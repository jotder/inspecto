---
type: Concept
title: Pipeline test run (run-to-here)
description: The Test step of the Build/Test/Run journey — two independent structural containments, the `files` jail, the two grains in the response, and the `to=` cutoff.
resource: inspecto/src/main/java/com/gamma/control
tags: [test-run, run-to-here, containment, jail, authoring]
timestamp: 2026-08-14T00:00:00Z
---

# Pipeline test run (run-to-here)

**What it is:** a bounded, scratch-only run of an authored Pipeline over the user's **real** inbox
files — the "Test" in the Build → Test → Run authoring journey. Shipped 2026-08-14
(`1f0937ee`, `141caf84`, `0b2a80ba`, `0c542829`).

`POST /pipelines/authored/{id}/run?to={nodeId}` · `canAuthorWorkbench` · returns the UI's
`PipelineRunResult`.

It is a **simulate**, which is why it is author-gated and why the path is deliberately *not*
`…/trigger` — that sibling is the operate verb (`canOperateRuns`) and fires a real run. This page owns
the **scratch lane** mechanism; the lane map is
[`../pipeline-graph/execution-lanes.md`](../pipeline-graph/execution-lanes.md), the operate lane
[`../pipeline-graph/live-execution.md`](../pipeline-graph/live-execution.md).

## Why it is safe: two independent containments, both structural

A test run that mutates production state would be worse than no feature at all, so containment is by
construction rather than by a flag anyone can forget.

**1 · Call-graph containment.** `ConsignmentIngestor.process` is, in order: `strategy.ingest(...)`, then
`commit(...)`, then `writeAudit(...)`, then `recordProvenance(...)`. `PipelineTestRun` calls **only the
first**. This is the load-bearing decision, because **five destinations are not derived from the config
at all** and so could never have been redirected by one:

| Destination | Resolved by | Lives in |
|---|---|---|
| Acquisition ledger (dedup fingerprint, DB-export watermark) | `-Dacquire.ledger.backend` / `.db.url` | `finalizeSource` (inside `commit`) |
| Consignment output registry | `-Dconsignment.outputs.backend`, per-space registry | `finalizeSource` |
| File stages | `-Dfile.stages.backend`, per-space registry | `finalizeSource` |
| `pipeline.batch.*` Signal | ambient `EventLog.current()` (space MDC) | `writeAudit` |
| Provenance matrix | process-wide registry | `recordProvenance` |

⛔ **If a fourth side-effecting statement is ever added to `ConsignmentIngestor.process`, do not mirror it
into `PipelineTestRun`.** The omission *is* the safety property.

**2 · Filesystem containment.** Picked files are **copied** into `scratchRoot/poll`, and the run
executes against `PipelineConfig.forScratchRun(scratchRoot)`, which re-roots every destination.

⚠ **The copy is not an optimisation to remove.** `CsvIngestStrategy` quarantines an unreadable /
field-mismatched / empty member through `QuarantineManager.quarantine`, which does a **`Files.move` of
the source file** — from *inside* the ingest half. Redirecting `dirs.quarantine` does not help, because
the source is the problem: run against the real inbox and **testing a malformed file would delete it
from the user's inbox**. (A hardlink would also work on a single filesystem but fails across volumes —
a later optimisation behind a fallback, never the default.)

🔴 **Re-rooting the sinks means re-rooting the `route:` branches with them** (`ROUTED-WRITE-COUNTS-PER-BRANCH-1`,
fixed 2026-09-23). Branch↔sink pairing is **by the branch's declared `database`**
(`PipelineLift.branchKeyForDatabase`). While `forScratchRun` moved the sinks under the scratch root and
left the branches naming production directories, the lift paired **nothing**, fell through to a plain
data edge per destination, and the test run silently **degraded to a fan-out: every sink received every
row**. It reported `rowsWritten` = rows × branches — 9 → 18 on `route_step`, 12 → 36 on `premed_events`,
the branch-count factor that named the defect — and showed a builder the whole feed under each branch.

⚠ **The counts were the symptom; the content was the damage.** A fan-out and a correct route agree on
`outputs().size()`, so any test that asserts only the shape of the result passes against both. Assert
the per-destination **content** (`RouteIngestEndToEndTest.aTestRunOfARoutedPipelineRoutesRatherThanFanningOut`).
The general rule this is an instance of: **anything `forScratchRun` re-roots, every config key that
JOINS on that value must be re-rooted in the same step** — `route.branches[].database` is the only such
join key today, and a second one would fail the same silent way.

The ingest half touches exactly five dirs — `poll`, `database`, `errors`, `quarantine`, `temp` — plus
each `sinks[].database` on fan-out. `forScratchRun` additionally **nulls the commit-half destinations**
(`backup`, `markers`, the status/batches/lineage CSVs, manifests, commit log) even though the commit
half is never reached: defence in depth, so a future caller that *does* call it still cannot write to
production. `backup == null` is what makes the source-file backup a no-op.

Building the batches directly also bypasses `CollectorProcessor`, so the dedup/marker layer never runs
and a test run cannot mark a file as already-processed.

## The `files` jail

The body is caller-supplied, so without containment this route is an arbitrary-file-read over HTTP.

- Entries are **connection-relative** — the picker fills them from `GET /connections/{id}/explore`,
  whose `ResourceNode.path` is relativized against the profile's `base_path`.
- The jail root is **derived server-side** from the pipeline's own `source.connection` profile
  (`dirs.poll` when it binds none). The request carries no connection id, so this is enforceable by
  construction. ⛔ **Never accept a client-supplied root.**
- Containment reuses **`LocalConnectionWorkbench.jail(Path, String)`** — the *same* primitive the picker
  uses, so the two surfaces cannot disagree about what is reachable. A picker that allows X beside a
  runner that allows Y is how this class of hole appears. An escape is `PathEscape` → **403**.
- ⚠ Not to be confused with `ConfigSafetyValidator.checkPathValue`, which is **advisory** (collects
  `Finding`s at authoring time) rather than enforcing.
- Non-`local` connectors are **501** — there is no local path to stage from; those files reach the
  inbox via acquisition first.

## Response, and the two grains in it

`PipelineRunResult` = `{seedNode, toNode, files[], relations[], output|null, warnings[]}`.

⚠ **`relations[]` counts the seeded sample; `output.rowCount` is the full parse.** The seed is bounded
(`TEST_RUN_SEED_ROWS` = 1000) because `PipelineDryRun` is in-memory and a picked file is unbounded. A
warning names the difference whenever the two can disagree, so neither number is quietly mistaken for
the other. Per-file quarantine outcomes also surface as warnings — the operator is told a file *would*
be quarantined even though nothing moved.

`relations[].{node, rel, rowCount}` is load-bearing: the canvas marks a node ✕ on
`rel === 'unmatched' && rowCount > 0`.

## The `to=` cutoff (2026-08-14)

`PipelineExecutor.dryRun(..., stopAtNodeId)` bounds the walk; `PipelineDryRun.run` and
`PipelineGraphRoutes.testRun` thread `to` down to it. `null` means the whole graph, which is the only shape any
production caller passes.

⚠ **The bound is the ancestor closure of the target, not a prefix of `topoOrder`.** Topological order is
arbitrary between sibling branches, so truncating it runs whichever branch happens to sort first and reports
counts for nodes the operator never asked about — which the canvas then marks ✓. `ancestorsOf` walks edges
backwards instead (skipping `on_commit`, which is a cross-flow trigger rather than a data dependency). *(Until 2026-09-10 this
added that the offline mock's `subgraphTo` matched, "so mock and server agree" — that mock was deleted
2026-08-31, so the server's walk is the only definition of what a run-to-here covers.)*

⚠ **`execute` was not touched.** The plan expected the cutoff to thread through a walk shared with the
production executor; in fact `execute` and `dryRun` are separate loop bodies sharing only the private
`topoOrder` helper, so the production path is untouched rather than merely defaulted.

Two boundaries worth keeping straight:
- **A cutoff bounds the preview, not the parse.** The picked files are always parsed in full, because the
  parse is what seeds the walk. `to=` narrows the answer, never the work.
- **Bounded-on-purpose is not "nothing would be written".** A cutoff above every sink leaves `sinks` empty,
  which deliberately does *not* trip DRYRUN-2's "no sink received any rows" warning — that one requires a
  non-empty `sinks` list.

An unknown `to=` throws → **400**, rather than silently widening to the whole graph.

## A SQL failure reads as the real error first (2026-09-23)

A step whose SQL is wrong 422s with *“test run failed: Binder Error: Referenced column "EVENT_TS" not
found … Candidate bindings: "EVENT_DATE" …”* — the actionable error first, the column and the candidates
kept verbatim. `POST …/dry-run` does the same (*“dry-run failed: …”*).

⚠ **Why there was anything to strip:** DuckDB's JDBC driver (1.5.2.1) runs `Statement.execute(String)` as a
pending query without checking whether preparing it failed, so a bind failure arrives as ONE native message:
*“Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result”*, a newline,
`Error: `, then the real error. The `SQLException` carries **no cause, no suppressed, no SQLState** — there
is no "real exception" to unwrap. `Connection.prepareStatement` on the same SQL reports the Binder Error
cleanly, which is how the cause was confirmed; switching the walk to prepared statements was **declined**
because the dry-run shares its SQL execution with the production executor.

✅ **One seam:** `DuckDbUtil.withoutPendingQueryPreamble` removes exactly that driver text and nothing else.
Used by `testRun`, `dryRunFlow` and `POST /components/transform/describe` (which carried its own looser
copy until now). `DuckDbPendingQueryPreambleTest` runs the real driver, so a DuckDB upgrade that rewords
the preamble goes red instead of letting it silently back in. Row `TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1`.

## A `route:<segment>` edge out of a parser IS walked (D5, signed 2026-09-22)

✅ **Decision (operator, 2026-09-22):** the test run **walks segment routes**. The alternative — declaring
segment-routed frontends out of scope with a named 422 — was considered and declined, because it would
leave 2 of 8 parse frontends, and every multi-record-type feed, with no test instrument past the decoder.

✅ **AS-BUILT since 2026-09-22 (`WB-08`), verified live 2026-09-23.** The dry-run seeds the parse node
with **one relation per segment** — `PipelineDryRun.runSeeded` takes `rel → rows`, materialises one
scratch table each, and `PipelineExecutor.dryRun` accepts a `rel → table` seed map. `liveInbound` then
follows the existing edges unchanged.

⛔ **The walker is NOT special-cased, and must not become so.** A second traversal rule for `route:` is
how a preview and a real run begin to disagree. The fix was the SEED SHAPE, which is what `D5` decided.

⚠ **Seed keys are the graph's own edge relations**, built with `PipelineRel.route(...)` over
**`PipelineLift.routeKey`** — made public for exactly this. ⛔ Never re-derive that sanitisation: it is a
name that has to match the edge exactly, and a second copy is free to drift.

⚠ **A segment that routed nothing is ABSENT from the result, not present with zero.** *Did not run* and
*ran and produced nothing* are different answers and the canvas renders them differently, so an empty
segment is never seeded with an empty table.

**Measured live 2026-09-23** against a running control plane over real bytes: `asn1_example` (BER)
returns `map_moCallRecord · data · 3 rows` carrying the decoded values and writes 3 rows under the
`moCallRecord` partition; `xml_example` (plugin ingester) returns `map_order · data · 3 rows`.

🔴 **What it was:** both returned `relations: []` with the honest DRYRUN-2 warning *“the sample reached
no node past the seed 'parse' — nothing downstream consumed it”*. The seed only ever wrote `data`, so the
walk could not leave a `parse →(route:<segment>)→ …` parser at all: two of eight parse frontends, and
every multi-record-type feed, had no test instrument past the decoder. ⚠ The plan briefly recorded this
as blocked on unrecoverable attribution — wrongly: `IngestOutcome.schemaByOutput` (output file → segment)
had carried it since the union-mode ingester wrote it; nothing was reading it out. Pinned by
`SegmentRoutedDryRunTest`; row `TESTRUN-SEGMENT-ROUTE-NO-FLOW-1`.

## Testing note worth keeping

Both safety properties were **falsification-probed**, and one probe changed the test:

- Removing the staging copy did **not** fail an assertion that merely checked "the inbox file still
  exists" — `QuarantineManager`'s own poll-root guard threw instead of moving, so the file survived for
  an unrelated reason. The test now pins containment **positively**: the staged copy must be found
  quarantined *inside* the scratch root.
- Replacing the jail with a plain `resolve()` turns the escape test red, and the failure output is the
  vulnerability in the clear — `../secret.csv` read, parsed, and its contents returned in the response.
- **The cutoff probe caught a bad test before it caught bad code.** Swapping `ancestorsOf` for a truncated
  `topoOrder` left the headline "a sibling branch does not run" test **green**: Kahn's order for the fork
  fixture is `acq, left, right, …`, so bounding at `left` gives the right set by coincidence. Only bounding
  at the sibling that sorts **later** discriminates, which is what the test does now.

**Re-run those probes rather than trusting a green suite** when touching staging, the jail, or the cutoff —
and when a probe leaves a test green, suspect the test, not the probe.

## Code

- `inspecto-engine/…/inspector/PipelineTestRun.java` — `run`, `sampleRows`, `deleteScratch`
- `inspecto-etl/…/etl/PipelineConfig.java` — `forScratchRun(Path)`
- `inspecto/…/control/PipelineGraphRoutes.java` — `testRun`, `testRunRoot`, `graphFor`, `fileList`, `runResult`
- `inspecto-acquire/…/acquire/LocalConnectionWorkbench.java` — `jail(Path, String)`
- `inspecto-engine/…/pipeline/exec/PipelineExecutor.java` — `dryRun(…, stopAtNodeId)`, `ancestorsOf`
- `inspecto-engine/…/pipeline/exec/PipelineDryRun.java` — `run(…, stopAtNodeId)`
- `inspecto-util/…/util/DuckDbUtil.java` — `withoutPendingQueryPreamble`
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (7, real HTTP),
  `PipelineDryRunTest` (15, of which 5 pin the cutoff)
