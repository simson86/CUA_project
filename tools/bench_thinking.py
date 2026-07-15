r"""사고 수준(thinking level)별 속도·비용 측정기.

주의: 첫 줄에 `#!/usr/bin/env python3` shebang 을 두지 말 것. Windows `py` 런처가
그 줄을 읽고 dotenv/genai 없는 다른 python3(msys2)로 실행해버린다(→ ModuleNotFoundError).

같은 화면(스크린샷 1장) + 같은 목표로, 사고 수준만 바꿔가며 CU 모델을 여러 번
호출해 **지연시간(latency)** 과 **토큰 사용량(→ 비용)** 을 잰다. 한 호출 = "이 화면에서
다음 액션 하나" 판단이라 수준 간 비교가 공정하다(멀티턴 X, 기기 상태 안 바뀜).

실행 (repo 루트에서, 반드시 `py`):
    py tools/bench_thinking.py --task "설정 앱을 열어" --runs 3
    py tools/bench_thinking.py --levels low,high --runs 5 --image shot.png
    py tools/bench_thinking.py --out docs/reference/thinking-bench-2026-07-15.md

옵션:
    --task   TEXT     목표 문장 (기본: "설정 앱을 열어 다크 모드를 켜")
    --levels L,L,..   측정할 수준 (기본: minimal,low,medium,high)
    --runs   N        수준마다 반복 횟수(평균낸다, 기본 3)
    --image  PATH     이 PNG 로 측정(기기 불필요). 없으면 연결된 기기에서 1장 캡처
    --price-in  F     입력 100만 토큰당 USD (기본 PRICING, --price 로 덮어씀)
    --price-out F     출력+사고 100만 토큰당 USD
    --out    PATH     결과 마크다운 표를 파일로도 저장

사전: .env 의 GEMINI_API_KEY. 기기 캡처 시 adb 연결. 실호출이라 소량 과금됨
      (호출 수 = len(levels) × runs; 기본 4×3 = 12회).

⚠ 비용 단가(PRICING)는 공식 가격표에서 직접 확인해 갱신할 것. 토큰량은 정확히 재지만
   달러 환산은 이 단가에 의존한다. 링크: https://ai.google.dev/pricing
"""

import argparse
import os
import statistics
import sys
import time
from typing import Optional

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from dotenv import load_dotenv
load_dotenv(os.path.join(_REPO_ROOT, ".env"))

from cua import CUClient, initial_input

# 100만 토큰당 USD — ⚠ 공식 가격표(https://ai.google.dev/pricing)에서 확인 후 갱신.
# 토큰 수는 정확히 측정되며, 아래 단가만 바꾸면 비용이 재계산된다.
PRICING = {
    "gemini-3.5-flash": {"in": 0.30, "out": 2.50},  # ← 추정 placeholder, 갱신 필요
}
DEFAULT_TASK = "설정 앱을 열어 다크 모드를 켜"


def get_screenshot(image_path: Optional[str]) -> bytes:
    """--image 있으면 그 파일, 없으면 연결된 기기에서 1장 캡처."""
    if image_path:
        with open(image_path, "rb") as f:
            return f.read()
    from live.adb_bridge import ADBBridge
    bridge = ADBBridge()
    print(f"기기에서 스크린샷 캡처... ({bridge.width}x{bridge.height})")
    return bridge.screenshot()


def usage_tokens(interaction) -> dict:
    """응답의 usage 를 표준 키로 정리. 없으면 0."""
    u = getattr(interaction, "usage", None)
    g = lambda name: (getattr(u, name, None) or 0) if u else 0
    return {
        "input": g("total_input_tokens"),
        "output": g("total_output_tokens"),
        "thought": g("total_thought_tokens"),
        "cached": g("total_cached_tokens"),
        "total": g("total_tokens"),
    }


def cost_usd(tok: dict, price_in: float, price_out: float) -> float:
    """비용 = 입력×단가_in + (출력+사고)×단가_out. 사고 토큰은 출력으로 과금."""
    billed_out = tok["output"] + tok["thought"]
    return (tok["input"] * price_in + billed_out * price_out) / 1_000_000


def measure(client: CUClient, task: str, shot: bytes, level: str, runs: int,
            price_in: float, price_out: float) -> dict:
    lats, toks, costs = [], [], []
    for i in range(runs):
        t0 = time.perf_counter()
        # 매번 새 판단(previous_interaction_id 없음)이라 수준 비교가 공정하다.
        interaction = client.create(initial_input(task, shot), thinking_level=level)
        lats.append(time.perf_counter() - t0)
        tk = usage_tokens(interaction)
        toks.append(tk)
        costs.append(cost_usd(tk, price_in, price_out))
        print(f"  [{level:<7}] run {i+1}/{runs}: {lats[-1]:6.2f}s  "
              f"in={tk['input']} out={tk['output']} thought={tk['thought']}")
    avg = lambda xs: sum(xs) / len(xs) if xs else 0.0
    return {
        "level": level,
        "lat_avg": avg(lats),
        "lat_min": min(lats), "lat_max": max(lats),
        "lat_std": statistics.pstdev(lats) if len(lats) > 1 else 0.0,
        "tok_in": avg([t["input"] for t in toks]),
        "tok_out": avg([t["output"] for t in toks]),
        "tok_thought": avg([t["thought"] for t in toks]),
        "tok_total": avg([t["total"] for t in toks]),
        "cost_avg": avg(costs),
    }


def _table(rows) -> list:
    """수준별 결과 표(마크다운 라인 리스트)."""
    L = ["| 수준 | 평균 지연(s) | 지연 min~max | 입력tok | 출력tok | 사고tok | 총tok | 평균 비용($) |",
         "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        L.append(
            f"| {r['level']} | {r['lat_avg']:.2f} | "
            f"{r['lat_min']:.2f}~{r['lat_max']:.2f} | {r['tok_in']:.0f} | "
            f"{r['tok_out']:.0f} | {r['tok_thought']:.0f} | {r['tok_total']:.0f} | "
            f"{r['cost_avg']:.6f} |")
    return L


def render_md(results, model, runs, price_in, price_out) -> str:
    """results: [(task, rows), ...]. 명령별 표 + 수준별 요약."""
    L = [f"# 사고 수준 벤치마크 — {model}", "",
         f"- 명령 {len(results)}개 · 수준별 {runs}회 평균",
         f"- 단가(USD/1M tok): 입력 {price_in}, 출력+사고 {price_out} "
         f"(⚠ https://ai.google.dev/pricing 에서 확인·갱신)",
         "- 측정 단위: **현재 화면에서 그 명령의 '첫 판단 1회'** (멀티턴/실제 실행 아님)", ""]
    for i, (task, rows) in enumerate(results, 1):
        L += [f"## {i}. {task}", ""] + _table(rows) + [""]

    # 수준별 요약(명령 전체 평균)
    levels = [r["level"] for r in results[0][1]] if results else []
    L += ["## 수준별 요약 (5개 명령 평균)", "",
          "| 수준 | 평균 지연(s) | 평균 사고tok | 평균 총tok | 평균 비용($) |",
          "|---|---|---|---|---|"]
    for lv in levels:
        picks = [r for _, rows in results for r in rows if r["level"] == lv]
        n = len(picks) or 1
        L.append(f"| {lv} | {sum(p['lat_avg'] for p in picks)/n:.2f} | "
                 f"{sum(p['tok_thought'] for p in picks)/n:.0f} | "
                 f"{sum(p['tok_total'] for p in picks)/n:.0f} | "
                 f"{sum(p['cost_avg'] for p in picks)/n:.6f} |")
    L += ["", "> 사고tok 이 수준에 따라 늘면 thinking_level 이 실제로 먹은 것.",
          "> 비용은 토큰×단가이므로 단가만 갱신하면 재계산됨."]
    return "\n".join(L) + "\n"


def load_tasks(path: str) -> list:
    """task 파일에서 명령들을 읽는다. '1. ', '5.' 같은 앞 번호는 떼어냄."""
    import re
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = re.sub(r"^\s*\d+\.\s*", "", line.strip())
            if s:
                out.append(s)
    return out


def main():
    ap = argparse.ArgumentParser(description="사고 수준별 속도·비용 측정")
    ap.add_argument("--task", default=DEFAULT_TASK)
    ap.add_argument("--tasks-file", default=None,
                    help="명령 목록 파일(줄마다 하나, 앞 번호는 자동 제거). 주면 --task 무시")
    ap.add_argument("--levels", default="minimal,low,medium,high")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--image", default=None)
    ap.add_argument("--model", default="gemini-3.5-flash")
    ap.add_argument("--price-in", type=float, default=None)
    ap.add_argument("--price-out", type=float, default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    price = PRICING.get(args.model, {"in": 0.0, "out": 0.0})
    price_in = args.price_in if args.price_in is not None else price["in"]
    price_out = args.price_out if args.price_out is not None else price["out"]
    levels = [x.strip().lower() for x in args.levels.split(",") if x.strip()]

    if args.tasks_file:
        tf = args.tasks_file if os.path.isabs(args.tasks_file) \
            else os.path.join(_REPO_ROOT, args.tasks_file)
        tasks = load_tasks(tf)
    else:
        tasks = [args.task]

    shot = get_screenshot(args.image)
    client = CUClient(model=args.model)
    total_calls = len(tasks) * len(levels) * args.runs
    print(f"모델: {args.model} | 명령 {len(tasks)}개")
    print(f"수준: {levels} | 반복: {args.runs} | 총 호출 {total_calls}회")
    print("-" * 60)

    results = []
    for ti, task in enumerate(tasks, 1):
        print(f"\n=== 명령 {ti}/{len(tasks)}: {task} ===")
        rows = []
        for lv in levels:
            print(f"[{lv}] 측정 중...")
            rows.append(measure(client, task, shot, lv, args.runs, price_in, price_out))
        results.append((task, rows))

    md = render_md(results, args.model, args.runs, price_in, price_out)
    print("\n" + md)
    if args.out:
        out = args.out if os.path.isabs(args.out) else os.path.join(_REPO_ROOT, args.out)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            f.write(md)
        print(f"저장: {out}")


if __name__ == "__main__":
    main()
