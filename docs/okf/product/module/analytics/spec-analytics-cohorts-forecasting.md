---
type: Architecture
title: Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — Cohorts, ranking, motifs, forecasting and model kernels
description: Part 3 of 3 of Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts: Cohort retention and funnel; Change-point and cohort Steps; Holdout split; Ranking, sampling and pivot; Temporal motif matcher; Forecasting; Model kernels; References
tags: [analytics, statistics, baseline, time-series, scores, similarity, entity-grouping, cohort, change-point, modules, duckdb]
timestamp: 2026-10-18T00:00:00Z
item: module.analytics
audience: internal
status: current
verified: 2026-11-19 last-edit
code: platform/inspecto-analytics
---

# Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts — Cohorts, ranking, motifs, forecasting and model kernels

Part 3 of 3 of [Analytics Kernel — canonical statistics, the column profiler, time-series primitives, the baseline and score output contracts](spec-analytics.md) · previous: [Distribution Steps, score contract and time-series primitives](spec-analytics-distribution-time-series.md).

## Cohort retention and funnel

*ANALYTICS-CORE-1 C9, built 2026-10-18; decision D-AN13.* `com.gamma.analytics.cohort` — the primitives the Sales &
Marketing BI pack needs (acquisition cohorts, retention curves, a conversion funnel), each a JVM function and a DuckDB SQL
builder held equal by a parity test. Built on M5 (`TimeGrain` buckets on a stated clock) and on C1's as-of discipline (the
as-of is always stated, never "now"). Entities are compared as text; an event with a NULL entity or time is ignored.

**`Retention`** — `compute(events, Params)` / `sql(relation, entityColumn, timeColumn, Params)` →
`cohort, period, size, active, rate`. `Params(grain, zone, through, from, mode, maxPeriods)`: `grain` is HOUR or coarser;
`zone` null = the column's own clock (DATE / zone-less TIMESTAMP), else the zone a `TIMESTAMPTZ` is bucketed in (never
DuckDB's session zone); `through` is the as-of.
* Events after `through`'s bucket are dropped; an entity's **cohort** is the bucket of its earliest remaining event;
  **size** = the cohort's entities; the **period** of an event = whole buckets from its cohort's bucket (0 = the cohort's own).
  `from` leaves older cohorts out of the *result* only — dropping old events first would make old entities look new.
* **`EXACT`** (classic): active(c, k) = distinct entities of c with an event in period k. **`UNBOUNDED`** ("returned on or
  after"): … in period k *or later*; never rises with k, period 0 = size.
* A cell exists only when **observable** (bucket c + k not after `through`, and k ≤ `maxPeriods`, default 120): an observable
  cell with nobody active is a row with `active = 0`; a cell not yet observable is **absent, never 0**.
* Hourly periods count wall-clock hours (a repeated fall-back hour is one bucket).

**`Funnel`** — `compute(events, Params)` / `sql(relation, entityColumn, stepColumn, timeColumn, Params)` →
`step, name, entities, from_previous, from_first`. `Params(steps, within)`: 2..12 distinct step names; `within` an optional
whole-second window from the anchor. Per entity the chain is built **earliest-first**: t₁ = the earliest step-1 event (the
anchor); tᵢ = the earliest step-i event with time ≥ tᵢ₋₁ (the very same instant counts) and, with `within`, ≤ t₁ + within. An
entity *reached* step i when tᵢ exists. Events between steps are ignored; steps need not be consecutive; an event outside the
funnel's steps is ignored. ⚠ **The anchor is always the earliest step-1**: an entity whose first attempt is too old for
`within` but whose later attempt would fit is *not* rescued ("any attempt" would need every step-1 as an anchor) — say so to
whoever reads the funnel. `from_previous` is NULL for step 1 and where the previous step is empty; `from_first` is 1 for step 1
and NULL when step 1 is empty. The caller bounds the time range by filtering the relation.

**Steps** (2026-11-01): `transform.cohort.retention` and `transform.cohort.funnel` —
[Change-point and cohort Steps](#change-point-and-cohort-steps).

Tests: `CohortTest` (hand matrices for monthly EXACT and UNBOUNDED retention, `from` and `maxPeriods`, the zone moving an
event across a month, week and day periods; a hand funnel of 6 entities with ordering, same-instant, earliest-anchor and
within cases; input rules), `CohortDuckDbParityTest` (retention SQL = JVM over 400 seeded entities for 4 grains × 4 zones ×
2 modes across the Berlin and New York DST changes with a different session zone, and over a DATE column; funnel SQL = JVM
with no window, 48 h and 30 d).

## Change-point and cohort Steps

*`ANALYTICS-STEPS-TS-1`, built 2026-11-01.* The C6 and C9 kernels as Pipeline Steps, in the house pattern of the
forecast and distribution Steps: one grammar parsed in one place (`TimeSeriesStep.parseChangePoint`,
`CohortStep.parseRetention` / `parseFunnel` in `platform/inspecto-engine/.../pipeline/exec`), read by the run
(`RowShaper`), the recipe compiler (`RecipeCompiler` / `RecipeConverter`) and the save gate (`ConfigRoutes`, with the
output columns flowing on to later Steps), so all three refuse the same config with the same words. Registered in
`BuiltinNodeType` (flat home `steps:`, kinds `changepoint`, `retention`, `funnel`), `PipelineConfig.Step.KINDS`,
`NodeAttributes`, `ProcessorCatalog` (BI family, DELIVERED) and both UI contract JSONs. Keys, defaults and refusals per
Step: [step catalog](../elt/spec-step-catalog-analytics-steps.md#changepoint--transformtimeserieschangepoint--change-point-regime--drift-detector).

**Shape decisions (assistant-decided 2026-11-01, pending operator confirmation; the Sales & Marketing pack's plan is
still not in the repository):**
* **Two cohort Steps, not one.** Retention and funnel answer different questions over different inputs (a funnel needs
  a step column, a retention a grain and an as-of); one Step with a mode switch would carry keys that mean nothing in
  the other mode. Their output columns are the kernels' own, unrenamed.
* **The change-point keeps every row.** It adds the four flags to the inbound rows (joined back on key + order), so the
  actual, forecast and residual stay beside the flags; the kernel's own SQL returns only the series columns.
* **σ is stated, never estimated by the Step.** `sigma` (one number) or `sigma_column` (per series) — exactly one.
  Estimating σ inside the Step would pick a lag and a window for the author; `scaleOfDifferences` stays a kernel
  function a `sql` Step or a Job can call.
* **The data is checked before the kernel runs.** The kernel's SQL silently misreads a repeated order value, a NULL or
  zero σ, a NaN residual (a σ of 0 compares nothing as outside) or a text order column (`'10'` sorts before `'9'`), so
  the run refuses them first, with counts. A σ column that varies within a series is not refused: the kernel reads
  each row's own σ. Caps:
  10,000 points per series (the recursive CTE runs one iteration per point, as the forecast's) and the score
  contract's 200,000 series.
* No `group_by` on the cohort Steps: the kernels have none, and a per-segment matrix is a `route` before the Step.

Tests: `RowShaperChangeCohortTest` (engine: a spike vs a three-point regime change, per-series σ from a column, a CUSUM
drift on the 11th point after a warm-up, `rebase`, an exact and an unbounded monthly retention matrix, a funnel with and
without `within`, every config refusal and every data refusal — all against figures worked by hand; falsified with ten
mutants, each caught), `NodeConfigNameContractTest#changePointAndCohortAttributesSurviveTheSave` (every declared key
survives the editor's lift and lower), `StepConfigSaveFindingsTest` (save gate: refusals with and without a schema, the
flags flowing on, a summarising Step replacing the columns; falsified by disabling the change-point check and the
retention column flow — all three caught), `BuiltinNodeTypeFlatHomeTest` (27 chain kinds).

## Holdout split

*ANALYTICS-CORE-1 S5d, built 2026-10-17.* `com.gamma.analytics.split.HoldoutSplit` — train / validation / test for any
model, as JVM functions and DuckDB SQL that agree. Shares are held in **basis points** (1/10,000, a fraction rounded to
the nearest), so both forms cut with integer arithmetic.

**By time — `TimeSplit`.** No shuffle and no seed (deterministic by construction): over the distinct buckets, oldest
first, the newest are test, the ones before them validation, the rest train. `lastBuckets(v, t)` takes bucket counts;
`fractions(v, t)` takes shares of the bucket count, each **rounded down** (⌊N · bp / 10000⌋), so a share never exceeds
its fraction. Fails when no train bucket is left. SQL: `sql(relation, bucketColumn, splitColumn)` adds the role column
(`'train'`, `'validation'`, `'test'`) by `dense_rank` over the relation's distinct buckets — one global cut, not per
entity; a NULL bucket gets a NULL role; no train bucket fails the statement through `error()`.

**By entity — `HashSplit(seed, train, validation)`.** bucket(key) = `md5_number_lower(seed ':' key) mod 10000`;
bucket < train → train, < train + validation → validation, else test. Every row of an entity gets the same role; the
same seed never reshuffles, a new seed does. SQL: `bucketSql`, `roleSql` and `predicateSql(column, role)` over the key
read as `VARCHAR` (an integer key hashes as its decimal text; a NULL key has no role).

**Why MD5, not DuckDB's `hash()`.** `hash()` is DuckDB's internal hash, with no published definition and no promise to
stay the same across versions: the JVM cannot reproduce it, and an upgrade could move entities between roles.
`md5_number_lower` is the lower 64 bits of the MD5 of the UTF-8 bytes (digest bytes 8..15, little-endian, unsigned) —
a fixed algorithm the JVM computes exactly. **One definition, `Md5Bucket`** (`of(text, n)`, `sql(expr, n)`, `lower64`):
`HashSplit` and LA's index `BucketFunction` (`la-storage`) both delegate to it — before the cleanup each held its own
copy of the digest reading. `Md5BucketTest` and `BucketFunctionTest#goldenBuckets` pin figures computed outside Java;
neither caller's buckets moved.

### Tests (S5 b, c, d)

* `AccuracyTest` — hand figures for every measure, the zero-actual and a = p = 0 rules, input rules.
* `AccuracyDuckDbParityTest` — 60 seeded pair sets (zero actuals, bands) through `Accuracy` and `AccuracySql`, groups
  and NULL skipping, the inverted band failing both; agreement within 1e-9 × max(1, |value|).
* `BacktestTest` — a hand-computed naive backtest (origins, overall and per-step measures), the expanding copy, band
  coverage, determinism, every cap and forecast check.
* `SeasonalityDetectorTest` — hand ACF, weekly / hourly / monthly detection, noise, trend and constant giving no
  period, minimum cycles, highest-wins and the tie rule.
* `SeasonalityDuckDbParityTest` — `acfSql` per key against `acf` (seasonal, noise, trend, constant, short series, a
  NULL value) and `decide` over the SQL rows = `detect`, verdict for verdict.
* `HoldoutSplitTest`, `HoldoutSplitDuckDbParityTest` — golden MD5 buckets computed outside Java; shares within 2 % of
  the fractions; SQL bucket, role and predicates = JVM over 3,000+ keys (quotes, accents, CJK, emoji, mixed case, an
  integer key, NULL); the time split in both modes against `TimeSplit.role`, and its no-train failure in both forms.

## Ranking, sampling and pivot

*ANALYTICS-CORE-1 S4, built 2026-10-17.* Three Steps for BI, investigations and testing large Datasets. Only the sampler
needs kernel code — its order must be reproducible outside DuckDB; ranking is one window function and the pivot's
aggregates are the Measure grammar's, so both live in the engine's Step grammar (`RankStep`, `PivotStep`). Every Step:
[step-catalog](../elt/spec-step-catalog.md) (`rank`, `sample`, `pivot`).

### The sampler — `com.gamma.analytics.sample.Sampler`

Deterministic, seeded and stratified, as a JVM function and DuckDB SQL that keep the same rows.

**Key text.** A row's key values, in key order, each as `<UTF-8 byte length>:<text>` (the text as DuckDB reads the
value as `VARCHAR`), a NULL as `-`, concatenated. A present value starts with a digit and a NULL with `-`, and the
length fixes where each value ends, so **two different keys never share a key text** (`ab`,`c` ≠ `a`,`bc`; NULL ≠ `''`
≠ `'-'`). SQL: `keyTextSql` — `CASE WHEN c IS NULL THEN '-' ELSE strlen(c::VARCHAR)::VARCHAR || ':' || c::VARCHAR END`
per column, joined with `||`.

**Hash.** `md5_number_lower('<seed>:' || key text)` — the holdout split's MD5 reading (§ [Holdout split](#holdout-split);
`Md5Bucket.lower64`, the one MD5 implementation in the platform), an **unsigned** 64-bit number.

**Order and quota.** Within each group (`group_by`; NULLs form a group of their own), rows are ordered by hash
(unsigned), then key text (UTF-8 bytes — DuckDB's `VARCHAR` order), then the tie columns the caller passes (the Step
passes every inbound column, so rows still tied are identical). The first `quota(n)` rows of a group of `n` are kept:

| Mode | `quota(n)` |
|---|---|
| Fixed size `k` (1 … 1,000,000) | `min(k, n)` — exactly `k`, or the whole of a smaller group (reservoir semantics) |
| Fraction `f` → `bp` basis points (rounded to the nearest, ≥ 1) | `⌊(n · bp + 5000) / 10000⌋` — the group's share, half up: 0.4 → 0, 0.5 → 1, 1.5 → 2 |

**Why not `USING SAMPLE` / `random()`.** Neither is reproducible across runs, threads or DuckDB versions, and neither has
a JVM twin; and a reservoir in the textbook sense (one pass, a random replacement) depends on arrival order. Ordering by
a keyed hash gives the reservoir's *result* — `k` rows per group, each equally likely across seeds — with none of that.
Properties that follow: the sample does not depend on input order; a row keeps its place as long as its key does, so
adding rows can only push a sampled row out (fixed size), never reshuffle the rest; another seed gives another sample.

**SQL.** `Sampler.sql(spec, relation, groupBy, keyColumns, tieColumns, scratch)` — `row_number()` and `count(*)` over the
group, `WHERE rn <= quota`; the four working columns (`<scratch>k`, `h`, `rn`, `n`) are excluded, so the columns are
unchanged. The Step uses scratch `__sample_` and refuses an inbound column with that prefix.

### Rank and pivot — the rules the Steps state

* **Rank** (`RankStep`): `row_number` / `rank` / `dense_rank` over `order_by` then `tie_break`, each direction stated and
  **NULLs last in both directions**. The tie-break is mandatory: `row_number` then orders by every remaining inbound
  column ascending, NULLs last — two rows still tied are identical, so the output is the same in any arrival order.
  `rank` / `dense_rank` give tied rows one rank by definition; `top: N` then keeps every row at place N.
* **Pivot** (`PivotStep`): `MeasureCompiler.aggregateSql(measure) FILTER (WHERE CAST(pivot AS VARCHAR) = 'value')` per
  declared value, `GROUP BY` the keys. Values are matched as text, required, at most 200 — a dynamic pivot (columns
  decided by the data) is refused. No aggregate of its own: the grammar's (count, countDistinct, sum, avg, min, max,
  median, stddev, variance, p05 … p99).

### Tests

* `SamplerTest` — key text (injectivity cases, UTF-8 length), golden hashes computed with Python's `hashlib`, quotas
  (half up), refusals, lowest-hash selection per group, independence from input order, a new seed, stability when rows
  are added, a NULL group.
* `SamplerDuckDbParityTest` — 2,500 seeded rows (accents, CJK, emoji, quotes, `:` and `-` in values, NULLs in key parts
  and groups, an integer column): 6 specs × 3 keys (unique and not) × stratified or not, **SQL rows = JVM rows**, on a
  second run and over a shuffled copy of the table; key text SQL = JVM for every row; the working columns do not leak.
* Engine: `RowShaperRankTest`, `RowShaperSampleTest` (written rows = `Sampler.select`), `RowShaperPivotTest`,
  `PipelineJobRunnerTest#runsAFlatConfigsRankSampleAndPivotSteps`. The demo `07-steps/sample`'s expected rows were
  computed by a third implementation of the definition (Python) and matched the run.

## Temporal motif matcher

ANALYTICS-CORE-1 S6 (2026-10-17). `com.gamma.graph.TemporalMotifMatcher` finds ordered `fan-in` / `fan-out` stages over
timed directed legs: each stage needs at least `minBranches` distinct counterparties inside the earliest qualifying
window (`windowHours`), optionally strictly after the time the previous stage reached each frontier node and within
`maxGapHours`; the last stage may close back onto the origin layer (`closesToStart`, a round trip). Structuring,
layering and circular-financing rings are this shape. It returns each match's nodes, edges and layers and a
`truncated` flag (match `limit` reached, or the 500 000 leg-examination `WORK_BUDGET` spent).

* **Home: the graph library `platform/inspecto-graph`, not this module.** Its input is nodes and directed edges
  (legs), its output a subgraph, and it needs nothing but the JDK — the graph library's own rule (no `inspecto-*`
  dependency). This module is statistics over numeric vectors and SQL builders; putting a graph search here would
  have mixed the two and given LA no shorter path (la-core already depends on `inspecto-graph`). So the move changed
  no pom, no allow-list and no enforcer rule.
* **Neutral input.** `TemporalMotifMatcher.Stage` carries only the search: shape, breadth, window, ordering, gap,
  closure and `admitsNodes` (false = the caller's stage names a node kind its graph does not hold, so it matches
  nothing — legs are still counted against the budget). The caller applies any edge kind or value band before it
  hands over each stage's eligible legs.
* **Link Analysis keeps** `PatternQueryCompiler` (stage parsing, the 422 refusals, the necessary-condition SQL prune
  over the whole Dataset and key normalisation), its `Stage` (edge kind, node kind, threshold band), and the
  mapping `Stage.motif()` (every projected node is an `entity`); see
  [link-analysis §5](../la-core/architecture-la-core-index-http.md#5-graph-run-graph--la-core--la-storage).
* **Twin and tests.** It is a line-for-line port of `matchBranchingPattern`
  (`inspecto-ui/projects/link-analysis/src/graph/branching-pattern-engine.ts`); the TypeScript stays with Link
  Analysis. `TemporalMotifMatcherGoldenTest` (inspecto-graph) pins the exact output — written against the LA class
  and committed before the move, unchanged since; `PatternStageMotifTest` (la-core) pins the stage mapping;
  `ControlApiInvPatternTest` (geo-link) and `branching-parity.spec.ts` still assert the same
  `branching-parity.fixture.json`.

## Forecasting

*ANALYTICS-CORE-1 S5 (a) + (e), built 2026-10-17.* Package `com.gamma.analytics.forecast`. The `business-assurance`
Space Template's Holt-Winters SQL, lifted into a core function: a JVM kernel (`Forecaster`) for bounded series, its
DuckDB form (`ForecastSql`) for Datasets, and the Step `transform.timeseries.forecast`
([step-catalog](../elt/spec-step-catalog.md)). No training on the request path, no new dependency (W1–W3, W7):
both methods are closed-form recursions with **declared** parameters — nothing is fitted or searched, so the same
input gives the same numbers.

### The methods

Over ONE regular series `y(1) … y(n)` — one value per bucket, no gap, no empty value (bucket and fill it first with the
M5 resample) — with period `m` (buckets per season, ≥ 2). Every series needs **at least 2m values**; the first season
has no forecast.

| Method | One-step forecast | From the last bucket |
|---|---|---|
| `SEASONAL_NAIVE` | ŷ(t) = y(t − m) | ŷ(n+h) = y(n − m + (h − 1) mod m + 1) |
| `HOLT_WINTERS` (additive) | ŷ(t) = L(t−1) + B(t−1) + S(k), k = (t − 1) mod m + 1 | ŷ(n+h) = L(n) + h·B(n) + S((n + h − 1) mod m + 1) |

Holt-Winters updates, for t = m+1 … n: `L(t) = α(y(t) − S(k)) + (1 − α)(L(t−1) + B(t−1))`,
`B(t) = β(L(t) − L(t−1)) + (1 − β)B(t−1)`, `S(k) = γ(y(t) − L(t)) + (1 − γ)S(k)`. α, β, γ ∈ [0, 1], declared; the
defaults 0.25 / 0.02 / 0.15 are the template's.

**Initialisation rule** (the template's, kept so the two agree): `L(m)` = mean of the first season; `B(m)` = (mean of
the second season − mean of the first) / m; `S(i) = y(i) − L(m)` for i = 1…m. The state is anchored at the end of the
first season, so the first forecast is bucket m + 1.

### The prediction interval (S5 (e))

A normal approximation with a robust spread (`PredictionInterval`):

| Piece | Definition |
|---|---|
| sigma | `Stats.scaledMad` (1.4826 × MAD) of the in-sample one-step errors e(t) = y(t) − ŷ(t), t > m — one planted spike does not widen every band, as a root-mean-square would |
| z(level) | Φ⁻¹((1 + level) / 2) — Acklam's approximation, relative error < 1.2e-9 (1.96 at 0.95) |
| v(h) | horizon growth: Holt-Winters `1 + Σ_{j<h} c_j²`, `c_j = α(1 + jβ) + γ(1 − α)·[j mod m = 0]` (the ETS(A,A,A) form of the recursion); seasonal naive `⌊(h − 1)/m⌋ + 1`; 1 for a history bucket |
| band | ŷ ∓ z · sigma · √v(h) |

⚠ The template's band is different by design (sigma = scaled MAD of the 7-day differences / √2, fixed z = 4); it is
not compared. A perfectly regular history has sigma 0 and a band of no width.

### The output shape

`ForecastOutput` — long form, one row per series per bucket **per level**, so a reader filters `level` instead of
knowing column names: `keys…, <time column>, horizon (0 = history, 1…H ahead), actual (empty ahead), forecast,
level, lower, upper` (the last three empty on the first season). The JVM form is `Forecaster.Result.rows()`;
`lowerAhead(level)` / `upperAhead(level)` give the horizon band as arrays, so the S5 (b) backtest's
`(point, lower, upper)` forecast is a one-line adapter (`ahead()`, `lowerAhead(l)`, `upperAhead(l)`, with
`Spec.withHorizon(h)`).

**Why not the score output contract (M7).** That contract is entity-keyed — one row per entity per run, a 0–100 scale
and bands, `model_version`, an entity cap and an atomic `_latest` swap written by the engine's `ScoreOutputWriter`. A
forecast is indexed by time, has no 0–100 scale or band names, and as a Step its output flows through the Pipeline's
own sink. So the shape is a **sibling** contract in the kernel that **reuses** the score contract's column vocabulary
(`ScoreColumns.Column`: name, DuckDB type, Dataset role) and its default series cap (`EntityCap.DEFAULT`), and copies
nothing else. A future scored Prediction kind can still write through `ScoreOutputWriter`.

### The SQL form

`ForecastSql.select` forecasts every series of a relation in one query: Holt-Winters is a recursive CTE that advances
every series one bucket per step, carrying its seasonal terms as a list (the template's technique); seasonal naive is a
`lag`. sigma is DuckDB `mad` × 1.4826. The z multipliers and v(h) are computed in Java and inlined as literals — one
definition each. Ahead buckets are labelled `last + h × grain`, cast back to the time column's type (`DATE` or a
zone-less `TIMESTAMP`; a `TIMESTAMPTZ` must be resampled with a zone first). `ForecastSql.checkSql` counts what makes a
series unusable — off-grain or empty times, empty values, a missing or repeated bucket, the shortest and longest series
— and the Step refuses on it before forecasting. Caps (the Step): period ≤ 1,000, horizon ≤ 1,000, 10,000 buckets per
series (one recursion step per bucket), 200,000 series.

### Golden result against the template

`ForecastTemplateGoldenTest` runs the template view's SQL **as shipped** on its own synthetic corpus (182 days,
weekly season, a −400 spike planted on day 151). The template adds three guards to plain Holt-Winters — a day outside
its band updates the state with the forecast instead of the actual, three such days reset the level, a CUSUM bends the
trend. Results, tolerance 0.005 (the template rounds to 2 decimals):

* guards switched off (its `z` and CUSUM threshold set to 1e9 in the parameter row, nothing else changed): **equal on
  all 175 forecast days**;
* as shipped: **equal on every day up to and including day 151**, the first day the template flags; they differ from
  day 152 because the template's state was fed its forecast instead of the spike.

Decision: the kernel stays plain Holt-Winters (the definition every textbook and the S5 (b) backtest assume), and the
template is unchanged. The template's outlier replacement, regime reset and drift are anomaly-handling on top of the
forecast — candidates for change-point work (Could C6), not part of the forecast definition.

### Tests

`ForecasterTest` (hand figures for both methods, variance factors, z, rows, input rules), `ForecastSqlParityTest`
(JVM = DuckDB within 1e-9 × max(1, |value|) on three keyed series including the NULL key, both methods, an unkeyed
monthly TIMESTAMP series, and the check counts), `ForecastTemplateGoldenTest` (above); in the engine
`RowShaperTimeSeriesTest` (resample → forecast against the JVM kernel, the refusals), `PipelineJobRunnerTest#runsAFlatConfigsResampleAndForecastStepsAndWritesTheForecastWithItsBand`.

## Model kernels

*ANALYTICS-CORE-1 Could C2, C3, C4, built 2026-10-18 (decisions D-AN20…D-AN29 in the plan's §9).*
Package `com.gamma.analytics.model`. Hand-written, **no dependency** (D-AN8), **fit is offline / in a Job only (W7)** —
nothing here trains on the request path; only the scoring and assignment forms may run there. All three share:

* **Determinism.** Same rows in the same order + the same spec (seed included) = the same model, to the last bit.
  Randomness is `SeededRandom`, SplitMix64 written out (pinned by the published vector in `KMeansTest`), not the JDK's.
  Callers hand rows over **sorted by entity key**.
* **Bounded input fails closed (D-AD10).** `InputCap` uses `EntityCap`'s numbers (default 200,000, ceiling 2,000,000), passed
  in; past it `InputCap.Exceeded`, never a sampled subset. Inputs are finite numbers (NaN / infinity refused).
* **Cancellation.** `com.gamma.graph.RunControl.checkpoint()` once per sweep / Newton step / tree.

### k-means — `KMeans`, `KMeansSql` (C2)

Lloyd's iterations from a k-means++ start on **z-scored** inputs (the fit's mean and population σ; a constant column has
scale 1). Stops when no assignment changes or after `maxIterations` (default 100, ≤ 1,000); `Model.converged()` says
which. k ≤ 64, ≤ 50 columns. An empty cluster takes the point farthest from its own centre. **Clusters are numbered by
centroid** (ascending, lexicographic in standardised space), so label 0 is the same group however the start fell;
*naming* a cluster is the operator's step. The `Model` carries means, scales, centroids, sizes, inertia, iterations and the
seed. `KMeansSql.select(relation, columns, model, clusterColumn)` assigns the nearest stored centroid set-based (lowest
index on a tie; a NULL / NaN input gives a NULL cluster); `KMeansDuckDbParityTest` holds it equal to `Model.assign`.
**No Step (D-AN22):** a fit yields a stored model version, which a Step cannot own; adding one later is additive.

### Logistic regression — `LogisticRegression`, `LogisticSql` (C3)

Ridge-penalised **Newton / IRLS** from zero (so no random start): L2 `λ = 1` default on the standardised coefficients, intercept
free; stops on a largest step below 1e-8 or after 25 steps by default (≤ 100). Rows are split by `HoldoutSplit.HashSplit`
on the entity key (the S5d split: train / validation / test, the same entity always the same role). **Standardisation uses
the train rows only** — validation and test are scored with it, never inside it. Both classes must be in the train rows.
`Model` has the coefficients (standardised space), the train means / scales, and `Metrics` (log-loss, AUC from average
ranks, accuracy at 0.5) for each share. A score is `100·σ(b0 + Σ bᵢ·zᵢ)`; `contributions` returns the `bᵢ·zᵢ` terms and
`recompute(intercept, contributions)` rebuilds the score from them. `LogisticSql` is the scoring form (stable sigmoid;
NULL / NaN input gives NULL), equal to the JVM within 1e-12 (`LogisticDuckDbParityTest`). Fitting has no SQL form.

### Isolation forest — `IsolationForest`, and the Anomaly option (C4)

Backlog row: `ANOMALY-ML-SCORING-1` (competitive gap L).

The kernel: 100 trees (≤ 1,000) over samples of 256 (≤ 4,096) drawn without replacement, height limit ⌈log₂ ψ⌉, splits on a
feature chosen weight-proportionally among those that still vary, at a uniform point between the node's min and max.
`s = 2^(−E[h]/c(ψ))`; `Path.excess() = 2·max(0, s − 0.5)` in [0, 1]. `attribute(point, reference)` gives per-feature shares
**counterfactually** (the drop in excess when one feature takes its reference value, normalised to 1) — split-count credit
was built first and rejected because a split is chosen by chance, not by what isolates the point (D-AN27).

The Anomaly option (`features/inspecto-anomaly`, `AnomalyForestScorer`): a model with `method: isolation_forest`
(+ optional `forest: {trees, sampleSize, seed}`; default `robust_z`, nothing changes) re-combines the entities' per-feature
**deviations** — the shipped robust z with its self and peer baselines, direction and `zCap` — through the forest in place
of the weighted RMS. `raw` = the excess, `score = 100·raw`, bands from the model's own thresholds. The explanation keeps
every `FeatureResult` field; `contribution = share·raw` sums to `raw`, features are listed largest first (evidence follows
the top one), and `AnomalyForestScorer.recompute` rebuilds `{raw, score}` from the stored explanation alone. Output goes
through the M7 score-output contract unchanged (`period_start, score, band, raw, features, insufficient_count`). The entity cap
(`maxEntities`) fails the run before the forest is fitted. **No preview** (D-AN28: fitting on the request path). Fewer than 2
entities score 0. The UI model form authors `method` and, for the forest, `trees` / `sampleSize` / `seed` (D-AN29, 2026-10-20); the draft
preview box says "no preview for the isolation forest" rather than scoring with the robust method. The shipped AML pack
model uses the forest (`PackFraudAmlAnomalyPropertyTest`: planted account elevated, clear margin over the look-alike,
explained by its isolation shares).

### Tests

`KMeansTest`, `KMeansDuckDbParityTest`, `LogisticRegressionTest`, `LogisticDuckDbParityTest`, `IsolationForestTest` (determinism,
caps, cancellation, refusals); in the Anomaly feature `AnomalyForestScorerTest` over the golden corpus (default unchanged,
planted anomalies at the top, bit-for-bit repeat, recompute, cap, preview refusal, model grammar).

## References

* Plan and decisions D-AN1 … D-AN9 and D-AN20 … D-AN29: [`analytics-core-plan.md`](../../../../archived-documents/plans-archive/analytics-core-plan.md).
* The baselines' first consumer: [anomaly-scores](../anomaly/spec-anomaly-scores.md).
* The score kinds on the contract: [anomaly-scores](../anomaly/spec-anomaly-scores.md), [risk-scores](../scoring/spec-risk-scores.md).
* Module axes and manifests: [module-taxonomy](../../layer/platform/architecture-modules.md).
