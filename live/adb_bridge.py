"""ADB로 안드로이드 기기(에뮬레이터/실제 폰)를 제어하는 실행부.

[신규 · 라이브] cua/ 판단 코어와 분리 — 판단은 cua가, 실제 조작(캡처·탭·입력)은
여기가 담당한다. (GitHub 레퍼런스 agent.py 의 ADBBridge 기반)

CU 액션 이름(click/type/drag_and_drop/...)과 동일한 이름의 메서드를 두어,
main 에서 getattr(bridge, action.name)(**action.args) 로 바로 디스패치한다.
좌표는 CU의 0-1000 정규화 → 기기 실제 픽셀로 변환(cua.denormalize).

사전 준비: ADB 설치 + 기기 연결(`adb devices`로 확인).
"""

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

    # ── CU 액션 핸들러 (메서드명 = CU 액션명, **_ 로 intent 등 흡수) ──
    def click(self, x, y, **_):
        px, py = denormalize(x, y, self.width, self.height)
        self._run(["shell", "input", "tap", str(px), str(py)])

    def type(self, text, press_enter=False, **_):
        # adb input text 는 공백을 %s 로 넣어야 함
        self._run(["shell", "input", "text", str(text).replace(" ", "%s")])
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
