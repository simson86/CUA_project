# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Agents that drive a **real Android device via ADB** using Google's **Gemini Computer Use (CU)** model (mobile environment). A screenshot + goal go to the model each step; it returns one UI action (click/type/…); the action runs over ADB; repeat until done. There are **three parallel implementations of the same idea** — see `STRUCTURE_COMPARISON.md` for a full side-by-side.

## Commands

- **Use `py`, never `python`.** On this machine PATH `python` is msys2 (no pip / no `google-genai`) and will raise `ModuleNotFoundError: google`. The real interpreter is the `py` launcher (Python 3.14) where `google-genai` + `python-dotenv` are installed.
- **Run the live agent:** `py live/main.py "작업 설명"` (from repo root). No arg → defaults to `"Open the Settings app"`.
- **Run the monolithic agent:** `py mobile_agent/agent.py` (prompts interactively for the task).
- **Regenerate the git change report:** `py tools/git_report.py` (usually automatic — see Automation).
- **Prerequisites:** a connected device (`adb devices` shows one), and `GEMINI_API_KEY` in `.env` (copy from `.env.example`).
- **No test/build/lint framework.** The only self-tests are `if __name__ == "__main__"` smoke blocks (e.g. `mobile_agent/device.py` prints screen size and taps center).

## Architecture

### Two codebases, two different Gemini SDKs — do not mix them
- **`cua/` + `live/` (modular):** uses `client.interactions.create` with **server-managed history** via `previous_interaction_id`. `cua/` is a pure *judgment core* (knows nothing about ADB or benchmarks); `live/` is the *execution layer* (ADB) plus the multi-turn loop in `live/main.py`. `live/adb_bridge.py` adds the repo root to `sys.path` to import `cua`.
- **`mobile_agent/` (monolith):** uses `client.models.generate_content` with **client-managed history** (it accumulates `contents` itself). Standalone; has its own `ADBBridge` in `device.py`. Keep changes here separate from the `cua`/`live` line.

### Action dispatch (both codebases)
Actions are dispatched by name: `getattr(bridge, action.name)(**action.args)`. **`ADBBridge` method names must equal CU action names** (`click`, `type`, `long_press`, `drag_and_drop`, `press_key`, `go_back`, `open_app`, `wait`, `list_apps`, `take_screenshot`). To support a new action, add a method of that name; use `**_` to absorb extra args like `intent`. Unknown/failed actions are fed back to the model as `{"status":"error",...}` instead of crashing, so the model can self-correct.

### Coordinates
CU returns **0–1000 normalized** coords. Convert to real pixels with `cua.denormalize(x, y, width, height)` (`live`) or `_px()` (`mobile_agent`). Screen size is read dynamically from `wm size`.

### Korean text input (live)
`live` types via **ADBKeyboard + base64 broadcast** (`am broadcast -a ADB_INPUT_B64 --es msg <base64>`), which bypasses shell parsing so Korean/spaces/special chars survive. `main.py` calls `bridge.ensure_adb_keyboard()` on startup (installs the bundled `live/vendor/ADBKeyboard.apk` if missing, switches IME, saves the original) and `bridge.restore_keyboard()` in a `finally`. `mobile_agent` has the same mechanism; the older `adb input text` path is ASCII-only.

### Gotcha: `ADBBridge._run` decodes with the OS locale (cp949 here)
It uses `subprocess.run(..., text=True)`, so adb output containing non-ASCII (e.g. a `uiautomator` XML dump with Korean) will raise `UnicodeDecodeError`. Production paths return ASCII, so this is fine today — but any new feature that parses non-ASCII adb output must read it as UTF-8 explicitly.

## Session continuity — read this first
**At the start of a session, read the most recent `docs/progress/YYYY-MM-DD.md`** to recover what was done and stay consistent across terminal restarts. These dated files are the durable progress journal (tracked in git), auto-generated from commit history — so write **descriptive commit messages**, since they become the journal entries.

## Gemini Computer Use reference
**`docs/reference/gemini-computer-use.md`** distills the 4 official sources (DeepMind/Flash announcements, the CU API + safety docs, and the `gemini-android-computer-use-quickstart` repo our code derives from). Treat it as the *spec* for what this project implements — consult it before adding actions, touching coordinates, or changing the safety/loop flow. Key facts: model `gemini-3.5-flash` with `environment:"mobile"`; actions returned as function_calls dispatched to same-named `ADBBridge` methods; coords 0–1000 normalized; `safety_decision:"require_confirmation"` must be acknowledged (we auto-ack in the demo); the quickstart's `click(y, x)` has **swapped arg order** — ours uses `(x, y)`.

## Automation & repo conventions
- **Auto-commit:** a `Stop` hook in `.claude/settings.local.json` runs `git add -A` + commits (message `[auto] <timestamp>`) at the end of each turn, only when something changed. Make your own descriptive commits for real work; the hook is a safety net.
- **On every commit**, `.git/hooks/post-commit` regenerates two things (via `py tools/*.py`):
  - `git_report.html` — self-contained HTML diff viewer. **Gitignored** (artifact) — don't commit it.
  - `docs/progress/<date>.md` — dated progress journal. **Tracked** (committed). Generated by `tools/progress_log.py`; **don't edit by hand**. Commits that touch *only* `docs/progress/` are excluded from the journal so the file reaches a fixpoint and the Stop hook doesn't loop.
- The post-commit hook lives in `.git/` (not tracked); recreate it after a fresh clone.
- **Secrets:** `.env` is gitignored; keys are `GEMINI_API_KEY`, plus unused `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`AUX_MODEL`. `live/vendor/ADBKeyboard.apk` **is** committed intentionally (offline install).
- Model id in use: `gemini-3.5-flash` (see `cua/cu_client.py` and `mobile_agent/agent.py`).
