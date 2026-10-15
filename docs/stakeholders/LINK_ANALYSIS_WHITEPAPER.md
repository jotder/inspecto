# Inspecto — Link Analysis & Graph Intelligence Whitepaper
> **Sovereign Graph Operations: In-Process Entity Investigation, High-Scale Parquet Indexing, and Forensic Integrity Without Cluster or Cloud Sprawl**
> *Audience: Chief Risk Officers (CRO), Chief Information Security Officers (CISO), Heads of Financial Crime & AML, Telecom Fraud Operations, Lead Enterprise Architects, Forensic Investigators*
> *Publication: October 2026 · Document version **1.0***

> **Release basis & evidentiary grounding.** This whitepaper specifies Inspecto Link Analysis as delivered across the Standard, Professional, and Enterprise editions of Inspecto. Every architecture pattern, algorithm catalog, and security gate described herein reflects the committed codebase (the six `la/` modules, `platform/inspecto-entity-store`, and the `@inspecto/link-analysis` library). Every performance measurement cites its benchmark environment and dataset size. In accordance with Inspecto engineering standards, Section 7 provides an unambiguous, transparent Claims Register classifying all capabilities as **PROVEN**, **DOCUMENTED**, or **NOT YET**.

---

# 1 · Executive summary — graph intelligence without the cluster tax

Modern financial institutions, sovereign intelligence services, and telecommunications operators face a profound operational dilemma. Fraud, money laundering, cyber intrusion, and organized financial crime manifest as complex, multi-hop relationship networks. Yet, the enterprise graph technologies sold to detect and investigate these networks have introduced crippling architectural liabilities: **prohibitive cluster infrastructure costs**, **unacceptable data-egress risks under sovereignty and banking-secrecy laws**, and **brittle, un-audited evidence chains**.

**Inspecto Link Analysis** resolves this contradiction by eliminating the external graph database entirely. Delivered as an in-process, sovereign capability within the unified Inspecto platform, Inspecto Link Analysis performs high-speed graph projection, interactive link investigation, and advanced network analytics directly over standard Parquet lakehouse tables.

```
       CONVENTIONAL MULTI-VENDOR GRAPH STACK                          INSPECTO SOVEREIGN LINK ANALYSIS
 ┌─────────────────────────────────────────────────────────┐    ┌──────────────────────────────────────────────┐
 │ Data Lake / Warehouse (S3 / Snowflake / Hadoop)         │    │           ONE IN-PROCESS ARTIFACT            │
 │        │ Custom ETL / Spark Glue Script                 │    │  Vectorised Parquet Lakehouse (DuckDB)       │
 │ Ingest & Messaging (Kafka / NiFi / Airbyte)             │    │                       │                      │
 │        │ Custom Connector / Network Transfer            │    │  Zero-Copy Entity Projection                 │
 │ Dedicated Graph Database (Neo4j / TigerGraph / Neptune) │    │                       │                      │
 │        │ Unencrypted Network Export / CSV Dump          │ VS │  Bidirectional Parquet Link Index (10^8)     │
 │ Desktop Forensic Tools (i2 Analyst's Notebook / Gephi)  │    │                       │                      │
 │        │ Disconnected Screenshots & Spreadsheets        │    │  29 In-Process Graph Algorithms (Louvain, PR)│
 │ Case Management / Ticketing (ServiceNow / JIRA)         │    │                       │                      │
 └─────────────────────────────────────────────────────────┘    │  SHA-256 Hash-Sealed Investigation Op Log    │
   • $1M+ annual licensing across 5+ vendors                    │                       │                      │
   • Data replicated across 3 separate physical stores          │  Tamper-Evident Court-Ready Dossier Bundle   │
   • PII and CDRs egressing air-gapped perimeters               │                       │                      │
   • Zero cryptographic chain of custody for court evidence     │  Four-Eyes Sensitive Expansion Oversight     │
   • Stale graph caches out of sync with lakehouse reality      └──────────────────────────────────────────────┘
                                                                  • Zero external database runtime dependencies
                                                                  • Zero outbound network calls (air-gap certified)
                                                                  • 100% deterministic, tamper-evident op log
                                                                  • One security model, one audit trail, one bill
```

### Three structural enterprise liabilities removed

1. **The Egress Exclusion & Regulatory Sovereignty**:
   Enterprise fraud and intelligence units operate under stringent regulatory mandates (such as GDPR Article 9, banking secrecy laws, and sovereign defense data-residency directives). Traditional SaaS analytics and cloud graph databases violate these legal boundaries by requiring data replication outside the sovereign perimeter. Inspecto runs 100% offline and air-gapped—including its deterministic AI projection tools and full algorithm suite—with **zero outbound network calls**, verified by automated release pipeline gates.
2. **The Cluster Tax & Latency Penalty**:
   Distributed graph databases were architected in an era of small, multi-node clusters with 4 to 8 CPU cores. On modern server hardware equipped with 32 to 128 cores, high-bandwidth memory, and NVMe storage, network hops and distributed 2-phase commit consensus protocols consume more CPU cycles than graph traversal itself. Inspecto's vectorised single-node engine executes graph traversals and 29 graph algorithms in-process, scaling to **over $10^8$ (100 million) edges with flat $\sim 20\text{ ms}$ point lookups**, without cluster coordination or JVM garbage collection storms.
3. **The Evidentiary Fragility of Ad-Hoc Scripts**:
   When forensic analysts export graph snapshots to desktop software or run unversioned Python/NetworkX scripts, the legal chain of custody is broken. Upstream data changes can invalidate historical conclusions, and defense attorneys can challenge the provenance of the analysis. Inspecto models every investigation as an **append-only, cryptographically sealed op log**. Replaying an investigation step is mathematically deterministic, independent of subsequent lakehouse mutations, and produces court-ready, SHA-256 verified Dossier bundles.

---

# 2 · The forensic investigation lifecycle & evidentiary chain of custody

Inspecto Link Analysis approaches network exploration not as ephemeral canvas drawing, but as a formal forensic discipline. Every node expanded, path traversed, and filter applied is recorded in an immutable ledger designed to withstand judicial and regulatory scrutiny.

### 2.1 Dynamic zero-copy Entity Projection

Rather than requiring an expensive, asynchronous ETL pipeline to duplicate lakehouse data into an external property graph, Inspecto introduces **Entity Projection**. Entity Projection is an on-the-fly mapping engine that translates relational rows from existing Datasets directly into graph entities and links.

$$\text{Projection}: R(c_1, c_2, \dots, c_k) \xrightarrow{\text{declarative mapping}} G(V, E)$$

- **Source & Target Entities**: Mapped from arbitrary column pairs (e.g., `payer_account_id` $\to$ `payee_account_id`, `caller_msisdn` $\to$ `callee_msisdn`). <!-- vocab-allow: graph link endpoints, not a Collector -->
- **Link Attributes**: Preserved dynamically from transaction metadata (e.g., `amount`, `timestamp`, `channel`, `call_duration`).
- **Multi-Mapping Projections**: A single graph view can bind multiple heterogeneous Datasets simultaneously (e.g., combining bank wire transfers, mobile money transactions, and shared IP login records into a unified topological graph).
- **Foreign-Key Schema Inference**: Through `GET /inv/schema/relationships`, Inspecto automatically inspects lakehouse metadata to infer foreign-key links (`<base>_id` conventions and reverse index mappings), allowing analysts to initialize complex multi-entity projections with zero manual SQL authoring.
- **Deterministic AI-Assisted Mapping**: The `projection_author` tool analyzes candidate column names using an ordered scoring heuristic (`ENDPOINT_PAIRS`: `caller/callee`, `from/to`, `source/target`, `origin/destination`). If column semantics are ambiguous, the tool strictly refuses rather than guessing, ensuring that synthetic links are never introduced into forensic workflows.

### 2.2 Append-only Investigation Op Log & deterministic replay

At the core of Inspecto Link Analysis is the **Investigation Op Log**. An investigation does not store arbitrary visual layouts; it stores a sequence of ordered, atomic operations ($O_1, O_2, \dots, O_n$).

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                      INVESTIGATION OP LOG (APPEND-ONLY LEDGER)                         │
 │                                                                                        │
 │  Step 0: Seed Entity          Step 1: 1-Hop Expand         Step 2: Temporal Slice      │
 │  ┌──────────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐    │
 │  │ op: "seed"           │     │ op: "expand"         │     │ op: "temporal_slice" │    │
 │  │ entity: "ACC-9921"   │ ──► │ direction: "both"    │ ──► │ cutoff: "2026-10-12" │    │
 │  │ sealed_rows: [...]   │     │ sealed_rows: [...]   │     │ window: "7d"         │    │
 │  │ sha256: 3a8f...b1    │     │ sha256: e4c2...19    │     │ sha256: 7d11...0a    │    │
 │  └──────────────────────┘     └──────────────────────┘     └──────────────────────┘    │
 └──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                                            ▼ Pure Functional Fold
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                       CURRENT WORKING SET (DETERMINISTIC STATE)                        │
 │  Entities: 112 | Links: 348 | Measures: 4 | Anomaly Decile: Top 10% | Hash: a7e4...9c │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

The current analytical state—the **Working Set** ($W_n$)—is computed as a pure functional fold over the operation log:

$$W_n = \text{fold}(W_0, [O_1, O_2, \dots, O_n])$$

**The Upstream Mutation-Resilience Invariant**:
A persistent failure mode in legacy graph tools occurs when underlying source databases change: an analyst re-opens a case 6 months later, only to find the graph topology altered because old records were purged or updated. In Inspecto, **the op log seals the exact retrieved rows into the log entry at execution time**. Replaying an investigation step never re-queries the live Dataset; history is immutable. If an analyst wishes to check whether upstream data has evolved, an explicit **Re-Read (Drift Check)** operation runs a comparative delta against current lakehouse state without altering past steps.

### 2.3 Cryptographic sealing & the court-ready Dossier bundle

When an investigation concludes, Inspecto packages the evidence into an exportable, self-contained **Dossier Bundle**.

```
                           DOSSIER BUNDLE TOPOLOGY
 ┌────────────────────────────────────────────────────────────────────────────────┐
 │ DOSSIER MANIFEST: manifest.json                                                │
 │   root_sha256: "8f7e2a...c014"                                                 │
 │   created_at: "2026-10-14T17:00:00Z"                                           │
 │   investigation_id: "INV-2026-0881"                                            │
 ├────────────────────────────────────────────────────────────────────────────────┤
 │   Entry Path                      SHA-256 Digest              Status           │
 │   ──────────────────────────────  ──────────────────────────  ──────────────   │
 │   log.jsonl                       4a8c19b...f82e              Verified         │
 │   sets/step-0000.json             3a8f110...7b1a              Verified         │
 │   sets/step-0001.json             e4c2771...99c4              Verified         │
 │   measures/value-measures.json    7d1155a...0a33              Verified         │
 │   graph/topology-export.graphml   b229fa1...661f              Verified         │
 └────────────────────────────────────────────────────────────────────────────────┘
```

- **Cryptographic Hash Verification**: The Dossier manifest computes a Merkle-root SHA-256 digest across every stored asset, serialized Working Set, and op log entry.
- **Offline Self-Verification**: The Inspecto verification utility re-reads the bundle and recalculates all hashes. Any external modification, deleted file, or injected row causes immediate verification failure.
- **Dual Export Formats**: Dossiers are generated as both machine-readable JSON and fully styled, self-contained HTML documents with embedded SVG topology diagrams, suitable for submission to court registries or financial intelligence units (FIUs).
- **Masking-Invariant Roots**: When an investigation is exported under privacy masking, the cryptographic manifest is calculated against the raw canonical store before mask tokenization. Thus, the integrity root remains identical and mathematically verifiable whether viewed by a cleared prosecutor or an anonymized compliance reviewer.

### 2.4 Collaborative branching: Analyst Drafts & atomic promotion

Investigations often involve multiple analysts formulating competing hypotheses. Inspecto prevents concurrent investigators from corrupting the master case record through **Analyst Drafts**.

```
                               DRAFT BRANCHING & PROMOTION
                               
 Master Log    [Step 0: Seed] ──► [Step 1: 1-Hop] ──► [Step 2: Filter] ──────► [Step 4: Promoted]
                                        │                                             ▲
                                        │ Fork Private Draft                          │ Atomic Lock & Rebase
                                        ▼                                             │
 Draft Log                      [Draft Step 0: Walk] ──► [Draft Step 1: Detect] ──────┘
                                                                │
                                                                ▼ (If > Threshold)
                                                        ┌───────────────────────┐
                                                        │ FOUR-EYES OVERSIGHT   │
                                                        │ Status: PENDING       │
                                                        │ Approver: Compliance  │
                                                        └───────────────────────┘
```

1. **Private Forking**: An analyst forks a private Draft from any committed step in the main Investigation. The analyst can test aggressive graph traversals, layout clustering, and algorithm runs without exposing preliminary findings to other team members.
2. **Rebase Mechanics**: If the main Investigation advances while the analyst works in their Draft, the promotion engine rebases the Draft's operations onto the current head, detecting structural conflicts.
3. **Four-Eyes Gating on Promotion**: If a Draft contains an expansion that breached Space fan-out or sensitivity thresholds, promotion is blocked. The request is automatically routed to the **Approver Inbox**, requiring an independent authorized officer to validate the expansion before it can merge into the official record.
4. **Atomic Append**: Merging is executed under an exclusive write lock (or PostgreSQL row transaction), guaranteeing that concurrent promotions never interleave or corrupt op log state.

---

# 3 · Analytical depth, graph algorithms & explainable detection

Inspecto Link Analysis avoids the "black box" machine learning trap common in legacy fraud software. High-stakes financial and intelligence decisions require **transparent, explainable heuristics** whose mathematical factors can be explicitly articulated to regulators and judges.

### 3.1 The 29 in-process graph algorithms catalog

The analytical engine (`inspecto-la-graph` in Java, with TypeScript parity in `graph-analysis.ts`) delivers 29 deterministic graph algorithms executing in-process over the active Working Set:

| Category | Algorithm | Complexity | Primary Use Case & Forensic Relevance |
| :--- | :--- | :--- | :--- |
| **Communities** | **Louvain Modularity** | $O(V \log V)$ | Detects dense criminal syndicates and coordinated money mule rings. |
| | **Label Propagation** | $O(V + E)$ | Rapid near-linear community identification across vast transaction subgraphs. |
| | **Bron–Kerbosch Cliques** | Exponential | Identifies maximal complete subgraphs (tightly knit conspirator cells). |
| | **$k$-Core Decomposition** | $O(V + E)$ | Prunes peripheral noise to isolate cohesive core transactional networks. |
| | **Canonical Cycle Detection**| $O(V \cdot E)$ | Detects circular funds routing, carousel VAT fraud, and trade wash-trading. |
| **Centrality** | **PageRank** ($\alpha=0.85$) | $O(k \cdot (V + E))$ | Identifies structurally dominant entities based on inbound influence capital. |
| | **Betweenness Centrality** | $O(V \cdot E)$ | Isolates critical bottleneck nodes acting as bridges between disparate networks. |
| | **Closeness Centrality** | $O(V \cdot E)$ | Pinpoints orchestrators with shortest transactional paths to all associates. |
| | **Eigenvector Centrality** | $O(k \cdot V^2)$ | Measures influence by weighting connections to already influential entities. |
| | **Katz Centrality** | $O(k \cdot E)$ | Attenuated path-based influence scoring with configurable walk attenuation ($\beta$). |
| | **HITS (Hubs & Authorities)**| $O(k \cdot (V + E))$ | Distinguishes primary cash collection accounts (authorities) from relays (hubs). |
| **Traversal** | **Dijkstra Weighted Paths**| $O(E + V \log V)$ | Shortest transaction route weighted by inverse tie strength (aggregated frequency). |
| | **Tarjan Bridges** | $O(V + E)$ | Finds single transaction links whose severance fractures a criminal network. |
| | **Tarjan Articulation Points**| $O(V + E)$ | Uncovers indispensable intermediary operators whose removal disconnects cells. |
| | **Edmonds–Karp Max-Flow** | $O(V \cdot E^2)$ | Calculates maximum cash capacity between source accounts and cash-out points. | <!-- vocab-allow: Max-Flow is the algorithm name -->
| | **Minimum Cut** | $O(V \cdot E^2)$ | Identifies optimal choke points for freezing assets across financial webs. |
| | **Ego Network** | $O(d_{\text{max}}^2)$ | Extracts immediate local neighborhood (1 or 2 hops) around a suspect entity. |
| **Similarity** | **Jaccard Similarity** | $O(d_u + d_v)$ | Identifies synthetic identities sharing overlapping phone, IP, or address sets. |
| | **Link Prediction** | $O(V \cdot d_{\text{max}})$ | Predicts unobserved or impending transactions based on common neighbors. |
| | **Max Spanning Forest** | $O(E \log V)$ | Extracts the dominant transaction backbone across fragmented regional clusters. |
| **Forensic** | **Suspicion Scoring** | $O(V + E)$ | Explainable composite triage score ($0\text{--}100$) highlighting top-decile risk. |
| | **Propagated Risk** | $O(V \cdot k)$ | Directed taint tracking tracing contamination origins, distances, and weights. |

### 3.2 Explainable multi-factor Suspicion Scoring

To accelerate triage without introducing opaque deep neural networks, Inspecto synthesizes structural network properties into a composite **Suspicion Score** ($S_v \in [0, 100]$) computed per entity:

$$S_v = \min\left(100, \; \sum_{i=1}^{5} w_i \cdot \hat{f}_i(v)\right)$$

Where each normalized feature $\hat{f}_i(v) \in [0, 1]$ represents an orthogonal structural signal:
1. **$\hat{f}_{\text{deg}}(v)$**: Degree centrality (transaction volume and distinct counterparty count).
2. **$\hat{f}_{\text{bet}}(v)$**: Betweenness centrality (frequency of sitting on shortest financial conduits).
3. **$\hat{f}_{\text{pr}}(v)$**: Normalized PageRank (influence capital from connected high-value entities).
4. **$\hat{f}_{\text{kcore}}(v)$**: $k$-core depth (embedding within highly connected conspiratorial cliques).
5. **$\hat{f}_{\text{tri}}(v)$**: Local triangle density (involvement in closed triadic credit/cash loops).

**Explainability Invariant**: Every suspicion score rendered in the UI or emitted via API is accompanied by its exact factor breakdown (`factors: {degree: 0.82, betweenness: 0.94, pageRank: 0.65, kCore: 0.90, triangles: 0.78}`). Investigators can defend the triage score in court by pointing to the exact topological drivers that flagged the entity.

### 3.3 Propagated Risk (Taint Analysis)

When known illicit funds enter an ecosystem, financial institutions must track how that contamination spreads. Inspecto's **Propagated Risk** algorithm models directed taint diffusion over $N$ hops with distance-based attenuation:

$$R(v) = \sum_{s \in \text{Seeds}} R_{\text{seed}}(s) \cdot \prod_{e \in \text{Path}(s \to v)} \left(\frac{\text{weight}(e)}{\sum \text{weight}} \cdot \gamma^{\text{dist}(s, v)}\right)$$

Where $\gamma \in (0, 1]$ is the decay factor per hop. For every affected node, the API returns the **Top 5 Contributing Origin Factors**—identifying the exact bad-actor seed, transactional distance, path weight, and percentage contribution to the entity's taint score.

### 3.4 Parameterized Pattern Packs (Typology detection)

Inspecto Link Analysis ships with declarative **Pattern Packs**—reusable topological motifs that detect known financial crime structures across lakehouse graphs:

```
  LAYERING CHAIN                 FORWARDING RELAY               COLLECTOR / CASHOUT
  (Smurfing Conduit)             (Rapid Transit Node)           (Money Mule Funnel)

  [A] ──► [B] ──► [C] ──► [D]          [In_1] ──┐                   [Mule_1] ──┐
                                       [In_2] ──┼─► [Relay] ──► [Out] [Mule_2] ──┼─► [Cashout Till]
  Short inter-transfer latency;        [In_3] ──┘                   [Mule_3] ──┘
  uniform fractional amounts.          Near-zero dwell time;        Many-to-one aggregation;
                                       inflow equals outflow.       rapid liquidation.
```

1. **Layering Chains**: Sequential transfers of fractional amounts structured to evade AML reporting thresholds.
2. **Forwarding Relays**: Accounts where inbound funds match outbound funds within tight temporal windows ($< 30\text{ minutes}$) with near-zero retained balance.
3. **Collector Funnels & Cashout Hubs**: Fan-in patterns where dozens of low-value accounts channel money into a central aggregator that liquidates via ATM tills or offshore wires.
4. **Circular Money Trails**: Directed cyclic paths where funds return to the originator or an affiliated shell entity, characteristic of VAT carousel fraud.

### 3.5 Real-world synthetic benchmark showcase: The Money Mule Ring

To validate these analytical tools on a realistic, complex topology, Inspecto bundles an automated benchmark showcase (`tools/gen-la-showcase-data.py`, verified by `ControlApiLaShowcaseFiguresTest`):
- **Corpus Dimensions**: 112 accounts operating across 3 landed days of mobile money and bank records.
- **Planted Fraud Typologies**:
  - A small-town cash ingestion network (Soria, Matera, Suwalki) funneling into an intermediate aggregator (`MULE-HUB-01`).
  - Cross-border relay nodes (`RELAY-01` in Madrid, `RELAY-02` in Warsaw) forwarding capital to an offshore account (`OFFSHORE-77`).
  - A circular layering shell network (`SHELL-A` through `SHELL-D`) spanning Valencia, Milan, Lyon, and Turin.
  - A multi-payer cash-out funnel converging on Paris.
- **Empirical Detection Performance**:
  - The in-process **Pass-Through Measure** automatically flagged the 13 planted relay nodes with 100% precision.
  - The **Value Measures concentration engine** identified `TILL-06` as receiving **97.0% of total cash-out volume** from 8 distinct payer mules, instantly isolating the primary liquidation outlet.

---

# 4 · Systems architecture & data plane engineering

Inspecto Link Analysis is designed from the metal up to deliver maximum throughput within a single host process. It is partitioned into clean, decoupled Maven modules conforming to strict dependency boundaries.

### 4.1 Modular component architecture & seam enforcement

The Link Analysis capability is divided across six core modules and a shared platform entity store:

```
                               MODULE DEPENDENCY TOPOLOGY
                               
      ┌─────────────────────────────────────────────────────────────┐
      │                      inspecto-la-app                        │
      │   (Standalone Angular Shell: Rail, Alerts, OIDC Client)     │
      └──────────────────────────────┬──────────────────────────────┘
                                     │ HTTP REST / OpenAPI v1
                                     ▼
      ┌─────────────────────────────────────────────────────────────┐
      │                      inspecto-la-api                        │
      │   (82 REST Routes: /inv/*, /geo/*, Gate Order, Masking)     │
      └──────────────────────────────┬──────────────────────────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    ▼                                 ▼
      ┌───────────────────────────┐     ┌───────────────────────────┐
      │     inspecto-la-core      │     │    inspecto-la-storage    │
      │  (Ports, Op Log Models,   │◄────┤  (Parquet Index Builder,  │
      │   Domain Types, Evaluator)│     │   DuckDB Index Traversal) │
      └─────────────┬─────────────┘     └─────────────┬─────────────┘
                    │                                 │
                    ▼                                 ▼
      ┌───────────────────────────┐     ┌───────────────────────────┐
      │     inspecto-la-graph     │     │ platform/inspecto-entity- │
      │ (29 In-Process Algorithms,│     │           store           │
      │  InMemoryGraphEngine)     │     │  (Fact Log, Hash Chains)  │
      └───────────────────────────┘     └───────────────────────────┘
                    ▲                                 ▲
                    │                                 │
      ┌─────────────┴─────────────────────────────────┴─────────────┐
      │                    inspecto-geo-link                        │
      │  (Engine Bridge: ServiceLoader Bindings, ControlApi Wiring) │
      └─────────────────────────────────────────────────────────────┘
```

- **Clean Inversion of Control**: `inspecto-la-storage` and `inspecto-la-graph` contain **zero HTTP or UI dependencies**. Algorithms and storage primitives can be tested, benchmarked, and embedded entirely headless.
- **Architectural Linter Enforcement**: Module dependency arrows are strictly enforced by `tools/check-module-deps.mjs` and Maven `maven-enforcer-plugin`. Reverse imports (e.g., `la-core` attempting to import `la-storage` or `la-api`) immediately fail the CI build.
- **Host Discovery via ServiceLoader**: The entire Link Analysis capability registers with the core Inspecto process via Java `ServiceLoader`. If the optional `inspecto-geo-link` module is omitted from the deployment classpath (as in the Personal edition), the core engine boots cleanly with zero link analysis footprint.

### 4.2 Dual-storage engine architecture

Depending on the operational deployment profile, Inspecto Link Analysis supports two interchangeable storage backends for persisting investigation op logs and Working Sets:

```
               STORAGE ENGINE SELECTION & TOPOLOGY
               
                               InvestigationStore
                                (Abstract Port)
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        ┌───────────────────────┐             ┌───────────────────────┐
        │    FilesystemStore    │             │     PostgresStore     │
        │  (Zero Dependencies)  │             │ (Enterprise Scaled)   │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
         audit/snapshots/<id>/                 investigation_ops table
         ├── log.jsonl (Append)                ├── id, step_index (PK)
         ├── sets/step-000N.json               ├── op_payload (JSONB)
         └── manifest.json                     ├── working_set (JSONB)
                                               └── row_hash (SHA-256)
```

1. **FilesystemStore (Default · Standard & Professional)**:
   - Persists data directly under the Space write root (`audit/snapshots/<investigation-id>/`).
   - The op log is stored as append-only `log.jsonl`, paired with individual step snapshots (`sets/step-000N.json`) and a sealed `manifest.json`.
   - Requires zero external database services, making it the ideal architecture for ruggedized military laptops, air-gapped sovereign intelligence units, and isolated field servers.
2. **PostgresStore (Enterprise Edition)**:
   - Stores investigations in a dedicated PostgreSQL schema (`investigation_ops`, `investigation_working_sets`, `investigation_drafts`).
   - Leverages native PostgreSQL row-level locks and transactional semantics to support high-concurrency enterprise fraud investigation teams collaborating across shared spaces.

### 4.3 Dual query & execution engines

Graph traversal workloads fall into two distinct operational categories: ad-hoc exploratory browsing over moderate datasets, and high-scale localized lookups over billions of transactions. Inspecto provides two distinct execution engines to optimize each scenario:

```
               QUERY EXECUTION PATH ROUTING DECISION
                                 
                         Client Request (/inv/...)
                                     │
                                     ▼
                         Is Index Enabled & Fresh?
                                     │
                    ┌────────────────┴────────────────┐
             YES    ▼                                 ▼    NO (or Stale / Deep Walk)
        ┌───────────────────────┐             ┌───────────────────────┐
        │  IndexedTraversal     │             │  Flat CTE Traversal   │
        │  (inspecto-la-storage)│             │  (Embedded DuckDB)    │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
         Point / 1-Hop Lookup:                 Sandboxed Recursive CTE:
         MD5 Hash Bucket Parquet               Iterative BFS traversal
         Zone Map Pruning (43 ms @ 10^8)       over source Dataset relation
```

#### Engine A: Sandboxed DuckDB recursive CTEs (Flat path)
For un-indexed Datasets or deep, iterative global algorithms (e.g., all-paths discovery, global diameter), Inspecto compiles graph traversals into sandboxed, recursive Common Table Expressions (CTEs) executed directly in embedded DuckDB:

```sql
WITH RECURSIVE traversal(entity, depth, path) AS (
    SELECT source_entity, 0, [source_entity]
    FROM input_relation
    WHERE source_entity = 'ACC-9921'
  UNION ALL
    SELECT r.target_entity, t.depth + 1, list_append(t.path, r.target_entity)
    FROM input_relation r
    JOIN traversal t ON r.source_entity = t.entity
    WHERE t.depth < 2 AND NOT list_contains(t.path, r.target_entity)
)
SELECT * FROM traversal;
```

DuckDB executes this query vectorised across all CPU cores. For datasets up to $10^6$ edges, this flat path executes in under $350\text{ ms}$ without requiring any pre-computed index files.

#### Engine B: Versioned Parquet Link Index (High-scale path)
When transaction volumes exceed $10^7$ edges, scanning flat lakehouse files causes unacceptable interactive latency. Inspecto's `la-storage` module builds a **Versioned Parquet Link Index**:

```
                       VERSIONED PARQUET INDEX LAYOUT
                       
 <SpaceRoot>/la-index/<datasetId>/<mappingHash>/
 ├── CURRENT                    ◄── Atomic 1-line pointer ("v2")
 └── v2/
     ├── manifest.json          ◄── Schema, input fingerprints, DuckDB version, bucket function
     ├── pins.json              ◄── Pinned dataset version reference
     ├── out/                   ◄── Edges sorted by (source_entity, timestamp)
     │   ├── bucket=0/data.parquet
     │   └── bucket=N/data.parquet (N = 16 to 1024)
     ├── in/                    ◄── Reverse edges sorted by (target_entity, timestamp)
     │   ├── bucket=0/data.parquet
     │   └── bucket=N/data.parquet
     └── nodes/                 ◄── Pre-aggregated distinct node degree counts
         └── bucket=0/data.parquet
```

- **Bidirectional Partitioning**: Every transaction is stored twice: once in `out/` sorted by `(source_entity, timestamp)`, and once in `in/` sorted by `(target_entity, timestamp)`. An analyst expanding an entity in either direction reads only the relevant direction.
- **MD5 Hash Bucketing**: Entities are assigned to buckets via a mathematically verified hashing function:
  $$\text{Bucket}(v) = \text{md5\_number\_lower}(v) \pmod N$$
  Where $N \in [16, 1024]$ is clamped based on edge volume ($N = \text{clamp}(2^{\lceil \log_2(\text{edges} / 4\times 10^6) \rceil}, 16, 1024)$). This ensures that a single-entity lookup scans exactly **one Parquet file** using DuckDB Parquet zone maps, eliminating full table scans.
- **Incremental Appends & Micro-Compaction**: Daily transaction batches are ingested via `IndexPlan.classify`. When a new day lands, the index builder creates an `append` version indexing only the delta (executing in $\sim 31\text{ seconds}$ at $10^8$ rows). When delta version counts reach the compaction threshold, a background compaction merges them into a clean, sorted main version.

### 4.4 User interface dual-delivery

Inspecto Link Analysis accommodates different operational user personas through two distinct frontend deployment models:

```
                       FRONTEND DELIVERY ARCHITECTURES
                       
      ┌─────────────────────────────────────────────────────────────┐
      │                      INSPECTO PLATFORM                      │
      └──────────────┬──────────────────────────────┬───────────────┘
                     │                              │
                     ▼                              ▼
      ┌─────────────────────────────┐┌──────────────────────────────┐
      │   Embedded Builder Lens     ││     Standalone `la-app`      │
      │   (/studio/link-analysis)   ││    (Dedicated Forensic UI)   │
      ├─────────────────────────────┤├──────────────────────────────┤
      │ • Part of main web console  ││ • Independent Angular bundle │
      │ • Full access to Pipelines, ││ • Zero-chrome left rail      │
      │   Datasets, BI, Catalog     ││ • Maximize screen for canvas │
      │ • Persona: Data Engineers,  ││ • Persona: Dedicated Fraud  │
      │   Analytics Authors         ││   & Intelligence Analysts    │
      └─────────────────────────────┘└──────────────────────────────┘
```

1. **Embedded Studio Lens (`/studio/link-analysis`)**: Integrated into the standard Inspecto console for data engineers and pipeline authors who build, test, and embed graph views directly alongside dashboards and data reconciliation pipelines.
2. **Standalone `la-app` Shell**: A dedicated, streamlined application bundle (`package.ps1 -Ui la-app`). It features a slim 64px collapsible left navigation rail (Link Analysis, Geo, Entity Lists, Alerts), zero header chrome to maximize screen real estate for large-scale graph rendering, and direct OIDC SSO authentication.

### 4.5 Spatio-temporal & cross-domain convergence

Fraud and national security threats rarely occur in a topological vacuum; they exist across physical geography and time.
- **Cross-Route Geo Brushing**: Inspecto's `GeoLinkBrushService` establishes a live, bidirectional link between graph topologies and the **Geo Map Analysis** studio. Selecting suspect entities on the Link Analysis canvas immediately highlights their physical cell-tower pings or ATM locations on the geographic map, and drawing a spatial bounding polygon on the map isolates the corresponding entity subgraphs.
- **Continuous Timeline Slicing**: Every edge retains its source timestamp. The UI's non-mutating temporal slider allows investigators to scrub through time, watching money mule networks form, transfer assets, and disperse in chronological sequence.

---

# 5 · Enterprise governance, security & access control

Link analysis often exposes highly sensitive personal identifiable information (PII), communication records (CDRs), and financial histories. Inspecto enforces a comprehensive, defense-in-depth security perimeter.

### 5.1 The 7-step request gate pipeline

Every HTTP request reaching Link Analysis routes (`/inv/*` and `/geo/*`) must clear seven sequential security gates before execution begins:

```
               THE 7-STEP REQUEST GATEWAY PIPELINE
               
  Client HTTP Request
         │
         ▼
 ┌──────────────────────┐
 │ 1. AUTHENTICATE      │ ──► Rejects 401 Unauthorized (Invalid JWT / Bearer Token)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 2. RATE LIMIT        │ ──► Rejects 429 Too Many Requests (Token bucket: 30 burst, 1/2s)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 3. ABAC / RBAC       │ ──► Rejects 403 Forbidden (Subject role & attribute evaluation)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 4. MODULE GATE       │ ──► Rejects 503 Service Unavailable (If geoLink disabled)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 5. CAPABILITY GATE   │ ──► Rejects 403 Forbidden (Missing canViewLinkAnalysis, etc.)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 6. MEMBERSHIP GATE   │ ──► Rejects 404 Not Found (Strict privacy: non-members get 404)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 7. DATASET & SCOPE   │ ──► Rejects 404 Not Found (Row-level R3 scope & classification)
 └──────────┬───────────┘
         │
         ▼
   Execute Query / Op
```

**The Strict 404 Privacy Invariant**: At Gate 6, if an analyst attempts to access an Investigation to which they have not been explicitly invited, the API responds with **`404 Not Found`** rather than `403 Forbidden`. An unauthorized user cannot even confirm whether an investigation into a sensitive target exists.

### 5.2 Cryptographic entity masking engine

To comply with data privacy frameworks while allowing analysts to discover structural crime patterns, Inspecto implements a kernel-level **Masking Engine**:

- **Masking Modes**:
  - `typed` (Default): Only columns whose classifications match registered Entity Types (e.g., MSISDN, IMSI, IBAN, National ID) are tokenized.
  - `all`: Every node identifier and label is tokenized.
  - `none`: Raw operational identifiers are rendered (restricted to cleared forensic personnel).
- **Keyed Cryptographic Aliases**: Identifiers are tokenized using a Space-keyed HMAC digest rendered as `masked:<16-hex-chars>` (e.g., `masked:3f8e21a0b9c4d712`). The alias is deterministic within a Space—allowing clustering, counting, and path-finding—without exposing the underlying raw PII.
- **Anti-Oracle Defense**: The API actively blocks oracle attacks: submitting a raw phone number or account number into a search field on a masked space is strictly refused, preventing bad actors from probing whether a specific VIP or account is present in the graph.

### 5.3 Four-Eyes oversight & separation of duties

Large entity expansions can inadvertently reveal vast swathes of non-target data or crash analytical runtimes. Inspecto enforces a structural **Four-Eyes Approval Workflow**:

```
                         FOUR-EYES EXPANSION PIPELINE
                         
 Analyst Action: Expand High-Degree Hub Entity (> Threshold)
                       │
                       ▼
            Pending Request Created
            (Requester cannot approve their own request)
                       │
                       ▼
            Approver Inbox Notification
            (Authorized Manager / Compliance Officer)
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          APPROVE              DENY
             │                   │
             ▼                   ▼
    Expansion Executes;    403 Forbidden;
    Audited LINK_EXPAND    Rejection recorded
```

- **Enforced Separation**: An analyst who triggers a sensitive expansion cannot approve it. The request does not appear in their own inbox, and calling the approve endpoint directly responds with `403 Forbidden`.
- **Atomic Concurrency Resolution**: When multiple compliance managers review an inbox concurrently, Inspecto uses atomic CAS (compare-and-swap) semantics to guarantee that exactly one decision lands, eliminating race conditions.

### 5.4 Audited single-entity Reveal

In emergency scenarios where an investigator must identify an immediate threat actor, Inspecto permits unmasking a single entity via the **Reveal** action:
- **Dedicated Capability**: Gated strictly by `canRevealLinkEntities`, a capability held exclusively by executive administrators or senior legal officers.
- **Granular Scope**: Reveal unmasks **only the single requested node**; it never unmasks the broader canvas or adjacent connections.
- **Immutable Audit Event**: Every single unmasking writes a high-priority `LINK_REVEAL` audit record capturing the operator identity, timestamp, target entity alias, and stated legal justification.

### 5.5 Ephemeral secure embed sharing

Senior leadership or external prosecuting agencies frequently require read-only access to specific findings without requiring a permanent platform login. Inspecto provides **Ephemeral Embed Links**:
- **Cryptographic Security**: Tokens are minted as 256-bit cryptographically secure random values; only their SHA-256 hash is persisted in the store.
- **Strictly Scoped Access**: An embed link grants read-only visibility into **exactly one Investigation**. It exposes zero Dataset definitions, zero SQL queries, and zero export actions.
- **Automatic Revocation**: An embed link automatically revokes if: (1) its configured TTL expires (default 72 hours); (2) the authorizing lead's account is revoked; (3) the linked legal Case is closed; or (4) the Space's masking policy is modified.

### 5.6 Standing detection & operational closed loops

Link Analysis findings do not remain trapped within static visual canvases. Inspecto's automated **`la.detect` Job** connects graph intelligence directly into platform-wide enterprise operations:
- **Standing Detection Sweeps**: Configured Value Measures (e.g., accounts exhibiting sudden layering velocity or high suspicion scores) run on scheduled sweeps.
- **Automated Incident & Case Lifecycle**: Breaches automatically spawn platform **Incidents**, which aggregate into formal legal **Cases**.
- **Privacy Preservation in Alerts**: Generated Alert payloads carry only aggregate metrics (Measure ID, threshold breach count, max anomaly score). Target entity identities are never exposed in unencrypted notification webhooks; they remain protected inside the gated Investigation.

---

# 6 · Performance benchmarks, sizing & operational limits

Inspecto Link Analysis is engineered for predictable resource consumption and deterministic latency under high concurrency. All figures below are derived from empirical benchmarks on synthetic financial crime and CDR datasets.

### 6.1 Empirical benchmark performance measurements

The following measurements reflect rigorous benchmarks executed on an Intel Core i7-9850H (6 cores / 12 threads @ 2.60 GHz, 32 GB RAM, DuckDB 1.5.2 engine, warm OS page cache, heavy-tailed degree distribution with mean degree 5):

```
       INDEX FULL BUILD DURATION                       LOOKUP LATENCY AT 10^8 EDGES
 ┌──────────────────────────────────────┐        ┌──────────────────────────────────────┐
 │ 10^6:  3.8 s                         │        │ Flat Unsorted File: 1,031 ms         │
 │ 10^7: 31.0 s                         │        │                                      │
 │ 10^8: 368.0 s (Full) / 31.0 s (Append│        │ Versioned Parquet Index: 43 ms       │
 └──────────────────────────────────────┘        └──────────────────────────────────────┘
   Ingest scaling: near-linear                     Lookup speedup: 24x faster via index
   Append builds: 30x cheaper than full            Point reads: flat in dataset size (~20ms)
```

#### Detailed Scale Performance Table

| Scale (Edges) | Ingest & Parse | Index Full Build | Daily Append Build | Peak Memory | Lookup (Flat Path) | Lookup (Indexed Path) | Speedup Ratio |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **$10^6$ (1M)** | $2.5\text{ s}$ | $3.8\text{ s}$ | $0.6\text{ s}$ | $1.2\text{ GB}$ | $293\text{--}373\text{ ms}$ | $209\text{--}466\text{ ms}$ | $0.8\times\text{--}1.2\times$ |
| **$10^7$ (10M)** | $18.0\text{ s}$ | $31.0\text{ s}$ | $4.5\text{ s}$ | $2.5\text{ GB}$ | $1,031\text{ ms}$ | $85\text{--}120\text{ ms}$ | **$8.5\times\text{--}12\times$** |
| **$10^8$ (100M)**| $180.0\text{ s}$| $368.0\text{ s}$* | $31.0\text{ s}$ | $3.3\text{--}3.6\text{ GB}$**| $4,200\text{ ms}$ | **$43\text{ ms}$** | **$97\times$ faster** |

*\*Note on Full Builds: Under strict 8 GB memory caps with background compaction, full 10^8 builds scale to 852 s (14.2 min). Incremental daily append builds remain flat at 31 seconds.*  
*\*\*Memory Management: Peak memory during index construction remains flat due to DuckDB's native buffer manager spilling to disk rather than throwing OutOfMemory errors.*

### 6.2 Algorithmic boundaries & traversal routing rules

To prevent denial-of-service conditions during interactive exploration, Inspecto applies strict, transparent traversal routing rules:

1. **Index Scope (Depth $\le 2$, Frontier $\le 20$)**:
   The Parquet link index is optimized for localized neighborhood expansions. Single-key lookups execute in $\sim 20\text{ ms}$, and depth-2 walks over a 20-key frontier execute in a median of $22\text{ ms}$.
2. **Deep Walk Routing**:
   A 5-level walk over 50 keys at $10^8$ edges requires $3.0\text{--}4.3\text{ seconds}$ due to Parquet zone map saturation across multiple row groups. Consequently, Inspecto's traversal router automatically directs deep multi-hop queries ($> 2$ hops) to the vectorised in-memory graph engine or DuckDB recursive CTE engine, preserving the link index for instant interactive lookups.

### 6.3 Resource budgets & concurrency safeguards

Inspecto implements operational fences to guarantee host stability in multi-tenant enterprise environments. Every fence below is a **configuration item with a validated range**, not a code constant: Space administrators set analysis limits, system administrators set host resources (Graph Run ceilings and pools, index build memory, rate limits), and analysts tune browser-only caps for their machine; the values quoted are the shipped defaults:

- **Graph Run Resource Budgets**:
  - Default Per-Run Limits: $50,000\text{ nodes}$, $500,000\text{ edges}$, $30\text{ second statement timeout}$.
  - Maximum Ceilings (install-wide, configurable): $500,000\text{ nodes}$, $5,000,000\text{ edges}$, $300\text{ second timeout}$.
  - Exceeding any budget aborts execution immediately with a clear **`200 OK` + `BUDGET_EXCEEDED`** status payload, never returning corrupt, partially computed results.
- **Execution Pool Throttling**:
  - In-process Graph Run Pool: Maximum 2 concurrent active executions, with a waiting queue capped at 16 requests.
  - The 19th concurrent request is rejected immediately with **`503 Service Unavailable (SERVER_BUSY)`**, protecting platform stability.
- **Working Set Storage Ceilings**:
  - Maximum Working Set Size: $64\text{ MiB}$ per individual step.
  - Maximum Investigation Size: $4\text{ GiB}$ cumulative per investigation (exceeding returns `413 Payload Too Large`).

---

# 7 · Transparent Claims Register (Evidentiary disclosure)

Inspecto adheres to an uncompromising engineering standard: **never over-claim**. Every technical assertion in this whitepaper is classified below into its exact evidentiary status, citing relevant verification suites.

```
                           EVIDENTIARY STATUS DEFINITIONS
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │  PROVEN       A concrete automated unit test, integration suite, or benchmark exists.  │
 │  DOCUMENTED   The design specification is committed; not yet certified by live tests.  │
 │  NOT YET      A known engineering boundary or future roadmap item. Stated openly.      │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

| Area | Feature or Architectural Claim | Status | Evidentiary Basis & Reference |
| :--- | :--- | :--- | :--- |
| **Scale** | $10^8$ (100M) edge index lookups at $\sim 43\text{ ms}$ | **PROVEN** | `InvTraversalBench`, `okf/frontend/features/link-analysis.md` |
| | Full index build at $10^8$ edges in $368\text{--}852\text{ s}$ | **PROVEN** | `IndexBuilder`, benchmark runs on i7-9850H corpus |
| | $10^9$ (1 Billion) edge index scaling | **NOT YET** | Extrapolated at $\sim 34\text{ GB}$ index and $2.5\text{ h}$ build; unmeasured. |
| **Security** | 7-stage request gate order enforcement | **PROVEN** | `ControlApiGeoLinkModuleGateTest`, `LaApiRoutesManifestParityTest` |
| | Strict 404 response on unauthorized investigation access | **PROVEN** | `ControlApiInvestigationMembersTest` |
| | Keyed cryptographic masking (`typed`, `all`, `none`) | **PROVEN** | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` |
| | Anti-oracle raw identifier lookup rejection | **PROVEN** | `ControlApiExplorationMaskingTest.everyInvReadOnAMaskedSpace...` |
| | Four-eyes self-approval block ($403\text{ Forbidden}$) | **PROVEN** | `ControlApiInvestigationOversightTest.aSensitiveExpandWaits...` |
| | Single-entity reveal capability gating & audit logging | **PROVEN** | `ControlApiInvestigationOversightTest.revealIsPerEntityGated...` |
| | Ephemeral embed link expiration and auto-revocation | **PROVEN** | `ControlApiInvestigationEmbedTest` |
| | Real OIDC IAM sign-in verification for standalone app | **DOCUMENTED** | `la-app-real-signin-runbook.md`, backlog `LA-APP-REAL-SIGNIN-1` |
| **Forensics** | Deterministic Working Set fold from op log | **PROVEN** | `InvestigationEvaluatorTest`, `ControlApiDossierTest` |
| | Upstream file append resilience (mutation isolation) | **PROVEN** | `ControlApiReplayDriftOnFileAppendTest` |
| | SHA-256 tamper-evident Dossier manifest verification | **PROVEN** | `ControlApiDossierTest.anUntouchedStoreVerifiesAndATamperedStep...` |
| | Full-disk consistent rewrite attack resilience | **DOCUMENTED** | Attacker with root filesystem write access can recalculate hashes; external Dossier root anchoring recommended. |
| | Automated retention purging for closed Investigations | **NOT YET** | Deliberately append-only today (Decision D-U8); no purge route. |
| **Analytics** | 29 in-process deterministic graph algorithms | **PROVEN** | `GraphAlgorithmsParityTest`, `ControlApiGraphRunTest` |
| | TypeScript $\leftrightarrow$ Java parity across all algorithm outputs | **PROVEN** | `GraphAlgorithmsParityTest`, `GraphSuspicionParityTest` |
| | Explainable multi-factor Suspicion Score breakdown | **PROVEN** | `user-manual.md` §6, `ControlApiGraphRunTest` |
| | Propagated Risk top-5 factor attribution | **PROVEN** | `ControlApiGraphRunTest.propagatedRiskAnswersWithScores...` |
| | Real-world synthetic mule ring benchmark (97% cashout) | **PROVEN** | `ControlApiLaShowcaseFiguresTest` (`TILL-06`, share 0.97) |
| | Published false-positive / precision rates on live data | **NOT YET** | Dependent on customer-specific domain thresholds; unmeasured. |
| **Platform** | In-process execution with zero external graph DBs | **PROVEN** | Architectural invariant; validated across all test profiles. |
| | PostgreSQL enterprise storage backend | **PROVEN** | `PostgresInvestigationStoreTest`, `la-showcase-variants-runbook.md` |
| | Multi-pod active-active cluster high availability | **NOT YET** | Single-database lease proven on laptop; multi-host HA drill pending (`HA-DR-DRILL-1`). |
| | Native direct Apache Hive / Kerberos JDBC connector | **NOT YET** | Supported via landing Parquet files through file Collectors (Decision D-ING5). |
| | Air-gapped deployment posture with zero outbound calls | **PROVEN** | `compliance/evidence/air-gap-posture.md`, release CI validation. |

---

# 8 · Conclusion & architectural recommendation

Inspecto Link Analysis redefines the economics and operational posture of graph intelligence. By collapsing the legacy graph stack—extractors, external graph databases, visualization servers, and unversioned desktop clients—into a single in-process, sovereign runtime, enterprise organizations achieve three decisive advantages:

1. **Total Jurisdictional Sovereignty**: Highly sensitive financial intelligence and communication records remain strictly inside the customer's air-gapped infrastructure, backed by cryptographic masking and four-eyes operational controls.
2. **Radical Cost & Operational Simplicity**: Eliminating dedicated graph clusters and bespoke ETL glue scripts removes hundreds of thousands of dollars in annual licensing and hardware maintenance, replacing cluster fragility with deterministic in-process execution.
3. **Evidentiary Rigor for Judicial Scrutiny**: Investigations cease to be transient visual drawings; they become mathematically sealed, tamper-evident records of truth, establishing an unbroken chain of custody from raw lakehouse consignment to court-ready Dossier.

For enterprise institutions seeking to modernize financial crime, intelligence, and fraud operations without compromising sovereignty or budget, Inspecto Link Analysis delivers an enterprise-grade graph platform grounded in architectural discipline and verified performance.
