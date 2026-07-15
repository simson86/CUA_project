# 사고 수준(Thinking Level) — 속도·비용 관리

> Gemini 3.5 Flash Computer Use에서 **판단 전 추론량**을 조절해 품질↔속도/비용을 맞추는 기능.
> 개념·API·우리 코드 적용·측정 방법을 한곳에 정리. 관련: [[cu-reference-doc]] (`gemini-computer-use.md`).

## 1. 개념

모델이 다음 액션을 내기 전에 "얼마나 생각할지"를 단계로 지정한다.
- **높을수록** — 애매·복잡한 화면에서 판단 정확↑, 대신 **느리고 사고 토큰↑(비용↑)**
- **낮을수록** — 빠르고 저렴, 대신 어려운 상황에서 실수 가능성↑

공식 문서(Computer Use)의 권장:
> 컴퓨터 사용 에이전트는 품질과 실행 속도의 균형을 위해 사고 수준을 구성할 수 있으며,
> **일반적으로 낮은 사고 수준이 표준 자동화 작업에 적합한 균형**을 제공한다.

→ "설정 열기/토글" 같은 일상 조작은 낮게, 다단계·추론 많은 작업만 높인다.

## 2. API 규격 (설치된 google-genai에서 실측)

Interactions API는 `generation_config`에 **평평하게** 넣는다(중첩 아님):

```python
client.interactions.create(
    model="gemini-3.5-flash",
    input=...,
    tools=[{"type": "computer_use", "environment": "mobile"}],
    generation_config={"thinking_level": "low"},   # ← 이 한 줄
)
```

- **값(소문자):** `"minimal"` · `"low"` · `"medium"` · `"high"`
  (`google.genai._gaos.types.interactions.thinkinglevel.ThinkingLevel`)
- 세밀 제어용으로 `thinking_budget`(토큰 정수, `0`=끔 `-1`=자동)도 스키마에 있으나
  Computer Use엔 `thinking_level` 사용이 자연스럽다.

> 주의: 일반 `generate_content`(=`mobile_agent/`)의 `ThinkingConfig`는 `thinking_config`
> 아래에 `thinking_level`이 **중첩**된다. 하지만 우리 `live`/`cua`가 쓰는 **Interactions
> API는 `generation_config.thinking_level`로 평평**하다. 두 API 형태가 다르니 혼동 말 것.

### 응답의 토큰 사용량 (`interaction.usage`)
| 필드 | 뜻 |
|---|---|
| `total_input_tokens` | 입력(프롬프트+스크린샷) |
| `total_output_tokens` | 생성 출력 |
| `total_thought_tokens` | **사고 토큰** — 수준↑이면 여기서 늘어남 |
| `total_cached_tokens` | 캐시된 입력 |
| `total_tokens` | 합계 |

비용 ≈ `입력×단가_in + (출력+사고)×단가_out` (사고 토큰은 출력으로 과금).

## 3. 우리 코드 적용

`cua/cu_client.py`의 `CUClient`에 반영됨(한 곳만 고치면 `live`·벤치 전부 적용):
- **우선순위:** `create(..., thinking_level=)` 인자 > 생성자 인자 > **`.env`의 `CU_THINKING_LEVEL`** > 미지정(모델 기본)
- `.env`에 `CU_THINKING_LEVEL=low` 한 줄이면 코드 수정 없이 `live` 전체가 그 수준으로 동작.
- 미지정이면 `generation_config`를 아예 안 붙여 기존과 100% 동일.

```python
# 예: 라이브를 낮은 수준으로 (빠르게)
#   .env →  CU_THINKING_LEVEL=low
# 또는 코드에서 직접
client = CUClient(thinking_level="low")
```

## 4. 실제 측정 — `tools/bench_thinking.py`

같은 스크린샷 1장 + 같은 목표로 **수준만 바꿔** 여러 번 호출해 지연·토큰·비용을 잰다.
한 호출 = "이 화면에서 액션 하나" 판단이라 수준 간 비교가 공정하다(멀티턴 X).

```bash
py tools/bench_thinking.py --task "설정 앱을 열어" --runs 3
py tools/bench_thinking.py --levels low,high --runs 5 --image shot.png
py tools/bench_thinking.py --out docs/reference/thinking-bench-2026-07-15.md
```

- `--image PATH` 주면 기기 없이 저장된 PNG로 측정(재현 가능). 없으면 연결된 기기에서 캡처.
- 총 호출 수 = `len(levels) × runs` (기본 4×3=12회) — **실호출이라 소량 과금**.
- 출력: 수준별 평균 지연(min~max), 입력/출력/사고/총 토큰, 평균 비용($) 표.

### ⚠ 비용 단가 갱신 필요
`tools/bench_thinking.py`의 `PRICING`은 **추정 placeholder**다. 토큰량은 정확히 재지만
달러 환산은 이 단가에 의존하므로, 공식 가격표에서 확인해 갱신할 것:
- 가격: https://ai.google.dev/pricing
- 갱신 방법: `PRICING["gemini-3.5-flash"]`의 `in`/`out`(1M 토큰당 USD) 수정,
  또는 실행 시 `--price-in`/`--price-out`로 덮어쓰기.

## 5. 판단 가이드 (측정 후 채우기)

| 수준 | 언제 | 기대 |
|---|---|---|
| `minimal`/`low` | 일반 앱 조작(권장 기본) | 빠름/저렴 |
| `medium` | 다소 복잡한 다단계 | 중간 |
| `high` | 애매한 화면·추론 다수 | 느림/비쌈, 정확도↑ |

실제 수치는 `bench_thinking.py`로 측정해 이 표 아래에 결과를 붙여 확정한다.
사고 토큰이 수준에 따라 실제로 늘어나는지(=옵션이 먹었는지)도 함께 확인.
