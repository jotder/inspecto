---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — platform base
description: Part 2 of 8 of Product maturity assessment — every client-facing item: Per-item assessments
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
item: layer.product
audience: internal
status: plan
verified: 2026-11-19 last-edit
---

# Product maturity assessment — every client-facing item — Per-item assessments — platform base

Part 2 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Verdict, scoring and scorecard](plan-maturity-assessment-verdict.md) · next: [Per-item assessments — add-ons for operations, investigation and AI](plan-maturity-assessment-addons-operations.md).

## 6. Per-item assessments

### A. Platform base (every edition) and the enterprise baseline

#### cap.acquire — Data acquisition (module.acquire, module.connectors, module.connectors-kafka)
- **What it is (customer view):** Connections and Collectors that pull files and records from local or NFS directories, SFTP/FTP/FTPS, JDBC databases, S3/GCS/Azure Blob and Kafka into Pipelines. They come with dedup, watermarks, gap detection, retry and decompression.
- **Scores:** F 3 · B 4 · Q 3 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Seven requirements of record. ACQ-1/2/3/5/6/7 are shipped. ACQ-4 (a Must) is **partial**: object storage is shipped, NFS/SMB network share was refused by design (`module/elt/plan-elt.md` §ACQ.2, §6.1).
  - Connector factories in code: Sftp, Ftp, Ftps, DbExport (JDBC), S3, GCS, AzureBlob, Kafka (`providers/inspecto-connectors/src/main/java/com/gamma/connect/`), plus the local file system and a Dataset-entry collector.
  - Step Processor board, ACQ family: 4 delivered on every edition (SP-ACQ-01..04). 4 are partial: S3/GCS have no proven end-to-end acquisition-node run, ADLS Gen2 is unproven, Kafka is unproven against a real broker, Excel is one sheet per file. 11 are planned: Pulsar, Kinesis, SQS, RabbitMQ, MQTT, Debezium CDC, Delta, Iceberg, Snowflake/BigQuery readers, a REST poller and a webhook receiver/gRPC (`docs/EDITIONS.md` SP-ACQ-*; plan §5).
  - Tests: 26 + 36 + 6 = **68 unit test classes**, 0 real-HTTP in these hubs (the HTTP routes are tested under module.processor). `KafkaRealBrokerTest` is opt-in and skips unless a broker is supplied; CI has none (`module/connectors-kafka/plan-connectors-kafka.md`).
  - The acquisition ledger defaults to **memory** (`AcquisitionLedgers.java`, `acquire.ledger.backend` defaults to `memory`). No launcher, offering or script sets `db`. Plan §5 flags that the completeness KPI cannot be computed on a fresh install.
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

#### cap.pipelines — Pipelines & processing (module.elt, module.engine, module.config)
- **What it is (customer view):** Visual and TOON-authored Pipelines that parse many formats, then cast, validate (Expectations), transform, enrich, route (Decision Rules) and write partitioned Parquet. They are validated before save, can be dry-run or test-run, and are scheduled as Jobs.
- **Scores:** F 4 · B 4 · Q 4 · E 3 · R 4 → **Level L3 (Pilot-ready)**, capped by E and by the platform baseline
- **Evidence:**
  - 46 built-in node types. 10 parser frontend tokens across 8 formats: delimited, fixed-width, JSON, text-regex, xlsx, Parquet, ASN.1, plugin (`module/elt/plan-elt.md` §2; `module/elt/plan-elt.md` §2.2).
  - Step Processor board: **119 processors, 51 delivered, 17 partial, 51 planned** on the Advanced column (`docs/EDITIONS.md` §Step Processors, generated from `ProcessorCatalog`). The planned share is heaviest in parsers (9), transforms (10), enrichment (7) and sinks (6).
  - Requirements of record ING-1..6 and PIP-1..7 are recorded as shipped (plans §2). The Expectations UI exists now (`inspecto-ui/src/app/inspecto/api/expectations.service.ts`, routed in `app.routes.ts`). That makes the plan's "ING-6 has no UI" claim stale.
  - Tests: engine **369**, etl **104**, config **22** unit test classes. 18 test classes reference golden fixtures (for example the `asn-golden` `SyntheticCorpusTest`). The nightly CI benchmark floors run `PluginIngestBenchmark` at 1M rows (floor 50,000 rows/s) and `MixedLoadBenchmark` (floor 30,000 rows/s, p95 ceiling 5 s). Both were green on the GitHub runner on 2026-10-08, 10-09 and 10-10 (`.github/workflows/nightly-perf.yml`; BACKLOG `ASSURE-OPERABILITY-1`).
  - Measured throughput: native DuckDB ingest of 2M rows × 12 columns in about 3.5 s, and 1.0–1.4M rows/s for transform and write (`layer/platform/tests-performance.md`).
  - Config safety: `ConfigSafetyValidator`, path jail, fail-closed schema types (`module/config/spec-config-safety.md`). Config writes can be held for maker-checker (item 6).
  - Open rows: P2 `AUTHORING-REDESIGN-1` (remaining letters parked on demand), P2 "Postgres multi-user", P2 `SCALEOUT-OBJSTORE-DATABASE-DIR-1` (operator hold), P3 defect `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` (a duplicate output after failover re-plan; operator hold).
  - Replay is bounded. `JobService` resolves runs from an in-memory map capped at `LIVE_RUN_CAP = 1000` (`JobService.java`), so replay after a restart or eviction returns 404 (`module/elt/plan-elt.md` §EXE.2.3).
- **Strengths vs market:**
  - A full ingest → Pipeline → data-quality → lineage platform under the assurance controls. The competitive matrix (§3) names this as something none of the six vendors shows publicly.
  - The authoring loop (validate → dry-run → test-run to a node → arm) is fail-closed, and save-time safety checks are pinned by tests.
- **Gaps vs market / RFP risk:**
  - Outbound writes are thin: no JDBC/SQL sink, no Iceberg/Delta write, and the multi-destination `sinks:` executor is not wired (`module/connectors/plan-warehouse-targets.md` §2, §7). **No BACKLOG row** (EXPORT-1 closed 2026-09-24, covering only object-store export).
  - 51 of 119 catalogued processors are planned, not built (EDITIONS board). Planned work has rows only by family. The processor-catalog item in the module.elt hub has no row id.
  - Record-level replay from quarantine is deliberately not built without a driver (ELT plan §EXE.5.2). Run replay is in-memory: **no row**.
  - Single-node execution. Distributed scheduler coordination has **no BACKLOG row** (ELT plan §EXE.5.1). Only the Postgres run lease exists, for active/passive.
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
  - Analytics Kernel: Musts M1–M10, Shoulds S1–S8 and Coulds C1–C6, C8 and C9 are built, among them forecasting with backtest, k-means, logistic regression, isolation forest, change-point and cohort/funnel (`module/analytics/plan-analytics.md`). 41 unit test classes. A CI duplication ratchet guards it (`tools/check-data-ops-duplication.mjs`).
  - BI publication: the `publish.postgres` Job (`PostgresPublishJobType.java`) does full or partition-incremental refresh into Postgres for an external BI tool (EDITIONS JOB-06).
  - Masking on BI results: `BiResultMasking` and `ControlApiBiQueryMaskingTest` cover `/bi/query`, `/queries/{id}/run`, `/datasets/{id}/rows`, share queries, `/db/*` and Report exports (BACKLOG `BI-QUERY-MASKING-1`, built 2026-10-13, closed 2026-10-23 after its security review; open free-SQL channels: P1 `BI-MASKING-ROW-VALUE-LEAKS-1`, P1 `BI-MASKING-FREE-SQL-ORACLES-1`).
  - UI tests: 41 Studio spec files and 32 viz spec files. The only e2e file is `inspecto-ui/src/e2e/backend-smoke.spec.ts`, and it self-skips without `E2E_BASE_URL`. Nightly `MixedLoadBenchmark` drives 4 `/bi/query` readers during ingest (local p95 528–639 ms).
  - Open rows: P2 `ANALYTICS-CORE-1`, P2 `AUTHORING-REDESIGN-1`, P3 `ANALYTICS-STEPS-TS-1`, P3 `PREDICTIVE-*` (design only), P3 `PACK-SALES-MARKETING-1`.
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
  - The OIDC resource server has been **proven live against WSO2 IS 7.3.0 and Keycloak** (`module/oidc/plan-oidc.md`; `OidcAgainstRealProviderTest`, `OidcKeycloakAgainstRealProviderTest`). 8 test classes, 1 real-HTTP. Entra and Okta go through the `generic` preset and are unverified.
  - ABAC (`inspecto-policy`) is Advanced only (SEC-04). The policy authoring UX with guards and `POST /access/policies/preview` shipped 2026-09-25 (SEC-05). 6 test classes, 5 real-HTTP.
  - Route gating: a mutating route with neither a capability nor a recorded exemption fails the boot (`UndeclaredMutatingRouteTest`, `RouteInventoryTest`; `compliance/evidence/route-gating.md`). In `processor/api-routes.md`, 140 of 178 mutating routes declare a capability. The other 38 are previews, auth or self-service routes.
  - Masking: `AuditReadMasking`, `BiResultMasking`, `ObjectReadMasking`, `NotificationContentMasking`, `BiReportRowMasking` in `inspecto/src/main/java/com/gamma/control/`, each with a real-HTTP test. The `quality.pii.mask` Step is gated to Advanced (SEC-08 🟡).
  - HTTPS is proven at TLS 1.3, including fail-closed on a broken keystore (`ControlApiHttpsTest`, 5 tests, mutation-proven). BFF sessions use an httpOnly, SameSite=Strict cookie (SEC-5).
  - Open rows: P3 `IMPORT-WHOLE-SPACE-SPLIT-1` (demand); `BI-MASKING-FREE-SQL-ORACLES-1` closed 2026-10-25. Untracked in the plan: `SEC-SIDECAR-BOOT-1` (a Standard bundle cannot boot without an IdP), and `permissions[]` emitted but never read.
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
  - Jobs: 12 Job Types, 23 maintenance tasks. Triggers are cron, `on_pipeline`, `on_signal` with guards, catch-up and manual (EDITIONS JOB-01..04; ELT plan §EXE.2.2). Concurrency defaults to 4 and the intake cap to 500 files per cycle.
  - Tests: processor **350 classes (221 real-HTTP)**, observability 8 (5 real-HTTP), backup 10 (1 real-HTTP), event 8. That adds up to 405 test classes across the repo that drive a real `ControlApi`.
  - Backup: per-file and archive SHA-256 sidecar, fail-closed restore into a new Space, a `maintenance_backups` catalog, and secrets excluded (`module/backup/plan-backup.md`; `BackupTaskSecretExclusionTest`). Standard and up only (OPS-06).
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
| Job scheduling with dependencies and retries | Comply | JOB-01..04. Retry is per Job, and a per-Pipeline retry block is deferred (ELT plan §EXE.5.2). |
| Backup/restore with verification | Comply (capability) / Partial (proof) | OPS-06, hash-verified, but no drill recorded |
| Alerting/notification to email/webhook | Comply (Standard and up) | CP-12/CP-15 (CP-15 🟡) |
| Documented REST API | Comply | OpenAPI 3.1, 468 paths. 430 of 559 operations still carry generated-skeleton summaries. |

- **To reach the next level:** (1) Run and record a restore drill and the service-wrapper acceptance on Linux and Windows. (2) Add OpenTelemetry spans and a reference Grafana dashboard. (3) Replace the skeleton OpenAPI summaries for the 50 most-used routes.

#### cap.workflow — Workflow & maker-checker (module.workflow)
- **What it is (customer view):** Four-eyes approval of configuration changes (Pending Changes), plus authored lifecycles, SLA Policies on a business calendar and Escalation Rules for Incidents and Cases. A graphical state editor is included.
- **Scores:** F 3 · B 5 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**. B and E would allow L4. Held at L3 by the platform cap and by thin live evidence.
- **Evidence:**
  - `ASSURE-MAKER-CHECKER-1` shipped 2026-09-27, and its residual rows closed by 2026-10-06. `ASSURE-WORKFLOW-SLA-1` closed 2026-10-04. The graphical state editor shipped 2026-10-11. **No open rows** (`module/workflow/plan-workflow.md`; hub index).
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
| SSO / OIDC | ✅ live-proven | WSO2 7.3.0 and Keycloak (`module/oidc/plan-oidc.md`). Standard and Advanced only; Basic is auth-free by design and binds every interface unless `-Dcontrol.bind` is set (`EDITIONS.md` §Matrix). |
| RBAC / ABAC | ✅ RBAC Standard and up, ABAC Advanced | SEC-03/04/05/06. Route gating is enforced at boot (`compliance/evidence/route-gating.md`). |
| Masking | 🟡 Advanced | SEC-08. Read masking across BI, audit, objects and notifications. `BI-QUERY-MASKING-1` closed 2026-10-23; the free-SQL key channels `BI-MASKING-ROW-VALUE-LEAKS-1` (2026-10-23) and `BI-MASKING-FREE-SQL-ORACLES-1` (2026-10-25: a key named only bare, vetted per caller) are closed. |
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
