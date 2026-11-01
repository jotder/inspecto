---
type: Feature
title: "Predictive Analytics — features"
description: "Predictive Analytics assigns customers to declared segments from their recency, frequency and spend, scores how likely each is to churn, and forecasts a Measure per series with an interval and a stored backtest."
tags: [predictive, features, presales]
timestamp: 2026-11-01T00:00:00Z
---

# Predictive Analytics — features

**Product Map Item:** [`module.predictive`](../../../../../product-map/module/predictive.toon) · **Status:** segments, churn and forecasts built · **Availability:** add-on [predictiveAnalytics](../../addon/predictiveAnalytics/index.md), Standard and every higher Edition.

## What it is

Deterministic, explainable predictions computed in the platform with no machine-learning runtime and no new dependency.
The first capability is **Customer Segments**: a `segmentation-model` declares recency, frequency and monetary Measures
over a trailing window and a label table; the `predict.segment` Job assigns every entity one segment ("Champions",
"At risk", …) and stores the inputs, scores and bin edges so every assignment can be recomputed.
The second is **Churn Scores**: a `churn-model` declares windowed Inputs, a transform and an authored coefficient each;
the `predict.churn` Job writes a 0-100 score per entity with every Input's contribution, marks an entity with too
little history `insufficient` instead of scoring it, and can release a pending Alert Rule after its first run.
The third is **Forecasts**: a `forecast-model` declares a Measure per day or week bucket per series and a method
(seasonal-naive or Holt-Winters with authored smoothing); the `predict.forecast` Job writes each series' next 1-90
buckets with a prediction interval and the method's backtest error against seasonal-naive, and marks a series with
under two seasons of history `insufficient`.

## Who benefits

Marketing, revenue and planning teams that want stable, named customer groups for dashboards, campaigns and Alert Rules.

## Key capabilities

- `segmentation-model` config kind and `predict.segment` Job Type — [spec](spec-segmentation.md)
- Output Datasets `customer_segments_<model>` (history) and `_latest`, readable by BI Widgets and Alert Rules
- `churn-model` config kind and `predict.churn` Job Type — [spec](spec-churn.md)
- Output Datasets `churn_scores_<model>` (history) and `_latest`; a per-entity Alert Rule over `score`, or a pending
  one released by the model's first run (`afterScore: {kind: churn-model}`)
- `forecast-model` config kind and `predict.forecast` Job Type — [spec](spec-forecast.md)
- Output Datasets `forecasts_<model>` (history) and `_latest`: one row per series per future bucket with `lower` /
  `upper` and `backtest_mae`, `backtest_smape`, `backtest_rel_mae`
- Not yet: a churn backtest, a forecast degradation flag, the Studio pane, chart and read routes (see Open work)
