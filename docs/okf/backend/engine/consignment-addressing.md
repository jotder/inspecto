# Consignment Addressing

> **Scope:** how a read decides *which files it names*, and how a Consignment's event-time extent is
> recorded so anything can answer "which files can contain this window" and "has this window closed".
> As-built from the consignment-addressing plan, delivered 2026-08-10
> ([archived plan](../../../archived-documents/plans-archive/consignment-addressing-plan.md)).
> Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md) §6-B (**Consignment Selector**, **Watermark**).

> **⚠️ Keep this current.** Derived from `ConsignmentSelector`, `StreamWatermark`,
> `ConsignmentOutputs`, `DbConsignmentOutputStore`, `PartitionSinkWriter`, `DataTransformer` and
> `MaintenanceJob`. The catalog's DDL lives in [db-layer.md §3.9](db-layer.md); the retirement task in
> [operations-reference.md](../build-run/operations-reference.md).

## 1. The one rule everything follows

**An optional index may never be the authority for what exists.** The `consignment_outputs` catalog is
fail-open by construction — a configured `none` or a failed open leaves no registry, and the
per-Consignment JSON manifest stays authoritative for a file's *existence* while the catalog is
authoritative for its *state*. Every design decision below falls out of that:

- The Selector **filters** a glob; it never produces the file list.
- A file with **no catalog row stays in** the list. Unknown is a possible match, never an exclusion.
- Null bounds mean **unknown**, never *empty*.

The practical consequence is that switching the catalog on changes nothing a reader sees, and an
installation whose data predates the registry needs no backfill. There is no migration.

## 2. Event time at write

Bounds are recorded per **output file**. Two write paths can establish them, each from a declaration it
already holds — neither ever infers which column is temporal, because a relation's only `TIMESTAMP`
column is not evidence that it *is* event time:

| Path | Source of event time | Absent when |
|---|---|---|
| Ingest (`BatchProcessor` → `DataTransformer`) | `__event_time`, coerced from the schema's date `PartitionDef` and **excluded from written output** | no date partition declared, or every row failed to parse |
| Pipeline sink (`PartitionSinkWriter`) | `TRY_CAST(<source> AS TIMESTAMP)`, where `source` is a `partitions[]` entry's declared raw column | no entry declares one, entries disagree, or it is not a plain identifier |

Enrichment and the Consignment processor still record **no** bounds and no producer.

⚠ **Why not the dataset's `role: temporal`?** Because reaching a dataset *from* a store name is a
reverse lookup that is ambiguous by construction (`putIfAbsent`, first-scan-wins), so the bounds would
depend on directory scan order. `DatasetRelation.temporalColumn` therefore has no caller on any write
path, and the general rule is: **a write path must read a declaration it already holds.**

`record_day` is derived from these bounds when a file's event times share a day, else from the
`year`/`month`/`day` partition segments. It is superseded — one day per file cannot express a file that
straddles two — and nothing in the engine reads it.

## 3. The Selector

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
one-file-per-Consignment, so one path can own an old `SUPERSEDED` row and a current `LIVE` one.

⛔ **Not a pruner and not generation pinning.** Bounds-based window pruning is deliberately unbuilt: a
file whose bounds miss the window contains no matching rows, so the query's own predicate already
excludes them — identical answers, pure performance, and the measured ceiling was 1.3–2.6× wall-clock
(rung A) against 29–88× fewer rows scanned, because DuckDB already skips row groups on Parquet
statistics. And subtraction fixes *stale inclusion*, not **torn reads**: the glob is evaluated at
resolve time, so a file revealed a moment later is simply absent from the list. Torn multi-file reads
across a recompute remain an open defect.

## 4. Revisions — how a recompute stays safe

A full pipeline recompute used to rewrite its sink files **in place**. That was atomic per file, so a
reader saw either the old bytes or the new — and it was load-bearing, not merely a risk, because every
reader globs. Ending it therefore required the Selector to land **first**.

The current model:

1. The sink base name always carries the **batch id**, so a recompute writes a new revision beside the
   old one. Not a `<generation>` counter: that needs durable state the optional registry cannot
   guarantee, `_g<N>_` is already `DuckDbRecordSink`'s spelling for a memory-bounded flush chunk, and a
   batch-derived name preserves what the old stable name existed for — a same-batch-id replay still
   rewrites its own path, so it stays idempotent.
2. `supersedeOtherRevisions(table, keep)` marks earlier revisions dead. **Full recomputes only** — an
   incremental run appends a slice, so superseding there would discard every increment before it.
   `keep` is required: omitting it would mark the recompute's own fresh files stale and empty the table.
3. Nothing is deleted at flip time, so a read already in flight finishes on the revision it started
   with. The `retire_superseded` maintenance task removes the bytes after an explicit
   `retention_days`, deleting **files and keeping rows** — a row for a deleted file is harmless, while
   dropping the row of a file still on disk would let it back into every glob.

⚠ **Without a `retire_superseded` job defined, every full recompute leaves a complete extra copy of its
output on disk permanently.** The old overwrite was O(1) disk; this is not.

## 5. The Watermark — completeness

Overlap answers "which files *can* contain this window". Completeness-needing rules (non-monotonic,
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
  answer. Advancing too far closes a window while rows are still owed, which fires an absence rule on
  data that was merely late; not advancing is merely no answer.

Absent means **unknown**, so completeness-needing rules do not fire rather than firing on a guess. A
table written by enrichment or the Consignment processor has no watermark at all, since those paths
record an unattributed producer with no bounds.

⚠ **Vocabulary.** *Watermark* is reserved for this concept. `PipelineWatermarkStore` is an incremental
**read cursor** and `$job.last_success_time` is **wall clock**; neither answers window close.

## 5-A. Bounds are not readable by a Job yet

Nothing outside the engine can ask a Job for the event-time range it produced. The `$upstream(<job>)`
`.artifact(<name>).time_range` attr exists in the grammar but is **dead**: `RunArtifact.timeRange` is a
literal `null` at both construction sites (`RunContext.java:81-82,86-87`) and `ArtifactRecorder.dataset(...)`
has no parameter to carry one.

Filling it would not help, which is why the addressing plan's step 8 was **closed rather than built**
(2026-08-10). The value is a single opaque `"<min>..<max>"` string fixed only by a test fixture, and no
consumer can use it: `SqlParamScanner.substitute` wraps the whole string in one SQL literal, nothing splits
on `..`, and `ParameterResolver.matchesType` rejects it for exactly the `DATE`/`INSTANT` parameters that would
want it. Repo-wide it has **zero live consumers** — no config, no UI, no guide.

The settled replacement is two scalars (`event_time_min`/`event_time_max`) resolved **live from this
registry** rather than stored, keyed on the sink `store` name — the one identifier where `RunArtifact.ref`
and `consignment_outputs.table_name` coincide, and one that requires `PipelineJobRunner` to start recording a
`RunArtifact` (it records none). A stored copy is rejected: §4's revisions mean a recompute would leave the
snapshot describing a superseded revision. Ingest is structurally excluded — it is not a Job and has no Run.
Design of record: [`superpower/job-parameter-contract-plan.md`](../../../superpower/job-parameter-contract-plan.md) §5-B, step 17.

## 6. Related

[db-layer.md §3.9](db-layer.md) (the catalog's DDL and its two readers) ·
[operations-reference.md](../build-run/operations-reference.md) (`retire_superseded`) ·
[output-sinks.md](output-sinks.md) · [transforms-seams.md](transforms-seams.md) ·
[GLOSSARY.md](../../../GLOSSARY.md) §6-B
