---
type: Concept
title: Pipeline model — node types, the Step catalog, transforms and the editable round-trip — Pipeline node types and the node-type plugin seam
description: Part 1 of 4 of Pipeline model — node types, the Step catalog, transforms and the editable round-trip: Pipeline node types & the node-type plugin seam
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-etl
---

# Pipeline model — node types, the Step catalog, transforms and the editable round-trip — Pipeline node types and the node-type plugin seam

Part 1 of 4 of [Pipeline model — node types, the Step catalog, transforms and the editable round-trip](spec-pipeline-model.md) · next: [Step Processor catalog vs. real node executors](spec-pipeline-model-processor-catalog.md).

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
  — the same shape as [`CollectorConnectorFactory`](spec-parsing.md) and the
  [parser plugins](spec-parsing-profiles-plugins.md#p9-parser-plugins--the-self-describing-parser-framework).
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
[`plan-elt.md`](plan-elt.md) § Decisions.)*

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
(`StepReceipt`; lanes in [§ Execution lanes](spec-pipeline-execution-engines-lanes.md#execution-lanes) below). The main outlet stays spelled `data`
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
