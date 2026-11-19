---
type: Capability
title: Studio (BI + INV) — capability spec — Purpose, requirements and specification
description: Part 1 of 3 of Studio (BI + INV) — capability spec: Purpose & scope; Requirements of record; Specification
status: current
item: layer.product
audience: internal
verified: 2026-11-19 last-edit
---

# Studio (BI + INV) — capability spec — Purpose, requirements and specification

Part 1 of 3 of [Studio (BI + INV) — capability spec](plan-studio.md) · next: [Specification — KPIs, Reports and scheduled delivery onward](plan-studio-kpis-reports.md).

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
* **Measure alerting** — measure-shape Alert Rules (`alert-rule` components) evaluated headlessly.
* **Link Analysis** — Entity Projection, the shared G6 host, the layout and algorithm toolboxes,
  pattern packs, saved Link-Analysis Views.
* **Geo Map Analysis** — the offline MapLibre basemap, GeoSource/GeoQuery, the intelligence
  toolbox, tools and layers, saved Geo Views.
* **The edition gate** for the two investigation studios (`inspecto-geo-link`, Standard+).

**Out of scope (owned elsewhere)**

* ⛔ **Cases (`INV-3`) are not Studio's.** A Case is one value of `ObjectType` on the same
  operational-objects machinery as Alerts and Incidents, and the chain Alert → Incident → Case is owned
  by `INC` ([`incidents/incidents.md`](../../addon/incidents/plan-incidents.md)), which already specifies Case
  contents, merge and split, Findings, Disposition, Case Rules and RCA templates. §2 moves the row.
* **Datasets, Queries as persisted components, the Result Set contract itself and the operational
  stores** → `DAT` ([`data-plane/data-plane.md`](../platform/plan-data-plane.md)). Studio *binds* to
  Datasets; it no longer owns the pane that defines them (§3.4).
* **The component registry, ETag/If-Match, versions and restore, Tags** → `MET`
  ([`metamodel/metamodel.md`](../../module/config/plan-metamodel.md)). Widgets, Dashboards and both saved-view kinds
  are ordinary writable component kinds.
* **The Job framework** (triggers, scheduling, the parameter contract) → `PIP`/`OPS`. Studio owns only
  the `report` Job Type's *payload*.
* **Alert objects, notification channels and delivery** → `INC`. Studio owns only the measure-rule
  *evaluator*.
* **Lens homes, navigation and the shell** → `UI`; **the Exchange sharing axis** → `SEC`/`MET`;
  **auth for the public routes** → `SEC` ([`security/security.md`](../../module/access/plan-security.md)).
* **The Catalog graph view** → `MET`, even though it shares this area's G6 host.

## 2. Requirements of record

The board rows were `docs/okf/product/layer/platform/spec-requirements.md` §3.5 (that section was stripped to an index on 2026-09-09 — this file is their only home now) `BI-1`…`BI-8` and §3.6 `INV-1`…`INV-4`, plus
`EDITIONS.md` `CP-08` and `CP-09`. Below, each row is restated as it actually holds. **Where a cell says
CORRECTION the board was wrong on 2026-09-08 and has been amended in the same commit as this file.**

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **BI-1** | Studio authoring: **Widgets** (Visualization Type + config + Result-Set binding) and **Dashboards**, on real persistence through the widened component store | Must | ✅ SHIPPED (W3 widened `WRITABLE_TYPES`). **CORRECTION:** the row lists **Datasets** as a Studio authoring surface; they left. `/studio/datasets` and the Studio root both **redirect to `/catalog/datasets`** (Phase B.2 — a Dataset is a Catalog data asset). Studio binds to Datasets, `MET`/`DAT` own them | All |
| **BI-2** | **`VizPlugin`** registry of Visualization Types | Must | ✅ SHIPPED. **The count is 13**, and `BUILTIN_VIZ_PLUGINS` is the one place to read it: `kpi`, `table`, `bar`, `line`, `area`, `pie`, `bubble`, `gauge`, `scatter`, `funnel`, `geo-map`, `link-analysis`, `reconciliation`. The row's "charts, tables, scatter, funnel, …" states no count; the archived plan says "8 viz plugins" and is a 2026-07 snapshot | All |
| **BI-3** | KPI & Reports gallery; dashboard quick-filter bar, drill-through, time grain, PNG export; **Measures** in Explore | Should | ✅ SHIPPED. ⚠ A **Measure is a client-side `NamedMeasure`** (`{id, expression, label}`) authored per Dataset; there is **no server Measure entity**. The same "the client's model described as the server's" shape `MET-1`/`MET-3` carry | All |
| **BI-4** | Scheduled report/export delivery | Should | ✅ SHIPPED. **CORRECTION:** the cell says "a timestamped JSON/CSV artifact" — the code dispatches **four** formats (`json`, `csv`, `png`, `pdf`); `csv`/`png`/`pdf` each **require `scope: dataset`** and a rollup report renders as `json`. ~~The SMTP caveat in the cell is **true and confirmed in code**: the mail carries the artifact *path*, not an attachment~~ — **REVERSED 2026-09-29 by D-P6** (`ASSURE-XLSX-ATTACHMENTS-1`): a fifth format `xlsx` (DuckDB `excel` extension on a sealed connection, `ReportXlsx`, now `XlsxWorkbook`), and `recipients` + `attach: true` mail the Job's OWN delivered artifact as a size-capped, type-allowlisted attachment (`MailAttachments`, see `backend/control-plane/events-metrics.md` § *Mail attachments*). Text cells in `csv` and `xlsx` are formula-neutralised: a leading tab or CR, or a first non-whitespace character among `= + - @ | %` or their fullwidth forms `＝ ＋ － ＠`, gets a `'` prefix; numbers are never prefixed | **S/E as a product decision the code does not apply** — see the note below this table |
| **BI-5** | Alerting on **Measures** | Could | ✅ SHIPPED. The "v1 = whole-dataset measures, no per-rule filters" caveat is **enforced, not merely intended**: a measure rule's constructor requires `when`, `window` and the legacy metric field to be absent | All |
| **BI-6** | Public/embedded Dashboard sharing | Could | ✅ SHIPPED, backend and UI. Fail-closed as described: inert without `-Dbi.share.secret` (and it must be ≥16 chars), expiring, and **every** failure — bad signature, malformed, expired, unknown — returns the *same* `404` ⚠ **Named for grep:** the share dialog is `ShareDashboardDialog` (`share-dashboard.dialog.spec.ts` pins it). | 🔴 **the row says `S/E`, not `All`** — and the code is ungated (`ShareRoutes`/`ShareTokens` are core). See the note below this table |
| **BI-7** | Semantic / headless BI API | Could | ✅ SHIPPED. **CORRECTION (two):** (a) the cell's "Open follow-up: swapping the UI viz layer onto it" is **done** — `DatasetResultService`'s live path *is* `POST /bi/query`, and every Widget renders through it. (b) `GET /bi/datasets`, which the cell also claimed, is ⛔ **RETIRED 2026-09-14** (`RETIRE-HALVES-1`) — it never had a client consumer; Dataset listing goes through the generic component registry | **S/E as a product decision the code does not apply** — see the note below this table |
| **BI-8** | Widget/Dashboard template marketplace | Could | ✅ SHIPPED as a **curated seed pack**, not a marketplace. Exactly three ids ship (`kpi-overview`, `quality-monitor`, `trend-monitor`), the corpus is a Java constant, and apply is all-or-nothing with a `409` on any id collision. An external marketplace is out of scope by design (§6) | All |
| **INV-1** | **Link Analysis Studio**: Entity Projection over a Dataset, shared G6 host, 11 layouts, communities, pattern matching, saved **Link-Analysis Views** | Should | ✅ SHIPPED. **CORRECTIONS (three):** (a) the cell's "Open per design §7: `attrCols` mapping surface + the schema-relationship model" — **both shipped**, `attrCols` on both sides and the relationship inference on 2026-07-20; the concept page had already flagged that note stale. (b) "with the offline sample fold as fallback" is **gone**: since the mock backend was deleted a backend failure **surfaces**. (c) The counts are pinnable — 11 layouts, 6 pattern packs, two community methods | **S/E** — `inspecto-geo-link` (see `INV-2`) |
| **INV-2** | **Geo Map Analysis Studio**: offline MapLibre basemap, GeoSource/GeoQuery, heatmap, od-routes, time slider + playback, intelligence toolbox, measure/radius/polygon/notes tools, layer manager + GeoJSON overlays, saved **Geo Views** | Should | ✅ SHIPPED. **CORRECTIONS (two):** (a) "DuckDB-spatial backend = Phase 4" mislabels both halves — the **Phase 4 server-side projection shipped** (`POST /geo/projection`, `POST /geo/routes`), and the DuckDB `spatial` extension is a **deliberate refusal**, not a pending phase (no geometry op is needed and the hardened sandbox disables extension loading). (b) The client-side fallback is gone, as for `INV-1`. (c) 🔴 **The basemap is not PMTiles**: `inspecto-ui/src/assets/basemap/` ships four slimmed Natural Earth GeoJSON layers plus glyph fonts (~2.7 MB), and **zero `.pmtiles` files exist in the tree** — no code references the protocol either. A planet extract would have been ~100 MB, so it was refused at bundling time (D2, 2026-07-05); the word survived in this row, in `geo-map.md`, and in this file's own first draft | **S/E** — ✅ **GATED 2026-09-07** (EDG-01 cell 3b: `GeoRoutes` + `InvRoutes` moved into `inspecto-geo-link`; Basic gets a core `503` stub). ~~code is core and ungated, EDG-01~~ — stale from 2026-09-07 until 2026-09-08 |
| **INV-3** | **Cases** grouping Incidents; RCA templates; correlation ids end-to-end | Must | ✅ SHIPPED — **but this row does not belong to Studio.** ⛔ **MOVED to `INC`** (`incidents/incidents.md` owns the Alert → Incident → Case chain, Case Rules and RCA templates). A Case is one `ObjectType` value on the operational-objects tables, reached at `/cases` from the objects module, not from `/studio/*`. `GLOSSARY.md` §14's point that "Investigation" was never a coherent area is this row | **CORRECTION — S/E, not `All`.** The Case machinery is `inspecto-ops` (`EDITIONS` `CP-11`, gated 2026-09-08 by EDG-01 cell 7). Basic gets `AbsentModuleRoutes` and `features.ops = false` |
| **INV-4** | Cross-studio bridges | Could | ✅ SHIPPED — **exactly one bridge exists**: geo co-location → a graph dialog over the shared G6 host. A full hand-off into `/studio/link-analysis` was deliberately deferred, and there is no link→geo bridge | S/E with the studios |

🔴 **Three BI rows promise Standard+ for code that is ungated — the mirror image of `OPS-2`.** `BI-4`,
`BI-6` and `BI-7` all read `S/E`, but `BiRoutes`, `ShareRoutes`, `ShareTokens`, `TablePngRenderer` and
`PdfRenderer` all sit in the **core** modules and no `features.*` flag governs them: a Basic install can
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
`POST /bi/query`, `GET /bi/templates` and the capability-gated
`POST /bi/templates/{id}/apply`. A query is compiled by **`MeasureCompiler`**
(`platform/inspecto-engine/src/main/java/com/gamma/query/MeasureCompiler.java`) from
`Spec(dataset, measures, groupBy, grains, filters, orderBy, limit)` into **one** SELECT built only from
validated identifiers and typed literals — fifteen aggregations (`count`, `countDistinct`, `sum`, `avg`,
`min`, `max`, and the statistics `median`, `stddev`, `variance`, `p05`…`p99` — see
[data-plane §3.5](../platform/plan-data-plane.md)) and three grains (`day`, `week`, `month`). The compiled text then passes `SqlGuard.check`
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
(`DatasetsService.list()` → `ComponentsService.list('dataset')`). ⛔ `GET /bi/datasets` was **retired
2026-09-14** (`RETIRE-HALVES-1`) precisely because this is the only listing path anything uses.
Two Dataset rules Studio depends on, both dated 2026-08-14: the editor's store picker offers **real
catalogued stores** from `/db/catalog`, business groups only, keeping a saved Dataset's own source in the
list even when the catalog stops naming it (a `mat-select` whose value is absent renders blank); and a
Dataset's `sourceName` is **never defaulted**, because the old fallback named a key that does not exist
and made a source-less Dataset read empty everywhere, indistinguishable from an empty store.

**The Dataset list carries a freshness badge, derived at read time** (2026-09-24,
`DUCKLE-C1-DATASET-FRESHNESS-1` residual 4; `studio/datasets/dataset-freshness.ts`). After the cards
render, the list fetches `GET /alerts/rules` once and then, per Dataset, `GET /signals?type=dataset.write&source=dataset:<id>&limit=500`;
each answer lands in its own card, so nothing blocks the list. The newest write is compared with the
`maximumAge` (`Ns|Nm|Nh|Nd`) of a freshness Alert Rule naming that Dataset: within it ⇒ *Fresh*, beyond ⇒
*Stale*; with no declared limit the badge shows *Published &lt;time&gt;*, never *Fresh*. An empty page or a
failed read is *Freshness unknown* — `source` is filtered AFTER the store page is cut, so an empty answer
means "not on this page", not "never published" (hence the 500 limit rather than 1). A failed rules fetch
degrades to "no limit". ⛔ No stale flag is persisted (the `stale-tiles.ts` objection) and
`CatalogOverlay`/`latestRunTime` is never read — a pipeline run is not a Dataset publication.

**A freshness Alert Rule arms its own minute sweep** (2026-09-24, residual 1). While any armed Alert Rule
carries `maximumAge`, the platform keeps a **system job** `system.freshness-sweep` — `alert.evaluate`,
cron `* * * * *`, `scope: freshness` — and removes it when the last such rule goes
(`com.gamma.job.FreshnessSweep`, re-derived in `CollectorService.start()` and after every
`AlertService.upsert/remove`). It is an ordinary registered job (run ledger, next fire, cross-pod arming
claim, a `GET /jobs` row with `system: true`), not a thread. **Nothing is persisted for it**: every boot
re-derives it from the rules, and re-arming an identical config is a no-op — that is the idempotence.
Job CRUD answers **409** to `PUT`/`DELETE`/`enable`/`disable`/`reschedule` on it, because a delete or a
disable would quietly switch freshness off; it stays triggerable. ⚠ An *authored* job that already holds
the name wins — the sweep is refused with a WARN, never clobbered. ⚠ The Scheduler UI does not yet badge
`system`.

**The freshness clock survives retention** (2026-09-24, residual 3, duckle S2). `event_prune` drops whole
day partitions, so a Dataset silent for longer than the audit window lost its only `dataset.write`, and after
a restart its rule read *unknown* — silent, for exactly the stalest Dataset. `DatasetFreshnessProbe` now
keeps a durable floor, `<audit dir>/dataset-publications.tsv` (one `<dataset>\t<epoch ms>` line each),
rewritten whenever a Dataset's newest publication advances — from the bus **or** from a cold-start scan, so
a space that predates the file seeds it on its first sweep. The cold-start answer is `max(scan, floor)`.
Kept only over a durable event store (none on `-Devents.backend=memory`, which forgets everything on
restart anyway). ⛔ A **prune floor** (sparing the partition that holds each last publication) was
rejected: it would keep whole days of unrelated audit past the window, forever, for precisely the Dataset
that never publishes again — making the stated retention false — and would teach both event stores what a
Signal is. The file is a timestamp, not a stale flag; staleness is still computed at read time.
Pinned by `FreshnessAlertTest.aBreachSurvivesThePruneOfItsLastPublicationAndARestart` (red before the floor).

**`ConfigSpecs.alert()` is shape-aware** (2026-09-24). `metric`/`threshold`/`window` are no longer flatly
required; the `alert-metric-by-shape` / `alert-window-by-shape` / `alert-threshold-positive` rules require
them per shape, decided in `AlertRule`'s own order (investigation → freshness → measure → ledger), so a
freshness rule written through `/config/write` is no longer refused for omitting what the engine refuses.

**Alerts are owner-routed** (2026-09-26, residual 2 — the last one; the row is closed). The operator's
2026-09-15 design stands: **owner = the authenticated `Subject`, `appUser` where none**. As built, across
four seams (every rule shape, not only freshness):
* **The record.** `AlertRule.owner` — a Subject id, or `AlertRule.UNOWNED` (`appUser`) for a blank/absent
  one; `isOwned()` tells them apart. `alert.owner` is declared in `ConfigSpecs.alert()`.
* **The capture point is the component's R3 envelope, not a new one.** `owner` was already the sharing
  envelope's key on every registry component (`ComponentAccess`: stamped from the Subject on create, carried
  forward by an edit, changed only by the owner, an access admin, or a first claim on an owner-less
  component). ⚠ `/alerts/rules` used to bypass `ComponentAccess` altogether; `AlertRoutes` create/update now
  run `onCreate`/`onUpdate`, so the two write paths share one ownership model, and a save through the alert
  routes keeps the `shares` list `AlertRule` does not model (it used to strip it). A config-authored rule — a
  hand-edited registry file — names its owner with `owner:`, and a Decision Rule's `create-alert` consequence
  (no request, no Subject) carries a stored rule's envelope forward. That answers the row's "no moment at
  which a `Subject` could be captured": the moment is the write, and the owner is stored with the rule.
* **The event.** An owned rule's `ALERT_FIRED` / `ALERT_CLEARED` carry a `recipient` attribute
  (`Notification.RECIPIENT_ATTR`) and the `alert-rule.fired` / `.cleared` Signals carry `owner` in their
  payload. An unowned rule's events carry **no** recipient — that absence is what keeps them a broadcast.
* **Delivery.** A `Notification` is a broadcast (`recipient == null`) or addressed. `NotificationRule`
  renders the recipient into `{{recipient.name}}` (no longer a hardcoded `appUser`); the personal email leg
  goes to the recipient alone, still subject to its own effective preference; the in-app feed, badge count
  and SSE stream show an addressed notification to its recipient only.

Session decisions (2026-09-26, recorded here, reversible):
* **The feed is a personal inbox** — an addressed notification is hidden from every other Subject,
  `canAdminister` included. Oversight of every breach is `GET /alerts` and the Incidents a critical breach
  opens. Basic has no Subject, so there nothing is hidden, as before.
* **Unowned means broadcast**, not "addressed to `appUser`" — a Personal-built or pre-existing rule notifies
  exactly as it always did, and `appUser` is never written into the stored component (R3 would read it as a
  claimed owner and refuse the first claim).
* **`ChannelConfig` stays flat.** Operator-managed destinations (a team mailbox, a webhook) deliver every
  alert as before; the per-owner destination is the Subject's own verified email from
  `NotificationPreferenceOverrides`, which already existed — no per-owner channel was added.
* **The key is `recipient`, not `owner`, on the event**: Exchange events already use `owner` for a Space.
* **The owner is the creator, unless the caller is an admin** (operator, 2026-09-28, `OWNER-IS-CREATOR`).
  Until then R3 let a creator name any `owner` in the create body, and anyone with edit access first-claim
  an owner-less rule for anyone, which addressed that rule's alerts to the named Subject. Now a body `owner`
  other than the caller is a `403 PERMISSION_DENIED`, and a first claim may only claim for the caller, unless
  the caller holds `canConfigureAccess` or `canAdminister`. It applies to every R3 component kind, not only
  alert rules. No Subject (Basic): unchanged. Pinned by `ControlApiComponentSharesTest`.

Pinned by `AlertOwnerRoutingTest` (engine), `NotificationServiceTest` (the `owned`/`unowned`/opt-out/template
cases) and `ControlApiAlertOwnerTest` (real HTTP, with Subjects).

**A Measure is a client-side `NamedMeasure`** — `{id, expression, label}` — authored per Dataset in the
Dataset's Measures editor and previewed client-side. There is **no server-side Measure entity**: what
crosses the wire is always a validated `{agg, field}` pair, so a named-Measure expression is exactly the
kind of spec that cannot cross and must fail honestly.

The **Query Library** (`/studio/queries`) authors SQL with `$`-parameters and previews the Result Set.
⚠ The editor previews an unsaved draft through `DatasetRowsService` (`/db/table`, `/db/query`); the
*Run saved* action runs a stored query through `POST /queries/{id}/run` (`STUDIO-HALVES-1`). *(Corrected
2026-11-01: this said the route had no client caller.)*

### 3.5 Dashboards

A `DashboardConfig` is `{tiles: [{widgetId, span}], filter?: ConditionGroup, exposedFields?, description?, asOf?, illustrative?, dateField?, defaultRange?}`. The
**quick-filter bar** renders chips over `exposedFields` and emits the same `{field, value}` toggle shape
a tile's drill-down emits. The **drill-through drawer** is a slide-over that reuses the shared data table
for the rows behind a tile. **PNG export** (`exportPngs()`) downloads every canvas under a dashboard
tile, so chart tiles export and table/KPI tiles export through their own surfaces.

**Viewer header (UIE-5, 2026-09-25).** Three optional keys, declared in `ConfigSpecs.dashboard()` so the
component-route key census accepts them: `description` (one line naming the question the page answers),
`asOf` (a calendar date `YYYY-MM-DD`, shown as *As of 23 Sep 2026* and never zone-shifted; rule
`as-of-is-a-date`) and `illustrative` (a STAGED page — viewers see an *Illustrative data* chip; absorbs
DW-02). `<app-dashboard-header>` renders them under the title in the Menu viewer and the public share
viewer, owns no heading, and renders nothing when all three are absent; the editor authors them in its
toolbar. ⚠ A blank value or a `false` flag is **not written** (`compactHeader`), so clearing a field in
the editor removes the key.

**Date-range selector (UIE-5 d, 2026-09-25).** Viewer-only (operator decision): two optional keys, declared
in `ConfigSpecs.dashboard()` — `dateField` (the date column) and `defaultRange` (a preset id or a custom
`{from, to}`; rule `default-range-is-valid`). Presets count back from `asOf`, or from today when it is unset:
`last-7-days`, `last-30-days`, `last-90-days`, `month-to-date`, `quarter-to-date`, `year-to-date`,
`last-12-months`. Both ends are inclusive days; quarters are calendar quarters; last 12 months is the day after
`asOf − 12 months` (month-end clamped). The maths is `dashboard-date-range.ts`, mirrored server-side by
`DashboardDateRange.java` — change one, change the other (`ConfigSpecs.DASHBOARD_RANGE_PRESETS` is the id
list both are tested against). `<app-dashboard-date-range>` sits in the viewer's filter card beside the
quick-filter bar (Menu viewer and editor preview), seeded from `defaultRange` and transient; the editor authors
`dateField` (suggestions = the columns every tiled Dataset declares; free text allowed) and the default.
- **Per-tile scoping.** `DashboardViewStore.filterFor(dataset)` adds the range ONLY for a Dataset that declares
  `dateField` (column or calculated column); other tiles keep the plain cross-filter, which still reaches every
  tile unchanged. The range is ANDed around the cross-filter, never spliced into it, so the quick-filter bar and
  drill toggles see exactly what they did before.
- **Half-open terms.** The range is `field >= from AND field < to + 1 day`, not `between`: identical on a DATE
  column, and on a TIMESTAMP column `<= to` would stop at midnight and drop the last day.
- **Share links** get the Dashboard's DEFAULT range server-side (`ShareRoutes` → `DashboardDateRange`), inside
  the same fence as the stored filter: the recipient's `filters` are ignored, so the range can be neither
  widened nor dropped, and an unreadable `defaultRange` is a 422, never an unranged share. A viewer-chosen range
  on the share page is **out of scope**. With no `asOf`, "today" is the server's calendar day on a share and the
  viewer's wall-clock day in the app.
- ⚠ A Dataset that declares no `columns` is treated as lacking the column — its tiles are never range-filtered.

**Time grain travels on the wire (2026-08-14).** `QuerySpec.grains` (group-by column →
`day|week|month`) is the one source of truth: each plugin's `buildQuery` fills it from the channel
controls, offline bucketing walks exactly those columns, and the live body sends them as `grains` for
`MeasureCompiler` to compile to `DATE_TRUNC`. ⛔ **Do not re-add a client-side fold** — a fold cannot
bucket rows the server already aggregated. ⚠ The server returns the bucket as **text** in the UI's own
format, aliased back to the raw column name, so both paths label categories identically; and only
*grouped* columns may carry a grain, so a stale one is dropped rather than allowed to 422 the whole
Widget.
