---
type: Capability
title: Case Management — requirement of record
description: What was required of Case Management (Cases grouping Incidents, merge and split, Case Rules, Cases from entities), what is built, what is left and what was refused.
resource: features/inspecto-case-management/
tags: [case-management, plan, requirement, case-rule, merge, split]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — requirement of record

## Required

A **Case** groups related Incidents for one business investigation; the chain is Alert → Incident → Case
(product direction: Incident = operational, Case = business —
[case-management design](../../../../archived-documents/plans-archive/case-management-design.md) §2).

## Built

- Roadmap C1–C6 + I1 + I2, all shipped 2026-07-12: merge and split, dispositions, analytics, Case Rules and
  Findings ([design](../../../../archived-documents/plans-archive/case-management-design.md) §4).
- Scheduled rule evaluation: Job Type `caserule.evaluate` (`CaseRuleEvalJob`).
- `POST /cases/from-entities` from Link Analysis, capped at 100 entities (`CaseRoutes.MAX_CASE_ENTITIES`,
  settled 2026-10-18 under `LA-CONFIG-LIMITS-RESIDUALS-1`, [BACKLOG](../../../../BACKLOG.md)).
- Optimistic lock on objects (`409 CONFLICT_STALE_VERSION`) and a real-Postgres two-node soak on one Case
  (Postgres multi-user row, [BACKLOG](../../../../BACKLOG.md)). A Case is an `ObjectType.CASE` row behind
  `ObjectStore`; there is no separate Case store.
- The Case-specific surface is the optional module `inspecto-case-management` ([features](features-case-management.md)).

## Left

- P3 `D6-REAL-USER-SESSION-1`: the *Findings fields* think-aloud with a real Case-desk lead is unrun
  ([BACKLOG](../../../../BACKLOG.md)).
- Incident resolution gate: the UI hard-gate and the backend workflow guard are not started (design §4 item 5).
- A deployment-configurable disposition list (TOON override) is unscheduled (design §4 item 3).
- Case analytics as a Studio Dataset:
  [plan](../../../../archived-documents/plans-archive/case-analytics-dataset-plan.md) (draft 2026-07-24).

## Refused

- Merging Incidents, auto-merge suggestions, and moving comments and attachments on split (design §3, non-goals).
- A separate `MERGED` state (the source goes `CLOSED` with `mergedInto`), a hard open-member gate on
  resolve / close (soft warning only), and true document upload on a Case (references only) — design §4 items 1, 2, 4.
