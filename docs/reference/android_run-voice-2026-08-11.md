# 음성으로 목표 말하기 (`android/`)

목표 입력칸 옆에 **마이크 버튼**을 붙인다. 말하면 인식된 문장이 입력칸에 들어가고,
**3초 카운트다운 뒤 자동 실행**된다. 그 사이 `취소`를 누르면 멈춘다.

**기존 텍스트 경로는 한 줄도 안 없앤다.** 입력칸도 실행 버튼도 그대로다 — 음성은 입력칸을
채우는 또 하나의 방법일 뿐이다.

---

## §0 전체 그림

인식 경로가 **둘**이고, 같은 마이크 버튼에 짧게/길게로 나뉜다.

```
🎤 짧게 누름 ─→ 경로 B · SpeechRecognizer (인앱, 실시간 자막)
🎤 길게 누름 ─→ 경로 A · RecognizerIntent  (구글 다이얼로그, 권한 불필요)
                        ↓ 인식 문자열 (acceptHeard)
              taskInput 에 채움 ──→ 3초 카운트다운 ──→ startRun(task)
                                        ↑                   ↑
                                    [취소] 누르면 중단    [실행] 버튼도 여기로
```

**두 경로는 같은 인식 엔진을 쓴다 — 인식률은 동일하다**(§1). 나뉘는 건 UI와 권한뿐이라,
B 를 주력으로 두고 A 를 대비책으로 남긴다. B 가 막히는 경우가 실제로 있다:
매니페스트 `<queries>` 누락, `RECORD_AUDIO` 거부, 인식 엔진 미설치. 그때 코드가
자동으로 A 로 흘러간다.

핵심 변경:

| # | 무엇 | 파일 |
|---|---|---|
| 1 | 실행 로직을 `startRun(task)` 로 뽑아낸다 ★ | `MainActivity.kt` |
| 2 | 마이크 버튼 + 취소 버튼 + 아이콘 리소스 | `activity_main.xml`, `res/drawable/ic_mic.xml`, `res/color/mic_tint.xml` |
| 3 | 카운트다운 타이머 | `MainActivity.kt` |
| 4 | 경로 A — `RecognizerIntent` 호출·결과 수신 | `MainActivity.kt` |
| 5 | 경로 B — `SpeechRecognizer` 래퍼 | `VoiceInput.kt` (신규) |
| 6 | `RECORD_AUDIO` 권한 + `RecognitionService` `<queries>` | `AndroidManifest.xml` |

**기존 텍스트 경로는 그대로다.** `runBtn` 도 `taskInput` 도 안 없앴다.

---

## §1 두 경로 — 인식률 이야기

안드로이드에서 음성인식을 부르는 길은 둘이고, **둘 다 구현했다.**

| | 경로 A `RecognizerIntent` (길게) | 경로 B `SpeechRecognizer` (짧게) |
|---|---|---|
| 인식 엔진 | **폰에 설정된 기본 음성인식 서비스** | **똑같은 서비스** |
| **인식률** | **동일** | **동일** |
| UI | 구글이 그린 다이얼로그 | 우리가 그림 — **실시간 자막** |
| `RECORD_AUDIO` 권한 | 불필요 (구글 앱이 마이크를 씀) | **필요** (런타임 요청) |
| `<queries>` 선언 | 불필요 (`try/catch` 로 처리) | **필수** — 없으면 엔진이 있어도 안 보임 |
| 생명주기 | 없음 | `destroy()` 안 부르면 바인딩이 샘 |
| 코드량 | ~30줄 | ~150줄 (콜백 10개 + 에러코드) |

★ **둘은 같은 엔진의 두 껍데기다.** 인식률로 고를 이유가 없다 — 실제로 갈리는 건
"말하는 중 자막이 보이나"와 "권한을 받아야 하나"뿐이다.

그래서 이 앱은 **B 를 기본, A 를 대비책**으로 둔다. B 가 못 뜨는 세 경우
(`<queries>` 누락 / 권한 거부 / 엔진 없음) 마다 안내문이 "길게 누르면 구글 음성
다이얼로그" 를 알려 주고, `isAvailable()` 이 false 면 코드가 알아서 A 를 띄운다.

### 빅스비와 비교하면

- **일반 한국어 받아쓰기는 대등하다.** 빅스비도 삼성 ASR을 쓸 뿐이고, 구글 한국어
  인식률이 그에 준한다.
- **빅스비가 이기는 지점은 고유명사다.** 빅스비는 내 연락처·설치된 앱 목록을 인식
  언어모델에 먹인다. 범용 ASR엔 그 문맥이 없어 사람 이름·낯선 앱 이름에서 더 틀린다.
- **그런데 우리는 그 약점이 덜 아프다.** 빅스비는 인식 문자열을 규칙 기반 인텐트
  파서에 넣어 한 글자만 틀려도 "무슨 말인지 모르겠어요"가 된다. 우리는 그 문자열이
  **Gemini에게** 간다. "유투브 열어줘" 정도의 오탈자는 모델이 흡수하고, 앱 이름은
  `list_apps` 가 실제 설치 목록을 라벨과 함께 넘겨 주므로 모델이 대조해서 맞춘다.
- **오프라인 인식(`EXTRA_PREFER_OFFLINE`)은 켜지 말 것.** 정확도가 눈에 띄게 떨어진다.
  기본값(서버 인식) 그대로 둔다.

인식률을 더 밀고 싶으면 §10(오디오를 Gemini에 직접)이 유일한 길이다. **지금은 기록만
해 두고, 1번을 먼저 굴려 실제 인식률을 본 뒤에 판단한다.**

---

## §2 ★ 선행 리팩터링 — 실행 로직을 `startRun` 으로 뽑기

지금 실행 로직은 `runBtn.setOnClickListener { … }` **안에 통째로** 들어 있다
(`MainActivity.kt:74-131`). 음성 경로도 똑같은 걸 해야 하는데, 여기서 복사·붙여넣기를
하면 **같은 로직이 두 벌**이 되어 이후 한쪽만 고치는 사고가 난다. 먼저 뽑아낸다.

### 어디에 뽑나 — `onCreate` 안의 **지역 함수**

```kotlin
// onCreate 안, findViewById 들 아래에 둔다.
fun startRun(task: String) { … }
```

- **클래스 멤버 함수로 올리지 않는 이유:** 이 로직은 `input`/`result`/`logView`/
  `runBtn`… 지역변수를 잔뜩 쓴다. 멤버로 올리면 그것들을 전부 클래스 프로퍼티로
  끌어올려야 한다. 지역 함수는 바깥 지역변수를 그대로 캡처하므로 **본문을 손대지 않고
  들여쓰기만 옮기면 된다.**
- **람다(`val startRun: (String) -> Unit = { … }`)가 아니라 `fun` 인 이유:** 본문에
  `return` 이 3번 나온다(빈 입력·서비스 꺼짐·오버레이 권한 없음). 람다면 전부
  `return@라벨` 로 고쳐야 하고, 변수에 대입한 람다는 암시적 라벨이 없어 `startRun@{ … }`
  처럼 라벨을 직접 붙여야 한다. 지역 함수는 그냥 `return` 이면 된다.
- **선언 순서 주의:** Kotlin 지역 함수는 **쓰기 전에 선언**돼 있어야 한다.
  `stopCountdown` → `startRun` → `startCountdown` 순으로 두면 앞을 향한 참조가 없다.

### 옮기는 방법

`runBtn.setOnClickListener { … }` 의 **몸통을 그대로** `fun startRun(task: String) { … }`
안으로 옮긴다. 딱 두 군데만 바뀐다.

```kotlin
// (1) 첫 줄에 있던 task 추출은 뺀다 — 이제 인자로 받는다
//     val task = input.text.toString().trim()   ← 삭제

fun startRun(task: String) {
    stopCountdown()                      // ★ 카운트다운 중에 '실행'을 눌러도 두 번 안 돌게
    if (task.isEmpty()) {
        result.text = "지시사항을 입력하세요. 예 : 설정 앱을 열어줘"
        return                           // ← return@setOnClickListener 에서 바뀜
    }
    val svc = a11service.instance
    if (svc == null) {
        result.text = "접근성 서비스가 꺼져 있습니다. \n설정 > 접근성에서 'Android_run'을 켠 뒤 다시 실행하세요."
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return
    }
    if (!Settings.canDrawOverlays(this)) {
        result.text = "‘다른 앱 위에 표시’ 권한이 필요합니다.\n설정에서 켠 뒤 다시 실행하세요."
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
        return
    }
    val maxTurns = (turnsInput.text.toString().trim().toIntOrNull() ?: 20).coerceIn(1, 40)
    turnsInput.setText(maxTurns.toString())
    val model = CuClient.MODELS[modelSpinner.selectedItemPosition]
    val thinking = CuClient.THINKING[thinkSpinner.selectedItemPosition]
    prefs.edit()
        .putInt("max_turns", maxTurns)
        .putString("model", model)
        .putString("thinking", thinking)
        .apply()
    runBtn.isEnabled = false
    micBtn.isEnabled = false             // ★ 추가 — 실행 중엔 마이크도 잠근다(§7-3)
    stopBtn.isEnabled = true
    logView.text = ""
    result.text = "실행 중… ($task)"
    thread {
        val runLog = StringBuilder()
        val r = try {
            svc.runTask(task, maxTurns, model, thinking) { line ->
                runLog.append(line).append("\n")
                runOnUiThread {
                    logView.append(line + "\n")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        } catch (e: Exception) { "오류: ${e.message}" }
        runLog.append(r).append("\n")
        saveLog(task, runLog.toString())
        runOnUiThread {
            result.text = r
            runBtn.isEnabled = true
            micBtn.isEnabled = true      // ★ 추가 — 잠근 건 반드시 푼다
            stopBtn.isEnabled = false
        }
    }
}

// (2) 버튼은 껍데기만 남는다
runBtn.setOnClickListener { startRun(input.text.toString().trim()) }
```

> 이 단계까지만 하고 한 번 빌드·실행해 **텍스트 경로가 그대로 동작하는지** 확인하고
> 넘어가는 걸 권한다. 음성 코드까지 한꺼번에 넣고 안 되면 원인이 둘로 갈린다.

---

## §3 레이아웃 — `activity_main.xml`

### (1) 마이크 버튼 — `taskInput` 을 가로 줄로 감싼다

기존 `taskInput` (18-24행) 을 이걸로 **교체**:

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical">

    <EditText
        android:id="@+id/taskInput"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:hint="목표 (예: 설정 앱을 열어)"
        android:inputType="text"
        android:imeOptions="actionDone"/>

    <ImageButton
        android:id="@+id/micBtn"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_marginStart="4dp"
        android:src="@drawable/ic_mic"
        android:scaleType="centerInside"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="음성으로 목표 말하기"
        app:tint="@color/mic_tint"/>
</LinearLayout>
```

루트 `<LinearLayout>` 에 `xmlns:app="http://schemas.android.com/apk/res-auto"` 를
추가해야 `app:tint` 가 먹는다.

- `layout_width="0dp"` + `layout_weight="1"` — 입력칸이 남는 폭을 다 먹고 버튼은 제 크기.
  (`match_parent` 로 두면 버튼이 밀려나 안 보인다.)
- **`Button` + 🎤 이모지가 아니라 `ImageButton` + 벡터 아이콘인 이유:** 이모지는 폰·폰트에
  따라 모양이 제각각이고 테마 색을 안 따른다. 벡터는 해상도에 안 뭉개지고 `tint` 로
  테마 색을 따라간다.
- `48dp` — 구글 권장 최소 터치 영역. 아이콘 자체는 24dp라 `centerInside` 로 가운데 둔다.
- `selectableItemBackgroundBorderless` — 눌렀을 때 원형 리플만 퍼진다(검색창 옆 음성
  버튼과 같은 표준 모양).
- `contentDescription` — 글자가 아예 없어 TalkBack이 읽을 게 이것뿐이다.
  **접근성 서비스를 만드는 앱이 접근성 라벨을 빼먹으면 곤란하다.**

### (1-a) 아이콘 리소스 2개

`res/drawable/ic_mic.xml` — 안드로이드 표준 Material "mic" 벡터:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#FF000000"
        android:pathData="M12,14c1.66,0 3,-1.34 3,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17,11c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5L5,11c0,3.53 2.61,6.43 6,6.92L11,21h2v-3.08c3.39,-0.49 6,-3.39 6,-6.92h-2z"/>
</vector>
```

`res/color/mic_tint.xml` — **`ImageButton` 은 `Button` 과 달리 비활성 상태를 스스로
흐리게 만들지 않는다.** 실행 중 잠갔을 때(`micBtn.isEnabled = false`) 티가 나도록
직접 지정한다:

```xml
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- ★ 비활성 항목을 먼저 — selector 는 위에서부터 먼저 맞는 걸 쓴다 -->
    <item android:state_enabled="false" android:alpha="0.3" android:color="?attr/colorOnSurface"/>
    <item android:color="?attr/colorPrimary"/>
</selector>
```

`?attr/colorPrimary`·`?attr/colorOnSurface` 는 앱 테마가 `Theme.Material3.DayNight`
계열이라 쓸 수 있다(`values/themes.xml`). 다크 모드에도 자동으로 맞는다.

### (2) 취소 버튼 — 실행/중단 줄 **바로 아래**에 추가

```xml
<Button
    android:id="@+id/cancelBtn"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="8dp"
    android:visibility="gone"
    android:text="취소 (자동 실행 멈춤)"/>
```

- `visibility="gone"` 이라 평소엔 자리도 차지하지 않는다 → 기존 화면이 안 밀린다.
- **기존 `중단` 버튼을 재활용하지 않는 이유:** `중단` 은 `requestCancel()` 로 *돌고 있는*
  루프를 멈추는 버튼이다. 카운트다운은 아직 루프가 시작도 안 한 상태라 의미가 다르다.
  한 버튼이 상황에 따라 다른 일을 하게 만들면 나중에 반드시 헷갈린다.

---

## §4 `MainActivity.kt` — import 와 필드

```kotlin
import android.content.ActivityNotFoundException
import android.os.CountDownTimer
import android.speech.RecognizerIntent
```

클래스 필드 (`prefs` 옆):

```kotlin
/** 자동 실행 카운트다운. 취소·화면 이탈 시 반드시 cancel() 해야 한다(§7-1). */
private var countdown: CountDownTimer? = null
```

---

## §5 `onCreate` 안에 넣을 것

`findViewById` 블록에 두 줄 추가:

```kotlin
// ★ micBtn 은 ImageButton 이다. <Button> 으로 잡으면 컴파일은 통과하고
//   실행할 때 ClassCastException 으로 죽는다 (findViewById 의 타입 인자는 검사되지 않는다).
val micBtn    = findViewById<ImageButton>(R.id.micBtn)
val cancelBtn = findViewById<Button>(R.id.cancelBtn)
```

import 에 `android.widget.ImageButton` 추가.

그리고 **§2의 `startRun` 앞뒤로** 아래 순서대로 넣는다.

```kotlin
// ── 카운트다운 ────────────────────────────────────────────────
fun stopCountdown() {
    countdown?.cancel()
    countdown = null
    cancelBtn.visibility = View.GONE
}

fun startRun(task: String) { …§2… }          // ← stopCountdown 을 쓰므로 그 뒤

fun startCountdown(task: String) {
    stopCountdown()                          // 연달아 말했을 때 타이머가 겹치지 않게
    cancelBtn.visibility = View.VISIBLE
    countdown = object : CountDownTimer(3000, 500) {
        // 500ms 틱 — 1000ms 로 두면 남은 초가 툭툭 끊겨 3→1 로 건너뛰어 보인다.
        override fun onTick(left: Long) {
            result.text = "${left / 1000 + 1}초 뒤 실행합니다 — “$task”"
        }
        override fun onFinish() {
            stopCountdown()
            startRun(task)
        }
    }.start()
}

// ── 음성 결과 수신구 ──────────────────────────────────────────
// registerForActivityResult 는 액티비티가 STARTED 되기 전에만 등록할 수 있다.
// onCreate 안은 OK. 버튼 리스너 안에서 등록하면 IllegalStateException 이다(§7-2).
val voiceInput = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { res ->
    if (res.resultCode != RESULT_OK) return@registerForActivityResult   // 사용자가 취소/뒤로
    val heard = res.data
        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        ?.firstOrNull()?.trim().orEmpty()
    if (heard.isEmpty()) {
        result.text = "못 알아들었습니다. 다시 말해 주세요."
        return@registerForActivityResult
    }
    input.setText(heard)        // ★ 자동 실행이어도 들은 내용을 반드시 보여 준다
    input.setSelection(heard.length)
    startCountdown(heard)
}

// ── 버튼 배선 ────────────────────────────────────────────────
micBtn.setOnClickListener {
    stopCountdown()             // 카운트다운 중에 다시 말하려는 경우
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                 RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "무엇을 시킬까요?")
    }
    try {
        voiceInput.launch(i)
    } catch (e: ActivityNotFoundException) {
        // 이 기기에 음성인식기가 없거나 구글 앱이 비활성화됨
        result.text = "음성 인식을 쓸 수 없는 기기입니다. 직접 입력해 주세요."
    }
}

cancelBtn.setOnClickListener {
    stopCountdown()
    result.text = "취소했습니다. 내용을 고친 뒤 ‘실행’을 누르세요."
}

runBtn.setOnClickListener { startRun(input.text.toString().trim()) }
```

### 인텐트 엑스트라 셋

| 엑스트라 | 값 | 왜 |
|---|---|---|
| `EXTRA_LANGUAGE_MODEL` | `LANGUAGE_MODEL_FREE_FORM` | 자유 받아쓰기. `WEB_SEARCH` 는 검색어처럼 짧은 구절 쪽으로 편향돼 문장형 지시에 불리 |
| `EXTRA_LANGUAGE` | `"ko-KR"` | 폰 기본 언어에 맡기려면 **이 줄을 빼면** 된다. 영어 지시도 쓸 거면 빼는 쪽 |
| `EXTRA_PROMPT` | `"무엇을 시킬까요?"` | 다이얼로그에 뜨는 안내 문구 |

`EXTRA_MAX_RESULTS` 는 안 넣는다 — 우리는 1순위 후보만 쓴다. (나중에 "이 중에 골라"
UI를 만들면 그때 넣는다.)

### 화면 이탈 시 타이머 정리 — **빼먹으면 안 된다**

```kotlin
override fun onStop() {
    super.onStop()
    // 앱을 벗어난 뒤 3초 타이머가 터지면, 사용자가 보고 있지도 않은데
    // 접근성 서비스가 실기기를 조작하기 시작한다.
    stopCountdown()   // ← 지역 함수라 여기선 못 부른다. 아래 주의 참조
}
```

⚠️ `stopCountdown` 은 `onCreate` 안의 지역 함수라 `onStop` 에서 못 부른다. 둘 중 하나로:

- **(권장)** `onStop` 에 직접 쓴다:
  ```kotlin
  override fun onStop() {
      super.onStop()
      countdown?.cancel(); countdown = null
      findViewById<Button>(R.id.cancelBtn)?.visibility = View.GONE
  }
  ```
- 또는 `stopCountdown` 만 클래스 멤버 함수로 올린다(뷰는 `findViewById` 로 다시 잡음).

---

## §5.5 경로 B — 인앱 인식 (`VoiceInput.kt`)

`SpeechRecognizer` 는 콜백이 10개라 `MainActivity` 에 그냥 넣으면 읽기 어려워진다.
**별도 클래스 `VoiceInput` 으로 감싸고 람다 4개만 밖으로 낸다.**

```kotlin
class VoiceInput(
    private val ctx: Context,
    private val onState: (Boolean) -> Unit,   // 듣기 시작/끝 → 아이콘 색
    private val onPartial: (String) -> Unit,  // 말하는 중 (확정 아님)
    private val onFail: (String) -> Unit,     // 한국어로 바꾼 실패 메시지
    private val onDone: (String) -> Unit,     // 최종 문자열 ← trailing lambda
)
```

공개 API는 `isAvailable()` / `start()` / `stop()` / `cancel()` / `destroy()` /
`isListening` 뿐이다. 나중에 §10(오디오를 Gemini에 직접)으로 갈아탈 때 **이 6개만 같은
모양으로 다시 만들면 `MainActivity` 는 안 고쳐도 된다.**

### `stop()` 과 `cancel()` 은 다르다 ★

| | 하는 일 | 쓰는 곳 |
|---|---|---|
| `stop()` | 녹음만 끊고 **최종 결과를 요청**한다. 결과는 곧이어 `onDone`/`onFail` 로 온다 | 사용자가 "말 다 했다"고 마이크를 다시 누를 때 |
| `cancel()` | 결과 없이 **즉시 버린다** | `onStop`(화면 이탈), '실행' 을 눌렀을 때 |
| `destroy()` | 인식 서비스 바인딩 해제 | `onDestroy` **필수** |

`stop()` 을 부른 뒤 곧바로 끝났다고 처리하면 안 된다 — 결과는 아직 안 왔다.
같은 이유로 `onEndOfSpeech()` 에서 끝내도 안 된다.

### 인텐트 엑스트라 — A 와 다른 두 줄

```kotlin
putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)          // 이게 이 경로의 존재 이유
putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName) // 없으면 ERROR_CLIENT 내는 기기 있음
```

`EXTRA_PROMPT` 는 안 넣는다 — 띄울 다이얼로그가 없다.

### 상태 표시

`onState` 에서 `micBtn.isSelected = listening` 만 하면, `@color/mic_tint` 의
`state_selected` 항목이 아이콘을 `colorError`(빨강)로 바꾼다. 코드에서 색을 직접
칠하지 않는다.

### 권한

```kotlin
if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    != PackageManager.PERMISSION_GRANTED) {
    micPerm.launch(Manifest.permission.RECORD_AUDIO)   // 허용되면 콜백에서 start()
    return@setOnClickListener
}
```

거부당해도 앱이 막히지 않는다 — 안내문이 "길게 누르면 구글 음성 다이얼로그"를 알려 주고,
그 경로는 권한 없이 동작한다.

---

## §6 매니페스트 — 경로 B 때문에 **두 줄 늘었다**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>

<queries>
    <intent>   <!-- 기존: 런처 있는 앱 조회 (list_apps) -->
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
    <intent>   <!-- ★ 추가: SpeechRecognizer 가 인식 서비스에 바인딩하려면 필수 -->
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

- **`RECORD_AUDIO` 는 경로 B 에만 필요하다.** A(길게 누르기)는 구글 앱이 마이크를 열고
  우리는 결과 문자열만 받으므로, 권한을 거부해도 A 는 멀쩡히 동작한다.
- ★ **`RecognitionService` `<queries>` 선언이 이 작업 최대의 함정이다.** 없으면
  `isRecognitionAvailable()` 이 **엔진이 멀쩡히 깔려 있어도 false** 를 주고, 증상이
  "이 기기는 음성인식을 지원하지 않음"처럼 보여 엉뚱한 데를 뒤지게 된다.
  기존 MAIN/LAUNCHER 선언은 이 인텐트를 **안 덮는다.**
- **경로 A 는 여전히 `<queries>` 가 필요 없다.** 패키지 가시성은 `resolveActivity()`
  같은 *조회*를 막지 `startActivity` 실행을 막지 않고, 우리는 조회 대신
  `try/catch (ActivityNotFoundException)` 로 처리하기 때문이다.

---

## §7 함정

| # | 함정 | 증상 | 대응 |
|---|---|---|---|
| 1 ★ | 카운트다운을 안 끊고 화면을 벗어남 | 홈으로 나간 3초 뒤 폰이 저절로 조작되기 시작 | `onStop` 에서 `countdown?.cancel()` |
| 2 | 리스너 안에서 `registerForActivityResult` | `IllegalStateException: LifecycleOwner is attempting to register while current state is RESUMED` | `onCreate` 안(또는 프로퍼티)에서만 등록 |
| 3 ★ | 실행 중 마이크 버튼이 살아 있음 | CU 루프가 도는 중 음성 다이얼로그가 뜨고, **에이전트가 그 다이얼로그를 탭한다** | `startRun` 에서 `micBtn.isEnabled = false`, 끝나면 복구 |
| 4 | `RESULT_OK` 만 보고 문자열을 안 검사 | 빈 결과에 `startRun("")` → 무의미한 실행 | `heard.isEmpty()` 검사 (§5) |
| 5 | 카운트다운 중 `실행` 을 눌러 두 번 돎 | 같은 작업 2회 실행 | `startRun` 첫 줄 `stopCountdown()` |
| 6 | `EditText` 를 `match_parent` 로 둔 채 버튼 추가 | 마이크 버튼이 화면 밖으로 밀림 | `0dp` + `weight="1"` (§3) |
| 7 | 자동 실행이라고 인식 결과를 안 보여 줌 | 엉뚱하게 들었는데 확인할 방법이 없음 | `input.setText(heard)` 를 카운트다운 **전에** |
| 8 | 3초가 너무 짧다고 느껴짐 | 취소를 못 누름 | 상수 하나(`3000`)만 고치면 된다. 5000 도 무난 |
| 9 | `findViewById<Button>(R.id.micBtn)` | **컴파일은 통과**하고 실행할 때 `ClassCastException` | `ImageButton` 으로 잡는다. 제네릭 타입 인자는 검사되지 않는다 |
| 10 | `ImageButton` 에 tint 상태 지정을 안 함 | 실행 중 잠갔는데 아이콘이 멀쩡해 보여 계속 누름 | `@color/mic_tint` 로 `state_enabled=false` 색 지정 (§3-1a) |

### 경로 B(`SpeechRecognizer`)만의 함정

| # | 함정 | 증상 | 대응 |
|---|---|---|---|
| 11 ★★ | `<queries>` 에 `RecognitionService` 미선언 | `isAvailable()` 이 늘 false. **엔진은 멀쩡한데** "지원 안 하는 기기"처럼 보임 | §6 |
| 12 ★ | `onResults` 뒤에 `onError` 가 한 번 더 옴 | 잘 인식된 문장이 오류 메시지로 덮임 | `onError` 첫 줄에서 `if (!isListening) return` |
| 13 ★ | `onDestroy` 에서 `destroy()` 안 함 | 인식 서비스 바인딩이 남음. 반복하면 `ERROR_RECOGNIZER_BUSY` | `onDestroy` 에서 `voice?.destroy()` |
| 14 | `onEndOfSpeech` 를 "끝"으로 처리 | 결과가 오기 전에 UI를 되돌려 인식 결과를 버림 | 끝은 `onResults`/`onError` 뿐 |
| 15 | `stop()` 을 `cancel()` 처럼 씀 | 말을 다 했는데 결과가 버려짐 | §5.5 표 |
| 16 | 인스턴스 재사용 시 `ERROR_RECOGNIZER_BUSY` | 두 번째부터 인식이 안 됨 | `startListening` 직전에 `cancel()` 한 번 |
| 17 | 화면을 벗어나도 계속 들음 | 앱이 뒤로 간 채 마이크가 열려 있음 | `onStop` 에서 `voice?.cancel()` |
| 18 | 부분 결과를 확정처럼 다룸 | 말하다 만 문장으로 실행됨 | `onPartial` 은 입력칸 표시만. 카운트다운은 `onDone` 에서만 |
| 19 | 메인 스레드 밖에서 호출 | 조용히 아무 일도 안 일어남 | `SpeechRecognizer` 는 메인 스레드 전용 |

`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`(침묵 몇 초면 끝낼지)는 **구글
구현이 무시하는 경우가 많다.** 그래서 "마이크를 한 번 더 눌러 끝내기"를 주 조작으로
뒀다 — 침묵 감지에 의존하지 않는다.

### 이 프로젝트 특유의 것 — 다이얼로그 잔상

음성 다이얼로그가 닫히는 애니메이션이 끝나기 전에 첫 스크린샷을 찍으면, 모델이
**닫히는 중인 구글 다이얼로그**를 보고 판단한다. 카운트다운 3초가 이걸 겸사겸사
막아 준다 — **카운트다운을 0으로 줄이거나 "즉시 실행"으로 바꿀 거라면 이 점을 기억할 것.**
(같은 계열의 문제가 `CLAUDE.md` 「오버레이 제거는 비동기」에 이미 있다.)

---

## §8 검증

빌드: `:app:assembleDebug` (JAVA_HOME 이 필요하면
`$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`).

| # | 확인 | 기대 |
|---|---|---|
| 1 | 리팩터링만 하고 실행 | **텍스트 경로가 전과 똑같이** 동작 |
| 2 | 🎤 → "설정 앱 열어줘" | 다이얼로그 → 입력칸에 문장이 들어옴 |
| 3 | 그대로 3초 | `3초 뒤…` → `2초 뒤…` → 실행 시작 |
| 4 | 🎤 → 말하고 즉시 `취소` | 실행 안 됨, 문장은 입력칸에 남아 있음 |
| 5 | 4번 뒤 `실행` | 그 문장으로 정상 실행 |
| 6 | 🎤 → 다이얼로그에서 뒤로가기 | 아무 일도 안 일어남(카운트다운도 안 뜸) |
| 7 | 🎤 → 아무 말 안 함 | "못 알아들었습니다" |
| 8 ★ | 🎤 → 말한 뒤 **바로 홈 버튼** | 3초 뒤에도 폰이 조작되지 않음 |
| 9 ★ | 실행 중 🎤 | 눌리지 않음(비활성) |
| 10 | 실행 완료 후 🎤 | 다시 눌림 |
| 11 | 고유명사 시험 | "유튜브에서 뉴진스 검색해줘" 같은 걸 5회 — 몇 번 맞는지 세 둔다(§10 판단 근거) |

경로 B(짧게 누르기) 추가분:

| # | 확인 | 기대 |
|---|---|---|
| 12 | 첫 짧게 누르기 | 마이크 권한 요청 팝업 → 허용하면 바로 듣기 시작 |
| 13 | 권한 **거부** | "길게 누르면 구글 음성 다이얼로그" 안내. 길게 눌러 실제로 되는지 확인 |
| 14 ★ | 말하는 중 | 아이콘이 **빨갛게**, 입력칸에 **실시간 자막**이 갱신됨 |
| 15 | 말 끝내고 마이크 다시 누르기 | "인식 중…" → 최종 문장 확정 → 카운트다운 |
| 16 | 말 안 하고 가만히 | "말소리가 들리지 않았습니다" (`ERROR_SPEECH_TIMEOUT`) |
| 17 ★ | 듣는 중에 홈 버튼 | 마이크가 닫힘. 되돌아오면 아이콘이 빨갛지 않음 |
| 18 ★ | 12~17을 **연속 10회** | `ERROR_RECOGNIZER_BUSY` 가 안 나야 함 (함정 13·16 검증) |
| 19 | 듣는 중에 '실행' 누르기 | 인식이 접히고, 입력칸에 있던 내용으로 실행 |
| 20 | 인식 성공 직후 화면 관찰 | 오류 메시지가 뒤늦게 덮어쓰지 않아야 함 (함정 12 검증) |
| 21 | **A와 B 인식률 비교** | 같은 문장 5개를 짧게/길게 각각 — **결과가 같아야 정상**(§1) |

21번이 §1의 "둘은 같은 엔진"이라는 주장을 실제로 확인하는 항목이다. 만약 눈에 띄게
다르면 §1을 고쳐야 한다.

11번은 **§10으로 갈지 말지를 정하는 데이터**다. 인식이 틀려도 Gemini가 흡수해서
결과적으로 성공하는 경우가 많으니, "받아쓰기 정확도"와 "작업 성공 여부"를 따로 센다.

---

## §9 속도 영향

**CU 루프 속도에는 영향이 없다.** 음성은 루프가 시작되기 *전* 단계라, 턴당 API 호출
시간(벽시계의 대부분)은 그대로다.

사람이 체감하는 시작 지연만 바뀐다:

| | 시작까지 |
|---|---|
| 타이핑 (한글 한 문장) | ~10-15초 |
| 음성 | 다이얼로그 ~1초 + 발화 ~3초 + 인식 ~1초 + **카운트다운 3초** ≈ 8초 |

카운트다운이 이득의 절반을 먹는다. 익숙해진 뒤에도 거슬리면 §7-8대로 초를 줄이되,
§7 마지막의 다이얼로그 잔상 문제를 같이 고려할 것.

---

## §10 (기록 전용, 구현 안 함) 오디오를 Gemini 에 직접 보내기

§1에서 정리했듯 **인식률을 빅스비 위로 올릴 수 있는 유일한 경로**다. 지금은 안 만들고,
§8-11 결과가 나쁠 때 꺼내 쓰기 위해 설계만 남긴다.

### 왜 더 나을 수 있나

ASR 은 "무슨 소리였나"만 푼다. Gemini 는 **문맥을 주면** "무슨 소리였을 법한가"를
같이 푼다. 우리는 줄 문맥이 있다:

- `list_apps` 가 주는 **실제 설치된 앱 이름 목록** → "배민"·"토스"·낯선 앱 이름을 맞춤
- "이건 폰 조작 지시다"라는 과제 설명 → 엉뚱한 동음이의어를 배제
- 지난 실행 기록(`run_history.txt`) → 자주 쓰는 표현에 편향

즉 빅스비가 이기던 지점(고유명사·도메인 문맥)을 그대로 흉내낼 수 있다.

### 구조

```
[🎤 길게 누르기] → MediaRecorder 로 녹음(m4a) → base64
        ↓
  generateContent 1회 (interactions 아님)
   ├ inline_data: audio/mp4
   └ text: "이 음성은 안드로이드 폰 조작 지시다. 목표 한 문장으로 옮겨라.
            설치된 앱: {list_apps 결과}. 다른 말 붙이지 말고 문장만."
        ↓
   목표 문자열 → 기존 startCountdown/startRun 에 그대로 투입
```

### 필요한 것

| 항목 | 내용 |
|---|---|
| 권한 | `RECORD_AUDIO` (런타임 요청 필요 — §6과 달리 이번엔 우리가 마이크를 연다) |
| 녹음 | `MediaRecorder`(간단, m4a/AAC) 또는 `AudioRecord`(원시 PCM, 스트리밍용) |
| 엔드포인트 | `v1beta/models/{model}:generateContent` — **`CuClient.cuCall` 이 쓰는 `interactions` 와 다른 경로다.** `CuClient` 에 별도 함수를 만든다. API 키 헤더는 동일 |
| 요청 형식 | `contents[0].parts` 에 `{inline_data:{mime_type, data:base64}}` + `{text: 프롬프트}` |
| 크기 | 인라인 데이터는 요청 총량 제한이 있다(수 MB급). 짧은 지시는 수십 KB라 문제 없음 |
| 비용·지연 | 실행마다 API 호출 1회 추가, 대략 +1~2초 |

### ⚠️ 미검증 — 반드시 대조 실험부터

이 저장소는 **구글 문서대로 보냈다가 400을 맞은 전례가 둘**이다
(`CLAUDE.md` 의 「안전 승인 형식」, 「사고수준 평면+소문자」). 위 요청 형식도 문서를
근거로 적은 것이고 **이 API 에 실제로 쏴 본 적이 없다.**

만들 때는 그 두 건과 같은 방식으로 확인한다:

1. 먼저 **엉터리 키**(`inline_dataX` 등)를 넣어 400이 나는지 본다 → 서버가 모르는 키를
   거절한다는 걸 확인(= 200이 "실제로 읽혔다"는 뜻이 됨).
2. 그다음 진짜 형식으로 200이 나는지, 응답 텍스트가 말한 내용과 맞는지 본다.
3. 되는 형식·안 되는 형식을 표로 `CLAUDE.md` Gotchas 에 남긴다.

### 판단 기준

§8-11 에서 **받아쓰기 정확도가 낮은데 그것 때문에 작업이 실패하는** 경우가 유의미하게
나올 때만 만든다. 인식이 좀 틀려도 Gemini 가 흡수해 결과가 맞으면, API 호출 1회를
더 쓸 이유가 없다.

---

## §11 다 되면 갱신할 문서

- **`CLAUDE.md` §구현 현황** — 「폰 단독 on-device」 목록에 한 줄. 기존 줄을 고쳐 쓰는
  문서라는 규칙에 따라, 검증 상태(`빌드 확인, 실기기 미검증` → `실기기 검증 완료`)를
  진행에 맞춰 갱신한다.
  ```
  - ✅ 음성으로 목표 입력 — 🎤 버튼 두 경로. 짧게=인앱 인식(SpeechRecognizer,
    실시간 자막, RECORD_AUDIO 권한·RecognitionService <queries> 필요), 길게=구글 음성
    다이얼로그(RecognizerIntent, 권한 불필요 대비책). 둘 다 폰 기본 엔진이라 인식률 동일.
    인식 문장을 입력칸에 채우고 3초 카운트다운 후 자동 실행(그 사이 취소 가능).
    텍스트 입력은 그대로. 실행 중엔 마이크 잠금.
  ```
- **`README.md`** — 「앱 화면에서 고를 수 있는 것」 근처에 음성 입력 한 단락.
  *"음성인식은 폰에 설정된 기본 엔진을 쓴다(설정 > 기본 앱 > 디지털 어시스턴트).
  기기·계정에 따라 인식률이 다르다"* 를 적어 두면 팀원이 헤매지 않는다.
- **이 문서 §10** — 나중에 실제로 만들면 「기록 전용」 딱지를 떼고 검증 표를 채운다.
