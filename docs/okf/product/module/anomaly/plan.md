---
type: Plan
title: "Anomaly Detection — plan"
description: "Requirement of record for the anomaly module: what was required, what is built, what is left and what was refused."
resource: features/inspecto-anomaly/src/main/resources/META-INF/inspecto/module.toon
tags: [anomaly, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Anomaly Detection — plan

Requirement of record, distilled from the archived design
[`anomaly-detection-design.md`](../../../../archived-documents/plans-archive/anomaly-detection-design.md)
(`ANOMALY-DETECTION-1`, decisions D-AD1..D-AD12) and the [BACKLOG](../../../../BACKLOG.md) rows named below.
As-built detail: [spec](spec-anomaly-scores.md).

## Required

A per-entity **Anomaly Score** (0–100) saying whether an entity behaves unlike itself or its peers, with a
per-Feature explanation that can be recomputed; authored as an **Anomaly Model** (component kind
`anomaly-model`), computed by a Job, read per entity and able to raise Incidents through existing Alert Rules.

## Built

- `ANOMALY-DETECTION-1` slices S1..S5 (shipped and archived 2026-10-10): the model kind, the `anomaly.score`
  Job Type, robust self baseline with weekday seasonality, Peer Groups with cohort shift, the two routes, the
  `afterScore` pending Alert Rule seed, the SPA pane and entity panel.
- `ANOMALY-DETECTION-RESIDUALS-1` (closed 2026-10-18): evidence rows, preview day series, NULL cohort values,
  unsaved-content preview, the telco-fraud dashboard tile.
- `ANOMALY-PEER-GROUPING-1` (closed 2026-10-11): grouped peer side, 200k entities in about 1–1.6 s (D-AD9).
- `ANOMALY-UI-FINDINGS-1` items (a)–(j) shipped 2026-10-14 to 2026-10-17 ((g) only partly).
- `ANOMALY-ML-SCORING-1`: `method: isolation_forest` on the model, built 2026-10-18 (see
  [analytics spec](../analytics/spec.md)).

## Left

- `ANOMALY-UI-FINDINGS-1`: a live check of the sign-in return path under a real token lapse.
- `ANOMALY-ML-SCORING-1`: nothing code-side. The form authors `method` / `forest` (D-AN29, closed 2026-10-20: a
  selector, and only for the isolation forest its `trees` / `sampleSize` / `seed`; a stored model round-trips both
  keys verbatim); the AML pack model `aml_account_transfers` ships on `method: isolation_forest`, proven by
  `PackFraudAmlAnomalyPropertyTest`. The telco-fraud and mobile-money models stay on the robust scorer: one steady
  Feature spike (telco) or a Peer Group (mobile-money) gives the forest no clear separation to prove.
- The isolation forest has no preview by design (W7): the preview box says so instead of scoring with the robust method.

## Refused or deferred

- Hour buckets: deferred until a pack or customer needs sub-day scoring (BACKLOG §6, 2026-10-18).
- A Risk Score factor type instead of a new kind: refused (D-AD3).
- The Link Analysis anomaly panel appearing only once a Risk Score model is named: LA's design call (BACKLOG §6).
