---
type: Concept
title: Step catalog — every Step kind, delivered and pending — Forecast to outlier — analytics Steps
description: Part 2 of 5 of Step catalog — every Step kind, delivered and pending: forecast → transform.timeseries.forecast — Time-series forecaster (Holt-Winters); changepoint → transform.timeseries.changepoint — Change-point, regime & drift detector; retention → transform.cohort.retention — Cohort retention matrix; funnel → transform.cohort.funnel — Ordered conversion funnel; similarity → …
tags: [pipeline-graph, steps, catalog, processors, palette, examples, roadmap]
timestamp: 2026-09-06T00:00:00Z
---

# Step catalog — every Step kind, delivered and pending — Forecast to outlier — analytics Steps

Part 2 of 5 of [Step catalog — every Step kind, delivered and pending](spec-step-catalog.md) · previous: [The two lanes, and collect to shift](spec-step-catalog-lanes-first-steps.md) · next: [Binning to sink — data, privacy and routing Steps](spec-step-catalog-data-steps.md).

## `forecast` → `transform.timeseries.forecast` — Time-series forecaster (Holt-Winters)

*Added 2026-10-17 (ANALYTICS-CORE-1 S5, [analytics-kernel § Forecasting](../analytics/spec-analytics.md#forecasting)).*

**Function.** A forecast per key of a regular series — every bucket of the history with its one-step-ahead forecast
and prediction band, then `horizon` buckets ahead — so a chart, a KPI band or an Alert Rule reads actual, forecast,
lower and upper side by side.

**Behaviour.** The SQL is the Analytics Kernel's (`ForecastSql`, the DuckDB form of `Forecaster`).
- **Methods:** `holt_winters` (default; additive level, trend and season, weights `alpha` / `beta` / `gamma`, defaults
  0.25 / 0.02 / 0.15 — the `business-assurance` template's) or `seasonal_naive` (the same bucket one season earlier).
  Deterministic: nothing is fitted or searched.
- **Band:** a normal approximation, sigma = 1.4826 × MAD of the in-sample one-step errors, widened with the horizon;
  one row per bucket **per level** (`levels`, default 0.95).
- **Output columns:** the keys, `time_column`, `horizon` (0 = history, 1…H ahead), `actual` (empty ahead),
  `forecast`, `level`, `lower`, `upper` (all three empty on the first season). Ahead buckets are labelled last + h
  grains, in the time column's own type.
- **The input must be regular** — run a `resample` first. Refused before forecasting: a time value not on the grain,
  an empty time, a missing or repeated bucket, an empty value, a series shorter than two seasons, a time column that is
  not a `DATE` / zone-less `TIMESTAMP`, a value column that is not a number. Caps: `period` ≤ 1,000, `horizon` ≤ 1,000,
  10,000 buckets per series, 200,000 series (the run fails past them).
- Identifiers are validated and quoted; the keys and the time column may not take an output column's name. Runs **at
  rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `time_column` | string | required | — | **Time column.** The DATE or TIMESTAMP bucket column of a regular series (a resample's output). |
| `grain` | select | required | — | **Time grain.** The bucket size. Weeks start on Monday. |
| `value_column` | string | required | — | **Value column.** The number forecast; an empty value is refused. |
| `group_by` | list | optional | — | **Series keys.** One forecast per key. |
| `method` | select | optional | `holt_winters` | **Method.** `holt_winters` or `seasonal_naive`. |
| `period` | number | required | — | **Season length.** Buckets per season (7 for a weekly pattern in daily data); every series needs two seasons. |
| `horizon` | number | required | — | **Buckets ahead.** |
| `levels` | list | optional | `0.95` | **Interval levels.** One lower/upper band (and row) per level. |
| `alpha` | number | advanced | `0.25` | **Level smoothing.** Holt-Winters only. |
| `beta` | number | advanced | `0.02` | **Trend smoothing.** Holt-Winters only. |
| `gamma` | number | advanced | `0.15` | **Season smoothing.** Holt-Winters only. |

**Example.** `examples/07-steps/forecast` — 28 days of sales resampled to days, then 7 days forecast with a 0.95 band.

## `changepoint` → `transform.timeseries.changepoint` — Change-point, regime & drift detector

*Added 2026-11-01 (ANALYTICS-STEPS-TS-1, [analytics-kernel § Change-point and regime detection](../analytics/spec-analytics.md#change-point-and-regime-detection)).*

**Function.** Flags where a series stops behaving as expected. It reads a **residual** (actual minus expected — a
forecast's error, a baseline or a peer median) and its scale σ, and every row gains four 0/1 flags: `outside_band` (a
breach that did not become a regime change), `regime_change` (K outside points in a row, raised once on the K-th),
`drift_change` (a slow lean found by a two-sided CUSUM over the inside points) and `warmup` (a leading point that carries
no flag).

**Behaviour.** The SQL is the Analytics Kernel's (`ChangePoint.sql`, held equal to `ChangePoint.detect` and to the
Business Assurance forecast template's own flags). Defaults are the template's: z 4, K 3, k 0.25, h 8.
- **σ** is either `sigma` (one number for every series) or `sigma_column` (a column, the same on every row of a
  series) — exactly one. A per-series σ is the caller's one-line aggregate, e.g. `ChangePoint.scaleOfDifferences`'s
  1.4826 × MAD of lagged differences ÷ √2.
- **Every inbound row is kept**, ordered by key then `order_column`; a row with an empty residual keeps its place with
  empty flags.
- **`rebase`**: for a model that does not re-level after a change, the new level reads as residual 0 afterwards;
  without it such a model raises a regime change every K points.
- Refused before detecting: a repeated `order_column` value among a series' rows with a residual, an empty order
  value on a row with a residual, a residual that is not a finite number, a σ that is not above 0, a residual or σ
  column that is not a number, an `order_column` that is not a number, `DATE` or `TIMESTAMP` (text sorts `10` before
  `9`), an inbound column already named like a flag (or `__cp_sigma`, which carries a numeric `sigma`). Caps: 10,000 points per series (the kernel recurses once per
  point), 200,000 series.
- Identifiers are validated and quoted. Runs **at rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `order_column` | string | required | — | **Order column.** Orders each series: one row per key per point, evenly spaced. |
| `residual_column` | string | required | — | **Residual column.** Actual minus expected; an empty residual gets no flag. |
| `sigma` | number | optional | — | **Residual scale.** One σ for every series; set this or `sigma_column`. |
| `sigma_column` | string | optional | — | **Sigma column.** Each series' own σ, constant within the series. |
| `group_by` | list | optional | — | **Series keys.** One series per key. |
| `z` | number | advanced | `4` | **Band width.** A residual beyond z·σ is outside the band. |
| `regime_run` | number | advanced | `3` | **Regime run.** Consecutive outside points that make one regime change. |
| `cusum_k` | number | advanced | `0.25` | **Drift allowance.** CUSUM slack in σ. |
| `cusum_h` | number | advanced | `8` | **Drift limit.** CUSUM decision limit in σ. |
| `warmup` | number | advanced | `0` | **Warm-up points.** Leading points that update the state but carry no flag. |
| `rebase` | boolean | advanced | `false` | **Re-level after a change.** |

## `retention` → `transform.cohort.retention` — Cohort retention matrix

*Added 2026-11-01 (ANALYTICS-STEPS-TS-1, [analytics-kernel § Cohort retention and funnel](../analytics/spec-analytics.md#cohort-retention-and-funnel)).*

**Function.** Summarising: one row per acquisition cohort per period — `cohort` (the bucket start, a `TIMESTAMP`),
`period` (0 = the cohort's own bucket), `size`, `active`, `rate` — as of a stated date.

**Behaviour.** The SQL is the Analytics Kernel's (`Retention.sql`, held equal to `Retention.compute`).
- An entity's cohort is the bucket of its earliest event up to `through`; events after `through`'s bucket are dropped.
  `from` leaves older cohorts out of the result only (their entities still count as not new).
- `mode: exact` (default) counts entities active in that period; `unbounded` counts those active in that period or
  later, so it never rises with the period.
- **A cell not yet observable is absent, never 0**; `max_periods` (default 120, at most 10,000) caps the periods.
- The clock is stated: a `DATE` or zone-less `TIMESTAMP` is bucketed on its own clock; a `TIMESTAMP WITH TIME ZONE`
  needs `timezone`. Grains hour..year. Entities compare as text; an empty entity or time is ignored.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `entity_column` | string | required | — | **Entity column.** Who is followed. |
| `time_column` | string | required | — | **Time column.** When each event happened. |
| `grain` | select | required | — | **Cohort grain.** hour, day, week (Monday), month, quarter or year. |
| `through` | string | required | — | **As of.** A date or date-time; never "now". |
| `timezone` | string | optional | — | **Time zone.** Only for a TIMESTAMP WITH TIME ZONE column. |
| `from` | string | optional | — | **First cohort.** |
| `mode` | select | optional | `exact` | **Active means.** `exact` or `unbounded`. |
| `max_periods` | number | advanced | `120` | **Most periods.** |

## `funnel` → `transform.cohort.funnel` — Ordered conversion funnel

*Added 2026-11-01 (ANALYTICS-STEPS-TS-1, [analytics-kernel § Cohort retention and funnel](../analytics/spec-analytics.md#cohort-retention-and-funnel)).*

**Function.** Summarising: one row per funnel step — `step` (1..n), `name`, `entities` that reached it, `from_previous`
and `from_first` (the conversion rates; empty where the base is 0, and `from_previous` empty for step 1).

**Behaviour.** The SQL is the Analytics Kernel's (`Funnel.sql`, held equal to `Funnel.compute`).
- Per entity the chain is built earliest-first: the anchor is its **earliest** step-1 event; each later step is the
  earliest event of that step at or after the previous one (the same instant counts), and with `within` no later than
  anchor + within. ⚠ A later step-1 attempt never replaces the anchor, so an entity whose first attempt is too old
  for `within` is not rescued by a later one.
- Events of other kinds between steps are ignored. Filter the rows first to bound the period.
- `time_column` must be a `TIMESTAMP` (with or without a zone). 2..12 distinct steps; `within` at most 36500d.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `entity_column` | string | required | — | **Entity column.** Who goes through the funnel. |
| `step_column` | string | required | — | **Step column.** What each event is. |
| `time_column` | string | required | — | **Time column.** When it happened. |
| `steps` | list | required | — | **Steps.** The step names, in funnel order. |
| `within` | string | optional | — | **Within.** `30s`, `90m`, `48h`, `30d`; blank = no limit. |

## `similarity` → `transform.similarity` — Similarity score

*Added 2026-10-17 (ANALYTICS-CORE-1 S1, catalog `quality.match.fuzzy`, [analytics-kernel § Similarity kit](../analytics/spec-analytics.md#similarity-kit)).*

**Function.** Every row gains a **0..1 score of how alike two of its text columns are** (a customer name and an
account name, a payee and a beneficiary) and, with a `threshold`, a **true/false candidate flag** for review.

**Behaviour.** The SQL is the Analytics Kernel's (`SimilaritySql`): DuckDB's built-in string functions, each with a JVM
twin the kernel's parity test holds equal bit for bit, so a score in the output can be recomputed outside DuckDB.
- **Methods.** `jaro_winkler` (default; `jaro_winkler_similarity`, prefix scale 0.1, prefix up to 4, boost above 0.7 —
  favours a shared beginning, good for names), `jaro`, `levenshtein` (`1 − edits / longer length`) and `token_jaccard`
  (shared words over all distinct words — word order does not matter).
- **Normalize first** (default on): accents stripped (canonical decomposition, marks removed), lower-cased, runs of
  spaces, tabs and line breaks collapsed to one space, ends trimmed — on both sides. No transliteration: `Müller` becomes
  `muller`, not `mueller`.
- ⚠ DuckDB's string functions count **UTF-8 bytes**: an accented letter left in place (normalize off) counts as two
  characters. Normalizing first removes the common case.
- A value that is empty (NULL) on either side gives an empty score and flag; a blank text scores 0. A non-text column is
  compared as its text.
- **Length cap — 1,000 characters per value** (`SimilarityStep.MAX_TEXT_LENGTH`, measured on the raw text before
  normalizing). Edit-distance and Jaro cost grow with the product of the two lengths, so a longer value **fails the run**
  with `transform.similarity: a value in '<left>' or '<right>' is longer than 1000 characters`, as the kernel's other caps
  fail closed. A NULL score was not used for it: NULL already means a side is empty, and would hide the refusal.
- ⛔ **A candidate, never an identity** (decision D-M1). The flag says a pair is worth a look. The Step never merges,
  links, drops or reorders rows, and nothing may read the flag as "the same entity".
- Identifiers are validated (`[A-Za-z_][A-Za-z0-9_]*`) and quoted; the added columns must not collide with an inbound
  column. Runs **at rest** like every `steps:` kind; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `left` | string | required | — | **Left column.** The first text of each pair. |
| `right` | string | required | — | **Right column.** The second text of each pair, from the same row. |
| `method` | select | optional | `jaro_winkler` | **Method.** `jaro_winkler` · `jaro` · `levenshtein` · `token_jaccard`. |
| `normalize` | boolean | optional | `true` | **Normalize first.** Strip accents, lower-case and collapse spaces on both sides before scoring. |
| `score_column` | string | optional | `similarity` | **Score column.** Receives the score, 0..1. |
| `threshold` | number | advanced | — | **Candidate threshold.** 0..1; when set, adds the candidate flag: score ≥ threshold. |
| `candidate_column` | string | advanced | `similarity_candidate` | **Candidate column.** Receives the flag; only with a threshold. |
| `keep_score` | boolean | advanced | `true` | **Keep the score.** Off keeps only the flag (needs a threshold). |

Refused at save, at compile and at run with the same words (`SimilarityStep.parse`): an unknown key, a missing or
non-identifier column, `left` = `right`, an unknown method, a threshold outside 0..1, `keep_score: false` with no
threshold, equal output names, and (against the declared schema) a column it lacks or an output that collides.

**Example.** `examples/07-steps/similarity` — 6 customer/account name pairs, Jaro-Winkler at 0.92: `José Müller` /
`Jose Muller` scores 1.0 (accents and case only); `Vladimir Putin` / `Putin, Vladimir` 0.43 (Jaro-Winkler reads order —
`token_jaccard` would not); `Anna Kowalska` / `Ann Kowalski` 0.90, scored but not flagged.

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperSimilarityTest`, `PipelineJobRunnerTest#runsAFlatConfigsSimilarityStepAndWritesScoresAndCandidates` · (3) lift/lower round trip
`NodeConfigNameContractTest#similarityAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks it through the
same `RowShaper` arm · (4) palette `addable` from the regenerated contract · (5) Base, every edition · (6)
`StepConfigSaveFindingsTest` (+3) · (7) `examples/07-steps/similarity`, run with `serve-example --demo --check-jobs`
(job SUCCESS, 6 rows as its `probes.txt` states).

## `rank` → `transform.analytics.rank` — Rank & Top-N pruner

*Added 2026-10-17 (ANALYTICS-CORE-1 S4, [analytics-kernel § Ranking, sampling and pivot](../analytics/spec-analytics.md#ranking-sampling-and-pivot)).*

**Function.** Ranks the rows of each group by a stated order — the top 3 calls per region, the largest invoice per
customer — and either **keeps the top N** or **adds the rank as a column**.

**Behaviour.** One DuckDB window function over the inbound rows (`RankStep`).
- **Methods.** `row_number` (default — 1, 2, 3: every row its own place), `rank` (1, 1, 3: ties share a place, a gap
  follows) and `dense_rank` (1, 1, 2: ties share, no gap).
- **The tie-break is mandatory and deterministic.** The window orders by `order_by`, then `tie_break`, each with its
  direction and **empty values last in both directions**. For `row_number` it then orders by **every remaining inbound
  column**, ascending, empty last — so two rows still tied are equal in every column, and either one gives the same
  output. The same input gives the same ranks in any arrival order. For `rank` / `dense_rank`, rows equal in `order_by`
  and `tie_break` share a rank by definition, so `top: N` keeps every row tied at place N (possibly more than N rows).
- `top` keeps rows ranked N or better in each group; `keep_rank: false` (needs `top`) drops the rank column.
- Identifiers are validated (`[A-Za-z_][A-Za-z0-9_]*`) and quoted; the rank column must not collide with an inbound
  column. Runs **at rest** like every `steps:` kind; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `order_by` | list | required | — | **Order by.** Columns that decide the rank, each `COL`, `COL asc` or `COL desc`. |
| `group_by` | list | optional | — | **Rank within.** The rank restarts per key. Blank: one ranking of all rows. |
| `method` | select | optional | `row_number` | **Method.** `row_number` · `rank` · `dense_rank`. |
| `top` | number | optional | — | **Keep top N.** 1 … 1,000,000; keep rows ranked N or better per group. |
| `rank_column` | string | optional | `rank` | **Rank column.** Receives the rank, from 1. |
| `tie_break` | list | advanced | — | **Tie-break.** Ordered after `order_by`, same spelling; must not repeat an `order_by` column. |
| `keep_rank` | boolean | advanced | `true` | **Keep the rank.** Off keeps only the top rows (needs `top`). |

Refused at save, at compile and at run with the same words (`RankStep.parse`): an unknown key, no `order_by`, an entry
that is not `COL [asc|desc]`, a column ordered twice, an unknown method, `top` outside 1 … 1,000,000, `keep_rank: false`
with no `top`, and (against the declared schema) a column it lacks or a rank column that collides.

**Example.** `examples/07-steps/rank` — 8 calls, the top 2 per region by amount: K02 and K03 tie at 40.00 and are
ranked 1 and 2 by `CALL_ID`; K07 has no amount and sorts last.

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperRankTest`, `PipelineJobRunnerTest#runsAFlatConfigsRankSampleAndPivotSteps` · (3) lift/lower round trip
`NodeConfigNameContractTest#rankAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks it through the same
`RowShaper` arm · (4) palette `addable` from the regenerated contract · (5) Base, every edition · (6)
`StepConfigSaveFindingsTest` (unknown column refused; the rank column flows on to a later filter) · (7)
`examples/07-steps/rank`, run with `serve-example --demo --check-jobs` (job SUCCESS, 5 rows as its `probes.txt` states).

## `sample` → `transform.sample` — Statistical & reservoir sampler

*Added 2026-10-17 (ANALYTICS-CORE-1 S4, catalog `quality.sample.reservoir`, [analytics-kernel § Ranking, sampling and pivot](../analytics/spec-analytics.md#ranking-sampling-and-pivot)).*

**Function.** Keeps a **fixed number** or a **share** of the rows of each group — a test extract of a large Dataset, an
audit sample per region — and keeps the **same rows on every run**.

**Behaviour.** The SQL is the Analytics Kernel's (`Sampler`), with a JVM twin its parity test holds equal.
- **The order.** Each row's key values become a key text (each value length-prefixed, an empty value marked), hashed
  with the seed as `md5_number_lower('<seed>:' || key text)` — the holdout split's MD5 reading. Within each group rows
  are ordered by that hash, then by the key text, then by every inbound column; the first rows are kept.
- **`size: k`** keeps `k` rows per group, or every row of a smaller group (reservoir semantics). **`fraction: f`** keeps
  `⌊(n · bp + 5000) / 10000⌋` rows of a group of `n` (`bp` = `f` in basis points): each group's own share, rounded to
  the nearest row, half up — a stratified sample.
- ⛔ **Never DuckDB's `USING SAMPLE` or `random()`** — neither is reproducible across runs, threads or versions, and
  neither has a JVM twin. The same input, key and seed give the same rows; another seed gives another sample. A row
  keeps its place as long as its key does, so added rows can only push a sampled row out, never reshuffle the rest.
- `key` defaults to every inbound column (two rows equal in every column are interchangeable). Columns pass through
  unchanged. Runs **at rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `size` | number | optional | — | **Rows per group.** 1 … 1,000,000. Exactly one of this or `fraction`. |
| `fraction` | number | optional | — | **Fraction.** More than 0, at most 1, at least 0.0001 (one basis point). |
| `seed` | number | optional | `0` | **Seed.** Any whole number; the same seed picks the same rows. |
| `group_by` | list | optional | — | **Sample within.** Sample each key separately (stratified). Blank: one sample. |
| `key` | list | advanced | every column | **Row key.** The columns hashed with the seed. |

Refused at save, at compile and at run with the same words (`SampleStep.parse`): an unknown key, both or neither of
`size` / `fraction`, a size or fraction out of range, a seed that is not a whole number, and (against the declared
schema) a column it lacks; at run, an inbound column named with the sampler's reserved prefix `__sample_`.

**Example.** `examples/07-steps/sample` — 24 calls in three regions, `size: 3`, `seed: 42`, `key: [CALL_ID]`: 9 rows,
3 per region, the same 9 on every run (listed in its `probes.txt`, computed outside Java from the definition).

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperSampleTest` (written rows = the kernel's JVM
selection), `PipelineJobRunnerTest#runsAFlatConfigsRankSampleAndPivotSteps` · (3) `NodeConfigNameContractTest#sampleAttributesSurviveTheSave`,
`PipelineEditableTest`; the dry run walks the same `RowShaper` arm · (4) palette `addable` · (5) Base, every edition · (6)
`StepConfigSaveFindingsTest` (`size` + `fraction` refused even with no schema) · (7) `examples/07-steps/sample`, run with
`serve-example --demo --check-jobs` (job SUCCESS, the 9 rows its `probes.txt` names, identical on the second run).

## `pivot` → `transform.matrix.pivot` — Pivot (rows to columns)

*Added 2026-10-17 (ANALYTICS-CORE-1 S4, [analytics-kernel § Ranking, sampling and pivot](../analytics/spec-analytics.md#ranking-sampling-and-pivot)).*

**Function.** The reverse of `unpivot`: long rows become a wide table — one row per key, **one column per declared
value**, each holding one aggregate of that value's rows (usage per subscriber per call type, revenue per month column).

**Behaviour.** `PivotStep` writes `agg FILTER (WHERE CAST(pivot_column AS VARCHAR) = 'value')` per value, grouped by the
keys; the aggregate is the **Measure grammar's** (`MeasureCompiler.aggregateSql`: `count`, `countDistinct`, `sum`, `avg`,
`min`, `max`, `median`, `stddev`, `variance`, `p05` … `p99`) — no aggregate of its own.
- ⛔ **No dynamic pivot.** `values` is required and capped at **200**: a pivot over whatever values the data holds would
  make the output's columns depend on the data, which no schema, save check or later Step can judge.
- Values are matched **as text**. A row whose value is not listed, or is empty, adds to no column; its key still gets a
  row. A value with no rows gets 0 for `count` / `countDistinct`, empty otherwise.
- The column name is `prefix` + value and must be a plain column name (set `prefix` for a value such as `2024`); names
  must be distinct from each other and from the keys, ignoring case. `sum`, `avg` and the statistics need a numeric
  field (refused at run; at save a VARCHAR field fails open, as for every type check there).
- Runs **at rest**; not admitted mid-branch. The output's columns are the keys and the pivoted columns only.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `group_by` | list | optional | — | **Row keys.** One output row per key. Blank: one row. |
| `pivot_column` | string | required | — | **Pivot column.** Its values become columns; not also a key. |
| `values` | list | required | — | **Values.** 1 … 200 declared values, one column each. |
| `measure` | string | required | — | **Measure.** `count` or `agg(field)` in the Measure grammar. |
| `prefix` | string | optional | — | **Column prefix.** Put before each value to form its column name. |

Refused at save, at compile and at run with the same words (`PivotStep.parse`): an unknown key, no or too many
`values`, an empty value, a value that does not make a plain column name, duplicate names, a pivot column that is also a
key, a missing or unknown measure, and (against the declared schema) a column it lacks.

**Example.** `examples/07-steps/pivot` — 8 usage rows, `sum(AMOUNT)` per subscriber over `voice`, `sms`, `data`: 3 rows;
S2's `fax` call is in no listed value and adds to no column.

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperPivotTest`, `PipelineJobRunnerTest#runsAFlatConfigsRankSampleAndPivotSteps` · (3)
`NodeConfigNameContractTest#pivotAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks the same `RowShaper`
arm · (4) palette `addable` · (5) Base, every edition · (6) `StepConfigSaveFindingsTest` (a pivot without `values` refused
even with no schema; only the keys and pivoted columns flow on) · (7) `examples/07-steps/pivot`, run with
`serve-example --demo --check-jobs` (job SUCCESS, 3 rows as its `probes.txt` states).

## `entity_link` → `transform.entity.link` — Entity grouping

*Added 2026-10-17 (ANALYTICS-CORE-1 S2, catalog `enrichment.entity.link`, [analytics-kernel § Entity grouping](../analytics/spec-analytics.md#entity-grouping)).*

**Function.** Every row gains a **resolution group**: rows that agree exactly on a match key — the same email, the
same phone number — are the same entity, and the links chain (A shares an email with B, B a phone with C: one group).
The group id is the group's smallest key. Optionally, a **similarity review** flags look-alike rows of *other* groups
for a person to check.

**Behaviour.** The grouping is the Analytics Kernel's (`EntityGrouping`, run in DuckDB by `EntityGroupingSql`), whose
parity test holds the DuckDB result equal to a JVM union-find.
- **Match keys.** Each `match` entry is one way two rows are the same entity: one column, or several joined with `+`
  (`PHONE+LAST_NAME`: both must agree). Any entry is enough.
- **Normalising.** Before comparing, a column ignores accents, case and extra spaces (the similarity kit's
  normalisation); a column listed under `digits` keeps its digits only (`+44 20 7946-0001` = `442079460001`); one
  under `as_written` is compared as stored. A value that is **empty** — NULL, blank, or nothing left after
  normalising (`n/a` under `digits`) — **never matches**, so blanks cannot pull rows together.
- **Blocking.** With `blocking` columns, rows are compared only inside the same block (same value of every blocking
  column, normalised the same way). A row with an empty blocking value stays a group of its own.
- **Group id.** The key of the member whose key is smallest *as text* (`10` sorts before `9`), written in the key
  column's own type. It does not depend on row order.
- **Fails closed.** The run fails, naming the numbers and writing nothing, when the key is empty or repeated, when
  more rows than `max_block_size` share one match value or block (typically a placeholder such as `0` or `unknown`
  that would wrongly join them — filter it out first) or when the candidate pairs exceed `max_pairs`.
- ⛔ **Identity is never asserted by similarity** (decision D-M1). Match keys are exact. `candidate_by` scores a text
  column (the similarity kit: Jaro-Winkler by default) against the rows of **other** groups in the same block and
  adds `candidate_score`, `candidate_group` (the closest other group) and `candidate_review` (score ≥
  `candidate_threshold`). It never changes a group. A similarity knob offered as part of a match (`method`,
  `threshold`, `similarity`, `fuzzy`, `score`) is refused by name.
- Identifiers are validated (`[A-Za-z_][A-Za-z0-9_]*`) and quoted; the added columns must not collide with an inbound
  column. Runs **at rest** like every `steps:` kind; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `key` | string | required | — | **Record key.** A column that names each row once. A group's id is its smallest key (as text). |
| `match` | list | required | — | **Match keys.** One entry per way two rows are the same entity; join columns with `+`. |
| `blocking` | list | optional | — | **Blocking keys.** Rows are only compared inside the same block. |
| `digits` | list | optional | — | **Digits-only columns.** Compared on their digits only. |
| `as_written` | list | advanced | — | **Compare as written.** Compared exactly as stored. |
| `group_column` | string | optional | `resolution_group` | **Group column.** Receives the resolution group id. |
| `max_block_size` | number | advanced | `1000` | **Largest block.** 2..100,000; the run fails if more rows share one match value or block. |
| `max_pairs` | number | advanced | `1000000` | **Most candidate pairs.** 1..100,000,000. |
| `candidate_by` | string | advanced | — | **Review look-alikes by.** A text column scored against the other groups of the block; needs `blocking`. |
| `candidate_method` | select | advanced | `jaro_winkler` | **Review method.** `jaro_winkler` · `jaro` · `levenshtein` · `token_jaccard`. |
| `candidate_threshold` | number | advanced | — | **Review threshold.** 0..1, required with `candidate_by`. |
| `candidate_normalize` | boolean | advanced | `true` | **Normalize before review.** |

Refused at save, at compile and at run with the same words (`EntityLinkStep.parse`): an unknown key, a similarity
knob (D-M1), a missing `key` or `match`, a non-identifier column, a column named twice in one entry, a column in both
`digits` and `as_written` or listed there but used by no `blocking` / `match` entry, `candidate_by` without `blocking`
or `candidate_threshold`, a `candidate_*` key without `candidate_by`, a cap outside its range, and (against the
declared schema) a column it lacks or an output that collides.

**Example.** `examples/07-steps/entity-link` — 8 customers blocked by country, matched on email or phone (digits):
`C01` / `C02` share an email (case and spaces aside) and `C03` the phone digits of `C01`: one group `C01`; `C05`
has `C04`'s phone but another country: not joined; `Bob Stone` / `Bobby Stone` score 0.96 and are flagged for review,
yet stay two groups — 6 resolution groups from 8 rows.

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperEntityLinkTest`, `PipelineJobRunnerTest#runsAFlatConfigsEntityLinkStepAndWritesResolutionGroups` · (3) lift/lower round trip
`NodeConfigNameContractTest#entityLinkAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks it through the
same `RowShaper` arm · (4) palette `addable` from the regenerated contract · (5) Base, every edition (D-AN7) · (6)
`StepConfigSaveFindingsTest` (+3) · (7) `examples/07-steps/entity-link`, run with `serve-example --demo --check-jobs`
(job SUCCESS, 8 rows as its `probes.txt` states).**

## `outlier` → `transform.stats.outlier` — Outlier detector & IQR boxplot fencer

*Added 2026-10-17 (ANALYTICS-CORE-1 S3, [analytics-kernel § Distribution Steps](../analytics/spec-analytics.md#distribution-steps)).*

**Function.** Every row gains its group's outlier figures and a **true/false flag**: is this number unusual for its
own group (its region, its customer)?

**Behaviour.** The SQL is the Analytics Kernel's (`OutlierSql`), built on its `Stats` definitions.
- **`iqr`** (default) — Tukey's fences `Q1 − k·IQR` and `Q3 + k·IQR` (`k` 1.5; 3 = "far out"), the quartiles
  `quantile_cont` (`quantile_mode: continuous`, the kernel's default) or `quantile_disc` (`discrete`). Writes
  `<prefix>lower`, `<prefix>upper`, `<prefix>flag`; the flag is true when the value is **strictly** outside.
- **`robust_z`** — `(x − median) / max(1.4826 · MAD, max(unit, 5 % of |median|))`, the Anomaly Detection rule. Writes
  `<prefix>score`, `<prefix>flag`; the flag is true when `|score| > threshold` (default 3.5). `unit` (default 1) is the
  smallest change that matters — it keeps a near-constant group from flagging a tiny change.
- **Per group** — `group_by` columns; a NULL key is a group of its own; no `group_by` = the whole column.
- An **empty value** (NULL, or NaN in a floating column) gets no flag (and no score) but still sees its group's fences;
  a group with no value has no figures. A group of one value has fences equal to it: never flagged.
- The column must be a number (integer, DECIMAL, FLOAT, DOUBLE); refused otherwise. Every row is kept. A key of the
  other method (`k` with `robust_z`) is checked but has no effect. Runs **at rest**; not admitted mid-branch.

**Configuration.**

| Key | Type | Tier | Default | What it does |
|---|---|---|---|---|
| `column` | string | required | — | **Number column.** The number judged. |
| `method` | select | optional | `iqr` | **Method.** `iqr` · `robust_z`. |
| `group_by` | list | optional | — | **Groups.** Judge each value against its own group. |
| `k` | number | advanced | `1.5` | **Fence multiplier.** IQR only; 0–100. |
| `quantile_mode` | select | advanced | `continuous` | **Quartiles.** IQR only: `continuous` · `discrete`. |
| `threshold` | number | advanced | `3.5` | **Score threshold.** Robust z only; above 0. |
| `unit` | number | advanced | `1` | **Smallest meaningful change.** Robust z only; above 0. |
| `prefix` | string | advanced | `outlier_` | **Column prefix.** Names the added columns. |

Refused at save, at compile and at run with the same words (`DistributionStep.parseOutlier`): an unknown key, a
missing or non-identifier column, an unknown method or quantile mode, `k` outside 0–100, a threshold or unit not above
0, a prefix that does not make a plain column name, the column also grouped by, and (against the declared schema) a
column it lacks, an added column that collides, a text column.

**Example.** `examples/07-steps/outliers` — 14 payments in three regions: `P06` 960.00 is outside NORTH's fences
111.3125 … 157.8125, `P11` 15.00 outside SOUTH's 362.1875 … 435.6875; then binned (below).

**Release bar (2026-10-17): met.** (1) this section · (2) `RowShaperDistributionTest`,
`PipelineJobRunnerTest#runsAFlatConfigsOutlierAndBinningStepsAndWritesFlagsAndBins` · (3) lift/lower round trip
`NodeConfigNameContractTest#distributionAttributesSurviveTheSave`, `PipelineEditableTest`; the dry run walks it through
the same `RowShaper` arm · (4) palette `addable` from the regenerated contract · (5) Base, every edition (D-AN7) · (6)
`StepConfigSaveFindingsTest` · (7) `examples/07-steps/outliers`, run with `serve-example --demo --check-jobs` (job
SUCCESS, 14 rows as its `probes.txt` states).
