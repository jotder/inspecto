# Analytics core — one home for data operations and statistics (plan, 2026-10-17; reviewed 2026-10-18)

> 🟢 **BUILT — every Must, every Should, and Could C1–C6, C8, C9** (2026-10-17 and 2026-10-18). Open: C7 (blocked on
> DuckDB 2.0) and the follow-ups in §10. Row: `ANALYTICS-CORE-1` in `docs/BACKLOG.md`. As-built:
> `okf/backend/analytics-kernel.md` and `okf/backend/engine/db-layer.md` (one SQL building path).
>
> | Batch | Built | Decisions |
> |---|---|---|
> | Must, 2026-10-17 | M1–M10: Analytics Kernel `platform/inspecto-analytics`, graph library `platform/inspecto-graph`, one SQL building path, statistical Measure aggregates, one column profiler, time-series primitives and Steps, baseline and score-output contracts, the ratcheting duplication guard (inventory 74 → 2 → 0 after S1) | D-AN1…D-AN9 (§7; M6 amended by D-AN9) |
> | Should, 2026-10-17 | S1 similarity kit + `transform.similarity`; S2 entity grouping + `transform.entity.link` (D-M1 enforced in the API); S3 `transform.stats.outlier` / `transform.data.binning` / `transform.stats.correlation`; S4 `transform.analytics.rank` / `transform.sample` / `transform.matrix.pivot`; S5 forecast kernel, prediction intervals, `transform.timeseries.forecast`, accuracy measures and backtest, seasonality detection, holdout split; S6 temporal motif matcher; S7 `analytics` Platform Service; S8 `ObjectService.analytics` rollup and the Case Rule window in the store | §7 |
> | Could lane A, 2026-10-18 | C5 hour-of-week slots and seasonal profile, C6 change-point kernel, C1 windowed and ratio Measures, C9 cohort retention and funnel (their Steps: `ANALYTICS-STEPS-TS-1`) | D-AN10…D-AN13 (§7) |
> | Could lane B, 2026-10-18 | Spearman (completes S3), C2 k-means, C3 logistic regression, C4 isolation forest as an Anomaly Model option | D-AN20…D-AN29 (§9) |
> | Could lane C, 2026-10-18 | C8 function catalogue (`GET /analytics/functions` + the Analytics functions pane); S8 Postgres goldens green on PostgreSQL 18; quote residuals; dead `Summarize*` classes deleted | D-AN30…D-AN35 (§7) |
>
> Every decision from D-AN10 on is **assistant-decided under the operator's delegation, PENDING OPERATOR CONFIRMATION**.
> As built (verifier notes): S5 (e) writes a time-indexed *sibling* of the M7 contract — it reuses the column
> vocabulary and the series cap but not the 0–100 scale or the `_latest` swap, because a forecast is keyed by time,
> not by entity; the Assistant's `anomaly_scan` uses the kernel's SQL forms (`StatsSql`) rather than the S7 Platform
> Service, since it is set-based SQL. D-AN1…D-AN9 were taken by the agent under the operator's delegated authority,
> each with its reason (§7).
>
> **Operator brief (2026-10-17).** Every application on the platform — Link Analysis, Revenue Assurance, Fraud,
> Case Management — is data-heavy. Generic data munching and statistics belong in the core, once; what only one
> domain needs stays in that domain's module. **The goal is to minimise duplicate data-operations code across
> modules** — the measure of success is how many copies are gone (§1a, §4a), not how many functions are added.

## 1. Where we are (measured 2026-10-17, read-only survey)

**The munging layer is solid; the statistics are scattered.** The Step catalogue has 119 entries (40 delivered,
18 partial, 61 planned). Delivered Steps cover cleanse/derive (`transform.record`), filter, lookup, join/enrich,
aggregate (`transform.summarize`), running windows, dedup, unpivot/explode, a basic per-column profile, guarded
free SQL (`transform.sql`), hash/mask and route. Every statistic beyond that lives inside one feature module or is
hand-written SQL:

| Capability today | Where | Problem |
|---|---|---|
| Robust stats (median, 1.4826·MAD, robust z), seasonal and peer baselines | `features/inspecto-anomaly` `baseline/` (`RobustStats` 36 LOC, `PeerBaseline` 83, `SeasonalBaseline` 54) | neutral maths locked in an add-on |
| Median | four implementations: `RobustStats`, `BaselineEvaluator.median` (averages the middle pair), `VolumeBaseline` (lower median, on purpose), DuckDB `median()` in LA `ValueMeasures` | different results for the same input |
| Percentile | Java nearest-rank (`ReportService`), SQL `quantile_cont` (`DbJobRunStore`), SQL `quantile_disc` (LA `DatasetLinkStats`, `IndexBuilder`), rank-percentile (LA link stats) | `_cont` and `_disc` disagree |
| z-score / outlier | robust (anomaly), mean/`stddev_pop` (Assistant `InspectoTools`), median-of-N (Expectation `baseline`) | three outlier definitions |
| Column profiling | `RowShaper.profile` (Step), `BaselineEvaluator` (adds null rate and mean), LA `ColumnProfile` / `EntityProfiles` (quantiles) | three profilers |
| "Baseline vs current" | Expectation `baseline`, `VolumeBaseline`, anomaly `BaselineStatistic` | no shared interface (only anomaly's is pluggable) |
| Time grain truncation | `FileSequenceGaps`, `DeliveryCheck` (identical), `KpiDefinition` period start, SQL `DATE_TRUNC` in `MeasureCompiler` and `RecordTransform` | five copies |
| Similarity | Jaro-Winkler (`screening/NameMatcher`), token Jaccard (`TriageRunSimilarity`, `GraphCentrality`), SQL Jaccard (LA `InvRoutes`) | no shared kit |
| Union-find / grouping | `EntityRegistry.find`, `GraphPaths.find`, `GraphAlgorithms.connectedComponents` | three copies |
| Graph algorithms (29: PageRank, betweenness, Louvain, k-core, cliques, link prediction, max-flow, propagated risk…) | `la/inspecto-la-graph` (~1,815 LOC, depends only on Jackson, no LA types) | neutral library filed under one domain |
| Temporal motif matcher (fan-in / fan-out stages in a window) | `la-core/BranchingPatternEngine` (227) | engine neutral, compiler LA |
| Score scaffolding (scores Dataset + `_latest`, bands, explanation JSON, entity cap, watch-list feed, `afterScore`) | copy-shaped in Anomaly and Risk Score | D-AD5 said "extracted, never copied" — not done |
| Forecast (Holt-Winters additive with band) | SQL in the `business-assurance` Space Template only | not a core function |
| Measure grammar | `MeasureCompiler` (+ TS twin `query-spec.ts`): only `count`, `countDistinct`, `sum`, `avg`, `min`, `max` | no median, percentile or spread — every statistic needs hand SQL |


### 1a. Duplicated data-operations code beyond statistics (counted 2026-10-17)

| Duplicate | Count today | Shared home that already exists |
|---|---|---|
| Private SQL identifier / literal quoting helpers (`q`, `ident`, `lit`, `literal`, `quoteIdent`…) | **~30** in ~25 files across 9 modules (engine 13, LA api/core 11, anomaly, scoring, reconciliation, expectation, DuckLake registrar…); a few `esc`/`quote` hits are HTML/XML escapers, not SQL | `SqlBuilder.quoteIdent` (`inspecto-util`) — underused; bound parameters used only in `ConditionSql` / `MeasureCompiler` |
| Hand-built `read_parquet(` / `read_csv(` outside `SqlViews` | **~29 files** across 10 modules (engine 12, etl 4, processor 3, LA storage 2…) — some are legitimate ingest readers | `SqlViews` — the recorded rule is "`SqlViews` owns the read OPTIONS; nobody concatenates their own `read_*(`" (`okf/backend/engine/db-layer.md`) |
| JVM re-implementation of the Measure grammar | `WorkingSetMeasures` (geo-link, 258 LOC) re-applies `count / sum / avg / min / max / countDistinct` in Java | `MeasureCompiler` |
| Aggregation done in the JVM where SQL could do it | `ObjectService.analytics`, `CaseOperations.evaluateCaseRule`, `ReportService` percentiles | the store's own SQL / the kernel |
| Score outputs written per kind | Anomaly and Risk Score each write history + `_latest`, bands, explanation | none yet (M7) |

Counts are a grep of method names (`q|ident|lit|literal|quote|quoteIdent|esc|escape|sqlString`) and of `read_parquet(` / `read_csv(`;
the A0 guard (M10) replaces them with an exact, maintained inventory.

**Recorded constraints this plan keeps** (do not re-litigate): compute is **DuckDB SQL or hand-written JVM
kernels**; the SQL sandbox loads no extensions (`SqlSandbox.seal()`, `SqlGuard` refuses `install/load/attach`);
**no new third-party analytics dependency** (predictive design OD-4/6/9; Screening SCR-D2 "dependency-free");
no external ML runtime, AutoML or notebooks (predictive S13, §7); determinism and recompute-from-explanation for
every score; fail closed on entity/series caps (D-AD10); identity is never asserted by similarity (D-M1);
Measures stay `{agg, field}` pairs (parked-rows D10) and Risk factors take no free arithmetic (D11).

## 2. The line between core and module

| Goes to the core (domain-neutral) | Stays in its module (domain-specific) |
|---|---|
| A statistic, distribution, time-series or similarity primitive with one canonical definition | Anything bound to a domain object: Investigations, the LA link index, Value Measures, dossiers |
| A graph algorithm over plain node/edge ids | LA's algorithm catalogue, cost ceilings, the `"kind · N"` edge-label weight parse, suspicion presets |
| The scaffolding every score kind repeats (outputs, bands, explanation shape, caps, feeds) | Each score kind's model spec and wording (Anomaly Model, Risk Score, future Predictions) |
| Column profiling, baselines, binning, sampling, ranking, resampling as Steps and functions | Reconciliation break lifecycle, Screening name folding and blocking, Case Rule windows |
| Deterministic entity grouping (blocking + union-find) | What counts as an identity fact (LA `identity.asserted`) |

**Test for a new capability:** if two or more modules would use it with the same definition, it is core; if its
definition only makes sense in one domain's vocabulary, it stays.

## 3. Target shape

- **Analytics Kernel** (name: D-AN1) — a new **Platform module** `platform/inspecto-analytics`, Base offering (every
  edition), depending only on foundation modules (`util`, `sql`). Pure functions plus SQL builders; no routes, no
  state. Each primitive has **one written definition**, a **JVM kernel** where data is already small and in
  memory, a **SQL form** where it runs over Datasets, and a **parity test** proving the two agree.
- **Graph library** (D-AN2) — `la/inspecto-la-graph` becomes `platform/inspecto-graph`, unchanged in content, so
  Fraud, AML, Case Management and entity grouping can use it without depending on Link Analysis. The TypeScript
  twins and their parity fixtures stay in the LA library; only the Java side moves.
- **Exposure, in order of reach:**
  1. **Measure grammar** — new aggregates reach BI, KPIs, Alert Rules, Risk factors and Anomaly inputs at once.
  2. **Step kinds** — the catalogue's planned statistics Steps, built on the kernel.
  3. **Java API** — for feature modules (Anomaly, Scoring, Reconciliation, Screening, LA).
  4. **Platform Service `analytics`** — read-only kernels for Jobs and packs (no dry-run stand-in needed).
- **Two execution tiers, one rule:** set-based work runs in DuckDB; JVM kernels run only on bounded, pre-aggregated
  input (one row per entity per bucket, as Anomaly already does) or for iterative algorithms (graphs), always with a
  declared cap and a cancel checkpoint (`RunControl`, today in `la-graph`).

## 4. MoSCoW

Sizes: S / M / L. "Consumers" names who gets it first.

### Must — the foundation; without it every module keeps its own maths

| # | Item | Scope | Consumers | Size |
|---|---|---|---|---|
| **M1** | **Canonical statistics + the Analytics Kernel module** | Module skeleton; one definition each for mean, variance / stddev (population and sample), median (explicit modes: standard, lower), quantile (continuous by default, D-AN3), MAD and robust z, IQR, rate and ratio; golden-value conformance tests; JVM↔DuckDB parity tests | Anomaly, Expectation `baseline`, `VolumeBaseline`, `ReportService`, Assistant outlier tool | M |
| **M2** | **Consolidate the duplicates behind M1** | Replace the four medians, four percentiles and three z-scores with kernel calls **keeping each caller's current numbers** (explicit modes, e.g. `VolumeBaseline` keeps the lower median); per-caller golden tests written *before* the swap | as above, plus LA link stats | M |
| **M3** | **Statistical Measure aggregates** | Add `median`, `stddev`, `variance` and fixed percentiles `p05 p25 p75 p90 p95 p99` (D-AN5 — keeps the `{agg, field}` pair shape of D10) to `MeasureCompiler`, the TS twin and the contract JSON | Studio, KPIs, Alert Rules, Risk factors, Anomaly inputs | M |
| **M4** | **One column profiler** | Count, nulls, distinct, min/max, mean, stddev, quantiles, top-k values and a histogram, in SQL; used by `transform.profile`, the Expectation `baseline`, Catalog column stats and LA `ColumnProfile` / `EntityProfiles` | Data quality, Catalog, LA entity profiles | M |
| **M5** | **Time-series primitives** | One grain-truncation definition (week start, timezone stated — DuckDB's session zone is the host's); resample with gap fill (zero for count/sum, as D-AD8); period-over-period shift; Steps `transform.timeseries.resample` and `transform.timeseries.shift`; the five truncation copies retired | KPIs, Anomaly, Reconciliation days, delivery checks, forecasting | M |
| **M6** | **Baseline contract** | Anomaly's `BaselineStatistic` promoted to the kernel (median-of-N, robust, seasonal, peer); Expectation `baseline` and `VolumeBaseline` implement it | Data quality, completeness KPIs, Anomaly | S |
| **M7** | **Score output contract** | The scaffolding Anomaly and Risk Score both hand-roll — history + `_latest` Datasets, 0–100 scale and bands, explanation JSON shape, recompute-from-explanation, entity cap that fails the run, watch-list feed, `afterScore` trigger — extracted once (fulfils D-AD5) | Risk Score, Anomaly, future Predictions | M |
| **M9** | **One SQL building path** | Every module builds identifiers, literals and relations through the shared builders: `SqlBuilder` (extended with bound-parameter helpers where values are user input) and `SqlViews` for every `read_*`; the ~30 private quoting helpers and the non-ingest hand-built readers retired; the Measure grammar evaluated only by `MeasureCompiler` (`WorkingSetMeasures` runs it over its rows instead of re-implementing it) | every module that writes SQL | M |
| **M10** | **Duplication guard** (fitness function) | `tools/check-data-ops-duplication.mjs`, report-only first, then a ratchet that only goes down: flags private SQL quote/literal helpers, hand-built `read_*(` outside `SqlViews`, and private statistics / similarity / union-find implementations outside the Analytics Kernel and graph library; exemptions only with a recorded reason (e.g. HTML/XML escapers, ingest readers) | all modules | S |
| **M8** | **Graph library in the platform** | Move `la-graph` to `platform/inspecto-graph`; merge its duplicated neighbour helpers; one union-find shared with `EntityRegistry`; `RunControl` generalised for every long kernel | LA, AML pack (rings), Fraud (collusion), Case Management (linked Incidents) | S–M |

### Should — high value once the Musts exist

| # | Item | Scope | Consumers | Size |
|---|---|---|---|---|
| **S1** | **Similarity kit** | Jaro-Winkler, Levenshtein, token Jaccard, normalisation (case, accents, whitespace) — JVM and DuckDB's built-in string similarity functions, with parity tests; Step `transform.similarity` (planned as `quality.match.fuzzy`). Screening's name folding and blocking stay in Screening | Screening, data quality, LA, Mobile Money | S |
| **S2** | **Deterministic entity grouping** | Blocking keys + match conditions + union-find → resolution groups; Step `transform.entity.link` (planned as `enrichment.entity.link`). Similarity can *propose* candidates but never asserts identity (D-M1) | LA, Screening, Case dedupe, Mobile Money agents/wallets | M |
| **S3** | **Distribution Steps** | `transform.stats.outlier` (IQR, robust z), `transform.data.binning` (equal-width, quantile), histogram, `transform.stats.correlation` (`corr`, `regr_*`; Spearman via `method`, D-AN20) | Data quality, Fraud, BA | M |
| **S4** | **Ranking, sampling, pivot** | `transform.analytics.rank` (Top-N per group), `transform.sample` (planned as `quality.sample.reservoir`; seeded, deterministic, stratified), `transform.matrix.pivot` | BI, investigations, testing large Datasets | S–M |
| **S5** | **Forecasting & Prediction base** (promoted 2026-10-17: the next item after the Musts) | (a) **Forecast kernel** — seasonal-naive and Holt-Winters additive, lifted from the `business-assurance` template SQL into a core function and Step `transform.timeseries.forecast`, built on the M5 primitives; (b) **Backtest + accuracy measures** — rolling-origin backtest and one definition each of MAE, MAPE (zero-actual rule stated), sMAPE, bias and coverage of the prediction interval; (c) **Seasonality detection** — period found from the data (autocorrelation over candidate periods, deterministic), so a declared period is optional; (d) **Deterministic holdout split** — seeded train / validation / test split by time or by entity hash, reusable by any model; (e) **Prediction-interval output shape** — point forecast plus lower / upper band and level, written through the M7 score/output contract so BI, KPI bands and Alert Rules read every forecast the same way. The Predictive design's S4 builds on it (OD-11 stays open there); no training on the request path, no new dependency (W1–W3, W7) | BA and RA packs, KPI bands, Anomaly (seasonality), Predictive | M–L |
| **S6** | **Temporal motif matcher** | `BranchingPatternEngine` (fan-in / fan-out stages within a window) to the core; LA's `PatternQueryCompiler` stays | AML structuring, Mobile Money, Fraud | S |
| **S8** | **JVM-side aggregation to SQL** | `ObjectService.analytics`, `CaseOperations.evaluateCaseRule`, `ReportService` percentiles move to the store's SQL or the kernel | Case Management, Incidents, ops reports | S |
| **S7** | **Platform Service `analytics`** | The kernel's read-only functions granted to Jobs and packs; the Assistant's `anomaly_scan` / outlier tool reuses it instead of its own SQL | packs, Jobs, Assistant | S |

### Could — when demand is named

Status: 🟢 built · ⏳ open · ⛔ blocked.

| # | Item | Status | Note |
|---|---|---|---|
| C1 | Windowed and ratio Measures | 🟢 2026-10-18 (D-AN12) | `PREDICTIVE-WINDOWED-MEASURES-1` closed; D10 and D11 revisited, both stand |
| C2 | Clustering kernel (k-means) | 🟢 2026-10-18 (D-AN22, D-AN23) | Predictive design's *Could*; segmentation. Kernel and SQL assignment form, no Step |
| C3 | Hand-written logistic regression | 🟢 2026-10-18 (D-AN25) | Predictive *Could*; fitted churn. Fit offline only (W7) |
| C4 | Isolation forest behind the Anomaly explanation shape | 🟢 2026-10-18 (D-AN26…D-AN28) | `ANOMALY-ML-SCORING-1`; UI form follow-up D-AN29 |
| C5 | Hour buckets and weekday×hour seasonality | 🟢 2026-10-18 (D-AN10) | parked D9 |
| C6 | Change-point / regime detection | 🟢 2026-10-18, kernel and SQL form (D-AN11); Step open | generalise the BA template's regime-change SQL; Step: `ANALYTICS-STEPS-TS-1` |
| C7 | Graph traversal in SQL via `USING KEY` recursion | ⛔ | only after DuckDB 2.0 adoption (loop-queries plan C1/C2) |
| C8 | Analytics function catalogue in the UI | 🟢 2026-10-18 (D-AN33…D-AN35); lane A and B kernels not yet registered (§10) | id, parameters, cost hint, result kind — as LA's `Algorithm` enum does for graphs |
| C9 | Cohort, funnel and retention primitives | 🟢 2026-10-18, kernel and SQL form (D-AN13); Step open | Sales & Marketing BI pack; Step: `ANALYTICS-STEPS-TS-1` |

### Won't — this horizon (each a recorded decision)

| # | Item | Why |
|---|---|---|
| W1 | External ML runtime, ONNX model import, AutoML, deep learning | predictive S13, OD-4/6/9 |
| W2 | Python / R runtime, notebooks, feature store, model-registry UI | predictive §7 |
| W3 | New analytics libraries (commons-math, Smile, Tribuo…) | dependency policy; reopen only by a recorded decision |
| W4 | DuckDB extensions in the sandbox (`spatial`, `vss`, `duckpgq`) | sandbox seal; `spatial` zero demand; `duckpgq` unavailable |
| W5 | Streaming / real-time analytics | `REALTIME-DECISIONING-1` is its own design |
| W6 | Identity asserted by similarity | D-M1 |
| W7 | Training on the request path, auto-retrain | predictive §4 / §7 |
| W8 | Moving domain logic into the core | LA index / Investigations / Value Measures, Reconciliation break lifecycle, Screening name folding, Case Rules stay where they are (§2) |

### 4a. Success measures (baseline → target → now)

| Measure | Baseline 2026-10-17 | Target | Now (2026-10-18) |
|---|---|---|---|
| Private SQL quoting / literal helpers outside `SqlBuilder` | ~30 | 0 (recorded exemptions only) | 0 by the guard; 57 exemptions recorded (55 before 2026-10-18) in `tools/data-ops-duplication.exemptions.json`, two of them (typed-literal formatters in `Retention`, `ChangePoint`) added 2026-10-18 because the shared builders have no inline typed-literal form. The guard sees only named helpers: hand-wrapped `"" + name + ""` scratch-name sites remain (§10) |
| Hand-built `read_*(` outside `SqlViews` (non-ingest) | ~29 files | 0 | 0 by the guard (the recorded exemptions are ingest and Collector-sample readers) |
| Median / percentile / z-score implementations | 4 / 4 / 3 | 1 each, with explicit modes | 1 each (M1, M2) |
| Column profilers | 3 | 1 | 1 (`ColumnProfiler`, M4) |
| Time-grain truncation copies | 5 | 1 | 1 (M5) |
| Similarity implementations | 3 | 1 kit | 1 kit (S1) |
| Union-find implementations | 3 | 1 | 1 (`com.gamma.graph.UnionFind`, M8) |
| JVM re-implementations of the Measure grammar | 1 (`WorkingSetMeasures`) | 0 | 0 by the guard (category d) |
| Score-output writers | 2 (Anomaly, Risk Score) | 1 contract | 1 contract (M7), plus the S5 (e) time-indexed sibling |
| Duplication guard | none | in CI, ratchet only down | in CI (`ci.yml`, `--ratchet`); inventory 0 |

⚠ The guard's inventory rose 0 → 2 when lane A merged (two private literal helpers) and the CI ratchet failed on `master` until the two reasoned exemptions above were recorded; the ratchet is the check that a Could batch did not quietly re-open the duplication this plan exists to remove.

## 5. Order (as executed — every step below is built; see the header)

1. **A0 (M10, M1)** — the duplication guard in report-only mode (the baseline inventory), then the module,
   definitions, conformance and parity tests. Nothing calls it yet.
2. **A1 (M9, M2, M6, M4)** — one SQL building path, then consolidate the statistics duplicates behind golden
   tests, adopt the baseline contract, one profiler. The guard ratchets down after each step.
3. **A2 (M3, M5)** — Measure aggregates (Java + TS twin + contract JSON) and time-series primitives.
4. **A3 (M7, M8)** — score output contract; graph library move.
5. **S5 Forecasting & Prediction base** next (operator, 2026-10-17): (a)+(e) first, then (b), (c), (d).
6. Other **Should** items as their consumers ask, S1/S2/S6 first (AML and Mobile Money use them).

## 6. Risks

- 🔴 **Silent number changes.** Consolidation must not move a single shipped figure: write each caller's golden
  test first, keep explicit modes (lower median, nearest-rank where a report relies on it), and change a definition
  only by a recorded decision (D-AN3 for LA's `_disc` quantiles).
- 🔴 **Twin drift.** The Measure grammar and graph algorithms have TypeScript twins held by parity fixtures; every
  M3 / M8 change lands in both in the same commit.
- 🔴 **Contract files.** New aggregates touch the Measure contract JSON and may touch `openapi-v1.json`; new Steps
  need the four registrations and the step-catalogue doc counts the doc-count guard checks.
- ⚠ **Timezone.** DuckDB's session zone is the host's, not UTC — M5 states the zone in every definition.
- ⚠ **Module guards.** A new platform module needs its `module.toon`, the module-deps allow-list and enforcer rule.

## 7. Decisions — taken 2026-10-17 and 2026-10-18 by the agent under the operator's delegated authority

The operator delegated these calls for this plan ("take the decisions for me in this context") and asked for a short
note on each. Any of them can be reversed by the operator. D-AN1…D-AN9 were taken 2026-10-17; D-AN10…D-AN13 (lane A) and
D-AN30…D-AN35 (lane C) on 2026-10-18 and are **pending operator confirmation**; D-AN20…D-AN29 (lane B) are in §9.
Numbers D-AN14…D-AN19 are unused (the ranges were split per lane to avoid collisions).

| # | Decision | Why |
|---|---|---|
| D-AN1 | **Analytics Kernel**, new Platform module `platform/inspecto-analytics`, Base in every edition | A library with no routes or state costs nothing to ship everywhere; a separate module (not the 63k-LOC engine) lets LA and feature modules depend on it without pulling in the engine. The name pairs with the existing *Decision Kernel* and avoids banned words (*Metric*, bare *Rule*) |
| D-AN2 | **Move `la-graph` to `platform/inspecto-graph`**; Java only, the TypeScript twins and parity fixtures stay with the LA library | It has no LA types and depends only on Jackson; AML, Fraud and Case Management need graph algorithms without buying Link Analysis. LA keeps its catalogue, cost ceilings and edge-label parse |
| D-AN3 | Canonical quantile is **continuous** (DuckDB `quantile_cont`); LA's link statistics **keep `quantile_disc` as an explicit mode** | Continuous is the DuckDB default and matches `DbJobRunStore`; keeping LA's mode means no shipped figure moves, which is the plan's first risk. Unifying LA can be a later, deliberate change |
| D-AN4 | Median has **explicit modes** (standard, lower) | `VolumeBaseline` uses the lower median on purpose; one definition with named modes removes the copies without changing anyone's numbers |
| D-AN5 | New Measure aggregates are **fixed names**: `median`, `stddev`, `variance`, `p05`, `p25`, `p75`, `p90`, `p95`, `p99` | Stays inside the recorded `{agg, field}` pair shape (parked-rows D10), so the Java/TypeScript twin and the contract JSON change additively; an arbitrary percentile parameter would reopen D10 for little gain |
| D-AN6 | The **score-output contract lives in the Analytics Kernel**, superseding D-AD5's "extract to the engine" | Anomaly, Risk Score and future Predictions are separate add-ons; the shared piece belongs in a module they all depend on, and D-AD5's intent (extract, never copy) is kept |
| D-AN7 | New statistics **Steps are Base**, every edition | They are generic munging, like the 40 delivered Steps; gating them would push editions back to hand-written SQL, the duplication this plan removes |
| D-AN8 | **No new dependency**: hand-written kernels plus DuckDB built-in functions | Keeps the recorded stance (predictive OD-4/6/9, Screening SCR-D2, sealed sandbox) and the SBOM / air-gap story; the statistics needed are small and well defined |
| D-AN9 | **M6 amended**: the baseline contract is adopted where its shape fits (Anomaly); the Expectation `baseline` and `VolumeBaseline` share the kernel *definitions* (median modes, column profiler) but not the `BaselineStatistic` contract | That contract takes a timestamped history and returns a robust median plus scaled MAD; the Expectation compares the plain median of ordered profiles and `VolumeBaseline` answers a signed deviation over calendar days. Forcing them in would invent timestamps or change outputs — the plan's first rule is that no shipped figure moves |
| D-AN10 | **C5 = the calendar slot and a profile over it, not new grains**: `HourOfWeek` (hour of day, weekday, weekday × hour; ISO Monday = 0; zone stated) and `SeasonalProfile` (slot means and a seasonal-strength share, JVM + SQL). `TimeGrain.HOUR`, its DST rules and the hourly candidates 24 and 168 of `SeasonalityDetector` already existed, so they are untouched. No Step. *(assistant-decided, PENDING OPERATOR CONFIRMATION)* | Hour buckets were built with M5; what was missing was a slot function and a way to ask "does a weekday × hour shape explain this series?" that needs no gap fill. Changing the detector's candidates would move a shipped number. A one-line SQL expression and a profile do not yet need a Step; the first consumer (`SeasonalBaseline` callers) will say what shape it wants |
| D-AN11 | **C6 = `ChangePoint` over a residual series, JVM + recursive-CTE SQL; the BA template is not rewired.** Template constants as defaults (z 4, K 3, k 0.25, h 8); an opt-in `rebase` for models that do not re-level themselves; σ stated by the caller (`scaleOfDifferences` = the template's estimate). The Step `transform.timeseries.changepoint` is deferred to `ANALYTICS-STEPS-TS-1`. *(assistant-decided, PENDING OPERATOR CONFIRMATION)* | The template's regime logic is one state machine shared with the forecaster's own re-levelling, so a literal lift is impossible; a function of residuals is what any model can feed. The golden is the template itself: the kernel reproduces its `outside_band`, `regime_change` and `drift_change` on all 175 days of five corpora (shipped, shift up and down, ramp up and down). Leaving the template alone keeps every shipped number. The Step needs a residual-producing Step first and the registration files were under edit by another lane the same day |
| D-AN12 | **C1: D10 and D11 both STAND; the windowed and ratio forms are additions to the *spec*, not to the Measure.** `window {field, asOf, days?}` is a row restriction over `(asOf − days, asOf]` applied to every measure and ratio; `recency` is the one window-relative aggregate and lives in its own list `AS_OF_AGGS`, **not** in `AGGS`; `ratios [{num, den}]` are `num / NULLIF(den, 0)` as DOUBLE with id `<num>_per_<den>`. Java `MeasureCompiler`, TS twin `query-spec.ts` and `measure-grammar.contract.json` change in one commit; `/bi/query` accepts both, and `BiResultMasking` vets them. *(assistant-decided, PENDING OPERATOR CONFIRMATION)* | The most additive option: a Measure is still an `{agg, field}` pair (D10), a Risk factor still takes no free arithmetic because a ratio is a fixed form with a fixed-vocabulary numerator and denominator, not a parsed expression (D11), no existing spec or call site changes, and the SQL of every existing spec is byte-identical. `recency` outside `AGGS` keeps summarize, KPI, Alert Rule and report from saving a Measure they cannot give an as-of — they would otherwise fail at run time. The as-of is mandatory (never "now") so a run is reproducible; dates are bound (`MEASURE-SQL-BIND-1`). Alternatives refused: a `window` per Measure (breaks the pair and the contract's shape), free `expr` Measures (reopens D11 and SQL injection review) |
| D-AN13 | **C9 = `Retention` and `Funnel` in `com.gamma.analytics.cohort`, JVM + SQL with parity; Steps deferred (`ANALYTICS-STEPS-TS-1`).** Retention: cohort = the bucket of the earliest event; `EXACT` and `UNBOUNDED` modes; only *observable* cells (an unobservable cell is absent, never 0); the as-of `through` always stated. Funnel: ordered chain built earliest-first from the earliest step-1 event, optional window from that anchor. *(assistant-decided, PENDING OPERATOR CONFIRMATION)* | The Sales & Marketing pack's plan is not in the repository, so the primitives follow the textbook definitions with every ambiguity written down: zero-filling unobservable cells would show a false drop-off for the newest cohorts; the earliest-anchor funnel is deterministic and cheap, at the cost of not rescuing a late retry (stated, so a reader is not misled). They are built on M5 (`TimeGrain`, zone) and C1's as-of discipline. The Step shape is best fixed against the pack |
| D-AN30 | **The uncalled `ParquetSummarizer`, `PartitionSummarizer` and `SummarizeSupport` (and `SummarizeSupportTest`) are deleted** — *assistant-decided, PENDING OPERATOR CONFIRMATION* | grep over every tracked file found no caller, no `main`, no `META-INF/services` entry and no config or script naming them; the only mentions were their own javadoc and two OKF lines. They had been moved to `inspecto-sql` on 2026-10-17 only to keep one `read_parquet(` option list, so deleting them removes a hand-built-read site rather than keeping a moved one alive. The `summarize` pipeline Step is a different, live thing and is untouched. Recoverable from git history if an out-of-tree caller turns up |
| D-AN31 | **S8's Postgres goldens are run, not just written**: `CaseRuleEvaluateGoldenTest` gains a PostgreSQL variant and `inspecto-case-management` gets the `postgresql` driver at TEST scope — *assistant-decided, PENDING OPERATOR CONFIRMATION* | `ObjectAnalyticsGoldenTest` already had a PG variant (skipped without a server); the Case Rule window had none, so the S8 claim was half-pinned. Both ran green against a throwaway `postgres:18` container (9/9 and 3/3, none skipped). The driver is the version-managed artifact `inspecto-ops` already uses at test scope, which is not transitive — no new library, no runtime change. Verified on PostgreSQL 18 only. `TEST-ENV-PG-ASN-UNRUN-1` stays open: the profile still has no standing Postgres and the `asn-*` surefire cache gap is untouched |
| D-AN32 | **Quote residuals: the sites `db-layer.md` named are moved onto `SqlIdent.q`** (`EnrichmentEngine` reference view names and `eventTimeSource`; `ConsignmentIngestStrategy` `dropTable`, `dropView`, member copy), pinned by `ConsignmentDropQuotingTest`; the many other hand-wrapped engine scratch names are listed on the `ANALYTICS-CORE-1` row instead of swept — *assistant-decided, PENDING OPERATOR CONFIRMATION* | `EnrichmentEngine.buildFilter` and `stampReferenceVersions` src / dst had already moved on 2026-10-17. A repo-wide sweep of every `\"" + name + "\"` is a different, larger change than the residual list asked for and touches ~30 sites across engine, etl, `inspecto` and la-api; SQL text for plain names is byte-identical, so nothing shipped moves, but each site wants its own look at whether its name is caller-controlled (la-api `PatternRoutes` columns are the one to check first) |
| D-AN33 | **C8 is registry-driven by `ServiceLoader`**: `AnalyticsFunctionProvider` in `com.gamma.analytics.catalogue`; this lane's `KernelFunctions` lists the S-tier kernels and a later kernel ships its OWN provider plus one line in `META-INF/services` — *assistant-decided, PENDING OPERATOR CONFIRMATION* | The house SPI idiom (ServiceLoader, no framework), and it means lanes A and B register functions in new files rather than editing mine — no merge conflict on a shared list. A duplicate id fails at load (`AnalyticsCatalogue`), and `ControlApiAnalyticsCatalogueTest` checks every named Step against `BuiltinNodeType`, so a provider cannot point at a Step that does not exist |
| D-AN34 | **C8 route is `GET /analytics/functions`, read-only and ungated, in the core; the graph family lists `graph.algorithms` (a pointer to `/inv/graph/algorithms`) and the temporal motif matcher, not the 29 graph algorithms** — *assistant-decided, PENDING OPERATOR CONFIRMATION* | It describes functions and runs none, carries no data, and has no write root, so the endpoint skill's 503 / 403 / 409 gates do not apply (an unknown `kernel` is 422). The 29 algorithms live in `la-core`'s `Algorithm`, above this module (D-AN1); copying them would create the second list the plan exists to remove, so the catalogue links to the LA route that already serves them |
| D-AN35 | **The cost hint is asymptotic (constant / linear / n log n / quadratic) with no per-function ceiling** — *assistant-decided, PENDING OPERATOR CONFIRMATION* | LA's graph ceilings come from measured node counts because the JVM runs the algorithm; the kernel mostly renders SQL the caller's own engine runs, so a ceiling would be a number nobody measured. The hint orders functions by how work grows; it is not a guarantee |

## 8. Grounding

Read-only survey 2026-10-17 by three parallel investigations (core inventory, domain-module analytics, prior
decisions), with the load-bearing facts re-checked: file sizes of `RobustStats`, `NameMatcher`, `BaselineEvaluator`,
`VolumeBaseline`, `ReportService`, `MeasureCompiler`, the `la-graph` classes and `EntityRegistry`; the six-aggregate
list at `MeasureCompiler.java:30`; `la-graph`'s only dependency (Jackson); the ML jars in `tools/dependencies.lock`
(ONNX / DJL, used by the Assistant only). Prior decisions: `predictive-analytics-design.md` §1, §4, §7, §9a;
`archived-documents/plans-archive/anomaly-detection-design.md` §17; `parked-rows-scoping-2026-10-12.md` §9a;
`okf/backend/control-plane/screening.md`; `okf/backend/modules/link-analysis.md`; `okf/backend/pipeline-graph/step-catalog.md`.
Not verified: DuckDB 1.5.6's exact list of built-in string-similarity functions (checked at S1), LOC of the
smaller helper classes.

## 9. Model-kernel lane — decisions D-AN20…D-AN29 (2026-10-18, assistant-decided, PENDING OPERATOR CONFIRMATION)

Taken by the assistant on the model-kernel lane (Spearman, C2, C3, C4) under the operator's standing delegation ("take
decisions smartly, with reasons"). None is confirmed by the operator yet; each can be reversed. As built:
`okf/backend/analytics-kernel.md` § Model kernels. Numbers D-AN10… belong to the time-series / Measures lane.

| # | Decision | Why |
|---|---|---|
| D-AN20 | **Spearman is a `method: pearson \| spearman` key on the existing `transform.stats.correlation` Step** (default `pearson`), not a second Step and not extra output columns. Ties take **average ranks**; `slope` / `intercept` / `r2` then describe the line through the ranks | The catalog entry is one "Pearson & Spearman" Step; a new column would change the shipped output shape and a second Step would split one concept. Spearman is *defined* as Pearson of the ranks, so the same SQL runs over rank columns and every figure follows. Average ranks are the textbook tie rule and are exact in a double (multiples of 0.5) |
| D-AN21 | **Model kernels draw randomness from a hand-written SplitMix64 (`SeededRandom`)**, not `java.util.Random` / `SplittableRandom` | "Same seed, same fit" must not depend on the JDK: `SplittableRandom` promises no algorithm across releases. Eight lines, pinned by the published test vector |
| D-AN22 | **k-means (C2) is a kernel with a SQL assignment form and no Step** | A fit produces a *model* (centroids), which the predictive design (§4.2) stores as a versioned artefact and a Pipeline Step cannot own; a Step that refit on every run would make a segment drift silently. The set-based assignment form (`KMeansSql`, nearest stored centroid) is what a Step or Job needs later, and adding a Step then is additive. Also keeps this lane out of the Step catalogue lane C is editing |
| D-AN23 | **k-means contract:** z-scored inputs (fit's own mean and population σ; σ = 0 → scale 1); k-means++ from the seed; stop on no assignment change or after **100 sweeps (1,000 at most)**; **k ≤ 64**, ≤ 50 columns; empty cluster takes the point farthest from its centre; **clusters numbered by centroid order**; callers sort rows by entity key | Each is the minimum that makes the fit reproducible and its output stable for a human to name. Numbering by centroid keeps "cluster 0" the same group however the start fell. No tolerance stop: an iteration cap and "no change" are exact and testable |
| D-AN24 | **Model kernels refuse, never sample, past a cap** — the same numbers as `EntityCap` (default 200,000, ceiling 2,000,000), passed in by the caller, failing with `InputCap.Exceeded` (D-AD10) | The kernels are in-memory and O(n·k) / O(n·d); an unbounded input is the failure mode the plan's §6 names. Reusing `EntityCap`'s numbers means a model's `maxEntities` means one thing everywhere |
| D-AN25 | **Logistic regression (C3): ridge-penalised Newton / IRLS, L2 default 1.0 on standardised coefficients (intercept free), ≤ 25 steps by default (100 at most), split by the S5(d) `HashSplit`, standardisation from the train rows only**; metrics (log-loss, AUC via average ranks, accuracy) reported for train, validation and test; scoring has a JVM and a SQL form, **fitting has none** | Newton has no start to draw, so the fit is deterministic with no seed; the ridge keeps separable data finite and the Hessian invertible; train-only standardisation is the leakage control the design (§4.3) requires. Fit is offline / in a Job only (W7); only the scoring side may run on a request path |
| D-AN26 | **Isolation forest (C4) is an Anomaly Model option, `method: isolation_forest` (+ optional `forest: {trees 100, sampleSize 256, seed 1}`), default `robust_z` unchanged.** It runs over the entities' per-feature *deviations* (the robust z, direction-aware, held to `zCap`, self and peer baselines included), so it is a multivariate re-combination of the same evidence, not a second feature pipeline. `raw` = the forest's excess over typical in [0, 1]; `score = 100·raw`; the model's thresholds read as before | Reusing the deviations keeps every per-feature baseline, peer cohort and masking rule that already shipped; only the combination step changes. The robust score is bit-for-bit unchanged when the option is off (test: explicit `robust_z` equals the default, golden corpus numbers still pinned). `model_version` hashes the content, so the seed and parameters are in it |
| D-AN27 | **Per-feature explanation is counterfactual, not split-count.** Each feature's share is how much of the entity's excess disappears if that feature alone had no deviation (clipped, normalised to sum 1); `contribution = share · raw`, so contributions sum to `raw` and the score is recomputed from the stored explanation alone (`AnomalyForestScorer.recompute`) | Crediting the features a path happens to split on was built first and **failed its own test** (an outlier on feature 1 got 49 % to feature 0: a split is chosen by chance, not by what isolates the point). The counterfactual names the feature that matters and is deterministic. Cost: up to d extra tree walks per entity with a deviation, bounded by the 16-feature and entity caps |
| D-AN28 | **An isolation-forest model has no preview** (`POST /anomaly-scores/preview` refuses it, 422); it is scored by the `anomaly.score` Job only | The forest is fitted over the whole population; fitting on a request thread breaks W7, and a one-entity preview has no population to isolate against |
| D-AN29 | **The Anomaly Model form in the UI does not yet author `method` / `forest`**; they are written as TOON (the save gate validates them) | The UI catalogue is another lane's area; the follow-up is filed in `ANOMALY-ML-SCORING-1`. Unverified here: whether the form round-trips an unknown key without dropping it |

## 10. Open items (2026-10-18)

Nothing here blocks the plan; each is a follow-up, in the order a shift should take them.

1. **Operator confirmation of D-AN10…D-AN35** (§7, §9), all assistant-decided. The ones most worth a read: D-AN12 (D10 and D11 stand; `window` and `ratios` are spec additions, `recency` lives outside `AGGS`), D-AN26 (the isolation forest reuses the per-feature deviations), D-AN30 (dead `Summarize*` classes deleted) and D-AN22 (k-means has no Step).
2. **Register the lane A and B kernels in the C8 catalogue.** Each ships its own `AnalyticsFunctionProvider` plus a `META-INF/services` line (D-AN33); today only the S-tier `KernelFunctions` is listed, so the catalogue does not yet show hour-of-week, change-point, windowed Measures, cohort / funnel, k-means, logistic regression or the isolation forest.
3. **`ANALYTICS-STEPS-TS-1`** — Steps for change-point and cohort / funnel (D-AN11, D-AN13); build against the Sales & Marketing pack's plan when it is scheduled. k-means and logistic regression deliberately have no Step (D-AN22, D-AN25).
4. **Anomaly Model form in the UI** does not author `method` / `forest` (D-AN29); check it round-trips the keys without dropping them. No golden-pack template uses the isolation forest yet.
5. **Hand-wrapped scratch-name sites** (`"" + name + ""`) the duplication guard cannot see, listed on the `ANALYTICS-CORE-1` row (D-AN32); look at `PatternRoutes` in la-api first, its columns come from the request.
6. **`PartitionCompactor` `union_by_name`** behaviour — operator-owned; not touched.
7. **C7** stays blocked on DuckDB 2.0 adoption.
8. **Verification gaps:** S8's Postgres goldens ran on PostgreSQL 18 only (`TEST-ENV-PG-ASN-UNRUN-1` stays open); the full reactor and the UI specs were not re-run on the merged tree; `check-dependencies.mjs` cannot run on this profile (asn modules excluded).
