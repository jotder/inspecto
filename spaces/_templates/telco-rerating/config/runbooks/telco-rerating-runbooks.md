# Telecom re-rating (tariff validation) runbooks

> **Skeleton (slices R0 to R3).** This Space Template judges the tariff table, re-rates one day of rated usage at a time, explains each
> finding and groups the findings into root causes. It compares nothing with the invoice and raises no Alert, so there is no Incident to
> work from this file: the reading guide below is for the analyst who opens the `rerating_tariff_findings`, `rerated_usage`,
> `rerating_findings` and `rerating_root_causes` Datasets. The root-cause and Incident runbooks arrive with the Alert Rules in the next slices
> (`docs/superpower/telco-packs-plan.md`).

**This pack is an unbilled preview.** It re-rates usage for validation only: it is independent of the billing system, it never
writes to one, and it is not priced or ordered yet. A re-rated amount is evidence that a charge differs from the tariff table
as written, never proof that the table is right.

## What runs

Four chained `sql.template` Jobs:

1. `rr_tariff_hygiene` (daily at 04:30) judges the whole `tariff_version` Dataset and replaces the `rerating_tariff_findings`
   snapshot: one row per defect in the table itself, before any usage is judged.
2. `rr_rerate` runs when `rr_tariff_hygiene` has succeeded. It re-rates the day before (`window_start` to `window_end`, the
   `day` parameter names the partition) and writes one row per call to `rerated_usage` (31 days kept). Run it for another day
   by passing `window_start`, `window_end` and `day` on the run.
3. `rr_explain` runs when `rr_rerate` has succeeded, for the same day, and writes one row per finding to `rerating_findings`
   (31 days kept): the cause, its confidence and the revenue-at-risk columns.
4. `rr_root_cause` runs when `rr_explain` has succeeded and replaces the snapshot `rerating_root_causes`: one row per root cause over
   every retained day.

## Reading `rerating_tariff_findings`

| `REASON` | Reads as |
|---|---|
| `overlap_same_rate` | Two rows are in force at once with the same rate. Harmless to a charge, but it is a defect in the table. One row here, not one per call. |
| `overlap_different_rate` | Two rows are in force at once with different rates. A call in the overlap has no single expected charge unless `PRIORITY_ORDERED` is true. |
| `gap` | No row is in force between `PERIOD_FROM` and `PERIOD_TO`. Calls starting then have no tariff. |
| `negative_rate`, `missing_rate`, `missing_start` | A rate below zero, no rate, or no start date (never in force). A zero rate is a free plan and is not reported. |
| `inverted_interval` | `EFFECTIVE_TO` is not after `EFFECTIVE_FROM`. The row is never in force. |

`PRIORITY_ORDERED` is true on a conflict when an explicit `PRIORITY` separates the two rows, so the overlap is resolved by
design. It is still reported: a layered tariff is a defect to confirm, not to assume.

## Reading `rerated_usage`

A call is rated at its **start** (`EVENT_TS`): the rate in force then, `EFFECTIVE_FROM` inclusive and `EFFECTIVE_TO` exclusive,
however far the call runs past a change.

| `RESOLUTION` | Reads as |
|---|---|
| `single` | One tariff row in force. `EXPECTED_CHARGE` is its amount. |
| `agree` | Several rows, one amount. The call is judged against it; the duplicate is a tariff finding, not a call finding. |
| `precedence` | Several amounts, and one row holds the highest explicit `PRIORITY` (a blank counts as lowest, a tie has no winner). Judged against that row. |
| `disagree` | Several amounts and no winner. There is no single expected charge: `EXPECTED_LOW` to `EXPECTED_HIGH` is the range. |
| `none` | No usable tariff row (none in force, or its rate is blank). |

| `FINDING` | `REASON` | Reads as |
|---|---|---|
| `leakage` | `rate_mismatch` | Charged less than expected by more than the tolerance. `UNDERCHARGED_AMOUNT` is the shortfall against the nearest bound. |
| `overbilled` | `rate_mismatch` | Charged more than expected by more than the tolerance. `OVERBILLED_AMOUNT` is the excess. A customer and regulatory exposure, **not** recovered revenue. |
| `data_quality` | `ambiguous_tariff` | The charge lies inside the range an ambiguous tariff allows: explained, not leakage. |
| `data_quality` | `no_tariff` | The call has no usable tariff row (none in force, no rate, or only a row with a negative rate, which is never rated against). |
| `data_quality` | `unratable_call` | The call has a tariff but no charge or no units, so it cannot be judged. |

The two amounts are separate columns and never net: sum `UNDERCHARGED_AMOUNT` for what may be uncharged revenue and
`OVERBILLED_AMOUNT` for the customer exposure. A call within `tolerance` of the expected charge (default 0.02, absolute,
strict) is silent.

## Reading `rerating_findings`

One row per finding. `CAUSE` is what a counterfactual re-rating is **consistent with**, never proof that it is the only cause.

| `CAUSE` | Reads as |
|---|---|
| `stale_version` | The charge equals the amount of the version of that plan and service that ended most recently (`CAUSE_TARIFF_VERSION_ID`): an old rate is still in use. |
| `lower_priority_rate` | The call has a winner by `PRIORITY`, and the charge equals the amount of a row in force that lost (`CAUSE_TARIFF_VERSION_ID`). |
| `early_version` | The charge equals the amount of the next version, billed before it starts. |
| `wrong_plan` | The charge equals the amount of **another plan** that the subscriber's own calls of the day are billed at, while `subscription_history` assigns the subscriber a different plan at the call's start (`CAUSE_TARIFF_VERSION_ID` is the other plan's version). Its confidence is exactness times the share of the subscriber's calls (same service, that day) the inferred plan explains. Silent when fewer than `plan_min_rows` calls, no single top plan (two plans with the same rate), a share below `plan_share`, no history for the subscriber, two different plans assigned at once, or the assigned plan IS the inferred one (a plan change the rating label did not follow). |
| `unexplained` | No hypothesis fits, or none is supported well enough (confidence below `confidence_floor`). Confidence is 0. |
| `no_tariff`, `ambiguous_tariff`, `unratable_call` | A data-quality finding is its own cause (confidence 1): no usable tariff row; a charge inside the range an ambiguous tariff allows; no charge or no units on the call. |

`CAUSE_CONFIDENCE` is exactness (1 when the counterfactual reproduces the charge exactly, `within_tolerance_confidence` when only within the
tolerance) times support (the share of the same plan, service, candidate versions and finding that the same cause also explains). Every other
fitting cause is listed in `CAUSE_ALTERNATIVE`.

| Column | Reads as |
|---|---|
| `LEAKAGE_LOW` | The shortfall against the nearest bound of the expected range: the **revenue at risk**. Never includes over-billing. |
| `LEAKAGE_HIGH` | The shortfall against the dearest candidate. For an ambiguous tariff the gap between the two is exposure that the tariff might justify; it is not claimed. |
| `OVERBILLED_AMOUNT` | The excess over the expected charge: a customer and regulatory exposure, kept apart from the revenue at risk. |
| `LEAKAGE_AMOUNT` | A copy of `LEAKAGE_LOW`, for readers that expect the starter control's column list. |

## Reading `rerating_root_causes`

One row per `ROOT_CAUSE_ID` (cause, finding class, plan, service, the tariff versions in force and the version the cause points at; no date, so
a cause keeps its id from day to day). `CALLS`, `FIRST_DAY` and `LAST_DAY`, the sums of the three amounts, `RUN_RATE_PER_DAY` (`LEAKAGE_LOW`
over the calendar days from first to last), the weakest confidence in the group and up to three example `ITEM_KEY`s. Fix the tariff or the
rater once per root cause, not once per call. The KPIs `revenue_at_risk` (sum of `LEAKAGE_LOW`) and `overbilled_amount` are over `rerating_findings`.

## Feed contract

- `rated_usage`: one row per rated call or event: `XDR_ID`, `EVENT_TS` (the start; naive, one agreed zone), `EVENT_DATE`,
  `SUBSCRIBER_ID`, `PLAN`, `SERVICE` (`VOICE`, `SMS` or `DATA`), `USAGE_UNITS` and `CHARGE`. Flat `USAGE_UNITS x RATE_PER_UNIT`,
  rounded to two decimals; billing increments, minimum charges, zones, time bands, tax and allowances arrive with later slices.
- `subscription_history`: one row per period a subscriber was assigned a plan: `SUBSCRIBER_ID`, `PLAN`, `FROM_TS` (inclusive), `TO_TS`
  (exclusive; blank = open). Deliver the **whole** history in every file, like the tariff. It is an ordinary feed, not a `reference.load: scd2`
  store, for the reason below. `SUBSCRIBER_ID` is `INTERNAL` classified: use masked or synthetic ids. Without it (an empty Dataset) no `wrong_plan` is claimed.
- `tariff_version`: one row per version of a plan and service: `TARIFF_VERSION_ID`, `PLAN`, `SERVICE`, `RATE_PER_UNIT`,
  `EFFECTIVE_FROM`, `EFFECTIVE_TO` (blank = open) and the optional `PRIORITY`. Load the whole table in every file you deliver.
  Versions live in the file: a `reference.load: scd2` store stamps the **ingest** time as the version start and has no end, so it
  cannot carry effective dates for the past.

## Thresholds are configuration

`tolerance` is a Job parameter in `config/jobs/rr_rerate_job.toon`. The shipped value is **illustrative**; set it from the
operator's own rounding behaviour. In `config/jobs/rr_explain_job.toon`: `plan_share` (0.6, the share of a subscriber's calls one plan must
explain) and `plan_min_rows` (3, the calls it takes to infer a plan) are illustrative too; the confidence floor applies to a `wrong_plan` as to any cause.

## Sizing

The re-rating statement holds its working set in DuckDB memory. Measured on one synthetic day on a 12-core developer box with the default memory cap (3,246 MiB): peak about 0.4 GiB at 1 million rated rows, **1.8 GiB at 5 million** (the real Job took 16.9 s, process commit 2.7 GiB), 3.2 GiB at 10 million (97 % of the cap, spilling). **Above roughly 8 million rows a day on the default cap, raise `processing.duckdb.memory_limit` to at least 4 GiB.** These are one synthetic day, not an operator's volume; the plan's section 12 has the harness and every figure.
