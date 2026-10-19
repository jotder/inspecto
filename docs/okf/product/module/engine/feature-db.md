---
type: FeatureDb
title: Execution engine — feature database
item: module.engine
description: Generated feature list for module.engine — features, routes, config kinds and processors, with Editions and add-ons.
tags: [feature-db, module]
---

# Execution engine — feature database (`module.engine`)

> **Generated** by `tools/render-feature-db.mjs` from the module manifests, `offerings/*.toon`, the processor catalog
> contract and `docs/FEATURE_INVENTORY.md` — never edit this table; change its sources and re-run the tool. The whole
> product is in `target/product-docs/feature-db.csv` (sortable). Hub: [index](index.md) · Product Map Item
> [`module.engine`](../../../../../product-map/module/engine.toon).

128 row(s).

| feature | type | module | editions | addon | status | key | source |
|---|---|---|---|---|---|---|---|
| `sink.persistent` | inventory | engine | basic;standard;advanced;preview |  | ga | `store: events` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.dedup` | inventory | engine | basic;standard;advanced;preview |  | ga | `key: [call_id]` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.derive` | inventory | engine | basic;standard;advanced;preview |  | ga | `fields: [{name,type:EXPR,expr}]` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.filter` | inventory | engine | basic;standard;advanced;preview |  | ga | `- { id: keep, type: transform.filter, where: "amount > 0" }` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.merge` (fan-in) | inventory | engine | basic;standard;advanced;preview |  | ga | `inputs: [a,b]` / `sql: "SELECT … JOIN … USING(id)"` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.route` (case / clone) | inventory | engine | basic;standard;advanced;preview |  | ga | `mode: case` (exclusive) or `mode: clone` (fan-out); `routes: [{rel,where},{rel,default:true}]` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.select` | inventory | engine | basic;standard;advanced;preview |  | ga | `columns: [a,b,c]` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.split` | inventory | engine | basic;standard;advanced;preview |  | ga | `UNNEST` | `docs/FEATURE_INVENTORY.md §1G` |
| `transform.validate` | inventory | engine | basic;standard;advanced;preview |  | ga | `check: "amount > 0"` → `valid` + `invalid` edges | `docs/FEATURE_INVENTORY.md §1G` |
| Fixed-length binary (`record: bytes`) | inventory | engine | basic;standard;advanced;preview |  | live | `processing:` / `  ingester: …FixedWidthRecordIngester` / `  ingester_config: { record_length: 256 }` | `docs/FEATURE_INVENTORY.md §1B` |
| Alert rule dispatcher | processor | engine | basic;standard;advanced;preview |  | partial | `control.alert.dispatch` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Avro binary decoder | processor | engine | basic;standard;advanced;preview |  | planned | `parser.binary.avro` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Iceberg append / upsert sink | processor | engine | basic;standard;advanced;preview |  | planned | `sink.lake.iceberg` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Iceberg table ingest | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.lake.iceberg` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Kafka consumer | processor | engine | basic;standard;advanced;preview |  | partial | `acquisition.stream.kafka` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Kafka topic producer | processor | engine | basic;standard;advanced;preview |  | planned | `sink.stream.kafka` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Apache Pulsar consumer | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.stream.pulsar` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Array / object exploder & flattener | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.explode` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| ASN.1 PER / XER / DER decoder | processor | engine | basic;standard;advanced;preview |  | planned | `parser.asn1.per` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Audit metadata & lineage stamper | processor | engine | basic;standard;advanced;preview |  | delivered | `control.audit.stamp` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| AWS Kinesis / SQS ingest | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.stream.kinesis` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| AWS S3 object ingest | processor | engine | basic;standard;advanced;preview |  | partial | `acquisition.file.s3` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| AWS SQS / SNS event publisher | processor | engine | basic;standard;advanced;preview |  | planned | `sink.stream.aws` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Azure Blob & ADLS Gen2 ingest | processor | engine | basic;standard;advanced;preview |  | partial | `acquisition.file.azure` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Change Data Capture (Debezium) | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.cdc.debezium` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Character map & code page transcoder | processor | engine | basic;standard;advanced;preview |  | planned | `quality.cleanse.transcode` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Circuit breaker & fallback switch | processor | engine | basic;standard;advanced;preview |  | delivered | `control.circuitbreaker` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| ClickHouse / StarRocks analytical sink | processor | engine | basic;standard;advanced;preview |  | planned | `sink.db.clickhouse` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Cluster & edit value normalizer | processor | engine | basic;standard;advanced;preview |  | planned | `quality.cluster.edit` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Constraint & range checker (Expectations) | processor | engine | basic;standard;advanced;preview |  | partial | `quality.constraint.check` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| CSV partitioned store | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.file.csv` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Custom ingester plugin (segments, multi-event) | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.plugin` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Dataset differ & change compare | processor | engine | basic;standard;advanced;preview |  | partial | `transform.diff.compare` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Dataset entry (re-ingest a registered Dataset) | processor | engine | basic;standard;advanced;preview |  | delivered | `acquisition.dataset` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Dead-letter queue | processor | engine | basic;standard;advanced;preview |  | planned | `sink.dlq` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Delimited / CSV / TSV / PSV parser | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.delimited` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Delta Lake persistent table sink | processor | engine | basic;standard;advanced;preview |  | planned | `sink.lake.delta` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Delta Lake table reader | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.lake.delta` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Derived view (no bytes, registered SQL) | processor | engine | basic;standard;advanced;preview |  | partial | `sink.view` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| DML row-action strategy flagger | processor | engine | basic;standard;advanced;preview |  | planned | `transform.dml.strategy` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| DuckLake catalog (PostgreSQL) sink | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.ducklake` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Dynamic microservice REST enricher | processor | engine | basic;standard;advanced;preview |  | planned | `enrichment.rest` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Dynamic pivot / transpose | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.matrix.pivot` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Email & report dispatcher | processor | engine | basic;standard;advanced;preview |  | partial | `sink.notify.email` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Exact-key deduplicator (within a Consignment) | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.dedup.exact` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Excel multi-tab report sink | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.file.excel` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Excel workbook parser | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.excel` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| File sequence & gap integrity analyzer | processor | engine | basic;standard;advanced;preview |  | delivered | `control.file.sequence_analyzer` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| File-grain duplicate guard (path / checksum / metadata / marker) | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.dedup.file` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Fixed-width column slicer | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.fixedwidth` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Fuzzy string (Jaro-Winkler) matcher | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.match.fuzzy` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| GDPR / CCPA field redactor | processor | engine | basic;standard;advanced;preview |  | planned | `quality.compliance.redact` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| GeoIP & ISP geolocation enricher | processor | engine | basic;standard;advanced;preview |  | planned | `enrichment.geoip` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Google Cloud Storage ingest | processor | engine | basic;standard;advanced;preview |  | partial | `acquisition.file.gcs` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Graph cluster & connected-component tagger | processor | engine | basic;standard;advanced;preview |  | partial | `enrichment.graph.cluster` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Grok / Logstash expression matcher | processor | engine | basic;standard;advanced;preview |  | planned | `parser.pattern.grok` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Group-by summarizer (measures grammar) | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.summarize` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| gRPC stream receiver | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.api.grpc` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| H3 hexagonal & geohash grid indexer | processor | engine | basic;standard;advanced;preview |  | planned | `transform.geo.h3` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Hierarchical XML / JSON document builder | processor | engine | basic;standard;advanced;preview |  | planned | `transform.builder.xml` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Histogram & quantile binner | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.data.binning` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| HTTP webhook listener endpoint | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.api.webhook` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| IFRS 15 / IFRS 9 revenue recognition engine | processor | engine | basic;standard;advanced;preview |  | planned | `transform.fintech.ifrs` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Inline stream profiler & statistics | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.profiler.inline` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| JDBC / SQL query batch reader | processor | engine | basic;standard;advanced;preview |  | delivered | `acquisition.db.jdbc` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| JSON object & JSON Lines (NDJSON) parser | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.json` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Key-value / logfmt parser | processor | engine | basic;standard;advanced;preview |  | planned | `parser.keyvalue` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Level-of-detail (LOD) fixed aggregator | processor | engine | basic;standard;advanced;preview |  | planned | `transform.analytics.lod` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| LLM zero-shot classifier & tagging | processor | engine | basic;standard;advanced;preview |  | planned | `ml.llm.classify` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Local / NFS directory watcher | processor | engine | basic;standard;advanced;preview |  | delivered | `acquisition.file.local` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| LOD include / exclude context aggregator | processor | engine | basic;standard;advanced;preview |  | planned | `transform.analytics.lod_context` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Long-term compliance archive | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.archive` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Lookup & static map transcoder | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.lookup` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Mainframe EBCDIC & COBOL copybook | processor | engine | basic;standard;advanced;preview |  | planned | `parser.mainframe.ebcdic` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Master entity resolution & record linkage | processor | engine | basic;standard;advanced;preview |  | delivered | `enrichment.entity.link` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Monotonic surrogate key generator | processor | engine | basic;standard;advanced;preview |  | planned | `transform.key.surrogate` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| MQTT IoT telemetry ingest | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.stream.mqtt` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Multi-sheet Excel workbook ingest | processor | engine | basic;standard;advanced;preview |  | partial | `acquisition.file.excel` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Named-group regex extractor | processor | engine | basic;standard;advanced;preview |  | delivered | `parser.pattern.regex` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| One-way salted cryptographic hasher | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.crypto.hash` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| ONNX Runtime embedded inference | processor | engine | basic;standard;advanced;preview |  | planned | `ml.inference.onnx` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Outbound webhook dispatcher | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.api.webhook` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Outlier detector & IQR boxplot fencer | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.stats.outlier` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Parameterized stored-procedure caller | processor | engine | basic;standard;advanced;preview |  | planned | `transform.db.procedure` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Parquet (snappy / zstd / gzip) partitioned store | processor | engine | basic;standard;advanced;preview |  | delivered | `sink.file.parquet` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| PCAP network packet slicer | processor | engine | basic;standard;advanced;preview |  | planned | `parser.binary.pcap` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Pearson & Spearman correlation matrix | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.stats.correlation` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Period-over-period (YoY / MoM / WoW) shift | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.timeseries.shift` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| PII masking & tokenization | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.pii.mask` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Presorted stream merge joiner | processor | engine | basic;standard;advanced;preview |  | planned | `transform.join.merge` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Protocol Buffers decoder | processor | engine | basic;standard;advanced;preview |  | planned | `parser.binary.protobuf` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Quarantine error-log store | processor | engine | basic;standard;advanced;preview |  | partial | `sink.quarantine` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| RabbitMQ AMQP subscriber | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.stream.rabbitmq` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Rank & Top-N pruner | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.analytics.rank` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Record Transformer | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.record` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Redis / in-memory cache lookup | processor | engine | basic;standard;advanced;preview |  | planned | `enrichment.redis` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Reference-store join (versioned references) | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.join` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Reference-table enrichment (`*_enrich.toon`, Stage-2) | processor | engine | basic;standard;advanced;preview |  | delivered | `enrichment.reference` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| REST API poller & paged ingest | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.api.rest` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Roaming TAP3 / CIBER surcharger | processor | engine | basic;standard;advanced;preview |  | planned | `transform.telecom.roaming` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Router — case / clone branches with mid-branch steps | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.route` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Row filter (pre-parse regex / post-map predicate) | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.filter` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Running calculations & moving averages | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.analytics.running` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Schema drift & new-field detector | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.schema.drift` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Schema registry & structural rejects | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.schema.validator` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Semantic KPI calculator & Measure formulas | processor | engine | basic;standard;advanced;preview |  | partial | `transform.semantic.metric` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Sequence-gap & data-loss watchdog | processor | engine | basic;standard;advanced;preview |  | delivered | `control.gap.detector` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| SFTP / FTPS remote ingest | processor | engine | basic;standard;advanced;preview |  | delivered | `acquisition.file.sftp` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| SIM box & bypass fraud detector | processor | engine | basic;standard;advanced;preview |  | planned | `transform.telecom.simbox` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| SLA timeout & heartbeat monitor | processor | engine | basic;standard;advanced;preview |  | partial | `control.sla.monitor` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Sliding time-window deduplicator | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.dedup.windowed` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Slowly changing dimension (SCD Type 2) | processor | engine | basic;standard;advanced;preview |  | planned | `transform.dim.scd2` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Smart custom string splitter | processor | engine | basic;standard;advanced;preview |  | planned | `transform.string.smart_split` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Snowflake / BigQuery ingest | processor | engine | basic;standard;advanced;preview |  | planned | `acquisition.db.cloud` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Spatial polygon & point-in-polygon intersect | processor | engine | basic;standard;advanced;preview |  | planned | `transform.geo.spatial_join` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Statistical & reservoir sampler | processor | engine | basic;standard;advanced;preview |  | delivered | `quality.sample.reservoir` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Syslog RFC 5424 / RFC 3164 parser | processor | engine | basic;standard;advanced;preview |  | planned | `parser.pattern.syslog` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Tariff, rating & usage billing engine | processor | engine | basic;standard;advanced;preview |  | planned | `transform.telecom.rating` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Template & runtime parameter injector | processor | engine | basic;standard;advanced;preview |  | partial | `transform.param.jinja` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Text embeddings & vector generator | processor | engine | basic;standard;advanced;preview |  | planned | `ml.embedding.vector` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Throttle & rate limiter | processor | engine | basic;standard;advanced;preview |  | delivered | `control.throttle` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Time-grain resampler & gap imputer | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.timeseries.resample` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Time-series forecaster (Holt-Winters) | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.timeseries.forecast` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Transaction & commit controller | processor | engine | basic;standard;advanced;preview |  | partial | `control.transaction.commit` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Unpivot / column flattener | processor | engine | basic;standard;advanced;preview |  | delivered | `transform.matrix.unpivot` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| Velocity & impossible-travel anomaly | processor | engine | basic;standard;advanced;preview |  | planned | `transform.fintech.velocity` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| XML / XPath / DOM unpacker | processor | engine | basic;standard;advanced;preview |  | partial | `parser.xml` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
| YAML document slicer | processor | engine | basic;standard;advanced;preview |  | planned | `parser.yaml` | `inspecto-ui/src/app/inspecto/contracts/processor-catalog.contract.json` |
