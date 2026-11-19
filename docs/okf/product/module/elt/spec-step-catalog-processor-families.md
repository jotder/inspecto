---
type: Concept
title: Step catalog — every Step kind, delivered and pending — Processor families, and keeping this page true
description: Part 5 of 5 of Step catalog — every Step kind, delivered and pending: Collectors & Ingestion (ACQ) — 4 delivered · 5 partial · 11 planned; Extraction & Format Parsers (PRS) — 7 delivered · 1 partial · 9 planned; Data Quality, Validation & Cleansing (DQ) — 10 delivered · 1 partial · 3 planned; Transformers & Dimensional Modeling (XFM) — 10 delivered · 1 partial · 10 planned; Analytics …
tags: [pipeline-graph, steps, catalog, processors, palette, examples, roadmap]
timestamp: 2026-09-06T00:00:00Z
---

# Step catalog — every Step kind, delivered and pending — Processor families, and keeping this page true

Part 5 of 5 of [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) · previous: [Webhook and Excel sinks, and the non-chain processors](spec-step-catalog-outbound-sinks.md).

## Collectors & Ingestion (`ACQ`) — 4<!--count:processors-acq-delivered--> delivered · 5<!--count:processors-acq-partial--> partial · 11<!--count:processors-acq-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Local / NFS directory watcher | `acquisition.file.local` | `acquisition` — LocalFileSystemConnector — the default collector |
| SFTP / FTPS remote ingest | `acquisition.file.sftp` | `acquisition` — inspecto-connectors (sftp, ftps; key auth, bastion tunnel, host-key pinning) |
| JDBC / SQL query batch reader | `acquisition.db.jdbc` | `acquisition` — the db-export connector (`connector: db`, watermark column) |
| Dataset entry (re-ingest a registered Dataset) | `acquisition.dataset` | `acquisition` — UI-S7: `connector: dataset` + `on:dataset` trigger |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Multi-sheet Excel workbook ingest | `acquisition.file.excel` | `parser.xlsx` — the xlsx PARSER is delivered; per-sheet workbook fan-out as an acquisition is not |
| Azure Blob & ADLS Gen2 ingest | `acquisition.file.azure` | `acquisition` — Connection kind exists (azure blob connector); ADLS Gen2 semantics not proven |
| AWS S3 object ingest | `acquisition.file.s3` | `acquisition` — Connection kind exists (s3 connector, SDK-free SigV4; covers MinIO / GCS-interop); no proven end-to-end acquisition-node run |
| Google Cloud Storage ingest | `acquisition.file.gcs` | `acquisition` — Connection kind exists (gcs connector, native GCS JSON API + service-account OAuth2); no proven end-to-end acquisition-node run |
| Apache Kafka consumer | `acquisition.stream.kafka` | `acquisition` — Per-cycle Collector drain ships: frontier on the ledger watermark, re-keyed on land and durable across a restart, one uncommitted slice per partition, lag exported (STREAM-CONSUMER-1); broker consumer groups out by decision; proven against MockConsumer and a fake connector only, never a real broker |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Apache Pulsar consumer | `acquisition.stream.pulsar` | — |
| AWS Kinesis / SQS ingest | `acquisition.stream.kinesis` | — |
| RabbitMQ AMQP subscriber | `acquisition.stream.rabbitmq` | — |
| MQTT IoT telemetry ingest | `acquisition.stream.mqtt` | — |
| Change Data Capture (Debezium) | `acquisition.cdc.debezium` | — |
| Delta Lake table reader | `acquisition.lake.delta` | — |
| Apache Iceberg table ingest | `acquisition.lake.iceberg` | — |
| Snowflake / BigQuery ingest | `acquisition.db.cloud` | — |
| REST API poller & paged ingest | `acquisition.api.rest` | — |
| HTTP webhook listener endpoint | `acquisition.api.webhook` | — |
| gRPC stream receiver | `acquisition.api.grpc` | — |

## Extraction & Format Parsers (`PRS`) — 7<!--count:processors-prs-delivered--> delivered · 1<!--count:processors-prs-partial--> partial · 9<!--count:processors-prs-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Delimited / CSV / TSV / PSV parser | `parser.delimited` | `parser.delimited` |
| Fixed-width column slicer | `parser.fixedwidth` | `parser.fixedwidth` — text (`record: line`) and fixed-length binary (`record: bytes`) |
| JSON object & JSON Lines (NDJSON) parser | `parser.json` | `parser.json` |
| Excel workbook parser | `parser.excel` | `parser.xlsx` — needs the DuckDB `excel` extension in the bundle (multiformat X1) |
| ASN.1 BER telecom CDR decoder | `parser.asn1.ber` | `parser.asn1` — Telecom industry pack (inspecto-telecom-asn1) over the asn-parser reactor (decoders, vendor plugins); Professional and above. ⚠ Resolved at READ time: with no `asn1` parser registered the served entry is `planned`, carries `requires: inspecto-telecom-asn1`, no node type, and is not addable |
| Named-group regex extractor | `parser.pattern.regex` | `parser.text_regex` |
| Custom ingester plugin (segments, multi-event) | `parser.plugin` | `parser.plugin` — ParserPlugin SPI; `segments: {CALL, SMS}` |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| XML / XPath / DOM unpacker | `parser.xml` | `parser.plugin` — tree→segments bridge ships XML ingests; no XPath selector grammar yet |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Key-value / logfmt parser | `parser.keyvalue` | — |
| YAML document slicer | `parser.yaml` | — |
| ASN.1 PER / XER / DER decoder | `parser.asn1.per` | — |
| Mainframe EBCDIC & COBOL copybook | `parser.mainframe.ebcdic` | — |
| Protocol Buffers decoder | `parser.binary.protobuf` | — |
| Apache Avro binary decoder | `parser.binary.avro` | — |
| PCAP network packet slicer | `parser.binary.pcap` | — |
| Grok / Logstash expression matcher | `parser.pattern.grok` | — |
| Syslog RFC 5424 / RFC 3164 parser | `parser.pattern.syslog` | — |

## Data Quality, Validation & Cleansing (`DQ`) — 10<!--count:processors-dq-delivered--> delivered · 1<!--count:processors-dq-partial--> partial · 3<!--count:processors-dq-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Schema registry & structural rejects | `quality.schema.validator` | `parser` — the declared schema on the Parse Step: typed fields, TRY_CAST at ingest, structural rejects → quarantine. ⛔ Only HALF of the old "Schema validator & type coercion" — coercion as an AUTHORING act folded into `transform.record` (2026-09-04). What cannot fold is the schema CONTRACT: the declared source column + target type are the cast-failure audit's denominator, and `SchemaCompatibility` gates edits to them BACKWARD |
| Exact-key deduplicator (within a Consignment) | `quality.dedup.exact` | `transform.dedup` — `scope: consignment` (default) |
| Inline stream profiler & statistics | `quality.profiler.inline` | `transform.profile` — per-column row/null/distinct counts and min/max, as a Step (2026-09-15) |
| PII masking & tokenization | `quality.pii.mask` | `transform.mask` — 🔒 Enterprise only (SEC-08); by classification (strictest-wins) or name, full / partial / hash, no reversible vault (2026-10-06) |
| One-way salted cryptographic hasher | `quality.crypto.hash` | `transform.hash` — Space-keyed HMAC-SHA-256 token per value, joinable within the Space; the key never leaves the Space's secrets (2026-10-06) |
| Sliding time-window deduplicator | `quality.dedup.windowed` | `transform.dedup` — D-9: `scope: window(P4D)` + the durable dedup ledger |
| File-grain duplicate guard (path / checksum / metadata / marker) | `quality.dedup.file` | `acquisition` — Collector `duplicate:` policy + marker dedup — a Guarantee, rides the Collector |
| Fuzzy string (Jaro-Winkler) matcher | `quality.match.fuzzy` | `transform.similarity` — a 0..1 score of two columns (Jaro-Winkler, Jaro, Levenshtein, token Jaccard; normalized first by default) on the Analytics Kernel, plus an optional candidate flag at a threshold; proposes candidates, never asserts identity (D-M1) (2026-10-17) |
| Statistical & reservoir sampler | `quality.sample.reservoir` | `transform.sample` — a fixed size or a fraction per group, chosen by an MD5 hash of a seed and a row key on the Analytics Kernel (`Sampler`): the same rows every run and in the JVM twin; never `USING SAMPLE` / `random()` (2026-10-17) |
| Schema drift & new-field detector | `quality.schema.drift` | `parser` — ✅ **Release-ready** (`PROCESSOR-RELEASE-READINESS-1`, 2026-09-24: passes the bar; detection only is the promise, not a gap). ✅ **SHIPPED 2026-09-10.** `SchemaDrift.detect` reads each header-bearing delimited member's header once per batch (`CsvIngestStrategy`, above the lane dispatch, so the native and Java lanes agree) and diffs it against `raw.fields[]`: **width always** (fewer than `maxSelector+1` = rejected rows; more than declared + `skip_tail_columns` = a new field), **names only when the schema was authored from the header** (≥1 declared name occurs in it, case-insensitive — a positional lane may name `customer_id` for "Customer ID", and diffing those would flag every file forever). Detection only: the parse runs unchanged and the REPORT is one `quality.schema_drift` WARN Signal per batch (`SchemaDriftSignal`, `correlationId = batchId`, payload `files[]{file, declaredWidth, observedWidth, observed, added, missing, namesCompared}`), so triage/RCA show it beside the batch's own `pipeline.batch.committed` with no UI change. ⛔ No refusal policy — a detector that cannot see (`has_header: false`, unreadable header) says nothing; a "refuse on new field" knob was put to the operator 2026-09-10 and **refused: detection only** (`BACKLOG.md` §6). |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Constraint & range checker (Expectations) | `quality.constraint.check` | `expectation` — Expectations evaluated per Dataset (`ExpectationEvaluator`), authored in the Expectations pane — not a Step: never on the canvas, so no workbench preview / lift / lower |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Cluster & edit value normalizer | `quality.cluster.edit` | — |
| Character map & code page transcoder | `quality.cleanse.transcode` | — |
| GDPR / CCPA field redactor | `quality.compliance.redact` | board SEC-08 — Enterprise only |

## Transformers & Dimensional Modeling (`XFM`) — 10<!--count:processors-xfm-delivered--> delivered · 1<!--count:processors-xfm-partial--> partial · 10<!--count:processors-xfm-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Record Transformer | `transform.record` | `transform.sql` — ONE Step that shapes a record: sanitize (trim/pad/replace/case), cast, rename and computed columns — one row per output field over a typed function catalog, generating the SELECT it saves. Folded 2026-09-04 from `transform.expression` + `transform.cast` + `quality.cleanse.trim`, which were three catalog labels over this one grid. `transform.map` was deleted 2026-09-05: a stored `mapping.rules[]` is read as `fields[]` (EXPR → custom, CONCAT_DT → date.concat_parts, FILENAME_DATE → date.from_filename) |
| Row filter (pre-parse regex / post-map predicate) | `transform.filter` | `transform.filter` |
| Lookup & static map transcoder | `transform.lookup` | `transform.lookup` — inline `key=value` map over one column (2026-09-06) |
| Router — case / clone branches with mid-branch steps | `transform.route` | `transform.route` |
| Group-by summarizer (measures grammar) | `transform.summarize` | `transform.summarize` |
| Reference-store join (versioned references) | `transform.join` | `transform.join` — at rest only — refused mid-branch (no reference resolver on the ingest lane) |
| Array / object exploder & flattener | `transform.explode` | `transform.explode` — one row per element of a LIST or JSON-array column, optional index (2026-10-06); the grandfathered `transform.split` stays the read-only ancestor |
| Unpivot / column flattener | `transform.matrix.unpivot` | `transform.matrix.unpivot` — wide columns to (name, value) rows by list or name pattern, cast to one value type (2026-10-06) |
| Dynamic pivot / transpose | `transform.matrix.pivot` | `transform.matrix.pivot` — rows to columns over DECLARED values only (at most 200; a dynamic pivot is refused), one Measure-grammar aggregate per value (2026-10-17) |
| Rank & Top-N pruner | `transform.analytics.rank` | `transform.analytics.rank` — row_number / rank / dense_rank per group by a stated order, ties broken deterministically; keeps the top N or adds the rank (2026-10-17) |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Dataset differ & change compare | `transform.diff.compare` | `recon` — Reconciliation boards compare Datasets; not a chain step |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Presorted stream merge joiner | `transform.join.merge` | the grandfathered `transform.merge` node type is the read-only ancestor |
| Slowly changing dimension (SCD Type 2) | `transform.dim.scd2` | — |
| Monotonic surrogate key generator | `transform.key.surrogate` | — |
| DML row-action strategy flagger | `transform.dml.strategy` | — |
| Hierarchical XML / JSON document builder | `transform.builder.xml` | — |
| IFRS 15 / IFRS 9 revenue recognition engine | `transform.fintech.ifrs` | — |
| SIM box & bypass fraud detector | `transform.telecom.simbox` | — |
| Tariff, rating & usage billing engine | `transform.telecom.rating` | — |
| Roaming TAP3 / CIBER surcharger | `transform.telecom.roaming` | — |
| Velocity & impossible-travel anomaly | `transform.fintech.velocity` | — |

## Analytics, Time-Series & Semantic Modeling (`BI`) — 10<!--count:processors-bi-delivered--> delivered · 2<!--count:processors-bi-partial--> partial · 5<!--count:processors-bi-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Time-grain resampler & gap imputer | `transform.timeseries.resample` | `transform.timeseries.resample` — one row per key per bucket (second..year, ISO weeks), every bucket present - count/sum fill 0, the rest empty; an explicit time zone for TIMESTAMPTZ (2026-10-17, ANALYTICS-CORE-1 M5) |
| Period-over-period (YoY / MoM / WoW) shift | `transform.timeseries.shift` | `transform.timeseries.shift` — the value a key had N buckets earlier, matched by bucket so a gap gives empty, not the wrong period (2026-10-17, ANALYTICS-CORE-1 M5) |
| Time-series forecaster (Holt-Winters) | `transform.timeseries.forecast` | `transform.timeseries.forecast` — seasonal naive or Holt-Winters additive over a regular series, point forecast plus a prediction band per level (2026-10-17, ANALYTICS-CORE-1 S5) |
| Running calculations & moving averages | `transform.analytics.running` | `transform.running` — count/sum/avg/min/max per key over a time (60m) or row (5 rows) window, as a Step (2026-10-06); state is per run — cross-batch velocity stays a SQL-over-sink Job |
| Histogram & quantile binner | `transform.data.binning` | `transform.data.binning` — equal-width or quantile (equal-frequency) bins of a number column, per group, with a bin number and label; a histogram is binning then summarize (count by bin), and the profiler's histogram shares the equal-width bin (2026-10-17, ANALYTICS-CORE-1 S3) |
| Outlier detector & IQR boxplot fencer | `transform.stats.outlier` | `transform.stats.outlier` — Tukey's fences (k, quantile mode) or a robust z (median and MAD) per group; every row kept with its fences or score and a flag (2026-10-17, ANALYTICS-CORE-1 S3) |
| Pearson & Spearman correlation matrix | `transform.stats.correlation` | `transform.stats.correlation` — Pearson (default) or Spearman (`method: spearman`: Pearson of the within-group average ranks, ties share their average rank) plus the least-squares line (slope, intercept, r2) per column pair and group, as long-form rows (2026-10-17 Pearson, 2026-10-18 Spearman, ANALYTICS-CORE-1 S3)
| Change-point, regime & drift detector | `transform.timeseries.changepoint` | `transform.timeseries.changepoint` — over a residual series (actual minus expected) and its scale sigma: band breaches, regime changes (a run of K outside points) and drift (two-sided CUSUM), every row kept with 0/1 flags; the Business Assurance forecast template's rule (2026-11-01, ANALYTICS-STEPS-TS-1) |
| Cohort retention matrix | `transform.cohort.retention` | `transform.cohort.retention` — acquisition cohorts by hour..year on a stated clock, exact or on-or-after retention per period as of a stated date; a period not yet observable is absent, never 0 (2026-11-01, ANALYTICS-STEPS-TS-1) |
| Ordered conversion funnel | `transform.cohort.funnel` | `transform.cohort.funnel` — 2..12 ordered steps per entity, earliest-first from the first step-1 event, with an optional window; entities per step and conversion from the previous and the first step (2026-11-01, ANALYTICS-STEPS-TS-1) |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Semantic KPI calculator & Measure formulas | `transform.semantic.metric` | `measure-grammar` — the Measure grammar (`count \| agg(field)`) serves Studio + summarize; no named-KPI layer |
| Template & runtime parameter injector | `transform.param.jinja` | `sql.template` — the `sql.template` job resolves `$name` tokens; no Jinja |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Level-of-detail (LOD) fixed aggregator | `transform.analytics.lod` | — |
| LOD include / exclude context aggregator | `transform.analytics.lod_context` | — |
| Smart custom string splitter | `transform.string.smart_split` | — |
| H3 hexagonal & geohash grid indexer | `transform.geo.h3` | needs the DuckDB `spatial`/`h3` extension — board CP-09 gate |
| Spatial polygon & point-in-polygon intersect | `transform.geo.spatial_join` | needs the DuckDB `spatial` extension — deliberately not loaded (SqlSandbox) |

## Enrichment, Entity Resolution & AI/ML (`ENR`) — 2<!--count:processors-enr-delivered--> delivered · 1<!--count:processors-enr-partial--> partial · 7<!--count:processors-enr-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Reference-table enrichment (`*_enrich.toon`, Stage-2) | `enrichment.reference` | `enrichment` — the shipped enrichment job + versioned references |
| Master entity resolution & record linkage | `enrichment.entity.link` | `transform.entity.link` — every row gets a resolution group: blocking keys + exact match keys (normalised as text, digits or as written) + union-find on the Analytics Kernel, caps fail closed; similarity only flags look-alikes for review, never merges (D-M1) (2026-10-17) |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Graph cluster & connected-component tagger | `enrichment.graph.cluster` | `link-analysis` — link-analysis VIEW ships (projection); no tagging step |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| GeoIP & ISP geolocation enricher | `enrichment.geoip` | — |
| Redis / in-memory cache lookup | `enrichment.redis` | — |
| Dynamic microservice REST enricher | `enrichment.rest` | — |
| Parameterized stored-procedure caller | `transform.db.procedure` | — |
| ONNX Runtime embedded inference | `ml.inference.onnx` | onnxruntime ships in the Enterprise bundle inside `inspecto-intelligence` (its embeddings, ASSURE-INTELLIGENCE-BUNDLE-1); this Step Processor is not built |
| LLM zero-shot classifier & tagging | `ml.llm.classify` | assist/intelligence agents are never bundled (CP-14) |
| Text embeddings & vector generator | `ml.embedding.vector` | — |

## Control, Governance & Sequence Integrity (`CTL`) — 5<!--count:processors-ctl-delivered--> delivered · 3<!--count:processors-ctl-partial--> partial · 0<!--count:processors-ctl-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| File sequence & gap integrity analyzer | `control.file.sequence_analyzer` | `gap` — Collector `gap_detection: {sequence}` → the gap node + SEQUENCE_GAP events |
| Sequence-gap & data-loss watchdog | `control.gap.detector` | `gap` — same detector; gaps raise stored Alerts via the EventAlertBridge |
| Audit metadata & lineage stamper | `control.audit.stamp` | `sink.persistent` — `filename_column` + the per-file/batch/lineage ledgers + `__consignment_id` provenance |
| Throttle & rate limiter | `control.throttle` | `acquisition` — Collector `fetch.rate_limit` + intake caps + the concurrency broker |
| Circuit breaker & fallback switch | `control.circuitbreaker` | `acquisition` — Collector `circuit_breaker` + `retry` |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Transaction & commit controller | `control.transaction.commit` | `engine` — the BranchCommitCoordinator ledger + bounded COMMIT retry are engine-internal, not authorable |
| Alert rule dispatcher | `control.alert.dispatch` | `alert-rule` — Alert Rules over the ledgers → Alerts → channels (board CP-11/CP-15), authored in the Alert Rules pane — not a Step: never on the canvas, so no workbench preview / lift / lower |
| SLA timeout & heartbeat monitor | `control.sla.monitor` | `completeness-kpi` — completeness KPI + `heartbeat` maintenance task; no SLA object |

## Sinks, Storage & Destinations (`SNK`) — 6<!--count:processors-snk-delivered--> delivered · 3<!--count:processors-snk-partial--> partial · 6<!--count:processors-snk-planned--> planned

**Delivered**

| Processor | Id | Maps to |
|---|---|---|
| Parquet (snappy / zstd / gzip) partitioned store | `sink.file.parquet` | `sink.persistent` — Hive `year=/month=/day=` partitions |
| CSV partitioned store | `sink.file.csv` | `sink.persistent` — `output.format: CSV` |
| DuckLake catalog (PostgreSQL) sink | `sink.ducklake` | `sink.persistent` — `output.ducklake` — needs the postgresql sidecar (Professional+) |
| Long-term compliance archive | `sink.archive` | `acquisition` — Collector `post_action: MOVE archive_path` + the `backup` maintenance task |
| Outbound webhook dispatcher | `sink.api.webhook` | `sink.webhook` — the top-level `webhook:` block: JSON batches to an https Connection, at-rest lane, Professional+ (2026-09-23; [above](spec-step-catalog-outbound-sinks.md#webhook--sinkwebhook--the-outbound-webhook)) |
| Excel multi-tab report sink | `sink.file.excel` | `sink.excel` — the top-level `excel:` block: named sheets of one .xlsx under the data root, a read-only SELECT per sheet, at-rest lane (2026-10-06; [above](spec-step-catalog-outbound-sinks.md#excel--sinkexcel--the-excel-workbook-sink)) |

**Partial**

| Processor | Id | What exists today · what is missing |
|---|---|---|
| Derived view (no bytes, registered SQL) | `sink.view` | `sink.view` — grandfathered node type; the Dataset/View surface replaced it. **Both halves of D-7 leave the user surface:** `sink.materialized` is `summarize`'s internal **compile target** and `sink.view` is Dataset-registration sugar — neither is something an author picks. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)* |
| Quarantine error-log store | `sink.quarantine` | `sink.quarantine` — structural rejects + `errors/<base>_errors.csv` on every pipeline — not a Step you add: a quarantine node is lifted only for selector / segments pipelines, so there is no workbench preview / lift / lower of its own |
| Email & report dispatcher | `sink.notify.email` | `mail.send` — the `mail.send` JOB + mail channels; not a chain sink |

**Planned — not yet available**

| Processor | Id | Note |
|---|---|---|
| Delta Lake persistent table sink | `sink.lake.delta` | — |
| Apache Iceberg append / upsert sink | `sink.lake.iceberg` | — |
| ClickHouse / StarRocks analytical sink | `sink.db.clickhouse` | — |
| Apache Kafka topic producer | `sink.stream.kafka` | — |
| AWS SQS / SNS event publisher | `sink.stream.aws` | — |
| Dead-letter queue | `sink.dlq` | — |

---

## Keeping this page true

- **Part A's tables are the served contracts.** When `NodeAttributes` or a parse grammar changes,
  regenerate `node-attributes.contract.json` (`mvn -o test -Dnode.attributes.write=true`) and rebuild
  the tables from it; do not retype a help string here.
- **Part B is the processor contract, verbatim.** Change `ProcessorCatalog.java`, regenerate with
  `-Dprocessor.catalog.write=true`, then re-render this part and `EDITIONS.md`
  (`node tools/render-processor-board.mjs`). A processor moves from *planned* to *delivered* only when
  a Step kind executes it; *partial* is the honest middle and should name what is missing.
- **Every delivered Step kind keeps its example runnable** — `inspecto/examples/07-steps` and
  `spaces/default/config/<kind>_step`. Run `bash inspecto/examples/run-example.sh 01-ingest/hello-csv`
  after touching the path jail or the launchers: the examples sit outside every test.
- Related: [pipeline-graph-design](architecture-elt.md) (the model) ·
  [pipeline-config-keys](../config/spec-pipeline-config-keys.md) (who reads every block) ·
  [execution-lanes](spec-pipeline-execution.md) · [catalog-vs-executors](spec-pipeline-model.md) ·
  [pipeline-editor](user-guide-elt.md) (the palette and panes) ·
  [job-vs-step](../processor/architecture-processor.md) (why some catalog entries are Jobs, not Steps).

## Adding a node type — what is declared once, what is derived, what is legitimately separate (2026-09-15)

`NODE-TYPE-MIRRORS-1` measured the cost of adding ONE node type at ten hand sites and asked for the mirrors to
be DERIVED. As-built:

| Site | Now |
|---|---|
| `BuiltinNodeType` case | ✅ source of truth — and it now carries the **flat-config home**: `FlatHome.NONE` / `BLOCK` / `STEP` + the `steps:` kind. A constructor guard refuses a `STEP` without a kind and a kind without `STEP`. |
| `PipelineEditable.LOWERABLE` | 🔁 **derived** — every case whose `flatHome() != NONE` |
| `PipelineEditable.STEP_KIND` | 🔁 **derived** — every `STEP` case's `stepKind()`; `BuiltinNodeTypeFlatHomeTest` pins each kind to a `PipelineConfig.Step` constant, TRANSFORM-only, unique |
| `inspecto-ui/…/node-attributes.ts` | 🔁 **derived** — imports `node-attributes.contract.json` (the JSON `NodeAttributesContractTest` regenerates from the Java table) instead of a hand-typed copy; its spec now pins shape, not equality with itself |
| `NodeAttributes` (spec + `byType` + registration) | ⛔ **stays** — a form schema (widget / tier / default / help / options per key) is a richer structure than an enum field can carry; it IS the attribute-spec table, not a mirror of one |
| `ProcessorCatalog` | ⛔ **stays** — a many-to-many PRODUCT taxonomy that includes PLANNED processors with no node type at all; its own javadoc forbids folding it into the enum |
| `RecipeCompiler` ×2, `RowShaper`, `ConfigSpecs` block | per-node work by nature, out of scope |

⚠ **What the derivation did NOT fix, stated so nobody thinks it did:** `FlatHome.BLOCK` is still a CLAIM that
lift/lower code exists for the type — the `LOWERABLE` trap moved onto the enum case, it did not disappear.
The guard that would catch a false claim is the round trip in `NodeConfigNameContractTest`; add the new type to
it. ⚠ The `transform.<kind>` namespace rule (site 0) is still stated nowhere the compiler can see.
