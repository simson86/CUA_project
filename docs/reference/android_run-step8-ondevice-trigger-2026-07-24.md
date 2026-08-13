# step8 — 온디바이스 트리거 (소켓/PC 없이 폰에서 직접 실행)

> ①번(폰 단독)의 **마지막 조각**.
> 지금까진 PC가 `RUN`을 소켓으로 쏴야 시작됐음 → 이제 **폰 화면의 입력창+버튼**으로 시작.
> 이게 되면 "PC 없어도 폰 혼자 돈다"가 코드로도 성립.

---

## 0. 무엇을·왜

지금 흐름: `send.py → 소켓 8080 → startServer()의 RUN 분기 → runAgent(this, cu, task)`.
바꿀 흐름: **`MainActivity`의 버튼 → 실행 중인 서비스 인스턴스 → runAgent(...)`**.

핵심 3개 (이게 전부):

1. **서비스 인스턴스를 어떻게 잡나** — Activity와 AccessibilityService는 **서로 다른 객체**다.
   Activity가 `new a11service()` 하면 안 됨(그건 시스템이 만든 '살아있는' 서비스가 아님 — 화면캡처/제스처 권한 없음).
   → 서비스가 켜질 때 **자기 자신을 `companion object`에 등록**해두고, Activity는 그걸 꺼내 쓴다.
   이게 원본 구조에서 `live/main.py`가 `bridge`(ADBBridge 인스턴스)를 손에 쥐고 있던 것과 같은 역할.

2. **반드시 백그라운드 스레드** — `runAgent`는 ①네트워크(Gemini) ②`takeScreenshot`/`dispatchGesture`의 `latch.await()`로
   **블로킹**한다. 메인(UI) 스레드에서 부르면 `NetworkOnMainThreadException` 또는 화면 멈춤(ANR).
   → `thread { ... }` 안에서 호출, 결과는 `runOnUiThread { }`로 UI에 반영.

3. **접근성이 꺼져 있으면** 인스턴스가 `null` → 안내 + 접근성 설정 화면 열어주기.

> ⚠️ **소켓 서버는 일단 그대로 둔다.** 지우지 않아도 "폰 단독"은 UI가 있으면 성립.
> send.py(PC 디버깅)도 계속 쓸 수 있어 편함. 완전 제거는 §4(선택).

---

## 1. `a11service.kt` — 인스턴스 등록 + 진입점 (3곳 추가)

### 1-a. 클래스 맨 위에 companion object 추가

`class a11service : AccessibilityService(), Executor {` 바로 아래, `private val cu ...` 위/아래 아무 곳에:

```kotlin
    companion object {
        // 살아있는 서비스 인스턴스. 서비스가 켜지면 자신을 등록, 꺼지면 null.
        // @Volatile: 서비스 스레드가 쓰고 Activity(다른 스레드)가 읽으므로 가시성 보장.
        @Volatile
        var instance: a11service? = null
            private set   // 외부에서 읽기만, 세팅은 이 클래스 안에서만
    }
```

### 1-b. onServiceConnected에서 등록 (기존 메서드에 한 줄)

```kotlin
    override fun onServiceConnected() {
        Log.d("A11y", "connected")
        instance = this          // ← 추가: 나(살아있는 서비스)를 등록
        startServer()            // (소켓 서버는 그대로 둠)
    }
```

### 1-c. 꺼질 때 해제 + 진입점 메서드 추가

`onInterrupt()` 근처(클래스 안 아무 곳)에 추가:

```kotlin
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null          // 접근성 OFF/재설치 시 죽은 참조 방지
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 온디바이스 트리거 진입점.
     *  cu는 private로 두고 Activity엔 이 메서드만 노출(원본 main.run()과 같은 위치).
     *  ⚠️ 반드시 백그라운드 스레드에서 호출할 것(네트워크+latch 블로킹). */
    fun runTask(task: String): String = runAgent(this, cu, task)
```

> `Intent`는 이미 import 되어 있음(openApp에서 씀). 추가 import 불필요.

---

## 2. `MainActivity.kt` — 전체 교체

```kotlin
package com.cua.a11

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input  = findViewById<EditText>(R.id.taskInput)
        val runBtn = findViewById<Button>(R.id.runBtn)
        val result = findViewById<TextView>(R.id.resultView)

        runBtn.setOnClickListener {
            val task = input.text.toString().trim()
            if (task.isEmpty()) {
                result.text = "목표를 입력하세요. 예: 설정 앱을 열어"
                return@setOnClickListener
            }
            val svc = a11service.instance
            if (svc == null) {
                // 접근성 서비스가 안 켜져 있음 → 안내 + 설정 화면으로
                result.text = "접근성 서비스가 꺼져 있습니다.\n설정 > 접근성에서 'Android_run'을 켠 뒤 다시 실행하세요."
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            // 실행 중 버튼 잠그고, 백그라운드에서 자율 루프 실행
            runBtn.isEnabled = false
            result.text = "실행 중… ($task)"
            thread {
                val r = try {
                    svc.runTask(task)                 // 여기서 캡처→Gemini→제스처 반복(수 턴)
                } catch (e: Exception) {
                    "오류: ${e.message}"
                }
                runOnUiThread {
                    result.text = r                   // "Done turn=N : ..." 표시
                    runBtn.isEnabled = true
                }
            }
        }
    }
}
```

> 기존 `enableEdgeToEdge()`/insets 코드는 뺐다(입력창이 상태바에 가리지 않게 레이아웃 padding으로 처리).
> 그대로 쓰고 싶으면 유지해도 됨 — 단 insets listener의 `R.id.main`은 §3 레이아웃 루트 id와 일치해야 함.

---

## 3. `app/src/main/res/layout/activity_main.xml` — 전체 교체

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:paddingTop="56dp">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="온디바이스 CU — 폰 단독 실행"
        android:textSize="20sp"
        android:textStyle="bold"
        android:paddingBottom="16dp"/>

    <EditText
        android:id="@+id/taskInput"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="목표 (예: 설정 앱을 열어)"
        android:inputType="text"
        android:imeOptions="actionDone"/>

    <Button
        android:id="@+id/runBtn"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:text="실행"/>

    <TextView
        android:id="@+id/resultView"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:textSize="15sp"
        android:textIsSelectable="true"
        android:text=""/>
</LinearLayout>
```

> 루트 id를 `@+id/main`으로 유지 → 기존 insets 코드를 남겨도 안 깨짐.

---

## 4. (선택) 소켓/PC 완전 제거

"진짜 폰 단독" 상징을 코드로도 남기고 싶으면 소켓을 지운다. **급하지 않음** — send.py 디버깅이 편해서 나중에 해도 됨.

지울 것:
- `onServiceConnected()`의 `startServer()` 호출 한 줄
- `startServer()` 메서드 전체 (SHOT/TAP/.../RUN 소켓 분기)
- 그와 함께 안 쓰이게 되는 것: `ackOK`, import `ServerSocket`/`Socket`/`Base64`(소켓 TEXT 분기용) — **단, `capturePngBlocking`·`tapBlocking`·`setText` 등은 dispatch가 계속 쓰므로 남긴다.**

남기는 것(온디바이스 루프가 쓰는 것): `screenshot()`, `dispatch()`, `pxX/pxY`, `pngSize`, `capturePngBlocking`, `dispatchBlocking`, `tapBlocking`, `swipeBlocking`, `setText`, `imeEnter`, `openApp`, `runTask`, companion `instance`.

> 지우면 send.py로는 더 이상 못 붙음(그게 목적). SHOT 캡처 확인이 필요하면 그 전에 해둘 것.

---

## 5. 테스트 절차

1. 위 3파일 반영 → **Build ▶ 설치**.
2. **설정 > 접근성 > Android_run 켜기** (재설치하면 꺼지니 매번 확인).
3. 앱 열기 → 입력창에 `설정 앱을 열어` → **실행**.
4. 화면이 실제로 바뀌고, 잠시 뒤 결과창에 `Done turn=N : 설정 열었습니다.` 뜨면 성공 ✅
   (Logcat 태그 `a11cu`로 턴 흐름도 확인 가능.)
5. 접근성을 끈 상태로 실행 → "접근성 서비스가 꺼져 있습니다" + 설정 화면 열림 → 정상.

---

## 6. 함정

| 증상 | 원인/해결 |
|---|---|
| 버튼 눌러도 아무 반응 없고 앱 멈춤(ANR) | `thread{}` 빼먹고 메인스레드에서 `runTask` 호출 → 반드시 백그라운드 |
| `a11service.instance`가 항상 null | onServiceConnected에 `instance = this` 누락, 또는 접근성 OFF |
| 재설치 후 첫 실행이 null | 접근성 토글이 꺼짐 → 다시 켜기(정상 동작) |
| 결과창에 `오류: NetworkOnMainThread...` | 위와 동일 — 스레드 확인 |
| 화면 안 바뀌는데 Done | dispatch가 조용히 실패 → Logcat `a11cu`에서 `dispatch실패` 확인, 접근성 `canPerformGestures` 확인 |
| ⚠️ 결제/구매/전송 | 여전히 **자동승인** 상태 — 위험작업 금지. `android_run-todo-safety-confirm-2026-08-03.md` 참고 |

---

## 7. 완료 후

- 이걸로 **①번(폰 단독) 기능 100%**: 화면캡처·판단(Gemini)·실행·트리거 전부 폰 안.
- PC(send.py/소켓)는 이제 **선택적 디버깅 채널**일 뿐.
- 다음 후보: 안전확인 다이얼로그(`android_run-todo-safety-confirm-2026-08-03.md`), list_apps 서드파티 필터, §4 소켓 제거.
