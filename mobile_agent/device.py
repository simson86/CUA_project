import subprocess
import base64
import time
import re

ADB = r"C:\Users\shimw\AppData\Local\Android\Sdk\platform-tools\adb.exe"


# [vs quickstart] 구조: quickstart는 agent.py 한 파일에 ADBBridge를 넣지만, 우린 device.py로 분리(관심사 분리).
#   _run 헬퍼 / 액션메서드=액션이름+**_ / _px 좌표변환 은 quickstart 패턴을 차용한 것.
class ADBBridge:
    def __init__(self, device_id=None):
        self.prefix = [ADB] + (["-s", device_id] if device_id else [])
        self.width, self.height = self._screen_size()

    def _run(self, args, check=True):
        result = subprocess.run(self.prefix + args, capture_output=True, text=True)
        if check and result.returncode != 0:
            raise RuntimeError(f"ADB error : {result.stderr.strip()}")
        return result.stdout

    def _screen_size(self):
        out = self._run(["shell", "wm", "size"])
        m = re.search(r"Physical size: (\d+)x(\d+)", out)
        if m:
            return int(m.group(1)), int(m.group(2))
        return 1080, 2400

    def _px(self, x, y):
        px = int(x / 1000 * self.width)
        py = int(y / 1000 * self.height)
        return px, py

    def screenshot(self, path="screen.png"):
        # [vs quickstart] quickstart는 bytes만 반환. 우린 파일(screen.png)로도 저장(디버깅용).
        #   공통점: 바이너리(PNG)라 _run(text=True) 대신 subprocess 직접 호출.
        png = subprocess.run(
            self.prefix + ["exec-out", "screencap", "-p"], capture_output=True,
        ).stdout
        with open(path, "wb") as f:
            f.write(png)
        return png
    
    def _current_ime(self):
        # 현재 활성 IME(키보드) 아이디 읽기 — enable 이전에 호출해 원본을 _saved_ime에 기억하려고.
        return self._run(["shell","settings","get","secure","default_input_method"]).strip()
    
    def enable_adb_keyboard(self):
        # broadcast는 ADBKeyboard가 현재 IME여야만 작동 — 한글입력 중 이걸로 켜뒀다가 나중에 복원.
        # 주의: 이미 ADBKeyboard가 활성이면 if문 스킵 → 원본을 안 저장하고 나중에 ADBKeyboard로 복원되는 순환 발생.
        cur = self._current_ime()
        if "adbkeyboard" not in cur:
            self._saved_ime = cur
        self._run(["shell","ime","set","com.android.adbkeyboard/.AdbIME"],check=False)

    def restore_keyboard(self):
        # 자동화 끝 — 사람이 폰에서 평소 키보드로 타이핑할 수 있게 원래 IME로 복원(없으면 조용히 넘어감).
        if getattr(self,"_saved_ime",None):
            self._run(["shell","ime","set",self._saved_ime],check=False)
    def _tap(self, x, y):
        self._run(["shell", "input", "tap", str(x), str(y)])

    def _type_text(self, text):
        # base64 인코딩 — adb shell이 인자를 공백에서 재분해해 한글/공백이 깨지는 걸 방지(base64는 순수 ASCII·공백 없음).
        # ADBKeyboard가 ADB_INPUT_B64 broadcast 받아 디코딩 후 입력.
        b64= base64.b64encode(text.encode("utf-8")).decode()
        self._run(["shell","am","broadcast","-a","ADB_INPUT_B64","--es","msg",b64])

    def _swipe(self, x1, y1, x2, y2, ms=300):
        self._run(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])

    def _keyevent(self, code):
        self._run(["shell", "input", "keyevent", str(code)])

    def _open_app(self, package):
        self._run(["shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"], check=False)

    def click(self, x, y, **_):
        # [vs quickstart] 파라미터를 x,y 순서로. quickstart는 click(self, y, x)로 뒤집혀 있음(혼동 방지).
        px, py = self._px(x, y)
        self._tap(px, py)
        print(f" -> 탭 ({px},{py})")

    def type(self, text, press_enter=False, **_):
        # [vs quickstart] 한글 입력은 ADBKeyboard + base64 broadcast로 해결(input text는 ASCII 전용이라 NPE).
        self._type_text(text)
        if press_enter:
            self._keyevent(66)
        print(f' -> 입력 ("{text}"), enter={press_enter}')

    def open_app(self, package_name=None, **_):
        # [vs quickstart] quickstart는 "No activities found" 감지 시 예외로 알림. 우린 검증 없이 실행(_open_app은 check=False).
        self._open_app(package_name)
        print(f" -> 앱 실행 ({package_name})")

    def drag_and_drop(self, start_x, start_y, end_x, end_y, **_):
        sx, sy = self._px(start_x, start_y)
        ex, ey = self._px(end_x, end_y)
        self._swipe(sx, sy, ex, ey)
        print(f" -> 드래그 ({sx},{sy}) -> ({ex},{ey})")

    def go_back(self, **_):
        self._keyevent(4)
        print(" -> 뒤로가기")

    def press_key(self, key, **_):
        # [vs quickstart] 미지원 키는 무시(A안, 안전). quickstart는 keymap.get(key,key)로 모르는 키도 그대로 keyevent에 넘김.
        keymap = {"enter": 66, "backspace": 67, "home": 3, "back": 4, "tab": 61}
        code = keymap.get(key.lower())
        if code:
            self._keyevent(code)
            print(f" -> 키 입력 ({key})")
        else:
            print(f" -> 미지원 키 ({key})")

    def wait(self, seconds=1, **_):
        time.sleep(seconds)
        print(f" -> 대기 {seconds}초")

    def long_press(self, x, y, seconds=2, **_):
        px, py = self._px(x, y)
        self._swipe(px, py, px, py, int(seconds * 1000))
        print(f" -> 롱프레스 ({px},{py}) {seconds}초")


if __name__ == "__main__":
    b = ADBBridge()
    print("해상도:", b.width, b.height)
    b.click(500, 500)      # ← 화면 중앙 탭
    b.go_back()            # ← 뒤로가기
    print("액션 OK")
