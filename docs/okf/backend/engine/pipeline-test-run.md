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
- A **Dataset-fed** Pipeline (`collector: dataset`) is **501** too, naming the Dataset (`WB-07`,
  2026-09-22). It used to answer a `{files:[…]}` run with 200 *“no rows were parsed”* — an empty
  success where the honest answer is a refusal. Row `TESTRUN-DATASET-COLLECTOR-SILENT-1`.

## Response, and the two grains in it

`PipelineRunResult` = `{seedNode, toNode, files[], relations[], output|null, warnings[]}`.

⚠ **`relations[]` counts the seeded sample; `output.rowCount` is the full parse.** The seed is bounded
(`PipelineTestRun.SEED_ROWS` = 1000 raw rows per segment) because `PipelineDryRun` is in-memory and a picked file is unbounded. A
warning names the difference whenever the two can disagree, so neither number is quietly mistaken for
the other. Per-file quarantine outcomes also surface as warnings — the operator is told a file *would*
be quarantined even though nothing moved.

`relations[].{node, rel, rowCount}` is load-bearing: the canvas marks a node ✕ on
`rel === 'unmatched' && rowCount > 0`.

The sibling `POST …/dry-run` takes `{sampleRows:[…], pipeline?}`. A body of the wrong JSON shape (a bare
array is the natural first guess) is **400**, not 500 — fixed at the shared `ControlApi.body` seam
(`WB-06`, 2026-09-22), because the 500 was every POST route's defect, not this route's; a route's own more
specific 400 still reaches the caller. Row `DRYRUN-MALFORMED-BODY-500-1`.

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

**Every other 422 built from such a failure adopts the same seam (2026-09-23, `DUCKDB-PREAMBLE-OTHER-422S-1`).**
Probed on the real driver: the preamble appears on **any plain `Statement`** — `execute(String)` AND
`executeQuery(String)` — for a failure found while binding (Binder, Catalog, a table function's `IO Error:
No files found`), and **never** on a `PreparedStatement`, a Parser Error or an execution-time error (e.g.
Conversion Error). `QueryExecutor.run` registers the dataset view on a plain `Statement` and runs a query
with no binds on `SqlSandbox.statement()`, so every route over it could carry it. Adopted at: `BiRoutes`
(`/bi/query`), `QueryRoutes` (`/queries/{id}/run`), `ShareRoutes` (`/public/dashboards/{t}/query`),
`RuleRoutes` (`/rule-templates/{id}/simulate`), `DbBrowserRoutes` (`/db/query` + `/db/table`, both the
store and the `ops:` group — `BrowsableStore.exec` is a plain `Statement` too), `EnrichmentRoutes`
(`/enrichment/preview`), `ExpectationRoutes` (`/expectations/{name}/evaluate`), `ReconRoutes` (all four),
`ViewRoutes` (`/views/{name}/data`), `RouteErrors.mapPreviewErrors` (`/components/{transform,grammar}/…/preview|test`),
and in `inspecto-geo-link` `GeoRoutes` (`/geo/projection|routes`, the `SQLException` arm only) and
`InvRoutes` (projection, multi-projection, traversal). One real-HTTP test per route family asserts the
message **starts with** the real error; each went red against the unstripped message before the fix.

⚠ **Not yet adopted elsewhere:** four more routes (`BiRoutes`, `DbBrowserRoutes`, `EnrichmentRoutes`,
`ExpectationRoutes`) build a 422 from a raw DuckDB message and probably carry the same preamble —
unverified, reproduce per route before adopting the seam. Row `DUCKDB-PREAMBLE-OTHER-422S-1`.

## Defects found by the demo lanes (reported 2026-09-23)

Found by the lanes that built the domain demos (`gl_journal`, `stock_movements`, `msc_cdr`,
`in_recharges`). Each cause is the lane's hypothesis until someone reproduces it.

✅ **The seed is the PARSER's raw rows, captured before mapping** (`TESTRUN-SEED-IS-MAPPED-OUTPUT-1`, fixed
2026-09-23). **Decision (operator, 2026-09-23): seed the preview with the RAW parsed rows, so it runs the same
mapping a real ingest does.** The alternative — seed downstream of `map` and skip it — was declined: it would
leave the mapping, the step most likely to be wrong, as the one step a test run never exercises.
- 🔴 **What it was:** `PipelineTestRun.sampleRowsBySegment` read back the rows the ingest **wrote** — already
  mapped — and `testRun` passed them to `PipelineDryRun.runSeeded` as the parse node's output, so the walk
  re-applied `map` / `map_<segment>` to canonical columns. **Loud:** `in_recharges` (`AMOUNT_MINOR`) and
  `msc_cdr` (`EVENT_TIME`) refused 422 with a binder error, so the test run could not reach route or sinks.
  **Silent, and worse:** `premed_events` answered 200 with every `EVENT_TS` NULL — the written TIMESTAMP,
  stringified by JDBC and re-parsed by the `keep`'s format list, failed the format and became NULL.
- **The seam:** `DataTransformer.RAW_INPUT`, a `ScopedValue<RawInputObserver>` checked at the top of
  `DataTransformer.materialize` — the ONE point all six ingest lanes (native single / chunked / union, the
  Java parse lane, both plugin modes) funnel through. `PipelineTestRun.run` binds it around
  `strategy.ingest(...)` and samples `SELECT * FROM <raw source> LIMIT n` (up to `SEED_ROWS` = 1000 per
  segment, `__src_id` dropped) into `Result.rawRows()`. The segment is found from the schema map the
  union-mode ingester passes (identity, then value) — never from table names. Unbound, i.e. every production
  run, it is one `isBound()` check. ⚠ A scoped value, not a parameter, because a parameter would have to be
  threaded through all six lanes; ⚠ it does not cross threads, which is fine only because every lane calls
  `materialize` on the ingest thread.
- ⚠ **The sample re-scans the raw relation** (a lazy `read_csv` view on the native lanes). Production already
  scans it twice (`materialize` + `countCastFailures`), so this adds a third bounded scan in a test run only.
- ⚠ **An exception thrown by the observer is the ingest's exception** — on the single-member native lane that
  reads as the member being unreadable. The sampler is a plain `SELECT … LIMIT`, so this is theoretical today.
- Pinned by `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos, each compared against a
  REAL ingest of the same committed sample): `premed_events` — all 12 `EVENT_TS` equal to the written values,
  route voice·sms·other `5·3·4` as written; `in_recharges` — 14 `AMOUNT`s equal to the written ones, route `10·4`; `msc_cdr` —
  each `map_<segment>` row count and `EVENT_TS` equal to the written segment. ⚠ A previewed TIMESTAMP comes back
  as epoch millis of a HOST-zone `java.sql.Timestamp`; reading it as UTC is off by the host offset.

✅ **A failed batch is reported as the failure** (`TESTRUN-FAILED-BATCH-REPORTED-EMPTY-1`, fixed 2026-09-23). A
`FAILED` `PipelineTestRun.Result` now answers **422** *“test run failed: the batch FAILED after N row(s) parsed:
&lt;the batch's error&gt;”* before any preview runs, and `RunToHereDialog` renders it in its error alert (it already
surfaced any error body through `apiErrorMessage`; `run-to-here.dialog.spec.ts` pins the rendered alert). 🔴 With
the raw seed alone the hole got WORSE, not better: a failure outside the mapping — a `partitionKey` naming an
absent column fails the real transform, while the preview's map projects mapped columns only — answered 200
with a clean preview and no warning. Pinned by
`ControlApiPipelineTestRunTest.aBatchThatFailsAfterParsingIsReportedAsTheFailureNotAsEmpty`, which uses TWO files
because only the multi-member lane fails the BATCH on a transform error.
⚠ **The single-member native lane files a TRANSFORM failure as the input being unreadable**: `streamingIngest`
holds `streamUnit`'s `materialize` inside the catch that quarantines `QUARANTINED_UNREADABLE`, so the same bad
`partitionKey` over one file quarantines a readable file — in production that moves it out of the inbox.
Reported as its own row; the write half was split out by `WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`, the
transform half was not.

✅ **A failed partition write fails the batch; it never quarantines the input**
(`WINDOWS-LONG-SCRATCH-PATH-QUARANTINES-1`, fixed 2026-09-23). A scratch path near 250 characters failed the
partition write on Windows, and the member was marked `QUARANTINED_UNREADABLE` although it read fine. The
cause was not Windows-specific: `NativeCsvStreamingEngine.streamingIngest` (the single-member native
`read_csv` lane) held the `read_csv`, the transform **and** the `writeAndTrace` in one `catch`, so any sink
failure became an unreadable-input verdict — and in a production run that verdict **moves a good file out
of the inbox**. `streamUnit` now wraps the write in `SinkFlushException` (the type the generation and union
lanes already use for the same distinction) and `streamingIngest` rethrows it, so the batch is `FAILED`
with *“partition write failed for …”* and nothing is quarantined. A genuine `read_csv` failure surfaces
before the write and is still `QUARANTINED_UNREADABLE`. Pinned by
`PipelineTestRunTest.aFailedPartitionWriteFailsTheBatchAndNeverBlamesTheInput`, which blocks the partition
directory with a regular file, so it runs on every platform. The route's scratch prefix is also shorter
(`itr_`, was `inspecto_testrun_`); the rest of a written path — the temp dir, the partition layout, the
file stem — is data and still counts toward the Windows limit. ⚠ A `FAILED` test run still answers
*“no rows were parsed”* — no longer: it answers 422 with the batch's error (above).

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

- `inspecto-engine/…/inspector/PipelineTestRun.java` — `run` (binds `RAW_INPUT`), `Result.rawRows`, `sampleRows`, `deleteScratch`
- `inspecto-etl/…/etl/DataTransformer.java` — `RAW_INPUT` / `RawInputObserver`, checked in `materialize`
- `inspecto-etl/…/etl/PipelineConfig.java` — `forScratchRun(Path)`
- `inspecto/…/control/PipelineGraphRoutes.java` — `testRun`, `testRunRoot`, `graphFor`, `fileList`, `runResult`
- `inspecto-acquire/…/acquire/LocalConnectionWorkbench.java` — `jail(Path, String)`
- `inspecto-engine/…/pipeline/exec/PipelineExecutor.java` — `dryRun(…, stopAtNodeId)`, `ancestorsOf`
- `inspecto-engine/…/pipeline/exec/PipelineDryRun.java` — `run(…, stopAtNodeId)`
- `inspecto-util/…/util/DuckDbUtil.java` — `withoutPendingQueryPreamble`
- `inspecto-engine/…/query/QueryExecutor.java` — `run` (the plain-`Statement` view registration and no-bind query)
- Tests: `PipelineTestRunTest` (8), `ControlApiPipelineTestRunTest` (8, real HTTP),
  `ControlApiPipelineTestRunDemoTest` (3, real HTTP over the shipped demos vs a real ingest),
  `PipelineDryRunTest` (15, of which 5 pin the cutoff)
