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
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Base64
import android.content.Intent
import java.net.Socket
import java.util.concurrent.CountDownLatch
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class a11service : AccessibilityService(), Executor {
    private val cu by lazy { CuClient(BuildConfig.GEMINI_API_KEY) }
    private var lastW = 0
    private var lastH = 0

    override fun screenshot(): ByteArray {
        val png = capturePngBlocking()
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
            "list_apps" -> return JSONObject().put("apps",
                JSONArray(packageManager.getInstalledPackages(0).map { it.packageName }))
            else -> throw IllegalArgumentException("Unknown action: $name")
        }
        return null
    }
    override fun onServiceConnected() {
        Log.d("A11y", "connected")
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
                        "CU" -> {
                            // p 형식: "CU 설정 앱을 열어"  (명령 뒤 나머지를 목표로)
                            val task = if (p.size > 1) line.trim().substringAfter(" ") else "설정 앱을 열어"
                            val png = capturePngBlocking()          // 2단계에서 만든 캡처 함수 재사용
                            val result = callGeminiOnce(png, task)   // ← 폰이 직접 Gemini 호출
                            android.util.Log.i("a11cu", result)      // logcat 에서도 확인
                            // 결과 문자열을 그대로 PC로 돌려줌(길이4 + 본문 형태로 보내도 되고, 간단히 줄바꿈 텍스트로)
                            val out = (result + "\n").toByteArray()
                            client.getOutputStream().apply { write(out); flush() }
                        }
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
    private fun imageBlock(png: ByteArray) = JSONObject()
        .put("type", "image")
        .put("data", Base64.encodeToString(png, Base64.NO_WRAP))
        .put("mime_type", "image/png")
    //목표 + 화면
    private fun userInput(task: String, png: ByteArray): JSONArray {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Task: $task"))
            .put(imageBlock(png))
        return JSONArray().put(JSONObject().put("type", "user_input").put("content", content))
    }
    private fun functionResult(name: String, callId: String, png: ByteArray, safetyAck: Boolean): JSONObject {
        val status = JSONObject().put("status", "ok")
        if (safetyAck) status.put("safety_acknowledgement", true)
        val result = JSONArray()
            .put(JSONObject().put("type", "text").put("text", status.toString()))
            .put(imageBlock(png))
        return JSONObject()
            .put("type", "function_result")
            .put("name", name).put("call_id", callId).put("result", result)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val CU_SYSTEM = """
    You are operating an Android phone.
    * Use the provided tools to complete the task.
    * Scroll down to inspect the full screen before assuming an element is missing.
    * You can open apps by package name from anywhere.
    * Type text only using the `type` tool. Do not use the virtual keyboard.
    * If the task is already complete, state that directly.
    """.trimIndent()
    /**
     * 스크린샷(PNG bytes)과 목표를 Gemini CU에 1회 보내고,
     * 돌아온 첫 액션을 "name {args}" 문자열로 만들어 반환한다.
     * 네트워크 호출이라 반드시 백그라운드 스레드에서 부를 것(소켓 서버 스레드는 이미 백그라운드).
     * 5단계는 여기까지 = 로그 확인. 실제 실행/멀티턴은 6단계.
     */
    private fun callGeminiOnce(png: ByteArray, task: String): String {
        // 1) 이미지 → base64 (NO_WRAP: 줄바꿈 없이 한 줄)
        val b64 = Base64.encodeToString(png, Base64.NO_WRAP)

        // 2) 요청 바디 JSON 조립 (§1 계약 그대로)
        val textBlock = JSONObject()
            .put("type", "text")
            .put("text", "Task: $task")
        val imageBlock = JSONObject()
            .put("type", "image")
            .put("data", b64)
            .put("mime_type", "image/png")
        val userInput = JSONObject()
            .put("type", "user_input")
            .put("content", JSONArray().put(textBlock).put(imageBlock))
        val tool = JSONObject()
            .put("type", "computer_use")
            .put("environment", "mobile")
        val body = JSONObject()
            .put("model", "gemini-3.5-flash")
            .put("input", JSONArray().put(userInput))
            .put("system_instruction", CU_SYSTEM)
            .put("tools", JSONArray().put(tool))

        // 3) POST
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .addHeader("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return "HTTP ${resp.code}: ${txt.take(300)}"   // 키 오류·쿼터 등 그대로 보이게
            }
            // 4) 응답 파싱 → 첫 function_call
            val obj = JSONObject(txt)
            val id = obj.optString("id")
            val status = obj.optString("status")
            val steps = obj.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                if (s.optString("type") == "function_call") {
                    val name = s.optString("name")
                    val args = s.optJSONObject("arguments")?.toString() ?: "{}"
                    return "id=$id status=$status action=$name args=$args"
                }
            }
            return "id=$id status=$status (function_call 없음 = 완료로 판단)"
        }
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
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }




    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}
}