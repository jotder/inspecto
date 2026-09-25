---
type: Feature
title: Runs (Operations)
description: The ingest-operations list — one row per running Pipeline — with run-now/pause/history actions; rows open the run detail.
resource: inspecto-ui/src/app/modules/admin/runs/runs.routes.ts
tags: [feature, runs, operations, workbench]
timestamp: 2026-09-25T00:00:00Z
---

# Runs (Operations)

Route `/runs` (Workbench nav group), dir `modules/admin/runs/`. The operations view over executing
Pipelines: a **standard** [data-table](../design-system/data-table.md) (`autoHeight`) of the `RunView` /
`RunResult` / `RunStatus` models with per-row run-now/pause/history actions; row-click opens
[run detail](run-detail.md) (`/runs/:name`). Backed by `RunsService`.

**Run now** follows the v1 async contract (W5b): the trigger returns `202` + `{runId, …}` and the pane shows
a `Run "<name>" started.` toast. The 202 arrives while the run is still going, so the immediate list re-fetch
races it (a run that finished in 0.3 s still showed `Committed 0` until a manual refresh — fixed 2026-09-25).
The pane therefore also polls `GET /runs/runs/{runId}` via `RunsService.awaitRun` — backoff
`RUN_POLL_BACKOFF_MS` (500 ms, 1 s, 2 s, 4 s, then every 5 s), capped at `RUN_POLL_MAX` = 60 polls — and
re-fetches the list once the run leaves `RUNNING` (or a poll fails: 404 = evicted/unknown, or the cap is
hit). This runs whether or not *Auto* is on. Poll delays use `visibleDelay`, so a hidden tab stops polling
and resumes on return; the poll is torn down with the pane (`takeUntilDestroyed`).

**Auto** (toggle, on by default) re-fetches the list every `DEFAULT_REFRESH_MS` = **15 s** via
`visibleInterval` — paused while the tab is hidden, and skipped while the reprocess dialog is open. Grounded
2026-09-25: this clock was working; the stale row came from the trigger race above, not from Auto. Neither
refresh is a server push — there is no SSE on this pane.

**Business lens is read-only observe here** (product decision 2026-07-03): trigger/pause/resume/reprocess
are hidden *and* method-guarded on `lens.readOnly()` (defense-in-depth) — a deliberate pane-specific
exception to the Jobs precedent, where run-now/enable stay operational in every lens. New panes default to
the Jobs heuristic unless the plan says "read-only observe" this explicitly.
