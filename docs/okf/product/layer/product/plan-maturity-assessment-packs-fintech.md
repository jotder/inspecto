---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — fintech and commerce solution packs
description: Part 6 of 8 of Product maturity assessment — every client-facing item: 
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
---

# Product maturity assessment — every client-facing item — Per-item assessments — fintech and commerce solution packs

Part 6 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — telecom solution packs](plan-maturity-assessment-packs-telecom.md) · next: [Per-item assessments — Link Analysis packs, Inspecto LA and the editions](plan-maturity-assessment-la-editions.md).

## 6. Per-item assessments (continued)

### E. Solution packs — fintech and commerce

#### solution-pack.aml — AML
- **What it is (customer view):** a ready-to-run AML Space for a bank or payment institution: four laundering typologies, an account Risk Score, a behavioural Anomaly Model, sanctions/PEP screening, money-trail Link Analysis and an STR draft, over a synthetic ledger.
- **Scores:** F 2 · B 4 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - Typology count: **4 rule scenarios** as `sql.template` Jobs + per-account CRITICAL Alert Rules — structuring, threshold crossing, smurfing fan-in, high-risk-jurisdiction wires (`spaces/_templates/aml/config/jobs/aml_{structuring,threshold,fan_in,high_risk_traffic}_job.toon`, `config/registry/alert-rules/*.toon`, 4 files); **2 model-based detectors** — Risk Score `aml_account` (3 weighted, capped factors, high ≥ 60; `registry/risk-scores/aml_account.toon`) and Anomaly Model `aml_account_transfers` (`method: isolation_forest`, 28-day weekday-seasonal baseline; `registry/anomaly-models/aml_account_transfers.toon`), each with a pending Alert Rule (`config/pending/alert-rules/`, 2 files); **4 investigative pattern packs** (circular flow, inbound collector, layering chain with 48 h hop ordering, pass-through) + 1 Link Analysis view.
  - Screening: `aml_screening` Job (`screening.run`, threshold 0.85, lists `aml_sanctions,aml_pep`) ships `enabled: false`; Entity Lists are not shipped (`config/jobs/aml_screening_job.toon`; architecture.md "Limits"). No vendor list-feed loader (OFAC / UN / World-Check) found — grep of `module/screening` docs and `features/inspecto-screening/src/main` returns nothing.
  - SAR/STR: `regulatory-report-templates/aml-str.toon` — 17-field XML, file-drop delivery, explicitly "follows no real regulator's schema"; engine is four-eyes (`docs/okf/product/module/regulatory-reporting/spec-regulatory-reporting.md`); regulator formats are open P3 `REGULATORY-SAR-STR-TEMPLATES-1` (hold: demand).
  - Tests: `AmlPackGoldenTest` 4 (committed corpus = seeded generator output; golden corpus raises exactly the planted accounts per typology), `AmlScreeningGoldenTest` 2 (hits exactly the planted parties, no look-alike; STR template loads), `PackFraudAmlAnomalyPropertyTest` 3 of 5 on AML (forest ranks and explains the planted account), plus `ControlApiSpaceTemplateSeedGateTest` 12 (real-HTTP apply of every shipped template, capability refusal, pending-rule gating).
  - Live evidence: screening demo rehearsal 2026-10-14 recorded with 11 beats PASS, re-driven on an Enterprise (today Advanced) Demo Auth bundle (`docs/BACKLOG.md` row `SCREENING-1`; `docs/okf/product/module/screening/spec-screening.md` § *Demo script and rehearsal*; `tools/seed-screening-demo.mjs` builds the Space from the `aml` template).
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
  - Daily batch only; no real-time transaction screening / interdiction — `REALTIME-DECISIONING-1` (P3). Ledger `account_id` was classified `CONFIDENTIAL`, which is not in `ColumnClassification.SENSITIVE` (`MSISDN IMSI ACCOUNT PII`), so account numbers were stored unmasked — reclassified 2026-11-14 (`TEMPLATE-CONFIDENTIAL-UNMASKED-1`, pinned by `TemplateIdentifierClassificationTest`) to `ACCOUNT`.
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
  - Hourly batch: "Features are hourly batch, not authorisation-time scoring" (`docs/okf/product/solution-pack/payment-fraud/architecture-payment-fraud.md` Limits). Closed P2 `ASSURE-PACK-PAYMENT-FRAUD-1` as BUILT "further typologies on customer request" (BACKLOG l.163); the two PAN-leak rows it spawned (`INGEST-REJECT-SIDECAR-RAW-PAN-1`, `INGEST-FAILURE-TEXT-QUOTES-VALUE-1`) are no longer open.
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
  - Masking (fixed: reclassified 2026-11-14 (`TEMPLATE-CONFIDENTIAL-UNMASKED-1`, pinned by `TemplateIdentifierClassificationTest`)): `wallet_id` and `msisdn` were classified `CONFIDENTIAL` in `config/wallets/wallets_schema.toon` and `config/core_subscribers/core_subscribers_schema.toon`, which `ColumnClassification.SENSITIVE` (`platform/inspecto-util/.../ColumnClassification.java`) does not mask — while the industry pack marks `wallet` / `msisdn` Entity Types `masked: true`.
  - Matrix row R: Y vs LATRO Assure Fintech, Subex FraudZap, Mobileum RAID mobile-money risk (matrix l.18-20, 50).
- **Strengths vs market:**
  - RA (float, settlement, fee, commission) and fraud/AML-lite (split, round-trip, dormant, KYC) in one pack — LATRO's Assure Fintech is the closest peer and is largely managed-service; this is self-run configuration.
  - Peer-group anomaly by KYC tier, explainable, property-tested.
- **Gaps vs market / RFP risk:**
  - No screening, no STR template and no Link Analysis view inside the pack (LA is the separate `la-mobile-money` template) — a mobile-money AML RFP needs the `aml` pack alongside — no row.
  - Daily batch; no real-time agent/wallet blocking — `REALTIME-DECISIONING-1` (P3).
  - MSISDN / wallet ids were stored unmasked (`CONFIDENTIAL` not a masked class) — reclassified 2026-11-14 (`TEMPLATE-CONFIDENTIAL-UNMASKED-1`, pinned by `TemplateIdentifierClassificationTest`).
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
  - Fail-closed loading: a pack with a missing / unparseable file or duplicate id is refused whole (`docs/okf/product/solution-pack/industry-mobile-money/architecture-industry-mobile-money.md`, `IndustryPacks.java`).
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
