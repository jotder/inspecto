---
type: Feature
title: "Telecom fraud — features"
description: "Fourteen telecom fraud typologies over synthetic CDR, subscriber-event and payment feeds, with per-offender Alert Rules."
tags: [telco-fraud, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom fraud — features

**Product Map Item:** [`solution-pack.telco-fraud`](../../../../../product-map/solution-pack/telco-fraud.toon) · **Status:** ga · **Availability:** billable Solution Pack `INS-SP-TELCO-FRAUD`, listed under `contentPacks` in `offerings/standard.toon` (Advanced and Preview include Standard); Space Template `telco-fraud`, `minEdition: standard`.

## What it is

A fraud content pack over three feeds — CDR, subscriber events and payments — with one detection Job and one per-offender Alert Rule per typology. Every threshold is configuration.

## Who benefits

Telecom fraud analysts and fraud managers.

## Key capabilities

- Fourteen detection windows as `sql.template` Jobs: IRSF, Wangiri, SIM-box, premium-rate, roaming high usage, SIM-swap, subscription / identity, dealer activations, voucher / EVD, payment reversal, recharge fraud, data-charging bypass, internal fraud and manual adjustments.
- Fourteen per-entity Alert Rules keyed on the offender, each with a runbook entry (`config/registry/runbooks/`).
- Seven data-quality Expectations on the three feeds (non-null keys and timestamps, duration and amount ranges).
- Two Decision Rules: a roaming voice call over two hours opens a CRITICAL Incident; payment reversals are tagged with their typology for routing.
- A Case Rule (`fraud_critical_burst`) that groups CRITICAL Incidents on one offender (by `msisdn`, 1440-minute window).
- A nightly CSV export of the IRSF offenders (`fraud_offender_export`, a `report` Job).
- Subscriber-usage Anomaly Model (outgoing voice, SMS and data against each line's own history) with a pending Alert Rule, created after the model's first run.
- Four KPIs (IRSF exposure seconds, premium-rate exposure, reversed amount, roaming exposure), the `telco_fraud_overview` dashboard and runbooks.

## Related hubs

[Architecture](architecture.md) · [Brochure](brochure.md) · [incidents](../../addon/incidents/index.md) · [caseManagement](../../addon/caseManagement/index.md) · [anomalyDetection](../../addon/anomalyDetection/index.md) · [linkAnalysis](../../addon/linkAnalysis/index.md)
