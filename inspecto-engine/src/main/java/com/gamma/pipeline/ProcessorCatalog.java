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
     * One processor. {@code nodeType} is the executable node type a DELIVERED/PARTIAL processor maps
     * onto (null when it is a capability rather than a Step); {@code capability} names that capability
     * in words when so; {@code note} is the board's remark (the gap for a PARTIAL, the gate for a PLANNED).
     */
    public record Processor(String family, String id, String emoji, String label, Status status,
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

    private static Processor p(String family, String id, String emoji, String label, Status status,
                               String nodeType, String capability, String note) {
        return new Processor(family, id, emoji, label, status, nodeType, capability, note);
    }

    public static final List<Processor> PROCESSORS = List.of(
            p("ACQ", "acquisition.file.local", "📁", "Local / NFS directory watcher", Status.DELIVERED, "acquisition", null, "LocalFileSystemConnector — the default collector"),
            p("ACQ", "acquisition.file.sftp", "🔒", "SFTP / FTPS remote ingest", Status.DELIVERED, "acquisition", null, "inspecto-connectors (sftp, ftps; key auth, bastion tunnel, host-key pinning)"),
            p("ACQ", "acquisition.db.jdbc", "🗄️", "JDBC / SQL query batch reader", Status.DELIVERED, "acquisition", null, "the db-export connector (`connector: db`, watermark column)"),
            p("ACQ", "acquisition.dataset", "🧱", "Dataset entry (re-ingest a registered Dataset)", Status.DELIVERED, "acquisition", null, "UI-S7: `connector: dataset` + `on:dataset` trigger"),
            p("ACQ", "acquisition.file.excel", "📑", "Multi-sheet Excel workbook ingest", Status.PARTIAL, "parser.xlsx", null, "the xlsx PARSER is delivered; per-sheet workbook fan-out as an acquisition is not"),
            p("ACQ", "acquisition.file.s3", "☁️", "AWS S3 object ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.file.azure", "🌐", "Azure Blob & ADLS Gen2 ingest", Status.PARTIAL, "acquisition", null, "Connection kind exists (azure blob connector); ADLS Gen2 semantics not proven"),
            p("ACQ", "acquisition.file.gcs", "🪣", "Google Cloud Storage ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.kafka", "📤", "Apache Kafka consumer", Status.PARTIAL, "acquisition", null, "Connection kind exists (kafka); consumer-group ingest as a Collector not proven"),
            p("ACQ", "acquisition.stream.pulsar", "📨", "Apache Pulsar consumer", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.kinesis", "📬", "AWS Kinesis / SQS ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.rabbitmq", "🐰", "RabbitMQ AMQP subscriber", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.stream.mqtt", "📡", "MQTT IoT telemetry ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.cdc.debezium", "🔄", "Change Data Capture (Debezium)", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.lake.delta", "💾", "Delta Lake table reader", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.lake.iceberg", "🧊", "Apache Iceberg table ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.db.cloud", "❄️", "Snowflake / BigQuery ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.rest", "🌐", "REST API poller & paged ingest", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.webhook", "🪝", "HTTP webhook listener endpoint", Status.PLANNED, null, null, null),
            p("ACQ", "acquisition.api.grpc", "🔌", "gRPC stream receiver", Status.PLANNED, null, null, null),
            p("PRS", "parser.delimited", "📄", "Delimited / CSV / TSV / PSV parser", Status.DELIVERED, "parser.delimited", null, null),
            p("PRS", "parser.fixedwidth", "📦", "Fixed-width column slicer", Status.DELIVERED, "parser.fixedwidth", null, "text (`record: line`) and fixed-length binary (`record: bytes`)"),
            p("PRS", "parser.json", "🧬", "JSON object & JSON Lines (NDJSON) parser", Status.DELIVERED, "parser.json", null, null),
            p("PRS", "parser.excel", "📑", "Excel workbook parser", Status.DELIVERED, "parser.xlsx", null, "needs the DuckDB `excel` extension in the bundle (multiformat X1)"),
            p("PRS", "parser.xml", "📑", "XML / XPath / DOM unpacker", Status.PARTIAL, "parser.plugin", null, "tree→segments bridge ships XML ingests; no XPath selector grammar yet"),
            p("PRS", "parser.asn1.ber", "📡", "ASN.1 BER telecom CDR decoder", Status.DELIVERED, null, "parser.asn1", "asn-parser reactor (decoders, vendor plugins)"),
            p("PRS", "parser.pattern.regex", "🔎", "Named-group regex extractor", Status.DELIVERED, "parser.text_regex", null, null),
            p("PRS", "parser.plugin", "🧩", "Custom ingester plugin (segments, multi-event)", Status.DELIVERED, "parser.plugin", null, "ParserPlugin SPI; `segments: {CALL, SMS}`"),
            p("PRS", "parser.keyvalue", "🏷️", "Key-value / logfmt parser", Status.PLANNED, null, null, null),
            p("PRS", "parser.yaml", "📜", "YAML document slicer", Status.PLANNED, null, null, null),
            p("PRS", "parser.asn1.per", "📜", "ASN.1 PER / XER / DER decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.mainframe.ebcdic", "📠", "Mainframe EBCDIC & COBOL copybook", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.protobuf", "⚡", "Protocol Buffers decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.avro", "🦅", "Apache Avro binary decoder", Status.PLANNED, null, null, null),
            p("PRS", "parser.binary.pcap", "🌐", "PCAP network packet slicer", Status.PLANNED, null, null, null),
            p("PRS", "parser.pattern.grok", "📜", "Grok / Logstash expression matcher", Status.PLANNED, null, null, null),
            p("PRS", "parser.pattern.syslog", "🖥️", "Syslog RFC 5424 / RFC 3164 parser", Status.PLANNED, null, null, null),
            p("DQ", "quality.schema.validator", "🛡️", "Schema validator & type coercion", Status.DELIVERED, "transform.map", null, "the schema registry: typed fields, TRY_CAST, structural rejects → quarantine"),
            p("DQ", "quality.constraint.check", "⚠️", "Constraint & range checker (Expectations)", Status.DELIVERED, null, "expectation", "Expectations evaluated per Dataset (`ExpectationEvaluator`); not a mid-chain step"),
            p("DQ", "quality.dedup.exact", "🧼", "Exact-key deduplicator (within a Consignment)", Status.DELIVERED, "transform.dedup", null, "`scope: consignment` (default)"),
            p("DQ", "quality.dedup.windowed", "⏱️", "Sliding time-window deduplicator", Status.DELIVERED, "transform.dedup", null, "D-9: `scope: window(P4D)` + the durable dedup ledger"),
            p("DQ", "quality.dedup.file", "🗂️", "File-grain duplicate guard (path / checksum / metadata / marker)", Status.DELIVERED, "acquisition", null, "Collector `duplicate:` policy + marker dedup — a Guarantee, rides the Collector"),
            p("DQ", "quality.schema.drift", "🧬", "Schema drift & new-field detector", Status.PARTIAL, null, "expectation", "multi-schema dispatch refuses unknown shapes; no drift REPORT yet"),
            p("DQ", "quality.cluster.edit", "🔍", "Cluster & edit value normalizer", Status.PLANNED, null, null, null),
            p("DQ", "quality.match.fuzzy", "🔍", "Fuzzy string (Jaro-Winkler) matcher", Status.PLANNED, null, null, null),
            p("DQ", "quality.cleanse.trim", "🧹", "Whitespace & string sanitizer", Status.PARTIAL, "transform.map", null, "any `EXPR` rule does it today; no dedicated step"),
            p("DQ", "quality.profiler.inline", "🧮", "Inline stream profiler & statistics", Status.PARTIAL, null, "storage_report", "storage/completeness KPIs exist; no per-column profile step"),
            p("DQ", "quality.sample.reservoir", "📊", "Statistical & reservoir sampler", Status.PLANNED, null, null, null),
            p("DQ", "quality.cleanse.transcode", "🔤", "Character map & code page transcoder", Status.PLANNED, null, null, null),
            p("DQ", "quality.pii.mask", "🔒", "PII masking & tokenization", Status.PLANNED, null, null, "board SEC-08 — Enterprise only"),
            p("DQ", "quality.crypto.hash", "🔑", "One-way salted cryptographic hasher", Status.PLANNED, null, null, null),
            p("DQ", "quality.compliance.redact", "🛡️", "GDPR / CCPA field redactor", Status.PLANNED, null, null, "board SEC-08 — Enterprise only"),
            p("XFM", "transform.expression", "🧮", "Expression builder & computed columns", Status.DELIVERED, "transform.map", null, "the `EXPR` / `CONCAT_DT` / `FILENAME_DATE` rules"),
            p("XFM", "transform.cast", "🔄", "Field type cast & renamer matrix", Status.DELIVERED, "transform.map", null, "the mapping rows (`DIRECT` + typed target)"),
            p("XFM", "transform.filter", "🔽", "Row filter (pre-parse regex / post-map predicate)", Status.DELIVERED, "transform.filter", null, null),
            p("XFM", "transform.route", "🔀", "Router — case / clone branches with mid-branch steps", Status.DELIVERED, "transform.route", null, null),
            p("XFM", "transform.summarize", "∑", "Group-by summarizer (measures grammar)", Status.DELIVERED, "transform.summarize", null, null),
            p("XFM", "transform.join", "🤝", "Reference-store join (versioned references)", Status.DELIVERED, "transform.join", null, "at rest only — refused mid-branch (no reference resolver on the ingest lane)"),
            p("XFM", "transform.lookup", "🗺️", "Lookup & static map transcoder", Status.PARTIAL, "transform.join", null, "a reference join covers it; no inline static map"),
            p("XFM", "transform.matrix.pivot", "🔀", "Dynamic pivot / transpose", Status.PLANNED, null, null, null),
            p("XFM", "transform.matrix.unpivot", "🔄", "Unpivot / column flattener", Status.PLANNED, null, null, null),
            p("XFM", "transform.analytics.rank", "🏆", "Rank & Top-N pruner", Status.PLANNED, null, null, null),
            p("XFM", "transform.explode", "💥", "Array / object exploder & flattener", Status.PLANNED, null, null, "the grandfathered `transform.split` node type is the read-only ancestor"),
            p("XFM", "transform.join.merge", "🤝", "Presorted stream merge joiner", Status.PLANNED, null, null, "the grandfathered `transform.merge` node type is the read-only ancestor"),
            p("XFM", "transform.dim.scd2", "🏛️", "Slowly changing dimension (SCD Type 2)", Status.PLANNED, null, null, null),
            p("XFM", "transform.key.surrogate", "🔑", "Monotonic surrogate key generator", Status.PLANNED, null, null, null),
            p("XFM", "transform.dml.strategy", "🏷️", "DML row-action strategy flagger", Status.PLANNED, null, null, null),
            p("XFM", "transform.diff.compare", "⚖️", "Dataset differ & change compare", Status.PARTIAL, null, "recon", "Reconciliation boards compare Datasets; not a chain step"),
            p("XFM", "transform.builder.xml", "🏗️", "Hierarchical XML / JSON document builder", Status.PLANNED, null, null, null),
            p("XFM", "transform.fintech.ifrs", "📊", "IFRS 15 / IFRS 9 revenue recognition engine", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.simbox", "📱", "SIM box & bypass fraud detector", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.rating", "💵", "Tariff, rating & usage billing engine", Status.PLANNED, null, null, null),
            p("XFM", "transform.telecom.roaming", "🌍", "Roaming TAP3 / CIBER surcharger", Status.PLANNED, null, null, null),
            p("XFM", "transform.fintech.velocity", "🚨", "Velocity & impossible-travel anomaly", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.lod", "🏛️", "Level-of-detail (LOD) fixed aggregator", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.lod_context", "🏛️", "LOD include / exclude context aggregator", Status.PLANNED, null, null, null),
            p("BI", "transform.timeseries.resample", "⏱️", "Time-grain resampler & gap imputer", Status.PARTIAL, null, "measure-grammar", "time grains exist in Studio queries (`QuerySpec.grains`); no resampling step"),
            p("BI", "transform.timeseries.shift", "📈", "Period-over-period (YoY / MoM / WoW) shift", Status.PLANNED, null, null, null),
            p("BI", "transform.analytics.running", "📊", "Running calculations & moving averages", Status.PLANNED, null, null, null),
            p("BI", "transform.timeseries.forecast", "🔮", "Time-series forecaster (Holt-Winters)", Status.PLANNED, null, null, null),
            p("BI", "transform.semantic.metric", "📐", "Semantic KPI calculator & Measure formulas", Status.PARTIAL, null, "measure-grammar", "the Measure grammar (`count | agg(field)`) serves Studio + summarize; no named-KPI layer"),
            p("BI", "transform.param.jinja", "🏷️", "Template & runtime parameter injector", Status.PARTIAL, null, "sql.template", "the `sql.template` job resolves `$name` tokens; no Jinja"),
            p("BI", "transform.data.binning", "📦", "Histogram & quantile binner", Status.PLANNED, null, null, null),
            p("BI", "transform.string.smart_split", "✂️", "Smart custom string splitter", Status.PLANNED, null, null, null),
            p("BI", "transform.geo.h3", "🗺️", "H3 hexagonal & geohash grid indexer", Status.PLANNED, null, null, "needs the DuckDB `spatial`/`h3` extension — board CP-09 gate"),
            p("BI", "transform.geo.spatial_join", "📍", "Spatial polygon & point-in-polygon intersect", Status.PLANNED, null, null, "needs the DuckDB `spatial` extension — deliberately not loaded (SqlSandbox)"),
            p("BI", "transform.stats.outlier", "🚨", "Outlier detector & IQR boxplot fencer", Status.PLANNED, null, null, null),
            p("BI", "transform.stats.correlation", "🧮", "Pearson & Spearman correlation matrix", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.reference", "📚", "Reference-table enrichment (`*_enrich.toon`, Stage-2)", Status.DELIVERED, "enrichment", null, "the shipped enrichment job + versioned references"),
            p("ENR", "enrichment.geoip", "🌍", "GeoIP & ISP geolocation enricher", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.redis", "⚡", "Redis / in-memory cache lookup", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.rest", "🌐", "Dynamic microservice REST enricher", Status.PLANNED, null, null, null),
            p("ENR", "transform.db.procedure", "🗄️", "Parameterized stored-procedure caller", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.entity.link", "🔗", "Master entity resolution & record linkage", Status.PLANNED, null, null, null),
            p("ENR", "enrichment.graph.cluster", "🕸️", "Graph cluster & connected-component tagger", Status.PARTIAL, null, "link-analysis", "link-analysis VIEW ships (projection); no tagging step"),
            p("ENR", "ml.inference.onnx", "🤖", "ONNX Runtime embedded inference", Status.PLANNED, null, null, "the optional `inspecto-intelligence` module carries onnxruntime; never bundled"),
            p("ENR", "ml.llm.classify", "🏷️", "LLM zero-shot classifier & tagging", Status.PLANNED, null, null, "assist/intelligence agents are never bundled (CP-14)"),
            p("ENR", "ml.embedding.vector", "📐", "Text embeddings & vector generator", Status.PLANNED, null, null, null),
            p("CTL", "control.file.sequence_analyzer", "🕳️", "File sequence & gap integrity analyzer", Status.DELIVERED, "gap", null, "Collector `gap_detection: {sequence}` → the gap node + SEQUENCE_GAP events"),
            p("CTL", "control.gap.detector", "🕳️", "Sequence-gap & data-loss watchdog", Status.DELIVERED, "gap", null, "same detector; gaps raise ALERT objects via the EventObjectBridge"),
            p("CTL", "control.audit.stamp", "🏷️", "Audit metadata & lineage stamper", Status.DELIVERED, "sink.persistent", null, "`filename_column` + the per-file/batch/lineage ledgers + `__batch_id` provenance"),
            p("CTL", "control.throttle", "⏳", "Throttle & rate limiter", Status.DELIVERED, "acquisition", null, "Collector `fetch.rate_limit` + intake caps + the concurrency broker"),
            p("CTL", "control.circuitbreaker", "🚦", "Circuit breaker & fallback switch", Status.DELIVERED, "acquisition", null, "Collector `circuit_breaker` + `retry`"),
            p("CTL", "control.transaction.commit", "🏁", "Transaction & commit controller", Status.PARTIAL, null, "engine", "the BranchCommitCoordinator ledger + bounded COMMIT retry are engine-internal, not authorable"),
            p("CTL", "control.alert.dispatch", "🚨", "Alert rule dispatcher", Status.DELIVERED, null, "alert-rule", "Alert Rules over the ledgers → Alerts → channels; board CP-11/CP-15"),
            p("CTL", "control.sla.monitor", "⏱️", "SLA timeout & heartbeat monitor", Status.PARTIAL, null, "completeness-kpi", "completeness KPI + `heartbeat` maintenance task; no SLA object"),
            p("SNK", "sink.file.parquet", "📁", "Parquet (snappy / zstd / gzip) partitioned store", Status.DELIVERED, "sink.persistent", null, "Hive `year=/month=/day=` partitions"),
            p("SNK", "sink.file.csv", "📄", "CSV partitioned store", Status.DELIVERED, "sink.persistent", null, "`output.format: CSV`"),
            p("SNK", "sink.ducklake", "🦆", "DuckLake catalog (PostgreSQL) sink", Status.DELIVERED, "sink.persistent", null, "`output.ducklake` — needs the postgresql sidecar (Standard+)"),
            p("SNK", "sink.view", "👁️", "Derived view (no bytes, registered SQL)", Status.PARTIAL, "sink.view", null, "grandfathered node type; the Dataset/View surface replaced it"),
            p("SNK", "sink.quarantine", "☣️", "Quarantine error-log store", Status.DELIVERED, null, "sink.quarantine", "structural rejects + `errors/<base>_errors.csv`"),
            p("SNK", "sink.archive", "📜", "Long-term compliance archive", Status.DELIVERED, "acquisition", null, "Collector `post_action: MOVE archive_path` + the `backup` maintenance task"),
            p("SNK", "sink.lake.delta", "🗄️", "Delta Lake persistent table sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.lake.iceberg", "🧊", "Apache Iceberg append / upsert sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.db.clickhouse", "📊", "ClickHouse / StarRocks analytical sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.file.excel", "📑", "Excel multi-tab report sink", Status.PLANNED, null, null, null),
            p("SNK", "sink.stream.kafka", "📤", "Apache Kafka topic producer", Status.PLANNED, null, null, null),
            p("SNK", "sink.stream.aws", "📨", "AWS SQS / SNS event publisher", Status.PLANNED, null, null, null),
            p("SNK", "sink.notify.email", "📧", "Email & report dispatcher", Status.PARTIAL, null, "mail.send", "the `mail.send` JOB + mail channels; not a chain sink"),
            p("SNK", "sink.api.webhook", "🪝", "Outbound webhook dispatcher", Status.PARTIAL, null, "channel", "webhook notification channel exists; not a chain sink"),
            p("SNK", "sink.dlq", "🕳️", "Dead-letter queue", Status.PLANNED, null, null, null));

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
