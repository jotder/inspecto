# Analytics core — one home for data operations and statistics (plan, 2026-10-17)

> 🟢 **MUST ITEMS BUILT 2026-10-17** (M1–M10; M6 amended by D-AN9). As-built: `okf/backend/analytics-kernel.md`
> and `okf/backend/engine/db-layer.md` (one SQL building path). Duplication inventory 74 → 2 (only Jaccard and
> Jaro-Winkler left, Should item S1). Should / Could not started. Decisions D-AN1…D-AN9 taken by the agent under the
> operator's delegated authority, each with its reason (§7). Row: `ANALYTICS-CORE-1` in `docs/BACKLOG.md`.
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
| **S1** | **Similarity kit** | Jaro-Winkler, Levenshtein, token Jaccard, normalisation (case, accents, whitespace) — JVM and DuckDB's built-in string similarity functions, with parity tests; Step `quality.match.fuzzy`. Screening's name folding and blocking stay in Screening | Screening, data quality, LA, Mobile Money | S |
| **S2** | **Deterministic entity grouping** | Blocking keys + match conditions + union-find → resolution groups; Step `enrichment.entity.link`. Similarity can *propose* candidates but never asserts identity (D-M1) | LA, Screening, Case dedupe, Mobile Money agents/wallets | M |
| **S3** | **Distribution Steps** | `transform.stats.outlier` (IQR, robust z), `transform.data.binning` (equal-width, quantile), histogram, `transform.stats.correlation` (`corr`, `regr_*`) | Data quality, Fraud, BA | M |
| **S4** | **Ranking, sampling, pivot** | `transform.analytics.rank` (Top-N per group), `quality.sample.reservoir` (seeded, deterministic, stratified), `transform.matrix.pivot` | BI, investigations, testing large Datasets | S–M |
| **S5** | **Forecasting kernel** | Seasonal-naive and Holt-Winters additive with backtest, lifted from the `business-assurance` template SQL into a core function and Step `transform.timeseries.forecast`; the Predictive design's S4 builds on it (OD-11 stays open there) | BA pack, KPI bands, Predictive | M |
| **S6** | **Temporal motif matcher** | `BranchingPatternEngine` (fan-in / fan-out stages within a window) to the core; LA's `PatternQueryCompiler` stays | AML structuring, Mobile Money, Fraud | S |
| **S8** | **JVM-side aggregation to SQL** | `ObjectService.analytics`, `CaseOperations.evaluateCaseRule`, `ReportService` percentiles move to the store's SQL or the kernel | Case Management, Incidents, ops reports | S |
| **S7** | **Platform Service `analytics`** | The kernel's read-only functions granted to Jobs and packs; the Assistant's `anomaly_scan` / outlier tool reuses it instead of its own SQL | packs, Jobs, Assistant | S |

### Could — when demand is named

| # | Item | Note |
|---|---|---|
| C1 | Windowed and ratio Measures | `PREDICTIVE-WINDOWED-MEASURES-1`; needs D10/D11 revisited |
| C2 | Clustering kernel (k-means) | Predictive design's *Could*; segmentation |
| C3 | Hand-written logistic regression | Predictive *Could*; fitted churn |
| C4 | Isolation forest behind the Anomaly explanation shape | D-AD2 left room for it |
| C5 | Hour buckets and weekday×hour seasonality | parked D9 |
| C6 | Change-point / regime detection | generalise the BA template's regime-change SQL |
| C7 | Graph traversal in SQL via `USING KEY` recursion | only after DuckDB 2.0 adoption (loop-queries plan C1/C2) |
| C8 | Analytics function catalogue in the UI | id, parameters, cost hint, result kind — as LA's `Algorithm` enum does for graphs |
| C9 | Cohort, funnel and retention primitives | Sales & Marketing BI pack |

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

### 4a. Success measures (baseline → target)

| Measure | Today | Target |
|---|---|---|
| Private SQL quoting / literal helpers outside `SqlBuilder` | ~30 | 0 (recorded exemptions only) |
| Hand-built `read_*(` outside `SqlViews` (non-ingest) | ~29 files, split not yet counted | 0 |
| Median / percentile / z-score implementations | 4 / 4 / 3 | 1 each, with explicit modes |
| Column profilers | 3 | 1 |
| Time-grain truncation copies | 5 | 1 |
| Similarity implementations | 3 | 1 kit |
| Union-find implementations | 3 | 1 |
| JVM re-implementations of the Measure grammar | 1 (`WorkingSetMeasures`) | 0 |
| Score-output writers | 2 (Anomaly, Risk Score) | 1 contract |
| Duplication guard | none | in CI, ratchet only down |

## 5. Order

1. **A0 (M10, M1)** — the duplication guard in report-only mode (the baseline inventory), then the module,
   definitions, conformance and parity tests. Nothing calls it yet.
2. **A1 (M9, M2, M6, M4)** — one SQL building path, then consolidate the statistics duplicates behind golden
   tests, adopt the baseline contract, one profiler. The guard ratchets down after each step.
3. **A2 (M3, M5)** — Measure aggregates (Java + TS twin + contract JSON) and time-series primitives.
4. **A3 (M7, M8)** — score output contract; graph library move.
5. **Should** items as their consumers ask, S1/S2/S6 first (AML and Mobile Money use them).

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

## 7. Decisions — taken 2026-10-17 by the agent under the operator's delegated authority

The operator delegated these calls for this plan ("take the decisions for me in this context") and asked for a short
note on each. Any of them can be reversed by the operator.

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
