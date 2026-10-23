---
type: Plan
title: Product maturity assessment — every client-facing item
description: Scored maturity (feature breadth, build status, quality evidence, enterprise grade, RFP defensibility → L1–L5) of every add-on, solution pack, edition and platform capability, against market competitors, with RFP stances and the actions that raise each level.
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
---

# Product maturity assessment — every client-facing item

> **As of 2026-10-20** (origin/master `56ba62d23`). Audience: product owner, presales, solution architects, leadership.
> Internal — it names our gaps frankly; brochures stay the external voice. Re-assess at each release or quarter:
> a score is a photograph, and several of the docs it read had already drifted from the code (§4.4).

## 1. Verdict

**Inspecto is a strongly engineered, pilot-ready platform — and not yet an RFP-proven enterprise product.**
Of 43 items assessed: L2 × 8 · L3 × 35. Nothing is L4 or L5 today, for two reasons that are both
fixable and both outside the modules themselves:

1. **The platform's enterprise proof is missing, not its code** (§4.1). HA/DR has only a one-machine drill, no restore
   drill is recorded, the signed-release path has never run, there is no pen test and no SOC 2 / ISO window has started.
   Every module rides the platform, so every module is capped at L3 until those close. Two items — **Link Analysis** and
   **Multi-entity / Group** — already score L4 on their own dimensions and move up the day the platform does.
2. **Content depth trails category leaders** (§4.2). The engines are broad and well governed; the libraries on top of
   them (fraud typologies, AML scenarios, RA controls, sanctions feeds, regulator formats) are small, and **real-time
   decisioning does not exist** — which alone disqualifies us from card-authorisation and pre-call-blocking RFPs.

**Where we genuinely lead** (verified in code; vendor columns are published claims only): four-eyes on configuration and
outbound actions, a tamper-evident audit chain with signed anchors, air-gapped editions with SBOM, evidential custody in
Link Analysis (sealed log, verifiable Dossier, audited reveal), explainable anomaly detection, and one platform spanning RA,
fraud, AML and investigation. Engineering discipline is unusual: ~1,575 Java test classes (≈ 400 drive a real Control API),
40+ CI guards, golden tests with planted positives for every pack, nightly performance floors.

## 2. How it was scored

Each item was scored 1–5 on five dimensions by an assessor reading the code and its docs hub, citing evidence
(paths, test and route counts, BACKLOG row ids); a decisive doc claim was re-checked in code.

| Dim | What it measures | 1 | 3 | 5 |
|---|---|---|---|---|
| **F** Feature breadth | vs category leaders on the RFP checklist | a fraction | core use cases, notable gaps | matches or exceeds leaders |
| **B** Build status | plan Musts done, open rows, defects | planned / stub | built, material items left | all Musts done, no defects |
| **Q** Quality evidence | tests, goldens, live runs | none | unit + HTTP tests | deep tests + recorded live runs |
| **E** Enterprise grade | authz, audit, masking, HA, retention, ops | single-user | gated + audited, HA/retention gaps | RBAC/ABAC, HA/DR, scale, compliance |
| **R** RFP defensibility | can we answer "comply" with evidence | roadmap only | core comply, some partial | every line evidenced, gaps disclosed |

**Level** (not an average — capped by the weaker of B and E, and by the platform baseline):
**L1** Concept · **L2** Prototype (works in demo, core gaps) · **L3** Pilot-ready (core built + tested; enterprise gaps
acceptable under supervision) · **L4** Production-ready (enterprise grade, defensible in an RFP) · **L5** Market-leading
(differentiated and proven at reference customers). **L4 needs B, E and R all ≥ 4** — RFP-defensible, not only built and governed. **Calibration rules applied:** (a) no item above the platform
baseline (L3 today); (b) L5 needs reference-customer evidence — the repo records none, so none is awarded;
(c) showcase packs are scored as demo assets, not SKUs. Market frame: the six telecom-assurance vendors of
[`brochure-competitive-matrix.md`](brochure-competitive-matrix.md); for AML, payment fraud, link analysis, reconciliation
and case management, the category leaders from general market knowledge (marked *(general market)* in §6).

## 3. Scorecard

Group letters match §6. Scores are the assessor's; levels are calibrated (§2).

| Grp | Item | Scores (F·B·Q·E·R) | Level |
|---|---|---|---|
| A | `cap.acquire` Data acquisition | F 3 · B 4 · Q 3 · E 3 · R 3 | **L3** Pilot-ready |
| A | `cap.pipelines` Pipelines & processing | F 4 · B 4 · Q 4 · E 3 · R 4 | **L3** Pilot-ready |
| A | `cap.analytics-bi` Analytics, BI & Studio | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| A | `cap.identity` Identity, access & security | F 4 · B 4 · Q 4 · E 4 · R 3 | **L3** Pilot-ready |
| A | `cap.operations` Operations & observability | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| A | `cap.workflow` Workflow & maker-checker | F 3 · B 5 · Q 3 · E 4 · R 3 | **L3** Pilot-ready |
| A | `PLATFORM ENTERPRISE BASELINE`  | E 3 · R 3 | **L3** Pilot-ready |
| B | `addon.incidents` Incidents | F 4 · B 4 · Q 4 · E 4 · R 3 | **L3** Pilot-ready |
| B | `addon.caseManagement` Case Management | F 3 · B 3 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| B | `addon.workflowSla` Workflow & SLA | F 3 · B 4 · Q 3 · E 4 · R 3 | **L3** Pilot-ready |
| B | `addon.actionRequests` Action Requests | F 3 · B 4 · Q 3 · E 4 · R 3 | **L3** Pilot-ready |
| B | `addon.aiAssist` AI Assist & Intelligence | F 2 · B 3 · Q 3 · E 3 · R 3 | **L3** Pilot-ready, supervised |
| B | `addon.anomalyDetection` Anomaly Detection | F 3 · B 3 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| B | `addon.multiEntity` Multi-entity / Group | F 4 · B 4 · Q 4 · E 4 · R 4 | **L3** Pilot-ready — **L4 on its own scores**, capped by the platform |
| C | `addon.reconciliation` Reconciliation | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| C | `addon.regulatoryReporting` Regulatory Reporting | F 2 · B 4 · Q 3 · E 4 · R 2 | **L3** Pilot-ready |
| C | `addon.scoringLists` Scoring & Lists | F 3 · B 4 · Q 4 · E 4 · R 3 | **L3** Pilot-ready |
| C | `addon.screening` Screening | F 2 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| C | `addon.integrationDelivery` Integration & Delivery | F 2 · B 4 · Q 3 · E 4 · R 2 | **L3** Pilot-ready |
| C | `addon.premiumConnectors` Premium Connectors | F 2 · B 3 · Q 2 · E 3 · R 2 | **L2** Prototype |
| C | `addon.linkAnalysis` Link Analysis | F 4 · B 4 · Q 5 · E 4 · R 4 | **L3** Pilot-ready — **L4 on its own scores**, capped by the platform |
| C | `addon.telecomIndustryPack` Telecom industry pack | F 2 · B 4 · Q 3 · E 3 · R 2 | **L3** Pilot-ready |
| D | `solution-pack.telco-ra` Telecom revenue assurance | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| D | `solution-pack.telco-fraud` Telecom fraud | F 3 · B 4 · Q 4 · E 4 · R 3 | **L3** Pilot-ready |
| D | `solution-pack.business-assurance` Business assurance | F 2 · B 4 · Q 4 · E 3 · R 2 | **L3** Pilot-ready |
| D | `solution-pack.industry-telecom` Industry pack telecom | F 2 · B 4 · Q 3 · E 4 · R 2 | **L3** Pilot-ready |
| E | `solution-pack.aml` AML | F 2 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| E | `solution-pack.payment-fraud` Payment fraud starter | F 2 · B 4 · Q 4 · E 3 · R 2 | **L3** Pilot-ready |
| E | `solution-pack.mobile-money` Mobile money | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| E | `solution-pack.industry-mobile-money` Industry pack mobile-money | F 2 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| E | `solution-pack.orders-starter` Orders starter | F 1 · B 4 · Q 2 · E 2 · R 1 | **L2** Prototype |
| F | `solution-pack.la-showcase` Link Analysis showcase | F 4 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| F | `solution-pack.la-showcase-off` Link Analysis showcase (feature off) | F 1 · B 3 · Q 2 · E 3 · R 2 | **L2** Prototype |
| F | `solution-pack.la-audit` Link Analysis audit showcase | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| F | `solution-pack.la-claims` Link Analysis claims showcase | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| F | `solution-pack.la-mobile-money` Link Analysis mobile money | F 3 · B 3 · Q 3 · E 3 · R 3 | **L2** Prototype |
| F | `solution-pack.la-daily-feed` Link Analysis daily feed | F 2 · B 3 · Q 4 · E 2 · R 2 | **L2** Prototype |
| F | `Inspecto LA` the standalone Link Analysis product | F 3 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| F | `edition.basic` Basic | F 2 · B 4 · Q 3 · E 2 · R 2 | **L2** Prototype |
| F | `edition.standard` Standard | F 4 · B 4 · Q 4 · E 3 · R 3 | **L3** Pilot-ready |
| F | `edition.advanced` Advanced | F 3 · B 3 · Q 3 · E 3 · R 2 | **L3** Pilot-ready |
| F | `edition.preview` Preview | F 4 · B 3 · Q 3 · E 2 · R 1 | **L2** Prototype |
| F | `Edition ladder` defensibility of Basic / Standard / Advanced | F 3 · B 4 · Q 4 · E 2 · R 3 | **L2** Prototype — the build-flavor mechanism alone would be L3; commercial enforcement (E 2) caps it |

## 4. Cross-cutting findings

### 4.1 The platform ceiling — operational proof (the single biggest lever)
| Gap | State | Row |
|---|---|---|
| HA / DR on real hosts, stated RTO/RPO demonstrated | runbook + one-machine drill only; targets uncommitted | `ASSURE-OPERABILITY-1`, `SCALEOUT-HELM-NPOD-1` |
| Restore drill | backup built, never drilled (G6 half-open) | `ASSURE-OPERABILITY-1` |
| Horizontal scale-out | not built (Helm replicas = 1); single-host nightly floors only | `SCALEOUT-D14-D16-DECISIONS-1` |
| Signed release, SBOM, vuln scan end to end | built, **never executed** (no `v*` tag since it landed) | no row |
| Certification, auditor, pen test | controls mapped (SOC 2 / ISO 27001 / NIST); window not started | BACKLOG §2 compliance program |
| API documentation quality | 430 of 559 OpenAPI operations carry skeleton summaries; ~41 typed 2xx schemas | no row |

### 4.2 Content depth vs market
- **Real-time decisioning** — every pack is batch (daily/hourly); `REALTIME-DECISIONING-1` is a P3 placeholder. Blocks
  PSP card-authorisation, inline AML, and pre-call telecom blocking RFPs.
- **Scenario / typology libraries** — AML 4, payment fraud 4, mobile money 7 vs leaders' hundreds *(general market)*;
  RA controls and fraud typologies cover the core but not a leader's catalogue.
- **Feeds and formats buyers ask for first** — no sanctions/PEP list feeds; no real regulator SAR/STR schema
  (only the SAR item has a row); no model-validation records (backtest, champion/challenger).
- **Telecom scope** — bypass/SIM-box is CDR-based only (`TELCO-BYPASS-SIMBOX-1`); signalling, test-call generation,
  5G/IoT assurance and managed services are out of scope (competitive matrix rows E–I, U, Z).
- **Link Analysis vs Quantexa / Palantir-class** — no probabilistic entity resolution; scale proven to ~10^8, not beyond;
  single-node.
- **Proof on real data** — every pack is proven only on synthetic canonical data; vendor feed mapping and threshold
  calibration are parked until a customer exists.

### 4.3 Packaging and commercial consistency
- `OFFERING-DECLARATION-DRIFT-1`: edition brochures sell showcase packs that are in no Offering (D2); the one priced
  Link Analysis pack, `la-daily-feed`, is listed in **Basic** (verified: `offerings/basic.toon` contentPacks), where its
  Alert Rules cannot dispatch (D1).
- `linkAnalysis` is billable yet built into Basic; `entity-list` is both a Basic module and part of the billable
  `scoringLists` add-on; Integration & Delivery, Premium Connectors and Telecom industry pack each hold one module —
  narrower than their names suggest.
- Per-node billing with no licence engine or meter (D-MR3); Preview carries a GA order code; Advanced's brochure
  promises HA, legal hold and residency that are unbuilt. Nothing has yet been released under the new edition names.
- Retention / legal hold covers Incidents only — Cases, anomaly scores and action-request history have none.

### 4.4 Docs that had drifted from the code (verified 2026-10-20 in code)
- `layer/product/plan-studio.md` says 13 Visualization Types — `BUILTIN_VIZ_PLUGINS` registers **20**.
- `module/etl/plan-ingestion.md` says Expectations have no UI — the `expectations` route exists in `app.routes.ts`.
- `docs/EDITIONS.md` SEC-09 says the audit log is not tamper-evident — the hash chain (`AuditAnchors`, `/audit/verify`) shipped.
- Assessors also report stale claims in `addon/incidents/plan-incidents.md` and `module/agent/plan-assistant.md` (§6 B).
  A docs-only review of these is tracked by `DOCS-AGENT-WRITTEN-REVIEW-1`.

### 4.5 Gaps found that have no BACKLOG row (each is itself a finding)
- Masking: AML `account_id` and mobile-money `wallet_id` / `msisdn` are classified `CONFIDENTIAL`, outside
  `ColumnClassification.SENSITIVE` (verified in the schemas and `ColumnClassification.java`), so they are not masked.
- Release-signing path never exercised; OpenAPI summary/schema quality; retention for Cases / anomaly scores / action
  requests; native ITSM connector; e2e UI coverage of ops panes (only `backend-smoke.spec.ts`). See §6 per item.

## 5. What raises the levels — ranked by leverage

| # | Action | Unlocks | Size |
|---|---|---|---|
| 1 | Two-host Linux HA drill + restore drill; commit RTO/RPO in `rto-rpo-statement.md` | platform E → 4; Link Analysis and Multi-entity to **L4** at once | weeks |
| 2 | Cut the first `v*` release so signing, SBOM and the vuln scan run end to end | supply-chain claims become defensible | days |
| 3 | Engage an auditor, commission a pen test, open the SOC 2 Type II window | "certified" RFP lines move from roadmap to in-progress | months |
| 4 | Fix the packaging drift (§4.3) and the masking gap (§4.5) | removes contradictions a buyer's due diligence will find | days |
| 5 | Grow content where RFPs score it: AML/fraud scenario libraries, sanctions/PEP feeds, a regulator SAR/STR format, RA control catalogue | F and R → 3–4 for the fintech and telecom packs | per pack |
| 6 | Decide on real-time decisioning (`REALTIME-DECISIONING-1`) — build, partner, or stop bidding card-auth / pre-call RFPs | qualifies or honestly scopes out a whole RFP class | decision first |
| 7 | One reference deployment on real data, with calibration recorded | the only route to L5 anywhere | customer |

**RFP guidance until then:** bid as a platform for RA, fraud monitoring, AML monitoring and investigation in
on-prem / air-gapped settings, where four-eyes, audit and custody score well; answer HA, certification and real-time
lines as **Partial / Roadmap** with the dates above; each item's RFP-stance table in §6 is the ready answer.


## 6. Per-item assessments

### A. Platform base (every edition) and the enterprise baseline

#### cap.acquire — Data acquisition (module.acquire, module.connectors, module.connectors-kafka)
- **What it is (customer view):** Connections and Collectors that pull files and records from local or NFS directories, SFTP/FTP/FTPS, JDBC databases, S3/GCS/Azure Blob and Kafka into Pipelines. They come with dedup, watermarks, gap detection, retry and decompression.
- **Scores:** F 3 · B 4 · Q 3 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Seven requirements of record. ACQ-1/2/3/5/6/7 are shipped. ACQ-4 (a Must) is **partial**: object storage is shipped, NFS/SMB network share was refused by design (`module/acquire/plan-acquisition.md` §2, §6.1).
  - Connector factories in code: Sftp, Ftp, Ftps, DbExport (JDBC), S3, GCS, AzureBlob, Kafka (`providers/inspecto-connectors/src/main/java/com/gamma/connect/`), plus the local file system and a Dataset-entry collector.
  - Step Processor board, ACQ family: 4 delivered on every edition (SP-ACQ-01..04). 4 are partial: S3/GCS have no proven end-to-end acquisition-node run, ADLS Gen2 is unproven, Kafka is unproven against a real broker, Excel is one sheet per file. 11 are planned: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta, Iceberg, Snowflake/BigQuery readers, a REST poller and a webhook receiver/gRPC (`docs/EDITIONS.md` SP-ACQ-*; plan §5).
  - Tests: 26 + 36 + 6 = **68 unit test classes**, 0 real-HTTP in these hubs (the HTTP routes are tested under module.processor). `KafkaRealBrokerTest` is opt-in and skips unless a broker is supplied; CI has none (`module/connectors-kafka/plan.md`).
  - The acquisition ledger defaults to **memory** (`AcquisitionLedgers.java:199`, `acquire.ledger.backend` defaults to `memory`). No launcher, offering or script sets `db`. Plan §5 flags that the completeness KPI cannot be computed on a fresh install.
  - Open rows: P3 `SCALEOUT-U1-REMOTE-ACQUIRE-1` (two pods would both fetch the same remote file) and the untagged P3 "Unpack (11) absent codecs" (no xz/zstd; multi-part archives). No open defects.
  - Security: SFTP host-key pinning and bastion tunnel. Secret references `${ENV}`/`${SYS}` on every edition and `${FILE}`/`${KEYSTORE}` on Standard and up (EDITIONS SEC-07). The Safety Policy checks Kafka partition-leader egress (connectors-kafka plan, 2026-10-06).
- **Strengths vs market:**
  - File-oriented acquisition (dedup by etag/version, watermarks, gap detection, push notify, Unpack with caps) covers the "mediation-lite" feed path that RA/FM buyers ask for. The assurance vendors often rely on a separate mediation layer (Neural Optimus sells one; *general market*).
  - Air-gap friendly: every connector is in-process, with no SaaS runtime dependency.
- **Gaps vs market / RFP risk:**
  - No CDC (Debezium), message queues (MQ/RabbitMQ/SQS/Kinesis) or REST/API poller. Declared as SP-ACQ-10..20 on the EDITIONS board, **no BACKLOG row**.
  - Kafka is not proven against a real broker in CI. **No row** (only the EDITIONS SP-ACQ-09 🟡 cell).
  - Remote acquisition is not distributed (`SCALEOUT-U1-REMOTE-ACQUIRE-1`). The ledger defaults to memory. **No row** for flipping that default.
  - Credentials and network profiles are not separate reusable resources, and a Collector is not run-gated on an invalid Connection (plan §5 "UNTRACKED" table; area-specific, **no BACKLOG row**).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| SFTP/FTP(S) file collection with dedup and restart safety | Comply | SP-ACQ-02, ACQ-3 ledgers/dedup. Set `acquire.ledger.backend=db` for a durable ledger. |
| Database extraction (JDBC, incremental) | Comply | SP-ACQ-03, watermark column |
| Object storage (S3/GCS/Azure) | Partial | Connectors are shipped and tested, but no proven end-to-end acquisition-node run (plan §5) |
| Streaming (Kafka) ingestion | Partial | Slice-level at-least-once and a lag gauge are built. Proven only against MockConsumer; Kafka is not in Basic. |
| CDC / message queues / REST APIs | Roadmap | SP-ACQ-10..20 planned, none scheduled |

- **To reach the next level:** (1) Make the `db` ledger the edition default, or set it in the Standard/Advanced launchers. (2) Add a real-broker Kafka leg and an S3 (MinIO) end-to-end acquisition-node run to CI. (3) File a row for one CDC or REST-poller connector and build it.

#### cap.pipelines — Pipelines & processing (module.etl, module.engine, module.config)
- **What it is (customer view):** Visual and TOON-authored Pipelines that parse many formats, then cast, validate (Expectations), transform, enrich, route (Decision Rules) and write partitioned Parquet. They are validated before save, can be dry-run or test-run, and are scheduled as Jobs.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 4 → **Level L3 (Pilot-ready)**, capped by E and by the platform baseline
- **Evidence:**
  - 46 built-in node types. 10 parser frontend tokens across 8 formats: delimited, fixed-width, JSON, text-regex, xlsx, Parquet, ASN.1, plugin (`module/etl/plan-ingestion.md` §2; `module/etl/plan-pipeline-authoring.md` §2.2).
  - Step Processor board: **119 processors, 51 delivered, 17 partial, 51 planned** on the Advanced column (`docs/EDITIONS.md` §Step Processors, generated from `ProcessorCatalog`). The planned share is heaviest in parsers (9), transforms (10), enrichment (7) and sinks (6).
  - Requirements of record ING-1..6 and PIP-1..7 are recorded as shipped (plans §2). The Expectations UI exists now (`inspecto-ui/src/app/inspecto/api/expectations.service.ts`, routed in `app.routes.ts`). That makes the plan's "ING-6 has no UI" claim stale.
  - Tests: engine **369**, etl **104**, config **22** unit test classes. 18 test classes reference golden fixtures (for example the `asn-golden` `SyntheticCorpusTest`). The nightly CI benchmark floors run `PluginIngestBenchmark` at 1M rows (floor 50,000 rows/s) and `MixedLoadBenchmark` (floor 30,000 rows/s, p95 ceiling 5 s). Both were green on the GitHub runner on 2026-10-08, 10-09 and 10-10 (`.github/workflows/nightly-perf.yml`; BACKLOG `ASSURE-OPERABILITY-1`).
  - Measured throughput: native DuckDB ingest of 2M rows × 12 columns in about 3.5 s, and 1.0–1.4M rows/s for transform and write (`layer/platform/tests-performance.md`).
  - Config safety: `ConfigSafetyValidator`, path jail, fail-closed schema types (`module/config/spec-config-safety.md`). Config writes can be held for maker-checker (item 6).
  - Open rows: P2 `AUTHORING-REDESIGN-1` (remaining letters parked on demand), P2 "Postgres multi-user", P2 `SCALEOUT-OBJSTORE-DATABASE-DIR-1` (operator hold), P3 defect `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` (a duplicate output after failover re-plan; operator hold).
  - Replay is bounded. `JobService` resolves runs from an in-memory map capped at `LIVE_RUN_CAP = 1000` (`JobService.java:327`), so replay after a restart or eviction returns 404 (`module/engine/plan-pipeline-execution.md` §2.3).
- **Strengths vs market:**
  - A full ingest → Pipeline → data-quality → lineage platform under the assurance controls. The competitive matrix (§3) names this as something none of the six vendors shows publicly.
  - The authoring loop (validate → dry-run → test-run to a node → arm) is fail-closed, and save-time safety checks are pinned by tests.
- **Gaps vs market / RFP risk:**
  - Outbound writes are thin: no JDBC/SQL sink, no Iceberg/Delta write, and the multi-destination `sinks:` executor is not wired (`module/connectors/plan-warehouse-targets.md` §2, §7). **No BACKLOG row** (EXPORT-1 closed 2026-09-24, covering only object-store export).
  - 51 of 119 catalogued processors are planned, not built (EDITIONS board). Planned work has rows only by family. The processor-catalog item in the module.etl hub has no row id.
  - Record-level replay from quarantine is deliberately not built without a driver (engine plan §5.2). Run replay is in-memory: **no row**.
  - Single-node execution. Distributed scheduler coordination has **no BACKLOG row** (engine plan §5.1). Only the Postgres run lease exists, for active/passive.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Multi-format parsing incl. telecom (ASN.1, fixed-width, CSV, JSON) | Comply | 8 formats, golden tests (plan-ingestion §2) |
| Visual pipeline design with validation and test runs | Comply | CP-02/CP-03 ✅ on all editions; 41 Pipeline UI spec files |
| Data quality rules and reject handling | Comply | Expectations (server + UI), SCH-02 quarantine routing |
| Throughput at telco volume (billions of records a day) | Partial | 140–260K rows/s measured on one 12-core host, 1M-row nightly floor. No multi-node or TB-scale evidence. |
| Load results into the customer warehouse (JDBC, Iceberg, Snowflake) | Partial | Parquet drop and `publish.postgres` only. "Lands Parquet your warehouse loads natively" (warehouse-targets §7). |

- **To reach the next level:** (1) Build the `sinks:` executor and a JDBC sink. (2) Persist the replay lookup, or narrow the CP-03 claim. (3) Record a sustained multi-hour, TB-class run on server hardware.

#### cap.analytics-bi — Analytics, BI & Studio (module.analytics, layer.product)
- **What it is (customer view):** Studio for Datasets, Measures, Queries, Widgets and Dashboards, with KPIs and reports, scheduled delivery, public embed and a Catalog. It sits on an Analytics Kernel of statistics, forecasting, clustering and anomaly primitives, and can publish to an external BI tool.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - **20 Visualization Types** in `BUILTIN_VIZ_PLUGINS` (`inspecto-ui/src/app/inspecto/viz/plugins/index.ts`): kpi, table, bar, line, area, pie, bubble, gauge, scatter, funnel, kpi-trend, progress-list, treemap, waterfall, combo, heatmap, geo-map, link-analysis, reconciliation, working-set. `layer/product/plan-studio.md` BI-2 still says 13, so it is stale.
  - BI-1..BI-8 are shipped (`plan-studio.md` §2). Drill-through, time grain on the wire, PNG export. Scheduled reports in json/csv/png/pdf. Measure alerting (whole-dataset only). Semantic BI API `POST /bi/query`. HMAC-signed, expiring, fail-closed public share. Only 3 curated templates.
  - Server-side Measures: `MeasureCompiler` with window, recency and ratio Measures, plus 4 test classes (`platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`). The plan's "no server Measure entity" line is stale.
  - Analytics Kernel: Musts M1–M10, Shoulds S1–S8 and Coulds C1–C6, C8 and C9 are built, among them forecasting with backtest, k-means, logistic regression, isolation forest, change-point and cohort/funnel (`module/analytics/plan.md`). 41 unit test classes. A CI duplication ratchet guards it (`tools/check-data-ops-duplication.mjs`).
  - BI publication: the `publish.postgres` Job (`PostgresPublishJobType.java`) does full or partition-incremental refresh into Postgres for an external BI tool (EDITIONS JOB-06).
  - Masking on BI results: `BiResultMasking` and `ControlApiBiQueryMaskingTest` cover `/bi/query`, `/queries/{id}/run`, `/datasets/{id}/rows`, share queries, `/db/*` and Report exports (BACKLOG `BI-QUERY-MASKING-1`, built 2026-10-13, closed 2026-10-23 after its security review; open free-SQL channels: P1 `BI-MASKING-ROW-VALUE-LEAKS-1`, P1 `BI-MASKING-FREE-SQL-ORACLES-1`).
  - UI tests: 41 Studio spec files and 32 viz spec files. The only e2e file is `inspecto-ui/src/e2e/backend-smoke.spec.ts`, and it self-skips without `E2E_BASE_URL`. Nightly `MixedLoadBenchmark` drives 4 `/bi/query` readers during ingest (local p95 528–639 ms).
  - Open rows: P2 `ANALYTICS-CORE-1`, P1 `BI-MASKING-FREE-SQL-ORACLES-1` (free-SQL key oracles, hold: operator), P2 `AUTHORING-REDESIGN-1`, P3 `ANALYTICS-STEPS-TS-1`, P3 `PREDICTIVE-*` (design only), P3 `PACK-SALES-MARKETING-1`.
- **Strengths vs market:**
  - Integrated BI over the platform's own lake, with classification-driven masking of result rows. Assurance vendors usually bundle dashboards without per-viewer key masking *(general market)*.
  - Explainable statistical analytics in-process, with no external ML runtime (refusals W1–W2), which suits air-gapped buyers.
- **Gaps vs market / RFP risk:**
  - Visualization and ad-hoc self-service depth is a fraction of Power BI or Tableau *(general market)*. The mitigation is `publish.postgres` into the customer's BI tool. **No row** for closing the gap natively, and that is reasonable.
  - Dashboard and Widget sharing/RBAC beyond the Exchange axis is not built (`plan-studio.md` §5.1, pointing at "SEC" rather than a row id): **no row id**.
  - Predictive analytics (churn, segmentation, forecast surfaces) is design-only: `PREDICTIVE-ANALYTICS-1` and its sub-rows. The competitive matrix (row V) shows LATRO and Mobileum offer it.
  - BI-4/6/7 (scheduled delivery, public share, headless BI) are ungated on Basic. This is by edition design (`EDITIONS.md` SPEC-GREENCELL-1), so the RFP answer must be worded per edition.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Configurable dashboards and KPIs, drill-down | Comply | BI-1/BI-3, 20 viz types |
| Scheduled reports (PDF/CSV) by email | Comply | BI-4. Email needs `inspecto-notify-channels` (Standard and up, CP-15 🟡). |
| Ad-hoc query and self-service analytics | Partial | Query Library, `/bi/query`, Explore. Not a full self-service BI tool. |
| Integration with the enterprise BI tool | Comply | `publish.postgres` Job (JOB-06, Standard and up) |
| Predictive / ML analytics | Partial | Kernel primitives are built. Predictive add-on surfaces are design-only (`PREDICTIVE-ANALYTICS-1`). |

- **To reach the next level:** (1) Add a real UI e2e leg in CI against a seeded server, covering dashboards, reports and shares. (2) Close the free-SQL masking rows (`BI-MASKING-ROW-VALUE-LEAKS-1`, `BI-MASKING-FREE-SQL-ORACLES-1`) and the dashboard-sharing RBAC gap. (3) Correct the stale counts in `plan-studio.md` (viz count, Measures).

#### cap.identity — Identity, access & security (module.access, module.oidc, module.policy, module.secrets, module.auth-spi)
- **What it is (customer view):** SSO through the customer's IdP (OIDC), role- and attribute-based authorization with row and Space scoping, a policy editor with impact preview, an access-review view, secret references, TLS, and classification-driven masking.
- **Scores:** F 4 · B 4 · Q 4 · E 4 · R 3 → **Level L3 (Pilot-ready)**. B and E would allow L4. It is held at L3 by the platform cap and by having no independent pen test.
- **Evidence:**
  - Security requirements: SEC-1..SEC-7 and SEC-9 are shipped. SEC-8 (secrets) is partial by design: Vault/KMS is not built and is reserved for Advanced when a client requires it (`module/access/plan-security.md` §2).
  - The OIDC resource server has been **proven live against WSO2 IS 7.3.0 and Keycloak** (`module/oidc/plan.md`; `OidcAgainstRealProviderTest`, `OidcKeycloakAgainstRealProviderTest`). 8 test classes, 1 real-HTTP. Entra and Okta go through the `generic` preset and are unverified.
  - ABAC (`inspecto-policy`) is Advanced only (SEC-04). The policy authoring UX with guards and `POST /access/policies/preview` shipped 2026-09-25 (SEC-05). 6 test classes, 5 real-HTTP.
  - Route gating: a mutating route with neither a capability nor a recorded exemption fails the boot (`UndeclaredMutatingRouteTest`, `RouteInventoryTest`; `compliance/evidence/route-gating.md`). In `processor/api-routes.md`, 140 of 178 mutating routes declare a capability. The other 38 are previews, auth or self-service routes.
  - Masking: `AuditReadMasking`, `BiResultMasking`, `ObjectReadMasking`, `NotificationContentMasking`, `BiReportRowMasking` in `inspecto/src/main/java/com/gamma/control/`, each with a real-HTTP test. The `quality.pii.mask` Step is gated to Advanced (SEC-08 🟡).
  - HTTPS is proven at TLS 1.3, including fail-closed on a broken keystore (`ControlApiHttpsTest`, 5 tests, mutation-proven). BFF sessions use an httpOnly, SameSite=Strict cookie (SEC-5).
  - Open rows: P1 `BI-MASKING-FREE-SQL-ORACLES-1` (free-SQL key oracles, hold: operator) and P3 `IMPORT-WHOLE-SPACE-SPLIT-1` (demand). Untracked in the plan: `SEC-SIDECAR-BOOT-1` (a Standard bundle cannot boot without an IdP), and `permissions[]` emitted but never read.
- **Strengths vs market:**
  - Policy-as-config ABAC with an impact preview and self-lockout refusal, plus maker-checker on access changes. The competitive matrix (§3) notes that none of the six vendors publicly shows four-eyes on configuration.
  - Masking is driven by field classification across BI, audit, operational objects and notifications, not configured screen by screen.
- **Gaps vs market / RFP risk:**
  - No independent penetration test: a BACKLOG §2 gate row, "Compliance program (NFR-7)", N3.
  - Vault / cloud KMS secret provider is not built (GAP-6, a §6 standing note). FIPS mode is open (controls-matrix G9; demand-gated).
  - Native SAML, LDAP and SCIM are absent by design ("IAM's job", `EDITIONS.md` §Matrix). This is acceptable for the IdP-federated pattern but needs a caveat in RFPs that list SAML. **No row** (a stated position).
  - ABAC and masking are Advanced-only, so a Standard bid cannot claim row scoping or column masking (EDITIONS SEC-04/SEC-08).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| SSO with the corporate IdP (SAML/OIDC, MFA) | Comply (OIDC) / Partial (SAML) | Live-proven on WSO2 and Keycloak. SAML and MFA are brokered by the IdP. |
| Role-based access control with segregation of duties | Comply | RBAC from claims (SEC-03), maker-checker where the author cannot approve |
| Data-level security (row scope, column masking of PII) | Comply (Advanced) / Not offered (Basic, Standard) | SEC-04/SEC-08 Advanced-only |
| Secrets and key management (HSM/Vault) | Partial | ENV/FILE/KEYSTORE. Vault/KMS when a client requires it (GAP-6). |
| Independent penetration test report | Roadmap | NFR-7 · N3 open |

- **To reach the next level:** (1) Commission a pen test and record it against NFR-7 · N3. (2) Build the Vault provider behind the existing `SecretsProvider` SPI. (3) Run a live check against an Entra or Okta tenant.

#### cap.operations — Operations & observability (module.observability, module.backup, module.processor, module.event)
- **What it is (customer view):** The Control API and Job scheduler (cron, event and signal triggers, maintenance library), health and readiness probes, Prometheus metrics, events feed, audit search/export, notifications, and hash-verified backup/restore.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Control API v1 (JDK HttpServer, envelope, ETag/If-Match, idempotency keys; EDITIONS CP-01). module.processor registers **343 routes, all 343 in the OpenAPI contract** (`module/processor/api-routes.md`).
  - Jobs: 12 Job Types, 23 maintenance tasks. Triggers are cron, `on_pipeline`, `on_signal` with guards, catch-up and manual (EDITIONS JOB-01..04; engine plan §2.2). Concurrency defaults to 4 and the intake cap to 500 files per cycle.
  - Tests: processor **350 classes (221 real-HTTP)**, observability 8 (5 real-HTTP), backup 10 (1 real-HTTP), event 8. That adds up to 405 test classes across the repo that drive a real `ControlApi`.
  - Backup: per-file and archive SHA-256 sidecar, fail-closed restore into a new Space, a `maintenance_backups` catalog, and secrets excluded (`module/backup/plan.md`; `BackupTaskSecretExclusionTest`). Standard and up only (OPS-06).
  - **No restore drill recorded** (`compliance/evidence/rto-rpo-statement.md` §4: "none recorded yet"). The RTO/RPO targets are operator-fill fields.
  - The service wrappers (systemd plus a Windows Scheduled Task) shipped, but their live acceptance (`kill -9`, reboot) is **unrun** (P2 `DEPLOY-SERVICE-WRAPPER-1`).
  - Retention: the `event_prune` one-year window and 23 maintenance tasks, all **operator-scheduled; nothing prunes by default** (controls-matrix G5).
  - No OpenTelemetry tracing and no shipped Grafana dashboards: a grep for OpenTelemetry/traceparent finds nothing in the Java sources, and `deploy/` holds only the Helm chart.
- **Strengths vs market:**
  - Everything is an API with a contract test against the live server (`ApiContractTest`, `OpenApiPathsContractTest`). Health detail exposes store degradation and the run-lease database state.
  - Backup and maintenance are first-class Jobs with signals, not shell scripts, so they are auditable and chainable.
- **Gaps vs market / RFP risk:**
  - Restore drill and RTO/RPO targets are not recorded (controls-matrix G6, operator-owned; part of the P2 `ASSURE-OPERABILITY-1` family).
  - No distributed tracing and no packaged monitoring dashboards: **no row**.
  - Replay is bounded in memory (see cap.pipelines): **no row**.
  - Service-wrapper live acceptance is unrun: `DEPLOY-SERVICE-WRAPPER-1`.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Monitoring integration (Prometheus/SNMP/APM) | Partial | Prometheus `/metrics` (Standard and up). No tracing, no SNMP. |
| Job scheduling with dependencies and retries | Comply | JOB-01..04. Retry is per Job, and a per-Pipeline retry block is deferred (engine plan §5.2). |
| Backup/restore with verification | Comply (capability) / Partial (proof) | OPS-06, hash-verified, but no drill recorded |
| Alerting/notification to email/webhook | Comply (Standard and up) | CP-12/CP-15 (CP-15 🟡) |
| Documented REST API | Comply | OpenAPI 3.1, 468 paths. 430 of 559 operations still carry generated-skeleton summaries. |

- **To reach the next level:** (1) Run and record a restore drill and the service-wrapper acceptance on Linux and Windows. (2) Add OpenTelemetry spans and a reference Grafana dashboard. (3) Replace the skeleton OpenAPI summaries for the 50 most-used routes.

#### cap.workflow — Workflow & maker-checker (module.workflow)
- **What it is (customer view):** Four-eyes approval of configuration changes (Pending Changes), plus authored lifecycles, SLA Policies on a business calendar and Escalation Rules for Incidents and Cases. A graphical state editor is included.
- **Scores:** F 3 · B 5 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**. B and E would allow L4. Held at L3 by the platform cap and by thin live evidence.
- **Evidence:**
  - `ASSURE-MAKER-CHECKER-1` shipped 2026-09-27, and its residual rows closed by 2026-10-06. `ASSURE-WORKFLOW-SLA-1` closed 2026-10-04. The graphical state editor shipped 2026-10-11. **No open rows** (`module/workflow/plan.md`; hub index).
  - Maker-checker code: `PendingChanges`, `ApprovalPolicy`, `ApproverCheck`, `ApproverRoster` (`inspecto/src/main/java/com/gamma/control/`). The author cannot decide their own change, and the original request is replayed on approval (`module/workflow/features-maker-checker.md`).
  - Tests: 4 unit classes in `platform/inspecto-workflow` (SlaDecisions, SlaPolicy, Workflow, WorkflowValidation). Core has `ControlApiPendingChangesTest`, `PendingChangesMultiPodTest`, `PendingChangesLockTimeoutTest` and `PendingChangesKeyTest`. Engine has `ApprovalGoldenVectorTest` and ops has `ControlApiWorkflowStrandTest`. About 12 classes in all.
  - Deciding needs an Authenticator, so maker-checker cannot be switched on through the API on Basic. The SLA sweep runs in `inspecto-ops` (Standard and up, EDITIONS CP-17).
  - Re-checking a deleted assignee was retired 2026-10-18 because no principal directory exists (plan §Left).
- **Strengths vs market:**
  - The competitive matrix rates workflow and SLA "Y" for Inspecto against at most "P" for all six vendors (row O). Four-eyes on configuration is not shown publicly by any of them (§3).
  - The same approver roster and per-Space HMAC are reused by Action Requests and Regulatory Reporting, so approvals stay consistent.
- **Gaps vs market / RFP risk:**
  - Not a general BPM engine: no BPMN, no multi-level or parallel approval chains, no forms designer, and no user directory for assignment, unlike ServiceNow-class tools *(general market)*. **No row** (scope position).
  - Workflows govern only Incident, Case and Task objects (`features-workflow-sla.md`). **No row**.
  - Live evidence is thin: no recorded demo of an SLA breach and escalation run in the hub. **No row**.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Four-eyes / maker-checker on configuration | Comply | Pending Changes, governable kinds per Space (Standard and up, which need an IdP) |
| Configurable case/incident workflow | Comply | Authored `workflow` component, state editor |
| SLA timers with business calendar and escalation | Comply | `SlaPolicy`, `EscalationRule` BREACH/AGE |
| Multi-level / parallel approvals | Not offered | Single second-person approval |
| Assignment rules against a user directory | Partial | Assignment exists. No principal directory; identity comes from the IdP. |

- **To reach the next level:** (1) Record an end-to-end SLA breach → escalation → approval demo on a seeded Space. (2) Decide whether multi-level approval is in scope and file it as a row either way.

---

#### PLATFORM ENTERPRISE BASELINE (all editions; referenced by every other group)
- **What it is (customer view):** The enterprise properties every module inherits: SSO, RBAC/ABAC, masking, a tamper-evident audit trail, HA/DR, backup, scale evidence, edition packaging, air-gap, compliance mapping and a stable API.
- **Scores (platform as a whole):** **E 3 · R 3** → **Level L3 (Pilot-ready)**. Strong on controls; HA/DR, scale-out and certification are unproven.

| Area | State | Evidence |
|---|---|---|
| SSO / OIDC | ✅ live-proven | WSO2 7.3.0 and Keycloak (`module/oidc/plan.md`). Standard and Advanced only; Basic is auth-free by design and binds every interface unless `-Dcontrol.bind` is set (`EDITIONS.md` §Matrix). |
| RBAC / ABAC | ✅ RBAC Standard and up, ABAC Advanced | SEC-03/04/05/06. Route gating is enforced at boot (`compliance/evidence/route-gating.md`). |
| Masking | 🟡 Advanced | SEC-08. Read masking across BI, audit, objects and notifications. `BI-QUERY-MASKING-1` closed 2026-10-23; free-SQL key channels stay open as P1 `BI-MASKING-ROW-VALUE-LEAKS-1` and `BI-MASKING-FREE-SQL-ORACLES-1`. |
| Audit chain | ✅ tamper-evident, not tamper-proof | `ASSURE-AUDIT-CHAIN-1`: a per-Space hash chain with MAC-signed daily anchors, checked by `GET /audit/verify` (`AuditVerifier`, `ControlApiAuditChainTest`). Not WORM, not non-repudiable, and events are dropped after 50k buffered on sustained flush failure (controls-matrix AU-9). ⚠ `EDITIONS.md` SEC-09 still says "not tamper-evident", which is stale. |
| HA / DR | 🔴 runbook plus a laptop-scale drill | `layer/platform/admin-guide-ha-dr-drill.md`. Lease takeover took about 60 s in a two-process, one-machine drill (2026-10-10). The two-host drill, standby promote and partition test were **not run**. Single-node is the stated posture (NFR-8). Helm is a replicas-1 StatefulSet (`SCALEOUT-HELM-NPOD-1`, P2). Postgres multi-user is P2. |
| Backup / restore | 🟡 built, no drill | OPS-06 (Standard and up). `rto-rpo-statement.md` §4 has no drill and no committed targets (G6 half-open). |
| Scale / performance | 🟡 single-host only | Nightly floors (50K rows/s solo, 30K rows/s mixed with 4 BI readers) green on CI. Local 140–260K rows/s at 1M rows (`nightly-perf.yml`, `tests-performance.md`). No multi-node, sustained or TB-scale evidence. |
| Editions / packaging | ✅ | Basic, Standard, Advanced as build flavors with 23/42/45 jars. Offerings matrix generated and CI-guarded (`EDITIONS.md` §Staged jars). jlink runtime 🟡 until the first tagged release (OPS-07). |
| Air-gap | ✅ packaging guarantee | `compliance/evidence/air-gap-posture.md`. `EgressGuardTest` proves hosted-AI SDKs are absent. Offline basemap. Explicitly **not** a runtime egress control. |
| Supply chain | ✅ built, 🔴 never exercised end to end | SBOM per bundle in CycloneDX and SPDX (G1). Signed-release workflow (G3), **never executed**: no `v*` tag since it landed. Offline OSV vulnerability scan wired into CI, but needs a snapshot (`ASSURE-OPERABILITY-1`). |
| Compliance mapping | 🟡 mapped, not certified | `compliance/controls-matrix.md` maps SOC 2, ISO 27001:2022 and NIST 800-53. G1–G5, G7, G8 and G10 are closed. G6 is half-open (drill). G9 (FIPS) is open. The SOC 2 Type II window has **NOT STARTED** (§4a). NFR-7 N1–N7 (pen test, auditor, policies) are all open. |
| API stability / OpenAPI | 🟡 | OpenAPI 3.1 is generated from module fragments: 468 paths, 559 operations, enforced by `ApiContractTest`, `ContractFragmentsTest` and a CI guard. **430 of 559 operations carry "generated skeleton" summaries, and about 41 have a typed 2xx schema.** `X-Actor` removal waits for the next major version (SEC-11). |

- **Strengths vs market:** Four-eyes on configuration and outbound actions, a tamper-evident audit chain, air-gapped editions with SBOM and signing built in, and an honest, self-caveating controls matrix. The competitive matrix (§3) says none of the six vendors shows these publicly. CI runs 40+ guards (`.github/workflows/ci.yml`) and enforces coverage floors.
- **Gaps / RFP risk:** HA/DR is unproven on real hosts (`ASSURE-OPERABILITY-1`, `SCALEOUT-HELM-NPOD-1`, `SCALEOUT-D14-D16-DECISIONS-1`). No certification, auditor or pen test (BACKLOG §2 "Compliance program (NFR-7)", "SOC 2 Type II window"). The release-signing path has never run (no row; the caveat sits in the controls matrix G3). Masking and ABAC are Advanced-only. The OpenAPI descriptions are thin (**no row**).
- **RFP stance on the most-asked enterprise requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| SSO + RBAC + audit trail | Comply (Standard/Advanced) | OIDC live-proven, route gating at boot, audit chain with `/audit/verify` |
| High availability / DR with stated RTO/RPO | Partial | Active/passive runbook. Targets in plan-editions §3.14 (RPO 5–15 min, RTO ≤ 30 min) are **not yet demonstrated** on two hosts. |
| Horizontal scale-out | Roadmap | Phase C scale-out. Helm is replicas-1. |
| SOC 2 / ISO 27001 certification | Roadmap | Control mapping exists. Window not started, no auditor. |
| On-prem / air-gapped deployment | Comply | Air-gap posture evidence, offline bundle, SBOM per bundle |

- **To reach the next level (L4):** (1) Run the two-host Linux drill (D1–D6) and a restore drill, then commit the RTO/RPO targets in `rto-rpo-statement.md`. (2) Cut a `v*` tag so the signing, SBOM and vulnerability-scan release path actually runs. (3) Engage an auditor and commission a pen test (NFR-7 N2/N3), then open the SOC 2 window.

---

**Group notes.** Engineering discipline is unusually strong: ~1,575 Java test classes, ≈ 400 of them driving a real `ControlApi`, plus 40+ CI guards, coverage floors and nightly performance floors. The platform's ceiling is operational proof, not code: no two-host HA drill, no restore drill, a release-signing path that has never run, no pen test, and a SOC 2 window that has not started. That is why every capability is capped at L3. Several hub docs are stale against the code. `plan-studio.md` gives 13 viz types (the code has 20). `plan-ingestion.md` says Expectations have no UI (they do). `EDITIONS.md` SEC-09 says the audit log is not tamper-evident (the chain shipped 2026-09-27). Other groups should cite code, not those lines. Not verified: whether any Kafka real-broker or S3 end-to-end run was ever executed off-CI; Entra/Okta compatibility; any customer-scale volume (no reference-customer evidence is recorded, so L5 is not assessable). Gaps with no BACKLOG row: CDC/REST/MQ acquisition, the JDBC/Iceberg sinks and `sinks:` executor, distributed scheduler coordination, the in-memory replay bound, OpenTelemetry tracing, the acquisition-ledger default, and OpenAPI description depth.

### B. Add-ons — operations, investigation and AI

#### addon.incidents — Incidents

- **What it is (customer view):** Alert Rules fire Alerts, and Alerts are promoted into Incidents. Analysts triage, assign, annotate,
  post-mortem and resolve Incidents in a mail-like inbox. Notifications and retention sit around that.
- **Scores:** F 4 · B 4 · Q 4 · E 4 · R 3 → **Level L3 (Pilot-ready)**, close to L4
- **Evidence:**
  - `addon/incidents/plan-incidents.md` §2: INC-1 Alert Rules and INC-2 Alert → Incident → Case are SHIPPED. INC-3 notifications and
    INC-5 Diagnosis are PARTIAL. INC-4 (queues, watchers) was WITHDRAWN 2026-09-14 under `RETIRE-HALVES-1`.
  - `module/ops/api-routes.md`: 43 routes, all 43 in the OpenAPI contract. `module/ops/feature-db.md` has about 55 feature rows.
  - `module/ops/tests-inventory.md`: 70 test classes (47 unit, 23 real-HTTP). They include `ControlApiIncidentFinishGateTest`,
    `ControlApiObjectReadMaskingTest`, `ControlApiScopedObjectsTest`, `PostgresTwoNodeSoakTest`, `ObjectAnalyticsGoldenTest` and
    `IncidentPurgeTaskTest`.
  - UI: 16 vitest specs under `inspecto-ui/src/app/modules/admin/objects/`, for example `object-mail`, `postmortem-panel`, `resolve.dialog`
    and `impact-panel`. Plus `settings/incident-governance.component.spec.ts`.
  - Retention: `incident_purge` with a fail-safe legal hold (MNT-14). The event ledger is append-only. By decision no purge job is shipped
    on a schedule (`module/ops/plan.md`).
  - Committed demo config: 2 Alert Rules under `spaces/demo/config/registry/alert-rules/`, plus
    `spaces/demo/config/ops/incident_burst_caserule.toon` and `critical_incidents_tagrule.toon`.
  - Open work is only P3 chores: `MODULE-REORG-P7-INCIDENTS` and `DOCS-SKILLS-ALERTS-AGENTIC-1` (`addon/incidents/index.md`).
- **Strengths vs market:**
  - Integrated Alert → Incident → Case chain inside the assurance product. The matrix rates RAFM peers P–Y on N (case) and N–P on O
    (workflow and SLA). Peers usually hand this off to an external ITSM.
  - Server-side resolve gate (four post-mortem sections) and MTTR analytics as a Dataset (`objects.analytics`). Object reads are masked
    and scoped.
- **Gaps vs market / RFP risk:**
  - No group or queue routing and no watchers. Assignment is to a person only, and on-call rotation is absent. These are standard
    ServiceNow/Jira lines (general market). Retired by decision, **no row** (plan says re-file on customer demand).
  - Diagnosis → Incident (the defining clause of INC-5) is absent. Verified: no `IncidentAccess`/`INCIDENT` reference in
    `features/inspecto-agent/.../diagnose/`. **No row**: it is kept in spec §5 as UNTRACKED.
  - No native ITSM connector (ServiceNow/Jira). The only routes out are the webhook channel and Action Requests. **No row.**
  - Retention covers `ObjectType.INCIDENT` only, has no UI and has no shipped Job (BACKLOG §6 `MNT-14`).
  - Per-user notification preferences: the plan says "one global set". Per-user read state shipped 2026-09-25 (BACKLOG line 388). Per-user
    *delivery* preferences are unverified.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Configurable lifecycle with assignment and SLA | Comply | ops workflow, plus the workflowSla add-on (`GovernanceSweepTest`) |
| Group or queue routing, on-call escalation | Partial | escalation rules can reassign; queues and watchers retired (`RETIRE-HALVES-1`) |
| Email and webhook notifications, per-user preferences | Partial | channels on Standard and Advanced; preferences global (plan §2 INC-3) |
| Audit trail and record retention / legal hold | Comply | `OBJECT_*` events in an append-only ledger; MNT-14 legal hold (Incident only) |
| MTTR / KPI reporting | Comply | `objects.analytics` → `ops_analytics`, `ObjectAnalyticsGoldenTest` |

- **To reach the next level:** (1) Decide on team queues or assignment groups and file a row. (2) Build Diagnosis → Incident (deduped)
  or retitle INC-5. (3) Add one end-to-end delivery test against a real SMTP and webhook endpoint (plan §8.7 names it absent).

---

#### addon.caseManagement — Case Management

- **What it is (customer view):** related Incidents are grouped into a Case for one business investigation. The add-on covers merge and
  split, Case Rules, dispositions, Findings fields, and opening a Case from Link Analysis entities.
- **Scores:** F 3 · B 3 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/case-management/plan.md`: roadmap C1–C6, I1 and I2 shipped 2026-07-12. The `caserule.evaluate` Job Type exists.
    `POST /cases/from-entities` is capped at 100 entities. Optimistic lock returns 409, and a real-Postgres two-node soak was run on one
    Case.
  - `module/case-management/api-routes.md`: 7 routes. `feature-db.md` has about 8 rows.
  - `module/case-management/tests-inventory.md`: 21 test classes (15 unit, 6 real-HTTP). They include `CaseRuleEvaluateGoldenTest`, the
    TCK and manifest-parity tests, `ControlApiCaseTriageGateTest` and `ControlApiScopedCaseMergeTest`.
  - UI specs: `merge-cases.dialog`, `case-contents`, `case-analytics.dialog`, `findings-spec-editor` (2 specs).
  - Committed Case Rules: `spaces/demo/config/ops/incident_burst_caserule.toon` and
    `spaces/_templates/telco-fraud/config/ops/fraud_critical_burst_caserule.toon`.
  - Left (plan): the Incident resolution gate is "not started". The disposition list is not deployment-configurable. Case analytics as a
    Studio Dataset is still a draft.
  - Open: P3 defect `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (item 1 masking is done; upload hardening is open) and P3 `D6-REAL-USER-SESSION-1`
    (Findings fields accepted on proxy only).
- **Strengths vs market:**
  - A Case can be opened in place from a Link Analysis working set, and an entity's case history can be read (`GET /objects/by-entity`).
    That LA-to-case link is rare outside i2/Palantir-class tools (general market).
  - Rule-raised Cases group by offender (`CaseRuleGroupByTest`) and have custom Findings fields. Merge and split are capability-gated and
    scoped (SEC-7d).
- **Gaps vs market / RFP risk:**
  - Attachments are references only, with no document upload (refused by design, `plan.md` §Refused). Actimize/SAS case managers store
    evidence documents (general market). **No row.**
  - Dispositions are not configurable per deployment, and the resolution hard-gate is not built. **No row** (listed only in plan Left).
  - No retention or legal hold for Cases: MNT-14 is Incident-only (BACKLOG §6 `MNT-14`). AML case records usually have multi-year retention.
  - No case queues, workload balancing or auto-merge suggestions (refused, design §3). Case-desk usability has never been tested with a real
    user (`D6-REAL-USER-SESSION-1`).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Group alerts or incidents into cases (manual and rule-based) | Comply | Case Rules plus `caserule.evaluate`, `ControlApiCaseRuleTest` |
| Evidence / document attachments | Partial | references only; no upload (refused) |
| Configurable case fields and dispositions | Partial | Findings spec is configurable; disposition list is fixed |
| Investigation link analysis from the case | Comply | `POST /cases/from-entities`, `ControlApiCaseFromEntitiesTest` |
| Case audit trail and retention | Partial | audited via the object ledger; no Case retention policy |

- **To reach the next level:** (1) File and build the Case retention / legal-hold extension of MNT-14. (2) Make the disposition list
  configurable and build the resolution gate. (3) Run the real Case-desk session (`D6-REAL-USER-SESSION-1`).

---

#### addon.workflowSla — Workflow & SLA

- **What it is (customer view):** authored lifecycles (Workflows), SLA policies counted in a business calendar, and Escalation Rules on
  Incidents and Cases. A background sweep stamps deadlines and escalates once per breach.
- **Scores:** F 3 · B 4 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/workflow/plan.md`: `ASSURE-WORKFLOW-SLA-1` closed 2026-10-04. `ASSURE-MAKER-CHECKER-1` shipped 2026-09-27. The graphical
    state-diagram editor shipped 2026-10-11 (`workflow-state-editor.component.ts`). `ASSURE-WORKFLOW-SLA-RESIDUALS-1` closed 2026-10-18.
  - `features/inspecto-ops/.../module.toon` has `configKinds[3]: workflow,sla-policy,escalation-rule`. The add-on feature-db has about
    70 lines (`addon/workflowSla/feature-db.md`).
  - Tests: `module/workflow/tests-inventory.md` lists 4 unit classes (`SlaDecisionsTest`, `SlaPolicyTest`, `WorkflowTest`,
    `WorkflowValidationTest`). The ops module adds `GovernanceSweepTest`, `GovernanceRegistrySameTickTest`, `ControlApiWorkflowStrandTest`
    and `WorkflowConfigLoadTest`. UI: `incident-governance.component.spec.ts`.
  - No Space Template ships an active Workflow, SLA policy or Escalation Rule, by design. Only illustrative
    `spaces/_templates/*/config/examples/sla-escalation-examples.md` files exist.
  - No open rows (`addon/workflowSla/index.md`).
- **Strengths vs market:**
  - The matrix row O rates the RAFM peers N/P and Inspecto Y. SLA runs in business calendars, escalation fires once, and config changes go
    through maker-checker four-eyes.
  - Governance kinds are written only through `canAdminister` routes, and authored changes are re-read without a restart.
- **Gaps vs market / RFP risk:**
  - Applies only to Incident and Case. Policies are keyed by the closed `ObjectType` enum, so reconciliation breaks and Entity List reviews
    are not covered (plan-incidents last para). **No row**: it waits for a second consumer.
  - No assignment groups, round-robin, skills routing or on-call calendars, which are ServiceNow-class basics (general market). **No row.**
  - A deleted assignee is not re-checked at sweep time. Retired 2026-10-18 because no principal directory exists to ask.
  - Thin direct test depth: 4 unit classes in the module and no real-HTTP class in `module.workflow`.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Configurable workflow states and transitions (graphical) | Comply | `workflow` kind plus the state editor (2026-10-11) |
| SLA targets per priority in a business calendar | Comply | `sla-policy`, `SlaPolicyTest`, `GovernanceSweepTest` |
| Escalation on breach (reassign, priority, notify) | Comply | `escalation-rule`, `OBJECT_ESCALATED` |
| Group / queue routing and workload balancing | Not offered | queues retired 2026-09-14 |
| Workflow on any work item (breaks, reviews, tasks) | Partial | Incident and Case only (Task has a type; generic contract deferred) |

- **To reach the next level:** (1) Add a real-HTTP test of the SLA sweep → breach → escalation chain. (2) Ship one optional, opt-in SLA
  example as a pack. (3) Decide on and file routing groups.

---

#### addon.actionRequests — Action Requests

- **What it is (customer view):** a governed outbound API call raised from an Incident or Case, by a person or by a Decision Rule's
  `invoke-api` consequence. It is sent only after a *different* person approves it, and every answer is recorded.
- **Scores:** F 3 · B 4 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/action-requests/plan.md`: `ASSURE-ACTION-REQUESTS-1` shipped 2026-09-27. `WEBHOOK-EGRESS-POLICY-1` closed 2026-09-27. The
    approver roster and `approverCheck` landed 2026-09-28 and 2026-10-04. Absent module → every route answers 503.
  - `module/action-requests/api-routes.md`: 7 routes, all in OpenAPI. `feature-db.md` has about 8 rows.
  - `module/action-requests/tests-inventory.md`: 8 classes (6 unit, 2 real-HTTP). They include `ActionDispatcherTest`,
    `InvokeApiConsequenceContractTest` and `ControlApiSpaceBundleActionRequestsTest`. TLS pinning and redirect refusal are tested in
    `HttpWebhookSinkTransportTest` and `PinnedHttpTlsTest` (`spec.md` §Deferred).
  - Gating: grep of the module source finds `canApproveChanges` (17 uses) and `canWorkIncidents` (6).
  - Known gaps, kept without rows (`spec.md` §Deferred): no per-request path below a Connection's base path, and no request history in a
    restored Space. No automatic re-send after a crash (refused).
  - No open rows.
- **Strengths vs market:**
  - Every outbound action is four-eyes approved and sent to an egress-policy-pinned `https` Connection with an idempotency key. The matrix
    notes no RAFM peer publishes four-eyes on outbound actions.
  - Detection → governed action closes the loop without an external SOAR.
- **Gaps vs market / RFP risk:**
  - No packaged ITSM or core-system adapters (ServiceNow, Jira, BSS barring). It is generic HTTP only. **No row.**
  - No bulk approve, no request templates library and no per-request sub-path. **No row** (sub-path is a known gap).
  - Request history is lost on Space restore. Known gap, **no row**.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Trigger an external action from a detection or case | Comply | `invoke-api` consequence, `InvokeApiConsequenceContractTest` |
| Maker-checker approval on actions | Comply | always-on four-eyes, `ActionRequestApproverCheckTest` |
| Secure, allowlisted egress | Comply | egress policy plus pinned TLS (`PinnedHttpTlsTest`) |
| Out-of-the-box ITSM / BSS connectors | Not offered | generic HTTPS Connection only |
| Full action audit and retry | Partial | every answer recorded; manual `mark-failed` then retry; history not kept across restore |

- **To reach the next level:** (1) Ship one packaged ITSM action template (ServiceNow or Jira) with a test. (2) Carry request history
  through Space backup and restore. (3) Add a real-HTTP test for the approve → dispatch → failure → retry path.

---

#### addon.aiAssist — AI Assist & Intelligence

- **What it is (customer view):** an Assistant with seven read-only or draft-only skills on Standard. Advanced adds an embedded
  intelligence layer: a 23-tool belt, inline natural-language authoring and an autonomy ladder. Every action is read-only or
  approval-gated, and local or air-gapped models are supported.
- **Scores:** F 2 · B 3 · Q 3 · E 3 · R 3 → **Level L3 (Pilot-ready, supervised)**
- **Evidence:**
  - `module/agent/plan-assistant.md` §2: AGT-1 (7 skills), AGT-2 (pluggable transport incl. Ollama), AGT-3 (air-gap; `EgressGuardTest`) and
    AGT-4 are built. AGT-6a is partial: `<inspecto-ai-assist>` on 5 panes and `<inspecto-ai-explain>` on 12. AGT-6b is only planned.
  - `module/intelligence/plan.md`: AGT-5 P0–P5 complete, with 23 tools pinned by `InspectoPackTest`. Egress masking
    (`AGENT-EGRESS-MASK-1`) and the model allowlist (`ASSIST-MODEL-EGRESS-1`) are in.
  - Packaging: `tools/bundle-modules.mjs` stages `inspecto-agent.jar` from Standard and `inspecto-intelligence.jar` from Advanced.
  - Tests (inventories): agent 28 classes (25 unit, 3 real-HTTP), intelligence 35 classes (all unit, 0 HTTP), agent-hosted 2. UI: about 15
    assist/ai specs.
  - `pom.xml:292` pins `<eoiagent.version>0.2.0-SNAPSHOT</eoiagent.version>`. CI rebuilds the upstream from a branch head
    (plan-assistant §2 EOI-7), so the runtime dependency is unpublished.
  - Open: P2 `AGENTIC-INVESTIGATION-1` (competitive gap AA), P2 AGT-5 per-tool dry-run seam (BLOCKED-EXTERNAL, BACKLOG line 552) and P3
    `AGT-SEGMENT-1`.
- **Strengths vs market:**
  - Air-gapped, local-model AI with hosted SDKs physically absent, proven by a test. That is a strong answer for regulated on-prem buyers.
  - An explicit governance model (approval inbox, autonomy ladder, egress masking of tool results) where many copilots are
    SaaS-only (general market).
- **Gaps vs market / RFP risk:**
  - No agent works an Incident: nothing explains, gathers evidence or proposes an action. Mobileum RAID Agentic and Subex AI Agent Squads
    have this (matrix row AA = P). Row `AGENTIC-INVESTIGATION-1` (P2).
  - Diagnosis does not open an Incident, and Diagnosis and the agent case store are 256-entry in-memory rings that are not durable
    (plan-incidents §5 *TriageRunStore*, parked). Embedding recall is parked.
  - Supply chain: the agent runtime is a SNAPSHOT built from source (EOI-7b open, plan-assistant §5.1). An enterprise security review will
    flag it.
  - The intelligence module has no real-HTTP tests, and the commercial tiering is unvalidated (`AGT-SEGMENT-1`).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Natural-language query and explanation of entities and alerts | Comply | `KpiToSqlSkill`, `ExplainEntitySkill`, `<inspecto-ai-explain>` on 12 panes |
| AI-assisted triage / investigation summary | Partial | `DiagnoseAndAlertSkill`, `ReportNarrativeSkill`; no Incident-working agent |
| Agentic automation with human approval | Roadmap | `AGENTIC-INVESTIGATION-1`; AGT-6b planned |
| On-prem / air-gapped LLM | Comply | Ollama provider, `EgressGuardTest` (AGT-3) |
| AI governance: audit, approval, data masking | Comply | approval gate, upstream EOI-4 audit, `AGENT-EGRESS-MASK-1`; dependency is a SNAPSHOT |

- **To reach the next level:** (1) Ship `AGENTIC-INVESTIGATION-1` with an eval suite. (2) Publish and pin a released eoiagent version
  (EOI-7b). (3) Make Diagnosis and triage durable (`TriageRunStore`) and add real-HTTP tests for `/agent/*`.

---

#### addon.anomalyDetection — Anomaly Detection

- **What it is (customer view):** an explainable 0–100 Anomaly Score per entity. It compares the entity with its own seasonal baseline
  and with its peers, can use an isolation forest, and can raise Incidents through Alert Rules.
- **Scores:** F 3 · B 3 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/anomaly/plan.md` lists what is built:
    - `ANOMALY-DETECTION-1` S1–S5 (2026-10-10)
    - `ANOMALY-PEER-GROUPING-1`: 200k entities in about 1–1.6 s
    - `ANOMALY-ML-SCORING-1`: `method: isolation_forest`, closed 2026-10-20
    - evidence rows masked at write
  - `module/anomaly/api-routes.md`: 2 routes. `feature-db.md` has about 4 rows.
  - `module/anomaly/tests-inventory.md`: 23 classes (21 unit, 2 real-HTTP). They include `AnomalyGoldenCorpusTest`,
    `AnomalyPeerGoldenCorpusTest`, `PeerSideEquivalenceTest`, `AnomalyForestScorerTest`, `PackFraudAmlAnomalyPropertyTest` and
    `TelcoFraudPackAnomalyPropertyTest`. UI: 6 specs.
  - Pack content: anomaly models in the `aml`, `telco-fraud` and `mobile-money` Space Templates
    (`spaces/_templates/*/config/registry/anomaly-models/`).
  - Live evidence: the `ANOMALY-UI-FINDINGS-1` live-verification lane on a demo build, 2026-10-13 (BACKLOG line 597).
  - Open P3 defects: `ANOMALY-TF-STORE-CLASH-1` (`AnomalyTemplateModelsTest...[1]` errors on clean master) and `ANOMALY-UI-FINDINGS-1`
    (live token-lapse check).
  - Masking and gates (`spec-anomaly-scores.md`): entity key masked unless `canRevealLinkEntities`. Read is gated by `canWorkIncidents` and
    preview by `canAuthorWorkbench`. Score output has "no retention" (line 164).
- **Strengths vs market:**
  - Explainable per-feature scores that can be recomputed, with peer-cohort shift and masked evidence rows. That transparency is a
    regulator-friendly answer that black-box ML vendors struggle with (general market).
  - Ships pre-wired in three industry packs and feeds the Alert → Incident chain without new plumbing.
- **Gaps vs market / RFP risk:**
  - Batch and daily only: hour buckets are deferred (BACKLOG §6) and there is no real-time scoring. FICO Falcon and Feedzai score in-stream
    (general market). See `REALTIME-DECISIONING-1`; no anomaly-specific row.
  - No supervised models, feedback loop from case dispositions, model drift monitoring or champion/challenger. **No row.**
  - Score history has no retention policy. **No row.**
  - A test errors on clean master (`ANOMALY-TF-STORE-CLASH-1`). The matrix row L still says "statistical, not ML", which is stale since
    2026-10-18.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Unsupervised / behavioural anomaly detection per entity | Comply | robust baseline plus isolation forest, golden corpora |
| Explainability of each score | Comply | per-feature explanation, `AnomalyScorer.recompute`, evidence rows |
| Peer-group analysis | Comply | Peer Groups, `PeerSideEquivalenceTest`, 200k in about 1.6 s |
| Real-time / intraday scoring | Not offered | daily buckets; hour buckets deferred |
| Model lifecycle (feedback, drift, champion/challenger) | Not offered | no row |

- **To reach the next level:** (1) Fix `ANOMALY-TF-STORE-CLASH-1` and close the live (h) check. (2) Add score-history retention and a
  disposition → model feedback signal. (3) File a sub-day scoring row against a named pack need.

---

#### addon.multiEntity — Multi-entity / Group

- **What it is (customer view):** group companies, or a regulator and its reporting entities, share a Dataset, Widget, saved view or
  Signal type between Spaces. Sharing is per item, opt-in and read-only, through approved Share Grants, as a snapshot or live.
- **Scores:** F 4 · B 4 · Q 4 · E 4 · R 4 → **Level L3 (Pilot-ready)** — module scores at L4. *(Calibrated 2026-10-20 to **L3**: the platform enterprise baseline is L3 and no item can exceed it — this item's own scores support **L4 once the platform gaps in §4.1 close**.)*
- **Evidence:**
  - `module/exchange/plan.md`: the offer / request / approve ledger is built, with snapshot and live delivery and version pin with drift.
    It also lists `EXCHANGE-OWNING-SPACE-AUTHZ-1` (P1, closed 2026-09-28), `SHARE-SNAPSHOT-ON-APPROVE-1`,
    `EXCHANGE-SNAPSHOT-MULTIPOD-1` and `BI-MASKING-EXCHANGE-SNAPSHOTS-1` (2026-10-13).
  - `module/exchange/api-routes.md`: 12 routes, all in OpenAPI. `feature-db.md` has about 13 rows.
  - `module/exchange/tests-inventory.md`: 17 classes (8 unit, 9 real-HTTP). They include `ControlApiExchangeOwningSpaceAuthzTest`,
    `ExchangeSnapshotMultiPodTest` and the end-to-end `CrossSpaceFraudIncidentAcceptanceTest`. UI: about 6 sharing specs.
  - Gating (grep of the module source): `canRequestShares`, `canOfferDatasets`, `canOfferSignals`, `canRender*`.
  - Open: only P3 `IMPORT-WHOLE-SPACE-SPLIT-1` (demand hold) and the "load as draft" browser pass.
  - Known limits (`architecture-overview.md` §9): Signals cross only within one Pod and are never queued. Duplicate `EXCHANGE_REFRESHED`
    events are possible from two pods.
- **Strengths vs market:**
  - The matrix says no RAFM peer shows a multi-entity / group-regulator model on its public pages. Here sharing fails closed and masks under
    the consumer's own entity types.
  - Sharing needs no reprocessing: the consumer's Widgets, Queries and Alert Rules read the owner's Dataset under a grant.
- **Gaps vs market / RFP risk:**
  - No central rule library pushed to entities. Rules travel by Metadata Bundles with copy semantics; sharing them is out of scope by
    design (`plan.md` §Refused). **No row.**
  - Cross-Space Signals need a single Pod and are not queued. Known limit, **no row**.
  - A live-mode grant stays fully masked until its owner registry is read (BACKLOG §6, retired).
  - There is no group roll-up hierarchy (parent → subsidiaries with automatic consolidation) as a concept, only per-item grants. Unverified
    whether any pack models it. **No row.**
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Segregated data per entity / OpCo | Comply | one Space per entity; owning-Space authz (`ControlApiExchangeOwningSpaceAuthzTest`) |
| Consolidated group / regulator view | Comply | shared Widgets and views render-only; snapshot and live delivery |
| Controlled, consented data sharing | Comply | offer → request → approve grant ledger, fail closed |
| Central rule library across entities | Partial | Metadata Bundles (copy), not live-shared |
| Masking of shared sensitive data | Comply | `BI-MASKING-EXCHANGE-SNAPSHOTS-1`; live grants fully masked |

- **To reach the next level:** L5 needs reference-customer evidence; none is recorded. Meanwhile: (1) Record a multi-host deployment
  soak. (2) Queue cross-Pod Signals. (3) Decide on a group roll-up / central-rule story and file a row.

---

#### Group notes

- **Documentation is wrong in places; the code was checked.** `addon/incidents/plan-incidents.md` has stale claims:
  - §5 and §8.7 say the authoring form cannot express a Measure rule. `inspecto-ui/.../alert-rule-attributes.ts` now has a `measure` kind.
  - §8.7 says "zero committed Case Rules / Tag Rules". Two `*_caserule.toon` files and one `*_tagrule.toon` file exist.
  - `module/agent/plan-assistant.md` §2 says AGT rows are "no edition" and §5.2 says intelligence is "bundled by nothing".
    `tools/bundle-modules.mjs` stages `inspecto-agent` from Standard and `inspecto-intelligence` from Advanced.
  - Matrix row L calls anomaly "not ML", but an isolation forest shipped 2026-10-18.
- **Common gaps across the ops add-ons:** no queue or group routing (deliberately retired), no native ITSM connector, and retention
  limited to Incidents. Cases, anomaly scores and action-request history have no retention or legal hold. Most of these have **no BACKLOG
  row**, which is itself a finding.
- **Process:** all ops/case tests run only under `-Pedition-standard|enterprise` (plan-incidents §8.1). No Playwright/e2e covers these
  panes; the only e2e spec is `inspecto-ui/src/e2e/backend-smoke.spec.ts`. Live evidence is limited to the anomaly UI lane, the D6
  proxy session and the Postgres two-node soak.
- **Not verified here:** per-user notification delivery preferences, whether anomaly models are under maker-checker, and any
  reference-customer use. L5 is not awarded anywhere.

### C. Add-ons — risk, assurance, data and Link Analysis

#### addon.reconciliation — Reconciliation
- **What it is (customer view):** Compares two or three Datasets at a key grain one day at a time and turns the differences into Breaks that analysts can assign, resolve, schedule and escalate to Incidents.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `reconciliation` (`offerings/standard.toon`); it ships in Standard and above (`docs/EDITIONS.md` l.178).
  - Built per `docs/okf/product/module/reconciliation/plan.md`: the Board, 2-way and 3-way compare, Break lifecycle `open | assigned | resolved | auto_closed`, the `recon.run` Job, `POST /recon/promote` to Incident, cardinality tier 1, 90-day state retention and the uplift slices. The only open row is P3 `PACK-PARTNER-SETTLEMENT-1`, a content pack. No defects.
  - Code confirms per-Measure tolerances (`exact`, `absolute`, percent) in `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconBreaks.java:414-419`, and cardinality assertion in `ReconService.Cardinality`.
  - 10 routes (`api-routes.md`). Writes are gated in `ReconRoutes.java:80-90`: `canOperateRuns` for record and breaks/status, `canManageIncidents` for promote. Read-shaped POSTs are recorded in `CapabilityManifest.EXEMPTIONS`.
  - 21 test classes: 16 unit and 5 real-HTTP (`ControlApiReconTest`, `ControlApiReconDayTest`, `ControlApiReconStateTest`, `ControlApiReconPromoteTest`, `MobileMoneyPackGoldenTest`), plus `TelcoRaGoldenTest` and an oracle parity corpus (`tests-inventory.md`).
  - Scale was measured with `ReconPerfTest` (`RECON_PERF=1`) up to 10M key groups. A live pass over 1,020,000 key groups and 60,000 Breaks loaded the Board in 1.3 s (`user-guide-reconciliation.md` l.151-218). This is one laptop run.
  - Audit happens at platform route level. `platform/inspecto-access/.../AuditTrail.java` records every successful mutating POST, so a break-status change is audited. There is no masking code in the module (grep `mask` hits only the filter bitmask).
  - 13 feature-db rows (`addon/reconciliation/feature-db.md`). The competitive matrix rates row B "Generic reconciliation engine" as **Y**.
- **Strengths vs market:**
  - One engine serves RA, mobile-money wallet/bank/partner reconciliation and telco-ra. Golden tests run the shipped Space Templates end to end. Telecom vendors in the matrix mostly rate P on this row.
  - Behaviour fails closed: it refuses rather than truncates at `MAX_BREAKS`, and refuses an undated side (`plan.md` Refused). Large-day performance is measured, not asserted.
  - A Break turns into an Incident in one call, and a scheduled run feeds the ops triage workflow (`ReconRunJob`).
- **Gaps vs market / RFP risk:**
  - True one-to-many and many-to-many matching (cardinality tier 2) is demand-gated in `plan.md`. **No row.** BlackLine/Duco-class tools (general market) treat it as table stakes.
  - There is no rule-based multi-pass auto-matching, fuzzy key matching or date-window matching. Matching is key-grain aggregate comparison only (`ReconService.Spec`). **No row.**
  - There is no period sign-off or certification workflow, and no balance-sheet account reconciliation (BlackLine core, general market). **No row.**
  - Re-rating and partner settlement: `PACK-PARTNER-SETTLEMENT-1` and `PACK-RERATING-1` (P3) are plans only. Classified key columns in Break rows (`/recon/rows`) are not masked by the module; whether platform masking covers them is unverified.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Multi-source matching (2/3/N-way) | Comply (2- and 3-way); N>3 Not offered | `plan.md` Required; `ControlApiReconTest` |
| Configurable match rules (tolerance, 1:n / n:m, fuzzy) | Partial | tolerances built; tier-2 cardinality demand-gated; no fuzzy matching |
| Exception workflow (assign, resolve, age, escalate) | Comply | Break lifecycle + `POST /recon/promote`; `ControlApiReconPromoteTest` |
| Scheduled, high-volume automation | Comply | `recon.run` Job; 10M-key-group harness; live run at 1M key groups (single laptop) |
| Audit, sign-off and export | Partial | route-level AUDIT + CSV export; no period certification or sign-off |

- **To reach the next level:** (1) File and build cardinality tier 2 (1:n matching) and a sign-off/certification step for each run. (2) Pin masking of classified keys on `/recon/rows` and `/recon/breaks` with a real-HTTP test. (3) Repeat the 1M-key live run on a server-class host and record it.

---

#### addon.regulatoryReporting — Regulatory Reporting
- **What it is (customer view):** Drafts a regulator-format filing (SAR/STR-style) from a Case or Incident, has a second person approve it, writes it atomically to a submission folder and audits every step.
- **Scores:** F 2 · B 4 · Q 3 · E 4 · R 2 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `regulatory-reporting` (`offerings/standard.toon`). The MVP shipped 2026-10-09 and `REGULATORY-REPORTING-1` closed 2026-10-18 (`module/regulatory-reporting/plan.md`).
  - 11 routes (`api-routes.md`). Draft and request-approval need `canWorkIncidents`. Approve, decline, retry and template PUT/DELETE need `canApproveChanges`.
  - XSD validation at draft time is real code (`features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/SchemaCheck.java`). There is a `.sha256` sidecar plus `GET /regulatory-reports/{id}/verify`, and `FileDrop.java` does the atomic file drop.
  - 6 test classes: 4 unit and 2 real-HTTP (`ControlApiRegulatoryReportsTest`, `ControlApiRegulatoryTemplatesTest`) (`tests-inventory.md`). That is thin for a filing engine.
  - The demo rehearsal on 2026-10-14 was GO and re-driven on an Enterprise bundle (`spec.md` l.134-161). It showed XML, CSV and JSON drafted, maker self-approval refused with 403, the viewer refused with 403, `sourceChanged` detection, and `GET /audit/verify ok`.
  - Masking of report content is "declared unmasked by design" (`spec.md` l.179). The only real-shaped template is the AML pack's `aml-str`. `sample-sar` claims no real regulator schema.
  - Open row: P3 `REGULATORY-SAR-STR-TEMPLATES-1` (hold: demand). Acknowledgement ingest, live delivery through a Connection and XMLDSig/CMS signing are BACKLOG §6 demand-gated. The competitive matrix rates row T as **P** for Inspecto (only LATRO has Y).
- **Strengths vs market:**
  - The evidence chain is strong for an MVP: four-eyes approval, a hash-chained AUDIT row for each transition, a content hash plus verify route, and partial filings refused with 403.
  - Report Templates are format-agnostic (XML/CSV/JSON + XSD), so a new regulator format is authoring work, not engineering.
- **Gaps vs market / RFP risk:**
  - No regulator-specific formats ship, for example goAML XML or FinCEN BSA SAR (general market: AML suites ship jurisdiction packs). Row: `REGULATORY-SAR-STR-TEMPLATES-1`.
  - There is no electronic submission to a regulator portal and no acknowledgement or rejection ingest; the file drop is the only channel. BACKLOG §6 holds it, with **no ranked row**.
  - There is no digital signing (XMLDSig/CMS), no amend or correct-and-resubmit (refused 2026-10-18), and no scheduled periodic returns (refused). Regulators that require amendments will mark this non-compliant.
  - Test depth is shallow: 6 classes and no golden test per format beyond `AmlScreeningGoldenTest` in scoring.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Jurisdiction formats shipped (goAML, FinCEN SAR, local STR) | Roadmap | `REGULATORY-SAR-STR-TEMPLATES-1` (demand); only `aml-str` real-shaped |
| Maker-checker approval | Comply | four-eyes decide gates; demo rehearsal maker-approve 403 |
| Schema validation before submit | Comply | `SchemaCheck` XSD at draft (built 2026-10-18) |
| E-filing + regulator acknowledgement | Partial | atomic file drop only; ack ingest / live delivery §6 demand-gated |
| Audit trail and evidence integrity | Comply | hash-chained AUDIT, `.sha256` sidecar, `/verify` route |

- **To reach the next level:** (1) Ship one regulator format end to end (for example goAML) as a template with a golden test. (2) Turn the §6 e-filing/ack item into a ranked row and build delivery through a Connection. (3) Add an amend/resubmit path or document the refusal in the RFP library.

---

#### addon.scoringLists — Scoring & Lists
- **What it is (customer view):** Combines Entity Lists, which say who to watch, block, allow or exclude, with explainable 0–100 Risk Scores per entity that can raise Incidents through Alert Rules.
- **Scores:** F 3 · B 4 · Q 4 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds `entity-list` and `scoring` (`offerings/standard.toon`). `entity-list` also ships as a Basic module (`offerings/basic.toon` `modules[6]`), so part of this billable add-on is already in Basic (packaging finding).
  - Scoring `plan.md` says "Nothing open on a BACKLOG row". It covers `ASSURE-RISK-SCORE-1`, the residuals (watch-list feed, opt-in history retention, entity-key masking on read, authoring pane with preview) and `TEMPLATE-RISK-SCORE-ALERT-RULE-1`.
  - Entity Lists `plan.md` lists ranges, CIDR, expiry, the Parquet sidecar, `/match`, four-eyes on held changes, the hash-chained fact log and `ENTITY_LIST_CHANGED` audit (counts, never keys). Left: a head-hash cache and `LA17-NORMALISER-CHANGE-1` (a normaliser change strands members).
  - Routes: scoring 2, both `canWorkIncidents`. Entity Lists 7: writes `canManageIncidents`, register-dataset `canAuthorWorkbench`, GET and `/match` ungated with members masked.
  - Tests: scoring 24 classes (21 unit, 3 real-HTTP), including golden runs of the AML, mobile-money and payment-fraud packs (`AmlPackGoldenTest`, `MobileMoneyRiskScoreGoldenTest`, `PaymentFraudTemplateGoldenTest`) and `RiskScoreOutputGoldenTest`. Entity Lists 5 classes (2 unit, 3 real-HTTP).
  - 13 feature-db rows. The competitive matrix rates rows J (entity risk scoring) and K (watch/deny lists) as **Y**.
  - Live evidence: no recorded live or demo pass found in `scoring/*.md` or `entity-list/*.md` (grep for live/re-driven/rehearsal). Only gated benches (`ListPruningSp4Bench`) exist.
- **Strengths vs market:**
  - The score is explainable and reproducible from stored factors, evidence is masked at write time, and the score feeds standard Alert Rules. Black-box scores are a common Actimize/SAS-class criticism (general market).
  - Entity Lists are one tamper-evident store, with maker-checker, expiry and CIDR/range entries, shared by Screening, Link Analysis and Risk Scores.
- **Gaps vs market / RFP risk:**
  - Scoring is batch only, with no real-time or in-transaction score. Row: `REALTIME-DECISIONING-1` (P3). Feedzai/FICO-class buyers expect real-time (general market).
  - There is no ML model scoring and no model governance (champion/challenger, back-testing, drift). The Anomaly add-on is separate. **No row** for model governance.
  - Named score bands are deferred (D-RP3) and indicators allow no arithmetic (refused). The Entity List fact log has no retention (append-only), which gives no GDPR erasure path. **No row.**
  - `LA17-NORMALISER-CHANGE-1` strands list members when an Entity Type's normaliser changes, a latent defect cited in the plan. No ranked row was found on the board (unverified).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Explainable entity risk score | Comply | `RiskScorerTest`, `ControlApiRiskScoreTest`; factor breakdown |
| Real-time scoring | Roadmap | `REALTIME-DECISIONING-1` |
| ML models + model governance | Not offered | Anomaly add-on is statistical; no governance row |
| Watch/deny/allow list management (expiry, maker-checker) | Comply | Entity Lists plan Built table; four-eyes held changes |
| Score-driven alerting and case creation | Comply | `RiskScoreAlertTest`, `PendingAlertRulesTest` (`afterScore` seed) |

- **To reach the next level:** (1) Record a live demo pass (score, then Alert Rule, then Incident) on a bundle. (2) File rows for model governance and list-member erasure/retention. (3) Decide the packaging overlap (Entity Lists in Basic versus the billable add-on).

---

#### addon.screening — Screening
- **What it is (customer view):** Screens names and identifiers against Entity Lists (sanctions, PEP, deny) with fuzzy, phonetic and transliteration matching. It raises signed hits for four-eyes review, and a confirmed hit opens an Incident and a Case.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `screening` (`offerings/standard.toon`). It requires `ops` (`SCREENING-1` row, BACKLOG l.596).
  - Matching is real code: token Jaro-Winkler from the analytics similarity kit (`features/inspecto-screening/src/main/java/com/gamma/screening/NameMatcher.java:3`), phonetic skeletons, Cyrillic transliteration by sound, and blocking keys (`spec.md` l.34-51).
  - 5 routes. `decide` needs `canWorkIncidents` and `reveal` needs `canRevealLinkEntities`. `POST /screening/check` and the hits GET routes are ungated ("—").
  - 12 test classes: 11 unit and 1 real-HTTP (`ControlApiScreeningTest`), plus `NameMatcherGoldenTest` and `ScreeningRevealTest`.
  - The 2026-10-14 demo rehearsal was re-driven on an Enterprise Demo Auth bundle (`spec.md` l.140-161). It covered exact, fuzzy, transliterated and identifier matches, 1,000 subjects in 94 ms, self-confirm refused with 409 four-eyes, and an audited reveal. Verdict: GO.
  - P2 `SCREENING-1` is still open even though the row says "No operator decision is open (D1-D3 built; D4-D7 taken)". The remaining items are decided deferrals: dedupe index D6, Space threshold default D5 and initial-letter typo D7.
  - Scale limits: `maxRows` defaults to 100,000 with a ceiling of 1,000,000. A run lists every hit document to dedupe, with no index, which is "fine for thousands of hits, not for millions" (`spec.md` l.139).
  - No sanctions-list feed or loader exists. There is no `ofac` hit in `features/`, `platform/` main code, `spaces/_templates` or the screening hub. The only hits are test fixtures.
- **Strengths vs market:**
  - The matching quality is documented with golden pairs (Gaddafi/Qadhafi, Muhammad/Mohamed, Cyrillic). Masked PII subjects are still matchable, and reveal is per call and audited.
  - Decisions are tamper-evident: signed hits, four-eyes on `confirmed`, and Incident plus Case opening that compensates on failure.
- **Gaps vs market / RFP risk:**
  - There is no list provisioning: no OFAC/UN/EU/HMT ingestion and no commercial Dow Jones/Refinitiv-class feeds with scheduled updates. Lists are hand-built Entity Lists. **No row.** This is the first line of every sanctions RFP (general market).
  - There is no real-time payment or transaction screening. Row: `REALTIME-DECISIONING-1`.
  - There is no delta re-screening when a list changes, no secondary-attribute scoring (DOB, nationality), and no good-guy suppression or tuning (ATL/BTL) toolkit. **No row** (whether the allow/exclusion purposes can act as good-guy lists is unverified).
  - Hit volume is capped by the missing dedupe index (D6, deferred on `SCREENING-1`), and the ungated `check` route lets any signed-in user probe list membership (match results masked).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Fuzzy / phonetic / transliteration name matching | Comply | `NameMatcherGoldenTest`; rehearsal beat 1 |
| Sanctions/PEP list provisioning and auto-update | Not offered | lists are manual Entity Lists; no row |
| Real-time transaction screening | Roadmap | `REALTIME-DECISIONING-1` |
| Alert review with four-eyes and audit | Comply | `decide` four-eyes 409; `screening.hit.revealed` audit |
| Batch screening of the customer base at scale | Partial | `screening.run` to 1M rows; no dedupe index, no delta re-screen |

- **To reach the next level:** (1) File and build a list-feed Collector/Space Template for the public sanctions lists (OFAC SDN, UN, EU) with scheduled refresh and delta re-screening. (2) Build the dedupe index (D6) and measure at 100,000 hits. (3) Close or split `SCREENING-1` so the board reflects the decided state.

---

#### addon.integrationDelivery — Integration & Delivery
- **What it is (customer view):** Sends platform notifications out by email and webhook, with digests, delivery receipts and bounce suppression, on top of the core in-app and SSE feed.
- **Scores:** F 2 · B 4 · Q 3 · E 4 · R 2 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `notify-channels` (`offerings/standard.toon`). The code is 5 classes in `providers/inspecto-notify-channels/src/main/java/com/gamma/notify/channel/`: `SmtpEmailChannel`, `WebhookChannel`, `HttpWebhookSinkTransport`, `PinnedHttp` and `WebhookEgress`.
  - `plan.md`: email and webhook are built only under Standard and above, with digest batching, receipts, bounce suppression and per-Space destinations. `WEBHOOK-EGRESS-POLICY-1` gives a pinned connect, and channel and notification-rule writes go through maker-checker (`ASSURE-MAKER-CHECKER-RESIDUALS-1`). No open rows.
  - Module tests: 9 unit classes, including two channel TCKs, `SmtpEmailChannelStarttlsRequiredTest`, `SmtpEmailChannelTlsIdentityTest` and `PinnedHttpTlsTest`. The core holds 5 real-HTTP notification tests (`inspecto/src/test/java/com/gamma/control/ControlApiNotification*Test`, `NotificationContentMaskingTest`).
  - Known limits (`architecture-overview.md` §9): no retry inside a channel except the soft-bounce retry, and suppression applies only to persisted destinations.
  - There is no feature-db row (`addon/integrationDelivery/index.md`: "no feature rows derive to this item").
  - No HMAC or signature code exists in `providers/inspecto-notify-channels` (grep `signature|hmac`). Signing of notification webhooks is unverified. Link Analysis has its own signed webhooks (`IntegrationWebhooksTest`).
- **Strengths vs market:**
  - Egress is security-hardened: STARTTLS required, TLS identity checks, pinned connect under the Space egress policy, and masked notification content.
  - Channel configuration is governed by maker-checker, which iPaaS add-ons rarely offer (general market).
- **Gaps vs market / RFP risk:**
  - There are no native Slack, Teams, SMS, paging (PagerDuty) or ITSM (ServiceNow/Jira) channels; email and a generic webhook are the only ones. **No row.**
  - The add-on name promises "Delivery", but it holds no scheduled report or Dataset delivery (SFTP/S3 push). Outbound targets are a proposal only (`connectors/plan-warehouse-targets.md`). **No row.**
  - There is no retry with backoff beyond soft-bounce, and no dead-letter view. **No row.**
  - No recorded live SMTP or webhook drive was found (unverified).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Email alerts over TLS | Comply | `SmtpEmailChannel*Test` (STARTTLS required) |
| Webhooks with egress control | Comply | `WebhookChannelTest`, `PinnedHttpTlsTest`; payload signing unverified |
| Slack / Teams | Partial | only through the generic webhook (payload shape unverified) |
| SMS / paging / ITSM ticketing | Not offered | no row |
| Delivery guarantees (retry, receipts, digest) | Partial | receipts + digest + bounce suppression; no retry beyond soft bounce |

- **To reach the next level:** (1) Add native Teams/Slack and an ITSM (ServiceNow/Jira) channel through the `NotificationChannel` SPI and its TCK. (2) Add retry with backoff and a dead-letter list. (3) Either add scheduled report delivery or rename or narrow the add-on.

---

#### addon.premiumConnectors — Premium Connectors
- **What it is (customer view):** Adds a Kafka consumer Collector so records arriving on a topic enter the same Pipelines as files. Today Kafka is the only premium connector.
- **Scores:** F 2 · B 3 · Q 2 · E 3 · R 2 → **Level L2 (Prototype)**
- **Evidence:**
  - The add-on holds one module, `connectors-kafka` (`offerings/standard.toon`). `docs/EDITIONS.md` row SP-ACQ-09 marks it 🟡 (partial) in Standard and Advanced: "proven against MockConsumer and a fake connector only, never a real broker".
  - `plan.md` Built: the workbench probe/explore/sample, `STREAM-CONSUMER-1` (ledger-watermark frontier durable across restart, lag gauge), the `trigger: stream` lane, the partition-leader egress check and `StreamLane.Backoff`.
  - 6 test classes, all unit. `KafkaRealBrokerTest` skips unless `-Dkafka.it.bootstrap` answers, and no broker ships with the build or CI (`plan.md` Left open).
  - Security: SASL is supported (`KafkaConnector.java:383`). The default `security.protocol` is `SASL_PLAINTEXT` when credentials are set; `SASL_SSL` is reachable only through a pass-through option. There is no truststore handling in the module (grep), so TLS to the broker is unverified.
  - No Avro or schema-registry support (grep of `providers/inspecto-connectors-kafka/src/main`: none). Outbound Kafka (Dataset to topic) is a proposal only.
  - No feature-db row; no open BACKLOG row.
- **Strengths vs market:**
  - The ledger frontier is the single source of truth, giving exactly-once landing into the same Pipelines as files, with no broker consumer-group state (by decision).
  - Egress policy applies to partition leaders, which most connector products do not check (general market).
- **Gaps vs market / RFP risk:**
  - It has never run against a real broker. **No row** for a real-broker CI or IT run (a finding: the 🟡 has no tracked path to ✅).
  - No Avro or Protobuf, no schema registry, no outbound Kafka. **No row** (outbound is a proposal in `plan-warehouse-targets.md`).
  - With a single connector, the add-on name over-promises. Other enterprise sources are missing: native Hive/Kerberos (deferred by `D-ING5` under `LA-DAILY-INGEST-1`), SAP/Salesforce and cloud warehouses. **No row** apart from D-ING5.
  - The insecure default `SASL_PLAINTEXT` can expose PLAIN credentials. **No row.**
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Kafka streaming ingest | Partial | built; mock-only proof (`docs/EDITIONS.md` SP-ACQ-09 🟡) |
| Broker security (SASL + TLS) | Partial | SASL built; TLS via pass-through option only, unverified |
| Schema registry / Avro | Not offered | no code, no row |
| Durable, exactly-once resume after restart | Comply | `KafkaStreamLaneTest`; ledger frontier (`STREAM-CONSUMER-1`) |
| Other enterprise connectors (Hive/Kerberos, SaaS, warehouses, outbound) | Roadmap | D-ING5 deferral; warehouse targets proposal only |

- **To reach the next level:** (1) Run `KafkaRealBrokerTest` in CI against a containerised broker, including SASL_SSL, and flip SP-ACQ-09 to ✅. (2) Make TLS the default when credentials are present. (3) Add Avro plus schema registry, or narrow the add-on's name and positioning.

---

#### addon.linkAnalysis — Link Analysis
- **What it is (customer view):** An investigation studio that projects any Dataset into a graph to explore, run 29 graph algorithms, map geo activity and resolve identities. It records every step in a hash-sealed Investigation that can be replayed, forked, approved under four-eyes and handed on as a sealed Dossier, a Case link or a standing detection.
- **Scores:** F 4 · B 4 · Q 5 · E 4 · R 4 → **Level L3 (Pilot-ready)** — module scores at L4, single-host. *(Calibrated 2026-10-20 to **L3**: the platform enterprise baseline is L3 and no item can exceed it — this item's own scores support **L4 once the platform gaps in §4.1 close**.)*
- **Evidence:**
  - Basic ships `la-storage`, `la-core`, `la-api` and `geo-link`; Advanced adds `la-store-pg` (`offerings/basic.toon`, `offerings/advanced.toon`). Basic is the explorer and the Investigation surface needs Standard (`ControlApiBasicEditionLinkAnalysisTest`, cited in `module/la-core/brochure-inspecto-la-whitepaper.md` §7). `la/inspecto-la-graph/` is untracked work in flight and not in any offering.
  - There are 29 algorithms, verified in code: 29 enum constants in `la/inspecto-la-core/src/main/java/com/gamma/la/core/Algorithm.java`, including PageRank, betweenness, Louvain, max-flow, link prediction and `PROPAGATED_RISK`.
  - Routes: `la-api` 90 and `geo-link` 7, so 97 registered, all in the OpenAPI contract (generated `api-routes.md`). Docs drift: the whitepaper says 93 (86+7), `features-link-analysis.md` says 87 and the add-on features say 94. 101 feature-db rows.
  - Tests: 186 classes across la-core (23), la-api (40), la-storage (12), la-store-pg (3) and geo-link (108). 90 are real-HTTP control-plane tests, held in `la/inspecto-geo-link/src/test`. Golden showcase tests (`ControlApiLaShowcaseFiguresTest`, `LaShowcaseGoldenTest`) and the acceptance test `LaDailyIngestAcceptanceTest` are among them.
  - Live and measured evidence (`brochure-inspecto-la-whitepaper.md` §6–§7):
    - `IndexScaleBench`: about 21 ms one-key p50 from 10^6 to 10^8 edges.
    - Daily append at 10^8 takes 30.6 s against 929 s for a full rebuild.
    - A production-scale day (132M records, 39.4M links) was indexed in 5.3 min. This is a single run on private data.
    - Live axe passes, real WSO2 OIDC sign-in, a Data Trust live drive, a think-aloud script and a stakeholder Q&A are recorded in `module/la-core/tests-*.md` and `admin-guide-real-signin.md`.
  - Governance proven by named tests: seven-gate order, strict 404 for non-members, keyed masking with anti-oracle, four-eyes on sensitive expansion, per-entity audited reveal, Dossier tamper detection (`ControlApiDossierTest`) and replay determinism.
  - Open work: P2 `LA-DAILY-INGEST-1` follow-ups (Hive/Kerberos deferred by D-ING5, SCD2 deferred by D-ING4), six P3 rows, and the P3 defect row `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1`. Items (1)–(6) of that defect row are done; only the alias-book scan check remains.
  - Stated NOT YET items (whitepaper §7): retention purge and legal hold (declined, D-U8), the multi-host HA two-host drill (`ASSURE-OPERABILITY-1`), 5×10^8 and 10^9 edge builds (`LA-INDEX-SCALE-MEASURE-1`), machine-to-machine trust, and full WCAG 2.2 AA (`LA-A11Y-AUDIT-1`). The Postgres store tests do not run on the shared profile (`TEST-ENV-PG-ASN-UNRUN-1`).
- **Strengths vs market:**
  - The evidentiary chain of custody is stronger than i2-class desktop tools (general market): a sealed op log, deterministic replay, a SHA-256 Dossier manifest, Drafts with rebase and promote, and four-eyes on sensitive expansion. All of it is pinned by real-HTTP tests.
  - It runs in-process over Parquet, with no separate graph database or cluster, and the standard build provably cannot call hosted AI (`EgressGuardTest`). This is a strong sovereignty and air-gap story against Palantir/Quantexa-class SaaS or cluster deployments (general market).
  - The disclosure discipline is unusually honest: a claims register marking each claim PROVEN, MEASURED or NOT YET makes RFP answers defensible line by line.
- **Gaps vs market / RFP risk:**
  - Entity resolution is analyst-asserted or imported identity pairs plus merged traversal (`user-guide-link-analysis.md` §8.2–8.3). There is no probabilistic or ML entity resolution at scale, which is Quantexa's core (general market). **No row.**
  - It runs on a single host: measured to 10^8 edges, with 5×10^8 and 10^9 unmeasured (`LA-INDEX-SCALE-MEASURE-1`, P3). The two-host HA drill has not run (`ASSURE-OPERABILITY-1`, P2, platform). Embed links are not supported on the Postgres store.
  - Retention purge and legal hold were declined (D-U8; BACKLOG §6 standing refusal). Regulated buyers who need retention schedules or right-to-erasure will flag this.
  - Daily-feed completeness is open under `LA-DAILY-INGEST-1` (P2): no native Hive/Kerberos and no SCD2 as-of join. The whitepaper's "All read surfaces masked — NOT YET" row is partly stale: `OBJECT-READ-MASKING-RESIDUALS-1` closed 2026-10-18, and `BI-QUERY-MASKING-1` closed 2026-10-23, but free SQL can still test a key (P1 `BI-MASKING-FREE-SQL-ORACLES-1`).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Interactive graph exploration (expand, paths, patterns, geo) | Comply | `ControlApiInvTraversalTest`, `ControlApiInvPatternTest`, geo tests; live axe passes |
| Graph analytics (centrality, community, paths, risk propagation) | Comply | 29 algorithms (`Algorithm.java`); `GraphAlgorithmsParityTest`, `ControlApiGraphRunTest` |
| Entity resolution | Partial | asserted/imported identity facts + `resolve` op; no probabilistic ER |
| Evidence integrity, collaboration, case integration | Comply | `ControlApiDossierTest`, `ControlApiDraftsTest`, `ControlApiInvestigationCaseShareTest` |
| Scale and high availability | Partial | 10^8 edges measured; 10^9 and two-host HA NOT YET; PG store tests not run on the shared profile |

- **To reach the next level:** (1) Complete the 10^9 index measurement on a server-class host and the two-host HA drill. (2) Decide whether to offer retention and legal hold for Investigations, at least as an Advanced option. (3) Scope probabilistic entity resolution as a row. L5 would also need recorded reference-customer evidence, and the repo records none.

---

#### addon.telecomIndustryPack — Telecom industry pack
- **What it is (customer view):** Adds the ASN.1 BER decoder step (`parser.asn1.ber`) so switch, IMS and charging CDRs can be ingested directly into Pipelines from their native binary format.
- **Scores:** F 2 · B 4 · Q 3 · E 3 · R 2 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `telecom-asn1` (`offerings/standard.toon`; `docs/EDITIONS.md` SP-PRS-06 ✅). The telco RA and fraud controls (`telco-ra`, `telco-fraud`) are separate Standard `contentPacks` and the contract-enforced `solution-pack.industry-telecom` (`product-map/solution-pack/industry-telecom.toon`), not part of this add-on.
  - `plan.md` Built: grammar-less structural preview, grammar-bound preview, stored path-jailed `.asn` grammars, derived columns and child rows, and `missingModule` reporting through `provides.stepKinds`. No open rows.
  - 10 unit test classes (`Asn1ParserPluginTckTest`, `Asn1RecordIngester*Test`, `DemoAsn1CorpusIngestTest`) and 2 feature-db rows. The decoder library `asn-decoders` has 1 test class.
  - `providers/asn-parser/docs/MIGRATION_STATUS.md` covers the library's 30 grammars from legacy operator feeds (Huawei/Ericsson MSC, IMS, GGSN, OCC; TAP and NRTRDE roaming). Only 9 corpus cases are proven (16/16 files at structural parity, 10/16 at content parity 1.0). Six tag-map formats need an `asn migrate` converter, which is not built.
  - The five `asn-*` modules cannot run offline on the shared profile, so a green reactor does not cover them (`TEST-ENV-PG-ASN-UNRUN-1`, P3). The real carrier corpus is not archived (`DATA-GOV-1`).
  - Deferred by decision: an out-of-process parser host (D8), signer anchoring (T2) and a segments editor.
- **Strengths vs market:**
  - The grammar-driven decoder works without vendor-specific code, with a structural preview before any grammar is bound. That shortens onboarding for a new switch feed compared with bespoke mediation adapters (general market).
  - It plugs into the same governed Pipeline, quarantine and Reconciliation stack as every other format.
- **Gaps vs market / RFP risk:**
  - The add-on is thin against LATRO/Subex/Mobileum-class telecom offerings, which bundle mediation, RA/FM controls and bypass detection (competitive matrix). Bypass/SIM-box detection: `TELCO-BYPASS-SIMBOX-1` (plan only). Re-rating and settlement: `PACK-RERATING-1` and `PACK-PARTNER-SETTLEMENT-1`.
  - Vendor coverage is partly unproven: 30 grammars, 9 proven cases, and tag-map formats with no converter. **No row** for the `asn migrate` converter.
  - There is no decoder throughput figure: the only mention is a to-do in `providers/asn-parser/docs/REDESIGN.md:225`. Mediation-grade volume claims are unverified. **No row.**
  - Parser trust is limited: the decoder runs in-process (out-of-process host D8 deferred) and plugin signatures are not anchored (T2).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Native decoding of switch CDRs (ASN.1 BER, 3GPP TS 32.298) | Comply | `parser.asn1.ber`; `Asn1ParserPluginTest`, `DemoAsn1CorpusIngestTest` |
| Vendor format coverage (Ericsson, Huawei, Nokia, TAP/NRTRDE) | Partial | 30 grammars, 9 proven corpus cases; tag-map converter not built |
| Out-of-the-box RA/FM controls | Partial | in the `telco-ra` / `telco-fraud` content packs, not this add-on |
| Bypass / SIM-box detection | Roadmap | `TELCO-BYPASS-SIMBOX-1` (plan only) |
| Mediation-grade throughput and error handling | Partial | Pipeline quarantine applies; no measured decoder throughput |

- **To reach the next level:** (1) Make the `asn-*` modules run in the reactor (surefire cache) and archive the real corpus (`DATA-GOV-1`). (2) Measure decoder throughput (records/s) on the corpus. (3) Either fold the telco content packs into the add-on's story or rename it "ASN.1 decoder".

---

#### Group notes

- **Breadth of evidence is very uneven.** Link Analysis (186 test classes, 90 real-HTTP, benches, live drives, a claims register) is the only item at L4. Screening and Regulatory Reporting are engines with good governance and rehearsed demos, but they lack the content buyers ask for first: sanctions list feeds and regulator formats. Premium Connectors is a mock-proven single Kafka consumer (L2).
- **Packaging findings:**
  - `entity-list` is both a Basic module and part of the billable `scoringLists` add-on.
  - `linkAnalysis` is billable yet built into Basic.
  - Integration & Delivery, Premium Connectors and Telecom industry pack each hold one module, narrower than their names suggest.
- **Docs drift:** Link Analysis route counts disagree across docs (87, 93, 94) against 97 generated. P2 `SCREENING-1` stays open with nothing left to decide. The whitepaper's masking NOT YET row predates the 2026-10-18 masking closures.
- **Shared gaps with no row:** real-time decisioning (`REALTIME-DECISIONING-1`) and retention or erasure are cross-cutting for Scoring, Screening and Link Analysis. Retention/erasure has no row; it is refused for the Link Analysis store and absent for the Entity List fact log.
- **Not verified:**
  - Masking of classified keys on `/recon/rows`.
  - Whether notification webhooks are HMAC-signed.
  - Kafka TLS.
  - Whether `LA17-NORMALISER-CHANGE-1` has a board row.
  - All performance figures, which are single laptop runs as the docs themselves state.
- No reference-customer evidence is recorded anywhere, so nothing qualifies for L5.

### D. Solution packs — telecom

#### solution-pack.telco-ra — Telecom revenue assurance

- **What it is (customer view):** a ready-to-install Space Template that runs the standard revenue-assurance controls (switch → mediation → rating completeness, rated vs billed, re-rating, prepaid roll-forward, interconnect settlement) over canonical telecom feeds and tracks leakage through to recovery.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Template `spaces/_templates/telco-ra/` (`template.toon`, `minEdition: standard`, listed in `offerings/standard.toon` `contentPacks`) ships 8 Pipelines + Schemas, 2 Reconciliations (`registry/reconciliations/ra_xdr_completeness.toon` 3-way, `ra_rated_vs_billed.toon`), 11 Jobs, 2 Alert Rules, 2 Expectations, 3 KPIs, 8 Widgets on `ra_overview`, a 131-line runbook, 8 sample feeds.
  - **Control count: 6 detection controls**: 2 Reconciliations, then 4 `sql.template` controls (`ra_xdr_lost`, `ra_rerating`, `ra_rollforward`, `ra_settlement`). Two fold Jobs (`ra_leakage`, `ra_data_quality`) and `ra_recovery` sit on top. Together they emit about 12 distinct REASON codes (re-rating 3, roll-forward 3, settlement 6), per `docs/okf/product/module/config/plan-spaces.md` §3.5.
  - `features/inspecto-reconciliation/src/test/java/com/gamma/job/TelcoRaGoldenTest.java` (6 tests, 502 lines). Every control flags exactly its planted leakages (15 completeness Breaks / 9 lost xDRs, 4 rated-vs-billed, 5+N re-rating, 6 roll-forward, 10 settlement) against 24 benign boundary look-alikes. `theTemplateIngestsEvaluatesAndAlertsEndToEnd` ingests through all 8 Pipelines and checks 6 Alerts and KPIs of 161.16 USD / 17 / 17.
  - Mutation-checked: about 20 rule and tolerance mutations, each run separately and each red (`plan-spaces.md` §3.5). `ControlApiSpaceTemplateSeedGateTest` and `KpiCompletenessWidgetTest` (3 tests) cover apply and the completeness tile.
  - BACKLOG: `ASSURE-PACK-TELCO-RA-1` was closed 2026-10-04 (`docs/BACKLOG.md` line 169). Its vendor-feed-mapping half is parked in `docs/archived-documents/plans-archive/assurance-capability-plan.md` §4. The hub shows "No open rows".
  - Decided limits: the impact ledger stays manual (`PUT /objects/{id}/impact`, no automatic write); timestamps are naive (one agreed timezone); the `kpi.completeness` tile ships `enabled: false` (`business-assurance/architecture-revenue-assurance.md` "Limits").
  - Every feed Schema column is classified `INTERNAL`, `SUBSCRIBER_ID` and `B_NUMBER` included (`config/switch_xdr/switch_xdr_schema.toon`). No `MSISDN`/`PII` classification exists anywhere in the pack (grep: 0 hits), so masking never applies to RA records.
  - Competitive matrix: A = **Y**, C re-rating = **P**, Q settlement = **P** (`brochure-competitive-matrix.md` rows A, C, Q).
- **Strengths vs market:**
  - The golden corpus is evidence-grade: planted leakages, boundary look-alikes, mutation testing and an end-to-end ingest test. Vendors do not publish a zero-false-positive proof for each control.
  - The controls are configuration (generic Reconciliation + `sql.template`), so new controls need no code. A full ingest platform with Expectations and lineage sits underneath, which matrix §3 says none of the six vendors shows.
  - The settlement logic is careful: FULL OUTER JOIN, and split vs duplicate statement handled by tolerance.
- **Gaps vs market / RFP risk:**
  - **Control coverage is narrow:** 6 controls across 4 RA domains (usage chain, billing, prepaid, interconnect). There is no provisioning reconciliation (HLR/CRM/billing subscriber sync), no roaming TAP/NRTRDE, no collections/payments, and no content/VAS partner controls. LATRO/Subex/RAID ship control catalogs in the hundreds *(general-market)*. **No row** for a control-catalog expansion or provisioning reconciliation.
  - Re-rating has no plan inference, allowances or bundles: `PACK-RERATING-1` (P3, plan only). Partner settlement lifecycle: `PACK-PARTNER-SETTLEMENT-1` (P3, plan only). Both are tagged to `industry-telecom`, not this pack.
  - Vendor feed mapping (real switch/mediation/billing extracts) is parked until a customer exists (`assurance-capability-plan.md` §4). Everything is proven only on canonical synthetic Schemas.
  - RA records carry no masking classification and leakage is not written to the impact ledger automatically. **No row** for either.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Usage completeness switch → mediation → rating → billing | Comply | `ra_xdr_completeness` 3-way + `ra_rated_vs_billed`; `TelcoRaGoldenTest` |
| Billing accuracy / re-rating against tariff | Partial | effective-dated `ra_rerating` (rate mismatch, ambiguous, no tariff); no bundles, allowances or plan inference → `PACK-RERATING-1` |
| Interconnect / partner settlement verification | Partial | `ra_settlement` against partner statements; no dispute lifecycle or partner portal → `PACK-PARTNER-SETTLEMENT-1` |
| RA control coverage across domains (provisioning, roaming, collections) | Not offered (beyond 4 domains) | no provisioning, TAP or collections controls; no row |
| Leakage quantification and recovery tracking | Partial | `ra_leakage` + impact ledger + `ra_recovery` tile; ledger entry is manual by decision |

- **To reach the next level:**
  1. Classify subscriber and number columns (`MSISDN`/`PII`) in the 8 Schemas and the findings Datasets, then prove masked tiles over HTTP the way telco-fraud did.
  2. Add provisioning-reconciliation and roaming controls, and file a BACKLOG row for an RA control catalog with a coverage % against a named framework.
  3. Record a live bundle run of the template, then map one real operator's extracts (unpark the vendor half).

---

#### solution-pack.telco-fraud — Telecom fraud

- **What it is (customer view):** a Space Template that detects 14 telecom fraud typologies daily from CDR, subscriber-event and payment feeds. It raises one Incident per offender, groups each offender's Incidents into a Case, and ships runbooks, KPIs and a masked dashboard.
- **Scores:** F 3 · B 4 · Q 4 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Template `spaces/_templates/telco-fraud/`: 3 Pipelines, **14 detection Jobs** (`config/jobs/fraud_*_job.toon`), 14 per-entity Alert Rules, 14 runbook records (`registry/runbooks/`), 7 Expectations, 2 Decision Rules, 1 Case Rule (`config/ops/fraud_critical_burst_caserule.toon`, `groupBy` msisdn), 4 KPIs, 19 Widgets, 1 Anomaly Model, a nightly `report` export, and an `expectation.evaluate` Job.
  - **Typology count: 14**: IRSF, Wangiri, SIM-box, premium rate, roaming, SIM swap, identity/subscription, dealer, voucher/EVD, reversal, recharge, data bypass, internal, manual adjustment. The thresholds are generic defaults tuned to the corpus (`ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1` (g)).
  - `inspecto/src/test/java/com/gamma/control/TelcoFraudTemplateGoldenTest.java` (12 tests, 866 lines) checks: exact planted offenders with no look-alike, every tile rendered, 16 retained days with no storm, an Expectation Incident from a bad row, runbook linkage, and MSISDN classification. Also `TelcoFraudFeedGapTest` (2), `TelcoFraudPackAnomalyPropertyTest` (2) and `AnomalyTemplateModelsTest`.
  - Live evidence: a demo rehearsal on an Enterprise Demo Auth bundle on 2026-10-14 (`docs/BACKLOG.md` line 76). The live bundle run on the same day exercised the Expectation Job and the offender export (row `TELCO-FRAUD-STORY-GAPS-1`).
  - Masking: `TELCO-FRAUD-MASKED-DASHBOARD-1` was closed 2026-10-18. 10 of the 14 sink Datasets classify `msisdn`/`caller` `MSISDN` and `id_doc` `PII`, and a viewer without `canRevealLinkEntities` gets masked bars over real HTTP (BACKLOG line 54).
  - Open rows (hub `index.md`): defect `ANOMALY-TF-STORE-CLASH-1` (P3: `AnomalyTemplateModelsTest...[1]` ERRORS on clean master; the pack's `tf_subscriber_anomaly_latest` Dataset clashes with the model's store), `TELCO-FRAUD-STORY-GAPS-1` (P3 remainder), `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1` (P3, demand), `PACK-IRSF-WANGIRI-1` (P3, plan only), `TELCO-BYPASS-SIMBOX-1` (P3, B0 measurement done 2026-10-20, nothing built).
  - The SIM-box check is a single heuristic (`fraud_simbox_job.toon`: no MT calls, no SMS, ≤ 1 cell, ≥ 10 distinct targets). The IRSF check is a static prefix list (`irsf_prefixes: "882,883,8818"`). Detection is daily batch at 02:00; only the Decision Rules act at ingest (`architecture.md` "Limits").
  - Competitive matrix: D = **Y**, E bypass = **P**, F signalling = **N**, G test call = **N**, H interconnect fraud = **P**.
- **Strengths vs market:**
  - Each typology has a runbook, a per-offender Incident with a masked key, a Case per offender and a dashboard tile, all proven by a golden corpus with look-alikes. Detection, case handling and evidence are tested together.
  - Includes data-quality Expectations, a feed sequence-gap Incident (`TelcoFraudFeedGapTest`) and four-eyes/audit from the platform. Matrix §3 lists these as absent on all six vendors' public pages.
  - The telecom Industry pack (`domain_profile: telecom`, `config/link-analysis.toon`) gives Link Analysis over the same Space. LATRO is the only vendor with comparable published link analysis (matrix §3).
- **Gaps vs market / RFP risk:**
  - Typology depth: 14 heuristic SQL windows against vendor libraries of 50 or more with device and behaviour profiling *(general-market)*. IRSF uses a static 3-prefix list with no `destination_risk` Reference: `PACK-IRSF-WANGIRI-1` (operator hold, decisions D-IW1…10 owed).
  - Bypass is CDR-only with one rule: no test-call generation (G = out of scope), no signalling (F = out of scope), no device/cell clustering. `TELCO-BYPASS-SIMBOX-1` has B0 done; D-BP1/D-BP2 gate B1.
  - No real-time or pre-call blocking (`REALTIME-DECISIONING-1`). This is the most common RFP knock-out line for FMS *(general-market)*.
  - An open defect errors a pack test on master (`ANOMALY-TF-STORE-CLASH-1`). The offender export writes raw MSISDNs (sink Datasets unclassified on purpose, `TELCO-FRAUD-STORY-GAPS-1`). The Anomaly Alert Rule must be created by hand after the first run.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Typology coverage (IRSF, Wangiri, SIM-box, subscription, roaming, PRS, internal) | Comply (breadth) / Partial (depth) | 14 typologies golden-tested; thresholds generic, IRSF static prefixes → `PACK-IRSF-WANGIRI-1` |
| Real-time / near-real-time detection and blocking | Not offered | daily batch; ingest Decision Rules only → `REALTIME-DECISIONING-1` |
| Case management and investigator workflow | Comply | Case Rule `groupBy` msisdn (`CaseRuleGroupByTest`), runbooks, Dispositions, Link Analysis |
| Behavioural / ML profiling | Partial | `tf_subscriber_usage` Anomaly Model (per-line history); pending Alert Rule; open defect `ANOMALY-TF-STORE-CLASH-1` |
| Bypass / SIM-box detection | Partial | one CDR heuristic; no TCG/signalling → `TELCO-BYPASS-SIMBOX-1` |

- **To reach the next level:**
  1. Fix `ANOMALY-TF-STORE-CLASH-1` and ship the anomaly Alert Rule ready to use instead of pending.
  2. Build `PACK-IRSF-WANGIRI-1` (destination-risk Reference) and `TELCO-BYPASS-SIMBOX-1` B1–B3 so rows E and H in the matrix move from P to Y.
  3. Calibrate thresholds on one real operator corpus and classify the export sink.

---

#### solution-pack.business-assurance — Business assurance

- **What it is (customer view):** a Space Template that forecasts daily revenue with a seasonal prediction band and flags margin erosion by product, channel and partner, with Alerts, KPIs and a dashboard.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 2 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Template `spaces/_templates/business-assurance/`: 3 derived views (`config/views/`, a Holt-Winters additive forecast with weekly season and 4-sigma band, plus margin erosion over baseline vs recent 28 days), 2 `sql.template` Jobs (both `enabled: false`), 5 Alert Rules, 3 KPIs, 5 Widgets, the `ba_overview` dashboard, an 86-line runbook and 2 seed Parquet files. No Pipeline is included.
  - **Detection models: 2** (revenue forecast with 3 flags: outside band, regime change, drift; and margin erosion with a data-quality status). In Basic edition `contentPacks` (`offerings/basic.toon`).
  - `platform/inspecto-engine/src/test/java/com/gamma/alert/BusinessAssurancePackGoldenTest.java` (11 tests): planted detections with no false positive, level shift, ramp, slow-ramp absorbed, a second corpus, bad margin input flagged, SqlGuard on each Job. Also `BusinessAssuranceSinkDatasetTest` (1) and `ForecastTemplateGoldenTest` (2, template vs analytics kernel parity).
  - `ASSURE-PACK-BUSINESS-ASSURANCE-1` was closed 2026-10-10 after a **live drive** (*Evaluate now* fired `ba_revenue_outside_band` CRITICAL and `ba_margin_erosion` 11.25 pp). An independently built second corpus passed with the shipped constants (`docs/BACKLOG.md` line 133). The hub has no open rows.
  - Limits (`architecture.md`): no ingest; model parameters are SQL literals, not config keys; one weekly season; no holiday calendar; fixed 28-day windows. No column classification (grep: 0 hits).
  - Competitive matrix row P = **Y** (all six vendors Y/P).
- **Strengths vs market:**
  - Explainable statistical forecasting in plain SQL with honest look-alike tests and a second-corpus check, with no data-science dependency. It ships in the Basic edition.
  - Data-quality status is kept separate from erosion, so bad feeds are not counted as margin loss.
- **Gaps vs market / RFP risk:**
  - Breadth: 2 models against leaders' BA suites covering order-to-cash, order-to-activation, product-catalogue and cost assurance, plus KPI catalogs with hundreds of KPIs *(general-market)*. **No row.**
  - Bring your own data: no Collector or Pipeline, and the Jobs ship disabled. **No row.**
  - Tuning means editing SQL literals; there is no holiday calendar and no multi-season support. **No row.**
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| KPI trend monitoring with forecast / thresholds | Comply | Holt-Winters band + regime/drift flags; `BusinessAssurancePackGoldenTest` |
| Margin / profitability by product, channel, partner | Comply | `ba_margin_erosion_view`; WARNING > 5 pp; live-driven 2026-10-10 |
| Order-to-cash / order-to-activation assurance | Not offered | no row |
| Network cost / supplier invoice assurance (TEOCO SmartCOGS class) | Not offered | no row |
| Self-service KPI catalog and model tuning | Partial | Studio BI KPIs generic; model constants are SQL literals |

- **To reach the next level:**
  1. Move model constants into Job parameters and add a holiday calendar Reference.
  2. Ship one ingest Pipeline for `daily_revenue` / `margin_lines` and enable the Jobs by default.
  3. File rows for order-to-cash and cost-assurance controls if BA RFPs are targeted.

---

#### solution-pack.industry-telecom — Industry pack telecom

- **What it is (customer view):** the telecom vocabulary for Link Analysis: subscribers, MSISDN, IMSI, IMEI, handsets and cells linked by calls, SMS, forwarding and shared devices, with ready-made ring and chain patterns.
- **Scores:** F 2 · B 4 · Q 3 · E 4 · R 2 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Classpath content in `platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/telecom/`: 6 Entity Types (4 `masked: true`: subscriber, msisdn, imsi, imei), 6 link kinds, 20 terms, 4 pattern packs, and "investigate a number" defaults (`industry-pack.toon`).
  - `IndustryPacksTest` (17 tests: fail-closed refusal of missing files, duplicate ids, unknown types or normalisers, neutral relabels) and `IndustryPackNeutralityTest` (4). `la/inspecto-geo-link/.../ControlApiTelecomPatternPacksTest.java` has 4 real-HTTP tests: subscription ring, forwarding chain and payment ring each find only their planted pattern.
  - Product map: `enforcement: contract` (not build-gated), bundled from Basic as part of the Link Analysis base (`features.md`).
  - Open rows tagged to this item are all market-frame gaps and all plan-only: `PACK-PARTNER-SETTLEMENT-1` (P3), `PACK-RERATING-1` (P3), `PACK-5G-IOT-ASSURANCE-1` (P3, demand).
  - The investigate default points at `telecom_links_dataset`, which only `la-showcase` provides (`registry/link-analysis-views/telecom_fraud_network.toon`). `telco-fraud` declares `domain_profile: telecom` but ships no such Dataset, so "investigate a number" there has no default target. What happens at runtime is unverified. **No row.**
  - Native ASN.1 CDR decoding is a separate item (`addon.telecomIndustryPack`, `providers/inspecto-telecom-asn1/`) and is not scored here.
  - Competitive matrix: C = **P**, Q = **P**, U = **N**. These are the rows this item carries on the BACKLOG.
- **Strengths vs market:**
  - The vocabulary uses the operator's own words in an i2-class Link Analysis UI, with masking on by default for identifiers. Discovery is fail-closed, so a broken pack never half-loads.
  - Pattern packs are tested over real HTTP against planted rings.
- **Gaps vs market / RFP risk:**
  - In operator RFP terms, "telecom industry pack" means settlement, re-rating and 5G/IoT assurance. All three are roadmap (`PACK-PARTNER-SETTLEMENT-1`, `PACK-RERATING-1`, `PACK-5G-IOT-ASSURANCE-1`).
  - Only 4 telecom patterns. Typical telecom LA/forensics suites also offer CDR-based co-location, IMEI churn and call-pattern similarity *(general-market)*. **No row.**
  - The paired `telco-fraud` template lacks the investigate-default Dataset (see Evidence). **No row.**
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Telecom entity model (MSISDN / IMSI / IMEI / cell) with masking | Comply | 6 Entity Types, 4 masked; `IndustryPacksTest` |
| Native CDR formats (ASN.1, vendor layouts) | Partial | separate `addon.telecomIndustryPack`; vendor mappings parked (`assurance-capability-plan.md` §4) |
| Partner / wholesale settlement | Roadmap | `PACK-PARTNER-SETTLEMENT-1` (plan, decisions PS-D1…10 open) |
| Re-rating / tariff validation pack | Partial / Roadmap | SQL re-rating in `telco-ra`; full pack `PACK-RERATING-1` plan only |
| 5G / IoT / digital service assurance | Roadmap | `PACK-5G-IOT-ASSURANCE-1`, demand hold |

- **To reach the next level:**
  1. Ship `telecom_links_dataset` (or a derived view over `cdr`) in `telco-fraud`, so its declared profile works out of the box.
  2. Build the smallest cut of `PACK-PARTNER-SETTLEMENT-1` (S0–S3) or `PACK-RERATING-1` (R0), so C and Q stop depending on the `telco-ra` SQL alone.
  3. Add co-location and IMEI-churn pattern packs.

---

#### Group notes

Every telecom pack is built and golden-tested, with planted positives and boundary look-alikes. Only
telco-fraud and business-assurance have recorded live or demo runs; no live bundle run of `telco-ra` was found.
All four are proven only on synthetic canonical data. Vendor feed mapping and threshold calibration are parked
until a customer exists, so none can claim L4 in an operator RFP, and the repo records no reference customer (no L5).

The market-frame gaps are honestly filed in the matrix: C, E, H and Q are P, and U, F and G are N. Every one is a
P3 row that is plan-only or on hold, except `TELCO-BYPASS-SIMBOX-1` (B0 measured). Real-time detection (RFP
knock-out) and RA control-catalog breadth are the two biggest RFP risks; the catalog has no BACKLOG row at all.

Data-protection posture differs: telco-fraud classifies and masks (proven over HTTP), while telco-ra and
business-assurance classify nothing beyond `INTERNAL`.

Not verified: vendor control and typology counts (general-market). Also not verified: runtime behaviour of
"investigate a number" in a telco-fraud Space, and whether `ANOMALY-TF-STORE-CLASH-1` reproduces in this checkout.

### E. Solution packs — fintech and commerce

#### solution-pack.aml — AML
- **What it is (customer view):** a ready-to-run AML Space for a bank or payment institution: four laundering typologies, an account Risk Score, a behavioural Anomaly Model, sanctions/PEP screening, money-trail Link Analysis and an STR draft, over a synthetic ledger.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Typology count: **4 rule scenarios** as `sql.template` Jobs + per-account CRITICAL Alert Rules — structuring, threshold crossing, smurfing fan-in, high-risk-jurisdiction wires (`spaces/_templates/aml/config/jobs/aml_{structuring,threshold,fan_in,high_risk_traffic}_job.toon`, `config/registry/alert-rules/*.toon`, 4 files); **2 model-based detectors** — Risk Score `aml_account` (3 weighted, capped factors, high ≥ 60; `registry/risk-scores/aml_account.toon`) and Anomaly Model `aml_account_transfers` (`method: isolation_forest`, 28-day weekday-seasonal baseline; `registry/anomaly-models/aml_account_transfers.toon`), each with a pending Alert Rule (`config/pending/alert-rules/`, 2 files); **4 investigative pattern packs** (circular flow, inbound collector, layering chain with 48 h hop ordering, pass-through) + 1 Link Analysis view.
  - Screening: `aml_screening` Job (`screening.run`, threshold 0.85, lists `aml_sanctions,aml_pep`) ships `enabled: false`; Entity Lists are not shipped (`config/jobs/aml_screening_job.toon`; architecture.md "Limits"). No vendor list-feed loader (OFAC / UN / World-Check) found — grep of `module/screening` docs and `features/inspecto-screening/src/main` returns nothing.
  - SAR/STR: `regulatory-report-templates/aml-str.toon` — 17-field XML, file-drop delivery, explicitly "follows no real regulator's schema"; engine is four-eyes (`docs/okf/product/module/regulatory-reporting/spec.md`); regulator formats are open P3 `REGULATORY-SAR-STR-TEMPLATES-1` (hold: demand).
  - Tests: `AmlPackGoldenTest` 4 (committed corpus = seeded generator output; golden corpus raises exactly the planted accounts per typology), `AmlScreeningGoldenTest` 2 (hits exactly the planted parties, no look-alike; STR template loads), `PackFraudAmlAnomalyPropertyTest` 3 of 5 on AML (forest ranks and explains the planted account), plus `ControlApiSpaceTemplateSeedGateTest` 12 (real-HTTP apply of every shipped template, capability refusal, pending-rule gating).
  - Live evidence: screening demo rehearsal 2026-10-14 recorded with 11 beats PASS, re-driven on an Enterprise Demo Auth bundle (`docs/BACKLOG.md` row `SCREENING-1`; `docs/okf/product/module/screening/spec.md` lines 150-166; `tools/seed-screening-demo.mjs` builds the Space from the `aml` template).
  - Enterprise hooks the pack uses: party `full_name` / `national_id` classified `PII` (masked) after the rehearsal; confirmed screening hit opens an Incident + Case; audited privileged reveal; four-eyes on `confirmed` (`SCREENING-1` D1-D3).
  - Corpus: ~1,200 ledger rows over 10 days + 127 parties (`data/samples/`); 3 KPIs, 7-tile dashboard, runbooks (57 lines). Closed P2 `PACK-AML-1` (2026-10-10).
- **Strengths vs market:**
  - One Space links typology hit → account risk → network (pattern packs, cycles) → STR draft; Actimize / SAS / Oracle FCCM typically split monitoring, screening and investigation into separately licensed modules (general market).
  - Every scenario is readable SQL with configuration thresholds and golden tests that prove exact hits and no look-alikes — strong explainability for model-risk reviewers.
  - Screening matcher handles transliteration and Cyrillic (Gaddafi/Qadhafi, Muhammad/Mohamed) with four-eyes and audited reveal.
- **Gaps vs market / RFP risk:**
  - Scenario library is 4 rules + 2 models vs the hundreds of out-of-box scenarios (CTR, rapid movement, dormant reactivation, trade-based, peer-group by segment, customer due-diligence risk rating) leaders ship (general market) — no row.
  - No sanctions/PEP list-feed ingestion or list-management workflow (delta updates, list versioning by publisher); lists are created by hand through the API — no row.
  - STR is illustrative; no goAML / FinCEN SAR schema — `REGULATORY-SAR-STR-TEMPLATES-1` (P3, hold: demand).
  - Daily batch only; no real-time transaction screening / interdiction — `REALTIME-DECISIONING-1` (P3). Ledger `account_id` is classified `CONFIDENTIAL`, which is not in `ColumnClassification.SENSITIVE` (`MSISDN IMSI ACCOUNT PII`), so account numbers are stored unmasked — no row.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Typology / scenario coverage | Partial | 4 rule scenarios + Risk Score + Anomaly Model + 4 LA pattern packs, golden-tested; library far smaller than leaders; authoring new scenarios is SQL configuration |
| Sanctions / PEP screening | Comply (with caveat) | `screening.run` + fuzzy/phonetic matcher, four-eyes, Case linkage, rehearsal PASS; customer must load lists — no vendor feed connector |
| SAR / STR filing | Partial | four-eyes Report Template engine + file drop; shipped template is illustrative, regulator schema is P3 `REGULATORY-SAR-STR-TEMPLATES-1` |
| Model governance | Partial | Risk Score versions + approval policy (`module/scoring/spec-risk-scores.md` ~l.272), explainable factors and anomaly contributions; no model validation / backtest / champion-challenger record found |
| Real-time scoring / interdiction | Not offered | daily batch (architecture.md Limits); `REALTIME-DECISIONING-1` P3 roadmap |

- **To reach the next level:** (1) ship a sanctions/PEP list-feed Collector (UN / OFAC / EU consolidated, delta + version) and file a row for it; (2) author one real regulator STR/SAR template (goAML) under `REGULATORY-SAR-STR-TEMPLATES-1` and classify `account_id` / `counterparty_id` as `ACCOUNT`; (3) widen the scenario library (CTR, rapid in-out, dormant reactivation, peer-segment) with golden tests.

#### solution-pack.payment-fraud — Payment fraud starter
- **What it is (customer view):** a starter Space for a PSP or card issuer that flags card testing, BIN attacks, velocity bursts and SIM-swap account takeover from payment attempts, SIM changes and disputes, with a per-account Risk Score.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 2 → **Level L3 (Pilot-ready)** — as post-authorisation batch monitoring only.
- **Evidence:**
  - Typology count: **4** per-entity CRITICAL Alert Rules — card testing (device, ≥ 8 instruments at ≤ 2.00 in 24 h), BIN attack (≥ 15 declined instruments per BIN in 24 h), velocity burst (≥ 6 attempts per instrument in 60 min), SIM-swap takeover (approved ≥ 200 on a new device ≤ 24 h after SIM change) (`spaces/_templates/payment-fraud/config/registry/alert-rules/`, 4 files).
  - 8 Jobs: 7 `sql.template` feature builders on rolling windows + `pf_risk_score` (`config/jobs/`); Risk Score `payment_account` has 4 factors (SIM-swap payments, velocity, declines, disputes; high ≥ 60) and a pending high-risk Alert Rule.
  - Label management: `pf_attempt_labels` derives DISPUTED / NEGATIVE / UNMATURED with a 120-day maturity flag — the training-label hygiene a supervised model needs, but no supervised model consumes it.
  - PCI-relevant ingest guard: every row scanned for a Luhn-valid card-shaped number; a hit quarantines the file (`template.toon` description). `PaymentFraudTemplateGoldenTest` **12 tests** (exact planted cases per typology; card number in any spelling / column / file name / header / chunk / malformed row never lands; 1 MB digit cell scanned quickly) + `PaymentFraudDashboardTest` 1 (every tile renders the planted values).
  - Data columns are tokenised by design (`INSTRUMENT_TOKEN` "never a card number"); all classified `INTERNAL` (`config/payment_attempts/payment_attempts_schema.toon`).
  - Hourly batch: "Features are hourly batch, not authorisation-time scoring" (`docs/okf/product/solution-pack/payment-fraud/architecture.md` Limits). Closed P2 `ASSURE-PACK-PAYMENT-FRAUD-1` as BUILT "further typologies on customer request" (BACKLOG l.163); the two PAN-leak rows it spawned (`INGEST-REJECT-SIDECAR-RAW-PAN-1`, `INGEST-FAILURE-TEXT-QUOTES-VALUE-1`) are no longer open.
  - Corpus: ~1,060 attempts over 3 days, 15 SIM changes, 6 disputes (`data/samples/`); runbooks 73 lines.
- **Strengths vs market:**
  - SIM-swap × payment correlation joins telecom and payment signals — a natural edge for a telco-owned PSP / mobile-money operator that card-only vendors do not get from their data (general market).
  - Ingest-time PAN quarantine is adversarially tested — useful PCI-scope-reduction story.
- **Gaps vs market / RFP risk:**
  - No authorisation-time / sub-100 ms scoring — the first line of any card-fraud RFP; FICO Falcon, Feedzai and Featurespace are built around it (general market) — `REALTIME-DECISIONING-1` (P3).
  - No supervised ML / adaptive behavioural profiling; the Risk Score is a hand-weighted factor table — `ANOMALY-ML-SCORING-1` shipped isolation forest for AML only; no row for a payment-fraud model.
  - 4 typologies; no account-takeover via credential change, merchant collusion / bust-out, friendly fraud, APP / mule scams, 3DS/chargeback reason-code analytics — no row ("further typologies on customer request").
  - No case/feedback loop specific to fraud (confirmed-fraud labels back to rules), no Link Analysis view or pattern pack in this pack — no row.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Real-time transaction scoring / decline | Not offered | hourly batch; `REALTIME-DECISIONING-1` P3 |
| Card-fraud typology coverage | Partial | 4 golden-tested typologies; small vs leaders |
| ML models / adaptive profiles | Partial | rule-weighted Risk Score + maturity-aware dispute labels; no supervised model |
| Model governance | Partial | Risk Score version + approval policy (platform); no performance monitoring / backtest on the dispute labels |
| Case management / chargeback workflow | Partial | platform Incidents / Cases receive Alerts; no dispute workflow in the pack |

- **To reach the next level:** (1) decide and design `REALTIME-DECISIONING-1` (inline allow/block) — without it the pack cannot answer a PSP RFP; (2) add a supervised or anomaly model that consumes `pf_attempt_labels` with a recorded backtest; (3) add 4-6 more typologies (ATO, mule, merchant collusion) with golden tests.

#### solution-pack.mobile-money — Mobile money
- **What it is (customer view):** a mobile-money assurance Space that proves e-money float against the trust bank, matches partner settlement, checks fees and agent commissions and flags agent and wallet abuse.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Typology count: **7** `sql.template` Jobs with per-entity Alert Rules — commission over/under-pay, fee mis-charge, agent split under the reporting limit, agent round-tripping (2 CRITICAL), dormant-wallet reactivation, KYC tier-limit breach, wallet-vs-core provisioning mismatch (`spaces/_templates/mobile-money/config/jobs/mm_*_job.toon`, `registry/alert-rules/`, 7 files).
  - **2 Reconciliations** run by `recon.run` at 04:00 — wallet vs bank float and wallet vs partner settlement, one-to-many, tolerance 0.01 (`registry/reconciliations/mm_bank_float.toon`, `mm_partner_settlement.toon`).
  - Agent Risk Score `mm_agent` (3 factors) and wallet Anomaly Model `mm_wallet_activity` with **KYC-tier peer groups** (`peers.by: kyc_tier`, `minGroupSize: 30`), both with pending Alert Rules.
  - Tests: `MobileMoneyPackGoldenTest` 5 (corpus = generator; every control flags exactly its planted cases and no look-alike; every dashboard tile renders the planted offenders), `MobileMoneyRiskScoreGoldenTest` 2, `PackFraudAmlAnomalyPropertyTest` 2 of 5 (planted wallet top, look-alikes normal), `ControlApiAnomalyTemplateApplyTest` 1 (peer model applies under the deployed safety policy, real HTTP), plus the shared seed-gate test.
  - Five feeds, ~725 wallet txns / 359 wallets / 359 subscribers / 56 settlement / 38 bank lines (`data/samples/`); 4 KPIs, 11-tile dashboard, runbooks (95 lines). Closed `PACK-MOBILE-MONEY-1` (2026-10-09).
  - Masking: `wallet_id` and `msisdn` are classified `CONFIDENTIAL` in `config/wallets/wallets_schema.toon` and `config/core_subscribers/core_subscribers_schema.toon`, which `ColumnClassification.SENSITIVE` (`platform/inspecto-util/.../ColumnClassification.java:26`) does not mask — while the industry pack marks `wallet` / `msisdn` Entity Types `masked: true`.
  - Matrix row R: Y vs LATRO Assure Fintech, Subex FraudZap, Mobileum RAID mobile-money risk (matrix l.18-20, 50).
- **Strengths vs market:**
  - RA (float, settlement, fee, commission) and fraud/AML-lite (split, round-trip, dormant, KYC) in one pack — LATRO's Assure Fintech is the closest peer and is largely managed-service; this is self-run configuration.
  - Peer-group anomaly by KYC tier, explainable, property-tested.
- **Gaps vs market / RFP risk:**
  - No screening, no STR template and no Link Analysis view inside the pack (LA is the separate `la-mobile-money` template) — a mobile-money AML RFP needs the `aml` pack alongside — no row.
  - Daily batch; no real-time agent/wallet blocking — `REALTIME-DECISIONING-1` (P3).
  - MSISDN / wallet ids stored unmasked (`CONFIDENTIAL` not a masked class) — no row.
  - Pending score Alert Rules must be created by hand after the first run (architecture.md Limits) — accepted design (`TEMPLATE-RISK-SCORE-ALERT-RULE-1` closed).
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Float / trust-account and partner reconciliation | Comply | 2 Reconciliations, golden-tested |
| Agent fraud typologies (split, round-trip, commission) | Comply | 3 agent controls + agent Risk Score, exact-hit goldens |
| Wallet / KYC controls (tier limits, dormant, mule) | Comply (with caveat) | KYC limit, dormant, peer anomaly; mule network analysis needs `la-mobile-money` |
| AML screening / STR for e-money | Partial | via the `aml` pack + Screening / Regulatory Reporting add-ons, not this pack |
| Real-time scoring / blocking | Not offered | daily batch; `REALTIME-DECISIONING-1` P3 |

- **To reach the next level:** (1) classify `wallet_id` / `msisdn` as `ACCOUNT` / `MSISDN` and add a test; (2) bundle a screening Job + STR template variant (or document the `aml` co-install as one offer); (3) record a live demo run of the pack (none found in the hub).

#### solution-pack.industry-mobile-money — Industry pack mobile-money
- **What it is (customer view):** a mobile-money vocabulary for Link Analysis — wallets, agents and MSISDNs linked by P2P, cash-in, cash-out, merchant pay, shared device and shared identity — so investigators see their own words and money-flow patterns.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Content: 3 Entity Types (wallet `masked: true`, agent, msisdn `e164`, masked), 6 link kinds, 16 relabelled terms, 5 pattern packs, measure/time hints (`platform/inspecto-entity-store/src/main/resources/META-INF/inspecto/industry-packs/mobile-money/*.toon`, 40 lines total).
  - Fail-closed loading: a pack with a missing / unparseable file or duplicate id is refused whole (`docs/okf/product/solution-pack/industry-mobile-money/architecture.md`, `IndustryPacks.java`).
  - Tests: `IndustryPacksTest` 17 (bundled packs load; references to shipped templates and pattern packs resolve; unknown entity type / normaliser / term refuses the pack), `IndustryPackNeutralityTest` 4 (core labels hold no pack word), `ControlApiDomainProfileTest` 7 (real-HTTP domain profile).
  - Commercial: `enforcement: contract` and "bundled in every Edition from Basic" (`product-map/solution-pack/industry-mobile-money.toon`; features.md) — billable `INS-IP-MOBILE-MONEY` but not enforced by build or licence check.
  - Paired templates `la-mobile-money` and `mobile-money`; no open rows.
- **Strengths vs market:**
  - Domain ontology with masking and normalisers baked in, tested for neutrality — the i2 / Quantexa-class "entity model per industry" idea at very low cost (general market).
  - Pattern packs (layering chain with time ordering, inbound collector, structuring) give investigators typology-shaped queries out of the box.
- **Gaps vs market / RFP risk:**
  - Vocabulary only — no entity resolution rules, scoring models or typology library of its own; value depends on the paired templates — no row.
  - Contract-only enforcement of a billable item (any Basic install has it) — commercial leakage risk — no row.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Domain data model for wallets / agents / MSISDN | Comply | 3 Entity Types, 6 link kinds |
| Money-flow pattern detection (layering, mule, cycles) | Comply | 5 pattern packs, LA tools |
| PII masking of subscriber identifiers in investigation | Comply | `masked: true` on wallet / msisdn; reveal via LA capability |
| Entity resolution across identifiers (shared device / ID) | Partial | link kinds exist; resolution rules not in the pack |
| Localisation of terms | Partial | 16 English terms; no translations found |

- **To reach the next level:** (1) add entity-resolution hints (shared device / ID linking rules) and a mule-scoring suggestion; (2) decide whether the item is enforced (licence key) or is just a free bundle and fix the Product Map accordingly.

#### solution-pack.orders-starter — Orders starter
- **What it is (customer view):** a first-Space sample — a 3-day retail-orders CSV feed wired to a Pipeline, a non-null Expectation, a Dataset and a two-tile dashboard.
- **Scores:** F 1 · B 4 · Q 2 · E 2 · R 1 → **Level L2 (Prototype)** — by design an onboarding sample, not a commerce solution.
- **Evidence:**
  - Contents: 1 Pipeline + schema (derived `GROSS`, gap detection, duplicate check), 1 Expectation (`orders_id_non_null`), 1 Dataset, 2 Widgets, 1 Dashboard, 3 sample files of 12 rows each (`spaces/_templates/orders-starter/`, 245 lines total).
  - No Jobs, Alert Rules, scores or typologies ("a starter to copy for a first feed" — architecture.md Limits).
  - Tests: no golden test; only `ControlApiSpaceTemplateSeedGateTest#everyShippedTemplateApplies` plus use as a fixture in `RefusalQuarantineTest` and `ConfigMigratorTest`.
  - Schema columns classified `INTERNAL` or blank; no masking need on the sample.
  - Yet it is a **billable** Product Map Item with order code `INS-SP-ORDERS-STARTER` (`product-map/solution-pack/orders-starter.toon`) and in Basic `contentPacks` (`offerings/basic.toon`).
- **Strengths vs market:**
  - Fast time-to-first-dashboard for an evaluator; exercises ingest, quality and BI in one step.
- **Gaps vs market / RFP risk:**
  - Not a commerce / order-fraud or order-to-cash assurance offer (no order-to-invoice reconciliation, no refund / promo abuse) — no row.
  - Billing a sample with an order code invites "what do I get?" in a price review — no row.
- **RFP stance on the 5 most-asked requirements for this category:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Order-to-cash reconciliation | Not offered | platform Reconciliation exists; no pack content |
| Order / refund / promo fraud typologies | Not offered | no Jobs or Alert Rules |
| Data quality on order feeds | Comply (basic) | 1 non-null Expectation |
| Commerce KPIs dashboard | Partial | 2 tiles (count, revenue by region) |
| Quick evaluation / onboarding | Comply | applies in every edition from Basic, tested by the seed gate |

- **To reach the next level:** (1) either reclassify as a free sample (drop the order code) or grow it into an order-to-cash assurance pack with a Reconciliation and 3-4 typologies; (2) add a golden test.

#### Group notes
All five hubs have no plan / spec / tests slot and no open BACKLOG rows, so "built" is judged from the template files and golden tests, which are strong (exact planted-hit goldens per typology for AML, payment fraud and mobile money). The decisive cross-cutting RFP gap is real-time scoring: every pack is daily or hourly batch and `REALTIME-DECISIONING-1` is only a P3 design placeholder, which disqualifies the payment-fraud pack from a PSP card-authorisation RFP. Scenario libraries are deliberately small (4 / 4 / 7) against leaders' hundreds; sanctions list feeds, a real regulator SAR/STR schema and model validation records (backtest, champion/challenger) are absent, and only the SAR item has a row. Masking gap found in code with no row: AML `account_id` and mobile-money `wallet_id` / `msisdn` are `CONFIDENTIAL`, outside `ColumnClassification.SENSITIVE`. Unverified: the `inspecto-demo/spaces/_templates/aml` copy lacks `method: isolation_forest` (bundle may lag source); no recorded live demo exists for payment-fraud or mobile-money; leader comparisons are general-market knowledge.

### F. Link Analysis packs, Inspecto LA and the Editions

#### solution-pack.la-showcase — Link Analysis showcase (mule ring + telecom + geo)
- **What it is (customer view):** a ready-made demo Space with synthetic money-mule and telecom-fraud data, saved views, Geo Views and five demo personas. It walks the Inspecto LA story from Alert to Investigation to Dossier.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset. It is not a sellable SKU.
- **Evidence:**
  - Template: 72 files, about 40 300 CSV rows (`spaces/_templates/la-showcase/`). It covers a mule ring, 5 telecom typologies (Wangiri, IRSF, SIM box, subscription fraud, mules) over 8 sixty-day feeds, a geo scene with 3 Geo Views, 3 Alert Rules, 3 pattern packs and 4 link-analysis views (`template.toon` `contents[11]`).
  - It ships as a turnkey demo bundle. `-Edition Advanced -DemoAuth -Ui la-app` bakes `la-showcase` pre-seeded into `inspecto-demo-<platform>.zip`: unzip, `serve-demo`, pick a persona (`solution-pack/la-showcase/admin-guide-variants-runbook.md` §0).
  - Tests: `LaShowcaseTemplateTest` 6 @Test, `LaShowcaseGoldenTest` 3, `ControlApiLaShowcaseFiguresTest` 1, `ControlApiLaShowcaseGeoSceneTest` 3. Runners: `tools/la-demo-smoke.mjs`, `seed-la-showcase.mjs`, `gen-la-showcase-data.py`, `gen-la-telecom-data.py`.
  - Live, Postgres variant (2026-10-13): `la-demo-smoke` 17/17, also after a restart, and `PgInvestigationStoreContractTest` 45/0/0 (variants runbook, "Not verified" section).
  - Live, browser, 2026-10-15: analyst coverage (`module/la-core/tests-analyst-coverage.md`) on this Space. 11 use cases: 6 PASS, 4 PARTIAL, 1 with axe 0 violations. 16 findings: 5 fixed, 11 open, all P3 except fixed P2s.
  - The stakeholder demo runbook `module/la-core/admin-guide-demo.md` (371 lines) still flags several beats "UNVERIFIED (rehearse first)": compare-and-seal, the masked-id set, and the la-app approve-then-step text.
- **Strengths vs market:**
  - A pre-seeded, one-click demo with governance on screen: masking, four-eyes on fan-out, Drafts, a sealed Dossier. i2 and Linkurious demos typically show charting, not evidential custody (general market).
  - It is multi-typology and multi-surface (graph, geo, telecom MSISDN profile) in one Space, and the planted outcomes are pinned by golden tests, so the demo cannot silently drift.
- **Gaps vs market / RFP risk:**
  - It is not in any Offering although brochures sell it with Advanced/Preview (`OFFERING-DECLARATION-DRIFT-1` D2).
  - Synthetic data only. "Recorded precision claims: none" (la-audit runbook §6 states the same policy). Buyers comparing with Quantexa or Featurespace-style references will ask for detection rates on real data: no row.
  - Some walk-through beats are still unrehearsed, and 11 analyst-coverage findings are open (F-7, F-10, F-11, F-2, F-3 and others). They are tracked only in `tests-analyst-coverage.md`: no BACKLOG row.
  - It needs Demo Auth (Advanced/Preview). A Standard customer with real OIDC cannot use the seeded personas. The default runbook says the "Advanced or Standard bundle" works, but `minEdition: advanced` refuses Standard: no row.
- **RFP stance on the 5 most-asked requirements (LA proof-of-value / demo content):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Live demo of ring detection on representative data | Comply | pre-seeded demo zip; `LaShowcaseGoldenTest`; analyst coverage UC1 PARTIAL |
| Telecom fraud typologies (SIM box, IRSF, Wangiri) | Comply (demo) | telecom corpus + Alert Rules in template; synthetic only |
| Governance shown (masking, four-eyes, audit) | Comply | UC5 four-eyes PASS, UC10 audited reveal PASS |
| Evidence export that can be verified | Comply | UC6 Dossier "all 9 recorded hashes agree" |
| Run on the customer's own data in a PoV | Partial | views and Jobs must be re-pointed by hand; no mapping wizard for the pack |
- **To reach the next level:** list it in `offerings/preview.toon` (close D2); move the 11 open analyst findings onto BACKLOG rows; rehearse and remove every "UNVERIFIED" beat in `admin-guide-demo.md`.

#### solution-pack.la-showcase-off — Link Analysis showcase (feature off)
- **What it is (customer view):** an empty companion Space with Link Analysis switched off. It shows that a Space can disable the capability (`/inv/*` answers 404 `MODULE_DISABLED`) while the Space's other routes keep working.
- **Scores:** F 1 · B 3 · Q 2 · E 3 · R 2 → **Level L2 (Prototype)**. It is a demo prop, not sellable.
- **Evidence:**
  - Template: 5 files: `modules.toon` (geoLink disabled), roles, demo users, an SLA example and `template.toon` (`spaces/_templates/la-showcase-off/`).
  - `LaShowcaseTemplateTest` asserts the template has no registry, and that its `roles.toon` equals la-showcase's (fixed in `f1b64ce03` after it drifted; la-audit runbook §6 "Known limits").
  - Admin guide: 2 steps, create then verify (`solution-pack/la-showcase-off/admin-guide.md`, 14 lines). No live drive is recorded for this Space. The variants runbook's live record covers the Postgres variant only.
  - `OFFERING-DECLARATION-DRIFT-1` recommends leaving it unlisted in every Offering.
- **Strengths vs market:** it answers the RFP line "can a capability be disabled per tenant or business unit" in under a minute, using the platform's per-Space module switch.
- **Gaps vs market / RFP risk:**
  - Nothing asserts 404 `MODULE_DISABLED` against a Space created from this template. I found only the template-shape assertions: no row.
  - The demo users ship but no data, so it demonstrates nothing beyond a 404.
- **RFP stance on the 5 most-asked requirements (tenant feature control):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Disable a module per tenant/Space | Comply | `modules.toon`; behaviour unverified on this template |
| Other routes unaffected | Partial | stated in `template.toon`; no test found on this template |
| Same identities across Spaces | Comply | roles identical, `LaShowcaseTemplateTest` |
| Audit of the switch change | Not offered here | no audit beat in the pack |
| Self-service toggle in UI | Partial | config file; UI toggle unverified |
- **To reach the next level:** add a real-HTTP test that creates a Space from this template and asserts `/inv/*` 404 plus `GET /pipelines` 200, or fold the prop into the la-showcase runbook and retire the separate item.

#### solution-pack.la-audit — Link Analysis audit showcase (procure-to-pay and GL)
- **What it is (customer view):** an internal-audit demo that turns procure-to-pay and journal data into a graph. It surfaces a fictitious vendor, a shell cluster, split invoices, a single point of control and an intercompany round trip, and ends with a Dossier that fails verification when one byte changes.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset.
- **Evidence:**
  - Template: 45 files, about 4 000 CSV rows. 5 Pipelines; 12 Datasets (6 feed, 6 link); 6 `sql.template` link Jobs plus index and detect Jobs; 3 views; 5 personas; a committee portal (`spaces/_templates/la-audit/`, `portal/committee-portal.html`).
  - Tests: `LaAuditTemplateTest` 6, `LaAuditGoldenTest` 9; `tools/la-audit-smoke.mjs`, `seed-la-audit.mjs`, `gen-la-audit-data.py`, each with `.test.mjs`.
  - Live rehearsal on 2026-10-16 (Advanced Demo Auth): smoke 30/30. In the browser: Cut points and Betweenness, Dossier verify, four-eyes. Over HTTP: 7 server Graph Runs, Draft rebase/promote, embed mint/revoke (`solution-pack/la-audit/admin-guide-showcase-runbook.md` §6).
  - The rehearsal found and fixed 5 defects: the seed silently building nothing, a refused Alert Rule, the Draft entity, the bridge plant, and portal framing. That is evidence the pack was driven, not only written.
  - Recorded "Not driven": the in-browser Jaccard, Link prediction and spanning forest; the Draft and Members panels in the browser; GL cycles in the browser.
- **Strengths vs market:**
  - Segregation-of-duties and vendor-employee identity overlap shown as structure (bridges, cut points), not as row filters. Classic audit analytics tools (Caseware IDEA, Diligent/ACL, general market) are table-first.
  - The sealed Dossier gives a workpaper with tamper evidence, which external auditors value.
- **Gaps vs market / RFP risk:**
  - Breadth is narrow next to audit analytics suites (general market: journal-entry testing libraries, Benford, sampling). It covers 6 planted stories. No row for an audit test library.
  - The neutral `generic` Domain Profile labels approval graphs generically, and masked ids leak into Investigation titles ("Vendor masked:… workpaper"). Recorded as "Observations, not fixed": no row.
  - Synthetic data, no precision claims, not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **RFP stance on the 5 most-asked requirements (audit analytics / P2P fraud):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Vendor-employee conflict detection | Comply (demo) | V-117/E-31 plant; `LaAuditGoldenTest` |
| Split-invoice / threshold avoidance | Comply (demo) | 30 invoices under 10 000 by E-23 |
| Duplicate payments | Comply (demo) | `ap_duplicate_payments` Job, 13 pairs |
| Tamper-evident workpaper | Comply | Dossier verify and fail on one altered hash (smoke 30/30) |
| Journal-entry test library / ERP connectors | Not offered | CSV feeds only; no SAP/Oracle connector in the pack |
- **To reach the next level:** add an audit Domain Profile (labels), file rows for the two observations, and list the pack in an Offering.

#### solution-pack.la-claims — Link Analysis claims showcase (insurance SIU)
- **What it is (customer view):** an insurance Special Investigations Unit demo. A staged-accident ring behind one garage, one clinic and one lawyer is hidden among 380 claimants and 520 claims, with benign look-alikes, geo clustering and identity resolution.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**, as a demo asset.
- **Evidence:**
  - Template: 44 files, about 3 350 CSV rows, 6 feeds. `claims_links` covers 7 link kinds. 3 views, 3 Geo Views, a partner portal; masked `party` Entity Type (`spaces/_templates/la-claims/`).
  - Tests: `LaClaimsTemplateTest` 5, `LaClaimsGoldenTest` 8, which pins the ground truth of every planted story; `tools/la-claims-smoke.mjs`, `seed-la-claims.mjs`, `gen-la-claims-data.py`.
  - Live rehearsal on 2026-10-16 (Advanced Demo Auth, port 8142): `la-claims-smoke --embed` 29/29. Geo, identity resolution (through the API), the masked Entity profile, Dossier with external references, and portal framing plus revoke were verified (`solution-pack/la-claims/admin-guide-showcase-runbook.md` lines 3–4, 62–86).
  - Scene 0–2 (seed and the busy clinic) is marked "UNVERIFIED on the canvas". Run times are not recorded (runbook line 107).
- **Strengths vs market:**
  - Benign look-alikes (a family sharing a phone, a busy clinic) are planted, so the presenter can show "centrality alone is not guilt". This answers the false-positive objection that SIU buyers raise against network scores (general market).
  - Masked party identities with audited reveal and an embed portal for partner review.
- **Gaps vs market / RFP risk:**
  - Claims-fraud leaders (general market: Shift Technology, FRISS, SAS) sell scoring at first notice of loss, text and image analytics, and policy-system integration. This pack is graph-only over CSV extracts: no row.
  - Identity resolution here is an asserted merge. Fuzzy merge suggestions were retired to §6 (`LA-I2-GAPS-RESIDUALS-1`, D-M1), so duplicate-claimant detection relies on the analyst.
  - Not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2). There is a mobile-money/claims "vertical" pitch, but no billable claims SKU exists.
- **RFP stance on the 5 most-asked requirements (insurance claims fraud):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Organised-ring detection across claims/parties/providers | Comply (demo) | `LaClaimsGoldenTest`; smoke 29/29 |
| Geo clustering of incidents | Comply (demo) | `claims_junction_cluster_map` verified live |
| Duplicate identity resolution | Partial | asserted merge only; fuzzy suggestions retired (D-M1) |
| Real-time scoring at first notice of loss | Not offered | batch link Jobs on cron |
| Integration with claims system / case handoff | Partial | inbound/outbound integration routes exist (Standard+); not shown in this pack |
- **To reach the next level:** rehearse scene 0–2 on the canvas and record run times; add a claims-system handoff beat using `POST /inv/investigations/{id}/handoff`; decide the Offering listing.

#### solution-pack.la-mobile-money — Link Analysis mobile money
- **What it is (customer view):** a mobile-money fraud and AML demo: 420 wallets, 40 agents and 14 days with seven planted stories (agent-assisted structuring, mule chain, device farm, cycle, dormant wake, benign biller hub, Alert to Case), plus a regulator portal.
- **Scores:** F 3 · B 3 · Q 3 · E 3 · R 3 → **Level L2 (Prototype)**. The cap allows L3, but the browser walk-through was never done.
- **Evidence:**
  - Template: 41 files, about 7 000 CSV rows (14 landed days plus staged days). `mm_links` covers 6 link kinds; masked `mm_kyc` reference; `portal/regulator-portal.html` (`spaces/_templates/la-mobile-money/`).
  - Tests: `LaMobileMoneyTemplateTest` 5, `LaMobileMoneyGoldenTest` 9; `tools/la-mm-smoke.mjs`, `seed-la-mm.mjs`, `gen-la-mm-data.py`.
  - Live pass on 2026-10-16: seed `--apply` clean, smoke **10/10**. "The browser walk-through of the Graph page is not part of this pass" (`solution-pack/la-mobile-money/admin-guide-showcase-runbook.md` lines 3–4).
  - Scene M-10 (dormant wake) is UNVERIFIED: "the pack is not shipped for this vertical". M-13 (Dossier) is UNVERIFIED on this Space (runbook lines 85, 90).
  - Index build is on a 5-minute cron because `la.index.build` fires only on a clock or a Pipeline commit (runbook line 22).
- **Strengths vs market:**
  - Agent-network typologies (agent-assisted structuring, cash-out concentration) are specific to mobile money and seldom pre-packaged by general LA tools (general market).
  - The regulator portal embed shows a masked, revocable, read-only Investigation for a supervisor.
- **Gaps vs market / RFP risk:**
  - No recorded browser rehearsal. Two scenes are unverified, one of which depends on a temporal pattern pack not shipped for the vertical: no row.
  - It overlaps the billable `solution-pack.mobile-money` (Standard). The preview pack and the GA pack sit side by side, and the relationship between them is not documented in the brochures I read: no row.
  - Synthetic data only; not in any Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **RFP stance on the 5 most-asked requirements (mobile-money fraud/AML):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Structuring under KYC tier limits | Comply (demo) | AG-18 plant; golden test |
| Mule / pass-through chain tracing | Comply (demo) | `HUB-01`→`AG-39` 3 hops; pass-through 0.98 |
| Device / shared-identity farms | Comply (demo) | `mm_device_farm` view |
| Regulator read-only access | Partial | portal embed; this Space's Dossier unverified |
| Real-time transaction monitoring | Not offered | daily files plus cron link Job |
- **To reach the next level:** do one browser rehearsal and record it; ship or drop the temporal pack beat (M-10); state in the brochure how this pack relates to `solution-pack.mobile-money`.

#### solution-pack.la-daily-feed — Link Analysis daily feed
- **What it is (customer view):** a reusable daily feed (one telecom XDR CSV per UTC day, partitioned by event date) that lands each day, checks freshness and row-count band, and appends it to the Link Analysis index. It is the only priced Link Analysis pack.
- **Scores:** F 2 · B 3 · Q 4 · E 2 · R 2 → **Level L2 (Prototype)** in its listed edition (Basic). The same pack on Standard and up would rate L3.
- **Evidence:**
  - Product map: `billable: true, orderCode INS-SP-LA-DAILY-FEED, billing flat, status ga` (`product-map/solution-pack/la-daily-feed.toon`). Listed in `offerings/basic.toon` `contentPacks`.
  - Template: 12 files. Pipeline plus schema; poll Collector (`XDR_{yyyyMMdd}.csv`); freshness and index-lag Alert Rules; row-band Expectation; `la_index_build` and `la_rows_band_evaluate` Jobs. Only **2 sample days of a few rows each** (`data/samples/daily_xdr/`).
  - Tests: `LaDailyFeedTemplateRulesTest` 6. `LaDailyIngestAcceptanceTest` covers 7 days, a late day, a re-run, a correction, a part-count change, a missing day and a dimension change. `ScheduledDailyChainTest` (geo-link) runs on the scheduler alone. Benches `LaDailyIngestScaleBench` / `DailyIndexScaleBench`: 10^7 rows a day ingest in 35–40 s, 10^8 in 489 s, APPEND 31 s onto 10^8 (BACKLOG `LA-DAILY-INGEST-1`).
  - Live drive on 2026-10-14 (`module/la-core/tests-data-trust-live-drive.md`): 8 steps, 1 FAIL (corrected day, since fixed by `f3a90e6a3`). At that time the template shipped no index Job; the Jobs were added later and are pinned by `theTemplateShipsTheJobsThatIndexAndEvaluateOnEveryCommit`.
  - **Edition mismatch.** The pack ships Alert Rules, but the Alert Rule dispatcher (`alert.dispatch`, declared by `inspecto-ops`) is Standard and up (`layer/platform/architecture-editions-model.md` §28; `OFFERING-DECLARATION-DRIFT-1` D1, open, hold: operator). The template has no `minEdition`, so the `tools/check-offerings.mjs` check 5 guard cannot catch it.
  - The index Job runs as `owner: admin` (`config/jobs/la_index_build_job.toon`), but Basic ships no Authenticator. By code read (2026-10-23, `LA-DAILY-INGEST-1`): on an open server the run authority is skipped (`JobAuthority.runAuthority`) and the template's Dataset has no `shares`, so `ComponentAccess.canViewAs` allows `admin`; with an Authenticator, `TemplateSeedGate.stampJobs` re-stamps the owner to the applier unless the applier holds `canConfigureAccess`. `LaIndexBuildJob` and `ScheduledLinkIndexBuilder` consult no edition feature (`platform/inspecto-engine/.../LaIndexBuildJob.java:59`, `la/inspecto-geo-link/.../ScheduledLinkIndexBuilder.java`), so the index probably builds on Basic while every index-consuming Investigation route is 503 there (unverified live).
  - The row-band Expectation "does not judge a day until 3 prior days exist" (`admin-guide.md`), and only 2 sample days ship. The out-of-box verify cannot show the band working.
- **Strengths vs market:**
  - The ingestion is measured to 10^8 rows a day with append-only index refresh and data-trust signals (freshness, gaps, quarantine, index lag). Neo4j Bloom and Linkurious leave this to customer ETL (general market).
  - The feed contract (stable name, one event date per file, gap detection by `{seq}`/date template) is documented for database exports too.
- **Gaps vs market / RFP risk:**
  - Sold in Basic, where its Alert Rules cannot dispatch and Investigations, the consumers of the index, are absent (`OFFERING-DECLARATION-DRIFT-1` D1).
  - No Hive/Kerberos or JDBC Collector path for Link Analysis, and no SCD2 / as-of join (deferred `D-ING4`/`D-ING5` in `LA-DAILY-INGEST-1`, P2, owner calls). Leaders ingest from warehouses directly (general market).
  - A same-name corrected file takes effect on the shipped feeds since 2026-10-14 (`2196ce8ea`: `collector.duplicate` checksum + `on_change: reprocess`; partition replaced, one `REDELIVERED`); a feed on the default path markers still skips it with `DUPLICATE_SKIPPED`.
  - Scale beyond 10^8 is unmeasured, and so are a single 10^8-row file and entity cardinality above 12 M at 10^8 rows (12.1 M nodes measured at 10^7 edges on 2026-10-20; `LA-DAILY-INGEST-1`).
- **RFP stance on the 5 most-asked requirements (scheduled LA ingestion):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Scheduled daily ingestion with incremental index refresh | Comply (Standard+) / Partial (Basic) | `ScheduledDailyChainTest`; Basic lacks the consumers |
| Late, missing and corrected file handling | Comply | `LaDailyIngestAcceptanceTest`; a corrected same-name day replaces its partition on the shipped feeds |
| Freshness / completeness alerting | Partial | Alert Rules ship; dispatch is Standard+ (D1) |
| Database / warehouse feeds (Hive, JDBC) | Roadmap | `D-ING5` deferred until a named customer feed |
| Proven scale for national telco volumes | Partial | 10^8/day on a laptop, synthetic; 10^9 not measured |
- **To reach the next level:** settle D1: set `minEdition: standard` or move the Alert Rules out of the Basic variant. Ship at least 4 sample days so the band demo works. Verify `owner: admin` behaviour on a Basic bundle and file a row for the result.

#### Inspecto LA (`la-app`) — the standalone Link Analysis product (module.la-core and its la/ modules)
- **What it is (customer view):** a graph investigation product over existing Datasets. Analysts explore who is connected to whom, run 29 graph algorithms, record the work as a sealed Investigation and hand it on as a verifiable Dossier, a Case link or a standing detection. It ships as a standalone app (`package.ps1 -Ui la-app`) or inside the console (`docs/GLOSSARY.md` "Inspecto LA", `docs/EDITIONS.md` "UI flavor").
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**.
- **Evidence:**
  - Modules: `la-core`, `la-storage`, `la-api`, `geo-link`, `la-store-pg` (`la/`). Routes: `la-api` 90 (90 in OpenAPI), `geo-link` 7 (`module/la-api/api-routes.md`, `module/geo-link/api-routes.md`).
  - Test classes: geo-link 108 (90 real-HTTP), la-api 40, la-core 23, la-storage 12, entity-list 5 (generated `tests-inventory.md` per hub). Plus 4 golden corpora tests in `platform/inspecto-graph`. UI: `projects/link-analysis` 130 specs, `projects/la-app` 9 specs (15 non-spec files: a thin shell).
  - Live evidence:
    - analyst coverage on 2026-10-15: 11 use cases, axe 0 violations (`tests-analyst-coverage.md`);
    - data trust drive on 2026-10-14;
    - real OIDC sign-in against WSO2 IS 7.3.0 on 2026-10-13 (`admin-guide-real-signin.md`);
    - Postgres store on 2026-10-13;
    - stakeholder Q&A with 22 PROVEN-tagged answers and 16 NOT YET mentions (`tests-stakeholder-qa.md`).
  - Edition placement is gated in code: Basic is the explorer; the Investigation surface answers 503 via `EditionFeatures.INVESTIGATIONS` (`ControlApiBasicEditionLinkAnalysisTest`, 2 tests, real HTTP).
  - Open work: P2 `LA-DAILY-INGEST-1` (owner calls only). P3s: `LA-I2-GAPS-RESIDUALS-1`, `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (defect, mostly done), `LA-ENTITY-PROFILE-RESIDUALS-1`, `LA-CONFIG-LIMITS-RESIDUALS-1`, `LA-A11Y-AUDIT-1` (screen-reader reading still person-only), `LA-INVESTIGATION-STORE-DESIGN-1`, `LA-INVESTIGATION-OPS-DEFERRED-1`, `LA-EDITIONS-RESIDUALS-1` (`module/la-core/index.md`).
  - Deliberately declined (BACKLOG §6 line 774): retention/purge of Investigation evidence (`D-U8`), a G6 `timebar`, and a server-rendered Dossier PDF.
- **Strengths vs market:**
  - Evidential custody is built in: a sealed op log, replay, a Dossier verifiable byte for byte, four-eyes on expansion, and masking with audited reveal. i2 ANB / Linkurious / Bloom chart well but do not seal the analysis path (general market). This is the clearest differentiator.
  - No separate graph database. It projects directly from DuckDB/Parquet Datasets with a versioned link index (10^8 edges measured), and server and browser algorithms are kept in parity (`GraphEngineParityTest`, 29 algorithms). That is lighter than a Neo4j + Bloom or Quantexa deployment (general market).
  - The operational loop goes from Alert Rule to Investigation to Case to standing detection inside one product. Linkurious needs a separate case tool; i2 ANB needs EIA/iBase (general market).
- **Gaps vs market / RFP risk:**
  - Entity resolution is assertion-based. Fuzzy merge suggestions were retired (`LA-I2-GAPS-RESIDUALS-1`, D-M1). Quantexa's core value is probabilistic entity resolution at billions of records (general market). This is the largest functional gap: no row beyond the §6 retirement.
  - Scale and topology: 10^9 is not measured, and multi-pod is not complete. The Helm chart is `replicas: 1` (`SCALEOUT-HELM-NPOD-1` P2, `LA-INVESTIGATION-STORE-DESIGN-1` P3). The stakeholder Q&A says NOT YET for multi-pod and for a two-host drill.
  - Retention / legal hold of Investigation evidence is declined (`D-U8`), yet `docs/EDITIONS.md` and the Advanced brochure list "retention, legal hold, residency" as Advanced roadmap. That is a contradiction, and legal hold and residency have no row.
  - Analyst ergonomics vs i2: no timeline / time-bar view (the G6 `timebar` was declined; only a timeline filter exists, `user-guide-manual.md` line 205), no i2 ANX import/export, no unstructured/document ingestion: no rows. Precision on real data is not measured (stakeholder Q&A line 201).
- **RFP stance on the 5 most-asked requirements (link analysis / investigation):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Interactive graph exploration, paths, neighbours, patterns, algorithms | Comply | 90 `la-api` routes; 29 algorithms; analyst coverage UC1–UC3 |
| Entity resolution across sources | Partial | identity assertions and Entity profile pages; no fuzzy/probabilistic ER |
| Court-ready, tamper-evident evidence export | Comply | sealed log + Dossier verify (UC6); PDF via browser only (`LA-DOSSIER-OUTPUT-1` declined) |
| Scale and HA (billions of edges, multi-node) | Partial | 10^8 measured; multi-pod incomplete (`SCALEOUT-HELM-NPOD-1`) |
| Access control, masking, audit, SSO | Comply (Standard+) | OIDC proven with WSO2; enforced masking + audited reveal; Basic is display-only |
- **To reach the next level:** run the two-host Investigation-store drill and close `SCALEOUT-HELM-NPOD-1`; decide and record (with a row) fuzzy entity resolution and Investigation retention/legal hold, and fix the EDITIONS/brochure contradiction; obtain one reference pilot on real data with a measured precision figure (L5 also needs reference customers, and none are recorded).

---

#### edition.basic — Basic
- **What it is (customer view):** the entry build for one analyst on one machine, with no sign-in. It gives Collectors, Pipelines, Expectations, Datasets, SQL, dashboards, the Link Analysis explorer, Entity Lists and three content packs.
- **Scores:** F 2 · B 4 · Q 3 · E 2 · R 2 → **Level L2 (Prototype)** for enterprise RFP fitness. By design it is a single-analyst desk.
- **Evidence:**
  - Offering: `offerings/basic.toon`, with modules `connectors, entity-list, la-storage, la-core, la-api, geo-link` and contentPacks `business-assurance, la-daily-feed, orders-starter`. Posture: `authMode none`, no Postgres.
  - Product map: `INS-ED-BASIC`, `billing per-node`, `enforcement: build`, `ga` (`product-map/edition/basic.toon`). No licence engine exists: `ModuleManifest.entitlementKey` is "reserved (D-MR3: no licence engine)… never consulted" (`platform/inspecto-util/.../ModuleManifest.java:10`; `layer/platform/spec-module-reorg-decisions.md` D-MR3).
  - Gating is tested by falsification: 9 `No*ShipsInThePersonalBuildTest` classes in `inspecto/src/test/java/com/gamma/control/`, plus `NoBackupTaskShips…` and `NoChannelShips…` (engine), `AbsentModuleRoutesTest`, `BootstrapEditionTest`, `EditionFeatureGateTest`, `ControlApiBasicEditionLinkAnalysisTest`.
  - Packaging: `release.yml` builds and signs a Basic bundle (`package.ps1 -Edition Basic`, line 189). Staged jars: 23 (`docs/EDITIONS.md` "Staged jars"). The Basic 503 has "not been re-probed on a built Basic bundle" (la-showcase variants runbook).
  - Masking on Basic is display only: "anyone may reveal" (`docs/EDITIONS.md` "Basic — the Link Analysis explorer").
  - Its own content is mis-tiered. `business-assurance` and `la-daily-feed` ship Alert Rules whose dispatcher is Standard+ (`OFFERING-DECLARATION-DRIFT-1` D1).
- **Strengths vs market:**
  - The install is zero-dependency (one process, no database, no IdP), which suits a presales or analyst desk. That is comparable to i2 ANB desktop or Neo4j Desktop/Bloom free use (general market).
  - It is upgraded by swapping the build, not migrating: Spaces and configuration carry over (`edition/basic/brochure.md`). This is held by the build-flavor model (`architecture-editions-model.md`).
- **Gaps vs market / RFP risk:**
  - No authentication at all. Competitors' entry tiers (Linkurious, Neo4j Enterprise starter, general market) still have users and roles. Basic cannot answer any SSO/RBAC RFP line (by design; no row needed, but it limits Basic to non-regulated use).
  - Per-node billing with no metering or licence key. The node count is honour-system: no row (D-MR3 decided "none").
  - Packaging ambiguity: the Basic brochure says the Link Analysis explorer is included, while `addon.linkAnalysis` is a separately priced Add-on (`INS-AO-LINK-ANALYSIS`, flat). Whether a Basic buyer pays twice is unstated: no row.
  - Content packs whose Alert Rules cannot fire (`OFFERING-DECLARATION-DRIFT-1` D1).
- **RFP stance on the 5 most-asked requirements (entry SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Ingest, validate, quality-check files | Comply | core platform, all editions |
| Users, roles, SSO | Not offered | `authMode none` |
| Audit trail | Partial | local audit log readable (`AuditLogRoutes`); no authenticated actor |
| Link analysis | Partial | explorer only; Investigations/Dossier are Standard |
| Alerting on data problems | Partial | Alert Rule dispatch is Standard+ (D1) |
- **To reach the next level:** close D1 (move or gate the two packs); state in the brochure and price book whether `INS-AO-LINK-ANALYSIS` applies to Basic; decide whether a minimal local sign-in belongs in Basic.

#### edition.standard — Standard
- **What it is (customer view):** the team build. It adds OIDC sign-in, roles, secrets, enforced masking, Incidents, Cases, SLA, Reconciliation, Risk Scores, Screening, Anomaly Detection, Regulatory Reporting, full Link Analysis (Investigations, Dossiers, Drafts, four-eyes) and five content packs.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**.
- **Evidence:**
  - Offering: `offerings/standard.toon`, with 18 modules over Basic, 14 Add-ons marked `status: built`, and contentPacks `mobile-money, aml, payment-fraud, telco-fraud, telco-ra`. Posture: OIDC, parquet events, bundled Postgres sidecar.
  - Staged jars: 41 first-party, 42 total (`docs/EDITIONS.md`). `release.yml` builds a signed Standard bundle without the policy jar (lines 238–244).
  - Absent-surface parity tests per module: `AnomalyAbsentSurfaceParityTest`, `ReconAbsentSurfaceParityTest`, `ScoringAbsentSurfaceParityTest`, `ScreeningAbsentSurfaceParityTest`, `EntityListAbsentSurfaceParityTest`, `GeoLinkAbsentSurfaceParityTest`, plus `OpsEditionFeaturesTest`.
  - The `tools/check-offerings.mjs` guard (CI `guards`) asserts Offering == bundle == pom profile and checks `minEdition` against each pack (check 5).
  - Real IdP sign-in proven once against WSO2 IS 7.3.0 (`module/la-core/admin-guide-real-signin.md`, 2026-10-13).
  - Open: `OFFERING-DECLARATION-DRIFT-1` (P3 defect, tagged `edition.standard`, hold: operator).
- **Strengths vs market:**
  - One SKU spans assurance, case and investigation, where competitors sell separate products. Examples: Linkurious + a case tool; i2 ANB + EIA; Subex or Mobileum modules priced per function (general market, and `layer/product/brochure-competitive-landscape.md`).
  - The full evidential Link Analysis is in the mid tier, following the principle "never sell weaker analysis or weaker evidence" (`docs/EDITIONS.md` "Inspecto LA feature placement").
- **Gaps vs market / RFP risk:**
  - Single node only. HA and scale-out sit in Advanced, and even there they are not built (`SCALEOUT-HELM-NPOD-1` P2).
  - No ABAC (Advanced only), which matters for multi-business-unit banks.
  - Nothing has shipped under this edition name. The last tag is `v3.9.0`, and 4.0.0 (the rename release) is pending (`git tag`; GLOSSARY migration note): no row for a first 4.0 release.
- **RFP stance on the 5 most-asked requirements (team / departmental SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| SSO via corporate IdP | Comply | `inspecto-oidc`; WSO2 run 2026-10-13 |
| RBAC + masking + audited reveal | Comply | `docs/EDITIONS.md` LA placement; masking enforced from Standard |
| Case and Incident workflow with SLA | Comply | `caseManagement`, `incidents`, `workflowSla` Add-ons built |
| High availability | Not offered | single node; HA is Advanced roadmap |
| Industry content (fraud, AML, RA) | Comply | 5 content packs with `minEdition: standard` |
- **To reach the next level:** cut 4.0.0 so the edition exists as a released, signed artifact; run the real-IdP runbook against a second IdP (Keycloak/Entra); close `OFFERING-DECLARATION-DRIFT-1`.

#### edition.advanced — Advanced
- **What it is (customer view):** the organisation build. It is Standard plus attribute-based access policy (`policy`), a PostgreSQL Investigation store for multi-node use (`la-store-pg`), operational objects on Postgres, and the intelligence module.
- **Scores:** F 3 · B 3 · Q 3 · E 3 · R 2 → **Level L3 (Pilot-ready)**, as Standard plus ABAC. Its headline HA/multi-node promise is not defensible yet.
- **Evidence:**
  - Offering: `offerings/advanced.toon` adds 3 modules (`policy`, `la-store-pg`, `intelligence`); posture `objectsBackend postgres`. 44 first-party jars (`docs/EDITIONS.md`).
  - `PolicyEditionFeaturesTest`, `ObjectsBackendEditionBootTest`. `PgInvestigationStoreContractTest` ran live 45/0/0 against Postgres 18 on 2026-10-13 (la-showcase variants runbook).
  - `la-store-pg` is "inert until `-Dinvestigations.backend=db`: every edition, Advanced included, defaults to the filesystem" (`docs/EDITIONS.md` "Assembly model").
  - The brochure promises "multi-node, highly-available deployments" (`edition/advanced/brochure.md`). But the only Helm chart is a `replicas: 1` StatefulSet "NOT MULTI-REPLICA SAFE" (`SCALEOUT-HELM-NPOD-1` P2), and the D14–D16 decisions are owed (`SCALEOUT-D14-D16-DECISIONS-1` P2, `SCALEOUT-OBJSTORE-DATABASE-DIR-1` P2).
  - "Scale-out, retention, legal hold, residency (not built yet)" is in the placement table (`docs/EDITIONS.md`). Investigation retention is declined (`D-U8`, BACKLOG line 774), and legal hold and residency have no row.
  - Its own showcases (`la-*` with `minEdition: advanced`) are listed in no Offering (`OFFERING-DECLARATION-DRIFT-1` D2).
- **Strengths vs market:**
  - ABAC that "can only narrow" what roles allow is a safe-by-construction model that RFP security reviewers favour (general market). Policy is a separate module, so Standard never carries it (release.yml lines 236–239).
  - An enterprise tier without a separate graph database or cluster keeps the SBOM small (`docs/EDITIONS.md` "Security direction").
- **Gaps vs market / RFP risk:**
  - HA/DR and scale-out are the reason to buy an enterprise tier (Palantir, Quantexa and Neo4j Enterprise clustering, general market). Here they are P2 rows not yet built (`SCALEOUT-HELM-NPOD-1`, `SCALEOUT-D14-D16-DECISIONS-1`), with no two-host drill (stakeholder Q&A).
  - Retention / legal hold / residency are promised as roadmap while retention is declined (`D-U8`). Legal hold and residency have no row.
  - The intelligence module is listed, but its customer value in this tier is not described beyond "AI-assisted" (the brochure). I could not verify its scope here; it belongs to the AI group.
- **RFP stance on the 5 most-asked requirements (enterprise SKU):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| ABAC / fine-grained policy | Comply | `inspecto-policy`, `PolicyEditionFeaturesTest` |
| HA / multi-node / DR | Partial | PG store contract proven; multi-pod and Helm N-pod not built (`SCALEOUT-HELM-NPOD-1`) |
| Data retention and legal hold | Not offered | retention declined `D-U8`; legal hold no row |
| Data residency | Not offered | no row |
| Shared Postgres persistence | Comply | `la-store-pg` (opt-in), objects on Postgres |
- **To reach the next level:** build and drill N-pod (close the three SCALEOUT P2s); either build retention/legal hold or remove them from the Advanced promise and file the decision; tone the brochure down to what is proven until then.

#### edition.preview — Preview
- **What it is (customer view):** an evaluation build that carries every module (today the same set as Advanced) with demo sign-in, for presales demos and design partners.
- **Scores:** F 4 · B 3 · Q 3 · E 2 · R 1 → **Level L2 (Prototype)**. It should not be a SKU at all.
- **Evidence:**
  - The product map makes it a priced GA SKU: `billable: true, orderCode INS-ED-PREVIEW, billing per-node, status ga` (`product-map/edition/preview.toon`).
  - The binding doc forbids that: "Preview — not a customer-facing tier… Never ship Preview to a customer, never advertise it externally… carries no compliance-scope guarantee at all" (`docs/EDITIONS.md` "Preview — not a customer-facing tier").
  - Its own brochure, written for customers, says "evaluation and incubation build, not a production tier" (`edition/preview/brochure.md`). No BACKLOG row tracks this contradiction.
  - `release.yml` packages Basic, Standard and Advanced only (lines 189, 244, 262), so Preview has no released artifact.
  - Posture differs from Advanced (`objectsBackend default` vs `postgres`, `offerings/preview.toon`), so "identical to Advanced" is true of modules, not of posture.
  - Guards: `tools/offering-classpath.test.mjs` "Preview carries every module…" and `tools/check-offerings.mjs`. The `pom.xml` `edition-preview` profile "does not auto-union" (`docs/EDITIONS.md`), which is a manual drift risk.
  - `offerings/preview.toon` lists no content packs, although the brochure says "All solution packs, including the Link Analysis showcases" (`OFFERING-DECLARATION-DRIFT-1` D2).
- **Strengths vs market:** one build shows the whole product with ready personas. That is a strong presales vehicle comparable to vendors' sandbox/trial environments (general market).
- **Gaps vs market / RFP risk:**
  - A priced, GA order code for a build the platform doc says must never reach a customer is a commercial and compliance risk: no row (`OFFERING-DECLARATION-DRIFT-1` covers only the pack listing).
  - Demo sign-in only, with no compliance scope, so it cannot answer security RFP lines.
- **RFP stance on the 5 most-asked requirements (evaluation / trial edition):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Full-feature trial | Comply | every module; demo bundles rehearsed live |
| Seeded demo content | Partial | showcases ship in the demo zip; not listed in the Offering (D2) |
| Production use | Not offered | `docs/EDITIONS.md` forbids it |
| Security / compliance scope | Not offered | "no compliance-scope guarantee" |
| Signed, released artifact | Not offered | not in `release.yml` |
- **To reach the next level:** set `billable: false, status: preview` (or drop the order code) in `product-map/edition/preview.toon`; close D2 by listing the showcases; add a check that the `edition-preview` pom profile equals the module table.

#### Edition ladder — defensibility of Basic / Standard / Advanced (/ Preview) as packaging
- **What it is (customer view):** three nested builds sold per node, differing by who uses them (one analyst, a team, an organisation), how much data and how much governance. Preview is an evaluation build.
- **Scores:** F 3 · B 4 · Q 4 · E 2 · R 3 → **Level L2 (Prototype)** *(calibrated 2026-10-20: E 2 caps the level; the mechanism alone is L3)* as a mechanism. The commercial enforcement is L2.
- **Evidence:**
  - Mechanism: build flavors with no branches. Absent modules answer 503 with an explanation (never 404), `/bootstrap` flags derive from registered routes, and each gated cell has a falsification test (`layer/platform/architecture-editions-model.md` recipe items 1–7, 28; 20+ edition test classes listed above).
  - Offering == bundle == pom profile is enforced in CI (`tools/check-offerings.mjs`, `tools/offering-classpath.mjs`, `tools/bundle-modules.mjs`). Customer trimming is available via `tools/customer-build.mjs` (`product-map/README.md`).
  - Enforcement is `build` only, with no licence engine (D-MR3) and no node metering, although every edition is billed `per-node`.
  - The ladder is clean on modules: Basic ⊂ Standard ⊂ Advanced ⊂ Preview through `includes:` in `offerings/*.toon`.
- **Strengths vs market:**
  - The ladder follows a recognised model: features are equal across tiers, and scale and governance differ. Neo4j Community/Enterprise and Linkurious tiers work the same way (general market). Keeping full evidential Link Analysis in Standard is a sales argument against i2's split of ANB desktop and EIA server (general market).
  - Physical absence of code (not a runtime flag) is a strong answer to "can a lower tier be unlocked by config": it cannot.
- **Gaps vs market / RFP risk:**
  - The top tier's differentiator (HA, scale-out, retention, residency) is mostly unbuilt (SCALEOUT P2s; `D-U8`; no rows for legal hold and residency). That makes Advanced vs Standard a thin step today: ABAC plus an opt-in Postgres store.
  - Per-node pricing without metering, and the Link Analysis Add-on double-listing in Basic, are both no row.
  - Preview as a GA order code: no row.
  - No release yet under the new names (last tag `v3.9.0`).
- **RFP stance on the 5 most-asked requirements (licensing and packaging):**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Clear tier matrix | Comply | `docs/EDITIONS.md` matrix, generated offerings matrix |
| Upgrade without migration | Comply | same Spaces/config across builds (brochures; build-flavor model) |
| Licence enforcement / usage metering | Not offered | D-MR3: no licence engine; per-node is honour-system |
| Enterprise tier with HA/DR | Partial | Advanced HA not built (`SCALEOUT-HELM-NPOD-1`) |
| On-prem / air-gapped install | Comply | self-contained bundles with embedded runtime (`release.yml` OPS-07 comment) |
- **To reach the next level:** remove Preview from the price book; decide metering (or move to flat or site pricing that needs none); deliver or descope the Advanced HA/retention promise; release 4.0.0.

---

#### Group notes

The Link Analysis showcase packs are well-built demo assets. Three are live-rehearsed with smoke runners (30/30, 29/29, 17/17), and the planted outcomes are pinned by golden tests, so they reliably support a proof-of-value conversation. None is sellable: all are `billable: false`, and none is listed in any Offering although two edition brochures sell them (`OFFERING-DECLARATION-DRIFT-1` D2). The one priced Link Analysis pack, `la-daily-feed`, is tiered into Basic, where its Alert Rules cannot dispatch (D1). Inspecto LA's real differentiator against i2, Linkurious and Bloom is evidential custody: sealed log, verifiable Dossier, four-eyes, audited reveal. Its largest gaps against Quantexa and Palantir-class products are probabilistic entity resolution, scale beyond 10^8 and multi-node HA.

Across editions the build-flavor mechanism is strong and well tested. The commercial layer is weaker:
- per-node billing with no licence engine or meter (D-MR3);
- Preview carries a GA order code that the platform doc forbids shipping;
- Advanced promises HA, retention, legal hold and residency, which are unbuilt or, for retention, declined;
- nothing has been released under the new edition names.

Unverified:
- Basic behaviour of `la_index_build` (`owner: admin` with no Authenticator; no edition check in the Job path);
- whether `la-showcase-off` actually answers 404 on a created Space;
- the scope of the `intelligence` module in Advanced.

No reference-customer evidence exists in the repo, so no item can be awarded L5.


## 7. References

- Market frame: [`brochure-competitive-matrix.md`](brochure-competitive-matrix.md),
  [`brochure-competitive-landscape.md`](brochure-competitive-landscape.md) — vendor columns are published claims, not verified behaviour.
- Open work: `docs/BACKLOG.md` (row ids cited above); controls and gaps G1–G10: `compliance/controls-matrix.md`.
- Every item's hub: [`../../index.md`](../../index.md) — plan, spec, guides, generated feature-db, api-routes and tests-inventory.
- Doc accuracy baseline: [`../platform/tests-doc-claim-audit-2026-10-19.md`](../platform/tests-doc-claim-audit-2026-10-19.md).
