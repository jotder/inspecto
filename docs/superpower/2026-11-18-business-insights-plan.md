# Business insights in Widgets and Dashboards — research and design

> **Status: DESIGN APPROVED 2026-11-18 — D-BI1, D-BI2, D-BI3 signed (operator, recommended options); D-BI4…D-BI10
> signed 2026-11-24 (operator, recommended options); P0 I-3 KPI History built 2026-11-24 (§11.1, D-BI11, D-BI12); P0 I-14 model output Datasets built 2026-11-24 (§11.3).** Every decision in §11 is closed. Board row: `BI-INSIGHTS-1`
> (§12). The new terms are in `GLOSSARY.md` §7.
> Scope: insights on **business data** — the Measures over Datasets (revenue, usage, subscribers, transactions,
> margin, leakage, partners, churn). Not observability: pipeline health stays with Metrics and Alert Rules.
> Inventory checked against the code on 2026-11-18 (three read-only sweeps plus spot checks); every BUILT / PARTIAL /
> ABSENT below names where it was found or what was searched.

## 1. The goal

A business user opens a Dashboard and the Widgets do not only show numbers — they answer six questions, on the
chart, about the data the Space ingested:

| # | Question | Example on telecom revenue |
|---|---|---|
| Q1 | **What happened?** | Prepaid revenue −6.2 % vs last week, −1.1 % vs the same week last year |
| Q2 | **Why did it move?** | 71 % of the drop is bundle `DATA-7D` in region North; ARPU fell on mix, not on price |
| Q3 | **What is unusual?** | Roaming revenue from partner `P-14` is 4.1σ below its weekday baseline on 3 days |
| Q4 | **What comes next?** | Month lands at 92 % of target (80 % interval 88–96 %); RED on projection, AMBER on actual |
| Q5 | **Who or what is at risk?** | 1,840 high-value subscribers moved Champions → At risk this month |
| Q6 | **What do we do?** | Open a Case with the evidence; arm an Alert Rule on the projection; brief the CFO |

And the people who own the numbers **define, author and tune** these insights themselves in Studio — which Measure,
which dimensions to look across, how sensitive, which known events to ignore — and can see, before saving, how
noisy their settings would have been on history.

## 2. What exists today

### 2.1 Presentation — Studio (inspecto-ui)

| Capability | State | Where |
|---|---|---|
| 19 Visualization Types: line, bar, area, pie, scatter, bubble, funnel, gauge, combo, waterfall, heatmap, treemap, table, kpi, kpi-trend, progress-list, geo-map, link-analysis, working-set, reconciliation | BUILT | `inspecto/viz/viz-registry.ts`, `viz/plugins/*.plugin.ts`; Chart.js, ag-Grid, G6, custom components |
| Widget channels x / y / y2 / series / size / color / value / compare / group; aggregates incl. median, stddev, p05…p99, `recency`; grain auto/day/week/month; filters, trailing window, ratio Measures, order, top-N | BUILT | `widgets/widget-types.ts`, `viz/viz-types.ts` |
| Show-Me recommender ranks Visualization Types for a Result Set by column roles | BUILT | `viz/show-me.ts:55` |
| Dashboard grid, quick filters, cross-filter on click, drill-through drawer, date range, public share link | BUILT | `studio/dashboards/` |
| KPI target with "better" direction and a worded status (never colour alone) | BUILT | `viz/target-status.ts` |
| kpi-trend: latest vs N periods back | PARTIAL | `VizRenderOptions.trend.compareBack` |
| Forecast chart with interval band and backtest line; anomaly day series | PARTIAL — Incident / entity panels only, **no Widget uses them** | `components/forecast-chart.component.ts`, `anomaly/anomaly-day-series.ts` |
| "Draft a KPI report" AI assist in the Dashboard editor | PARTIAL — passes Dataset + title only | `dashboard-editor.component.html:137` |
| Reference lines, target bands, chart markers, conditional formatting, period-over-period on any Widget, forecast bands in Widgets, alert-from-Widget, Dashboard parameters, auto-refresh, "explain this change" | ABSENT | searched `inspecto/viz`, widget dialogs, dashboards folder |

### 2.2 Query and KPI backend

| Capability | State | Where |
|---|---|---|
| Structured query `POST /bi/query` compiled by `MeasureCompiler` (Measures, groupBy, filters, as-of window, ratios, order, limit) and saved SQL Queries `POST /queries/{id}/run` with `$`-Parameters; `SqlGuard` + sandboxed DuckDB; masking via `BiResultMasking` | BUILT | `BiRoutes.java`, `MeasureCompiler.java`, `QueryRoutes.java`, `QueryExecutor.java` |
| Result Set column roles (dimension / measure / temporal) + cardinality | BUILT | `query/ResultSetDescriptor.java` |
| Calculated Measures beyond `Ratio(num, den)` | ABSENT | `MeasureCompiler.java:94` |
| Result caching | ABSENT | no cache in `QueryRoutes` / `BiRoutes` (by design: "never cached") |
| KPI component (`kpi`): Measure, time field, grain day…year, previous-period comparison, direction, target, RAG bands, unit, owner | BUILT | `query/KpiDefinition.java` |
| KPI value on demand, period to date or a range | BUILT | `KpiEvaluator.java`, `GET /kpis/{id}/value` (`KpiRoutes.java:57`) |
| **KPI history** (values stored per period) | ABSENT | nothing persists `KpiEvaluator` output |
| Alert Rule on a Dataset Measure, per entity (`by`), windowed | BUILT | `alert/AlertRule.java` (BI-5, PA-9b) |
| Alert Rule on a KPI id or on a projection | BUILT 2026-11-25 (P1 I-17, §11.5) | `kpi` / `projection` / `rag` on `AlertRule` |
| REPORT Job: CSV / XLSX / PNG / PDF; PDF draws bar, line and kpi Widgets; emits `REPORT_READY` | BUILT | `job/ReportJob.java`, `ReportWidget.java:23` |

### 2.3 Analytic engines that could feed insights

| Capability | State | Where |
|---|---|---|
| 16 analytic Steps: forecast (Holt-Winters / seasonal naive + interval), changepoint (band, regime, CUSUM drift), outlier (fences), correlation (Pearson / Spearman, slope, r²), binning, similarity, rank, sample, retention, funnel, resample, shift (period over period), running, profile, entity_link, summarize | BUILT | `elt/spec-step-catalog.md`; SQL from the Analytics kernel |
| Anomaly Models (self + Peer Group baselines, isolation forest, 0–100), Forecast / Churn (logistic) / Segmentation (RFM) models, Risk Scores | BUILT | `features/inspecto-anomaly`, `-predictive`, `-scoring`; Jobs `anomaly.score`, `predict.*`, `risk.score` |
| Per-row explanation that recomputes the score (`ExplanationPart`, contribution = share × raw; churn bᵢ·zᵢ) | BUILT | `analytics/score/ExplanationPart.java` |
| Model outputs visible to Studio | PARTIAL — `<kind>_<id>_latest` must be **declared as a Dataset by hand** | `admin-guide-anomaly.md:46-52`; no auto-registration found |
| Kernel `Backtest`, `SeasonalityDetector`; `Accuracy` via catalogue only | BUILT, **no production caller** | `analytics/accuracy/`, `analytics/time/` |
| Graph algorithms: PageRank, eigenvector, Katz, HITS, Louvain, betweenness, closeness, link prediction, temporal motifs | BUILT; results are JSON, **no writeback to a Dataset** | `platform/inspecto-graph`, `la-api` `GraphResultJson` |
| "Why did this Measure move" — drivers, contribution, mix vs rate | ABSENT | searched analytics / anomaly / scoring docs for root-cause, driver, decomposition |
| Grounded narrative: `report-narrative` restates a platform-computed report; `NumericGroundingGuard` rejects any number not in the input | BUILT (pipeline reports only) | `features/inspecto-agent/.../ReportNarrativeSkill.java` |
| Assistant drafts: `kpi_report_builder`, `query_author`, `KpiToSqlSkill`, `ReportSqlSkill`, `AlertRuleTool` — all draft-only | BUILT | `InspectoTools.java`, `features/inspecto-agent/.../skill/` |

### 2.4 The one-sentence diagnosis

**The engines that find insights and the Widgets that show numbers do not know each other.** Forecasts, anomalies,
change points and scores are computed in Pipelines and Jobs into tables a Builder must wire by hand, one Measure at
a time; a Widget queries a raw Measure with no idea of its baseline, its forecast, its target trajectory or what
drove its change — and nothing explains a movement at all.

## 3. What can be assembled today (Tier A — configuration only, no code)

Honest baseline for a demo or a pilot, using shipped pieces only:

1. **Forecast with interval** — `forecast` Step → Derived Table → combo Widget with `forecast`, `lower`, `upper` as
   series (the band is drawn as two lines, not filled).
2. **Period over period** — `shift` Step adds the previous value per key → table / kpi-trend Widgets.
3. **Unusual days** — `changepoint` / `outlier` flags → table Widget filtered on the flag, scatter for context.
4. **Co-movement** — `correlation` Step → heatmap Widget.
5. **Cohorts and conversion** — `retention` → heatmap; `funnel` → funnel Widget.
6. **KPI tiles** with target and RAG bands; a Measure Alert Rule (`by`, window) for the threshold.
7. **Who is at risk** — declare the churn / anomaly / risk / segment `_latest` outputs as Datasets → table Widgets
   with `rowLink` to Cases and Incidents.
8. **Draft the Dashboard** with `kpi_report_builder`.

Limits: one hand-built Pipeline per Measure and question; the chart does not know an insight exists; nothing
explains a change; no tuning loop; insights are not ranked, deduplicated or delivered.

## 4. Design principles (from the house style, not new)

1. **Deterministic numbers, language only on top.** Every figure is computed by the kernel or DuckDB. A model may
   only restate computed Insights, through the `NumericGroundingGuard` pattern.
2. **Every Insight is recomputable and shows its evidence**: its `ExplanationPart`s, and the exact structured query
   that reproduces it — one click opens it as a Widget.
3. **One home per data operation.** New statistics go into `platform/inspecto-analytics` with a JVM and a DuckDB
   form and a parity test; the data-ops duplication guard keeps it that way.
4. **Authored as components, armed by save.** Draft / Apply, version history, `ConfigSafetyValidator`, Drafts and
   four-eyes where the Space requires them. Nothing tunes itself silently.
5. **Unknown is absent, never 0** (`KPI-UNKNOWN-1`). Too little history → no forecast and no Insight, said in words.
6. **Masking holds everywhere.** A driver slice on a classified key shows its alias; public shares show aliases.
7. **Bounded compute.** Every scan has caps (slices, cardinality, history) like `EntityCap` and `PREVIEW_POLICY`,
   and refuses past them rather than degrading silently.

## 5. Proposed concepts (vocabulary — D-BI1)

| Proposed term | Meaning | Note |
|---|---|---|
| **Insight Type** | A template for one kind of finding: `change`, `driver`, `anomaly`, `trend-break`, `forecast`, `target-risk`, `concentration`, `distribution-shift`, `segment-migration` | Type ↔ Instance, like Visualization Type ↔ Widget |
| **Insight** | One computed finding about a Measure, for a slice and a period: value, expected, delta, score 0–100 with bands, `ExplanationPart`s, the reproducing query, status | Instance of an Insight Type |
| **Insight Profile** | Component kind `insight-profile`: the Measure or KPI, the dimensions to look across, the Insight Types switched on with their tuning, schedule, owner | What a user authors and tunes |
| **Chart Marker** | A visual overlay on a Widget: band, point, reference line, event span | ⛔ not "Annotation" — that word is taken (Notes and Tags) |
| **KPI History** | The stored value of a KPI per period, as a Dataset | Enables trends, forecasts and Alert Rules on KPIs |
| *Business calendar events* | Known campaigns, holidays, price changes, outages added to the existing **business calendar** | Reuse of the Workflow / SLA calendar, not a new concept |

## 6. Capability catalogue

Tier **A** = configuration only (§3) · **B** = extend an existing module · **C** = new module. Effort S / M / L.

### Q1 — What happened

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-1 | **Compare on any Widget**: previous period, same period last year, vs target, vs average — as series, delta labels and % | `MeasureCompiler.Spec.compare` emits the shifted window in the same statement (kernel `TimeSeriesSql` shift); the `compare` channel already exists in `viz-types.ts` | B (engine, UI) | M |
| I-2 | **Reference lines and target bands**: constant, target, average, percentile, RAG band | New `VizRenderOptions.markers`; Chart.js overlay; colours from named palettes (`lint:tokens`) | B (UI) | S |
| I-3 | **KPI History** + sparkline and trend on every KPI tile | Job type `kpi.snapshot` stores `KpiEvaluator` values per grain into an auto-registered Dataset; backfill on first run | B (engine) | M |

### Q2 — Why did it move (the headline innovation)

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-4 | **"Explain this change"** on any KPI tile or chart point: the smallest set of slices that explains most of the change | Additive Measures (sum, count): contribution of slice c = M_t(c) − M_{t−1}(c), share = contribution ÷ Δ. Rank dimensions by **surprise** (Jensen-Shannon divergence of the slice shares between the two periods), then take the fewest elements covering ≥ X % of Δ (Adtributor-style; depth 2 under a cap). One DuckDB `GROUPING SETS` query per candidate dimension set | B (kernel `analytics.drivers`, engine route, UI drawer) | L |
| I-5 | **Mix vs rate** for ratio Measures (ARPU, success rate, margin %) — an exact shift-share identity | ΔR = Σ_c (w_c,t − w_c,t−1)·r_c,t−1 **[mix]** + Σ_c w_c,t·(r_c,t − r_c,t−1) **[rate]**, w = D_c ÷ D. Rendered as a price / volume / mix bridge on the existing **waterfall** viz | B (kernel, UI) | M |
| I-6 | **Moved with it**: other Measures of the Dataset (and joined References) whose change correlates with this one | kernel correlation SQL over aligned grains; minimum overlap; never claims causation in words | B | M |

Non-decomposable aggregates (median, distinct count, percentiles) are refused in words ("a median does not split
by segment"), never approximated silently.

### Q3 — What is unusual

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-7 | **Anomaly markers on any time-series Widget**, sensitivity slider | Robust z against a seasonal baseline (`SeasonalBaseline`, `RobustStats`) in the Widget's own statement; seasonality chosen by `SeasonalityDetector` (its first caller); business calendar events excuse known days | B (engine, UI) | M |
| I-8 | **Insight scan** — every dimension × Measure slice of an Insight Profile, ranked | Score = impact (share of the Measure, or money via the Measure column's `unit`) × surprise (robust z, changepoint, drift) × novelty (not reported in the last N runs); minimum slice support; **Benjamini-Hochberg** false-discovery control across slices; caps on slices and cardinality | C (`inspecto-insights`, Job `insight.scan`) | L |
| I-9 | **Concentration**: top-N share, Pareto 80/20 point, HHI, Gini — "revenue concentration in 5 partners rose to 63 %" | New kernel statistics with DuckDB forms | B (kernel) | S |
| I-10 | **Distribution shift** between periods (PSI, Kolmogorov-Smirnov) — e.g. top-up denomination mix, transaction size | New kernel statistics | B (kernel) | S |

### Q4 — What comes next

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-11 | **Forecast band on any time-series Widget**, with its backtest accuracy shown | `ForecastSql` + `PredictionInterval` in the Widget statement; kernel `Backtest` / `Accuracy` (first production callers); the persisted `forecast-model` stays for heavy series | B (engine, UI) | M |
| I-12 | **Target-attainment projection**: where the period lands, its interval and P(target met); RAG on projection beside RAG on actual | Period-to-date actual + forecast of the remaining buckets; P from the interval; < 2 seasons of history → "no projection" | B (`KpiEvaluator`, UI) | M |
| I-13 | **Scenarios (what-if)**: shift drivers ("+5 % price on DATA-7D", "−1 pt churn in North") and see the projected Measure | Linear driver model from I-4 / I-5 decomposition + I-11 baseline; component kind `scenario`; never written back to data | C | L |

### Q5 — Who or what is at risk

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-14 | **Model outputs as first-class Datasets** — churn, anomaly, risk, segment, forecast `_latest` and history auto-registered, column roles set, masked keys | `ScoreOutputWriter` callers register the relation through the Catalog on first write | B (anomaly, predictive, scoring) | S |
| I-15 | **Segment migration**: RFM segment transitions between runs, value moved per transition | Segmentation history → transition matrix; new **sankey** Visualization Type | B (predictive, UI) | M |
| I-16 | **Network-weighted value**: PageRank / community / betweenness joined to business Measures — "revenue inside the flagged fraud community", "key agents by money relayed" | Graph result writeback to a Dataset through the LA ports | B (la-core, graph) | M |

### Q6 — What do we do

| ID | Capability | How it computes | Tier | Effort |
|---|---|---|---|---|
| I-17 | **Insight → action**: arm an Alert Rule from a Widget or an Insight (Measure, KPI id or projection); open a Case or an Action Request with the evidence | `AlertRule` gains `kpi` and `projection` targets; existing Case / Action Request routes | B (engine, ops) | M |
| I-18 | **Dashboard Brief**: the top Insights of the period in plain words at the top of a Dashboard and as the first page of the PDF Report | `report-narrative` pattern over Insights: extractive, `NumericGroundingGuard`, AI Assist add-on only; without it, a templated sentence per Insight | B (agent, Report Job) | M |
| I-19 | **Ask the Dashboard**: a question in words becomes a **structured `MeasureCompiler` spec** (not SQL), shown as a draft Widget | Closed vocabulary from the Dataset's column roles; the compiler validates; same masking and caps; draft-only | B (intelligence) | M |
| I-20 | **Insight-aware Show-Me**: building a Widget suggests overlays ("weekly seasonal — add a forecast band?", "add target band") | `SeasonalityDetector` + KPI binding feed `recommend()` | B (UI, engine) | S |

## 7. Authoring and tuning

The **Insight Profile editor** (Studio, beside Query Library and Viz Library):

1. **Pick** a Measure (Dataset + aggregation) or a KPI. Column roles propose the time field and the dimensions.
2. **Choose dimensions** to look across (region, product, channel, partner, segment) and depth (1 or 2).
3. **Switch on Insight Types**, each with plain knobs: sensitivity (Low / Normal / High → z threshold), minimum
   impact (absolute or % of the Measure, or money), minimum slice size, seasonality (auto / none / weekly / yearly),
   forecast horizon, comparison (previous period / same period last year / target).
4. **Exclude** known days from the business calendar (campaigns, holidays, outages) or mark them "expected".
5. **Preview on history — tuning by replay.** `POST /insights/preview` runs the unsaved Profile as-of each of the
   last N periods (rolling origin, kernel `Backtest`) and answers: "with these settings you would have seen 7
   Insights in the last 90 days" — the list, a per-week count and a noise budget. The user moves a slider and sees
   the count change before saving. Nothing is armed until Save.
6. **Feedback closes the loop.** On each Insight: *Useful* / *Not useful* / *Expected (why)*. "Expected" can add a
   business calendar event. Precision per Profile is shown in the editor; the Assistant may *suggest* a new
   threshold as a draft — never applied by itself.
7. **Who may do what (D-BI4, narrowed 2026-11-26).** Builders author Profiles and whoever may save a Profile tunes it
   (owner tuning within Builder bounds was dropped, §11.16); anyone who can view reads Insights, explains and gives
   feedback, and Business users read Insights, give feedback and arm Alert Rules they are allowed to.

## 8. Architecture

```
platform/inspecto-analytics   + drivers (contribution, surprise, minimal cover), shift-share mix/rate,
                                concentration (top-N, HHI, Gini), distribution shift (PSI, KS),
                                interestingness score, BH false-discovery control
                                → first callers of Backtest, Accuracy, SeasonalityDetector
platform/inspecto-engine      MeasureCompiler.Spec + compare + overlay (baseline | forecast | anomaly | target)
                                → ResultSetDescriptor marks overlay columns; same SqlGuard / sandbox / masking
                              KpiEvaluator + projection;  Job type kpi.snapshot → KPI History Dataset
                              AlertRule + kpi / projection targets;  Platform Service `analytics` widened
                                (forecast, baseline) so Jobs and packs can reach them
features/inspecto-insights    NEW (add-on, D-BI3): component kind insight-profile; Job type insight.scan;
  (module id `insights`)        Insights written like scores (history + atomically swapped _latest),
                                auto-registered as a Dataset; routes GET /insights, POST /insights/preview,
                                POST /insights/{id}/feedback; a Signal per new Insight → Notification Rules
features/inspecto-anomaly,    auto-register _latest / history as Datasets (I-14); segment transitions (I-15)
  -predictive, -scoring
la/inspecto-la-core           graph result writeback through the ports (I-16)
features/inspecto-agent,      Dashboard Brief over Insights (grounded); question → MeasureCompiler spec
  -intelligence
inspecto-ui                   Chart Markers (bands, points, lines, event spans) as Chart.js overlays;
                              "Explain this" drawer; Visualization Types insight-feed and sankey;
                              KPI tile sparkline + projection; Insight Profile editor with replay preview
job/ReportJob                 draws Chart Markers and the Brief page in PDF
```

**Query-time vs precomputed (D-BI7).** Overlays (I-1, I-2, I-7, I-11, I-12) run inside the Widget's own sandboxed
statement — fresh, no new storage, bounded by the same row caps. Scans (I-8) and explanations across many
dimensions (I-4 at depth 2) run as Jobs and are read back — they are too expensive per click on large Datasets.
"Explain this change" at depth 1 is interactive.

**Each Insight row**: `insight_id`, `profile_id`, `insight_type`, `measure`, `slice` (dimension = value pairs,
masked), `period`, `value`, `expected`, `delta`, `impact` (with unit), `score` 0–100 + band (score contract),
`explanation` (`ExplanationPart`s), `query` (the structured spec that reproduces it), `status` (new / useful /
not useful / expected), `first_seen`, `last_seen`. Being a Dataset, Insights are themselves chartable ("Insights per
region per week").

## 9. Phased roadmap

| Phase | Delivers | Capabilities | Why first |
|---|---|---|---|
| P0 Foundations | KPI History, model outputs as Datasets, compare on any Widget, reference lines and target bands | I-1, I-2, I-3, I-14 | Cheap, unblocks everything else, visible at once |
| P1 Overlays | Forecast band and anomaly markers on any time Widget; target-attainment projection; Alert Rule on KPI / projection | I-7, I-11, I-12, I-17, I-20 | The "insight on the chart" moment; uses idle kernel parts |
| P2 Explain | "Explain this change", mix vs rate bridge, moved-with-it | I-4, I-5, I-6 | The differentiator; no competitor row in the matrix claims it |
| P3 Profiles | Insight Profiles, scan, Insight Feed, replay preview, feedback, calendar events, concentration and distribution shift | I-8, I-9, I-10 | Turns one-off overlays into a tunable product |
| P4 Language | Dashboard Brief, Ask the Dashboard, Brief page in Reports | I-18, I-19 | Needs Insights to narrate; AI Assist add-on |
| P5 Advanced | Scenarios, segment migration, network-weighted value | I-13, I-15, I-16 | Highest value per pack; most design left |

Pilot (D-BI6): the `business-assurance` and `telco-ra` Space Templates — revenue by product, region and partner
already exists there, and Q2/Q4 answer the questions an RA or finance lead asks every Monday.

## 10. Risks and traps

- **Compute blow-up.** dimensions × values × periods explodes; caps refuse past them (D-BI7), and the scan's DuckDB
  memory is the `ANOMALY-SCORE-MEMORY-LIMIT-1` question again.
- **Alert fatigue.** Without interestingness ranking, false-discovery control and feedback, users mute the feed.
  Replay preview makes noise visible before it ships.
- **Statistical honesty.** Minimum slice sizes; non-decomposable aggregates refused; correlation never worded as
  cause; forecasts need two seasons; time grains use the zoned `TimeGrain` (DuckDB session time zone = host).
- **Masking.** Driver slices, Insight `slice` and narrative text carry aliases for roles without reveal; a public
  share never reveals.
- **Language models.** Narration only, extractive, numbers grounded; off when the AI Assist add-on is absent.
- **Chart.js limits.** Filled bands are two datasets with `fill`; many markers on long series need decimation.
- **Report PDF parity.** `ReportWidget` draws only bar, line and kpi today; Chart Markers and the Brief page need
  `WidgetChartRenderer` work, or Reports and screens disagree.

## 11. Decisions for the operator

| ID | Decision | Options (recommendation first) |
|---|---|---|
| D-BI1 | Names | ✅ **SIGNED 2026-11-18: (a)** Insight Type / Insight / Insight Profile / Chart Marker / KPI History, added to `GLOSSARY.md` §7 · (b) other names |
| D-BI2 | Where the scan lives | ✅ **SIGNED 2026-11-18: (a)** new `features/inspecto-insights` add-on; kernel and engine extended for overlays · (b) everything inside engine + analytics |
| D-BI3 | Edition placement | ✅ **SIGNED 2026-11-18: (a)** overlays and KPI History in Standard BI; Insight Profiles / scan / explain as a "Business Insights" add-on (Standard+); language features with AI Assist; scenarios Advanced · (b) all in one add-on. **Add-on placement superseded by D-BI13** (every edition) |
| D-BI4 | Who may tune | ✅ **SIGNED 2026-11-24: (a)** Builders author, owners tune within bounds, Business users give feedback · (b) Builders only. **Narrowed 2026-11-26 (operator):** owner tuning within Builder bounds is dropped — whoever may save a Profile tunes it; feedback and Explain for anyone who can view (§11.16) |
| D-BI5 | Language features in the first release | ✅ **SIGNED 2026-11-24: (a)** no — templated sentences first, Brief in P4 · (b) yes |
| D-BI6 | Pilot | ✅ **SIGNED 2026-11-24: (a)** `business-assurance` + `telco-ra` revenue · (b) `mobile-money` transaction value |
| D-BI7 | Compute placement | ✅ **SIGNED 2026-11-24: (a)** overlays at query time, scans and depth-2 explanations as Jobs · (b) everything precomputed |
| D-BI8 | Insights stored as an auto-registered Dataset | ✅ **SIGNED 2026-11-24: (a)** yes · (b) object store only |
| D-BI9 | Chart Markers implementation | ✅ **SIGNED 2026-11-24: (a)** a Chart.js overlay plugin behind the viz contract · (b) custom drawing |
| D-BI10 | KPI History grain and retention | ✅ **SIGNED 2026-11-24: (a)** the KPI's own grain, 3 years · (b) day only, retention per Space |
| D-BI11 | KPI History registration under a Dataset approval policy | ✅ **SIGNED 2026-11-24 (operator):** the `kpi.snapshot` Job registers its Dataset as a **system Dataset regardless of the policy** (`origin: system`); the gate is lifted for this Job only |
| D-BI13 | Edition placement of the Business Insights add-on | ✅ **SIGNED 2026-11-26 (operator interview), reverses D-BI3's add-on placement:** Business Insights ships in **every edition, Basic included** (§11.16) |
| D-BI12 | The period still in progress | ✅ **SIGNED 2026-11-24: (a)** stored with `complete = false`, rewritten each run until it closes · (b) closed periods only |

### 11.1 P0 I-3 KPI History — as designed (2026-11-24)

- **Job `kpi.snapshot`**, parameter `kpi` (the KPI id); built into the control plane (`inspecto/`, ServiceLoader) because
  it needs the BI masking check. One Job per KPI, cron'd like any Job.
- **One Dataset per KPI**, `kpi_history_<kpi>` (id = `physicalRef`), never a shared table: a shared one would show
  every viewer the values of KPIs over Datasets they cannot read. Its `owner` / `shares` admit only who may read both
  the KPI and its Dataset (a KPI and Dataset shared differently refuse the run). The `kpi_history_` store prefix is
  reserved (a check in `ComponentRoutes.validateKind`) except for that Dataset over a saved KPI.
- **Columns**: `kpi_id`, `grain`, `period_start` (DATE), `period_end` (DATE, exclusive), `value` (DOUBLE, NULL when
  the period holds no value — never 0), `rag`, `complete` (BOOLEAN), `snapshot_at` (TIMESTAMP), `definition` (a
  changed KPI definition rebuilds the whole history).
- **Runs as its author** (as `publish.postgres`): a KPI or source Dataset the Job's author cannot view refuses the run,
  and the KPI is the saved Job's (a run may not override it). A Measure that returns raw values of a masked column
  refuses the run (a Job never reveals; BI-QUERY-MASKING-1).
- **Backfill and restatement**: the first run evaluates every period from the later of the data's first day and
  3 years back; later runs evaluate the missing periods plus the current and the previous period (late data
  restates one closed period); rows older than 3 years are dropped. Each run rewrites the KPI's one file
  atomically (`ScoreOutputDirs.swapIn`, the snapshot-swap lock).

### 11.2 P0 I-2 Chart Markers — as built (2026-11-24)

- **Stored shape**: `options.markers` on a `line`, `bar`, `area` or `combo` Widget (`MARKER_VIZ_TYPES`; any other
  type ignores them): `constant` (`value`), `target` (`value` optional), `average`, `percentile` (`p` 1–99),
  `band` (`from` / `to`, either open, `tone` = a status tone) and `rag`; each may carry a `label`.
- **Resolved at render, in the browser** (D-BI7: query-time, no new storage): `inspecto-ui/src/app/inspecto/viz/chart-markers.ts`.
  Statistics read the values on the value axis `y` as drawn (a Combo's bars while its lines sit on the right axis;
  category totals per stack when stacked, a Combo's bars and lines being two stacks as in Chart.js); a percentile is a
  whole number, by `quantile_cont`'s rule. A target line takes its own value, else the
  bound KPI's target, else `options.kpi.target`. A marker that cannot be placed is dropped, never guessed.
- **KPI binding**: `options.kpi.kpiId` (the KPI tile's existing key), chosen in the cog dialog on the four types; the
  definition is read from `/components/kpi/{id}` only when a marker needs it, and `rag` follows `KpiDefinition.rag`
  (up / down thresholds, band intervals). A failed read leaves `rag` undrawn and the target line on the Widget's own target.
  The cog dialog now keeps a KPI tile's `kpiId` through a save (it was dropped before).
- **Drawing (D-BI9)**: one inline Chart.js plugin per chart (`chart-markers.plugin.ts`), built from resolved markers so
  nothing passes Chart.js's option resolver: `afterDataLimits` widens `y` to every marker, bands are shaded behind the
  data, lines drawn over it with their words. Colours from `CHART_TONE` / `CHART_MARKER_LINE` only; the canvas text
  alternative lists every marker (WCAG 1.1.1, 1.4.1).
- **Deliberately deferred**: the Report PDF (`WidgetChartRenderer`, Java2D) did not draw markers — filed as
  `REPORT-CHART-MARKERS-1`, **closed 2026-11-25**: `ChartMarkers` is the contract-tested Java twin
  (`contracts/chart-markers.contract.json`); see the hub § 4 *Report PDF parity*. Decimation of many markers on long series
  (§10) was not needed: markers are a handful of lines, not series.

### 11.3 P0 I-14 model output Datasets — as built (2026-11-24)

- Each model Job registers its `<prefix><model>` and `_latest` stores as system Datasets right after writing them
  (`ScoreDatasets.register`), every column declared with its role and `entity_key` classified with the model's Entity
  Type so BI masking aliases it. Operator rulings (2026-11-24): readable only by who may read the model AND every
  Dataset it reads (`SharingEnvelope`, shared with KPI History; sources shared differently register nothing); system
  Datasets regardless of a Dataset approval policy; a registration problem never fails the run — it is named in the
  run message, and any refusal narrows earlier output Datasets to the model's owner. `entity_key` takes the strictest
  class of the Entity Type and every input's key column (fail closed when untraceable). A hand-declared Dataset is
  never touched, except a bare `_latest` from a template, which is adopted. Spec: `okf/product/module/engine/spec-model-output-datasets.md`.

### 11.4 P1 I-11 forecast band and I-7 anomaly markers — as built (2026-11-25)

- **Wire**: `POST /bi/query` takes `overlay: {forecast?: {horizon? 1–60, level? 0.5–0.99}, anomaly?: {}, field?}`;
  `field` defaults to the one grained `groupBy` column (none or several → 422). Per Measure / ratio `m`:
  `m__fc`, `m__fc_lo`, `m__fc_hi` on `horizon` new rows after the last bucket (the Measure NULL there) and
  `m__expected`, `m__z` on scored rows, marked `{overlay: forecast | anomaly, kind, of, part}`; the response's
  `overlays[]` states per overlay, Measure and series the method, period (`detected` or `calendar`), backtest
  `{origins, horizon, pairs, mape, smape, mae, coverage}`, or the reason. A name clash with a result column → 422.
- **D-BI7 as built**: `ChartOverlays` (`platform/inspecto-engine`, `com.gamma.query`) computes both from the rows the
  Widget's own sandboxed statement returned — after `BiResultMasking`, under the same row cap, no second statement, no
  storage — in the JVM, not as SQL inside the statement. Reason: `Backtest` and `SeasonalBaseline` have no SQL form,
  and `ForecastSql` needs a separate regularity check statement first; `Forecaster` is `ForecastSql`'s JVM twin
  (`ForecastSqlParityTest`), so the band and its backtest describe one model. A masked Measure arrives as text and gets
  no overlay; a masked series key reaches the summary only as its alias.
- **Kernel first production callers**: `Forecaster` + `PredictionInterval` (Holt-Winters, default weights),
  `Backtest` + `Accuracy` (rolling origin from two seasons, at most 60 origins), `SeasonalityDetector` (period
  among the grain's calendar cycles, else the first: 7 / 52 / 12; a weekly cycle switches the anomaly baseline to
  weekday), `SeasonalBaseline` + its robust z (floor 5 % of the median, at least 7 points).
- **Honesty rules**: fewer than two seasons, a gap, an empty bucket, a cut result, more than 20 series or a
  non-number → no forecast and the reason; a missing value is never 0 (the SPA leaves a forecast row's actual as a
  gap). The anomaly sensitivity (1–5 → |z| 5, 4, 3.5, 3, 2.5) is the renderer's: the server's scores do not depend on
  it, so the slider under the chart re-draws without a query.
- **UI**: `options.overlay` (`chart-overlays.ts`) on line, bar, area — only those plugins pass `buildXyQuery(…, true)` — and
  sent only when x is a grained date; a stacked or value-sorted chart draws neither and says why; a series with no row at
  a forecast label is a gap, never 0. Past 20 series the summary is one refusal per Measure and overlay. Drawn
  through the Chart Markers plugin (D-BI9) as two new resolved kinds — `interval` (filled band + dashed point
  forecast) and `points` (rings) — not as extra Chart.js datasets, so the band is one drawing path with I-2.
- **Deliberately deferred**: none for the Report PDF (`REPORT-CHART-MARKERS-1`, closed 2026-11-25: the `report` Job
  sends `overlay` and draws both) or the public share viewer (it asks since §11.7); the method is fixed (Holt-Winters, default weights — no
  per-Widget tuning before Insight Profiles, P3); `SeasonalBaseline` has no week-of-year or month slot, so a week /
  month series is scored against all its other buckets; the baseline is the visible range, not a trailing window.

### 11.5 P1 I-12 projection + I-17 Alert Rule on a KPI — as built (2026-11-25)

Spec: [`okf/product/module/engine/spec-kpi-projection.md`](../okf/product/module/engine/spec-kpi-projection.md).

- **I-12**: `GET /kpis/{id}/value?projection=true` → `projection` {landing, 80 % interval, P(target met), RAG of the
  landing, or `status: none` + reason}. Landing = value to date + Holt-Winters forecast of the **days** left; the
  interval is **measured**, not assumed: rolling-origin `Backtest` of the same H-day total at every past day, sigma =
  scaled MAD of those misses about 0. New kernel class `analytics/forecast/PeriodProjection` and
  `PredictionInterval.normalCdf` — the first production callers of `Backtest`, `Accuracy` and `SeasonalityDetector`, beside `ChartOverlays` (§11.4)
  on `Forecaster` (the Java twin of `ForecastSql`; one small series per KPI, so no SQL form is needed).
- **History = the live Dataset per day, not KPI History** (decided with evidence): KPI History holds whole periods at
  the KPI's grain and cannot say what the rest of a period holds; it also exists only where a Job was scheduled.
- **"Less than two seasons → no projection"** became: count/sum Measure only, grain wider than a day, ≥ 2 weekly seasons
  (14 days) AND ≥ 8 past projections of the days left to measure the interval on. A range is never projected.
- **KPI tile**: "Projected: Amber" badge beside "RAG: Red", and the landing, range and chance in words; hidden in the
  mini size; never asked on a picked range.
- **I-17**: `AlertRule` gained `kpi`, `projection` (`value` | `probability`) and `rag` (`amber` | `red`). A KPI rule is
  a scalar rule scoped to `kpi:<id>` (edge-triggered, healed, Incident at CRITICAL, relapse relinked). Save gate and
  sweep both read the KPI **as the rule's owner** (id only, no roles — a role-only share is refused, fail closed),
  vet the Measure with no reveal, and refuse a status/probability rule on a KPI with no target or bands, or a
  projection a KPI cannot have.
- **Found in passing and fixed**: `ObjectReadMasking` treated every Alert scope as a Dataset id; a `kpi:` scope made
  `GET /alerts` fail (500). A `kpi:` scope now masks nothing (its text holds no key).
- **Deliberately deferred**: (the editor's KPI fields and "arm from a Widget" shipped in §11.7); a role-aware
  owner for rule evaluation — **closed 2026-11-26, §11.14**.

### 11.7 P1 leftovers — overlays on a public share, KPI Alert Rules in the UI (2026-11-25)

- **Share**: `POST /public/dashboards/{token}/query` parses `overlay` as `/bi/query` does and runs `ChartOverlays`
  over the rows after `BiResultMasking.forPublicShare` (no reveal, same cap, no second statement); the response adds
  `overlays`. The share viewer's `embedQueryBody` now forwards `grains` (a grained date axis used to go out raw) and
  `overlay` — still never any other option — and passes `options.overlay` and the summary to the renderer.
- **Editor**: *Watch: A KPI* + *Alert on* (form-only, like `kind`) → `kpi` / `projection` / `rag`; comparator and
  threshold hide on a RAG watch, which sends `gte` + the rank (the engine fixes them anyway). Leaving the KPI kind
  resets the watch, or a hidden RAG watch would hide comparator and threshold for a metric rule.
- **Arm from a Widget = a link, not a second write path**: the tile's *Alert me…* menu (value / projected landing)
  navigates to `/alerts?newRule=1&kpi=…` — the Risk Scores handshake (D-RP9) — so the draft is saved through
  `POST /alerts/rules`, its save gate and approval hold. Shown only to who may author Alert Rules.

### 11.6 P1 I-20 insight-aware Show-Me — as built (2026-11-25)

- **Where**: the Widget builder (`studio/widgets/explore.component`) shows a *Suggested for this chart* strip above the
  preview; the logic is the pure `suggestOverlays()` beside `recommend()` in `inspecto/viz/show-me.ts`.
  `recommend()` itself is unchanged: it ranks Visualization Types from fields, before any query has run, so the
  signal could not live inside it.
- **Seasonality signal = the I-11 / I-7 route, no new backend.** When the Widget is a line, bar or area with a Measure over a grained
  date, neither stacked nor value-sorted, and a forecast or anomaly overlay is not on yet, the builder runs the
  Widget's own statement once more with `overlay: {forecast, anomaly}` through `POST /bi/query` (D-BI7: query
  time, nothing stored; an identical probe is served from `DatasetResultService`'s cache). `ChartOverlays` already
  calls `SeasonalityDetector`, so its `overlays[]` summary is the signal.
- **Honesty (§10)**: a forecast band is offered only when the summary has no `reason` (two seasons, no gap, no empty
  bucket) AND `periodSource: detected` — a calendar fallback is not called a season. The cycle is worded only when it
  names one (7 days → weekly; 52 weeks, 12 months, 4 quarters → yearly), else "A 7-week cycle". Anomaly markers are
  offered only when at least one point reaches the default sensitivity's |z| (3.5), with the count. A target line
  when the Widget or its bound KPI (`options.kpi.kpiId`, read from `/components/kpi/{id}`) has a target; the
  KPI's bands (`rag` marker) when the KPI has them. An overlay or marker already on is never offered again.
- **Never applied without a click**: each suggestion carries the Widget's options with that one overlay added;
  *Add …* sets them and re-runs the preview, *Not now* hides it for this builder session. A probe answering after a later preview run (another
  chart, field or bound Query; a run counter) is dropped. A bound KPI's target wins over the Widget's own, as the
  marker draws it. The preview now also passes the result's overlay summary, so an applied band shows its
  caption in the builder as on a Dashboard.
- **Preview** (2026-11-25, a 112-day series with a weekly cycle and one spike): "Weekly seasonal pattern found over
  112 days of history" and "1 unusual point", both applied by click and drawn. The KPI target / bands suggestion is
  covered by unit tests only (no KPI in the scratch Space).
- **Deliberately deferred**: no suggestion on the Dashboard tile or cog dialog (the builder only); no suggestion of
  a compare (I-1) or a different Visualization Type from the signal.

### 11.8 P2 Explain — I-4 drivers, I-5 mix vs rate, I-6 moved with it — as built (2026-11-25)

- **Module (D-BI2 / D-BI3, operator 2026-11-25)**: `features/inspecto-insights` (module id `insights`, add-on
  `businessInsights`, Standard and above) is created now rather than in P3; P3's scan and Insight Profiles join it.
  Product Map Items `module.insights` and `addon.businessInsights`.
- **Route**: `POST /insights/explain`, gated on `canAuthorWorkbench` when built; **since 2026-11-26 open to anyone who
  may view the Widget's Dataset** (§11.16). Body and response: the module hub
  `okf/product/module/insights/architecture-insights.md` and `openapi-v1.json`.
- **Kernel** `com.gamma.analytics.drivers`: `Drivers` (contribution, share, Jensen-Shannon surprise, minimal cover —
  largest share first, which is the smallest set for a sum threshold), `ShiftShare` (mix + rate = ΔR exactly; a value
  that appears or disappears is all mix), `MovedWith` (Pearson over bucket-to-bucket changes, ≥ 8 pairs, |r| ≥ 0.5).
  Goldens: `DriversGoldenTest` (hand-computed region example, an exact mix/rate split) and `ControlApiExplainTest`.
- **Deviations from §6, with the reason**: (1) one statement per dimension through the new core seam `BiSpecRunner`
  (the `/bi/query` gates, guard, sandbox and masking as the caller), not one `GROUPING SETS` statement — masking
  works per grouped column of one plain SELECT, and a second masking path for grouping sets was not worth the risk;
  (2) the statistics run in the JVM over the masked rows (as `ChartOverlays`, §11.4) — no DuckDB form; (3) for a ratio,
  the drivers use each value's term N_c ÷ D, so contributions add up to ΔR.
- **Honesty**: sum and count (and a ratio of them) only; a non-additive aggregate is refused with the reason (an average
  is pointed at the ratio form). No change → no shares, nothing explains. An empty earlier bucket → 422, never 0. A
  masked Measure → 422. The moved-with note says it "does not say one causes the other".
- **D-BI7**: depth 1 interactive; depth 2 deferred to the P3 Job — board row `BI-EXPLAIN-DEPTH2-1`. Caps: 12
  dimensions, 10 related Measures, 200 values per dimension (more → `refused[]`), 8–60 buckets of lookback.
- **UI**: a light-bulb tile action *Explain this change* on a Widget over a grained date (not on a saved Query, not a
  KPI-bound tile) when `features.insights` is on and the user may author; the dialog shows the change, the drivers per
  dimension, the mix / rate bridge on the waterfall Visualization Type and the moved-with list. No Widget authors a
  ratio yet, so the bridge shows only for a ratio request (route + dialog spec); an `avg` is not silently mapped to
  sum ÷ count (it skips NULLs, `count` does not). Preview 2026-11-25: a line Widget over 18 months, the drawer
  named North as 93 % of the last month's jump and `sum_cost` as moving with it (r = 1.00 over 17 changes).

### 11.9 P3 I-8 Insight scan, Profiles, feed, feedback, replay, calendar — as designed (2026-11-26)

Operator 2026-11-26: the whole P3 backend in one lane (UI and I-9 / I-10 later); surprise = robust z **and**
changepoint **and** drift; one Insights Dataset per Profile.

- **Kernel** `com.gamma.analytics.insight`: `Surprise.spike` (type `anomaly`: robust z of the last complete bucket against the same
  season slot when ≥ `minPoints` of them hold a value, else every earlier bucket), `Surprise.changepoint` (type `trend-break`: largest
  CUSUM of the mean, σ = robust spread of first differences ÷ √2, Kolmogorov tail; reported only when the change lies
  in the last `maxAge` buckets), `Surprise.drift` (type `drift`: Mann-Kendall with ties, Sen's slope), `FalseDiscovery.adjusted`
  (Benjamini-Hochberg q-values), `InsightScore` (impact × surprise × novelty, 0–100, score-contract bands 40 / 70).
  Goldens: `InsightStatsGoldenTest`.
- **Component kind `insight-profile`** (`registry/insight-profiles/`, owned by module `insights`): `dataset`,
  `measure {agg: sum|count, field}`, `field` (date column), `grain` (day|week|month), `dimensions` (0–12; the total
  series is always scanned), `filters` / `when` (as `/bi/query`), `types` (⊆ anomaly, trend-break, drift; default all),
  `sensitivity` (low|normal|high → q ≤ 0.01 / 0.05 / 0.10), `minImpact` (share of the period's Measure, default 0.01),
  `minSupport` (slice share of the window, default 0.01), `maxValues` (per dimension, default 50, ≤ 200), `lookback`
  (buckets, 12–120; default 56 / 26 / 24), `season` (auto|none|n; auto = 7 for day, 52 for week, 12 for month — a slot holding fewer than 4 values falls back to every earlier bucket), `noveltyRuns`
  (default 4), `maxInsights` (per run, default 50, ≤ 200), `calendar` (`{from, to?, label, mode: exclude|expected}`).
  Validated on save by `InsightProfileValidator` (`ComponentKindValidator`); it also reserves the store prefix
  `insights_`.
- **Job `insight.scan`** (parameter `profile`, from the saved Job only): runs as its author (`canView` the Profile,
  `canRead` its Dataset — as `kpi.snapshot`); a Measure over a masked column is refused (a Job never reveals); grouped
  slice values come back as the non-revealing reader sees them (aliases), so stored Insights never hold a raw
  classified value. Statements: one for the total series and one per dimension, each through the new core seam
  `BiSpecRunner.runAsJob` (same compiler, SqlGuard, sandbox and masking as `/bi/query`, as a caller who may not
  reveal). "Now" = the last **complete** bucket. `exclude` calendar buckets are dropped from every series (never in a
  baseline, never reported); a finding dated in an `expected` bucket is dropped and counted.
- **Ranking**: every (slice × type) test that runs yields a p; BH across all of them in the run; kept when q ≤ α,
  impact ≥ `minImpact` and the slice's support ≥ `minSupport`; novelty = 1 ÷ (1 + times the same type × slice was
  reported in the last `noveltyRuns` runs), 0 when a reader marked it *not useful* or *expected*; top `maxInsights`
  by score.
- **Storage (D-BI8)**: Dataset `insights_<profile>` (id = physicalRef), a system Dataset with the Profile ∩ Dataset
  sharing envelope (`SharingEnvelope`, as KPI History; shared differently → refused). One Parquet rewritten atomically
  per run (`ScoreOutputDirs.claim` / `swapIn`), 400 days of runs kept. Columns: `insight_id`, `profile_id`, `run_id`,
  `run_at`, `insight_type`, `measure`, `dimension`, `slice_value`, `period`, `value`, `expected`, `delta`, `impact`,
  `p`, `q`, `score`, `band`, `explanation` (JSON), `query` (JSON `/bi/query` body that reproduces the slice series),
  `status`, `first_seen`. Deviation from §8: no separate `_latest` store — the latest run is `run_id = max`, which
  `GET /insights` reads; one store keeps one envelope.
- **Feedback**: `POST /insights/{profile}/{insightId}/feedback` `{status: useful|not_useful|expected, note?}` by anyone
  who may view the Insights Dataset (D-BI4: Business users give feedback; the `canAuthorWorkbench` gate it shipped with was lifted 2026-11-26, §11.16); kept in `feedback.json` beside the Parquet
  (the Dataset reads `*.parquet` only), keyed by type × slice so it outlives the run; the actor is the authenticated
  principal. The next run writes it into `status` and novelty.
- **Feed**: `GET /insights?profile=&run=latest|all&band=&limit=` — the Profile's Insights the caller may view
  (404 indistinguishable otherwise), best score first, current feedback laid over `status`.
- **Replay**: `POST /insights/preview` `{profile: <unsaved content>, periods: 4–52 (default 12)}`, `canAuthorWorkbench`,
  runs AS THE CALLER through `BiSpecRunner.run`: one fetch of `lookback + periods` buckets, then the scan as of each of
  the last `periods` buckets with novelty carried run to run → `{perPeriod: [{period, count}], total, insights (top 50)}`.
  Nothing is saved.
- **Signals**: `insight.scan.completed` (counts) and `insight.found` per new Insight (≤ 20 per run, band ≥ elevated)
  for Notification Rules.
- **Deliberately deferred**: the Profile editor UI and the Insight Feed Visualization Type; depth-2 slices (`BI-EXPLAIN-DEPTH2-1`); I-9, I-10 (built next, §11.10).

### 11.10 P3 I-9 concentration and I-10 distribution shift — as built (2026-11-26)

- **Kernel** `com.gamma.analytics.insight`: `Concentration` (`topShare`, `paretoCount` — fewest parts reaching a
  share, `hhi`, `gini`; negative parts refused, nothing → NaN) and `DistributionShift` (`psi` with shares floored at
  1e-4, `ksStatistic` over a given category order, `ksPValue` — Kolmogorov tail with Stephens' correction,
  `totalVariation`, `referenceP`, `chiSquareTail`). Goldens: `ConcentrationShiftGoldenTest`.
- **Insight Types** `concentration` and `distribution-shift`: per dimension (the slice value stored is `*`), over the
  same fetched rows as the series types — no new statement. **Opt-in**: `types` still defaults to anomaly, trend-break,
  drift, because adding two tests per dimension to every existing Profile would raise the BH bar on its findings; a
  dimension type with no `dimensions` is refused at save.
- **Tests and their p**: *concentration* = the top-3 share (re-ranked per bucket) as a series, `Surprise.spike` of the
  last complete bucket (season slot rule as `anomaly`, ≥ 4 points, 1 share point floor). *distribution-shift* = PSI of
  the last bucket's value mix against the baseline (every earlier usable bucket summed), p from the references = each
  earlier bucket's leave-one-out PSI, n ≥ 4 (`DistributionShift.referenceP`): within them, the empirical rank
  (# ≥ now + 1) ÷ (n + 1); beyond the largest, the upper tail of a scaled chi-square c·χ²(ν) matched to their mean and
  variance (Satterthwaite), never above the rank floor 1 ÷ (n + 1). Not a normal tail of the robust z: PSI is
  right-skewed across buckets (a rare value in a small bucket hits the share floor), and the verification probe found
  a normal tail gave P(p < .01) = 0.22 on a stable mix at ~30 units a bucket. Not a rank alone either: its floor
  (.018 for 56 days) lost every obvious shift to Benjamini-Hochberg once the series types ran beside it, and never
  fired at sensitivity `low`. Pinned by a null test (P(p < .05) ≤ .07 at ~30 units) and a power test (E 20 % → 50 % found
  with all five types, at normal and low). Concentration
  (a normal tail, as `anomaly`) was calibrated at ≤ .066 for .05. Concentration skips a dimension with 3 values or
  fewer (the top 3 always hold everything). Both p's join the run's Benjamini-Hochberg pass.
- **Deviations from §6, with the reason**: (1) **no DuckDB form** — as §11.8, the statistics run in the JVM over the
  masked, already-grouped rows (≤ `maxValues` per bucket), so SQL forms would add a second path with nothing to gain;
  (2) **KS is reported, not tested on**: its p assumes independent units — a sum Measure has none and a count only
  approximates them (with large counts any small move is "significant") — so the KS D always, and its nominal p for a
  count Measure, go into the explanation, while the test p is the empirical one above.
- **Impact**: the share of the whole that moved — share points for concentration, total variation for a shift — not
  |Δ| ÷ the Measure; `minImpact` applies to it. A bucket excluded by the calendar or empty is skipped; a dimension with
  a negative amount is not tested (a share of a sum with negative parts is not a share).
- **Tests**: `InsightScanDimensionTest` (a finding each, a steady mix finds neither, opt-in and save refusal),
  falsified by disabling each test's logic (3 of 4 red).

### 11.11 P3 Insight Profile editor, replay preview and Insight feed — as built (2026-11-26)

- **Studio ▸ Insight Profiles** (`/studio/insights`, nav `navFeature: insights`): list + detail of `insight-profile`
  components over the generic component routes; a dialog edits one (schema form + business calendar rows); save is
  `POST` / `PUT` with `If-Match`, a 422 shown verbatim.
- **Insight Types** are a free list (known names in the help), so `concentration` / `distribution-shift` (I-9/I-10, a
  parallel lane) need no UI change; the server's validator is the gate.
- **Column roles propose** Dimensions (and an empty Date column) from the Dataset's `dimension` / `temporal` columns.
- **Replay**: *Preview on history* posts the unsaved content and `periods` to `POST /insights/preview` → bars per
  period (`<inspecto-chart>`), total and average sentence, top 10 Insights. Nothing saved.
- **Feed**: `GET /insights?profile=` under the selected Profile — band badge with its word, slice, type, period, score,
  sentence; Useful / Not useful / Expected (`POST …/feedback`) (open to every viewer since 2026-11-26, §11.16); a
  404 is the empty state naming the `insight.scan` Job and linking the Jobs screen.
- **Deferred**: a "Run scan now" shortcut (the Jobs screen runs it); the Insight Feed Visualization Type for
  Dashboards; `run=all` history and band filters in the feed.

### 11.12 P3 Explain depth 2 — pairs of dimensions in the Insight scan (`BI-EXPLAIN-DEPTH2-1`) — as built (2026-11-26)

- **Where the pairs are computed (D-BI7)**: an opt-in mode of the existing `insight.scan` Job, not a Job Type of its
  own. The Profile already names the Dataset, Measure, filters, date column, grain and dimensions a pair needs; the Job
  already runs as its author, fetches through `BiSpecRunner.runAsJob` and owns a store whose sharing envelope is right.
  A second Job Type would repeat all of that and add a Job id, a manifest entry and a store with an envelope of its own.
  No new route and no new Job Type, so the new-route checklist does not apply.
- **Profile fields**: `pairs` (default `false`; `true` needs at least 2 `dimensions`) and `maxPairValues` (the cap on
  value pairs per pair of dimensions, 10–1000, default 200, as depth 1's 200 values per dimension).
- **Statements**: for every pair of the Profile's dimensions (at most 12 → 66), one `/bi/query` body grouped by both
  columns and the grained date over the last two complete buckets, through `BiSpecRunner.runAsJob` (no SQL built; a
  Measure over a masked column is refused by the vet before any pair runs; a classified value is stored as its alias).
  `limit` = 2 × `maxPairValues`; truncated, or more than `maxPairValues` distinct value pairs → that pair is listed in
  `refused` with the reason in words, never cut.
- **Statistics**: the depth-1 kernel unchanged — `Drivers.explain` over the value pairs (`"N × DATA-7D"`), cover 0.8,
  ranked with `Drivers.rank`. Additive Measures only (the Profile admits only sum and count).
- **Store**: `pairs.json` beside the Insights Parquet in `insights_<profile>/` — not a Parquet, so the Dataset (which
  reads `insights-*.parquet`) does not change shape. Keyed by the compared bucket; a run rewrites its bucket's entry
  atomically; entries computed more than 400 days ago are dropped. Readable only through `POST /insights/explain`, and
  only by a caller who may view the Dataset `insights_<profile>` (the same envelope as the feed).
- **Read**: `POST /insights/explain` takes `depth` (1 default, or 2). With 2, after the depth-1 answer, it looks for a
  saved Profile with `pairs: true` over the same Dataset, Measure, date column, grain, filters and condition whose
  Insights Dataset the caller may view, and returns its entry for the explained bucket as `pairs {profile, computedAt,
  previous, current, before, after, stale, drivers[], refused[]}`; `stale` is true when the stored totals no longer
  equal the live depth-1 totals (data restated since the run). None → `pairs: null` and `pairsNote` saying how to get
  them (save such a Profile with `pairs: true`, run its `insight.scan` after the bucket completes). A ratio with
  depth 2 is refused in words (422). A Profile the caller may not read is indistinguishable from none.
- **Not done**: a revealer still sees aliases in stored pairs (the Job
  never reveals); depth 3.
- **UI (`INSIGHT-PROFILE-PAIRS-UI-1`, 2026-11-27)**: the Profile editor authors `pairs` and `maxPairValues` (Advanced; written only when
  pairs is on, the server validator stays the gate); the Explain dialog asks `depth: 2` on demand (**Look at pairs of
  dimensions**, not offered for a ratio) and shows the pair drivers, the source Profile and scan time, `stale` in words,
  refused pairs with reasons, or `pairsNote`.

### 11.13 P3 I-17 second half — a Case or an Action Request from an Insight — as built (2026-11-26)

- **No new route.** Each Insight in the feed has **Open a Case** and **Request action…**, both on existing routes.
  `POST /objects` cannot carry an Insight (it needs ≥1 existing object to link), so the Case is opened by
  `POST /cases/from-entities` with the Insight as its one member: key `insight:<insight_id>`, Dataset
  `insights_<profile>`. The member is minted as an Incident the first time and **reused** for a second Case from the
  same Insight. Case title = the sentence (else type, slice and period); description = every piece of the evidence.
- **Evidence** (`inspecto/insights/insight-evidence.ts`): sentence, Insight Type, slice **as stored** (an alias
  stays an alias; nothing looks up a raw value), period, value / expected / delta, score and band, Profile id,
  Insight id and a link back to the feed (`/studio/insights?profile=…&insight=…`; the page now honours `profile`).
- **Request action** asks for an https Connection and an optional reason, opens the Case as above, then
  `POST /action-requests` with `caseId` (the route needs exactly one Incident or Case) and the evidence as the JSON
  `payloadTemplate` (`insight: {…}`, `case: {{case.id}}`). It waits as `pending` for a second person. If the request is
  refused after the Case opened, the Case is still shown so a retry does not open a blind second one.
- **Gates**: each action shows only when `/bootstrap` reports its module (`cases`; `actionRequests` and `cases`);
  disabled with the reason without the Manage Incidents and Cases permission (`canManageIncidents`, both) or Incident
  work (`canWorkIncidents`, the request). The server enforces both.
- **After success** the Insight shows "Opened Case <id>" (link to `/cases/<id>`) and the Action Request id (link to
  `/action-requests?id=<id>`; the page now opens the request named by `id`).
- **Links back**: `?insight=` marks that Insight in the feed (scrolled to) or says it is not in the latest run; a
  `?profile=` that names no Profile says so and shows the first. The Incident minted for the Insight reads
  "Raised for a Case from Entity insight:… in Dataset insights_…" (the route's wording is now neutral).
- **Deferred** (board rows): `{{x}}` in the sentence is interpolated by the Action Request template renderer
  (`ACTION-TEMPLATE-ESCAPE-1`); adding the Insight to an EXISTING Case (`INSIGHT-CASE-EXISTING-1`).

### 11.14 Loose ends A — as built (2026-11-26)

Operator decisions (2026-11-26, the recommended options):

- **A Mapping's base joins the sharing envelope.** `SharingEnvelope.of(store, sources)` adds, for every Mapping
  source (`origin: mapping`), its `basedOn` base, recursively through Mappings; a base shared differently from the
  rest refuses in words, a base that does not resolve (or a Mapping loop) refuses too. Callers: KPI History
  (`KpiSnapshotJobType.envelope`) and model output Datasets (`ScoreDatasets`, which already added one level). Insights
  (`InsightScanJobType.envelope`, in `features/inspecto-insights`) switched to `of(store, …)` in loose ends B (§11.16),
  pinned by `InsightScanJobTest.aProfileOverAMappingWhoseBaseIsSharedToFewerReadersIsRefused`.
- **KPI Alert Rules stamp roles and re-check access at evaluation.** The owner's held roles are stamped as `ownerRoles`
  when the owner saves (another editor never lends theirs; server-stamped, a client value is dropped at every write
  door and every raw-file import, via `JobAuthority.stampFiles`); every sweep re-checks that the owner, with those
  roles, may still view the KPI and read its Dataset; if not, the rule is skipped and ONE Incident names it and why
  (deduped while active on `kpiRuleAccess`). Spec: `okf/product/module/engine/spec-kpi-projection.md`.
- Residual: a rule file written straight to disk keeps whatever `ownerRoles` it holds (the same trust as any
  registry file written outside the routes).

### 11.15 Loose ends C — UI, as built (2026-11-26)

Operator interview 2026-11-26: the five UI loose ends below, all in one lane (`bi-ui-polish`). No new route; one
engine test.

- **Insight Feed Visualization Type** `insight-feed` (`inspecto/viz/plugins/view.plugins.ts`): a view-bound type
  (`viewKind: insight-profile`, `viewId` = the Profile id), so the Widget builder offers it with the saved-view picker
  listing Insight Profiles — only when `/bootstrap` reports `insights`. The tile
  (`studio/insights/insight-feed-widget.component.ts`, lazy through `registerVizComponent`) reads
  `GET /insights?profile=&limit=10` as the viewer: band badge with its word, slice, period, sentence, "Latest run … ·
  n of total · Open the feed". A 404 is the empty state naming the `insight.scan` Job. Read-only (feedback, Cases stay
  on the Profile screen). **Report PDF**: `ReportWidget.of` already refuses every type it cannot draw in words ("a
  report PDF draws bar, line and kpi Widgets"), failing the Run with nothing delivered — the Insight Feed gets the same
  refusal, pinned by `ReportPdfChartsTest.anInsightFeedWidgetIsRefusedInWords`; no backend change.
- **Run scan now** (`studio/insights/insight-scan-button.component.ts`) beside Edit / Delete: `GET /jobs` lists no
  parameters, so each `insight.scan` Job is read (`GET /jobs/{name}`) for the one whose `profile` names this Profile;
  then `POST /jobs/{name}/trigger` (canOperateRuns) and `GET /jobs/runs/{id}` every 2 s for up to 2 min, saying how it
  ended and reloading the feed. No Job: **Create a scan Job…** asks first, then `POST /jobs`
  `{name: insight_scan_<profile>, type: insight.scan, enabled, params: {profile}}` (canAuthorWorkbench) — no schedule;
  the Jobs screen adds one. Each button is disabled with the permission's name otherwise.
- **Feed history and band filter**: *This run / Every run* (`run=latest|all`) and *Any band / High / Elevated /
  Normal* (`band=`), applied by the server; *Every run* shows when each Insight was found.
- **Show-Me on Dashboards**: the builder's probe and KPI read moved into `ShowMeService`
  (`inspecto/viz/show-me.service.ts`) — the builder, the Dashboard tile and the cog dialog call it and
  `suggestOverlays()`, so the suggestions are the same everywhere. On a Dashboard tile (line, bar, area, combo over a
  Dataset, for a user with canAuthorWorkbench) a ✨ *Suggestions for this chart* menu reads the probe when opened;
  a click asks first ("This saves the Widget, so every Dashboard showing it changes") and saves the Widget. The cog
  dialog (`WidgetOptionsDialog`) shows the builder's suggestions; taking one sets its field (forecast, anomaly,
  compare) or adds its marker row (target, bands), kept only on Save. Deviation: Dashboards have no cog dialog of their
  own (the cog is the builder's), so the tile's menu is the Dashboard surface.
- **Suggest a compare (I-1)** — `compareSuggestion()` in `show-me.ts`, on the existing `options.compare`: a line / bar
  / area over a grained date with no break-down, not stacked or value-sorted, with no comparison on. **Same period
  last year** when the first bucket is at least 358 days (a year less a week) before the last; else **previous
  period** from 3 buckets. At most one is offered (last year wins: it removes the season the previous period keeps).
  Read from the probe's rows (the Widget's own statement).
- **Preview**: driven against a stub API (no backend jar with the add-on was built in the sandbox): the Profile screen
  created the Job, ran the scan and reloaded the feed; *Every run* + *High* filtered on the server; the Dashboard drew
  the Insight Feed tile beside a 420-day line whose menu offered last year / forecast / anomaly, and the applied
  compare was saved and not offered again; the cog dialog listed the builder's suggestions.

### 11.16 Loose ends B — Explain and feedback for every viewer, Business Insights in Basic — as built (2026-11-26)

Operator interview 2026-11-26, three decisions:

- **Explain and feedback for anyone who can view.** `POST /insights/explain` and
  `POST /insights/{profile}/{insightId}/feedback` no longer demand `canAuthorWorkbench`. Explain is allowed to whoever
  may view the Widget's Dataset — it runs as the caller through `/bi/query`'s gates, so a Dataset the caller cannot
  view is the 404 of an unknown one; feedback to whoever may view `insights_<profile>` (else the 404 of an unknown
  Profile). Authentication still applies (401). Route checklist: both left `CapabilityManifest.ENTRIES` for
  `EXEMPTIONS` — Explain as `read-shaped` (like `/bi/query`, it persists nothing), feedback as `self-service` (it
  writes only the caller's own opinion, stamped with the caller as actor, the same category as marking a
  notification read). Explain runs up to 13 statements per call, so it is now **rate-limited on the standard bucket**
  (`ControlApi.isRateLimited`), not `/bi/query`'s larger dashboard bucket. `POST /insights/preview` (replay of an
  unsaved Profile) stays `canAuthorWorkbench`: it is authoring. UI: the tile's *Explain this change* action and the
  feed's Useful / Not useful / Expected buttons no longer check `canAuthorWorkbench`. Pinned by
  `ControlApiExplainTest.anyoneWhoCanViewTheDatasetMayExplain`, `ControlApiExplainDepth2Test` and
  `ControlApiInsightsTest.feedbackIsGatedRecordedAndLaidOverTheFeed` (viewer 200, unviewable 404, anonymous 401).
- **D-BI13 — Business Insights in every edition, Basic included** (reverses D-BI3's Standard-and-above placement).
  `features/inspecto-insights` moved into the default reactor (Basic = the default build), `insights` and the
  `businessInsights` add-on moved from `offerings/standard.toon` to `offerings/basic.toon` (Standard and Advanced
  inherit it through `includes`), `package.ps1` stages `inspecto-insights.jar` outside the Standard-and-up branch.
  The module needs only core (processor, engine, analytics, etl — `BiSpecRunner`, the Job service, the Component
  Store), all in Basic. Opening a Case or an Action Request from an Insight stays where those modules are (Standard
  and up); the feed hides those actions when `/bootstrap` does not report them.
- **D-BI4 owner tuning within Builder bounds is dropped**: whoever may save an Insight Profile tunes it. No bounds
  model will be built; removed from the deferral lists here and in the hub.

## 12. Board row

Filed 2026-11-18 as **one** row, `BI-INSIGHTS-1` (P2, `BACKLOG.md` §3.6) — the board allows a lane at most one
row, so the phases P0…P5 are its slices, as `PRODUCT-ASSEMBLY-1` carries PA-0…PA-10. P0 is startable now. When
P3 starts, the add-on gets its Product Map Items (`module.insights`, an `addon` entry) and its hub, and the row's
tags move to them. Commit scope per slice = the Product Map Item it changes (`feat(engine): …`,
`feat(analytics): …`, later `feat(insights): …`).

## References

- Inventory sweeps of 2026-11-18 (UI viz and dashboards, BI backend, analytic modules), spot-checked:
  `query/KpiEvaluator.java`, `control/KpiRoutes.java`, `agent/skill/ReportNarrativeSkill.java`.
- The path from ingest to KPI and Report: [`okf/product/module/analytics/architecture-analytics.md`](../okf/product/module/analytics/architecture-analytics.md) §8.
- Kernel definitions: [`okf/product/module/analytics/spec-analytics.md`](../okf/product/module/analytics/spec-analytics.md).
- Steps: [`okf/product/module/elt/spec-step-catalog.md`](../okf/product/module/elt/spec-step-catalog.md).
- Predictive surface: [`okf/product/module/predictive/spec-surface.md`](../okf/product/module/predictive/spec-surface.md).
- Studio guide: [`okf/product/layer/product/user-guide-studio.md`](../okf/product/layer/product/user-guide-studio.md).
- Competitive gaps: [`okf/product/layer/product/brochure-competitive-matrix.md`](../okf/product/layer/product/brochure-competitive-matrix.md).
- Method background: Bhagwan et al., *Adtributor* (NSDI 2014) for multi-dimensional root cause; Kitagawa (1955)
  shift-share decomposition; Benjamini & Hochberg (1995) false discovery rate.
