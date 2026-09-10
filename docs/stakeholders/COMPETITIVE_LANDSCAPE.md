# Inspecto — Competitive Landscape

> Audience: product owner **and** the market/sales side, together · Status date: **2026-09-10** ·
> **A living alignment document.** It has two sides that must agree before a claim leaves the building:
> the **product side** (what ships, in which edition, with what evidence — every statement here traces to
> the repository) and the **market side** (who we meet, what they have, what buyers believe — every
> statement here traces to a dated, linked source). §6 is where the two sides disagree until they don't.
>
> Companions: [`PRODUCT_CAPABILITIES.md`](PRODUCT_CAPABILITIES.md) (what the user gets) ·
> [`EXECUTIVE_BRIEF.md`](EXECUTIVE_BRIEF.md) (the sponsor's three minutes) ·
> [`../okf/capabilities/editions/editions.md`](../okf/capabilities/editions/editions.md) (editions,
> topologies, the signed decisions) · [`../superpower/enterprise-scale-out-plan.md`](../superpower/enterprise-scale-out-plan.md)
> (the signed Kubernetes design) · [`../okf/backend/build-run/performance.md`](../okf/backend/build-run/performance.md)
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
runtime services below Enterprise. One declarative config file onboards a feed. It collapses four tool
categories — a NiFi-style collection and pipeline layer, a Superset-style self-service BI layer, a
Jira-style incident workflow, and the scripts around them — for **regulated, air-gapped, resource-constrained
buyers where heavyweight stacks cannot go**.

### 1.2 The edition ladder — what actually ships where

The staging table of record is `tools/bundle-modules.mjs`; the per-capability matrix is
[`../EDITIONS.md`](../EDITIONS.md). Editions are build flavours of one codebase, never branches.

| | **Personal** (free) | **Standard** (the revenue gate) | **Enterprise** |
|---|---|---|---|
| **Buyer situation** | one engineer, one ugly feed, no budget | a regulated team putting it in front of other people | multi-tenant, or one node is not enough |
| **The sentence** | *the whole data plane, free, on your laptop* | *operate it, prove it, survive a node* | *outgrow a node: same artifact, N pods* |
| **What is in it** | core: acquisition (SFTP/FTP/FTPS/DB/S3/GCS), every parser lane, Pipelines, the Parquet lakehouse, Query Library, Studio dashboards, **Reconciliation → Breaks**, Spaces, `/api/v1` | + `inspecto-security` (OIDC, HTTPS, RBAC, attributed audit) · `inspecto-ops` (Alerts → Incidents → Cases → RCA) · `inspecto-metrics` + `inspecto-events` (`/metrics`, event feed, audit CSV) · `inspecto-backup` · `inspecto-notify-channels` · `inspecto-geo-link` (Geo Map + Link Analysis) · `inspecto-exchange` (cross-Space sharing) · **fault-tolerant DR (T4)** | + `inspecto-policy` (ABAC tenant isolation) · **partitioned scale-out on Kubernetes (T5)** · gateway assertion trust |
| **External dependencies** (signed D9, 2026-09-10) | none | none — unless DR is enabled, then Postgres | Postgres + S3-compatible object store |
| **Conversion trigger** | the laptop becomes a shared server; or a Break needs an owner | a second tenant that must not see the first; or one node cannot keep up | — |

⚠ **The ladder's story is already true of the build, not invented for sales:** *Personal finds the
problem; Standard owns it; Enterprise scales it.* Reconciliation and quality signals are core; the moment a
Break needs an Incident, an audit export or a second team, the buyer is in Standard — because that is
where the modules live.

⛔ **The assistant and the autonomy ladder ship in NO edition.** `CP-14` on the board reads *"never bundled by
design; routes answer 503 in every bundle."* Built and tested; blocked on the Java-floor question
(`PKG-5`), which has no owner. It appears in no column above on purpose.

### 1.3 Measured capacity — the only numbers we may quote

Source: `docs/okf/backend/build-run/performance.md` (JDK 26 / DuckDB 1.5.2, `PipelineBenchmark`,
2M-row files, re-measured at v3.9.0 with no regression). Ingest cost is linear in **cells** (rows ×
columns), so capacity is a cells-per-second figure divided by width.

| Measured | Figure |
|---|---|
| Native ingest, 12 columns, one batch | 523K rows/s = **0.16 µs/cell** |
| Native ingest, 40 columns | 125K rows/s = 0.20 µs/cell |
| Java (messy-file) ingest, single-threaded | 0.65–1.0 µs/cell per core |
| Transform / write (DuckDB vectorised) | 1.4M / 1.1M rows/s at 12 columns — never the bottleneck |
| Footprint | ~90 MB artifact, zero external services (Personal) |

**Worked example — 8-core node, 100-column average:** 4–10M cells/s per node → 40–100K rows/s → 3.5–8.6
billion rows/day at 100 % utilisation → **~2–4 billion/day with 50 % headroom** → **~1 billion/day sized to
peak** (real feeds run 3–5× their daily mean at peak). That last figure is the one to quote.

**What 1 trillion rows/day would take at 100 columns:** 11.6M rows/s sustained = 1.16 billion cells/s =
**115–290 nodes**, writing **100–270 TB/day** of Parquet. It is an Enterprise cluster-design conversation,
never a single-node claim.

⚠ **Caveats that bound every figure above:** stage micro-benchmarks on one file — no sustained run, no
concurrent dashboard queries, no inbox churn; the benchmark machine's core count is unrecorded; 100 columns
extrapolates a slope measured at 3, 12 and 40; messy files fall to the slower Java path. **A sustained
benchmark with a CI floor is the single highest-value piece of evidence we do not yet have** (§6, A3).

### 1.4 The extension surface — what "plugins for the rest" rests on

19 `ServiceLoader`-loaded extension points (counted 2026-09-10:
`grep -rhoE "ServiceLoader\.load\(\s*[A-Za-z]+\.class" inspecto*/src/main`). The ones that matter in a
bespoke engagement are where estates differ: `ParserPlugin` and `DecompressorPlugin` (proprietary
formats — the ASN.1 vendor functions already ship through this surface), `CollectorConnectorFactory` (their
feeds), `JobTypeProvider` and `MaintenanceTaskProvider` (their scheduled work), `NotificationChannel`,
`PipelineNodeType` + `PipelineNodeExecutor` (their transforms), `SecretsProvider` (their vault), `RouteModule`.

⚠ **Two honesty notes before any coverage percentage goes on a slide.** `ExpressionProvider` is declared and
registered by nothing (`SPEC-DEADSEAM-1`). And the processor catalogue is **119**<!--count:processors-->
entries of which **35**<!--count:processors-delivered--> are delivered, **17**<!--count:processors-partial-->
partial, and the rest planned — a palette of inactive tiles a buyer will count. Claim coverage **by category
covered end to end**, never as a percentage of that palette.

### 1.5 What we are genuinely deep in

Two lanes, and we should say so rather than apologise for the rest:

- **File-native acquisition of hard formats.** **10**<!--count:parsing-frontend-tokens--> parsing frontends
  incl. **6**<!--count:builtin-parsers--> DuckDB-native built-ins and **7**<!--count:parser-node-types-->
  `parser.*` node types; binary fixed-width; compressed input; a 154-file ASN.1 decoder subsystem with
  vendor corpora. Native CDR ingestion is a telecom-specific capability generic platforms do not have.
- **Reconciliation → Breaks → Incidents.** The chain from "these two datasets disagree" to "someone owns
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
| **Data quality platforms** | **Collibra Data Quality** (built on OwlDQ, acquired Feb 2021) · Ataccama ONE · Great Expectations / Soda (OSS) | DQ buyers compare our Expectation engine to GX/Soda on day one — and it has routes but **no UI** (`ING-6`) |

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

---

## 3. Head-to-head by lane — the honest table

A buyer with an incumbent in a lane compares feature-for-feature, and a bundle loses every such comparison
at once. This is structural, not a failing — Palantir loses it too.

| Lane | The specialist has | We have |
|---|---|---|
| Ingestion | 300–500 connectors; NiFi's processor library | SFTP/FTP/DB/S3/GCS; the hard-format parsers |
| BI | 40+ chart types, plugin ecosystems, communities | Datasets → Widgets → Dashboards |
| Data quality | GX's 300+ expectations, profiling, docs generation | an Expectation engine with routes and no UI |
| Investigation | i2's decades of analyst workflows, case management, court-ready export | Link Analysis (backend projection pending) + Geo Map |
| Ops workflow | ServiceNow's CMDB, SLAs, approvals, integrations | Alerts → Incidents → Cases |

**How the bundle wins anyway — five levers, all grounded:**

1. **Compete on the seams.** A gap at acquisition becomes an Incident carrying lineage to the dashboard that
   went stale. The Signal ledger, threaded `causationId`, three-layer audit and Catalog lineage already exist;
   they are described as plumbing and should be described as the product.
2. **Change the evaluation unit** — "one config → operable, audited, monitored feed in under an hour", timed
   by the buyer. Every lane incumbent needs a sprint to wire together.
3. **Thin means lean, with numbers** — 94 reactor dependencies under an SBOM guard; publish it against a
   Kubernetes stack's dependency count.
4. **Interoperate where thin** — export Datasets to Superset, import GX expectation suites, emit Incidents to
   ServiceNow. Be the operable core the specialists plug into.
5. **Be deep in two lanes and say so** (§1.5).

---

## 4. Claims register

### 4.1 We MAY claim — with the evidence behind each

| Claim | Evidence |
|---|---|
| One ~90 MB artifact, zero external runtime services (Personal; Standard without DR) | `NFR-2`; signed D9 |
| One config file onboards a feed | the `.toon` model; `PRODUCT_CAPABILITIES.md` |
| Native ingest ~500K rows/s at 12 columns; transforms >1M rows/s | `performance.md`, measured |
| ~1 billion 100-column rows/day per 8-core node, sized to peak | §1.3 derivation |
| Native ASN.1 CDR ingestion | 154-file decoder subsystem, vendor corpora |
| Reconciliation with a Breaks lifecycle, in the free tier | core module |
| Fault-tolerant DR at Standard; Kubernetes scale-out at Enterprise | signed 2026-09-10; **design, not yet built** — say so |
| 19 extension points; the ASN.1 vendor functions ship through one | §1.4 |
| A distinct Standard bundle exists | `STANDARD-BUNDLE-1`, shipped 2026-09-10 |

### 4.2 We MUST NOT claim yet — and what unlocks each

| Claim | Why not | Unlocks when |
|---|---|---|
| Anything about the AI assistant or autonomy | ships in **no** bundle (`CP-14`, `PKG-5`) | `PKG-5` resolves and the assistant is in a downloadable artifact |
| "1 trillion rows/day" for Standard | off by 10–250× on one node (§1.3) | never for Standard; an Enterprise cluster-design conversation |
| "119 processors" | 67 are planned, inactive tiles | say **35**<!--count:processors-delivered--> delivered, or name the families |
| "90–100 % of requirements" | the palette above; one dead SPI | phrase as categories covered + named seams (§1.4) |
| A production-grade Expectation engine | routes, no UI (`ING-6`) | the UI ships, or GX suites import |
| "Low maintenance" | the install kit (preflight, service wrappers, upgrade/rollback) is unbuilt | deployment Phases 0–1 ship |
| Air-gap parity claims about Definite | their blog says it; their product page does not | a vendor conversation |

---

## 5. Positioning of record

> *Inspecto is the end-to-end data operations platform for regulated environments where data can't leave:
> acquire the hard formats, reconcile, investigate, and prove it to an auditor — from one 90 MB artifact,
> with no cluster to run. Standard handles billions of records a day on a single node with fault-tolerant
> DR; Enterprise partitions the same artifact across Kubernetes when you outgrow one. Every category is
> covered in the product, every point your estate differs has a named extension seam, and what falls outside
> both we build for you. One vendor, one config, one bill.*

Every clause traces to §1 or §4.1. The horizontal is not an industry; it is a buyer situation —
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
| **A4** | Publish pricing? | editions exist; Standard bundle exists | Definite shows $250/mo; a buyer who cannot self-serve a price assumes enterprise sales | Open |
| **A5** | The plugin/services line | 19 SPIs; one dead seam to fix or remove | "we build the rest for you" is a revenue line — price it | Open |
| **A6** | The Expectation engine | routes exist, no UI; absorb GX suites rather than rival them | DQ buyers test this first | Open — product decision |
| **A7** | Retire the stale stakeholder docs | brief + capabilities dated 2026-07-07 under- and over-sell | a sponsor reading them today gets the wrong product | Open — refresh from `EDITIONS.md` |
| **A8** | Link Analysis backend | UI complete, projection pending; serves two verticals | the i2-shaped differentiator is worth zero half-built | Open |

---

## 7. Sources

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
