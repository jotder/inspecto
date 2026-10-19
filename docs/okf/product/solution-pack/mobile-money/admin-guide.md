---
type: Feature
title: "Mobile money — admin guide"
description: "Install, enable and verify the Mobile money pack."
tags: [mobile-money, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Mobile money — admin guide

## Prerequisites

- Edition: Standard, Advanced or Preview (`minEdition: standard` in `spaces/_templates/mobile-money/template.toon`). The pack needs Alert Rules, Reconciliations, a Risk Score (Scoring & Lists) and an Anomaly Model (Anomaly Detection).
- Order code `INS-SP-MOBILE-MONEY` (`product-map/solution-pack/mobile-money.toon`).

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `mobile-money`.
2. Create a Space from it (needs `canAdminister`; a template that carries a KPI pack also needs `canAuthorWorkbench`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "mobile-money"}
   ```

   The first refusal creates no Space: 403 for a missing capability, 422 when a template KPI fails the KPI save gate (`docs/api/openapi-v1.json`, `postSpaces`).
3. Load the samples: copy each folder under `data/samples/<feed>` into `data/inbox/<feed>` in the new Space.
   Feeds: `wallet_txn`, `wallets`, `bank_statement`, `partner_settlement`, `core_subscribers`.

## After the first scoring run

Create the two pending Alert Rules from `config/pending/alert-rules/` (`mm_high_risk_agent`, `mm_unusual_wallet_activity`): `GET /api/v1/alerts/rules/pending` lists them and `POST /api/v1/alerts/rules` creates one.

## Verify

- `GET /api/v1/jobs` shows the eleven Jobs of `config/jobs/`; `POST /api/v1/jobs/{name}/trigger` runs one now instead of waiting for its schedule, and `GET /api/v1/jobs/runs` shows the result.
- `GET /api/v1/alerts/rules` lists the seven typology Alert Rules; `GET /api/v1/alerts` shows Alerts after the detection Jobs run.
- The `mobile_money_overview` dashboard fills once the Pipelines have ingested the samples.

## Tuning

Every rate, limit and threshold is configuration, tuned to the synthetic corpus — review them in the Jobs, Alert Rules, `registry/risk-scores/mm_agent.toon` and `registry/anomaly-models/mm_wallet_activity.toon` before using real data.
