package com.cua.a11

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 실행 기록 파일(`run_history.txt`). 앱 UI 실행과 트리거 실행이 **같은 파일·같은 형식**으로 남긴다.
 *
 * 원래 `MainActivity` 의 private 함수였다. 트리거 실행은 액티비티를 안 거쳐서, 그대로 두면
 * 기록이 아예 안 남는다 — 그런데 이 파일은 '어떤 설정이 어떤 결과를 냈는지'의 유일한 평문 증거다.
 * 형식을 두 벌로 복사하면 한쪽만 바뀌는 사고가 나므로 한 곳으로 옮겼다.
 *
 * 동시 쓰기는 고려하지 않는다 — 실행은 `a11service.runTask` 의 가드로 한 번에 하나다.
 */
object RunHistory {
    fun file(ctx: Context) = File(ctx.filesDir, "run_history.txt")

    /** 한 실행 로그를 타임스탬프+목표 헤더와 함께 파일 끝에 붙인다. 백그라운드 스레드에서 부를 것. */
    fun append(ctx: Context, task: String, body: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        file(ctx).appendText("===== $ts  |  $task =====\n$body\n\n")  // appendText=UTF-8
    }
}
