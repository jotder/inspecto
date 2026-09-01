# Pipeline Graph — the backend model

> **Scope (2026-09-01 consolidation):** this document owns the **backend Pipeline model** — the IR,
> the lift, the validator, the execution model and its decisions of record. Its siblings own the
> rest: [editable-round-trip.md](editable-round-trip.md) (the authoring round-trip, refusal codes,
> parser family, settings, dry-run — the former §16–§20 of this file),
> [execution-lanes.md](execution-lanes.md) (which lanes run a pipeline),
> [pipeline-config-keys.md](pipeline-config-keys.md) (the config-key census),
> [`engine/node-types.md`](../engine/node-types.md) (the type registry + execution SPI),
> [pipeline-editor.md](../../frontend/features/pipeline-editor.md) (the UI). The active plan is
> [`superpower/pipeline-spec.md`](../../../superpower/pipeline-spec.md). The 2026-06 design-era
> prose (motivation, roadmap, T-checklist, capability gate) is archived verbatim at
> [`plans-archive/pipeline-graph-design-era-2026-06.md`](../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md).

## 1. The model in one paragraph — and the token rule

A Pipeline is authored and stored as the flat `<name>_pipeline.toon`; the **graph is DERIVED, not
stored**. `PipelineLift` lifts the file (plus its referenced `*_schema.toon`) into the IR —
`PipelineGraph(name, active, nodes, edges)`, `PipelineNode(id, type, name, description, cfg, use,
enabled)`, `PipelineEdge(from, rel, to)` — which the validator, the projections, and the editor
consume. Saving reverses it (`PipelineEditable.lower`, [editable-round-trip.md](editable-round-trip.md)).

🔴 **Edges are wiring, not data carriers (adopted 2026-08-30, spec §13 D2).** A Step receives a
**Consignment token** and resolves data **by reference** (`ProcessorContext.outputs()`/`read()`);
no rows travel an edge, `transform.map` is never executed as a node at all, and six of the ten
declared `PipelineRel` relations never appear in a lifted graph. Documentation is written in this
token vocabulary now; the remaining runtime pieces converge with the amendment plan's Phase 7 —
the corrected model and migration are
[`superpower/pipeline-spec.md`](../../../superpower/pipeline-spec.md) §11. Everything else here —
IR, lift, validator, registry, commit model — stands as built.

`PipelineRel` constants: `data` (default), the control set `success`/`failure`/`unmatched`/`gap`/
`on_commit` (cross-flow ONLY — a same-graph `on_commit` is rejected, since the data-edge-only DAG
check cannot see the cycle it would create), the diverted set `dropped`/`invalid`/`duplicate`, and
operator-named `route:*`.

## 2. Two graphs — keep them distinct

The **lineage graph** (`com.gamma.catalog.MetadataGraphService`) is a derived projection of configs
answering "where did this column come from". The **pipeline graph** (`com.gamma.pipeline`) is the
derived topology answering "what runs, in what order, where do outcomes go". The pipeline graph
compiles down into the lineage graph (a sink's store yields the table node), and both render
through the same G6 component with different projections. There is a third, parallel concern — the
**provenance data plane** (§8): quantities painted onto the pipeline graph's edges for one concrete
run.

## 3. Node types, categories, and the sink family

The registry and its execution SPI are owned by [`engine/node-types.md`](../engine/node-types.md):
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
  in [editable-round-trip.md](editable-round-trip.md) §20.
- Every node carries a user `name`/`description`; lifted nodes get derived defaults (a lifted sink
  is named after its store), and `PipelineLift` deliberately does NOT stamp type labels as names.

## 4. The lift — what the flat file encodes (capability table, as currently true)

`PipelineLift.lift(PipelineConfig)` reads the pipeline file **and its referenced schema file(s)**
(the transform vocabulary, field selectors and partitions live in `*_schema.toon`, not the pipeline
file). Encodings of record — the 2026-06 G-table, updated to what is true today:

| Capability | Encoding today |
|---|---|
| CSV row-filters (`filter_target_column` + include/exclude) | a `transform.filter` node between parser and map — index-anchored, pre-naming. `PipelineLift` EMITS this type for any pipeline with row filters, so refusing it in `lower` would break open-then-save. |
| File-grain dedup | **both subsystems ride the `acquisition` Step** (fingerprint policy `collector.duplicate.*` since 2026-08-04; marker dedup `processing.duplicate_check` + `dirs.markers` since P5 2026-08-16, single rule statement `PipelineLift.markerHome`). ⚠ The design-era encoding (distinct `transform.dedup.marker`/`.fingerprint` nodes) is RETIRED: the fingerprint node was removed 2026-08-04 (dedup executes in the `CollectorProcessor` poll cycle, not a transform), and `transform.dedup.marker` is read-compat only — accepted, never emitted. |
| Multi-schema `schemas[]` selector | a `parser` dispatcher emitting `route:<key>` edges carrying `{priority, file_pattern, column_count}`; first-match-wins preserved; `unmatched` → quarantine. |
| Plugin-ingester `segments` | `parser` + ingester binding, emitting `route:<segment-key>` per segment table; fan-out is opaque to the graph (the plugin owns keys at runtime). |
| `incremental.watermark` | `acquisition.cfg.incremental` + the cross-field warn (watermark ⇒ a content-based `duplicate.mode`). |
| `post_action` | a **success-side finalizer on `acquisition`** (never the `failure` edge); `on_unsupported` governs the failure branch; capability-checked against the connector SPI at run time. |
| Gap detection | `acquisition` emits `gap` → a `gap` CONTROL node. |
| Pipeline ↔ enrichment/job boundary | **two flows joined by declared store name** — each sink declares the store it produces, each consumer its `source_store`, and `PipelineStores.superimpose` derives the producer→consumer topology from configs alone. Never `on_pipeline` name-coupling. |
| Dead top-level keys (`version:`/`search:`/`copy_tars:`/`backup:`) | dropped by the lift — never reproduced. |
| The transform chain | two spellings, the FILE owns which (`steps:` vs the legacy singular blocks) — [editable-round-trip.md](editable-round-trip.md) §16. |

The lift retypes a parser subtype only on an EXPLICIT `parsing.frontend` (delimited is the implicit
default and must not retype deployed files on a read) — full rules in
[editable-round-trip.md](editable-round-trip.md) §20.

## 5. The validator

`PipelineValidator.validate(g)` returns typed `Issue`s (ERROR/WARNING, stable codes);
`validateOrThrow` gates execution, and the save path 422s on ERRORs. Checks: DAG over `data` edges
(`CYCLE`, names the path; control/`route:*` edges excluded, matching the walk), dangling endpoints
(`on_commit` `to` exempt — cross-flow), duplicate ids, no-entry-node, `ON_COMMIT_SAME_GRAPH`,
emit/accept wiring against the type contract (`ILLEGAL_EMIT`/`ILLEGAL_ACCEPT` — handlers needn't
list every inbound outcome; the emitter governs), and `UNKNOWN_TYPE` as a **warning** (nothing
rejects an unknown type harder than that — which is why an invented palette once survived unnoticed;
the UI contract tests now pin the served vocabulary).

With a registry, `validate(g, ComponentRegistry)` adds `UNKNOWN_USE_REF` (ERROR) for a dangling
`use:` of a real component kind and `UNKNOWN_USE_KIND` for a bad kind (one typo must not read as two
faults). ⚠ Skipped when the space has no write root (the registry reads empty there and every
binding would look dangling). ⚠ **Save-time resolution is not run-time resolution**:
`PipelineJobRunner` never calls `effectiveConfig` — a `use:` binding is resolved for preview/save
validation, not honoured by a run. That gap is recorded, not closed.

## 6. Execution model — commit, branches, and where things actually run

Which lane runs a pipeline (ingest flat, ingest graph-fork, at-rest job, scratch, parked) is owned
by [execution-lanes.md](execution-lanes.md); the branch-aware ingest fork by
[`engine/branch-aware-ingest.md`](../engine/branch-aware-ingest.md). Model facts:

- **The committable unit is `(batch, branch)`.** `BranchCommitLog` (durable, fsync-per-record,
  phase `BRANCH`/`SOURCE` = the partial-commit state) + `BranchCommitCoordinator`: commit
  per-branch, then source-finalisation (backup → **markers LAST** → ledger/watermark) gated on
  *all branches committed*, run exactly once, idempotent on replay. One healthy branch stays
  committed when a sibling fails; the failed branch retries/circuit-breaks independently; sink
  writes are idempotent (deterministic partition filenames). Cross-branch all-or-nothing is
  deliberately out of scope.
- **`PipelineExecutor`** (the at-rest/authored lane) validates, walks `data` edges topologically
  (Kahn; cross-flow `on_commit` excluded), compiles transforms via `RowShaper` (multi-named-relation
  SQL: filter/validate/route case+clone/dedup/split/map/select/derive/merge, with `fuse()`
  chain-fusion of linear runs), routes each produced relation along its edge, and drives the
  coordinator at sinks. ⚠ Under the token rule (§1) this SQL-relation walk is the **at-rest lane's
  mechanism**, not the general edge semantics — the ingest lane diverts at `writeAndTrace`, and the
  runtime's remaining convergence on the token model lands with Phase 7.
- **Conservation invariant**: at a non-amplifying node `recordsIn = recordsOut + diverted +
  dropped`; at `route` clone / `split`, conservation is over records *accounted for* and the
  per-branch sum is a tracked amplification factor. An imbalance is silent data loss —
  `ConservationCheck` → `FLOW_CONSERVATION_IMBALANCE` event (LOSS/AMPLIFICATION) → alert.

## 7. Scheduling, triggers, and the two drivers

- **Entry nodes are scheduled; everything downstream is data-driven.** `PipelineTrigger` parses
  `schedule` (`every:`/`cron:`) / `event` (`on:`/`from:`/`coalesce:`) / `manual` / absent ⇒ the
  service poll interval (zero regression for legacy configs). `CollectorService.runAllOnce` gates
  each pipeline by its trigger; `EVENT` flows fire from a bus subscriber off the publishing thread
  through a per-flow `TriggerCoalescer` (an event storm collapses to one non-overlapping run);
  `MANUAL` runs only via the trigger endpoint. A flow never overlaps itself (`PipelineRunGuard`).
- **Two drivers, one responsibility each** (the boundary rule's owner is
  [`control-plane/job-vs-step.md`](../control-plane/job-vs-step.md)): the **loop scheduler** drives
  pipelines and owns ingest exclusively; the **job scheduler** (`JobService`, cron/event/manual)
  drives jobs over data at rest and never ingests (`JobType.INGEST` was deleted — an `ingest` job
  is a config error). The dividing line is **in-motion (pipeline) vs at-rest (job)**, not the
  operation's name. Stage B split the loop side into two coordinated timers (acquire → inbox →
  ingest, producer/consumer across the durable inbox, per-pipeline `acquireGuard`/`runGuard`) —
  one execution model with an explicit hand-off, not two schedulers racing one inbox.
- **Deletion is the one cross-driver hazard.** `DeletionFence.check` conflicts only when a delete
  targets a **resting** store with an active producer/consumer; `CollectorService.checkDeletion`
  folds authored flows and in-flight runs into the check (`STORE_DELETE_CONFLICT`). Maintenance
  jobs (the deleters) stay standalone and own this fence.
- **Misfire/catch-up**: `catch_up:` (default false) — on start, a cron job whose fire elapsed while
  down submits one immediate run; a never-run job is not force-fired. (Quartz was rejected — the
  config file is the durable schedule.)

## 8. Back-pressure — admission control, never queues

No inter-node queues: the durable inbox is the queue, and back-pressure is admission control.

- **`IntakeGovernor`** (per-pipeline per-cycle admission cap, oldest-first, opt-in via
  `-Dingest.maxFilesPerCycle`, floor `-Dingest.minFilesPerCycle`, `-Dingest.backpressure.adaptive`;
  per-flow TOON override `processing.intake.*`, absent block = inherit globals). The controller
  halves the cap on **cycle overrun** and doubles it back under half the interval — the 2× gap is
  the hysteresis. ⚠ **Inbox lag / pending depth are deliberately NOT throttle inputs** — throttling
  on them is positive feedback (admitting less raises both) and would pin a backlogged-but-healthy
  pipeline at the floor; they stay observability surfaces (`inspecto_inbox_oldest_seconds`).
  Exported gauge: `inspecto_intake_cap`.
- **Acquire high-water (B4)**: acquisition de-schedules itself when the inbox backlog reaches
  `-Dacquire.backpressure.highWater` (0 = off) — throttles the *producer*, the deliberate mirror of
  the intake governor, which must never throttle the ingest *consumer* on backlog.
- Fixed ceilings: `sources.max × processing.threads × duckdb_threads`, DuckDB `memory_limit` /
  `max_temp_directory_size`, per-source `CircuitBreaker` + `RateLimiter`. Fan-out (`route` clone,
  `split`) charges the **amplified** volume against the batch budget; overflow chunks and branches
  run sequentially.

## 9. The provenance data plane (shipped)

Per-(node, relationship) row counts are collected inline by `ProvenanceCollector` during authored
runs and persisted by `DbProvenanceStore` when `-Dprovenance.backend=duckdb` is set (default off).
`GET /provenance?flow=&batch=` + `GET /provenance/batches?flow=` feed the editor's last-run overlay
and the per-edge Sankey; the conservation invariant (§6) is checked over these counts. The plane
shares the pipeline graph's topology — quantities painted onto its edges.

## 10. Decisions of record and boundaries

Design-era decisions D1–D12 (runtime = topology over the batch engine · TOON files + `use:`
registry · route case/clone · merge = SQL over predecessors · adapter lands files (land-then-ack,
at-least-once) · entry-node triggers · admission back-pressure on cycle overrun · `(batch, branch)`
commit · in-file component identity, no version pinning · DAG over `data` edges · test = bounded
scratch dry-run · legacy auto-lift with parity gate) all stand; the full statements with rationale
are in the [archived design text](../../../archived-documents/plans-archive/pipeline-graph-design-era-2026-06.md)
§9. Newer decisions of record live in [`superpower/pipeline-spec.md`](../../../superpower/pipeline-spec.md) §13 (D1–D10, taken 2026-08-31).

Boundaries still true (v1 non-goals, archived §12 for the full table): no decoupled per-node
scheduling or inter-node queues; no live × live keyed join; adapter ingestion is at-least-once; no
component version pinning; transforms must be SQL-expressible (imperative logic = the plugin SPI);
DAGs only; per-flow trigger granularity; no cross-branch transactional commit; multi-tenant RBAC is
an edition concern.

Review risks R1–R6 (2026-06-16): all resolved — R1 (row-shaping machinery) built as `RowShaper`;
R2 (`(batch, branch)` commit) built as `BranchCommitLog`/`Coordinator`; R3 (branch executor)
**CLOSED 2026-08-26**, armed on the ingest path at the `writeAndTrace` choke point
([branch-aware-ingest.md](../engine/branch-aware-ingest.md)); R4 (fan-out lift) built; R5
(`on_commit` cross-flow rule) enforced; R6 (`ingest` job type) deleted.

## 11. Route branches and arming (pointer)

A `route:` branch is `{key, where, database}` — `key` and `database` are DERIVED (edge name; the
sink the edge feeds, stamped as the branch↔sink join key), `where` is authored. `RouteArming`
refuses an armed branch with no `where` (WARNING while `active: false`, 422 once active; the
`default:` branch is not exempt). ⛔ `branches` must never get an `AttributeSpec` — a specced key is
form-owned and would destroy the derived pair. Full account:
[editable-round-trip.md](editable-round-trip.md) §19; runtime fork:
[branch-aware-ingest.md](../engine/branch-aware-ingest.md).
