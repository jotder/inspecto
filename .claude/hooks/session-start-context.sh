#!/bin/bash
# session-start-context.sh
# EVENT: SessionStart
# Repo-first protocol reminder + last session snapshot (shared team sandbox, shift handover).

# ── activate the git hooks, every shift, idempotently ───────────────────────────
# `.githooks/pre-push` is the ONLY layer that can stop a committed secret or a vocabulary violation
# BEFORE it reaches the remote (CI runs after the push). It does nothing at all unless this clone
# sets core.hooksPath, which BRANCHING.md asks each teammate to do by hand once — and a hand step
# nobody is reminded of is a hand step somebody skips. This checkout is a shared sandbox worked in
# shifts, so wiring it at session start is what makes the layer real rather than documented.
# Cheap, idempotent, and silent when already correct.
if [ "$(git config --get core.hooksPath 2>/dev/null)" != ".githooks" ] && [ -d .githooks ]; then
  git config core.hooksPath .githooks 2>/dev/null && export HOOKS_ACTIVATED=1
fi

python3 - <<'PY'
import json, os

ctx = ""
if os.environ.get("HOOKS_ACTIVATED"):
    ctx += (
        "NOTE: this clone had no core.hooksPath set — it has been pointed at .githooks, so the "
        "pre-push guards (committed-secret + canonical-vocabulary) are now active. See "
        "docs/BRANCHING.md §8.\n\n"
    )
ctx += (
    "Repository-first protocol (see CLAUDE.md): this checkout is a SHARED TEAM SANDBOX worked in "
    "shifts; conversation context is temporary, the repo is the source of truth. Resume from "
    "SESSION_STATUS.local.md (live working state) and docs/. Reference maps in .claude/ "
    "(COMMON_MISTAKES, QUICK_START, ARCHITECTURE_MAP) are on-demand, not auto-loaded. "
    "At shift end run /handoff and END the session — session-per-shift, no mid-task compaction."
)
snap = ".claude/sessions/snapshot.md"
if os.path.isfile(snap):
    try:
        with open(snap, encoding="utf-8", errors="replace") as f:
            ctx += "\n\nLast session snapshot (.claude/sessions/snapshot.md):\n" + f.read()[:1500]
    except OSError:
        pass
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
PY
