# Anomaly Detection add-on — design (`ANOMALY-DETECTION-1`)

> **State: BUILDING — lane S1 built 2026-10-10 (see §16-A); decisions taken (operator, 2026-10-10).** In-flight plan for the BACKLOG row `ANOMALY-DETECTION-1`
> (P3). The row's home is the offering map in
> [module-reorganisation-decisions.md](../okf/backend/module-reorganisation-decisions.md), which files Anomaly
> Detection as a not-built add-on. Every decision this design needs is in §17 (*Decisions*, D-AD1..D-AD12)
> all approved as recommended (operator, 2026-10-10).

## 1. Why — the gap

Today the platform detects *deviation* in four places, and none of them answers *"is this entity behaving
unlike itself, or unlike its peers, and why?"*:

| Existing capability | What it compares | Why it is not entity anomaly detection |
|---|---|---|
| `baseline` Expectation ([observability §3.10](../okf/capabilities/observability/observability.md)) | an input's profile (row count, null rate, mean…) against the median of the last N accepted profiles, optionally per `groupBy` group | data-quality gate on a batch; fixed percent/absolute limits; no per-entity history, no seasonality, no explanation beyond the cell |
| Business-assurance forecast bands ([spaces §3.5](../okf/capabilities/spaces/spaces.md)) | a daily series against a Holt-Winters forecast ± 4σ̂ (MAD of week-on-week differences), with a regime rule and CUSUM | one hand-authored SQL view per series; a few aggregate series, not thousands of entities |
| Completeness KPI `VolumeBaseline` ([observability §3.9](../okf/capabilities/observability/observability.md)) | a Pipeline's daily volume against a rolling baseline (28 days, ≥ 7, tolerance 0.3) | per Pipeline, not per business entity |
| Risk Scores ([risk-scores.md](../okf/backend/control-plane/risk-scores.md)) | weighted indicators (Measures) per entity, explainable, reproducible | **absolute** thresholds the author picks; nothing compares an entity with its own history or its peers |

The competitive matrix (`docs/stakeholders/COMPETITIVE_CAPABILITY_MATRIX.md`, row L) marks *explainable
anomaly detection* **P** for us and **Y** for most competitors. The fraud and assurance packs (`telco-fraud`,
`mobile-money`, `aml`) all lean on fixed thresholds today; this add-on is what lets them say "unusual *for this
subscriber*".

## 2. Goals and non-goals

**Goals**

1. **Per-entity behavioural baselines** over author-chosen features, over a rolling window, with optional
   weekday / hour-of-day seasonality.
2. **Robust scoring** (median / MAD, not mean / standard deviation) that one outlier cannot hide behind.
3. **Peer-group comparison**: an entity against the distribution of its cohort (same tariff plan, same
   merchant category, same region), so a brand-new entity with no history is still scored.
4. **Explainability is the product.** Every score carries its contributing features, baseline vs observed,
   the deviation, the direction and a one-line reason. A score is **reproducible** from its stored
   explanation, as a Risk Score is (`RiskScorer.recompute`).
5. **Reuse, not a parallel stack**: Measures (`MeasureCompiler`) for features, the Job + output store +
   Dataset pattern of Risk Scores, Alert Rules → Incidents → Cases for action, Entity Lists for watch and
   exclusion, D-P8 masking, the maker-checker hold, component history.
6. **Fail closed** on every cap and every unreadable input.

**Non-goals (v1)**

- No black-box ML (isolation forest, autoencoders, gradient boosting). Robust statistics only; an ML
  scorer is a later, separately decided slice (D-AD2) and must still emit the same explanation shape.
- No real-time / streaming scoring — the engine is batch; that is `REALTIME-DECISIONING-1`.
- No forecasting of future values — that is `PREDICTIVE-ANALYTICS-1`; the BA forecast bands stay as they are.
- No new alerting path: Alerts are raised only by Alert Rules.
- No Step Processor: scoring runs as a Job (as Risk Scores do). `transform.running` remains the tool for
  per-row window features *inside* a Pipeline; it can produce a feature column the model then reads.
- No automatic action (block, suspend). Watch-list feed only, like Risk Scores.

## 3. Use cases

| # | Domain | Entity | Features (Measures per bucket) | Baseline | Anomaly |
|---|---|---|---|---|---|
| U1 | telco usage | subscriber (MSISDN) | outgoing voice minutes, data MB, SMS count per day | self, 28 days, weekday seasonal | a spike far above the subscriber's own history (stolen SIM, IRSF onset) |
| U2 | telco SIM-box | SIM / IMSI | outgoing call count, distinct called numbers, share of on-net calls, incoming/outgoing ratio, distinct cells | **peer**: same tariff plan; and self | many short outgoing calls to many distinct numbers, almost no incoming, one cell — individually normal-looking, jointly extreme |
| U3 | telco recharge / dealer | dealer | recharge count, average value, share of round values per hour | self, hour-of-day seasonal | a burst of identical-value recharges at 03:00 |
| U4 | payments | merchant | transaction count, amount sum, average ticket, refund rate per hour | self, hour × weekday; peer: merchant category | velocity shift — count ×4 with ticket size halved (card testing) |
| U5 | payments / mobile money | agent | manual adjustment count, adjustment amount, reversals per day | **peer**: agents in the same region | an adjustment outlier against peers who do the same job |
| U6 | mobile money | wallet | cash-in count, distinct counterparties | self | a dormant wallet waking up with a fan-in pattern (feeds the `aml` pack) |
| U7 | assurance | partner / route | terminated minutes, ASR, ACD per day | self, weekday | ACD collapse on one route (false answer supervision) |

Each use case ships as a **content example** in the matching Space Template only after S5 (§16), behind the
add-on's `requires`.

## 4. The model

### 4.1 Features

A **feature** is a Measure (`count` or `agg(field)` with the Measure grammar's `filters`) over one declared
Dataset, grouped by that Dataset's entity-key column **and** by a time bucket (`hour` or `day`) of a declared
timestamp column. Compiled by `MeasureCompiler`, exactly as a Risk Score factor. Ratios are declared as
`numerator` / `denominator` feature ids (`ratio` feature), computed after aggregation, with a zero
denominator yielding *missing*, never infinity. There is no raw SQL key.

### 4.2 Baseline per entity, per feature

For each entity *e*, feature *f*, the scorer collects the bucket values in the **baseline window**
(`window`, e.g. 28 days) **excluding the scored period** (so an anomaly cannot be in its own baseline).

- **Seasonality** (`seasonality: none | weekday | hour | weekday_hour`): the baseline for a bucket is drawn
  only from buckets of the same weekday, same hour, or both. `weekday_hour` over 28 days gives 4 points per
  cell — so it needs a longer window or falls back (below).
- **Robust centre and spread**: `median` and `MAD × 1.4826` (consistent with σ for a normal distribution).
- **Robust z**: `z = (observed − median) / max(scaledMAD, floor)`. The `floor` (per feature, default
  `max(1 unit, 5 % of |median|)`) stops a perfectly flat history (MAD = 0) from turning a +1 into z = ∞.
- **Minimum history** `minBaselinePoints` (default 7). Below it the self-baseline is `insufficient` and the
  scorer falls back to the **peer** baseline if one is declared, otherwise the feature is reported
  `insufficient` and contributes 0 — explicitly flagged, as a Risk Score flags `missing`.
- **Zero-inflation**: missing buckets count as 0 for `count`/`sum` features (an inactive day is real
  behaviour) and as absent for `avg`/`ratio` features. Declared per aggregation, not per author.

### 4.3 Peer-group comparison

`peers: {by: [tariff_plan], dataset?, minGroupSize: 30}` — the cohort column is read from the feature
Dataset or from a reference Dataset joined on the entity key. The peer baseline of feature *f* for cohort *c*
is the median / MAD of the **per-entity observed values** of all entities in *c* for the scored period. The
peer z is computed the same way. A cohort smaller than `minGroupSize` is `insufficient` (no peer score).

### 4.4 Combining into one score

Per feature: `dev_f = max(|z_self|, |z_peer|)` restricted to the declared `direction`
(`up` · `down` · `both`; a usage feature is usually `up`), capped at `zCap` (default 10) so one feature
cannot dominate. Combined:

- `raw = sqrt(Σ w_f · dev_f²) / sqrt(Σ w_f)` over non-insufficient features (a weighted RMS; it rewards
  several moderately unusual features together, which is exactly the SIM-box shape U2).
- `score = 100 · (1 − exp(−raw / scale))`, `scale` default 3, so z ≈ 3 maps to ≈ 63 and z ≈ 6 to ≈ 86.
  0–100 matches the Risk Score scale, so the same tile, Alert Rule shape and band vocabulary apply.
- `band`: `normal` · `elevated` · `high` from `elevatedThreshold` / `highThreshold` (§9).

All of this is pure (one class, `AnomalyScorer`, mirroring `RiskScorer`) and unit-tested without DuckDB.
Aggregation happens in DuckDB (one grouped query per feature, as the Risk Score evaluator does); the
median/MAD per entity is computed in SQL (`median`, `quantile_cont`) over the bucket table, so the JVM sees
one row per entity per feature, not raw rows.

## 5. Explainability — the explanation record

Every score row carries `features` (JSON list), one element per feature:

```
{ feature, label, bucket, observed, baseline: {kind: self|peer, median, mad, points, season},
  peerBaseline?: {cohort, median, mad, size}, zSelf, zPeer, deviation, direction, weight,
  contribution, insufficient, reason, evidence[] }
```

- `reason` is generated from the numbers, never free text: *"data MB 4 812 vs a usual 310 (Tuesdays, 4 weeks)
  — 14.5 MADs above"*; *"distinct called numbers 640 vs peers' 22 (tariff PREPAID_S, 3 211 SIMs)"*.
- `contribution = w_f · dev_f² / Σ w` (the share of `raw²`), so the features sort by how much each explains;
  the UI shows the top 3 and the rest on demand.
- `evidence[]`: up to 3 origin rows per entity for the top feature, masked at write time exactly as Risk
  Score evidence is (`EvidenceMasker`).
- **Reproducible**: `AnomalyScorer.recompute(features, params)` re-derives `score` and `band` from the stored
  `observed` / `median` / `mad` / weights; the Job test and the HTTP test assert equality, as for Risk Scores.
- `model_version` (12-hex SHA-256 of the stored model minus the envelope) on every row, so an explanation is
  tied to the exact model that produced it.

## 6. Config shape (TOON) and vocabulary

**Type vs Instance.** The *Anomaly Model* is the authored template (component kind `anomaly-model`, stored
at `registry/anomaly-models/<id>.toon` through `ComponentStore` — history, diff, restore, maker-checker come
for free). An *Anomaly Score* is the instance: one row per entity per run. Proposed GLOSSARY entries
(D-AD1): **Anomaly Model**, **Anomaly Score**, **Feature** *(of an Anomaly Model)*, **Peer Group**,
**Baseline Window**. Words to keep apart: an Anomaly Score is not a **Risk Score** (absolute weighted
factors), not a `baseline` **Expectation** (batch data quality), and not the Link Analysis `suspicionScore`.

```
name: Subscriber usage anomalies
entityType: subscriber
bucket: day
window: 28
scoredPeriod: 1
seasonality: weekday
minBaselinePoints: 3
elevatedThreshold: 60
highThreshold: 80
maxEntities: 500000
dataScope: fraud
watchList:
  list: usage-anomalies
  ttlHours: 24
exclusionList: known-heavy-users
peers:
  by[1]: tariff_plan
  dataset: subscribers
  minGroupSize: 30
features[2]:
  - id: data_mb
    label: Data volume (MB)
    dataset: cdr_data
    key: msisdn
    time: event_time
    measure: sum(volume_mb)
    direction: up
    weight: 1
  - id: voice_out
    label: Outgoing voice minutes
    dataset: cdr_voice
    key: msisdn
    time: event_time
    measure: sum(duration_min)
    filters[1]:
      - field: direction
        op: eq
        value: MO
    direction: up
    weight: 1
```

Outputs are derived, never authored (an authored `scoresDataset` is a 422): `anomaly_scores_<id>` (history)
and `anomaly_scores_<id>_latest`. The `anomaly_scores_` prefix is **reserved** the same way `risk_scores_` is
(`requireNotReserved` run by every writer, case-insensitive, first path segment), with the same
ownership-marker rule (`.anomaly-score-output`) — the writer never deletes what it does not own.

**Save-time gates** (422), identical in order to Risk Scores: `fromMap` structure + unknown keys + compile
every feature; `requireStorable` (every named column in its Dataset's Schema, including `time` and peer
`by`; `time` must be a TIMESTAMP/DATE column; output names collide with nothing); then the maker-checker hold.
Every writer (component route, bundle, template apply) runs them.

## 7. Where it runs

- **Job Type `anomaly.score`** (one parameter `model`; optional `as_of` for backfill/replay, default now
  truncated to the bucket). Triggered by `cron` or `on_pipeline:` after the Pipeline that lands the feature
  Datasets — same as `risk.score`.
- Steps: resolve caps → one grouped DuckDB query per feature over `[as_of − window − scoredPeriod, as_of)` →
  per-entity median/MAD in SQL → peer medians in SQL → `AnomalyScorer` in the JVM → write history parquet +
  swap `_latest` (`*.tmp` + atomic move) → watch-list feed → Signal.
- **Signals**: `anomaly.score.produced` (INFO; payload: model, run, scored, elevated, high, insufficient).
  Per §8-C the constant lives in a module class `AnomalySignals` with an `AnomalySignalsTest` pin; it moves
  to core `SignalType` only if core must match it (it will, for the deferred Alert Rule seed — D-AD7).
- **Columns**: `model, entity_type, entity_key, period_start, score, band, raw, features (JSON),
  insufficient_count, model_version, run_id, scored_at`.
- **Read route** `GET /anomaly-scores/{model}/{entityKey}` (latest + the last N runs for the sparkline) and
  `POST /anomaly-scores/preview` (one entity, writes nothing) — same gates as the Risk Score routes:
  `canWorkIncidents`, existence-hiding 404, data scope, `RowScope`, bound entity key, D-P8 key masking. Each
  new route clears the four route gates (capability manifest literal, authgate coverage, OpenAPI fragment,
  import-capability scan).

## 8. Errors

A query failure rethrows with model, feature, Dataset and error class only — never the DuckDB message
(which quotes cells) — as `RiskScoreEvaluator` does. An unreadable feature Dataset fails the run; a run
never writes a partial `_latest`.

## 9. Thresholds, bands and Alert Rule integration

- Bands `normal < elevatedThreshold ≤ elevated < highThreshold ≤ high`, both in (0, 100].
- No new alerting path. To raise Incidents: declare `anomaly_scores_<id>_latest` as a Dataset and point a
  per-entity Alert Rule at it (`measure: max(score)`, `by: [model, entity_key]`, `gte highThreshold`,
  `CRITICAL`/`ERROR` opens an Incident). It heals when the entity returns to normal on a later run, which is
  why it watches `_latest`.
- **Templates** cannot seed that rule at apply (no Schema until first run), so they use the **deferred seed**
  already built for Risk Scores (`config/pending/alert-rules/<name>.toon` + `afterRiskScore`). D-AD7 decides
  whether to generalise the trigger key (`afterScore: {kind, model}`) or add `afterAnomalyScore`.
- Incident attributes carry `key.model`, `key.entity_key`; the Incident page links to the entity anomaly
  panel. Case Rules group them as usual. Link Analysis can open an Investigation seeded from the entity.
- **Entity Lists**: `watchList` feeds every `high` entity to a `watch` list (1..24 h TTL) through the
  existing `WatchListFeed` seam; `exclusionList` (an `exclusion` list) removes entities before scoring
  (known heavy users, test SIMs) — reported as `excluded` in the run log, never silently.
- **Risk Score composition**: an Anomaly Score's `_latest` Dataset can be a Risk Score factor
  (`measure: max(score)`), so a Risk Model can weigh "unusual for itself" beside absolute indicators. No new
  code; documented as the pattern.

## 10. Masking (D-P8 / classification)

- Entity key stored raw, **masked on read** to the Space `masked:<16 hex>` token unless the caller holds
  `canRevealLinkEntities` (`keyMasked` in the response) — one helper shared with Risk Scores.
- Evidence masked at **write time** for columns classified `MSISDN`, `IMSI`, `ACCOUNT`, `PII`.
- **Peer cohort values** (e.g. tariff plan) are not classified identifiers and are shown; a cohort column
  that *is* classified is refused at save (it would leak through `reason`).
- `reason` strings contain feature labels and numbers only, never key or evidence values.

## 11. Performance budget and caps (fail closed)

| Cap | Default | Ceiling | On exceed |
|---|---|---|---|
| entities scored (`maxEntities`, else `-Danomaly.score.maxEntities`) | 200 000 | 2 000 000 | the run **fails** naming the cap; never a partial run (spec asks cap + 1 rows) |
| features per model | — | 16 | 422 at save |
| baseline window | — | 90 days (`day`) · 14 days (`hour`) | 422 at save |
| bucket rows per feature read (entities × buckets) | 50 000 000 | — | run fails |
| evidence rows per feature | 20 000 | — | truncated, `evidenceTruncated` in the run log |
| DuckDB policy | 2 GB, 4 threads, 10 min | — | run fails with the generic message |
| preview | 1 entity, 10 s, 512 MB | — | 503/422 as the Risk Score preview |

**Budget target** (to be measured in S2, not assumed): 500 000 subscribers × 2 features × 28 daily buckets
in ≤ 5 min on the 12-core dev host. Measure first; if the SQL median per entity is the bottleneck, D-AD9's
incremental option is the lever.

## 12. Module and edition placement

- New optional module **`inspecto-anomaly`** (id `anomaly`, package `com.gamma.anomaly`,
  `offeringRole: optional`), depending on the engine and on `scoring` only if D-AD5 shares code by
  dependency. It provides the `anomaly-model` kind, the `anomaly.score` `JobTypeProvider`, the routes and
  `AnomalySignals`.
- Add-on `anomalyDetection` in `offerings/professional.toon` (`status: planned` until S2 ships, then
  `built`), so Professional, Enterprise and Preview; not Personal (D-AD4). `tools/check-offerings.mjs`,
  the generated EDITIONS block and the `.claude/launch.json` jar lists are updated from the Offering, never by
  hand.
- Function / industry packs that ship anomaly content `requires` the add-on.

## 13. UI

1. **Authoring pane** (Studio, beside Risk Scores): feature list with the Measure picker the Risk Score
   pane uses, window / seasonality / peers / thresholds, inline 422 messages, and a **preview** box: pick an
   entity → its score, the per-feature table and a small chart of its baseline window with the observed point.
2. **Entity anomaly panel** (on the Incident page, the Link Analysis entity drawer, and the Risk Score
   entity view): score + band status-badge, top features sorted by contribution, baseline vs observed with
   the `reason` line, a sparkline of the last N scores, masked key with the reveal affordance where permitted.
3. **Dashboard tile** (Widget): count of `high`/`elevated` entities in `_latest`, top-10 table by score,
   trend of high counts per run. Built on the BI Widget layer over the `_latest` / history Datasets, not a
   bespoke endpoint.
All follow the `angular-ui` rules (design-system components, no hard-coded colours, WCAG 2.2 AA + axe gate).

## 14. Testing

- **Pure scorer unit tests** (`AnomalyScorerTest`): median/MAD, the MAD floor, seasonality bucketing,
  insufficient history, peer fallback, direction, `zCap`, the score mapping, `recompute` equality.
- **Golden corpus**, generated deterministically in SQL (`range()` + Weyl / Box-Muller, as the BA pack does
  — no RNG state): 2 000 entities × 60 days with weekday seasonality and three tariff cohorts, and **planted**:
  - true anomalies: a 10× usage spike (U1), a SIM-box profile (U2), a merchant velocity shift (U4), an agent
    adjustment outlier vs peers (U5);
  - **look-alikes that must NOT score high**: a heavy user who is always heavy; a weekly Monday peak (caught
    only if seasonality works); a whole-cohort shift (a promotion day — peers move together); a new entity
    with 2 days of history (must be `insufficient`/peer-scored, not high); a flat-history entity +1 (the
    MAD floor).
  - The test asserts precision and recall on the planted set (expected: all planted found in the top N,
    zero look-alikes `high`) and pins the exact scores.
- **Mutation checks** (listed for the operator to run by hand where they touch a guard): remove
  seasonality → the Monday look-alike goes high; mean/σ instead of median/MAD → the planted spike's own
  baseline masks a second spike; drop the MAD floor → the flat entity goes to 100; include the scored period
  in the baseline → the spike's z falls below threshold. Each must turn the golden test red **for the
  expected value**.
- **Job + HTTP tests** over real DuckDB (`ControlApiAnomalyScoreTest` with an armed Subject): every gate,
  existence-hiding 404, masking, the entity cap failing the run, the reserved prefix refused by every writer,
  the ownership marker, the Alert Rule → Incident → heal flow (mirroring `RiskScoreAlertTest`).
- Template tests: a template carrying an anomaly model passes every `_templates`-scanning test.

## 15. Open risks

- **Seasonality vs window size**: `weekday_hour` over 28 days is 4 points a cell; the fallback chain
  (cell → weekday → none) must be visible in the explanation, or analysts will distrust it.
- **Peer cohort quality** decides U2/U5; a bad cohort column produces confident nonsense. The preview must
  show the cohort size and median.
- **Measure before costing** the incremental path (D-AD9): do not build state stores until S2 shows the
  full-recompute run is too slow.

## 16. Phased slices

| Slice | Content | Size |
|---|---|---|
| S0 | ✅ done 2026-10-10: D-AD1..D-AD12 signed, GLOSSARY entries, BACKLOG row points here | XS |
| S1 | Module skeleton + Offering entry (`planned`); `anomaly-model` kind, `fromMap`, `requireStorable`, reserved prefix on every writer; `AnomalyScorer` pure + unit tests | M |
| S2 | `anomaly.score` Job: self baselines, weekday/hour seasonality, outputs + marker + `_latest` swap, caps, `AnomalySignals`; golden corpus (self cases) + mutation checks; performance measured | L |
| S3 | Peer groups + fallback; golden peer cases (U2, U5, cohort-shift look-alike) | M |
| S4 | Read + preview routes (four route gates, D-P8), watch-list / exclusion-list feeds, Alert Rule flow test, deferred template seed per D-AD7; Offering → `built` | M |
| S5 | UI: authoring pane + preview, entity anomaly panel, dashboard tile; content examples in `telco-fraud`, `mobile-money`, `aml` templates | L |
| S6 | Distil into an OKF concept (`okf/backend/control-plane/anomaly-scores.md`, not yet written), move residuals to BACKLOG, archive this plan | S |
| later | ML scorer behind the same explanation shape (D-AD2); incremental baselines (D-AD9) | — |

### 16-A. As built — lane S1 (2026-10-10)

The operator re-cut the slices for parallel lanes: lane **S1** = this table's S1 plus the self-baseline half of S2;
lane **S2** (another worktree, in parallel) = the pure peer / seasonal statistics in `com.gamma.anomaly.baseline`.

- **D-AD5 move.** The output-marker writer, the `_latest` swap and the reserved-prefix test are
  `com.gamma.alert.ScoreOutputDirs` (engine); the entity-key mask-on-read is `com.gamma.mask.EntityKeyMasking`
  (engine); `EvidenceMasker` was already in the engine. `RiskScoreEvaluator`, `RiskScoreRoutes` and `RiskScoreOutputs`
  delegate to them with byte-identical messages; the whole `inspecto-scoring` suite re-ran green.
- **Module.** `features/inspecto-anomaly` (id `anomaly`, package `com.gamma.anomaly`, no dependency on scoring), in the
  four edition profiles; add-on `anomalyDetection` `status: planned` in `offerings/professional.toon`; staged by
  `package.ps1`, `tools/bundle-modules.mjs`, `tools/offering-classpath.mjs` and `.claude/launch.json`.
- **Kind.** `anomaly-model` at `registry/anomaly-models/<id>.toon` (`ComponentRegistry` + `ComponentStore.WRITABLE_TYPES`),
  `AnomalyModel.fromMap` fail closed, `AnomalyKindValidator` (Schema columns, `time` must be DATE/TIMESTAMP, output
  collisions, reserved `anomaly_scores_` prefix with the `_latest` Dataset exception, marker `.anomaly-score-output`).
  Refused as *not built yet*: `peers`, `watchList`, `exclusionList`, `bucket: hour`, `scoredPeriod` ≠ 1,
  `seasonality` other than `none` / `weekday`.
- **Seam.** The Job scores through `com.gamma.anomaly.baseline.BaselineStatistic` (`kind()`,
  `compute(Map<LocalDateTime, Double> history, LocalDateTime scored)`), obtained from `BaselineStatistics.forModel`;
  `SeasonalBaseline` (the S2 lane's class) implements it. S2 adds statistics there, not in the Job.
- **Job.** `anomaly.score` (`model`, optional `as_of` YYYY-MM-DD, UTC days) scores the day before `as_of` over a window
  that excludes it; one grouped statement per feature (MeasureCompiler, day grain); columns as §7.
  **Reading of D-AD8 + §14 (the new-entity look-alike):** an entity's history starts at its **first bucket in the
  window** (any feature); from there an empty count/sum day is 0. Densifying from the window start gave a two-day-old
  entity 26 zero days and scored it `high` — the golden corpus caught it.
  This reading is **CONFIRMED (operator, 2026-10-10)**.
- **Golden corpus + mutations** (`AnomalyGoldenCorpusTest`): planted `spike`, `masked` are exactly the `high` set;
  look-alikes `heavy`, `flat`, `newbie`, `drop` stay `normal`. Red for the expected value: drop the 1-unit floor
  (`flat` → elevated); mean for median (`masked` median 713, a background entity enters the top 2); scored day kept at
  BOTH sites (Job + `SeasonalBaseline`) → 29 points. Keeping it at the Job alone is an equivalent mutant (the statistic
  excludes the scored bucket itself). The Monday-peak and cohort-shift look-alikes belong to the S2 corpus.
- **Not built in lane S1** (to BACKLOG via the row): evidence rows, ratio features, hour buckets, routes, watch /
  exclusion lists, Alert Rule flow + D-AD7 seed, performance measurement, UI.

### 16.1 S2 as-built: baseline statistics (lane anomalys2, operator, 2026-10-10)

Built in parallel with S1, so only the **pure statistics** landed; the Job, kind, helpers, module registration and
BACKLOG are S1's. Package `com.gamma.anomaly.baseline` in `features/inspecto-anomaly` (a temporary, UNREGISTERED pom
carrying only JUnit; it yields to S1's pom at merge). No DuckDB, no Space access: inputs are in-memory maps.

- `RobustStats`: `median`, `scaledMad` (x 1.4826), `floor(median, unit) = max(unit, 5 % of |median|)`, `z`.
- `Seasonality` (`NONE | WEEKDAY | HOUR | WEEKDAY_HOUR`): `sameSlot`, `label`, `fallbackChain()` =
  `weekday_hour -> weekday -> none`, `hour -> none`, `weekday -> none`.
- `SeasonalBaseline(seasonality, minBaselinePoints)`.`compute(Map<LocalDateTime,Double> history, scored)`: excludes
  the scored bucket, walks the chain until a slot has `minBaselinePoints`; the result records `requested`, `basis`
  (the slot actually used) and `fellBack`, so the fallback is visible in the explanation (§15).
- `PeerBaseline(minGroupSize, populationFallback)`.`compute(observed, cohortOf, cohort)`: cohort below
  `minGroupSize` is `insufficient` (D-AD11 a); opt-in population fallback is flagged `fellBack`, basis `population`.
- `Baseline` record (`kind, median, scaledMad, points, basis, requested, fellBack, insufficient, reason`) with
  `z(observed, unit)` (NaN when insufficient).
- `BaselineScore.of(observed, self, peer, unit)`: `zSelf`, `zPeer`, `peersOnly` (D-AD12: no self history, peers
  exist), `insufficient` (neither).
- The two baselines do **not** implement an interface here: S1 owns `BaselineStatistic`, and adopts them at merge.
- Tests: `BaselineStatisticsTest` (11) — planted Monday spike normal under `weekday`, high under `none`; fallback
  chain; small-cohort refusal + opt-in fallback; peers-only flag. Mutants run and all red: seasonality off (2
  fail), median -> mean (5), MAD floor removed (1), small cohort allowed (2).
- Not done here (S2's Job half, after merge): zero-inflation of missing buckets (caller's choice), SQL-side
  median/MAD, performance measurement (D-AD9).

### 16.2 S3 as-built: peer groups + fallback (lane anom-peers, 2026-10-10)

- **Config.** `peers: {by[1..3], dataset?, key?, minGroupSize? (2.., default 30), fallback? (none | population)}`.
  `dataset` defaults to the first feature's Dataset and `key` to the first feature's key (the join column in the peers
  Dataset; added because §4.3's "joined on the entity key" names no column). `by` may not include `key`. The peers
  Dataset and its `key` + `by` columns join `datasetIds()` / `referencedColumns()`, so `requireStorable` checks them
  against the Schema on every writer with no validator change. `peers` left `LATER_KEYS`.
- **Cohort query.** ONE grouped statement over the peers Dataset: `max(concat_ws('|', by...))` per key. A key with
  several cohort values takes the greatest (deterministic); a key with none is in the population only; past
  `MAX_ENTITIES_CEILING` keys the run fails; a failure withholds the DuckDB message (§8).
- **Peer baseline** = `PeerBaseline` (S2's class, unchanged) over the scored-day observations of the scored entities,
  after D-AD8 zero-filling, cached per feature and cohort. A cohort below `minGroupSize` is `insufficient` (D-AD11 a);
  `fallback: population` is opt-in and flagged `fellBack`, basis `population` (D-AD11 b).
- **Scorer.** `dev = max(dev(zSelf), dev(zPeer))` (§4.4). A feature is insufficient only when it has no observation or
  neither baseline. Self insufficient but peers usable means the feature is scored on peers only and flagged
  `peersOnly` (D-AD12). The explanation gains `peerBaseline {cohort, basis, median, mad, size, fellBack, insufficient}`,
  `zPeer`, `peersOnly` and `cohortShift` (only when the model declares peers), plus `baseline.basis` / `fellBack`.
  `recompute` re-derives both z from the stored numbers and is asserted equal over both corpora.
- **The cohort shift — CONFIRMED (operator, 2026-10-10).** With §4.4's plain `max`, the §14
  whole-cohort-shift look-alike *cannot* stay normal, because every member's self z is high on the promotion day. So each
  member's self baseline is scaled by `k = peer median today / the cohort's usual daily median`. The usual is the
  model's own `BaselineStatistic` over the cohort's per-day medians, so seasonality applies. `k = 1` when the cohort is
  insufficient or its usual is not > 0. The shift is multiplicative, so the floor stays in feature units and a heavy
  member of a doubled cohort is expected at 2x its own usual. It shows in `reason` as `, xk cohort shift = expected`
  and is stored as `cohortShift`. A member that moves alone (`promo_spike`, 10x on a 2x day) is still `high`.
- **Golden peer corpus** (`AnomalyPeerCorpus`, `AnomalyPeerGoldenCorpusTest`, 7 tests; 345 subscribers, cohorts
  PRE / POST / PROMO / SOLO from a reference `subscribers` Dataset). `high` is exactly `adjuster` (U5: one feature 10x
  its peers, self normal), `promo_spike` and `simbox` (U2: self normal, both features moderately above peers,
  zPeer between 4 and zCap, sessions zPeer exactly 6). The PROMO cohort (doubled), `newpeer` (2 days, peers only) and
  `loner` (cohort of 1) stay `normal`. Mutants run, and each one turned a named assertion red for the expected value:
  cohort shift off (all 100 PROMO members `high`); peer z dropped from `dev` (`simbox` and `adjuster` drop out of
  `high`); a cohort of 1 allowed (`loner` gets a peer score).
- **Gotcha.** With peers declared, S1's *always-heavy* look-alike becomes U5, a persistent outlier against its
  cohort. That is correct for peers. A legitimately heavy entity then belongs on the `exclusionList` (S4), not in a
  weaker peer rule. S1's self-only corpus is unchanged and still green.
- **Deferred.** Refusing a classified cohort column at save (§10) is not built: it needs the Schema classification
  lookup that Risk Scores use, so it goes to S4 or S5. Partial NULLs in a multi-column `by` are skipped by
  `concat_ws`. SQL-side peer medians (the JVM computes them from the per-entity series already in memory) and their
  performance measurement come under D-AD9. A cohort shift per `by` sub-column is also not built.

### 16.3 S4 as-built: routes, Entity Lists, Alert Rule flow, deferred seed (lane anom-routes, 2026-10-10)

Built beside the S3 lane (peer groups in the scoring code), so the scoring internals are untouched: the preview and the
exclusion run AROUND `AnomalyScoreEvaluator.evaluate`, never inside it.

- **Routes** (`AnomalyScoreRoutes`, `RouteModule` feature id `anomaly`; manifest `provides.routes`, the module's
  `openapi.fragment.json` merged into `docs/api/openapi-v1.json`, `CapabilityManifest` rows, route-gating evidence
  regenerated). `GET /anomaly-scores/{model}/{entityKey}` reads the HISTORY store (newest first, at most 30 runs): the
  first row is the latest score with its `features` explanation, all rows are the `history` sparkline. Gates as Risk
  Scores: `canWorkIncidents`; unknown model / unparseable model / out-of-scope model (SEC-7d) / `RowScope` deny / no
  row are one indistinguishable 404; the key is a bound parameter. `POST /anomaly-scores/preview` takes
  `{model | content, entityKey, asOf?}`: unsaved content also needs `canAuthorWorkbench` and passes `fromMap` +
  `requireStorable` (422); every feature is narrowed by a bound `key = entityKey` filter (`AnomalyModel.forEntity`), one
  entity, a 512 MB / 2-thread / 10 s sandbox; nothing is written. Both mask the key on read (`EntityKeyMasking`, the
  helper Risk Scores use) unless `canRevealLinkEntities`. Not ImportCapabilityGuard-`ACTS`-listed: `/anomaly-scores` is
  under no writable kind's prefix (the kind's directory is `anomaly-models`).
- **Entity Lists** (`AnomalyLists`, parsed beside the model so the record is unchanged): `watchList {list, ttlHours
  1..24, default 24}` feeds every `high` entity after the write (`job:anomaly.score:<id>`, D-P5 expiry);
  `exclusionList <id>` drops live members (exact or range, expired entries are scored again) before the write, logged
  and signalled as `excluded`. Both are checked at save (live list of the right purpose, Entity Lists installed) and
  fail the run when Entity Lists are absent. The seam is the existing `WatchListFeed`: its `check` now takes the purpose
  (`WATCH` | `EXCLUSION`) and RETURNS the live-membership predicate — the one Identity-Fact-log read site stays
  `RiskWatchListFeed#check`, so no writer-inventory entry was needed. The module now depends on `inspecto-entity-list`
  (`requires.modules: entity-list`).
  **Exclusion before peers — decided for the operator at the S3+S4 merge (integ17, 2026-10-10).** The exclusion
  predicate (`AnomalyScoreJobType#exclusion`) is passed INTO `AnomalyScoreEvaluator.evaluate`, which drops excluded
  entities as the bucket rows are read — before the entity cap, the self baselines and the peer-cohort medians — so an
  excluded entity never shapes its cohort's baseline; `Run.excluded()` carries the count. Proven by
  `AnomalyPeerGoldenCorpusTest#anExcludedHeavyEntityDoesNotShapeItsCohortMedian` (excluding PRE's heavier half and
  `adjuster` shrinks both cohorts by exactly those members and lowers PRE's median). Residual: the per-feature SQL
  `LIMIT maxEntities` still reads excluded keys, so a source within the cap only by its excluded keys still fails.
- **Alert Rule flow** (`AnomalyScoreAlertTest`): the documented per-entity rule over `anomaly_scores_usage_latest`
  raises exactly the planted `spike` + `masked` Incidents (`key.model`, `key.entity_key`) and both heal when the next
  run's `_latest` scores an ordinary day — the production `DatasetMeasureProbe` + `AlertService`, no new path.
- **D-AD7 deferred seed — generalised as decided.** The trigger is `afterScore: {kind, model}`; `PendingAlertRules`
  holds a `KINDS` table (`risk-score` → `RiskScoreOutputs`, `anomaly-model` → the new engine
  `com.gamma.alert.AnomalyScoreOutputs`, which `AnomalyModel`'s constants now alias). `anomaly.score.produced` moved to
  core `SignalType.ANOMALY_SCORE_PRODUCED` (`AnomalySignals` aliases it) and `CollectorService` maps either Signal to its
  kind. BREAKING: the three shipped pending rules (`aml`, `mobile-money`, `payment-fraud`) were rewritten to the new
  form; `GET /alerts/rules/pending` serves `afterScore`; the AUDIT attributes are `scoreKind` + `model` (was
  `riskScore`); the SPA Pending Alert Rules table shows "Risk Score x" / "Anomaly Model x".
  ⚠ The writer keeps the name `PendingAlertRules#onRiskScoreProduced` (now `(…, kind, model, …)`): renaming it needs the
  operator to update `ConfigWriteFunnelTest.WRITERS` (the `PENDING_ALERT_RULES` reason text) and
  `DecisionRuleWritersTest.GUARDED_BY` (`PendingAlertRules#ensureLatestDataset` → `#onRiskScoreProduced`).
- **Offering:** `anomalyDetection` is `status: built`; the EDITIONS matrix is regenerated.
- **Not built here:** template CONTENT seeding an anomaly model + its pending rule (S5 ships the examples in
  `telco-fraud` / `mobile-money` / `aml`), UI (S5), evidence rows, hour buckets, performance measurement.

## 17. Decisions (D-AD1..D-AD12) — taken (operator, 2026-10-10)

Every recommendation below was approved as written; the *Decided* column is binding.

| ID | Question | Options | Decided |
|---|---|---|---|
| D-AD1 | Names | (a) **Anomaly Model** (Type) / **Anomaly Score** (Instance) / **Feature** / **Peer Group**; (b) *Behaviour Profile* / *Behaviour Score*; (c) fold into Risk Score as a factor type | **(a)** — parallels Risk Score, says what it is; add to GLOSSARY §4 neighbourhood **(operator, 2026-10-10)** |
| D-AD2 | Scoring method in v1 | (a) **robust z (median/MAD) + seasonality + peers, weighted RMS**; (b) also isolation forest; (c) pluggable SPI now | **(a)** — explainable by construction; keep the explanation shape fixed so (b) can come later behind it **(operator, 2026-10-10)** |
| D-AD3 | New kind or extend Risk Score | (a) **new `anomaly-model` kind + `anomaly.score` Job**; (b) a `kind: anomaly` factor inside `risk-score`; (c) both | **(a)**, and document composing via the `_latest` Dataset as a Risk factor — one concept, one word; the math, caps and outputs differ **(operator, 2026-10-10)** |
| D-AD4 | Placement | (a) **new optional module `inspecto-anomaly`, add-on `anomalyDetection`, Professional+**; (b) inside `inspecto-scoring`; (c) base | **(a)** — sold separately per D-MR9; (b) would ship it wherever scoring ships **(operator, 2026-10-10)** |
| D-AD5 | Code sharing with scoring | (a) **extract the shared pieces (`EvidenceMasker`, key masking helper, output-marker writer) to the engine**; (b) depend on `scoring`; (c) copy | **(a)** — no copy (mirrors drift), no forced coupling of two add-ons **(operator, 2026-10-10)** |
| D-AD6 | Score scale | (a) **0–100 via `1 − exp(−raw/3)` plus raw z kept**; (b) raw max-z only; (c) percentile within run | **(a)** — same tile/Alert Rule shape as Risk Scores; percentile (c) always flags someone even on a quiet day **(operator, 2026-10-10)** |
| D-AD7 | Deferred Alert Rule seed trigger | (a) **generalise to `afterScore: {kind, model}`** for both; (b) add `afterAnomalyScore`; (c) no template seeding | **(a)** — breaking changes are free; one mechanism; moves `anomaly.score.produced` to core `SignalType` **(operator, 2026-10-10)** |
| D-AD8 | Missing buckets | (a) **0 for count/sum, absent for avg/ratio (fixed by aggregation)**; (b) author-chosen per feature; (c) always absent | **(a)** — correct by default, nothing to get wrong **(operator, 2026-10-10)** |
| D-AD9 | Baseline computation | (a) **full recompute from the feature Datasets each run**; (b) incremental per-entity state store; (c) (a) now, (b) when measured slow | **(c)** — measure in S2 first **(operator, 2026-10-10)** |
| D-AD10 | Entity cap behaviour | (a) **configurable + fail (as Risk Scores, 2026-10-06)**; (b) score top-K by volume; (c) sample | **(a)** — partial scoring is a silent false negative **(operator, 2026-10-10)** |
| D-AD11 | Peer cohort without enough members | (a) **`insufficient`, no peer score**; (b) fall back to the whole population; (c) merge small cohorts | **(a)**, with (b) as an opt-in `peers.fallback: population` — never silently change the comparison **(operator, 2026-10-10)** |
| D-AD12 | Entities with no self history | (a) **peer-only score, flagged in the explanation**; (b) not scored until `minBaselinePoints`; (c) scored against population | **(a)** — new SIMs/merchants are where fraud starts; flagged so analysts see the basis **(operator, 2026-10-10)** |
