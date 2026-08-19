# Inspecto Platform — Comprehensive Architecture & Design Review

**Author:** Antigravity Architecture Specialist  
**Target Codebase:** `inspecto-clean` (Java 24–26 Reactor / Angular 17+ Console / DuckDB Embedded Engine)  
**Date:** August 2026  
**Scope:** Full-System Architecture, Static & Dynamic Design, Data Engineering Engine, Control Plane, Frontend Architecture, Security, Operational Posture, and Strategic Debt Review.

---

## 1. Executive Summary & System Identity

### 1.1 Platform Vision & Core Value Proposition
**Inspecto** (formerly *UCC File Processor*) is an enterprise-grade, high-throughput, file-oriented **Extract-Load-Transform (ELT)** data platform and operational control plane. The platform is engineered to ingest, parse, validate, enrich, and query massive volumes of structured and semi-structured file streams (such as Call Detail Records [CDRs], financial transactions, billing logs, ASN.1 binary telemetry, and multi-format delimiter streams) with microsecond overhead, strict audit trails, and zero-loss guarantees.

Unlike conventional big-data frameworks that introduce heavy distributed runtime overhead (e.g., Spark, Flink, Kafka clusters), Inspecto adheres to a **lean, embedded, zero-unnecessary-dependency architecture**. It couples the modern **Java Virtual Machine (JDK 24–26 with virtual threads)** with **in-process analytical querying via DuckDB**, columnar **Parquet** storage, declarative **TOON** configuration, and an **Angular OnPush/Signals** single-page application (SPA).

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       INSPECTO PLATFORM                                          │
│                                                                                                  │
│   ┌──────────────────────────┐    ┌──────────────────────────┐    ┌──────────────────────────┐   │
│   │   inspecto-ui (SPA)      │    │   Control Plane (HTTP)   │    │   Orchestration & Jobs   │   │
│   │   Angular 17+ Standalone │◄──►│   JDK HttpServer + V-Thr │◄──►│   Virtual Thread Pools   │   │
│   │   Signals / OnPush / G6  │    │   /api/v1 Contract       │    │   Scheduler / RunGuard   │   │
│   └──────────────────────────┘    └──────────────────────────┘    └──────────────────────────┘   │
│                                                 │                                                │
│                                                 ▼                                                │
│   ┌──────────────────────────────────────────────────────────────────────────────────────────┐   │
│   │                                   Core ELT Engines                                       │   │
│   │  ┌────────────────────────┐   ┌────────────────────────┐   ┌──────────────────────────┐  │   │
│   │  │   Acquisition Framework│   │  Stage-1 Ingest Engine │   │   Stage-2 Transform/Join │  │   │
│   │  │   SFTP/S3/Local/Dedup  │──►│  Native DuckDB Appender│──►│   MeasureCompiler/Shaper │  │   │
│   │  └────────────────────────┘   └────────────────────────┘   └──────────────────────────┘  │   │
│   └──────────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                 │                                                │
│                                                 ▼                                                │
│   ┌──────────────────────────────────────────────────────────────────────────────────────────┐   │
│   │                               Storage & Isolation Layer                                  │   │
│   │   Hive-Partitioned Parquet  │  Ephemeral DuckDB Sandboxes  │  Multi-Space Directory Trees │   │
│   └──────────────────────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Core Architectural Principles
1. **Framework-Free Java Core**: Deliberate avoidance of Spring Boot, Jakarta EE, or heavy IoC containers. Inversion of control is achieved via explicit constructor injection, ServiceLoader SPIs, and the `ApiContext` facade.
2. **Virtual Threads as Standard Concurrency**: High-concurrency I/O and task dispatch leverage `Executors.newVirtualThreadPerTaskExecutor()`, drastically simplifying asynchronous code while sustaining thousands of concurrent streams.
3. **Embedded OLAP via DuckDB**: Columnar transformations, schema inference, format conversions, and BI sandboxing execute directly within DuckDB instances. Ingestion uses the high-performance native DuckDB Appender API.
4. **Consignment Unit-of-Work (`Run ⊇ Consignment ⊇ File`)**: Deterministic batch identity and manifest tracking guarantee append-only immutability, exact reprocessing without side-effects, and verifiable lineage.
5. **Editions as Build Flavors**: Personal, Standard, and Enterprise editions share a single unified `master` branch. Commercial capabilities (OIDC/JWT authentication, ABAC policy engine) reside in dedicated Maven modules loaded via `ServiceLoader` profiles.

---

## 2. Static Architecture & Module Decomposition

### 2.1 Multi-Module Reactor Topography
The codebase is structured as a Maven multi-module reactor comprising 14 Java modules, 1 native ASN submodule, and 1 Angular frontend:

```
inspecto-clean/
├── pom.xml                        # Root Aggregator POM & Parent Dependency Management
├── asn-parser/asn-decoders/       # ASN.1 Binary Telemetry Decoders (Separate Release Lifecycle)
├── inspecto-api/                  # Leaf Module: @PublicApi and Core API Marker Interfaces
├── inspecto-util/                 # Leaf Module: DuckDbUtil, CronExpression, CSV/Tar/IO Utilities
├── inspecto-config/               # Leaf Module: TOON Codec, ConfigSpecs, ConfigSafetyValidator
├── inspecto-sql/                  # Leaf Module: SqlSandbox, SqlGuard, SqlOracle, Query Isolation
├── inspecto-etl/                  # Domain Foundation: Batch Models, Ingest Interfaces, TypeFlow
├── inspecto-event/                # Domain Foundation: EventLog, Metrics, Parquet Audit Store
├── inspecto-acquire/              # Acquisition Layer: Pollers, Ledgers, StabilityGate, Connectors SPI
├── inspecto-engine/               # Core Execution Engine: BatchProcessor, Pipeline IR, Jobs, Consignment
├── inspecto/                      # Composition Root & Fat-JAR: ControlApi, SpaceManager, Host Service
├── inspecto-connectors/           # Extension Module: SFTP, FTPS, S3, Kafka, Remote DB Connectors
├── inspecto-agent/                # AI Assist Module: Vendored Reasoning Kernel, Skill Definitions
├── inspecto-agent-hosted/         # Extension Module: Hosted LLM Providers (Claude, OpenAI, Gemini)
├── inspecto-intelligence/         # Analytics Module: Statistical Profiling, Anomaly & Baseline Evaluator
├── inspecto-security/             # Standard Edition Profile: Nimbus OIDC/JWT Authenticator & TokenRelay
├── inspecto-policy/               # Enterprise Edition Profile: ABAC AccessDecider & Policy Engine
└── inspecto-ui/                   # Angular 17+ SPA Console (Dev server :4204, embedded in JAR)
```

### 2.2 Layered Dependency Model (L0–L5)
The platform enforces a strict unidirectional layer model:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ L5: EXTENSION MODULES (Optional ServiceLoader Providers)                                         │
│     inspecto-connectors │ inspecto-security │ inspecto-policy │ inspecto-agent-hosted             │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ L4: CONTROL PLANE & HTTP INTERFACE                                                               │
│     inspecto (com.gamma.control) — ControlApi, RouteModules, ApiContext, ErrorCodes              │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ L3: ORCHESTRATION & HOST LIFECYCLE                                                               │
│     inspecto (com.gamma.service) — CollectorService, SpaceManager, Scheduler, PipelineRunGuard  │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ L2: DOMAIN EXECUTION ENGINES                                                                     │
│     inspecto-engine    — BatchProcessor, PipelineExecutor, ConsignmentOutputStore, JobService    │
│     inspecto-acquire   — CollectorWatcher, RemoteAcquisitionHandler, StabilityGate               │
│     inspecto-agent     — UccAssistAgent, Tool Execution Harness                                  │
│     inspecto-intelligence — InspectoIntelligenceAgent, BaselineExpectations                      │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ L1: PLATFORM SERVICES & CROSS-CUTTING CONCERNS                                                   │
│     inspecto-etl       — BatchManifest, StreamingFileIngester, DuckDbCsvIngester, TypeFlow       │
│     inspecto-event     — EventLog, ParquetEventStore, MetricRegistry                             │
│     inspecto-config    — ConfigCodec, ConfigSpecs, ConfigSafetyValidator                        │
│     inspecto-sql       — SqlSandbox, SqlGuard, SqlViews                                          │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ L0: FOUNDATION LEAVES (No com.gamma Internal Dependencies)                                       │
│     inspecto-api       — @PublicApi                                                              │
│     inspecto-util      — DuckDbUtil, CronExpression, File Helpers                                │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Package Coupling & Dependency Analysis
A rigorous audit of package dependencies reveals strong boundaries with a few targeted areas of tight coupling:

| Source Package | Target Packages | Coupling Risk | Assessment & Recommendation |
|---|---|---|---|
| `com.gamma.control` | 20+ packages across L1–L3 | Low / Normal | Healthy: Control plane acts as the top-level REST boundary and uses `ApiContext` to coordinate lower domains. |
| `com.gamma.service` (`CollectorService`) | `etl`, `event`, `pipeline`, `enrich`, `job`, `catalog`, `acquire`, `inspector` | **High (Hotspot)** | `CollectorService` functions as a God Object (~1,600 lines, 25+ fields, 10 responsibilities). Should be refactored into focused lifecycle hosts. |
| `com.gamma.service` ↔ `com.gamma.job` ↔ `com.gamma.pipeline.exec` | Mutual references | **Medium (Cycle)** | Three-way cyclic dependency around `BatchEventBus`, `PipelineJobRunner`, and `JobService`. Extracting common event contracts to `inspecto-etl` or `inspecto-api` will eliminate this cycle. |
| `com.gamma.security` | `Authenticator`, `TokenRelay`, `Subject` | **Very Low (Ideal)** | Pristine SPI isolation. Interacts with core exclusively through 4 small interfaces. |
| `com.gamma.agent` | `catalog`, `config`, `sql`, `etl`, `service`, `job`, `report` | **Medium** | Agent directly imports internal engine implementations instead of an explicit `agent.spi` facade. |

---

## 3. Dynamic Execution & ELT Data Pipeline Architecture

### 3.1 Dual-Stage Processing Paradigm

Inspecto operates an **ELT architecture** divided into two discrete processing stages:

```
  INCOMING FILES
 (CSV / JSON / ASN.1)
        │
        ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ STAGE-1: EXTRACTION & LOADING (Streaming Multiplexer)                                            │
│                                                                                                  │
│  [File Discovery] ──► [Stability Gate] ──► [Format Detection] ──► [Native Appender Ingestion]   │
│                                                                                  │               │
│                                                                                  ▼               │
│  [Audit Ledger Commit] ◄── [Manifest Update] ◄── [Hive-Partitioned Parquet Data Landed]          │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
        │
        ▼ (BatchEventBus Fan-Out Signal)
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ STAGE-2: IN-DATABASE TRANSFORMATION & ENRICHMENT (At-Rest ELT)                                   │
│                                                                                                  │
│  [Event Trigger / Cron] ──► [Consignment Selection] ──► [DuckDB In-Memory Analytical Execution]  │
│                                                                                  │               │
│                                                                                  ▼               │
│  [Summary Table Update] ◄── [DQ Expectations Check] ◄── [Reference Lookups / Aggregations]       │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

#### Stage-1: High-Speed Ingest & Partitioning
* **Streaming Parser Engine**: `DuckDbCsvIngester` and `NativeCsvStreamingEngine` parse structured delimited and multi-segment streams using DuckDB native appenders.
* **Chunking & Boundary Scans**: Large files are segmented by `FileChunker` and scanned with `BoundaryScanner` to ensure record alignment without loading entire datasets into JVM heap memory.
* **Storage Output**: Writes raw structured data into Hive-partitioned columnar Parquet files (`year=YYYY/month=MM/day=DD/target_consignment.parquet`).
* **Batch Atomicity**: Governed by `CommitLog` and `MarkerManager`. Markers are written last, ensuring zero partial commits upon crash or hardware failure.

#### Stage-2: Transformation, Joins, and Metrics
* **Transformation Execution**: Post-load processing is orchestrated via `PipelineExecutor`, `MaterializeTask`, `MeasureCompiler`, and `RowShaper`.
* **Reference Joins**: Performed via SQL joins in DuckDB sandboxes through `ReferenceResolver`.
* **Data Quality Gates**: `ExpectationRoutes` and `ExpectationEvaluator` validate statistical distributions, nullability constraints, and value bounds against freshly landed partitions.

### 3.2 The Consignment Model (`Run ⊇ Consignment ⊇ File`)
The Consignment abstraction is the foundational data invariant of the platform:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ RUN (Single Execution Trigger / Schedule / Manual Invocation)                                    │
│  │                                                                                               │
│  ├──► CONSIGNMENT A (Unit-of-Work: Logical Ingest Partition / Revision)                          │
│  │     │                                                                                         │
│  │     ├──► File 1 (Input Data Slice) ──► Manifest Lineage Tracked                               │
│  │     └──► File 2 (Input Data Slice) ──► Checksum / Row Count / Event-Time Pinned               │
│  │                                                                                               │
│  └──► CONSIGNMENT B (Independent Unit-of-Work)                                                   │
│        └──► File 3                                                                               │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

* **Data Invariant**: *No file holds records from two different Consignments*.
* **Manifests as Relations**: Manifests are stored in `DbConsignmentOutputStore` and queryable directly in DuckDB SQL (`consignment_outputs` relation).
* **Compaction & Retention Hierarchy**: `Lateness Horizon ≤ Seal Horizon ≤ Compaction Horizon ≤ Raw Retention`. Partitions transition deterministically (`OPEN → SEALED → REOPENED`).
* **Reprocess Without In-Place Mutation**: `ReprocessCommand` supersedes historical consignments, generating a new `consignment_id` revision rather than destructively overwriting files on disk.

### 3.3 Authored Pipeline Intermediate Representation (IR)
Pipelines are defined via a unified graph intermediate representation:
* **Dual Projection Model**: A single underlying pipeline specification supports both visual canvas graph representation (`PipelineGraph`, `PipelineNode`, `PipelineEdge`) and flat recipe form (`RecipeCompiler`, `PipelineEditable`).
* **Static Type Flow**: `TypeFlow` derives output schemas at compile-time by executing DuckDB `DESCRIBE` queries across simulated pipeline steps.
* **Fail-Closed Arming Gates**: Advanced nodes (such as dynamic routing and multi-branch execution) compile to canonical AST nodes but enforce fail-closed checks at `prepare()` until full branch-aware execution is activated.

---

## 4. State Management, Storage, & Persistence Patterns

### 4.1 Ephemeral DuckDB Lifecycle Architecture
Inspecto avoids long-lived, multi-threaded database connection pools for analytical workloads. Instead, it employs an **open-per-use, sandbox-isolated connection lifecycle**:

```java
// Standard Architectural Invariant across Query & Preview Services
try (DuckDbConnection conn = DuckDbUtil.openConnection(dbPath)) {
    SqlSandbox.runIsolated(conn, () -> {
        // Execute sandboxed analytical SQL
    });
} // Connection, locks, and native allocations immediately released
```

* **Security Sandboxing**: `SqlSandbox` and `SqlGuard` enforce read-only execution, restricted file system access, and statement timeouts on user-supplied queries.
* **Native Memory Management**: Avoids Java heap exhaustion by offloading analytical aggregations directly to DuckDB off-heap C++ allocations.
* **Concurrency Trade-Off**: Because DuckDB allows single-writer / multi-reader access per database file, per-pipeline execution locks (`PipelineRunGuard`) prevent lock contention during batch writes.

### 4.2 Dual-Storage Abstraction Pattern
For every persistence domain (audit logs, object models, job runs, notification receipts, status tracking), Inspecto implements a strict interface-backed dual-store pattern:

```
                         ┌────────────────────────────┐
                         │   Domain Store Interface   │
                         │   (e.g., ObjectStore)      │
                         └──────────────┬─────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
  ┌─────────────────────────────┐               ┌─────────────────────────────┐
  │   InMemoryObjectStore       │               │   DbObjectStore             │
  │   (ConcurrentHashMap / Ring)│               │   (DuckDB / JDBC Table)     │
  │   • Fast Unit Testing       │               │   • Durable Production Ops  │
  │   • Zero-IO Development     │               │   • Append-Only Parquet Log │
  └─────────────────────────────┘               └─────────────────────────────┘
```

### 4.3 Multi-Space (Multi-Tenancy) Isolation Model
Multi-tenancy is structured around **Spaces** without requiring a shared, multi-tenant database schema:

```
spaces/
├── default/                   # Default / Single-Tenant Space
│   ├── config/                # Pipeline & Component TOON Configurations
│   ├── data/                  # Hive-Partitioned Parquet Storage
│   ├── audit/                 # Commit Logs & Batch Audit Records
│   └── duckdb/                # Local Space Metadata & Status DB
└── tenant_alpha/              # Isolated Tenant Space (Completely Separate Tree)
    ├── config/
    ├── data/
    ├── audit/
    └── duckdb/
```

* **Execution Routing**: Incoming HTTP requests bind the active Space via URL path prefix (`/spaces/{id}/...`) or header.
* **Context Propagation**: `ControlApi.dispatch` strips the space prefix and sets the tenant ID into **SLF4J MDC (`EventLog.currentSpaceId()`)**.
* **Zero Cross-Talk**: All file paths, DuckDB catalogs, and audit ledgers resolve relative to the isolated space root directory.

---

## 5. Control Plane & API Design

### 5.1 Lightweight Embedded HTTP Architecture
The control plane avoids heavy servlet containers (Tomcat/Jetty) in favor of the lightweight, native **JDK `HttpServer`**:
* **Virtual Thread Execution**: Every request is assigned a virtual thread via `Executors.newVirtualThreadPerTaskExecutor()`, providing non-blocking scalability.
* **Dispatch Pipeline**: `ControlApi.dispatch` executes a high-speed, non-allocating processing pipeline:
  1. **Correlation Stage**: Generates or extracts `Correlation-ID`.
  2. **Scope Sanitation**: Clears request-scoped `HttpExchange` attributes (`clearRequestScope`) to prevent cross-request leakage on shared JVM configurations.
  3. **Space Binding**: Extracts space context and binds MDC.
  4. **Authentication & Authorization**: Evaluates `Authenticator` and ABAC `AccessDecider` policies.
  5. **Routing & Invocation**: Matches route regexes and executes stateless `RouteModule` handlers.
  6. **Envelope Wrapping & Diagnostics**: Encapsulates payload in standard `{data, metadata, links, diagnostics}` envelope.

```
Incoming Request
       │
       ▼
 [Correlation ID Extraction / Injection]
       │
       ▼
 [ExchangeAttributeScope Sanitation (P0 Security Control)]
       │
       ▼
 [MDC Space ID Resolution (/spaces/{id}/...)]
       │
       ▼
 [Authentication & Capability Check (ServiceLoader)]
       │
       ▼
 [Stateless RouteModule Invocation (ApiContext Facade)]
       │
       ▼
 [Structured Envelope & ETag Response Generation]
```

### 5.2 Event Bus Separation of Concerns
The platform cleanly bifurcates event handling into two distinct channels:
1. **`event.EventLog` (Platform Audit & Observability)**: Append-only, space-scoped log backed by an in-memory ring buffer and durable Parquet event store. Emits operational events (`SEQUENCE_GAP`, `FLOW_CONSERVATION_IMBALANCE`, `PIPELINE_RUN_COMPLETED`) to UI subscribers, alerts, and notifications.
2. **`service.BatchEventBus` (Internal Domain Orchestration)**: Low-latency in-memory pub/sub (`Consumer<BatchEvent>`) that triggers Stage-2 downstream tasks (e.g., `JobService`, `EnrichmentService`) immediately upon Stage-1 commit.

---

## 6. Frontend Architecture & Design System (`inspecto-ui`)

### 6.1 Modern Angular Stack & State Management
`inspecto-ui` is built on modern Angular conventions:
* **Standalone Architecture**: 100% standalone components, directives, and pipes. Zero `NgModule` boilerplate.
* **Change Detection**: Universal enforcement of `ChangeDetectionStrategy.OnPush`.
* **Reactivity via Signals**: State is modeled through `signal()`, derived projections through `computed()`, and controlled local mutations via `linkedSignal()`. RxJS is strictly reserved for HTTP streams and cancellation.
* **Strict Feature Modularity**: Features reside under `src/app/modules/admin/<feature>` and are strictly decoupled. Shared components, tokens, and data utilities live in `src/app/inspecto/`.

```
src/app/
├── inspecto/                  # CORE / SHARED LIBRARIES (Zero Feature Imports)
│   ├── api/                   # Resource Services, Interceptors (Space, Auth, Error, V1)
│   ├── components/            # Design System (StatusBadge, Alert, EmptyState, Skeleton)
│   ├── component-model/       # Metamodel Engine, AttributeSpec, SchemaForm
│   ├── data-table/            # Reusable Queryable Table Family & ag-Grid Themes
│   ├── graph/                 # AntV G6 Graph Visualizer Host (Link Analysis, Pipelines)
│   ├── geo/                   # MapLibre GL Host & GeoSource Services
│   └── theme/                 # Design Tokens & Palette Specifications
└── modules/admin/             # FEATURE MODULES (Lazy Loaded, Standalone)
    ├── dashboard/             # Operational Dashboard
    ├── pipelines/             # Visual Pipeline Editor & Definition Drawer
    ├── studio/                # Datasets, Queries, Dashboards, Geo, Link Analysis
    ├── objects/               # Operational Cases & Incidents Console
    └── spaces/                # Multi-Tenant Administration Console
```

### 6.2 The Shared Component & Design System
* **Dynamic Metamodel Form (`<inspecto-schema-form>`)**: Renders complex configuration interfaces dynamically from server-published `AttributeSpec` and `ConfigSpecs` definitions, ensuring zero UI drift when backend schemas evolve.
* **Unified Data Table Stack (`<inspecto-data-table>`)**: Wraps ag-Grid with integrated query builders, saved views, column pinning, Excel/CSV export, and accessibility keyboard navigation.
* **Interactive Visualization Engine**: Features integrated graph rendering via **AntV G6** (for pipeline DAGs, entity lineage, and link analysis) and geospatial mapping via **MapLibre GL**.

### 6.3 Dual-Mode Pipeline Authoring Surface
The pipeline authoring experience combines visual graph editing with deep structural inspection:
* **Host Surface**: Central `/pipelines` editor equipped with an interactive canvas and a collapsible **Right-Dock Definition Drawer**.
* **Per-Tab Sample Streaming**: Dedicated sample thread maps sample data across parsing, schema casting, mapping, and output preview without mutating persisted state.
* **Zero-Loss Model Synchronization**: The UI bidirectional synchronization translates visually edited graphs to canonical flat TOON configurations via `PipelineEditable`.

---

## 7. Security, Governance, & Multi-Tenancy Architecture

### 7.1 Authentication & Token Propagation (Standard Edition)
* **Zero Auth Core (Personal Edition)**: The base distribution contains zero authentication overhead for local development, embedded execution, and single-user environments.
* **OIDC & PKCE Federation (Standard Edition)**: `inspecto-security` integrates OAuth2 / OpenID Connect authorization code flow with **Proof Key for Code Exchange (PKCE)** (RFC 7636).
* **Nimbus JOSE/JWT Verification**: Incoming JWT bearer tokens are validated in-process using cached JWKS sets.
* **Downstream Token Relay**: `OidcTokenRelay` securely forwards user credentials to authenticated remote endpoints (S3, Kafka, external databases).

### 7.2 Authorization & Policy Engine (Enterprise Edition)
* **Role-Based Access Control (RBAC)**: Fine-grained permissions are checked via `ApiContext.requireCapability()` against static role manifests.
* **Attribute-Based Access Control (ABAC)**: In Enterprise edition, `inspecto-policy` introduces `PolicyEngine` (implementing `AccessDecider`), evaluating environment attributes, space tenancy, resource ownership, and user clearance.
* **Auditability & Legal Compliance**:
  * All write operations emit structured audit records via `AuditTrail`.
  * `incident_purge` enforces data retention policies while strictly preserving records under active legal holds.
  * Secrets, API tokens, and private keys are dynamically masked in Logback outputs.

---

## 8. Embedded Intelligence & AI Subsystem Architecture

Inspecto incorporates an air-gap-capable, schema-constrained embedded intelligence layer:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│ AI / EMBEDDED INTELLIGENCE SUBSYSTEM                                                             │
│                                                                                                  │
│  ┌────────────────────────┐    ┌────────────────────────┐    ┌───────────────────────────────┐   │
│  │ inspecto-agent         │    │ inspecto-intelligence  │    │ inspecto-agent-hosted         │   │
│  │ • Assist Skills Kernel │    │ • Statistical Profiler │    │ • LangChain4j Provider Plugin │   │
│  │ • Tool Execution Engine│    │ • Anomaly Detection    │    │ • Claude / OpenAI / Gemini    │   │
│  │ • Schema Constraints   │    │ • Baseline Expectation │    │ • (Omitted in Air-Gap Builds) │   │
│  └────────────────────────┘    └────────────────────────┘    └───────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

* **Deterministic-First Principle**: Deterministic algorithms, heuristic derivations, and static SQL compilers are always attempted before invoking LLM inference.
* **Schema-Constrained Function Calling**: LLM interactions are bound by `ConfigJsonSchema` projections, preventing hallucinated configuration keys.
* **Bounded Self-Repair Loops**: In case of validation refusals, the system executes an automated repair loop capped at 3 iterations to correct syntax or schema mismatches.

---

## 9. Operational Posture, Concurrency, & Performance

### 9.1 Concurrency & Workload Isolation
* **Virtual Thread Work Pools**: HTTP requests, scheduled cron executions, and multi-collector poll cycles operate on independent virtual thread pools.
* **Per-Pipeline Serialization (`PipelineRunGuard`)**: Prevents concurrent ingestion runs on the same pipeline to protect data ordering, while allowing distinct pipelines to execute in full parallel concurrency.
* **Ingestion Throughput**: Native DuckDB Appender enables bulk ingestion rates exceeding **100,000+ records/sec** per core on standard commodity hardware.

### 9.2 Resilience & Self-Healing
* **Stability Gate**: Verifies that remote or polling file inputs have reached steady byte sizes before acquiring, preventing truncated ingest.
* **Quarantine Management**: Corrupted, malformed, or unparseable records are isolated to `quarantine/` with diagnostic error traces without halting pipeline execution.
* **Crash-Consistent Recovery**: Interrupted batch executions are safely resumed on next boot by scanning the append-only `CommitLog` and marker state.

---

## 10. Critical Architectural Findings, Technical Debt, & Strategic Recommendations

Based on a comprehensive architectural audit, the following findings, trade-offs, and modernization vectors are identified:

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                               ARCHITECTURAL ROADMAP & ACTION MATRIX                              │
│                                                                                                  │
│   [P0: Immediate Structural Health]                                                              │
│   ├── Decompose CollectorService God Object into focused lifecycle coordinators                  │
│   └── Unify Pipeline Identity Normalization (slugification vs regex vs filename)                 │
│                                                                                                  │
│   [P1: Near-Term Capability Completion]                                                          │
│   ├── Complete Branch-Aware Runtime Execution in PipelineExecutor (activate all 20 node types)   │
│   ├── Formalize RouteModule Discovery via ServiceLoader registry                                 │
│   └── Implement Explicit Request Filter/Middleware Chain in ControlApi                           │
│                                                                                                  │
│   [P2: Long-Term Enterprise Scalability]                                                         │
│   ├── Implement Multi-User Pooled Postgres Backend (execute postgres-multi-user-plan.md)         │
│   └── Formalize External Lakehouse Catalog Integration (DuckLake / Apache Iceberg)               │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 10.1 High-Priority Findings & Architectural Hotspots

#### Finding 1: God-Class Anti-Pattern in `CollectorService`
* **Observation**: `com.gamma.service.CollectorService` has grown to ~1,624 lines, 25+ fields, and ~99 KB of code. It aggregates 10 distinct responsibilities: pipeline registry management, schedule coordination, batch event distribution, status tracking, SLA monitoring, agent registration, space migrations, and multi-collector worker management.
* **Risk**: High maintenance friction, elevated blast radius for modifications, and testing complexity.
* **Recommendation**: Decompose `CollectorService` into distinct, focused delegates:
  1. `PipelineRegistryManager`: Owns registered TOON paths and reloading.
  2. `CollectorExecutionCoordinator`: Manages poll schedules and worker execution pools.
  3. `BatchLifecycleOrchestrator`: Owns `BatchEventBus` wiring and commit listeners.
  4. `ServiceAgentSlot`: Generic manager for optional assist and intelligence agents.

#### Finding 2: Incomplete Stage-2 Branch-Aware Runtime Execution
* **Observation**: The authored pipeline graph supports 20 node types and compiles complex DAGs, but `PipelineExecutor` currently executes only linear pipelines natively. 11 of the 20 node types (e.g., conditional route demuxing, multi-sink fanout) enforce compile-time validation but refuse execution at lowering.
* **Risk**: Architectural disconnect between the visual editor capabilities and the runtime execution engine.
* **Recommendation**: Complete Phase 4 of the ELT architecture plan by implementing branch-aware execution in `PipelineExecutor`, allowing conditional branching and multi-target sink writes to execute seamlessly over landed consignments.

#### Finding 3: Pipeline Identity Normalization Dissonance
* **Observation**: Three separate rules govern pipeline naming and identity derivation: (a) the explicit `id` regex pattern, (b) the automatic slug derivation (lowercase + underscores), and (c) the filename mapping in `ConfigRoutes`. In edge cases (e.g. `my-pipe` with hyphens), these rules produce conflicting identity strings.
* **Risk**: Subtle inconsistencies during pipeline renaming, export/import operations, and multi-tenant bundle migrations.
* **Recommendation**: Establish a single, canonical `PipelineIdentity` value object in `inspecto-etl` that encapsulates validation, slugification, and file-path mapping across both frontend and backend.

#### Finding 4: Hardcoded Route Registration & Monolithic Dispatch in `ControlApi`
* **Observation**: `ControlApi.registerRoutes()` manually instantiates a hardcoded list of ~24 `RouteModule` instances. Concurrently, `ControlApi.dispatch()` inlines correlation ID generation, CORS handling, authentication, space binding, and metrics scraping in a monolithic ~100-line method.
* **Risk**: Inability for plugins to register custom REST endpoints dynamically; difficulty adding cross-cutting request interceptors.
* **Recommendation**:
  1. Convert `RouteModule` to a public `ServiceLoader` SPI to allow modules and plugins to contribute endpoints.
  2. Refactor `dispatch()` into a clean, composable **Handler/Filter Middleware Pipeline** (`Filter.doFilter(exchange, chain)`).

#### Finding 5: DuckDB Memory Limits vs Unbounded Job Concurrency
* **Observation**: DuckDB operations run in virtual threads with default unbounded concurrency. Under heavy simultaneous multi-pipeline runs or large analytical group-by operations, multiple concurrent DuckDB instances can trigger native out-of-memory (OOM) conditions if system RAM is exhausted.
* **Risk**: Process crash under extreme concurrent analytical load.
* **Recommendation**: Enforce a global analytical execution semaphore (`-Dduckdb.max.concurrent.queries=N`) and configure DuckDB `SET max_memory` proportionally across active instances.

---

## 11. Conclusion & Verdict

The **Inspecto platform** demonstrates an **exceptionally mature, robust, and thoughtful software architecture**. Its core architectural decisions—framework-free Java runtime, embedded DuckDB execution, virtual-thread concurrency, immutable Consignment data semantics, and build-flavor edition packaging—deliver unmatched efficiency, minimal operational footprint, and high developer velocity.

By executing the targeted recommendations outlined in Section 10 (decomposing `CollectorService`, finishing branch-aware DAG execution, unifying identity semantics, and formalizing the HTTP middleware pipeline), Inspecto will solidify its position as a world-class, cloud-native, and edge-deployable data processing platform.

---
*Report generated by Antigravity AI Architecture Specialist — Inspecto System Review.*
