---
type: Feature
title: "Telecom re-rating (tariff validation) — user guide"
description: "How an analyst reads the tariff-hygiene findings and the re-rated usage in the Telecom re-rating pack."
tags: [telco-rerating, user-guide]
timestamp: 2026-10-20T00:00:00Z
---

# Telecom re-rating (tariff validation) — user guide

What a Space created from the `telco-rerating` template gives an analyst in slices R0 and R1. Design detail is in [architecture](architecture.md); the reading guide is `config/runbooks/telco-rerating-runbooks.md` in the Space.

## Data arriving

Copy the day's rated usage file (`RATED_USAGE_<yyyyMMdd>.csv`) into `data/inbox/rated_usage`. Copy the **whole** tariff table, one row per version, into `data/inbox/tariff_version` whenever it changes (`TARIFF_VERSION_<yyyyMMdd>.csv`).

## The daily cycle

| When | What runs | Output |
|---|---|---|
| 04:30 | `rr_tariff_hygiene` | the Dataset `rerating_tariff_findings`: one row per defect in the tariff table (replaced each run) |
| after it succeeds | `rr_rerate` | the Dataset `rerated_usage`: one row per call of the previous day (31 days kept) |

Run `rr_rerate` for another day with `POST /api/v1/jobs/rr_rerate/trigger`, passing `window_start`, `window_end` and `day`.

## Reading the tariff findings first

A defect in the table explains many call findings, so read `rerating_tariff_findings` before `rerated_usage`. `REASON` is `overlap_same_rate` (two rows in force at once, the same rate), `overlap_different_rate` (the same, different rates), `gap` (no row in force between `PERIOD_FROM` and `PERIOD_TO`), `negative_rate`, `missing_rate`, `missing_start` (no start date) or `inverted_interval`. `PRIORITY_ORDERED` is true when an explicit `PRIORITY` already decides an overlap.

## Reading a re-rated call

Each row carries what was charged (`ACTUAL_CHARGE`), what the tariff says (`EXPECTED_CHARGE`, or the range `EXPECTED_LOW` to `EXPECTED_HIGH` when an ambiguous tariff allows several amounts), the tariff row used (`TARIFF_VERSION_ID`, and every candidate in `CANDIDATE_VERSIONS`) and a verdict. `FINDING` is empty when the charge matches. `leakage` means under-charged by `UNDERCHARGED_AMOUNT`; `overbilled` means over-billed by `OVERBILLED_AMOUNT`, a customer exposure and not recovered revenue; `data_quality` means the call cannot be judged (`no_tariff`) or its charge sits inside the range an ambiguous tariff allows (`ambiguous_tariff`). The two amounts never net against each other.

## What it will not tell you

A mismatch shows a charge differs from the tariff **as written**; it does not say why, and it does not say the table is right. The cause, the root-cause grouping, the revenue at risk and the comparison with the invoice come in later slices. Billing increments, minimum charges, zones, time bands, tax and allowances are not modelled yet: a plan that uses them will show differences that are not defects.

## Verify

- `GET /api/v1/jobs` lists `rr_tariff_hygiene` and `rr_rerate`; `POST /api/v1/jobs/rr_tariff_hygiene/trigger` runs the chain now.
- Query `rerated_usage` for `FINDING IS NOT NULL`; query `rerating_tariff_findings` for the table's defects.

## Tuning

`tolerance` (absolute, default 0.02) is a parameter of `rr_rerate` in `config/jobs/rr_rerate_job.toon`; the shipped value is illustrative until set from your own rounding behaviour.
