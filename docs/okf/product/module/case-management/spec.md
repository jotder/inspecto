---
type: Concept
title: Case Management — specification
description: Implementation detail of the case-management module — classes, the Case workflow, merge and split, Case Rules, from-entities, storage and gates.
resource: features/inspecto-case-management/src/main/java/com/gamma/ops/cases/
tags: [case-management, spec, case-rule, merge, split, ops]
timestamp: 2026-10-19T00:00:00Z
---

# Case Management — specification

## Model

There is no Case-specific record: a Case is an `OperationalObject` of `ObjectType.CASE`, stored by
`ObjectStore` (`InMemoryObjectStore` / `DbObjectStore`, `-Dobjects.backend=db|postgres`) and changed by
`ObjectService` in module `ops`; members join by `CONTAINS` links (`LinkStore`). Writes carry an optimistic
`version` (`409 CONFLICT_STALE_VERSION` on a stale write; [DB layer](../engine/spec-db-layer.md) §3.1).

## Module

`features/inspecto-case-management`, package `com.gamma.ops.cases`
([module.toon](../../../../../features/inspecto-case-management/src/main/resources/META-INF/inspecto/module.toon):
id `case-management`, feature `cases`, contracts `JobTypeProvider` and `ObjectEngineExtension`, requires `ops`).

| Class | Role |
|---|---|
| `CaseRule` | rule record: `name`, `title`, `filter`, `threshold`, `windowMinutes`, `category`, `tags`, `groupBy` |
| `CaseOperations` | merge, split, rule evaluation, open-from-entities |
| `CaseRuleEvaluate` | one evaluation of a rule |
| `CaseRoutes` | the seven HTTP routes; `MAX_CASE_ENTITIES = 100` |
| `CaseRuleEvalJob` | Job Type `caserule.evaluate` (required parameter `rule`) |
| `CaseSignals` | signal `caserule.evaluate.completed` |
| `CaseConfigExtension` | loads `*_caserule.toon` at engine boot, fail-soft per file |

## Behaviour

Workflow, merge, split, Case Rule grouping (`attributes.raisedByRule`, `caseGroup` digest) and from-entities
compensation: [architecture § 4](architecture-overview.md). Rule file keys:
[architecture § 5](architecture-overview.md). Routes and capability gates: [API](api-case-management.md).
Limits: [architecture § 9](architecture-overview.md).

## Dry run

Under a dry run the `objects` Platform Service is substituted, so `caserule.evaluate` reports it evaluated
nothing (`PLATFORM-SERVICES-DRYRUN-GAP-1`; [Platform Services spec](../processor/spec-platform-services.md) §3).
