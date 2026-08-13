# TODO — 안전 확인(HITL) 자동승인 → 진짜 사용자 확인 UI로 교체

> 6단계 루프 **동작 검증 완료 후** 진행할 후속 작업.
> 지금은 원본 `live/main.py`와 동일한 **의도된 데모 단축(자동승인)** 상태 — 안전한 작업만 테스트하는 조건에서만 OK.

## 지금 상태 (데모 단축)

`CuClient.kt`의 `runAgent` 안:
```kotlin
val safetyAck = args.has("safety_decision")   // 확인 요구가 있으면 무조건 '승인했음' 도장
...
results.put(cu.functionResult(name, callId, png, status, safetyAck))
```
→ 모델이 `safety_decision`을 붙여 와도 **사람에게 안 묻고 통과**시킴. 결제·전송·삭제·CAPTCHA까지 그대로 실행됨.

## 왜 바꿔야 하나 (레퍼런스 근거)

`docs/reference/gemini-computer-use.md` §5(안전):
- `safety_decision = {"explanation": "...", "decision": "..."}`
  - `allow`/`general` → 승인 불필요, 그냥 진행
  - `require_confirmation` → **explanation 보여주고 사용자 승인받아야** 진행
- 강제 확인 케이스(문서 명시): **결제/금융, 통신(메시지 전송), 법적 약관 동의, CAPTCHA 풀기**
- ⚠️ 안전 정책을 꺼도 모델은 여전히 `require_confirmation`을 낼 수 있음 → 앱은 항상 안전결정을 처리해야 함.

## 교체 방안 (구현 시)

### 1. `decision` 값까지 구분 (지금은 키 존재만 봄 — 뭉툭함)
```kotlin
val sd = args.optJSONObject("safety_decision")
val needConfirm = sd?.optString("decision") == "require_confirmation"
val explanation = sd?.optString("explanation") ?: ""
```
- `require_confirmation`일 때만 사람 확인 흐름을 타고, `allow`/`general`은 바로 진행.

### 2. 실제 사용자 확인 (§8 온디바이스 트리거 UI 만들 때 같이)
- 소켓/헤드리스 루프에는 UI가 없음 → **§8에서 트리거 액티비티(EditText+실행 버튼)를 만들 때** 함께 붙이는 게 자연스러움.
- 확인 다이얼로그: `explanation`을 보여주고 [승인]/[거부].
  - 승인 → `safetyAck = true`로 `functionResult` 진행.
  - 거부 → 그 액션 실행 안 하고 status를 `{"status":"error","error":"user_rejected"}` 류로 돌려주거나 루프 중단.
- 백그라운드 스레드(runAgent)에서 UI 다이얼로그를 띄우려면 `CountDownLatch`로 사용자 응답을 블로킹 대기(제스처 latch와 동일 패턴). mainExecutor에 다이얼로그 post → 사용자 클릭에서 latch.countDown().

### 3. (선택) 정책 조정
- 특정 카테고리를 자동 허용하고 싶으면 요청에 `disabled_safety_policies` 추가 가능(7종: FINANCIAL_TRANSACTIONS, SENSITIVE_DATA_MODIFICATION, COMMUNICATION_TOOL, ACCOUNT_CREATION, DATA_MODIFICATION, USER_CONSENT_MANAGEMENT, LEGAL_TERMS_AND_AGREEMENTS).
- ⚠️ 꺼도 `require_confirmation`은 나올 수 있으니 확인 흐름은 여전히 필요.

## 진행 조건
- [ ] 6단계 루프 동작 검증(안전 작업으로) 완료
- [ ] §8 온디바이스 트리거 UI 착수 시 → 위 1·2를 함께 구현
- [ ] 그 전까지는 **위험 작업(결제/전송/삭제) 테스트 금지**, 자동승인 유지
