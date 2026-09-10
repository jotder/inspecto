---
type: Concept
title: Pipeline node types & the node-type plugin seam
description: The PipelineNodeType ServiceLoader seam, what a descriptor does and does NOT carry, and why the transform.* family cannot simply collapse into one SQL node.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/PipelineNodeType.java
tags: [engine, pipeline, plugins, spi, node-types]
timestamp: 2026-08-29T00:00:00Z
---

# Pipeline node types & the node-type plugin seam

Every node in a pipeline graph carries a `type` discriminator. `BuiltinNodeType` is the enum the lean
core ships; `PipelineNodeType` is the interface a plugin implements; `PipelineNodeTypes` is the registry
that merges them.

## The seam

```java
// PipelineNodeTypes.load()
for (BuiltinNodeType b : BuiltinNodeType.values()) m.put(b.type(), b);
// Providers are layered last so an edition can override a built-in of the same type().
for (PipelineNodeType t : ServiceLoader.load(PipelineNodeType.class)) m.put(t.type(), t);
```

* A plugin contributes by listing a provider in `META-INF/services/com.gamma.pipeline.PipelineNodeType`
  — the same shape as [`CollectorConnectorFactory`](../../../okf/backend/engine/plugins.md) and the
  [parser plugins](parser-plugins.md).
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

## The execution half — `PipelineNodeExecutor` (SHIPPED 2026-08-29)

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
  `~/.m2`, so install a current one first (`mvn -o install -DskipTests -pl inspecto-engine -am`) or it
  fails with *"cannot find symbol: class PipelineNodeExecutor"*.
* ⛔ **`scaffold.mjs new step` remains GATED and is a different thing** — that kind is *pack-hosted*
  (isolated classloader, `StepContext`, services ceiling, watchdog) and is owned by platform-services
  S2-3. A `nodetype` deploys on the engine **classpath**, like a Consignment Processor: it runs today,
  and it gets no hot deploy, no isolated classloader and no watchdog.
* The in-repo test fixture `FakeNodeExecutor` (`inspecto-engine/src/test`) contributes `transform.take`
  through the executor service file. ⚠ It deliberately registers **no descriptor**, and that is a finding worth
  keeping: **the served step catalog is a COMMITTED CONTRACT** (`StepTypesContractTest` vs
  `inspecto-ui/.../step-types.contract.json`), so a test-scope `PipelineNodeType` provider either fails
  that guard or gets a fixture type baked into the shipped client contract. Registering one broke three
  contract assertions on the first attempt. A REAL plugin still registers both halves — it just does so
  outside this build.

## Authorable ≠ lowerable — two flags, on purpose

`PipelineProjection` publishes both per type, and they are **not** the same question:

* `lowerable` (`PipelineEditable.isLowerable`) — can the flat config round-trip this node?
* `authorable` (`PipelineEditable.isAuthorable`) — may the palette OFFER a new one?

⛔ **Do not collapse them into one flag.** The comment at the site says why: filtering the palette on
lowerability *"would force the choice between offering a node nothing should create and refusing to save
a graph that legitimately still carries one."* Exactly **two** types differ today — `parser` and
`transform.dedup.marker` — and `PipelineProjectionTest` pins that list.

## The transform family — what each one emits

This is the table to consult before proposing that any of them merge. The second relation is the whole
story: **a plain `SELECT` emits one relation.**

⚠ **`accepts`/`emits` is the pre-token DATA vocabulary, and the facts below are still real** — each row
names the DuckDB relations the lift wires and `RowShaper` produces.

### The token model — the design of record

*(Distilled here 2026-09-10 from `pipeline-spec.md` §11/§12 when that plan was archived; five current-tier
pages used to delegate this to the plan. Decision **D2**, 2026-08-31, is recorded in
[`pipeline-authoring.md`](../../capabilities/pipeline-authoring/pipeline-authoring.md) § Decisions.)*

**A Step receives a Consignment token and resolves data by reference. An edge carries no records — it names
the outlet a token continues on.** The token is
`{ consignmentId, runId, attributes{…}, dataRefs[{ store, table, partition, path, rows }] }`, and ⛔
**`dataRefs` is a reference, never a payload.**

**Three outlets replace ten relations:** `main` (the token continues) · `reject:<reason>` (one reject kind
carrying its reason — `unmatched` · `dropped` · `invalid` · `duplicate`) · `route:<key>` (content demux).

🔴 **Why: the ten relations were doing four unrelated jobs, and were never the same kind of thing.** One
was the edge itself (`data`); one was content demux (`route:*`); four were a single reject kind differing only
in *reason*; and four were **Signals, not edges at all** (`success` · `failure` · `on_commit` · `gap`).
Reading that list as one vocabulary is what made the type system contradict the engine.

⛔ **What the token model does NOT fix**, so nobody scopes it wrongly: it does **not** make the config
non-flat (lift/lower stays); fan-in still needs its own decision; and it **breaks two committed contracts**
plus `BuiltinNodeType`'s declared sets. ⚠ **`ConservationCheck` must keep working across the change** — loss
accounting moves from "these four relations are loss" to "a `reject:` outlet is loss, and its reason names
which kind". That migration is real work, tracked as X5 on [`BACKLOG.md`](../../../BACKLOG.md).

⚠ **Today the constants keep their pre-token spelling deliberately** (renaming breaks the two committed
contracts): `DATA` = the token continues; `DROPPED`/`INVALID`/`DUPLICATE`/`route:*` = a token whose
`dataRefs` point at that side-relation. The runtime converges at **Phase 7** — the vocabulary is adopted now,
the runtime later. Steps 2 and 3 of that sequence (delete the five non-edges in favour of Signals; collapse
the rejects to `reject:<reason>`) are **documentation and vocabulary, and can land before any redesign is
agreed**; steps 4 and 5 need the runtime decision.

| Type | emits | Authorable? |
|---|---|---|
| ~~`transform.map`~~ | DELETED 2026-09-05 — the projection slot is a `transform.sql` (id `map`); a stored `mapping.rules[]` is read as `fields[]` | ❌ (verb `map` removed) |
| `transform.filter` | `DATA` + **`DROPPED`** | ✅ (verb `transform`) |
| `transform.select` | `DATA` | ❌ |
| `transform.derive` | `DATA` | ❌ |
| `transform.sql` (2026-09-04) | `DATA` — one author `SELECT … FROM input` over the TYPED upstream relation; a `project()`-class verb, never a split | ✅ recipe verb `sql` since 2026-09-04 (between `transform` and `summarize`; mid-branch it compiles but does not arm); one attribute `sql` ([catalog-vs-executors](catalog-vs-executors.md)) |
| `transform.validate` | `DATA` + **`INVALID`** | ❌ |
| `transform.dedup` | `DATA` + **`DUPLICATE`** | ✅ (verb `dedup`) |
| `transform.dedup.marker` | `DATA` + `DUPLICATE` | ❌ — read/lower-compat only |
| `transform.route` | `DATA` + **named `route:*`** (`emitsNamedRoutes`) | ✅ |
| `transform.join` | `DATA` | ✅ (verb `transform`) |
| `transform.lookup` (2026-09-06) | `DATA` — a `CASE` over an inline `key=value` map on one column; changes values, never row counts, so there is no second relation | ✅ (verb `lookup`) |
| `transform.summarize` | `DATA` | ✅ |
| `transform.split` | `DATA` | ❌ |
| `transform.merge` | `DATA` (multi-**input**) | ❌ |
| `enrichment` | accepts/emits `DATA` + **`ON_COMMIT`** | ✅ |

## Can the transform family fold into one SQL node?

**Assessed 2026-08-29 against the code.** The premise behind the question is correct and worth stating:
**`transform.*` has nothing to do with CSV** — every verb is `CREATE TABLE <out> AS SELECT … FROM <input>`
over a DuckDB relation, so they operate on the previous node's output metadata, not on a file.

* ✅ **The fold has since happened for the projection half — as an ADDITIVE node, not a merge
  (2026-09-04).** `transform.sql` is one author `SELECT` over the typed input (`RowShaper.sql`,
  `RowShaper.java:494-512`); `map`/`select`/`derive` were NOT retired (their type strings are a committed
  contract in stored `*_pipeline.toon`, see below). Typing stays declarative on Parse because the raw
  relation is all-VARCHAR — the split and its reasons live in
  [catalog-vs-executors.md](catalog-vs-executors.md).
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
  `POST /enrichment`, as a partition-scoped per-batch recompute, and its relationship is `ON_COMMIT` —
  documented as *"a batch committed … **cross-flow only**"*. Folding in-batch operators into it would
  move them from *inside* the batch to *after commit*, and from same-pipeline to cross-pipeline.
  `merge` is fan-in, which enrichment has no concept of; `summarize` changes cardinality that later
  same-batch Steps depend on. **It is a change of execution moment, not a rename.**

⚠ **The fold has already happened where it safely could — at the authoring layer.**
`PipelineProjection.RECIPE_VERBS` offers **16<!--count:step-types--> entries over 9 verbs** — `collect · parse` (one entry per
FORMAT, seven of them) `· dedup · transform→filter · transform→join · sql · lookup · summarize · route ·
sink`. ⛔ There is **no `map` verb**: `transform.map` was deleted 2026-09-05. `select`, `derive`, `split`, `merge`, `validate` and
`dedup.marker` are **not offered**, and one verb (`transform`) already covers two types. What is
duplicated is the type enum, not the authoring surface.

⚠ **Retiring a type is a migration, not an edit.** The type strings are a committed contract
(`node-attributes` + `step-types` contract JSON, pinned by `NodeConfigNameContractTest`) and they appear
verbatim in stored `*_pipeline.toon` files that `PipelineLift`/lower map.

## 🔴 `transform.dedup.marker` cannot simply be deleted — REFUTED 2026-08-29

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
