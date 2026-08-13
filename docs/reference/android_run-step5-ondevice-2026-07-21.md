# 5단계 — 폰 단독(온디바이스) CU 호출: PC 떼기 시작 (①번 방향)

> 코드는 **네가 직접** 타이핑한다([[user-writes-code-himself]]).
> 앞 단계: step4(제스처)까지 = ②하이브리드 완성(폰=눈+손, PC=두뇌). 이번부터 **두뇌도 폰으로**.

---

## §0. 큰 그림 — 왜/무엇을

지금(②)은 **PC 파이썬 `cua`가 Gemini를 호출**하고, 폰은 소켓으로 화면·제스처만 담당한다.
①번 = **폰 앱이 스스로 Gemini CU를 호출** → PC·케이블·소켓 전부 불필요.

바꿀 딱 한 조각: **"스크린샷 → Gemini에 물어봄 → 액션 받기"** 를 파이썬이 아니라 **Kotlin이** 한다.
`cua/cu_client.py`가 하던 걸 폰이 REST로 직접 때린다.

**이번 5단계는 최소 증명만** 한다(가장 큰 리스크 = "폰이 CU를 호출할 수 있나?" 검증):
> 폰이 화면 1장 캡처 → Gemini `interactions` REST 1회 호출 → **돌아온 액션(name+args)을 로그로** 확인.
> **제스처 실행·멀티턴 루프는 다음(6단계).** 이번엔 호출이 되는지만 본다.

트리거는 기존 소켓서버에 `CU` 명령 하나 추가해서 편하게 쏜다(PC는 "찔러주기"만; 실제 Gemini 호출은 폰이 함 = 온디바이스 증명). 6단계에서 그 찔러주기도 없앤다.

---

## §1. 확보한 정확한 REST 계약 (작동하는 파이썬 SDK가 실제로 보낸 것)

폰은 이 형태를 **그대로** 복제하면 된다.

### 요청 (생성 = 첫 턴)
```
POST https://generativelanguage.googleapis.com/v1beta/interactions
Headers:
  Content-Type: application/json
  x-goog-api-key: <GEMINI_API_KEY>          ← 이 헤더 하나로 인증 끝(OAuth 불필요)
Body(JSON):
{
  "model": "gemini-3.5-flash",
  "input": [
    { "type": "user_input",
      "content": [
        { "type": "text",  "text": "Task: 설정 앱을 열어" },
        { "type": "image", "data": "<base64 PNG>", "mime_type": "image/png" }
      ] }
  ],
  "system_instruction": "You are operating an Android phone.\n* Use the provided tools ...",
  "tools": [ { "type": "computer_use", "environment": "mobile" } ]
}
```
> `input`은 블록들을 `{"type":"user_input","content":[...]}`로 한 번 감싼다(파이썬은 평면 리스트를 넘겼지만 SDK가 이렇게 직렬화했다). 폰에선 처음부터 이 감싼 형태로 만든다.

### 응답
```json
{
  "id": "v1_ChcxQlZl...",              // ← 다음 턴 previous_interaction_id 로 씀(6단계)
  "status": "requires_action",          // 액션 있음. 완료면 "completed"
  "steps": [
    { "id": "u7zkpda9",                 // ← call_id (다음 턴 function_result에 필요)
      "type": "function_call",
      "name": "list_apps",              // ← CU 액션명(=ADBBridge/제스처 메서드명)
      "arguments": { "intent": "..." }  // click이면 x,y(0~1000) 등이 여기 들어옴
    }
  ],
  "model": "gemini-3.5-flash",
  "usage": { ... }
}
```
**완료 판정:** `steps`에 `type=="function_call"`이 하나도 없음(= 모델이 `model_output` 텍스트만 냄) 또는 `status=="completed"`.

### 시스템 프롬프트 원문 (그대로 넣기)
```
You are operating an Android phone.
* Use the provided tools to complete the task.
* Scroll down to inspect the full screen before assuming an element is missing.
* You can open apps by package name from anywhere.
* Type text only using the `type` tool. Do not use the virtual keyboard.
* If the task is already complete, state that directly.
```

---

## §2. 폰 프로젝트 준비 (Android_run)

### (1) INTERNET 권한 — 이미 있음(2단계에서 추가). 확인만.
`AndroidManifest.xml`에 `<uses-permission android:name="android.permission.INTERNET"/>` 있는지.

### (2) OkHttp 의존성 추가 — `build.gradle.kts (Module :app)`의 `dependencies { }` 안
```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```
> JSON은 안드로이드 내장 `org.json`(JSONObject/JSONArray) 쓰면 되니 별도 의존성 불필요.
> 추가 후 **Sync Now**.

### (3) API 키를 코드/깃에 넣지 않기 — `local.properties`(이미 gitignore) → BuildConfig 로 주입

**A. `local.properties`** (프로젝트 루트, 깃에 안 올라감) 맨 아래에 한 줄:
```
GEMINI_API_KEY=여기에_본인_키
```

**B. `build.gradle.kts (Module :app)`** — `android { }` 안에서 키를 읽어 BuildConfig 필드로:
```kotlin
import java.util.Properties
import java.io.FileInputStream

android {
    // ...(기존 compileSdk 등)...
    buildFeatures {
        buildConfig = true            // BuildConfig 생성 켜기(필수)
    }
    defaultConfig {
        // ...(기존 minSdk 30, compileSdk 37 등)...
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) props.load(FileInputStream(f))
        val key = props.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$key\"")
    }
}
```
> 이러면 코드에서 `BuildConfig.GEMINI_API_KEY`로 키를 읽는다. **키는 local.properties에만** 있고 소스·깃엔 없음.
> ⚠️ 단, 키는 빌드된 APK 안에 박힌다(추출 가능). **개인/개발용은 OK**, 배포용이면 뒤에서 서버 프록시로 바꿔야 함(§6 주의).

---

## §3. Kotlin — Gemini CU 1회 호출 함수 (a11service.kt에 추가)

### import 추가
```kotlin
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
```

### 클래스 필드(서버 시작부 근처)
```kotlin
// 이미지 업로드가 크므로 타임아웃 넉넉히
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
```

### 핵심 함수 — 캡처된 PNG를 주면 Gemini에 물어 액션 문자열을 돌려준다(블로킹)
```kotlin
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
```

### 소켓 when()에 `CU` 명령 추가 (기존 SHOT/TAP/... 옆에)
```kotlin
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
```
> ⚠️ 이 응답은 OK/PNG와 형식이 다르다(그냥 텍스트 줄). 5단계 테스트 전용. 6단계에서 정식 루프로 대체.

---

## §4. 실행·검증

1. 폰 앱 **Run ▶** 재설치 → 설정>접근성에서 다시 켜기(재설치하면 꺼짐).
2. 폰·PC 같은 Wi-Fi, `PHONE_IP` 확인.
3. PC에서 한 줄로 `CU` 명령 쏘기(테스트):
   ```powershell
   $ip="192.168.0.51"
   py -c "import socket; s=socket.socket(); s.connect(($ip,8080)); s.sendall('CU 설정 앱을 열어\n'.encode()); print(s.recv(1000).decode()); s.close()"
   ```
4. **기대 출력** (폰이 스스로 Gemini를 부른 결과):
   ```
   id=v1_... status=requires_action action=list_apps args={"intent":"..."}
   ```
   또는 `action=open_app args={"package_name":"com.android.settings", ...}` 등.
5. 폰 **logcat**(`a11cu` 태그)에도 같은 줄이 찍히면 성공.

이게 뜨면 → **폰이 PC 없이 Gemini CU를 직접 호출**하는 게 증명됨(①번의 핵심 관문 통과).

---

## §5. 다음(6단계 예고) — 진짜 폰 단독 루프

5단계가 되면, 6단계에서 파이썬 `main.py` 루프를 폰 안으로 옮긴다:
- 받은 액션을 **바로 dispatchGesture로 실행**(step4 헬퍼 재사용) → 다시 캡처 →
  `function_result` + `previous_interaction_id`로 다음 턴 → 완료까지 반복.
- **function_result 턴 형태**(파이썬 `function_result()` 기준, 6단계에서 캡처로 재확인):
  ```json
  { "type":"function_result", "name":"click", "call_id":"u7zk...",
    "result":[ {"type":"text","text":"{\"status\":\"ok\"}"},
               {"type":"image","data":"<b64>","mime_type":"image/png"} ] }
  ```
  이걸 `input`의 `user_input` 대신 넣고 `previous_interaction_id`를 body에 추가.
- `safety_decision`(require_confirmation) 오면 `safety_acknowledgement:true` 붙여 자동 승인(데모).
- 완료되면 소켓서버·PC 완전히 불필요 → 폰 앱만으로 동작.

---

## §6. 함정·주의
- **네트워크는 절대 메인 스레드에서 X.** 소켓서버 스레드(백그라운드)에서 부르면 됨. 메인에서 부르면 `NetworkOnMainThreadException`.
- **BuildConfig.GEMINI_API_KEY가 빈 문자열이면** → `local.properties`에 키 없음/`buildConfig=true` 누락/Sync 안 함. HTTP 401/403로 나타남.
- **키는 APK에 박힌다** → 개인용만. 배포하려면 서버 프록시(폰→내 서버→Gemini)로 키 숨기기(별도 과제).
- 이미지가 커서(수 MB base64) `readTimeout` 짧으면 끊김 → 60초 권장.
- `substringAfter(" ")`는 명령에 공백 없으면 원문 전체 반환 → 위 코드처럼 `p.size>1` 가드.
- Gemini가 **한글 목표**도 이해함(캡처 테스트에서 "설정 앱을 열어"로 정상 응답). 인코딩은 org.json이 UTF-8로 처리.
