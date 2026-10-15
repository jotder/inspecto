# Link Analysis configurable limits — interview record and plan (`LA-CONFIG-LIMITS-1`)

**Status: DECIDED 2026-10-15 (interview complete, CL-1…CL-18) — nothing built; P1 next.** When built: distil the
as-built facts into `okf/backend/modules/link-analysis.md` and `okf/frontend/features/link-analysis.md`, move
residuals to `docs/BACKLOG.md`, archive this file.

## 1. Intent (operator, 2026-10-15)

Every Link Analysis limit that is hardcoded today (canvas caps, algorithm ceilings, traversal fences, budgets, host
resource caps) becomes a **configuration item**, shown in **one configuration menu**, **unified between backend and
UI** (one item = one key, read by both), and **gated by the server** from that configuration. The UI keeps no
private copy of a server value.

## 2. Inventory basis

Code read 2026-10-15 (two explorer passes, backend and SPA). Summary of what exists today:

- **Already per-Space settings** (`link-analysis.toon`, `GET|PUT /settings/link-analysis`, 422 outside range):
  `projection_node_cap`, `analysis_node_cap`, `suspicion_node_cap`, `hub_threshold`, `merged_distinct_cap`,
  `seed_by_distinct_cap`, `max_set_bytes`, `max_investigation_bytes`, four-eyes thresholds, `masking_mode`,
  `graph_run.{max_nodes,max_edges,timeout_ms,threads,queue,max_result_items,propagated_risk_weights}` (defaults only),
  `index.{enabled,max_disk_bytes,keep_versions,threads,queue,max_age_hours}`, `drafts.*`, `embed.*`.
- **System properties** (install-wide, not persisted in a Space): `control.rateLimit.linkAnalysis.*`,
  `control.rateLimit.embed.*`, `assist.sql.{memory_limit,threads,traversal_threads,timeout_seconds}`,
  `inspecto.la.draft.heavy`, `processing.duckdb.memory_limit`.
- **Hardcoded** (~80 constants): Graph Run ceilings 500,000 / 5,000,000 / 300 s; per-algorithm node ceilings
  (`Algorithm`); index fences (depth 2, frontier 20, 8 deltas, buckets 16..1,024, 4 M edges per bucket, row group
  100,000, pin TTL 30 d); expand budget 2,000 / 20,000, frontier 1,000; recursive paths depth 6 / 10, 5 s statement;
  connect seeds 20 / 3 hops; pattern, value-measure, reference, case-history, dossier and list caps; embed active
  grants 20; scheduled-build waits.
- **SPA copies of server values** (drift): node-cap defaults, expand budgets, frontier, connect caps, Case members,
  Working Set read 10,000, geo points 5,000; SPA-only display caps (LOD labels 300, layered 30, force-atlas 800,
  embed labels 80 / edges 3,000).
- ⚠ **The "8 GB cap" is bench-only** (`IndexScaleBench`, `inspecto.bench.buildMemory`). Production index builds
  pass no memory limit and fall through to the shared DuckDB cap (`processing.duckdb.memory_limit`, else 40 % of RAM
  / 4, floor 1 GiB).

## 3. Decisions (operator, 2026-10-15)

| # | Item | Decision |
|---|---|---|
| CL-1 | Canvas projection cap | Configurable in the menu; **default 1,000** (was 500). User tunes to their machine. |
| CL-2 | Suspicion score cap | **One value for browser and server**, configurable; **default 1,000** (was 750 browser / 500 server). |
| CL-3 | Browser analysis cap | **Per-algorithm caps**, configurable; **default 2,500**, except algorithms whose measured ceiling is lower keep that measured default (betweenness, closeness, cliques, max flow, link prediction, eigenvector, Katz, HITS, propagated risk). |
| CL-4 | UI copies of server values | **All hardcoded values become configuration items**, one key shared by backend and UI; the server gates on that configuration; the SPA reads in-force values from the server and deletes its copies. |
| CL-5 | Who edits | **Two layers**: the Space admin sets limits and ceilings (server-enforced); each analyst may lower or raise **browser-only** caps (canvas, browser algorithms) for their own machine, up to the admin's ceiling. |
| CL-6 | Host-resource limits | **Install-wide, system admin only** (DuckDB memory, query threads, rate limits, Graph Run pool, index build threads); analysis limits stay per Space. Both shown in the one menu. |

| CL-7 | Graph Run hard ceilings | **Configurable, install-wide (admin)**; defaults stay 500,000 nodes / 5,000,000 edges / 300 s. Per-Space defaults stay 50,000 / 500,000 / 30 s. |
| CL-8 | Index build memory | **Own install-wide setting, default 80 % of RAM** (production builds have none today; the 8 GB was bench-only). Threads alongside it. |
| CL-9 | Index fences | **All configurable** (they vary by client requirement and host): traversal depth (default 2), frontier keys per level (default 20), delta cap (default 8) per Space; bucket sizing (default 4,000,000 edges per bucket) install-wide, a change forcing full rebuilds. |
| CL-10 | Investigation and traversal limits | **Configurable per Space, defaults DOUBLED** (today's values were measured on the operator's laptop): expand budget 4,000 default / 40,000 max; frontier 2,000; connect seeds 40 seeds; recursive paths depth 12 default / 20 max, statement timeout 10 s (paths / pairs / edge-yield caps doubled likewise). Connect hops: see open question. |

| CL-11 | Connect hops | **Default 3, max 6** (Space admin may raise). Not doubled: work grows with degree^hops. |
| CL-12 | Index build vs ingest | **80 % of RAM, and a build waits while ingest is active** (the two never overlap), so 80 % + ingest's 40 % cannot overcommit the host. |
| CL-13 | Rate limits | **Into the menu, install-wide (system admin)**; defaults unchanged (LA burst 30, 1 per 2 s; embed 60 then 2/s per link). Replaces the JVM system properties. |
| CL-14 | Display-only UI caps | **Per-user browser settings** (the analyst layer of CL-5), defaults unchanged: LOD labels 300, layered layout 30, force-atlas 800, embed labels 80 / edges 3,000, geo points 5,000; bounded by the Space ceiling. |

| CL-15 | Small input-shape caps | **All configurable** (~40: id length 512, note 2,000, labels 80 / 200, references 200 per Investigation, embed grants 20, reveal 100 ids, dossier 5,000 steps / 20 snapshots, page sizes and the rest of §2's hardcoded list); defaults unchanged unless CL-10 doubles them. |
| CL-16 | Validation | **Per-item min/max**; out of range is refused 422 with the range in the message (replaces today's shared 1..10,000,000 bound for Graph Run threads and queue). |
| CL-17 | Menu | **Extend the existing Link Analysis Settings pane** with three sections: *Analysis* (Space admin), *Host resources* (system admin; read-only for others), *My browser* (each analyst). |
| CL-18 | Delivery | **Phased**, below. |

## 4. Delivery phases

1. **P1 — one source of truth.** A server-published in-force limits block on `GET /settings/link-analysis` (every
   item: value, default, min, max, layer, who may edit); the SPA reads it and deletes its copies (CL-4). Behaviour
   unchanged. Kills drift first.
2. **P2 — canvas and browser algorithms.** CL-1, CL-2, CL-3, CL-14 and the per-user *My browser* layer (CL-5).
3. **P3 — analysis limits per Space.** CL-7 defaults, CL-9 Space fences, CL-10 / CL-11 doubled defaults, CL-15.
4. **P4 — host resources, install-wide.** CL-7 ceilings, CL-8 / CL-12 index build memory and build-waits-for-ingest,
   CL-9 bucket sizing, CL-13 rate limits, Graph Run / index pools; the *Host resources* section.

Every phase: each new key passes `ConfigSafetyValidator`, a real-HTTP test per gate, the per-item range (CL-16), and
the measured default it replaces is recorded beside the key.

## 5. Open questions

- None from the interview. To settle while building P3: whether doubled defaults (CL-10) need a re-measure on a
  server-class host before they ship.

## 6. Design (assistant, 2026-10-15 — binding contract for the build lanes)

### 6.1 The registry

- **One catalogue**: `com.gamma.entitystore.LaLimit` (enum in `platform/inspecto-entity-store`), one constant per
  item: `path` (dotted TOON path, also the wire key), `layer` (`SPACE` | `INSTALL`), `browser` (true = the analyst may
  set a personal value in their browser, clamped to the Space value), `group`, `unit` (`count`, `nodes`, `edges`,
  `rows`, `bytes`, `ms`, `s`, `min`, `h`, `days`, `chars`, `hops`, `per_s`, `pct_ram`), `defaultValue` (a number, or
  none = "no threshold", for the two four-eyes items only), `min`, `max` (the per-item range, CL-16), and a one-line
  `description`.
- **Storage**: SPACE items in the Space's `link-analysis.toon` at their `path`; INSTALL items in `la-limits.toon` in
  the server-wide document home (resolved like `SchedulerRoutes.systemDocPath`: `-Dsystem.config.dir`, then the spaces
  container root, then the sole Space's write root); the host installs the resolved path at boot with
  `LaLimits.installDocPath(Path)`, the `DuckDbUtil.installMemoryLimit` pattern.
- **Existing keys keep their paths** (`projection_node_cap`, `graph_run.max_nodes`, `index.keep_versions`,
  `drafts.max_open`, `embed.max_ttl_hours`, and so on); new items use `<group>.<name>`. ⚠ Every writer of
  `link-analysis.toon` becomes **read-modify-write over the raw TOON map** (overlay the keys it models, keep every
  other key): today `LinkAnalysisSettings.write` rebuilds the map from the record and would silently drop new keys.
- **Reading**: `LaLimits.forSpace(Path spaceWriteRoot)` returns `LaLimitValues` (`long get(LaLimit)`, plus a nullable
  getter for the four-eyes items); resolution = the stated value, else the shipped default; a stored value outside the
  item's range reads as the default (a hand edit cannot pass the range). Cached on file CONTENT, not mtime. The
  existing `LinkAnalysisSettings.effective*()` accessors delegate to the registry so every current caller keeps
  working.
- **The server gates on it**: every code site that holds one of the catalogue's constants reads `LaLimitValues`
  instead. A request outside the in-force value keeps that site's existing behaviour (refuse, clamp or fall back);
  only the NUMBER moves.

### 6.2 HTTP contract (P1)

- `GET /settings/link-analysis/limits`: read like `GET /settings/link-analysis` (no extra capability). Answer
  `{"items":[{"key":"projection_node_cap","layer":"space","browser":true,"group":"canvas","unit":"nodes",
  "default":500,"min":1,"max":100000,"value":null,"inForce":500,"editable":true,"description":"…"}]}`.
  `value` = stated (null = inherit); `editable` = whether THIS caller may change it (Space items `canAuthorWorkbench`,
  install items `canAdminister`); ordered by group, then catalogue order.
- `PUT /settings/link-analysis/limits`: `canAuthorWorkbench`; body `{"<key>": number or null}` (null clears =
  inherit); SPACE keys only; an unknown key, an install key, or a value outside `min..max` is **422 naming the key and
  the range**; all or nothing; answers the GET shape.
- `PUT /system/link-analysis/limits`: `canAdminister`; INSTALL keys only, same rules; pod-scoped like
  `/system/scheduler`.
- Each new route clears the four gates (`endpoint` skill): capability manifest, `docs/api/openapi-v1.json` and the
  module's `openapi.fragment.json`, route-gating evidence regenerated, and a real-HTTP test per gate with an armed
  Authenticator.

### 6.3 SPA (P1)

- `LaLimitsService` (`inspecto-ui/projects/link-analysis/src/link-analysis/`) loads the GET once per Space and exposes
  `inForce(key)` as signals. The **My browser** layer is a per-user map in `localStorage` (wrapped in try/catch;
  absent = the Space value), clamped to `[min, Space inForce]` for `browser:true` items; `effective(key)` = personal
  value, else the Space value.
- Every hardcoded SPA copy of a catalogue value reads `effective(key)` or `inForce(key)` instead, and the copy is
  deleted. The server stays the judge.
- The existing Link Analysis Settings pane gains three sections rendered FROM the GET (no per-item template):
  *Analysis* (Space items, editable when `editable`), *Host resources* (install items, read-only unless `editable`),
  *My browser* (`browser:true` items: a personal value plus "reset to Space value").

### 6.4 Lanes

| Lane | Depends on | Owns |
|---|---|---|
| P1a backend registry and routes | none | `LaLimit`, `LaLimits`, `LaLimitValues`, raw-map writers, the three routes, install doc path, gates |
| P1b SPA service and menu | the §6.2 contract (builds in parallel with P1a) | `LaLimitsService`, the three pane sections, deleting the SPA copies |
| P2 canvas and browser algorithms | P1 | CL-1, CL-2, CL-3, CL-14 defaults; per-algorithm browser caps; the server's `suspicionScore` ceiling unified |
| P3 Space analysis limits | P1 | CL-9 Space fences, CL-10 / CL-11 / CL-15 wired at their code sites with the new defaults |
| P4 host resources | P1 | CL-7 ceilings, CL-8 / CL-12 build memory and wait-for-ingest, CL-9 bucket sizing, CL-13 rate limits, Graph Run and index pools moved to INSTALL |

## 7. Catalogue (keys are binding; P1 ships TODAY's defaults, the new default lands in the phase named)

Ranges: counts `1` to 100× the default unless stated; byte, time and thread items as stated per item.

**SPACE, browser-overridable** (`browser:true`)

| key | today | new (phase) | code today |
|---|---|---|---|
| `projection_node_cap` | 500 | 1,000 (P2) | `entity-projection.ts` PROJECTION_NODE_CAP_DEFAULT |
| `analysis_node_cap` | 2,000 | 2,500 general (P2) | `graph-analysis.ts` ANALYSIS_NODE_CAP_DEFAULT |
| `suspicion_node_cap` | 750 | 1,000, browser and server unified (P2) | `graph-analysis.ts` SUSPICION_NODE_CAP_DEFAULT; `Algorithm.SUSPICION_SCORE` 500 |
| `browser_algorithms.<algorithmId>` (one per algorithm) | the analysis cap | 2,500, or the measured lower ceiling (P2) | new |
| `display.lod_label_cap` | 300 | same | `link-analysis.component.ts` LOD_LABEL_CAP |
| `display.layered_max_nodes` | 30 | same | LAYERED_MAX_NODES |
| `display.force_atlas_max_nodes` | 800 | same | FORCE_ATLAS_MAX_NODES |
| `display.embed_label_limit`, `display.embed_edge_limit` | 80, 3,000 | same | `embed-view.page.ts` |
| `display.geo_point_cap` | 5,000 | same; also `GeoRoutes.DEFAULT_LIMIT` | `geo-projection.ts`, `geo-analysis.ts` |

**SPACE** (Space admin). Existing keys: `hub_threshold` 500, `merged_distinct_cap` 20,000, `seed_by_distinct_cap`
20,000, `max_set_bytes` 64 MiB, `max_investigation_bytes` 4 GiB, `four_eyes_budget_above` and
`four_eyes_fan_out_above` (none), `graph_run.max_nodes` 50,000, `graph_run.max_edges` 500,000, `graph_run.timeout_ms`
30,000, `graph_run.max_result_items` 10,000, `index.max_disk_bytes` 0, `index.keep_versions` 2,
`index.max_age_hours` 24, `drafts.max_open` 50, `drafts.hibernate_after_minutes` 60, `drafts.expire_after_days` 30,
`embed.default_ttl_hours` 72, `embed.max_ttl_hours` 720. New (P3 wires them and doubles where CL-10 says):

| key | today | new | code today |
|---|---|---|---|
| `investigation.expand_budget_default`, `investigation.expand_budget_max` | 2,000, 20,000 | 4,000, 40,000 | `InvestigationRoutes` DEFAULT / MAX_EXPAND_BUDGET; SPA `investigate-number.ts`, `investigation-rung-form.ts` |
| `investigation.max_frontier` | 1,000 | 2,000 | `InvestigationRoutes.MAX_FRONTIER`; SPA MAX_EXPAND_FRONTIER |
| `connect.max_seeds`, `connect.max_hops`, `connect.default_hops` | 20, 3, 3 | 40, 6, 3 | `ConnectSeeds`; SPA `seed-list.ts` |
| `traversal.depth_default`, `traversal.depth_max` | 6, 10 | 12, 20 | `InvRoutes` |
| `traversal.timeout_s` | 5 | 10 | `InvRoutes.TRAVERSAL_TIMEOUT_SECONDS` |
| `traversal.paths_default` / `_max`, `traversal.pairs_default` / `_max`, `traversal.edge_yield_default` / `_max`, `traversal.rows_default` / `_max` | 1,000 / 10,000, 100 / 500, 10,000 / 100,000, 2,000 / 20,000 | doubled | `InvRoutes` |
| `index.traversal_depth`, `index.frontier_cap`, `index.max_deltas` | 2, 20, 8 | same | `IndexedTraversal`, `IndexedExpand`, `IndexPlan` |
| `pattern.*` (legs, matches, timeout, temporal rows and limit) | as coded in `PatternRoutes` | doubled | `PatternRoutes` |
| `measures.*`, `comparison.*`, `reference.*`, `case_history.*`, `dossier.*`, `working_set.*`, `files.*`, `snapshots.*`, `neighbours.*` page and row caps | as coded | doubled where they cap results or rows, else same | per the inventory |
| `input.*` shape caps (id 512, note 2,000, purpose 1,000, labels 80 / 200, references 200, reveal 100, link kinds 100, list members 5,000, Case members 100, embed active grants 20, Entity Types 64, recurring rules 32, excludes 366) | as coded | same (CL-15) | per the inventory |

**INSTALL** (system admin; P4 wires them)

| key | today | new | code today |
|---|---|---|---|
| `graph_run.ceiling_nodes`, `graph_run.ceiling_edges`, `graph_run.ceiling_timeout_ms` | 500,000, 5,000,000, 300,000 | same | `GraphRunService.Limits.standard()` |
| `graph_run.threads`, `graph_run.queue` | 2, 16 (per Space today) | same, moved to INSTALL | `LinkAnalysisSettings.GraphRun` |
| `index.threads`, `index.queue` | 1, 4 (per Space today) | same, moved to INSTALL | `LinkAnalysisSettings.Index` |
| `index.build_memory_pct` | none (the shared DuckDB cap) | 80 % of RAM; a build waits while ingest runs | `IndexBuildService` passes `Options(null, …)` |
| `index.edges_per_bucket`, `index.min_buckets`, `index.max_buckets` | 4,000,000, 16, 1,024 | same | `BucketFunction` |
| `rate.link_analysis_burst`, `rate.link_analysis_per_s` | 30, 0.5 | same | `RateLimiter.linkAnalysis()` (JVM properties) |
| `rate.embed_burst`, `rate.embed_per_s` | 60, 2 | same | `RateLimiter.embed()` |
| `traversal.memory_limit_mb`, `traversal.threads` | 1,024, 4 | same | `SqlSandboxPolicy` (JVM properties) |
| `drafts.heavy_jobs` | min(4, cores / 3) | same | `DraftAdmission` (JVM property) |
