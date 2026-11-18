---
type: Feature
title: "Incidents — features"
description: "An **Incident** is an operational object of type INCIDENT (ObjectType, in platform/inspecto-workflow/src/main/java/com/gamma/workflow/ObjectType.java) that records something wrong and is worked to resolution."
tags: [incidents, incidents, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Incidents — features

**Availability:** Offering: add-on `incidents` in `offerings/standard.toon` — `status: built`, module `ops` (the code ships in the engine and the `ops` module).

## What it does

An **Incident** is an operational object of type `INCIDENT` (`ObjectType`, in
`platform/inspecto-workflow/src/main/java/com/gamma/workflow/ObjectType.java`) that records something wrong
and is worked to resolution. The record is `OperationalObject`
(`features/inspecto-ops/src/main/java/com/gamma/ops/OperationalObject.java`), persisted through `ObjectStore`
(`DbObjectStore` / `InMemoryObjectStore`) and changed only through `ObjectService`. States are strings held
by a `Workflow`, not an enum. The capability spec is [Incidents](plan-incidents.md); the chain is
Alert → Incident → Case.

## Where it fits

Split across two layers:

- the engine seam `IncidentAccess` / `ObjectAccess` (`platform/inspecto-engine/src/main/java/com/gamma/objects/`)
  lets core code open an Incident without depending on the ops module;
- the store, service, routes and governance live in `features/inspecto-ops`.

## Legal hold

An Incident with the `legalHold` attribute (set with `PATCH /objects/{id}`; there is no screen yet) keeps its
**evidence** until the hold is released (LEGAL-HOLD-SCOPE-1, 2026-11-17): its notes and attachments, the Alerts
escalated into it, its links and Case membership, the Case it is in and that Case's Investigations, and its audit
history. The retention tasks skip it and report it as held; a delete, merge, split, Investigation delete or Space
purge that would remove it answers 409 naming the hold and is audited (`legal_hold.refused`). The rule is
`ObjectService.legalHoldReason`, offered to core and Link Analysis as `ObjectAccess.legalHold`. Not covered: a
Pipeline's data partitions (`partition_prune`). Full list: [operations reference](../../layer/platform/admin-guide-operations-reference.md).

A bundle without `inspecto-ops` reports `features.ops=false`; the object routes answer 503 and openers fall
back to signal-only behaviour. See [platform-services](../../layer/platform/architecture-platform-services.md) (`objects` service).

More: [screens](user-guide-incidents.md) · [API](api-incidents.md) · [architecture](architecture-incidents.md).
