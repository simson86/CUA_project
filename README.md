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
2. **`android/local.properties`에 API 키 넣기** ⚠️ 이거 없으면 앱이 빌드는 되지만 실행하면 401.
   이 파일은 gitignore라 각자 만들어야 한다 — 앱은 `.env`를 **안 읽는다**(빌드 시점에 굳는다).
   ```properties
   GEMINI_API_KEY=여기에_본인_키
   ```
3. 폰 연결 → **Run ▶** 로 설치 (minSdk 30 이상 필요; 최초 1회만 USB, 이후 무선)
4. 폰 **설정 → 접근성 → (앱 이름) → 켜기**
5. 폰과 PC를 **같은 Wi-Fi**에 (모바일 데이터 아님)
6. 폰 IP 확인: **설정 → Wi-Fi → 연결된 네트워크 상세** (예 `192.168.0.51`)

> 앱을 재설치하면 접근성 토글이 꺼질 수 있으니 매번 다시 켜기.

#### 앱 화면에서 고를 수 있는 것
목표 입력칸 아래 세 가지. 고른 값은 폰에 저장돼 다음 실행에도 유지된다.

| 항목 | 값 | 기본 |
|---|---|---|
| **최대 턴** | 1~40 | 20 |
| **모델** | `gemini-3.5-flash` / `gemini-3.6-flash` | 3.5 |
| **사고수준** | `minimal` / `low` / `medium` / `high` | `low` |

- 실행 로그 첫 줄에 `[설정] model=… thinking=… maxTurns=…`이 찍히므로, 지난 실행이 어떤
  설정이었는지 `run_history.txt`에서 확인할 수 있다.
- **새 모델을 추가하려면** `CuClient.MODELS`에 한 줄 넣으면 드롭다운에 뜬다.
- 앱을 **처음 설치했을 때의 출발점**을 바꾸고 싶으면 `android/local.properties`에
  `GEMINI_MODEL=` / `GEMINI_THINKING=`을 채운다(선택). 한 번 실행하면 앱에 저장된 값이 우선한다.
- 사고수준은 **낮다고 싸지 않다.** 비용의 대부분은 턴마다 들어가는 스크린샷이라, `minimal`로
  두면 모델이 헤매 턴이 늘어 오히려 손해다. 근거는 `docs/reference/thinking-level.md` §6.

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
- **비밀:** `.env`(PC용)와 `android/local.properties`(앱용) 둘 다 gitignore. **서로 공유되지 않으니 각자 두 곳에 키를 넣는다.** 공유 금지.
  - ⚠️ 앱 키는 빌드 시 `BuildConfig`로 **APK 안에 박힌다** — APK를 남에게 주면 키를 주는 것이다.
- **같은 Wi-Fi 필수** + 공유기 **AP 격리(기기간 통신 차단)** 꺼져 있어야 소켓이 붙는다.
- 모델 id: PC 경로는 `gemini-3.5-flash` 고정 (`cua/cu_client.py`). **폰 앱은 화면에서 고른다**(위 표).
- 폰 접근성 앱 프로토콜: PC `SHOT`→`[len4][PNG]`, `TAP/SWIPE/LONGPRESS/TEXT/ENTER/BACK/HOME/RECENTS/OPEN`→`OK`.
