---
type: Feature
title: "Event model and log — plan"
description: "Requirement of record for the event model, event log and audit chain: what is required, built, left and refused."
tags: [event, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Event model and log — plan

The capability-level requirement of record for events, the signal ledger and the audit trail is the
`OPS` area ([`REQUIREMENTS.md`](../../../../REQUIREMENTS.md) §3.7, `OPS-1` … `OPS-6`), whose
authoritative file is the [observability plan](../observability/plan-observability.md). This page
records what falls to the `inspecto-event` module.

## Required

- **Every edition records events and the audit trail.** Recording is a compliance obligation, not a
  feature; only reading the feed back over HTTP is edition-gated (`EDG-01` cell 6, 2026-09-08, see
  [`EDITIONS.md`](../../../../EDITIONS.md) *Audit* and `CP-13`).
- **A tamper-evident audit chain** — `ASSURE-AUDIT-CHAIN-1` (shipped 2026-09-27) and its residuals
  `ASSURE-AUDIT-CHAIN-RESIDUALS-1` ([REQUIREMENTS](../../../../REQUIREMENTS.md)).
- **Durable events** — a configured durable backend must not silently lose the trail on restart
  (`EVENTS-DURABLE-1`, cited in `ServiceStores`): a failed open is reported as a degraded store.
- **Secrets never reach the log** (`SecretScrubber`) and audit rows are masked on read for a caller
  without the unmask capability (D-P8).

## Built

- Event model, `EventLog` (global plus one per Space), capture of SLF4J INFO+ records via
  `EventStoreAppender`, the in-memory ring, rolling Parquet with a write-ahead journal, and the shared
  `DbEventStore` — see [spec](spec-events-metrics.md) and [architecture](architecture-overview.md).
- The audit hash chain (`AuditChainLinker`), anchors, verification and prune records —
  `ASSURE-AUDIT-CHAIN-1`; residuals (5) and (7) accepted as stated limits, (8) D-P8 built.
- Saved views (`SavedViewStore`) and the `inspecto_events_total{level,type}` counter.
- Signal backbone: [spec-signal-backbone](spec-signal-backbone.md).

## Left

- Open items are tracked in [BACKLOG](../../../../BACKLOG.md); none is a Must on this module. OPS-5
  provenance conservation on live data is a verification protocol, not code (REQUIREMENTS risk R5).

## Refused or deferred

- **Per-record lineage / replay** (`OPS-6`) — per-batch ancestry is the accepted grain
  ([REQUIREMENTS](../../../../REQUIREMENTS.md)).
- Multi-writer audit chains: one chain writer per directory or database; a second writer is refused
  rather than merged.
