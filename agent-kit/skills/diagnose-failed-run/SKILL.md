---
name: diagnose-failed-run
description: Find out why an Inspecto run or Job Run failed, went stale or parked rows, before anyone re-runs anything. Use when the person asks why a run failed, why a Dataset is stale, why an Incident or Alert was raised about a run, or before operate-runs.
---

# Diagnose a failed run

Read first, act later. A re-run that repeats the cause only adds a second failure. Every step here is a read; nothing
changes.

## 1. Find the failure

1. `health` — is a subsystem down (storage, scheduler, a Connection)? A platform fault is not the run's fault: stop and
   tell the person.
2. `list_runs` — the ingest runs and their last result; note the run (Pipeline) name, whether it is paused, and when it
   last succeeded.
3. `list_job_runs` (optionally with `job`) and `get_run` — a Job Run's state, timings and failure reason.
4. `dataset_freshness` on the run's output Dataset — how stale the data is, which is what the person usually feels.

## 2. Find the cause

- `get_run_batches` with the run name: which batch failed, how many rows were rejected or parked, and the error text.
- `list_quarantined_files` with the run name: whole files the run set aside (empty, corrupt, a duplicate), each with
  its reason.
- `list_incidents` and `get_incident` / `get_incident_briefs` — an Incident may already explain the failure.
- `get_lineage` on the output Dataset — an upstream Dataset that did not refresh starves this run.
- `get_pipeline` — what the run does; read only the steps the error names.

Sort the cause into one of four:

| Cause | Sign | Who fixes it |
|---|---|---|
| Source data | rejected rows, a parse or type error on some rows | the data provider; replaying rejects may help once fixed |
| Config | every row fails the same step; started after a change | a config change — hand over to `safe-config-change` |
| Upstream | an input Dataset is stale or missing | the upstream run first |
| Platform | `health` shows a fault, timeouts, storage full | the administrator; do not re-run |

## 3. Report

Tell the person, in their words: what failed, since when, the cause and the evidence (the batch, the error line, the
Incident), and the one next step you recommend. Only a **transient** cause (a timeout, a source that was briefly
unavailable, now back) is fixed by a re-run — then use `operate-runs`.

## Never

- Re-run, reprocess or drain while diagnosing — this skill only reads.
- Guess a cause the evidence does not show; say what you could not see.
- Quote masked values back unmasked; a masked value stays masked.

## MCP Tools

- `health` — per-subsystem health of the server
- `list_runs`, `get_run_batches` — the ingest runs, and one run's batches with their errors
- `list_quarantined_files` — the files a run set aside, and why
- `list_job_runs`, `get_run` — Job Runs and one Job Run's failure reason
- `dataset_freshness`, `get_lineage` — how stale an output is, and what feeds it
- `list_incidents`, `get_incident`, `get_incident_briefs` — what was already raised about it
- `get_pipeline` — what the run does
