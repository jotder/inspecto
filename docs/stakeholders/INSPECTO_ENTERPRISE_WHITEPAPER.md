# Inspecto — Enterprise Product & Solutions Whitepaper
> **Sovereign Data Operations: the end-to-end platform for environments where data cannot leave**
> *Audience: CIOs, CTOs, Enterprise Data Architects, Heads of Revenue Assurance and Fraud, Compliance Officers*
> *Publication: September 2026 · Document version **1.2***

> **Release basis.** This edition describes Inspecto **at the close of Sprint 8** — the Standard bundle as
> shipped on 2026-09-10 plus the seven Sprint 8 rows (`EVENTS-DURABLE-1`, `AIRGAP-EXTENSIONS-1`,
> `DEPLOY-SERVICE-WRAPPER-1`, `BREAK-INCIDENT-1`, `BREAK-AGING-1`, `INCIDENT-KPI-MTTR-1`,
> `SIGNAL-STALE-TILES-1`), the scale-out plan's phases A and B (the Standard DR tier), and the `PKG-5`
> decision that bundles the embedded assistant. Two items are described as **signed design**, not shipped
> code, and are marked where they appear: the Enterprise Kubernetes tier (T5) and the Postgres query
> surface. Before any external use, confirm against `BACKLOG.md` §0 that this basis holds. Every count in
> this document is derived from the committed contracts by `tools/check-doc-counts.mjs`; every measured
> figure cites its benchmark; the claims register it is written to is `COMPETITIVE_LANDSCAPE.md` §4.

---

# 1 · Executive summary — one artifact, the whole operation

**Inspecto** is a lean, configuration-driven platform for **data acquisition, reconciliation, business
intelligence, forensic investigation and operational incident management**, delivered as **one ~90 MB
self-contained artifact**. It runs on a laptop, an air-gapped bare-metal server, or a container — with
**zero external runtime services** in its Personal and Standard forms, and with exactly two declared
dependencies (PostgreSQL and S3-compatible object storage) at Enterprise scale.

It embeds a vectorised columnar engine (DuckDB) over an open Parquet lakehouse, and collapses the tool
categories a regulated enterprise otherwise buys, integrates and secures separately:

```
             WHAT A REGULATED ESTATE USUALLY RUNS                         WHAT INSPECTO SHIPS
 ┌──────────────────────────────────────────────────────┐    ┌────────────────────────────────────────┐
 │ Ingest (NiFi / Airbyte)  ──►  Storage (Hadoop / S3)   │    │            ONE ~90 MB ARTIFACT          │
 │        │ glue script            │ glue script          │    │  Acquire ► Parse ► Lakehouse ► Reconcile│
 │ Quality (Great Expectations) ── BI (Superset/Tableau)  │    │  ► Breaks ► Incidents ► Investigate     │
 │        │ glue script            │ glue script          │ VS │  ► Explain (offline AI)                 │
 │ Alerting (PagerDuty)  ──►  Ticketing (ServiceNow)      │    │                                        │
 │ Investigation (i2)    ──►  Graph DB (Neo4j)            │    │  one config file per feed              │
 └──────────────────────────────────────────────────────┘    │  one audit trail across all of it       │
   • many VMs, many vendors, many contracts                   │  one vendor, one bill                   │
   • every seam is a script somebody maintains                └────────────────────────────────────────┘
   • cloud egress the regulator forbids
```

### Three structural failures Inspecto removes

1. **The glue-code liability.** Specialist tools are wired together with scripts and webhooks. When an
   upstream partner changes a file layout, the seam fails silently and the dashboard keeps rendering
   yesterday's number. Inspecto's lifecycle is one declarative configuration end to end, and a fault at any
   stage is a first-class **Signal** that carries to every downstream Dataset, tile and Incident.
2. **The egress exclusion.** Observability, analytics and AI platforms are cloud-first. Central banks,
   defence, sovereign government and telecom operators under data-residency law cannot legally send them
   data. Inspecto runs fully offline — including its AI — with **no outbound network call in its
   air-gapped profile**, and the release pipeline asserts that with a test.
3. **The cluster tax.** Distributed frameworks were designed for servers with 4–8 cores. A modern node has
   64–128 cores and NVMe. For most enterprise feeds, coordination costs more than it delivers. Inspecto's
   vectorised single-node engine ingests **over half a million rows per second on a 12-column feed**,
   measured; and when one node is genuinely not enough, Enterprise partitions the same artifact across
   Kubernetes.

---

# 2 · Architecture — collapsing the stack along its seams

```
  ┌───────────────────────────────────────────────────────────────────────┐
  │                          OPERATOR CONSOLE — three Lenses               │
  │      Business Lens        │      Builder Lens       │     Ops Lens     │
  │  dashboards · KPIs ·      │  Workbench (Connections,│  Runs · Signals ·│
  │  Requirements · lineage   │  Collectors, Pipelines) │  Alerts →        │
  │                           │  Studio (Datasets,      │  Incidents →     │
  │                           │  Queries, Widgets,      │  Cases · Approvals│
  │                           │  Dashboards, Link, Geo) │                  │
  └───────────┬─────────────────────────┬─────────────────────┬───────────┘
              ▼                         ▼                     ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │              CONTROL PLANE — versioned REST /api/v1 (OpenAPI)          │
  │  OIDC/PKCE SSO · RBAC · ABAC (Enterprise) · optimistic concurrency     │
  │  (If-Match) · four-stage write gate · single-dispatch audit trail      │
  └───────────┬───────────────────────────────────────────────┬───────────┘
              ▼                                               ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │                              DATA PLANE                               │
  │ ┌──────────────────────────┐              ┌────────────────────────┐  │
  │ │ ACQUISITION & PARSING    │              │ RECONCILIATION & OPS   │  │
  │ │ SFTP·FTPS·FTP·S3·GCS·JDBC│              │ Dataset vs Dataset     │  │
  │ │ ASN.1 CDRs · fixed-width │              │ Breaks · auto-close ·  │  │
  │ │ delimited·xlsx·json·regex│              │ aging · promotion      │  │
  │ │ dedup · gap detection ·  │              │ Alerts → Incidents →   │  │
  │ │ schema-drift detection   │              │ Cases · SLA sweeps     │  │
  │ └────────────┬─────────────┘              └───────────▲────────────┘  │
  │              ▼                                        │               │
  │ ┌─────────────────────────────────────────────────────┴────────────┐  │
  │ │        VECTORISED PARQUET LAKEHOUSE (embedded DuckDB)            │  │
  │ │  Hive partitioning · DuckLake catalog · Query Library · Studio   │  │
  │ └──────────────────────────────────────────────────────────────────┘  │
  └───────────────────────────────────┬───────────────────────────────────┘
                                      ▼
  ┌───────────────────────────────────────────────────────────────────────┐
  │        EMBEDDED INTELLIGENCE — local models, zero egress               │
  │  7 reflex skills  │  23-tool deliberative belt  │  autonomy ladder L0–L3│
  └───────────────────────────────────────────────────────────────────────┘
```

### The five layers

1. **Acquisition & parsing** — scheduled Collectors with watermarks, deduplication, decompression, sequence
   gap detection and schema-drift detection, over **10**<!--count:parsing-frontend-tokens--> parsing
   frontends.
2. **The vectorised lakehouse** — DuckDB executing directly over Hive-partitioned Parquet on local disk;
   a DuckLake catalog makes every committed file visible atomically.
3. **Reconciliation & quality** — declarative Dataset-vs-Dataset matching with exact, absolute or
   percentage tolerance; Breaks with a real lifecycle; quality signals at ingest.
4. **Operations & investigation** — Alerts → Incidents → Cases with SLAs and root-cause analysis; graph
   Link Analysis and a fully offline Geo Map studio.
5. **Embedded intelligence** — an in-process assistant on local models, with a governed autonomy ladder
   from *explain* to *act with approval*.

### Why the seams are the product

Specialists compete inside silos. Inspecto's value is in what happens **between** them, because nothing
has to be integrated:

* A Collector detects a sequence gap in an overnight feed. It raises a `SEQUENCE_GAP` **Signal**; the
  Signal is linked through the Catalog's lineage graph to every Dataset built from that feed; **every
  dashboard tile bound to those Datasets is badged stale** with the Signal as its tooltip; and an
  **Incident** opens carrying a `causationId` that traces back to the exact batch and file. When the
  feed recovers and the pipeline commits, the badges clear.
* The same thread — Signal, lineage, `causationId`, Incident — is one audit trail, not four exports.

### Spaces: many tenants, one install

Every configuration, Dataset, dashboard and Incident lives in a **Space** — an isolated tree with its own
lakehouse. A single install hosts a revenue-assurance team, a fraud team and an audit team side by side;
**Space Templates** start a new Space from a vertical blueprint in one action, and the cross-Space
**Exchange** (Standard) publishes a Dataset from one team to another with attribution and without a copy
job.

---

# 3 · Acquisition & the vectorised lakehouse

```
  ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
  │  Feeds           │     │  Parsers         │     │  PartitionWriter │
  │  · raw CDRs      ├────►│  · native DuckDB ├────►│  · stage & reveal│
  │  · delimited/xlsx│     │  · vectorised    │     │  · Hive layout   │
  │  · fixed / binary│     │  · quarantine    │     │  · catalog commit│
  └──────────────────┘     └──────────────────┘     └────────┬─────────┘
                                                             ▼
                                                    ┌──────────────────┐
                                                    │ Parquet lakehouse │
                                                    │ + DuckLake catalog│
                                                    └──────────────────┘
```

### 3.1 File-native acquisition of the hard formats

Inspecto specialises in the formats generic ETL engines struggle with:

* **Telecom-grade ASN.1 decoding.** A 154-file decoder subsystem with vendor corpora processes raw Call
  Detail Records from switch manufacturers out of the box — a capability generic data platforms do not have.
* **Binary and text fixed-width**, both record modes (line-oriented and byte-layout).
* **Delimited, xlsx, JSON and regex-text** frontends, all first-class node types —
  **7**<!--count:parser-node-types--> `parser.*` node types over **6**<!--count:builtin-parsers-->
  DuckDB-native built-ins, plus a plugin lane for anything proprietary.
* **Connectors** for SFTP, FTPS, FTP, S3, Google Cloud Storage and JDBC databases, with retry and backoff,
  delivered in **every edition**.
* **Schema-drift detection.** When a delimited feed's header changes — even a rename at equal width — the
  batch still commits and one WARN Signal names exactly what moved.
* **Quarantine and error isolation.** Rows with schema mismatches or unreadable encodings are segregated
  into per-file error ledgers; healthy rows proceed.
* **Requirements triage.** Paste the business requirement; the platform maps it to the Datasets,
  Expectations and KPIs that satisfy it, and shows what is still missing.

### 3.2 The vectorised lakehouse

* **Embedded DuckDB** executes vectorised SQL directly against columnar Parquet — no external database
  cluster to run, patch or licence.
* **Hive-partitioned layout** (`year=/month=/day=`) gives partition pruning for historical analysis for
  free.
* **Atomic visibility.** Writes are staged and revealed atomically; with the DuckLake catalog, a file is
  visible to every reader exactly when its catalog transaction commits — never partially.
* **Air-gapped by construction.** The DuckDB extensions the engine uses ship inside the bundle; an
  air-gapped install never reaches for the network, and a release test fails if it could.
* **Query Library** — reusable SQL queries with `$`-parameters, executed live on the embedded engine, and
  the substrate for Widgets, Dashboards, KPIs and Reports in the Studio.
* **A metadata model you can export.** The whole Space — Collectors, Pipelines, Datasets, Queries,
  Dashboards — round-trips through a versioned **Metadata Bundle** with drift detection, so environments
  are promoted by diff, not by hand.

---

# 4 · Reconciliation → Breaks → Incidents

```
  Feed A ──┐
           ├──► [Reconciliation] ──► match? ──no──► ┌──────────────┐
  Feed B ──┘        ▲                                │    BREAK     │ open → resolved
              key columns ·                          │  key · type  │      → auto_closed
              tolerance (exact /                     │  first seen  │  aging 0-30·30-60·60-90·90+
              absolute / percent)                    └──────┬───────┘
                                                            ▼ promote
                                                     ┌──────────────┐
                                                     │   INCIDENT   │ SLA · assignee · RCA
                                                     │  causationId │ MTTR · MTTD
                                                     └──────────────┘
```

### 4.1 Dataset-vs-Dataset reconciliation
In banking, insurance and telecommunications, the gap between an upstream generation feed and the
downstream billing or ledger system is revenue. Inspecto reconciles Datasets declaratively: key columns,
compare columns, and a tolerance per column that is **exact, absolute or percentage**. It runs as
vectorised SQL, so a full-volume join across millions of records is seconds, not a batch window.

### 4.2 The Break lifecycle
Unlike BI tools that chart a variance, Inspecto manages each discrepancy as a stateful **Break**:
* **Created** with the key, the side that is missing or mismatched, and the run that found it.
* **Auto-closed** when a later run no longer observes it — a timing difference that resolves itself
  costs nobody a click.
* **Aged** from first observation, and bucketed **0–30 · 30–60 · 60–90 · 90+ days** for audit and
  regulatory reporting.
* **Promoted** to an Incident in one action, carrying the Break key, the reconciliation and the run as
  evidence — deduplicated, so a Break promoted twice opens one Incident.

### 4.3 Incident management and root-cause analysis
* **Alerts → Incidents → Cases**, with assignees, SLA deadlines, notes, links and tags.
* **Threaded causation.** Every Incident carries a `causationId` back through the run to the batch and the
  raw file.
* **RCA and post-mortems** annotated on the Incident, in the console.
* **MTTR and MTTD** on the KPI report — with the detection anchor stated (first Signal at the
  Incident's causation root → Incident opened), so the number is defined, not implied.

---

# 5 · Forensic investigation — Link Analysis and offline Geo

```
  ┌────────────────────────────────────┐   ┌────────────────────────────────────┐
  │        LINK ANALYSIS STUDIO        │   │          GEO MAP STUDIO            │
  │ server-side entity/link projection │   │ offline MapLibre basemap + vectors │
  │ PageRank · betweenness · closeness │   │ heatmaps · origin-destination     │
  │ eigenvector · degree               │   │ routes · time slider + playback   │
  │ Louvain · label propagation ·      │   │ measure · radius · polygon · notes│
  │ connected components               │   │ layer manager · GeoJSON overlays  │
  │ six pattern packs · suspicion score│   │ saved Geo Views                   │
  └────────────────────────────────────┘   └────────────────────────────────────┘
```

### 5.1 Link Analysis
Uncover fraud rings, laundering networks and coordinated behaviour without a separate graph database:
* **Server-side projection.** Relational Datasets are projected into entity/link graphs by DuckDB
  aggregation on the server; the browser receives a graph, not a table.
* **Centrality** — PageRank, betweenness, closeness, eigenvector and degree — to find the actors that
  matter.
* **Community detection** — Louvain, label propagation and connected components.
* **Six pattern packs** for common topologies: *Circular flow*, *Layering chain*, *Pass-through
  intermediary*, *Inbound collector*, *Call-forwarding relay*, *Shared associates*.
* **Suspicion scoring** that composes connectivity and pattern hits into a ranked list of nodes to look
  at first, plus saved Views so an investigation resumes where it stopped.

### 5.2 Geo Map Analysis
* **100 % offline.** A bundled MapLibre basemap with vector land, boundaries and places — no tile server,
  no internet.
* **Origin-destination routes**, **heatmaps**, a **time slider with playback**, and an intelligence
  toolbox — measure, radius, polygon and notes — over a layer manager with GeoJSON overlays.
* **Saved Geo Views** for repeatable analysis.

---

# 6 · Embedded intelligence — explain, draft, act with approval

```
                          THE GOVERNED AUTONOMY LADDER
  ┌────────────────────────────────────────────────────────────────────────┐
  │ L3  BOUNDED AUTONOMY (Enterprise, opt-in)                              │
  │     bounded remediation classes · hourly budgets · kill switch ·       │
  │     mandatory SHADOW mode first                                        │
  ├────────────────────────────────────────────────────────────────────────┤
  │ L2  ACT WITH APPROVAL (Standard+)                                      │
  │     the agent drafts; the Approvals Inbox shows the preview;           │
  │     nothing executes until an operator approves; the approval is       │
  │     actor-attributed and audited like any human action                 │
  ├────────────────────────────────────────────────────────────────────────┤
  │ L1  AUTHORING & DRAFTING                                                │
  │     natural language → SQL, pipeline configs, schedules; human saves   │
  ├────────────────────────────────────────────────────────────────────────┤
  │ L0  EXPLAIN & INVESTIGATE                                               │
  │     "why did batch 408 quarantine 12 % of rows?" — grounded RCA,       │
  │     lineage, schema explanation · read-only · fully offline            │
  └────────────────────────────────────────────────────────────────────────┘
```

### 6.1 Zero cloud egress, enforced
Sending customer records or network logs to a commercial cloud LLM is prohibited in the environments
Inspecto serves. The assistant connects only to **local inference runtimes** inside the customer's
perimeter, and the build pipeline runs `EgressGuardTest`, which fails the release if a cloud model SDK
ever enters the air-gapped artifact.

### 6.2 Sell the ladder, not the ceiling
* **Tier A — Explain & Investigate (L0–L1, all editions).** Diagnose a failed run from its error ledgers
  and schema, explain a grammar, translate a question into a validated query. Highest value, lowest risk,
  and it works in the deployments regulated buyers actually have.
* **Tier B — Author & Act with Approval (L2, Standard+).** The agent drafts a fix — a delimiter, a field
  width, a backfill. It lands in the **Approvals Inbox** with a preview. When approved, it executes through
  **the same authenticated `/api/v1` routes a human uses**, producing an identical actor-attributed audit
  entry.
* **Tier C — Bounded Autonomy (L3, Enterprise).** Never lead with `AUTO`. Enable `SHADOW` for one action
  class, review what it *would* have done in the `/autonomy` ledger, then promote that class with a
  conservative hourly budget and a single-click kill switch.

### 6.3 Seven reflex skills and a 23-tool belt
Seven deterministic reflex skills ship: `DiagnoseAndAlertSkill`, `ExplainEntitySkill`, `KpiToSqlSkill`,
`NlToScheduleSkill`, `ReportNarrativeSkill`, `ReportSqlSkill`, `SuggestConfigSkill`. For multi-step
work the deliberative agent wields a **23-tool belt** — among them `kpi_report_builder`,
`suggest_expectations`, `query_author`, `pipeline_author`, `projection_author`, `component_draft`,
`config_schema`, `anomaly_scan`, `signal_timeline` and `diff_batches` — every mutating tool previewed
before it is presented.

---

# 7 · Performance — measured capacity

### The capacity thesis
For workloads in the low billions of rows per day, a distributed cluster introduces more latency and
operational friction than it removes. Inspecto's embedded engine does the work on one node — and
partitions across nodes only when the arithmetic says so.

### Measured figures
*From `docs/okf/backend/build-run/performance.md` — JDK 26 / DuckDB 1.5.2, `PipelineBenchmark`, 2M-row
files, re-measured with no regression at v3.9.0.*

| Stage | Measured | Unit cost |
|---|---|---|
| **Native ingest, 12 columns** | **523,000 rows/s** | **0.16 µs per cell** |
| Native ingest, 40 columns | 125,000 rows/s | 0.20 µs per cell |
| Java fallback ingest (messy files, per core) | 0.65–1.0 µs per cell | — |
| **Vectorised SQL transform** | **1,400,000 rows/s** | — |
| **Parquet write** | **1,100,000 rows/s** | — |

Ingest cost is linear in **cells** (rows × columns), so capacity at any width follows from the per-cell
figure. Transform and write are never the bottleneck.

### Sizing — one 8-core commodity node, 100-column records
*Projected from the stage benchmarks above.*

| | |
|---|---|
| Processing density | 4–10 million cells/s |
| Throughput at 100 columns | 40,000–100,000 rows/s |
| Daily volume, 100 % utilisation | 3.5–8.6 billion rows |
| **Quotable, sized to peak (3–5× daily mean) with headroom** | **~1 billion rows per day per node** |

> **The rule.** One 8-core node runs the daily CDR volume of a mid-sized operator or the clearing ledger
> of a retail bank — no cluster. A trillion rows a day is real, and it is an **Enterprise** conversation:
> a partitioned cluster of the same artifact writing on the order of 100–270 TB of Parquet daily.

---

# 8 · Fault tolerance and disaster recovery

### 8.1 In-process fault tolerance
* **Crash-isolated batches.** Each batch runs on its own ephemeral DuckDB connection; one feed's failure
  cannot touch another's.
* **The markers-last commit order.** Catalog register → manifest → backup originals → **markers → ledger
  last**. An interruption anywhere leaves no dirty state; the next run resumes idempotently.
* **Restart as recovery.** Processing is stateless with respect to the JVM. The bundled **service
  wrapper** (systemd unit; Windows service) restarts a dead process, which re-arms and resumes.
* **A durable audit trail.** Every audited mutation and every Signal is written to rolling Parquet — and
  in DR and Enterprise modes to PostgreSQL — so a restart loses nothing an auditor will ask for.

### 8.2 The recovery ladder
*Targets signed 2026-09-06 as contract service levels (`editions.md` §3.14).*

| Tier | Profile | Edition | RPO | RTO | Mechanism |
|---|---|---|---|---|---|
| **T1** | workstation | Personal | ≤ 24 h | ≤ 4 h | daily config backup + local restore |
| **T2** | single server | Standard | ≤ 1 h | ≤ 1 h | hourly config + daily full (post-checkpoint) + volume snapshot |
| **T3** | gateway-fronted, multi-team | Enterprise | ≤ 15 min | ≤ 2 h | T2 + Postgres PITR/WAL + off-site copy |
| **T4** | **active/passive warm standby** | **Standard** | **≤ 5–15 min** | **≤ 30 min** | streaming replica + spaces-tree sync + automatic lease failover |
| **T5** | partitioned Kubernetes cluster | Enterprise | as T3/T4 | as T3/T4 | shared lakehouse; a lost pod loses nothing committed *(signed design)* |

### 8.3 T4 — fault-tolerant DR in Standard
* **Two sites, one artifact.** PostgreSQL streaming replication carries operational state; a scheduled
  `spaces/` tree sync carries the lakehouse.
* **Automatic failover.** A run lease with a heartbeat means the standby takes over the moment the active
  node stops renewing — inside the 30-minute RTO, with no runbook step in the critical path.
* **Backup, verify, restore — as jobs.** Scheduled backup and restore-verification run as ordinary jobs
  with their own Signals, so a backup that would not restore is an Incident before it is a disaster.
* **Honest dependency.** DR needs PostgreSQL. Standard without DR needs nothing.

```
  SITE A (active)                                  SITE B (warm standby)
 ┌───────────────────────────┐                    ┌───────────────────────────┐
 │ Inspecto ── lease holder  │                    │ Inspecto ── waits on lease│
 │  Parquet lakehouse ───────┼── spaces sync ────►│  Parquet lakehouse        │
 │  PostgreSQL state ────────┼── stream repl ────►│  PostgreSQL replica       │
 └───────────────────────────┘                    └───────────────────────────┘
```

---

# 9 · Governance, security and compliance

```
  ┌────────────────────────────────────────────────────────────────────────┐
  │                        THE SECURITY PERIMETER                          │
  │  Delegated IAM          │  Zero-egress core       │  Audit trail        │
  │  · OIDC + PKCE          │  · offline maps + AI    │  · single dispatch  │
  │  · Keycloak/Okta/Entra  │  · bundled extensions   │  · append-only,     │
  │  · RBAC · ABAC (Ent.)   │  · EgressGuardTest      │    durable, attributed│
  │  Secret hygiene         │  Supply chain           │  Write safety       │
  │  · SecretsProvider seam │  · 95 locked deps       │  · four-stage gate  │
  │  · no plaintext, ever   │  · CycloneDX + SPDX SBOM│  · path jail        │
  │  · env/file/keystore/   │  · SHA-256 + GPG signed │  · If-Match         │
  │    vault (by edition)   │    releases             │    concurrency      │
  └────────────────────────────────────────────────────────────────────────┘
```

### 9.1 Operational governance
* **Feed freshness.** Sequence continuity and watermarks are tracked per Collector; a missing file trips a
  `SEQUENCE_GAP` Signal immediately.
* **Incident SLAs.** Every Incident may carry a deadline; a sweep runs every 60 seconds; a breach stamps
  the Incident, emits `OBJECT_SLA_BREACH`, escalates priority per `*_escalation.toon`, and routes to the
  configured notification channels (webhook, email).
* **Executive visibility.** KPI reports with MTTR, MTTD and Incident aging.

### 9.2 Compliance posture
*Controls mapped in `compliance/controls-matrix.md`.*
* **SOC 2 (Trust Services Criteria) — alignment.** CC6.1: credentials resolve at run time through the
  `SecretsProvider` seam — environment, file, keystore, and vault or cloud key management by edition —
  never plaintext, and a CI guard fails the build if a credential literal appears. CC6.7: zero telemetry,
  zero tracking, an enforced lean dependency surface. CC8: **95 locked third-party dependencies**, a
  CycloneDX **and** SPDX bill of materials per bundle, SHA-256 checksums and detached GPG signatures on
  every release.
* **ISO/IEC 27001:2022 — alignment.** A.8.15–8.17: every state-changing API call passes one dispatch seam
  (`AuditTrail`) into an **append-only, durable, actor-attributed** audit trail. A.8.2–8.5: authentication
  delegated to the enterprise identity provider over OIDC with PKCE.
* **NIST 800-53 / FedRAMP — alignment at the Moderate baseline.** Declarative configuration (`.toon`),
  input validation at the write gate, and path containment (`PathJail`) against traversal. *Alignment of
  the implementation statements — not an authorisation programme.*

### 9.3 Write safety, by construction
Every write to the control plane passes four gates in a fixed order — **write-root 503 → validation 422
→ path jail 403 → conflict 409** — and only then acts, atomically. Concurrent editors are protected by
`If-Match` optimistic concurrency, so the last writer never silently wins.

### 9.4 Engineering you can audit
The trust signal is the build itself: **over 4,000 automated tests** across the reactor, **ten CI guards**
(secrets, vocabulary, dependency lock, SBOM module coverage, coverage floors, doc links, doc citations,
doc counts, gate tally, byte hygiene), a versioned `/api/v1` OpenAPI contract enforced by test, and a
release that is refused if any of them is red.

---

# 10 · Total cost of ownership, editions and the 60-minute evaluation

### 10.1 What you stop paying for
A structural comparison. Put your own figures against each line — the shape is what changes.

| Cost driver | Multi-vendor stack | Inspecto |
|---|---|---|
| Compute | 8–15 VMs across ingest, queue, compute, BI, database, orchestration | 1 node (+1 standby for DR) |
| Licences | separate ETL, BI, observability, ticketing and graph contracts | one platform licence |
| Engineering | platform/DevOps engineers to keep the seams alive | a fraction of one data-operations role |
| Compliance | one review, pen-test and SBOM per vendor | one artifact, one SBOM, one review |
| Time to first feed | a sprint of integration | one configuration file |
| Extension | bespoke code at every seam | **19 extension points** plus a services model |

### 10.2 Editions — one codebase, three build flavours

```
  ┌────────────────────────┐
  │       ENTERPRISE       │  the cluster: partitioned scale-out on Kubernetes (T5, signed design)
  │                        │  ABAC tenant isolation · gateway assertion trust · L3 autonomy
  │                        │  + Postgres SQL/BI query surface over the lakehouse (signed design)
  └───────────▲────────────┘
              │
  ┌───────────┴────────────┐
  │        STANDARD        │  operate it, prove it, survive a node:
  │                        │  OIDC SSO · HTTPS · RBAC · durable attributed audit ·
  │                        │  Alerts → Incidents → Cases · Geo + Link Analysis ·
  │                        │  backup/restore · notification channels · cross-Space Exchange ·
  │                        │  fault-tolerant DR (T4) · Tier B "act with approval"
  └───────────▲────────────┘
              │
  ┌───────────┴────────────┐
  │        PERSONAL        │  the whole data plane, free, on a laptop:
  │                        │  acquisition · every parser incl. ASN.1 · lakehouse · Query Library ·
  │                        │  Studio dashboards · Reconciliation & Breaks · Spaces · Tier A assistant
  └────────────────────────┘
```

*Personal finds the problem; Standard owns it; Enterprise scales it.* Reconciliation is free. The moment
a Break needs an owner, an audit export or a second team, you are in Standard — because that is where
those modules live. Enterprise adds nothing you must learn twice: the same artifact, as a pod, N times.

### 10.3 Extend it, or have it extended
Nineteen `ServiceLoader` extension points cover the places estates differ: parsers and decompressors for
proprietary formats (the ASN.1 vendor functions ship through this seam), connectors, job types, maintenance
tasks, notification channels, pipeline node types and executors, a secrets provider, and route modules.
What falls outside both product and plugin is built for you as a service. **One vendor, one config, one
bill.**

### 10.4 The 60-minute evaluation
```
  1. Download the ~90 MB bundle for your edition.
  2. Launch:   ./serve.sh          (Linux)      or      serve.bat          (Windows)
  3. Apply the included Space Template, or point a Collector at an existing feed.
```
Within the hour: ingest at native speed, watch a reconciliation produce Breaks, open an Incident from one,
explore the result in a dashboard — and ask the offline assistant why a row was quarantined.

* **Commercial inquiries:** *[commercial contact]*
* **Documentation and blueprints:** *[documentation URL]*
* **Vocabulary of record:** `docs/GLOSSARY.md`

---

*Version 1.2 supersedes 1.1 (September 2026). Changes: release basis stated; the edition ladder aligned
to the signed tier decisions (DR at Standard, Kubernetes at Enterprise); every technical specific verified
against the codebase or removed; counts derived; measured and projected figures distinguished; the
security vocabulary aligned to the compliance register.*
