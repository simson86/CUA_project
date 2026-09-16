package com.cua.a11

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.Display
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Base64
import android.content.Intent
import java.net.Socket
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.Toast
import java.util.concurrent.TimeUnit


class a11service : AccessibilityService(), Executor {
    companion object{
        @Volatile
        var instance : a11service? = null
            private set
        const val CHANNEL_ID= "cu_run"
        const val NOTI_ID = 1001
        // 이 크기 미만일 때만 픽셀 판정을 위해 디코딩한다(정상 화면의 디코딩 비용을 아끼는 사전 필터).
        // 실측: 보안 화면 21~37KB, 일반 화면 108KB~2.1MB. 여유 2.6배로 잡았다.
        const val PROBE_PNG_PREFILTER = 100_000
        // 본문이 이 비율 이상 순수 검정이면 '읽을 수 없는 화면'으로 본다. 실측: 보안 99, 정상 0.
        const val BLACK_PCT_THRESHOLD = 95
        // 검은 화면을 만나면 이만큼 뒤 한 번 더 찍어 본다 — 화면 전환 순간의 검은 프레임을 걸러낸다.
        // 정확성이 아니라 카드가 헛뜨는 빈도에만 영향을 준다. 로그를 보며 조정할 값.
        const val BLACK_RECHECK_MS = 400L
        const val HANDOVER_TIMEOUT_MIN = 3L

        // ── 콜백 대기 상한 ──────────────────────────────────────
        //  아래 셋은 전부 '콜백이 오기를' 기다린다. 시간 제한이 없으면 콜백이 한 번
        //  안 오는 것만으로 스레드가 **영구히** 멈춘다. 소켓 서버는 단일 스레드라
        //  (accept 루프에서 runAgent 를 그대로 돌린다) 그때 서버가 통째로 죽고,
        //  포트는 리스닝인데 아무 명령도 안 받는 상태가 된다 — 앱 재시작 전엔 안 풀린다.
        //  실측된 정상값: 캡처 ~0.3초. 아래 값은 넉넉한 상한일 뿐 튜닝 대상이 아니다.
        /** 클라이언트가 명령 한 줄을 다 보낼 때까지의 상한. 보내다 만 연결이 스레드를
         *  영원히 잡지 않게 한다. 정상값은 밀리초 단위다. */
        const val CLIENT_READ_TIMEOUT_MS = 15_000
        const val CAPTURE_TIMEOUT_S = 10L
        const val GESTURE_TIMEOUT_S = 10L
        const val UI_POST_TIMEOUT_S = 5L
        const val HANDOVER_SELF = 0   // 사용자가 직접 처리 → 잠시 기다렸다 다시 찍는다
        const val HANDOVER_SKIP = 1   // 보안 화면 아님 → 그대로 진행, 이 앱에서는 다시 안 물음
        const val HANDOVER_STOP = 2   // 실행 중단
        // [직접 하겠습니다]를 누른 뒤 사용자가 인증할 시간. 짧으면 아직 잠긴 화면을 찍는다.
        const val HANDOVER_SELF_WAIT_MS = 5000L

        // ── 접근성 버튼 트리거 ──────────────────────────────────
        // 트리거 카드가 finish() 한 뒤 첫 캡처까지 기다리는 시간. 짧으면 첫 스크린샷에 우리 카드가
        // 찍혀 모델이 그걸 조작하려 든다. 시작값일 뿐 실측으로 정할 것 — run_history 의 [턴 1]
        // 액션이 카드 자리를 누르면 올린다. 속도 영향: 트리거 실행마다 한 번(턴당 비용 아님).
        const val TRIGGER_SETTLE_MS = 600L
        // runTask 가드에 걸렸을 때 돌려주는 문자열. "오류"로 시작해야 알림·화면이 실패로 읽는다.
        const val BUSY_MESSAGE = "오류: 이미 실행 중입니다. 끝난 뒤 다시 시도하세요."

        // ── 자격증명 입력 인계 ──────────────────────────────────
        //  인계 카드(위)와 목적이 다르다. 저쪽은 '모델이 화면을 못 봄', 이쪽은 '모델이 값을 모름'.
        //  3분이 아니라 5분인 이유: 문자·메일로 오는 인증 코드를 기다려야 할 수 있다.
        const val CRED_TIMEOUT_MIN = 5L
        const val CRED_DONE = 0   // 사용자가 직접 입력을 마침 → 이어서 진행
        const val CRED_STOP = 1   // 사용자가 중단을 선택
        // 모델이 잘못 판단해 카드를 띄웠을 때의 탈출구. **모델 경로에서만** 제공한다 —
        // 비밀번호 게이트에서 이걸 열어주면 '모델의 추측값을 비밀번호 칸에 넣어라'가 된다.
        const val CRED_SKIP = 2   // "입력할 것 없다, 그냥 계속해"
    }

    override fun onUnbind(intent: Intent?): Boolean{
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy(){
        hideOverlay()
        instance = null
        super.onDestroy()
    }
    @Volatile private var cancelled = false

    /**
     * 이 실행을 지켜보는 사람이 있나. 앱 UI 실행은 true, 소켓 `RUN` 은 false.
     *
     * 사람에게 넘기는 지점(인계 카드·자격증명 카드)은 **답할 사람이 있을 때만** 뜻이 있다.
     * 소켓 경로는 정의상 무인이라(개발·측정용, `tools/bench_*.py` 의 짝) 카드를 띄우면
     * 타임아웃까지 그냥 버린다 — 실측 2026-09-13: 소켓 RUN 203초 중 **187초**가 인계 카드가
     * 없는 사람을 기다린 시간이었다. 무인이면 기다리지 말고 즉시 실패한다.
     *
     * `cancelled`·`skipBlackPkgs` 와 같은 성질의 실행 단위 상태다 — 동시에 두 실행이
     * 돌면 서로를 덮는다. 지금은 실질적으로 직렬이라 그대로 두지만, 병렬 실행을 허용하게
     * 되면 셋 다 같이 옮겨야 한다.
     */
    @Volatile private var attended = true

    /**
     * 지금 에이전트가 돌고 있나. 앱 UI·소켓 양쪽이 세운다.
     *
     * 연결마다 스레드를 띄우게 되면서 **소켓 RUN 이 동시에 둘 들어올 수 있다.** 그러면
     * 두 에이전트가 같은 화면을 서로 조작한다 — 화면도 기록도 엉킨다. 앱 UI 와 소켓이
     * 겹치는 것도 마찬가지라 깃발 하나를 둘이 공유한다.
     * (앱 UI 는 실행 중 버튼을 잠그지만 그건 UI 한 겹일 뿐이다.)
     */
    @Volatile private var agentBusy = false
    // 카드가 떠 있는 동안 에이전트 스레드는 latch 앞에서 자고 있어 cancel 깃발을 확인할 코드가
    // 돌지 않는다. 중단 버튼이 그 대기까지 깨워야 '눌러도 반응 없음'이 안 생긴다.
    @Volatile private var pendingLatch: CountDownLatch? = null
    // 사용자가 "그냥 계속"을 고른 앱. 실행마다 초기화한다(runTask).
    private val skipBlackPkgs = java.util.Collections.synchronizedSet(HashSet<String>())
    fun requestCancel() { cancelled = true; pendingLatch?.countDown() }

    /**
     * 지금 실행 중인가(`runTask` 경로). 원래 없었다 — MainActivity 가 실행 버튼을 꺼서 막고 있었을 뿐이라,
     * 액티비티를 안 거치는 접근성 버튼 트리거가 생기면서 겹치는 실행을 막을 곳이 필요해졌다.
     *
     * ★ AtomicBoolean + compareAndSet 인 이유: 트리거 카드가 이 값을 확인한 뒤 3초 카운트다운이
     *   도는 사이에 앱에서 '실행'을 누를 수 있다. '확인'과 '시작'이 따로면 둘 다 통과한다.
     *
     * ⚠️ 소켓 `RUN` 은 runTask 를 안 거쳐 **이 가드 밖이다.** 무인 경로라 runTask 로 합치면 안 되고
     *   (attended 가 true 로 바뀐다), 개발·측정용이라 그대로 둔다. `attended`·`cancelled` 가 실행 단위가
     *   아니라 서비스 필드라는 한계(위 주석)와 같은 뿌리다.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()
    // ★ log 는 반드시 맨 뒤 — MainActivity 가 trailing lambda 로 넘긴다.
    //   중간에 파라미터를 끼우면 `svc.runTask(task, maxTurns, model, thinking) { … }` 문법이 깨진다.
    // ★ 새 파라미터엔 기본값을 준다 — 소켓 RUN 경로와 기존 호출부가 안 깨지게.
    fun runTask(task: String, maxTurns: Int = 20,
                model: String = CuClient.DEFAULT_MODEL,
                thinking: String = CuClient.DEFAULT_THINKING,
                log: (String) -> Unit = {}): String {
        // ★ 겹치는 실행을 막는다. 확인과 시작을 한 번에(compareAndSet) — running 주석 참조.
        if (!running.compareAndSet(false, true)) return BUSY_MESSAGE
        try {
            return runTaskBody(task, maxTurns, model, thinking, log)
        } finally {
            running.set(false)   // ★ finally 가 아니면 예외 한 번에 영구히 '실행 중'으로 잠긴다
        }
    }

    /** runTask 의 실제 몸통. 가드(running) 없이 부르지 말 것 — 겹치는 실행이 서로의 상태를 덮는다. */
    private fun runTaskBody(task: String, maxTurns: Int, model: String, thinking: String,
                            log: (String) -> Unit): String {
        // 지난 실행에서 중단 버튼이 눌렸으면 cancelled 가 true 로 남아 있다.
        // 초기화하지 않으면 새 요청이 첫 턴에서 곧바로 중단된다.
        // ★ true 로 새면 이후 **모든** 실행이 영구히 막힌다. 반드시 finally 로 푼다.
        if (agentBusy) return "이미 실행 중입니다. 끝난 뒤 다시 시도하세요."
        agentBusy = true
        cancelled = false
        skipBlackPkgs.clear()      // "그냥 계속" 판단은 이번 실행에만 유효하다
        attended = true            // 앱에서 눌렀으니 사람이 보고 있다
        // 이번 판에 쓸 설정을 갈아끼운다. 따로 대입하지 말 것 — 사고수준이 이 모델에서
        // 유효한지는 '조합'을 봐야 알 수 있고(3.7·3.8 은 minimal 을 400 으로 거절한다),
        // 그 판단은 configure 안에 한 번만 둔다.
        cu.configure(model, thinking)
        // 이 줄을 빼지 말 것 — 설정을 바꿀 수 있게 만든 순간, '어떤 설정이 어떤 결과를 냈는지'가
        // 기록에 안 남는 게 가장 큰 손해다. run_history.txt 에 남는 유일한 증거다.
        log("[설정] ${cu.settingsLine()} maxTurns=$maxTurns")
        showOverlay(task)
        val r = try {
            runAgent(this, cu, task,maxTurns,
                log = { line -> log(line);postOverlay(line) },
                cancel = {cancelled},
                trace = runTrace)
        } catch (e: Exception) {
            "오류: ${e.message}"     // screenshot/네트워크 예외도 알림에 잡히게
        } finally {
            agentBusy = false
        }
        postOverlay(r)
        notifyDone(task, r)
        ui.postDelayed({ hideOverlay() }, 4000)   // 결과 4초 보여주고 닫음
        return r
    }

    // ── 접근성 버튼 트리거 — 앱을 안 열고 작업 맡기기 ─────────────────────────
    //  흐름: 버튼 → VoiceTriggerActivity(듣기·카운트다운) → launchFromTrigger → runTaskWithSavedConfig → runTask
    //  근거·버린 대안(Google Assistant/Gemini): docs/reference/android_run-handsfree-impl-2026-09-15.md

    /**
     * 시스템이 그리는 접근성 버튼(제스처 내비: 떠 있는 동그란 버튼 / 3버튼 내비: 내비바 안).
     * config 의 `flagRequestAccessibilityButton` 과 짝이다 — 한쪽만 있으면 안 온다.
     * 우리 오버레이가 아니라서 에이전트의 탭을 가로채는 문제와 무관하다.
     *
     * ★ AccessibilityService 에 `onAccessibilityButtonClicked()` 같은 오버라이드는 **없다.**
     *   설계 문서가 그렇게 적었다가 컴파일에서 'overrides nothing' 으로 걸렸다.
     *   `AccessibilityButtonController` 에 콜백을 등록하는 방식이다 —
     *   onServiceConnected 에서 등록, onUnbind 에서 해제.
     */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = onAccessibilityButton()
        override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
            // 버튼이 사라지는 경우(설정에서 해제, 다른 서비스가 가져감 등). "안 눌린다" 추적용 로그만.
            Log.d("A11y", "accessibility button available=$available")
        }
    }

    private fun onAccessibilityButton() {
        Log.d("A11y", "accessibility button clicked (running=${running.get()})")
        // ★ 마이크는 여기서 못 연다 — 접근성 서비스는 포그라운드가 아니다(Android 9+).
        //   그래서 액티비티를 띄워 포그라운드 지위를 얻는다. 서비스에는 태스크가 없으니 NEW_TASK 필수.
        //   (백그라운드 액티비티 실행 제한은 시스템이 바인딩한 서비스라 면제된다.)
        startActivity(Intent(this, VoiceTriggerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * 트리거 경로의 실행 진입점. VoiceTriggerActivity 가 finish() 한 직후에 부른다.
     * 카드가 화면에서 실제로 사라질 시간을 준 뒤 시작한다 — finish() 는 종료를 예약할 뿐이라
     * 곧바로 찍으면 첫 스크린샷에 카드가 남는다(CLAUDE.md 「오버레이 제거는 비동기」와 같은 함정).
     */
    fun launchFromTrigger(task: String) {
        ui.postDelayed({
            thread { runTaskWithSavedConfig(task) }   // 네트워크·대기가 있으므로 메인 스레드 금지
        }, TRIGGER_SETTLE_MS)
    }

    /**
     * 앱에서 마지막으로 고른 설정(모델·사고수준·최대 턴)으로 도는 실행.
     * ★ 이름을 runTask 로 오버로드하지 말 것 — 기존 runTask 는 뒤 파라미터가 전부 기본값이라
     *   `runTask(task) { … }` 가 두 후보에 다 맞아 '모호한 호출'로 컴파일이 깨진다.
     * ★ runTask 를 거치므로 attended = true 로 돈다. 사람이 버튼을 누른 실행이니 맞다 —
     *   인계·자격증명 카드가 뜨는 게 정상이다. 소켓처럼 runAgent 를 직접 부르면 무인 취급된다.
     */
    fun runTaskWithSavedConfig(task: String): String {
        val c = RunConfig.load(this)
        val buf = StringBuilder("[경로] 접근성 버튼\n")   // run_history 에서 앱 UI 실행과 구분
        val r = runTask(task, c.maxTurns, c.model, c.thinking) { line -> buf.append(line).append('\n') }
        if (r == BUSY_MESSAGE) {
            // 카드가 확인한 뒤 카운트다운 사이에 다른 실행이 시작된 경우. runTask 는 알림 없이
            // 돌아오므로, 여기서 알리지 않으면 사용자는 아무 일도 안 일어난 줄 안다.
            ui.post { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            return r
        }
        buf.append(r).append('\n')
        // 앱 UI 경로는 MainActivity 가 저장한다. 이 경로엔 액티비티가 없으니 여기서 남긴다.
        // 기록 실패가 실행 결과를 뒤집으면 안 되므로 삼키되 로그는 남긴다.
        try { RunHistory.append(this, task, buf.toString()) }
        catch (e: Exception) { Log.w("A11y", "run_history 저장 실패: ${e.message}") }
        return r
    }

    private fun notifyDone(task: String, r: String) {
        val title = when {
            r.startsWith("중단") -> " ■ 중단됨"
            r.startsWith("STOP") -> "⚠ 최대 턴 도달"
            r.startsWith("오류")  -> "❌ 실행 오류"
            else                  -> "✅ 실행 완료"
        }
        // 알림 탭 → 앱으로 복귀
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 기본 아이콘(커스텀 불필요)
            .setContentTitle(title)
            .setContentText(r)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$task\n$r"))  // 펼치면 전체
            .setContentIntent(pi)
            .setAutoCancel(true)                              // 탭하면 사라짐
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // API33+ 권한 없으면 조용히 skip(예외 방지). 권한은 MainActivity가 요청.
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(NOTI_ID, n)
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "CU 실행 알림",
            NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // ── 실행 중 떠있는 오버레이 창(다른 앱 위) ──
    private fun showOverlay(task: String) {
        if (!Settings.canDrawOverlays(this)) return   // 권한 없으면 skip(알림은 뜸)
        ui.post {
            overlayView?.let { it.text = "▶ $task"; return@post }   // 이미 있으면 재사용
            val tv = TextView(this).apply {
                text = "▶ $task"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())   // 반투명 검정
                setPadding(24, 24, 24, 24)
                textSize = 12f
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // 다른 앱 위
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // 입력 안 뺏음
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // 터치를 아예 안 받는다. 이게 없으면 화면 최상단을 덮은 이 띠가
                    // 그 영역의 탭을 먹어버려 dispatchGesture 가 목표에 닿지 못한다.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    // ★ 실행 중에는 화면이 꺼지지 않게 한다. 에이전트가 액션 없이 기다리는
                    //   구간(wait, 긴 API 왕복)이 화면 꺼짐 시간을 넘기면 캡처가 검게 나오고,
                    //   screenshot() 이 그걸 '화면이 꺼졌다'로 판정해 실행을 끝낸다.
                    //   이 띠는 실행 중에만 떠 있으므로 수명이 정확히 실행과 같다.
                    //   (소켓 경로는 이 띠를 안 띄우므로 해당 없음 — 거긴 애초에 무인이라
                    //    화면이 꺼져 있으면 즉시 실패하는 게 맞다.)
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
            getSystemService(WindowManager::class.java).addView(tv, lp)
            overlayView = tv
        }
    }

    private fun postOverlay(line: String) {
        ui.post {
            overlayView?.text = line   // 지금 턴 한 줄만 표시
        }
    }

    private fun hideOverlay() {
        ui.post {
            overlayView?.let { getSystemService(WindowManager::class.java).removeView(it) }
            overlayView = null
        }
    }

    // 스크린샷 직전 숨김→캡처→복원 (오버레이가 모델 시야 가리지 않게)
    private fun hideForShot() {
        val tv = overlayView ?: return
        val latch = CountDownLatch(1)
        ui.post { tv.visibility = View.GONE; latch.countDown() }   // 메인스레드서 숨김
        // 상한을 두는 이유는 아래 CAPTURE_TIMEOUT_S 주석 참조. 여기서는 던지지 않고 넘어간다 —
        // 최악의 결과가 '오버레이가 찍힌 스크린샷' 뿐이라 실행을 죽일 이유가 없다.
        if (!latch.await(UI_POST_TIMEOUT_S, TimeUnit.SECONDS))
            Log.w("A11y", "hideForShot: 메인 스레드 응답 없음 — 오버레이가 찍힐 수 있다")
        Thread.sleep(32)                                          // 컴포지터가 없는 프레임 그릴 시간
    }
    private fun showAfterShot() {
        val tv = overlayView ?: return
        ui.post { tv.visibility = View.VISIBLE }
    }

    // 모델·사고수준은 앱 드롭다운에서 매 실행 고른다(문서: android_run-model-thinking-2026-08-11.md).
    // 그래서 여기 BuildConfig 값은 '씨앗'일 뿐이다 — 드롭다운의 첫 기본 선택이자, 소켓 RUN 처럼
    // runTask 를 안 거치는 경로가 쓸 초기값. 비었거나 오타면 CuClient 가 기본값으로 떨군다.
    private val cu by lazy {
        CuClient(
            BuildConfig.GEMINI_API_KEY,
            BuildConfig.GEMINI_MODEL,
            BuildConfig.GEMINI_THINKING,
        )
    }
    private val ui = Handler(Looper.getMainLooper())   // 메인스레드 post용
    private var overlayView: TextView? = null
    private var lastW = 0
    private var lastH = 0
    @Volatile private var lastEventTs = 0L      // [Phase 1 · 임시 계측] 마지막 화면 변경 시각
    @Volatile private var lastAction = "-"      // [Phase 1 · 임시 계측] 직전에 실행한 액션 이름

    // ── 참고사항 주입 (Unit 2) ──────────────────────────────────────────────
    //  하드코딩 맵이던 것이 memory 테이블 조회로 바뀌었다. 넣는 규칙 자체는 그대로다:
    //   - 로그에서 모델이 '실제로 반복해서 막히는 것'을 확인한 뒤에만 추가.
    //   - 한 앱당 한두 줄. 왜 넣었는지는 source_run_id 가 가리킨다.
    //   - 관련 없는 작업에서 읽어도 해가 없는 문장이면 기억이 아니라 system_prompt 감이다.
    //  예산·민감도 필터는 전부 MemoryGateway 안에 있다 — 여기서 다시 걸지 말 것.

    override fun appNote(): String? =
        rootInActiveWindow?.packageName?.toString()?.let { gateway.readForApp(it) }

    override fun taskNote(task: String): String? = gateway.readForTask(task)

    override fun noteFailure(): String? = gateway.takeFailureForLog()

    // ── 구조화 로깅(0단계) ────────────────────────────────────────────────
    //  longVersionCode 는 기억 무효화의 근거다(설계 §7 4번). 지금은 기록만 하고 쓰지 않지만,
    //  안 적어두면 나중에 소급이 안 되므로 0단계부터 남긴다.
    override fun foregroundApp(): com.cua.a11.Foreground? =
        rootInActiveWindow?.packageName?.toString()?.let { pkg ->
            com.cua.a11.Foreground(pkg, pkgVersionOf(pkg))
        }

    private fun pkgVersionOf(pkg: String): Long? = try {
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (e: Exception) { null }   // 앱이 지워졌거나 조회 불가 — 로깅이 실행을 막으면 안 된다

    /** DB 는 처음 쓰일 때 만들어진다. trace 와 gateway 가 같은 인스턴스를 공유한다. */
    private val memoryDao by lazy { com.cua.a11.memory.MemoryDb.get(this).dao() }
    private val gateway by lazy { com.cua.a11.memory.MemoryGateway(memoryDao) }
    private val runTrace: com.cua.a11.RunTrace by lazy {
        com.cua.a11.memory.RoomRunTrace(memoryDao, gateway, ::queueReflection)
    }

    // ── 리플렉터 (Unit 5b) ───────────────────────────────────────────────
    /**
     * **단일 스레드 큐**다. 별도 깃발(`reflectorBusy` 같은) 대신 이걸 쓰는 이유:
     *  · 스레드가 하나라 리플렉터가 **겹칠 수가 없다** — 앞엣것이 끝나야 뒤엣것이 시작된다.
     *  · 깃발이 아니라 구조라서 **샐 수가 없다.** `agentBusy` 가 true 로 새면 이후 모든
     *    실행이 영구히 막히는데(위 주석), 큐에는 그런 실패 모드가 없다.
     *  · 배치를 빠르게 돌려 리플렉터가 밀리면 **쌓일 뿐** 아무것도 안 깨진다.
     */
    private val reflectorPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val reflector by lazy { com.cua.a11.memory.Reflector(cu, memoryDao, gateway) }

    /**
     * 실행이 끝났다 — 되새김을 **큐에 넣기만 하고 즉시 반환한다.**
     *
     * ★ 여기서 그냥 돌리면 안 된다. 이 함수는 `runAgent` 의 `finally` 안에서 불리므로,
     *   리플렉터가 끝날 때까지 ⑴ 소켓 응답이 안 나가고(측정 벽시계 오염) ⑵ `agentBusy` 가
     *   잡혀 있어 다음 `RUN` 이 "이미 실행 중입니다" 로 거절된다. 하네스는 응답을 받자마자
     *   다음 `RUN` 을 보내므로 **가끔** 깨진다 — 제일 찾기 어려운 종류다.
     *
     * 리플렉터는 화면을 안 만지므로 다음 실행과 겹쳐도 충돌하지 않는다(`agentBusy` 가
     * 지키는 것은 화면이다). 쓰는 것도 `PENDING` 뿐이라 다음 실행의 주입을 바꾸지 않는다.
     * ⚠️ 5c 에서 reconciliation 이 `ACTIVE` 로 승격시키기 시작하면 그 전제가 깨진다 —
     *    실행 도중에 주입 내용이 바뀔 수 있다. 그때 이 주석을 다시 읽을 것.
     */
    private fun queueReflection(runId: String) {
        if (!com.cua.a11.memory.MemoryGateway.reflectorEnabled(this)) return
        // 실행 로그(`log` 람다)는 실행마다 넘겨받는 것이라 여기서는 이미 끝났다. 되새김의
        // 흔적은 logcat(`a11mem`)과 **기억 목록**에 남는다 — 후보 자체가 `PENDING` 행으로
        // 뜨고 출처가 `reflector` 로 찍히므로, 사람이 보는 면은 그쪽이 맞다.
        reflectorPool.submit { reflector.reflect(runId) }
    }

    private fun captureOnce(): ByteArray {
        hideForShot()                          // 오버레이 숨기고 프레임 대기
        val png = capturePngBlocking()
        showAfterShot()                        // 다시 보이기
        val (w, h) = pngSize(png); lastW = w; lastH = h
        return png
    }

    /** 읽을 수 없는 화면(캡처가 검게 나오는 화면)이면 사용자에게 넘기고, 그 결과로 얻은 화면을 준다.
     *
     *  FLAG_SECURE 화면은 캡처가 '실패'하지 않고 내용만 검게 온다(실측). 모델에게는 검은 이미지가
     *  가므로 눈이 먼 채 헤매다 턴을 태우거나 서버의 이상행동 차단에 걸린다(실측: 카카오톡 잠금에서
     *  8턴 헤매다 400). 그렇다고 노드 트리로 키패드를 눌러 뚫는 건 앱이 의도적으로 가린 화면을
     *  우회하는 것이라 하지 않는다 — 사람에게 넘기는 게 맞다.
     *
     *  검은 화면이 '보안 화면'인지 '그냥 검은 콘텐츠'인지는 코드로 구분할 수 없다(유튜브 검은 영상이
     *  보안 화면보다 더 검게 나온다). 그래서 구분을 포기하고 선택지를 주어 사용자가 정하게 한다.
     */
    override fun screenshot(): ByteArray {
        var png = captureOnce()
        if (probeSecureScreen(png) < BLACK_PCT_THRESHOLD) return png

        // ★ 꺼진 화면과 보안 화면은 픽셀로 똑같이 검다 — 구분하지 않으면 화면이 꺼졌을 뿐인데
        //   '보안 화면'이라며 카드를 내민다. 사람이 앞에 있으면 탭 한 번이라 무해하지만,
        //   무인 실행에서는 3분을 통째로 버린다(실측 2026-09-13: 소켓 RUN 203초 중 187초).
        //
        //   화면이 꺼져 있으면 에이전트는 아무것도 할 수 없다 — 깨워 봐야 잠금화면이다.
        //   그러니 기다리지 말고 여기서 끝낸다. 조용히 반쯤 망가진 실행을 만드는 것보다
        //   시끄럽게 실패하고 사람이 화면을 켜고 다시 시작하는 편이 낫다.
        //   (앱 UI 실행 중에는 진행 표시 띠가 FLAG_KEEP_SCREEN_ON 을 들고 있어 여기 안 온다.)
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            throw IllegalStateException("화면이 꺼져 있습니다. 화면을 켜고 다시 실행하세요.")
        }

        // 사용자가 이 앱에서 이미 "그냥 계속"을 골랐으면 다시 묻지 않는다.
        val pkg = rootInActiveWindow?.packageName?.toString()
        if (pkg != null && pkg in skipBlackPkgs) return png

        // 화면 전환 순간에도 검은 프레임이 잡힌다. 잠깐 뒤 다시 찍어보면 그건 사라진다.
        Thread.sleep(BLACK_RECHECK_MS)
        png = captureOnce()
        if (probeSecureScreen(png) < BLACK_PCT_THRESHOLD) return png

        // 여기까지 왔으면 화면은 켜져 있는데 내용이 검다 = 진짜 보안 화면일 가능성이 높다.
        // 그건 사람만 풀 수 있는데, 무인 실행에는 그 사람이 없다.
        if (!attended) {
            throw IllegalStateException(
                "화면을 읽을 수 없습니다(무인 실행). 보안 화면으로 보입니다 — pkg=$pkg")
        }

        return handoverWait(png, pkg)
    }


    /** 카드를 띄워 사용자 선택을 받는다. [직접 하겠습니다]면 인증할 시간을 준 뒤 다시 찍는다.
     *
     *  '화면이 다시 읽히면 자동 재개'는 두 번 시도했다가 접었다 — 우리가 화면에 그린 것(알림 배너,
     *  카드)이 판정을 오염시켜 곧바로 재개돼 버렸다. 판정을 아예 쓰지 않고 사용자의 명시적 선택만
     *  받는 편이 단순하고 확실하다.
     *
     *  5초 뒤에도 여전히 검으면 카드를 다시 띄운다 — 인증이 덜 끝났거나(더 누르면 됨),
     *  다음 화면도 보안 화면이거나(중단하면 됨) 둘 중 하나이고, 어느 쪽이든 사용자가 정할 일이다.
     */
    private fun handoverWait(first: ByteArray, pkg: String?): ByteArray {
        var png = first
        while (true) {
            when (showHandoverCard()) {
                HANDOVER_SELF -> {
                    Thread.sleep(HANDOVER_SELF_WAIT_MS)      // 사용자가 인증할 시간
                    png = captureOnce()
                    if (probeSecureScreen(png) < BLACK_PCT_THRESHOLD) return png
                    // 아직 검다 → 카드를 다시 띄운다(턴을 낭비하지 않고 그 자리에서 재시도)
                }
                HANDOVER_SKIP -> { pkg?.let { skipBlackPkgs.add(it) }; return png }
                else -> {
                    // screenshot() 은 이미지를 반환하는 함수라 '여기서 끝내라'를 직접 말할 수단이 없다.
                    // 취소 플래그만 세우면 runAgent 가 다음 턴 시작에서 확인해 중단으로 끝낸다.
                    requestCancel(); return png
                }
            }
            if (cancelled) return png
        }
    }

    /** 인계 카드를 띄우고 선택을 기다린다(3분 무응답 = 중단).
     *
     *  확인 카드(confirm)와 터치 정책이 정반대다 — 저쪽은 뒤 앱 오터치를 막으려 전체 모달이지만,
     *  이 카드는 사용자가 뒤 앱의 키패드를 눌러야 하므로 카드 밖 터치를 통과시켜야 한다.
     *  키패드는 보통 화면 아래쪽에 있으므로 카드는 위쪽에 붙인다.
     */
    private fun showHandoverCard(): Int {
        if (!Settings.canDrawOverlays(this)) return HANDOVER_STOP   // 물어볼 수 없으면 중단
        val latch = CountDownLatch(1)
        var choice = HANDOVER_STOP
        var root: View? = null
        pendingLatch = latch                     // 중단 버튼이 이 대기를 깨울 수 있게
        ui.post {
            val wm = getSystemService(WindowManager::class.java)
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat(); setColor(0xFF1E1F24.toInt())
                }
                setPadding(dp(20), dp(18), dp(20), dp(16))
                elevation = dp(16).toFloat()
            }
            val title = TextView(this).apply {
                text = "🔒 화면을 읽을 수 없습니다"
                setTextColor(0xFFF2F3F5.toInt()); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val body = TextView(this).apply {
                text = "보안 화면이라면 [제가 직접 하겠습니다]를 누르고 5초 안에 인증해 주세요."
                setTextColor(0xFFAAB0BA.toInt()); textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, dp(16))
            }
            fun pill(label: String, textColor: Int, bg: Int, border: Boolean) = TextView(this).apply {
                text = label; setTextColor(textColor); textSize = 13f
                gravity = Gravity.CENTER; isClickable = true
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(bg)
                    if (border) setStroke(dp(1), 0xFF3A3B42.toInt())
                }
            }
            val self = pill("제가 직접 하겠습니다", 0xFFFFFFFF.toInt(), 0xFF3B82F6.toInt(), false)
            val skip = pill("그냥 계속", 0xFFC9CDD4.toInt(), 0x00000000, true)
            val stop = pill("중단", 0xFFC9CDD4.toInt(), 0x00000000, true)

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(self, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.8f))
            row.addView(skip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) })
            row.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
                .apply { marginStart = dp(8) })

            card.addView(title); card.addView(body); card.addView(row)

            fun close(res: Int) {
                choice = res
                // removeViewImmediate: 카드가 남아 있는 동안 재캡처하면 스크린샷에 카드가 찍힌다.
                root?.let { wm.removeViewImmediate(it) }
                root = null; pendingLatch = null; latch.countDown()
            }
            self.setOnClickListener { close(HANDOVER_SELF) }
            skip.setOnClickListener { close(HANDOVER_SKIP) }
            stop.setOnClickListener { close(HANDOVER_STOP) }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // 뒤 앱의 입력을 뺏지 않음
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // 카드 밖 터치는 뒤 앱으로
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
            lp.y = dp(24)
            root = card
            wm.addView(card, lp)
        }
        val answered = latch.await(HANDOVER_TIMEOUT_MIN, TimeUnit.MINUTES)
        pendingLatch = null
        if (!answered) {
            ui.post { root?.let { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
            return HANDOVER_STOP
        }
        Thread.sleep(150)   // 창이 실제로 화면에서 사라질 시간. 이 뒤라야 재캡처가 깨끗하다.
        return choice
    }

    /** 사용자에게 입력을 넘기고, 그 결과를 모델에게 보고할 status 로 돌려준다.
     *
     *  ★ 절대 `{"status":"ok"}` 로 두지 말 것 — `type` 을 실행하지 않았는데 ok 를 주면 모델은
     *  자기가 친 값이 들어갔다고 믿고 다음 단계로 넘어간다. press_key 에서 똑같이 당해
     *  4턴을 날린 적이 있다(1620808). 무슨 일이 있었는지 말로 알려주고, 실행 후 화면은
     *  runAgent 가 다시 찍어 함께 보내므로 모델이 눈으로 확인한다.
     *
     *  값 자체는 우리 손을 거치지 않는다 — 사용자가 뒤 앱의 진짜 키보드로 직접 친다.
     *  그래서 run_history.txt 에도 남지 않는다.
     */
    private fun credentialHandover(reason: String, allowSkip: Boolean = false): JSONObject {
        // 무인 실행에는 값을 쳐 줄 사람이 없다. 5분(CRED_TIMEOUT_MIN)을 기다려 봐야
        // 결국 CRED_STOP 이므로, 기다리지 말고 지금 끝낸다.
        if (!attended) {
            requestCancel()
            throw IllegalStateException("사용자 입력이 필요한데 무인 실행입니다: $reason")
        }
        when (showCredentialCard(reason, allowSkip)) {
            CRED_STOP -> {
                // 확인 카드의 '거부'와 같은 취급 — 여기서 끝낸다. dispatch 는 '루프를 끝내라'를
                // 직접 말할 수단이 없으므로 취소 깃발을 세우고, runAgent 가 다음 턴에서 확인한다.
                requestCancel()
                throw IllegalStateException(
                    "The device owner declined to enter the value. Stop and report that the task " +
                    "cannot continue without their input.")
            }
            // 모델이 헛짚었다는 뜻이다. 실행을 끝내지 않고 '네 판단이 틀렸으니 그냥 진행하라'고
            // 알린다. 반복 호출은 이 문장으로만 막는다 — 면제 목록을 두면 뒤늦게 진짜로 필요해진
            // 입력까지 조용히 통과시키게 된다.
            CRED_SKIP -> return JSONObject()
                .put("status", "not_needed")
                .put("message",
                    "The device owner says nothing needs to be entered here — you were not " +
                    "actually blocked. Re-read the screenshot and continue with the task " +
                    "without asking for input again.")
        }
        return JSONObject()
            .put("status", "user_entered")
            .put("message",
                "The device owner typed the value themselves; you never see it. " +
                "Do not type into this field. Read the screenshot and continue from there.")
    }

    /** 자격증명 입력 카드. 인계 카드와 터치 정책이 같다 — 사용자가 **뒤 앱의 키보드**를 써야 한다.
     *
     *  FLAG_NOT_FOCUSABLE 이 특히 중요하다: 이 창이 포커스를 가져가면 뒤 앱의 입력칸이 포커스를
     *  잃어 키보드가 내려간다. 그러면 정작 입력을 못 한다.
     *
     *  [필요 없어요]는 **모델 경로에서만** 뜬다(allowSkip). 모델이 헛짚었을 때의 탈출구다.
     *  비밀번호 게이트에서는 없어야 한다 — 거기서 '계속'은 모델이 지어낸 값을 비밀번호 칸에
     *  넣으라는 뜻이 되기 때문이다. 검은 화면 카드의 [그냥 계속]과는 성격이 다르다.
     */
    private fun showCredentialCard(reason: String, allowSkip: Boolean): Int {
        if (!Settings.canDrawOverlays(this)) return CRED_STOP   // 물어볼 수 없으면 진행하지 않는다
        val latch = CountDownLatch(1)
        var choice = CRED_STOP
        var root: View? = null
        pendingLatch = latch                     // 중단 버튼이 이 대기를 깨울 수 있게
        ui.post {
            val wm = getSystemService(WindowManager::class.java)
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat(); setColor(0xFF1E1F24.toInt())
                }
                setPadding(dp(20), dp(18), dp(20), dp(16))
                elevation = dp(16).toFloat()
            }
            val title = TextView(this).apply {
                text = "🔐 직접 입력해 주세요"
                setTextColor(0xFFF2F3F5.toInt()); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            // 접기/펼치기 — 카드가 로그인 폼의 아이디 입력칸을 가리는 경우가 있다(실사용 발견).
            // 창 높이가 WRAP_CONTENT 라 안쪽 뷰를 GONE 하면 창 자체가 함께 줄어든다.
            val toggle = TextView(this).apply {
                text = "▴"
                setTextColor(0xFFAAB0BA.toInt()); textSize = 18f
                gravity = Gravity.CENTER; isClickable = true
                setPadding(dp(14), dp(4), dp(6), dp(4))
            }
            // 접힌 상태에도 [입력했어요]를 남긴다. 입력을 마치고 '펼치기 → 누르기'로 두 번
            // 만지게 하면, 정작 가려서 접은 사용자에게 다시 번거로움을 주는 셈이다.
            val doneMini = TextView(this).apply {
                text = "입력했어요"
                setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
                gravity = Gravity.CENTER; isClickable = true; visibility = View.GONE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(0xFF3B82F6.toInt())
                }
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(doneMini, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            header.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            // 모델이 준 reason 을 그대로 쓴다. 화면 언어에 맞춰 한국어로 오고, 무엇을 왜 입력해야
            // 하는지가 상황마다 다르므로 우리가 문구를 지어내는 것보다 정확하다.
            val body = TextView(this).apply {
                text = "$reason\n\n입력을 마친 뒤 [입력했어요]를 눌러 주세요."
                setTextColor(0xFFAAB0BA.toInt()); textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(6), 0, dp(16))
            }
            fun pill(label: String, textColor: Int, bg: Int, border: Boolean) = TextView(this).apply {
                text = label; setTextColor(textColor); textSize = 13f
                gravity = Gravity.CENTER; isClickable = true
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(bg)
                    if (border) setStroke(dp(1), 0xFF3A3B42.toInt())
                }
            }
            val done = pill("입력했어요", 0xFFFFFFFF.toInt(), 0xFF3B82F6.toInt(), false)
            val skip = if (allowSkip) pill("필요 없어요", 0xFFC9CDD4.toInt(), 0x00000000, true) else null
            val stop = pill("중단", 0xFFC9CDD4.toInt(), 0x00000000, true)

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(done, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
                if (allowSkip) 1.8f else 2f))
            skip?.let {
                row.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                    .apply { marginStart = dp(8) })
            }
            row.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
                if (allowSkip) 0.7f else 1f).apply { marginStart = dp(8) })

            card.addView(header); card.addView(body); card.addView(row)

            var expanded = true
            fun setExpanded(v: Boolean) {
                expanded = v
                body.visibility = if (v) View.VISIBLE else View.GONE
                row.visibility  = if (v) View.VISIBLE else View.GONE
                doneMini.visibility = if (v) View.GONE else View.VISIBLE
                toggle.text = if (v) "▴" else "▾"        // 화살표는 '누르면 일어날 일'을 가리킨다
                title.textSize = if (v) 17f else 14f
                if (v) card.setPadding(dp(20), dp(18), dp(20), dp(16))
                else   card.setPadding(dp(18), dp(10), dp(10), dp(10))
            }
            toggle.setOnClickListener { setExpanded(!expanded) }

            fun close(res: Int) {
                choice = res
                root?.let { wm.removeViewImmediate(it) }   // 카드가 남으면 재캡처에 찍힌다
                root = null; pendingLatch = null; latch.countDown()
            }
            done.setOnClickListener { close(CRED_DONE) }
            doneMini.setOnClickListener { close(CRED_DONE) }
            skip?.setOnClickListener { close(CRED_SKIP) }
            stop.setOnClickListener { close(CRED_STOP) }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // 뒤 앱이 입력 포커스를 유지
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // 카드 밖 터치는 뒤 앱(키보드)으로
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP        // 키보드는 아래에 뜨므로 카드는 위로
            lp.y = dp(24)
            root = card
            wm.addView(card, lp)
        }
        val answered = latch.await(CRED_TIMEOUT_MIN, TimeUnit.MINUTES)
        pendingLatch = null
        if (!answered) {
            ui.post { root?.let { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
            return CRED_STOP
        }
        Thread.sleep(150)   // 창이 실제로 사라질 시간. 이 뒤라야 재캡처가 깨끗하다.
        return choice
    }

    /** 지정한 세로 구간에서 순수 #000000 픽셀의 비율. 디코딩 실패면 -1. */
    private fun blackPercentOf(png: ByteArray, topFrac: Double, botFrac: Double): Int {
        val opt = BitmapFactory.Options().apply { inSampleSize = 8 }   // 축소 디코딩
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opt) ?: return -1
        val y0 = (bmp.height * topFrac).toInt()
        val y1 = (bmp.height * botFrac).toInt()
        var total = 0; var black = 0
        for (y in y0 until y1 step 2) for (x in 0 until bmp.width step 2) {
            total++
            if ((bmp.getPixel(x, y) and 0x00FFFFFF) == 0) black++
        }
        bmp.recycle()
        return if (total > 0) black * 100 / total else -1
    }

    /** 본문이 순수 검정인 비율(0~100). 판정 대상이 아니면 -1. 지표는 함께 로그로 남긴다.
     *
     *  실측(2026-08-06)으로 버려진 후보들 — 되살리기 전에 데이터부터 볼 것:
     *   - 노드 개수: 보안 화면 6~15 vs 정상 3~171 로 완전히 겹쳐 판별력이 없다.
     *   - isPassword 플래그: 카카오톡·토스 모두 커스텀 키패드라 0개.
     *  그래서 판정은 픽셀 하나로만 한다. 노드 지표는 로그에만 남겨 데이터를 계속 모은다.
     *
     *  검정은 '어두움'이 아니라 정확히 #000000 으로 센다 — 다크모드 화면은 글자·아이콘 픽셀이
     *  섞여 걸러지지만, 진짜 검은 영상은 여전히 통과한다(유튜브 실측 100%). 그 오탐은
     *  코드가 아니라 인계 카드의 [그냥 계속] 이 흡수한다.
     */
    private fun probeSecureScreen(png: ByteArray): Int {
        var blackPct = -1
        try {
            var nodes = 0
            var pwd = false
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || nodes >= 2000 || depth > 40) return   // 병적인 트리 방어
                nodes++
                if (n.isPassword) pwd = true
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            val root = rootInActiveWindow
            walk(root, 0)

            // 픽셀 판정은 디코딩이 필요해 비싸다. 정상 화면은 288KB 수준이라
            // 바이트 수로 먼저 걸러 대부분의 턴에서 디코딩 자체를 건너뛴다.
            // 상태바·내비바는 보안 화면에서도 가려지지 않으므로 본문만 표본으로 삼는다.
            if (png.size < PROBE_PNG_PREFILTER) blackPct = blackPercentOf(png, 0.10, 0.90)
            // quiet = 캡처 시점에 화면이 '조용해진 지' 얼마나 됐나.
            //   작을수록 아직 바뀌는 중에 찍었다는 뜻 = sleep(600) 이 짧다는 증거.
            val quiet = if (lastEventTs == 0L) -1 else (SystemClock.uptimeMillis() - lastEventTs)
            Log.i("a11probe", "after=$lastAction quiet=${quiet}ms png=${png.size} " +
                    "black%=$blackPct nodes=$nodes pwd=$pwd fg=${root?.packageName}")
        } catch (e: Exception) {
            // 판정이 실행을 방해하면 안 된다. 실패하면 '검지 않음'으로 보고 평소대로 진행한다.
            Log.e("a11probe", "probe 실패(무시): ${e.message}")
            return -1
        }
        return blackPct
    }


    override fun dispatch(name: String, args: JSONObject): JSONObject? {
        lastAction = name                      // [Phase 1 · 임시 계측] 액션별로 묶어 보기 위함
        val w = lastW; val h = lastH
        when (name) {
            "click" -> tapBlocking(pxX(args.getInt("x"), w).toFloat(), pxY(args.getInt("y"), h).toFloat())
            "long_press" -> {
                val x = pxX(args.getInt("x"), w).toFloat(); val y = pxY(args.getInt("y"), h).toFloat()
                swipeBlocking(x, y, x, y, (args.optDouble("seconds", 2.0) * 1000).toLong())
            }
            "drag_and_drop" -> swipeBlocking(
                pxX(args.getInt("start_x"), w).toFloat(), pxY(args.getInt("start_y"), h).toFloat(),
                pxX(args.getInt("end_x"), w).toFloat(),   pxY(args.getInt("end_y"), h).toFloat(), 300)
            // ★ 자체 안전 게이트(액션 기반) — 비밀번호 칸에는 우리가 절대 쓰지 않는다.
            //   '화면이 로그인 화면인가'를 판정하지 않는 게 핵심이다. 그건 회원가입 폼·비밀번호
            //   변경 화면·로그인 폼이 일부만 있는 페이지에서 전부 오탐이 난다. 대신 '지금 쓰려는
            //   그 칸이 비밀 값인가'만 본다 — 화면을 스쳐 지나갈 때는 아예 걸리지 않는다.
            //
            //   목표 문장에 비밀번호가 적혀 있어도 막는다. 예외를 열면 (a) 모델이 진짜 비밀번호를
            //   자기 추측값으로 덮어쓸 수 있고 (b) 앱이 자격증명을 쥐는 순간이 생긴다.
            "type" -> {
                if (focusedIsPassword()) return credentialHandover("비밀번호는 직접 입력해 주세요.")
                setText(args.getString("text")); if (args.optBoolean("press_enter", false)) imeEnter()
            }
            // 모델이 '내가 모르는 값을 요구받았다'고 스스로 알릴 때 부르는 통로(CuClient.REQUEST_USER_INPUT).
            // 인증번호처럼 노드에 아무 표식이 없는 경우(실측: isPassword·resource-id·content-desc 모두 빔)는
            // 코드가 알아낼 방법이 없어 이 경로가 유일한 수단이다. 근거는 CLAUDE.md Gotchas.
            // allowSkip=true — 모델 판단이라 헛짚을 수 있다(실측: 인증번호 화면 참양성 2/5).
            // 잘못 떴을 때 사용자가 [필요 없어요]로 빠져나가지 못하면, 아무것도 안 치고
            // [입력했어요]를 누를 수밖에 없고 그러면 모델에게 거짓을 보고하게 된다.
            "request_user_input" -> return credentialHandover(
                args.optString("reason").ifBlank { "직접 입력이 필요한 값이 있습니다." },
                allowSkip = true)
            "press_key" -> when (val k = args.optString("key").lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "enter" -> imeEnter()
                "app_switch" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                // else 가 없으면 지원하지 않는 키(숫자 등)를 조용히 무시하고 {"status":"ok"} 로
                // 보고해, 모델이 '눌렸다'고 믿고 다음 단계로 넘어간다. 실측: 잠금화면에서
                // press_key{key=4} 4번이 전부 무시됐는데 성공으로 보고돼 4턴이 낭비됐다.
                // 실패는 실패로 알려야 모델이 다른 방법(좌표 탭)으로 자기교정한다.
                else -> throw IllegalArgumentException(
                    "Unsupported key '$k'. Supported: back, home, enter, app_switch. " +
                    "To press an on-screen key, click its coordinates instead.")
            }
            "go_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "open_app" -> {
                val pkg = args.optString("package_name").ifEmpty { args.optString("app_name") }
                if (pkg.isNotEmpty()) openApp(pkg) else performGlobalAction(GLOBAL_ACTION_HOME)
            }
            "wait" -> Thread.sleep((args.optDouble("seconds", 1.0) * 1000).toLong())
            "take_screenshot" -> { /* 다음 스냅샷이 곧 결과 */ }
            // 런처가 있는 앱만. getInstalledPackages(0)은 시스템 패키지까지 수백 개를
            // 쏟아내 모델이 목표 앱을 못 고른다. 원본 live/adb_bridge.py 의 `pm list packages -3`에 대응.
            // 라벨을 함께 주어 한국어 지시("유튜브 열어줘")를 패키지명에 매칭할 수 있게 한다.
            "list_apps" -> {
                val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = packageManager.queryIntentActivities(q, 0)
                    .map { "${it.loadLabel(packageManager)} (${it.activityInfo.packageName})" }
                    .distinct().sorted()
                return JSONObject().put("apps", JSONArray(apps))
            }
            else -> throw IllegalArgumentException("Unknown action: $name")
        }
        return null
    }
    override fun onServiceConnected() {
        Log.d("A11y", "connected")
        // 접근성 버튼 플래그가 실제로 읽혔는지. config 를 바꾼 뒤 서비스를 껐다 켜지 않으면 옛 설정이
        // 남아 있어 false 가 나온다 — "버튼이 안 눌린다"를 코드 탓과 설정 탓으로 바로 가르려고 남긴다.
        Log.d("A11y", "accessibility button requested=" + ((serviceInfo?.flags ?: 0) and
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON != 0))
        instance = this
        // 접근성 버튼 콜백. 메인 스레드 핸들러로 받는다 — 콜백 안에서 startActivity 를 부르므로.
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback, ui)
        createChannel()
        startServer()
    }

    /**
     * 8080 소켓 서버. **연결마다 스레드를 띄운다.**
     *
     * 왜 — 예전엔 accept 루프에서 명령 처리까지 직접 했다. 그러면 처리 중 **한 군데라도
     * 안 끝나면 서버가 통째로 죽는다**: 포트는 LISTEN 인데 아무 명령도 안 받고, 앱을
     * 재시작하기 전엔 안 풀린다. 실제로 세 번 겪었고 매번 물린 자리가 달랐다 —
     *   ① 인계 카드가 없는 사람을 3분 기다림   → attended 깃발로 해결
     *   ② cuCall 이 5분 넘게 안 돌아옴          → callTimeout 으로 해결
     *   ③ 응답을 쓰다 멈춤(자바 소켓엔 쓰기 타임아웃이 없다) → 여기, 구조로 해결
     * 하나씩 막는 걸로는 안 끝난다는 게 ③에서 분명해졌다. 이제 물려도 **그 스레드만**
     * 죽고 서버는 계속 명령을 받는다.
     *
     * 남은 구멍: 쓰기가 멈춘 스레드는 회수되지 않는다(자바 소켓에 쓰기 상한이 없어
     * NIO 없이는 못 막는다). 스레드 하나가 새는 건 서버가 죽는 것보다 훨씬 낫다.
     */
    private fun startServer() {
        thread(isDaemon = true) {
            val server = ServerSocket(8080)
            Log.d("A11y", "server listening on 8080")
            while (true) {
                val client = try { server.accept() } catch (e: Exception) {
                    Log.e("A11y", "accept 실패: ${e.message}"); continue
                }
                thread(isDaemon = true) { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        run {
                Log.d("A11y", "client connected: ${client.inetAddress}")
                try {
                    // 읽기 상한. 명령을 보내다 만 클라이언트가 이 스레드를 영원히 잡지 않게.
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    // 클라이언트가 보낸 명령 한 줄을 읽는다. readLine()은 '\n'까지 읽고 개행은 뗀다.


                    val line = client.getInputStream().bufferedReader().readLine() ?: ""
                    Log.d("A11y", "cmd: $line")
                    val p = line.trim().split(" ")

                    when (p[0]) {
                        "SHOT" -> {
                            val png = capturePngBlocking()
                            val out = client.getOutputStream()
                            val n = png.size
                            out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(),
                                (n ushr 8).toByte(), n.toByte()))
                            out.write(png); out.flush()
                            Log.d("A11y", "sent png $n bytes")
                        }
                        "TAP"       -> { tapBlocking(p[1].toFloat(), p[2].toFloat()); ackOK(client) }
                        "LONGPRESS" -> { swipeBlocking(p[1].toFloat(), p[2].toFloat(),
                            p[1].toFloat(), p[2].toFloat(), p[3].toLong()); ackOK(client) }
                        "SWIPE"     -> { swipeBlocking(p[1].toFloat(), p[2].toFloat(),
                            p[3].toFloat(), p[4].toFloat(), p[5].toLong()); ackOK(client) }
                        "TEXT"      -> { setText(String(Base64.decode(p[1], Base64.DEFAULT))); ackOK(client) }
                        "ENTER"     -> { imeEnter(); ackOK(client) }
                        "BACK"      -> { performGlobalAction(GLOBAL_ACTION_BACK); ackOK(client) }
                        "HOME"      -> { performGlobalAction(GLOBAL_ACTION_HOME); ackOK(client) }
                        "RECENTS"   -> { performGlobalAction(GLOBAL_ACTION_RECENTS); ackOK(client) }
                        "OPEN"      -> { openApp(p[1]); ackOK(client) }

                        // ── 측정 하네스용 (Unit 4) ──────────────────────────
                        //  기억을 PC 에서 세웠다 비웠다 할 수 있어야 A/B 를 **번갈아** 돌린다.
                        //  손으로 목록 UI 를 만져야 하면 배치를 A 5회 / B 5회로 쪼갤 수밖에 없고,
                        //  그러면 시간대·앱 학습 효과가 조건에 엮인다.
                        //  ★ 전부 bench{} 로 감싼다. 예외를 그냥 튀우면 바깥 catch 가 로그만
                        //    남기고 연결을 닫아, PC 는 **빈 응답**만 본다. 그러면 러너가
                        //    "기억이 거절됐다"와 "연결이 죽었다"를 구분할 수 없고, 조건 B 가
                        //    조용히 비어 있는 채로 측정이 돌아간다.
                        "MEMCLEAR"  -> bench(client) { "OK ${gateway.deleteAll()}" }
                        "MEMADD"    -> bench(client) {
                            // JSON 에 공백이 있으므로 split 이 아니라 첫 공백 뒤 전체를 쓴다.
                            val body = line.trim().substringAfter(" ")
                            "OK ${gateway.addFromJson(org.json.JSONObject(body))}"
                        }
                        "MEMCOUNT"  -> bench(client) { "OK ${gateway.count()}" }
                        "DUMP"      -> bench(client) { dumpLastRun() }
                        "RUN" -> {
                            val task = if (p.size > 1) line.trim().substringAfter(" ") else "설정 앱을 열어"
                            // 소켓 경로도 기록한다 — tools/bench_*.py 가 이쪽을 쓰므로
                            // 여기서 빠지면 정작 측정할 실행이 로그에 안 남는다.
                            // ★ 실행 단위 상태를 runTask 와 똑같이 초기화한다. 안 하면 지난 실행이
                            //   남긴 cancelled=true 를 물려받아 첫 턴에서 곧바로 중단된다.
                            // ★ 연결마다 스레드라 RUN 이 동시에 둘 들어올 수 있다.
                            //   두 에이전트가 같은 화면을 조작하면 화면도 기록도 엉킨다.
                            if (agentBusy) {
                                ackLine(client, "ERR 이미 실행 중입니다")
                                return@run
                            }
                            agentBusy = true
                            cancelled = false
                            skipBlackPkgs.clear()
                            attended = false            // 소켓 = 지켜보는 사람이 없는 실행
                            //
                            // ★ cancel 을 넘기지 않으면 기본값이 {false} 라 **중단이 아예 안 된다** —
                            //   앱의 중단 버튼도, 인계 타임아웃이 세우는 requestCancel() 도 무시된다.
                            //   실측 2026-09-13: 그래서 인계가 3분 만에 포기한 뒤에도 검은 화면을
                            //   들고 그대로 진행했다.
                            val result = try {
                                runAgent(this, cu, task,
                                    cancel = { cancelled }, trace = runTrace)  // this = a11service = Executor
                            } catch (e: Exception) {
                                // 예외를 그대로 튀우면 바깥 catch 가 로그만 남기고 연결을 닫아 PC 쪽은
                                // '빈 응답'만 본다. 측정 스크립트가 이유를 읽게 한 줄로 돌려준다.
                                "오류: ${e.message}"
                            } finally {
                                attended = true         // 다음 앱 UI 실행이 무인으로 오해받지 않게
                                agentBusy = false
                            }
                            client.getOutputStream().apply {
                                write((result + "\n").toByteArray()); flush()
                            }
                        }
                        else        -> { Log.e("A11y", "unknown cmd: $line"); ackOK(client) }
                    }
                } catch (e: Exception) {
                    Log.e("A11y", "client error: ${e.message}")
                } finally {
                    try { client.close() } catch (_: Exception) {}  // 이번 요청 끝 → 연결 정리
                }
        }
    }
    private fun pxX(norm: Int, w:Int) = (norm / 1000.0 * w).toInt()
    private fun pxY(norm: Int, h:Int) = (norm / 1000.0 * h).toInt()

    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        fun be(o: Int) = ((png[o].toInt() and 0xFF) shl 24) or ((png[o+1].toInt() and 0xFF) shl 16) or
                ((png[o+2].toInt() and 0xFF) shl 8) or (png[o+3].toInt() and 0xFF)
        return Pair(be(16), be(20))
    }

    // takeScreenshot은 결과를 '콜백'으로 준다(비동기). 서버 스레드는 결과를 손에 쥐어야
    // 소켓으로 보낼 수 있다 → CountDownLatch로 "콜백이 끝날 때까지" 기다린다.
    private fun capturePngBlocking(): ByteArray {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ByteArray(0)
        var failCode: Int? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val buffer = r.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, r.colorSpace)
                    buffer.close()                                 // 네이티브 자원 해제(필수)
                    val out = ByteArrayOutputStream()
                    bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                    result = out.toByteArray()
                    latch.countDown()
                }
                override fun onFailure(code: Int) {
                    Log.e("A11y", "capture failed: $code")
                    failCode = code
                    latch.countDown()
                }
            })
        // ★ 상한 없이 기다리면 콜백이 한 번 안 오는 것만으로 이 스레드가 영구히 멈춘다.
        //   소켓 서버는 단일 스레드라(accept 루프에서 runAgent 를 그대로 돌린다) 그때
        //   서버가 통째로 죽는다 — 포트는 LISTEN 인데 아무 명령도 안 받고, 앱 재시작
        //   전엔 안 풀린다. 정상값이 ~0.3초이므로 10초면 '안 온다'로 봐도 된다.
        if (!latch.await(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS))
            throw IllegalStateException("화면 캡처 응답 없음 (${CAPTURE_TIMEOUT_S}초)")
        // 빈 배열을 그대로 돌려주면 곧바로 pngSize() 가 png[16] 을 읽다 터져
        // "length=0; index=16" 같은 알 수 없는 메시지로 실행이 끝난다(실제 로그에서 관측).
        // 원인이 드러나는 예외로 바꾼다.
        failCode?.let { throw IllegalStateException("화면 캡처 실패 (code=$it)") }
        if (result.isEmpty()) throw IllegalStateException("화면 캡처 결과가 비어 있음")
        return result
    }

    private fun ackOK(client:Socket){
        val out = client.getOutputStream()
        out.write("OK\n".toByteArray());out.flush()
    }

    /**
     * 측정 명령의 응답. 성공이면 [body] 가 돌려준 줄을, 실패면 `ERR <사유>` 를 보낸다.
     *
     * **반드시 한 줄이 나가야 한다.** 러너는 응답으로만 상태를 아는데, 빈 응답은
     * "거절됐다"와 "연결이 죽었다"를 구분해 주지 못한다. 특히 MEMADD 가 조용히 실패하면
     * 조건 B 에 기억이 없는 채로 측정이 돌아가 **"기억은 효과가 없다"는 틀린 결론**이 난다.
     */
    private inline fun bench(client: Socket, body: () -> String) {
        val reply = try { body() } catch (e: Exception) {
            Log.w("A11y", "bench 명령 실패", e)
            "ERR " + (e.message ?: e.javaClass.simpleName).lines().joinToString(" ")
        }
        ackLine(client, reply)
    }

    /** 한 줄 응답. toByteArray() 는 UTF-8 이라 한글이 그대로 간다. */
    private fun ackLine(client: Socket, s: String) {
        val out = client.getOutputStream()
        out.write((s + "\n").toByteArray()); out.flush()
    }

    /**
     * 마지막 run 1건 + 그 턴들을 JSON 한 줄로. 측정 스크립트가 결과를 프로그램으로 읽는다.
     *
     * 왜 turnsUsed 만으로 안 되나 — 탐색 라운드에서 **어디서 턴을 낭비했는지**를 봐야
     * 기억 문장을 쓸 수 있다. episode 를 같이 실어야 그걸 눈이 아니라 스크립트로 읽는다.
     *
     * memoryCount 를 함께 싣는 이유: 조건 A 가 **정말 0건이었는지** 실행마다 확인해야 한다.
     * 확인하지 않으면 A 와 B 가 같은 조건이었던 실행이 섞여도 알 수 없다.
     */
    private fun dumpLastRun(): String = try {
        val r = memoryDao.recentRuns(1).firstOrNull()
            ?: return org.json.JSONObject().put("error", "run 행이 없다").toString()
        val eps = org.json.JSONArray()
        for (e in memoryDao.episodesOf(r.id)) {
            eps.put(org.json.JSONObject()
                .put("turn", e.turn).put("pkg", e.pkg ?: org.json.JSONObject.NULL)
                .put("action", e.action ?: org.json.JSONObject.NULL)
                .put("intent", e.intent ?: org.json.JSONObject.NULL)
                .put("result", e.result ?: org.json.JSONObject.NULL)
                .put("note", e.note ?: org.json.JSONObject.NULL))
        }
        org.json.JSONObject()
            .put("runId", r.id).put("goal", r.goal)
            .put("model", r.model).put("thinking", r.thinking).put("maxTurns", r.maxTurns)
            .put("outcome", r.outcome ?: org.json.JSONObject.NULL)
            .put("turnsUsed", r.turnsUsed ?: org.json.JSONObject.NULL)
            .put("startedAt", r.startedAt).put("endedAt", r.endedAt ?: org.json.JSONObject.NULL)
            .put("memoryReadFailed", r.memoryReadFailed ?: org.json.JSONObject.NULL)
            .put("memoryCount", memoryDao.memoryCount())
            .put("episodes", eps)
            .toString()
    } catch (e: Exception) {
        // 여기서 던지면 바깥 catch 가 연결을 닫아 PC 는 '빈 응답'만 본다.
        org.json.JSONObject().put("error", e.message ?: e.javaClass.simpleName).toString()
    }
    override fun confirm(explanation: String): Boolean {
        // 무인 실행에는 승인할 사람이 없다. 60초(아래 latch)를 기다려 봐야 결국 미승인이므로
        // 지금 거부한다. **자동 승인은 절대 안 된다** — 지켜보는 사람이 없을수록 더 그렇다.
        // 인계·자격증명 카드와 같은 원칙이고, 소켓 수정(2026-09-13) 때 여기만 빠졌었다.
        if (!attended) {
            Log.w("A11y", "무인 실행 — 확인 카드를 안 띄우고 거부한다: $explanation")
            return false
        }
        if (!Settings.canDrawOverlays(this)) return false   // 권한 없으면 안전하게 거부
        val latch = CountDownLatch(1)
        var approved = false
        var root: View? = null
        pendingLatch = latch                     // 중단 버튼이 이 대기를 깨울 수 있게(깨우면 미승인)
        ui.post {
            val wm = getSystemService(WindowManager::class.java)
            val d = resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()

            // 배경 딤(scrim) — 뒤를 어둡게 깔아 카드가 떠 보이게
            val scrim = FrameLayout(this).apply { setBackgroundColor(0xB3000000.toInt()) }

            // 카드(둥근 서피스)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(0xFF1E1F24.toInt())     // 순검정 대신 블루그레이 다크 서피스
                }
                setPadding(dp(24), dp(24), dp(24), dp(20))
                elevation = dp(16).toFloat()
            }

            val icon = TextView(this).apply { text = "⚠️"; textSize = 30f }
            val title = TextView(this).apply {
                text = "동작 확인이 필요해요"
                setTextColor(0xFFF2F3F5.toInt()); textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, 0)
            }
            val body = TextView(this).apply {
                text = explanation
                setTextColor(0xFFAAB0BA.toInt()); textSize = 14f    // 보조 회색
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(22))
            }

            // 둥근 알약 버튼(TextView 기반 — 커스텀 색/모서리)
            fun pill(label: String, textColor: Int, bg: Int, border: Boolean) = TextView(this).apply {
                text = label; setTextColor(textColor); textSize = 15f
                gravity = Gravity.CENTER; isClickable = true
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(bg)
                    if (border) setStroke(dp(1), 0xFF3A3B42.toInt())
                }
            }
            val no = pill("거부", 0xFFC9CDD4.toInt(), 0x00000000, border = true)   // 고스트
            val ok = pill("승인", 0xFFFFFFFF.toInt(), 0xFF3B82F6.toInt(), border = false) // 파랑 강조

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(no, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) })

            card.addView(icon); card.addView(title); card.addView(body); card.addView(row)

            scrim.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER; marginStart = dp(28); marginEnd = dp(28) })

            // removeViewImmediate: 창을 '지금' 떼어낸다. removeView 는 제거를 예약만 하고 돌아오는데,
            // 이 카드는 전체화면 모달(뒤 앱 오터치 방지)이라 아직 떠 있는 상태에서 dispatchGesture 를
            // 쏘면 승인 직후의 탭을 카드가 먹어버린다(= 삭제가 안 되고 모델이 무한 재시도).
            fun close(res: Boolean) {
                approved = res
                root?.let { wm.removeViewImmediate(it) }
                root = null
                pendingLatch = null
                latch.countDown()
            }
            no.setOnClickListener { close(false) }
            ok.setOnClickListener { close(true) }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // ★ FLAG_NOT_FOCUSABLE 이 없으면(예전 flags=0) 이 창이 입력 포커스를 가져가고,
                //   그 순간 뒤 앱의 입력칸이 포커스를 잃어 **키보드가 내려간다**. 키보드가 사라지면
                //   뒤 앱 레이아웃이 다시 흘러 버튼 위치가 바뀌는데, 승인 후 우리가 쏘는 탭 좌표는
                //   **카드가 뜨기 전 화면**을 보고 모델이 계산한 값이라 엉뚱한 곳을 누른다.
                //   실측(2026-08-12, ChatGPT 메시지 전송): 전송 버튼 승인을 3번 했는데 3번 다
                //   안 나갔다. 카드가 닫히면 키보드가 다시 올라와 다음 턴엔 반대로 어긋난다.
                //
                //   이 플래그는 FLAG_NOT_TOUCH_MODAL 을 함께 켜서 '창 밖' 터치를 통과시키지만,
                //   이 창은 MATCH_PARENT × MATCH_PARENT 라 **바깥이 없다** — 화면 전체가 창 안이므로
                //   모든 터치를 그대로 흡수한다. 즉 오터치 방지는 그대로다.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            root = scrim
            wm.addView(scrim, lp)
        }
        val answered = latch.await(60, TimeUnit.SECONDS)
        pendingLatch = null
        // 중단 버튼이 깨웠다면 승인으로 볼 수 없다 — 미승인으로 돌려보내 루프가 종료되게 한다.
        if (!answered || cancelled) {
            ui.post { root?.let { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
            return false
        }
        Thread.sleep(150)   // 창이 실제로 화면에서 사라질 시간. 이 뒤라야 탭이 뒤 앱에 닿는다.
        return approved
    }

private fun dispatchBlocking(gesture:GestureDescription){
        val latch = CountDownLatch(1)
        dispatchGesture(gesture,object : GestureResultCallback(){
            override fun onCompleted(d:GestureDescription?){latch.countDown()}
            override fun onCancelled(d:GestureDescription?){latch.countDown()}
        },null)
        // 상한을 두는 이유는 capturePngBlocking 주석 참조. 여기서 던지면 runAgent 의
        // 액션별 try 가 받아 {"status":"error"} 로 모델에 알리므로 모델이 스스로 고친다.
        if (!latch.await(GESTURE_TIMEOUT_S, TimeUnit.SECONDS))
            throw IllegalStateException("제스처 응답 없음 (${GESTURE_TIMEOUT_S}초)")
    }
    private fun tapBlocking(x:Float,y:Float){
        val path = Path().apply {moveTo(x,y)}
        val stroke = GestureDescription.StrokeDescription(path,0,60)
        dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun swipeBlocking(x1: Float, y1:Float, x2:Float,y2:Float,durMs:Long){
        val path = Path().apply {moveTo(x1,y1);lineTo(x2,y2)}
        val stroke = GestureDescription.StrokeDescription(path,0,durMs)
        dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
    }

    /** 지금 입력 포커스를 가진 칸이 '가려지는 비밀 값'인가.
     *
     *  isPassword 는 우리가 추측하는 값이 아니라 **앱이 스스로 붙인 표식**이다
     *  (`inputType="textPassword"`, 웹이면 `<input type="password">`). 근거가 앱 자신의
     *  선언이라 오탐이 구조적으로 거의 없다. 웹뷰에서도 그대로 올라온다(네이버 로그인 실측).
     *
     *  ⚠ CLAUDE.md 의 "isPassword 는 판별력이 없다(카톡·토스 0개)" 표를 근거로 이 함수를
     *  지우지 말 것. 그건 **커스텀 키패드 잠금화면** 얘기다 — 거긴 EditText 자체가 없다.
     *  표준 로그인 폼은 상황이 다르고, 그쪽은 검은 화면 인계가 따로 맡는다.
     *
     *  반대로 이 신호의 사정거리도 딱 여기까지다: 인증번호 칸은 화면에 숫자가 보여야 해서
     *  앱이 마스킹하지 않으므로 false 다. 그 경우는 모델이 request_user_input 으로 알린다.
     */
    private fun focusedIsPassword(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.isPassword
    }

    /** 로그 마스킹용(Executor). 판정 자체는 focusedIsPassword 와 같은 것을 본다. */
    override fun isSecretFieldFocused(): Boolean = focusedIsPassword()

    private fun setText(text : String){
        val root = rootInActiveWindow ?:return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply{
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)
    }
    private fun imeEnter(){
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?:return
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }
    private fun openApp (pkg: String){
        // 조용히 return 하면 dispatch 가 null 을 반환해 모델에 {"status":"ok"} 로 보고된다.
        // 실패를 성공이라 속이면 모델이 자기교정을 못 한다. 예외는 runAgent 가 잡아 error 로 전달.
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: throw IllegalStateException("App $pkg is not installed or has no launcher.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }




    // [Phase 1 · 임시 계측] 화면이 마지막으로 바뀐 시각만 기록한다(내용은 안 본다).
    //  우리 오버레이(진행 표시 띠)도 이벤트를 내므로 자기 패키지는 걸러야 한다 —
    //  안 그러면 hideForShot() 이 캡처 직전에 이벤트를 만들어 quiet 이 항상 0에 가깝게 나온다.
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.packageName != packageName) lastEventTs = SystemClock.uptimeMillis()
    }
    override fun onInterrupt() {}
}