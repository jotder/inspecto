---
type: Reference
title: Link analysis performance - decisions, seams and measured no-gos
description: As-built facts of the LA performance work (2026-10-24 to 2026-11-13) - operator decisions, the browser seams, and the options measured and refused with their numbers.
resource: inspecto-ui/projects/link-analysis/
tags: [module, la-core, link-analysis, performance, spec]
timestamp: 2026-11-13T00:00:00Z
---

# Link analysis performance - decisions, seams and measured no-gos

Distilled from the LA performance plan when it closed on 2026-11-13 (every `LA-PERF-*` row closed; full history and
every run in `archived-documents/plans-archive/la-performance-plan.md`). Server-side stage costs and the N7–N11 profile:
[`../la-api/spec-server-profile.md`](../la-api/spec-server-profile.md). Event store flush: [`../event/architecture.md`](../event/architecture.md).

**Reference graph for every number below:** la-showcase saved view `telecom_fraud_network`, 767 nodes / 4,000 links
(cut by the 4,000-ROW cap, not the node cap), production `la-app` build, demo-auth Preview, built-in browser with the
pane shown, in-page `PerformanceObserver` long tasks. No Chrome performance-panel trace was ever taken. Figures are the
LONGEST task, the freeze an analyst feels.

## Operator decisions

| Id | Question | Answer |
|---|---|---|
| PERF-D1 | Betweenness: Web Worker, approximation, or keep refused? | 2026-11-12 keep refused; **superseded 2026-11-13**: approximation built (below), worker still refused |
| PERF-D2 | May a server cache hold a masked result? | Key every server cache entry on the Dataset fingerprint plus the viewer's masking profile and row scope; identical access may share, others never. Audited 2026-11-13 (`PERF-D2-KEYS-1`): every LA cache complies, see [`../la-api/spec-server-profile.md`](../la-api/spec-server-profile.md) |
| PERF-D3 | New wire format for edge rows? | Build it (2026-11-12) as a media type, not a `v2` path (see la-api) |
| PERF-D4 | Working Set staleness bound | Cheap change token (size + ns mtime + file key + last-line hash) trusted for 30 s (`WORKING_SET_TRUST_MS`), then a full SHA-256 check |
| PERF-D5 | Heavy graph work off the main thread? | (a) No Web Worker for layouts or algorithms (LA-06 / LA-07 stay refused); fix by caps, memoisation and fewer shapes. Reopen only on an analyst-reported freeze with a trace |
| N9 | Audit append on the request thread | Option (c), built as a lock-free segment flush (event hub); the post-boot latency tail is accepted as a cold-start cost |

**Rule for every perf change:** never raise the caps (1,000 canvas / 2,500 analysis / 500 exact betweenness) as a
performance fix — caps are product decisions. Masking and row scope run before any cache or fast path.

## Browser seams (as built)

- **Selection repaint** goes through G6 element state (`syncSelectionState`), never `setData` + `draw`; `selection`
  and `emphasis` are not in `styleKey`. `brush-select` uses `immediately: false`, so the lasso emits once on release.
- **Content signature** replaces whole-graph `JSON.stringify` keys; Louvain hulls are memoised per graph signature
  and cap, and the hulls toggle never rebuilds the graph.
- **Layout switch** runs the new layout on the live graph (no destroy / rebuild); style mappers re-run only when
  `display` changed. A node size change still rebuilds (the entity icon size is fixed at construction).
- **Layout on expand:** new nodes are placed beside their drawn neighbours (`placeBesideNeighbours`, golden-angle
  spiral) and only `draw()` runs; a full re-layout only above `EXPAND_RELAYOUT_MAX_NEW` = 50 new nodes or for a node
  with no drawn neighbour. Before: 378–435 ms per few-node expand on ~405 nodes; after: not measured.
- **Force layout slicing** (`graph-sliced-layout.ts`): Force (`d3-force`) and Force (spread) (`fruchterman`) drive the
  same @antv/layout class with G6's 300 ticks but yield every ~40 ms. Longest task at open 5.4–7.6 → 2.4–3.3 s; Force
  switch 2.5–4.5 → 0.7–1.7 s. G6 5.1.1's own `enableWorker` was unusable (needs a served `worker.js`, cannot carry the
  `nodeSize` function).
- **Layout caps:** every layout is capped where it reaches G6 (`layoutConfig`, limits in `LaLimit` / `la-limits.ts`),
  Concentric is the fallback. Radial uncapped froze the tab > 3 min at 767 nodes.
- **Large-graph shape rule (>1,000 links):** above `DENSE_DETAIL_CAP` = 1,000 links, `detailHiddenByDensity` hides
  arrowheads and node labels (link labels already hide above `DENSE_EDGE_LABEL_CAP`). Hover / click reveal through a
  second state `detailed` (`revealTargets` returns `state|id`); override `display.allDetail`. Select all does not
  reveal. The gain is the arrowheads: open 2,010 → 1,032 ms, move after Grid 946 → 546, Clear 1,642 → 931 (medians).
  ⚠ Gotcha: Link Analysis starts with node labels OFF, so a "labels off" experiment on a default view measures nothing.
- **What is left in a freeze is @antv/g canvas painting** every changed shape in one `requestAnimationFrame` (a
  1,424 ms callback on a Grid switch; a camera-only repaint is 150–475 ms). Batching cannot remove it: each `draw()`
  recomputes every element's style and repaints the whole canvas.
- **Betweenness above 500 nodes is sampled** (`graph-analysis.ts`): exact up to `browser_algorithms.betweennessCentrality`
  (500); above it Brandes from k = ⌊cap² / n⌋ fixed-seed pivots (Brandes–Pich), scaled by n / k; refused only above
  2,500. 767 nodes: 781–1,285 → 423–595 ms; top node always matches exact, Spearman 0.985 (0.962 at 1,458 nodes);
  places 2–20 swap, said on screen. Suspicion score still runs exact betweenness under its own cap.
- **Client fetch:** result cache, in-flight de-dup, debounced `pushFilter`, ETag revalidation reusing the decoded body
  on 304 (budget 32 M chars of uncompressed JSON); landing-page reads de-duplicated (`short-reads.ts`). Hashed static
  files are `immutable`; 429 carries `Retry-After` and projection / neighbour reads wait it out once (≤ 10 s).

## Measured no-gos (nothing shipped)

| Item | What was tried | Numbers | Why no |
|---|---|---|---|
| N3 prefetch the graph chunk | Measure cold-open split | Chunk 0.28 s of a 7.4 s freeze (element creation 2.7 s, Force layout 4.1 s) | Prefetch saves ~4 % |
| N11 boot time | Three warm restarts | First log line 9–11 s, `/health` 37–83 s; the 185 s gap happened once after a fresh jar swap | Cold-file cost, not code |
| P11 fuse derived passes | Replica of the 12 passes at size, `stableKey` timing | 2.8 ms median of 2.3–4.5 s actions (0.1–0.3 %); `stableKey` 0.4 ms | Time is in G6 |
| WebGL renderer (`@antv/g-webgl` 2.1.1) | `main` layer on WebGL | Cold open froze > 2.5 min twice (canvas: 442 ms single run) | Cost grows with shape count |
| P10a-5 delta expand | `known` held links sent, matching rows dropped after masking | Hub: response gzip −2.5 KB, request 173 → 17,402 B, server 396 → 363 ms (noise) | Uncompressed request costs more than the gzipped rows saved; any "list what I hold" design has this |
| Selection / element batching | Select all in batches of 120; `translate`-stage moves | Longest 908 → 444 ms but total 1.7 → 3.4 s; `translate` no gain | Fixed repaint per batch |
| P9 main-log checkpoint | Benchmark on `FsInvestigationStore` | evaluate + hash 13–35 ms at 1,000 ops | Under the 50 ms line |
| P3a Grow preview rescans | DuckDB plan | DuckDB 2.0 already materialises a reused CTE (one Parquet read) | Only a semantic rewrite would help |

## Known low gaps (accepted, no row)

No test pins the production ETag-cache budget; `deepEqual` overflows at ~5,000 nesting levels (real folds never get
there); a fresh 200 at the full budget blocks the main thread ~70 ms. The two unrun live checks (hover reveal at size,
P6 after-timing) were run on 2026-11-13; see "Live checks" below.

## Live checks, 2026-11-13 (`LA-PERF-LIVE-CHECKS-1`)

Demo-auth Preview bundle of `ef0466730` on :8087 over a private copy of `la-showcase`, built-in browser with the pane
shown, `PerformanceObserver` long tasks. One run per row; the box was not otherwise idle.

- **Large-graph hover reveal: PASS.** On `telecom_fraud_network` (767 nodes / 4,000 links) the note "arrows and node
  labels on hover above 1,000 links" shows; zoomed in, links carry no arrowheads and nodes no labels. Hovering a node
  shows the arrowheads of its links. With *Node labels* turned on, nodes stay unlabelled at rest, and a hover shows the
  node's label, its links' arrowheads and the labels of the nodes at their other ends.
- **Layout on expand (P6): no measured gain.** An Investigation from `99979100001`, 1 degree (403 nodes / 403 links):

| Expand | Nodes | Long tasks (ms) | Total |
|---|---|---|---|
| double-click one subscriber | 403 → 412 | 553 · 74 · 65 · 83 · 463 · 87 · 125 | 1,450 ms |
| double-click one subscriber | 412 → 418 | 334 · 71 · 67 | 472 ms |
| toolbox Expand (first 25 unexpanded) | 418 → 444 | 775 · 112 · 117 · 52 | 1,056 ms |

  Before (2026-11-12, another build and day, ~405 nodes): 378–435 ms per 2–5-node expand. So the expand still costs a
  re-fit and repaint of the whole canvas; skipping the layout did not make it cheaper here. Also seen: the new
  entities land in a separate group far from the graph, board row `LA-EXPAND-PLACEMENT-1` (not root-caused).
