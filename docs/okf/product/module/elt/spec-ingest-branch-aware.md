---
type: Concept
title: Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest — Branch-aware ingest and contract tests
description: Part 3 of 3 of Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest: Branch-aware ingest — route: on the poll-driven path; Contract tests; References
tags: [elt, engine, ingestion, spi, streaming, consignment, ingester, duckdb, seam, partitions, contract-test, plugins, segments, quarantine, route, branch, arming, fail-closed, lanes, addressing, revisions, watermark, selector, event-time, concurrency, scheduler, priority, backpressure, operations, parsing, grammar, csv, plugin, read-csv, frontend, options, asn1, xml, decode-profile]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
status: current
verified: 2026-11-20 last-edit
code: platform/inspecto-etl
---

# Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest — Branch-aware ingest and contract tests

Part 3 of 3 of [Ingest — the ingestion SPI, ingesters, Consignment coordination and branch-aware ingest](spec-ingest.md) · previous: [Consignment coordination, failover and write-path identity](spec-ingest-coordination-failover.md).

## 11. Branch-aware ingest — `route:` on the poll-driven path

**Shipped 2026-08-26** (`b3a8bd40` → `23b9265d`; plan:
[`branch-aware-executor-arming-plan.md`](../../../../archived-documents/plans-archive/branch-aware-executor-arming-plan.md)).
Closes design §13 R3: an **active** pipeline carrying `route:` executes its branch tree on the
ordinary ingest path — the graph editor's route vocabulary finally runs where it is authored.

This section owns the **ingest flat / graph-fork mechanism**; the map of all lanes lives in
[spec-pipeline-execution.md](spec-pipeline-execution-engines-lanes.md#execution-lanes).

### How it runs

- **Divert point:** `ConsignmentIngestStrategy.writeAndTrace` — the one choke point every ingest path
  funnels through with the live DuckDB connection and the materialised `transformed` table (the
  connection is strategy-scoped, which is why no higher divert is possible). A `route:` pipeline
  diverts when `ConsignmentGraphRunner.engages(PipelineLift.lift(cfg))`; **since 2026-08-29 a NON-route
  pipeline diverts too** whenever the two lanes are provably the same write — see *The lane fork*
  below. Either way the write segment is replaced by `graphWriteAndTrace`.
- **Machinery:** `ConsignmentGraphRunner.run` (the `SinkWriter` overload) drives `PipelineExecutor` over
  the `route → sinks` subgraph, seeded at the route node's upstream (the map node — parse/map are
  never re-run), committing each branch through a durable per-batch `BranchCommitLog` under
  `dirs.temp` via `BranchCommitCoordinator`.
- **Writes:** `IngestSinkWriter` (com.gamma.inspector) writes each branch to the `sinks[]`
  destination its key was paired with at lift time — matched by the branch's `database`, the same
  join key `PipelineLift.branchKeyForDatabase` uses — with the destination's own
  format/compression/`filename_column`, per-branch `LineageCollector` rows and event-time bounds.
- **Parity by shared code, not mirrors:** the method returns the flat `Written` shape into
  `IngestOutcome`, so `commit`/`finalizeSource`/`writeAudit` — manifest, backup, markers-LAST,
  dedup ledger, watermark, all three CSV ledgers, `ConsignmentEvent` → signals/enrichment, provenance —
  are the SAME code as the flat path. `IngestSinkWriter` deliberately does NOT register §11.3;
  `finalizeSource` registers from the returned lineage exactly as always. The runner's
  once-after-all-branches hook is a documented no-op for the same reason.

### The lane fork (ELT Phase 6 slices A–C2, 2026-08-29)

The graph lane is no longer route-only. `ConsignmentIngestStrategy.graphLaneCarries(cfg)` admits a
non-route pipeline when the write is reproducible there, which is now every shape a pipeline can
actually be armed in: **one or many destinations** (the lift emits a sink node per `sinks[]` entry
and the executor writes each independently — the fan-out gains per-destination crash resumption the
flat loop never had), **a versioned reference store** (the stamp runs before the walk exactly as it
does flat; only the ROUTE combination stays refused, because one version history across branches is
ill-defined), and **several writes per batch**. The seed generalises to `seedFeedingTheWrite` — *the
node whose data relation IS the materialised table* — so the walk performs the WRITE and never
re-runs parse/map. That is also why `withMappingContext` (a `PipelineDryRun`-only patch) is still
not needed here.

Refused, and left flat by name: a node BETWEEN map and sink (`dedup`/`join`/`summarize` — carrying
them means EXECUTING them at rest, which is Stage-2 work), and a pipeline with **no configured
scratch dir**.

**The flag (ELT amendment §6 step 3 / D-2, built 2026-09-02).** `-Dingest.lane=auto|graph|flat`, read
in `ConsignmentIngestStrategy.admittedLift` (the fork's decision, extracted pure so it is testable).
`auto` (default) is the admission above. `graph` **disables the legacy flat lane**: a write the graph lane
cannot carry fails the batch with an `IllegalStateException` naming the pipeline and the reason
(`flatReason` — no scratch dir, a node between map and the write, …) rather
than quietly diverting flat. That is what the "one verification minor" runs with: every remaining
dependency on the flat lane surfaces as a refusal. `flat` is the kill switch (never divert). Any other
value is refused. Pinned by `IngestLaneFlagTest`. ⚠ The deletion of the flat readers stays release-gated
(BACKLOG row 15, D-2) — the flag is its precondition, not its trigger.

Three mechanisms a change here must respect:

1. 🔴 **`writeAndTrace` has four callers and two write a batch in SEVERAL calls** (one per chunk, one
   per segment), reusing the same sink ids against ONE shared branch ledger. Each caller passes a
   **write scope**; `BranchCommitCoordinator` records `<scope>::<branch>` while handing the bare id
   to the writer. Whole-batch callers pass `""` and record exactly the keys they always did, which
   is what keeps the drain — reading bare sink ids back out — and the single-log cleanup working.
2. 🔴 **Decision Rules are a space-registry fact, not a config property**, so the admission cannot
   see them statically. `DecisionRuleApplier.apply` runs ONCE above the fork and its RESULT is part
   of the admission: a Decision Rule that actually routed rows keeps the pipeline flat.
3. 🔴 **`dirs.temp` is optional, and the graph lane keeps a DURABLE ledger.** All three sites that
   spell the log path — create, drain-resume, cleanup — go through
   `ConsignmentIngestStrategy.branchCommitLogPath`, and a pipeline without a scratch dir stays flat rather
   than parking that ledger in a shared `%TEMP%`, where a stale `branch_commit_<batchId>.log` makes
   the coordinator skip the branch and the batch writes NOTHING.

Parity is proven, not asserted: `FlatVsGraphLaneParityTest` runs one materialised table through both
lanes and diffs output files, partitions, rows on disk, the lineage matrix and event-time bounds on
each admitted shape — and the whole reactor now exercises simple pipelines through the graph lane on
every run.

### The engagement predicate

`ConsignmentGraphRunner.dataFedSinkCount` counts **branches**, not sink nodes: distinct `route:*`
relations reaching a SINK-category node, plus one for the trunk when any sink is plain-`data`-fed.
So: flat single-sink = 1 · plain `sinks[2]` fan-out = 1 (N destinations of ONE branch — stays on
`writeAndTrace`'s flat fan-out with its reference-versioning and Decision Rules) · a two-branch
route = 2 · multi-schema selector = 1 (its `route:*` rels terminate at map nodes, not sinks).
⚠ The original node-count predicate engaged for plain fan-out and was refuted by its own
falsification test (`ConsignmentGraphRunnerLiftEngagementTest`); a stale `PipelineLiftTest` pin had
encoded the wrong belief. ⚠ And do not read this predicate as a claim about capability: it answers
*"is there a second BRANCH worth diverting for?"* for the route lane. Whether the graph lane can
perform a given write is `graphLaneCarries`' question, and a plain fan-out — one branch, N
destinations — is carried there since slice B.

### Fail-closed arming (`PipelineConfig.prepare()`)

Each arming check refuses BY NAME a shape that would drop rows silently:

| Check | Why |
|---|---|
| `default:` required, naming a branch key | `mode: case` labels an unmatched row NULL and the executor emits it on NO relation — no default = silent discard |
| every branch has a `database` matching a **distinct** `sinks[]` destination | the branch↔sink pairing is by database; unmatched or shared = a branch whose rows land nowhere |
| ~~`mode: clone` refused~~ | arms since 2026-09-06 — see the decision at the end of this section |
| multi-schema (selector/segments): every branch `where:` must bind in EVERY schema's mapped row | one shared `route:` block applies to every schema (2026-09-24); a predicate on a column only some schemas map would fail mid-run on the others, after the earlier schemas' branches committed — see *Multi-schema route* below |

Runtime refusals in `graphWriteAndTrace`: Decision Rule routing + route branches; a versioned
reference store per branch. Decision Rule routing + `sinks>1` (no `route:`) is carried in both lanes since
2026-10-06 — the routed rows are replicated to every destination ([spec-sinks.md](spec-sinks.md#multi-destination-sinks-shipped-2026-08-02--0cdc9dff--79dcb3e6) § *Refusals*).

### Deliberately not built (residuals — BACKLOG §6)

Mid-branch transforms in the recipe's route verb (compiles refused: "a route branch compiles as exactly
one sink step for now"). ~~`mode: clone` arming~~ (2026-09-06) · ~~multi-schema + route~~ (2026-09-24,
below) · ~~the pre-fetch intake cap~~ (2026-11-17, MoSCoW C8: the byte cap runs before a remote fetch when
the listing sizes every file; [acquisition spec](spec-acquisition.md) § *The poll cycle and its two timers*). The flat homes
(`transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest) stay refused; board §6
*Pipelines — execution*.

**The save-time arming pre-check SHIPPED 2026-08-26** — and this section's earlier reason for deferring it
("arming validates at engine LOAD on both server and mock — a 422-on-save would be UX polish, not a
gap") was wrong in the half that mattered. Arming did validate at load, but neither `/validate` nor
`/config/write` calls `prepare()`, so a save returned `written: true` and the operator learned their
branch tree was unarmable at the next run. A fail-closed gate the author never sees is a log line.

### Where the arming checks live

`RouteArming.refusals(route, sinkDatabases, schemaColumns[, mayBind])` (`inspecto-etl`) is the ONE
statement of the checks, with two callers holding the config in two different states. `schemaColumns` is
`null` for a single-schema pipeline, else every schema's name → its MAPPED columns (check 4's input):

| Caller | State | Behaviour |
|---|---|---|
| `PipelineConfig.prepare()` | parsed config, at registration | throws the FIRST refusal — registration is all-or-nothing |
| `ConfigRoutes.routeArmingFindings` | unparsed DRAFT map, at save | reports ALL refusals as `Finding`s; `active: true` ⇒ ERROR (422, nothing written), `active: false` ⇒ WARNING naming when it will bite |

The columns come from different places, deliberately: `prepare()` feeds `TypeFlow.transformedColumns`
per schema (DuckDB-authoritative); the save path feeds each schema file's raw fields ∪ `mapping.fields[]`
(as `routeColumnFindings` models one schema; an unreadable schema is an empty list = not judged) and
passes `mayBind` = its `SqlGuard` check, so an untrusted predicate never reaches the binder unguarded.
Only a genuine unknown-column binder error refuses — `RouteArming.isUnknownColumn`, now the one place
that line is drawn (`ConfigRoutes.isUnknownColumn` delegates to it).

⛔ Do not run the draft through `PipelineConfig.fromMap` to reuse the parsed form: `fromMap`
hard-fails on an unresolvable schema reference, which the save path keeps a WARNING on an inactive
draft (the file may be created after the save) and refuses only when active (`ERR_SCHEMA_FILE_UNRESOLVED`,
[config safety](../config/spec-config-safety.md)) — the draft still has to be judgeable without it. `armedWithoutSchemaFindings`
makes the same call, for the same reason, and says so in its javadoc. The checks take plain data so
both callers can supply it from what they have — restating them over raw maps in the control plane
would be the hand-mirrored-map drift this repo has already paid for three times.

### Multi-schema route — the segment-scoped lift (shipped 2026-09-24)

Design + slice record (archived): [`branch-aware-segment-lift-design.md`](../../../../archived-documents/plans-archive/branch-aware-segment-lift-design.md).
Operator decisions 2026-09-24: **one shared `route:`** for every schema (no per-schema route blocks);
**`<branch database>/<segKey>/…`** landing (one store per branch × segment); a predicate binding in some
schemas only **refuses arming**; the plugin `segments` path and the CSV `schemas[]` selector path ship
**together**; **partial-batch semantics** accepted (below).

- **The walk runs on one schema's slice.** `PipelineLift.scope(graph, key)` is a VIEW over the one lift —
  `map_<key>` plus everything downstream, same node ids and configs (so branch↔sink pairing, ledger keys
  and park ids are unchanged); `null` key = identity. `admittedLift` admits, seeds and walks the scoped
  graph whenever the write's key is known, on the route AND the non-route admission. The STORED graph is
  still the full lift, so `PipelineEditable.lower` and the round-trip are untouched — a scoped lift is never
  a second emitter.
- **The key.** A segments write: `segmentWrite(cfg, writeScope)` (the segment key IS the scope) → the
  lift's `routeKey`. A selector batch: `selectorWrite(cfg, selectedTable)` — the table its schema was
  selected under, passed to `writeAndTrace` as its OWN argument by all three selector call sites
  (`CsvIngestStrategy`, `NativeCsvStreamingEngine.unionStreamingIngest` and `streamUnit`). ⚠ Not through
  `writeScope`: the single-member streaming and chunked lanes already use it for the chunk base name
  (the ledger discriminator), and a selector batch is one schema in one call, so its ledger scope stays
  `""`. ⚠ Keyed for `route:` pipelines only — a non-route selector write keeps its existing (flat)
  admission; widening that lane is a separate change.
- 🔴 **The defect it fixed (D1).** Before, a selector batch knew no key, so the seed was the FIRST
  `transform.route` node's upstream: every non-first schema's rows were routed by the first schema's
  route and written through its sinks — attributed to the wrong Dataset. Pinned in `SegmentScopedRouteTest`.
- **Parking.** `StepDisableArming.parkableSinkIds(route, sinkDbs, schemaKeys)` emits `sink_<key>__d<i>`.
  🔴 Without it, once multi-schema arms, `disabled_steps: [sink__d1]` passes the gate and matches NO lifted
  node — a silently-enabled step. `liftKey` mirrors `PipelineLift.routeKey`, pinned against a real lift
  (`PipelineLiftTest.multiSchemaParkableSinkIdsMatchTheLiftedGraph`).
- **Drain.** `DrainCommand` resolves the schema by manifest: a selector batch's entry is the one named by
  `outputTable`; a segments batch writes each parked sink under its own segment's home
  (`database/<segKey>`, that segment's partitions) — one `IngestSinkWriter` per segment.
- **Partial batch (accepted semantics).** Each segment commits its own branches under ledger scope
  `segKey`. If segment 2 throws after segment 1 committed, the batch is FAILED with segment 1's branch
  files durable; the file stays in the inbox, the ledger survives, and the retry (same content-derived
  Consignment id) skips what the ledger holds — no duplicate rows (`SegmentRouteEndToEndTest`).
  ⚠ **Known gap, pinned in that test (not introduced here — a single-schema route whose second branch
  fails resumes the same way):** the resumed commit never sees the skipped branches' outputs, so its
  manifest — and the DuckLake register / §11.3 output registry fed from it — omits them. Their rows are
  durable and the lineage LEDGER is whole (the failed run's audit wrote it); only the output registry is
  short. The park path solved this with the `ParkedCommit` sidecar; the failure path has none.
- ⚠ `ConsignmentGraphRunner.engages` / `dataFedSinkCount` still exclude a multi-schema parser's
  `route:<key>` dispatch edges; on a scoped graph there are none left to exclude.
- **Arming (check 4) is mutation-checked.** Re-inserting the old blanket multi-schema refusal turned 7 tests
  red when S4 was built (the slice record's count, not re-run since), so the per-schema bind check cannot silently regress to a refusal.
- ⚠ **The end-to-end "kill after segment 1" is a write failure, not a JVM kill.** `SegmentRouteEndToEndTest`
  blocks segment 2's branch directory with a file, lets the batch FAIL after segment 1 committed, removes
  the blocker and runs the next cycle. That leaves the same durable state a kill does (ledger, committed
  files, inbox file), minus the FAILED audit row. Its fixture is a test-resources copy of the demo
  `stock_movements` Pipeline with a `route:` on `QTY` — ⛔ never a `spaces/demo` edit.

**Why the walk was already scoped, and why lifting the refusal alone was still wrong.** Since the
per-segment `writeAndTrace` calls (2026-09-16), a segments walk seeded at `map_<key>` reached only that
segment's route and sinks, because `PipelineExecutor` skips every node with no live inbound relation. That
scoping was an accident of the seed. Everything that reasons about the graph before and after the walk —
the seed choice for a selector batch (D1), the route admission, arming, crash drain, partial-batch retry —
was not scoped, which is what the slices fixed.

**Options not taken (2026-09-24).** *Fix only the seed and delete the refusal* — rejected: it leaves
admission, arming, drain and retry unscoped, and scoping stays an emergent property of a skip rule nothing
tests for this purpose. *Per-schema `route:` blocks* (`segments.<key>.route` / `schemas[i].route`) — not
built, because the operator chose one shared block. It stays available as an ADDITIVE config home if
per-segment routing semantics are ever wanted; it would need a new config home, spec/JSON-schema, editor,
lowering and recipe-grammar changes.

### Traps pinned along the way

- `PipelineLift.stageTwo` is the **at-rest Stage-2 chain** lift (refuses without `output_store:`);
  the ingest-topology lift is `PipelineLift.lift(cfg)`.
- `PartitionSinkWriter` is Pipeline-Job-shaped (one `dataDir/store` root, no lineage, self-registers
  §11.3) — never reuse it for ingest destinations.
- A TOON tuple row with an **unquoted Windows path** fails as "Array length mismatch … found 0"
  (the drive colon); quote every path cell.

### Decision 2026-09-06 — `mode: clone` arms, and committed branches become visible

B9's constraint was that nothing surfaces partial-commit state. The `BranchCommitLog` is already durable per
`(batch, branch, phase)`; only the read surface was missing. **Decided:** arm `clone` and merge
`committedBranches[]` / `sourceFinalized` into `GET /runs/{name}/batches` beside the park detail, rendered on the
Batches tab. **Shipped 2026-09-06:** `RouteArming` no longer refuses `clone`; `RunRoutes.withParkDetail` merges
`committedBranches[]` + `sourceFinalized` onto any batch row whose branch commit log still exists (a fully
committed batch deletes its log, so only unfinished ones carry the keys); the batch dialog lists them.

---

## 12. Contract tests

`ConsignmentIngestorPluginTest` (`inspecto-engine`; named `BatchProcessorPluginTest` until the 2026-08-31 rename — the wrap-SPI page carried the old name until 2026-09-08) is the end-to-end pin: a toy
`StreamingFileIngester` drives records through the whole wrap into a **partitioned** store
(CALL/SMS segments, `event_type/year/month/day`) and — `unkeyedSegmentWritesAFlatStoreWithLineage`
— into an **unpartitioned flat** store, asserting rows, layout (no sentinel), and the lineage
ledger. The concurrency claims of the finalization stores are pinned at the ledger by
`ConsignmentAuditWriterTest.concurrentFlushesKeepEachBatchBlockContiguous` (E4, narrowed — see the plan),
and since 2026-08-19 by **`FinalizeSourceConcurrencyTest`** (the E4 remainder, ex-BACKLOG §4 (b)):
8 distinct batches finalizing concurrently through the SHARED `DbConsignmentOutputStore` /
`DbAcquisitionLedger` / `DbFileStageStore` (registry reconciliation, ledger PROCESSED, the full
crash-ordered stage trail, one manifest per batch), plus the same-file marker race — exactly ONE
loser, on the atomic `Files.createFile`. ⚠ The race fixture deliberately has no backup dir (a
same-file `Files.move` race throws platform-dependent exceptions before the marker), and the
harness pins exactly the stores' own `synchronized`+one-connection claim — a future connection
pool must re-run it.

---

## References

- [architecture-elt.md](architecture-elt.md) — engine architecture, the M..N multiplexer, back-pressure, the
  Consignment status flow, the format-specific configuration principle; acquisition (Collectors, fetch,
  discovery — getting files to the inbox)
- [spec-pipeline-execution.md](spec-pipeline-execution.md) — [node types, the token model, transform seams](spec-pipeline-execution.md) ·
  [execution lanes, Build→Test→Run containment, post-sync step chains](spec-pipeline-execution.md) ·
  [the graph-lane sink kinds; quarantine](spec-sinks.md)
- [spec-db-layer.md §3.9](../engine/spec-db-layer.md) — the catalog's DDL and its two readers
- [operations-reference.md](../../layer/platform/admin-guide-operations-reference.md) — `retire_superseded`
- [HA/DR drill runbook](../../layer/platform/admin-guide-ha-dr-drill.md)
- [admin-guide-configuration-reference.md](../config/admin-guide-configuration-reference.md) — every config key, incl. the fixed-width frontend
- [user-guide-grammar-config.md](../config/user-guide-grammar-config.md) — authoring Grammars, the Parse drawer
- [spec-component-registry.md](../processor/spec-component-registry.md) — Grammar Templates as components
- [ELT plan](plan-elt.md) — the ING capability spec (requirements of record)
- Archived designs: [delimited-grammar-design.md](../../../../archived-documents/plans-archive/delimited-grammar-design.md) ·
  [parser-plugins-trust-design.md](../../../../archived-documents/plans-archive/parser-plugins-trust-design.md) ·
  [parsing-repeated-child-design.md](../../../../archived-documents/plans-archive/parsing-repeated-child-design.md)
- [GLOSSARY.md](../../../../GLOSSARY.md) §2 *Consignment*, §5 *Parser*, *Grammar*, *Grammar Template*, *Decode Profile*, §6-B
