---
type: Feature
title: Catalog
description: The data catalog — Streams/References/Tables/KPIs grids, lineage traversal, the usage graph, and the Data Browser.
resource: inspecto-ui/src/app/modules/admin/catalog/catalog.routes.ts
tags: [feature, catalog, acquisition, graph]
timestamp: 2026-07-30T00:00:00Z
---

# Catalog

Route `/catalog` (Platform ▸ Catalog nav group). Tabs (`catalog.component.ts`): **Streams** (default) ·
**References** · **Tables** · **KPIs** · **Lineage** (tab id `graph` — AntV G6 traversal:
from-node/depth/direction/kind filters, read-only) · **Usage** (embeds the former Registry reuse graph) ·
**Shared with me / by me** (only when `exchangeEnabled()`). Grids are **standard**
[data-tables](../design-system/data-table.md); row-click opens the node-detail dialog (facts, raw
attributes, walkable neighbours, and a per-store lineage panel via `GET /lineage?store=` for
TABLE/DERIVED_TABLE/REFERENCE_DATASET nodes). Streams/References rows carry a lifecycle badge from
`attrs.active` (Draft/Live, `—` when absent) and ONE row action — the pencil to
`/catalog/onboard/<attrs.pipeline>` ("Resume onboarding" on drafts). ⚠ There is **no
rename/take-offline row action anywhere in the Catalog grid itself** — take-offline lives in the
onboarding publish pane (**Take offline**, `saveBlock({active:false})`); the remaining lifecycle gaps
(live saves hot-reload silently — mitigated by a Live-state warning banner; no per-date retention) are
recorded in `BACKLOG.md` §4 *Catalog lifecycle*, and the user-facing truths (config-delete never
deletes data; Space purge is the only data purge; schema changes are forward-only) are written up in
`USER_GUIDE.md` §4.3. `?onboard=stream|reference` raises the create dialog after rows load (inline
duplicate-name check); `?tab=` and `?from=` deep-link the Lineage traversal. Backed by `CatalogService`
(`/catalog/streams|references|tables/{id}|kpis|graph`).

**Discarding a draft now checks dependents first (2026-08-14).** `DELETE /config/pipeline/{name}`
(the onboarding discard path) used to gate only on `active`; something else still referencing the
origin — an enrichment trigger/by-name reference, an Expectation/Decision Rule target, a Dataset
`physicalRef`/`sourceName`, or (transitively) a Widget/Dashboard tile bound to that Dataset — dangled
silently, detected only later by the read-only `metadata_validate` sweep (and even that missed most of
the kinds, plus any `physicalRef` containing a slash). It now **409s with the dependent list unless
`?force=true`** (`ConfigRoutes.deleteConfig` → `PipelineDependents.scan`), mirroring
`ComponentRoutes.deleteComponent`'s existing pattern. A companion read, `GET
/config/pipeline/{name}/impact`, reports the same scan without deleting anything (the `/import/preview`
report-only shape); the onboarding shell's discard confirm calls it first and names what would break,
and confirming anyway resends the delete with `force`. `PipelineDependents`
(`inspecto/src/main/java/com/gamma/service/PipelineDependents.java`) is a deliberate **non**-extraction
of `PipelineRoutes.rewriteDependents` (that scanner reads-and-writes in one loop; this one is read-only)
— the two key sets must be kept in sync by hand if a new binding is ever added to either.

## Data Browser

The per-space raw table browser (`modules/admin/data-browser/`, nav item under Catalog): store/table
list (left, resizable via `InspectoSplitDirective`) + column schema, paginated/sorted rows in an
`<inspecto-data-table>`, and an ad-hoc read-only SQL console — over both the business Parquet stores and
(when DB-backed) the operational tables via the backend's `/db/catalog|table|query` routes. Ops-table
reads go through each store's own live connection (`BrowsableStore` — DuckDB files are single-writer);
SQL is `SqlGuard`-checked server-side. Offline via the `db-browser` mock handler.
