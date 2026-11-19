---
type: Feature
title: "Standard Edition — features"
description: "Standard includes Basic and adds sign-in, operations, case, assurance and detection modules plus six content packs."
tags: [standard, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
item: edition.standard
audience: internal
status: current
verified: 2026-11-18 last-edit
code: offerings/standard.toon
---

# Standard Edition — features

**Product Map Item:** [`edition.standard`](../../../../../product-map/edition/standard.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-STANDARD`, per-node billing, standard support, enforced at build (`offerings/standard.toon`).

## What it is

Standard includes Basic and adds sign-in, operations, case, assurance and detection modules plus six content packs. For the sales story see [brochure](brochure-standard.md).

## Includes

- Everything in the [Basic Edition](../basic/features-basic.md) (`includes: basic`).

## Platform posture

- Sign-in mode: `oidc`
- Events backend: `parquet`
- Objects backend: `default`
- PostgreSQL sidecar: `bundled`

## Modules added over Basic

- [oidc](../../module/oidc/index.md)
- [secrets](../../module/secrets/index.md)
- [geo-country](../../module/geo-country/index.md)
- [connectors-kafka](../../module/connectors-kafka/index.md)
- [notify-channels](../../module/notify-channels/index.md)
- [backup](../../module/backup/index.md)
- [exchange](../../module/exchange/index.md)
- [observability](../../module/observability/index.md)
- [ops](../../module/ops/index.md)
- [case-management](../../module/case-management/index.md)
- [action-requests](../../module/action-requests/index.md)
- [regulatory-reporting](../../module/regulatory-reporting/index.md)
- [reconciliation](../../module/reconciliation/index.md)
- [scoring](../../module/scoring/index.md)
- [screening](../../module/screening/index.md)
- [anomaly](../../module/anomaly/index.md)
- [predictive](../../module/predictive/index.md)
- [realtime](../../module/realtime/index.md)
- [agent](../../module/agent/index.md)

The ASN.1 and telecom decoders (`telecom-asn1`) are not here: Advanced only since 2026-11-17 (DATA-TIERS-1 DT-12).

## Add-ons available

| Add-on | Order code | Stages this Edition contributes |
|---|---|---|
| [aiAssist](../../addon/aiAssist/index.md) | `INS-AO-AI-ASSIST` | agent |
| [integrationDelivery](../../addon/integrationDelivery/index.md) | `INS-AO-INTEGRATION-DELIVERY` | notify-channels |
| [multiEntity](../../addon/multiEntity/index.md) | `INS-AO-MULTI-ENTITY` | exchange |
| [incidents](../../addon/incidents/index.md) | `INS-AO-INCIDENTS` | ops |
| [caseManagement](../../addon/caseManagement/index.md) | `INS-AO-CASE-MANAGEMENT` | case-management |
| [reconciliation](../../addon/reconciliation/index.md) | `INS-AO-RECONCILIATION` | reconciliation |
| [scoringLists](../../addon/scoringLists/index.md) | `INS-AO-SCORING-LISTS` | entity-list, scoring |
| [actionRequests](../../addon/actionRequests/index.md) | `INS-AO-ACTION-REQUESTS` | action-requests |
| [regulatoryReporting](../../addon/regulatoryReporting/index.md) | `INS-AO-REGULATORY-REPORTING` | regulatory-reporting |
| [screening](../../addon/screening/index.md) | `INS-AO-SCREENING` | screening |
| [anomalyDetection](../../addon/anomalyDetection/index.md) | `INS-AO-ANOMALY-DETECTION` | anomaly |
| [predictiveAnalytics](../../addon/predictiveAnalytics/index.md) | `INS-AO-PREDICTIVE-ANALYTICS` | predictive |
| [realtimeDetection](../../addon/realtimeDetection/index.md) | `INS-AO-REALTIME-DETECTION` | realtime |
| [workflowSla](../../addon/workflowSla/index.md) | `INS-AO-WORKFLOW-SLA` | ops |
| [premiumConnectors](../../addon/premiumConnectors/index.md) | `INS-AO-PREMIUM-CONNECTORS` | connectors-kafka |
| [laInvestigations](../../addon/laInvestigations/index.md) | `INS-AO-LA-INVESTIGATIONS` | ops |

## Content packs added over Basic

- [business-assurance](../../solution-pack/business-assurance/index.md)
- [la-daily-feed](../../solution-pack/la-daily-feed/index.md) (from Basic on 2026-11-17, DATA-TIERS-1 DT-18)
- [mobile-money](../../solution-pack/mobile-money/index.md)
- [aml](../../solution-pack/aml/index.md)
- [payment-fraud](../../solution-pack/payment-fraud/index.md)
- [telco-fraud](../../solution-pack/telco-fraud/index.md)
- [telco-ra](../../solution-pack/telco-ra/index.md)
- [telco-bypass](../../solution-pack/telco-bypass/index.md)
- [partner-settlement](../../solution-pack/partner-settlement/index.md)
- [telco-rerating](../../solution-pack/telco-rerating/index.md)

## Data layer over Basic (DATA-TIERS-1, as built 2026-11-17)

- `pipeline.automation` is declared here (by `inspecto-ops`): an import Dataset's *Keep this up to date* (a watched folder or SFTP feed on a chosen schedule) and its retention policy (*every arrival*, *the last N days*, *the last N arrivals*).
- Add data does not lock *another database or a remote system* (the screen says a Pipeline reads them), and the Dataset page offers adding a Reference's details to each row (enrichment).
- `inspecto-ops` also declares the other three screen gates, so nothing Basic locks is locked here: `pipeline.transform` (the step palette's Steps that compute, join, enrich or summarise), `pipeline.egress` (new *Object Storage Export* and *Publish to Postgres* Jobs) and `collector.remote` (new remote Connections, *Connection* in the Collect stage). `/bootstrap` reports `pipelineAutomation`, `pipelineTransform`, `pipelineEgress` and `remoteSystems` true.
- Everything in Basic's data layer is here too. Full table: [EDITIONS.md](../../../../EDITIONS.md) *Data layer by edition*.

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](index.md) · [Advanced](../advanced/index.md) · [Preview](../preview/index.md)
