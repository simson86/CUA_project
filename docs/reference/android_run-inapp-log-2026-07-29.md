# 앱 안에 실시간 로그 띄우기 (매 명령마다)

> §8 온디바이스 UI 확장.
> logcat에만 찍히던 `[턴 N] name {args}` 를 **앱 화면에도** 실시간 표시.

---

## 0. 설계 (왜 콜백)

`runAgent`는 백그라운드 스레드에서 도는 **오케스트레이터**(원본 main.py run()). UI를 직접 만지면 안 됨
(UI는 메인스레드 전용). 그래서 **"로그 한 줄 생겼다"를 콜백으로 밖에 알리고**, UI 갱신은 Activity가
`runOnUiThread`로 한다.

- `runAgent(..., log: (String)->Unit = {})` — 기본값 no-op → **소켓 RUN 경로는 변경 없이 그대로 동작**.
- 매 턴 `Log.i` 하던 자리에서 `log(...)`도 같이 호출 → logcat + 앱 둘 다 남음.
- Activity는 콜백에서 `runOnUiThread { logView에 append + 맨아래로 스크롤 }`.

이게 원본에서 "실행부/판단부는 그대로 두고, 관찰(로그)만 밖으로 뺀" 형태 — 의존방향 안 깨짐.

---

## 1. `CuClient.kt` — runAgent에 로그 콜백 추가 (한 파일만 수정)

### 1-a. 시그니처에 `log` 파라미터 추가

```kotlin
fun runAgent(exec: Executor, cu: CuClient, task: String,
             maxTurns: Int = 20, log: (String) -> Unit = {}): String {
```
> 기존 `maxTurns: Int = 20):String{` 을 위처럼 바꾸면 됨.

### 1-b. 함수 맨 첫 줄에 emit 헬퍼 정의

`var png = exec.screenshot()` **바로 위**에:

```kotlin
    fun emit(s: String) { android.util.Log.i("a11cu", s); log(s) }   // logcat + 앱 동시
```

### 1-c. 기존 `android.util.Log.i/e("a11cu", …)` 4곳을 emit으로 교체

```kotlin
    // [완료] 분기
    val fin = cu.finalText(resp)
    emit("[완료] $fin")                                   // was Log.i
    return "Done turn=$turn : $fin"
```
```kotlin
    // 매 턴 액션 로그
    emit("[턴 $turn] $name {${fmtArgs(args)}}")           // was Log.i
```
```kotlin
    // dispatch 실패
    }catch(e: Exception){
        status.put("status","error").put("error", e.message ?:"")
        emit("⚠ dispatch실패 $name: ${e.message}")        // was Log.e
    }
```
```kotlin
    // 루프 끝(최대 턴)
    emit("[중단] 최대 턴 도달")                            // was Log.i
    return "STOP: max turns"
```

> 딱 이 4줄만 `emit(...)`으로. 나머지 로직은 손 안 댐.

---

## 2. `a11service.kt` — runTask가 콜백을 넘기게 (한 줄)

```kotlin
    fun runTask(task: String, log: (String) -> Unit = {}): String =
        runAgent(this, cu, task, log = log)
```
> 기존 `fun runTask(task:String) : String = runAgent(this,cu,task)` 를 위로 교체.
> 소켓 RUN 분기(`runAgent(this, cu, task)`)는 그대로 둬도 됨 — log 기본 no-op.

---

## 3. `MainActivity.kt` — 로그를 화면에 append

### 3-a. import 추가
```kotlin
import android.widget.ScrollView
```

### 3-b. onClick 안, findViewById에 두 개 추가
```kotlin
        val logView = findViewById<TextView>(R.id.logView)
        val logScroll = findViewById<ScrollView>(R.id.logScroll)
```
> 이건 `runBtn.setOnClickListener {` **바깥**(onCreate 위쪽, 다른 findViewById 옆)에 두는 게 깔끔.

### 3-c. 실행 시작할 때 로그 비우고, thread 안에서 콜백 연결

기존 실행 블록을 이렇게:
```kotlin
            runBtn.isEnabled = false
            logView.text = ""                     // 새 실행마다 로그 초기화
            result.text = "실행 중… ($task)"
            thread {
                val r = try {
                    svc.runTask(task) { line ->    // ← 매 턴 콜백
                        runOnUiThread {
                            logView.append(line + "\n")
                            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }  // 맨 아래로
                        }
                    }
                } catch (e: Exception) {
                    "오류: ${e.message}"
                }
                runOnUiThread {
                    result.text = r
                    runBtn.isEnabled = true
                }
            }
```

### 3-d. import 하나 더 (fullScroll의 View)
```kotlin
import android.view.View
```

---

## 4. `activity_main.xml` — 로그 영역 추가

기존 `resultView` TextView **아래**에 붙이기(같은 LinearLayout 안):

```xml
    <ScrollView
        android:id="@+id/logScroll"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="16dp"
        android:background="#11000000">

        <TextView
            android:id="@+id/logView"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="8dp"
            android:fontFamily="monospace"
            android:textSize="12sp"
            android:textIsSelectable="true"
            android:text=""/>
    </ScrollView>
```

> `layout_height="0dp" + layout_weight="1"` → 로그창이 남는 세로공간을 다 차지(스크롤).
> 이러려면 루트 LinearLayout이 `android:orientation="vertical"`이어야 함(이미 그럼).

---

## 5. 결과

앱에서 실행하면 결과창 위/아래로 이런 로그가 **턴마다 실시간**으로 쌓임:
```
[턴 1] list_apps {}
[턴 2] open_app {package_name=com.android.settings}
[완료] 설정 열었습니다.
```
- logcat `a11cu`에도 그대로 남음(emit이 둘 다 함).
- dispatch 실패는 `⚠ dispatch실패 …`로 앱에서도 바로 보임.

---

## 6. 함정
| 증상 | 원인/해결 |
|---|---|
| 로그가 한 번에 몰려 뜸 | 정상 — 한 턴 안에서 캡처/네트워크 대기 후 다음 턴 콜백. 턴 단위로 갱신됨 |
| 앱 멈춤(ANR) | 콜백 안에서 `runOnUiThread` 빼먹고 바로 UI 접근 → 반드시 감쌀 것 |
| 스크롤 안 내려감 | `logScroll.post{ fullScroll }` 누락 or `View` import 누락 |
| 소켓 RUN 결과에 로그 안 나옴 | 정상 — 소켓 경로는 log 기본 no-op(원하면 거기도 콜백 연결 가능) |
