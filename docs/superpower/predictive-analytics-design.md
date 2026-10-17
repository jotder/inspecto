# Predictive Analytics add-on - design with options (`PREDICTIVE-ANALYTICS-1`)

**Status 2026-10-12: interview HELD - decisions in section 9a (OD-1..OD-9, OD-14, OD-17 decided; S0 done); no kernel built; P3 design-only.** Written by a docs-only lane
before any code. The key deliverable is section 9 (open decisions). Row: `PREDICTIVE-ANALYTICS-1` (P3) in
[BACKLOG](../BACKLOG.md); the later Sales & Marketing BI pack is `PACK-SALES-MARKETING-1`. How an add-on, an optional
module and an Offering are modelled: [`okf/backend/module-reorganisation-decisions.md`](../okf/backend/module-reorganisation-decisions.md)
and [`okf/backend/module-taxonomy.md`](../okf/backend/module-taxonomy.md). When this ships: distil into an OKF concept, move
leftovers to BACKLOG, `git mv` to `archived-documents/plans-archive/` (CLAUDE.md lifecycle).

> **Honesty box - what was and was not verified.** Verified by reading this checkout at `299becc07`: the Anomaly and Risk
> Score OKF concepts, the archived anomaly design (decisions D-AD1..D-AD12), `offerings/standard.toon`, the anomaly
> `module.toon`, the module list under `features/ la/ platform/ providers/ spi/`, `SqlSandbox`/`SqlGuard` headers,
> `tools/dependencies.lock` (it contains `onnxruntime 1.20.0` and `ai.djl` 0.36.0 `api` + `huggingface:tokenizers`),
> the embedded-intelligence concept, the DuckDB pin (`duckdb.version` 1.5.6.0). **Not verified** (named so nobody
> trusts them): the exact numerical behaviour of any DuckDB aggregate named below (no query was run; the machine was in
> use); that DuckDB 1.5.6 ships `regr_*` / `corr` / `quantile_cont` statically linked (believed, from DuckDB core docs,
> not checked here); whether `inspecto-intelligence` uses onnxruntime for anything beyond embeddings / RAG (the
> OKF says only that natives are pinned; it is NOT a general model runtime); the performance of any slice; and any
> Sales & Marketing pack content (none was found by grep). No existing doc, row or code mentions churn, forecasting or
> k-means: this is a greenfield design.

## 1. Truthful baseline - what the platform has and lacks

- **No ML runtime exists.** The one ML-shaped dependency is in `inspecto-intelligence` (onnxruntime + DJL tokenizers, for
  local embeddings of the Assistant's RAG corpus; licence texts and lock pins already handled). It is the AI Assist add-on,
  an LLM facility, **not** a training or scoring runtime for tabular models, and re-using it for churn would couple two
  add-ons. The Assistant / Intelligence agent explains and drafts; it computes nothing (its `anomaly_scan` tool is a
  deterministic z-score SQL).
- **The compute rule is DuckDB-only** for data work, with extension autoload disabled (`SqlSandbox.seal()` sets
  `autoload_known_extensions=false`; `SqlGuard` refuses `install`/`load`/`attach`/`pragma` and file/extension functions).
  So anything predictive must run on **core DuckDB SQL, window functions and the JVM**, not on a DuckDB community
  extension. DuckDB 2.0 adoption is decided-not-built (`duckdb-2-adoption-plan.md`); nothing here depends on it.
- **Two proven score patterns exist** and are the template: Risk Score (`risk.score`: absolute weighted factors) and
  Anomaly Score (`anomaly.score`: robust self / peer baselines). Both are an authored **component kind** in
  `registry/<kind>/<id>.toon`, scored by a **Job Type**, writing **history + `_latest` Datasets** under a reserved prefix
  with an ownership marker, fully **recomputable from the stored explanation**, with **masking at write time**,
  **maker-checker** through `PendingChanges.hold`, four route gates, a Studio pane + preview, a Signal, and an
  `afterScore` pending Alert Rule. Both are batch; real-time decisioning is a separate unbuilt row.
- **Measures / BI**: `MeasureCompiler` (count or `agg(field)` with filters, values bound since `MEASURE-SQL-BIND-1`) backs
  `/bi/query`, Risk factors and Anomaly Features. It has no window, ratio-of-aggregates or time-series grammar.
- **Vocabulary collisions to settle first** (GLOSSARY is binding): *Segment* already means "one record kind a plugin
  ingester emits", *Feature* is already "one Measure per entity per bucket" (Anomaly Model only), and *Model* is used by
  Anomaly Model, Risk Score model and *Model Settings* (AI). See OD-1.

## 2. What each capability means HERE, and the smallest honest first slice

All three follow the platform's grain: an **entity** (subscriber, customer, account, SKU, store) with **Measures** read
from **Datasets**, computed by a **Job**, stored with an **explanation**, thresholded into bands, optionally raising an Alert.

### 2.1 Segmentation (what group does this entity belong to?)
Meaning: assign every entity of a type to one named, stable group, so dashboards, Alert Rules and campaigns can address it.
- **Option A (recommended first): declared RFM** - recency / frequency / monetary Measures per entity, each cut into
  quantile bins (`ntile` / `quantile_cont` over the entity population), segment = lookup on the bin triple from an
  authored table ("Champions", "At risk"...). Pure SQL, deterministic, explainable, reuses Feature-style Measures.
- **Option B: declared condition segments** - ordered conditions on the condition tree (Decision Kernel); first match wins.
  Trivial, no statistics; arguably already possible as a Decision Rule tag - a baseline, not a new capability.
- **Option C: k-means** - small JVM implementation (Lloyd's, k-means++ seeded from a **declared seed**, standardised
  inputs, fixed iteration cap), centroids stored as the **model version**. Reproducible only because seed + input
  snapshot + ordering are pinned (section 4). Needs a "name the clusters" step (an operator labels them after inspecting
  centroids), or the output is meaningless numbers.
- **Option D: library (Smile/Tribuo class)** - dependency sign-off (OD-9).
Smallest honest slice: **A**, with B as a degenerate case, then C behind the same output shape.

### 2.2 Churn (how likely is this entity to leave?)
Meaning: a 0-100 likelihood per entity per run with per-input contributions. Needs a **label** (what "churned" means:
no activity for N days, or an explicit termination Dataset) - the platform has no label concept today.
- **Option A (recommended first): declared-coefficient logistic score** - the author declares inputs (Measures per entity,
  windowed), a transform each (cap / log / z against a stated mean and scale), a **coefficient**, and an intercept;
  `score = 100 * sigmoid(b0 + sum(bi * xi))`. Contributions are `bi * xi`, exactly recomputable, auditable, reviewable under
  maker-checker. Honest because **nothing is fitted by the platform**: coefficients are authored (from a data scientist's
  offline fit, or judgement), like Risk Score weights. It is a Risk Score with a sigmoid.
- **Option B: platform-fitted logistic regression** - the Job fits coefficients from a labelled training window (JVM
  IRLS / gradient descent with fixed iterations and seed), stores them as a versioned model artefact, then scores. Adds the
  training concern: label definition, train/test split, leakage (inputs computed as of the label cut-off), calibration, drift.
  Medium-large, and where most of the risk lives.
- **Option C: gradient-boosted trees / ONNX import** - external runtime or imported model file: dependency, licence,
  provenance and a trust problem (a model file is executable-ish content). Out of scope for v1 (OD-9).
Smallest honest slice: **A**, with a stored **backtest** (score vs a declared outcome Dataset over a past window: AUC /
lift table computed in SQL) so an author can judge a hand-authored model. B later, decided separately.

### 2.3 Demand forecasting (how much, per entity per bucket, next?)
Meaning: a forecast Measure per series (SKU x store, region, plan) per future bucket, with an interval.
- **Option A (recommended first): seasonal-naive + moving-average baseline** - forecast = same slot of the last season
  (weekday / week-of-year) averaged over N seasons; interval from the empirical residual spread of the same method on the
  history (backtest). SQL-native, deterministic, and the **benchmark any later method must beat**.
- **Option B: exponential smoothing (simple / Holt / Holt-Winters additive)** - a small JVM implementation with parameters
  either declared or picked by a fixed grid search on a hold-out (deterministic tie-break). Mature, explainable
  (level / trend / season components stored).
- **Option C: ARIMA / Prophet / neural** - library or external runtime; reject for v1.
Smallest honest slice: **A + B on one series grain, with backtest error (MAPE / sMAPE / MASE vs seasonal-naive) stored per
run**. Series must exist at a regular bucket grain; missing buckets are explicit (anomaly precedent D-AD8: 0 for count/sum).

## 3. Module shape and placement

**Recommendation: one new optional module `features/inspecto-predictive`** (id `predictive`, package `com.gamma.predictive`),
because it passes the D-MR9 five-test (distinct buyer: marketing / revenue / planning, not fraud analysts; base coherent
without it; separable; absence breaks no security promise; carries its own risk: statistical claims) and mirrors
`inspecto-anomaly`. Alternative (OD-2): fold into `inspecto-scoring` (smaller, but conflates "score an entity" with
"forecast a series", and the scoring module's Offering is the saleable *Scoring & Lists*).
- **SPIs used (all existing):** `JobTypeProvider` (`predict.segment`, `predict.churn`, `predict.forecast`),
  `ComponentKindValidator` (save-time gates), `RouteModule` (read + preview routes, feature id `predictive`), the
  `module.toon` manifest (`provides` features / configKinds / jobTypes / routes; `requires.modules` as needed).
- **Component kinds** (stored `registry/<kind>/<id>.toon` via `ComponentStore`, history/diff/restore/maker-checker for free):
  segmentation, churn and forecast models (names per OD-1). One kind with a `method:` discriminator is the alternative
  (fewer registries, but three schemas in one parser): OD-3.
- **Outputs, derived never authored, same discipline as anomaly:** `segments_<id>` / `_latest`, `churn_scores_<id>` /
  `_latest`, `forecasts_<id>` / `_latest`; a reserved prefix on every writer, an ownership marker, atomic `_latest` swap,
  rows carry `model_version`, `run_id`, `as_of`, `scored_at` and the explanation. The shared helpers already live in the
  engine (`ScoreOutputDirs`, `AnomalyScoreOutputs` naming): extend them, do not copy.
- **Routes:** `GET /predictions/{kind}/{model}/{entityKey}` (read), `POST /predictions/preview` (unsaved content;
  `canAuthorWorkbench` + save gates; `PREVIEW_POLICY` resource cap), forecast read by series key. Every new route must clear
  the **four gates** (route list and `openapi-v1.json` entry via the module's `openapi.fragment.json`, authgate literal,
  write-funnel inventory, module manifest) - the recorded trap for new routes.
- **UI:** Studio panes for the three kinds (list/detail/edit/History + preview), an entity prediction panel (the shape of
  `<inspecto-anomaly-panel>`), a forecast chart (`<inspecto-chart>`, already draws the anomaly series band). `navFeature:
  predictive` hides everything when the module is absent (503 / unknown kind = absent).
- **Editions / Offering:** add-on `predictiveAnalytics` in `offerings/standard.toon` (`status: planned` then `built`),
  so Professional, Enterprise, Preview; **not Personal** (as D-AD4), unless OD-5 chooses otherwise; needs the
  `tools/check-offerings.mjs` and bundle-module list entries anomaly had. Content ships as **Space Templates** (the unit of
  sale includes content): later `PACK-SALES-MARKETING-1` is a *function pack* (domain-neutral templates that `requires`
  `predictive` + BI) plus a retail industry pack - zero platform edits, by the binding pack rule.
- **Capability / four-eyes model:** authoring = `canAuthorWorkbench`, with `PendingChanges.hold` (maker-checker) on every
  save, as Risk/Anomaly; reading a prediction = the same gate as scores; **coefficients and seeds are config, so versioned and
  reviewable in the component history**. No new RBAC capability (P1-D3 keeps module-exclusive capabilities out until a
  module has many).

## 4. Determinism, reproducibility, masking, lineage, drift (binding for every slice)

1. **A prediction is a function of declared inputs.** Same model version + same input Datasets as of `as_of` + same seed =
   the same output. Pure maths lives in a `...Scorer` class with a `recompute` that re-derives score and band from the STORED
   explanation (tests assert equality), like `RiskScorer` / `AnomalyScorer`.
2. **Fitted models are artefacts, not hidden state.** Anything fitted (k-means centroids, fitted coefficients, smoothing
   parameters) is stored as a **model version row** in a history Dataset (`predict_models_<id>`: version, fitted_at, training
   window, row count, input-snapshot fingerprint, hyper-parameters, seed, parameters, metrics), and scoring reads a **named
   version**, never "latest fit". Promoting a new fit to the scoring version is a **maker-checker change**, not automatic.
3. **Training-data lineage:** the version row records the Datasets, Schema versions, time window and filters used. Leakage
   control: training inputs are computed as of the label cut-off.
4. **Masking (D-P8 precedent):** entity keys masked on read via `EntityKeyMasking` unless `canRevealLinkEntities`; evidence
   values from a classified column pass `EvidenceMasker` at write time; a classified column may not become a segment / cohort
   label (the `requireUnclassifiedCohort` gate, reused). A stored label must never embed a raw classified value.
5. **Fail closed and bounded:** `maxEntities` / `maxSeries` caps that FAIL the run, never produce a partial result;
   statement and iteration caps; a query failure rethrows with model, Dataset and error class only.
6. **Drift:** every run stores population statistics (score deciles, per-input mean/stddev, missing rate); a drift check
   compares the run to the model's training / first-run profile (population stability index) and flags `drifted` in the run
   log and a Signal. A forecast model stores backtest error per run and flags degradation versus seasonal-naive. Drift is
   *reported*, never auto-retrained.
7. **Honest uncertainty:** forecasts carry an interval; churn scores under a minimum data threshold are `insufficient` (not
   0), like the anomaly baseline; an unvalidated model is labelled so in the UI (hand-authored vs backtested, metric shown).

## 5. Reuse map (copy the pattern; share the helpers; do not rebuild)

| Need | Reuse from | Note |
|---|---|---|
| Component kind, `fromMap` fail-closed parse, unknown keys refused | `AnomalyModel` / `RiskScoreModel` | new parser per kind |
| Save-time gates, reserved output prefix, schema checks | `AnomalyKindValidator` (`ComponentKindValidator` SPI), `ScoreOutputDirs` | extend the prefix list |
| Job Type, params `model`, `as_of`, caps | `AnomalyScoreJobType` / `JobTypeProvider` | |
| History + `_latest` atomic swap + marker | `ScoreOutputDirs`, `AnomalyScoreOutputs` (engine) | |
| Entity Measures per bucket from Datasets | `MeasureCompiler`, the Feature grammar | needs a windowed variant (S1) |
| Baseline statistics (median/MAD, seasonal fallback) | `BaselineStatistics`, `SeasonalBaseline` | seasonal-naive is a cousin |
| Recompute-from-explanation, golden corpora, mutation checks | `AnomalyScorer.recompute`, golden-corpus tests | |
| Masking | `EntityKeyMasking`, `EvidenceMasker` | |
| Banding + Signal + `afterScore` pending Alert Rule | `PendingAlertRules` (`KINDS` gains the new kinds) | the writer inventory row needs operator approval |
| Entity Lists feed (watch / exclusion) | `WatchListFeed` | "high churn" to a watch list |
| Preview with resource cap | `POST /anomaly-scores/preview`, `PREVIEW_POLICY` | |
| UI pane, panel, series chart | anomaly pane, `<inspecto-anomaly-panel>`, `<inspecto-chart>` | |
| Condition tree for declared segments | Decision Kernel (`ConditionTree`, `ConditionSql.predicateBound`) | write path MUST use the bound form |
| Dashboards | BI Widgets over `_latest` Datasets | a first demo needs zero new UI |

Genuinely new: the windowed / ratio Measure grammar, the model-version history, the backtest evaluator, the k-means and
smoothing kernels, the drift statistic.

## 6. MoSCoW and slices (effort: S ~ days, M ~ 1-2 wk, L ~ 3+ wk, one engineer)

| Slice | Content | Effort | MoSCoW |
|---|---|---|---|
| S0 | Decisions signed (section 9), GLOSSARY entries, Offering entry `planned`, row re-pointed | S | **Must** |
| S1 | Module skeleton + manifest + Offering; windowed entity-Measure grammar (recency / frequency / sum over a trailing window, as-of); shared output prefix | M | **Must** |
| S2 | **Segmentation A**: RFM kind, `predict.segment` Job, bins + label table, outputs, recompute, golden corpus | M | **Must** |
| S3 | **Churn A**: declared-coefficient kind, `predict.churn` Job, contributions, bands, `insufficient`, Signal + `afterScore` Alert | M | **Must** |
| S4 | **Forecast A+B**: seasonal-naive and Holt-Winters kind, `predict.forecast` Job, intervals, backtest error vs seasonal-naive | L | **Must** |
| S5 | Routes (read + preview, four gates, masking), Studio panes, entity panel, forecast chart | L | **Must** |
| S6 | Backtest evaluator for churn (AUC / lift in SQL) and the "validated vs hand-authored" label | M | **Should** |
| S7 | Drift report (PSI + backtest degradation) + Signal | M | **Should** |
| S8 | Model-version history Dataset + promote-by-maker-checker (needed as soon as anything is FITTED) | M | **Should** (a **Must** once S9/S10 is chosen) |
| S9 | Segmentation C: k-means (seeded, versioned centroids, cluster naming step) | M | **Could** |
| S10 | Churn B: platform-fitted logistic regression with train/validate split | L | **Could** |
| S11 | Demo content: retail / telecom Space Template on a synthetic golden corpus; Sales & Marketing pack skeleton | L | **Could** (is `PACK-SALES-MARKETING-1`) |
| S12 | Incremental / streaming scoring; real-time decisioning hook | L | **Won't (now)** - the engine is batch (`REALTIME-DECISIONING-1`) |
| S13 | External ML runtime, ONNX model import, AutoML, deep learning, hyper-parameter search per entity | L+ | **Won't** (OD-9) |
| S14 | Uplift / next-best-action / campaign execution | L+ | **Won't** (pack territory, needs Action Requests) |

Must + Should is roughly 2 L + 5 M + 2 S, about 12-16 engineer-weeks. **An unmeasured guess**; S4 and S6 are the least certain.

## 7. Risks and non-goals

**Risks.** (1) *Statistical overclaim*: an unvalidated number is treated as truth; mitigated by backtest metrics, the
`insufficient` state and a "hand-authored" label, but it is a product-honesty risk the operator owns. (2) *The churn label*
differs per customer and is the real work; the first slice dodges it by not fitting. (3) *Leakage* in any fitted slice.
(4) *Cost*: a windowed per-entity aggregate over a large fact Dataset is the expensive statement; same caps and
measure-first approach as anomaly (D-AD9). (5) *Reproducibility drift*: floating-point summation order differs across
threads; pin `ORDER BY` entity and use tolerance where order cannot be pinned - **how DuckDB orders parallel floating sums
here was not verified**. (6) *Vocabulary* collisions. (7) *Scope pull* toward "real" ML once a demo lands (S9/S10/S13).
(8) *Four-eyes cost*: approval on every retrain may be heavy for marketing users (OD-7). (9) *Privacy*: segmenting people is
profiling; a compliance review (consent, automated-decision obligations) is not done here and is owed before any
customer-facing use.

**Non-goals.** No general ML platform, notebook, feature store or model-registry UI; no training on the request path; no
GPU; no auto-retrain; no LLM-generated predictions (the Assistant may *explain* a stored explanation, never compute a
score); no write-back of segments to a CRM (Action Requests / Integration & Delivery territory); no real-time scoring; no
cross-Space model sharing (Exchange territory).

## 8. Headline recommendation

A new optional module `inspecto-predictive`, shipped as three SQL/JVM-native, deterministic, explainable first slices -
**RFM segmentation, declared-coefficient churn scoring, and seasonal-naive / Holt-Winters forecasting with a stored
backtest** - built by copying the Anomaly Score pattern, with **no new third-party dependency and no ML runtime**.
Platform-fitted models (k-means, fitted logistic regression) are a second, separately decided phase that adds a versioned
model-artefact history and four-eyes promotion. External ML runtimes are out of scope. Alternatives: (X) *smaller* -
declared-only (segments by condition tree, churn by weights), about 3 weeks, effectively a Risk Score variant and not
"predictive"; (Y) *bigger* - a vetted JVM library (Smile / Tribuo class, licence to be checked) for k-means, logistic
regression, boosting and ARIMA, 6+ weeks plus dependency review, SBOM, native-licence and jlink work.

## 9. Open decisions for the operator interview (each: options, recommendation)

**OD-1 Vocabulary and names.** *Segment* is taken (ingester record kind), *Feature* is taken (Anomaly Model), *Model* is
overloaded. Options: (a) **Customer Segment** / *Segmentation Model*, *Churn Model*, *Forecast Model*, with a new term
**Input** for churn variables; (b) reuse *Feature* generically (breaks "one concept one word"); (c) an umbrella **Prediction
Model** with three Types. *Recommend (a)* plus **Prediction** as the umbrella output term (Instances: *Churn Score*,
*Segment Assignment*, *Forecast*), recorded in GLOSSARY before any code.

**OD-2 Module boundary.** (a) new `inspecto-predictive`; (b) fold into `inspecto-scoring`; (c) three modules.
*Recommend (a)*: one buyer, one Offering, shared kernels (windowed Measures, backtest, drift).

**OD-3 Component kinds.** (a) three kinds; (b) one kind with `method:`. *Recommend (a)*: separate schemas, parsers and
validators; a new method is a field, not a new kind.

**OD-4 Which capability first, and is a thin "declared" v1 acceptable?** (a) all three first slices (S2-S4); (b) forecasting
only (clearest value, least label risk); (c) churn only. *Recommend (a)* if a pack demo is the goal, *(b)* if the least
statistical exposure matters most.

**OD-5 Edition and Offering placement.** (a) own add-on `predictiveAnalytics`, Professional and up, not Personal;
(b) Enterprise only; (c) include in Personal for single-analyst forecasting. *Recommend (a)*, matching D-AD4.

**OD-6 Does the platform ever train?** (a) never in v1 - coefficients and parameters are authored config (S2-S4, S6);
(b) yes for k-means and logistic regression in the JVM (S8-S10); (c) yes via an external runtime. *Recommend (a) for v1,
decide (b) after S6 shows how authors actually obtain coefficients; (c) no.*

**OD-7 Four-eyes for model changes and promotions.** (a) maker-checker on every model save and on promotion of a fitted
version; (b) on save only; (c) approval only for changes that alter a customer-facing segment. *Recommend (a)*; revisit if
marketing users find it heavy. A fitted-version promotion is a new writer, so a write-funnel inventory row needs operator approval.

**OD-8 Access and masking.** Read gate: reuse `canWorkIncidents` (fraud-analyst semantics) or a new analytics gate /
capability. Mask segment and churn output about people by default, reveal with `canRevealLinkEntities`? *Recommend* reuse
existing gates now, mask keys like scores, and raise the new-capability question with the Sales & Marketing pack.

**OD-9 Dependencies.** (a) none: SQL + hand-written JVM kernels (k-means, Holt-Winters, logistic IRLS); (b) one vetted
JVM stats/ML library (Smile, Tribuo, Commons Math class) - licence check, `tools/dependencies.lock` sign-off, SBOM,
`check-native-licences`, jlink module set (the jlinked runtime lacks `jdk.*` modules), per-jar signing; (c) ONNX / external
runtime. *Recommend (a) through S8; (b) only as a named, signed-off exception per library and only for S9/S10 if
hand-written kernels prove too costly; (c) refused.* onnxruntime is already locked for the Assistant, but reusing it here
couples two add-ons and brings a model-file trust problem.

**OD-10 Churn label and outcome Dataset.** Backtest and any fitting need "churned" declared: (a) inactivity for N days;
(b) an explicit outcome Dataset (terminations); (c) both. *Recommend (c)*, with (a) as the zero-config default.

**OD-11 Forecast grain and limits.** Bucket grain, maximum horizon, `maxSeries` cap, minimum history. *Recommend* day and
week first, horizon <= 90 buckets, at least two seasons of history else `insufficient`, `maxSeries` default 100 000, fail closed.

**OD-12 Reproducibility standard.** (a) bit-identical for the same version + inputs + seed (pinned ordering, may cost
performance); (b) identical within a stated tolerance (1e-9 relative). *Recommend (b)*, stated honestly, with (a) for integer
and rank-based outputs (segments, bins), after S2 measures DuckDB aggregation order.

**OD-13 Validation and drift display.** Show backtest metric and a "hand-authored / backtested / fitted" provenance on every
prediction surface, and report drift as a Signal only (never auto-retrain)? *Recommend yes to all*; hiding validation status
is dishonest.

**OD-14 Privacy and profiling review.** Segmenting individuals may carry consent / automated-decision obligations. Who signs
that review, and is it a gate for the Sales & Marketing pack? *Recommend* compliance sign-off is a precondition for any
customer-facing template, not for the engine module.

**OD-15 Relationship to the Sales & Marketing pack.** (a) the module first, the pack (`PACK-SALES-MARKETING-1`) later as Space
Templates + retail vocabulary; (b) the pack drives the module's scope (demo-first). *Recommend (a)*, with a synthetic golden
retail corpus built in S11 so the pack is demonstrable.

**OD-16 Interaction with Anomaly / Risk.** Is a churn score only a Risk Score factor (the `_latest` pattern, no code), or
does it also emit its own Signal? *Recommend* both: the `_latest` Dataset is a documented factor source and the module emits
its own Signal for the `afterScore` Alert path.

**OD-17 Priority and start.** Confirm P3, and whether S0 may start before the BI pack is scheduled. *Recommend* keep P3;
run S0 (decisions + glossary) only when the operator schedules the module.

## 9a. Decisions taken 2026-10-12 (operator interview)

* **OD-1, OD-2, OD-3, OD-5** accepted as recommended: terms **Customer Segment** / **Segmentation Model**, **Churn Model**, **Forecast Model** with the umbrella **Prediction** (recorded in `GLOSSARY.md` section 4 on 2026-10-12); one new optional module `inspecto-predictive`; three component kinds; own add-on `predictiveAnalytics`, Professional and up, not Personal.
* **OD-4, OD-6, OD-9** all three thin 'declared' first slices (RFM segmentation, churn score with AUTHORED coefficients, forecasting with a stored backtest); the platform never trains in v1; no new dependency (hand-written JVM/DuckDB kernels); platform-fitted models are decided after S6; external ML runtimes refused.
* **OD-7, OD-8, OD-14** maker-checker on every model save and on promotion of a fitted version (a promotion writer needs an operator-approved inventory row when built); reuse the existing gates and mask keys like scores; a compliance sign-off gates any customer-facing template, not the engine module.
* **OD-17** priority stays P3 and design-only: S0 (decisions + glossary) is DONE with this record; no kernel work until the operator schedules the module.
* Not yet decided (answer when the module is scheduled): OD-10 churn label, OD-11 forecast grain and limits, OD-12 reproducibility tolerance, OD-13 validation display, OD-15 Sales & Marketing pack relationship, OD-16 interaction with Anomaly/Risk.

## 10. Proposed BACKLOG rows (text only - this lane may not edit `docs/BACKLOG.md`)

- **P3 `PREDICTIVE-ANALYTICS-1`** (existing row, re-point): design in `superpower/predictive-analytics-design.md`; S0 is the
  interview of OD-1..OD-17; nothing built.
- **P3 `PREDICTIVE-WINDOWED-MEASURES-1`**: entity Measures over a trailing window as-of a date (recency, frequency, sum), the
  shared kernel for RFM and churn inputs; bound values; golden corpus.
- **P3 `PREDICTIVE-SEGMENT-RFM-1`**: segmentation kind + `predict.segment` Job, declared RFM bins and label table.
- **P3 `PREDICTIVE-CHURN-DECLARED-1`**: churn kind, declared-coefficient logistic score, contributions, `insufficient`,
  `afterScore` pending Alert Rule seed (needs an operator-approved inventory row for the writer).
- **P3 `PREDICTIVE-FORECAST-1`**: forecast kind, seasonal-naive + Holt-Winters, interval, backtest error stored.
- **P3 `PREDICTIVE-SURFACE-1`**: read + preview routes (four gates), Studio panes, entity panel, forecast chart.
- **P3 `PREDICTIVE-VALIDATE-DRIFT-1`**: churn backtest (AUC / lift in SQL) and the drift report (PSI, forecast-error degradation).
- **P3 `PREDICTIVE-MODEL-VERSIONS-1`**: versioned fitted-model history Dataset and maker-checker promotion (precondition for fitting).
- **P3 `PREDICTIVE-KMEANS-1`** and **`PREDICTIVE-CHURN-FITTED-1`**: the fitted slices; gated on OD-6 and OD-9.
- **P3 `PACK-SALES-MARKETING-1`** (existing): Space Templates + retail vocabulary + synthetic golden corpus; gated on OD-14 / OD-15.
