# 사용법 — 폰 단독 온디바이스 CU 실행 치트시트

> ①번(폰 단독) 앱을 **실제로 돌리는 방법**만 모음.
> 코드 설명은 각 step 문서(step5=단발 호출, step6=자율 루프)·안전은 `android_run-todo-safety-confirm-2026-08-03.md`.

---

## 0. 준비물 (매번 확인)

1. **폰**: Android Studio에서 앱 **Run ▶** 설치됨 + **설정>접근성에서 서비스 ON**.
   - 재설치하면 접근성 토글이 꺼질 수 있음 → 매번 다시 켜기.
2. **같은 Wi-Fi**: 폰·PC 동일 네트워크.
3. **폰 IP 확인**: 폰 설정>Wi-Fi>(연결된 AP)>상세, 또는 아래 SHOT이 되면 OK.
   - 현재 상용 폰 IP: `192.168.0.51` (DHCP라 바뀔 수 있음 — 안 되면 다시 확인).
4. **API 키**: 앱에 `local.properties`의 `GEMINI_API_KEY`가 빌드 시 박힘(BuildConfig). 폰이 직접 Gemini 호출하므로 PC엔 키 불필요.
5. **파이썬**: PC에서 명령 보낼 때 `py` 사용(‼️ `python` 아님 — msys2라 안 됨).

> 폰이 뜨면 소켓 서버가 **8080 포트**에서 대기. PC는 한 줄 명령을 보내면 됨.

---

## ★ 제일 쉬운 방법 — `tools/send.py` (이거만 알면 됨)

매번 긴 소켓 코드 붙여넣지 말고 이 도구를 쓰면 끝. (`RUN`은 cmd 명령이 아니라 폰에 보내는 소켓 신호라, 직접 못 침 → 이 스크립트가 대신 보냄.)

```powershell
py tools/send.py "설정 앱을 열어"          # ★자율 실행(RUN): 폰이 목표를 혼자 수행
py tools/send.py --shot                     # 현재 폰 화면 캡처 → after.png
py tools/send.py "쿠팡 슬리퍼 검색" --ip 192.168.0.99 --timeout 240
```
- 목표만 따옴표로 바꾸면 됨. 결과는 한글 안 깨지고 출력.
- IP 기본값 `192.168.0.51`, 바뀌면 `--ip`. 오래 걸리는 작업은 `--timeout`(초) 늘리기.
- 연결 실패하면 이유(접근성 OFF/다른 Wi-Fi/IP 틀림)를 안내해 줌.
- ⚠️ **결제/구매/전송/삭제 금지**(자동승인 상태). 안전한 범위만 — §2 경고 참고.

> 아래 §1~§3은 send.py 없이 **소켓을 직접** 다루는 원리/수동 방법(도구가 안 될 때 참고용).

---

## 1. 연결 확인 (SHOT) — 먼저 이걸로 살아있나 테스트

```powershell
$ip="192.168.0.51"
py -c "import socket,struct; s=socket.socket(); s.settimeout(10); s.connect(('$ip',8080)); s.sendall(b'SHOT\n'); n=struct.unpack('>I',s.recv(4))[0]; print('OK, png',n,'bytes'); s.close()"
```
- `OK, png 76901 bytes` 식으로 나오면 폰 살아있음 ✅
- 에러(ConnectionRefused)=앱 안 떴거나 접근성 OFF. (ConnectionReset=빌드 실패로 옛 APK 실행 중)

---

## 2. ★자율 실행 (RUN) — 폰이 목표를 혼자 수행★

**형식:** `RUN <목표를 한글/영어로>` — 폰이 캡처→Gemini→제스처를 완료까지 반복.

```powershell
$ip="192.168.0.51"
py -c "import socket
s=socket.socket(); s.settimeout(180)
s.connect(('$ip',8080)); s.sendall('RUN 설정 앱을 열어\n'.encode())
buf=b''
while True:
    d=s.recv(4000)
    if not d: break
    buf+=d
    if buf.endswith(b'\n'): break
print(buf.decode('utf-8','replace')); s.close()"
```
- 성공 시 `Done turn=N : <완료 텍스트>` 반환 + 폰 화면이 실제로 바뀜.
- 타임아웃 180초(루프가 수 턴 도니 넉넉히). 복잡한 작업은 더 늘려도 됨.
- **한글 깨져 보이면** 콘솔 인코딩 문제일 뿐(소켓 바이트는 정상 UTF-8). 아래 §3 방법으로 깔끔히 볼 수 있음.

### 목표 예시
- `RUN 설정 앱을 열어` — ✅ 검증됨(6턴 완주)
- `RUN 설정에서 접근성 메뉴로 들어가` — 안전
- `RUN 쿠팡 열어서 슬리퍼 검색하고 장바구니에 담아` — ⚠️ **결제 직전까지만**. 아래 경고 참고.

### ⚠️ 위험 작업 경고 (지금은 자동승인 상태)
- **"구매/결제/전송/삭제"는 시키지 말 것.** 현재 안전확인이 **자동승인**이라 결제 버튼도 사람 확인 없이 눌러버림 → 실제 돈 나갈 수 있음.
- 실제 결제 마지막의 **지문/PIN 화면은 접근성으로 조작 불가**(OS 보안) → 어차피 거기서 멈춤.
- 안전하게: **"장바구니 담기까지"**, **"검색까지"** 처럼 되돌릴 수 있는 범위만.
- 근본 해결=`android_run-todo-safety-confirm-2026-08-03.md`(사람 확인 다이얼로그로 교체) 후 진행.

---

## 3. 결과 한글 깨짐 없이 보기 / 실행 후 화면 확인

**RUN 결과를 파일로 받아 UTF-8로:**
```powershell
py -c "import socket
s=socket.socket(); s.settimeout(180); s.connect(('192.168.0.51',8080))
s.sendall('RUN 설정 앱을 열어\n'.encode())
buf=b''
while True:
    d=s.recv(4000);
    if not d or buf.endswith(b'\n'): break
    buf+=d
open('run_result.txt','w',encoding='utf-8').write(buf.decode('utf-8','replace')); s.close()
print('saved run_result.txt')"
```

**실행 후 폰 화면 캡처(눈으로 검증):**
```powershell
py -c "import socket,struct
s=socket.socket(); s.settimeout(15); s.connect(('192.168.0.51',8080)); s.sendall(b'SHOT\n')
def rex(n):
    b=b''
    while len(b)<n:
        c=s.recv(n-len(b));
        if not c: break
        b+=c
    return b
n=struct.unpack('>I',rex(4))[0]; open('after.png','wb').write(rex(n)); s.close(); print('saved after.png',n)"
```

---

## 4. logcat으로 루프 흐름 보기 (선택)
Android Studio Logcat에서 태그 필터 `a11cu`:
```
[턴 1] list_apps {...}
[턴 2] open_app {package_name=com.android.settings}
[완료] ...
```
(주의: runAgent 완료줄 태그가 `allcu`로 오타났으면 그 줄만 필터에 안 잡힘 → 태그 `a11cu`로 통일 권장.)

---

## 5. 자주 겪는 문제
| 증상 | 원인/해결 |
|---|---|
| ConnectionRefused | 앱 안 떴거나 접근성 OFF → 앱 실행+접근성 ON |
| ConnectionReset | 빌드 실패로 옛 APK → Android Studio 빌드 에러 확인 |
| 45초쯤 타임아웃 | 그냥 시간 부족 → `settimeout` 늘리기(첫 호출 느릴 수 있음) |
| 좌표가 좌상단만 눌림 | 정규화(0~1000) 환산 누락 → dispatch의 pxX/pxY 확인 |
| 한글 결과 깨짐 | 콘솔 표시 문제 → §3으로 파일 저장해서 보기 |
| 탭이 무시됨 | 접근성 XML `canPerformGestures` 확인 |

---

## 6. (참고) 옛 PC 경로 — 비교/베이스라인용
케이블(ADB)로 돌리던 원본은 여전히 동작:
```powershell
py live/main.py "설정 앱을 열어"          # ADB 연결 필요, .env에 GEMINI_API_KEY
```
온디바이스와 기능 동등(같은 모델·액션). 지금 주 경로는 **폰 단독(RUN)**.
