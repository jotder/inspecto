---
name: lane-integration-workflow
description: "Parallel lanes are BUILT in parallel but INTEGRATED and PUSHED one lane (or one small related group) at a time, from a clean sibling integration worktree, with targeted tests per lane and one full gate per day or release (operator 2026-10-20)"
metadata:
  type: feedback
---

**Rule (operator 2026-10-20, after weighing both ways):** each lane is committed, integrated, fixed and pushed on its
own — not batched with five other lanes. Lanes still BUILD in parallel; the serialisation is at integration.

- **Integrate in a clean sibling worktree** (`C:\sandbox\integNN`, branch from `origin/master`), never by merging in the
  shared checkout's local master: a peer's uncommitted edits blocked the fast-forward at least four times, and
  `.githooks/pre-push` runs its guards on the tree it is run from, so a peer's dirty doc failed a push. Push from the
  integration worktree (`git push origin HEAD:master`); the shared checkout fast-forwards later, when it is clean.
- **Order per lane:** `git fetch` → merge `origin/master` into the integration branch (never rebase) → merge the lane →
  fix what it broke HERE → targeted tests → push. If origin moved while you verified, merge again and re-run the
  cheap guards before pushing (origin moved six times in one session).
- **Verify per lane with the affected-build protocol** (`node tools/affected-modules.mjs`) plus the lane's own named
  test classes. The pre-commit hook runs every guard; do not re-run guards by hand. A per-lane FULL gate (≈40 min
  Maven + ≈10 min UI) is impractical; run **one full gate per day, per release, or when the tool says `full`**.
- **Fix red tests in the lane that caused them.** A peer-owned red (fails identically on a clean `origin/master`
  worktree) is classified, noted in the row, and not fixed by you unless the cause is a clear small test/data defect.
- **A small group of lanes may go together** only when they touch the same files or one is meaningless without the
  other (otherwise they conflict later). Never batch unrelated lanes.
- **Check for duplicated work before integrating:** peers rebuilt the same thing mid-lane three times (a masking
  class, the Home tile refresh, Screening items). `git log origin/master` since the lane's base; when the peer's
  version is complete, DROP the lane's copy and keep only what it adds.

**Why:** one lane per push gives a bisectable `origin/master` and puts a red test or a bad merge on exactly one lane;
it finds duplicated peer work and cross-lane conflicts early. **Cost accepted:** more pushes, more re-merges when
origin moves, thinner per-lane coverage (hence the daily full gate), and more merge commits. CI runs only nightly or
on demand, so extra pushes add no CI time. See [[push-and-branch-policy]], [[shared-tree-peer-work]],
[[worktree-location-traps]], [[ci-gates-beyond-the-reactor]].
