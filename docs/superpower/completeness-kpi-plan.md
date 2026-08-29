# Pipeline completeness KPI — a scheduled per-pipeline job

**Status:** operator-decided 2026-08-30, ready to build. Supersedes this file's first draft (a slicing
of Consignment §8 sealing), which the operator's answers **replaced outright**.

**What changed and why:** the gate register carried "Consignment §8 end-of-period summary pass ·
§11.4 `partition_state`" as two rows. Grounded, §8's design answers completeness with a
Consignment-landing-triggered **sealing state machine** (`OPEN → SEALED → REOPENED`, a lateness
horizon, no schedule anywhere). The operator's actual requirement is different: **gap/sequence
analysis and file/record count deviation, as a KPI that is not a Consignment concept, computed by a
daily or scheduled job per pipeline.**

🔴 **This deliberately overrides `consignment-elt-architecture.md` §8's central claim** — *"'End of
day' is a completeness condition, not a clock"* and *"this solves the motivating 'scheduled
end-of-day summary' problem with no schedule anywhere"*. The operator was shown that contradiction
explicitly and chose the scheduled job (2026-08-30). **§8's sealing design and §11.4's
`partition_state` table are dropped, not deferred** — no `OPEN/SEALED/REOPENED`, no lateness horizon,
no seal signals. Mark §8/§11.4 superseded rather than leaving them to read as pending work.

---

## 1. Decisions (operator, 2026-08-30 — ⛔ do not re-ask)

| # | Decision |
|---|---|
| Scope | **Replaces sealing.** Drop `partition_state`, the state machine and the horizon chain. |
| File deviation | **From the sequence template** — exact, because the template already implies how many files a period should hold. |
| Record deviation | **From a rolling prior-period baseline** — statistical; there is no declared expected row count and inventing one was refused. |
| Count source | ~~CommitLog~~ 🔴 **REFUTED — see §2.** Use the `consignment_outputs` registry. |
| Job shape | **One job config per pipeline**, following `ReconRunJob` — no fan-out machinery. |

---

## 2. 🔴 The count source was wrong, and the correction is load-bearing

The operator chose "CommitLog / batch ledger". **It cannot answer the question.**

- `CommitLog` (`inspecto-etl/src/main/java/com/gamma/etl/CommitLog.java:29,39`) persists
  `committed_at, consignment_id, pipeline, status, member_count, output_count, output_rows,
  output_bytes` — **one row per batch, no partition or day column**. A batch spans multiple
  record-days and a record-day receives rows from many batches, so per-day counts are not derivable.
  (It is fsync'd and default-on — durable, just not shaped for this.)
- The `_lineage_<runTimestamp>.csv` ledger (`BatchAuditWriter.java:52-53`) *does* carry
  `partition,row_count` per record-day — but it is buffered rather than fsync'd, and is written **one
  file per run**, so reading it means globbing many files and can lose a tail on a crash. Not a
  ledger to build a KPI on.
- ✅ **Use `consignment_outputs`** (`ConsignmentOutput.java`, `DbConsignmentOutputStore.java:56,83,132`):
  a durable DuckDB registry with `record_day`/`bounds()` **and** a per-output-file `rows` count,
  written from the ordinary ingest path (`BatchProcessor.finalizeSource`), **on by default since
  2026-08-10**. Counts are already computed per record-day by
  `LineageCollector.java:56-66` (`GROUP BY __src_id, year, month, day`).

⚠ **Naming tension, stated rather than hidden:** the operator's framing is "a KPI *not related with*
Consignment", and the chosen store is *named* `consignment_outputs`. The coupling is nominal — it is
the ordinary per-output-file registry on the normal write path, not a Consignment-only structure. If
the name later matters, that is a rename, not a redesign.

### Two ways this can silently report nothing 🔴

Both must fail loudly, because a completeness KPI that quietly reports zero is worse than one that is
absent — it manufactures false confidence in exactly the number it exists to check.

1. **`-Dconsignment.outputs.backend=none`** makes recording a no-op (fail-open by design,
   `ConsignmentOutputStores.java:23-28`). The job must **refuse and say so**, never emit zeros.
2. **`bounds` is nullable** for sinks with no partition or event time (`ConsignmentOutput.java:48-53`)
   — dataset/enrichment sinks. Those pipelines' daily counts are **UNKNOWN, not zero**, and the KPI
   must carry that distinction end to end.

*(Precedent for the grouping: `ReportServiceTest.java:98-101` already does
`SELECT year, month, day, COUNT(*) … GROUP BY year, month, day`.)*

---

## 3. What already exists (do not rebuild)

- **Gap detection is live in production.** `GapDetector.findGaps(template, names)`
  (`inspecto-acquire/.../GapDetector.java:35-158`) finds holes in a **filename date/time sequence**
  (e.g. `CDR_{yyyyMMddHH}`), returning `GapReport(observed, missing, unit)`; `GapTracker` de-dups per
  Collector so a persistent gap fires once. Surfaced as `SEQUENCE_GAP`
  (`AcquisitionTelemetry.emitSequenceGap:42-51`) from the poll loop (`CollectorProcessor.java:573`),
  promoted to an ALERT by `EventObjectBridge:51`. ⚠ **In-memory only — nothing persists a daily
  count**, which is precisely the gap this job fills.
- **The Job SPI and cron.** `Job` (`inspecto-engine/.../job/Job.java:10-36`), `JobTypeProvider` +
  `JobTypeRegistry` (open, string-keyed; the `JobType` enum is `@Deprecated`), registered in
  `JobService.java:349-491`. Cron via `CronExpression`, armed at `JobService.armCron:543-556`.
  **Template: `ReconRunJob` (`.../job/ReconRunJob.java:35-144`)** — reads a target from `JobConfig`,
  computes, emits a Signal, opens a deduped Incident on breach. That is this job's exact shape.
- ⛔ **`ConservationCheck` is NOT this KPI and must not be conscripted into it.** It checks node-level
  row invariants (map/filter/dedup conserve rows) per pipeline run, and it is **default-off** behind
  `-Dprovenance.backend` (`PipelineJobRunner.java:299,447-470`). Different question, different
  trigger, silently absent by default.
- ⚠ `AcquisitionTelemetry` counts **files, not rows**, as live Prometheus-style counters with no
  durable per-day dimension — history cannot be back-filled from it.

---

## 4. Slices

| # | Slice | Verify gate |
|---|---|---|
| **K1** | A durable per-(pipeline, record-day) read over `consignment_outputs`: files + rows received. Refuses loudly when the registry is disabled; reports UNKNOWN (never 0) where `bounds` is null | A day receiving rows from several batches sums correctly; a disabled registry refuses rather than returning zeros; a null-bounds sink reads UNKNOWN |
| **K2** | File-count deviation from the **sequence template**, reusing `GapDetector` rather than a second implementation | A missing file in the middle of a period is reported with its expected name; a period with no template degrades honestly instead of guessing |
| **K3** | Record-count deviation from a **rolling prior-period baseline**, with the window and sensitivity as job parameters | A steady pipeline reports no deviation; a halved day breaches; ⚠ an empty history must read as "no baseline yet", not as a 100% drop |
| **K4** | The `kpi.completeness` job type: `JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, **one config per pipeline** (`ReconRunJob` shape). Emits a signal; opens a deduped Incident on breach | Real cron arming; a breach opens exactly one Incident across repeated runs; a pipeline whose registry is off fails the run visibly |
| **K5** | Retire §8/§11.4: mark the design sections superseded by this plan, with the reason | No doc still presents sealing as pending work |

**K1 is the whole risk.** K2–K4 are assembly over existing parts; K1 is the only slice that has to be
right about what the data actually says.

---

## 5. Open, but not blocking

- **Signal type naming** — `kpi.completeness.evaluated` / `.breached`. ⚠ The design's §11.5 note still
  applies: **do not grow the `EventType` enum**; these ride `Signal` on one `SIGNAL` Event, and a
  constants class should land before ~10 string literals do.
- **Baseline window default** (K3) — a job parameter with a sane default is fine here; unlike a
  retention window, a wrong baseline window is visible and harmless to change.
