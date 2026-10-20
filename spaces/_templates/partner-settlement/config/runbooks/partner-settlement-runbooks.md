# Partner settlement runbooks

> **Slices S1 to S3 of the plan** (`docs/superpower/partner-settlement-wholesale-plan.md`): the References, statement ingestion and
> the compare. There is no dispute Case workflow, netting, aging or accrual here yet; a finding opens an Incident through the
> Reconciliation and the Alert Rule, and the Case, the evidence pack and the finance positions arrive with the next slices.

**Every number here is synthetic.** No real partner statement, TAP file or wholesale invoice has been seen: the statement layout
is one canonical Schema, and mapping a partner's own layout into it is a Pipeline mapping, outside this pack. A finding is a
difference between what a partner says is owed and what we price ourselves, never proof that the partner is wrong.

## What runs

Six chained `sql.template` Jobs (each runs when the previous one succeeds; the first runs daily at 01:00) and two `recon.run` Jobs
(05:30):

| Job | Writes | What it does |
|---|---|---|
| `ps_calendar` | `settlement_calendar` | One row per agreement and closed cycle with `statement_due`, `dispute_window_end` and `payment_due`, each rolled forward to the next business day (weekends and the partner country's holidays). |
| `ps_statement_current` | `settlement_statement_current` | The statement lines that count: a re-sent line (same statement id, version and line number) once, and only the highest version per agreement and cycle. Superseded versions stay in `settlement_statement`. |
| `ps_statement_findings` | `settlement_statement_findings` | `control_total_mismatch`, `duplicate_statement`, `restated_statement`, `unknown_partner`, `unknown_agreement`. |
| `ps_expected` | `settlement_expected` | Our independently re-rated usage per agreement, direction, service, destination class and day, in the agreement's settlement currency, with the tolerance in force that day. |
| `ps_billed` | `settlement_billed` | The partner's usage lines at the same grain and currency. `amount` is snapped to our expected amount when the variance is inside the tolerance (so the Reconciliation compares at tolerance 0); `amount_billed` is always the true billed amount. |
| `ps_findings` | `settlement_findings` | `amount_mismatch`, `cutoff_difference`, `fx_difference` (finding class `variance`); `missing_statement`, `unrated_usage`, `fx_rate_missing` (class `data_quality`). |
| `ps_voice`, `ps_sms` | Break state | One Reconciliation per service family on `settlement_expected` vs `settlement_billed`: Break lifecycle, recurrence and the Incident. |

The two Jobs that read the as-of day, `ps_calendar` and `ps_expected`, take an `as_of` parameter (a date, default today); pass the
same date to both to reproduce a past day. Only cycles that ended before `as_of` are calendared, and a cycle's expected amounts are
compared only once its statement is received or its `statement_due` is before `as_of`.

## Reading a finding

| Reason | Meaning | First step |
|---|---|---|
| `amount_mismatch` | The billed amount for that agreement, direction, service, destination class and day differs from ours by more than the tolerance in force that day. `side` says `both`, `ours_only` (we priced it, the statement has no line) or `statement_only`. | Compare `expected_amount` and `billed_amount`; check the rate (`rate_ids` on `settlement_expected`) and the rounding rule on the agreement. |
| `cutoff_difference` | The day's variance is cancelled by an opposite variance on the next or previous day: the partner's day boundary is not ours. Reported on both days. | Ask the partner for its cut-off time and zone; do not dispute the amount. |
| `fx_difference` | The partner billed in the settlement currency but converted our priced amount at its own rate (`fx_rate_applied`), and that explains the variance. | Compare the partner's rate with `fx_rate` on the matching `settlement_expected` row (rate type and date come from the agreement). |
| `missing_statement` | Our priced usage exists for a cycle whose `statement_due` has passed and no statement lines arrived. One finding per cycle. Before the due date it is silent (an accrual in a later slice). | Chase the partner. |
| `unrated_usage` | The re-rater could not price the day (`no_rate_found`, `ambiguous_rate`, `unknown_agreement`). Only the latest rating run counts. Never counted as a partner variance. | Fix the rate card in the re-rating pack. |
| `fx_rate_missing` | A converted amount has no `fx_rate` row on or before its date for the agreement's rate type. No amount is invented; the day is left out of the compare. | Add the rate to the `fx_rate` feed. |

Statement findings: `control_total_mismatch` (the lines do not add up to the header totals the partner stated; data quality, the
statement may still be right), `duplicate_statement` (a line was sent twice; it is counted once), `restated_statement` (a higher
version supersedes an earlier one; information), `unknown_partner` and `unknown_agreement` (the statement names a partner or an
agreement that is not in the References, so none of its lines are compared).

## Tolerance, FX and cut-off are agreement data

They come from `settlement_agreement` (effective-dated: a change is a new row with its own `valid_from`), never from the SQL.
`tolerance_type` is `pct` (percent of the expected amount) or `abs` (an amount in the settlement currency); variance **up to and
including** the tolerance is silent, one step beyond it is a finding. FX uses the `fx_rate` rows whose `rate_type` matches the
agreement's `fx_rate_type`, taking the latest row on or before the date the agreement's `fx_date_rule` names (`usage_date` or
`period_end`); the platform fetches no market rate, so finance feeds `fx_rate`. The cut-off zone and time on the agreement are
documentation: `usage_date` must already be bucketed in that zone (by the re-rater and by the statement mapping).

## The re-rating contract (a stand-in feed)

`rerated_usage` is the one Dataset this pack needs from the re-rating work: one row per agreement, direction, service, destination
class, day and rating run, with `units`, `amount_expected`, `currency`, `rate_id`, `rating_run_id` and `rated_at`. Until the
re-rating pack exists, load it as a feed (copy the CSV into `data/inbox/rerated_usage`). The compare uses the latest run per
agreement and cycle; a row with a blank `amount_expected` must carry a `reason` and becomes `unrated_usage`.

## Limits

- A statement line with `line_kind` other than `usage` (tax, fee, credit, adjustment) counts toward the control totals but is not
  compared in this slice; netting and positions arrive in S4.
- An agreement's `cycle` must not change between two versions inside one cycle: cycles are calendar-aligned (monthly, semi-monthly
  1-15 and 16-end, weekly Monday-Sunday).
- The compare is per day. A Reconciliation reads one day, so the `recon.run` Jobs run for the latest day on any side unless a
  `day` is passed; run them per day when catching up.
- Break detail is capped by the add-on (50,000 recorded Breaks per Reconciliation). The compare is per agreement and day, not
  per record, which keeps a wholesale partner far below it; split a Reconciliation per service if it is ever reached.
