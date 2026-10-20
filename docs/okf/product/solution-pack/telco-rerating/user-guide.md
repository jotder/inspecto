---
type: Feature
title: "Telecom re-rating (tariff validation) — user guide"
description: "How an analyst reads the tariff-hygiene findings and the re-rated usage in the Telecom re-rating pack."
tags: [telco-rerating, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom re-rating (tariff validation) — user guide

What a Space created from the `telco-rerating` template gives an analyst in slices R0 to R2. Design detail is in [architecture](architecture.md); the reading guide is `config/runbooks/telco-rerating-runbooks.md` in the Space.

## Data arriving

Copy the day's rated usage file (`RATED_USAGE_<yyyyMMdd>.csv`) into `data/inbox/rated_usage`. Copy the **whole** tariff table, one row per version, into `data/inbox/tariff_version` whenever it changes (`TARIFF_VERSION_<yyyyMMdd>.csv`).

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 04:30 | `rr_tariff_hygiene` | the Dataset `rerating_tariff_findings`: one row per defect in the tariff table (replaced each run) |
| after it succeeds | `rr_rerate` | the Dataset `rerated_usage`: one row per call of the previous day (31 days kept) |
| after it succeeds | `rr_explain` | the Dataset `rerating_findings`: one row per finding of that day, with its cause (31 days kept) |
| after it succeeds | `rr_root_cause` | the Dataset `rerating_root_causes`: one row per root cause over every retained day (replaced each run) |

Run `rr_rerate` for another day with `POST /api/v1/jobs/rr_rerate/trigger`, passing `window_start`, `window_end` and `day`.

## Reading the tariff findings first

A defect in the table explains many call findings, so read `rerating_tariff_findings` before `rerated_usage`. `REASON` is `overlap_same_rate` (two rows in force at once, the same rate), `overlap_different_rate` (the same, different rates), `gap` (no row in force between `PERIOD_FROM` and `PERIOD_TO`), `negative_rate`, `missing_rate`, `missing_start` (no start date) or `inverted_interval`. `PRIORITY_ORDERED` is true when an explicit `PRIORITY` already decides an overlap.

## Reading a re-rated call

Each row carries what was charged (`ACTUAL_CHARGE`), what the tariff says (`EXPECTED_CHARGE`, or the range `EXPECTED_LOW` to `EXPECTED_HIGH` when an ambiguous tariff allows several amounts), the tariff row used (`TARIFF_VERSION_ID`, and every candidate in `CANDIDATE_VERSIONS`) and a verdict. `FINDING` is empty when the charge matches. `leakage` means under-charged by `UNDERCHARGED_AMOUNT`; `overbilled` means over-billed by `OVERBILLED_AMOUNT`, a customer exposure and not recovered revenue; `data_quality` means the call cannot be judged (`no_tariff`) or its charge sits inside the range an ambiguous tariff allows (`ambiguous_tariff`). The two amounts never net against each other.

## Reading the causes and the root causes

Open `rerating_root_causes` first when you want to fix things: one row per cause, not per call. `ROOT_CAUSE_ID` names the cause, the plan and service, the tariff versions in force and the version the cause points at; it holds no date, so the same cause keeps its id tomorrow. `CALLS`, `FIRST_DAY`, `LAST_DAY` and `RUN_RATE_PER_DAY` say how big and how long; `LEAKAGE_LOW` is the revenue at risk and `OVERBILLED_AMOUNT` the customer exposure, never added together.

`rerating_findings` has the call behind each row. `CAUSE` is what a counterfactual re-rating is **consistent with**: `stale_version` (the charge equals the version that ended most recently), `lower_priority_rate` (a row that lost on `PRIORITY`) or `early_version` (the next version, billed early). `CAUSE_CONFIDENCE` is exactness times support; `CAUSE_ALTERNATIVE` lists every other cause that fits. `unexplained` (confidence 0) means nothing fits, or not enough of the same plan's findings agree. `LEAKAGE_HIGH` is the shortfall against the dearest candidate: for an ambiguous tariff it is exposure the tariff might justify, not a claim. The KPIs `revenue_at_risk` and `overbilled_amount` sum the two amounts over this Dataset.

## What it will not tell you

A mismatch shows a charge differs from the tariff **as written**; a cause is a hypothesis the numbers are consistent with, and nothing says the table is right. The wrong plan, the rate-structure causes, the Alert Rule and the comparison with the invoice come in later slices. Billing increments, minimum charges, zones, time bands, tax and allowances are not modelled yet: a plan that uses them will show differences that are not defects.

## Verify

- `GET /api/v1/jobs` lists `rr_tariff_hygiene`, `rr_rerate`, `rr_explain` and `rr_root_cause`; `POST /api/v1/jobs/rr_tariff_hygiene/trigger` runs the chain now.
- Query `rerated_usage` for `FINDING IS NOT NULL`; query `rerating_tariff_findings` for the table's defects; query `rerating_root_causes` ordered by `LEAKAGE_LOW`.

## Tuning

`tolerance` (absolute, default 0.02) is a parameter of `rr_rerate` and of `rr_explain`; keep the two equal. `within_tolerance_confidence` (0.9) and `confidence_floor` (0.5) are parameters of `rr_explain` in `config/jobs/rr_explain_job.toon`. The shipped values are illustrative until set from your own rounding behaviour and your own tolerance for a weakly supported cause.
