# 접근성 버튼 트리거 — 구현 기록

**2026-09-15 작성.** 브랜치 `accessibility_button`. 설계 가이드는 `android_run-handsfree-2026-09-02.md`,
타당성 검토는 `android_run-todo-hands-free-2026-08-11.md`. 이 문서는 **실제로 무엇을 바꿨고
왜 그렇게 했는지**의 기록이다.

**상태: 빌드 확인, 실기기 미검증.** 확인 순서는 §8.

---

## §0 무엇이 달라졌나 — 사용자 입장에서

**앱을 열지 않고 작업을 맡길 수 있다.**

1. 어느 앱을 쓰고 있든 **접근성 버튼**(제스처 내비: 화면에 떠 있는 동그란 버튼 / 3버튼 내비:
   내비바 오른쪽 끝)을 누른다
2. 화면 아래에 작은 카드가 뜨고 곧바로 **듣기 시작** — 말하는 동안 자막이 카드에 찍힌다
3. 말을 멈추면(또는 접근성 버튼을 한 번 더 누르면) 확정 → **3초 카운트다운**
   (`취소` / 카드 바깥 탭으로 취소, `지금 실행`으로 건너뛰기)
4. 카드가 사라지고, **보고 있던 그 화면에서** 에이전트가 시작한다
5. **실행 중에 버튼을 누르면** 새 지시 대신 `[중단]` / `[닫기]` 카드가 뜬다(§4.10)
6. 진행은 기존 진행 표시 띠·알림으로 보이고, 기록은 `run_history.txt` 에 `[경로] 접근성 버튼`
   표시와 함께 남는다

앱 UI 경로(목표 입력 → 실행, 마이크 버튼)는 **그대로**다.

---

## §1 왜 이 방식인가 — 조사하고 버린 대안

처음 요청은 "**Google Assistant 와 연결해** 앱을 안 켜고 일을 시키기"였다. 2026-09-15 에
조사한 결과 그 길은 막혀 있었다.

| 대안 | 확인한 사실 | 판단 |
|---|---|---|
| **Google Assistant + App Actions**(`shortcuts.xml`) | Assistant 는 Android 폰에서 **2026-09-04 부터 종료 중**(수 주에 걸쳐 전파, 되돌릴 수 없음). App Actions 문서는 Assistant 만 언급하고 Gemini 언급이 없다 | ❌ 사라지는 제품에 붙이는 것 |
| **Gemini + AppFunctions**(공식 후속, 기기 안) | 폰(SM-S911N, Android 16)에 `app_function` 시스템 서비스가 **있다.** 등록한 앱 16개는 **전부 삼성·구글·제휴사**(시계·캘린더·메시지·노트·TVING 등). Gemini 연동은 "2026년 5월 기준 **신뢰 테스터 비공개 프리뷰**". 제3자 개발자가 등록까지 했는데 Gemini 가 못 부른 보고가 "closed as not planned" | ❌ 우리가 쓸 수 없음. EAP 신청은 가능하나 수락·시기를 통제 못 함 |
| **Gemini 커스텀 MCP 연결**(클라우드) | 설정은 웹에서만, **미국·영어만**, 개인 계정 18세 이상 | ❌ 한국어 지시로 쓰는 우리 조건과 안 맞음. 되더라도 공개 서버 + 폰 중계가 필요 |
| **Gemini "앱 열어줘"** | 설치된 앱을 열 수는 있다 | 🔶 **작업 문장을 못 넘긴다** — 열린 뒤 다시 말해야 해서 핸즈프리가 아님 |
| **접근성 버튼 + 우리 음성 인식** | 앱이 이미 접근성 서비스라 XML 한 줄 + 콜백. 새 권한 0개 | ✅ **채택** |

> **핵심:** 백그라운드 **실행**은 원래 됐다(소켓 `RUN` 이 앱 화면 없이 완주, 2026-09-13 검증).
> 없던 것은 **"작업 문장을 서비스에 넘겨주는 입구"** 하나였다. 구글이 그 입구를 막았으니 직접 만든다.

출처: [The Decoder — Assistant 종료](https://the-decoder.com/google-will-shut-down-google-assistant-starting-september-2026-as-gemini-takes-over-on-android-and-wear-os/) ·
[Build App Actions](https://developer.android.com/develop/devices/assistant/get-started) ·
[AppFunctions 개요](https://developer.android.com/ai/appfunctions) ·
[cookbook #1151](https://github.com/google-gemini/cookbook/issues/1151) ·
[Gemini utilities](https://support.google.com/gemini/answer/15235441) ·
[Gemini 커스텀 앱 연결](https://support.google.com/gemini/answer/17209137)

---

## §2 흐름

```
[어느 앱에서든 접근성 버튼]
   │  AccessibilityButtonCallback.onClicked        (a11service 가 onServiceConnected 에서 등록)
   ▼
a11service.onAccessibilityButton()
   │  startActivity(VoiceTriggerActivity, NEW_TASK)
   ▼
VoiceTriggerActivity  ── 투명 창, 화면 아래 카드
   │  점검: 서비스 켜짐? 실행 중 아님? 오버레이 권한? 인식기? 마이크 권한?  → 걸리면 이유 보여 주고 닫힘
   │  VoiceInput.start()  → 실시간 자막
   │  확정 → 3초 카운트다운 (취소 가능)
   │  finish()
   ▼
a11service.launchFromTrigger(task)
   │  600ms 대기 (TRIGGER_SETTLE_MS)   ← 카드가 화면에서 실제로 사라질 시간
   │  새 스레드
   ▼
runTaskWithSavedConfig(task)
   │  RunConfig.load()  → 앱에서 마지막으로 고른 모델·사고수준·최대 턴
   ▼
runTask(...)   ── 중복 실행 가드(compareAndSet) → runTaskBody → attended = true → runAgent
   ▼
RunHistory.append()   ── "[경로] 접근성 버튼" + 로그 + 결과
```

---

## §3 바뀐 파일

| 파일 | 무엇을 | 왜 (자세히는 §4) |
|---|---|---|
| `res/xml/accessibility_service_config.xml` | `accessibilityFlags="flagRequestAccessibilityButton"` 추가 | 이게 있어야 시스템이 접근성 버튼을 우리 서비스에 준다. 기존 `can*` 능력은 별도 속성이라 영향 없음 |
| `a11service.kt` | 버튼 콜백 등록/해제, `onAccessibilityButton`, `launchFromTrigger`, `runTaskWithSavedConfig` | 트리거 입구 (§4.1, §4.2, §4.4) |
| `a11service.kt` | `runTask` 에 **중복 실행 가드** — 몸통을 `runTaskBody` 로 옮기고 겉에 `compareAndSet`+`finally` | 액티비티를 안 거치는 입구가 생겨 겹치는 실행을 막을 곳이 필요 (§4.3) |
| `a11service.kt` | `onServiceConnected` 에 "button requested=…" 로그 | 설정이 실제로 읽혔는지 즉시 판별 (§5) |
| `VoiceTriggerActivity.kt` (신규) | 투명 카드: 점검 → 듣기 → 카운트다운 → 넘기기 | 마이크를 열려면 포그라운드여야 함 (§4.1, §4.7~§4.9) |
| `res/layout/activity_voice_trigger.xml` (신규) | 화면 아래 카드 + 안내 문구 + `취소`/`지금 실행` | §4.9 |
| `res/drawable/trigger_card_bg.xml` (신규) | 둥근 카드 배경, 색은 테마(`?attr/colorSurface`) | 다크 모드에서 흰 카드가 번쩍이지 않게 |
| `res/values/themes.xml` | `Theme.Android_run.VoiceTrigger` — 반투명 창, 배경 흐림, 창 애니메이션 없음 | 뒤 앱이 비쳐야 "쓰던 앱 위에 잠깐"이 성립 (§4.9) |
| `AndroidManifest.xml` | `VoiceTriggerActivity` 등록 — `exported=false`, `taskAffinity=""`, `singleTask`, `excludeFromRecents`, `stateAlwaysHidden` | §4.9 |
| `RunConfig.kt` (신규) | 저장된 설정 읽기 | 액티비티 없는 경로가 설정을 읽을 곳 (§4.5) |
| `RunHistory.kt` (신규) | `run_history.txt` 위치·형식 | 원래 `MainActivity` private 함수 — 트리거 실행도 같은 형식으로 남기려고 옮김 (§4.6) |
| `MainActivity.kt` | `logFile()`·`saveLog()` 가 `RunHistory` 를 부르게 (동작 동일) | 형식이 두 벌이 되지 않게 |
| `CLAUDE.md` | 구현 현황에 한 줄 | Update rule |
| 가이드 2종 | 틀린 API 이름 정정, 새 main 반영 | §5 |

---

## §4 설계 결정과 이유

### 4.1 왜 액티비티를 거치나 — 마이크

**접근성 서비스는 마이크를 못 연다.** Android 9 부터 포그라운드가 아닌 앱은 마이크를 못 쓰고,
접근성 서비스로 상시 떠 있다는 것은 포그라운드 지위가 아니다. 마이크형 포그라운드 서비스로
우회해도 "백그라운드에서 시작된 FGS 는 마이크를 못 받는다"(API 30+)에 다시 걸린다.

액티비티는 정의상 포그라운드라 이 문제가 아예 없다. 반대로 **트리거는 서비스가 받아야**
어느 앱에서든 눌린다. 그래서 "서비스가 받고 → 액티비티를 띄운다"로 갈라졌다.
서비스에서 액티비티를 띄우는 것은 백그라운드 실행 제한에 걸리지 않는다 — 시스템이 바인딩한
서비스(접근성 등)는 면제 목록에 있다.

### 4.2 `runTask` 를 거친다 — 그리고 소켓은 합치지 않는다 ★

트리거 실행은 `runTask` 를 거쳐 **`attended = true`** 로 돈다. 사람이 버튼을 누른 실행이니 맞다 —
보안 화면 인계 카드·자격증명 카드가 뜨는 게 정상이다. 앱 UI 경로가 하는 초기화
(`cancelled`·`skipBlackPkgs`·`attended`·`cu.configure`)도 전부 그대로 탄다. `MEMORY.md` 의
"새 실행 경로를 만들 때 `runTask` 가 하는 초기화를 그대로 따라 할 것"을 **따라 하는 대신
그 함수를 직접 부르는 것**으로 지켰다 — 복사하면 한쪽만 고치는 사고가 난다.

**소켓 `RUN` 은 건드리지 않았다.** 설계 가이드 초판(2026-09-02)은 "소켓도 같은 함수로 합치자"고
적었는데, 팀원의 PR #15 이후로는 **하면 안 된다.** 소켓은 정의상 무인이라 일부러 `runAgent` 를
직접 부르고 `attended = false` 로 돈다. `runTask` 로 합치면 `attended` 가 true 가 되어
**없는 사람을 인계 카드 앞에서 3분씩 기다리던 버그**(실측 203초 중 187초)가 되살아난다.
목표는 경로를 하나로 모으는 게 아니라 **각 경로가 '사람이 있나'를 정확히 말하게 하는 것**이다.

### 4.3 중복 실행 가드 — `AtomicBoolean.compareAndSet` + `finally`

원래 `runTask` 에는 가드가 없었다. `MainActivity` 가 실행 중에 버튼을 꺼서 막고 있었을 뿐이라
**액티비티를 안 거치는 입구가 생기는 순간** 두 실행이 겹칠 수 있다. 겹치면 두 에이전트가 같은
화면을 만지고, 서비스 필드인 `cancelled`·`attended`·`skipBlackPkgs` 를 서로 덮는다.

- **왜 `compareAndSet` 인가:** 카드는 열릴 때 `isRunning` 을 확인하지만 그 뒤 **3초 카운트다운이
  돈다.** 그 사이 앱에서 `실행`을 누르면, '확인'과 '시작'이 따로인 구조에서는 둘 다 통과한다.
  확인과 시작을 한 연산으로 묶었다. 카드의 사전 확인은 사용자에게 **빨리 알려 주는 용도**일 뿐이다.
- **왜 `finally` 인가:** 예외 한 번에 깃발이 안 풀리면 앱이 **영구히 "실행 중"** 으로 잠긴다.
- **왜 몸통을 `runTaskBody` 로 옮겼나:** 기존 몸통 전체를 `try` 로 감싸면 들여쓰기가 통째로
  바뀌어 팀원 코드의 diff 가 읽을 수 없게 된다. 겉에 얇은 함수를 두면 몸통은 한 글자도 안 바뀐다.
  공개 시그니처(`runTask(task, maxTurns, model, thinking) { log }`)도 그대로다.
- **가드에 걸리면:** `BUSY_MESSAGE`("오류: 이미 실행 중입니다…")를 돌려준다. "오류"로 시작해야
  `MainActivity` 화면이 실패로 읽는다. 트리거 경로는 알림 없이 돌아오므로 **토스트로 알린다** —
  안 그러면 카드가 사라진 뒤 아무 일도 안 일어난 줄 안다.
- ⚠️ **소켓 `RUN` 은 이 가드 밖이다**(§4.2 이유로 `runTask` 를 안 거친다). 개발·측정용이라 그대로 뒀다.

**기존 앱 UI 경로에 대한 영향:** 정상 사용에서는 없다(버튼이 이미 막고 있었다). 달라지는 것은
"트리거 실행 중에 앱을 열어 실행을 누른" 경우뿐이고, 그때 겹치는 대신 BUSY 문구가 뜬다.

### 4.4 `finish()` 뒤 600ms 기다린다 — `TRIGGER_SETTLE_MS`

`finish()` 는 종료를 **예약**할 뿐이고, 창이 실제로 사라지고 뒤 앱이 다시 그려지기까지 프레임이
더 필요하다. 곧바로 찍으면 **첫 스크린샷에 우리 카드가 찍혀** 모델이 그걸 조작하려 든다.
`CLAUDE.md`「오버레이 제거는 비동기 — 떼자마자 탭 쏘면 오버레이가 먹는다」와 같은 함정이다.

- 3초 카운트다운은 이걸 막아 주지 **않는다** — 카운트다운은 카드가 떠 있는 동안 돈다.
- 테마에서 창 애니메이션을 껐지만(`windowAnimationStyle=@null`) 그건 여지를 줄일 뿐이다.
- **600ms 는 시작값이다.** 실측으로 정할 것(§8-6). `lastEventTs` 로 "화면이 멈출 때까지" 기다리는
  방식도 있지만 동영상 앱처럼 계속 움직이는 화면에서 안 끝나 상한이 필요하다 — 1차는 고정값.

### 4.5 `RunConfig` — 저장된 문자열을 **그대로** 넘긴다

액티비티가 없으니 서비스가 설정을 직접 읽어야 한다. 안 그러면 `runTask` 기본값(3.5-flash / low /
20턴)으로 **조용히** 돈다.

모델·사고수준은 **거르지 않고** 문자열 그대로 넘긴다. 조합 검증(3.7·3.8 + `minimal` → 400)은
`runTask` 안의 `cu.configure()` **한 곳**에 있다. 여기서 또 거르면 규칙이 두 벌이 되고, 설계 가이드
초판처럼 `CuClient.THINKING[index]` 로 읽으면 모델별 목록(`thinkingFor`)과 인덱스가 어긋난다
(실제로 새 main 에서 그 코드는 컴파일도 안 된다 — `thinkingIndex` 가 인자 2개로 바뀌었다).

`MainActivity` 는 `RunConfig` 를 **안 쓴다.** 화면 드롭다운 값을 저장하고 그대로 넘기는 지금 구조가
맞다 — 방금 고른 값이 곧 저장값이고, 다시 읽게 하면 "화면에 보이는 값과 도는 값이 다른" 틈이 생긴다.

### 4.6 `RunHistory` — 기록 형식을 한 곳으로

`run_history.txt` 쓰기는 `MainActivity` 의 private 함수였다. 트리거 실행은 액티비티를 안 거치니
그대로 두면 **기록이 아예 안 남는다.** 그런데 이 파일은 "어떤 설정이 어떤 결과를 냈는지"의
유일한 평문 증거다(`runTask` 의 `[설정]` 줄 주석). 형식을 서비스에 복사하면 두 벌이 되므로
`RunHistory` 로 옮기고 양쪽이 부르게 했다. 트리거 실행에는 첫 줄에 `[경로] 접근성 버튼` 을 붙여
나중에 기록을 볼 때 구분되게 했다.

기록 실패는 삼키고 로그만 남긴다 — 기록 때문에 실행 결과가 뒤집히면 안 된다.
BUSY 로 거절된 경우는 기록하지 않는다(실행이 없었다).

### 4.7 오버레이 권한이 없으면 트리거 실행을 막는다 ★

`runTask` 는 오버레이 권한이 없으면 진행 표시 띠만 조용히 건너뛴다. 그런데 **위험 액션 확인 카드와
인계·자격증명 카드도 전부 오버레이다.** 권한 없이 돌리면 그 카드들이 안 떠서, 사용자가 **볼 수도
멈출 수도 없는 실행**이 된다. 앱을 안 연 채로 도는 경로라 더 위험하다. `MainActivity` 도 같은
이유로 이 권한 없이는 실행을 시작하지 않는다 — 트리거도 똑같이 막고 "앱을 열어 허용" 안내를 띄운다.

### 4.8 마이크 권한은 트리거에서 **요청하지 않는다**

권한이 없으면 안내만 하고 닫는다. 트리거에서 요청하면 **남의 앱 위에 권한 창이 튀어나오고**,
거부되면 카드가 설명 없이 사라진다. 앱의 마이크 버튼에서 한 번 받아 두는 것을 전제로 한다.

### 4.10 실행 중에 누르면 `[중단]` — 2026-09-16 추가

처음엔 실행 중에 버튼을 누르면 "이미 실행 중입니다"만 보여 주고 닫았다. 실기기에서 써 보니
**앱을 안 연 채로 도는 실행을 멈출 방법이 앱을 열어 중단 버튼을 찾는 것뿐**이었고, 그 사이에도
에이전트는 계속 화면을 조작한다. 그래서 그 카드에 `[중단]`을 넣었다.

| 선택 | 이유 |
|---|---|
| `requestCancel()` 재사용 | `MainActivity` 중단 버튼과 **같은 함수**. 인계·자격증명 카드의 대기(`pendingLatch`)까지 깨운다 |
| "중단 요청됨 — 현재 단계가 끝나면 멈춥니다" | **협조적 취소**라 진행 중인 API 왕복이 끝나야 멈춘다. "멈췄다"고 하면 거짓이다 |
| 누르는 순간 실행이 이미 끝났으면 "이미 끝났습니다" | 없는 실행에 "중단 요청됨"이라 하면 거짓. (`cancelled` 가 true 로 남아도 다음 `runTask` 가 초기화하므로 무해) |
| 고르면 1.2초 뒤, 안 골라도 **8초 뒤 스스로 닫힘** ★ | 이 카드가 떠 있는 동안에도 에이전트는 돌고 있어 **스크린샷에 카드가 찍힌다.** 모델이 카드를 보고 헤매거나 버튼을 누를 수 있다. 에이전트가 접근성 버튼을 잘못 눌러 이 카드를 연 경우에도 화면이 영영 막히지 않게 한다 |
| 새 지시는 여전히 안 받음 | 받으면 두 에이전트가 같은 화면을 만진다(§4.3) |

### 4.9 카드 UI 의 선택들

| 선택 | 이유 |
|---|---|
| `taskAffinity=""` ★ | 없으면 `MainActivity` 와 **같은 태스크**에 들어가, 카드를 닫았을 때 쓰던 앱이 아니라 **우리 앱 화면으로 돌아간다.** 기능의 전제가 무너진다 |
| `excludeFromRecents` | 최근 앱 목록에 카드가 남지 않게 |
| `singleTask` + `onNewIntent` | 버튼을 연타해도 카드가 쌓이지 않는다. 듣는 중의 **두 번째 누름은 "말 다 했다"** — 앱 마이크 버튼과 같은 약속 |
| `stateAlwaysHidden` | 키보드가 올라오면 뒤 앱 레이아웃이 흐른다 — 승인한 탭이 빗나가던 사건(`CLAUDE.md`)과 같은 뿌리 |
| `windowIsTranslucent` | 배경색만 투명하게 하면 뒤 앱이 **안 보인다.** 불투명 창 아래는 시스템이 그리기를 멈춘다 |
| 화면 **아래쪽** 카드 | 위는 상태바·앱 헤더, 아래는 손이 닿는 곳 |
| 안내 문구 "지금 보고 있는 화면에서 작업합니다" | 첫 화면이 보던 앱이라는 건 이 기능의 **가치이자 위험**이다(사적인 화면이 그대로 모델에 간다). 알고 누르게 한다 |
| 카드 **바깥 탭 = 취소** | 투명 영역을 누르면 아무 일도 안 일어나는 게 더 헷갈린다. 카드 안 빈 곳 탭은 소비해서 스치기만 해도 취소되지 않게 |
| 실패 시 2.5초 보여 주고 닫힘 | 바로 닫으면 버튼이 안 눌린 줄 안다 |
| `onStop` 에서 무조건 접기 | 안 보이는 카드의 카운트다운이 끝나 실기기를 조작하면 안 된다(`MainActivity.onStop` 과 같은 이유) |
| 3초 카운트다운 유지 | 어디서든 한 번 눌러 실기기 조작이 시작되는 경로다. 모델의 `require_confirmation` 은 비결정적이라 방어선으로 못 믿는다 |

---

## §5 구현 중 걸린 함정 — 설계 문서가 틀렸다 ★

설계 가이드와 그 이전 TODO 문서 둘 다 `override fun onAccessibilityButtonClicked()` 를 쓰라고 적었다.
**그런 오버라이드는 `AccessibilityService` 에 없다.** 첫 빌드가 이렇게 실패했다:

```
e: a11service.kt:198:5 'onAccessibilityButtonClicked' overrides nothing.
```

맞는 방식은 **`AccessibilityButtonController` 에 콜백을 등록**하는 것이다:

```kotlin
private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
    override fun onClicked(controller: AccessibilityButtonController) = onAccessibilityButton()
    override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) { … }
}
// onServiceConnected: accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback, ui)
// onUnbind:           accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
```

핸들러로 `ui`(메인 스레드)를 넘긴 이유: 콜백 안에서 `startActivity` 를 부른다.
두 문서 모두 정정 표시를 달았다. 이 저장소에서 **문서를 그대로 믿었다가 틀린 네 번째 사례**다
(안전 승인 형식·사고수준 형식·커스텀 함수 형식에 이어). 이번엔 구글 문서가 아니라 우리 문서였다.

**설정이 실제로 읽혔는지 판별하는 로그를 넣었다.** `onServiceConnected` 가
`accessibility button requested=true|false` 를 찍는다. config 를 바꾼 뒤 서비스를 껐다 켜지 않으면
옛 설정이 남아 false 가 나오는 기기가 있다 — "버튼이 안 눌린다"를 코드 탓과 설정 탓으로 바로 가른다.

---

## §6 속도 영향

| 경로 | 추가 비용 |
|---|---|
| 앱 UI 실행 | `compareAndSet` 한 번 — 무시할 수준. 턴당 비용 없음 |
| 트리거 실행 | 실행당 **600ms 한 번**(`TRIGGER_SETTLE_MS`) + 사람이 쓰는 3초 카운트다운. **턴당 비용 없음** |
| 소켓 `RUN` | 변화 없음 |

벽시계의 ~85%가 API 왕복이라 실행당 0.6초는 체감 차이가 거의 없다. 이 값을 줄이고 싶으면 §8-6 실측 후에.

---

## §7 알려진 한계 — 안 한 것

| 항목 | 설명 |
|---|---|
| 소켓 `RUN` 은 중복 실행 가드 밖 | §4.2·§4.3. 트리거 실행 중에 PC 가 `RUN` 을 쏘면 겹친다. 개발·측정용이라 둠 |
| 결과 표시 직후 새 실행 시 진행 띠가 사라질 수 있음 | **기존 문제.** `runTask` 가 끝나면 `ui.postDelayed({ hideOverlay() }, 4000)` 을 건다. 4초 안에 새 실행이 시작되면 새 실행이 띠를 재사용하다가 그 지연 호출에 지워진다. 트리거가 생겨 연속 실행이 쉬워졌으니 가능성이 조금 올라갔다. 이번엔 안 고쳤다 |
| 에이전트가 접근성 버튼을 누를 수 있음 | 제스처 내비의 떠 있는 버튼은 좌표가 겹칠 수 있다. 누르면 `[중단]`/`[닫기]` 카드가 뜬다 — 실행이 겹치지는 않지만 턴을 헛쓰고, **최악의 경우 모델이 자기 `[중단]`을 누른다**(8초 자동 닫힘으로 노출 시간을 줄였을 뿐 막지는 못한다). 기존 🔶 자체 안전 게이트 항목과 같은 뿌리 |
| 실행 중 카드가 스크린샷에 찍힘 | §4.10. 사람이 `[중단]`을 고르는 몇 초 동안 모델도 그 카드를 본다. 에이전트를 일시정지하는 장치는 없다 |
| 마이크 권한을 앱에서 먼저 받아야 함 | §4.8 |
| 측면 버튼 두 번 누르기 직결 | 삼성 설정은 **런처 액티비티(MainActivity)** 를 연다. 트리거 카드로 보내려면 `<activity-alias>` + LAUNCHER 가 필요한데 **런처 아이콘이 하나 더 생긴다.** 사용해 본 뒤 판단 |
| 실행 결과를 카드에 안 보여 줌 | 카드는 실행 전에 닫힌다. 결과는 기존 알림으로 본다 |
| 시스템 프롬프트가 "홈에서 시작"을 전제하는지 | 확인 안 함. 다른 앱에서 실제로 돌려 보고 판단(§8-7). **돌려 보기 전에 프롬프트부터 고치지 말 것** |

---

## §8 실기기 확인 순서

> ★ **먼저 설정 > 접근성에서 `Android_run` 을 껐다 켤 것.** config 가 바뀌어 재설치만으로는
> 안 읽히는 기기가 있다.

| # | 할 것 | 확인 방법 | 안 되면 |
|---|---|---|---|
| 1 | 서비스 껐다 켜기 | `adb logcat -s A11y` 에 `accessibility button requested=true` | false 면 config 가 안 읽힌 것 — 다시 껐다 켜기 |
| 2 | 설정 > 접근성 > (고급 설정) > **접근성 버튼**에 `Android_run` 지정 | 화면에 버튼이 보이는가 | 목록에 없으면 1번부터 |
| 3 | 다른 앱(예: 설정)에서 버튼 탭 | 뒤 앱이 비쳐 보이는 카드 + `accessibility button clicked` 로그 | 로그는 있는데 카드가 없으면 액티비티 실행 문제 |
| 4 | 카드 `취소` / 바깥 탭 / 홈으로 나가기 | 카드가 닫히고 **원래 앱**으로 돌아가는가 | 우리 앱 화면이 나오면 `taskAffinity=""` 문제 |
| 5 | 말하기 | 자막 → 3초 카운트다운 → 실행 | 권한·인식기 안내 문구가 뜨면 그대로 조치 |
| 6 | 짧은 작업(예: 설정 화면에서 "와이파이 설정 열어줘") | `run_history.txt` 의 `[경로] 접근성 버튼` 실행에서 **[턴 1] 액션이 우리 카드를 향하지 않는가** | 카드 자리를 누르면 `TRIGGER_SETTLE_MS` 를 올린다 |
| 7 | 카톡 등 다른 앱에서 그 앱 기준 작업 | 모델이 "지금 이 앱"을 전제로 움직이는가 | 매번 홈으로 나가면 프롬프트 검토 |
| 8 | 트리거 실행 중에 앱을 열어 `실행` | "이미 실행 중" 문구, 실행이 겹치지 않음 | — |
| 9 | 트리거 실행 중에 버튼 다시 탭 → `[중단]` | "중단 요청됨" 후 카드 닫힘, 현재 단계가 끝나고 "■ 중단됨" 알림, `run_history` 에 중단 기록 | 멈추지 않으면 `requestCancel` 이 닿는지 logcat 확인 |
| 9-b | 같은 상황에서 아무것도 안 누름 | 8초 뒤 카드가 스스로 닫히고 실행은 계속 | — |
| 10 | 오버레이 권한을 끄고 버튼 탭 | 권한 안내 후 닫힘, 실행 안 됨 | — |
