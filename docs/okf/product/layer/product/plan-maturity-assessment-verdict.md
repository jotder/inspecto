---
type: Plan
title: Product maturity assessment — every client-facing item — Verdict, scoring and scorecard
description: Part 1 of 8 of Product maturity assessment — every client-facing item: Verdict; How it was scored; Scorecard; Cross-cutting findings; What raises the levels — ranked by leverage
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
item: layer.product
audience: internal
status: plan
verified: 2026-11-19 last-edit
---

# Product maturity assessment — every client-facing item — Verdict, scoring and scorecard

Part 1 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · next: [Per-item assessments — platform base](plan-maturity-assessment-platform-base.md).

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
- Per-node billing with no licence engine or meter (D-MR3; since 2026-11-11 an offline signed entitlement file
  narrows which modules run — D-PA22, `Entitlements` — but its `nodes` count is recorded, not enforced); Preview carries a GA order code; Advanced's brochure
  promises HA, legal hold and residency that are unbuilt. Nothing has yet been released under the new edition names.
- Retention / legal hold covers Incidents only — Cases, anomaly scores and action-request history have none.

### 4.4 Docs that had drifted from the code (verified 2026-10-20 in code)
- `layer/product/plan-studio.md` says 13 Visualization Types — `BUILTIN_VIZ_PLUGINS` registers **20**.
- `module/elt/plan-elt.md` says Expectations have no UI — the `expectations` route exists in `app.routes.ts`.
- `docs/EDITIONS.md` SEC-09 says the audit log is not tamper-evident — the hash chain (`AuditAnchors`, `/audit/verify`) shipped.
- Assessors also report stale claims in `addon/incidents/plan-incidents.md` and `module/agent/plan-assistant.md` (§6 B).
  A docs-only review of these is tracked by `DOCS-AGENT-WRITTEN-REVIEW-1`.

### 4.5 Gaps found that have no BACKLOG row (each is itself a finding)
- Masking: AML `account_id` and mobile-money `wallet_id` / `msisdn` were classified `CONFIDENTIAL`, outside
  `ColumnClassification.SENSITIVE`, so they were not masked; reclassified 2026-11-14 (`TEMPLATE-CONFIDENTIAL-UNMASKED-1`, pinned by `TemplateIdentifierClassificationTest`).
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
