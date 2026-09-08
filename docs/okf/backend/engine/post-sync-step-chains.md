---
type: Concept
title: Post-sync step chains (the open DAG)
description: What runs over a Consignment AFTER sync — an authored, ordered chain of ConsignmentProcessor steps whose carrier is the Consignment output registry, whose emitted tables register back onto the same Consignment, and whose schema propagates through TypeFlow rather than being re-declared.
resource: inspecto-engine/src/main/java/com/gamma/job/ConsignmentProcessJobType.java
tags: [engine, consignment, post-sync, derived-table, processor, chain, registry, plugin-step, retention]
timestamp: 2026-09-06T00:00:00Z
---

# Post-sync step chains (the open DAG)

After sync a Consignment's data is a **table**; everything downstream of the sink reads it **through
Consignment info**, never through a piped relation. As-built after the open-DAG plan (stages 2/3/5
shipped 2026-08-29, stage 4 shipped 2026-08-31, stage 6 refuted, the remaining questions ratified
2026-09-06). Vocabulary follows [`GLOSSARY.md`](../../../GLOSSARY.md): **Pipeline**, **Consignment**,
**Step** (per-Consignment) vs **Job** (cross-Consignment, [job-vs-step](../control-plane/job-vs-step.md)),
**Collector**, **Dataset**.

## 1. The dataflow model (operator, 2026-08-29)

> *Sync contains data; Consignment information flows, with added information from the previous step(s).
> Any step can get Consignment data from Consignment info only.*

Two lanes, two contracts — do not blur them:

| Lane | Carrier | Contract | Authored as |
|---|---|---|---|
| **In-batch** (parser → filters → map → dedup → sink) | scratch relations inside the run | `PipelineNodeExecutor`, `steps:` chain | the canvas / flat TOON |
| **Post-sync** (this page) | the **Consignment output registry** | `ConsignmentProcessor` + `ProcessorContext` | a `type: consignment.process` ⚠ *(this read `consignment_process` with an underscore until 2026-09-09 — the registered id is dotted, and copying the old spelling authors an UNKNOWN job type)*`consignment.process` Job's `processor` / `chain_config` params |

⛔ **The sink stays terminal in the batch graph.** The first draft proposed `SINK_PERSISTENT` emitting
`DATA` so a step could chain after it on the canvas. Refuted: that pipes the *scratch* (pre-write,
pre-commit) relation, and the sink write is driven by the commit coordinator, so the reader would run
before anything is durable. The post-sync lane is the Consignment-addressed one, read **after commit**.

## 2. The carrier: `ProcessorContext` over the registry

`inspecto-engine/src/main/java/com/gamma/consignment/ProcessorContext.java` is the Consignment-scoped
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

### Schema propagates; it is never re-declared

`inspecto-etl/src/main/java/com/gamma/etl/TypeFlow.java` derives a Step's output schema by DuckDB
`DESCRIBE` over the *identical* SELECT it executes, without executing it — DuckDB is the type authority
and the Parquet footer carries the same types. So a derived table's schema **is** the base table's schema
after the SQL: nothing declares it, nothing can drift. The declared `raw.fields[]` belongs to the
**parser** only, where text becomes typed. ⚠ A post-sync step reads Parquet, so it is `TypeFlow`'s
*typed-source* case, not the all-VARCHAR CSV seed.

## 3. Stage 2 — derived tables (`ctx.tables().emit(...)`)

`DerivedTable(name, sql, partitionBy)` · `DerivedTableEmitter` (`@PublicApi`) ·
`GuardedDerivedTableEmitter` (validates + collects) · `DerivedTableWriter` (materialises after
`process()` returns and registers the files onto the **same Consignment**) — all under
`inspecto-engine/src/main/java/com/gamma/consignment/`.

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

## 4. Stage 3 — the ordered chain

`ConsignmentProcessJobType` (`inspecto-engine/src/main/java/com/gamma/job/`): the `processor`
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

## 5. Retention, merge and reprocess — "connect, don't invent"

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

## 6. `read_parquet` over registered paths (operator, 2026-08-29)

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

## 7. Stage 5 — a contributed in-batch Step is authorable via `steps:`

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

## 8. Stage 4 — the lane in the UI (shipped 2026-08-31)

Read side: `GET /runs/{name}/outputs?consignmentId=` and the Batch-detail registered-outputs section.
Authoring side: `inspecto-ui/src/app/modules/admin/jobs/job-chain-editor.component.ts` + `job-chain.ts`,
rendered in `job-form.dialog.html` under `@if (chainEngaged())` — a structural editor over the
`processor` / `chain_config` param pair. ⚠ The archived plan's §6.4 row still says "hand-edited TOON";
that claim is STALE as of 2026-08-31.

## 9. Decisions ratified 2026-09-06 (operator)

- **Q1** — a post-sync step's table **registers as a Consignment output**; the retire/compact/registry
  lifecycle is reused (§5).
- **Q2** — a chained step sees the Consignment **as the previous step left it** (answered by §4's as-built).
- **Q3** — when step A re-runs, B's output is **superseded through the existing revision model** (§5).
- **Q4** — a per-Consignment matrix is a **Step**; a cross-Consignment report is a **Job**
  ([job-vs-step](../control-plane/job-vs-step.md)).

## 10. Refuted and deferred

- ⛔ **Stage 6 "parser output-schema publication" — REFUTED**: `ParserPlugin.preview()` already returns
  `ParseResult.Table.columnTypes`, `POST /parsers/{id}/preview` forwards it for every registered parser,
  and the parse-definition pane's `onPreviewed` offers it as the grid's **Auto** mode. It is *pre-map,
  sample-derived, advisory*; `TypeFlow` is *post-map, derived, authoritative* — siblings, not one gap.
- ⛔ Sink emitting `DATA` (§1). ⛔ Raw `Connection` on `ProcessorContext` (§2). ⛔ Opening
  `RecipeCompiler`'s verb switch (§7 — no caller).
- Deferred: derivation edge / partial invalidation (§5); folding Map into the sink (blocked by `sinks:`
  fan-out keyed on `database`). The BACKLOG row `OPEN-DAG-S1` the plan's §5 filed was CLOSED the same
  day (2026-09-06) on grounding — stage 1 (authored chains) had already shipped as §4 + §8, so nothing is owed.

## Related

[consignment-addressing](consignment-addressing.md) (Selector, revision model) ·
[consignment-status-flow](consignment-status-flow.md) · [node-types](node-types.md) ·
[transforms-seams](transforms-seams.md) · [plugins](plugins.md) ·
[execution-lanes](../pipeline-graph/execution-lanes.md) · [step-catalog](../pipeline-graph/step-catalog.md) ·
[jobs](../control-plane/jobs.md) · [job-vs-step](../control-plane/job-vs-step.md)

**Provenance:** distilled from
[`open-dag-pipeline-design.md`](../../../archived-documents/plans-archive/open-dag-pipeline-design.md)
(design 2026-08-29, stages shipped 2026-08-29/31, Q1/Q3/Q4 ratified 2026-09-06).

## Compaction is the only operation that can duplicate rows

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

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*
