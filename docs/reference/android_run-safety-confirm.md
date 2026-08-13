# 안전(HITL) — 위험 액션은 사용자 확인 후 앱이 실행

> 로컬 전용(.gitignore `android_run-*`). **팀원은 이 파일을 볼 수 없다** — 공유해야 할 내용은
> `CLAUDE.md`에 쓸 것.
>
> **상태: 구현 완료, 실기기 검증 완료(2026-08-04).** 코드 기준 = `CuClient.kt:234-251`,
> `a11service.kt:338-427`.
>
> ⚠️ **전송 형식(`safety_acknowledgement`)의 정본은 `CLAUDE.md` §Gotchas다.** 여기 옮겨 적지 않는다 —
> 예전에 이 문서가 형식·설계를 자체 서술했다가 코드와 반대로 벌어졌다(아래 §0).

---

## 0. 폐기된 설계 — "인계-중단" (채택 안 됨)

이 문서는 원래 **인계-중단**(위험 액션을 앱이 실행하지 않고 사용자에게 넘긴 뒤 루프 종료) 방식을
서술했다. **채택되지 않았고, 코드에 들어간 적이 없다.**

폐기 이유 — 그 설계의 근거가 무너졌다:

- 인계-중단을 택한 유일한 실질적 근거는 **"`safety_acknowledgement`를 보내면 400이 나니 아예 안 보낸다"**
  는 회피였다.
- 2026-08-04, 그 400의 원인이 밝혀졌다: **`result`를 배열이 아니라 '객체'로** 보내면 통과한다.
  구글 문서·quickstart 형식이 이 API에서 틀린 것이었다. (실측 표는 `CLAUDE.md` §Gotchas)
- 400을 정면으로 풀었으므로 승인 경로를 포기할 이유가 없어졌다. 승인하면 **앱이 이어서 실행**하는 쪽이
  작업이 중간에 죽지 않아 사용자 경험도 낫다.

> 옛 문서에 있던 "Executor 인터페이스에 `confirm`은 제거됨", "ack를 안 보낸다"는 서술은 **전부 사실이 아니다.**
> `Executor.confirm`은 `CuClient.kt:198`에 있고, 승인 시 ack를 보낸다.

---

## 1. 현재 동작

```
모델이 safety_decision.decision == "require_confirmation" 을 붙여옴
        ↓
앱이 dispatch 전에 멈추고 전체화면 확인 카드를 띄움 (explanation 표시)
        ↓
   [승인] → 액션 실행 → function_result 에 safety_acknowledgement:true 로 보고 → 루프 계속
   [거부] → 실행 안 함 → 서버에 되돌리지 않고 루프 즉시 종료
```

### 1-1. 감지 (`CuClient.kt:236`)

```kotlin
val sd = c.optJSONObject("safety_decision") ?: args.optJSONObject("safety_decision")
val needConfirm = sd?.optString("decision") == "require_confirmation"
```

`safety_decision`의 위치가 스펙(`arguments` 안)과 실제 응답(스텝 형제 필드)이 다를 수 있어 **둘 다 본다.**
`c`를 먼저 보는 순서를 바꾸지 말 것.

### 1-2. 거부는 서버에 알리지 않는다 (`CuClient.kt:242-249`)

```kotlin
if (!exec.confirm(explanation)) {
    emit("[거부] 사용자가 승인하지 않음 — 실행 중단")
    return "중단: 사용자가 승인하지 않음"
}
```

`require_confirmation`을 낸 호출은 **승인 표시가 붙은 요청만** 받는다. 거부 사실을 `function_result`로
보고하려 하면 그 요청 자체가 400이다. 그래서 `{"status":"error","error":"user_rejected"}`를 돌려주는
경로는 **없앴다**(구글 문서 예제도 거부 시 break).

### 1-3. 승인 보고 (`CuClient.kt:118-137` `putResult`)

승인 턴만 `result`가 **객체**이고, 실행 후 화면은 같은 `input` 배열에 `user_input`으로 따로 실린다
(객체 result에는 이미지 블록을 못 넣기 때문). 화면을 빼면 모델이 눈이 멀어 다음 턴에 `take_screenshot`을
요청한다. **자세한 근거·실측 표는 `CLAUDE.md` §Gotchas.**

---

## 2. 확인 카드 (`a11service.kt:338-427`)

- **전체화면 모달** — scrim(딤) + 둥근 카드. 실행 중 오버레이 띠(`FLAG_NOT_TOUCHABLE`로 터치를 통과시킴)와
  달리 **일부러 터치를 막는다.** 뒤 앱을 잘못 누르는 사고를 방지.
- 백그라운드 스레드(`runAgent`)에서 호출되므로 `CountDownLatch`로 사용자 응답까지 **블로킹 대기**
  (제스처 latch와 같은 패턴).

### 반드시 지켜야 하는 두 가지

| 코드 | 이유 |
|---|---|
| `wm.removeViewImmediate(it)` (`:406`) | `removeView`는 제거를 **예약만** 하고 돌아온다. 모달이 아직 떠 있는 상태에서 `dispatchGesture`를 쏘면 **카드가 그 탭을 삼킨다** → 아무 일도 안 일어나고 모델이 무한 재시도 |
| `Thread.sleep(150)` (`:425`) | `removeViewImmediate` 뒤에도 창이 화면에서 실제로 사라질 시간이 필요하다. 이 뒤라야 탭이 뒤 앱에 닿는다 |

### 안전측 실패(fail-safe) 두 경로

- **오버레이 권한 없음** (`:339`) → `return false` = 거부 취급 → 루프 종료. 카드를 못 띄우는데 실행하면 안 되므로 의도된 동작.
- **60초 무응답** (`:423`) → `false` = 거부 → 루프 종료.

### 알림 (`a11service.kt:82-88`)

거부는 `"중단: …"`으로 시작하므로 `notifyDone`의 `r.startsWith("중단") -> " ■ 중단됨"`에 그대로 걸린다.
**별도 케이스 추가 불필요**(옛 문서의 `"넘김"` 케이스는 인계-중단 설계 소속이라 해당 없음).

---

## 3. 테스트

`require_confirmation`은 모델이 위험하다 판단할 때만 나온다.

- **UI 흐름만 빠르게 보려면(임시 강제):** `CuClient.kt:237`의 `needConfirm`을 잠깐 `true`로 고정 →
  아무 목표나 실행 → 첫 액션에서 카드가 뜨는지, [승인] 후 그 탭이 **뒤 앱에 제대로 닿는지**(§2 함정) 확인
  → **끝나면 반드시 원복.**
- **진짜 경로(안전 환경에서만):** 되돌릴 수 있는 대상으로 삭제/전송류를 시도. **실제 결제·전송은 금지.**

---

## 4. 함정

| 증상 | 원인/해결 |
|---|---|
| 400 `must be acknowledged` | 승인 턴 `result`를 배열로 보냄 → 객체여야 한다. `CLAUDE.md` §Gotchas 참조. **구글 문서를 근거로 되돌리지 말 것** |
| 400 `Unknown parameter` | ack를 `function_result`/텍스트 블록의 형제 필드로 올림 → `result` 객체 안으로 |
| 승인했는데 아무 일도 안 일어나고 모델이 같은 탭 반복 | 모달이 아직 떠서 탭을 먹음 → `removeViewImmediate` + `Thread.sleep(150)` 확인 (§2) |
| 위험 화면인데 카드가 안 뜸 | ① `safety_decision`을 `args`에서만 찾음 → `c ?: args` 둘 다 볼 것 ② **또는 모델이 이번엔 안 붙인 것 — 정상 범위다(§5)** |
| 카드가 아예 안 뜨고 바로 중단됨 | 오버레이 권한 없음(`canDrawOverlays`) → 설정에서 허용 |
| 다음 턴에 모델이 `take_screenshot`을 요청 | 승인 턴에 화면을 안 실었음 → `putResult`의 `user_input` 블록 확인 |

---

## 5. 이 방식으로 못 막는 것 ★

**확인 카드를 최종 방어선으로 신뢰하면 안 된다.**

- **`safety_decision`은 비결정적이다.** 같은 "알람 삭제" 작업이 한 실행에선 `require_confirmation`이
  붙고 다음 실행에선 안 붙어 확인 없이 실행됐다. 규칙표가 아니라 모델의 그때그때 판단이다.
  → **결제·비밀번호도 똑같이 샐 수 있다.** 카드가 아예 안 뜨는 실행이 존재한다.
- **판단 기준을 추가할 입구가 없다.** 내장 정책 7종은 API로 *끄는* 것만 가능하고 새 기준을 넣을 수 없다.
- **시스템 프롬프트의 `## Care` 섹션은 권고지 강제가 아니다.**
- **화면 단위 위험은 아예 대상이 아니다.** `safety_decision`은 *모델이 하려는 액션*에 대한 것이라,
  비밀번호·카드번호가 떠 있는 화면이 매 턴 스크린샷으로 API에 업로드되는 것은 이 경로가 다루지 않는다.

→ 필수 차단은 **우리 코드의 자체 안전 게이트**가 맡아야 한다. `CLAUDE.md` §구현 현황의
⬜ 항목이 그것이며, 설계는 별도 문서로 다룬다.
