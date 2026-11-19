---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — telecom solution packs
description: Part 5 of 8 of Product maturity assessment — every client-facing item: 
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
---

# Product maturity assessment — every client-facing item — Per-item assessments — telecom solution packs

Part 5 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — add-ons for risk, assurance, data and Link Analysis](plan-maturity-assessment-addons-risk.md) · next: [Per-item assessments — fintech and commerce solution packs](plan-maturity-assessment-packs-fintech.md).

## 6. Per-item assessments (continued)

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
  - Decided limits: the impact ledger stays manual (`PUT /objects/{id}/impact`, no automatic write); timestamps are naive (one agreed timezone); the `kpi.completeness` tile ships `enabled: false` (`business-assurance/architecture-business-assurance.md` "Limits").
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
  - Live evidence: a demo rehearsal on an Enterprise (today Advanced) Demo Auth bundle on 2026-10-14 (the 2026-10-14 ⬆ count note in `docs/BACKLOG.md`'s header). The live bundle run on the same day exercised the Expectation Job and the offender export (row `TELCO-FRAUD-STORY-GAPS-1`).
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
