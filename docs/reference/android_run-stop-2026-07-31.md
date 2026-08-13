# 실행 중단(Stop) 버튼 — 돌고 있는 CU 루프를 사용자가 멈추기

> 실행 중 **중단** 버튼을 눌러 자율 루프를 멈춘다.
> 방식 = **협조적 취소(cooperative cancellation)**: 루프가 매 턴 "취소됐나?" 플래그를 보고,
> 버튼은 그 플래그만 세운다. **진행 중인 API 호출 1개는 끝난 뒤** 멈춘다(네트워크는 중간에 못 끊음).
> 즉 누르면 대개 몇 초 안에(현재 턴 마무리 후) 정지. 이게 이 크기 프로젝트의 표준이고 충분.

---

## 0. 설계

- **취소 플래그는 `a11service`가 소유**(`@Volatile var cancelled`). 루프(`runAgent`)는 device-agnostic이라
  플래그를 직접 안 보고, **`cancel: () -> Boolean` 람다**를 받아서 매 턴 물어본다(판단↔실행 경계 유지).
- **확인 지점 2곳**: ① 각 턴 시작, ② 한 턴 안에서 액션 실행 직전. 여기서 취소면 즉시 `return`.
  네트워크(`cuCall`, 최대 60s)·`Thread.sleep(600)`은 중간에 못 끊으므로, 그게 끝나 루프가 다음
  확인 지점에 도달하면 멈춘다.
- **버튼 상태**: 평소 `실행`만 활성. 실행 시작하면 `중단` 활성/`실행` 비활성, 끝나면 반대로.
- **결과 문자열** `중단: 사용자 중단` → 알림/오버레이에 `⏹ 중단됨`으로 표시.

---

## 1. `CuClient.kt` — `runAgent`에 취소 확인 추가

`runAgent` 시그니처에 `cancel` 파라미터 추가하고, 루프 안 2곳에서 확인.

### 1-a. 시그니처 (기존 줄 교체)
```kotlin
fun runAgent(exec: Executor, cu: CuClient, task: String, maxTurns: Int = 20,
             log: (String) -> Unit = {}, cancel: () -> Boolean = { false }): String {
```

### 1-b. 루프 안 확인 2곳 (아래 굵은 줄 2개만 추가)

기존 for 루프를 이렇게 만든다(추가 줄에 `// ← 추가` 표시):
```kotlin
    for (turn in 1..maxTurns) {
        if (cancel()) { emit("[중단] 사용자 중단"); return "중단: 사용자 중단" }   // ← 추가 ①
        val calls = cu.functionCalls(resp)
        if (calls.isEmpty()) {
            val fin = cu.finalText(resp)
            emit("[완료] $fin")
            return "Done turn=$turn : $fin"
        }
        val results = JSONArray()
        for (c in calls) {
            if (cancel()) { emit("[중단] 사용자 중단"); return "중단: 사용자 중단" }   // ← 추가 ②
            val name = c.optString("name")
            val callId = c.optString("id")
            val args = c.optJSONObject("arguments") ?: JSONObject()
            emit("[턴 $turn] $name {${fmtArgs(args)}}")
            val status = JSONObject().put("status", "ok")
            try {
                val extra = exec.dispatch(name, args)
                if (extra != null) for (k in extra.keys()) status.put(k, extra.get(k))
            } catch (e: Exception) {
                status.put("status", "error").put("error", e.message ?: "")
                emit("⚠ dispatch실패 $name: ${e.message}")
            }
            val safetyAck = args.has("safety_decision")
            Thread.sleep(600)
            png = exec.screenshot()
            results.put(cu.functionResult(name, callId, png, status, safetyAck))
        }
        resp = cu.cuCall(results, prevId)
        prevId = resp.optString("id")
    }
```
> 나머지(첫 screenshot/cuCall, `emit`/`fmtArgs`)는 그대로. **추가는 `// ← 추가` 두 줄뿐.**

---

## 2. `a11service.kt` — 취소 플래그 + 요청 메서드

### 2-a. 필드 (다른 필드 근처, 클래스 안)
```kotlin
    @Volatile private var cancelled = false
```

### 2-b. 취소 요청 메서드 (runTask 근처에 추가)
```kotlin
    /** MainActivity의 '중단' 버튼이 부름. 다음 확인 지점에서 루프가 멈춘다. */
    fun requestCancel() { cancelled = true }
```

### 2-c. `runTask` 교체 — 시작 시 플래그 리셋 + cancel 람다 전달
```kotlin
    fun runTask(task: String, log: (String) -> Unit = {}): String {
        cancelled = false          // ← 이번 실행 시작: 취소 플래그 초기화
        showOverlay(task)
        val r = try {
            runAgent(this, cu, task,
                log = { line -> log(line); postOverlay(line) },
                cancel = { cancelled })          // ← 루프가 매 턴 이걸 물어봄
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
        postOverlay(r)
        notifyDone(task, r)
        ui.postDelayed({ hideOverlay() }, 4000)
        return r
    }
```

### 2-d. `notifyDone` 제목에 중단 케이스 추가 (when에 한 줄)
```kotlin
        val title = when {
            r.startsWith("중단") -> "⏹ 중단됨"        // ← 추가
            r.startsWith("STOP") -> "⚠ 최대 턴 도달"
            r.startsWith("오류")  -> "❌ 실행 오류"
            else                  -> "✅ 실행 완료"
        }
```

---

## 3. `activity_main.xml` — 중단 버튼 추가

기존 단독 `실행` 버튼(runBtn)을 **실행/중단 가로 2칸**으로 교체.
아래 `<Button android:id="@+id/runBtn" … />` 블록을 통째로 이 LinearLayout으로 바꾼다:
```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="16dp">

        <Button
            android:id="@+id/runBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="3"
            android:text="실행"/>

        <Button
            android:id="@+id/stopBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="8dp"
            android:enabled="false"
            android:text="중단"/>
    </LinearLayout>
```
> `android:enabled="false"` = 평소엔 비활성(실행 중에만 켜짐).

---

## 4. `MainActivity.kt` — 버튼 연결

### 4-a. findViewById에 stopBtn 추가 (runBtn 찾는 줄 근처)
```kotlin
        val stopBtn = findViewById<Button>(R.id.stopBtn)
```

### 4-b. 중단 버튼 클릭 (histBtn/clearBtn 리스너 근처에 추가)
```kotlin
        stopBtn.setOnClickListener {
            a11service.instance?.requestCancel()
            result.text = "중단 요청됨… 현재 단계가 끝나면 멈춥니다."
            stopBtn.isEnabled = false
        }
```

### 4-c. runBtn 실행 시 버튼 토글 (기존 runBtn 리스너 안 수정)

시작할 때 `실행`은 끄고 `중단`은 켜고, 끝나면 반대로. 아래 2곳에 한 줄씩:
```kotlin
            runBtn.isEnabled = false
            stopBtn.isEnabled = true          // ← 추가(실행 시작)
            logView.text = ""
            result.text = "실행 중… ($task)"
            thread {
                // … 기존 그대로 …
                runOnUiThread {
                    result.text = r
                    runBtn.isEnabled = true
                    stopBtn.isEnabled = false  // ← 추가(실행 끝)
                }
            }
```

---

## 5. 테스트

1. Build ▶ 설치 → `설정 앱을 열고 와이파이 설정으로 들어가` 같은 **여러 턴짜리** 목표 실행.
2. 루프가 도는 중 **중단** 누름 → `중단 요청됨…` 뜨고, **현재 턴이 끝나면**
   로그에 `[중단] 사용자 중단`, 결과에 `중단: 사용자 중단`, 상태바에 `⏹ 중단됨` 알림.
3. `실행` 다시 활성화됨. 새 목표로 재실행 가능(플래그는 runTask 시작 때 리셋되니 바로 됨).

---

## 6. 함정
| 증상 | 원인/해결 |
|---|---|
| 눌러도 즉시 안 멈춤 | 정상. 진행 중 API 호출/`sleep(600)`은 못 끊음 → 다음 확인 지점(현재 턴 끝)에서 멈춤 |
| 다음 실행이 바로 멈춤 | `runTask` 시작의 `cancelled = false` 리셋 누락 → 2-c 확인 |
| 중단 버튼이 계속 활성/비활성 | 4-c의 토글 두 줄 위치 확인(시작 true / 끝 false) |
| 중단인데 알림 제목이 ✅완료 | 2-d의 `startsWith("중단")` 케이스를 when **맨 위쪽**에 넣었는지 확인 |
| `requestCancel` 못 찾음(빨간 줄) | `a11service.instance`가 null(접근성 OFF) → 실행 중이면 non-null. 버튼은 실행 중에만 활성이라 정상 |

> (선택) 정말 즉시 끊고 싶으면 OkHttp `Call`을 취소해야 하는데(진행 중 요청 abort),
> 지금 구조는 턴 경계 취소로 충분. 필요해지면 그때 `cu`에 현재 call 참조를 들고 `cancel()` 호출하는 방식으로 확장.
