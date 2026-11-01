---
title: Predictive read surface — as built
description: The Predictions read route, the entity prediction panel and the forecast chart (PREDICTIVE-SURFACE-1, first slice).
---

# Predictive read surface — as built (PREDICTIVE-SURFACE-1, 2026-11-01)

The first slice of the Predictive add-on's surface (design: `okf/product/module/anomaly/plan-predictive-analytics.md`
§3 *Routes* / *UI*, §4.4 masking, OD-8). It reads the stores the three Jobs write — `customer_segments_<model>`
([spec-segmentation](spec-segmentation.md)), `churn_scores_<model>` ([spec-churn](spec-churn.md)) and
`forecasts_<model>` ([spec-forecast](spec-forecast.md)) — and never writes. The Studio panes and the preview route are
`PREDICTIVE-STUDIO-PREVIEW-1`; until then models are authored through the generic Components pane.

## Route

`GET /api/v1/spaces/{space}/predictions/{kind}/{model}/{entityKey}` — `PredictionRoutes` (a `RouteModule`, feature
id `predictive`), registered only when `inspecto-predictive` is on the classpath; absent, the manifest's
`provides.routes` entry is stubbed 503 by the host.

| Gate | Result |
|---|---|
| no credential | 401 |
| caller lacks `canWorkIncidents` | 403 |
| unknown `kind`, unknown or unparseable model, a row the Advanced PDP denies (`RowScope`, resource type = the kind), a model that has not run, no row for the key | 404, indistinguishable |

`kind` is the component kind: `segmentation-model`, `churn-model` or `forecast-model`; for a forecast the key is the
series key. The key is a bound parameter.

Response (envelope `data`): `kind, model, entityType, entityKey, keyMasked, asOf, modelVersion, runId, scoredAt`, then
the kind's own columns in camelCase with `explanation` parsed (so a client recomputes the row):

- segment / churn: the latest row's columns (`segment`, `recencyScore`…; `score` — NULL when insufficient — `band`,
  `logit`, `historyDays`) plus `history[]` — the last 30 runs, newest first (`asOf, runId` and `segment` or
  `score, band`);
- forecast: `points[]` — every row of the latest run in step order (`status, step, bucket, forecast, lower, upper,
  backtestMae, backtestSmape, backtestRelMae, historyBuckets, explanation`); an insufficient series is one point
  with NULL values.

The key is echoed as the Space `masked:` token (`keyMasked: true`) unless the caller holds `canRevealLinkEntities`
(`EntityKeyMasking`, as Anomaly and Risk Scores).

Four gates: `module.toon` `provides.features` / `provides.routes` (parity: `PredictiveAbsentSurfaceParityTest`), the
`CapabilityManifest` entry + literal `withCapability("canWorkIncidents")`, the module's `openapi.fragment.json` merged
into `docs/api/openapi-v1.json`, and no write-funnel row (read-only).

## UI

- `PredictionsService` (`inspecto/api/predictions.service.ts`) — the typed read.
- `<inspecto-prediction-panel [entityKey]>` (`inspecto/components/prediction-panel.component.ts`) — lists the models of
  the three kinds and shows one block per model that has a prediction for the key: the Customer Segment with its R/F/M
  scores, the Churn Score with its band badge ("Not enough history" for NULL, never 0), or the forecast chart. Any
  failure (404, 503, absent kind) renders nothing. Hosted on the Incident page beside the Anomaly panel, keyed by the
  Alert Rule's `key.entity_key`.
- `<inspecto-forecast-chart [model] [points]>` (`inspecto/components/forecast-chart.component.ts`, data in
  `inspecto/predictive/forecast-series.ts`) — the forecast line with the stored interval as a filled band on
  `<inspecto-chart>`, a text alternative in words, and the series' backtest error underneath; an insufficient series
  shows the chart's empty state.

## Decisions (assistant-decided under operator delegation 2026-11-01, pending confirmation)

- **D-PV1** One route for the three kinds, `kind` = the component kind name (unambiguous, matches the registry), not
  three per-kind routes.
- **D-PV2** Read gate `canWorkIncidents`, the Anomaly / Risk Score gate (OD-8 recommendation: reuse existing gates).
- **D-PV3** Forecast series keys are masked like entity keys: one rule for every kind, fail closed, even when a series
  key is a product code rather than a person.
- **D-PV4** A forecast read returns the latest run only (no run history): the history store is still queryable from BI.
- **D-PV5** First slice = read route + panel + chart; the Studio panes and `POST /predictions/preview` are a separate
  row (`PREDICTIVE-STUDIO-PREVIEW-1`), since the generic Components pane already authors the three kinds.

## Tests

| Class / spec | What it pins |
|---|---|
| `ControlApiPredictionReadTest` | real HTTP, armed: 401 / 403 / 404 (not yet run, unknown kind, model, key, wrong kind for the model, an unsafe id) / 200; churn: latest run, history newest first, explanation recomputes, NULL insufficient score, masked vs revealed key with the raw key nowhere in the body; segment: label, R/F/M, parsed explanation; forecast: 10 steps of the latest run only in step order, every point recomputes, an insufficient series is one NULL point |
| `PredictiveAbsentSurfaceParityTest` | `module.toon` routes = what `PredictionRoutes` registers |
| `forecast-series.spec.ts` | band data, NULL-not-zero for insufficient, text alternative, legend filter |
| `prediction-panel.component.spec.ts` | one block per model with a prediction, insufficient churn text, nothing when the module is absent, axe |
