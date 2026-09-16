"""기억 시스템 A/B 측정 러너 — 판단 ①("사람이 써 준 기억을 주입하면 효과가 있나").

설계와 판정 기준은 저장소 루트 MEMORY.md 의 'Unit 4 — 측정 계획' 참조.
**판정 기준은 숫자를 보기 전에 정했다. 결과를 보고 바꾸지 않는다.**

쓰는 법
    py tools/bench_memory.py explore          탐색 라운드(기억 없이 2회씩, 데이터는 버림)
    py tools/bench_memory.py measure          본 측정 30회 (A/B 번갈아)
    py tools/bench_memory.py report           쌓인 결과를 집계

왜 USB 터널(`adb forward`)이 아니라 Wi-Fi 인가 — **USB 터널을 먼저 시도했다가 접었다.**
짧은 명령은 멀쩡했지만 실제로 쓰기 시작하자 **adb 의 USB 채널 자체가 두 번 먹통이 됐다**
(`adb devices` 는 되는데 `adb shell` 이 무응답, 서버 재시작 필요, 한 번은 재인증까지).
같은 세션에서 Wi-Fi 소켓은 203초·268초짜리 실행을 포함해 한 번도 문제를 안 냈다.

Wi-Fi 의 약점은 실재한다 — IP 가 바뀌거나(실제로 겪었다) 네트워크가 달라지면 끊긴다.
그래서 IP 를 박아두지 않고 매번 adb 로 읽는다. 다만 그건 **시끄럽게 실패**하는 종류라
(연결 거부) adb 를 망가뜨리는 것보다 낫다.

전제 둘:
  · PC 와 폰이 **같은 네트워크**에 있어야 한다
  · **폰에 인터넷**이 있어야 한다 — 에이전트가 매 턴 Gemini API 를 직접 부른다
  · 40분 배치 동안 **Wi-Fi 를 옮기지 말 것**. 충전기는 꽂아 둘 것(절전 모드가 켜지면
    폰 동작이 달라진다)
"""

import json
import os
import socket
import subprocess
import sys
import time
from datetime import datetime

if hasattr(sys.stdout, "reconfigure"):
    # line_buffering — 40분짜리 배치의 진행이 버퍼에 갇히면 안 된다. 중간에 멈췄는지
    # 잘 돌고 있는지 알 방법이 없어진다.
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)

# ── 설계 (MEMORY.md 와 일치해야 한다) ────────────────────────────────
MODEL = "gemini-3.5-flash"
THINKING = "low"
REPEATS = 5                      # 조건당 반복. 과제 3 × 조건 2 × 5 = 30회
EXPLORE_REPEATS = 2

PORT = 8080
RUN_TIMEOUT = 900                # 20턴이 다 돌아도 남을 만큼
SETTLE_S = 3                     # force-stop 뒤 화면이 정리될 시간

RESULTS = os.path.join(os.path.dirname(__file__), "..", "docs", "bench",
                       "memory_ab.jsonl")

# 조건 B 의 기억 — 2026-09-16 탐색 라운드(6회)에서 관측한 것만 적었다. 지어낸 문장은 없다.
#
# ★ 셋 다 **'앱 UI 지도'** 급이다(앱 × 화면 수만큼 필요한, 가장 낮은 급). 탐색에서 낭비가
#   거기서 났기 때문인데, 결과적으로 가장 방어하기 어려운 표본이다. 그래서 이 측정의 결과는
#   "기억이 효과가 있다/없다"가 아니라 **"앱 UI 지도 급 지식이 값어치를 하는가"** 로 읽어야
#   한다. 자세한 것은 MEMORY.md 의 '지식의 급' 절.
#
# ★ 전부 **서술문**이다 — "이렇게 해라"가 아니라 "이렇게 생겼다". 첫 초안의 멜론 문장이
#   "검색하지 말고 최근 목록을 눌러라"였는데, 요청한 곡이 거기 없으면 **엉뚱한 곡을 튼다**.
#   확인 단계를 건너뛰라고 지시하는 기억은 위험하다. 판단은 모델에게 남긴다.
TASKS = [
    {
        "id": "storage",
        "goal": "저장공간이 얼마나 남았는지 알려줘",
        "pkg": "com.android.settings",
        "memories": [{
            "kind": "APP_FACT",
            # ★ 한 과제가 네 패키지를 넘나든다. episode.pkg 를 보고 채운 목록이다 —
            #   추측하면 빠뜨린다(설정 '검색' UI 가 별도 패키지인 걸 재측정해서야 알았다).
            "pkg": "com.android.settings,com.android.settings.intelligence,"
                   "com.samsung.android.lool,com.sec.android.app.myfiles",
            "text": "Storage usage is under Device Care, near the bottom of the "
                    "Settings list (Device Care -> Storage).",
        }],
    },
    {
        "id": "meta",
        "goal": "메타클럽에서 내 가장 최근 세탁이 어떤 세탁기였는지 알려줘",
        "pkg": "com.fingerverse.metapoint",
        "memories": [{
            "kind": "APP_FACT", "pkg": "com.fingerverse.metapoint",
            "text": "'최근 이용내역' is on the home screen. '세탁하기' opens a "
                    "machine-selection popup, not the usage history.",
        }],
    },
    {
        "id": "mega",
        "goal": "메가스터디에서 김기훈 선생님의 커리큘럼을 확인해줘",
        "pkg": "net.megastudy.smartplay.main",
        "memories": [{
            "kind": "APP_FACT", "pkg": "net.megastudy.smartplay.main",
            "text": "A teacher's curriculum is reached from the menu at the top left: "
                    "megateacher list -> subject -> teacher -> curriculum tab. Search "
                    "results show lectures and profiles, not the curriculum.",
        }],
    },
]


# ── 하부 ─────────────────────────────────────────────────────────────
def _find_adb():
    """PATH 에만 기대지 않는다 — 40분짜리 배치가 PATH 설정 하나로 통째로 죽으면 안 된다.
    ANDROID_HOME / 표준 SDK 위치 / PATH 순으로 찾는다."""
    import shutil
    cands = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            cands.append(os.path.join(os.environ[env], "platform-tools", "adb"))
    cands.append(os.path.expanduser("~/AppData/Local/Android/Sdk/platform-tools/adb"))
    for c in cands:
        for ext in (".exe", ""):
            if os.path.isfile(c + ext):
                return c + ext
    found = shutil.which("adb")
    if found:
        return found
    raise RuntimeError("adb 를 찾을 수 없다. ANDROID_HOME 을 지정하거나 PATH 에 넣을 것.")


ADB = _find_adb()


def adb(*args, check=True):
    r = subprocess.run([ADB, *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} 실패: {r.stderr.strip()}")
    return r.stdout.strip()


_ip_cache = [None]


def phone_ip(refresh=False):
    """폰 IP 를 adb 로 읽는다. 박아두면 네트워크가 바뀔 때 낡는다(실제로 겪었다).

    매번 adb 를 부르면 30회 배치에서 수백 번이 되므로 캐시하고, 연결에 실패했을 때만
    다시 읽는다. adb shell 이 빈 문자열을 돌려주는 일이 있어 재시도한다."""
    if _ip_cache[0] and not refresh:
        return _ip_cache[0]
    if os.environ.get("PHONE_IP"):
        _ip_cache[0] = os.environ["PHONE_IP"]
        return _ip_cache[0]
    for _ in range(4):
        out = adb("shell", "ip", "-f", "inet", "addr", "show", "wlan0", check=False)
        for tok in out.split():
            if tok.count(".") == 3 and tok[0].isdigit():
                _ip_cache[0] = tok.split("/")[0]
                return _ip_cache[0]
        time.sleep(0.6)
    raise RuntimeError("폰 IP 를 못 찾음. PHONE_IP 환경변수로 직접 줄 수 있다.")


def sock(cmd, timeout=30):
    """명령 한 줄을 보내고 응답 한 줄을 받는다.

    연결이 안 되면 IP 를 다시 읽고 한 번 더 시도한다 — 배치 도중 DHCP 갱신으로
    주소가 바뀌었을 수 있다."""
    try:
        return _sock_once(cmd, timeout, phone_ip())
    except OSError:
        return _sock_once(cmd, timeout, phone_ip(refresh=True))


def _sock_once(cmd, timeout, host):
    s = socket.create_connection((host, PORT), timeout=15)
    s.settimeout(timeout)
    try:
        s.sendall((cmd + "\n").encode("utf-8"))
        buf = b""
        while b"\n" not in buf:
            chunk = s.recv(8192)
            if not chunk:
                break
            buf += chunk
        return buf.decode("utf-8", "replace").strip()
    finally:
        s.close()


def set_memory(memories):
    """조건을 세운다. **매 실행마다** 부른다 — 그래야 A/B 를 번갈아 돌릴 수 있다.

    MEMADD 가 ERR 를 돌려주면 즉시 멈춘다. 조건 B 에 기억이 덜 들어간 채로 측정이
    돌아가면 결과가 '기억은 효과가 없다'로 조용히 틀린다."""
    sock("MEMCLEAR")
    for m in memories:
        r = sock("MEMADD " + json.dumps(m, ensure_ascii=False))
        if not r.startswith("OK"):
            raise RuntimeError(f"MEMADD 거절됨: {r}\n  보낸 것: {m}")
    got = sock("MEMCOUNT")
    n = int(got.split()[1]) if got.startswith("OK") else -1
    if n != len(memories):
        raise RuntimeError(f"기억 건수가 안 맞는다: 기대 {len(memories)}, 실제 {got}")


def reset(pkg):
    """앱과 화면을 같은 출발선으로. 이게 없으면 '설정이 이미 열려 있어 0턴에 완료' 같은
    실행이 섞인다(실제로 겪었다)."""
    adb("shell", "am", "force-stop", pkg)
    sock("HOME")
    time.sleep(SETTLE_S)


def one_run(task, cond, memories=None):
    """실행 1회. 돌려주는 dict 가 그대로 결과 파일 한 줄이 된다.

    [memories] 를 주면 그걸 쓰고, 안 주면 종전대로 cond=="B" 일 때만 task 의 것을 쓴다.
    조건이 둘보다 많은 실험(tools/bench_template.py)이 이 함수를 그대로 쓰기 위한 것 —
    검증(DUMP 대조)이 여기 들어 있어서 복사해 가면 그 검증이 갈라진다."""
    if memories is None:
        memories = task["memories"] if cond == "B" else []
    reset(task["pkg"])
    set_memory(memories)

    t0 = time.time()
    reply = sock("RUN " + task["goal"], timeout=RUN_TIMEOUT)
    wall = round(time.time() - t0, 1)

    dump = json.loads(sock("DUMP", timeout=60))

    # 이 실행을 믿어도 되는지 — 어긋나면 집계에서 뺀다(MEMORY.md 의 제외 조건).
    bad = []
    if dump.get("model") != MODEL or dump.get("thinking") != THINKING:
        bad.append(f"설정 불일치 {dump.get('model')}/{dump.get('thinking')}")
    if dump.get("memoryReadFailed"):
        bad.append("기억 읽기 실패")
    if dump.get("memoryCount") != len(memories):
        bad.append(f"기억 {dump.get('memoryCount')}건 (기대 {len(memories)})")
    if dump.get("goal") != task["goal"]:
        bad.append("목표 문자열 불일치")

    return {
        "ts": datetime.now().isoformat(timespec="seconds"),
        "task": task["id"], "cond": cond,
        "outcome": dump.get("outcome"), "turns": dump.get("turnsUsed"),
        "wall": wall, "reply": reply[:200],
        "model": dump.get("model"), "thinking": dump.get("thinking"),
        "memoryCount": dump.get("memoryCount"),
        "memoryReadFailed": dump.get("memoryReadFailed"),
        "runId": dump.get("runId"),
        "episodes": [{k: e[k] for k in ("turn", "pkg", "action", "intent", "note")}
                     for e in dump.get("episodes", [])],
        "excluded": bad or None,
    }


def append(row):
    """한 줄 끝날 때마다 바로 쓴다 — 17회째에 죽어도 앞의 16회를 잃지 않는다."""
    os.makedirs(os.path.dirname(RESULTS), exist_ok=True)
    with open(RESULTS, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def show(row):
    mark = "✗ " + "; ".join(row["excluded"]) if row["excluded"] else "✓"
    print(f"  [{row['cond']}] {row['task']:9} {str(row['turns']):>3}턴  "
          f"{row['outcome']:8} {row['wall']:6.1f}s  {mark}")


# ── 모드 ─────────────────────────────────────────────────────────────
def cmd_explore():
    """기억 없이 돌려 '어디서 턴을 낭비하는지' 본다. 데이터는 본 측정에 안 쓴다 —
    같은 실행을 보고 기억을 쓴 뒤 그 실행을 조건 A 로 쓰면 시험지를 보고 답을 쓴 셈이다."""
    print(f"탐색 라운드 — 과제 {len(TASKS)} × {EXPLORE_REPEATS}회, 기억 없음\n")
    for task in TASKS:
        for i in range(EXPLORE_REPEATS):
            row = one_run(task, "A")
            show(row)
            for e in row["episodes"]:
                print(f"        턴{e['turn']:>2} {str(e['action']):12} "
                      f"{e['pkg']}\n             {e['intent']}")
            print()


def cmd_measure():
    missing = [t["id"] for t in TASKS if not t["memories"]]
    if missing:
        sys.exit(f"조건 B 의 기억이 비어 있다: {missing}\n"
                 f"탐색 라운드를 먼저 돌리고 TASKS[*]['memories'] 를 채울 것.")
    total = len(TASKS) * REPEATS * 2
    print(f"본 측정 — {total}회 (과제 {len(TASKS)} × 조건 2 × {REPEATS}), A/B 번갈아\n")
    done = 0
    for task in TASKS:
        print(f"[{task['id']}] {task['goal']}")
        for i in range(REPEATS):
            for cond in ("A", "B"):        # 번갈아 — 시간대·앱 학습이 조건에 엮이지 않게
                row = one_run(task, cond)
                append(row)
                show(row)
                done += 1
        print()
    print(f"끝. {done}회 → {os.path.relpath(RESULTS)}")


def cmd_report():
    rows = [json.loads(l) for l in open(RESULTS, encoding="utf-8")]
    ok = [r for r in rows if not r["excluded"] and r["turns"] is not None]
    print(f"총 {len(rows)}행, 집계 대상 {len(ok)}행 (제외 {len(rows)-len(ok)})\n")
    for r in rows:
        if r["excluded"]:
            print(f"  제외: {r['task']}/{r['cond']} — {'; '.join(r['excluded'])}")

    print(f"\n{'과제':10} {'A 평균':>8} {'B 평균':>8} {'변화':>8}  {'A 완주':>7} {'B 완주':>7}")
    agree = 0
    for task in TASKS:
        a = [r for r in ok if r["task"] == task["id"] and r["cond"] == "A"]
        b = [r for r in ok if r["task"] == task["id"] and r["cond"] == "B"]
        if not a or not b:
            print(f"{task['id']:10} {'표본 부족':>8}")
            continue
        am = sum(r["turns"] for r in a) / len(a)
        bm = sum(r["turns"] for r in b) / len(b)
        delta = (bm - am) / am * 100
        asucc = sum(r["outcome"] == "success" for r in a)
        bsucc = sum(r["outcome"] == "success" for r in b)
        if delta <= -20:
            agree += 1
        print(f"{task['id']:10} {am:8.1f} {bm:8.1f} {delta:+7.1f}%  "
              f"{asucc:3}/{len(a):<3} {bsucc:3}/{len(b):<3}")

    # 판정 기준 — MEMORY.md 에 미리 적어둔 그대로. 여기서 바꾸지 말 것.
    print(f"\n20% 이상 줄어든 과제: {agree}/{len(TASKS)}")
    print("판정:", "효과 있다" if agree >= 2 else "효과 없다 (또는 표본 부족)")
    print("\n⚠️ 턴 수만 본 값이다. 정확도는 재지 않았다 — 모델이 답을 틀려도 success 다.")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    {"explore": cmd_explore, "measure": cmd_measure, "report": cmd_report}.get(
        mode, lambda: sys.exit(__doc__))()
