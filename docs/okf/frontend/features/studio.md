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
  Offline, the same surface runs against the mock store ([mock backends](../conventions/mock-backends.md)).
* **Widgets are library citizens** — identity + tags, the browsable Viz Library gallery, a standalone
  `WidgetHost` render path, and one shared `DatasetResultService` result layer: live it runs
  `POST /bi/query` (DuckDB), offline the same specs run byte-identically on AlaSQL; unmappable specs
  (named-Measure SQL, OR filters) fail honestly. Sharing/RBAC stays gated on the security module.
* ⛔ **A Dataset's `sourceName` is never defaulted (2026-08-14).** Every Studio consumer resolves rows
  as `SAMPLE_SOURCES[ds.sourceName] ?? []`, so `DatasetsService.fromContent`'s old `?? 'data'` fallback
  named a key that does not exist and a dataset stored without a source read **empty everywhere**,
  indistinguishable from an empty store. It stays blank instead. The write that produced that shape was
  Catalog go-live's auto-registration ([catalog](catalog.md)), which now sets `sourceName` to the store
  it registers — the dataset kind's own validator has always said *"A source is required"*, but nothing
  ran it on that path. ⚠ The editor's `sourceName` picker therefore carries the **saved dataset's own source
  when it is not a sample key**, hinted "no preview rows here yet": a `mat-select` whose value is absent
  from its options renders BLANK, which an operator reads as "no source chosen" rather than as a real
  store with no offline preview. Real rows over a real store are `BACKLOG.md` §4 split S2 **slice B**
  (the sync→async rows seam) and are deliberately NOT what this affordance claims.
* **Forms** follow ask-the-minimum + `uniqueNameValidator` on create
  ([forms & state](../conventions/forms-and-state.md)).

Design of record (archived):
[`report-builder-design.md`](../../../archived-documents/plans-archive/report-builder-design.md) ·
[`widget-library-spec.md`](../../../archived-documents/plans-archive/widget-library-spec.md) ·
[`studio-implementation-plan.md`](../../../archived-documents/plans-archive/studio-implementation-plan.md) ·
[`studio-bi-improvements-plan.md`](../../../archived-documents/plans-archive/studio-bi-improvements-plan.md).
