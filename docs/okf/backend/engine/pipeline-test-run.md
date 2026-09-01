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
backwards instead (skipping `on_commit`, which is a cross-flow trigger rather than a data dependency). This
matches the offline mock's `subgraphTo`, so mock and server agree on what a run-to-here covers.

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
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (6, real HTTP),
  `PipelineDryRunTest` (15, of which 5 pin the cutoff)
