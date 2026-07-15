# 구조 비교: `mobile_agent` vs `cua + live`

두 코드베이스 모두 **Gemini Computer Use(CU) 모델로 실제 안드로이드 기기를 ADB로 조작하는 에이전트**라는 동일한 목적을 가진다. 하지만 설계 철학이 다르다.

- **`mobile_agent`** — 한 폴더 2파일의 **모놀리식(monolithic)** 구조. 실행 스크립트 하나에 루프·안전확인·재시도가 다 들어있음.
- **`cua + live`** — **판단 코어(`cua`)와 실행부(`live`)를 분리**한 모듈형 구조. `cua`는 벤치마크/기기와 무관한 순수 판단 코어로 재활용을 노림.

---

## 1. 파일 구조 한눈에

### `mobile_agent/` (2 파일)
```
mobile_agent/
├── agent.py     # 진입점 + 멀티턴 루프 + 안전확인(HITL) + 재시도 + 프롬프트
└── device.py    # ADBBridge (화면 캡처·탭·입력·앱실행 등 실제 조작)
```

### `cua + live` (2 패키지, 6 파일)
```
cua/                 # 판단 코어 (benchmark·device 무관, 재활용 목적)
├── __init__.py      # 공개 API 재노출
├── cu_client.py     # CUClient — Gemini CU API 래퍼 + 입력/결과 블록 빌더
├── actions.py       # CUAction dataclass + 응답 파싱 + 좌표 변환(denormalize)
└── prompt.py        # SYSTEM_PROMPT (최소 지시)

live/                # 실행부 (실제 기기 루프)
├── __init__.py
├── adb_bridge.py    # ADBBridge (실제 조작) — cua.denormalize 사용
└── main.py          # 진입점 + 멀티턴 루프
```

---

## 2. 관심사 분리(Separation of Concerns) 비교

| 관심사 | `mobile_agent` | `cua + live` |
|---|---|---|
| **API 호출** | `agent.py` 내부 인라인 | `cua/cu_client.py` (`CUClient`) |
| **응답 파싱** | `agent.py` 루프 안에서 직접 순회 | `cua/actions.py` (`parse_actions`, `is_done`, `final_text`) |
| **프롬프트** | `agent.py` 안 상수 | `cua/prompt.py` (별도 파일) |
| **좌표 변환** | `device.py`의 `_px()` (기기에 종속) | `cua/actions.py`의 `denormalize()` (기기 무관, 소비자가 크기 전달) |
| **기기 조작** | `device.py` (`ADBBridge`) | `live/adb_bridge.py` (`ADBBridge`) |
| **루프/진입점** | `agent.py` | `live/main.py` |

> 핵심 차이: `mobile_agent`는 "판단"과 "실행"이 `agent.py` 한 파일에 얽혀 있고, `cua+live`는 판단(`cua`)을 순수 라이브러리로 떼어내 라이브 실행(`live`)과 벤치마크가 **같은 코어를 공유**하도록 설계됨.

---

## 3. API 호출 방식의 근본적 차이 ⭐

두 구조가 **서로 다른 Gemini SDK API**를 쓴다.

| | `mobile_agent` | `cua + live` |
|---|---|---|
| API | `client.models.generate_content` | `client.interactions.create` |
| 히스토리 관리 | **클라이언트(직접 `contents` 누적)** | **서버(`previous_interaction_id`)** |
| 도구 정의 | `types.Tool(computer_use=...)` (typed) | `[{"type":"computer_use","environment":"mobile"}]` (dict) |
| 완료 판정 | `function_call` part 없음 | `is_done()` = `function_call` 스텝 없음 |
| 재시도 | ✅ `generate_with_retry` (429/5xx 대기·재시도) | ❌ 없음 |

`mobile_agent`는 히스토리를 스스로 관리해서 **재시도 유지 + 토큰 절감용 가지치기가 가능**하다고 주석에 명시. `cua+live`는 서버가 맥락을 이어줘 코드가 단순.

---

## 4. `ADBBridge` 구현 차이

| 항목 | `mobile_agent/device.py` | `live/adb_bridge.py` |
|---|---|---|
| adb 경로 | **하드코딩** (`C:\Users\shimw\...adb.exe`) | **자동 탐색** (`which("adb")` → SDK 기본경로 → PATH) |
| 스크린샷 | bytes 반환 **+ `screen.png` 파일 저장** | bytes만 반환 |
| 좌표 변환 | 내부 `_px()` 메서드 | 외부 `cua.denormalize()` 호출 |
| **한글 입력** | ✅ **ADBKeyboard + base64 broadcast** (한글/특수문자 안전) | ❌ `input text`(공백→`%s`, ASCII 전용) |
| 키보드 복원 | ✅ `enable/restore_keyboard` (원래 IME 저장·복원) | ❌ 없음 |
| `open_app` 검증 | 검증 없이 실행 | "No activities found" 감지 시 예외 |
| `list_apps` | ❌ (excluded) | ✅ 구현 |
| `take_screenshot` | ❌ (excluded) | ✅ (no-op) 구현 |
| `press_key` 미지원키 | 무시(안전) | 원본 키 그대로 전달 |

> 가장 실용적 차이: **`mobile_agent`만 한글 입력을 제대로 처리**한다(ADBKeyboard + base64). `live`는 `adb input text`라 한글이 깨질 수 있음.

---

## 5. 안전(Safety) / HITL 비교

| | `mobile_agent` | `cua + live` |
|---|---|---|
| 안전확인 | ✅ **`y/n` 사용자 확인**(Human-in-the-loop) 후 진행 | ⚠️ `safety_decision` 있으면 **자동 승인**(데모용) |
| 키보드 복원 | ✅ `try/finally`로 무조건 복원 | ❌ |

`mobile_agent`가 안전 측면에서 더 보수적(사용자에게 물음). `live`는 데모 편의로 자동 승인.

---

## 6. 루프 구조

| | `mobile_agent` | `cua + live` |
|---|---|---|
| 최대 스텝 | `for step in range(1,16)` (15스텝) + `for-else`로 미완료 판정 | `for turn in range(1, max_turns+1)` (기본 30턴) |
| 입력 방식 | `input()` 대화형 프롬프트 | `sys.argv` 커맨드라인 인자 |
| 액션 디스패치 | `getattr(bridge, fc.name)(**args)` | `getattr(bridge, action.name)(**action.args)` |
| 결과 되먹임 | `FunctionResponse` + 새 스크린샷 | `function_result()` 헬퍼 + 새 스크린샷 |
| 화면 안정 대기 | `time.sleep(0.5)` | `SETTLE_SEC = 0.6` |

디스패치 패턴(`getattr` + try/except로 크래시 대신 에러 되먹임)은 **두 구조가 동일**하다.

---

## 7. 종합 정리

| 관점 | `mobile_agent` | `cua + live` |
|---|---|---|
| 설계 | 모놀리식, 단일 실행 스크립트 | 모듈형, 판단/실행 분리 |
| 재사용성 | 낮음(한 덩어리) | 높음(`cua` 코어를 live·벤치마크가 공유) |
| 견고성 | ✅ 재시도, ✅ 한글입력, ✅ 키보드복원, ✅ HITL | 이 4가지 없음(대신 자동 탐색·검증·list_apps 보유) |
| 안전성 | 사용자 y/n 확인 | 자동 승인(데모) |
| 이식성 | adb 경로 하드코딩(윈도우 종속) | adb 자동 탐색(이식성 ↑) |
| 실행 방식 | 대화형 `input()` | CLI 인자 |

### 한 줄 결론
- **`mobile_agent`** = 실전 운용에 강한 **단일 완성형 스크립트** (재시도·한글·안전확인·키보드복원 등 실전 방어장치가 촘촘함).
- **`cua + live`** = **판단 코어를 라이브러리로 분리**해 라이브 실행과 벤치마크가 공유하도록 만든 **확장·재사용 지향 아키텍처** (대신 한글입력·재시도·HITL 같은 실전 방어장치는 아직 얇음).

두 구조를 합치면 이상적: **`cua+live`의 모듈형 뼈대 + `mobile_agent`의 실전 방어장치(재시도·ADBKeyboard 한글입력·키보드 복원·HITL 안전확인)**.
