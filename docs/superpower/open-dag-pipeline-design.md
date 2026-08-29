# An open DAG: the post-sync lane, and pluggable authorable steps

**Status:** DESIGN, not scheduled (operator direction, 2026-08-29; **materially corrected the same day**
once the operator described the dataflow model — see §2). No code beyond what is noted as shipped.
**Concept home on build:** `okf/backend/engine/node-types.md` + `okf/backend/engine/consignment-*.md`.

**The direction, as given.**

1. **Extract** — the parser emits **schemas + data**; custom parsers pluggable, emitting metadata +
   supporting data; standard parsers have their metadata defined.
2. **Sync** — writes mapped data (schema changes propagated, through Map): *Map + Load*.
3. **Steps extend as far as needed** — repeated steps, up to report generation (summarize / matrices),
   NiFi-processor style. That is why authoring is a graph/DAG.
4. **New steps pluggable AND authorable**, doing custom things with the incoming data, **executable per
   Consignment**.

**And the dataflow model, which is the load-bearing clarification:**

> *"Sync contains data; Consignment information flows, with added information from the previous
> step(s). Any step can get Consignment data from Consignment info only. Once file data is synced it's a
> table, and newer tables can be created from the base table, for each Consignment — that's how
> incremental summary was supposed to build."*

---

## 0. DECIDED 2026-08-29 (operator)

> **Arbitrary tables, registered as Consignment outputs.** *"Steps may write arbitrary tables; the chain
> needs an ownership and retention model — that's correct. For each persisted table (Parquet) we will
> have retention / merge (small-file merge per partition key, on schedule, maybe 7-day-old data) etc.
> later on."*

⇒ §5 Q1 is closed: a post-sync step is **not** confined to the summary guardrail. §6 is what that costs
and what it does not.

🔴 **And most of the "later on" is already shipped** — see §6.2. The registry row already carries
`tableName`, `producer`, `partitionKey`, `generation` and a lifecycle `State`; `retire_superseded` and
`compact` are shipped maintenance tasks. The ownership/retention model is not a thing to invent; it is a
thing to **connect**.

---

## 0b. The short version

🔴 **That model is already implemented, more completely than it looks — and it is NOT the model my first
draft of this design assumed.** The carrier between post-sync steps is the **Consignment output
registry**, not a piped relation, and what a step emits is **written and registered back onto the same
Consignment**, so the next step sees it. §2.

What is missing is **composition and authoring**, not the dataflow:

| Gap | Where | Blocks |
|---|---|---|
| **A** one processor per Job run — no chain, no DAG | `ConsignmentProcessJobType` | "steps extend as far as needed" (point 3) |
| **B** a post-sync step is a **Job type**, not a canvas step | same | authoring the post-sync lane at all |
| **C** output is confined to the summary guardrail — no arbitrary table | `SummaryEmitter` / `GuardedSummaryEmitter` | "newer tables from the base table" (point 3), **deliberately** — §2.3 |
| **D** `RecipeCompiler`'s verb switch is closed (8 verbs, `default → UNSUPPORTED_STEP`) | `RecipeCompiler:112-130` | authoring a **plugin** step (point 4) |
| **E** `PipelineEditable.LOWERABLE` is a hardcoded `Set.of(BuiltinNodeType…)` | `PipelineEditable:85-100` | saving a graph with a plugin node (point 4) |

⛔ **What is NOT a gap, contrary to my first draft: the sink does not need to emit `DATA`.** That
framing solved a scratch-relation-piping problem this model does not have — see §2.4.

---

## 1. Points 1 and 2 — already the shipped shape

**Point 1 (Extract emits schemas + data).** `ParserPlugin` is a ServiceLoader SPI giving a custom parser
`id()`, `label()`, `hierarchical()` and a declarative **`grammarSchema()` of `FieldSpec`s** — the served
options a generic form renders. Per-format built-ins each have their own node type with metadata defined
the same way. `TypeFlow` derives a Step's output schema by `DESCRIBE` without executing it.
⚠ **The one real gap:** a parser's *output* shape is declared in the schema `.toon` (`raw.fields[]`), not
returned by the plugin — a parser that discovers its own shape has no seam to publish it through.

**Point 2 (Sync = Map + Load).** This is the current topology exactly: the lift builds
**parser → (row filters) → map → (dedup) → sink(s)**, where `transform.map` is not a chain step but the
schema projection between parser and sink, authored in `processing.map`.
⚠ Folding Map *into* the sink has one concrete blocker — **fan-out**: destinations are keyed by
`database` dir and a pipeline may carry a plural `sinks:` block, so one projection feeds N sinks today.
Folding means either N copies or a rule for which sink owns it.

---

## 2. The post-sync lane — what actually exists

### 2.1 The carrier is the Consignment output registry

`ProcessorContext` is a Consignment-scoped façade the framework resolves and hands to a
`ConsignmentProcessor`:

* `consignmentId()` — *"resolved by the framework, never by the author"*
* `outputs()` — *"every file this Consignment wrote, from the §11.3 registry: path, row count, bytes,
  partition, record day and lifecycle state"*, and explicitly **the addressing authority**
* `read()` — a `ConsignmentReader`: **read-only SQL over this Consignment's own data**
* `summaries()` — *"the only sanctioned way to emit summary output"*
* `log()`, `signals()`

⇒ **"Any step can get Consignment data from Consignment info only" is literally the contract.** A raw
JDBC handle was considered and rejected, because it makes the forbidden read-modify-write trivially
expressible. The registry is **on by default** since 2026-08-10
(`-Dconsignment.outputs.backend=duckdb`).

### 2.2 Information really does flow, with additions

`ConsignmentProcessJobType` ends a run with:

```java
persistSummaries(ctx, consignmentId, summaries.emitted(), processorId);
// §7.3: write the validated summary rows, then register the files.
// Ordering matches §11.3's rule — the registry row is written only after the Parquet file is …
```

🔴 **So what a step emits is written as Parquet and REGISTERED as an output of the same Consignment** —
which is exactly what the next step's `outputs()` reads. The "Consignment info flows with added
information from the previous step" model is not aspirational; it is the shipped mechanism. Nothing new
is needed to carry information forward.

### 2.3 …and incremental summary is what the guardrail is for

`SummaryEmitter` is *"deliberately not a raw writer"*: `count` is mandatory on every row and **every
measure must declare how it composes** — an undeclared or mis-declared measure is *"refused rather than
guessed"*. That declared composability is precisely what lets partial summaries combine, i.e. what makes
an **incremental** summary correct rather than merely repeated.

⚠ **This is the tension in point 3.** "Newer tables can be created from the base table" is broader than
what the guardrail admits: today a post-sync step may emit **summary rows**, not an arbitrary table. That
restriction is a stated design position (§7.2 composability, enforced rather than left to authors), so
widening it is a **decision to take deliberately**, not a gap to close quietly. §5 Q1.

### 2.4 Schema PROPAGATES — it is never re-declared

> *"Schema also gets propagated and mapped with SQL, not complete new."*

This is already the shipped mechanism, and it is the same one on both sides of sync:

* **`TypeFlow.transformedColumns`** derives a Step's output schema by running DuckDB `DESCRIBE` over the
  *identical* SELECT the Step executes, **without executing it**. Its javadoc states the principle:
  *"DuckDB is the type authority; the returned types are its own inferred types, which is exactly what
  the Parquet footer will carry."*
* So a derived table's schema **is** the base table's schema after the SQL — nothing declares it, and
  nothing can drift from it, because the derivation and the execution are the same query.
* The step workbench (shipped 2026-08-29, S1/S2) already publishes exactly this per Step: the derived
  `{name, type}` schema beside the rows and the SQL that produced them.

⇒ **A post-sync step needs no `raw.fields[]` of its own.** The declared schema belongs to the *parser*,
where text becomes typed; from there on the schema is carried and transformed. Any design for the
post-sync lane should derive through `TypeFlow`, not ask an author to restate a shape the engine can
already compute — and that removes most of what would otherwise be "who defines the derived table".

⚠ The one asymmetry to respect: `TypeFlow` takes a `typedSource` flag because the CSV path seeds
all-VARCHAR while the plugin-ingester path carries declared types. A **post-sync** step reads Parquet,
which is typed — so its input is the typed case, not the VARCHAR one.

### 2.5 ⛔ Correcting my first draft

My first version of this design proposed making `SINK_PERSISTENT` emit `DATA` and having the executor
publish the sink's relation, so a step could be chained after it in the graph. **Under the operator's
model that is the wrong mechanism**: it would pipe the *scratch* relation (pre-write, pre-commit,
in-batch) to the next node, when the whole point is that after sync **the data is a table**, addressed
through Consignment info, read after commit. The two would also disagree about ordering: the sink write
is driven by the commit coordinator, so anything reading its input runs *before* anything is durable.

The correct post-sync lane is the Consignment-addressed one that already exists. **The sink stays
terminal in the batch graph.**

---

## 3. What genuinely blocks the direction

### A + B — composition and authoring
A processor runs as **one Job** (`ConsignmentProcessJobType`) over **one** committed Consignment. There
is no chain: to run two, you schedule two Jobs, and nothing expresses "B runs over what A produced"
except the implicit fact that B's `outputs()` will contain A's files. So:

* there is no ordering guarantee between them,
* no way to author the sequence as a graph,
* and no place in the pipeline UI where the post-sync lane appears at all.

**This is the real work**, and it is the smaller half of what I first estimated: the data path is done,
the *orchestration and authoring* are not.

### C — arbitrary tables vs the summary guardrail
See §2.3. A decision, not a defect.

### D + E — plugin steps are not authorable
`RecipeCompiler` is a closed `switch` over eight verbs ending in `UNSUPPORTED_STEP`; `LOWERABLE` is a
hardcoded set, so a plugin node is greyed out in the palette and its graph refuses to save with *"the
flat pipeline config has no home for a '…' node"*. That refusal is truthful — the flat chain holds five
kinds, each a dedicated block.

⇒ Plugin authorability belongs on the **recipe** format (a list of steps, not fixed blocks): an unknown
verb resolves against `PipelineNodeTypes`, its config travels **verbatim**, and it lowers back to the
same `steps:` entry. The flat lane should keep refusing rather than fork the config format.

⚠ Note this applies to **in-batch** plugin steps. A post-sync plugin step is a different contract
(`ConsignmentProcessor`, already pluggable via ServiceLoader — `packs-dev` example `acme.masker`), and it
needs authoring, not a new SPI.

---

## 4. A staged path

> 🔴 **This table is SUPERSEDED by §6.4's renumbering — read that one for stage numbers (noted
> 2026-08-29).** Once §5 Q1 closed, §6.4 renumbered the path (1 ~~decide~~ · 2 derived-table emitter ·
> 3 ordered chain · 4 editor · 5 recipe verb · 6 parser schema) and the two numberings have since
> disagreed in a way that misreads the as-builts: **§7 "Stage 2 as-built"** describes the write seam,
> which has no counterpart in the table below at all; **§8 "Stage 3 as-built"** describes what the table
> below calls stage 2; and **§9's "before stage 4"** means §6.4's stage 4 (**the editor**), not this
> table's stage 4 (the recipe verb). Keep the table only for its ordering rationale — the ⛔ below — and
> that rationale is itself spent now that Q1 is answered.

| # | Stage | Delivers | Gate |
|---|---|---|---|
| **1** | **Decide the post-sync output contract** — may a step create an arbitrary table, or must it stay within the summary guardrail? | settles point 3's scope before anything is built | operator, §5 Q1 |
| **2** | **Compose Consignment steps** — an ordered, authored chain over one Consignment, each step seeing the registry as the previous left it | point 3's "steps extend as far as needed" | stage 1 |
| **3** | **Surface the post-sync lane in the editor** — it exists in the model and is invisible in the UI | authoring at all | stage 2 |
| **4** | **Open the recipe verb** (D) + palette `authorable` for contributed types (E) | point 4 for **in-batch** plugin steps | — |
| **5** | **Parser output-schema publication** | point 1's real gap | — |

**Stage 4 is independent of 1-3** and is the cheapest standalone win: it makes plugin steps authorable
without touching commit semantics or the Consignment lane.

⛔ **Do not "just chain processors" before stage 1.** If a step may write arbitrary tables, the chain
needs a naming/ownership model for them and a retention story; if it stays within summaries, the chain is
a much smaller thing. Building the chain first bakes that answer in by accident.

---

## 5. Open questions

1. 🔴 **May a post-sync step create an arbitrary table, or must its output go through the summary
   guardrail?** The guardrail exists so incremental measures compose correctly; arbitrary tables give
   the freedom point 3 describes and hand that correctness back to each author. ⚠ Note the **schema
   half of this question is already answered** (§2.4): a derived table's shape is propagated and
   SQL-mapped through `TypeFlow`, never re-declared. What remains is naming, lifecycle/retention, and
   whether it registers as a Consignment output (so the next step sees it) or as something else.
2. ~~**Does a chained step see the Consignment as the previous step left it, or as sync left it?**~~
   ✅ **ANSWERED BY THE AS-BUILT — see §8** (noted 2026-08-29; this question outlived its own answer by a
   day). It sees it **as the previous step left it**: the registry is re-read per step and a fresh
   `ConsignmentReader` built from that list. Not a preference — falsified: hoisting the read out of the
   loop makes step 2 fail with `Catalog Error: Table with name mid__derived does not exist`. The
   consequence this question worried about is real and accepted: a step's view depends on execution
   order, which is why §8 records that order is **authored, never inferred**.
3. **Re-runs and idempotence.** A Consignment can be reprocessed. If step B ran over A's output and A
   re-runs, what happens to B's? (`retire_superseded` and the revision model already exist for the sync
   tier — the post-sync tier would need the same answer.)
4. **Reports/matrices — Step or Job?** `okf/backend/control-plane/job-vs-step.md` already draws this
   line; the incremental-summary tier suggests these are Consignment steps, but a cross-Consignment
   report is a Job by that concept's own rule. Worth re-reading before answering.

---

## 6. What "arbitrary tables, registered as outputs" costs

### 6.1 The registry already has the shape

`ConsignmentOutput` is:

```java
record ConsignmentOutput(String consignmentId, String runId, String tableName, String partitionKey,
                         String recordDay, String path, long rows, long bytes, String writtenAt,
                         int generation, State state, String schemaFingerprint,
                         EventTimeBounds bounds, String producer)
```

Every field the decision needs is present: **`tableName`** (so a derived table is representable at all),
**`producer`** (ownership), **`partitionKey`** (the merge key), **`generation` + `State`** (lifecycle),
**`schemaFingerprint`** (the propagated schema, §2.4). Nothing here has to change to admit a derived
table — which is the strongest argument that this decision fits the existing model rather than bending
it.

### 6.2 🔴 Retention and merge are ALREADY BUILT, not "later on"

| Wanted | Shipped |
|---|---|
| retention of replaced files | **`retire_superseded`** maintenance task — deletes the bytes of files the catalog marks `SUPERSEDED`, gated on `retention_days` (≥ 1) |
| small-file merge per partition key, on schedule | **`compact`** maintenance task — *"merge the many small per-batch Parquet output files inside each partition"*, via `PartitionCompactor` |
| the lifecycle those need | `State` = `LIVE` / `SUPERSEDED` ("replaced by a reprocess of the same Consignment") / `COMPACTED_AWAY` ("merged into a compacted file and unlinked") |
| readers not seeing retired files | `ConsignmentSelector`: `resolve(glob) = glob MINUS paths the catalog marks SUPERSEDED / COMPACTED_AWAY` |

**One shipped trade-off a derived table inherits — ✅ ACCEPTED (operator, 2026-08-29):** after
compaction, *"reprocess of a compacted-away batch is no longer supported"*. Known and fine; it is a
stated position, not an open risk. ⚠ Note the registry is deliberate about it — `supersede()` moves only
`LIVE` rows, because *"a `COMPACTED_AWAY` row must keep that state: it is the evidence that the file's
rows now live inside a merged file, which is precisely what a reprocess needs to know to take the §6.2
partition-rewrite path instead of a no-op unlink."*

### 6.2b The pre-compaction window IS the reprocess window (operator, 2026-08-29)

> *"Before compaction, reprocess should be easier, since every output is separated by consignment id."*

✅ **Correct, and the shipped reprocess is built on exactly that property.** `ReprocessCommand` (1) deletes
this Consignment's own output paths, (2) restores its backed-up members to the poll dir, (3) supersedes
the manifest **and the registry rows, keyed by Consignment**, then (4) re-polls under a fresh batch id.
Every one of those steps works because, before compaction, a Consignment's rows live in **its own files**
inside a partition — the `compact` task's own description is *"merge the many small **per-batch** Parquet
output files inside each partition"*.

⇒ **Compaction is precisely the act that destroys the 1:1 file↔Consignment property**, which is why the
reprocess then refuses rather than degrading: `deleteIfExists` no-ops on a path compaction already
unlinked while the members are restored, so re-ingest would **duplicate** the rows.

**Two consequences worth carrying into the design:**

* 🔴 **The compaction schedule is the reprocess SLA.** The operator's *"maybe 7 days old data"* is not an
  arbitrary knob — it is the length of the window in which a Consignment stays cheaply reprocessable.
  Compacting sooner buys fewer files and costs reprocessability; later, the reverse. That trade should be
  stated wherever the schedule is configured, not discovered.
* **Derived tables inherit the property and the trade.** A derived table's outputs are per-Consignment
  files registered the same way, so before compaction a derived table is reprocess-friendly for exactly
  the same reason — and compacting it trades exactly the same thing away.

⚠ **A doc/code drift found and fixed while confirming this** (`DbConsignmentOutputStore.supersede`): its
javadoc said the `COMPACTED_AWAY` state is what *"a reprocess needs to know to take the §6.2
partition-rewrite path instead of a no-op unlink"*. There is no rewrite path — reprocess **refuses**. The
javadoc now says so, because a reader would otherwise conclude a compacted Consignment is reprocessable.

### 6.3 The actual new work: a WRITE seam

`ProcessorContext` today is read-only plus `summaries()` — and that is deliberate, not accidental: a raw
`Connection` was rejected because it *"makes the read-modify-write §5.1 forbids trivially expressible"*,
and `ArtifactRecorder` was deliberately not delegated because *"two plausible ways to emit the same
thing"* is a one-concept-two-words violation.

⇒ Admitting arbitrary tables means adding **one** sanctioned writer beside `summaries()`, not opening a
`Connection`. Its contract has to settle five things the registry fields ask for:

1. **`tableName` — who names it, and in what namespace?** Two steps in two pipelines must not collide.
   The natural key is the step's own id within the Consignment, but that is a decision.
2. **`producer` — the step id**, so ownership is recorded rather than inferred. Free, since the field
   exists and the sync tier already sets it.
3. **`partitionKey` / `recordDay` — a derived table must declare its partitioning**, or `compact` has no
   key to merge on and the Selector has nothing to filter. ⚠ This is the field most likely to be left
   blank by an author and the one that silently disables both.
4. **`schemaFingerprint`** — computed from the propagated schema (§2.4, `TypeFlow`), not authored.
5. **Reprocess cascade — 🔴 already FREE, correcting an earlier claim in this document.** I wrote that
   a derivation edge was "the one genuinely new piece of state this decision introduces". That is
   **wrong**: supersede is keyed on the Consignment, not on lineage —

   ```sql
   UPDATE … SET state = 'SUPERSEDED' WHERE consignment_id = ? AND state = 'LIVE'
   ```

   A derived table registers under the **same `consignmentId`**, so a reprocess already marks it
   `SUPERSEDED` along with the base. No lineage walk, no new edge. (A second path supersedes every
   `LIVE` row of a *table* outside the keeping Consignment — the full-recompute case — and is likewise
   lineage-free.) ⚠ What the cascade does *not* do is recompute: the derivative is marked stale, and the
   chain must re-run to produce a new generation. That is the right default — stale is marked, never
   silently kept — but it means **the chain has to be re-runnable per Consignment**, which is stage 3's
   problem, not a state problem.

   ⚠ **A derivation edge would still buy something, just not this**: partial invalidation (re-run step B
   without step A) and lineage display. Neither is required by the decision, so neither should be built
   for it. Note also that the *structural* lineage edge already exists in the catalog — `EdgeKind.FEEDS`,
   *"TABLE → DERIVED_TABLE … data lineage"* — but `MetadataGraphBuilder` assembles that
   *"from configuration alone (no audit, no DuckDB)"*, so it is design-grain, not instance-grain, and
   cannot answer a per-generation question either way.

### 6.4 Revised staging

| # | Stage | Note |
|---|---|---|
| **1** | ~~decide the output contract~~ | ✅ **DECIDED**: arbitrary tables, registered as outputs |
| **2** | ✅ **SHIPPED 2026-08-29** — `DerivedTableEmitter` / `DerivedTableWriter`, registered onto the same Consignment | no new state; the reprocess cascade is proven free by test. §7 |
| **3** | ✅ **SHIPPED 2026-08-29** — an ordered chain, the registry re-read per step | §8 |
| **4** | ✅ **SHIPPED 2026-08-29** — `GET /runs/{name}/outputs` + the Batch-detail section (`aa777782`) | authoring |
| **5** | ✅ **SHIPPED 2026-08-29** — a CONTRIBUTED step is authorable via `steps:` | §11 |
| **6** | 🔴 **REFUTED 2026-08-29 — already shipped, nothing to build.** See §12 | n/a |

⛔ **Retention and merge are NOT stages** — they exist. What they need is for a derived table to arrive
in the registry with a `partitionKey` and a `State`, which is stage 2's job.

---

## 7. Stage 2 as-built (2026-08-29)

**Shipped:** `ctx.tables().emit(new DerivedTable(name, sql, partitionBy))` — a processor declares a table,
the framework materialises it after `process()` returns and **registers it onto the same Consignment**.
`DerivedTable` · `DerivedTableEmitter` · `GuardedDerivedTableEmitter` · `DerivedTableWriter`, plus
`ProcessorContext.tables()`.

**The four contract points, settled:**

* **`tableName`** — namespaced `<name>__derived`, mirroring the summary tier's `__summary`, so a derived
  table can never collide with the sync tier's own table of that name. Held to
  `[A-Za-z_][A-Za-z0-9_]{0,127}` because it becomes a **directory name** — the path jail at the seam
  where third-party text enters, the same rule `SummaryWriter` applies.
* **`producer`** — the processor id, so a row is attributable. Pinned end-to-end, not just in the writer.
* **`partitionKey`** — one file per distinct value, which is what `compact` merges on and what
  `ConsignmentSelector` prunes with. ⚠ A partition **value** also reaches a path, so it is jailed too:
  refused, never escaped.
* **`schemaFingerprint`** — `DESCRIBE` over the produced relation. Never authored; the schema of a
  derived table *is* its SQL applied to the base (§2.4).

**Three findings from building it:**

1. 🔴 **The author's SQL must clear `SqlGuard`, and a shape check is not a substitute.** A derived table
   runs on the *same unsealed sandbox* as `ConsignmentReader.query` — the relations are lazy views over
   files — so without the guard a `SELECT * FROM read_csv('/etc/passwd')` reaches the filesystem exactly
   where `query()` refuses to let it. **Falsified**: with the guard replaced by a leading-SELECT check,
   that exact statement is admitted. It is now held to the identical allow-list.
2. ⚠ **The writer takes the framework's `ConsignmentReader`, not a `Connection`.** The author's SQL names
   the Consignment's lazy views, which exist only on that sandbox — a fresh connection resolves none of
   them. `SandboxConsignmentReader` gained a **package-private** `frameworkConnection()`; the public
   interface still hands out no handle, because that is what stops a processor expressing the
   read-modify-write the append-only path forbids. ⛔ Do not widen it.
3. ⚠ **A derived table is not readable within the run that emits it.** Emissions materialise after
   `process()` returns, matching the summaries precedent. The next step sees it through the registry —
   which is the chain working as designed, not a limitation to route around.

**The reprocess cascade is proven, not assumed**: a test derives a table, calls `supersede("c1")`, and
asserts **base and derivative are both `SUPERSEDED`** — no lineage edge involved.

**Not done, deliberately:** nothing *orchestrates* a chain yet (stage 3) and the lane is still invisible
in the editor (stage 4). A processor is still one Job over one Consignment; what changed is that its
output is now a first-class table the next one can read.

---

## 8. Stage 3 as-built (2026-08-29)

**Shipped:** the `processor` parameter takes **one id or an ordered comma-separated chain** —
`mask,rollup,report`. Each step runs in authored order over the same Consignment.

**The one mechanism that makes it a chain:** the registry is **re-read per step**, and a fresh
`ConsignmentReader` is built from that list, so step N sees the Consignment as step N-1 left it —
including the tables that step registered. **Falsified**: hoisting the read out of the loop makes step 2
fail with `Catalog Error: Table with name mid__derived does not exist`, which is exactly the silent
"ordering is meaningless" failure it prevents.

**Decisions, each with its reason:**

* 🔴 **Every step is resolved before any step runs.** A chain that failed half-way on a typo would
  already have written and registered the earlier steps' tables, and nothing rolls those back — the data
  path is append-only. So an unresolvable id fails the run with *"nothing has run"*.
* **Order is authored, never inferred.** Two steps may both read the base and neither declares a
  dependency, so the written order is the only honest one.
* ⚠ **A repeated id is kept, not de-duplicated** — running the same processor twice is legal (it sees a
  different Consignment state each time), and silently dropping the second would be a surprise.
* **A failure mid-chain leaves earlier steps' output registered and `LIVE`.** That is the append-only
  posture, not an oversight: those tables were correctly derived from the state that existed when they
  ran. ⚠ It does mean a partially-complete chain is indistinguishable, from the registry alone, from a
  complete one — the Run's status is what says which it was.

**Verified end to end**: step 1 derives `mid`, step 2 finds `mid__derived` among its relations, reads its
rows, and derives from it; both tables register on the Consignment, each attributed to the step that made
it (`producer` = `first` / `second`).

---

## 9. Decided 2026-08-29 (operator)

1. **Per-step config: a `chain_config` JSON parameter, added alongside `processor` — shipped.** The
   `processor` string still names the ordered chain; `chain_config` is an optional, positionally-aligned
   JSON array of `{"config": {...}}` objects (index 0 configures the first-named step). A length mismatch
   against the chain fails the run before any step executes, same fail-closed posture as an unresolvable
   processor id. Landed as: a new `ParamType.JSON` (the vocabulary's first nested shape); a default
   `ProcessorContext.config()` (empty `Map` when the chain declared none, or when running standalone —
   backward compatible for existing third-party `ConsignmentProcessor` implementers, since it's an
   additive default method); `ConsignmentProcessJobType.chainConfigsOf` parses and validates. Pinned in
   `ConsignmentProcessJobTypeTest` (standalone default-empty, positional alignment, length-mismatch
   refusal, malformed-JSON refusal, the parser unit tests).
2. **Mid-chain failure: stays append-only — no change.** A registered table remains a fact regardless of
   what a later step in its chain does; the Run's own status is what says whether the chain finished.
   Matches the registry's existing invariant elsewhere and needed no code.

---

## 10. `read_parquet` over registered paths (operator decision, 2026-08-29)

**The question that prompted it:** *"Why does the author's SQL have to clear `SqlGuard`? Any real reason
to put restriction?"* — a fair challenge, and it corrected a claim of mine.

🔴 **`SqlGuard` here is NOT a security boundary, and saying it was overstated it.** A processor is
arbitrary Java on the engine classpath; it can open a file directly without going near SQL.
`ConsignmentReader`'s own javadoc says as much: *"invariant protection, not a defence against hostile
in-process code."*

**What it actually protects is the ADDRESSING invariant.** A `COPY … TO` in author SQL writes a file the
registry never learns about, and everything downstream keys off registered outputs — `ConsignmentSelector`'s
pruning, `retire_superseded`, `compact`, and the next step's own `outputs()`. **An unregistered file is
invisible to all of them.**

⇒ **Which is exactly why a REGISTERED path is different.** Reading one keeps the invariant intact, so the
restriction was wider than its own reason. **Decided and shipped: `read_parquet('<literal>')` is allowed
when the registry lists that path as readable.** That buys the cross-Consignment and Reference joins the
blanket ban had cost.

**How it is enforced, and why this shape:**

* `DbConsignmentOutputStore.isReadable(path)` is the authority — registered **and** carrying a `LIVE` row.
  ⚠ It follows `unreadablePaths`' rule exactly: **readability is per-PATH, row state is per-registration**.
  A full recompute rewrites a stable path in place, so a path legitimately owns an old `SUPERSEDED` row
  beside a current `LIVE` one and is readable. Pinned by its own test.
* Each `read_parquet('<literal>')` is checked against the registry, then **masked to an identifier**, and
  the masked statement goes through `SqlGuard` unchanged. So a verified read is excused and **nothing
  else is**: a second unregistered reader, a second statement, or a `COPY … TO` beside it all still fail.
* 🔴 **The pattern is narrow on purpose, and the failure direction is what makes it safe.** It matches only
  the function name, optional whitespace, one single-quoted literal, close. Anything richer — extra
  options, a non-literal argument, a different reader — does not match, stays in the text, and `SqlGuard`
  refuses it. **Fail-closed by construction: an unrecognised call is a rejected call, never an unchecked
  one.**
* `GuardedDerivedTableEmitter.ReadablePaths.NONE` is the default, so any caller that cannot ask the
  registry vouches for nothing.

⚠ **Validation runs on the masked text while the ORIGINAL executes.** That is sound only because the two
differ *solely* by substrings individually verified against the registry — which is why the verification
happens before the mask, never after.

---

## 11. Stage 5 as-built (2026-08-29) — a plugin Step is authorable

**What changed:** a **contributed** node type now lowers to a `steps:` entry, so the palette may offer it
and a graph carrying one saves. `PipelineNodeExecutor` had made such a type *executable*; this makes it
*authorable*, which is what the operator's point 4 actually asked for.

🔴 **The design premise was wrong in a useful way.** Stage 5 was written as *"open `RecipeCompiler`'s
verb switch"*. Grounding found `RecipeCompiler` has **no production caller** — a recipe is a converter
and parity artifact, not a load path. What IS load-bearing is the flat file's own `steps:` chain, which
`PipelineLift` already lifts as **`"transform." + kind` with the config verbatim**. The lift was open
all along; only the two gates around it were shut.

**The three gates, and what each became:**

* **`PipelineEditable.LOWERABLE`** — now also admits a type whose `stepKindOf` resolves, i.e. a
  registered, **non-built-in** `transform.*`. `isAuthorable` follows for free.
* **`stepsOf` / chain collection** — keyed by `stepKindOf(type)` rather than the closed `STEP_KIND` map.
  A contributed step's config travels through `stepConfig`'s existing `default` arm, verbatim.
* **The parser's closed `Step.KINDS` check** — see below.

🔴 **CONTRIBUTED only, and that restriction is the load-bearing part.** The first cut admitted every
registered `transform.*`, which would have silently made `transform.split` / `select` / `derive` /
`validate` / `merge` authorable — all deliberately absent from `LOWERABLE` and `RECIPE_VERBS`, and all
covered by `PipelineProjectionTest`'s "exactly two types differ" invariant. A built-in keeps whatever
`LOWERABLE` already says; only a type the core does not ship gains the `steps:` home. Pinned by a test
that walks all five.

⚠ **`isLegacyShaped` needed no change and that is not luck**: a contributed kind is absent from
`Step.KINDS`, so `indexOf` gives `-1` and the chain is correctly *not* legacy-shaped — the singular
blocks have one fixed key each and cannot hold a kind they have never heard of.

### The parser gate, and the guard I nearly traded away

`PipelineConfigParser` refused any kind outside `Step.KINDS`. The first cut relaxed it to a
safe-identifier shape — and **broke `PipelineConfigStepsTest.refusesAnUnknownKind`**, which was right to
fail: that trade buys plugin steps by giving up a **load-time** refusal, so a typo'd kind would load and
surface only at run.

⇒ Inverted instead. **`StepKindRegistry`** is a ServiceLoader seam declared in `inspecto-etl` and
implemented in `inspecto-engine` (`NodeTypeStepKinds` → `PipelineNodeTypes.isKnown("transform." + kind)`).
The parser still refuses at load; it just stops needing to know what a node type is. **With no provider
the answer is "no"**, so the lean core behaves exactly as before.

Both directions are pinned: `frobnicate` is still refused, and a kind a test-scope provider vouches for
loads with its config verbatim. ⚠ That fixture vouches for exactly ONE obscure kind — a permissive test
provider is global to the module and would quietly widen every other `steps:` test.

**Still open:** nothing from this thread — stage 6 was REFUTED (§12, the seam already existed) and the
two stage-3 §9 questions are now decided and shipped (chain_config landed; append-only confirmed).

---

## 12. Stage 6 REFUTED (2026-08-29) — the gap was already closed

Stage 6 was framed (§1) as *"a parser's output shape is declared in the schema `.toon`
(`raw.fields[]`), not returned by the plugin — a parser that discovers its own shape has no seam to
publish it through"*. **Grounded before building anything, and the seam already exists, is already
wired end to end for a THIRD-PARTY plugin, and predates this thread.**

* `ParserPlugin.preview()` returns a `ParseResult.Table` carrying **`columnTypes`**
  (`List<Map<String,String>>`, `{name, type}`) — a field any plugin author fills directly, with no
  dependency on DuckDB or a built-in sniff.
* `POST /parsers/{id}/preview` forwards it **unconditionally for every registered parser**, built-in or
  plugin — `ParserRoutes.preview` doesn't special-case the id, it dispatches through `Parsers.get(id)`
  and serialises whatever `ParseResult` comes back.
* `pipeline-parse-definition.component.ts`'s `onPreviewed` — the **one place a schema is ever
  derived**, per its own comment — reads `columnTypes` off *any* `ParserPreview`, narrows each type to
  the schema vocabulary, and offers it as the grid's **Auto** mode. An author accepts or overrides per
  field; nothing is silently applied.
* The offline mock mirrors the same key (`parsers.handler.ts`), so the loop is honest offline too.

⇒ **A plugin parser that wants to discover its own output shape already has the seam**: fill
`columnTypes` in `preview()`. Nothing routes, models, or authoring-side needed building. The "real gap"
in §1 was itself wrong — found by checking the consumer before writing code, the same discipline that
refuted stage 3's original premise about `RecipeCompiler`.

**The one thing that is NOT this seam, and should not be confused with it:** `TypeFlow`
(§2.4/§7) derives a **post-map** table's schema by `DESCRIBE`, without a sample and without an author
in the loop. `columnTypes` here is pre-map, sample-derived, and advisory by design — the two are
siblings serving different moments (parse-time authoring vs. run-time derivation), not one gap with two
names.