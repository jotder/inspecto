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
| P5 betweenness worker | ⬜ OPEN — operator decision PERF-D1; §0b shows betweenness is NOT the top cost (385 ms at 465 nodes), layouts and hulls are | — |
| P6 layout on expand, P8 hulls, P11 polish | ⬜ OPEN — P8 hulls is now MEASURED at about 3.1 s per toggle (§0b N2) and moves to the top | — |
| P10a-5 delta fetch on expand | ⬜ OPEN — needs a server delta response | — |
| P10 wire format | ⬜ DEFERRED — PERF-D3 | — |

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
**Tier 1 — freezes the page**
- **N1 Radial layout hangs the tab (> 3 min at 767 nodes / 4,000 links).** Cap or refuse Radial above a node count (a `layered_max_nodes` = 30 cap already exists as the pattern), and say so on screen; then decide on a worker (PERF-D5). Severity: high; a one-line cap removes the hang. ✅ **SHIPPED 2026-10-25** (3 builder / verifier rounds; targeted tests only): the Radial, Information density, Clustering and every other layout are capped at the one place they reach G6 (`layoutConfig`, caps through the `display` limits: Java `LaLimit`, `la-limits.ts`, docs, parity test), Concentric is the fallback, the cap notice no longer covers the legend. `d4fd1aa11`, `65593934a`, `92903f91b`.
- **N2 Community hulls toggle costs ~3.1 s on and off.** Turning hulls OFF must not recompute Louvain; memoise the community assignment per graph signature (P8, now measured). ✅ **SHIPPED 2026-10-25** (3 rounds; targeted tests only): the hulls toggle no longer rebuilds the graph, Louvain is memoised per graph signature and cap, hull shapes come off before a data change and return after it. `0ceee890f`, `f0ecaeed5`, `aec36325a`.
- **N3 Cold first-open freezes 2.7–4.1 s.** Cause not isolated: the lazy graph chunk (~1.2 MB of library, now loaded on first open since P7) plus the layout. Prefetch the chunk when the landing page is idle; measure parse vs layout separately before building anything.
- **N4 Other layouts 2.3–3.6 s (Fruchterman, Force, Layered) and box-select 579 ms.** Main-thread layouts: P6 plus the worker decision.

**Tier 2 — load and startup**
- **N5 Landing makes ~27 calls with every Space's `/bootstrap` fetched twice (~1 MB decoded).** Dedupe (a request-level cache for GETs, the same mechanism as P10a-2) and fetch only the current Space's bootstrap; `/spaces`, `/spaces/_meta`, the components lists and `/entity-lists` each ×2 too. Cheap, client-only, high yield. ✅ **SHIPPED 2026-10-25** (verified PASS; targeted tests only): the landing page fetches each Space call once through short-lived reads (`short-reads.ts`), and every Entity List / Dataset / component / access write clears them. `10b5776b7`, `0a6420e2b`, `ddfa3d81a`.
- **N6 Hashed JS/CSS/font chunks are revalidated on every load (19 × `304`).** Serve content-hashed files with `Cache-Control: public, max-age=31536000, immutable`; negligible on localhost, 19 round trips on a real network.

**Tier 3 — server fixed overhead (MEASURE FIRST: profile before building; code-read was wrong 6 of 8 times)**
- **N7 A fixed ~80 ms per LA request** (Working Set and the Investigation list at 94–95 ms with one Investigation, against 15 ms for `/health`), and **N8 a 304 that costs as much as a 200** (projection 270–290 vs 303 ms; `/bootstrap` 21.8 vs 25.3 ms): the 304 still pays the key computation and gates. Profile with a sampling run (jcmd `Thread.print` ×N or JFR if the runtime has it) under a request loop to find what the fixed cost is (suspects: settings/limits read per request, audit append, capability/ABAC, fingerprint listing in the cache key).
- **N9 Projection latency 146 ms → 20.6 s, unexplained.** Repeat with a server-side timer and the scheduled jobs' minutes ruled out (jobs are ≤ 1.5 s so they are not the cause); suspects: lazy first-open of a DuckDB file, native-thread oversubscription (N10), the rate limiter's queueing.
- **N10 About 3,550 native threads and 1.4 GB at idle.** Cap DuckDB `threads` and `memory_limit` per instance (the first review: `DuckDbUtil.applyDuckDbSettings` leaves them at defaults when unset); prove with a before/after thread count and a projection timing.
- **N11 Boot 3 min 35 s (185 s before the first log line).** Re-measure on a warm restart; then trace what runs before logging.

**Tier 4 — defects and small fixes**
- **N12 `renderMinimap` TypeError after route changes (×4).** Guard the destroyed-graph case. ✅ **SHIPPED 2026-10-25** (builder + verifier follow-up; targeted tests only): the graph tears down without a late minimap render and survives a throwing minimap silencer. `c47966312`, `3b5efb0c2`.
- **N13 `429` without `Retry-After`,** and no client-side handling: add the header and let the client back off.
- **N14 The "node cap" banner appears at 465–767 nodes** because the 4,000-ROW cap binds first: say which cap bit.

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

### P6 — browser: layout on expand (medium gain, medium risk)

**Why:** `applyData` seeds every node's position one call at a time, and adding any node triggers a full
`graph.render()` re-layout (`graph-view.component.ts` — `applyData`, `seedPosition`, the `render()` call).

| Id | Work | Proof |
|---|---|---|
| PERF-P6-1 | Position only the new nodes from their neighbours; re-layout only when the new set is large (threshold stated in code) | P0-1: expand scripting drops on S-expand; positions of existing nodes unchanged (test) |

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

**Guardrails.** Masking and access are checked on every served result, cached or not. A cached graph is keyed to the
viewer: it must never be shared between two viewers with different row scope (see PERF-D2). A stale result must
be visibly marked as "as of" a time, never silently shown as current.

### P11 — polish (low gain, do last)

| Id | Work | Proof |
|---|---|---|
| PERF-P11-1 | Fuse the 8–12 derived passes in the browser pipeline; memoise `autoStyleOf`; add value comparators on `canvasData` and its intermediate computeds (as `canvasPlugins` already does) | P0-1 scripting per action drops; no identity churn in the change log |

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
**Answer:**

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
**Answer:**

**PERF-D3 — API change for P10.** A new media type / `v2` path for the edge wire format, or defer P10 entirely?
*Recommendation:* defer until P0-3 shows bytes are still a problem after P4 (gzip removes most of the cost).
**Answer:**

**PERF-D5 — Heavy graph work off the main thread (NEW 2026-10-25, from §0b).** The live measurement found the freezes are LAYOUTS and HULLS, not betweenness: Radial blocks the tab for more than 3 minutes at 767 nodes / 4,000 links, Fruchterman / Force / Layered take 2.3–3.6 s, the hulls toggle 3.1 s, a cold first-open 2.7–4.1 s, while betweenness is 385 ms at 465 nodes and is already refused above 500. The standing refusals LA-06 / LA-07 (viewport culling, Web Worker for the algorithms) were reasoned at a 500-node canvas cap and one slow algorithm. Choose: (a) keep the refusals and fix by CAPS and MEMOISATION only (N1 cap on Radial, N2 memoised hulls, prefetch the graph chunk), or (b) reopen LA-07 to cover layouts and Louvain in a Web Worker (G6 supports worker layouts for some algorithms).
*Recommendation:* (a) first — N1 and N2 are small and remove the worst freezes (both SHIPPED 2026-10-25) — then re-measure; reopen (b) only if layouts above the caps are still a felt freeze. This also supersedes PERF-D1's framing: betweenness alone does not justify a worker.
**Answer:**

## 6. Documentation that must be corrected

- `docs/okf/product/module/la-core/user-guide-link-analysis.md` says the projection cap is 500 and the analysis cap is 2,000 (lines around 321–322 and 398–401). Both caps are settings-driven now with defaults 1,000 and 2,500, and the same file contradicts itself near line 299. Fix in the same change that lands any perf work (per the doc rules: distil into the hub, do not add a new doc).
