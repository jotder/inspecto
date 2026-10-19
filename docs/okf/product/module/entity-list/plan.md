---
type: Capability
title: Entity Lists — plan of record
description: What Entity Lists are required to do, what was built, what is left and what was refused — distilled from the LA-17 entity-model design, ASSURE-ENTITY-LISTS-1 and the BACKLOG.
resource: features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java
tags: [entity-list, plan, requirements]
timestamp: 2026-10-19T00:00:00Z
---

# Entity Lists — plan of record

## Required

- One concept in one store (D-P10, `LA-17` D-M8): a Space-scoped set of typed Entity keys with a purpose
  `allow · block · watch · exclusion`, kept as facts in the per-Space hash-chained Identity Fact log —
  [design §4.2-4.3](../../../../archived-documents/plans-archive/link-analysis-entity-model-design.md).
- Writes need a non-blank `reason` and capability `canManageIncidents`, and are audited as `ENTITY_LIST_CHANGED`
  (counts, never keys). Members are masked at render per `maskingMode` (D-U6).
- Range, CIDR and expiring entries, a Parquet sidecar Dataset, a match route, a maker-checker hold and the Risk
  Score watch-list feed (`ASSURE-ENTITY-LISTS-1`, WS-12) — [spec](spec.md).

## Built

| Item | Shipped | Where |
|---|---|---|
| Store, five routes, fold, masking, `excludeBy` / `seedBy` (`LA-17`) | 2026-09-26 | [design §4.3.2, §4.4.2](../../../../archived-documents/plans-archive/link-analysis-entity-model-design.md) |
| Ranges, CIDR, expiry, sidecar, `/match`, four-eyes, watch-list feed (`ASSURE-ENTITY-LISTS-1`) | 2026-09-28 | [spec](spec.md); [BACKLOG](../../../../BACKLOG.md) change log |
| Own module `inspecto-entity-list`; routes renamed `/inv/entity-lists…` to `/entity-lists…` (SEP-08) | 2026-10-01 | [spec](spec.md) |
| `POST /entity-lists/{id}/register-dataset` (manual) and `approverCheck` on held changes | 2026-10-04 | [spec](spec.md) |
| SPA **Edit entries…** dialog for ranges and expiry | 2026-10-06 | [spec](spec.md) |
| Refresh disabled without `canManageIncidents` (`LA-UI-ENTITY-LISTS-REFRESH-GATE-1`) | 2026-10-13 | [BACKLOG](../../../../BACKLOG.md) change log |

`ASSURE-ENTITY-LISTS-RESIDUALS-1` closed 2026-10-18.

## Left

- Range / CIDR entries in `excludeBy` / `seedBy` — retired to [BACKLOG](../../../../BACKLOG.md) §6 *Link Analysis & Geo*
  (DEFER; trigger: a customer asks). It changes the sealed-log format and the replay hash.
- A head-hash cache for `EntityFactLog.read()` — recommended by the SP4 measurement, not built ([spec](spec.md), Scale facts).
- Changing an Entity Type's normaliser strands existing members (`LA17-NORMALISER-CHANGE-1`, design §4.3.2).

## Refused / decided against

- No second store and no new kind for assurance entries; ranges are canonical strings in a separate set.
- `create` is not held under four-eyes (an empty list matches nothing).
- A list write never writes Dataset config; registering the sidecar is an explicit act gated by
  `canAuthorWorkbench` only (operator, 2026-10-04).
- No fact-log retention: append-only (design §4.2).
