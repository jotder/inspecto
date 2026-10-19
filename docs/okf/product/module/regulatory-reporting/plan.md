---
type: Capability
title: Regulatory Reporting — requirement of record
description: What was required of Regulatory Reporting (four-eyes regulator-format reports from Cases and Incidents), what is built, what is left and what was refused.
resource: features/inspecto-regulatory-reporting/
tags: [regulatory-reporting, plan, requirement, four-eyes, sar]
timestamp: 2026-10-19T00:00:00Z
---

# Regulatory Reporting — requirement of record

## Required

A regulator-format document (a SAR / STR is the motivating case) assembled from one Case or Incident — its fields,
member Incidents and evidence — plus a typed narrative, approved by a different person and then submitted, written
once and atomically to a file-drop folder, every step a hash-chained AUDIT row (`REGULATORY-REPORTING-1`,
prerequisite of `PACK-AML-1`). Design and decisions D-RR1 … D-RR12:
[regulatory-reporting-addon-plan](../../../../archived-documents/plans-archive/regulatory-reporting-addon-plan.md)
(provenance only). Mechanism of record: [spec](spec.md).

## Built

- MVP shipped 2026-10-09: Report Templates (XML / CSV / JSON), draft → pending → approved → submitted, four-eyes
  decide gates, the signed store, the file drop and the submission log ([spec](spec.md);
  [architecture](architecture-overview.md)).
- Template authoring, `PUT` / `DELETE /regulatory-reports/templates/{id}` (2026-10-11) ([spec](spec.md)).
- Demo rehearsal passed 2026-10-14 ([spec, *Demo rehearsal*](spec.md)).
- `REGULATORY-REPORTING-1` (P2) closed 2026-10-18 with its seven owed decisions taken: XSD validation at draft and
  a `.sha256` sidecar plus `GET /regulatory-reports/{id}/verify` built; masking declared by design
  ([BACKLOG](../../../../BACKLOG.md)).
- The AML pack ships the only real-shaped template, `aml-str`
  ([AML solution](../../solution-pack/aml/architecture.md)).

## Left

- P3 `REGULATORY-SAR-STR-TEMPLATES-1` (competitive gap T, 2026-10-18): regulator-specific SAR / STR templates, one
  per named regulator, authored on the shipped engine. Build when a customer names its regulator
  ([BACKLOG](../../../../BACKLOG.md)).
- BACKLOG §6 *Regulatory Reporting demand-gated rest* (was P3 `REGULATORY-REPORTING-RESIDUALS-1`, retired
  2026-10-18): regulator acknowledgement ingest (the reserved `rejected` status, receipts) and live delivery through
  a Connection; XMLDSig / CMS signing; nested repeating groups; a dedicated filing capability. Trigger: a named
  regulator's format or an operator ask ([BACKLOG](../../../../BACKLOG.md); [spec](spec.md)).

## Refused

- Amend, scheduled drafting and reproducible regenerate (declined 2026-10-18, `REGULATORY-REPORTING-1` decisions,
  [BACKLOG](../../../../BACKLOG.md)).
- Holding a report as a Pending Change: a report is not config, and a second hold would need two approvals for one
  filing ([spec, *Lifecycle and gates*](spec.md)).
- A partial filing: one out-of-scope member Incident refuses the whole draft (403), never silently thins it.
- Claiming a real regulator schema in the product: `sample-sar` is illustrative; real formats are pack content.
