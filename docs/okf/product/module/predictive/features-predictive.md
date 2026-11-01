---
type: Feature
title: "Predictive Analytics — features"
description: "Predictive Analytics assigns customers to declared segments from their recency, frequency and spend; churn and forecasting follow."
tags: [predictive, features, presales]
timestamp: 2026-11-01T00:00:00Z
---

# Predictive Analytics — features

**Product Map Item:** [`module.predictive`](../../../../../product-map/module/predictive.toon) · **Status:** first slice built · **Availability:** add-on [predictiveAnalytics](../../addon/predictiveAnalytics/index.md), Standard and every higher Edition.

## What it is

Deterministic, explainable predictions computed in the platform with no machine-learning runtime and no new dependency.
The first capability is **Customer Segments**: a `segmentation-model` declares recency, frequency and monetary Measures
over a trailing window and a label table; the `predict.segment` Job assigns every entity one segment ("Champions",
"At risk", …) and stores the inputs, scores and bin edges so every assignment can be recomputed.

## Who benefits

Marketing, revenue and planning teams that want stable, named customer groups for dashboards, campaigns and Alert Rules.

## Key capabilities

- `segmentation-model` config kind and `predict.segment` Job Type — [spec](spec-segmentation.md)
- Output Datasets `customer_segments_<model>` (history) and `_latest`, readable by BI Widgets and Alert Rules
- Not yet: churn scores, forecasts, the Studio pane and read routes (see Open work)
