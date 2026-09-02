# Unified Canvas UI, Docking System & Enterprise Analytics Step Directory

This document is the master architectural specification for the **Inspecto Graph Canvas UI Shell**, integrating a **Left-Fixed Collapsible & Resizable Sidebar (Step Palette)** with **Floating/Draggable Contextual Panels (Inspectors, Minimap, AI Copilot)**, a **Bottom Telemetry Drawer**, and an expanded **87+ Enterprise Processor Directory** that unifies modern streaming/Lakehouse ingestion with conventional ETL (SCD2, DML Strategy, Mainframe), **Tableau / Apache Superset Visual Analytics** (LOD Calculations, Time-Series Resampling, YoY Growth, H3 Hexagon Grid Indexing), and **Mission-Critical Audit & Regulatory Processors (File Sequence Analyzer, IFRS 15/9 Revenue Recognition Engine)**.

---

## 1. Visual Layout Architectures

### A. Master Layout: Left-Fixed Resizable Sidebar + Canvas + Floating Panels

```text
+---------------------------------------------------------------------------------------------------------------------------------------------------------------+
| 🚀 Inspecto Pipeline Studio   [💾 Save] [▶ Run Pipeline] [↩ Undo] [↪ Redo] [⛶ Fit View] [🔍 100%] [🎨 Layout]                             [⚙ Settings] [👤 User] |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------+
| LEFT FIXED RESIZABLE PALETTE  ║                                                 GRAPH CANVAS WORKSPACE                                                        |
| +---------------------------+ ║                                                                                                                               |
| | 📦 Step Palette (87+) [◀] | ║                                                                                               +-------------------------------+ |
| +---------------------------+ ║                                                                                               | ⠿ 🔍 Node Inspector   [—] [×] | |
| | 🔍 Search (e.g. ifrs, seq)| ║                                                                                               +-------------------------------+ |
| | [All] [Src] [DW] [FinTech]| ║                                                                                               | 📊 IFRS Revenue Engine (Sel)  | |
| | ------------------------- | ║                                                                                               | 🏷️ ID: ifrs_rev_001 • 🟢 Valid | |
| | ▼ Control & Governance    | ║               +-------------------+                                                           | ----------------------------- | |
| |   ├── 🕳️ File Seq Analyzer| ║               | 📁 Local File Ingest                                                           | [⚙ Config] [📜 Grammar] [🔌 Sc]| |
| |   ├── 🏁 Txn Controller   | ║               | 🏷️ /var/data/raw  |                                                           | Standard:      [IFRS 15     ▼]| |
| |   └── 🏷️ Audit Lineage    | ║               +---------+---------+                                                           | Contract Key:  [contract_id ▼]| |
| | ▼ FinTech & Domain        | ║                         |                                                                     | SSP Allocation: PRO_RATA_VALUE| |
| |   ├── 📊 IFRS 15/9 Engine | ║                         v (Filename Stream)                                                   | +---------------------------+ | |
| |   ├── 📱 SIM Box Detector | ║               +-------------------+                                                           | | 🔌 Port Divergence Routes | | |
| |   └── 💵 Tariff & Rating  | ║               | 🕳️ File Seq Analyz|                                                           | | • data:      Amortized Rev  | | |
| | ▼ BI & Visual Analytics   | ║               | ⚙️ Gap Window: 10m|                                                           | | • contracts: Balance Ledger | | |
| |   ├── 🏛️ LOD Fixed Aggr   | ║               +----+---------+----+                                                           | | • gaps:      Missing Seq DLQ| | |
| |   ├── ⏱️ Time-Series Resam| ║                    |         | (Missing File Alert)                                          | +---------------------------+ | |
| |   ├── 📈 YoY / MoM Growth | ║     (In-Sequence)  v         v (Route to DLQ)                                                | [🧪 Dry-Run] [📦 Subgraph]   | |
| |   └── 🗺️ H3 Hex Grid Index| ║               +-------------------+      +-------------------+                                +-------------------------------+ |
| | ▼ Sources & Ingestion     | ║               | 📡 ASN.1 Decoder  |      | 🚨 Missing File Q |                                                                  |
| |   ├── 💾 Lakehouse Delta  | ║               | 🏷️ Telecom CDRs   |      | 🏷️ /alert/gap_log |                                +-------------------------------+ |
| |   └── 📑 Multi-Tab Excel  | ║               +---------+---------+      +-------------------+                                | ⠿ 🗺️ Minimap          [—] [×] | |
| | ▼ Extraction & Parsers    | ║                         |                                                                     +-------------------------------+ |
| |   ├── 📡 ASN.1 CDR Decoder| ║                         v (Parsed Records)                                                    |  +-----------------------+    | |
| |   ├── 📠 Mainframe EBCDIC | ║               +-------------------+                                                           |  |   [   ]               |    | |
| |   └── 📄 Delimited Parser | ║               | 📊 IFRS 15 Engine |                                                           |  |    ↓                  |    | |
| | ▼ Quality & Hygiene       | ║               | 🔑 SSP Bundles    |                                                           |  |   [   ]               |    | |
| |   ├── 🔍 Fuzzy Matcher    | ║               +---------+---------+                                                           |  +-----------------------+    | |
| |   └── 🔒 PII Masker       | ║                         |                                                                     +-------------------------------+ |
| | ▼ Transforms & DW         | ║                         v (Recognized Journal Entries)                                                                        |
| |   ├── 🏛️ SCD Type 2 Store | ║               +-------------------+                                                                                           |
| |   ├── 🔀 Pivot / Unpivot  | ║               | 🗄️ Ledger Delta   |                                                                                           |
| |   └── ⚖️ Dataset Differ   | ║               | 🏷️ /fin/ifrs_rev  |                                                                                           |
| | ▼ Sinks & Destinations    | ║               +-------------------+                                                                                           |
| |   ├── 🗄️ Delta Lake Table | ║                                                                                                                               |
| |   └── 📧 Email Alert Sink | ║                                                                                                                               |
| +---------------------------+ ║                                                                                                                               |
| (Width: 240px - 600px)        ║ <--- Splitter / Resize Drag Handle (`cursor: col-resize`)                                                                     |
|                               ║                                                                                                                               |
|   +---------------------------┴---------------------------------------------------------------------------------------------------------------------------+   |
|   | ⠿ 📊 Pipeline Telemetry & Live Execution Drawer                                                                                           [🗖] [—] [×]|   |
|   +-------------------------------------------------------------------------------------------------------------------------------------------------------+   |
|   | [📑 Logs (3)]  [📈 Stream Metrics]  [⚡ Node Latency]  [🧪 Sample Preview]  [🧮 Data Profiler HUD]  [🕳️ Sequence Tracker]  [📊 IFRS Schedules]              |   |
|   | 12:45:10.201 [INFO] [File Seq Analyzer] Channel CDR_MSC_01 verified: 480 files continuous (Seq #10041 to #10520). Zero missing gaps detected.           |   |
|   | 12:45:10.880 [INFO] [ASN.1 Decoder] Decoded 1.2M binary call records in 890ms (Zero bit-corruption).                                                    |   |
|   | 12:45:11.042 [INFO] [IFRS 15 Engine] Multi-element bundle revenue allocated across 48,000 contracts (Contract liabilities updated: $1.42M).                |   |
|   | 12:45:11.319 [SUCCESS] [Ledger Delta] Checkpoint flushed to Delta Lake /fin/ifrs_rev (Journal entries posted: 48,000, Balanced: 100%).                    |   |
+---+-------------------------------------------------------------------------------------------------------------------------------------------------------+---+
```

---

### B. Collapsed Left Rail Mode (48px Slim View)

```text
+---------------------------------------------------------------------------------------------------------------------------------------------------------------+
| 🚀 Inspecto Pipeline Studio   [💾 Save] [▶ Run Pipeline] [↩ Undo] [↪ Redo] [⛶ Fit View] [🔍 100%] [🎨 Layout]                             [⚙ Settings] [👤 User] |
+---------------------------------------------------------------------------------------------------------------------------------------------------------------+
| COLLAPSED RAIL ║                                                GRAPH CANVAS WORKSPACE (MAXIMIZED AREA)                                                       |
| [ ▶ Expand ]   ║                                                                                                                                              |
| ────────────── ║                                                                                                                                              |
| [ 🔍 Search ]  ║                         +-------------------+             +-------------------+             +-------------------+                            |
| [ Sources  ]   ║                         | 📁 Local Ingest   | ----------> | 📄 Pipe Parser    | ----------> | 💾 Parquet Sink   |                            |
| [ Parsers  ]   ║                         +-------------------+             +-------------------+             +-------------------+                            |
| [ Quality  ]   ║                                                                                                                                              |
| [ Logic/DW ]   ║                                                                                                                                              |
| [ FinTech  ]   ║                                                                                                                                              |
| [ Controls ]   ║                                                                                                                                              |
| [ Sinks    ]   ║                                                                                                                                              |
| ────────────── ║                                                                                                                                              |
| [ Settings ]   ║                                                                                                                                              |
| (Width: 48px)  ║ <--- Click [▶] or press `Ctrl+B` to expand back to user's customized width                                                                  |
+----------------+----------------------------------------------------------------------------------------------------------------------------------------------+
```

---

## 2. Master Architecture Tree

```text
Canvas UI, Docking & Floating Window System
├── 1. Global Viewport & Workspace Canvas
│   ├── 1.1 Canvas Viewport Layer (Zoom/Pan Matrix, Adaptive Grid, Reactive Node Graph)
│   ├── 1.2 Top Global Command Bar (Save, Run, Undo/Redo, Zoom %, Layout)
│   └── 1.3 Docking Perimeter & Anchor Rails (Left Dock, Right Inspector Dock, Bottom Telemetry Dock)
│
├── 2. Left-Fixed Panel: Collapsible & Resizable Step Palette
│   ├── 2.1 Header Bar & Controls (Step count badge "87+ Processors", [◀]/[▶] toggle, Ctrl+B)
│   ├── 2.2 Drag-to-Resize Splitter Bar (Right border, minWidth: 240px, default: 320px, maxWidth: 600px)
│   ├── 2.3 Slim Icon Rail Mode (48px compact mode with hover flyout menus)
│   ├── 2.4 Search & Filter Bar (Real-time query, Filter chips [All] [BI] [Src] [DW] [FinTech] [Control])
│   └── 2.5 Drag-and-Drop Node Instantiation (Pointer ghost preview, canvas grid snapping)
│
├── 3. Contextual Node Inspector & Property Sheet (Floating or Docked-Right)
│   ├── 3.1 Window Header & Controls (Drag handle, title, validation badge, dock/float toggles)
│   ├── 3.2 Multi-Tab System ([Config], [Grammar/Jinja/Logic], [Schema & Types], [Test & Sample])
│   └── 3.3 Context Action Bar (Validate, Convert to Subgraph, Duplicate, Delete)
│
├── 4. Floating Minimap Navigator (Scaled radar, draggable viewport camera box)
│
├── 5. Bottom Telemetry & Diagnostics Drawer
│   ├── [Logs & Traces] Real-time execution logs
│   ├── [Stream Throughput] Live rows/sec & backpressure gauges
│   ├── [Step Latency] Profiler breakdown per node
│   ├── [Sample Preview] Live data dry-run table
│   ├── [Data Profiler HUD] Real-time column cardinality & null ratios
│   ├── [Sequence Tracker] File/packet sequence gap timeline & missing file alerts
│   └── [IFRS Schedules] Multi-period contract amortization & revenue ledger preview
│
├── 6. Floating AI Copilot & Assist Drawer (Natural language DAG authoring, schema repair)
│
├── 7. Spatial Coordinates, Stacking & 8-Point Resizing (Z-Index elevation, edge snapping)
│
└── 8. State Persistence, Performance & Accessibility (LocalStorage, GPU translate3d, ARIA)
```

---

## 3. Comprehensive Categorized Step Directory (87+ Processors)

```text
Enterprise Step Directory (8 Core Families, 87+ Builtin Processors)
│
├── 1. Business Intelligence, Visual Analytics & Tableau/Superset Pack
│   ├── 1.1 Multi-Grain & Level of Detail (LOD) Calculations
│   │   ├── 🏛️ Level of Detail (LOD) Fixed Aggregator (`transform.analytics.lod`)
│   │   └── 🏛️ LOD Include / Exclude Context Aggregator (`transform.analytics.lod_context`)
│   ├── 1.2 Time-Series Resampling, Period-over-Period & Forecasting
│   │   ├── ⏱️ Time Grain Resampler & Gap Imputer (`transform.timeseries.resample`)
│   │   ├── 📈 Period-over-Period (YoY / MoM / WoW) Shift (`transform.timeseries.shift`)
│   │   ├── 📊 Running Calculations & Moving Averages (`transform.analytics.running`)
│   │   └── 🔮 Time-Series Forecaster (Holt-Winters) (`transform.timeseries.forecast`)
│   ├── 1.3 Semantic Modeling, Jinja & Discretization
│   │   ├── 📐 Semantic KPI Calculator & Metric Formulas (`transform.semantic.metric`)
│   │   ├── 🏷️ Jinja Template & Runtime Parameter Injector (`transform.param.jinja`)
│   │   ├── 📦 Histogram & Quantile Binner (`transform.data.binning`)
│   │   └── ✂️ Smart Custom String Splitter (`transform.string.smart_split`)
│   ├── 1.4 Geospatial Indexing & Spatial Analytics (Deck.gl / Mapbox Prep)
│   │   ├── 🗺️ Uber H3 Hexagonal & Geohash Grid Indexer (`transform.geo.h3`)
│   │   └── 📍 Spatial Polygon & Point-in-Polygon Intersect (`transform.geo.spatial_join`)
│   └── 1.5 Statistical Analytics & Outlier Fencing
│       ├── 🚨 Outlier Detector & IQR Boxplot Fencer (`transform.stats.outlier`)
│       └── 🧮 Pearson & Spearman Correlation Matrix (`transform.stats.correlation`)
│
├── 2. Sources & Ingestion (Acquisition & Adapters)
│   ├── 2.1 File & Object Storage Connectors
│   │   ├── 📁 Local / NFS Directory Watcher (`acquisition.file.local`)
│   │   ├── 📑 Multi-Sheet Excel Workbook Ingest (`acquisition.file.excel`)
│   │   ├── ☁️ AWS S3 Object Ingest (`acquisition.file.s3`)
│   │   ├── 🌐 Azure Blob & ADLS Gen2 (`acquisition.file.azure`)
│   │   ├── 🪣 Google Cloud Storage (`acquisition.file.gcs`)
│   │   └── 🔒 SFTP / FTPS Remote Ingest (`acquisition.file.sftp`)
│   ├── 2.2 Message Queues & Streaming Brokers
│   │   ├── 📤 Apache Kafka Consumer (`acquisition.stream.kafka`)
│   │   ├── 📨 Apache Pulsar Consumer (`acquisition.stream.pulsar`)
│   │   ├── 📬 AWS Kinesis / SQS (`acquisition.stream.kinesis`)
│   │   ├── 🐰 RabbitMQ AMQP Subscriber (`acquisition.stream.rabbitmq`)
│   │   └── 📡 MQTT IoT Telemetry Ingest (`acquisition.stream.mqtt`)
│   ├── 2.3 Database & Lakehouse Readers
│   │   ├── 🔄 Change Data Capture / CDC Debezium (`acquisition.cdc.debezium`)
│   │   ├── 💾 Lakehouse Delta Lake Reader (`acquisition.lake.delta`)
│   │   ├── 🧊 Apache Iceberg Table Ingest (`acquisition.lake.iceberg`)
│   │   ├── 🗄️ JDBC / SQL Query Batch Reader (`acquisition.db.jdbc`)
│   │   └── ❄️ Snowflake / BigQuery Ingest (`acquisition.db.cloud`)
│   └── 2.4 Network, APIs & Push Endpoints
│       ├── 🌐 REST API Poller & Paged Ingest (`acquisition.api.rest`)
│       ├── 🪝 HTTP Webhook Listener Endpoint (`acquisition.api.webhook`)
│       └── 🔌 gRPC Stream Receiver (`acquisition.api.grpc`)
│
├── 3. Extraction & Format Parsers (Parse Family)
│   ├── 3.1 Structured Text Parsers
│   │   ├── 📄 Delimited / CSV / TSV / PSV Parser (`parser.delimited`)
│   │   ├── 📦 Fixed-Width Column Slicer (`parser.fixedwidth`)
│   │   └── 🏷️ Key-Value / Logfmt Parser (`parser.keyvalue`)
│   ├── 3.2 Semi-Structured & Document Parsers
│   │   ├── 🧬 JSON Object & JSON Lines (NDJSON) Parser (`parser.json`)
│   │   ├── 📑 XML / XPath / DOM Unpacker (`parser.xml`)
│   │   └── 📜 YAML Document Slicer (`parser.yaml`)
│   ├── 3.3 Binary, Telephony & Legacy Mainframe Parsers
│   │   ├── 📡 ASN.1 Binary Telecom CDR Decoder (`parser.asn1.ber`)
│   │   ├── 📜 ASN.1 PER / XER / DER Decoder (`parser.asn1.per`)
│   │   ├── 📠 Mainframe EBCDIC & COBOL Copybook (`parser.mainframe.ebcdic`)
│   │   ├── ⚡ Protocol Buffers / Protobuf (`parser.binary.protobuf`)
│   │   ├── 🦅 Apache Avro Binary Decoder (`parser.binary.avro`)
│   │   └── 🌐 PCAP Network Packet Stream Slicer (`parser.binary.pcap`)
│   └── 3.4 Pattern & Log Extractor Engines
│       ├── 📜 Grok / Logstash Expression Matcher (`parser.pattern.grok`)
│       ├── 🔎 Named-Group Regex Extractor (`parser.pattern.regex`)
│       └── 🖥️ Syslog RFC 5424 / RFC 3164 Parser (`parser.pattern.syslog`)
│
├── 4. Data Quality, Validation, Cleansing & Fuzzy Matching
│   ├── 4.1 Schema & Type Enforcement
│   │   ├── 🛡️ Schema Validator & Type Coercion (`quality.schema.validator`)
│   │   ├── ⚠️ Constraint & Range Checker (`quality.constraint.check`)
│   │   └── 🧬 Schema Drift & New Field Detector (`quality.schema.drift`)
│   ├── 4.2 Fuzzy Matching & Smart Value Normalization
│   │   ├── 🔍 Cluster & Edit Value Normalizer (`quality.cluster.edit`)
│   │   ├── 🔍 Fuzzy String & Jaro-Winkler Matcher (`quality.match.fuzzy`)
│   │   ├── 🧼 Exact Hash Deduplicator (`quality.dedup.exact`)
│   │   ├── ⏱️ Sliding Time-Window Deduplicator (`quality.dedup.windowed`)
│   │   └── 🧹 Whitespace & String Sanitizer (`quality.cleanse.trim`)
│   ├── 4.3 Profiling, Sampling & Code Page Transformation
│   │   ├── 🧮 Inline Stream Profiler & Statistics (`quality.profiler.inline`)
│   │   ├── 📊 Statistical & Reservoir Sampler (`quality.sample.reservoir`)
│   │   └── 🔤 Character Map & Code Page Transcoder (`quality.cleanse.transcode`)
│   └── 4.4 Security, Governance & Compliance
│       ├── 🔒 PII Masking & Tokenization (`quality.pii.mask`)
│       ├── 🔑 One-Way Salted Cryptographic Hasher (`quality.crypto.hash`)
│       └── 🛡️ GDPR / CCPA Field Redactor (`quality.compliance.redact`)
│
├── 5. Transformers, Dimensional Modeling, Analytics & FinTech
│   ├── 5.1 FinTech, Regulatory Accounting & Telecom Analytics
│   │   ├── 📊 IFRS 15 / IFRS 9 Revenue Recognition Engine (`transform.fintech.ifrs`)
│   │   ├── 📱 SIM Box & Bypass Fraud Detector (`transform.telecom.simbox`)
│   │   ├── 💵 Tariff, Rating & Usage Billing Engine (`transform.telecom.rating`)
│   │   ├── 🌍 Roaming TAP3 / CIBER Surcharger (`transform.telecom.roaming`)
│   │   └── 🚨 Velocity & Impossible Travel Anomaly (`transform.fintech.velocity`)
│   ├── 5.2 Dimensional Modeling & Data Warehouse Lifecycle
│   │   ├── 🏛️ Slowly Changing Dimension SCD Type 2 (`transform.dim.scd2`)
│   │   ├── 🔑 Monotonic Surrogate Key Generator (`transform.key.surrogate`)
│   │   ├── 🏷️ DML Row Action Strategy Flagger (`transform.dml.strategy`)
│   │   └── ⚖️ Dataset Differ & Change Compare (`transform.diff.compare`)
│   ├── 5.3 Structural Reshaping & Matrix Transforms
│   │   ├── 🔀 Dynamic Pivot / Transpose (`transform.matrix.pivot`)
│   │   ├── 🔄 Unpivot / Column Flattener (`transform.matrix.unpivot`)
│   │   ├── 🏆 Rank & Top-N Pruner (`transform.analytics.rank`)
│   │   ├── 💥 Array / Object Exploder & Flattener (`transform.explode`)
│   │   └── 🤝 Presorted Stream Merge Joiner (`transform.join.merge`)
│   └── 5.4 Projections, Math & Expressions
│       ├── 🧮 Expression Builder & Computed Columns (`transform.expression`)
│       ├── 🔄 Field Type Cast & Renamer Matrix (`transform.cast`)
│       ├── 🗺️ Lookup & Static Map Transcoder (`transform.lookup`)
│       └── 🏗️ Hierarchical XML/JSON Document Builder (`transform.builder.xml`)
│
├── 6. Enrichment, Entity Resolution & AI/ML
│   ├── 6.1 External Knowledge & Cache Joiners
│   │   ├── 🌍 MaxMind GeoIP & ISP Geolocation Enricher (`enrichment.geoip`)
│   │   ├── ⚡ Redis / In-Memory Cache Lookup (`enrichment.redis`)
│   │   └── 🌐 Dynamic Microservice REST Enricher (`enrichment.rest`)
│   │   └── 🗄️ Parameterized Stored Procedure Caller (`transform.db.procedure`)
│   ├── 6.2 Identity & Graph Resolution
│   │   ├── 🔗 Master Entity Resolution & Record Linkage (`enrichment.entity.link`)
│   │   └── 🕸️ Graph Cluster & Connected Component Tagger (`enrichment.graph.cluster`)
│   └── 6.3 Machine Learning & Inline AI
│       ├── 🤖 ONNX Runtime Embedded Inference (`ml.inference.onnx`)
│       ├── 🏷️ LLM Zero-Shot Classifier & Tagging (`ml.llm.classify`)
│       └── 📐 Text Embeddings & Vector Generator (`ml.embedding.vector`)
│
├── 7. Control, Governance, Sequence Analysis & Auditing
│   ├── 7.1 Sequence Integrity & Flow Control
│   │   ├── 🕳️ File Sequence & Gap Integrity Analyzer (`control.file.sequence_analyzer`)
│   │   ├── 🏁 Dynamic Transaction & Commit Controller (`control.transaction.commit`)
│   │   ├── 🏷️ Audit Metadata Lineage Stamper (`control.audit.stamp`)
│   │   ├── ⏳ Throttle & Rate Limiter (`control.throttle`)
│   │   └── 🚦 Circuit Breaker & Fallback Switch (`control.circuitbreaker`)
│   └── 7.2 Monitoring & Alerting
│       ├── 🚨 Real-Time Security Alert Dispatcher (`control.alert.dispatch`)
│       ├── 🕳️ Sequence Gap & Data Loss Watchdog (`control.gap.detector`)
│       └── ⏱️ SLA Timeout & Heartbeat Monitor (`control.sla.monitor`)
│
└── 8. Sinks, Storage & Destinations (Sink Family)
    ├── 8.1 Lakehouse & Analytics Storage
    │   ├── 🗄️ Delta Lake Persistent Table Sink (`sink.lake.delta`)
    │   ├── 🧊 Apache Iceberg Append/Upsert Sink (`sink.lake.iceberg`)
    │   ├── 📁 Parquet Snappy Compressed Store (`sink.file.parquet`)
    │   ├── 📊 ClickHouse / StarRocks Analytical Sink (`sink.db.clickhouse`)
    │   └── 📑 Excel Multi-Tab Report Sink (`sink.file.excel`)
    ├── 8.2 Streaming Queues & Notifications
    │   ├── 📤 Apache Kafka Topic Producer (`sink.stream.kafka`)
    │   ├── 📨 AWS SQS / SNS Event Publisher (`sink.stream.aws`)
    │   ├── 📧 Email & Report Dispatcher (`sink.notify.email`)
    │   └── 🪝 Outbound Webhook Dispatcher (`sink.api.webhook`)
    └── 8.3 Dead Letter, Quarantine & Audit
        ├── 🕳️ Dead Letter Queue / DLQ (`sink.dlq`)
        ├── ☣️ Quarantine Error Log Store (`sink.quarantine`)
        └── 📜 Long-Term Compliance Archive (`sink.archive`)
```

---

## 4. Deep-Dive Specification: Core Enterprise & Analytics Processors

### 1. 🕳️ File Sequence & Gap Integrity Analyzer (`control.file.sequence_analyzer`)
* **Purpose**: Tracks continuous sequence numbers per channel, detects missing sequence gaps, flags duplicate deliveries, and validates trailer row count/checksum integrity.
* **Inbound Ports**: `files` (Inbound file metadata & header stream).
* **Outbound Ports**: 
  * `data` (Clean, verified in-sequence file consignments).
  * `gaps` (Missing sequence alert packets dispatched to monitoring).
  * `duplicates` (Duplicate file numbers routed to quarantine).
  * `corrupt` (Trailer record count or checksum mismatch).
* **Config Keys**:
  ```yaml
  channel_key: "channel_id"                  # e.g., MSC_ID, Switch_ID, Gateway_ID
  sequence_regex: ".*_([0-9]{6,10})\\..*"    # Regex to extract numeric sequence from filename
  sequence_mode: "STRICT_MONOTONIC"          # Options: STRICT_MONOTONIC, GAP_ALLOWED_WITH_WARN, WRAP_AROUND
  max_sequence_number: 99999999              # Rollover/wrap ceiling
  reorder_buffer_timeout_seconds: 600        # Wait up to 10m for out-of-order late arriving files
  verify_trailer_record_count: true          # Validates trailer count against parsed row count
  checksum_algorithm: "SHA256"               # Options: MD5, SHA256, CRC32, NONE
  ```

---

### 2. 📊 IFRS 15 / IFRS 9 Revenue Recognition Engine (`transform.fintech.ifrs`)
* **Purpose**: Automates statutory regulatory accounting and revenue recognition for multi-element bundles and credit loss provisioning.
* **Inbound Ports**: `contracts` (Contract activation, upgrades, or termination event stream).
* **Outbound Ports**: 
  * `data` (Period revenue recognition journal entries for General Ledger).
  * `contracts` (Updated contract asset & liability balance sheet ledger).
  * `schedules` (Future multi-period monthly amortization schedules).
  * `exceptions` (Contracts with invalid SSP or unallocated discounts).
* **Config Keys**:
  ```yaml
  ifrs_standard: "IFRS_15"                   # Options: IFRS_15, IFRS_9, IFRS_16
  contract_id_col: "subscription_id"
  contract_start_col: "start_date"
  contract_duration_months_col: "term_months"
  total_transaction_price_col: "bundle_price_charged"
  
  performance_obligations:
    - name: "hardware_handset"
      ssp_value_col: "handset_ssp"
      timing: "POINT_IN_TIME"
    - name: "monthly_airtime_service"
      ssp_value_col: "plan_ssp_total"
      timing: "OVER_TIME"
      amortization_method: "STRAIGHT_LINE"
    - name: "bundled_vas_streaming"
      ssp_value_col: "vas_ssp_total"
      timing: "OVER_TIME"
      amortization_method: "STRAIGHT_LINE"
      
  currency: "USD"
  journal_account_mapping:
    deferred_revenue_account: "2010_UNEARNED_REV"
    contract_asset_account: "1050_CONTRACT_ASSET"
    recognized_revenue_account: "4010_OPERATING_REV"
  ```

---

### 3. 🏛️ Level of Detail (LOD) Fixed Aggregator (`transform.analytics.lod`)
* **Purpose**: Computes multi-grain aggregates (e.g., *Customer Lifetime Total Spend*) and joins it back to detail rows without changing row grain.
* **Inbound Ports**: `data` (Detail transaction stream).
* **Outbound Ports**: `data` (Detail rows with appended LOD metric columns).
* **Config Keys**:
  ```yaml
  lod_type: "FIXED"                  # Options: FIXED, INCLUDE, EXCLUDE
  fixed_dimensions: ["customer_id"]  # Grain to compute calculation at
  aggregations:
    - name: "cust_lifetime_spend"
      expression: "SUM(transaction_amount)"
    - name: "cust_order_count"
      expression: "COUNT(order_id)"
  ```

---

### 4. ⏱️ Time-Series Resampler & Gap Imputer (`transform.timeseries.resample`)
* **Purpose**: Resamples irregular, bursty timestamps into clean discrete time intervals and imputes missing gaps.
* **Inbound Ports**: `data` (Continuous event stream).
* **Outbound Ports**: `data` (Evenly bucketed time-series stream).
* **Config Keys**:
  ```yaml
  timestamp_column: "event_time"
  time_grain: "1_HOUR"               # Options: 1_MINUTE, 5_MINUTE, 1_HOUR, 1_DAY, 1_WEEK, 1_MONTH
  group_by: ["sensor_id", "region"]
  imputation_strategy: "FORWARD_FILL"# Options: FORWARD_FILL, BACKWARD_FILL, LINEAR_INTERPOLATE, ZERO_FILL
  aggregations:
    temperature: "AVG"
    pressure: "MAX"
    event_count: "SUM"
  ```

---

### 5. 🗺️ Uber H3 Hexagonal Grid Indexer (`transform.geo.h3`)
* **Purpose**: Converts GPS latitude/longitude coordinates into Uber H3 spatial index tokens for instant Deck.gl / Mapbox heatmaps.
* **Inbound Ports**: `data`
* **Outbound Ports**: `data`
* **Config Keys**:
  ```yaml
  latitude_column: "pickup_lat"
  longitude_column: "pickup_lon"
  h3_resolution: 8                   # Res 8 ~0.7 km²
  output_h3_column: "h3_hex_index"
  generate_hex_center: true
  ```

---

### 6. 🏛️ Slowly Changing Dimension SCD2 (`transform.dim.scd2`)
* **Purpose**: Tracks historical changes to dimension entities by closing prior records (`valid_to = now, is_current = false`) and inserting new active rows.
* **Inbound Ports**: `data`
* **Outbound Ports**: `data` (Active records), `expired` (Closed historical records).
* **Config Keys**:
  ```yaml
  business_keys: ["customer_id"]
  tracked_attributes: ["street_address", "plan_tier", "phone_number"]
  effective_date_col: "event_timestamp"
  surrogate_key_col: "customer_sk"
  target_dimension_table: "dim_customer"
  ```

---

## 5. TypeScript Contracts & State Models

```typescript
// ==========================================
// 1. Step Catalog Item Specification
// ==========================================
export type StepFamily = 
  | 'BI_ANALYTICS' 
  | 'FINTECH_REGULATORY'
  | 'ACQUISITION' 
  | 'PARSE' 
  | 'QUALITY' 
  | 'TRANSFORM' 
  | 'DIMENSIONAL_DW' 
  | 'ENRICHMENT' 
  | 'ML' 
  | 'CONTROL' 
  | 'SINK';

export type IfrsStandard = 'IFRS_15' | 'IFRS_9' | 'IFRS_16' | 'IFRS_17';
export type SequenceVerificationMode = 'STRICT_MONOTONIC' | 'GAP_ALLOWED_WITH_WARN' | 'WRAP_AROUND';

export interface PortSpec {
  id: string;
  name: string;
  type: 
    | 'data' 
    | 'contracts'
    | 'schedules'
    | 'gaps'
    | 'duplicates'
    | 'corrupt'
    | 'expired' 
    | 'added' 
    | 'modified' 
    | 'deleted' 
    | 'outliers' 
    | 'matched' 
    | 'unmatched' 
    | 'telemetry' 
    | `route:${string}`;
  direction: 'input' | 'output';
  description: string;
  isOptional?: boolean;
}

export interface StepCatalogItem {
  typeId: string;           // e.g. 'control.file.sequence_analyzer' | 'transform.fintech.ifrs'
  displayName: string;      // e.g. 'File Sequence Analyzer' | 'IFRS Revenue Engine'
  family: StepFamily;
  category: string;
  icon: string;
  badge?: 'Tableau/BI' | 'FinTech' | 'Enterprise' | 'Streaming' | 'DW' | 'AI';
  description: string;
  tags: string[];
  authorable: boolean;
  lowerable: boolean;
  inputs: PortSpec[];
  outputs: PortSpec[];
  defaultConfig: Record<string, any>;
}

// ==========================================
// 2. Global Layout & Sidebar State
// ==========================================
export interface LeftSidebarState {
  isCollapsed: boolean;
  width: number;            // Active width in px (240px to 600px, default 320px)
  minWidth: number;
  maxWidth: number;
  defaultWidth: number;
  collapsedWidth: number;   // 48px
  isResizing: boolean;
  activeCategory: string | null;
  selectedFamilyFilter: StepFamily | 'ALL';
  searchQuery: string;
}

export interface WorkspaceLayoutConfig {
  sidebar: LeftSidebarState;
  panels: Record<string, any>;
  bottomDrawerHeight: number;
  bottomDrawerOpen: boolean;
  activeBottomTab: 'logs' | 'metrics' | 'latency' | 'sample' | 'profiler' | 'spatial' | 'sequences' | 'ifrs' | null;
}
```

---

## 6. Architectural & Rendering Guidelines

1. **Left Sidebar Docking & Splitter Engine**:
   - Layout container uses `display: flex; flex-direction: row; height: 100%; width: 100%;`.
   - The graph canvas sits in a fluid container (`flex: 1 1 auto; overflow: hidden;`) that automatically recalculates viewport bounds on sidebar resize without canvas deformation.
   - During splitter drag (`isResizing === true`), attach mouse listeners directly to `window` and set `pointer-events: none` on the graph canvas to maintain 60 FPS performance.

2. **Floating Panels & Stacking Context**:
   - Floating panels (Inspector, Minimap, AI Copilot) use GPU-accelerated transforms: `transform: translate3d(x, y, 0); will-change: transform;`.
   - Clicking anywhere inside a floating panel increments and assigns the highest active `zIndex` to promote that panel above sibling panels.

3. **Windowing & Docking Transitions**:
   - Floating panels support seamless docking to the right rail or bottom drawer without losing user input state.
   - Geometry memory preserves the floating `(x, y, width, height)` coordinates whenever a panel is temporarily docked, minimized, or maximized.
