---
type: Feature
title: "Partner settlement — user guide"
description: "How a settlement analyst reads the findings, Breaks and calendar of the Partner settlement pack."
tags: [partner-settlement, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Partner settlement — user guide

What a Space created from the `partner-settlement` template gives an analyst in slices S1 to S4. Design detail is in [architecture](architecture.md); the per-reason reading guide is `config/runbooks/partner-settlement-runbooks.md` in the Space.

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 01:00 | `ps_calendar`, then the eight Jobs after it, each on the previous one's success | the calendar, the current statement lines, statement findings, expected and billed amounts, settlement findings, then the movements, the position and the roll-forward |
| 05:30 | `ps_voice`, `ps_sms` | Breaks per agreement, direction, service, destination class and day; an Incident when a Reconciliation breaches |

## Reading the work

1. **Alerts.** `ps_findings_found` raises one Alert per agreement that has any finding. Open the Dataset `settlement_findings`
   filtered to that `agreement_id`.
2. **Finding class.** `variance` rows (`amount_mismatch`, `cutoff_difference`, `fx_difference`) are differences between the
   partner's statement and our own pricing; `data_quality` rows (`missing_statement`, `unrated_usage`, `fx_rate_missing`) say
   something could not be judged. Statement-level problems are in `settlement_statement_findings`.
3. **Each variance row** carries `expected_amount`, `billed_amount`, `variance`, `tolerance_amount`, `side` and the day; the
   matching `settlement_expected` row names the rate (`rate_ids`), the rating run and the FX rate and date used.
4. **Cut-off differences come in pairs** (the same amount moves between two days): raise it with the partner as a convention, not as an
   overbilling. An **FX difference** names the partner's own rate on the statement line.
5. **A missing statement** is one finding per cycle, only after the agreement's `statement_due` date; before it nothing is raised.
6. **Breaks** work as in any Reconciliation (assign, resolve, auto-close when the data is fixed); promote a Break to an Incident from
   the Reconciliation screen.

## Reading the position and the roll-forward

1. **`settlement_position`** has one row per netting group, cycle month and settlement currency: `payable` (what the partner statements say we owe), `receivable`, `net` (positive = we pay) and `net_direction`. `orig_net` shows the net in each original currency (for example `EUR:-4.0000,USD:123.0000`), so a converted statement is visible in both currencies. A cycle with no statement has no row.
2. **`settlement_rollforward`** has one row per month for every netting group and currency, from the first movement to today: `opening + invoiced - paid + adjustments = closing` (positive = we owe). A quiet month repeats the balance. A restated statement changes the `invoiced` of its month and every later `closing`.
3. **`settlement_movement`** is the line-level truth under both, with the FX rate, its date and its type on every converted line. Filter on `status`: `fx_missing` lines add no amount and are counted in `lines_unconverted`; `unknown_agreement` lines (a payment naming an agreement or partner finance has not set up) are in no balance.
4. Netting is inside one settlement currency, and a position is what the statements say, not an agreed balance: disputes are not read.

## What it will not tell you

A finding is a difference between what a partner says is owed and what we price ourselves, never proof the partner is wrong: the
rate card, the attribution of usage to an agreement and the cut-off are inputs this pack trusts. It does not move money, post
accruals or open a dispute with the partner; those are later slices and finance processes.

## Verify

- `GET /api/v1/jobs` lists the nine `ps_*` chain Jobs and the two Reconciliation Jobs.
- After a run, `settlement_calendar` has a row per agreement and closed cycle; `settlement_findings` is empty on clean data.
