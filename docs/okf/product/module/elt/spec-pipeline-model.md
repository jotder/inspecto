---
type: Concept
title: Pipeline model — node types, the Step catalog, transforms and the editable round-trip
description: The backend model of a Pipeline: the PipelineNodeType / PipelineNodeExecutor plugin seams, the token model and the transform family, the Step Processor catalog vs the real executors, the transform seams, and the PipelineEditable lift/lower round-trip with every named refusal, settings, dry-run context, route branches, the parser family and the bundles.
resource: platform/inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java, platform/inspecto-engine/src/main/java/com/gamma/inspector/ConsignmentIngestStrategy.java, platform/inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java
tags: [elt, engine, pipeline, plugins, spi, node-types, catalog, transform, seam, lift, lower, round-trip, authoring, gotcha, pipeline-graph, execution, lanes, ingest, job, scratch, test-run, dry-run, park, drain, post-sync, consignment, output, sink, partition, quarantine, ducklake, export, object-storage, s3, hdfs, job-type, post-action]
timestamp: 2026-11-18T00:00:00Z
---

# Pipeline model — node types, the Step catalog, transforms and the editable round-trip

> One of the ELT spec files, split out on 2026-11-18 (DOCS-HUB-NAMES-1); section numbers are unchanged. **spec-pipeline-model.md** · [spec-pipeline-execution.md](spec-pipeline-execution.md) · [spec-sinks.md](spec-sinks.md)

**Contents**

- [Pipeline node types & the node-type plugin seam](#pipeline-node-types--the-node-type-plugin-seam)
- [Step Processor catalog vs. real node executors](#step-processor-catalog-vs-real-node-executors)
- [Transforms & Modularity Seams](#transforms--modularity-seams)
- [The editable round-trip — the editor's graph ⇄ the canonical config](#the-editable-round-trip--the-editors-graph--the-canonical-config)

**What this covers.** What a Pipeline *is* to the engine. Every Step on the canvas is a node of a known type; this part lists
those types, what each one emits, and how a plugin adds a new one. It explains why the Step catalog the
author picks from has fewer real executors behind it than entries, how the SQL Step and the Record
Transformer work, and how the editor's graph is saved to — and read back from — the `*_pipeline.toon` file
the engine runs, including every named reason a save is refused. How a Pipeline then runs is
[Part II](spec-pipeline-execution.md) below; the editor screens are [user-guide-elt.md](user-guide-elt.md).

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
  [parser plugins](spec-parsing.md#p9-parser-plugins--the-self-describing-parser-framework).
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
(`StepReceipt`; lanes in [§ Execution lanes](spec-pipeline-execution.md#execution-lanes) below). The main outlet stays spelled `data`
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

The [Step Processor catalog](user-guide-elt.md) (122<!--count:processors--> entries, 8<!--count:processor-families--> families,
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
  [the editor guide](user-guide-elt.md) for the pane.
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
[`okf/product/module/elt/user-guide-elt.md`](user-guide-elt.md) §"The step workbench".
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

Related: `OutputFormat` is the third such enum-as-strategy seam (see [output & sinks](spec-sinks.md)).

## The editable round-trip — the editor's graph ⇄ the canonical config

> **Scope (2026-09-01 consolidation):** this section owns the AUTHORING half of the backend Pipeline
> model — the `PipelineEditable` round-trip and every named refusal, the `use:` ref homes, the
> parser family's retype/lower rules, route-branch arming, the settings surface, and dry-run.
> The model itself (IR, lift, validator, token execution model) is
> [architecture-elt.md](architecture-elt.md); the editor UI is
> [the editor guide](user-guide-elt.md). Sections keep their
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
