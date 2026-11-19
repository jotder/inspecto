---
name: handoff
description: >
  Shift-change / session-end handoff for the shared Inspecto sandbox. Trigger on "HANDOFF",
  "shift change", "hand over", "end of shift/session", or when the operator says they are done
  for the day. Rewrites SESSION_STATUS.local.md (state, commit chain, test baseline, pending,
  guardrails, next steps), refreshes affected repo docs and the memory index, then tells the
  operator to END the session so the next shift starts fresh — never resume a stale context.
---

# /handoff — shift-change protocol

This sandbox is shared by a team working in shifts. The next operator resumes from the **repo**,
not from this conversation. A handoff is complete when a fresh session can continue the work with
zero questions.

## Steps

1. **Rewrite `SESSION_STATUS.local.md`** (replace, don't append). Sections:
   - **Objective** — what this stretch of work is trying to achieve.
   - **Current state** — commit chain with SHAs, branch, test baseline (e.g. `mvn -o clean test`
     counts, `npm run test:ci` counts), what is verified vs. merely written.
   - **In-flight / uncommitted** — exact files and why they are uncommitted.
   - **Next steps** — ordered, concrete, with the first command to run.
   - **Blockers / gotchas** — anything the next shift would trip over.
   - Absolute dates only (no "today"/"yesterday"). Never commit this file (gitignored).
2. **Refresh affected living docs** — the product hub pages the shift touched (gotchas go to `.claude/memory/` or the hub's gotchas page), the active plan under
   `docs/superpower/`. Refactor/dedup in place; don't append changelogs.
   **Doc lifecycle check (root `CLAUDE.md`):** if this shift SHIPPED a plan's work, distill its
   durable as-built facts into the matching `docs/okf/` concept, move still-open items to
   `docs/BACKLOG.md`, `git mv` the plan to `docs/archived-documents/plans-archive/`, add its row to the
   archived-plans table in `docs/archived-documents/README.md`, and drop its in-flight row from `docs/INDEX.md`.
   `docs/superpower/` holds in-flight plans only.
   **Docs health (`INFO-RESTRUCTURE-1`):** in the lane that lands this shift's docs, run
   `node tools/roll-backlog-ledger.mjs --write` (the board keeps three days of ledger; older entries move verbatim to
   `docs/archived-documents/backlog-ledger-<year>.md`), then `node tools/docs-health.mjs` and name any page on its
   drift list or newly over 40 KB in the hand-off. If this shift shrank docs debt, lock it in with
   `node tools/docs-health.mjs --baseline-write` in the same commit.
   **Handbook renders (`USER-HANDBOOK-1`):** run `node tools/publish-handbook.mjs` — it rebuilds the external and
   internal Handbook into `target/handbook/` and publishes to any place set in `handbook.toon` (`handbook.publish`).
   **Shift audit (root `CLAUDE.md`, "origin/master is the only line"):** `git worktree list` must show the
   shared checkout plus lanes listed in `SESSION_STATUS.local.md` only, and `git branch` must show `master` only.
   Anything else pushed → remove it (`git cherry origin/master <branch>` first, bundle what differs into
   `C:\sandbox\archive\`); anything unpushed → list it in the status file with owner and date (one shift max).
   When clean, fast-forward the shared checkout: `git merge --ff-only origin/master`.
3. **Update the memory index breadcrumb** only if a milestone shipped (repo stays the source of
   truth; memory is a thin pointer).
4. **Report to the operator**: one paragraph of what was handed off, then recommend ending this
   session. Session-per-shift is the rule — mid-task compaction is the failure mode this skill
   exists to prevent.
