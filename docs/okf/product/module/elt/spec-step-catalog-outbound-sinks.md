---
type: Concept
title: Step catalog — every Step kind, delivered and pending — Webhook and Excel sinks, and the non-chain processors
description: Part 4 of 5 of Step catalog — every Step kind, delivered and pending: webhook → sink.webhook — the outbound webhook; excel → sink.excel — the Excel workbook sink; The delivered processors that are not chain Steps — where their keys live
tags: [pipeline-graph, steps, catalog, processors, palette, examples, roadmap]
timestamp: 2026-09-06T00:00:00Z
---

# Step catalog — every Step kind, delivered and pending — Webhook and Excel sinks, and the non-chain processors

Part 4 of 5 of [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) · previous: [Binning to sink — data, privacy and routing Steps](spec-step-catalog-data-steps.md) · next: [Processor families, and keeping this page true](spec-step-catalog-processor-families.md).

## `webhook` → `sink.webhook` — the outbound webhook

*Catalog `sink.api.webhook`, DELIVERED 2026-09-23. Professional and Enterprise only.*

**Function.** POSTs the at-rest chain's output rows as JSON to an **https Connection**, `batch_size`
rows per request. Body: `{consignment, sink, batch, batches, rows: [{column: value, …}]}` (`batch` is
1-based). A value goes as a JSON number, boolean or string when it is one, and as its string form
otherwise (dates, timestamps). An empty relation sends nothing.

**Flat home.** The top-level `webhook:` block, carried verbatim as the node's config:

```
output_store: orders_out
webhook:
  connection: orders_hook
  batch_size: 250
  retry: {count: 3, backoff: EXPONENTIAL, initial_delay: 1s, max_delay: 30s}
```

The canvas palette offers it (catalog `addable`). The recipe verb `webhook:` compiles, and `RecipeConverter` projects it back. ✅ Nothing more is owed in the SPA: the Recipe view was deleted (`6d3c68fa`, 2026-09-18), and the lists it read — the served `GET /pipelines/step-types` catalogue, the client `RECIPE_VERBS` fallback and the mock lift/lower in `pipeline-editable.ts` (dead since `f1553136`) — were deleted 2026-09-24 (`STEP-TYPES-DEAD-CLIENT-MIRRORS-1`; `WEBHOOK-RECIPE-PALETTE-1` refuted 2026-09-23). `pipeline-editable.ts` keeps only `isProjectionSlot`.

**Egress (operator decision 2026-09-23).** ⛔ **There is no `url:` and no token key.** The block
names a Connection whose connector is `https`. Onboarding one needs the admin-only
`canOnboardConnections` grant, so a pipeline author can send rows only to an endpoint an administrator
already approved. The engine builds `https://<host>[:<port>]<base_path>`. Plain `http` is not a scheme
it can build. The bearer token is the Connection's `password`: a `SecretResolver` reference, resolved
at send time and never logged. The per-request timeout is the Connection option `timeout_seconds`
(default 10). A Connection that declares a **tunnel or proxy is refused**, because the sink dials
through neither, and quietly bypassing a proxy is worse than refusing. Redirects are never followed:
a 3xx fails the request. The parser refuses an authored `url:`/`token:`/`endpoint:` by name, and refuses
any unknown key rather than defaulting it.

**Lane.** At rest only. `PipelineLift.stageTwo` hangs `sink.webhook` as a **second commit branch**
off the chain's end, beside the `output_store:` sink. It is not downstream of that sink: both consume
the same relation. A webhook with no `steps:` still lifts ("send what landed"). `prepare()` refuses an
**active** pipeline carrying `webhook:` when it has no `output_store:`, a `route:`, or a multi-schema
layout, because on those lanes the webhook would never be reached. `IngestSinkWriter` throws by name
as a backstop.

**Failure semantics: fail the branch, like the persistent sink.** Each POST runs under `retry:`
through the Collector's own `RetryPolicy`. Every attempt carries the same
`Idempotency-Key: <consignment>:<sink>:<batch>` header, and a retry re-sends the identical body. When
a batch runs out of retries, the write throws, `BranchCommitCoordinator` records the branch as failed,
and the source is not finalised. ⚠ Batches the receiver has already accepted stay accepted, because
HTTP has no rollback. A re-run re-sends them under the same keys, so the receiver must de-duplicate
on the key. Keys stay stable across re-runs only as far as the relation's row order does, and the
sink adds no `ORDER BY`.

✅ **No Recipe-view gap** (`WEBHOOK-RECIPE-PALETTE-1`, refuted 2026-09-23): the `webhook:` verb compiles and
the canvas palette adds the node; the Recipe view whose client-side verb lists lacked it was deleted in `6d3c68fa`.

**Edition (EDG-01).** The engine defines the `WebhookSinkTransport` SPI and ships **no**
implementation. The one provider, `HttpWebhookSinkTransport`, lives in `inspecto-notify-channels`
next to `WebhookChannel` and reuses its POST (`WebhookChannel.send`). On Personal there is no
provider, so the write refuses and names the edition. A dry run (`DryRunSinkWriter`) resolves the
whole target the same way, so it refuses exactly what a real run would, then sends nothing. The **graph**
dry run and run-to-here (`PipelineDryRun`) call the same `WebhookSink.plan` for every webhook branch they
reach, so they refuse 422 the same way (G8, 2026-09-23; before, they showed the branch as healthy).
`package.ps1` fails the build if the jar loses the SPI entry.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `connection` | autocomplete | required | — | **Connection.** An https Connection. It carries the endpoint (host, port, path) and the bearer-token reference. |
| `batch_size` | number (1–10000) | optional | `500` | **Rows per request.** Rows sent in one JSON POST. |
| `retry.count` | number | advanced | `0` | **Retries per request.** Extra attempts after a rejection or timeout. Every attempt carries the same Idempotency-Key. |
| `retry.backoff` | select | advanced | `EXPONENTIAL` | **Retry backoff.** EXPONENTIAL · LINEAR · FIXED, full-jittered, capped at `max_delay`. |
| `retry.initial_delay` | duration | advanced | `1s` | **First retry delay.** |
| `retry.max_delay` | duration | advanced | `60s` | **Longest retry delay.** |

**Code.** `WebhookSink` (plan + deliver) · `WebhookSinkTransport` (SPI) · `PartitionSinkWriter` /
`DryRunSinkWriter` (dispatch) · `PipelineConfig.Webhook` · `providers/inspecto-notify-channels/…/HttpWebhookSinkTransport`.
Tests: `WebhookSinkTest`, `WebhookSinkLiftLowerTest`, `PipelineConfigWebhookTest`,
`HttpWebhookSinkTransportTest` (a JDK `HttpServer` stub), `NodeConfigNameContractTest#webhookAttributesReachTheEngine`.

## `excel` → `sink.excel` — the Excel workbook sink

*Catalog `sink.file.excel`, DELIVERED 2026-10-06. Every edition.*

**Design (operator, 2026-10-06).** The operator named `sink.file.excel`: write one or more relations as
named **sheets** of a single `.xlsx`, reusing the report job's workbook path (`ASSURE-XLSX-ATTACHMENTS-1`)
rather than duplicating it. As built:

1. **Node type `sink.excel`, flat home the top-level `excel:` block** — the same shape as `sink.webhook`
   (catalog id `sink.file.*`, node type `sink.<thing>`). A second commit branch beside the `output_store:`
   sink, at-rest lane only; `prepare()` refuses an active pipeline whose chain would never reach it
   (no `output_store:`, a `route:`, a multi-schema layout) and `IngestSinkWriter` throws by name as the backstop.
2. **"One or more relations" = one or more `sheets:`, each a read-only `sql` over the branch's relation
   (alias `input`)** — the `transform.sql` rule, checked by `SqlGuard` at load and at save. A sheet with no
   `sql` is `SELECT * FROM input`.
3. **One writer, extracted.** `ReportXlsx` moved to `com.gamma.pipeline.XlsxWorkbook` (public) and gained
   `writeSheets`: each sheet goes through the same sealed DuckDB connection (`excel` loaded explicitly, rows as
   bound parameters, configuration locked to the output directory) and the same formula neutraliser, then
   `XlsxSheetMerger` stitches the one-sheet parts — DuckDB's writer rewrites a file on every `COPY`. The
   report job calls the same class.
4. **Sheet names are REFUSED, not repaired** — Excel's rule: 1–31 characters, none of `[ ] : * ? / \`, no
   leading or trailing `'`, not `History`, unique ignoring case. (The report job still sanitises its one name.)
5. **Row cap per sheet, fail closed.** `max_rows` (default 100000, ceiling 1048575 = Excel's row limit minus the
   header). A sheet over the cap FAILS the branch; a sink never truncates silently.
6. **Path jail.** `path` is relative to the Space's data root, ends `.xlsx`, carries no `..` segment (refused at
   load), and is re-checked at write time with `PathJail.require` against the data root (symlinks included).
   The workbook is written beside the target and moved into place atomically; each run replaces it.
7. **Fail closed without the extension.** No loadable `excel` binary ⇒ the write throws with every remedy
   named (`ExcelExtension`), the branch fails and the source is not finalised.

**Flat home.**

```
output_store: orders_out
excel:
  path: reports/orders.xlsx
  max_rows: 50000
  sheets:
    - {name: Orders}
    - {name: By region, sql: "SELECT region, count(*) AS orders FROM input GROUP BY region"}
```

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `path` | string | required | — | **Workbook path.** Under the Space's data root, ends `.xlsx`; replaced on every run. |
| `sheets` | list of `{name, sql}` | required | — | **Sheets.** One sheet per entry, in order; `sql` is a read-only SELECT over `input` (blank = every row). |
| `max_rows` | number (1–1048575) | optional | `100000` | **Rows per sheet.** A sheet over the cap fails the branch. |

**Code.** `ExcelSink` (plan + write) · `XlsxWorkbook` (the shared writer) · `XlsxSheetMerger` ·
`PartitionSinkWriter` / `DryRunSinkWriter` (dispatch) · `PipelineConfig.Excel`. Tests: `ExcelSinkTest`,
`ExcelSinkLiftLowerTest`, `PipelineConfigExcelTest`, `XlsxWorkbookTest`,
`NodeConfigNameContractTest#excelAttributesReachTheEngine`. Demo: `inspecto/examples/07-steps/excel-sink`.

⚠ **Known edges.** A sheet whose query returns no rows is written with a single `(no rows)` header — the
shared writer takes its header from the rows, so the column names are lost. `sheets` has no form attribute
(it is a list of maps; the `list` widget is `string[]`): the drawer keeps it as a typed additional-config row.
The write-time symlink check (`ExcelSinkTest#aSymlinkOutOfTheDataRootIsRefusedAtWriteTime`) SKIPS on a
Windows host without symlink rights; the `..`/absolute refusals are pinned everywhere.

---

## The delivered processors that are not chain Steps — where their keys live

*Added 2026-09-23 (`PROCESSOR-RELEASE-READINESS-1` G11).* Part B lists four processors whose
*Maps to* is a capability or a companion config, not a `steps[]` kind — one DELIVERED
(`enrichment.reference`, a canvas node) and, since the 2026-09-24 release-readiness verdicts, three
PARTIAL (the three that are never on the canvas). None of them has an
`AttributeSpec` table in `node-attributes.contract.json`. Their keys come from the parser each names, and
the declared ones also from its `ConfigSpecs` spec.

### `enrichment.reference` → the `enrichment` node — a Stage-2 `*_enrich.toon`

**Function.** Runs hand-written SQL over a Pipeline's committed Stage-1 output joined to named
references, and writes an enriched store. The canvas `enrichment` node is **companion-persisted**: its
truth is the `*_enrich.toon`, which `PipelineEditable.lower` ignores
([pipeline-editor § The enrichment Step](user-guide-elt.md)).
Parser: `EnrichmentConfig.fromMap`. Spec: `ConfigSpecs.enrichment()`, censused one level down
(`AcceptedConfigKeys`, `EnrichmentKeyCoverageContractTest`).

| Key | Type · default | What it does |
|---|---|---|
| `name` | string · required | The enrichment's id. |
| `input.database` | data path · required | The Stage-1 Hive-partitioned output it reads. Relative = under the Space directory. |
| `input.format` | `PARQUET` · `CSV` · `PARQUET` | Format of that output. |
| `input.partitions` | list · required | Hive partition columns on the input; `[]` = unpartitioned. |
| `output.database` | data path · required | Where the enriched output is written. |
| `output.format` / `output.compression` | `PARQUET` · `CSV` · `PARQUET` / string | Output format and codec. |
| `output.partitions` | list · required | Output grain: bare names or `{column, source}` entries. One agreed `source` records event-time bounds. |
| `transform` / `transform_file` | SQL / path · one required | SQL over the `input` view and the named references. `transform_file` is read when `transform` is blank. |
| `references.<name>.path` / `.ref` | data path / id · exactly one | The view registered as `<name>`: a file, or a Reference Dataset by name. Parser-only (`ENRICHMENT_PARSER_ONLY`). |
| `references.<name>.format` | string · `PARQUET` | Format of a `path` reference. |
| `references.<name>.as_of` | ISO date or date-time | Read the Reference as of that instant. Needs `ref`; a `path` has no version history. |
| `triggers.on_pipeline` | string | Upstream Pipeline (or enrichment) whose commit triggers an incremental recompute. |
| `triggers.schedule_seconds` | integer | Interval of a full recompute; `<= 0` disables. |

### `quality.constraint.check` → Expectations

**Function.** An at-rest data-quality check. `ExpectationEvaluator` counts the violating records in the
target's at-rest Parquet. A FAILED check opens a deduplicated `expectation:<name>` Incident and emits
`EXPECTATION_FAILED`. It is authored through `/expectations*` and persisted as an `expectation` component
under `<write-root>/registry` (`ExpectationRoutes`). It is never a chain Step.
[ingestion § Expectations](plan-elt.md). Parser:
`Expectation.fromMap`. Spec: `ConfigSpecs.expectation()`, the same twenty-two keys (the last eight are the
`baseline` kind's — [observability § Baseline Expectations](../observability/plan-observability-ops-lens-decisions.md#310-baseline-expectations-duckle-c8-2026-09-24)).

| Key | Type · default | What it does |
|---|---|---|
| `name` | string · required | The check's name. |
| `description` | string | Free text. |
| `targetType` | `pipeline` · `job` · `pipeline` | Whose at-rest data is scanned. |
| `target` | string · required | That Pipeline or Job's name. |
| `kind` | `non_null` · `range` · `regex` · `referential` · `condition` · `baseline` · required | The constraint. The spec lists `non_null` as its default, but the record refuses an absent kind. |
| `column` | string | The checked column. Required for every kind except `condition` and `baseline`. |
| `min` / `max` | number | `range` bounds; at least one. |
| `pattern` | regex | `regex`: the value must match. |
| `refDataset` / `refColumn` | string | `referential`: the lookup relation and column; both required. |
| `when` | condition tree | `condition`: the violation predicate itself (`ConditionSql`). |
| `baselineWindow` | integer 1–100 · `7` | `baseline`: the median of this many most recent accepted profiles is the baseline. |
| `measures` | list · `[row_count]` | `baseline`: any of `row_count` `null_count` `null_rate` `distinct_count` `min` `max` `mean`. |
| `columns` | list | `baseline`: the columns the per-column measures apply to; required iff one is chosen. |
| `maxIncrease` / `maxDecrease` | number ≥ 0 | `baseline`: allowed rise / fall per cell; at least one. |
| `limitUnit` | `percent` · `absolute` · `percent` | `baseline`: percent of the baseline's magnitude, or the raw difference. |
| `groupBy` | list | `baseline`: profile each group separately. |
| `requireExistingGroups` | boolean · `false` | `baseline`: a group the baseline typically has, missing now, is a violation. Needs `groupBy`. |
| `severity` | `MINOR` · `MAJOR` · `CRITICAL` · `MAJOR` | Severity of the Incident raised. |
| `enabled` | boolean · `true` | Evaluate-all skips a disabled check. |

### `control.alert.dispatch` → Alert Rules

**Function.** `AlertService` sweeps the rules on a timer and on `POST /alerts/evaluate`. A breach emits
`ALERT_FIRED` and the `alert-rule.fired` Signal, which the notification reactor fans out to channels
([incidents § Alert Rules](../../addon/incidents/plan-incidents-specification.md#32-alert-rules),
[§ the default reactor](../../addon/incidents/plan-incidents-refused-verification.md#82-alerts-notifications-and-diagnosis--the-default-reactor)).
Rules are persisted as `alert-rule` components under `<write-root>/registry`, through
`/alerts/rules*` (`AlertRoutes`). Parser: `AlertRule.fromMap`, a flat map of the keys below. Spec:
`ConfigSpecs.alert()`, with `dataset`/`measure`/`when` parser-only (`ALERT_PARSER_ONLY`). One record,
four disjoint shapes, chosen by which keys are present:

| Key | Shape | What it does |
|---|---|---|
| `name` | all · required | The rule's name. |
| `metric` | ledger | `error_rate` · `failed_batches` · `rejected_files` · `duration_ms`, over the batches ledger. |
| `window` | ledger | `Ns`/`Nm`/`Nh`/`Nd` elapsed, or `Nb` = the last N batches. |
| `onPipeline` | ledger | Restrict to one Pipeline; blank = every Pipeline. |
| `when` | ledger | Condition tree scoping the ledger rows before the metric aggregates them. Refused on the other shapes. |
| `dataset` + `measure` | measure | A Measure (`count`, or `agg(field)` with agg = count · countDistinct · sum · avg · min · max · median · stddev · variance · p05 · p25 · p75 · p90 · p95 · p99) over a Dataset's current data. No `metric`, `window` or `when`. |
| `dataset` + `maximumAge` | freshness | The Dataset must have published within `Ns`/`Nm`/`Nh`/`Nd`. Comparator and threshold are fixed (`gt`, 0), and a measure, metric, window or `when` is refused. |
| `investigation` + `relation` + `measure` | investigation | A Measure over an Investigation's Working Set (`relation`: `entities` · `links` · `excluded`, default `entities`). Authored only through `POST /inv/investigations/{id}/alert-rules`. |
| `comparator` | all but freshness | `gt` · `gte` · `lt` · `lte`. |
| `threshold` | all but freshness | A positive number. `error_rate` is a fraction in (0, 1]. |
| `severity` | all | `INFO` · `WARNING` · `CRITICAL`. |

### `sink.quarantine` → the quarantine directory and the reject log

**Function.** Two outputs, and neither is a node the author adds. A file the engine cannot process
at all is moved to `dirs.quarantine`, under a reason sub-directory: `field_mismatch/`, `unreadable/`,
`corrupt_download/` or `retry_exhausted/`
([operations-reference § Quarantine](../../layer/platform/admin-guide-operations-reference.md#quarantine),
[output-sinks § Quarantine outcomes](spec-sinks.md#quarantine-outcomes)). The rows a
parse rejects drain to `<dirs.errors>/<base>_errors.csv`. The lift models a quarantine *node* (a
`sink.persistent` carrying `dir`, never `database`) only for selector/segments pipelines. Otherwise the
directory has no owning node, and a save preserves it.

| Key | Type · default | What it does |
|---|---|---|
| `dirs.quarantine` | data path · `<dirs.poll>/quarantine` | Quarantine root. The quarantine node's `dir`. Engine-read, not spec-declared (`dirs` is accepted whole). |
| `dirs.errors` | data path · `<dirs.poll>/errors` | Where `<base>_errors.csv` reject logs land. Engine-read, not spec-declared. |
| `processing.csv_settings.store_rejects` | boolean · `true` | Capture rejected rows. `false` skips capture, since reject rows carry raw source data. |
| `processing.csv_settings.rejects_table` / `rejects_scan` / `rejects_limit` | identifier / identifier / number | `read_csv`'s reject tables (`reject_errors` / `reject_scans`) and the per-file cap (blank or 0 = unlimited). |
| `-Dingest.retry.max` (system property) | number · `5` | Commit failures before a file is quarantined as `retry_exhausted`. `0` = retry without bound. |

---

# Part B — the Step Processor catalog (the palette's taxonomy)

The palette lists **122**<!--count:processors--> processors in eight families. A **delivered** processor is a label over a
Step kind or an engine capability that ships today; the *Maps to* column says which. A **partial**
processor has something real behind it, but not as a chain Step or not the whole promise — the
*What exists today* column is the honest boundary. A **planned** processor renders inactive in the
palette (*"Not yet available"*): no engine code, no config keys; adding one is a product decision
(a new Step kind needs a `BuiltinNodeType`, a `RowShaper` arm or a contributed `PipelineNodeExecutor`,
a `BuiltinNodeType.FlatHome` declaration on the enum case (since `NODE-TYPE-MIRRORS-1`, 2026-09-15, this is
where `PipelineEditable` derives LOWERABLE and STEP_KIND from — they are no longer hand-kept lists) plus the
lift/lower code that home names, and the runtime steps→graph seam — the
four registrations [catalog-vs-executors](spec-pipeline-model.md) names). The board's
edition cells and the two Enterprise-only rows live in `tools/render-processor-board.mjs`.

✅ **"Delivered" now means "releasable" — the bar is adopted** (`PROCESSOR-RELEASE-READINESS-1`; audit
2026-09-23, operator sign-off 2026-09-24). A DELIVERED processor meets seven points: **(1) documented** — its
keys and behaviour on this page or the concept it links · **(2) real-path test** — a test drives the real
engine path and asserts the written output · **(3) workbench preview / lift / lower** — it is on the canvas,
its config survives the graph save, and the dry run shows (or honestly refuses) it · **(4) UI palette** —
the palette offers it, or renders it inactive saying why · **(5) edition gating** — the build flavours ship
it where `EDITIONS.md` says (G9, judged separately) · **(6) fails clearly at save** — a bad value is refused
or warned at the save, not at load or run · **(7) demo** — a runnable `inspecto/examples/…` config or a
committed Space config, or, where infrastructure is unavoidable, an `examples/_reference/…` template held
true by `ShippedExamplesPassTheSaveGateTest`. A processor that cannot meet a point is **PARTIAL**, not
DELIVERED with a footnote. Earlier gap groups G1, G3–G8 and G11 closed 2026-09-23 (catalog↔docs drift, one
`SaveGate`, save-time step checks — [config safety](../config/spec-config-safety-roots-save-gate.md#step-config-checks-g4) —,
dry-run blind spots — [pipeline test run](spec-pipeline-execution.md)).

**Verdicts on the seven "questionably delivered" (2026-09-24).** ✓ met · ✗ not met · — no keys to get wrong ·
column 5 = edition gating (G9, closed 2026-09-24 — see *Edition notes* below): *all* = every edition, as the board says.

| Processor | 1 doc | 2 test | 3 workbench | 4 palette | 5 edition | 6 save | 7 demo | Verdict |
|---|---|---|---|---|---|---|---|---|
| `quality.schema.drift` | ✓ row below | ✓ `SchemaDriftIngestTest`, `SchemaDriftTest` | ✓ rides the parse node; the parse pane's drift check | ✓ inactive, "delivered as parser" (like `quality.schema.validator`) | ✓ all | — | ✓ `03-schema-transform/schema-drift` | **PASS** — detection only IS the promise (a refusal knob was refused 2026-09-10) |
| `control.throttle` | ✓ Collect table (`fetch.rate_limit` + `processing.intake`) | ✓ `RateLimiterTest`, `CollectorProcessorRemoteCycleTest`, `CollectorProcessorAdmissionCapTest` | ✓ acquisition form; round trip in `NodeConfigNameContractTest` | ✓ addable | ✓ all | ✓ rate grammar 422; inert on a local inbox warned | ✓ `05-acquisition/intake-throttle` · `_reference/sftp-collector` | **PASS** — `RATE-LIMIT-OVERSIZE-HANGS-1` fixed 2026-09-24 |
| `control.circuitbreaker` | ✓ Collect table | ✓ `CollectorProcessorRemoteCycleTest` (trips, skips while OPEN, half-open closes) | ✓ acquisition form; round trip | ✓ addable | ✓ all | ✓ durations/counts 422; inert on local warned | ✓ `_reference/sftp-collector`, `_reference/db-export` | **PASS** — ⚠ there is no *fallback*: a tripped breaker skips the cycle, it switches to nothing |
| `sink.archive` | ✓ Collect table (`post_action`) + [backup tasks](../processor/spec-jobs.md) | ✓ MOVE in `CollectorProcessorRemoteCycleTest`; `BackupTaskTest` | ✓ acquisition form; a dry run never applies a post-action | ✓ addable | ✓ Professional+, gated | ✓ unknown `on_success` 422; MOVE with no `archive_path` refused | ✓ `_reference/sftp-collector` | **PASS** — originals are moved (local: `dirs.backup`; remote: `post_action: MOVE`), and the `backup`/`backup_verify` tasks zip them with SHA-256 manifests |
| `acquisition.db.jdbc` | ✓ Collect table (`db` options) | ✓ exact sink rows (`CollectorProcessorRemoteCycleTest`, `DbExportConnectorTest`) | ✓ acquisition node | ✓ addable | ✓ all | ✓ connector option checks | ✓ `_reference/db-export` | **PASS** |
| `quality.constraint.check` | ✓ [section above](#qualityconstraintcheck--expectations) | ✓ `ControlApiExpectationTest` | ✗ authored in the Expectations pane, never on the canvas | ✓ inactive | ✓ all | ✓ 422 on a bad body | ✓ `spaces/demo` Expectations | **PARTIAL** — not a Step |
| `control.alert.dispatch` | ✓ [section above](#controlalertdispatch--alert-rules) | ✓ `AlertServiceTest`, `ControlApiAlertRuleWriteTest` | ✗ authored in the Alert Rules pane, never on the canvas | ✓ inactive | ✓ Professional+, gated | ✓ 422 on a bad body | ✓ `spaces/demo` Alert Rules | **PARTIAL** — not a Step |
| `sink.quarantine` | ✓ [section above](#sinkquarantine--the-quarantine-directory-and-the-reject-log) | ✓ `ControlApiRejectedRowsTest`, the ingester reject tests | ✗ always on; a node is lifted only for selector / segments pipelines | ✓ inactive | ✓ all | — | ✓ `03-schema-transform/reject-routing` | **PARTIAL** — not a Step you add |

The three PARTIALs are a *category* verdict, not a quality one: each is a working capability, and Part B's
own definition of partial is "something real behind it, but not as a chain Step" — the reason
`transform.diff.compare` and `sink.notify.email` were already partial. Nothing became less addable: all three
were inactive in the palette before. Save-time checks added for the Collector keys:
[config safety § Collector checks](../config/spec-config-safety-roots-save-gate.md#collector-checks--throttle-breaker-archive-2026-09-24).

**G10 — demos (2026-09-24).** Every DELIVERED processor now has one. Added: `07-steps/profile`,
`07-steps/dedup-window`, `03-schema-transform/schema-drift`, `05-acquisition/intake-throttle`, and the
`_reference/` templates `sftp-collector` (also `acquisition.file.sftp`), `db-export`, `webhook-sink`
(`sink.api.webhook`), `ducklake-sink` (`sink.ducklake`). `acquisition.dataset` is demoed by `spaces/demo`'s
`orders_by_region_feed` (its unsafe id is `DEMO-DATASET-FEED-UNSAFE-ID-1`); `enrichment.reference` by
`spaces/demo`'s `orders_daily_enrich`.

**Edition notes (G9) — ✅ GATED 2026-09-24 (operator decision: "gate the code").** Three board cells
disagreed with the code: `control.alert.dispatch` and `sink.archive` were "not for Personal" while
`AlertService`/`AlertRoutes` and the Collector's `post_action` shipped in every flavour, and `sink.ducklake`'s
note said Professional+ while its board cells read ✅ in all three (now `— ✅ ✅`). All three are core code, so
there is no module to leave out; instead a Professional-only module DECLARES each feature through the
`com.gamma.etl.EditionFeatureProvider` ServiceLoader seam — `inspecto-ops` declares `alert.dispatch`,
`inspecto-backup` declares `sink.archive` and `sink.ducklake` — and `EditionFeatures.present(...)` is the
one question every door asks. The classpath entry is the switch, exactly as for the other EDG-01 modules;
there is no flag. On Personal, refused with **`ERR_EDITION_FEATURE`** naming the feature and "Professional+":
* **at save** — `SaveGate.editionFindings` (every config door: `/config/write`, `/config/patch`, graph PUT,
  bundle import, `/validate`): `collector.post_action.on_success: MOVE`, `output.ducklake` / `sinks[i].ducklake`
  with `enabled: true`, and the legacy `alert` type; ERROR regardless of `active`. Alert Rule components are
  refused on their own routes (`POST`/`PUT /alerts/rules`, `/components/alert-rule`) with the same 422 findings
  body, and a Decision Rule's `create-alert` records the refusal instead of authoring the rule.
* **at run, for a config that arrived on disk** — `ConfigRegistry` makes such a pipeline a load failure (listed,
  never registered); the boot load does not arm an Alert Rule and logs why; and two backstops cover lanes the
  registry does not guard: `DuckLakeRegistrar.register` (the job lane, the CLI) and `CollectorProcessor.acquire`
  (before any connector opens; a dry run skips the post-action and is not refused).
What stays in every edition: the other post-actions, the local `dirs.backup` move, a ducklake block that is not
enabled. The `backup` task itself was already `inspecto-backup`'s (OPS-06, EDG-01 cell 2). Tests:
`EditionFeatureGateTest` (core = Personal), `EditionFeaturesTest`, `CollectorProcessorArchiveEditionTest`,
and the Professional acceptance with the REAL providers: `ProfessionalAlertRulesAcceptedTest` (inspecto-ops),
`ProfessionalSinkFeaturesAcceptedTest` (inspecto-backup). ⚠ Core, engine, etl and connectors test classpaths
are Personal, so a test of the Professional behaviour pins it with `EditionFeatures.overrideForTest(...)` and
restores `null` in teardown; `ShippedExamplesPassTheSaveGateTest` asserts the exact two `_reference/` templates
(`sftp-collector`, `ducklake-sink`) a Personal build refuses.

✅ **Gap G7 (at-rest real-path tests) closed 2026-09-23.** `PipelineJobRunnerTest` now runs a flat file's
`profile` step, its `lookup` step and a `webhook:` branch end to end through the job lane and asserts
the written output: the profile's statistics row, the transcoded column with its `default`, and the
POSTed batches with their `Idempotency-Key`. A rejected POST fails the branch and leaves the source
unfinalised, and a re-run of the same batch re-sends under the same keys. `NodeConfigNameContractTest`
now holds the profile round trip that `BuiltinNodeType.FlatHome` names as its guard, covering both a
named column subset and the empty "profile every column" list. How the webhook tests reach a transport: a
capturing `WebhookSinkTransport` is discovered through the real `ServiceLoader` lookup, from a scratch
class loader set as the thread's context loader for one run. It is never registered in the engine's
test resources, because `WebhookSinkTest.theEngineBundlesNoTransport` pins that the engine ships none.
Profile `min_value`/`max_value` are aggregated in the column's own type and only then cast to VARCHAR,
so a numeric column reports a numeric min/max (20 before 150), not a text one.

**G6 (Collector features at poll-cycle level) — tests shipped 2026-09-23.** `CollectorProcessorRemoteCycleTest`
(inspecto-connectors; real MINA SFTP server + DuckDB JDBC) drives `CollectorProcessor.run`/`acquire` for the
circuit breaker (trips at the threshold, skips without dialling while OPEN, half-open trial closes it),
`fetch.rate_limit` (measurably throttles), `post_action: MOVE` (moves a fetched file, leaves a failed fetch in
place and retries it next cycle) and SFTP/JDBC runs asserting the exact sink rows; `CollectorProcessorDatasetFeedTest`
(inspecto-engine) does the same for a `connector: dataset` feed, including refresh and producer-file safety.
⚠ `post_action` fires on **fetch** success (land-then-ack), before ingest — not on ingest success.
✅ **A file above one second of `rate_limit` is throttled, not hung (2026-09-24, `RATE-LIMIT-OVERSIZE-HANGS-1`).**
`RateLimiter.acquire` (inspecto-acquire; its only caller is `RemoteAcquisitionHandler`, once per whole file with
`rf.size()` before the fetch — there is no per-chunk acquire) used to loop until the bucket held `bytes` tokens,
but the bucket is capped at one second of rate (the burst), so an oversized request never completed. Operator
decision — **proportional wait**: a request the bucket cannot cover drains it and sleeps once for
`(bytes − available) / rate`; the refill accrued during that sleep pays the deficit and is discarded, so the
bucket never goes negative (no debt borrowed from later requests) and the long-run rate is honoured. Requests
within the bucket behave as before. Pinned by `RateLimiterTest` (proportional wait, throughput over five
oversize requests = rate, a following small request pays only normal refill; the old loop times out three of
them) and the re-enabled `CollectorProcessorRemoteCycleTest#aFileLargerThanOneSecondOfRateIsThrottledNotHung`.
✅ **A MOVE's archive tree is excluded from discovery (2026-09-23, `POST-ACTION-MOVE-RECOLLECTS-ARCHIVE-1`).**
Every connector resolves `archive_path` under the Collector's own root, so a recursive listing used to return the
archived files under `archive/…` and MOVE them again to `archive/archive/…` every cycle.
`CollectorProcessor.excludeArchiveTree` drops them right after `discover`, before stability, gap detection and
fetch; date tokens in the template match any digits, so every past day's `archive/yyyy/MM/dd` tree is excluded,
not just today's. ⚠ The row's "duplicate rows" half was not reproduced: content dedup already refused the
re-ingest — the observable defect was the repeated re-MOVE (and a re-fetch per cycle). Pinned by
`CollectorProcessorRemoteCycleTest#anArchivedFileIsNotCollectedAgain`, plus `#aFileArchivedOnAnEarlierDayIsNotCollectedAgain`
(`archive_path: archive/yyyy/MM/dd` with a file already under `archive/2026/09/01/`; turning off the date-token
matching makes it red, with the old file ingested and today's file collected again) and
`#ftpAnArchivedFileIsNotCollectedAgain` (FTP's MOVE target is `basePath/archive_path/<relativePath>`, the same
under-the-root shape as SFTP, driven against an in-process Apache FtpServer; dropping the exclusion re-MOVEs the
file to `archive/archive/…`).

⚠ **Also open (filed 2026-09-23):** `COLLECTOR-ON-CHANGE-SKIP-IS-REPROCESS-1` — the acquisition form offers `duplicate__on_change: skip`, which `DuplicatePolicy.OnChange.from` does not recognise, so it silently means REPROCESS (the valid values are `ignore` / `alert` / `archive_old_version`).

✅ **Connector end-to-end tests assert ROW IDS, not just an output file (2026-09-23, was `CONNECTOR-TESTS-HIDE-DROPPED-ROWS-1`).** `DbExportConnectorTest#endToEndDbExportIsIngested` and `SftpConnectorTest#endToEndParallelFetchWithMovePostAction` read every `*_out.csv` (test helper `OutputRows`) and compare the sorted first column to the rows the Collector was fed. The DB-export test really was dropping `r1`: its Pipeline set `skip_header_lines: 1` on top of the default header skip — a test-config defect, not a product one; the key is gone. Mutation-proved: re-planting a header skip turns each test red with the missing IDs named.
