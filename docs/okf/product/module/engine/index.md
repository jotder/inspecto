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
> this folder — add or rename a file (`<slot>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [Pipeline execution (PIP) — capability plan](plan.md) |
| spec | current | [Consignments — addressing and concurrency](spec-consignments.md) · [Database / persistence layer](spec-db-layer.md) · [DuckDB Integration](spec-duckdb.md) · [Ingest — the StreamingFileIngester SPI, Consignment coordination and branch-aware ingest](spec-ingest.md) · [Parsing — frontends, the parsing block and parser plugins](spec-parsing.md) · [Pipeline execution](spec-pipeline-execution.md) · [Pipeline model — node types, executors and the editable round-trip](spec-pipeline-model.md) · [Output & Sinks](spec-sinks.md) |
| architecture | current | [Execution engine — architecture](architecture.md) |
| features | current | [Execution engine — features](features.md) |
| feature-db | current | [Execution engine — feature database](feature-db.md) |
| user-guide | current | [Runs and Run Detail](user-guide.md) |
| admin-guide | current | [Execution engine — admin guide](admin-guide.md) |
| api | n/a | the engine registers no HTTP routes; the Pipeline, Run and Job routes it serves are documented in module/processor api-routes and api-jobs-scheduler and module/elt api-parsing-ingest |
| tests | current | [Execution engine — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.engine` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| — | P2 | feature | SPACE-LINK-CENSUS-1 the link census: the default flip and the plan scope not built | — |
| — | P3 | feature | SPACE-EVOLUTION-1 the release-gated rest of the Space evolution | operator |
| `LEGAL-HOLD-SCOPE-1` | P3 | feature | a legal hold does not reach a Pipeline's data partitions, and has no screen (narrowed 2026-11-17; filed 2026-11-15 from ARCH-MOSCOW-UNTRA… | — |
| `INGEST-MULTIFILE-BATCH-REPLAN-DUP-1` | P3 | defect | a multi-file Consignment re-planned after a failover can write a second, differently named output, even on a shared output tree. | operator |
| — | P2 | feature | Postgres multi-user | operator |
| `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1` | P3 | chore | report DuckDB 2.0.0's window_self_join hive-pruning defect upstream, then drop the workaround when it is fixed. | — |
| `SCALEOUT-OBJSTORE-DATABASE-DIR-1` | P2 | feature | ⏸ POSTPONED 2026-11-16 (operator, MoSCoW S7): waits until a multi-Pod deployment is scheduled, together with the postponed scale-out rows… | operator |
