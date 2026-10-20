# Telecom re-rating (tariff validation)

> **Level 2 solution page.** Content pack: Space Template `telco-rerating`
> (`spaces/_templates/telco-rerating/template.toon`). **Slices R0 to R3 of `PACK-RERATING-1` are built: tariff versioning,
> tariff-table hygiene, ambiguity verdicts, causes with confidence (including a wrong plan, inferred from a subscription history), root-cause grouping and revenue at risk.** Rate structure, Alert Rules and the
> re-rated-versus-billed Reconciliation are later slices ([plan](../../../../superpower/rerating-tariff-validation-plan.md)).
> Checked against the template on 2026-10-21. This pack is an unbilled preview: commercial terms are not set (D-RR10).

## Business problem

The `telco-ra` starter re-rates each call with one SQL control. It picks a tariff row, flags a call when two rows match, and
reports one finding per call. It cannot tell a harmless duplicate row from a harmful one (twelve findings for one duplicate row
with the same rate), it judges the tariff only indirectly after a billing run, and it nets over-billing against leakage in one
signed figure. This pack re-rates independently of the rater and separates three questions: is the **tariff table** sound, what
should this call have cost, and how sure can the answer be.

## Data in

Three Pipelines with Schemas under `spaces/_templates/telco-rerating/config/`:

| Feed | Contents |
|---|---|
| `rated_usage` | one row per rated call or event: `XDR_ID, EVENT_TS, EVENT_DATE, SUBSCRIBER_ID, PLAN, SERVICE, USAGE_UNITS, CHARGE` (the same canonical Schema as `telco-ra`) |
| `tariff_version` | one row per version of a plan and service: `TARIFF_VERSION_ID, PLAN, SERVICE, RATE_PER_UNIT, EFFECTIVE_FROM, EFFECTIVE_TO` (blank = open), optional `PRIORITY` |
| `subscription_history` | one row per period a subscriber was assigned a plan: `SUBSCRIBER_ID, PLAN, FROM_TS` (inclusive), `TO_TS` (exclusive, blank = open) |

Versions live **in the file**. `reference.load: scd2` was measured in slice R0 and does not fit: it appends a version row per
changed key with `__valid_from` set to the ingest time and no end column, so it cannot carry an effective date in the past (see
plan section 12). `tariff_version` is therefore an ordinary feed, and the file carries the whole table each time. **`subscription_history` is decided the same way (D-RR8, slice R3):** the plan's recommendation was an `scd2` Reference, but a subscriber's plan period needs a `FROM_TS` and a `TO_TS` in the past, which `scd2` cannot store (ingest-time start, no end column), so it is an ordinary feed with the periods in the file. The Pipeline sets
`csv_settings.null_padding: true`, so a file without the optional `PRIORITY` column ingests with it NULL. Every Dataset ships a
zero-row schema seed under `spaces/_templates/telco-rerating/data/`, so the Jobs run on a fresh Space (an empty history simply infers nothing).

## What the pack sets up

Four `sql.template` Jobs, chained on a signal:

1. `rr_tariff_hygiene` (04:30 daily) judges the whole table and replaces the Dataset `rerating_tariff_findings`: `overlap_same_rate`,
   `overlap_different_rate` (with `PRIORITY_ORDERED`), `gap`, `negative_rate`, `missing_rate`, `missing_start`, `inverted_interval`. A zero rate is a
   free plan, not a defect.
2. `rr_rerate` (`on_signal: job.run.completed` when `rr_tariff_hygiene` succeeded; window `$yesterday`, `incremental` by
   `window_date`, `lookback: 1`, `retain: 31`) writes the Dataset `rerated_usage`, one row per call: the contract other packs read.

3. `rr_explain` (`on_signal` when `rr_rerate` succeeded; the same window and `incremental` settings) writes the Dataset `rerating_findings`: one row per finding of the day, 23 columns. A leakage or over-billing is tested against
   three counterfactual re-ratings, each a one-parameter change that must reproduce the charge within `tolerance`: `stale_version` (the most recently ended version), `lower_priority_rate` (a row in force that lost on
   `PRIORITY`) `early_version` (the next version) and `wrong_plan` (the plan the subscriber's own calls are billed at, when `subscription_history` assigns a different one: see below). `CAUSE_CONFIDENCE` is exactness (1, or `within_tolerance_confidence`) times support (the share of the same plan, service, candidate versions and finding that the same cause
   also explains; for `wrong_plan`, the share of the subscriber's calls the inferred plan explains); below `confidence_floor`, or with no fit, `unexplained` with confidence 0; other fitting causes stay in `CAUSE_ALTERNATIVE`. `LEAKAGE_LOW` (revenue at risk), `LEAKAGE_HIGH` and `OVERBILLED_AMOUNT` are separate columns.
4. `rr_root_cause` (`on_signal` when `rr_explain` succeeded) replaces the snapshot `rerating_root_causes`: one row per `ROOT_CAUSE_ID` (`CAUSE|FINDING|PLAN|SERVICE|CANDIDATE_VERSIONS|CAUSE_TARIFF_VERSION_ID`, no date)
   over every retained day, with calls, first and last day, the sums, `RUN_RATE_PER_DAY`, the weakest confidence and example keys.

Two KPIs, `revenue_at_risk` (`sum(LEAKAGE_LOW)`) and `overbilled_amount` (`sum(OVERBILLED_AMOUNT)`), read `rerating_findings`.

`rr_rerate` first cuts each plan and service into **segments** between consecutive tariff boundaries, so the candidate rows are
decided once per segment (a few hundred) and not once per call; each call then joins the one segment in force at its start.

| `RESOLUTION` | Meaning | Expected charge |
|---|---|---|
| `single` | one row in force | its amount |
| `agree` | several rows, the rounded amounts are equal | that amount |
| `precedence` | several amounts, exactly one row holds the highest non-blank `PRIORITY` | that row's amount |
| `disagree` | several amounts and no winner (a tie, or no `PRIORITY`) | none: the range `EXPECTED_LOW` to `EXPECTED_HIGH` |
| `none` | no usable row in force (a negative-rate row is never rated against) | none |

A charge more than `tolerance` (absolute, strict, default 0.02) below `EXPECTED_LOW` is `leakage` by the shortfall; above
`EXPECTED_HIGH` it is `overbilled` by the excess; inside the range of a `disagree` call it is `data_quality` (`ambiguous_tariff`),
explained and never leakage. `no_tariff` is `data_quality`; so is `unratable_call` (a call with a tariff but no charge or no units). `UNDERCHARGED_AMOUNT` and `OVERBILLED_AMOUNT` are separate non-negative
columns and are never netted (D-RR4).

## Decisions and seams worth knowing

- **Rated at the start.** A call is rated at `EVENT_TS`: `EFFECTIVE_FROM` inclusive, `EFFECTIVE_TO` exclusive; a call that starts
  before a rate change and runs past it is not flagged.
- **Precedence needs an explicit `PRIORITY` (D-RR3).** The plan's section 6.2 also named "latest `EFFECTIVE_FROM`" as a tie-break;
  D-RR3 as answered says precedence only with an explicit `PRIORITY`, and that is what is built. A blank counts as lowest and a tie
  has no winner.
- **A reversal** (negative units and charge) is re-rated like any call; the range ends are the least and greatest of the two extreme
  rates' amounts, so the order does not flip.
- **Hygiene reads only usable rows.** A row with no rate, or an interval that ends at or before its start, never takes part in an
  overlap or gap, and is reported on its own.
- **No zone arithmetic.** Both Jobs compare naive timestamps only, so the result does not depend on the host's session zone; the
  golden runs both statements under two zones.
- **Plan inference (R3, plan section 6.4).** Per subscriber and service over the day, for each plan the number of calls (with a charge and non-zero units) whose charge its rate, in the version in force at the call, reproduces within `tolerance`. The plan with the most is **inferred** when it is the only one at the top, explains at least `plan_share` (0.6) and there are at least `plan_min_rows` (3) calls. A `wrong_plan` is claimed for a finding when the history assigns the subscriber exactly one plan at the call's start (`FROM_TS` inclusive, `TO_TS` exclusive), that plan differs from the inferred one, and the inferred plan's rate in force at the call reproduces the charge. Confidence is exactness times that share (not the cohort of R2). Silent look-alikes: too few calls, two plans with the same rate, a share below the floor, no history, two plans assigned at once, and a subscriber whose assigned plan IS the inferred one (a plan change the rating label did not follow). The root cause is the plan pair (`wrong_plan|overbilled|PLAN_P1|DATA|P1-D-1|P2-D-1`), over every subscriber it touches. Inference reads the day's calls of the subscribers that have a finding; the other subscribers cost nothing.
- **Decided in R2:** a call on a negative-rate row has no usable tariff; a call with no charge or no units is `unratable_call`; `agree` outranks `precedence` (zero units); `PRIORITY_ORDERED` is a fact about one pair while `RESOLUTION` is the verdict
  for a call; the gap names the version that ends last, ties to the greater id (the tariff file's order changes nothing). Details and the equivalent clauses no mutant can claim: plan section 14.
- **Not built:** orphan-plan detection (needs the plan Reference), the stage columns of R4 (increments, minimum charge, rounding scope, zone, time band, tax) and their causes, allowances (R5), the Reconciliation, the
  Alert Rule by `ROOT_CAUSE_ID` and the dashboard (R6). `rr_explain` was measured at volume in R3 (admin guide).

## Verification

`TelcoRerateGoldenTest` (module `inspecto`, beside `BypassFeatureGoldenTest`) boots the template through `POST /spaces`, ingests the
seeded synthetic `RerateCorpus` through the template's own Pipelines, runs all four Jobs as authored and holds every contract column of
every call, every tariff finding, every finding of `rr_explain` and every root cause to an independent plain-Java oracle (and the planted causes to hand-declared ones); the findings to the exact planted set with every look-alike
silent; the degradation (no `PRIORITY` column), the tolerance parameter, zone independence, idempotent re-runs and the signal chain; and
a pass of 157 clause mutants that must each change the oracle's rows (57 must change a named row); the known unpinned clauses and limits are in the plan sections 13 and 14.

## Related

[user guide](user-guide.md) · [admin guide](admin-guide.md) · [features](features.md) · [plan](../../../../superpower/rerating-tariff-validation-plan.md) ·
the starter control it extends: [Telecom revenue assurance](../telco-ra/architecture.md).
