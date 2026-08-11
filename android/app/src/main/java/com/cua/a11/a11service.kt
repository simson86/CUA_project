package com.cua.a11

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.view.Display
import android.graphics.Bitmap
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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import java.util.concurrent.TimeUnit


class a11service : AccessibilityService(), Executor {
    companion object{
        @Volatile
        var instance : a11service? = null
            private set
        const val CHANNEL_ID= "cu_run"
        const val NOTI_ID = 1001
    }

    override fun onUnbind(intent: Intent?): Boolean{
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
    fun requestCancel() { cancelled = true }
    // ★ log 는 반드시 맨 뒤 — MainActivity 가 trailing lambda 로 넘긴다.
    //   중간에 파라미터를 끼우면 `svc.runTask(task, maxTurns, model, thinking) { … }` 문법이 깨진다.
    // ★ 새 파라미터엔 기본값을 준다 — 소켓 RUN 경로와 기존 호출부가 안 깨지게.
    fun runTask(task: String, maxTurns: Int = 20,
                model: String = CuClient.DEFAULT_MODEL,
                thinking: String = CuClient.DEFAULT_THINKING,
                log: (String) -> Unit = {}): String {
        // 지난 실행에서 중단 버튼이 눌렸으면 cancelled 가 true 로 남아 있다.
        // 초기화하지 않으면 새 요청이 첫 턴에서 곧바로 중단된다.
        cancelled = false
        cu.model = model                     // 이번 판에 쓸 설정을 갈아끼운다
        cu.thinkingLevel = thinking
        // 이 줄을 빼지 말 것 — 설정을 바꿀 수 있게 만든 순간, '어떤 설정이 어떤 결과를 냈는지'가
        // 기록에 안 남는 게 가장 큰 손해다. run_history.txt 에 남는 유일한 증거다.
        log("[설정] ${cu.settingsLine()} maxTurns=$maxTurns")
        showOverlay(task)
        val r = try {
            runAgent(this, cu, task,maxTurns,
                log = { line -> log(line);postOverlay(line) },
                cancel = {cancelled})
        } catch (e: Exception) {
            "오류: ${e.message}"     // screenshot/네트워크 예외도 알림에 잡히게
        }
        postOverlay(r)
        notifyDone(task, r)
        ui.postDelayed({ hideOverlay() }, 4000)   // 결과 4초 보여주고 닫음
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
        latch.await()                                             // 반영될 때까지 대기
        Thread.sleep(32)                                          // 컴포지터가 없는 프레임 그릴 시간
    }
    private fun showAfterShot() {
        val tv = overlayView ?: return
        ui.post { tv.visibility = View.VISIBLE }
    }

    // 모델·사고수준은 앱 드롭다운에서 매 실행 고른다(문서: android_run-model-thinking.md).
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

    // ── 앱별 참고사항 (지금은 비어 있음 — 발견되면 채운다) ──────────────────────
    //  지금 화면에 떠 있는 앱일 때만 모델에게 전달된다. system_prompt 에 넣으면 무관한
    //  작업에서도 계속 따라다니며 다른 지시의 주의력을 뺏으므로, 여기 두고 조건부로 붙인다.
    //  넣는 규칙:
    //   - 로그에서 모델이 '실제로 반복해서 막히는 것'을 확인한 뒤에만 추가. 미리 상상해서 쓰지 말 것.
    //   - 한 앱당 한두 줄. 왜 넣었는지(어떤 실패를 봤는지) 옆에 주석으로 남길 것.
    //   - 앱이 업데이트되어 UI 가 바뀌면 낡은 메모가 모델을 오도한다 — 같이 정리할 것.
    //   - 여러 앱에 공통인 사항이면 여기 말고 CuClient.taskNotes 나 system_prompt 쪽을 먼저 검토.
    private val appNotes = mapOf<String, String>(
        // "com.sec.android.gallery3d" to
        //     "Samsung Gallery: tapping the trash icon opens a second dialog that must " +
        //     "also be confirmed before the photo is actually deleted.",
    )

    override fun appNote(): String? =
        rootInActiveWindow?.packageName?.toString()?.let { appNotes[it] }

    override fun screenshot(): ByteArray {
        hideForShot()                          // 오버레이 숨기고 프레임 대기
        val png = capturePngBlocking()
        showAfterShot()                        // 다시 보이기
        val (w, h) = pngSize(png); lastW = w; lastH = h
        return png
    }
    override fun dispatch(name: String, args: JSONObject): JSONObject? {
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
            "type" -> { setText(args.getString("text")); if (args.optBoolean("press_enter", false)) imeEnter() }
            "press_key" -> when (args.optString("key").lowercase()) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "enter" -> imeEnter()
                "app_switch" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
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
        instance = this
        createChannel()
        startServer()
    }

    private fun startServer() {
        thread(isDaemon = true) {
            val server = ServerSocket(8080)
            Log.d("A11y", "server listening on 8080")
            while (true) {
                val client = server.accept()                       // PC 접속 대기
                Log.d("A11y", "client connected: ${client.inetAddress}")
                try {
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
                        "RUN" -> {
                            val task = if (p.size > 1) line.trim().substringAfter(" ") else "설정 앱을 열어"
                            val result = runAgent(this, cu, task)          // this = a11service = Executor
                            client.getOutputStream().apply {
                                write((result + "\n").toByteArray()); flush()
                            }
                        }
                        else        -> { Log.e("A11y", "unknown cmd: $line"); ackOK(client) }
                    }
                } catch (e: Exception) {
                    Log.e("A11y", "client error: ${e.message}")
                } finally {
                    client.close()                                 // 이번 요청 끝 → 연결 정리
                }
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
                    latch.countDown()
                }
            })
        latch.await()
        return result
    }

    private fun ackOK(client:Socket){
        val out = client.getOutputStream()
        out.write("OK\n".toByteArray());out.flush()
    }
    override fun confirm(explanation: String): Boolean {
        if (!Settings.canDrawOverlays(this)) return false   // 권한 없으면 안전하게 거부
        val latch = CountDownLatch(1)
        var approved = false
        var root: View? = null
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
                latch.countDown()
            }
            no.setOnClickListener { close(false) }
            ok.setOnClickListener { close(true) }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                0,                                  // 모달: 전체를 덮어 뒤 앱 오터치 방지(버튼만 반응)
                PixelFormat.TRANSLUCENT
            )
            root = scrim
            wm.addView(scrim, lp)
        }
        val answered = latch.await(60, TimeUnit.SECONDS)
        if (!answered) { ui.post { root?.let { getSystemService(WindowManager::class.java).removeView(it) } }; return false }
        Thread.sleep(150)   // 창이 실제로 화면에서 사라질 시간. 이 뒤라야 탭이 뒤 앱에 닿는다.
        return approved
    }

private fun dispatchBlocking(gesture:GestureDescription){
        val latch = CountDownLatch(1)
        dispatchGesture(gesture,object : GestureResultCallback(){
            override fun onCompleted(d:GestureDescription?){latch.countDown()}
            override fun onCancelled(d:GestureDescription?){latch.countDown()}
        },null)
        latch.await()
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




    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}
}