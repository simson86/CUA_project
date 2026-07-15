#!/usr/bin/env python3
"""git 변경사항을 보기 좋은 단일 HTML(git_report.html)로 생성.

커밋마다 post-commit 훅이 이 스크립트를 실행해 리포트를 갱신한다.
수동 실행도 가능:  python tools/git_report.py [최대커밋수]

- 표준 라이브러리만 사용 (외부 패키지 불필요)
- 결과 HTML 은 self-contained (CSS/JS 인라인), 오프라인에서 그냥 열면 됨
- 커밋 목록(최신순) + 파일별 stat + 접이식 컬러 diff
"""

import html
import os
import subprocess
import sys
from datetime import datetime

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "git_report.html")
MAX_COMMITS = int(sys.argv[1]) if len(sys.argv) > 1 else 100

# 필드 구분자(파일/메시지에 안 나오는 제어문자 사용)
US, RS = "\x1f", "\x1e"


def git(*args) -> str:
    return subprocess.run(
        ["git", "-C", REPO, "--no-pager", *args],
        capture_output=True, encoding="utf-8", errors="replace",
    ).stdout


def get_commits():
    fmt = US.join(["%H", "%h", "%an", "%ad", "%s", "%b"]) + RS
    out = git("log", f"-n{MAX_COMMITS}", "--date=format:%Y-%m-%d %H:%M:%S",
              f"--pretty=format:{fmt}")
    commits = []
    for rec in out.split(RS):
        rec = rec.strip("\n")
        if not rec.strip():
            continue
        parts = rec.split(US)
        if len(parts) < 6:
            continue
        full, short, author, date, subject, body = parts[:6]
        commits.append(dict(full=full, short=short, author=author,
                            date=date, subject=subject, body=body.strip()))
    return commits


def diff_to_html(patch: str) -> str:
    rows = []
    for line in patch.split("\n"):
        cls = "ctx"
        if line.startswith("diff --git") or line.startswith("index ") \
                or line.startswith("new file") or line.startswith("deleted file") \
                or line.startswith("rename "):
            cls = "meta"
        elif line.startswith("+++") or line.startswith("---"):
            cls = "fhead"
        elif line.startswith("@@"):
            cls = "hunk"
        elif line.startswith("+"):
            cls = "add"
        elif line.startswith("-"):
            cls = "del"
        rows.append(f'<span class="dl {cls}">{html.escape(line) or "&nbsp;"}</span>')
    return "\n".join(rows)


def commit_block(c) -> str:
    stat = git("show", "--stat", "--oneline", "--format=", c["full"]).strip()
    patch = git("show", "--no-color", "--format=", c["full"])
    body = f'<div class="body">{html.escape(c["body"])}</div>' if c["body"] else ""
    is_auto = c["subject"].startswith("[auto]")
    tag = '<span class="tag auto">auto</span>' if is_auto else ""
    return f"""
    <article class="commit">
      <header class="chead">
        <code class="hash">{html.escape(c["short"])}</code>
        <span class="subject">{html.escape(c["subject"])}</span>{tag}
        <span class="meta-info">{html.escape(c["author"])} · {html.escape(c["date"])}</span>
      </header>
      {body}
      <details>
        <summary>변경 파일 / diff 보기</summary>
        <pre class="stat">{html.escape(stat)}</pre>
        <pre class="diff">{diff_to_html(patch)}</pre>
      </details>
    </article>"""


def build_html(commits) -> str:
    branch = git("rev-parse", "--abbrev-ref", "HEAD").strip() or "(detached)"
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    blocks = "\n".join(commit_block(c) for c in commits) or \
        '<p class="empty">커밋이 없습니다.</p>'
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Git 변경 리포트 · {html.escape(os.path.basename(REPO))}</title>
<style>
  :root {{
    --bg:#0d1117; --panel:#161b22; --border:#30363d; --text:#e6edf3;
    --muted:#8b949e; --add-bg:#12261e; --add:#3fb950; --del-bg:#26171c; --del:#f85149;
    --hunk:#58a6ff; --meta:#a371f7; --accent:#58a6ff;
  }}
  @media (prefers-color-scheme: light) {{
    :root {{
      --bg:#ffffff; --panel:#f6f8fa; --border:#d0d7de; --text:#1f2328;
      --muted:#656d76; --add-bg:#e6ffec; --add:#1a7f37; --del-bg:#ffebe9; --del:#cf222e;
      --hunk:#0969da; --meta:#8250df; --accent:#0969da;
    }}
  }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:var(--bg); color:var(--text);
    font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",system-ui,sans-serif; }}
  header.top {{ padding:20px 24px; border-bottom:1px solid var(--border);
    position:sticky; top:0; background:var(--bg); z-index:5; }}
  header.top h1 {{ margin:0 0 4px; font-size:18px; }}
  header.top .sub {{ color:var(--muted); font-size:13px; }}
  .wrap {{ max-width:1000px; margin:0 auto; padding:16px 24px 60px; }}
  .commit {{ background:var(--panel); border:1px solid var(--border);
    border-radius:8px; margin:12px 0; overflow:hidden; }}
  .chead {{ display:flex; flex-wrap:wrap; align-items:center; gap:8px; padding:12px 16px; }}
  .hash {{ background:var(--bg); border:1px solid var(--border); border-radius:6px;
    padding:1px 7px; color:var(--accent); font-size:12px; }}
  .subject {{ font-weight:600; }}
  .meta-info {{ color:var(--muted); font-size:12px; margin-left:auto; white-space:nowrap; }}
  .tag.auto {{ background:var(--meta); color:#fff; border-radius:10px;
    padding:0 8px; font-size:11px; }}
  .body {{ padding:0 16px 8px; color:var(--muted); white-space:pre-wrap; }}
  details {{ border-top:1px solid var(--border); }}
  summary {{ cursor:pointer; padding:8px 16px; color:var(--accent);
    user-select:none; font-size:13px; }}
  summary:hover {{ background:rgba(128,128,128,.08); }}
  pre {{ margin:0; padding:12px 16px; overflow-x:auto;
    font:12px/1.45 ui-monospace,SFMono-Regular,Consolas,monospace; }}
  pre.stat {{ color:var(--muted); border-bottom:1px dashed var(--border); }}
  .dl {{ display:block; white-space:pre; }}
  .add {{ background:var(--add-bg); color:var(--add); }}
  .del {{ background:var(--del-bg); color:var(--del); }}
  .hunk {{ color:var(--hunk); }}
  .meta {{ color:var(--meta); font-weight:600; }}
  .fhead {{ color:var(--muted); }}
  .empty {{ color:var(--muted); text-align:center; padding:40px; }}
</style>
</head>
<body>
<header class="top">
  <h1>📋 Git 변경 리포트 — {html.escape(os.path.basename(REPO))}</h1>
  <div class="sub">branch <b>{html.escape(branch)}</b> · 커밋 {len(commits)}개 (최신순) · 생성 {now}</div>
</header>
<div class="wrap">
{blocks}
</div>
</body>
</html>"""


def main():
    commits = get_commits()
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(build_html(commits))
    print(f"git_report.html 생성 완료 (커밋 {len(commits)}개) -> {OUT}")


if __name__ == "__main__":
    main()
