---
type: Feature
title: "Standard Edition — features"
description: "Standard includes Basic and adds sign-in, operations, case, assurance and detection modules plus six content packs."
tags: [standard, edition, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Standard Edition — features

**Product Map Item:** [`edition.standard`](../../../../../product-map/edition/standard.toon) · **Status:** ga · **Availability:** Billable Edition `INS-ED-STANDARD`, per-node billing, standard support, enforced at build (`offerings/standard.toon`).

## What it is

Standard includes Basic and adds sign-in, operations, case, assurance and detection modules plus six content packs. For the sales story see [brochure](brochure.md).

## Includes

- Everything in the [Basic Edition](../basic/features.md) (`includes: basic`).

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
- [telecom-asn1](../../module/telecom-asn1/index.md)
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
- [agent](../../module/agent/index.md)

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
| [workflowSla](../../addon/workflowSla/index.md) | `INS-AO-WORKFLOW-SLA` | ops |
| [premiumConnectors](../../addon/premiumConnectors/index.md) | `INS-AO-PREMIUM-CONNECTORS` | connectors-kafka |
| [telecomIndustryPack](../../addon/telecomIndustryPack/index.md) | `INS-AO-TELECOM-INDUSTRY-PACK` | telecom-asn1 |

## Content packs added over Basic

- [business-assurance](../../solution-pack/business-assurance/index.md)
- [mobile-money](../../solution-pack/mobile-money/index.md)
- [aml](../../solution-pack/aml/index.md)
- [payment-fraud](../../solution-pack/payment-fraud/index.md)
- [telco-fraud](../../solution-pack/telco-fraud/index.md)
- [telco-ra](../../solution-pack/telco-ra/index.md)

## Limits

- No Edition-specific limits are declared in the offering file (`defaultSpaceSettings` is empty or absent).

## Related hubs

[Basic](../basic/index.md) · [Standard](../standard/index.md) · [Advanced](../advanced/index.md) · [Preview](../preview/index.md)
