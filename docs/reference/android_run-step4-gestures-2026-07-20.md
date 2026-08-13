# 하이브리드 4단계 — 실제 제스처 (연습 종료 → 실제 프로젝트 전환)

> 3단계까지: 폰이 화면 캡처(`takeScreenshot`)하고, PC가 소켓으로 받아(`SHOT`→PNG)
> `A11yBridge.screenshot()`으로 CU에 넣는 파이프까지 검증(연습앱 Testempty).
> **4단계 목표:** ① 연습앱 → **새 정식 프로젝트**(`Android_run`, 클래스 `a11service`)로 이식,
> ② 폰 프로토콜에 **실제 동작**(TAP/SWIPE/TEXT/BACK…) 추가,
> ③ PC `A11yBridge`의 액션 메서드를 no-op → 진짜 소켓 명령으로 채움,
> ④ `main.py`가 **환경변수로 ADB/A11y 선택**하게 하고 repo에 정식 커밋.
> 형식: 완성 코드 + 줄별 설명 주석(직접 옮겨 적으며 이해). 로드맵: `accessibility-migration-guide.md`.

결정(2026-07-20): 앱=새 정식 프로젝트(`Android_run`, 클래스 `a11service`), PC 코드=repo 정식 커밋, 오늘=바로 4단계.
> 패키지명은 프로젝트 생성 시 값 그대로 사용(사용자 확인 예정). 아래 예시의 `com.cua.a11y`는 그 패키지로 바꿔 읽을 것.

---

## §0. 오늘의 큰 그림

```
매 턴:
 PC(A11yBridge)                      폰(접근성 앱 = 서버)
   screenshot()  ── "SHOT"     ──▶   takeScreenshot → PNG
                 ◀── [len][PNG] ──
   CU 판단(0~1000 좌표)
   click(x,y) → denormalize → 픽셀
                 ── "TAP px py"──▶    dispatchGesture(탭)
                 ◀── "OK"       ──    (제스처 완료까지 대기)
   (다음 턴 screenshot 으로 결과 확인)
```

핵심: **CU 판단부(`cua/`)는 한 줄도 안 바뀐다.** 바뀌는 건 실행부(폰 코틀린 + PC `A11yBridge`)뿐.

---

## §1. 새 프로젝트로 이식 (Testempty → Android_run / 클래스 a11service)

연습앱을 버리고 **이름부터 제대로** 된 새 프로젝트를 만든다. 코드는 어제 검증된 걸 그대로 옮긴다.

### 1-1. 새 프로젝트 생성 (Android Studio) — 완료
- **New Project → Empty Views Activity** (Kotlin)
- Name: **`Android_run`**
- Package name: 프로젝트 생성값 (아래 예시의 `<pkg>` 로 표기)
- **Minimum SDK: API 30** (어제와 동일 — `takeScreenshot`/`ACTION_IME_ENTER` 하한)
- Language: Kotlin

### 1-2. 서비스 클래스 옮기기
`app/src/main/java/<pkg 경로>/a11service.kt` 새로 만들고, 어제 Testempty의
캡처+소켓서버 내용을 붙여넣되 **맨 윗줄 패키지 + 클래스명만 교체**:
```kotlin
package <pkg>              // ← 새 프로젝트 패키지명
...
class a11service : AccessibilityService() { ... }   // 클래스명 a11service
```
> **Manifest의 `android:name`(§1-3)이 이 클래스명 `.a11service`와 정확히 일치**해야 한다.

### 1-3. Manifest (어제와 동일 구조, 이름만 새 것)
`AndroidManifest.xml` `<manifest>` 안, `<application>` **위**:
```xml
<uses-permission android:name="android.permission.INTERNET" />
```
`<application ...>` **여는 태그가 완전히 닫힌 뒤**, `<activity>`와 형제로 `<service>` 추가:
```xml
<service
    android:name=".a11service"                                 <!-- 클래스명과 정확히 일치 -->
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### 1-4. 접근성 설정 XML (⚠️ 속성은 여는 태그 안, self-close)
`app/src/main/res/xml/accessibility_service_config.xml` (New → Android Resource File,
Resource type: **XML**, Root element: `accessibility-service`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:notificationTimeout="100" />
```
> **어제 SecurityException의 진짜 원인 = 속성을 `>`로 닫은 뒤 본문에 나열해서 전부 무시됨.**
> 반드시 위처럼 **여는 태그 안에 속성 다 넣고 `/>`로 self-close.** 자식 태그 없음.
> `canPerformGestures="true"` 가 이번 4단계(탭/스와이프)의 필수 권한 — 빠지면 dispatchGesture 무시됨.

### 1-5. 설치 & 접근성 켜기
- Run ▶ 로 폰에 설치
- 설정 → 접근성 → (앱 이름) → **켜기**
- Logcat 필터 `A11y` 에서 `connected` + `server listening on 8080` 확인
- (재설치하면 접근성 꺼질 수 있음 — 매번 다시 켜기)

**§1 성공 기준:** 새 패키지 앱에서 어제와 동일하게 `server listening on 8080` 로그.
`py test_shot.py`(폰 IP만 맞추면) → `screen.png` 저장까지 그대로 재현되면 이식 완료.

---

## §2. 폰 프로토콜 확장 — 실제 동작 명령 (코틀린)

### 프로토콜 (한 줄 명령 → OK 응답, SHOT만 PNG 응답)
```
SHOT              → [len4][PNG]            (기존)
TAP x y           → "OK\n"                 (한 점 탭)
LONGPRESS x y ms  → "OK\n"                 (길게 누르기)
SWIPE x1 y1 x2 y2 ms → "OK\n"             (드래그/스와이프)
TEXT <base64>     → "OK\n"                 (포커스된 입력창에 텍스트)
ENTER             → "OK\n"                 (IME 액션 = 검색/완료)
BACK / HOME / RECENTS → "OK\n"            (전역 동작)
OPEN <package>    → "OK\n"                 (패키지로 앱 실행)
```
> 좌표(x,y)는 **실제 픽셀**. PC가 CU의 0~1000 좌표를 denormalize 해서 보낸다.

### 2-1. import 추가 (파일 상단)
```kotlin
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
import android.util.Base64
import android.content.Intent
import java.net.Socket
import java.util.concurrent.CountDownLatch
```

### 2-2. 서버 루프: SHOT 하나만 처리하던 걸 → 명령 분기로 교체
`startServer()` 안의 `try { ... }` 블록(명령 읽고 처리하는 부분)을 아래로 교체:
```kotlin
try {
    // 한 줄 명령을 읽는다. readLine()은 '\n'까지 읽고 개행은 뗀다.
    val line = client.getInputStream().bufferedReader().readLine() ?: return@use
    Log.d("A11y", "cmd: $line")
    val p = line.trim().split(" ")               // 공백으로 토큰 분리. p[0]=명령어

    when (p[0]) {
        "SHOT" -> {
            val png = capturePngBlocking()
            val out = client.getOutputStream()
            val n = png.size
            out.write(byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),
                                  (n ushr 8).toByte(), n.toByte()))   // 길이 4바이트(빅엔디안)
            out.write(png); out.flush()
            Log.d("A11y", "sent png $n bytes")
        }
        "TAP"       -> { tapBlocking(p[1].toFloat(), p[2].toFloat()); ackOk(client) }
        "LONGPRESS" -> { swipeBlocking(p[1].toFloat(), p[2].toFloat(),
                                       p[1].toFloat(), p[2].toFloat(), p[3].toLong()); ackOk(client) }
        "SWIPE"     -> { swipeBlocking(p[1].toFloat(), p[2].toFloat(),
                                       p[3].toFloat(), p[4].toFloat(), p[5].toLong()); ackOk(client) }
        "TEXT"      -> { setText(String(Base64.decode(p[1], Base64.DEFAULT))); ackOk(client) }
        "ENTER"     -> { imeEnter(); ackOk(client) }
        "BACK"      -> { performGlobalAction(GLOBAL_ACTION_BACK); ackOk(client) }
        "HOME"      -> { performGlobalAction(GLOBAL_ACTION_HOME); ackOk(client) }
        "RECENTS"   -> { performGlobalAction(GLOBAL_ACTION_RECENTS); ackOk(client) }
        "OPEN"      -> { openApp(p[1]); ackOk(client) }
        else        -> { Log.e("A11y", "unknown cmd: $line"); ackOk(client) }
    }
} catch (e: Exception) {
    Log.e("A11y", "client error: ${e.message}")
} finally {
    client.close()
}
```
> `return@use`가 아니라 현재 구조에선 `continue`가 맞을 수 있음 — 어제 코드는 `try/finally` 안에서
> `client.close()`만 하고 while로 되돌아감. `readLine()`이 null이면 그냥 finally로 떨어지게 두면 된다.
> (즉 `?: return@use` 대신 어제처럼 null 체크 없이 두거나, `val line = ... ?: ""` 로 받아 else로 흘려도 OK.)

### 2-3. 헬퍼 메서드들 (클래스 안, capturePngBlocking 옆에 추가)
```kotlin
// "OK\n" 한 줄 응답. PC는 이걸 받고 액션 완료로 간주.
private fun ackOk(client: Socket) {
    val out = client.getOutputStream()
    out.write("OK\n".toByteArray()); out.flush()
}

// 제스처는 비동기(콜백). 서버 백그라운드 스레드에서 '완료까지' 기다린 뒤 OK를 보내야
// 다음 SHOT이 변화된 화면을 찍는다 → 스크린샷과 같은 CountDownLatch 패턴.
private fun dispatchBlocking(gesture: GestureDescription) {
    val latch = CountDownLatch(1)
    dispatchGesture(gesture, object : GestureResultCallback() {
        override fun onCompleted(d: GestureDescription?) { latch.countDown() }
        override fun onCancelled(d: GestureDescription?) { latch.countDown() }
    }, null)
    latch.await()
}

// 한 점 탭: 그 점에서 60ms 짧게 눌렀다 뗀다.
private fun tapBlocking(x: Float, y: Float) {
    val path = Path().apply { moveTo(x, y) }
    val stroke = GestureDescription.StrokeDescription(path, 0, 60)
    dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
}

// 스와이프/드래그/롱프레스: (x1,y1)→(x2,y2)를 durMs 동안. 롱프레스는 시작=끝, 긴 시간.
private fun swipeBlocking(x1: Float, y1: Float, x2: Float, y2: Float, durMs: Long) {
    val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
    val stroke = GestureDescription.StrokeDescription(path, 0, durMs)
    dispatchBlocking(GestureDescription.Builder().addStroke(stroke).build())
}

// 현재 포커스된 입력창(EditText 등)에 텍스트를 '통째로' 세팅.
// (한 글자씩 타이핑이 아니라 ACTION_SET_TEXT로 값 자체를 넣음 → 한글/특수문자 안전)
private fun setText(text: String) {
    val root = rootInActiveWindow ?: return
    val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
    val args = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}

// 입력창의 IME 액션(검색/완료/이동) 실행 = 소프트 엔터. API 30+.
private fun imeEnter() {
    val root = rootInActiveWindow ?: return
    val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
}

// 패키지명으로 앱 실행. (앱 이름→패키지 매핑은 안 함 → open_app은 package_name일 때만 동작)
private fun openApp(pkg: String) {
    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
```
> **필수 권한 재확인:** 탭/스와이프 = `canPerformGestures`, 포커스/노드 접근 = `canRetrieveWindowContent`.
> 둘 다 §1-4 XML에 이미 넣음. 빠지면 dispatchGesture/findFocus가 조용히 무시됨.

**§2 성공 기준:** 앱 재설치·접근성 켜고, PC에서 임시로 `TAP 500 1000` 한 줄 보내
화면 그 위치가 눌리면(그리고 `OK` 수신) 제스처 경로 통함.

---

## §3. PC `A11yBridge` — 액션 메서드 실동작 (파이썬)

3단계에서 만든 `live/a11y_bridge.py`의 **no-op 액션들을 진짜 명령 전송으로** 교체.
`screenshot()`/`_recv_exact`/`_png_size`/`__init__`는 그대로 두고, 아래를 반영.

```python
import base64   # 파일 상단 import 목록에 추가

    # ── 명령 전송 유틸 (SHOT 외 모든 액션 공용) ──
    def _send_cmd(self, line: str) -> bytes:
        """한 줄 명령을 보내고 폰의 'OK' 응답을 받아온다. 매 호출 새 연결(폰 서버가 요청 1건 후 닫는 구조와 짝)."""
        s = socket.socket(); s.settimeout(10)
        s.connect((self.phone_ip, self.port))
        try:
            s.sendall((line + "\n").encode())   # 끝에 개행 — 폰 readLine 기준
            return s.recv(16)                    # "OK\n" 정도만 읽으면 됨
        finally:
            s.close()

    # ── CU 액션 (실제 동작) ──
    def click(self, x, y, **_):
        px, py = denormalize(x, y, self.width, self.height)
        self._send_cmd(f"TAP {int(px)} {int(py)}")

    def type(self, text, press_enter=False, **_):
        b64 = base64.b64encode(text.encode()).decode()   # 공백/한글이 명령 파싱을 안 깨게 base64
        self._send_cmd(f"TEXT {b64}")
        if press_enter:
            self._send_cmd("ENTER")

    def long_press(self, x, y, seconds=2, **_):
        px, py = denormalize(x, y, self.width, self.height)
        self._send_cmd(f"LONGPRESS {int(px)} {int(py)} {int(seconds * 1000)}")

    def drag_and_drop(self, start_x, start_y, end_x, end_y, **_):
        x1, y1 = denormalize(start_x, start_y, self.width, self.height)
        x2, y2 = denormalize(end_x, end_y, self.width, self.height)
        self._send_cmd(f"SWIPE {int(x1)} {int(y1)} {int(x2)} {int(y2)} 300")

    def press_key(self, key, **_):
        m = {"back": "BACK", "home": "HOME", "enter": "ENTER", "app_switch": "RECENTS"}
        cmd = m.get(str(key).lower())
        if cmd:
            self._send_cmd(cmd)
        else:
            print(f"[a11y] press_key 미지원: {key}")

    def go_back(self, **_):
        self._send_cmd("BACK")

    def open_app(self, app_name=None, package_name=None, **_):
        # 접근성엔 '이름→패키지' 매핑이 없어서 package_name일 때만 실행.
        # app_name만 오면 홈으로 가서 CU가 아이콘을 직접 탭하도록 유도(대안).
        if package_name:
            self._send_cmd(f"OPEN {package_name}")
        else:
            print(f"[a11y] open_app: package_name 없음(app_name={app_name}) → HOME 후 아이콘 탭 유도")
            self._send_cmd("HOME")

    def list_apps(self, **_):
        return {"apps": "No app list over accessibility (noop)."}

    def wait(self, seconds=1, **_):
        time.sleep(seconds)

    def take_screenshot(self, **_):
        return None   # 어차피 다음 턴에 새 SHOT
```
> `ensure_adb_keyboard`/`restore_keyboard`는 3단계처럼 빈 메서드 유지(접근성 경로는 ADBKeyboard 불필요).

---

## §4. `main.py` — 브리지 연결 + 커밋

> **실제 채택(2026-07-20):** 지금 `main.py`는 **A11y 전용**으로 단순화됨(아래 "실제 코드").
> ADB/A11y 를 함께 고르는 `CU_BRIDGE` 분기는 **선택사항**(나중에 케이블도 쓰고 싶을 때). 지금은 안 넣어도 됨.

### 실제 채택 코드 (A11y 전용, 파일명 `live/a11service_bridge.py`)
```python
from a11service_bridge import A11yBridge   # main.py가 live/ 안에서 실행돼 같은 폴더 모듈로 import
...
    phone_ip = os.environ.get("PHONE_IP")
    if not phone_ip:
        raise SystemExit('PHONE_IP 미설정. 예: $env:PHONE_IP="192.168.0.55"; py live/main.py "작업"')
    bridge = A11yBridge(phone_ip)
```

### (선택) ADB/A11y 둘 다 고르기 — 나중에 케이블도 유지하려면
지금(고정):
```python
from live.adb_bridge import ADBBridge
...
    bridge = ADBBridge()
```
교체(선택 가능하게):
```python
import os   # 이미 있으면 생략
...
    # 실행부 선택: CU_BRIDGE=a11y 면 무선(접근성), 기본은 adb(케이블).
    if os.environ.get("CU_BRIDGE", "adb").lower() == "a11y":
        # 폰 IP는 하드코딩하지 않는다(폰마다·재접속마다 달라짐 = DHCP).
        # 환경변수 PHONE_IP로 받고, 없으면 명확히 안내하고 종료.
        phone_ip = os.environ.get("PHONE_IP")
        if not phone_ip:
            raise SystemExit(
                "PHONE_IP 미설정. 폰 Wi-Fi 설정에서 IP 확인 후:\n"
                '  PowerShell:  $env:CU_BRIDGE="a11y"; $env:PHONE_IP="192.168.0.55"; py live/main.py "작업"'
            )
        from live.a11y_bridge import A11yBridge
        bridge = A11yBridge(phone_ip)
    else:
        from live.adb_bridge import ADBBridge
        bridge = ADBBridge()
```
- 나머지 루프(`ensure_adb_keyboard`/`width·height`/`screenshot()`/`getattr` 디스패치/`restore_keyboard`)는 **한 줄도 안 바뀜** — 계약을 지킨 이점.
- **IP 하드코딩 없음(방식 A):** 실행할 때 `PHONE_IP`로 그때그때 폰 IP를 넘긴다. 폰 IP는 **설정 → Wi-Fi → 연결된 네트워크 상세**에서 확인.
- 실행:
  - 케이블(기존): `py live/main.py "작업"`
  - 무선(접근성): PowerShell `$env:CU_BRIDGE="a11y"; $env:PHONE_IP="192.168.0.55"; py live/main.py "작업"`
- **후속 개선(방식 B, 나중):** 폰이 UDP로 자기 IP를 방송 → PC가 자동 탐색해 `PHONE_IP` 없이도 붙게. 파이프 검증 후 편의 기능으로 추가 예정.

**커밋(결정 2):** `live/a11y_bridge.py`(신규) + `live/main.py`(선택 로직) 을 baseline에 커밋.
step 문서는 2026-08-13부터 깃으로 공유한다(파일명 끝의 날짜 = 작성일).

---

## §5. 실행 & 확인 (전 구간)

1. 폰: 새 앱 접근성 ON, Logcat `server listening on 8080`.
2. PC: `$env:CU_BRIDGE="a11y"; $env:PHONE_IP="폰IP"; py live/main.py "설정 앱을 열고 Wi-Fi를 켜"`
3. 기대: 해상도 출력 → `[턴 n]` 액션 로그 → **폰 화면이 실제로 눌리고 바뀜** → 다음 턴이 바뀐 화면을 캡처.
4. 몇 턴 실제로 동작하면 하이브리드 **실동작 완성**. `Ctrl+C`로 중단.

**성공 기준:** no-op이 아니라 **폰이 실제로 탭/입력/뒤로가기를 수행**하고, 그 결과가 다음 스크린샷에 반영됨.

---

## §6. 함정 & 현재 한계
- **`canPerformGestures` 빠지면 탭이 조용히 무시** — 클릭했는데 화면이 안 바뀌면 XML 먼저 의심.
- **좌표 스케일**: PNG 해상도(=`_png_size`)와 실제 디스플레이가 같아야 denormalize가 맞음. 배율/멀티디스플레이면 어긋남.
- **`setText`는 포커스된 입력창 필요** — 먼저 입력창을 탭해 포커스를 준 뒤 `type`이 와야 함(대개 CU가 그 순서로 냄).
- **`open_app`은 package_name일 때만** 실제 실행. app_name만이면 HOME으로 가서 CU가 아이콘 탭하게 유도.
- **제스처 완료 대기**: `dispatchBlocking`의 latch로 콜백까지 기다림 → 애니메이션이 길면 다음 SHOT이 이른 화면을 찍을 수 있음. 필요하면 액션 뒤 `wait`/`SETTLE_SEC` 늘리기.
- **한 명령 = 한 연결**: 폰 서버가 요청 1건 후 `client.close()` 하는 구조라 정상. 연결 재사용은 후순위 최적화.
- **엔터/검색**: `ENTER`는 IME 액션(`ACTION_IME_ENTER`, API30+). 앱이 이 액션을 안 물면 무반응일 수 있음.
