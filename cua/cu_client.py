"""Thin wrapper around the Gemini Computer Use API (mobile environment).

benchmark도 device도 모르는 순수 판단 코어. 스크린샷+목표를 주면
CU 모델을 호출해 Interaction(응답)을 돌려준다. 실제 파싱은 actions.py 가,
실행/채점은 소비자(live, cu_agent)가 담당한다.

smoke test(cu_smoke_test.py)로 검증된 호출 방식과 동일하다.
"""

import base64
import json
import os
from typing import Any, List, Optional

from google import genai

from .prompt import SYSTEM_PROMPT

MODEL = "gemini-3.5-flash"
TOOLS = [{"type": "computer_use", "environment": "mobile"}]


def to_b64(image: Any) -> str:
    """이미지를 base64 문자열로. bytes 면 인코딩, 이미 str 이면 그대로."""
    if isinstance(image, str):
        return image
    return base64.b64encode(image).decode()


def image_block(image: Any) -> dict:
    return {"type": "image", "data": to_b64(image), "mime_type": "image/png"}


def initial_input(goal: str, screenshot: Any,
                  history: Optional[List[str]] = None) -> List[dict]:
    """첫 턴 입력: 목표(+진행 히스토리) + 스크린샷(image).

    [5단계] history: 지금까지 밟은 스텝 요약 리스트. 주면 목표 텍스트에 덧붙여
    모델이 진행 상황(맥락)을 알게 한다 → back/scroll 오예측 감소.
    """
    text = f"Task: {goal}"
    if history:
        steps = "\n".join(f"Step {i + 1}. {s}" for i, s in enumerate(history))
        text += ("\n\nSteps already done:\n" + steps
                 + "\n\nNow choose the next single action for the current screen.")
    return [
        {"type": "text", "text": text},
        image_block(screenshot),
    ]


def function_result(name: str, call_id: str, screenshot: Any,
                    result_data: Optional[dict] = None,
                    safety_ack: bool = False) -> dict:
    """[라이브 루프용] 액션 실행 결과 + 새 스크린샷을 CU에 되돌려줄 블록.

    safety_ack=True 면 safety_acknowledgement 를 붙여 자동 승인(데모용).
    벤치마크(오프라인, 단일 호출)는 이걸 안 쓴다.
    """
    data = dict(result_data or {"status": "ok"})
    if safety_ack:
        data["safety_acknowledgement"] = True
    return {
        "type": "function_result",
        "name": name,
        "call_id": call_id,
        "result": [
            {"type": "text", "text": json.dumps(data)},
            image_block(screenshot),
        ],
    }


def _build_generation_config(thinking_level: Optional[str],
                             temperature: Optional[float]) -> Optional[dict]:
    """[reasoning 배선] 이 호출에 적용할 thinking_level·temperature → generation_config.

    thinking_level: 'MINIMAL'/'LOW'/'MEDIUM'/'HIGH' (Gemini 네이티브, 대소문자 무관) 또는 None.
    ⚠️ SDK 규격: thinking_level 은 반드시 thinking_config 안에 중첩. 평면으로 넣으면
       google-genai 2.10/2.11 모두 ValidationError(extra_forbidden)로 죽는다(검증됨).
    둘 다 None(미지정)이면 None 반환 → 지금까지와 동일(모델 기본값). 라이브·평가 공유.
    """
    cfg: dict = {}
    if thinking_level:
        cfg["thinking_config"] = {"thinking_level": thinking_level}
    if temperature is not None:
        cfg["temperature"] = temperature
    return cfg or None


class CUClient:
    """Computer Use 호출기."""

    def __init__(self, api_key: Optional[str] = None, model: str = MODEL,
                 thinking_level: Optional[str] = None,
                 temperature: Optional[float] = None):
        # [reasoning 배선] thinking_level·temperature 는 안 넘기면 None → 기본 동작 그대로.
        self.client = genai.Client(api_key=api_key or os.getenv("GEMINI_API_KEY"))
        self.model = model
        # 기본 사고수준: 인자 > .env(CU_THINKING_LEVEL) > 미지정(모델 기본).
        #   - 벤치마크(cu_agent)는 항상 인자를 명시 → .env 안 탐 → 재현성 보존.
        #   - 라이브에서 --thinking 생략 시에만 .env 가 기본값으로 작동.
        self.thinking_level = thinking_level or os.getenv("CU_THINKING_LEVEL") or None
        self.temperature = temperature

    def create(self, input: List[dict], previous_interaction_id: Optional[str] = None,
               thinking_level: Optional[str] = None):
        """CU 호출 1회. input 은 initial_input(...) 또는 function_result 리스트.

        previous_interaction_id 로 이전 턴을 이어갈 수 있다(라이브 루프용).
        벤치마크(오프라인)는 스텝당 initial_input 으로 1회만 부르면 된다.
        thinking_level 을 주면 이번 호출만 그 수준으로(측정용). 없으면 인스턴스 기본값.
        """
        level = thinking_level or self.thinking_level  # per-call > 인스턴스 기본
        kwargs: dict = dict(
            model=self.model,
            system_instruction=SYSTEM_PROMPT,
            input=input,
            tools=TOOLS,
            previous_interaction_id=previous_interaction_id,
        )
        gen_cfg = _build_generation_config(level, self.temperature)
        if gen_cfg is not None:  # thinking·temperature 둘 다 없으면 기존과 완전히 동일
            kwargs["generation_config"] = gen_cfg
        return self.client.interactions.create(**kwargs)

    def decide(self, goal: str, screenshot: Any,
               history: Optional[List[str]] = None,
               previous_interaction_id: Optional[str] = None,
               thinking_level: Optional[str] = None):
        """편의 함수: 목표(+히스토리)+스크린샷으로 바로 1회 호출. [5단계] history 추가"""
        return self.create(initial_input(goal, screenshot, history),
                           previous_interaction_id, thinking_level)
