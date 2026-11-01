---
type: Architecture
title: Customer Segments — the Segmentation Model kind and the predict.segment Job (declared RFM)
description: As built for PREDICTIVE-SEGMENT-RFM-1 (2026-11-01) — the first slice of the Predictive Analytics add-on. The segmentation-model component kind (declared RFM bins and a label table), the predict.segment Job that assigns every entity a Customer Segment from windowed Measures, the output stores, the save gates, the determinism and recompute contract, the decisions taken (D-PS1…D-PS8, pending operator confirmation) and what is not built.
resource: docs/okf/product/module/anomaly/plan-predictive-analytics.md
tags: [predictive, segmentation, rfm, customer-segment, job, analytics]
timestamp: 2026-11-01T00:00:00Z
---

# Customer Segments (declared RFM)

*PREDICTIVE-SEGMENT-RFM-1, built 2026-11-01 — slice S2 of the Predictive Analytics design
([`plan-predictive-analytics.md`](../anomaly/plan-predictive-analytics.md), Option 2.1 A).* A **Segmentation Model** (the
Type) declares how entities are grouped; one `predict.segment` run writes one **Customer Segment** assignment (the
Instance) per entity. Words per [`GLOSSARY.md`](../../../../GLOSSARY.md) §4: never the bare word *Segment*, which is an
ingester record kind.

## The module

`features/inspecto-predictive` — artifactId `inspecto-predictive`, manifest id `predictive`, `offeringRole: optional`,
package `com.gamma.predictive`. Add-on `predictiveAnalytics` (`offerings/standard.toon`, `status: built`), so Standard,
Advanced and Preview; **not Basic** (OD-5). Thin jar, profile-gated like `inspecto-anomaly` (root `pom.xml` edition
profiles, `tools/bundle-modules.mjs`, `tools/offering-classpath.mjs`, `inspecto/package.ps1` with its
`META-INF/services` check). It provides one config kind (`segmentation-model`), one Job Type (`predict.segment`) and **no
routes** — the read and preview routes and the Studio pane are `PREDICTIVE-SURFACE-1`.

| Class | Role |
|---|---|
| `SegmentationModel` | the kind's record and fail-closed `fromMap` parse |
| `RfmSegmenter` | the pure maths: quantile edges, scores, label lookup, `recompute` |
| `SegmentEvaluator` | the windowed read (one bound statement), the output columns, the write |
| `SegmentJobType` | the `predict.segment` Job Type (`JobTypeProvider`) |
| `SegmentationKindValidator` | the save gates (`ComponentKindValidator`) |

The engine knows the kind only as a name: `ComponentRegistry.TYPE_BY_DIR` (`segmentation-models/`) and
`ComponentStore.WRITABLE_TYPES`. Being writable makes it **governable**, so a Space's approval policy
(`approval.toon`, `ASSURE-MAKER-CHECKER-1`) can hold every save for four-eyes review — that is how OD-7's maker-checker
applies; no code of this module is involved.

## The kind

Stored at `registry/segmentation-models/<id>.toon`. The id names the outputs, so it matches `[A-Za-z0-9][A-Za-z0-9_]*`
(at most 64 characters).

```
entityType: customer
dataset: orders
key: customer_id
time: order_date
window: 365
monetary: sum(amount)
bins: 5
segments[3]{label,recency,frequency,monetary}:
  Champions,4-5,4-5,4-5
  At risk,1-2,3-5,*
  New,5,1,*
otherLabel: Other
```

| Key | Meaning |
|---|---|
| `entityType`, `dataset`, `key`, `time` | required; `time` must be a DATE or TIMESTAMP column |
| `window` | trailing days, 1…3660; absent = all history up to `as_of` |
| `frequency` | `count` (default) or `countDistinct(field)` |
| `monetary` | `sum(field)` only |
| `bins` | quantile bins per dimension, 2…10, default 5 |
| `segments` | the label table, 1…64 rows, in order; each row `{label, recency, frequency, monetary}` with a score range `*`, `n` or `a-b` inside `1..bins` |
| `otherLabel` | the label of an entity no row matches; default `Other` |
| `maxEntities` | 1…2 000 000; default `-Dpredict.segment.maxEntities`, else 200 000 |
| `description`, `name`, `owner`, `shares` | free text and the component envelope |

Labels are 1…64 characters with no control characters and unique (including `otherLabel`). Unknown keys, in the model
or in a table row, are refused — never ignored.

## The run

`predict.segment` with params `model` (required, config only) and `as_of` (`YYYY-MM-DD`; trigger args override the
config, so a back-dated run is `POST /jobs/{name}/trigger {"params":{"as_of":"…"}}`).

1. **Read.** One statement through `MeasureCompiler` with a `window: {field: time, asOf, days: window}` (ANALYTICS-CORE-1
   C1, [windowed Measures](../analytics/spec.md#windowed-and-ratio-measures)): `recency(time)`, the frequency and the
   monetary Measure, grouped by `key`, ordered by key, every date and value bound. NULL keys are dropped. An entity with
   no row in `(as_of − window, as_of]` has **no assignment**. A monetary value that is the sum of only NULLs is 0.
2. **Bin.** Per dimension, the Analytics Kernel's quantile edges over the run's population
   (`Binning.quantileEdges`, `quantile_cont`) and index (`Binning.quantileIndex`). Score = bin + 1, so **1 is worst and
   `bins` is best on every dimension**: frequency and monetary are binned on their value, recency on **minus** its days.
   Ties always share a bin and a value equal to an edge goes to the higher bin (never `ntile`, which splits ties); with
   heavy ties a bin can stay empty.
3. **Label.** The first table row whose three ranges all contain the entity's scores; else `otherLabel`.
4. **Write** `customer_segments_<model>` (history, one Parquet file per run) and `customer_segments_<model>_latest` (this
   run only), through the score output contract (`ScoreOutputWriter`, `ScoreKind`): ownership marker
   `.segmentation-model-output`, `*.tmp` then atomic move, no retention.

Columns: the contract head `model, entity_type, entity_key`, then `as_of` (TIMESTAMP, the window's last day),
`segment`, `recency_score`, `frequency_score`, `monetary_score`, `recency_days`, `frequency`, `monetary`, `explanation`,
then the tail `model_version` (12-hex hash of the model minus its envelope), `run_id`, `scored_at`.

**Recompute.** `explanation` is `{recency|frequency|monetary: {value, score, edges}}` — the binned value (recency as
minus days) and the run's edges. `RfmSegmenter.recompute(model, explanation)` re-derives the three scores and the label
from a stored row alone; `SegmentJobTest` asserts it equals the stored row for every row, and a changed value
recomputes to something else.

**Fail closed.** An unknown model or Dataset fails the run. More entities than the cap fails the run before anything is
written (never a subset). An output directory that exists without this model's marker fails the run and is left
untouched. A query failure names the model, the Dataset and the error class only (the message may quote source values).

## Save gates

`SegmentationKindValidator`, run by every writer through the core's component save gate (`ComponentRoutes.validateKind`:
the authoring route, bundle import, BI template apply, the Space Template stager):

* structure — `SegmentationModel.fromMap`;
* in the Space — every referenced column (`key`, `time`, the Measure fields) is in the Dataset's Schema, `time` is a
  DATE or TIMESTAMP, an unreadable Schema or an unknown Dataset refuses; neither output name may already exist under the
  data root unless this model owns it, nor be the id of a Dataset over another store;
* the **reserved prefix** `customer_segments_` on every `dataset` and `sink` write: refused, except a Dataset whose
  id = `physicalRef` = `customer_segments_<model>` or `…_latest` of a saved model (the read Dataset a dashboard or an
  Alert Rule uses).

## Decisions (D-PS1…D-PS8 — assistant-decided under operator delegation, 2026-11-01, pending operator confirmation)

* **D-PS1** Output prefix `customer_segments_`, not the design's `segments_`: *Segment* alone is the ingester record kind,
  and a store name would bring the word back.
* **D-PS2** Bins are quantiles over the run's population only (count 2…10, default 5); authored fixed edges are not
  built. Quantiles are the design's Option A and need nothing the author cannot know.
* **D-PS3** Score 1…bins, higher is better on all three dimensions; recency binned on minus days.
* **D-PS4** The label table is ordered score ranges with first-match-wins and a default `otherLabel`, not a full lookup
  over all bins³ triples (125 cells at 5 bins).
* **D-PS5** Frequency `count` or `countDistinct(field)`; monetary `sum(field)` only (average order value is not RFM's
  *monetary* and is not built); a sum of only NULLs is 0.
* **D-PS6** `as_of` defaults to yesterday (UTC), the last complete day, and is inside the window.
* **D-PS7** The module is the design's `inspecto-predictive` (OD-2) with the add-on `predictiveAnalytics` built.
* **D-PS8** No Signal and no `afterScore` Alert Rule seed for segments: a segment is a label, not a threshold breach;
  churn (`PREDICTIVE-CHURN-DECLARED-1`) is where the design puts them.

## Not built

* Read / preview routes, the Studio pane, entity panel — `PREDICTIVE-SURFACE-1`.
* Key masking on read and evidence: there is no read route yet and no evidence rows are stored; masking lands with the
  routes (design §4.4).
* Filters on the read, authored bin edges, average-based monetary, k-means segments (`PREDICTIVE-KMEANS-1`), drift
  between runs (`PREDICTIVE-VALIDATE-DRIFT-1`).
* A compliance review of profiling individuals (OD-14) gates any customer-facing template, not this engine slice.

## Tests

`features/inspecto-predictive/src/test/java/com/gamma/predictive/`, synthetic data only (`RfmCorpus`: 7 customers, as of
2026-06-30, hand-computed edges and segments in its header).

| Class | What it pins |
|---|---|
| `SegmentationModelTest` | defaults, first-match labelling, every refusal names its field |
| `RfmSegmenterTest` | quintile edges of 1…10 by hand, recency direction, ties on an edge, the corpus figures, recompute and a tampered row |
| `SegmentJobTest` | a real `JobService` run: markers, history vs `_latest`, the column order, hand-computed segments, window edges (a day after `as_of`, a row before the window), all-NULL monetary, recompute of every stored row, unknown model, the cap (over fails, at passes), a foreign output directory |
| `SegmentationKindValidatorTest` | Schema columns, time type, unknown Dataset, output clash, the reserved prefix and its read-Dataset exception |
| `ControlApiSegmentationModelTest` | real HTTP: a malformed and an unstorable model are 422 with nothing stored, a good one saves, a Dataset under the prefix is 422 |
| `SegmentJobTypeManifestTest`, `SegmentJobTypeTckTest` | manifest parity and the JobTypeProvider TCK |

Falsified 2026-11-01: twelve mutants (recency direction, score offset, last-match labelling, no window days, no cap,
monetary any aggregate, no time-type check, no Space check, no reserved prefix, NULL monetary as 1, discrete quantiles,
an empty `ComponentKindValidator` service file) each turned at least one of these tests red.
