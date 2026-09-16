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
            val known = dao.injectedIn(runId)

            val raw = cu.generateJson(prompt(run, episodes, known), cu.model)
            val cands = parse(raw)
            if (cands.isEmpty()) { log("[리플렉터] 배울 것 없음 (0건)"); return }

            // 모델이 신고한 출처를 우리가 가진 사실과 대조하기 위한 재료.
            val facts = CandidateFilter.RunFacts(
                turns = episodes.map { it.turn }.toSet(),
                taintedTurns = episodes.filter { it.hadSafety }.map { it.turn }.toSet(),
                pkgs = episodes.mapNotNull { it.pkg }.toSet(),
            )
            // 그 턴의 앱 버전을 붙여 둔다 — 무효화의 근거가 된다(설계 §7 4번).
            val versionOf = episodes.filter { it.pkg != null }
                .associate { it.pkg!! to it.pkgVersion }

            var kept = 0
            for (c in cands) {
                val why = CandidateFilter.reject(c, facts)
                if (why != null) { log("[리플렉터] 버림 — $why"); continue }
                val pkgVersion = c.pkg?.split(',')?.firstNotNullOfOrNull { versionOf[it.trim()] }
                try {
                    gateway.save(c.toMemory(runId, pkgVersion))
                    kept++
                    log("[리플렉터] 후보 저장(검토 대기) — ${c.assemble()}")
                } catch (e: Exception) {
                    // validate() 가 거절한 것. 필터를 통과해도 검색 키가 없으면 여기서 걸린다.
                    log("[리플렉터] 버림 — ${e.message}")
                }
            }
            Log.i("a11mem", "reflect $runId: 후보 ${cands.size} 중 $kept 건 저장")
        } catch (e: Exception) {
            // 여기서 터뜨리면 실행이 끝난 뒤에 앱이 죽는다. 흔적만 남긴다.
            Log.w("a11mem", "리플렉터 실패(무시)", e)
            log("⚠ [리플렉터] 실패 — ${e.javaClass.simpleName}")
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
    private fun prompt(run: RunEntity, eps: List<EpisodeEntity>, known: List<MemoryEntity>): String {
        val log = eps.joinToString("\n") { e ->
            "turn ${e.turn} | ${e.pkg ?: "?"} | ${e.action ?: "?"} | ${e.intent ?: ""}" +
                (if (e.result != "ok") " | result=${e.result}" else "")
        }
        val knownText = if (known.isEmpty()) "(none)"
        else known.joinToString("\n") { "- ${it.text}" }

        return """
You are reviewing one completed run of a phone-automation agent, to decide whether
anything about the APPS involved is worth remembering for next time.

GOAL: ${run.goal}
OUTCOME: ${run.outcome} (${run.turnsUsed} turns, limit ${run.maxTurns})

TURN LOG (action and the agent's own stated intent; you cannot see the screens):
$log

ALREADY KNOWN (these were shown to the agent during this run — do not repeat them):
$knownText

Return JSON: {"candidates": [ ... ]} with 0 or more items shaped like
  {"kind": "APP_FACT" | "PITFALL",
   "trigger": "<what someone does>",
   "consequence": "<what happens / what is then reachable>",
   "pkg": "<package name, comma-separated if several>",   // APP_FACT
   "keywords": "<goal words incl. synonyms, comma-separated>",  // PITFALL
   "source_turn": <the turn number this is based on>}

RULES
1. RETURNING ZERO CANDIDATES IS THE NORMAL, CORRECT ANSWER. Most runs teach nothing.
   If you are unsure, return none.
2. The agent that will read your note ALREADY has two things: the screenshot in front of
   it, and everything you know about how Android apps normally behave. A note that only
   repeats either of those is worthless — do not write it. Exactly two kinds of note are
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
3. Write what the app IS LIKE, not what to do. Describe structure, never give orders,
   and never tell the agent to skip a verification step.
4. Only durable app structure. Never record values read off the screen: names, people,
   message text, balances, prices, codes, search history, times, counts of the user's
   own items. If a fact would not be true on a stranger's phone, it is not a fact.
   Labels the app itself draws for everyone (menu names, tab names, button text) ARE
   part of the structure and belong in the note.
5. source_turn must be a turn number that appears in the log above.
   pkg must be a package that appears in the log above.
6. Keep trigger under 80 characters and consequence under 160.
""".trim()
    }

    /**
     * 모델 응답을 후보 목록으로. **파싱 실패는 0건으로 떨어뜨린다** — 되새김은 뒷정리라
     * 여기서 던져 봐야 할 수 있는 게 없다.
     *
     * `responseMimeType` 으로 JSON 을 강제해도 코드펜스를 씌워 오는 경우가 있어 한 번 벗긴다.
     */
    private fun parse(raw: String): List<Candidate> {
        val txt = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr: JSONArray = try {
            JSONObject(txt).optJSONArray("candidates") ?: JSONArray()
        } catch (e: Exception) {
            try { JSONArray(txt) } catch (e2: Exception) {
                Log.w("a11mem", "리플렉터 응답 파싱 실패: ${txt.take(200)}")
                return emptyList()
            }
        }
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Candidate(
                    kind = o.optString("kind"),
                    trigger = o.optString("trigger"),
                    consequence = o.optString("consequence"),
                    pkg = o.optString("pkg").ifBlank { null },
                    keywords = o.optString("keywords").ifBlank { null },
                    // 없으면 -1 → 출처 대조에서 걸린다. 0 으로 두면 안 된다(턴은 1부터다).
                    sourceTurn = o.optInt("source_turn", -1),
                )
            )
        }
        return out
    }
}
