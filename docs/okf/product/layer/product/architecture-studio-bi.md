# Studio BI

> **Level 3 module page** (`SOLUTION-SUITE-DOCS-1`). Layer: Platform base.
> Offering: all editions (the BI screens carry no feature gate). The investigation studios mounted under
> `/studio` have their own page, [link-analysis](../../module/la-core/architecture.md): the Link
> Analysis graph studio ships in every edition, the Geo map and the Entity profile pages in Standard and
> above (`docs/EDITIONS.md`). Checked against the code on 2026-11-12.

## 1. What it is

Studio is where a builder turns a **Dataset** into something a person can read: a number, a table, a
chart, a board. Its parts, in the canonical vocabulary:

- **Dataset** — a named relation over a store (component kind `dataset`), owned by the data plane.
- **Measure** — an aggregation over a Dataset field (`{agg, field}`), or a named calculated Measure
  stored on the Dataset.
- **Visualization Type** — a template for a chart or view (`VizPlugin`); twenty ship.
- **Widget** — an instance: one Visualization Type bound to a Dataset with its controls set.
- **Dashboard** — tiles of Widgets with a quick-filter bar, drill-through and PNG export.
- **KPIs and Reports** — the business gallery, with scheduled delivery through the `report` Job Type.

The capability spec of record is [studio](plan-studio.md); Datasets and the guarded SQL stack
are in [data-plane](../platform/plan-data-plane.md).

## 2. Who uses it

- **Analysts and builders** define Datasets and Measures, build Widgets and Dashboards.
- **Business users** read Dashboards and the KPI and Reports page (the business lens home).
- **External viewers** open a shared Dashboard through a public embed link.

## 3. Where it sits

Studio reads committed stores from [storage](../../module/entity-store/architecture.md) through Datasets; it writes nothing to
business data. Widgets, Dashboards and Datasets are ordinary component kinds, so they get versions,
ETags and history from the component registry. Code homes:

- Server: `inspecto/src/main/java/com/gamma/control/BiRoutes.java`,
  `platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`,
  `inspecto/src/main/java/com/gamma/control/ShareRoutes.java`.
- SPA: `inspecto-ui/src/app/modules/admin/studio/` (shell `studio.routes.ts`) and the Visualization
  Type registry in `inspecto-ui/src/app/inspecto/viz/` (`viz-types.ts`, `viz-registry.ts`,
  `plugins/index.ts`, `query-spec.ts`, `dataset-result.service.ts`). The HTTP client is
  `inspecto-ui/src/app/inspecto/api/bi-query.service.ts`.

For the SPA as a whole (stack, boot, layering) see [architecture](architecture.md).

## 4. How it works

![Studio BI result path](../../assets/studio-bi.svg)

**Visualization Types.** A `VizPlugin` declares `meta`, `controls`, `buildQuery()`,
`transformProps()` and a `render` descriptor. `BUILTIN_VIZ_PLUGINS` registers, in order: `kpi`,
`table`, the Chart.js standards `bar`, `line`, `area`, `pie`, the breadth additions (`bubble`, `gauge`,
`scatter`, `funnel`, `kpi-trend`, `progress-list`, `treemap`, `waterfall`, `combo`, `heatmap`), and the
view-bound `geo-map`, `link-analysis`, `reconciliation` and `working-set`. `viz-render.component.ts`
dispatches on `render.kind` (`chartjs`, `aggrid`, `component`).

**Controls become structured Measures, never SQL text.** A control value
(`{field, agg, grain, expression}`) is turned into a `QueryMeasure`
(`{id, expression, label, agg?, field?, ratio?}`) by `query-spec.ts`. The Widget produces a `QuerySpec`
(`{datasetId, sourceName, groupBy, grains?, measures, window?, filters?, orderBy?, limit?, queryId?}`).
`biQueryBody` in `dataset-result.service.ts` maps it to the request body: each Measure travels as its
`{agg, field}` pair, a ratio as `{num, den}` in `ratios`, the filter tree as a flat AND list. A spec that
cannot cross faithfully (a named Measure's SQL expression, a real OR branch, no Measure and no group-by)
fails with a message rather than degrading.

**The result path.** `POST /bi/query` hands the body to `MeasureCompiler`, which builds one SELECT
from validated identifiers and bound, typed parameters; no caller SQL text and no filter value enters
the statement. It accepts:

- fifteen aggregations: `count`, `countDistinct`, `sum`, `avg`, `min`, `max`, `median`, `stddev`,
  `variance` and the percentiles `p05`, `p25`, `p75`, `p90`, `p95`, `p99`;
- three grains: `day`, `week`, `month`;
- an optional trailing `window` (`{field, asOf, days?}`), which also enables the as-of aggregate
  `recency`;
- `ratios` of two aggregations.

The text then passes `SqlGuard.check` (422 on failure), the Dataset resolves through
`DatasetRelation.relationSql`, and it runs in the same ephemeral DuckDB sandbox as
`POST /queries/{id}/run`. Two more steps apply before the rows are returned:

- **Bound Query.** When the body names a saved Query (`query`), the Measures are aggregated over that
  Query's result instead of the whole Dataset. The Query must be `type: sql` and read the same Dataset;
  the combined statement is guarded again.
- **Masking.** Classified entity keys are masked in the rows unless the caller may reveal them
  (`BiResultMasking`); the response then carries a `masking` block.

The answer is the shared Result Set:
`{resultSet: {columns, rowCount}, rows, statistics{rowCount, elapsedMs, truncated}, sql}`. The route
fails closed: 503 without a write root, 404 for an unknown or not-visible Dataset, 422 for a bad spec.
The public embed, the `report` Job and the Measure-alert probe compile through `MeasureCompiler` too.

**Dashboards.** A Dashboard config holds `tiles: [{widgetId, span}]`, an optional `filter`,
`exposedFields` for the quick-filter chips, a viewer header (`description`, `asOf`, `illustrative`)
and a viewer-only date range (`dateField`, `defaultRange`).

**Freshness.** The Dataset list shows a badge derived at read time from the newest `dataset.write`
Signal against the `maximumAge` of a freshness Alert Rule; nothing is persisted as "stale".

## 5. Configuration

| Component kind | Key fields |
|---|---|
| `dataset` | `sourceName` (never defaulted), `columns`, `calculated` (calculated columns), `measures` (named Measures) |
| `widget` | `vizType` (required), the Dataset binding and control values |
| `dashboard` | `tiles`, `filter`, `exposedFields`, `description`, `asOf`, `illustrative`, `dateField`, `defaultRange` |
| `*_job.toon` with `type: report` | scheduled export and delivery |

The `widget`, `dashboard` and Job specs are declared in
`platform/inspecto-config/src/main/java/com/gamma/config/spec/ConfigSpecs.java`. `dataset` has no entry
there; its relation is validated when it is read, by
`platform/inspecto-engine/src/main/java/com/gamma/query/DatasetRelation.java`.

## 6. Screens

- **Datasets** (`/catalog/datasets`; editor `studio/datasets/dataset-editor.component.ts`, Measures
  tab `dataset-measures.component.ts`). `/studio` and `/studio/datasets` redirect there.
- **Query Library** (`/studio/queries`), **Widgets** (`/studio/widgets`, sidebar label "Viz Library",
  `widget-host.component.ts`), **Dashboards** (`/studio/dashboards`, sidebar label "Dashboard Builder"),
  **Template Gallery** (`/studio/templates`).
- **KPI & Reports** (`/kpi-reports`), a top-level pane, not a Studio child.
- **Shared Dashboard viewer** (`/share/:token`), outside the app shell, with no sign-in.

## 7. API

| Route | Purpose |
|---|---|
| `POST /bi/query` | compile and run a `QuerySpec` |
| `GET /bi/templates`, `POST /bi/templates/{id}/apply` | curated templates, all-or-nothing apply |
| `/components/dataset`, `/components/widget`, `/components/dashboard` | CRUD, versions, restore |
| `GET /datasets/{id}/rows`, `/datasets/{id}/freshness`, `POST /datasets/{id}/materialize` | Dataset reads |
| `POST /queries/{id}/run` | run a saved Query |
| `POST /dashboards/{id}/share`, `GET /public/dashboards/{token}`, `POST …/query` | public embed |
| `GET /kpis/{id}/value` | one KPI value |

## 8. Interactions

- **Alerts:** Measure-shape Alert Rules and freshness Alert Rules ([alerts](../../addon/incidents/architecture-alerts.md)).
- **Jobs:** the `report` Job Type delivers exports on a schedule ([jobs-scheduler](../../module/processor/architecture.md)).
- **Analytics kernel:** time grains and statistics ([analytics-kernel](../../module/analytics/architecture.md)).
- **Exchange:** shared Datasets and Widgets across Spaces ([exchange](../../module/exchange/architecture.md)).
- **Identity and access:** public routes and HMAC share tokens ([identity-access](../../module/access/architecture.md)).

## 9. Limits and gotchas

- `GET /bi/datasets` was retired; Datasets are listed through the component registry only.
- A `QuerySpec` with a named Measure's free-text `expression`, or with a real OR branch in its filter,
  cannot cross to `/bi/query`; the Widget shows the refusal.
- A `429` from `/bi/query` is retried three times (after 1, 2 and 4 seconds), then shown as rate limited,
  not as "no data".
- `recency` is accepted only together with a `window`, which gives it its as-of date.
- `VizRender` also declares a `g6` kind; no built-in plugin uses it and `viz-render.component.ts` has no
  branch for it.
- Every row read is a page with `truncated`; a consumer that counts must say so.
- A blank Dashboard header value is not written, so clearing a field removes the key.
- Only three time grains (`day`, `week`, `month`) cross the wire.
