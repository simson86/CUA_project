"""ADB로 안드로이드 기기(에뮬레이터/실제 폰)를 제어하는 실행부.

[신규 · 라이브] cua/ 판단 코어와 분리 — 판단은 cua가, 실제 조작(캡처·탭·입력)은
여기가 담당한다. (GitHub 레퍼런스 agent.py 의 ADBBridge 기반)

CU 액션 이름(click/type/drag_and_drop/...)과 동일한 이름의 메서드를 두어,
main 에서 getattr(bridge, action.name)(**action.args) 로 바로 디스패치한다.
좌표는 CU의 0-1000 정규화 → 기기 실제 픽셀로 변환(cua.denormalize).

사전 준비: ADB 설치 + 기기 연결(`adb devices`로 확인).
"""

import base64
import os
import re
import subprocess
import sys
import time
from shutil import which

# cua 코어 import 를 위해 repo 루트를 경로에 추가
_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from cua import denormalize


def _resolve_adb() -> str:
    """adb 실행 파일 위치. PATH에 있으면 그걸, 없으면 기본 Android SDK 위치.

    → PATH를 설정 안 해도 동작 (Android Studio 기본 설치 경로 자동 탐색).
    """
    if which("adb"):
        return "adb"
    candidate = os.path.join(
        os.environ.get("LOCALAPPDATA", ""),
        "Android", "Sdk", "platform-tools", "adb.exe",
    )
    if os.path.exists(candidate):
        return candidate
    return "adb"  # 못 찾으면 PATH 가정 (에러 시 사용자 안내)


ADB_BIN = _resolve_adb()

# ADBKeyboard: 한글/특수문자를 base64 broadcast 로 안전하게 입력하기 위한 IME.
# 기기에 없으면 vendor 에 번들된 APK 를 설치한다.
ADBK_PKG = "com.android.adbkeyboard"
ADBK_IME = "com.android.adbkeyboard/.AdbIME"
ADBK_APK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "vendor", "ADBKeyboard.apk")


class ADBBridge:
    """ADB 명령으로 안드로이드 기기를 캡처·조작한다."""

    def __init__(self, device_id: str | None = None):
        # 여러 기기가 붙어 있으면 device_id 로 지정 (adb -s)
        # ADB_BIN: PATH 또는 기본 SDK 위치에서 자동 탐색된 adb
        self.prefix = [ADB_BIN] + (["-s", device_id] if device_id else [])
        self.width, self.height = self._screen_size()

    # ── 내부 유틸 ──
    def _run(self, args, check=True) -> str:
        result = subprocess.run(self.prefix + args, capture_output=True, text=True)
        if check and result.returncode != 0:
            raise RuntimeError(f"ADB error: {result.stderr.strip()}")
        return result.stdout

    def _screen_size(self) -> tuple[int, int]:
        # 화면 해상도를 동적으로 읽음 → 에뮬/실폰 무관, 코드 수정 없이 적응
        out = self._run(["shell", "wm", "size"])
        m = re.search(r"Physical size: (\d+)x(\d+)", out)
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    def screenshot(self) -> bytes:
        res = subprocess.run(
            self.prefix + ["exec-out", "screencap", "-p"], capture_output=True
        )
        return res.stdout

    # ── ADBKeyboard(한글 입력) 준비/복원 ──
    def _is_installed(self, pkg: str) -> bool:
        out = self._run(["shell", "pm", "list", "packages", pkg], check=False)
        return f"package:{pkg}" in out

    def _current_ime(self) -> str:
        return self._run(["shell", "settings", "get", "secure",
                          "default_input_method"]).strip()

    def ensure_adb_keyboard(self):
        """기기 연결 시 1회 호출: ADBKeyboard 가 없으면 설치하고 IME 로 전환.

        전환 전 원래 IME 를 _saved_ime 에 저장 → 종료 시 restore_keyboard 로 복원.
        """
        if not self._is_installed(ADBK_PKG):
            if not os.path.exists(ADBK_APK):
                raise RuntimeError(
                    f"ADBKeyboard 미설치이며 번들 APK 도 없음: {ADBK_APK}\n"
                    f"  기기에서 base.apk 를 pull 해 그 경로에 두세요."
                )
            print("ADBKeyboard 미설치 → 설치 중...")
            self._run(["install", "-r", ADBK_APK])
        # 원래 IME 저장(이미 ADBKeyboard 면 저장 스킵 → 나중에 자기 자신으로 복원 방지)
        cur = self._current_ime()
        if "adbkeyboard" not in cur.lower():
            self._saved_ime = cur
        # 새로 설치한 IME 는 enable 먼저 해야 set 가능
        self._run(["shell", "ime", "enable", ADBK_IME], check=False)
        self._run(["shell", "ime", "set", ADBK_IME], check=False)

    def restore_keyboard(self):
        """자동화 종료 시: 사람이 쓰던 원래 키보드(IME)로 복원."""
        if getattr(self, "_saved_ime", None):
            self._run(["shell", "ime", "set", self._saved_ime], check=False)

    # ── CU 액션 핸들러 (메서드명 = CU 액션명, **_ 로 intent 등 흡수) ──
    def click(self, x, y, **_):
        px, py = denormalize(x, y, self.width, self.height)
        self._run(["shell", "input", "tap", str(px), str(py)])

    def type(self, text, press_enter=False, **_):
        # base64 로 셸 해석을 우회 → 한글/공백/특수문자 안전(ADBKeyboard 가 디코딩해 입력).
        # (기존 `input text` 는 ASCII 전용이라 한글이 깨졌음)
        b64 = base64.b64encode(str(text).encode("utf-8")).decode()
        self._run(["shell", "am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", b64])
        if press_enter:
            self._run(["shell", "input", "keyevent", "66"])

    def long_press(self, x, y, seconds=2, **_):
        px, py = denormalize(x, y, self.width, self.height)
        self._run(["shell", "input", "swipe", str(px), str(py),
                   str(px), str(py), str(int(seconds * 1000))])

    def drag_and_drop(self, start_x, start_y, end_x, end_y, **_):
        sx, sy = denormalize(start_x, start_y, self.width, self.height)
        ex, ey = denormalize(end_x, end_y, self.width, self.height)
        self._run(["shell", "input", "swipe", str(sx), str(sy),
                   str(ex), str(ey), "300"])

    def press_key(self, key, **_):
        keymap = {"home": "3", "back": "4", "enter": "66",
                  "app_switch": "187", "menu": "82"}
        self._run(["shell", "input", "keyevent", keymap.get(str(key).lower(), str(key))])

    def go_back(self, **_):
        self._run(["shell", "input", "keyevent", "4"])

    def open_app(self, app_name=None, package_name=None, **_):
        pkg = app_name or package_name
        if not pkg:
            raise ValueError("open_app requires app_name or package_name")
        out = self._run(["shell", "monkey", "-p", pkg, "-c",
                         "android.intent.category.LAUNCHER", "1"], check=False)
        if "No activities found" in out or "monkey aborted" in out:
            raise RuntimeError(f"App {pkg} is not installed or has no launcher.")

    def list_apps(self, **_):
        out = self._run(["shell", "pm", "list", "packages", "-3"])
        apps = [l.split(":", 1)[1] for l in out.splitlines() if l.startswith("package:")]
        return {"apps": apps or "No third-party apps installed."}

    def wait(self, seconds=1, **_):
        time.sleep(seconds)

    def take_screenshot(self, **_):
        # 다음 턴에 어차피 새 스크린샷을 보내므로 별도 동작 없음
        return None
