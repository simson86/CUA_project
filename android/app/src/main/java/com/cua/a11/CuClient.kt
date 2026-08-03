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

    /** 턴2+ 입력: 액션 실행 결과(status) + 실행 후 화면을 input 배열에 담는다.
     *
     *  일반 턴 — result = [text(JSON을 '문자열'로), image]. 문서/quickstart 형식 그대로.
     *  승인 턴 — result = {..., "safety_acknowledgement": true} '객체', 화면은 user_input 으로 따로.
     *
     *  왜 승인 턴만 형식이 다른가(전부 실측):
     *   - 텍스트 블록의 JSON 문자열 안에 넣으면(=문서 형식) 서버가 승인으로 못 읽는다
     *     → "The safety decision ... must be acknowledged" 400. 불리언/문자열/미국식 철자 모두 실패.
     *   - function_result 나 텍스트 블록의 형제 필드로 올리면 "Unknown parameter" 400.
     *   - result 를 '객체'로 보낼 때만 구조화 필드로 읽힌다. 대신 객체 result 에는 이미지 블록을
     *     못 실으므로, 실행 후 화면은 같은 input 배열의 user_input 으로 함께 보낸다.
     *     (이미지를 아예 안 보내면 모델이 눈이 멀어 다음 턴에 take_screenshot 을 요청한다.)
     */
    fun putResult(input: JSONArray, name: String, callId: String, png: ByteArray,
                  status: JSONObject, safetyAck: Boolean) {
        val fr = JSONObject()
            .put("type", "function_result").put("name", name).put("call_id", callId)
        if (safetyAck) {
            status.put("safety_acknowledgement", true)
            input.put(fr.put("result", status))
            input.put(JSONObject().put("type", "user_input")
                .put("content", JSONArray().put(imageBlock(png))))
        } else {
            input.put(fr.put("result", JSONArray()
                .put(JSONObject().put("type", "text").put("text", status.toString()))
                .put(imageBlock(png))))
        }
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
    fun confirm(explanation: String): Boolean
}

// 목표를 완료까지 자율 실행. 좌표는 안 만짐 — 환산은 exec.dispatch 내부에서.
// 반드시 백그라운드 스레드에서 호출(네트워크+제스처 latch).
fun runAgent(exec: Executor, cu: CuClient, task: String, maxTurns: Int = 20,log:(String)->Unit={}, cancel : () -> Boolean = {false}):String{
    fun emit(s:String){android.util.Log.i("a11cu",s);log(s)}
    var png = exec.screenshot()
    var resp = cu.cuCall(cu.userInput(task,png),null)
    var prevId = resp.optString("id")

    for(turn in 1..maxTurns){
        if (cancel()) { emit("[중단] 사용자 중단"); return "중단: 사용자 중단" }
        val calls = cu.functionCalls(resp)
        if(calls.isEmpty()){
            val fin = cu.finalText(resp)
            emit("[완료] $fin")
            return "Done turn=$turn : $fin"
        }
        val results= JSONArray()
        for (c in calls){
            if (cancel()) { emit("[중단] 사용자 중단"); return "중단: 사용자 중단" }
            val name = c.optString("name")
            val callId = c.optString("id")
            val args = c.optJSONObject("arguments") ?: JSONObject()
            emit("[턴 $turn] $name {${fmtArgs(args)}}")

            // ── 안전 확인(HITL): 위험 액션은 '실행 전'에 사용자 승인 ──
            // safety_decision 위치가 스펙(arguments 안)과 실제(스텝 형제 필드)가 다를 수 있어 둘 다 본다.
            val sd = c.optJSONObject("safety_decision") ?: args.optJSONObject("safety_decision")
            val needConfirm = sd?.optString("decision") == "require_confirmation"
            var safetyAck = false
            if (needConfirm) {
                val explanation = sd?.optString("explanation") ?: "되돌릴 수 없는 동작일 수 있습니다."
                emit("[확인요청] $explanation")
                if (!exec.confirm(explanation)) {            // 사용자 응답까지 블로킹
                    // 거부는 서버에 되돌리지 않고 여기서 끝낸다.
                    // require_confirmation 을 낸 호출은 '승인 표시가 붙은 요청'만 받아준다 —
                    // 거부 사실을 function_result 로 보고하려 하면 그 요청 자체가 400 으로 거절된다.
                    // (구글 문서 예제도 거부 시 루프를 break 한다.)
                    emit("[거부] 사용자가 승인하지 않음 — 실행 중단")
                    return "중단: 사용자가 승인하지 않음"
                }
                safetyAck = true                             // 승인됨 → function_result에 ack
            }

            val status = JSONObject().put("status","ok")
            try {
                val extra = exec.dispatch(name,args)
                if(extra != null) for (k in extra.keys()) status.put(k,extra.get(k))
            }catch(e: Exception){
                status.put("status","error").put("error", e.message ?:"")
                emit("⚠ dispatch실패 $name: ${e.message}")
            }
            Thread.sleep(600)
            png = exec.screenshot()
            cu.putResult(results, name, callId, png, status, safetyAck)
        }
        resp= cu.cuCall(results, prevId)
        prevId = resp.optString("id")
    }
    emit("[중단] 최대 턴 도달")
    return "STOP: max turns"
}

private fun fmtArgs(o: JSONObject): String {
    val order = listOf("x", "y", "start_x", "start_y", "end_x", "end_y",
        "text", "press_enter", "key", "package_name", "app_name","intent", "seconds")
    val known = order.filter { o.has(it) }
    val rest = o.keys().asSequence().filter { it !in order }.sorted().toList()
    return (known + rest).joinToString(", ") { "$it=${o.get(it)}" }
}

