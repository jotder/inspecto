---
type: Feature
title: "Predictive Analytics — features"
description: "Predictive Analytics assigns customers to declared segments from their recency, frequency and spend, and scores how likely each is to churn."
tags: [predictiveAnalytics, features, presales]
timestamp: 2026-11-01T00:00:00Z
---

# Predictive Analytics — features

**Product Map Item:** [`addon.predictiveAnalytics`](../../../../../product-map/addon/predictiveAnalytics.toon) · **Status:** segments and churn built · **Availability:** Billable add-on `INS-AO-PREDICTIVE-ANALYTICS`, offered from Standard and every higher Edition (`offerings/standard.toon`); it stages [predictive](../../module/predictive/index.md).

## What it is

Predictive Analytics computes explainable, recomputable predictions inside the platform. Its first capability is
Customer Segments: declared RFM bins and a label table, configured as `segmentation-model` and run as the
`predict.segment` job type. Its second is Churn Scores: a declared-coefficient logistic score with each input's
contribution, configured as `churn-model` and run as the `predict.churn` job type.

## Who benefits

Marketing and revenue teams that address customers by stable, named groups.

## Key capabilities

- `segmentation-model` config kind, `predict.segment` job type — [feature-db](feature-db.md)
- `churn-model` config kind, `predict.churn` job type — [feature-db](feature-db.md)
- Module detail — [Predictive Analytics features](../../module/predictive/features-predictive.md)

## Related hubs

[predictive](../../module/predictive/index.md) · [analytics](../../module/analytics/index.md) · [anomalyDetection](../../addon/anomalyDetection/index.md)
