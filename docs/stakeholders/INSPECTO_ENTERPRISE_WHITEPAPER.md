# Inspecto — Enterprise Product & Solutions Whitepaper
> **Sovereign Data Operations: The High-Throughput, Low-Footprint Platform for Regulated Environments**  
> *Audience: Chief Information Officers, Chief Technology Officers, Enterprise Data Architects, and Compliance Officers*  
> *Publication Date: September 2026 • Document Version: 1.1 (Updated with Embedded AI & Governed Autonomy)*

---

# Page 1: Executive Summary & The Problem Statement
## The Crisis of the Heavyweight Data Stack

### Executive Overview
**Inspecto** is a lean, configuration-driven **data acquisition, management, reconciliation, business intelligence (BI), embedded AI, and forensic investigation platform** delivered as a single ~90 MB self-contained artifact. It runs on a commodity laptop, an air-gapped bare-metal server, or a standard container—with **zero external runtime dependencies** in its standard deployment.

By embedding a vectorized columnar database engine (DuckDB) directly over an open Parquet lakehouse, Inspecto collapses four traditionally fragmented enterprise tool categories—ETL/ingest pipelines, data lakehouse storage, self-service business intelligence, and operational incident workflows—into a single, unified operational fabric. 

Designed specifically for **regulated, sovereign, air-gapped, and resource-constrained environments** where cloud-SaaS tools and cloud LLMs are legally, architecturally, or contractually disqualified, Inspecto enables enterprises to ingest complex file formats, detect reconciliation breaks, maintain an immutable audit trail, and investigate anomalies without deploying a multi-node cluster.

```
                      THE DISTRIBUTED CLUSTER TAX
 ┌─────────────────────────────────────────────────────────────────────────┐
 │ Ingest (NiFi/Airbyte) ──► Storage (Hadoop/S3) ──► Quality (Great Expect)│
 │           │                        │                         │          │
 │     [Glue Script]            [Glue Script]             [Glue Script]    │
 │           ▼                        ▼                         ▼          │
 │ BI (Superset/Tableau) ──► Alerting (PagerDuty) ──► Ticketing (ServiceNow│
 └─────────────────────────────────────────────────────────────────────────┘
   • 5 to 15 Virtual Machines           • High Serialization / Network Shuffle
   • Thousands of Dependencies (CVEs)   • Stale Dashboards & Broken Lineage
   • Unsafe Cloud LLM Egress            • Fragile Glue Code Between Silos
                                     VS
 ┌─────────────────────────────────────────────────────────────────────────┐
 │                      INSPECTO UNIFIED ARTIFACT                          │
 │  Acquire ──► Vectorized Lakehouse ──► Reconcile ──► Breaks ──► AI Agent   │
 └─────────────────────────────────────────────────────────────────────────┘
   • One ~90 MB Executable              • 523,000+ Rows/Sec Single-Node
   • Zero External Runtime Services     • 100% Air-Gapped / Zero Cloud Egress
   • Embedded Sovereign Intelligence    • Governed Autonomy with Human Approval
```

### The Three Structural Failures Inspecto Solves
1. **The "Glue Code" Liability:** Modern enterprise data stacks connect ingestion tools (NiFi, Airbyte) to storage (Snowflake, MinIO), data quality engines (Great Expectations, Collibra), and ticketing systems (Jira, ServiceNow) using bespoke Python scripts and external webhooks. When an ingest job fails or an upstream partner changes a schema, the seam breaks silently. Inspecto eliminates glue code by unifying the end-to-end lifecycle under declarative configuration.
2. **The Cloud & SaaS Egress Exclusion:** Leading observability, analytics, and AI platforms (Snowflake, Datadog, OpenAI, Monte Carlo) are cloud-first SaaS architectures. Defense agencies, sovereign governments, central banks, and telecommunications operators operating under strict data-residency laws or isolated air-gaps cannot legally use them. Inspecto operates 100% offline with zero outbound network calls.
3. **The Distributed Overhead Tax:** Running distributed compute clusters (Spark, Kafka, Flink) to process feeds under two billion records per day wastes 70–80% of CPU cycles on network serialization, JVM garbage collection pauses, and multi-node coordination. Inspecto’s embedded vectorized architecture processes up to **1 billion 100-column rows per day on a single 8-core node**.

---

# Page 2: System Architecture
## Collapsing the Enterprise Stack Across the Seams

```
  ┌───────────────────────────────────────────────────────────────────────┐
  │                         OPERATOR CONSOLE                              │
  │     Business Lens       │     Builder Lens      │     Ops Lens        │
  └───────────┬─────────────────────────┬─────────────────────┬───────────┘
              │                         │                     │
  ┌───────────▼─────────────────────────▼─────────────────────▼───────────┐
  │                    CONTROL PLANE & REST API (/api/v1)                 │
  │   OIDC / OAuth2 SSO   │   RBAC / ABAC Policies   │  Single-Seam Audit │
  └───────────┬───────────────────────────────────────────────┬───────────┘
              │                                               │
  ┌───────────▼───────────────────────────────────────────────▼───────────┐
  │                           DATA PLANE                                  │
  │ ┌────────────────────────┐                   ┌──────────────────────┐ │
  │ │ ACQUISITION & PARSING  │                   │ RECONCILIATION & OPS │ │
  │ │ • SFTP / S3 / DB / GCS │                   │ • Dataset vs Dataset │ │
  │ │ • ASN.1 (CDRs)         │                   │ • Automated Breaks   │ │
  │ │ • Fixed-Width / CSV    │                   │ • Incidents & Cases  │ │
  │ └───────────┬────────────┘                   └──────────▲───────────┘ │
  │             │                                           │             │
  │ ┌───────────▼───────────────────────────────────────────┴───────────┐ │
  │ │             VECTORIZED PARQUET LAKEHOUSE (DuckDB Engine)          │ │
  │ │  • In-Memory Columnar Transforms    • Hive Partitioning           │ │
  │ │  • SQL Sandbox & Query Library      • Studio BI & Visualizations  │ │
  │ └───────────────────────────────────┬───────────────────────────────┘ │
  └─────────────────────────────────────┼─────────────────────────────────┘
                                        ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │         EMBEDDED INTELLIGENCE LAYER (Local / Air-Gapped)              │
  │  Reflex Skills (7)  │  Governed Autonomy (L0-L3)  │  Approvals Inbox  │
  └───────────────────────────────────────────────────────────────────────┘
```

### The Unified Architectural Layers
1. **Acquisition & Parsing:** Automated polling, watermark tracking, deduplication, and decompression for raw network, file, and database sources.
2. **The Vectorized Lakehouse:** Columnar in-memory execution paired with Hive-partitioned Parquet storage, providing fast analytical SQL execution directly on local disk.
3. **Reconciliation & Quality:** Rule-based and schema-level validation comparing disparate feeds to catch financial or operational discrepancies at the source.
4. **Operations & Investigation:** An integrated incident management system with root-cause analysis, forensic Link Analysis, and offline Geo Mapping.
5. **Embedded Sovereign Intelligence:** An in-process, air-gapped AI agent with a governed autonomy ladder, providing failure diagnostics, automated root-cause analysis, and human-gated remediation.

### The Power of "The Seam"
Specialist tools compete within isolated silos (e.g., NiFi for ingestion, Superset for BI). Inspecto wins along the **seams between the silos**:
* When an SFTP collector encounters a transmission gap, it doesn't just log an error—it creates a tracked **Signal**, links it to downstream **Datasets**, highlights affected **Dashboard Tiles** as stale, and opens an **Incident** with a traceable `causationId`.
* Lineage, audit trails, and execution context are preserved end-to-end without custom API integrations.

---

# Page 3: Deep Dive — Acquisition & The Vectorized Lakehouse
## High-Speed File Ingestion & Analytical Storage

```
  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐
  │  Source Feeds   │      │ Native Parser   │      │ PartitionWriter │
  │  • Raw CDRs     ├─────►│  • C++ Zero-Copy├─────►│  • Stage & Reveal│
  │  • Delimited    │      │  • Vector Cast  │      │  • Hive Layout  │
  │  • Binary/Fixed │      │  • Quarantine   │      │  • Manifest Commit
  └─────────────────┘      └─────────────────┘      └────────┬────────┘
                                                             ▼
                                                    ┌─────────────────┐
                                                    │ Parquet Lakehouse│
                                                    │  (Local Disk)   │
                                                    └─────────────────┘
```

### 1. File-Native Acquisition of Complex Formats
Inspecto specializes in proprietary, binary, and complex formats that traditional ETL engines struggle to parse without heavy third-party plugins:
* **Telecom-Grade ASN.1 Decoding:** Features a built-in 154-file ASN.1 decoder subsystem with vendor corpora, enabling out-of-the-box processing of raw Call Detail Records (CDRs) from major switch manufacturers.
* **Fixed-Width & Binary Parsing:** High-performance binary and text layout parsers with memory-mapped buffers.
* **Resilient Protocol Support:** Connectors for SFTP, FTPS, FTP, S3, Google Cloud Storage, and JDBC databases with automatic connection pooling and retry backoff.
* **Quarantine & Error Isolation:** Rows with schema mismatches, corrupt headers, or unreadable encodings are segregated into dedicated error ledgers (`errors/<file>_errors.csv`) and quarantined. Healthy rows proceed without interruption.

### 2. The Vectorized Lakehouse Architecture
Inspecto discards the traditional approach of loading analytical data into a heavyweight, constantly running external database cluster:
* **Embedded DuckDB Engine:** Executes vectorized, SIMD-accelerated SQL queries directly against columnar Parquet files.
* **Zero-Copy Memory Mapping:** Reads Parquet pages directly into memory, bypassing JVM object overhead and garbage collection pauses.
* **Partitioned Hive Layout:** Automatically arranges landed data into date-, region-, or key-partitioned Parquet files (`year=YYYY/month=MM/day=DD/`), ensuring efficient partition pruning for historical analysis.
* **Staged & Revealed Atomic Writes:** The `PartitionWriter` writes batches to hidden staging areas and reveals them via atomic filesystem renames, preventing dirty reads by concurrent BI dashboards.

---

# Page 4: Deep Dive — Reconciliation to Breaks to Incidents
## The Automated Revenue & Operational Assurance Seam

```
  Source Feed A ──┐
                  ├──► [Reconciliation Engine] ──► [Discrepancy?]
  Source Feed B ──┘            ▲                          │
                               │                          ▼ (YES)
                       [Match Rules / Keys]       ┌──────────────────┐
                                                  │ Automated Break  │
                                                  └────────┬─────────┘
                                                           ▼
                                                  ┌──────────────────┐
                                                  │  Tracked Incident│
                                                  │  • Root Cause ID │
                                                  │  • SLA Deadline  │
                                                  │  • Assignee / LOB│
                                                  └──────────────────┘
```

### 1. Dataset-vs-Dataset Reconciliation
In banking, insurance, and telecommunications, identifying discrepancies between upstream generation feeds and downstream billing systems is a core requirement:
* **Declarative Matching Rules:** Define multi-key matching rules, tolerance thresholds (percentage or absolute value), and one-to-many or many-to-many reconciliation logic.
* **Full-Volume Scans:** Leverages vectorised SQL execution to perform cross-dataset joins and delta calculations over millions of records in seconds.

### 2. The Break Lifecycle
Unlike BI tools that merely display reconciliation variances on a chart, Inspecto manages discrepancies as distinct, stateful entities called **Breaks**:
* **Automated Break Creation:** Discrepancies generate persistent Break records capturing the exact source records, discrepancy magnitude, and detection timestamp.
* **Auto-Close Reconciliation:** If an upstream timing difference resolves itself in the next batch cycle, Inspecto can automatically reconcile and close the corresponding Break, reducing manual overhead.
* **Break Aging & Aging Buckets:** Tracks open breaks by age (0–30 days, 30–60 days, 90+ days) to meet financial audit and regulatory compliance standards.

### 3. Integrated Incident Management & RCA
* **Break-to-Incident Promotion:** Critical breaks or groups of related variances can be promoted directly into formal **Incidents**.
* **Threaded Causation:** Every incident maintains a `causationId` linking back through the reconciliation run to the exact ingestion batch and raw source file.
* **Root Cause Analysis (RCA):** Operators annotate Incidents with root-cause classifications, corrective action plans, and post-mortem notes directly within the console.

---

# Page 5: Deep Dive — Forensic Investigation
## Graph Link Analysis & Offline Geo Mapping

```
  ┌─────────────────────────────────┐   ┌─────────────────────────────────┐
  │       LINK ANALYSIS STUDIO      │   │        GEO MAP ANALYSIS         │
  │ • Entity / Link Graph Projection│   │ • Bundled Offline Natural Earth │
  │ • Louvain Community Detection   │   │ • Route & Playback Trajectories │
  │ • PageRank & Centrality Scoring │   │ • Heatmaps & Stay-Point Radius  │
  │ • Pattern Motif Detection       │   │ • Co-Location Anomaly Detection │
  └─────────────────────────────────┘   └─────────────────────────────────┘
```

### 1. Link Analysis Studio
Inspecto includes a specialized Link Analysis environment designed to uncover fraud rings, money laundering networks, and coordinated irregular behavior without external graph databases like Neo4j:
* **Server-Side Entity Projection:** The `InvRoutes` backend dynamically projects relational datasets into graph structures (nodes and edges) using DuckDB aggregations.
* **Graph Algorithms:** 
  * **Centrality & Influence:** Calculates PageRank, betweenness, and eigenvector centrality to find key network actors.
  * **Community Detection:** Automatically groups clusters using Louvain and label propagation algorithms.
  * **Suspicion Scoring:** Applies composite scoring algorithms (0–100) to flag high-risk nodes based on connectivity density and transaction frequency.
* **Pre-Built Pattern Packs:** Out-of-the-box detection motifs for common fraud topologies: *Circular flow*, *Pass-through shells*, *Inbound aggregators*, and *Layering chains*.
* **Interactive Timeline Slider:** Filters graph edges by timestamp, allowing investigators to play back the chronological evolution of a network.

### 2. Geo Map Analysis Studio
* **100% Air-Gapped Spatial Engine:** Runs entirely without internet access. Bundles a lightweight, offline MapLibre basemap engine with Natural Earth vector datasets (~2.7 MB) for global land, boundaries, and places.
* **Origin-Destination (OD) Routes:** Projects coordinate pairs into weighted great-circle routes to visualize physical movement, logistics flows, or telecom cell-tower handoffs.
* **Spatio-Temporal Playback:** Plays back device or transaction movements over time with configurable playback speed and time windows.
* **Co-Location Intelligence:** Automatically identifies when two distinct entities (e.g., suspect SIM cards or vehicles) were within an identical radius during the same time window.

---

# Page 6: Embedded Intelligence & The Governed Autonomy Ladder
## Air-Gapped AI: Explain, Draft, and Act with Human Approval

```
                       THE GOVERNED AUTONOMY LADDER
  ┌────────────────────────────────────────────────────────────────────────┐
  │ LEVEL 3: BOUNDED AUTONOMY (Enterprise / Opt-in)                        │
  │ • Hands-off automated remediation for bounded, low-risk operational    │
  │   failures (e.g., automated partition retries, quarantined cleanups).  │
  │ • Strict Hourly Budgets • Global Kill Switch • Mandatory SHADOW Mode.  │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 2: ACT WITH APPROVAL (Standard+)                                 │
  │ • Agent drafts a mutation (e.g., config patch, schema fix, job retry). │
  │ • Routed to human operator's Approvals Inbox with a full visual diff.  │
  │ • Zero execution without explicit cryptographically signed sign-off.   │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 1: AUTHORING & DRAFTING (All Editions)                           │
  │ • Natural language to SQL queries, pipeline configs, and schedules.   │
  │ • Generates proposed components in-session; human reviews and saves.   │
  ├────────────────────────────────────────────────────────────────────────┤
  │ LEVEL 0: EXPLAIN & INVESTIGATE (All Editions)                          │
  │ • "Why did batch #408 failure quarantine 12% of rows?"                │
  │ • Grounded RCA diagnosis, schema explaining, and data lineage tracing. │
  │ • 100% Read-Only • Fully Offline • Zero Egress Guarantee.              │
  └────────────────────────────────────────────────────────────────────────┘
```

### 1. The Sovereign AI Imperative: Zero Cloud Egress
In banking, national defense, and intelligence environments, **transmitting proprietary customer records or network logs to commercial cloud LLMs (OpenAI, Anthropic) is strictly prohibited**. 
* **Local / Air-Gapped Model Transport:** Inspecto connects natively to local inference runtimes (Ollama, llama.cpp, vLLM, ONNX Runtime) running directly on the host or inside the customer's secure perimeter.
* **Physically Enforced Air-Gap Invariant:** Cloud LLM SDKs are physically excluded from air-gapped production builds, validated on every CI commit by `EgressGuardTest`. No prompt, token, or dataset cell ever leaves the physical node.

### 2. The Governed Autonomy Ladder: "Sell the Ladder, Not the Ceiling"
Autonomous AI agents that take unmonitored actions are an unacceptable operational risk in regulated infrastructure. Inspecto enforces a **strict, graduated governance ladder**:

* **Tier A — Explain & Investigate (Levels 0 & 1):**
  * Investigates batch crashes, explains complex regex grammars, and translates natural language questions into validated SQL queries against the local lakehouse.
  * Root Cause Analysis: Given a failed run, the agent reads error ledgers, cross-references schema definitions, and produces a ranked root-cause analysis with actionable recommendations.
* **Tier B — Author & Act with Approval (Level 2):**
  * The agent drafts an operational fix (e.g., updating a broken delimiter, expanding a field width, or triggering a backfill).
  * **The Approvals Inbox:** The proposed action lands in the operator’s console with an exact, color-coded visual diff and risk score. Nothing executes until an authorized operator clicks **Approve**.
  * **Identical Audited Seams:** When approved, the agent executes the change through the **exact same authenticated `/api/v1` REST routes** that human operators use, generating an identical actor-attributed audit log.
* **Tier C — Bounded Autonomy (Level 3):**
  * For repetitive operational failures, operators can enable bounded autonomy under strict policies.
  * **Mandatory SHADOW Mode First:** The agent runs in observation mode, recording what it *would* have executed in the `/autonomy` ledger for operator review before any live permission is granted.
  * **Budgets & Kill Switches:** Enforces strict hourly execution limits (e.g., max 3 auto-retries/hour) with an instant, single-click global kill switch.

### 3. The Seven Built-In Reflex Skills & The 23-Tool Deliberative Belt
Inspecto embeds seven specialized, deterministic reflex skills:
1. `DiagnoseAndAlertSkill` — Analyzes error bursts and recommends alert rule tuning.
2. `ExplainEntitySkill` — Plain-language lineage and schema explanations for business users.
3. `KpiToSqlSkill` — Translates business metric definitions into DuckDB-optimized SQL queries.
4. `NlToScheduleSkill` — Converts plain English recurrence requests into validated cron expressions.
5. `ReportNarrativeSkill` — Synthesizes batch health trends into executive summary narratives.
6. `ReportSqlSkill` — Formulates analytical reporting queries with grain and partition pruning.
7. `SuggestConfigSkill` — Proposes parser settings based on raw file sample inspection.

For multi-step investigation, the deliberative agent wields a **23-tool belt**—including `AuthorPipelineDryRun`, `EditConfigDryRun`, and `TriggerJobDryRun`—ensuring every proposed mutation is dry-run verified before presentation.

---

# Page 7: Performance & Measured Capacity
## Single-Node Vectorization vs. Distributed Bloat

### The Capacity Thesis
Distributed big-data frameworks (Spark, Flink, Hadoop) were architected when servers were limited to 4–8 cores and 16 GB of RAM. Today, a modern single server possesses 64–128 cores, hundreds of gigabytes of fast DDR5 memory, and high-throughput NVMe storage. 

For workloads processing under 1 to 2 billion rows per day, **distributed clustering introduces more latency and operational friction than it solves**.

```
  Rows / Second
  ▲
  │  ┌──────────────────────────┐
  │  │ DuckDB Native (Inspecto) │ 523,000 rows/sec
  │  └──────────────────────────┘
  │  ┌──────────────────────────┐
  │  │ Distributed Spark/JVM    │ 40,000–80,000 rows/sec (Single VM Equiv)
  │  └──────────────────────────┘
  │  ┌──────────────────────────┐
  │  │ Legacy Java Parse        │ 25,000–50,000 rows/sec
  │  └──────────────────────────┘
  └────────────────────────────────────────────────────────► Architecture
```

### Grounded Benchmark Metrics
*Measurements from `docs/okf/backend/build-run/performance.md` (JDK 26 / DuckDB 1.5.2, `PipelineBenchmark`, 2M-row production-profile files):*

| Workload / Stage | Measured Throughput | Cost per Unit |
|---|---|---|
| **Native Ingest (12 columns, batch)** | **523,000 rows / sec** | **0.16 µs / cell** |
| **Native Ingest (40 columns)** | **125,000 rows / sec** | **0.20 µs / cell** |
| **Java Fallback Ingest (Complex files)** | **25,000–50,000 rows / sec / core** | **0.65–1.0 µs / cell** |
| **Vectorized SQL Transforms** | **1,400,000 rows / sec** | Direct SIMD vector |
| **Parquet Lakehouse Writes** | **1,100,000 rows / sec** | Compressed disk I/O |

### Production Sizing: 8-Core Commodity Server
* **Average Row Width:** 100 columns (typical telecom CDR or banking transaction record).
* **Processing Density:** 4,000,000 to 10,000,000 cells per second per node.
* **Throughput:** 40,000 to 100,000 rows per second sustained.
* **Daily Ingest Volume:** 3.5 to 8.6 billion rows/day at 100% utilization.
* **Sized for Peak (with 50% Headroom & Peak Multipliers):** **~1.0 Billion rows per day**.

> **The Architectural Rule:** One commodity 8-core server running Inspecto can process the entire daily CDR volume of a mid-sized national telecom carrier or the daily clearing ledger of a major retail bank, without a single external cluster service.

---

# Page 8: Fault Tolerance, HA & Disaster Recovery
## Zero-Loss Ingest & Contracted Recovery Targets

### 1. In-Process Fault Tolerance (NFR-3)
* **Crash-Isolated Batch Execution:** Batches execute in parallel using dedicated, ephemeral DuckDB connections. A query failure or memory error in one feed cannot impact concurrent feeds.
* **The "Markers-Last" Invariant:** Files are staged in temporary working directories and revealed via atomic rename. Commit state follows a strict order:
  $$\text{Catalog Register} \longrightarrow \text{Manifest} \longrightarrow \text{Backup Original} \longrightarrow \mathbf{\text{Markers}} \longrightarrow \mathbf{\text{Ledger Last}}$$
  Because markers and commit ledgers are written last, any interrupted or failed execution leaves zero dirty state and resumes **100% idempotently**.
* **Restart-as-Recovery:** Processing is stateless with respect to the JVM. An unexpected process crash is resolved by the service wrapper restarting the process; in-flight tasks re-arm and resume automatically.

### 2. The Disaster Recovery (DR) Ladder
*Recovery targets defined in `docs/okf/capabilities/editions/editions.md` §3.14:*

| Tier | Deployment Profile | RPO (Max Data Loss) | RTO (Max Downtime) | Recovery Mechanism |
|---|---|---|---|---|
| **T1** | Personal / Workstation | $\le 24\text{ hours}$ | $\le 4\text{ hours}$ | Daily config zip backup + local restore |
| **T2** | Standard Single Server | $\le 1\text{ hour}$ | $\le 1\text{ hour}$ | Hourly config + daily full (post-checkpoint) |
| **T3** | Enterprise Multi-Team | $\le 15\text{ minutes}$ | $\le 2\text{ hours}$ | T2 + Postgres PITR / WAL archiving + off-site copy |
| **T4** | **Standard Warm Standby** | $\mathbf{\le 5 - 15\text{ min}}$ | $\mathbf{\le 30\text{ min}}$ | **Active/Passive replication + space tree sync** |

### 3. T4 Warm Standby (Standard Edition)
* **Active/Passive Architecture:** Provides true multi-site disaster recovery without the operational complexity of distributed consensus protocols.
* **Replication Channels:**
  * **Relational State:** Continuous PostgreSQL streaming replication to the standby site.
  * **File Lakehouse:** Scheduled incremental synchronization of the `spaces/` directory tree (Parquet files, configs, and checkpointed metadata).
* **Failover Protocol:** If Site A fails, an operator promotes the Postgres replica, starts the identical Inspecto binary on Site B, and updates the load balancer/DNS. **Downtime $\le$ 30 minutes; Data loss $\le$ 15 minutes.**

```
  SITE A (Active)                              SITE B (Warm Standby)
 ┌─────────────────────────┐                  ┌─────────────────────────┐
 │ Inspecto Engine (Live)  │                  │ Inspecto Engine (Standby│
 │ ┌─────────────────────┐ │                  │ ┌─────────────────────┐ │
 │ │  Parquet Lakehouse  ├─┼──Space Sync─────►│ │  Parquet Lakehouse  │ │
 │ └─────────────────────┘ │                  │ └─────────────────────┘ │
 │ ┌─────────────────────┐ │                  │ ┌─────────────────────┐ │
 │ │  PostgreSQL State   ├─┼──Stream Repl────►│ │  PostgreSQL Replica │ │
 │ └─────────────────────┘ │                  │ └─────────────────────┘ │
 └─────────────────────────┘                  └─────────────────────────┘
```

---

# Page 9: Operational Governance, Security & Compliance
## Active SLA Sweeps & Auditor-Grade Sovereign Governance

### 1. Operational Governance & Automated SLA Sweeps
* **Feed Freshness & Sequence Verification:** Inspecto tracks sequence continuity (`{seq}`) and delivery watermarks. Missing files immediately trip a `SEQUENCE_GAP` Signal and open an Incident.
* **Incident SLAs & Background Sweeps:** Every Incident can carry an explicit resolution deadline (e.g., `dueInMinutes: 120`). A background daemon sweeps open Incidents every 60 seconds (`-Dobjects.sla.sweep.seconds`).
* **Automated Escalation (`*_escalation.toon`):** Upon breach, the engine permanently stamps `slaBreachedAt`, emits an `OBJECT_SLA_BREACH` event, escalates priority from P2 to P1, and dispatches webhook alerts.
* **Executive Visibility (`/kpi-reports`):** Live tracking of Mean Time to Detect (MTTD), Mean Time to Resolve (MTTR), and Incident aging metrics.

### 2. Compliance Framework Mappings
*Audited in [`compliance/controls-matrix.md`](../../compliance/controls-matrix.md):*

```
  ┌────────────────────────────────────────────────────────────────────────┐
  │                      INSPECTO SECURITY PERIMETER                       │
  │  ┌────────────────┐    ┌─────────────────┐    ┌─────────────────────┐  │
  │  │ Delegated IAM  │    │  Zero-Egress    │    │ Single-Dispatch     │  │
  │  │ • OIDC / PKCE  │    │  Air-Gap Core   │    │ Audit Trail         │  │
  │  │ • Okta / Entra │    │  • Offline Maps │    │ • Central Dispatch  │  │
  │  │ • Keycloak     │    │  • 0 Phone-Home │    │ • Immutable Ledger  │  │
  │  └────────────────┘    └─────────────────┘    └─────────────────────┘  │
  │  ┌────────────────┐    ┌─────────────────┐    ┌─────────────────────┐  │
  │  │ Secret Hygiene │    │ Supply Chain    │    │ File Path Security  │  │
  │  │ • Zero Cleartext│   │ • 94 Locked Deps│    │ • PathJail Jailing  │  │
  │  │ • JCEKS / Vault│    │ • GPG Signatures│    │ • Write-Root Gates  │  │
  │  └────────────────┘    └─────────────────┘    └─────────────────────┘  │
  └────────────────────────────────────────────────────────────────────────┘
```

* **SOC 2 Type II Alignment:**
  * **CC6.1 (Credential Security):** Passwords and private keys are never stored in cleartext. Inspecto uses `SecretResolver` to resolve credentials at runtime via environment variables, files, or encrypted JCEKS keystores. A build guard (`tools/check-secrets.mjs`) fails CI if any credential literal is introduced.
  * **CC6.7 (Data Egress Prevention):** 100% air-gap capable by design. Contains zero external telemetry, zero tracking scripts, and an enforced lean dependency surface.
  * **CC8 (Software Supply Chain):** Every build is verified against `tools/dependencies.lock` (94 locked third-party dependencies). Release artifacts include SHA-256 checksums and detached GPG signatures (`package.ps1 -Sign`).
* **ISO/IEC 27001:2022 (Annex A):**
  * **A.8.15–A.8.17 (Single-Dispatch Audit Logging):** All state-changing API operations (`POST`, `PUT`, `DELETE`, `/export`) pass through a single architectural dispatch seam in `AuditTrail.java`. The system records an append-only audit trail with actor attribution and timestamps.
  * **A.8.2–A.8.5 (Privileged Access):** Authentication is delegated to enterprise Identity Providers (Keycloak, Okta, Microsoft Entra ID) using OIDC with PKCE.
* **NIST 800-53 Rev 5 / FedRAMP (Moderate Baseline):** Pre-mapped customer-responsibility matrices accelerate agency **Authority to Operate (ATO)** certifications. `SI-10` input validation and `CM-6` declarative configuration (`.toon`) with `PathJail` path containment protect against directory traversal.

---

# Page 10: Total Cost of Ownership (TCO) & Packaging
## Editions Ladder & The 60-Minute Evaluation

### The 3-Year TCO Comparison
*Typical deployment: Ingesting, reconciling, and reporting on 100M–500M records/day.*

| Cost Category | Legacy Multi-Vendor Stack | Inspecto Standard Edition |
|---|---|---|
| **Infrastructure Compute** | 8–15 VMs (Kafka, Spark, Superset, Postgres, NiFi): **$36,000/yr** | 1 Production VM + 1 DR Standby VM: **$6,000/yr** |
| **Software Licensing** | Multi-vendor subscriptions (ETL, BI, Observability): **$80,000/yr** | Single platform license: **Predictable Flat Fee** |
| **Engineering Headcount** | 2–3 Dedicated Platform/DevOps Engineers: **$300,000/yr** | 0.5 FTE Data Operations Operator: **$60,000/yr** |
| **Compliance & Audit Overhead**| Multi-vendor SOC2 reviews, penetration tests: **$40,000/yr** | Single self-contained binary audit: **$10,000/yr** |
| **3-Year Estimated TCO** | **$1,368,000** | **$228,000 (83% Savings)** |

---

### The Clean Edition Ladder
*Editions are build flavors of one codebase, never forks:*

```
  ┌────────────────────────┐
  │       ENTERPRISE       │  Scale-Out: Partitioned Kubernetes clustering,
  │                        │  ABAC tenant boundaries, shared object storage.
  └───────────▲────────────┘
              │
  ┌───────────┴────────────┐
  │        STANDARD        │  The Revenue Gate: OIDC SSO, HTTPS, RBAC,
  │                        │  Attributed Audit, Geo/Link Analysis,
  │                        │  Active/Passive Warm Standby DR (T4),
  │                        │  Embedded Intelligence (Tiers A & B).
  └───────────▲────────────┘
              │
  ┌───────────┴────────────┐
  │        PERSONAL        │  The Free Wedge: Full data plane, acquisition,
  │                        │  ASN.1, lakehouse, Query Library, Studio BI,
  │                        │  Reconciliation & Breaks. 100% Free on Laptop.
  └────────────────────────┘
```

---

### The 60-Minute Evaluation Challenge
*Experience Inspecto on your hardware with your data today:*

```
                           GET STARTED IN 3 STEPS
  1. Download: Obtain the ~90 MB self-contained binary.
  2. Launch: Run locally with zero external database dependencies:
     ./inspecto -Dcontrol.port=8080
  3. Load & Reconcile: Apply an included Space Template or drop in an existing feed.
  
  Within 60 minutes, evaluate high-speed ingestion, automated break detection,
  interactive dashboards, and offline AI assistance on your own machine.
```

* **Commercial Inquiries:** `sales@inspecto.io`
* **Documentation & Blueprints:** `https://inspecto.io/docs`
* **Headquarters:** Inspecto Data Systems • *Sovereign Data Operations*
