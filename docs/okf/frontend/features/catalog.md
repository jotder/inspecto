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

**A batch links back to the store it wrote (2026-08-14).** The batch-detail dialog
(`run-detail/batch-detail.dialog.ts`) offers *"View &lt;store&gt; in the Catalog"*, landing on the
existing `?tab=graph&from=<nodeId>` deep link. The join is **not** derivable client-side: a batch row
carries `output_table` (the store name) but a catalog node's id is `event:<pipeline>/<schemaKey>`, and
the row's `schema_name` is `raw.name`, which is a different thing — so the id is resolved server-side by
`GET /catalog/resolve?table=` → `MetadataGraphService.nodeByTable`, a **unique** match on the node's
`table` attribute.

⛔ **Zero matches and several matches both 404, and the dialog then renders no link** — that is the
contract, not a limitation to be relaxed. Only `MetadataGraphBuilder`'s selector branch records a
`table` attr — and that mirrors runtime truth rather than lagging it: for the segments and
single-schema shapes `batch.table()` is **null** at ingest too (`CollectorProcessor.java:113-115`), so
those pipelines write straight to `dirs.database` with no table-named subdirectory
(`BatchIngestStrategy.databaseDir:291-296`) and their ledger rows carry a blank `output_table`. Such a
batch names no store, the dialog asks the catalog nothing, and no link renders — correctly, because no
distinct store node exists to point at. ⛔ Do not "complete" this by backfilling a synthetic table name
from `dirs.database` or the pipeline name (refuted 2026-08-14, `BACKLOG.md` §4). Widening the match to labels or prefixes would "fix" the
missing link by pointing at a store the graph cannot prove is the right one, and a wrong lineage edge is
worse than an absent one. ⚠ The route is `/catalog/resolve?table=`, deliberately **not** a path under
`/catalog/tables/` — that route's `(.+)` pattern is greedy and swallows any sub-path added beneath it.

## Data Browser

The per-space raw table browser (`modules/admin/data-browser/`, nav item under Catalog): store/table
list (left, resizable via `InspectoSplitDirective`) + column schema, paginated/sorted rows in an
`<inspecto-data-table>`, and an ad-hoc read-only SQL console — over both the business Parquet stores and
(when DB-backed) the operational tables via the backend's `/db/catalog|table|query` routes. Ops-table
reads go through each store's own live connection (`BrowsableStore` — DuckDB files are single-writer);
SQL is `SqlGuard`-checked server-side. Offline via the `db-browser` mock handler.
