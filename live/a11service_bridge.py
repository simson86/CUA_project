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
import base64

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
    def _send_cmd(self,line:str)-> bytes:
        s=socket.socket(); s.settimeout(10)
        s.connect((self.phone_ip,self.port))
        try:
            s.sendall((line+"\n").encode())
            return s.recv(16)
        finally:
            s.close()

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
        self._send_cmd(f"TAP {int(px)} {int(py)}")

    def type(self, text, press_enter=False, **_):
        b64=base64.b64encode(text.encode()).decode()
        self._send_cmd(f"TEXT {b64}")
        if press_enter:
            self._send_cmd("ENTER")

    def long_press(self, x, y, seconds=2, **_):
        px,py= denormalize(x,y,self.width,self.height)
        self._send_cmd(f"LONGPRESS {int(px)} {int (py)} {int(seconds * 1000)}")

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
        # main.py가 반환 dict를 결과에 합침. 지금은 빈 값.
        return {"apps": "No third-party apps (noop)."}

    def wait(self, seconds=1, **_):
        time.sleep(seconds)  

    def take_screenshot(self, **_):
        return None           # 어차피 다음 턴에 새 스크린샷을 보냄