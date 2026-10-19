---
type: Feature
title: "Telecom revenue assurance — admin guide"
description: "Install, enable and verify the Telecom revenue assurance Solution Pack."
tags: [telco-ra, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom revenue assurance — admin guide

## Prerequisites

- Edition: Standard, Advanced or Preview (`minEdition: standard`; `offerings/standard.toon` lists `telco-ra` under `contentPacks`). It uses Alert Rules and Reconciliations.
- Order code `INS-SP-TELCO-RA`.
- The creating user needs `canAdminister`; the template's KPI pack also needs `canAuthorWorkbench` (see `POST /spaces` in `docs/api/openapi-v1.json`).

## Install

1. List the available Space Templates: `GET /spaces/templates`; `telco-ra` should appear.
2. Create the Space: `POST /spaces` with body `{"id": "<space-id>", "template": "telco-ra"}`. A refusal (403/422) creates nothing.

## Load data

Copy each sample feed from `data/samples/<feed>` into `data/inbox/<feed>` (`switch_xdr`, `mediated_xdr`, `rated_usage`, `billed_invoice`, `tariff`, `balance_ledger`, `ic_rates`, `ic_statement`), or deliver your own extracts with the schemas under `config/<feed>/<feed>_schema.toon`.

## Enable optional parts

The completeness tile needs `ra_switch_kpi_completeness_job` (`kpi.completeness`), which ships `enabled: false`.

## Tune

- Reconciliation tolerances: `config/registry/reconciliations/` (`USAGE_UNITS` absolute 1; `CHARGE` absolute 0.05).
- Alert thresholds: `config/registry/alert-rules/`.
- Control SQL and schedules: `config/jobs/`.

## Verify

- The eight feed Datasets fill after ingest.
- The Reconciliation Jobs run (cron `0 5 * * *` and `5 5 * * *`) and breaks open Incidents.
- `ra_leakage` and `ra_data_quality` fill; `ra_leakage_found` raises one Alert per control on the planted leakages.
- `ra_overview` shows leakage found / confirmed / recovered.

## References

[Features](features.md) · [User guide](user-guide.md) · [Architecture](architecture.md)
