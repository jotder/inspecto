---
type: Feature
title: Studio
description: The Builder-lens BI authoring hub — Datasets, Query Library, Viz Library / Widget Builder, Dashboard Builder — with real persistence via the widened component store.
resource: inspecto-ui/src/app/modules/admin/studio/
tags: [feature, studio, bi, dataset, query, widget, dashboard]
timestamp: 2026-07-07T00:00:00Z
---

# Studio

The Builder surface for BI authoring under `/studio`. Vocabulary is Type→Instance throughout
([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Visualization Type** is the template; a **Widget** is the
configured instance bound to a Dataset's Result Set; a **Dashboard** is a layout of Widgets.

* **Panes** — **Datasets** (define Tables/Derived Tables/Views the BI layer binds to), the
  **Query Library** (`/studio/queries` — author SQL + `$`-Parameters, preview the Result Set offline),
  the **Viz Library** (searchable Widget gallery) with the **Widget Builder**, and the
  **Dashboard Builder** (quick-filter bar, drill-through drawer, time grain, PNG export). The
  investigation studios live alongside: [Geo Map Analysis](geo-map.md) and [Link Analysis](link-analysis.md).
* **Visualization Types** come from the `VizPlugin` registry (`src/app/inspecto/viz/`) — charts, tables,
  scatter, funnel, …; **Measures** (never "metrics" in the BI sense) drive aggregations in Explore.
* **Persistence is real** — datasets/widgets/dashboards/queries are writable component kinds since W3/W4
  (`/components` + ETag/If-Match; [backend registry](../../backend/components/component-registry.md));
  query execution runs on DuckDB via [`POST /queries/{id}/run`](../../backend/control-plane/queries.md).
* **Widgets are library citizens** — identity + tags, the browsable Viz Library gallery, a standalone
  `WidgetHost` render path, and one shared `DatasetResultService` result layer: live it runs
  `POST /bi/query` (DuckDB), offline the same specs run byte-identically on AlaSQL; unmappable specs
  (named-Measure SQL, OR filters) fail honestly. Sharing/RBAC stays gated on the security module.
* **The rows seam — `DatasetRowsService` (2026-08-14, split S2 slice B).** What a Dataset's `sourceName`
  resolves to is asked in ONE place (`src/app/inspecto/viz/dataset-rows.service.ts`): live it reads the
  real store over `GET /db/table`, or `POST /db/query` with the dataset's Query Core model compiled by
  `compileSql`; offline it serves the store's entry in `inspecto/mock/sample-sources.ts`, filtered by
  `evaluateRows`. `sql()` runs authored SQL the same way (server-guarded live, AlaSQL offline) and
  `columns()` answers the declared columns, else a 1-row probe. It is the layer UNDER
  `DatasetResultService`: that one runs a `QuerySpec`, this one supplies rows a screen reads directly.
  ⚠ **Every result is a PAGE** — it carries `truncated` and an `error` string, and a consumer that
  counts or lists must say so (the drill-through drawer and the Queries preview both do). Before this,
  every consumer did a synchronous `SAMPLE_SOURCES[name]` lookup, so a live deployment showed sample data
  or nothing.
* ⛔ **Three sample-row folds are CORRECT and must stay** — `EntityProjectionGraphSource`,
  Geo's point/route sources and `ReconExecService` each already pair a server call with a sample fold as
  its **offline arm**; routing those through the seam adds a second round-trip behind a path that already
  has one. They share one `sampleDatasetRows` (there were two divergent copies; the Reconciliation one
  dropped column metadata and so compared numbers and dates as strings).
* **A widget's time grain travels on the wire (2026-08-14).** `QuerySpec.grains` (group-by column →
  `day|week|month`) is the ONE source of truth: each plugin's `buildQuery` fills it from the channel
  controls, offline `bucketSpecRows` buckets exactly those columns, and live `biQueryBody` sends them as
  the `/bi/query` body's `grains` key for `MeasureCompiler` to compile to `DATE_TRUNC`
  ([queries](../../backend/control-plane/queries.md)). Before this the grain existed only as a
  client-side row rewrite over the x channel, so the server grouped by the un-truncated timestamp while
  the demo bucketed correctly. ⛔ **Do not re-add a client-side fold for this** — a fold cannot bucket
  rows the server already aggregated. ⚠ The server returns the bucket as **text** in the UI's own format
  (`YYYY-MM-DD` / `YYYY-MM`), aliased back to the raw column's name, so both paths label their categories
  identically. ⚠ Only *grouped* columns may carry a grain — the server 422s otherwise, and `biQueryBody`
  drops a stale one rather than letting it fail the whole widget.
* ⚠ **`DatasetResultService.run` takes rows as a thunk.** Its live branch maps the spec to `/bi/query`
  and never reads rows — a ten-tile dashboard would otherwise fetch and discard ten pages.
* **The store picker lists real stores (2026-08-14, split S2 slice A).** The Dataset editor's
  `sourceName` field offers catalogued stores, not a hardcoded sample table: `DatasetRowsService.stores()`
  reads `/db/catalog` — and offers its **business** groups only — an `ops:*` table needs a group id that a
  Dataset's `sourceName` cannot carry. Create mode lands on the first catalogued store; an unreadable
  catalog says so rather than rendering as "this space has no stores"; and a saved dataset's own source
  stays in the list even when the catalog no longer names it, because a `mat-select` whose value is
  absent from its options renders BLANK. ⛔ The filed `storeOptionLoader` was deliberately **not** built —
  the only field naming a store is this `mat-select`, and the expectation form's `target` is a
  pipeline/job, a different vocabulary. Build it when a schema-form field genuinely names a store.
* ⛔ **A Dataset's `sourceName` is never defaulted (2026-08-14).** `DatasetsService.fromContent`'s old
  `?? 'data'` fallback named a key that does not exist, so a dataset stored without a source read
  **empty everywhere**, indistinguishable from an empty store. It stays blank instead. The write that
  produced that shape was Catalog go-live's auto-registration ([catalog](catalog.md)), which now sets
  `sourceName` to the store it registers — the dataset kind's own validator has always said *"A source is
  required"*, but nothing ran it on that path.
* **Forms** follow ask-the-minimum + `uniqueNameValidator` on create
  ([forms & state](../conventions/forms-and-state.md)).

Design of record (archived):
[`report-builder-design.md`](../../../archived-documents/plans-archive/report-builder-design.md) ·
[`widget-library-spec.md`](../../../archived-documents/plans-archive/widget-library-spec.md) ·
[`studio-implementation-plan.md`](../../../archived-documents/plans-archive/studio-implementation-plan.md) ·
[`studio-bi-improvements-plan.md`](../../../archived-documents/plans-archive/studio-bi-improvements-plan.md).
