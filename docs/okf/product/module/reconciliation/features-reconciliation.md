---
type: Feature
title: "Reconciliation — features"
description: "Reconciliation compares two or three Datasets at a **key-column grain** and reports the differences as **Breaks**."
tags: [reconciliation, reconciliation, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Reconciliation — features

**Availability:** Offering: Standard and above — add-on `reconciliation` (status `built`, module `reconciliation`) in `offerings/standard.toon`.

## What it does

Reconciliation compares two or three Datasets at a **key-column grain** and reports the differences as
**Breaks**. It lives in `features/inspecto-reconciliation` (package `com.gamma.recon`). The module descriptor
`features/inspecto-reconciliation/src/main/resources/META-INF/inspecto/module.toon` declares
`offeringRole: optional`, one config kind (`reconciliation`), one Job Type (`recon.run`) and ten routes.

Four Break types exist (`ReconBreaks.TYPES`): `missing_right` (anchor only), `missing_left` (compared side
only), `value_break` (matched key, a Measure outside tolerance) and `cardinality_break` (only when a
cardinality is asserted). The comparison itself is stateless (`ReconService`); the Break lifecycle is
server-side operational state (`ReconBreaks` + `ReconStateStore`).

## Where it fits

An add-on over the platform base. It reads Datasets through their trusted relation SQL (`DatasetRelation`),
computes in an ephemeral DuckDB sandbox (`SqlSandbox`), schedules through the Jobs layer
([jobs-scheduler](../processor/architecture-jobs-scheduler.md)) and opens Incidents through the `objects` Platform Service
([platform-services](../../layer/platform/architecture-platform-services.md)). Without `inspecto-ops` the `objects` grant is empty: the Job
stays signal-only and `POST /recon/promote` answers 503.

More: [screens](user-guide-reconciliation.md) · [API](api-reconciliation.md) · [architecture](architecture-overview.md).
