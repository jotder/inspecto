# Dated Reference runs — each record joins the Reference version of its own day, and the backlog drains itself

**State:** 🟡 **PROPOSED 2026-11-22 — direction agreed with the operator; D-DR1…D-DR9 open; nothing built.**
Board row `DATED-REFERENCE-RUNS-1` [items: module.elt, module.engine, layer.product]. Builds on
[`2026-11-22-reference-lookup-plan.md`](2026-11-22-reference-lookup-plan.md) (`LOOKUP-1`: the keyed Reference join,
P1a–P1c built) and replaces that plan's P2 items "as-of each row's event time" and "version used in lineage".

## 1. The problem

A telco enriches call records (CDRs) with **daily** reference files. `DIM_DIAL_DIGIT_20261122.csv` describes the
dialling codes valid on 22 November; a call made on 22 November must be enriched with **that** file, not with
whichever file is newest.

- The daily Reference file arrives in the morning (say 06:00). CDRs for that day start arriving after midnight.
  Records for 22 November cannot be enriched correctly before 06:00.
- **One Consignment, even one file, can hold more than one day** — the end of yesterday and the start of today.
- **Late files are common**: a file for 19 November can arrive on 22 November and must use 19 November's codes.
- Reference files are re-sent with corrections.
- After an outage, hundreds of Consignments are waiting at once.

Today a Job with `on_pipeline` fires **one run per arriving Consignment** (edge-triggered) and joins the
**current** Reference. That enriches 22 November's early records with 21 November's codes, cannot hold them until the
right file arrives, starts one run per file in a surge, and loses work if an event is missed.

## 2. What already exists (reuse, do not rebuild)

| Piece | Where | What it gives |
|---|---|---|
| Consignment output registry | `DbConsignmentOutputStore` (`consignment_outputs`) | per output file: `consignment_id`, `table_name`, `partition_key`, `record_day`, `state`, `event_time_min`/`max` — **which days each Consignment brought** |
| Event-time bounds per partition | `ConsignmentOutputs.boundsByPartition` | the day range a write covered, recorded at ingest and by Pipeline sinks |
| Period arrival mode | `output.load: period` + `period_column`, `ArrivalLoadStep` | a file **replaces the days it covers** — a daily Reference file replaces its day; a re-run replaces its day's output |
| Readable-state rule | `ConsignmentSelector`, registry `state` | readers see only `LIVE`/`REPLACED` files |
| Keyed Reference join | `RowShaper.keyedJoin` (`LOOKUP-1` P1b/P1c) | exact, longest prefix and range matching; each distinct value resolved once |
| Reference by name in a Job | `JobService.pipelineConfigs` (fixed 2026-11-22) | `reference/<pipeline>` resolves in a running server |
| Job triggers | `JobConfig`: `cron`, `on_pipeline`, `on_signal` + `when`, `manual`, `catch_up` | when a Job is considered |
| Signals | the Signal network | a Reference landing can wake work without polling |

## 3. The model

### 3.1 The event-time column (per Stream)

The Stream's onboarding Pipeline names **one date or timestamp column** as the record's event time (`CALL_DATE`,
`START_TIME`, …). It is already the partition key (`partitionKey:`), so each landed file holds one day and the
registry knows the day of every file. The Pipeline also names the **time zone** in which that column is a calendar
day (D-DR2). A row whose event time is missing or unparseable goes to a declared bucket (D-DR3), never to a day it
could block.

### 3.2 Reference versions by business day (per Reference)

A Reference Pipeline declares that each file is the table **for one business day**:

```toon
produces: reference
reference:
  versions: daily                      # one version per business day (D-DR1)
  day_from: file_name                  # DIM_DIAL_DIGIT_{yyyyMMdd}.csv — or a column
  keep_days: 60                        # history kept for late CDRs and re-runs
  key[1]: dial_digit_key
```

- Each file is stored as its day's partition and **replaces that day** (the existing period arrival mode): a corrected
  file re-sent for 22 November replaces 22 November only.
- The registry then answers "is there a version for day D?" and "when was it last replaced?".

### 3.3 The join picks the version of the row's own day

```toon
- join:
    reference: reference/dim_dial_digit
    version_by: CALL_DATE              # each row uses the Reference version for its own day
    if_version_missing: wait           # wait | previous | fail   (D-DR4)
    key: dial_digit_key
    match: longest_prefix
    …
```

- `wait`: rows for a day with no version are not enriched yet (§3.4 holds them).
- `previous`: use the latest earlier day's version; the output records which version it used, so it is re-enriched
  when the right one lands (§3.6).
- Resolve-once still applies, per (day, value).

### 3.4 Scheduling: a work ledger and a reconcile loop (level-triggered)

The Job stops firing once per Consignment. Instead, per Job:

**Work ledger** — derived, not maintained by events:

```
pending(Job, D) = registry files of the Job's input Stream with record_day = D and a readable state
                  MINUS the files this Job has already enriched for D
```

The Job keeps a **progress record**: per day D, which input files it enriched and with which Reference version
(D-DR5: a table beside `consignment_outputs`). Landing a Consignment never starts a run; it only makes `pending` non-empty.

**Reconcile loop** — runs when anything relevant happens (a Stream file lands, a Reference version lands, a run ends)
**and** on a timer (D-DR6, e.g. every 2 minutes), so a missed event costs minutes, never data:

```
for each day D with pending(Job, D) not empty:
    ready    = the Reference version for D exists (or if_version_missing = previous)
               and the Job's input conditions hold (§3.5)
    settled  = no new file for D for `quiet` (e.g. 5 min)  OR  D has waited `max_hold` (e.g. 30 min)
    overdue  = D has waited past its deadline
pick  up to max_parallel days that are ready and settled, by `order`
run   each picked day ONCE over ALL its pending files; the run replaces D's output partition
overdue days take the deadline outcome (§3.5)
```

**What this solves**

| Problem | How |
|---|---|
| When to run | when a day has work, its Reference version exists, and arrivals have settled — not when a file lands |
| The 06:00 surge | every Consignment for 22 November becomes **one** run of day 22 November |
| A file holding two days | its files are already split by day at ingest; each day runs on its own |
| Late files | add to their old day's `pending`; that day runs again with **its own** Reference version |
| Backlog after an outage | the ledger has one entry per day; the loop drains it `max_parallel` at a time, oldest first — fixed throughput, no thundering herd |
| Missed or doubled events | none matter: the loop reads state; a run takes everything pending |
| Retries | safe — a run replaces its day's output partition |

### 3.5 Input conditions, deadline and policy (per Job)

```toon
schedule:
  mode: by_day                    # opt-in per Job (D-DR7); on_pipeline / cron keep working as today
  quiet: 5m
  max_hold: 30m
  order: oldest_first             # oldest_first | newest_first | current_day_first   (D-DR8)
  max_parallel: 2
  deadline: "09:00 next day"      # per day D
  on_deadline: previous           # previous (marked stale) | fail (raises an Incident) | skip
inputs:
  dim_dial_digit:    {role: reference, not_empty: true}
  dim_service_range: {role: reference, covers: CALL_DATE}     # a period exists for every call date
```

Conditions are a fixed set checked against stored facts — version exists, not empty, fresh within N, covers the day —
plus the existing `when` guard over a Signal. No user code decides readiness.

### 3.6 Corrections and the version used

- Every enriched day records **which Reference version** (day, load) it used.
- A corrected Reference file for day D (or the real file arriving after a `previous` run) marks day D's output
  **stale**; with `rerun_on_reference_change: {within: 7d}` (D-DR9, off by default) the loop re-runs only day D.
- Older days stay as they are and are shown as stale.

### 3.7 The Job graph (how the user builds it)

The user builds three onboarding Pipelines and one Job Pipeline, linked by their stores:

```
Pipeline dim_dial_digit      acq → parse → map → ▣ dim_dial_digit      (Reference, daily versions)
Pipeline dim_service_range   acq → parse → map → ▣ dim_service_range   (Reference)
Pipeline cdr                 acq → parse → map → ▣ cdr                 (Stream, by CALL_DATE)

Job cdr_enrich               ▣ cdr ──data──→ join ──→ join ──→ ▣ cdr_enriched   (persistent, by day)
                             ▣ dim_dial_digit ──ref──↗        ↑
                             ▣ dim_service_range ──ref────────┘
```

- A Job Pipeline's input is a **store** (the Stream's Dataset), not a Collector; References are **side inputs** drawn
  as reference edges into their join; the output is a persistent Dataset partitioned by day.
- The **Topology** view joins the four graphs at their shared stores.
- Today a join chain is authored inside the Stream's own Pipeline (`steps:` + `output_store:`) and the editor draws
  it as one line ending in the wrong sink (it shows the landed store `cdr_enrich`, never the Job's output store). This
  plan moves the chain to its own Job Pipeline and fixes the drawing.

## 4. Boundaries — what this will NOT do

| Out of scope | Why | Instead |
|---|---|---|
| Waiting per record or per file | queues explode; nothing merges | the unit is (Job, day) — or another fixed period |
| A general workflow engine (branches, loops, fan-out graphs of Jobs) | nobody can reason about it | linear Steps over stores; chains go through stores |
| User code as a readiness condition | untestable, unsafe | the fixed condition kinds + `when` over Signals |
| Polling an outside system | hides failure | a Collector or webhook turns the outside event into a Signal |
| Waiting forever | silent backlog | every day has a deadline and an outcome |
| "Is day D complete?" | unknowable — more files can always come | every run takes what is pending; a late file re-runs its day |
| Dependencies across Spaces | breaks Space isolation | exchange/sharing publishes a store into the Space |

## 5. Phases

| Phase | Builds | Done when |
|---|---|---|
| **P0 — spike** | measure the registry query for `pending(Job, D)` on 100k files; confirm `acquisition.dataset` (a store as a Pipeline's input) can start a join chain; decide D-DR1…D-DR9 | the operator signs the decisions |
| **P1 — dated References** | `reference.versions: daily`, day from file name or column, `keep_days`; the join's `version_by` + `if_version_missing` | a CDR for 19 Nov arriving on 22 Nov joins 19 Nov's codes; a test per policy |
| **P2 — ledger + reconcile loop** | the Job progress record; the loop; `schedule.mode: by_day` with `quiet` / `max_hold` / `order` / `max_parallel` / deadline; Waiting state on Runs | the 06:00 surge is one run per day; an outage backlog drains oldest-first at `max_parallel` |
| **P3 — output by day + corrections** | output partitioned by event day, replaced per run; version-used record; stale marking; opt-in re-run on Reference change | a corrected Reference for 22 Nov re-runs 22 Nov only |
| **P4 — screens and alerts** | Runs ▸ Backlog (per Job: days waiting and why, ready, running, oldest waiting age, drain estimate; Run now, Pause, Drain, Give up waiting); Alert Rules over backlog age, missing Reference, deadline | an operator sees and clears a backlog without the CLI |
| **P5 — the Job graph** (independent; can run in parallel) | planned in [`2026-11-22-job-graph-plan.md`](2026-11-22-job-graph-plan.md) (`JOB-GRAPH-1`): Job Pipeline with store inputs and reference edges; Topology joins it to the onboarding Pipelines; the misplaced-sink drawing fixed | the four graphs above draw and save as shown |

## 6. Decisions (open)

- **D-DR1** Version granularity: `daily` only first, or `daily | hourly | monthly` from the start.
- **D-DR2** The calendar zone of an event day: per Space default with a per-Pipeline override, or per Pipeline only.
- **D-DR3** Rows with no usable event time: quarantine, or an `unknown` day that never waits.
- **D-DR4** Default `if_version_missing`: `wait` (correct, slower) or `previous` (fast, re-run later).
- **D-DR5** Where the Job progress record lives: a table beside `consignment_outputs` (recommended) or a column on it.
- **D-DR6** Reconcile timer: 2 minutes (recommended) or per Job.
- **D-DR7** Opt-in per Job (`schedule.mode: by_day`, recommended) or the default for every Job with a dated Reference.
- **D-DR8** Default `order`: `oldest_first` (recommended) or `current_day_first`.
- **D-DR9** Re-run on Reference change: off by default (recommended), window default 7 days.

## 7. Proposed vocabulary (added to `GLOSSARY.md` when P1 starts)

- **Event day** — the calendar day of a record's event time, in its Pipeline's declared zone.
- **Reference version (dated)** — the Reference table valid for one business day.
- **Work ledger** — per Job, the days with input not yet processed; derived from the Consignment output registry.
- **Waiting** — a Run state: work exists for a day but its inputs are not ready; ends at the deadline.
