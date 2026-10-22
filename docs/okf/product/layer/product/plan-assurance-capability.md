---
type: Plan
title: Assurance capability programme — generic product before the project (as built)
description: The rule that decided which RA / FM / BA work is product (no customer input) and which waits for a customer, where each of the five shipped waves is documented, and the customer-dependent work that stays parked.
tags: [plan, revenue-assurance, fraud-management, business-assurance, payment-fraud]
timestamp: 2026-10-22T00:00:00Z
---

# Assurance capability programme — generic product before the project

**Status: SHIPPED 2026-09-26 → 2026-10-14; distilled here 2026-10-22** from the archived
[`assurance-capability-plan.md`](../../../../archived-documents/plans-archive/assurance-capability-plan.md) (its wave
tables, estimates and decision texts stay there). Open residuals are rows in [`BACKLOG.md`](../../../../BACKLOG.md)
(the `ASSURE-*-RESIDUALS-1` rows and `ASSURE-OPERABILITY-1`).

## The test for "generic" (operator, 2026-09-26)

An assurance workstream is **product** — built before any project — when all three hold: (1) **no customer input**:
it can be specified, built and accepted on synthetic data; (2) **not a one-customer shape**: thresholds, factor
weights and list contents are configuration, never code; (3) **only operator decisions block it**, never a
customer's call (their factor table, gateway API or hosting). A workstream that is mostly generic is split; the
customer half is parked. No customer material appears in the repo: every fixture is synthetic and every content
pack ships as a Space Template with a synthetic golden corpus (planted cases plus look-alikes that must stay silent).

## Where each wave's as-built facts live

| Wave | Items (bid ids `WS-xx`) | As built in |
|---|---|---|
| 1 Detection and case foundations | per-key Alerts (WS-18), Incident `assigned` / ageing / recurrence (WS-11), impact ledger and Disposition (WS-10) | [`addon/incidents/plan-incidents.md`](../../addon/incidents/plan-incidents.md), [`module/ops/user-guide-objects.md`](../../module/ops/user-guide-objects.md) |
| 2 Governance and action | maker-checker (WS-13), Entity Lists (WS-12), Action Requests (WS-24) | [`module/access/admin-guide-auth-security.md`](../../module/access/admin-guide-auth-security.md), [`module/entity-list/spec.md`](../../module/entity-list/spec.md), `addon/actionRequests/` |
| 3 Measurement and scoring | KPI definitions (WS-20), Explainable Risk Score (WS-22), tamper-evident audit (WS-25) | [`module/scoring/spec-risk-scores.md`](../../module/scoring/spec-risk-scores.md), [`module/event/spec-events-metrics.md`](../../module/event/spec-events-metrics.md) |
| 4 Integration and operability | push ingest (WS-21), BI publication Job (WS-17), xlsx export and attachments (WS-27), workflow / SLA / Escalation Rules (WS-23), operability generic half (WS-01/02/04/26), intelligence bundle (WS-32) | [`module/acquire/plan-acquisition.md`](../../module/acquire/plan-acquisition.md), [`plan-studio.md`](plan-studio.md), `addon/incidents/plan-incidents.md`, [`layer/platform/plan-editions.md`](../platform/plan-editions.md) |
| 5 Content (Space Templates) | telco fraud (WS-15), telco RA (WS-16), payment fraud (WS-40…44), business assurance (WS-28) | [`module/config/plan-spaces.md`](../../module/config/plan-spaces.md) and the `solution-pack/*` hubs |

Decisions D-P1…D-P14 were answered on 2026-09-26 ("go with recommendations") and D-P8 on 2026-10-06; each is
recorded in the hub of the item it shaped (for example D-P10, one Entity List kind in the Identity Fact store, in
`module/entity-list/`; D-P4, escalation only and queues stay deleted, in `addon/incidents/`). Two have no other home:
**D-P2** — the intelligence module is bundled in the top Edition first (as built: Advanced, `ASSURE-INTELLIGENCE-BUNDLE-1`);
**D-P9** — each wave's items go on the board when the wave starts.

## Parked — needs a customer (do not start without one)

| Item | Why it waits |
|---|---|
| WS-03 feed validation | needs the customer's vendor CDR, HLR and PCRF samples |
| Vendor-specific halves of telco fraud and telco RA | thresholds and feed mappings tuned to real feeds (the one item left when `ASSURE-PACK-TELCO-RA-1` closed 2026-10-04) |
| WS-30 signalling-control partner integration | needs a partner choice |
| WS-45 payment gateway integration | needs the gateway's API |
| WS-46 telco risk-signal lookup | a commercial / privacy call; generic in shape, so it can become product by an operator decision |
| Customer halves of WS-01 / 02 / 04 / 26 | customer hardware, volumes, sizing, penetration test, national control mapping, in-country hosting |
| The customer's factor table, Risk Tiers, payment scope, card-data posture, IP / BIN enrichment choice | the customer signs these; the payment-fraud pack ships defaults |

Outside the programme for other reasons: WS-14 velocity / baseline Steps (Step Processor hold, 2026-09-23 — SQL
windows cover detection meanwhile), WS-29 investigation evidential controls (Link Analysis), ML scoring and
Arabic / RTL (roadmap).
