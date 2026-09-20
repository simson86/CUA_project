"""템플릿 형식 A/B/C/D — "리플렉터가 쓸 수 있는 모양"이 성능을 깎는가.

Unit 5b(리플렉터)를 만들기 전에 재는 실험이다. 리플렉터의 출력은 자유 문장이 아니라
`trigger → consequence` 템플릿인데(설계 §2 · §3 집행 A층), **그 형식 자체가 성능을
깎는다면 리플렉터는 태생적으로 약한 문장만 만든다.** 다 만든 뒤에는 "추출이 나쁜 건지
형식이 나쁜 건지" 구분할 수 없다 — 1차 측정에서 세 과제가 각각 다른 이유로 오염돼
아무 결론도 못 실었던 것과 같은 함정이다.

과제는 mega 하나다. 2차 측정에서 **−48.8%** 로 유일하게 큰 효과가 나온 과제이고
(A 18,18,18,17,15 / B 8,8,9,8,11 — 겹치는 구간이 없다), 효과가 큰 데서 재야 형식 비용이
보인다.

조건 넷 — 내용은 같고 **형식과 성분만** 다르다:
    A  기억 없음 (기준선 재측정)
    B  원문 그대로          자유 문장 1건   ← 2차의 그 문장
    C  같은 내용을 템플릿꼴  2건(ⓐ 경로 + ⓑ 부정 사실)
    D  ⓑ만                 1건

두 가지를 동시에 답한다:
    C vs B   형식 비용        C 가 20% 이상 많으면 "템플릿이 깎는다"
    D vs C   경로(ⓐ)의 기여   D 가 20% 이내면 "부정 사실 하나로 −49% 가 설명된다"
                             → RECIPE(Unit 9) 가 필요 없다는 쪽

**판정 기준은 숫자를 보기 전에 정했다(MEMORY.md 와 같은 20% 문턱). 결과를 보고 바꾸지 않는다.**

쓰는 법
    py tools/bench_template.py measure     20회 (4조건 × 5, A B C D 번갈아)
    py tools/bench_template.py report      집계

준비물은 MEMORY.md 의 '측정 전 준비물' 과 같다. 특히 **모델이 3.5-flash/low 여야 한다** —
2차가 그 설정이었고, 다르면 비교 자체가 무의미해진다.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# 공통 배선(소켓·adb·DUMP 검증)은 그대로 쓴다. bench_memory.py 는 1·2차 결과를 만든
# 하네스라 건드리지 않는다 — one_run 에 memories 를 넘길 수 있게 한 것이 유일한 변경이다.
from bench_memory import MODEL, THINKING, one_run, sock  # noqa: E402

REPEATS = 5
RESULTS = os.path.join(os.path.dirname(__file__), "..", "docs", "bench",
                       "template_form.jsonl")

GOAL = "메가스터디에서 김기훈 선생님의 커리큘럼을 확인해줘"
PKG = "net.megastudy.smartplay.main"

# ── 처치 (사람이 확인한 문장, 2026-09-16) ────────────────────────────
#  B 는 2차 측정에서 쓴 문장 **그대로**다. 한 글자도 고치지 않았다 — 고치면 2차와의
#  연결이 끊긴다.
ORIGINAL = ("A teacher's curriculum is reached from the menu at the top left: "
            "megateacher list -> subject -> teacher -> curriculum tab. "
            "Search results show lectures and profiles, not the curriculum.")

#  C 는 같은 내용을 Candidate 템플릿(trigger → consequence)으로 다시 쓴 것이다.
#  **2건으로 쪼개지는 것은 억지가 아니라 리플렉터가 실제로 내놓을 모양**이다 —
#  후보는 건별로 나온다. 예산이 APP_FACT 2줄이라 둘 다 들어간다.
PATH_FACT = ("tapping the menu button at the top left -> the megateacher list opens, "
             "and subject then teacher then the curriculum tab leads to that "
             "teacher's curriculum.")
SEARCH_FACT = ("searching for a teacher's name -> the results show lectures and "
               "profiles, not the curriculum.")


def mem(text):
    return {"kind": "APP_FACT", "pkg": PKG, "text": text}


CONDITIONS = {
    "A": [],
    "B": [mem(ORIGINAL)],
    "C": [mem(PATH_FACT), mem(SEARCH_FACT)],
    "D": [mem(SEARCH_FACT)],
}

TASK = {"id": "mega", "goal": GOAL, "pkg": PKG, "memories": None}


def append(row):
    os.makedirs(os.path.dirname(RESULTS), exist_ok=True)
    with open(RESULTS, "a", encoding="utf-8") as f:
        f.write(json.dumps(row, ensure_ascii=False) + "\n")


def show(row):
    mark = "✗ " + "; ".join(row["excluded"]) if row["excluded"] else "✓"
    print(f"  [{row['cond']}] {str(row['turns']):>3}턴  {row['outcome']:8} "
          f"{row['wall']:6.1f}s  {mark}")


def cmd_measure():
    total = len(CONDITIONS) * REPEATS
    print(f"템플릿 형식 측정 — {total}회 ({len(CONDITIONS)}조건 × {REPEATS}), "
          f"A B C D 번갈아\n모델 {MODEL}/{THINKING}\n")
    for i in range(REPEATS):
        print(f"[{i + 1}회차]")
        # 번갈아 돌린다 — 시간대·네트워크·앱의 자체 학습이 조건과 엮이지 않게.
        # 무신사 최근 검색어처럼 앱이 스스로 학습해 뒤로 갈수록 쉬워지기 때문이다.
        for cond, memories in CONDITIONS.items():
            row = one_run(TASK, cond, memories=memories)
            append(row)
            show(row)
        print()
    print(f"끝 → {os.path.relpath(RESULTS)}")


def cmd_report():
    rows = [json.loads(l) for l in open(RESULTS, encoding="utf-8")]
    # ⚠️ outcome == "error" 는 **과제의 난이도가 아니라 기기·인프라의 사고**다
    #   (보안 화면 오판 · HTTP 503). 게다가 그런 실행은 finish() 를 못 거쳐 turnsUsed 가
    #   0 으로 남아 **평균을 끌어내린다** — 실제로 처음 집계에서 A 16.5 → 13.2,
    #   B 8.75 → 7.0 으로 왜곡됐다.
    #   ★ 사전 제외 목록에 이 항목이 없었다(1·2차에서 error 가 한 번도 안 났다).
    #     숫자를 본 뒤에 더한 규칙이므로 **사후 판단임을 밝혀 둔다.**
    #   반대로 fail("20턴 안에 못 끝냄")은 과제의 결과이므로 20턴 그대로 넣는다.
    def dropped(r):
        if r["excluded"]:
            return "; ".join(r["excluded"])
        if r["outcome"] == "error":
            return f"실행 오류(사후 제외) — {r['reply'][:60]}"
        if r["turns"] is None:
            return "턴 수 없음"
        return None

    ok = [r for r in rows if dropped(r) is None]
    print(f"총 {len(rows)}행, 집계 대상 {len(ok)}행 (제외 {len(rows) - len(ok)})\n")
    for r in rows:
        why = dropped(r)
        if why:
            print(f"  제외: [{r['cond']}] {why}")

    avg = {}
    for cond in CONDITIONS:
        v = [r["turns"] for r in ok if r["cond"] == cond]
        if v:
            avg[cond] = sum(v) / len(v)
            done = sum(1 for r in ok if r["cond"] == cond and r["outcome"] == "success")
            print(f"  {cond}  평균 {avg[cond]:5.1f}턴  완주 {done}/{len(v)}  {v}")

    print("\n── 판정 (기준은 측정 전에 정했다) ──")
    if "B" in avg and "C" in avg:
        d = (avg["C"] - avg["B"]) / avg["B"] * 100
        print(f"  형식 비용   C vs B: {d:+.1f}%  → "
              f"{'템플릿이 깎는다' if d >= 20 else '형식 비용 없음'}")
    if "C" in avg and "D" in avg:
        d = (avg["D"] - avg["C"]) / avg["C"] * 100
        print(f"  경로의 기여 D vs C: {d:+.1f}%  → "
              f"{'경로(ⓐ)가 실질 기여 — RECIPE 논의 필요' if d >= 20 else 'ⓑ 하나로 설명된다'}")
    if "A" in avg:
        print(f"\n  참고 — 2차의 A 는 17.2턴, B 는 8.8턴이었다 (이번 A {avg['A']:.1f}턴).")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "report"
    {"measure": cmd_measure, "report": cmd_report}[cmd]()
