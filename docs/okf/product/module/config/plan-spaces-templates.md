---
type: Capability
title: Spaces & tenancy (SPC) — Specification — Space Templates
description: Part 2 of 4 of Spaces & tenancy (SPC): 
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
---

# Spaces & tenancy (SPC) — Specification — Space Templates

Part 2 of 4 of [Spaces & tenancy (SPC)](plan-spaces.md) · previous: [Purpose, requirements and the Space model](plan-spaces-scope-model.md) · next: [Specification — Space Templates (continued)](plan-spaces-templates-continued.md).

## 3. Specification (continued)

### 3.5 Space Templates (SPC-3)

A template is a **server-global catalog entry**, deliberately **not a Component kind** (product owner,
2026-07-03): `<spacesRoot>/_templates/<id>/template.toon` (`{id, name, tagline, description, icon,
contents[]}` — the UI's `SpaceTemplateInfo`) beside a `config/` tree and an optional `data/`.
`createFromTemplate` (`SpaceManager.java`) mints the convention directories, copies `config/`
**rewriting `${SPACE}` tokens** in every `.toon` (template configs address their own Space as
`spaces/${SPACE}/…` — the portable bare-form the product writes, W3) and copies `data/` verbatim. An
unreadable template is warned and skipped, never fatal. The SPA's gallery (`SpaceTemplateGalleryDialog`) is
two-step ask-the-minimum and renders whatever the server publishes. **What is published: 30 templates**
(counted 2026-11-15) — `orders-starter` (a pipeline, an Expectation, a dataset and a live dashboard), and, since 2026-09-30,
`business-assurance`, `telco-fraud` and `telco-ra` (below) and `payment-fraud` slice 1 (§3.5.1), since 2026-10-09 the `mobile-money` industry pack (§3.5.3),
then `aml`, `partner-settlement`, `telco-bypass` and `telco-rerating`; and twenty `la-*` Link Analysis templates
(`la-showcase`, §3.5.6, and its siblings). A template's `minEdition:` (`standard` on the packs except `orders-starter`, `basic` on the
`la-*` templates except `la-sample` and `la-showcase-off`, which declare none) is checked by `tools/check-offerings.mjs`.

**`telco-ra` — the telecom revenue-assurance pack** (`ASSURE-PACK-TELCO-RA-1`, wave 5.2 of the assurance plan,
2026-09-30). ✅ **SHIPPED — row closed 2026-10-04**: items (1)-(6) done or decided; the vendor-specific half is
parked in the plan's §4 (needs real feeds).

- **What it is.** Configuration only: no new Step Processor.
  - The vendor feed mapping stays parked.
  - The pack needs **Standard or Advanced**, because it ships Alert Rules (`alert.dispatch`). The gallery
    marks it `creatable: false` on Basic.
  - `ra_objects_analytics` needs the ops module.
- **Feeds.** Eight canonical synthetic feeds, each with a Pipeline and a Schema.
  - Money is `DECIMAL(18,4)`.
  - `tariff` carries `EFFECTIVE_FROM` / `EFFECTIVE_TO`.
  - Called numbers are in the fictional `+1-555-01xx` range.
- **Reconciliations.**
  - `ra_xdr_completeness`: 3-way, switch → mediation → rating.
  - `ra_rated_vs_billed`: needs `includeRecordCount: false`, because many rated rows face one invoice row.
  - Both run as `recon.run` Jobs.
  - Since `fix(recon)` in the same lane, the run message, Signal and Incident count the Breaks of **every**
    pair. Before that they counted A↔B only, from the run summary's `byType`.
- **`sql.template` control Jobs.**
  - **Chained, not staggered (2026-10-04).** `ra_rerating` keeps its cron; `ra_rollforward` →
    `ra_settlement` → `ra_leakage` and `ra_data_quality` each use `on_signal: job.run.completed` with
    `when: "$signal.job == <previous> && $signal.outcome == SUCCESS"` (halt-on-failure by guard), so the union
    Jobs never read a half-written findings Dataset. ⚠ Every completion also records a SKIPPED run on the
    other links (guard false) — a test waiting for a link must ignore SKIPPED
    (`TelcoRaGoldenTest.theFindingsJobsChainOnSignalFromOneTrigger`).
  - `ra_xdr_lost`: distinct lost or short xDRs.
  - `ra_rerating`: the tariff row in force at the call **start**. It emits one row per call, and a
    duplicate or overlapping row becomes an `ambiguous_tariff` data-quality finding, never a fan-out.
  - `ra_rollforward`: checks the movement rule, day-to-day continuity, and a NULL field (data quality).
  - `ra_settlement`: sums the statement per partner/day first, then FULL OUTER JOINs it against our switch
    minutes × rate. It flags these reasons, in priority order:
    - `missing_statement` (data quality).
    - `unknown_partner`: the whole billed total, every line, is the leakage.
    - `no_traffic`.
    - `split_statement` (data quality): several lines whose total is within tolerance of the expected amount.
      It is a legitimate split bill, so it never reaches `ra_leakage` or the CRITICAL Alert.
    - `duplicate_statement`: the amount is the billed total of ALL lines minus the expected amount. It never
      depends on which line is "first". `LINES_DISAGREE` marks a line that is also off-tolerance, and
      `STATEMENT_ID` reports the lowest id, for reference only.
    - `amount_mismatch`.
  - Each control writes every finding to `<control>_findings`. Every row carries `REASON`, and `FINDING` is
    `leakage` or `data_quality`. A leakage row always has a non-NULL amount.
  - `ra_leakage` takes only the leakage rows, and its Alert Rule `ra_leakage_found` is CRITICAL.
  - `ra_data_quality` takes only the data-quality rows, and its Alert Rule is WARNING.
  - ⚠ A dataset Alert Rule has no row filter (`when` scopes only ledger-metric rules), so the split has to be
    two Datasets.
  - Both rules are keyed `by: CONTROL` (2026-10-04): one Alert per firing control and rule, never per entity
    (G-42). The seed gate checks a `by` column against the Dataset's Schema, so `ra_leakage` and
    `ra_data_quality` ship a zero-row `schema-seed.parquet` (below).
  - Timestamps are naive, so every feed must arrive in one agreed timezone.
  - Tolerances are Job keys. A `$param` arrives as a string literal, so the SQL `CAST`s it to
    `DECIMAL(18,4)`.
  - ⚠ `CEIL` returns a DOUBLE: a settlement minute count must be cast back to `BIGINT`, or every downstream
    amount silently turns into a float. The golden test caught this.
- **Alert Rules.** Both use `gte 1` and `by: CONTROL`. ⚠ An Alert Rule threshold must be positive, so "any row" cannot be
  written `gt 0`.
- **KPIs (2026-10-04).** `registry/kpis/` ships `leakage_found` (`sum(LEAKAGE_AMOUNT)`), `leakage_items` and
  `data_quality_items` (`count`), month grain over `EVENT_DATE`; the three matching dashboard tiles are bound by
  `kpiId`. The seed gate reads a KPI's Dataset Schema and every pack Dataset is empty at apply, so
  `data/ra_leakage/` and `data/ra_data_quality/` each ship a **zero-row `schema-seed.parquet`** (generated with
  DuckDB, as `payment-fraud` does): `CONTROL`, `ITEM_KEY`, `REASON`, `FINDING` VARCHAR, `EVENT_DATE` DATE,
  `LEAKAGE_AMOUNT` DECIMAL(38,4). The gate is NOT relaxed. ⚠ `TelcoRaGoldenTest` pins each seed to its Job's
  output schema (a drift, e.g. the `DECIMAL(38,4)` the Job really emits, fails it) and that the first run replaces
  the seed. The seed carries the two Jobs' columns only; the other findings Datasets need none.
  - ⚠ **Breaks are not xDRs.** On the golden corpus, 15 completeness Breaks are **9** distinct lost or short
    xDRs, because a record lost at mediation breaks both pairs. The dashboard shows the xDR count
    (`ra_xdr_lost`).
- **Golden test.** `features/inspecto-reconciliation/src/test/java/com/gamma/job/TelcoRaGoldenTest.java` runs the template's
  own Jobs and Reconciliations over `TelcoRaCorpus` (seed `20260930`, 600 xDRs). It asserts the exact
  `key|reason` set per control, and one row per finding:

  | Control | Findings |
  |---|---|
  | Completeness | 15 Breaks / 9 xDRs |
  | Rated vs billed | 4 |
  | Re-rating | 5 `rate_mismatch` (2 half-rate + 3 still on the pre-change rate), 12 `ambiguous_tariff` and 1 `no_tariff` |
  | Roll-forward | 6 (3 movement, 2 continuity, 1 NULL opening) |
  | Settlement | 10 (2 over-billed, 1 missing statement, 2 unknown partner (one day duplicated: 90.00, not 45.00), 3 duplicates, 2 split statements: one exact, one at +0.9 % of a 1 % tolerance) |

  - The three duplicates are an exact copy of a correct line, an over-billed line billed twice, and a
    different line that sorts first. The test pins each one's exact loss.
  - Split by Dataset: **17 leakage rows** in `ra_leakage` and **17 data-quality rows** in `ra_data_quality`.

  - It also checks that leakage amounts are never NULL, that they are DECIMAL, and that the committed samples
    are byte-identical to the generator (`-Dtelcora.regenerate=true`).
  - **End to end (2026-10-04).** `theTemplateIngestsEvaluatesAndAlertsEndToEnd` creates a Space from the template,
    drops each feed's CSV in `data/inbox/<feed>`, ingests it through the template's own 8 Pipelines
    (`CollectorService.runPipeline`), runs the Jobs (the `on_signal` chain included), evaluates the Alert Rules and
    the Expectations, and asserts the same pinned rows (17 + 17). Six Alerts fire, one per firing control and rule
    (`ra_leakage_found` CRITICAL: re-rating 5, roll-forward 5, settlement 7; `ra_data_quality` WARNING: 13, 1, 3;
    never per entity), and the KPIs evaluate to 161.16 USD, 17 and 17; both shipped Expectations PASS the
    clean corpus, and a `non_null` probe on the NULL opening balance proves an Expectation can fail. The test moved
    from `inspecto-engine` to `inspecto` because Expectations and the Space bootstrap live there. No Pipeline
    needed a fix to ingest its feed. The direct-load tests stay as the fast unit-level pin.
  - **Impact ledger stays manual (decided 2026-10-04, item (2) of `ASSURE-PACK-TELCO-RA-1`, no build).** A Break's amount
    does not reach the impact ledger by itself; `recon.run` semantics are unchanged and an analyst records impact from a
    Break explicitly (`PUT /objects/{id}/impact`). No automatic ledger write.
  - The benign look-alikes are real **boundary** cases:
    - a call spanning the tariff change;
    - one cent under the re-rating tolerance;
    - 0.04 against the 0.05 billing and roll-forward tolerances;
    - 0.9 % against the 1 % settlement tolerance;
    - goodwill adjustments;
    - rounding by one usage unit.
  - **Mutation-checked, each one run separately and red.** Mutations that tighten a tolerance under its
    look-alikes:
    - completeness 1 → 0;
    - rated-vs-billed 0.05 → 0.03;
    - re-rating 0.02 → 0.005;
    - roll-forward 0.05 → 0.03;
    - settlement 1.0 → 0.8.

    Mutations that remove or change a rule:
    - a re-rating join without effective dates;
    - re-rating at the call's end;
    - roll-forward without adjustments, without continuity, or without the NULL rule;
    - settlement with a LEFT JOIN instead of the FULL OUTER JOIN;
    - an unknown partner's amount set to NULL;
    - no duplicate-statement rule;
    - `ra_leakage` keeping data-quality rows;
    - a duplicate's loss taken as the total minus one line;
    - the highest statement id reported;
    - a duplicate outranking an unknown partner;
    - `no_tariff` routed as leakage;
    - lines-disagree ignoring the lowest line;
    - no split-statement rule;
    - a split bound of an exact sum only;
    - a split routed as leakage.
  - ⚠ The golden test loads the corpus straight into the stores. It does not ingest through the eight
    Pipelines.
- `ControlApiSpaceTemplateSeedGateTest` applies the template through the real seed gate.

**A template may carry a KPI pack** (`config/registry/kpis/`, `ASSURE-KPI-DEFINITIONS-RESIDUALS-1` (1),
2026-09-28). `SpaceRoutes.createSpace` hands `createFromTemplate` a seed gate
(`KpiRoutes.requireTemplateKpis`) that runs after the WHOLE tree is copied — so the template's Datasets are on
disk — and before boot: the caller needs `canAuthorWorkbench` (the `/components/kpi` door's capability; the
zero-Space recovery create asks it too, since `POST /spaces` is `canAdminister` always), and each KPI meets
`KpiRoutes.requireMeasure` against the NEW Space's registry and `data/`. The first refusal is a 403 / 422, the
half-made tree is deleted and nothing is registered; a `kpis/*.toon` the registry cannot read is refused,
never seeded unchecked. The tree is seeded in a unique `_staging/<id>-<nanos>/` (discovery never boots it) and
renamed into place only once the gate passes, so a cleanup that fails (a Windows lock) is logged and suppressed —
the refusal stays the answer — and the leftover never blocks the id. With no Subject (Basic, an unauthenticated
recovery create) Dataset access is fail-open; a template Dataset shared away from the caller is refused as
"shared away", not as absent (template content is the server's own, so there is nothing to hide). A KPI tile in the template binds by `options.kpi.kpiId` (no new mechanism). ⚠ The gate
reads the Dataset's Schema, so a KPI over a Dataset that the template's own pipeline has not filled yet (a
`physicalRef` store with no Parquet — every fresh `orders-starter` store) is REFUSED at apply; that is why
`orders-starter` ships no KPI pack.

**The business assurance pack** (`spaces/_templates/business-assurance/`, `ASSURE-PACK-BUSINESS-ASSURANCE-1`,
wave 5.4, 2026-09-30) is the first content pack, and it dodges that trap by **generating its corpus in SQL**:
three hand-authored views (`config/views/ba_*_view.toon`, plain `derived_sql`) each open with a CTE that builds
a deterministic synthetic corpus from `range()` (a fixed-offset Weyl sequence into Box-Muller — no RNG state, so
every DuckDB and every run gives the same rows), then run the model over it. The Datasets are `view:`-backed, so
their Schema exists the moment the template is copied and the KPI / Alert Rule `by` gates pass at apply. What it
ships: `ba_revenue_forecast` — a **Holt-Winters additive** forecast (season 7, α 0.25 β 0.02 γ 0.15) written as
a `WITH RECURSIVE` whose state row carries the seven seasonal terms as a DuckDB `LIST` (`list_transform` with an
index lambda replaces one slot per step), with `lower_band` / `upper_band` = forecast ± 4σ̂, where σ̂ is a
MAD estimate over week-on-week differences (independent of the recursion, so an anomaly cannot widen its own
band). A point outside the band updates the state with the forecast instead of the actual (clipping), so one
spike does not drag the next season. ⚠ **Clipping alone can never re-base**: in the first build a +300 level
shift flagged every one of the 82 following days and a +8/day trend ramp flagged none (verification FAIL,
2026-09-30). So the recursion also carries (1) a **regime rule** — `regime_k` (3) consecutive out-of-band days
are a regime change: the K-th day sets `regime_change = 1`, the level re-bases on the actual and the run's
earlier days are NOT reported as `outside_band` (only a run shorter than K is a spike); (2) a two-sided
**CUSUM drift detector** over the in-band residuals in σ̂ units (`cusum_k` 0.25, `cusum_h` 8): crossing `h` sets
`drift_change = 1`, re-bases the level and adds a slope estimate from the CUSUM run to the trend; out-of-band
days do not feed it, so a spike or a shift never trips it. Consequence for a live tail: a run that is still
shorter than K reads as `outside_band` and becomes a regime change (its spike Alerts heal) if it reaches K, so
a real shift can raise up to K − 1 spike Alerts before its one regime Incident — **accepted as designed
(operator, 2026-10-06)**: the spike Alerts heal, and the pack runbook documents it. The anchor row must type the
CUSUM state `DOUBLE`: a `0.0` literal is `DECIMAL(2,1)`. At the default `cusum_h` 8 the sum resets before it
reaches 10, so this matters only when `cusum_h` is raised; the golden test raises it to 12 and fails without the
cast. ⚠ **Drift bound:** the CUSUM catches a trend change of roughly ≥ 0.2σ/day (+4/day on this corpus, caught on
day 143); anything slower is absorbed by the trend term and never flagged (a +2/day ramp is a golden
known-limit case), and a change inside the 28-day warm-up is absorbed silently or reported late as drift. All
constants live in the SQL's `p` CTE (view and Job alike); `outside_band`, `regime_change` and `drift_change` are
0 through a 28-day warm-up. The margin model assesses erosion only for a group with `status = assessed`: a
group with a null or negative revenue / cost line in the windows is `data_quality` (flag `data_quality_flag`,
its own Alert Rule) instead of reading as a margin change; no baseline lines is `new`; fewer than `min_lines`
(10) lines or `min_revenue` (1000) in either window is `insufficient`; `erosion_pp` is NULL for all three. `ba_margin_lines` / `ba_margin_erosion`
— revenue − cost by product / channel / partner, margin %, and `erosion_pp` = baseline (prior 28 days) margin %
minus recent (last 28 days) margin %. Five per-entity Alert Rules (`ba_revenue_outside_band`,
`ba_revenue_regime_change`, `ba_revenue_drift`: `max(<flag>)` `by [ds]` `gte 1`, CRITICAL; `ba_margin_erosion`:
`max(erosion_pp)` `by [product, channel, partner]` `gt 5`, WARNING; `ba_margin_data_quality`, WARNING) — since
2026-10-10 they read the Jobs' **sink** Datasets `revenue_forecast` / `margin_erosion`, not the view-backed demo
Datasets (**repointed after the live drive, operator 2026-10-10**), so a fresh Space raises nothing until the Jobs
have written a run; the golden test stands in for both Job runs by writing each view's SQL to
`data/<sink>/run.parquet` in a scratch data root before every sweep — three KPI
definitions, one dashboard, and two **disabled** `sql.template` Jobs that run the SAME model
SQL over the user's own `daily_revenue` / `margin_lines` stores (`BusinessAssurancePackGoldenTest` pins each view
to contain its Job's SQL verbatim, and the Job's SQL to pass `SqlGuard`). ⚠ An Alert Rule `threshold` must be
positive, so a 0/1 flag is armed as `gte 1`, not `gt 0`. ⚠ A WARNING rule raises an Alert but no Incident.
Golden result: 2 detections (the planted −400 day, 2026-06-04; the planted +15 % cost line, P3/online/PB), 0
false positives against 4 planted look-alikes; forecast RMSE 10.3 against the noise-free signal (σ = 20),
MAPE 1.7 %. Variant corpora (the test rewrites the corpus under the view's own model SQL): a ±level shift from
day 100 → exactly 1 regime change (day 102), then silence; a ±8/day ramp → exactly 1 drift detection within 21
days (measured 19 / 13); a scaled / re-noised copy (series x7 + 3000; a different noise realization) → only the planted spike (`theConstantsHoldOnASecondSyntheticCorpus`); and an **independently built second corpus** (operator 2026-10-10: weekend-peaked season, downward trend 6000 − 4t, σ 45, other Weyl offsets) with a planted spike, a +600 shift, a −15/day ramp and a 2-day excursion, plus look-alikes (a +300 day at ~0.8 of the band half-width, a 5-day +110 bump) → exactly `70:band, 112:regime, 125:band, 126:band, 159:drift` with the **shipped constants, no retune needed** — each constant moved one notch (z 3, K 2, h 3, h 20) turns it red (`aSecondCorpusGivesTheIntendedAlertsAndSilencesItsLookAlikes`). The pack was **driven live 2026-10-10** (5 Alert Rules; *Evaluate now* fired `ba_revenue_outside_band` CRITICAL for 2026-06-04 and `ba_margin_erosion` WARNING 11.25 pp on P3/online/PB); real customer series remain untested; bad margin input → 2 data-quality Alerts, `new` and `insufficient` groups silent. The
forecast Datasets' descriptions (view and sink) are kept under 60 characters so Alert text names them (`DatasetMeasureProbe.label`). Runbooks: `config/runbooks/business-assurance-runbooks.md`.

**Product gaps the pack recorded** (both decided 2026-10-04): the forecast is NOT a Measure function — the
Measure shorthand is `count | agg(field)`, so a forecast inside a KPI or Alert Rule would need an engine change;
the Dataset form was taken instead, and stays (no build; revisit if a customer needs forecasting inside an ad-hoc Measure). A hand-authored view cannot read another view, so each view inlines its
corpus CTE; this is by design: layering stays explicit through Job-written Datasets (avoids cycles, hidden cost and lineage ambiguity). A template's runbooks live at `config/runbooks/<pack>-runbooks.md` (`createFromTemplate` copies only `config/` and
`data/`, and rewrites `${SPACE}` in `.toon` files only); all three assurance packs follow this, and the seed-gate test
pins it. Since 2026-10-10 each typology also ships a `runbook` component (`registry/runbooks/fraud_<typology>.toon`) linked from its Alert Rule (operator, 2026-10-10). **Runbook referential integrity (2026-10-11):** a direct save (`/components/runbook`) refuses (422, naming `steps[i]` and the target) a step link to a `dataset`, `query` or `dashboard` that is not in the Space (a `case` link names a runtime object and is not checked); deleting a Runbook that an Alert Rule links is refused 409 naming up to 10 rules. Inline in `ComponentRoutes` (the `ComponentDeleteHook` SPI is after-delete cleanup only, it cannot refuse). Bundle, Space-template and Space-import writers stay lenient (structural check only), so load order cannot break an import.
Each Job's `sink_dataset` is a shipped Dataset (`revenue_forecast`, `margin_erosion`; `physicalRef` = the sink dir) with a zero-row
`data/<sink>/seed.parquet` (the telco-fraud pattern), so enabling a Job needs no hand re-point; regenerate the seeds with env
`BA_SINK_REGENERATE=true` (`BusinessAssuranceSinkDatasetTest`). The Alert Rules and dashboard still read the view-backed demo Datasets. Decision (operator, 2026-10-04): repointing them to the sink
Datasets stays MANUAL until the pack has been driven live; no second Alert Rule set ships.

**The telecom fraud pack — `spaces/_templates/telco-fraud/`** (`ASSURE-PACK-TELCO-FRAUD-1`, wave 5.1 of
`archived-documents/plans-archive/assurance-capability-plan.md`, BUILT 2026-09-30, verified 2026-10-03, retired from the board 2026-10-14 after the golden test re-ran green 8/8; the three items that wait on a customer or a model change are P3 `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1`: Schema-from-Job (deferred 2026-10-04), vendor thresholds (parked), register-matched exemptions (waits on a customer register)). Three feed Pipelines + schemas (`cdr`,
`subscriber_events`, `payments`; each schema needs a `mapping:` — a `raw:`-only schema fails every file on the
Consignment path with "a mapping with neither fields[] nor rules[]"); thirteen detection windows as `sql.template` Jobs (ten at the 2026-09-30 build; see *Content gaps closed* below)
(`config/jobs/fraud_<typology>_job.toon`: IRSF, Wangiri, SIM-box, premium-rate, roaming high usage, SIM-swap,
subscription / identity, dealer activations, voucher / EVD, payment reversal), each writing one row per candidate
entity per `window_date` to its sink Dataset `fraud_<typology>`; and one per-entity Alert Rule per typology, `by` =
the offender alone (`msisdn`, the ringing number, `id_doc`, `dealer_id` or `voucher_serial`), Measure `max(<feature>)`
over every retained window, `stormCap: 500`. No new Step Processor. **Thresholds are configuration in two places**: the Job's parameters say what
counts (`irsf_prefixes`, `premium_prefixes`, `home_cc`, `max_ring_seconds`, `callback_hours`, `max_cells`,
`exempt_msisdns`, `exempt_doc_prefixes`, `window_hours`, `traffic_grace_hours`, `window_start` / `window_end`,
`day`) and the Alert Rule's `threshold` (`gt`) says how much is too much. **Schedule:** every detection Job carries `cron: "0 2 * * *"` and the rolling window `window_start: "$yesterday"` / `window_end: "$today"` (job-parameter Expressions, resolved at fire time in the Job's zone), so a created Space detects the previous day each night; a manual run (and the golden test) overrides both with literal timestamps, plus `day` (the partition the run replaces, below). Also: four KPIs + tiles, one
dashboard (`telco_fraud_overview`: the four KPI tiles plus one bar Widget per typology over its sink Dataset,
keyed on the offender — the golden test renders every tile through `/kpis/{id}/value` and `/bi/query`), and `config/runbooks/telco-fraud-runbooks.md` (plain Markdown, kept for the shared guidance; since 2026-10-10 each typology's steps are ALSO a
`runbook` component linked from its Alert Rule and shown on the Incident/Case page, operator 2026-10-10, superseding the 2026-10-04 park), which names the known false-positive sources that have no defence. **Edition gating:** since 2026-10-18 the template declares `minEdition: standard` (its Alert Rules are Standard+, below), which `tools/check-offerings.mjs` holds against every Offering that lists the pack. *(History: the 2026-10-04 decision was "none — the template ships to all editions".)* **Two product gaps decided DEFER 2026-10-04, no build until a customer asks:** a Dataset whose Schema comes from its producing Job (the pack ships zero-row `data/fraud_*/seed.parquet` Schema seeds instead) and an incremental Job Type mode — the latter since BUILT (operator, 2026-10-10) and enabled in this pack 2026-10-11 (below). Known limits (not product gaps): thresholds are generic defaults (the vendor-specific half
stays parked); each run writes one day partition, and the identity / dealer / voucher / reversal
sinks keep every entity, so they grow with the subscriber base and with every retained day — **sizing documented (operator, 2026-10-06)**:
size by subscriber count x `retain` (pack runbook § *Sizing and retention*).

**KPI tiles follow the data (`TELCO-FRAUD-DEMO-DATE-1`, 2026-10-14).** A KPI's period was today while the template's rows are keyed on `window_date` = yesterday (the seed shifts the sample feeds), so the four tiles read `value: null`. One opt-in KPI key, `asOf: latest` (`KpiDefinition.asOfLatest`, validated in `fromMap`: only `latest` or absent, anything else 422 at save), makes `GET /kpis/{id}/value` with no `asOf` query evaluate as of the newest day present in `timeField` in the KPI's zone (`KpiEvaluator.latestDay`), never past today; an empty Dataset falls back to today, and an explicit `?asOf=` always wins. A KPI without the key is unchanged. The four telco-fraud KPIs carry it. Tested in `ControlApiKpiTest` (yesterday-keyed rows read a value, today-keyed rows still read today, no opt-in stays today). The `asOf` of the response says which day was read.

**Content gaps closed 2026-10-09 (`TELCO-FRAUD-CONTENT-GAPS-1`).** Three typologies added to the same template, no new feed and no platform edit: `fraud_recharge` (card-testing / top-up velocity: PAYMENT top-ups under `small_amount` per line, `by` = `msisdn`, `gt 8`), `fraud_data_bypass` (DATA sessions rated at zero outside `zero_rated_apns`, `by` = `msisdn`, `gt 7200` s) and `fraud_internal` (SIM swaps per staff user outside `exempt_staff`, `by` = `staff_id`, `gt 5`), each with its Job, per-entity Alert Rule, sink Dataset + zero-row seed, bar Widget on `telco_fraud_overview` (now 17 tiles) and runbook section. **Seams:** a DATA record reuses the CDR columns (`service = 'DATA'`, `duration_s` = session seconds, `b_number` = the APN) and a staff SIM swap reuses `subscriber_events.dealer_id` as the staff user, so the existing ten Jobs' filters (`VOICE`, `ACTIVATION`, `PAYMENT`) were verified unaffected and the old seeds untouched. **Deliberate cuts:** no KPI for the new typologies, no data-volume (bytes) column, no staff-to-own-line cross-check (needs a staff register Reference Dataset, the same gap as `exempt_doc_prefixes`), no recharge-channel / payer-instrument key (the payments feed carries none). **Gotcha:** the 16-day simulation suffixes every identifier per day, so a recurring exempt or at-threshold key (`zero.operator.apn`, `HELPDESK`, `S111`, the two at-threshold lines) must be added to the golden test's `RECURRING` list. Golden: 13 rules, 54 Alerts; mutation-checked (small_amount 5 to 7 raises the bill-payer, `zero_rated_apns` to `none` raises the zero-rated portal, dropping `charge = 0` raises the charged-data line, `exempt_staff` to `none` raises `HELPDESK`).

**Manual adjustment fraud added 2026-10-10 (`TELCO-FRAUD-MANUAL-ADJUSTMENTS-1`).** A fourteenth typology, `fraud_adjustment`: manual ADJUSTMENT credits summed per staff agent outside `exempt_agents` (default `BILLING-DESK`, the billing-correction desk), `by` = `agent_id`, `measure` = `max(adjusted_amount)`, `gt 500`, CRITICAL; Job, Alert Rule, sink Dataset + zero-row seed, bar Widget (`telco_fraud_overview` now 18 tiles) and runbook section. **Seam:** the payments feed had no staff column, so it gained one trailing `agent_id` column (blank on every non-ADJUSTMENT row) and a new `txn_type` value `ADJUSTMENT`; the four existing payments Jobs all filter on `txn_type` (`PAYMENT`, `VOUCHER`, `REVERSAL`), so they are unaffected and their seeds untouched. The schema change reaches only Spaces created from the template afterwards. Planted: three agents at 900, one JUST above (500.01), one AT 500, one with small goodwill credits and the desk at 6000; `A211` and `BILLING-DESK` joined the golden test's `RECURRING` list. Golden: 14 rules, 58 Alerts; mutation-checked (`exempt_agents` to `none` raises `BILLING-DESK`, `threshold` 499 raises `A211`, filtering `PAYMENT` instead of `ADJUSTMENT` raises nothing). Cut, as for `fraud_internal`: no staff-to-own-line cross-check (needs a staff register Reference Dataset).

Detection semantics worth knowing: dialled numbers are normalised to one E.164-digit format (`+` / `00` stripped,
national `0…` → `home_cc` + NSN) before a prefix list is matched with `starts_with`, entries of any length; a prefix
list entry that is not 1–15 digits fails the run (DuckDB `error()`), never matches silently. Wangiri counts ring
targets that call the ringing number back within `callback_hours` AFTER the ring, so a flash-call OTP sender stays
silent. SIM-swap anchors each payment to the latest swap before it, so a payment counts once however many swaps
precede it. Dealer activations look for traffic up to `traffic_grace_hours` past the window, so late-day activations
are not idle.

⚠ **Each Job is partition-incremental by `window_date` (2026-10-11, `ASSURE-PACK-TELCO-FRAUD-1` (b)).** Every
detection Job declares `incremental: {by: day, column: window_date, lookback: 1, retain: 31}` and `day: "$yesterday"`, so a run
replaces only its own window's `data/fraud_<typology>/window_date=YYYY-MM-DD/` folder and never opens the others
(→ `okf/product/module/processor/spec-jobs.md`); the Jobs no longer read their own sink (until then they re-emitted every
other `window_date` within `retention_days` in their SQL). A manual run passes `day` beside the literal window, or its
rows fall outside the lookback and are dropped. A same-window re-run replaces that window, never duplicates it; a later
window's run neither raises nor heals an earlier offender's Alert. **Retention:** `retain: 31` (the old
`retention_days` horizon: the window plus 30 earlier days) makes each run delete older day folders (never the seed folder), so a key heals
when all its evidence ages out (its Incident stays open — D-P11); the golden test pins the heal day (kept on day 31, healed on day 32). ⚠ **The storm
cap counts every retained breaching key**: `AlertService.evaluateGrouped` marks the rule's one Alert a storm (and
opens ONE storm Incident) once the total exceeds `stormCap`, and no new offender gets its own Incident until it drops back. A first cut keyed on
offender + `window_date` with `stormCap: 50` hit that around day 11 at the corpus rate; keying on the offender bounds
the count by distinct offenders, and `stormCap` must stay above `retain` x daily offenders. ⚠ **Scaling**: each run writes one day (≈ daily candidates); `fraud_simbox` keeps only lines
with ≥ `min_candidate_targets` targets, the identity / dealer / voucher / reversal sinks still keep every entity. ⚠ **The exemption lists are a trust assumption**: `exempt_doc_prefixes`
matches a prefix of `id_doc`, which the dealer enters, so a made-up `CORP-FAKE` document is exempted (pinned in the
corpus as a known risk). The lists must come from a verified register, never from a field the monitored party
controls; matching a verified-register Reference Dataset will be built WHEN a customer supplies one (operator,
2026-10-06); until then the prefix-spoof risk stays pinned and documented. ⚠ **Seeds, and
why.** The per-entity Alert Rule save gate (`AlertRoutes.requireGroupingColumns`, run by the template seed gate) and
the KPI gate both read the Dataset's Schema and fail closed on a Dataset with no data; so the template ships a
**zero-row seed** per sink in the day layout, `data/fraud_<typology>/window_date=1970-01-01/seed.parquet` (a
placeholder day no run replaces; a flat `seed.parquet` would be refused by the incremental layout gate). The
reader auto-detects the `window_date=` folders, so the Schema carries `window_date` (as the last column). A test regenerates them from each Job's SQL and fails when a seed's Schema drifts. ⚠ The Alert Rules
are Standard+: a Basic build refuses the whole template at `POST /spaces`, though the gallery still lists it.

**Golden corpus**: synthetic, from a fixed-seed generator (`inspecto/src/test/java/com/gamma/control/TelcoFraudCorpus.java`,
seed `20260930`; fictional numbering — home country code `999`, foreign callers `287` / `289`) committed under
`data/samples/`, with planted offenders (one JUST above each threshold) and planted look-alikes (one exactly AT each
threshold, plus flash-call OTP, a registered PBX line, a business traveller, a corporate multi-line document,
late-day activations, a two-swap / one-payment line). `TelcoFraudTemplateGoldenTest` boots the template through
`POST /spaces`, ingests the corpus through the three Pipelines on the production Consignment path
(`CollectorProcessor.run`; every CSV row must land), runs the ten Jobs, asserts each Alert Rule raises EXACTLY its
planted offenders (5/4/4/5/4/4/3/3/4/4 = 40 breached keys, counted in each rule's ONE Alert) and no look-alike, re-runs the same window (no duplicate row, no
Alert change), then runs the next window (nothing raised or dropped); a second case runs 16 retained days of
fresh offenders at the corpus rate — each rule's one Alert counts every retained offender (640 keys in all, each its own digest), no storm. Each defence was mutation-checked red.

**Demo rehearsal 2026-10-14 (`TELCO-FRAUD-DEMO-REHEARSAL-1`, no code row).** A fresh `package.ps1 -Edition Advanced -DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `package.ps1 -Edition Advanced -Distribution demo`)* bundle (main `gamma` UI),
a Space made from this template through *Settings > Spaces > New from template*, the three sample files copied into `data/inbox/<feed>`, driven in the
browser as `admin` (`admin;super`), `fm.analyst` (`operations`), `fm.manager` (`power`) and a non-privileged `business` viewer. **GO for a presenter who
follows the checklist below, NO-GO for a cold start** (the three P2 rows). Beat list (this is the checklist):

| # | Beat | Result | Evidence |
|---|---|---|---|
| 0 | Sign in on a fresh bundle | **FAIL** -> `TELCO-FRAUD-DEMO-BOOTSTRAP-1` | `/bootstrap` `demoUsers: []`; the template cannot carry `demo-users.toon` (import-reserved) |
| 1 | Create the Space from the gallery | **PASS** with `admin;super` (**FAIL** as seed `admin`: refused, `canAuthorAlertRules`) | Space folder + 15 Jobs + 14 Alert Rules seeded; gallery text said *thirteen* (fixed: fourteen) |
| 2 | Deliver the feeds (polled inbox, 60 s) | **PASS** | status CSVs: CDR 3359, payments 698, subscriber events 503 rows, 0 error rows |
| 3 | Ingest quality: Expectations, quality Incidents | **FAIL** -> `TELCO-FRAUD-STORY-GAPS-1` | `/expectations` empty, Home *Expectations breached 0* |
| 4 | Detection Jobs (14, by API with the golden window) | **PASS**, with `TELCO-FRAUD-DEMO-DATE-1` | 14 x SUCCESS, rows per sink 6-346 |
| 5 | Alert Rules raise Alerts | **FAIL as shipped, fixed**: no Job evaluated the rules overnight; `fraud_alert_evaluate` (`alert.evaluate`, `30 2 * * *`) added | `POST /alerts/evaluate` -> **58 Alerts**, exactly the golden count, one per offender per rule (per entity by design; the sequence_gap decision belongs to the LA demo and does not apply here) |
| 6 | Alert -> Incident | **PASS** | 30 CRITICAL Incidents (WARNING rules stay Alerts), runbook, tags `fraud` / `telco`, evidence attributes (`rule`, `key`, `value`, `threshold`, `measure`) |
| 7 | Incident workflow as `fm.analyst`: Accept | **PASS** (IDENTIFIED -> DIAGNOSING, no assignee) | `TELCO-FRAUD-INCIDENT-PRIORITY-1` |
| 8 | SLA badge | **PASS** (badge shows) but **wrong target**: 24 h `*` fallback | `TELCO-FRAUD-INCIDENT-PRIORITY-1`; the policies are applied by hand from `config/examples/` |
| 9 | Case with assignment | **PASS** | created in *Case Manager* (assignee `fm.manager`, linked Incident); no Case Rule, so never automatic |
| 10 | Decision Rules | **FAIL** -> `TELCO-FRAUD-STORY-GAPS-1` | `/decision-rules` empty; the template ships none |
| 11 | Dashboard + Value Measures | **PASS** structurally (18 tiles, 14 bars render), **FAIL** for the KPI tiles on the sample (`value: null`) | `TELCO-FRAUD-DEMO-DATE-1` |
| 12 | Masking for a non-privileged persona | **FAIL** -> `TELCO-FRAUD-MASKED-DASHBOARD-1` (closed 2026-10-18) | viewer read raw `msisdn`; feed schemas now `MSISDN`; sinks classified, every bar renders masked |
| 13 | Reports | **FAIL** -> `TELCO-FRAUD-STORY-GAPS-1` | no scheduled export or report in the template |

**Bootstrap (2026-10-14, closes `TELCO-FRAUD-DEMO-BOOTSTRAP-1`).** `node tools/seed-telco-fraud-demo.mjs --spaces inspecto-demo/spaces` writes a `telco-demo` roster Space (`admin;super`, `fm.analyst` `operations`, `fm.manager` `power`, `ra.viewer` `business`); sign in as Demo Admin, create `telco-fraud` from the gallery, run the script again: it copies the roster in and drops the three sample feeds into `data/inbox/<feed>` with every date shifted so the sample day is yesterday (the Jobs roll to `$yesterday`). Test: `tools/seed-telco-fraud-demo.test.mjs`. Still open: the KPI tiles read today (`TELCO-FRAUD-DEMO-DATE-1`).
