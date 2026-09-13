# MEMORY.md — CU 에이전트 기억 시스템

기억 시스템의 **현재 상태**와 **작업 전에 알아야 할 것**만 담는다.
설계 근거·대안 검토·결정 기록은 전부 아래 명세서에 있다.

> **설계 명세**: `docs/reference/android_run-memory-2026-09-12.html` (브라우저로 열 것)
> 반드시 답해야 하는 아홉 개 질문으로 구성돼 있고, 부록에 스키마·파일구조·결정기록·열린질문이 있다.

**Update rule** — `CLAUDE.md` 의 구현 현황과 같은 규칙이다. 기능이 바뀌면 **같은 커밋에서**
아래를 고쳐라. 줄을 덧붙이는 게 아니라 **기존 줄을 고쳐 쓰는** 문서다.

---

## 한 줄 요약

에이전트가 남긴 일화에서 의미 기억을 뽑아 **기기 안 SQLite** 에 쌓고, 다음 실행에서
상황에 맞는 것만 예산 안에서 모델 입력에 얹는다. **읽기·주입·사람의 편집까지 돈다**
(Unit 1~3). 아직 **자동으로 쓰지는 않는다** — 기억은 사람이 목록 UI 로 넣는 것뿐이고,
실행에서 뽑아내는 리플렉터는 Unit 5 다. 기억이 하나도 없으면 note == null 이라
요청 본문이 종전과 완전히 동일하다.

---

## 구현 현황

| Unit | 무엇 | 상태 |
|---|---|---|
| **1** | **DB 뼈대 + 구조화 로깅** | ✅ **실기기 검증 완료** (2026-09-12, SM-S931N) |
| **2** | **`memory` 테이블 + 읽기 경로** | ✅ **실기기 검증 완료** (2026-09-13) — 주입까지 확인 |
| **3** | **기억 목록 UI (보기·추가·수정·삭제)** | ✅ **실기기 검증 완료** (2026-09-13) |
| 4 | 측정 세트 + 소켓 `DUMP` | ⬜ **다음** → **판단 ① 수동 기억이 효과 있나** |
| 5 | 리플렉터 (추출 + §3 필터) | ⬜ |
| 6 | reconciliation + 승격 | ⬜ → **판단 ② 자동 학습이 효과 있나** |
| 7 | 랭킹 점수식 | ⬜ |
| 8 | 생애주기 (보존·압축·감쇠·상한·버전태깅) | ⬜ ← **여기까지가 완성** |
| 9 | `RECIPE` (절차 재사용) | ⬜ 선택 |

**Unit 8 까지가 완성이다.** Unit 9 는 다른 종류의 기억을 얹는 확장이라 없어도 완결된다.
반면 Unit 8 이 없으면 앱이 업데이트될 때마다 낡은 기억이 계속 주입돼 **시간이 지날수록
나빠진다**.

> **소켓 `DUMP` 는 원래 Unit 2 였는데 Unit 4 로 합쳤다.** "쌓인 로그를 사람이 본다"는
> 목적은 **Android Studio 의 Database Inspector** 가 더 잘 해낸다(실시간·GUI·임의 쿼리,
> 만들 것 없음). 소켓 경로에 남는 유일한 쓸모는 **측정 자동화**(`tools/bench_memory.py` 가
> 결과를 프로그램으로 읽는 것)이고, 그건 Unit 4 에서야 필요해진다.

---

## Unit 1 — 지금 코드가 어떻게 생겼나

### 새로 만든 것

```
android/app/src/main/java/com/cua/a11/memory/
├── MemoryDb.kt        run · episode 엔티티 + Room DB (version 1)
├── MemoryDao.kt       INSERT + 검증/집계 쿼리
└── RoomRunTrace.kt    RunTrace 구현 — 모든 실패를 삼킨다
```

### 기존 파일에서 바뀐 것 (세 군데뿐)

| 위치 | 무엇 |
|---|---|
| `CuClient.kt` | `Executor.foregroundApp()` · `RunTrace`/`TurnRecord` 계약 · `runAgent(trace=null)` 훅 |
| `a11service.kt` | `foregroundApp()` 구현 · `runTrace` lazy · 앱/소켓 두 경로에 주입 |
| `android/gradle.properties` 외 | Room·KSP 빌드 설정 (아래 Gotchas) |

### 경계

`runAgent` 는 **DB 도 Room 도 모른다.** `RunTrace` 인터페이스만 알고 기본값이 `null` 이라,
안 넘기면 동작도 요청 본문도 종전과 완전히 동일하다. 이미 있는 `log`/`cancel` 과 같은 패턴.

### 담는 것 / 안 담는 것

- **좌표는 안 담는다**(설계 원칙 1). `action` 이름과 `intent` 만 남긴다.
- **`longVersionCode` 는 지금 안 쓰지만 기록한다.** 무효화의 근거인데(설계 §7 4번) 안 적어두면
  나중에 소급이 안 된다.
- **`hadSafety`** = `require_confirmation` 이 붙은 턴 표시. 그 턴에서 나온 후보를 통째로
  버리는 데 쓴다(설계 §3 규칙 A) — 내용 판정 없이 **출처로 자르는** 방식.

---

## Unit 2 — memory 테이블과 읽기 경로

### 새로 만든 것
`memory/MemoryGateway.kt` — **DB 를 만지는 유일한 출입구.** 예산(APP_FACT 2줄 + PITFALL 2줄)·
민감도 필터·우리 앱 제외가 전부 여기 있다. 호출부에서 다시 걸지 말 것.

### 기존 파일에서 바뀐 것
- `CuClient.kt` — **`taskNote` 를 `CuClient` 에서 `Executor` 로 옮겼다.** `appNote` 는 이미
  거기 있었는데 `taskNote` 만 판단 코어 안에 있었다. 이제 두 노트가 **같은 경계**를 지나고
  `CuClient` 는 기억을 완전히 모른다.
- `a11service.kt` — 하드코딩 맵 두 개를 지우고 `gateway.readForApp/readForTask` 로.

### 아직 안 만든 것 (일부러)
**랭킹.** 기억이 몇 개일 때 점수식은 무의미하다 — 하드 필터 + `LIMIT` 뿐이고 점수식은
Unit 7 이다. `PITFALL` 키워드 매칭도 FTS5 대신 **코틀린에서** 한다(목표 문장을 어떻게
쪼갤지가 아직 안 정해졌다).

### 검증된 것 / 안 된 것
| | |
|---|---|
| 스키마 1→2 마이그레이션 | ✅ 기존 데이터 생존(run 4행·episode 9행) |
| `memory` 22칸 + 인덱스 2개 | ✅ |
| 손으로 쓴 DDL 이 Room 기대치와 일치 | ✅ `app/schemas/2.json` 과 대조 |
| `note=null` 일 때 요청 본문 종전 동일 | ✅ |
| **`memory` 행이 실제로 주입되는지** | ✅ **Unit 3 에서 확인** (아래) |

## Unit 3 — 기억 목록 UI

### 새로 만든 것
```
memory/MemoryActivity.kt        목록 · 편집 다이얼로그 · 삭제
res/layout/activity_memory.xml  목록 화면
res/layout/item_memory.xml      행 하나
res/layout/dialog_memory_edit.xml  편집 폼
```
`MemoryDao` 에 `updateMemory`/`deleteMemory`/`deleteAllMemories`,
`MemoryGateway` 에 `list`/`save`/`delete`/`deleteAll`/`validate` 를 더했다.
`MainActivity` 에 `기억 관리` 버튼 하나.

### 게이트웨이의 읽기·쓰기 비대칭 ★
**읽기는 실패를 삼키고 쓰기는 안 삼킨다.** 일부러 다르게 뒀다 —
에이전트 입장에선 "기억이 없다"와 "DB 가 깨졌다"가 같은 결과지만(둘 다 note == null 로
종전대로 돈다), 사람이 저장을 눌렀는데 조용히 실패하면 **저장된 줄 알고 화면을 뜬다.**
`MemoryActivity.io()` 가 예외를 받아 토스트로 띄운다.

### 저장을 막는 것 = 읽기에 절대 안 걸리는 행
`MemoryGateway.validate()` 가 네 가지를 거절한다: 빈 내용 · 패키지 없는 `APP_FACT` ·
`pkg == com.cua.a11` · 키워드 없는 `PITFALL`. 전부 **저장은 되는데 아무 때도 안 나오는**
행이다. 그런 게 목록에 쌓이면 사용자는 "기억 기능이 고장 났다"고 결론 내린다.
⚠️ **읽기 조건(`activeForApp`/`readForTask`)을 바꾸면 `validate()` 도 같이 고쳐야 한다.**

### `RECIPE` 는 종류 드롭다운에서 뺐다
읽는 쪽(Unit 9)이 아직 없다. 넣을 수 있게 두면 사용자가 **아무도 안 읽는 행**을 만든다.
Unit 9 에서 `MemoryGateway.KINDS` 에 한 줄 더하면 열린다.

### 주입이 로그에 보이게 했다
`runAgent` 가 `[기억] …` 을 찍는다 — **note 가 바뀔 때만.** 요청 본문은 폰에서 볼 수 없어
이 줄이 없으면 주입 여부를 확인할 방법이 없고, 매 턴 찍으면 같은 문장이 로그를 덮는다.

⚠️ **찍는 것은 반드시 '실제로 보낸 것'이어야 한다.** 승인 턴은 `result` 가 객체라
`putResult` 가 note 를 안 붙이는데, 로그를 그 앞에서 무심코 찍으면 **안 실린 걸 찍는다**.
이 로그의 존재 이유가 신뢰이므로 거짓말을 하면 있느니만 못하다 — `logNote` 에는
`if (safetyAck) null else turnNote` 를 넘긴다.

### 실패를 드러내는 세 갈래 (Unit 3 뒤에 덧붙임)
읽기 실패를 삼키는 건 유지하되, **아무도 모르는 상태**는 없앴다. 자세한 건 아래 Gotchas 의
`memoryReadFailed` 항목. 코드로는 세 곳이 바뀌었다:

- `MemoryGateway` — `guard` 의 catch 에서 깃발 두 개를 세운다(로그용·run 행용, 소비 시점이
  다르다). `takeFailureForLog()` / `takeFailureForRun()` 으로 **한 번 가져가면 사라진다.**
- `Executor.noteFailure()` — 새 계약. `runAgent` 는 기억을 모르므로 이 통로로만 알 수 있다.
  실행 로그에 `⚠ [기억] 읽기 실패…` 로 뜬다.
- `RoomRunTrace.onRunEnd` — 깃발을 `run.memoryReadFailed` 에 기록.
  **시작이 아니라 종료에서 소비한다** — 시작에서 지우면 `runAgent` 가 `onRunStart` 보다
  **먼저** 부르는 `taskNote` 의 실패가 지워진다.

목록 UI 에도 `lastAccessed` 를 노출했다 — `주입 N회 · 마지막 …`, 0회면
`아직 한 번도 안 걸림 — 검색 키를 확인해 보세요`, 요약줄에 `그중 n건은 아직 안 걸림`.
이건 **예외가 안 나는 실패**(키워드가 안 맞아 그냥 조회가 0건)의 유일한 신호다. 다만
"검색 키가 틀렸다"와 "그 상황이 아직 안 왔다"를 구분하지는 못한다 — 판정이 아니라 신호다.

### 실기기 검증 (2026-09-13, SM-S931N)
`APP_FACT / com.android.settings / "Use the search icon at the top to find a setting quickly."`
를 UI 로 넣고 `Open the Settings app` 을 실행:

```
[턴 1] list_apps                        ← pkg=com.cua.a11, note 없음
[턴 2] open_app com.android.settings
[기억] Use the search icon at the top…  ← 주입됨
[완료] The Settings app has been opened.
```
| | |
|---|---|
| `memory` 행이 실제로 주입되는지 (Unit 2 이월) | ✅ `episode.turn=2` 의 `note` 에 문장 그대로 |
| `numRecalled` 증가 · `lastAccessed` 기록 | ✅ 0 → 1 |
| 턴 1 은 note 없음(우리 앱이 포그라운드) | ✅ 설계대로 |
| 추가 · 편집 · 삭제 | ✅ |
| 편집이 이력을 안 지우는가 (`copy()`) | ✅ `numRecalled=1`·`timeAdded` 보존 |
| 종류 바꾸면 검색 키 칸이 바뀌는가 | ✅ 패키지 ↔ 키워드 |
| 빈 내용 저장 거절 + **다이얼로그 유지** | ✅ |
| 삭제가 `run`·`episode` 를 안 건드리는가 | ✅ 6 run · 20 episode 그대로 |
| `a11mem` 경고 | ✅ 없음 |
| 스키마 2→3 마이그레이션 (`memoryReadFailed`) | ✅ 데이터 생존(run 6·episode 20·memory 1), 옛 행은 `null` |
| 정상 실행에 `memoryReadFailed=0` 기록 | ✅ |
| **읽기 실패 경로 자체** | ⬜ **미검증** — 아래 |

⚠️ **실패를 일부러 일으켜 보지는 못했다.** 확인된 것은 **배선**이다 —
`onRunEnd` 가 `gateway.takeFailureForRun()` 의 값을 실제로 `run` 행에 썼다(`0`). 안 밟아 본
것은 `guard` 의 catch 안 두 줄(깃발 대입)뿐이다. 다음에 스키마를 바꿀 때가 자연스러운
기회다 — 그때 마이그레이션을 빼먹으면 이 경로가 실제로 돈다.

**`PITFALL` 경로도 확인** (같은 날). `APP_FACT` 와 **다른 함수**를 타고(`readForTask`)
매칭도 SQL 이 아니라 코틀린 문자열 `contains` 라 따로 봐야 했다. 키워드 `settings` 짜리
행을 넣고 `Open the Settings app` 실행:

```
[기억] PITFALL-TEST: …                      ← 턴 1 앞. taskNote 는 첫 요청부터 붙는다
[턴 1] list_apps
[턴 2] open_app com.android.settings
[기억] PITFALL-TEST: … Use the search icon…  ← taskNote + appNote 가 합쳐짐
```
`episode.turn=1` 의 `note` 에 PITFALL 만, `turn=2` 에 둘 다 들어 있었다.
**두 노트가 합쳐지는 것까지 이걸로 확인됐다** — 전엔 한 갈래씩만 봤다.

⚠️ 확인한 건 **키워드가 목표에 그대로 들어 있을 때** 걸린다는 것뿐이다. 매칭이 부분
문자열이라 `"휴지통에 넣어줘"` 는 `삭제` 에 안 걸린다 — 그 취약성은 그대로다(설계 §5).

## Gotchas — 기억 시스템 특유의 것

### 소켓 실행은 '무인 실행'이다 — 사람을 기다리는 지점이 함정 ★
**해결됨 (2026-09-13).** 소켓 `RUN` 이 턴 2 에서 6분 22초 멈추던 원인은 Doze 도 네트워크도
아니었다. **인계 카드가 없는 사람을 기다린 것**이다.

```
화면이 꺼짐 → 캡처가 25KB 검은 PNG → probeSecureScreen ≥95% → '보안 화면' 판정
            → 인계 카드 표시 → 아무도 안 누름 → HANDOVER_TIMEOUT_MIN(3분) 소진
```
재현(2026-09-13): `18:00:00 cmd RUN` → `18:03:07 [턴 1]` → `18:03:24 [완료]`.
**203초 중 187초가 대기.** 원래 본 6분 22초는 이게 두 번 걸린 것이다.

**핵심은 꺼진 화면과 보안 화면이 픽셀로 똑같이 검다는 것.** 사람이 앞에 있으면 이 혼동이
무해하다 — 탭 한 번이면 된다. 무인 실행에서만 비용이 된다.

⚠️ 커밋 `a89f9ed` 의 "Doze 로 죽는다"는 **틀렸다.** `readTimeout` 60초로 6분 22초가 설명이
안 됐던 게 단서였는데 그때 더 안 팠다.

**고친 것** (`a11service.kt`):

| | |
|---|---|
| `PowerManager.isInteractive()` 로 꺼진 화면과 보안 화면을 구분 | 꺼져 있으면 즉시 실패. 깨워 봐야 잠금화면이라 방법이 없다 |
| `attended` 깃발 — 앱 UI `true`, 소켓 `false` | 무인이면 인계·자격증명 카드를 아예 안 띄우고 즉시 실패 |
| 소켓 `RUN` 에 `cancel = { cancelled }` | 아래 별도 항목 |
| 진행 표시 띠에 `FLAG_KEEP_SCREEN_ON` | 앱 UI 실행 중에는 화면이 안 꺼진다 |
| `latch.await()` 세 곳에 상한 | 아래 별도 항목 |

**실기기 검증 (2026-09-13, SM-S931N)**

| | |
|---|---|
| 소켓 · 화면 꺼짐 | ✅ **187초 → 0.4초**, `"오류: 화면이 꺼져 있습니다…"` 를 돌려줌 |
| 소켓 · 잠금화면 | ✅ 0.9초, `pkg=com.android.systemui` 까지 짚음 |
| 소켓 · 정상 실행 | ✅ 37.5초 완주 — 회귀 없음 |
| 소켓 · 실패 직후 서버 | ✅ `SHOT` 0.3초 — 죽지 않는다 |
| 앱 UI · 진행 표시 띠 | ✅ 그대로 뜸, 실행 완주(2턴 ~21초) |
| 앱 UI · `KEEP_SCREEN_ON` | ✅ 창 플래그 `fl=10000b8` → `0xb8 = 0x80│0x20│0x10│0x08` |
| 앱 UI · 실행 후 정리 | ✅ 오버레이 0개 — 플래그가 안 남는다(화면이 다시 꺼질 수 있다) |

창 플래그는 `adb shell dumpsys window windows | grep -A6 com.cua.a11` 의 `fl=` 로 본다.
`0x80` 이 `FLAG_KEEP_SCREEN_ON` 이고 나머지는 종전 셋(`NOT_FOCUSABLE`·`NOT_TOUCHABLE`·
`NOT_TOUCH_MODAL`)이다 — **띠의 터치 통과 정책은 그대로**여야 한다(안 그러면 최상단 탭을
가로챈다, `CLAUDE.md` 참조).

### 소켓 `RUN` 은 `cancel` 을 안 넘기고 있었다 ★
```kotlin
runAgent(this, cu, task, trace = runTrace)   // cancel 인자 없음 → 기본값 {false}
```
그래서 **소켓 실행은 중단이 아예 안 됐다** — 앱의 중단 버튼도, 인계 타임아웃이 세우는
`requestCancel()` 도 무시됐다. 위 재현에서 인계가 3분 만에 포기한 뒤에도 검은 화면을 들고
그대로 진행한 이유다. `cancelled`·`skipBlackPkgs` 초기화도 빠져 있어 함께 넣었다
(안 하면 지난 실행의 `cancelled=true` 를 물려받아 첫 턴에서 죽는다).

**새 실행 경로를 만들 때 `runTask` 가 하는 초기화를 그대로 따라 할 것** —
`cancelled` · `skipBlackPkgs` · `attended` · `cu.configure`.

### 콜백 대기에 상한이 없으면 소켓 서버가 통째로 죽는다 ★
소켓 서버는 **단일 스레드**다 — `accept()` 루프에서 `runAgent` 를 그대로 돌린다. 그래서 그
스레드가 멈추면 포트는 `LISTEN` 인데 **아무 명령도 안 받는다.** 실제로 그 상태를 봤다:
TCP 핸드셰이크는 되는데 268초 동안 앱 로그가 0줄(`client connected` 조차 없음).

상한 없이 콜백을 기다리던 곳이 셋이었다 — 전부 상한을 뒀다:

| 위치 | 기다리는 것 | 상한 | 시간 초과 시 |
|---|---|---|---|
| `capturePngBlocking` | `takeScreenshot` 콜백 | 10초 | 던짐 (정상값 ~0.3초) |
| `dispatchBlocking` | `dispatchGesture` 콜백 | 10초 | 던짐 → 모델에 `status:error` |
| `hideForShot` | 메인 스레드 `ui.post` | 5초 | 로그만 남기고 진행 |

⚠️ **새로 `latch.await()` 를 쓸 때 반드시 상한을 둘 것.** 이 코드베이스에서 무한 대기는
'그 요청이 느려지는' 문제가 아니라 **'서버가 재시작 전까지 죽는'** 문제다.

### 앱을 force-stop 하면 접근성 서비스가 꺼진다
`adb shell am force-stop com.cua.a11` 을 쓰면 안드로이드가 그 앱의 접근성 서비스를
**비활성화**한다. 다시 켜려면 사람이 설정에서 켜야 한다(접근성 권한이라 adb 로 되돌리는 것은
하지 않는다). 재테스트할 때 force-stop 대신 **앱 UI 에서 다시 실행**하는 편이 낫다.

### DB 실패는 조용하다 — 그리고 **크래시로 알 수 없다** ★
`RoomRunTrace` 가 모든 **쓰기** 실패를, `MemoryGateway.guard` 가 모든 **읽기** 실패를
삼킨다. 기억이 실행을 망가뜨리면 안 되므로 의도한 것이지만, 대가가 크다.

⚠️ **`MemoryDb` 를 만지는 경로가 셋인데 셋 다 예외를 잡는다** —
`RoomRunTrace.swallow` · `MemoryGateway.guard` · `MemoryActivity.io`.
그래서 **마이그레이션이 어긋나도 앱은 안 죽는다.** Room 은 `build()` 가 아니라 **첫 쿼리**에서
DB 를 여는데, 그 첫 쿼리가 하필 `onRunStart`(= `swallow` 안)다. 실제로 일어나는 일은:

| | |
|---|---|
| 앱·에이전트 | **완벽히 정상으로 보인다** |
| `run`·`episode` | 한 줄도 안 쌓인다 |
| 기억 주입 | 하나도 안 된다 |
| 사람이 볼 수 있는 단서 | `기억 관리` 화면의 토스트, 그리고 logcat `a11mem` |

앱이 멀쩡히 도니 DB 를 의심할 이유가 없어서 **원인을 찾는 데 오래 걸린다.** 스키마를 바꾼
직후에는 반드시 아래 둘 중 하나로 확인할 것:
```bash
adb logcat -s a11mem:W AndroidRuntime:E     # 조용하면 통과
```
또는 `기억 관리` 화면을 열어 본다(거기만 예외를 사람에게 보여준다).

**연쇄 실패 주의**: `onRunStart` 가 실패하면 `run` 행이 없고 → `episode` INSERT 가 외래키
위반 → 그것도 삼켜진다 → **그 실행의 episode 가 전부 조용히 사라진다.** "행이 0개"와
"안 들여다본 것"을 구분하려면 **반드시 DB 를 직접 확인해야 한다.**

### 삼키는 것과 숨기는 것은 다르다 — `memoryReadFailed` ★
위의 조용함이 **측정을 오염시킨다.** 읽기가 실패한 실행이 "기억 있음" 조건에 섞이면 Unit 4 는
그걸 *"기억은 효과가 없다"* 로 읽는다. `CLAUDE.md` 의 PowerShell 인코딩 버그와 같은 구도다 —
**오염된 측정은 조용히 틀린다.**

그래서 삼키되 흔적은 세 군데 남긴다:

| 어디에 | 무엇 | 누가 본다 |
|---|---|---|
| logcat `a11mem` | 예외 전문 | 개발자 |
| 실행 로그 `⚠ [기억] 읽기 실패…` | 한 줄 | **폰만 쓰는 사용자** |
| `run.memoryReadFailed` | `1` | **Unit 4 의 측정 스크립트** |

`null` = 이 칸 이전의 실행(모름) · `0` = 정상 · `1` = 실패. 오염된 실행은
`MemoryDao.contaminatedRuns()` 로 뽑는다.

⚠️ **이 깃발은 "DB 는 살아 있는데 읽기가 실패한" 경우만 잡는다.** DB 가 통째로 죽으면
`onRunStart` 부터 실패해 **`run` 행 자체가 없다** — 측정에서 기대 건수와 실제 건수를
대조해야 알아챈다.

**실패 원인은 거의 다 '가끔'이 아니라 '항상'이다** — 마이그레이션 불일치, 메인 스레드 호출.
즉 확률적으로 삐끗하는 게 아니라 **켜져 있거나 통째로 죽어 있거나** 둘 중 하나다. 나쁜 쪽은
한 번 죽으면 그날 측정이 전부 오염된다는 것이고, 좋은 쪽은 **깃발 하나로 잡힌다**는 것이다.

### 턴 1 의 패키지는 우리 앱 자신이다 ★
실측(2026-09-12): 턴 1 의 `pkg` 가 `com.cua.a11` 이었다. 사용자가 CUA 앱에서 실행을 누르니
포그라운드가 우리 앱이고 `list_apps` 는 화면을 안 바꾸기 때문이다.

- **기록은 그대로 둔다** — 사실이고, "몇 턴 만에 우리 앱을 벗어났나"라는 신호도 된다.
- **Unit 5 의 리플렉터가 `com.cua.a11` 후보를 만들지 않아야 한다.** 우리 앱은 에이전트가
  *조작하는* 앱이 아니라 *실행이 시작된 곳*이라 앱 지식이 될 수 없다.
- 런처(`com.sec.android.app.launcher`)는 **거르지 말 것** — "앱 서랍은 위로 스와이프" 같은
  건 진짜 앱 지식이다. 제외 대상은 우리 앱 하나뿐이다.
- 부수 효과: 설계 부록 F의 **Q7**("턴 1 에 `appNote` 를 붙일까")이 "붙일 앱 지식이 애초에
  없다" 쪽으로 기운다. 표본 1 이라 확정은 아니다.

### AGP 9 내장 Kotlin × KSP — 해제 플래그가 필요하다 ★
이 프로젝트는 Kotlin 플러그인을 따로 선언하지 않는다(AGP 9.2.1 의 **내장 Kotlin**). 그런데
KSP 는 생성 소스를 `kotlin.sourceSets` 로 등록하고, 내장 Kotlin 은 그걸 막는다:

```
Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin.
```

AGP 가 에러 메시지로 안내하는 공식 플래그를 `android/gradle.properties` 에 넣어 풀었다:
```properties
android.disallowKotlinSourceSets=false
```
**이 줄을 지우면 `:app:compileDebugKotlin` 이 죽는다.** KSP 버전은 Kotlin 2.2.10 에 맞춰
`2.2.10-2.0.2`.

### `action` 은 SQLite 예약어다
`SELECT pkg AS action ...` 이 파싱 실패한다(`mismatched input 'action'`). 컬럼명으로는
괜찮은데 **`AS` 별칭으로는 안 된다.** Room 이 이걸 빌드 시점에 잡아줬다 — 생
`SQLiteOpenHelper` 였으면 그 쿼리를 처음 부르는 런타임에 터졌을 것이다.

### `.db` 는 루팅 없이 못 본다 — WAL 까지 같이 꺼낼 것
```
/data/data/com.cua.a11/databases/memory.db      ← 본체
                                 memory.db-wal  ← 최근 쓰기가 여기 있다
                                 memory.db-shm
```
디버그 빌드라 `run-as` 로 꺼낼 수 있다. **바이너리이므로 `adb shell` 이 아니라 `exec-out`**
을 써야 깨지지 않는다:
```bash
for f in memory.db memory.db-wal memory.db-shm; do
  adb exec-out run-as com.cua.a11 cat "databases/$f" > "$f"
done
```
본체가 4KB 인데 `-wal` 이 80KB 인 건 정상이다(아직 체크포인트 전). 세 파일을 같이 가져와야
내용이 보인다. **기기에 `sqlite3` 바이너리는 없다** — PC 로 꺼내 읽어야 한다.

**하지만 보통은 이럴 필요가 없다** — `Android Studio → View → Tool Windows →
App Inspection → Database Inspector` 가 기기에서 도는 DB 를 실시간 표로 보여주고 임의
쿼리도 돌려준다. WAL 도 exec-out 도 신경 쓸 필요 없다. 위 절차는 Android Studio 를 못
쓰는 상황의 대안이다.

---

## 열린 결정

숫자는 전부 **근거 없이 정했거나 미정**이다. 스펙처럼 읽지 말 것. 전체 목록은 명세서 부록 F.

| | 항목 | 언제 정하나 |
|---|---|---|
| **N1** | 랭킹 가중치 `0.40/0.25/0.15/0.20` | Unit 7, 측정으로 |
| **N2** | `kind` 별 감쇠 반감기 | Unit 8, `pkgVersion` 변경 간격에서 역산 |
| **N3** | 용량 상한 500건 | Unit 8, 실제 축적량 보고 |
| **N4** | 보존 기간 30/90/180일/1년 | Unit 8 |
| **N5** | `score − 1` 가중치 | Unit 6 |
| **N6** | `Recent:` 개수 3 | Unit 2 |
| **Q7** | 턴 1 에 `appNote` 를 붙일지 | 🔶 **'안 붙인다' 쪽으로 기움** — 아래 |
| **Q11** | ~~Room 이냐 생 SQLiteOpenHelper 냐~~ | ✅ **Room 채택** (2026-09-12) |

`MemoryDao` 에 **Q7·N2 를 답할 쿼리를 미리 넣어뒀다** — `firstTurnActions()`,
`versionSpread()`. Database Inspector 에서 바로 돌려볼 수 있다.

**Q7 중간 결과** (2026-09-13, `firstTurnActions()`, N=6):

```
턴 1 액션 : list_apps 6/6
턴 1 패키지: com.cua.a11 4 · com.sec.android.app.launcher 1 · com.kakao.talk 1
```

턴 1 은 **예외 없이 `list_apps`** 였다. 앱 지식이 필요 없는 액션이므로 붙여 봐야 쓸 데가
없다. 흥미로운 건 두 번째 줄 — 포그라운드가 늘 우리 앱인 건 **아니다**(소켓 실행은
사용자가 다른 앱에 있을 때 시작된다). 그런 경우에도 액션은 `list_apps` 였다.
**N=6 이라 확정은 아니다** — Unit 4 에서 표본이 늘면 다시 볼 것.

---

## 다음 할 일

**Unit 4 — 측정 세트 + 소켓 `DUMP`.** 여기서 처음으로 **판단 ①(수동 기억이 효과 있나)** 이
나온다. 같은 과제를 기억 있음/없음으로 나란히 돌려 턴 수를 비교한다.

**소켓 경로는 고쳤다**(위 Gotchas) — 이제 막는 것은 없다. 다만 측정 스크립트가
**반드시 해야 할 세 가지**가 있다(안 하면 숫자를 믿을 수 없다):

1. **화면을 켜 두고 잠금을 풀어 둔다.** 꺼져 있으면 이제 0.4초에 실패하므로 조용히
   오염되지는 않지만, 배치가 통째로 날아간다.
2. `memoryReadFailed = 1` 인 실행을 결과에서 뺀다 (`MemoryDao.contaminatedRuns()`)
3. **기대 건수와 실제 `run` 행 수를 대조**한다 — DB 가 통째로 죽으면 행 자체가 없다

같이 정할 것: 과제 세트를 무엇으로 할지, 몇 회씩 돌릴지. `CLAUDE.md` 의 사고수준 A/B
경고와 같은 함정이 있다 — **N=5 짜리 단발 측정으로 판단하지 말 것.**
