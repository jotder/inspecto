---
type: Feature
title: "AML — user guide"
description: "Working day to day in a Space created from the aml Space Template."
tags: [aml, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# AML — user guide

How an analyst works in a Space created from the `aml` Space Template. Install steps: [admin guide](admin-guide.md).

## The data

Two Pipelines ingest the feeds: `aml_txn` (account ledger) and `aml_parties` (party register). Files dropped into `data/inbox/<feed>` are picked up by the matching Pipeline and land in the `aml_txn` and `aml_parties` Datasets. The synthetic golden corpus ships in `data/samples` (ten daily `AML_TXN_*.csv` files and one `AML_PARTIES_*.csv`).

## The daily cycle

The four detection Jobs run on a daily schedule over the previous day and write their findings to their own Datasets:

| Job | Finding Dataset | Alert Rule |
|---|---|---|
| `aml_structuring_job` | `aml_structuring` | `aml_structuring` |
| `aml_threshold_job` | `aml_threshold` | `aml_threshold` |
| `aml_fan_in_job` | `aml_fan_in` | `aml_fan_in` |
| `aml_high_risk_traffic_job` | `aml_high_risk_traffic` | `aml_high_risk_traffic` |

Each Alert Rule fires per account, so every flagged account becomes its own Alert, triaged in the Incidents screens ([Incidents user guide](../../addon/incidents/user-guide-incidents.md)).

`aml_risk_score_job` scores accounts with the `aml_account` Risk Score and `aml_anomaly_score_job` scores the `aml_account_transfers` Anomaly Model. Their Alert Rules (`aml_high_risk_account`, `aml_unusual_account_transfers`) are created by an administrator after each first run.

## Dashboard and KPIs

The `aml_overview` dashboard carries three KPI tiles (cash over limit, high-risk amount, inbound small transfers) and per-account Widgets for structuring, threshold, fan-in and high-risk traffic.

## Screening

Once an administrator has created the `aml_sanctions` and `aml_pep` Entity Lists and enabled `aml_screening_job`, the party register is screened against both lists.

## Investigating

Open the `aml_account_transfers` Link Analysis view to see how flagged accounts connect, and apply the money pattern packs (circular flow, inbound collector, layering chain, pass-through). See [Link Analysis](../../addon/linkAnalysis/index.md).

## Reporting

The `aml-str` Report Template is an illustrative STR layout for the Regulatory Reporting add-on.

## Runbooks

Per-typology guidance: `config/runbooks/aml-runbooks.md` in the Space.
