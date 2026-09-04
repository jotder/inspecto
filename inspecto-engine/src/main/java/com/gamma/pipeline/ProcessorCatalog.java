package com.gamma.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <b>Step Processor catalog</b> — the product's full processor taxonomy (families → processors),
 * INCLUDING the ones not built yet. Served read-only on {@code GET /pipelines/processor-catalog} so the
 * editor palettes can show every processor and render the undelivered ones inactive (operator decision
 * 2026-09-02: "all visible on screen, inactivated whichever not delivered"). It is the source the
 * EDITIONS.md "Step Processors" board table is generated from — one list, two renderings.
 *
 * <p>⚠ This is a TAXONOMY, not the executable type registry. {@link BuiltinNodeType}/{@link PipelineNodeTypes}
 * model exactly what the engine can run; a processor here is {@link Status#DELIVERED} only when it maps
 * onto one of those node types ({@code nodeType}) or a named engine capability ({@code capability} — a
 * Guarantee on the Collector, a job type, a Studio surface…). {@link Status#PLANNED} entries carry no
 * mapping and must never become addable by accident: the projection computes {@code addable} from the
 * mapped node type's authorability, never from the status alone.
 *
 * <p>⛔ Do not fold this into {@code BuiltinNodeType}: a planned processor has no runtime, no attributes
 * and no lift/lower, and the arming gates would have to learn to refuse it. Families are the product's
 * eight groupings and deliberately finer than {@link NodeCategory}.
 */
public final class ProcessorCatalog {

    /** Delivery status of a processor — the palette's active/inactive switch and the board's cell. */
    public enum Status { DELIVERED, PARTIAL, PLANNED }

    /** One of the eight processor families (the palette's sections). {@code icon} is a heroicons id. */
    public record Family(String code, String label, String icon) {}

    /**
     * One processor. {@code icon} is the heroicons id the palette draws for it (every processor has its own,
     * delivered or not — a meaningful glyph, never a shared family placeholder). {@code nodeType} is the executable node type a DELIVERED/PARTIAL processor maps
     * onto (null when it is a capability rather than a Step); {@code capability} names that capability
     * in words when so; {@code note} is the board's remark (the gap for a PARTIAL, the gate for a PLANNED).
     */
    public record Processor(String family, String id, String emoji, String icon, String label, Status status,
                            String nodeType, String capability, String note) {}

    public static final List<Family> FAMILIES = List.of(
            new Family("ACQ", "Collectors & Ingestion", "heroicons_outline:arrow-down-tray"),
            new Family("PRS", "Extraction & Format Parsers", "heroicons_outline:document-text"),
            new Family("DQ", "Data Quality, Validation & Cleansing", "heroicons_outline:shield-check"),
            new Family("XFM", "Transformers & Dimensional Modeling", "heroicons_outline:adjustments-horizontal"),
            new Family("BI", "Analytics, Time-Series & Semantic Modeling", "heroicons_outline:chart-bar"),
            new Family("ENR", "Enrichment, Entity Resolution & AI/ML", "heroicons_outline:sparkles"),
            new Family("CTL", "Control, Governance & Sequence Integrity", "heroicons_outline:signal"),
            new Family("SNK", "Sinks, Storage & Destinations", "heroicons_outline:archive-box"));

    private static Processor p(String family, String id, String emoji, String icon, String label, Status status,
                               String nodeType, String capability, String note) {
        return new Processor(family, id, emoji, icon, label, status, nodeType, capability, note);
    }

    public static final List<Processor> PROCESSORS = List.of(
            p("ACQ", "acquisition.file.local", "📁", "heroicons_outline:folder-open", "Local / NFS directory watcher", Status.DELIVERED, "acquisition", null, "LocalFileSystemConnector — the default collector"),
            p("ACQ", "acquisition.file.sftp", "🔒", "heroicons_outline:lock-closed", "SFTP / FTPS remote ingest", Status.DELIVERED, "acquisition", null, "inspecto-connectors (sftp, ftps; key auth, bastion tunnel, host-key pinning)"),
            p("ACQ", "acquisition.db.jdbc", "🗄️", "heroicons_outline:circle-stack", "JDBC / SQL query batch reader", Status.DELIVERED, "acquisition", null, "the db-export connector (`connector: db`, watermark column)"),
            p("ACQ", "acquisition.dataset", "🧱", "heroicons_outline:square-3-stack-3d", "Dataset entry (re-ingest a registered Dataset)", Status.DELIVERED, "acquisition", null, "UI-S7: `connector: dataset` + `on:dataset` trigger"),
            p("ACQ", "acquisition.file.excel", "📑", "heroicons_outline:table-cells", "Multi-sheet Excel workbook ingest", Status.PARTIAL, "parser.xlsx", null, "the xlsx PARSER is delivered; per-sheet workbook fan-out as an acquisition is not"),
            p("ACQ", "acquisition.file.s3", "☁️", "heroicons_outline:cloud-arrow-down", "AWS S3 object ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.file.azure", "🌐", "heroicons_outline:cloud", "Azure Blob & ADLS Gen2 ingest", Status.PARTIAL, "acquisition", null, "Connection kind exists (azure blob connector); ADLS Gen2 semantics not proven"),
            p("ACQ", "acquisition.file.gcs", "🪣", "heroicons_outline:cloud-arrow-down", "Google Cloud Storage ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.kafka", "📤", "heroicons_outline:queue-list", "Apache Kafka consumer", Status.PARTIAL, "acquisition", null, "Connection kind exists (kafka); consumer-group ingest as a Collector not proven"),
            p("ACQ", "acquisition.stream.pulsar", "📨", "heroicons_outline:paper-airplane", "Apache Pulsar consumer", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.kinesis", "📬", "heroicons_outline:inbox-stack", "AWS Kinesis / SQS ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.rabbitmq", "🐰", "heroicons_outline:inbox-arrow-down", "RabbitMQ AMQP subscriber", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.mqtt", "📡", "heroicons_outline:signal", "MQTT IoT telemetry ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.cdc.debezium", "🔄", "heroicons_outline:arrow-path", "Change Data Capture (Debezium)", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.lake.delta", "💾", "heroicons_outline:server-stack", "Delta Lake table reader", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.lake.iceberg", "🧊", "heroicons_outline:cube", "Apache Iceberg table ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.db.cloud", "❄️", "heroicons_outline:server", "Snowflake / BigQuery ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.rest", "🌐", "heroicons_outline:globe-alt", "REST API poller & paged ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.webhook", "🪝", "heroicons_outline:link", "HTTP webhook listener endpoint", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.grpc", "🔌", "heroicons_outline:bolt", "gRPC stream receiver", Status.PLANNED, null, null, null),
            p("PRS", "parser.delimited", "📄", "heroicons_outline:table-cells", "Delimited / CSV / TSV / PSV parser", Status.DELIVERED, "parser.delimited", null, null),
            p("PRS", "parser.fixedwidth", "📦", "heroicons_outline:view-columns", "Fixed-width column slicer", Status.DELIVERED, "parser.fixedwidth", null, "text (`record: line`) and fixed-length binary (`record: bytes`)"),
            p("PRS", "parser.json", "🧬", "heroicons_outline:code-bracket", "JSON object & JSON Lines (NDJSON) parser", Status.DELIVERED, "parser.json", null, null),
            p("PRS", "parser.excel", "📑", "heroicons_outline:table-cells", "Excel workbook parser", Status.DELIVERED, "parser.xlsx", null, "needs the DuckDB `excel` extension in the bundle (multiformat X1)"),
            p("PRS", "parser.xml", "📑", "heroicons_outline:code-bracket-square", "XML / XPath / DOM unpacker", Status.PARTIAL, "parser.plugin", null, "tree→segments bridge ships XML ingests; no XPath selector grammar yet"),
            p("PRS", "parser.asn1.ber", "📡", "heroicons_outline:cpu-chip", "ASN.1 BER telecom CDR decoder", Status.DELIVERED, null, "parser.asn1", "asn-parser reactor (decoders, vendor plugins)"),
            p("PRS", "parser.pattern.regex", "🔎", "heroicons_outline:magnifying-glass", "Named-group regex extractor", Status.DELIVERED, "parser.text_regex", null, null),
            p("PRS", "parser.plugin", "🧩", "heroicons_outline:puzzle-piece", "Custom ingester plugin (segments, multi-event)", Status.DELIVERED, "parser.plugin", null, "ParserPlugin SPI; `segments: {CALL, SMS}`"),
            p("PRS", "parser.keyvalue", "🏷️", "heroicons_outline:tag", "Key-value / logfmt parser", Status.PLANNED, null, null, null),
            p("PRS", "parser.yaml", "📜", "heroicons_outline:document-text", "YAML document slicer", Status.PLANNED, null, null, null),
            p("PRS", "parser.asn1.per", "📜", "heroicons_outline:cpu-chip", "ASN.1 PER / XER / DER decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.mainframe.ebcdic", "📠", "heroicons_outline:computer-desktop", "Mainframe EBCDIC & COBOL copybook", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.protobuf", "⚡", "heroicons_outline:bolt", "Protocol Buffers decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.avro", "🦅", "heroicons_outline:document-duplicate", "Apache Avro binary decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.pcap", "🌐", "heroicons_outline:wifi", "PCAP network packet slicer", Status.PLANNED, null, null, null),
            p("PRS", "parser.pattern.grok", "📜", "heroicons_outline:command-line", "Grok / Logstash expression matcher", Status.PLANNED, null, null, null),
            p("PRS", "parser.pattern.syslog", "🖥️", "heroicons_outline:bars-3-bottom-left", "Syslog RFC 5424 / RFC 3164 parser", Status.PLANNED, null, null, null),
            p("DQ", "quality.schema.validator", "🛡️", "heroicons_outline:shield-check", "Schema validator & type coercion", Status.DELIVERED, "transform.map", null, "the schema registry: typed fields, TRY_CAST, structural rejects → quarantine"),
            p("DQ", "quality.constraint.check", "⚠️", "heroicons_outline:exclamation-triangle", "Constraint & range checker (Expectations)", Status.DELIVERED, null, "expectation", "Expectations evaluated per Dataset (`ExpectationEvaluator`); not a mid-chain step"),
            p("DQ", "quality.dedup.exact", "🧼", "heroicons_outline:document-duplicate", "Exact-key deduplicator (within a Consignment)", Status.DELIVERED, "transform.dedup", null, "`scope: consignment` (default)"),
            p("DQ", "quality.dedup.windowed", "⏱️", "heroicons_outline:clock", "Sliding time-window deduplicator", Status.DELIVERED, "transform.dedup", null, "D-9: `scope: window(P4D)` + the durable dedup ledger"),
            p("DQ", "quality.dedup.file", "🗂️", "heroicons_outline:folder-minus", "File-grain duplicate guard (path / checksum / metadata / marker)", Status.DELIVERED, "acquisition", null, "Collector `duplicate:` policy + marker dedup — a Guarantee, rides the Collector"),
            p("DQ", "quality.schema.drift", "🧬", "heroicons_outline:arrow-trending-up", "Schema drift & new-field detector", Status.PARTIAL, null, "expectation", "multi-schema dispatch refuses unknown shapes; no drift REPORT yet"),
            p("DQ", "quality.cluster.edit", "🔍", "heroicons_outline:squares-2x2", "Cluster & edit value normalizer", Status.PLANNED, null, null, null),
            p("DQ", "quality.match.fuzzy", "🔍", "heroicons_outline:magnifying-glass-circle", "Fuzzy string (Jaro-Winkler) matcher", Status.PLANNED, null, null, null),
            p("DQ", "quality.cleanse.trim", "🧹", "heroicons_outline:scissors", "Whitespace & string sanitizer", Status.PARTIAL, "transform.map", null, "any `EXPR` rule does it today; no dedicated step"),
            p("DQ", "quality.profiler.inline", "🧮", "heroicons_outline:chart-bar-square", "Inline stream profiler & statistics", Status.PARTIAL, null, "storage_report", "storage/completeness KPIs exist; no per-column profile step"),
            p("DQ", "quality.sample.reservoir", "📊", "heroicons_outline:beaker", "Statistical & reservoir sampler", Status.PLANNED, null, null, null),
            p("DQ", "quality.cleanse.transcode", "🔤", "heroicons_outline:language", "Character map & code page transcoder", Status.PLANNED, null, null, null),
            p("DQ", "quality.pii.mask", "🔒", "heroicons_outline:eye-slash", "PII masking & tokenization", Status.PLANNED, null, null, "board SEC-08 — Enterprise only"),
            p("DQ", "quality.crypto.hash", "🔑", "heroicons_outline:finger-print", "One-way salted cryptographic hasher", Status.PLANNED, null, null, null),
            p("DQ", "quality.compliance.redact", "🛡️", "heroicons_outline:shield-exclamation", "GDPR / CCPA field redactor", Status.PLANNED, null, null, "board SEC-08 — Enterprise only"),
            p("XFM", "transform.expression", "🧮", "heroicons_outline:variable", "Expression builder & computed columns", Status.DELIVERED, "transform.sql", null, "computed columns as SELECT expressions in the SQL Step (`transform.sql`); the `EXPR` / `CONCAT_DT` / `FILENAME_DATE` map rules remain"),
            p("XFM", "transform.cast", "🔄", "heroicons_outline:arrows-right-left", "Field type cast & renamer matrix", Status.DELIVERED, "transform.sql", null, "type casts stay on the Parse step's Types section (declarative typing); renames/aliases via the SQL Step (`transform.sql`)"),
            p("XFM", "transform.filter", "🔽", "heroicons_outline:funnel", "Row filter (pre-parse regex / post-map predicate)", Status.DELIVERED, "transform.filter", null, null),
            p("XFM", "transform.route", "🔀", "heroicons_outline:arrows-pointing-out", "Router — case / clone branches with mid-branch steps", Status.DELIVERED, "transform.route", null, null),
            p("XFM", "transform.summarize", "∑", "heroicons_outline:calculator", "Group-by summarizer (measures grammar)", Status.DELIVERED, "transform.summarize", null, null),
            p("XFM", "transform.join", "🤝", "heroicons_outline:link", "Reference-store join (versioned references)", Status.DELIVERED, "transform.join", null, "at rest only — refused mid-branch (no reference resolver on the ingest lane)"),
            p("XFM", "transform.lookup", "🗺️", "heroicons_outline:map", "Lookup & static map transcoder", Status.PARTIAL, "transform.join", null, "a reference join covers it; no inline static map"),
            p("XFM", "transform.matrix.pivot", "🔀", "heroicons_outline:arrows-up-down", "Dynamic pivot / transpose", Status.PLANNED, null, null, null),
            p("XFM", "transform.matrix.unpivot", "🔄", "heroicons_outline:bars-4", "Unpivot / column flattener", Status.PLANNED, null, null, null),
            p("XFM", "transform.analytics.rank", "🏆", "heroicons_outline:trophy", "Rank & Top-N pruner", Status.PLANNED, null, null, null),
            p("XFM", "transform.explode", "💥", "heroicons_outline:squares-plus", "Array / object exploder & flattener", Status.PLANNED, null, null, "the grandfathered `transform.split` node type is the read-only ancestor"),
            p("XFM", "transform.join.merge", "🤝", "heroicons_outline:arrows-pointing-in", "Presorted stream merge joiner", Status.PLANNED, null, null, "the grandfathered `transform.merge` node type is the read-only ancestor"),
            p("XFM", "transform.dim.scd2", "🏛️", "heroicons_outline:building-library", "Slowly changing dimension (SCD Type 2)", Status.PLANNED, null, null, null),
            p("XFM", "transform.key.surrogate", "🔑", "heroicons_outline:key", "Monotonic surrogate key generator", Status.PLANNED, null, null, null),
            p("XFM", "transform.dml.strategy", "🏷️", "heroicons_outline:tag", "DML row-action strategy flagger", Status.PLANNED, null, null, null),
            p("XFM", "transform.diff.compare", "⚖️", "heroicons_outline:scale", "Dataset differ & change compare", Status.PARTIAL, null, "recon", "Reconciliation boards compare Datasets; not a chain step"),
            p("XFM", "transform.builder.xml", "🏗️", "heroicons_outline:code-bracket-square", "Hierarchical XML / JSON document builder", Status.PLANNED, null, null, null),
            p("XFM", "transform.fintech.ifrs", "📊", "heroicons_outline:banknotes", "IFRS 15 / IFRS 9 revenue recognition engine", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.simbox", "📱", "heroicons_outline:device-phone-mobile", "SIM box & bypass fraud detector", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.rating", "💵", "heroicons_outline:receipt-percent", "Tariff, rating & usage billing engine", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.roaming", "🌍", "heroicons_outline:globe-americas", "Roaming TAP3 / CIBER surcharger", Status.PLANNED, null, null, null),
            p("XFM", "transform.fintech.velocity", "🚨", "heroicons_outline:rocket-launch", "Velocity & impossible-travel anomaly", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.lod", "🏛️", "heroicons_outline:building-library", "Level-of-detail (LOD) fixed aggregator", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.lod_context", "🏛️", "heroicons_outline:rectangle-group", "LOD include / exclude context aggregator", Status.PLANNED, null, null, null),
            p("BI", "transform.timeseries.resample", "⏱️", "heroicons_outline:clock", "Time-grain resampler & gap imputer", Status.PARTIAL, null, "measure-grammar", "time grains exist in Studio queries (`QuerySpec.grains`); no resampling step"),
            p("BI", "transform.timeseries.shift", "📈", "heroicons_outline:presentation-chart-line", "Period-over-period (YoY / MoM / WoW) shift", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.running", "📊", "heroicons_outline:arrow-trending-up", "Running calculations & moving averages", Status.PLANNED, null, null, null),
            p("BI", "transform.timeseries.forecast", "🔮", "heroicons_outline:light-bulb", "Time-series forecaster (Holt-Winters)", Status.PLANNED, null, null, null),
            p("BI", "transform.semantic.metric", "📐", "heroicons_outline:chart-pie", "Semantic KPI calculator & Measure formulas", Status.PARTIAL, null, "measure-grammar", "the Measure grammar (`count | agg(field)`) serves Studio + summarize; no named-KPI layer"),
            p("BI", "transform.param.jinja", "🏷️", "heroicons_outline:hashtag", "Template & runtime parameter injector", Status.PARTIAL, null, "sql.template", "the `sql.template` job resolves `$name` tokens; no Jinja"),
            p("BI", "transform.data.binning", "📦", "heroicons_outline:chart-bar", "Histogram & quantile binner", Status.PLANNED, null, null, null),
            p("BI", "transform.string.smart_split", "✂️", "heroicons_outline:scissors", "Smart custom string splitter", Status.PLANNED, null, null, null),
            p("BI", "transform.geo.h3", "🗺️", "heroicons_outline:globe-europe-africa", "H3 hexagonal & geohash grid indexer", Status.PLANNED, null, null, "needs the DuckDB `spatial`/`h3` extension — board CP-09 gate"),
            p("BI", "transform.geo.spatial_join", "📍", "heroicons_outline:map-pin", "Spatial polygon & point-in-polygon intersect", Status.PLANNED, null, null, "needs the DuckDB `spatial` extension — deliberately not loaded (SqlSandbox)"),
            p("BI", "transform.stats.outlier", "🚨", "heroicons_outline:exclamation-circle", "Outlier detector & IQR boxplot fencer", Status.PLANNED, null, null, null),
            p("BI", "transform.stats.correlation", "🧮", "heroicons_outline:squares-2x2", "Pearson & Spearman correlation matrix", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.reference", "📚", "heroicons_outline:book-open", "Reference-table enrichment (`*_enrich.toon`, Stage-2)", Status.DELIVERED, "enrichment", null, "the shipped enrichment job + versioned references"),
            p("ENR", "enrichment.geoip", "🌍", "heroicons_outline:globe-alt", "GeoIP & ISP geolocation enricher", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.redis", "⚡", "heroicons_outline:bolt", "Redis / in-memory cache lookup", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.rest", "🌐", "heroicons_outline:globe-alt", "Dynamic microservice REST enricher", Status.PLANNED, null, null, null),
            p("ENR", "transform.db.procedure", "🗄️", "heroicons_outline:circle-stack", "Parameterized stored-procedure caller", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.entity.link", "🔗", "heroicons_outline:user-group", "Master entity resolution & record linkage", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.graph.cluster", "🕸️", "heroicons_outline:share", "Graph cluster & connected-component tagger", Status.PARTIAL, null, "link-analysis", "link-analysis VIEW ships (projection); no tagging step"),
            p("ENR", "ml.inference.onnx", "🤖", "heroicons_outline:cpu-chip", "ONNX Runtime embedded inference", Status.PLANNED, null, null, "the optional `inspecto-intelligence` module carries onnxruntime; never bundled"),
            p("ENR", "ml.llm.classify", "🏷️", "heroicons_outline:sparkles", "LLM zero-shot classifier & tagging", Status.PLANNED, null, null, "assist/intelligence agents are never bundled (CP-14)"),
            p("ENR", "ml.embedding.vector", "📐", "heroicons_outline:viewfinder-circle", "Text embeddings & vector generator", Status.PLANNED, null, null, null),
            p("CTL", "control.file.sequence_analyzer", "🕳️", "heroicons_outline:list-bullet", "File sequence & gap integrity analyzer", Status.DELIVERED, "gap", null, "Collector `gap_detection: {sequence}` → the gap node + SEQUENCE_GAP events"),
            p("CTL", "control.gap.detector", "🕳️", "heroicons_outline:bell-alert", "Sequence-gap & data-loss watchdog", Status.DELIVERED, "gap", null, "same detector; gaps raise ALERT objects via the EventObjectBridge"),
            p("CTL", "control.audit.stamp", "🏷️", "heroicons_outline:clipboard-document-check", "Audit metadata & lineage stamper", Status.DELIVERED, "sink.persistent", null, "`filename_column` + the per-file/batch/lineage ledgers + `__batch_id` provenance"),
            p("CTL", "control.throttle", "⏳", "heroicons_outline:pause-circle", "Throttle & rate limiter", Status.DELIVERED, "acquisition", null, "Collector `fetch.rate_limit` + intake caps + the concurrency broker"),
            p("CTL", "control.circuitbreaker", "🚦", "heroicons_outline:bolt-slash", "Circuit breaker & fallback switch", Status.DELIVERED, "acquisition", null, "Collector `circuit_breaker` + `retry`"),
            p("CTL", "control.transaction.commit", "🏁", "heroicons_outline:check-badge", "Transaction & commit controller", Status.PARTIAL, null, "engine", "the BranchCommitCoordinator ledger + bounded COMMIT retry are engine-internal, not authorable"),
            p("CTL", "control.alert.dispatch", "🚨", "heroicons_outline:megaphone", "Alert rule dispatcher", Status.DELIVERED, null, "alert-rule", "Alert Rules over the ledgers → Alerts → channels; board CP-11/CP-15"),
            p("CTL", "control.sla.monitor", "⏱️", "heroicons_outline:clock", "SLA timeout & heartbeat monitor", Status.PARTIAL, null, "completeness-kpi", "completeness KPI + `heartbeat` maintenance task; no SLA object"),
            p("SNK", "sink.file.parquet", "📁", "heroicons_outline:archive-box", "Parquet (snappy / zstd / gzip) partitioned store", Status.DELIVERED, "sink.persistent", null, "Hive `year=/month=/day=` partitions"),
            p("SNK", "sink.file.csv", "📄", "heroicons_outline:document-text", "CSV partitioned store", Status.DELIVERED, "sink.persistent", null, "`output.format: CSV`"),
            p("SNK", "sink.ducklake", "🦆", "heroicons_outline:server-stack", "DuckLake catalog (PostgreSQL) sink", Status.DELIVERED, "sink.persistent", null, "`output.ducklake` — needs the postgresql sidecar (Standard+)"),
            p("SNK", "sink.view", "👁️", "heroicons_outline:eye", "Derived view (no bytes, registered SQL)", Status.PARTIAL, "sink.view", null, "grandfathered node type; the Dataset/View surface replaced it"),
            p("SNK", "sink.quarantine", "☣️", "heroicons_outline:shield-exclamation", "Quarantine error-log store", Status.DELIVERED, null, "sink.quarantine", "structural rejects + `errors/<base>_errors.csv`"),
            p("SNK", "sink.archive", "📜", "heroicons_outline:archive-box-arrow-down", "Long-term compliance archive", Status.DELIVERED, "acquisition", null, "Collector `post_action: MOVE archive_path` + the `backup` maintenance task"),
            p("SNK", "sink.lake.delta", "🗄️", "heroicons_outline:server-stack", "Delta Lake persistent table sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.lake.iceberg", "🧊", "heroicons_outline:cube", "Apache Iceberg append / upsert sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.db.clickhouse", "📊", "heroicons_outline:chart-bar-square", "ClickHouse / StarRocks analytical sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.file.excel", "📑", "heroicons_outline:table-cells", "Excel multi-tab report sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.stream.kafka", "📤", "heroicons_outline:queue-list", "Apache Kafka topic producer", Status.PLANNED, null, null, null),
            p("SNK", "sink.stream.aws", "📨", "heroicons_outline:paper-airplane", "AWS SQS / SNS event publisher", Status.PLANNED, null, null, null),
            p("SNK", "sink.notify.email", "📧", "heroicons_outline:envelope", "Email & report dispatcher", Status.PARTIAL, null, "mail.send", "the `mail.send` JOB + mail channels; not a chain sink"),
            p("SNK", "sink.api.webhook", "🪝", "heroicons_outline:link", "Outbound webhook dispatcher", Status.PARTIAL, null, "channel", "webhook notification channel exists; not a chain sink"),
            p("SNK", "sink.dlq", "🕳️", "heroicons_outline:archive-box-x-mark", "Dead-letter queue", Status.PLANNED, null, null, null));

    static {
        Set<String> ids = new HashSet<>();
        Set<String> fams = new HashSet<>();
        for (Family f : FAMILIES) fams.add(f.code());
        for (Processor p : PROCESSORS) {
            if (!ids.add(p.id())) throw new IllegalStateException("duplicate processor id " + p.id());
            if (!fams.contains(p.family())) throw new IllegalStateException(p.id() + " names unknown family " + p.family());
            if (p.status() == Status.PLANNED && (p.nodeType() != null || p.capability() != null))
                throw new IllegalStateException(p.id() + " is PLANNED but maps onto something — fix the status or the mapping");
            if (p.status() != Status.PLANNED && p.nodeType() == null && p.capability() == null)
                throw new IllegalStateException(p.id() + " is " + p.status() + " but maps onto nothing");
        }
    }

    private ProcessorCatalog() {}

    /** The catalog as served: {@code {families:[…], processors:[…]}}, in declaration order. */
    public static Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> fams = new ArrayList<>();
        for (Family f : FAMILIES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("label", f.label());
            m.put("icon", f.icon());
            fams.add(m);
        }
        out.put("families", fams);
        List<Map<String, Object>> procs = new ArrayList<>();
        for (Processor p : PROCESSORS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("family", p.family());
            m.put("label", p.label());
            m.put("emoji", p.emoji());
            m.put("icon", p.icon());
            m.put("status", p.status().name().toLowerCase(java.util.Locale.ROOT));
            if (p.nodeType() != null) m.put("nodeType", p.nodeType());
            if (p.capability() != null) m.put("capability", p.capability());
            if (p.note() != null) m.put("note", p.note());
            procs.add(m);
        }
        out.put("processors", procs);
        return out;
    }
}
