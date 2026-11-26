---
type: Reference
title: KPI History — the kpi.snapshot Job and its Dataset
description: How a KPI's value per period is stored (BI-INSIGHTS-1 P0 I-3) — the kpi.snapshot Job, the kpi_history_<kpi> Dataset, its rules and limits
tags: [kpi, history, job, dataset, bi-insights]
timestamp: 2026-11-24T00:00:00Z
item: module.engine
audience: internal
status: current
verified: 2026-11-24 code-reviewed
code: platform/inspecto-engine
---

# KPI History — the `kpi.snapshot` Job and its Dataset

`BI-INSIGHTS-1` P0, capability I-3 (plan [`2026-11-18-business-insights-plan.md`](../../../../superpower/2026-11-18-business-insights-plan.md)
§11.1). A KPI is evaluated on demand (`GET /kpis/{id}/value`); KPI History stores its value **per period at the KPI's own
grain** so a tile can draw a sparkline and later phases (forecast bands, anomaly markers, projections) have a series.

## The Job

| | |
|---|---|
| Type | `kpi.snapshot` (`inspecto/…/control/KpiSnapshotJobType.java`, registered through `META-INF/services/com.gamma.job.JobTypeProvider`) |
| Parameter | `kpi` — the KPI id. One Job per KPI, scheduled like any Job |
| Signal | `kpi.snapshot.completed` (INFO): `kpi`, `dataset`, `evaluated`, `rows` |
| Dry run | writes nothing |

A run evaluates every period the history lacks, from the later of the data's first day (`KpiEvaluator.earliestDay`)
and three years back (D-BI10), plus the **current** and the **previous** period on every run — late data restates one
closed period, no more. Rows older than three years are dropped. The merged history replaces the KPI's one parquet
file atomically (`ScoreOutputDirs.swapIn`, under the snapshot-swap lock). Engine side: `com.gamma.query.KpiHistory`.

## The Dataset `kpi_history_<kpi>`

| Column | Type | Meaning |
|---|---|---|
| `kpi_id`, `grain` | VARCHAR | the KPI and its grain (`day` … `year`) |
| `period_start`, `period_end` | DATE | the period; `period_end` is exclusive |
| `value` | DOUBLE | the Measure over the period; **NULL when the period holds no value — never 0** |
| `rag` | VARCHAR | `GREEN` / `AMBER` / `RED` from the KPI's target or bands; NULL without them |
| `complete` | BOOLEAN | `false` for the period in progress, rewritten each run until it closes (D-BI12) |
| `snapshot_at` | TIMESTAMP | when the file was written |
| `definition` | VARCHAR | the KPI definition the row was computed under (Dataset, Measure, time field, grain, zone, direction, target, bands) |

- **A changed KPI rebuilds its history.** A run keeps only rows of the KPI's current `definition`: a changed grain,
  Measure, zone, Dataset, time field or target re-evaluates every period instead of mixing two definitions' periods.
- **One Dataset per KPI**, never a shared table: a shared one would show every viewer KPIs over Datasets they cannot read.
- **A system Dataset** (`origin: system`, tag `kpi-history`), registered by the Job **regardless of a Dataset approval
  policy** (D-BI11, operator 2026-11-24). Its `owner` / `shares` admit only who may read **both** the KPI and its
  Dataset: an unshared one is open, so the other's envelope applies; two shared differently refuse the run (an
  envelope cannot state an intersection). When the Dataset is a Mapping (`origin: mapping`), its `basedOn` base joins
  the envelope, recursively through Mappings (`SharingEnvelope.of(store, …)`, operator 2026-11-26); a base shared
  differently refuses the run, and a base that does not resolve refuses it too. The envelope is re-applied before
  every write.
- **Reserved prefix.** A Dataset or sink naming a `kpi_history_` store is refused at save
  (`KpiSnapshotJobType.requireNotReserved`, run from `ComponentRoutes.validateKind`), except the history Dataset itself
  (`id = physicalRef = kpi_history_<kpi>`) over a saved KPI. A Dataset of that id that reads anything else is never
  overwritten by the Job — the run fails.

## Refusals

- **Runs as its author**, as `publish.postgres`: a KPI or a source Dataset the Job's author may not view fails the run,
  worded as for an absent one (`PostgresPublishJobType.canView` / `canRead`).
- **The KPI is the saved Job's.** A trigger argument, a manual trigger body or a Signal binding naming another KPI
  fails the run (as `la.index.build`): it could otherwise point the Job, under its author's rights, at any KPI.
- **Never reveals.** A Measure that returns raw values of a masked column (`max(msisdn)` and the like,
  BI-QUERY-MASKING-1) fails the run: the stored value would outlive the reader's masking.
- A history directory another owner made (no `.kpi-history-output` marker naming this KPI) fails the run untouched.

## Limits (deliberate)

- A run evaluates one window per period (one query each). The first run of a `day` KPI over three years is about
  1,100 queries; later runs evaluate two or three.
- A correction older than the previous period is not restated. Re-create the history (delete the store) to restate all.
- A KPI and its Dataset shared with different people cannot have a history until they are shared alike.

## Tests

`KpiHistoryTest` (engine: period selection, partial vs complete, retention, the stored file, a changed grain, a foreign
directory), `KpiSnapshotJobTest` (a real JobService run, the system Dataset and its envelope, the masking, Dataset-author,
KPI-author and run-override refusals — each falsified by removing its guard, the reserved prefix),
`ControlApiKpiTest.aDatasetOverAKpiHistoryStoreIsRefusedAtSave` (the save route), `KpiSnapshotJobTypeTckTest`.
