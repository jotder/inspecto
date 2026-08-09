# Consignment Addressing — plan

**Status: v1.1 DRAFT (2026-08-09) — not approved. Grounded against source 2026-08-09; every
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
| dataset `role: temporal` | declares *which* column is temporal; carries no bound, and `DatasetRelation` never reads it | `premium_cdr_view.toon:13-21`, `DatasetRelation.java:47-80` |

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
`role: temporal` (`premium_cdr_view.toon:13-21`). Make that declaration **load-bearing** — today
`DatasetRelation` ignores it entirely. One declaration point, already in the config shape, no new
vocabulary.

**How, cheaply?** The COPY in `PartitionWriter` already has the relation open in DuckDB. Compute
`min()/max()` of the temporal column in the same connection and hand them to `ConsignmentOutputs.build`.
No extra read of the data.

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

**Rung A is the baseline nobody has measured.** The mandated first action is: commit one Consignment,
rescan its dirty windows through a catalog-pruned file list, record wall-clock. That number decides
everything below it. DuckDB aggregates Parquet at GB/s; rungs C–F may be optimizing a non-problem.

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
| **Capability seam / Controller Services** | `job-vs-step.md` §6 gaps | now owned by [`platform-services-plan.md`](platform-services-plan.md) (2026-08-09) — named **Platform Services** there (D0) |

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
| 1 | **Measure rung A.** Commit one Consignment; rescan its dirty windows over an explicit file list; record wall-clock and rows scanned. Publish the number in this doc. | a number in §5.4, not an estimate |
| 2 | **Make `role: temporal` load-bearing.** `DatasetRelation` resolves the declared temporal column; reject a dataset that declares two. | unit test: dataset with `role: temporal` resolves; duplicate rejected |
| 3 | **Capture event-time bounds + `producer` at write.** `min()/max()/spread` in the existing `PartitionWriter` COPY connection → `ConsignmentOutputs.build` → four new `consignment_outputs` columns (§3.1). | ingest a file with known bounds; assert the catalog row carries bounds and producer |
| 4 | **Per-stream watermark** (§3.6), derived from the catalog; decision D4 recorded. | seeded catalog, two producers: a window reports complete only when *both* have passed `hi + allowed_lateness` |
| 5 | **Catalog on by default** (decision D1) + migration for existing installs. | fresh space records rows with no `-D` flag |
| 6 | **End stable-name overwrites** (R1 promoted): a pipeline-sink full recompute writes a new `<name>_<generation>` path and flips `generation`; it never rewrites a path a catalog row points at. | recompute while a selector-pinned read is open: the read completes on the old generation; the next resolve sees the new one |
| 7 | **The Consignment Selector.** Config shape + resolver emitting an explicit `read_parquet([...])` list, generation-pinned. | test: selector over a seeded catalog names exactly the overlapping files |
| 8 | **Populate `RunArtifact.timeRange`** from the catalog; `$upstream(job).time_range` returns a real range. | expression test asserting non-null |
| 9 | **Late-arrival segregation** at write time + declared allowed-lateness per stream. | inject a late record; assert it lands in the late partition and hot bounds stay tight |
| 10 | **Retire `record_day`** — populate from real bounds, mark deprecated. | existing readers unaffected |

Steps 1–2 are independent and can run in parallel. Nothing after step 3 is worth starting before
step 1's number exists. Step 6 must land **before step 7 exposes a Selector to any consumer** —
generation pinning cannot protect a path whose bytes are replaced in place.

---

## 8. Decisions and risks

- **D1 — flip `consignment_outputs` to on by default.** Addressing depends on it. It is a local DuckDB
  file, so cost is small, but it is a default change on a shipped flag and needs an explicit call.
- **D2 — bounds granularity: per output file, or per Consignment?** Per file prunes better; per
  Consignment is cheaper and simpler. Recommend **per file** (the catalog is already per file).
- **D3 — what happens to a Consignment whose dataset declares no temporal column?** Recommend:
  bounds stay null, selector falls back to the current glob, no error. Addressing must degrade, not
  break.
- **D4 — the watermark's expected-producer set (§3.6).** Observed-within-horizon (producers seen in
  the last H advance/hold the watermark; one silent past H drops out) vs declared-per-stream
  (explicit, but one more thing to configure). Recommend **observed** with a per-stream horizon
  default, declaration as the override for streams with known slow reporters.
- **R1 — output naming is inconsistent** (§2.4); a full recompute overwrites a stable file name.
  **Promoted to delivery step 6** — invalidation-by-`generation` alone cannot protect a path whose
  bytes are replaced in place (and on Windows the open read handle makes the *writer* fail instead);
  full recomputes must write a new path and flip `generation`.
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
ban on bare *sliding window* for the hopping case. Step 4 additionally forces the **watermark**
split — the word already carries three concepts (`PipelineWatermarkStore`'s incremental read cursor ·
`$job.last_success_time`'s wall clock · §3.6's per-stream event-time completeness). Reserve
**Watermark** for the §3.6 concept and qualify the other two in the same entry.

---

Related: [`job-vs-step.md`](../okf/backend/control-plane/job-vs-step.md) ·
[`jobs.md`](../okf/backend/control-plane/jobs.md) ·
[`pipeline-graph-design.md`](../okf/backend/pipeline-graph/pipeline-graph-design.md) ·
[`duckdb.md`](../okf/backend/engine/duckdb.md)
