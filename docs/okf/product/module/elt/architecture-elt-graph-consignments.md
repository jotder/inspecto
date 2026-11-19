---
type: Architecture
title: ELT — architecture — The Pipeline graph, consignment status and directory layout
description: Part 3 of 3 of ELT — architecture: The Pipeline graph — the backend model; Consignment status flow (the flowfile question); Stopping a Space (graceful stop); Directory layout; References
tags: [elt, acquisition, phases, dedup, watermark, stability, ledger, multi-location, composition, pattern, module, core, composition-root, inspecto-processor, engine, architecture, stage1, multiplexer, ingest, layout, pipeline-graph, lift, node-types, derived, tokens, sinks, consignment, observability, audit, provenance, quarantine, status]
timestamp: 2026-11-18T00:00:00Z  # merged 2026-11-18 from the elt, acquire and engine architecture pages; the multi-location ingest part carried type Practice (2026-08-25)
---

# ELT — architecture — The Pipeline graph, consignment status and directory layout

Part 3 of 3 of [ELT — architecture](architecture-elt.md) · previous: [Engine code, design scope and how a file becomes Tables](architecture-elt-engine-design.md).

## E4. The Pipeline graph — the backend model

> **Scope (2026-09-01 consolidation):** this section owns the **backend Pipeline model** — the IR,
> the lift, the validator, the execution model and its decisions of record. Its siblings own the
> rest: [spec-pipeline-model.md](spec-pipeline-model.md) (the type registry + execution SPI, the token
> model, the authoring round-trip — refusal codes, parser family, settings, dry-run — and which lanes
> run a pipeline),
> [../config/spec-pipeline-config-keys.md](../config/spec-pipeline-config-keys.md) (the config-key census),
> [user-guide-elt.md](user-guide-elt.md) (the Pipeline editor UI). The plan of record is
> [`archived-documents/plans-archive/pipeline-spec.md`](../../../../archived-documents/plans-archive/pipeline-spec.md). The 2026-06 design-era
> prose (motivation, roadmap, T-checklist, capability gate) is archived verbatim at
> [`plans-archive/pipeline-graph-design-era-2026-06.md`](../../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md).

### E4.1 The model in one paragraph — and the token rule

A Pipeline is authored and stored as the flat `<name>_pipeline.toon`; the **graph is DERIVED, not
stored**. `PipelineLift` lifts the file (plus its referenced `*_schema.toon`) into the IR —
`PipelineGraph(name, active, nodes, edges)`, `PipelineNode(id, type, name, description, cfg, use,
enabled)`, `PipelineEdge(from, rel, to)` — which the validator, the projections, and the editor
consume. Saving reverses it (`PipelineEditable.lower`, [spec-pipeline-model.md](spec-pipeline-model.md)).

🔴 **Edges are wiring, not data carriers (adopted 2026-08-30, spec §13 D2).** A Step receives a
**Consignment token** and resolves data **by reference** (`ProcessorContext.outputs()`/`read()`);
no rows travel an edge, `transform.map` is never executed as a node at all, and six of the ten
declared `PipelineRel` relations never appear in a lifted graph. Documentation is written in this
token vocabulary now; the remaining runtime pieces converge with the amendment plan's Phase 7 — the
corrected model and its migration are [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.
Everything else here — IR, lift, validator, registry, commit model — stands as built.

**The three outlets (the token vocabulary, and what the constants below mean).** `data` — the token
continues (the token model wrote `main`; X5 decision D4 keeps `data`) · `reject:<reason>` — one reject kind
carrying its reason · `route:<key>` — content demux. **As built in 4.0.0 (X5-a)** — the history below explains
why; the membership of record is [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.

🔴 **The ten `PipelineRel` constants are NOT one vocabulary, and the old control/split grouping is the
conflation being removed.** They split **four and four** around the edge itself:

* `data` — the edge; the token continues (default when an edge omits `rel`).
* **Four were Signals, not edges at all** — `success` · `failure` · `on_commit` · `gap`. The first three were
  **retired in 4.0.0** (no producer; an authored edge naming one is dropped on decode with a `RETIRED_RELATION`
  warning); `gap` stays an edge to its CONTROL node.
* **Four are one reject kind differing only in reason** — `UNMATCHED` · `DROPPED` · `INVALID` · `DUPLICATE`,
  whose values are `reject:unmatched` … `reject:duplicate` since 4.0.0; `PipelineRel.canonical` reads the old
  bare spellings (authored graphs, stored provenance).
* `route:*` — operator-named content demux; survives the token model unchanged.

⛔ **`unmatched` is a reject, not a control relationship.** An earlier revision of this line grouped it
with `success`/`failure`/`gap`/`on_commit` as a five-member "control set" against a three-member
"diverted set"; that is the 5/3 misgrouping the design of record corrected to 4/4 on 2026-09-13. Do not
reintroduce it.

⚠ Two SEPARATE properties, easy to merge and wrong to: "is not a Signal" and "is not lifted".
`PipelineLift` builds only `data`, `reject:unmatched`, `gap` and `route:*` — so `reject:dropped`/`reject:invalid`/
`reject:duplicate` are real at runtime yet never drawn as edges.

### E4.2 Two graphs — keep them distinct

The **lineage graph** (`com.gamma.catalog.MetadataGraphService`) is a derived projection of configs
answering "where did this column come from". The **pipeline graph** (`com.gamma.pipeline`) is the
derived topology answering "what runs, in what order, where do outcomes go". The pipeline graph
compiles down into the lineage graph (a sink's store yields the table node), and both render
through the same G6 component with different projections. There is a third, parallel concern — the
**provenance data plane** ([E4.9](#e49-the-provenance-data-plane-shipped)): quantities painted onto the pipeline graph's edges for one concrete
run.

### E4.3 Node types, categories, and the sink family

The registry and its execution SPI are owned by [spec-pipeline-model.md](spec-pipeline-model.md):
`PipelineNodeTypes` layers `BuiltinNodeType` with `ServiceLoader<PipelineNodeType>` providers,
`PipelineNodeExecutor` is the execution half (shipped 2026-08-29), and node-type **packs**
hot-load through an owner-keyed overlay (2026-08-31). Facts that shape the model:

- Every type declares a `category` (`SOURCE`/`PARSE`/`TRANSFORM`/`SINK`/`CONTROL`), a UI
  `label`/`description`, and its `emits`/`accepts` relations (+ `emitsNamedRoutes()` for `route`).
  Grouping code asks **category, not type string** (`PipelineNodeTypes.isCategory`) — the sink and
  parser families extend without edits there.
- **Sink is a family**: `sink.persistent` (rests bytes), `sink.materialized` (per-batch upsert
  rollup), `sink.view` (nothing rests — a logical store consumers bind to; carries a
  `derived_sql`). `PipelineStores.producedStores(...).restsOnDisk()` is the persistence flag the
  **deletion fence** reads: only a resting store can be a deletion hazard; a view never
  participates. A legacy `*_pipeline.toon` only ever lifts `sink.persistent`;
  `materialized`/`view` are authored-only.
- **Parse is a family** too — `parser` plus `parser.<frontend>` subtypes; retype/lower rules live
  in [spec-pipeline-model.md](spec-pipeline-model.md) (the parser family).
- Every node carries a user `name`/`description`; lifted nodes get derived defaults (a lifted sink
  is named after its store), and `PipelineLift` deliberately does NOT stamp type labels as names.

### E4.4 The lift — what the flat file encodes (capability table, as currently true)

`PipelineLift.lift(PipelineConfig)` reads the pipeline file **and its referenced schema file(s)**
(the transform vocabulary, field selectors and partitions live in `*_schema.toon`, not the pipeline
file). Encodings of record — the 2026-06 G-table, updated to what is true today:

| Capability | Encoding today |
|---|---|
| CSV row-filters (`filter_target_column` + include/exclude) | a `transform.filter` node between parser and map — index-anchored, pre-naming. `PipelineLift` EMITS this type for any pipeline with row filters, so refusing it in `lower` would break open-then-save. |
| File-grain dedup | **both subsystems ride the `acquisition` Step** (fingerprint policy `collector.duplicate.*` since 2026-08-04; marker dedup `processing.duplicate_check` + `dirs.markers` since P5 2026-08-16, single rule statement `PipelineLift.markerHome`). ⚠ The design-era encoding (distinct `transform.dedup.marker`/`.fingerprint` nodes) is RETIRED: the fingerprint node was removed 2026-08-04 (dedup executes in the `CollectorProcessor` poll cycle, not a transform), and `transform.dedup.marker` is read-compat only — accepted, never emitted. |
| Multi-schema `schemas[]` selector | a `parser` dispatcher emitting `route:<key>` edges carrying `{priority, file_pattern, column_count}`; first-match-wins preserved; `reject:unmatched` → quarantine. |
| Plugin-ingester `segments` | `parser` + ingester binding, emitting `route:<segment-key>` per segment table; fan-out is opaque to the graph (the plugin owns keys at runtime). |
| `incremental.watermark` | `acquisition.cfg.incremental` + the cross-field warn (watermark ⇒ a content-based `duplicate.mode`). |
| `post_action` | a **success-side finalizer on `acquisition`** (there is no `failure` edge; it was retired in 4.0.0); `on_unsupported` governs the failure branch; capability-checked against the connector SPI at run time. |
| Gap detection | `acquisition` emits `gap` → a `gap` CONTROL node. |
| Pipeline ↔ enrichment/job boundary | **two Pipelines/Jobs joined by declared store name** — each sink declares the store it produces, each consumer its `source_store`, and `PipelineStores.superimpose` derives the producer→consumer topology from configs alone. Never `on_pipeline` name-coupling. |
| Dead top-level keys (`version:`/`search:`/`copy_tars:`/`backup:`) | dropped by the lift — never reproduced. |
| The transform chain | two spellings, the FILE owns which (`steps:` vs the legacy singular blocks) — [spec-pipeline-model.md](spec-pipeline-model.md) (the round-trip). |

The lift retypes a parser subtype only on an EXPLICIT `parsing.frontend` (delimited is the implicit
default and must not retype deployed files on a read) — full rules in
[spec-pipeline-model.md](spec-pipeline-model.md) (the parser family).

### E4.5 The validator

`PipelineValidator.validate(g)` returns typed findings (`Issue` records — ERROR/WARNING, stable codes);
`validateOrThrow` gates execution, and the save path 422s on ERRORs. Checks: DAG over `data` edges
(`CYCLE`, names the path; control/`route:*` edges excluded, matching the walk), dangling endpoints
(no exemption since `on_commit` was retired in 4.0.0), duplicate ids, no-entry-node,
emit/accept wiring against the type contract (`ILLEGAL_EMIT`/`ILLEGAL_ACCEPT` — handlers needn't
list every inbound outcome; the emitter governs), **pairing** (`ILLEGAL_PAIRING` — an outcome or route
edge's target must accept that relation **or** accept `data`, deliberately so that anything consuming rows
may also consume a reject or a route stream; an unregistered plugin type is *warned*, not blocked),
and `UNKNOWN_TYPE` as a **warning** (nothing
rejects an unknown type harder than that — which is why an invented palette once survived unnoticed;
the UI contract tests now pin the served vocabulary). *(The pairing rule was distilled 2026-09-10 (Sprint 7.6)
from the three archived plans; this was their only home.)*

With a registry, `validate(g, ComponentRegistry)` adds `UNKNOWN_USE_REF` (ERROR) for a dangling
`use:` of a real component kind and `UNKNOWN_USE_KIND` for a bad kind (one typo must not read as two
faults). ⚠ Skipped when the space has no write root (the registry reads empty there and every
binding would look dangling). A `transform.join` is checked the same two-layered way since 2026-09-06:
`JOIN_REFERENCE_MISSING` / `JOIN_ON_MISSING` are config shape (engine, always), `UNKNOWN_JOIN_REFERENCE`
is existence (control plane, registry-gated — resolved through `ReferenceReader.parse` + the loaded
pipelines exactly as the run resolves it; a `path:` reference is syntax-checked only). ⚠ **Save-time resolution is not run-time resolution**:
`PipelineJobRunner` never calls `effectiveConfig` — a `use:` binding is resolved for preview/save
validation, not honoured by a run. That gap is recorded, not closed.

### E4.6 Execution model — commit, branches, and where things actually run

Which lane runs a pipeline (ingest flat, ingest graph-fork, at-rest job, scratch, parked) is owned
by [spec-pipeline-execution.md](spec-pipeline-execution.md); the branch-aware ingest fork by
[spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*. Model facts:

- **The committable unit is `(batch, branch)`.** `BranchCommitLog` (durable, fsync-per-record,
  phase `BRANCH`/`SOURCE` = the partial-commit state) + `BranchCommitCoordinator`: commit
  per-branch, then source-finalisation (backup → **markers LAST** → ledger/watermark) gated on
  *all branches committed*, run exactly once, idempotent on replay. One healthy branch stays
  committed when a sibling fails; the failed branch retries/circuit-breaks independently; sink
  writes are idempotent (deterministic partition filenames). Cross-branch all-or-nothing is
  deliberately out of scope.
- **`PipelineExecutor`** (the at-rest/authored lane) validates, walks `data` edges topologically
  (Kahn), compiles transforms via `RowShaper` (multi-named-relation
  SQL: filter/validate/route case+clone/dedup/split/map/select/derive/merge — one `shape` per node in
  order; the `fuse()` chain-fusion was deleted 2026-09-06), routes each produced relation along its edge, and drives the
  coordinator at sinks. ⚠ Under the token rule ([E4.1](#e41-the-model-in-one-paragraph--and-the-token-rule)) this SQL-relation walk is the **at-rest lane's
  mechanism**, not the general edge semantics — the ingest lane diverts at `writeAndTrace`, and the
  runtime's remaining convergence on the token model lands with Phase 7.
- **Conservation invariant**: at a non-amplifying node `recordsIn = recordsOut + diverted +
  dropped`; at `route` clone / `split`, conservation is over records *accounted for* and the
  per-branch sum is a tracked amplification factor. An imbalance is silent data loss —
  `ConservationCheck` → `FLOW_CONSERVATION_IMBALANCE` event (LOSS/AMPLIFICATION) → alert.

### E4.7 Scheduling, triggers, and the two drivers

- **Entry nodes are scheduled; everything downstream is data-driven.** `PipelineTrigger` parses
  `schedule` (`every:`/`cron:`) / `event` (`on:`/`from:`/`coalesce:`) / `manual` / absent ⇒ the
  service poll interval (zero regression for legacy configs). `CollectorService.runAllOnce` gates
  each pipeline by its trigger; `EVENT` Pipelines fire from a bus subscriber off the publishing thread
  through a per-Pipeline `TriggerCoalescer` (an event storm collapses to one non-overlapping run);
  `MANUAL` runs only via the trigger endpoint. A Pipeline never overlaps itself (`PipelineRunGuard`).
- **Two drivers, one responsibility each** (the boundary rule's owner is
  [`control-plane/job-vs-step.md`](../processor/architecture-processor.md)): the **loop scheduler** drives
  pipelines and owns ingest exclusively; the **job scheduler** (`JobService`, cron/event/manual)
  drives jobs over data at rest and never ingests (`JobType.INGEST` was deleted — an `ingest` job
  is a config error). The dividing line is **in-motion (pipeline) vs at-rest (job)**, not the
  operation's name. Stage B split the loop side into two coordinated timers (acquire → inbox →
  ingest, producer/consumer across the durable inbox, per-pipeline `acquireGuard`/`runGuard`) —
  one execution model with an explicit hand-off, not two schedulers racing one inbox.
- **Deletion is the one cross-driver hazard.** `DeletionFence.check` conflicts only when a delete
  targets a **resting** store with an active producer/consumer; `CollectorService.checkDeletion`
  folds authored Pipelines and in-flight runs into the check (`STORE_DELETE_CONFLICT`). Maintenance
  jobs (the deleters) stay standalone and own this fence.
- **Misfire/catch-up**: `catch_up:` (default false) — on start, a cron job whose fire elapsed while
  down submits one immediate run; a never-run job is not force-fired. (Quartz was rejected — the
  config file is the durable schedule.)

### E4.8 Back-pressure — admission control, never queues

No inter-node queues: the durable inbox is the queue, and back-pressure is admission control.

- **`IntakeGovernor`** (per-pipeline per-cycle admission cap, oldest-first, **on by default at 500
  files/cycle** (operator, 2026-10-06: soak waived; `0` = unbounded) via
  `-Dingest.maxFilesPerCycle`, floor `-Dingest.minFilesPerCycle`, `-Dingest.backpressure.adaptive`;
  per-Pipeline TOON override `processing.intake.*`, absent block = inherit globals). The controller
  halves the cap on **cycle overrun** and doubles it back under half the interval — the 2× gap is
  the hysteresis. ⚠ **Inbox lag / pending depth are deliberately NOT throttle inputs** — throttling
  on them is positive feedback (admitting less raises both) and would pin a backlogged-but-healthy
  pipeline at the floor; they stay observability surfaces (`inspecto_inbox_oldest_seconds`).
  Exported gauge: `inspecto_intake_cap`. How it composes with the Consignment slot broker:
  [spec-consignments.md](spec-consignments.md) (concurrency).
- **Acquire high-water (B4)** (detail: *Back-pressure (B4)* in the [Data Acquisition Framework](architecture-elt-acquisition-parsing.md#data-acquisition-framework) above): acquisition de-schedules itself when the inbox backlog reaches
  `-Dacquire.backpressure.highWater` (0 = off) — throttles the *producer*, the deliberate mirror of
  the intake governor, which must never throttle the ingest *consumer* on backlog.
- Fixed ceilings: `sources.max × processing.threads × duckdb_threads`, DuckDB `memory_limit` /
  `max_temp_directory_size`, per-Collector `CircuitBreaker` + `RateLimiter`. Fan-out (`route` clone,
  `split`) charges the **amplified** volume against the batch budget; overflow chunks and branches
  run sequentially.

### E4.9 The provenance data plane (shipped)

Per-(node, relationship) row counts are collected inline by `ProvenanceCollector` during authored
runs and persisted by `DbProvenanceStore` when `-Dprovenance.backend=duckdb` is set (default off).
`GET /provenance?flow=&consignment=` + `GET /provenance/batches?flow=` feed the editor's last-run overlay
and the per-edge Sankey; the conservation invariant ([E4.6](#e46-execution-model--commit-branches-and-where-things-actually-run)) is checked over these counts. The plane
shares the pipeline graph's topology — quantities painted onto its edges.

### E4.10 Decisions of record and boundaries

Design-era decisions D1–D12 (runtime = topology over the batch engine · TOON files + `use:`
registry · route case/clone · merge = SQL over predecessors · adapter lands files (land-then-ack,
at-least-once) · entry-node triggers · admission back-pressure on cycle overrun · `(batch, branch)`
commit · in-file component identity, no version pinning · DAG over `data` edges · test = bounded
scratch dry-run · legacy auto-lift with parity gate) all stand; the full statements with rationale
are in the [archived design text](../../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md)
§9. The decisions of record taken 2026-08-31 (D1–D10) are in
[the ELT plan](plan-elt.md) § *Decisions*, and the
token model itself is [spec-pipeline-model.md](spec-pipeline-model.md) § *The token model*.

Boundaries still true (v1 non-goals, archived §12 for the full table): no decoupled per-node
scheduling or inter-node queues; no live × live keyed join; adapter ingestion is at-least-once; no
component version pinning; transforms must be SQL-expressible (imperative logic = the plugin SPI);
DAGs only; per-Pipeline trigger granularity; no cross-branch transactional commit; multi-tenant RBAC is
an edition concern.

Review risks R1–R6 (2026-06-16): all resolved — R1 (row-shaping machinery) built as `RowShaper`;
R2 (`(batch, branch)` commit) built as `BranchCommitLog`/`Coordinator`; R3 (branch executor)
**CLOSED 2026-08-26**, armed on the ingest path at the `writeAndTrace` choke point
([spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*); R4 (fan-out lift) built; R5
(`on_commit` cross-Pipeline rule) enforced until the relation was retired in 4.0.0 (X5-a); R6 (`ingest` job type) deleted.

### E4.11 Route branches and arming (pointer)

A `route:` branch is `{key, where, database}` — `key` and `database` are DERIVED (edge name; the
sink the edge feeds, stamped as the branch↔sink join key), `where` is authored. `RouteArming`
refuses an armed branch with no `where` (WARNING while `active: false`, 422 once active; the
`default:` branch is not exempt). ⛔ `branches` must never get an `AttributeSpec` — a specced key is
form-owned and would destroy the derived pair. Full account:
[spec-pipeline-model.md](spec-pipeline-model.md) (route branches); runtime fork:
[spec-ingest.md](spec-ingest.md) § *Branch-aware ingest*.

---

## E5. Consignment status flow (the flowfile question)

"Where is this Consignment, and what happened to it?" — NiFi's flowfile question, answered by four
separate mechanisms rather than one attribute bag. As-built after the consignment-chain plan
(shipped 2026-08-12/13; provenance from S3, the live gauge from S7, the audit holes closed after).
Vocabulary: **Consignment** in code too since the Phase-7 rename shipped (`ff33246a` —
`ConsignmentAuditWriter`, `ConsignmentManifest`, `ConsignmentEvent`, …); ⚠ wire and persisted
spellings deliberately kept `batch_id`/`batches` (ledger columns via read-alias, `GET /runs/{n}/batches`).
The persisted trio (the DB columns, the `__batch_id` reference column, the `batch_id` job key) became
`consignment_id` / `__consignment_id` in 4.0.0 (D-12, 2026-11-17), with legacy reads kept.

### Per lane

The per-lane identity/status table lives in
[spec-pipeline-execution.md](spec-pipeline-execution.md) — this section owns the recording mechanisms
below (gauges, ledgers, quarantine, drill-down).

All provenance-writing lanes share the same `inspecto_pipeline_provenance` matrix, through **one**
`DbProvenanceStore` shared via the per-space `ProvenanceStores` registry — DuckDB is single-writer, so
two independently-opened stores would contend. Default-off (`-Dprovenance.backend=duckdb`); absent, the
editor's per-edge weights 404 and recording is a map lookup.

### Live gauges are in-memory by decision, not by omission

`IngestProgress` (which file) and `StepProgress` (which step) are process-local
`ConcurrentHashMap`s, cleared in a `finally` so a snapshot never outlives its batch/run — success or
failure alike. Both are served by the existing poll route (`InboxStatus.current` / `.step`,
`RunRoutes`). Two alternatives were **refused** with reasons that still hold:

- ⛔ **Per-step Signals.** A Signal is a durable ledger write, and the one existing emission is already
  synchronous *inside the pipeline's run claim* (`ConsignmentAuditWriter.flush` → `PipelineConsignmentSignal`).
  Per-step would multiply durable writes on the claim-holding thread — and because Signals trigger
  `on_signal` jobs, a mid-batch Signal invites triggered work to fire **while the claim is held**: the
  re-entrancy class [PROJECT_NOTES §6](../../../../PROJECT_NOTES.md) forbids. Revisit only at
  per-Consignment grain with a mandatory off-bus hand-off.
- ⛔ **Periodic persistence of progress.** Stale by construction, recurring writes for a value that
  expires in seconds, and crash-orphaned rows to clean. The post-crash question is already answered by
  the ledgers.

A wedged-but-alive step therefore reads as a **stale `startedAt` on a still-present snapshot** — that
is the intended hang diagnostic. The EL lane tracks `parse`→`transform`→`sink` on the Java per-member
path only: the native streaming paths fuse those stages into one pass, so they are deliberately
untracked rather than reported falsely.

### Auditing a failed file or record

**Files.** A bad input is moved to `<quarantine>/<poll-subpath>/<reason>/<file>` (reasons:
`field_mismatch`, `unreadable`, `empty`, `corrupt_download`, and since X1 `retry_exhausted` — a file whose
Consignment failed at COMMIT `-Dingest.retry.max` times; see the bounded-retry note in
[operations-reference](../../layer/platform/admin-guide-operations-reference.md)) — the tree itself is evidence, organised by
why. The status ledger carries a row per member file (`filename, status, parsed_rows, error_rows,
error, consignment_id`), the batches ledger the per-Consignment roll-up, the lineage ledger the
output↔input join. UI: **Run Detail** tabs (Batches / Files / Lineage / Quarantine / Commits), drill-in
by `consignment_id`, and Reprocess by batch id.

**Records.** Two distinct failure kinds, both now auditable:
1. **Parse rejects** — offending lines are written to a companion `errors/<base>_errors.csv` which
   travels *into quarantine beside its input file*. `GET /runs/{name}/errors?file=<bare name>` serves
   that content (bounded, `truncated` reports the true total), surfaced as "View the rejected rows" on
   Files rows with `error_rows > 0` and on Quarantine rows.
2. **Coercion failures** — a failed `TRY_CAST`/`TRY_STRPTIME` yields NULL and **keeps the row**.
   `DataTransformer.countCastFailures` measures these over the *same compiled expressions* the
   transform uses (never a re-derivation) and reports `cast_failures` on the batches ledger.
   ⚠ **`-1` means NOT MEASURED and writes a BLANK cell** — a path that cannot measure must never claim
   a clean batch. `EXPR` mapping rules are excluded by design: author-owned SQL has no defined
   "non-blank source".

**Job-lane rejects are ONE kind carrying a reason** — `reject:<reason>`, where the reason names which
operator diverted the rows (`filter`→`dropped`, `validate`→`invalid`, `dedup`→`duplicate`,
`merge`→`unmatched`). ⚠ They are four *reasons*, not four relations; since 4.0.0 (X5-a) the
constants carry the `reject:<reason>` spelling, and the old bare spellings are read as aliases. Rejects are scratch unless the author **wires a sink to the reject
outlet** — the designed pattern ("the user never wires these, only tunes where they rest").
`ConservationCheck` alerts on an unexplained in/out imbalance either way.

#### Rejects are counted in TWO columns, files and rows (D2 — AS-BUILT 2026-09-22)

✅ **SHIPPED 2026-09-22 (`WB-09`).** The batches ledger header is
`…,member_count,rejected_files,rejected_rows,total_input_rows,…`, and `total_input_rows` counts what
ARRIVED. ⚠ **A wholly-rejected member counts in `rejected_files` and NOT in `rejected_rows`** — its rows
were never parsed, so counting them as rows would double-count the file as rows it never had.

🔴 **The header has SEVEN construction/mirror sites, not the four the plan listed.** Beyond
`ConsignmentAuditWriter`'s header string, its `batchLine` codec, `ConsignmentRow` and
`OperationalTables.BATCHES` (the agent's SQL surface, which declares the header explicitly), the
compiler found `DrainCommand` and four test fixtures across three
modules. ✅ That is the case for the typed record over the string header: **a stale string mirror
mis-reads silently; a stale constructor does not compile.** Readers parse **by header name per file**
(`Csv.readInto`), so *appending* a column never breaks old ledger files; a stale mirror silently hides it.

⚠ **`error_rate` changed meaning, deliberately.** The alert measure is
`1 - sum(total_output_rows)/sum(total_input_rows)`; with the old input count excluding dropped rows, a
file that lost a record reported **0%**. It now reports the real rate, so thresholds tuned against the
old always-reconciling numerator may fire where they used to stay quiet. ✅ The alert measure was
already NAMED `rejected_files` while reading a column called `rejected_count`; the rename ends that
mismatch too.

Pinned by `ConsignmentAuditWriterTest.theBatchLedgerCountsRejectedFilesAndRejectedRowsSeparately`,
which asserts the two columns by POSITION as well as by name — readers parse by header name, so a codec
emitting them in the wrong order would swap files and rows with every column still present.

*(The decision, for provenance:)* `rejected_count` is **split, not redefined** — `rejected_files`
(today's value: members not `SUCCESS`) **and** `rejected_rows` (the sum of `IngestResult.errorRows`), with
`total_input_rows` = parsed + rejected rows so the ledger reconciles to the file on disk. Renaming rather
than overloading is what the ledger needs: one number that meant both was the ambiguity. ✅ Breaking the
header is free — nothing after 3.x shipped.

🔴 **Why:** measured 2026-09-22 on a 20-data-row file carrying one row truncated mid-write to 9 of 18
fields, the batch row read `status=SUCCESS · rejected_count=0 · total_input_rows=19 ·
total_output_rows=19` — **the counters reconcile (19 = 19) while a record is missing.** The drop is
recorded only in `<dirs.errors>/<file>_errors.csv`, which nothing in the audit row points at. An operator
reconciling output against the store by these counters concludes nothing was lost.

⚠ The invariant the split buys: `total_input_rows = total_output_rows + rejected_rows` when no Step drops
rows; a Step that drops rows is the other, already-honest half (see
[`spec-step-catalog.md`](spec-step-catalog.md)'s counted reject relation). Tracked as `WB-09` in
`archived-documents/plans-archive/workbench-trust-plan.md`; row `INGEST-REJECT-ACCOUNTING-1`.

### Intrinsic and inherited status must not collapse into one enum

A file's own outcome and the outcome of the Consignment containing it are different facts, and both are
needed: a `COMMITTED` file inside a `FAILED` Consignment is a real and common state (the file landed; the
Consignment failed later, on another file or at commit). Folding them into a single status loses the ability
to answer "which files actually made it?" after a partial failure — which is exactly the question reprocessing
asks.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

---

## E6. Stopping a Space (graceful stop)

`SpaceManager.close()` closes each Space under a 10 s budget (`CLOSE_DEADLINE_MS`). `CollectorService.close()`
first asks in-flight runs to stop, then drains them (Jobs, timers, trigger workers, enrichment, the notification
queue), and closes the stores last. A busy Space can overrun the budget; its drain is then abandoned on a daemon
thread. **Since `DEMO-BAKE-WAL-RACE-1` (2026-11-12) an abandoned close still closes the Space's stores**
(`CollectorService.closeStoresNow()`, which also closes the Job run ledger and provenance store through
`JobService.closeStores()`), under a second budget (5 s since `JOB-OUTLIVES-CLOSE-1`, below). A closing DuckDB store checkpoints, so no `.wal` is left;
work still running then fails on a closed store, which is the stop it was already asked to make. Each store
serialises its one connection (`SingleConnectionSource`), so a store close waits for the statement in flight and
never cuts it; only a store still busy after the second budget keeps its `.wal`, which DuckDB replays at the next
start. The store close runs once, whichever path reaches it first.

**Since `JOB-OUTLIVES-CLOSE-1` (2026-11-15) an overrun drain is escalated before it is abandoned.** A Job Run that
did not stop for the cooperative cancel (the claim's `cancelled()`, read at `CommitFence.checkpoint`) is interrupted:
`SpaceManager` calls `CollectorService.interruptRuns()` → `JobService.interruptRuns()` (`shutdownNow()` on the Run
executor; the Space is marked closing, so an interrupted Run is recorded `CANCELLED`, a Run still waiting for a run
slot is recorded `SKIPPED` "the Space is shutting down", and a Run spends the interrupt before it writes its own
records). The drain then gets up to 5 s more (`STOP_GRACE_MS`) to finish, its own
store close included; only a drain still busy after that is abandoned, and `closeStoresNow()` gets what is left of
the second budget, 5 s. The worst case per Space stays two budgets (20 s). ⚠ Trade-off: the flush budget is halved on
every overrun, also when the drain is stuck outside any Run (an agent or a notification queue that will not close),
where the grace cannot help and only shortens the flush; a store close waits only for its statement in flight, so 5 s
is normally ample. Before this a Run still writing its Dataset
went on writing after `SpaceManager.close()` had returned — on a loaded machine `ControlApiSpaceTemplateSeedGateTest`
could not delete `t-la-utility/data/transformer_loss`, and its log showed 2–3 Spaces abandoned per run even when it
passed. `sql.template` now checks the cancel before each write under the data root (before it creates the sink,
after its query, before each Decision Rule route and before the snapshot swap), so a cancelled Run reveals no
snapshot. ⚠ Not covered: a Run inside a native DuckDB statement stops only when that statement returns (an interrupt
does not cancel it); such a Run is still abandoned, as before, with the WARN. ⚠ Known limit: an interrupt that lands while a Run emits an
event closes the event journal's `FileChannel` until its next rotation, exactly as a Run deadline expiry
(`JobDeadline`) already can; the events appended until then are buffered only ("survives only a clean shutdown",
`ParquetEventStore`), not crash-durable. Tests: `SpaceCloseStopsRunsTest`,
`JobCloseStopTest`.

Decision: close the stores at the deadline rather than extend the budget while Jobs finish. A container runtime
stops a process after its own grace period (10 s for `docker stop`, 30 s for a Kubernetes pod) and Spaces close one
after another, so a longer budget would not hold; a checkpoint alone would not either, because work that is still
writing would add to the WAL again before the JVM exits. Test: `SpaceCloseFlushTest`.

How a Pipeline run and a Job Run react to the cooperative cancel (`RunCancelledException`, `CANCELLED`
outcomes, lease hand-over) is [spec-ingest.md](spec-ingest.md) § *Failover and graceful stop*.

---

## E7. Directory layout

Since multi-space, everything an installation owns lives under a **Space** directory
(`-Dspaces.root`, default `./spaces`); single-space installs use the `default` Space. Legacy flat
layouts are migrated once via `com.gamma.service.SpaceMigrator` — there is no flat fallback.

```
sandbox-root/                       ← working directory for local runs (the JVM CWD)
  packs/                            ← host-wide Job Packs (job-framework §12; -Djobs.packs.dir default)
  spaces/                           ← -Dspaces.root; one directory per Space
    _shared/                        ← THE EXCHANGE (cross-Space sharing) — reserved, not a Space
    <space-id>/
      space.toon                    ← Space manifest
      config/<name>/                ← *_gen.toon, *_schema.toon, *_pipeline.toon (+ grammars, jobs, alerts)
        share-grants/               ← owner-side Share Grants (cross-Space dataset/widget sharing)
      data/…                        ← the per-pipeline dirs.* trees: inbox, partitioned Parquet
                                      output (Tables), backup, errors, quarantine, markers,
                                      status, temp scratch, logs
      audit/                        ← batch/file audit ledgers
      duckdb/                       ← per-Space DuckDB state
      flows/                        ← authored Pipeline definitions (storage dir name is historical)
      views/                        ← ViewDefinitions (T32-C)
  warehouse_setup.sql               ← pg_duckdb warehouse schema, views (run once on server)
```

**Layout contract (§L0).** The four persistence axes — `config/` (authored definitions), `data/` (the
data plane), `audit/` (ledgers) and `duckdb/` (embedded-DB state) — must never mix. This is checked at
Space boot by `com.gamma.service.SpaceLayoutContract` and is **advisory**: every departure emits a
`LAYOUT_CONTRACT_VIOLATION` WARN event, never a boot failure (matching the warning-and-skipping posture
of `SpaceManager.discover`). Every new subsystem must declare its home here before landing:

| Subsystem | Home | Axis |
|---|---|---|
| Job run logs | `audit/` | audit |
| Job Run Artifacts DB | `duckdb/` | duckdb |
| Job Packs (host-wide) | `<install-root>/packs/` | (install scope) |
| Share Grants (owner-side) | `config/share-grants/` | config |
| The Exchange (cross-Space) | `spaces/_shared/` | (install scope, reserved) |
| ViewDefinitions | `views/` | config-adjacent |

Contract rules enforced by the check: canonical subdirs (`data/ audit/ duckdb/`) must exist; no
embedded-DB file (`*.duckdb`/`*.db`/`*.wal`) may sit outside `duckdb/`; no unrecognised top-level entry
may appear at the Space root. `_shared/` under `-Dspaces.root` is **not** a Space (discovery admits only
dirs with a `config/` subtree, so it is skipped) — see
[storage-layout-and-sharing-plan](../../../../archived-documents/plans-archive/storage-layout-and-sharing-plan.md) §2.

⚠️ **A relative `dirs.*` path resolves under the Space directory, not the JVM working directory** —
write it Space-relative (`data/<pipeline>/database`); a config ref sits beside its config
(`DATA-DIRS-RESOLVE-AGAINST-CWD-1` / `SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`, 2026-09-23 — see
[config safety](../config/spec-config-safety.md)). `SpaceMigrator` still cannot rewrite absolute paths.

---

---

## References

- [spec-acquisition.md](spec-acquisition.md) — the acquisition mechanism: the connector SPI, the resolvable schemes, the phases; the Unpack stage is [spec-acquisition-unpack.md](spec-acquisition-unpack.md)
- [spec-ingest.md](spec-ingest.md) — the ingest SPI, Consignment coordination, failover, branch-aware ingest
- [spec-consignments.md](spec-consignments.md) — Consignment addressing (Selector, revisions, Watermark) and concurrency
- [spec-parsing.md](spec-parsing.md) — parse mechanisms, the `parsing:` block, parser plugins
- [spec-pipeline-model.md](spec-pipeline-model.md) — node types, the token model, the authoring round-trip
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — execution lanes, live execution, test runs
- [spec-sinks.md](spec-sinks.md) — output sinks and quarantine
- [spec-step-catalog.md](spec-step-catalog.md) — every Step kind's keys, refusals and a runnable example
- [spec-duckdb.md](../engine/spec-duckdb.md) · [spec-db-layer.md](../engine/spec-db-layer.md)
- [events-metrics](../event/spec-events-metrics.md) · [jobs](../processor/spec-jobs.md)
- [layer/platform architecture](../../layer/platform/architecture-platform.md) — the platform-wide layer model
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment* / §6-B
