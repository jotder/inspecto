---
type: Feature
title: "Telecom fraud — admin guide"
description: "Install, enable and verify the Telecom fraud Solution Pack."
tags: [telco-fraud, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom fraud — admin guide

## Prerequisites

- Edition: Standard, Advanced or Preview (`minEdition: standard`; `offerings/standard.toon` lists `telco-fraud` under `contentPacks`). The Anomaly Model part needs Anomaly Detection.
- Order code `INS-SP-TELCO-FRAUD`.
- The creating user needs `canAdminister` (and `canAuthorWorkbench` for the KPI pack); the template's Alert Rules need `canAuthorAlertRules` (per `tools/seed-telco-fraud-demo.mjs`).

## Install

1. List the available Space Templates: `GET /spaces/templates` — `telco-fraud` should appear.
2. Create the Space: `POST /spaces` with body `{"id": "telco-fraud", "template": "telco-fraud"}`. A refusal (403/422) creates nothing.

## Load data

Copy each sample feed from `data/samples/<feed>` into `data/inbox/<feed>` (`cdr`, `subscriber_events`, `payments`), then run the Jobs. The detection Jobs roll to the previous day.

For a demo, `tools/seed-telco-fraud-demo.mjs` does this file-based, no server needed:

```
node tools/seed-telco-fraud-demo.mjs --spaces <spaces-dir> [--today 2026-10-14]
```

Run it once to write a demo sign-in roster; sign in as `admin`, create the Space `telco-fraud` from the gallery, then run it again. The second run copies the roster in and drops the sample feeds into `data/inbox/<feed>` with every date shifted so the sample day is yesterday.

## Enable the pending part

After `tf_anomaly_score_job` has run once, create the Alert Rule from `config/pending/alert-rules/tf_unusual_subscriber_usage.toon`.

## Tune

Thresholds are in the Job files under `config/jobs/`; the Case Rule window and grouping in `config/ops/fraud_critical_burst_caserule.toon`.

## Verify

- `cdr`, `subscriber_events` and `payments` fill after ingest.
- The `fraud_<typology>` Datasets fill after the detection Jobs; Alerts appear after `fraud_alert_evaluate_job`.
- `data/exports/fraud_offender_export` (the Space data tree) holds the nightly CSV.
- The `telco_fraud_overview` dashboard shows the exposure tiles.

## References

[Features](features.md) · [User guide](user-guide.md) · [Architecture](architecture.md)
