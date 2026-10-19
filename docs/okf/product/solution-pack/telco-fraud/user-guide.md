---
type: Feature
title: "Telecom fraud — user guide"
description: "Working day to day in a Space created from the telco-fraud Space Template."
tags: [telco-fraud, user-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom fraud — user guide

How a fraud analyst works in a Space created from the `telco-fraud` Space Template. Install steps: [admin guide](admin-guide.md).

## The data

Three Pipelines ingest the feeds into Datasets of the same name: `cdr`, `subscriber_events` and `payments`. Files dropped into `data/inbox/<feed>` are picked up by the matching Pipeline. Seven Expectations check the feeds (for example `tf_cdr_a_number_non_null`, `tf_payments_amount_in_range`); `fraud_expectation_evaluate_job` evaluates them.

## The nightly cycle

- The fourteen detection Jobs (`fraud_<typology>_job`) run over the previous day and write one row per offender to `fraud_<typology>` Datasets (for example `fraud_irsf`, `fraud_simbox`, `fraud_sim_swap`).
- `fraud_alert_evaluate_job` evaluates the fourteen Alert Rules, one Alert per offender per typology.
- `fraud_offender_export` writes a CSV of IRSF offenders (summed `irsf_seconds` and `irsf_calls` by `msisdn`) to `exports/fraud_offender_export`.
- `tf_anomaly_score_job` scores the `tf_subscriber_usage` Anomaly Model.

## Incidents and Cases

- Alerts are triaged in the Incidents screens ([Incidents user guide](../../addon/incidents/user-guide-incidents.md)).
- A roaming voice call over two hours opens a CRITICAL Incident (Decision Rule `tf_long_roaming_call_escalate`).
- Payment reversals carry their typology so they route to the payment-reversal runbook (Decision Rule `tf_reversal_route_by_typology`).
- The Case Rule `fraud_critical_burst` ("Critical fraud Incidents on one offender") groups CRITICAL Incidents by `msisdn` within a day into a Case tagged `fraud-desk`. See [Case Management](../../addon/caseManagement/index.md).

## Dashboard

The `telco_fraud_overview` dashboard shows the exposure KPIs (IRSF seconds, premium rate, roaming, reversed amount) and per-typology Widgets.

## Runbooks

Each Alert Rule has a runbook in `config/registry/runbooks/<typology>.toon`; the narrative is `config/runbooks/telco-fraud-runbooks.md`.
