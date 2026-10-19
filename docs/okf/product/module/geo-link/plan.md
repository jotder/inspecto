---
type: Plan
title: Geo and link adapters — plan of record
description: Why the geo-link bridge exists, what shipped in it, and what stays open.
resource: la/inspecto-geo-link/
tags: [module, geo-link, link-analysis, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Geo and link adapters — plan of record

## Requirement

Keep Link Analysis separable from the platform: la-core and la-api own the model and routes against ports,
and one bridge module implements those ports over the engine (LA separation, option D). Design of record:
[la-separation-d1-design](../../../../archived-documents/plans-archive/la-separation-d1-design.md) and
[la-separation-execution-plan](../../../../archived-documents/plans-archive/la-separation-execution-plan.md).
Before that, the module was EDG-01 cell 3b (CP-09), the geo map adapters
([geo-map-analysis-plan](../../../../archived-documents/plans-archive/geo-map-analysis-plan.md)).

## Built

- Port implementations: Dataset, Case, Collector coverage, upload landing, outbound (LA separation D-1
  step 5b/6; LA-EDITIONS-1 phase 6).
- Measures over the Working Set and Alert Rules from an Investigation (LA-23,
  [link-analysis-backlog-plan](../../../../archived-documents/plans-archive/link-analysis-backlog-plan.md) §2.7);
  standing detection (`LA-LIVE-DETECTION-1`, closed 2026-10-12,
  [la-live-detection-design](../../../../archived-documents/plans-archive/la-live-detection-design.md)).
- Corrected-day warning `…/data-changes` (`LA-DATA-TRUST-1` S5, closed 2026-10-14,
  [la-data-trust-plan](../../../../archived-documents/plans-archive/la-data-trust-plan.md)).
- Embed grant expiry sweep (`LA-EMBED-EXPIRY-SWEEP-1`, closed 2026-10-12,
  [la-embed-view-design](../../../../archived-documents/plans-archive/la-embed-view-design.md)).
- Signed webhooks and case hand-off (LA-EDITIONS-1 phase 6,
  [inspecto-la-editions-plan](../../../../archived-documents/plans-archive/inspecto-la-editions-plan.md)).
- Moved into every Edition, Basic included (LA-EDITIONS-1 phase 3, 2026-10-17).
- `link-index` Platform Service bridge (LA-DAILY-INGEST-1 T5).

## Left

Open Link Analysis rows live in `docs/BACKLOG.md` under the `LA-*` ids (for example `LA-EDITIONS-RESIDUALS-1`,
`LA-INVESTIGATION-OPS-DEFERRED-1` and P2 `LA-DAILY-INGEST-1`); none is specific to this bridge beyond the ports they call.

## References

- [architecture](architecture.md) · [spec](spec.md) · [la-core](../la-core/index.md)
