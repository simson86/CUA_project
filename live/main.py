"""라이브 CU 에이전트 — 실제 안드로이드 기기에서 앱을 자동 조작.

[신규 · 라이브] 루프: 캡처(ADB) → 판단(cua) → 실행(ADB) → 반복.
(GitHub 레퍼런스 agent.py 의 멀티턴 루프 구조 기반)

실행:  python live/main.py "작업 설명"
예:    python live/main.py "설정 앱을 열어서 다크모드를 켜"
사전:  ADB + 에뮬레이터/폰 연결 (adb devices 로 확인), .env 에 GEMINI_API_KEY
"""

import os
import sys
import time

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

try:
    sys.stdout.reconfigure(encoding="utf-8")  # 윈도우 콘솔 유니코드
except Exception:
    pass

from dotenv import load_dotenv
load_dotenv(os.path.join(_REPO_ROOT, ".env"))

from cua import (
    CUClient, initial_input, function_result, parse_actions, is_done, final_text,
)
from live.adb_bridge import ADBBridge

SETTLE_SEC = 0.6  # 액션 후 화면이 안정될 때까지 대기


def run(task: str, max_turns: int = 30):
    client = CUClient()
    bridge = ADBBridge()
    bridge.ensure_adb_keyboard()   # 한글 입력용 ADBKeyboard 확인·설치·IME 전환(원래 IME 저장)
    print(f"기기 해상도: {bridge.width}x{bridge.height}")
    print(f"작업: {task}")
    print("-" * 50)

    try:
        # 첫 턴: 목표 + 현재 스크린샷
        interaction = client.create(initial_input(task, bridge.screenshot()))
        prev_id = interaction.id

        for turn in range(1, max_turns + 1):
            # 완료 판정 = function_call 없음
            if is_done(interaction):
                print("\n[완료]", final_text(interaction) or "(작업 완료)")
                return

            # 이번 턴의 액션들을 실행하고 결과(새 스크린샷)를 모음
            results = []
            for action in parse_actions(interaction):
                print(f"[턴 {turn}] {action.name}({action.args})")
                data = {"status": "ok"}
                handler = getattr(bridge, action.name, None)
                if handler is None:
                    data = {"status": "error", "error": f"Unknown action: {action.name}"}
                else:
                    try:
                        out = handler(**action.args)
                        if isinstance(out, dict):
                            data.update(out)
                    except Exception as e:
                        data = {"status": "error", "error": str(e)}

                safety_ack = "safety_decision" in action.args  # 데모: 자동 승인
                time.sleep(SETTLE_SEC)                          # 화면 안정 대기
                shot = bridge.screenshot()
                results.append(
                    function_result(action.name, action.call_id, shot, data, safety_ack)
                )

            if not results:
                break

            # 실행 결과를 되돌려주고 다음 턴 (previous_interaction_id 로 맥락 이어감)
            interaction = client.create(results, previous_interaction_id=prev_id)
            prev_id = interaction.id

        print("\n[중단] 최대 턴 도달")
    finally:
        bridge.restore_keyboard()  # 완료·에러·중단 어디서 끝나든 원래 키보드로 복원


if __name__ == "__main__":
    task_desc = " ".join(sys.argv[1:]) or "Open the Settings app"
    run(task_desc)
