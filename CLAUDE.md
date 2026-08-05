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
- ✅ 완료 알림 + 앱 내 로그 실시간 출력·파일 저장(`run_history.txt`)
- ✅ 중단 버튼 — 루프 중간에 사용자가 멈춤
- ✅ 최대 턴 수 사용자 설정 — 앱에서 1~40 지정(빈칸/오입력은 20, 범위 밖은 clamp 후 입력칸에 반영), `SharedPreferences`로 유지. 20턴 안에 안 끝나는 작업이 있어서 넣음. 소켓 `RUN`은 기본 20 고정 — 목표 문자열 파싱과 충돌해서 의도적으로 제외. **빌드 확인, 실기기 미검증**
- ✅ **위험 액션 확인(HITL)** — `require_confirmation` 시 동의 카드 → 승인/거부. 실기기 검증 완료 (2026-08-04)
- ✅ 8080 소켓 서버 (`a11service.startServer`) — PC가 같은 Wi-Fi에서 `SHOT`/`TAP`/`RUN` 등으로 원격 조종. 짝은 `live/a11service_bridge.py`
- ⬜ 자체 안전 게이트 — 모델의 `safety_decision`과 별개로, 우리 규칙(포그라운드 패키지·노드 텍스트)으로 확인을 강제하는 경로. 모델 판단은 비결정적이라(아래 Gotchas) 필수 차단은 이쪽이 맡아야 함

### PC + ADB (`cua/`, `live/`, `mobile_agent/`) — 유지보수만
- ✅ 동작하는 베이스라인. 벤치마크 도구(`tools/bench_*.py`)가 여기 붙어 있음
- ⚠️ `safety_acknowledgement` 형식이 구식(`cua/cu_client.py`) — 위험 액션을 만나면 400. 아래 Gotchas 참조

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

### `safety_decision`은 매번 붙지 않는다
같은 "알람 삭제" 작업이 한 실행에선 `require_confirmation`이 붙고 다음 실행에선 안 붙어 확인 없이 실행됐다. 규칙표가 아니라 모델의 그때그때 판단이라 **비결정적**이다. 확인 카드를 최종 방어선으로 신뢰하지 말 것. 판단 기준은 구글 내장 정책 7종이며 API로는 *끄는* 것만 가능하고 새 기준 추가 입구는 없다.

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
- **Secrets:** `.env` is gitignored; keys are `GEMINI_API_KEY`, plus unused `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`AUX_MODEL`. `live/vendor/ADBKeyboard.apk` **is** committed intentionally (offline install).
- Model id in use: `gemini-3.5-flash` (see `cua/cu_client.py` and `mobile_agent/agent.py`).
