# 모델·사고수준을 앱에서 고르기

> 로컬 전용(.gitignore `android_run-*`). 팀원은 이 파일을 볼 수 없다.
>
> **구현 완료 (2026-08-11).** 빌드·검증까지 마쳤다. 아래는 *왜 이렇게 됐는지*의 기록이다.
>
> **모델과 사고수준 둘 다 앱 드롭다운에서 매 실행 고른다.** `local.properties`의
> `GEMINI_MODEL`/`GEMINI_THINKING`은 **드롭다운의 첫 기본 선택**만 정한다 — 한 번 실행하면 그때
> 고른 값이 `SharedPreferences`에 저장돼 그쪽이 이긴다.
>
> 값의 우선순위: **저장값 > 빌드값(local.properties) > `CuClient` 기본값**
>
> 설계 이력: 2026-08-06 전부 드롭다운(미구현) → 08-11 전부 `local.properties` → 08-11 사고수준만
> 드롭다운 → **08-11 둘 다 드롭다운**. 결국 처음 설계로 돌아왔지만, 그 사이에 얻은 것이 있다 —
> `local.properties` 경로가 *씨앗*으로 남아서, 앱을 새로 설치했을 때의 출발점을 빌드로 정할 수 있다.

---

## 0. ★ 먼저 알아야 할 것 — `thinking_level`은 평면 + 소문자

`v1beta/interactions`에 raw REST로 직접 실측한 결과:

| 보낸 형식 | 결과 |
|---|---|
| `generation_config: { thinking_config: { thinking_level: "HIGH" } }` (SDK 형식) | ❌ 400 `Unknown parameter 'thinking_config' at 'generation_config'` |
| `generationConfig: { thinkingConfig: { thinkingLevel: … } }` (camelCase) | ❌ 400 `Did you mean 'generation_config'?` |
| `generation_config: { thinking_level: "HIGH" }` (평면, **대문자**) | ❌ 400 `Supported values: 'minimal', 'low', 'medium', 'high'` |
| **`generation_config: { thinking_level: "high" }` (평면 + 소문자)** | ✅ 200 |

즉 **평면 + 소문자**다. 값은 `minimal` `low` `medium` `high` 넷.

**이 200이 의미가 있는 이유(대조군을 먼저 쟀다):** 서버가 모르는 키를 조용히 무시한다면 200은
아무것도 증명하지 않는다. 그래서 일부러 엉터리 키를 먼저 보냈고, 둘 다 거절당했다 —
`Unknown parameter 'bogus_param_xyz'`, `Unknown parameter 'bogus_inner_xyz' at 'generation_config'`.
**이 서버는 모르는 키를 반드시 거절한다.** 따라서 200 = 서버가 그 필드를 실제로 읽었다는 뜻이다.

**효과도 확인됐다** — 응답 `usage.total_thought_tokens`가 값에 따라 움직인다:

| 모델 | 미지정 | `minimal` | `high` |
|---|---|---|---|
| `gemini-3.5-flash` | 63 | **0** | 149 |
| `gemini-3.6-flash` | 78 | **0** | 54 |

### `gemini-3.6-flash`는 쓸 수 있다

`ListModels`에 있고, `tools:[{computer_use, mobile}]`로 호출했을 때 3.5와 **동일하게**
`function_call: list_apps`를 돌려줬다. 다만 주의: `supportedGenerationMethods`에는
`interactions`가 **3.5든 3.6이든 안 나온다.** 그 필드로 CU 지원 여부를 판별하려 하면 안 된다 —
실제로 쏴 보는 것 말고는 확인 방법이 없다.

---

## 1. 값이 흐르는 길

```
android/local.properties  ─┐  (씨앗: 첫 기본 선택)
                           ├─► BuildConfig.GEMINI_MODEL / GEMINI_THINKING
SharedPreferences  ────────┘         │
  ("model" / "thinking")             ▼
        │                    MainActivity: 드롭다운 초기 선택
        │                            │  실행 버튼
        ▼                            ▼
   실행할 때 저장 ◄──────  runTask(task, maxTurns, model, thinking, log)
                                     │
                                     ▼
                          cu.model / cu.thinkingLevel  (@Volatile var)
                                     │
                                     ▼
                          CuClient.cuCall → body.model
                                            body.generation_config.thinking_level
```

| 위치 | 무엇이 있나 |
|---|---|
| `local.properties` | `GEMINI_MODEL`, `GEMINI_THINKING` — 둘 다 씨앗 |
| `app/build.gradle.kts:31-44` | `buildConfigField` 2개 + 사고수준 값 검증 |
| `CuClient.kt:15-52` | companion에 `MODELS`·`THINKING`·기본값 2개·`modelIndex`/`thinkingIndex`, `@Volatile var model`/`thinkingLevel` |
| `CuClient.kt:187,193` | `model` 필드 사용, `generation_config` 항상 전송 |
| `a11service.kt:64-80` | `runTask`에 `model`·`thinking` 파라미터, `cu` 세팅, `[설정]` 로그 |
| `a11service.kt:177` | `CuClient(key, GEMINI_MODEL, GEMINI_THINKING)` — 씨앗 주입 |
| `activity_main.xml:59-100` | `modelSpinner` / `thinkingSpinner` 두 줄 |
| `MainActivity.kt` | import 2개, 어댑터·복원·저장·전달 |

**`runAgent`는 안 고쳤다.** 모델명·사고수준은 *전송* 설정이지 *판단 루프*의 관심사가 아니다.
`runAgent`에 파라미터를 늘리면 판단 코어가 HTTP 세부사항을 알게 되어 `Executor` 경계를 좁게
유지한 이점이 깎인다. 설정은 `cu` 객체에 실어 보낸다.

---

## 2. `android/local.properties`

```properties
sdk.dir=C\:\\Users\\shimw\\AppData\\Local\\Android\\Sdk
GEMINI_API_KEY=AQ.…

# 아래 둘은 앱 드롭다운의 '첫 기본 선택'만 정한다. 실제 값은 앱에서 매 실행 고르고,
# 한 번 고르면 그게 저장돼 이쪽보다 우선한다. 비우면 CuClient 기본값(gemini-3.5-flash / low).
GEMINI_MODEL=
GEMINI_THINKING=
```

**언제 이걸 건드리나:** 앱을 새로 설치했을 때(또는 앱 데이터를 지웠을 때) 어떤 값에서 출발할지를
정하고 싶을 때뿐이다. 평소에는 안 건드린다 — 값 바꾸기는 앱에서 한다.

**주의 세 가지**

1. **파일 맨 위의 "YOUR CHANGES WILL BE ERASED" 경고.** 저건 Android Studio가 `sdk.dir`을
   자동 관리한다는 뜻이고, 실제로 사용자 키는 지워지지 않는다 — `GEMINI_API_KEY`가 이미 여기서
   멀쩡히 살아 있는 것이 증거다.
2. **`.properties` 문법.** `#`은 주석, `\`는 이스케이프 문자다(그래서 `sdk.dir`에 `C\:\\…`).
   값에 따옴표를 감싸면 **따옴표까지 값에 들어간다** — `GEMINI_MODEL="gemini-3.6-flash"` ❌.
3. **git에 안 올라간다.** 팀원이 받아도 이 줄들은 없다 → §11.

---

## 3. `app/build.gradle.kts` — 25~44행

```kotlin
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) props.load(FileInputStream(f))
        val key = props.getProperty("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$key\"")

        // ★ 둘 다 없으면 빈 문자열로 넘긴다 — 기본값은 CuClient 가 유일한 출처다.
        val model = (props.getProperty("GEMINI_MODEL") ?: "").trim()
        val thinking = (props.getProperty("GEMINI_THINKING") ?: "").trim().lowercase()

        require(thinking.isEmpty() || thinking in listOf("minimal", "low", "medium", "high")) {
            "local.properties: GEMINI_THINKING='$thinking' — minimal/low/medium/high 중 하나이거나 비어 있어야 합니다."
        }

        buildConfigField("String", "GEMINI_MODEL", "\"$model\"")
        buildConfigField("String", "GEMINI_THINKING", "\"$thinking\"")
```

### 왜 이렇게 쓰나

- **★ 기본값을 여기서 채우지 않는다.** `?: "gemini-3.5-flash"`로 채우면 같은 문자열이
  `build.gradle.kts`와 `CuClient.DEFAULT_MODEL` **두 곳**에 살게 되고, `BuildConfig`가 항상 값을
  주므로 **`DEFAULT_MODEL`은 고쳐도 아무 일이 안 일어나는 죽은 값**이 된다(처음엔 실제로 그랬다).
  빈 문자열로 넘기고 `CuClient`가 떨구게 하면 기본값의 출처가 한 곳이다.
- **`.trim()`** — `.properties`는 값 끝의 공백을 안 잘라준다. 끝에 스페이스 하나가 붙으면
  드롭다운 목록과 문자열 비교가 어긋나 조용히 기본값으로 떨어지는데, 공백은 눈에 안 보인다.
- **`.lowercase()`** — §0에서 대문자는 400이다. 사람이 `HIGH`라고 쓸 확률이 충분히 높다.
  모델명은 소문자화하지 **않는다**(모델 id의 대소문자는 우리가 정할 문제가 아니다).
- **사고수준만 `require`로 검증한다.** 값이 4종으로 고정이라 여기 목록을 적어도 안 낡는다.
  **모델은 검증하지 않는다** — 후보 목록(`CuClient.MODELS`)을 여기 복사하면 새 모델이 나올 때마다
  두 곳을 고쳐야 하고, 그게 바로 위에서 피하려던 '기본값 두 군데' 문제와 같은 함정이다.
- **`buildConfigField`의 값은 생성되는 자바 소스에 그대로 붙여넣어진다.** 값 안에 `"`나 `\`가
  있으면 **자바 컴파일 에러**가 난다(에러가 `BuildConfig.java`를 가리켜 원인이 안 보인다).

### 구성 캐시 — 걱정했지만 문제 없다 ✅ (2026-08-11 실측)

`android/gradle.properties:19`에 **`org.gradle.configuration-cache=true`**가 켜져 있다.
`buildConfigField`는 *구성 단계*에서 계산되므로, `local.properties`만 고치고 빌드했을 때 Gradle이
구성 캐시를 재사용하면 옛 값 그대로 빌드될 **위험이 있어 보였다.**

**직접 확인했다 — Gradle이 이 파일을 캐시 입력으로 추적한다.** 같은 태스크
(`:app:generateDebugBuildConfig`)를 두 번 돌리되 사이에 `GEMINI_THINKING`만 고쳤더니:

```
Calculating task graph as configuration cache cannot be reused because
properties file C:\dev\CUA_run\android\local.properties has changed.
```

생성된 `BuildConfig.java`도 따라왔다. (한 번은 *다른* 태스크를 요청해 "no cached configuration
available"이 떴는데, 그건 무효화가 아니라 캐시 항목 자체가 없던 것이라 아무것도 증명하지 못한다 —
검증하려면 **같은 태스크를 두 번** 돌려야 한다.)

혹시라도 안 먹으면: ① AS에서 **Sync Project with Gradle Files** → ②
`./gradlew assembleDebug --no-configuration-cache` → ③ `providers.gradleProperty(...)`로 읽기.

---

## 4. `CuClient.kt`

### 4-1. 클래스 머리 (12~52행)

```kotlin
class CuClient(private val apiKey : String,
               model: String = DEFAULT_MODEL,
               thinking: String = DEFAULT_THINKING,) {
    companion object{
        val MODELS = listOf("gemini-3.5-flash", "gemini-3.6-flash")
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        val THINKING = listOf("minimal", "low", "medium", "high")
        const val DEFAULT_THINKING = "low"

        fun modelIndex(value: String?): Int =
            MODELS.indexOf(value).takeIf { it >= 0 } ?: MODELS.indexOf(DEFAULT_MODEL)
        fun thinkingIndex(value: String?): Int =
            THINKING.indexOf(value).takeIf { it >= 0 } ?: THINKING.indexOf(DEFAULT_THINKING)
    }

    @Volatile var model: String =
        if (model in MODELS) model else DEFAULT_MODEL
    @Volatile var thinkingLevel: String =
        if (thinking in THINKING) thinking else DEFAULT_THINKING
```

**세 가지 설계 판단:**

- **왜 `val` 생성자 프로퍼티가 아니라 `var`인가.** `a11service.cu`는 `by lazy`라 **최초 1회만**
  만들어지고 계속 재사용된다(`a11service.kt:177`). 생성자로만 받으면 **첫 실행 이후로는 못 바꾼다**
  — "앱에서 고른다"가 성립하지 않는다. `@Volatile`인 이유는 소켓 서버가 **별도 스레드**에서 같은
  `cu`를 쓰기 때문이다(`a11service.kt:297`).
  > 초기화식의 `model`은 **생성자 파라미터**다(프로퍼티 자신이 아니다). Kotlin에서 생성자
  > 파라미터는 프로퍼티 초기화식에서 이름이 같아도 우선한다 — 익숙한 관용구지만 처음 보면
  > 순환처럼 읽힌다.
- **생성자 인자는 둘 다 '씨앗'**이고, 비었거나 모르는 값이면 여기서 기본값으로 떨어진다.
  `BuildConfig`의 두 값이 빈 문자열일 때가 정확히 이 경우다. 기본값의 출처를 이 클래스 한 곳으로
  모으려는 것(§3).
- **★ `modelIndex`/`thinkingIndex`를 따로 둔 이유** — 흔한 관용구인
  `MODELS.indexOf(v).coerceAtLeast(0)`는 **틀렸다.** 못 찾으면 0번으로 떨어진다(사고수준이면
  `minimal`). 우리가 원하는 대체값은 목록의 첫 항목이 아니라 **기본값**이다. 저장값은 목록보다
  오래 살기 때문에(나중에 목록이 바뀐다) 이 분기는 실제로 탄다. `setSelection(-1)`으로 인한
  `IndexOutOfBoundsException`도 여기서 같이 막는다.

**새 모델이 나오면 `MODELS`에 한 줄** 추가하면 앱 드롭다운에 뜬다. 자유 입력(EditText)을 안 쓰는
이유: 모델명 오타는 첫 `cuCall`에서 400이고 화면엔 `오류: HTTP 400: …`으로만 보인다. 목록에서
고르게 하면 그 실패가 아예 안 생긴다.

### 4-2. 실행 기록용 한 줄

```kotlin
    /** run_history.txt 에 남길 설정 요약. 지난 실행이 어떤 설정이었는지 알 수 있게 한다. */
    fun settingsLine() = "model=$model thinking=$thinkingLevel"
```

### 4-3. `cuCall`

```kotlin
        if (prevId != null) body.put("previous_interaction_id", prevId)  // 턴2+에서만
        // 사고수준은 이제 항상 보낸다(앱에서 4종 중 하나를 반드시 고르므로 '미지정'이 없다).
        // 형식은 '평면 + 소문자' — SDK 형식(generation_config.thinking_config 중첩)은 이 엔드포인트에서
        // 400 이다. 실측표는 §0. 문서를 근거로 되돌리지 말 것.
        body.put("generation_config", JSONObject().put("thinking_level", thinkingLevel))
```

⚠️ **'미지정'(= `generation_config`를 아예 안 보내는 상태)이 사라졌다.** 4종 중 하나를 반드시
고르는 UI라 그렇다. 그래서 **이 기능 이전과 요청 본문이 같은 상태가 이제 없다** — 뭔가 나빠졌을 때
"기본으로 되돌려 비교" 하려면 §0 표의 미지정 열을 참고하거나 이 한 줄을 임시로 주석 처리해야 한다.
(값 목록에 "기본"을 하나 더 두면 되살릴 수 있다. 지금은 안 넣었다.)

---

## 5. `a11service.kt`

### 5-1. `runTask` ★파라미터 순서 주의★

```kotlin
    // ★ log 는 반드시 맨 뒤 — MainActivity 가 trailing lambda 로 넘긴다.
    // ★ 새 파라미터엔 기본값을 준다 — 소켓 RUN 경로와 기존 호출부가 안 깨지게.
    fun runTask(task: String, maxTurns: Int = 20,
                model: String = CuClient.DEFAULT_MODEL,
                thinking: String = CuClient.DEFAULT_THINKING,
                log: (String) -> Unit = {}): String {
        cancelled = false
        cu.model = model                     // 이번 판에 쓸 설정을 갈아끼운다
        cu.thinkingLevel = thinking
        log("[설정] ${cu.settingsLine()} maxTurns=$maxTurns")
        showOverlay(task)
```

- **`log`를 중간에 끼우면** `svc.runTask(task, maxTurns, model, thinking) { line -> … }`의 trailing
  lambda 문법이 깨진다. 최대 턴 때와 **똑같은 함정**이다.
- **`log("[설정] …")`을 빼지 말 것.** 설정을 바꿀 수 있게 만든 순간 생기는 가장 큰 손해는
  **어떤 설정이 어떤 결과를 냈는지가 기록에 안 남는 것**이다. `run_history.txt`의 유일한 증거다.

### 5-2. `cu` 생성

```kotlin
    private val cu by lazy {
        CuClient(
            BuildConfig.GEMINI_API_KEY,
            BuildConfig.GEMINI_MODEL,
            BuildConfig.GEMINI_THINKING,
        )
    }
```

### 소켓 `RUN`은 앱 설정을 물려받는다

`a11service.kt:297`의 `runAgent(this, cu, task)`는 `runTask`를 안 거치므로 `cu`의 두 필드를
안 건드린다 → **앱에서 마지막으로 고른 값**으로 돈다(앱 실행 전이면 `BuildConfig` 씨앗값).
`cu`가 하나뿐이니 자연스러운 동작이지만, PC 쪽에서 "왜 내가 지정한 적 없는 모델로 도는가"로
헷갈릴 수 있다. 이 경로는 **`[설정]` 로그도 안 남는다**(그 줄이 `runTask`에 있어서).

---

## 6. 어떤 값을 기본으로 둘 것인가

**`DEFAULT_MODEL = "gemini-3.5-flash"`, `DEFAULT_THINKING = "low"`.** 사고수준 쪽 근거는
기존 실측(`thinking-level.md` §6, 2026-07-15, 3.5-flash, PC+ADB 멀티턴):

> **권장 `low`** — 턴 최소·최속, 비용은 `minimal`과 동급.
> `minimal`은 첫 판단만 보면 싸 보이지만 **멀티턴에서 헤매 턴이 늘어** 그 이점이 사라진다.

**비용의 동인은 사고 토큰이 아니라 턴마다 들어가는 스크린샷(턴당 ~1만 토큰)이다.** §0 표에서
사고 토큰 차이는 기껏 0~149다. **사고수준을 낮춰 아끼는 돈은 사실상 없고, 턴이 하나라도 늘면
곧바로 손해다.** 이 노브는 "싸게" 쓰는 게 아니라 "덜 헤매게" 쓰는 것이다.

⚠️ 저 결론은 **3.5 · PC+ADB · 2026-07-15** 조건이다. 3.6이나 온디바이스 경로에서 같으리라는
보장은 없다 — 이제 드롭다운이 둘 다 있으니 폰에서 직접 재보고 정하면 된다. 그게 이 기능의 요점이다.

---

## 7. 속도에 미치는 영향

- **이 기능 자체의 오버헤드: 없다.** 요청 본문에 짧은 필드 하나, 실행당 대입 두 번.
- **사고수준:** §0 측정에서 한 턴 지연은 4.3~4.7초로 값에 관계없이 사실상 같았다. 단, 1×1 더미
  이미지 1회 호출이라 **의미 있는 측정이 아니다.** 진짜 영향은 한 턴이 아니라 **총 턴 수**로
  나타난다(§6). 벽시계의 ~85%가 API 왕복인 이 프로젝트에선 턴 수가 곧 속도다.
- **모델 교체:** 3.5 vs 3.6의 지연·정확도는 **아직 모른다.** 각 1회씩 잰 4.5초 언저리 숫자로는
  아무 말도 할 수 없다.
- **★ 비교 실험 자체의 속도가 이 기능의 진짜 값어치다.** 둘 다 재설치 없이 바뀌므로 A/B 비교가
  몇 초 단위다. 빌드·설치가 실험 사이클마다 끼면 비교를 아예 안 하게 된다 —
  `local.properties` 방식을 거쳐보고 이쪽으로 온 이유가 그것이다.

---

## 8. 실패했을 때 어떻게 보이나

| 언제 | 무엇이 보이나 |
|---|---|
| `GEMINI_THINKING` 오타 | **빌드 실패**, `build.gradle.kts line: 39` + 한국어 메시지 (§3의 `require`) |
| `GEMINI_MODEL` 오타 | 조용히 `gemini-3.5-flash`가 첫 선택 (§4-1) — 에러 없음 |
| 저장된 값이 목록에서 사라짐 | 조용히 기본값 선택 (§4-1) — 에러 없음 |
| `MODELS`에 실제로 없는 모델을 넣음 | 폰에서 실행 → **첫 `cuCall`에서 HTTP 400**, 화면에 `오류: HTTP 400: …` |

마지막 경우도 **액션을 하나도 실행하기 전에** 멈춘다 — 폰이 반쯤 조작된 상태로 남지 않는다.

---

## 9. 테스트

**✅ 1~4는 2026-08-11 PC에서 완료.** 5~11은 폰이 있어야 한다.

1. ~~**구성 캐시 검증**~~ ✅ — `local.properties` 변경이 캐시를 무효화하고 `BuildConfig.java`에
   반영되는 것 확인(§3).
2. ~~**`BuildConfig` 생성**~~ ✅ — `GEMINI_MODEL`/`GEMINI_THINKING` 둘 다 박힘(빈 값 포함).
3. ~~**빌드 시점 검증**~~ ✅ — `GEMINI_THINKING=hihg` → `line: 39`와 한국어 메시지로 BUILD FAILED.
4. ~~**전체 빌드**~~ ✅ — `:app:assembleDebug` BUILD SUCCESSFUL.
5. **드롭다운이 보이나** — 최대 턴 줄 아래에 `모델`·`사고수준` 두 줄. 처음엔
   `gemini-3.5-flash` / `low`(빌드값이 빈칸이므로).
6. **로그에 남나** — 실행하면 첫 줄
   `[설정] model=gemini-3.5-flash thinking=low maxTurns=20`. 값을 바꾸면 따라 바뀌는지.
7. **저장** — 3.6 + `high`로 실행 → 앱 완전 종료 후 재실행 → 드롭다운 둘 다 복원됐는지.
8. **씨앗** — 앱 데이터 삭제(저장값 제거) + `local.properties`에 `GEMINI_MODEL=gemini-3.6-flash`
   → 재빌드·재설치 → 드롭다운이 3.6에서 시작하는지.
9. **오래된 저장값** — `CuClient.THINKING`에서 `high`를 잠깐 빼고 빌드 → 앱이 안 죽고
   **`minimal`이 아니라 `low`**가 선택되는지(§4-1). 확인 후 되돌린다.
10. **3.6 실주행** — 앱에서 `gemini-3.6-flash` 골라 평소 과제 완주하는지.
11. **소켓 `RUN`** — 앱에서 3.6으로 한 판 돌린 뒤 PC에서 `RUN` → 3.6으로 도는지(§5의 의도된 동작).

---

## 10. 함정

| 증상 | 원인/해결 |
|---|---|
| `HTTP 400: Unknown parameter 'thinking_config' at 'generation_config'` | SDK 형식을 그대로 옮김. **평면 + 소문자** (§0) |
| `HTTP 400: Supported values: 'minimal', 'low', …` | 대문자로 보냄. `HIGH` ❌ `high` ✅ |
| `HTTP 400: Did you mean 'generation_config'?` | camelCase. REST는 snake_case |
| `HTTP 400` — 모델을 못 찾음 | `MODELS`에 실제로 없는 모델명을 적어둠 (§4-1) |
| `local.properties`를 고쳤는데 안 바뀜 | ① 저장값이 이김(정상) — 앱에서 고르거나 앱 데이터 삭제. ② 구성 캐시 (§3) |
| `MainActivity` 컴파일 에러 (trailing lambda) | `runTask`에서 `log`가 마지막이 아님 (§5-1) |
| 첫 실행 후 값을 바꿔도 안 바뀜 | `model`/`thinkingLevel`을 `val`로 둠. `cu`는 `by lazy`라 한 번만 만들어진다 → `var` (§4-1) |
| 스피너가 늘 첫 항목으로 리셋 | `coerceAtLeast(0)` 관용구를 씀 — 못 찾으면 0번. `modelIndex`/`thinkingIndex` 쓸 것 (§4-1) |
| `IndexOutOfBoundsException` (스피너) | `setSelection(-1)` — 위와 같은 원인 |
| 앱 재실행하면 항상 초기값 | `prefs.edit()…apply()` 누락, 또는 복원 코드 없음 |
| `DEFAULT_MODEL`을 고쳤는데 아무 일도 안 일어남 | `build.gradle.kts`가 기본값을 채우고 있음 → 빈 문자열로 넘겨 `CuClient`가 떨구게 (§3) |
| `Unresolved reference: GEMINI_MODEL` | `buildConfigField` 추가 후 **Gradle Sync 안 함** |
| `BuildConfig.java`에서 자바 컴파일 에러 | 값에 `"` 또는 `\`가 들어감 (§3) |
| PC `RUN`이 예상 밖 설정으로 돔 | 의도된 동작 — `cu`가 하나라 앱 설정을 물려받는다 (§5) |
| 지난 로그에서 어떤 설정이었는지 모름 | `log("[설정] …")` 누락 (§5-1) |

---

## 11. `local.properties`는 공유되지 않는다

`android/.gitignore:15`로 빠져 있고 앞으로도 그래야 한다(API 키가 들어 있다). 결과:

- **팀원이 받으면 씨앗이 없어 `gemini-3.5-flash` / `low`에서 출발한다.** 안전한 기본값이라
  이제는 문제될 게 거의 없다(앱에서 바꾸면 되므로 — 모델까지 앱으로 옮긴 부수 효과다).
- 그래도 **키 이름과 허용값은 추적되는 문서에 적어둘 것** (§13-a에서 `CLAUDE.md`에 반영).
  파일로 남기고 싶으면 `android/local.properties.example`을 **값은 비운 채로** 커밋한다.
- ⚠️ **`GEMINI_API_KEY`는 APK에 그대로 박힌다. APK를 넘기면 키를 넘기는 것이다.**

---

## 12. 앞으로 손댈 여지

- **"기본"(미지정) 항목 되살리기** — 사고수준 드롭다운에 항목 하나 더. §4-3의 트레이드오프 참조.
- **모델 자유 입력** — 빌드 없이 새 모델을 시험하고 싶어지면 스피너 마지막 항목을 `직접 입력`으로
  두고 선택 시 EditText를 `View.VISIBLE`로. 지금은 `MODELS`에 한 줄 추가로 충분하다.
- **소켓 `RUN`에도 `[설정]` 로그** — 지금은 `runTask`를 안 거쳐 안 남는다(§5).

---

## 13. 끝내고 할 것

**(a) `CLAUDE.md` §구현 현황** ✅ 반영함 — Update rule에 따라 같은 커밋에서.

**(b) `CLAUDE.md` §Gotchas에 §0을 옮기기** ✅ 반영함. 이건 코드만 봐선 절대 모르는 종류라,
안 적어두면 누군가 SDK 문서를 근거로 "고치려다" 400을 만든다(안전 승인 형식 때와 같은 구도).

**(c) `docs/reference/thinking-level.md`** — §2의 SDK 규격 옆에 "REST(interactions)는 평면 +
**소문자 강제**" 한 줄. (그 문서 §2는 원래부터 평면이 맞다고 적고 있었다. 틀린 쪽은
`cua/cu_client.py:79`의 주석이었다.) **아직 안 함.**

**(d) 모델 id를 적어둔 문서들** — 3.6을 기본으로 삼게 되면 `CLAUDE.md`, `README.md`,
`docs/reference/gemini-computer-use.md`, `docs/android-structure.html`, `HOW_TO_RUN_ANDROID_APP.html`.
기본을 3.5로 유지한다면 손댈 필요 없다.

---

## 14. 범위 밖(파이썬) — 기록만

> ## ⛔ 이 절은 **적용하지 않는다**
> 지금은 **Android만 작업한다.** `cua/`·`live/`·`mobile_agent/`·`tools/`는 건드리지 않는다.

- `cua/cu_client.py`는 `CUClient(model=…)`·`thinking_level` 인자를 이미 갖고 있다.
  `live/main.py`엔 `--model`이 없다(`--thinking`만 있음).
- **★ 미수정 버그:** `_build_generation_config`가 **중첩** 형식을 보낸다. 400이 안 나고 **조용히
  무시된다.** 4회 측정 — 평면 `minimal`: `0,0,0,0` / 중첩 `minimal`: `107,62,126,121` /
  미지정: `148,174,66,102`. **중첩 = 미지정.** `git log -S`로 `0db3327`(2026-07-17)이 평면→중첩으로
  바꾸며 `.lower()`도 지운 것을 확인. 즉 **2026-07-17 이후 `live --thinking`과
  `CU_THINKING_LEVEL`은 아무 효과가 없다.** 고칠 때는 `cfg["thinking_level"] = thinking_level.lower()`
  한 줄(`.lower()` 빠뜨리면 400). **2026-07-15 벤치는 그 커밋 이전이라 유효하다.**
- `tools/bench_thinking.py:205` `PRICING.get(args.model, {"in":0,"out":0})` — 3.6 항목이 없어
  **에러 없이 비용이 $0으로 찍힌다.**
