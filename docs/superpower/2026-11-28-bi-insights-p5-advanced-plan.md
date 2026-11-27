# Business insights P5 — Advanced: Scenarios, Customer Segment migration, Network value (`BI-INSIGHTS-1` P5)

> **Status: DESIGN, awaiting the operator — D-BI25…D-BI36 are open (recommended option first in every row of §11);
> nothing is built.** Parent: [`2026-11-18-business-insights-plan.md`](2026-11-18-business-insights-plan.md) (§4 principles,
> §6 rows I-13 / I-15 / I-16, §8 architecture, §9 "P5 Advanced", §10 traps, §11 decisions; D-BI3 "scenarios Advanced",
> D-BI7 compute placement, D-BI8 outputs as Datasets, D-BI13 Business Insights in every edition). House format and
> neighbour: [`2026-11-27-bi-insights-p4-language-plan.md`](2026-11-27-bi-insights-p4-language-plan.md). Items:
> `module.insights`, `module.analytics`, `module.engine`, `module.predictive`, `layer.product`. Board row
> `BI-INSIGHTS-1` stays **one** row; P5 is its slices SC-0…, SM-0…, NW-0…. Every code fact below was checked on
> 2026-11-28 with CodeGraph and direct reads; where a fact could **not** be checked it says so (§12).
> **Two claims of the parent plan do not survive the code** and change the design: I-16 does not need the Link Analysis
> ports to write back (§6.2), and I-15 needs **no new Visualization Type** to be useful (§5.4).

## 1. Scope

| In | Out (stays where it is) |
|---|---|
| **I-13 Scenarios** — a what-if on a Measure: the user states levers (assumptions) and sees the Measure that would result, as a waterfall, with the assumptions in words | A model that learns elasticities or predicts behaviour; a Scenario written back to data; a Scenario as a Dashboard Widget or a Report page |
| **I-15 Customer Segment migration** — which entities moved between Customer Segments from one `predict.segment` run to the next, and the value that moved | A **sankey** Visualization Type (priced and deferred, §5.4); a `segment-migration` **Insight Type** in the scan (reserved word, not built) |
| **I-16 Network value** — graph measures (PageRank, community, betweenness) stored per entity as a Dataset beside one business Measure, so "revenue inside a community" is an ordinary Widget | A graph written back from an Investigation's Working Set; community tracking over time; a graph Widget in a Dashboard |

Binding rules carried from the parent (§4): numbers are computed by the kernel or DuckDB; every figure is recomputable
and shows its evidence; **unknown is absent, never 0** (`KPI-UNKNOWN-1`); masking holds everywhere; every compute is
bounded and **refuses past its cap in words**; a model is never needed (nothing in P5 calls one).

## 2. What is built and what is absent (verified 2026-11-28)

| Capability | State | Where |
|---|---|---|
| **I-4 drivers** — contribution, Jensen-Shannon surprise, fewest values covering a share | BUILT, pure JVM over `(value, before, after)` elements | `platform/inspecto-analytics/.../drivers/Drivers.java:25,52` |
| **I-5 mix vs rate** — exact shift-share identity; a slice with no denominator in one period takes the other period's rate | BUILT, pure JVM over `(value, num0, den0, num1, den1)` slices | `.../drivers/ShiftShare.java` `decompose` |
| `POST /insights/explain` — stateless; one grouped statement per dimension through `BiSpecRunner.run(api, ex, …)` **as the caller** (masked rows, the same gates and caps); caps `MAX_DIMENSIONS 12`, `MAX_VALUES 200`, `MAX_RELATED 10`, lookback ≤ 60; refuses a non-additive aggregate in words; any viewer of the Dataset | BUILT | `features/inspecto-insights/.../ExplainRoutes.java:59,112` |
| **A forward use of those two identities** (apply a stated change instead of an observed one) | ABSENT — both only read two observed periods | grep `ShiftShare` / `Drivers` callers: `ExplainRoutes`, `PairExplain` |
| **I-12 projection** — value to date + Holt-Winters of the days left, interval from a rolling-origin backtest, absent with a reason under two seasons | BUILT, but **only for a KPI** (`KpiEvaluator.project` reads the KPI's own daily series); the pure `PeriodProjection.project(series, toDate, horizon, …)` is reusable | `.../forecast/PeriodProjection.java:33,89`; hub `okf/product/module/engine/spec-kpi-projection.md` |
| A projection for an **arbitrary Measure** (no KPI) | ABSENT — would need a daily bucketed statement of the Measure (the `ChartOverlays` / `KpiEvaluator.dailySql` shape) | — |
| A saved what-if (component kind `scenario`, route, UI) | ABSENT; `ComponentRegistry` knows `insight-profile` the same way a new kind would be added | `platform/inspecto-engine/.../pipeline/ComponentRegistry.java:79`, `ComponentStore.java:90` |
| The word **Scenario** or **Lever** in the vocabulary | ABSENT from `docs/GLOSSARY.md` (free) | grep |
| **Segmentation Model** (`segmentation-model`) and Job `predict.segment`: RFM bins per run over the run's own entity population, label of the first matching row | BUILT; `maxEntities` default 200 000 | `features/inspecto-predictive/.../SegmentEvaluator.java:32`, `RfmSegmenter.java:23`, `SegmentJobType.java:65` |
| Segment **history**: one Parquet run file per run, **retention off** (`ScoreRunFiles.Retention.OFF`), plus `_latest`; every row carries `entity_key`, `segment`, the three scores and raw `recency_days` / `frequency` / `monetary`, `as_of`, `model_version`, `run_id` | BUILT — so a transition matrix **can** be built from history that already exists | `SegmentEvaluator.java:117–142`; `ScoreColumns.HEAD/TAIL` |
| Segment outputs registered as system Datasets (`customer_segments_<model>` + `_latest`), `entity_key` classified, envelope = model + inputs, never fails the run | BUILT (I-14) | `platform/inspecto-engine/.../alert/ScoreDatasets.java`; hub `spec-model-output-datasets.md` |
| `ScoreKind` — an open record (component kind, output prefix, owner marker), not an enum | BUILT: a second kind is one more instance | `platform/inspecto-analytics/.../score/ScoreKind.java` |
| A transition / migration matrix, anywhere in the kernel or Jobs | ABSENT — grep `transition`, `markov` finds only unrelated state machines | `platform/`, `features/` |
| Visualization Types `heatmap` (a real `<table>`: one dimension × one dimension × one measure) and `treemap`; **no sankey**, no sankey package in `package.json` (chart.js 4, G6) | BUILT / ABSENT | `inspecto-ui/src/app/inspecto/viz/plugins/heatmap.plugin.ts`, `inspecto-ui/package.json` |
| Report PDF draws `bar`, `line`, `kpi` and the `insight-brief` page; everything else is **refused by name** | BUILT | `platform/inspecto-engine/.../job/ReportWidget.java:28` |
| Graph algorithms (PageRank, Louvain, label spread, betweenness, k-core, triangles, suspicion) — pure, parity-tested, `canonical-v1` order; the module reaches nothing but the JDK | BUILT in `platform/inspecto-graph`; the **index-backed** engine answers only neighbourhood, ego network and seed degree — every whole-graph algorithm runs **in memory** over a materialised graph | `platform/inspecto-graph`, `la/inspecto-la-storage/.../SqlGraphEngine.java`, `la/inspecto-la-core/.../Algorithm.java:69–91` |
| `GraphRunService` — runs on **an Investigation's Working Set**, per owner, in daemon threads, budgeted (`GraphBudget`, ceilings `graph_run.ceiling_*`), result cached **raw**, masked by the route on the way out; JSON only | BUILT; **no Job, no Dataset writeback** | `la/inspecto-la-core/.../GraphRunService.java`, `la/inspecto-la-api/.../GraphResultJson.java` |
| The `la.detect` Job (Investigation Alert Rules on a schedule) — the one LA-side Job; it lives **in the engine** and reaches LA only through a `ServiceLoader` SPI; the engine does not depend on `la-core` (guarded) | BUILT | `platform/inspecto-engine/.../job/LaDetectJob.java`, `.../linkindex/LinkIndexBuilder.java`, `tools/check-module-deps.mjs` |
| A Job that builds a **graph from a Dataset**, runs an algorithm and stores per-entity results | ABSENT | grep `GraphInput` callers: `la-core`, `la-api`, `la-storage` only |
| Edition placement: `la-core`, `la-api`, `geo-link` **and `insights` are in Basic**; `predictive` is a Standard add-on | BUILT | `offerings/basic.toon:9–17`, `offerings/standard.toon:46–48` |

Three consequences shape everything below:

1. **I-13 and I-15 are mostly reuse.** A scenario is the same two identities as Explain run forward; a migration is the
   existing segment history joined to itself. What is new is small and lives in the kernel.
2. **I-16 is the expensive one.** It is a new component kind, a new Job, a new output kind and a masking question (§6.4);
   the graph algorithms themselves already exist and are pinned.
3. **Edition spread.** Scenarios and Network value can ship in **Basic**; migration exists only where the Predictive
   Analytics add-on (Standard and up) writes segment history.

## 3. Vocabulary (checked against `docs/GLOSSARY.md`; the ⛔ ban list applies to every string this plan adds)

| Term | Use | Note |
|---|---|---|
| **Scenario** (new, D-BI26) | A what-if: a Measure, a period and a list of **Levers**; its result is the Measure that follows from them | ⛔ not "simulation", "plan" or "forecast" — a **Forecast** is a model output (GLOSSARY, Prediction); a Scenario predicts nothing, it computes what *stated* assumptions imply |
| **Lever** (new) | One assumption: a dimension value (or a pair) and a change to its volume or rate | A word the UI shows ("+5 % on `DATA-7D`"); not a component |
| **Customer Segment migration** (new, D-BI30) | The entities whose Customer Segment differs between two consecutive runs of one Segmentation Model, with the value that moved | ⛔ the bare word *Segment* never stands for this (GLOSSARY §Segmentation Model: it is a plugin record kind); the reserved Insight Type id `segment-migration` is unchanged |
| Dataset `customer_segment_migration_<model>` (+ `_latest`) | Its output, a model output Dataset | The prefix is an engineering name, as `customer_segments_` |
| **Network Model** / **Network Score** (new, D-BI32) | The component kind (a **Type**) that declares which links to read and which graph measures to store; its Instance is one entity's **Network Score** row | Same family as **Churn Model / Churn Score**. ⛔ not the Link Analysis *suspicion score* (GLOSSARY, Risk Score), ⛔ not "risk" and not "fraud score" — a community is a **community**, never a "ring" or "cluster" (⛔ cluster: a fuzzy-match group, GLOSSARY §Entity matching) |
| **Network value** | UI wording for a Widget over a Network Score Dataset ("revenue by community") | Not a concept of its own |

A Scenario, a migration and a Network Score are about **Measures**, **Datasets** and **Entities**; an unusual finding is
an **Insight**, never an Incident or an Alert, exactly as P4 §3.

## 4. I-13 Scenarios — design

### 4.1 What a Scenario is, and what it is not

A Scenario is **arithmetic on the user's own assumptions** over the decomposition Explain already computes. It never
infers elasticity: "+5 % price on `DATA-7D`" changes that slice's revenue by exactly 5 %; if the user also believes
volume falls 2 % they state a second lever. The result says so in words under the chart: *"Assumes the levers do not
affect each other or any other value; computed from <period> as stored."* The parent's "linear driver model" is this and
nothing statistical (D-BI26; a fitted elasticity would be a claim about cause, which the parent §10 forbids).

### 4.2 The model

Let the Measure over one period be cut by dimension *d* (or, for two lever dimensions, by the joint grid *d1 × d2*) into
slices *c*, read **as the caller** exactly as Explain does.

| Measure | Lever kinds | After |
|---|---|---|
| additive (`sum`, `count`) | `scale` ±x % of the slice's value · `add` ±a in the Measure's unit | `M_c' = M_c·(1+x)` or `M_c + a`; `M' = Σ M_c'` |
| ratio `R = N ÷ D` of two additive Measures | `rate` ±x % or ±p points of the slice's rate · `weight` ±y % of the slice's denominator (volume) | `D_c' = D_c·(1+y)`, `r_c' = r_c·(1+x)` (or `+p`), `N_c' = r_c'·D_c'`; `R' = ΣN_c' ÷ ΣD_c'` |

The effect per slice is then **not new maths**: the pair (base slices, after slices) is fed to `Drivers.explain`
(additive) or `ShiftShare.decompose` (ratio — its exact mix + rate identity), so the waterfall is the one Explain draws,
baseline → one bar per lever-carrying slice → scenario. New kernel code is therefore only `Scenario.apply(slices,
levers)` plus its refusals (SC-0). Levers on **up to two dimensions** are composed over the joint grid (a lever on
*region = North* multiplies every cell with that region), capped at **2 000 cells**; past that the route refuses in
words and names a coarser dimension.

### 4.3 Baseline

| Basis | Meaning | Slice |
|---|---|---|
| `last-period` (default) | The last **complete** bucket of the grain before `at`, as stored: "if March had been like this" | SC-1 |
| `projection` | The period in progress (`sum` / `count` only): `PeriodProjection` over a daily series of the Measure, the projection **allocated to slices by the last complete period's shares** (stated: *"assumes the mix of <period>"*), its interval shown beside the result | SC-3, optional |

Absent, never 0: a lever on a value with no row in the base period is refused ("`DATA-7D` has no value in
<period>"); a ratio slice with no denominator has no rate to lever; a projection under two seasons or fewer than
`MIN_ORIGINS` (8) backtest origins says why and offers `last-period` (the I-12 reasons, reused verbatim).
Non-decomposable aggregates (median, distinct count, percentile) are refused in words, exactly as `requireAdditive`.
A lever that would drive a slice's numerator or denominator below zero is refused.

### 4.4 Route and gates (module `insights`)

`POST /insights/scenario` `{dataset, field, grain, at, measure | ratio, levers:[{dimension, value, kind, amount}], basis}`
→ `{measure, basis, period, before, after, delta, pct, effects:[{dimension, value, before, after, delta}], mix?, rate?,
assumptions:[…words…], refused:[…]}`. Stateless, nothing stored, nothing logged but the standard audit line.

| Gate | Rule |
|---|---|
| Caller | Authenticated; **anyone who can view the Dataset** (404 otherwise), as Explain after §11.16; standard rate-limit bucket |
| Capability | None literal (`read-shaped` exemption, like `GET /insights` and `POST /insights/explain`) — **an open point**: unlike Explain, a Scenario is more an authoring act; if the operator wants it gated on `canAuthorWorkbench` (as `POST /insights/preview`), say so in D-BI29 |
| Compute | One grouped statement through `BiSpecRunner.run` per lever-dimension set; the same `SqlGuard`, sandbox, row caps and masking as `/bi/query`; ≤ 10 levers; ≤ 2 000 cells |
| Masking | A lever value is matched against the value **the caller sees** (an alias for a classified dimension without reveal). Whether the alias is the same token on every call is **not verified here**; SC-1 pins it with a test, and if it is not stable the route **refuses a lever on a masked dimension for a caller without reveal** (fail closed) |
| Route checklist | literal capability decision, armed 401 / 404 / 200 test, regenerate `openapi-v1.json`, `render-api-routes.mjs` (CLAUDE.md "new route") |

### 4.5 UI

A **What if…** tab in the Explain dialog (`inspecto-ui/.../studio/widgets/explain.dialog.ts`, request builder
`viz/explain-request.ts`, service `api/insights.service.ts`): pick the dimension(s), add a lever per value (a dropdown
of the values Explain already listed), the waterfall, the assumptions as text, **Reset**. Drafts stay in the dialog's
state; closing it discards them (D-BI27). Number words, not colour alone (axe). No Dashboard Widget, no PDF.

### 4.6 Persistence (D-BI27)

A saved Scenario would be a component kind `scenario` (owner, shares, versions, `ConfigSafetyValidator`, the Drafts
path) — the `insight-profile` recipe. It would arm nothing (a Scenario triggers no Job and no Alert Rule), so the
"armed by save" principle of the parent §4.4 buys nothing here; the value is sharing a named what-if in a meeting.
Recommendation: **draft only in P5**, the kind as slice SC-4 held for demand.

## 5. I-15 Customer Segment migration — design

### 5.1 The data is already there

`predict.segment` appends one history file per run and never prunes (`Retention.OFF`). Each row holds the entity's
segment and its raw R, F, M values. Two consecutive run files are therefore enough to pair every entity **with itself**
across runs. The pairing needs the entity key, which is **classified**; doing it inside a Job as the model's owner (the
way `predict.segment` itself reads raw keys) keeps the keys out of every reader's way, and what is stored carries the key
under the same classification (§5.2).

### 5.2 Output shape (D-BI30)

A new `ScoreKind` instance, written **through the existing score output contract** by `predict.segment`, right after
the segment output and under the same "never fails the run" rule (a problem is named in the run message):

| Column | Role | Meaning |
|---|---|---|
| `entity_key` (contract head) | entity, classified as the segment output's | the entity that moved |
| `from_segment`, `to_segment` | dimension | the labels in the earlier and later run; the pseudo-labels **`(new)`** (no row earlier) and **`(gone)`** (no row later) |
| `moved` | dimension (`true`/`false`) | `from_segment <> to_segment` — so a Widget can show moves only |
| `from_as_of`, `to_as_of` | temporal | the two runs' `as_of` dates |
| `monetary_from`, `monetary_to`, `monetary_delta` | measure | the entity's windowed value at each end and its change — "value moved" is `sum(monetary_to)` of a transition, or `sum(monetary_delta)` |
| tail (contract) | | `model_version`, `run_id`, `scored_at` of the **later** run |

Why entity-level rows and not an aggregated matrix: it reuses the whole I-14 machinery unchanged — history file per
run, atomically swapped `_latest`, `ScoreDatasets.register` (column roles, key classification, sharing envelope, system
Dataset regardless of an approval policy) — and any matrix is then one `/bi/query` (`count(entity_key)` by
`from_segment × to_segment`), which the existing **heatmap** draws as is. Storage: one more row per entity per run, i.e.
the segment history's size again (capped by the model's `maxEntities`, default 200 000). The alternatives are priced in
D-BI30.

**Comparable runs only.** A pair is stored only when both runs carry the **same `model_version`** (the 12-hex hash of the
model without its envelope): after a bin or label-table edit the labels and edges differ and "moved" would be an
artefact. A changed model writes **nothing** for that pair and says so in the run message ("model changed since the
previous run: no migration for this pair"). `RfmSegmenter` bins are **quantiles of each run's own population**, so a
migration is a change of *relative* rank, never of absolute level — the user guide and the Widget's caption say
*"relative to the other entities in each run"*.

**Backfill.** The history exists before this feature does. A run param `backfill: true` (config only, never a trigger
argument) replays every consecutive pair of the existing run files, oldest first, capped at **24 pairs** per run
(a long history takes several runs, said in the message). Without it only the newest pair is written.

### 5.3 Compute placement

Per D-BI7: precomputed by the Job, read back as a Dataset. The pairing is one DuckDB full outer join of two run files
on `entity_key` (the kernel's **SQL form**) with a **JVM form** over the same inputs and a parity test, as every kernel
statistic (parent §4.3, the data-ops duplication guard). Caps: the model's `maxEntities` bounds both files; the label
table (≤ 64) bounds the matrix.

### 5.4 Presentation — no new Visualization Type needed

| Form | Built from | PDF |
|---|---|---|
| Migration matrix (from × to, count or value) | existing **heatmap** over `customer_segment_migration_<model>_latest` | refused by name today (`DRAWN`), as every non-bar/line/kpi Type |
| Moves into / out of one segment | existing **bar**, `series = to_segment`, `x = from_segment` (stacked) | **drawn** by the Report Job — the form to put in a Report |
| Value moved per transition | existing **table** | refused by name |
| **Sankey** | new Visualization Type (a custom Angular component, as `heatmap.component.ts` is 264 lines and `treemap.component.ts` 266; **no chart library** in the SPA draws one, and adding `chartjs-chart-sankey` is a third-party dependency **not evaluated here**) | refused by name, like `insight-feed` |

Recommendation (D-BI31): **no sankey in P5.** A sankey is the picture people expect, but the matrix answers the same
question with an accessible table the SPA already has, and the Report form needs bars either way. It stays a
separately priced slice (SM-3, hold: demand).

## 6. I-16 Network value — design

### 6.1 What is absent, precisely

The graph algorithms exist and are pinned; what is absent is a **batch consumer** (§2). Today a graph measure is
computed for one analyst over one Investigation's Working Set, held in memory, returned as JSON, masked on the way out,
and gone (cached ten minutes, `GraphRunService.Limits`). To join "PageRank / community / betweenness" to a business
Measure the result must exist **as a Dataset**, scheduled, readable by a Widget.

### 6.2 The parent's "through the LA ports" does not hold (D-BI32)

The LA ports (`DatasetProvider`, `CasePort`, `UploadLandingPort`, …) are how **Link Analysis reads and acts on the
platform**, implemented by the `geo-link` bridge. Writeback goes the other way, and nothing forces it through LA:
`platform/inspecto-graph` is a JDK-only library that `inspecto-analytics` already depends on, and the engine **already
hosts the LA-adjacent Job** (`la.detect`) behind a `ServiceLoader` SPI because the engine may not import `la-core`.
So the recommended design is a Job that builds the graph **from an edge Dataset** (two entity columns of one Entity Type)
and calls `inspecto-graph` directly, writing through the **score output contract** like every other model kind. This
costs one thing and gains another, both to be said in the user guide: it is **not** an Investigation's Working Set (no
analyst curation, no temporal cut, no link kinds beyond what the edge Dataset holds), and it is **scheduled**,
bounded and shared like any model output. Alternatives in D-BI32.

### 6.3 The model and the Job

Component kind **`network-model`** (the `churn-model` / `segmentation-model` recipe: `ComponentRegistry` dir, validator,
`ScoreKind("network-model", "network_scores_", ".network-score-output")`, Job `network.score`, `ScoreDatasets.register`):

| Field | Meaning |
|---|---|
| `entityType` | the Entity Type of both link columns (one type per model in v1; two types, e.g. subscriber ↔ device, would need a node namespace and is **deferred**) |
| `links` | `{dataset, from, to, window?}` — the edge Dataset, its two columns and an optional trailing window on its temporal column |
| `measures` | any of `pageRank`, `communities` (Louvain), `betweenness` (opt-in) |
| `value` | optional `{dataset, key, measure}` — one business Measure per entity over the same window (e.g. `sum(amount)` by agent), read like `SegmentEvaluator` reads its windowed spec |
| `maxNodes`, `maxEdges` | default 50 000 / 500 000 (the `GraphRunService` defaults), ceilings from `graph_run.ceiling_*`; betweenness only up to 10 000 nodes (the algorithm's own `JOB` threshold) |

Columns after the contract head: `pagerank` (measure), `community_id` (dimension), `community_size` (measure),
`betweenness` (measure, null when not asked), `value` (measure, null when the entity has no row — absent, never 0),
`degree` (measure), `as_of`. Network value is then plain BI: *revenue in the flagged community* = a Widget over
`network_scores_<model>_latest`, grouped by `community_id`, `sum(value)` (and `count(entity_key)`), no join in the
query layer (`MeasureCompiler` has none) — the join to the business Measure is made **inside the Job**, in DuckDB, over
the Dataset's relation SQL under `SqlSandboxPolicy`.

### 6.4 Identity and masking (D-BI34) — the trap

The two existing patterns disagree and the choice matters. **Insights** read through `BiSpecRunner.runAsJob` as a
reader who may not reveal, so classified keys come back as **aliases** and everything stored is alias-only — safe, but
nothing stored can be joined back to a raw key. **Model outputs** (`predict.segment`) read the Dataset's relation SQL
directly as the model's owner and store the **raw key under a classified column**, masked at read time. Network Scores
must be joinable (that is their point — a Case, a Risk Score, another Dataset share the key), so the recommendation is
the model-output pattern: raw read as the model's owner, `entity_key` classified with the strictest of the Entity Type
and the class of both link columns and the value key, readable only by who may read the model **and** every Dataset it
reads (`SharingEnvelope`, including a Mapping Dataset's base), narrowed on any refusal, never failing the run. Two
traps to pin in tests: **`community_id` must not embed a member key** (a label like `c1`, ranked by size then by the
canonical-v1 order of its members — never "the smallest member id", which would put a classified value in an
unclassified column), and a community of one or two entities is still returned (the Dataset's envelope already covers
the audience; small-cell suppression is **not** a P5 rule, and is flagged in §12).

### 6.5 Honest limits to print in the guide

Community ids are **not stable between runs** (Louvain labels are arbitrary and the graph changes); P5 does not match
communities across runs (D-BI35). A graph past `maxNodes` / `maxEdges` **fails the run in words** and names the cap —
the way `EntityCap` fails a segment run — it is never sampled silently. Betweenness is exact and costly: it is an
opt-in measure, refused above 10 000 nodes. PageRank in the in-memory engine takes no edge weights (the `Algorithm`
flag is false), so P5 ranks an unweighted graph; the edge Dataset's `count`, if any, is ignored and said so.

### 6.6 Home module and edition (D-BI33)

Recommendation: put the kind and the Job in **`features/inspecto-insights`** (module `insights`, already in **Basic**,
already depends on engine and analytics; add `inspecto-graph`). That avoids a new module and its Product Map Item and
hub (PRODUCT-DOCS-HUB-1) and gives Network value the same edition as the rest of Business Insights. The cost is
conceptual: the insights add-on gains a model kind that is not an Insight. The alternatives are a new
`la/inspecto-la-network` module (cleaner, needs offering, product-map and hub entries) or the `geo-link` bridge (already
over-loaded). `tools/check-module-deps.mjs` must stay green (NW-1 runs it; `inspecto-graph` reaches nothing, so adding
it is expected to pass, **not run here**).

## 7. Edition and module boundary (the answer to "what lands in Basic")

| Piece | Module | Basic | Needs |
|---|---|---|---|
| I-13 Scenario: kernel `Scenario.apply` | `inspecto-analytics` | ✅ | — |
| I-13 route + dialog tab | `inspecto-insights`, `inspecto-ui` | ✅ (D-BI29 asks whether to honour D-BI3's "scenarios Advanced") | — |
| I-15 migration kernel (SQL + JVM) | `inspecto-analytics` | ✅ library | — |
| I-15 migration **output** | `inspecto-predictive` (`predict.segment`) | ❌ — Standard and up | the Predictive Analytics add-on; no Basic user has a segment history |
| I-15 on screen | existing heatmap / bar / table | with the output | — |
| I-16 kind, Job, output | `inspecto-insights` (D-BI33) | ✅ | the edge Dataset, built by the Builder (a link table, or a Pipeline output) |
| I-16 on screen | existing bar / table / KPI | ✅ | — |
| Report PDF | unchanged | bar only; heatmap and any new Type refused by name | — |

No P5 piece needs `-Pai` (nothing calls a model) or the Link Analysis modules at build time.

## 8. Edge cases

| Case | Behaviour |
|---|---|
| Scenario with no levers | Returns the base unchanged (`delta 0`), says "no levers" — not an error |
| Two levers on one slice | Compose multiplicatively and are shown as one effect row with both levers in its words |
| A lever's value absent from the base period, or a Measure the caller may not read | Refused in words per lever; the others still compute (`refused:[…]`), like Explain's refused dimensions |
| Ratio whose base denominator is 0 | Refused: "no ratio to start from" (`ShiftShare`'s own message) |
| A Scenario on a masked Measure | Refused as `/bi/query` refuses it |
| First `predict.segment` run | No previous file: no migration written, message "first run: nothing to compare" |
| Entities in only one run | `(new)` / `(gone)` pseudo-labels, `monetary_from` or `monetary_to` null — never 0 |
| Runs two grains apart or irregular `as_of` | Written as stored (`from_as_of`, `to_as_of`); the Widget shows both, the guide says migrations are per run, not per day |
| `maxEntities` exceeded in a segment run | The segment run already fails; no migration is attempted |
| Network graph with a self-loop or a duplicate edge | The input `GraphInput.of` rule: a closed graph, dangling edges dropped **and counted** in the run message |
| Edge column value not in the value Dataset | `value` null for that entity (absent), counted in the message |
| A run over `maxNodes` | Fails the run, names the cap and the measured size; the previous `_latest` is kept |
| Two models over one edge Dataset | Independent outputs; the envelope of each follows its own inputs |

## 9. Slices, each with its tests

One lane per slice (CLAUDE.md "one lane per push", fresh worktree off `origin/master`, ends at push). **Three tracks are
independent and may be built in parallel**: SC, SM and NW share no files except the generated ones listed under
"Shared touch points". Inside a track the order is by dependency.

| Slice | Track | Parallel with | Delivers | Tests (what proves it) |
|---|---|---|---|---|
| **SC-0** analytics | I-13 | SM-0, NW-0 | `drivers/Scenario.apply(slices, levers)` + refusals; additive and ratio forms; no SQL | Identity tests: no levers ⇒ base exactly · a `scale` on every slice equals the same scale on the total · applying the **observed** slice rates as `rate` levers reproduces `ShiftShare`'s rate term and the observed weights as `weight` levers its mix term (round trip with `ShiftShare.decompose`) · refusals (no base slice, zero denominator, below-zero) · property test over random slices · **falsified** by breaking each identity |
| **SC-1** insights | I-13 | SM-1, NW-1 | `POST /insights/scenario`, basis `last-period`, joint grid ≤ 2 dims | Real HTTP: 401 / 404 hidden Dataset / 200; every §4.4 gate (cell cap, ≤ 10 levers, non-additive refused in words); a classified dimension: lever by the displayed alias **and** a second call returns the same effect (the alias-stability probe — if red, switch to refuse-for-non-revealer and keep this test as its proof) · parity with Explain: a lever that equals an observed change reproduces Explain's delta for that slice · route checklist (`CapabilityManifest`, `openapi-v1.json`, `render-api-routes.mjs`) |
| **SC-2** UI | I-13 | SM-2 | *What if…* tab in the Explain dialog, waterfall, assumptions text, Reset | Vitest: request builder, refusals in words, no colour-only signal; axe; drive the preview (stub API) as §11.15 did; the dialog discards drafts on close |
| **SC-3** engine + insights | I-13 | — | basis `projection` (sum / count): daily bucketed statement of the Measure, `PeriodProjection`, allocation by last-period shares, interval beside the result | The I-12 absence reasons reproduced verbatim · a golden series with a known landing · allocation sums to the projection · optional slice: drop it if `KpiEvaluator.dailySql` cannot be generalised without touching KPI code (§12) |
| **SC-4** component (hold: demand) | I-13 | — | kind `scenario`, save / share / version through the gated path | The `insight-profile` recipe's tests; deliberately not scheduled |
| **SM-0** analytics | I-15 | SC-0, NW-0 | `SegmentMigration` SQL + JVM forms (full outer join of two run relations on the key, pseudo-labels, deltas) | **Parity test** SQL form vs JVM form on a fixture with new / gone / moved / same entities · null (not 0) for the missing end · no key in an error message |
| **SM-1** predictive | I-15 | SC-1, NW-1 | `ScoreKind` for the migration output; `predict.segment` writes the newest pair, `backfill` replays ≤ 24 pairs; registers the Dataset; differing `model_version` writes nothing and says so | `SegmentJobTest` extended: first run writes nothing · second run writes the pair · model edited between runs writes nothing and the message says why · registration failure never fails the run (the I-14 rule) · `entity_key` classified as the segment output's · envelope equals the segment Dataset's · backfill idempotent over two invocations · **whole-feature falsification**: disable the pairing, expect red. **Touches `ScoreDatasets` / `ScoreKind` callers — build in the same lane window as NW-1 only if serialised** |
| **SM-2** docs/template | I-15 | SC-2 | A migration Dashboard (heatmap + stacked bar + table) in a pilot Space Template **only if one already carries a segmentation model** (not verified, §12), else a recipe in the user guide | Template golden / install test; the Report PDF of the bar form (existing `ReportPdfChartsTest` style) |
| **SM-3** UI (hold: demand) | I-15 | — | Visualization Type `sankey` (custom component) with a table fallback | Plugin tests like `treemap.plugin.spec.ts`; axe; **PDF refusal in words** (`anInsightFeedWidgetIsRefusedInWords` pattern) |
| **NW-0** graph + analytics | I-16 | SC-0, SM-0 | `NetworkScorer`: edge rows → ranked rows (`pagerank`, `community_id` by size rank, `degree`, optional betweenness), caps, deterministic order | Golden against `LaClaimsGoldenTest`'s fixture: same ranking as the interactive engine · caps refuse with the measured size · `community_id` contains no member key (assert over every id) · determinism across two runs · betweenness refused above 10 000 nodes |
| **NW-1** insights | I-16 | SC-1, SM-1 | Kind `network-model`, validator, `ScoreKind`, Job `network.score`, `ScoreDatasets.register`, `value` join in DuckDB under the sandbox | Job test through `JobService`: outputs registered as system Datasets · a link table with a dangling edge counts it · over-cap run fails in words and **keeps the previous `_latest`** · key classification = strictest of the three columns · an unshared input narrows the outputs · `ConfigSafetyValidator` refuses a bad model (422) · `check-module-deps.mjs` green · **whole-feature falsification** (drop the join: `value` all null ⇒ red) |
| **NW-2** UI | I-16 | SC-2 | A Network Model editor in Studio (the existing model editors' pattern) and "Run now" | Vitest + preview drive; a refusal shown in words |
| **P5-DOC** docs | all | last | GLOSSARY §7 (**Scenario**, **Lever**, **Customer Segment migration**, **Network Model / Network Score**); hub pages (`architecture-insights` §8, `spec-segmentation`, a new `spec-network-score.md` in the insights hub, `user-guide-insights`); parent plan §11.18 "as built"; **archive the parent, P4 and this plan** (`docs/archived-documents/plans-archive/` + the archived-plans table) when the **last** built slice ships; BACKLOG row | `check-vocabulary`, `check-doc-links`, `check-doc-citations`, `docs-health --ratchet`, `render-product-docs.mjs`, `check-handbook.mjs` for any new guide |

**Shared touch points** (serialise their lanes, do not parallelise them): `ComponentRegistry` / `ComponentStore`
(NW-1, SC-4), `ScoreKind` and `ScoreDatasets` (SM-1, NW-1), `openapi-v1.json` and the generated `api-routes.md` (SC-1),
`GLOSSARY.md` §7 and the hub pages (P5-DOC only — slices do **not** edit them).

Honest limits to state in each report: statistical quality is fixture-pinned, not measured on a customer's data;
real-graph performance past 10⁴ nodes is **not** measured here; if the operator caps usage, targeted tests only
(`node tools/affected-modules.mjs`) and say the full gate was not run.

## 10. Value against cost — an honest ranking so the operator can pick or drop

| Rank | Capability | Value | Cost | Why |
|---|---|---|---|---|
| 1 | **I-15 migration** (SM-0, SM-1, SM-2) | High where a churn or retention team already runs a Segmentation Model — "1 840 high-value entities moved Champions → At risk" is actionable and answers a Q5 question nothing else in the product does | **S–M**: one kernel statistic, one output kind through machinery that exists, **no new UI** | Highest value per unit of work. Limits: only with the Predictive add-on (Standard+), and only relative rank (quantile bins) |
| 2 | **I-13 scenarios** (SC-0…SC-2) | Medium-high in a meeting, medium in daily use; strongest on revenue by product (the D-BI6 pilot) | **M**: thin kernel, one route, one dialog tab — because it reuses Explain's two identities | The risk is *credibility*: a user may read the result as a forecast. The words under the chart and the refusal to infer elasticity carry that, and SC-2's text is part of the acceptance, not decoration. The projection basis (SC-3) is where cost grows and value is least proven |
| 3 | **I-16 network value** (NW-0, NW-1, NW-2) | High for a fraud / partner-network team **that has an edge Dataset**; zero for one that does not | **L**: a new component kind, a new Job, an editor, a masking decision, caps on a memory-bound graph, unstable community ids | Value is real but conditional on a network-shaped Dataset the Builder must first build; the parent's "M" is an underestimate. Recommend **design now, build when a pilot names its edge Dataset** (D-BI25) |
| — | Sankey (SM-3), saved Scenario (SC-4), Insight Type `segment-migration` | Low-medium | M each | Held for demand; nothing in P5 depends on them |

## 11. Decisions for the operator (recommended option first)

| ID | Decision | Options |
|---|---|---|
| D-BI25 | What P5 builds now | **(a)** I-15 and I-13 now (SM-0…2, SC-0…2, P5-DOC); I-16 **designed and held** until a pilot names its edge Dataset · (b) all three · (c) I-15 only |
| D-BI26 | What a Scenario is | **(a)** arithmetic on the user's stated levers over Explain's decomposition; no elasticity inferred; the assumptions printed under the result · (b) a driver model fitted from history (elasticities) — not recommended: it states a cause the parent §10 forbids |
| D-BI27 | Is a Scenario stored | **(a)** no — a draft in the dialog; the `scenario` kind is slice SC-4, held for demand · (b) a component kind now |
| D-BI28 | Scenario baseline | **(a)** last complete period first (SC-1); the projection basis later (SC-3), sum / count only, allocated by last-period shares · (b) last period only, no projection ever · (c) projection first |
| D-BI29 | Scenario edition and gate | **(a)** Basic, with Business Insights, open to any viewer of the Dataset (reverses D-BI3's "scenarios Advanced" as D-BI13 reversed its add-on placement; it needs no module Basic lacks) · (b) Advanced only, honouring D-BI3 · (c) Basic but gated on `canAuthorWorkbench` |
| D-BI30 | Migration data shape | **(a)** entity-level rows through the score output contract (`customer_segment_migration_<model>` + `_latest`), written by `predict.segment`, a changed `model_version` writes nothing, a 24-pair backfill · (b) an aggregated matrix Dataset (smaller, but a new writer, no entity drill-down, no Case from a mover) · (c) a query-time self-join route (no storage, but runs as the caller over a classified key and has no `MeasureCompiler` form) |
| D-BI31 | Sankey | **(a)** not in P5; matrix = existing heatmap, Reports use the stacked bar · (b) build a custom `sankey` Visualization Type (SM-3) · (c) add `chartjs-chart-sankey` (a dependency not evaluated here) |
| D-BI32 | How a graph result becomes a Dataset | **(a)** a `network.score` Job over an edge Dataset, calling `inspecto-graph` directly, through the score output contract — the Link Analysis ports are not involved · (b) a new `la-core` port so the graph is an Investigation's Working Set (analyst-curated, but per owner and not schedulable without a `la.detect`-style SPI) · (c) an analyst's "Save result as Dataset" in Link Analysis (per-owner, one-off) |
| D-BI33 | Where Network value lives | **(a)** `features/inspecto-insights` (Basic, no new module) · (b) new `la/inspecto-la-network` (cleaner, needs offering, product-map and hub entries) · (c) the `geo-link` bridge |
| D-BI34 | Identity and masking of Network Scores | **(a)** the model-output pattern: raw read as the model's owner, `entity_key` stored raw under the strictest class of its three key columns, the model-and-inputs envelope, narrow on refusal · (b) the Insights pattern (`runAsJob`, alias-only at rest) — not joinable |
| D-BI35 | Caps and community ids | **(a)** fail the run past `maxNodes` 50 000 / `maxEdges` 500 000 (ceilings from `graph_run.ceiling_*`), betweenness ≤ 10 000 nodes; community ids ranked by size, stated unstable, no tracking across runs · (b) sample past the cap · (c) stable ids by overlap matching (a design of its own) |
| D-BI36 | Report PDF | **(a)** no PDF work in P5: bar forms are drawn, the heatmap and any new Type are refused by name as today · (b) draw the heatmap in the PDF (new `WidgetChartRenderer` work; Reports and screens agree) |

If the operator signs (a) throughout: P5 adds **one route** (`POST /insights/scenario`), **one kernel class** for
scenarios and **one** for migration, **one output kind** (`customer_segment_migration_`), **one dialog tab**, no
Visualization Type, no component kind, and — for I-16, designed and held — one component kind, one Job type and one
output kind. No Dashboard or Insight Profile schema change.

## 12. Risks, and what could not be verified

**Risks.**

- **A Scenario read as a forecast.** The strongest threat to trust. Mitigation: the word *Forecast* is banned for it, the
  assumptions sit under the numbers, no elasticity is inferred, and SC-2 reviews the text.
- **A migration read as absolute.** Quantile bins make it relative rank (§5.2); the caption says so.
- **A changed model silently changing meaning.** Handled by writing nothing across a `model_version` change; the run
  message says so — which also means a Builder who tunes bins weekly never gets a migration. State it in the guide.
- **Masking leak through a derived column.** `community_id`, the pseudo-labels and `moved` hold no key by construction;
  tests assert it (NW-0, SM-0).
- **Memory.** A whole-graph run is in memory; the caps fail the run rather than degrade, and the DuckDB join in the
  Job is the `ANOMALY-SCORE-MEMORY-LIMIT-1` question again (parent §10).
- **Report PDF parity.** Unchanged by design (D-BI36): Reports and screens agree on bars, disagree (in words) on heatmaps.

**Not verified here (each is a task for the named slice, not a fact):**

1. That a classified alias is **the same token on every call for the same caller** (SC-1's probe; the design has a
   fail-closed fallback).
2. That `KpiEvaluator.dailySql` can be generalised to a non-KPI Measure without touching KPI behaviour (SC-3 may be
   dropped if not).
3. That a second `ScoreKind` over the component kind `segmentation-model` does not collide with the reserved-prefix save
   check (`ScoreDatasets`, kind's `kind` field) (SM-1).
4. That a pilot Space Template already carries a segmentation model (SM-2) — the P4 plan's pilots are
   `business-assurance` and `telco-ra`; their model set was **not** read.
5. That `tools/check-module-deps.mjs` accepts `inspecto-graph` in `features/inspecto-insights` (NW-1 runs it).
6. Whole-graph run time and memory at 10⁴–10⁵ nodes; the `Algorithm` constants carry measured figures in comments
   (e.g. PageRank "61 / 356 / 11 610 ms") whose graph sizes were **not** confirmed.
7. Whether small communities (1–2 entities) need suppression for a reader with a narrower right than the model's owner.
   The envelope makes the audience the model's, so P5 does not add a rule; a privacy review may want one.
8. Nothing in this plan was built or run; **no guard other than the docs guards was run** (see the commit report).

## References

- Parent plan: [`2026-11-18-business-insights-plan.md`](2026-11-18-business-insights-plan.md) (§4, §6 I-13 / I-15 / I-16, §8, §9, §10, §11.8, §11.16); P4 plan [`2026-11-27-bi-insights-p4-language-plan.md`](2026-11-27-bi-insights-p4-language-plan.md).
- Hubs: [`okf/product/module/insights/architecture-insights.md`](../okf/product/module/insights/architecture-insights.md), [`okf/product/module/engine/spec-kpi-projection.md`](../okf/product/module/engine/spec-kpi-projection.md), [`okf/product/module/engine/spec-model-output-datasets.md`](../okf/product/module/engine/spec-model-output-datasets.md), [`okf/product/module/predictive/spec-segmentation.md`](../okf/product/module/predictive/spec-segmentation.md), [`okf/product/module/la-core/architecture-la-core.md`](../okf/product/module/la-core/architecture-la-core.md).
- Code read: `platform/inspecto-analytics/.../drivers/Drivers.java`, `ShiftShare.java`, `forecast/PeriodProjection.java`, `score/ScoreKind.java`, `score/ScoreColumns.java`; `features/inspecto-insights/.../ExplainRoutes.java`; `features/inspecto-predictive/.../SegmentEvaluator.java`, `RfmSegmenter.java`, `SegmentJobType.java`; `platform/inspecto-engine/.../alert/ScoreOutputWriter.java`, `ScoreDatasets.java`, `job/LaDetectJob.java`, `job/ReportWidget.java`, `linkindex/LinkIndexBuilder.java`, `pipeline/ComponentRegistry.java`; `la/inspecto-la-core/.../GraphRunService.java`, `GraphInput.java`, `Algorithm.java`; `la/inspecto-la-api/.../GraphResultJson.java`; `inspecto/.../control/BiSpecRunner.java`; `inspecto-ui/src/app/inspecto/viz/plugins/heatmap.plugin.ts`, `heatmap.component.ts`; `offerings/basic.toon`, `offerings/standard.toon`; `tools/check-module-deps.mjs`.
