# Gemini Computer Use — 참고 자료 (프로젝트 레퍼런스)

> 출처 4개(`참고 문서 링크.txt`)를 학습해 정리한 내부 참고 문서.
> 이 프로젝트(`live/`, `mobile_agent/`)가 무엇을 구현하는지의 **정답지**로 사용한다.
> 새 기능/디버깅 시 여기 표준과 우리 코드가 어긋나지 않는지 먼저 대조할 것.
>
> **원문 링크**
> 1. [DeepMind 발표 — Gemini 2.5 Computer Use](https://blog.google/innovation-and-ai/models-and-research/google-deepmind/gemini-computer-use-model/)
> 2. [Computer Use in Gemini 3.5 Flash](https://blog.google/innovation-and-ai/models-and-research/gemini-models/introducing-computer-use-gemini-3-5-flash/)
> 3. [API 문서 — Computer Use (safety-security)](https://ai.google.dev/gemini-api/docs/computer-use?hl=ko#safety-security)
> 4. [google-gemini/gemini-android-computer-use-quickstart](https://github.com/google-gemini/gemini-android-computer-use-quickstart) ← **우리 프로젝트의 원형**

---

## 1. Computer Use 가 뭔가 (한 줄)

스크린샷 + 목표를 모델에 주면 **UI 액션 하나(function_call)** 를 돌려준다. 그 액션을
클라이언트(우리는 ADB)가 실행하고 새 스크린샷을 되돌려준다 — 완료/에러/안전중단까지 반복.

**루프:** `캡처 → 모델 판단 → 실행 → 새 스크린샷 피드백 → 반복`
우리 코드: `live/main.py`의 `run()`, `mobile_agent/agent.py`가 정확히 이 구조.

### 환경(environment) 3종
| environment | 상태 | 우리 프로젝트 |
|---|---|---|
| `browser` | 최적화 (1순위) | 미사용 |
| `mobile` | 지원 (Android) | **우리가 쓰는 값** |
| `desktop` | 3.5 Flash에서 추가 | 미사용 |

도구 설정: `tools=[{"type": "computer_use", "environment": "mobile"}]`

### 모델 계보
- `gemini-2.5-computer-use` — 최초 standalone 모델 (Gemini 2.5 Pro 시각·추론 기반)
- **`gemini-3.5-flash`** — Computer Use가 **built-in 도구**로 통합됨. browser/mobile/desktop 전부. **← 우리가 쓰는 모델** (`cua/cu_client.py`, `mobile_agent/agent.py`)
- `gemini-3-flash-preview`도 지원, `gemini-2.5`는 legacy preview.

### 성능/특징 (2.5 발표 기준 참고 수치)
- 벤치마크: Online-Mind2Web, WebVoyager, **AndroidWorld**(모바일) 등에서 우위.
- 지연/품질: 70%+ 정확도에 ~225초 latency, 동급 최저 지연이라 주장.
- 데스크톱 OS 레벨 제어는 2.5에선 미최적화 → 3.5 Flash에서 desktop 추가.

---

## 2. 액션 스페이스 (environment=mobile)

모델이 돌려주는 function_call 이름 = 우리 `ADBBridge`의 메서드 이름이어야 함
(**dispatch: `getattr(bridge, action.name)(**action.args)`**).

### mobile 액션 전체 목록
| 액션 | 주요 인자 | 의미 |
|---|---|---|
| `click` | `x, y` | 탭 |
| `type` | `text`, `press_enter?` | 텍스트 입력 |
| `long_press` | `x, y` | 롱프레스 |
| `drag_and_drop` | `x, y, destination_x, destination_y` | 드래그 |
| `press_key` | `key` | 키 입력 |
| `go_back` | — | 뒤로가기 |
| `open_app` | `app_name` | 앱 실행 |
| `list_apps` | — | 설치 앱 목록 |
| `wait` | `seconds?` | 대기 |
| `take_screenshot` | — | 스크린샷 |
| `scroll` | 방향/거리 | 스크롤 (환경에 따라) |

> browser 환경엔 추가로 `double_click, right_click, middle_click, move, mouse_down/up,
> navigate, go_forward, hotkey, key_down/up, scroll` 등이 있음 — mobile엔 불필요.

### 새 액션 추가 방법 (우리 규칙)
`ADBBridge`에 **CU 액션 이름과 똑같은 이름**의 메서드를 추가한다.
- 여분 인자(`intent`, `safety_decision` 등)는 `**_`로 흡수.
- 실패/미지원 액션은 크래시 대신 `{"status":"error",...}`로 모델에 피드백 → 자가수정 유도.

---

## 3. 좌표 정규화 ★중요★

모델은 **0–1000 정규화 좌표**를 반환한다. (repo 문서엔 0-999로도 표기 — 사실상 동일 스케일)
실제 픽셀로 환산해야 함:

```python
px = int(x / 1000 * width)
py = int(y / 1000 * height)
```

- `live/`: `cua.denormalize(x, y, width, height)`
- `mobile_agent/`: `_px()`
- 화면 크기는 `adb shell wm size`로 **동적으로** 읽는다 (기기마다 다름).

⚠️ **원형 repo의 함정:** quickstart의 `click(y, x, **_)`는 **인자 순서가 (y, x)로 뒤바뀌어**
있다. 우리 코드가 `(x, y)`를 쓰는지 항상 확인. 좌표가 90도 돌아간 듯 오작동하면 이걸 의심.

---

## 4. API 호출 구조 (Interactions API — 서버 관리 히스토리)

`live/`+`cua/`가 쓰는 방식. **`previous_interaction_id`로 맥락을 서버가 관리**.

```python
client = genai.Client()

# 첫 턴: 목표 + 스크린샷
interaction = client.interactions.create(
    model="gemini-3.5-flash",
    system_instruction=SYSTEM_PROMPT,
    input=user_input,                       # 텍스트 task + PNG 스크린샷
    tools=[{"type": "computer_use", "environment": "mobile",
            "enable_prompt_injection_detection": True}],  # 선택
)
prev_id = interaction.id
```

### 응답 파싱
`interaction.steps` 각 원소:
- `step.type == "function_call"` → 실행할 액션. `step.name`, `step.arguments`(kwargs), `step.id`(call_id)
- `step.type == "model_output"` → 모델의 설명 텍스트
- **function_call이 하나도 없으면 = 작업 완료** (우리 `is_done()`).

### 실행 결과 되돌리기
```python
result = {
  "type": "function_result",
  "name": step.name,
  "call_id": step.id,
  "result": [
    {"type": "text",  "text": json.dumps({"url": ...})},   # 모바일은 url 대신 상태 dict
    {"type": "image", "data": b64_png, "mime_type": "image/png"},
  ],
}
interaction = client.interactions.create(
    input=results, previous_interaction_id=prev_id)   # 맥락 이어감
prev_id = interaction.id
```

### 스크린샷 캡처(ADB)
```python
subprocess.run(prefix + ["exec-out", "screencap", "-p"], capture_output=True).stdout
# → raw PNG bytes → base64 인코딩해서 image data로 전송
```

> **두 SDK를 섞지 말 것** (CLAUDE.md 참조):
> - `live/`+`cua/` = `client.interactions.create` + `previous_interaction_id` (서버 히스토리)
> - `mobile_agent/` = `client.models.generate_content` + `contents` 직접 누적 (클라 히스토리)

---

## 5. 안전(Safety) — HITL ★반드시 처리★

### `safety_decision` 필드
민감/되돌릴 수 없는 액션이면 모델이 function_call 인자에 넣어 반환:
```json
"safety_decision": {"explanation": "...", "decision": "require_confirmation"}
```
- `general/allow` → 그냥 진행
- `require_confirmation` → **사용자에게 explanation 보여주고 승인받아야** 진행

### 승인 신호 (safety_acknowledgement)
승인했으면 function_result에 표시해서 돌려준다:
```python
result_text["safety_acknowledgement"] = True
```
우리 코드(`live/main.py`)는 **데모라서 자동 승인**(`safety_ack = "safety_decision" in action.args`).
→ 실제 서비스로 갈 땐 **진짜 사용자 확인 UI로 바꿔야 함**. (지금은 의도적 데모 단축)

### 내장 안전 정책 7종 (`disabled_safety_policies`로 선택 해제 가능)
`FINANCIAL_TRANSACTIONS`, `SENSITIVE_DATA_MODIFICATION`, `COMMUNICATION_TOOL`,
`ACCOUNT_CREATION`, `DATA_MODIFICATION`, `USER_CONSENT_MANAGEMENT`,
`LEGAL_TERMS_AND_AGREEMENTS`.

⚠️ **정책을 꺼도 모델은 여전히 `require_confirmation`을 낼 수 있다.** 앱은 항상 안전 결정을
처리해야 하며 "정책 껐으니 안전검사 없음"을 가정하면 안 됨.

### 프롬프트 인젝션 탐지
`"enable_prompt_injection_detection": True` — 스크린샷 픽셀 안에 숨겨진 적대적 명령을
스캔·차단. 3.5 Flash는 이를 위한 적대적 학습(adversarial training)을 강화.

### 강제 확인 케이스 (문서 명시)
결제/금융, 통신(메시지 전송), 법적 약관 동의, **CAPTCHA 풀기** 등은 반드시 사용자 확인.

### 운영 권장
샌드박스(VM/컨테이너) 실행, 프롬프트·스크린샷·액션 상세 로깅, 입력 새니타이즈,
출시 전 충분한 테스트.

---

## 6. 원형 repo(quickstart) vs 우리 프로젝트

| 항목 | quickstart(원본) | 우리 프로젝트 |
|---|---|---|
| 대상 | Android **에뮬레이터**(`AI_Agent_Phone`) | **실기기**(Galaxy S23) |
| 패키지 관리 | `uv` + `uv pip` | **`py` 런처**(Python 3.14). `python`은 msys2라 금지 |
| 파일 | 단일 `agent.py` | 3중 구조: `cua/`(판단) + `live/`(실행) + `mobile_agent/`(모놀리식) |
| 한글 입력 | 없음(ASCII) | **ADBKeyboard + base64 broadcast** (`ADB_INPUT_B64`) |
| 자동화 | 없음 | 자동 커밋 훅 + git 리포트 + 날짜별 진행 기록 |

### quickstart 셋업 흐름(참고)
```
./setup_emulator.sh          # macOS 에뮬 세팅 (우리는 실기기라 불필요)
export GEMINI_API_KEY="..."
uv venv && source .venv/bin/activate
uv pip install -r requirements.txt
python agent.py "task"
```
우리 실행: `py live/main.py "작업 설명"` (자세한 건 CLAUDE.md).

### 우리가 원본에서 조심해야 할 것
1. `click(y, x)` 인자 순서 뒤집힘 (§3) — 우리 코드는 `(x, y)` 유지.
2. 좌표 스케일 0-1000 vs 0-999 혼용 표기 — 정수 나눗셈이라 실질 차이 미미하나 통일.
3. 원본은 에뮬 전제 — 실기기는 `adb devices` 인증, 화면 크기 동적 조회 필수.
4. 한글은 원본에 없음 — 우리 base64 broadcast 경로가 표준(§ CLAUDE.md 한글 입력).

---

## 7. 빠른 체크리스트 (새 작업 시작 전)

- [ ] 모델 id `gemini-3.5-flash`, `environment: "mobile"` 맞나
- [ ] 새 액션이면 `ADBBridge`에 **동명 메서드** 추가했나 (`**_`로 여분 인자 흡수)
- [ ] 좌표를 0-1000 → 픽셀로 denormalize 했나, `(x, y)` 순서 맞나
- [ ] function_call 없음 = 완료 처리(`is_done`) 되나
- [ ] `safety_decision` 처리 경로 있나 (데모는 자동승인, 실서비스는 확인 UI)
- [ ] 파이썬은 `py`로 실행하나 (`python`은 msys2 금지)
- [ ] 한글 입력은 ADBKeyboard broadcast 경로 쓰나
