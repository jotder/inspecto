---
type: Feature
title: "Telecom re-rating (tariff validation) — decisions and as-built record"
description: "Slices R0 to R3 of PACK-RERATING-1 as built: why there is no kernel, the answered decisions, gate G-K, the R0 answers, the corpus, the mutation passes, the known limits and the traps found."
tags: [telco-rerating, plan, as-built]
timestamp: 2026-10-22T00:00:00Z
---

# Telecom re-rating (tariff validation) — decisions and as-built record

> **What this page is.** The durable record of slices **R0 to R3** of `PACK-RERATING-1` (R0 to R2 built 2026-10-20, R3 2026-10-21),
> distilled from the original plan on 2026-10-22. How the pack works today is in [architecture](architecture.md) and
> [admin guide](admin-guide.md) (which holds the sizing figures). **Open work** (R4 to R7, D-RR10, the contract with partner
> settlement) lives in [`superpower/telco-packs-plan.md`](../../../../superpower/telco-packs-plan.md). Full history: the archived
> original [`rerating-tariff-validation-plan.md`](../../../../archived-documents/plans-archive/rerating-tariff-validation-plan.md)
> (section 12 the measurements, sections 13 to 15 the as-built narratives).

## Starting point (2026-10-18)

`telco-ra`'s `ra_rerating` (pinned by `TelcoRaGoldenTest` in `features/inspecto-reconciliation`: 5 rate mismatches, 12 ambiguous
tariff, 1 no tariff) picks the tariff row with `MIN(RATE_PER_UNIT)`, flags every duplicate row, reads `PLAN` from the rated record,
applies one flat rounding and one absolute tolerance, gives one finding per call with no cause, and sums `LEAKAGE_AMOUNT = EXPECTED -
CHARGE`, so **over-billing nets against leakage** (read from the SQL, not run). It stays as the starter.

## Why no kernel (D-RR2)

Re-rating is built as staged `sql.template` Jobs over Reference data, reusing the Reconciliation add-on for the re-rated-versus-billed
compare (slice R6). `ra_rerating` already expresses effective dating, ambiguity and tolerance in one statement; Expectations cover only
per-row tariff hygiene (an overlap is a relation, so it stays in SQL); Decision Rules are not a pricing engine. The planned
`transform.telecom.rating` Step Processor is a **producer** of charges and is deliberately not this: a validator's value is
independence from the rater. Tariff mathematics is telecom vocabulary, so a kernel would be a feature module with a Job Type, a
manifest and a parity test, paid for only if gate G-K trips.

**Gate G-K** (measured at R0, re-checked after R4; any clause opens slice R7, a SQL builder with a JVM twin and a parity test, the
`StatsSql` / `AccuracySql` pattern):

| Clause | State after R3 |
|---|---|
| (a) the stateful allowance stage cannot meet the budget (a 5M-row day through `rr_rerate` in 60 s on 4 threads, no spill, inside the default cap) | Not tripped: `rr_rerate` 25.4 s and 1.78 GiB at 5M rows, a harness cut of the allowance stage 2.8 s (no cycle roll-over, bundle priority or overage, which R5 adds) |
| (b) a prospect names a tariff structure outside the closed vocabulary | Not tripped |
| (c) a stage's SQL cannot be unit-tested in isolation | Not tripped for R1 to R3; re-check at R4 |
| (d) partner settlement needs the same arithmetic | Open by design (the cross-pack contract in the plan) |

## Decisions (D-RR1 to D-RR9, answered 2026-10-20: all as recommended)

| ID | As answered | As built |
|---|---|---|
| D-RR1 | New template `telco-rerating`; `ra_rerating` stays the starter | Built; `telco-ra` counts untouched. Reverse: move into `telco-ra` and re-pin its golden, solution page and `contents[]` together |
| D-RR2 | No kernel now, gate G-K | Above |
| D-RR3 | Conservative range when candidate charges differ; precedence only with an explicit `PRIORITY` | Built; the plan's "latest `EFFECTIVE_FROM`" tie-break was dropped to match the answer |
| D-RR4 | Over-billing is its own class, excluded from `revenue_at_risk` | Built. ⚠ It **changes the meaning** of the net `leakage_found` sum |
| D-RR5 | Confidence = exactness × support; defaults are configuration | Built (`within_tolerance_confidence` 0.9, `confidence_floor` 0.5) |
| D-RR6 | Incident unit = root cause | R6 builds the Alert Rule |
| D-RR7 | Allowances (R5) demand-gated | Not started |
| D-RR8 | `subscription_history` as an `scd2` Reference, `SUBSCRIBER_ID` `INTERNAL` | **Revised at R3: a plain feed** (below); `INTERNAL` stands |
| D-RR9 | Retail here, wholesale in partner settlement, one `rerated_usage` contract | Holds; the contract does not yet match what settlement needs (plan) |

D-RR10 (commercial and matrix cell C) is **open**: see the plan.

## R0: the four answers (2026-10-20)

1. **`load: scd2` keeps versions, not the history this pack needs.** An `upsert` / `scd2` Reference is an append-only version store:
   a changed key appends a row with `__valid_from` = the **ingest wall clock**, nothing closes the old row and there is no `valid_to`
   (`ReferenceReader`, `ConsignmentIngestStrategy.stampReferenceVersions`, `ReferenceScd2CharacterizationTest`); a Job reading the
   store sees every version row. So versions and periods live **in the file** (`tariff_version`, and at R3 `subscription_history`).
2. **Alert Rule `by: ROOT_CAUSE_ID` works as a plain column.** One Alert per Alert Rule, one Incident per key; above `stormCap`
   (default 100) one storm Incident; digests stop at 1,000. The probe groups the whole Dataset, so `ROOT_CAUSE_ID` carries no date; a
   persisting cause opens one Incident and **does not update it**; a cause that clears and returns opens a new one unless
   `healAfterSweeps` bridges it. A real tariff with hundreds of plans needs `stormCap` raised, never above 1,000.
3. **Throughput**: admin guide, "Sizing". The first per-call formulation of `rr_rerate` spilled at 5M rows (28.6 s, 3.17 GiB) and ran
   out of memory under a 1 GiB cap; cutting each plan and service into segments halved the peak. `ROUND(units × rate)` is monotone in
   the rate, so the range ends are the amounts at the least and greatest rate.
4. `TelcoRaGoldenTest` is in `features/inspecto-reconciliation` (the board text saying `inspecto` was wrong); the new pack's tests are
   in `inspecto`, beside `BypassFeatureGoldenTest`, because they boot the template through `ControlApi`.

## R1: versioning and ambiguity (commit `f2774acc4`)

`RerateCorpus` (seed 20261021, 533 usage rows, 46 tariff rows, all fictional), `RerateCorpusTest` (5), `TelcoRerateGoldenTest`. The
headline: the 12 calls on a duplicate same-rate tariff row, 12 `ambiguous_tariff` rows in the starter, are **0 call findings and 1
tariff finding**, while a wrong charge inside that group is still leakage. Under-charged and over-billed amounts are separate columns,
never both on one call (planted sums 10.26 and 4.26).

**Traps.**

- ⚠ **A seed at the root of a Hive-partitioned store breaks every read** (`Hive partition mismatch`): `rated_usage` drops
  `partitionKey` (flat per-day files; the Job filters on `EVENT_TS`, pruned by row-group statistics); the "no partitions[] or
  partitionKey" warning at ingest is expected.
- ⚠ A failed Job's message shows only the first line of a DuckDB error; the cause is on the next lines, in the surefire report.
- `getString` of a `TIMESTAMP` prints a trailing `.0` (read it as `LocalDateTime`); `max(column, 2)` returns the two highest non-NULL
  values as a list, so "exactly one highest `PRIORITY`" needs no window function.
- `rerated_usage` carries the call verdict (`REASON`, `FINDING`, the two amounts), so `rr_explain` reads `FINDING IS NOT NULL` and
  does not re-rate.

## R2: causes, root causes and revenue at risk

R1's open limits, decided: a negative-rate row is never rated against (`no_tariff`); a call with a tariff but no charge or units is
`unratable_call`; `PRIORITY_ORDERED` (a fact about one pair) and `RESOLUTION` (the call verdict) both stay and differ by design;
`agree` outranks `precedence` (zero units); the gap's other version is the row ending last, ties to the greater id. The five hygiene
clauses R1 left unpinned are pinned by corpus plans `PLAN_U`, `PLAN_UT`, `PLAN_KB`, `PLAN_W` and the zero-unit call.

Golden: 63 findings and 33 root causes equal the oracle; five stale calls form one root cause (confidence 0.8333 and 0.7500 with the
cohort's sixth call unexplained); a second day adds to the same `ROOT_CAUSE_ID`.

**Known limits, recorded not fixed.** Equivalent on the corpus, so unpinned: `PLAN` and `SERVICE` in the cohort partitions, the
winner test in `lower_priority_rate`, the id tie-break of the stale and early hypotheses, `ORDER BY RN` of the alternatives, the `>` of
the early boundary. Confidence, run rate and the `LEAKAGE_HIGH` division use `DOUBLE` before rounding (the oracle exact decimals): a
value at an exact half in the fifth decimal could round the other way. A call with no `USAGE_UNITS` and several candidate rows has an
unspecified `RESOLUTION`. Cohort support is per day, so a root cause's confidence is its weakest day. KPIs are asserted as
definitions, not evaluated through the KPI route. `rr_explain` was not measured at volume in R2.

## R3: plan inference and `wrong_plan`

**D-RR8 revised.** `subscription_history` is a plain feed (periods in the file, the whole history each time), for the R0 reason; not
a Stream, because a history is a dimension read whole. Cost: a real history is millions of rows delivered whole (unmeasured).

**Deviation.** The support of `wrong_plan` is the inferred plan's share of the **subscriber's** calls, not the plan-service-versions
cohort the other causes use (that cohort is the plan's whole population). `plan_min_rows` is added because 2 of 2 is 100 % and the
confidence floor alone would not refuse it.

Golden: 14 new subscribers (`SUB9001` to `SUB9014`, none shared, so no older finding moved), 123 findings, 39 root causes, all equal
to the extended oracle; three more runs under `plan_share` 0.8, `plan_min_rows` 2 and 6, and a floor of 0.7.

**Known limits.** Unpinned as equivalent: the `sub` cost clause, the service on the inference joins, the best-fitting row when the
inferred plan has several rows in force, the order of `wrong_plan` on a confidence tie. Inference is per day (a mid-day plan change is
one population; weekly or monthly inference is not built). The history is trusted (an inverted period is never in force and not
reported; no history hygiene Job). A wrong rating label (history says Q, rater labelled P) is `unexplained`; no `plan_label`
hypothesis.

## Mutation passes

R1: 46 `rr_rerate` and 27 `rr_tariff_hygiene` one-clause mutants, the unmutated statement first reproducing the Job's rows; a broad
pass, not every clause (review found five surviving hygiene mutants, pinned in R2). R2: 132 mutants over four statements, 32 naming
the row they must change. R3: 25 more on `rr_explain` (157 in all, 57 naming a row); two survived first drafts (a service condition
and the confidence floor masked them) and gave the corpus its MMS-rate and 3-of-6 cases.

## Deliberate deferrals after R3

Carried as open work in the plan: orphan-plan detection (needs a plan Reference) and a history hygiene Job; the hypotheses
`fractional_rate`, `rounding_mode` / `rounding_scope`, `increment_ignored`, `tax_basis`, `allowance_not_applied`, `plan_label`; R4 to
R7. Unmeasured: the Job over Parquet at volume (R3 measured in-memory tables only), findings over many subscribers, a real history,
tariff or volume.
