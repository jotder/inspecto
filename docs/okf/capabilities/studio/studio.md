---
type: Capability
area: BI · INV
title: Studio (BI + INV) — capability spec
description: The requirement-of-record and as-built specification for Studio — the Query Library, Widgets over the VizPlugin registry, Dashboards, KPIs & Reports with scheduled delivery, curated templates, public embed, and the two investigation studios (Link Analysis, Geo Map Analysis) — one file, eight sections, machine-verified pointers.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.5 BI-1..BI-8 and §3.6 INV-1..INV-4 (this file corrects them, see §2)
---

# Studio (`BI` + `INV`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.5/§3.6 or `docs/EDITIONS.md`, **this file wins** and the disagreement is
> stated in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built
> (tracked + `UNTRACKED`), §6 what was refused or superseded, §7 the pointers into code and docs, §8 how
> it is verified. Everything in backticks under §3 and §7 was checked against the tree on 2026-09-08.
>
> ⛔ **`BI` and `INV` are ONE capability.** `docs/GLOSSARY.md` §14 settles it: Link Analysis and Geo Map
> Analysis live *inside* **Studio** (§1-A), so `INV` was never a sibling of `BI`, and both
> "Investigation" and "presentation" are undefined words. Both ID ranges keep their numbers; one spec
> owns them.
>
> **Canonical vocabulary** (`GLOSSARY.md` §7, binding). **Type → Instance** throughout: a
> **Visualization Type** is the template, a **Widget** is the configured instance bound to a Dataset's
> **Result Set**, a **Dashboard** is a layout of Widgets. A BI aggregation is a **Measure** — ⛔ never
> the BI sense of *metric* <!-- vocab-allow: names the banned BI synonym in order to ban it -->, which
> is reserved for the operational counters `OPS` owns. A **Report** is an *operational* deliverable and
> is deliberately not a Dashboard. ⛔ Never call a Widget a "chart" in a model name.

## 1. Purpose & scope

Studio is where a builder **turns a Dataset into something a person can read** — a number, a table, a
chart, a board, a graph, a map — and where an investigator asks *who connects to whom* and *where*.

**In scope**

* **The Studio surface** — the `/studio/*` shell: Query Library, Viz Library + Widget Builder, Dashboard
  Builder, Template Gallery, and the two investigation studios.
* **The Widget model and the `VizPlugin` registry** — Visualization Types, their control specs, the
  `QuerySpec` they build, and the render dispatch.
* **The result path** — `QuerySpec` → `POST /bi/query` → `MeasureCompiler` → the guarded sandbox → the
  Result Set contract, plus the rows seam underneath it.
* **Dashboards** — tiles, the quick-filter bar, the drill-through drawer, time grain on the wire, PNG
  export.
* **KPIs & Reports** — the operational gallery, KPI targets as business acceptance criteria, and
  scheduled export delivery through the `report` Job Type.
* **Curated templates** — `GET /bi/templates` and the all-or-nothing apply.
* **Public embed** — HMAC share tokens, the anonymous fenced query, the shell-less viewer.
* **Measure alerting** — `*_alert.toon` measure rules evaluated headlessly.
* **Link Analysis** — Entity Projection, the shared G6 host, the layout and algorithm toolboxes,
  pattern packs, saved Link-Analysis Views.
* **Geo Map Analysis** — the offline MapLibre basemap, GeoSource/GeoQuery, the intelligence
  toolbox, tools and layers, saved Geo Views.
* **The edition gate** for the two investigation studios (`inspecto-geo-link`, Standard+).

**Out of scope (owned elsewhere)**

* ⛔ **Cases (`INV-3`) are not Studio's.** A Case is one value of `ObjectType` on the same
  operational-objects machinery as Alerts and Incidents, and the chain Alert → Incident → Case is owned
  by `INC` ([`incidents/incidents.md`](../incidents/incidents.md)), which already specifies Case
  contents, merge and split, Findings, Disposition, Case Rules and RCA templates. §2 moves the row.
* **Datasets, Queries as persisted components, the Result Set contract itself and the operational
  stores** → `DAT` ([`data-plane/data-plane.md`](../data-plane/data-plane.md)). Studio *binds* to
  Datasets; it no longer owns the pane that defines them (§3.4).
* **The component registry, ETag/If-Match, versions and restore, Tags** → `MET`
  ([`metamodel/metamodel.md`](../metamodel/metamodel.md)). Widgets, Dashboards and both saved-view kinds
  are ordinary writable component kinds.
* **The Job framework** (triggers, scheduling, the parameter contract) → `PIP`/`OPS`. Studio owns only
  the `report` Job Type's *payload*.
* **Alert objects, notification channels and delivery** → `INC`. Studio owns only the measure-rule
  *evaluator*.
* **Lens homes, navigation and the shell** → `UI`; **the Exchange sharing axis** → `SEC`/`MET`;
  **auth for the public routes** → `SEC` ([`security/security.md`](../security/security.md)).
* **The Catalog graph view** → `MET`, even though it shares this area's G6 host.

## 2. Requirements of record

The board rows are `docs/REQUIREMENTS.md` §3.5 `BI-1`…`BI-8` and §3.6 `INV-1`…`INV-4`, plus
`EDITIONS.md` `CP-08` and `CP-09`. Below, each row is restated as it actually holds. **Where a cell says
CORRECTION the board was wrong on 2026-09-08 and has been amended in the same commit as this file.**

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **BI-1** | Studio authoring: **Widgets** (Visualization Type + config + Result-Set binding) and **Dashboards**, on real persistence through the widened component store | Must | ✅ SHIPPED (W3 widened `WRITABLE_TYPES`). **CORRECTION:** the row lists **Datasets** as a Studio authoring surface; they left. `/studio/datasets` and the Studio root both **redirect to `/catalog/datasets`** (Phase B.2 — a Dataset is a Catalog data asset). Studio binds to Datasets, `MET`/`DAT` own them | All |
| **BI-2** | **`VizPlugin`** registry of Visualization Types | Must | ✅ SHIPPED. **The count is 13**, and `BUILTIN_VIZ_PLUGINS` is the one place to read it: `kpi`, `table`, `bar`, `line`, `area`, `pie`, `bubble`, `gauge`, `scatter`, `funnel`, `geo-map`, `link-analysis`, `reconciliation`. The row's "charts, tables, scatter, funnel, …" states no count; the archived plan says "8 viz plugins" and is a 2026-07 snapshot | All |
| **BI-3** | KPI & Reports gallery; dashboard quick-filter bar, drill-through, time grain, PNG export; **Measures** in Explore | Should | ✅ SHIPPED. ⚠ A **Measure is a client-side `NamedMeasure`** (`{id, expression, label}`) authored per Dataset; there is **no server Measure entity**. The same "the client's model described as the server's" shape `MET-1`/`MET-3` carry | All |
| **BI-4** | Scheduled report/export delivery | Should | ✅ SHIPPED. **CORRECTION:** the cell says "a timestamped JSON/CSV artifact" — the code dispatches **four** formats (`json`, `csv`, `png`, `pdf`); `csv`/`png`/`pdf` each **require `scope: dataset`** and a rollup report renders as `json`. The SMTP caveat in the cell is **true and confirmed in code**: the mail carries the artifact *path*, not an attachment | **S/E as a product decision the code does not apply** — see the note below this table |
| **BI-5** | Alerting on **Measures** | Could | ✅ SHIPPED. The "v1 = whole-dataset measures, no per-rule filters" caveat is **enforced, not merely intended**: a measure rule's constructor requires `when`, `window` and the legacy metric field to be absent | All |
| **BI-6** | Public/embedded Dashboard sharing | Could | ✅ SHIPPED, backend and UI. Fail-closed as described: inert without `-Dbi.share.secret` (and it must be ≥16 chars), expiring, and **every** failure — bad signature, malformed, expired, unknown — returns the *same* `404` | 🔴 **the row says `S/E`, not `All`** — and the code is ungated (`ShareRoutes`/`ShareTokens` are core). See the note below this table |
| **BI-7** | Semantic / headless BI API | Could | ✅ SHIPPED. **CORRECTION (two):** (a) the cell's "Open follow-up: swapping the UI viz layer onto it" is **done** — `DatasetResultService`'s live path *is* `POST /bi/query`, and every Widget renders through it. (b) `GET /bi/datasets`, which the cell also claims, has **no client consumer**: Dataset listing goes through the generic component registry | **S/E as a product decision the code does not apply** — see the note below this table |
| **BI-8** | Widget/Dashboard template marketplace | Could | ✅ SHIPPED as a **curated seed pack**, not a marketplace. Exactly three ids ship (`kpi-overview`, `quality-monitor`, `trend-monitor`), the corpus is a Java constant, and apply is all-or-nothing with a `409` on any id collision. An external marketplace is out of scope by design (§6) | All |
| **INV-1** | **Link Analysis Studio**: Entity Projection over a Dataset, shared G6 host, 11 layouts, communities, pattern matching, saved **Link-Analysis Views** | Should | ✅ SHIPPED. **CORRECTIONS (three):** (a) the cell's "Open per design §7: `attrCols` mapping surface + the schema-relationship model" — **both shipped**, `attrCols` on both sides and the relationship inference on 2026-07-20; the concept page had already flagged that note stale. (b) "with the offline sample fold as fallback" is **gone**: since the mock backend was deleted a backend failure **surfaces**. (c) The counts are pinnable — 11 layouts, 6 pattern packs, two community methods | **S/E** — `inspecto-geo-link` (see `INV-2`) |
| **INV-2** | **Geo Map Analysis Studio**: offline MapLibre basemap, GeoSource/GeoQuery, heatmap, od-routes, time slider + playback, intelligence toolbox, measure/radius/polygon/notes tools, layer manager + GeoJSON overlays, saved **Geo Views** | Should | ✅ SHIPPED. **CORRECTIONS (two):** (a) "DuckDB-spatial backend = Phase 4" mislabels both halves — the **Phase 4 server-side projection shipped** (`POST /geo/projection`, `POST /geo/routes`), and the DuckDB `spatial` extension is a **deliberate refusal**, not a pending phase (no geometry op is needed and the hardened sandbox disables extension loading). (b) The client-side fallback is gone, as for `INV-1`. (c) 🔴 **The basemap is not PMTiles**: `inspecto-ui/src/assets/basemap/` ships four slimmed Natural Earth GeoJSON layers plus glyph fonts (~2.7 MB), and **zero `.pmtiles` files exist in the tree** — no code references the protocol either. A planet extract would have been ~100 MB, so it was refused at bundling time (D2, 2026-07-05); the word survived in this row, in `geo-map.md`, and in this file's own first draft | **S/E** — ✅ **GATED 2026-09-07** (EDG-01 cell 3b: `GeoRoutes` + `InvRoutes` moved into `inspecto-geo-link`; Personal gets a core `503` stub). ~~code is core and ungated, EDG-01~~ — stale from 2026-09-07 until 2026-09-08 |
| **INV-3** | **Cases** grouping Incidents; RCA templates; correlation ids end-to-end | Must | ✅ SHIPPED — **but this row does not belong to Studio.** ⛔ **MOVED to `INC`** (`incidents/incidents.md` owns the Alert → Incident → Case chain, Case Rules and RCA templates). A Case is one `ObjectType` value on the operational-objects tables, reached at `/cases` from the objects module, not from `/studio/*`. `GLOSSARY.md` §14's point that "Investigation" was never a coherent area is this row | **CORRECTION — S/E, not `All`.** The Case machinery is `inspecto-ops` (`EDITIONS` `CP-11`, gated 2026-09-08 by EDG-01 cell 7). Personal gets `AbsentObjectRoutes` and `features.ops = false` |
| **INV-4** | Cross-studio bridges | Could | ✅ SHIPPED — **exactly one bridge exists**: geo co-location → a graph dialog over the shared G6 host. A full hand-off into `/studio/link-analysis` was deliberately deferred, and there is no link→geo bridge | S/E with the studios |

🔴 **Three BI rows promise Standard+ for code that is ungated — the mirror image of `OPS-2`.** `BI-4`,
`BI-6` and `BI-7` all read `S/E`, but `BiRoutes`, `ShareRoutes`, `ShareTokens`, `TablePngRenderer` and
`PdfRenderer` all sit in the **core** modules and no `features.*` flag governs them: a Personal install can
mint a public share link, run a headless BI query and render a PDF. EDG-01's six cells did not include them,
and `EDITIONS` `CP-08` (✅ in all three editions) is what the build actually does. EDG-01's own closing rule
applies verbatim: *until it ships, the P cells are a stated product decision the code does not apply — say
so, do not describe them as absent.* Filed as `UNTRACKED` (§5.2): closing it is a product call.

**Edition-board corrections carried in this commit**

* `EDITIONS.md` `CP-09`'s gating is correct and was already recorded; the drift was one-way, in
  `REQUIREMENTS.md`.
* `BACKLOG.md` §5 named **`INV-2`** as a row that "says `All`". It says `S/E`. The same line made the
  same mistake about `OPS-2` and was struck for it on 2026-09-08; `INV-2`'s half is struck here.
* 🔴 **EDG-01 cell 7 missed `INV-3`.** The cell amended `EDITIONS` `JOB-01`, `JOB-03`, `SP-CTL-02`,
  `CP-11` and `OPS-01`, and `REQUIREMENTS` `INC-2` — and left `INV-3` promising the same Case machinery
  to every edition. That is the **fourth** row a gating cell has amended-by-name while missing a
  neighbour (after `INC-2`, `INC-4`, `JOB-01`/`JOB-03`).

## 3. Specification

### 3.1 The Studio surface

`/studio/*` is a lazily-loaded shell (`inspecto-ui/src/app/modules/admin/studio/studio.routes.ts`) whose
children are **Query Library** (`queries`), **Viz Library + Widget Builder** (`widgets`), **Dashboard
Builder** (`dashboards`), **Template Gallery** (`templates`), **Link Analysis** (`link-analysis`) and
**Geo Map Analysis** (`geo-map`). Two redirects, not panes: `/studio` itself and `/studio/datasets` both
send the caller to `/catalog/datasets`, because a Dataset became a Catalog asset in Phase B.2 and the
old bookmark had to keep working.

**KPI & Reports is a top-level pane**, `/kpi-reports`, not a Studio child, and it is the **business
lens's home**. Routes are not lens-filtered — every lens can reach every Studio pane; only the home
route differs (`UI` owns that).

### 3.2 The Widget model and the `VizPlugin` registry

A **`VizPlugin`** (`inspecto-ui/src/app/inspecto/viz/viz-types.ts`) is five things: `meta`
(`type`, `label`, `icon`, `fit`, optional `viewKind`), a `controls: ControlSpec[]` declaration, a
`buildQuery()` that produces a `QuerySpec`, a `transformProps()` that produces `VizProps`, and a
`render` descriptor. The registry (`inspecto-ui/src/app/inspecto/viz/viz-registry.ts`) is a plain map
with `registerViz`/`getViz`/`allViz`, a duplicate guard, and `isolateViz`/`snapshotViz`/`restoreViz` for
tests.

**Thirteen Visualization Types ship**, and `BUILTIN_VIZ_PLUGINS`
(`inspecto-ui/src/app/inspecto/viz/plugins/index.ts`) is the canonical list in registration order:
`kpi`, `table` (always-available), then the standards `bar`, `line`, `area`, `pie`, then the breadth
additions `bubble`, `gauge`, `scatter`, `funnel`, then the **view-bound** `geo-map`, `link-analysis`,
`reconciliation`. Registration is a **side-effect of importing that barrel** (`registerBuiltinViz()`
skips ids already present), which is why the shell-less share viewer imports it explicitly (§3.8).

`viz-render.component.ts` dispatches on `render.kind`: `chartjs` → the shared chart component, `aggrid`
→ the shared data table, `component` → `NgComponentOutlet` (the light `kpi` host synchronously; the
heavy `geo-map-view` and `link-analysis-view` hosts through the async loader registry in
`viz-components.ts`). A `g6` kind exists as a placeholder.

**Controls become validated aggregation pairs, never SQL text.** A `ChannelValue`
(`{field, agg, grain, expression}`) is turned into a structured `QueryMeasure` `{agg, field}` by
`buildMeasure`/`channelMeasure` in `inspecto-ui/src/app/inspecto/viz/query-spec.ts`. That is what makes
the public embed safe (§3.8) and what a spec carrying a free-text `expression` cannot cross (§5).

### 3.3 The result path

The client-side **`QuerySpec`** (`{datasetId, sourceName, groupBy, grains?, measures, filters?,
orderBy?, limit?}`) is the whole contract. `DatasetResultService`
(`inspecto-ui/src/app/inspecto/viz/dataset-result.service.ts`) maps it to the wire body and runs it
through `BiQueryService` (`inspecto-ui/src/app/inspecto/api/bi-query.service.ts`) against
**`POST /bi/query`**. A spec that cannot cross faithfully maps to `null` and **fails honestly** rather
than silently degrading. ⚠ `run` takes its rows as a thunk: the live branch never reads them, or a
ten-tile Dashboard would fetch and discard ten pages.

Server side, `BiRoutes` (`inspecto/src/main/java/com/gamma/control/BiRoutes.java`) registers
`GET /bi/datasets`, `POST /bi/query`, `GET /bi/templates` and the capability-gated
`POST /bi/templates/{id}/apply`. A query is compiled by **`MeasureCompiler`**
(`inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`) from
`Spec(dataset, measures, groupBy, grains, filters, orderBy, limit)` into **one** SELECT built only from
validated identifiers and typed literals — six aggregations (`count`, `countDistinct`, `sum`, `avg`,
`min`, `max`) and three grains (`day`, `week`, `month`). The compiled text then passes `SqlGuard.check`
as defence in depth (422 with findings if it fails), the Dataset resolves through
`DatasetRelation.relationSql`, and execution happens in **the same ephemeral DuckDB sandbox that
`POST /queries/{id}/run` uses**. The response is the shared Result Set shape:
`{resultSet: {columns[{name,type,role,cardinality}], rowCount}, rows, statistics{rowCount, elapsedMs,
truncated}, sql}`.

**Three other paths reuse that exact pipeline** — the public embed query, the `report` job's dataset
scope, and the measure-alert probe. That reuse is the reason the contract is worth one file.

**The rows seam underneath.** `DatasetRowsService`
(`inspecto-ui/src/app/inspecto/viz/dataset-rows.service.ts`) answers a different question — *what rows
does this Dataset's `sourceName` resolve to* — for screens that read rows directly (drill-through,
filter values, the Query Library preview). It reads the real store over `GET /db/table`, or
`POST /db/query` with a compiled Query Core model; `sql()` runs authored SQL server-guarded; `columns()`
answers declared columns or falls back to a one-row probe. ⚠ **Every result is a page** — it carries
`truncated` and an `error`, and a consumer that counts or lists must say so.

⚠ **What no longer exists.** Before the offline mock backend was deleted, each of these had a
client-side arm. It is gone: a backend failure now surfaces. The former fold functions
(`projectEntities`, `projectPoints`/`projectRoutes`) are **deliberately retained dead code** under
decision `MOCK-DEAD-COMPUTE-1` (2026-08-31) purely as the reference folds the live paths are asserted
to agree with, and as the vehicle the example graph and geo case studies are pinned through. **Delete
them and the guards go with them.** AlaSQL survives in the tree but only as the data table's Pro SQL
editor feature, which is a live client-side capability `DAT` documents — not a Studio result path.

### 3.4 Datasets, Measures and the Query Library

**Datasets are `MET`/`DAT`'s.** Studio lists them through the generic component registry
(`DatasetsService.list()` → `ComponentsService.list('dataset')`), *not* through `GET /bi/datasets`.
Two Dataset rules Studio depends on, both dated 2026-08-14: the editor's store picker offers **real
catalogued stores** from `/db/catalog`, business groups only, keeping a saved Dataset's own source in the
list even when the catalog stops naming it (a `mat-select` whose value is absent renders blank); and a
Dataset's `sourceName` is **never defaulted**, because the old fallback named a key that does not exist
and made a source-less Dataset read empty everywhere, indistinguishable from an empty store.

**A Measure is a client-side `NamedMeasure`** — `{id, expression, label}` — authored per Dataset in the
Dataset's Measures editor and previewed client-side. There is **no server-side Measure entity**: what
crosses the wire is always a validated `{agg, field}` pair, so a named-Measure expression is exactly the
kind of spec that cannot cross and must fail honestly.

The **Query Library** (`/studio/queries`) authors SQL with `$`-parameters and previews the Result Set.
⚠ It previews through `DatasetRowsService` (`/db/table`, `/db/query`) — **not** through
`POST /queries/{id}/run`, which has no client caller at all.

### 3.5 Dashboards

A `DashboardConfig` is `{tiles: [{widgetId, span}], filter?: ConditionGroup, exposedFields?}`. The
**quick-filter bar** renders chips over `exposedFields` and emits the same `{field, value}` toggle shape
a tile's drill-down emits. The **drill-through drawer** is a slide-over that reuses the shared data table
for the rows behind a tile. **PNG export** (`exportPngs()`) downloads every canvas under a dashboard
tile, so chart tiles export and table/KPI tiles export through their own surfaces.

**Time grain travels on the wire (2026-08-14).** `QuerySpec.grains` (group-by column →
`day|week|month`) is the one source of truth: each plugin's `buildQuery` fills it from the channel
controls, offline bucketing walks exactly those columns, and the live body sends them as `grains` for
`MeasureCompiler` to compile to `DATE_TRUNC`. ⛔ **Do not re-add a client-side fold** — a fold cannot
bucket rows the server already aggregated. ⚠ The server returns the bucket as **text** in the UI's own
format, aliased back to the raw column name, so both paths label categories identically; and only
*grouped* columns may carry a grain, so a stale one is dropped rather than allowed to 422 the whole
Widget.

### 3.6 KPIs, Reports and scheduled delivery

`/kpi-reports` is the **operational** gallery: **KPIs** (single-number Measures with a target or
threshold, rendered mini → standard → max) and **Reports** (run health, freshness, SLA). It reads
Dashboards and adds scheduled export.

**A KPI target is a business acceptance criterion** (product sign-off 2026-07-22): it is authored by
Business **on the Requirement**, not by the Builder inside the component that implements it — a
`kind: 'kpi'` Requirement carries `target` with `comparator`/`unit`, and the Widget that satisfies it
renders against that agreed bar.

**A schedule is a Job, not a new entity** (C6, 2026-07-04): `type: 'report'` with
`params: {reportKind, dashboardId, format, recipients}`. ⚠ Dispatch keys on `params.dashboardId` being
present, **not** on `type === 'report'`, because that type predates C6 and covers other report jobs.

Server side, `ReportJob` (`inspecto-engine/src/main/java/com/gamma/job/ReportJob.java`) takes `out_dir`
and `format`, path-jails the directory, and writes `<job>_<timestamp>.<ext>`:

| Format | Requires | Renderer |
|---|---|---|
| `json` | nothing — the rollup default | pretty-printed report |
| `csv` | `scope: dataset` | row serialisation |
| `png` | `scope: dataset` | `TablePngRenderer`, JDK-native Graphics2D, a 50-row table snapshot |
| `pdf` | `scope: dataset` | `PdfRenderer` — the PNG wrapped in a hand-written single-page PDF (one image XObject, no text layer), because no PDF library is on the classpath and the build is offline |

The artifact is registered on the run and a `REPORT_READY` event is emitted carrying `job`, `scope` and
`path`. ⚠ **`json` is missing from both user-facing lists** — `GLOSSARY.md` and `USER_GUIDE.md`
each say "CSV / PDF / PNG", and `json` is the *default*. ⚠ **SMTP delivers that path, not an attachment** — a stated channel limitation, confirmed in the
job's own contract.

### 3.7 Curated templates

`GET /bi/templates` serves a corpus that is a **Java constant** (`BiTemplates.TEMPLATES`) with exactly
three ids: `kpi-overview`, `quality-monitor`, and `trend-monitor` (the temporal one, added 2026-09-02 —
two `line` Widgets over `event_date` at `month` grain plus a KPI). `POST /bi/templates/{id}/apply`
takes a Dataset and an optional id `prefix`, **conflict-checks every target id first and 409s if any
exists**, then writes them all — all-or-nothing, so a half-written board is not reachable. What it
writes is **UI-native**: `{vizType, datasetId, controls, options}` Widgets and `{name, tiles}`
Dashboards, so an applied board renders immediately instead of needing a translation step.

The gallery pane lists them and applies through a dialog with a Dataset picker and the prefix field, and
blocks apply when no Dataset exists. ⚠ Template ids are **entirely server-owned** — the client has no
catalog of them, so a doc that lists ids must read the Java constant.

### 3.8 Public embed

Sharing is fail-closed by construction. `ShareTokens`
(`inspecto/src/main/java/com/gamma/control/ShareTokens.java`) mints
`base64url(type/name/expiry).base64url(HMAC-SHA256)`; the secret comes from `-Dbi.share.secret` and must
be at least 16 characters, otherwise the whole feature reports itself disabled, issuing nothing and
verifying nothing. Verification checks the signature with a constant-time compare and then the expiry,
and `ShareRoutes` returns **the same `404`** for every failure — tampered, malformed, expired or
unknown are indistinguishable to a caller.

Three routes: `POST /dashboards/{name}/share` (capability-gated, default TTL 168 hours, `ttl_hours` to
override), `GET /public/dashboards/{token}` (anonymous resolve) and
`POST /public/dashboards/{token}/query` (anonymous query). The query is **fenced to the datasets the
shared Dashboard's own Widgets reference** — anything else is a 403 — and is still `SqlGuard`-checked.
`/public/dashboards/` is exempted from the auth gate as a *self-verifying* prefix, which is deliberately
a different mechanism from the exact-match public-path set (`SEC` §3 owns that distinction).

The viewer is `/share/:token` with **no shell and no guard**. It self-registers the plugin registry,
because nothing else on that path would. `embedQueryBody()` narrows each tile to
`{dataset, measures, groupBy, orderBy, limit}` — validated pairs only, never SQL text — and returns
`null` for the two cases that cannot be embedded: a **view-bound** Widget (a saved geo or link view) and
a channel carrying a free-text **expression** (a named Measure). Those tiles say so explicitly. Each
tile fetches independently, so one bad tile shows an inline alert while the page survives; only a bad
token fails the whole page.

### 3.9 Link Analysis

`/studio/link-analysis` works on **P3 — Entity/Link graphs** (records as business entities), never on
artifact or lineage graphs (`GLOSSARY.md` §11 keeps the four graph planes distinct).

**Where the graph comes from.** The `entity-projection` **GraphSource** is a *mapping, not a store*: a column becomes the
source Entity, another the target, optional columns carry the Link type and attributes. It is
**backend-first only** — `POST /inv/projection` folds server-side in DuckDB
(`GROUP BY` with `COUNT(*)`, **heaviest-first**, default cap 2,000 and maximum 20,000, returning
`{rows[{source,target,kind,count,attrs?}], truncated}`), `POST /inv/projection/neighbors` does one-hop
expansion with bound parameters, and `GET /inv/schema/relationships` infers naming-convention foreign
keys across Datasets (`<base>_id` → a Dataset named `<base>`) so a multi-mapping projection can be
pre-filled instead of hand-picked. `attrCols` are extra validated identifiers that join the group-by
key, so distinct attribute combinations become distinct rows. The client cap is
`PROJECTION_NODE_CAP = 500`, and an Entity id is type-scoped (`entity:<type>:<value>`) only when a
multi-mapping merge supplies a type, so single-mapping ids and every existing saved view stay
byte-identical.

**Rendering** is the shared G6 host, reused by the Catalog graph and by the geo bridge. ⚠ **It does not
live where two docs say it does:** `inspecto-ui/src/app/inspecto/graph/` holds only pure libraries, while the
host component and the layout catalogue are a *feature* file,
`inspecto-ui/src/app/modules/admin/catalog/graph-view.component.ts`, which Studio imports across features.
Nodes are canvas-drawn, so inspector logic is verified in unit tests, not preview clicks.

**Toolboxes.** **Eleven layouts** (`dagre`, `grid`, `force`, `force-cluster`, `radial`, `concentric`,
`circular`, `mds`, `mindmap`, `org`, `radial-tree`, the tree shapes gated to acyclic data). Communities
by either label propagation or Louvain. The analysis depth lives in a pure, framework-free library, so a
new algorithm is a `(graph, …) ⇒ result` drop-in: advanced traversal (weighted shortest path by tie
strength, canonicalised cycles, articulation points and bridges, ego network); the algorithm library
(PageRank, closeness/eigenvector/Katz centrality, HITS, k-core, triangle count, Bron–Kerbosch cliques,
max-flow with min-cut, maximum spanning forest, Jaccard similarity, link prediction); and
**suspicion scoring**, an explainable 0–100 composite with a per-node factor breakdown whose top decile
the toolbox highlights. Super-linear work is guarded by `ANALYSIS_NODE_CAP` (2,000).

**Six pattern packs** pre-fill the motif builder: layering chain, pass-through, inbound collector,
forwarding relay, circular flow, shared associates. They became a per-Space `pattern-pack` component
kind on 2026-07-26 rather than staying a hardcoded constant; packs whose shape is not a path motif point
at the fitter tool instead.

**A timeline** filters edges only — an edge survives when its chosen attribute parses as a date at or
before the cutoff, nodes untouched — and slots into the existing filter pipeline (kind filter → time
filter → branch collapse → the shared displayed-graph binding), so no new filtering mechanism was
needed and it participates in undo/redo like every other presentation filter.

**Saved investigations** are `link-analysis-view` components with version history through the shared
component-history dialog, and a saved view whose source is `entity-projection` is renderable as a Widget.

### 3.10 Geo Map Analysis

`/studio/geo-map` answers the *where* of an investigation, sibling to Link Analysis's
*who-connects-to-whom*. Vocabulary is `GLOSSARY.md` §11-Geo: GeoSource, GeoQuery, GeoPoint/GeoRoute, Geo
View, Layer, Geocoder — ⛔ never "marker" or "pin" in a model name.

**Fully offline.** A MapLibre GL host over a basemap bundled in `inspecto-ui/src/assets/basemap/` — ⚠ **four
slimmed Natural Earth GeoJSON layers (land, boundaries, lakes, places) plus glyph fonts, ~2.7 MB. It is NOT
PMTiles**: a planet extract at z0–6 would have been ~100 MB, so D2 (2026-07-05) took GeoJSON and accepted
the consequence of no satellite or terrain imagery in-bundle. The plan kept `pmtiles://` as a registered
protocol for a future customer archive, and **no code in the tree references it today**. An offline
place-table Geocoder sits behind a pluggable seam.

**Data plane.** A **GeoSource** projects Dataset rows to GeoPoints (lat/lon column mapping) or to
weighted great-circle **od-routes**. Server side (`inspecto-geo-link/src/main/java/com/gamma/geolink/GeoRoutes.java`):
`POST /geo/projection` filters valid WGS84 coordinates with `TRY_CAST` and reports a `skipped` count;
`POST /geo/routes` folds origin/destination/kind with a summed `weight`. **Plain SQL only** — no `ST_*`
call exists anywhere in the tree. The client cap is `GEO_POINT_CAP = 5000` on both paths.

**Display and tools.** Heatmap or markers, a time slider with playback (~30 steps), filter-to-view; an
intelligence toolbox of co-location, frequent-location and stay-point analyses; measure, radius,
polygon and note tools; a layer manager with custom GeoJSON overlay upload and GeoJSON export;
parallel-route bows. Saved views are `geo-map-view` components carrying GeoSource + GeoQuery + display
options + camera.

**The bridge** (`INV-4`): a co-location result opens a graph dialog over the shared G6 host. A point
that resolves an `objectRef` also offers "View in graph", the shared investigation-pivot contract.

### 3.11 Measure alerting

An `AlertRule` in measure form is
`alert { dataset:, measure: agg(field), comparator, threshold, severity }`
(`inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java`). Its constructor **requires** the
legacy ledger-metric field, `window` and `when` to be absent, which is how "no per-rule filters" is
enforced rather than merely documented. `DatasetMeasureProbe`
(`inspecto-engine/src/main/java/com/gamma/query/DatasetMeasureProbe.java`) is the headless evaluator —
the same compile-then-sandbox pattern as §3.3 reduced to a scalar — and it **degrades to empty rather
than throwing**, so a broken Dataset cannot take down the sweep. `AlertService` evaluates these on every
sweep and fires the existing `ALERT_FIRED` path; the Alert object and its notification are `INC`'s.
A worked example ships: `spaces/demo/config/orders/orders_volume_alert.toon`.

### 3.12 Edition gating

**The BI half is ungated** — `BiRoutes`, `ShareRoutes` and the templates are in the core built-in route
list, and no `features.*` flag governs the Query Library, Viz Library, Dashboard Builder, Template
Gallery or KPI gallery. `EDITIONS` `CP-08` is ✅ in all three editions and is correct.

**The two investigation studios are Standard+** (`CP-09`, EDG-01 cell 3b, 2026-09-07).
`inspecto-geo-link` contributes `GeoRoutes` and `InvRoutes` through the public `RouteModule`
ServiceLoader seam (`inspecto-geo-link/src/main/resources/META-INF/services/com.gamma.control.RouteModule`).
Absent the module, core `AbsentGeoLinkRoutes` claims the five paths and answers `503` naming what is not
installed. The SPA reads `bootstrap.features.geoLink` into a session signal and **hides** the two nav
entries and the Menu-Builder offer.

🔴 **`hasRoute` deliberately excludes stubs, and that exclusion is the whole point.** The first version
counted them, so `/bootstrap` reported `geoLink: true` on a Personal build because the 503 stub had
claimed the pattern. *"A route exists" and "the feature is installed" are different questions* — the
flag is derived from what actually registered, never guessed from the edition string. Stubs register
**last**, only for patterns `hasRoute` reports unclaimed.

⚠ **The routes stay registered when the flag is false.** Only the nav entry is hidden, so a deep link
still reaches the pane, which then renders the module's 503 as an edition message rather than a generic
query failure. Pinned by `NoGeoLinkShipsInThePersonalBuildTest`.

## 4. Decisions (dated one-liners)

| Date | Decision | Who / where |
|---|---|---|
| 2026-06-28 | Report-builder design locked with the user: UI-first and phased; a Visualization Type declares a config schema and in code that is a `VizPlugin`; ⛔ never call the instance a "chart" | archived `report-builder-design.md` |
| 2026-07-01 | `chart → widget` rename taken as part of M1, while it was still mock-only and cheap | archived `widget-library-spec.md` |
| 2026-07-04 | **C6 — a schedule IS a Job**, `type: 'report'` with the dashboard in `params`; no separate scheduling entity | `kpi-reports.md` |
| 2026-07-04 | Link Analysis scoped **FULL** by the owner: include the business Entity/Link graph over Datasets, not only system graphs; all four analysis operations plus node search, kind filtering and canvas highlighting | archived `link-analysis-studio-plan.md` |
| 2026-07-05 | Geo decisions locked with the user: **D1** MapLibre GL + PMTiles; **D2** basemap fully bundled offline, consequence accepted — no satellite or terrain imagery in-bundle; **D3** mock-first delivery with the backend later; **D4** geocoding demoted out of the MVP, lat/lon columns required, returning later as a pluggable seam | archived `geo-map-analysis-plan.md` |
| 2026-07-07 | Studio persistence becomes **real** — datasets/widgets/dashboards/queries join the writable component kinds (W3/W4) | `studio.md`; `MET` |
| 2026-07-08 | Link Analysis and Geo both go **backend-first**: `POST /inv/projection` folds server-side, the studio stops folding rows in the browser | `REQUIREMENTS` `INV-1`; `link-analysis.md` |
| 2026-07-08 | The BI Could-tier shipped in one pass: headless `POST /bi/query`, HMAC share tokens, the template seed pack, measure alerting, report delivery | `REQUIREMENTS` §3.5 |
| 2026-07-20 | Schema-relationship inference shipped (`GET /inv/schema/relationships`), closing the design's other deferred half; self-references included, unusable Datasets skipped rather than fatal | `link-analysis.md` |
| 2026-07-20 | **PDF export** shipped as the PNG-wrapped-in-PDF fallback — no PDF library on the classpath and the build is offline, so a snapshot, not a general-purpose export | `kpi-reports.md` |
| 2026-07-20 | The investigation pivot (design review R8): a point resolving an `objectRef` offers "View in graph" over a shared contract | `geo-map.md` |
| 2026-07-22 | **A KPI target is a business acceptance criterion**, authored by Business on the Requirement — not by the Builder inside the component that implements it | product sign-off; `kpi-reports.md` |
| 2026-07-22 | Geo Phase 4 backend shipped (`GeoRoutes`) to scale past the browser point cap | `geo-map.md` |
| 2026-07-24 | Four Link-Analysis V2 tracks shipped (advanced traversal, algorithm library, suspicion scoring, pattern packs), plus the edge-only timeline and per-view version history | `link-analysis.md` |
| 2026-07-24 | Geo client-perf follow-ons **closed as obsoleted**: the server fold plus a hard 5,000-point cap leave nothing to offload; the binner is retained but unwired and the UI has no worker infrastructure | `geo-map.md`; `BACKLOG` §7 |
| 2026-07-26 | Pattern packs become a per-Space `pattern-pack` component kind rather than a constant; the Exchange `kind` axis gains `link-analysis-view`; the pane declares its six canonical terms in-product | `link-analysis.md` |
| 2026-08-14 | **Time grain travels on the wire** — `QuerySpec.grains` is the one source of truth and ⛔ a client-side fold must not be re-added | `studio.md` |
| 2026-08-14 | The Dataset store picker lists **real catalogued stores**, business groups only; and ⛔ a Dataset's `sourceName` is **never defaulted** | `studio.md` |
| 2026-08-14 | The rows seam is asked in **one** place (`DatasetRowsService`), under the spec-running result service; every result is a page carrying `truncated` and an error | `studio.md` |
| 2026-08-31 | **`MOCK-DEAD-COMPUTE-1`** — the former client-side folds are retained as reference oracles after the mock backend's deletion, because the live paths are asserted against them; deleting them deletes the guards | code contracts in `entity-projection.ts`, `geo-projection.ts` |
| 2026-09-02 | `trend-monitor` added to the template pack — the temporal starter board | `studio.md`; `EDITIONS` `CP-08` |
| 2026-09-07 | **EDG-01 cell 3b** — `GeoRoutes` + `InvRoutes` move to `inspecto-geo-link` (Standard+); Personal gets a core 503 stub | operator; `EDITIONS` `CP-09` |
| 2026-09-07 | 🔴 `hasRoute` **excludes absent-module stubs**, so a capability flag stays honest; stubs register last and only for unclaimed patterns | `ApiContext` contract |
| 2026-09-08 | This spec: `INV-3` moved to `INC` and its edition corrected; `INV-1`/`INV-2`'s stale open items and mislabelled deferral fixed; `BI-1`'s Datasets pane, `BI-4`'s formats and `BI-7`'s follow-up corrected; the Visualization Type count pinned at 13 | this file §2 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

| Item | Row |
|---|---|
| DuckDB `spatial` extension for geo — deferred for want of any `ST_*` demand; progressive loading obsoleted by the 5,000-point cap | §7 *Geo map* |
| Template pack **enrichment** — the corpus is deliberately small and grows on demand | §7 C7 (continuous) |
| Widget/Dashboard sharing and RBAC beyond the Exchange axis — gated on the security module | `studio.md`; `SEC` |

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-GREENCELL-1`, `SPEC-MOCKRESIDUE-1`, `SPEC-PLANSTALE-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

1. 🔴 **The deleted mock backend's doc residue is repo-wide and unowned.** Twenty current-tier documents
   still describe the offline mock as if it existed, and `studio.md` names
   `inspecto-ui/src/app/inspecto/mock/sample-sources.ts` — a **dead path**: there is no `mock/`
   directory, and the file itself moved to `inspecto-ui/src/app/inspecto/fixtures/sample-sources.ts`. This
   commit fixes the Studio-owned pages; the other pages belong to `PIP`, `MET`, `ACQ` and `OPS` and need
   one sweep with a board row, because every one of them can send a reader to a dead path. The `SPC`
   spec had already recorded the deletion date, which is how this was noticed.
2. **`GET /bi/datasets` has no client consumer** — the seventh instance of a shipped server half with no
   caller, and the second inside `BI-7` alone. Either the SPA should list Datasets through it or the
   route should be retired in favour of the component registry.
3. **`POST /queries/{id}/run` still has no client consumer**, and `studio.md` named it as Studio's
   execution path. `DAT` §5 already carries this as a Must; Studio is the area that was documented as
   its consumer, so the two records should be filed together.
4. **A Measure has no server home.** `BI-3` and `BI-5` both speak of Measures, but a named Measure is a
   client-side expression while the wire only carries validated `{agg, field}` pairs. A named Measure can
   therefore never cross to `/bi/query`, be embedded, or drive a measure alert. That gap is deliberate
   today; whether Measures become a server concept is a product decision, not a doc edit.
5. **The two investigation panes keep routes the nav hides.** With `features.geoLink` false the routes
   stay registered, so a deep link reaches a pane that can only 503. It renders an edition message, so
   this is honest, but it is an affordance that exists only to explain itself — the same pattern the code
   comments flag elsewhere.
6. **One bridge, called "bridges".** `INV-4` is plural and shipped as exactly one direction. The
   deferred full hand-off into Link Analysis is recorded only in a dialog comment and an archived plan
   phase, with no board row.
7. **No `SchemaRelationship` model reaches the client.** `attrCols` is a flat list of extra columns; the
   inference route returns suggestions. Whether the richer relationship model the design named is
   actually wanted is undecided.
8. **A `g6` render kind exists as a placeholder** in the render dispatch, with the real graph and map
   hosts arriving through the async component loader instead. Either the kind is dead and should go, or
   it is a seam and should be documented as one.
9. **The template gallery's own specs use ids that do not ship.** Its tests name `kpi_total`,
   `sum_by_dim` and `kpi_board` alongside the one real id, so a reader grepping for shipped ids finds
   fixtures. Ids are server-owned; the fixtures should say so.
10. 🔴 **`BI-4`, `BI-6` and `BI-7` promise Standard+ for ungated code** (the note under §2). A Personal
    install can mint a public share link, run a headless BI query and render a PDF. Either the three rows
    become `All`, or a seventh EDG-01 cell moves them. A product decision either way.
11. 🔴 **The anonymous public embed has no compliance control.** `compliance/controls-matrix.md` holds no
    occurrence of share, embed, anonymous or public-embed, yet `/public/dashboards/{token}` is an
    unauthenticated surface returning customer data. The mechanism is fail-closed and well built; what is
    missing is the control row an auditor would rely on.
12. **`SP-BI-07`'s processor id is `transform.semantic.metric`** — the banned BI word, in an id, in the very
    `EDITIONS` cell that calls the concept a Measure. An id is a rename with a migration, so this is a
    decision to take deliberately, not a doc fix.
13. **The Studio pane roster is stated four ways and none matches the route table.** `studio.md` and
    `okf/frontend/features/index.md` both omit the **Templates** pane that `BI-8` says shipped;
    `USER_GUIDE.md` lists **Menu Builder** as a Studio pane and omits Templates; `EDITIONS` `CP-08` still
    lists Datasets, which left. This commit fixes `studio.md`; the other three need one pass.
14. **`link-analysis.md`'s toolbox inventory has a hole where community detection should be.** It lists
    fifteen algorithms and no community tool, while `INV-1` and the user guide both promise Louvain — and
    Louvain ships. The one doc meant to be the mechanism's source of truth is the one that omits it;
    §3.9 above states it, the concept page still needs the entry.
15. **Ten Studio review sheets in the archive are cited by nothing** (~58 KB under
    `docs/archived-documents/superpower-reviews/`, including the only written-up account of that community
    detection). Two archived plans link them through a `reviews/` relative path that does not resolve.
16. **Three archived plans still label themselves ACTIVE or not-started for work that shipped** — pattern
    packs ("code-complete, NOT VERIFIED"), projection authoring ("build not started") and the widget-tags
    migration ("build NOT started"), the latter two shipped 2026-07-27. The archive is unmaintained by
    policy, but current pages cite these three *as design of record*.
17. **`exchange-sharing.md` is named authoritative for sharing and never mentions editions.** Sharing a
    Widget or a saved view needs `inspecto-exchange` (`SEC-10`, gated 2026-09-07), so a Personal install
    cannot do it — and the authoritative doc does not say so.

## 6. Refused & superseded

| Item | Verdict | Why / source |
|---|---|---|
| An external **marketplace/exchange** for Widgets and Dashboards | **Out of scope by design** | `BI-8` shipped as a curated seed pack; cross-space sharing stays the bundle path |
| The DuckDB `spatial` extension | **Deliberately refused, not deferred work** | no geometry operation is needed, and the hardened sandbox disables extension loading |
| Progressive loading and worker-side binning for geo | **Closed as obsoleted 2026-07-24** | the server fold plus the 5,000-point cap leave nothing to offload; revisit only if the cap is deliberately raised, and then the candidate is the toolbox analyses, not binning |
| Satellite and terrain imagery in the bundle | **Accepted consequence of D2 (2026-07-05)** | the basemap is fully offline; imagery would need the network |
| A **PMTiles** planet extract as the bundled basemap | **Refused at bundling, 2026-07-05** | ~100 MB at z0–6; slimmed Natural Earth GeoJSON at ~2.7 MB shipped instead, and no code references the protocol today |
| SVG export from the map | **Refused** | MapLibre is WebGL/canvas; PNG, GeoJSON and CSV cover it |
| Routing engines (turn-by-turn, isochrones, road network) | **COULD/WON'T** | cannot ship offline cheaply; great-circle covers the investigation cases |
| Cross-dataset joins or blending inside a Widget | **Not planned in either milestone** | a Widget binds exactly one Dataset |
| Porting the bespoke G6 gallery demos (Fishbone, Arc, SubGraph, cluster-sort) | **Refused 2026-07-04 (owner)** | expose G6's real layout types instead |
| A `link-analysis-view` ConfigSpec | **Refused 2026-07-26** | buys validation, not authoring; the defensive UI mapper is already the boundary |
| Guessing a projection mapping when scoring is ambiguous | **⛔ Refused** | an arbitrary column pair "produces a graph that looks authored and is wrong" — refuse instead |
| Setting `entityType` on a drafted single mapping | **Refused** | it would break node-id byte-identity with every existing saved view and export |
| A reserved `_`-prefixed system Space to own pattern packs (D16) | **Overturned by the operator 2026-07-26** | it passes discovery then dies at id validation, logging a spurious WARN every boot; per-Space forking was accepted |
| Re-keying notes by component type+id (the narrow D10 option) | **Refused 2026-07-25** | it "buys the same feature and guarantees a third caller becomes a third special case" |
| Two tag systems on one Widget card (D7 options a and b) | **Refused 2026-07-26; the operator chose (c)** | "two tag systems on one card is the split-brain phase 2 existed to end" |
| Geocoding in the geo MVP | **Demoted 2026-07-05 (D4)** | lat/lon columns fit the core cases; it returns as a pluggable seam with an offline lookup table |
| A client-side fold for time grain | **⛔ Must not be re-added** | a fold cannot bucket rows the server already aggregated |
| Defaulting a Dataset's `sourceName` | **⛔ Refused 2026-08-14** | the old `?? 'data'` named a nonexistent key and made a source-less Dataset read empty everywhere |
| A generic `storeOptionLoader` for schema forms | **Deliberately not built** | one field names a store; build it when a second genuinely does |
| Deleting the retained fold functions | **⛔ Refused (`MOCK-DEAD-COMPUTE-1`, 2026-08-31)** | they are the reference oracles the live paths are asserted against |
| Per-rule filters on a measure alert | **Refused in v1, enforced in the constructor** | `when`/`window` are rejected outright on a measure rule |
| Attachments on emailed report artifacts | **Channel limitation, stated** | SMTP carries the path; `INC` owns the channel |
| A general-purpose PDF export | **Refused 2026-07-20** | no PDF library on the classpath and an offline build, so PDF is a PNG snapshot wrapped in a minimal container |
| Free SQL text on the embed and BI wire | **Refused by construction** | only validated `{agg, field}` pairs cross; a view-bound or expression Widget degrades to "not embeddable" |
| Distinguishable failures on a share token | **Refused** | tampered, expired, malformed and unknown all return the same 404 |
| Counting absent-module stubs in `hasRoute` | **Refused 2026-09-07, learned the hard way** | it reported `geoLink: true` on a Personal build |
| `/studio/datasets` as a Studio pane | **Superseded (Phase B.2)** | a Dataset is a Catalog asset; the path survives as a redirect |
| The offline mock arm under every Studio result path | **Superseded by the mock backend's deletion** | a backend failure now surfaces |
| Distributed graph databases, streaming graph analytics, live co-editing, 3D/VR/AR, GPU compute, federation, blockchain, OSINT/dark-web ingestion, media extraction, forensics, cyber attack graphs, interactive graphs beyond 100M nodes | **Won't, per the owner's own list** | archived `link-analysis-studio-plan.md` |
| **`INV-3` as a Studio requirement** | **Superseded by this spec** | a Case is an operational object; `INC` owns the chain |

## 7. As-built pointers

| Concern | Code | Docs |
|---|---|---|
| Plugin seam, registry, render dispatch | `inspecto-ui/src/app/inspecto/viz/viz-types.ts`, `viz-registry.ts`, `viz-components.ts`, `viz-render.component.ts`, `plugins/index.ts` (+ `standard.plugins.ts`, `table.plugin.ts`, `kpi.plugin.ts`, `bubble.plugin.ts`, `gauge.plugin.ts`, `scatter.plugin.ts`, `funnel.plugin.ts`, `view.plugins.ts`) | [`studio.md`](../../frontend/features/studio.md) |
| Spec, grain, result and rows seams | `inspecto-ui/src/app/inspecto/viz/query-spec.ts`, `time-grain.ts`, `result-set.ts`, `show-me.ts`, `dataset-result.service.ts`, `dataset-rows.service.ts`; `inspecto-ui/src/app/inspecto/api/bi-query.service.ts` | [`queries.md`](../../backend/control-plane/queries.md); `DAT` §3 |
| Headless BI + templates | `inspecto/src/main/java/com/gamma/control/BiRoutes.java`, `BiTemplates.java`; `inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java` | `EDITIONS.md` `CP-08` |
| Share tokens + embed | `inspecto/src/main/java/com/gamma/control/ShareTokens.java`, `ShareRoutes.java`; `inspecto-ui/src/app/modules/admin/share/share-viewer.component.ts`, `inspecto-ui/src/app/inspecto/api/share.service.ts` | `SEC` §3 (public paths) |
| Studio panes | `inspecto-ui/src/app/modules/admin/studio/studio.routes.ts`; `queries/`, `widgets/`, `dashboards/`, `templates/`, `link-analysis/`, `geo-map/` | [`studio.md`](../../frontend/features/studio.md) |
| Dashboards | `inspecto-ui/src/app/modules/admin/studio/dashboards/dashboard-types.ts`, `dashboard-editor.component.ts`, `dashboard-filter-bar.component.ts`, `dashboard-drill-drawer.component.ts` | [`dashboard.md`](../../frontend/features/dashboard.md) |
| KPIs, Reports, delivery | `inspecto-ui/src/app/modules/admin/kpi-reports/kpi-reports.component.ts`; `inspecto-engine/src/main/java/com/gamma/job/ReportJob.java`, `TablePngRenderer.java`, `PdfRenderer.java` | [`kpi-reports.md`](../../frontend/features/kpi-reports.md) |
| Measure alerting | `inspecto-engine/src/main/java/com/gamma/alert/AlertRule.java`, `AlertService.java`; `inspecto-engine/src/main/java/com/gamma/query/DatasetMeasureProbe.java`; `spaces/demo/config/orders/orders_volume_alert.toon` | `INC` §3 (the Alert object) |
| Link Analysis | `inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java`; `inspecto-ui/src/app/inspecto/api/inv.service.ts`; `inspecto-ui/src/app/inspecto/graph/graph-analysis.ts`, `graph-source.ts`, `graph-export.ts`, `graph-history.ts` (pure libs); `inspecto-ui/src/app/modules/admin/catalog/graph-view.component.ts` (**the host component + `GRAPH_LAYOUTS`**); `inspecto-ui/src/app/modules/admin/studio/link-analysis/entity-projection.ts`, `graph-sources.ts`, `pattern-packs.ts`, `link-analysis-toolbox.component.ts`, `link-view-widget.component.ts` | [`link-analysis.md`](../../frontend/features/link-analysis.md) |
| Geo Map Analysis | `inspecto-geo-link/src/main/java/com/gamma/geolink/GeoRoutes.java`; `inspecto-ui/src/app/inspecto/api/geo.service.ts`; `inspecto-ui/src/app/modules/admin/studio/geo-map/geo-projection.ts`, `geo-map.component.ts`, `geo-view-widget.component.ts`, `colocation-graph.dialog.ts` | [`geo-map.md`](../../frontend/features/geo-map.md) |
| Edition gate | `inspecto/src/main/java/com/gamma/control/AbsentGeoLinkRoutes.java`, `ApiContext.java`, `BootstrapRoutes.java`; `inspecto-geo-link/src/main/resources/META-INF/services/com.gamma.control.RouteModule`; `inspecto-ui/src/app/inspecto/api/session.service.ts` | `EDITIONS.md` `CP-09`; `PKG` |
| Saved-view + widget kinds | `inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java` (`WRITABLE_TYPES`); `spaces/demo/config/registry/` | `MET` §3 |
| Cases (**`INC`'s**) | `inspecto-engine/src/main/java/com/gamma/objects/ObjectType.java`; `inspecto-ops/src/main/java/com/gamma/ops/ObjectService.java`; `inspecto-ui/src/app/modules/admin/objects/cases.routes.ts` | [`incidents/incidents.md`](../incidents/incidents.md) |

**Gap rows** (a pointer that should exist and does not): 🔴 **this area has effectively no backend
tier.** The consolidation plan measured it — *Widget Builder* and *Dashboard Builder* appear **zero times**
under `okf/backend/`, `/bi/query` gets two lines, and *Entity Projection*, *GeoQuery* and *MapLibre* appear
zero times — so `okf/frontend/features/studio.md` has been the de-facto backend spec. Five route families
(`/bi/query`, `/bi/templates`, the share routes, `GeoRoutes`, `InvRoutes`) have **no concept page at all**,
which is why §7 points at code for them and why this spec is the first place they are written down
together. Also missing: a Feature page for the Viz Library / Widget Builder and for the Template Gallery,
and a page for the shared G6 host.

**Archive cited as authority** (history tier, never maintained): `report-builder-design.md` (the locked
2026-06-28 design and the Visualization-Type/`VizPlugin` vocabulary), `widget-library-spec.md` (the
`chart → widget` rename), `studio-implementation-plan.md`, `studio-bi-improvements-plan.md` (⚠ its "8 viz
plugins" is a 2026-07 snapshot, not the count), `link-analysis-studio-plan.md` (the owner's 2026-07-04
scope and the won't-do list), `link-analysis-and-graphsource.md`, `link-analysis-toolboxes-plan.md`,
`link-analysis-pattern-packs-plan.md`, `link-analysis-projection-authoring-plan.md`,
`geo-map-analysis-plan.md` (D1–D4 and the deferred hand-off), `geo-map-case-studies.md`,
`widget-tags-assignment-migration-plan.md`, `agt5-p1-investigation-plan.md`. Cite them for *why*; cite
this file and the concept pages for *what is*.

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (⚠ **the checker is NOT in this repo** — it was a session scratch script (recorded 2026-09-09 as `TOOL` §5.2 item 1). Until it is committed to `tools/`, re-derive the check by grepping this file's backticked paths and class names against `git ls-files`.) indexes the tracked and
  untracked tree and checks every backticked repo path, Java class and test name behind a control probe
  that must pass first. **One MISSING hit is deliberate:** `inspecto-ui/src/app/inspecto/mock/sample-sources.ts`
  in §3.3 and §5.2 is quoted precisely *because* it no longer exists — it is the dead path `studio.md`
  was sending readers to. Do not "fix" it by deleting the citation.
* **Backend tests** (14 classes): `ControlApiBiQueryTest`, `ControlApiBiTemplatesTest`,
  `ControlApiShareTest`, `ControlApiComponentSharesTest`, `ControlApiAlertRuleWriteTest`,
  `NoGeoLinkShipsInThePersonalBuildTest` (core); `ControlApiGeoProjectionTest`,
  `ControlApiInvProjectionTest` (`inspecto-geo-link`); `MeasureCompilerTest`,
  `MeasureCompilerGrainExecutionTest`, `AlertRuleTest`, `AlertServiceTest`, `ReportJobDeliveryTest`
  (`inspecto-engine`); `AlertServicePersistenceTest` (`inspecto-ops`).
* **UI tests** — 62 vitest specs across the viz seam, the Studio panes, KPI & Reports and the share
  viewer. The load-bearing ones: `viz-registry.spec.ts` and `view.plugins.spec.ts` (the plugin seam),
  `entity-projection.spec.ts` and `graph-sources.spec.ts` (backend-first projection and `attrCols`, and
  the reference-fold agreement `MOCK-DEAD-COMPUTE-1` exists to keep), `geo-projection.spec.ts` and
  `geo-case-studies.spec.ts` (the geo fold and the point cap), `pattern-packs.spec.ts` (the six packs),
  `share-viewer.component.spec.ts` (embed degradation), `dataset-result.service.spec.ts` and
  `dataset-rows.service.spec.ts` (the two seams).
* **Guards** — `node tools/check-vocabulary.mjs` (this area is where the Measure and Visualization
  Type/Widget distinctions are most likely to slip), `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, all from the repo root.
* **Falsify, don't read** — three claims worth re-probing on a running server: `POST /bi/query` with a
  free-text expression must be a 422, never an executed string; a tampered share token and an expired
  one must return the identical 404 body; and on a Personal bundle `POST /inv/projection` must be a 503
  naming the module while `/bootstrap` reports `geoLink: false`.
