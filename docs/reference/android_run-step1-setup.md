# 하이브리드 1단계 셋업 가이드 (안드로이드 처음인 사람용)

> 목표(1단계): **빈 앱이 폰에서 실행됨 → 접근성 서비스로 등록·켜짐 → `takeScreenshot`으로 화면 한 장 잡아 PNG 크기를 Logcat에 찍음.**
> 여기서 막히면 하이브리드 전체가 막히므로 첫 관문. 스펙은 [`android-accessibility.md`](android-accessibility.md), 메서드 매핑은 [`accessibility-migration-guide.md`](accessibility-migration-guide.md).
>
> ⚠️ **코드는 뼈대·`// TODO(나)`만.** 직접 채우며 배우는 게 이 프로젝트 목적.

---

## 0. 안드로이드 앱 아주 짧은 개념 지도

파이썬만 하다 오면 낯선 것들:

| 개념 | 한 줄 설명 | 우리 비유 |
|---|---|---|
| **Android Studio** | 공식 IDE(에디터+빌드+에뮬레이터). | PyCharm 같은 것 |
| **Gradle** | 빌드/의존성 도구. `build.gradle`에 SDK버전·라이브러리 적음. | `requirements.txt`+빌드 |
| **Kotlin** | 우리가 쓸 언어(자바 대체). | 파이썬 자리 |
| **`AndroidManifest.xml`** | 앱의 "설정표". 어떤 컴포넌트(액티비티/서비스)·권한이 있는지 선언. | 진입점 명세 |
| **Activity** | 화면 한 개. | 창(window) |
| **Service** | 화면 없이 도는 백그라운드 컴포넌트. **접근성 서비스가 이 종류.** | 데몬 |
| **`res/`** | 리소스(문자열·레이아웃·xml설정). | 정적 자원 |
| **minSdk / targetSdk** | 지원할 최소/목표 안드로이드 버전(API 레벨). | 파이썬 최소버전 |
| **Logcat** | 실시간 로그 콘솔. `Log.d("TAG", "...")`. | `print`/logging |
| **API 레벨** | OS 버전의 숫자 이름. Android 11 = **API 30**, 12=31, 13=33, 14=34. | — |

우리가 만들 것 = **Activity 1개(안내 화면) + AccessibilityService 1개(진짜 일꾼)**.

---

## 1. 폰 버전부터 확인 (minSdk 결정)

**설정 → 휴대전화 정보 → Android 버전.**

| 폰 버전 | minSdk | 캡처 방법 |
|---|---|---|
| **11 이상 (API 30+)** | 30 | `AccessibilityService.takeScreenshot()` — 제일 간단 |
| **10 이하** | 그 버전 | `MediaProjection`(1회 사용자 동의) 경로. 1단계 목표는 동일하되 캡처 코드가 다름 |

> 되도록 11+ 폰을 쓰자. 없으면 알려줘 → MediaProjection 버전으로 1단계를 다시 짜준다(가이드만).

---

## 2. 환경 설치 순서

1. **Android Studio 설치** (공식: developer.android.com/studio). 설치 중 SDK·에뮬레이터 컴포넌트 같이 받음.
2. 첫 실행 → **New Project → "Empty Views Activity"** (Compose 아님, 처음엔 단순한 게 나음) 선택.
   - Language: **Kotlin**
   - **Minimum SDK: API 30 ("Android 11")** ← 위 표 기준
   - 패키지명 예: `com.example.a11yagent`
3. 프로젝트 열리면 Gradle 동기화(Sync)가 자동으로 돎 — 처음엔 몇 분 걸림. 끝날 때까지 대기.

---

## 3. 폰을 개발 모드로 (USB로 앱 설치 — 딱 1단계 설치용)

> ⚠️ 헷갈리지 말 것: 여기서 USB는 **"우리가 만든 앱을 폰에 설치"**하는 용도지, 최종 동작(ADB 조종)이 아니다.
> 앱이 깔린 뒤엔 케이블 없이 폰 혼자 접근성으로 돈다. (나중에 무선 설치도 가능)

1. 폰: **설정 → 휴대전화 정보 → 빌드번호 7번 탭** → 개발자 옵션 활성화.
2. **개발자 옵션 → USB 디버깅 ON.**
3. USB로 PC 연결 → 폰에 뜨는 "USB 디버깅 허용?" 승인.
4. Android Studio 상단 기기 목록에 폰이 뜨면 → ▶(Run)로 빈 앱 설치·실행 확인. **여기까지가 1a.**

---

## 4. (1b) 접근성 서비스 등록 3요소

빈 앱이 폰에서 떴으면, 이제 접근성 서비스를 붙인다. 상세 스펙은 `android-accessibility.md` §1. 여기선 "어디에 무슨 파일" 위주.

### (a) 서비스 클래스 — `MyA11yService.kt` (새 파일)
```kotlin
class MyA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        // 서비스가 켜지면 시스템이 호출. 여기 로그 찍어 "켜짐" 확인.
        // TODO(나): Log.d("A11Y", "connected") 그리고 §5 캡처 테스트 호출
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent) { /* 지금은 비워둠 */ }
    override fun onInterrupt() {}
}
```

### (b) `AndroidManifest.xml` 에 `<service>` 선언
`<application>` 태그 안에 추가. (내용은 android-accessibility.md §1(a) 그대로)
```xml
<service android:name=".MyA11yService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true"
    android:label="@string/service_label">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```
> `@string/service_label`은 `res/values/strings.xml`에 문자열 하나 추가해야 함. `@xml/...`는 (c).

### (c) `res/xml/accessibility_service_config.xml` (새 파일)
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
```
- `canPerformGestures`는 1단계엔 안 쓰지만 뒤(탭)에서 필요하니 미리 켜둠.

---

## 5. (1b 핵심) 캡처 한 장 → PNG 크기 로그

`accessibility-migration-guide.md` §3.1의 `captureAsPng` 뼈대를 서비스 안에 넣고, `onServiceConnected`에서 한 번 호출해 본다.

```kotlin
fun captureOnce() {
    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
        object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                // TODO(나): HardwareBuffer -> Bitmap -> PNG ByteArray 변환
                // 그리고: Log.d("A11Y", "png bytes = ${bytes.size}")
                // 힌트: Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                //       ByteArrayOutputStream + bitmap.compress(PNG, 100, out)
                //       다 쓰면 r.hardwareBuffer.close()  (안 닫으면 누수)
            }
            override fun onFailure(code: Int) {
                // TODO(나): Log.e 로 code 찍기. FLAG_SECURE 화면이면 실패할 수 있음.
            }
        })
}
```

---

## 6. 켜고 확인하는 흐름

1. ▶로 앱 재설치.
2. 폰: **설정 → 접근성 → (우리 라벨) → 켜기.** (런타임 팝업 아님! 수동 토글)
3. 켜지면 `onServiceConnected` → `captureOnce` 실행.
4. Android Studio **Logcat**에서 `A11Y` 태그 필터 → `png bytes = 12345` 같은 로그 보이면 **1단계 성공.**

---

## 7. 1단계에서 막히기 쉬운 곳

- **접근성 목록에 안 뜸** → manifest `<service>` 선언 누락/오타, 또는 `@xml` 설정 파일 경로 문제. 재설치 후 확인.
- **켰는데 콜백 안 옴** → 서비스 클래스명·패키지 경로(`.MyA11yService`)가 manifest와 일치하는지.
- **`takeScreenshot` 실패(onFailure)** → API 30 미만이거나, 캡처 차단 화면(FLAG_SECURE: 뱅킹/결제). 홈 화면에서 테스트.
- **HardwareBuffer 처리** → 이 변환이 1단계 진짜 관문. 안 닫으면(close) 메모리 누수. 여기서 막히면 물어봐.

---

## 8. 1단계 다음(예고)

성공하면 **2단계 = 이 PNG를 소켓으로 PC에 보내기.** 그때 `A11yBridge.screenshot()`(PC쪽)와 왕복을 맞춘다.
전체 순서는 `accessibility-migration-guide.md` §4 참고.
