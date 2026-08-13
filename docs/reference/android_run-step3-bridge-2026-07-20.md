# 하이브리드 3단계 — PC에 `A11yBridge` 만들어 `main.py`에 끼우기

> 2단계에서 "폰에 SHOT → PNG 수신"이 됐다. 3단계 목표: 그 소켓 로직을
> **`ADBBridge`와 똑같은 계약**을 가진 `A11yBridge` 클래스로 감싸서,
> `main.py`에서 **한 줄 교체**로 CU 루프를 1턴 돌린다(액션은 아직 no-op).
> 형식: 완성 코드 + 설명 주석. 로드맵: `accessibility-migration-guide.md` §4~5.

---

## 0. 계약(interface) — `main.py`가 bridge에게 요구하는 것

`live/main.py`를 보면 bridge를 이렇게만 쓴다:
```
bridge.ensure_adb_keyboard()          # 시작 시 1회
bridge.width, bridge.height           # 해상도 출력·denormalize용
bridge.screenshot() -> bytes(png)     # 매 턴 캡처
getattr(bridge, action.name)(**args)  # click/type/... 디스패치
bridge.restore_keyboard()             # finally에서 1회
```
→ `A11yBridge`가 **이 이름·시그니처만 그대로** 제공하면 `main.py`는 안 바뀐다.
3단계에선 `screenshot()`만 실제로 폰과 통신하고, 액션 메서드는 **로그만 찍는 no-op**.

> **왜 no-op부터?** "폰 캡처 → CU 판단 → 디스패치까지 파이프가 이어지는지"를
> 먼저 확인하려는 것. 실제 탭/입력(제스처)은 4단계에서 하나씩 붙인다.

---

## 1. `live/a11y_bridge.py` (새 파일, 전체)

```python
"""A11yBridge — 폰의 접근성 앱(소켓 서버)과 통신하는 실행부.

ADBBridge와 '같은 계약'(width/height, screenshot(), CU 액션 메서드)을 지켜서
main.py에서 ADBBridge 대신 그대로 끼울 수 있게 한다.
3단계: screenshot()만 실제 동작. 액션들은 no-op(로그만).
"""

import os
import socket
import struct
import sys
import time

# cua.denormalize 를 쓰기 위해 repo 루트를 경로에 추가 (adb_bridge.py와 동일 패턴)
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from cua import denormalize


class A11yBridge:
    """폰 접근성 서버(ServerSocket 8080)와 소켓으로 통신. ADBBridge의 쌍둥이."""

    def __init__(self, phone_ip, port=8080):
        self.phone_ip = phone_ip
        self.port = port
        # 폰에서 한 장 받아, 화면 픽셀 크기를 PNG 헤더에서 읽는다.
        # (폰에 별도 'SIZE' 명령을 안 만들어도 됨 — 스크린샷 자체가 전체 해상도라서)
        png = self.screenshot()
        self.width, self.height = self._png_size(png)

    # ── 소켓 유틸 ──
    def _recv_exact(self, sock, n):
        # recv는 요청보다 적게 올 수 있다(TCP는 스트림). n바이트 다 찰 때까지 반복.
        buf = b""
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("연결이 중간에 끊김")
            buf += chunk
        return buf

    def screenshot(self) -> bytes:
        # 매 호출마다 새 연결을 연다(폰 서버가 요청 1건 처리 후 닫는 구조와 짝).
        s = socket.socket()
        s.settimeout(10)
        s.connect((self.phone_ip, self.port))
        try:
            s.sendall(b"SHOT\n")                    # 명령 전송
            raw_len = self._recv_exact(s, 4)        # 길이 4바이트 먼저
            n = struct.unpack(">I", raw_len)[0]     # 빅엔디안 unsigned int
            return self._recv_exact(s, n)           # 딱 n바이트(PNG)
        finally:
            s.close()

    @staticmethod
    def _png_size(png: bytes):
        # PNG 구조: 8바이트 시그니처 + IHDR( 길이4 + "IHDR" + width4 + height4 ...)
        # → width는 바이트 16~20, height는 20~24 (둘 다 빅엔디안 32비트).
        w = struct.unpack(">I", png[16:20])[0]
        h = struct.unpack(">I", png[20:24])[0]
        return w, h

    # ── 키보드 준비/복원 ──
    # 접근성 경로에선 ADBKeyboard가 필요 없다(4단계에서 ACTION_SET_TEXT 사용 예정).
    # 하지만 main.py가 호출하므로 '계약 유지용' 빈 메서드로 둔다.
    def ensure_adb_keyboard(self):
        pass

    def restore_keyboard(self):
        pass

    # ── CU 액션 (3단계: 실제 실행 안 함, 로그만) ──
    # 메서드명 = CU 액션명. **_ 로 intent/safety_decision 등 여분 인자 흡수.
    def click(self, x, y, **_):
        # denormalize는 지금 굳이 필요 없지만, 4단계에서 쓸 좌표 변환을 미리 확인.
        px, py = denormalize(x, y, self.width, self.height)
        print(f"[noop] click {x},{y} -> px({px},{py})")

    def type(self, text, press_enter=False, **_):
        print(f"[noop] type {text!r} enter={press_enter}")

    def long_press(self, x, y, seconds=2, **_):
        print(f"[noop] long_press {x},{y} {seconds}s")

    def drag_and_drop(self, start_x, start_y, end_x, end_y, **_):
        print(f"[noop] drag ({start_x},{start_y}) -> ({end_x},{end_y})")

    def press_key(self, key, **_):
        print(f"[noop] press_key {key}")

    def go_back(self, **_):
        print("[noop] go_back")

    def open_app(self, app_name=None, package_name=None, **_):
        print(f"[noop] open_app {app_name or package_name}")

    def list_apps(self, **_):
        # main.py가 반환 dict를 결과에 합침. 지금은 빈 값.
        return {"apps": "No third-party apps (noop)."}

    def wait(self, seconds=1, **_):
        time.sleep(seconds)   # wait는 no-op이 아니라 실제로 기다려도 무해

    def take_screenshot(self, **_):
        return None           # 어차피 다음 턴에 새 스크린샷을 보냄
```

---

## 2. `main.py`에서 두 곳만 교체

지금:
```python
from live.adb_bridge import ADBBridge
...
    bridge = ADBBridge()
```

바꿀 것 (IP 하드코딩 없이 환경변수 `PHONE_IP`로 받음 = 방식 A):
```python
import os
from live.a11y_bridge import A11yBridge
...
    phone_ip = os.environ.get("PHONE_IP")   # 폰마다·재접속마다 IP가 달라짐(DHCP) → 하드코딩 금지
    if not phone_ip:
        raise SystemExit('PHONE_IP 미설정. 예: $env:PHONE_IP="192.168.0.55"; py live/main.py "작업"')
    bridge = A11yBridge(phone_ip)
```
- 나머지(루프·판단·`previous_interaction_id`·완료 판정)는 **한 줄도 안 바꾼다** → 계약을 지킨 이점.
- 폰 IP는 **설정 → Wi-Fi → 연결된 네트워크 상세**에서 확인해 실행 시 넘긴다.
- **후속(방식 B):** 폰이 UDP로 IP 방송 → PC 자동 탐색. 파이프 검증 후 추가.
> 4단계에선 이 선택을 `CU_BRIDGE=adb|a11y` 분기 안에 넣어 ADB/무선을 함께 고르게 확장한다(step4 §4).

---

## 3. 실행 & 확인

1. 폰: 접근성 앱 켜져 있고 Logcat `server listening on 8080` (2단계 상태 그대로).
2. PC(PowerShell): `$env:PHONE_IP="192.168.0.55"; py live/main.py "설정 앱을 열어"`  (← 폰 IP로 교체)
3. 기대 출력:
   ```
   기기 해상도: 1080x2400          ← screenshot()으로 받은 PNG에서 읽은 해상도
   작업: 설정 앱을 열어
   [턴 1] open_app({'app_name': ...})   ← CU가 판단한 액션
   [noop] open_app ...                   ← 우리 no-op 핸들러
   [턴 2] ...
   ```
4. 액션이 no-op이라 화면이 안 바뀌므로 CU가 계속 비슷한 액션을 낼 수 있다 →
   **몇 턴 돌아가는 것만 확인하고 `Ctrl+C`로 중단**하면 된다.

**성공 기준:** "해상도 출력 + CU가 낸 액션이 `[턴 n]`으로 찍히고 크래시 없이 다음 턴으로 넘어감."
= 폰(캡처) → PC(CU 판단) → 디스패치 **파이프 전체가 이어짐**. 하이브리드 뼈대 완성.

---

## 4. 막히기 쉬운 곳
- **`__init__`에서 멈춤/에러**: 생성 시 `screenshot()`을 부르므로 폰 서버가 안 떠 있으면 여기서 실패. 2단계 상태(서버 로그) 먼저 확인.
- **해상도가 이상(0x0 등)**: `_png_size`가 PNG가 아닌 데이터를 읽은 것 → 폰이 SHOT에 PNG 대신 에러를 보냈을 수 있음. Logcat `sent png` 확인.
- **매 턴 새 소켓**: 폰 서버가 요청 1건 처리 후 `client.close()` 하는 구조라 정상. 연결 재사용은 나중 최적화.
- **GEMINI_API_KEY**: `main.py`가 `.env`에서 로드. CU 호출이 나야 액션이 나온다.

## 5. 다음(4단계 예고)
no-op을 진짜 동작으로: 폰 프로토콜에 `TAP x y`, `SWIPE ...`, `TEXT ...`, `BACK/HOME` 명령을 추가하고,
`A11yBridge`의 각 액션 메서드가 그 명령을 소켓으로 보내도록 채운다. `click`부터.
