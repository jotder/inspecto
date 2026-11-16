---
type: Reference
title: Link analysis core and ports - spec
description: Classes, ports, store selection, invariants and failure handling of la-core, and the as-built performance decisions, browser seams and measured no-gos of Link Analysis.
resource: la/inspecto-la-core/
tags: [module, la-core, link-analysis, spec, performance]
timestamp: 2026-11-15T00:00:00Z
---

# Link analysis core and ports - spec

- **Core** — [classes](#classes-by-concern) · [ports](#ports) · [store selection](#store-selection-investigationstoresofwriteroot) ·
  [SPI](#spi-investigationstoreprovider) · [invariants](#invariants) ·
  [link index status and Rebuild](#link-index-status-one-answer-and-rebuild) · [tests](#tests)
- **[Performance](#performance)** — [operator decisions](#operator-decisions) · [browser seams](#browser-seams-as-built) ·
  [measured no-gos](#measured-no-gos-nothing-shipped) · [known low gaps](#known-low-gaps-accepted-no-row) ·
  [live checks 2026-11-13](#live-checks-2026-11-13-la-perf-live-checks-1)

Package `com.gamma.la.core` in `la/inspecto-la-core/`. Manifest
`la/inspecto-la-core/src/main/resources/META-INF/inspecto/module.toon`: `buildRole: platform`, `offeringRole:
optional`, `bindingTime: boot`, requires `graph`. No HTTP routes and no `META-INF/services` registrations of its own:
it defines the SPIs other modules register. Narrative design: [architecture](architecture.md).

## Classes by concern

| Concern | Classes |
|---|---|
| Op log and fold | `InvestigationEvaluator`, `InvestigationTime`, `InvestigationMembers`, `LinkEventTypes`, `LinkIds`, `LinkTemporalDetector`, `AdmiraltyGrade` |
| Storage port | `InvestigationStore` (port), `FsInvestigationStore` + `FsInvestigationLayout` (default), `InvestigationStores` (selector), `InvestigationStoreProvider` (SPI), `InvestigationVersionConflictException`, `SnapshotStore` (legacy) |
| Size limits | `WorkingSetSizeLimit` (per set), `InvestigationSetBudget` (per Investigation total) |
| Drafts | `DraftStore`, `DraftLifecycle`, `DraftCheckpoints`, `DraftIndex` |
| Graph Run | `GraphRunService`, `GraphEngine`, `InMemoryGraphEngine`, `GraphInput`, `WorkingSetGraphInput`, `GraphResult`, `GraphBudget`, `Algorithm`, `GraphRunException`, `InvalidGraphRequest`, `IndexCapExceeded`, `InputFingerprint`, `FingerprintBudget` |
| Patterns | `PatternQueryCompiler` (stage parsing; the matcher lives in `platform/inspecto-graph`) |
| Ports (+ holder) | `DatasetProvider`/`DatasetProviders`, `CasePort`/`CasePorts`, `CollectorCoveragePort`/`CollectorCoveragePorts`, `OutboundPort`/`OutboundPorts`, `UploadLandingPort`/`UploadLandingPorts` |

## Ports

Each port is an interface plus a static holder that resolves it through `SpiSlot` (ServiceLoader) and offers a
`forTest` seam, e.g. `la/inspecto-la-core/src/main/java/com/gamma/la/core/CasePorts.java`. The holder returns
`Optional.empty()` when no implementation is on the classpath; callers then answer "not installed". The production
implementations are all in `la/inspecto-geo-link/` (see the [geo-link hub](../geo-link/index.md)).

## Store selection: `InvestigationStores.of(writeRoot)`

Called once per request; no route names an implementation class
(`la/inspecto-la-core/src/main/java/com/gamma/la/core/InvestigationStores.java`).

| `-Dinvestigations.backend` | Result |
|---|---|
| `fs` (default) | `new FsInvestigationStore(writeRoot)` (holds no state) |
| `db` | the single `InvestigationStoreProvider` whose `backend()` is `db`, opened with the Space id, a `Connection` and the two size suppliers |
| anything else | 503 `CAPABILITY_UNAVAILABLE` |

Connection: `-Dinvestigations.db.url` / `.user` / `.password`, each falling back to `-Dinspecto.db.*`; the URL must start
`jdbc:postgresql:`; the password goes through `SecretResolver`, so a `${ENV:NAME}` reference works. The Space id is the
directory above `config` for a hosted Space, else `default`. Every unusable case (unknown value, no provider, a broken
or doubled registration, no or non-PostgreSQL URL, unreachable database) is **503, never a fall-back**. The outcome is
published as `live.investigations` on `/health/details`; `probeAtBoot` tries once at boot and WARNs, never stopping it.

## SPI: `InvestigationStoreProvider`

- `backend()` returns the `investigations.backend` spelling it serves (`db`).
- `open(spaceId, connection, maxSetBytes, maxInvestigationBytes)` returns the Space's store or throws `IOException`,
  which the selector turns into 503. The two `LongSupplier`s are read per write; the store must enforce both on every
  write path that stores a set.
- An implementation caches the pool and schema bootstrap (it is called per request) but **must not cache a failure**.
- `Connection.toString()` prints only the URL before `?`, never the password.
- Register in `META-INF/services/com.gamma.la.core.InvestigationStoreProvider`; exactly one may be present. The one
  implementation is [la-store-pg](../la-store-pg/spec.md).

## Invariants

- The log is append-only; a write carries `expectedVersion`, and a mismatch raises
  `InvestigationVersionConflictException`.
- Filesystem layout `<writeRoot>/audit/snapshots/investigations/<id>/`, or the data root's `audit/snapshots/…`
  for a Space with a data root ([architecture](architecture.md) §3.2). Serialised by a JVM monitor plus an OS `FileChannel.lock` on `.store.lock`.
- Graph Run checks size before work and the deadline at checkpoints; overrun ends `BUDGET_EXCEEDED` with no result.
- Library code takes every limit as a parameter from its la-api caller ([architecture](architecture.md) §9).
- The exploration edge reads (`/inv/projection`, `/inv/projection/neighbors`) send compact rows (PERF-P10-1, 2026-11-12);
  the SPA library (`projects/link-analysis/src/api/edge-wire.ts`) decodes them to the `ProjectionTriple` objects every
  fold reads, so no fold changed. Wire contract: [la-api spec](../la-api/spec.md) § Invariants.

## Link index status: one answer, and Rebuild

`LA-FULL-CYCLE-TEST-1` phase 4 (finding 21), as built 2026-11-16. Before it, a fresh import read *index v1 (stale)* in
the Investigation's data strip and the Expand said the index "is out of date … (the Dataset's relation SQL changed
since the index was built)", while the Data page said *graph up to date* and nothing offered a rebuild.

**The cause** was not the details import (a Dataset with no link mapping is `SKIPPED` by the index follow-on): every
index built outside the default Space was out of date the moment it was published, because the build worker carried no
Space and read the store as a glob where the Space reads a pinned file list ([architecture](architecture.md) §4,
*A build runs in the Space that submitted it*). Fixed at the cause: the build keeps the hash stable because the
Dataset's meaning did not change. Rebuilding on every import was the other option, and was not taken: every rebuild
went through the same worker and came out out of date again.

**One answer.** `IndexStaleness` (la-api) judges an index; `GET /inv/index` serves the judgement per index (`stale`,
`reasons`, `reason`, `fingerprint`, `plan`), and a read judges the same way before it serves from the index. The SPA
reads it through ONE helper, `datasetIndexStatus` (`projects/link-analysis/src/link-analysis/index-status.ts`):

| State | When | Data page (*Graph*, *Data status*) | Data strip (Investigation) | Expand note |
|---|---|---|---|---|
| `current` | listed, not stale, its files compared (`fingerprint: known`) | *up to date* | nothing in the sentence; tooltip *Link index version N, up to date.* | *Answered from the link index vN.* |
| `out_of_date` | any listed index of the Dataset is stale | *out of date* + **Rebuild** | *The link index is out of date.* (**Check data**) + **Rebuild**; tooltip *Link index version N, out of date.*; the drawer adds the reason | *Answered from the Dataset because the link index is out of date (reason).* |
| `unknown` | listed, files not comparable (a view-backed Dataset) | the arrivals decide | nothing in the sentence; tooltip *Link index version N.* | as the server says |
| none | no index listed, or `GET /inv/index` cannot answer (Basic) | the arrivals decide (*graph N files behind* …) | nothing in the sentence; tooltip *No link index for this Dataset.* | as the server says |

**Plain words** (the server's `details`, shown as they are): *the Dataset's files changed after the index was built
(added, removed or replaced)* · *the Dataset's definition changed after the index was built* · *the Dataset cannot be
read now* · *the index was built by another version of Inspecto (layout …)* · *the index was built with DuckDB X and
this server runs Y*; a served index that only misses added files says *N files were added to the Dataset after the
index was built*. The screens say **out of date**, never *stale* (the Glossary's states: up to date, out of date; files
not held yet are counted as *waiting*).

**Rebuild** (`<inspecto-la-index-rebuild [dataset]>`, `index-rebuild.component.ts`): shown only while the Dataset's
index is out of date; enabled for a caller holding `canBuildLinkIndex` (`LensService.canBuildLinkIndex`), otherwise
disabled with *Needs Build link index.* (its accessible name starts with the words on the button, and a status line
says when the rebuild ends). It sends the stale index's OWN mapping in the mode the server advises
(`plan.recommended`, `full` when it advises none) to the existing `POST /inv/index/builds` — no new route, so the
route's gates (capability, Dataset view, queue, disk budget) stay the only ones. When the build completes, `GET /inv/index`
is read again, so every surface agrees at once; a refused start says why in the index build sentences
(`indexBuildErrorMessage`).

**Decisions** (taken in the lane, each reversible by the operator):

- **The Data page reads the live answer, not its import record.** The arrivals record what the import's build did; the
  index can go out of date afterwards (a definition change, a removed file). When `GET /inv/index` lists the Dataset's
  index, its state wins; the arrivals decide only when there is no answer (Basic, no index, files not comparable).
- **The words live on the server.** `details` were developer text (*relation SQL*, *bucket function*); they are now the
  sentence every screen shows, so the Expand and the header cannot word one cause two ways.
- **Rebuild beside the words, not in a menu.** The person reading *out of date* is the one who wants it fixed; a
  disabled button that names the permission says who can.
- **No Rebuild on the Expand note.** The note is read inside the canvas, which another lane changes; the data strip
  above the same Investigation carries Rebuild.
- **The index's entity count is not this answer.** The import result's *460 links between 138 entities* counts each raw
  spelling as its own node (`' acc-1003 '` and `ACC-1003`) while the coverage counts 137 accounts. That is how the index
  keys a node, not whether it is current, so it is left to its own row.

Add data's background pool (an import above `import.inline_max_bytes`, a 202) had the same hole and carries the request's
Space since this phase (found by the lane's verification).

Pinned by `ControlApiFullCycleIndexStatusTest` (geo-link, real HTTP: the full-cycle fixture in a Space of its own, inline
and in the background, the showcase Space laid down as its seed's `--scaffold` does and indexed by its own Job, and an out-of-date index rebuilt
in the advised mode), `IndexBuildServiceTest.aBuildRunsInTheSpaceThatSubmittedItAndTheWorkerKeepsNoneAfter` and the
specs `index-status.spec.ts`, `index-rebuild.component.spec.ts`, `index-source.spec.ts`, `data-trust.spec.ts`.

## Tests

`la/inspecto-la-core/src/test/`; inventory in [tests-inventory](tests-inventory.md); live drives in
[tests-live-drives](tests-live-drives.md).

## Performance

As-built facts of the LA performance work (2026-10-24 to 2026-11-13), distilled when the plan closed on 2026-11-13
(every `LA-PERF-*` row closed; full history and every run in
[`la-performance-plan.md`](../../../../archived-documents/plans-archive/la-performance-plan.md)). Server-side stage
costs and the N7–N11 profile: [`../la-api/spec-server-profile.md`](../la-api/spec-server-profile.md). Event store
flush: [`../event/architecture.md`](../event/architecture.md).

**Reference graph for every number below:** la-showcase saved view `telecom_fraud_network`, 767 nodes / 4,000 links
(cut by the 4,000-ROW cap, not the node cap), production `la-app` build, demo-auth Preview, built-in browser with the
pane shown, in-page `PerformanceObserver` long tasks. No Chrome performance-panel trace was ever taken. Figures are the
LONGEST task, the freeze an analyst feels.

### Operator decisions

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

### Browser seams (as built)

- **Selection repaint** goes through G6 element state (`syncSelectionState`), never `setData` + `draw`; `selection`
  and `emphasis` are not in `styleKey`. `brush-select` uses `immediately: false`, so the lasso emits once on release.
- **Content signature** replaces whole-graph `JSON.stringify` keys; Louvain hulls are memoised per graph signature
  and cap, and the hulls toggle never rebuilds the graph.
- **Layout switch** runs the new layout on the live graph (no destroy / rebuild); style mappers re-run only when
  `display` changed. A node size change still rebuilds (the entity icon size is fixed at construction).
- **Layout on expand:** new nodes are placed beside their drawn neighbours (`placeBesideNeighbours`, golden-angle
  spiral) and only `draw()` runs; a full re-layout only above `EXPAND_RELAYOUT_MAX_NEW` = 50 new nodes or for a node
  with no drawn neighbour. Measured in the [live checks](#live-checks-2026-11-13-la-perf-live-checks-1).
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

### Measured no-gos (nothing shipped)

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

### Known low gaps (accepted, no row)

No test pins the production ETag-cache budget; `deepEqual` overflows at ~5,000 nesting levels (real folds never get
there); a fresh 200 at the full budget blocks the main thread ~70 ms.

### Live checks, 2026-11-13 (`LA-PERF-LIVE-CHECKS-1`)

Demo-auth Preview bundle of `ef0466730` on :8087 over a private copy of `la-showcase`, built-in browser with the pane
shown, `PerformanceObserver` long tasks. One run per row; the box was not otherwise idle.

- **Large-graph hover reveal: PASS.** On `telecom_fraud_network` (767 nodes / 4,000 links) the note "arrows and node
  labels on hover above 1,000 links" shows; zoomed in, links carry no arrowheads and nodes no labels. Hovering a node
  shows the arrowheads of its links. With *Node labels* turned on, nodes stay unlabelled at rest, and a hover shows the
  node's label, its links' arrowheads and the labels of the nodes at their other ends.
- **Layout on expand (P6), first attempt: measured the fallback, not P6.** Every expand took the full-relayout
  fallback (`graph.render()`), because the Expand returned its entities under a different id than the start graph
  (`LA-EXPAND-PLACEMENT-1`), so no new node had a drawn neighbour. Fallback cost on an Investigation from
  `99979100001`, 1 degree (403 nodes / 403 links): 1,450 ms total for 403 → 412 nodes, 472 ms for 412 → 418, 1,056 ms
  for a toolbox Expand of 25 (418 → 444). Before (2026-11-12, another build, ~405 nodes): 378–435 ms per 2–5-node expand.
- **P6 after the `LA-EXPAND-PLACEMENT-1` fix** (same scenario, the double click now runs the unsaved Expand step): three
  successive single-subscriber expands on 403 → 412 nodes each took `draw` only, moved 0 existing nodes and added 3
  entities beside their neighbour; long tasks 429, 309 and 545 ms in total (longest 251–317 ms). ⚠ Measured on a
  DEVELOPMENT `la-app` build (`ng serve`), not production, so it is not comparable with the production figures above;
  a production re-measure is still owed (not filed: a measurement, not a defect).
