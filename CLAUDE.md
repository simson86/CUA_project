# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Agents that drive a **real Android device via ADB** using Google's **Gemini Computer Use (CU)** model (mobile environment). A screenshot + goal go to the model each step; it returns one UI action (click/type/…); the action runs over ADB; repeat until done. There are **three parallel implementations of the same idea** — see `STRUCTURE_COMPARISON.md` for a full side-by-side.

## 구현 현황 (as of 2026-08-04)

**Update rule — 이 섹션은 기능이 바뀔 때마다 갱신한다.** 기능을 추가·수정·검증했으면 **같은 커밋에서** 아래 목록을 고쳐라. 새 줄을 덧붙이는 게 아니라 **기존 줄을 고쳐 쓰는** 문서다 (예: "구현됨" → "실기기 검증 완료"). 날짜별 `docs/progress/`는 시간순 *이력*이고, 이 섹션은 *현재 상태*다 — 역할이 다르니 둘 다 유지한다.

### 폰 단독 on-device (`android/`) — 현재 주력
- ✅ CU 루프 — 화면 캡처 → 판단 → 접근성 실행, 기본 20턴(앱에서 조절 가능) (`CuClient.runAgent`)
- ✅ 액션 10종 — `click` `long_press` `drag_and_drop` `type`(+`press_enter`) `press_key`(back/home/enter/app_switch) `go_back` `open_app` `wait` `take_screenshot` `list_apps`
- ✅ `list_apps`는 런처 있는 앱만 + 라벨 동반 → 한국어 지시("유튜브 열어줘")를 패키지명에 매칭
- ✅ 한글 입력 — 포커스 노드에 `setText` 직접 주입 (ADB 경로와 달리 ADBKeyboard 불필요)
- ✅ 실행 중 오버레이(현재 턴 표시) — 초기엔 이 띠가 화면 최상단 터치를 가로채 그 영역의 탭이 안 먹혔고, `FLAG_NOT_TOUCHABLE`로 **터치 통과**하게 수정됨. 확인 카드는 반대로 일부러 모달(아래 Gotchas)
- ✅ **읽을 수 없는 화면 인계** — `FLAG_SECURE` 등으로 캡처가 검게 나오면 에이전트가 멈추고 카드로 사용자에게 넘김(`[제가 직접 하겠습니다]`/`[그냥 계속]`/`[중단]`). 실기기 검증 완료 (2026-08-06). 감지·실패한 시도들은 아래 Gotchas
- ✅ 음성으로 목표 입력 — 목표 입력칸 옆 마이크 버튼(`VoiceInput.kt`, `SpeechRecognizer`). 누르면 듣기 시작, **말하는 중 실시간 자막**이 입력칸에 찍히고, 한 번 더 누르면 확정 → **3초 카운트다운 뒤 자동 실행**(그 사이 `취소` 가능). 텍스트 입력 경로는 그대로. `RECORD_AUDIO` 런타임 권한 + 매니페스트 `<queries>`에 `RecognitionService` 선언 필요(**없으면 엔진이 있어도 `isRecognitionAvailable()`이 false**). 실행 중엔 마이크 잠금. **빌드 확인, 실기기 미검증**
- ✅ 완료 알림 + 앱 내 로그 실시간 출력·파일 저장(`run_history.txt`)
- ✅ 중단 버튼 — 루프 중간에 사용자가 멈춤
- ✅ 최대 턴 수 사용자 설정 — 앱에서 1~40 지정(빈칸/오입력은 20, 범위 밖은 clamp 후 입력칸에 반영), `SharedPreferences`로 유지. 20턴 안에 안 끝나는 작업이 있어서 넣음. 소켓 `RUN`은 기본 20 고정 — 목표 문자열 파싱과 충돌해서 의도적으로 제외. **빌드 확인, 실기기 미검증**
- ✅ 모델·사고수준을 앱에서 선택 — 드롭다운 2개(`CuClient.MODELS`: `gemini-3.5-flash`/`3.6`/`3.7`/`3.8` × 사고수준 `minimal`/`low`/`medium`/`high`), `SharedPreferences` 유지. **사고수준은 모델마다 목록이 다르다** — 3.7·3.8 은 `minimal` 을 400 으로 거절해서 드롭다운에서 아예 뺀다(아래 Gotchas). 그래서 사고수준 목록의 출처는 `CuClient.THINKING`(전체)이 아니라 `CuClient.thinkingFor(model)` 이고, 모델을 바꾸면 사고수준 드롭다운이 다시 그려진다(고르고 있던 값은 새 모델이 받으면 유지, 아니면 `low`). 값 우선순위는 **저장값 > `android/local.properties`의 `GEMINI_MODEL`·`GEMINI_THINKING`(= 드롭다운 첫 기본 선택, 빌드 시 `BuildConfig`로) > `CuClient` 기본값(3.5-flash / low)**. 실행 로그 첫 줄 `[설정] model=… thinking=… maxTurns=…`에 기록. 소켓 `RUN`은 앱이 마지막에 고른 설정을 물려받음(그 경로는 `[설정]` 줄 없음). 새 모델은 `CuClient.MODELS`에 한 줄 추가(사고수준 제약이 있으면 `THINKING_UNSUPPORTED`에도 한 줄). 모델·사고수준 대입은 `CuClient.configure()` 로만 — 따로 대입하면 **조합**(3.8+`minimal`)을 검증할 곳이 없어지고, UI 를 안 거치는 소켓 `RUN`·오래된 저장값이 그 조합으로 들어올 수 있다. **실기기 검증 완료** (2026-09-08, SM-S931N) — 4종 드롭다운, 3.7·3.8 선택 시 `minimal` 제거, `minimal` 상태에서 3.8 로 바꿀 때 `low` 로 이동, 저장·복원, 3.8 실주행까지. **단 턴 수·정확도 A/B 는 안 쟀다**(아래 Gotchas)
- ✅ **위험 액션 확인(HITL)** — `require_confirmation` 시 동의 카드 → 승인/거부. 실기기 검증 완료 (2026-08-04). 2026-08-12 에 **승인한 탭이 빗나가던 버그** 수정 — 카드가 포커스를 뺏어 키보드가 내려가면서 좌표가 어긋났다(아래 Gotchas). 수정 후 재검증 완료
- ✅ 8080 소켓 서버 (`a11service.startServer`) — PC가 같은 Wi-Fi에서 `SHOT`/`TAP`/`RUN` 등으로 원격 조종. 짝은 `live/a11service_bridge.py`. **개발·측정용 경로이고 정의상 무인(無人)이다** — `RUN` 은 `attended=false` 로 돌아 사람을 기다리는 지점(인계·자격증명 카드)에서 기다리지 않고 즉시 실패한다(아래 Gotchas). 실기기 검증 완료 (2026-09-13): 화면 꺼짐 0.4초 실패, 정상 실행 완주
- ✅ **자격증명 입력 인계** — 에이전트가 값을 모르는 입력칸을 사용자에게 넘김. 값은 사용자가 뒤 앱의 진짜 키보드로 직접 침(앱이 값을 쥐지 않음 → 로그에도 안 남음). 카드 버튼은 경로에 따라 다르다 — 비밀번호 게이트는 `[입력했어요]`/`[중단]`, 모델 경로는 `[필요 없어요]`가 추가된 3종. **카드는 접을 수 있다**(헤더 우측 ▴/▾) — 로그인 폼의 아이디 칸을 가리는 경우가 있어서다. 접힌 상태에도 `[입력했어요]`를 남겨 둔다(가려서 접은 사용자에게 '펼치기→누르기' 두 번을 시키지 않으려고). **모델 경로에만 탈출구를 두는 이유**: 모델 판단은 헛짚을 수 있는데(인증번호 참양성 2/5) 빠져나갈 길이 없으면 사용자가 아무것도 안 치고 `[입력했어요]`를 눌러 모델에게 거짓을 보고하게 된다. 반대로 비밀번호 게이트에 이걸 열면 '모델의 추측값을 비밀번호 칸에 넣어라'가 된다. **감지 경로가 둘**:
  - **비밀번호 — 코드가 결정적으로 차단.** `type` 실행 직전 포커스 노드의 `isPassword`를 보고 막는다(`a11service.dispatch`). 화면이 로그인 화면인지는 **판정하지 않는다** — 그건 회원가입 폼·비밀번호 변경 화면에서 오탐 덩어리다. 목표 문장에 비밀번호가 적혀 있어도 막는다(모델이 진짜 값을 추측값으로 덮어쓰는 걸 방지)
  - **그 외(인증번호·아이디) — 모델이 알림.** `request_user_input` 커스텀 함수. 인증번호 칸은 코드가 볼 표식이 없고(아래 Gotchas), '그 값을 아는가'는 화면이 아니라 대화 맥락에 있어 원리적으로 노드로 못 푼다
  - **모델 경로 실기기 검증 완료** (2026-08-12, 메가스터디 앱 로그인 → 17턴에 작업 완료). 이때 **커스텀 함수의 `function_result` 를 기존 `putResult` 형식 그대로 돌려줘도 200**이 확인됐다 — 안전 승인처럼 형식을 따로 맞출 필요가 없다. 도구를 하나 더 선언해도 `computer_use` 동작은 그대로였다
  - **비밀번호 노드 게이트는 아직 미검증** — 위 실행에서 모델이 `type` 을 아예 시도하지 않아(스스로 `request_user_input` 호출) 게이트까지 내려가지 않았다. 로그 마스킹도 같은 이유로 미발동. 중단 버튼·거부 경로도 미검증
- 🔶 자체 안전 게이트 — 모델의 `safety_decision`과 별개로 우리 규칙으로 판단하는 경로. 사례 둘: **인계 카드**(화면 기반 — 픽셀로 판정)와 **비밀번호 차단**(액션 기반 — 노드 플래그로 판정). 둘 다 모델 판단을 안 쓴다. 위험 액션 차단(예: 특정 앱에서의 모든 탭)은 아직 미구현 — 모델 판단은 비결정적이라(아래 Gotchas) 필수 차단은 이쪽이 맡아야 함

### PC + ADB (`cua/`, `live/`, `mobile_agent/`) — 유지보수만
- ✅ 동작하는 베이스라인. 벤치마크 도구(`tools/bench_*.py`)가 여기 붙어 있음
- ⚠️ `safety_acknowledgement` 형식이 구식(`cua/cu_client.py`) — 위험 액션을 만나면 400. 아래 Gotchas 참조

## 기억 시스템 — 먼저 `MEMORY.md` 를 읽어라 ★

**기억(memory) 관련 작업을 시작하기 전에 저장소 루트의 `MEMORY.md` 를 먼저 읽어라.**
설계가 장황해서 여기 두면 이 파일이 감당이 안 된다. 현재 상태·빌드 함정·열린 결정이
전부 거기 있고, 이 파일에는 중복해 적지 않는다.

해당하는 작업:
- `android/app/src/main/java/com/cua/a11/memory/` 를 건드릴 때
- `runAgent` 의 `RunTrace`/`trace`, `Executor.foregroundApp()`, `appNote`/`taskNote` 를 건드릴 때
- `memory.db` · Room · KSP 빌드 설정을 건드릴 때

설계 근거 전체(아홉 개 축·스키마·결정 기록·열린 질문)는
`docs/reference/android_run-memory-2026-09-12.html` — 브라우저로 열 것.

## Gotchas — 코드만 봐선 모르는 것들

### CU 안전 승인은 공식 문서 형식으로 보내면 안 먹는다 ★
`safety_decision: require_confirmation` 을 승인 처리할 때, **구글 문서·quickstart 형식(`result` 텍스트 블록의 JSON 문자열 안에 `safety_acknowledgement`)은 이 API에서 동작하지 않는다.** 2026-08-04 실측:

| 보낸 형식 | 결과 |
|---|---|
| 텍스트 JSON 안 + 불리언 `true` (문서 형식) | ❌ 400 `must be acknowledged` |
| 텍스트 JSON 안 + 문자열 `"true"` | ❌ 400 |
| 미국식 철자 `safety_acknowledgment` | ❌ 400 |
| `function_result` / 텍스트 블록의 형제 필드 | ❌ 400 `Unknown parameter` |
| **`result`를 배열이 아닌 '객체'로** | ✅ 200 |

객체 `result`에는 이미지 블록을 못 실으므로 **실행 후 화면은 같은 `input` 배열에 `user_input`으로 따로** 보낸다. 화면을 빼면 모델이 눈이 멀어 다음 턴에 `take_screenshot`을 요청한다(대조 실험으로 확인). 구현·근거는 `CuClient.putResult` 주석 참조. **문서를 근거로 되돌리지 말 것.**

거부는 서버에 되돌리지 말고 **루프를 즉시 끝낸다** — 승인 표시 없는 후속 요청은 무조건 400이다.

### 사고수준은 SDK와 REST의 형식이 다르다 — 평면 + 소문자 ★
`android/`는 REST(`v1beta/interactions`)로 직접 쏜다. 거기서 `thinking_level`은 **`generation_config` 바로 아래에 평평하게, 소문자로** 넣어야 한다. 2026-08-06 실측:

| 보낸 형식 | 결과 |
|---|---|
| `generation_config.thinking_config.thinking_level` (**파이썬 SDK 형식**) | ❌ 400 `Unknown parameter 'thinking_config'` |
| `generationConfig.thinkingConfig.thinkingLevel` (camelCase) | ❌ 400 `Did you mean 'generation_config'?` |
| `generation_config.thinking_level = "HIGH"` (평면, 대문자) | ❌ 400 `Supported values: 'minimal', 'low', 'medium', 'high'` |
| **`generation_config.thinking_level = "high"`** | ✅ 200 |

이 서버는 **모르는 키를 반드시 거절한다**(엉터리 키로 대조군을 먼저 확인). 그래서 200 = 실제로 읽혔다는 뜻이고, `usage.total_thought_tokens`도 값에 따라 움직인다(3.5-flash: 미지정 63 / `minimal` 0 / `high` 149).

**주의:** `docs/reference/thinking-level.md`는 SDK(`google-genai`) 기준 문서다. 일반 `generate_content`는 `thinking_config` **중첩**이 맞아서, 그쪽을 근거로 REST 코드를 "고치면" 400이 된다. 안전 승인 형식(위)과 정확히 같은 구도다. ⚠️ 실제로 `cua/cu_client.py`의 `_build_generation_config`가 중첩 형식이라 **2026-07-17 이후 `live --thinking`/`CU_THINKING_LEVEL`이 조용히 무효**다(400도 안 남). 파이썬 쪽은 현재 작업 범위 밖이라 **미수정** — 근거·수치는 `docs/reference/android_run-model-thinking-2026-08-11.md` §14.

### 사고수준은 모델마다 받는 값이 다르다 — 3.7·3.8 은 `minimal` 이 없다 ★
`minimal`/`low`/`medium`/`high` 4종이 **모든 모델에 공통이 아니다.** 실측 2026-09-08
(`computer_use` mobile + 커스텀 함수 동반, 4모델 × 4수준 전수):

| 모델 | `minimal` | `low` | `medium` | `high` |
|---|---|---|---|---|
| `gemini-3.5-flash` | ✅ | ✅ | ✅ | ✅ |
| `gemini-3.6-flash` | ✅ | ✅ | ✅ | ✅ |
| `gemini-3.7-flash` | ❌ 400 | ✅ | ✅ | ✅ |
| `gemini-3.8-flash` | ❌ 400 | ✅ | ✅ | ✅ |

400 메시지가 허용값을 직접 알려준다 — `'minimal' is not a supported thinking level for this
model. Allowed values are: medium, low, high.` 즉 신형에는 '사고 끄기'가 없다(3.5·3.6 은
`minimal` 에서 `total_thought_tokens=0` 이 나온다).

이게 UI 에 주는 함의: 모델 × 사고수준을 **자유 조합으로 두면 안 된다.** 조합이 틀리면 첫
`cuCall` 에서 400 이라 액션 하나 못 해보고 죽는다. 특히 조용한 함정은 **오래된 저장값** —
3.5 로 `minimal` 을 쓰던 사용자가 모델만 3.8 로 바꾸면 그 조합이 만들어진다. 그래서
`CuClient.thinkingFor(model)` 이 드롭다운 목록을 정하고, `CuClient.configure()` 가 마지막
방어선으로 조합을 다시 거른다(소켓 `RUN` 처럼 UI 를 안 거치는 경로 때문에 둘 다 필요하다).

⚠️ **`3.7`·`3.8` 이 "돌아간다"는 확인됐지만 "더 낫다"는 안 쟀다.** 실기기에서 3.8 실주행까지
확인했다(2026-09-08). 하지만 **같은 과제를 3.5 와 나란히 돌린 A/B 는 없다** — 턴 수·정확도를
비교한 적이 없으므로 "3.8 이 낫다/못하다"고 쓰지 말 것. 이 프로젝트에서 속도는 한 턴의 토큰이
아니라 **총 턴 수**이므로(벽시계의 ~85%가 API 왕복), 기본 모델을 올리려면 폰에서 같은 과제로
각 3회 이상 돌려 완주 여부와 턴 수를 봐야 한다. `DEFAULT_MODEL` 은 그때까지 `gemini-3.5-flash`
로 둔다. 단가도 모른다 — `tools/bench_thinking.py` 의 가격표는 3.5 조차 "추정 placeholder"라,
턴이 줄어도 총비용이 준다는 보장이 없다. 참고로 1턴짜리 `total_thought_tokens` 숫자는 표본이 1이라 순서도
안 맞는다(3.5 는 high 30 < medium 80) — **200/400 말고는 저 실측에서 아무것도 읽지 말 것.**

### 커스텀 함수는 쓸 수 있다 — 역시 평면 ★
CU API 는 `computer_use` 와 **커스텀 함수 선언을 함께** 받는다. 이걸로 모델이 우리 코드에 말을 거는 통로를 만들 수 있다(`CuClient.requestUserInputTool`). 2026-08-12 실측:

| 보낸 형식 | 결과 |
|---|---|
| `computer_use` 만 (기준선) | ✅ 200 |
| 엉터리 `type` (대조군) | ❌ 400 — 지원 목록에 `function` 이 있음이 에러 메시지로 확인됨 |
| `{type:"function", function:{…}}` (중첩) | ❌ 400 `Unknown parameter 'function'` |
| `{function_declarations:[…]}` | ❌ 400 `'type' is required` |
| **`{type:"function", name, description, parameters}` (평면)** | ✅ 200 |

**안전 승인·사고수준과 정확히 같은 구도다** — 이 엔드포인트는 중첩을 안 받는다. SDK/문서를 근거로 중첩으로 "고치면" 400이다. 세 번째 사례이니 이제 이 서버의 성질로 보면 된다.

**호출률 실측**(3.5-flash/low, N=5, 인코딩 수정 후):

| 화면 | 작업 | `request_user_input` |
|---|---|---|
| 네이버 로그인 | "네이버에 로그인해줘" | 5/5 |
| 네이버 로그인 | 같은 작업, 프롬프트 규칙 없음 | 5/5 |
| 네이버 로그인 | **"회원가입 링크 눌러줘"**(로그인 불필요) | **0/5** (전부 `click`) |
| 인증번호(ChatGPT 메일 코드) | "로그인을 끝내줘" | **12/17** (아래 편차 주의) |
| 인증번호 | **"화면에 뭐라고 적혀 있는지 알려줘"** | **0/5** (전부 완료 처리) |

**오탐은 두 화면 모두 0/5다** — 화면에 아이디·비밀번호·인증코드 칸이 있어도 그 작업에 필요 없으면 안 부른다. 트리거가 '화면 생김새'가 아니라 '내가 막혔는가'라서 그렇다. 걱정할 만한 쪽은 오탐이 아니라 **미탐**이다.

**인증번호에서 2/5로 떨어지는 이유는 '헤매서'가 아니다.** 나머지 3회는 `list_apps`/`open_app` 이었고 intent 가 명확했다 — *"이메일 앱을 찾기 위해 앱 목록을 확인합니다"*, *"Open Gmail to check the verification code"*. **모델이 사용자에게 묻는 대신 자기가 메일을 열어 코드를 읽으러 간다.** 기능적으로는 성립하는 경로지만(코드가 같은 폰에 있다), ⑴ 사용자의 메일을 열어 읽는다는 뜻이고 ⑵ 턴을 더 쓴다. 이 경로로 코드를 읽어 오면 그 `type` 은 게이트에 안 걸린다(인증번호 칸은 `password=false`).

그래서 프롬프트에 *"코드는 이 폰에서 찾을 수 있어도 주인의 것이다 — 메일·메시지를 열어 찾지 말고 `request_user_input` 으로 요청하라"* 를 넣었다. 같은 화면·같은 작업으로 A/B(N=12씩, 두 배치로 나눠):

| 규칙 | 인계 요청 | 앱 뒤지기(`list_apps`/`open_app`) |
|---|---|---|
| 메일 금지 문장 없음 | 10/12 | 2/12 |
| **문장 추가** | **12/12** | **0/12** |

방향은 두 배치에서 일관됐고 문장 비용도 없어 유지한다. 다만 **2/12 대 0/12 는 이 표본으로 통계적으로 구분되지 않는다** — "효과가 확인됐다"고 쓰지 말 것.

⚠️ **같은 조건의 호출률이 세션마다 크게 흔들린다.** 메일 금지 문장 없는 동일 조건에서 한 세션은 **2/5**, 다른 세션은 **10/12** 였다(화면·작업·모델·사고수준 모두 동일). N=5 짜리 단발 측정으로 이 값을 판단하지 말 것. 위 표의 다른 행들도 N=5 라 같은 한계를 갖는다 — **오탐 0/5 처럼 방향이 뚜렷한 것만 신뢰할 만하다.**

⚠️ **초기 측정("규칙 없으면 0/5")은 틀렸다 — 테스트 하네스 버그였다.** PowerShell 5.1 의 `Invoke-WebRequest` 는 `-Body` 에 문자열을 주면 기본 인코딩으로 보내 한글이 전부 `?` 가 된다. 모델이 `"task description is unreadable (contains only question marks)"` 라고 답해서 발각됐다. 목표를 못 읽은 모델이 로그인 화면만 보고 아무렇게나 반응한 수치였다. 재측정용 스크립트를 쓸 땐 `-Body ([Text.Encoding]::UTF8.GetBytes($json))` 로 **바이트 배열**을 넘길 것. (안드로이드 앱은 OkHttp 가 UTF-8 로 보내므로 이 버그와 무관하다.)

그래서 **프롬프트 규칙이 호출을 켜는 스위치라는 근거는 없다.** 다만 규칙 교체 자체는 여전히 옳다 — 옛 문장은 "아무 것도 하지 말고 인증이 필요하다고 보고하라"라서 **실행이 거기서 끝났다**. 바뀐 문장은 이어가게 한다. 비교 대상이 '규칙 없음'이지 '옛 규칙'이 아니었다는 점에 주의.

### 자격증명이 담긴 목표는 서버가 아예 막는다 ★
`"아이디 hong123 으로 네이버에 로그인해줘"` → **400 Input blocked**, 이유는 "third-party account 에 제공된 자격증명으로 접근 = credential harvesting". 목표 문장이 모델에 닿기도 전에 차단된다.

두 가지 함의: ⑴ 사용자가 목표에 아이디·비밀번호를 적는 사용법은 **성립하지 않는다**(그래서 자격증명 게이트의 "목표에 적혀 있어도 막는다"는 방어적 성질일 뿐 실전에서 탈 일이 거의 없다). ⑵ **비밀번호 노드 게이트를 검증하려고 목표에 계정을 적는 방법은 못 쓴다** — 400 으로 막혀 모델이 `type` 을 시도할 기회 자체가 없다. 다른 검증 경로를 찾아야 한다.

### 인증번호 칸은 코드가 볼 표식이 없다 ★
"에이전트가 모르는 값"을 노드로 잡으려던 시도는 인증번호에서 막힌다. 실측(2026-08-12, ChatGPT 이메일 인증 화면):

| 후보 | 값 |
|---|---|
| `isPassword` | ❌ false — 인증번호는 **가리지 않는다**(사용자가 숫자를 눈으로 확인해야 함) |
| `resource-id` | ❌ 빈 문자열 |
| `content-desc` | ❌ 빈 문자열 |

"코드"라는 라벨은 입력칸 속성이 아니라 **옆의 별도 TextView**다. 즉 화면 텍스트를 읽지 않으면 판별할 수 없다.

**더 근본적으로, 화면을 완벽히 읽어도 안 풀린다.** 필요한 판단이 두 조각인데 둘째가 화면 밖에 있기 때문이다 — ⑴ 이 칸이 무엇을 요구하는가(화면에 있음) ⑵ **에이전트가 그 값을 가졌는가**(목표 문장·히스토리에만 있음). 화면에 "아이디를 입력하세요"가 떠 있을 때, 목표가 "아이디 abc로 로그인해줘"면 입력해야 하고 "로그인해줘"면 물어봐야 한다 — **화면은 동일한데 정답이 반대다.** 그래서 이 판단은 모델만 할 수 있고, 통로가 `request_user_input`이다.

### `safety_decision`은 매번 붙지 않는다
같은 "알람 삭제" 작업이 한 실행에선 `require_confirmation`이 붙고 다음 실행에선 안 붙어 확인 없이 실행됐다. 규칙표가 아니라 모델의 그때그때 판단이라 **비결정적**이다. 확인 카드를 최종 방어선으로 신뢰하지 말 것. 판단 기준은 구글 내장 정책 7종이며 API로는 *끄는* 것만 가능하고 새 기준 추가 입구는 없다.

### 보안 화면은 캡처가 '실패'하지 않는다 — 검은 이미지가 온다 ★
`FLAG_SECURE` 화면(카카오톡 잠금, 토스 비밀번호 등)에서 `takeScreenshot`은 `onFailure`가 아니라 **`onSuccess` + 내용만 검은 비트맵**을 준다. 모델은 검은 이미지를 받아 눈이 먼 채 헤매고, 심하면 서버의 이상행동 차단에 걸린다(실측: 8턴 헤매다 400 `erratic or unnecessary action`). 그래서 캡처 실패 감지로는 못 잡고 **픽셀로 판정**한다 — 본문(상하 10% 제외)이 정확히 `#000000`인 비율 ≥95%, PNG 바이트 수(<100KB)로 먼저 걸러 정상 화면은 디코딩도 안 한다.

**실측으로 버려진 신호들 — 되살리기 전에 데이터부터 볼 것:**

| 후보 | 결과 |
|---|---|
| 노드 개수가 많다 | ❌ 보안 6~15 vs 정상 3~171. 완전 중첩 |
| `isPassword` 노드 존재 | ❌ 카카오톡·토스 모두 커스텀 키패드라 0개 (★ **이 화면에서만** 무용. 아래 주의) |
| 픽셀만으로 보안 화면 특정 | ❌ 유튜브 검은 영상이 100%로 보안 화면(99%)보다 더 검다 |

결국 **구분을 포기하고 사용자에게 선택지를 주는 것**이 답이었다. 오탐 비용이 탭 한 번이면 감지가 헐거워도 된다.

**노드는 판정에 쓰지 않는다.** 노드 트리로 키패드 좌표를 읽으면 비밀번호 자동 입력이 기술적으로 가능하지만(토스의 무작위 배열도 읽힌다), 앱이 의도적으로 가린 화면을 우회하는 것이라 하지 않는다.

⚠️ **위 표를 근거로 `focusedIsPassword()`(자격증명 게이트)를 지우지 말 것.** 표가 말하는 건 **커스텀 키패드 잠금화면**에서 0개라는 것이다 — 거긴 `EditText` 자체가 없다. **표준 로그인 폼은 정반대로 잘 뜬다**(2026-08-12 실측: 네이버 로그인 웹뷰에서 `EditText` 3개 중 password 1개, 비밀번호 칸 탭 시 포커스 노드가 `password=true`). Chrome 은 HTML `<input type="password">` 를 그대로 노출하고 `id` 속성을 `resource-id` 로 준다. 두 화면은 서로 다른 상황이고 담당 게이트도 다르다(잠금화면 = 검은 화면 인계, 로그인 폼 = 자격증명 게이트).

### 무인 실행에서 '사람에게 넘기기'는 그냥 시간 낭비다 ★
인계 카드·자격증명 카드는 **답할 사람이 있을 때만** 뜻이 있다. 소켓 `RUN` 은 정의상 무인인데
(PC가 원격으로 돌린다) 카드를 띄우면 타임아웃까지 그대로 버린다. 실측 2026-09-13:

```
18:00:00  cmd: RUN Open the Clock app     ← 화면이 꺼져 있었다
18:03:07  [턴 1]                          ← 3분 7초. HANDOVER_TIMEOUT_MIN = 3L
18:03:24  [완료]                          ← 화면이 켜진 뒤로는 17초
```
**203초 중 187초가 대기.** 이게 두 번 걸리면 6분 22초 — 오래 "소켓은 화면이 꺼지면 Doze 로
죽는다"고 적어 뒀던 그 숫자다. **틀린 진단이었다**(`readTimeout` 60초로 6분이 설명 안 되는데
그때 더 안 팠다).

**근본 원인은 꺼진 화면과 보안 화면이 픽셀로 똑같이 검다는 것이다.** 위의 "보안 화면은 캡처가
'실패'하지 않는다" 항목이 픽셀 판정을 택한 대가다 — 사람이 앞에 있으면 오탐 비용이 탭 한 번
이라 괜찮지만, **무인 실행에서는 3분**이다.

고친 방식:
- **`PowerManager.isInteractive()`** 로 둘을 구분한다. 화면이 꺼져 있으면 보안 화면이 아니라
  그냥 꺼진 것이고, **에이전트는 아무것도 할 수 없다** — 깨워도 잠금화면이다(실측:
  `KEYCODE_WAKEUP` 후 `pkg=com.android.systemui`, 캡처는 여전히 검정). 그러니 즉시 실패한다.
- **`attended` 깃발** — 앱 UI `true`, 소켓 `false`. 무인이면 인계·자격증명 카드를 아예 안
  띄우고 던진다. 소켓은 예외를 잡아 **한 줄 문자열로 돌려준다**(연결을 끊으면 PC 쪽이
  이유를 못 읽는다).
- **진행 표시 띠에 `FLAG_KEEP_SCREEN_ON`** — 앱 UI 실행 중에는 화면이 안 꺼진다. 띠의 수명이
  정확히 실행과 같아서 자연스럽다.

⚠️ **새 실행 경로를 만들 때 `runTask` 의 초기화를 그대로 따라 할 것** —
`cancelled` · `skipBlackPkgs` · `attended` · `cu.configure`. 소켓 `RUN` 이 이걸 안 해서
**중단이 아예 안 됐다**(`cancel` 기본값이 `{false}`라 앱의 중단 버튼도, 인계 타임아웃이 세우는
`requestCancel()` 도 무시됐다).

### 콜백 대기에 상한이 없으면 소켓 서버가 통째로 죽는다 ★
소켓 서버는 **단일 스레드**다 — `accept()` 루프에서 `runAgent` 를 그대로 돌린다. 그 스레드가
멈추면 포트는 `LISTEN` 인데 **아무 명령도 안 받는다.** 실제로 그 상태를 봤다: TCP 핸드셰이크는
되는데 268초 동안 앱 로그가 0줄(`client connected` 조차 없음). 앱 재시작 전엔 안 풀린다.

`capturePngBlocking`(`takeScreenshot` 콜백)·`dispatchBlocking`(`dispatchGesture` 콜백)·
`hideForShot`(`ui.post`) 셋 다 상한 없이 기다리고 있었다. 전부 상한을 뒀다(10/10/5초).
**새로 `latch.await()` 를 쓸 때 반드시 상한을 둘 것** — 여기서 무한 대기는 '그 요청이
느려지는' 문제가 아니라 **'서버가 재시작 전까지 죽는'** 문제다.

### 우리가 화면에 그린 것으로 화면을 판정하면 안 된다 ★
"화면이 다시 읽히면 자동 재개"를 두 번 시도했다가 둘 다 접었다:

- **알림 방식** — 우리가 띄운 알림 배너가 보안 화면 위에 그려지며 `36KB → 112KB`가 되어 '검지 않음' 판정, 3초 만에 자동 재개. 사용자가 알림을 보려고 손을 뻗는 순간 이미 취소된 뒤였다
- **카드 유지 + 가운데 띠만 판정** — 그래도 1초 만에 재개

자기가 그린 것을 자기가 판정하는 구조 자체가 문제다. 지금은 **자동 판정 없이 사용자의 명시적 선택만** 받는다(`[제가 직접 하겠습니다]` → 5초 뒤 재캡처 → 여전히 검으면 카드 재표시).

### 일부 앱은 우리 오버레이를 아예 안 보이게 한다
토스 등 금융 앱은 `setHideOverlayWindows`(API 31+)로 자기 화면 위의 앱 오버레이를 시스템이 숨기게 한다(탭재킹 방어). 실측에서 **인계 카드가 '떠 있는데 안 보였다'** — 다른 앱으로 나가야 보였다. 우회할 수 없고 우회해서도 안 된다. 진행 표시 띠도 같은 이유로 그런 앱에서는 안 보인다.

### 오버레이 터치 정책이 셋 다 다르다
| 오버레이 | 정책 | 이유 |
|---|---|---|
| 진행 표시 띠 | 터치 완전 통과(`FLAG_NOT_TOUCHABLE`) | 에이전트의 탭을 방해하면 안 됨 |
| 위험 액션 확인 카드 | 전체화면(`MATCH_PARENT`) + `FLAG_NOT_FOCUSABLE` | 화면 전체가 창 안이라 터치는 다 흡수(오터치 방지 유지). 포커스는 **뺏으면 안 된다** — 아래 참조 |
| 인계 카드 | 카드 안만 터치(`FLAG_NOT_TOUCH_MODAL`) | 사용자가 뒤 앱 키패드를 눌러야 함 |
| 자격증명 입력 카드 | 인계 카드와 동일 + `FLAG_NOT_FOCUSABLE` | 포커스를 가져가면 뒤 앱 입력칸이 포커스를 잃어 **키보드가 내려간다** — 그러면 정작 입력을 못 한다 |

"오버레이는 이렇게 하는 것"으로 일반화하지 말 것. 새로 추가할 때 어느 쪽인지 먼저 정해야 한다.

### 오버레이가 포커스를 뺏으면 승인한 탭이 빗나간다 ★
확인 카드는 원래 `flags=0`(포커스 가져가는 전체화면 모달)이었다. 그러면 카드가 뜨는 순간 **뒤 앱 입력칸이 포커스를 잃어 키보드가 내려가고**, 키보드가 사라지면 뒤 앱 레이아웃이 다시 흘러 버튼 위치가 바뀐다. 그런데 승인 후 우리가 쏘는 탭 좌표는 **카드가 뜨기 전 화면**을 보고 모델이 계산한 값이다 → 엉뚱한 곳을 누른다.

실측(2026-08-12, ChatGPT 메시지 전송): 전송 버튼에 `require_confirmation` 이 붙어 **3번 승인했는데 3번 다 안 나갔다**. 카드가 닫히면 키보드가 다시 올라와 다음 턴엔 반대로 어긋난다. 모델은 결국 포기하고 사용자에게 직접 누르라고 보고했다.

`FLAG_NOT_FOCUSABLE` 로 고쳤다. 이 플래그가 `FLAG_NOT_TOUCH_MODAL` 을 함께 켜지만 이 창은 `MATCH_PARENT × MATCH_PARENT` 라 **'창 밖'이 존재하지 않아** 모든 터치를 그대로 흡수한다 — 오터치 방지는 유지된다.

**수정 후 같은 작업 재실행(실기기 검증 완료):** 확인 카드 **1번**, 승인 즉시 전송, 모델이 답변까지 읽고 완료. 수정 전에는 3번 승인에도 전송 실패였다.

**이 버그가 지금까지 안 보인 이유**: 탭 대상이 키보드 영향을 받는 위치일 때만 드러난다. 메시지 전송이 처음으로 그 조건을 만들었다. 인계 카드·자격증명 카드는 처음부터 `FLAG_NOT_FOCUSABLE` 이었다(사용자가 뒤 앱 키보드를 써야 하므로) — 같은 함정을 우연히 피해 간 것이다.

### 오버레이 제거는 비동기 — 떼자마자 탭 쏘면 오버레이가 먹는다
`WindowManager.removeView`는 제거를 예약만 하고 돌아온다. 확인 카드는 전체화면 모달(뒤 앱 오터치 방지)이라, 승인 직후 곧바로 `dispatchGesture`를 쏘면 **아직 떠 있는 카드가 그 탭을 삼킨다**(= 아무 일도 안 일어나고 모델이 무한 재시도). `removeViewImmediate` + 프레임 대기로 해결. 같은 이유로 `hideForShot()`도 `Thread.sleep(32)`를 둔다. 새 오버레이를 추가할 때 반드시 고려할 것.

### `ADBBridge._run`은 OS 로케일(cp949)로 디코딩한다 — 아래 Architecture 참조

## Commands

- **Use `py`, never `python`.** On this machine PATH `python` is msys2 (no pip / no `google-genai`) and will raise `ModuleNotFoundError: google`. The real interpreter is the `py` launcher (Python 3.14) where `google-genai` + `python-dotenv` are installed.
- **Run the live agent:** `py live/main.py "작업 설명"` (from repo root). No arg → defaults to `"Open the Settings app"`.
- **Run the monolithic agent:** `py mobile_agent/agent.py` (prompts interactively for the task).
- **Regenerate the git change report:** `py tools/git_report.py` (usually automatic — see Automation).
- **Prerequisites:** a connected device (`adb devices` shows one), and `GEMINI_API_KEY` in `.env` (copy from `.env.example`).
- **No test/build/lint framework.** The only self-tests are `if __name__ == "__main__"` smoke blocks (e.g. `mobile_agent/device.py` prints screen size and taps center).

## Architecture

### Two codebases, two different Gemini SDKs — do not mix them
- **`cua/` + `live/` (modular):** uses `client.interactions.create` with **server-managed history** via `previous_interaction_id`. `cua/` is a pure *judgment core* (knows nothing about ADB or benchmarks); `live/` is the *execution layer* (ADB) plus the multi-turn loop in `live/main.py`. `live/adb_bridge.py` adds the repo root to `sys.path` to import `cua`.
- **`mobile_agent/` (monolith):** uses `client.models.generate_content` with **client-managed history** (it accumulates `contents` itself). Standalone; has its own `ADBBridge` in `device.py`. Keep changes here separate from the `cua`/`live` line.

### Action dispatch (both codebases)
Actions are dispatched by name: `getattr(bridge, action.name)(**action.args)`. **`ADBBridge` method names must equal CU action names** (`click`, `type`, `long_press`, `drag_and_drop`, `press_key`, `go_back`, `open_app`, `wait`, `list_apps`, `take_screenshot`). To support a new action, add a method of that name; use `**_` to absorb extra args like `intent`. Unknown/failed actions are fed back to the model as `{"status":"error",...}` instead of crashing, so the model can self-correct.

### Coordinates
CU returns **0–1000 normalized** coords. Convert to real pixels with `cua.denormalize(x, y, width, height)` (`live`) or `_px()` (`mobile_agent`). Screen size is read dynamically from `wm size`.

### Korean text input (live)
`live` types via **ADBKeyboard + base64 broadcast** (`am broadcast -a ADB_INPUT_B64 --es msg <base64>`), which bypasses shell parsing so Korean/spaces/special chars survive. `main.py` calls `bridge.ensure_adb_keyboard()` on startup (installs the bundled `live/vendor/ADBKeyboard.apk` if missing, switches IME, saves the original) and `bridge.restore_keyboard()` in a `finally`. `mobile_agent` has the same mechanism; the older `adb input text` path is ASCII-only.

### Gotcha: `ADBBridge._run` decodes with the OS locale (cp949 here)
It uses `subprocess.run(..., text=True)`, so adb output containing non-ASCII (e.g. a `uiautomator` XML dump with Korean) will raise `UnicodeDecodeError`. Production paths return ASCII, so this is fine today — but any new feature that parses non-ASCII adb output must read it as UTF-8 explicitly.

## Session continuity — read this first
**At the start of a session, read the most recent `docs/progress/YYYY-MM-DD.md`** to recover what was done and stay consistent across terminal restarts. These dated files are the durable progress journal (tracked in git), auto-generated from commit history — so write **descriptive commit messages**, since they become the journal entries.

## Gemini Computer Use reference
**`docs/reference/gemini-computer-use.md`** distills the 4 official sources (DeepMind/Flash announcements, the CU API + safety docs, and the `gemini-android-computer-use-quickstart` repo our code derives from). Treat it as the *spec* for what this project implements — consult it before adding actions, touching coordinates, or changing the safety/loop flow. Key facts: model `gemini-3.5-flash` with `environment:"mobile"`; actions returned as function_calls dispatched to same-named `ADBBridge` methods; coords 0–1000 normalized; `safety_decision:"require_confirmation"` must be acknowledged (`android/`는 실제 사용자 확인 카드로 처리 — 전송 형식은 위 Gotchas 참조, **문서의 형식은 틀렸다**. `cua`/`live`는 아직 데모용 자동 승인이라 미수정); the quickstart's `click(y, x)` has **swapped arg order** — ours uses `(x, y)`.

## Project direction: moving off the cable (ADB → Android AccessibilityService)
**New goal (2026-07-18):** run **without USB/ADB** by putting an on-device Android AccessibilityService in charge of screen read + action, instead of a PC driving the phone over a cable. **`docs/reference/android-accessibility.md`** is the reference for this pivot — read it before designing the cable-less path. Key implication: this migrates the *execution layer* from Python+ADB (`live/adb_bridge.py`) to a **Kotlin on-device app** (`dispatchGesture` for taps/swipes, `performGlobalAction` for back/home, `takeScreenshot()` for CU input, `getRootInActiveWindow()` for the node tree). The `cua/` judgment core (screenshot+goal→action) and 0–1000 `denormalize` coordinate logic stay conceptually valid. The ADB codebase (`cua`/`live`/`mobile_agent`) remains the working baseline until the accessibility path lands.

## Automation & repo conventions
- **Auto-commit:** a `Stop` hook in `.claude/settings.local.json` runs `git add -A` + commits (message `[auto] <timestamp>`) at the end of each turn, only when something changed. Make your own descriptive commits for real work; the hook is a safety net.
- **On every commit**, `.git/hooks/post-commit` regenerates two things (via `py tools/*.py`):
  - `git_report.html` — self-contained HTML diff viewer. **Gitignored** (artifact) — don't commit it.
  - `docs/progress/<date>.md` — dated progress journal. **Tracked** (committed). Generated by `tools/progress_log.py`; **don't edit by hand**. Commits that touch *only* `docs/progress/` are excluded from the journal so the file reaches a fixpoint and the Stop hook doesn't loop.
- The post-commit hook lives in `.git/` (not tracked); recreate it after a fresh clone.
- **`docs/reference/` 파일명에는 날짜를 붙인다 — `<주제>-YYYY-MM-DD.md`** (예: `android_run-voice-2026-08-11.md`). 날짜는 **작성일**이지 갱신일이 아니다 — 문서를 고칠 때마다 이름을 바꾸면 링크가 계속 깨진다. 폴더를 열었을 때 무엇이 최신이고 무엇이 오래된 판단인지 바로 보이게 하는 것이 목적이다. **새 문서를 만들 때 처음부터 날짜를 넣어라**(나중에 바꾸면 참조를 전부 고쳐야 한다). 이 폴더는 2026-08-13부터 전부 깃 공유 대상이므로, 넣기 전에 비밀이 섞였는지 볼 것 — API 키는 값 대신 `GEMINI_API_KEY=…`.
- **Secrets:** `.env` is gitignored; keys are `GEMINI_API_KEY`, plus unused `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`AUX_MODEL`. `live/vendor/ADBKeyboard.apk` **is** committed intentionally (offline install).
- Model id in use: `gemini-3.5-flash` (see `cua/cu_client.py` and `mobile_agent/agent.py`).
