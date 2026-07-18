# Android 접근성(AccessibilityService) — 무선(케이블리스) 동작 레퍼런스

> 프로젝트 방향 전환 근거 문서. 지금까지는 **PC + USB 케이블 + ADB**로 폰을 조종했다.
> 목표는 **케이블/ADB 없이**, 폰에 설치한 앱이 스스로 화면을 읽고 조작하게 만드는 것.
> 그 열쇠가 Android **AccessibilityService**다. 출처: `참고 문서 링크.txt`의 접근성 링크 5종
> (Google Accessibility for developers, developer.android.com의 apps/principles/testing/service).
> CU 판단 코어 스펙은 [`gemini-computer-use.md`](gemini-computer-use.md) 참고.

---

## 0. 왜 이게 방향을 바꾸는가 (핵심)

**현재 구조 (ADB):**
```
PC(파이썬) ──USB/ADB──> 폰
  screenshot: adb screencap
  action:     adb input tap/text, dispatch...
  좌표변환:   CU 0–1000 → 픽셀 (denormalize)
```
CU 판단은 PC의 파이썬(`cua`/`live`)이 돌리고, 실행만 폰에서 일어난다. **케이블(또는 adb tcpip 무선디버깅)이 반드시 필요.**

**목표 구조 (AccessibilityService):**
```
폰 안의 앱(하나) 이 전부 담당
  screenshot: AccessibilityService.takeScreenshot() (API 30+)  또는 MediaProjection
  판단:       Gemini CU API 호출 (폰에서 직접 HTTPS)
  action:     dispatchGesture(tap/swipe) · performAction(CLICK) · performGlobalAction(BACK/HOME)
```
→ **PC도, 케이블도, ADB도 필요 없다.** 폰이 인터넷만 되면 독립 동작.

> ⚠️ **이건 단순 리팩터가 아니라 실행 계층의 이주다.** `live/adb_bridge.py`(파이썬+ADB)가 하던 일을
> **온디바이스 Android 앱(Kotlin/Java)** 이 대신하게 된다. `cua/`의 "판단 코어" 개념(스크린샷+목표→액션)은
> 그대로 살지만, 언어·런타임이 파이썬에서 안드로이드 앱으로 옮겨간다.
> 아래 **§5 마이그레이션**에서 선택지를 정리.

---

## 1. AccessibilityService 등록 (3요소)

### (a) Manifest 선언
```xml
<service android:name=".MyA11yService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    android:label="@string/service_label">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```
- `BIND_ACCESSIBILITY_SERVICE` 권한 필수(시스템만 바인딩).
- **런타임 권한 팝업이 아님** → 사용자가 **설정 > 접근성 > 서비스명**에서 직접 켜야 한다(§4).

### (b) XML 설정 `res/xml/accessibility_service_config.xml`
```xml
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRequestTouchExplorationMode"
    android:canRetrieveWindowContent="true"    <!-- 화면 노드 읽기 -->
    android:canPerformGestures="true"          <!-- 탭/스와이프 주입 -->
    android:notificationTimeout="100" />
```
| 속성 | 우리 용도 |
|---|---|
| `canRetrieveWindowContent="true"` | 화면 UI 트리(노드) 읽기 — **필수** |
| `canPerformGestures="true"` | `dispatchGesture`로 좌표 탭/스와이프 — **필수** |
| `accessibilityEventTypes` | 화면변화 감지. `typeAllMask`는 무겁다 → 필요한 것만 권장 |

### (c) 서비스 클래스
```kotlin
class MyA11yService : AccessibilityService() {
    override fun onServiceConnected() { /* setServiceInfo로 동적 설정 가능 */ }
    override fun onAccessibilityEvent(e: AccessibilityEvent) { /* 화면변화 훅 */ }
    override fun onInterrupt() {}
}
```

---

## 2. 화면 읽기 (ADBBridge.take_screenshot / uiautomator 대체)

두 가지 경로 — CU에는 **스크린샷**이 필수이고, 노드 트리는 보조.

### (a) 스크린샷 — CU 입력용
- **`AccessibilityService.takeScreenshot(displayId, executor, callback)`** (API 30+):
  콜백으로 `ScreenshotResult` → `HardwareBuffer` → `Bitmap`. ADB `screencap`을 완전 대체.
- 대안: **MediaProjection**(사용자 1회 동의로 화면 캡처) — API 낮거나 다중 디스플레이 이슈 시.

### (b) UI 노드 트리 — 좌표/텍스트 보조
```kotlin
val root: AccessibilityNodeInfo? = rootInActiveWindow
val rect = Rect(); node.getBoundsInScreen(rect)     // 화면상 실제 픽셀 좌표
val cx = rect.centerX(); val cy = rect.centerY()
node.text; node.contentDescription; node.isClickable // 라벨·상태
node.findAccessibilityNodeInfosByText("검색")         // 텍스트로 노드 찾기
```
→ CU가 좌표를 주지만, 노드 트리로 **좌표 검증/보정**하거나 텍스트 기반 클릭도 가능.

---

## 3. 액션 실행 (ADBBridge 메서드 ↔ 접근성 API 매핑)

CU가 돌려주는 액션(같은 이름의 `ADBBridge` 메서드로 디스패치되던 것)을 접근성 API로 옮기는 표:

| CU 액션 | 지금 (ADB) | 접근성(AccessibilityService) |
|---|---|---|
| `click(x,y)` | `adb input tap` | `dispatchGesture()` (짧은 탭 Path) 또는 노드 `performAction(ACTION_CLICK)` |
| `long_press(x,y)` | `adb input swipe`(지연) | `dispatchGesture()` StrokeDescription duration↑ |
| `drag_and_drop` | `adb input swipe` | `dispatchGesture()` 두 점 Path |
| `type(text)` | ADBKeyboard broadcast | 포커스 노드 `performAction(ACTION_SET_TEXT, bundle)` 또는 IME 입력 |
| `press_key` / `go_back` | `adb input keyevent` | `performGlobalAction(GLOBAL_ACTION_BACK)` |
| (home) | keyevent HOME | `performGlobalAction(GLOBAL_ACTION_HOME)` |
| (recents) | keyevent APP_SWITCH | `performGlobalAction(GLOBAL_ACTION_RECENTS)` |
| `open_app` | `adb shell monkey/am` | 앱 실행 Intent(패키지) — 접근성 아님, 일반 Android API |
| `take_screenshot` | `adb screencap` | `takeScreenshot()` (§2a) |
| `wait` | sleep | 그대로 (앱 내 delay) |

### 좌표 탭 (dispatchGesture) 최소 예
```kotlin
fun tap(x: Float, y: Float) {
    val p = Path().apply { moveTo(x, y); lineTo(x, y) }
    val stroke = GestureDescription.StrokeDescription(p, 0, 50) // 50ms 탭
    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
}
```
- 스와이프: `moveTo(x1,y1); lineTo(x2,y2)` + duration 길게.
- **좌표는 여전히 실제 픽셀** → CU의 0–1000 정규좌표를 `denormalize`(화면 wh 기준)로 변환하는 로직은 **그대로 유효**. 화면 크기는 `WindowManager`/`DisplayMetrics`로 얻는다(ADB `wm size` 대체).

### 글로벌 액션
```kotlin
performGlobalAction(GLOBAL_ACTION_BACK)     // 뒤로
performGlobalAction(GLOBAL_ACTION_HOME)     // 홈
performGlobalAction(GLOBAL_ACTION_RECENTS)  // 최근 앱
// API 30+: GLOBAL_ACTION_TAKE_SCREENSHOT 도 있음
```

---

## 4. 사용자가 켜는 흐름 (권한 모델)

- 접근성 서비스는 **런타임 권한 다이얼로그로 못 켠다.** 반드시:
  **설정 앱 → 접근성 → [우리 서비스] → 켜기**. 앱에서 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 인텐트로 안내 가능.
- 켜지면 시스템이 서비스를 바인딩하고 `onServiceConnected()` 호출.
- **주의**: 접근성 권한은 강력해서 Play 스토어 정책·사용자 신뢰 이슈가 있다. 데모/연구 앱은 사이드로드로 충분.

---

## 5. 우리 코드 마이그레이션 선택지

`cua/`(판단) + `live/`(ADB 실행)의 파이썬 구조를 어디까지 옮길지 3안:

1. **풀 온디바이스 (권장 최종형)** — 안드로이드 앱 1개가 스크린샷·CU호출·제스처까지 전부. 케이블·PC 완전 제거.
   비용: Kotlin으로 `live/adb_bridge.py` 재작성 + CU API를 안드로이드에서 호출. `cua`의 판단 로직은 이식 대상.
2. **하이브리드(과도기)** — 폰 안 접근성 앱은 "실행기+스크린샷 서버"만 하고, 판단은 여전히 PC 파이썬이 **Wi-Fi(HTTP)** 로 지시.
   케이블은 없애되 파이썬 자산(`cua`)을 재사용. ADB만 Wi-Fi 소켓으로 대체하는 셈.
3. **무선 ADB (최소 변경, 진짜 목표 아님)** — `adb tcpip 5555` + `adb connect <폰IP>`. 케이블은 빠지지만 여전히 ADB·PC 의존.
   접근성 안 씀. "유선만 제거"가 목적이면 즉효지만, 사용자가 원한 접근성 방향은 아님.

> 판단 근거: 사용자가 명시적으로 **"google accessibility 활용, 유선 연결 없이"** 라고 했으므로 **1안(장기) / 2안(과도기)** 가 정답 라인. 3안은 참고용.

---

## 6. 검증(테스트) 도구 — 개발 중 활용

- **UI Automator Viewer / Layout Inspector**: 현재 화면 노드 트리·좌표 확인(우리 dispatchGesture 좌표 디버깅에 유용).
- **Accessibility Scanner**(Play): 라벨·터치영역 점검.
- **TalkBack**: 우리 서비스가 아니라 "노드가 접근성에 어떻게 보이는지" 감 잡는 용도.
- 회귀: 안드로이드 계측 테스트(Espresso/Compose test) 또는 실제 기기 시나리오.

---

## 7. 한 줄 요약

**ADB로 밖에서 찌르던 것을, 폰 안 접근성 서비스가 안에서 직접 한다.**
CU의 판단(스크린샷→액션)·정규좌표 변환 개념은 그대로. 바뀌는 건 **실행 계층의 언어·런타임(파이썬+ADB → Kotlin 접근성 앱)** 과 **권한 모델(케이블 → 설정에서 접근성 켜기)**.
