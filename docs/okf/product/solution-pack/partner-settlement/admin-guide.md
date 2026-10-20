---
type: Feature
title: "Partner settlement — admin guide"
description: "Install, load and verify the Partner settlement pack."
tags: [partner-settlement, admin-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Partner settlement — admin guide

## Prerequisites

- Edition: Standard and up (`minEdition: standard`); the Reconciliation and Alert Rule capabilities of that edition.
- Product Map Item `solution-pack.partner-settlement` (`product-map/solution-pack/partner-settlement.toon`); commercial fields are not set.

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `partner-settlement`.
2. Create a Space from it (needs `canAdminister`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "partner-settlement"}
   ```

   The first refusal creates no Space.
3. Load the References once, in this order, by copying each CSV into its inbox: `data/inbox/partner`,
   `data/inbox/settlement_agreement`, `data/inbox/holiday`, then `data/inbox/fx_rate` (a new rate row each time finance sets one).
4. Point finance's statement mapping at `data/inbox/settlement_statement` (one file per statement version; a re-sent file is counted
   once) and the re-rating output at `data/inbox/rerated_usage`. **No sample files ship** for a real contract: use the Schemas in
   `config/<feed>/<feed>_schema.toon`. The synthetic corpus used by the tests is generated, not committed.

## The statement contract

Each statement line carries its statement's `control_total_amount` and `control_total_lines`; the integrity control sums the lines
(after removing re-sent ones) and compares. `line_kind` is `usage` for lines that are compared; `tax`, `fee`, `credit` and
`adjustment` lines count toward the totals only. Every usage line needs `usage_date`, `direction`, `service` and `destination_class`
at the grain the re-rater uses, and `agreement_id` (resolve a partner-side code in the Pipeline mapping). Currency per line is
ISO 4217; `fx_rate_applied` is optional and only names an FX difference.

## The agreement Reference

One row per effective-dated version, `valid_from` and `valid_to` inclusive (blank `valid_to` = open). A change of tolerance, currency
or due days is a **new row**, never an edit of the old one: the compare uses the version in force on each usage date, and the
calendar assigns a cycle to the version in force on its last day. Keep `cycle` constant across versions inside a cycle.
`tolerance_type` is `pct` or `abs`; `fx_rate_type` selects which `fx_rate` rows convert; `fx_date_rule` is `usage_date` or
`period_end`.

## Tuning and as-of

There are no thresholds in the SQL. `ps_calendar` and `ps_expected` read the Job parameter `as_of` (default today); pass the same
date to both to reproduce a past day. Reconciliations read one day: run `ps_voice` and `ps_sms` per day when catching up.

## Verify

- `GET /api/v1/jobs` lists the chain Jobs `ps_calendar`, `ps_statement_current`, `ps_statement_findings`, `ps_expected`, `ps_billed`,
  `ps_findings` and the Jobs `ps_voice`, `ps_sms`.
- After the first run the Dataset `settlement_calendar` has the closed cycles with business-day due dates.
- The golden tests are `PartnerSettlementPackGoldenTest`, `PartnerSettlementReferenceTest`, `PartnerSettlementSeedTest` and
  `PartnerSettlementCorpusTest` in module `inspecto-reconciliation`.

## Safety

The pack publishes nothing, posts no payment or ledger entry and fetches no rate. Amounts are classified `CONFIDENTIAL`; no personal
data is held (partners are organisations). The Alert Rule and Reconciliations need the edition's Alert Rule and Reconciliation
capabilities to author, as for any Space.
