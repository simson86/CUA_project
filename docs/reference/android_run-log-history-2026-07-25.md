# 로그 저장 + 지난 로그 다시 보기

> `android_run-inapp-log-2026-07-29.md`(실시간 로그)의 후속.
> 실행이 끝나면 로그를 **파일에 저장** → 나중에 앱에서 "지난 로그 보기"로 다시 확인.

---

## 0. 설계

- 실행 중 콜백으로 들어오는 로그 줄을 **StringBuilder에도 모은다**(화면 append와 별개).
- 실행이 끝나면 그 뭉치를 **내부 저장소 파일에 append**(`filesDir/run_history.txt`).
  - `filesDir` = `/data/data/com.cua.a11/files/` — **앱 전용, 권한 불필요, 앱 재시작해도 유지**(삭제는 앱 제거 시).
- "지난 로그 보기" 버튼 → 파일 전체를 logView에 로드. "지우기" 버튼 → 파일 삭제.
- 파일 IO는 **백그라운드 스레드**에서(작지만 StrictMode 회피).

한 실행 = 파일에 이런 블록 하나가 쌓임:
```
===== 2026-07-25 14:32:10  |  설정 앱을 열어 =====
[턴 1] list_apps {}
[턴 2] open_app {package_name=com.android.settings, intent=...}
[완료] 설정 열었습니다.
Done turn=3 : 설정 열었습니다.

```

---

## 1. `activity_main.xml` — 버튼 2개 추가

`runBtn`(실행 버튼) **바로 아래**에 가로 버튼 줄 추가:

```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="8dp">

        <Button
            android:id="@+id/histBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="지난 로그 보기"/>

        <Button
            android:id="@+id/clearBtn"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="8dp"
            android:text="로그 지우기"/>
    </LinearLayout>
```

> `logScroll`/`logView`(실시간 로그 영역)는 `android_run-inapp-log-2026-07-29.md` §4대로 이미 있다고 가정.

---

## 2. `MainActivity.kt` — 저장/불러오기

### 2-a. import 추가
```kotlin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
```

### 2-b. 클래스 안에 파일 헬퍼 3개 (onCreate 밖, 메서드로)
```kotlin
    private fun logFile() = File(filesDir, "run_history.txt")

    /** 한 실행 로그를 타임스탬프+목표 헤더와 함께 파일 끝에 append. */
    private fun saveLog(task: String, body: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile().appendText("===== $ts  |  $task =====\n$body\n\n")  // appendText=UTF-8
    }

    private fun loadHistory(): String {
        val f = logFile()
        return if (f.exists()) f.readText() else "(저장된 로그 없음)"
    }
```

### 2-c. onCreate 안 — 두 버튼 연결
`runBtn.setOnClickListener {` **위나 아래**(같은 onCreate 안)에:
```kotlin
        val histBtn  = findViewById<Button>(R.id.histBtn)
        val clearBtn = findViewById<Button>(R.id.clearBtn)

        histBtn.setOnClickListener {
            logView.text = loadHistory()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        clearBtn.setOnClickListener {
            logFile().delete()
            logView.text = "(로그 지움)"
        }
```

### 2-d. 실행 블록 — 로그를 모아서 끝나면 저장

`android_run-inapp-log-2026-07-29.md` §3-c의 thread 블록을 이렇게 교체:
```kotlin
            runBtn.isEnabled = false
            logView.text = ""
            result.text = "실행 중… ($task)"
            thread {
                val runLog = StringBuilder()               // ← 이번 실행 로그 누적
                val r = try {
                    svc.runTask(task) { line ->
                        runLog.append(line).append("\n")   // 파일용(백그라운드)
                        runOnUiThread {                    // 화면용
                            logView.append(line + "\n")
                            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                } catch (e: Exception) {
                    "오류: ${e.message}"
                }
                runLog.append(r).append("\n")
                saveLog(task, runLog.toString())           // ← 끝나면 파일 저장(백그라운드)
                runOnUiThread {
                    result.text = r
                    runBtn.isEnabled = true
                }
            }
```

> `View`/`ScrollView`/`thread` import는 inapp-log 단계에서 이미 추가됨.

---

## 3. 사용

1. `설정 앱을 열어` 실행 → 실시간 로그 뜨고, 끝나면 자동 저장.
2. 다른 작업 실행(실시간 로그는 초기화됨).
3. **"지난 로그 보기"** → 지금까지 실행들이 시간순으로 다 나옴(스크롤).
4. 너무 쌓이면 **"로그 지우기"**.

### (선택) PC로 로그 파일 꺼내기
```powershell
adb exec-out run-as com.cua.a11 cat files/run_history.txt > history.txt
```
> `run-as`는 디버그(디버거블) 빌드에서만 됨. 평소엔 앱 안에서 보면 충분.

---

## 4. 함정
| 증상 | 원인/해결 |
|---|---|
| 앱 껐다 켜니 실시간 로그창 비어있음 | 정상 — 실시간창은 휘발. **"지난 로그 보기"**로 파일에서 복원 |
| 로그 파일이 계속 커짐 | 개인용이라 보통 무해. 신경 쓰이면 "로그 지우기" 또는 saveLog에서 오래된 것 자르기 |
| 한글 깨짐 | `appendText`/`readText`는 기본 UTF-8이라 정상 |
| 저장 안 됨 | 실행이 예외로 죽으면 catch의 `r`도 저장됨(오류도 기록). 그래도 없으면 filesDir 경로/권한 확인 |
| 화면 회전하면 실시간 로그 사라짐 | Activity 재생성 때문 — 저장 파일엔 남아있음(지난 로그로 확인). 필요하면 회전잠금 |
