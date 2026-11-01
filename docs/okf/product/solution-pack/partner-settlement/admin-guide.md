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

5. Point finance's record of payments, credit notes and debit notes at `data/inbox/settlement_payment` (the positions work without
   it, with nothing paid). A record sent twice counts once and a correction is a new record with the same `payment_id` and a later
   `recorded_at`. The pack reads these records and never writes one.

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

## Apply the dispute governance (once per Space)

A Space Template carries no Workflow, SLA Policy or Escalation Rule (decision PS-O5: they are governance, written only through their
`canAdminister` routes, and every import refuses them). The Case Rule and the Alert Rules ship and work without them, on the built-in
CASE workflow. To get the dispute states, an administrator posts the three bodies in `config/governance/` of the new Space:

```
POST /api/v1/components/workflow          ← config/governance/case-workflow.json
POST /api/v1/components/sla-policy        ← config/governance/case-sla-policy.json
POST /api/v1/components/escalation-rule   ← config/governance/case-escalation-age.json
```

⚠ The workflow is the Space's ONE CASE workflow: every Case of the Space follows it. It keeps every built-in state and move, so other
Cases still work; check for an existing authored CASE workflow first (`GET /api/v1/workflows/{type}` for the Case type) and merge rather than replace.
The SLA targets and the escalation age are illustrative: set your own. With an Approval Policy on these kinds the save is held for a
checker (`202 pending`).

## The dispute evidence pack

The two Report Templates ship in the Space (`config/regulatory-report-templates/`) and need the Regulatory Reporting add-on
(Professional and up); without it they are inert files. They read the Dataset `settlement_dispute` through the template `rows`
block (`matchOn: dispute_key`), so the drafter must be able to see that Dataset. Delivery drops into
`regulatory-submissions/settlement-dispute-pack` (and `-csv`) under the write root: put that directory where the settlement desk
picks files up, or edit `delivery.dir`. The layout is illustrative; replace the fields with what your partners accept.

## Tuning and as-of

There are no thresholds in the SQL. `ps_calendar`, `ps_expected` and `ps_rollforward` read the Job parameter `as_of` (default today); `ps_dispute` reads it too, with `window_alert_days` (default 5): the lead time before `dispute_window_end` at which
`ps_dispute_window` raises its reminder. Pass the same
date to all of them, and to `ps_aging` and `ps_accrual`, to reproduce a past day (the roll-forward has a row for every month up to the `as_of` month). Reconciliations read one day: run `ps_voice` and `ps_sms` per day when catching up.

`ps_aging` takes the bucket boundaries `aging_bucket_1`, `aging_bucket_2`, `aging_bucket_3` (default 30, 60, 90 days; the KPI "aged
over 60 days" follows `aging_bucket_2`, rename it if you change it) and `payment_alert_days` (default 5). `ps_accrual` takes
`provision_pct` (default 100): the share of the disputed amount provisioned, a finance policy. Both take `kpi_currency` (default USD):
the one settlement currency the KPI tiles count; change the KPIs' `unit` and titles with it.

## Verify

- `GET /api/v1/jobs` lists the chain Jobs `ps_calendar`, `ps_statement_current`, `ps_statement_findings`, `ps_expected`, `ps_billed`,
  `ps_findings`, `ps_dispute`, `ps_movement`, `ps_position`, `ps_rollforward`, `ps_aging`, `ps_accrual`, the Jobs `ps_voice`, `ps_sms` and the Case Rule Job
  `ps_dispute_case`; `GET /api/v1/cases/rules` lists `ps_dispute_case` (loaded from `config/ops/`).
- After the first run the Dataset `settlement_calendar` has the closed cycles with business-day due dates, and the dashboard
  `settlement_overview` renders its six tiles.
- The golden tests are `PartnerSettlementPackGoldenTest`, `PartnerSettlementPositionGoldenTest`, `PartnerSettlementDisputeGoldenTest`,
  `PartnerSettlementAgingGoldenTest`, `PartnerSettlementReferenceTest`, `PartnerSettlementSeedTest` and `PartnerSettlementCorpusTest` in module `inspecto-reconciliation`, and
  `PartnerSettlementDisputeCaseTest` in module `inspecto-case-management`.

## Safety

The pack publishes nothing, posts no payment or ledger entry and fetches no rate. Amounts are classified `CONFIDENTIAL`; no personal
data is held (partners are organisations). The Alert Rules and Reconciliations need the edition's Alert Rule and Reconciliation
capabilities to author, as for any Space.
