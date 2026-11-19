---
type: Brochure
title: Inspecto LA whitepaper — Performance, sizing, limits and the claims register
description: Part 5 of 6 of Inspecto LA whitepaper: · Performance benchmarks, sizing & operational limits; · Transparent Claims Register (Evidentiary disclosure)
---

# Inspecto LA whitepaper — Performance, sizing, limits and the claims register

Part 5 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · previous: [Enterprise governance, security and access control](brochure-inspecto-la-whitepaper-governance.md) · next: [Conclusion and architectural recommendation](brochure-inspecto-la-whitepaper-conclusion.md).

# 6 · Performance benchmarks, sizing & operational limits

Inspecto LA's figures come from **three different benchmarks, run on different corpora and configurations**. They are kept apart below and must not be read as one curve. All use synthetic corpora with a skewed (heavy-tailed) degree distribution unless stated; every one is a recorded run on the named hardware, not a service-level guarantee.

### 6.1 Benchmark measurements, one bench per table

**Bench A — the index design spike** (`InvIndexSpikeBench`, a gated harness; Intel Core i7-9850H, 6 cores / 12 threads, 32 GB RAM, DuckDB 1.5.2, warm cache, mean degree 5, no memory cap; single run). At $10^8$ edges:

| Measure | Value |
| :--- | :--- |
| Full index build | $368\text{ s}$ (6.1 minutes), peak working set $17.9\text{ GB}$ |
| One hop, flat unsorted file | $1{,}031\text{ ms}$ |
| One hop, sealed index path (the spike's hop, not a production request) | $43\text{ ms}$ — about $24\times$ faster |
| Java-driven 5-level walk | $3.0\text{ s}$ and $4.3\text{ s}$ p50 against a $1.5\text{ s}$ criterion — **failed** |

The failed walk is why the index serves only depth $\le 2$ and $\le 20$ keys per level (Section 6.2).

**Bench B — `IndexScaleBench`** (the production `IndexBuilder`, `IndexReader` and `IndexedTraversal`; a Windows laptop with 12 hardware threads, warm; skewed corpus with nodes = edges / 5; DuckDB build memory capped at $8\text{ GB}$ by the bench property `inspecto.bench.buildMemory` — **a bench setting, not the production default** (production gives a build 80 % of RAM by default, `index.build_memory_pct`); 60 random keys after one warm-up; single run, 2026-10-05):

| Edges | Full build | Index size | One key p50 / p95 | One key p50, 8 delta files | Depth-2 walk p50 / p95 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| $10^6$ | $4\text{ s}$ | $0.02\text{ GB}$ | $26.6 / 43.5\text{ ms}$ | $35.3\text{ ms}$ | $229 / 538\text{ ms}$ |
| $10^7$ | $22\text{ s}$ | $0.23\text{ GB}$ | $20.2 / 27.3\text{ ms}$ | $29.9\text{ ms}$ | $32 / 489\text{ ms}$ |
| $10^8$ | $447\text{ s}$ (32 buckets) | $2.23\text{ GB}$ | $20.9 / 31.0\text{ ms}$ | $37.8\text{ ms}$ | $21.8 / 32.0\text{ ms}$ |

A one-key lookup is flat in the number of edges at reader level (about $20\text{ ms}$ from $10^6$ to $10^8$): what limits a walk is the size of its frontier, not the size of the data. Daily appends stay cheap: at $10^8$ each delta file adds about $2\text{ ms}$ to a lookup ($20.9\text{ ms}$ with none, $37.8\text{ ms}$ at the maximum of 8, so about $17\text{ ms}$ more), and a compaction restores the single-file figure. A walk costs its frontier: a full 20-key frontier costs about $0.4\text{ s}$ (about $20\text{ ms}$ per key, sequential; $408\text{ ms}$ at $10^8$ with no delta files). The $22\text{ ms}$ depth-2 figure at $10^8$ is over typical random keys, most of which have degree near 1, not over a 20-key frontier. **Beyond $10^8$**: at $5\times10^8$ edges the build ran out of memory at the $8\text{ GB}$ cap after 91 minutes; the $10^9$ attempt of 2026-10-13 was stopped when free disk reached its floor. On 2026-10-21 (same laptop, 31.7 GB RAM) the full builds at $3\times10^8$ and $2\times10^8$ edges both ran out of memory in the `nodes` phase under a $6\text{ GB}$ cap (DuckDB reported $5.5\text{ GiB}$ in use; 60 M and 40 M distinct nodes, after 64 and 39 minutes), and a $5\times10^8$ rerun ran out of memory in the same phase under a $12\text{ GB}$ cap ($11.1\text{ GiB}$ in use, 100 M distinct nodes, about 97 minutes). The `nodes` phase thus needs memory at least linear in distinct nodes, and bounding it is open work. So the scaling run is **incomplete**: no completed build, size or latency figure exists above $10^8$, $10^9$ is not proven, and a $10^9$ run needs a host with roughly $250\text{ GB}$ free disk and at least $16\text{ GB}$ for the build (`docs/okf/product/module/la-storage/tests-index-scale-run.md`, backlog `LA-INDEX-SCALE-MEASURE-1`). In production the build's memory is configurable: `index.build_memory_pct` gives a build that share of physical RAM (default 80 %), and a build waits in phase `waiting_for_ingest` while ingest runs, so a nightly build does not compete with the day's load (`docs/okf/product/module/la-storage/admin-guide-la-storage.md`).

**Bench C — the daily chain** (`LaDailyIngestScaleBench` then `DailyIndexScaleBench`, measured 2026-10-09; a 12-thread Windows laptop with 31.7 GB RAM that was 19–31 % busy with other work, so wall times are noisy; a synthetic 10-column day whose generator caps distinct entities at about 1.8 million nodes (real records carry far more, which raises sort and dictionary cost); index builds with no memory limit and all threads; one run each):

| Step | $10^7$ rows | $10^8$ rows |
| :--- | :--- | :--- |
| Day ingest (CSV to partitioned Parquet) | $35\text{--}40\text{ s}$ | $489\text{ s}$ (ten parts of $10^7$) |
| Full index build (first build) | $31\text{ s}$, peak $3.3\text{ GB}$ | $852\text{ s}$ (14.2 min), peak $3.6\text{ GB}$ |
| New day lands (+10 %), **append** onto the live index | $4.5\text{ s}$, peak $0.54\text{ GB}$ | $30.6\text{ s}$, peak $3.3\text{ GB}$ |
| Full rebuild of base plus the day | $31\text{ s}$ | $929\text{ s}$ (15.5 min) |

An append costs only the new day, so the daily Job is about $7\times$ ($10^7$) and about $30\times$ ($10^8$) cheaper than a full rebuild. Peak memory is flat with row count (DuckDB spills to disk) rather than proportional. One consequence: the `la.index.build` Job's default wait for an in-flight build is bounded (about two minutes), so behind a full build at $10^8$ it gives up and the next trigger indexes the day; the Job parameter `wait_seconds` lifts the bound.

**Through the real route** (`InvTraversalBench` for the $10^6$ gate; the scale table of the Link Analysis feature concept for the rest): every request pays a fresh sandboxed connection of about $100\text{--}200\text{ ms}$, so at $10^6$ edges the index is **not** faster (one hop $209\text{--}466\text{ ms}$ against $293\text{--}373\text{ ms}$ flat); from $10^7$ it wins about $2\text{--}5\times$ for a hop and $1.5\text{--}3\times$ at depth 2. At $10^8$ the route comparison was not re-run. That is why the index is consulted only when it is enabled, matching and fresh enough.

### 6.2 Algorithmic boundaries & traversal routing conditions

To prevent denial-of-service conditions during interactive exploration, Inspecto applies stated, configurable routing conditions:

1. **Index scope (depth $\le 2$, frontier $\le 20$ keys per level)**: `index.traversal_depth` and `index.frontier_cap`. Single-key reads are the cheap case; the cost of a walk is linear in its frontier, and a hub key costs more (a hub key $36\text{--}56\text{ ms}$ in Bench B). The frontier cap bounds the number of keys, not the cost of a hub.
2. **Deeper or wider requests**: a traversal deeper than 2 hops or wider than the frontier cap is **not** served by the index; it falls back to the flat recursive CTE over the Dataset with a closed, stated Reason in the answer (the traversal fences `traversal.*` bound depth, edge yield, paths and time). Whole-graph algorithms run in memory over the Working Set or through a Graph Run, routed by input type, not by size.
3. **Index-backed reads cover** recursive paths to depth 2, neighbours, simple Investigation expands, temporal reads and three Graph Run algorithms on index input (neighbourhood, ego network, seeds-only degree). Everything else reads the Dataset directly.

**Inline node ceilings per algorithm.** Each server algorithm carries a cost class and a node ceiling (the catalogue `Algorithm`): the largest benched node count — $10^3$, $10^4$ or $10^5$, on a heavy-tailed graph with 5 edges per node — whose median run took at most $1\text{ s}$, or 500 where even $10^3$ took longer. At or under its ceiling a Graph Run request waits up to 3 s for the answer; above it the run continues as a job. The ceiling is not a limit — the budget (Section 6.3) refuses a run that is too large.

| Cost class | Node ceiling | Algorithms |
| :--- | :---: | :--- |
| Inline (SYNC) | 100,000 | shortest path, neighbourhood, ego network, degree centrality, connected components, articulation points, bridges, is-forest, descendants |
| Inline (SYNC) | 10,000 | k-core, triangle count, weighted shortest path, maximum spanning forest, Jaccard similarity, PageRank |
| Job | 100,000 | find cycles |
| Job | 10,000 | Louvain, label propagation, all paths |
| Job | 1,000 | closeness, cliques, maximum flow, link prediction, eigenvector, Katz, HITS, propagated risk |
| Job | 500 | betweenness, suspicion score (the Space's `suspicion_node_cap`, default 1,000, decides the suspicion score's inline-or-job choice and the browser refusal) |

In the browser, each algorithm has its own configurable cap, defaulting to `analysis_node_cap` (2,500 nodes) or a lower per-algorithm ceiling (500 for betweenness, 1,000 for closeness, cliques, maximum flow, link prediction, eigenvector, Katz and HITS). The measurement behind those caps: 25 of the browser algorithms finished under $60\text{ ms}$ at 2,000 nodes, while the suspicion score took about $7\text{ s}$ there (its cost is quadratic, dominated by betweenness), which is why large runs move to the server.

### 6.3 Layered configurable limits system (LA-CONFIG-LIMITS-1)

Inspecto LA avoids hard-coded limits: the `LaLimit` catalogue (156 items on this tree, from canvas caps to rate limits) holds every limit with its key, layer, default and validated range, and the server enforces whatever value is in force. Deployments size the system to their hardware and their analysts, not to the laptop the defaults were measured on. A value outside its range is refused `422` naming the key and the range; a stored value outside its range reads as the default. Limits fall in three layers, separating platform sizing, organisational policy and client rendering:

```
                    LAYERED CONFIGURATION ARCHITECTURE
                    
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ INSTALL: HOST RESOURCE CEILINGS (system administrator, la-limits.toon)  │
  │ • Graph Run pool and ceilings · index build threads, memory, buckets    │
  │ • Rate limits · traversal memory and threads · Entity profile memory    │
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │ Space values are clamped to these
                                       ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ SPACE: ANALYSIS AND POLICY LIMITS (Space administrator, link-analysis.  │
  │ toon; edited under Settings > Link Analysis)                            │
  │ • Four-eyes thresholds, hub threshold, expansion and traversal budgets  │
  │ • Investigation size caps · Draft, embed and index policy · profile     │
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │ Items marked "browser" may be lowered
                                       ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ BROWSER: RENDERING CAPS (the analyst, kept in the browser)              │
  │ • Canvas, algorithm and level-of-detail caps, clamped to the Space value│
  └─────────────────────────────────────────────────────────────────────────┘
```

- **One source of truth**: `GET /settings/link-analysis/limits` publishes every item with its default, range, stated value and the value in force; the single-page application reads them instead of keeping its own copies (`LaLimitCatalogueParityTest` pins its fallbacks to the catalogue). Space items are written by `PUT /settings/link-analysis/limits`, install items by `PUT /system/link-analysis/limits`, all or nothing.
- **Changes apply without a restart**: Graph Run ceilings and pool size are re-tuned on the next request, index pools on the next build, rate buckets after the system write (a hand edit of `la-limits.toon` applies at the next write or restart), and traversal memory, threads and heavy-Draft permits are read per call.
- **Index builds and ingest never compete**: a build may use up to 80 % of physical RAM by default (`index.build_memory_pct`) and waits in phase `waiting_for_ingest` while ingest runs.
- **Defaults set from measurement**: canvas 1,000 entities; browser algorithms 2,500 nodes or their measured lower ceiling; suspicion score 1,000 nodes in browser and server; expand budget 4,000 rows (at most 40,000); recursive paths 12 hops by default (at most 20, 10 s); connect 40 seeds, 3 hops by default (at most 6).

A selection of the shipped items (defaults and ranges are those of the catalogue on this tree):

| Layer | Key | Default | Validated range | Enforced where, and on overflow |
| :--- | :--- | :--- | :--- | :--- |
| **Install** | `graph_run.ceiling_nodes` / `ceiling_edges` | 500,000 / 5,000,000 | 1 to $10^8$ / 1 to $10^9$ | clamps a Graph Run's Space budget; the response says `budgetClamped` |
| | `graph_run.ceiling_timeout_ms` | 300,000 | 1 to 86,400,000 | clamps the Space timeout |
| | `graph_run.threads` / `queue` | 2 / 16 | 1 to 64 / 1 to 1,000 | worker pool; a full queue is `REJECTED`, HTTP 503 `STORE_BUSY` |
| | `index.threads` / `queue` | 1 / 4 | 1 to 64 / 1 to 1,000 | one live build per Dataset and mapping (409); a full queue is 503 |
| | `index.build_memory_pct` | 80 | 1 to 100 | share of physical RAM given to a build |
| | `index.edges_per_bucket`, `min_buckets`, `max_buckets` | 4,000,000, 16, 1,024 | 1,000 to $10^9$; 1 to 1,024; 1 to 65,536 | bucket sizing; a change forces a full rebuild |
| | `rate.link_analysis_burst` / `per_s` | 30 / 0.5 | 1 to 3,000 / 0.01 to 1,000 | per-user bucket; 429 |
| | `traversal.memory_limit_mb`, `traversal.threads` | 1,024, 4 | 64 to 1,048,576; 1 to 64 | one recursive path search |
| | `drafts.heavy_jobs` | min(4, cores / 3), at least 1 | 1 to 64 | heavy Draft jobs (replays, Working Set builds, expands) at once; over it 429 |
| | `entity_profile.memory_limit_mb` | 4,096 | 64 to 1,048,576 | memory of one Entity profile query |
| **Space** | `graph_run.max_nodes` / `max_edges` / `timeout_ms` | 50,000 / 500,000 / 30,000 | 1 to $10^7$ each (clamped to the install ceiling) | `POST /inv/graph/runs`; overrun ends `BUDGET_EXCEEDED` with no result |
| | `four_eyes_budget_above`, `four_eyes_fan_out_above` | none (off) | 1 to 100,000 | an expansion above is held for approval (Section 5.3) |
| | `hub_threshold` | 500 | 1 to 100,000 | a candidate with more distinct contacts is flagged high-connectivity and not expanded further |
| | `max_set_bytes` / `max_investigation_bytes` | 64 MiB / 4 GiB | 1 B to 1 GiB / 1 B to 1 TiB | one step's sealed Working Set / one Investigation's total; 413 `PAYLOAD_TOO_LARGE` |
| | `investigation.expand_budget_default` / `_max`, `max_frontier` | 4,000 / 40,000 rows, 2,000 | 1 to 200,000 / 2,000,000; 1 to 100,000 | one expand; clamped or refused |
| | `traversal.depth_default` / `depth_max`, `traversal.timeout_s` | 12 / 20 hops, 10 s | 1 to 50 each; 1 to 300 | one recursive path search |
| | `connect.max_seeds`, `connect.default_hops` / `max_hops` | 40, 3 / 6 | 2 to 2,000; 1 to 6 each | one `connect` |
| | `index.traversal_depth`, `frontier_cap`, `max_deltas`, `max_age_hours` | 2, 20, 8, 24 | 1 to 10; 1 to 2,000; 1 to 100; 1 to 8,760 | indexed reads; flat fallback with a Reason |
| | `drafts.max_open`, `hibernate_after_minutes`, `expire_after_days` | 50, 60, 30 | 1 to 1,000; 1 to 10,080; 1 to 3,650 | Draft admission and lifecycle |
| | `embed.default_ttl_hours` / `embed.max_ttl_hours` | 72 / 720 | 1 to 8,760 each | embed expiry; above the maximum is 422 |
| | `entity_profile.timeout_s` | 30 | 1 to 600 | one profile statement |
| | `entity_profile.max_datasets`, `top_n` | 20, 20 | 1 to 2,000 each | Datasets read per type profile; entries per list |
| | `entity_profile.shape_sample_rows` | 1,000,000 | 1 to $10^9$ | above it, value shapes read a sample |
| | `entity_profile.sentinel_share` | 20 % | 1 to 100 | a single value above this share is reported as dominant |
| | `entity_profile.timeline_days`, `relationship_threshold` | 400, 6 | 1 to 36,600; 2 to $10^6$ | timeline length; outlier count |
| | `entity_profile.measure_sample_entities` | 100,000 | 1 to $10^7$ | above it, a Measure's per-Entity totals read a key-hash sample |
| **Browser** | `projection_node_cap` | 1,000 | 1 to 100,000 | entities admitted to one canvas projection |
| | `analysis_node_cap`, `suspicion_node_cap` | 2,500, 1,000 | 1 to 100,000 each | above it the browser refuses or hands over to the server |
| | `display.lod_label_cap`, `layered_max_nodes`, `radial_max_nodes`, `mds_max_nodes`, `force_atlas_max_nodes` | 300, 30, 100, 300, 200 | up to 30,000; 3,000; 200; 400; 400 | labels drawn; layouts offered |

### 6.4 Measured on one production-scale day (single run)

One day of an IMS call-and-message record feed was profiled and indexed end to end to see what the product does at production scale. **This is one run on one laptop, on private data that a reader cannot reproduce; it is reported as a measurement, not a benchmark gate.** Only aggregate volumes and timings are published.

**Host and data.** A Windows laptop, 12 logical cores, 31.7 GB RAM, one SSD. One day (2026-10-01): 100 Parquet files, $12.25\text{ GB}$, $132{,}165{,}497$ records, 83 columns, one schema.

| Activity | Volume | Where | Time |
| :--- | :--- | :--- | :--- |
| Schema and row count from Parquet metadata | 100 files | laptop | under $1\text{ s}$ |
| Value profile of 12 columns | 132 M rows | laptop | about $45\text{ s}$ (sum of 2–9 s queries) |
| Threshold calibration | 132 M rows | laptop, 10 threads, 12 GB | $72\text{ s}$ |
| Link build, dry run | 132 M rows → 39.0 M links | laptop, 10 threads, 12 GB | $335\text{ s}$ |
| **Link build Job** | 132 M rows → 39.4 M links | server, DuckDB cap 10 GB | $572\text{ s}$ (9.5 min) |
| **Link index, full build** | 39.4 M links → 12.35 M nodes, $838\text{ MB}$ | server, one build thread | $316\text{ s}$ (5.3 min) |
| Typology counts Job | 39.4 M links | server | $7\text{--}45\text{ s}$ |
| Alert evaluation | one row | server | under $1\text{ s}$ |
| Whole entity-and-link profile (offline) | 132 M rows + 39.4 M links | laptop, 10 threads | $166\text{ s}$ |
| Entity profile, warm (cached) | — | server | $0.4\text{ s}$ |
| Entity profile, cold, IMSI / IMEI (after the scale fixes of Section 4.7; the relationship to the number timed out at 300 s in that run) | 132 M rows | server, 4 GB, sampling | $669\text{ s}$ / $731\text{ s}$ |
| Entity profile, cold, IMSI, after same-party pairing | 132 M rows | server | $453\text{ s}$ for the whole section; relationships exact, not sampled |

*Before the scale fixes* the profile ran out of memory on two sections at the 1 GB sandbox default (after $31\text{ s}$ and $131\text{ s}$), and one number-level profile exceeded 25 minutes. With a 4 GB budget the two reads took $58\text{ s}$ and $149\text{ s}$; hash-sampled at 1 GB, $41\text{ s}$ and $123\text{ s}$.

**Estimates, not measurements.** The source note marks these with "~" and they are *estimates*: a nightly link build at the server's default DuckDB memory (about 3.2 GB there, not the 10 GB above) of **15 to 25 minutes**; a nightly chain (links, index, typology counts, Alerts) of **25 to 35 minutes**; DuckDB spill up to **about 4 GB** during the link build; a cold number-level profile of **20 to 30 minutes**.

**Caveats to carry with these figures.** (1) A single run on a single laptop. (2) The server configuration differs between rows — a 10 GB cap for the link Job, one build thread for the index, two threads for the profile — so the rows do not form one consistent configuration. (3) The data is private, so no reader can reproduce it. (4) Two outliers in the data (one handset and one number carrying an unusually large count of the other identifier) are unexplained. (5) The scale test in the repository (`ControlApiEntityProfileScaleTest`) pins behaviour at sandbox scale, not these timings. (6) In this feed a delivery attempt is recorded as a record, so burst-style counts are inflated: a rule's count there must be read as *failed delivery attempts*, not as distinct targets.

### 6.5 Concurrency and admission

Every shared resource is bounded, and a full one refuses at once instead of queueing without bound (defaults; each is a limit of Section 6.3):

| Resource | Limit | When full |
| :--- | :--- | :--- |
| Graph Run pool | 2 threads, queue of 16 (install) | refused at once: `REJECTED`, HTTP 503 `STORE_BUSY` (retryable) |
| Index builds | one build service per Space: 1 thread, queue of 4 (install); one live build per Dataset and mapping | 503 when the queue is full; 409 for a duplicate |
| Heavy Draft operations | min(4, cores / 3) permits, at least 1 (install) | 429 at once, never waited for |
| Expensive exploration calls | per-user token bucket, burst 30, one more every 2 s (install) | 429 |
| Graph Run result cache | 32 entries, 10 minutes; key includes the caller's row scope | evicted; a scope change never reuses a result |

### 6.6 Draft rebase and promote cost

Measured at 800 steps (`LA-DRAFT-PROMOTE-COST-1`, pinned by `DraftPromoteCostTest`): promote fell from $34.9\text{ s}$ to $3.3\text{ s}$ warm ($8.3\text{ s}$ cold) and is now linear in the bytes the sealed sets hold, because it reuses the Draft's sealed per-step hash and set file after verifying each file's bytes. Rebase fell from $36.7\text{ s}$ to $8.4\text{ s}$; it stays proportional to the state per step, because every sealed hash is re-checked. A cheaper rebase was examined and declined, since it would weaken the evidence chain.

### 6.7 Test baseline and coverage

The whole platform builds and passes its tests before a change ships. At the editions commit (`f1b64ce03`, 2026-10-17) the full reactor ran 56 modules and 10,566 tests with 0 failures, and the single-page application 5,913 tests (5 skipped). Coverage was measured on 2026-10-15 (backend over 48 module reports; browser over 553 spec files, 5,820 tests):

| Layer | Coverage (2026-10-15) | Enforced floor | Baseline (2026-09-08) |
| :--- | :--- | :--- | :--- |
| Backend (Java) | 86.08 % of instructions, 73.59 % of branches | 78 % / 64 % | 81.01 % / 67.19 % |
| Browser (TypeScript) | 81.51 % of statements, 76.64 % of branches | 70 % / 66 % | 73.82 % / 70.04 % |

Both layers rose by five to eight points since the 2026-09-08 baseline. The floors are set below the measured figures on purpose: a ratchet, not a target (`docs/okf/product/layer/platform/tests-guard-coverage.md`, `docs/okf/product/layer/platform/plan-compliance.md`, `docs/PROJECT_NOTES.md`).

---

# 7 · Transparent Claims Register (Evidentiary disclosure)

Inspecto adheres to an uncompromising engineering standard: **never over-claim**. Every technical assertion in this whitepaper is classified below into its evidentiary status, citing the verification suite or benchmark.

```
                           EVIDENTIARY STATUS DEFINITIONS
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │  PROVEN       A concrete automated unit test, integration suite, or gated benchmark    │
 │                harness exists.                                                         │
 │  MEASURED     A single recorded run on stated hardware (often on private data); not a  │
 │                repeatable gate.                                                        │
 │  DOCUMENTED   The design specification is committed; not yet certified by live tests.  │
 │  NOT YET      A known engineering boundary or future roadmap item. Stated openly.      │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

| Area | Feature or Architectural Claim | Status | Evidentiary Basis & Reference |
| :--- | :--- | :--- | :--- |
| **Scale** | One-key index lookups flat at about $21\text{ ms}$ p50 from $10^6$ to $10^8$ edges (reader level) | **PROVEN** | `IndexScaleBench` (Bench B; 8 GB bench cap, one run, 2026-10-05) |
| | $10^8$ one-hop $43\text{ ms}$ indexed against $1{,}031\text{ ms}$ flat ($24\times$) in the design spike | **PROVEN** | `InvIndexSpikeBench` (Bench A; i7-9850H, uncapped, one run) |
| | Full index build at $10^8$: $368\text{ s}$ (spike), $447\text{ s}$ (8 GB cap), $852\text{ s}$ (daily chain) | **PROVEN** | Three different benches and configurations: `InvIndexSpikeBench`, `IndexScaleBench`, `DailyIndexScaleBench` |
| | Daily append at $10^8$: $30.6\text{ s}$ against $929\text{ s}$ for a full rebuild | **PROVEN** | `DailyIndexScaleBench`; append correctness by `IndexAppendTest` and `ScheduledIndexBuildTest` |
| | 5-hop recursive path search on $10^6$ edges at warm p95 under $350\text{ ms}$ | **PROVEN** | `InvTraversalBench` (gate `G-R4`) |
| | Index faster than flat through the real route from $10^7$ edges ($2\text{--}5\times$ a hop); not faster at $10^6$ | **MEASURED** | `okf/product/module/la-core/user-guide-la-core.md` index design record; not re-run at $10^8$ |
| | Index builds above $10^8$ edges ($2\times10^8$ to $10^9$) | **NOT YET** | $2\times10^8$ and $3\times10^8$ ran out of memory in the `nodes` phase at a 6 GB cap, $5\times10^8$ at 8 GB and at 12 GB (2026-10-21); the $10^9$ attempt stopped at the disk floor (2026-10-13); `docs/okf/product/module/la-storage/tests-index-scale-run.md`, backlog `LA-INDEX-SCALE-MEASURE-1` |
| | One production-scale day (132 M records) profiled and indexed in the times of Section 6.4 | **MEASURED** | Single laptop run, private data, 2026-10-16 (the measurement note is kept with the data, outside the repo; aggregates only in Section 6.4); estimates in that section are marked as such |
| **Editions** | Basic is the explorer (no sign-in, no Investigations); the Investigation surface answers 503 on Basic and works on Standard | **PROVEN** | `ControlApiBasicEditionLinkAnalysisTest` |
| | The Preview bundle carries every module | **PROVEN** | `tools/offering-classpath.test.mjs`; a Preview demo bundle booted and answered `/health` |
| | Industry packs (Telecom, Mobile Money) and the server-side Domain Profile in every edition | **PROVEN** | `IndustryPacksTest`, `ControlApiDomainProfileTest` |
| | Retention, legal hold, residency and scale-out | **NOT YET** | Placed in Advanced by decision; not built (`docs/EDITIONS.md`) |
| **Security** | Seven-gate order: Link Analysis routes gated per Space by the module gate; every route in the manifest, in the rate classes and in the API contract | **PROVEN** | `ControlApiGeoLinkModuleGateTest`, `LaApiRoutesManifestParityTest`, `LinkAnalysisRateClassCoverageTest` (the first pins the module gate only, not the whole order) |
| | Strict 404 for non-members of an Investigation | **PROVEN** | `ControlApiInvestigationMembersTest` |
| | Keyed masking (`typed`, `all`, `none`) | **PROVEN** | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` |
| | Anti-oracle: a raw identifier refused where an alias is required | **PROVEN** | `ControlApiExplorationMaskingTest.everyExplorationReadShowsAliasesAndNeverARawIdWhenTheSpaceMasks` |
| | Four-eyes: requester cannot approve or deny; a denial reads nothing; self-approval is 403 | **PROVEN** | `ControlApiInvestigationOversightTest.aSensitiveExpandWaitsForADifferentPersonsApproval` |
| | Per-entity reveal gated on its own capability and audited | **PROVEN** | `ControlApiInvestigationOversightTest.revealIsPerEntityGatedOnItsOwnCapabilityAndAudited` |
| | Embed links: expiry, auto-revoke, frame allow-list, one answer to every bad token | **PROVEN** | `ControlApiInvestigationEmbedTest`, `ControlApiEmbedMultiSpaceTest`; backlog row `LA-EMBED-VIEW-1` closed 2026-10-14 (`2dad1638f`) |
| | Embed Viewer shell with token redaction, driven live | **PROVEN** | Live browser pass on an Advanced demo bundle with two Spaces (2026-10-14; backlog row above) |
| | Classified keys masked on object reads, mutation echoes, `OBJECT_OPENED` events, notifications, Assistant tool results and `POST /cases/from-entities` for a non-revealer (2026-10-15) | **PROVEN** | `ControlApiObjectReadMaskingTest`, `ControlApiEventsReadMaskingTest`, `NotificationContentMaskingTest`, `EgressMaskingTest`, `ControlApiCaseFromEntitiesTest` |
| | **All** read surfaces masked | **NOT YET** | Open: Regulatory Report object view; two pseudonyms for one key; `ops:*` database browsing; ungated `/agent/triage-runs*`, `/agent/feedback`, `/agent/approvals*`; uncovered echoes (Section 5.2.1; backlog `OBJECT-READ-MASKING-RESIDUALS-1`) |
| | Real OIDC sign-in of the standalone app | **PROVEN** | `LA-APP-REAL-SIGNIN-1`, closed 2026-10-13: a real WSO2 IS 7.3.0 with a scripted identity-provider page; `docs/okf/product/module/la-core/admin-guide-la-core.md#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server` (`4cae924e1`) |
| | Configurable limits: every limit a validated catalogue item, SPA fallbacks equal to the catalogue | **PROVEN** | `LaLimitCatalogueParityTest`, `LaLimitsTest`; backlog row `LA-CONFIG-LIMITS-1` (`7dd9938fb`) |
| | Third-party integration: inbound seeding (idempotent), deep link, outbound hand-off, signed webhooks | **PROVEN** | `ControlApiInvestigationInboundTest`, `ControlApiLaIntegrationTest` |
| | Machine-to-machine client-credentials trust for integrations | **NOT YET** | Awaits a formal security review (Section 5.7) |
| **Data Trust** | Data Trust strip, landed-File attribution, corrected-day warning | **PROVEN** | `LA-DATA-TRUST-1`; live drive in `docs/okf/product/module/la-core/tests-live-drives.md#data-trust-drive-la-data-trust-1-s10-2026-10-14` |
| | Cached data-as-of: $895\text{--}993\text{ ms}$ miss, $132\text{--}138\text{ ms}$ hit | **MEASURED** | 70 Files / 322 thousand rows, live bundle over loopback, 2026-10-14 (`LA-DATA-TRUST-POLISH-1`) |
| **Forensics** | Deterministic Working Set fold from the op log | **PROVEN** | `ControlApiInvestigationsTest.replayIsDeterministicAndEquivalentToTheIncrementalSteps`, `InvestigationExpandedFoldTest` |
| | Sealed reads survive upstream data growth; `reread` reports drift | **PROVEN** | `ControlApiReplayDriftOnFileAppendTest` |
| | SHA-256 Dossier manifest; a tampered step is reported | **PROVEN** | `ControlApiDossierTest.anUntouchedStoreVerifiesAndATamperedStepIsReported` |
| | Resilience to a rewrite that also recomputes every hash (store owner with root access) | **DOCUMENTED** | On-box evidence has this stated limit; external anchoring of the Dossier root is the countermeasure |
| | Draft promote linear in the sealed bytes: 800 steps in $3.3\text{ s}$ warm (down from $34.9\text{ s}$); rebase $8.4\text{ s}$ (down from $36.7\text{ s}$) | **PROVEN** | `DraftPromoteCostTest` (`LA-DRAFT-PROMOTE-COST-1`) |
| | Retention purge of closed Investigations | **NOT YET** | **Declined** (decision `D-U8`, 2026-09-24): the store is deliberately append-only; reopen on a regulator's or customer's retention demand (backlog standing refusals) |
| **Analytics** | 29 in-process graph algorithms; browser twins of 28 | **PROVEN** | `GraphAlgorithmsParityTest`, `GraphSuspicionParityTest`, `ControlApiGraphRunTest` (`propagatedRisk` has no TypeScript twin, by design) |
| | Explainable Suspicion Score with a factor breakdown | **PROVEN** | `GraphSuspicionParityTest`, `ControlApiGraphRunTest`; the user guide `docs/okf/product/module/la-core/user-guide-la-core.md` |
| | Propagated Risk with top-five attribution and a capped score | **PROVEN** | `ControlApiGraphRunTest.propagatedRiskAnswersWithScoresAndFactorsAndRefusesBadWeights422` |
| | Showcase figures: `TILL-06` 97 % of cash-out from 8 payers; Pass-Through lists 13 accounts of which two are the planted relays | **PROVEN** | `ControlApiLaShowcaseFiguresTest`, `LaShowcaseGoldenTest` (planted data; no detection rate implied) |
| | Entity profile pages: sections, honesty rules, masking, alias-only URLs | **PROVEN** | `ControlApiEntityProfileTest`; scale behaviour `ControlApiEntityProfileScaleTest` (sandbox scale) |
| | Published false-positive or precision rates on real data | **NOT YET** | Depend on customer thresholds; unmeasured |
| **Platform** | In-process execution with no external graph database | **PROVEN** | Architectural invariant; seam enforced by `tools/check-module-deps.mjs` and `tools/check-engine-names-no-la.mjs` |
| | **111**<!--count:la-routes--> Link Analysis API routes (**104**<!--count:la-api-routes--> in `la-api`, **7**<!--count:geo-link-routes--> in `geo-link`) | **PROVEN** | `LaApiRoutesManifestParityTest`; marker-checked against each `module.toon` |
| | PostgreSQL Investigation store (Advanced, opt-in) | **PROVEN** | `PgInvestigationStoreContractTest`, `PgInvestigationStoreTwoJvmRaceTest`; `docs/okf/product/solution-pack/la-showcase/admin-guide-variants-runbook.md`. Embed links are not supported on this store |
| | Whole-product test baseline at the editions commit | **PROVEN** | Full reactor `-Pedition-advanced`, 56 modules, 10,566 tests, 0 failures; SPA 5,913 passed, 5 skipped (commit `f1b64ce03`) |
| | Coverage measured 2026-10-15 | **MEASURED** | Backend instruction 86.08 % / branch 73.59 % (48 modules); UI statements 81.51 % / branch 76.64 % (553 spec files); recorded in `docs/okf/product/layer/platform/tests-guard-coverage.md` and `docs/PROJECT_NOTES.md` (UI also in `docs/okf/product/layer/product/plan-surfaces.md`) |
| | Multi-host high availability | **NOT YET** | A laptop-scale drill (two server processes, one Postgres) ran 2026-10-10; the two-host drill on plain Linux has not run (`docs/okf/product/layer/platform/admin-guide-ha-dr-drill.md`; backlog `ASSURE-OPERABILITY-1`) |
| | Native Apache Hive / Kerberos JDBC connector | **NOT YET** | Supported through landed Parquet files via file Collectors (decision `D-ING5`) |
| | Hosted-AI endpoints cannot be called by the standard distribution | **PROVEN** | `EgressGuardTest` (classpath absence); `compliance/evidence/air-gap-posture.md` — a packaging guarantee, not a network control |
| | Accessibility: automated axe-core gate and live passes on Link Analysis pages | **PROVEN** | `docs/okf/product/layer/product/tests-accessibility-audit.md`; `LA-A11Y-AUDIT-1` |
| | Full WCAG 2.2 AA conformance of Link Analysis | **NOT YET** | Two vendor data-grid findings and a not-audited list remain open (backlog `LA-A11Y-AUDIT-1`); a self-assessment, not a third-party certification |

### 7.1 Roadmap and limits to know today

The core is built and has been demonstrated end to end on telecom-fraud scenarios. The open work is about proving the largest scale and preparing call-record data at production volume.

**Next**
- **The $10^9$-edge proof** on a sized host (about $250\text{ GB}$ free disk and at least $16\text{ GB}$ for the build), after bounding the memory of the index build's `nodes` phase (backlog `LA-INDEX-SCALE-MEASURE-1`).
- **Call-record preparation at volume** — a plan, not built, its decisions open (`docs/superpower/link-analysis-roadmap.md`, *Data preparation*): Hive acquisition, E.164 normalisation with a node dictionary, frozen index schemas and manifests, and a presence index with co-location ("who was with X") served from the server.
- **Machine-to-machine trust for integrations**, after its security review (Section 5.7).

**Limits to know today**
- The link index is enabled per Space by default (`index.enabled`, on since 2026-10-10) but serves only the reads of Section 6.2 item 3; everything else reads the Dataset directly.
- Identity resolution is deterministic only: two identifiers merge only when someone asserts it. This is deliberate.
- Range and CIDR entries in Entity Lists match within the lists but do not yet drive seeding or exclusion in an Investigation (Section 2.5).
- Retention, legal hold and erasure controls are not built (Section 1; the purge of closed Investigations was declined, `D-U8`): real customer data should not be loaded until these rules are agreed for the deployment.

---
