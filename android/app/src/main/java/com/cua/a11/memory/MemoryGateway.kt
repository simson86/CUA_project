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

    /**
     * 읽기 실패는 삼킨다. 기억이 없어서 못 붙는 것과 DB 가 깨져서 못 붙는 것은 **에이전트
     * 입장에서 같다** — 둘 다 note == null 이고 종전대로 돌면 된다. 실패를 터뜨려서 실행을
     * 멈추게 하는 쪽이 훨씬 나쁘다. 흔적은 logcat 에 남긴다.
     */
    private inline fun guard(body: () -> String?): String? =
        try { body() } catch (e: Exception) { Log.w("a11mem", "기억 읽기 실패(무시)", e); null }
}
