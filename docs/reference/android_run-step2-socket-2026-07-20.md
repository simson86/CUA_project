# 하이브리드 2단계 — 폰의 화면(PNG)을 소켓으로 PC에 전송

> 1단계(`android_run-step1-setup-2026-07-20.md`)에서 폰이 스스로 화면을 캡처(`takeScreenshot`→PNG)하는 걸 검증했다.
> 2단계 목표: 그 PNG를 **소켓으로 PC에 보내기**. 하이브리드의 "폰↔PC 연결" 부분.
> 형식: **완성 코드 + 줄별 설명 주석**. 직접 옮겨 적으며 이해하는 방식(빈칸/TODO 아님).
> 전체 로드맵은 `accessibility-migration-guide.md` §4.

---

## 0. 큰 그림 (역할)

```
PC (파이썬, 나중에 A11yBridge)  ──"SHOT" 요청──▶  폰 (접근성 앱 = 서버)
        클라이언트              ◀──PNG 바이트──    takeScreenshot → PNG
```

최종 구조에서 **PC가 지시하고 폰이 실행**하므로 → **폰 = 서버(듣기)**, **PC = 클라이언트(연결·요청)**.

한 번에 다 하면 어디서 막혔는지 모르니 **3조각**으로:

| 단계 | 목표 | 성공 확인 |
|---|---|---|
| **2a** | 폰이 소켓 서버 열고, PC 연결되면 로그 | `client connected` + `connected!` |
| **2b** | "SHOT" 요청 → PNG 바이트 응답 | PC가 바이트 수신 |
| **2c** | PC가 받은 PNG를 파일로 저장 → 열어 확인 | 진짜 스크린샷 이미지 |

---

## 2a — 연결만 먼저 (같은 Wi-Fi·방화벽·IP 걸러내기)

### ① Manifest 인터넷 권한
`AndroidManifest.xml`에서 `<manifest>` 바로 안, `<application>` **위**에 추가:
```xml
<!-- 소켓 통신(ServerSocket 포함)에 필요한 일반 권한. 설치 시 자동 허용(팝업 없음). -->
<uses-permission android:name="android.permission.INTERNET" />
```

### ② `MyA11yService`에 서버 코드
```kotlin
import java.net.ServerSocket          // TCP 연결을 '기다리는' 서버 소켓
import kotlin.concurrent.thread       // 백그라운드 스레드를 간단히 만드는 코틀린 헬퍼

override fun onServiceConnected() {
    Log.d("A11y", "connected")
    startServer()                     // 서비스가 켜지면 서버도 시작
}

fun startServer() {
    // 네트워크를 메인 스레드에서 하면 NetworkOnMainThreadException으로 크래시.
    // 그래서 백그라운드 스레드에서 돌린다.
    // isDaemon=true: 앱이 죽으면 이 스레드도 같이 죽게(앱 종료를 막지 않게) 하는 옵션.
    thread(isDaemon = true) {
        // 포트 8080에서 연결을 받는 서버 소켓. 한 번 열고 while로 계속 재사용.
        val server = ServerSocket(8080)
        Log.d("A11y", "server listening on 8080")

        while (true) {
            // accept()는 '블로킹' — PC가 접속해올 때까지 이 줄에서 멈춰 대기.
            // 접속되면 그 클라이언트와 통신할 Socket을 돌려준다.
            val client = server.accept()
            Log.d("A11y", "client connected: ${client.inetAddress}")

            // 2a는 '연결됐다'만 확인이 목표라 바로 닫는다.
            // 2b에서 이 자리에 "요청 읽기 → PNG 응답"이 들어간다.
            client.close()
        }
    }
}
```
> `captureOnce()`는 2a 동안 `onServiceConnected`에서 호출만 빼둔다(지우지 말 것). 2b에서 서버 안으로 옮긴다.

### ③ 폰 Wi-Fi IP 확인
- 폰: **설정 → Wi-Fi → 연결된 네트워크 상세** → IP (예 `192.168.0.23`).
- **PC와 폰이 같은 Wi-Fi**여야 함(모바일 데이터 아님).

### ④ PC 연결 테스트 (파이썬)
스크래치패드에 임시 스크립트:
```python
# test_connect.py — 폰 소켓 서버에 연결만 해보는 테스트
import socket

PHONE_IP = "192.168.0.23"   # ← 확인한 폰 IP로 교체
PORT = 8080                 # 폰 서버 포트와 동일

s = socket.socket()                 # 기본 TCP 소켓 생성
s.settimeout(5)                     # 5초 내 연결 안 되면 포기(무한대기 방지)
s.connect((PHONE_IP, PORT))         # 폰 IP:포트로 접속 (여기서 왕복 발생)
print("connected!")                 # 도달 = 성공
s.close()
```
실행: `py test_connect.py`

### 2a 성공 기준
1. 앱 재설치 → 접근성 켜기 → Logcat: **`server listening on 8080`**
2. `py test_connect.py` → **`connected!`** + Logcat **`client connected: /192.168.0.xx`**

---

## 2b — "SHOT" 요청 → PNG 응답

### 프로토콜 (아주 단순)
```
PC → 폰 :  "SHOT\n"                (명령 한 줄)
폰 → PC :  [길이 4바이트][PNG 바이트...]
```
> **왜 길이를 먼저 보내나:** TCP는 '스트림'이라 "어디까지가 사진 한 장인지" 경계가 없다.
> 그래서 먼저 4바이트로 "이제 N바이트짜리 PNG가 간다"고 알려주고, 받는 쪽은 딱 N바이트만 읽는다.
> 이 '길이 프리픽스' 패턴은 소켓으로 덩어리 데이터 보낼 때의 표준.

### 폰: `startServer` + `capturePngBlocking` (전체 교체)
```kotlin
private fun startServer() {
    thread(isDaemon = true) {
        val server = ServerSocket(8080)
        Log.d("A11y", "server listening on 8080")
        while (true) {
            val client = server.accept()                       // PC 접속 대기
            Log.d("A11y", "client connected: ${client.inetAddress}")
            try {
                // 클라이언트가 보낸 명령 한 줄을 읽는다. readLine()은 '\n'까지 읽고 개행은 뗀다.
                val line = client.getInputStream().bufferedReader().readLine()
                Log.d("A11y", "cmd: $line")

                if (line == "SHOT") {
                    val png = capturePngBlocking()             // 화면 캡처(동기적으로 바이트 획득)
                    val out = client.getOutputStream()

                    // 1) 길이 4바이트를 '빅엔디안'으로 먼저 전송.
                    //    ushr = 부호없는 오른쪽 시프트. 32비트 정수를 8비트씩 4조각으로 쪼갠다.
                    val n = png.size
                    out.write(byteArrayOf(
                        (n ushr 24).toByte(),                  // 최상위 바이트
                        (n ushr 16).toByte(),
                        (n ushr 8).toByte(),
                        n.toByte()                             // 최하위 바이트
                    ))
                    // 2) 이어서 PNG 실제 바이트 전송.
                    out.write(png)
                    out.flush()                                // 버퍼에 남은 것까지 즉시 내보냄
                    Log.d("A11y", "sent png $n bytes")
                }
            } catch (e: Exception) {
                Log.e("A11y", "client error: ${e.message}")
            } finally {
                client.close()                                 // 이번 요청 끝 → 연결 정리
            }
        }
    }
}

// takeScreenshot은 결과를 '콜백'으로 준다(비동기). 하지만 서버 스레드는 결과를
// 손에 쥐어야 소켓으로 보낼 수 있다 → CountDownLatch로 "콜백이 끝날 때까지" 기다린다.
private fun capturePngBlocking(): ByteArray {
    val latch = java.util.concurrent.CountDownLatch(1)         // 1회 신호용 빗장
    var result = ByteArray(0)                                  // 콜백이 채워줄 결과
    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
        object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val buffer = r.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, r.colorSpace)
                buffer.close()                                 // 네이티브 자원 해제(필수)
                val out = ByteArrayOutputStream()
                bitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                result = out.toByteArray()
                latch.countDown()                              // "다 됐다" 신호 → await 풀림
            }
            override fun onFailure(code: Int) {
                Log.e("A11y", "capture failed: $code")
                latch.countDown()                              // 실패해도 풀어줘야 서버가 안 멈춤
            }
        })
    latch.await()                                              // 콜백이 countDown 할 때까지 대기
    return result
}
```
> 데드락 걱정: 콜백은 `mainExecutor`(메인 스레드)에서 돌고, `await()`는 **서버 백그라운드
> 스레드**에서 기다린다. 서로 다른 스레드라 메인이 안 막혀서 정상 동작한다.
> 기존 `captureOnce()`는 `capturePngBlocking()`로 대체(지워도 됨).

## 2c — PC가 PNG를 받아 파일로 저장 → 눈으로 확인

```python
# test_shot.py — 폰에 SHOT 보내고 PNG 받아서 저장
import socket, struct

PHONE_IP = "192.168.0.23"   # ← 폰 IP
PORT = 8080

def recv_exact(sock, n):
    # recv는 요청한 n바이트보다 적게 올 수 있다(스트림이라). n바이트 다 모일 때까지 반복.
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("연결이 중간에 끊김")
        buf += chunk
    return buf

s = socket.socket()
s.settimeout(10)
s.connect((PHONE_IP, PORT))
s.sendall(b"SHOT\n")                       # 명령 전송(끝에 개행 — 폰 readLine이 이걸 기준으로 자름)

raw_len = recv_exact(s, 4)                  # 길이 4바이트 먼저 수신
n = struct.unpack(">I", raw_len)[0]         # ">I" = 빅엔디안 unsigned int → 정수 n
data = recv_exact(s, n)                     # 딱 n바이트(PNG) 수신
s.close()

with open("screen.png", "wb") as f:        # 파일로 저장
    f.write(data)
print(f"saved screen.png ({n} bytes)")
```
실행: `py test_shot.py` → `screen.png` 열어서 **폰 화면이 보이면 2단계 완성** 🎉
이 로직이 나중에 `A11yBridge.screenshot()`의 알맹이가 된다(3단계).

---

## 막히기 쉬운 곳
- **연결 timeout**: (1) 다른 Wi-Fi, (2) 공유기 **AP 격리**(기기간 통신 차단), (3) 폰 IP 오타 순으로 점검.
  정 안 되면 역할을 바꾸거나(폰이 PC로 접속) 무선디버깅 포트포워딩으로 우회.
- **포트 사용중**: 8080 대신 8765 등으로.
- **`NetworkOnMainThreadException`**: 네트워크는 반드시 백그라운드 스레드.
- **재설치 시 접근성 꺼짐**: 매번 설정에서 다시 켜기.
- **TCP 경계 문제(2b)**: 길이 프리픽스 없이 보내면 PC가 어디까지 읽어야 할지 모른다 → 길이 먼저.
