# Design — Platform Services Stage 2 (open Step-kind registry) and Stage 3 (contributed services)

**Row:** `BACKLOG.md` §3.2 *Platform Services Stage 2 / 3* (P2).
**Owner concept:** [`okf/backend/control-plane/platform-services.md`](../okf/backend/control-plane/platform-services.md).
**Status:** DESIGN ONLY, written 2026-09-24. Nothing built. Decisions owed in §7.
**Scope fence:** new Step Processors are ⛔ **ON HOLD** (operator, 2026-09-23). This document designs the
*registry and SPI* a Step kind is contributed through. It adds no processor to the catalog; the only Step
it builds is a test-scope no-op for the S2-2 spike.

Settled inputs, not reopened here:

* ✅ 2026-09-15 — an intervening node **may** execute at rest; `EXECUTED` Steps are allowed anywhere, not
  only at lane boundaries. This also discharges ELT Phase 6's `graphLaneCarries` precondition (Row 15).
* ⛔ Third-party `LOWERED` stays closed until a SQL-fragment guard exists (archived plan R2).
* Services are open by default (D6); the seam stays a flat typed lookup, never a DI framework (R3).
* Breaking changes are free on any surface — nothing after 3.x is in production.

## 1. As-is — what the code actually has (grounded 2026-09-24)

### 1.1 🔴 The row understates the as-is: an open execution seam already ships

The row says *"no `StepTypeProvider` exists in any module"*. That is true **by name**, and misleading. A
third party can contribute and run a Step kind today, from a hot-deployed pack, through two
`ServiceLoader` seams that landed 2026-08-29/31 — after the Stage 2 plan was written and archived:

| Half | Type | Registry | Where it is consumed |
|---|---|---|---|
| Descriptor | `PipelineNodeType` (`inspecto-engine/src/main/java/com/gamma/pipeline/PipelineNodeType.java:31`) | `PipelineNodeTypes` — base built at class-load (`PipelineNodeTypes.java:49-55`) plus an owner-keyed pack overlay (`:69-90`) | palette, validator, lift; lowers to a `steps:` entry via `PipelineEditable.stepKindOf` (`PipelineEditable.java:245-254`) |
| Execution | `PipelineNodeExecutor` (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/PipelineNodeExecutor.java:52`) | `PipelineNodeExecutors` (`PipelineNodeExecutors.java:31-58`) | `RowShaper.shape`, consulted **before** the built-in chain (`RowShaper.java:165-166`) |
| Pack hosting | — | `JobPackManager.load` discovers both kinds in the pack's own `URLClassLoader` (`JobPackManager.java:215-222`), registers types before executors (`:246-248`), rolls both back on any failure (`:265-270`) | — |
| Load-time gate | `StepKindRegistry` (`inspecto-etl/src/main/java/com/gamma/etl/StepKindRegistry.java:20`) | `NodeTypeStepKinds` answers "is `transform.<kind>` registered" (`NodeTypeStepKinds.java:20`) | `PipelineConfigParser.java:629` — a contributed `steps:` kind parses fail-closed |

Scaffolding exists too: `node tools/scaffold.mjs new nodetype` (template `tools/templates/nodetype/`).
`new step` still refuses, pointing at S2-3.

⇒ **Stage 2 is not a green-field registry. It is a contract change to an open seam that is already
live** — and the live seam lacks every property Stage 2 was meant to guarantee (§1.2). This is the main
finding of this document, and it changes the recommendation (§4).

### 1.2 What the live seam lacks, against the Stage 2 contract

| Stage 2 property (archived plan §5.2) | The live seam | Evidence |
|---|---|---|
| Execution **mode** (`LOWERED` / `EXECUTED`) | none — a contributed executor is neither | `PipelineNodeType` has no mode; `PipelineNodeExecutor` has one method |
| ⛔ No third-party SQL into the engine's query | 🔴 **a pack executor receives the raw batch `java.sql.Connection`** and writes its own `CREATE TABLE … AS` | `PipelineNodeExecutor.java:69` (`shape(Connection conn, …)`); the contract text says "create its own output tables on `conn`" |
| Isolation of what a Step can read | only by convention — "read nothing outside `input`"; enforced only in preview, which runs on a sealed connection | `PipelineNodeExecutor` javadoc; `ComponentPreview.java:93` (sealing is preview-only) |
| A pack may **not** redefine a built-in | 🔴 **refused for descriptors, allowed for executors** — a pack can change how `transform.filter` runs for every pipeline | `PipelineNodeTypes.register` refuses a built-in (`PipelineNodeTypes.java:71-72`); `PipelineNodeExecutors.register` has no such check and says so on purpose (`PipelineNodeExecutors.java:42-46`) |
| `requires:` grants / `services()` | none — no grant, no `PlatformServices` | `shape` takes no context carrying services |
| `dryRun()` | none — the dry-run walk calls the same `shape` (`PipelineExecutor.java:344-346`); a side-effecting executor cannot tell it is a preview | — |
| Run context (ledger, Consignment id) | dropped for contributed executors: `RowShaper` passes `references` but not `ctx` | `RowShaper.java:165-166` |
| Watchdog | none — `shape` runs inline on the walk thread | `PipelineExecutor.java:234-240` |
| Failure mapping | a thrown exception fails the batch; no reject-relation contract; output tables of a half-run node are not dropped | `PipelineNodeExecutor` javadoc `@throws` |
| Unload safety | 🔴 **a pack can unload mid-walk** — the in-flight pin (`acquireRun`/`releaseRun`) is taken only by a Job Run, never by a Pipeline walk executing that pack's node | `JobService.java:1348-1358` is the only caller; `JobPackManager.closeOrDefer` (`:299-308`) counts only those pins |

Two of those rows are **defects in shipped code**, independent of any Stage 2 design: the executor
built-in override from a pack, and the unpinned unload. §5 slice S2-0 fixes both first.

### 1.3 The closed side: vocabulary, recipe, catalog

* **`BuiltinNodeType`** is a closed enum (`BuiltinNodeType.java:30`). Each case carries a `FlatHome` claim
  and a `steps:` kind (`:229`, `:251-265`); `PipelineEditable.LOWERABLE` and `STEP_KIND` are derived from
  it (`PipelineEditable.java:187-190`, `:207-209`). Built-ins are, in the Stage 2 vocabulary, all
  `LOWERED` in effect — each compiles to SQL inside `RowShaper` — but nothing says so.
* **`RecipeCompiler`** has a closed verb `switch` (`RecipeCompiler.java:111-135`); any other verb is
  `UNSUPPORTED_STEP` (`:133-134`). A contributed kind is authorable in the graph editor and in a flat
  `steps:` list, but **has no recipe spelling at all**.
* **`ProcessorCatalog`** is a static, contract-pinned list (`ProcessorCatalog.java:60`, invariants at
  `:181-193`) of built-in processors mapped to node types. A contributed kind never appears in it; it
  reaches the palette through `PipelineNodeTypes.catalog()` instead.

### 1.4 The walk already materialises every node — "fusion" is a flat-lane property

Inside the graph lane there is no fused query to break. Every built-in shaping step is its own
`CREATE TABLE … AS SELECT` over the previous node's table — e.g. `transform.filter` writes a keep table
and a drop table (`RowShaper.java:264-265`) — and `PipelineExecutor` threads table names from node to
node (`PipelineExecutor.java:234-240`). The only fused path is the **flat lane**, and that is exactly why
the non-route admission `graphLaneCarries` refuses any node between the seed and a sink
(`ConsignmentIngestStrategy.java:484-499`; the "node BETWEEN map and sink" rationale at `:456`).

Consequence for S2-2: the "fused" comparator is the flat lane, and an `EXECUTED` Step's cost has **two**
separable parts — the per-node materialisation every graph-lane node already pays, and the
JVM round-trip only imperative code pays. The spike must separate them (§5, S2-2).

### 1.5 The model to copy for `StepContext`: `ConsignmentProcessor`

`ConsignmentProcessor` (`inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentProcessor.java:19-29`)
is two methods wide, and its `ProcessorContext` (`ProcessorContext.java:36-96`) is the narrow façade
Stage 2 wants: read-only SQL through `ConsignmentReader` — every query passes `SqlGuard`
(`ConsignmentReader.java:19`) — sanctioned emitters (`summaries()`, `tables()`), `log()`, `signals()`,
`dryRun()`, `config()`. It is **Job-hosted and post-commit**, not a walk step, so it is the shape to
copy, not the mount.

### 1.6 Stage 3 as-is

* `PlatformServiceRegistry` is built once at boot (`inspecto/src/main/java/com/gamma/service/CollectorService.java:157`,
  bindings at `:426`, `:433`) and fails closed on a duplicate id **and** interface
  (`PlatformServiceRegistry.java:28-37`); `grant(ids)` throws on an unbound id (`:49-56`). There is no
  overlay — a pack cannot add a binding.
* `DryRunServices` substitutes mutating services **by hard-coded interface** (`DryRunServices.java:41-58`).
  A contributed mutating service has no stand-in, so under today's code a dry run would call it for real
  — the MNT-1 violation `alert.evaluate` once had.
* `ConsignmentSelector` (`inspecto-engine/src/main/java/com/gamma/consignment/ConsignmentSelector.java`)
  **exists** — the pinned member list shipped 2026-08-29. The gate "`DatasetAccess` after the Consignment
  Selector" therefore reads as **met** by the class's existence; whether its pruning and generation
  pinning are what `DatasetAccess` needs is not assumed here (D-10).

### 1.7 DuckDB facts the watchdog depends on (driver 1.5.2.1)

`DuckDBPreparedStatement.cancel()` is public; `DuckDBConnection.interrupt()` is **package-private**.
So the engine can only cancel a statement it holds a handle to — another reason (§4) an `EXECUTED` Step
must issue SQL through the context, not through a raw `Connection` it could use behind the engine's back.
`registerArrowStream` is public, which keeps an Arrow bridge possible for S2-2's V3 variant.

## 2. The problem, restated

A contributed Step kind can already run in production with the batch connection in hand, no grant
declaration, no preview awareness, no timeout, the power to silently redefine a core verb, and a
classloader that can close under it. Stage 2 must turn that into a contract with a mode, a narrow
context, a services ceiling, a failure grain and a watchdog — without opening a second, parallel seam
for the same concept (GLOSSARY: one concept, one word).

## 3. Options

**A — Green-field `StepTypeProvider`, as the archived plan drew it.** A new registry beside
`PipelineNodeTypes`, with its own `StepExecutor.execute(StepContext)`.
✔ Clean contract. ✘ Two open seams for one concept; the live raw-`Connection` seam keeps shipping beside
it unless separately retired, so the guarantee is only as strong as the weaker door.

**B — Evolve the live seam into the Stage 2 contract (recommended).** Keep `PipelineNodeType` as the
descriptor and `PipelineNodeTypes` as the one registry. Add a `mode()` to the descriptor. Replace the
pack-facing execution half with `StepExecutor.execute(StepContext)`; the raw-`Connection`
`PipelineNodeExecutor` becomes **classpath-only** (edition code, reviewed and shipped with the build —
the trust the classpath already has), and a pack carrying one is rejected whole. `StepTypeProvider` is
then the *name* of the pack-facing pair, not a third registry.
✔ One seam, one vocabulary; the weaker door closes. ✘ Breaking for any existing pack executor — free by
policy, and the scaffold template is the only known consumer.

**C — Harden only.** Ship S2-0's two defect fixes and stop.
✔ Smallest. ✘ Leaves third-party SQL on the batch connection, which is the thing ⛔ R2 exists to
prevent, and leaves `new step` refusing forever.

## 4. Recommendation — Option B

1. **Mode on the descriptor.** `PipelineNodeType.mode()` → `LOWERED | EXECUTED`. Every `BuiltinNodeType`
   is `LOWERED`. A **pack**-contributed type must be `EXECUTED`; a pack declaring `LOWERED` is rejected
   whole (R2 stays closed). A classpath provider may declare either.
2. **The pack-facing execution contract.**
   `StepExecutor { String type(); void execute(StepContext ctx) throws Exception; }`
   with `StepContext`:
   * `in()` — a read-only reader over the node's input relation, through `SqlGuard`, exactly as
     `ConsignmentReader` does; the engine owns every statement handle, so the watchdog can cancel it;
   * `emit(rel)` → a row writer backed by an engine-owned appender, refused for a relation the descriptor
     does not `emits()`;
   * `attributes()`, `schema()` (the input columns), `log()`, `signals()`, `dryRun()`;
   * `services()` — the `requires:` grant, filtered and dry-run-wrapped exactly as a Job's is, under the
     **stage-2 data-path ceiling** already recorded in the owner concept (§5): a mid-walk node may not
     write Datasets or send outbound mail.
3. **Failure grain.** Any throw ⇒ the engine drops every table the node created and fails the batch
   through the existing path — never a half-applied node. Row-level rejection is expressed by emitting to
   a declared `reject:<reason>` relation, not by throwing. A timeout is a failure with its own code.
4. **Watchdog.** The engine runs `execute` on a dedicated virtual thread with a deadline (per-kind
   default on the descriptor, per-node override in attributes, a system ceiling). On expiry: cancel the
   engine-held statements, interrupt the thread, drop the node's tables, fail the batch with
   `STEP_TIMEOUT`. ⚠ A pure-Java loop that ignores interrupts cannot be killed on this JVM; the watchdog
   then fails the batch and **abandons** the thread, keeps the pack pinned while it lives, and says so in
   a CRITICAL Signal. That is the honest limit — do not claim more.
5. **Admission.** `graphLaneCarries` admits nodes between the seed and a sink (the 2026-09-15 decision),
   built-in and `EXECUTED` alike. This is the Row 15 precondition, so it lands as its own slice with the
   Phase 6 parity suite as its gate.
6. **Pinning.** Any walk that will execute a pack-owned node pins that pack for the walk's duration, with
   the same `acquireRun`/`releaseRun` counter a Job Run uses.

Stage 3 follows the existing shape: a pack-side `ServiceProvider` (id, interface, factory, **a mandatory
dry-run stand-in for a mutating service**, or a `readOnly` declaration), an owner-keyed overlay on
`PlatformServiceRegistry` in the `PipelineNodeTypes` mould, collision ⇒ the whole pack is rejected in the
same rollback block as the other four registries, and reference-tracked quiesce extended from "Runs of
this pack's types" to "Runs granted this pack's services".

## 5. Phased slices and test plan

Each slice is independently green; unit tests per change (`-pl inspecto-engine -Dtest=A,B` — commas).

| # | Slice | Verify (test first where it reproduces a defect) |
|---|---|---|
| **S2-0a** | `PipelineNodeExecutors.register` refuses a built-in type **from a pack** (classpath layer unchanged) | `JobPackManagerTest`: a real pack jar contributing an executor for `transform.filter` is rejected whole, `transform.filter` still runs the built-in; a mutant that removes the check goes red on that assertion |
| **S2-0b** | A walk pins the owning pack of every contributed node it will execute | new test: load a pack node, start a walk that blocks inside it, rescan with the jar removed — the classloader close is deferred until the walk ends; without the pin the step fails with a linkage error (the reproduction) |
| **S2-1** | `mode()` on `PipelineNodeType`; built-ins `LOWERED`; a pack `LOWERED` rejected; a mode-less contributed type refused at arming | every committed pipeline arms identically (existing suites); regenerate the node-attributes contract if `mode` is served; `NodeAttributesContractTest` green |
| **S2-2** | **Bridge spike** — measurement only, test scope, behind a system property, excluded from the default suite | see §5.1; the deliverable is a published number, not a pass/fail |
| **S2-3** | `StepExecutor` + `StepContext` + `requires:` + dry-run + failure mapping + watchdog; pack raw-`Connection` executors rejected; `scaffold.mjs new step` unlocked | a pack Step runs armed and in a dry run (and knows which); a throwing Step leaves no node tables and fails the batch; a `reject:` emit is tagged as a reject stream by `ConservationCheck`; a sleeping Step is killed at its deadline with `STEP_TIMEOUT`; an undeclared service is invisible; `ScaffoldTemplatesTest` compiles and loads the new template |
| **S2-4** | `graphLaneCarries` admits intervening nodes | `IngestLaneFlagTest`; the whole suite under `-Dingest.lane=graph` with zero refusals (the Row 15 parity gate, re-run); dedup / join / summarize between map and sink produce the same rows as today's at-rest run |
| **S2-5** | Recipe spelling for a contributed kind (D-5) and catalog visibility (D-6) | `RecipeCompilerTest` round trip; contract JSONs regenerated in the same change |
| **S3-1** | `ServiceProvider` via packs; overlay registry; collision rejects the pack; mandatory dry-run stand-in; `new service` unlocked | a service from pack A consumed by a Job in pack B; a colliding pack rejected whole with nothing left registered; a dry run of a Job using a contributed mutating service records, does not act |
| **S3-2** | Reference-tracked quiesce + enable/disable | unload while a granted Run is in flight defers until drain; disable refuses new grants, running grants finish |
| **S3-3** | `DatasetAccess` (read side first) | gated on D-10 |

### 5.1 S2-2 — the bridge spike, specified

The row's only adjacent number spans *a half to a thirteenth* of the native rate — too wide to design
against. Measure four variants over one fixture, in the same JVM, warm:

| Variant | What runs between map and the write | Isolates |
|---|---|---|
| **V0** | nothing — flat lane (today's fused path) | the baseline |
| **V1** | a built-in `transform.sql` `SELECT *` — one extra CTAS, graph lane (admission widened by the spike's flag only) | the per-node materialisation cost every graph-lane node pays |
| **V2** | a no-op `EXECUTED` Step: `in()` rows read through JDBC, written back through the appender | the JVM round-trip — the real "bridge" |
| **V3** *(optional)* | as V2 over an Arrow stream (`registerArrowStream`) | whether a columnar bridge closes the gap |

* **Fixture:** the `performance.md` shape — 2M rows at 10 and at 50 columns, mixed types — generated, not
  committed (no data files in commits).
* **Measure:** rows/s for the write stage and wall ms per node; 1 warm-up + 5 timed runs, report the
  median and the spread; record host, heap, DuckDB threads and memory limit beside the numbers.
* **Publish:** the table in the owner concept's §7 and in `okf/backend/build-run/performance.md`.
* **Decision it feeds:** D-3 (the acceptable ratio) and D-4 (whether V3 is in S2-3's scope).

## 6. Deliberately not designed here

New Step Processors (on hold) · fan-in for contributed Steps (`RowShaper.merge` stays built-in-only) ·
a Job-side watchdog (R1, recorded gap) · filtered `services()` on `ProcessorContext` (D4) and a devkit jar
(D5), both only on demand · user-instantiated configured resources (still the Connection component's).

## 7. Decisions owed (operator)

1. **D-1 — Option B over A.** Evolve the live `PipelineNodeType` + `PipelineNodeExecutor` seam into the
   Stage 2 contract rather than add a parallel `StepTypeProvider` registry. *Recommended: B.*
2. **D-2 — Close third-party raw-`Connection` execution.** A pack carrying a `PipelineNodeExecutor` is
   rejected (the classpath layer keeps it). This reads the existing seam as a breach of ⛔ R2's intent.
   *Recommended: yes, in S2-3; breaking, and free by policy.*
3. **D-3 — The bridge ratio that makes `EXECUTED` acceptable** as a default authoring choice, from S2-2's
   V2 ÷ V0. *No recommendation until measured; proposal: publish, then decide.*
4. **D-4 — Arrow bridge in S2-3 scope?** Only if V3 materially beats V2. *Recommended: decide on the
   number.*
5. **D-5 — Recipe spelling for a contributed kind:** a generic `- step: {kind: acme.score, …}` verb, or
   the kind id as its own verb. *Recommended: the generic verb — the verb set stays closed and
   compiler-total, and a pack cannot shadow a built-in verb.*
6. **D-6 — Do contributed kinds appear in `ProcessorCatalog`?** *Recommended: no — the catalog is a
   committed contract of built-ins; contributed kinds reach the palette through
   `PipelineNodeTypes.catalog()` as they do today.*
7. **D-7 — Watchdog defaults:** the per-kind default, the system ceiling, and whether an abandoned
   (uninterruptible) thread also disables the kind until the pack is replaced. *Proposal: 5 min default,
   30 min ceiling, disable-on-abandon.*
8. **D-8 — S2-0 ships now, ahead of the rest?** Both halves are defects in shipped code (a pack can
   redefine a core verb's execution; a pack can unload under a running walk). *Recommended: yes, as its
   own change.*
9. **D-9 — Cross-pack service dependencies (Stage 3).** A Job in pack B requiring a service from pack A
   is rejected today if B loads first (`rescan` loads in directory order). Options: a second pass over
   rejected packs after each load round, or declared pack dependencies. *Recommended: the retry pass —
   no new manifest vocabulary.*
10. **D-10 — Is the `DatasetAccess` gate met?** `ConsignmentSelector` exists; confirm its pruning and
    generation pinning are the ones `DatasetAccess` should inherit before S3-3 is sized.
