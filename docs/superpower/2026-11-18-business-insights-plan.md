# Business insights in Widgets and Dashboards — research and design

> **Status: DESIGN APPROVED 2026-11-18 — D-BI1, D-BI2, D-BI3 signed (operator, recommended options); D-BI4…D-BI10
> signed 2026-11-24 (operator, recommended options); P0 I-3 KPI History built 2026-11-24 (§11.1, D-BI11, D-BI12).** Every decision in §11 is closed. Board row: `BI-INSIGHTS-1`
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
| Alert Rule on a KPI id or on a projection | ABSENT | rules take `dataset` + `measure` |
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
7. **Who may do what (D-BI4).** Builders author Profiles; owners may tune knobs within bounds the Builder set;
   Business users read Insights, give feedback and arm Alert Rules they are allowed to.

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
| D-BI3 | Edition placement | ✅ **SIGNED 2026-11-18: (a)** overlays and KPI History in Standard BI; Insight Profiles / scan / explain as a "Business Insights" add-on (Standard+); language features with AI Assist; scenarios Advanced · (b) all in one add-on |
| D-BI4 | Who may tune | ✅ **SIGNED 2026-11-24: (a)** Builders author, owners tune within bounds, Business users give feedback · (b) Builders only |
| D-BI5 | Language features in the first release | ✅ **SIGNED 2026-11-24: (a)** no — templated sentences first, Brief in P4 · (b) yes |
| D-BI6 | Pilot | ✅ **SIGNED 2026-11-24: (a)** `business-assurance` + `telco-ra` revenue · (b) `mobile-money` transaction value |
| D-BI7 | Compute placement | ✅ **SIGNED 2026-11-24: (a)** overlays at query time, scans and depth-2 explanations as Jobs · (b) everything precomputed |
| D-BI8 | Insights stored as an auto-registered Dataset | ✅ **SIGNED 2026-11-24: (a)** yes · (b) object store only |
| D-BI9 | Chart Markers implementation | ✅ **SIGNED 2026-11-24: (a)** a Chart.js overlay plugin behind the viz contract · (b) custom drawing |
| D-BI10 | KPI History grain and retention | ✅ **SIGNED 2026-11-24: (a)** the KPI's own grain, 3 years · (b) day only, retention per Space |
| D-BI11 | KPI History registration under a Dataset approval policy | ✅ **SIGNED 2026-11-24 (operator):** the `kpi.snapshot` Job registers its Dataset as a **system Dataset regardless of the policy** (`origin: system`); the gate is lifted for this Job only |
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
