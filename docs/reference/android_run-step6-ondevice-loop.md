# 6단계 — 폰 단독 멀티턴 루프 (판단/실행 분리판, ①번 완성)

> 로컬 전용(.gitignore `hybrid-step*`). 코드는 **네가 직접** 타이핑([[user-writes-code-himself]]).
> 앞: step5에서 "폰이 Gemini 1회 호출 → 액션 로그"까지 검증 완료(✅ list_apps 받음).
> 이번: 그 액션을 **실제로 실행 + 다음 턴으로 이어가기**를 반복 → 폰 혼자 목표 달성.
>
> ★이 판은 원본 `cua`(판단) ↔ `live`(실행) **분리 구조를 그대로 재현**한다.★

---

## §0. 큰 그림 — 원본 3분할을 폰 안에 복제

원본(PC/ADB)의 관계를 Kotlin으로 1:1 이식한다:

| 원본 파일 | 역할 | Kotlin | 담는 것 |
|---|---|---|---|
| `cua/` | **판단** 화면+목표→액션 | **`CuClient.kt`** (새 파일) | cuCall, userInput, functionResult, 파싱. **기기·좌표 모름** |
| `live/adb_bridge.py` | **실행** 캡처·조작 | **`A11Service`** (`Executor` 구현) | screenshot, dispatch(내부 pxX/pxY 환산 + 제스처) |
| `live/main.py`의 `run()` | **오케스트레이터** 루프 | **`runAgent()`** (top-level fun) | `Executor`+`CuClient`만 받아 반복. **좌표 안 만짐** |

**핵심 원칙(원본 그대로):**
- 좌표 환산(0~1000→픽셀)은 **실행부 안에서** 일어난다. `adb_bridge.click`이 내부에서 `denormalize`를 부르듯, `A11Service.dispatch`가 내부에서 `pxX/pxY`를 부른다. 루프는 정규화 좌표를 그대로 흘려보낼 뿐.
- 판단부(`CuClient`)는 **AccessibilityService·제스처를 전혀 모른다.** OkHttp/JSON/Base64만 씀 → 순수 JVM으로 단위 테스트 가능.
- 의존 방향: `A11Service`·`runAgent` → `CuClient` (한 방향). `CuClient`는 아무도 안 봄. (원본에서 `live`가 `cua`를 import 하고 그 반대는 없던 것과 동일.)

루프 개념:
```
캡처 → CuClient(턴1: user_input) → 액션 받음 → executor.dispatch(제스처) → 다시 캡처
     → CuClient(턴2: function_result + previous_interaction_id) → ... → 완료까지 반복
```

---

## §1. 확보한 wire 계약 (step5에서 실제 캡처)

### 턴 1 (첫 요청) — `user_input`
```json
{ "model":"gemini-3.5-flash",
  "input":[ {"type":"user_input","content":[ {"type":"text","text":"Task: 설정 앱을 열어"},
                                              {"type":"image","data":"<b64>","mime_type":"image/png"} ]} ],
  "system_instruction":"...", "tools":[{"type":"computer_use","environment":"mobile"}] }
```

### 턴 2+ (액션 실행 후) — `function_result` + `previous_interaction_id`
```json
{ "model":"gemini-3.5-flash",
  "input":[ { "type":"function_result",
              "name":"list_apps",              // 직전에 실행한 액션 이름
              "call_id":"86mtbdib",            // 직전 응답 step의 id
              "result":[ {"type":"text","text":"{\"status\": \"ok\"}"},   // 실행 결과(JSON 문자열)
                         {"type":"image","data":"<b64>","mime_type":"image/png"} ] } ],  // 실행 후 새 화면
  "system_instruction":"...", "tools":[{"type":"computer_use","environment":"mobile"}],
  "previous_interaction_id":"v1_Chc0VDF..." }   // ★ 직전 응답의 id. 서버가 앞 맥락 기억
```
**핵심 차이:**
- 첫 턴 블록은 `user_input`으로 감싸지만, **`function_result`는 감싸지 않고** input 배열에 바로 넣는다.
- `previous_interaction_id`는 **바디 최상위**(input 밖).
- `system_instruction`·`tools`는 **매 턴 계속 넣는다**.
- 한 턴에 액션이 여러 개면 `function_result`를 **여러 개**(각자 call_id + 실행 후 스냅샷) input 배열에 넣는다.

### 완료·안전
- **완료 판정:** 응답 `steps`에 `type=="function_call"`이 없음(모델이 `model_output` 텍스트만). 그때 종료.
- **safety_decision:** 액션 args에 `safety_decision`이 있으면, 그 액션의 결과 JSON에 `"safety_acknowledgement": true`를 넣어 자동 승인(데모).

---

## §2. 판단부 — `CuClient.kt` (새 파일, 기기 무관)

> step5에서 `A11Service`에 넣었던 `http`, `CU_SYSTEM`, `callGeminiOnce`, `imageBlock/userInput/functionResult`는
> **여기로 옮긴다.** (`callGeminiOnce`는 `cuCall`로 일반화되어 대체됨.)
> `app/src/main/java/com/cua/a11/CuClient.kt` 로 새 파일 생성.

```kotlin
package com.cua.a11

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 판단 코어(원본 cua). 화면(png)+목표를 Gemini Computer Use에 보내 다음 액션을 받는다.
 * AccessibilityService·제스처를 전혀 모른다 → 순수 JVM에서 테스트 가능.
 */
class CuClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // 모델 추론 대기(루프 한 턴)
        .build()

    // 원본 cua/prompt.py SYSTEM_PROMPT 그대로
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

    /** 턴1 입력: 목표 + 현재 화면 (user_input으로 감쌈) */
    fun userInput(task: String, png: ByteArray): JSONArray {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "Task: $task"))
            .put(imageBlock(png))
        return JSONArray().put(JSONObject().put("type", "user_input").put("content", content))
    }

    /** 턴2+ 입력 한 개: 액션 실행 결과(status) + 실행 후 화면. user_input으로 감싸지 않음. */
    fun functionResult(name: String, callId: String, png: ByteArray,
                       status: JSONObject, safetyAck: Boolean): JSONObject {
        // 승인은 result 텍스트(JSON) 안에 넣는다 — 공식/원본/philschmid 모두 이 형식.
        // function_result 최상위엔 safety_acknowledgement 필드가 없음(넣으면 "Unknown parameter" 400).
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
```

---

## §3. 실행부 — `A11Service`가 `Executor` 구현 (원본 adb_bridge)

> step5에서 이미 있는 `tapBlocking/swipeBlocking/setText/imeEnter/performGlobalAction/openApp/capturePngBlocking`는 그대로 재사용.
> 블록1에서 넣은 `pxX/pxY/pngSize`도 여기(실행부)에 그대로 둔다.
> 여기에 **`Executor` 계약 구현 2개**(`screenshot`, `dispatch`)만 추가한다.

### (a) 좌표 환산 + PNG 크기 — (블록1, 이미 넣음)
```kotlin
private fun pxX(norm: Int, w: Int) = (norm / 1000.0 * w).toInt()
private fun pxY(norm: Int, h: Int) = (norm / 1000.0 * h).toInt()
private fun pngSize(png: ByteArray): Pair<Int, Int> {
    fun be(o: Int) = ((png[o].toInt() and 0xFF) shl 24) or ((png[o+1].toInt() and 0xFF) shl 16) or
                     ((png[o+2].toInt() and 0xFF) shl 8) or (png[o+3].toInt() and 0xFF)
    return Pair(be(16), be(20))
}
```

### (b) Executor 구현 — 캡처 + 디스패치(내부 좌표 환산)
```kotlin
// 실행부가 마지막으로 캡처한 화면의 해상도(=모델이 본 좌표계). dispatch가 이걸로 환산.
private var lastW = 0
private var lastH = 0

/** Executor.screenshot: 캡처하며 해상도 갱신 */
override fun screenshot(): ByteArray {
    val png = capturePngBlocking()
    val (w, h) = pngSize(png); lastW = w; lastH = h
    return png
}

/**
 * Executor.dispatch: CU 액션명 → 우리 제스처. 좌표(0~1000)는 여기서 픽셀로 환산.
 * 원본 adb_bridge의 click/type/... 메서드 + main.py의 getattr 디스패치를 한 데 합친 것.
 * 반환: 추가 결과(list_apps의 apps 등) 또는 null(대부분).
 */
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
            JSONArray(packageManager.getInstalledPackages(0)
                .map { it.packageName }))   // 원본 list_apps와 동일 취지(선택: -3만 원하면 필터)
        else -> throw IllegalArgumentException("Unknown action: $name")
    }
    return null
}
```

> 클래스 선언에 `Executor` 추가: `class A11Service : AccessibilityService(), Executor {`
> (import 필요 없음 — 같은 패키지)

---

## §4. 오케스트레이터 — `runAgent()` (원본 main.py의 run())

> **top-level 함수**로 둔다(어느 클래스에도 안 속함). 원본에서 `main.py`가 `cua`·`adb_bridge` 어디에도 안 속했던 것과 동일.
> `CuClient.kt` 파일 맨 아래에 두거나 `AgentLoop.kt`로 분리해도 됨.

### (a) Executor 계약 (판단↔실행 경계)
```kotlin
package com.cua.a11

import org.json.JSONObject

/** 실행부가 지키는 계약(원본 adb_bridge의 인터페이스). 루프는 이것만 알면 됨. */
interface Executor {
    fun screenshot(): ByteArray
    fun dispatch(name: String, args: JSONObject): JSONObject?
}
```

### (b) 루프 본체
```kotlin
/**
 * 목표를 완료까지 자율 실행. 폰 혼자 판단(CuClient)+조작(Executor)을 반복.
 * 반드시 백그라운드 스레드에서 호출(네트워크+제스처 latch). 소켓 서버 스레드는 이미 백그라운드.
 * 좌표는 전혀 만지지 않는다 — 환산은 executor.dispatch 내부에서(원본 main.py와 동일).
 */
fun runAgent(exec: Executor, cu: CuClient, task: String, maxTurns: Int = 20): String {
    var png = exec.screenshot()
    var resp = cu.cuCall(cu.userInput(task, png), null)   // 턴1: user_input, prevId 없음
    var prevId = resp.optString("id")

    for (turn in 1..maxTurns) {
        val calls = cu.functionCalls(resp)
        if (calls.isEmpty()) {                            // 완료
            val fin = cu.finalText(resp)
            android.util.Log.i("a11cu", "[완료] $fin")
            return "DONE turn=$turn : $fin"
        }
        val results = org.json.JSONArray()
        for (c in calls) {
            val name = c.optString("name")
            val callId = c.optString("id")
            val args = c.optJSONObject("arguments") ?: JSONObject()
            android.util.Log.i("a11cu", "[턴 $turn] $name $args")
            val status = JSONObject().put("status", "ok")
            try {                                         // 원본 main.py의 try/except (에러도 모델에 피드백)
                val extra = exec.dispatch(name, args)
                if (extra != null) for (k in extra.keys()) status.put(k, extra.get(k))
            } catch (e: Exception) {
                status.put("status", "error").put("error", e.message ?: "")
                android.util.Log.e("a11cu", "dispatch 실패 $name: ${e.message}")
            }
            val safetyAck = args.has("safety_decision")   // 확인 요구 시 자동 승인(데모)
            Thread.sleep(600)                             // 화면 안정(원본 SETTLE_SEC=0.6)
            png = exec.screenshot()                       // 실행 후 새 화면
            results.put(cu.functionResult(name, callId, png, status, safetyAck))
        }
        resp = cu.cuCall(results, prevId)                 // 턴 n+1: function_result + prevId
        prevId = resp.optString("id")
    }
    android.util.Log.i("a11cu", "[중단] 최대 턴 도달")
    return "STOP: max turns"
}
```

---

## §5. 트리거 — 소켓 `when()`에 `RUN` 추가

> `A11Service`에 `CuClient` 인스턴스 하나 필요:
> `private val cu by lazy { CuClient(BuildConfig.GEMINI_API_KEY) }`

```kotlin
"RUN" -> {
    val task = if (p.size > 1) line.trim().substringAfter(" ") else "설정 앱을 열어"
    val result = runAgent(this, cu, task)          // this = A11Service = Executor
    val out = (result + "\n").toByteArray()
    client.getOutputStream().apply { write(out); flush() }
}
```
> 이 소켓 트리거는 **테스트 편의용**(PC가 "시작"만 눌러줌). 판단·조작은 100% 폰.
> 완전 무(無)PC로 가려면 §7 참고.

---

## §6. 실행·검증
1. **Run ▶** 재설치 → 설정>접근성 다시 켜기.
2. 폰·PC 같은 Wi-Fi. PC에서(루프가 수 턴 도니 타임아웃 넉넉히):
   ```powershell
   $ip="192.168.0.51"
   py -c "import socket; s=socket.socket(); s.settimeout(180); s.connect(($ip,8080)); s.sendall('RUN 설정 앱을 열어\n'.encode()); print(s.recv(4000).decode()); s.close()"
   ```
3. **기대:** 폰 화면이 실제로 바뀌며 진행 → 최종 `DONE turn=N : ...` 반환.
   - logcat `a11cu`: `[턴 1] ...` → `[턴 2] ...` → `[완료] ...` 흐름.
4. 되면 → **PC 없이 폰이 목표를 자율 수행**. ①번(폰 단독) **완성**.

---

## §7. 함정·주의
- **좌표 정규화**: 전부 0~1000. `dispatch` 내부 `pxX/pxY`로 환산(루프에선 안 만짐). 안 하면 좌상단만 눌림.
- **해상도 시점**: `dispatch`는 `lastW/lastH`(직전 `screenshot`에서 갱신)를 쓴다 = 모델이 본 화면 기준. 루프가 항상 dispatch 전에 화면을 캡처해 cuCall 하므로 시점이 맞다.
- **네트워크/latch는 백그라운드에서**: `runAgent`를 메인 스레드에서 부르면 `NetworkOnMainThreadException` + 제스처 콜백(mainExecutor) 데드락. 소켓 스레드(백그라운드)에서 호출 유지.
- **한 턴 여러 액션**: `calls` 순회하며 각자 실행+스냅샷+functionResult를 만들어 한꺼번에 되돌려준다(위 코드가 이미 그렇게 함).
- **list_apps**: 위 구현은 전체 패키지를 넘긴다(원본 취지). 너무 많으면 `-3`(서드파티)만: `getInstalledPackages(0).filter{ (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM)==0 }`.
- **완료 판정**은 function_call 유무로만. `finalText`가 비어도 무방.
- **무한 루프 방지**: `maxTurns`(기본 20). 모델이 헤매면 여기서 멈춤.
- **키/타임아웃/스레드**는 step5와 동일 조건.

---

## §8. 진짜 PC 제거 — 다음 마무리(선택)
루프가 검증되면 소켓 서버·PC 자체가 불필요:
- **트리거를 온디바이스로**: 앱에 작은 액티비티(EditText로 목표 입력 + "실행" 버튼) → 버튼에서 `thread { runAgent(this, cu, task) }`. 또는 서비스 시작 시 고정 목표 자동 실행.
- 그 후 `startServer()`(ServerSocket 8080)·`when()`의 SHOT/TAP/RUN 등 **소켓 코드 전부 삭제 가능** → 폰 앱 단독.
- 그러면 저장소의 `live/`·`cua/`(PC 파이썬)는 **레퍼런스/베이스라인**으로만 남고, 실제 동작은 `android/` 앱이 전담. ①번 완전체.

---

## 파일 요약 (분리 구조)
```
CuClient.kt   (판단)  ← 새로: cuCall/userInput/functionResult/imageBlock/functionCalls/isDone/finalText + Executor 인터페이스 + runAgent
A11Service    (실행)  ← 추가: Executor 구현(screenshot, dispatch), pxX/pxY/pngSize(블록1), cu 필드, 소켓 RUN
```
step5의 `callGeminiOnce`는 `cuCall`로 흡수되어 **삭제**(원하면 참고용 주석). `http`/`CU_SYSTEM`은 `CuClient`로 이동.
