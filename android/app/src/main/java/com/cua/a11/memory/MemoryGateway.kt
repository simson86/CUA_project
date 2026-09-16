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

    /** 지금 떠 있는 앱에 대한 참고사항. 없으면 null — 그때 요청 본문은 종전과 동일하다. */
    fun readForApp(pkg: String): String? = guard {
        if (pkg == OWN_PACKAGE) return@guard null
        val now = System.currentTimeMillis()
        val hits = dao.activeAppFacts(now)
            .filter { appliesTo(it, pkg) }
            .take(APP_FACT_LINES)
        emit(hits, now)
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
        return if (m.id == 0L) dao.insertMemory(m) else { dao.updateMemory(m); m.id }
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
            source = "bench",
            // bench 는 사람이 쓴 문장을 대신 넣는 경로다 — 측정의 처치(處置)이므로
            // 사람이 넣은 것과 같은 무게로 시작해야 한다(위 HUMAN_SCORE 주석).
            score = HUMAN_SCORE,
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
