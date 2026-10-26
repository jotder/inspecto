---
name: push-and-branch-policy
description: "master is the only line (4.x deleted, nothing in production after 3.x): no merge-forward set to confirm; push when asked; the auto-mode push denial is per-attempt, not a verdict"
metadata:
  type: project
---

- **4.x was deleted 2026-08-17**; `v4.0.0*` tags are ancestors of master. Operator restated 2026-09-20: "after 3.x
  we practically don't have any release, just carry on master." The merge-forward set is permanently empty —
  do NOT ask the operator to confirm it; `.githooks/pre-push` prints that. If an instruction seems to change the
  branch model, ask for the reason before acting (it flip-flopped once).
- **Push cadence (operator 2026-10-20):** one lane — or one small related group — per push, integrated in a clean
  sibling worktree and verified with targeted tests; one full gate per day or release; see
  [[lane-integration-workflow]] and [[ci-gates-beyond-the-reactor]] (this replaces the earlier "push batches").
- **`origin/master` is the only line (operator 2026-10-26):** no long-lived or per-person branches, no parked worktrees;
  every lane starts from `origin/master` and is removed when pushed (root `CLAUDE.md`, [[lane-integration-workflow]]).
- **Classifier push denials are per-attempt** and not a hook/policy failure: retry on the next push; if denied
  again, say it needs the operator's hands for now. Never work around it (`permissions.allow` is the wrong lever).
  History rewrite is also refused — squash-merge an integrated tree instead.
