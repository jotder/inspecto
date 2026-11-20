---
type: Concept
title: Consignments — addressing and concurrency
description: Consignment addressing (event time at write, the Selector, revisions, the Watermark, three storage invariants) and concurrency (the four-layer hierarchy, the ConcurrencyBroker, named execution pools, the run budget, hot-apply, precedence, IntakeGovernor vs the broker, per-Pipeline cadence, operating it).
resource: platform/inspecto-etl/src/main/java/com/gamma/etl/StreamingFileIngester.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestor.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PipelineConfig.java, platform/inspecto-engine/src/main/java/com/gamma/consignment, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConcurrencyBroker.java, platform/inspecto-etl/src/main/java/com/gamma/etl/DuckDbCsvIngester.java, platform/inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Consignments — addressing and concurrency

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. [spec-ingest.md](spec-ingest.md) · **spec-consignments.md** · [spec-parsing.md](spec-parsing.md)

**Contents**

- [Part A — Addressing](#part-a--addressing)
- [A.1 The one rule everything follows](#a1-the-one-rule-everything-follows)
- [A.2 Event time at write](#a2-event-time-at-write)
- [A.3 The Selector](#a3-the-selector)
- [A.4 Revisions — how a recompute stays safe](#a4-revisions--how-a-recompute-stays-safe)
- [A.5 The Watermark — completeness](#a5-the-watermark--completeness)
- [A.6 Three storage invariants that are cheap now and unfixable later](#a6-three-storage-invariants-that-are-cheap-now-and-unfixable-later)
- [Part B — Concurrency (the four-layer hierarchy)](#part-b--concurrency-the-four-layer-hierarchy)
- [B.1 The grain rule everything follows](#b1-the-grain-rule-everything-follows)
- [B.2 `ConcurrencyBroker` — one monitor, three counters, weighted grants](#b2-concurrencybroker--one-monitor-three-counters-weighted-grants)
- [B.2a Named execution pools — admission only (2026-09-24, `DUCKLE-C10-ADMISSION-POOLS-1`)](#b2a-named-execution-pools--admission-only-2026-09-24-duckle-c10-admission-pools-1)
- [B.3 ⛔ The run budget is NOT redundant with the broker](#b3--the-run-budget-is-not-redundant-with-the-broker)
- [B.4 Hot-apply: what changes live, and how a shrink behaves](#b4-hot-apply-what-changes-live-and-how-a-shrink-behaves)
- [B.5 Precedence and provenance (the "two declarations" rule)](#b5-precedence-and-provenance-the-two-declarations-rule)
- [B.6 Two mechanisms, two questions — `IntakeGovernor` vs the broker](#b6-two-mechanisms-two-questions--intakegovernor-vs-the-broker)
- [B.7 Per-Pipeline cadence and fetch parallelism](#b7-per-pipeline-cadence-and-fetch-parallelism)
- [B.8 Operating it](#b8-operating-it)
- [B.9 Decision 2026-09-06 — the fetch lane stays FIFO](#b9-decision-2026-09-06--the-fetch-lane-stays-fifo)

**What this covers.** A Consignment is a set of files ingested and processed together as one unit of
work. This part answers two operational questions about them. Part A: once Consignments have written
their output files, how does a reader pick the right files — skipping old revisions after a recompute —
and how can anything tell which files cover a time window and whether that window is complete? Part B:
how many Consignments may run at once, per Pipeline, per Space and per server, how priority shares the
free slots without starving anyone, and which settings change live.

Vocabulary follows [`GLOSSARY.md`](../../../../GLOSSARY.md): **Consignment**, **Pipeline**, **Collector**,
and §6-B (**Consignment Selector**, **Watermark**).

## Part A — Addressing

> **Scope:** how a read decides *which files it names*, and how a Consignment's event-time extent is
> recorded so anything can answer "which files can contain this window" and "has this window closed".
> As-built from the consignment-addressing plan, delivered 2026-08-10
> ([archived plan](../../../../archived-documents/plans-archive/consignment-addressing-plan.md)).

> **⚠️ Keep this current.** Derived from `ConsignmentSelector`, `StreamWatermark`,
> `ConsignmentOutputs`, `DbConsignmentOutputStore`, `PartitionSinkWriter`, `DataTransformer` and
> `MaintenanceJob`. The catalog's DDL lives in [spec-db-layer.md §3.9](../engine/spec-db-layer.md); the retirement task in
> [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md).

## A.1 The one rule everything follows

**An optional index may never be the authority for what exists.** The `consignment_outputs` catalog is
fail-open by construction — a configured `none` or a failed open leaves no registry, and the
per-Consignment JSON manifest stays authoritative for a file's *existence* while the catalog is
authoritative for its *state*. Every design decision below falls out of that:

- The Selector **filters** a glob; it never produces the file list.
- A file with **no catalog row stays in** the list. Unknown is a possible match, never an exclusion.
- Null bounds mean **unknown**, never *empty*.

The practical consequence is that switching the catalog on changes nothing a reader sees, and an
installation whose data predates the registry needs no backfill. There is no migration.

## A.2 Event time at write

Bounds are recorded per **output file**. Two write paths can establish them, each from a declaration it
already holds — neither ever infers which column is temporal, because a relation's only `TIMESTAMP`
column is not evidence that it *is* event time:

| Path | Source of event time | Absent when |
|---|---|---|
| Ingest (`ConsignmentIngestor` → `DataTransformer`) | `__event_time`, coerced from the schema's date `PartitionDef` and **excluded from written output** | no date partition declared, or every row failed to parse |
| Pipeline sink (`PartitionSinkWriter`) | `TRY_CAST(<source> AS TIMESTAMP)`, where `source` is a `partitions[]` entry's declared raw column | no entry declares one, entries disagree, or it is not a plain identifier |

Enrichment and the Consignment processor **record a producer since 2026-08-10**, and both record bounds since
2026-08-11 — but by opposite mechanisms, because they are opposite problems:

| Writer | `producer` | `bounds` |
|---|---|---|
| Enrichment (`EnrichmentEngine`) | the enrichment's own `cfg.name()`, on the main **and** routed/quarantine writes | **SHIPPED 2026-08-11.** An `output.partitions` entry may be the sink's `{column, source}` map instead of a bare name; the parser folds the declared sources to `Output.eventTimeSource` and `boundsOf` runs the same `TRY_CAST(<source> AS TIMESTAMP)` fold as the sink |
| Consignment processor (`ConsignmentProcessJobType`) | the **processor id**, threaded through `SummaryWriter.write` | **SHIPPED 2026-08-11 — stated, not derived.** `SummaryRow` gained an optional `EventTimeBounds bounds`; `SummaryWriter` folds the declarations per output file. See A.2-A |

⚠ **Neither writer was ever missing its registry row** — both always called `ConsignmentOutputStores.record`; the
two columns just arrived null. Worth knowing before someone goes looking for an absent write path.

⚠ **The producer is not the table.** Enrichment's routed/quarantine write goes to `dest`, which more than one
enrichment can write, so `dest` would be a useless producer; and the process job's producer is the *processor*,
not the Job Type, because two processors can summarise one target and `producerHighWater` groups by producer.

### A.2-A Summary bounds are *stated by the processor*, never derived

Every other writer folds bounds **out of** the rows it is writing. A summary row has no rows — it is already an
aggregate — so the only party that ever saw the detail is the processor that aggregated it. That inverts the
mechanism: `SummaryRow` carries an optional `EventTimeBounds bounds`, built with `EventTimeBounds.of(min, max)`,
and `SummaryWriter.boundsByPartition` folds those declarations per output file.

⛔ **Do not "simplify" this into a pointer at a grain key** (the shape the backlog originally proposed: "declare
which key is event time"). `record_day` is the *bucket* the rows fell into, so reading it as an event time
collapses a whole day to its first instant, and the Selector then **skips a file that does overlap the query** —
a false negative, strictly worse than the null a reader correctly treats as "cannot prune". A grain key is only
a correct event time in the degenerate case where the grain *is* an instant, which the stated form expresses
anyway as `of(t, t)`.

Three properties fall out, each pinned by a test:

- **A file is bounded only when every row in it declared a range.** One silent row means the file holds events
  outside the fold, and a bound that under-covers its file is exactly the false negative above. One undeclared
  row drops the whole file's bound. *(Verified by disabling the check and watching the test go red.)*
- **`spreadMs` is derived, never stated.** `EventTimeBounds.of` computes it, `GuardedSummaryEmitter` refuses a
  hand-built bounds whose spread disagrees with its own endpoints, and the per-file fold re-derives it from the
  folded endpoints rather than summing per-row spreads (which would double-count overlap).
- **The endpoints must be ISO-8601 local date-*time*.** Enforced at `emit()`, not at write time, because the
  registry compares them lexicographically in SQL — a bare `2026-07-01` would compare wrong rather than fail,
  and it is precisely the grain-as-event-time mistake arriving in another costume.

Absence stays legal and stays the default: a processor that declares nothing records `null`, exactly as every
processor written before this did. Nothing in the `ConsignmentProcessor` interface changed, so the three in-repo
implementers needed no edit — ⚠ contrary to this item's "breaking for every implementation" framing, which
assumed the pointer shape. `tools/templates/processor/` was updated anyway, because its job is to *teach* the
capability: a new processor that never learns bounds exist is the failure the template exists to prevent.

⚠ **The enrichment and sink declarations are the same shape but NOT the same reader.** `EnrichmentConfig`
folds its own copy (`eventTimeSourceOf`) because it parses `.toon` into a record at load time, while
`SinkPartitions` reads a node config map at write time. They are kept behaviourally identical on purpose —
same four null cases, same identifier guard — so an author who writes one declaration gets one answer. If a
third writer ever wants it, extract the fold rather than adding a third copy.

The sink declaration has **one reader**: `SinkPartitions` (`pipeline/exec`), shared by the writer that acts on
it and the `ComponentPreview` that predicts what it will do. Keeping that logic in both files let them disagree
twice in one day, each time costing an author their bounds with no signal anywhere — so `eventTimeSource` is
derived from `declaredSources` rather than parsing the list again, and the preview warns at authoring time on
exactly the declarations the writer refuses: none declared, two that disagree, one blank, one that is not a
plain identifier. Deliberately **not** unified with `PartitionDef.fromSchema` on the ingest side — same config
word, different contract (`type` required per entry, non-list hard-fails, legacy `partitionKey` fallback; see
[§3](spec-ingest-plugin-ingester.md#3-segment-schemas-and-the-partition-declaration) above).

⚠ An entry declaring **no `column`** (or a blank one) is the single `partitions[]` defect this path refuses
rather than degrades past. It names no directory segment, so accepting it silently writes the store to a layout
its readers do not glob for; it previously stringified the whole entry into a directory named `{source=…}`.
`PartitionSinkWriter` throws before writing a byte, and the preview warns while the config is still editable.

⚠ **Why not the dataset's `role: temporal`?** Because reaching a dataset *from* a store name is a
reverse lookup that is ambiguous by construction (`putIfAbsent`, first-scan-wins), so the bounds would
depend on directory scan order. `DatasetRelation.temporalColumn` therefore has no caller on any write
path, and the general principle is: **a write path must read a declaration it already holds.**

`record_day` is derived from these bounds when a file's event times share a day, else from the
`year`/`month`/`day` partition segments. It is superseded — one day per file cannot express a file that
straddles two — and nothing in the engine reads it.

## A.3 The Selector

```
resolve(glob) = glob  MINUS  paths the catalog marks SUPERSEDED / COMPACTED_AWAY
```

Two enumerators, because only one of the seven readers in the product takes a `Connection`:

- **With a connection** (`SourceStoreReader`) — DuckDB's own `glob()` expands the very pattern the read
  will use, so the two cannot disagree about what exists. This is the better mechanism.
- **Without one** (`DatasetRelation`, whose `relationSql` has call sites in three modules) — a
  filesystem walk, kept to exactly the `<root>/**/*.<ext>` shape all readers use. It **skips hidden
  segments**: `PartitionCompactor`'s safety model depends on its intermediates being invisible to
  readers' globs, and a walk that promoted a `.staging/` tree into an explicit list would make data
  appear in reads that was never there.

Both sides of the path comparison go through the same normaliser, because the registry stores the
writer's own spelling — which may be relative — while an enumerator answers in its own. Comparing raw
would match nothing and report success.

**A path with any `LIVE` row is never excluded**, whatever dead rows also name it: output naming is not
one-file-per-Consignment, so one path can own an old `SUPERSEDED` row and a current `LIVE` one. A `REPLACED` row
(`BASIC-ELT-PROFILE-1` B4: a later arrival replaced some of the file's rows) is readable too: the file keeps its path
and the rows the replacement kept ([spec-arrival-replace.md](spec-arrival-replace.md)).

⛔ **Not a pruner.** Bounds-based window pruning is deliberately unbuilt: a file whose bounds miss the
window contains no matching rows, so the query's own predicate already excludes them — identical
answers, pure performance, and the measured ceiling was 1.3–2.6× wall-clock (rung A) against 29–88×
fewer rows scanned, because DuckDB already skips row groups on Parquet statistics.

✅ **Torn multi-file reads across a recompute — CLOSED 2026-08-29.** Subtraction always fixed *stale
inclusion*; the still-open half was that an empty exclusion set fell back to the caller's own **live
glob string**, which DuckDB re-expands against the filesystem at actual scan time — so a file written by
a concurrent recompute between `resolve()`'s enumeration and the query's own scan could be silently
pulled into a read the catalog never approved. `ConsignmentSelector` now **always pins** the enumerated
file list to an explicit array once any registry exists, falling back to the live glob only when there
is no registry at all or enumeration itself fails. ⚠ **Accepted tradeoff:** the SQL always carries an
explicit file array now (no longer a bare glob string in the common no-exclusions case), and a pinned
list can fail loudly if `retire_superseded` deletes one of its files mid-read, where the old glob
fallback would have silently rescanned around it — judged the lesser risk against a silent wrong answer.
Regression-pinned by `ConsignmentSelectorTest.aFileWrittenAfterResolveIsInvisibleToTheAlreadyPinnedRead`:
a file written after `resolve()` runs is provably absent from the pinned read it returns.

### Which readers are filtered, and which deliberately are not (2026-08-28)

Filtering belongs on readers of **pipeline sink** stores — the only stores a full recompute can leave an
old revision in. Traced per reader:

| Reader | Filtered? | Why |
|---|---|---|
| `SourceStoreReader` | yes — `ConsignmentSelector.resolve` | reads a sink store on a connection |
| `DatasetRelation` (`physicalRef`) | yes — `sourceLiteral` (walks; no connection in scope) | the dataset read of a sink store |
| `DatasetRelation` (`view`) + `ViewQuery` | yes — **rendered at read time**, see below | executes a *persisted* definition |
| `DbBrowserRoutes.browseStore`, `ExpectationEvaluator` | yes — `sourceLiteral` (since 2026-09-06) | both read a pipeline sink store and were missing from this table: the Data Browser re-globbed at scan time and showed a superseded revision during a recompute; an Expectation counted it |
| `EnrichmentEngine` ×2, `ConsignmentIngestStrategy` | ⛔ no, correctly | read Stage-1 ingest output / the ingest-written reference store — append-only, never `supersedeOtherRevisions` targets, so no catalog-marked file can appear |
| `ReferenceCompactor` | ⛔ no, correctly | owns an equivalent safety model (`*.refcompact.tmp` / `*.parquet.refcompacting` + journal) — a second authority, not a missing one |

**A persisted read has to be rendered, not stored.** `PipelineJobRunner.deriveViewSql` builds a
`sink.view`'s SQL once and it is executed arbitrarily later, so the source read is written as the
`ViewReaderSql.READER_TOKEN` (`{{reader}}`) placeholder with `reader_root`/`reader_format` recorded
beside it, and every executor renders it through the Selector at read time. The two alternatives are
both wrong in opposite directions, which is why the template exists: a **baked-in glob** keeps reading a
revision the catalog has since superseded (silent double-counting — the defect this closed), and a
**baked-in file list** breaks loudly the moment retention deletes those files. Plain SQL — hand-authored
views, definitions written before the template — executes verbatim, so this needed no migration; a
templated definition that has lost its reader fields **refuses**, because rendering it unfiltered would
reintroduce exactly the staleness it exists to remove.

## A.4 Revisions — how a recompute stays safe

A full pipeline recompute used to rewrite its sink files **in place**. That was atomic per file, so a
reader saw either the old bytes or the new — and it was load-bearing, not merely a risk, because every
reader globs. Ending it therefore required the Selector to land **first**.

The current model:

1. The sink base name always carries the **batch id**, so a recompute writes a new revision beside the
   old one. Not a `<generation>` counter: that needs durable state the optional registry cannot
   guarantee, `_g<N>_` is already `DuckDbRecordSink`'s spelling for a memory-bounded flush chunk, and a
   batch-derived name preserves what the old stable name existed for — a same-batch-id replay still
   rewrites its own path, so it stays idempotent. The registry's never-written `generation` column was
   deleted on 2026-09-24 for the same reason ([../engine/spec-db-layer.md](../engine/spec-db-layer.md) §3.9) — nothing here pins by generation.
2. `supersedeOtherRevisions(table, keep)` marks earlier revisions dead. **Full recomputes only** — an
   incremental run appends a slice, so superseding there would discard every increment before it.
   `keep` is required: omitting it would mark the recompute's own fresh files stale and empty the table.
3. Nothing is deleted at flip time. The `retire_superseded` maintenance task removes the bytes of files
   older than an explicit `retention_days`, deleting **files and keeping rows** — a row for a deleted file
   is harmless, while dropping the row of a file still on disk would let it back into every glob.
   ⚠ **`retention_days` counts from the file's last write, not from when it was superseded** — the
   registry stores no supersede time. A revision written 30 days ago and superseded today goes at the next
   pass of a 7-day job. What lets a read already in flight finish on the revision it started with is
   `SnapshotSwapLock.writeAbove`: the delete waits for every reader holding a store above the file
   (`SnapshotSwapLockTest.retireSupersededWaitsForAReaderHoldingTheStoreBeforeItDeletesAFile`), not the
   retention window.

⚠ **Without a `retire_superseded` job defined, every full recompute leaves a complete extra copy of its
output on disk permanently.** The old overwrite was O(1) disk; this is not. **No longer silent
(2026-08-29):** `PipelineJobRunner` now warns naming the affected store(s) the moment step 2 actually
supersedes something and no enabled `retire_superseded` job is configured — see
[operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) for the wiring. A copyable example ships
**disabled** at `spaces/demo/config/jobs/retire_superseded_job.toon` (2026-09-23): the task reads only
`retention_days` and walks the process-wide output registry, so it carries **no** path or store key.

⛔ **Off by default — decided 2026-11-17 (MoSCoW C7), do not re-open without the operator.** The task is
safe to run: the delete waits for readers (`writeAbove`, above), it never deletes a path any `LIVE` row
still names, however old (`unreadablePaths`;
`MaintenanceLibraryTest.retireSupersededSparesAnAgedFileThatALiveRowStillNames`), and the only `SIMULATED`
row writer, `DryRunSinkWriter`, records a placeholder path rather than a real file. It stays off because the product's retention principle is *"retention is never on by default: every
deleting task is inert until an operator defines a Job"* ([operations-reference.md](../../layer/platform/admin-guide-operations-reference.md)),
and `retire_superseded` would be the one exception, armed in every Space. Two concrete surprises it would
cause: on the first pass it would delete every superseded revision a pre-release 4.0.0 install has kept
since 2026-08-10, and because retention counts from write time, a revision an operator meant to keep "for a
week" after a recompute could go within minutes of it. What 4.0.0 does instead: the WARN above, the
disabled demo job to copy, and the release note in
[plan-api-stability](../api/plan-api-stability.md) (no 3.x release keeps superseded revisions — 3.x
overwrote in place — so the disk growth is new at the upgrade). Pinned by
`DemoRetireSupersededJobShipsDisabledTest.shipsDisabledSoTheDefaultIsNotFlipped` and
`JobServiceTest.retireSupersededCheckIsWiredFromLiveJobConfigNotJustBuildTime`.

## A.5 The Watermark — completeness

Overlap answers "which files *can* contain this window". Completeness-needing checks (non-monotonic,
absence, gap) also need "has this window *closed*", which is undefined without a watermark:

```
watermark(stream) = min over producers of max(event_time_max)      -- stream key = table_name
window [lo, hi) is complete when watermark >= hi + allowed_lateness
```

Derived on read, never stored. `min over producers` rather than a plain `max` because parallel producers
into one table are the normal CDR case: one producer running ahead must not close a window a slower one
still owes rows inside.

- **`SUPERSEDED` excluded, `COMPACTED_AWAY` included.** The two non-live states are opposites here:
  compacted data was genuinely delivered and still exists inside the merged file, so dropping it would
  make the watermark travel *backwards* on compaction.
- **Observed within a horizon** (24 h default) rather than a declared producer set, which goes stale
  silently — a decommissioned producer nobody removed would freeze the stream forever.
- **Conservative in every ambiguous direction.** Staleness must be *proven* (an unparseable `written_at`
  keeps its producer in the set), and any in-horizon producer with unknown event time suppresses the
  answer. Advancing too far closes a window while rows are still owed, which fires an absence check on
  data that was merely late; not advancing is merely no answer.

Absent means **unknown**, so completeness-needing checks do not fire rather than firing on a guess. A
table written by enrichment or the Consignment processor has a watermark only when its writer declares
bounds (A.2: the enrichment's `{column, source}` partition entry, the processor's stated `EventTimeBounds`);
both record a producer since 2026-08-10.

⚠ **Built, not yet consumed (re-grounded 2026-11-17).** No production code calls `StreamWatermark.of` or
`DbConsignmentOutputStore.producerHighWater`; only their tests do. No shipped Alert Rule or Job asks "has this
window closed" yet, so the properties above describe the primitive, not a running check.

⚠ **Vocabulary.** *Watermark* is reserved for this concept. `PipelineWatermarkStore` is an incremental
**read cursor** and `$job.last_success_time` is **wall clock**; neither answers window close.

### A.5-A Reading the range from a Job

A downstream Job binds its window to what a predecessor produced through two Expression attrs, **resolved
live against this registry** at fire time:

```
$upstream(<job>).artifact(<store>).event_time_min | .event_time_max
```

`DbConsignmentOutputStore.bounds(table)` folds `min(event_time_min)`/`max(event_time_max)` over the same
predicate the watermark uses — `SUPERSEDED` excluded, `COMPACTED_AWAY` included — and returns **empty rather
than half a window**, because a caller handed one end would silently scan to the epoch.

Three properties are deliberate:

- **Derived, never stored.** A.4's revisions mean a recompute writes a new revision and supersedes the old, so
  a range copied onto the artifact at write time would go on describing superseded data. Reading it here
  makes the answer move when the data does.
- **Keyed on the sink `store` name**, the one identifier that is both `RunArtifact.ref` and this table's
  `table_name`. `PipelineJobRunner` records one artifact per store it wrote for exactly this purpose; before
  2026-08-10 it recorded none, so the accessor had no handle to key off.
- **They yield strings, not instants.** The stored bounds are zone-less local date-times (A.2), so an
  `INSTANT`-typed parameter would reject them — but each end is a valid SQL timestamp literal in the
  single-quoted form the substituter produces, which is what binding a window actually requires.

⛔ **The retired `time_range` attr is not coming back.** It yielded one opaque `"<min>..<max>"` string that
nothing split, so it could never be bound to a predicate; it was always `null` besides. Addressing step 8 was
closed on that basis rather than built. As-built detail:
[`archived-documents/plans-archive/job-parameter-contract-plan.md`](../../../../archived-documents/plans-archive/job-parameter-contract-plan.md) §5-B, step 17.

### A.5-B Ingest-written streams — no accessor, by design (closed 2026-11-17, MoSCoW C7)

The board carried "the ingest-side Consignment-scoped accessor waits for a consumer" from 2026-08-10 to
2026-11-17. ⚠ **That accessor was never code.** It was the option the job-parameter contract deferred
(archived plan §5-B, point 4): an Expression that would read the event-time range of a store written by
**ingest** rather than by a Job. Grounded 2026-11-17:

- **Ingest records bounds already.** `ConsignmentIngestor.finalizeSource` writes registry rows keyed on the
  Consignment's table, with bounds from the schema's date partition (A.2), so the data such an accessor would
  read is there.
- **No Expression can name it.** `$upstream(<job>).artifact(<store>)` resolves through a Job's last
  successful Run (`JobService.latestArtifacts`). A Collector's ingest is not a Job and records no Run
  Artifact, so the attrs have no handle on an ingest-written table — the gap the deferral named.
- **Nothing asks for it.** No Space, example, template or UI uses `event_time_min`/`event_time_max` on an
  ingest table; the Job editor's parameter catalog lists only the `$upstream` form. The completeness
  primitive that would read ingest streams (A.5) has no consumer either.

**Decision: not built.** Adding a second token (for example a stream-keyed `$stream(<table>).event_time_max`)
now would be a surface with no caller and a second word for one concept. Build it when a real consumer
appears — a Job that must bind its window to what a Collector landed, or a completeness check over an ingest
stream — and key it on the registry `table_name`, never on a store→dataset reverse lookup (A.2). The board
row is closed; this section is the reopen trigger.

## A.6 Three storage invariants that are cheap now and unfixable later

**A partition-affecting config must be PINNED in the manifest at load time.** Any setting that decides which
partition a row lands in (the partition expression, the event-time field, the time zone) is recorded with the
Consignment and **reprocessing must use the pinned value, never the current config**. Otherwise editing a
pipeline silently re-partitions only the rows reprocessed after the edit — *partition drift*, which reads as
data corruption and has no cheap repair. ⚠ This is trivial to add before there is data and effectively
impossible to retrofit after.

**No output file ever contains rows from two Consignments.** One-directional: one Consignment may write many
files, but a file belongs to exactly one. This is what makes a Consignment's output deletable by unlinking
whole files, and it is why compaction (which merges across Consignments) is the one operation that has to be
handled specially — see [post-sync step chains](spec-pipeline-execution-post-sync.md#post-sync-step-chains-the-open-dag).

**Two kinds of delete, only one of which is safe.** Unlinking a whole file owned by a single Consignment is
fine. **Rewriting** a file that holds several Consignments' rows to remove one of them is the read-modify-write
this model exists to avoid — it is the operation that loses data when it fails halfway.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

---

## Part B — Concurrency (the four-layer hierarchy)

As-built after the scheduler-system-config plan (shipped 2026-08-25, commits `1b872515` → `275bf764`;
[archived plan](../../../../archived-documents/plans-archive/scheduler-system-config-plan.md)).

## B.1 The grain rule everything follows

**A permit counts a Consignment being executed — never a Pipeline, never a run.**

The operator constraint is host I/O and CPU, and the unit of heavy work is a Consignment being
parsed → transformed → written. A run that is planning, or parked waiting for a slot, costs
essentially nothing. Because every tier counts the same unit, `3` / `12` / `16` are comparable
numbers rather than three different things wearing one name.

| Layer | Cap | Where configured | Grain |
|---|---|---|---|
| Per Pipeline | `processing.threads` | pipeline TOON (editor: sink node) | Consignments of ONE pipeline |
| Per space | `max_concurrent_consignments` | space `scheduler.toon` · Settings ▸ Scheduler | across that space |
| Per server | `max_concurrent_consignments` | system `scheduler.toon` · Settings ▸ Scheduler | across the whole process |
| Share weight | `processing.priority` (1–3) | pipeline TOON (editor: sink node) | who wins a freed slot |
| Named pool | `pools:` (name → cap) · chosen by `processing.pool` | **system** `scheduler.toon` · `PUT /system/scheduler` | Consignments admitted in that pool (B.2a) |

⚠ **`processing.threads` IS the per-pipeline Consignment cap.** The name predates the concept and is
kept only because renaming it is a GLOSSARY §13 decision; its help text says what it means.

## B.2 `ConcurrencyBroker` — one monitor, three counters, weighted grants

Process-wide singleton (`shared()`/`use()`, the `IntakeGovernor` idiom). A worker calls
`admit(space, pipeline, cap, priority)` before heavy work and closes the returned `Permit` after;
`admit` parks the virtual thread until all three tiers have room.

- **Per-pipeline FIFO queues** — Consignment arrival order within a Pipeline is preserved by
  construction (which is what makes the `mtime` planner ordering mean anything downstream).
- **Stride scheduling** for grants: `stride = 6 / priority`, lowest accumulated `pass` wins. Priority
  3 gets ~3× the throughput share of priority 1 **and priority 1 provably keeps a non-zero share** —
  weights are *shares*, never precedence. Deterministic, so the ratio is unit-testable.
- **No banked credit**: a pipeline returning from idle joins at the current virtual time, so it can
  neither burst on saved-up credit nor be punished for having been quiet.
- **Inert unless configured**: with no space/system cap the only bound is the pipeline's own cap —
  byte-identical to the run-local `Semaphore(threads)` this replaced (`CollectorProcessor.ingest`).

⛔ **Three refused designs** (do not re-propose):

- **A global priority-ordered queue.** Strict priority ordering *is* the starvation trap: a
  priority-3 pipeline with a 100k-file backlog owns the head forever. It also reorders Consignments
  within a Pipeline, breaking arrival order.
- **A separate consignment-generation service.** Planning stays in the poll cycle; only *admission
  to execution* is shared.
- **Nested semaphores per tier.** No global view for the fairness decision, and cross-tier ordering
  is a deadlock hazard. One monitor guarding three counters instead.

## B.2a Named execution pools — admission only (2026-09-24, `DUCKLE-C10-ADMISSION-POOLS-1`)

A fourth broker tier, with five binding properties (adopted 2026-09-15, scale-out plan §4.1; built 2026-09-24):

1. **Admission only.** A pool answers *"may this Consignment start now"*; `grantNext` skips a waiter
   whose pool is full, so a pool can only refuse a grant the other tiers allow. It **never widens**
   `processing.threads`, a space cap, the server cap or any memory limit (pinned by
   `ConcurrencyBrokerPoolTest.aPoolNeverWidensThePipelineOrServerCap`).
2. **Server-defined only.** Pools live in the **server-wide** `scheduler.toon` as `pools: {name: cap}`
   (`0` = unbounded), installed at boot and hot-applied by `PUT /system/scheduler` `{"pools":{…}}`
   (absent = preserve, `null`/`{}` = clear, any bad name or cap 422s the whole write). Never in a
   space document. A Pipeline only **chooses** one with `processing.pool` (sink node ▸ *Execution
   pool*); the validator checks only the name's shape (`ConfigSafetyValidator.POOL_NAME`, shared by
   both sides). **Unknown ⇒ `default`** (`ConcurrencyBroker.resolvePool`) — neither a refusal nor an
   implicit new pool. `default` always exists and is unbounded unless the server caps it.
3. **Waiting is a recorded state.** `admit(space, pipeline, pool, cap, priority, ticketId)` takes the
   Consignment id (`batchId`) as the ticket id, so the admission is listed in `snapshot().admissions`
   **before** it is granted: `state: queued` with a `queueReason` naming the tier that held it
   (`pool 'heavy' full (2 in use)`, `pipeline cap reached …`, `space cap …`, `server cap …`), then
   `state: running` with `queueMs`. The `Permit` carries the same (`id()`, `queueReason()`,
   `queueMs()`), and `CollectorProcessor` logs every admission that queued. The listing is capped at
   100 rows with `admissions_total` / `admissions_truncated` beside it, because a cycle submits every
   Consignment up front and they all park on `admit`.
4. **⛔ A supervisor takes no slot.** There is no API to admit a dispatcher. `CollectorProcessor`'s
   batch loop submits every Consignment and waits on the futures holding **no** permit; a supervisor
   holding one while its children need the same pool deadlocks at exactly the pool size. Pinned both
   ways: `aSupervisorHoldingNoSlotCompletesItsChildrenInAPoolOfOne` (with a timeout) and its
   counterfactual `theCounterfactualSupervisorHoldingASlotWedgesItsChild`, plus the real dispatcher
   in `CollectorProcessorPoolTest` (four Consignments through a pool of one, under a timeout).
5. **Metric = free permits per pool**: `freePermits(pool)` (`null` when unbounded — "not applicable"
   stays distinct from a real zero), `snapshot().pools.<name>.{cap,in_flight,free}` on
   `GET /system/scheduler` `live.pools`, and the Prometheus gauge `inspecto_pool_free_permits{pool=…}`
   (bounded pools only).

⚠ **Deliberate limits.** The ticket list is JVM-local and in memory, like every broker counter — the
id is durable because it is the Consignment's, not because the queue is persisted; moving admission
state to Postgres is phase B's U2 work. A gauge series for a pool the server has since removed keeps its
last value until restart (`MetricRegistry` has no series removal). `JobService`'s Run bound
(B.3) is **not** pooled.

## B.3 ⛔ The run budget is NOT redundant with the broker

`PipelineScheduler.runPermits` (`-Dservice.max.runs`) looks like a second budget for the same lane.
It is not, and **retiring it would be a real regression** — a claim the plan itself made and the code
refuted (2026-08-25).

The broker admits **Consignment execution**. `runPermits` is the only bound on everything a cycle
does **before** the first broker permit is taken (`CollectorProcessor.ingest`):

- the stale-marker sweep,
- `collect()` discovery,
- **checksum dedup, which reads whole files** (`ledgerFilter` → `Checksums.of`),
- **archive expansion, which writes to temp and is itself virtual-thread parallel**
  (`UnpackStage.expand`),
- the planner's per-file `length()`.

Remove it and every pipeline hashes and unpacks simultaneously with nothing bounding the disk. **The
two budgets govern different *phases* of a run, not the same fact twice.**

## B.4 Hot-apply: what changes live, and how a shrink behaves

Everything in the table below applies to the running server. **A shrink drains, never interrupts** —
Consignments already executing finish and the new ceiling gates the next admissions. Aborting a
mid-commit ingest is not something the commit sequence was designed for (it is crash-*idempotent*,
which is a different property).

| Knob | Mechanism |
|---|---|
| system / space Consignment caps | `ConcurrencyBroker.setSystemCap` / `setSpaceCap` |
| `poll_seconds`, `acquire_poll_seconds` (per space) | hold the `ScheduledFuture`, `cancel(false)`, re-register one interval out |
| intake globals (`intake_*`) | `IntakeGovernor.setGlobalPolicy` |
| `processing.*` (threads, priority, batch, intake) | already live — re-read per run via `ConfigRegistry.rebuild` |

⚠ **A cadence change must retarget the governor's overrun threshold** (`PipelineScheduler
.pollIntervalMs`). The T15 controller measures overrun *against the poll interval*; leaving it stale
throttles pipelines against a budget no longer in force.

⚠ **A changed `IntakeGovernor` policy clears every learned cap** — each was learned under the old
thresholds and could sit above the new base or below the new floor. An unchanged re-install is a
no-op, so the per-cycle re-install does not disturb adaptation.

## B.5 Precedence and provenance (the "two declarations" rule)

A **stored key** in `scheduler.toon` is the source of truth; the `-D` flag is a **bootstrap default
consulted only while that key is absent**. Provenance follows the **key, never file presence**
(fixed 2026-08-26): a document storing only the intake globals or the resource pair leaves the cap
owned by `-Dscheduler.max.consignments` — before the fix, saving *anything* on the form wrote the
effective cap into the file and silently seized its provenance to `file` forever, so the next
deploy's changed flag did nothing. The cap is now nullable end-to-end (absent key = inherit, stated
`0` = unbounded), the UI seeds it only from `source === 'file'`, and an explicit `null` in a PUT
hands ownership back to the flag, live. `GET /system/scheduler` reports, per key, the effective
value **and** its `source` (`file` | `property` | `default`), so two declarations can never leave an
operator guessing which won.

⛔ **No key may be writable through the settings document and also read from `-D` at use time.** A
knob is either file-sourced (with `-D` as its absent-file default) or it stays `-D`-only. Split
ownership of one fact is what the 2026-08-15 operational-db decision forbids — and is why
`/system/operational-db` correctly still has no PUT while `/system/scheduler` does.

**A validation grammar is served, never mirrored.** The DuckDB memory-limit grammar the PUT gates on —
an absolute size (`2GB`) or a proportion of host RAM (`80%`, operator ask 2026-08-26) — is
published by the same GET as `duckdbMemoryLimitPattern` (`SchedulerRoutes.MEMORY_LIMIT_PATTERN`), and
the form installs it — the UI holds **no regex of its own**. Before this the grammar lived in two
hand-maintained copies that were identical when measured and unpinned, i.e. a drift where the form
accepts what the server refuses. ⚠ One string can only serve both sides if it stays **portable**: no
inline `(?i)` (the browser compiles it with `new RegExp(p, 'i')`, and JS has no inline-flag construct
— it throws) and **explicitly anchored**, because `String.matches` anchors implicitly while
`RegExp.test` does not — unanchored, the form would accept `2GB of RAM`. Both properties are pinned by
a test, since neither is visible at the call site. ⚠ A **semantic bound must live inside the pattern**,
not beside it: the percentage form is bounded `1..100` within the grammar, because a separate server-side
range check would let the form accept a `500%` the PUT refuses — reintroducing the very drift this
replaces. An absent or uncompilable pattern degrades to
blank-only validation and lets the 422 speak; a client-side fallback guess would just be the mirroring
again. ⚠ The offline mock was the one legitimate place to copy the gate — it *stood in for* the server, so it had
to gate identically — and it had **no resource-pair gate at all** until this landed, accepting `"lots"`
where the server 422s. *(That mock was deleted 2026-08-31, so the server's gate is now the only gate; what is worth keeping is the divergence as a failure class, not the mirroring.)*

**A PUT merges per key**: absent = preserve stored, explicit `null` = clear and revert live to the
`-D` default. ⚠ A cap-only PUT that rewrote the whole document would silently destroy a stored
cadence — caught in review, and the same wipe existed in the offline mock.

**Every change is journalled** (operator decision 2026-08-26). The generic `AuditTrail` already
classifies these PUTs and records who / when / from where / status; what a path-classified audit row
cannot carry is *what the numbers became*, which is the half an incident review actually asks for. So
a `SCHEDULER_SETTINGS_CHANGED` event carries the **deltas** — one attribute per changed key rendered
`"<old> -> <new>"`, plus `tier` and `scope` — alongside the actor. Three properties, each pinned by a test:

- **Only an actual change is journalled.** A re-save of identical settings emits nothing; a trail that
  logs unchanged re-saves teaches an investigator to skim past it.
- **Only the keys that moved appear** in the delta, so the entry reads as the change it was.
- **An unset value renders `inherit`, not `null`** — that is what it means, and the trail is read by
  people.

⚠ It emits to **`api.service().eventLog()`** (the bound space's log), not `EventLog.current()`: a
hosted space has its own `EventLog`, while `current()` routes by the thread's space MDC and falls
back to global — so a bare `/system/scheduler` call would file the entry in a log the operator's
`/events` view never reads. That mis-filing is exactly what the first version did, and the test
caught it. Same seam `PipelineRenameRoutes` uses for `PIPELINE_RENAMED`. Journalling is best-effort: a
settings change that succeeded must never fail on its own audit entry.

## B.6 Two mechanisms, two questions — `IntakeGovernor` vs the broker

They compose; neither replaces the other.

- **Broker** — *how many Consignments may execute right now* (instantaneous slots).
- **IntakeGovernor** — *how many files one poll cycle may admit* (backlog fairness across time),
  halving a pipeline's cap while its runs overrun the poll interval and restoring it when they fit
  (defaults and flags: [architecture-elt.md](architecture-elt.md) §E4.8).

The governor's signal is **cycle overrun, not inbox lag**: admitting fewer files cannot reduce inbox
age or pending depth, so throttling on those is positive feedback that pins a healthy-but-backlogged
pipeline at the floor. Inbox lag stays an *alerting* surface, never a throttle input.

That pairing is what drains a 100k-file backlog fairly: bounded runs, permit released between them,
other pipelines interleaving at the run boundaries.

## B.7 Per-Pipeline cadence and fetch parallelism

- **Cadence** — `trigger: {every: 30s|5m|1d}` or `cron:`, gated per tick by
  `PipelineScheduler.dueThisTick`. Cron wins when both are stated. ⚠ **The space's poll tick is the
  resolution floor**: a 30s pipeline needs the space tick at ≤ 30s. Authored on the acquisition node
  (`trigger__every` / `trigger__cron`) — these are **top-level `trigger:` keys the node borrows**,
  not `collector:` block keys.
- **Fetch parallelism** — `collector.fetch.parallel_fetch` (default 1 = sequential) switches one
  pipeline's download loop to a bounded **connector-session pool** in `RemoteAcquisitionHandler`;
  each worker gets its own session because connectors are not thread-safe. `fetch.rate_limit` caps
  bandwidth. ⚠ **Remote Collectors only** — a local inbox has nothing to download; files are pushed
  in by the producer, and the poll cycle just discovers them (with the stability window guarding
  against a half-written file, and `discovery: watch` for immediate pickup). Collector configuration
  as a whole is owned by [spec-acquisition.md](spec-acquisition.md).
- Acquisition is **already continuous**: its own timer, its own per-pipeline guard, overlapping its
  pipeline's ingest, with two fetches of the same pipeline skipped rather than queued.

## B.8 Operating it

`ConfigValidator` warns when `scheduler cap × duckdb_threads > cores`. ⚠ It reads the **installed
fleet cap** via `ConfigValidator.fleetConsignmentCap(IntSupplier)` — the etl module sits below the
engine and cannot see the broker, so `CollectorService`'s constructor installs
`ConcurrencyBroker.shared()::systemCap`. The CLI orchestrator installs nothing and keeps its
`-Dsources.max` check. (Before 2026-08-25 the check read only `sources.max` and was therefore silent
in server mode — the one deployment shape that runs a fleet.)

`GET /system/scheduler`'s `live` block answers *what is waiting and why*: `system_free` (**`null` when
unbounded — a fake `0` reads as "wedged"**), per-pipeline in-flight/waiting/priority, and `throttled`
— the pipelines the governor has admitted **below** their base cap, joined in the route because the
broker cannot see the governor. Only actually-throttled rows appear, hard-capped at 50 with the true
total beside it. Rendered on **Settings ▸ Scheduler** (`canOperateRuns`).

## B.9 Decision 2026-09-06 — the fetch lane stays FIFO

`processing.priority` weights run admission through `ConcurrencyBroker`; `acquirePermits` stays a plain
semaphore. No fetch-lane contention has been observed. **Decided:** keep FIFO; the trigger to revisit is the
first observed fetch-lane wait, at which point a second `ConcurrencyBroker` instance on the fetch lane is a
small change. ⛔ `PipelineScheduler.runPermits` is not retired. Recorded as a standing decision (BACKLOG §6).

---

## References

- [architecture-elt.md](architecture-elt.md) — engine architecture, the M..N multiplexer, back-pressure, the
  Consignment status flow, the format-specific configuration principle; acquisition (Collectors, fetch,
  discovery — getting files to the inbox)
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — [node types, the token model, transform seams](spec-pipeline-execution.md) ·
  [execution lanes, Build→Test→Run containment, post-sync step chains](spec-pipeline-execution.md) ·
  [the graph-lane sink kinds; quarantine](spec-sinks.md)
- [spec-db-layer.md §3.9](../engine/spec-db-layer.md) — the catalog's DDL and its two readers
- [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) — `retire_superseded`
- [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md)
- [admin-guide-configuration-reference.md](../config/admin-guide-configuration-reference.md) — every config key, incl. the fixed-width frontend
- [user-guide-grammar-config.md](../config/user-guide-grammar-config.md) — authoring Grammars, the Parse drawer
- [spec-component-registry.md](../processor/spec-component-registry.md) — Grammar Templates as components
- [ELT plan](plan-elt.md) — the ING capability spec (requirements of record)
- Archived designs: [delimited-grammar-design.md](../../../../archived-documents/plans-archive/delimited-grammar-design.md) ·
  [parser-plugins-trust-design.md](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) ·
  [parsing-repeated-child-design.md](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md)
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment*, §5 *Parser*, *Grammar*, *Grammar Template*, *Decode Profile*, §6-B
