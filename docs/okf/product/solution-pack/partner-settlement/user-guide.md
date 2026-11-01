---
type: Feature
title: "Partner settlement — user guide"
description: "How a settlement analyst reads the findings, Breaks and calendar of the Partner settlement pack."
tags: [partner-settlement, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Partner settlement — user guide

What a Space created from the `partner-settlement` template gives an analyst in slices S1 to S6. Design detail is in [architecture](architecture.md); the per-reason reading guide is `config/runbooks/partner-settlement-runbooks.md` in the Space.

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 01:00 | `ps_calendar`, then the eleven Jobs after it, each on the previous one's success | the calendar, the current statement lines, statement findings, expected and billed amounts, settlement findings, the dispute lines, then the movements, the position, the roll-forward, the aging and the accruals |
| 05:30 | `ps_voice`, `ps_sms` | Breaks per agreement, direction, service, destination class and day; an Incident when a Reconciliation breaches |
| 06:00 | `ps_dispute_case` | the dispute Incidents grouped into one Case per partner and cycle |

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

## Working a dispute

1. **The Case is the dispute, the Incident is the line.** Every `amount_mismatch` and `fx_difference` is a row of
   `settlement_dispute` and a CRITICAL Incident (`ps_dispute_line`); the Case Rule `ps_dispute_case` puts every line of one partner
   and cycle into one Case (category `partner-settlement`). A cut-off pair is a timing convention and never a dispute line.
2. **Move the Case** (once an administrator has applied the dispute workflow): `investigate`, then `dispute` when the claim is sent
   to the partner, then `agree` or `reject`; an agreed dispute goes to `await_credit` (`CREDIT_PENDING`) and is closed when the credit
   note arrives; a rejected one is escalated again or closed. Without the workflow the Case follows the built-in path.
3. **Record the Impact** on the Case (PS-D6), in the settlement currency: `suspected` = what you disputed (start from the sum of
   `disputed_amount` of its lines), `confirmed` = the credit the partner accepted, `recovered` = the credit note received (still
   allowed on the closed Case), `prevented` = a variance caught before we paid.
4. **The contractual deadline is data.** Each dispute line carries the cycle's `dispute_window_end` and `days_to_window_end`;
   `ps_dispute_window` opens a reminder Incident in the Case when the window closes within five days (the Job parameter
   `window_alert_days`). It does not read the Case's state: if the dispute is already sent, resolve the reminder.
5. **File the evidence pack** when the claim goes to the partner: in *Operations ▸ Regulatory Reports*, **New report**, template
   `settlement-dispute-pack` (JSON) or `settlement-dispute-pack-csv`, the dispute Case, and a narrative. The pack lists every
   disputed line with the partner's statement id and version, our rate ids and re-rating run, the variance and its reason, the
   agreement version and tolerance, both FX rows, and the Case's Incidents. A line with no re-rating run refuses the draft: re-rate
   the cycle first. A second person approves it and it is dropped to `regulatory-submissions/settlement-dispute-pack*`; you send it.
   Without the capability to reveal entity keys you cannot read the drafted pack back; the approver can.
6. **Internal targets** come from the Case SLA Policy and an escalation that notifies when a settlement Case is three days old and
   still not `DISPUTED` (both illustrative, set by the administrator).

## Reading the position and the roll-forward

1. **`settlement_position`** has one row per netting group, cycle month and settlement currency: `payable` (what the partner statements say we owe), `receivable`, `net` (positive = we pay) and `net_direction`. `orig_net` shows the net in each original currency (for example `EUR:-4.0000,USD:123.0000`), so a converted statement is visible in both currencies. A cycle with no statement has no row.
2. **`settlement_rollforward`** has one row per month for every netting group and currency, from the first movement to today: `opening + invoiced - paid + adjustments = closing` (positive = we owe). A quiet month repeats the balance. A restated statement changes the `invoiced` of its month and every later `closing`.
3. **`settlement_movement`** is the line-level truth under both, with the FX rate, its date and its type on every converted line. Filter on `status`: `fx_missing` lines add no amount and are counted in `lines_unconverted`; `unknown_agreement` lines (a payment naming an agreement or partner finance has not set up) are in no balance.
4. Netting is inside one settlement currency, and a position is what the statements say, not an agreed balance. `disputed_amount` and `disputed_lines` show the dispute lines of that group and month beside the net; they are never netted into it, and they do not change with the Case's outcome.

## Aging, accruals and the dashboard

1. **`settlement_aging`** has one row per agreement and statement cycle, in the agreement's direction (`we_pay`: what we owe;
   `they_pay`: what the partner owes us): `invoiced`, `settled_applied` (finance's payments and notes applied to the oldest cycle
   first), `open_balance`, `open_disputed`, `age_days` from the date the statement was received and `age_bucket`. A payment finance
   filed under the wrong partner settles nothing, so its cycle stays open.
2. **`ps_payment_due`** raises one WARNING Alert per cycle whose `open_balance` is due within five days (`payment_alert_days`) or is
   late. The balance includes the disputed amount; pay the undisputed part and leave the rest to the dispute Case.
3. **`settlement_accrual`** has one row per agreement and closed cycle. `status` is `billed`, `unbilled` (no statement yet, not due)
   or `statement_late` (no statement and past `statement_due`, when `missing_statement` also fires). `accrued_unbilled` is our expected
   amount while no statement has arrived; `dispute_provision` is the provision on the disputed amount. Finance posts accruals; the pack
   only reports them.
4. **The `settlement_overview` dashboard** shows the open disputed amount, the open balance aged over 60 days and the accrued unbilled
   amount as tiles (USD only by default, payables and receivables added as amounts at stake) and three bars that never add two currencies (by age bucket and currency, by partner and currency, by agreement).

## What it will not tell you

A finding is a difference between what a partner says is owed and what we price ourselves, never proof the partner is wrong: the
rate card, the attribution of usage to an agreement and the cut-off are inputs this pack trusts. It does not move money, post
accruals or send a dispute to the partner (the evidence pack is a file you send); finance processes do that.

## Verify

- `GET /api/v1/jobs` lists the twelve `ps_*` chain Jobs, the two Reconciliation Jobs and `ps_dispute_case`.
- After a run, `settlement_calendar` has a row per agreement and closed cycle; `settlement_findings` is empty on clean data.
