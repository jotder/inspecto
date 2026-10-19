---
type: Feature
title: "AML — admin guide"
description: "Install, enable and verify the AML Solution Pack."
tags: [aml, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# AML — admin guide

## Prerequisites

- Edition: Standard, Advanced or Preview (`minEdition: standard` in the template; `offerings/standard.toon` lists `aml` under `contentPacks`).
- Order code `INS-SP-AML`.
- The pack uses the Scoring & Lists, Anomaly Detection, Screening, Regulatory Reporting and Link Analysis add-ons.
- The creating user needs `canAdminister`; the template's KPI pack also needs `canAuthorWorkbench` (see `POST /spaces` in `docs/api/openapi-v1.json`).

## Install

1. List the available Space Templates: `GET /spaces/templates` — `aml` should appear.
2. Create the Space: `POST /spaces` with body `{"id": "<space-id>", "template": "aml"}`. A refusal (403/422) creates nothing.

## Load data

Copy each sample feed from `data/samples/<feed>` into `data/inbox/<feed>` (`aml_txn`, `aml_parties`), or deliver your own extracts with the same schemas (`config/aml_txn/aml_txn_schema.toon`, `config/aml_parties/aml_parties_schema.toon`).

## Enable the pending parts

- After `aml_risk_score_job` has run once, create the Alert Rule from `config/pending/alert-rules/aml_high_risk_account.toon`.
- After `aml_anomaly_score_job` has run once, create the Alert Rule from `config/pending/alert-rules/aml_unusual_account_transfers.toon`.
- Screening: create the Entity Lists `aml_sanctions` and `aml_pep`, then enable `aml_screening_job` (ships `enabled: false`).

A synthetic screening walk-through: `node tools/seed-screening-demo.mjs --spaces <spaces-dir>` writes the roster; boot the bundle and run it again with `--base <url>`, which creates the Space `screening-demo` from the `aml` template, drops a party register and creates both Entity Lists with synthetic entries; it leaves the Job for you to enable.

## Tune

Thresholds, the reporting limit and the high-risk jurisdiction list are configuration in the Job files under `config/jobs/`.

## Verify

- The four detection Jobs and the score Jobs run on schedule and their Datasets (`aml_structuring`, `aml_threshold`, `aml_fan_in`, `aml_high_risk_traffic`) fill.
- The `aml_overview` dashboard shows its KPI tiles.
- Alerts appear for the planted accounts in the golden corpus.

## References

[Features](features.md) · [User guide](user-guide.md) · [Architecture](architecture.md)
