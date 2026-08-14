# Pipeline test run (run-to-here)

**What it is:** a bounded, scratch-only run of an authored Pipeline over the user's **real** inbox
files — the "Test" in the Build → Test → Run authoring journey. Shipped 2026-08-14
(`1f0937ee`, `141caf84`, `0b2a80ba`, `0c542829`).

`POST /pipelines/authored/{id}/run?to={nodeId}` · `canAuthorWorkbench` · returns the UI's
`PipelineRunResult`.

It is a **simulate**, which is why it is author-gated and why the path is deliberately *not*
`…/trigger` — that sibling is the operate verb (`canOperateRuns`) and fires a real run. See
[`../pipeline-graph/live-execution.md`](../pipeline-graph/live-execution.md).

## Why it is safe: two independent containments, both structural

A test run that mutates production state would be worse than no feature at all, so containment is by
construction rather than by a flag anyone can forget.

**1 · Call-graph containment.** `BatchProcessor.process` is, in order: `strategy.ingest(...)`, then
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

⛔ **If a fourth side-effecting statement is ever added to `BatchProcessor.process`, do not mirror it
into `PipelineTestRun`.** The omission *is* the safety property.

**2 · Filesystem containment.** Picked files are **copied** into `scratchRoot/poll`, and the run
executes against `PipelineConfig.forScratchRun(scratchRoot)`, which re-roots every destination.

⚠ **The copy is not an optimisation to remove.** `CsvBatchStrategy` quarantines an unreadable /
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

## Known gap: the `to=` cutoff is unbuilt

There is no partial-graph primitive — `PipelineExecutor.dryRun` walks `topoOrder(g)` over the whole
graph. So the run covers everything and **says so in `warnings`** rather than echoing `toNode` as if it
had stopped, which would paint the canvas green on nodes that were never bounded.

⚠ The offline mock **does** truncate (`subgraphTo`), which made it *more* capable than the server, so it
now appends the same warning — a mock that over-promises is the failure mode that convention exists to
kill. **When the cutoff lands, remove that warning from `pipelines.handler.ts` and
`PipelineRoutes.testRun` together.** Plan of record:
[`../../../superpower/pipeline-build-test-run-gaps.md`](../../../superpower/pipeline-build-test-run-gaps.md) Step 5b.

## Testing note worth keeping

Both safety properties were **falsification-probed**, and one probe changed the test:

- Removing the staging copy did **not** fail an assertion that merely checked "the inbox file still
  exists" — `QuarantineManager`'s own poll-root guard threw instead of moving, so the file survived for
  an unrelated reason. The test now pins containment **positively**: the staged copy must be found
  quarantined *inside* the scratch root.
- Replacing the jail with a plain `resolve()` turns the escape test red, and the failure output is the
  vulnerability in the clear — `../secret.csv` read, parsed, and its contents returned in the response.

**Re-run those probes rather than trusting a green suite** when touching staging or the jail.

## Code

- `inspecto-engine/…/inspector/PipelineTestRun.java` — `run`, `sampleRows`, `deleteScratch`
- `inspecto-etl/…/etl/PipelineConfig.java` — `forScratchRun(Path)`
- `inspecto/…/control/PipelineRoutes.java` — `testRun`, `testRunRoot`, `graphFor`, `fileList`, `runResult`
- `inspecto-acquire/…/acquire/LocalConnectionWorkbench.java` — `jail(Path, String)`
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (5, real HTTP)
