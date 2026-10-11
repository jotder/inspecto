---
type: Concept
title: Anomaly Scores — explainable behavioural anomaly scoring per entity
description: The anomaly-model component kind, the anomaly.score Job writing the scores Datasets, self and Peer Group baselines with the cohort shift, GET /anomaly-scores/{model}/{entityKey} + POST /anomaly-scores/preview, the afterScore pending Alert Rule, the Studio pane and the entity anomaly panel.
resource: features/inspecto-anomaly/src/main/java/com/gamma/anomaly/AnomalyScoreEvaluator.java
tags: [control-plane, anomaly-score, job, alert-rule, incident, assurance, entity-list]
timestamp: 2026-10-10T00:00:00Z
---

# Anomaly Scores

> **Module.** Anomaly Detection is the optional `features/inspecto-anomaly` module (id `anomaly`, package
> `com.gamma.anomaly`, add-on `anomalyDetection` `status: built` in `offerings/professional.toon`, so
> Professional, Enterprise and Preview; not Personal — D-AD4). It depends on `inspecto-entity-list`
> (`requires.modules: entity-list`) and **not** on `inspecto-scoring`: the pieces it shares with
> [Risk Scores](risk-scores.md) live in the engine (D-AD5). Without the module `/anomaly-scores*` answers 503
> and an `anomaly-model` component is unknown to the SPA, which then hides every anomaly surface.
> Built 2026-10-10 in slices S1..S5 (`ANOMALY-DETECTION-1`); the design and its lane notes are archived at
> [`anomaly-detection-design.md`](../../../archived-documents/plans-archive/anomaly-detection-design.md).
> Open items: `ANOMALY-DETECTION-RESIDUALS-1` in [BACKLOG](../../../BACKLOG.md).

An **Anomaly Score** (GLOSSARY: *Anomaly Model* = the Type, *Anomaly Score* = the Instance, *Feature*,
*Peer Group*) answers *"is this entity behaving unlike itself, or unlike its peers, and why?"*. It is a 0–100
number per entity per run, stored with a per-Feature explanation so it is recomputable. Keep it apart from a
**Risk Score** (absolute weighted factors the author thresholds), a `baseline` **Expectation** (batch data
quality) and the Link Analysis `suspicionScore` (client-side graph centrality).

## The model — the `anomaly-model` component kind

Stored at `registry/anomaly-models/<id>.toon` through `ComponentStore` (`WRITABLE_TYPES`, `ComponentRegistry`
`anomaly-models → anomaly-model`), so history, diff, restore and the maker-checker hold come from the generic
component routes. Parsed fail closed by `AnomalyModel.fromMap` (unknown keys refused); `AnomalyLists` parses
`watchList` / `exclusionList` beside it.

```
name: Subscriber usage anomalies
entityType: subscriber
bucket: day            # 'hour' refused: not built
window: 28             # days, max 90; excludes the scored day
scoredPeriod: 1        # only 1 is accepted
seasonality: weekday   # none | weekday (hour, weekday_hour refused for a day bucket)
minBaselinePoints: 3   # default 7, <= window
elevatedThreshold: 60
highThreshold: 80
maxEntities: 500000    # else -Danomaly.score.maxEntities; fail, never partial (D-AD10)
dataScope: fraud
watchList: {list: usage-anomalies, ttlHours: 24}   # 1..24, default 24
exclusionList: known-heavy-users
peers:
  by[1]: tariff_plan   # 1..3 columns, may not include key
  dataset: subscribers # default: the first Feature's Dataset
  key: msisdn          # join column in the peers Dataset; default: the first Feature's key
  minGroupSize: 30     # 2.., default 30
  fallback: none       # none | population (opt-in, flagged fellBack — D-AD11)
features[1]:
  - id: data_mb
    label: Data volume (MB)
    dataset: cdr_data
    key: msisdn
    time: event_time   # must be a DATE / TIMESTAMP column
    measure: sum(volume_mb)   # count | agg(field), MeasureCompiler, Measure-grammar filters
    direction: up      # up | down | both
    weight: 1
```

Outputs are derived, never authored (an authored `scoresDataset` is a 422): `anomaly_scores_<id>` (history)
and `anomaly_scores_<id>_latest`, naming facts in the engine's `com.gamma.alert.AnomalyScoreOutputs`.
🔴 The `anomaly_scores_` prefix is **reserved** on every writer exactly as `risk_scores_` is, with the
ownership marker `.anomaly-score-output` (`com.gamma.alert.ScoreOutputDirs`, shared with Risk Scores). The only
Dataset accepted over a scores store is the identity shape (`id = physicalRef`, nothing else).

**Save-time gates (422), in Risk Score order:** `fromMap` (structure, unknown keys, every Feature compiled) →
`AnomalyKindValidator` (`ComponentKindValidator` SPI: every named column — key, measure, filters, `time`, peers
`key` + `by` — in its Dataset's Schema, `time` DATE/TIMESTAMP, output collisions, reserved prefix, live Entity
Lists of the right purpose) → `PendingChanges.hold`. Every writer (component route, bundle, template apply)
runs them. ⚠ **The gate needs a Schema at apply**: a template's Feature Datasets ship a zero-row
`data/<feed>/database/schema-seed.parquet` (read `union_by_name`; no run replaces it) rather than relaxing the
gate.

**No classified cohort column (2026-10-11, `fa8a9b3cb`, residual (f)).** `AnomalyKindValidator.requireUnclassifiedCohort`
refuses (422) a `peers.by` column classified MSISDN / IMSI / ACCOUNT / PII, because the cohort value is stored
raw in every score's explanation (`peerBaseline.cohort`) and quoted in its `reason`. It reuses
`EvidenceMasker.sensitiveColumns` as-is — the Dataset's own column classification, sibling Datasets over the
same store, and pipeline schema lineage. ⚠ It **fails closed** when the peers Dataset's lineage cannot be
traced while classified columns are in play (a view / SQL Dataset over such a store) — stricter than the design
wording; **the operator is to confirm** (BACKLOG §1). The peers join `key` is not checked: it stays raw by design
(D-P8). Pinned by `AnomalyKindValidatorTest`.

## The scoring — `anomaly.score` Job

Job Type `anomaly.score` (`AnomalyScoreJobType`; params `model`, optional `as_of` YYYY-MM-DD, UTC days)
scores the day before `as_of` over a `window` that excludes it. `AnomalyScoreEvaluator` issues one grouped
DuckDB statement per Feature (day grain); the pure maths is `AnomalyScorer` plus `com.gamma.anomaly.baseline`.

- **Self baseline** (`BaselineStatistic` seam ← `BaselineStatistics.forModel`; `SeasonalBaseline`):
  median and `MAD × 1.4826` of the same-slot buckets, walking the fallback chain `weekday → none` until a
  slot has `minBaselinePoints`; the explanation records `requested`, `basis`, `fellBack`. Floor
  `max(1 unit, 5 % of |median|)` (`RobustStats`) stops a flat history turning +1 into infinity.
- **Missing buckets (D-AD8):** 0 for count/sum, absent for avg. An entity's history **starts at its first
  bucket in the window** (any Feature) — densifying from the window start scored a two-day-old entity `high`
  (caught by the golden corpus; this reading CONFIRMED by the operator 2026-10-10).
- **Peer Group** (`PeerBaseline`): ONE grouped cohort statement `max(concat_ws('|', by...))` per key (several
  values → the greatest; none → population only). The peer baseline is median / MAD of the scored-day values
  of the cohort's scored entities; a cohort below `minGroupSize` is `insufficient` (D-AD11). Peer medians are
  computed **in the JVM** from the per-entity series already in memory (not in SQL).
  ⚠ **NULLs in a multi-column `by` — characterised, policy OPEN (2026-10-11, `bd18b0371`, residual (g)).**
  `concat_ws` skips NULLs, so ('A', NULL) and (NULL, 'A') both key to cohort `'A'` and are scored as peers — a
  real collision; an all-NULL row keys to `''`, which the evaluator drops (population only, the same as having
  no peers row). Untested sibling: a value holding the `|` separator can collide too, e.g. ('A|B', NULL) vs
  ('A', 'B'). `AnomalyScoreEvaluatorTest` pins today's behaviour, not the intended policy. Recommendation
  recorded for the operator: exclude rows with a NULL in any `by` column (count them as unscored) and build the
  key collision-free.
- **Cohort shift — CONFIRMED (operator, 2026-10-10).** With a plain `max(|zSelf|, |zPeer|)` a whole cohort
  moving together (a promotion day) goes `high`. So each member's self baseline is scaled by
  `k = peer median today / the cohort's usual daily median` (the usual is the model's own `BaselineStatistic`
  over the cohort's per-day medians, so seasonality applies; `k = 1` when the cohort is insufficient or its
  usual is not > 0). Shown in `reason` as `, xk cohort shift = expected` and stored as `cohortShift`. A member
  that moves alone is still `high`.
- **Combining:** `dev_f = max(dev(zSelf), dev(zPeer))` in the declared `direction`, capped at `zCap`
  (default 10); `raw = sqrt(Σ w·dev²) / sqrt(Σ w)` over sufficient Features; `score = 100·(1 − exp(−raw/3))`
  (D-AD6); bands `normal < elevatedThreshold ≤ elevated < highThreshold ≤ high`. Self insufficient with usable
  peers → scored on peers only, flagged `peersOnly` (D-AD12); neither → `insufficient`, contributes 0.
- **Exclusion — before the cohort medians AND before the SQL LIMIT (decided for the operator, 2026-10-10).**
  The `exclusionList` predicate is passed INTO `AnomalyScoreEvaluator.evaluate`: each Feature first reads its
  DISTINCT keys in range (bounded by `MAX_ENTITIES_CEILING`, fail closed), tests them in the JVM (ranges and the
  list's normaliser live there) and binds the hits as `?` parameters in a `NOT IN` before the grouping. So an
  excluded entity never shapes its cohort's baseline nor counts against `maxEntities`; `Run.excluded()` is
  logged and signalled. No list = `AnomalyScoreEvaluator.NONE`, no extra statement. Proven by
  `AnomalyPeerGoldenCorpusTest#anExcludedHeavyEntityDoesNotShapeItsCohortMedian` and
  `#excludedKeysDoNotCountAgainstTheEntityCap`.
- **Writes:** history parquet + `_latest` swap (`*.tmp` + atomic move), columns `model, entity_type,
  entity_key, period_start, score, band, raw, features (JSON), insufficient_count, model_version, run_id,
  scored_at`; then the watch-list feed (`high` entities, `job:anomaly.score:<id>`); then the Signal
  `SignalType.ANOMALY_SCORE_PRODUCED` (core; `AnomalySignals` aliases it). A query failure rethrows with
  model, Feature, Dataset and error class only — never the DuckDB message. `AnomalyScorer.recompute` re-derives
  score and band (both z) from the stored numbers; tests assert equality.
- **Entity Lists seam:** `WatchListFeed.check(purpose)` (`WATCH` | `EXCLUSION`) returns the live-membership
  predicate; the one Identity-Fact-log read site stays `RiskWatchListFeed#check`. Lists absent → the run fails.

## Routes

`AnomalyScoreRoutes` (`RouteModule` feature `anomaly`; the module's `openapi.fragment.json` is merged into
`docs/api/openapi-v1.json`):

- `GET /anomaly-scores/{model}/{entityKey}` — reads the HISTORY store, newest first, ≤ 30 runs: row 1 is the
  latest score with its `features`, all rows are the `history` sparkline. Gate `canWorkIncidents`; unknown /
  unparseable / out-of-scope model, `RowScope` deny and no row are one indistinguishable 404; the key is bound.
- `POST /anomaly-scores/preview` — `{model | content, entityKey, asOf?}`; unsaved `content` also needs
  `canAuthorWorkbench` and passes the save gates. Writes nothing; runs under `PREVIEW_POLICY`
  (512 MB / 2 threads / 10 s; past it → 422 with a value-free message). Without `peers` every Feature is
  narrowed to the entity (bound filter, cap 1); **with `peers` it reads the Features and cohort in full and
  scores only the entity** (`scoreOnly`), so cohort medians and shift equal the run's.
- Both mask the key on read (`com.gamma.mask.EntityKeyMasking`, shared with Risk Scores; `keyMasked`) unless
  the caller holds `canRevealLinkEntities`. Not `ImportCapabilityGuard`-`ACTS`-listed: `/anomaly-scores` is under
  no writable kind's prefix.

## Incidents — the `afterScore` pending Alert Rule

No new alerting path: declare `anomaly_scores_<id>_latest` as a Dataset and point a per-entity Alert Rule at
it (`max(score)`, `by: [model, entity_key]`, `gte highThreshold`); it heals when a later run's `_latest` scores
the entity normal (`AnomalyScoreAlertTest`). Templates cannot seed it at apply (no Schema before the first
run), so they use the deferred seed `config/pending/alert-rules/<name>.toon` with the generalised trigger
**`afterScore: {kind, model}`** (D-AD7; `kind` = `risk-score` | `anomaly-model`, `PendingAlertRules.KINDS`).
The writer is **`PendingAlertRules#onScoreProduced`** (renamed from `#onRiskScoreProduced`, operator-approved
inventory edit 2026-10-10: the `PENDING_ALERT_RULES` reason in `ConfigWriteFunnelTest.WRITERS` and the
`DecisionRuleWritersTest.GUARDED_BY` value); `CollectorService` maps either Signal to its kind. AUDIT
attributes are `scoreKind` + `model`. Composition with Risk Scores: a `_latest` Dataset can be a Risk Score
factor (`measure: max(score)`) — a documented pattern, no code.

## UI

- **Studio ▸ Anomaly Models** (`/anomaly-models`, `modules/admin/anomaly-models/`, nav beside Risk Scores,
  `navFeature: anomaly`): list / detail / create / edit (`PUT` + `If-Match`) / delete / History over the generic
  component routes, gated `canAuthorWorkbench`; the 202 hold reads as held. Schema-form for the scalars + a Feature
  row editor (Dataset picker over readable Schemas, key/time pickers, time DATE/TIMESTAMP only, shared
  `count | agg(column)` grammar). A 422 lands on the field or Feature row it names (`mapAnomalyRefusal`).
  Since 2026-10-11 (`a8c4c14f7`, residual (h)) the Feature row also edits `filters` (operators = the
  `MeasureCompiler.filterTerm` set: `=` `!=` `>` `>=` `<` `<=` `in` `like` `isNull` `notNull`;
  `anomaly-feature-fields.ts`) and `unit` (> 0). ⚠ Both still travel in the draft's `extra`; moving them into
  `FEATURE_MODELLED` in `inspecto/anomaly/anomaly-model-form.ts` would be cleaner (follow-up note).
  The preview box (gated `canWorkIncidents`) scores a saved model for one key: badge, per-Feature table and an
  SVG strip of the baseline (median ± 3 MAD, observed point) with a text alternative. The form dialog's
  **Preview the draft** box scores the **unsaved** content through `AnomalyModelsService.previewContent`
  (`POST /anomaly-scores/preview` with `content`; gated `canWorkIncidents`, the dialog itself needs
  `canAuthorWorkbench`). Services: `inspecto/api/anomaly-models.service.ts` (config + preview),
  `inspecto/anomaly/anomaly-model-form.ts`.
- **Entity anomaly panel** `<inspecto-anomaly-panel [entityKey] [model]?>`
  (`inspecto/components/anomaly-panel.component.ts`, reads via `inspecto/api/anomaly-scores.service.ts`,
  helpers `inspecto/anomaly/anomaly-score-view.ts`): band badge, Features by contribution (top 3, "Show all N"),
  observed vs baseline (+ peer) median and `reason`, a `currentColor` SVG sparkline, the key (a `keyMasked`
  key shows the `masked:` token with no reveal; otherwise dotted to the last 4 with a "Reveal key" toggle).
  Every failure renders nothing (404 = no score; 503 / unknown kind = module absent).
  **Model choice (decided by the lane):** a host that knows the model passes `[model]`; one that knows only the
  entity omits it and the panel shows one block per model that has a score for it — no selector.
  **Hosts:** the Incident page (`object-detail.component.html`, beside the Risk Score panel under the same
  `key.model` + `key.entity_key` condition, `[model]` bound — each panel hides on the other kind's 404); the Risk
  Score entity view (entity only); and Link Analysis — ⚠ there is no LA "entity drawer", so the panel sits in
  the **toolbox scoring view** (`link-analysis-toolbox.component.html`, beside the picked node's Risk Score
  panel) and therefore appears **only once a Risk Score model is named** (that section's existing gate).
- **Templates:** `telco-fraud` → `tf_subscriber_usage` (per `a_number` over `cdr`, no peers);
  `mobile-money` → `mm_wallet_activity` (per `wallet_id`, peers `by: [kyc_tier]` from `wallets`); `aml` →
  `aml_account_transfers` (per `account_id`, no peers). Each: day / 28 / `weekday` / `minBaselinePoints: 3` /
  high 80, an `anomaly.score` Job (`45 4 * * *`), and an `afterScore` pending rule; no Entity Lists.
  `AnomalyTemplateModelsTest` pins them ([spaces §3.5.5](../../capabilities/spaces/spaces.md)).

## Gotchas

- With `peers` declared, an *always-heavy* entity is a correct U5 outlier against its cohort; a legitimately
  heavy entity belongs on the `exclusionList`, not in a weaker peer rule.
- Mutation checks that turn the golden corpora (`AnomalyGoldenCorpusTest`, `AnomalyPeerGoldenCorpusTest`) red for
  the expected value: MAD floor dropped, mean for median, scored day kept in the baseline (at BOTH sites — keeping
  it at the Job alone is an equivalent mutant), seasonality off, cohort shift off, peer z dropped, cohort of 1.

## Deliberate deferrals

Tracked in `ANOMALY-DETECTION-RESIDUALS-1`: the dashboard tile (the Widget layer lacks a Widget-level filter and
a table row limit, and `/bi/query` does not mask entity keys in scores Datasets — a top-10 Widget stays out of
templates until BI masks); pack golden extension (needs multi-week corpora); evidence rows; hour buckets; JVM
peer-median performance unmeasured (D-AD9: full recompute until measured slow); NULLs in a multi-column `by`
(characterised above, awaiting the operator's policy call); pane: day-by-day chart series (a preview
response-shape change); the LA panel's Risk Score model gate. Later: an ML scorer behind the same explanation
shape (D-AD2). (Shipped 2026-10-11 and removed from this list: refusing a classified cohort column at save;
Feature `filters` / `unit` fields; unsaved-content preview.)

## Decisions (operator, 2026-10-10)

D-AD1 names (Anomaly Model / Anomaly Score / Feature / Peer Group) · D-AD2 robust z + seasonality + peers,
weighted RMS · D-AD3 a new kind, not a Risk Score factor type · D-AD4 optional module, Professional+ · D-AD5
shared pieces extracted to the engine · D-AD6 0–100 via `1 − exp(−raw/3)` · D-AD7 `afterScore: {kind, model}` ·
D-AD8 missing = 0 for count/sum, absent for avg/ratio · D-AD9 full recompute now, incremental when measured
slow · D-AD10 cap + fail · D-AD11 small cohort `insufficient`, opt-in `fallback: population` · D-AD12 peer-only
score for entities without self history, flagged.
