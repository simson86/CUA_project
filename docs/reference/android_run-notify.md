# 실행 완료/최대턴 알림 (상태바 Notification)

> 로컬 전용(.gitignore `hybrid-*`). 실행이 끝나거나(완료/최대턴/오류) 알림으로 알려줌.

---

## 0. 설계 (왜 이렇게)

- **알림은 `a11service`(서비스)에서 발생.** 서비스는 접근성이 켜진 동안 항상 살아있음 →
  실행 중 앱이 뒤로 가거나 죽어도 알림이 뜬다. 소켓 RUN 경로도 자동 커버.
- **`runAgent`(판단/오케스트레이터)는 안 건드림.** 알림은 안드로이드 UI라 device 계층(service)에만 둠 → 분리 유지.
- **결과 문자열로 3가지 구분:** `Done turn=N …`=✅완료 / `STOP: max turns`=⚠최대턴 / `오류 …`=❌.
- **Android 13+(API33)** 는 알림도 런타임 권한(`POST_NOTIFICATIONS`) 필요 → MainActivity에서 요청. (폰=Android16)
- 새 gradle 의존성 없음(`androidx.core`의 NotificationCompat 사용, 이미 있음).

---

## 1. `AndroidManifest.xml` — 권한 1줄

기존 `<uses-permission android:name="android.permission.INTERNET"/>` 아래에:
```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

---

## 2. `a11service.kt` — 채널 만들고, 끝나면 알림

### 2-a. import 추가
```kotlin
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
```

### 2-b. companion object에 상수 2개 추가
```kotlin
    companion object {
        @Volatile
        var instance : a11service? = null
            private set
        const val CHANNEL_ID = "cu_run"   // ← 추가
        const val NOTI_ID = 1001          // ← 추가
    }
```

### 2-c. onServiceConnected에서 채널 생성 (한 줄)
```kotlin
    override fun onServiceConnected() {
        Log.d("A11y", "connected")
        instance = this
        createChannel()          // ← 추가
        startServer()
    }
```

### 2-d. runTask 교체 + 헬퍼 2개 추가

기존 `fun runTask(...) = runAgent(...)` 한 줄을 아래로 **교체**:
```kotlin
    fun runTask(task: String, log: (String) -> Unit = {}): String {
        val r = try {
            runAgent(this, cu, task, log = log)
        } catch (e: Exception) {
            "오류: ${e.message}"     // screenshot/네트워크 예외도 알림에 잡히게
        }
        notifyDone(task, r)
        return r
    }

    private fun createChannel() {
        // minSdk 30 ≥ 26 이라 버전분기 불필요
        val ch = NotificationChannel(CHANNEL_ID, "CU 실행 알림",
            NotificationManager.IMPORTANCE_HIGH)  // HIGH=상단에 잠깐 뜸+소리
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notifyDone(task: String, r: String) {
        val title = when {
            r.startsWith("STOP") -> "⚠ 최대 턴 도달"
            r.startsWith("오류")  -> "❌ 실행 오류"
            else                  -> "✅ 실행 완료"
        }
        // 알림 탭 → 앱으로 복귀
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // 기본 아이콘(커스텀 불필요)
            .setContentTitle(title)
            .setContentText(r)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$task\n$r"))  // 펼치면 전체
            .setContentIntent(pi)
            .setAutoCancel(true)                              // 탭하면 사라짐
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // API33+ 권한 없으면 조용히 skip(예외 방지). 권한은 MainActivity가 요청.
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(NOTI_ID, n)
        }
    }
```

> 이제 소켓 RUN 분기의 `runAgent(this, cu, task)`도 `runTask(task)`로 바꾸면 알림이 붙음(선택).
> 안 바꿔도 됨 — 앱 버튼 경로는 MainActivity가 `runTask`를 부르니 이미 알림 뜸.

---

## 3. `MainActivity.kt` — 알림 권한 요청 (Android 13+)

### 3-a. import 추가
```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
```

### 3-b. 클래스 안(메서드 밖)에 launcher 필드
```kotlin
    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 결과 무시 */ }
```

### 3-c. onCreate 안(setContentView 아래 아무 곳)에서 요청
```kotlin
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
```

> 앱 처음 열 때 "알림 허용?" 팝업 한 번 뜸 → 허용. 거부해도 앱은 정상(알림만 안 옴).

---

## 4. 테스트

1. Build ▶ 설치 → 앱 첫 실행 시 **알림 권한 팝업 → 허용**.
2. `설정 앱을 열어` 실행 → 끝나면 상태바에 **✅ 실행 완료 + 결과** 알림.
3. 알림 탭 → 앱으로 돌아옴.
4. 최대 턴 케이스(일부러 안 끝나는 애매한 목표)면 **⚠ 최대 턴 도달**.
5. (Wi-Fi 끄고 실행 등) 네트워크 실패면 **❌ 실행 오류**.

---

## 5. 함정
| 증상 | 원인/해결 |
|---|---|
| 알림이 안 뜸 | ①권한 거부됨(설정>앱>알림 허용) ②채널 미생성(onServiceConnected에 createChannel) |
| 권한 팝업이 안 뜸 | 이미 허용/거부됨. 설정>앱>Android_run>알림에서 수동 토글 |
| 소켓 RUN엔 알림 안 옴 | 정상 — RUN 분기가 `runAgent` 직접 호출 중이면. `runTask(task)`로 바꾸면 붙음 |
| `ic_dialog_info` 흐릿 | 상태바 아이콘은 단색 실루엣이 정석. 나중에 흰색 벡터 아이콘으로 교체 가능 |
| 실행마다 알림이 덮어씀 | 같은 NOTI_ID(1001)라 마지막 것만 남음(의도). 매번 새로 쌓고 싶으면 id를 매번 다르게 |
