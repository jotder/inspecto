<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-24 from a code-reading deep drive (server path, browser path). No profile or bundle build has been
  run yet: every cost below is a hypothesis until P0 measures it. Retire per the three-tier lifecycle in CLAUDE.md.
-->

# Link Analysis — UI and server performance plan

| | |
|---|---|
| Status | ACTIVE PLAN — **status as of 2026-10-25 in §0a; live measurement and the RE-RANKED open list in §0b** (fake-auth Preview build, `f6c168147`) |
| Trigger | Operator report 2026-10-24: "UI performance is poor" on Link Analysis (LA) |
| Related | [`link-analysis-roadmap.md`](link-analysis-roadmap.md) (LA-06 and LA-07 are standing refusals — see P5) · [`la-separation-feasibility-plan`](../archived-documents/plans-archive/la-separation-feasibility-plan.md) §7.4 (the partitioned index and graph engine are the long-term fix) · [`../okf/product/module/la-core/user-guide-link-analysis.md`](../okf/product/module/la-core/user-guide-link-analysis.md) (stale caps, see §6) |
| Grounding | Two read-only code investigations, 2026-10-24: server `inspecto-la-api` / `inspecto-la-core` / `inspecto-la-storage`; browser `inspecto-ui/projects/link-analysis` and `inspecto-ui/src/app/inspecto/graph/graph-view.component.ts`. ⛔ Re-ground every cite before implementing |

---

## 0a. Status as of 2026-10-25 (what shipped, what is open)

Every shipped lane went through independent adversarial verification (1–3 rounds) before its push. **No full UI suite or full backend plan was run for these pushes** (operator, session-usage limit); the operator runs the full gates. ✅ **Superseded 2026-10-25 by §0b:** a live measurement ran in the built-in browser against a fake-auth ("demo") Preview build, which removes the WSO2 certificate blocker; the live 304 round trip was observed. Still not done: a Chrome performance-panel trace (only in-page `PerformanceObserver` / `performance.now()` timings), the full UI suite and the full backend plan.

| Item | State | On `master` |
|---|---|---|
| P1 selection repaint, P1c brush coalescing, P2 content signature | ✅ SHIPPED (emphasis still repaints in full) | `5d785907a`, `f7e2eb350`, `84eed1eff` |
| P10a-1/3/4/6 client fetch cache, de-dup, debounce, local-action audit | ✅ SHIPPED | `86298ff0b` |
| P10a-2 ETag revalidation (request-level conditional cache + graph reuse) | ✅ SHIPPED, 3 verification rounds; budget is 32 M chars of UNCOMPRESSED JSON (about 62 MB heap, measured) | `8e36f14f2` |
| P4-1 gzip | ✅ SHIPPED as a HARDENING of existing gzip (it already existed) | `7ea407188` |
| P3-1/2/3 index default, top-N, count scan | ⛔ NO CODE NEEDED — already on / already planned / already skipped | — |
| P4-2 projection cache + ETag/304 | ✅ SHIPPED, 2 rounds; hit audit parity, 5,000 row / 2 MB per-entry cap, 32 MB total bound (real heap about 130–190 MB) | `e283286bd` |
| P4-3 Working Set conditional read | ✅ SHIPPED, 3 rounds; 30 s trust window (PERF-D4), monotonic clock, post-read token re-check, no ETag on any error (`ControlApi.respond` strips the tag at status >= 400) | `c5c22b5de`, `70acba42e`, `cd33f93bb` … `e65b2ce93` |
| P7 bundle | ✅ SHIPPED — main chunk 1.89 MB → 364 kB, initial 2.52 MB → 999 kB (gamma); budgets NOT tightened | `10701932f` |
| P9 main-log checkpoint | ⛔ MEASURED NO-GO (13–35 ms at 1,000 ops) | — |
| P3a Grow preview rescans | ⛔ MEASURED NO-GO (DuckDB already materialises the CTE; only a semantic rewrite would help) | — |
| P0 measure (browser timings, per-option algorithm timings) | 🟡 DONE in-page, 2026-10-25 (§0b); no Chrome performance-panel trace; largest graph available is only 767 nodes | — |
| P5 betweenness | ✅ SHIPPED 2026-11-13 as an APPROXIMATION (no worker, PERF-D5): sampled above 500 nodes, exact below; 767 nodes 781–1,285 → 423–595 ms and no longer refused (§P5 "As built") | — |
| P6 layout on expand | ✅ SHIPPED 2026-11-12 on its vitest proof; the after-timing was NOT measured (§P6) | see §P6 |
| P8 hulls | ✅ SHIPPED as N2 (§0b) | — |
| P11 polish | ❌ NO-GO 2026-11-13 — measured, the derived passes are ~0.1–0.3 % of scripting per action (§P11); nothing built | — |
| P10a-5 delta fetch on expand | ❌ MEASURED, NO-GO, 2026-11-13 — built in a lane, measured a net wire LOSS on a hub, discarded (§P10a "P10a-5 measured") | — |
| P10 wire format | ✅ SHIPPED 2026-11-12 (PERF-D3 answered: build it) — compact edge rows; raw bytes −41 %, gzip bytes only −1 to −7 %, no measurable server or parse time change (§P10 "Measured") | see §P10 |

**Known low items left open:** no test pins the production ETag-cache budget (a mutation to 3.2 billion passes); `deepEqual` overflows the stack at about 5,000 levels of nesting (real fold output never gets there); a fresh 200 at the full budget blocks the main thread about 70 ms; `menu-landing.spec` fails four tests when it runs after `session.service.spec` (that spec leaves `inspecto.currentSpace=gone` in localStorage, pre-existing on master); `FsInvestigationStore.promoteBeforeMarkHook` is a pre-existing public mutable static; the Postgres store, an ABA token change inside one mtime tick, and a real serialisation failure through the full HTTP stack were never exercised. The LA user guide still states the old caps (500 / 2,000; the defaults are now 1,000 / 2,500).

---

## 0b. Live measurement, 2026-10-25 — and the re-ranked open list

**What was measured.** A fake-auth ("demo sign-in") Preview build of `f6c168147` was deployed to `C:\sandbox\inspecto-preview\inspecto-la` (`package.ps1 -Edition Preview -DemoAuth -Ui la-app -NoBakeDemo`; launcher `serve-demo.bat`; the OIDC variant is backed up at `inspecto-preview\backup\predeploy-20261025-1245-fakeauth`). Server-side numbers below (boot, idle CPU, threads, memory, polling, jobs) were taken first-hand from the process and its log; browser and API numbers by an agent driving the built-in browser with in-page `fetch`, `PerformanceObserver` long tasks and `performance.now()`. ⚠ **Limits:** the data is small (the Spaces are 17–59 MB; the LARGEST graph is la-showcase `telecom_fraud_network`, **767 nodes / 4,000 edge rows, truncated at the 4,000-ROW cap, not at the 1,000-node cap**; no graph reaches 1,000 nodes); the browser pane was in the background (first-contentful-paint is NOT trustworthy; JS-before-paint bytes not measured); several figures are one run (flagged); no Chrome performance-panel trace; 1 Investigation exists, so list-cost scaling (P2-1) and the Entity List chain (P2-2) could not be measured; the geo views are tiny (max 449 points), so the map hypotheses (H1, H2) are NOT measured.

### Server, measured first-hand (idle, no user)
| Measure | Result |
|---|---|
| Boot to `/health` | **3 min 35 s** (12:46:31 → 12:50:06) after the swap; the first application log line came 3 min after launch, then 30 s to `/health` with no gap of 8 s or more inside the log. The 185 s before the first log line is UNEXPLAINED (JVM start, shell start or first-run scanning of freshly written files); a warm restart is owed. |
| CPU | about **95% of one core** for the first ~4–5 min after boot (start-up work), then **6.9% of one core** settled |
| Threads / memory (settled) | **3,622 OS threads, only 74 are Java** → about **3,550 native threads** with 48 DuckDB files under the Spaces and one native library loaded (DuckDB's): consistent with an uncapped worker pool PER database instance (H4; not proven by a native stack dump); working set **1.4 GB** |
| Collector polling | exactly **one poll per source per minute** per Space (26 inbox scans a minute across 5 Spaces: 11 + 6 + 5 + 3 + 1); cheap — **H1 is REFUTED as a CPU problem** at this scale |
| Scheduled jobs on a no-change tick | `system.freshness-sweep` 6–15 ms (9,972 ms only at boot), `la_detect` 13–567 ms, `la_index_build` **1,527 ms** → the no-change fast paths exist and are cheap (H5/H6 refuted at demo scale) |

### Browser and API, measured by the agent (la-showcase unless noted)
| Measure | Result |
|---|---|
| Cold load, sign-in page | DCL 231 ms; 25 requests; 19 JS files all revalidated with `304` on EVERY load (no long-lived immutable caching of hashed chunks) |
| Landing page after sign-in | about **27 API calls**; **every Space's `/bootstrap` is fetched TWICE in parallel** (6 Spaces × 2, about 17.4 KB gzip / 78 KB decoded each, plus one more) ≈ **1 MB decoded**; `/spaces` and `/spaces/_meta` ×2; components lists and `/entity-lists` ×2; the SPA's own calls take 155–227 ms against 8–37 ms alone (they queue behind the burst) — **H3/H4 CONFIRMED** |
| `/health` p50 | 14.8 ms (n=20) |
| `/bootstrap` 200 vs 304 | 25.3 vs **21.8 ms** — the 304 costs 86% of the 200 (**P2-5 CONFIRMED**) |
| Projection (767 nodes, 4,000 rows) | 200: p50 **303 ms** (479 KB decoded / 27.9 KB gzip); **304: p50 270–290 ms**, empty body — the live 304 round trip was observed (weak ETag stable, `Cache-Control: private, no-cache`, `Vary: Accept-Encoding`), but **a 304 saves bytes, not time** |
| Projection variance | 146 ms … **3.2 s**, the SPA's own call **4.4 s and 20.6 s**, a first-open of la-audit `ap_approval_graph` **7.2 s**, a warm direct fetch 310 ms — **UNEXPLAINED** |
| Neighbors (1 node) | p50 198 ms, max 502 ms, 87 KB |
| Working Set | 200 p50 **94 ms**, 304 **95 ms** (no cheaper); `/inv/investigations` with ONE Investigation **95 ms** against 15 ms for `/health`: a fixed per-request overhead of about 80 ms on these routes |
| 429 | 10–20 expensive requests within seconds, or a sustained 4 requests a second, answer `429` with **no `Retry-After`** (the standard bucket is burst 20 then one request per 3 s per subject; LA has its own bucket) |

### UI, 767 nodes / 4,000 links (longest main-thread task)
| Action | Result |
|---|---|
| Open the view, cold | longtasks **4,074 and 3,705 ms** (two cold opens); repeat opens 160–200 ms; JS heap 184 → 323 MB |
| Box-select 327 nodes | longest **579 ms**, about 2.0 s in total (8 tasks) |
| Hover | max 55 ms |
| Layouts | Circular / Grid none; **Fruchterman 3,643 ms; Force 3,113 ms; Layered 2,308 ms; 🔴 Radial FREEZES the page — the main thread blocked for more than 3 minutes, reproduced twice (a reload was needed)** |
| Community hulls toggle | **about 3.1 s every time, on AND off** (3,099 / 3,084 / 3,123 / 3,334 ms) — **H6 CONFIRMED** |
| Betweenness | refused above 500 nodes ("767 nodes, above the 500-node limit"); on a 465-node graph **385 / 369 ms** |
| Other algorithms on 465 nodes | Degree and PageRank under 50 ms; Louvain 682 ms first run (cached after); **Cycles 770 ms; Cliques 700 ms** — **H7 REFUTED** (no worse than betweenness + redraw); shortest path and Grow NOT measured |
| Memory | five away-and-back cycles: canvases 0 away / 5 back and DOM nodes 322 / 1,022 EVERY time → the view IS destroyed and rebuilt (**H5 REFUTED for canvases and DOM**); heap drifted 160 → 176 MB without a forced GC (inconclusive) |
| Health | idle: 0 requests in 35 s (no polling); 🔴 **`TypeError: Cannot read properties of undefined (reading 'getData')` in `renderMinimap`, four times, after route changes** (a real defect); 5 × "Unable to preventDefault inside passive event listener" |

### The re-ranked open list (by what an analyst feels)
Open items N4, N7–N11 and P5 are tracked on the board as `LA-PERF-*` rows (`docs/BACKLOG.md` §3.12, 2026-11-12; P11 closed as a measured no-go 2026-11-13).

**Tier 1 — freezes the page**
- **N1 Radial layout hangs the tab (> 3 min at 767 nodes / 4,000 links).** Cap or refuse Radial above a node count (a `layered_max_nodes` = 30 cap already exists as the pattern), and say so on screen; then decide on a worker (PERF-D5). Severity: high; a one-line cap removes the hang. ✅ **SHIPPED 2026-10-25** (3 builder / verifier rounds; targeted tests only): the Radial, Information density, Clustering and every other layout are capped at the one place they reach G6 (`layoutConfig`, caps through the `display` limits: Java `LaLimit`, `la-limits.ts`, docs, parity test), Concentric is the fallback, the cap notice no longer covers the legend. `d4fd1aa11`, `65593934a`, `92903f91b`.
- **N2 Community hulls toggle costs ~3.1 s on and off.** Turning hulls OFF must not recompute Louvain; memoise the community assignment per graph signature (P8, now measured). ✅ **SHIPPED 2026-10-25** (3 rounds; targeted tests only): the hulls toggle no longer rebuilds the graph, Louvain is memoised per graph signature and cap, hull shapes come off before a data change and return after it. `0ceee890f`, `f0ecaeed5`, `aec36325a`.
- **N3 Cold first-open freezes 2.7–4.1 s.** Cause not isolated: the lazy graph chunk (~1.2 MB of library, now loaded on first open since P7) plus the layout. Prefetch the chunk when the landing page is idle; measure parse vs layout separately before building anything. ⛔ **PREFETCH MEASURED NO-GO 2026-11-12** (table below): the chunk costs about 0.28 s; the freeze is G6 creating 4,767 elements (2.7 s) and the Force layout (4.1 s). What is left is the worker case, PERF-D5.
- **N4 Other layouts 2.3–3.6 s (Fruchterman, Force, Layered) and box-select 579 ms.** Main-thread layouts: P6 plus the worker decision. ✅ **Layout switch SHIPPED 2026-11-12** (targeted spec only): a layout pick no longer destroys and rebuilds the graph; it runs the new layout on the live graph and re-runs the element styles only when `display` changed too. Circular 2.7 → 1.2 s, Force 5.2 → 2.3 s (table below); a node size change still rebuilds, because the entity icon size is fixed at construction (found by the verifier). `c0b60fdb1`, `b8aababb1`. The layout itself and the selection repaint stay on the main thread (PERF-D5, answered 2026-11-12: no worker). ✅ **Layout on expand (P6) SHIPPED 2026-11-12**: a small expand no longer re-lays out the graph; before 378–435 ms per few-node expand on ~405 nodes, after NOT measured (§P6). ✅ **Force layouts sliced SHIPPED 2026-11-13** (`LA-PERF-N4-1`): Force and Force (spread) yield every ~40 ms instead of blocking; longest task at open 5.4–7.6 → 2.4–3.3 s, Force switch 2.5–4.5 → 0.7–1.7 s, Force (spread) 2.9–5.8 → 0.8–1.7 s (alternated runs, table under "N4 sliced Force layouts"). Selection batching measured worse in total and dropped. 🟡 **The three canvas freezes left, measured 2026-11-13 (second lane, nothing shipped):** element creation, the move after a layout and a large selection repaint are all @antv/g's canvas render of every changed shape in one frame (a 1,424 ms `requestAnimationFrame` callback on a Grid switch); moving through G6's `translate` stage gave no gain, and batching pays a full repaint per batch. Next step is a renderer decision (operator), table under "N4 the three canvas freezes". ✅ **SHIPPED 2026-11-13 (`LA-PERF-N4-1` closed):** WebGL was tried and rejected (froze > 2.5 min). The fix is fewer shapes: above 1,000 links, arrowheads and node labels show only on hover or click. Median longest task on the 767-node view: open 2,010 → 1,032 ms, move after Grid 946 → 546 ms, Clear 1,642 → 931 ms. Tables under "N4 fewer shapes" and "N4 large-graph rule".

#### N3 / N4 measured, 2026-11-12
Production `la-app` build of `origin/master` 948fcf54b, demo-auth Preview bundle on :8081 with a copy of `la-showcase`, saved view `telecom_fraud_network` (767 nodes / 4,000 links, default layout Force), the built-in browser. In-page `PerformanceObserver` long tasks and long-animation-frame script attribution, plus TEMPORARY `performance.mark`s on G6's `beforerender / beforeelementcreate / beforelayout / afterlayout / beforedraw / afterrender` events (not shipped). No Chrome performance-panel trace: the agent's browser tools give no DevTools protocol. Before and after are separate builds of the same tree measured in the same session.

| Action | Where the time goes | Before | After |
|---|---|---|---|
| Cold open, graph chunk | route chunk 994 KB + G6 chunk 1,458 KB, 33 ms each on localhost; module evaluation one long task | **284 ms** | (unchanged) |
| Cold open, after the projection arrives | one 7.4 s task inside the projection XHR `onload`: page fold ~0.2 s, `new Graph` 0.06 s, **element creation 2.7 s** (767 nodes 0.32 s, 4,000 edges 2.33 s), **Force layout 4.1 s**, draw 0.09 s | **7,372 ms** | (unchanged) |
| Layout switch → Circular (longest task) | before: destroy + `new Graph` 0.35 s + element creation 2.1 s + layout 0.02 s | **2,743 ms** | **1,164 ms** |
| → Grid | as above | **2,844 ms** | **1,036 ms** |
| → Force | before: rebuild 2.1 s + d3-force 3.0 s | **5,161 ms** | **2,301 ms** |
| → Force (spread) (Fruchterman) | before: rebuild 2.4 s + layout 3.7 s | **6,156 ms** | **3,837 ms** |
| Select all 767 / Clear | page work ~150 ms; **G6 `draw` of the `selected` state 1.27 s** | 1,461 / 1,496 ms | (not changed) |
| Hover the 650-link hub | edge-label reveal, pre-existing: same on a fresh open with no relayout | — | 584–868 ms |

- **After a switch, about 1 s stays at any layout** (Circular, Grid): moving 4,767 elements to their new positions. A first fix that also re-set the data cost 1.7 s for the same switch, so the style mappers are re-run only when `display` changed.
- **Not avoidable by caps, memoisation or prefetch:** element creation at first open (2.7 s, edges dominate), the Force layout (2.3–4.1 s, about 300 d3 ticks with collide), and the 1.27 s state repaint of a large selection. The lasso emits once on release (`brush-select immediately:false`), so the old "579 ms in 8 tasks" is the state repaint, not work in the brush callback. Layered (`dagre`) is capped at 30 nodes and could not be measured on this graph. No double layout was found: one `beforelayout` per open and per switch.
- Checked live after the change: the hulls follow the new layout (they come off before it and return after it), the view re-fits, Select all paints 767 and the hover tooltip shows. A screenshot pass only; no halo pixel comparison.

#### N4 sliced Force layouts, measured 2026-11-13 (`LA-PERF-N4-1`)
**Built:** Force (`d3-force`) and Force (spread) (`fruchterman`) run through `graph-sliced-layout.ts`, a G6 layout that drives the SAME @antv/layout class with the same options and G6's own 300 ticks, but yields to the event loop after every ~40 ms of ticks instead of running all of them in one task. The positions are G6's. Every other layout is unchanged. No Web Worker (PERF-D5 stands): G6 5.1.1's own `enableWorker` needs a served `worker.js` and cannot carry the `nodeSize` function, and the iterative path it uses never calls the worker anyway.

**How measured:** two production `la-app` builds of the same tree (before = `origin/master` 875a84f1f3, after = this change) served side by side by two demo-auth servers (:8086 before, :8085 after) on two copies of `la-showcase`. The installed Preview jars predate the v2 edge wire (`4a6a7cf7ef`), so `inspecto-la-api` and `inspecto-http-spi` were rebuilt from the same tree and swapped in. Saved view `telecom_fraud_network` (767 nodes / 4,000 links), the built-in browser, page visible, `PerformanceObserver` long tasks; each layout switch starts from Circular. The box was busy (CPU ~73 % from other work), so the builds were ALTERNATED and every run is listed. Figure = the LONGEST task (the freeze an analyst feels).

| Action | Before (runs) | After (runs) |
|---|---|---|
| Cold open of the view | 5,414 · 6,022 · 7,566 ms | 2,397 · 2,901 · 3,186 · 3,275 ms |
| Switch to Force | 2,451 · 4,453 · 4,282 ms | 745 · 1,271 · 1,287 · 1,652 ms |
| Switch to Force (spread) | 2,894 · 5,340 · 5,780 ms | 767 · 1,594 · 1,671 · 1,685 ms |

- **What is left in the longest task is not the layout.** At open it is G6 element creation (2.7 s on 2026-11-12, edges dominate); after a switch it is moving the 4,767 elements to their new positions (~1 s, the same cost Circular and Grid pay). Neither can be sliced without changing G6's render, so both stay.
- **Selection repaint batching was built, measured and dropped.** Repainting a 767-node Select all in batches of 120 cut its longest task 908 → 444 ms but DOUBLED the total main-thread time (1.7 → 3.4 s, Clear the same): each G6 `setElementState` call pays a fixed repaint cost. Not shipped; the 1.3 s state repaint stays (G6 canvas work, as PERF-D5 said).
- Checked live after the change: the Force layout draws the same star-of-clusters shape and the hulls follow it (screenshot pass).

#### N4 the three canvas freezes, measured 2026-11-13 (second lane, nothing shipped)
**How measured:** as above. Two production `la-app` builds of `origin/master` `d6bd3e1e9` (before, :8086) and of it plus the change below (after, :8085), two copies of `la-showcase`, the rebuilt `inspecto-la-api` / `inspecto-http-spi` jars, the built-in browser with the pane shown, `PerformanceObserver` long tasks and long-animation-frame script attribution. Figure = the longest task; every run is listed.

| Action | Before (runs) | After (runs) |
|---|---|---|
| Cold open of the view (longest · second) | 2,920 · 1,896 ms; 1,863 · 987 ms | 1,596 · 924 ms; 1,464 · 771 ms |
| Switch to Circular | 1,539 · 1,227 ms | 1,361 · 1,282 ms |
| Switch to Grid | 1,246 ms | 1,857 ms |
| Switch to Force | 1,032 ms | 896 ms |
| Select all 767 / Clear (before build only) | 1,551 · 1,420 / 1,272 · 989 ms | — |
| Fit to screen (camera only, no element change) | — | 153 + 475 ms frames |

**Tried: move the elements after a layout through G6's `translate` stage** (the path `translateElementTo` and a node drag use; it skips G6's per-element style pass). Wired by wrapping the layout controller's `updateElementPosition`. On the switches, where the move is the whole longest task, it gave **no gain** (the table: within run-to-run spread, Grid worse). The open looks lower, but the cold-open spread is wider than the difference. Not shipped: no proven gain, and it patched a G6 internal.

**Where the time really is.** Long-animation-frame attribution of a switch to Grid: one **1,424 ms `requestAnimationFrame` callback**, that is @antv/g's canvas render of 4,767 dirty display objects, not G6's data or style work (which runs before it, in the click task). A camera-only repaint (Fit to screen) costs 150–475 ms per frame. So all three freezes are the same cost: the canvas renderer syncing and painting every changed shape in one frame.
- **Element creation at first open:** the shapes are constructed in one task and painted in the next frame. Adding them in batches means one `draw()` per batch, and every `draw()` recomputes the default and state styles of EVERY element (`ElementController.computeStyle` with no ids) plus a full repaint. Not built: the per-batch fixed cost is the same mechanism that doubled the total for the selection below.
- **Moving the elements after a layout (~1–1.5 s):** batching the moves across frames would split the rAF, but each frame repaints the whole canvas (150–475 ms measured above), so total time grows with the batch count, and a half-moved graph draws links between old and new positions in the meantime.
- **Painting a 767-node selection (1.0–1.6 s):** batching was built and measured in the first lane: longest task 908 → 444 ms, total 1.7 → 3.4 s. Not shipped.

**What would move it:** a cheaper renderer, not a slice: @antv/g's WebGL renderer (`@antv/g-webgl`, a new dependency) or fewer shapes per element (edge labels and arrows off at this size). Both are product decisions for the operator; neither was built or measured here.

#### N4 WebGL renderer trial, measured 2026-11-13 (third lane, NO-GO, nothing shipped)
**Operator choice:** "Try WebGL renderer". **Built (prototype, reverted):** `@antv/g-webgl@2.1.1` (the release built on the installed `@antv/g-lite` 2.7.0, beside `@antv/g-canvas` 2.2.0 and G6 5.1.1). It was wired through G6's per-layer `renderer` option: the `main` layer (every node, link and label) used WebGL, the other layers stayed on canvas, and a browser without WebGL fell back to canvas. One production `la-app` build had a temporary local switch that forced canvas, so the before and after runs used the same build. The production build passed; it warned that `earcut` (pulled in through `@antv/g-plugin-device-renderer`) is not ESM. The bundle delta was not recorded because nothing ships.

**How measured:** a demo-auth Preview bundle built from `origin/master` `5086db965b` on :8087, with a private copy of `la-showcase`. The built-in browser with the pane shown, real GPU (`ANGLE (Intel UHD Graphics 630, Direct3D11)`), `PerformanceObserver` long tasks, saved view `telecom_fraud_network` (767 nodes / 4,000 links).

| Action | Canvas (before) | WebGL (after) |
|---|---|---|
| Cold open of the view, longest task | 442 ms (one run; this build, this box) | **page frozen > 2.5 min, two runs**; never recovered, the tab had to be reloaded |
| Move after a layout / Select all 767 | not re-measured | not reachable (the open never finished) |
| Small view `mule_large_transfers` (8 nodes / 32 links) | draws | draws the same picture (longest task 136 ms) |

- **Verdict: NO-GO.** On the target size the WebGL renderer does not reduce the freeze; it blocks the main thread far longer than canvas does. It renders correctly on a small graph, so the cost grows with shape count. That is plausibly @antv/g-webgl building a mesh per shape (text and paths) on the main thread, but this was not profiled. So the feature checks (labels, arrows, click, hover, lasso, export) were not run at size. The export path does not depend on the renderer, though: G6's `toDataURL` clones the scene into its own offscreen canvas renderer.
- The canvas figure here (442 ms) is lower than the 1.5–2.9 s from the earlier lanes. That is a different day and box load, a single run and the same view, so it is NOT a new before figure for the plan.
- **Left for the operator:** the other option, "fewer shapes": link labels and arrows off at large sizes. PERF-D5 (no worker) stands.

#### N4 fewer shapes, measured 2026-11-13 (fourth lane, measure only, nothing shipped)
**Operator choice:** measure first, build only on a clear gain (at least 30–40 % off the longest task). **Found first:** link labels are ALREADY off on this view; `edgeLabelsHiddenByDensity` hides them above `DENSE_EDGE_LABEL_CAP`. So the shapes left to cut were the 4,000 arrowheads (`endArrow`) and the 767 node labels.

**How measured:** one production `la-app` build of `origin/master` `cd5a5af6ac` with a temporary `localStorage` switch, removed afterwards: `arrows` set `endArrow: false`, and `both` also turned the node labels off. A demo-auth Preview bundle of the same tree ran on :8087 with a private copy of `la-showcase`. The built-in browser had the pane shown, and `PerformanceObserver` recorded long tasks. Saved view `telecom_fraud_network` (767 nodes / 4,000 links). Each run opened the view from the landing page, switched to Grid, switched to Circular, then pressed Select all and Clear. Modes were alternated (base, arrows, both, base, arrows, both, base, both, arrows, base). Figure = the longest task; every run is listed, and the median is in bold.

| Action | Baseline (4 runs) | Arrows off (3) | Arrows + node labels off (3) | Change, both off |
|---|---|---|---|---|
| Open the view | 3,808 · 1,985 · 1,418 · 2,034 (**~2,010**) | 2,477 · 1,565 · 961 (**1,565**) | 1,050 · 907 · 1,015 (**1,015**) | **−50 %** |
| Move after Grid switch | 1,264 · 864 · 823 · 1,028 (**946**) | 802 · 802 · 537 (**802**) | 556 · 479 · 482 (**482**) | **−49 %** |
| Move after Circular switch | 1,533 · 892 · 700 · 708 (**800**) | 722 · 739 · 480 (**722**) | 570 · 475 · 492 (**492**) | **−39 %** |
| Select all 767 | 2,365 · 1,413 · 1,080 · 1,301 (**1,357**) | 1,983 · 1,324 · 1,137 (**1,324**) | 1,181 · 863 · 869 (**869**) | **−36 %** |
| Clear the selection | 2,866 · 1,455 · 1,044 · 1,829 (**1,642**) | 1,152 · 1,584 · 813 (**1,152**) | 884 · 978 · 830 (**884**) | **−46 %** |

- **Baseline (step 1):** the freezes are still about 0.8–2 s (median), so the row is not "acceptable as is". The 442 ms single run of the WebGL lane was an outlier. The baseline ran first and last too (2,034 ms at open), so the gain is not drift on the box.
- **Arrowheads alone:** a small, uneven gain (about −2 % to −30 %, inside the run-to-run spread on Select all). **Arrowheads plus node labels:** a clear gain on all three freezes, −36 % to −50 %, and the spread of its runs is tight. The node labels are the larger share: text is the most expensive shape @antv/g draws.
- **Verdict: GO for a build.** Above a size threshold, turn off the arrowheads and the node labels, as the existing link-label density rule already does for link labels. Show them again on hover and selection (the same reveal as `revealEdgeLabels`), with a short note on screen and a Display-menu override, like `allEdgeLabels`. Still a decision for the operator: the threshold and whether arrows matter for this analysis. Nothing was built in this lane.
- ⚠ **CORRECTED 2026-11-13 (build lane):** the bullet on node labels above is WRONG. On `telecom_fraud_network` the Display menu's **Node labels is off** (Link Analysis starts labels off, operator 2026-10-15), so the "both" switch changed nothing beyond "arrows": the two modes drew the same shapes. The gap between them was run-to-run noise, and the adjacent runs agree (arrows 3 against both 3: 961 / 537 / 480 / 1,137 / 813 against 1,015 / 482 / 492 / 869 / 830 ms). Read together, the six arrowhead-off runs give medians of open **1,032** ms (−49 %), Grid **546** (−42 %), Circular **531** (−34 %), Select all **1,159** (−15 %), Clear **931** (−43 %). **The gain is the arrowheads.** What node labels cost when an analyst turns them on was NOT measured.

#### N4 large-graph rule, SHIPPED 2026-11-13 (`LA-PERF-N4-1` closed)
**Built** (`graph-view.component.ts`, `link-analysis.component.*`): above **`DENSE_DETAIL_CAP` = 1,000 links**, `detailHiddenByDensity` hides every arrowhead (`endArrow`) and node label. The 1,000 is the assistant's pick, because the operator named no threshold: a 1,000-link graph still draws everything. A hover or click shows them again through a second reveal state, `detailed`, beside the link-label rule's `labelled`. `revealTargets` returns `state|id` keys. Hovering a node reveals its label, the arrowheads of its links and the labels of their ends; hovering a link reveals that link and its two ends. A click pins the reveal, and a node-label switch that is off stays off. The Display menu has "Arrows and node labels on large graphs" (`display.allDetail`, saved with the view), and the render strip says "arrows and node labels on hover above 1,000 links" while the rule is active. Select all does NOT reveal labels (it would bring back the repaint cost); the selected nodes keep only their ring.

**Measured on the shipped build** (demo bundle of `04860a7366` plus this change, :8087, pane shown): open **1,206** ms (one run), and the note shows. With the override ticked, a Grid switch costs **967** ms against 946 for the baseline median, so the override restores the arrowheads and their cost. Three further runs on the shipped build were lost when the pane was hidden. On this view the shipped build draws exactly what the "arrows" and "both" runs above drew, so those six runs are its numbers.

**Not checked live:** that a hover shows the hidden detail at this size. On the auto-fitted 767-node canvas a single node is a pixel or two, and the pane kept resizing. The reveal reuses the link-label rule's G6 state mechanism, and `revealTargets` is covered by unit specs.

**Checks:** `npx ng test` exit 0 (683 files, 7,569 passed, 6 skipped); the three tsconfigs type-check; the production `la-app` build passes, with a bundle delta of **+1,698 bytes** (30,360,279 → 30,361,977, uncompressed `dist/la-app/browser`). Targeted UI tests only; the full gate was NOT run (operator rule).

**Tier 2 — load and startup**
- **N5 Landing makes ~27 calls with every Space's `/bootstrap` fetched twice (~1 MB decoded).** Dedupe (a request-level cache for GETs, the same mechanism as P10a-2) and fetch only the current Space's bootstrap; `/spaces`, `/spaces/_meta`, the components lists and `/entity-lists` each ×2 too. Cheap, client-only, high yield. ✅ **SHIPPED 2026-10-25** (verified PASS; targeted tests only): the landing page fetches each Space call once through short-lived reads (`short-reads.ts`), and every Entity List / Dataset / component / access write clears them. `10b5776b7`, `0a6420e2b`, `ddfa3d81a`.
- **N6 Hashed JS/CSS/font chunks are revalidated on every load (19 × `304`).** Serve content-hashed files with `Cache-Control: public, max-age=31536000, immutable`; negligible on localhost, 19 round trips on a real network. ✅ **SHIPPED 2026-11-12** (targeted tests only): `<stem>-<hash>.js|css|woff2` is `public, max-age=31536000, immutable`; index.html and plain names keep `no-cache` + ETag (`ControlApiStaticAndCorsTest`). `732528b7c`.

**Tier 3 — server fixed overhead (MEASURE FIRST: profile before building; code-read was wrong 6 of 8 times)**
- **N7 A fixed ~80 ms per LA request** (Working Set and the Investigation list at 94–95 ms with one Investigation, against 15 ms for `/health`), and **N8 a 304 that costs as much as a 200** (projection 270–290 vs 303 ms; `/bootstrap` 21.8 vs 25.3 ms): the 304 still pays the key computation and gates. Profile with a sampling run (jcmd `Thread.print` ×N or JFR if the runtime has it) under a request loop to find what the fixed cost is (suspects: settings/limits read per request, audit append, capability/ABAC, fingerprint listing in the cache key). ✅ **SHIPPED 2026-11-12 (LA-PERF-SERVER-1), N7 and N8 together:** profiled, not code-read — the Investigation list spent 80 % of its time re-parsing every pipeline file for the masking lineage, the projection 304 spent ~50 % re-scanning the registry; a stamped (no time-to-live) cache cut the list 174 → 32 ms p50 and the projection 209 → 52 ms. Settings / ABAC / fingerprint listing showed no cost. Evidence: `docs/okf/product/module/la-api/spec-server-profile.md`. Hash: `git log --grep LA-PERF-SERVER-1`.
- **N9 Projection latency 146 ms → 20.6 s, unexplained.** Repeat with a server-side timer and the scheduled jobs' minutes ruled out (jobs are ≤ 1.5 s so they are not the cause); suspects: lazy first-open of a DuckDB file, native-thread oversubscription (N10), the rate limiter's queueing. 🟡 **PARTLY EXPLAINED 2026-11-12:** rate-limiter queueing REFUTED (it answers 429, never waits); the median was the N7 cost; one 15.99 s call in 60 is still unexplained; audit append (journal write 36 %, parquet flush 24 % of native samples, on the request thread under the event store lock) is a proven cost left unfixed because the per-event sync is the audit durability rule. Evidence file above. 🟡 **TAIL RE-MEASURED 2026-11-12 after N10** (`LA-PERF-N9-1`): 220 projections in four runs; one call took **10.3 s**, in the first run, which started at the first 10-minute cron burst after boot (about 20 jobs, two `la_index_build`, and `system.freshness-sweep` taking **11.4 s**). Every other call was ≤ 2.2 s, including a 40-call run straddling a later burst (the same jobs; that sweep took 16 ms; max 2.2 s). So the tail follows the FIRST job burst after a boot, not a steady cost; N10 did not remove it. Not caught in a JFR sample: request threads are virtual and showed no Java wait over 2 s, so the time was native (DuckDB) or CPU contention. No per-stage timer was added. **Next step if it matters:** time the first post-boot `system.freshness-sweep` (11.4 s cold against 16 ms warm). **Audit append on the request thread: decision for the operator, nothing changed.** Measured: journal write 36 % and parquet flush 24 % of the projection's native samples, under the event store lock. Options: (a) keep it (durability per event, the current rule); (b) group commit: a writer thread forces once every N ms and the request waits for its batch's force (same durability, less lock time under concurrency, same latency for one caller); (c) journal-only on the request thread with the parquet flush moved off it (the journal keeps it durable; the flush is a compaction); (d) fully async append (fastest; an event can be lost on a crash, so this breaks the rule). (c) looks like the smallest change that keeps the rule. It needs a design review of the replay path. ✅ **DECIDED 2026-11-13 (operator interview):** build option (c); accept the post-boot tail as a cold-start cost (not chased). Board row `LA-PERF-N9-1` now tracks building (c). ✅ **BUILT 2026-11-13 — lock-free flush, not plain (c).** Plain (c), with the flush on a background thread but under the store lock, was built and measured first and gave **no gain**: store-level p99 was 140–210 ms before and 140–240 ms after, because the ~150 ms flush still held the lock and the next append waited. The operator then chose the segment design. The journal is rotated into `pending.seg-<n>.jsonl` under the lock, the batch is written outside the lock on a second DuckDB connection into `.staging/flush-<n>/`, and the files move into place under the lock. Replay reads segments as well as the journal. **Profile** (`ParquetEventStore.append`, AUDIT events, journal forced, 50-event threshold, three runs each): back-to-back, p99 **153–245 ms → 6.4–7.3 ms** and max 248–480 → 22–45 ms; one append every 20 ms, p99 **155–191 ms → 5.7–6.9 ms**. The p50 of about 2.6 ms is the per-event journal force, kept by the durability rule. This is a store-level measure, not a re-run of the HTTP projection profile. Tests: `anAuditEventIsInTheJournalFileWhenAppendReturns`, `aKillBeforeTheBackgroundFlushRunsLosesNoEvent`, `aKillAfterTheJournalRotatedReplaysTheSegment` and `aBackgroundFlushLandsEveryEventOnce`, each falsified (async journal: 7 red; segments not replayed: red). As built: `okf/product/module/event/architecture.md` §9.
- **N10 About 3,550 native threads and 1.4 GB at idle.** Cap DuckDB `threads` and `memory_limit` per instance (the first review: `DuckDbUtil.applyDuckDbSettings` leaves them at defaults when unset); prove with a before/after thread count and a projection timing. ✅ **SHIPPED 2026-11-12** (`LA-PERF-N10-1`, targeted tests only): the cause was NOT `threads` or `memory_limit` but DuckDB 2.0's async I/O pool, `async_threads` = 4 × cores (48 here) per database instance, idle. A start-address dump of the native threads put 5,251 of 5,338 in `libduckdb_java` (5,332 at the probe a minute later); 89 open DuckDB instances × 59 threads. `DuckDbUtil.applyInstanceCaps` sets `async_threads=1` on every open path (`applyCorrectnessSettings` and `JdbcDrivers.connect` for the per-Space stores), override `-Dprocessing.duckdb.async_threads`. Same bundle, only `inspecto-util.jar` swapped: threads **5,332 → 1,158**, working set **1,844 → 1,483 MB**, projection p50 **579 → 450 ms** (three runs of 60: 450 / 392 / 455). `threads` alone saves 8-11 per instance and costs query parallelism, so it stays at DuckDB's default; `memory_limit` is a cap, not idle use, and the idle working set did not point at it — left unchanged. Evidence: `okf/product/module/la-api/spec-server-profile.md` § N10.
- **N11 Boot 3 min 35 s (185 s before the first log line).** Re-measure on a warm restart; then trace what runs before logging. ✅ **MEASURED, COLD-ONLY 2026-11-12** (`LA-PERF-N11-1`): three warm restarts of the same install (9 Spaces) reached the first application log line 9–11 s after launch and `/health` in 37, 55 and 83 s (the spread follows load from parallel Maven builds on the box). The 185 s before the first log line happened once, right after a fresh `/MIR` swap of every jar: a cold-file cost (first read of new jars and the jlinked runtime, likely including an on-access scan), not code. Nothing to fix.

**Tier 4 — defects and small fixes**
- **N12 `renderMinimap` TypeError after route changes (×4).** Guard the destroyed-graph case. ✅ **SHIPPED 2026-10-25** (builder + verifier follow-up; targeted tests only): the graph tears down without a late minimap render and survives a throwing minimap silencer. `c47966312`, `3b5efb0c2`.
- **N13 `429` without `Retry-After`,** and no client-side handling: add the header and let the client back off. ✅ **SHIPPED 2026-11-12** (targeted tests only): every 429 carries `Retry-After` (seconds to the bucket's next token); the LA projection and neighbour reads wait it out once (at most 10 s), then the refusal stands. `732528b7c`.
- **N14 The "node cap" banner appears at 465–767 nodes** because the 4,000-ROW cap binds first: say which cap bit. ✅ **SHIPPED 2026-11-12** (targeted tests only): the *Result cut short* notice names the node cap when the graph holds that many nodes, else the server's row limit (`truncationNotice`). `732528b7c`.

**Standing results.** Refuted at demo scale: H1 idle polling, H5 page leak (canvas/DOM), H7 worst-algorithm, P5's premise that betweenness is the top cost. Unmeasured (need bigger data or a map drive): H1/H2 geo, P2-1/P2-2 scaling, P2-3/P2-4 geo limit (consistent with the limit applied after the scan: 182 vs 177 ms for limit 100 vs 5,000 on 449 points), heap after a forced GC, shortest path, Grow.

---

## 0. How to read this

Items are ordered by **expected gain × confidence ÷ risk**, not by size. Each item names its proof, so a fix is
not "done" until the proof is green on the same measured scenario. Nothing in P1 or later starts until P0 has
confirmed the ranking — the browser estimates are from code, and the repo's only measured browser number
(betweenness) is a single algorithm.

**Scenarios used for every measurement** (fixed so before/after compare):
- **S-small:** a showcase Space, a result of about 500 nodes (the default projection cap).
- **S-large:** a result near the analysis cap (about 2,000 nodes), from a large Dataset (IMS-scale or a 10⁷-edge bench).
- **S-expand:** one expand from a seed on S-large, then a selection change.

---

## 1. P0 — measure first (gate for everything else)

| Id | Work | Proof / exit |
|---|---|---|
| PERF-P0-1 | Chrome performance trace of S-small and S-large: fetch, fold, layout, paint, scripting, GC, per action (load, expand, select, hover, filter, run betweenness) | A table of ms per stage per action, saved under `docs/superpower/` as an evidence file |
| PERF-P0-2 | Production build of `inspecto-ui`; check what lands in the **main** chunk (look for `@antv/g6` and `maplibre-gl`); compare against `angular.json` budgets (2.58 / 2.65 MB) | Main-chunk size and the list of heavy libraries found in it |
| PERF-P0-3 | Server timings for `/inv/projection`, `/inv/projection/neighbors`, `/inv/investigations/{id}/expand`, and the Working Set GET, on S-small and S-large, cold and warm | p50 / p95 table and response size in bytes |
| PERF-P0-4 | Time **every** centrality and algorithm option the toolbox offers, not only the default (the docs record one per-option cliff already) | A per-option ms table at 500 / 1,000 / 2,000 |

**Gate:** if P0-1 shows the browser spends most time in scripting on selection, do P1 before P2. If P0-3 shows
the server is under 100 ms p95, move server items down the list.

---

## 2. Priority list

Gain is an estimate from code reading, to be replaced by P0 numbers. Confidence = how sure the cost driver is real.

### P1 — browser: selection and emphasis repaint (high gain, low risk to data)

**Why:** a selection or hover change calls `setData` plus `draw()` on the whole graph, which re-runs every node and
edge style mapper (`inspecto-ui/src/app/inspecto/graph/graph-view.component.ts` — `redraw`, around the `setData`
call). The code already has per-element `syncSelectionState` for the same purpose.

| Id | Work | Proof |
|---|---|---|
**Traced 2026-10-24 — the multi-select drag path (`inspecto-ui/src/app/inspecto/graph/graph-view.component.ts`):**
1. ⚠ **Corrected 2026-10-24 (lane A and the verifier):** G6 `brush-select` defaults to `immediately: false`, so
   `onSelect` fires ONCE, on release — not on every pointer move as first traced. Live per-move feedback exists
   only if the host enables `immediately`. The per-move cost the operator saw therefore came from step 3 (the full
   redraw per `selection` change, e.g. a hide or a hover-driven change), not from per-move events. Frame
   coalescing (`buildBehaviors` callback, around line 1078) is a safeguard.
2. The page sets `selection` on every emission. `ngOnChanges` runs `styleKey()`, which `stableKey`-stringifies the
   whole selection array (line ~805), so the key changes on every move.
3. `redraw` (line ~858) then calls `graph.setData(this.data)` and `draw()`. That re-runs every node and edge mapper,
   labels, badges and icons, for the whole graph, on every pointer move.
4. The halo and thicker outline are painted by mappers that read `selSet` (lines ~990–1004), so G6's own `selected`
   state, which the brush already sets, is duplicated at full cost.
5. `syncSelectionState` (line ~879) already diffs and calls `setElementState` for just the changed ids, but it runs
   only after the full redraw.

**Fix shape (proposed, not built):** (a) selection never calls `redraw`: move the halo and outline into a G6 `selected`
state style, and apply only the diff through `setElementState`; (b) remove `selection` from `styleKey`; (c) coalesce
`selectionChange` to one emission per animation frame during a drag, plus one final emission on release; (d) check
the parent (`link-analysis.component.ts`) for work that re-runs on each `selection` change (for example the Working
Set and the selection-bound computeds), and keep that work off the drag path.

| Id | Work | Proof |
|---|---|---|
| PERF-P1-1 | Route selection and emphasis through per-element state updates (`syncSelectionState`); keep `redraw` for real style changes only | P0-1 trace: scripting per selection drops; a vitest that counts mapper calls per selection change (before ≫ after) |
| PERF-P1-2 | Remove the `selection` and `emphasis` arrays from `styleKey` so they stop forcing a full style pass | Unit test: a selection change does not change `styleKey` |

**Expected gain:** largest interactive win. **Risk:** the documented G6 repaint trap — the visual proof must
include a box-select and a hover on S-large, screenshotted before and after.

### P2 — browser: whole-graph string keys (high gain, low risk)

**Why:** each data change runs `JSON.stringify` over the entire graph two or three times to decide what changed
(`graph-view.component.ts` — `stableKey`, `ngOnChanges`, `snapshotKeys`, `applyData`). At 2,000 nodes that's
roughly 1–3 MB of string per change.

| Id | Work | Proof |
|---|---|---|
| PERF-P2-1 | Replace the full-graph key with a cheap signature: counts plus a host-supplied version counter, bumped on every real change to nodes, edges or their attributes | Test: the signature changes whenever content changes, and is stable when it does not (mutation-checked: change one attribute, signature must move) |
| PERF-P2-2 | Compute the signature once per data change and pass it through `applyData` (no second stringify) | P0-1: one fewer large string per change |

**Risk:** a signature that misses a change shows stale data. The mutation test is the guard.

### P3 — server: stop re-scanning per request (high gain, medium risk)

**Why:** `/inv/projection`, `/neighbors` and `expand` each run a full Dataset scan, group-by and sort, and only
then apply the limit (`inspecto-la-api/.../InvRoutes.java` — `edgeSql`, around the projection query). The edge
index that prunes by bucket is off by default (`IndexedRead.java:143`), and the full projection never uses it.

| Id | Work | Proof |
|---|---|---|
| PERF-P3-1 | Enable the edge index for neighbors and expand on indexed Datasets; keep the exact fallback | Real-HTTP test: same results with index on and off (parity), and P0-3 p95 drops on S-large |
| PERF-P3-2 | Push the limit into the projection query (top-N) without changing the truncation probe semantics | Test: `truncated` flag and row count identical before and after on S-small and S-large |
| PERF-P3-3 | Count query skipped when four-eyes fan-out is not configured (the second `COUNT(*)` scan) | Test + P0-3: one fewer scan per neighbors call |

**Risk:** index freshness. A stale bucket must fall back, not answer. The parity test covers it.

### P3a — server: Grow preview rescans (MEASURED 2026-10-25 — NO-GO, nothing built)

The earlier read claimed the Grow preview's SQL (`neighboursPreview` in `la/inspecto-la-api/.../InvRoutes.java`, a CTE with `pairs`, `cand`, `nb`, `deg`, `ig`) scans the relation several times. It uses the `pairs` CTE many times (in `cand`, in the `reciprocal` EXISTS, twice in the `deg` UNION ALL; `cand` is read again by `nb` and `ig`), but **DuckDB 2.0.0.0 materialises a CTE used more than once without being told to**: the plan has one `CTE Name: pairs` node over a single `READ_PARQUET` of 1.2 M rows, and every later use is a `CTE Scan`. So `AS MATERIALIZED` would remove no reads. Measured on a laptop (12 cores, in-process DuckDB 2.0.0.0, a stand-alone harness, 1.2 M skewed edges — not a production number): seed 1 or 20 with either direction is p50 about 450–500 ms (1 Parquet read); with a drawn filter 215–240 ms; reciprocal 140 ms. The time is the ONE read plus the `GROUP BY` that builds the full distinct `pairs` over all 1.2 M rows BEFORE the frontier filter applies. **The only real saving is a semantic rewrite** — narrowing the scan to rows that touch the frontier or its neighbours — which changes the query's shape and needs its own decision and a parity test; it is not a tuning pass. Not verified: the stand-alone harness used a view over `read_parquet`, not the HTTP route or `DatasetProviders.require().run`, so how the real provider wraps `relationSql` was not checked (one read is the most it could be). Harness and data: scratchpad `GrowBench.java` and `edges.parquet` of the 2026-10-25 session.

### P4 — server: caching and response size (high gain, low risk)

| Id | Work | Proof |
|---|---|---|
| PERF-P4-1 | ⚠ **Corrected 2026-10-24:** gzip ALREADY existed — `ApiContext.respondJson` in `spi/inspecto-http-spi` gzips bodies over 1 KB when the client sends `Accept-Encoding` (the server read that said "no gzip anywhere" was wrong; it searched `inspecto/src/main` and `platform/*` only). Streams and downloads write straight to the exchange and bypass it. Lane C (`la-perf-c`) HARDENED it instead: real `Accept-Encoding` q-value parsing (`gzip;q=0` means none), `Vary: Accept-Encoding`, a strong ETag becomes weak on the gzipped variant, `respondText` negotiated too, `-Dcontrol.gzip=false`. | Test: `Content-Encoding: gzip` when asked, identical JSON after decode; a synthetic 200-node payload shrank 11,798 → 1,270 bytes (about 89%) — not a real projection payload |
| PERF-P4-2 | Projection ETag from the Dataset input fingerprint plus a body hash; answer `304` on a match. Masking and R3 gating run **before** the cache lookup | Test: second identical request returns 304; a changed Dataset invalidates; a viewer without access still gets 403/404 |
| PERF-P4-3 | Working Set GET: ETag from `logToken` (not a re-read and SHA-256 of the whole log) | Test: unchanged log → 304 and no log read (instrumented); append → new ETag |

### P5 — browser: the known centrality hot spot (medium gain, needs a decision)

**Why:** betweenness is Brandes, O(V·E), synchronous on the main thread — 425 ms at 500 nodes, 9.8 s at 2,000
(measured, `graph-analysis.ts` comments). This is exactly the case the roadmap says to reopen LA-07 for: "reopen only
if an analyst reports the ~425 ms as a felt freeze".

| Id | Work | Proof |
|---|---|---|
| PERF-P5-1 | **Operator decision first:** reopen LA-07 (Web Worker for betweenness) or approximate it (pivot sampling, with the approximation stated on screen) | Decision recorded in the roadmap |
| PERF-P5-2 | If reopened: run betweenness in a worker with progress and cancel; results byte-identical to the main-thread version on the parity fixture | Parity test; no main-thread block over 50 ms in the P0-1 trace |

**As built (`LA-PERF-P5-1`, 2026-11-13, operator ask; P5-1 answered "approximate", P5-2 stays refused with PERF-D5).**
`betweennessCentrality` (`graph-analysis.ts`) is exact up to `browser_algorithms.betweennessCentrality` (500). Above
it, it runs the same Brandes pass from k = ⌊cap² / n⌋ source nodes only (Brandes–Pich pivot sampling), scaled by n / k,
so the work stays about that of an exact run at the cap. The pivots come from a fixed seed: the same graph gives the
same answer on every run. It refuses only above `max(analysis_node_cap, its own item)` (2,500), and the toolbox hands
it to the server only above that. Under the ranking the toolbox says "Approximate: estimated from k of n nodes, so close
scores may swap places." Suspicion score still runs exact betweenness under its own cap.

Measured on la-showcase `telecom_fraud_network` (the projection's 4,000 rows, ordered by source then target: 767 nodes,
4,000 links, rebuilt from the Preview install's `telecom_links` Parquet), in-page in the built-in browser with the
shipped algorithm copied verbatim, 5 alternating runs each:

| | before (exact, was refused in the app) | after (sampled, k = 325) |
|---|---|---|
| main-thread time | 875–1,285 ms (781–849 ms in a first run of 5) | 423–595 ms |

Ranking against exact (Node, the shipped seed; the 1,458-node graph is the first 8,000 rows of the same Dataset — the
other showcase graphs are under 500 nodes and stay exact):

| graph | k | top node | top-10 overlap | top-20 overlap | sampled top 10 in exact top 20 | Spearman (all nodes) |
|---|---|---|---|---|---|---|
| 767 nodes / 4,000 links | 325 | same | 0.8 | 0.75 | 0.9 | 0.985 |
| 1,458 nodes / 8,000 links | 171 | same | 0.5 | 0.4 | 0.6 | 0.962 |

Over 10 other seeds the top node matched every time; at 767 nodes Spearman stayed 0.98–0.99 for k = 300.
⚠ **Where it is weak:** after the supernode the exact scores sit close together (8,645 … 3,691 for ranks 2–25 at 767
nodes), so places 2–20 swap; the larger the graph, the fewer pivots and the more they swap. Hence the on-screen note.
For an exact answer above 500 nodes, raise `browser_algorithms.betweennessCentrality`, or run it on the
Whole Dataset tab.

### P6 — browser: layout on expand (medium gain, medium risk)

**Why:** `applyData` seeds every node's position one call at a time, and adding any node triggers a full
`graph.render()` re-layout (`graph-view.component.ts` — `applyData`, `seedPosition`, the `render()` call).

| Id | Work | Proof |
|---|---|---|
| PERF-P6-1 | Position only the new nodes from their neighbours; re-layout only when the new set is large (threshold stated in code) | P0-1: expand scripting drops on S-expand; positions of existing nodes unchanged (test) |

✅ **SHIPPED 2026-11-12 (`perf(link-analysis)`, hash in the board's closing note; targeted spec only).** `applyData` in
`graph-view.component.ts` now gives each new node a position beside its drawn neighbours (`placeBesideNeighbours`: their
centre plus a golden-angle spiral offset, new-to-new chains in later passes, the caller's data never written) and calls
`graph.draw()`, so no existing node moves. The whole layout re-runs (`graph.render()`) only for more than
`EXPAND_RELAYOUT_MAX_NEW` = 50 new nodes or a new node with no drawn neighbour. Proof: three vitest specs in
`graph-view.component.spec.ts` (existing positions unchanged and no layout on a small expand; 51 new nodes re-lay out;
siblings of one hub do not stack).

*Measured BEFORE* (production `la-app` build of `fbac92982`, demo-auth Preview bundle on :8083 over a copy of `la-showcase`,
built-in browser pane, in-page `PerformanceObserver` long tasks — the §0b method): an Investigation from `99979100001`
grown to about 405 nodes; expands adding 2, 5 and 3 nodes cost **378, 379 and 435 ms** of long tasks (longest 236–283 ms),
and the whole graph visibly moved after each. *AFTER:* **NOT measured** — the browser pane was hidden for the rest of the
session (a hidden pane runs as a background page, so its timings are not comparable), no Claude-in-Chrome or Playwright
was available, and the operator chose to ship on the vitest proof. The before/after box-select + hover screenshot was
NOT taken either. Re-measure on the next visible-pane session with the same method.

### P7 — browser: bundle (unknown gain until P0-2; low risk if confirmed)

**Why:** a static import chain may put the graph library into the eager `main` chunk on every page load
(`app.config.ts` → `la-host.providers.ts` → `catalog/registry.component` → `graph-view.component`). This is
unconfirmed; `public-api.ts` says the opposite.

| Id | Work | Proof |
|---|---|---|
| PERF-P7-1 | If P0-2 confirms it: move the registry constants out of the component import, or load them lazily | Main chunk shrinks by the library's size; the Catalog and LA routes still render (vitest + preview) |

### P8 — browser: community hulls (low–medium gain, low risk)

| Id | Work | Proof |
|---|---|---|
| PERF-P8-1 | Debounce Louvain hulls, or key them on a structure signature so they rerun only when the community structure changes | P0-1: no Louvain run on a selection-only change |

### P9 — server: log and evaluator cost (medium gain at long sessions, larger risk)

✅ **MEASURED 2026-10-25 — NO-GO, nothing built.** A throwaway benchmark on the real `FsInvestigationStore` (a seed plus a repeating expand/exclude/hide/keep/window mix) found: at N = 1,000 ops (about 2,500 entities) read+parse is 3–9 ms, `evaluate` 7–19 ms and the state hash 5–16 ms, warm; at N = 200 it is about 1 ms each. So evaluate + hash is about 13–35 ms at 1,000 ops, under the 50 ms line set for building a checkpoint, and growth is linear, not flat. One finding the original claim missed: `stateBefore()` (InvestigationRoutes ~2927) also does a full `evaluate` on the main log, so a commit costs read+parse, TWO folds and one hash — about 20–55 ms at 1,000 ops, near the line. A checkpoint could only remove the folds (about 2 × evaluate, roughly 15–40 ms): the hash covers the whole current state and an undo still re-folds the prefix. **If this is revisited:** the cheaper first step is to reuse the `stateBefore` fold for the "after" state by applying only the new op to a copy — no prefix-identity validation needed, not measured. Not run: the full op commit over HTTP, the Postgres store. The benchmark is kept at `scratchpad/P9BenchTmpTest.java` of the 2026-10-25 session (drop into `la/inspecto-la-core/src/test/java/com/gamma/la/core/` and run with `-Dtest=P9BenchTmpTest`).

**Why:** every expand re-folds the whole main log (`InvestigationRoutes` — `evaluate(next, -1, null)`); drafts have
checkpoints, the main log does not. Cost grows with the number of steps.

| Id | Work | Proof |
|---|---|---|
| PERF-P9-1 | Main-log checkpoint keyed by `logToken`, reusing the `DraftCheckpoints` pattern; undo re-folds from the checkpoint | Test: result identical with and without checkpoints over a random op sequence; expand time flat as ops grow (P0-3) |

### P10 — server: wire format (medium gain, API change)

| Id | Work | Proof |
|---|---|---|
| PERF-P10-1 | Send column names once and rows as arrays, or stream via a generator; cache `columnTypes` per Dataset fingerprint; skip the mask copy when masking is off | Versioned API change (new media type or `v2` path, per the no-compat rule); P0-3 bytes and GC drop |

**✅ SHIPPED 2026-11-12 (PERF-D3 answered "let's take on D3").** As built:
- **Routes (re-grounded).** `POST /inv/projection` and `POST /inv/projection/neighbors` (`InvRoutes.project`, both the index
  and the flat branch). "Investigation expand" is NOT an edge-row response: `POST /inv/investigations/{id}/ops` answers the
  appended log entry, and the graph comes from the Working Set / replay, so it is unchanged. Also unchanged: the neighbours
  `list: true` page, `/inv/projection/multi` and the Grow preview.
- **Shape.** `{masking, columns, attrCols, rows, truncated, columnTypes, source?}`: `columns` = `source, target, kind, count`,
  then `total` when a Measure is read, then the `attrCols`; each row is an array in that order. It REPLACED the object rows
  (no-shim rule); nothing serves the old shape.
- **Versioning: a media type, `application/vnd.inspecto.la-edges.v2+json`, not a `v2` path** (session decision). Every
  path-keyed table stays as it is: the rate classes, the capability manifest, OpenAPI, the absent-module stubs, the
  projection cache's route key and the SPA's conditional-read key. With no dual serving, a new path would only rename. To carry
  it, `ApiContext.respondJson` now keeps a `…+json` type the handler set on a success; any error is plain
  `application/json` and carries no ETag, as before.
  The type is set on the three success returns only. Known low gap: a request replayed by `Idempotency-Key` (`Idempotency.replay`)
  is sent as `application/json`; the SPA never sends that key on these reads. The decoder counts columns (`5 + attrCols` means a
  `total` is present), so an attribute column named `total` stays an attribute (verification finding, fixed).
- **Mask copy.** Each row is built once as an array straight from the statement row (`InvRoutes.edgeArrays`); the two ends are
  masked in place only when the read masks. The old path built a `LinkedHashMap` (and an `attrs` map) per row.
- **`columnTypes` cache: NOT built** (session decision). On a ProjectionCache hit it is not computed at all (the cached body
  holds it); on a miss it is one registry lookup beside a DuckDB `GROUP BY`. With no measured cost, caching it would add a
  key and an invalidation rule for nothing.
- **Masking, row scope, cache, 304.** Unchanged order: every gate and the masking run before the ProjectionCache lookup; the
  cached body is the compact body; ETag/304 (P4-2) and the client's revalidation (P10a-2) work as before. The SPA decodes once
  in `edge-wire.ts` (`decodeEdges`) BEFORE the conditional-read cache holds the body, so a 304 returns the same decoded object
  and the cache key stays viewer-scoped. The cache budget therefore still counts decoded JSON.
- **Tests.** `ControlApiEdgeWireFormatTest` (real HTTP, armed: shape and media type on both routes, masking off and on, the
  304 path, the R3 404 as plain JSON with no ETag, 401); `EdgeWireParityTest` (decoded compact rows equal the old rows,
  masking off and on, with and without a Measure and attribute columns); `edge-wire.spec.ts` and
  `inv-conditional-read.spec.ts` (vitest). The 28 geo-link tests that read projection rows decode through
  `EdgeWireTestRows`. Targeted runs only; the full gate was NOT run.
- **Error path pinned (2026-11-13).** `aReadThatFailsAfterTheCacheLookupIsPlainJsonWithNoETag` fails the read itself (a
  Measure column the Dataset lacks → 422) AFTER the cache lookup on both routes and asserts plain JSON with no ETag. The two
  guards (v2 type set only on success returns; `respondJson` forces `application/json` at status >= 400) are redundant
  layers: removing both turns it red on each route; removing either alone keeps it green, by design. Commit
  `test(la-api): LA-PERF-P10-1 pin the plain-JSON error path after the cache lookup`.

**Measured (2026-11-12, la-showcase `telecom_links_dataset`, the 767-node view: `a_msisdn` → `b_msisdn`, kind `link_kind`,
attribute `via`, limit 4,000 minus a varied 1–9 so every timed call misses the cache; medians of 9).** Before = the
installed Preview build; after = the same install with this lane's `inspecto-la-api` and `inspecto-http-spi` jars swapped
in (the install's other jars are older; probe script `la-perf-wire-probe.mjs` in the session notes, not in the repo).

| Scenario | Rows | Bytes decoded before → after | Bytes gzip before → after | Server ms, cache miss | Server ms, cache hit | V8 parse (+ decode) ms |
|---|---|---|---|---|---|---|
| Projection | 3,991 | 477,874 → 282,387 (**−41 %**) | 27,841 → 26,009 (**−6.6 %**) | 678 → 620 | 390 → 423 | 5.2 → 5.5 |
| Neighbours of the hub 99970000100 | 730 | 87,190 → 51,489 (−41 %) | 8,951 → 8,785 (−1.9 %) | 601 → 760 | 460 → 590 | 1.0 → 0.8 |
| Neighbours of the suspect 99979100001 | 403 | 48,666 → 28,989 (−40 %) | 5,452 → 5,398 (−1.0 %) | 599 → 661 | 472 → 761 | 0.6 → 0.5 |

Server serialisation alone (Jackson, in-process, 4,000 rows with one attribute column, warm, median of 25;
`EdgeWireParityTest.serialisationCostOfBothShapes`): **13.7 ms → 4.9 ms**, 356 KB → 160 KB (the fixture ids are shorter than the showcase's). Real, but 9 ms against a ~400 ms per-request floor.

**Verdict, plainly: the gain on the wire is negligible.** gzip already removed the repetition the compact shape removes, so
the bytes a browser downloads fall by 1–7 % (1.8 KB on the largest graph). Server time is unchanged within noise: even a
cache HIT costs about 400 ms on this install, a per-request floor that has nothing to do with the body shape (see N9 and
`spec-server-profile`). Parse plus decode in V8 is the same (about 5.5 ms for 3,991 rows), because the client still builds
one object per row for the folds. The real gains are the uncompressed size (−41 %, which matters only where gzip is not
negotiated) and one fewer map per row on the server. Not measured: browser heap and GC (the Node heap probe was not
reliable, so no figure is claimed), a browser performance trace, and the fold time, which this change does not touch
(the folds read the same decoded objects).

### P10a — browser: smart data fetch (high gain for repeated work; depends on P4)

**Principle (operator, 2026-10-24):** fetch from the server only when the server's answer can have changed or
the analyst asked for something new. Everything else is served from the client.

**What is there today (grounded 2026-10-24, `inspecto-ui/projects/link-analysis/src/link-analysis/link-analysis.component.ts`):**
- `execute()` (line ~2078) is the one fetch path for a graph. Its callers are: opening a Dataset (line ~1669), running
  the query (line ~1975) and reopening a saved view (line ~3601). `pushFilter()` (line ~2172) is a second, explicit path.
- Each caller calls `source.query(q)` directly. No result is kept on the client, and an identical request is
  re-sent every time.
- Every expand goes back to the server for the full Working Set rather than only what changed (server report, item 7).
- Local-only actions (hide, colour, layout, selection) must not fetch. This is not yet proved; the trace in P0 should confirm it.

**Items:**

| Id | Work | Proof |
|---|---|---|
| PERF-P10a-1 | Client result cache keyed on (source, normalised query, Dataset fingerprint); an identical re-run is served from the cache and makes no request | Test: second identical `execute` makes zero HTTP calls (HttpTestingController); a different filter makes one |
| PERF-P10a-2 | Revalidate, don't refetch: send `If-None-Match` with the stored ETag (P4-1/P4-2) and reuse the body on `304` | Test: `304` path reuses the cached graph object by reference and changes nothing downstream |
| PERF-P10a-3 | In-flight de-duplication: concurrent identical requests (double click, a view reopened while loading) share one promise | Test: two overlapping calls produce one request |
| PERF-P10a-4 | Debounce `pushFilter` and treat "Apply locally" as local (no request) | Test: a burst of filter edits produces one request after the pause; local apply makes none |
| PERF-P10a-5 | Expand and Grow fetch only the delta (new nodes and edges since the last `at`), merged into the existing graph; this depends on the server delta response (server item 7) | Test: an expand's request body asks for the delta; the merged graph equals a full refetch (parity test) |
| PERF-P10a-6 | Audit every action for hidden fetches: none of hide, colour, layout, selection, legend or minimap may call the server | Trace per action (P0-1): zero network requests for the local set |

❌ **P10a-5 measured, NO-GO, 2026-11-13 (operator: "Close as no-go"; board row `LA-PERF-P10A5-1` closed; nothing shipped).**
A lane built the delta, its targeted tests passed, and the code was discarded after this measurement:
- **What was tried.** `POST /inv/projection/neighbors` took an optional `known: [[source, target, kind, attr...]]` (the
  links the caller holds, as an earlier answer showed them) and dropped the matching rows AFTER masking, so a delta was always
  a subset of that viewer's full answer and a raw id matched nothing; the body-hashing cache key kept deltas apart (PERF-D2).
  Expand / Grow sent each held link of the node (only when its row folds back to that link's id) and fell back to a full read
  on any doubt (more left out than named, or the node cap could bind).
- **Measured (Preview install :8080, `la-showcase` `telecom_links_dataset`, after a 4,000-row projection; medians of 9;
  each call misses the cache).** Hub `99970000100` (257 of its 730 links held): rows 730 → 473, response 51,489 → 33,792 B
  (−34 %), gzip 8,339 → 5,874 B (−2.5 KB), **request 173 → 17,402 B**, server 396 → 363 ms (noise; the ~400 ms floor, N9).
  The suspect `99979100001` is not in that projection: nothing held, nothing saved.
- **Why no-go.** Net loss on the wire under gzip (about 23.3 KB against 8.5 KB on the hub): request bodies are not compressed,
  and each named link (~68 B) costs more than the gzipped row it saves (~10 B). Any design that lists what the client holds
  has this problem. The cheap-request alternative, a server token (the held projection's ETag → ProjectionCache entry checked
  against the same viewer), was not built: entries live 60 s, so most expands would fall back. Server time is the per-request
  floor, not the body. Reopen only with a different mechanism and a measurement that beats the full read.

**Guardrails.** Masking and access are checked on every served result, cached or not. A cached graph is keyed to the
viewer: it must never be shared between two viewers with different row scope (see PERF-D2). A stale result must
be visibly marked as "as of" a time, never silently shown as current.

### P11 — polish (low gain, do last)

| Id | Work | Proof |
|---|---|---|
| PERF-P11-1 | Fuse the 8–12 derived passes in the browser pipeline; memoise `autoStyleOf`; add value comparators on `canvasData` and its intermediate computeds (as `canvasPlugins` already does) | P0-1 scripting per action drops; no identity churn in the change log |

❌ **MEASURED, NO-GO, 2026-11-13 (nothing built; board row `LA-PERF-P11-1` closed).** Preview demo install
(`inspecto-previewinspecto-la`, its own production UI) on :8080, `la-showcase` saved view `telecom_fraud_network`
(767 nodes / 4,000 links), the built-in browser pane VISIBLE, in-page `PerformanceObserver` long tasks and
long-animation-frame script time, plus a timing wrapper on `JSON.stringify` (the cost of every `stableKey` comparator).

| Action | Script per action | Long tasks | `stableKey` (all calls) |
|---|---|---|---|
| Select (node click, first) | 4,518 ms (pointerup 3,426 ms) | 4,433 ms | — |
| Link click (details dialog) | 253 ms | 83 ms | — |
| Filter: hide one link kind (legend chip) | 2,349 ms | 2,335 ms | 0.4 ms, 4 calls, 27 KB |
| Filter: show it again | 3,403 ms | 3,295 ms | 0.4 ms, 4 calls, 27 KB |
| Expand | not re-measured: §P6 has 378–435 ms of long tasks per small expand on ~405 nodes | | |

The derived passes themselves could not be isolated in the production bundle (no profiler protocol). Bound: a replica
of the pipeline's passes at the same size (Working Set marking, link-kind hiding, held marking, link weighing, the
`autoStyle` counts, legend kinds, degree / hubs, node options, drawn / unexpanded / missing counts — 12 full passes
with object spreads) costs **2.8 ms median, 5.4 ms p90** over 30 runs in the same page. With `stableKey` at 0.4 ms,
the work P11 would fuse or memoise is about 0.1–0.3 % of an action; even removing all of it would not be visible. The
time is in G6 (rAF and timer callbacks: element update, render). A plain select does not recompute `canvasData`
at all unless "Only unexpanded" is on. `autoStyleOf` and `displayOptions` already carry `stableKey` comparators.
Before = after: nothing changed. (The current-tree dev UI on :4210 could not draw against this older backend, so the
measurement used the install's own UI build; the derived-pass code is the same shape.)

---

## 3. Sequencing

1. **P0** (all four) — the gate.
2. **P1 + P2** in parallel (both browser, separate files) — largest interactive win.
3. **P3 + P4** (server, same module) — largest payload and request win.
4. **P5** once the operator has decided.
5. **P6, P7, P8** as P0 confirms their share.
6. **P9, P10, P11** last; P10 needs an API versioning decision.

## 4. Rules for every item

- A change lands only with its proof, and the P0 scenario is re-measured after it. Numbers go in the plan, not in memory.
- The two browser items that touch G6 (P1, P6) need a before/after screenshot of S-large.
- P3 and P4 must keep masking and row-scope before any cache or fast path.
- Do not bump caps (500 / 1,000 / 2,500) as a performance fix — that is a product decision, recorded in the roadmap.

## 5. Decisions owed (operator)

**PERF-D1 — LA-07 / betweenness.** Reopen the Web Worker (P5-2), approximate (P5-1), or keep refused until a felt
freeze is reported with a trace?
*Recommendation:* keep refused until P0-1 shows it as the top scripting cost on S-large; the existing reopen trigger
is about analyst reports, so record one if it happens.
**Answer:** (2026-11-12, aligned with the operator's PERF-D5 answer at the operator's instruction "align D1") keep refused: no worker, no approximation. Reopen only on an
analyst-reported felt freeze with a trace. **Superseded 2026-11-13 (operator asked for P5):** approximation built, worker still refused (§P5 "As built").

**PERF-D4 — Working Set staleness bound (ANSWERED 2026-10-25, operator: "go with your 30s TTL decision").**
The Working Set GET trusts a cheap change token (size + nanosecond mtime + file key + a hash of the last log line)
for at most 30 s (`WORKING_SET_TRUST_MS`); after that the next read does the full SHA-256 content check as
before. An out-of-band edit that preserves all four token parts is therefore stale for at most 30 s, never
indefinitely. In-app rewrites always change the mtime. Found by the lane D2 verifier: a same-size, same-mtime
rewrite was served stale as a 304 by the first cut. The alternative (full hash on every read) was declined: it
returns the 304 bandwidth saving only.

**PERF-D2 — Server cache scope.** ETags on projection and Working Set (P4) — acceptable to cache a masked result
keyed on the viewer's masking profile?
*Recommendation:* key on the masking profile as well as the fingerprint; never share a cached result across viewers
with different row scope.
**Answer:** (operator, 2026-11-13, interview: "Masking profile") key every server cache entry on the Dataset
fingerprint plus the viewer's masking profile and row scope; viewers with identical access may share an entry, others
never.
**Audit against the rule (2026-11-13, `PERF-D2-KEYS-1`): every server-side LA cache complies; no key changed.** Masking in
LA is the Space's (mode, Entity Types, bound columns, masking key), the same for every viewer; the one per-viewer lift,
`canRevealLinkEntities`, is used only by uncached reads (suggest, Dossier hand-off). Row scope is the R3 Dataset gate
(and the Investigation open gate); no LA read applies a per-row policy.
- **P4-2 projection / neighbours** (`ProjectionCache.key`, `la/inspecto-la-api/.../ProjectionCache.java:100`): complies,
  and is stricter. The key holds the fingerprint, the masking mode, Entity Types, Space masking key and the masking
  description (`:121`–`:128`), plus the whole viewer (`:116`, `viewer()` at `:256`: Subject id, capabilities, data scopes,
  attributes, held roles). The same viewer shares an entry; two different Subjects never do, even with identical access.
  The cache is looked up after every gate (`InvRoutes.java:1019`).
- **P4-3 Working Set** (`WorkingSetRoutes` relation cache `:150`, key `WorkingSetEtag.relationKey`; ETag
  `WorkingSetEtag.of`): complies. The cache holds the RAW relation of the Investigation log, read only after
  `openForRead` (owner / Case member / R3 / PDP); masking is applied after the cache on every request, and the masking
  profile is in the ETag (`WorkingSetEtag.java:41`, `:52`). Every viewer the gate admits sees the same log.
- **Graph run results** (`GraphRunService.cacheKey`, `la/inspecto-la-core/.../GraphRunService.java:449`): complies.
  The key holds the relation key and `scopeFingerprint` (data scopes and attributes, `GraphRunRoutes.java:741`); ids are
  masked after the cache (`GraphResultJson`).
- **Entity Type profile** (`EntityProfiles.Cache`, key `(root, type, bindingSignature)` at `EntityProfiles.java:223`):
  complies. The binding signature is built from the caller's own resolved bindings (`EntityTypeBindings.resolve(ex, …)`,
  R3 `canView`), so the row scope is in the key; the stamp holds the masking mode and every Dataset's fingerprint
  (`:905`). Masked types answer aggregates only. The scope is held twice: the stamp also names the caller's Datasets and
  binding signature, so the test fails only when both key and stamp lose it (mutant run 2026-11-13).
- **Discover** (`DatasetDiscovery.Cache`, `DatasetDiscovery.java:75`): complies. One Dataset after its R3 gate; the key
  holds the masking mode; the cached answer holds raw ids and is masked per request after the cache.
- **N7 / N8 stamped caches** (`StampedCache` for `ComponentRegistry` and `PipelineSchemas`): not in scope of the rule.
  They hold parsed config (the registry scan, pipeline schema lineage), no Dataset rows or values; every viewer filter
  runs after them.
- **Investigation coverage row counts** (`InvestigationCoverageRoutes.ROWS_CACHE`, `InvestigationCoverageRoutes.java:186`, key at
  `:154`): complies. Per-day counts only, keyed on Dataset, relation, column, zone, window and input stamp; read after
  `openForRead` and the R3 check in `relationFor` (`:140`).
- **Draft checkpoints** (`DraftCheckpoints.STATES` / `BASES`, `la/inspecto-la-core/.../DraftCheckpoints.java:40`): complies.
  Evaluation state keyed on the Draft's log, Investigation-scoped like the Working Set, masked after.
- `InputFingerprintCache` holds file fingerprints only, and `ExplorationMasking`'s alias book holds Space-wide pseudonyms.

Pinned by `ControlApiProjectionCacheKeyTest.aDifferentRowScopeIsADifferentEntry` / `identicalAccessSharesOneEntry` and
`ControlApiEntityTypeProfileTest.theProfileCacheIsScopedToTheDatasetsTheCallerCanView`; the Working Set masking profile
was already pinned by `ControlApiWorkingSetEtagTest.theMaskingProfileIsPartOfTheTagSoAMaskedBodyIsNeverServedForAnotherProfile`.

**PERF-D3 — API change for P10.** A new media type / `v2` path for the edge wire format, or defer P10 entirely?
*Recommendation:* defer until P0-3 shows bytes are still a problem after P4 (gzip removes most of the cost).
**Answer:** 2026-11-12, operator: "let's take on D3" — build it now rather than defer. Built as PERF-P10-1 with a versioned
media type (§P10); the measurement bore out the recommendation's caution (gzip bytes −1 to −7 %).

**PERF-D5 — Heavy graph work off the main thread (NEW 2026-10-25, from §0b).** The live measurement found the freezes are LAYOUTS and HULLS, not betweenness: Radial blocks the tab for more than 3 minutes at 767 nodes / 4,000 links, Fruchterman / Force / Layered take 2.3–3.6 s, the hulls toggle 3.1 s, a cold first-open 2.7–4.1 s, while betweenness is 385 ms at 465 nodes and is already refused above 500. The standing refusals LA-06 / LA-07 (viewport culling, Web Worker for the algorithms) were reasoned at a 500-node canvas cap and one slow algorithm. Choose: (a) keep the refusals and fix by CAPS and MEMOISATION only (N1 cap on Radial, N2 memoised hulls, prefetch the graph chunk), or (b) reopen LA-07 to cover layouts and Louvain in a Web Worker (G6 supports worker layouts for some algorithms).
*Recommendation:* (a) first — N1 and N2 are small and remove the worst freezes (both SHIPPED 2026-10-25) — then re-measure; reopen (b) only if layouts above the caps are still a felt freeze. This also supersedes PERF-D1's framing: betweenness alone does not justify a worker.
*Evidence, 2026-11-12 (§0b "N3 / N4 measured"; the worker was NOT built, this decision stays the operator's):* with N1, N2 and the N4 live relayout shipped and every layout cap in place, the main thread still blocks on the default graph of 767 nodes / 4,000 links for **7.4 s at first open** (G6 element creation 2.7 s + Force layout 4.1 s), **2.3 s for a switch to Force** and **3.8 s to Force (spread)**, and **1.3 s to paint a 767-node selection**. Prefetching the chunk would save 0.28 s of that. A worker can take only the layout part: about 4.1 s at first open and 1.3–2.8 s of a Force switch (the switch minus the ~1 s position update). It cannot take element creation or the selection repaint, which are G6 canvas work. Cheaper alternatives that are NOT caps: fewer d3-force ticks (a faster `alphaDecay`, visibly looser layout; not tried) and positions remembered per graph signature so a re-open skips the layout. Both change behaviour or add state, so they are listed for the decision rather than built.
**Answer:** (operator, 2026-11-12: "go with your recommendation on PERF-D5") option (a): keep LA-06 / LA-07 refused,
no Web Worker for layouts or algorithms; fix by caps and memoisation only. Reopen (b) only on an analyst-reported felt
freeze with a trace.

## 6. Documentation that must be corrected

- `docs/okf/product/module/la-core/user-guide-link-analysis.md` says the projection cap is 500 and the analysis cap is 2,000 (lines around 321–322 and 398–401). Both caps are settings-driven now with defaults 1,000 and 2,500, and the same file contradicts itself near line 299. Fix in the same change that lands any perf work (per the doc rules: distil into the hub, do not add a new doc).
