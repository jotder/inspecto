---
type: Feature
title: "Telecom revenue assurance — architecture"
description: "How the telco-ra Space Template wires Pipelines, Reconciliations, sql.template controls and Alert Rules into a leakage ledger."
tags: [telco-ra, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom revenue assurance — architecture

Everything below is configuration in the `telco-ra` Space Template (`spaces/_templates/telco-ra/`). Platform pieces: [Reconciliation](../../addon/reconciliation/index.md), [Incidents](../../addon/incidents/index.md), [etl](../../module/etl/index.md). The broader revenue assurance design: [revenue assurance architecture](../business-assurance/architecture-revenue-assurance.md).

## Layers

```
 feeds (data/inbox/<feed>)
   -> 8 Pipelines + canonical schemas -> 8 feed Datasets
   -> 2 Reconciliations (recon.run Jobs)        -> breaks open Incidents
   -> sql.template controls -> *_findings Datasets
   -> ra_leakage / ra_data_quality Datasets      -> 2 Alert Rules
   -> impact ledger -> ra_recovery               -> ra_overview dashboard
```

## Ingest

Eight Pipelines, each with its own schema under `config/<feed>/`: `switch_xdr`, `mediated_xdr`, `rated_usage`, `billed_invoice`, `tariff`, `balance_ledger`, `ic_rates`, `ic_statement`. Each lands in a `<feed>_dataset`; `rated_usage_monthly_dataset` rolls rated usage up for billing comparison. Two Expectations guard the feeds: `ra_switch_xdr_id_non_null` and `ra_rated_charge_non_negative`.

## Reconciliations

| Reconciliation | Compares | Key | Tolerance |
|---|---|---|---|
| `ra_xdr_completeness` | switch, mediated and rated xDRs (three-way) | `XDR_ID` | `USAGE_UNITS` sum, absolute 1 |
| `ra_rated_vs_billed` | monthly rated usage vs billed invoices | `SUBSCRIBER_ID` | `CHARGE` sum, absolute 0.05 |

They run as `recon.run` Jobs (`ra_xdr_completeness_job`, `ra_rated_vs_billed_job`). A break opens an Incident whose impact and Disposition feed the impact ledger. `ra_xdr_lost_job` writes the xDRs lost between stages to `ra_xdr_lost`.

## Controls

`sql.template` Jobs each write a findings Dataset; every row carries `CONTROL`, `EVENT_DATE`, `ITEM_KEY`, `REASON`, `FINDING` and `LEAKAGE_AMOUNT`:

| Job | Control | Findings Dataset |
|---|---|---|
| `ra_rerating_job` | re-rating against the effective-dated tariff | `ra_rerating_findings` |
| `ra_rollforward_job` | balance roll-forward (opening + in - out = closing) | `ra_rollforward_findings` |
| `ra_settlement_job` | interconnect settlement against partner statements | `ra_settlement_findings` |

`ra_leakage_job` unions the rows with `FINDING = 'leakage'` into `ra_leakage`; `ra_data_quality_job` unions the `data_quality` rows into `ra_data_quality`. Leakage and records a control could not judge are kept apart.

## Alerting

- `ra_leakage_found`: CRITICAL, fires once per control (`by CONTROL`) when the leakage Dataset holds at least one row; the rows name the entities.
- `ra_data_quality`: a control could not judge some records (ambiguous or missing tariff, a NULL balance field, a missing partner statement).

## Impact and reporting

- `ra_objects_analytics_job` (`objects.analytics`, hourly) runs object analytics; `ra_recovery_job` reads the impact ledger (suspected, confirmed, recovered, prevented, outstanding) into `ra_recovery`.
- KPIs `leakage_found`, `leakage_items` and `data_quality_items`; the `ra_overview` dashboard shows leakage found, confirmed and recovered, leakage by control, lost xDRs and data-quality items.
- `ra_switch_kpi_completeness_job` (`kpi.completeness`) feeds the completeness tile; it ships disabled.

## Configuration

Tolerances live in the Reconciliation files, thresholds in the Alert Rule and Job files. Runbooks: `config/runbooks/telco-ra-runbooks.md`.
