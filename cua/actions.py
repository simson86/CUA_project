"""Parse a Computer Use interaction response into neutral actions.

이 모듈은 benchmark도 device도 모른다 (중립). 그냥 CU 응답(Interaction)을
받아서 우리가 다루기 쉬운 형태(CUAction)로 바꾸고, 좌표 변환 유틸을 제공한다.

CU 모바일 액션(원문 확인): click, type, long_press, drag_and_drop,
press_key, go_back, wait, open_app, list_apps, take_screenshot.
  - 좌표는 0-1000 정규화 값 (x, y 는 args 안에 들어옴)
  - scroll 은 없음 → 스와이프는 drag_and_drop 으로 표현됨
"""

from dataclasses import dataclass, field
from typing import Any, Dict, List


@dataclass
class CUAction:
    """CU가 뱉은 function_call 하나를 SDK 독립적으로 담는 그릇.

    args 안의 좌표(x, y 등)는 0-1000 정규화 값 그대로 보존한다.
    실제 픽셀 변환은 소비자(live/adb_bridge, benchmark/cu_agent)가
    자기 화면 크기로 수행한다.
    """
    name: str                       # "click", "type", "drag_and_drop", ...
    args: Dict[str, Any] = field(default_factory=dict)
    call_id: str = ""
    intent: str = ""


def parse_actions(interaction) -> List[CUAction]:
    """Interaction.steps 에서 function_call 스텝만 뽑아 CUAction 리스트로."""
    actions: List[CUAction] = []
    for step in interaction.steps:
        if step.type == "function_call":
            args = dict(step.arguments) if step.arguments else {}
            actions.append(CUAction(
                name=step.name,
                args=args,
                call_id=getattr(step, "id", "") or "",
                intent=args.get("intent", ""),
            ))
    return actions


def is_done(interaction) -> bool:
    """완료 판정 = function_call 스텝이 하나도 없음 (모델이 텍스트만 뱉음)."""
    return not any(step.type == "function_call" for step in interaction.steps)


def final_text(interaction) -> str:
    """완료 시 model_output 스텝들의 텍스트를 모아 반환."""
    parts: List[str] = []
    for step in interaction.steps:
        if step.type == "model_output":
            for block in getattr(step, "content", None) or []:
                if getattr(block, "type", None) == "text":
                    parts.append(block.text)
    return " ".join(parts).strip()


def denormalize(x: int, y: int, width: int, height: int) -> tuple[int, int]:
    """0-1000 정규화 좌표 → 실제 픽셀. (레퍼런스 _px 와 동일: /1000)

    소비자가 자기 화면 크기(width, height)로 호출한다.
    """
    px = int(int(x) / 1000 * width)
    py = int(int(y) / 1000 * height)
    return px, py
