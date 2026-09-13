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

        // ── 목록 UI 가 쓰는 값 목록 (Unit 3) ───────────────────
        //  RECIPE 는 일부러 뺐다 — 읽는 쪽(Unit 9)이 아직 없어서, 넣을 수 있게 해두면
        //  사용자가 **아무도 안 읽는 행**을 만들게 된다. Unit 9 에서 함께 연다.
        val KINDS = listOf("APP_FACT", "PITFALL")
        val STATES = listOf("ACTIVE", "PENDING", "RETIRED")
        val SENSITIVITIES = listOf("normal", "restricted")
    }

    /** 지금 떠 있는 앱에 대한 참고사항. 없으면 null — 그때 요청 본문은 종전과 동일하다. */
    fun readForApp(pkg: String): String? = guard {
        if (pkg == OWN_PACKAGE) return@guard null
        val now = System.currentTimeMillis()
        val hits = dao.activeForApp(pkg, now, APP_FACT_LINES)
        emit(hits, now)
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
        return hits.joinToString(" ") { it.text }
    }

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
        if (m.kind == "APP_FACT") {
            if (m.pkg.isNullOrBlank())
                return "APP_FACT 는 패키지명이 있어야 합니다.\n없으면 어떤 앱에서도 조회되지 않습니다."
            if (m.pkg == OWN_PACKAGE)
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
