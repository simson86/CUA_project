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
| 2 | 로그 열람 (소켓 `DUMP` + `tools/`) | ⬜ 다음 |
| 3 | `memory` 테이블 + 읽기 경로 | ⬜ |
| 4 | 기억 목록 UI (보기·추가·수정·삭제) | ⬜ |
| 5 | 측정 세트 (기억 on/off 교차 실행) | ⬜ → **판단 ① 수동 기억이 효과 있나** |
| 6 | 리플렉터 (추출 + §3 필터) | ⬜ |
| 7 | reconciliation + 승격 | ⬜ → **판단 ② 자동 학습이 효과 있나** |
| 8 | 랭킹 점수식 | ⬜ |
| 9 | 생애주기 (보존·압축·감쇠·상한·버전태깅) | ⬜ ← **여기까지가 완성** |
| 10 | `RECIPE` (절차 재사용) | ⬜ 선택 |

**Unit 9 까지가 완성이다.** Unit 10 은 다른 종류의 기억을 얹는 확장이라 없어도 완결된다.
반면 Unit 9 가 없으면 앱이 업데이트될 때마다 낡은 기억이 계속 주입돼 **시간이 지날수록
나빠진다**.

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

## Gotchas — 기억 시스템 특유의 것

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
"안 들여다본 것"을 구분하려면 반드시 읽는 도구(Unit 2)가 있어야 한다.

### 턴 1 의 패키지는 우리 앱 자신이다 ★
실측(2026-09-12): 턴 1 의 `pkg` 가 `com.cua.a11` 이었다. 사용자가 CUA 앱에서 실행을 누르니
포그라운드가 우리 앱이고 `list_apps` 는 화면을 안 바꾸기 때문이다.

- **기록은 그대로 둔다** — 사실이고, "몇 턴 만에 우리 앱을 벗어났나"라는 신호도 된다.
- **Unit 6 의 리플렉터가 `com.cua.a11` 후보를 만들지 않아야 한다.** 우리 앱은 에이전트가
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

이 절차가 번거로운 게 **Unit 2(소켓 `DUMP`)가 필요한 이유**다.

---

## 열린 결정

숫자는 전부 **근거 없이 정했거나 미정**이다. 스펙처럼 읽지 말 것. 전체 목록은 명세서 부록 F.

| | 항목 | 언제 정하나 |
|---|---|---|
| **N1** | 랭킹 가중치 `0.40/0.25/0.15/0.20` | Unit 8, 측정으로 |
| **N2** | `kind` 별 감쇠 반감기 | Unit 9, `pkgVersion` 변경 간격에서 역산 |
| **N3** | 용량 상한 500건 | Unit 9, 실제 축적량 보고 |
| **N4** | 보존 기간 30/90/180일/1년 | Unit 9 |
| **N5** | `score − 1` 가중치 | Unit 7 |
| **N6** | `Recent:` 개수 3 | Unit 3 |
| **Q7** | 턴 1 에 `appNote` 를 붙일지 | Unit 2 후 `firstTurnActions()` 로 |
| **Q11** | ~~Room 이냐 생 SQLiteOpenHelper 냐~~ | ✅ **Room 채택** (2026-09-12) |

`MemoryDao` 에 **Q7·N2 를 답할 쿼리를 미리 넣어뒀다** — `firstTurnActions()`,
`versionSpread()`. Unit 2 가 생기면 바로 볼 수 있다.

---

## 다음 할 일

**Unit 2 — 소켓 `DUMP` (약 60줄).** 8080 소켓 서버(`a11service.startServer`)에 명령을
추가하고 `tools/` 에 뽑아 보는 스크립트를 둔다. 이게 없으면 Unit 1 이 통째로 허공이다.
