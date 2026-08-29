# An open DAG: steps after the sink, and pluggable authorable steps

**Status:** DESIGN, not scheduled (operator direction, 2026-08-29). No code beyond what is noted as
already shipped. **Concept home on build:** `okf/backend/engine/node-types.md` + `okf/backend/engine/
branch-aware-ingest.md`.

**The direction, as given.** The pipeline is over-restricted. Wanted:

1. **Extract** — the parser emits **schemas + data**; custom parsers pluggable, emitting metadata +
   supporting data; standard parsers have their metadata defined.
2. **Sync** — writes mapped data (schema changes propagated, through Map): *Map + Load*.
3. **Steps extend as far as needed** — repeated steps, up to report generation (summarize / matrices),
   NiFi-processor style. That is why authoring is a graph/DAG.
4. **New steps are pluggable AND authorable**, doing custom things with the incoming data, executable
   **per Consignment**.

---

## 0. The short version

**Most of the machinery exists. Three closed gates hold the vision back, and they are independent.**

| Gate | Where | What it blocks |
|---|---|---|
| **G-A** `SINK` emits no `DATA`, and the executor publishes no relation for a sink | `BuiltinNodeType:159`, `PipelineExecutor:201-204` | anything after the sink (point 3) |
| **G-B** `LOWERABLE` is a hardcoded `Set.of(BuiltinNodeType…)` | `PipelineEditable:85-100` | saving a graph containing a plugin node (point 4) |
| **G-C** `RecipeCompiler`'s step switch is closed (8 verbs, `default → UNSUPPORTED_STEP`) | `RecipeCompiler:112-130` | authoring a plugin verb in a recipe (point 4) |

None of them is an accident, and none is load-bearing in the way it first appears. §3 takes them one at
a time.

---

## 1. What is already built (more than the framing assumes)

**Point 1 — Extract emits schemas + data: largely shipped.**
* `ParserPlugin` (SPI, ServiceLoader) already gives a custom parser an `id()`, a `label()`,
  `hierarchical()`, and a **declarative `grammarSchema()` of `FieldSpec`s** — the served options a
  generic form renders. So "custom parsers pluggable, with defined metadata" is the shipped model, not
  a gap.
* Per-format built-ins each have their own node type (`parser.delimited` / `fixedwidth` / `asn1` /
  `json` / `text_regex` / `xlsx` / plugin) with their metadata defined the same way.
* `TypeFlow` derives a Step's **output schema** by `DESCRIBE` over the identical SELECT, without
  executing it — the "emits schemas" half, per Step rather than only at the parser.
* ⚠ What is genuinely thin: a parser's **output** schema is declared in the schema `.toon`
  (`raw.fields[]`), not returned by the plugin. A plugin that wants to *discover* its own output shape
  has no seam to publish it through. That is a real, small gap — and it is the one worth naming under
  point 1.

**Point 2 — Sync = Map + Load: this is what the flat lane already does**, and the shape is documented
in the lift: **parser → (row filters) → map → (dedup) → sink(s)**. `transform.map` is not a chain step;
it is the schema projection between parser and sink, authored in `processing.map`. So "Sync writes
mapped data through Map" describes the current topology accurately. ⚠ Folding Map *into* the sink has
one concrete blocker — **fan-out**: destinations are keyed by `database` dir and a pipeline may carry a
plural `sinks:` block, so one projection feeds N sinks today. Folding means either N copies or a rule
saying which sink owns it. Decide that before merging them, not after.

**Point 3 — the DAG lane exists and is armed.** `PipelineExecutor` already runs an arbitrary
topology: fan-out (`route` named branches), fan-in (`transform.merge`, multi-input), per-branch commit
via `BranchCommitCoordinator`, per-edge counters, park/drain on disabled nodes. The restriction is not
the executor's traversal — it is what a sink publishes (G-A).

**Point 4 — two of the three halves shipped, one today.**
* `PipelineNodeType` (descriptor) — palette, category, `accepts`/`emits` the validator enforces.
* `PipelineNodeExecutor` (execution, shipped 2026-08-29) — a contributed type can shape rows.
* ⚠ **Authoring is the missing half** (G-B/G-C).
* ⚠ **"Executable per Consignment" already has a different seam**: `ConsignmentProcessor` —
  *"work that runs once a Consignment has landed, over that Consignment's own data and nothing else"*
  (`packs-dev/acme.masker`). It is a **post-landing hook, not a DAG step**. Whether point 4 wants that
  seam or a DAG step that happens to run per Consignment is the first thing to settle — they have
  different contexts, different failure semantics, and only one of them composes in a graph.

---

## 2. The one that matters most: steps after the sink

**The mechanism, precisely.** A post-sink step is not refused at execution — it is **silently skipped**:

```java
if (PipelineNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
    String in = tableOf(inbound.get(0), produced);
    sinkInputs.put(nodeId, in);      // ← recorded for the commit coordinator
    prov.record(nodeId, PipelineRel.DATA, count(conn, in));
}                                    // ← and NOTHING is put into `produced`
```

A downstream node then finds `liveInbound(...)` empty and hits `continue` — *"upstream not executed
here — skip"*. Upstream of that, `PipelineValidator` would already have rejected the edge, because
`SINK_PERSISTENT.emits()` is `{SUCCESS, FAILURE, ON_COMMIT}` and every transform `accepts` `{DATA}`.

**So the change is small and well-defined:**

1. **The sink emits `DATA`** (pass-through) so the validator admits the edge.
2. **The executor publishes the sink's relation** into `produced`, so the next node has an input.

🔴 **But the semantics need deciding first, and they are the whole risk:**

* **What does a post-sink step read** — the sink's *input* relation (pre-write, in scratch) or the
  rows *as written* (post-projection, post-partitioning, possibly re-typed by the Parquet round-trip)?
  These differ, and the answer changes what "after the sink" means.
* **When does it run relative to the commit?** The sink write is driven by the
  `BranchCommitCoordinator` at commit time, and source finalisation waits for every branch. A step
  reading the sink's input runs **before** anything is durable — so "after sync" would be a lie in
  ordering terms. Running it genuinely after the commit is a different execution phase, which is what
  `ON_COMMIT` and enrichment already are.
* **What happens on a post-sink failure?** The batch already committed. Failure semantics have to say
  whether that is a failed Run over committed data (and what the operator does about it).

⚠ **The honest reading:** "extend the pipeline after sync" already exists in one form —
`enrichment` accepts `ON_COMMIT`, runs after the batch commits, and is partition-scoped per batch. What
it does **not** offer is composition: it is one hop, cross-pipeline, not an arbitrary sub-DAG. The
design choice is between *making enrichment composable* and *making the sink pass through*, and they
are not the same system.

---

## 3. The three gates, and what each would take

### G-A — the sink is terminal
Above. Two-line mechanical change; three semantic decisions. **The decisions are the work.**

### G-B — `LOWERABLE` is a hardcoded set
`isAuthorable = isLowerable && !READ_COMPAT_ONLY`, and `isLowerable` is membership in a `Set.of(...)` of
built-ins. A plugin type is therefore **greyed out in the palette** and a graph containing one **refuses
to save**: *"the flat pipeline config has no home for a 'transform.acme_redact' node"*.

That refusal is truthful — the **flat** `*_pipeline.toon` transform chain holds exactly five kinds
(`filter`, `join`, `dedup`, `summarize`, `route`), each with a dedicated block. A plugin node has no
block. So G-B is not a flag to flip; it needs a **generic home** for a node whose config the core does
not model — a `steps:` entry carrying `{id, type, config}` verbatim.

⚠ The recipe format is already close to that shape, which is why G-C matters more than G-B.

### G-C — the recipe step vocabulary is closed
`RecipeCompiler` is a `switch` over eight verbs ending in
`default -> UNSUPPORTED_STEP "unknown step verb"`. A plugin verb cannot be authored in a recipe today.

**This is the highest-leverage gate.** The recipe is the newer authoring format, it is a list of steps
rather than a fixed set of blocks, and opening it means:
* an unknown verb resolves against `PipelineNodeTypes` instead of refusing outright;
* its config travels **verbatim** (the flat lane's own "unmodelled keys survive" rule);
* it lowers back to the same `steps:` entry, so the round-trip is closed.

⇒ **Plugin authorability should land on the recipe format first, and the flat lane should keep
refusing** — a plugin node genuinely has no home there, and inventing one would fork the config format.

---

## 4. A staged path

| # | Stage | Why this order | Gate |
|---|---|---|---|
| **1** | **Decide the post-sink contract** — reads-input vs reads-written, before-commit vs after-commit, failure semantics | every other stage inherits these answers; building first would bake a guess | operator |
| **2** | **Open the recipe verb** (G-C): unknown verb → `PipelineNodeTypes` lookup, config verbatim, lowers back | makes plugins authorable on the format that can hold them, without touching the flat lane | — |
| **3** | **Palette + `authorable` for contributed types** (G-B, recipe-scoped) — a contributed type is authorable when the *recipe* can hold it, so `isLowerable` stops being the only answer | the UI half of stage 2; nothing to offer until the format can save it | — |
| **4** | **Sink pass-through** (G-A) per stage 1's decision | needs the contract, and is more useful once plugin steps exist to put after it | stage 1 |
| **5** | **Parser output-schema publication** — let a plugin parser declare/derive the shape it emits, rather than requiring a hand-authored `raw.fields[]` | independent of 1-4; closes point 1's real gap | — |

**Stages 2 and 3 alone deliver point 4** — pluggable, authorable steps that do custom things with the
incoming data — without touching commit semantics. That is the cheapest real progress toward the
direction, and it is reversible.

⛔ **Do not start at stage 4.** The sink change is two lines and three decisions; shipping the lines
before the decisions is how "after sync" becomes an ordering lie that is expensive to take back.

---

## 5. Open questions for the operator

1. **Post-sink steps: input or output?** Should a step after the sink read the rows *about to be*
   written, or the rows *as written* (re-read from Parquet, with the types the footer carries)?
2. **Before or after the commit?** If genuinely after, that is a new execution phase alongside
   `ON_COMMIT` — should it instead be *enrichment made composable*?
3. **Point 4's "per Consignment"** — a DAG step that runs in the batch, or the existing
   `ConsignmentProcessor` post-landing hook made richer? Both exist; they are not interchangeable.
4. **Reports / matrices as steps** — `summarize` exists as a verb but its output goes to a sink. Is
   "report generation" a Step that writes a different *kind* of artifact, or a Job that reads a store
   the pipeline already wrote? (The `job-vs-step` concept already draws this line and should be
   re-read before answering.)
