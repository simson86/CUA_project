r"""멀티턴 완주 벤치마크 — 사고 수준이 '실제 작업 수행'에 주는 차이 측정.

bench_thinking.py 가 '첫 판단 1회'만 잰다면, 이건 **작업을 끝까지 수행**시키며
수준별로 완주율·스텝 수·에러·시간·비용을 잰다. 사고 수준의 진짜 값어치
(실수를 줄여 총 스텝을 줄이는가, 더 잘 끝내는가)가 여기서 드러난다.

⚠ 실제로 기기를 여러 스텝 조작한다. **안전·되돌릴 수 있는 작업만** 쓸 것
   (앱 열기/화면 이동/계산 등. 구매·결제·전송 금지). 기기 연결 필수, 시간 오래 걸림.

주의: 첫 줄에 shebang 금지(py 런처가 msys 파이썬으로 실행 → ModuleNotFoundError).

실행 (repo 루트, py):
    py tools/bench_multiturn.py --levels minimal,low,medium,high --runs 2
    py tools/bench_multiturn.py --tasks-file safe_tasks.txt --runs 1 --max-turns 10
    py tools/bench_multiturn.py --out docs/reference/multiturn-bench-2026-07-15.md

지표(수준별):
    완주율   = 모델이 max-turns 안에 done 선언한 비율 (※ 모델 자기 판단.
               실제 성공은 저장된 최종 스크린샷으로 확인)
    턴/액션  = 완료까지 걸린 API 호출/실행 액션 수 (적을수록 효율적)
    에러     = status=error 로 되먹인 액션 수 (적을수록 정확)
    시간/비용 = 작업당 총 벽시계 시간, 총 토큰 기반 비용
"""

import argparse
import os
import sys
import time

_REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_TOOLS = os.path.dirname(os.path.abspath(__file__))
for p in (_REPO_ROOT, _TOOLS):
    if p not in sys.path:
        sys.path.insert(0, p)

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from dotenv import load_dotenv
load_dotenv(os.path.join(_REPO_ROOT, ".env"))

from cua import (CUClient, initial_input, function_result,
                 parse_actions, is_done, final_text)
from live.adb_bridge import ADBBridge
from bench_thinking import usage_tokens, cost_usd, PRICING  # 단가·토큰 헬퍼 재사용

SETTLE_SEC = 0.6
SHOT_DIR = os.path.join(
    os.environ.get("TEMP", _REPO_ROOT), "cua_multiturn_shots")

# 안전·되돌릴 수 있는 기본 작업(구매/결제/전송 없음, 상태 변경 최소).
DEFAULT_TASKS = [
    "설정 앱을 열어 배터리 화면으로 이동해",
    "계산기 앱을 열어 15 더하기 27을 계산해",
    "시계 앱을 열어 스톱워치 화면으로 이동해",
]


def call_with_retry(fn, retries=5):
    """연결 끊김(슬립/네트워크)에 견디게 지수 백오프 재시도. 논리오류는 즉시 raise."""
    delay = 2.0
    for attempt in range(retries + 1):
        try:
            return fn()
        except Exception as e:
            name = type(e).__name__
            transient = any(k in name for k in ("Connection", "ReadError",
                            "Timeout", "Remote")) or "10054" in str(e)
            if attempt >= retries or not transient:
                raise
            print(f"    (연결 오류 {name} → {delay:.0f}s 후 재시도 "
                  f"{attempt+1}/{retries})", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 30)


def reset_home(bridge):
    """작업 사이 초기화: 홈으로. (앱 상태가 다음 작업에 새지 않게)"""
    for _ in range(2):
        bridge._run(["shell", "input", "keyevent", "3"], check=False)  # HOME
        time.sleep(0.8)


def run_task(client, bridge, task, level, max_turns, tag) -> dict:
    """한 작업을 끝까지(또는 max_turns) 수행하며 지표 수집."""
    reset_home(bridge)
    time.sleep(0.5)
    acc = {"input": 0, "output": 0, "thought": 0, "total": 0}

    def add(interaction):
        tk = usage_tokens(interaction)
        for k in acc:
            acc[k] += tk.get(k, 0)

    t0 = time.perf_counter()
    interaction = call_with_retry(
        lambda: client.create(initial_input(task, bridge.screenshot()),
                              thinking_level=level))
    add(interaction)
    prev = interaction.id

    turns, actions, errors, done = 0, 0, 0, False
    for turn in range(1, max_turns + 1):
        if is_done(interaction):
            done = True
            break
        turns += 1
        results = []
        for action in parse_actions(interaction):
            actions += 1
            data = {"status": "ok"}
            handler = getattr(bridge, action.name, None)
            if handler is None:
                data = {"status": "error", "error": f"Unknown action: {action.name}"}
                errors += 1
            else:
                try:
                    out = handler(**action.args)
                    if isinstance(out, dict):
                        data.update(out)
                except Exception as e:
                    data = {"status": "error", "error": str(e)}
                    errors += 1
            safety_ack = "safety_decision" in action.args
            time.sleep(SETTLE_SEC)
            results.append(function_result(action.name, action.call_id,
                                           bridge.screenshot(), data, safety_ack))
        if not results:
            break
        interaction = call_with_retry(
            lambda r=results, p=prev: client.create(r, previous_interaction_id=p,
                                                    thinking_level=level))
        add(interaction)
        prev = interaction.id

    wall = time.perf_counter() - t0

    # 최종 스크린샷 저장(실제 성공 눈검증용)
    os.makedirs(SHOT_DIR, exist_ok=True)
    shot_path = os.path.join(SHOT_DIR, f"{tag}.png")
    try:
        with open(shot_path, "wb") as f:
            f.write(bridge.screenshot())
    except Exception:
        shot_path = "(캡처 실패)"

    return {
        "done": done, "turns": turns, "actions": actions, "errors": errors,
        "wall": wall, "tok_total": acc["total"] or (acc["input"] + acc["output"] + acc["thought"]),
        "cost": cost_usd(acc, PRICE_IN, PRICE_OUT),
        "final_text": (final_text(interaction) or "")[:80], "shot": shot_path,
    }


# 단가(모듈 전역; main 에서 세팅)
PRICE_IN, PRICE_OUT = 0.30, 2.50


def agg(runs_data) -> dict:
    n = len(runs_data) or 1
    s = lambda k: sum(r[k] for r in runs_data)
    return {
        "done_rate": s("done") / n, "turns": s("turns") / n,
        "actions": s("actions") / n, "errors": s("errors") / n,
        "wall": s("wall") / n, "tok_total": s("tok_total") / n,
        "cost": s("cost") / n, "n": n,
    }


def render_md(results, model, runs, levels) -> str:
    L = [f"# 멀티턴 완주 벤치마크 — {model}", "",
         f"- 작업 {len(results)}개 · 수준 {levels} · 작업당 {runs}회",
         f"- 단가(USD/1M): 입력 {PRICE_IN}, 출력+사고 {PRICE_OUT} (⚠ placeholder, 갱신 필요)",
         "- **완주율 = 모델이 max-turns 안에 done 선언한 비율(자기 판단).** "
         "실제 성공은 저장된 최종 스크린샷으로 확인.",
         f"- 최종 스크린샷: `{SHOT_DIR}`", ""]
    for i, (task, per_level) in enumerate(results, 1):
        L += [f"## {i}. {task}", "",
              "| 수준 | 완주율 | 평균 턴 | 평균 액션 | 평균 에러 | 시간(s) | 총tok | 비용($) |",
              "|---|---|---|---|---|---|---|---|"]
        for lv in levels:
            a = per_level[lv]
            L.append(f"| {lv} | {a['done_rate']*100:.0f}% | {a['turns']:.1f} | "
                     f"{a['actions']:.1f} | {a['errors']:.1f} | {a['wall']:.1f} | "
                     f"{a['tok_total']:.0f} | {a['cost']:.6f} |")
        L.append("")
    # 수준별 요약(작업 전체 평균)
    L += ["## 수준별 요약 (작업 전체 평균)", "",
          "| 수준 | 완주율 | 평균 턴 | 평균 액션 | 평균 에러 | 평균 시간(s) | 평균 비용($) |",
          "|---|---|---|---|---|---|---|"]
    for lv in levels:
        picks = [per_level[lv] for _, per_level in results]
        n = len(picks) or 1
        m = lambda k: sum(p[k] for p in picks) / n
        L.append(f"| {lv} | {m('done_rate')*100:.0f}% | {m('turns'):.1f} | "
                 f"{m('actions'):.1f} | {m('errors'):.1f} | {m('wall'):.1f} | "
                 f"{m('cost'):.6f} |")
    L += ["", "> 높은 수준이 **액션·에러·턴을 줄이면** 사고 수준이 값어치를 한 것.",
          "> 첫 판단만 보는 bench_thinking.py 와 함께 해석. 단가는 placeholder."]
    return "\n".join(L) + "\n"


def load_tasks(path):
    import re
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            s = re.sub(r"^\s*\d+\.\s*", "", line.strip())
            if s:
                out.append(s)
    return out


def main():
    global PRICE_IN, PRICE_OUT
    ap = argparse.ArgumentParser(description="멀티턴 완주 벤치(사고 수준별)")
    ap.add_argument("--tasks-file", default=None)
    ap.add_argument("--levels", default="minimal,low,medium,high")
    ap.add_argument("--runs", type=int, default=2)
    ap.add_argument("--max-turns", type=int, default=12)
    ap.add_argument("--model", default="gemini-3.5-flash")
    ap.add_argument("--price-in", type=float, default=None)
    ap.add_argument("--price-out", type=float, default=None)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    price = PRICING.get(args.model, {"in": 0.0, "out": 0.0})
    PRICE_IN = args.price_in if args.price_in is not None else price["in"]
    PRICE_OUT = args.price_out if args.price_out is not None else price["out"]
    levels = [x.strip().lower() for x in args.levels.split(",") if x.strip()]
    tasks = (load_tasks(args.tasks_file if os.path.isabs(args.tasks_file)
             else os.path.join(_REPO_ROOT, args.tasks_file))
             if args.tasks_file else DEFAULT_TASKS)

    out = None
    if args.out:
        out = args.out if os.path.isabs(args.out) else os.path.join(_REPO_ROOT, args.out)
        os.makedirs(os.path.dirname(out), exist_ok=True)

    client = CUClient(model=args.model)
    bridge = ADBBridge()
    bridge.ensure_adb_keyboard()
    total = len(tasks) * len(levels) * args.runs
    print(f"모델: {args.model} | 기기: {bridge.width}x{bridge.height}")
    print(f"작업 {len(tasks)} × 수준 {levels} × {args.runs}회 = {total} 작업-실행")
    print(f"최종 스크린샷: {SHOT_DIR}")
    print("-" * 60)

    results = []
    try:
        for ti, task in enumerate(tasks, 1):
            per_level = {}
            for lv in levels:
                print(f"\n=== 작업 {ti}/{len(tasks)} [{lv}]: {task} ===", flush=True)
                runs_data = []
                for r in range(1, args.runs + 1):
                    tag = f"t{ti}_{lv}_run{r}"
                    m = run_task(client, bridge, task, lv, args.max_turns, tag)
                    runs_data.append(m)
                    print(f"  run {r}: {'완주' if m['done'] else '미완'} "
                          f"턴{m['turns']} 액션{m['actions']} 에러{m['errors']} "
                          f"{m['wall']:.1f}s ${m['cost']:.6f}", flush=True)
                per_level[lv] = agg(runs_data)
            results.append((task, per_level))
            if out:  # 작업마다 중간 저장
                with open(out, "w", encoding="utf-8") as f:
                    f.write(render_md(results, args.model, args.runs, levels))
    finally:
        bridge.restore_keyboard()

    md = render_md(results, args.model, args.runs, levels)
    print("\n" + md, flush=True)
    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write(md)
        print(f"저장: {out}")


if __name__ == "__main__":
    main()
