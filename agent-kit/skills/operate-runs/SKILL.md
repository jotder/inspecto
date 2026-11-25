---
name: operate-runs
description: Re-run, resume, pause, reprocess, replay rejects, retry or drain an Inspecto run, release a quarantined file, trigger or replay a Job, evaluate Expectations or Alert Rules again, or work a Job's backlog, after the cause is known. Use when the person asks to re-run or fix a failed run, to re-check after a fix, or to clear what piled up, and after diagnose-failed-run says a re-run will help. Covers what happens when the action is held as a Pending Action.
---

# Operate runs

Only after `diagnose-failed-run` has named the cause, and only when acting will fix it. Every action here is a real
action on live data.

## Who you are decides what happens

- **Signed in as a person** (an agent client acting for them): the action runs **at once**, within that person's own
  rights (it needs `canOperateRuns`). The audit row names the person and your agent client.
- **An unattended machine agent**: the action is **held** as a **Pending Action** (`pa-…`) for a person to approve —
  the answer is `202` with `ran: false`. Nothing ran yet. Put what you found in its `reason` (for a quarantined file,
  its quarantine reason and whether it will be set aside again): the approver decides with it.
- **Nothing tells you in advance which you are.** Until an answer shows you are a machine agent (a 202 with a `pa-…`
  id), act as a person's agent: your action runs at once, so every rule below that says "ask first" applies.

## The actions

| Want | Tool | Notes |
|---|---|---|
| Run an ingest run now | `operate_run` `action: trigger` | answers a run id; follow it with `get_run_status` |
| Stop / restart its schedule | `operate_run` `pause` / `resume` | |
| Run one batch again | `operate_run` `reprocess`, `body: {batchId}` | take the batch id from `get_run_batches` |
| Retry rejected rows once the source is fixed | `operate_run` `replay-rejects` | |
| Retry a waiting retry now / give up retrying | `operate_run` `retry-now` / `cancel-retries` | |
| Discard what is queued | `operate_run` `drain` | **destroys queued work** — ask the person first, every time |
| Put a quarantined file back into the inbox | `operate_run` `release-quarantined`, `body: {path, reprocess?}` | take the `path` from `list_quarantined_files`; read its `reason` first — a file set aside for bad content fails again |
| Run a Job now / again | `trigger_job` / `replay_job_run` | `replay_job_run` takes the Job Run id |
| Check the data again after a fix | `evaluate_expectations` (one by `expectation`, or every enabled one) | saves each result; a failed one opens an Incident and notifies |
| Evaluate the Alert Rules now | `evaluate_alerts` | a breach fires an Alert, which can page people |
| Clear a by-day Job's backlog | `operate_job_backlog` `drain` / `pause` / `resume` | read `get_job_backlog` first |
| Run one waiting day now | `operate_job_backlog` `run-day`, `day: yyyy-MM-dd` | only a day `get_job_backlog` lists |
| Stop waiting for one day | `operate_job_backlog` `give-up`, `day` | **discards that day**: its files are marked skipped and never run — ask the person first, every time |

Always pass a one-line `reason` (at most 500 characters): the audit row keeps it, and an approver reads it first.

## Steps

1. Say what you will do and why, in one sentence, and wait for the person's yes — always for `reprocess`,
   `replay-rejects`, `drain`, `release-quarantined` and a backlog `give-up` (name the Job and the day).
2. Call the tool once.
3. Read the answer:

| Answer | Meaning | Do |
|---|---|---|
| 200 / 202 with a run id | it runs | follow it with `get_run_status`; report the outcome |
| 202 with a Pending Action | held for a person (you are a machine agent) | report the `pa-…` id; stop; `get_pending_action` shows the decision later |
| 409 "already pending approval (pa-…)" | the same action is already waiting | do not ask again; report the id |
| 403 "missing capability …" | the person lacks `canOperateRuns` | tell the person; never look for another route |
| 404 | no such run, Job or batch | check the name with `list_runs` / `list_jobs` |
| 409 | the run is not in a state for this action (already running, not paused) | read `list_runs` again; do nothing else |
| 500 | a server fault | stop after one retry and tell the person, with the reason text |

4. Afterwards, check the result: `list_runs`, `dataset_freshness`, `get_job_backlog`; after a fix,
   `evaluate_expectations` says whether the check passes now — report each one's `lastResult.status`. A second failure with the same cause means the
   re-run was the wrong fix — go back to `diagnose-failed-run`; do not loop.

`withdraw_action` takes back a Pending Action you asked for while it still waits.

## Never

- Approve or decline a Pending Action, a Pending Change or an Action Request — a person does.
- Re-run in a loop, or re-run a failure whose cause is config, upstream or the platform.
- Drain, reprocess, release a quarantined file or give up on a backlog day without the person's explicit yes.
- Evaluate in a loop to make a failing check pass: a FAILED result is the answer — go back to `diagnose-failed-run`.
- Release a file whose quarantine reason is not fixed, or guess its `path`.
- Take a backup: there is no agent route for it yet.

## MCP Tools

- `operate_run` — trigger, pause, resume, reprocess, replay-rejects, retry-now, cancel-retries, drain or release-quarantined
- `trigger_job`, `replay_job_run` — run a Job now, or a past Job Run again
- `evaluate_expectations`, `evaluate_alerts` — check the Expectations or Alert Rules again now
- `operate_job_backlog` — drain, pause, resume, run-day or give-up on a by-day Job's backlog
- `get_run_status` — a manual run's state by its run id
- `list_runs`, `get_run_batches`, `list_quarantined_files`, `list_jobs`, `get_job_backlog`, `dataset_freshness` — the state before and after
- `get_pending_action`, `withdraw_action` — a held action's decision, or take it back
