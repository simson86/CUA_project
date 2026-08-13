# 실행 중 떠있는 오버레이 창 (다른 앱 위에 실시간 로그)

> 실행 중 조작 대상 앱(설정/쿠팡 등) **위에 작은 창**이 떠서
> 턴마다 `[턴 N] 액션`을 실시간 표시. 끝나면 결과 잠깐 보여주고 사라짐.

---

## 0. 설계

- **오버레이는 `a11service`가 소유.** runTask에 이미 로그 콜백이 흐르므로 그걸 창에도 그린다.
  서비스는 항상 살아있어 앱이 뒤로 가도 창이 유지됨.
- **권한 `SYSTEM_ALERT_WINDOW`("다른 앱 위에 표시").** 런타임 팝업 불가 → **설정 화면에서 사용자가 직접 ON**.
  `Settings.canDrawOverlays(this)`로 확인, 없으면 MainActivity가 설정으로 보냄.
- **WindowManager 조작은 메인스레드 전용.** 로그 콜백은 백그라운드 스레드라 `Handler(Looper.getMainLooper())`로 post.
- **입력 안 뺏기:** `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL` (없으면 조작 대상 앱의 키보드/터치가 깨짐).
- 창 UI는 XML 없이 코드로 TextView 하나 생성(간단). **지금 턴 한 줄만** 표시(다음 턴에 덮어씀).
- **스크린샷 오염 방지(중요):** 오버레이는 화면에 실제로 그려져 `takeScreenshot`에 같이 찍힘 → 모델 시야를 가림.
  그래서 `screenshot()`에서 **찍기 직전 오버레이를 숨기고(GONE) 한두 프레임 기다린 뒤 캡처, 직후 복원**한다(§2-f).
  대가는 턴당 깜빡임 1회 + ~32ms(무시 수준). 이걸로 모델이 보는 화면은 완전히 깨끗해짐.

---

## 1. `AndroidManifest.xml` — 권한 추가

INTERNET/POST_NOTIFICATIONS 아래에:
```xml
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
```

---

## 2. `a11service.kt` — 오버레이 만들고 갱신

### 2-a. import 추가
```kotlin
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
```

### 2-b. 필드 2개 (cu 근처, 클래스 안)
```kotlin
    private val ui = Handler(Looper.getMainLooper())   // 메인스레드 post용
    private var overlayView: TextView? = null
```

### 2-c. runTask에서 창 켜고/갱신/끄기 (runTask 교체)

기존 runTask를 아래로 **교체**(알림은 그대로 유지, 오버레이만 추가):
```kotlin
    fun runTask(task: String, log: (String) -> Unit = {}): String {
        showOverlay(task)
        val r = try {
            runAgent(this, cu, task, log = { line ->
                log(line)              // 기존: 앱 화면 로그 + 파일
                postOverlay(line)      // 추가: 오버레이 창
            })
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
        postOverlay(r)
        notifyDone(task, r)
        ui.postDelayed({ hideOverlay() }, 4000)   // 결과 4초 보여주고 닫음
        return r
    }
```

### 2-d. 오버레이 헬퍼 3개 (notifyDone 근처에 추가)
```kotlin
    private fun showOverlay(task: String) {
        if (!Settings.canDrawOverlays(this)) return   // 권한 없으면 조용히 skip(알림은 뜸)
        ui.post {
            overlayView?.let { it.text = "▶ $task"; return@post }  // 이미 있으면 재사용
            val tv = TextView(this).apply {
                text = "▶ $task"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())   // 반투명 검정
                setPadding(24, 24, 24, 24)
                textSize = 12f
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // 다른 앱 위
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or       // 입력 안 뺏음
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
            getSystemService(WindowManager::class.java).addView(tv, lp)
            overlayView = tv
        }
    }

    private fun postOverlay(line: String) {
        ui.post {
            overlayView?.text = line   // 지금 턴 한 줄만 표시(다음 턴에 덮어씀)
        }
    }

    private fun hideOverlay() {
        ui.post {
            overlayView?.let { getSystemService(WindowManager::class.java).removeView(it) }
            overlayView = null
        }
    }
```

### 2-e. 서비스 종료 시 창 정리 (누수 방지)

`onDestroy`/`onUnbind`에 `hideOverlay()` 한 줄씩:
```kotlin
    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()          // ← 추가
        instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        hideOverlay()          // ← 추가
        instance = null
        super.onDestroy()
    }
```

### 2-f. 스크린샷 직전 오버레이 숨기기 (핵심 — 모델 시야 오염 방지)

기존 `override fun screenshot()`을 아래로 **교체**(찍기 전 숨김 → 캡처 → 복원):
```kotlin
    override fun screenshot(): ByteArray {
        hideForShot()                          // 오버레이 숨기고 프레임 대기
        val png = capturePngBlocking()
        showAfterShot()                        // 다시 보이기
        val (w, h) = pngSize(png); lastW = w; lastH = h
        return png
    }

    private fun hideForShot() {
        val tv = overlayView ?: return         // 창 없으면 할 것 없음
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post { tv.visibility = View.GONE; latch.countDown() }  // 메인스레드서 숨김
        latch.await()                          // 숨김이 '적용'될 때까지 대기
        Thread.sleep(32)                       // 컴포지터가 오버레이 없는 프레임 그릴 시간(~2프레임)
    }
    private fun showAfterShot() {
        val tv = overlayView ?: return
        ui.post { tv.visibility = View.VISIBLE }
    }
```

> `screenshot()`은 백그라운드(runAgent) 스레드에서 호출되므로 `latch.await()`/`Thread.sleep`이 UI를 막지 않음.
> `ui.post{ GONE }` → latch로 반영 확인 → 32ms 대기 → 캡처. 이 순서라야 오버레이가 안 찍힘.
> **소켓 SHOT 경로도 같은 `capturePngBlocking`을 쓰지만 그건 `screenshot()`을 안 거치므로 영향 없음**(SHOT엔 오버레이가 찍힐 수 있으나 디버그용이라 무관).

---

## 3. `MainActivity.kt` — 오버레이 권한 안내

실행(runBtn) 직전에, 접근성 null 체크 **다음**에 추가:
```kotlin
            if (!Settings.canDrawOverlays(this)) {
                result.text = "‘다른 앱 위에 표시’ 권한이 필요합니다.\n설정에서 켠 뒤 다시 실행하세요."
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
```

import 추가:
```kotlin
import android.net.Uri
```
> `Settings`는 이미 import돼 있음. 이 체크를 넣으면 권한 없을 때 실행을 막고 설정으로 보냄
> (없어도 서비스에서 `canDrawOverlays`로 skip하니 크래시는 안 나지만, 안내가 있는 게 친절).

---

## 4. 테스트

1. Build ▶ 설치 → 실행 누르면 **"다른 앱 위에 표시" 설정 화면**으로 감 → **Android_run 허용** → 뒤로.
2. 다시 실행 → 조작 대상 앱 위 상단에 **검은 반투명 창**이 뜨고 **지금 턴 한 줄**(`[턴 N] …`)로 갱신.
3. 끝나면 결과 줄 뜨고 4초 뒤 사라짐 + 상태바 완료 알림.

---

## 5. 함정
| 증상 | 원인/해결 |
|---|---|
| 창이 안 뜸 | ‘다른 앱 위에 표시’ 권한 OFF → 설정에서 ON. `canDrawOverlays`가 false면 skip됨 |
| 조작 대상 앱 타이핑이 깨짐 | 오버레이가 포커스 가져감 → `FLAG_NOT_FOCUSABLE` 확인 |
| `addView` 크래시(BadTokenException) | 권한 없이 add 시도 → `canDrawOverlays` 가드 확인 |
| 창이 안 사라짐 | hideOverlay 미호출/예외 → onDestroy·onUnbind에도 hideOverlay 넣음 |
| 창이 두 번 뜸 | 이전 실행 창이 안 닫힘 → showOverlay의 `overlayView?.let{ 재사용 }` 가드로 방지 |
| 터치가 앱에 안 먹음 | `FLAG_NOT_TOUCH_MODAL` 확인(오버레이 밖 터치는 앱으로 통과) |
| 스크린샷에 오버레이가 찍혀 모델이 헷갈림 | §2-f의 `hideForShot`/`showAfterShot` 누락 → screenshot()에서 숨김·복원 확인 |
| 캡처에 오버레이가 가끔 남음 | 프레임 대기 부족 → `Thread.sleep(32)`를 48~64ms로 늘림(느린 기기) |

> (선택) 창을 드래그로 옮기고 싶으면 tv에 OnTouchListener로 `lp.y` 갱신 후 `updateViewLayout`. 지금은 상단 고정.
