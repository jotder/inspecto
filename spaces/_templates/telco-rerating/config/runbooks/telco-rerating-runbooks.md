# Telecom re-rating (tariff validation) runbooks

> **Skeleton (slices R0 and R1).** This Space Template judges the tariff table and re-rates one day of rated usage at a time.
> It explains no cause yet, groups no root cause, compares nothing with the invoice and raises no Alert, so there is no Incident to
> work from this file: the reading guide below is for the analyst who opens the `rerating_tariff_findings` and `rerated_usage`
> Datasets. The root-cause and Incident runbooks arrive with the Alert Rules in the next slices
> (`docs/superpower/rerating-tariff-validation-plan.md`).

**This pack is an unbilled preview.** It re-rates usage for validation only: it is independent of the billing system, it never
writes to one, and it is not priced or ordered yet. A re-rated amount is evidence that a charge differs from the tariff table
as written, never proof that the table is right.

## What runs

Two chained `sql.template` Jobs:

1. `rr_tariff_hygiene` (daily at 04:30) judges the whole `tariff_version` Dataset and replaces the `rerating_tariff_findings`
   snapshot: one row per defect in the table itself, before any usage is judged.
2. `rr_rerate` runs when `rr_tariff_hygiene` has succeeded. It re-rates the day before (`window_start` to `window_end`, the
   `day` parameter names the partition) and writes one row per call to `rerated_usage` (31 days kept). Run it for another day
   by passing `window_start`, `window_end` and `day` on the run.

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
| `data_quality` | `no_tariff` | The call has no usable tariff row. |

The two amounts are separate columns and never net: sum `UNDERCHARGED_AMOUNT` for what may be uncharged revenue and
`OVERBILLED_AMOUNT` for the customer exposure. A call within `tolerance` of the expected charge (default 0.02, absolute,
strict) is silent.

## Feed contract

- `rated_usage`: one row per rated call or event: `XDR_ID`, `EVENT_TS` (the start; naive, one agreed zone), `EVENT_DATE`,
  `SUBSCRIBER_ID`, `PLAN`, `SERVICE` (`VOICE`, `SMS` or `DATA`), `USAGE_UNITS` and `CHARGE`. Flat `USAGE_UNITS x RATE_PER_UNIT`,
  rounded to two decimals; billing increments, minimum charges, zones, time bands, tax and allowances arrive with later slices.
- `tariff_version`: one row per version of a plan and service: `TARIFF_VERSION_ID`, `PLAN`, `SERVICE`, `RATE_PER_UNIT`,
  `EFFECTIVE_FROM`, `EFFECTIVE_TO` (blank = open) and the optional `PRIORITY`. Load the whole table in every file you deliver.
  Versions live in the file: a `reference.load: scd2` store stamps the **ingest** time as the version start and has no end, so it
  cannot carry effective dates for the past.

## Thresholds are configuration

`tolerance` is a Job parameter in `config/jobs/rr_rerate_job.toon`. The shipped value is **illustrative**; set it from the
operator's own rounding behaviour.

## Sizing

The re-rating statement holds its working set in DuckDB memory. Measured on one synthetic day on a 12-core developer box with the default memory cap (3,246 MiB): peak about 0.4 GiB at 1 million rated rows, **1.8 GiB at 5 million** (the real Job took 16.9 s, process commit 2.7 GiB), 3.2 GiB at 10 million (97 % of the cap, spilling). **Above roughly 8 million rows a day on the default cap, raise `processing.duckdb.memory_limit` to at least 4 GiB.** These are one synthetic day, not an operator's volume; the plan's section 12 has the harness and every figure.
