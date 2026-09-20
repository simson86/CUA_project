package com.cua.a11.memory

import android.util.Log
import com.cua.a11.CuClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 실행이 끝나면 그 실행의 **기록을 읽고** 기억 후보를 뽑는다 (Unit 5b, 설계 §2).
 *
 * 입력 셋 — 셋째가 빠지면 *"리플렉터가 이미 아는 것을 매번 새로 발견한다"*:
 *  1. `run` — 목표가 뭐였고 성공했나
 *  2. `episode` — 턴별 액션·의도·앱 (주 재료)
 *  3. **그 실행에 주입됐던 기억** (`memory_recall`)
 *
 * ⚠️ **리플렉터는 화면을 못 본다. 로그만 본다.** 2026-09-16 의 실측이 정확히 이 조건에서
 * 났다 — 사람이 로그만 보고 쓴 메타클럽 문장이 거짓이었고 그 기억이 턴을 +27% 늘렸다.
 * 거기서 나온 규칙이 *"기억 문장의 근거는 로그가 아니라 화면이다"* 인데, 리플렉터는 그
 * 규칙을 지킬 수 없는 자리에 있다. **화면을 주는 것은 §3 이 막으려는 것을 모델 앞에
 * 펼치는 일**이라 먼저 *틀리는 비율부터 재기로 했다*(MEMORY.md).
 *
 * 저장은 항상 `PENDING` 이다 — 주입되지 않는다. 사람이 목록에서 올리거나(Unit 3),
 * reconciliation 이 재관측으로 승격시켜야(5c) 모델에 간다.
 */
class Reflector(
    private val cu: CuClient,
    private val dao: MemoryDao,
    private val gateway: MemoryGateway,
) {

    /**
     * 실행 1건을 되새긴다. **예외를 밖으로 내지 않는다** — 기억이 실행을 망가뜨리면 안 되고,
     * 이건 실행이 이미 끝난 뒤에 도는 뒷정리다.
     *
     * [log] 는 사람에게 보일 한 줄(앱 실행 로그). 버린 후보도 **사유와 함께** 남긴다 —
     * 안 남기면 나중에 "리플렉터가 0건만 낸다" 는 증상만 보이고 원인을 못 찾는다.
     */
    fun reflect(runId: String, log: (String) -> Unit = {}) {
        try {
            val run = dao.recentRuns(50).firstOrNull { it.id == runId } ?: return
            val episodes = dao.episodesOf(runId)
            if (episodes.isEmpty()) return

            val pkgs = episodes.mapNotNull { it.pkg }.toSet()
            // 대조 대상 — 이 실행에 붙을 수 있었던 기억들(PENDING 포함, RETIRED 제외).
            val existing = gateway.reconcileSet(pkgs, run.goal)

            val verdicts = parse(cu.generateJson(prompt(run, episodes, existing), cu.model))
            if (verdicts.isEmpty()) { log("[리플렉터] 배울 것 없음 (0건)"); return }

            // 모델이 신고한 출처를 우리가 가진 사실과 대조하기 위한 재료.
            val facts = CandidateFilter.RunFacts(
                turns = episodes.map { it.turn }.toSet(),
                taintedTurns = episodes.filter { it.hadSafety }.map { it.turn }.toSet(),
                pkgs = pkgs,
            )
            // 그 턴의 앱 버전을 붙여 둔다 — 무효화의 근거가 된다(설계 §7 4번).
            val versionOf = episodes.filter { it.pkg != null }
                .associate { it.pkg!! to it.pkgVersion }
            // 모델이 아무 id 나 대는 것을 막는다. 프롬프트에 실어 준 것만 만질 수 있다.
            val shown = existing.map { it.id }.toSet()
            // ★ 이 실행에 **주입됐던** 기억. NOOP 점수를 막는 데 쓴다(아래 apply 주석).
            val injected = dao.injectedIn(runId).map { it.id }.toSet()

            val tally = HashMap<String, Int>()
            for (v in verdicts) {
                val done = apply(v, runId, facts, versionOf, shown, injected, log)
                if (done) tally[v.verdict] = (tally[v.verdict] ?: 0) + 1
            }
            Log.i("a11mem", "reflect $runId: 판정 ${verdicts.size} 건 → $tally")
        } catch (e: Exception) {
            // 여기서 터뜨리면 실행이 끝난 뒤에 앱이 죽는다. 흔적만 남긴다.
            Log.w("a11mem", "리플렉터 실패(무시)", e)
            log("⚠ [리플렉터] 실패 — ${e.javaClass.simpleName}")
        }
    }

    /** 판정 1건 적용. 처리했으면 true. **무엇을 왜 버렸는지 반드시 남긴다.** */
    private fun apply(
        v: Verdict, runId: String, facts: CandidateFilter.RunFacts,
        versionOf: Map<String, Long?>, shown: Set<Long>, injected: Set<Long>,
        log: (String) -> Unit,
    ): Boolean {
        // ★ 모델이 만지겠다는 기억은 우리가 보여준 것이어야 한다. 안 그러면 프롬프트에
        //   없던 id 를 지어내 엉뚱한 기억을 은퇴시킬 수 있다.
        if (v.verdict != "ADD" && (v.id == null || v.id !in shown)) {
            log("[리플렉터] 버림 — ${v.verdict}: 보여주지 않은 id(${v.id})")
            return false
        }
        fun store(c: Candidate): MemoryEntity? {
            CandidateFilter.reject(c, facts)?.let { log("[리플렉터] 버림 — $it"); return null }
            val ver = c.pkg?.split(',')?.firstNotNullOfOrNull { versionOf[it.trim()] }
            return c.toMemory(runId, ver)
        }
        return try {
            when (v.verdict) {
                "NOOP" -> {
                    // ★ **주입됐던 기억의 재관측은 증거가 아니다.** 기억이 "메뉴를 눌러라"
                    //   라고 말했고 → 에이전트가 그대로 했고 → 로그에 그게 찍혔고 →
                    //   리플렉터가 그걸 보고 "또 봤다" 고 하는 순환이다. 당연히 확인된다.
                    //   명세의 *"2회는 반드시 실행 간"* 이 요구하는 것은 **독립된 관측**이다.
                    //
                    //   실측 2026-09-17: 막기 전에는 한 실행에서 성공 보너스(+1)와 NOOP(+1)이
                    //   겹쳐 `score 2 → 4`(상한)로 뛰었다. 자기강화가 수치로 보인 것이다.
                    if (v.id in injected) {
                        log("[리플렉터] 재관측이지만 점수 없음 — #${v.id} 는 이번 실행에 주입됐다")
                        return false
                    }
                    gateway.noop(v.id!!)
                    log("[리플렉터] 재관측 — #${v.id} (${v.why})")
                    true
                }
                "DUPLICATE" -> {
                    // 남길 쪽도 우리가 보여준 것이어야 한다 — 위의 id 검사는 접는 쪽만 본다.
                    val keep = v.sameAs
                    if (keep == null || keep !in shown) {
                        log("[리플렉터] 버림 — DUPLICATE: 보여주지 않은 sameAs($keep)")
                        return false
                    }
                    val ok = gateway.mergeInto(v.id!!, keep)
                    log(if (ok) "[리플렉터] 중복 접음 — #${v.id} → #$keep (${v.why})"
                        else "[리플렉터] 중복 접기 건너뜀 — #${v.id}")
                    ok
                }
                "DELETE" -> {
                    val ok = gateway.retireByVerdict(v.id!!)
                    log(if (ok) "[리플렉터] 무효화 — #${v.id} (${v.why})"
                        else "[리플렉터] 무효화 건너뜀 — #${v.id} 은 고정된 기억")
                    ok
                }
                "ADD" -> {
                    val m = store(v.candidate ?: return false) ?: return false
                    gateway.save(m)
                    log("[리플렉터] 후보 저장(검토 대기) — ${m.text}")
                    true
                }
                "UPDATE" -> {
                    val m = store(v.candidate ?: return false) ?: return false
                    val newId = gateway.supersede(v.id!!, m)
                    log(if (newId != null) "[리플렉터] 대체 — #${v.id} → #$newId (${v.why})"
                        else "[리플렉터] 대체 건너뜀 — #${v.id} 은 고정된 기억")
                    newId != null
                }
                else -> { log("[리플렉터] 버림 — 알 수 없는 판정 ${v.verdict}"); false }
            }
        } catch (e: Exception) {
            // validate() 가 거절한 것 등. 필터를 통과해도 검색 키가 없으면 여기서 걸린다.
            log("[리플렉터] 버림 — ${e.message}")
            false
        }
    }

    // ── 프롬프트 ─────────────────────────────────────────────────────────
    /**
     * **가장 중요한 부분은 "0건이 정답일 때가 많다" 이다**(설계 §2). 억지로 뽑은 후보가
     * 곧 노이즈가 되고, 그 노이즈는 reconciliation 대조 비용까지 늘린다.
     *
     * 두 번째 — **읽는 쪽이 이미 가진 것을 빼고 남는 것만** 쓰게 한다. 에이전트는 ⑴ 눈앞의
     * 스크린샷과 ⑵ 안드로이드 앱 일반 관례에 대한 사전지식을 이미 갖고 있다. 둘 중 하나를
     * 되풀이하는 문장은 값이 0 이다.
     *
     * 그래서 값이 있는 것은 정확히 두 갈래다:
     *  · **방향** — *"X 를 하면 Y 에 닿는다"*. 화면이 못 주는 것(다음 화면)이라 **턴을 줄인다.**
     *    2026-09-16 실측: 경로 기억 −47%, *"검색해도 없다"* 는 부정 사실만 준 기억 **0%**.
     *    값어치는 그 문장이 **후보를 몇 개나 지우느냐**에 비례한다.
     *  · **예외** — *"이 앱은 관례를 어긴다"*. 사전지식이 못 주는 것이라 **실수를 막는다.**
     *    턴은 한 개도 안 줄일 수 있지만, 모델이 **자신 있게 틀리는 것**을 막는다.
     *
     * ⚠️ **초판에는 앞엣것만 있었다.** 규칙이 *"후보를 가장 많이 지우는 것"* 뿐이라
     * 경로 쪽으로만 유도했는데, 관례 위반은 후보를 많이 지우지 않는다. 그리고 **우리
     * 측정은 턴 수만 쟀지 정확도를 잰 적이 없어서**(MEMORY.md 의 열린 질문) 그 공백이
     * 수치로는 안 보였다.
     */
    /**
     * ⚠️ **`keywords` 는 목표 문장의 언어로 써야 한다** — 실측으로 드러난 함정(2026-09-21).
     *
     * 판단 ② 배치 1 에서 리플렉터가 만든 첫 `PITFALL` 의 키워드가
     * `search bar, enter key, icon, click` 이었다. 매칭은
     * `goal.lowercase().contains(keyword)` 인데 우리 목표는 한국어다 —
     * **영원히 안 걸린다.** 저장은 되고 승격 경로도 없어(주입이 안 되니 NOOP 대상도 아니다)
     * `PITFALL` 이라는 범주가 통째로 죽어 있었다.
     *
     * 원인은 모델이 아니라 프롬프트였다 — **매칭 방식을 설명한 적이 없다.** `text` 는
     * 영문 권장이라 모델이 `keywords` 도 같은 언어로 맞춘 것이 오히려 자연스럽다.
     * 규칙 7 이 그 설명이다(예시까지 넣은 이유: 규칙만으로는 `text` 의 영문 관례와
     * 충돌하는 것처럼 보인다).
     */
    private fun prompt(run: RunEntity, eps: List<EpisodeEntity>, existing: List<MemoryEntity>): String {
        val log = eps.joinToString("\n") { e ->
            "turn ${e.turn} | ${e.pkg ?: "?"} | ${e.action ?: "?"} | ${e.intent ?: ""}" +
                (if (e.result != "ok") " | result=${e.result}" else "")
        }
        // id 를 반드시 같이 준다 — 판정이 id 로 돌아와야 어느 기억을 가리키는지 알 수 있다.
        val existingText = if (existing.isEmpty()) "(none)"
        else existing.joinToString("\n") { "  ${it.id} | ${it.state} | ${it.text}" }

        return """
You are reviewing one completed run of a phone-automation agent, to decide whether
anything about the APPS involved is worth remembering for next time.

GOAL: ${run.goal}
OUTCOME: ${run.outcome} (${run.turnsUsed} turns, limit ${run.maxTurns})

TURN LOG (action and the agent's own stated intent; you cannot see the screens):
$log

EXISTING MEMORIES for these apps (id | state | text) — you may confirm, replace or
invalidate these, using their id:
$existingText

Return JSON: {"verdicts": [ ... ]}, zero or more of:
  {"verdict":"ADD",    "kind":"APP_FACT"|"PITFALL", "trigger":"…", "consequence":"…",
                       "pkg":"…", "keywords":"… (PITFALL only — in the GOAL's language, see rule 7)",
                       "source_turn":N, "why":"…"}
  {"verdict":"NOOP",   "id":N, "why":"…"}     // this run showed the same thing again
  {"verdict":"DELETE", "id":N, "why":"…"}     // this run contradicted it
  {"verdict":"UPDATE", "id":N, "kind":…, "trigger":…, "consequence":…, "pkg":…,
                       "source_turn":N, "why":"…"}   // replace with a better statement
  {"verdict":"DUPLICATE", "id":N, "sameAs":M, "why":"…"}
                       // two memories ALREADY IN THE LIST say the same thing;
                       // id is the one to fold away, sameAs is the one to keep

RULES
1. RETURNING ZERO VERDICTS IS THE NORMAL, CORRECT ANSWER. Most runs teach nothing.
   If you are unsure, return none.
2. NOOP MATTERS AS MUCH AS ADD. If this run showed something an existing memory already
   says — even worded completely differently — return NOOP with its id instead of writing
   it again as ADD. A memory in state PENDING is unverified and is NOT being shown to the
   agent; NOOP is the only way it can ever become trusted, so look for it deliberately.
3. ALSO LOOK AT THE LIST ITSELF. If two memories already in the list say the same
   thing in different words, return DUPLICATE for the weaker one, keeping the clearer
   or more specific one. Injection budget is only two lines per app, so a duplicate
   costs half of it. This is separate from NOOP — NOOP compares this run against one
   memory; DUPLICATE compares two memories against each other.
4. The agent that will read these notes ALREADY has two things: the screenshot in front
   of it, and everything you know about how Android apps normally behave. A note that
   only repeats either of those is worthless — do not write it. Exactly two kinds are
   worth writing:
   (a) DIRECTION — "doing X leads to Y". This is what saves turns: it tells the agent
       where to go next, which the screen cannot. Prefer the note that narrows the search
       the most. "Tapping X leads to Y" is worth far more than "Y is not under X"; ruling
       one place out leaves the agent still searching and measurably saves nothing.
   (b) SURPRISE — where THIS app contradicts what you would expect of a typical Android
       app: a control that does not do what its label or shape suggests, a back gesture
       that exits instead of going back, a list that reorders between visits, a keypad
       whose digits move. These may not save a single turn, but they stop the agent from
       confidently doing the wrong thing, which is worth just as much.
5. Write what the app IS LIKE, not what to do. Describe structure, never give orders,
   and never tell the agent to skip a verification step.
6. NEVER record anything that belongs to THIS PERSON rather than to the app: contact or
   people names, message text, balances, prices, codes, their search history, their own
   items or counts, or habits you inferred about them. Test: would it still be true on a
   stranger's phone? If not, leave it out — and DELETE an existing memory that contains it.
   The opposite is also true: labels the app draws for everyone — menu names, tab names,
   button text — ARE the structure and must be written, even though they are proper nouns.
   "Tapping '메가선생님' opens the teacher list" is structure.
   "The top chat is '엄마'" is this person's data.
7. PITFALL keywords decide whether the note is ever shown. They are matched as LITERAL
   SUBSTRINGS against the user's goal sentence, which is written in THEIR OWN language
   (see GOAL above). Write the keywords in THAT SAME language, choosing words that would
   actually appear in a request like it, comma-separated, with synonyms.
   For GOAL "메가스터디에서 민동휘 강사의 가장 최신 수강평이 뭔지 알려줘":
     keywords "수강평,강사,최신순,정렬"   -> matches, the note gets shown
     keywords "course reviews,sorting"    -> NEVER matches, the note is dead weight
   English keywords cannot match a Korean goal. If you cannot name words that would
   appear in such a goal, make it an APP_FACT keyed by pkg instead.
8. source_turn must be a turn number that appears in the log above.
   pkg must be a package that appears in the log above.
   id and sameAs must be ids listed above.
9. Keep trigger under 80 characters and consequence under 160.
10. Every verdict needs a short "why".
""".trim()
    }

    /**
     * 모델이 내린 판정 1건. `ADD`/`UPDATE` 만 [candidate] 를 갖고, `NOOP`/`DELETE` 는 [id] 만 쓴다.
     * [why] 는 사람이 읽을 근거 — 판정이 헛짚었을 때 **왜 그랬는지**를 남기는 유일한 자리다.
     */
    private data class Verdict(
        val verdict: String,
        val id: Long?,
        /** `DUPLICATE` 에서 **남길** 쪽. 접히는 쪽은 [id] 다. */
        val sameAs: Long?,
        val candidate: Candidate?,
        val why: String,
    )

    /**
     * 모델 응답을 판정 목록으로. **파싱 실패는 0건으로 떨어뜨린다** — 되새김은 뒷정리라
     * 여기서 던져 봐야 할 수 있는 게 없다.
     *
     * `responseMimeType` 으로 JSON 을 강제해도 코드펜스를 씌워 오는 경우가 있어 한 번 벗긴다.
     */
    private fun parse(raw: String): List<Verdict> {
        val txt = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr: JSONArray = try {
            JSONObject(txt).optJSONArray("verdicts") ?: JSONArray()
        } catch (e: Exception) {
            try { JSONArray(txt) } catch (e2: Exception) {
                Log.w("a11mem", "리플렉터 응답 파싱 실패: ${txt.take(200)}")
                return emptyList()
            }
        }
        val out = ArrayList<Verdict>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("verdict").uppercase()
            val hasCand = kind == "ADD" || kind == "UPDATE"
            out.add(
                Verdict(
                    verdict = kind,
                    // 0 은 "없음" 과 구분이 안 된다(id 는 1부터). null 로 떨어뜨려 위에서 걸리게.
                    id = o.optLong("id", 0L).takeIf { it > 0L },
                    sameAs = o.optLong("sameAs", 0L).takeIf { it > 0L },
                    candidate = if (!hasCand) null else Candidate(
                        kind = o.optString("kind"),
                        trigger = o.optString("trigger"),
                        consequence = o.optString("consequence"),
                        pkg = o.optString("pkg").ifBlank { null },
                        keywords = o.optString("keywords").ifBlank { null },
                        // 없으면 -1 → 출처 대조에서 걸린다. 0 으로 두면 안 된다(턴은 1부터다).
                        sourceTurn = o.optInt("source_turn", -1),
                    ),
                    why = o.optString("why").ifBlank { "이유 없음" },
                )
            )
        }
        return out
    }
}
