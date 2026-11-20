---
name: registry-state-readability
description: "A Consignment output registry state for a file that still holds rows must stay READABLE: every non-readable state is subtracted from every Dataset read (ConsignmentSelector) and deleted by retire_superseded"
metadata:
  type: feedback
---

`DbConsignmentOutputStore.unreadablePaths()` feeds `ConsignmentSelector`, which subtracts those paths from EVERY Dataset
read (LA, BI, `/datasets/{id}/rows`), and `retire_superseded`, which deletes them after `retention_days`. So a new
`ConsignmentOutput.State` is not a label: if its file still sits at the path with live rows, marking it non-readable
silently hides (then deletes) those rows.

**Why:** `BASIC-ELT-PROFILE-1` B4 (2026-11-20) needed "refuse a reprocess" for a file *Replace the period* rewrote in
place. Reusing `COMPACTED_AWAY` (or any state outside `READABLE`) made the falsification read 3 rows where 8 were kept.
The fix was a state that refuses the reprocess but stays readable (`REPLACED`, in `READABLE` beside `LIVE`).

**How to apply:**
- Before adding or reusing a state, ask: does the path still hold rows a reader needs? If yes, it belongs in `READABLE`
  (`unreadablePaths`, `isReadable`, `sourcesForPaths`); decide `dailyVolume` (row counts) separately.
- Prove it over real HTTP with a Dataset read (`GET /datasets/{id}/rows`), not only the store unit test, and falsify by
  dropping the state from `READABLE`.
- Two heals on one path mask each other's probe (B4: the compaction step healed what the pre-run heal was meant to):
  switch the other path off in the test (`import_steps` without `follow_on`) before trusting a red.

Related: [[falsify-negative-tests]].
