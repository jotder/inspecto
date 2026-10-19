---
type: Feature
title: "Payment fraud starter — admin guide"
description: "Install, enable and verify the Payment fraud pack."
tags: [payment-fraud, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Payment fraud starter — admin guide

## Prerequisites

- Edition: Standard and up (`minEdition: standard`); the Risk Score needs Scoring & Lists.
- Order code `INS-SP-PAYMENT-FRAUD` (`product-map/solution-pack/payment-fraud.toon`).

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `payment-fraud`.
2. Create a Space from it (needs `canAdminister`; the template's KPI pack also needs `canAuthorWorkbench`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "payment-fraud"}
   ```

   The first refusal creates no Space: 403 for a missing capability, 422 when a template KPI fails the KPI save gate (`docs/api/openapi-v1.json`, `postSpaces`).
3. Load the samples: copy each folder under `data/samples/<feed>` into `data/inbox/<feed>` — `payment_attempts`, `sim_changes`, `disputes`.

## After the first Risk Score run

Create the pending `pf_high_risk_account` from `config/pending/alert-rules/`: `GET /api/v1/alerts/rules/pending` lists it and `POST /api/v1/alerts/rules` creates it.

## Verify

- Files with a card number appear in the restricted quarantine, not in the Datasets.
- `GET /api/v1/jobs` shows the eight Jobs (seven feature Jobs and `pf_risk_score`); `POST /api/v1/jobs/{name}/trigger` runs one now.
- `GET /api/v1/risk-scores/payment_account/{entityKey}` returns an account's score after `pf_risk_score` has run.
- `GET /api/v1/alerts/rules` lists the four typology Alert Rules.

## Tuning

Thresholds are configuration in the Alert Rules and `registry/risk-scores/payment_account.toon`. Features are hourly batch, not authorisation-time scoring.
