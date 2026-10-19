---
type: Plan
title: "Scoring — plan"
description: "Requirement of record for Risk Scores (module scoring): what was required, built, left and refused."
resource: features/inspecto-scoring/src/main/resources/META-INF/inspecto/module.toon
tags: [scoring, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Scoring — plan

Requirement of record for **Risk Scores**, distilled from the [BACKLOG](../../../../BACKLOG.md) rows named below
and the archived [authoring pane spec](../../../../archived-documents/plans-archive/risk-score-authoring-pane-spec.md).
As-built detail: [spec](spec-risk-scores.md). The Decision Kernel documented in this hub is engine base, not this
module: see [its features](features-decision-kernel.md).

## Required

An explainable 0–100 score per entity from weighted factors (component kind `risk-score`), reproducible from its
stored factors, computed by a Job, read per entity, masked where evidence is classified, and able to open
Incidents through existing Alert Rules.

## Built

- `ASSURE-RISK-SCORE-1` (assurance wave 3.2, closed 2026-09-27): the model kind, the `risk.score` Job, the read
  route, write-time evidence masking, the `_latest` relation for Alert Rules.
- Residuals from `ASSURE-RISK-SCORE-RESIDUALS-1` (closed 2026-10-18): watch Entity List feed, opt-in history
  retention, entity-key masking on read (D-P8), configurable entity cap, the authoring pane S1..S3 with preview
  (D-RP1..D-RP10).
- `TEMPLATE-RISK-SCORE-ALERT-RULE-1` (closed 2026-10-06): the deferred `afterScore` Alert Rule seed for Space
  Templates.

## Left

Nothing open on a BACKLOG row. Not inspected: the capability approvers hold for held component changes
([residuals](spec-risk-scores.md#residuals)).

## Refused or deferred

- Free-form arithmetic in an indicator and a server `measure` kind: refused 2026-10-12 (BACKLOG §6); use a
  Dataset `calculated` column.
- Named score bands: deferred (D-RP3); the score stays a binary `highThreshold`.
- A reveal of masked evidence: none exists; masked evidence stays masked.
- Rewriting rows written before a column was classified: won't fix (history is never rewritten).
