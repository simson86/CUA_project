# 접근성 전환 실전 가이드 — "이 파일 이 함수를 이렇게 바꾼다"

> [`android-accessibility.md`](android-accessibility.md)(API 스펙)의 **실전 편**.
> 우리 현재 코드(`live/adb_bridge.py`, `live/main.py`, `cua/`)를 기준으로,
> **어디를 무엇으로 바꾸면 케이블리스가 되는지**를 파일·함수 단위로 매핑한다.
>
> ⚠️ **이 문서는 학습용 지도(map)다. 코드는 직접 채워 넣으며 배우도록** 뼈대와 TODO만 둔다.
> 완성 코드를 그대로 붙여넣는 문서가 아니다. (프로젝트 목적 = 구현 + 학습)

---

## 0. 먼저 큰 그림: 무엇이 어디로 가나

현재 3덩이:
- **`cua/`** = 판단 코어 (스크린샷+목표 → CU 액션). ADB를 전혀 모름.
- **`live/adb_bridge.py`** = 실행부 (ADB로 캡처·탭·입력). ← **여기가 통째로 바뀐다**
- **`live/main.py`** = 루프 (캡처→판단→실행 반복).

전환 후:
| 덩이 | ①풀 온디바이스 | ②하이브리드(권장 시작점) |
|---|---|---|
| `cua/` 판단 | Kotlin으로 이식 | **그대로 PC에 유지** |
| `adb_bridge.py` 실행 | Kotlin 접근성 서비스로 대체 | 폰 앱(접근성) + PC는 소켓 호출로 지시 |
| `main.py` 루프 | 폰 앱 안으로 | **PC에 유지**, ADB 호출부만 소켓 호출로 교체 |

> **이 가이드는 ②(하이브리드)를 1차 목표로 삼는다.** 이유: `adb_bridge.py`의 인터페이스만
> "ADB → 네트워크"로 갈아끼우면 되고, 어려운 `cua`/`main` 루프는 손대지 않아 리스크가 작다.
> ②가 돌면 §6에서 ①로 넘어가는 델타만 추가하면 된다.

---

## 1. 핵심 발상 — `ADBBridge`의 "계약"은 그대로 두고 속만 바꾼다

`main.py`는 `bridge`를 이렇게만 쓴다 (이 인터페이스가 **계약**):
```
bridge.width, bridge.height          # 화면 크기
bridge.screenshot() -> bytes(png)    # 캡처
getattr(bridge, action.name)(**args) # click/type/... 디스패치
bridge.ensure_adb_keyboard() / restore_keyboard()
```
→ **이 계약을 똑같이 지키는 새 클래스 `A11yBridge`를 만들면 `main.py`는 거의 안 고쳐도 된다.**
즉 목표는 "`adb_bridge.py`의 각 메서드를, 몸통만 접근성/네트워크로 바꾼 쌍둥이"를 만드는 것.

> 학습 포인트: **인터페이스(계약) vs 구현(속)의 분리**. 지금 코드가 이미 이렇게 돼 있어서
> 전환이 깔끔하다. `main.py`가 ADB를 직접 안 부르고 `bridge`를 통해서만 부른 덕분.

---

## 2. 폰 쪽: 접근성 서비스가 제공해야 할 "능력" 목록

폰 안 Android 앱(Kotlin)에 접근성 서비스를 하나 만들고, 아래 능력을 노출한다.
각 능력은 §3의 `adb_bridge.py` 메서드와 1:1 대응.

| 능력 | 접근성 API (스펙은 android-accessibility.md §2·§3) | 대응 CU 액션 |
|---|---|---|
| 화면 캡처 | `takeScreenshot()` → Bitmap → PNG 바이트 | `screenshot()` |
| 화면 크기 | `DisplayMetrics` / `WindowManager` | `bridge.width/height` |
| 탭 | `dispatchGesture()` (짧은 Path) | `click` |
| 롱프레스 | `dispatchGesture()` (duration↑) | `long_press` |
| 스와이프/드래그 | `dispatchGesture()` (두 점 Path) | `drag_and_drop` |
| 텍스트 입력 | 포커스 노드 `performAction(ACTION_SET_TEXT)` 또는 IME | `type` |
| 뒤로/홈/최근 | `performGlobalAction(GLOBAL_ACTION_*)` | `press_key`/`go_back` |
| 앱 실행 | (접근성 아님) `startActivity(launchIntent)` | `open_app` |
| 설치앱 목록 | (접근성 아님) `PackageManager` | `list_apps` |

> ⚠️ 접근성 서비스는 **사용자가 설정 > 접근성에서 직접 켜야** 동작한다(런타임 권한 아님).
> 앱 첫 실행 시 `Settings.ACTION_ACCESSIBILITY_SETTINGS` 인텐트로 안내하는 화면을 두면 좋다.

---

## 3. 메서드별 전환 표 (adb_bridge.py 한 줄씩)

각 항목: **지금(ADB)** → **접근성으로 뭘 호출** → **학습 시 주의점**.
Kotlin은 뼈대만. 몸통 `// TODO(나)` 는 직접 채우며 익힐 것.

### 3.1 `screenshot()` — 가장 중요, 여기서 막히면 다 막힘
- 지금: `adb exec-out screencap -p` → PNG 바이트.
- 접근성: `AccessibilityService.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`
  → `ScreenshotResult` → `HardwareBuffer` → `Bitmap.wrapHardwareBuffer` → `compress(PNG)` → bytes.
- ②에선 이 bytes를 소켓으로 PC에 보냄. ①에선 CU에 바로 첨부.
- 주의: API 30(Android 11)+ 필요. 그 이하면 `MediaProjection`(1회 사용자 동의) 경로.
```kotlin
// 폰 서비스 안
fun captureAsPng(cb: (ByteArray) -> Unit) {
    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
        object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                // TODO(나): HardwareBuffer→Bitmap→PNG bytes 로 변환해 cb 호출
            }
            override fun onFailure(code: Int) { /* TODO(나): 재시도/로그 */ }
        })
}
```

### 3.2 화면 크기 (`_screen_size`, `bridge.width/height`)
- 지금: `adb shell wm size` 파싱.
- 접근성: `resources.displayMetrics.widthPixels/heightPixels` 또는 `WindowManager.currentWindowMetrics`.
- **중요**: `cua.denormalize(x,y,w,h)`가 CU의 0–1000 → 픽셀 변환을 한다. 이 로직은 **그대로 유효**.
  ②면 PC가 계속 denormalize하고 픽셀 좌표만 폰에 전달. ①이면 denormalize를 Kotlin으로 이식.

### 3.3 `click(x, y)` → 탭
- 지금: `denormalize` 후 `adb shell input tap px py`.
- 접근성: 짧은 `dispatchGesture`.
```kotlin
fun tap(px: Float, py: Float) {
    val path = Path().apply { moveTo(px, py); lineTo(px, py) }
    val stroke = GestureDescription.StrokeDescription(path, 0, 50) // 50ms
    // TODO(나): GestureDescription.Builder().addStroke(stroke).build() 를 dispatchGesture 로
}
```
- 주의: `dispatchGesture`는 **비동기**(콜백). ADB `input tap`은 동기였다. 루프에서
  "액션 끝 → 스크린샷"의 타이밍(`SETTLE_SEC`)을 콜백 완료 기준으로 다시 잡아야 함.

### 3.4 `long_press` / `drag_and_drop` → 제스처 duration·경로만 다름
- 롱프레스: 같은 점, `StrokeDescription(path, 0, ms)`의 ms를 길게(예 800~2000).
- 드래그: `moveTo(sx,sy); lineTo(ex,ey)`, duration 300 정도.
- 학습 포인트: ADB의 `input swipe`가 하던 걸 Path+duration으로 표현하는 것뿐.

### 3.5 `type(text, press_enter)` — 접근성에서 제일 성격이 다름
- 지금: ADBKeyboard + base64 broadcast (한글 안전).
- 접근성 옵션 A: **포커스된 편집 노드에 텍스트 세팅**
  `node.performAction(ACTION_SET_TEXT, bundleOf(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to text))`.
  → IME 없이 값 주입. 한글도 OK. 단 "포커스된 EditText 노드"를 먼저 찾아야 함(getRootInActiveWindow 순회).
- 접근성 옵션 B: 우리 ADBKeyboard IME를 계속 쓰되, 전환을 앱이 하도록.
- `press_enter`: 접근성엔 keyevent가 없다 → 노드 `performAction(ACTION_IME_ENTER)`(API 30+) 또는
  "검색/확인" 버튼 노드를 찾아 클릭.
- **주의(설계 결정 필요)**: ACTION_SET_TEXT는 "키 입력"이 아니라 "값 치환"이라, 일부 앱의
  실시간 입력 감지(자동완성 등)와 다르게 동작할 수 있다. 학습하며 실제 앱에서 확인해볼 것.

### 3.6 `press_key` / `go_back` → 글로벌 액션
- 지금: `input keyevent`(home=3, back=4, app_switch=187, enter=66...).
- 접근성 매핑:
  - back → `performGlobalAction(GLOBAL_ACTION_BACK)`
  - home → `performGlobalAction(GLOBAL_ACTION_HOME)`
  - app_switch → `performGlobalAction(GLOBAL_ACTION_RECENTS)`
  - enter → §3.5의 IME_ENTER 참고 (글로벌 액션에 enter는 없음)
  - menu → 대응 없음 → 화면상 메뉴 버튼 노드를 찾아 클릭으로 대체
- 학습 포인트: **keyevent 전체가 접근성에 1:1로 있지 않다.** 없는 건 "노드를 찾아 클릭"으로 우회.

### 3.7 `open_app` — 접근성이 아니라 일반 Android API
- 지금: `adb shell monkey -p <pkg> ...`.
- 대체: `packageManager.getLaunchIntentForPackage(pkg)` → `startActivity(intent)`.
- 접근성 서비스는 `Context`라 `startActivity` 가능(필요 시 `FLAG_ACTIVITY_NEW_TASK`).

### 3.8 `list_apps` — 일반 API
- 지금: `adb shell pm list packages -3`.
- 대체: `packageManager.getInstalledApplications(...)`에서 서드파티 필터.
- 주의: Android 11+는 패키지 가시성(`<queries>`) 제한이 있어 매니페스트 설정 필요할 수 있음.

### 3.9 `wait` — 그대로
- 지금: `time.sleep`. ②면 PC쪽 그대로, ①이면 코루틴 `delay`.

### 3.10 `ensure_adb_keyboard` / `restore_keyboard`
- 접근성 경로에서 §3.5 옵션 A(ACTION_SET_TEXT)를 쓰면 **ADBKeyboard 자체가 불필요** → 이 두 함수는 사라짐.
- 옵션 B로 IME를 계속 쓸 거면 IME 전환을 앱이 담당하도록 재설계.

---

## 4. ②하이브리드: `adb_bridge.py`를 "네트워크 클라이언트"로

②의 실제 작업량은 생각보다 작다. **`main.py`는 그대로**, `ADBBridge`만 `A11yBridge`로 교체:

```
# live/a11y_bridge.py (새 파일, 네가 작성)
class A11yBridge:
    def __init__(self, phone_ip, port): ...      # 소켓/HTTP 연결
    @property
    def width/height: ...                         # 폰에 1회 질의해 캐시
    def screenshot(self) -> bytes:                # 폰에 "캡처" 요청 → PNG 수신
    def click(self, x, y, **_):                   # denormalize 후 폰에 "tap px py" 전송
    def type(self, text, press_enter=False, **_): # 폰에 "settext ..." 전송
    ...  # adb_bridge.py 의 메서드와 '같은 이름·같은 시그니처'
```
- **폰 앱**: 위 요청을 받는 작은 서버(접근성 서비스 + 소켓/HTTP 리스너). 요청 → 대응 접근성 API 실행 → 결과/스크린샷 응답.
- `main.py`에서 `ADBBridge()` → `A11yBridge(phone_ip, port)` **한 줄만** 바꾸면 끝.
  (계약을 지켰으므로 루프·판단은 무변경 → §1의 이점)

> 통신 프로토콜은 단순하게: JSON 한 줄 `{"cmd":"tap","x":..,"y":..}` 요청 / `{"png": base64}` 응답 수준으로 시작.
> 보안(같은 Wi-Fi, 토큰)·재연결은 나중에.

**학습 순서 제안(②):**
1. 폰: 접근성 서비스 켜고 `takeScreenshot`만 되게 → 로그로 PNG 크기 확인.
2. 폰: 소켓 리스너 붙여 "캡처 요청 → PNG 응답" 왕복.
3. PC: `A11yBridge.screenshot()`만 구현해 `main.py`로 1턴 돌려보기(액션은 아직 no-op).
4. `tap` → `type` → 나머지 순으로 하나씩 채우기.

---

## 5. `main.py`에서 손볼 딱 두 곳 (②)

1. **브리지 교체**: `bridge = ADBBridge()` → `bridge = A11yBridge(ip, port)`.
2. **타이밍**: `SETTLE_SEC`(0.6) 대기는 유지하되, `dispatchGesture`가 비동기라 폰 응답이
   "제스처 완료 후"를 보장하도록 폰 쪽에서 콜백 기다렸다 응답하게 설계(그럼 PC는 그대로).
- `ensure_adb_keyboard`/`restore_keyboard` 호출은 §3.10 결정에 따라 제거 또는 대체.
- 나머지(파싱·`previous_interaction_id`·완료 판정)는 **손대지 않는다**.

---

## 6. ①풀 온디바이스로 가는 델타 (②가 돈 다음)

②가 안정되면, 남은 건 "PC가 하던 판단을 폰으로":
- `cua/cu_client.py`의 CU 호출(`client.interactions.create`)을 **Kotlin에서** 호출
  (google-genai의 안드로이드/Java 경로 또는 REST 직접).
- `cua/prompt.py`(시스템 프롬프트)·`actions.py`(파싱·denormalize)를 Kotlin으로 이식.
- `main.py`의 루프(캡처→판단→실행)를 폰 앱의 코루틴 루프로.
- 이 시점엔 소켓도 불필요 → 앱 하나로 완결.
- 학습 관점: ②에서 이미 "실행"을 접근성으로 검증했으니, ①은 "판단 이식"만 남아 범위가 명확.

---

## 7. 막히기 쉬운 지점 미리 표시 (학습 체크포인트)

- **접근성 안 켜짐**: 설정에서 수동 토글 필수. 앱이 죽거나 재설치하면 꺼질 수 있음.
- **`takeScreenshot` 실패/제한**: 일부 화면(FLAG_SECURE, 결제·뱅킹)은 캡처 차단 → 검은 화면. 데모 대상 선정 시 유의.
- **좌표계**: `dispatchGesture`는 **실제 픽셀**. CU는 0–1000. denormalize를 어디서 하든 화면 크기 소스가 폰 기준인지 확인.
- **제스처 비동기**: 완료 콜백 전에 다음 스크린샷 찍으면 이전 화면이 잡힘 → 타이밍 버그. §3.3 주의.
- **텍스트 입력 차이**: ACTION_SET_TEXT는 값 치환이라 키 입력과 미묘히 다름 → 실제 앱에서 확인.
- **패키지 가시성**(Android 11+): `list_apps`/`open_app`이 매니페스트 `<queries>` 없이는 안 보일 수 있음.

---

## 8. 한 줄 정리

**`main.py`와 `cua/`는 지키고, `adb_bridge.py`의 각 메서드 몸통만 접근성 API로 바꾼 쌍둥이를 만든다.**
②는 그 쌍둥이를 "PC↔폰 네트워크"로, ①은 판단까지 폰으로. 지금 코드가 계약/구현을 이미 분리해둔 덕에, 바꾸는 표면은 거의 `adb_bridge.py` 하나다.
