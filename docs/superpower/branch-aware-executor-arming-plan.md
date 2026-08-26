# Branch-Aware Executor — Arming Plan (route: goes live on the ingest path)

**Status:** APPROVED 2026-08-26 — **Option B signed off** (operator call, §2); slices reshaped for B
**Closes:** BACKLOG §6 "Branch-aware executor — run what the graph editor can now author";
design doc §13 R3; the `route:` arming gate left by ELT-amendment P2 S5.
**Predecessor of record:** `archived-documents/plans-archive/branch-aware-executor-plan.md`
(Stage A steps 1–3 shipped as dormant machinery `318acf2a`/`6965f6f3`; Stage B closed; Stage C
folded into the ELT amendment). This plan is Stage A's step-3 wiring + step 4, re-planned against
what actually shipped since (P2 S5 `route` lowering, `sinks:`, the unpack stage, `logical_name`).

---

## 0. Grounded facts (verified 2026-08-26 — every claim carries a file ref)

**The machinery is built and tested, with exactly one missing link.**

- `BatchGraphRunner` (`inspecto-engine/.../pipeline/exec/BatchGraphRunner.java`):
  `Input(conn, graph, seedNodeId, seedTable, batchId, dataDir, baseName, branchCommitLog)` :46,
  `run(Input, SourceFinalizer)` :68-77 → `PipelineExecutor.execute` with a real
  `PartitionSinkWriter` + `BranchCommitCoordinator` over a fresh `BranchCommitLog`;
  `engages(g) = dataFedSinkCount(g) > 1` :89-100, quarantine/`unmatched` sink excluded.
  Six pinned tests (`BatchGraphRunnerTest`, `BatchGraphRunnerFinalizeTest` — incl. markers-LAST
  once-only on a two-sink graph and no re-finalise on commit-log replay).
- **No production caller.** Nothing on the ingest path lifts a graph or calls `engages()`.
  The only run-time lift is the flow-job path: `PipelineJobRunner.execute:196` →
  `PipelineLift.stageTwo(PipelineConfig.load(flatPath))`.
- **The graph is derivable from the flat file today — via `PipelineLift.lift(cfg)`, NOT
  `stageTwo`.** ⚠ Corrected by S1's own test run (2026-08-26): `stageTwo` is the Stage-2 AT-REST
  chain lift and refuses any config without `output_store:` (`PipelineLift.java:131-134`) — the
  original claim here was refuted the first time it met the compiler. `lift(cfg)` (:53) is the full
  ingest-topology lift (the same one `PipelineEditable.toMap` uses) and lifts an authored `route:`
  into a `transform.route` node whose `route:<key>` edges feed per-destination sinks, paired by the
  branch's declared `database` (`emitSinks`/`branchKeyForDatabase` :365-421).
- **The arming gate:** `PipelineConfig.prepare()` :1520-1524 throws for an `active` pipeline
  carrying `route:` ("authoring-only until the branch-aware executor lands"). Same posture for
  `summarize`/`join`/`explicitSteps`-route :1530-1583 — those are NOT this plan's scope.
- **The divert point has everything but the graph.** `BatchIngestStrategy.writeAndTrace`
  (:120-189) holds `conn, table (post-transform-materialisation input), cfg, dbDir, baseName,
  batchId, srcIdToFile` — `BatchGraphRunner.Input` minus `graph`/`seedNodeId`.
- `RowShaper.shape` (:105-125) executes `filter/validate/route/dedup/split/summarize/map/select/
  derive/join` table-in → named-tables-out; `merge` separate (:479). `PipelineExecutor` (T12)
  walks the DAG Kahn-topologically with `route:*`/`unmatched` edge semantics.

**The parity surface — what the flat path writes for one committed batch, in order**
(`BatchProcessor.process` :51; this is the checklist the graph path must not silently shrink):

| # | Side effect | Site | Graph-path status today |
|---|---|---|---|
| 1 | DuckLake register | BatchProcessor:181 | missing |
| 2 | `FileStages` roll-ups (REGISTERED…WATERMARK_ADVANCED) | :186,325,341,361,371,395 | missing |
| 3 | Member entries incl. dedup-ledger keys, skipped-unreadable rows | :207-306 | missing |
| 4 | Schema fingerprint | :310 | missing |
| 5 | Manifest write | :312-324 | missing |
| 6 | §11.3 output registry | :332-337 | **reused differently** — `PartitionSinkWriter.write` :114-117 registers per branch; `finalizeSource` deliberately skips when graph-driven (:53-56 comment) |
| 7 | Backup originals (skip expanded members) | :343-361 | missing |
| 8 | Markers LAST | :365-371 | missing |
| 9 | Fingerprint ledger LAST | :374-378 | missing |
| 10 | DB-export watermark LAST | :383-395 | missing |
| 11 | Unpack scratch cleanup + deferred archive backup/marker | :406-420 | missing |
| 12 | FileRow audit rows incl. `origin` + `logical_name` | :535-554 | missing |
| 13 | `UnpackLedger.entryOutcome` roll-up | :542-545 | missing |
| 14 | BatchRow (status/counts/castFailures) | :559-565 | missing |
| 15 | 3 CSV ledgers + fsync'd commit log | BatchAuditWriter:152-160 | missing |
| 16 | Terminal `BatchEvent` → BatchEventBus + `pipeline.batch.committed`/`failed` Signal → enrichment triggers, `JobService.mirrorPipelineCommit` | BatchAuditWriter:161-184, PipelineBatchSignal:29-53 | missing |
| 17 | Provenance rows (SUCCESS-only) | BatchProcessor:99-108 | missing |
| 18 | Quarantine of failed members | CsvBatchStrategy:109-131 | N/A — upstream of both drivers |

Rows 1-5 and 7-11 are exactly `finalizeSource` — designed to be handed to `BatchGraphRunner`
as its `SourceFinalizer` (that seam exists and is replay-tested). Rows 12-17 are `writeAudit` +
its downstream — **no seam exists for them on the runner path.** This is what ⛔ "don't just
wire `engages()`" meant.

**Also grounded:** decision rules + reference versioning + `sinks:` fan-out live in
`writeAndTrace` upstream of any sink write (:120-157,177-186). Row-grain dedup at EL is gone by
operator decision (2026-08-11); `processing.dedup` QUALIFY runs between materialisation and the
write (:141-150).

---

## 1. Scope

**In:** an `active` pipeline carrying `route:` executes end-to-end on the poll-driven ingest
path with FULL output parity (all 17 applicable rows above), and `prepare()`'s route refusal is
lifted. `mode: case` only (exclusive, record-conserving, D3 default).
**Out (unchanged refusals, each by name):** `summarize`/`join`/`steps:`-route arming
(their own rows); `mode: clone` cross-branch partial-commit UX (B9 stands: non-transactional);
`adapter`/`alert`/`event` (genuinely unimplemented anywhere); lifting the editor's remaining
`UNSUPPORTED_NODE` list beyond what route needs; decision-rule routing combined with `route:`
(fail-closed, new refusal — the flat path already refuses fanOut+routing at :128-130).

---

## 2. The one design decision — WHERE the graph path diverts (operator call)

**Option A — outcome-shaped divert inside `writeAndTrace` (RECOMMENDED).**
When the lifted graph engages, replace ONLY the segment between transform materialisation and
the partitioned write: walk the route subgraph (`RowShaper.shape` per node, `PipelineExecutor`
order) from the materialised table, `PartitionWriter.write` each branch to its paired sink,
collect lineage per branch, and return everything in the SAME `IngestOutcome` shape the flat
path returns. `commit`/`finalizeSource`/`writeAudit` then run UNCHANGED.

- Parity for rows 1-5, 7-17 **by construction** — the entire commit + audit + event surface is
  the same code, not a mirror. Zero new mirrors to drift.
- The unmatched-rows branch lands via the existing quarantine-sink semantics.
- Cost: `BatchGraphRunner.run` + `BranchCommitCoordinator` are NOT on this path (their
  once-only-markers replay guarantee is not needed — batch-level retry + OVERWRITE_OR_IGNORE
  idempotence is exactly the flat path's crash story). They remain the machinery for a future
  `mode: clone` / genuinely-parallel-branch stage.
- Risk: the honest trigger is "the config has route:", but the predicate must stay "off the
  graph, not a flag" (Stage A's rule) — so compute `engages(PipelineLift.stageTwo(cfg))`.

**Option B — runner-shaped divert at `BatchProcessor` level.**
Hand the whole batch to `BatchGraphRunner.run` with `finalizeSource` as the finalizer, then
build the missing audit/event/provenance surface (rows 12-17) into that path.

- Uses the shipped runner + per-branch commit log as designed; ready for clone-mode later.
- Cost: rows 12-17 must be REPRODUCED (a second caller of `writeAudit`'s assembly, or an
  extraction) — a mirror class this codebase has been burned by five times (batches-ledger
  five-mirror drift, `srcIdToFile` five-site leak). §11.3 double-registration (row 6) must be
  de-conflicted. Materially larger diff for the same observable behaviour.

~~Recommendation: **A**.~~ **DECIDED 2026-08-26: Option B** (operator call). The runner + per-branch
commit log go live as designed, keeping the clone-mode substrate on the production path. The cost B
carries is therefore a BINDING constraint on S2: **rows 12-17 are EXTRACTED into a seam both paths
call — never reproduced.** Concretely: the graph path assembles the same `IngestOutcome` shape from
`BatchGraphRunner.Result` and calls the SAME `writeAudit` (which already takes an `IngestOutcome`);
`finalizeSource` rides as the `SourceFinalizer` exactly as its Stage-A seam intends; the §11.3
double-registration (row 6) is resolved by the already-shipped skip comment (`BatchGraphRunner.java:53-56`)
— verify it, don't re-decide it. If S2 grounding finds ANY row that cannot be served by a shared seam,
stop and surface it — a mirror is not an acceptable fallback.

---

## 3. Slices (each shippable, verify gate stated; reshaped 2026-08-26 for Option B)

**S1 — lift + engage, still refusing. ✅ SHIPPED 2026-08-26** (`CollectorProcessor.ingest`
lifts `PipelineLift.lift(cfg)` for a config with `route:` and logs the engagement decision;
observe-only). Verified: inspecto-engine 1372/0/0/0. **Two premises refuted while building:**
(a) `stageTwo` was the WRONG lift (§0 correction) — `lift(cfg)` is the ingest-topology one;
(b) **the engagement predicate itself was wrong**: a plain `sinks[2]` fan-out lifts to two
plain-`data`-fed sink NODES, so the node count engaged for a config that runs (and must keep
running) on the FLAT path — the BACKLOG row's "sinks: does not create >1 data-fed sink node"
was false, and `PipelineLiftTest.liftsSinksListToADataFedFanOut` had PINNED the wrong belief
("liftable but not yet runnable", stale since 2026-08-02). Fixed by refining
`dataFedSinkCount` to count BRANCHES (distinct `route:*` rels reaching sinks + 1 for a
plain-data-fed trunk): flat = 1, plain fan-out = 1, two-branch route = 2, multi-schema selector
= 1 (its `route:*` rels terminate at map nodes, not sinks). All prior numeric pins hold; the
stale boolean pin now asserts the refined invariant with the rationale inline. New tests:
`BatchGraphRunnerLiftEngagementTest` (route engages / plain fan-out does not, both from REAL
loaded `.toon` fixtures).

**S2 — wire `BatchGraphRunner` at `BatchProcessor` level, audit through the SHARED seam.**
Engaged path (post-materialisation): refuse decision-rules+route combo by name → build
`BatchGraphRunner.Input` (graph from S1's lift, seed = the materialised table, fresh
`BranchCommitLog` under the run's temp dir) → `BatchGraphRunner.run(input, finalizeSource-overload)`
— the runner's `PartitionSinkWriter` writes each branch and registers §11.3 per branch
(`finalizeSource` must SKIP its own registration on this path, per the shipped :53-56 contract) →
assemble the flat `IngestOutcome` from `Result` (outputs, per-branch lineage, totals, memberAudits
carried through from ingest) → the SAME `writeAudit` call emits rows 12-17 (three ledgers,
`UnpackLedger` roll-up, `BatchEvent` → signals/enrichment, provenance rides `process` as today).
Unmatched rows land via the graph's quarantine sink. ⚠ Branch↔sink pairing reads the SAME
`database`-match rule as `PipelineLift.branchKeyForDatabase` — reference it, never restate it.
⚠ Lineage per branch: the runner path must feed `LineageCollector`-equivalent rows keyed by
`srcId` → branch output files, or Run Detail loses the input→output matrix — grounding task one.
→ *verify:* a two-branch fixture writes both sinks + quarantines unmatched; row conservation
(in = Σ branches + unmatched); lineage rows carry each branch's output files; replay over the same
`BranchCommitLog` does not re-write or re-finalise (extends `BatchGraphRunnerFinalizeTest`); a
single-sink config never engages and is byte-for-byte untouched (flat-path regression suite).

**S3 — arm + parity gate.** Lift `prepare()` :1520 refusal (route only). The parity test:
run the SAME input through a route fixture and assert every §0 row — manifest members, backup,
markers-LAST ordering, all three ledgers (incl. `origin`/`logical_name`), `BatchEvent` fields,
provenance rows, FileStages — against the flat path's shapes. An archive input through a route
pipeline exercises rows 11/13 (unpack interplay). → *verify:* the parity test + full
`-Pedition-enterprise` reactor green; `ApiContractTest` unchanged.

**S4 — surfaces + docs.** `RecipeCompiler`'s route verb: confirm compile→run round-trip now
executes; mock strictness re-check (`mock/pipeline-editable.ts` must stop refusing what the
server now accepts — pinned equal, both directions); design doc §13 R3 + §16,
`okf/backend/engine/ingestion.md`, BACKLOG §6 row, GLOSSARY untouched (no new vocabulary).
→ *verify:* GAUNTLET (reactor + UI lint/test/build); vocabulary guard green.

---

## 4. Gotchas carried forward (binding)

- ⚠ Markers **LAST** is a crash-ordering invariant — S2/S3 must not reorder `finalizeSource`.
- ⚠ `BatchEventBus.publish` is synchronous on the publishing thread holding the run-guard claim.
- ⚠ Unchanged-behaviour proof for non-route configs is the whole ballgame: the flat path is
  byte-for-byte or the plan has failed. Pin with the existing suite, not new assertions alone.
- ⚠ `TestConfigs` defaults every pipeline to id `TEST_ETL` — route fixtures must `.name(...)`.
- ⚠ Deviation rule: if S2 grounding contradicts this plan (it has happened to every plan in this
  repo), correct the plan in place and say so — never build to a refuted premise.

## 5. Open question for the operator (gates S2)

**Q1 — Option A or B (§2)?** A is recommended; B is not wrong, just costlier for the same
observable result, and keeps its value as the clone-mode substrate either way.
