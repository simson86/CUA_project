package com.cua.a11

import android.content.Context

/**
 * 저장된 실행 설정을 읽는 곳. **액티비티를 거치지 않는 사람 있는 실행**(접근성 버튼 트리거)이 쓴다.
 *
 * 쓰지 않는 곳과 이유:
 *  - `MainActivity` — 화면 드롭다운 값을 저장하고 그대로 넘긴다. 방금 고른 값이 곧 저장값이라
 *    결과가 같고, 여기서 다시 읽게 하면 '화면에 보이는 값과 도는 값이 다를 수 있는' 틈이 생긴다.
 *  - 소켓 `RUN` — 무인(attended=false) 경로라 runTask 를 거치면 안 된다
 *    (docs/reference/android_run-handsfree-impl-2026-09-15.md §4).
 *
 * 우선순위는 MainActivity 와 같다: 저장값 > BuildConfig(local.properties) > CuClient 기본값.
 */
data class RunConfig(val maxTurns: Int, val model: String, val thinking: String) {
    companion object {
        /** ★ MainActivity 의 SharedPreferences 이름·키와 반드시 같아야 한다. */
        private const val PREFS = "cua"

        fun load(ctx: Context): RunConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // ★ 모델·사고수준은 저장된 문자열을 그대로 넘긴다. 여기서 거르지 말 것 —
            //   조합 검증(3.7·3.8 + minimal → 400)은 runTask 안의 cu.configure() 한 곳에만 둔다.
            //   CuClient.THINKING[...] 처럼 전체 목록 인덱스로 읽으면 모델별 목록(thinkingFor)과 어긋난다.
            return RunConfig(
                maxTurns = p.getInt("max_turns", 20).coerceIn(1, 40),
                model = p.getString("model", null) ?: BuildConfig.GEMINI_MODEL,
                thinking = p.getString("thinking", null) ?: BuildConfig.GEMINI_THINKING,
            )
        }
    }
}
