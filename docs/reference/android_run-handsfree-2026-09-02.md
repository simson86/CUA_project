# 핸즈프리 트리거 — 앱을 안 열고 작업 시키기 (구현 가이드)

**대상 문서:** `android_run-todo-hands-free-2026-08-11.md`(설계·타당성)의 **후속**.
저쪽은 "무엇이 가능한가", 이 문서는 "어떻게 짜는가"다. 설계 근거(핫워드가 왜 안 되는지,
트리거 후보 6종 비교)는 반복하지 않으니 그쪽을 먼저 읽을 것.

**상태: 코드 미적용.** 2026-09-02 작성. `minSdk = 30` 확인 — 접근성 버튼(API 26+)에
버전 가드가 필요 없다.

> **2026-09-15 갱신 — 새 main(PR #13~#15: 모델 3.7·3.8, 기억 시스템, 무인 실행) 기준으로 고쳤다.**
> 달라진 곳: §3-a 테마 부모 · §4 `attended` · §5 `RunConfig`(**옛 코드는 컴파일 안 됨**) ·
> §5 소켓 권고(**철회 — 따르면 PR #15 가 고친 버그가 되살아난다**) · §6 가드.
> 같은 날 확인: Google Assistant 는 2026-09-04 부터 종료 중이고, 후속인 Gemini 는 제3자 앱에
> 작업을 넘길 공개 경로가 없다(AppFunctions 는 비공개 프리뷰). 그래서 트리거는 이 문서대로
> **우리가 직접 만든다.**

---

## §0 무엇을 만드는가

```
[어느 앱에서든 접근성 버튼 탭]
        ↓  a11service.onAccessibilityButtonClicked()
[VoiceTriggerActivity]  투명 배경 + 화면 아래 작은 카드
        ↓  VoiceInput(이미 있음) 으로 듣기 → 실시간 자막 → 확정
[3초 카운트다운 + 취소]           ← 안전선. 절대 빼지 않는다
        ↓  finish()
[카드가 사라질 때까지 대기]        ← ★ §4
        ↓
a11service.runTaskWithSavedConfig(들은 말)
        ↓  첫 스크린샷 = 아까 보던 그 앱
```

사용자 눈에는 **쓰던 앱 위에 카드가 잠깐 떴다 사라지는 것**뿐이다. 액티비티를
띄우는 게 맞지만 그건 마이크를 열기 위한 수단이고(§2), 빅스비도 같은 구조다.

---

## §1 왜 액티비티를 거치는가 — 마이크 때문이다

한 줄 요약: **접근성 서비스는 마이크를 못 연다.**

- 안드로이드 9부터 포그라운드가 아닌 앱은 마이크를 못 연다. 접근성 서비스로 상시
  떠 있다는 사실은 포그라운드 지위를 주지 않는다.
- 마이크형 포그라운드 서비스(FGS)로 우회하려 해도, **백그라운드에서 시작된 FGS 는
  마이크를 못 받는다**(API 30+)에 다시 걸린다.
- 액티비티는 정의상 포그라운드다. 그래서 문제 자체가 사라진다. **권한 추가 0개.**

반대로 **트리거는 서비스가 받아야 한다** — 어느 앱에 있든 눌리려면 그렇다.
그래서 구조가 "서비스가 받아서 → 액티비티를 띄운다"로 갈라진다.

> **백그라운드 액티비티 실행 제한에 안 걸리나?** 안 걸린다 — 구글이 문서화한 면제
> 목록에 "시스템이 바인딩한 서비스(AccessibilityService 등)를 가진 앱"이 있다.
> 다만 **문서를 근거로 넘기지 말고 §7 1단계에서 실제로 확인할 것.** 이 저장소는
> 문서가 틀렸던 사례가 이미 셋이다(`CLAUDE.md` 안전승인·사고수준·커스텀함수).

---

## §2 1단계 — 접근성 버튼만 붙여 본다

**목표: 트리거가 실제로 오는지만 확인.** 음성도 액티비티도 아직 붙이지 않는다.

### (a) `res/xml/accessibility_service_config.xml`

지금 이 파일에는 `accessibilityFlags` 자체가 없다. 한 줄 추가한다.

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:accessibilityFlags="flagRequestAccessibilityButton"
    android:notificationTimeout="100" />
```

기존 능력(`canRetrieveWindowContent`·`canPerformGestures`·`canTakeScreenshot`)은
플래그가 아니라 별도 속성이라 **영향받지 않는다.** 추가일 뿐 교체가 아니다.

### (b) `a11service.kt`

```kotlin
/**
 * 접근성 버튼(제스처 내비에선 화면에 뜨는 동그란 버튼, 3버튼 내비에선 내비바 안).
 * 시스템이 그리는 버튼이라 우리 오버레이의 터치 가로채기 문제와 무관하다.
 * config 의 flagRequestAccessibilityButton 과 짝이다 — 한쪽만 있으면 안 온다.
 */
override fun onAccessibilityButtonClicked() {
    Log.d("A11y", "accessibility button clicked")
    ui.post { Toast.makeText(this, "트리거 도착", Toast.LENGTH_SHORT).show() }
}
```

`ui` 는 이미 서비스에 있는 메인 핸들러다(오버레이가 쓴다). Toast 는 메인 스레드에서만
띄울 수 있다. **`import android.widget.Toast` 를 추가할 것** — 2026-09-15 기준 `a11service.kt` 에는
`Intent` 만 있고 `Toast` import 가 없다.

### (c) 폰에서 해야 하는 일 ★ 코드로 못 한다

플래그를 켜도 **자동으로 버튼이 우리 앱에 붙지는 않는다.**
접근성 버튼은 요청한 서비스가 여럿이면 사용자가 고르는 자원이다.

- 설정 > 접근성 > **접근성 버튼**(삼성: 접근성 > 고급 설정 > 접근성 버튼) 에서
  `Android_run` 이 목록에 나오는지 확인하고 켠다.
- 접근성 서비스를 **껐다 켜야** 새 config 가 읽히는 기기가 있다. 재설치만으로
  안 되면 이걸 먼저 의심할 것.
- `accessibilityButtonController.isAccessibilityButtonAvailable` 로 코드에서도
  확인할 수 있다. 1단계에서 로그로 찍어 두면 "안 눌리는" 상황의 원인이 바로 갈린다.

### (d) 덤 — 하드웨어 트리거

삼성 **측면 버튼 두 번 누르기**를 설정에서 `Android_run` 으로 지정하면 코드 0줄로
하드웨어 트리거가 붙는다. 다만 그건 **런처 액티비티(= `MainActivity`)** 를 띄운다.
`VoiceTriggerActivity` 로 보내려면 `<activity-alias>` 에 LAUNCHER 를 달아야 하고,
그러면 **런처에 아이콘이 하나 더 생긴다.** 그 값이 있는지는 §7 5단계 이후에 판단.

---

## §3 2단계 — `VoiceTriggerActivity`(투명)

### (a) 테마 — `res/values/themes.xml` 에 추가

```xml
<!-- 투명 액티비티. windowIsTranslucent 없이 배경만 투명하게 하면 뒤 앱이 안 보인다
     (액티비티 창은 기본이 불투명이라, 그 아래 그리기를 시스템이 아예 멈춘다). -->
<!-- 부모는 앱 테마와 같은 Material3 계열로 — AppCompat 로 두면 다크 모드·?attr/colorSurface 색이
     MainActivity 와 달라진다. (AppCompatActivity 는 Material3 테마에서도 그대로 동작한다.) -->
<style name="Theme.VoiceTrigger" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowNoTitle">true</item>
    <!-- 뒤 앱을 살짝 어둡게 — 지금 입력을 받는 중이라는 신호. 취향이면 false. -->
    <item name="android:backgroundDimEnabled">true</item>
    <item name="android:windowAnimationStyle">@null</item>
</style>
```

`windowAnimationStyle=@null` 은 §4(카드가 화면에 남는 문제)를 **줄여 주지만
없애 주지는 않는다.** 대기 시간은 그대로 필요하다.

### (b) 매니페스트

```xml
<activity
    android:name=".VoiceTriggerActivity"
    android:exported="false"
    android:theme="@style/Theme.VoiceTrigger"
    android:excludeFromRecents="true"
    android:launchMode="singleTask"
    android:taskAffinity=""
    android:windowSoftInputMode="stateAlwaysHidden" />
```

- `excludeFromRecents` — 최근 앱 목록에 우리 카드가 남으면 안 된다.
- `singleTask` — 버튼을 연타해도 카드가 쌓이지 않는다.
- `taskAffinity=""` — **이게 없으면 `MainActivity` 와 같은 태스크에 들어간다.**
  그러면 카드를 닫았을 때 원래 앱이 아니라 `MainActivity` 로 돌아가 버린다.
  이 액티비티는 별도 태스크여야 "쓰던 앱 위에 잠깐"이 성립한다.
- `stateAlwaysHidden` — 키보드가 올라오면 뒤 앱 레이아웃이 흐르고, 그건 우리가
  이미 크게 데인 함정이다(`CLAUDE.md` "오버레이가 포커스를 뺏으면 …").

### (c) 레이아웃 `res/layout/activity_voice_trigger.xml`

화면 **아래쪽**에 붙인다. 위쪽은 상태바·앱 헤더가 있고, 아래는 손가락이 닿는 곳이다.

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:id="@+id/card"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:layout_margin="16dp"
        android:orientation="vertical"
        android:padding="20dp"
        android:background="?attr/colorSurface"
        android:elevation="8dp">

        <TextView android:id="@+id/heard"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:textSize="18sp" android:minLines="2"
            android:text="듣는 중…" />

        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="horizontal" android:gravity="end">
            <Button android:id="@+id/cancelBtn"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="취소" />
            <Button android:id="@+id/okBtn"
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:text="지금 실행" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
```

### (d) `VoiceTriggerActivity.kt`

`VoiceInput` 을 그대로 재사용한다. **`MainActivity` 의 음성 로직을 복사하지 말 것** —
두 벌이 되면 한쪽만 고치는 사고가 난다. 지금은 콜백 배선만 다르고 인식기는 하나다.

```kotlin
package com.cua.a11

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 접근성 버튼이 띄우는 투명 액티비티. 존재 이유는 단 하나 — **마이크를 열기 위한
 * 포그라운드 지위**다(가이드 §1). 인식이 끝나면 스스로 사라지고, 실행은 서비스가 한다.
 */
class VoiceTriggerActivity : AppCompatActivity() {

    private var voice: VoiceInput? = null
    private var countdown: CountDownTimer? = null
    private var task: String = ""

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_voice_trigger)

        val heard = findViewById<TextView>(R.id.heard)
        val okBtn = findViewById<Button>(R.id.okBtn)

        // 실행 중이면 새 지시를 받지 않는다. 받으면 두 에이전트가 같은 화면을 동시에 만진다.
        val svc = a11service.instance
        if (svc == null) {
            heard.text = "접근성 서비스가 꺼져 있습니다."
            finishAfter(1800); return
        }
        if (svc.isRunning) {
            heard.text = "이미 실행 중입니다. 끝난 뒤 다시 눌러 주세요."
            finishAfter(1800); return
        }

        findViewById<Button>(R.id.cancelBtn).setOnClickListener { cancelAndFinish() }
        okBtn.setOnClickListener { if (task.isNotEmpty()) launch() }   // 카운트다운 건너뛰기

        val v = VoiceInput(
            this,
            onState  = { /* 필요하면 카드에 표시 */ },
            onPartial = { heard.text = it },
            onFail   = { heard.text = it; finishAfter(2500) },
        ) { said ->
            task = said
            heard.text = said
            startCountdown(heard, said)
        }
        voice = v

        if (!v.isAvailable()) { heard.text = "이 기기에서 음성 인식을 쓸 수 없습니다."; finishAfter(2500); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // ★ 여기서 권한을 처음 받게 하지 말 것 — §5 참조.
            heard.text = "마이크 권한이 필요합니다. 앱을 열어 한 번 허용해 주세요."
            finishAfter(2500); return
        }
        v.start()
    }

    private fun startCountdown(heard: TextView, said: String) {
        countdown?.cancel()
        countdown = object : CountDownTimer(3000, 500) {
            override fun onTick(left: Long) { heard.text = "${left / 1000 + 1}초 뒤 실행 — “$said”" }
            override fun onFinish() { launch() }
        }.start()
    }

    /** 카드를 먼저 지우고, 화면에서 실제로 사라진 뒤에 실행을 건다(§4). */
    private fun launch() {
        countdown?.cancel(); countdown = null
        voice?.cancel()
        val t = task
        finish()
        overridePendingTransition(0, 0)
        a11service.instance?.launchFromTrigger(t)
    }

    private fun cancelAndFinish() {
        countdown?.cancel(); countdown = null
        voice?.cancel()
        finish(); overridePendingTransition(0, 0)
    }

    private fun finishAfter(ms: Long) =
        findViewById<TextView>(R.id.heard).postDelayed({ finish() }, ms)

    /** 화면을 벗어나면 무조건 접는다 — 안 보이는 카드가 3초 뒤 실기기를 조작하면 안 된다. */
    override fun onStop() { super.onStop(); cancelAndFinish() }

    override fun onDestroy() { super.onDestroy(); voice?.destroy(); voice = null }
}
```

`a11service.onAccessibilityButtonClicked()` 는 이제 토스트 대신:

```kotlin
override fun onAccessibilityButtonClicked() {
    startActivity(Intent(this, VoiceTriggerActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))   // ★ 서비스에는 태스크가 없다. 없으면 예외
}
```

---

## §4 ★ `finish()` 직후 바로 캡처하면 우리 카드가 찍힌다

`CLAUDE.md` 「오버레이 제거는 비동기」와 **정확히 같은 함정**이다. 액티비티도
`finish()` 는 종료를 예약할 뿐이고, 창이 실제로 사라지고 뒤 앱이 다시 그려지기까지
프레임이 더 필요하다. 그 사이에 찍으면 모델이 **우리 카드를 보고** 그걸 조작하려 든다.

지금 이 함정이 안 보이는 이유는 **3초 카운트다운이 우연히 막아 주고 있어서**가 아니다 —
카운트다운은 카드가 떠 있는 동안 돈다. `finish()` 와 첫 캡처 사이에는 아무것도 없다.

```kotlin
// a11service.kt
/**
 * 트리거(접근성 버튼) 경로의 실행 진입점.
 * 액티비티가 finish() 한 직후에 불린다 — 카드가 화면에서 실제로 사라질 시간을 준 뒤
 * 시작한다. 이 대기를 빼면 첫 스크린샷에 우리 카드가 찍힌다(가이드 §4).
 * 값은 실측으로 정할 것. 시작점 600ms.
 */
fun launchFromTrigger(task: String) {
    ui.postDelayed({
        thread { runTaskWithSavedConfig(task) }   // 네트워크·대기가 있으므로 메인 스레드 금지
        // ★ runTask 를 거치므로 attended = true 로 돈다 — 사람이 버튼을 누른 실행이니 맞다.
        //   소켓 RUN 처럼 runAgent 를 직접 부르면 무인 취급돼, 인계·자격증명 카드 없이 즉시 실패한다.
    }, TRIGGER_SETTLE_MS)
}
```

**고정 지연이 마음에 안 들면** 서비스에 이미 있는 `lastEventTs`(마지막 화면 변경 시각)를
써서 "이벤트가 N ms 동안 멈출 때까지 기다린다"로 바꿀 수 있다. 다만 그건 화면이
계속 움직이는 앱(동영상·애니메이션)에서 영영 안 끝난다 — **상한을 반드시 둘 것.**
1차 구현은 고정 지연으로 하고, §7 4단계에서 로그를 보고 판단한다.

---

## §5 설정 읽기를 한 곳으로 — 진입점이 셋이 됐다

지금 `SharedPreferences` 를 읽는 곳은 `MainActivity` 하나다. 그래서 액티비티를
안 거치는 소켓 `RUN` 은 조용히 기본값으로 돈다(`CLAUDE.md` 에 이미 알려진 제약).
트리거 경로가 붙으면 **같은 병이 하나 더 생긴다.** 이참에 읽기를 모은다.

`RunConfig.kt` (신규):

```kotlin
package com.cua.a11

import android.content.Context

/**
 * 실행 설정의 유일한 읽기 지점.
 * 진입점이 셋(액티비티 · 소켓 RUN · 접근성 버튼)이 된 순간, 읽기가 흩어져 있으면
 * 어떤 경로가 어떤 설정으로 돌았는지 아무도 모르게 된다.
 * 우선순위는 MainActivity 가 쓰던 것과 같다: 저장값 > BuildConfig > CuClient 기본값.
 */
data class RunConfig(val maxTurns: Int, val model: String, val thinking: String) {
    companion object {
        fun load(ctx: Context): RunConfig {
            val p = ctx.getSharedPreferences("cua", Context.MODE_PRIVATE)
            // ★ 모델·사고수준은 **저장된 문자열을 그대로** 넘긴다. 여기서 인덱스로 거르지 말 것.
            //   조합 검증(3.7·3.8 + minimal → 400)은 runTask 안의 cu.configure() 한 곳에만 있다.
            //   여기서 또 거르면 규칙이 두 벌이 되고, CuClient.THINKING[...] 처럼 전체 목록 인덱스로
            //   읽으면 모델별 목록(thinkingFor)과 어긋난다 — MainActivity 도 같은 이유로 인덱스를 안 쓴다.
            return RunConfig(
                maxTurns = p.getInt("max_turns", 20).coerceIn(1, 40),
                model    = p.getString("model", null) ?: BuildConfig.GEMINI_MODEL,
                thinking = p.getString("thinking", null) ?: BuildConfig.GEMINI_THINKING,
            )
        }
    }
}
```

`a11service.kt`:

```kotlin
/**
 * 저장된 설정으로 도는 실행. 액티비티를 안 거치는 경로(트리거·소켓)가 쓴다.
 * ★ 이름을 runTask 로 하지 말 것 — 기존 runTask 는 뒤 파라미터가 전부 기본값이라
 *   `runTask(task) { … }` 가 두 후보에 다 맞아 '모호한 호출'로 컴파일이 깨진다.
 */
fun runTaskWithSavedConfig(task: String, log: (String) -> Unit = {}): String {
    val c = RunConfig.load(this)
    return runTask(task, c.maxTurns, c.model, c.thinking, log)
}
```

`MainActivity` 는 화면의 드롭다운 값을 **저장하고 그대로 넘기는** 지금 구조를 유지한다
(사용자가 방금 고른 값이 곧 저장값이라 결과가 같다). 여기서 굳이 `RunConfig` 를
읽게 바꾸면 "화면에 보이는 값과 도는 값이 다를 수 있는" 경로가 생긴다.

**소켓 `RUN` 은 `runTaskWithSavedConfig` 로 합치지 말 것 — 권고 철회(2026-09-15).**
처음엔 "세 경로를 같게 하자"고 적었지만 이제는 **하면 안 된다.** `runTask` 는 시작할 때
`attended = true`(사람이 보고 있다)로 둔다. 소켓은 정의상 무인이라 팀원이 PR #15 에서 일부러
`runAgent` 를 직접 부르고 `attended = false` 로 돌린다. 이걸 `runTask` 로 합치면 **없는 사람을
인계 카드 앞에서 3분씩 기다리던 버그(실측 203초 중 187초)가 되살아난다.**
목적은 경로를 하나로 모으는 게 아니라, **각 경로가 '사람이 있나'를 정확히 말하게 하는 것**이다.
트리거는 사람이 누른 것이므로 `runTask` 경로가 맞다(§4).

---

## §6 안전 — 트리거가 생기면 위험도가 올라간다

어디서든 한 번 눌러 실기기 조작이 시작된다. 지금까지는 "앱을 열고 실행을 누른다"가
사실상 확인 절차였다.

1. **3초 카운트다운 + 취소는 반드시 유지한다.** 모델의 `require_confirmation` 은
   비결정적이라 방어선으로 못 믿는다(`CLAUDE.md`).
2. **중복 실행 가드** — 서비스에 상태를 둔다. 지금은 `MainActivity` 가 버튼을 비활성화해
   막고 있을 뿐이라, 액티비티를 안 거치는 경로엔 아무 가드가 없다(2026-09-15 main 에도
   `isRunning` 은 없다). **소켓 `RUN` 도 이 가드를 안 거친다** — 트리거로 도는 중에 PC 가
   `RUN` 을 쏘면 둘이 같은 화면을 만진다. 게다가 `attended`·`cancelled` 가 실행 단위가 아니라
   서비스 필드라 서로를 덮는다(팀원 주석: "병렬 실행을 허용하게 되면 셋 다 같이 옮겨야 한다").

   ```kotlin
   @Volatile var isRunning = false
       private set
   // runTask 안에서:
   //   시작 직후  isRunning = true
   //   반드시 try { … } finally { isRunning = false }
   ```
   `finally` 가 아니면 예외 한 번에 앱이 영구히 "실행 중"으로 잠긴다.
3. **첫 화면이 그때 보던 앱이다.** 이게 이 기능의 핵심 가치지만(카톡에서 "이 대화
   요약해줘"가 성립한다), 동시에 **사적인 화면이 그대로 모델에 간다**는 뜻이다.
   카운트다운 카드에 그 사실이 드러나야 한다 — "지금 화면을 보고 작업합니다" 같은
   한 줄을 카드에 넣는 것을 권한다.
4. **에이전트가 접근성 버튼 자체를 누를 수 있다.** 제스처 내비에서는 화면에 떠 있는
   동그란 버튼이라 좌표가 겹칠 수 있다. 기존 미해결 항목(에이전트가 우리 앱의
   중단 버튼을 누를 수 있는 문제, `android_run-token-compare-2026-08-11.md` §3.5)과
   같은 뿌리다. 이 기능이 그 표면을 하나 더 늘린다는 점만 기록해 둔다.
5. **마이크 권한은 트리거 경로에서 처음 받게 하지 않는다.** 권한 다이얼로그가
   남의 앱 위에 튀어나오고, 거부되면 카드가 아무 설명 없이 사라진다. `MainActivity`
   에서 한 번 받아 둔 뒤 트리거는 **있는지 확인만** 한다(§3-d 코드가 그렇게 돼 있다).

---

## §7 착수 순서 — 한 단계씩 폰에서 확인하고 넘어간다

각 단계는 그 자체로 커밋 가능해야 한다. 한꺼번에 짜면 안 될 때 원인이 안 갈린다.

| # | 할 것 | 확인 방법 | 안 되면 의심할 것 |
|---|---|---|---|
| 1 | `accessibilityFlags` + `onAccessibilityButtonClicked` 토스트 | 다른 앱에서 접근성 버튼 탭 → 토스트 | 설정에서 버튼 미지정 / 서비스 껐다 켜기 (§2-c) |
| 2 | `VoiceTriggerActivity` 띄우기만 (음성 없이) | 뒤 앱이 비쳐 보이는가, 닫으면 **원래 앱**으로 돌아가는가 | `taskAffinity=""` 누락, `windowIsTranslucent` 누락 (§3-b) |
| 3 | `VoiceInput` 배선 | 실시간 자막이 카드에 찍히는가 | 마이크 권한, `<queries>` (이미 있음) |
| 4 | `launchFromTrigger` + `RunConfig` | 첫 스크린샷에 **우리 카드가 없는가** — 로그의 첫 액션을 볼 것 | `TRIGGER_SETTLE_MS` 를 올려 본다 (§4) |
| 5 | 카톡 등에서 실제 작업 | 모델이 "지금 이 앱"을 전제로 움직이는가 | `CuClient.system_prompt` (§8) |

---

## §8 확인 필요 — 시스템 프롬프트가 홈에서 시작한다고 전제하는가

`list_apps`/`open_app` 안내는 "from anywhere, including from inside another app" 이라
큰 수정은 없을 것으로 **보이나 확인하지 않았다.** 5단계에서 실제로 돌려 보고 판단할 것.

만약 모델이 매번 홈으로 나갔다가 다시 앱을 여는 식으로 움직이면, 그때 프롬프트에
"작업은 사용자가 지금 보고 있는 화면에서 시작한다" 한 줄을 더하는 정도로 족할 것이다.
**돌려 보기 전에 프롬프트부터 고치지 말 것** — 이 저장소는 프롬프트 한 줄의 효과를
N=5 로 판단했다가 두 번 뒤집힌 적이 있다.

---

## §9 이 문서가 안 다루는 것

- **핫워드**("하이 빅스비"처럼 말만 하면 시작) — `android_run-todo-hands-free-2026-08-11.md` §1
  에서 범위 밖으로 정리했다. 별개 프로젝트다.
- **기본 어시스턴트 교체**(`VoiceInteractionService`, 홈 버튼 길게) — 구글 어시스턴트를
  대체하게 되고 작업량이 크다. 트리거가 실제로 쓸 만한지 확인한 뒤에 볼 선택지.
