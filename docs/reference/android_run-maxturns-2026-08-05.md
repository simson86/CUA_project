# 최대 턴 수를 앱에서 설정하기

> **핵심: `runAgent`는 이미 `maxTurns: Int = 20`을 파라미터로 받는다**(`CuClient.kt:206`).
> 배관은 뚫려 있고, 값이 두 호출부에서 기본값으로 떨어지고 있을 뿐이다. `runAgent` 본체는 안 고친다.

---

## 0. 왜 만드나 — 20턴 안에 완료를 못 하는 작업이 있다

**동기: 기본 20턴으로는 끝나지 않는 작업이 실제로 있다.** 그래서 이건 안전장치가 아니라
**완주를 위한 여유**를 주는 설정이다. 상한은 오타 방어용이지 사용을 제한하려는 게 아니다.

한 턴 = API 왕복(수 초) + `Thread.sleep(600)` + 캡처/전송.
**정상적으로 끝나는 작업은 이 값을 올려도 전혀 안 느려진다** — 완료되면 그 자리에서 `return`한다.
늘어나는 건 끝내 못 푸는 작업이 소진하는 시간뿐이다.

### ★ 올리기 전에 원인부터 가른다

턴이 모자란 원인이 둘인데 처방이 정반대다.

| 원인 | 증상 | 처방 |
|---|---|---|
| **(a) 작업이 실제로 길다** | 마지막 턴들이 계속 **새 화면**으로 나아가는 중 | 턴 수를 올리면 해결 |
| **(b) 모델이 헛돌았다** | 같은 액션 반복, 같은 화면에서 스크롤만 | **올려도 똑같이 실패** — 시간·비용만 배로 |

구분법: `run_history.txt`(앱의 "지난 로그 보기")에서 `STOP: max turns`로 끝난 실행의
**마지막 5턴**을 본다.

(b)라면 턴 수가 아니라 프롬프트 쪽 문제다 — `CuClient.system_prompt`의
`## Verify your own actions`(화면이 안 바뀌면 같은 액션 반복 금지, 두 경로 모두 실패하면 중단)가
이미 그걸 겨냥하고 있으니 그쪽을 손본다.

### 상한값

이 문서는 **1~40**을 쓴다(기본 20의 2배). 40은 "이보다 크면 오타"라는 선이다.

> ⚠️ **확인 안 된 것:** 서버 관리 히스토리(`previous_interaction_id`)라 턴이 쌓일수록 서버 쪽
> 컨텍스트가 커진다. 그렇다면 **턴 35의 비용·지연이 턴 5보다 클 수 있고**, 40턴이 20턴의 단순
> 2배가 아닐 수 있다. 실측된 바 없으니, 긴 실행을 돌리게 되면 턴별 소요 시간이 뒤로 갈수록
> 늘어나는지 로그로 확인해볼 것.

---

## 1. 값이 막혀 있는 지점

| 호출부 | 현재 | 이 문서에서 |
|---|---|---|
| `a11service.runTask` (`a11service.kt:70`) | `maxTurns` 안 넘김 → 20 | **UI 값 전달** |
| 소켓 `RUN` (`a11service.kt:285`) | 안 넘김 → 20 | **그대로 둔다** (§5) |

---

## 2. `activity_main.xml` — 입력 칸 추가

`taskInput`(24행) **아래**, 실행/중단 버튼 줄(26행) **위**에 가로줄로 넣는다.

```xml
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:layout_marginTop="12dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="최대 턴"
            android:textSize="15sp"/>

        <EditText
            android:id="@+id/maxTurnsInput"
            android:layout_width="72dp"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:inputType="number"
            android:maxLength="3"
            android:gravity="center"
            android:hint="20"/>

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:text="완료까지 허용할 턴 (1~40)"
            android:textSize="12sp"
            android:alpha="0.6"/>
    </LinearLayout>
```

- `maxLength="3"` — 네 자리 입력 자체를 막는다(clamp 전 1차 방어).
- 옆의 설명 TextView는 있으면 좋다. 이 숫자가 "빠르기"가 아니라 "몇 턴까지 붙잡고 갈지"라는 걸
  안 적어두면 성능 노브로 오해한다(§0).

---

## 3. `MainActivity.kt` — 읽기·검증·저장·전달

### 3-1. SharedPreferences (클래스 필드)

`logFile()` 근처, `onCreate` 밖에 둔다.

```kotlin
    private val prefs by lazy { getSharedPreferences("cua", MODE_PRIVATE) }
```

저장을 안 하면 앱을 다시 켤 때마다 기본값으로 돌아가서, 20이 아닌 값을 쓰는 사람은 매번
다시 입력해야 한다 → 실용성이 크게 떨어진다.

### 3-2. 뷰 찾기 + 지난 값 복원 (`onCreate`, 38행 `stopBtn` 다음)

```kotlin
        val turnsInput = findViewById<EditText>(R.id.maxTurnsInput)
        turnsInput.setText(prefs.getInt("max_turns", 20).toString())
```

### 3-3. 실행 시 파싱·clamp·저장 (`runBtn` 클릭 안, `runBtn.isEnabled = false` 직전)

```kotlin
            // 빈칸·숫자 아님 → 20. 0 이하와 오타성 과대값만 잘라낸다(§0).
            val maxTurns = (turnsInput.text.toString().trim().toIntOrNull() ?: 20)
                .coerceIn(1, 40)
            turnsInput.setText(maxTurns.toString())   // ★ clamp 결과를 화면에 되돌린다
            prefs.edit().putInt("max_turns", maxTurns).apply()
```

**`setText`로 되돌리는 줄을 빼면 안 된다.** 사용자가 `200`을 넣었는데 화면엔 계속 `200`이 보이고
실제로는 40으로 도는, 말없이 어긋나는 상태가 된다.

### 3-4. 전달 (82행)

```kotlin
                    svc.runTask(task, maxTurns) { line ->
```

`runTask(task)` → `runTask(task, maxTurns)`. trailing lambda(`{ line -> … }`)는 그대로 둔다 — §4의
파라미터 순서를 지키면 문법이 안 깨진다.

---

## 4. `a11service.runTask` — 파라미터 추가 ★순서 주의★

```kotlin
    fun runTask(task: String, maxTurns: Int = 20, log: (String) -> Unit = {}): String {
        cancelled = false
        showOverlay(task)
        val r = try {
            runAgent(this, cu, task, maxTurns,
                log = { line -> log(line);postOverlay(line) },
                cancel = {cancelled})
```

- **`maxTurns`는 반드시 `log` 앞에** 둔다. `log`가 마지막 파라미터여야 호출부의
  `svc.runTask(task, maxTurns) { line -> … }` trailing lambda 문법이 성립한다.
  뒤에 넣으면 `MainActivity`가 컴파일 에러가 난다.
- **기본값 `= 20`을 유지**한다. 그래야 소켓 `RUN` 경로(`runAgent(this, cu, task)`)와
  다른 기존 호출부가 안 깨진다.

---

## 5. 소켓 `RUN`은 왜 안 건드리나

```kotlin
"RUN" -> { val task = if (p.size > 1) line.trim().substringAfter(" ") else "설정 앱을 열어" }
```

첫 토큰 뒤를 **전부 목표 문자열로** 취급한다. 여기에 턴 수를 끼워넣으면(`RUN 30 유튜브 열어`)
`30`이 목표의 일부로 먹히거나, 목표가 숫자로 시작할 때 오작동한다.

필요해지면 **별도 명령**이 깔끔하다 — `MAXTURNS 30`으로 서비스 필드에 저장해두고 `RUN`이 그걸
읽는 식. 지금은 PC 경로를 쓸 일이 적으니 기본값 20으로 두고, **두 경로의 기본이 다르지 않다는
사실만 기억**하면 된다(둘 다 20).

---

## 6. (선택) 오버레이에 진행률 표시

최대치를 사용자가 정하게 되면 현재 턴만 보여주는 건 정보가 부족하다. `CuClient.kt:232`:

```kotlin
            emit("[턴 $turn/$maxTurns] $name {${fmtArgs(args)}}")
```

`maxTurns`는 `runAgent`의 파라미터라 그 자리에서 바로 쓸 수 있다. 로그·오버레이·저장 파일에
동시에 반영된다.

---

## 7. 테스트

1. **기본값** — 입력칸을 비우고 실행 → 20으로 돌고, 다음에 앱을 열면 칸에 `20`이 보인다.
2. **저장** — `5`로 바꿔 실행 → 앱 완전 종료 후 재실행 → 칸에 `5`가 남아 있다.
3. **clamp** — `200` 입력 후 실행 → **칸이 즉시 `40`으로 바뀌고** 40턴 상한으로 돈다.
4. **최소값** — `0` 입력 → `1`로 바뀐다. (clamp를 안 넣으면 `for(1..0)`이 한 번도 안 돌아
   즉시 `STOP: max turns`가 뜨고, 원인을 알기 어렵다.)
5. **짧은 상한 실동작** — `2`로 두고 여러 턴이 필요한 목표를 실행 → 2턴 후 `STOP: max turns`,
   알림은 "⚠ 최대 턴 도달".
6. **본래 목적 확인** — 기존에 20턴으로 실패하던 목표를 `40`으로 다시 실행 → 완주하는지 본다.
   여기서도 `STOP: max turns`가 뜨거나 같은 지점에서 맴돌면 §0의 **(b) 헛돌기**이므로
   턴 수를 더 올리지 말고 프롬프트 쪽을 본다.

---

## 8. 함정

| 증상 | 원인/해결 |
|---|---|
| `MainActivity` 컴파일 에러 (trailing lambda) | `runTask`에서 `maxTurns`를 `log` **뒤**에 넣음 → 앞으로 옮긴다 (§4) |
| 200을 넣었는데 40으로 도는 걸 모름 | clamp 결과를 `setText`로 안 되돌림 (§3-3) |
| 앱 재실행하면 항상 20 | `prefs.edit().putInt(...).apply()` 누락, 또는 복원 코드(§3-2)가 없음 |
| `0`을 넣었더니 즉시 종료되고 로그가 텅 빔 | `coerceIn(1, 40)` 누락. `for(1..0)`은 한 번도 안 돈다 |
| 턴을 올려도 여전히 `STOP: max turns` | §0의 **(b) 헛돌기**. 턴 수 문제가 아니다 — 마지막 5턴 로그를 보고 프롬프트 쪽을 손본다 |
| 소켓 `RUN`만 여전히 20 | 의도된 동작 (§5) |
| `MODE_PRIVATE` 를 못 찾음 | `AppCompatActivity`가 `Context`를 상속하므로 그대로 쓰인다. 안 되면 `Context.MODE_PRIVATE` |

---

## 9. 끝내고 할 것

`CLAUDE.md` §구현 현황의 **Update rule** — 기능이 바뀌면 **같은 커밋에서** 목록을 고친다.
「폰 단독 on-device」 목록에 한 줄 추가/수정:

```
- ✅ 최대 턴 수 사용자 설정 — 앱에서 1~40 지정, SharedPreferences 로 유지 (소켓 RUN 은 기본 20)
```
