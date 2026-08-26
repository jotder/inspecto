# Branch-aware ingest — `route:` executes on the poll-driven path

**Shipped 2026-08-26** (`b3a8bd40` → `23b9265d`; plan:
[`branch-aware-executor-arming-plan.md`](../../../archived-documents/plans-archive/branch-aware-executor-arming-plan.md)).
Closes design §13 R3: an **active** pipeline carrying `route:` executes its branch tree on the
ordinary ingest path — the graph editor's route vocabulary finally runs where it is authored.

## How it runs

- **Divert point:** `BatchIngestStrategy.writeAndTrace` — the one choke point every ingest lane
  funnels through with the live DuckDB connection and the materialised `transformed` table (the
  connection is strategy-scoped, which is why no higher divert is possible). When
  `cfg.routeConfig() != null` and `BatchGraphRunner.engages(PipelineLift.lift(cfg))`, the write
  segment is replaced by `graphWriteAndTrace`.
- **Machinery:** `BatchGraphRunner.run` (the `SinkWriter` overload) drives `PipelineExecutor` over
  the `route → sinks` subgraph, seeded at the route node's upstream (the map node — parse/map are
  never re-run), committing each branch through a durable per-batch `BranchCommitLog` under
  `dirs.temp` via `BranchCommitCoordinator`.
- **Writes:** `IngestSinkWriter` (com.gamma.inspector) writes each branch to the `sinks[]`
  destination its key was paired with at lift time — matched by the branch's `database`, the same
  join key `PipelineLift.branchKeyForDatabase` uses — with the destination's own
  format/compression/`filename_column`, per-branch `LineageCollector` rows and event-time bounds.
- **Parity by shared code, not mirrors:** the method returns the flat `Written` shape into
  `IngestOutcome`, so `commit`/`finalizeSource`/`writeAudit` — manifest, backup, markers-LAST,
  dedup ledger, watermark, all three CSV ledgers, `BatchEvent` → signals/enrichment, provenance —
  are the SAME code as the flat path. `IngestSinkWriter` deliberately does NOT register §11.3;
  `finalizeSource` registers from the returned lineage exactly as always. The runner's
  once-after-all-branches hook is a documented no-op for the same reason.

## The engagement predicate

`BatchGraphRunner.dataFedSinkCount` counts **branches**, not sink nodes: distinct `route:*`
relations reaching a SINK-category node, plus one for the trunk when any sink is plain-`data`-fed.
So: flat single-sink = 1 · plain `sinks[2]` fan-out = 1 (N destinations of ONE branch — stays on
`writeAndTrace`'s flat fan-out with its reference-versioning and decision rules) · a two-branch
route = 2 · multi-schema selector = 1 (its `route:*` rels terminate at map nodes, not sinks).
⚠ The original node-count predicate engaged for plain fan-out and was refuted by its own
falsification test (`BatchGraphRunnerLiftEngagementTest`); a stale `PipelineLiftTest` pin had
encoded the wrong belief.

## Fail-closed arming (`PipelineConfig.prepare()`)

Each rule refuses BY NAME a shape that would drop rows silently:

| Rule | Why |
|---|---|
| `default:` required, naming a branch key | `mode: case` labels an unmatched row NULL and the executor emits it on NO relation — no default = silent discard |
| every branch has a `database` matching a **distinct** `sinks[]` destination | the branch↔sink pairing is by database; unmatched or shared = a branch whose rows land nowhere |
| `mode: clone` refused | cross-branch partial-commit UX (plan B9/D8) deliberately unshipped |
| multi-schema (selector/segments) + route refused | the lift emits one route node per schema branch; the divert executes exactly one |

Runtime refusals in `graphWriteAndTrace`: decision-rule routing + route branches; a versioned
reference store per branch.

## Deliberately not built (residuals — BACKLOG §6)

`mode: clone` arming · multi-schema + route · mid-branch transforms in the recipe's route verb
(compiles refused: "a route branch compiles as exactly one sink step for now").

**The save-time arming pre-check SHIPPED 2026-08-26** — and this section's reason for deferring it
("arming validates at engine LOAD on both server and mock — a 422-on-save would be UX polish, not a
gap") was wrong in the half that mattered. Arming did validate at load, but neither `/validate` nor
`/config/write` calls `prepare()`, so a save returned `written: true` and the operator learned their
branch tree was unarmable at the next run. A fail-closed gate the author never sees is a log line.

## Where the arming rules live

`RouteArming.refusals(route, sinkDatabases, multiSchema)` (`inspecto-etl`) is the ONE statement of
the six rules, with two callers holding the config in two different states:

| Caller | State | Behaviour |
|---|---|---|
| `PipelineConfig.prepare()` | parsed config, at registration | throws the FIRST refusal — registration is all-or-nothing |
| `ConfigRoutes.routeArmingFindings` | unparsed DRAFT map, at save | reports ALL refusals as `Finding`s; `active: true` ⇒ ERROR (422, nothing written), `active: false` ⇒ WARNING naming when it will bite |

⛔ Do not run the draft through `PipelineConfig.fromMap` to reuse the parsed form: `fromMap`
hard-fails on an unresolvable schema reference, which the save path deliberately keeps a WARNING
(the file may be created after the save, or belong to another host). `armedWithoutSchemaFindings`
makes the same call, for the same reason, and says so in its javadoc. The rules take plain data so
both callers can supply it from what they have — restating them over raw maps in the control plane
would be the hand-mirrored-map drift this repo has already paid for three times.

## Traps pinned along the way

- `PipelineLift.stageTwo` is the **at-rest Stage-2 chain** lift (refuses without `output_store:`);
  the ingest-topology lift is `PipelineLift.lift(cfg)`.
- `PartitionSinkWriter` is flow-job-shaped (one `dataDir/store` root, no lineage, self-registers
  §11.3) — never reuse it for ingest destinations.
- A TOON tuple row with an **unquoted Windows path** fails as "Array length mismatch … found 0"
  (the drive colon); quote every path cell.
