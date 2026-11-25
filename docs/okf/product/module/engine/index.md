---
type: ProductItem
title: Execution engine
item: module.engine
description: Documentation hub for module.engine — every document about it, by slot.
tags: [product-item, module]
---

# Execution engine (`module.engine`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.engine`](../../../../../product-map/module/engine.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.engine - board row detail](plan-board-detail.md) |
| spec | current | [Database / persistence layer — Three data classes and the operational store inventory](spec-db-layer-data-classes.md) · [Database / persistence layer — Schemas — consignment outputs onward](spec-db-layer-schemas-continued.md) · [Database / persistence layer — Schemas (operational, non-Parquet)](spec-db-layer-schemas.md) · [Database / persistence layer — File topology, Postgres and the raw tables](spec-db-layer-topology-postgres.md) · [Database / persistence layer](spec-db-layer.md) · [DuckDB Integration — Sealed connections, time zones and aggregate state](spec-duckdb-connections-state.md) · [DuckDB Integration — DuckDB 2.0.1.0, the pinned version](spec-duckdb-version-pin.md) · [DuckDB Integration](spec-duckdb.md) · [Engine - gotchas](spec-engine-gotchas.md) · [Engine seams and performance](spec-engine-seams.md) · [KPI History — the kpi.snapshot Job and its Dataset](spec-kpi-history.md) |
| architecture | n/a | documented in the ELT hub module/elt architecture-elt.md (Execution engine internals, sections E1-E7), merged 2026-11-18 |
| features | n/a | documented in the ELT hub module/elt features-elt.md (Execution engine), merged 2026-11-18 |
| feature-db | current | [Execution engine — feature database](feature-db.md) |
| user-guide | n/a | documented in the ELT hub module/elt user-guide-elt.md section 22 (Runs and Run Detail), merged 2026-11-18 |
| admin-guide | n/a | documented in the ELT hub module/elt admin-guide-elt.md, merged 2026-11-18 |
| api | n/a | the engine registers no HTTP routes; the Pipeline, Run and Job routes it serves are documented in module/processor api-routes and api-jobs-scheduler and module/elt api-elt.md |
| tests | current | [Execution engine — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.engine` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `LOOKUP-1` | P2 | feature | Lookup: exact and best-match key resolution over a Reference Dataset (longest prefix, range, as-of, fuzzy), a batch engine and a row-at-a… | — |
| `DATED-REFERENCE-RUNS-1` | P2 | feature | Each record joins the Reference version of its own day, and a Job's backlog drains itself: daily Reference versions, version_by on the jo… | — |
| `OUTPUT-NAME-OVERWRITE-1` | P3 | defect | A second file with the same file name silently replaces the first file's rows, on any Pipeline (filed 2026-11-20, BASIC-ELT-PROFILE-1 B3… | — |
| `REPROCESS-ARCHIVE-MEMBER-1` | P3 | defect | Reprocessing ONE Consignment of a zip restores the whole archive under the member's composite name and reads every entry again, so rows d… | — |
| `ARRIVAL-HEAL-AT-START-1` | P3 | defect | A crash during a replace leaves readers a mix of old and new files until the Dataset's next run, replace or compaction (filed 2026-11-20,… | — |
| `REFERENCE-LOAD-REPLACE-1` | P3 | chore | reference.load: replace is not on the run-scoped replace: Add a file's *Replace everything* on a Reference and the Reference's own key ar… | — |
| `ARRIVALS-STANDARD-STORE-1` | P3 | feature | Arrivals mode (the merge map, retention) exists only for an import Dataset; a Standard Pipeline's own store has none, and the run claim s… | — |
| `REPLACED-STATE-READERS-1` | P3 | defect | Four readers of the Consignment output registry ignore REPLACED (filed 2026-11-20, BASIC-ELT-PROFILE-1 B4 review). | — |
| — | P2 | feature | SPACE-LINK-CENSUS-1 the link census: the default flip and the plan scope not built | — |
| — | P3 | feature | SPACE-EVOLUTION-1 the release-gated rest of the Space evolution | operator |
| `LEGAL-HOLD-SCOPE-1` | P3 | feature | A legal hold does not yet protect a Pipeline's data partitions, and holds have no screen. | — |
| `BI-INSIGHTS-1` | P2 | feature | Advanced insights on business data in Widgets and Dashboards, authored and tuned as Insight Profiles. | — |
| `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` | P3 | defect | A multi-file Consignment re-planned after a failover can write a second, differently named output. | operator |
| — | P2 | feature | Postgres multi-user | operator |
| `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1` | P3 | chore | Drop the window_self_join workaround once a fixed DuckDB driver is pinned. | — |
| `DUCKDB-2-0-1-HAVANA-TZ-1` | P3 | chore | DuckDB 2.0.1's zone data keeps Cuba on UTC−4 from 2026-11-01 to about mid-March 2027; the JDK's tzdata 2026b does not. | — |
| `RUN-TRIGGER-ACTOR-SPOOF-1` | P3 | defect | ?actor= on POST /jobs/{name}/trigger and POST /jobs/runs/{id}/replay is free text written into the run ledger's trigger field (manual:<ac… | — |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | The shapes the object-store ingest lane still refuses (sinks:, produces: reference, the readers). | operator |
