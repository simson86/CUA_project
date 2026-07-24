package com.cua.a11

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

class CuClient(private val apiKey : String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // 모델 추론 대기(루프 한 턴)
        .build()

    //프롬프트
    private val system_prompt = """
        You are operating an Android phone.
        * Use the provided tools to complete the task.
        * Scroll down to inspect the full screen before assuming an element is missing.
        * You can open apps by package name from anywhere.
        * Type text only using the `type` tool. Do not use the virtual keyboard.
        * If the task is already complete, state that directly.
    """.trimIndent()

    // ── 요청 조립 ────────────────────────────────────────────────
    private fun imageBlock(png: ByteArray) = JSONObject()
        .put("type", "image")
        .put("data", Base64.getEncoder().encodeToString(png))  // 기본 인코더=줄바꿈 없음(NO_WRAP과 동일)
        .put("mime_type", "image/png")

    /** 턴1 입력: 목표 + 현재 화면 */
    fun userInput(task: String, png: ByteArray): JSONArray {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Task: $task"))
            .put(imageBlock(png))
        return JSONArray().put(JSONObject().put("type", "user_input").put("content", content))
    }

    /** 턴2+ 입력 한 개: 액션 실행 결과(status) + 실행 후 화면. */
    fun functionResult(name: String, callId: String, png: ByteArray,
                       status: JSONObject, safetyAck: Boolean): JSONObject {
        if (safetyAck) status.put("safety_acknowledgement", true)
        val result = JSONArray()
            .put(JSONObject().put("type", "text").put("text", status.toString()))  // JSON을 '문자열'로
            .put(imageBlock(png))
        return JSONObject()
            .put("type", "function_result")
            .put("name", name).put("call_id", callId).put("result", result)
    }

    // ── 실제 호출 (원본 CUClient.create) ─────────────────────────
    /** input 배열 + 선택적 previous_interaction_id → 응답 JSON */
    fun cuCall(input: JSONArray, prevId: String?): JSONObject {
        val body = JSONObject()
            .put("model", "gemini-3.5-flash")
            .put("input", input)
            .put("system_instruction", system_prompt)
            .put("tools", JSONArray().put(JSONObject()
                .put("type", "computer_use").put("environment", "mobile")))
        if (prevId != null) body.put("previous_interaction_id", prevId)  // 턴2+에서만

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${txt.take(300)}")
            return JSONObject(txt)
        }
    }

    // ── 응답 파싱 (원본 cua/actions.py) ──────────────────────────
    /** 실행할 function_call step들 (원본 parse_actions) */
    fun functionCalls(resp: JSONObject): List<JSONObject> {
        val steps = resp.optJSONArray("steps") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("type") == "function_call") out.add(s)
        }
        return out
    }
    /** 완료 판정 = function_call 없음 (원본 is_done) */
    fun isDone(resp: JSONObject) = functionCalls(resp).isEmpty()

    /** 완료 텍스트 추출 (원본 final_text). 완료 판정엔 영향 없음(참고용). */
    fun finalText(resp: JSONObject): String {
        val steps = resp.optJSONArray("steps") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            if (s.optString("type") != "model_output") continue
            val content = s.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val b = content.getJSONObject(j)
                if (b.optString("type") == "text") sb.append(b.optString("text")).append(" ")
            }
        }
        return sb.toString().trim()
    }
}
// ── 판단↔실행 경계 계약  ──
// A11Service가 이걸 "구현"한다. runAgent는 이 계약만 알면 됨(실제 폰 조작은 모름).
interface Executor {
    fun screenshot(): ByteArray
    fun dispatch(name: String, args: JSONObject): JSONObject?
}

// 목표를 완료까지 자율 실행. 좌표는 안 만짐 — 환산은 exec.dispatch 내부에서.
// 반드시 백그라운드 스레드에서 호출(네트워크+제스처 latch).
fun runAgent(exec: Executor, cu: CuClient, task: String, maxTurns: Int = 20):String{
    var png = exec.screenshot()
    var resp = cu.cuCall(cu.userInput(task,png),null)
    var prevId = resp.optString("id")

    for(turn in 1..maxTurns){
        val calls = cu.functionCalls(resp)
        if(calls.isEmpty()){
            val fin = cu.finalText(resp)
            android.util.Log.i("a11cu", "[완료] $fin")
            return "Done turn=$turn : $fin"
        }
        val results= JSONArray()
        for (c in calls){
            val name = c.optString("name")
            val callId = c.optString("id")
            val args = c.optJSONObject("arguments") ?: JSONObject()
            android.util.Log.i("a11cu", "[턴 $turn] $name {${fmtArgs(args)}}")
            val status = JSONObject().put("status","ok")
            try {
                val extra = exec.dispatch(name,args)
                if(extra != null) for (k in extra.keys()) status.put(k,extra.get(k))
            }catch(e: Exception){
                status.put("status","error").put("error", e.message ?:"")
                android.util.Log.e("a11cu","dispatch실패 $name: ${e.message}")
            }
            val safetyAck = args.has("safety_decision")
            Thread.sleep(600)
            png = exec.screenshot()
            results.put(cu.functionResult(name,callId, png, status, safetyAck))
        }
        resp= cu.cuCall(results, prevId)
        prevId = resp.optString("id")
    }
    android.util.Log.i("a11cu", "[중단] 최대 턴 도달")
    return "STOP: max turns"
}

private fun fmtArgs(o: JSONObject): String {
    val order = listOf("x", "y", "start_x", "start_y", "end_x", "end_y",
        "text", "press_enter", "key", "package_name", "app_name", "seconds")
    val known = order.filter { o.has(it) }
    val rest = o.keys().asSequence().filter { it !in order }.sorted().toList()
    return (known + rest).joinToString(", ") { "$it=${o.get(it)}" }
}

