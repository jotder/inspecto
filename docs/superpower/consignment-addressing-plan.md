# Consignment Addressing — plan

**Status: v1.1 DRAFT (2026-08-09) — not approved. ⚠ Step 1 (measure rung A) is DONE 2026-08-10 and
its number contradicts the plan's performance premise: catalog pruning cuts rows scanned 29–88× but
wall-clock only 1.3–2.6×, so the Consignment Selector is a **correctness** feature, not a speed one,
and rungs C–F are optimizing a non-problem. Read §5.4 before approving anything here.** Grounded against source 2026-08-09; every
"already exists" claim below carries a `file:line` ref and was verified, not assumed. v1.1
(2026-08-09, operator-accepted review) folds in four amendments: `producer` on the catalog row +
the per-stream watermark (§3.1, §3.6, D4) · the stable-name overwrite fix promoted into delivery
(step 6 ← R1) · window-token provenance + the `SELECTOR_REF` seam (§3.3, §4) · the
cadence/compaction tension with an explicit promotion trigger (§6.1).**

> **Scope guard (operator, 2026-08-09):** *"let's not complicate addressing all BI, RA, Warehouse and
> Fraud requirement together."* This plan builds **one thing**: the addressing layer that lets any
> consumer name a set of Consignments as a single relation. It deliberately does **not** design the
> BI, Revenue Assurance, Warehouse or Fraud surfaces. §6 lists them as named extension points with the
> hook each will use, and §5 gives the strategy framework to instantiate **per source** later.

---

## 1. The thesis

A NiFi FlowFile is opaque content in a queue — it exists to be passed on, and once through, it is
gone. Our Consignment is the inverse: a **Parquet table that persists, is addressable, and can carry
statistics**. That difference decides the architecture.

> **In-motion and at-rest are the same type here.** A Step's input is a DuckDB relation. A Job's
> dataset scan is a DuckDB relation. In NiFi those are genuinely different things (bytes in a queue
> vs. a database). Here they are the same thing under different **selection**.

| | What computes the input relation |
|---|---|
| **Step** | the pipeline — "the Consignment currently in flight" |
| **Job** | a predicate over the catalog — "Consignments whose event-time range overlaps `[T_lo, T_hi)`" |

So the load-bearing object is not `Job` and not `Step`. It is the **Consignment Selector**, and the
substrate it needs is a **catalog carrying event-time bounds**. Everything else in this plan follows
from that one sentence.

**The single mechanical change that matters:** today every read is a filesystem glob
(`read_parquet('<root>/**/*.parquet')`, `SourceStoreReader.java:49-54`). After this plan a read is an
**explicit, catalog-pruned file list**. Pruning moves from "DuckDB opens every file and looks" to
"the catalog names the ~8 files that can possibly match."

---

## 2. What already exists (verified 2026-08-09)

This is the part that changes the plan's shape: **most of the catalog is already built, and it is
switched off.**

### 2.1 The catalog is 80% there

`DbConsignmentOutputStore` (`inspecto-engine/src/main/java/com/gamma/consignment/DbConsignmentOutputStore.java:46-48,71-75`)
already persists one row per written output file:

```
consignment_id, run_id, table_name, partition_key, record_day,
path, row_count, bytes, written_at, generation, state, schema_fingerprint
```

`path`, `row_count`, `bytes`, `generation` and `state` are exactly the columns an addressing layer
needs — **including `generation`, which is the atomic-swap primitive** compaction will require.

Two facts that must be fixed before it can serve as the addressing layer:

- **It is default-off.** Only enabled by `-Dconsignment.outputs.backend`
  (`inspecto/src/main/java/com/gamma/service/SpaceRoot.java:145,203`, `ServiceStores.java:102-115`).
- **`record_day` is not event time.** It is a single day string parsed out of the Hive partition path's
  `year=/month=/day=` segments at reveal time. Its own javadoc
  (`ConsignmentOutputs.java:126-135`) states it is "a write-time approximation" that "diverges
  silently" for late-arriving data, and it is `null` whenever the partition scheme is not y/m/d.

### 2.2 There is no event-time metadata anywhere

Confirmed absent by broad search — no min/max of any in-data timestamp column is persisted for any
batch, Consignment or file. The near-misses, all verified as *not* that:

| Thing | What it actually is | Ref |
|---|---|---|
| `record_day` | one derived day from the partition path, `null` off-y/m/d | `ConsignmentOutputs.java:126-156` |
| `RunArtifact.timeRange` | schema field, **always `null`** — both construction sites hardcode it | `RunArtifact.java:14`, `RunContext.java:76-82` |
| `PipelineWatermarkStore` | single `max(incremental_column)` per `(flow, store)`, no min | `PipelineWatermarkStore.java:55-57` |
| `AcquisitionLedger` high-watermark | file **modification** time — arrival, not event | `DbAcquisitionLedger.java:101,232` |
| `$job.last_success_time` | wall-clock of the run | `JobRunLedger.java:115` |
| dataset `role: temporal` | declares *which* column is temporal; carries no bound. ✔ **now read** by `DatasetRelation.temporalColumn` (step 2, 2026-08-10); before that it was a **UI-only** field no backend class parsed | `premium_cdr_view.toon:13-21`, `DatasetRelation.java:100-131` |

`$upstream(...).time_range` is already wired to the dead field (`BuiltinExpressions.java:118`), so an
author can reference a value that is always null.

### 2.3 There is no Consignment type

`Consignment` is a 2026-08-03 vocabulary rename that reached column names and the `consignment`
package but **not the core class**. The real unit of work is
`record Batch(String batchId, String schemaName, String table, List<Member> members)`
(`inspecto-etl/src/main/java/com/gamma/etl/Batch.java:15`). Ids are deterministic strings from two
independent call sites (`BatchPlanner.java:90`, `PipelineJobRunner.java:136-137`).

Per-Consignment header data lives in `BatchManifest` — one JSON at `<manifestsDir>/<batchId>.json`
(`BatchManifest.java:26-41`, `ManifestStore.java:21-26`), already serializing `batchId` as
`consignmentId`.

### 2.4 Output layout is not one-file-per-Consignment

`PartitionWriter.write` (`PartitionWriter.java:104,145-161`) emits
`COPY (...) TO ... (PARTITION_BY (year,month,day), OVERWRITE_OR_IGNORE 1)` with an atomic reveal by
rename. The base name decides Consignment granularity, and it is **inconsistent**:

- CSV ingest, single-file batch → source filename stem; a re-ingest **overwrites** the prior file.
- CSV ingest, multi-file batch → `batchId`; genuinely one file-set per Consignment.
- Pipeline/graph sink → `<jobName>` + `_<batchId>` **only when incremental**; a full recompute keeps a
  stable name and overwrites (`PipelineJobRunner.java:164-166`).

**Consequence for this plan:** the filesystem cannot be the source of truth for "which Consignment
wrote which file." The catalog must be, and `ConsignmentOutputs.build`
(`ConsignmentOutputs.java:168-185`) + `PartitionSinkWriter.write` (`PartitionSinkWriter.java:90-94`)
are already the registration points.

### 2.5 Nothing evaluates a window over data

This is the biggest correction to the conversation that produced this plan. The existing alert engine
is about **pipeline health**, not data content:

- `AlertRule.window` accepts `\d+[smhdb]` (`AlertRule.java:101,146-165`).
- `AlertService.inWindow` (`AlertService.java:334-349`) filters rows of the **batch audit ledger** by
  their `end_time`/`start_time`, anchored to `System.currentTimeMillis()` — wall-clock, not event time.
- `metricValue` (`AlertService.java:304-318`) computes `error_rate`, `failed_batches`,
  `rejected_files`, `duration_ms` **in Java over ledger rows**. No SQL runs.
- The one rule family that touches real data — BI-5 measure rules (`dataset:`/`measure:`) — reads the
  dataset's *current* value via `DatasetMeasureProbe` and **explicitly forbids `window`**
  (`AlertRule.java:94`).

So: **no windowed scan of ingested data exists today, in any form.** Content rules over time windows
are new capability, not an extension of the alert engine. Say so plainly in any estimate.

Adjacent constraints found in the query layer:

- `MeasureCompiler` groups by **plain identifier columns only** — no `date_trunc`, no `time_bucket`
  (`MeasureCompiler.java:93-116,107-108`).
- `ExpressionGuard` whitelists window functions after `OVER (…)` but **deliberately rejects frame
  clauses** (`ROWS|RANGE BETWEEN`) (`ExpressionGuard.java:20-28,61-67`). Any continuous-sliding
  strategy (§5, rung E) requires lifting that guard first.
- `Parameters` tokens are `$today`, `$now`, `$day`, `$day(N)`, `$current_user`, `$role` + declared
  (`Parameters.java:27,42,69-97`). There is no window/range token.

### 2.6 There is one existing pre-aggregation primitive

`MaterializeTask` (`maintenance` Job, `task: materialize`) compiles `measures` + `group_by` through
the same `MeasureCompiler` over a `dataset` component's relation, `COPY`s to
`<dataDir>/<target>/matrix-<epochMs>.parquet` with an atomic stage-and-swap, and registers a dataset
component for the target (`MaterializeTask.java:70-74,77-93,110-115`).

It is a **full-table replace**, not incremental. A pane store (§5 rung C) would be its incremental
sibling — which means it is an *extension of a shipped primitive*, not a new subsystem.

---

## 3. The addressing layer — what this plan builds

### 3.1 Event-time bounds, captured at write

Add to the catalog, per output file: `event_time_min`, `event_time_max`, `event_time_spread_ms` —
and **`producer`** (the writing pipeline/job/collector name; the same value the `dataset.write`
Signal design already carries). Bounds are what make the Selector possible; `producer` is what makes
the watermark (§3.6) possible — and parallel producers are the *normal* CDR case (§5.2). It is one
column at write time now, versus a backfill migration later.

**Which column?** The dataset registry already declares `columns[{name,type,role}]` with
`role: temporal` (`premium_cdr_view.toon:13-21`). Make that declaration **load-bearing** — ✔ done in
step 2 via `DatasetRelation.temporalColumn`. One declaration point, already in the config shape, no new
vocabulary.

> ⚠ **Correction (2026-08-10).** This section said `DatasetRelation` "ignores it entirely", implying the
> declaration was already understood backend-side and merely unread. It was not: `role` lived **only in
> the Angular dataset editor** (`dataset-types.ts:13-24`, inferred from type/name heuristics) and no Java
> class parsed a dataset's `columns[]` at all. Step 2 therefore *introduced* the backend's reader.
> Two consequences for the steps below: (a) there is still **no `ConfigSpec` entry** for `columns[]`, so a
> dataset declaring two temporal columns is rejected when something *resolves* it, not at save time —
> a save-time gate is a separate, deliberate deferral; (b) the role is **operator-editable heuristic
> output**, not a verified schema fact, so step 3 must tolerate it naming a column that is absent from the
> data or not a timestamp, rather than assuming the declaration is true.

**How, cheaply?** The COPY in `PartitionWriter` already has the relation open in DuckDB. Compute
`min()/max()` of the temporal column in the same connection and hand them to `ConsignmentOutputs.build`.
No extra read of the data.

> ⚠ **Correction (2026-08-10, step 3 as built).** The two sentences above are wrong in three ways, each
> found by grounding rather than by review. **(1) The dataset's `role: temporal` is unreachable here.**
> The write path holds no `ComponentRegistry` (`scan` is called only from control-plane code), and the
> store→dataset reverse lookup is *ambiguous by construction* — nothing stops two datasets sharing a
> `physicalRef`, and `DbBrowserRoutes:119` resolves the clash with `putIfAbsent`, first-scan-wins. Bounds
> chosen that way would depend on directory scan order. **(2) There is no live connection where the bounds
> are recorded.** `finalizeSource` takes no `Connection`; the DuckDB connection lives inside
> `strategy.ingest(...)` and is closed before the registry is written. The real seam is
> `BatchIngestStrategy.writeAndTrace`, which holds `conn`, the write relation and `partCols`, and covers all
> four ingest call sites — bounds ride out to `finalizeSource` the way `lineage` already does. **(3) The
> written relation has no column to aggregate.** It carries mapped columns under their *target* names plus
> `year`/`month`/`day`; `PartitionDef.source` is a *raw* column, generally absent, and where it is `VARCHAR`
> the correct parse needs the declared field type and the pipeline's format lists. Only `year/month/day`
> were both present and correctly coerced — day granularity, i.e. exactly what `record_day` already gives.
>
> **As built:** `DataTransformer` materialises `__event_time`, the same coerced expression `year/month/day`
> are cut from, kept whole as a `TIMESTAMP`; `PartitionWriter` excludes it so **written output is
> byte-identical**, and `TypeFlow.sinkColumns` drops it so the declared sink schema stays in lockstep.
> `ConsignmentOutputs.boundsByPartition` then aggregates it in one `GROUP BY` on the live connection.
> Consequences worth carrying forward: bounds are keyed **by output file, not by partition** (decision-rule
> routing writes different rows that can land under a partition key the main write also used — a
> partition-keyed join would hand a routed file the main relation's range); `producer` is
> `cfg.identity().pipelineName()`, the only reliably non-null identity at that scope, and `run_id` stays
> `null` as it already was; and the enrichment / Pipeline-sink paths materialise no event time, so they
> register **null bounds** — which every consumer must read as *unknown*, never as *no rows in range*.
> `PartitionWriter`'s exclusion also had to become presence-filtered: its 7-arg overload is a general entry
> point applied to relations that never went through `DataTransformer`, and DuckDB's `EXCLUDE` is a binder
> error, not a no-op, when it names an absent column.

**Retire `record_day`** in favour of the real bounds. Keep the column populated for one release for
compatibility; its javadoc already concedes it is wrong.

### 3.2 Turn the catalog on

`consignment_outputs` must be always-on with the DuckDB backend, since addressing depends on it.
This is a behaviour change on a default and is called out as **decision D1** in §8.

### 3.3 The Consignment Selector

A declared object that resolves to a relation. Sketch (final shape is step 7's deliverable):

```
selector:
  dataset: orders
  event_time: { from: $window_start, to: $window_end }   # half-open [from, to)
  state: COMMITTED
  generation: latest
```

Resolution: query the catalog for rows where
`event_time_max >= from AND event_time_min < to AND state = 'COMMITTED' AND generation = <pinned>`,
then emit `read_parquet([<explicit path list>])`.

Three properties that matter:

1. **Explicit file list, not a glob.** This is the whole performance argument.
2. **Generation pinned for the duration of a query.** Compaction can then rewrite files underneath a
   running Job without a `file not found`.
3. **Conservative but correct.** Overlap on `[min,max]` may include files with no matching rows; it
   can never exclude a file that has them.

**Where `$window_start` / `$window_end` come from:** they do not exist yet. Their home is the
`ExpressionRegistry` the job-parameter-contract work shipped (`BuiltinExpressions` is its built-in
provider — the same registry already serving `$upstream(...).time_range`, §3.4): a window-token
`ExpressionProvider` derives them from fire time + the rule's `size`/`hop` on a cron fire, or from
the firing Signal on `on_signal`. Registration, not an engine edit — the two plans share that
principle.

### 3.4 Populate `RunArtifact.timeRange`

The field and its expression token already exist and are dead (`RunArtifact.java:14`,
`BuiltinExpressions.java:118`). Feed it from the catalog so `$upstream(job).time_range` becomes real —
this is the seam by which one Job tells the next what event-time range it actually covered.

### 3.5 Guard the zone map against skew

Min/max pruning collapses when one Consignment spans a wide event-time range — a single file covering
09:00–11:00 forces every window in those hours to read it. Two cheap mitigations, both in scope:

- Persist `event_time_spread_ms` so the planner (and an operator) can see which files are unhelpful.
- **Segregate late arrivals at write time**: when a record's event time falls outside the
  Consignment's expected band, route it to a distinct late partition. The hot partition's bounds stay
  tight, and late data becomes explicitly addressable instead of poisoning the index. This is the
  highest-value non-obvious item in the plan.

### 3.6 The per-stream watermark — the completeness primitive

Overlap answers "which files *can* contain this window". Revenue Assurance and every
completeness-needing rule in §5.3 (non-monotonic, absence/gap) also need "has this window *closed*"
— and window close is **undefined** without a watermark. With `producer` on every catalog row
(§3.1) the catalog can answer it:

```
watermark(stream) = min over producers of max(event_time_max) over COMMITTED rows
```

> ⚠ **Correction (2026-08-10, as built — `StreamWatermark` / `DbConsignmentOutputStore.producerHighWater`).**
> Three things in this section did not survive contact with the catalog:
>
> 1. **There is no `COMMITTED` state.** The enum is `LIVE`/`SUPERSEDED`/`COMPACTED_AWAY`, and the two non-live
>    states are *opposites* here. `COMPACTED_AWAY` rows are **included** — that data was genuinely delivered and
>    still exists inside the merged file, so excluding them would make the watermark travel **backwards** the
>    moment a partition is compacted. `SUPERSEDED` rows are **excluded** — a reprocess replaced them, so counting
>    them could claim delivery the current data no longer supports.
> 2. **A "stream" has no key of its own.** The catalog's grain is `table_name`, so that is the stream key.
>    Nothing else in the schema identifies one.
> 3. **`producer` is nullable and that is not a legacy artefact.** Three live write paths record rows with no
>    pipeline identity *and* no bounds today — `EnrichmentEngine` (×2), `PartitionSinkWriter`,
>    `ConsignmentProcessJobType`, all via `fromPartitionCounts`. They group as one unattributed producer and,
>    having no placeable event time, **suppress the stream's watermark entirely**. That is the honest answer, not
>    a gap: a table those paths write to is receiving data nobody can place in event time. Giving those paths a
>    producer identity and bounds is what unlocks a watermark for them, and it is not in this plan.
>
> Property 1 ("conservative but correct") also had to be applied in two places the sketch does not mention:
> staleness must be **proven** (a row whose `written_at` will not parse keeps its producer in the set rather than
> dropping it out), and any in-horizon producer with an unknown `event_time_max` suppresses the answer rather
> than being skipped over. Both protect the one direction that is unsafe — advancing too far closes a window
> while rows are still owed, which fires an absence rule on data that was merely late.

A window `[lo, hi)` is complete when `watermark(stream) ≥ hi + allowed_lateness` (the per-stream
lateness declared in step 9). Three properties, matching the Selector's own:

1. **Conservative but correct** — a lagging producer holds the watermark back; it never advances
   past data a known producer could still deliver.
2. **Derived, never stored** — computed from the same catalog rows the Selector reads; no new state,
   no update path.
3. **Degrades, doesn't break** — a stream with no producer history yet has no watermark, and
   completeness-needing rules simply do not fire (D3 posture).

The expected-producer set is decision **D4** (§8). ⚠ Vocabulary: the repo already holds three
near-collisions on this word — `PipelineWatermarkStore` (an incremental *read cursor*),
`$job.last_success_time` (wall clock), and this (per-stream *event-time completeness*); §9 pins the
split.

---

## 4. What the selector does to the Job/Step boundary

It replaces the current rule. `docs/okf/backend/control-plane/job-vs-step.md` currently states the
boundary as **in-motion vs at-rest** (per `GLOSSARY.md` §5). That framing is borrowed from systems
where those are different data types. Here they are the same type, so the honest rule is:

> **Same relation, different selection.** A Step's relation is supplied by the pipeline; a Job's
> relation is computed by a Selector. What survives the unification is *grain and identity* — a Job
> produces a Run with a ledger row, trigger, overlap guard and retry; a Step produces batch counters
> inside someone else's Run.

Therefore: **keep the `STEP | JOB` discriminator.** Unify the contract plane and the input; do not
unify execution identity.

Two adjacent items are **cross-referenced, not owned by this plan** (they stand alone and should not
be bundled):

- **Node classification** (`INPUT_FORBIDDEN | INPUT_ALLOWED | INPUT_REQUIRED` on node types) — makes
  the head/interior/terminal boundary a machine-checked field and gives the canvas a real palette
  rule. Mostly documents an invariant the executor already obeys.
- **Contract-plane widening** — `NodeAttribute` currently lacks `secret`, `pattern`, `group`, `multi`
  and expression support that `ParameterDecl` gained in the job-parameter-contract work
  (already recorded as a gap in `job-vs-step.md` §6).
- **`SELECTOR_REF` parameter type** — the natural join point with the job-parameter-contract work
  once step 7 lands: a Job Type *declares* a selector-valued parameter, `ParameterResolver` hands
  the Job a resolved, generation-pinned relation, and the authoring UI inherits a picker through
  the same decl→widget mapping that renders `DATASET_REF`. Follows the Selector; not bundled here.

---

## 5. Strategies — the framework to instantiate per source

**This section is a decision procedure, not a design.** It exists so that each source (and later each
fraud rule) can be slotted onto a rung with a recorded reason, rather than every use case dragging the
whole architecture with it.

### 5.1 Vocabulary — pin this before anything else

`GLOSSARY.md` forbids one word carrying two concepts, and "sliding" is already overloaded.

| Term | Meaning |
|---|---|
| **Hopping window** | the canonical term for our case: a window of `size`, starting every `hop` |
| **size** | the span a rule looks at (e.g. 60 min) |
| **hop** | the interval between window starts (e.g. 10 min) |
| **pane** | `gcd(size, hop)` — the tumbling bucket both divide evenly |
| **dirty window** | a window whose panes were touched by a newly committed Consignment |
| **sliding window** | reserve for the *continuous* variant (every event is an endpoint) |

Arithmetic worth recording, because it bounds the work: each pane belongs to exactly `size/hop`
windows; a Consignment touching `m` panes dirties exactly **`m + size/hop − 1`** windows, contiguous
and computable with no I/O. And a burst of duration `d` is **guaranteed** caught by some hopping
window iff `d ≤ size − hop` — between `size − hop` and `size` capture depends on alignment. That
inequality is the real reason to prefer a small hop.

### 5.2 Rule tiers — classify before choosing machinery

| Tier | Example | Machinery needed |
|---|---|---|
| **T1 intra-record** | `duration > 24h` | none — a predicate fused into the Step |
| **T2 intra-Consignment** | `> N events for this key in this file` | none — a GROUP BY in the Step |
| **T3 cross-Consignment windowed** | `> N events for this key in any hour` | the ladder below |

T1/T2 are free and cover more than expected. **Do not promote a rule to T3 until T1/T2 provably
cannot express it.**

⚠ A rule looks T2 but is really T3 whenever panes receive contributions from more than one
Consignment — which happens for two independent reasons: **parallel sources** (several network
elements each reporting the same event-time period — the normal case for CDR) and **late arrival**.
Window size relative to Consignment span does **not** decide this.

### 5.3 Firing discipline — decide before storage

| Rule shape | Needs completeness? | Fires |
|---|---|---|
| T1 / T2 | no | immediately, in the Step |
| **Monotonic threshold** — `count ≥ N`, `sum ≥ X`, non-negative contributions | **no** | **the instant the threshold is crossed** |
| Non-monotonic — averages, ratios, `<` thresholds | yes | window close + allowed lateness |
| **Absence / gap** | yes, by definition | window close only |

**Build monotonic firing first.** It is what actually delivers "fast", it is independent of every
storage choice below, and it cannot be replaced by making a scan quicker. Late data can only make a
monotonic rule *more* true, so no watermark is required. The converse also holds: "window close" for
the completeness-needing rows above is *defined by* the §3.6 watermark passing `hi + allowed_lateness`
— non-monotonic and absence rules are blocked on step 4, one more reason monotonic ships first.

Note the existing suppression machinery this must cooperate with: a per-`rule|scope` cooldown map
(10 min for batch/measure rules, else the window duration, floor 1 min — `AlertService.java:192-220,364-369`)
plus an active-object guard that will not open a second ALERT while one is open
(`AlertService.java:254-277`). A fire-on-crossing rule needs a suppression key of
`(rule, window_id)`, which neither layer provides today.

### 5.4 The evaluation ladder — start at A, escalate only on evidence

| Rung | Approach | Escalate when |
|---|---|---|
| **A** | **Catalog-pruned rescan.** Selector names the files; DuckDB aggregates. No derived state, any aggregate, new rules work instantly on history, no backfill. | measured latency misses the budget |
| **B** | **Hot raw table.** Keep `max(size) + allowed_lateness` of raw events in a native DuckDB table; Parquet is the archive. Still exact, still no backfill, bounded by retention not cardinality. | hot table too large or scan still too slow |
| **C** | **Panes.** Pre-aggregate to `gcd(size,hop)` buckets, keyed `(stream, pane_id, group_key, consignment_id)` so reprocess/late/retract are all idempotent. Windows are **derived**, never stored. | high overlap factor, or raw volume dominates |
| **D** | **Sketch pre-filter.** top-K / count-min to narrow candidates, then exact confirmation on those only. | group-key cardinality explodes (see below) |
| **E** | **Continuous sliding** via `RANGE BETWEEN INTERVAL`. Exact, no boundary loss, no derived state. | hopping approximation is unacceptable — **requires lifting `ExpressionGuard`'s frame-clause ban** (`ExpressionGuard.java:20-28`) |
| **F** | **Decayed velocity counters.** Per-entity counter with decay; O(entities) state, no windows, no completeness question. | detection quality matters more than exact window semantics |

~~**Rung A is the baseline nobody has measured.**~~ **MEASURED 2026-08-10 (step 1). It was indeed a
non-problem, and the measurement also refutes this section's own reason for wanting a file list.**

Harness: `inspecto-engine/src/test/java/com/gamma/consignment/RescanBenchmark.java`, opt-in
(`-Dbench.run=true`, skipped in the normal suite). It lays down an event-time-partitioned Parquet
corpus, lands one more Consignment covering 30 minutes, then recomputes the 9 hopping windows
(size 60 m, hop 10 m) that Consignment dirtied — once over an explicit `read_parquet([...])` list of
the overlapping files, once over the `**/*.parquet` glob `DatasetRelation` builds today. Both sides
return **identical groups and breaches**, so pruning is semantically transparent.

| corpus | days | files | glob rows | pruned rows | pruned | glob | speed-up |
|---|---|---|---|---|---|---|---|
| 20 M | 30 | 33 | 20.0 M | 0.68 M | **36 ms** | **46 ms** | 1.3× |
| 20 M, row order shuffled | 30 | 33 | 20.0 M | 0.68 M | 29 ms | 44 ms | 1.5× |
| 20 M | 90 | 93 | 20.0 M | 0.23 M | 17 ms | 43 ms | 2.6× |
| **200 M** | **90** | **93** | **200.0 M** | **2.27 M** | **145 ms** | **239 ms** | **1.6×** |

*(1 M subscribers as the group key; 200 M rows = 1.4 GB of snappy Parquet; best of 3 timed passes
after a warm-up; one Windows workstation, warm page cache.)*

**The number: 145 ms pruned / 239 ms glob to rescan the dirty windows against 90 days of history.**

Four conclusions, each of which changes what is worth building:

1. **Rungs C–F are optimizing a non-problem.** Rung A answers in a fifth of a second against 90 days
   of history. Panes, sketches and decayed counters would add derived state, a backfill obligation
   and a second copy of the data to save ~100 ms. ⛔ Do not build them on latency grounds; the
   escalation trigger in the table above is not met and is not close to being met.
2. **⚠ Catalog pruning is a correctness feature, not a performance one — this section had it
   wrong.** Naming the files cuts rows scanned by **29–88×** but wall-clock by only **1.3–2.6×**,
   because DuckDB skips row groups on `event_time` statistics and reads what survives at GB/s. That
   holds even with row order shuffled so every row group spans its whole day (the pessimal case for
   statistics). So the Selector (step 7) must be justified by what a glob genuinely cannot do —
   **generation pinning and excluding `SUPERSEDED`/`COMPACTED_AWAY` files** — not by scan speed. §1's
   "the one mechanical change" framing should be read as a correctness change throughout.
3. **Glob cost tracks row volume, not file count.** Holding the file set at 93 and raising rows 10×
   moved the glob from 43 ms to 239 ms (5.6×), while holding rows at 20 M and tripling files barely
   moved it. So **compaction (§6.3) needs its own justification too** — small-file count is not what
   makes a rescan slow here.
4. **Where rung A will actually break is cardinality, not retention.** The 200 M-row pass built
   **787 k `(window, subscriber)` groups**; that GROUP BY, not the scan, is the cost that grows with
   subscriber count and with `size/hop` fan-out. Re-measure when a real rule's group key is known —
   and note §5.4's own cardinality hazard was about rung C's *storage*, while this is rung A's
   *compute*.

Caveats worth carrying: synthetic uniform event times and a hash-scattered key (real CDR is skewed,
and skew moves aggregate cost); pruning is **day-granular** here because the corpus partitions by day
and `consignment_outputs` has no event-time bounds until step 3 — real per-file bounds would prune
harder, but conclusion 2 says that headroom is not where the time goes; and the monotonic threshold
never fired at 1 M subscribers, so the reported cost is the aggregate, which is the part that matters.

Two hazards to record now:

- **Cardinality.** Fraud rules group by subscriber. 10M subscribers × 144 ten-minute panes/day ≈ 1.4B
  pane rows/day — that is a second copy of the data, not an index. Rung C is only viable with
  **rule-driven materialization**: build panes solely for the `(stream, group_key, aggregate)` triples
  a declared rule needs. That in turn makes **backfill a day-one requirement**, since adding a rule
  means populating its panes over history.
- **Prefix sums are hostile to late data.** They give O(1) per window regardless of overlap, but a
  two-hour-late file rewrites every prefix after its insertion point. Panes update locally; prefixes
  update globally. Bound them per epoch (per day) if used at all.

### 5.5 Per-source instantiation template

Fill this in per source when the time comes; do **not** generalise across sources prematurely.

```
source:            <name>
temporal column:   <col>            # becomes role: temporal in the dataset registry
consignment cadence: <interval>     # this is the detection-latency floor, nothing else changes it
event-time skew:   p50 / p99 lateness observed
partitioning claim: does a Consignment own its (event-time × group-key) space?  yes/no
                    -> if yes, engine-verify against the catalog; never trust the declaration
rules:             per rule -> tier (T1/T2/T3), monotonic? (Y/N), size, hop, group key, cardinality
chosen rung:       A..F, with the measurement that justified it
allowed lateness:  <duration>       # and what happens after: re-emit / correction Signal / ignore
window alignment:  epoch-UTC | local-midnight, and the timezone (DST makes "hourly" ambiguous twice a year)
```

**One blunt bound to repeat in every instantiation:** detection latency ≥ Consignment cadence +
processing + allowed lateness. A 1-minute hop with 10-minute Consignments does not detect faster; it
only places window boundaries more precisely (see the `d ≤ size − hop` bound in §5.1). The only
latency lever is cadence, traded against small-file cost.

---

## 6. Explicitly out of scope

Named here so the boundary is visible, with the hook each will use when its own plan is written.

| Deferred | Hook it will use | Why not now |
|---|---|---|
| **Fraud rules with context** | §5.5 template + rung F/monotonic firing | needs per-source context and real skew data |
| **BI** | `MeasureCompiler` + `DatasetRelation`; needs time-bucketing, absent today (`MeasureCompiler.java:107-108`) | its own grain and latency profile |
| **Revenue Assurance** | Selector over two datasets + reconciliation; `transform.join` against a Reference Dataset is compile-only, no runtime executor | needs the join executor first |
| **Warehouse** | compaction + `generation` swap; `MaterializeTask` as the rollup primitive | depends on the catalog landing first |
| **Compaction transactionality** | `generation` + `state` columns already present | promote to Tier 1 the moment compaction runs against live queries |
| **Dataset API** | would replace `Path.of(dataDir).resolve(name)` (`SqlTemplateJob.java:82`) | should follow the Selector, not precede it |
| **Capability seam / Controller Services** | `job-vs-step.md` §6 gaps | ✔ **Stage 1 SHIPPED 2026-08-10** — named **Platform Services** (D0); as-built in [`platform-services.md`](../okf/backend/control-plane/platform-services.md), plan archived |

### 6.1 The cadence tension, and compaction's promotion trigger

Fraud latency and BI scan speed pull the same knob in opposite directions: detection latency ≥
Consignment cadence (§5.5), so hot streams want small, frequent Consignments — which is exactly the
small-file pressure that degrades BI scans. Compaction behind the pinned `generation` is what lets
one substrate serve both: ingest small, compact quietly, and a running query keeps the file list it
pinned. The deferral above therefore carries an **expiry condition, not a memory**: promote
compaction to its own plan when a hot `(table, day)` exceeds ~100 live files, or when step 1's
rung-A number, re-measured, has visibly degraded with file count — whichever evidence arrives first.

---

## 7. Delivery table

| # | Step | Verify |
|---|---|---|
| 1 | ✔ **DONE 2026-08-10 — rung A measured**, harness `RescanBenchmark` (opt-in, `-Dbench.run=true`). **145 ms pruned / 239 ms glob** against 90 days = 200 M rows = 1.4 GB. ⚠ It refuted this plan's performance premise: pruning cuts rows 29–88× but wall-clock only 1.3–2.6×, so the Selector is a **correctness** feature (generation pinning, excluding superseded files), not a speed one — see §5.4 | ✔ a number in §5.4, not an estimate |
| 2 | ✔ **DONE 2026-08-10** — `DatasetRelation.temporalColumn(config)` → `Optional<String>`. Absent degrades to empty (D3), two declarations throw, and the name is identifier-checked because step 3 embeds it in `min()/max()` SQL. ⚠ Note the plan's framing was off: `role` did **not** exist anywhere in the Java backend — it was a **UI-only** concept (`dataset-types.ts:13-24`), so this added the backend's first reader of `columns[]`, it did not switch on an ignored one | ✔ `DatasetRelationTest` — resolves, degrades, duplicate rejected, name fails closed |
| 3 | ✔ **DONE 2026-08-10** — four columns land on the **ingest path**, keyed per output file. ⚠ Built differently from the sketch below, which did not survive grounding twice: there is **no live connection** at `finalizeSource` (the seam is `BatchIngestStrategy.writeAndTrace`), and the written relation **has no aggregatable event-time column** — so a coerced `__event_time` is now materialised in `DataTransformer` and excluded from output. See the correction box in §3.1 | ✔ `ConsignmentOutputRegistrationTest` — bounds + spread + producer per file; no-event-time degrades to null bounds; `__event_time` absent from written output |
| 4 | ✔ **DONE 2026-08-10** — `StreamWatermark.of(producerHighWater, horizon, now)` → `Optional<LocalDateTime>`, plus `windowComplete(wm, hi, lateness)`; the per-producer aggregation is `DbConsignmentOutputStore.producerHighWater(table)`. **D4 resolved: observed-within-horizon**, default 24 h, overridable per call site (see §8). Derived on read — no table, no update path. ⚠ Three corrections in the §3.6 box: no `COMMITTED` state (it is `SUPERSEDED`-excluded / `COMPACTED_AWAY`-**included**), the stream key is `table_name`, and unattributed writes suppress the watermark rather than being skipped | ✔ `StreamWatermarkTest` (11) — lagging producer wins, stale drops out, unknown suppresses, and the seeded-catalog two-producer window closes only when both pass `hi + lateness`; `DbConsignmentOutputStoreTest` (+5) — grouping, state filter, chronological last-seen |
| 5 | ✔ **DONE 2026-08-10** — default flipped to `duckdb` (`none` still honoured), and the store wired into `db_maintenance`. ⚠ Justified by a **shipped bug**, not by addressing: `ReprocessCommand`'s refusal to reprocess a compacted-away Consignment (the alternative is silent row duplication) was decidable only from this registry, so the fix was switched off everywhere. **The "migration for existing installs" was dropped, not deferred** — §7-A's filter contract means pre-registry files need no backfill; they read as unknown, not absent | ✔ `ServiceStoresDefaultsTest` — absent property ⇒ a store is opened; `none` ⇒ none |
| 6 | ⛔ **BLOCKED ON STEP 7 — do not build this first** (grounded 2026-08-10; see the ordering box below). **End stable-name overwrites** (R1 promoted): a pipeline-sink full recompute writes a new `<name>_<generation>` path and flips `generation`; it never rewrites a path a catalog row points at. | recompute while a selector-pinned read is open: the read completes on the old generation; the next resolve sees the new one |
| 7 | **The Consignment Selector.** Config shape + resolver emitting an explicit `read_parquet([...])` list, generation-pinned. | test: selector over a seeded catalog names exactly the overlapping files |
| 8 | **Populate `RunArtifact.timeRange`** from the catalog; `$upstream(job).time_range` returns a real range. | expression test asserting non-null |
| 9 | **Late-arrival segregation** at write time + declared allowed-lateness per stream. | inject a late record; assert it lands in the late partition and hot bounds stay tight |
| 10 | **Retire `record_day`** — populate from real bounds, mark deprecated. | existing readers unaffected |

Steps 1–2 are independent and can run in parallel. ~~Nothing after step 3 is worth starting before
step 1's number exists.~~ **That gate is cleared (2026-08-10) — and it moved step 7.** With rung A
answering in 145 ms, the Selector can no longer be sold on scan speed, so **step 7 must be re-argued
on correctness before it is built**: what an explicit list gives that a glob cannot is generation
pinning and the exclusion of `SUPERSEDED`/`COMPACTED_AWAY` files. ~~Step 6 must still land **before**
step 7 exposes a Selector to any consumer — generation pinning cannot protect a path whose bytes are
replaced in place — and on the measured evidence step 6 is now the *load-bearing* half of the pair.~~

> ⛔ **Correction (2026-08-10, grounded): the 6-before-7 ordering is backwards, and following it would
> corrupt reads.** Every reader in the repo resolves a store by **glob**, not by file list —
> `SourceStoreReader.java:49`, `DatasetRelation.java:76`, `EnrichmentEngine.java:129,295`,
> `BatchIngestStrategy.java:308`, `PipelineJobRunner.java:339`, `ReferenceCompactor.java:297`, all
> `/**/*.<ext>`. None of them can exclude a superseded generation.
>
> Overwriting the stable name is therefore not the bug the plan takes it for — **it is the only thing
> keeping glob readers correct.** A full recompute rewrites the same path atomically, so a concurrent read
> sees exactly one file, old bytes or new. The moment a recompute writes `<name>_<gen+1>` *beside*
> `<name>_<gen>`, every one of those six readers picks up **both**, and a full recompute recomputes the same
> source data — so the result is exact row duplication in the live read path.
>
> There is no ordering of step 6 alone that avoids this. Unlinking the old generation after the flip
> reintroduces the very window the step exists to close (and R1 already notes the open read handle makes the
> *writer* fail on Windows). So the dependency runs **7 → 6**: readers must pin an explicit file list before
> any writer may leave two generations on disk. Step 7's own re-argument is now the gate for both.
>
> Two more things a builder needs before touching this. **`generation` is a dead field** — `ConsignmentOutputs.build`
> stamps every row `0` and nothing has ever read it back, so step 6 has to *invent* the counter, and with the
> registry default-off (D1 unresolved) there is no durable place to read the current one from. And
> **`_g<N>_` is already taken**: `DuckDbRecordSink` writes `<stem>_g00001_out.parquet` for a *memory-bounded
> flush chunk* (`DuckDbRecordSink.java:272`), which is a different concept entirely. A recompute generation
> needs its own spelling, or the two become indistinguishable on disk.

### 7-A. The 5–6–7 knot, resolved (2026-08-10)

Steps 5, 6 and 7 read as three sequential steps. They are one knot: 7 gates 6 (readers must pin before a
writer may leave two generations), 6 needs 5's catalog for its generation counter, and 7 lost its own
justification when rung A came in. Pulled on, the knot has a single root.

**The root: the plan asks the catalog to be authoritative for reads, and the catalog is contractually
optional.** §1 says a read becomes "an explicit, catalog-pruned file list" — the catalog *produces* the list.
But `DbConsignmentOutputStore` is fail-open by construction and says so in its own javadoc: *a store that can
legitimately be absent must never be the only record that a file exists.* An optional index cannot be an
existence oracle. Every downstream difficulty in the knot is that contradiction surfacing: a Selector that
produces the list needs the catalog always-on and retroactively complete, which is why step 5 grew a migration
problem it cannot solve (no backfill exists for files written before the registry) and why step 6 could not be
ordered anywhere safe.

**The resolution: the Selector filters the glob; it never replaces it.**

```
resolve(root, window) = glob(root)                              -- still the authority for existence
                        MINUS rows the catalog marks SUPERSEDED / COMPACTED_AWAY
                        MINUS rows whose bounds provably miss [lo, hi)
```

A file with **no** catalog row stays in the list. That is the same rule already written for null bounds
(D3, `ConsignmentOutput#bounds`): unknown is a possible match, never an exclusion. Applied to the file list
rather than just to the bounds, it dissolves the knot:

| | Before | After |
|---|---|---|
| Catalog absent / `none` | Selector cannot resolve | list == glob, byte-for-byte today's behaviour |
| Pre-registry files | need a backfill nobody has written | read normally — they are unknown, not absent |
| Step 5's "migration" | unsolved | **does not exist**; turning the catalog on can only ever exclude a file a writer already marked dead |
| Step 6 | corrupts glob readers | safe once readers exclude `SUPERSEDED` — which is exactly what this gives them |
| Rung A's verdict | Selector had no case | this *is* the correctness case: exclusion, not pruning speed |

It also costs nothing that rung A did not already price in. The glob still runs (145 ms over 90 days), and
pruning was never where the time went.

**Revised order — `7′ → 5 → 6`**, where `7′` is the Selector reshaped as a filter. Step 5 is independent of
both and went first, because grounding turned up a better reason for it than this plan ever gave (below).
Step 7's config surface and the `SELECTOR_REF` seam (§3.3, §4) are unchanged; only the resolver's contract
moves — from *producing* a list to *subtracting* from one.

**⚠ What this forecloses.** Generation *pinning* in the strong sense — a reader holding a consistent snapshot
across a concurrent recompute — is not achievable by subtraction alone, because the glob is evaluated at read
time and a file revealed mid-read is already in it. Subtraction fixes *stale inclusion*, not *torn reads*.
Torn multi-file reads across a recompute are a real defect today and remain one after step 6; closing them
needs the reader to capture a file list once and reuse it, which is a separate change to every call site in
the ordering box above. Do not let step 7 claim it.

---

## 8. Decisions and risks

- **D1 — flip `consignment_outputs` to on by default. ✔ RESOLVED 2026-08-10: flipped** to `duckdb`
  (`ServiceStores.java:103`), `none` still honoured. ⚠ **Not for the reason recorded here.** "Addressing
  depends on it" is a promise about unbuilt work, and would not have justified changing a shipped default.
  The real reason was already in the tree: `ReprocessCommand.guardAgainstCompactedOutputs` refuses to
  reprocess a Consignment whose output a compaction merged away — the alternative being **silent row
  duplication** — and that refusal is decidable only from this table's `COMPACTED_AWAY` rows. Default-off
  meant a fix for a live data-corruption bug shipped switched off in every deployment
  (`ReprocessCommand.java:76-93`, `PartitionCompactor.java:38-45`). Turning it on changes nothing a reader
  sees; every read is still a glob.
  **Behaviour change to note in release notes:** a reprocess that used to succeed while duplicating rows now
  fails with a refusal. That is the fix working, but it is visible.
  Also landed with it: the store is now CHECKPOINT/VACUUMed by `db_maintenance`
  (`MaintenanceJob.java:640`) — it had a `maintenance()` nobody called, which was survivable for an
  opt-in store and not for a default-on one. **No retention purge was added**: one ~200-byte row per output
  file is not a growth problem yet, and a purge with no policy behind it would be speculative. When one is
  needed the rule is *never purge a non-`LIVE` row whose file is still on disk* — that row is the only thing
  excluding it from a glob.
- **D2 — bounds granularity: per output file, or per Consignment?** Per file prunes better; per
  Consignment is cheaper and simpler. Recommend **per file** (the catalog is already per file).
- **D3 — what happens to a Consignment whose dataset declares no temporal column?** Recommend:
  bounds stay null, selector falls back to the current glob, no error. Addressing must degrade, not
  break.
- **D4 — the watermark's expected-producer set (§3.6). ✔ RESOLVED 2026-08-10: observed-within-horizon**,
  `StreamWatermark.DEFAULT_HORIZON` = 24 h, passed per call rather than configured. A declared set is exact but
  goes stale silently — a decommissioned producer nobody removed from the list freezes the stream's watermark
  forever — while observed self-heals, at the cost of a window closing early for an outage longer than `H`.
  A day is the smallest horizon that survives a normal overnight gap in operator CDR delivery. **No config
  surface was added**: nothing consumes the watermark yet, so a per-stream declaration would be speculative;
  step 9 already owns per-stream lateness and is where a declared override belongs if one is ever needed.
- **R1 — output naming is inconsistent** (§2.4); a full recompute overwrites a stable file name.
  **Promoted to delivery step 6** — invalidation-by-`generation` alone cannot protect a path whose
  bytes are replaced in place (and on Windows the open read handle makes the *writer* fail instead);
  full recomputes must write a new path and flip `generation`.
  ⚠ **Re-read 2026-08-10: the overwrite is currently a load-bearing safety property, not only a risk.**
  All six readers glob, so the atomic same-path rewrite is what keeps them from seeing two generations at
  once. R1 is real, but it cannot be fixed before the readers pin — see the ordering box under §7.
- **R2 — `Batch` vs `Consignment` naming.** Code still says `Batch`. This plan adds columns and config
  using **Consignment** vocabulary against a class called `Batch`. That is the existing state
  (`GLOSSARY.md:162-166` records the rename as not rolled out); do not let this plan expand into the
  rename.
- **R3 — the alert engine is ledger-based** (§2.5). Nothing here makes content rules work; it makes
  them *possible*. Estimates for fraud/RA must not assume the alert path is reusable beyond
  Object/Incident creation.

---

## 9. Glossary additions required

`GLOSSARY.md` and `docs/INDEX.md` must be updated in the same change that lands step 7:
**Consignment Selector**, **hopping window**, **size**, **hop**, **pane**, **dirty window**, and a
ban on bare *sliding window* for the hopping case.

✔ **The watermark split landed with step 4** (`GLOSSARY.md` §6-B, 2026-08-10): **Watermark** now means the §3.6
per-stream event-time completeness, with *incremental cursor* / *acquisition high-watermark*
(`PipelineWatermarkStore`, `source.incremental.watermark`, `AcquisitionLedger.highWatermark`) and
*last-success time* (`$job.last_success_time`) banned from the bare word. ⚠ The plan said the word "already
carries three concepts" in the glossary — it carried **none**: `GLOSSARY.md` had no watermark entry at all, so
this was an addition, not an amendment, and the two older meanings live only in code and in `docs/okf/`.

---

Related: [`job-vs-step.md`](../okf/backend/control-plane/job-vs-step.md) ·
[`jobs.md`](../okf/backend/control-plane/jobs.md) ·
[`pipeline-graph-design.md`](../okf/backend/pipeline-graph/pipeline-graph-design.md) ·
[`duckdb.md`](../okf/backend/engine/duckdb.md)
