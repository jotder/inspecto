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

* **Panes** — the **Query Library** (`/studio/queries` — author SQL + `$`-Parameters, preview the Result Set offline),
  the **Viz Library** (searchable Widget gallery) with the **Widget Builder**, and the
  **Dashboard Builder** (quick-filter bar, drill-through drawer, time grain, PNG export). The
  investigation studios live alongside: [Geo Map Analysis](geo-map.md) and [Link Analysis](link-analysis.md).
  ⚠ **Datasets is NOT a Studio pane** (corrected 2026-09-08): `/studio/datasets` — and the Studio root —
  redirect to `/catalog/datasets`, because a Dataset became a Catalog asset in Phase B.2.
* **Visualization Types** come from the `VizPlugin` registry (`src/app/inspecto/viz/`) — charts, tables,
  scatter, funnel, …; **Measures** (never "metrics" in the BI sense) drive aggregations in Explore.
* **Persistence is real** — datasets/widgets/dashboards/queries are writable component kinds since W3/W4
  (`/components` + ETag/If-Match; [backend registry](../../backend/components/component-registry.md));
  a Widget's result runs on DuckDB via [`POST /bi/query`](../../backend/control-plane/queries.md) and the
  Query Library previews through `DatasetRowsService` (`/db/table`, `/db/query`). ⚠ *(This line named
  `POST /queries/{id}/run` until 2026-09-08; that route has **no client caller at all** — see the
  [`DAT`](../../capabilities/data-plane/data-plane.md) and [`Studio`](../../capabilities/studio/studio.md) specs.)*
* **Curated starter templates** — `GET /bi/templates` lists the seed pack (`BiTemplates`): `kpi-overview`,
  `quality-monitor` and, since 2026-09-02, the temporal `trend-monitor` (two `line` widgets over
  `event_date` at `month` grain + a KPI); `POST /bi/templates/{id}/apply` writes them as ordinary
  components bound to the caller's Dataset (409 on an id collision, `prefix` to disambiguate). Enrichment
  is the continuous BACKLOG §7 C7 item.
* **Widgets are library citizens** — identity + tags, the browsable Viz Library gallery, a standalone
  `WidgetHost` render path, and one shared `DatasetResultService` result layer: it runs
  `POST /bi/query` (DuckDB) and unmappable specs (named-Measure SQL, OR filters) fail honestly.
  ⚠ *(Until 2026-09-08 this said "offline the same specs run byte-identically on AlaSQL" — that arm went
  with the mock backend; AlaSQL survives only as the data-table Pro editor's own client-side SQL.)* Sharing/RBAC stays gated on the security module.
* **The rows seam — `DatasetRowsService` (2026-08-14, split S2 slice B).** What a Dataset's `sourceName`
  resolves to is asked in ONE place (`src/app/inspecto/viz/dataset-rows.service.ts`): it reads the
  real store over `GET /db/table`, or `POST /db/query` with the dataset's Query Core model compiled by
  `compileSql`. ⚠ *(Until 2026-09-08 this named an offline arm serving `inspecto/mock/sample-sources.ts`, a path that no longer exists,
  and an AlaSQL arm for `sql()`.)* `sql()` runs authored SQL server-guarded and
  `columns()` answers the declared columns, else a 1-row probe. It is the layer UNDER
  `DatasetResultService`: that one runs a `QuerySpec`, this one supplies rows a screen reads directly.
  ⚠ **Every result is a PAGE** — it carries `truncated` and an `error` string, and a consumer that
  counts or lists must say so (the drill-through drawer and the Queries preview both do). Before this,
  every consumer did a synchronous `SAMPLE_SOURCES[name]` lookup, so a live deployment showed sample data
  or nothing.
* ⛔ **The sample-row folds must stay — but not for the reason this bullet used to give.** Since the mock
  backend was deleted they are **not** offline arms: a backend failure surfaces. They are retained under
  decision `MOCK-DEAD-COMPUTE-1` (2026-08-31) as the **reference folds** the live paths are asserted to
  agree with, and as the vehicle the example graph and geo case studies are pinned through — delete them
  and the guards go with them (corrected 2026-09-08). They share one `sampleDatasetRows` (there were two divergent copies; the Reconciliation one
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
