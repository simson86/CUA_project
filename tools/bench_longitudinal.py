"""판단 ② — 에이전트가 **스스로 쌓은** 기억이 시간이 갈수록 도움이 되나.

지금까지의 측정(판단 ①)은 전부 **사람이 써 준 기억**을 넣고 쟀다. 이것은 리플렉터가
스스로 만들고 reconciliation 이 승격시킨 기억을 재는 것이다 — **자동 루프 전체**가 대상이다.

## 설계 — A/B 를 시간축 위에 놓는다

순수 종단(기억 켜고 계속 돌리며 턴이 주는지 보기)은 **앱 자체 학습과 구분이 안 된다**
(무신사 최근 검색어, 멜론 최근 재생 — 1차 측정에서 실측된 교란이다). 그래서 대조군을
유지하되 **B 의 기억은 지우지 않고 누적**시킨다:

    배치 1:  A B A B …      A = 주입 없음 (기준선)
    배치 2:  B A B A …      B = 그 시점까지 쌓인 기억을 주입
    배치 3:  A B A B …      ← 배치마다 순서를 뒤집는다(아래)

`B − A` 를 보면 앱 학습이 **양쪽에서 상쇄**된다. 보는 것은 그 차이가 **배치가 갈수록
벌어지나** 이다.

⚠️ **A 실행에서도 리플렉터는 돈다.** 실제 사용에서는 모든 실행이 기억을 만든다. A 는
*"기억 없이 했을 때의 성능"* 을 재는 것이지 *"기억을 안 만드는 조건"* 이 아니다.

⚠️ **배치마다 A/B 순서를 뒤집는다.** 항상 A→B 면 B 가 **A 가 데워 놓은 앱**을 물려받아
유리해진다(앱이 최근 검색어 등을 학습하므로). 1·2차의 "번갈아" 와 같은 논리를 배치
단위로 적용한 것이다.

## 판정 기준 — 숫자를 보기 전에 정했다. 결과를 보고 바꾸지 않는다.

    주 판정   마지막 3배치에서 B 평균이 A 평균보다 **20% 이상** 낮으면
              → "스스로 쌓은 기억이 도움이 된다"
    추이      (첫 3배치의 B−A) vs (마지막 3배치의 B−A) — 벌어지면 "쌓일수록 좋아진다"

20% 는 1·2차·템플릿 측정에서 쓴 것과 같은 문턱이다.

⚠️ **효과가 금방 포화된다는 것을 알고 있다.** 예산이 앱당 2줄이라, 좋은 기억 2개가
`ACTIVE` 가 되면 그 뒤로는 주입 내용이 더 안 바뀐다. 곡선은 *오르다 평탄* 해질 것이다.

## 쓰는 법
    py tools/bench_longitudinal.py explore     탐색 라운드 (리플렉터·주입 끄고 1회씩)
    py tools/bench_longitudinal.py batch       배치 1회 (6과제 × A/B = 12회)
    py tools/bench_longitudinal.py report      집계

`batch` 는 **몇 번이든 이어서** 돌릴 수 있다. 기억이 누적되는 설계이고 우리 시스템에는
시간 기반 규칙이 없으므로(감쇠·만료를 Unit 8 에서 뺐다) **배치 사이 간격은 상관없다.**
"""

import json
import os
import sys
import time
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench_memory import MODEL, THINKING, one_run, sock  # noqa: E402

RESULTS = os.path.join(os.path.dirname(__file__), "..", "docs", "bench",
                       "longitudinal.jsonl")

MEGA = "net.megastudy.smartplay.main"
MELON = "com.iloen.melon"

# ── 과제 (사람이 폰에서 전제를 확인함, 2026-09-21) ───────────────────
#  설계 의도:
#   · L1·L2 는 **경로 앞부분을 공유**한다 → 같은 APP_FACT 가 다른 과제에서 재관측되어
#     승격된다. 같은 과제를 반복하는 것과 다르다 — 5c 시험이 그 반복이었고 그래서
#     승격이 인위적으로 쉬웠다.
#   · **M2 는 다른 갈래를 시험한다.** 리플렉터 프롬프트가 규정하는 쓸모 있는 기억은 둘인데
#     (DIRECTION = 경로 · SURPRISE = 앱이 관례를 어김), 지금까지의 측정은 **전부 앞엣것**
#     이었다. M2 는 뒤엣것이다 — 탐색 5회 중 3회가 같은 함정에 빠졌다: 검색 결과의
#     `수강평` 탭에서 정렬 드롭다운을 누르면 **검색이 리셋되고** 그 뒤 탭 사이를 10턴 넘게
#     왕복한다. 정렬 드롭다운이 정렬만 바꾸지 않는다는 건 사전지식으로 못 피하고 화면으로도
#     안 보인다. ⚠️ 그래서 M2 는 분산이 크다(8·20·20·10·20). 한 과제의 12턴 폭은 배치
#     평균을 ±2턴 흔든다 — **M2 만 따로 집계해서 볼 것**(cmd_report 의 과제별 표).
#     5회 중 1회는 앱을 안 쓰고 브라우저로 샜다. 그 실행의 기억은 브라우저 패키지에 붙어
#     메가스터디에서 주입되지 않는다.
#   · M3·L3 는 **다른 갈래**다 → 앱당 기억이 3개 이상 쌓여 **예산(2줄)을 다투게** 된다.
#     Unit 7 의 랭킹이 처음으로 실제 일을 하는 조건이다.
#   · M3·L3 는 화면에 **개인 데이터**가 뜬다 → §3 의 C층(모델 판정)이 실전에서 그것을
#     거르는지 볼 수 있다. 지금까지 "그럴 것이다" 로만 적어둔 것이다.
#
#  ⚠️ `personal=True` 인 과제는 **모델의 답변을 결과 파일에 저장하지 않는다.**
#     docs/bench/*.jsonl 은 git 에 커밋되는데, 거기에 사용자의 목표 대학이나 하이라이트한
#     가사가 그대로 들어가면 안 된다. 측정에 필요한 것은 턴 수뿐이다.
TASKS = [
    {"id": "M1", "pkg": MEGA, "personal": False,
     "goal": "메가스터디에서 김기훈 선생님의 커리큘럼을 확인해줘"},
    {"id": "M2", "pkg": MEGA, "personal": False,
     "goal": "메가스터디에서 민동휘 강사의 가장 최신 수강평이 뭔지 알려줘"},
    {"id": "M3", "pkg": MEGA, "personal": True,
     "goal": "메가스터디에서 내 목표 대학 3지망이 뭔지 알려줘"},
    {"id": "L1", "pkg": MELON, "personal": False,
     "goal": "멜론에서 1976년에 가장 인기있던 노래가 뭐야"},
    {"id": "L2", "pkg": MELON, "personal": False,
     "goal": "멜론에서 1990년대 차트 1위가 뭐야"},
    {"id": "L3", "pkg": MELON, "personal": True,
     "goal": "멜론에서 내가 하이라이트한 가사 중 가장 최근 것 보여줘"},
]


# ── 스위치 ───────────────────────────────────────────────────────────
def reflector(on):
    """리플렉터는 **측정 내내 켜 둔다**. 끄는 것은 탐색 라운드뿐이다."""
    r = sock(f"REFLECTOR {'on' if on else 'off'}")
    if not r.startswith("OK"):
        raise RuntimeError(f"REFLECTOR 실패: {r}")


def inject(on):
    r = sock(f"MEMINJECT {'on' if on else 'off'}")
    if not r.startswith("OK"):
        raise RuntimeError(f"MEMINJECT 실패: {r}")


def mem_stats():
    return json.loads(sock("MEMSTATS"))


def run_one(task, cond, batch):
    """cond: 'A' = 주입 없음 · 'B' = 주입 있음."""
    inject(cond == "B")
    # ★ keep_memory=True — **기억을 지우지 않는다.** 누적이 곧 실험 조건이다.
    row = one_run(task, cond, keep_memory=True)
    bad = list(row["excluded"] or [])

    # 주입이 실제로 꺼졌나/켜졌나를 **episode.note 로** 확인한다. 스위치를 믿지 않는다 —
    # 앱이 재시작되면 조용히 켜질 수 있고, 그런 오염은 턴 수만 봐서는 안 보인다.
    notes = sum(1 for e in row["episodes"] if e.get("note"))
    if cond == "A" and notes > 0:
        bad.append(f"A 인데 note 가 {notes}턴에 붙었다 — 주입이 안 꺼졌다")

    row["batch"] = batch
    row["notes"] = notes
    row["excluded"] = bad or None
    row["memory"] = mem_stats()
    if task["personal"]:
        row["reply"] = "(개인 데이터 — 저장 안 함)"
    row.pop("episodes", None)      # 개인 데이터가 intent 에 섞일 수 있다
    return row


def append(row):
    os.makedirs(os.path.dirname(RESULTS), exist_ok=True)
    with open(RESULTS, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def show(row):
    mark = "✗ " + "; ".join(row["excluded"]) if row["excluded"] else "✓"
    m = row["memory"]
    print(f"  [{row['cond']}] {row['task']:3} {str(row['turns']):>3}턴  "
          f"{row['outcome']:8} {row['wall']:6.1f}s  note{row['notes']:>2}  "
          f"A{m.get('ACTIVE',0)}/P{m.get('PENDING',0)}/R{m.get('RETIRED',0)}  {mark}")


def next_batch():
    if not os.path.exists(RESULTS):
        return 1
    rows = [json.loads(l) for l in open(RESULTS, encoding="utf-8")]
    return max((r.get("batch", 0) for r in rows), default=0) + 1


# ── 모드 ─────────────────────────────────────────────────────────────
def cmd_explore():
    """과제가 **잴 수 있는 것인지** 본다. 데이터는 버린다.

    판정(1·2차 실측을 기준선으로): **10턴 이상 + 헤맨 흔적**이면 통과, **6턴 이하**면
    바닥 효과로 보고 교체한다. mega 17턴(낭비 9~10) 은 쟀고, storage 7.4턴·melon 4.2턴은
    줄일 것이 없어 못 쟀다.
    """
    print("탐색 라운드 — 리플렉터·주입 모두 끄고 1회씩. 데이터는 버린다.\n")
    reflector(False)
    inject(False)
    for t in TASKS:
        row = one_run(t, "explore", keep_memory=True)
        print(f"  {t['id']:3} {str(row['turns']):>3}턴  {row['outcome']:8} "
              f"{row['wall']:6.1f}s")
        for e in row["episodes"]:
            print(f"        턴{e['turn']:>2} {str(e['action']):14} {e['intent']}")
        print()
    print("판정: 10턴 이상 + 헤맨 흔적(go_back·같은 화면 반복) → 통과 / 6턴 이하 → 교체")


def cmd_batch():
    b = next_batch()
    # 배치마다 A/B 순서를 뒤집는다 — 항상 A→B 면 B 가 데워진 앱을 물려받는다.
    order = ("A", "B") if b % 2 == 1 else ("B", "A")
    print(f"배치 {b} — {len(TASKS)}과제 × {order} = {len(TASKS)*2}회  "
          f"({MODEL}/{THINKING})\n")
    reflector(True)
    t0 = time.time()
    for t in TASKS:
        for cond in order:
            row = run_one(t, cond, b)
            append(row)
            show(row)
    print(f"\n배치 {b} 끝 — {(time.time()-t0)/60:.0f}분 → {os.path.relpath(RESULTS)}")


def cmd_report():
    rows = [json.loads(l) for l in open(RESULTS, encoding="utf-8")]

    def dropped(r):
        if r["excluded"]:
            return "; ".join(r["excluded"])
        if r["outcome"] == "error":
            return f"실행 오류 — {str(r.get('reply'))[:50]}"
        if r["turns"] is None:
            return "턴 수 없음"
        return None

    ok = [r for r in rows if dropped(r) is None]
    print(f"총 {len(rows)}행, 집계 {len(ok)}행 (제외 {len(rows)-len(ok)})\n")
    for r in rows:
        if dropped(r):
            print(f"  제외: 배치{r.get('batch')} [{r['cond']}] {r['task']} — {dropped(r)}")

    batches = sorted({r["batch"] for r in ok})
    print(f"\n{'배치':>4} {'A':>7} {'B':>7} {'B−A':>8}   기억(A/P/R)")
    diffs = {}
    for b in batches:
        a = [r["turns"] for r in ok if r["batch"] == b and r["cond"] == "A"]
        v = [r["turns"] for r in ok if r["batch"] == b and r["cond"] == "B"]
        if not a or not v:
            continue
        ma, mv = sum(a)/len(a), sum(v)/len(v)
        diffs[b] = mv - ma
        last = [r for r in ok if r["batch"] == b][-1]["memory"]
        print(f"{b:>4} {ma:>7.1f} {mv:>7.1f} {mv-ma:>+8.1f}   "
              f"{last.get('ACTIVE',0)}/{last.get('PENDING',0)}/{last.get('RETIRED',0)}")

    # ── 과제별 ────────────────────────────────────────────────────────
    #  전체 평균만 내면 안 된다. 2차 측정의 교훈이 정확히 이거였다 — 세 과제 중 하나만
    #  −48.8% 이고 둘은 0% 였는데, 합쳐 놓으니 "약간 도움이 된다"로 뭉개졌다. 게다가
    #  여기서는 M2 의 분산이 8~20 이라(탐색 실측) 한 과제가 배치 평균을 ±2턴 흔든다.
    print(f"\n{'과제':>4} {'A':>7} {'B':>7} {'B-A':>8} {'%':>7}   A완주  B완주")
    for t in TASKS:
        a = [r["turns"] for r in ok if r["task"] == t["id"] and r["cond"] == "A"]
        v = [r["turns"] for r in ok if r["task"] == t["id"] and r["cond"] == "B"]
        if not a or not v:
            continue
        ma, mv = sum(a) / len(a), sum(v) / len(v)
        da = sum(1 for r in ok if r["task"] == t["id"] and r["cond"] == "A"
                 and r["outcome"] == "success")
        dv = sum(1 for r in ok if r["task"] == t["id"] and r["cond"] == "B"
                 and r["outcome"] == "success")
        print(f"{t['id']:>4} {ma:>7.1f} {mv:>7.1f} {mv-ma:>+8.1f} "
              f"{(mv-ma)/ma*100:>+6.1f}%   {da}/{len(a):<4} {dv}/{len(v)}")
        print(f"       A {a}\n       B {v}")

    print("\n── 판정 (기준은 측정 전에 정했다) ──")
    if len(batches) >= 3:
        tail = batches[-3:]
        a = [r["turns"] for r in ok if r["batch"] in tail and r["cond"] == "A"]
        v = [r["turns"] for r in ok if r["batch"] in tail and r["cond"] == "B"]
        if a and v:
            ma, mv = sum(a)/len(a), sum(v)/len(v)
            d = (mv - ma) / ma * 100
            print(f"  주 판정  마지막 3배치 {tail}: A {ma:.1f} · B {mv:.1f} → {d:+.1f}%")
            print(f"           → {'도움이 된다' if d <= -20 else '문턱 미달'}")
    if len(batches) >= 6:
        head, tail = batches[:3], batches[-3:]
        dh = sum(diffs[b] for b in head) / 3
        dt = sum(diffs[b] for b in tail) / 3
        print(f"  추이     첫 3배치 B−A {dh:+.1f} → 마지막 3배치 {dt:+.1f}")
        print(f"           → {'쌓일수록 좋아진다' if dt < dh else '추이 없음'}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "report"
    {"explore": cmd_explore, "batch": cmd_batch, "report": cmd_report}[cmd]()
