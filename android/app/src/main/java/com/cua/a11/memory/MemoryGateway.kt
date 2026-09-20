package com.cua.a11.memory

import android.util.Log

/**
 * DB 를 만지는 **유일한 출입구**. 읽기·쓰기·정리가 전부 여기를 지난다.
 *
 * 왜 하나로 좁히나 — SQL 이 호출부마다 흩어지면 예산 상한이나 민감도 필터를 어딘가에서
 * 빠뜨리게 된다. 새 읽기 경로를 추가할 때 특히 그렇다. 출입구가 하나면 거기만 지키면 된다.
 *
 * 설계: docs/reference/android_run-memory-2026-09-12.html §6
 */
class MemoryGateway(private val dao: MemoryDao) {

    companion object {
        // 주입 예산(설계 원칙 4). **최적화가 아니라 알고리즘의 일부다** — 기억이 쌓일수록
        // 모델의 주의가 화면에서 텍스트로 옮겨간다. 코드로 강제해야 하는 이유다.
        private const val APP_FACT_LINES = 2
        private const val PITFALL_LINES = 2

        /** 열린 결정 N2 — 근거 없는 값이다. `pkgVersion` 변경 간격에서 역산하기로 돼 있다. */
        private const val RECENCY_HALFLIFE_DAYS = 30.0

        /**
         * **`ACTIVE` 상한** (열린 결정 N3 — 500 은 명세값이고 근거 없다).
         *
         * ⚠️ **명세는 "memory 총 500건" 인데 여기서는 `ACTIVE` 만 센다.** 명세의 상한은
         * 초과분을 `PENDING` 으로 **강등**하는데(삭제 아님), 강등은 행 수를 안 줄인다. 그래서
         * 총 행 수를 세면 강등을 아무리 해도 500 밑으로 못 내려간다 — 명세에서 그 문제를
         * 푸는 것은 `PENDING` 90일 삭제(보존 정책)인데, **이 프로젝트는 보존 정책을 뺐다**
         * (한 달짜리라 90일 규칙이 발동하지 않는다). 그러면 **`ACTIVE` 를 세는 것만이 강등으로
         * 실제로 해소되는 상한**이다. 그리고 모델에 실리고 예산을 다투는 것도 `ACTIVE` 다.
         */
        const val ACTIVE_CAP = 500

        /**
         * **`PENDING` 상한** — 근거 없는 값이다(N3 와 같은 처지). 다만 유도 과정은 남긴다.
         *
         * ★ **실제로 차는 건 이쪽이다.** 리플렉터가 만드는 건 전부 `PENDING` 이고, 나가는
         * 문은 **승격(재관측)뿐**이다. 한 번만 쓰는 앱의 후보는 재관측될 일이 없어 영원히
         * 남는다. 반면 `ACTIVE` 는 승격된 것만 들어오고 실패 강등·`DELETE`·`DUPLICATE`·
         * 상한까지 나가는 문이 넷이다. **`ACTIVE 500` 은 이 프로젝트에서 사실상 장식이고,
         * 진짜 작동하는 상한은 이것이다.**
         *
         * 조이는 게 더 중요한 이유도 있다 — **reconciliation 프롬프트에 `PENDING` 도 전부
         * 실린다**(`reconcileSet` 은 `RETIRED` 만 뺀다). 검증 안 된 후보가 프롬프트 비용을
         * 직접 만든다. 200 이면 앱 10개 기준 한 앱당 ~20건, ~400 토큰이라 감당된다.
         *
         * ⚠️ **명세의 규칙은 시간이었다** — *"`PENDING` 90일 무변동 삭제"*. 한 달짜리
         * 프로젝트에서는 발동하지 않아 뺐고, 건수로 대신한다. **둘은 목적이 다르다**
         * (시간=신선도, 건수=자원). 다만 **고르는 순서는 같다** — `PENDING` 은 점수가 거의
         * 다 1이라 `rank()` 가 사실상 `recency` 순이고, 그래서 **가장 오래되고 한 번도 안
         * 걸린 것부터** 지운다. 갓 만든 후보는 `recency` 가 높아 자동으로 보호된다.
         */
        const val PENDING_CAP = 200

        /** 우리 앱 자신. 에이전트가 *조작하는* 앱이 아니라 *실행이 시작된 곳*이라 앱 지식이 될 수 없다. */
        const val OWN_PACKAGE = "com.cua.a11"

        /**
         * **사람이 써 넣은 기억의 시작 점수**(열린 결정 N5, 2026-09-16 확정).
         *
         * 기본값 1 로 두면 `fail`(최대 턴 도달) **한 번에 score 0 → RETIRED** 다.
         * 측정에서 이게 조용한 오염이 된다 — 30회 배치 중 B 실행 하나가 20턴을 넘기면
         * 그 순간 기억이 은퇴하고 **이후 모든 B 가 사실상 A 가 된다.**
         *
         * 게다가 `fail` 은 *"기억이 틀렸다"* 가 아니라 *"20턴 안에 못 끝냈다"* 이다 —
         * 앱이 느렸을 수도, 과제가 길었을 수도 있다.
         *
         * 명세 §7 은 승격 쪽에서 *"한 번은 우연일 수 있다"* 며 2회를 요구한다. 강등도
         * 대칭이어야 한다. 그리고 명세는 **사람의 판단을 '즉시 ACTIVE'** 로 인정하므로,
         * 그 판단이 실패 한 번보다는 무거운 게 맞다.
         *
         * ⚠️ **리플렉터가 만든 기억에는 쓰지 말 것** — 그쪽은 아직 검증 안 된
         * 후보라 기본값 1(그리고 `PENDING`)에서 시작해야 한다.
         */
        const val HUMAN_SCORE = 2

        // ── 목록 UI 가 쓰는 값 목록 (Unit 3) ───────────────────
        //  RECIPE 는 일부러 뺐다 — 읽는 쪽(Unit 9)이 아직 없어서, 넣을 수 있게 해두면
        //  사용자가 **아무도 안 읽는 행**을 만들게 된다. Unit 9 에서 함께 연다.
        val KINDS = listOf("APP_FACT", "PITFALL")
        val STATES = listOf("ACTIVE", "PENDING", "RETIRED")
        val SENSITIVITIES = listOf("normal", "restricted")

        /**
         * 이 기억이 **어느 앱에서 보이나**.
         *  · `pkg`    — `pkg` 칸의 패키지(쉼표로 여러 개) 중 하나가 떠 있을 때
         *  · `system` — 패키지를 안 본다. 항상 후보
         *
         * 설계 명세는 `vendor` 도 두지만 **구현하지 않았다.** 접두사로 제조사를 판정하려
         * 했는데 실제 데이터가 그걸 허락하지 않는다 — 같은 삼성 기능인데도
         * `com.android.settings` · `com.samsung.android.lool` · `com.sec.android.app.myfiles`
         * 로 공통 접두사가 없다. 억지로 목록을 박아 넣느니 쉼표 목록이 정직하다.
         */
        val SCOPES = listOf("pkg", "system")

        // ── 리플렉터 스위치 (Unit 5b) ───────────────────────────
        //  왜 설정이 필요한가 — 측정 배치에서는 꺼야 한다. 켜 두면 실행마다 API 를 한 번
        //  더 쓰고, 무엇보다 **측정 중에 기억이 늘어난다.** 반대로 판단 ②(종단 측정)는
        //  켜 놓고 오래 돌려야 성립하므로 '소켓이면 끈다' 같은 고정 규칙으로는 안 된다.
        //
        //  ★ 기본값은 꺼짐이다. 자동 쓰기는 사람이 한 번 켜는 동작을 거쳐야 한다 —
        //    §3 의 C층(모델 판정)이 아직 없어서 지금은 D층(목록 UI)이 유일한 내용 방어선이다.
        private const val PREFS = "cua"
        private const val KEY_REFLECTOR = "reflector_enabled"

        fun reflectorEnabled(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_REFLECTOR, false)

        fun setReflectorEnabled(ctx: android.content.Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REFLECTOR, on).apply()
        }
    }

    /**
     * 지금 떠 있는 앱에 대한 참고사항. 없으면 null — 그때 요청 본문은 종전과 동일하다.
     *
     * [pkgVersion] 은 `version_match` 항에만 쓴다(없으면 그 항이 1.0 이 된다).
     */
    fun readForApp(pkg: String, pkgVersion: Long? = null): String? = guard {
        if (pkg == OWN_PACKAGE) return@guard null
        val now = System.currentTimeMillis()
        val hits = dao.activeAppFacts(now)
            .filter { appliesTo(it, pkg) }
            .sortedByDescending { rank(it, pkgVersion, now) }
            .take(APP_FACT_LINES)
        emit(hits, now)
    }

    // ── 랭킹 (Unit 7, 설계 §5 ③단계) ────────────────────────────────────
    /**
     * 예산 안에 무엇을 넣을지 정하는 점수. **명세 §5 의 네 항을 그대로** 쓴다.
     *
     * ```
     * 0.40·relevance + 0.25·confidence + 0.15·recency + 0.20·version_match
     * ```
     *
     * ⚠️ **가중치 넷은 근거가 없다.** 명세가 직접 그렇게 적어 뒀다 — *"이 숫자들은
     * 시작값이지 정답이 아니다 … 그 전까지는 **'이런 항들을 본다'는 구조만** 확정된 것으로
     * 읽어야 한다."* (열린 결정 N1). **지금은 튜닝할 재료가 없다** — 후보가 예산(2줄)보다
     * 많아야 정렬이 의미를 갖는데, 기억이 그만큼 안 쌓였다.
     *
     * **고친 것은 그 전의 정렬이다.** 종전에는 `ORDER BY numRecalled DESC` 였는데 명세의
     * 표현으로 **인기투표**다 — 매일 여는 앱의 기억이 관련도·신뢰도와 무관하게 항상 이기고,
     * `numRecalled` 는 **주입된 횟수**라 *"50번 실패한 실행에 매번 들어간 기억도 50"* 이 된다.
     * 점수식이 아직 근거가 없어도 **그건 명백히 틀렸다.**
     */
    private fun rank(m: MemoryEntity, pkgVersion: Long?, now: Long): Double {
        // relevance — 얼마나 이 상황의 것인가.
        //  명세는 PITFALL 에 정규화된 bm25 를 쓰지만 우리는 FTS5 가 없고 코틀린 문자열
        //  매칭이라 **걸렸다/아니다** 뿐이다. 걸린 것만 여기 오므로 1.0.
        //  scope='system' 은 명세의 `vendor` 자리다 — 앱을 가리지 않는 만큼 덜 구체적이라
        //  같은 0.7 을 준다(명세에 `system` 항은 없다. 대입한 것임을 밝혀 둔다).
        val relevance = when {
            m.kind == "PITFALL" -> 1.0
            m.scope == "system" -> 0.7
            else -> 1.0
        }
        // confidence — 얼마나 믿을 만한가. score 0~4 를 0~1 로.
        //  ⚠️ 명세는 여기에 **⑦의 시간 감쇠를 곱하라**고 한다. 그 감쇠가 Unit 8 이라
        //  지금은 **빠져 있다.** 자리만 비워 둔 것이다.
        val confidence = m.score.coerceIn(0, 4) / 4.0
        // recency — 최근에 쓰였나. 한 번도 안 걸렸으면 추가된 시각을 쓴다.
        //  반감기 30일은 **근거 없는 값**이다(열린 결정 N2 — pkgVersion 변경 간격에서
        //  역산하기로 돼 있는데 아직 데이터가 없다).
        val last = m.lastAccessed ?: m.timeAdded
        val days = (now - last).coerceAtLeast(0L) / 86_400_000.0
        val recency = Math.exp(-Math.log(2.0) * days / RECENCY_HALFLIFE_DAYS)
        // version_match — **네 항 중 유일하게 결정론적**이다. 나머지 셋은 추정값이지만
        //  longVersionCode 는 사실이다. 명세가 0.20 을 준 이유다.
        //  양쪽 중 하나라도 모르면 1.0 — 벌점을 줄 근거가 없다(사람이 UI 로 넣은 기억은
        //  pkgVersion 이 null 이다).
        val versionMatch = when {
            m.pkgVersion == null || pkgVersion == null -> 1.0
            m.pkgVersion == pkgVersion -> 1.0
            else -> 0.4
        }
        return 0.40 * relevance + 0.25 * confidence + 0.15 * recency + 0.20 * versionMatch
    }

    /**
     * 이 기억이 [pkg] 가 떠 있을 때 보여야 하나.
     *
     * 한 과제가 여러 패키지를 넘나들기 때문에 `pkg` 는 **쉼표 목록**을 받는다
     * (근거는 MemoryDao.activeAppFacts 주석 — 74턴 중 22턴에만 붙던 실측).
     */
    private fun appliesTo(m: MemoryEntity, pkg: String): Boolean =
        when (m.scope) {
            "system" -> true
            else -> m.pkg?.split(',')?.any { it.trim() == pkg } == true
        }

    /**
     * 목표 문장에 걸리는 함정. 목표는 실행 내내 안 바뀌므로 호출부가 값을 캐시한다.
     *
     * 매칭이 취약하다 — "휴지통에 넣어줘"는 '삭제'에 안 걸린다. 그래서 keywords 에
     * **동의어를 함께** 넣어야 하고, 그 생성은 Unit 5 의 리플렉터 몫이다.
     */
    fun readForTask(goal: String): String? = guard {
        val now = System.currentTimeMillis()
        val g = goal.lowercase()
        val hits = dao.activePitfalls(now)
            .filter { m -> m.keywords?.split(',')
                ?.any { k -> k.trim().takeIf { it.isNotEmpty() }?.let { g.contains(it.lowercase()) } == true } == true }
            .sortedByDescending { rank(it, null, now) }   // PITFALL 은 앱에 안 묶인다
            .take(PITFALL_LINES)
        emit(hits, now)
    }

    /** 주입한 것을 기록한다 — 이게 다음 읽기의 순위를 만든다. */
    private fun emit(hits: List<MemoryEntity>, now: Long): String? {
        if (hits.isEmpty()) return null
        dao.markRecalled(hits.map { it.id }, now)
        hits.forEach { injected.merge(it.id, 1, Int::plus) }
        return hits.joinToString(" ") { it.text }
    }

    // ── 승격 카운터 (Unit 6) ─────────────────────────────────────────────
    /**
     * 이번 실행에 주입된 기억 — `id → 주입 횟수`. 실행이 끝날 때 표로 내보내고 비운다.
     *
     * **왜 RAM 을 거치나 — 주입 시점에는 `runId` 가 없다.** `readForApp`/`readForTask` 는
     * `Executor` 계약을 통해 불리는데 그 계약에 `runId` 가 없고, 넣으면 판단 코어가
     * 기억을 알게 된다. 그래서 id 만 모아 뒀다가 `runId` 가 확정된 `onRunEnd` 에서 쓴다.
     *
     * ⚠️ **실행 시작에서 비우면 안 된다.** `runAgent` 는 `taskNote()` 를 `onRunStart` 보다
     * **먼저** 부른다 — 시작에서 비우면 방금 담긴 PITFALL 이 지워져 그 기억은 영영 점수를
     * 못 받는다. 읽기 실패 깃발이 "종료에서 소비"인 것과 **정확히 같은 이유**다.
     *
     * 동시 실행은 `agentBusy`(앱 UI·소켓 공유)가 막으므로 실행 하나분만 담긴다.
     */
    private val injected = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /**
     * 실행 종료 처리. 주입 기록을 남기고 결과에 따라 점수를 움직인다.
     *
     * **`outcome` 넷 중 둘만 증거다:**
     * | `success` | +1 (상한 4) | 실제로 통했다 |
     * | `fail` | −1 | 최대 턴까지 갔다 — 약한 신호 |
     * | `aborted` | **건드리지 않음** | 사람이 멈췄거나 승인을 거부했다 |
     * | `error` | **건드리지 않음** | 예외 — 화면 꺼짐·네트워크·dispatch 실패 |
     *
     * ⚠️ `aborted`·`error` 를 실패로 묶으면 **Wi-Fi 가 끊긴 것만으로 멀쩡한 기억이 두 번
     * 만에 RETIRED 로 내려간다.** `runAgent` 의 `outcome` 초기값이 `"error"` 라서
     * (예외로 빠져나가면 아무도 못 덮는다) "성공이 아니면 깎는다"로 짜는 순간 **모든 예외가
     * 곧 강등**이 된다. 반드시 두 값을 이름으로 집어서 분기할 것.
     *
     * 주입 기록 자체는 네 경우 모두 남긴다 — 점수와 무관하게 **주입은 일어난 사실**이다.
     */
    fun settleRun(runId: String, outcome: String) {
        // ★ 먼저 스냅샷을 뜨고 비운다. 아래에서 DB 가 실패해도 이번 실행의 id 가
        //   다음 실행으로 새지 않는다 — 새면 엉뚱한 기억이 남의 성적표를 받는다.
        val snapshot = injected.toMap()
        injected.clear()
        if (snapshot.isEmpty()) return

        val now = System.currentTimeMillis()
        dao.insertRecalls(snapshot.map { (id, n) -> MemoryRecallEntity(runId, id, n, now) })

        val ids = snapshot.keys.toList()
        when (outcome) {
            "success" -> { dao.raiseScore(ids); dao.activateProven(ids) }
            "fail"    -> { dao.lowerScore(ids); dao.retireExhausted(ids, now) }
            else      -> Unit   // aborted · error — 기억에 대한 증거가 아니다
        }
    }

    // ── reconciliation (Unit 5c) ────────────────────────────────────────
    /**
     * 이 실행과 **대조할 기억들**. 리플렉터 프롬프트에 id 와 함께 실린다.
     *
     * 고르는 기준은 *"이 실행에 붙을 수 있었던 것"* 이다 — `APP_FACT` 는 이 실행에서 실제로
     * 간 앱의 것, `PITFALL` 은 목표 문장에 걸리는 것. 주입 경로와 같은 조건을 쓴다.
     *
     * ⚠️ **`PENDING` 을 반드시 포함한다.** 재관측이 `PENDING` 의 **유일한 승격 경로**라
     * 모델이 그걸 봐야 *"같은 걸 또 봤다"(NOOP)* 고 말할 수 있다. 빼면 검역이 영영 안 풀린다.
     * 반대로 `RETIRED` 는 DAO 에서 이미 빠져 있다.
     */
    fun reconcileSet(pkgs: Set<String>, goal: String): List<MemoryEntity> {
        val g = goal.lowercase()
        return dao.reconcilable().filter { m ->
            when (m.kind) {
                "PITFALL" -> m.keywords?.split(',')
                    ?.any { k -> k.trim().takeIf { it.isNotEmpty() }?.let { g.contains(it.lowercase()) } == true } == true
                else -> pkgs.any { appliesTo(m, it) }
            }
        }
    }

    /** `NOOP` — 재관측됐다. 점수를 올리고 `>= 2` 면 검역을 푼다. */
    fun noop(id: Long) {
        val ids = listOf(id)
        dao.raiseScore(ids)
        if (dao.activateProven(ids) > 0) enforceCap()   // 검역이 풀려 ACTIVE 가 늘었다
    }

    /** `DELETE` — 즉시 은퇴. 돌려주는 값이 false 면 `pinned` 라 건너뛴 것이다. */
    fun retireByVerdict(id: Long): Boolean =
        dao.retireByVerdict(id, System.currentTimeMillis()) > 0

    /**
     * `UPDATE` — 새 문장을 넣고 기존을 그쪽으로 넘긴다.
     *
     * **순서가 중요하다**: 새 행을 먼저 넣어야 `supersededBy` 에 넣을 id 가 생긴다. 그리고
     * 기존이 `pinned` 면 **아무것도 하지 않는다** — 새 행만 넣으면 사람이 쓴 문장과 거의 같은
     * 기억이 둘이 되어 예산만 잡아먹는다.
     */
    fun supersede(oldId: Long, new: MemoryEntity): Long? {
        val old = dao.allMemories().firstOrNull { it.id == oldId } ?: return null
        if (old.pinned) return null
        val newId = save(new)
        dao.supersede(oldId, newId, System.currentTimeMillis())
        return newId
    }

    /**
     * `DUPLICATE` — **기존 기억 둘이 같은 말**일 때 하나를 접는다. [dupId] 를 은퇴시키고
     * `supersededBy` 로 [keepId] 를 가리킨다.
     *
     * `UPDATE` 와 메커니즘이 같고 **새 행을 안 만드는 것**만 다르다 — 대체할 문장이
     * 새로 오는 게 아니라 **이미 목록에 있다.**
     *
     * ⚠️ **점수를 합치지 않는다.** 남는 쪽 값을 그대로 둔다. 합치면 근거 없이 부풀고,
     * 상한 4 가 있는 이유(매일 쓰는 앱의 기억이 무한정 커지지 않게)와도 어긋난다.
     */
    fun mergeInto(dupId: Long, keepId: Long): Boolean {
        if (dupId == keepId) return false
        val all = dao.allMemories()
        val dup = all.firstOrNull { it.id == dupId } ?: return false
        if (all.none { it.id == keepId }) return false   // 남길 쪽이 실재해야 한다
        if (dup.pinned) return false                     // 사람이 쓴 것은 모델이 안 접는다
        dao.supersede(dupId, keepId, System.currentTimeMillis())
        return true
    }

    /** 목록 UI·측정용. `memoryId → (실행 수, 주입 횟수, 성공 수)`. */
    fun recallStats(): Map<Long, RecallStat> = dao.recallStats().associateBy { it.memoryId }

    // ── 목록 UI (Unit 3) ─────────────────────────────────────────────────
    //  **읽기와 달리 여기서는 실패를 삼키지 않는다.** 에이전트 경로에서 "기억이 없다"와
    //  "DB 가 깨졌다"는 결과가 같지만(둘 다 note == null 로 종전대로 돈다), 사람이 손으로
    //  쓴 것이 조용히 사라지는 건 다른 종류의 실패다 — 사용자는 저장된 줄 알고 화면을 뜬다.
    //  그래서 여기서 던진 예외는 호출부(MemoryActivity)가 받아 화면에 띄운다.

    fun list(): List<MemoryEntity> = dao.allMemories()

    /** id == 0 이면 새로 넣고, 아니면 덮어쓴다. 돌려주는 값은 그 행의 id. */
    fun save(m: MemoryEntity): Long {
        validate(m)?.let { throw IllegalArgumentException(it) }
        val id = if (m.id == 0L) dao.insertMemory(m) else { dao.updateMemory(m); m.id }
        // 새로 넣은 것만 상한을 건드린다. 편집(id != 0)은 개수를 안 바꾼다.
        if (m.id == 0L) enforceCap()
        return id
    }

    // ── 용량 상한 (Unit 8) ──────────────────────────────────────────────
    /**
     * `ACTIVE` 가 [cap] 을 넘으면 **랭킹 최하위부터** `PENDING` 으로 내린다. 내린 수를 돌려준다.
     *
     * `ACTIVE` 가 늘어나는 자리 둘에서 부른다 — `save()`(사람·측정이 `ACTIVE` 로 넣을 때)와
     * `noop()`(검역이 풀릴 때). 리플렉터의 `ADD` 는 `PENDING` 이라 해당 없다.
     *
     * "최하위" 는 **랭킹과 같은 점수식**으로 고른다(`rank`). 앱 버전은 모르므로 `null` 을
     * 넘긴다 — `version_match` 가 모두에게 1.0 이 되어 순위에 영향이 없다.
     *
     * [cap] 을 인자로 받는 이유는 **시험** 때문이다(소켓 `MEMCAP`). 500 을 채워서 시험할
     * 수는 없다. 상수를 바꾸는 게 아니라 **한 번만** 다른 문턱으로 돌리는 것이다.
     *
     * ⚠️ `pinned` 만 남아 여전히 넘치면 **그대로 둔다** — 사람이 고정한 것을 상한으로
     * 내리지 않는 게 원칙이고, 그 상황은 사람이 목록에서 푸는 것이 맞다.
     */
    fun enforceCap(active: Int = ACTIVE_CAP, pending: Int = PENDING_CAP): Int {
        val now = System.currentTimeMillis()
        var moved = 0

        // ── ACTIVE 초과 → PENDING 으로 강등 (삭제 아님, 명세 그대로) ──
        val overActive = dao.activeCount() - active
        if (overActive > 0) {
            val victims = dao.activeUnpinned().sortedBy { rank(it, null, now) }.take(overActive)
            if (victims.isEmpty()) {
                Log.w("a11mem", "ACTIVE 상한 ${overActive}건 초과인데 전부 pinned 라 내릴 게 없다")
            } else {
                moved += dao.demoteToPending(victims.map { it.id })
                Log.i("a11mem", "ACTIVE 상한 $active 초과 → ${victims.map { it.id }} 를 PENDING 으로")
            }
        }

        // ── PENDING 초과 → 삭제 ──
        //  ★ 강등한 것이 여기 더해지므로 **ACTIVE 처리 뒤에** 센다.
        //  삭제인 이유: PENDING 은 주입이 안 되므로 memory_recall 참조가 없어 고아 행이
        //  안 생기고, RETIRED 로 남기면 "틀린 것" 과 "확인 못 받은 것" 이 목록에서 섞인다.
        //  그리고 재관측이 가능하므로 버리는 비용이 거의 0이다(§3 과 같은 논리).
        val overPending = dao.pendingCount() - pending
        if (overPending > 0) {
            val victims = dao.pendingDeletable().sortedBy { rank(it, null, now) }.take(overPending)
            if (victims.isEmpty()) {
                Log.w("a11mem", "PENDING 상한 ${overPending}건 초과인데 지울 수 있는 게 없다")
            } else {
                val n = dao.deleteMemories(victims.map { it.id })
                moved += n
                Log.i("a11mem", "PENDING 상한 $pending 초과 → ${victims.map { it.id }} 삭제")
            }
        }
        return moved
    }

    fun delete(id: Long) = dao.deleteMemory(id)

    /** 기억만 지운다. run·episode(실행 로그)는 남는다. */
    fun deleteAll(): Int = dao.deleteAllMemories()

    fun count(): Int = dao.memoryCount()

    /**
     * 측정 하네스(Unit 4)가 조건 B 를 세울 때 쓰는 입구. JSON 한 줄을 받아 한 건 넣는다.
     *
     * **반드시 save() 를 지난다** — DAO 를 직접 부르면 validate() 를 우회하고, 그러면
     * 러너가 *읽기에 절대 안 걸리는 기억*을 넣고도 성공으로 받는다. 그 상태로 측정하면
     * **조건 B 가 사실상 A 가 되고, 결과는 "기억은 효과가 없다"로 조용히 틀린다.**
     *
     * `source = "bench"` 로 표시해 둔다. 사람이 손으로 넣은 것(`user_ui`)과 섞이면
     * 측정이 끝난 뒤 무엇을 지워야 할지 알 수 없다 — 목록 UI 에서 바로 구분된다.
     * (지금 `source` 로 분기하는 코드는 없다. 순전히 출처 표시다.)
     */
    fun addFromJson(json: org.json.JSONObject): Long = save(
        MemoryEntity(
            kind = json.getString("kind"),
            text = json.getString("text"),
            pkg = json.optString("pkg").ifBlank { null },
            keywords = json.optString("keywords").ifBlank { null },
            scope = json.optString("scope").ifBlank { "pkg" },
            state = json.optString("state").ifBlank { "ACTIVE" },
            sensitivity = json.optString("sensitivity").ifBlank { "normal" },
            // 사람이 넣은 기억은 목록 UI 에서 pinned 가 기본 체크다. 측정 하네스가 그
            // 조건을 재현하려면 여기서도 지정할 수 있어야 한다(기본은 false — 자동 강등
            // 경로를 타는 쪽이 bench 의 기본값이어야 측정이 실제 동작을 본다).
            pinned = json.optBoolean("pinned", false),
            // 랭킹(Unit 7)을 시험하려면 점수가 다른 후보를 여럿 세울 수 있어야 한다.
            // 실행을 여러 번 돌려 점수를 만드는 것으로는 몇 분이 걸린다.
            score = json.optInt("score", HUMAN_SCORE),
            // 버전 태깅(Unit 8)을 시험하려면 '앱이 업데이트된 뒤의 기억' 을 만들 수 있어야
            // 한다. 진짜 앱 업데이트를 기다릴 수는 없다.
            pkgVersion = json.optLong("pkgVersion", 0L).takeIf { it > 0L },
            source = "bench",
            timeAdded = System.currentTimeMillis(),
        )
    )

    /**
     * 저장해도 되는 행인가. 통과면 null, 아니면 사용자에게 보여줄 이유.
     *
     * 여기서 막는 건 전부 **읽기 경로에서 절대 안 걸리는 행**이다. 저장은 되는데 아무 때도
     * 안 나오는 기억이 목록에 쌓이면, 사용자는 "기억이 작동 안 한다"고 결론 내린다.
     * 읽기 조건(activeForApp / readForTask)이 바뀌면 이 함수도 같이 고쳐야 한다.
     */
    fun validate(m: MemoryEntity): String? {
        if (m.text.isBlank()) return "내용이 비어 있습니다."
        if (m.kind !in KINDS) return "알 수 없는 종류입니다: ${m.kind}"
        if (m.scope !in SCOPES) return "알 수 없는 범위입니다: ${m.scope}"
        if (m.kind == "APP_FACT" && m.scope != "system") {
            val pkgs = m.pkg?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (pkgs.isEmpty())
                return "APP_FACT 는 패키지명이 있어야 합니다.\n없으면 어떤 앱에서도 조회되지 않습니다."
            // ★ 목록 안에 섞여 있어도 막는다. `==` 로만 보면 쉼표 목록에 든 것을 놓친다.
            if (OWN_PACKAGE in pkgs)
                return "$OWN_PACKAGE 은 우리 앱 자신이라 주입 대상에서 제외됩니다.\n조작할 앱의 패키지명을 넣으세요."
        }
        if (m.kind == "PITFALL" && m.keywords.isNullOrBlank())
            return "PITFALL 은 키워드가 있어야 합니다.\n없으면 어떤 목표 문장에도 걸리지 않습니다."
        return null
    }

    // ── 읽기 실패 깃발 ───────────────────────────────────────────────────
    //  삼키는 것과 숨기는 것은 다르다. 실행을 멈추지 않는 건 맞지만(아래 guard),
    //  **아무도 모르게 두면 안 된다** — 기억이 안 들어간 실행이 "기억 있음" 측정에 섞이면
    //  Unit 4 는 "기억은 효과가 없다"는 틀린 결론을 낸다.
    //
    //  알아야 할 곳이 둘이고 소비 시점이 달라 깃발도 둘이다:
    //   · 실행 로그 — 사람이 그 자리에서 본다. 실패 직후 한 번 찍고 소비.
    //   · run 행    — Unit 4 가 나중에 본다. 실행이 끝날 때 기록하고 소비.
    @Volatile private var failureForLog: String? = null
    @Volatile private var failureForRun = false

    /** 사람에게 보일 한 줄. 한 번 가져가면 사라진다. */
    fun takeFailureForLog(): String? = failureForLog.also { failureForLog = null }

    /** run 행에 남길 값. 한 번 가져가면 사라지므로 **실행이 끝날 때 한 번만** 부를 것. */
    fun takeFailureForRun(): Boolean = failureForRun.also { failureForRun = false }

    /**
     * 읽기 실패는 삼킨다. 기억이 없어서 못 붙는 것과 DB 가 깨져서 못 붙는 것은 **에이전트
     * 입장에서 같다** — 둘 다 note == null 이고 종전대로 돌면 된다. 실패를 터뜨려서 실행을
     * 멈추게 하는 쪽이 훨씬 나쁘다. 흔적은 logcat 에 남긴다.
     */
    private inline fun guard(body: () -> String?): String? =
        try { body() } catch (e: Exception) {
            Log.w("a11mem", "기억 읽기 실패(무시)", e)
            failureForLog = "읽기 실패 — 이번 실행은 기억 없이 진행합니다 (${e.javaClass.simpleName})"
            failureForRun = true
            null
        }
}
