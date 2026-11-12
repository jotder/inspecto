# Space tests — cases that travel with a Space (SE-6a)

**State:** built 2026-11-12 (SE-6a of the Space evolution plan, §4.6). Promotion (SE-6b) is not part of this.

## 1. What a case is

A **case** is one folder under `spaces/<id>/tests/<case>/`. It holds a case file, `case.toon`, and the
sample input files the case feeds to one Pipeline. The folder sits **beside** `config/`, never inside it:
Pipeline discovery (`*_pipeline.toon` under the config root) and `ServiceBootstrap.resolveBySuffix` walk only
the config root, so no loader ever reads a case. Sample input is synthetic only.

```
pipeline: orders
inputs[3]: ORDERS_20260701.csv,ORDERS_20260702.csv,ORDERS_20260703.csv
expect:
  rows: 34
  rejects: 2
  checksum: "<md5, optional>"
  columns[1]{column,check,value}:
    REGION,not_null,""
```

| Key | Meaning |
|---|---|
| `pipeline` | the registered Pipeline id the case runs |
| `inputs` | file names, relative to the case folder; each is jailed to it |
| `expect.rows` | rows the run would land (the ingest's written row count) |
| `expect.rejects` | rejected records: the parse's error rows summed over every input file |
| `expect.checksum` | optional; md5 over the landed rows, sorted, so row order does not matter |
| `expect.columns` | optional per-column checks: `not_null` (value unused), `distinct` (count of distinct values), `min`, `max` (compared as text) |

A case name and every input are plain names (`ImportPaths.segmentRefusal`) and must resolve inside the case
folder (`PathJail.contains`). A case that names a file outside its folder is malformed, never run.

`case.toon` is an **allowed** fixed name, not a reserved one (`ImportLoaderInventoryTest.ALLOWED`, 2026-11-12):
an import writes only under `config/` (`ImportPaths.realPathRefusal` jails it there), and `tests/` is that
folder's sibling, so an import cannot plant a case; and a case only ever runs into a deleted scratch root.

## 2. How a case runs

`SpaceTestRunner` (in `com.gamma.service`, beside `ImportPaths` whose jail it reuses) runs each case through
**`PipelineTestRun.run`** — the run-to-here engine, not a second one. That path:

- copies the inputs into a fresh scratch root in the JVM temp dir (`spacetest_*`) and re-roots every destination
  of the Pipeline there (`PipelineConfig.forScratchRun`); backup and markers are disabled;
- runs only the ingest pass — never `commit`, `writeAudit` or `recordProvenance`, so the acquisition ledger,
  output registry, file stages, Signals and provenance are not touched; the pass's ambient Signals go to a
  contained log;
- never reads the inbox and never marks a file processed.

The checksum and column checks read the scratch Parquet outputs, then the scratch root is deleted. Nothing is
written under the Space's `data/`, `audit/` or `duckdb/`, nor under `tests/`.

⚠ One exception is inherited from the engine: `forScratchRun` does not re-root
`processing.duckdb.temp_directory`. A Pipeline that sets it gets DuckDB spill files (and the branch commit log)
there during a case. No shipped Pipeline sets it inside a Space.

Each expectation produces one line: `name`, `expected`, `actual`, `passed`. A case passes when every line passes.
A case that cannot run (unknown Pipeline, a batch that FAILED) fails with that reason, it does not error the call.

## 3. Routes

| Route | Capability | Gates, in order |
|---|---|---|
| `GET /spaces/{id}/tests` | none (a read) | 503 no config root · 200 `{cases:[{name, pipeline, error?}]}` |
| `POST /spaces/{id}/tests/run` | `canAuthorWorkbench` | 401/403 · 503 no config root · 400 body `cases` not a list of strings · 403 a case name that is not a plain name · 404 unknown case · 422 a malformed case file · 200 `{passed, total, failed, cases:[…]}` |

The body is optional: `{"cases":["orders_ingest"]}`; no body runs every case. A failing case is still 200 —
the result is the answer. The Space is the one the `/spaces/{id}` prefix binds; its `tests/` folder is the
sibling of its config root.

## 4. The Job type `space.test`

Runs the cases of its Space (`cases` parameter: comma-separated names; all when unset). Pipelines are loaded from
the Space's own `*_pipeline.toon` files with `PipelineConfig.loadForValidation` (no write). On any failed case it
emits `space.test.failed` (WARN) with the failed case names; it always emits `space.test.completed`. A dry run
runs the cases too: running them is itself side-effect free.

The type is contributed by the host module through `META-INF/services/com.gamma.job.JobTypeProvider`, like
`expectation.evaluate`, so `GET /jobs/types` reports its `source` as `classpath`, not `builtin` (by design:
the engine module cannot see `SpaceTestRunner`). It resolves its Space with `SpaceConfigRoot.requireCurrent`,
where a Space's registered config root wins over `-Dassist.write.root`.

## 5. Deliberately not covered

- **Stage 2** — Enrichments, Jobs and Alert Rules downstream of the landed rows are not run.
- **Acquisition** — the Collector, remote connections, duplicate and sequence-gap checks; a case starts at files.
- **Per-sink split** — a Pipeline with several sinks is checked over all of its outputs together.
- **Dataset-fed Pipelines** (`collector: dataset`) have no input files and are refused as a case.
- **Promotion** (SE-6b) and any UI.
