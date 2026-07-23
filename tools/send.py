"""폰 단독 온디바이스 CU 실행 편의 도구 (PC → 폰 소켓 8080).

앱(a11service)의 소켓 서버로 명령을 보내는 클라이언트. 앱 소스가 아니라 PC 쪽 실행 도구.

사용:
  py tools/send.py "설정 앱을 열어"          # RUN: 폰이 목표를 자율 수행(캡처→Gemini→제스처 반복)
  py tools/send.py --shot                     # 현재 폰 화면을 after.png 로 저장
  py tools/send.py "쿠팡 슬리퍼 검색" --ip 192.168.0.99 --timeout 240

기본 IP=192.168.0.51 (DHCP라 바뀌면 --ip 로 지정). 파이썬은 반드시 py 로 실행.
⚠️ 결제/구매/전송/삭제는 시키지 말 것 — 현재 안전확인이 자동승인 상태(hybrid-todo-safety-confirm.md).
"""
import argparse
import socket
import struct
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")  # 윈도우 콘솔 한글 깨짐 방지
except Exception:
    pass

DEFAULT_IP = "192.168.0.51"
PORT = 8080


def _connect(ip: str, timeout: float) -> socket.socket:
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((ip, PORT))
    except Exception as e:
        raise SystemExit(
            f"[연결 실패] {ip}:{PORT} — {e}\n"
            "  · 폰에 앱이 떠 있고 설정>접근성에서 서비스가 켜져 있나?\n"
            "  · 폰·PC 같은 Wi-Fi인가? 폰 IP가 맞나(--ip 로 지정)?"
        )
    return s


def _recv_line(s: socket.socket) -> str:
    """개행(\\n)까지 텍스트 응답을 모아 UTF-8로 디코드."""
    buf = b""
    while True:
        d = s.recv(4000)
        if not d:
            break
        buf += d
        if buf.endswith(b"\n"):
            break
    return buf.decode("utf-8", "replace").rstrip("\n")


def _recv_exact(s: socket.socket, n: int) -> bytes:
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            break
        b += c
    return b


def do_shot(ip: str, timeout: float, out: str):
    s = _connect(ip, timeout)
    s.sendall(b"SHOT\n")
    n = struct.unpack(">I", _recv_exact(s, 4))[0]
    png = _recv_exact(s, n)
    s.close()
    with open(out, "wb") as f:
        f.write(png)
    print(f"[SHOT] {n} bytes → {out}")


def do_run(ip: str, timeout: float, task: str):
    """RUN <task> 를 소켓으로 전송 → 폰이 목표를 자율 수행하고 결과 반환."""
    s = _connect(ip, timeout)
    s.sendall(f"RUN {task}\n".encode("utf-8"))
    print(f"[RUN] 전송: {task}  (폰이 처리 중... 최대 {timeout:.0f}s 대기)")
    try:
        print("[결과]", _recv_line(s))
    except socket.timeout:
        raise SystemExit(
            f"[타임아웃] {timeout:.0f}s 안에 응답 없음. 복잡한 작업이면 --timeout 을 늘려보세요."
        )
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description="폰 단독 온디바이스 CU 실행 도구")
    ap.add_argument("task", nargs="*", help='목표 문장 (예: "설정 앱을 열어")')
    ap.add_argument("--ip", default=DEFAULT_IP, help=f"폰 IP (기본 {DEFAULT_IP})")
    ap.add_argument("--timeout", type=float, default=180.0, help="응답 대기 초 (기본 180)")
    ap.add_argument("--shot", action="store_true", help="현재 폰 화면만 캡처(after.png)")
    ap.add_argument("--out", default="after.png", help="--shot 저장 경로")
    args = ap.parse_args()

    if args.shot:
        do_shot(args.ip, min(args.timeout, 20), args.out)
        return

    task = " ".join(args.task).strip()
    if not task:
        raise SystemExit('목표를 주세요. 예: py tools/send.py "설정 앱을 열어"  (또는 --shot)')

    do_run(args.ip, args.timeout, task)


if __name__ == "__main__":
    main()
