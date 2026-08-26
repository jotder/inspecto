#!/bin/bash
# pre-compact-persist.sh
# EVENT: PreCompact
#
# Remind the shift to externalize state before the context window is compacted. CLAUDE.md calls
# mid-task compaction "the failure mode" for this shared-sandbox team, so this is the last moment
# knowledge can be moved from conversation into the repo.
#
# ⚠ WHY THIS IS A SCRIPT AND NOT AN INLINE `echo` IN settings.json.
# It used to be an inline echo of a hand-escaped JSON literal (quadruple backslashes), emitting
# {"hookSpecificOutput": {"hookEventName": "PreCompact", "additionalContext": …}}. That shape is
# INVALID: `hookSpecificOutput` accepts `additionalContext` only for SessionStart / UserPromptSubmit /
# PostToolUse / PostToolBatch / Stop — PreCompact is not among them. So every compaction printed
# "Hook JSON output validation failed — (root): Invalid input" and the reminder NEVER reached anyone,
# silently, for as long as it existed. Building the JSON with a serializer instead of by hand is what
# stops the next one.
#
# `systemMessage` is a top-level key valid for ANY event, so it is what a PreCompact hook can actually
# use. It surfaces to the operator rather than being injected as model context — a real limitation,
# stated here rather than papered over: PreCompact has no model-facing additionalContext channel.
python3 - <<'PY'
import json

print(json.dumps({
    "systemMessage": (
        "Context is about to be compacted. Externalize anything important not yet in the repo: "
        "update SESSION_STATUS.local.md (current objective, active work, pending items, blockers, "
        "next steps), refresh affected docs / architecture / decision records, and write durable "
        "facts to memory. Refactor and dedup knowledge rather than appending. Do not rely on "
        "conversation history surviving — the repo is the source of truth."
    )
}))
PY
