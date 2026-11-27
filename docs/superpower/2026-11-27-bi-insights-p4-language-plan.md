# Business insights P4 — Language: Dashboard Brief and Ask the Dashboard (`BI-INSIGHTS-1` P4)

> **Status: DESIGN APPROVED 2026-11-27 - D-BI14..D-BI24 signed (operator, recommended options; option (a) of every row
> in §10); BR-0 and AQ-0 built (board row), BR-1…BR-5 and AQ-1 open.** Design only. Parent: [`2026-11-18-business-insights-plan.md`](2026-11-18-business-insights-plan.md)
> (§4 principles, §6 I-18 / I-19, §9 P4, §10 traps, §11 decisions; D-BI5 "templated sentences first, Brief in P4";
> D-BI13 Business Insights in every edition). Items: `module.insights`, `module.agent`, `module.intelligence`,
> `module.engine`, `layer.product`. Board row: `BI-INSIGHTS-1` stays **one** row; P4 is its slices BR-0…BR-5 and AQ-0…AQ-1.
> Code facts below were checked on 2026-11-27 with CodeGraph and direct reads; each says where.

## 1. Scope

| In | Out (stays where it is) |
|---|---|
| **I-18 Dashboard Brief** — the top Insights of the period, in plain words, on a Dashboard and as a page of a Report PDF | Scenarios, segment migration, network value (P5) |
| **I-19 Ask the Dashboard** — a question in words becomes a **structured `MeasureCompiler` spec**, shown as a *draft* Widget | A model that states a number, writes SQL, or answers in prose about data |
| The **Brief page** in Reports (a Widget drawn as a page by the Report Job) | A Brief on a public share (D-BI19), a Brief of Alerts or Incidents (that is the **Incident Brief**, a different thing) |

Binding rules carried from the parent plan: numbers are computed by the kernel or DuckDB and a model may only restate
them (§4.1); every sentence shows its evidence (§4.2); unknown is absent, never 0 (§4.5); masking holds everywhere
(§4.6); a model is an add-on and everything must work without it (D-BI5, §10 "Language models").

## 2. What is built and what is absent (verified 2026-11-27)

| Capability | State | Where |
|---|---|---|
| An Insight carries a **templated sentence** and its evidence (`value`, `expected`, `delta`, `explanation`, `query`) | BUILT (D-BI5) | `features/inspecto-insights/.../InsightScan.java:401` `sentence(...)`; stored in the `explanation` JSON, read by `GET /insights` |
| Insights are **alias-only at rest**: the scan never reveals, so a classified slice is stored as its alias; `GET /insights` returns them as stored, even to a revealer | BUILT | `InsightScanJobType` (§11.9, §11.12 "a revealer still sees aliases") |
| `GET /insights?profile=&run=latest&band=&limit=` — one Profile, best score first, 404 for a Profile the caller may not view | BUILT | `InsightsRoutes`; hub `api-routes.md` |
| **No route reads Insights across Profiles**, and **nothing joins a Dashboard to a Profile** except an `insight-feed` Widget's `viewId` | ABSENT | `insight-feed` in `inspecto/viz/plugins/view.plugins.ts` is bound to **one** Profile |
| `ReportNarrativeSkill` (`report-narrative`) restates a platform-computed **pipeline** report; falls back to a template with no model; repairs through `RepairLoop` (≤ 2 rounds); `modelBacked` flag; draft-only | BUILT, **pipeline reports only** | `features/inspecto-agent/.../skill/ReportNarrativeSkill.java:51`; registered in `UccAssistAgent` |
| `NumericGroundingGuard` rejects any number in the narration not in the evidence | BUILT — **looser than a Brief can accept** (§5.2) | `features/inspecto-agent/.../kernel/tool/NumericGroundingGuard.java:20` |
| AI Assist add-on = module `agent`, **Standard and above**; Business Insights = module `insights`, **Basic** | BUILT | `offerings/standard.toon` (`addons.aiAssist`), `offerings/basic.toon` (`addons.businessInsights`) |
| `POST /assist/{intent}` runs a skill **as the server, not as the caller**, with no capability gate; 503 when the agent module is absent | BUILT | `inspecto/.../control/AssistRoutes.java:33–104`; `AssistAgent` SPI is core, `HostContext.service().assistAgent()` |
| Intelligence tools author **drafts from structured args** — the model supplies measures and dimensions, never SQL or Widget JSON; promotion is the gated `component_apply`; results leaving to a model are masked (`EgressMasking`) | BUILT | `InspectoTools.java` `query_author`, `kpi_report_builder`; `DraftArtifacts` |
| `inspecto-ai-assist` UI component: `tool=` + `prompting`, degrades on 503, hides when the module is absent | BUILT | `inspecto/ai-assist/ai-assist.component.ts`; used for "Draft a KPI report" in `studio/dashboards/dashboard-editor.component.html:137` (passes dataset + title only) |
| **A question → `MeasureCompiler.Spec` tool** | ABSENT — `KpiToSqlSkill` / `ReportSqlSkill` emit SQL, `query_author` takes a condition tree, `kpi_report_builder` takes a ready measure list | searched `features/inspecto-agent`, `-intelligence` |
| Report Job: `widgets:` lists Widget ids, **pdf only**; `ReportWidget.DRAWN = bar, line, kpi`; any other type is **refused in words**; a `dashboardId` schedule is refused; chart pages are Java2D images (`WidgetChartRenderer`), Chart Markers have a contract-tested Java twin (`ChartMarkers`) | BUILT | `platform/inspecto-engine/.../job/ReportJob.java:107–114,227`, `ReportWidget.java:23`; `ReportPdfChartsTest.anInsightFeedWidgetIsRefusedInWords` |
| A **text page** in a Report PDF (title, paragraphs) | ABSENT — pages are chart images or the table renderer (`PdfRenderer`) | `WidgetChartRenderer.drawString` draws labels only |
| Anything called a Dashboard Brief | ABSENT — "Brief" exists only as the **Incident Brief** (`IncidentBriefs`, GLOSSARY) | grep `brief` over `*.java`, `*.ts` |

Two consequences shape everything below:

1. **The Brief must work in Basic.** Insights ship in every edition, AI Assist only in Standard and up, and a Basic
   build has no `inspecto-agent` jar. So the Brief is **built in the insights module** from stored sentences, and the
   model only *rewrites* it when AI Assist is present. Narration is the add-on; the Brief is not.
2. **`/assist/*` is not the place to read data.** It runs as the server. A skill that resolved Insights itself would
   ignore the caller's view rights. So the narration capability is a **pure function from evidence to text** and the
   *caller-scoped insights route* supplies the evidence (§5.1).

## 3. Vocabulary (checked against `docs/GLOSSARY.md`, ban list applies)

| Term | Use | Note |
|---|---|---|
| **Dashboard Brief** (new, D-BI14) | The ranked top Insights of a Profile's latest run, as plain sentences | Qualified, like **Incident Brief** (which stays the Assistant's one-Incident output). ⛔ not "summary", "digest" or "executive summary"; ⛔ not an **Incident** or **Alert** |
| **Brief page** | The page of a Report PDF that prints a Dashboard Brief | Not a new concept: the Report's rendering of one Widget |
| **Narrate** | The verb for the model rewriting a Brief (already the word in `report-narrative`) | One line under **Dashboard Brief**; "Narration" is not a separate noun |
| Visualization Type **`insight-brief`** | The Widget that shows one Profile's Brief | Beside `insight-feed`; Type ↔ Widget as everywhere |
| **Ask the Dashboard** | A UI action label, not a concept | Its result is a draft **Widget** (and the **Query** the Widget builder already makes). No noun "Question" |
| Draft | The existing word for an unsaved proposal | Unchanged |

The ⛔ ban list of `GLOSSARY.md` §0 applies to every UI string, route and field this plan adds. A Brief is about
**Measures**, **Insights** and **Profiles**; an unusual value is an *Insight*, never an Incident or an Alert.

## 4. Dashboard Brief — design

### 4.1 What a Brief is

For one Insight Profile: its **latest run** (`run_id = max`), Insights with status not `not_useful` / `expected`, band
**elevated or high** (score ≥ 40; the score-contract bands), best score first, at most **5** (cap 10), one line each.
Header: "Brief for *<Profile>* — scanned *<run_at>*, period *<period>*". Nothing qualifies → the sentence
**"Nothing unusual in <period>"**, never padded with low-band Insights. No scan has run → the 404 empty state's words
(names the `insight.scan` Job). The latest run older than two grains → "Last scan was *<n>* days ago" ahead of the lines
(a stale Brief says so; it does not look current).

Each line = **band word** + the stored templated **sentence** + a link to the Insight in the feed
(`/studio/insights?profile=…&insight=…`, which already exists) and the reproducing `query` (§4.2 "one click opens it").

### 4.2 Two forms, one selection

| Form | Needs | Text |
|---|---|---|
| **Templated** (every edition) | the `insights` module | the stored sentences, as above |
| **Narrated** (Standard and up) | AI Assist (module `agent`) **and** an available model | a 2–4 sentence paragraph per Profile, each sentence citing one Insight |

Narration never replaces the lines: the UI shows the paragraph **above** the cited lines, and every sentence of it
carries its Insight number, so a reader can open the evidence. A narration that fails any check (§5.2) falls back **per
sentence** to that Insight's templated sentence — the Brief is never absent because a model misbehaved.

### 4.3 Routes (module `insights`, feature id `insights`)

| Route | Purpose | Gate |
|---|---|---|
| `GET /insights/brief?profiles=a,b&max=5` | The templated Brief for up to 5 Profiles: `{profiles:[{profile, runId, runAt, period, stale, lines:[{insightId, band, score, sentence, type, slice, delta…}], note?}]}`; a Profile the caller may not view is **omitted** (as a 404 would be, but the others still return) | Authenticated; each Profile by the caller's view of `insights_<profile>` — `read-shaped` exemption, like `GET /insights` |
| `POST /insights/brief/narrate` `{profiles, max}` | Builds the same selection as the caller, asks the narrator, returns `{brief, narration:{mode: model\|templated, profiles:[{profile, sentences:[{text, cites, source: model\|template}], modelBacked, repaired}], reason?}}` | Same view rule; **503 `CAPABILITY_UNAVAILABLE`** "AI Assist is not installed" when no agent; rate-limited on the standard bucket (a model call is the cost) |

Why a route rather than the UI templating `GET /insights` N times: the selection, the stale rule and the sentence
assembly must have **one home** shared by the Dashboard tile, the PDF page and the narrator (parent §4.3 spirit), and
the "omit what the caller cannot view" rule belongs server-side. Route checklist: literal capability (none, as `GET
/insights`), armed 401 / 404-for-hidden / 200 test, regenerate `openapi-v1.json`, `render-api-routes.mjs`.

### 4.4 Dashboard placement

A Brief is a **Widget**, not a Dashboard-level setting (D-BI16): Visualization Type `insight-brief`, view-bound
(`viewKind: insight-profile`, `viewId` = the Profile), offered only when `/bootstrap` reports `insights`; the Builder
places it first in the layout. No Dashboard schema change, and the link graph already sees `viewId`. Several Profiles =
several Brief Widgets. The tile reads `GET /insights/brief` as the viewer; a **Narrate** button (visible only when
`/bootstrap` reports `agent`) calls `POST /insights/brief/narrate`; the result is held in the tile's state, not saved.
Plain text only — never markdown or HTML from a model (§6).

### 4.5 Brief page in Reports

`ReportWidget.of` accepts `insight-brief` (today it refuses every view-bound Widget); the Report Job, running **as its
author** through the same insights code (the `kpi.snapshot` / `insight.scan` pattern), draws a **text page** for that
Widget's Profile at the Widget's position in `widgets:` — put it first for "the first page of the PDF" (D-BI24). Screen
and PDF agree by construction: both call the one selection. Needs two new pieces: a `BriefPageRenderer` (Java2D, like
`WidgetChartRenderer`: wrapped paragraphs, band word beside each line, "templated" / "model-written, checked against the
Insights" footer so the reader knows which they hold) and an engine-side seam so `ReportJob` (engine, lower layer) can
ask the insights module for the lines — a ServiceLoader SPI in engine implemented in `features/inspecto-insights`, the
same shape as Job types. In a Basic build the Brief page is templated; with AI Assist, narration is per D-BI23.

## 5. Narration — extractive, numerically grounded

### 5.1 Where it runs (D-BI14's consequence)

New capability **`insight-brief`** in `features/inspecto-agent` (beside `report-narrative`, same `Capability` shape:
`ModelTier.SMALL`, `RepairLoop`, abstain-safe template, draft-only, `applyVia = null`). It **reads nothing itself**:
its input is the evidence list the insights route built *as the caller*, so there is no per-caller access question
inside the agent. The same holds if someone POSTs `/assist/insight-brief` directly with their own evidence: they get
their own text restated, as they can today with `report-narrative` and a supplied `report`; nothing server-side is read,
nothing is cached from that path, and the route in §4.3 never trusts a body for evidence. The insights module reaches the
agent through one small **public seam in core** (an `Optional<AssistAgent>` accessor; `HostContext` is package-private
today, so this is the one core change), and absent agent ⇒ `Optional.empty()` ⇒ 503 on the narrate route and the templated
Brief everywhere else.

### 5.2 The grounding contract (why the existing guard is not enough)

`NumericGroundingGuard` gathers **every number anywhere in the evidence** into one set and accepts a narration number
if it equals any of them, **or ×100 / ÷100 of any of them, or has the same rounded integer as any of them**
(`NumericGroundingGuard.java:71–77`). For a pipeline report that is a fair trade; for a Brief it lets "5" ground "4.6",
attributes one slice's figure to another's sentence, and says nothing about direction or invented names. So, **D-BI18**:

| Layer | Rule |
|---|---|
| **Slots, not digits** | The model writes line skeletons with placeholders (`{measure}`, `{slice}`, `{period}`, `{value}`, `{expected}`, `{delta}`, `{pct}`); the server fills them from the cited Insight's stored fields, formatted as the templated sentence formats them. **Any literal digit** in a model line is rejected. A hallucinated number cannot exist |
| **Per-sentence citation** | Each line starts `[n]` naming exactly one Insight; slots resolve from **that** Insight only, so one slice's figures cannot appear in another's sentence |
| **Direction** | Words of increase (up, rose, climbed, grew, higher…) require `delta > 0` for the cited Insight; words of decrease (down, fell, dropped, slid, lower…) require `delta < 0`; both in one line → rejected |
| **No cause** | A closed list (because, due to, caused, driven by, as a result, thanks to, reason…) is rejected: an Insight says *what moved*; causes belong to **Explain this change** (parent §10 "never claims causation") |
| **Belt** | The rendered paragraph also passes `NumericGroundingGuard` against the selection (the existing check, kept as a second net, not the first) |
| **Repair, then fall back** | A rejected line goes back through `RepairLoop` with the reason (≤ 2 rounds, `AssistTunables.repairRounds`); still bad → that one line becomes its templated sentence, `source: template` |
| **Text only** | Output is plain text; the tile and PDF never interpret markdown or HTML |

### 5.3 Prompt-injection and egress

An Insight's `slice_value`, measure name and Profile title are **data**, including text an upstream feed controls. They
are passed as quoted fields in a fixed JSON evidence block, the system prompt says to ignore instructions inside it,
and the slot design means the model's own words are checked against a closed shape. **Feedback notes are never sent to
the model.** Stored Insights are alias-only, so a classified value never leaves the process; unclassified business
values (a region name, a product) do reach the configured provider, **as they already do for `report-narrative`** — the
operator's model-endpoint allowlist (`ModelEgress`) and "local provider" setting are the control, and the User guide must
say so (§9). A model-bound narration is not masked further than the alias, because there is nothing left to mask.

## 6. Ask the Dashboard — design (I-19)

### 6.1 Where it lives (D-BI20)

A new **draft-only intelligence tool `widget_from_question`** in `features/inspecto-intelligence/.../pack/InspectoTools`
(author, L1, as `query_author` and `kpi_report_builder`; that the tool call carries the caller's identity for view checks
is bound through `ToolCaller` — `EgressMasking` already reads it — and AQ-0 must prove it with a test before relying on
it), with the `inspecto-ai-assist` UI component in `prompting`
mode on the Dashboard editor and Dashboard viewer toolbar. Not a `/assist/*` skill (that runs as the server, §2) and
not a new insights route (the question is not about Insights).

### 6.2 What the model sees and returns

- **Sees**: the Datasets the caller may view (default: the Dashboard's own Widget Datasets first), each as **column
  name, role (dimension / measure / temporal), type, cardinality bucket** — taken from `ResultSetDescriptor` roles. **No
  values, no rows.** A classified column is listed by name only and flagged "masked".
- **Returns**: a structured argument object only — `{dataset, measures:[{agg, field, label?}], groupBy:[≤2], grain,
  filters:[{field, op, value}], window:{trailing|last}, compare, topN, order}`. The closed vocabulary is the Widget
  builder's (`widgets/widget-types.ts`, `query-spec.ts`): sum, count, avg, min, max, countDistinct, ratio, the
  statistical aggregates the grammar knows; grains auto/day/week/month; the existing `compare` options. **No joins, no
  calculated Measures, no SQL text, no forecasts** (D-BI22).
- **Server**: builds the `/bi/query`-shaped body, runs it through `MeasureCompiler.parse` and the same validation the
  builder uses; every field must exist, every aggregate must suit its column's role; an unknown value in a filter is
  *not guessed* — the preview shows zero rows and says "no value 'X' in region". The tool returns a **draft `widget`
  component** (chart type by the existing Show-Me `recommend()`), exactly the shape `kpi_report_builder` returns.

### 6.3 Answer form (D-BI21)

The reply is a **draft Widget previewed live** in a dialog: the Widget runs through `POST /bi/query` **as the
caller**, so the number on screen is the engine's, masking and caps apply as on any Widget, and a revealer-only column
masks for others as everywhere. There is **no model-written answer text**: with nothing for a model to state, there is
nothing to ground. A one-line templated caption names the spec in words ("Sum of revenue by region, last 8 weeks,
compared with the previous period") so a reader can check the model understood the question. **Save** goes through the
existing gated apply (`component_apply`, L2), so Draft / Apply, version history and four-eyes hold (parent §4.4); *Not
now* discards. Ask never writes by itself.

### 6.4 Refusals, in words

| Question | Reply |
|---|---|
| A column the caller cannot view / not in any Dataset they can view | "I can't see a column called X" — never confirms a hidden Dataset exists (404-shaped) |
| Why did it move? | "Use **Explain this change** on the tile" (a link) — Ask never reasons about causes |
| Forecast / projection | "Add a forecast band from the tile's suggestions" — Ask does not author overlays in P4 |
| More than 2 breakdowns, joins, calculated Measures | refused with the supported vocabulary listed |
| Question needs a Dataset no one named and several fit | the dialog asks which Dataset (a picker), never silently picks |

## 7. Edge cases

| Case | Behaviour |
|---|---|
| AI Assist absent (Basic, or Standard without `agent`) | Narrate and Ask controls are **not rendered** (bootstrap `agent` / `intelligence` flags); `POST /insights/brief/narrate` → 503; the templated Brief works unchanged; a PDF Brief page is templated and says so |
| Agent present, **no model available** | `modelBacked=false`; narrate returns the templated lines with `reason: "no model"`; never an error to the reader |
| Model returns ungrounded text after two repairs | that sentence is the template; `repaired: true`; the metrics counter `repaired` increments (existing `AssistMetrics`) |
| Insight deleted / Profile edited between selection and narration | narration is of the stored run; its `run_id` is on the result; the link says "not in the latest run" as the feed already does |
| Viewer may see only some Profiles | those only; none → empty `profiles`, 200 (Basic parity with how the feed hides) |
| Two Brief Widgets on one Dashboard over one Profile | one narration each; cache (D-BI17) makes it one model call |
| Scheduled Report re-runs with no new scan | same `run_id` ⇒ cache hit ⇒ **identical text**; a Report's two deliveries do not disagree |
| Revealer vs non-revealer | identical Brief: stored Insights are aliases for everyone (the Job never reveals) — consistent with §11.12 |
| Public share | no Brief (D-BI19); the tile renders nothing there |
| Non-English / locale | English only in P4; numbers formatted as the templated sentence formats them (`Locale.ROOT`) |
| Very long slice values | truncated with "…" in the tile; full in the feed link |
| Concurrency | a scan swaps its Parquet atomically; the selection reads exactly one `run_id` |
| Time | period labels come from stored Insights, never recomputed; "stale" uses the Profile's grain, not wall-clock guessing |
| A question that names an unknown measure in Ask | refused, with the nearest known column names as suggestions (server-side string similarity, no model) |
| Ask and a masked Measure column | the compile vet refuses as `/bi/query` does (422 in words); Ask shows it |
| Rate / cost | narrate on the standard bucket; one cached model call per (profiles, run ids, mode) |

## 8. Slices, each with its tests

Order is by dependency; each is one lane (CLAUDE.md "one lane per push"). BR-0 and AQ-0 are independent and may be built
in parallel; BR-1 needs BR-0's evidence shape.

| Slice | Delivers | Tests (what proves it) |
|---|---|---|
| **BR-0** insights | `InsightBrief` selection + stale/empty rules; `GET /insights/brief` | Golden selection: band cut, `not_useful` / `expected` excluded, score order, ties by `insight_id`, `max`, "Nothing unusual", stale note, no scan → 404 words · 401 / hidden-Profile omitted / 200 · alias-only for a revealer and a non-revealer · Basic-only build serves it · route checklist (`CapabilityManifest` exemption, `openapi-v1.json`, `render-api-routes.mjs`) · **falsified** by dropping the view check |
| **BR-1** agent | capability `insight-brief` + slot renderer + `BriefGroundingGuard` rules | One unit test per rejection class: literal digit, "5" vs 4.6, another Insight's slice, wrong direction, both directions, a cause word, markdown, an injected instruction in a `slice_value` · a fake model that is wrong then right (repair), wrong always (per-sentence template), unavailable (`modelBacked=false`) · `GoldenEvalTest` fixture · **whole-feature mutants**: disable each rule, expect its test red |
| **BR-2** core seam + route | public `Optional<AssistAgent>` accessor; `POST /insights/brief/narrate`; cache keyed by (profiles, `run_id`s, mode) | Real-HTTP: 401 / 200 / 503 without agent (a build **without** `inspecto-agent`: templated route still 200) · the route ignores evidence in a body · cache hit returns identical text, a new `run_id` misses · rate limit · direct `POST /assist/insight-brief` is not cached or exposed in the Brief |
| **BR-3** UI | `insight-brief` Visualization Type, tile, **Narrate** button, `/bootstrap` gating | Vitest: renders templated, narrated, per-sentence template mark, empty / stale / 404 states, no markdown interpretation, button hidden without `agent` · axe (band as a word, not colour) · drive the preview with a stub API in Basic and Standard shapes, as §11.15 did |
| **BR-4** reports | `ReportWidget` accepts `insight-brief`; SPI + `BriefPageRenderer`; PDF footer says templated or model-written | `ReportPdfChartsTest`-style: page text equals the route's lines (parity), order follows `widgets:`, an unreadable Profile fails the Run in words, a Basic run is templated, a cached narration is identical across two runs · the existing `anInsightFeedWidgetIsRefusedInWords` still red-green |
| **AQ-0** intelligence | tool `widget_from_question`, vocabulary builder, server validation | Vocabulary golden (roles → allowed aggregates; no values in the model's view) · each refusal of §6.4 · unknown column / value never guessed · masked column refused as `/bi/query` · result is a draft and **never persists** (a write-root-less run still drafts) · egress masking test on the schema block · fake model returning an out-of-vocabulary aggregate |
| **AQ-1** UI | Ask box (`inspecto-ai-assist`, `prompting`), draft Widget preview, Save via gated apply | Vitest + preview: question → draft → live `/bi/query` preview as caller → Save goes through apply (holds under a Pending Change policy), hidden when `intelligence` absent |
| **BR-5** docs | hub updates (`architecture-insights`, `user-guide-insights`, `api-routes` regenerated), GLOSSARY §7 `Dashboard Brief`, parent plan §11.17 "as built", archive when shipped | `check-doc-*` guards, `render-product-docs.mjs` |

Honest limits to state in each report: real-model quality is **not** CI-testable (fake models only); the evals are
fixtures. If the operator caps usage, targeted tests only and say the full gate was not run.

## 9. User-guide and glossary text to ship with BR-5

- GLOSSARY §7: add **Dashboard Brief** (as §3) and a "Narrate" sentence; extend **Insight** "a language model may only
  restate it" with the slot rule.
- User guide: what a Brief is, that it appears in every edition, what Narrate adds and where its words come from, that a
  Brief never shows a classified value, that **unclassified values (region, product) are sent to the configured model
  provider** when you press Narrate, and that Ask the Dashboard only drafts.

## 10. Decisions for the operator (recommended option first)

| ID | Decision | Options |
|---|---|---|
| D-BI14 | ✅ **SIGNED 2026-11-27 (a)** — Name and home of the Brief | **(a)** *Dashboard Brief*, qualified beside *Incident Brief*; built in the `insights` module (Basic), narration in `inspecto-agent` as capability `insight-brief` · (b) *Insight Brief* · (c) build it entirely in `inspecto-agent` (Standard+ only) |
| D-BI15 | ✅ **SIGNED 2026-11-27 (a)** — Does a Brief exist without AI Assist | **(a)** yes — a **templated** Brief in every edition (D-BI5, D-BI13); only the model *narration* needs AI Assist · (b) the whole Brief is AI Assist-only (Basic users get the feed only) |
| D-BI16 | ✅ **SIGNED 2026-11-27 (a)** — How a Dashboard shows a Brief | **(a)** Visualization Type `insight-brief`, a Widget bound to one Profile (like `insight-feed`; no Dashboard schema change; several Profiles = several Widgets) · (b) a Dashboard-level `brief: {profiles[], max}` setting rendered above the grid (schema change + link census work; merged ranking across Profiles) |
| D-BI17 | ✅ **SIGNED 2026-11-27 (a)** — When a Brief is narrated, and caching | **(a)** on an explicit **Narrate** click (and by the Report Job); cached by (profiles, `run_id`s, mode), served only after the caller's per-Profile view check · (b) automatically on every Dashboard open (a model call per open) |
| D-BI18 | ✅ **SIGNED 2026-11-27 (a)** — Grounding strictness | **(a)** slot-filled lines (no literal digits), one Insight per sentence, direction and no-cause checks, `NumericGroundingGuard` kept as a second net · (b) reuse `NumericGroundingGuard` as-is (the model writes numbers; rounded-integer and ×100 matches allowed) |
| D-BI19 | ✅ **SIGNED 2026-11-27 (a)** — Brief on a public share | **(a)** no, not in P4 (a share has no Insight reader and never reveals; revisit with a snapshot design) · (b) a templated Brief baked at share time |
| D-BI20 | ✅ **SIGNED 2026-11-27 (a)** — Home of Ask the Dashboard | **(a)** new draft-only intelligence tool `widget_from_question` (the `query_author` / `kpi_report_builder` pattern; runs as the caller) · (b) a `/assist/ask` skill in `inspecto-agent` (runs as the server — **not recommended**) · (c) a route in the insights module |
| D-BI21 | ✅ **SIGNED 2026-11-27 (a)** — What Ask returns | **(a)** a draft Widget previewed live, plus a templated one-line caption; no model-written answer · (b) also a model-worded answer, grounded |
| D-BI22 | ✅ **SIGNED 2026-11-27 (a)** — Ask's vocabulary and Datasets | **(a)** the Widget builder's closed vocabulary (≤ 2 breakdowns, filters, trailing window, compare, top-N), over Datasets the caller may view, the Dashboard's first · (b) wider (joins, calculated Measures) — needs those to exist first |
| D-BI23 | ✅ **SIGNED 2026-11-27 (a)** — Narration in a delivered Report PDF | **(a)** allowed with AI Assist, cached per scan run, the page footer says "model-written, checked against the Insights" · (b) PDFs are always templated |
| D-BI24 | ✅ **SIGNED 2026-11-27 (a)** — How the Brief page is requested | **(a)** a `insight-brief` Widget id in the Report Job's `widgets:` list draws the Brief page (screen and PDF agree by construction; order = position) · (b) a separate `brief:` Job parameter listing Profiles |

If the operator signs (a) throughout, P4 adds **two routes** (`GET /insights/brief`, `POST /insights/brief/narrate`),
**one Visualization Type** (`insight-brief`), **one agent capability** (`insight-brief`), **one intelligence tool**
(`widget_from_question`), **one Report page renderer**, **one core seam**, and no Dashboard or Profile schema change.

## 11. Risks

- **A model that sounds right and is wrong** — contained by slots (no digits), one-Insight sentences, direction and
  no-cause checks; the residual risk is a *true number in a misleading sentence* ("steady" for a spike), which the
  closed phrasing and the visible templated lines beside it limit but do not remove. The Brief says what it is.
- **Cost surprise** — a model call per open would be a bill; D-BI17(a) caps it at one per scan run.
- **Report/screen drift** (parent §10) — one selection, one sentence source, a parity test in BR-4.
- **The `/assist/*` shape** — it runs as the server; the design keeps the capability a pure function and the route the
  only reader of data, so no new server-as-caller read is introduced. Worth a separate look at whether `/assist/{intent}`
  should gain a capability gate at all (not filed: out of P4's scope; raise if the operator wants it).
- **Vocabulary creep** — *Brief* is overloaded by one word; the qualified names and the ⛔ lines in §3 are the guard.

## References

- Parent plan: [`2026-11-18-business-insights-plan.md`](2026-11-18-business-insights-plan.md) (§4, §6 I-18 / I-19, §9, §10, §11.9, §11.12, §11.15, §11.16).
- Insights hub: [`okf/product/module/insights/architecture-insights.md`](../okf/product/module/insights/architecture-insights.md), [`api-routes.md`](../okf/product/module/insights/api-routes.md).
- Code read: `features/inspecto-agent/.../skill/ReportNarrativeSkill.java`, `.../kernel/tool/NumericGroundingGuard.java`,
  `inspecto/.../control/AssistRoutes.java`, `features/inspecto-insights/.../InsightScan.java`, `features/inspecto-intelligence/.../pack/InspectoTools.java`,
  `platform/inspecto-engine/.../job/ReportJob.java`, `ReportWidget.java`, `WidgetChartRenderer.java`, `offerings/basic.toon`, `offerings/standard.toon`.
