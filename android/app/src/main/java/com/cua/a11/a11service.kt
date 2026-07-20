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

class a11service : AccessibilityService() {
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