---
type: Concept
title: Pipeline — the model, execution and sinks
description: The backend model of a Pipeline (the PipelineNodeType / PipelineNodeExecutor plugin seams, the token model and the transform family, the Step Processor catalog vs the real executors, the transform seams, the PipelineEditable lift/lower round-trip with every named refusal, settings, dry-run, route branches, the parser family and the bundles); how a Pipeline runs (the two engines and their triggers, the execution lanes and the post-sync lane, run identity and the async contract, commit retry and record replay, the scratch test run, the whole-pipeline dry run, park and drain, code pointers); and where its rows land (the OutputFormat strategy, partitioned vs single-file writers, the store-layout contract, multi-destination sinks, an object-store dirs.database, quarantine outcomes, the objectstore.export push post-action).
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
---

# Pipeline — the model, execution and sinks

**What this covers.** What a Pipeline is to the engine, how it runs, and where its rows land. Part I is the
model: node types and the plugin seams, the Step catalog vs the real executors, and how the editor's graph is
saved to and read back from the `*_pipeline.toon` file. Part II is execution: the two engines, the lanes,
retry and replay, test runs, dry runs, and park and drain. Part III is output: writers, the store layout,
multi-destination sinks, quarantine and the object-store export. How files are ingested and parsed before
this is [spec-ingest.md](spec-ingest.md).

**Contents**

- [Part I — The Pipeline model](#part-i--the-pipeline-model)
  - [Pipeline node types & the node-type plugin seam](#pipeline-node-types--the-node-type-plugin-seam)
  - [Step Processor catalog vs. real node executors](#step-processor-catalog-vs-real-node-executors)
  - [Transforms & Modularity Seams](#transforms--modularity-seams)
  - [The editable round-trip — the editor's graph ⇄ the canonical config](#the-editable-round-trip--the-editors-graph--the-canonical-config)
- [Part II — Execution](#part-ii--execution)
  - [Two engines and their triggers](#two-engines-and-their-triggers)
  - [Execution lanes](#execution-lanes)
  - [Lane-fork admission notes](#lane-fork-admission-notes)
  - [Run identity, ledgers and the async contract](#run-identity-ledgers-and-the-async-contract)
  - [Caps and the bounded retry](#caps-and-the-bounded-retry)
  - [The at-rest job lane (live execution)](#the-at-rest-job-lane-live-execution)
  - [The at-rest runner, and the two lifts](#the-at-rest-runner-and-the-two-lifts)
  - [The scratch lane — Pipeline test run (run-to-here)](#the-scratch-lane--pipeline-test-run-run-to-here)
  - [Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20)](#whole-pipeline-dry-run-pipeline-dryrun-1-shipped-2026-09-20)
  - [Per-Step `enabled` — park and drain](#per-step-enabled--park-and-drain)
  - [Failure and recovery, per lane](#failure-and-recovery-per-lane)
  - [Post-sync step chains (the open DAG)](#post-sync-step-chains-the-open-dag)
  - [Code pointers](#code-pointers)
- [Part III — Sinks](#part-iii--sinks)
  - [Writers, store layout and destinations](#writers-store-layout-and-destinations)
  - [Object-storage export (S3 / HDFS) — the push post-action and its posture](#object-storage-export-s3--hdfs--the-push-post-action-and-its-posture)

---

## Part I — The Pipeline model

**What this covers.** What a Pipeline *is* to the engine. Every Step on the canvas is a node of a known type; this part lists
those types, what each one emits, and how a plugin adds a new one. It explains why the Step catalog the
author picks from has fewer real executors behind it than entries, how the SQL Step and the Record
Transformer work, and how the editor's graph is saved to — and read back from — the `*_pipeline.toon` file
the engine runs, including every named reason a save is refused. How a Pipeline then runs is
[Part II](#part-ii--execution) below; the editor screens are [user-guide.md](user-guide.md).

## Pipeline node types & the node-type plugin seam

Every node in a pipeline graph carries a `type` discriminator. `BuiltinNodeType` is the enum the lean
core ships; `PipelineNodeType` is the interface a plugin implements; `PipelineNodeTypes` is the registry
that merges them.

### The seam

```java
// PipelineNodeTypes.load()
for (BuiltinNodeType b : BuiltinNodeType.values()) m.put(b.type(), b);
// Providers are layered last so an edition can override a built-in of the same type().
for (PipelineNodeType t : ServiceLoader.load(PipelineNodeType.class)) m.put(t.type(), t);
```

* A plugin contributes by listing a provider in `META-INF/services/com.gamma.pipeline.PipelineNodeType`
  — the same shape as [`CollectorConnectorFactory`](spec-ingest.md#part-i--ingest) and the
  [parser plugins](spec-ingest.md#p9-parser-plugins--the-self-describing-parser-framework).
* **Providers are layered LAST, so one may override a built-in** by declaring the same `type()`. That is
  deliberate: an edition can specialise a node type without forking core.
* The registry is built once at class-load and is immutable after.

**A descriptor carries** `type()` · `category()` · `label()` / `description()` · `accepts()` / `emits()`
· `emitsNamedRoutes()`. Those feed the UI palette (`catalog()`), `PipelineValidator` (an outbound edge
whose relationship is not in `emits()` is **rejected**), the lift, and the executor's sink-category
checks.

**What it does NOT carry: execution** — by design, and there is a second seam for that.
`PipelineNodeType`'s own javadoc says *"Execution and dry-run hooks are added in later phases, so this
interface stays small and stable for now."*

⚠ **No external provider exists as of 4.x** — every shipped type is a `BuiltinNodeType`. The seam is
kept deliberately (`PipelineNodeType`: *"Don't remove it for being 'unused' — implement a provider
against it instead"*).

### The execution half — `PipelineNodeExecutor` (SHIPPED 2026-08-29)

**The gap that closed.** Until this, execution dispatched through `RowShaper.shape`, a *closed*
`if`-chain over the built-ins ending in `throw new IllegalArgumentException("RowShaper cannot shape node
type '…'")`. A contributed type rendered in the palette, validated and lifted — and then **threw at run
time**. The seam was descriptor-only in the literal sense: you could describe a node type but not run one.

`com.gamma.pipeline.exec.PipelineNodeExecutor` is the execution counterpart, discovered the same way
(`META-INF/services/com.gamma.pipeline.exec.PipelineNodeExecutor`, registry
`PipelineNodeExecutors`). `RowShaper.shape` consults it **before** the built-in chain.

* 🔴 **A plugin needs BOTH halves, and they are independent registrations.** A descriptor without an
  executor is the old gap; an executor without a descriptor shapes relations the validator will not let
  anyone wire (an outbound edge whose relationship the type does not `emits()` is rejected). The refusal
  message now says *which* half is missing rather than a bare "cannot shape" — that ambiguity was
  precisely what made the gap read as a core bug.
* ⚠ **A provider may override a built-in** by claiming the same `type()`, deliberately mirroring
  `PipelineNodeTypes` (providers layered last so "an edition can specialise a node type without forking
  the core"). Same trade, stated: a provider can silently change what a core verb does. ⚠ This is the one
  behaviour **not covered by a test** — `ServiceLoader` registration is global and class-load-resolved,
  so shadowing a core verb in test scope would change it for every other test in the module. The reason
  it is untested is the reason it is powerful.
* ⚠ **Single-input only.** The seam covers `RowShaper.shape`. Multi-input fan-in (`transform.merge`)
  goes through `RowShaper.merge`, a different signature, and is deliberately out of the contract rather
  than half-working.
* **Cost to a stock build: one map lookup**, and the registry is empty unless a provider is installed.
* **Worked example, and how to start one:** `node tools/scaffold.mjs new nodetype --id acme.redact
  --name "Acme Redact"` generates a complete, buildable plugin — both halves, both service files, and a
  test that runs the Step through `RowShaper.shape` the way the engine will (template:
  `tools/templates/nodetype/`). ⚠ Building it outside the reactor resolves `inspecto-engine` from
  `~/.m2`, so install a current one first (`mvn -o install -DskipTests -pl :inspecto-engine -am`) or it
  fails with *"cannot find symbol: class PipelineNodeExecutor"*.
* ⛔ **`scaffold.mjs new step` is a different thing** — that kind is *pack-hosted* (isolated
  classloader, `StepExecutor` + `StepContext`, services ceiling, watchdog), shipped by platform-services
  S2-3 (2026-09-28, [Platform Services §7c](../processor/spec-platform-services.md)). A `nodetype` deploys
  on the engine **classpath**, like a Consignment Processor: it gets no hot deploy, no isolated classloader
  and no watchdog. ⛔ Since S2-3 (D-2) a **pack** carrying a `PipelineNodeExecutor` is rejected whole;
  the raw-`Connection` executor is classpath-only.
* The in-repo test fixture `FakeNodeExecutor` (`platform/inspecto-engine/src/test`) contributes `transform.take`
  through the executor service file. ⚠ It deliberately registers **no descriptor**, and that is a finding worth
  keeping: **the served node-type catalog is a COMMITTED CONTRACT** (`NodeAttributesContractTest` vs
  `inspecto-ui/.../node-attributes.contract.json`), so a test-scope `PipelineNodeType` provider either fails
  that guard or gets a fixture type baked into the shipped client contract. Registering one broke three
  contract assertions on the first attempt. A REAL plugin still registers both halves — it just does so
  outside this build.

### Authorable ≠ lowerable — two flags, on purpose

`PipelineProjection` publishes both per type, and they are **not** the same question:

* `lowerable` (`PipelineEditable.isLowerable`) — can the flat config round-trip this node?
* `authorable` (`PipelineEditable.isAuthorable`) — may the palette OFFER a new one?

⛔ **Do not collapse them into one flag.** The comment at the site says why: filtering the palette on
lowerability *"would force the choice between offering a node nothing should create and refusing to save
a graph that legitimately still carries one."* Exactly **two** types differ today — `parser` and
`transform.dedup.marker` — and `PipelineProjectionTest` pins that list.

### The transform family — what each one emits

This is the table to consult before proposing that any of them merge. The second relation is the whole
story: **a plain `SELECT` emits one relation.**

⚠ **`accepts`/`emits` is token vocabulary, and the facts below are still real** — each row names the
DuckDB relations the lift wires and `RowShaper` produces. The `PipelineRel` constant names below (`DROPPED`, …)
predate 4.0.0; their values are the `reject:<reason>` outlets (X5-a, below).

#### The token model — the design of record

*(Distilled here 2026-09-10 from `pipeline-spec.md` §11/§12 when that plan was archived; five current-tier
pages used to delegate this to the plan. Decision **D2**, 2026-08-31, is recorded in
[`plan.md`](plan.md) § Decisions.)*

**A Step receives a Consignment token and resolves data by reference. An edge carries no records — it names
the outlet a token continues on.** The token is
`{ consignmentId, runId, attributes{…}, dataRefs[{ store, table, partition, path, rows }] }`, and ⛔
**`dataRefs` is a reference, never a payload.**

**Three outlets replace ten relations:** `data` (the main outlet: the token continues) · `reject:<reason>`
(one reject kind carrying its reason — `unmatched` · `dropped` · `invalid` · `duplicate`, or a contributed
Step's own) · `route:<key>` (content demux). ⚠ **Amended 2026-11-17 (X5 decision D4): the main outlet is spelled
`data`, not `main`.** `data` is the default when an edge omits `rel` and is in every saved graph; renaming it
would change every edge for no operator gain. `gap` stays as the one control edge (below).

🔴 **Why: the ten relations were doing four unrelated jobs, and were never the same kind of thing.** One
was the edge itself (`data`); one was content demux (`route:*`); four were a single reject kind differing only
in *reason*; and four were **Signals, not edges at all** (`success` · `failure` · `on_commit` · `gap`).
Reading that list as one vocabulary is what made the type system contradict the engine.

**As built (X5-a, 4.0.0 — [`plan-api-stability.md`](../api/plan-api-stability.md) § Release notes).**

- **The rejects are collapsed.** `PipelineRel.UNMATCHED` / `DROPPED` / `INVALID` / `DUPLICATE` keep their constant
  **names**; their **values** are `reject:unmatched` … `reject:duplicate`. `RowShaper` emits them, the lift draws
  `parse → reject:unmatched → quarantine`, and `GET /pipelines/node-types`, `GET /provenance`, a graph's edges and a
  dry run's `relations[]` all carry them. The scratch table name sanitises the colon (`<node>__reject_dropped`).
- **Read aliases, never a rewrite.** `PipelineRel.canonical(rel)` maps a pre-4.0.0 bare spelling to its
  `reject:` form and passes everything else through. `PipelineCodec` decodes every authored edge through it (a
  graph saved before 4.0.0 loads, validates and runs; the next save writes the new names), and
  `DbProvenanceStore.query` maps stored `rel` values in SQL (`CASE rel WHEN 'dropped' THEN 'reject:dropped' …`). No
  file or stored row is rewritten in place.
- **`success`, `failure` and `on_commit` are retired** (X5 decision D5 = b). Nothing produced them: the sinks and
  `acquisition` no longer declare them, `enrichment` no longer accepts `on_commit`, and the executor, dry run and
  validator lost their `on_commit` skips (and `ON_COMMIT_SAME_GRAPH` with them). An authored edge naming one is
  **dropped on decode** with a `RETIRED_RELATION` warning (`PipelineCodec.decode` returns it; `fromMap` logs it;
  the `pipeline_author` tool reports it as a finding). A Consignment's outcome is the `pipeline.batch.*` Signal.
- **`gap` stays an edge** to its CONTROL node in 4.0.0 (D5 = b). Moving it to the `SEQUENCE_GAP` Signal and
  deleting the node is the remaining part of step 2, and after the tag it costs a 5.0.0.
- **`ConservationCheck` reads the reason.** `relCounts` canonicalises each relation, tags it diverted by
  `PipelineRel.isReject` alone (the bare-spelling set is deleted) and fills `RelCount.reason`. `imbalances`
  never looked at relation names, so loss accounting did not change.

**The sequence, restated here as the current-tier copy of record** (the archived
`pipeline-spec.md` §11.5 copy says "five non-edges"; the number is four, and this copy is the one to cite).

1. **Say it before building it** — ✅ done: `BuiltinNodeType` and this page describe `accepts`/`emits` as tokens.
2. **Delete the non-edges** — ⚠ three of four done in 4.0.0 (`success` / `failure` / `on_commit` retired, X5-a);
   `gap` stays an edge (D5 = b).
3. **Collapse the rejects** into `reject:<reason>`; teach `ConservationCheck` to read the reason — ✅ done (X5-a).
4. **Open the Step SPI** — ✅ landed differently: `ExecutionMode` `LOWERED`/`EXECUTED`, `StepExecutor` /
   `StepContext` (Platform Services Stage 2), a row reader rather than the `ConsignmentProcessor` shape.
5. Only then revisit **fan-in** and the authoring shape — not started, and not X5.

⚠ Step 3 was **not** "documentation and vocabulary" as this page used to say: it changed wire and persisted
values, which is why it needed the 4.0.0 MAJOR. The rest of X5 (Step Receipts, the cross-lane drill-down) is
tracked on [`BACKLOG.md`](../../../../BACKLOG.md) §2 *X5*.

**Step Receipts name outlets canonically (X5 L1, 2026-11-17).** Every **Step Receipt** (GLOSSARY) names its
outlets through `PipelineRel.canonical(rel)`, so a receipt says `reject:dropped` even for a relation still spelled
`dropped` (a stored graph or a contributed Step written before 4.0.0). The receipt is the durable record of one hop of the token:
rows in, rows per outlet, where the data rests, a schema fingerprint and bounded diagnostics, at most 1024 bytes
(`StepReceipt`; lanes in [§ Execution lanes](#execution-lanes) below). The main outlet stays spelled `data`
(X5 D4 = b).

| Type | emits | Authorable? |
|---|---|---|
| ~~`transform.map`~~ | DELETED 2026-09-05 — the projection slot is a `transform.sql` (id `map`); a stored `mapping.rules[]` is read as `fields[]` | ❌ (verb `map` removed) |
| `transform.filter` | `DATA` + **`DROPPED`** | ✅ (verb `transform`) |
| `transform.select` | `DATA` | ❌ |
| `transform.derive` | `DATA` | ❌ |
| `transform.sql` (2026-09-04) | `DATA` — one author `SELECT … FROM input` over the TYPED upstream relation; a `project()`-class verb, never a split | ✅ recipe verb `sql` since 2026-09-04 (between `transform` and `summarize`; mid-branch it compiles but does not arm); one attribute `sql` (*Step Processor catalog vs. real node executors*, below) |
| `transform.validate` | `DATA` + **`INVALID`** | ❌ |
| `transform.dedup` | `DATA` + **`DUPLICATE`** | ✅ (verb `dedup`) |
| `transform.dedup.marker` | `DATA` + `DUPLICATE` | ❌ — read/lower-compat only |
| `transform.route` | `DATA` + **named `route:*`** (`emitsNamedRoutes`) | ✅ |
| `transform.join` | `DATA` | ✅ (verb `transform`) |
| `transform.lookup` (2026-09-06) | `DATA` — a `CASE` over an inline `key=value` map on one column; changes values, never row counts, so there is no second relation | ✅ (verb `lookup`) |
| `transform.summarize` | `DATA` | ✅ |
| `transform.running` (2026-10-06) | `DATA` — every inbound row plus one window-measure column each (`RunningWindow`: count/sum/avg/min/max per key over a time or row window, per run) | ✅ (verb `running`) |
| `transform.timeseries.resample` (2026-10-17) | `DATA` — one row per key per bucket, gaps filled (`TimeSeriesStep` → the Analytics Kernel's `TimeSeriesSql.resample`) | ✅ (verb `resample`) |
| `transform.timeseries.shift` (2026-10-17) | `DATA` — every inbound row plus each listed column's value N buckets earlier, matched by bucket (`TimeSeriesSql.shift`) | ✅ (verb `shift`) |
| `transform.timeseries.forecast` (2026-10-17) | `DATA` — per key, every bucket of a regular series with its one-step forecast and band, then `horizon` buckets ahead; one row per level (`TimeSeriesStep` → the Analytics Kernel's `ForecastSql`) | ✅ (verb `forecast`) |
| `transform.timeseries.changepoint` (2026-11-01) | `DATA` — every inbound row plus the 0/1 flags `outside_band`, `regime_change`, `drift_change`, `warmup` over a residual series and its σ (`TimeSeriesStep` → the Analytics Kernel's `ChangePoint.sql`) | ✅ (verb `changepoint`) |
| `transform.cohort.retention` (2026-11-01) | `DATA` — summarising: one row per cohort per observable period with size, active and rate, as of a stated date (`CohortStep` → `Retention.sql`) | ✅ (verb `retention`) |
| `transform.cohort.funnel` (2026-11-01) | `DATA` — summarising: one row per funnel step with the entities that reached it in order and the conversion rates (`CohortStep` → `Funnel.sql`) | ✅ (verb `funnel`) |
| `transform.stats.outlier` (2026-10-17) | `DATA` — every inbound row plus its group's Tukey fences or robust z and an outlier flag (`DistributionStep` → the Analytics Kernel's `OutlierSql`) | ✅ (verb `outlier`) |
| `transform.data.binning` (2026-10-17) | `DATA` — every inbound row plus a bin number and label, equal-width or quantile (`DistributionStep` → `Binning`) | ✅ (verb `binning`) |
| `transform.stats.correlation` (2026-10-17) | `DATA` — summarising: one row per group per column pair with Pearson's r and the least-squares line (`DistributionStep` → `CorrelationSql`) | ✅ (verb `correlation`) |
| `transform.similarity` (2026-10-17) | `DATA` — every inbound row plus a 0..1 similarity score of two columns and an optional candidate flag (`SimilarityStep` → the Analytics Kernel's `SimilaritySql`); never asserts identity (D-M1) | ✅ (verb `similarity`) |
| `transform.analytics.rank` (2026-10-17) | `DATA` — every inbound row plus its rank per group (`row_number` / `rank` / `dense_rank` by a stated order, ties broken deterministically), or only the top N (`RankStep`) | ✅ (verb `rank`) |
| `transform.sample` (2026-10-17) | `DATA` — the sampled rows, columns unchanged: a fixed size or a fraction per group by a seeded MD5 order of a row key (`SampleStep` → the Analytics Kernel's `Sampler`) | ✅ (verb `sample`) |
| `transform.matrix.pivot` (2026-10-17) | `DATA` — one row per key, one column per DECLARED value holding a Measure-grammar aggregate (`PivotStep`); no dynamic pivot | ✅ (verb `pivot`) |
| `transform.entity.link` (2026-10-17) | `DATA` — every inbound row plus its resolution group (exact match keys within a block, closed transitively; `EntityLinkStep` → the Analytics Kernel's `EntityGroupingSql`) and, with `candidate_by`, a similarity review that never joins a group (D-M1). Catalog `enrichment.entity.link` | ✅ (verb `entity_link`) |
| `transform.split` | `DATA` | ❌ |
| `transform.merge` | `DATA` (multi-**input**) | ❌ |
| `enrichment` | accepts/emits `DATA`; fires on the **commit Signal** (`on_commit` was never an edge; the relation was retired in 4.0.0, X5-a) | ✅ |

### Can the transform family fold into one SQL node?

**Assessed 2026-08-29 against the code.** The premise behind the question is correct and worth stating:
**`transform.*` has nothing to do with CSV** — every verb is `CREATE TABLE <out> AS SELECT … FROM <input>`
over a DuckDB relation, so they operate on the previous node's output metadata, not on a file.

* ✅ **The fold has since happened for the projection half — as an ADDITIVE node, not a merge
  (2026-09-04).** `transform.sql` is one author `SELECT` over the typed input (`RowShaper.sql`,
  `RowShaper.java`); `map`/`select`/`derive` were NOT retired (their type strings are a committed
  contract in stored `*_pipeline.toon`, see below). Typing stays declarative on Parse because the raw
  relation is all-VARCHAR — the split and its reasons live in
  *`transform.sql` — the SQL transformer v1* below.
* ✅ **`map` / `select` / `derive` already ARE one construct in the executor** — one method,
  `RowShaper.projectionSelectFrom`, serves all three (`derive` just prepends `*`). (`RowShaper.fuse`, which
  fused a projection plus filters into one `SELECT … WHERE`, was deleted 2026-09-06 — caller-less, and its
  "last projection wins" rule dropped every projection but the final one; a chain runs each `shape` in order.)
* 🔴 **`filter` is not equivalent to them.** It emits `DROPPED` — the rejected rows are a first-class
  relation an author can wire to a quarantine sink. Fold it into a node that emits only `DATA` and that
  side disappears silently. (Likewise `validate`→`INVALID`, `dedup`→`DUPLICATE`, `route`→named
  branches: each is a *split*, not a projection.)
* ⚠ **The authoring contracts differ even where execution does not**: `select` takes
  `columns: [name]`, `map`/`derive` take `columns: [{name, expr}]`. One execution shape, three
  vocabularies.
* 🔴 **`split` / `merge` / `summarize` cannot fold into `enrichment`.** They are specialised SQL
  (`UNNEST`, `UNION ALL BY NAME`/join, `GROUP BY`) — but **`RowShaper` contains zero references to
  enrichment**. Enrichment is not a graph verb: it runs on its own engine, registered via
  `POST /enrichment`, as a partition-scoped per-batch recompute, and it fires on the **`on_commit`
  Signal** — *"a batch committed … **cross-flow only**"*. (`PipelineRel.ON_COMMIT` was retired in 4.0.0, X5-a;
  it was always a Signal, not an edge.) Folding in-batch operators into it would
  move them from *inside* the batch to *after commit*, and from same-pipeline to cross-pipeline.
  `merge` is fan-in, which enrichment has no concept of; `summarize` changes cardinality that later
  same-batch Steps depend on. **It is a change of execution moment, not a rename.**

⚠ **The fold has already happened where it safely could — at the authoring layer.**
`RecipeCompiler`'s verb switch accepts **11 verbs** — `collect · parse · transform` (→ filter or join) `·
sql · sink · dedup · lookup · route · summarize · profile · webhook` (plus the legacy `map`, converted to
a `transform.sql` Record Transformer: `transform.map` was deleted 2026-09-05). `select`, `derive`,
`split`, `merge`, `validate` and `dedup.marker` have **no verb**, and one verb (`transform`) already
covers two types. What is duplicated is the type enum, not the authoring surface. (The served
recipe-verb table `PipelineProjection.RECIPE_VERBS` / `GET /pipelines/step-types` was retired 2026-09-24 (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`) — the Recipe view was its last reader.)

⚠ **Retiring a type is a migration, not an edit.** The type strings are a committed contract
(`node-attributes` contract JSON, pinned by `NodeConfigNameContractTest`) and they appear
verbatim in stored `*_pipeline.toon` files that `PipelineLift`/lower map.

### 🔴 `transform.dedup.marker` cannot simply be deleted — REFUTED 2026-08-29

It looks vestigial: marker dedup was folded onto the acquisition node in P5-a (2026-08-16), it publishes
no attributes (`NodeAttributes.forType("transform.dedup.marker").isEmpty()` is asserted), and it is
never emitted by the lift. **It is still load-bearing, and the reason is recorded at the code:**

* `PipelineEditable` keeps it **lowerable** because *"an editor opened before the fold holds a graph"*
  that still carries the node — deleting the type would refuse to save that graph.
* `PipelineCompiler` reads it (two sites); `PipelineLift` documents it as *"still READ — never
  emitted"*.
* `PipelineProjectionTest` pins it as one of exactly two types where `lowerable != authorable`, and
  `PipelineGraphTest` asserts `PipelineNodeTypes.isKnown("transform.dedup.marker")`.

⛔ **It is already in its correct terminal state** — known, lowerable, unauthorable, unspecced. There is
nothing left to "lift": the behavioural fold happened, and what remains is a compatibility surface with
a stated expiry (when no deployed graph can contain one, i.e. behind the same converter-plus-flagged-
minor migration as the legacy read path). ⚠ Marking it `@Deprecated` would be actively harmful — core
code still references it, so it would raise warnings against a repo that keeps its build warning-clean.

**Method note.** This refutation corrected a suggestion made *in this repo's own voice* from the
`NodeAttributes` comment "read-compat only, never authored" — which describes the **authoring** surface
and says nothing about lift, lower or the compiler. Read a type's **consumers**, not the comment nearest
to it.

#### Scaffold dependency contract (`NODETYPE-SCAFFOLD-EMITS-A-COPY-1`, closed 2026-09-24)

A module generated by `node tools/scaffold.mjs new nodetype` compiles against **two** engine artifacts,
both `provided` in `tools/templates/nodetype/pom.xml` because the engine carries them at run time:
`inspecto-engine` (the SPI) and `inspecto-util` (for `com.gamma.util.SqlIdent`, the reactor's one SQL
quoting implementation). The generated Executor calls `SqlIdent.q` / `SqlIdent.sqlStr` directly; it used
to emit its own private copy, so `SQLIDENT-NINE-COPIES-1` regrew from the scaffold however many call
sites were consolidated.

* `inspecto-util` already reached the scaffolded module transitively (engine → util, compile scope,
  so `provided` downstream). It is declared **directly** anyway, so the pom states what the sources
  import instead of depending on the engine's own dependency list.
* The note lives in the template Executor's class javadoc (*Dependency contract*) — the earlier
  "see the contract note above" pointed at a note that had been lost from the file.
* `ScaffoldTemplatesTest.theNodetypeTemplateCompiles` stamps and compiles the template and fails if
  the Executor re-grows a private quoting helper.

## Step Processor catalog vs. real node executors

The [Step Processor catalog](user-guide.md) (122<!--count:processors--> entries, 8<!--count:processor-families--> families,
`ProcessorCatalog.java`) is a **taxonomy/palette layer**, not a 1:1 map to engine code. Five catalog entries
that read like a "Mapping and Transformer" family — spanning the `DQ` and `XFM` families — all resolve to
three node types (above): `transform.map` (2), `transform.sql` (2 — repointed 2026-09-04 when the
SQL transformer v1 shipped, see below) and `transform.join` (1). There is no dedicated Java class, config
schema, or dispatch path per catalog id.

> **Update 2026-09-05 — the Record Transformer now RUNS where Map does.** Four phases shipped
> (`dd4b8c37` · `7927d703` · `2f000e22` · `7b7c7a5e`, plan archived): `RecordTransform` (inspecto-etl)
> is a Java mirror of `sql-functions.ts` that compiles `fields[]` into the same `[{name, expr}]` seam
> `DataTransformer.dataColumns` returns — **the one seam both lanes already read** — so a Record
> Transformer executes on the ingest lane and at rest through one compiler. `processing.map.fields[]`
> is its flat-file home, and `PipelineLift` puts a `transform.sql` node in the projection slot when it
> is authored. 🔴 **`fields[]` is now ENGINE-READ**; the four comments saying otherwise moved with it.
> Migration was proven by comparing compiled SQL: *byte-identical projection for 14 stored schemas,
> 795 rules across three spaces*.

> **2026-09-05 — `transform.map` is DELETED.** The projection slot `PipelineLift` fills between parser and
> sink is always a **Record Transformer** (`transform.sql`, id `map` / `map_<key>`); `BuiltinNodeType.TRANSFORM_MAP`,
> the `map` recipe verb, `TransformCompiler`'s rule path and the UI's Load pane are gone. A stored
> `mapping.rules[]` still LOADS: `DataTransformer.recordFields` converts it to `fields[]` through
> `RecordTransform.fromMappingRules` (DIRECT → `keep`, EXPR → `custom`, CONCAT_DT → `date.concat_parts`,
> FILENAME_DATE → `date.from_filename`), and `PipelineLift` does the same for `processing.map.rules`. Every
> schema under `spaces/` was migrated by `MappingMigrator` (block-list TOON form when a row needs `args`).
> ⚠ On the ingest lane `date.concat_parts` compiles exactly as CONCAT_DT did (parser timestamp formats +
> the date column's source zone) — the same lane-specific special case `keep` has. Plan:
> `archived-documents/plans-archive/delete-transform-map-plan.md`.

### The fold — five entries became three (2026-09-04, operator call)

The catalog was advertising **four processors that are one grid**. Once `transform.sql`'s fields grid
shipped over a typed function catalog (`sql-functions.ts` — `text.trim`, `convert.type`, `custom`, and
rename via the Field-name alias), *Whitespace & string sanitizer*, *Expression builder & computed columns*
and *Field type cast & renamer matrix* were three labels for three columns of the same table. They are now
**one entry**:

| Catalog id | Family | Status | `nodeType` | What it is |
|---|---|---|---|---|
| **`transform.record`** — *Record Transformer* | XFM | DELIVERED | `transform.sql` | One Step that shapes a record: sanitize · cast · rename · computed columns, one row per output field over the function catalog, generating the SELECT it saves. **Folded from `transform.expression` + `transform.cast` + `quality.cleanse.trim`.** |
| `quality.schema.validator` — *Schema registry & structural rejects* | DQ | DELIVERED | `parser` (⇒ `addable: false`) | The half that could NOT fold — see below. |
| `transform.lookup` | XFM | **DELIVERED (2026-09-06)** | `transform.lookup` | Its own node type and executor — an inline `key=value` map over one column, compiled to a `CASE`. A *versioned* reference is `transform.join`. |

**One kind, two nodes (2026-11-15, `TRANSFORMER-TWO-NODES-1`).** The palette entry `transform.record` adds a
chain `sql` Step that runs after load, at rest, writing the Pipeline's `output_store:` (refused at save
without one, `ERR_CHAIN_WITHOUT_OUTPUT_STORE`). The projection slot is the other Record Transformer instance:
`PipelineLift` names it "Shape &lt;table&gt;", or "Shape records" when no table is known (it was "Record
Transformer", the kind's own label, until 2026-11-15). Type ids, config keys and the palette label are unchanged.

🔴 **Only HALF of "Schema validator & type coercion" folded, and the half that stayed is the load-bearing
one.** Type coercion as an *authoring act* is now a Record Transformer row (`convert.type` → `TRY_CAST`).
What cannot move is the schema **contract**: the declared source column + target type are the
cast-failure audit's *denominator* (`countCastFailures` counts "source non-blank AND result IS NULL" per
rule — with no declared pair there is nothing to count against, which is exactly why `EXPR` is excluded by
definition), and `SchemaCompatibility` gates edits to those declarations BACKWARD. Fold the declaration
away and the audit's coverage goes to zero silently. So the DQ entry was re-scoped and re-pointed at
`parser` rather than deleted.

⚠ **Its `addable` flipped `true` → `false`** as a consequence, and that is a fix, not a regression: it
previously pointed at `transform.map`, so clicking "Schema validator" in the palette added a **Map** step.
`parser` is not authorable (you add `parser.delimited`, never bare `parser`), so the entry now reads as a
*capability of the Parse Step* — the same shape as `quality.constraint.check`, which is a capability of
Expectations rather than a step.

⛔ **The fold is a taxonomy change only** — no engine code, no node type, no config key moved. The
executor set is unchanged; `addable` is computed from `PipelineEditable.isAuthorable(nodeType)`, so the
contract test's "only an authorable processor is addable" invariant holds by construction.
Contract mirror: `processor-catalog.contract.json`, regenerated with
`mvn -o test -Dprocessor.catalog.write=true` and pinned by `ProcessorCatalogContractTest`.

### `transform.sql` — the SQL transformer v1 (SHIPPED 2026-09-04, `98ffc90b` engine · `7e13dd82` pane)

**The one fact that shaped it:** the raw relation is deliberately **ALL-VARCHAR** (`read_csv` is issued with
explicit `columns={'c0':'VARCHAR',…}` so the Java and DuckDB ingest paths agree on types), so `SELECT * FROM
raw` carries no types until something casts — and whatever casts *is* the mapping. It has to stay a
declarative rule table for three non-stylistic reasons: the cast-failure audit counts `source non-blank AND
result IS NULL` per rule and needs a declared source column + target type as its denominator (`EXPR` is
excluded by definition); `TRY_CAST`/`TRY_STRPTIME` null one cell where a hand-written `CAST` kills the whole
batch; and `SchemaCompatibility`'s BACKWARD contract needs a stable schema to diff against. Hence the
**two-layer split**, decided 2026-09-03 (`sql-transform-v1-plan.md`, archived; grounding in
`sql-only-transform-feasibility.md`, archived beside it):

- **Layer 1 — typing** stays on the Parse step (Types section, declarative, `TRY_CAST` audit). Unchanged
  mechanism — this IS the "Schema validator & type coercion" processor.
- **Layer 2 — transforming** is ONE SQL `SELECT` over the **typed** output of Layer 1, where resultset
  metadata is finally meaningful. That is `transform.sql`.

As built:

* **Node type:** `BuiltinNodeType.TRANSFORM_SQL("transform.sql", TRANSFORM, "SQL")`
  (`BuiltinNodeType.java`) — `DATA` in, `DATA` out, single-input; a `project()`-class verb like
  `map`/`select`/`derive`, never a split. ⛔ **Filtering stays `transform.filter`** (operator D3): a `WHERE`
  buried in free SQL would discard rows silently, where `transform.filter` emits them as `DROPPED` for
  quarantine/audit. There is deliberately no `where` attribute on this node.
* **Attributes:** exactly one — `sql` (`multiline`, required; `NodeAttributes.TRANSFORM_SQL`, mirrored in
  `node-attributes.ts`, pinned by `NodeAttributesContractTest`). Help text pins the convention: the
  input relation is addressed by the fixed alias **`input`** (`FROM input`); no DDL/DML, no multiple
  statements. The Angular pane also stores a `fields[]` block beside `sql` (the Simple-mode rows that
  *generated* the SQL) — an **authoring artifact, not an engine contract**: the engine reads only `sql`,
  and a node without `fields[]` is a hand-written ("locked") one. See
  [the editor guide](user-guide.md) for the pane.
* **Executor:** `RowShaper.shape()` gains one branch (`RowShaper.java`) → `sql(conn, node, input,
  outPrefix)`: the statement passes `SqlGuard.check` (allow-list — anything that is not a
  single `SELECT` throws naming the node), then `CREATE OR REPLACE TEMP VIEW input AS SELECT * FROM <in>`
  binds the alias, `CREATE TABLE <out> AS <sql>` materializes exactly one relation (`PipelineRel.DATA`),
  and the view is dropped in a `finally`. It runs on the same sealed [`SqlSandbox`](../engine/spec-duckdb.md) connection
  `EXPR` uses — no file/network access, no extension autoload.
* **Derived output schema without execution:** `TypeFlow.describe(List<Column> inputColumns, String sql)`
  (`platform/inspecto-etl/.../TypeFlow.java`) generalizes `transformedColumns`: an in-memory connection, a
  scratch table literally named `input` shaped by the **upstream Step's typed schema**, then `DESCRIBE
  <sql>` → `[{name, type}]`. A binder/parse failure surfaces as `IllegalArgumentException` carrying
  DuckDB's message verbatim (it names the offending column — that IS the validation). No rows are ever
  read. **Exposed since 2026-09-04 as `POST /components/transform/describe`**
  (`ComponentRoutes.describeTransform`) — body `{ sql, inputColumns: [{name, type}] }` → `{ columns:
  [{name, type}] }`, 400 on a malformed body, 422 on the guard's reason or DuckDB's binder message (with the
  driver's "closed pending query result" wrapper line stripped — `duckDbMessage`, pinned by test).
  ⚠ Callers must send the DECLARED input types: VARCHAR-for-everything makes the binder refuse valid
  arithmetic over a typed column. It
  sits with the other `/components` previews (un-gated: reads nothing, writes nothing) rather than at the
  planned `POST /pipelines/authored/{id}/nodes/{nodeId}/describe`, because it needs no pipeline and no
  node — the pane has the SQL and the upstream columns in hand. ⚠ **`TypeFlow.describe` opens a plain
  in-memory DuckDB connection, NOT the sealed [`SqlSandbox`](../engine/spec-duckdb.md) the executor and the preview run
  on**, so the route applies `SqlGuard.check` itself before calling it: `DESCRIBE` never executes the
  plan, but the binder still OPENS a `read_csv('…')` target to infer its schema, which would have made
  this the one author-SQL entry point that reads arbitrary files. Guarding at the route also keeps
  describe and execute agreeing — SQL refused at run time is refused while it is being written
  (`ControlApiComponentsTest.describeRefusesFileReadingSqlTheExecutorWouldAlsoRefuse`, whose probe is a
  CSV that really exists, so an un-guarded route would answer 200).
* **`describe`'s SECOND consumer — save-time validation (2026-09-11, `TYPEFLOW-CONSUMERS-1` (a)):**
  `ConfigRoutes.routeColumnFindings` binds each `route:` branch's `where:` as
  `SELECT * FROM "input" WHERE <predicate>` over the pipeline's declared columns, so DuckDB's binder —
  not a regex — decides whether the predicate reads a column that exists, and its message names the
  offending one. Wired into `/config/write`, `/config/patch` and `PipelineGraphRoutes`, which is the
  "needs the recipe/pipeline context" the amendment's P2 S2 deferral meant. Its sibling
  `summarizeMeasureFindings` is a plain type comparison (no DuckDB): `sum`/`avg` over a non-numeric
  declared field.
  🔴 **Both judge the MAPPED row, not the raw fields** (2026-09-24). Branch predicates and summarize
  measures run after the mapping, so the known columns are the raw fields plus `mapping.fields[]`
  (`ConfigRoutes.addMappedColumns`). A predicate is refused only on a genuine `Referenced column … not
  found` binder error — any other bind failure (unknown function, type mismatch) fails open, as the filter
  `where` check does. A summarize field whose mapping is anything but a plain `keep` (a `custom`
  expression or cast) has no type the check can know, so it is not judged; a plain `keep` of a text field
  is still refused. Until then both read the raw fields only, so a mapped column was a false refusal
  (route) or was judged by its raw source's type (summarize). Pinned by `TypeFlowSaveTimeFindingsTest`.
  🔴 **`SqlGuard.check` runs on the ASSEMBLED statement, never the bare predicate.** `SqlGuard` requires
  SQL to begin with SELECT/WITH, so guarding the fragment rejects **every** predicate and silently skips
  the bind for all of them — the check then passes everything while appearing to work (found by test, not
  by reading). ⚠ Guarding is not optional here for the reason the bullet above gives: the binder OPENS a
  `read_csv('…')` target to infer its schema, so an unguarded save-time bind would read arbitrary files
  **on every save** — a worse exposure than the route's, because no one asked for it.
  🔴 **Unknown ≠ empty.** `ConfigRoutes.declaredColumns` returns an empty list — and both checks then say
  nothing — whenever the schema cannot be read. An unresolvable `schema_file` is a deliberate WARNING
  (the file may be created after the save), so treating "no columns" as "column missing" would refuse
  every save made in the normal authoring order. It also merges the `_structure.csv` sibling: a split
  schema's TOON carries no `raw.fields[]`, and without the merge both checks fall permanently silent.
* **The plugin half, declared but not enforced (`TYPEFLOW-CONSUMERS-1` (c)):**
  `PipelineNodeType.outputColumns(inputColumns, config)` (`default` ⇒ `Optional.empty()`) lets a pack
  state its Step's output shape, because a plugin Step has no SQL for the binder to plan and every
  derived-schema consumer otherwise stops dead at one. ⚠ **Nothing reads it yet** — it was added ahead of
  its consumers, on the operator's explicit call, so pack authors have a stable contract; that means no
  gate compares a provider's answer against what `shape` actually produces. `Optional.empty()` means "not
  statically knowable"; an empty LIST would mean "emits no columns", and a consumer cannot tell those
  apart, so the distinction is load-bearing.
* **Audit honesty at the boundary:** `transform.sql` is, by construction, outside the cast-failure audit
  (the same reason `EXPR` is). `PipelineValidator.validate` emits one WARNING `SQL_STEP_UNAUDITED`
  (`PipelineValidator.java`) per `transform.sql` node, naming the node id and its `sql`
  attribute. Unlike the 2026-08-29 `EXPR` fix, **no `clean`/gate bug was found alongside it**:
  `PipelineValidator.Result.ok()` and `PipelineGraphRoutes.saveGraph`'s findings gate already keyed off
  `Severity.ERROR` only — proven, not trusted, by `PipelineValidatorTest`
  (`theUnauditedSqlWarningAloneDoesNotBlockSave`, `anActualErrorOnAGraphWithASqlStepStillBlocksSave`).
* **Recipe verb:** since 2026-09-04 `transform.sql` IS a recipe verb — `sql` in `RecipeCompiler`'s verb
  switch. (The served verb table `PipelineProjection.RECIPE_VERBS`, the UI's `RECIPE_VERBS` and
  `step-types.contract.json` that also listed it were retired 2026-09-24 with `GET /pipelines/step-types`,
  `STEP-TYPES-DEAD-CLIENT-MIRRORS-1`.)
- 🔴 **Neither committed contract can be drifted by a plugin, which is why hot node types were not gated on
  them.** `NodeAttributesContractTest` compares a **static Java table** (`NodeAttributes.wireMap()`), and a
  plugin type is simply not in it. (The second, `StepTypesContractTest`, read the served step catalogue
  with no plugins loaded, so it compared exactly the verb table; it was retired 2026-09-24.) The authors had already accounted for plugin types being **additive at runtime** — so “the contracts would
  drift” is not a reason to defer pack work. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
- `RecipeCompiler` compiles `sql: {sql, fields}` on the trunk and inside a
  `route:` branch; `RecipeConverter.sqlStep` converts it back, so a config carrying a sql step round-trips
  through the Recipe view instead of failing with `UNSUPPORTED_STEP`. ⚠ **Mid-branch, `sql` compiles but
  does not ARM:** `RouteArming.BRANCH_STEP_KINDS` is `{FILTER, DEDUP, SUMMARIZE}`, so a route branch
  chaining a sql step is a save-time finding and an `IllegalStateException` from
  `PipelineConfig.prepare()` once active — deliberate (fail-closed until the branch walker runs it), and
  the compiler still accepts it so the round-trip of an inactive draft stays lossless.

**Where the catalog's processors land after v1** (operator's question, answered):

| Catalog entry | Home |
|---|---|
| Schema validator & type coercion | Parse step, Types section (Layer 1, declarative, unchanged) |
| Whitespace & string sanitizer | `TRIM(x)` in the SQL — the Simple mode's "Remove extra spaces" verb |
| Expression builder & computed columns | **is** `transform.sql` |
| Field type cast & renamer matrix | splits: cast → Layer 1; rename → the editable Name (alias) in the Simple grid |
| Lookup & static map | **split, and both halves now exist:** the inline static map is `transform.lookup` (delivered 2026-09-06); `transform.join` keeps the *versioned* Reference-dataset half |
| Summarizer | same shape — `transform.sql` with `GROUP BY`; `transform.summarize` keeps its `measures`/`group_by` validators for the structured form, not deleted |

**Parked with a recorded decision (BACKLOG AUTHORING-REDESIGN-1, not this file):** v2 smart table over the
AST (`json_serialize_sql`/`json_deserialize_sql`) — 🔴 it lives in the `json` extension and
`SqlSandbox.seal()` sets `autoload_known_extensions=false`; `json` is *normally* statically linked in the
JDBC jar, but so was assumed of `excel`, which is NOT — measure on the sealed connection before designing.
v3 macros as the UDF registry — measured 2026-08-29 on `duckdb_jdbc 1.5.2.1`: `CREATE MACRO` (scalar and
table) works and **survives the seal**, but `DuckDBConnection` exposes **no** Java-side scalar-UDF API, so
"UDF" can only ever mean a SQL macro, per-connection, re-created on every scratch connection
(`EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy`, preview) and needing a component kind +
registry that do not exist. **Parked on demand (operator 2026-10-04)** until a named consumer exists; if ever built, recommended: a Space-level `macros/*.toon` registry loaded by one shared helper per scratch connection, governed by existing Safety Policy tiers + SqlGuard/ExpressionGuard + seal. Dynamic/environment values — none in v1; when needed, render at read time
(the standing rule) or `SET VARIABLE`/`getvariable()`. AUTHORING-REDESIGN-1 (e) is **DEFERRED / WON'T-DO FOR NOW (decided 2026-10-04):** storing column metadata nothing reads is dead config, and the Catalog has no node for a Step's output columns; reopen when a consumer exists.

### The projection slot (was the `transform.map` group — deleted 2026-09-05)

* The slot node is a `transform.sql` whose config may carry `fields` (Record Transformer rows), authored
  `columns`, a lift-derived `schema`, or a mapping component's `rules`; `RowShaper.isProjection` sends any of
  those through `project()` (compiled by `RecordTransform`), and only a HAND-WRITTEN `sql` key takes the
  opaque-SQL path. `RowShaper.MAP_NODE_CONFIG_KEYS` pins the vocabulary.
* **Engine:** `DataTransformer.materialize()` (`platform/inspecto-etl/.../DataTransformer.java`) builds the
  transformed table. Type coercion is `TRY_CAST`; a failed cast is counted + WARN-logged and the row is
  **kept with NULL** (`DataTransformer.java`) — it is NOT routed to quarantine, despite the catalog
  note for `quality.schema.validator` implying a reject path.
* **Legacy rule kinds** (`transformType` — `DIRECT`, `EXPR`, `CONCAT_DT`, `FILENAME_DATE`) no longer
  compile anywhere: `RecordTransform.fromMappingRules` converts them to catalog functions at read time.
  `TransformCompiler` now compiles PARTITION and event-time columns only.
* **"Cast & rename matrix"** is nothing more than a `DIRECT` rule where the row's `targetColumn` differs
  from the source field name — no separate rename mechanism exists.
* **UI:** `app-mapping-editor-dialog`
  (`inspecto-ui/src/app/modules/admin/components/mapping-editor.dialog.ts`) — NOT the generic
  `<inspecto-schema-form>` used by attribute-spec-driven node types. 🔴 **Corrected 2026-09-09**: this
  named a *bespoke* `app-pipeline-load-definition` component, which does not exist and appears nowhere in
  the UI — a deleted component named as the current mapping UI. Its replacement is also not bespoke: the
  dialog edits `rules[{targetColumn, sourceExpression, transformType}]` over the **shared**
  `<inspecto-editable-grid>` (`mapping-editor.dialog.ts`).
* **Persistence:** rules save as a sidecar CSV next to the schema file — `x_schema.toon` → `x_mapping.csv`
  — via `MappingCsv.siblingFor()` (`platform/inspecto-util/.../MappingCsv.java`), pushed through
  `PUT /pipelines/{name}/graph`.
* **Dispatch:** hardcoded in `RowShaper.shape()` (`RowShaper.java`) → `project(...)`, after the
  optional `PipelineNodeExecutor` SPI seam (`RowShaper.java`) is checked and found empty.

### `transform.join` (the 5th — "Lookup & Static Map Transcoder")

* 🔴 **The inline-static-map half is no longer missing.** It shipped 2026-09-06 as its own node type,
  `transform.lookup` (`BuiltinNodeType.TRANSFORM_LOOKUP`, `RowShaper.lookup`), which compiles an author
  `key=value` list to a `CASE` — no join, no stored relation. Pick `lookup` for a fixed code table,
  `join` for a Reference dataset that changes over time.
* **Config schema exists and is real** (unlike the map group):
  `NodeAttributes.TRANSFORM_JOIN` (`NodeAttributes.java`) — `reference` (autocomplete, required,
  points at a registered `reference/<id>` component) and `on` (list, required, join key columns).
  Registered at `NodeAttributes.java`; kept in sync with the Angular mirror
  (`node-attributes.ts`) by `NodeAttributesContractTest`.
* **Engine:** `RowShaper.join()` (LEFT JOIN semantics in the `RowShaper` class Javadoc) resolves
  the reference via a `ReferenceResolver` seam, throwing if unresolvable or if `on` is missing
  (`RowShaper.java`).
* **Authoring-only / at-rest gated** — refused mid-branch, since no reference resolver exists on the
  ingest lane (`BuiltinNodeType.TRANSFORM_JOIN`).
* **UI:** the generic path — `app-pipeline-config-definition` rendering `<inspecto-schema-form>` off the
  server-published `AttributeSpec[]` (`GET /pipelines/node-types`).
* **Dispatch:** `RowShaper.shape():164`, before the `transform.map` branch.

### Execution philosophy: SQL builders over embedded DuckDB, not row-by-row Java

None of the 5 processors run row-by-row Java logic. `RowShaper` and `DataTransformer` are **SQL builders**
that generate a `SELECT`/`JOIN` and hand it to the embedded [DuckDB](../engine/spec-duckdb.md) connection — DuckDB does all
the actual execution:

* **Type coercion** (`quality.schema.validator`) is DuckDB's native `TRY_CAST` function, spliced into the
  generated `SELECT`. There is no Java-side type-checking pass. This is also *why* a failed cast can't be
  routed to quarantine: `TRY_CAST` returns SQL `NULL` inline, in the same query, with no per-row branch
  point for Java to intercept — the NULL count is only measured after the query returns
  (`DataTransformer.java`).
* **Sanitizing, casting, renaming and computing** — all four of the acts now folded into
  `transform.record` — are SQL fragments in the generated column list: `TRIM(col)`,
  `TRY_CAST(col AS …)`, `expr AS target_col`, any scalar expression. DuckDB parses and runs them like
  any other SQL, so none of them is a distinct transform pipeline stage; on `transform.sql` the fields
  grid *generates* those fragments from typed function definitions, and on the legacy `transform.map`
  path `TransformCompiler` splices an `EXPR` rule's text verbatim.
* **Lookup** (`transform.lookup`) is a DuckDB `LEFT JOIN` between the row relation and the resolved
  Reference dataset's relation (`RowShaper.join()`), keyed on `on`. DuckDB executes the join; Java only
  resolves which reference table to bind and builds the join SQL.

The consequence: the catalog's 5 "processors" are product labels for different **shapes of generated SQL**,
not 5 pieces of transform logic. Adding a genuinely new processor here means either (a) adding a new
`transformType` to `TransformCompiler`'s SQL-fragment registry, which still executes as DuckDB SQL, or (b) a
real engineering effort to add a new node type with its own Java-side row processing — which is what the
missing "inline static map" half of `transform.lookup` would require, since a literal key→value map is not
naturally expressible as a join against a stored relation.

### Cross-cutting

* **The measure shorthand has ONE home:** `MeasureCompiler.splitShorthand`
  (`MeasureCompiler.java`), which every production call site reaches (`MaterializeTask`, `ReportJob`,
  `RowShaper`, `PivotStep`, `TimeSeriesStep`, `KpiDefinition`). ⛔ `RecipeVerbParityTest` deliberately still hand-rolls the split and
  **must keep doing so**: it exists to prove the recipe path produces byte-compatible measures, and a
  parity test that calls the thing it is checking proves nothing. That copy is independent verification,
  not duplication. The rationale lives in that javadoc and in `DatasetMeasureProbe.java`.
* **Dispatch architecture:** `RowShaper.shape()` (`RowShaper.java`) — SPI seam
  (`PipelineNodeExecutors.get(type)`) first, then a hardcoded `if (BuiltinNodeType.X.equals(type))` chain
  (`transform.sql` among them), else throws. No per-catalog-id branch exists; several catalog ids fan into
  the same `transform.map` or `transform.sql` branch.
* **Palette activity is gated on `Status.PLANNED` only** — `DELIVERED` and `PARTIAL` both render as
  active in the palette (`ProcessorCatalog.java`), so `transform.lookup` looks fully live despite
  being a partial implementation. ⚠ *Addable* is a separate flag, computed from
  `PipelineEditable.isAuthorable(nodeType)`: `quality.schema.validator` is DELIVERED but **not addable**,
  because it now points at `parser` (a capability of the Parse Step, not a step you add).
* Searched broadly for a class named for any of these processors ("schema validator", "whitespace
  sanitizer", "expression builder", "cast matrix", "lookup transcoder") — none exist. This is a confirmed
  gap, not an unexplored one.

### The step workbench design, distilled (2026-09-06)

`step-workbench-design.md` (archived) asked for one surface where a Step is authored against its real input
relation. What shipped of it: S1 `RelationPreview.columnTypes` + `Result.sql` on the sealed sandbox; S2
`<inspecto-step-preview-result>`; S5 `GET /config/schema/derived` + `<inspecto-derived-schema-panel>`; and the
derived-schema half as `POST /components/transform/describe` (`TypeFlow.describe`). S3 was refuted. Two durable
facts: a chain preview must run each Step's `shape` in order over the previous relation (never fuse — `RowShaper.fuse`,
"last projection wins" and caller-less, was **deleted 2026-09-06** rather than wired); and a preview publishes at two
points — the relation and the derived schema — which must agree. S4, the one-surface workbench, **SHIPPED 2026-09-07** — all three
slices, UI-only, no new endpoint. As-built:
[`okf/product/module/elt/user-guide.md`](user-guide.md) §"The step workbench".
🔴 Its planned merge/join input PICKER was refused on grounding: `transform.merge` declares no attributes and
takes its inputs as a positional list the executor builds from the graph edges, so a picker would have written
a key nothing reads. The 2026-09-06 grounding above still holds for why no endpoint was needed — the input
relation is implicit in the authored `edges[]`, which the editor already holds.

### A Dataset's columns are derived from the Parquet, not from the pipeline shape (2026-09-16)

A `DatasetColumn` is `{name, type, role}`. `MaterializeTask` is the one place a Dataset is registered by
code rather than by a human, and it used to write no `columns` at all.

* **The derivation reads the WRITTEN artifact, not a predicted shape.** It is `DESCRIBE` over the Parquet
  the task just wrote. ⛔ It deliberately does **not** go through `TypeFlow`: the task already holds the
  real relation, so describing what was actually written is truthful where a statically-derived shape is
  only a prediction. `TypeFlow.Column` stays `(name, type)` for this reason — ⚠ a board row demanded a
  `role` carry-through here for months, and the design rejects it.
* **One coarse-type vocabulary, one role heuristic, both pinned.** `ResultSetDescriptor.columnType(String)`
  maps a DuckDB type name to the coarse vocabulary; `roleFor` (`inspecto/viz/result-set.ts`)
  owns the role heuristic and the Studio copy delegates to it. Pinned across both languages by
  `column-role.contract.json` + `ColumnRoleContractTest` + `column-role.spec.ts`.
* **Deliberate calls:** several date columns ⇒ derive `temporal` for **NONE** (an ambiguous tie-break is
  worse than none); `INTERVAL` and composite types (`STRUCT`, `MAP`, arrays) ⇒ `string`; a stored column
  the derivation stops producing ⇒ marked `hidden`, never deleted.
* **Refresh MERGES keys by owner** (`MaterializeTask.mergeColumns`): the stored document is
  seeded first and only the job-owned keys are restated, so hand-authored roles and labels ride through a
  refresh untouched. The code carries that rule as a comment precisely so a future field list cannot
  silently start dropping keys.

🔴 **The gotcha worth keeping: a declared contract key that no checker reads is not a pin.** `"coarseTypes"`
was added to `column-role.contract.json` and *nothing on either side read it* — a single grep hit, the
declaration itself — so the contract looked whole from the board while covering only the role heuristic.
Half a pin and a whole pin are indistinguishable until something reads the key.

⚠ **Two accepted limitations, stated rather than papered over.** Hidden columns accumulate with no pruning
story. And `query/query-columns.ts` `dbColumnType` is a **second** client-side interpreter of the same
DuckDB spellings that disagrees on four (`BIGINT[]`/`STRUCT`/`MAP` → `number`, `LOGICAL` → `string`); it
serves the query builder rather than stored Datasets, so it is pinned as a **documented exclusion** — see
`COLUMN-TYPE-SECOND-INTERPRETER-1`.

## Transforms & Modularity Seams

These behavior-preserving seams keep the engine modular (SQL / `.toon` / on-disk output unchanged).

* **`TransformCompiler`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/TransformCompiler.java`) — a pure
  SQL-expression compiler for the partition and date expressions. ⚠ *(Until 2026-09-08 this bullet described a
  `ColumnRule` registry `DATA_RULES` mapping `transformType` — `DATA_RULES` does not exist anywhere in the tree; the
  mapping lane is the Record Transformer since 2026-09-05, `configuration.md` §2 / `spec-pipeline-model.md`.)* An unrecognised non-blank type
  throws immediately (typo-safe); adding a type is a one-line registry edit. Note the deliberate asymmetry —
  data columns wrap DATE/TIMESTAMP sources in `CAST(... AS VARCHAR)` before the `TRY_STRPTIME` chain;
  partition columns route through `SqlBuilder.buildCastExpr`.
* **`ConsignmentIngestStrategy`** (`platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java`) — a
  package-private interface, one method `IngestOutcome ingest(Consignment, PipelineConfig)`, with two
  implementations: `CsvIngestStrategy` (default) and `StreamingPluginIngestStrategy` (renamed 2026-08-31; this
  page said `BatchIngestStrategy` → `ConsignmentIngestStrategy`, `CsvBatchStrategy` → `CsvIngestStrategy` and
  `StreamingPluginBatchStrategy` → `StreamingPluginIngestStrategy` until 2026-09-08) (the
  [plugin](spec-ingest.md#plugin-ingester) path). Each owns its DuckDB connection lifecycle; the shared commit+audit tail in
  `ConsignmentIngestor` is path-agnostic. The interface also carries static helpers (`dropTable`, `dropView`,
  `partitionColumns`).

Related: `OutputFormat` is the third such enum-as-strategy seam (see [output & sinks](#part-iii--sinks)).

## The editable round-trip — the editor's graph ⇄ the canonical config

> **Scope (2026-09-01 consolidation):** this section owns the AUTHORING half of the backend Pipeline
> model — the `PipelineEditable` round-trip and every named refusal, the `use:` ref homes, the
> parser family's retype/lower rules, route-branch arming, the settings surface, and dry-run.
> The model itself (IR, lift, validator, token execution model) is
> [architecture.md](architecture.md); the editor UI is
> [the editor guide](user-guide.md). Sections keep their
> historical §16–§22 numbers (§16–§20 moved here whole from the pipeline-graph architecture page).

### 16. The editable round-trip — the graph editor writes the canonical config (W5, 2026-08-01)

The read-only lift of §15 became a **round-trip**: the Pipelines editor no longer persists its own
`*_flow.toon`; it authors the canonical `*_pipeline.toon` the engine executes (plan U-A). The seam is a
dedicated pair, **not** `PipelineCompiler.toConfigMap` — that class is the Phase-1 *typed-record* parity
gate (it consumes the live `PipelineConfig` sub-records a `PipelineLift` carries). The editable pair
speaks the **config-file vocabulary end to end**, so nothing typed crosses the HTTP boundary:

- **[`PipelineEditable`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java)**
  — `toMap(cfg, raw)` lifts topology via `PipelineLift` but swaps each node's config for the **verbatim
  raw-map section** it owns (from the decoded file); `lower(g, existing, strict)` writes those sections
  back over the existing file. **Ownership rule:** a present node owns its section wholesale (a cleared
  field ⇒ a deleted key); an absent node kind's section is removed in `strict` mode, preserved in
  lenient. Keys the graph does not model (`description`, `dirs.status_dir`, `partitions`, a single-schema
  `dirs.quarantine` that has no owning node) **travel through a save untouched**. `enrichment` nodes are
  ignored by the lower — their truth is the registered `*_enrich.toon` companion (never a mirror; the D7
  split-brain lesson).
- **Named refusals, not silent truncation.**
  [`PipelineCompileException`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineCompileException.java)
  carries stable codes — `UNSUPPORTED_NODE` (a node type the flat config has no home for),
  `UNSUPPORTED_BINDING` (a home for the *node*, but not for the `use:` component ref it carries — see
  below), ~~`MULTI_SINK`~~ 🔴 **(DELETED — not a live refusal; corrected 2026-09-09.** Its constant went with the pipeline spec's Wave 0 when `sinks:` became a plural block — see `PipelineCompileException`'s Javadoc and `pipeline-editable.ts`. A >1-destination list now SAVES and is refused at `PipelineConfig.prepare()`: an execution-time refusal, not an authoring one.)
  (>1 distinct persistent `database` dir — the flat config expresses exactly one), and the strict
  completeness set `NO_ACQUISITION`/`NO_PARSER`/`NO_PERSISTENT_SINK`/`PARSER_NO_SCHEMA`. This closes the
  old `toConfigMap` behaviour of silently picking the first sink. `strict` = an `active` save or a
  brand-new file; an **inactive draft may be partial** (it overlays only what its present nodes own, so a
  half-built pipeline saves without erasing the rest — the same model Onboarding's stage saves use).
- **An AUTHORED `use:` ref has a home for two node kinds only** (`PipelineEditable.USE_HOME`, fixed 2026-08-14 —
  AUTHOR-1): acquisition's `connection/<id>` lands in the collector block, and the parser's
  `grammar/<id>` (authored Grammar) or `ingester/<fqcn>` (a plugin parser's synthesized binding) land in
  `parsing:`/`processing:`. **Every other node kind carries its settings inline**, so a ref on one is
  refused `UNSUPPORTED_BINDING`.

  ⚠ This matters because the editor's component picker is keyed on a node's **category, not its type**
  (`bindKindFor`), so it used to offer `transform/<id>` on *every* transform node — map, filter, join,
  dedup, summarize, route — and `sink/<id>` on every sink. Before the fix, `lower` read `use:` for the two
  homed kinds and **dropped every other ref in silence**: `PUT /pipelines/{name}/graph` returned
  `200 written:true` while `graph/raw` still showed the node with `{}`.

  **Follow-on (b) — the picker itself, closed 2026-08-15.** `bindKindFor` now answers `grammar` for
  `PARSE` and `null` for everything else, so the dead-end options are gone; `NEEDS_CONFIG` was split out
  so "must be configured" no longer derives from "binds a component" (a transform still needs config
  after losing its picker). The **category/type mismatch itself remains** — the two agree only because
  `PARSE` holds exactly one type, `parser`, and nothing structural forced that. It is now a tripwire
  rather than a latent bug: `bind-kinds.contract.json` is written from `PipelineEditable.typesWithUseHome()`
  by `BindKindHomeContractTest` (Java) and read by `pipeline-graph.contract.spec.ts` (TS), pinning the
  rule **a picker requires a home**. ⚠ Deliberately **one-way** — a home does not require a picker, since
  a `connection/` ref has a home but a Connection is not a `ComponentType` (no `GET /components/connection`),
  so the collector component owns that picker. ⚠ The artifact diff alone is **not** the whole guard: a
  falsification probe that gave `transform.map` a home left `bindableCategories` unchanged (the other
  transform types are still homeless) and was caught only by the explicit
  `neitherTransformNorSinkIsBindable` assertion — a category flips only when **every** type in it is homed.

  **Why refuse rather than preserve:** no code path in the engine resolves `transform/<id>` or
  `sink/<id>`, so writing the ref would produce a config that loads and then does nothing. That is the
  objection that removed the registry `schema` kind in unification W1, re-admitted only once
  `PipelineConfigParser.resolveSchemaRef` made such a ref executable. A named refusal tells the author
  at Save; an inert file tells them much later.

  ⛔ Two traps recorded here because the first diagnosis hit both. **`transform.map`'s absence from
  `STEP_KIND` was never the cause** — the five chain kinds *are* in `STEP_KIND` and their refs were
  dropped too, because `stepsOf` emits config only. And **"a map node is never author-configurable" is
  false**: `RowShaper.columnsOf` honours an authored `columns` list, `mappingSchemaOf` honours authored
  `rules`, and the flat file holds an authored mapping at `processing.mapping_file`. What a map node has
  no home for is the *ref*. Giving map a `steps:` entry remains wrong for a separate reason — a map node
  never enters the chain, so it would change **when `steps:` is emitted at all**.

  The offline mirror (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-editable.ts`) refused identically, in
  the same commit — a preview that accepts what the backend refuses is the same defect reversed.
  ⛔ **That TS lift/lower port was deleted 2026-09-24** (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`): its last reader, the offline mock,
  went in `f1553136`, and only `isProjectionSlot` survives in the file. Every mention of the SPA's
  `liftConfig` / `lowerGraph` below is history; the server's `PipelineEditable` is the only lower.

  🔴 **A DERIVED ref is not an unhomed one** (`PipelineEditable.DERIVED_USE`, 2026-08-15). The 08-14
  refusal was applied to every lowerable kind, which swept up **enrichment** — whose
  `use: enrichment/<name>` the editor writes onto the node itself each time it saves the companion
  (`node-config.dialog.ts`, W4b) **and `PipelineGraphRoutes` synthesizes on every `GET /graph/raw`** — so an
  untouched open→save round trip was enough to hit it. Since the companion is the truth and lower has
  "nothing to lower" for
  the kind, that ref is *dropped on purpose*, exactly as `MAP_DERIVED`'s `schema`/`csv` are. For one day
  it was refused instead, which made **every pipeline holding an enrichment node unsaveable**. The rule:
  a binding the product itself writes can never be an authoring mistake — only the kind's own derived
  prefix is exempt, so `transform/x` on an enrichment node still refuses.

  🔴 **And the lower was the SECOND gate, not the first.** `saveGraph` runs `parseAndValidateFlow`
  (→ `PipelineValidator.checkWiring`) *before* `PipelineEditable.lower`, and `enrichment` is not a
  `ComponentRegistry` kind at all (a companion registers through `POST /enrichment`, not
  `registry/<dir>/`), so the round trip 422'd on `UNKNOWN_USE_KIND` — an **older** defect that the
  engine-level fix alone left standing, and that a unit test against `lower` could never have seen.
  `PipelineEditable.isDerivedBinding` is now the one place the rule is spelled and both gates consult
  it. ⛔ Whenever a save-path rule is added, check it against **every gate on that path**: a rule spelled
  in one of two sequential gates is a rule that never takes effect. Pinned over real HTTP by
  `ControlApiPipelineCrudTest.anEnrichmentNodesCompanionBindingSavesOverHttp` — the unit test passed
  while the route still refused.

  ✅ **The picker that caused it is gone** (2026-08-15). `bindKindFor` now answers `grammar` for PARSE
  and null for everything else, so no transform or sink is offered a component whose every option ends
  in a refused save; the free-text "Use (component ref)" box went with it (its placeholder advertised
  `transform/my_component` — the refused shape), leaving the control unrendered so a ref an existing
  file carries is refused **by name** rather than silently stripped in the dialog. ⚠ Note what the two
  halves of `bindKindFor` were: "which component does this bind" and "does this node need
  configuration". Only the first is about bindings — the second is now `NEEDS_CONFIG`, or nulling the
  kinds would have quietly turned every blank transform 'configured' and dropped its Validate error.
  Acquisition and parser — the only two homed kinds — never open this dialog at all (a drawer and the
  Grammar editor), which is why every ref it could write was refusable by construction.
- **Routes** ([`PipelineGraphRoutes`](../../../../../inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java)):
  `GET /pipelines/{name}/graph/raw` (lift + a synthesized node per registered enrichment companion whose
  `triggers.on_pipeline` names this pipeline, joined to the persistent sink by a **derived display-only
  edge** `{from: <sink>, rel: "companion", to: <enrichment>, derived: true}` — see *Derived companion
  edge* below) and `PUT /pipelines/{name}/graph` (lower over the existing
  file, then the **same** `ConfigSpecs.pipeline()` + `ConfigSafetyValidator` gate + atomic write that
  `POST /config/write` runs — the editor is a *caller*, not a second write pipe). **Since 2026-09-01 the
  graph route also runs the three arming pre-checks** (`armedWithoutSchemaFindings` ·
  `routeArmingFindings` · `stepDisableFindings`) that `/config/write`/`/config/patch`/`/validate` gained
  2026-08-26 — before that, the editor's own Save answered `200 written:true` for a config that then
  failed to arm at the next `ConfigRegistry.rebuild` (one WARN log, silently skipped every cycle).
  Severity split unchanged: ERROR (422, nothing written) when `active: true`, WARNING on an inactive
  draft. Pinned over real HTTP by `ControlApiGraphSaveArmingTest`; ⚠ its fixture lesson: a node's
  `enabled` rides INSIDE `config` — a top-level node key is silently dropped by the codec. The `*_flow.toon`
  authoring writes (`POST /pipelines/authored`, `PUT`, `/nodes`, `/edges`) **retired**; grandfathered
  flows stay readable / runnable / deletable, never newly written (`CapabilityManifest` updated to match).

##### `processing.map` — the authored half of a map node (shipped 2026-08-15, AUTHOR-1 follow-on (a))

The ⛔ above ("a map node *is* author-configurable") described a hole that was still open: `lower` had no
branch for `transform.map` at all, so a projection typed into the map node's dialog was answered
`200 written: true` and dropped. The flat file now has a home for it, beside `processing.dedup`/`join`/
`summarize`:

```toon
processing:
  map:
    columns[2]{name,expr}:
      amount_major, "amount_minor / 100"
      event_day,    "CAST(event_time AS DATE)"
```

- **Authored vs derived is the whole design.** `columns`/`rules` are authored and lower verbatim;
  `schema` (the legacy schema map `PipelineLift` carries wholesale) and `csv` (moved within the node's
  reach by `PipelineDryRun`) are **derived** — never lowered, never refused. ⛔ A blanket "map has config
  ⇒ refuse" would have refused **every existing pipeline's save**, because every lifted map node carries
  a derived `schema`. Any key outside both sets refuses `UNSUPPORTED_MAP_KEY`.
- **`sql` on the projection slot is derived only beside a non-empty `fields[]`** (2026-09-25). The slot
  (`transform.sql`, id `map`) compiles from `fields[]` (`RecordTransform`), so a `sql` next to rows is
  the Fields grid's own rendering of them: `authoredMapConfig` skips it and it is never written. A `sql`
  with NO fields is a hand-written SELECT the slot has no home for, and still refuses
  `UNSUPPORTED_MAP_KEY` ("compiles from fields[]"). Found when every new Pipeline's first Save refused —
  the pane wrote the chain step's `{sql, fields}` shape into the slot; the SPA now writes `{fields}` only
  there (pinned by `ControlApiRecordTransformerSlotSaveTest`, which PUTs the captured payload).
- **A save refreshes the registry** (`CollectorService.refreshConfigs()` after the write, as the history
  restore route already did). `GET …/graph/raw` lifts the REGISTERED config, so before this a reopen
  straight after Save served the pre-save graph until the next poll cycle.
- ⚠ **This block executes.** Unlike its three neighbours (authoring-only until a recipe-driven executor),
  `RowShaper` reads `columns`/`rules` on the graph executor `PipelineJobRunner` already runs in
  production — so a preserved `columns` changes what the *next* run projects. `PipelineConfig.prepare()`
  therefore does **not** refuse it on an active pipeline, which would break the case it exists to serve.
- **The allow-list is pinned, not documented.** `MapNodeKeyContractTest` asserts
  `PipelineEditable.MAP_AUTHORED ∪ MAP_DERIVED == RowShaper.MAP_NODE_CONFIG_KEYS`, *and* re-derives that
  constant by scanning the `node.cfg("…")` reads in RowShaper's map-path region — because a constant can
  be edited without editing the code it claims to describe. A key that becomes executable without joining
  the allow-list is silently dropped on save: exactly the defect this section closes.
- **Two more refusals, both for losses the file cannot express.** `MAPPING_CONFLICT` — authored `columns`
  next to a declared `processing.mapping_file`: `columnsOf` checks `columns` first and never consults the
  schema, so the authored list would silently outrank a reference the operator declared on purpose. (The
  alternative — making `mapping_file` authoritative — was rejected as a behaviour change to a live
  production path; it stays a separate, deliberate decision.) `MULTI_MAP_CONFIG` — a multi-schema graph
  whose map nodes have drifted apart; one `processing.map` serves them all.
- ⛔ **Still not a `steps:` kind, and deliberately not mutually exclusive with `steps:`.** A map node sits
  between parser and sink in *both* spellings, so a chain entry would change when `steps:` is emitted at
  all (rewriting files that round-trip verbatim today), and removing `map` alongside the singular
  transform keys during a `steps:` rewrite would delete an authored projection.
- **Validation is hand-rolled** in `ConfigSafetyValidator` — the third list-of-objects walker beside
  `processing.schemas` and `sinks`, for the same reason: `FieldType` has scalar `LIST` and untyped `MAP`
  only, no list-of-objects primitive, so no `FieldSpec` can express the shape. Honest cost: the author
  gets a 422 with a hand-written message and **no generated form control**. Building that primitive (and
  migrating all three onto it) stays its own `BACKLOG` item.

*Verified: 6 cases in `PipelineEditableTest` (incl. the rich fixture's verbatim round-trip now carrying a
`processing.map`), 3 in `MapNodeKeyContractTest`, 6 in `modules/admin/pipelines/pipeline-editable.spec.ts` (promoted out of the deleted mock tree 2026-08-31; deleted 2026-09-24 with the TS lift/lower), and
`PipelineExecutorTest#anAuthoredProcessingMapProjectsThroughTheRealExecutor` — which runs config →
`PipelineLift` → `PipelineExecutor` over real DuckDB, because a config-format slice is not verified by a
`fromMap` test. Both new guards were falsified before being trusted (a bogus `node.cfg` read and a
disabled emit each turned them red).*

**Boundary (why a pipeline can be a grandfathered `*_flow.toon` graph and not a canonical config):** the flat
`PipelineConfig` cannot represent a graph that uses non-lowerable node types — `transform.derive`,
`transform.route`, `sink.materialized`/`sink.view`, non-`gap` CONTROL, or a second persistent sink. Such
graphs stay `*_flow.toon` and refuse to lower with a named code. Making them *run* needs the branch-aware
executor (§13 R3), still future work — W5 makes the editor *author* the canonical subset, it does not
widen what executes.

*Verified: `PipelineEditableTest` (8 — verbatim round-trip + every refusal), the rewritten
`ControlApiPipelineCrudTest` (7 — the canonical round-trip, grandfathered reads, retired writes), full
`inspecto-engine,inspecto` reactor 618/0/0; UI gate 1945/0 + prod build; a live offline walk (create →
canonical `*_pipeline.toon` written with the full space-convention dir set + `registered:true` → lifted
to the graph). The UI's TS lift/lower (`inspecto-ui/.../modules/admin/pipelines/pipeline-editable.ts`) pinned the same
refusals so the offline preview could not pass a topology the server 422s (deleted 2026-09-24, see above).*

##### The chain has two spellings, and the FILE owns which one (fixed 2026-08-18)

A pipeline's transform chain is written **one of two ways, never both** — the legacy singular blocks
(`processing.dedup` / `join` / `summarize`, `csv_settings.where`, top-level `route:`) or an explicit
top-level `steps:` list — because `PipelineConfigParser` refuses the two spellings in one file (there is
no non-arbitrary position at which a singular block would join a sequence). Two rules follow, and both
were violated by shipped code until 2026-08-18:

- ⛔ **The spellings are NOT interchangeable, so normalising one into the other is not cosmetic.**
  `PipelineConfig.hasExplicitSteps()` decides whether `PipelineLift` walks the **authored order** or its
  own constant `filter → join → dedup → summarize → route`, and whether `prepare()` demands a top-level
  `output_store:` before it will arm. `lower` used to pick the spelling from the **graph shape alone**
  (`isLegacyShaped`), so a hand-authored `steps:` file whose chain happened to fit the singular keys was
  rewritten into them on save. It now keeps `steps:` whenever `existing` already carries a non-empty one:
  **the spelling is the file's, not the graph's** — the same ownership rule as every unmodelled key. A
  file with no `steps:` key is untouched, so every pre-existing file and every editor-authored graph
  still takes the byte-for-byte legacy path.
- 🔴 **A reader that knows only one spelling cannot tell "absent" from "empty".**
  `RecipeConverter.toRecipe` synthesised its transform steps **only** from the singular blocks — which a
  `steps:` file never carries — so it projected an **empty chain in silence**, and the round trip wrote
  the config back with no transforms at all. Worst for a chain the singular keys *cannot* hold
  (`dedup → summarize → dedup`): every step gone, nothing raised. It now projects the authored list in
  order, through one builder per kind **shared with the legacy path** so the two cannot drift, and an
  unmodelled kind travels **verbatim** so `RecipeCompiler` names it in an `UNSUPPORTED_STEP` refusal — a
  projection must never quietly shorten a chain.

*Verified: `RecipeConverterTest` — the every-repo-fixture round trip (which is what caught it, once a
shift authored the repo's first `steps:` fixture in `ae2c0909`) plus 3 explicit guards: authored order
preserved, round trip in the authored spelling, and an unmodelled kind refusing rather than vanishing.
Full `-Pedition-advanced -fae` reactor 3458/0/0/5 at `f72f7fc8`.*

#### Derived companion edge — display-only, never saved (2026-09-24)

`GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1` (operator decision: a derived display-only edge kind).
`attachCompanionEnrichments` used to join each companion to the sink with a real `data` edge, and
`PipelineValidator` refuses that `ILLEGAL_EMIT` (a sink emits nothing), so an
untouched open → save and the candidate dry run of any Pipeline with a companion answered 422. The edge is
now `{from, rel: "companion", to, derived: true}` (`PipelineGraphRoutes.COMPANION_REL`):

- **Server:** `parseAndValidateFlow` — the one parse behind `PUT …/graph` and the candidate dry run — first
  drops every edge that is BOTH `derived: true` AND `rel: companion` (`withoutDerivedEdges`). Only that exact
  kind: a `data` edge out of a sink, with or without `derived`, still reaches the validator and is still
  `ILLEGAL_EMIT`. The companion node itself stays (the lower already ignores `enrichment` nodes), so a graph
  save never touches the `*_enrich.toon`.
- **SPA:** `AuthoredEdge.derived`; `withoutDerivedEdges` in `pipelines.service.ts` strips it from
  `savePipelineGraph` and the `dryRunAuthored` candidate; `authoredToG6` renders it with no run overlay,
  dotted and faded, and `isDerivedEdge` keeps `edge:click` from selecting it (not editable).
- ⚠ Not covered: the catalog export carries a `graph/raw` body opaquely; it does not validate data flow,
  so the edge rides along there. (The metadata bundle's `pipeline` item projects its graph with
  `PipelineEditable.toMap` alone, so it carries no companion edge; §21.)

*Verified: `ControlApiPipelineGraphCompanionTest` (real HTTP: raw shape, untouched PUT → 200 with the
companion byte-unchanged, candidate dry run → 200, a hand-authored sink `data` edge still `ILLEGAL_EMIT`);
mutation — parsing `body` instead of `withoutDerivedEdges(body)` turns the save and dry-run tests red with
`ILLEGAL_EMIT … does not emit relationship 'companion'`. UI: `pipelines.service.spec.ts`,
`pipeline-graph.spec.ts`.*

---

### 17. Pipeline-level settings — a dedicated surface for what the graph editor never models (2026-08-13)

> **Extended 2026-09-01:** the settings pair also carries the optional top-level `description`
> (display-only; nothing in the engine reads it) — its one post-creation write path. Trimmed on
> write; a blank clears the key from the file rather than persisting an empty string. Pinned by
> `ControlApiPipelineSettingsTest.descriptionRoundTripsAndBlankClearsIt`.

The `produces`/`reference` block (`produces: stream|reference`; `reference: {load, key, refresh_seconds}`)
is a **pipeline-level** property, not a node's — it names what the whole pipeline outputs (an ordinary
Stream, or a versioned Reference dataset other pipelines' enrichments can bind to by name), and
`PipelineEditable` deliberately never models it: §16's "keys the graph does not model … travel through a
save untouched" already covered it as opaque passthrough, but that meant it had **no write path at all**
outside hand-editing the `.toon` file, since there is no node to put a pipeline-level property on.

Rather than teach `PipelineEditable`/`PUT .../graph` about a non-node key (which would blur the "the flat
graph editor authors topology" boundary §16 draws), this got its own dedicated pair:
[`PipelineSettingsRoutes.pipelineSettings`/`savePipelineSettings`](../../../../../inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java)
(`GET`/`POST /pipelines/{name}/settings`) read/write `produces`/`reference` straight off the config file,
independent of the graph route. `savePipelineSettings` mirrors `relabel`'s gate: the full `ConfigSpecs.pipeline()`
+ `ConfigSafetyValidator` check runs, but only findings the write **itself introduces** (not ones already
present in the on-disk file) block it — a config already on disk was never subjected to the write-time
safety policy, so re-punishing it here would make most real deployments' pipelines un-settable. The
pre-existing `ConfigSpecs.pipeline()` cross-field rule (`reference-upsert-requires-key`) already enforced
`load: upsert|scd2` needing a non-empty `key` — no backend validation gap needed closing.

The Angular side is a plain reactive-form dialog (`PipelineSettingsDialog`, opened from the pipeline
editor's ⋮ menu → "Settings…"), not `<inspecto-schema-form>` — `reference.key` is a `FieldType.LIST` and
`fieldSpecsToAttributes`'s `TYPE_MAP` still deliberately skips served `LIST` (unchanged here; its only
other driver remains `transform.route`'s `branches`).

*Verified: `ControlApiPipelineSettingsTest` (5, real HTTP round-trip via `V1Body.of` envelope unwrapping —
absent block reads as the parser's own `stream` default, a valid reference block persists and reads back,
`upsert` with no key is refused with the on-disk file untouched, clearing a saved block restores the
default) + `CapabilityManifestTest` drift guard; full `inspecto` reactor 746/0/0. UI: `PipelineSettingsDialog`
spec 6/6, production build clean, no regression in `pipeline-editor.component.spec.ts` (50/50).*

### 18. Dry-run grows reference context and an honest empty answer (2026-08-13)

✅ **One condition, ONE name, on all three paths (`WB-05`, 2026-09-22).** A reference that parses but
whose file is not there now refuses **422 `JOIN_REFERENCE_MISSING`** from the dry-run and the test run,
carrying the RESOLVED target — the same code the save path already raised. 🔴 Before this, the save path
named the condition while the two test paths leaked the DuckDB internal *“Invalid Input Error:
Attempting to execute an unsuccessful or closed pending query result”*, which is not actionable by an
author. The `CREATE OR REPLACE VIEW` sat OUTSIDE the guard; it is inside it now. Row:
`TESTRUN-REFERENCE-REFUSAL-UNNAMED-1`.


Two §6 findings from driving the UI end-to-end, both closed in one change because they meet at the same
route and the same result record.

**DRYRUN-1 — a `transform.join` pipeline could not be dry-run at all.** `POST …/pipelines/authored/{id}/dry-run`
answered 422 `no ReferenceResolver supplied` for every join Pipeline, which is most realistic ones: the walk
reached the join node and `RowShaper.ReferenceResolver.NONE` refused. No new machinery was needed — the
seam already existed (`PipelineExecutor.dryRun` has had a resolver overload since the join executor
shipped); `PipelineDryRun` simply called the arity that refuses. Now `PipelineDryRun.run` takes an optional
resolver (the two-arg entry point still passes `NONE`, so nothing starts resolving references by accident),
and `PipelineGraphRoutes.dryRunFlow` supplies one built on the shared `ReferenceReader` — the same resolution
the production join executor and the Stage-2 `EnrichmentEngine` use, so a versioned reference store's
current/as-of view cannot mean one thing in a preview and another in a real run. The view is created on the
throwaway dry-run connection and dies with it.

⚠ **The backlog row's own stated constraint was refuted.** It required that "the write-root/path-jail gate
has to be honoured on the dry-run route too". It is deliberately *not*: a `path:` reference names a **data**
file, which routinely lives outside the config write root, so jailing it there refuses legitimate
references — and it buys nothing, because `POST /enrichment/preview` already resolves the very same `path:`
references through the very same reader with **no jail and no write root at all**. A jail on this route
alone would be security theatre that breaks working configs. If arbitrary-path reads through a preview are
a concern, they are a concern about *both* surfaces and need one deliberate answer.

**DRYRUN-2 — a dry-run that reached nothing returned a silent empty 200**, indistinguishable from success.
`Result` gained a `warnings` list (the shape the sink preview already returns; a compact constructor keeps
the old 3-arg arity for `@PublicApi` compatibility), populated for two silences: the sample reached no node
past the seed, and no sink received a row.

⚠ **The second rule is about SINKS, not "every relation is empty" — and a test caught the difference.** The
first implementation warned when no relation anywhere carried rows; a filter that drops all three sample
rows produces `data`=0 **and `dropped`=3**, so the warning was both false and noise. That run *is*
informative. What the operator cannot see from row counts alone is that **nothing would be written**.

The warning is rendered by `pipeline-dry-run-panel.component.html` —
DRYRUN-2's complaint was explicitly "indistinguishable from success **in the UI**", so a server-only field
would not have closed it. The panel spec asserts the rendered text, not the signal.

*Verified: `PipelineDryRunTest` 10/10 (+5: join-with-resolver, join-still-refuses-without, reached-no-node,
no-sink-row with a non-empty `dropped` branch, and no-warning-on-a-normal-run), `RowShaperTest` 16/16,
`PipelineExecutorTest` 5/5, `ControlApiPipelineCrudTest` 14/14 (+2 real-HTTP: a `path:` CSV reference
resolving through the route, and the warning reaching the response body); full backend reactor green. UI:
panel + mock-handler specs 34/34, production build clean.*

### 19. An armed route branch needs a predicate — and why `branches` still has no spec (2026-08-28)

Two conclusions from grounding the last driver of the map-list `AttributeSpec` type. One is a **build**,
one is a **refutation**; they are the same investigation because both turn on what a branch entry is.

#### 19.1 What a branch entry actually is — three keys, two of them derived

A `route:` branch is `{key, where, database}`, and the three have different owners:

| key | owner | where it comes from |
|---|---|---|
| `key` | **derived** | the name of the node's `route:<key>` edge |
| `database` | **derived** | stamped by `PipelineEditable.routeSection` from the sink the branch's chain TERMINATES at — the branch↔sink JOIN KEY on both halves of the round-trip. ⚠ Since MIDBRANCH-1 (2026-09-02) the pairing follows the chain, not the edge's direct target: with a `steps[]` sub-chain the `route:<key>` edge feeds the chain's first node |
| `where` | **authored** | the branch's SQL predicate; no other home |
| `steps` | **graph-owned** (MIDBRANCH-1, 2026-09-02) | the branch's ordered sub-chain — the SAME single-key-map vocabulary as the top-level `steps:` list (one entry grammar, `PipelineConfigParser.parseStepEntries`). `PipelineLift.emitSinks` flattens it into ordinary nodes (`<kind><schema-suffix>__<key>`, `__s<i>` for repeats) wired `route:<key> → step₁ → … → sink`; `PipelineEditable.branchChains`/`routeSection` reverse it, rebuilding `steps[]` from the walk through the shared `stepConfig` builders. A branch wired straight to its sink carries no `steps` key — byte-identical to pre-R3. Malformed chain shapes (fan-out, non-sink terminal, nested route) refuse `UNSUPPORTED_BRANCH_STEP`. Kinds allowed to ARM mid-branch: `filter`/`dedup`(consignment scope)/`summarize` (`RouteArming.BRANCH_STEP_KINDS`) — the ingest walk carries no `ReferenceResolver` and no `ExecutionContext`, so `join`, windowed `dedup`, and nested `route` refuse at save with the usual ERR/WARN_ROUTE_UNARMABLE split at all five gates. `ConsignmentGraphRunner.engages` traces a sink's feeding relation upstream through the chain and additionally engages on ANY route-fed chain (a chain node is executed by no other lane) |

#### 19.2 BUILD — `RouteArming` now refuses a branch with no `where`

`RowShaper.route` requires `where` on **every** branch of **both** modes (`reqStr(b, "where", …)`, in the
CASE builder and the clone loop). `RouteArming.refusals` did not check it, so a branch missing the
predicate passed every arming rule, **registered, armed, and then threw mid-run** on the first row.

⚠ **Reachable from the product itself, not just hand-editing.** The editor's `addRouteBranch` writes the
entry as `{key}` and wires its sink; the predicate is typed afterwards through a separate
`setRouteBranchWhere`. "Wired but blank" is the normal intermediate state.

The gate is the usual severity split, so this does **not** break mid-authoring saves: `active: false`
→ WARNING ("refuses only once it is activated"), `active: true` → ERROR/422. ⛔ The `default:` branch is
**not** exempt — it is one of `branches[]` and gets a WHEN like any other; "everything else" is the
CASE's ELSE arm, generated from `default:`, which needs no entry of its own.

🔴 **Three separate fixtures encoded the same hole, and each called itself well-formed.**
`RouteArmingTest.branch(key, db)`, `RecordDedupRouteConfigTest`'s arming cases (these did carry `where`)
and `ControlApiRouteArmingTest.wellFormedRouteSavesClean` — the first and last asserted success for a
route that could never have executed. The control-plane one only surfaced in the FULL reactor, not in the
targeted `inspecto-etl` run that made the change green. **A fixture named "well-formed" is a claim, and it
was wrong in two of the three places it was stated.**

#### 19.3 REFUTED — do not build a map-list `AttributeSpec` for `branches`

With sink `partitions[]` decided schema-owned (2026-08-13), `transform.route`'s `branches` was the type's
last remaining driver. It does not survive grounding, for two independent reasons:

1. **There is already an authoring surface.** The Recipe view's branch rows add/remove a branch, name it,
   and carry a `when …` input bound to `setRouteBranchWhere`. ⚠ The `NodeAttributes.TRANSFORM_ROUTE` help
   text said the branches are "edited on the canvas edges" — **wrong surface**, corrected here across all
   four mirrors (Java table, TS table, and both committed contract JSONs). That sentence is how a reader
   concludes no surface exists and sets out to build a second one.
2. **Speccing `branches` would destroy the derived pair.** A specced key is form-OWNED, and an owned leaf
   is deleted and replaced wholesale on save (the `ownedLeaves` rule that half-refuted the D7 partitions
   row). `key` and `database` are derived from the graph edge, so a form-owned `branches` re-writes them
   from whatever the form holds — which is precisely the branch-destruction bug `RouteBranch` was
   introduced to make structurally impossible.

⛔ The map-list `AttributeSpec` type now has **no driver at all**. Do not open it again without one.

*Verified: `mvn -o clean test -Pedition-advanced` → 3692/0/0/5, exit 0, all 25 modules SUCCESS, none
skipped (+5 tests). Rule falsified in both directions — stubbing the check to `if (false)` turns all four
new tests red. UI: `npm run test:ci` 2769 passed/5 skipped exit 0, `lint:tokens` + `build` green, prettier
clean.*

### 20. The parser family — per-format node types (2026-08-15, `6bc685cf`; merged from the former `design.md`, 2026-09-01)

Parse is a **family**, the way sink already was: the generic `parser` plus one type per format —
`parser.delimited` (P3a), `parser.fixedwidth` (P3b), `parser.asn1` (P3c), the `parser.json` /
`parser.text_regex` pair, and `parser.plugin` (all P3d, 2026-08-16) — the family is now closed. Decided as
B6 — no generic parse node with format tabs, because each format owns its own grammar shape and
complexities. `isParserType` is `PARSER` ∪ `SUBTYPE_FRONTENDS.keySet()`, so a subtype joins the family by
declaring its spellings and nothing else.

* **The lift retypes only on an EXPLICIT `parsing.frontend`.** Delimited is also the parser's *implicit*
  default (`PipelineConfigParser` defaults the key), so retyping every bare legacy file would change the
  node type of everything already deployed on a mere read. A file that never says the word keeps the plain
  `parser` type until its author opts in. ⚠ This caveat is **delimited's alone** — every other frontend
  (fixed width, ASN.1, JSON, text/regex) is never implicit, so those configs already declare themselves and
  all of them retype.
* **A subtype answers to every spelling of its frontend, and the set is the source of truth.**
  `SUBTYPE_FRONTENDS` maps each subtype to its spellings and `subtypeForFrontend` inverts it; both the
  lift's retype and the lower's mismatch check go through it, so the comparison is by **subtype, not by
  string**. Fixed width has two (`fixedwidth`, `fixed_width` — `PipelineConfigParser#parseFixedWidth`
  reads both) and neither contradicts the other. ⛔ **A lifted node keeps the spelling its author wrote**:
  canonicalising `fixed_width` on a read would rewrite a deployed file on the next save, and the verbatim
  round-trip is the property `PipelineEditableTest` pins.
* **Lower stamps the CANONICAL frontend** (the first entry in the subtype's list) onto a palette-fresh
  node — the file must say the word its type means, or the next lift silently loses the identity. A
  lifted node already carries one, so the round-trip stays byte-verbatim.
* **A frontend the parser SYNTHESIZES a binding for makes that binding DERIVED** (P3c). `frontend: asn1`
  is sugar: `PipelineConfigParser#asn1PluginBlock` builds the `Asn1RecordIngester` wiring at load, so the
  lift reads a class back and presents `use: ingester/<fqcn>` on a node whose only *authored* home is
  `grammar/`. Refusing that ref would make every ASN.1 pipeline unsaveable — the AUTHOR-1(b) enrichment
  regression reached from the opposite direction — so `DERIVED_USE` maps `parser.asn1 → ingester/` and it
  is dropped in silence. ⚠ The rule generalises: **whenever a load-time synthesis invents something the
  lift can present, check what the save path will then think the author wrote.** ⛔ It does **not**
  generalise to the other built-in subtypes: nothing synthesizes an ingester for a plain built-in, so an
  `ingester/` ref on `parser.json` / `parser.text_regex` / `parser.delimited` / `parser.fixedwidth` is an
  authoring mistake and refuses with `UNSUPPORTED_BINDING`. **`parser.plugin` is the second exception,
  for a different reason**: its `USE_HOME` accepts `ingester/` too (like the plain `parser` type, since a
  plugin's ingester is an AUTHORED `parsing.plugin.ingester`, not synthesized) — but `PipelineLift.parserNode()`
  computes `use = "ingester/" + fqcn` unconditionally whenever `s.ingesterClass() != null`, with no check
  for *which* subtype the node will become, so the lift presents that derived ref on plugin-backed nodes
  too. `DERIVED_USE` therefore also maps `parser.plugin → ingester/`, for the identical reason as ASN.1.
  ⚠ **Found but out of scope**: the plain `parser` type accepts `ingester/` in `USE_HOME` with no matching
  `DERIVED_USE` entry, so a legacy `processing.ingester`-configured pipeline never retyped to
  `parser.plugin` would hit `UNKNOWN_USE_KIND` on validate today — a pre-existing gap, not fixed here.
* ⚠ **A record mode is not a node type.** Binary fixed width (`record: bytes`) lifts to
  `parser.fixedwidth` like any other — the type spans the format — but its field geometry lives in
  `processing.ingester_config` and is executed by `FixedWidthRecordIngester`, **not** by the
  `fixedwidth.fields[]` slices; `ComponentPreview` refuses to preview it and `DuckDbCsvIngester` excludes
  it from the native path. Operator decision 2026-08-16: it keeps the **dialog**, because the drawer
  pane's slice table would govern nothing. It needs no `ingester/` use-home either — binary reaches its
  ingester through the plain `processing.ingester` CLASS key, not a `use:` binding.
* Two refusals, both named: `PARSER_FRONTEND_MISMATCH` (a `parsing.frontend` contradicting the node's own
  type) and `MULTI_PARSER` (a second parser-family node — the flat file has one parse slot, and what used
  to be a silent last-one-wins became authorable once the palette offered two icons).
* **Every single-slot kind refuses a second node by name (2026-09-23).** `acquisition`, `gap` and
  `transform.dedup.marker` each own ONE slot in the flat file (the collector block, `gap_detection`, the
  marker-dedup keys) and were the last three still last-one-wins — a second node silently discarded the
  first one's config on save. They now refuse exactly like `MULTI_PARSER`: `MULTI_ACQUISITION`,
  `MULTI_GAP`, `MULTI_MARKER`, naming the SECOND node, the message naming the node that holds the slot,
  strict and lenient alike (the discard never depended on `strict`). Same 422 `refusals[]` shape through
  `PUT /pipelines/{name}/graph` (the SPA's `lowerGraph` mirror of it was deleted 2026-09-24).
  The lift emits at most one of each, so no shipped config can trip them (`LiftLowerFixtureSweepTest`
  green); the rule only closes the hand-built / canvas-authored case. Pinned by
  `PipelineEditableTest.aSecondNodeOfASingleSlotKindRefusesByName`,
  `ControlApiPipelineCrudTest.aSecondAcquisitionNodeRefusesWithANamedCode` (and, until their deletion
  2026-09-24, three `pipeline-editable.spec.ts` cases).
* Every subtype's `use:` home is `grammar/` **only**, not `ingester/`: a plugin-ingester binding on a node
  whose type already names its format is a contradiction, refused rather than half-honoured.
* ⚠ **`use: grammar/<id>` is read-supported but NEVER authored** (operator decision 2026-08-15). Every
  engine-side piece of it is deliberately unchanged — `resolveGrammarRef`, the `PipelineEditable`
  lift/lower translation, `UNKNOWN_USE_REF`, `PARSER_NO_SCHEMA`'s `grammarBound` branch, `USE_HOME`, and
  the `BindKindHomeContractTest` tripwire — because a hand-authored file may still carry one. What
  changed is upstream: a Grammar component is now a **Template** the UI copies from, so nothing writes a
  new binding, and opening a bound node in the editor migrates it to an inline copy. ⛔ Do not "tidy up"
  the binding read path as dead code — it is the compatibility half of a deliberate split. See
  [`plans-archive/grammar-templates-not-bindings-plan.md`](../../../../archived-documents/plans-archive/grammar-templates-not-bindings-plan.md).
* Grouping by **category, not type string**: `PipelineCompiler.compile` and `PipelineDryRun` ask
  `PipelineNodeTypes.isCategory(t, PARSE)`, mirroring the sink family. A new parse subtype needs no edit
  there — but it *does* need its own `LOWERABLE` and `USE_HOME` entries in `PipelineEditable`.
* **No `NodeAttributes` spec is published** for these types on purpose. Their grammars nest two levels
  (`parsing.delimited.*`, `parsing.fixedwidth.*`) while the `key__nested` spec convention has only ever
  carried one, and the UI drawer owns the form shape — a best-guess table that looks authoritative is what
  that class's doc warns against. Consequently `node-attributes.contract.json` stays byte-unchanged as
  each subtype lands.
* ⚠ `BindKindHomeContractTest` has fired correctly **twice** — at `parser.delimited` and again at
  `parser.fixedwidth`. Its tripwire asserts the exact PARSE type list plus the derivation the UI's
  category-keyed picker depends on. The category stays *bindable* only because each new type arrived
  **with** a `grammar/` home; one added without one must flip `bindKindFor('PARSE')` to null, and this
  test is where that shows up first.

UI side: [Grammar configuration](../config/user-guide-grammar-config.md).

### 21. The pipeline bundle — selective export/import with dependency closure (R2, 2026-09-01)

`PipelineBundleRoutes` closes TRANSFER-ARCH-1: canonical `*_pipeline.toon` transfer with closure.

- **`GET /pipelines/{name}/bundle`** (ungated read — the export posture every transfer surface
  shares; secrets never travel): an `application/zip` of `manifest.toon` (format
  `inspecto-pipeline-bundle` v1, satellites `{path, sha256}`, `requirements[]`, `notes[]`), the
  pipeline toon, every satellite byte-verbatim under its bare basename, and each `*_enrich.toon`
  companion. **Closure = `PipelineConfig.referencedFiles()`** — the engine's own resolution
  (beside the config, `PathJail.resolveConfigRef`); ⛔ no second resolver exists. `collector.connection` travels as a
  secret-free `requirements[]` entry; a literal secret-looking value is masked to `***` + noted.
- **`POST /pipelines/import?name=&conflict=refuse|overwrite|rename`** (`canAuthorWorkbench`; raw
  zip body + query params — the codebase has no multipart parser, matching
  `POST /spaces/{id}/import`). Gate order: write-root 503 → manifest/spec/sha256 422 → zip-slip
  jail 403 (checked against BOTH the requested and final destination) → conflict 409 unless the
  policy says otherwise (`overwrite` lands on the REGISTERED file — the saveGraph anti-shadow
  rule; `rename` takes the first free `<id>_2`…) → retarget inside each body (name/id/stream/
  collector.id; `active: false` always; dirs re-derived from the space convention; enrichment
  re-pointed on `name`/`triggers.on_pipeline`/`input.database`/`output.database`) → the FULL
  saveGraph gate (spec + safety + the three arming findings) → atomic writes, satellites before
  the pipeline, then register.
- ⚠ **Satellites are written before the safety gate and deleted on an ERROR verdict** —
  deliberate: `resolveSchemaRef`/`ConfigSafetyValidator.resolveRef` accept a config-relative
  candidate only when it exists on disk, so validating rewritten bare-basename refs against an
  empty directory would false-422 every bundle. A refused import leaves nothing written.
- Pinned over real HTTP by `ControlApiPipelineBundleTest` (round-trip with byte-compared
  satellites · conflict matrix · zip-slip · requirement-no-secret · 503/404/422 triple). ⚠ The
  capability-manifest drift guard fires on any new gated route — declare it in
  `CapabilityManifest` in the same change (only the FULL reactor showed it).
- The UI's Duplicate + row export still ride the client stream-bundle — migration onto these
  routes is the BACKLOG follow-up.
- **The Metadata Bundle's `pipeline` kind is this door (2026-09-25, BUNDLE-AUTHORED-PIPELINE-STORE-1
  option B, operator).** The route bodies were split into two package-private cores,
  `PipelineBundleRoutes.exportClosure` (→ `Closure{id, manifest, entries}`) and `importClosure`
  (everything after the write-root gate → `Imported{written, body}`), and `BundleRoutes`'
  `RegisteredPipelineBundleSource` calls them: the item's `content.closure` is `{manifest, files}` —
  the same manifest and entries as the zip, text as UTF-8 (`{base64}` otherwise) — so the sidecars
  travel INSIDE the one item and meet the same gates in the same order. ⚠ `exported_at` moved out of
  the core into the zip route: inside an item's content it made every export hash as drifted.
  ⚠ The item also carries the editable graph (`PipelineEditable.toMap`, the `graph/raw` shape) as a
  PROJECTION for lineage and Import as draft; a write-through import ignores it. ⚠ `BundleRoutes`
  decides skip-vs-overwrite first, so the core sees `conflict=overwrite` for an existing id and
  `refuse` otherwise (never `rename`); every `ApiException` / ERROR verdict becomes that item's
  `failed`. Pinned by `ControlApiPipelineKindBundleTest` (round trip into a fresh instance under a real
  Subject: 403 without the capability, registered + listed + dry-runs, skip/overwrite; tampered sha
  and graph-only items fail with nothing written).
- The metadata bundle's `authored-pipeline` kind is **export-only** since the same change — an import
  carrying one is 422 (W5: grandfathered `PipelineStore` graphs are never newly written) — and a
  `PipelineStore` graph no longer shadows a registered pipeline of the same id in Run to here /
  dry-run (the registered config wins; the store answers only an unregistered grandfathered id).

### 22. The Stream bundle's forward closure — the Reference Datasets a Pipeline reads (W5, 2026-09-24)

The per-Stream zip (the route keeps its legacy `datasources` path: `GET /spaces/{id}/datasources/{ds}/export` → `DataSourceBundleResolver` →
`BundleExporter`) already carried the **reverse** closure — what names the Pipeline (jobs, Decision Rules,
Expectations, Datasets over its store, Alert Rules). It now also carries the **forward** closure: what the
Pipeline itself reads. Operator decision 2026-09-24: build it. ⛔ `PipelineCompiler.toConfigMap` is
deliberately not migrated.

**Every way a Pipeline declares a Reference read** (grounded against the parser and the two executors):

| Where | Spelling | Carried? |
|---|---|---|
| `processing.join.reference` | `reference/<id>` | ✅ — surfaces as a `join` Step through `PipelineConfig.steps()` |
| top-level `steps[]` → `join.reference` | `reference/<id>` | ✅ — same seam |
| `route.branches[].steps[]` → `join.reference` | `reference/<id>` | ✅ — walked from the `route` Step config and `routeConfig()` |
| `*_enrich.toon` companion → `references.<alias>.ref` | `<id>` | ✅ — the companion itself travels too (matched on `triggers.on_pipeline`, the `PipelineBundleRoutes.companionsOf` rule) |
| any of the above as a **path** (`data/ref/x.csv`, `references.<alias>.path`) | a data path | ❌ — data, and a config bundle carries no data |
| `transform.lookup` | inline `mappings` | ❌ — reads nothing |
| recipe `transform: {join: references/<id>}` | `references/<id>` | ✅ — accepted beside `reference/`, as `PipelineConfigParser.referencePath` treats both as ids |

A by-name id resolves the way `ReferenceReader.sqlFor` resolves it at run time — the loaded Pipeline whose
`identity().pipelineName()` equals the id **and** that declares `produces: reference`; anything else (a
dangling id, a Pipeline that produces no Reference) is warned and not carried. A carried Reference is its
producer's `*_pipeline.toon` **plus its connection and schema files**, because the import gate 422s on a
Pipeline whose connection or schema the target lacks. The closure is **transitive** (a producer that joins
another Reference brings it) and never revisits the Stream itself, so a self-join or a cycle adds
nothing. `DataSourceBundle.files()` de-duplicates, so a file both closures reach — a connection the producer
shares, the same schema — rides once.

**Import round trip.** The manifest indexes the forward closure as `references: {<producer id>: [entries]}`,
listing only the entries that Reference alone brought. On `POST /spaces/{id}/import` (and its preview),
`BundleImporter.keepExistingReferences` drops every carried Reference the target already hosts — its entries
are not written and it is not a 409 conflict — and reports it in `referencesKept`; under
`?on_conflict=overwrite` nothing is narrowed and the carried copy replaces the target's.

**A missing connection warns, it does not refuse (operator, 2026-09-25).** Until then the Stream-bundle
import's connection gate was an ERROR — 422, nothing registered — which made moving a Stream into a
Space that has not yet onboarded its connection impossible. Now `BundleImporter.disableForMissingConnections`
runs on the zip entries **before** the write: a connection id is known when the target's registry holds it or
the bundle carries its `*_connection.toon`; every config that names an unknown one is written **switched off
by the switch its own kind reads** — a Pipeline (`collector.connection` or `webhook.connection`) gets
top-level `active: false` (`PipelineConfigParser`; the poll cycle runs only an active Pipeline), a job
(`job.connection`, `objectstore.export`) gets `job.enabled: false` (`JobConfig`). No cross-kind stamp: a
Pipeline has no `enabled` key, and a job no `active`. Only a disabled entry is re-serialised (canonical TOON);
every other entry keeps its exact bytes. The Pipeline still **registers** — it is present and inactive, so an
operator connects the profile and activates it in place. The response (and the preview) carries
`connectionWarnings: [{connection, code: WARN_UNRESOLVED_CONNECTION, message: "connect X to enable …",
disabled: [{kind, name, file}]}]`, one per missing connection; the preview's per-file finding is a WARNING, so
`valid` no longer goes false for this. The schema-existence and containment halves of the commit gate
(`DataSourceRoutes.referentialFindings`) are unchanged and still refuse the whole bundle. This now matches
`POST /pipelines/import`, which always warned (and always lands `active: false`). ⚠ `POST /spaces/import`
(seed a **new** Space from a whole-Space bundle) never ran this gate and still does not — a whole-Space export
carries its connections. Pinned by `ControlApiBundleImportTest.aBundleNamingAnUnknownConnectionImportsWith…`,
`…AllResolveCarriesNoWarnings…`, `previewReportsAnUnknownConnectionAsAWarningToo` and
`BundleImporterTest.disableForMissingConnections…`. An imported companion `*_enrich.toon` is
written but not hot-registered — it goes live on the Space's next boot, as in a whole-Space import.

Pinned by `DataSourceBundleResolverTest.carriesTheReferenceDatasetsThePipelineReadsAndNothingElse` (all
three by-name read sites, transitive hop, shared-file de-dup, the manifest index, and the negatives: path,
dangling id, non-producer, self-join, a Pipeline reading nothing) and, over real HTTP,
`ControlApiBundleImportTest.anExportCarriesTheReferenceItReadsAndAnImportRoundTripsOrKeepsTheTargetsOwn`.

### Every fixture survives the round trip — the standing gate (W0, proven 2026-09-06)

`LiftLowerFixtureSweepTest` (inspecto-engine) runs every `spaces/**/*_pipeline.toon` in the repo through the
editor's own seam — `PipelineEditable.toMap(cfg, raw)` → `PipelineCodec.fromMap` → a **strict** `lower` over the
original — and asserts the decoded original comes back verbatim (modulo the always-written `active`). All 22
fixtures pass, which is the proof the unification plan's W0 asked for before W4/W5. ⚠ The proof is about
THIS seam: a first cut used `PipelineLift.lift(cfg)` alone and every fixture "failed", because the bare lift
materialises engine defaults (`duckdb_threads=0`) and typed records (`PostActionConfig[…]`) that the editable
projection strips — that is the projection's business, not a loss. `RecipeConverterTest` gates the recipe
projection over the same fixtures; a hand-authored fixture must pass both before it is committed.

✅ **Verbatim means the decoded map, never the bytes (D4, signed 2026-09-22).** A workbench save rewrites
the whole TOON file: measured over the corpus, keys and values survive intact — including undeclared
`dirs` leaves and `description` — but blank lines, column alignment, quoting, key order within
`collector` and the trailing newline do not. 🔴 The rewritten quoting (`delimiter: "|"` →
`delimiter: |`) **looks like** the classic comment-character loss and was first read as corruption; it is
not — the reader parses those back identically and a re-run produced the same row counts. The operator
**accepted the churn** rather than fund a format-preserving writer, which would add a second drift
surface of its own; what is funded instead is the guard — a round trip through the real `PUT …/graph`
asserting the key set AND the re-read node configs (`WB-01`, which this in-process sweep cannot see).
⚠ So when a diff looks alarming, compare key sets and re-read values, never the text. Row:
`GRAPH-SAVE-REFORMATS-CONFIG-1`.

---

## Part II — Execution

**What this covers.** What happens when a Pipeline actually runs. Two engines share one config vocabulary: the ingest engine moves
files from an inbox into stores, and the Job engine works over data already at rest. One Pipeline can run in
five lanes (two ingest lanes, an at-rest Job, a scratch test run, and a paused "parked" state), plus a
post-sync lane that derives tables after commit. This part says which lane does what, how each is triggered,
what each writes, how failures are retried or replayed, and how a test or dry run stays harmless.
How an author writes a Pipeline is the Pipeline model ([Part I](#part-i--the-pipeline-model) above) and the
editor guide ([user-guide.md](user-guide.md)); the requirement of record is [plan.md](plan.md).

## Two engines and their triggers

This capability is **how work actually runs**, and the organising fact is that there is not one execution
engine here. There are two, triggered independently, sharing one config vocabulary:

> **The ingest engine** polls an inbox, plans Consignments and drives a Pipeline over files *in motion*.
> **The Job engine** fires on cron, on a commit, on a signal or by hand, and runs work over data *at
> rest* — including a Pipeline's own second stage, hosted as a Job.

The code names this split itself: one of its trigger enums is annotated *the two-scheduler split*. The
boundary is binding: in-motion and at-rest compose as **producer and consumer over a shared store, never by
nesting** — which is why sub-Pipelines and an `ingest` Job Type are design refusals ([plan.md](plan.md) §6).

### Two engines

| | Ingest engine | Job engine |
|---|---|---|
| Drives | a Pipeline over files in motion | anything over data at rest |
| Triggered by | the poll cycle, or an entry-node trigger | cron, a commit, a signal, or by hand |
| Unit of work | a **Consignment** | a **Run** of an Executable |
| Bounded by | its own semaphore, sized at construction | a hot-resizable permit holder, default four |
| Exclusion | a per-pipeline non-reentrant guard | a per-job lock that records a skip |
| Terminal record | three CSV ledgers plus the commit log | the run ledger, plus an optional database projection |

They meet at exactly one place: a commit publishes an event, and that event is what a downstream trigger
consumes. An authored Pipeline's *second* stage crosses the boundary in the other direction — it is hosted
as a Job, because a Job is the only thing that reads data already written.

How the two engines bound concurrency (the ingest run budget, the Run bound, the four-layer broker and stride
scheduling) is owned by [spec-ingest.md](spec-ingest.md#part-b--concurrency-the-four-layer-hierarchy) (Part II, Part B).

### Two trigger models

The entry-node trigger grammar is in [architecture.md](architecture.md); the Job trigger fields in
[../processor/spec-jobs.md](../processor/spec-jobs.md).

**Pipeline triggers** are declared on the entry node — six kinds (`PipelineTrigger.Kind`): an interval, a
cron expression, an event, manual, the default poll, or a **stream** (`{type: stream, records, max_wait}`, the
continuous lane, still on the loop scheduler; `ASSURE-PUSH-INGEST-1`). The event kind carries what it listens for — a **commit** from an upstream pipeline, or a
**dataset** write — and the two namespaces are explicitly fenced from each other so they cannot collide.
There is a coalescing window. **There is no catch-up.**

**Job triggers** are fields on the job config: cron, on-pipeline, on-signal with an optional guard, and
manual. **Catch-up is here**, and it replays exactly **one** missed fire at startup from the run ledger's
last-start times. The guard grammar is flat and **fail-closed** — a missing field or an unparsable term is
false.

**How a commit-driven trigger actually fires**, end to end: the processor takes a commit consumer, the
service layer passes it a bus, and the bus publishes to three subscribers — the job engine's
consignment-event handler, a mirror that republishes every commit as a signal so a signal trigger also
works, and the ingest scheduler's upstream-commit and dataset-write handlers.

⚠ **A `dataset.write` is announced only once the run that produced it has COMPLETED**
(`DATASET-PUBLISH-ON-FAILURE-1`, 2026-09-15). `ConsignmentProcessJobType` used to emit inside
`persistSummaries` — i.e. before `persistDerivedTables`, which throws, and before every later chain step
had run — so a run that went on to fail had already told the platform its Dataset was fresh, and an
`on: dataset` trigger could act on a write its own run then abandoned. Announcements are now accumulated
across the chain and fired after it closes. `MaterializeTask` always had this ordering (its emit sits
after the swap), so the two producers now agree.
⛔ Deferring is **sufficient** precisely because a processor signals failure by **throwing** —
`ProcessorResult` distinguishes only "did the work" from "nothing to do" — so reaching the end of the
chain *is* the success condition. ⚠ Granularity is unchanged: still one signal per (store, producer),
summed. Whether a run should emit **one** signal, and whether derived tables should announce at all, are
contract changes for `dataset.write` consumers and are deliberately still open.

🔴 **The `on: dataset` handler DOES have a self-loop guard — since 2026-09-15 (`DATASET-SELF-TRIGGER-1`).** Its javadoc previously said one was unnecessary because *“the producer is a Dataset write (a job/materialize), never the triggered pipeline’s own commit”*. That was **false for the consignment path**: `ConsignmentProcessJobType` emits from inside a pipeline’s own run, so a pipeline whose processor writes store `S` and declares `{on: dataset, from: datasets/S}` re-triggered itself — coalesced, so it degraded into a hot loop rather than a crash, and read as *busy* rather than *broken*.
⛔ **The obvious one-line guard could not have fired**, and would have looked right in review. `producer` was a bare String meaning different things at different sites — a JOB name from `MaterializeTask`, a PROCESSOR component id from `ConsignmentProcessJobType` — and `CollectorService` **dropped it entirely** before calling the scheduler, so nothing to compare ever arrived. The fix was therefore a vocabulary change first: `producer` is now a structured `Ref {kind, id}` on the Signal’s `actor` slot, and the **owning pipeline** travels as its own payload key, which is what the guard matches on.
⚠ **A `null` owning pipeline means “suppress nothing”, and that is load-bearing.** A cron or manually fired job genuinely has no owning pipeline (only an `event:<pipeline>` trigger carries one), so reading absence as “suppress” would have silently killed the ordinary materialize-then-trigger path that predates the guard.
⚠ The wiring was the defect, not the comparison — a guard unit-tested only on `PipelineScheduler` would pass while the shipped path stayed broken, which is why `CollectorServiceTriggerTest` drives the real subscriber end to end.

⚠ **There is no on-commit *job* trigger.** Commit-fired jobs ride the signal bus. Do not cite the
graph-structure refusal as the thing that keeps job work at rest — that is a pipeline-edge relation and a
different mechanism.

## Execution lanes

One Pipeline definition can execute in **five distinct lanes**. Before this page, each lane's concept
re-explained the split in its own vocabulary (~6 framings across five files, none authoritative —
inventory finding D-6). This page is now the **single owner of "which lanes exist"**; the mechanism
sections below (and [spec-ingest.md](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) for the graph fork) describe *how their lane works*.
⚠ The five lanes are the ways one Pipeline definition executes over a Consignment *before or instead of
commit*; the **post-sync lane** — `ConsignmentProcessor` Steps reading a Consignment *after* commit — is a
Job-hosted lane of its own, described in *Post-sync step chains* below.

Vocabulary (spec §13 D2, the token model): a Step receives a **Consignment token** and resolves data
by reference — edges never carry records. Every "what executes" cell below describes which code
resolves the token's data, not rows flowing along edges. *One note:* the runtime edge model itself
converges under Phase 7's major-bump window (D2's second half); the Batch→Consignment **rename** half
of Phase 7 shipped (`ff33246a`) with deliberate wire/persisted residuals — see *Naming* below.

### The lanes

| Lane | Triggered by | Divert / fork predicate | What executes | What it writes | Mechanism owner |
|---|---|---|---|---|---|
| **Ingest, flat** | the poll cycle (or `trigger:` schedule/event/manual) planning a Consignment over inbox files | the default — everything the graph fork refuses: no scratch dir, a node between map and sink, a decision rule that actually routed rows | `ConsignmentIngestor.process` → `CsvIngestStrategy` / `StreamingPluginIngestStrategy` → `DataTransformer` → `writeAndTrace`'s flat `sinks[]` loop (reference-version stamp + decision rules run here) | partitioned output per destination + the full commit tail: manifest, backup, **markers LAST**, three CSV ledgers, dedup ledger, watermark, `ConsignmentEvent` | [architecture-stage1](architecture.md) (the core path) · [branch-aware-ingest](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) (the fork rules) |
| **Ingest, graph fork** | the same poll-driven ingest — this is a fork *inside* `writeAndTrace`, not a separate entry point | `route:` pipeline: `ConsignmentGraphRunner.engages(PipelineLift.lift(cfg))` (counts **branches**, not sink nodes). Non-route: `ConsignmentIngestStrategy.graphLaneCarries(cfg)` — scratch dir configured, lifted sinks 1:1 with `cfg.sinks()`, every sink `data`-fed directly off the `transform.map` seed | `ConsignmentGraphRunner.run` drives `PipelineExecutor` over the `route → sinks` subtree, seeded **below parse/map** — the lane performs only the WRITE, never re-runs upstream nodes | each branch via `IngestSinkWriter` to its `database`-paired destination, committed through a durable per-Consignment `BranchCommitLog` under `dirs.temp`; then the SAME commit tail as flat (shared code, not a mirror) | [branch-aware-ingest](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) |
| **At-rest job** (incl. the Stage-2 `pipeline_config:` chain) | a `type: pipeline` Job — cron / `on_pipeline:` / manual, or the config-less `POST /pipelines/authored/{id}/trigger` | the graph source picks the flavor, **mutually exclusive, exactly one**: `pipeline:` (an authored graph; legacy `flow:` dual-read) or `pipeline_config:` (a flat file's `steps:` chain, lifted at run time by `PipelineLift.stageTwo` into `source_store(landed) → chain → sink.persistent(output_store)`) | `PipelineJobRunner` → the production `PipelineExecutor`/`RowShaper` over `source_store` views (`SourceStoreReader`); this is where `dedup`/`join`/`summarize` execute — both ingest lanes refuse them | sink stores via `PartitionSinkWriter` (self-registers §11.3), `JobRun` → `DbJobRunStore`, watermarks, `sink.view` `ViewDefinition`s, `ConsignmentEvent` | *The at-rest job lane* (below) · [stage1-architecture §Step 3](architecture.md) (the Stage-2-as-job split) |
| **Scratch** (dry-run + run-to-here) | `POST /pipelines/authored/{id}/run?to={nodeId}` (`canAuthorWorkbench` — the **simulate** verb, never `…/trigger`) and the editor's dry-run | none — always scratch, by construction: `PipelineTestRun` calls only the ingest half of `ConsignmentIngestor.process` (never commit/audit/provenance) over files **copied** into a scratch root; `PipelineExecutor.dryRun` bounds the walk to the target's ancestor closure | the real parse over picked inbox files + an in-memory `PipelineDryRun` walk (seed bounded to 1000 rows); a disabled node here is an in-memory **bypass** | **nothing in production** — scratch root only; returns `PipelineRunResult` | *The scratch lane* (below) |
| **Parked-then-drained** | not a fifth executor — the ingest graph fork's durable pause: the walk reaches a **disabled SINK** (via `processing.disabled_steps`) with a live inbound relation | armable only for a sink strictly inside an armed `route:` subtree (`StepDisableArming` refuses every other shape, at save and at `prepare()`) | park: `PipelineExecutor.ParkWriter` COPYs the branch relation to `dirs.backup()/parked/`, `ConsignmentIngestor.parkSource` records the manifest + `ParkedCommit` sidecar, status `PARKED`. Drain: `POST /runs/{name}/drain` → `DrainCommand` — **no re-walk**, registers the park tables and runs the real `finalizeSource` for the whole Consignment | park: park tables + sidecar + `pipeline.batch.parked` Signal, enabled branches already committed; drain: the ordinary commit tail over the union of sidecar outputs and its own | *Park and drain* (below) |

Two boundary rules the table encodes, worth stating once:

- 🔴 **The two ingest lanes are one write, two mechanisms.** The graph fork replaces only the write
  segment of `writeAndTrace`; parse/map are never re-run and `dedup`/`join`/`summarize` between map and
  sink are refused on **both** — they execute only in the at-rest job lane (batch independence is the
  dividing rule, spec §7.1).
- 🔴 **`…/run` vs `…/trigger` is the scratch/production boundary.** `…/run` is the simulate (scratch
  lane), `…/trigger` the operate (at-rest job lane). The verbs must never be merged.

**Recovery affordances per lane (2026-09-02):** ingest lanes — `POST /runs/{name}/reprocess`
(whole-Consignment redo; refuses when an output was compacted away; **retracts the run's dedup-ledger
claims beside `registry.supersede`** so a windowed dedup re-admits the redone rows), and since
2026-09-25 `POST /runs/{name}/replay-rejects` (one file's rejected records only — X4, below); parked —
`POST /runs/{name}/drain`; at-rest job lane — `POST /jobs/runs/{runId}/replay` (canOperateRuns;
re-fires the job through the normal lifecycle with its configured defaults — the run ledger persists
no per-run params, and the response `note` says so; the new run's `trigger` field carries
`replay:<originalRunId>` so the linkage is followable; 409 while the job is running or no longer
registered). **Bounded COMMIT retry (X1, 2026-09-02):** the ingest lane's retry is the next cycle's
re-encounter of a FAILED Consignment's files; `CommitRetry` bounds it — durable per-file attempt sidecars
under `<status_dir>/retries/`, exponential backoff honoured on the run path only (a waiting file is still
pending), exhaustion ⇒ quarantine under `retry_exhausted` + one CRITICAL `pipeline.batch.retry_exhausted`
Signal. ⚠ Grounding refuted the plan's queue: nothing on the ingest lane classifies transient vs fatal
(the catch sites collapse every read failure into a permanent `unreadable` quarantine, duplicated across
four strategies), "DuckDB lock" as a trigger was aspirational, and the real gap was the OPPOSITE of the
plan's fear — poison never stopped. Member-level `unreadable` stays as it is. Also
lane-wide since 2026-09-01: the Stage-2 **orphan-`output_store:` check is default-on** in every
space (transition-debounced signal; `-Djobs.orphan.audit=false` to disable) — see
[architecture-stage1](architecture.md) §Step 3.

**The COMMIT retry affordance (X1 deferrals, 2026-09-25; decisions Q1–Q4 in
[retry-affordance-design](../../../../archived-documents/plans-archive/retry-affordance-design.md)).** Three routes over
`CommitRetry`, **per pipeline** (Q4) and addressed by the **poll-relative FILE path, never `batchId`**
(minted per cycle, so a batch-keyed route would be dead by the second cycle):
`GET /runs/{name}/retries` (open read; bounded at 500 with `truncated` + the true `total`),
`POST /runs/{name}/retries/retry-now {file}` and `POST /runs/{name}/retries/cancel {file}` (both
`canOperateRuns`, string literal). As built:

* **retry-now clears `nextRetryAt` ONLY — the attempt count is kept** (Q2), and the response's `note` says
  how many attempts remain before `retry_exhausted`; resetting attempts would make a poison file immortal.
* **cancel = quarantine NOW under `retry_cancelled`** (Q1) and spend the record — the same "fate decided"
  end as exhaustion. ⛔ It never merely drops the sidecar: with no sidecar the file is retried *unboundedly*
  (the trap the old "delete the sidecar by hand" workaround fell into).
* **The engine half never throws.** `CommitRetry.list/retryNow/cancel` return a `Listing` / `Outcome`
  (`RESCHEDULED`, `CANCELLED`, `NO_RETRY_STATE`, `NO_RECORD`, `NOT_IN_INBOX`, `ALREADY_QUARANTINED`, `BUSY`,
  `FAILED`); an unreadable sidecar is listed with `readable: false`, never a 500. The route maps the
  non-acting outcomes to **404** (no record / not in the inbox) and **409** (no retry state, already
  quarantined — naming the reason directory found in the quarantine tree — mid-cycle, or could not act),
  each with the reason, so nothing appears to act that did not.
* **`keepsRetryState: false` ≠ an empty list.** No `dirs.status_dir` ⇒ no records at all ⇒ unbounded retry;
  the list says so in a `note` rather than returning a reassuring `[]`.
* **Serialised against the poll**: `CollectorService.commitRetryAct` takes the pipeline's run claim with
  `tryAcquire` (never blocks) — a pipeline mid-cycle answers `BUSY` (409) and nothing is attempted, so a
  cancel can never move a file a cycle is ingesting.
* The per-file `CommitRetry.clear(File, cfg)` sits beside `clear(Consignment, cfg)`, which now loops over it;
  `CommitRetry.inboxFile(cfg, rel)` is the path jail (blank, absolute or escaping ⇒ `null` ⇒ 403).
* **UI (Q3) shipped 2026-09-25** as the Run Detail **Commit retries** tab — see
  [run-detail](user-guide.md) § *Commit retries*. The routes came first, as Q3 decided.

**Per-pipeline `processing.retry` (X1 deferral, 2026-09-25).** The cap and backoff are now per pipeline:

```
processing:
  retry:
    max_attempts: 3        # 0 = unbounded for THIS pipeline
    initial_backoff: 30s   # Collector duration grammar: bare seconds or N s|m|h|d
    max_backoff: 2h
```

Every key is independently optional and **inherits its `-Dingest.retry.*` global when unset**
(`max` 5, `backoff.initialMs` 60000, `backoff.maxMs` 3600000) — so an absent block is today's behaviour byte
for byte. The `processing.intake` posture exactly: `PipelineConfig.commitRetry()` carries only what the
author STATED (`CommitRetryPolicy`, nullable fields, `null` when the block is absent), and the merge happens
in `CommitRetry.policy(cfg)`, which `recordFailure`, `due`, the list route and retry-now's `note` all read.
⚠ **No spec defaults, deliberately** — the config pane seeds spec defaults into the saved file, which would
pin a pipeline off the live global. Gates: declared in `ConfigSpecs.pipeline()` (so `AcceptedConfigKeys`
accepts the block as `spec`-declared — `PARSER_ONLY` may only shrink, so it is not listed there);
`ConfigSafetyValidator` refuses `max_attempts` outside `[0, 1000]` and a negative or non-duration backoff
(**422** on `/config/write`); the parser refuses the same at load, naming the key. Editor: `retry__*`
attributes on `sink.persistent` nest to the node's `retry` map and lower to `processing.retry:`
(`PipelineEditable`, beside `intake`), pinned end-to-end by `NodeConfigNameContractTest`.

**Record-level replay evidence (X4, 2026-09-24/25).** The ingest lane's `?dryRun=true` runs the real
ingest pass over member copies in a deleted scratch root (`PipelineTestRun.dryIngest`, contained;
zero side effects pinned by `FlatLaneDryRunParseTest`) and reports per member a `MemberOutcome`:
the member's **kind** of end (`WOULD_LAND` / `REJECTED` / `SKIPPED` / `FAULT`) and, since 2026-09-25,
its **rejected records** — `rejects: [RejectedRecord(line, reason)]`, capped at
`PipelineTestRun.REJECTS_PER_MEMBER` (100) in file order, plus an uncapped `rejectTotal`. They are read
from the reject sidecar `<errors>/<file>_errors.csv` **before** the scratch root is deleted; the offset
is the sidecar's own `line_number` column (both CSV ingesters already wrote one — nothing was added to
the sidecar) and the reason its `reason` column, located by header name because the native ingester
has a `columns` field between them (`CsvIngester.rejectSidecar` is the one path). ⚠ The sidecar has
**two homes**: `errors/` for a member accepted while losing rows, and the quarantine tree for a member
rejected whole as a field mismatch (`QuarantineManager` moves it with the file) — the reader checks
both, and a mutant reading only `errors/` goes red. The raw line is deliberately **not** carried (reject
rows hold raw source data). Surface: `DryIngest.members()` and one `dry run: … rejected record(s), first
N of M: line L (reason); …` log line per member — the trigger's HTTP response (`202 + {runId,…}`) does
not carry it, so `docs/api/openapi-v1.json` is unchanged. Plugin decoders write no sidecar, so their
members report `rejectTotal = 0`. **Decided 2026-09-25 (operator):** the replay default is
**eject-and-continue**; this was its stated precondition.

**Record-level replay (X4, first slice, 2026-09-25).** ✅ **Eject-and-continue was already the live
behaviour** for a delimited file, on both CSV engines — nothing had to change to make it the default: a
record that fails the column count goes to the sidecar (`line_number`, `columns`, `reason`, `value_fingerprint`,
`source_file`, `source_size`, `source_sha256` — never the record itself, see below), the file's
other records land, and the file commits `SUCCESS` with `error_rows > 0` (pinned by
`RecordReplayTest.ejectAndContinueIsTheLiveBehaviour…`, `java` + `duckdb`). Two edges are not "eject":
a file with **zero** valid records is quarantined whole as `QUARANTINED_MISMATCH` (its sidecar moves with
it), and a failed type **coercion** nulls the value and KEEPS the row (counted in `cast_failures`, no
sidecar row). What was missing was only the replay, now `POST /runs/{name}/replay-rejects {file}`
(`canOperateRuns`) → `CollectorService.replayRejects` → `RecordReplay.replay`. Its choices:

- **Input = each rejected record RE-READ from the original source file** (⛔ *never store values*, operator
  decision 2026-10-03 — `INGEST-REJECT-SIDECAR-RAW-PAN-1`). The sidecar keeps only the record's **physical**
  start line (1-based, header included; both engines agree — the Java ingester reads through a
  `LineNumberReader`, DuckDB's `reject_errors.line` already is one), its columns, reason and a salted
  `value_fingerprint`, plus the source's name, size and sha256 (`CsvIngester.SourceId`). Replay looks for the
  source in the inbox, `backup/` and the quarantine tree (never `.restricted`), accepts it only when size +
  sha256 match, reads the records at those lines with the ingest's own decompression and quoted-field
  continuation (`CsvIngester.recordsAt`), and checks each against its fingerprint. A **gone** source file **→ 410**,
  source **changed** (size/sha or a fingerprint differs) **→ 409**, both before anything is claimed
  (`RecordReplay.SourceUnavailable`). The records are then written as a UTF-8 file of bare data lines
  into the poll root as `<stem>__replay_<sha8>.<ext>` and parsed with `PipelineConfig.forRecordReplay()`,
  which switches off every line-framing knob that described the ORIGINAL file (`skip_header_lines`,
  `has_header`, `skip_junk_lines`, `skip_tail_lines`, encoding, compression) and keeps everything that
  decides what a record means. Safe because fields map by selector index, never by header name.
  Sidecars written before 2026-10-03 (with `raw_line`) are refused 422: no source columns.
- **Lane = the ordinary flat ingest over exactly that one file** — `CollectorProcessor.ingestCandidates`,
  the post-discovery half of `ingest` — so the replay is a real Consignment with the whole commit tail
  (outputs, manifest, backup, marker, audit, provenance, terminal event). Discovery is bypassed, so the name
  need not match `file_pattern`, and the schema is selected by the **original** file's name. It runs on the
  trigger pool under the pipeline's run claim, like a manual trigger, so no poll of it overlaps.
- **Attribution = a durable replay record** `<status_dir>/replays/<sidecar-sha256>.json`: `originalFile`,
  `sidecar`, `replayFile`, `batchId`, `status`, `outputRows`, `errorRows`, and `lines` — record *k* of the
  replay input is the original file's line `lines[k-1]`. The replayed rows' own lineage names the replay
  file; no per-row column was added.
- **Idempotence = that record, claimed by an atomic create before anything is written**, keyed on the
  sidecar's content hash: the same sidecar replayed again is **409** and lands nothing. A replay whose
  Consignment did not complete (`FAILED` or thrown) deletes its input and **releases** the claim so it can be
  retried; any other end keeps it. Records that are **still** rejected land in the replay input's OWN sidecar
  (`<stem>__replay_<sha8>_errors.csv` — in `errors/`, or in quarantine when none landed), which is a
  different sidecar and replayable in turn. **Abandoned claims (2026-09-25):** a completed record is
  never stale, but an **empty** claim (a replay that never wrote its record) is refused as *in progress* while
  younger than `RecordReplay.ABANDONED_CLAIM_AFTER` (30 min) and, once older, treated as a crash's leftover —
  its leftover input (and `.writing` temp) is deleted from the poll root and the claim re-taken atomically
  (pinned by three `RecordReplayTest` cases). **The post-commit crash window (closed 2026-09-27):** a
  crash AFTER the replay Consignment committed but BEFORE its record was moved into place also leaves an
  empty claim. The replay's identity is fixed before the commit — its input name `<stem>__replay_<sha8>`
  is derived from the sidecar hash — so the reclaim first scans `dirs.status_dir`'s manifests
  (`RecordReplay.committedManifest`) for a live, non-`PARKED` member of that name; found ⇒ it deletes any
  leftover input, **completes the record** from that manifest (`batchId`, `status`,
  `completedFromManifest: true` — row counts are not in a manifest, so they read 0) and replays nothing.
  Pinned by `aCrashAfterTheCommitButBeforeTheRecordIsCompletedNotReplayed`. ⚠ Mutating the check away
  turns it red as `FAILED`, not as a double landing: under `duplicate_check` the second ingest of the
  same replay name is refused by its marker — so without the check the claim was never completable, and
  without `duplicate_check` it would have landed twice. A crash BEFORE the manifest write (inside the
  commit tail itself) is not covered here. ⚠ A whole-Consignment `reprocess` of the original rewrites the
  same sidecar with the same content, so its replay stays refused — correctly, since the earlier replay's
  rows were not superseded.
- **Refused (422):** a non-delimited frontend or plugin decoder, a sidecar row with no source columns, and a
  sidecar whose row count reaches `rejects_limit` (it may be truncated).

✅ **`processing.reject_mode: all_or_nothing` (built 2026-09-28, operator ask).** Absent or `eject` is the
behaviour above, byte for byte (the default path gains one boolean test per member, no extra pass).
`all_or_nothing`: a file with **any** rejected record lands **nothing** — it is quarantined whole under
`<quarantine>/…/rejects_all_or_nothing/` with its reject sidecar moved beside it, and its audit row reads
`QUARANTINED_MISMATCH` (the member vocabulary is unchanged) with the reason
`rejects_all_or_nothing: N record(s) rejected, nothing landed (reject_mode all_or_nothing)` and `error_rows = N`.
A "reject" here is only a record the parser sends to the `_errors.csv` sidecar (a parse or field-count
mismatch); type-cast failures (NULLed and counted) and later-stage checks (Expectations, Decision Rules,
dedup, steps) do not count.
**The unit is the FILE** (the Consignment member), like every other per-member quarantine: its clean
batch-mates still land, and a Consignment whose only member was refused ends `EMPTY`. Per ingest path —
the decision is always taken after the reject count is final and **before any of the file's rows are
written**, so there is no staging/rename step and a crash before the decision leaves no output at all:

| Path | Where the reject count is known | Mechanism |
|---|---|---|
| Java parse loop (`CsvIngestStrategy`) | after `CsvIngester.ingest` into the per-file `raw_f<id>` temp table | drop the temp table before it joins `raw_input` |
| Native single member (`NativeCsvStreamingEngine.streamingIngest`) | `streamUnit`: after `read_csv → transformed` (a table in the SCRATCH DB), before `writeAndTrace` | drop `transformed`, write nothing; `finishSingle` quarantines. 🔴 The BACKLOG's "streams to output before it knows its reject count" was wrong for this path: `DataTransformer.materialize` is a CTAS in the scratch DB, and `drainRejects` already ran before the write |
| Native multi-member UNION (`unionStreamingIngest`) | the per-member `COUNT(*)` probe | the member's view never joins the union |
| Native CHUNKED (`chunkedIngest`) | only after the LAST chunk — and each chunk reveals its output as it goes | **not used**: under `all_or_nothing` a chunking-sized file streams as one unit through the single-member path (the fan-out trade: it materialises in scratch) |
| Plugin lane (`StreamingPluginIngestStrategy`) | union mode: after the member's `sink.finish()`; generation mode: only after output was revealed per flush | union mode drops the member's raw tables; **generation mode is not used** under `all_or_nothing` (wins over the forced test seam too) |

Gates: `ConfigSpecs.pipeline()` declares `processing.reject_mode` (ENUM `eject`/`all_or_nothing`, no spec
default — absent means eject), `ConfigSafetyValidator` refuses any other value (**422** on `/config/write`),
and the parser refuses it at load naming the key (`PipelineConfig.RejectMode.parse`). The SPA config pane is
spec-driven, so the field appears there with no UI change; there is no editor node attribute for it.
**X4 replay:** a sidecar found in a `rejects_all_or_nothing` quarantine directory is refused (**422**) — none
of the file's good records landed, so replaying only the rejects would land the wrong half; the recovery is
the whole file (fix, move it back to the inbox). A replay input run under `all_or_nothing` is itself all or
nothing. Pinned by `RejectAllOrNothingTest` (Java single + multi, native single / union / chunked: clean
lands, one bad record lands nothing + quarantine + sidecar, eject unchanged, a failed quarantine — the stand-in
for a crash between the count and the publish — leaves nothing visible and the file in the inbox, replay
refused, unknown mode refused) and `StreamingPluginIngestStrategyTest.allOrNothing*`. ⚠ Residuals: under
`all_or_nothing` a huge single file (or a huge plugin input) materialises whole in scratch instead of
chunking/generating; a crash AFTER the decision, inside the ordinary write, is the same window `eject` has;
the chunked `eject` path names its sidecar per CHUNK (`<stem>_chunk_NNNNN_errors.csv`), so X4 replay by the
original file name does not find it (pre-existing, not changed). The nearest older knob,
`csv_settings.ignore_errors: false` (native engine only), still FAILS the batch on the first bad row — a
retry-then-`retry_exhausted` end, not a whole-file quarantine. **Audit:** the replay is recorded by the
single `AuditTrail` seam every mutating route goes through (actor, capability `canOperateRuns`, IP), under
its own action `pipeline.rejects_replayed` since 2026-09-25 — before that it fell to the POST default and
read as `pipeline.created`; `reprocess` was already `pipeline.reprocessed` the same way. **SPA:** the
rejected-rows dialog on Run Detail carries the action since 2026-09-25 — see
[run-detail](user-guide.md). **Dry-run rejects on HTTP (2026-09-27):** the dry branch
of `ConsignmentIngestor.process` also files each member with `rejectTotal > 0` into `DryRunRejects`
(keyed by pipeline name, like `IngestProgress`; the run claim means one run per slot), and
`CollectorService.runPipeline` takes the slot while it still holds the claim. The v1 async poll
`GET /runs/runs/{runId}` then carries `rejects: [{consignment, file, rejectTotal, rejects: [{line, reason}]}]`
on a finished dry run — `[]` when nothing was rejected, absent on a real or failed run. Schema:
`PipelineRun` / `DryRunMemberRejects` in `openapi-v1.json`; pinned by
`ControlApiAsyncV1Test.pipelineDryRunPollCarriesTheRejectedRecords`. The legacy synchronous `RunResult`
body does not carry them, and the SPA has no pipeline-trigger dry-run panel to show them in (its dry-run
panel is the authored-pipeline `…/dry-run`, a different route) — so no UI change.

### Identity and status, per lane

(Supersedes the two-lane table that lived in
[consignment-status-flow](architecture.md), which still owns the mechanisms —
gauges, ledgers, quarantine, audit drill-down.)

| | Ingest lanes (flat + graph fork) | At-rest job lane | Scratch lane | Parked |
|---|---|---|---|---|
| Identity | `batchId` = `TS_slug_seq`; `ConsignmentManifest` authoritative per file | `runId`; `batchId` on provenance rows | none durable — the scratch root is deleted | the parked `batchId` (the drain resumes it) |
| Terminal status | `SUCCESS`/`FAILED`/`EMPTY` + per-file `QUARANTINED_*` → three CSV ledgers + `CommitLog` (`ConsignmentAuditWriter`) | `JobRun` → `DbJobRunStore` | the `PipelineRunResult` response | `PARKED` (neither committed nor failed) until drained |
| Per-step counts | `ConsignmentIngestor.recordProvenance` → `parse`/`sink` rows (SUCCESS only) | `PipelineExecutor` records one row per node, flushed with the commit | `relations[]` in the response (seeded sample) | branch commits in `BranchCommitLog` (kept as the drain's resume record) |
| Step Receipts (X5) | flat: three phases (`parse`, `transform`, `sink:<n>` per destination); graph fork: the two phases then one per walked Step — every status, flushed at the status-final point (L3) | one per walked Step (seeds and sinks included) → `consignment_steps`, flushed in a `finally`, success or failure | none | the parked sink `PARKED` with a `park` pointer; then one `drain` receipt per parked sink (L3) |
| Live position | `IngestProgress` (which FILE) + `StepProgress` (which STEP) | `StepProgress` (which node of the walk) | n/a (bounded, synchronous) | n/a (durably at rest) |
| Terminal event | `ConsignmentEvent` on the sync `ConsignmentEventBus` **and** the `pipeline.batch.committed\|failed` Signal | `ConsignmentEvent` published by `PipelineJobRunner` on SUCCESS; on failure the `pipeline.batch.failed` Signal alone (X5 D9 = c) | none | `pipeline.batch.parked` Signal; the drain's commit emits the ordinary terminal event |

Both provenance-writing lanes share **one** `DbProvenanceStore` per space (`ProvenanceStores` —
DuckDB is single-writer). Default-off (`-Dprovenance.backend=duckdb`).

**Step Receipts on the at-rest job lane (X5 L1, 2026-11-17).** `PipelineJobRunner` walks the graph with a
`StepReceiptRecorder` — a `PipelineExecutor.WalkObserver` that forwards every count to the provenance collector it
wraps, so the provenance rows and the conservation alert are unchanged — and writes the Consignment's receipts to
the default-ON registry (`consignment_steps`, [db-layer §3.9-a](../engine/spec-db-layer.md)) **in the run's `finally`**, once,
success or failure. That is the point: a failed run's provenance rows are lost (they are written after the walk
returns), its receipts are not. Lane `job`, `runId` = the job run, `seq` = the node's topological index.

- **What a receipt holds:** rows in (a seed's own count; a sink's consumed count; else the sum over its live inbound
  edges), rows per outlet named canonically (`PipelineRel.canonical`: `dropped` → `reject:dropped`, so either
  spelling of the reject relations reads the same), a 12-hex fingerprint of the main relation's columns, and for an
  on-disk sink an `outputs` pointer (the store, and how many files this run wrote to it). A dry run's receipts are
  `simulated` and point at nothing.
- **Failure:** the Step that threw is `FAILED` with a code (`STEP_FAILED` / `STEP_TIMEOUT` / `STEP_DISABLED` from a
  contributed Step's `StepFailure`, else `SQL_ERROR` / `IO_ERROR` from the cause, else `STEP_FAILED`; the cause chain
  is walked by identity, at most 32 deep, so a cyclic chain cannot hang the walk) and the message; every Step the
  walk would still have run is `NOT_REACHED`. A throw while a node is being worked on before it starts (a seed's or
  a sink's row count) is that node's failure. A sink is timed and judged at its branch commit, and is `OK` only once
  the coordinator has recorded its branch durable: a failed write or a failed branch record is the sink's `FAILED`.
  A branch a replay finds already durable is `OK`. A throw between Steps (the "no sink branches" refusal, the source
  finalisation) is no Step's: every receipt stays as it was and the run's own record carries the failure. A code a
  contributed Step invents is mapped onto the closed set (`STEP_FAILED`, the raw code at the head of the message).
  The run still fails exactly as before.
- **Without a receipt observer** (`WalkObserver.NONE`, a plain `ProvenanceCollector`: every pre-X5 caller, and the
  `ConsignmentGraphRunner.run` overloads that take none) the walk's Step bookkeeping is inert — no schema probe, no
  diagnosis, nothing on failure.
- **Conservation:** after a completed walk, each `ConservationCheck` imbalance is written onto that Step's `diag`
  (`CONSERVATION_LOSS` / `CONSERVATION_AMPLIFICATION`, with `in` / `out` counts) whether or not the provenance store
  is on; the `PIPELINE_CONSERVATION_IMBALANCE` event is still raised only with the provenance store, as before.
- **A failed run raises `pipeline.batch.failed` ALONE (X5 D9 = c, L3, 2026-11-17).** Never a `ConsignmentEvent`:
  `JobService.mirrorPipelineCommit` turns every such event into a `pipeline.commit` Signal, so `on_signal:
  pipeline.commit` Jobs see no change. `PipelineConsignmentSignal.emitJobFailed`: WARN, subject the Pipeline (id),
  source the Job, correlation the Consignment; payload `dryRun`, `status: FAILED`, `durationMs`, `error` (quoted
  values fingerprinted, at most 240 chars) and — when a Step owns the failure — `failedStep` / `code` from its FAILED
  receipt (none for a refusal before the walk — a missing pipeline, a jail error — which raises it too; subject then
  the authored `pipeline:`, else the Job). **Exactly one per Run that ENDS FAILED** (L3 review): each attempt hands
  the Signal it would raise to its `RunContext` (a retry replaces or clears it) and `JobService` raises it after
  recording the Run, only when the recorded status is `FAILED` — so a CANCELLED Run (a cancel, a switched-off module,
  a closing Space, `interruptRuns`) and an attempt a retry recovered raise nothing; a Run whose every attempt failed
  raises one, with the final attempt's facts. A Job run directly (the CLI's standalone context) raises none.
  OpsMonitor's `batch_rerun` ignores it (source `job:`): a job run recovers by its own `retries`. Pinned by
  `PipelineJobBatchFailedSignalTest` (failed, recovered retry, every attempt failed, cancelled, refused) and
  `OpsMonitorTest.ignoresAFailedJobRunsBatchFailedSignal`.
- **Never store values (L3 review):** `StepReceipt.Diagnostics` scrubs its message in its constructor — the one door
  every diagnosis enters by, on every lane — with `FailureText.scrub`, the scrub the failure Signals use: a quoted
  literal or echoed line becomes `<redacted>` (no Space salt is known there), while a text the ingest lane already
  scrubbed under its Space keeps its `<fp:…>` tokens (`FailureText.scrub` is idempotent on its own tokens, so a
  read-back changes nothing). Pinned on the three paths: `RouteIngestEndToEndTest` (graph fork, drain) and
  `PipelineJobRunnerTest.aFailedStepsQuotedValueNeverReachesTheReceiptTable`, each reading the raw column.

**Step Receipts on the ingest lanes, park and drain (X5 L3, 2026-11-17).** The flat lane runs no Steps, so it is
told in PHASES; the graph fork tells the same phases and then every Step it walks. Collected while the pass runs in
an `IngestTrail` carried in the CALL CONTEXT (`IngestTrail.CURRENT`, a `ScopedValue` that
`ConsignmentIngestor.process` binds around exactly one pass — never a map keyed by the Consignment id, which two
Pipelines, Spaces or attempts can mint alike; a workbench test run binds a throwaway, and the binding ends with the
pass whatever it throws) and flushed once at the status-final point — **for every status** (SUCCESS,
EMPTY, FAILED, PARKED) — best effort, never changing the outcome.

- **Phases:** `parse` (`in` = rows read + sidecar rejects; `data` = rows read, `reject:unmatched` = the parser's
  sidecar rows and a `rejects` pointer counting the members that have one; `castFailures` in `diag.counts`;
  `LANE_FLAT` with the reason when the write stayed flat — the kill switch by name, else `flatReason`),
  `transform` (`in` = rows meeting the Decision Rules; outlets `data` = what went on, plus one per rule that moved
  rows — **`route:<rule>`** for a route, `reject:quarantined` / `reject:dropped` for a quarantine / drop, one count
  per matched row, from `DecisionRuleApplier.Result.moved`), then on the flat lane one **`sink:<n>`** per `sinks[]`
  entry (`n` = the 0-based index, the lift's `__d<n>`; `in` = rows written there, an `outputs` pointer with its
  file count). An `EMPTY` pass has `parse` alone. Kind `phase`; `ms` from the phase marks.
- **Graph fork:** `ConsignmentGraphRunner.run(…, observer)` walks with a `StepReceiptRecorder` (lane
  `ingest-graph`); its receipts reach the trail in a `finally`, so a throwing walk keeps them. Each sink gets an
  `outputs` pointer (files that branch wrote). A Consignment written in several calls (chunks, segments) merges a
  Step met again into one receipt (rows summed, worst outcome kept). The phases are told on `ingest-graph` when every
  write took the fork, else on `ingest-flat`; each lane numbers its own `seq`.
- **Identity:** `runId` = the ingest Run — the `run_id` its `consignment_outputs` rows carry — not `''` (a plan §2.5
  deviation, below). A dry run's receipts are `simulated`, point at nothing, and are the Consignment's (the suffixed
  dry pass is re-keyed); a workbench test run leaves none.
- **Failure (plan §3.4):** a pass that failed names the Step it failed at — a walked Step that threw (its own
  diagnosis: `SQL_ERROR` / `IO_ERROR` / `STEP_FAILED`…), else the furthest phase it reached (`STEP_FAILED`, the
  scrubbed error text), or on the flat write tail the destination being written — FAILED, everything after it
  NOT_REACHED. A failure no Step owns (the commit tail after a pass that succeeded, a park, a lease loss, a lane
  refusal between phases) marks nothing FAILED. ⚠ On the native and plugin readers parse and transform are one
  statement, so a failure before the write tail reads `parse`; the plugin lane's generation mode writes outside the
  fork, so its sink counts come from the outcome and a failure there also reads `parse`.
- **`pipeline.batch.failed` carries the same `failedStep` / `code`** (`PipelineConsignmentSignal.attributing` binds
  them around the audit flush; the `ConsignmentEvent` record itself is unchanged). Absent when no Step owns it.
- **Park:** the parked sink is `PARKED`, `in` = the rows on its live inbound edge, pointer `park` with `path` =
  `parked/<consignment>__<step>.parquet` under `dirs.backup`; the live branches commit as OK.
- **Drain** (`DrainCommand`, the body of `POST /runs/{name}/drain`): one lane-`drain` receipt per parked sink under
  the drain's own Run (also passed to the commit tail, so its registry rows carry the same `run_id`): OK with the
  rows and files it wrote, FAILED for the write that threw, NOT_REACHED after it; a sink already durable from an
  interrupted drain is OK with nothing written. Recorded in a `finally`. The park receipt stays: receipts are never
  rewritten.
- Pinned by `FlatVsGraphLaneParityTest.bothLanesLeaveTheSameSinkRowsOnTheirStepReceiptTrails`,
  `RouteIngestEndToEndTest.aStepThatThrowsOnTheGraphForkIsNamedOnItsReceiptAndOnTheFailedSignal` /
  `aParkedConsignmentsTrailShowsTheParkThenTheDrain`, `DecisionRuleWiringTest.theTransformReceiptCountsWhatEachRuleMovedAndWhatWentOn`;
  the trail's isolation by `FlatVsGraphLaneParityTest.twoConcurrentSameIdConsignmentsInDifferentPipelinesKeepSeparateTrails`
  and `aWorkbenchTestRunNeverTouchesTheTrailOfThePassAroundIt`.
- **Deviation from plan §2.5 item 6 / §3.2:** ingest receipts carry the ingest Run id, not `''`. The plan's premise
  ("ingest-lane receipts have no run id") was wrong — `ConsignmentIngestor.process` has one — and with `''` a retried
  Consignment (same members, same id) would have its second attempt dropped by `ON CONFLICT DO NOTHING`, so the
  trail would show the first failure and never the success.
- ⚠ **Not built:** nothing serves the receipts over HTTP yet (L4, `GET /consignments/{consignmentId}`); a drain
  failure raises no Signal (unchanged — the route answers the error).

**Across the lanes (X2, 2026-09-02):** the at-rest lane records which ingest-lane Consignments it READ
— `inspecto_job_run_sources` beside the run row, fed from the files the Consignment selector kept and
mapped through the default-ON `consignment_outputs` registry, never from the rows (ordinary output files
carry no per-row batch id). `GET /jobs/runs/{runId}` → `derivedFrom[]`; `GET /runs/{name}/outputs` →
`derivedRuns[]`. Absent when the registry or run store is off — unknown is not empty. Schema and the
three decisions behind it: [db-layer §3.5](../engine/spec-db-layer.md).

### The Stage-2 chain is a job, not a sixth lane

A flat `*_pipeline.toon` carrying `steps:` + `output_store:` arms on the *declared promise* that a
`pipeline_config:` job exists to run the chain over the landed store — `output_store:` does not create
the job. 🔴 Author the chain, skip the job, and the pipeline ingests forever while the transform never
runs. Two closures (2026-09-01): `pipeline_config` is a declared parameter of the `pipeline` Job Type,
and `SchedulerAuditTask` reports the orphan. 🔴 **The following sentence was HISTORY stated as current and is corrected 2026-09-09** — since 2026-09-01 the check is **default-on in every space** (`JobService.auditOrphanOutputStores`, kill switch `-Djobs.orphan.audit=false`), exactly as *Recovery affordances per lane* above says. It *used* to fire only from a `scheduler_audit` maintenance job
(`spaces/demo/config/jobs/scheduler_audit_job.toon` ships one; a space without it has no orphan
detection). Full detail: [stage1-architecture §Step 3](architecture.md).

### Naming

The Batch→Consignment rename shipped as one commit (`ff33246a`): `ConsignmentIngestor` (🔴 **not**
`ConsignmentProcessor` — that name is the third-party post-sync SPI), `ConsignmentIngestStrategy`,
`ConsignmentGraphRunner`, `ConsignmentEvent`/`ConsignmentEventBus`, `ConsignmentManifest`,
`ConsignmentAuditWriter`. ⚠ Wire and persisted spellings are deliberately unchanged (BACKLOG §4):
the `batch_id` ledger columns keep their read-alias, `GET /runs/{n}/batches` keeps its path, the
`__batch_id` reference column and the `batch_id` job key became `__consignment_id` / `consignment_id` in 4.0.0
(D-12, legacy reads kept), and the
`DbProvenanceStore`/`DbStatusStore` DDL columns await a data migration.

## Lane-fork admission notes

The lane fork itself — what admits a write to the graph lane, the `-Dingest.lane=auto|graph|flat` flag, the
decision-rule and scratch-dir rules, the engagement predicate and the parity test — is owned by
[spec-ingest.md](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path). This section keeps the execution-side notes the PIP spec carried.

✅ **The ELT amendment §6 step-2 parity gate is MET (2026-09-16, `GRAPH-LANE-RULE-ROUTED-1`, `b6b90fa2c`):** the whole suite
under `-Dingest.lane=graph` passed with zero "cannot carry pipeline" refusals, once rule-routed writes rode the
graph lane (`DecisionRuleApplier` runs above the lane fork, so both lanes see the same remainder). The root pom
carries `<ingest.lane>auto</ingest.lane>` and hands it to surefire, so `mvn -Dingest.lane=graph test` runs the
WHOLE suite with the flat lane disabled — the gate as D-2 wrote it; re-run it after each lane change rather
than reading a projection round-trip test as parity. (The first run that day was NOT met: 13 `inspecto-engine`
tests refused, on the multi-schema sink count and on rule-routed rows; both gaps shipped the same day.) The
rule-routed gap looked representational (`PipelineLift` never sees a Decision Rule), but no graph node was
needed: the routed outputs only had to be merged into the graph lane's written list. Routing combined with
route branches stays refused on both lanes. (Routing with a multi-destination `sinks[]` is carried since
2026-10-06 — see [Part III](#part-iii--sinks) below.)

### A multi-schema write is admitted PER SEGMENT (2026-09-16)

A `segments:` batch reaches the write path **once per segment** (`UnionModeIngester`): each call materialises
that segment's `transformed_<KEY>` table and writes under `database/<segKey>`. So the lane fork asks its
admission question about the sub-chain that call actually writes — `map_<segKey> → sink_<segKey>` — and the
write stays **one seed feeding one sink**, exactly as for a single-schema batch. `-Dingest.lane=auto` diverts
these writes to the graph lane by default (the operator's call: real production exposure over a path only the
gate exercises).

🔴 **Two readings of this gap were wrong before the code was written, and both are worth keeping.** (1) The
refusal *states* an arity — *sink count (3) differs from sinks[] (1)* — which reads as a count to relax; it is
not. (2) The next reading, that the seeding contract is structurally violated because a per-schema lift gives
each branch its own map, implied a multi-seed walk. Also wrong: the caller had **already** decomposed the batch
per segment, so no multi-seed is needed. What was actually broken is narrower than either: **the admission
asked at pipeline granularity while the caller works at segment granularity.** The lesson generalises — when a
refusal message names a quantity, check which caller's granularity that quantity was measured at.

⚠ `writeScope` is overloaded across the four callers — `""` whole-batch, the chunk base name when chunked,
the segment key only in the per-segment loop — so a scope counts as a segment key only when the config
declares a segment by that name. A `schemas[]` selector batch's schema therefore travels in its OWN argument
(`selectedTable`, 2026-09-24) rather than in the scope.

⚠ **`segments:` is parsed only when `processing.ingester:` is also set.** Without it a multi-schema config
lifts as SINGLE-schema (a linear `acq → parse → map → sink`) and quietly proves nothing — it cost the first
spike fixture of this work.

⚠ The executor was never the obstacle: `PipelineExecutor.execute` has always taken a **map** of seeds
(multi-source, T32 Phase C) and walks per-schema trees when given them. `ConsignmentGraphRunner.engages` /
`dataFedSinkCount` still report *false* / *1* for such a graph — they gate **route** pipelines and treat
per-schema dispatch as trunk, which is deliberate and not in conflict with the above.

The reason-for-flat enumeration is the most operator-useful thing on this path: an authored route that does
not engage, a decision rule that routed rows, no scratch directory, a destination-count mismatch, a
non-projection node between projection and write, or a sink fed through another node.

## Run identity, ledgers and the async contract

**Run ⊇ Consignment ⊇ File.** Identity is the run-and-consignment pair, because the Run is the *attempt* —
so a reprocess is a new Run over the same Consignment. Intrinsic and inherited file status are deliberately
separate enums — see [architecture.md](architecture.md).

**The commit log is one append-only synced file** and its header carries no run timestamp: it answers *did
this Consignment finish*, and it is distinct from the per-run audit. There is no day column.

**The async contract is the same shape on both engines**: a trigger returns 202 with a run id and a
location header, and the caller polls. An idempotency key is cached on method, path and key for ten minutes
with a thousand-entry cap, only for non-server-error statuses, and a replay is flagged in the response
headers.

⚠ **The at-rest lane's always-on terminal record is the run ledger CSV, not the database projection.** The
lane table above names `DbJobRunStore`, which is **default-off**.

## Caps and the bounded retry

**The resource-cap pair ships on.** The Run bound is on by default in code (four — see
[spec-ingest.md](spec-ingest.md#part-b--concurrency-the-four-layer-hierarchy)); the memory limit has a **code default** since 2026-09-26
(GAP-4: 40 % of JVM-visible RAM ÷ a fixed 4, floored at 1 GiB — `DuckDbUtil.defaultMemoryLimit`; the
measurement behind it is in [../engine/spec-duckdb.md](../engine/spec-duckdb.md)). Precedence for the limit is per-pipeline, then
server-installed, then a bootstrap flag, then that code default (`DuckDbUtil.memoryLimit` never returns null;
configure `80%` to get the database's own behaviour back). Preview and dry-run connections are **deliberately
exempt** — they run over bounded samples. Total exposure is the limit times the concurrent Run count, which is exactly
why an unbounded Run count would make any per-instance cap meaningless. The two standing refusals (a cap of
memory ÷ live concurrency; any temporary-directory-size default) are in [plan.md](plan.md) §6.

**The commit retry is bounded** (default five attempts, `CommitRetry`; the routes, the per-pipeline
`processing.retry` block and the record-level replay are under *Execution lanes* above). What it fixed:
**the retry already existed** — the next poll cycle re-encountered the files — and it was **unbounded**, so
a poison Consignment failed every cycle forever. 🔴 **There is still no transient-versus-fatal classifier** —
treating a database lock as the trigger *"was aspirational"*; failure is recorded on any commit or park
failure regardless of cause. A zero setting (`-Dingest.retry.max=0`, or `processing.retry.max_attempts: 0`)
restores the pre-bound, unbounded behaviour.

⚠ **The retry does not cover a crash mid-archive**, which relies on idempotent overwrite instead: the
sidecar records a *returned* failure, and a crash returns nothing.

## The at-rest job lane (live execution)

An authored Pipeline is **job-style**: it reads data at rest (`source_store`), runs `transform.*` nodes, and
writes to a sink store. It is hosted as a [`JobType.PIPELINE`](../processor/spec-jobs.md) job on the existing
`JobService` scheduler (cron / `on_pipeline` / manual). This is the **at-rest job lane** — for how it sits
beside the other four lanes see *Execution lanes* above. Design of record (T32 plan,
shipped 2026-06-18/19):
[`flow-live-execution-plan.md`](../../../../archived-documents/plans-archive/flow-live-execution-plan.md).

### `PipelineJobRunner.run()`

`PipelineJobRunner` (`platform/inspecto-engine/src/main/java/com/gamma/job/PipelineJobRunner.java`):

1. Load the `PipelineGraph` from `PipelineStore`.
2. `seedsOf(g)` — find every node with a non-blank `source_store` cfg key; **throws if none** (zero seeds is
   a hard error). The Phase-A MVP allowed exactly one seed; Phase C relaxed it to **≥1** (a downstream
   `transform.merge` joins/unions multiple sources).
3. For each seed, `SourceStoreReader.registerView(…)` registers a DuckDB view over the at-rest Parquet/CSV.
4. `PipelineExecutor.execute(…)` walks the graph (see [design](architecture.md)).
5. `PartitionSinkWriter` (the `SinkWriter` impl) delegates each sink write to
   [`PartitionWriter`](#writers-store-layout-and-destinations).
6. Optionally collect provenance (`DbProvenanceStore`, one row per node — shared with the ingest lane
   through `ProvenanceStores` since 2026-08-12) and run `ConservationCheck` → emit
   `PIPELINE_CONSERVATION_IMBALANCE` events when records are lost at a non-amplifying node. The
   executor also reports its live position per node (`StepProgress`, cleared in a `finally`) — see
   [consignment status flow](architecture.md).
7. Advance `PipelineWatermarkStore` per source (opt-in incremental mode); register `sink.view` outputs as durable
   `ViewDefinition`s.
8. Publish a `ConsignmentEvent`, return a `JobResult`.

Config (`*_job.toon`): `type: pipeline`, plus **exactly one of the two graph sources** (mutually
exclusive; `PipelineJobRunner` enforces "pick one" at run time — ⛔ neither is `required` in the
descriptor, see [stage1-architecture §Step 3](architecture.md)):

- `pipeline: <authored-pipeline-id>` — an authored graph from `PipelineStore` (canonical since the
  Tier 3 rename, vocabulary plan §4 — the pre-rename `flow:` key still reads, dual-read only, never
  written again);
- `pipeline_config: <path/to/*_pipeline.toon>` — a **flat file's Stage-2 `steps:` chain**, lifted at
  run time by `PipelineLift.stageTwo` into `source_store(landed) → chain → sink.persistent(output_store)`
  (the flat file stays the truth). A `transform.join` here resolves its Reference Dataset through the
  shared `ReferenceReader`, identically to Stage-2 enrichment.


### Config-less ad-hoc run (2026-07-18)

`POST /pipelines/authored/{id}/trigger` fires an authored Pipeline **once, with no `*_job.toon`**:
`JobService.triggerFlowRun(flowId, actor)` builds a synthetic, **never-registered** `type: pipeline` config
and runs it through the exact registered-job lifecycle — deletion-fence `runningFlows()` tracking, per-Pipeline-id
non-overlap (a re-fire while running records `SKIPPED`), the durable run ledger, and `GET /jobs/runs/{runId}`
polling — so `GET /jobs` stays config-only while `GET /jobs/{flowId}/runs` serves the ad-hoc history (runs are
recorded under the Pipeline id — `flowId` in code; the chain `ConsignmentEvent` is likewise published under it, so downstream
`on_pipeline:` consumers key on it, not on a job name). Response mirrors `POST /jobs/{name}/trigger`:
`202 {runId, pipeline, status}` + `Location`. **Deliberately `…/trigger`, not `…/run`** — `…/run` is the
scratch lane's **simulate** verb, `…/trigger` the **operate**; see *Execution lanes* and *The scratch
lane* on this page.
Prefer a persisted `*_job.toon` when a run needs `data_dir`/`consignment_id` overrides (`batch_id` before 4.0.0), a schedule,
or chaining; the ad-hoc route takes no params.

## The at-rest runner, and the two lifts

A pipeline-type Job reads a config and lifts only the **second stage**, then runs that chain over the
already-landed store (graph-source and seed rules above).

⚠ **Two lifts exist and both are live. Neither is stale, and confusing them is the area's classic error:**

| Lift | Produces | Callers |
|---|---|---|
| the **full** lift | the ingest topology | the lane admission, the processor, the drain command, and the editor's round trip |
| the **stage-two** lift | the at-rest remainder only | the pipeline job runner, and nothing else |

The note that "stage-two is the wrong lift" was a **scoping correction to one plan draft**, which had
proposed computing the *ingest* fork check from the stage-two lift — impossible, because it knows nothing
of ingest topology or route branches. It was corrected to the full lift, which is what the admission uses
today. Each belongs where it is called.

The stage-two lift's refusal set is complete and named: no chain, no output store, a multi-schema selector
(one seed cannot pick a store), a route step (route's home is the ingest lane), and a legacy pre-map filter
carrying pre-parse keys.

## The scratch lane — Pipeline test run (run-to-here)

**What it is:** a bounded, scratch-only run of an authored Pipeline over the user's **real** inbox
files — the "Test" in the Build → Test → Run authoring journey. Shipped 2026-08-14
(`1f0937ee`, `141caf84`, `0b2a80ba`, `0c542829`).

`POST /pipelines/authored/{id}/run?to={nodeId}` · `canAuthorWorkbench` · returns the UI's
`PipelineRunResult`.

It is a **simulate**, which is why it is author-gated and why the path is deliberately *not*
`…/trigger` — that sibling is the operate verb (`canOperateRuns`) and fires a real run. This section owns
the **scratch lane** mechanism; the lane map is *Execution lanes* above, the operate lane *The at-rest job
lane* above.

### Why it is safe: two independent containments, both structural

A test run that mutates production state would be worse than no feature at all, so containment is by
construction rather than by a flag anyone can forget.

**1 · Call-graph containment.** `ConsignmentIngestor.process` is, in order: `strategy.ingest(...)`, then
`commit(...)`, then `writeAudit(...)`, then `recordProvenance(...)`. `PipelineTestRun` calls **only the
first**. This is the load-bearing decision, because **five destinations are not derived from the config
at all** and so could never have been redirected by one:

| Destination | Resolved by | Lives in |
|---|---|---|
| Acquisition ledger (dedup fingerprint, DB-export watermark) | `-Dacquire.ledger.backend` / `.db.url` | `finalizeSource` (inside `commit`) |
| Consignment output registry | `-Dconsignment.outputs.backend`, per-space registry | `finalizeSource` |
| File stages | `-Dfile.stages.backend`, per-space registry | `finalizeSource` |
| `pipeline.batch.*` Signal | ambient `EventLog.current()` (space MDC) | `writeAudit` |
| Provenance matrix | process-wide registry | `recordProvenance` |

⛔ **If a fourth side-effecting statement is ever added to `ConsignmentIngestor.process`, do not mirror it
into `PipelineTestRun`.** The omission *is* the safety property.

**2 · Filesystem containment.** Picked files are **copied** into `scratchRoot/poll`, and the run
executes against `PipelineConfig.forScratchRun(scratchRoot)`, which re-roots every destination.

⚠ **The copy is not an optimisation to remove.** `CsvIngestStrategy` quarantines an unreadable /
field-mismatched / empty member through `QuarantineManager.quarantine`, which does a **`Files.move` of
the source file** — from *inside* the ingest half. Redirecting `dirs.quarantine` does not help, because
the source is the problem: run against the real inbox and **testing a malformed file would delete it
from the user's inbox**. (A hardlink would also work on a single filesystem but fails across volumes —
a later optimisation behind a fallback, never the default.)

🔴 **Re-rooting the sinks means re-rooting the `route:` branches with them** (`ROUTED-WRITE-COUNTS-PER-BRANCH-1`,
fixed 2026-09-23). Branch↔sink pairing is **by the branch's declared `database`**
(`PipelineLift.branchKeyForDatabase`). While `forScratchRun` moved the sinks under the scratch root and
left the branches naming production directories, the lift paired **nothing**, fell through to a plain
data edge per destination, and the test run silently **degraded to a fan-out: every sink received every
row**. It reported `rowsWritten` = rows × branches — 9 → 18 on `route_step`, 12 → 36 on `premed_events`,
the branch-count factor that named the defect — and showed a builder the whole feed under each branch.

⚠ **The counts were the symptom; the content was the damage.** A fan-out and a correct route agree on
`outputs().size()`, so any test that asserts only the shape of the result passes against both. Assert
the per-destination **content** (`RouteIngestEndToEndTest.aTestRunOfARoutedPipelineRoutesRatherThanFanningOut`).
The general rule this is an instance of: **anything `forScratchRun` re-roots, every config key that
JOINS on that value must be re-rooted in the same step** — `route.branches[].database` is the only such
join key today, and a second one would fail the same silent way.

The ingest half touches exactly five dirs — `poll`, `database`, `errors`, `quarantine`, `temp` — plus
each `sinks[].database` on fan-out. `forScratchRun` additionally **nulls the commit-half destinations**
(`backup`, `markers`, the status/batches/lineage CSVs, manifests, commit log) even though the commit
half is never reached: defence in depth, so a future caller that *does* call it still cannot write to
production. `backup == null` is what makes the source-file backup a no-op.

Building the batches directly also bypasses `CollectorProcessor`, so the dedup/marker layer never runs
and a test run cannot mark a file as already-processed.

### The `files` jail

The body is caller-supplied, so without containment this route is an arbitrary-file-read over HTTP.

- Entries are **connection-relative** — the picker fills them from `GET /connections/{id}/explore`,
  whose `ResourceNode.path` is relativized against the profile's `base_path`.
- The jail root is **derived server-side** from the pipeline's own `source.connection` profile
  (`dirs.poll` when it binds none). The request carries no connection id, so this is enforceable by
  construction. ⛔ **Never accept a client-supplied root.**
- Containment reuses **`LocalConnectionWorkbench.jail(Path, String)`** — the *same* primitive the picker
  uses, so the two surfaces cannot disagree about what is reachable. A picker that allows X beside a
  runner that allows Y is how this class of hole appears. An escape is `PathEscape` → **403**.
- ⚠ Not to be confused with `ConfigSafetyValidator.checkPathValue`, which is **advisory** (collects
  `Finding`s at authoring time) rather than enforcing.
- Non-`local` connectors are **501** — there is no local path to stage from; those files reach the
  inbox via acquisition first.
- A **Dataset-fed** Pipeline (`collector: dataset`) is **501** too, naming the Dataset (`WB-07`,
  2026-09-22). It used to answer a `{files:[…]}` run with 200 *“no rows were parsed”* — an empty
  success where the honest answer is a refusal. Row `TESTRUN-DATASET-COLLECTOR-SILENT-1`.

### Getting a file into the inbox (INBOX-UPLOAD-1, 2026-09-25)

A Stream onboarded from the Catalog binds **no connection** and polls `data/inbox/<stream>` under the Space
root, so its run-to-here jail root is `dirs.poll` — and until this route the builder had to copy a file
there by hand (found in a builder pilot). `PipelineInboxRoutes`:

- `GET /pipelines/authored/{id}/inbox` — open read: the regular files directly in `dirs.poll`, newest
  first, by **bare name** (exactly what `files` takes for a connection-less Pipeline). Capped at 500 with
  `truncated` + the true `total`.
- `POST /pipelines/authored/{id}/inbox?file=<name>[&overwrite=true]` — `canAuthorWorkbench` (like
  run-to-here). The **raw body** is the file. Gates in order: 503 no write root · 404 · 501 Dataset-fed ·
  422 no name · **403 a name that is a path** (separator, `..`, colon — refused, never resolved; the
  resolved target is still jailed to `dirs.poll`) · 413 over 64 MiB (on the declared length, before
  buffering) · 422 empty body · 409 exists without `overwrite` · then `AtomicFiles.write` — the staging
  file is a `.tmp` sibling, which the poll's in-flight exclusion ignores.
- ⚠ It writes into the **production** inbox: an ACTIVE Pipeline ingests the file on its next poll. That is
  the point for a draft Stream's first file; it is not a scratch copy.
- ⚠ A connection-bound Pipeline's run-to-here jails against the connection's `base_path`, not
  `dirs.poll`, so the editor offers the upload only when no connection is bound.
- The UI is the Run-to-here dialog's *Inbox files* section (`run-to-here.dialog.ts`): it lists the inbox,
  uploads a picked file, asks before replacing (409 → confirm → `overwrite=true`), then refreshes the list
  and selects the upload.

### Response, and the two grains in it

`PipelineRunResult` = `{seedNode, toNode, files[], relations[], output|null, warnings[]}`.

⚠ **`relations[]` counts the seeded sample; `output.rowCount` is the full parse.** The seed is bounded
(`PipelineTestRun.SEED_ROWS` = 1000 raw rows per segment) because `PipelineDryRun` is in-memory and a picked file is unbounded. A
warning names the difference whenever the two can disagree, so neither number is quietly mistaken for
the other. Per-file quarantine outcomes also surface as warnings — the operator is told a file *would*
be quarantined even though nothing moved.

`relations[].{node, rel, rowCount}` is load-bearing: the canvas marks a node ✕ on
`rel === 'unmatched' && rowCount > 0`.

The sibling `POST …/dry-run` takes `{sampleRows:[…], pipeline?}`. A body of the wrong JSON shape (a bare
array is the natural first guess) is **400**, not 500 — fixed at the shared `ControlApi.body` seam
(`WB-06`, 2026-09-22), because the 500 was every POST route's defect, not this route's; a route's own more
specific 400 still reaches the caller. Row `DRYRUN-MALFORMED-BODY-500-1`.

### The `to=` cutoff (2026-08-14)

`PipelineExecutor.dryRun(..., stopAtNodeId)` bounds the walk; `PipelineDryRun.run` and
`PipelineGraphRoutes.testRun` thread `to` down to it. `null` means the whole graph, which is the only shape any
production caller passes.

⚠ **The bound is the ancestor closure of the target, not a prefix of `topoOrder`.** Topological order is
arbitrary between sibling branches, so truncating it runs whichever branch happens to sort first and reports
counts for nodes the operator never asked about — which the canvas then marks ✓. `ancestorsOf` walks edges
backwards instead (skipping `on_commit`, which is a cross-Pipeline trigger rather than a data dependency). *(Until 2026-09-10 this
added that the offline mock's `subgraphTo` matched, "so mock and server agree" — that mock was deleted
2026-08-31, so the server's walk is the only definition of what a run-to-here covers.)*

⚠ **`execute` was not touched.** The plan expected the cutoff to thread through a walk shared with the
production executor; in fact `execute` and `dryRun` are separate loop bodies sharing only the private
`topoOrder` helper, so the production path is untouched rather than merely defaulted.

Two boundaries worth keeping straight:
- **A cutoff bounds the preview, not the parse.** The picked files are always parsed in full, because the
  parse is what seeds the walk. `to=` narrows the answer, never the work.
- **Bounded-on-purpose is not "nothing would be written".** A cutoff above every sink leaves `sinks` empty,
  which deliberately does *not* trip DRYRUN-2's "no sink received any rows" warning — that one requires a
  non-empty `sinks` list.

An unknown `to=` throws → **400**, rather than silently widening to the whole graph.

### Two blind spots closed: enrichment and webhook (G8, 2026-09-23)

Both apply to `POST …/dry-run` and to run-to-here, because both go through `PipelineDryRun.runSeeded`.
Row `PROCESSOR-RELEASE-READINESS-1`, gap G8.

**An `enrichment` node is WARNED BY NAME, not run and not refused.** The walk runs only `transform.*`
(`PipelineExecutor.isShapeable`), `transform.merge` and sinks. An `enrichment` node mid-walk used to produce
nothing, and nothing below it ran either. Its sink disappeared from `sinks[]` and no warning fired, because
both DRYRUN-2 warnings need either no node reached at all or a sink that got zero rows. Now
`PipelineExecutor.DryRunResult.notExecuted` lists every enabled node the walk reached and has no executor for.
Control terminals are left out, since they produce no data relation by design. `PipelineDryRun` adds one
warning per such node. The warning names the node, gives the reason, and lists the nodes below it that got
nothing, for example: *node 'enr' (enrichment) was NOT previewed: … preview it with POST /enrichment/preview;
so nothing reached 'sink'*.
⚖ **Why a warning and not a run:** an enrichment is a post-commit Stage-2 job. It reads the *committed store*
(partitions, earlier batches, its own references), and neither run lane runs it as a walk step. Running
it over the sample would preview something no real run does. **Why not a refusal:** that would block the
preview of every graph with an enrichment in it, because of one node the rest of the walk doesn't need.
Any future node type the walk has no executor for gets the same named warning.

✅ **A mid-walk enrichment cannot be SAVED (2026-09-24, `ENRICHMENT-MIDWALK-LANES-DISAGREE-1`, operator
decision).** The two lanes disagreed about it: `PipelineExecutor.execute` skips the node and starves
everything below it, while `PipelineEditable.lower` drops the node and so the flat file feeds its downstream
from its upstream. `PipelineEditable.lower` now refuses any `enrichment` node with an edge to another node in
the graph: **422** `{written: false, refusals: [{code: "ENRICHMENT_NOT_TERMINAL", nodeId, message}]}`, and the
message names the nodes it feeds. The canvas shows it through the same `refusals[]` path as every other lower
refusal (`showRefusals` → the Validation dock, next to the node). ⚖ The refusal sits at the SAVE, not in `PipelineValidator`. The
candidate dry run and run-to-here still accept the shape and give the named warning above, so a draft can
still be previewed. What a real run executes always comes from a saved file, and a saved file can no longer
hold this shape. A terminal enrichment still saves. So does the derived `companion` edge, because it points
INTO the enrichment and `withoutDerivedEdges` drops it before parsing anyway. Pinned by
`ControlApiEnrichmentMidWalkRefusalTest` (3, real HTTP). `ControlApiPipelineGraphRoundTripSweepTest` stays
green, so no shipped Pipeline is refused. `ControlApiPipelineCrudTest#anEnrichmentNodesCompanionBindingSavesOverHttp`
used to author the mid-walk shape and now uses the companion shape `graph/raw` really serves. Falsified: with
the check disabled the 422 test fails with the old `200 written:true`, and refusing every enrichment turns the
terminal and companion tests red.

✅ **The companion shape now reaches the walk (2026-09-24, `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1`).**
`GET /pipelines/{name}/graph/raw` joins a `*_enrich.toon` companion to the sink with a **derived display-only**
edge `{rel: "companion", derived: true}` instead of a `data` edge (which `PipelineValidator` refuses
`ILLEGAL_EMIT` — a sink emits nothing). `parseAndValidateFlow`, shared by the
candidate dry run and `PUT …/graph`, drops that edge before parsing, so both answer 200; a hand-authored
sink `data` edge is still refused. As-built in
[editable-round-trip](#derived-companion-edge--display-only-never-saved-2026-09-24) (*Derived companion edge*).

**A `sink.webhook` branch now refuses the way the job dry run does.** For every webhook sink the walk reaches,
`PipelineDryRun` calls `WebhookSink.plan`. That is the same resolution `DryRunSinkWriter` uses: config, edition
transport, Connection, token. On a bundle with no `WebhookSinkTransport` (Personal), the dry run returns
**422** with WebhookSink's own message, which names the sink and the edition. It used to show a healthy
branch with a row count. The same call also refuses an unregistered Connection or a token that doesn't
resolve, just as the job dry run does. Nothing is ever sent.

Pinned by `ControlApiDryRunBlindSpotsTest` (2, real HTTP). The `inspecto` module does not depend on
`inspecto-notify-channels`, so its test classpath matches Personal. Falsified: making each fix a no-op turns
its own test red with the old answer (`warnings: []` and `sinks: []` for the enrichment, 200 with the webhook
branch counted for the webhook).

### A SQL failure reads as the real error first (2026-09-23)

A step whose SQL is wrong 422s with *“test run failed: Binder Error: Referenced column "EVENT_TS" not
found … Candidate bindings: "EVENT_DATE" …”* — the actionable error first, the column and the candidates
kept verbatim. `POST …/dry-run` does the same (*“dry-run failed: …”*).

⚠ **Why there was anything to strip:** DuckDB's JDBC driver (1.5.2.1) runs `Statement.execute(String)` as a
pending query without checking whether preparing it failed, so a bind failure arrives as ONE native message:
*“Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result”*, a newline,
`Error: `, then the real error. The `SQLException` carries **no cause, no suppressed, no SQLState** — there
is no "real exception" to unwrap. `Connection.prepareStatement` on the same SQL reports the Binder Error
cleanly, which is how the cause was confirmed; switching the walk to prepared statements was **declined**
because the dry-run shares its SQL execution with the production executor.

✅ **One seam:** `DuckDbUtil.withoutPendingQueryPreamble` removes exactly that driver text and nothing else.
Used by `testRun`, `dryRunFlow` and `POST /components/transform/describe` (which carried its own looser
copy until now). `DuckDbPendingQueryPreambleTest` runs the real driver, so a DuckDB upgrade that rewords
the preamble goes red instead of letting it silently back in. Row `TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1`.

**Every other 422 built from such a failure adopts the same seam (2026-09-23, `DUCKDB-PREAMBLE-OTHER-422S-1`).**
Probed on the real driver: the preamble appears on **any plain `Statement`** — `execute(String)` AND
`executeQuery(String)` — for a failure found while binding (Binder, Catalog, a table function's `IO Error:
No files found`), and **never** on a `PreparedStatement`, a Parser Error or an execution-time error (e.g.
Conversion Error). `QueryExecutor.run` registers the dataset view on a plain `Statement` and runs a query
with no binds on `SqlSandbox.statement()`, so every route over it could carry it. Adopted at: `BiRoutes`
(`/bi/query`), `QueryRoutes` (`/queries/{id}/run`), `ShareRoutes` (`/public/dashboards/{t}/query`),
`RuleRoutes` (`/rule-templates/{id}/simulate`), `DbBrowserRoutes` (`/db/query` + `/db/table`, both the
store and the `ops:` group — `BrowsableStore.exec` is a plain `Statement` too), `EnrichmentRoutes`
(`/enrichment/preview`), `ExpectationRoutes` (`/expectations/{name}/evaluate`), `ReconRoutes` (all four),
`ViewRoutes` (`/views/{name}/data`), `RouteErrors.mapPreviewErrors` (`/components/{transform,grammar}/…/preview|test`),
and in `inspecto-geo-link` `GeoRoutes` (`/geo/projection|routes`, the `SQLException` arm only) and
`InvRoutes` (projection, multi-projection, traversal). One real-HTTP test per route family asserts the
message **starts with** the real error; each went red against the unstripped message before the fix.

⚠ **Not yet adopted elsewhere:** four more routes (`BiRoutes`, `DbBrowserRoutes`, `EnrichmentRoutes`,
`ExpectationRoutes`) build a 422 from a raw DuckDB message and probably carry the same preamble —
unverified, reproduce per route before adopting the seam. Row `DUCKDB-PREAMBLE-OTHER-422S-1`.

### Defects found by the demo lanes (reported 2026-09-23)

Found by the lanes that built the domain demos (`gl_journal`, `stock_movements`, `msc_cdr`,
`in_recharges`). Each cause is the lane's hypothesis until someone reproduces it.

✅ **The seed is the PARSER's raw rows, captured before mapping** (`TESTRUN-SEED-IS-MAPPED-OUTPUT-1`, fixed
2026-09-23). **Decision (operator, 2026-09-23): seed the preview with the RAW parsed rows, so it runs the same
mapping a real ingest does.** The alternative — seed downstream of `map` and skip it — was declined: it would
leave the mapping, the step most likely to be wrong, as the one step a test run never exercises.
- 🔴 **What it was:** `PipelineTestRun.sampleRowsBySegment` read back the rows the ingest **wrote** — already
  mapped — and `testRun` passed them to `PipelineDryRun.runSeeded` as the parse node's output, so the walk
  re-applied `map` / `map_<segment>` to canonical columns. **Loud:** `in_recharges` (`AMOUNT_MINOR`) and
  `msc_cdr` (`EVENT_TIME`) refused 422 with a binder error, so the test run could not reach route or sinks.
  **Silent, and worse:** `premed_events` answered 200 with every `EVENT_TS` NULL — the written TIMESTAMP,
  stringified by JDBC and re-parsed by the `keep`'s format list, failed the format and became NULL.
- **The seam:** `DataTransformer.RAW_INPUT`, a `ScopedValue<RawInputObserver>` checked at the top of
  `DataTransformer.materialize` — the ONE point all six ingest lanes (native single / chunked / union, the
  Java parse lane, both plugin modes) funnel through. `PipelineTestRun.run` binds it around
  `strategy.ingest(...)` and samples `SELECT * FROM <raw source> LIMIT n` (up to `SEED_ROWS` = 1000 per
  segment, `__src_id` dropped) into `Result.rawRows()`. The segment is found from the schema map the
  union-mode ingester passes (identity, then value) — never from table names. Unbound, i.e. every production
  run, it is one `isBound()` check. ⚠ A scoped value, not a parameter, because a parameter would have to be
  threaded through all six lanes; ⚠ it does not cross threads, which is fine only because every lane calls
  `materialize` on the ingest thread.
- ⚠ **The sample re-scans the raw relation** (a lazy `read_csv` view on the native lanes). Production already
  scans it twice (`materialize` + `countCastFailures`), so this adds a third bounded scan in a test run only.
- ⚠ **An exception thrown by the observer is the ingest's exception** — on the single-member native lane it
  fails the batch as a transform failure (the input still reads, see below). The sampler is a plain
  `SELECT … LIMIT`, so this is theoretical today.
- Pinned by `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos, each compared against a
  REAL ingest of the same committed sample): `premed_events` — all 12 `EVENT_TS` equal to the written values,
  route voice·sms·other `5·3·4` as written; `in_recharges` — 14 `AMOUNT`s equal to the written ones, route `10·4`; `msc_cdr` —
  each `map_<segment>` row count and `EVENT_TS` equal to the written segment. ⚠ A previewed TIMESTAMP comes back
  as epoch millis of a HOST-zone `java.sql.Timestamp`; reading it as UTC is off by the host offset.

✅ **A failed batch is reported as the failure** (`TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1`, fixed 2026-09-23). A
`FAILED` `PipelineTestRun.Result` now answers **422** *“test run failed: the batch FAILED after N row(s) parsed:
&lt;the batch's error&gt;”* before any preview runs, and `RunToHereDialog` renders it in its error alert (it already
surfaced any error body through `apiErrorMessage`; `run-to-here.dialog.spec.ts` pins the rendered alert). 🔴 With
the raw seed alone the hole got WORSE, not better: a failure outside the mapping — a `partitionKey` naming an
absent column fails the real transform, while the preview's map projects mapped columns only — answered 200
with a clean preview and no warning. Pinned by
`ControlApiPipelineTestRunTest.aBatchThatFailsAfterParsingIsReportedAsTheFailureNotAsEmpty`, which uses TWO files
(written when only the multi-member lane failed the BATCH on a transform error; one file now does too).

✅ **A transform failure fails the batch on the single-member lane too; it never quarantines the input**
(`SINGLE-MEMBER-TRANSFORM-FAILURE-QUARANTINES-1`, fixed 2026-09-23). `streamingIngest` held `streamUnit`'s
`materialize` inside the catch that quarantines `QUARANTINED_UNREADABLE`, so a bad `partitionKey` over ONE
readable file quarantined it — in production, moving a good file out of the inbox. The view is lazy, so the
read and the transform fail in one statement and the error text cannot tell them apart. **The seam:** when
`materialize` throws, `streamUnit` re-drives the read ALONE with `SELECT COUNT(*) FROM raw_input` — the same
readability probe `unionStreamingIngest` runs per member, so both native lanes now share one definition of
*unreadable*. Probe fails → the original error propagates and the member is `QUARANTINED_UNREADABLE` as before;
probe succeeds → `TransformFailedException` (*“transform failed for &lt;file&gt;: …”*), which `streamingIngest`
rethrows beside `SinkFlushException`, so the batch is `FAILED` and the file stays in the inbox for the retry.
**Cost: zero on a successful batch** — the probe runs only on the failure path (one extra read of a file that
already failed). A cheaper pre-read or a `DESCRIBE` bind-check was not needed and would have taxed every batch.
⚠ A runtime transform error (e.g. author SQL in an `EXPR` rule that throws) is classified correctly too — the
probe does not care WHY the transform failed. The chunked lane now classifies per chunk too — see the next
paragraph (`CHUNKED-UNREADABLE-FAILS-BATCH-1`). The Java parse lane (`IOException` only) and both plugin lanes (transform
wrapped in `SinkFlushException`) already classified correctly. Pinned by
`ConsignmentIngestorTest.singleMemberTransformFailureFailsTheBatchAndLeavesTheFileInTheInbox` and
`…singleMemberUnreadableInputIsStillQuarantinedUnreadable` (a non-gzip `.csv.gz`, which fails INSIDE
`materialize`, so it exercises the probe's unreadable branch — a probe forced to succeed turns it red).

✅ **The chunked lane quarantines an unreadable file, and rolls back the chunks it already wrote**
(`CHUNKED-UNREADABLE-FAILS-BATCH-1`, fixed 2026-09-23). `chunkedIngest` had no quarantine catch, so an
unreadable file over `processing.chunking.max_file_bytes` FAILED the batch and stayed in the inbox until
`CommitRetry` exhausted it (`retry_exhausted`, not `unreadable`), each attempt re-writing the readable chunks.
**Classification, per chunk, with no second helper:** each chunk goes through the same `streamUnit`, so the
`COUNT(*)` probe above already splits a failed `materialize` into `TransformFailedException` (batch `FAILED`,
file stays) or the raw read error (unreadable); `SinkFlushException` still fails the batch. 🔴 **The realistic
unreadable chunk is not a `read_csv` error at all** — chunks are re-written as clean UTF-8 and read with
`ignore_errors=true` — it is `FileChunker`'s own read of the source: a corrupt `.gz` fails in its constructor, a
TRUNCATED `.gz` fails in `next()` after earlier chunks were written. `FileChunker` now raises those as
`UnreadableSourceException`, kept apart from a plain `IOException` writing the chunk to scratch (disk full, a
too-long path — the host's fault, which still fails the batch, the `WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`
lesson). The constructor closes its stream when it throws: the caller then MOVES the file, which an open handle
refuses on Windows. **Atomicity (decided 2026-09-23):** each chunk's write is revealed into the partition dirs
on its own, and a quarantine never commits (no manifest, registration or lineage), so the earlier chunks would
sit in the Dataset as unregistered orphans that a glob still reads — a half-written Consignment, silently. So
an unreadable chunk makes the whole FILE unreadable: `rollBackChunks` deletes every output file the earlier
chunks wrote and the batch's branch-commit log (which would otherwise claim those chunk scopes committed), then
the file is quarantined `QUARANTINED_UNREADABLE` with *“chunk N unreadable: … (K output file(s) written by
earlier chunks rolled back)”* — the same all-or-nothing a single-member file has. ⚠ Not undone: Decision Rule
record-quarantine copies (`quarantine/records/…`) from earlier chunks — quarantine evidence, beside the file.
⚠ A chunk output that OVERWROTE a same-named file from an earlier committed batch (same file stem re-ingested)
is deleted too; that earlier content was already gone at the overwrite. Pinned by `ChunkedStreamingTest`
(+3): `truncatedGzipLaterChunkIsQuarantinedUnreadableAndEarlierChunksAreRolledBack` (20 chunk outputs written
then removed; red with the rollback disabled, and separately with only the branch-log delete disabled),
`corruptGzipChunkedFileIsQuarantinedUnreadable`, and `chunkedTransformFailureFailsTheBatchAndLeavesTheFileInTheInbox`
(red when the per-chunk catch swallows `TransformFailedException`).

✅ **`PARKED-BRANCH-LEAK-ON-FAILED-BATCH-1` FIXED 2026-09-23:** `ConsignmentIngestor.process` now drains the batch's `ParkedBranches` entry on EVERY non-dry-run outcome — a non-SUCCESS batch (FAILED, `QUARANTINED_UNREADABLE`, …) drains and discards it, so the static registry never outlives its batch; a re-run re-parks to the same `<backup>/parked/<batchId>__<node>.parquet`. Pinned by `ChunkedStreamingTest#parkedBranchEntryIsDrainedWhenTheBatchDoesNotSucceed`. ✅ `GRAPH-RAW-COMPANION-ENRICHMENT-ILLEGAL-EMIT-1` FIXED 2026-09-24 — the companion edge is a derived display-only `rel: companion` edge the save path drops (see above). ✅ `ENRICHMENT-MIDWALK-LANES-DISAGREE-1` FIXED 2026-09-24 — a mid-walk enrichment is refused at save, `ENRICHMENT_NOT_TERMINAL` (see *Two blind spots closed* above).

✅ **A failed partition write fails the batch; it never quarantines the input**
(`WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`, fixed 2026-09-23). A scratch path near 250 characters failed the
partition write on Windows, and the member was marked `QUARANTINED_UNREADABLE` although it read fine. The
cause was not Windows-specific: `NativeCsvStreamingEngine.streamingIngest` (the single-member native
`read_csv` lane) held the `read_csv`, the transform **and** the `writeAndTrace` in one `catch`, so any sink
failure became an unreadable-input verdict — and in a production run that verdict **moves a good file out
of the inbox**. `streamUnit` now wraps the write in `SinkFlushException` (the type the generation and union
lanes already use for the same distinction) and `streamingIngest` rethrows it, so the batch is `FAILED`
with *“partition write failed for …”* and nothing is quarantined. A genuine `read_csv` failure surfaces
before the write and is still `QUARANTINED_UNREADABLE`. Pinned by
`PipelineTestRunTest.aFailedPartitionWriteFailsTheBatchAndNeverBlamesTheInput`, which blocks the partition
directory with a regular file, so it runs on every platform. The route's scratch prefix is also shorter
(`itr_`, was `inspecto_testrun_`); the rest of a written path — the temp dir, the partition layout, the
file stem — is data and still counts toward the Windows limit. ⚠ A `FAILED` test run still answers
*“no rows were parsed”* — no longer: it answers 422 with the batch's error (above).

### A `route:<segment>` edge out of a parser IS walked (D5, signed 2026-09-22)

✅ **Decision (operator, 2026-09-22):** the test run **walks segment routes**. The alternative — declaring
segment-routed frontends out of scope with a named 422 — was considered and declined, because it would
leave 2 of 8 parse frontends, and every multi-record-type feed, with no test instrument past the decoder.

✅ **AS-BUILT since 2026-09-22 (`WB-08`), verified live 2026-09-23.** The dry-run seeds the parse node
with **one relation per segment** — `PipelineDryRun.runSeeded` takes `rel → rows`, materialises one
scratch table each, and `PipelineExecutor.dryRun` accepts a `rel → table` seed map. `liveInbound` then
follows the existing edges unchanged.

⛔ **The walker is NOT special-cased, and must not become so.** A second traversal rule for `route:` is
how a preview and a real run begin to disagree. The fix was the SEED SHAPE, which is what `D5` decided.

⚠ **Seed keys are the graph's own edge relations**, built with `PipelineRel.route(...)` over
**`PipelineLift.routeKey`** — made public for exactly this. ⛔ Never re-derive that sanitisation: it is a
name that has to match the edge exactly, and a second copy is free to drift.

⚠ **A segment that routed nothing is ABSENT from the result, not present with zero.** *Did not run* and
*ran and produced nothing* are different answers and the canvas renders them differently, so an empty
segment is never seeded with an empty table.

**Measured live 2026-09-23** against a running control plane over real bytes: `asn1_example` (BER)
returns `map_moCallRecord · data · 3 rows` carrying the decoded values and writes 3 rows under the
`moCallRecord` partition; `xml_example` (plugin ingester) returns `map_order · data · 3 rows`.

🔴 **What it was:** both returned `relations: []` with the honest DRYRUN-2 warning *“the sample reached
no node past the seed 'parse' — nothing downstream consumed it”*. The seed only ever wrote `data`, so the
walk could not leave a `parse →(route:<segment>)→ …` parser at all: two of eight parse frontends, and
every multi-record-type feed, had no test instrument past the decoder. ⚠ The plan briefly recorded this
as blocked on unrecoverable attribution — wrongly: `IngestOutcome.schemaByOutput` (output file → segment)
had carried it since the union-mode ingester wrote it; nothing was reading it out. Pinned by
`SegmentRoutedDryRunTest`; row `TESTRUN-SEGMENT-ROUTE-NO-FLOW-1`.

### Testing note worth keeping

Both safety properties were **falsification-probed**, and one probe changed the test:

- Removing the staging copy did **not** fail an assertion that merely checked "the inbox file still
  exists" — `QuarantineManager`'s own poll-root guard threw instead of moving, so the file survived for
  an unrelated reason. The test now pins containment **positively**: the staged copy must be found
  quarantined *inside* the scratch root.
- Replacing the jail with a plain `resolve()` turns the escape test red, and the failure output is the
  vulnerability in the clear — `../secret.csv` read, parsed, and its contents returned in the response.
- **The cutoff probe caught a bad test before it caught bad code.** Swapping `ancestorsOf` for a truncated
  `topoOrder` left the headline "a sibling branch does not run" test **green**: Kahn's order for the fork
  fixture is `acq, left, right, …`, so bounding at `left` gives the right set by coincidence. Only bounding
  at the sibling that sorts **later** discriminates, which is what the test does now.

**Re-run those probes rather than trusting a green suite** when touching staging, the jail, or the cutoff —
and when a probe leaves a test green, suspect the test, not the probe.

### Code

- `platform/inspecto-engine/…/inspector/PipelineTestRun.java` — `run` (binds `RAW_INPUT`), `Result.rawRows`, `sampleRows`, `deleteScratch`
- `platform/inspecto-etl/…/etl/DataTransformer.java` — `RAW_INPUT` / `RawInputObserver`, checked in `materialize`
- `platform/inspecto-etl/…/etl/PipelineConfig.java` — `forScratchRun(Path)`
- `inspecto/…/control/PipelineGraphRoutes.java` — `testRun`, `testRunRoot`, `graphFor`, `fileList`, `runResult`
- `platform/inspecto-acquire/…/acquire/LocalConnectionWorkbench.java` — `jail(Path, String)`
- `inspecto/…/control/PipelineInboxRoutes.java` — `list`, `upload` (INBOX-UPLOAD-1); test `ControlApiPipelineInboxTest` (8, real HTTP)
- `platform/inspecto-engine/…/pipeline/exec/PipelineExecutor.java` — `dryRun(…, stopAtNodeId)`, `ancestorsOf`
- `platform/inspecto-engine/…/pipeline/exec/PipelineDryRun.java` — `run(…, stopAtNodeId)`, `notExecutedWarnings`, the `WebhookSink.plan` loop in `runSeeded`
- `platform/inspecto-util/…/util/DuckDbUtil.java` — `withoutPendingQueryPreamble`
- `platform/inspecto-engine/…/query/QueryExecutor.java` — `run` (the plain-`Statement` view registration and no-bind query)
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (8, real HTTP),
  `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos vs a real ingest),
  `PipelineDryRunTest` (15, of which 5 pin the cutoff), `ControlApiDryRunBlindSpotsTest` (2, real HTTP — G8)

## Whole-pipeline dry run (`PIPELINE-DRYRUN-1`, shipped 2026-09-20)

`POST /runs/{name}/trigger?dryRun=true` runs an entire cycle — acquisition **and** the flat ingest lane —
and **lands nothing**, for **every** pipeline, with no per-pipeline caveats: no partition outputs, no
quarantine or backup moves, no manifest, no markers, no audit or commit-log rows (not even the commit
log's header file), no fingerprint/watermark ledger entries, no unpack scratch. Every suppressed mutation
is logged `dry run: would …`, so the log *is* the report of what a real run would have done. 🔴 **The ONE
durable write a dry run makes is a provenance record flagged `simulated`** (metadata only, below).

**Two flags, deliberately distinct.** `?skipPostAction=true` means *acquisition fetches but never acks the
remote original — the ingest write still happens for real*. `?dryRun=true` means *nothing happens*.
✅ **`dryRun` IMPLIES `skipPostAction`**, OR-ed in at both `RunRoutes.triggerPipeline` and
`CollectorProcessor.run`, because hazard (a) — a "dry run" that deletes the customer's remote file — is the
worst failure this feature can have and **must not depend on a caller remembering**. The converse does not
hold. The job framework's unrelated `JobContext.dryRun()` (a genuine at-rest preview) keeps its own name
and shares no method on this chain.

🔴 **`strategy.ingest` is CONTAINED, never substituted, and that is the load-bearing decision.**
A survey of the lane found **~20** durable sites inside it — `PartitionWriter.write`,
`QuarantineManager.quarantine` (×3), `SchemaDriftSignal.emit`, the `BranchCommitLog` write, the park-home
parquet `COPY`, `ParkedBranches.record`, `ConsignmentGraphRunner`'s branch commits, and the unpack plugins'
scratch expansion. Substituting each is exactly the *"every sink honours a flag"* shape that misses one —
the failure mode `DryRunServices`' own javadoc names. From 2026-09-20 the pass was therefore **skipped
whole** (true by construction, but it parsed nothing). Since 2026-09-24 (`FLAT-DRYRUN-COUNTS-ZERO-1`) it
**runs for real inside three containments**, each true by construction because nothing inside the pass is
told it is a dry run — `PipelineTestRun.dryIngest`, shared with the builder's test run:

1. **Call graph** — only `strategy.ingest` runs; `commit` / `writeAudit` / `recordProvenance` stay the
   gated tail below.
2. **Filesystem** — the pass runs over **copies** of the members (a quarantine is a `Files.move` of the
   *source*) against `PipelineConfig.forScratchRun`, which re-roots every destination under a scratch root
   on the data volume (`processing.duckdb.temp_directory`, else `dirs.temp`, else the JVM temp dir); the
   root is deleted before the result returns. The pass runs under `<batchId>__dryrun`, because the branch
   commit log is keyed by batch id and lives in the explicit DuckDB temp dir when one is set — a directory
   `forScratchRun` does not re-root — so the real id could have resumed or appended to a real batch's log.
3. **Ambient emitters** — `EventLog.CONTAINED` (a `ScopedValue` consulted by `EventLog.current()`) is bound
   to a throwaway log for the pass, so the schema-drift Signal, the dedup-dropped event and the capture
   appender's lines never reach the space's ledger. One seam at the lookup, not a flag per emitter. ⚠ It
   does not follow work onto another thread. The builder's test run gained the same binding (before it, a
   test run over a drifted file raised a real WARN Signal).

✅ **So a dry run now DOES parse.** Each member is reported in the run log, keyed on the member vocabulary:
`dry run: consignment <id> member <file> → <kind> (<MemberStatus>): N parsed, M rejected row(s) — <reason>`,
where the kind (`PipelineTestRun.MemberKind`) is **`WOULD_LAND`** (`SUCCESS` in a batch that did not fail),
**`REJECTED`** (`QUARANTINED_*` — validation; the batch carries on), **`SKIPPED`** (`SKIPPED_UNREADABLE`) or
**`FAULT`** (the batch threw — even a member that parsed lands nothing; status `not reached` when the lane
had not audited it yet, which the native union lane never has before its one transform). ⚠ Two facts the
kinds pin: a **plugin decoder that throws on one file is `REJECTED`** (`QUARANTINED_UNREADABLE`, caught per
member), not a fault; and **`SKIPPED` is unreachable under a dry run today** — `SKIPPED_UNREADABLE` comes from
the unpack stage, which a dry run skips because it writes expanded copies. The batch line carries the real
parsed and would-land totals. ⚠ Residuals: the pass **copies** every member (a dry run over a large inbox
costs its size in scratch), a `route:` pipeline with a **disabled branch sink** faults in the pass (the park
hook needs `dirs.backup`, which `forScratchRun` nulls — shared with the test run), and per-record offsets are
not reported (the reject sidecar `<errors>/<file>_errors.csv` is written in the scratch root and deleted).

**It PUBLISHES, marked — and that is safe because the consumer set is CLOSED.** `ConsignmentEvent` gained a
`boolean dryRun` component (11 in total; the pre-existing 7-arg back-compat constructor absorbed the 3 main
and 24 test sites that needed no edit). There is exactly **one** fan-out — `ConsignmentAuditWriter`
constructs the terminal event and hands it to `commitListener` (→ the bus) and `terminalBatchSink`
(→ the `pipeline.batch.committed` Signal) — so the consumers are enumerable, and were enumerated:

| Consumer | Verdict |
|---|---|
| `JobService.fireOnCommit` (the `on_pipeline` chain) | **HONOURS** — the `Firing` inherits `event.dryRun()` instead of a hardcoded `false`, so the chained Job runs dry too |
| `JobService.onSignalEvent` (the `on_signal` chain) | **HONOURS**, by a *different* mechanism — it builds its `Firing` from a `Signal`, not an event, so `PipelineConsignmentSignal.emit` and `commitPayload` put the flag **in the payload** and `signalDryRun` reads it back. It **fails closed to "real"** for any payload carrying no flag |
| `AlertService.onEvent` | **REFUSES loudly** — evaluation is not a read; it fires Alerts, advances cooldowns and may open Incidents |
| `CollectorService`'s event-store bridge | **REFUSES loudly** — a `BATCH_COMMITTED` row would assert a commit that did not happen |
| `PipelineScheduler.onUpstreamCommit` | **REFUSES loudly** — a downstream *pipeline* run has no dry mode to inherit |
| `EnrichmentService.onConsignmentEvent` | **REFUSES loudly** — a recompute writes for real, over partitions the simulated batch never wrote |
| `PipelineConsignmentSignal.emit` | **MARKED, still emitted** — it is the observability record that a simulated batch happened |

⇒ No consumer silently ignores the flag, so publishing does not make the flag a lie. ⚠ This **deliberately
and narrowly overturns** the older written invariant *"cron/event/signal fires are always real"*: such a
fire is now dry exactly when the batch that caused it was simulated, and never otherwise.

⛔ **Rejected on the way here, recorded so they are not re-proposed:** rerouting `?dryRun=true` through the
job lane (where gate 2's `DryRunSinkWriter` already works) — it covers only pipelines a `pipeline`-type Job
processes, so it would silently write for flat-lane pipelines; a narrow build plus another rename (make only
the chained Job dry) — it does not deliver a dry run at all; marking all four `ConsignmentEvent` publish
sites "for symmetry" — two of them never consult any dry-run flag, so the marker could only assert "this
was real" about runs nobody asked about; and parking the work.

⚠ **The completeness guarantee is only as good as the next PR.** A fifth mutating surface added without the
same discipline silently breaks it — when reviewing changes that touch acquisition or the ingest lane, grep
for `DatasetWriteSignal.emit`, new `CollectorConnector.post` implementors, and new writers inside
`strategy.ingest`.

🔴 **The one deliberate exception: a flat-lane dry run records ONE provenance record, flagged
`simulated` (`DRYRUN-INVISIBLE-ON-FLAT-LANE-1` a — operator decision 2026-09-23).** `ConsignmentIngestor`
calls `recordProvenance(…, simulated = dryRun)` on both paths, so the no-op writes exactly the rows a real
run writes — `parse` and `sink`, one per node, keyed by the batch id the marked `ConsignmentEvent`
carries — through the same `ProvenanceStores` seam, marked the way the job/graph lane's
`PipelineJobRunner` marks its own (`ProvenanceRow.simulated`). It is **metadata only and the only write**:
no sink file, marker, status CSV, backup, quarantine — and no commit log (its constructor wrote the CSV
header, so `CollectorProcessor.ingest` now passes it no commit-log path on a dry run). ✅ **The counts are
real** (`FLAT-DRYRUN-COUNTS-ZERO-1`, 2026-09-24): `parse` = rows the contained pass accepted, `sink` = rows it
would have landed. Until then they were the zeros of a skipped pass — the row made the dry run *visible*
but did not estimate it. As on a real run the record is written for a `SUCCESS` batch only, so a dry run
whose every member is rejected shows in the run log, not the overlay. With provenance disabled (no store registered, the
`-Dprovenance.backend` default) nothing is written at all, the same as a real run. Half (b), the UI
marking, shipped 2026-09-23 (`e02eeab2`): `GET /provenance/batches` carries a per-batch `simulated`, and
the Pipeline editor's run overlay labels, dashes and annotates a dry-run batch
([pipeline editor](user-guide.md)) — so a dry run is now visible **and**
marked on both lanes. Pinned by `FlatLaneDryRunTest` (a filesystem diff of the whole pipeline tree plus
the store's `batches()` read, and its real-run control) and `FlatLaneDryRunParseTest` (per-kind outcomes,
and zero side effects against probes the real pass trips — the quarantine move and the DB-export
watermark, each with its real-run control).

**References.** Provenance: the archived plan
[`archived-documents/plans-archive/pipeline-dryrun-design.md`](../../../../archived-documents/plans-archive/pipeline-dryrun-design.md).
Route contract: `docs/api/openapi-v1.json`. Release note:
[`okf/product/module/api/plan-api-stability.md`](../api/plan-api-stability.md).
Pinned by `FlatLaneDryRunTest`, `JobServiceTest.aChainedOnPipelineFiringInheritsTheUpstreamBatchesDryRunFlag`
and `ControlApiAsyncV1Test.pipelineTriggerDryRunImpliesSkipPostAction`.

## Per-Step `enabled` — park and drain

Switching a Step off means different things per execution lane (the lane map is
[§ Execution lanes](#execution-lanes) above), and conflating them is the whole design problem this
concept solves: in the **scratch lane** a disabled node is an in-memory **bypass** (it produces
nothing, everything downstream is inert — correct there, deliberately unchanged), while on the
**ingest lanes** a bypass would *lose rows* — so there a disabled Step is a durable **pause**: the
Consignments that reach it **PARK**, and a later **drain** completes them.

Decision of record: **D-13**, ELT Phase 4 S4. Shipped 2026-08-28/29 across five slices; the slice plan is
[`elt-s4-park-drain-plan.md`](../../../../archived-documents/plans-archive/elt-s4-park-drain-plan.md).

### The durable home: `processing.disabled_steps`

One key, one home — a pipeline-level list of Step ids:

```toon
processing:
  disabled_steps[1]: sink__d1
```

`PipelineLift` overlays `enabled: false` onto each named node, so the canvas and the scratch lanes get the
behaviour for free; `PipelineEditable.toMap` **derives the list back** from node state on save. Rejected:
a per-node key in every lower branch (a 7-branch × 2-mirror drift class), and a graph-native-only home (the
graph write path that could author it is retired, so it would be dead config by construction).

⚠ `PipelineEditable.toMap` rebuilds node configs from the RAW map by ownership rules, which **drops any
lift-time overlay** unless carried explicitly. The `enabled` flag is carried explicitly for exactly that
reason; any future overlay hits the same trap.

⚠ `enabled` is a **structural** node field, not a declared `NodeAttribute` — `PipelineNode.enabled()` reads
`config["enabled"]`. Declaring it as an attribute would render it on every sink, including flat-lane ones
the save gate refuses, and would force the UI to mirror the arming rule.

### Arming — where a park is even possible

At rest the executor is seeded **downstream of parse/map**: only the `route → sinks` subtree runs as graph
nodes. So `StepDisableArming` relaxes exactly one shape — a **sink strictly inside an armed `route:`
subtree** — and refuses, at SAVE and at `prepare()`, never as a silent skip at rest:

1. any `disabled_steps` on a pipeline with **no armed `route:`** (the flat lane has no park boundary; the
   answer is dry-run / run-to-here). Lifting this is Phase 6's non-route parity project;
2. disabling `collect`/`parse`/`map` or anything at-or-upstream-of the route node (the executor is seeded
   below them; the answer is `active: false` or the Stage-B half-pause);
3. disabling the `transform.route` node itself (the divert's engagement anchor);
4. an unknown Step id (a typo must never become a silently-enabled Step);
5. **all** branches (`active: false` is that ask);
6. **no park home** (added 2026-08-29). `dirs.backup` is OPTIONAL — `PipelineConfigParser` reads it with a
   plain `get` — but `ConsignmentIngestor.parkSource` parks under `<backup>/parked`, so without it the pipeline
   arms, runs, and dies at the park with an NPE the operator reads as `park failed: null`, **one batch
   every cycle**. That is the arms-then-fails-forever shape this whole list exists to convert into an
   authoring-time answer. ⚠ `refusals()` takes `parkHomeConfigured` as a **required** argument rather than
   an overload defaulting to `true`: a caller that cannot answer must not be allowed to arm a pipeline with
   nowhere to park. Draft-map callers use `StepDisableArming.draftHasParkHome(dirs)`, which mirrors the
   parser exactly — absent and blank are both "no home".

⇒ **Consequence for `DrainCommand`:** a blank `backupPath` on a member is no longer read as a 1→N unpack
expansion. It never was one — it means `dirs.backup` was unset — and no armed batch can now be in that
state. Reporting it as an unpack problem described a fault the operator did not have; the expansion test
is now the JAR-style `archive!entry` address and only that.

⚠ `StepDisableArming.parkableSinkIds` **hand-mirrors the lift's `sink__d<i>` id grammar**. That is legitimate
only because an armed `route:` pipeline is single-schema (so the suffix is empty), and it is pinned verbatim
by `PipelineLiftTest.parkableSinkIdsMatchTheLiftedGraph`. Extend both together.

### Park — the boundary IS the materialisation boundary

`PipelineExecutor.ParkWriter` is a hook invoked when the walk reaches a **disabled SINK with a live inbound
relation**. It is `null` on every scratch path, so the bypass stays. At rest `ConsignmentIngestStrategy` supplies a
lambda that `COPY`s the branch relation to `dirs.backup()/parked/<batchId>__<nodeId>.parquet` and records it
in the batchId-keyed `ParkedBranches` stash (the `UnpackOrigins` lifecycle idiom — threading `IngestOutcome`'s
eight constructor sites was rejected).

`ConsignmentIngestor.parkSource` then does **almost nothing** of the normal commit tail, because a parked
Consignment is *uncommitted*: no DuckLake register, no §11.3 output registration, no markers, no fingerprint
stash, no watermark. What it does:

1. writes the manifest with `parkedAt` (node ids) + `parkedTables` (nodeId → Parquet path) and every member
   `PARKED` — the inspectable record of where and why the Consignment stopped;
2. moves each plain member's **original** into the park home (`dirs.backup()/parked/<poll-relative-path>`,
   recorded as `MemberEntry.backupPath`) so the next poll does not re-ingest it;
3. writes the **`ParkedCommit` sidecar** (`<parkHome>/<batchId>__pending.json`) — see below;
4. records `FileStage.PARKED` and leaves the batch status `PARKED` → Signal `pipeline.batch.parked`
   (neither committed nor failed).

The **branch commit log is KEPT** on park (it is the drain's resume record); it is deleted only when a batch
is fully committed.

⚠ **An unpack EXPANSION product's original stays in the inbox.** `batch.max_files: 1` splits an archive
across batches, so moving the shared original would strand its siblings; that original re-expands next cycle.
This is the crash posture — idempotent, wasteful, honest — and it is why a drain refuses such a batch.

### `ParkedCommit` — why a sidecar exists at all

A parked batch's **enabled** branches already committed their files. Their `PartitionOutput`s, lineage rows
and event-time bounds live **only in the ingest JVM's memory**, and `BranchCommitLog` records branch *ids*
and nothing more. Without persisting them, a drain in a later process could not run `finalizeSource` — the
register, `manifest.outputs` and the §11.3 registry would each record **half a batch**, silently. So park
writes them beside the park tables, and the drain consumes and deletes them.

### Drain — the commit tail, resumed

`DrainCommand.run(toonPath, batchId)`, reached through `POST /runs/{name}/drain` (`canOperateRuns`, the
structural sibling of `reprocess`). **Not a re-ingest and not a graph re-walk**: a route branch's table is
complete when its sink is reached, so nothing upstream needs re-running and no transform remains between the
park table and the write. Drain registers each park table with `read_parquet`, writes it through the ordinary
`IngestSinkWriter`, and then runs the ordinary `ConsignmentIngestor.finalizeSource` for the **whole** batch over
the union of the sidecar's outputs and its own.

Refusals (loud, never a partial — the `guardAgainstCompactedOutputs` posture); the route answers 409 with the
reason verbatim, and 404 only for a batch with no manifest:

- the batch is not parked;
- a parked Step is **still listed in `processing.disabled_steps`** — config is the truth about "is this Step
  on", never the manifest;
- a parked Step no longer names a node in the lifted graph;
- a park table or the sidecar is missing;
- the batch carries unpack-expansion members (their original re-expands and re-parks as a fresh batch, so
  completing the old one would commit rows the new one is about to write again).

Two mechanisms worth knowing before changing this code:

⚠ **The coordinator's `SOURCE` row is not this lane's finalisation signal.** The ingest path passes a no-op
`SourceFinalizer` to `ConsignmentGraphRunner` (finalisation belongs to `ConsignmentIngestor.commit`, once the batch
outcome exists), so a park run has *already* recorded `SOURCE` having finalised nothing. Drain therefore uses
`BranchCommitCoordinator` only for the durable, idempotent per-BRANCH skip, and runs the commit tail itself.

⚠ **The restore window.** Every poll-relative computation in `finalizeSource` — manifest `rel`, backup
destination, marker path, ledger key, stage rows — assumes the original is in the inbox. Drain therefore
moves it back from the park home immediately before finalising, rather than mirroring five relativisations.
The file is in the inbox for the length of the commit tail, which then moves it to backup and marks it. A
concurrent poll or a crash inside that window re-ingests the file — the same idempotent
`OVERWRITE_OR_IGNORE` posture a crash mid-commit has always had.

### The surfaces

- **Authoring** — the Step switch in the definition drawer's identity strip, offered only where the engine
  can park. The UI decides that **structurally**: a sink Step whose inbound edge from the `transform.route`
  Step carries a **`route:<key>`** relation (never the lift's `sink__d<i>` spelling, which the editable
  model never sees). ⚠ The RELATION is the whole test. A destination no branch names — the primary at
  `dirs.database`, in a config that also declares branches — hangs off the route node too, but by a plain
  `data` edge, because both lifts pair branches to sinks BY DATABASE and find no key for it. The first cut
  keyed on "inbound from the route node" and so offered the switch on a Step whose disable
  `StepDisableArming` refuses; only driving a real routed pipeline in the preview surfaced it. Switching on **deletes**
  the `enabled` key rather than writing `enabled: true`, so the lower derives an empty list. A switched-off
  Step reads `disabled` on the canvas — pause glyph + icon + label, never colour alone — computed **first**,
  because an author's explicit decision outranks every derived status, and it raises an `info` finding in
  Validate.
- **Operating** — a **Drain** row action on Run detail's Batches tab, visible only on a `PARKED` row (the
  engine refuses every other state, so offering it elsewhere would be an affordance that predictably fails).
  The 409 reason reaches the operator verbatim, because "re-enable the Step first" *is* the next step.
- **Inspecting** (PARK-1(a), execution residuals X3, 2026-09-02) — a `PARKED` row on
  `GET /runs/{name}/batches` carries the manifest's `parkedAt` (the Step ids it parked at) and
  `parkedTables` (Step id → durable park-table path), and the Batch detail dialog renders them as a
  *Parked at* table under a warning that the Consignment is uncommitted. **Merged at the ROUTE
  (`RunRoutes.withParkDetail`), not the ledger**, deliberately: the `_batches_` header has five mirrors
  and is projected by two status backends (file + db), so a new column there is a ledger-format change
  across every writer and reader; the manifest is already the authoritative park record and the route
  reads it by the same `dirs.manifestsDir` + batch id the drain does. Non-parked rows are untouched; a
  parked row whose manifest is missing/corrupt keeps its ledger fields — the drain's 404/409 already
  names that gap, and one broken Consignment must not fail the list. ⚠ `AuditRow` is typed
  `Record<string, string>` because a ledger row IS strings; these two keys are the one structured
  exception, narrowed locally in the dialog and excluded from its key/value summary (they rendered as
  `[object Object]` otherwise). ⚠ The BACKLOG row's own remedy — “needs a new read route” — was wrong;
  the premise (nothing served) was right. Pinned by `ControlApiTest.parkedBatchRowsCarryTheirManifestsParkDetail`
  (real HTTP, hand-written ledger row + manifest, incl. the missing-manifest case) and
  `batch-detail.dialog.spec.ts`.
- **Deliberately not built:** re-enabling a Step does **not** auto-drain (operator decision, 2026-08-29) — a
  config save must not start batch work as a side effect.

### References
- [Design](architecture.md) — the graph IR, lift and executor this rides on.
- [Consignment status flow](architecture.md) — where `FileStage` and the batch ledger fit.

### Decision 2026-09-06 — expansion members stay refused at drain, but the refusal names its siblings

The shared archive original cannot move while sibling batches split off the same archive still need it, and
no cross-batch member registry exists to know when the LAST sibling has drained. **Decided:** keep the refusal
and sharpen it — name the archive and the sibling batch ids so the operator drains them together. The full
lifecycle rule ("the original moves when its last sibling drains") is recorded here as the shape of the
eventual fix, not scheduled. Build: `DrainCommand.refuseExpansionMembers` + test (BACKLOG §3 `PARK-MSG-1`).

## Failure and recovery, per lane

**One recovery affordance per lane, and they are not interchangeable**: ingest has **reprocess** — which
also *retracts that Consignment's dedup-ledger claims*, or re-ingested rows would be permanently
suppressed; a parked Consignment has **drain**, which does not re-walk but registers the park tables and
runs the real finalisation; the at-rest lane has **replay** — ⚠ backed by an in-memory, capped map, so a
replay of an evicted run, or of any run after a restart, is a 404 ([plan.md](plan.md) §2.3).

⚠ **Reprocess is whole-batch only**, stated verbatim in the source. Record-level replay of one file's
rejected rows is the separate `POST /runs/{name}/replay-rejects` route, with `processing.reject_mode: eject |
all_or_nothing` as the per-pipeline choice (both under *Execution lanes* above).

Park (a durable pause, never a bypass, with six arming refusals) is under *Per-Step `enabled`* above; the
orphan output-store gate (default-on in every space since 2026-09-01) under *Execution lanes*.

⚠ **Cancelling a cron handle un-arms only *future* fires.** A hanging Job is bounded since 2026-10-06 by
**`JobDeadline`** (platform-services R1): every Run gets its Job Type's deadline (default 30 min, the
long-running types the ceiling), overridable by `deadline_seconds:`, capped by `-Djob.deadlineCeilingSeconds`
(default 24 h); on expiry the Run's thread is interrupted and the Run is recorded `FAILED`. Contributed Steps
keep their own watchdog.

## Post-sync step chains (the open DAG)

After sync a Consignment's data is a **table**; everything downstream of the sink reads it **through
Consignment info**, never through a piped relation. As-built after the open-DAG plan (stages 2/3/5
shipped 2026-08-29, stage 4 shipped 2026-08-31, stage 6 refuted, the remaining questions ratified
2026-09-06). Vocabulary follows [`GLOSSARY.md`](../../../../GLOSSARY.md): **Pipeline**, **Consignment**,
**Step** (per-Consignment) vs **Job** (cross-Consignment, [job-vs-step](../processor/architecture.md)),
**Collector**, **Dataset**. (The "§n" references in this section are to its own numbered subsections.) The
Job-side decisions of this lane (registry-not-glob reads, declared composability, a summary-write failure
fails the Run) are in [../processor/spec-jobs.md](../processor/spec-jobs.md).

### 1. The dataflow model (operator, 2026-08-29)

> *Sync contains data; Consignment information flows, with added information from the previous step(s).
> Any step can get Consignment data from Consignment info only.*

Two lanes, two contracts — do not blur them:

| Lane | Carrier | Contract | Authored as |
|---|---|---|---|
| **In-batch** (parser → filters → map → dedup → sink) | scratch relations inside the run | `PipelineNodeExecutor`, `steps:` chain | the canvas / flat TOON |
| **Post-sync** (this section) | the **Consignment output registry** | `ConsignmentProcessor` + `ProcessorContext` | a `type: consignment.process` ⚠ *(this read `consignment_process` with an underscore until 2026-09-09 — the registered id is dotted, and copying the old spelling authors an UNKNOWN job type)*`consignment.process` Job's `processor` / `chain_config` params |

⛔ **The sink stays terminal in the batch graph.** The first draft proposed `SINK_PERSISTENT` emitting
`DATA` so a step could chain after it on the canvas. Refuted: that pipes the *scratch* (pre-write,
pre-commit) relation, and the sink write is driven by the commit coordinator, so the reader would run
before anything is durable. The post-sync lane is the Consignment-addressed one, read **after commit**.

### 2. The carrier: `ProcessorContext` over the registry

`platform/inspecto-engine/src/main/java/com/gamma/consignment/ProcessorContext.java` is the Consignment-scoped
façade the framework resolves and hands to a processor: `consignmentId()` (resolved by the framework,
never the author), `outputs()` (every file this Consignment wrote — the **addressing authority**),
`read()` (a `ConsignmentReader`: read-only SQL over lazy views of this Consignment's files),
`summaries()`, `tables()`, `config()`, `log()`, `signals()`.

- ⛔ **No raw `Connection` — ever.** Rejected because it makes the read-modify-write the append-only
  path forbids trivially expressible. `SandboxConsignmentReader.frameworkConnection()` is
  **package-private** for the writer's use only; do not widen it.
- The registry (`DbConsignmentOutputStore`) is on by default (`-Dconsignment.outputs.backend=duckdb`).
  With it `none`, `GET /runs/{name}/outputs` (`RunRoutes`) says so rather than returning an empty list
  that would read as "nothing written".

#### Schema propagates; it is never re-declared

`platform/inspecto-etl/src/main/java/com/gamma/etl/TypeFlow.java` derives a Step's output schema by DuckDB
`DESCRIBE` over the *identical* SELECT it executes, without executing it — DuckDB is the type authority
and the Parquet footer carries the same types. So a derived table's schema **is** the base table's schema
after the SQL: nothing declares it, nothing can drift. The declared `raw.fields[]` belongs to the
**parser** only, where text becomes typed. ⚠ A post-sync step reads Parquet, so it is `TypeFlow`'s
*typed-source* case, not the all-VARCHAR CSV seed.

### 3. Stage 2 — derived tables (`ctx.tables().emit(...)`)

`DerivedTable(name, sql, partitionBy)` · `DerivedTableEmitter` (`@PublicApi`) ·
`GuardedDerivedTableEmitter` (validates + collects) · `DerivedTableWriter` (materialises after
`process()` returns and registers the files onto the **same Consignment**) — all under
`platform/inspecto-engine/src/main/java/com/gamma/consignment/`.

The four registry contract points, settled:

| Field | Decision |
|---|---|
| `tableName` | namespaced `<name>__derived` (mirrors `__summary`) so it can never collide with the sync tier; held to `[A-Za-z_][A-Za-z0-9_]{0,127}` because it becomes a **directory** — path jail at the seam third-party text enters |
| `producer` | the processor id, pinned end to end — ownership recorded, not inferred |
| `partitionKey` | one file per distinct value; what `compact` merges on and `ConsignmentSelector` prunes with. ⚠ A partition **value** reaches a path too — jailed, refused, never escaped. ⚠ Left blank it silently disables both compaction and pruning |
| `schemaFingerprint` | `DESCRIBE` over the produced relation — never authored (§2) |

Gotchas found by building it:

- 🔴 **Author SQL clears `SqlGuard`; a leading-`SELECT` shape check is not a substitute.** Falsified:
  `SELECT * FROM read_csv('/etc/passwd')` was admitted by the shape check on the same unsealed sandbox.
- ⚠ **The writer takes the framework's `ConsignmentReader`, not a fresh connection** — the author's SQL
  names lazy views that exist only on that sandbox.
- ⚠ **A derived table is not readable within the run that emits it** (materialised after `process()`
  returns, matching summaries). The *next step* sees it via the registry — the chain working as designed.
- `GuardedSummaryEmitter`/`SummaryEmitter` remain the *composable-measure* tier (`count` mandatory,
  every measure declares how it composes — refused rather than guessed). Derived tables sit beside it,
  not instead of it.

### 4. Stage 3 — the ordered chain

`ConsignmentProcessJobType` (`platform/inspecto-engine/src/main/java/com/gamma/job/`): the `processor`
parameter is **one id or an ordered comma-separated chain** (`mask,rollup,report`); `chain_config` is an
optional, positionally aligned JSON array of `{"config": {...}}` (`ParamType.JSON`, the vocabulary's
first nested shape; `ProcessorContext.config()` defaults to an empty map for standalone runs and
pre-existing third-party implementers).

- 🔴 **The one mechanism that makes it a chain: the registry is re-read per step** and a fresh
  `ConsignmentReader` built from that list, so step N sees the Consignment as step N-1 left it.
  Falsified: hoisting the read out of the loop fails step 2 with `Catalog Error: Table with name
  mid__derived does not exist`.
- 🔴 **Every step (and the `chain_config` length) is resolved before any step runs** — the data path
  is append-only, nothing rolls back, so an unresolvable id fails with "nothing has run".
- **Order is authored, never inferred.** Two steps may both read the base with no declared dependency.
- ⚠ **A repeated id is kept, not de-duplicated** — the second run sees a different Consignment state.
- **Mid-chain failure stays append-only (decided 2026-08-29).** Earlier steps' tables remain
  registered and `LIVE`; ⚠ the registry alone cannot distinguish a partial chain from a complete one —
  the Run's status is what says which.
- Save-time half: a hand-authored job whose `chain_config` does not align with its `processor` chain is
  refused at save (`ConsignmentProcessJobType`, CHAIN-CONFIG-1). Only the `params:` block is examined —
  a chain reached through `args:`/`bind:` is checked at run.
- 🔴 **The engine half of CHAIN-CONFIG-1: a `chain_config` value must be a SCALAR, and the engine
  accepts-then-CORRUPTS anything else.** `chainConfigsOf` reads each entry into a `Map<String, String>`, so
  `{"columns": ["a","b"]}` **saves, runs, and arrives at the processor as the string `"[a, b]"`** — no
  refusal anywhere. That is why the authoring surface refuses a non-scalar rather than trusting the engine.
  *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
- ⚠ **A null value used to NPE instead of naming the key** (`Map.copyOf` rejects nulls), so it is refused by
  name at authoring with *"omit the key instead"*.
- ⛔ **A surplus `chain_config` entry is never dropped to line the two sides up.** It becomes a row with a
  blank id and the save refuses — discarding an authored config to tidy an off-by-one is the exact failure
  this surface exists to prevent, so the lossless path is the tested one.
- 🔴 **The refusal is marked touched at seed time**, because a `mat-error` on an untouched control renders
  nothing: without that, Save would refuse **with nothing on screen**.
- **It fails open, deliberately.** If the parameter is absent from the descriptor, or the `chain_config`
  cannot be represented by the structural editor, both raw fields are left exactly as they were — *a
  structural editor that cannot read a value must not be the thing that rewrites it*. Unmodelled element keys
  travel verbatim; only `config` is rewritten.
- ✅ **The processor-id picker SHIPPED 2026-09-10 (`PROCESSOR-CATALOG-ROUTE-1`).** `GET /jobs/processors`
  (`JobRoutes`, beside `/jobs/types` and `/jobs/expressions`) serves the registered `ConsignmentProcessor`
  ids from `JobService.processorCatalog()`, and the chain editor's *Processor id* field is an autocomplete
  over them. It **suggests without constraining** — no validator consults the catalog, because a processor
  jar can be deployed *after* its Job config is authored, so a catalog-backed refusal would lock an author
  out of a valid chain.
  ⚠ **The catalog is EMPTY on a stock install and that is correct, not a defect.** The product ships **zero**
  `ConsignmentProcessor` implementations — only the `tools/templates/processor` scaffold, whose services file
  still carries an unexpanded `{{packageName}}` placeholder — so entries appear only where a third party has
  put one on the classpath. That is why the empty state renders an explicit *"nothing to suggest — type the
  id"* hint rather than an inert dropdown, and why the value accrues to deployments with a deployed
  processor rather than to the stock product. `ProcessorCatalogTest` pins the zero-implementations fact so a
  future change to it is visible.
  ⛔ **Job Packs cannot contribute a processor**, so there is no pack overlay to merge into the catalog:
  `JobPackManager` registers exactly four SPIs (`JobTypeProvider`, `ExpressionProvider`, `PipelineNodeType`,
  `PipelineNodeExecutor`) and not this one. The `ServiceLoader` set is the whole set. ✅ The scaffold's own
  `tools/templates/processor/README.md` §"Deployment differs from a Job Pack" already states the operator
  half of this — **the jar goes on the engine classpath, not into `-Djobs.packs.dir`**, with no hot deploy
  and no isolated classloader — so the two halves agree. (`packs-dev/<id>/` is only the scaffold's gitignored
  *generation* directory, not a deployment target.)
  🔴 **Two ids that can never be selected are excluded from the vocabulary, not served**: a blank one (the
  lookup matches only a non-blank id) and — less obvious — **one containing a comma**, because `chainOf`
  *splits* the `processor` param on commas. ⚠ Do not confuse that with a **repeated** id inside an authored
  chain, which is deliberately legal (one processor run twice); the catalog's `shadowed` flag is the
  different case of two *classes* claiming one id, where only the first is reachable.

### 5. Retention, merge and reprocess — "connect, don't invent"

The ownership/retention model was **already shipped** before the plan asked for it; a derived table
merely arrives with a `partitionKey` and a `State`:

| Wanted | Shipped |
|---|---|
| retention of replaced files | `retire_superseded` maintenance task, gated on `retention_days` ≥ 1 |
| small-file merge per partition key, on schedule | `compact` → `PartitionCompactor` (`com/gamma/job/`) |
| lifecycle | `ConsignmentOutput.State` = `LIVE` / `SUPERSEDED` / `COMPACTED_AWAY` |
| readers not seeing retired files | `ConsignmentSelector.resolve(glob)` = glob MINUS superseded/compacted paths |

- 🔴 **The reprocess cascade is FREE — proven by test, not assumed.** `DbConsignmentOutputStore.supersede`
  is keyed on the Consignment (`WHERE consignment_id = ? AND state = 'LIVE'`); a derived table registers
  under the same `consignmentId`, so base and derivative both go `SUPERSEDED`. No lineage edge exists or
  is needed. ⚠ The cascade **marks** stale — it does not recompute; the chain must re-run per Consignment.
- 🔴 **The pre-compaction window IS the reprocess window.** `ReprocessCommand` (`com/gamma/inspector/`)
  works only because a Consignment's rows live in *its own* files; compaction destroys that 1:1 property,
  after which reprocess **refuses** (a no-op unlink + re-ingest would duplicate rows). The `compact`
  schedule ("maybe 7 days") is therefore the reprocess SLA — accepted trade (operator, 2026-08-29).
  ⚠ `supersede()` moves only `LIVE` rows; `COMPACTED_AWAY` must keep its state as the evidence. A javadoc
  claiming a "partition-rewrite path" existed was corrected: there is none.
- Deferred by decision: a per-generation **derivation edge** (would buy partial invalidation + lineage
  display; nothing shipped requires it). `EdgeKind.FEEDS` in the catalog is design-grain, not instance-grain.

### 6. `read_parquet` over registered paths (operator, 2026-08-29)

🔴 **`SqlGuard` here is NOT a security boundary** — a processor is arbitrary Java on the classpath. What
it protects is the **addressing invariant**: a `COPY … TO` in author SQL writes a file the registry never
learns about, invisible to `ConsignmentSelector`, `retire_superseded`, `compact` and the next step's
`outputs()`. Reading a *registered* path keeps the invariant, so it is allowed:

- `DbConsignmentOutputStore.isReadable(path)` = registered **and** carrying a `LIVE` row. ⚠ Readability
  is per-PATH, state is per-registration — a recompute rewrites a stable path in place, so a path may own
  a `SUPERSEDED` row beside a `LIVE` one and still be readable.
- `GuardedDerivedTableEmitter` matches only `read_parquet('<literal>')` (name, whitespace, one
  single-quoted literal), verifies each against the registry, **masks it to an identifier**, and runs
  the masked text through `SqlGuard` unchanged. Anything richer stays in the text and is refused —
  **fail-closed by construction**. `ReadablePaths.NONE` is the default for callers without a registry.
- ⚠ Validation runs on the masked text while the ORIGINAL executes — sound only because they differ
  solely by individually verified substrings, which is why verification precedes masking.

### 7. Stage 5 — a contributed in-batch Step is authorable via `steps:`

🔴 **The premise was wrong usefully:** `RecipeCompiler` has no production caller (a converter/parity
artifact). The load-bearing path is the flat file's `steps:` chain, which `PipelineLift` lifts as
`"transform." + kind` with config verbatim — open all along; only two gates were shut:

- `PipelineEditable.LOWERABLE` (`com/gamma/pipeline/`) now admits a registered **non-built-in**
  `transform.*` whose `stepKindOf` resolves; `stepsOf` keys by `stepKindOf(type)`. ⛔ **CONTRIBUTED only**:
  admitting every `transform.*` would silently make `split`/`select`/`derive`/`validate`/`merge`
  authorable (pinned by a test walking all five).
- The parser gate: `PipelineConfigParser` (`inspecto-etl`) still refuses an unknown kind **at load**,
  through the `StepKindRegistry` ServiceLoader seam (`inspecto-etl`), implemented by `NodeTypeStepKinds`
  → `PipelineNodeTypes.isKnown(...)` in the engine. **No provider ⇒ "no"**, so the lean core is unchanged.
  ⚠ Relaxing to a safe-identifier shape was tried and rightly broke `refusesAnUnknownKind` — it traded a
  load-time refusal for a run-time one. ⚠ The test provider vouches for exactly ONE obscure kind; a
  permissive one is module-global.

A **post-sync** plugin step is a different contract (`ConsignmentProcessor`, ServiceLoader, `packs-dev`
example `acme.masker`) and needed authoring (§8), not a new SPI.

### 8. Stage 4 — the lane in the UI (shipped 2026-08-31)

Read side: `GET /runs/{name}/outputs?consignmentId=` and the Batch-detail registered-outputs section.
Authoring side: `inspecto-ui/src/app/modules/admin/jobs/job-chain-editor.component.ts` + `job-chain.ts`,
rendered in `job-form.dialog.html` under `@if (chainEngaged())` — a structural editor over the
`processor` / `chain_config` param pair. ⚠ The archived plan's §6.4 row still says "hand-edited TOON";
that claim is STALE as of 2026-08-31.

### 9. Decisions ratified 2026-09-06 (operator)

- **Q1** — a post-sync step's table **registers as a Consignment output**; the retire/compact/registry
  lifecycle is reused (§5).
- **Q2** — a chained step sees the Consignment **as the previous step left it** (answered by §4's as-built).
- **Q3** — when step A re-runs, B's output is **superseded through the existing revision model** (§5).
- **Q4** — a per-Consignment matrix is a **Step**; a cross-Consignment report is a **Job**
  ([job-vs-step](../processor/architecture.md)).

### 10. Refuted and deferred

- ⛔ **Stage 6 "parser output-schema publication" — REFUTED**: `ParserPlugin.preview()` already returns
  `ParseResult.Table.columnTypes`, `POST /parsers/{id}/preview` forwards it for every registered parser,
  and the parse-definition pane's `onPreviewed` offers it as the grid's **Auto** mode. It is *pre-map,
  sample-derived, advisory*; `TypeFlow` is *post-map, derived, authoritative* — siblings, not one gap.
- ⛔ Sink emitting `DATA` (§1). ⛔ Raw `Connection` on `ProcessorContext` (§2). ⛔ Opening
  `RecipeCompiler`'s verb switch (§7 — no caller).
- Deferred: derivation edge / partial invalidation (§5); folding Map into the sink (blocked by `sinks:`
  fan-out keyed on `database`). The BACKLOG row `OPEN-DAG-S1` the plan's §5 filed was CLOSED the same
  day (2026-09-06) on grounding — stage 1 (authored chains) had already shipped as §4 + §8, so nothing is owed.

### References
[consignment-addressing](spec-ingest.md#part-a--addressing) (Selector, revision model) ·
[consignment-status-flow](architecture.md) · [node-types](#pipeline-node-types--the-node-type-plugin-seam) ·
[transforms-seams](#transforms--modularity-seams) · [plugins](spec-ingest.md#plugin-ingester) ·
[step-catalog](spec-step-catalog.md) ·
[jobs](../processor/spec-jobs.md) · [job-vs-step](../processor/architecture.md)

**Provenance:** distilled from
[`open-dag-pipeline-design.md`](../../../../archived-documents/plans-archive/open-dag-pipeline-design.md)
(design 2026-08-29, stages shipped 2026-08-29/31, Q1/Q3/Q4 ratified 2026-09-06).

### Compaction is the only operation that can duplicate rows

Every other write path is append-or-replace. Compaction merges several files into one, so it has both failure
modes available — and they are not symmetric: **unlink-first loses data, which is worse than the duplicate**.
Hence the protocol: stage the merged output under a `_v2/` sibling, flip **one** marker to reveal it, then
unlink the originals lazily. A crash before the flip leaves the originals authoritative; a crash after leaves
the merged copy authoritative; neither leaves both readable. The operation must therefore be **re-runnable on
an already-compacted partition**, because a retry cannot tell which side of the flip it died on.

**The horizon is per-table and `none` must remain a legal value.** A compaction horizon is an SLA on how far
back reprocessing stays cheap, not a storage-tuning knob — so a table whose rows are never reprocessed is
entitled to `none`, and one feeding a 30-day correction window is entitled to a long horizon. ⛔ Do not make it
a single global setting.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

## Code pointers

The PIP spec's as-built pointer table ("§" references below are [plan.md](plan.md) sections).
⚠ **Cite the symbol, not the line** — and in this area, **check the symbol still exists**: twenty-plus
current citations named classes the 2026-08-31 rename removed (plan §5.3 item 7).

| Concern | Where it lives | Gap |
|---|---|---|
| The Job engine | `JobService` | 🔴 Javadoc contradicts its own default (§2.5) |
| The Job type registry | `JobTypeProvider` / `JobTypeRegistry` / `JobTypeDescriptor` | The maintenance descriptor was wrong both ways (§2.2) — ✅ fixed 2026-09-09 (§5.3 item 2) |
| The legacy type enum | `JobType` | Deprecated; 4 constants; ⚠ its javadoc names a retired file and key |
| Job config and templates | `JobConfig`, `JobTemplate` | ⚠ The config javadoc omits two recognised trigger keys |
| The Run bound | `JobService.DEFAULT_MAX_CONCURRENT_RUNS`, the permit holder | Deliberately not a semaphore ([spec-ingest.md](spec-ingest.md#part-b--concurrency-the-four-layer-hierarchy)) |
| Per-job non-overlap | `LockingRunner` | — |
| The ingest engine | `PipelineScheduler`, `CollectorService` | 🔴 **No test class exists at all** |
| Per-pipeline exclusion | `PipelineRunGuard` | Non-reentrant on purpose ([spec-ingest.md](spec-ingest.md#part-b--concurrency-the-four-layer-hierarchy)) |
| Pipeline triggers | `PipelineTrigger` (6 kinds + a scheduler enum) | ⚠ The glossary's trigger entry omits a shipped kind |
| The commit bus | `ConsignmentEventBus` | ⚠ Cited under its **old** name in a concept page |
| The lane fork | `ConsignmentIngestStrategy.admittedLift` / `flatReason` | — |
| The whole-pipeline dry run | `RunRoutes.triggerPipeline`, `CollectorProcessor.run`/`ingest`, `ConsignmentIngestor.process`, `PipelineTestRun.dryIngest`, `EventLog.CONTAINED`, `ConsignmentAuditWriter` | *Whole-pipeline dry run* above — `strategy.ingest` runs contained (member copies, scratch root, throwaway event log), so parsing IS re-validated and reported per member |
| The graph runner | `ConsignmentGraphRunner` — `engages`, `run`, `dataFedSinkCount` | 🔴 The backlog names a **non-existent** class and denies its callers (§2.3) |
| Route arming | `RouteArming.refusals` | One statement, two callers |
| The two lifts | `PipelineLift.lift` / `.stageTwo` | ⚠ Both live; see *The at-rest runner, and the two lifts* above before citing either |
| The at-rest runner | `PipelineJobRunner` | — |
| Maintenance dispatch | `MaintenanceJob` (`BUILT_IN_TASKS`, 23 ids since 2026-11-15) | Seven were unadvertised (§2.2) — ✅ fixed 2026-09-09 (§5.3 item 2) |
| Contributed tasks | `MaintenanceTaskProvider` + the backup and operations modules | — |
| Compaction | `PartitionCompactor` | — |
| The bounded retry | `CommitRetry` | 🔴 No transient/fatal classifier; the zero escape hatch is now stated under *Caps and the bounded retry* |
| The memory resolver | `DuckDbUtil.memoryLimit` | ✅ Falls back to `DuckDbUtil.defaultMemoryLimit` since 2026-09-26 (§2.4) |
| Quarantine | `QuarantineManager` | — |
| Reprocess and drain | `ReprocessCommand`, `DrainCommand` | Whole-batch only, verbatim |
| The commit log | `CommitLog` | No day column, by design |
| The run ledger | `JobRunLedger` (+ an optional projection) | ⚠ The lane table names only the default-off projection |
| Idempotency | `Idempotency` | — |
| Execution routes | `RunRoutes`, `JobRoutes` | 🔴 Replay's in-memory lookup (§2.3) |
| The declared lane owner | *Execution lanes* (this page) | The orphan-gate self-contradiction was corrected 2026-09-09; the post-sync lane is now named beside the five |
| The Job concept page | [`jobs.md`](../processor/spec-jobs.md) | ⚠ Dead class citations, by line |
| The boundary page | [`job-vs-step.md`](../processor/architecture.md) | ⚠ A fifth wrong node-type count |
| Concurrency | [`spec-ingest.md` Part II](spec-ingest.md#part-b--concurrency-the-four-layer-hierarchy) | ✅ The most accurate page in the area |
| The ingest fork | [`spec-ingest.md`](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path) | ✅ Accurate |
| The post-sync lane | *Post-sync step chains* (this page) | The job type id misspelling was corrected 2026-09-09 |
| Resource caps | [`duckdb.md`](../engine/spec-duckdb.md) | 🔴 Its bolded headline still states the absent default |
| ~~The active plans~~ | **ALL FOUR ARCHIVED** — the first three 2026-09-10, `completeness-kpi-plan.md` 2026-09-09 | ✅ Their decisions of record are distilled into the current tier (`INDEX.md` names each target). The "what actually runs" staleness this row flagged was **corrected before the move** (Sprint 7.5): four dead class names and a "you cannot add a new Step type" claim that a shipped pack overlay had refuted |

Exactly **one** practice-typed page exists here, and it is the smallest file in the set; the operator-facing
procedure lives in a 71 KB *reference*.

---

## Part III — Sinks

**What this covers.** Where a Pipeline's output goes once it has been parsed and shaped. It covers the file formats and writers,
the rule that keeps every store a top-level directory so reads never double-count, writing one Pipeline to
several destinations (with shared defaults and one DuckLake catalog per destination), writing straight into
an S3-compatible bucket, what happens to files that cannot be ingested, and the `objectstore.export` Job Type
that pushes committed output to S3 after a successful run.

## Writers, store layout and destinations

* **`OutputFormat`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/OutputFormat.java`) — an enum-as-strategy with
  `PARQUET` (compressible) and `CSV`. Each constant carries its own `copyToken()`/`extension()`/
  `supportsCompression()`; `resolve(token)` maps the config token (anything but `"PARQUET"` → `CSV`).
* **`PartitionWriter`** (`platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java`) — writes a materialized
  table to Hive-partitioned output via DuckDB `COPY … PARTITION_BY`. **Requires non-empty partition columns**
  (default `["year","month","day"]`), excludes the internal `__src_id` column, uses a two-step atomic rename,
  and parallelises rename fan-out above 16 partitions.
* **`PartitionSinkWriter`** (`platform/inspecto-engine/src/main/java/com/gamma/pipeline/exec/PartitionSinkWriter.java`) — the
  [pipeline-engine](#part-ii--execution) sink writer: delegates to `PartitionWriter` when partitions are
  declared, else writes a **single unpartitioned file**. (`sink.view` subtypes write no bytes — they register
  a view definition instead.)

### Store-layout contract (decided 2026-07-18 — closes the UAT double-count)

One store-layout contract governs where sink bytes land and what reads sweep (root cause of the
UAT-proven +72% double-count: a Pipeline Job's `data_dir` pointed inside the `orders` store, its sink
nested at `orders/rollup/`, and the dataset's recursive glob counted both):

* **Write side** — a persistent store is a **top-level directory under the space data root**.
  `PipelineJobRunner.requireTopLevelSinks` fails a Pipeline Job run closed *before any bytes are written*
  when a sink would resolve deeper (a `data_dir` pointed inside another store's tree, or a slashed
  `store` name). A `data_dir` fully **outside** the data root stays allowed (external export). Job
  configs bypass `ConfigSafetyValidator`, so this is enforced at run time.
* **Read side** — `SqlViews.storeReadRoot(dir)`: a **pipeline-shaped store** (one with a
  `database/` subtree) is read at its *mapped output* (`<store>/database/**`), so `backup/`,
  `quarantine/` (incl. Decision-Rule record quarantine) and any stray nested trees never leak into
  reads. A flat snapshot store (no `database/`) reads unchanged. Applied by `DatasetRelation`
  (`physicalRef` datasets), `SourceStoreReader` (Pipeline Job seeds + `sql.template` sources — a Pipeline Job can
  now seed straight from an ingest pipeline's store by name, no `data_dir` hack),
  `PipelineJobRunner.deriveViewSql`, and `ExpectationEvaluator`. An **explicit deeper ref**
  (`orders/database`, `orders/backup`) is honoured as written; `shared/…` Exchange refs are exempt.
  (`DbBrowserRoutes` already resolves pipeline stores to `dirs.database` by pipeline lookup.)

Tests: `DatasetRelationTest.physicalRefWithDatabaseSubtreeReadsMappedOutputOnly`,
`PipelineJobRunnerTest` (`seedReadsAPipelineShapedStoresMappedOutputOnly`,
`sinkNestedInsideAnotherStoreFailsClosed`, `slashedSinkStoreNameFailsClosed`,
`externalDataDirStaysAllowed`).

### Multi-destination `sinks:` (shipped 2026-08-02 — `0cdc9dff` + `79dcb3e6`)

A `*_pipeline.toon` may declare a top-level plural **`sinks:`** list — one `{database, format,
compression, ducklake}` tuple per destination. The single `output:` + `dirs.database` pair is the
one-element shorthand; `PipelineConfig.sinks()` is never empty (it synthesises the shorthand).

* **Model / parse / validate** — `PipelineConfig.Sink` (`@PublicApi 4.8.0`) + `resolveSinks(...)`
  (synthesises the shorthand, never throws); `PipelineConfigParser` parses the `sinks:` list (each entry
  needs a non-blank `database`); `ConfigSafetyValidator.checkSink` path-jails every `database` /
  `ducklake.data_path` and allow-lists format/compression.
* **Structural spec — refused at SAVE, not at run (2026-09-24).** `ConfigSpecs.pipeline()` declares
  `sinks` as a **list of objects**: `FieldSpec.listOf` with an item spec (`FieldSpec.items`, the
  list-of-objects facility added for it). `database` is the one required item key — every other key
  inherits `output:` (below), so requiring it would refuse valid files; `format` is the `CSV|PARQUET`
  enum, `ducklake` a map, `ducklake.enabled` a boolean. `ConfigLoader.validate` refuses a `sinks:` that is
  not a list (a map, or the comma-string a scalar list tolerates), a non-map entry and an entry with no
  `database`, naming the index (`sinks[1].database`) — so `/config/write` and every other save path
  answer **422**. Before, a `sinks:` map parsed as *no sinks* (the pipeline silently wrote to its one
  `output:`) and a missing `database` threw at config load. `ConfigJsonSchema` projects the item spec as
  the array's `items`, so an LLM author sees the element shape; the `/config` draft pane skips a field
  with `items` (it has no flat control for one — the canvas authors destinations). ⚠ The item spec is
  what let `sinks` leave `AcceptedConfigKeys.PARSER_ONLY` honestly — delisting `steps` without one was
  refused as gaming the ratchet ([pipeline-config-keys](../config/spec-pipeline-config-keys.md)).
  Tests: `ConfigLoaderTest` (the four `sinks` cases), `ConfigJsonSchemaTest.aListOfObjectsProjectsItsItemSpecAsTheArraysItems`,
  `ControlApiSinkDuckLakeSharedTableTest.writeRefusesAMalformedSinksBlock` (real HTTP).
* **Ingest fan-out** — `ConsignmentIngestStrategy.writeAndTrace` (the shared choke point) fans the main
  partitioned write to every `cfg.sinks()` destination, each under its own `database` root (the `dbDir`
  suffix beyond `dirs.database` is preserved) and its own format/compression. **Predicate =
  `cfg.sinks().size() > 1`** — *not* the whole-graph `ConsignmentGraphRunner.engages`, which miscounts a
  multi-schema batch. A single destination is byte-for-byte the legacy write. **Direct fan-out, not
  `ConsignmentGraphRunner`** — the ingest commit model is already "write everything, finalise once," and
  `finalizeSource` (backup / markers-LAST / ledger) is per-source-file, so it runs exactly once
  regardless of destination count. `ConsignmentGraphRunner` is not used by this fan-out. (⚠ When written, 2026-08-02, this line called it "the
  flow-job executor, unused by ingest"; since 2026-08-29 it drives the ingest graph fork — see
  [spec-ingest.md](spec-ingest.md#11-branch-aware-ingest--route-on-the-poll-driven-path).)
* **Bypass guards** — paths that skip `writeAndTrace` (native single-member `streamingIngest`/
  `chunkedIngest`; plugin `GenerationModeIngester`) materialise via the union path when `sinks>1`
  (`CsvIngestStrategy`, `StreamingPluginIngestStrategy`).
* **Editor round-trip** — `PipelineEditable.lower` no longer refuses `MULTI_SINK`; a graph with >1
  distinct sink database lowers to a `sinks:` list (a NEW list's shorthand is built from the first
  destination; over a file that already has one, `output:` is the default layer and is preserved — see
  below). Safe because a `transform.route`/`derive` node is not `LOWERABLE` (fails
  `UNSUPPORTED_NODE` first), so every sink reaching here is a replicate-per-destination fan-out. The
  `MULTI_SINK` constant was deleted with the pipeline spec's Wave 0 (2026-08-31) — it had been
  unreachable since this change, and an unreachable refusal code reads as a live one.
* **Refusals (deliberate, at load/runtime).** A **versioned reference store** (`reference.load:
  upsert|scd2`) + `sinks>1` is refused at `PipelineConfig.prepare()` (single version history is
  ill-defined across destinations) - **permanent (operator, 2026-10-06)**: the stamp reads ONE prior version store (`existingStoreReader(dbDir, …)`)
  to skip unchanged rows, so N destinations would be N version histories that the enrichment views would
  each read as the truth — the same "one history is ill-defined across branches" rule `graphWriteAndTrace`
  holds permanently for `route:`. Multi-sink commit is **not** cross-branch transactional (B9 stands) — a
  clone may have some destinations committed and others retrying.
* **Decision-rule *routing* + `sinks>1` is CARRIED (operator, 2026-10-06).** The routed rows are
  **replicated** under every `sinks[].database` — the fan-out's own rule for the remainder: the pipeline
  `RouteSink` in `DecisionRuleApplier.apply(…, PipelineConfig, …)` re-roots `dbDir`'s suffix beyond
  `dirs.database` under each destination, writes `<dest db>/<rel>/<route destination>` in that
  destination's `format`/`compression`, and returns one output + lineage set per destination. The rules
  still run ONCE, above the lane fork; the old refusals in `flatWriteAndTrace`, `graphWriteAndTrace`
  and `flatReason` are gone, so both lanes carry it. A single destination is byte-for-byte the legacy
  write under `dbDir`. Routed copies do not apply `filename_column` (the routed write never did).
  Pinned by `DecisionRuleWiringTest.routeWithTwoSinksReplicatesRoutedRowsOnThe{Flat,Graph}Lane`.

⚠ **Toon authoring:** in the indexed-tuple form `sinks[N]{database,format}:`, a `database` path
(contains `:` and `/`) **must be quoted** — `"/data/hot",PARQUET` — or the tabular decoder reads 0 rows.

**`output:` is the DEFAULT LAYER for every `sinks[]` entry** (operator decision 2026-09-23,
`SINKS-ENTRY-IGNORES-OUTPUT-DEFAULTS-1`). All four keys the two share — `format`, `compression`,
`ducklake`, `filename_column` — resolve **entry's own value → `output.*` → hard default** (`format` =
`CSV`, the rest none). An explicit entry value always wins, including an opt-out (`compression: none`).
Before this, an entry took nothing from `output:`: the parser defaulted an omitted `format` to the
literal `"CSV"` and left `compression` null, so the shipped `premed_events` (`output.compression:
snappy` above three `{database,format}` entries) never passed its codec to the write.

* **Where it resolves** — `PipelineConfigParser` leaves an omitted entry key **null** (no `"CSV"`
  default any more); `PipelineConfig.resolveSinks` fills it from `output:`. So `cfg.sinks()` always
  carries **effective** values, and every writer (`IngestSinkWriter`, `ConsignmentIngestStrategy`, the
  test-run scratch sinks) and `PipelineLift`'s display graph see the inherited value with no change of
  their own.
* **The editor does not materialise it** — `PipelineEditable.toMap` gives a sink node only its own
  entry's keys, and `lower` **preserves `output:` verbatim** while the file keeps a plural `sinks:`
  block (no node models that layer). Before,
  `lower` rebuilt `output:` from the primary node, which dropped the inherited codec on every no-edit save
  and would have let one destination's explicit value re-point every other destination's inheritance.
  **Collapsing to one destination** drops `sinks:` and folds the layer into the shorthand (the node's
  own value, else the one it was inheriting). `RecipeConverter` takes the shorthand destination's keys
  from its matching `sinks:` entry, not from `output:`, so the recipe round trip does not stamp the
  inherited value onto `sinks[0]` either.
* ⚠ **snappy is DuckDB's own parquet default.** A null compression appends no `COMPRESSION` option, so
  `premed_events`'s bytes happened to be snappy before the fix too. The bug only showed on disk for any
  *other* codec. That is why the on-disk test uses `zstd`: with the fallback reverted it reads `SNAPPY`,
  not `ZSTD`.

Tests: `PipelineConfigSinksTest` (incl. `anEntryInheritsEveryOutputKeyItOmits`,
`anEntrysOwnValueOverridesTheOutputLayer`), `ConfigSafetyValidatorTest` (per-sink jail/allow-list),
`PipelineLiftTest.liftsSinksListToADataFedFanOut`,
`ConsignmentIngestorSinksTest.fanOutWritesEachDestinationAndFinalisesOnce`,
`ConsignmentIngestorSinksTest.anEntryThatOmitsCompressionWritesTheOutputBlocksCodec` (reads the parquet
codec off disk), `…anEntrysOwnCompressionOverridesTheOutputBlock`,
`PipelineEditableTest.twoDistinctDatabasesLowerToASinksList`,
`PipelineEditableTest.anInheritedOutputValueRoundTripsWithoutBeingMaterialisedPerSink`,
`…anExplicitPerSinkValueOverridesWithoutRepointingTheLayer`,
`…collapsingToOneDestinationFoldsTheInheritedValueIntoTheShorthand`, and the fixture sweeps
`LiftLowerFixtureSweepTest` + `RecipeConverterTest` over the real `premed_events`,
`ControlApiPipelineCrudTest.twoDistinctDatabasesSaveAsAMultiSinkPipeline`.

**Each sink registers into ITS OWN effective `ducklake`** (operator decision 2026-09-23,
`SINK-DUCKLAKE-IGNORED-1`). The parser, `resolveSinks` and `PipelineLift` always carried a per-sink
lake, but `DuckLakeRegistrar.register` read only `cfg.output().duckLake()` and pooled every written file
into it — a sink's own lake was silently dropped, and a two-sink pipeline registered **both** sinks' files
into the output lake (measured live: `lake_out` held `A=3 B=5`, `lake_a` had no table).

* **Attribution** — `DuckLakeRegistrar.plan` assigns each written file to the sink whose `database` root
  contains it (the root every write site re-roots under: `IngestSinkWriter.write`,
  `ConsignmentIngestStrategy.flatWriteAndTrace`); the **deepest** root wins, so nested databases do not
  claim each other's files. A multi-sink file under **no** root is refused (`IllegalStateException`), not
  registered somewhere arbitrary. **One sink** (the shorthand, or a one-entry `sinks:`) takes every file
  with no attribution — byte-for-byte the old behaviour.
* **Partitioned** — the "registration is mandatory" check runs for every sink that wrote, **before any
  ATTACH**, and names the offending `sinks[]` database; a sink that wrote nothing is skipped.
* ⛔ **Two sinks registering into ONE lake table are REFUSED** (operator decision 2026-09-23,
  `SINK-DUCKLAKE-SHARED-LAKE-DUPLICATES-1`). Before it, two sinks inheriting the same lake both inserted
  into its one table, so a replicate fan-out held each row once per destination — silently. The rule is
  `com.gamma.etl.SinkLakeCollisions` (one copy, plain data in, the `RouteArming` pattern):
  * **Identity** = `catalog_url` (trimmed, verbatim — `./lake.ducklake` and `lake.ducklake` are NOT
    unified) + `schema` (default `main`) + the table **that would actually be registered**. Only
    `enabled: true` lakes register, so only they collide. The effective lake is entry → `output.ducklake`.
  * **The registered table is not always the block's `table` key.** `register` is handed `batch.table()`,
    which overrides it; that is non-null exactly on a multi-schema pipeline (`processing.schemas[].table`;
    `ConsignmentPlanner` maps blank / `default` to `null`). So there a per-sink `table` does **not**
    separate two sinks sharing a catalog, and the refusal says to give one its own `schema` or
    `catalog_url` instead. On a single-schema pipeline it suggests a per-sink `table`.
  * **Where it bites** — `PipelineConfig.prepare()` throws the first collision, **unconditionally** (a
    shape rule like the versioned-reference-with-many-sinks rule above it, not an `active`-gated arming
    rule). The save path — `/config/write`, `/config/patch`, `PUT /pipelines/{name}/graph`, bundle import
    and the draft branch of `POST /validate` — reports every collision as
    `ERR_SINK_DUCKLAKE_SHARED_TABLE` at **ERROR even on an inactive draft**, because a WARNING would write
    a file that then fails at registration. `POST /validate {configPath}` now answers **422** (not 500) for
    any `prepare()` refusal. `fromMap` (editor / lift) does not refuse, so such a file still opens.
  * ⚠ It lives in `ConfigRoutes`, **not** `ConfigSafetyValidator`: `inspecto-config` is below
    `inspecto-etl`, so the validator cannot call the rule without a second copy of it. The draft side
    approximates the parser's plugin-ingester test (`processing.ingester` / `parsing.plugin.ingester`);
    a grammar-component or `frontend: asn1` ingester beside a `schemas[]` list is not modelled there.

Tests: `DuckLakeRegistrarPerSinkTest` (7), `SinkDuckLakeSharedTableTest` (8, `prepare()`),
`ControlApiSinkDuckLakeSharedTableTest` (4, real HTTP). A live two-catalog DuckLake run (ducklake extension cached)
read `lake_a → A=3`, `lake_out → B=5` after the fix; it is not in the reactor, for the network-INSTALL
reason `DuckLakeRegistrarTest` records.

### Object-store `dirs.database` (shipped 2026-11-01 — `SCALEOUT-OBJSTORE-DATABASE-DIR-1`)

The ingest lane writes Stage-1 output to an `s3://bucket/prefix` `dirs.database` that lies under the operator
allow-list `-Dassist.safety.object_roots` (containment rule: module.config `spec-config-safety.md`
§ *Object-store containment*). Scale-out plan §5.4 bullet 6, credentials route (a):

```
dirs:
  database: s3://inspecto-lakehouse/space-a/orders
output:
  connection: lake_s3          # an s3 Connection (*_connection.toon): host/port, username = access key,
                               # password = ${ENV:…} secret, options.region, options.protocol http|https
```

* **Where it happens.** `ConsignmentIngestStrategy.configure` calls `ObjectStoreDatabase.prepare` BEFORE the
  seal (LOAD is file access and is refused after it): containment re-checked; the Connection resolved from
  `ConnectionRegistry` (this Space); its host refused unless it is a DNS name or a canonical IPv4 dotted quad
  (`EgressPolicy.checkHost`, plus no `:` or `[`: no port and no IPv6 literal, which `ENDPOINT` cannot carry
  unbracketed; no `/`, `@`, `?`, `#`, whitespace: DuckDB dials the authority it parses from `ENDPOINT`, so
  `evil:80/x.allowed.com` would pass an allow-list pattern yet dial `evil` — verification finding; and no
  decimal / octal / hex / short / leading-zero / trailing-dot spelling, which libcurl reads as an address the
  Safety Policy never compared — item (e), 2026-11-13, `ObjectStoreIngestTest.aNumericHostSpelling…`) and then
  passed through `EgressGate` (DuckDB's S3 client dials from native code, past every Java-side egress hop); `httpfs` loaded; then `CREATE OR REPLACE SECRET inspecto_output_store
  (TYPE s3, …, SCOPE '<prefix>/')` (the trailing `/` matters: DuckDB matches a scope as a string prefix) — temporary, in memory, shown redacted by `duckdb_secrets()`. The seal adds the
  prefix to `allowed_directories`; `ingestAllowedDirs` leaves the URI out of the local list.
* **What the write looks like** is `PartitionWriter.writeToObjectStore`: no staging, no reveal, a repeat write
  accumulates (`spec-db-layer.md` § the output registry).
* **Sub-directories** (`table:`, segment keys, Decision Rule routes) join through `PathJail.childOf`, a
  `/`-join for a URI; the joined key is judged by `requireObjectStore` at the write, so a `..` table name is
  refused there.
* **Refused by name** (each a `SQLException` at `configure`, so the batch fails before any I/O), decided
  under operator delegation 2026-11-01, operator-confirmed 2026-11-01: no / unregistered / non-`s3` / tunnelled or
  proxied / keyless Connection; a `sinks:` list; `produces: reference`. Readers of
  an object-store pipeline's output (DB browser, join reference, enrichment) skip it, and the seal then
  refuses the read. Left open on the row: fan-out, reference stores, readers.
* **DuckLake registration (built 2026-11-01).** `output.ducklake.enabled` is accepted with an object-store
  database, so `-Dinspecto.topology=partitioned` (which requires registration) can use one. The registrar
  opens its own, unsealed connection, which held no credentials; `ConsignmentIngestor.finalizeSource` now
  passes it a `DuckLakeRegistrar.ConnectionSetup` from `ObjectStoreDatabase.registrarSetup`, run after the
  extensions load and before the `ATTACH`: the same checks as `prepare` (containment, shape, Connection,
  bare host, `EgressGate`), then the same prefix-scoped `inspecto_output_store` secret. ⛔ Because that
  connection has no seal, every written file is first checked against the database prefix: it must start
  with `<prefix>/`, have no empty, `.` or `..` segment, and contain no `\` or glob byte (`* ? [ ] { }`), or the
  batch fails before registration (a sibling prefix, `..`, another bucket, a local path). Operator decision
  2026-11-02, replacing the 2026-11-01 use of `PathJail.requireObjectStore` here: a prefix test, not the
  containment rule's key character set, so a partition value DuckDB percent-encodes
  (`ts=2026-11-01%2010%3A00%3A00` — the `%` is what the old rule refused) registers. ⛔ This check, not the
  secret's scope, is the boundary: DuckDB matches the scope on the string BEFORE it normalises `..` (measured:
  `<prefix>/a/../../other/x` is sent to `/other/x` with the scoped credentials), and `SqlViews.pathList`
  rewrites `\` to `/`, which is why `\` is refused (verification finding 2026-11-02). DuckDB's own writer
  escapes all of these bytes, so real output never contains them. The setup is built only when the lake is enabled, so
  an unregistered pipeline is unchanged. The lake's own `data_path` / catalog credentials are untouched:
  the secret is scoped to the database prefix, so a lake whose `data_path` is in a bucket outside that prefix still needs its
  own credentials, as before.
* **The Connection's `base_path` is not consulted** — the allow-list bounds where; the Connection supplies
  only the endpoint and credentials.

Tests: `ObjectStoreIngestTest` (18 — every refusal one key away from the accepted shape; the live case runs a
CSV pipeline into MinIO and an `fn: custom` expression reading the same bucket outside the prefix lands
nothing; a second live case registers the bucket output in a local-file DuckLake catalog and reads both rows
back from the lake — red when the setup is not passed; `aWrittenFileOutsideThePrefixIsNotRegistered` is red
when the per-file containment check is removed; both run live 2026-11-01 against MinIO, none skipped. ⚠ A
shared Postgres catalog under `partitioned` was not run live; SKIPS without `INSPECTO_TEST_S3_ENDPOINT`/`_KEY`/`_SECRET`, and a skip is not a pass),
`ConfigSafetyValidatorTest.outputConnectionIsRequiredForAnObjectStoreDatabaseAndRefusedForALocalOne`.

### Quarantine outcomes

* `QUARANTINED_UNREADABLE` — the ingester threw (file unreadable/undecodable).
* `QUARANTINED_MISMATCH` — the ingester emitted **zero** rows (parsed, but nothing came out).
* `SinkFlushException` — a framework/schema error during a generation flush → **fail the batch** (not
  quarantine). Audit is always written regardless (see [ingestion](spec-ingest.md#part-i--ingest)).

## Object-storage export (S3 / HDFS) — the push post-action and its posture

**Status:** the push post-action **SHIPPED 2026-09-24** (`EXPORT-1`, built on the operator's request by
name that day; the row is closed). Discussed 2026-08-28; the options analysis below is kept as the record
of why this shape and not another.
**Related:** [jobs](../processor/spec-jobs.md) (the Job framework it runs in) · [dataset consumption / the `dataset` connector](spec-acquisition.md)
(the inbound mirror of this question) · [connectors](../connectors/spec-connectors.md) ·
[operations reference](../../layer/platform/admin-guide-operations-reference.md).

### As built (2026-09-24) — `objectstore.export`

A built-in **Job Type**, not a pipeline key: a Job with `on_pipeline: <name>` fires on that Pipeline's
batch COMMIT and never on a failed run, which is exactly "after a successful run" — so the pipeline TOON
learns nothing about exports.

```toon
job:
  name: push_orders
  type: objectstore.export
  on_pipeline: orders
  connection: lake
  local_path: orders/database
```

| Param | | Meaning |
|---|---|---|
| `connection` | required | Id of an `s3` Connection (`*_connection.toon`). Its `base_path` = `bucket[/prefix]` is the export root; `username` / `password` are the access key and a `${ENV:…}`-style secret reference, resolved at signing time. ⛔ Credentials never appear in a pipeline or job TOON. |
| `local_path` | required | A directory under the **Space data root** — a store's `database/`, or one Dataset's output directory. |
| `remote_prefix` | optional | Key prefix under the Connection's `base_path`; default = `local_path`. A `.`/`..` segment is refused. |
| `retries` | optional, `3` | Retries per request (0–10), exponential backoff with full jitter (`RetryPolicy`). |

**The seams.**

| Piece | Where | Note |
|---|---|---|
| `ExportConnector` / `ExportConnectorFactory` | `inspecto-acquire` `com.gamma.acquire` | The outbound mirror of `CollectorConnector` / `CollectorConnectorFactory`; `forProfile` is the `ServiceLoader` lookup by `connection.connector`. |
| `S3Connector` implements `ExportConnector` too | `inspecto-connectors` | ONE class serves both directions, so `AwsSigV4` signing and the endpoint/`base_path` rules cannot drift. `stat` = HeadObject, `put` = PutObject streamed from disk with a signed `Content-MD5`. `S3ConnectorFactory` is listed in `META-INF/services/com.gamma.acquire.ExportConnectorFactory`. |
| `ObjectStoreExportJobType` | `inspecto-engine` `com.gamma.job` | Registered as a built-in by `JobService` with the Space `dataDir`. |

**The contract** (pinned by `ObjectStoreExportJobTest` in `inspecto-connectors`, against a JDK
`HttpServer` fake S3 that re-computes each PUT's SigV4 signature and checks the body against the signed
SHA-256 and `Content-MD5`):

- **Idempotent.** A file is skipped when the remote object's size AND ETag equal the local size and MD5.
  A single-part PUT's ETag is the body's MD5; any other ETag shape (multipart, SSE-KMS) never matches, so
  the file is re-sent — the conservative answer, never a false skip.
- **Upload, then manifest.** `_inspecto_export_manifest.json` (job, run id, time, every key + size + MD5)
  is written at the export prefix LAST. A consumer that keys on the manifest never reads a half-delivered
  export.
- **Failure is failure.** A request that still fails after `retries` returns the run `FAILED` and the
  manifest is not written. Files already sent stay (object stores have no transaction); the next run
  skips them.
- **Path-jailed local reads.** `local_path` is contained in the data root by `PathJail.require`; every
  file is re-checked with `PathJail.contains`; a symlink anywhere in the tree refuses the run. All
  refusals fire in the planning pass, before any byte is sent.
- **Skipped locally:** hidden files and `*.tmp` (in-flight atomic writes).
- **Dry run** (`POST /jobs/{name}/trigger?dryRun=true`) stats the remote side and reports what would be
  uploaded; nothing is PUT, no manifest.

**Deliberate limits.**

- ⚠ **Single-part PUT only** — a file over 5 GiB refuses the run up front. Multipart upload is not built;
  engine-written Parquet partitions sit far below it.
- ⚠ A Connection that declares a `tunnel` or `proxy` is **refused**, as `WebhookSink` does: the export
  transport does not dial through either, and silently bypassing an administrator's proxy is worse.
- ⚠ **`local_path` is not in `ConfigSafetyValidator.JOB_PATH_KEYS`.** That list is for keys resolved
  against `SpaceConfigRoot.jobPathBase`; this key resolves against the Space DATA root, so adding it
  there would manufacture the gate/runtime split the list's own javadoc warns about. It is jailed at run
  time. No pipeline key was added, so `ConfigSpecs` / `AcceptedConfigKeys` / the contract JSONs are
  untouched.
- Only `s3` has a transport. GCS (interoperability mode) and HDFS reach it through an S3-compatible
  endpoint; ⛔ never `hadoop-client`.

### The grounded facts (2026-08-28)

1. **Nothing pushed outbound before `objectstore.export`.** Every connector in `inspecto-connectors`
   (S3, GCS, Azure, SFTP, FTP, Kafka, DB-export) was *acquisition-side* — they fetch **in**. Outputs are
   local parquet written by `PartitionWriter`.
2. **`output.ducklake` registers, it does not relocate.** `DuckLakeRegistrar` ATTACHes a DuckLake
   catalog (`DATA_PATH` from config) and registers already-written *local* paths — bytes stay put.
3. **The engine is deliberately local-filesystem-shaped.** Atomic temp+rename writes
   (`AtomicFiles`), marker files, path jails, DuckDB file databases, config-registry directory
   scans, poll-dir semantics. S3 has no atomic rename and no real directories, so a whole *space*
   on S3 (natively or via a FUSE mount) silently changes the meaning of the crash-safe commit
   ordering. **Posture: space (config + working state) local; data tier exported.**
4. Reusable plumbing already in-repo for an outbound build: `AwsSigV4`,
   `AbstractHttpObjectStoreConnector`, and the object-store workbench family.

### Options, by ambition

| Option | Effort | What it gets |
|---|---|---|
| **Operator sync** — `aws s3 sync` / rclone of `data/<store>/database/` on a schedule | zero code, works today | outputs mirrored to S3; consumers read via DuckDB `httpfs` |
| **Push post-action / upload task** — an outbound mirror of the connector SPI: a post-commit uploader (job task or sink option) reusing `AwsSigV4` + the object-store HTTP plumbing | small–medium build | product-managed, per-pipeline "also deliver committed outputs to `s3://…`", audited |
| **DuckLake `DATA_PATH 's3://…'`** — DuckDB writes parquet straight to S3 via `httpfs` | medium — a real re-plumb: `PartitionWriter` owns the local write + swap today | the data tier *lives* on S3, catalog-managed; the space stays local |
| **HDFS directly** | the expensive one — DuckDB has no HDFS support; `hadoop-client` is a huge dependency tree colliding with the lean-SBOM / framework-free non-negotiable; WebHDFS-over-REST is buildable like the other HTTP connectors but only if mandated | — |

### Recommendation of record

If the Hadoop cluster exposes an **S3-compatible gateway** (Ozone S3, MinIO, or the org's object
store), everything reduces to the S3 column — reuse `AwsSigV4`, never take a Hadoop dependency.
Sequence of record was **operator sync first** to prove the consumption pattern, then the **push
post-action** only on demand — the operator asked for it by name 2026-09-24 and it shipped the same day
(§*As built*). `aws s3 sync` / rclone remains a valid zero-code alternative. The full-space-on-S3 idea is recommended *against* for
the semantic reasons in fact 3, not effort.

### The object-store lane, ACCEPTED against a live MinIO (2026-09-15)

`AIRGAP-S3-EXTENSIONS-1` closed on this run. `PartitionWriter.writeToObjectStore` opens with
`DuckDbExtension.ensureLoaded(conn, "httpfs", …)` (`PartitionWriter.java`), and `PartitionWriterObjectStoreTest`
(**4/4 green, 1.15 s**) drove that method against the sandbox's MinIO (`127.0.0.1:9000`, bucket
`inspecto-lakehouse`) with the explicit load in place — the acceptance the row refused to take from a green
reactor, because all four tests `assumeTrue`-skip without an endpoint and had never entered the method since
the load call was added.

* ⚠ **`INSPECTO_TEST_S3_ENDPOINT` is `host:port`, NOT a URL.** The test feeds it straight into
  `SET s3_endpoint=…` and derives `s3_use_ssl` from a `https` prefix; passing `http://127.0.0.1:9000` produced
  `Could not resolve hostname … 'http://http://127.0.0.1%3A9000/…'` and **four 45-second timeouts** that read
  like a dead MinIO. The first run of the day was red for exactly this reason; the code was never at fault.
* What the run does NOT prove: that an **air-gapped** bundle loads `httpfs` from `duckdb-extensions/<plat>/`.
  On this box `ensureLoaded`'s first step (`LOAD httpfs`) succeeds from the developer cache, so the flat-file
  fallback (`-Dduckdb.extension.dir`) was not exercised. That half is packaging's, measured separately
  (`AIRGAP-EXTENSIONS-CI-1`, the staged 10 files; autoload over the flat dir FAILS, explicit `LOAD '<file>'` WORKS).
* Still open, and NOT this row's: there is **no S3 credential/endpoint config surface in `src/main`** — the five
  `SET s3_*` statements live only in the test. Scale-out phase C §5.4 bullet 6 owns it, and its eager-vs-deferred
  resolution posture is a §1 operator call (`BACKLOG.md`). ⚠ As of 2026-09-15. Since 2026-11-01 the ingest
  lane has one — `output.connection` for an object-store `dirs.database` (*Object-store `dirs.database`* above,
  credentials route (a)).
