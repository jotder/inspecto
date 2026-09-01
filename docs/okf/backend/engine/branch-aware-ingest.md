# Branch-aware ingest — `route:` executes on the poll-driven path

**Shipped 2026-08-26** (`b3a8bd40` → `23b9265d`; plan:
[`branch-aware-executor-arming-plan.md`](../../../archived-documents/plans-archive/branch-aware-executor-arming-plan.md)).
Closes design §13 R3: an **active** pipeline carrying `route:` executes its branch tree on the
ordinary ingest path — the graph editor's route vocabulary finally runs where it is authored.

This page owns the **ingest flat / graph-fork mechanism**; the map of all lanes lives in
[execution-lanes.md](../pipeline-graph/execution-lanes.md).

## How it runs

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

## The lane fork (ELT Phase 6 slices A–C2, 2026-08-29)

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

Three mechanisms a change here must respect:

1. 🔴 **`writeAndTrace` has four callers and two write a batch in SEVERAL calls** (one per chunk, one
   per segment), reusing the same sink ids against ONE shared branch ledger. Each caller passes a
   **write scope**; `BranchCommitCoordinator` records `<scope>::<branch>` while handing the bare id
   to the writer. Whole-batch callers pass `""` and record exactly the keys they always did, which
   is what keeps the drain — reading bare sink ids back out — and the single-log cleanup working.
2. 🔴 **Decision rules are a space-registry fact, not a config property**, so the admission cannot
   see them statically. `DecisionRuleApplier.apply` runs ONCE above the fork and its RESULT is part
   of the admission: a rule that actually routed rows keeps the pipeline flat.
3. 🔴 **`dirs.temp` is optional, and the graph lane keeps a DURABLE ledger.** All three sites that
   spell the log path — create, drain-resume, cleanup — go through
   `ConsignmentIngestStrategy.branchCommitLogPath`, and a pipeline without a scratch dir stays flat rather
   than parking that ledger in a shared `%TEMP%`, where a stale `branch_commit_<batchId>.log` makes
   the coordinator skip the branch and the batch writes NOTHING.

Parity is proven, not asserted: `FlatVsGraphLaneParityTest` runs one materialised table through both
lanes and diffs output files, partitions, rows on disk, the lineage matrix and event-time bounds on
each admitted shape — and the whole reactor now exercises simple pipelines through the graph lane on
every run.

## The engagement predicate

`ConsignmentGraphRunner.dataFedSinkCount` counts **branches**, not sink nodes: distinct `route:*`
relations reaching a SINK-category node, plus one for the trunk when any sink is plain-`data`-fed.
So: flat single-sink = 1 · plain `sinks[2]` fan-out = 1 (N destinations of ONE branch — stays on
`writeAndTrace`'s flat fan-out with its reference-versioning and decision rules) · a two-branch
route = 2 · multi-schema selector = 1 (its `route:*` rels terminate at map nodes, not sinks).
⚠ The original node-count predicate engaged for plain fan-out and was refuted by its own
falsification test (`ConsignmentGraphRunnerLiftEngagementTest`); a stale `PipelineLiftTest` pin had
encoded the wrong belief. ⚠ And do not read this predicate as a claim about capability: it answers
*"is there a second BRANCH worth diverting for?"* for the route lane. Whether the graph lane can
perform a given write is `graphLaneCarries`' question, and a plain fan-out — one branch, N
destinations — is carried there since slice B.

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
