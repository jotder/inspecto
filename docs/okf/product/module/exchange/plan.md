---
type: Capability
title: Exchange — requirement of record
description: What was required of the Exchange (grant-mediated cross-Space sharing), what is built, what is left and what was refused.
resource: features/inspecto-exchange/
tags: [exchange, plan, requirement, multi-space, sharing]
timestamp: 2026-10-19T00:00:00Z
---

# Exchange — requirement of record

## Required

One Space's Dataset, Widget or saved view used by another Space — a department's Dataset read by another
department's Widgets, Queries and Alert Rules — without reprocessing or re-creating Pipelines. Sharing is
read-only, per item and opt-in: nothing is discoverable unless its owner offers it, nothing is usable without an
active Share Grant (fail closed). Original phasing (L0–S3 plus the UI track):
[storage-layout-and-sharing-plan](../../../../archived-documents/plans-archive/storage-layout-and-sharing-plan.md)
(provenance only). Mechanism of record: [spec](spec.md).

## Built

- Offer / request / approve ledger, snapshot and live delivery, version pin and drift, the Catalog sharing
  screens ([spec](spec.md); [architecture](architecture-overview.md)).
- Saved views on the kind axis (BACKLOG D9, 2026-07-26) and the `signal` kind
  ([cross-space-consequence-design](../../../../archived-documents/plans-archive/cross-space-consequence-design.md),
  2026-09-28) ([spec](spec.md)).
- `EXCHANGE-OWNING-SPACE-AUTHZ-1` (P1, closed 2026-09-28; gates shipped `67ce12f96`): every write is authorized
  in the Space that owns what it acts on ([BACKLOG](../../../../BACKLOG.md); [spec](spec.md)).
- `SHARE-SNAPSHOT-ON-APPROVE-1` (closed 2026-10-11): first snapshot published on approve, per-offer `refresh`
  cadence, `ExchangeSnapshotSweep`, `lastRefresh`.
- `EXCHANGE-SNAPSHOT-MULTIPOD-1` (closed 2026-10-11): collision-free `v<millis>-<hex>` versions, safe prune,
  `current.toon` never moves back.
- `BI-MASKING-EXCHANGE-SNAPSHOTS-1` (closed 2026-10-13): the snapshot meta carries the owner's per-column
  classification and the consumer's BI routes mask under their own entity types; a live grant or an older
  snapshot fails closed. This supersedes the spec's "no per-row/column masking in v1" line.

## Left

- BACKLOG §6 *BI masking leftovers* (retired 2026-10-18): a live-mode Exchange grant stays fully masked until
  its owner registry is read (fails closed) ([BACKLOG](../../../../BACKLOG.md)).
- P3 *Bundle / Exchange — "load as draft" residuals*: only the browser pass over the six editors remains
  ([BACKLOG](../../../../BACKLOG.md)).
- Known limits, not rows: Signals cross only between Spaces hosted in one Pod and are never queued; duplicate
  `EXCHANGE_REFRESHED` events from two pods remain possible ([architecture § 9](architecture-overview.md);
  [spec](spec.md)).

## Refused

From [spec, *What is deliberately out of scope*](spec.md): no Schema sharing; no sharing of Dashboards,
Pipelines, Jobs, Queries or Expectations (that transport is Metadata Bundles, copy semantics); no cross-Space
writes, ever; no wholesale or public scope. A distributed publish lease was not built — concurrent publishes are
made harmless instead (`EXCHANGE-SNAPSHOT-MULTIPOD-1`).
