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
