# Inspecto — Competitive Landscape

> Audience: product owner **and** the market/sales side, together · Status date: **2026-09-10** for the market
> side; **product side refreshed 2026-11-12** against `origin/master` (corrections 2026-09-23 and 2026-11-12 logged in §2.4) ·
> **A living alignment document.** It has two sides that must agree before a claim leaves the building:
> the **product side** (what ships, in which edition, with what evidence — every statement here traces to
> the repository) and the **market side** (who we meet, what they have, what buyers believe — every
> statement here traces to a dated, linked source). §6 is where the two sides disagree until they don't.
>
> Companions: [`brochure-competitive-matrix.md`](brochure-competitive-matrix.md) (vendors 2026-10-07, Inspecto column 2026-11-12 — capability-by-capability
> matrix against LATRO, Subex, Mobileum, Neural Technologies, TEOCO and Araxxe, mapped to Inspecto modules) ·
> [`features-product-capabilities.md`](features-product-capabilities.md) (what the user gets) ·
> [`brochure-executive-brief.md`](brochure-executive-brief.md) (the sponsor's three minutes) ·
> [`../platform/plan-editions.md`](../platform/plan-editions.md) (editions,
> topologies, the signed decisions) · [`../../../../superpower/enterprise-scale-out-plan.md`](../../../../superpower/enterprise-scale-out-plan.md)
> (the signed Kubernetes design) · [`../platform/tests-performance.md`](../platform/tests-performance.md)
> (the only measured throughput figures).
>
> **Maintenance rule.** Every count is derived, not typed — the `<!--count:…-->` markers are checked by
> `tools/check-doc-counts.mjs` against the committed contracts, so a stale number fails the build. Every
> market claim carries the date it was verified; re-verify before any external use older than a quarter.
> Vendor ownership in this space churns (three of the names below changed hands in the last four years).

---

## 1. Product side — what we are, grounded

### 1.1 The thesis in one paragraph

A lean, configuration-driven **data acquisition + management + BI + investigation** platform: one ~90 MB
self-contained artifact that runs on a laptop, an air-gapped server or a container, with zero external
runtime services on Basic and Standard (Advanced keeps its Incidents and Cases on PostgreSQL). One declarative config file onboards a feed. It collapses four tool
categories — a NiFi-style collection and pipeline layer, a Superset-style self-service BI layer, a
Jira-style incident workflow, and the scripts around them — for **regulated, air-gapped, resource-constrained
buyers where heavyweight stacks cannot go**.

### 1.2 The edition ladder — what actually ships where

The staging table is derived by `tools/bundle-modules.mjs` from `offerings/<edition>.toon`; the per-capability
matrix is [`../../../../EDITIONS.md`](../../../../EDITIONS.md); the editions and Add-ons are Product Map Items under
`product-map/edition/` and `product-map/addon/`. Editions are build flavours of one codebase, never branches.
*(Refreshed 2026-11-12: the Personal / Professional / Enterprise ladder this section described was replaced by
Basic / Standard / Advanced on 2026-10-17, `LA-EDITIONS-1`, `f1b64ce03`; Reconciliation had already left the free
tier on 2026-10-07, `MODULE-REORG-1` P7, `c5cb8ea4f`. A fourth flavour, Preview, takes every module and is not sold.)*

| | **Basic** | **Standard** | **Advanced** |
|---|---|---|---|
| **Buyer situation** (`EDITIONS.md`) | one analyst on one machine | a team | an organisation |
| **What is in it** | the core platform: acquisition connectors incl. S3 / GCS (`inspecto-connectors`), every parser lane except ASN.1, Pipelines, the Parquet lakehouse, Query Library, Studio dashboards, Spaces, `/api/v1`; *Add data* (upload, an import inbox, a PostgreSQL snapshot); Link Analysis at the Explorer level with Entity Lists; 19 Link Analysis Space Templates plus `orders-starter`. No sign-in | + `inspecto-oidc` (OIDC sign-in, roles, attributed audit, machine clients) · `inspecto-secrets` · `inspecto-connectors-kafka` · `inspecto-telecom-asn1` · `inspecto-ops` (Alerts → Incidents) · `inspecto-case-management` · `inspecto-action-requests` (four-eyes, the Incident Brief) · `inspecto-reconciliation` (→ Breaks) · `inspecto-scoring` · `inspecto-screening` · `inspecto-anomaly` · `inspecto-predictive` · `inspecto-regulatory-reporting` · `inspecto-observability` · `inspecto-backup` · `inspecto-notify-channels` · `inspecto-exchange` · `inspecto-agent` (the assistant); the Investigations add-on and the Geo map; 9 domain Space Templates (`telco-ra`, `telco-fraud`, `telco-bypass`, `telco-rerating`, `partner-settlement`, `business-assurance`, `mobile-money`, `aml`, `payment-fraud`) | + `inspecto-policy` (Access Policies, per-Space isolation) · `inspecto-la-store-pg` (Investigations on shared PostgreSQL) · `inspecto-intelligence` (the intelligence agents) |
| **External dependencies** | none | none (PostgreSQL optional; the driver ships) | PostgreSQL, required for Incidents, Cases, notes, links and tags |
| **Conversion trigger** | a second person needs to sign in; a feed must stay up to date on a schedule; a Break needs an owner | rows one role may not see; several servers sharing one state | — |

⚠ **Not on the ladder, though the 2026-09-10 version put them there:** fault-tolerant DR (T4) and partitioned
scale-out on Kubernetes (T5) are a signed design ([`enterprise-scale-out-plan.md`](../../../../superpower/enterprise-scale-out-plan.md));
phase A (shared PostgreSQL state, the pool included) is recorded complete 2026-09-14 and `PostgresStateStoreTest`
ran green against PostgreSQL 18.6 on 2026-09-12 (the plan, §A), but no HA deployment has validated failover live, and
T5 is not built. Advanced's shipped multi-server
piece is shared PostgreSQL state, not partitioning.

⚠ **The ladder's story:** *Basic finds the problem; Standard owns it; Advanced governs it.* Exploration, data
quality and Link Analysis are in every edition; the moment a finding needs an Incident, a Break, a second person or
a schedule, the buyer is in Standard, because that is where the modules live.

⛔ **The assistant ships from Standard; the intelligence agents from Advanced; neither is in a published
release.** The **assist agent** (`inspecto-agent`, `/assist/*`) is in `offerings/standard.toon`. The **intelligence
agent** (`inspecto-intelligence`: `/agent/*`, the autonomy ladder, the approvals inbox) entered the top edition on
2026-09-29 (`ASSURE-INTELLIGENCE-BUNDLE-1`, `5938e030b`, then Enterprise) and is in `offerings/advanced.toon` today. `inspecto-agent-hosted` is still staged by no edition. No `v*` tag has been
cut since `v3.12.0` (2026-06-05), so no downloadable bundle carries either (§4.2).

### 1.3 Measured capacity — the only numbers we may quote

Measured in `docs/okf/product/layer/platform/tests-performance.md` (JDK 26 / DuckDB 1.5.2, `PipelineBenchmark`,
2M-row files, re-measured at v3.9.0 with no regression). ⚠ Every figure below was measured on DuckDB 1.5.2; the
engine moved to DuckDB 2.0 (`DUCKDB-2-MIGRATION-1`) and the stage figures were not re-measured on it. An
interleaved A/B on 2026-10-23 found ingest level, Parquet write 30–60 % faster and 4–8 concurrent readers 10–20 %
slower ([`spec-duckdb.md`](../../module/engine/spec-duckdb.md)); the end-to-end run below predates it. Ingest cost is linear in **cells** (rows ×
columns), so capacity is a cells-per-second figure divided by width.

| Measured | Figure |
|---|---|
| Native ingest, 12 columns, one batch | 523K rows/s = **0.16 µs/cell** |
| Native ingest, 40 columns | 125K rows/s = 0.20 µs/cell |
| Java (messy-file) ingest, single-threaded | 0.65–1.0 µs/cell per core |
| Transform / write (DuckDB vectorised) | 1.4M / 1.1M rows/s at 12 columns — never the bottleneck |
| Footprint | ~90 MB artifact, zero external services (Basic, Standard) |

**End-to-end measurement (2026-09-14, the base for every sizing figure below):** the whole Collector path —
acquire, parse, tag, transform, partitioned write, lineage — on a six-core / twelve-thread 2019 mobile i7
(Windows, JDK 26, other services idle), 48 files × 250K rows × 100 columns as 24 batches with
`processing.threads=12` on the native lane: **174 s = 69K rows/s = 6.9M cells/s**; a 24 × 100K rerun after the
2026-09-14 checkpoint fix gave **81K rows/s = 8.1M cells/s** at ~78 % CPU while busy. Twelve batches ran
**3.4× one batch** (same files, `threads=1`: 30 s vs 8.9 s at 3 columns). Per physical core that is
**~1.35M cells/s end to end**. Single-thread stage figures on the same box reproduced the `performance.md`
table within 5 % at 12 and 40 columns; at 100 columns per-cell cost is 0.28 µs native (1.8× the 12-column
figure — ingest is NOT perfectly linear in cells at wide schemas). Reproduce with
`PerfDeepDiveBenchmark#concurrencyAndAutoDerive -Dbench.threads/-Dbench.cols/-Dbench.engine`; the raw
laptop numbers are deliberately NOT in `performance.md` (operator, 2026-09-14) — this paragraph is their home.

**Server factor (estimate, the one unmeasured input):** a current Linux server core sustains ×1.3–1.6 a 2019
mobile core (clocks, no thermal throttling) and the Linux allocator adds ×1.0–1.3 (measured ×2.1 on the
Java appender lane in a Linux container on the same box; assumed small for the native lane) →
**~1.8–2.8M cells/s per physical server core, end to end.** ⚠ Confirm with one run on the target hardware;
until then this is the factor a challenge would land on.

**Worked example — 8-core Linux node, 100-column average:** 14–22M cells/s → 140–220K rows/s → 12–19
billion rows/day at 100 % → 50 % headroom → ÷ 3–5 peak-to-mean → **~2.5 billion/day sized to peak**. That
last figure is the one to quote. 16 cores → **~5 billion/day**; 32 cores → **~10 billion/day**. Linear holds
only with ≥ cores concurrent batches (`processing.threads`, default = cores since 2026-09-14; ingest is
single-threaded per file), NVMe-class storage (~60–250 MB/s Parquet at 32-core peak, from 1.0–2.7 bytes/cell)
and the console's share of the cores left in the peak headroom. Above 32 cores: measure, or partition.

**What 1 trillion rows/day would take at 100 columns:** 11.6M rows/s sustained = 1.16 billion cells/s =
**13–20 partitioned 32-core nodes** (was 115–290 8-core-equivalents on the old stage-benchmark base), writing
**100–270 TB/day** of Parquet. A cluster-design conversation on the unbuilt T5 design, never a single-node claim.

⚠ **Caveats that bound every figure above:** one end-to-end run on one machine, synthetic clean files, no
concurrent dashboard queries, no inbox churn, no thermal soak; the server-core factor is an estimate; wide
schemas cost more per cell than the 12-column slope predicts; messy files fall to the Java lane, which is
**materially slower than the native lane — by a margin we have not measured under controlled conditions and
therefore do not quote**. **A sustained benchmark on the
target hardware with a CI floor is still the single highest-value piece of evidence we do not have** (§6, A3).

### 1.4 The extension surface — what "plugins for the rest" rests on

**47**<!--count:spi-extension-points--> extension-point interfaces, derived by `tools/check-doc-counts.mjs`
from every `ServiceLoader.load(X.class)`, `OptionalSpi.all(X.class)` and `SpiSlot(X.class)` call site in
`inspecto*/src/main` (re-counted 2026-09-14: the 2026-09-10 grep counted call sites of ONE loader and landed on
the same number by coincidence — three security seams go through `SpiSlot`, and `TransformFunctionProvider`
in the ASN.1 subsystem is a 20th outside the core). The ones that matter in a
bespoke engagement are where estates differ: `ParserPlugin` and `DecompressorPlugin` (proprietary
formats — the ASN.1 vendor functions already ship through this surface), `CollectorConnectorFactory` (their
feeds), `JobTypeProvider` and `MaintenanceTaskProvider` (their scheduled work), `NotificationChannel`,
`PipelineNodeType` + `PipelineNodeExecutor` or, from a pack, `StepExecutor` (their transforms), `SecretsProvider` (their vault), `RouteModule`.

Above the Java seams, since 2026-11-11 (`PRODUCT-ASSEMBLY-1` PA-6, PA-7): a partner builds an **App** (Space
Templates, nav, docs, conformance data) with the App authoring kit (`node tools/inspecto-app.mjs new | validate | test |
package`), ships it as a signed App package, and a running server installs, upgrades or removes it live
(`AppPackage`, `AppInstallRoutes`); customer builds are assembled from Product definitions with a signed
entitlement (PA-10b). A machine client signs in with OIDC client credentials and gets one narrow integration grant in
named Spaces only, opt-in through `-Dauth.oidc.clients` (`MachineTrust`, `Roles.admitMachine`, `RolesMachineAdmissionTest`; `OIDC-CC-INTEGRATION-ROLE-1`, 2026-11-01).

⚠ **Two honesty notes before any coverage percentage goes on a slide.** `ExpressionProvider` is NOT a dead seam
*(corrected 2026-10-18; `SPEC-DEADSEAM-1` verdict refuted 2026-09-15)*: `BuiltinExpressions` implements it,
`ExpressionRegistry` registers it permanently, and `ParameterResolver` evaluates every Job-parameter `$token`
through it. What is true: it is loaded by `ServiceLoader` from the classpath and from Job Packs, and no
external provider ships in the base build — so quote it as a seam, not as an existing plugin. And the processor catalogue is **122**<!--count:processors-->
entries of which **54**<!--count:processors-delivered--> are delivered, **17**<!--count:processors-partial-->
partial, and the rest planned — a palette of inactive tiles a buyer will count. Claim coverage **by category
covered end to end**, never as a percentage of that palette.

### 1.5 What we are genuinely deep in

Two lanes, and we should say so rather than apologise for the rest:

- **File-native acquisition of hard formats.** **10**<!--count:parsing-frontend-tokens--> parsing frontends
  incl. **6**<!--count:builtin-parsers--> DuckDB-native built-ins and **7**<!--count:parser-node-types-->
  `parser.*` node types; binary fixed-width; compressed input; a 154-file ASN.1 decoder subsystem with
  vendor corpora (`inspecto-telecom-asn1`, Standard and up). Native CDR ingestion is a telecom-specific capability generic platforms do not have.
- **Reconciliation → Breaks → Incidents** (Standard and up). The chain from "these two datasets disagree" to "someone owns
  it" is one product here and two or three vendors anywhere else.

---

## 2. Market side — who we meet (verified 2026-09-10)

### 2.1 Closest in *shape* — one platform, ingest → analyse → operate → investigate

| Vendor | Overlap | Why it is not the same | Verified |
|---|---|---|---|
| **Definite** | The closest competitor found. **Same DuckDB + DuckLake lakehouse**, ingestion (500+ connectors), transformations, BI, semantic layer, an agentic AI analyst ("Fi"), self-hosted as one platform | **Helm into Kubernetes** — a cluster, where we are one artifact. **No incident management, no link analysis, no geospatial** (their own site). Target buyer is finance/RevOps/marketing, not regulated telecom. Standard from $250/mo, credit-based. ⚠ "fully air-gapped, zero egress" appears in their blog, not on the product page — confirm with the vendor before treating air-gap parity as fact | 2026-09-10, definite.app |
| **Palantir Foundry / Gotham** | The conceptual competitor — pipelines, modelling, analysis, investigation in one | Enormous, services-heavy, six-to-seven-figure entry. Our pitch is the inverse | 2026-09-10 |
| **Cloudera CDP Private Cloud** | Genuinely air-gap-capable on-prem data platform | Cluster-scale, heavy ops burden — the buyer we target *because* CDP cannot go there | 2026-09-10 |
| **Microsoft Fabric** | Same "one platform" story | Cloud-only — disqualified from the air-gap wedge | 2026-09-10 |
| **Pentaho** | The legacy analogue: on-prem visual ETL + reporting in one suite | **Acquired by LEO Software, June 2026**; the community edition was being restricted to push users to paid Pentaho+ — a migration opening | 2026-09-10 |
| **Rill Data / Metabase** | Closest on **footprint philosophy** — single binary, DuckDB-backed, runs on a laptop | BI only: no acquisition, no ops workflow | 2026-09-10 |

### 2.2 Closest by *buyer* — where displacement actually happens

| Vertical | Incumbents | Notes |
|---|---|---|
| **Telecom RA / fraud** | **Mobileum RAID** (absorbed WeDo, 2019, $70M; RAID 9 current) · **Subex HyperSense** (GenAI-embedded RA/FM, winning 2026 carrier deals incl. a USD 1.93M five-year North-African modernisation) · Amdocs (absorbed cVidya) · Neural Technologies · Araxxe | Carrier procurement already solved; built for billions of CDRs/day on distributed engines. We compete *below their floor* on footprint and price |
| **Investigation (fraud, public sector)** | **i2 Group** — *not IBM*: divested to N. Harris Computer Corp (Constellation Software) effective Jan 2022 · Siren · Linkurious · SAS Visual Investigator · Nuix | i2 is the product our Link Analysis + Geo Map most resembles; the incumbent in telecom, law enforcement, intelligence |
| **Reconciliation as a product** | Duco (SaaS) · SmartStream TLM · Gresham Clareti · Broadridge | Our Reconciliation → Breaks lifecycle *is* their entire business |
| **Data quality platforms** | **Collibra Data Quality** (built on OwlDQ, acquired Feb 2021) · Ataccama ONE · Great Expectations / Soda (OSS) | DQ buyers compare our Expectation engine to GX/Soda on day one — it has a builder UI but **six check kinds** against GX's 300+ *(corrected 2026-09-23: this said "routes but no UI"; `ING-6` closed 2026-07-07; product fact corrected 2026-11-12: five → six, `baseline` added 2026-09-24)* |

### 2.3 Lane incumbents

- **Acquire / pipeline:** Apache NiFi (our own stated comparator) · **StreamSets (IBM — acquired with
  webMethods from Software AG, completed 1 July 2024)** · Airbyte · Talend/Qlik · Informatica · IBM DataStage.
  Airflow/Dagster/Prefect are orchestration-only and require Python.
- **Data observability:** Monte Carlo · Anomalo · Bigeye · Sifflet · Metaplane — SaaS-first. ⚠ Whether any
  offers true air-gapped deployment is **unconfirmed**; industry commentary names on-prem mandates as *the*
  common trigger for leaving them. Treat "they structurally cannot follow us" as supported, not proven.
- **BI:** Superset · Metabase · Redash (OSS) · Tableau Server · Power BI Report Server · Qlik Sense.
- **Ops workflow:** Jira Service Management · ServiceNow · PagerDuty — integration targets, not displacement.
- **Lakehouse / query:** Dremio · Starburst/Trino — self-hostable overlaps with the DuckDB + Parquet layer.

### 2.4 Corrections log — claims that were wrong before verification

| Said | Actual | Lesson |
|---|---|---|
| "IBM i2" | i2 Group, N. Harris / Constellation since Jan 2022 | vendor names date a document by years |
| "Pentaho (Hitachi Vantara)" | LEO Software, June 2026 | — |
| "cloud object storage is a release-gating gap" (the exec brief) | S3/GCS connectors shipped in **every** edition 2026-09-07 (`CONNECTORS-BUNDLE-1`) | the brief dated 2026-07-07 undersells the product |
| "nothing occupies the same square" | Definite occupies the neighbouring square with the same stack | "no direct competitor" is a risk, not a moat |
| "the Expectation engine has routes but no UI (`ING-6`)" — §2.2, §3, §4.2, §6 A6 | the builder UI landed 2026-07-04 (`73831fa6`) and `ING-6` closed 2026-07-07, two months **before** this document's status date (corrected 2026-09-23) | a board ID quoted as a gap needs its close date checked first |
| "the assistant and the autonomy ladder ship in NO edition" — §1.2, §4.2 | `PKG-5` staged the assist agent into Professional and Enterprise on 2026-09-12; only `inspecto-intelligence` and `inspecto-agent-hosted` still ship in none (corrected 2026-09-23) | re-read `tools/bundle-modules.mjs` after any packaging change |
| "Link Analysis backend projection pending" — §3, §6 A8 | `/inv/projection*` routes ship in `inspecto-geo-link`; the evidential controls (`LA-19`) were built 2026-09-24 (corrected 2026-10-06) | — |
| "the audit trail is in memory on every stock bundle" — §4.1 | `EVENTS-DURABLE-1` closed 2026-09-11: the Professional/Enterprise launchers set `-Devents.backend=parquet`; only Personal stays in memory (corrected 2026-09-23) | a P1 cited by ID goes stale the day it closes |
| "19 SPIs; one dead seam to fix or remove" — §6 A5, and "one dead SPI" in §4.2 | `SPEC-DEADSEAM-1` was refuted 2026-09-15 (`ExpressionProvider` is live, §1.4) and the count is guard-derived (**47**<!--count:spi-extension-points-->); both lines survived the 2026-09-23 pass (corrected 2026-11-12) | a correction in one section must be searched for in every other |

---

## 3. Head-to-head by lane — the honest table

A buyer with an incumbent in a lane compares feature-for-feature, and a bundle loses every such comparison
at once. This is structural, not a failing — Palantir loses it too.

| Lane | The specialist has | We have |
|---|---|---|
| Ingestion | 300–500 connectors; NiFi's processor library | SFTP/FTP/DB/S3/GCS, Kafka (Standard and up); *Add data* in every edition (upload, an import inbox, a PostgreSQL snapshot, a no-code row filter); object-store output registered in DuckLake (Standard and up, `SCALEOUT-OBJSTORE-DATABASE-DIR-1`); the hard-format parsers |
| BI | 40+ chart types, plugin ecosystems, communities | Datasets → Widgets → Dashboards |
| Data quality | GX's 300+ expectations, profiling, docs generation | an Expectation engine with a builder UI and six check kinds (`non_null`, `range`, `regex`, `referential`, `condition`, and `baseline`, a whole-input profile compared with accepted history, since 2026-09-24) |
| Investigation | i2's decades of analyst workflows, case management, court-ready export | Link Analysis (server projection `POST /inv/projection*` shipped; evidential controls `LA-19` built 2026-09-24) + Geo Map; the Investigations add-on (sealed step history, replay, Dossier with verification); Screening against Entity Lists |
| Ops workflow | ServiceNow's CMDB, SLAs, approvals, integrations | Alerts → Incidents → Cases, four-eyes Action Requests, Workflow & SLA |

**How the bundle wins anyway — five levers, all grounded:**

1. **Compete on the seams.** A gap at acquisition becomes an Incident carrying lineage to the dashboard that
   went stale. The Signal ledger, threaded `causationId`, three-layer audit and Catalog lineage already exist;
   they are described as plumbing and should be described as the product.
2. **Change the evaluation unit** — "one config → operable, audited, monitored feed in under an hour", timed
   by the buyer. Every lane incumbent needs a sprint to wire together.
3. **Thin means lean, with numbers** — 89<!--count:locked-dependencies--> reactor dependencies under an SBOM guard; publish it against a
   Kubernetes stack's dependency count.
4. **Interoperate where thin** — export Datasets to Superset, import GX expectation suites, emit Incidents to
   ServiceNow. Be the operable core the specialists plug into.
5. **Be deep in two lanes and say so** (§1.5).

---

## 4. Claims register

### 4.1 We MAY claim — with the evidence behind each

| Claim | Evidence |
|---|---|
| One ~90 MB artifact, zero external runtime services (Basic and Standard) | `NFR-2`; signed D9 |
| One config file onboards a feed | the `.toon` model; `features-product-capabilities.md` |
| Native ingest ~500K rows/s at 12 columns; transforms >1M rows/s | `performance.md`, measured |
| ~2.5 / ~5 / ~10 billion 100-column rows/day per 8- / 16- / 32-core Linux node, sized to peak | §1.3: measured end to end on six cores (8.1M cells/s) × a stated server-core factor; conditions stated (concurrent batches, NVMe, console headroom, native lane). Operator decision 2026-09-14: publish without hardware specifics |
| "~1.35M cells/s per physical core end to end, measured; ~1.8–2.8M on a current Linux server core" | §1.3 — say "measured" only of the first half |
| Native ASN.1 CDR ingestion (Standard and up) | 154-file decoder subsystem, vendor corpora; `inspecto-telecom-asn1` in `offerings/standard.toon` |
| Reconciliation with a Breaks lifecycle (Standard and up) | `inspecto-reconciliation` in `offerings/standard.toon`. *Corrected 2026-11-12: this said "in the free tier", true of the Personal edition until 2026-10-07, `MODULE-REORG-1` P7* |
| Fault-tolerant DR and Kubernetes scale-out as a **signed design** | signed 2026-09-10; DR phase A built but never validated on an HA deployment, T5 **not built** — say so, and name no edition (§1.2). The audit trail is kept on disk (Parquet) in every edition, Basic included (`offerings/*.toon` `eventsBackend: parquet`; `EDITIONS.md`) |
| **47**<!--count:spi-extension-points--> extension points; the ASN.1 vendor functions ship through one | §1.4; guard-derived |
| Three edition bundles (Basic, Standard, Advanced) from one build | `offerings/*.toon`, derived by `tools/bundle-modules.mjs`; `LA-EDITIONS-1` (2026-10-17) |
| An Expectation (data-quality) engine with a builder UI — six check kinds, not a GX-sized library | `baseline` added 2026-09-24 (`ad1c0d9b6`, `BaselineEvaluator`); `ING-6` closed 2026-07-07 (`REQUIREMENTS.md` §5); `ExpectationEvaluator`; `inspecto-ui/src/app/modules/admin/expectations/`. A breach raises a deduplicated Incident only where `inspecto-ops` ships (Standard and up). *Moved here from §4.2 on 2026-09-23 — its unlock condition, "the UI ships", was met on 2026-07-04* |
| Explainable anomaly scoring, statistical baselines **or** an isolation forest, with a per-feature explanation (Anomaly Detection add-on, Standard and up) | `ANOMALY-ML-SCORING-1` closed 2026-10-20: `AnomalyForestScorer`, `AnomalyForestScorerTest`, the `aml` pack model on `method: isolation_forest` (`PackFraudAmlAnomalyPropertyTest`) |
| Customer segments (RFM), churn scores and forecasts (Predictive Analytics add-on, Standard and up) | `features/inspecto-predictive`: `SegmentJobType` / `ChurnJobType` / `ForecastJobType` (`predict.segment`, `predict.churn`, `predict.forecast`), `SegmentJobTest`, `ChurnJobTest`, `ForecastJobTest`; read route and entity panel `PREDICTIVE-SURFACE-1`. No Studio authoring panes yet (`PREDICTIVE-STUDIO-PREVIEW-1`) |
| Partner settlement and re-rating as Space Templates (Standard and up) — not wholesale, MVNO or roaming | `spaces/_templates/partner-settlement` (S0 to S8: statements vs re-rated usage, netting, positions, disputes as Cases with an evidence pack, aging, accruals) and `spaces/_templates/telco-rerating` (R1 to R3); hub `solution-pack/partner-settlement` |
| SIM-box detection **from CDRs** with one Incident per suspected box (Standard and up) | `spaces/_templates/telco-bypass` (B1 to B3, `tb_*` Jobs, `tb_box_caserule`); hub `solution-pack/telco-bypass/plan-as-built.md`. Not test-call or signalling evidence |
| Machine-to-machine integration under its own identity (OIDC client credentials, one narrow grant in named Spaces) | opt-in (`-Dauth.oidc.clients`); `MachineTrust`, `OidcMachineActorTest`, the Space narrowing in `Roles.admitMachine` (`RolesMachineAdmissionTest`); `OIDC-CC-MACHINE-ACTOR-1`, `OIDC-CC-INTEGRATION-ROLE-1` closed 2026-11-01 |
| Partner-built Apps: an authoring kit, signed packages, live install and upgrade | `tools/inspecto-app.mjs`, `AppPackage`, `AppInstallRoutes`; `PRODUCT-ASSEMBLY-1` PA-6, PA-7 (2026-11-10/11) |

### 4.2 We MUST NOT claim yet — and what unlocks each

| Claim | Why not | Unlocks when |
|---|---|---|
| Anything about the AI assistant | *(corrected 2026-09-23 — this row said "ships in no bundle")* staged in every Standard and Advanced bundle (since 2026-09-12 under the old edition names) (`CP-14`, `PKG-5`, both closed), but **no release has been tagged since `v3.12.0` (2026-06-05)** and only a `v*` tag publishes (`.github/workflows/release.yml`), so no downloadable artifact carries it (still true 2026-11-12) | a tagged release publishes a Standard bundle |
| Anything about the autonomy ladder or the intelligence agent (approvals inbox, `SHADOW`/`AUTO`) | the original unlock — a bundling decision — **was met 2026-09-29** (`ASSURE-INTELLIGENCE-BUNDLE-1`): `inspecto-intelligence` is in `offerings/advanced.toon` (`inspecto-agent-hosted` still in none). It now waits on the same thing as the assistant: no tagged release carries it | a tagged release publishes an Advanced bundle |
| "1 trillion rows/day" on one node, any edition | off by 10–250× on one node (§1.3) | never for one node; a cluster-design conversation on the unbuilt T5 design |
| "119 processors" | 51 are planned, inactive tiles | say **54**<!--count:processors-delivered--> delivered, or name the families |
| "90–100 % of requirements" | the palette above *(corrected 2026-11-12: this also said "one dead SPI"; there is none, §1.4)* | phrase as categories covered + named seams (§1.4) |
| "Low maintenance" | the service wrappers shipped 2026-09-11 (`SCR-3`: a systemd unit, a Windows Scheduled Task) but their live acceptance on two hosts has never run, and preflight and upgrade / rollback are unbuilt | `DEPLOY-SERVICE-WRAPPER-1` (P2) runs its live acceptance |
| Real-time or pre-call blocking | the windowed Alert Rule and `sequence` / `absence` Patterns are built (PA-9b, PA-9c, 2026-11-12) but are not yet evaluated on each stream commit, and no latency is measured; inline allow / block is unbuilt | PA-9d ships the Add-on, the commit hook and the latency harness (`REALTIME-DECISIONING-1`); inline: `REALTIME-INLINE-DECISION-1` |
| "An AI agent investigates your alerts" | the Incident Brief (`POST /incidents/{id}/briefs`, 2026-11-01) is deterministic — no model-written prose — and is 1 of 6 slices; there is no Incident page panel yet | `AGENTIC-INVESTIGATION-1` ships S2 (the panel) and the prose layer; say "an Incident Brief with cited evidence and a four-eyes proposal" until then |
| Air-gap parity claims about Definite | their blog says it; their product page does not | a vendor conversation |

---

## 5. Positioning of record

> *Inspecto is the end-to-end data operations platform for regulated environments where data can't leave:
> acquire the hard formats, reconcile, investigate, and prove it to an auditor — from one 90 MB artifact,
> with no cluster to run. Standard handles billions of records a day on a single node; the signed design adds
> fault-tolerant DR and partitions the same artifact across Kubernetes when you outgrow one. Every category is
> covered in the product, every point your estate differs has a named extension seam, and what falls outside
> both we build for you. One vendor, one config, one bill.*

Every clause traces to §1 or §4.1. *(Product facts corrected 2026-11-12: the edition names, and DR / Kubernetes
named as a design, not an edition feature, §1.2.)* The horizontal is not an industry; it is a buyer situation —
*organisations that receive data as files from systems they don't control, under regulation that says the
data cannot leave* — with **telecom RA**, **financial reconciliation and audit**, and **public-sector
investigation** as the first three Space-Template blueprints under it. Telecom is the cheapest place to win
the first three customers (native ASN.1), not the market.

What not to lead with: BI (crowded, free); autonomy (not shipped, and refused as positioning); processor
counts; SSO as a feature (table stakes).

---

## 6. Alignment questions — open until both sides sign

| # | Question | Product side says | Market side says | Status |
|---|---|---|---|---|
| **A1** | Which segment first? | telecom gives the cheapest first three customers (ASN.1) | RA is niche; broaden via the buyer-situation horizontal + three blueprints | `AGT-SEGMENT-1` deferred with caveat kept (2026-09-10). Blueprints are roadmap items, not shipped templates |
| **A2** | May we quote a capacity number? | only §1.3's derivation, with its caveats | a number is needed on the first slide | **Blocked on A3** |
| **A3** | Sustained benchmark + CI floor | ~a day's work; `PipelineBenchmark -Dbench.cols=100` replaces the extrapolation | this is the evidence behind every capacity claim | **Open — highest-value unbuilt evidence** |
| **A4** | Publish pricing? | three edition bundles exist (Basic, Standard, Advanced) | Definite shows $250/mo; a buyer who cannot self-serve a price assumes enterprise sales | Open |
| **A5** | The plugin/services line | **47**<!--count:spi-extension-points--> SPIs, no dead seam (§1.4); Apps ship as signed packages *(facts corrected 2026-11-12, §2.4)* | "we build the rest for you" is a revenue line — price it | Open |
| **A6** | The Expectation engine | engine + builder UI shipped (`ING-6`, closed 2026-07-07), six check kinds *(corrected 2026-11-12: `baseline` added 2026-09-24)*; absorb GX suites rather than rival them | DQ buyers test this first | Open — product decision |
| **A7** | Retire the stale stakeholder docs | brief + capabilities dated 2026-07-07 under- and over-sell | a sponsor reading them today gets the wrong product | Open — refresh from `EDITIONS.md` |
| **A8** | Link Analysis backend | projection shipped; the Investigation layer (op log, multi-hop traversal, dossier) landed 2026-09-23; evidential controls (`LA-19`) built 2026-09-24; serves two verticals | the i2-shaped differentiator is worth zero half-built | Open |

---

## 7. References

Verified 2026-09-10: [Harris acquires i2 from IBM](https://i2group.com/articles/harris-acquires-i2-product-portfolio-from-ibm) ·
[Mobileum acquires WeDo](https://www.mobileum.com/about/news-press-releases/mobileum-inc-acquires-wedo-technologies/) ·
[Subex HyperSense GenAI](https://www.subex.com/press_release/subex-redefines-intelligence-in-revenue-assurance-and-fraud-management-with-embedded-genai/) ·
[Subex North Africa deal](https://www.equitybulls.com/category.php?id=371094) ·
[Collibra acquires OwlDQ](https://www.prnewswire.com/news-releases/collibra-acquires-predictive-data-quality-vendor-owldq-301220863.html) ·
[IBM completes StreamSets/webMethods](https://newsroom.ibm.com/2024-07-01-IBM-Completes-Acquisition-of-StreamSets-and-webMethods,-Bolstering-its-Automation,-Data-and-AI-Portfolios) ·
[Pentaho+ launch](https://www.hitachivantara.com/en-us/news/gl231130) · [Pentaho alternatives / LEO acquisition](https://www.integrate.io/blog/best-pentaho-alternatives/) ·
[Definite](https://www.definite.app/) · [Definite self-hosted stack (vendor blog)](https://www.definite.app/blog/self-hostable-data-stack-private-cloud-ai-data-agent) ·
[Rill + DuckDB](https://www.rilldata.com/blog/why-we-built-rill-with-duckdb) ·
[DuckLake catalog database](https://ducklake.select/docs/stable/duckdb/usage/choosing_a_catalog_database) ·
[Data observability tools 2026](https://atlan.com/know/data-observability-tools/).

Product-side sources are repository files, cited inline; the count markers are verified on every build.
