---
type: Feature
title: "Case Management — features"
description: "A **Case** groups related Incidents for one investigation."
tags: [case-management, case-management, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — features

**Availability:** Offering: Standard and above — add-on `caseManagement` (status `built`, module `case-management`) in `offerings/standard.toon`.

## What it does

A **Case** groups related Incidents for one investigation. There is no Case-specific model: a Case is an
`OperationalObject` (`features/inspecto-ops/src/main/java/com/gamma/ops/OperationalObject.java`) of type
`CASE` (`ObjectType`), stored and changed by the ops object machinery (`ObjectStore`, `ObjectService`), with
its members joined by `CONTAINS` links (`LinkStore`).

The module `features/inspecto-case-management` (id `case-management`, feature id `cases`, requires module
`ops`) adds what is Case-specific, in package `com.gamma.ops.cases`:

- `CaseRule` — a rule that groups matching Incidents into a Case;
- `CaseOperations` — merge, split, rule evaluation, open-from-entities;
- `CaseRoutes` — the HTTP routes;
- `CaseRuleEvalJob` — Job Type `caserule.evaluate`, emitting `caserule.evaluate.completed` (`CaseSignals`);
- `CaseConfigExtension` — loads rule files at boot.

## Where it fits

An add-on on top of [incidents](../../addon/incidents/architecture-incidents.md): it needs the `ops` module, which supplies the objects, links,
workflow engine and SLA sweep. The chain is Alert → Incident → Case
([Incidents capability](../../addon/incidents/plan-incidents.md)).

More: [screens](user-guide-case-management.md) · [API](api-case-management.md) · [architecture](architecture-overview.md).
