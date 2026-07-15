#!/usr/bin/env python3
"""날짜별 진행 기록(docs/progress/YYYY-MM-DD.md)을 git 커밋 이력에서 생성.

커밋마다 post-commit 훅이 실행 → 터미널이 꺼져도 진행상황이 디스크에 남고,
git 으로 추적되어 미래 세션이 읽어 프로젝트 일관성을 유지한다.
수동 실행:  python tools/progress_log.py

무한 커밋 루프 방지: "진행 문서(docs/progress/)만 건드린 커밋"은 기록 대상에서
제외한다. 그런 커밋은 새 진행 항목을 만들지 않으므로 문서 내용이 안정되어(fixpoint)
Stop 훅의 자동 커밋이 무한히 이어지지 않는다.
"""

import os
import subprocess
from collections import OrderedDict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUTDIR = os.path.join(REPO, "docs", "progress")
PROGRESS_PREFIX = "docs/progress/"      # 이 경로만 바꾼 커밋은 진행 항목에서 제외
HIDE_FILES = ("docs/progress/", "git_report.html")  # 항목의 파일 목록에서 숨김

MARK = "\x1e"   # record separator (NUL 은 Windows argv 에 못 넣으므로 사용 불가)
SEP = "\x1f"


def git(*args) -> str:
    return subprocess.run(
        ["git", "-C", REPO, "--no-pager", *args],
        capture_output=True, encoding="utf-8", errors="replace",
    ).stdout or ""


def load_commits():
    out = git("log", "--reverse", "--date=format:%Y-%m-%d %H:%M",
              f"--pretty=format:{MARK}%H{SEP}%h{SEP}%ad{SEP}%an{SEP}%s", "--name-only")
    entries, cur = [], None
    for line in out.split("\n"):
        if line.startswith(MARK):
            if cur:
                entries.append(cur)
            full, short, ad, an, subj = line[len(MARK):].split(SEP)
            date, _, tm = ad.partition(" ")
            cur = dict(full=full, short=short, date=date, time=tm,
                       author=an, subject=subj, files=[])
        elif line.strip() and cur is not None:
            cur["files"].append(line.strip())
    if cur:
        entries.append(cur)
    return entries


def is_progress_only(files) -> bool:
    return bool(files) and all(f.startswith(PROGRESS_PREFIX) for f in files)


def render(date, commits) -> str:
    lines = [f"# 진행 기록 — {date}", "",
             "> 커밋마다 자동 생성됩니다 (`tools/progress_log.py`). 직접 편집하지 마세요.",
             f"> 이 날짜의 진행 항목 {len(commits)}개.", ""]
    for c in commits:
        auto = " _(auto)_" if c["subject"].startswith("[auto]") else ""
        lines.append(f"## {c['time']} · `{c['short']}` — {c['subject']}{auto}")
        shown = [f for f in c["files"]
                 if not any(f.startswith(h) for h in HIDE_FILES)]
        if shown:
            lines.append("")
            lines += [f"- `{f}`" for f in shown]
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    by_date = OrderedDict()
    for c in load_commits():
        if is_progress_only(c["files"]):
            continue
        by_date.setdefault(c["date"], []).append(c)

    written = 0
    for date, commits in by_date.items():
        path = os.path.join(OUTDIR, f"{date}.md")
        content = render(date, commits)
        old = ""
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                old = f.read()
        if old != content:                       # 변경 없으면 파일 안 건드림(fixpoint 유지)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            written += 1
    print(f"progress_log: {len(by_date)}개 날짜, {written}개 갱신 -> {OUTDIR}")


if __name__ == "__main__":
    main()
