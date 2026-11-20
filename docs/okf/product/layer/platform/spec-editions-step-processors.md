---
type: Spec
title: Editions — engineering specification — Feature × edition matrix — Step Processors
description: Part 4 of 5 of Editions — engineering specification: Feature × edition matrix — Step Processors
resource: offerings/
tags: [editions, packaging, offerings, gating, decisions]
timestamp: 2026-11-06T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-20 last-edit
---

# Editions — engineering specification — Feature × edition matrix — Step Processors

Part 4 of 5 of [Editions — engineering specification](spec-editions.md) · previous: [Feature × edition matrix — how to read it, core engine](spec-editions-feature-matrix.md) · next: [Feature × edition matrix — control plane, security, operations, compliance, gating debt](spec-editions-platform-board.md).

### Step Processors (one row per processor; the board's authoring surface) [`ING` · `PIP`]

Generated from the processor catalog (`ProcessorCatalog`, served on `GET /pipelines/processor-catalog`, mirrored to
`processor-catalog.contract.json`) — edit the catalog, regenerate this table; do not hand-edit rows. **Status**: ✅ delivered
(maps onto an existing node type / engine capability, named in *Maps to*), 🟡 partial (a neighbour capability covers part
of it — the gap is in Notes), 🔲 planned. Every processor is VISIBLE in the editor palettes; 🔲 and 🟡-without-node entries
render inactive. Edition cells default to the same value in all three unless a board decision says otherwise (SEC-08 →
E-only for the two compliance processors; CP-09/CP-11/CP-15/OPS-06 → not for Basic). Row ids are `SP-<family>-<nn>`.

⚠ **DATA-TIERS-1 (2026-11-17): these cells say what the build CARRIES and runs, not what Basic's screens offer.** Basic
runs any configuration it is given (DT-9 narrowed, §5.3), so a configured Step or remote Collector runs there as the
cell says. Basic's step palette shows locked, with the reason, every Step the catalog marks `featureFlag: pipelineTransform`
(the **SP-XFM** transforms but *Row filter*, the BI and ENR Steps; Basic's offer is Add data's no-code shaping: types,
rename, choose columns, the row filter, dedupe and schema `expression:` columns, DT-7) and the remote **SP-ACQ** rows
marked `remoteSystems` (SFTP / FTPS, JDBC, S3, Azure, GCS, Kafka; `inspecto-connectors` stays in the Basic Offering, and
the Postgres snapshot of Add data needs no Connection). **SP-PRS-06** (ASN.1) is Advanced only since 2026-11-17 (DT-12:
module `telecom-asn1` and add-on `telecomIndustryPack` moved to `offerings/advanced.toon`). See *Data layer by edition*
above.

| ID | Processor | Family | P | S | E | Maps to | Notes |
|---|---|---|---|---|---|---|---|
| SP-ACQ-01 | 📁 Local / NFS directory watcher (`acquisition.file.local`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | LocalFileSystemConnector — the default collector |
| SP-ACQ-02 | 🔒 SFTP / FTPS remote ingest (`acquisition.file.sftp`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | inspecto-connectors (sftp, ftps; key auth, bastion tunnel, host-key pinning) |
| SP-ACQ-03 | 🗄️ JDBC / SQL query batch reader (`acquisition.db.jdbc`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | the db-export connector (`connector: db`, watermark column) |
| SP-ACQ-04 | 🧱 Dataset entry (re-ingest a registered Dataset) (`acquisition.dataset`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | UI-S7: `connector: dataset` + `on:dataset` trigger |
| SP-ACQ-05 | 📑 Multi-sheet Excel workbook ingest (`acquisition.file.excel`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `parser.xlsx` | the xlsx PARSER is delivered; per-sheet workbook fan-out as an acquisition is not |
| SP-ACQ-06 | ☁️ AWS S3 object ingest (`acquisition.file.s3`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (s3 connector, SDK-free SigV4; covers MinIO / GCS-interop); no proven end-to-end acquisition-node run |
| SP-ACQ-07 | 🌐 Azure Blob & ADLS Gen2 ingest (`acquisition.file.azure`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (azure blob connector); ADLS Gen2 semantics not proven |
| SP-ACQ-08 | 🪣 Google Cloud Storage ingest (`acquisition.file.gcs`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (gcs connector, native JSON API + service-account OAuth2); no proven end-to-end acquisition-node run |
| SP-ACQ-09 | 📤 Apache Kafka consumer (`acquisition.stream.kafka`) | Collectors & Ingestion | — | 🟡 | 🟡 | `acquisition` | **Premium connector, NOT in Basic (MODULE-REORG-1 P7, 2026-10-07): its own module `inspecto-connectors-kafka` (kafka-clients 3.9.x), staged from Standard up — a deliberate behaviour change; Basic's `inspecto-connectors.jar` registers seven factories.** Per-cycle Collector drain ships: frontier on the ledger watermark, re-keyed on land and durable across a restart, one uncommitted slice per partition, lag exported (STREAM-CONSUMER-1); broker consumer groups out by decision; proven against MockConsumer and a fake connector only, never a real broker |
| SP-ACQ-10 | 📨 Apache Pulsar consumer (`acquisition.stream.pulsar`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-11 | 📬 AWS Kinesis / SQS ingest (`acquisition.stream.kinesis`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-12 | 🐰 RabbitMQ AMQP subscriber (`acquisition.stream.rabbitmq`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-13 | 📡 MQTT IoT telemetry ingest (`acquisition.stream.mqtt`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-14 | 🔄 Change Data Capture (Debezium) (`acquisition.cdc.debezium`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-15 | 💾 Delta Lake table reader (`acquisition.lake.delta`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-16 | 🧊 Apache Iceberg table ingest (`acquisition.lake.iceberg`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-17 | ❄️ Snowflake / BigQuery ingest (`acquisition.db.cloud`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-18 | 🌐 REST API poller & paged ingest (`acquisition.api.rest`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-19 | 🪝 HTTP webhook listener endpoint (`acquisition.api.webhook`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-20 | 🔌 gRPC stream receiver (`acquisition.api.grpc`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-21 | 🐘 Postgres table or query copy (`acquisition.db.postgres`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | the `postgres` connector (`connector: postgres`; DuckDB's postgres extension, no driver): one table or read-only query per run, a read-only login, the password an `INSPECTO_PG_` reference only. Every edition, Basic included: Add data's Choose a table (BASIC-ELT-PROFILE-1 BE-4) |
| SP-PRS-01 | 📄 Delimited / CSV / TSV / PSV parser (`parser.delimited`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.delimited` |  |
| SP-PRS-02 | 📦 Fixed-width column slicer (`parser.fixedwidth`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.fixedwidth` | text (`record: line`) and fixed-length binary (`record: bytes`) |
| SP-PRS-03 | 🧬 JSON object & JSON Lines (NDJSON) parser (`parser.json`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.json` |  |
| SP-PRS-04 | 📑 Excel workbook parser (`parser.excel`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.xlsx` | needs the DuckDB `excel` extension in the bundle (multiformat X1) |
| SP-PRS-05 | 📑 XML / XPath / DOM unpacker (`parser.xml`) | Extraction & Format Parsers | 🟡 | 🟡 | 🟡 | `parser.plugin` | tree→segments bridge ships XML ingests; no XPath selector grammar yet |
| SP-PRS-06 | 📡 ASN.1 BER telecom CDR decoder (`parser.asn1.ber`) | Extraction & Format Parsers | — | — | ✅ | `parser.asn1` | Telecom industry pack (inspecto-telecom-asn1) over the asn-parser reactor (decoders, vendor plugins); Advanced (DATA-TIERS-1 DT-12) |
| SP-PRS-07 | 🔎 Named-group regex extractor (`parser.pattern.regex`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.text_regex` |  |
| SP-PRS-08 | 🧩 Custom ingester plugin (segments, multi-event) (`parser.plugin`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.plugin` | ParserPlugin SPI; `segments: {CALL, SMS}` |
| SP-PRS-09 | 🏷️ Key-value / logfmt parser (`parser.keyvalue`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-10 | 📜 YAML document slicer (`parser.yaml`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-11 | 📜 ASN.1 PER / XER / DER decoder (`parser.asn1.per`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-12 | 📠 Mainframe EBCDIC & COBOL copybook (`parser.mainframe.ebcdic`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-13 | ⚡ Protocol Buffers decoder (`parser.binary.protobuf`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-14 | 🦅 Apache Avro binary decoder (`parser.binary.avro`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-15 | 🌐 PCAP network packet slicer (`parser.binary.pcap`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-16 | 📜 Grok / Logstash expression matcher (`parser.pattern.grok`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-17 | 🖥️ Syslog RFC 5424 / RFC 3164 parser (`parser.pattern.syslog`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-01 | 🛡️ Schema registry & structural rejects (`quality.schema.validator`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `parser` | the declared schema on the Parse Step: typed fields, TRY_CAST at ingest, structural rejects → quarantine. ⛔ Only HALF of the old "Schema validator & type coercion" — coercion as an AUTHORING act folded into `transform.record` (2026-09-04). What cannot fold is the schema CONTRACT: the declared source column + target type are the cast-failure audit's denominator, and `SchemaCompatibility` gates edits to them BACKWARD |
| SP-DQ-02 | ⚠️ Constraint & range checker (Expectations) (`quality.constraint.check`) | Data Quality, Validation & Cleansing | 🟡 | 🟡 | 🟡 | `expectation` | Expectations evaluated per Dataset (`ExpectationEvaluator`), authored in the Expectations pane — not a Step: never on the canvas, so no workbench preview / lift / lower |
| SP-DQ-03 | 🧼 Exact-key deduplicator (within a Consignment) (`quality.dedup.exact`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.dedup` | `scope: consignment` (default) |
| SP-DQ-04 | ⏱️ Sliding time-window deduplicator (`quality.dedup.windowed`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.dedup` | D-9: `scope: window(P4D)` + the durable dedup ledger |
| SP-DQ-05 | 🗂️ File-grain duplicate guard (path / checksum / metadata / marker) (`quality.dedup.file`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `acquisition` | Collector `duplicate:` policy + marker dedup — a Guarantee, rides the Collector |
| SP-DQ-06 | 🧬 Schema drift & new-field detector (`quality.schema.drift`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `parser` | the header each file carries vs `raw.fields[]` — width always, names when the schema was authored from the header; one `quality.schema_drift` WARN Signal per batch |
| SP-DQ-07 | 🔍 Cluster & edit value normalizer (`quality.cluster.edit`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-08 | 🔍 Fuzzy string (Jaro-Winkler) matcher (`quality.match.fuzzy`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.similarity` | `transform.similarity` — a 0..1 score of two columns (Jaro-Winkler, Jaro, Levenshtein, token Jaccard; normalized first by default) on the Analytics Kernel, plus an optional candidate flag at a threshold; proposes candidates, never asserts identity (D-M1) (2026-10-17) |
| SP-DQ-09 | 🧮 Inline stream profiler & statistics (`quality.profiler.inline`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.profile` | per-column row/null/distinct counts and min/max, as a Step (2026-09-15) |
| SP-DQ-10 | 📊 Statistical & reservoir sampler (`quality.sample.reservoir`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.sample` | `transform.sample` — a fixed size or a fraction per group, chosen by an MD5 hash of a seed and a row key on the Analytics Kernel (`Sampler`): the same rows every run and in the JVM twin; never `USING SAMPLE` / `random()` (2026-10-17) |
| SP-DQ-11 | 🔤 Character map & code page transcoder (`quality.cleanse.transcode`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-12 | 🔒 PII masking & tokenization (`quality.pii.mask`) | Data Quality, Validation & Cleansing | — | — | ✅ | `transform.mask` | board SEC-08 — Advanced only; by classification (strictest-wins) or name, full / partial / hash, no reversible vault (2026-10-06) |
| SP-DQ-13 | 🔑 One-way salted cryptographic hasher (`quality.crypto.hash`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.hash` | Space-keyed HMAC-SHA-256 token per value, joinable within the Space; the key never leaves the Space's secrets (2026-10-06) |
| SP-DQ-14 | 🛡️ GDPR / CCPA field redactor (`quality.compliance.redact`) | Data Quality, Validation & Cleansing | — | — | 🔲 | — | board SEC-08 — Advanced only |
| SP-XFM-01 | 🧮 Record Transformer (`transform.record`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.sql` | ONE Step that shapes a record: sanitize (trim/pad/replace/case), cast, rename and computed columns — one row per output field over a typed function catalog, generating the SELECT it saves. Folded 2026-09-04 from `transform.expression` + `transform.cast` + `quality.cleanse.trim`, which were three catalog labels over this one grid. `transform.map` was deleted 2026-09-05: a stored `mapping.rules[]` is read as `fields[]` (EXPR → custom, CONCAT_DT → date.concat_parts, FILENAME_DATE → date.from_filename) |
| SP-XFM-02 | 🔽 Row filter (pre-parse regex / post-map predicate) (`transform.filter`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.filter` |  |
| SP-XFM-03 | 🔀 Router — case / clone branches with mid-branch steps (`transform.route`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.route` |  |
| SP-XFM-04 | ∑ Group-by summarizer (measures grammar) (`transform.summarize`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.summarize` |  |
| SP-XFM-05 | 🤝 Reference-store join (versioned references) (`transform.join`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.join` | at rest only — refused mid-branch (no reference resolver on the ingest lane) |
| SP-XFM-06 | 🗺️ Lookup & static map transcoder (`transform.lookup`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.lookup` | inline key=value map over one column (2026-09-06); a versioned reference is transform.join |
| SP-XFM-07 | 🔀 Dynamic pivot / transpose (`transform.matrix.pivot`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.matrix.pivot` | rows to columns over DECLARED values only (at most 200; a dynamic pivot is refused), one Measure-grammar aggregate per value (2026-10-17) |
| SP-XFM-08 | 🔄 Unpivot / column flattener (`transform.matrix.unpivot`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.matrix.unpivot` | wide columns to (name, value) rows by list or name pattern, cast to one value type (2026-10-06) |
| SP-XFM-09 | 🏆 Rank & Top-N pruner (`transform.analytics.rank`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.analytics.rank` | row_number / rank / dense_rank per group by a stated order, ties broken deterministically; keeps the top N or adds the rank (2026-10-17) |
| SP-XFM-10 | 💥 Array / object exploder & flattener (`transform.explode`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.explode` | one row per element of a LIST or JSON-array column, optional index (2026-10-06); the grandfathered `transform.split` stays the read-only ancestor |
| SP-XFM-11 | 🤝 Presorted stream merge joiner (`transform.join.merge`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — | the grandfathered `transform.merge` node type is the read-only ancestor |
| SP-XFM-12 | 🏛️ Slowly changing dimension (SCD Type 2) (`transform.dim.scd2`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-13 | 🔑 Monotonic surrogate key generator (`transform.key.surrogate`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-14 | 🏷️ DML row-action strategy flagger (`transform.dml.strategy`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-15 | ⚖️ Dataset differ & change compare (`transform.diff.compare`) | Transformers & Dimensional Modeling | 🟡 | 🟡 | 🟡 | `recon` | Reconciliation boards compare Datasets; not a chain step |
| SP-XFM-16 | 🏗️ Hierarchical XML / JSON document builder (`transform.builder.xml`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-17 | 📊 IFRS 15 / IFRS 9 revenue recognition engine (`transform.fintech.ifrs`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-18 | 📱 SIM box & bypass fraud detector (`transform.telecom.simbox`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-19 | 💵 Tariff, rating & usage billing engine (`transform.telecom.rating`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-20 | 🌍 Roaming TAP3 / CIBER surcharger (`transform.telecom.roaming`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-21 | 🚨 Velocity & impossible-travel anomaly (`transform.fintech.velocity`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-01 | 🏛️ Level-of-detail (LOD) fixed aggregator (`transform.analytics.lod`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-02 | 🏛️ LOD include / exclude context aggregator (`transform.analytics.lod_context`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-03 | ⏱️ Time-grain resampler & gap imputer (`transform.timeseries.resample`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.timeseries.resample` | one row per key per bucket (second..year, ISO weeks), every bucket present - count/sum fill 0, the rest empty; an explicit time zone for TIMESTAMPTZ (2026-10-17, ANALYTICS-CORE-1 M5) |
| SP-BI-04 | 📈 Period-over-period (YoY / MoM / WoW) shift (`transform.timeseries.shift`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.timeseries.shift` | the value a key had N buckets earlier, matched by bucket so a gap gives empty, not the wrong period (2026-10-17, ANALYTICS-CORE-1 M5) |
| SP-BI-05 | 📊 Running calculations & moving averages (`transform.analytics.running`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.running` | count/sum/avg/min/max per key over a time (60m) or row (5 rows) window, as a Step (2026-10-06); state is per run — cross-batch velocity stays a SQL-over-sink Job |
| SP-BI-06 | 🔮 Time-series forecaster (Holt-Winters) (`transform.timeseries.forecast`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.timeseries.forecast` | seasonal naive or Holt-Winters additive over a regular series, point forecast plus a prediction band per level (2026-10-17, ANALYTICS-CORE-1 S5) |
| SP-BI-07 | 📐 Semantic KPI calculator & Measure formulas (`transform.semantic.metric`) | Analytics, Time-Series & Semantic Modeling | 🟡 | 🟡 | 🟡 | `measure-grammar` | the Measure grammar (`count | agg(field)`) serves Studio + summarize; no named-KPI layer |
| SP-BI-08 | 🏷️ Template & runtime parameter injector (`transform.param.jinja`) | Analytics, Time-Series & Semantic Modeling | 🟡 | 🟡 | 🟡 | `sql.template` | the `sql.template` job resolves `$name` tokens; no Jinja |
| SP-BI-09 | 📦 Histogram & quantile binner (`transform.data.binning`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.data.binning` | `transform.data.binning` — equal-width or quantile (equal-frequency) bins of a number column, per group, with a bin number and label; a histogram is binning then summarize (count by bin), and the profiler's histogram shares the equal-width bin (2026-10-17, ANALYTICS-CORE-1 S3) |
| SP-BI-10 | ✂️ Smart custom string splitter (`transform.string.smart_split`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-11 | 🗺️ H3 hexagonal & geohash grid indexer (`transform.geo.h3`) | Analytics, Time-Series & Semantic Modeling | — | 🔲 | 🔲 | — | needs the DuckDB `spatial`/`h3` extension — board CP-09 gate |
| SP-BI-12 | 📍 Spatial polygon & point-in-polygon intersect (`transform.geo.spatial_join`) | Analytics, Time-Series & Semantic Modeling | — | 🔲 | 🔲 | — | needs the DuckDB `spatial` extension — deliberately not loaded (SqlSandbox) |
| SP-BI-13 | 🚨 Outlier detector & IQR boxplot fencer (`transform.stats.outlier`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.stats.outlier` | `transform.stats.outlier` — Tukey's fences (k, quantile mode) or a robust z (median and MAD) per group; every row kept with its fences or score and a flag (2026-10-17, ANALYTICS-CORE-1 S3) |
| SP-BI-14 | 🧮 Pearson & Spearman correlation matrix (`transform.stats.correlation`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.stats.correlation` | `transform.stats.correlation` — Pearson (default) or Spearman (`method: spearman`: Pearson of the within-group average ranks, ties share their average rank) plus the least-squares line (slope, intercept, r2) per column pair and group, as long-form rows (2026-10-17 Pearson, 2026-10-18 Spearman, ANALYTICS-CORE-1 S3) |
| SP-BI-15 | 📉 Change-point, regime & drift detector (`transform.timeseries.changepoint`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.timeseries.changepoint` | `transform.timeseries.changepoint` — over a residual series (actual minus expected) and its scale sigma: band breaches, regime changes (a run of K outside points) and drift (two-sided CUSUM), every row kept with 0/1 flags; the Business Assurance forecast template's rule (2026-11-01, ANALYTICS-STEPS-TS-1) |
| SP-BI-16 | 👥 Cohort retention matrix (`transform.cohort.retention`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.cohort.retention` | `transform.cohort.retention` — acquisition cohorts by hour..year on a stated clock, exact or on-or-after retention per period as of a stated date; a period not yet observable is absent, never 0 (2026-11-01, ANALYTICS-STEPS-TS-1) |
| SP-BI-17 | 🪜 Ordered conversion funnel (`transform.cohort.funnel`) | Analytics, Time-Series & Semantic Modeling | ✅ | ✅ | ✅ | `transform.cohort.funnel` | `transform.cohort.funnel` — 2..12 ordered steps per entity, earliest-first from the first step-1 event, with an optional window; entities per step and conversion from the previous and the first step (2026-11-01, ANALYTICS-STEPS-TS-1) |
| SP-ENR-01 | 📚 Reference-table enrichment (`*_enrich.toon`, Stage-2) (`enrichment.reference`) | Enrichment, Entity Resolution & AI/ML | ✅ | ✅ | ✅ | `enrichment` | the shipped enrichment job + versioned references |
| SP-ENR-02 | 🌍 GeoIP & ISP geolocation enricher (`enrichment.geoip`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-03 | ⚡ Redis / in-memory cache lookup (`enrichment.redis`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-04 | 🌐 Dynamic microservice REST enricher (`enrichment.rest`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-05 | 🗄️ Parameterized stored-procedure caller (`transform.db.procedure`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-06 | 🔗 Master entity resolution & record linkage (`enrichment.entity.link`) | Enrichment, Entity Resolution & AI/ML | ✅ | ✅ | ✅ | `transform.entity.link` | `transform.entity.link` — every row gets a resolution group: blocking keys + exact match keys (normalised as text, digits or as written) + union-find on the Analytics Kernel, caps fail closed; similarity only flags look-alikes for review, never merges (D-M1) (2026-10-17) |
| SP-ENR-07 | 🕸️ Graph cluster & connected-component tagger (`enrichment.graph.cluster`) | Enrichment, Entity Resolution & AI/ML | — | 🟡 | 🟡 | `link-analysis` | link-analysis VIEW ships (projection); no tagging step |
| SP-ENR-08 | 🤖 ONNX Runtime embedded inference (`ml.inference.onnx`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — | onnxruntime ships in the Advanced bundle inside `inspecto-intelligence` (its embeddings, ASSURE-INTELLIGENCE-BUNDLE-1); this Step Processor is not built |
| SP-ENR-09 | 🏷️ LLM zero-shot classifier & tagging (`ml.llm.classify`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — | still unbuilt; the assist agent now ships Standard+ (CP-14, `PKG-5` 2026-09-12) but this step kind does not exist |
| SP-ENR-10 | 📐 Text embeddings & vector generator (`ml.embedding.vector`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-CTL-01 | 🕳️ File sequence & gap integrity analyzer (`control.file.sequence_analyzer`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `gap` | Collector `gap_detection: {sequence}` → the gap node + SEQUENCE_GAP events |
| SP-CTL-02 | 🕳️ Sequence-gap & data-loss watchdog (`control.gap.detector`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `gap` | same detector; a gap raises the `SEQUENCE_GAP` event in EVERY edition, and `EventAlertBridge` raises it as a stored Alert on every edition, Basic included (P7 ALERT residue retirement 2026-10-08; it was an ALERT object only where the optional `inspecto-ops` module was present) |
| SP-CTL-03 | 🏷️ Audit metadata & lineage stamper (`control.audit.stamp`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `sink.persistent` | `filename_column` + the per-file/batch/lineage ledgers + `__consignment_id` provenance |
| SP-CTL-04 | ⏳ Throttle & rate limiter (`control.throttle`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `acquisition` | Collector `fetch.rate_limit` + intake caps + the concurrency broker |
| SP-CTL-05 | 🚦 Circuit breaker & fallback switch (`control.circuitbreaker`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `acquisition` | Collector `circuit_breaker` + `retry` |
| SP-CTL-06 | 🏁 Transaction & commit controller (`control.transaction.commit`) | Control, Governance & Sequence Integrity | 🟡 | 🟡 | 🟡 | `engine` | the BranchCommitCoordinator ledger + bounded COMMIT retry are engine-internal, not authorable |
| SP-CTL-07 | 🚨 Alert rule dispatcher (`control.alert.dispatch`) | Control, Governance & Sequence Integrity | — | 🟡 | 🟡 | `alert-rule` | Alert Rules over the ledgers → Alerts → channels (board CP-11/CP-15), authored in the Alert Rules pane — not a Step: never on the canvas, so no workbench preview / lift / lower. Standard+ (G9): `inspecto-ops` declares it through `EditionFeatureProvider`; Basic refuses every authoring door with `ERR_EDITION_FEATURE` and arms no rule copied onto disk |
| SP-CTL-08 | ⏱️ SLA timeout & heartbeat monitor (`control.sla.monitor`) | Control, Governance & Sequence Integrity | 🟡 | 🟡 | 🟡 | `completeness-kpi` | completeness KPI + `heartbeat` maintenance task; no SLA object |
| SP-SNK-01 | 📁 Parquet (snappy / zstd / gzip) partitioned store (`sink.file.parquet`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.persistent` | Hive `year=/month=/day=` partitions |
| SP-SNK-02 | 📄 CSV partitioned store (`sink.file.csv`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.persistent` | `output.format: CSV` |
| SP-SNK-03 | 🦆 DuckLake catalog (PostgreSQL) sink (`sink.ducklake`) | Sinks, Storage & Destinations | — | ✅ | ✅ | `sink.persistent` | `output.ducklake` / `sinks[].ducklake` — needs the postgresql sidecar. Standard+ (G9): `inspecto-backup` declares it through `EditionFeatureProvider`; Basic refuses `ducklake.enabled: true` at save (`ERR_EDITION_FEATURE`), at pipeline load and in `DuckLakeRegistrar` |
| SP-SNK-04 | 👁️ Derived view (no bytes, registered SQL) (`sink.view`) | Sinks, Storage & Destinations | 🟡 | 🟡 | 🟡 | `sink.view` | grandfathered node type; the Dataset/View surface replaced it |
| SP-SNK-05 | ☣️ Quarantine error-log store (`sink.quarantine`) | Sinks, Storage & Destinations | 🟡 | 🟡 | 🟡 | `sink.quarantine` | structural rejects + `errors/<base>_errors.csv` on every pipeline — not a Step you add: a quarantine node is lifted only for selector / segments pipelines, so there is no workbench preview / lift / lower of its own |
| SP-SNK-06 | 📜 Long-term compliance archive (`sink.archive`) | Sinks, Storage & Destinations | — | ✅ | ✅ | `acquisition` | Collector `post_action: MOVE archive_path` + the `backup` maintenance task. Standard+ (G9): `inspecto-backup` declares it through `EditionFeatureProvider`; Basic refuses `post_action.on_success: MOVE` (`ERR_EDITION_FEATURE`) at save, at pipeline load and before a remote acquisition — `RETAIN`/`DELETE`/`RENAME`/`TAG` and the local `dirs.backup` move stay in every edition |
| SP-SNK-07 | 🗄️ Delta Lake persistent table sink (`sink.lake.delta`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-08 | 🧊 Apache Iceberg append / upsert sink (`sink.lake.iceberg`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-09 | 📊 ClickHouse / StarRocks analytical sink (`sink.db.clickhouse`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-10 | 📑 Excel multi-tab report sink (`sink.file.excel`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.excel` | top-level `excel:` — named sheets of one .xlsx under the data root, a read-only SELECT per sheet, at-rest lane |
| SP-SNK-11 | 📤 Apache Kafka topic producer (`sink.stream.kafka`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-12 | 📨 AWS SQS / SNS event publisher (`sink.stream.aws`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-13 | 📧 Email & report dispatcher (`sink.notify.email`) | Sinks, Storage & Destinations | — | 🟡 | 🟡 | `mail.send` | the `mail.send` JOB + mail channels; not a chain sink |
| SP-SNK-14 | 🪝 Outbound webhook dispatcher (`sink.api.webhook`) | Sinks, Storage & Destinations | — | ✅ | ✅ | `sink.webhook` | top-level `webhook:` — JSON batches to an https Connection, at-rest lane, Standard+ |
| SP-SNK-15 | 🕳️ Dead-letter queue (`sink.dlq`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |

**Count:** 123 processors — 55 delivered, 17 partial, 51 planned.

**SP-CTL-07 behaviour change 2026-10-07 (operator, MODULE-REORG-P7-INCIDENTS slice 2): Alert records are durable on EVERY edition, Basic included.** Fired Alerts live in an Alert-owned store (`ALERTS` family, the Space's own `duckdb/inspecto-alerts.db`, `-Dalerts.backend=db` default), not in operational objects: wherever an Alert does fire on Basic (rule authoring is Standard+, so this is the rules a Basic Space was handed), it now remembers its Alerts across a restart — `GET /alerts` history survives and an open Alert is not re-fired. Basic still has NO Incidents, Cases or operational objects (CP-11 unchanged); only the Incident escalation is skipped there. A Basic operator works a stored Alert with `POST /alerts/{id}/ack|resolve` (`canWorkIncidents`, a no-op without a Subject). (Narrative kept here, outside the generated rows: the catalog `note` stays short.)

| ~~SP-DQ-09~~ | ~~🧹 Whitespace & string sanitizer (`quality.cleanse.trim`)~~ | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.sql` | **FOLDED into SP-XFM-01 (Record Transformer) 2026-09-04** — it is the `text.trim` / `text.pad_left` / `text.replace` rows of that grid, no longer a separate catalog entry |
| ~~SP-XFM-02~~ | ~~🔄 Field type cast & renamer matrix (`transform.cast`)~~ | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.sql` | **FOLDED into SP-XFM-01 2026-09-04** — cast is the `convert.type` row, rename is the Field-name alias |
