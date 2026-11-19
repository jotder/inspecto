---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — add-ons for risk, assurance, data and Link Analysis
description: Part 4 of 8 of Product maturity assessment — every client-facing item: 
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
---

# Product maturity assessment — every client-facing item — Per-item assessments — add-ons for risk, assurance, data and Link Analysis

Part 4 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — add-ons for operations, investigation and AI](plan-maturity-assessment-addons-operations.md) · next: [Per-item assessments — telecom solution packs](plan-maturity-assessment-packs-telecom.md).

## 6. Per-item assessments (continued)

### C. Add-ons — risk, assurance, data and Link Analysis

#### addon.reconciliation — Reconciliation
- **What it is (customer view):** Compares two or three Datasets at a key grain one day at a time and turns the differences into Breaks that analysts can assign, resolve, schedule and escalate to Incidents.
- **Scores:** F 3 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - The add-on holds one module, `reconciliation` (`offerings/standard.toon`); it ships in Standard and above (`docs/EDITIONS.md` l.178).
  - Built per `docs/okf/product/module/reconciliation/plan-reconciliation.md`: the Board, 2-way and 3-way compare, Break lifecycle `open | assigned | resolved | auto_closed`, the `recon.run` Job, `POST /recon/promote` to Incident, cardinality tier 1, 90-day state retention and the uplift slices. The only open row is P3 `PACK-PARTNER-SETTLEMENT-1`, a content pack. No defects.
  - Code confirms per-Measure tolerances (`exact`, `absolute`, percent) in `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconBreaks.java`, and cardinality assertion in `ReconService.Cardinality`.
  - 10 routes (`api-routes.md`). Writes are gated in `ReconRoutes.java`: `canOperateRuns` for record and breaks/status, `canManageIncidents` for promote. Read-shaped POSTs are recorded in `CapabilityManifest.EXEMPTIONS`.
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
  - The add-on holds one module, `regulatory-reporting` (`offerings/standard.toon`). The MVP shipped 2026-10-09 and `REGULATORY-REPORTING-1` closed 2026-10-18 (`module/regulatory-reporting/plan-regulatory-reporting.md`).
  - 11 routes (`api-routes.md`). Draft and request-approval need `canWorkIncidents`. Approve, decline, retry and template PUT/DELETE need `canApproveChanges`.
  - XSD validation at draft time is real code (`features/inspecto-regulatory-reporting/src/main/java/com/gamma/regreporting/SchemaCheck.java`). There is a `.sha256` sidecar plus `GET /regulatory-reports/{id}/verify`, and `FileDrop.java` does the atomic file drop.
  - 6 test classes: 4 unit and 2 real-HTTP (`ControlApiRegulatoryReportsTest`, `ControlApiRegulatoryTemplatesTest`) (`tests-inventory.md`). That is thin for a filing engine.
  - The demo rehearsal on 2026-10-14 was GO and re-driven on an Enterprise (today Advanced) bundle (`spec.md` § *Demo rehearsal 2026-10-14*). It showed XML, CSV and JSON drafted, maker self-approval refused with 403, the viewer refused with 403, `sourceChanged` detection, and `GET /audit/verify ok`.
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
  - Entity Lists `plan.md` lists ranges, CIDR, expiry, the Parquet sidecar, `/match`, four-eyes on held changes, the hash-chained fact log and `ENTITY_LIST_CHANGED` audit (counts, never keys). Left: a head-hash cache. (`LA17-NORMALISER-CHANGE-1`, a normaliser change stranding members, closed 2026-09-27: a list seals its normaliser at `list.created`, D-M9, `EntityListRoutes`.)
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
  - ~~`LA17-NORMALISER-CHANGE-1` strands list members when an Entity Type's normaliser changes.~~ Closed 2026-09-27 (D-M9): a list seals its normaliser at `list.created` and member writes use the sealed one (`EntityListRoutes`); re-checked 2026-11-15.
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
  - Matching is real code: token Jaro-Winkler from the analytics similarity kit (`features/inspecto-screening/src/main/java/com/gamma/screening/NameMatcher.java`), phonetic skeletons, Cyrillic transliteration by sound, and blocking keys (`spec.md` l.34-51).
  - 5 routes. `decide` needs `canWorkIncidents` and `reveal` needs `canRevealLinkEntities`. `POST /screening/check` and the hits GET routes are ungated ("—").
  - 12 test classes: 11 unit and 1 real-HTTP (`ControlApiScreeningTest`), plus `NameMatcherGoldenTest` and `ScreeningRevealTest`.
  - The 2026-10-14 demo rehearsal was re-driven on an Enterprise (today Advanced) Demo Auth bundle (`spec.md` § *Demo script and rehearsal*). It covered exact, fuzzy, transliterated and identifier matches, 1,000 subjects in 94 ms, self-confirm refused with 409 four-eyes, and an audited reveal. Verdict: GO.
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
  - Known limits (`architecture.md` §9): no retry inside a channel except the soft-bounce retry, and suppression applies only to persisted destinations.
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
  - Security: SASL is supported (`KafkaConnector.java`). The default `security.protocol` is `SASL_PLAINTEXT` when credentials are set; `SASL_SSL` is reachable only through a pass-through option. There is no truststore handling in the module (grep), so TLS to the broker is unverified.
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
  - Routes: `la-api` 90 and `geo-link` 7, so 97 registered, all in the OpenAPI contract (generated `api-routes.md`). Docs drift: the whitepaper says 93 (86+7), `features.md` says 87 and the add-on features say 94. 101 feature-db rows.
  - Tests: 186 classes across la-core (23), la-api (40), la-storage (12), la-store-pg (3) and geo-link (108). 90 are real-HTTP control-plane tests, held in `la/inspecto-geo-link/src/test`. Golden showcase tests (`ControlApiLaShowcaseFiguresTest`, `LaShowcaseGoldenTest`) and the acceptance test `LaDailyIngestAcceptanceTest` are among them.
  - Live and measured evidence (`brochure-inspecto-la-whitepaper.md` §6–§7):
    - `IndexScaleBench`: about 21 ms one-key p50 from 10^6 to 10^8 edges.
    - Daily append at 10^8 takes 30.6 s against 929 s for a full rebuild.
    - A production-scale day (132M records, 39.4M links) was indexed in 5.3 min. This is a single run on private data.
    - Live axe passes, real WSO2 OIDC sign-in, a Data Trust live drive, a think-aloud script and a stakeholder Q&A are recorded in `module/la-core/tests-*.md` and `admin-guide.md`.
  - Governance proven by named tests: seven-gate order, strict 404 for non-members, keyed masking with anti-oracle, four-eyes on sensitive expansion, per-entity audited reveal, Dossier tamper detection (`ControlApiDossierTest`) and replay determinism.
  - Open work: P2 `LA-DAILY-INGEST-1` follow-ups (Hive/Kerberos deferred by D-ING5, SCD2 deferred by D-ING4), six P3 rows, and the P3 defect row `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1`. Items (1)–(6) of that defect row are done; only the alias-book scan check remains.
  - Stated NOT YET items (whitepaper §7): retention purge and legal hold (declined, D-U8), the multi-host HA two-host drill (`ASSURE-OPERABILITY-1`), 5×10^8 and 10^9 edge builds (`LA-INDEX-SCALE-MEASURE-1`), machine-to-machine trust, and full WCAG 2.2 AA (`LA-A11Y-AUDIT-1`). The Postgres store tests do not run on the shared profile (`TEST-ENV-PG-ASN-UNRUN-1`).
- **Strengths vs market:**
  - The evidentiary chain of custody is stronger than i2-class desktop tools (general market): a sealed op log, deterministic replay, a SHA-256 Dossier manifest, Drafts with rebase and promote, and four-eyes on sensitive expansion. All of it is pinned by real-HTTP tests.
  - It runs in-process over Parquet, with no separate graph database or cluster, and the standard build provably cannot call hosted AI (`EgressGuardTest`). This is a strong sovereignty and air-gap story against Palantir/Quantexa-class SaaS or cluster deployments (general market).
  - The disclosure discipline is unusually honest: a claims register marking each claim PROVEN, MEASURED or NOT YET makes RFP answers defensible line by line.
- **Gaps vs market / RFP risk:**
  - Entity resolution is analyst-asserted or imported identity pairs plus merged traversal (`user-guide.md` §11.7 and [architecture-records](../../module/la-core/architecture-records.md)). There is no probabilistic or ML entity resolution at scale, which is Quantexa's core (general market). **No row.**
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
  - The add-on holds one module, `telecom-asn1` (`offerings/advanced.toon` since 2026-11-17, DATA-TIERS-1 DT-12; `docs/EDITIONS.md` SP-PRS-06 Advanced only). The telco RA and fraud controls (`telco-ra`, `telco-fraud`) are separate Standard `contentPacks` and the contract-enforced `solution-pack.industry-telecom` (`product-map/solution-pack/industry-telecom.toon`), not part of this add-on.
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
  - There is no decoder throughput figure: the only mention is a to-do in `providers/asn-parser/docs/REDESIGN.md`. Mediation-grade volume claims are unverified. **No row.**
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
  - ~~Whether `LA17-NORMALISER-CHANGE-1` has a board row.~~ It has none: it closed 2026-09-27 (D-M9; re-checked 2026-11-15).
  - All performance figures, which are single laptop runs as the docs themselves state.
- No reference-customer evidence is recorded anywhere, so nothing qualifies for L5.
