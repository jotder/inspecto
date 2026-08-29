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

Enrichment and the Consignment processor **record a producer since 2026-08-10**, and both record bounds since
2026-08-11 — but by opposite mechanisms, because they are opposite problems:

| Writer | `producer` | `bounds` |
|---|---|---|
| Enrichment (`EnrichmentEngine`) | the enrichment's own `cfg.name()`, on the main **and** routed/quarantine writes | **SHIPPED 2026-08-11.** An `output.partitions` entry may be the sink's `{column, source}` map instead of a bare name; the parser folds the declared sources to `Output.eventTimeSource` and `boundsOf` runs the same `TRY_CAST(<source> AS TIMESTAMP)` fold as the sink |
| Consignment processor (`ConsignmentProcessJobType`) | the **processor id**, threaded through `SummaryWriter.write` | **SHIPPED 2026-08-11 — stated, not derived.** `SummaryRow` gained an optional `EventTimeBounds bounds`; `SummaryWriter` folds the declarations per output file. See §2-A |

⚠ **Neither writer was ever missing its registry row** — both always called `ConsignmentOutputStores.record`; the
two columns just arrived null. Worth knowing before someone goes looking for an absent write path.

⚠ **The producer is not the table.** Enrichment's routed/quarantine write goes to `dest`, which more than one
enrichment can write, so `dest` would be a useless producer; and the process job's producer is the *processor*,
not the Job Type, because two processors can summarise one target and `producerHighWater` groups by producer.

### 2-A. Summary bounds are *stated by the processor*, never derived

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

Three rules fall out, each pinned by a test:

- **A file is bounded only when every row in it declared a range.** One silent row means the file holds events
  outside the fold, and a bound that under-covers its file is exactly the false negative above. One undeclared
  row drops the whole file's bound. *(Verified by disabling the rule and watching the test go red.)*
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
it and the `ComponentPreview` that predicts what it will do. Keeping that rule in both files let them disagree
twice in one day, each time costing an author their bounds with no signal anywhere — so `eventTimeSource` is
derived from `declaredSources` rather than parsing the list again, and the preview warns at authoring time on
exactly the declarations the writer refuses: none declared, two that disagree, one blank, one that is not a
plain identifier. Deliberately **not** unified with `PartitionDef.fromSchema` on the ingest side — same config
word, different contract (`type` required per entry, non-list hard-fails, legacy `partitionKey` fallback).

⚠ An entry declaring **no `column`** (or a blank one) is the single `partitions[]` defect this path refuses
rather than degrades past. It names no directory segment, so accepting it silently writes the store to a layout
its readers do not glob for; it previously stringified the whole entry into a directory named `{source=…}`.
`PartitionSinkWriter` throws before writing a byte, and the preview warns while the config is still editable.

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

### Which readers are filtered, and which deliberately are not (2026-08-28)

Filtering belongs on readers of **pipeline sink** stores — the only stores a full recompute can leave an
old revision in. Traced per reader:

| Reader | Filtered? | Why |
|---|---|---|
| `SourceStoreReader` | yes — `ConsignmentSelector.resolve` | reads a sink store on a connection |
| `DatasetRelation` (`physicalRef`) | yes — `sourceLiteral` (walks; no connection in scope) | the dataset read of a sink store |
| `DatasetRelation` (`view`) + `ViewQuery` | yes — **rendered at read time**, see below | executes a *persisted* definition |
| `EnrichmentEngine` ×2, `BatchIngestStrategy` | ⛔ no, correctly | read Stage-1 ingest output / the ingest-written reference store — append-only, never `supersedeOtherRevisions` targets, so no catalog-marked file can appear |
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
output on disk permanently.** The old overwrite was O(1) disk; this is not. **No longer silent
(2026-08-29):** `PipelineJobRunner` now warns naming the affected store(s) the moment step 2 actually
supersedes something and no enabled `retire_superseded` job is configured — see
[operations-reference.md](../build-run/operations-reference.md) for the wiring.

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

## 5-A. Reading the range from a Job

A downstream Job binds its window to what a predecessor produced through two Expression attrs, **resolved
live against this registry** at fire time:

```
$upstream(<job>).artifact(<store>).event_time_min | .event_time_max
```

`DbConsignmentOutputStore.bounds(table)` folds `min(event_time_min)`/`max(event_time_max)` over the same
predicate the watermark uses — `SUPERSEDED` excluded, `COMPACTED_AWAY` included — and returns **empty rather
than half a window**, because a caller handed one end would silently scan to the epoch.

Three properties are deliberate:

- **Derived, never stored.** §4's revisions mean a recompute writes a new revision and supersedes the old, so
  a range copied onto the artifact at write time would go on describing superseded data. Reading it here
  makes the answer move when the data does.
- **Keyed on the sink `store` name**, the one identifier that is both `RunArtifact.ref` and this table's
  `table_name`. `PipelineJobRunner` records one artifact per store it wrote for exactly this purpose; before
  2026-08-10 it recorded none, so the accessor had no handle to key off.
- **They yield strings, not instants.** The stored bounds are zone-less local date-times (§2), so an
  `INSTANT`-typed parameter would reject them — but each end is a valid SQL timestamp literal in the
  single-quoted form the substituter produces, which is what binding a window actually requires.

⛔ **The retired `time_range` attr is not coming back.** It yielded one opaque `"<min>..<max>"` string that
nothing split, so it could never be bound to a predicate; it was always `null` besides. Addressing step 8 was
closed on that basis rather than built. As-built detail:
[`superpower/job-parameter-contract-plan.md`](../../../archived-documents/plans-archive/job-parameter-contract-plan.md) §5-B, step 17.

## 6. Related

[db-layer.md §3.9](db-layer.md) (the catalog's DDL and its two readers) ·
[operations-reference.md](../build-run/operations-reference.md) (`retire_superseded`) ·
[output-sinks.md](output-sinks.md) · [transforms-seams.md](transforms-seams.md) ·
[GLOSSARY.md](../../../GLOSSARY.md) §6-B
