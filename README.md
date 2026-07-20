# CUA_project — Gemini Computer Use로 안드로이드 자동 조작

Google **Gemini Computer Use(CU)** 모델이 **실제 안드로이드 기기**를 조작하는 에이전트.
매 스텝: `스크린샷 + 목표 → 모델 → UI 액션 1개(탭/입력/…) → 기기에서 실행 → 반복`.

실행 방식이 **두 가지**다:

| 방식 | 실행부 | 케이블 | 폰에 앱 설치 |
|---|---|---|---|
| **A. ADB (케이블)** | `live/` + ADB | 필요(USB) | 불필요 |
| **B. 접근성 (무선)** | `live/` + `android/` 접근성 앱 | 불필요(Wi-Fi) | 필요 |

> B(무선)는 폰에 온디바이스 접근성 서비스(`android/`)를 깔아, PC와 **같은 Wi-Fi**에서 소켓으로 화면·제스처를 주고받는다. 판단(Gemini 호출)은 PC가, 캡처·조작은 폰이 담당.

---

## 구성

```
cua/            판단 코어 (스크린샷+목표 → CU 액션). ADB/벤치는 모름(순수 판단부)
live/           실행 레이어 + 멀티턴 루프 (main.py)
  adb_bridge.py       케이블(ADB) 실행부
  a11service_bridge.py  무선(접근성) 실행부 — 폰 앱과 소켓 통신
mobile_agent/   단일 파일 버전(별도 SDK 경로)
android/        폰 접근성 앱 (Kotlin, Android Studio 프로젝트)
```

---

## 사전 준비 (공통)

1. **Python (`py` 런처)** — `python` 아님. 저장소 루트에서:
   ```powershell
   py -m pip install -r requirements.txt
   ```
2. **API 키** — `.env.example`를 `.env`로 복사하고 `GEMINI_API_KEY` 채우기:
   ```
   GEMINI_API_KEY=여기에_본인_키
   ```
   (`.env`는 gitignore — 절대 커밋 금지)

---

## 방식 A — ADB (케이블)

1. 폰 USB 디버깅 켜고 연결 → `adb devices`에 1대 보이면 OK
2. 실행:
   ```powershell
   py live/main.py "설정 앱을 열어서 Wi-Fi를 켜"
   ```

---

## 방식 B — 접근성 (무선, 케이블 없음)

### 1) 폰 앱 설치 (`android/`)
1. **Android Studio**로 `android/` 폴더 열기 (Gradle sync 자동)
2. 폰 연결 → **Run ▶** 로 설치 (minSdk 30 이상 필요; 최초 1회만 USB, 이후 무선)
3. 폰 **설정 → 접근성 → (앱 이름) → 켜기**
4. 폰과 PC를 **같은 Wi-Fi**에 (모바일 데이터 아님)
5. 폰 IP 확인: **설정 → Wi-Fi → 연결된 네트워크 상세** (예 `192.168.0.51`)

> 앱을 재설치하면 접근성 토글이 꺼질 수 있으니 매번 다시 켜기.

### 2) PC에서 실행 (폰 IP를 환경변수로)
```powershell
$env:PHONE_IP="192.168.0.51"; py live/main.py "설정 앱을 열어"
```
- `PHONE_IP`는 하드코딩하지 않는다(폰마다·재접속마다 바뀜). 실행 시 넘긴다.
- 기대: `기기 해상도 ...` 출력 → `[턴 n] <액션>` 로그 → **폰 화면이 실제로 바뀜** → 목표 달성 시 `[완료]`.

---

## 동작 확인 (무선 경로 스모크 테스트)
폰 앱이 켜진 상태에서, PC가 화면 캡처+정중앙 탭이 되는지:
```python
# SHOT(해상도) + 화면 중앙 TAP 테스트 (PHONE_IP만 본인 값으로)
import socket, struct
IP, PORT = "192.168.0.51", 8080
def recv(s,n):
    b=b""
    while len(b)<n:
        c=s.recv(n-len(b));  b+=c
        if not c: raise ConnectionError
    return b
s=socket.socket(); s.connect((IP,PORT)); s.sendall(b"SHOT\n")
n=struct.unpack(">I",recv(s,4))[0]; png=recv(s,n); s.close()
w,h=struct.unpack(">I",png[16:20])[0],struct.unpack(">I",png[20:24])[0]
print("해상도",w,h)
s=socket.socket(); s.connect((IP,PORT)); s.sendall(f"TAP {w//2} {h//2}\n".encode())
print("ack",s.recv(16)); s.close()
```

---

## 주의
- **비밀:** `.env`(GEMINI_API_KEY)는 gitignore. 공유 금지. 협업자는 각자 키를 넣는다.
- **같은 Wi-Fi 필수** + 공유기 **AP 격리(기기간 통신 차단)** 꺼져 있어야 소켓이 붙는다.
- 모델 id: `gemini-3.5-flash` (`cua/cu_client.py`).
- 폰 접근성 앱 프로토콜: PC `SHOT`→`[len4][PNG]`, `TAP/SWIPE/LONGPRESS/TEXT/ENTER/BACK/HOME/RECENTS/OPEN`→`OK`.
