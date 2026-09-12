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
상황에 맞는 것만 예산 안에서 모델 입력에 얹는다. 지금은 **기록만** 하고 아무것도 주입하지
않는다 — `taskNotes`·`appNotes` 가 비어 있어 **요청 본문이 종전과 완전히 동일**하다.

---

## 구현 현황

| Unit | 무엇 | 상태 |
|---|---|---|
| **1** | **DB 뼈대 + 구조화 로깅** | ✅ **실기기 검증 완료** (2026-09-12, SM-S931N) |
| **2** | **`memory` 테이블 + 읽기 경로** | ✅ **실기기 검증** (2026-09-13) — 주입 확인만 Unit 3 으로 |
| 3 | 기억 목록 UI (보기·추가·수정·삭제) | ⬜ **다음** |
| 4 | 측정 세트 + 소켓 `DUMP` | ⬜ → **판단 ① 수동 기억이 효과 있나** |
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
| **`memory` 행이 실제로 주입되는지** | ⬜ **미검증** |

마지막 항목은 **폰에 행을 넣을 방법이 없어서** 미뤘다 — 기기에 `sqlite3` 가 없고, 앱이 DB 를
연 채로 파일을 밀어 넣으면 WAL 이 깨진다. **Unit 3 의 목록 UI 가 행을 넣는 자연스러운
경로**이므로 거기서 함께 검증한다.

## Gotchas — 기억 시스템 특유의 것

### 소켓 실행은 화면이 꺼지면 네트워크가 죽는다 ★
PC 에서 소켓 `RUN` 으로 돌리면 아무도 폰을 안 만지므로 **화면이 꺼지고 Doze 에 들어간다.**
그러면 `cuCall` 이 백그라운드 네트워크 제한에 걸려 타임아웃난다. 실측(2026-09-13, 2/2 재현):

```
02:37:41  턴 2 open_app
02:44:03  A11y: client error: timeout        ← 6분 뒤. readTimeout 60초 × 재시도
```

같은 작업을 **앱 UI 로 돌리면 완주한다**(사용자가 방금 만져서 Doze 에 안 걸림). 경로 차이가
아니라 **화면 상태 차이**다. 같은 시간대에 `adb screencap` 도 타임아웃났다.

⚠️ **Unit 4(측정 자동화)에 직접 영향이 있다.** `tools/bench_*.py` 가 소켓으로 돌리므로,
화면을 켜두거나 `KEEP_SCREEN_ON` 을 걸지 않으면 측정값이 오염된다.

부수 확인: 이때 `run.outcome` 이 `error`, `turnsUsed=0` 으로 남았다 — **설계대로다.**
outcome 초기값을 "error" 로 두고 finally 에서 기록하게 한 것이 예외 경로를 정확히 잡았다.

### 앱을 force-stop 하면 접근성 서비스가 꺼진다
`adb shell am force-stop com.cua.a11` 을 쓰면 안드로이드가 그 앱의 접근성 서비스를
**비활성화**한다. 다시 켜려면 사람이 설정에서 켜야 한다(접근성 권한이라 adb 로 되돌리는 것은
하지 않는다). 재테스트할 때 force-stop 대신 **앱 UI 에서 다시 실행**하는 편이 낫다.

### DB 실패는 조용하다 ★
`RoomRunTrace` 가 **모든 쓰기 실패를 삼킨다**(`swallow`). 로깅이 실행을 망가뜨리면 안 되므로
의도한 것이지만, 대가로 **DB 가 통째로 안 돌아도 앱은 멀쩡히 돈다.**

확인은 logcat 으로:
```bash
adb logcat -s a11mem:W AndroidRuntime:E
```
`a11mem` 이 조용하면 DB 쓰기가 다 통과한 것이다.

**연쇄 실패 주의**: `onRunStart` 가 실패하면 `run` 행이 없고 → `episode` INSERT 가 외래키
위반 → 그것도 삼켜진다 → **그 실행의 episode 가 전부 조용히 사라진다.** "행이 0개"와
"안 들여다본 것"을 구분하려면 **반드시 DB 를 직접 확인해야 한다** — Database Inspector 나
아래 `run-as` 절차로.

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
| **Q7** | 턴 1 에 `appNote` 를 붙일지 | Database Inspector 로 `firstTurnActions()` 실행 |
| **Q11** | ~~Room 이냐 생 SQLiteOpenHelper 냐~~ | ✅ **Room 채택** (2026-09-12) |

`MemoryDao` 에 **Q7·N2 를 답할 쿼리를 미리 넣어뒀다** — `firstTurnActions()`,
`versionSpread()`. Database Inspector 에서 바로 돌려볼 수 있다.

---

## 다음 할 일

**Unit 3 — 기억 목록 UI.** 보기·추가·수정·삭제·전체 초기화. 폰 단독으로 기억을 보고 지우는
유일한 창구이고(Database Inspector 는 개발용이지 제품이 아니다), **Unit 2 에서 미룬 "행이
실제로 주입되는지" 검증을 여기서 함께** 한다 — UI 가 행을 넣는 자연스러운 경로다.

삭제 기능이 자동 쓰기(Unit 5)보다 **먼저** 있어야 한다. 순서가 반대면 그사이 쌓인 것에
사용자가 손쓸 방법이 없다.
