<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-01; re-evaluated 2026-10-02 against HEAD 8eb2f6a9d (§0.1). PLAN ONLY — nothing in this plan is built. All 11 decisions in §8 are OPEN: the operator will decide later, so
  every recommendation below is a recommendation, never an answer. Work that needs no decision is marked "start now" (§5, §6).
  Retire per the three-tier lifecycle in CLAUDE.md when the work ships: distill the as-built facts into the OKF concepts,
  move open items to docs/BACKLOG.md, then git mv this file to docs/archived-documents/plans-archive/.
-->

# Link Analysis + Geo — data preparation, Domain Profiles and requirements closure

The plan for getting telecom CDR (call detail records) into a fast, domain-neutral Link Analysis (LA) + Geo index, with
the domain-specific parts separated from the core. It folds the 2026-10-01 requirements review into one **MoSCoW register
(§4)** and one **ranked priority list (§5)**, so the design is not re-opened after the build starts.

It extends — and must not contradict — the signed option-D work: [`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md)
(D1–D21, signed 2026-09-30) and [`la-separation-d1-design.md`](../archived-documents/plans-archive/la-separation-d1-design.md) (D-1 decisions 1–5, signed
2026-10-01). Where this plan challenges a signed answer it says so and files a decision (§3, §8) instead of overriding it.

## 0. Read this first (two minutes)

**Verdict.** The requirements were **not complete**. LA is not greenfield — Investigations, Entity Lists, masking, four-eyes
approval and the Dossier all ship — so the gaps are at the **data layer** (no Hive path, no bucketed/sorted writer, no
idempotent day replace, no index), at the **seams** (supernode policy, result gate, SQL-prunable lists, barrier), and in
**governance** (reads ungated, unaudited, unmasked). Twenty Musts, eleven Shoulds, nine Coulds, seven Won'ts.

| Class | Count | Of which can start now with **no decision** |
|---|---|---|
| Must | 20 | 8 (M02, M03, M04, M06 design, M09, M16 part, M14 part: masking of stateless reads, M19 design) |
| Should | 11 | 2, design only (S08, S09) |
| Could / Won't | 9 / 7 | — |
| Decisions owed (§8) | 11 | all OPEN |
| Spikes (§9) | 7 | 6 start now (SP7 waits for LDP-D1) |

**What starts now, with no decision:** the facts brief (§10), spikes SP1–SP6, the platform writer and replace work
(M02, M03, M04), the rate-limiter coverage of `/inv` and `/geo`, and the doc fixes (§14). (The geo view check has already landed, `bda33a508`.) Every one of these is
no-regret: it is useful whatever the decisions turn out to be.

**What I need from you, in this order** (full text in §8; none blocks Wave 0):

1. **LDP-D1** — which size target governs (D21's 20 analysts / 10⁹ edges, or your 1–3 analysts / ~10⁹ CDRs a day).
2. **LDP-D2** — what "result > 10K" counts (nodes *found* at the server, not nodes drawn).
3. **LDP-D6** — how data leaves Hive (files vs JDBC) and where record-level evidence comes from.
4. Then **D3, D4, D8** (design details, low regret), **D5, D7**, and last **D9, D10, D11** (before real customer data lands).

## 0.1 Re-evaluation 2026-10-02 (code fast-forwarded to `8eb2f6a9d`)

64 commits landed after this plan was written (at `a2c902074`): D-1 finished (steps 3–7, verified 41/41 modules and 7,319 tests),
D-4 was built **ahead of D-3**, SEP-08 moved the Entity Lists out, and a peer fixed the geo view check. **The verdict stands; the
critical path moved.**

| What moved | Consequence for this plan |
|---|---|
| D-1 done. Modules `inspecto-la-core`, `-la-api`, `-la-graph`, `-entity-list`, `-entity-store`, `-auth-spi`, `-http-spi`, `-audit-spi`; the bridge stays `inspecto-geo-link`; two ports (`DatasetProvider`, `CasePort`) | WP3 no longer waits on D-1. Module names follow the built `inspecto-*` names |
| D-4 done: the Graph Run — 28 algorithms, a `GraphEngine` seam, `/inv/graph/*`, budget 50,000 nodes / 500,000 edges / 30 s (ceilings 500,000 / 5,000,000 / 300 s), result lists cut at 10,000 with stated totals, **in-memory over a Working Set only** | It already serves the analyst's ≤ 10K-node answer. An index-scale engine is needed only for S03 and M13. "10,000" is already house policy |
| **D-3 (the index) is not started and unscheduled**; D-4's own `GraphInput` names "an edge index at D-3" as the next input | **WP3 is now the critical path** — nothing else delivers 10⁸–10⁹ scale |
| Geo view check fixed (`bda33a508`) | The geo half of M14 is done; its task is retired |
| Entity Lists: SEP-08 done (`/entity-lists`); sidecar unchanged; fact-log replay still uncached | M07 and LDP-D3 stand |
| DuckPGQ dropped by the operator (D-S2) | C02 narrows to an external graph database |
| `inspecto-etl` and `inspecto-connectors` untouched | M01–M04 gaps all stand |
| Leakage audit: `inspecto-la-core` and `inspecto-la-graph` hold **zero** telecom words | M18 extraction is two places plus the SPA constant (§2 row 9) |
| Unchanged: D21; no barrier, supernode policy, SQL-pruned lists or found-nodes gate; `/inv` and `/geo` outside the rate limiter; no hop-ladder UI | M06, M07, M11, M12, M16 stand |

**Net:** no requirement added or removed; M14 partly closed; M11 gains an in-repo precedent; S03 and C02 sharpened; WP3 moves up.
One tension to carry into LDP-D1: D-4's hard ceilings leave 10⁹ edges to a later index-backed engine, so D21 is still proven by nothing built.

## 1. Inputs and assumptions

### 1.1 Stated by the operator (2026-10-01)

| ID | Input |
|---|---|
| I-01 | Domain first: telecom revenue assurance (RA) and fraud management (FM) on CDR; the design stays domain-neutral (FX and others later) |
| I-02 | About one billion CDRs a day; the split by record type (voice on-net, off-net, international, GPRS) is **unknown** |
| I-03 | Freshness: daily |
| I-04 | Depth: up to 4 hops with filters; above ~10K result nodes the analyst must add filters |
| I-05 | 1–3 concurrent analysts |
| I-06 | Geo question: "who was with X" (co-location) is the driver |
| I-07 | Inputs held: a dial digit analysis table; a blacklist database; CDR for on-net, off-net, international and GPRS; mobile money possibly later (keyed by wallet id **and** MSISDN, both available) |
| I-08 | GPRS: analysing visited sites is wanted; the feed's contents and volume are unknown |
| I-09 | User-defined exclusion lists (call center, VIP, toll-free, …) to shrink result sets |
| I-10 | Free to pull the full data from Hive and lay out our own Parquet partitions |
| I-11 | Domain-specific parts are separate from the core, plugin-style; several active at once; shipped only by us; FX has no design yet |
| I-12 | The operator decides the open decisions later; this plan must not wait on them where it can avoid it |

**Vocabulary (binding — `docs/GLOSSARY.md`).** Your term → the canonical one used here and in all later work:

| You said | Canonical |
|---|---|
| blacklist database | **Entity List**, purpose `block` (assurance D-P10: one kind) |
| exclusion filter list | **Entity List**, purpose `exclusion` |
| dial digit analysis table | **Reference** |
| pull from Hive | a **Collector** feeding a **Pipeline** |
| layer (earlier brainstorm) | **link kind** (the existing `linkKindCol` concept) |
| plugin / pack | **Domain Profile** — *proposed*, see LDP-D7 (⚠ "pack" already means pattern pack, job pack and content pack) |

### 1.2 Assumptions to verify (each is closed by a fact in §10)

| ID | Assumption | Closed by |
|---|---|---|
| A-01 | The ~10⁹/day **includes** GPRS records (data records are usually the majority); voice+SMS may be far lower | F-01 |
| A-02 | Distinct (A,B) pairs per day are 2–4× fewer than voice+SMS CDRs | F-02 |
| A-03 | Average distinct contacts per subscriber over 30 days is ~30–50, with a heavy tail (call centers, OTP senders) reaching 10⁵–10⁶ | F-03 |
| A-04 | Active subscribers per day are tens of millions; presence tuples (cell × 15-minute bucket) per subscriber per day are ~10–30 | F-02, F-05 |
| A-05 | One reference host with ≥ 64 GB RAM and NVMe storage; operating system unknown | F-13 |
| A-06 | Hive can aggregate per day and write Parquet to a location the platform can read | F-12, SP3 |
| A-07 | CDR timestamps are in one operator time zone; the day boundary is that zone's midnight | F-16 |
| A-08 | An investigation needs a 30–90 day look-back | F-14, F-15 |
| A-09 | MSISDNs are re-issued after a quarantine of ≥ 90 days | F-11 |

## 2. Where we stand — the grounded baseline (verified 2026-10-01; rows 4, 5, 8, 9, 16, 18 re-verified 2026-10-02)

| # | Area | State | Evidence |
|---|---|---|---|
| 1 | Read path | Every LA read is a registered Dataset → `DatasetRead.relationSql` → an ephemeral sandboxed DuckDB per request. **No persisted index**; each call re-aggregates the flat relation | `inspecto-engine/src/main/java/com/gamma/query/QueryExecutor.java`, `inspecto-geo-link/.../InvRoutes.java` |
| 2 | Server expansion | Projection, neighbours, multi-mapping (≤ 16), recursive paths (5 s, depth 6, max 10), branching pattern, and the Investigation `expand` rung (min events, min distinct days, degree band, max fan-out, direction). **The SPA sends none of the rung fields** | `InvRoutes`, `InvestigationRoutes`; BACKLOG `LA-SPA-OWED-SURFACES-1` |
| 3 | Measured scale | One-hop p50 at 10⁸ rows: 43 ms (64-bucket entity-hash, sorted) vs 1 031 ms flat. Recursive CTE at 10⁸: ~3 s at depth 4. **Corpus: median out-degree 3, p99 ≈ 39, one key, no concurrency; 10⁹ is extrapolated** | feasibility plan §7.10.1; `inspecto-geo-link/src/test/java/com/gamma/control/InvTraversalBench.java` |
| 4 | Server graph algorithms | **Wired since D-4**: `GraphRunService` runs 28 algorithms on an in-memory engine through `/inv/graph/*`. Input is an already-materialised **Working Set** only (neutral `GraphInput`, "an edge index at D-3"); default budget 50,000 nodes / 500,000 edges / 30 s, hard ceilings 500,000 / 5,000,000 / 300 s; result lists cut at 10,000 with stated totals; an over-budget input ends `BUDGET_EXCEEDED` before any work. Betweenness, closeness and suspicion time out at 10⁴ nodes | `inspecto-la-core` `GraphRunService`, `inspecto-la-api` `GraphRunRoutes`, `inspecto-la-graph` |
| 5 | Entity Lists | Space-scoped, typed; purposes allow, block, watch, exclusion; prefix/range/CIDR entries; per-entry expiry; Parquet sidecar. Stored as a hash-chained fact log (one JSON file per fact), replayed uncached on every read; sealed lists hold ≤ 5,000 exact members; **list keys are not pruned in SQL** | `EntityRegistry`, `EntityListSidecar`, `EntityListEntries` — now in `inspecto-entity-store`; routes `/entity-lists` in `inspecto-entity-list` (SEP-08, done) |
| 6 | Hide / exclude / keep | Exist. **No barrier** (reach but do not traverse through), **no global degree ceiling, no result gate** (reads truncate with a flag; derived reads answer 422) | `InvestigationEvaluator`, link-analysis OKF |
| 7 | Identity | Deterministic assertions; "as-of" is the recording position, not valid time; bulk import ≤ 10,000 rows. The platform's Reference supports `scd2` loads | `link-analysis-entity-model-design.md`; `PipelineConfig` `reference.load` |
| 8 | Geo | `/geo/projection` is a lat/lon point fold; `/geo/routes` a group-by. Co-location, stay-point, heatmap and radius are **SPA-only**. No cell dimension, no H3, no spatial extension. The Dataset view check `GeoRoutes` lacked is **fixed** (`bda33a508`; the routes now live in `inspecto-la-api`) | `GeoRoutes` |
| 9 | Telecom assumptions in "generic" code | `inspecto-la-core` and `inspecto-la-graph` hold **none**. What remains: `EntityTypes.DEFAULTS` (now in `inspecto-entity-store`), `ValueMeasures` (money-flow, structural on an `agent` list; now in `inspecto-la-api`), the SPA `domain-profile.ts`, and the `e164` normaliser (no country inference) | grep of main code, 2026-10-02 |
| 10 | Acquisition | Collectors: sftp, ftp, ftps, db, s3, kafka, azure, gcs, local. **No Hive, no Kerberos.** `db` ships the PostgreSQL driver only, writes an all-string CSV, sets no fetch size. `parsing.frontend: parquet` reads Parquet but lands every column as VARCHAR | `DbExportConnector`, `BuiltinParsers` |
| 11 | Parquet writer | `COPY … PARTITION_BY`; zstd allowed; partition types VARCHAR, DOUBLE, INTEGER, DATE_*. **No bucket, in-file sort, row-group size, file-size target or bloom control** | `PartitionWriter`, `PartitionDef` |
| 12 | Idempotency | A same-named file is overwritten per touched partition. A re-run touching fewer partitions leaves stale files; a differently named file duplicates rows. Ledger defaults to `memory` (watermark lost on restart). No multi-day backfill | `ConsignmentIngestStrategy`, `OperationalDb` |
| 13 | Enrichment | Reference (replace, upsert, scd2) read as a DuckDB view; `transform.join` is equi-only; Stage-2 SQL is arbitrary guarded SQL | `ReferenceReader`, `RowShaper`, `EnrichmentEngine` |
| 14 | Retention | A library of maintenance tasks, none scheduled by default; legal hold only on Incidents; cold/worm tags are labels | `MaintenanceJob`; `compliance/evidence/retention-configuration.md` |
| 15 | DuckDB | 1.5.2.1. Staged: excel, ducklake, postgres_scanner, httpfs, aws. Not loaded: spatial, h3, vss, duckpgq (no build for 1.5.2). `spatial` is a *deferral* for zero demand, not a refusal | `DuckDbExtension`; BACKLOG lines 350 and 561 |
| 16 | Governance and operations | `/inv` and `/geo` reads: no capability gate, no Dataset row scope, raw ids on stateless reads (`LA-GRAPH-RUN-MASK-ORACLE-1` is the graph-run instance), LA events outside the audit chain, **outside the rate limiter** (only graph runs have a bounded executor); no Job cancel (graph runs cancel cooperatively); backup scope silent on `audit/`; the geo view check is fixed | `okf/capabilities/security/security.md` |
| 17 | Ingest throughput | 69–81K rows/s end to end (6-core laptop, 12M rows, 100 columns); 523K rows/s native CSV at 12 columns. ≥ 2.5B/day on 8 cores is **unmeasured** | `okf/backend/build-run/performance.md` |
| 18 | Modularisation | **D-1 done** 2026-10-01 (steps 1–7; 41/41 modules, 7,319 tests): `inspecto-la-core`, `-la-api`, `-la-graph`, `-entity-list`, `-entity-store`, `-auth-spi`, `-http-spi`, `-audit-spi`; the bridge stays `inspecto-geo-link`; two ports (`DatasetProvider`, `CasePort`). **D-4 done**, ahead of D-3. **D-3 (the index) not started and unscheduled**; D-5 only its prep | D-1 design; feasibility plan §7.8 |
| 19 | Existing "domain" pieces | Per-Space Entity Types (`link-analysis.toon`, ≤ 64), SPA `domain-profile.ts` (labels and suggestions only), per-Space pattern packs (TOON, no authoring UI), Space Templates (telco-fraud, telco-ra, payment-fraud, business-assurance), job packs with a SHA-256 allowlist | OKF, `spaces.md` |
| 20 | Module enablement | Editions are build-time; ServiceLoader is JVM-wide. **No per-Space enablement** | `docs/EDITIONS.md` |

## 3. Fit with signed decisions

| Signed | What this plan does with it |
|---|---|
| D1, D2, D14 (own data first; one SKU; Geo always included) | Builds the own-data index first; Geo is WP6 inside the same product |
| D4 (data in through Pipeline templates) | WP2 delivers the Pipeline capabilities and templates; no upload route |
| D7 + D-1 Decision 4 (Entity Lists out of LA, before step 5) | Done (SEP-08). The list design (§7.6) builds on `inspecto-entity-store`; the index list table is *derived* from the sidecar, never a second truth |
| D9, D13 (DuckDB SQL + index first; vector later) | Followed. DuckPGQ was **dropped** by the operator (D-S2); an external graph database and vectors are Coulds (C02, C03) |
| D10 (partitioning: decide from D-S1 on a **realistic corpus**) | SP2 supplies the realistic corpus — D-S1's had median out-degree 3 |
| D11 (incremental append by partition; retention inherited) | Adopted. Legal hold, residency and erasure are unanswered → M15, LDP-D11 |
| D12 (external IAM) | Unchanged |
| **D21 (20 analysts, 50 Drafts, 10⁹ edges)** | ⚠ In tension with I-02 and I-05 and with the sizing in §7.3 → LDP-D1 |
| D-4 decisions 1–7 (signed 2026-10-01) | The Graph Run is the analyst's engine for a materialised result; its hard ceilings (500,000 nodes / 5,000,000 edges) leave 10⁹ to an index-backed engine — which is WP3 |
| D-1 Decision 6 (two SPI modules, the cycle broken) | No effect; module names in this plan follow the built `inspecto-*` names |
| D-M1 (deterministic identity), D-M8 (one concept, one store), D-M9 (sealed normaliser) | Respected: no fuzzy merge; the list table is derived from the sidecar; a profile-supplied normaliser is a new sealed id |
| Closed operation vocabulary (no twelfth op without a decision) | Barrier is a **field on the `expand` rung**, not a new operation |
| Standing refusals LA-06, LA-07 (viewport culling, Web Worker) | Honoured; canvas caps unchanged (LDP-D2) |
| D-U8 (no purge of Investigation evidence) | Conflicts with erasure → LDP-D11 |

## 4. Requirements register — MoSCoW

State: ✅ exists · 🟡 partial · ⛔ missing. WP = work package (§6). Gate = decision that must be signed before the
decision-dependent part is built (§8).

### 4.1 Must

| ID | Requirement | State today | WP | Gate |
|---|---|---|---|---|
| M01 | Land Hive CDR into Parquet at ~10⁹ rows/day **without moving raw rows through JDBC**: Hive-side aggregation per record type and day, delivered as Parquet files to a landing location the platform ingests | ⛔ no Hive connector or Kerberos; `db` Collector is PostgreSQL-only, all-string CSV, no fetch size | WP2 | LDP-D6 |
| M02 | Writer controls for the index: hash-bucket partition, in-file sort, row-group size, target file size | ⛔ only in the benchmark; `PartitionWriter` has none; not shown that `PARTITION_BY` preserves `ORDER BY` | WP2 | — |
| M03 | Idempotent partition replace (a re-run leaves no duplicate rows and no stale files), a late-data rule, multi-day backfill, a durable ledger for builds | 🟡 see §2 row 12 | WP2 | — |
| M04 | Number normalisation to E.164 with profile parameters (home country code, international and trunk prefixes), and **node-dictionary enrichment** (country, operator, number type) by longest-prefix match against the dial digit Reference — once per *new number*, never per CDR row | ⛔ `e164` does no country inference; the only longest-prefix idiom is hand-written Stage-2 SQL in the telco-fraud template | WP2 | — |
| M05 | A signed index unit, window, retention and sizing target, reconciled with D21 | ⛔ conflict, §3 and §7.3 | WP1, WP3 | LDP-D1 |
| M06 | Server-side supernode policy (a flagged hub is reached but not expanded unless the analyst asks) | ⛔ only per-rung fan-out and degree band | WP3, WP4 | — |
| M07 | Entity List pruning **inside SQL** (a derived list-membership table keyed by node id) with the barrier semantic | 🟡 `excludeBy` is Investigation-only; keys not SQL-prunable; no barrier | WP4 | LDP-D3, D4 |
| M08 | Recycling-safe identity at build time: MSISDN → subscriber resolved **as of the CDR date** from an SCD2 Reference | 🟡 `scd2` loads exist; no as-of join pattern or test | WP2, WP3 | LDP-D8 |
| M09 | Link-kind and graph-schema model: how a Dataset's columns map to link kinds of two shapes — **pair** (entity↔entity) and **incidence** (entity↔thing: cell, domain, device) — with declared measures | ⛔ open since the feasibility plan §7.13 | WP3 | — |
| M10 | Presence index and server-side "where was X" / "who was with X" (cell × time bucket, hot-cell cap, overlap threshold) | ⛔ SPA-only | WP6 | — |
| M11 | One result gate: refuse, never silently truncate, naming the hop and suggesting filters; "result" means nodes **found** | 🟡 reads truncate with a flag, derived reads answer 422. **Precedent now in the repo (D-4):** a Graph Run is refused before any work when its input exceeds the budget (`BUDGET_EXCEEDED`, naming the cap), and result lists are cut at `max_result_items` (default 10,000) with stated totals | WP4 | LDP-D2 |
| M12 | Analyst UI for the 4-hop-with-filters workflow: per-hop filters, list picker with hide / exclude / barrier, gate refusal with suggestions, ranked result table | ⛔ `inv.service.ts` types the rung fields but no UI call site sends them (`LA-SPA-OWED-SURFACES-1`); D-4 added only the Run-on-server UI | WP7 | LDP-D2 |
| M13 | A recorded scope call: investigation only, or also scheduled detection that raises Alerts | ⛔ undecided | WP9 | LDP-D5 |
| M14 | Access by sensitivity tier and read auditing; geo routes apply the same view check as `/inv` | 🟡 §2 row 16. **Geo view check: done** (`bda33a508`) | WP8 | LDP-D9 |
| M15 | Index retention, legal hold, residency and erasure | 🟡 D11 covers retention only | WP8 | LDP-D11 |
| M16 | Operations minimums: admission and rate limits on `/inv` and `/geo`; cancel and restart of a long build; backup covering `audit/` and the index; LA counters; index versioning and cleanup | 🟡 graph runs have a bounded executor, a queue (503 when full) and cooperative cancel; `/inv` and `/geo` are still outside the rate limiter; no build cancel | WP8 | — |
| M17 | Acceptance criteria and latency targets, signed before the build | ⛔ only "a stated bound" | WP1, WP8 | LDP-D10 |
| M18 | One domain seam: canonical name, per-Space enablement, multi-profile rules, built from the existing per-Space pieces | 🟡 scattered, §2 row 19; the extraction scope is now small (§2 row 9) | WP5 | LDP-D7 |
| M19 | Per-day coverage and quality gates on the build; a missing or failed day appears as a **gap** and is never answered as "no links" | ⛔ the standing refusal exists; `LA-COLLECTOR-COVERAGE-1` unbuilt | WP3 | — |
| M20 | Record-level drill-through for evidence: the underlying CDR rows of a link or node (pair, date range), capped and audited, from retained raw Parquet or on-demand Hive — **not** from the index. ⚠ Confirm with the investigators in WP0; if aggregates suffice, demote to Could | ⛔ the index keeps counts and first/last time, not per-call rows | WP2, WP3 | LDP-D6 |

### 4.2 Should

| ID | Requirement | State | WP | Gate |
|---|---|---|---|---|
| S01 | Mobile-money link kinds (transfer, cash-in/out, merchant) and agent/merchant entity types; move the money measures out of `ValueMeasures` into the profile | 🟡 measures hard-coded | WP10 | LDP-D7 |
| S02 | Incidence link kind for GPRS domains/apps (rarity weighting, popular-domain exclusion) — after F-09; privacy-gated | ⛔ | WP10 | LDP-D9 |
| S03 | Scalable batch graph features in node properties (degree, components, community, rank, hops to a flagged list). The Graph Run engine is in-memory over a Working Set (ceilings 500,000 nodes / 5,000,000 edges): whole-graph features need an array- or SQL-based engine behind the existing `GraphEngine` seam | ⛔ | WP9 | LDP-D5 (Must if in scope) |
| S04 | Freshness and coverage in the UI (data-as-of badge, per-day gaps, Draft rebase prompts) | ⛔ | WP7 | — |
| S05 | Evidence output: Dossier PDF; audited export of graphs and result tables | 🟡 only json, steps, method | WP10 | — |
| S06 | Public API: versioning, keys, rate limits (operator point 4) | ⛔ beyond the OpenAPI fragment | WP10 | — |
| S07 | Drafts, membership and promotion (D16–D20) — signed; not needed for 1–3 analysts, so after the first release | ⛔ | WP10 | — |
| S08 | A **derived** domain-neutrality guard plus a toy second profile as a test kit | ⛔ | WP5 | LDP-D7 |
| S09 | Hot-cell exclusion computed from cell popularity; rarity weighting for incidence links | ⛔ | WP6 | — |
| S10 | Per-query cost limits beyond admission control | ⛔ | WP8 | — |
| S11 | Valid-time (from/to) on analyst identity assertions | ⛔ | WP10 | LDP-D8 |

### 4.3 Could

| ID | Item | Note |
|---|---|---|
| C01 | H3 or the DuckDB `spatial` extension | Cell-id equality needs neither. If wanted, compute grid keys in the build, not in the sandbox (a staged extension needs a named call site) |
| C02 | An external graph database | DuckPGQ was dropped by the operator (DuckDB 2.0's own graph features are assessed when the pin moves). The measured gap is volume, not depth. LA-exclusive if ever |
| C03 | Vector / embedding similarity | D13: only with the entity-context work |
| C04 | Weekly and monthly roll-ups | Only if the look-back exceeds the daily retention |
| C05 | Comparison mode, timeline playback, burst detection, calendar exclusions | `LA-INVESTIGATION-OPS-DEFERRED-1` |
| C06 | Remote query and federation; machine-to-machine trust | Option D phase 2 |
| C07 | Team-shared Drafts | D17 "later" |
| C08 | FX normalisers (LEI, BIC, IBAN) | Extends the closed normaliser set (D-M9 sealed) |
| C09 | Number-portability-aware operator attribution | Needs MNP data (F-11) |

### 4.4 Won't (this release) and when to reopen

| ID | Item | Reopen when |
|---|---|---|
| W01 | Direct or hybrid queries to the upstream database **for traversal** (on-demand Hive drill-through for evidence, M20, is separate) | Freshness tightens below a day |
| W02 | A distributed graph database; interactive graphs beyond 100M nodes (`studio.md` §6, the owner's own list) | The D-S3 curve shows a volume gap past 10⁹ rows per hop |
| W03 | Customer-authored Domain Profiles; a marketplace | The operator reverses I-11 |
| W04 | An FX profile build (a one-page paper sketch only, WP5) | An FX design exists |
| W05 | Streaming or near-real-time ingest | Freshness drops below hours |
| W06 | Fuzzy identity merging (D-M1) | A decision entry reopens D-M1 |
| W07 | Drawing more than ~2K nodes on the canvas; viewport culling and a Web Worker (LA-06, LA-07) | A demand trigger in `docs/BACKLOG.md` fires |

## 5. Priority list (ranked)

**Ranking rule.** (1) retires the largest unknown, (2) unblocks the most other items, (3) Must before Should, (4) within
a class, no-decision before decision-gated. **Size** is a single-lane estimate, unmeasured: S ≤ 2 days · M ≈ 1 week ·
L ≈ 2–3 weeks · XL > 1 month.

| # | Item | Class | Size | Gate | Wave | Start now |
|---|---|---|---|---|---|---|
| 1 | Facts brief F-01…F-16 handed to the data engineers (§10) | enabler for M05, M17 | S | — | 0 | ✅ |
| 2 | **SP1** — real writer: bucket + sort + row group (M02) | Must | M | — | 0 | ✅ |
| 3 | **SP2** — skewed-corpus traversal, daily tables vs window table, `EXPLAIN` of the frontier join | Must (M05, M06) | M | — | 0 | ✅ |
| 4 | **SP3** — Hive extraction and landing throughput (M01) | Must | M | needs Hive access | 0 | ✅ |
| 5 | **SP4** — list pruning at scale; fact-log replay cost (M07) | Must | S | — | 0 | ✅ |
| 6 | **SP5** — node dictionary build and longest-prefix enrichment (M04) | Must | M | — | 0 | ✅ |
| 7 | Rate-limiter coverage of `/inv` and `/geo` (M16 part); the geo view check already landed | Must | S | — | 0 | ✅ |
| 8 | Doc fixes §14 and GLOSSARY entries for signed terms | enabler | S | — | 0 | ✅ |
| 9 | **SP6** — presence sizing and co-location (M10) | Must | M | after F-02, F-05 | 0 | ✅ |
| 10 | **Decisions LDP-D1, D2, D6** (operator) | — | — | — | 0–1 | — |
| 11 | M03 — idempotent partition replace, late data, backfill, durable ledger | Must | M | — | 1 | ✅ |
| 12 | M02 — writer controls in the product | Must | M | SP1 | 1 | after SP1 |
| 13 | M04 — E.164 normalisation + node-dictionary enrichment | Must | M | SP5 | 1 | after SP5 |
| 14 | M01 — Hive acquisition path | Must | M–L | LDP-D6 | 1 | — |
| 15 | M08 — build-time as-of identity (SCD2 Reference) | Must | S–M | LDP-D8 (soft) | 1 | — |
| 16 | M05 + M09 — freeze index schemas and manifests | Must | M | LDP-D1, SP1, SP2 | 2 | — |
| 17 | M19 — coverage and quality gates in the build | Must | M | — | 2 | — |
| 18 | WP3 builder — the daily incremental index job (D-3 content). **Now the critical path: D-3 is unstarted and unscheduled** | Must | L | SP1, SP2, LDP-D1 (D-1 is done) | 2 | — |
| 19 | M06, M07 — supernode policy, SQL-prunable lists, barrier | Must | M–L | LDP-D3, D4 | 2 | — |
| 20 | M11 — the result gate | Must | M | LDP-D2 | 2 | — |
| 21 | M10 — presence index and server-side co-location | Must | L | SP6 | 2 | — |
| 22 | M12 — analyst UI for the hop ladder, lists, gate, ranked table | Must | M–L | server fields shipped | 2–3 | — |
| 23 | M20 — evidence drill-through | Must (confirm) | M | LDP-D6 | 2–3 | — |
| 24 | M18 — Domain Profile seam and per-Space enablement | Must | M | LDP-D7 | 3 | — |
| 25 | Telecom profile extraction (`EntityTypes.DEFAULTS`, `ValueMeasures`, `domain-profile.ts`, normalisers) | Must | M | LDP-D7 | 3 | — |
| 26 | S08 — derived neutrality guard + toy second profile; FX paper sketch | Should | S–M | LDP-D7 | 3 | design ✅ |
| 27 | M14, M15, M16 — tiers, read audit, retention/hold/erasure, operations | Must | L | LDP-D9, D11 | 4 | parts ✅ |
| 28 | M17 — the acceptance run | Must | M | LDP-D10 | 4 | — |
| 29 | M13 + S03 — detection scope; scalable batch features | Must / Should | L–XL | LDP-D5 | 5 | — |
| 30 | S01 mobile-money profile · S02 GPRS incidence · S04–S07, S09–S11 · Coulds | Should / Could | — | various | 5 | — |

## 6. Work packages

Mapped onto the signed phases: WP1 ⇒ D-2; WP3 ⇒ D-3; WP4 and WP9 ⇒ D-4; WP2, WP5, WP6, WP7, WP8 are this plan's own. Each
package ends with a **Done when** that is a check, not an intention.

### WP0 — Requirements closure and hygiene (Wave 0, start now)
- Hand §10 (facts F-01…F-16, queries in Appendix A) to the data engineers; record answers in a "data profile" section here.
- Apply the §14 fixes; enter the signed-but-missing GLOSSARY terms (Working Set, Dossier, Snapshot, Draft, Purpose, Masking mode,
  Value Measure). Terms proposed by this plan (Domain Profile, link-kind shapes) wait for LDP-D7.
- Cross-check M20 and M14 against the local RFP gap register (G-20 evidential controls) **if the git-excluded folder is present**.
- **Done when:** the vocabulary, doc-link and citation guards are green and every §14 claim has been re-grepped.

### WP1 — Spikes (Wave 0)
- SP1–SP7 (§9). Each appends a numbered result block to §9 and updates the decision it feeds.
- **Done when:** every spike has a measured pass/fail against its stated condition — a curve or a table, never an opinion.

### WP2 — Platform data preparation (Waves 0–1; M01–M04, M08, M20 part)
No dependency on the D-1 extraction; all of it is useful to Inspecto without LA.
1. **Writer layout** — hash-bucket partition type, in-file sort, row-group size and file-size target on the one `copyOpts`
   seam in `PartitionWriter`, plumbed through config validation and `SafetyPolicy`.
2. **Deterministic partition replace** — a replace mode that removes the touched partition's prior files after the new ones
   are staged; late data re-runs the day; backfill is a per-day re-run; the ledger backend for builds is durable.
3. **Number normalisation** — a step or expression taking home country code and prefixes, property-tested on a corpus of
   formats (+, 00, national, short codes).
4. **Node-dictionary enrichment** — distinct numbers only: generate candidate prefixes for the lengths present in the dial
   Reference, equi-join, keep the longest. (A `starts_with` join over CDR rows would be a nested loop and is ruled out.)
5. **Acquisition** — per LDP-D6: (a) Hive-side aggregation → Parquet files → a landing Collector, or (b) a Hive JDBC Collector
   with Kerberos and typed streaming. Raw CDR is retained for drill-through (M20) per the same decision.
- **Done when:** a product test shows sorted row groups survive the partitioned write; re-running a day leaves identical
  row counts and no duplicate or stale file; a known corpus normalises and enriches correctly; the chosen acquisition path
  lands one real day end to end.

### WP3 — The index, `la-storage` (Wave 2; D-3 content; M05, M06, M08, M09, M19) — **critical path** (working name; the built modules are `inspecto-la-*`)
- Schemas and manifests of §7.2, the daily build of §7.4 as **one builder class used two ways** (a Pipeline node type through
  the bridge and an `la-app` job), per the feasibility plan §7.4.
- Coverage manifest per day and table; the day becomes visible only when its manifest is written, last.
- The Graph Run's neutral `GraphInput` is the seam: an index-backed input loads a **subgraph** (a frontier result), so the analyst's ≤ 10K-node answer reuses the shipped engine; only whole-graph features (S03) need a new engine.
- **Done when:** the one-day acceptance build passes AC-01 and AC-02; a deliberately dropped record type produces a visible
  gap in the next traversal; the index dependency rule (D-1 decision 5) is green.

### WP4 — Traversal and result control (Wave 2; M06, M07, M11)
- Hop ladder over the index (§7.5); supernode policy; derived list-membership table; barrier as an `expand` field; the gate.
- Every new route clears the four gates (authorise, capability, write gate, `openapi-v1.json`) — see the `endpoint` skill.
- **Done when:** AC-03, AC-04, AC-07 and AC-08 pass on the skewed corpus; a mutation test proves hidden nodes spend no budget.

### WP5 — Domain Profile seam and the telecom profile (Wave 3; M18, S08, W04)
- Name and mechanism per LDP-D7 (§7.9); extract the telecom assumptions of §2 row 9 into a profile; a derived guard; a toy
  second profile that loads alongside; the FX paper sketch (one page).
- **Done when:** AC-09 passes — core carries no word derived from a shipped profile, a second profile loads alongside telecom,
  and a collision fails closed at load.

### WP6 — Geo presence (Wave 2; M10, S09)
- Presence tables in two sort orders, the cell Reference, server-side "where was X" and "who was with X", hot-cell cap.
- **Done when:** AC-06 passes; a cell with a planted crowd is excluded from the answer and the exclusion is reported.

### WP7 — Analyst UI (Waves 2–3; M12, S04)
- Per-hop filters, list picker with the three modes, gate refusal dialog with suggestions, ranked result table, data-as-of badge.
  Follows the `angular-ui` conventions; verified in the preview.
- **Done when:** the 4-hop-with-filters scenario of I-04 is run end to end by clicking, and the refusal path is exercised.

### WP8 — Governance and operations (Wave 4; M14, M15, M16, M17)
- Sensitivity tiers as separately registered Datasets so the existing view check and policy apply (§7.11); read auditing for
  sensitive tiers into the chain; stateless reads honour the masking mode; retention, hold, residency, erasure per LDP-D11;
  cancel and restart of builds; backup scope; counters; disk quotas.
- **Done when:** AC-08 and AC-10 pass and the acceptance run (AC-01…AC-10) is green on the reference host.

### WP9 — Batch features and detection (Wave 5; M13, S03; gated by LDP-D5)
- If in scope: scalable features (an index-backed engine behind the Graph Run's `GraphEngine` seam) into the node properties → Risk Score feed → Alert → Case → Investigation seed.
  If out: the node properties are filter and seed attributes only.

### WP10 — Extensions (Wave 5)
- S01 mobile money · S02 GPRS incidence · S05 evidence output · S06 public API · S07 Drafts · S11 valid-time assertions, then
  the Coulds on demand.

## 7. Design (PROPOSED — confirmed or changed by the §9 spikes)

### 7.1 Layers
```
Hive (system of record)
  └─ Hive-side aggregation per record type and day  ──►  Parquet files in a landing location        (M01)
bronze   landed files as delivered, partitioned by dt; kept N days for replay and drill-through      (M20)
silver   normalised numbers, quality gates, ids assigned from the node dictionary                    (M04, M19)
gold     the index: node_dict · edge_daily (+ mirror) · node_props · list_member · presence ×2 · manifest   (§7.2)
```
Aggregating in Hive moves ~10⁸ rows a day instead of 10⁹, which is what makes the measured ingest rate (§2 row 17) sufficient.

### 7.2 Index tables

| Table (working name) | Grain | Partition and sort | Notes |
|---|---|---|---|
| `node_dict` | one row per entity (type, normalised key) | bucket by hash of the key | dense BIGINT node id; first/last seen; for numbers: country, operator, number type from the dial Reference |
| `edge_daily` (one per link kind) | (dt, a, b) | dt, bucket by `a`, sorted (a, b) | count, declared measures, first/last timestamp. **A mirrored copy keyed by `b`** serves reverse lookups |
| `edge_window` *(optional)* | (window, a, b) | bucket by `a`, sorted | built **only if SP2 shows daily tables miss AC-04**; maintained incrementally with a per-pair day bitmap |
| `node_props` | one row per node | bucket by node id | degrees per link kind, distinct days, supernode flag, batch features, as-of |
| `list_member` | (list id, node id) | by list id | derived from Entity List sidecars; pattern lists are re-derived **daily** (new numbers appear) |
| `presence_by_node` | (dt, node, cell, time bucket) | dt, bucket by node, sorted (node, bucket) | events, dwell seconds |
| `presence_by_cell` | the same rows | dt, sorted (cell, bucket) | second sort order for co-location |
| `cell_dim` | one row per cell | — | a Reference: coordinates, site, sector; versioned |
| `manifest` | (dt, table) | — | row counts, quality verdict, input fingerprint; immutable name per build (D-7 baselines) |

Notes. The daily day boundary is the operator time zone (A-07), sealed as the time-zone contract the Investigation already
uses. The adjacency-list alternative (one row per node holding a list of neighbours) is **not** proposed: a supernode's list
would not fit a row. Flat pair rows sorted by node are robust; SP2 may still measure the alternative.

### 7.3 Sizing model (order of magnitude — A-01…A-04 unverified)

Daily pair rows `P = R / k`, where `R` is voice+SMS CDRs per day and `k` is CDRs per distinct (a, b, day) pair (est. 2–4). Both
directions are stored, so rows per day `= 2P`; compressed width ≈ 12–16 bytes.

| Scenario (illustrative) | R | P per day | Rows per day (×2) | 90 days | Storage |
|---|---|---|---|---|---|
| Voice+SMS is half of the 10⁹ | 5×10⁸ | 2×10⁸ | 4×10⁸ | 3.6×10¹⁰ | ~450–580 GB |
| Everything is voice+SMS | 10⁹ | 4×10⁸ | 8×10⁸ | 7.2×10¹⁰ | ~0.9–1.2 TB |

- **D21 says 10⁹ edges.** Daily-grain edges at this volume pass that in about two days (⚠ D21 does not say whether mirrored rows
  count). Either the target is re-stated for this deployment, or the unit indexed changes (a window table, shorter retention) —
  **LDP-D1**.
- **Presence is probably the largest table.** Illustratively 3×10⁷ active subscribers × 15 tuples = 4.5×10⁸ rows a day, ×2 sort
  orders ≈ 10 GB a day; at 14 days retained ≈ 140 GB. Its bucket size and retention are sizing decisions in their own right (F-05).
- **Node dictionary** ≈ 2×10⁸ numbers × ~25 bytes ≈ 5 GB; resident memory during the daily join is the SP5 question.
- **Bronze** ≈ 80–120 GB a day if raw rows are retained (est.); 30 days ≈ 3 TB. This is the cost of M20 by retained files.
- **Build time:** 4×10⁸ aggregated rows at the measured 69–81K rows/s end to end ≈ 85–100 minutes before the writer work;
  narrower rows run faster (§2 row 17). The initial backfill of W days is a separate, one-off cost (R-07).

### 7.4 The daily build
1. **Wait** for the Hive partition for day D; run the Hive-side aggregation; land the files (M01).
2. **Silver:** normalise numbers; run the quality gates (M19: row-count drop against the trailing median, null-key share,
   duplicate rate, record-type completeness) — fail closed or quarantine, never publish a thin day silently.
3. **Dictionary:** upsert new numbers; enrich the new ones from the dial Reference (M04); resolve subscriber as of D (M08).
4. **Gold:** map ids; write `edge_daily` and its mirror, both presence orders, per M02 layout.
5. **Refresh** `node_props` and `list_member`; update `edge_window` if it exists.
6. **Publish:** write the manifest **last**, atomically; a day is visible only when its manifest exists.
Each stage is idempotent through partition replace (M03), so a failed build restarts from the failed stage (M16).

### 7.5 Traversal over the index
- One hop per query from the server, not one recursive statement: after each hop the server checks the budget, applies the
  lists and the gate, and can stop early.
- Hop *k*: take the frontier ids; read the mirrored/forward edges with the bucket predicate computed by the reader (the D-S1
  lesson: without it the layout does not prune); apply the per-hop filters (link kinds, minimum count, distinct days, date
  range, direction, degree band, maximum fan-out per frontier node); drop `exclusion`-list members **before** they count toward
  degree or budget; admit the rest; compute the next frontier by removing `barrier`-list members and supernodes.
- **Barrier** = admitted and visible, never expanded. It is a field on the `expand` rung (`barrierListIds`), so the closed
  operation vocabulary is untouched.
- **Supernode policy** is a computed list (`auto` supernode per link kind, threshold from the profile) used as a barrier by
  default; the analyst can expand one explicitly.
- **Gate** (default 10,000 nodes *found*, a per-Space setting): when a hop would exceed it, refuse with the hop number, the
  count, and ranked suggestions (largest contributing frontier nodes, link kinds, date range). Never truncate silently.
- Temporal constraints (time-of-day, ordering) read `edge_daily` within the date range; unconstrained expansions may read
  `edge_window` if it exists.
- **House style for caps (from D-4, as built):** size is checked **before** work and the refusal names the cap; result lists are cut with total, returned and limit stated; a request above a ceiling is clamped and echoed. The gate above follows the same three habits.

### 7.6 Lists
- The Entity List fact log stays the truth for analyst-curated lists (hash-chained evidence, ≤ 5,000 sealed).
- The Parquet sidecar is already the SQL-facing form. The index derives `list_member (list, node id)` from it — prefix, range
  and CIDR entries resolved against `node_dict` — so the hop query is an anti-join on **active list ids**. A bitmask on the node
  table is an optimisation to add only if SP4 shows the anti-join dominates a hop.
- **Computed lists** (supernodes, hot cells, money agents) are produced by the build and written as sidecars with a `computed`
  provenance; analysts cannot edit their entries but can opt out per query.
- **Open risk:** fact-log replay is uncached per read (R-09); SP4 measures it at the list counts we expect.

### 7.7 Identity and number normalisation
- Normalise to E.164 **before** a node id is assigned (M04); the dictionary key is (type, normalised key).
- Subscriber identity for own-network numbers is resolved **as of the CDR date** from an SCD2 Reference (M08), so a re-issued
  number lands on a different subscriber node in each period. Analyst assertions stay deterministic and recording-time (S11 adds
  valid time later).
- The wallet ↔ MSISDN map (mobile money) is the same mechanism: one SCD2 Reference per identity pair.

### 7.8 Presence and co-location
- Presence rows come from the CDR's cell fields (and GPRS sessions later); Hive aggregates to (node, cell, bucket) per day.
- "Who was with X": take X's (cell, bucket) rows for the date range; join `presence_by_cell` on the cell and the bucket ±1;
  drop (cell, bucket) pairs whose distinct-node count exceeds the hot-cell cap; require a minimum overlap; anti-join the active
  exclusion lists; apply the gate.
- Same-cell is weak evidence: the cap, the minimum overlap and the reported exclusions are part of the answer, not options.

### 7.9 The Domain Profile (name and mechanism: LDP-D7)
A Domain Profile is the one thing that carries everything domain-specific, so the core carries none of it:

| A profile provides | Telecom example | FX prompt for the paper sketch |
|---|---|---|
| Entity types and normalisers | msisdn, imsi, imei, cell | account, LEI, BIC, counterparty |
| Link kinds (pair and incidence) | voice on-net/off-net/international, SMS; incidence: cell, device | trade, payment, ownership, shared beneficiary |
| References | dial digit table, cell dimension, IMSI↔MSISDN (scd2) | BIC country, sanctions, account↔LEI |
| List seeds and computed-list detectors | call center, VIP, toll-free; supernode, hot cell | market maker, prime broker |
| Patterns and Value Measures | call-forwarding relay, SIM box, wangiri, IRSF | wash trade, round trip, layering |
| Privacy defaults and UI labels | per link kind | per link kind |

- **Declarative first**: a TOON document per profile; Java only for normalisers and detectors, through the existing
  ServiceLoader mechanism. No new loading machinery.
- **Effective configuration** = the profile baseline overlaid by the Space's `link-analysis.toon`; Space settings win; one
  validation at load. This keeps D-M8 (one concept, one store).
- **Several profiles at once** (I-11): an entity type is **owned by exactly one profile** and others declare `requires`;
  link kinds are namespaced `<profile>.<kind>`; the sum of entity types stays within the existing limit of 64; any collision
  fails closed at load (422, never clamped); a profile-supplied normaliser is a new sealed id (D-M9).
- **Per-Space enablement** is a `profiles` list in the Space's LA settings — the one piece that does not exist today (§2 row 20).
- **Derived guard** (S08): the banned-word list for the core is *computed from the shipped profiles' manifests*, never
  hand-kept — this repo has recorded the hand-kept mirror failing four times.
- Space Templates (telco-fraud, telco-ra, payment-fraud, business-assurance) ship a Profile plus content; they do not grow a
  second mechanism.

### 7.10 Telecom profile — content (Appendix B)

### 7.11 Governance mapping
Access by sensitivity is expressed with machinery that already exists: **register each sensitivity tier of the index as its own
Dataset** (voice and SMS · presence · browsing · money) so `ComponentAccess` and, on Enterprise, the policy engine already apply
— no new capability constants (the capability manifest scanner is literal-only). Read auditing for tiers marked sensitive needs
the audit-chain types extended (BACKLOG `ASSURE-AUDIT-CHAIN-RESIDUALS-1`, item 8).

## 8. Decisions owed (operator)

⛔ None may be answered by an implementer in passing. Write the answer on the **Answer** line. "While open" says what proceeds
without a signature and why that is safe.

**LDP-D1 — Which size target governs.** D21 (20 analysts, 50 Drafts, 10⁹ edges), or this deployment (1–3 analysts, ~10⁹ CDRs a
day)? And does "edge" count mirrored rows?
*Recommendation:* keep D21 as the **product ceiling**; state a separate telecom acceptance target; index the unit SP2 proves
sufficient (daily tables, or a window table).
*Blocks:* M05, the index unit, retention, SP7. *While open:* spikes run for both numbers; schemas stay unit-agnostic. D-4's hard ceilings (500,000 nodes / 5,000,000 edges) already leave 10⁹ to the index-backed engine, so nothing built proves D21.
**Answer:** _open_

**LDP-D2 — What "result > 10K" counts.** Nodes found at the server, or nodes drawn?
*Recommendation:* the gate counts nodes **found**; the canvas keeps its caps (500 / 2,000 / 750); larger results show as a ranked
table; LA-06 and LA-07 stay refused.
*Blocks:* M11, M12. *While open:* the server gate is built regardless — it is needed under any answer. D-4 already uses 10,000 as the default cut of Graph Run result lists, so the number is not new; what is new is applying it to nodes found.
**Answer:** _open_

**LDP-D3 — Where large and computed lists live.**
*Recommendation:* fact log for analyst lists; the Parquet sidecar is the single SQL-facing form; computed lists write a sidecar
with `computed` provenance; the index derives `list_member` (§7.6). Respects D-M8.
*Blocks:* M07. *While open:* build the derived table off the sidecar; an alternative is a later add-on, so regret is low.
**Answer:** _open_

**LDP-D4 — How a barrier is expressed.**
*Recommendation:* a field on the `expand` rung, not a new operation (the vocabulary is closed).
*Blocks:* M07. *While open:* as D3 — the field is additive.
**Answer:** _open_

**LDP-D5 — Detection in scope?** LA as an investigation tool only, or also scheduled scoring that raises Alerts that seed
Investigations (RA/FM)?
*Recommendation:* investigation first; expose node properties as filters and seeds; add detection only once the Risk Score →
Alert → Case path is chosen as the consumer.
*Blocks:* WP9, S03. *While open:* Waves 0–4 are unaffected.
**Answer:** _open_

**LDP-D6 — How data leaves Hive, and where record-level evidence comes from.** (a) Hive-side aggregation → Parquet files →
landing Collector; or (b) a Hive JDBC Collector with Kerberos. And for M20: retained raw Parquet, or on-demand Hive?
*Recommendation:* (a) for traversal data — it avoids Kerberos JDBC and CSV, and moves ~10⁸ rows instead of 10⁹; for evidence,
retain raw Parquet for the investigation look-back, bucketed by A-party, unless F-12 shows on-demand Hive is fast enough.
*Blocks:* M01, M20. *While open:* the landing side (M02–M04) proceeds; SP3 measures both.
**Answer:** _open_

**LDP-D7 — The domain seam: name and mechanism.**
*Recommendation:* extend the existing **Domain Profile** (today SPA labels only) into a server-and-UI concept declared per Space
in TOON; Space Templates ship Profiles; enter Domain Profile, pair link kind and incidence link kind in the GLOSSARY first.
*Blocks:* M18, S01, S08, WP5. *While open:* design and the FX paper sketch proceed; no code introduces the term.
**Answer:** _open_

**LDP-D8 — Identity validity model.**
*Recommendation:* build-time as-of resolution from an SCD2 Reference for bulk maps (M08); the fact log keeps analyst assertions;
valid time on assertions is S11.
*Blocks:* M08 detail. *While open:* the SCD2 pattern is the recommended default and is cheap to change.
**Answer:** _open_

**LDP-D9 — Access by sensitivity tier.**
*Recommendation:* tiers as registered Datasets (§7.11); sensitive tiers audited into the chain; stateless reads honour the
masking mode.
*Blocks:* loading browsing (S02) or money (S01) data; M14 completion. *While open:* the voice/SMS tier proceeds on today's
Dataset-level access.
**Answer:** _open_

**LDP-D10 — Acceptance criteria (§11).** Sign the numbers or replace them.
*Recommendation:* adopt §11 as the starting targets.
*Blocks:* M17 and the final run. *While open:* spikes use §11 as their pass lines; changing a number changes only pass/fail.
**Answer:** _open_

**LDP-D11 — Retention, legal hold, residency, erasure.** Index retention default (D11 says inherited), legal hold on index
partitions, residency statement, and — if the jurisdiction requires erasure — how it fits the hash-chained fact log (D-U8
refuses purge).
*Recommendation:* retention inherits; a hold flag makes maintenance tasks skip a partition; erasure needs legal input first.
*Blocks:* go-live with real customer data. *While open:* development uses synthetic or profiled data only.
**Answer:** _open_

## 9. Spikes (measure before freezing the design)

| Id | Question | Method | Pass condition | Feeds |
|---|---|---|---|---|
| SP1 | Does the **real** writer produce bucketed, sorted, row-group-tuned files? | One synthetic day (~2×10⁸ rows) through the `PartitionWriter` seam with bucket, `ORDER BY`, row-group size; read the Parquet metadata | Row groups sorted and non-overlapping per file; files 128–512 MB; a one-key lookup reads one file; bloom presence recorded | M02, LDP-D1 |
| SP2 | Do traversal times hold on a **CDR-shaped** skew, and are daily tables enough? | Reuse `InvTraversalBench`; power-law corpus with planted hubs of 10⁵–10⁶ contacts, at 10⁸ rows and generated to 10⁹ if feasible; compare daily tables with a date range against a maintained window table; 1–4 hops with filters; `EXPLAIN ANALYZE` | AC-03 and AC-04 met; **whether the frontier join is pushed below the pair aggregation is recorded** (nobody has checked) | M05, M06, LDP-D1 |
| SP3 | What does getting a day out of Hive cost? | Hive-side aggregation time per record type; export to Parquet; landing; platform ingest of ~2–4×10⁸ aggregated rows through `parsing.frontend: parquet` (VARCHAR retype cost); the JDBC alternative if Kerberos is available | A day lands within the AC-01 build budget; the cheaper path is named | M01, LDP-D6 |
| SP4 | What do lists cost at scale? | Anti-join of 1–50 lists with up to 10⁶ members against 10⁸ edges; fact-log replay time at the list counts expected | ≤ 25% added hop latency; replay time stated | M07, LDP-D3 |
| SP5 | Can the node dictionary and enrichment run daily? | 2×10⁸–10⁹ distinct numbers; id assignment; longest-prefix enrichment of new numbers; full re-derive on a dial-table change | Daily upsert ≤ 30 minutes; memory stated; re-derive time stated | M04 |
| SP6 | How big is presence, and is co-location fast? | Presence at 4.5×10⁸ rows a day for 7 days; "who was with X" with and without the hot-cell cap | AC-06 met; cap effect measured | M10, F-05 |
| SP7 | Does it hold under concurrency? | Extends D-S5: 3 analysts and 20 analysts, one heavy build or traversal alongside | One heavy job moves others' p95 by ≤ 2× | LDP-D1, M17 |

## 10. Facts to collect (the data profile — start now)

Owned by the data engineers; queries in Appendix A. Answers go into a "data profile" section of this file.

| ID | Fact | Closes |
|---|---|---|
| F-01 | Rows per day per **record type** (on-net, off-net, international, GPRS, SMS) | A-01, M05 |
| F-02 | Distinct A, distinct B and distinct (A, B) per day and per record type; distinct pairs over 7 days (and 30, if affordable) | A-02, A-04 |
| F-03 | Distinct contacts per subscriber over 30 days — percentiles, and the top 1,000 by distinct contacts and by in-degree | A-03, M06 |
| F-04 | B-party number formats: share by leading pattern (+, 00, 0, other) and length | M04 |
| F-05 | Null share of IMEI, IMSI and cell fields for A and B; distinct (subscriber, cell, 15-minute bucket) per day | A-04, M10 |
| F-06 | Cell dimension: rows, coordinate coverage, update frequency, site versus sector | M10 |
| F-07 | Dial digit table: rows, prefix-length distribution, versioning | M04 |
| F-08 | Blacklist database: size, key type, update cadence, format | Entity List `block` |
| F-09 | GPRS feed: columns (host, SNI, URL, app, category, IP), non-null share, rows per day, distinct subscribers and domains; any IP→domain map | S02, LDP-D9 |
| F-10 | Mobile money: transactions per day, key types, agent/merchant share, wallet↔MSISDN map and its validity | S01, M08 |
| F-11 | MSISDN re-issue: quarantine period; IMSI↔MSISDN history available; MNP data available | A-09, M08, C09 |
| F-12 | Hive: version, file format and partitioning of the CDR tables, export options (Parquet export to a directory?), Kerberos, cluster load windows, **when day D lands**, raw retention in days | A-06, LDP-D6 |
| F-13 | Reference host: cores, RAM, NVMe size and throughput, operating system | A-05, M17 |
| F-14 | Retention in Hive; required investigation look-back; legal basis; data classification of each feed | A-08, LDP-D11 |
| F-15 | Investigators: do they need call-level lines for a link as evidence? the five questions they ask most | M20, M13 |
| F-16 | CDR timestamp zone; day-boundary convention; duplicate-CDR rate and dedup key | A-07, M19 |

## 11. Acceptance criteria (PROPOSED starting points — LDP-D10)

| ID | Criterion |
|---|---|
| AC-01 | One day, at the real per-record-type volume, builds gold from landed files in ≤ 4 h on the reference host |
| AC-02 | Re-running the same day yields identical row counts, no duplicate rows and no stale file |
| AC-03 | One-hop lookup p95 ≤ 100 ms at the corpus size of SP2 |
| AC-04 | Four hops with ≥ 2 filters, p95 ≤ 5 s (the existing traversal fence), on the **skewed** corpus |
| AC-05 | When a hop would exceed the gate, the refusal names the hop, the count and suggestions within 1 s; no partial result is returned |
| AC-06 | "Who was with X" for one subject over 7 days, p95 ≤ 3 s, with the hot-cell exclusion reported |
| AC-07 | Excluded nodes spend no budget and add no degree — pinned by a test that fails if either changes |
| AC-08 | A caller denied a Dataset gets 404 from `/inv` **and** `/geo`; a read of a sensitive tier writes an audit event |
| AC-09 | Core holds no word derived from a shipped profile; a second profile loads alongside telecom; a collision fails closed at load |
| AC-10 | Three analysts concurrently, with one heavy job: the others' p95 moves by ≤ 2× |

## 12. Risks

| ID | Risk | Mitigation |
|---|---|---|
| R-01 | The Hive path is unknown; M01 may stay a manual file drop for the first release | SP3 early; the landing side is built regardless |
| R-02 | `PARTITION_BY` does not preserve `ORDER BY`, or bucket skew defeats the layout | SP1 before any schema freeze |
| R-03 | CDR skew (hubs of 10⁵–10⁶) breaks a hop; the earlier benches did not contain it | SP2 on a skewed corpus; barrier on by default |
| R-04 | "10K result" is read as a canvas promise while the canvas holds ~500–2,000 | LDP-D2; the ranked table |
| R-05 | D21 versus this deployment: over-building for 20 analysts or under-building for 10⁹ CDRs a day | LDP-D1 |
| R-06 | A second copy of sensitive data goes live before retention, hold and erasure are decided | LDP-D11 blocks real data |
| R-07 | The initial backfill of 30–90 days costs 30–90 build days if run serially | Parallel per-day builds; measure in SP3 |
| R-08 | The seam is over-generalised before a second domain exists | Config-first; mobile money is the first real second profile; FX stays a paper sketch |
| R-09 | Fact-log replay is uncached per read and becomes a cliff at list scale | SP4; cache or sidecar-only reads |
| R-10 | Peer sessions edit shared files (`docs/INDEX.md`, `docs/BACKLOG.md`) | Stage shared files by hunk; this plan touches one INDEX row |
| R-11 | Single-node ceiling (NFR-8) is hit at 10⁹ CDRs a day | SP7 and AC-01 on the reference host; the scale-out plan is the escalation path |
| R-12 | D-4 shipped ahead of D-3: the analyst-facing graph work is done, but the 10⁸–10⁹ index this deployment needs is unscheduled | Schedule WP3 right after SP1, SP2 and LDP-D1; it is the critical path |

## 13. How this plan is kept

- A decision is answered **only** by the operator writing on its **Answer** line; the date goes after the answer.
- A spike result is appended to §9 as a numbered block and updates the decision it feeds.
- When a work package ships: distill the as-built facts (decisions, seams, gotchas, deliberate deferrals) into the matching OKF
  concept, move still-open items to `docs/BACKLOG.md`, and archive this file when the last package ships. `docs/INDEX.md` is
  updated in the same change.

## 14. Documents to fix first (so the plan does not inherit contradictions)

| # | Where | Problem |
|---|---|---|
| 1 | `la-separation-feasibility-plan.md` §7.10.1 (L557) and §7.13 (L616) | The ported count was corrected to 28; the export count still disagrees (~40 vs ~45) |
| 2 | `docs/ui/link-analysis-user-manual.md` vs `okf/frontend/features/link-analysis.md` | label-hiding threshold (300 nodes vs 20 links); layouts (11 vs 14); path ceiling (10M vs 10⁶ certified) |
| 3 | `docs/stakeholders/PRODUCT_CAPABILITIES.md`, `COMPETITIVE_LANDSCAPE.md` | LA-19 (evidential controls) called "not started"; built 2026-09-24 |
| 4 | `okf/frontend/features/link-analysis.md` (~line 299) | says exclusion and reveal are client-side and unaudited; both are server operations with audit events |
| 5 | `docs/GLOSSARY.md` | still missing: Dossier, Snapshot, Masking mode, Value Measure (re-grep Purpose; Working Set, Draft and Graph Run have since been entered); "Field Classification is never a control" is contradicted by LA masking and Risk Score |
| 6 | `docs/EDITIONS.md` SEC-08; `okf/capabilities/security/security.md` | masking listed Enterprise-only though it ships in Professional; text still says `edition-standard` |
| 7 | `okf/capabilities/studio/studio.md` §6 "Won't" list | conflicts with option D's optional LA-exclusive graph database and large-graph target |
| 8 | `la-separation-execution-plan.md` | Stages 1–3 are done (D-1 and D-4 shipped); the file's own header says retire it |
| 9 | `docs/INDEX.md` row for `la-separation-d1-design.md` | still says "nothing built"; **D-1 is done** (steps 1–7, verified at `ab36c6e9c`) |

## 15. References

[`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) (§7.3 architecture, §7.4 data and graph layer, §7.9
decisions, §7.10 spikes) · [`la-separation-execution-plan.md`](../archived-documents/plans-archive/la-separation-execution-plan.md) ·
[`la-separation-d1-design.md`](../archived-documents/plans-archive/la-separation-d1-design.md) · [`link-analysis-entity-model-design.md`](link-analysis-entity-model-design.md) ·
[`assurance-capability-plan.md`](assurance-capability-plan.md) (D-P10, Entity Lists in assurance) ·
[`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) ·
[`../okf/frontend/features/geo-map.md`](../okf/frontend/features/geo-map.md) ·
[`../okf/backend/control-plane/entity-lists.md`](../okf/backend/control-plane/entity-lists.md) ·
[`../okf/backend/control-plane/risk-scores.md`](../okf/backend/control-plane/risk-scores.md) ·
[`../okf/backend/build-run/performance.md`](../okf/backend/build-run/performance.md) ·
[`../okf/backend/acquisition/connectors-runbook.md`](../okf/backend/acquisition/connectors-runbook.md) ·
[`../okf/backend/engine/consignment-addressing.md`](../okf/backend/engine/consignment-addressing.md) ·
[`../okf/capabilities/security/security.md`](../okf/capabilities/security/security.md) ·
[`../okf/capabilities/studio/studio.md`](../okf/capabilities/studio/studio.md) ·
[`../ui/link-analysis-user-manual.md`](../ui/link-analysis-user-manual.md) ·
[`../GLOSSARY.md`](../GLOSSARY.md) · [`../BACKLOG.md`](../BACKLOG.md) · [`../EDITIONS.md`](../EDITIONS.md)

## Appendix A — Hive profiling queries (placeholders in angle brackets; adapt to the real schema)

```sql
-- F-01  rows per record type for one day
SELECT record_type, count(*) AS rows_per_day FROM <cdr_table> WHERE dt = '<day>' GROUP BY record_type;

-- F-02  distinct A, B and pairs per record type for one day
SELECT record_type, count(DISTINCT a_number) AS a, count(DISTINCT b_number) AS b,
       count(DISTINCT concat(a_number, '|', b_number)) AS pairs
FROM <cdr_table> WHERE dt = '<day>' GROUP BY record_type;

-- F-03  distinct contacts per subscriber over 30 days (percentiles), then the top 1,000
SELECT percentile_approx(c, array(0.5, 0.9, 0.99, 0.999)) FROM (
  SELECT a_number, count(DISTINCT b_number) AS c FROM <cdr_table>
  WHERE dt BETWEEN '<from>' AND '<to>' GROUP BY a_number) t;

-- F-04  B-party formats
SELECT CASE WHEN b_number LIKE '+%' THEN 'plus' WHEN b_number LIKE '00%' THEN '00'
            WHEN b_number LIKE '0%' THEN '0' ELSE 'other' END AS fmt,
       length(b_number) AS len, count(*) FROM <cdr_table> WHERE dt = '<day>' GROUP BY 1, 2;

-- F-05  presence tuples per day (15-minute buckets) and null shares
SELECT count(*) AS tuples FROM (
  SELECT DISTINCT a_number, a_cell, floor(unix_timestamp(start_time) / 900) AS bucket
  FROM <cdr_table> WHERE dt = '<day>') t;
SELECT avg(CASE WHEN a_imei IS NULL THEN 1 ELSE 0 END) AS imei_null,
       avg(CASE WHEN a_imsi IS NULL THEN 1 ELSE 0 END) AS imsi_null,
       avg(CASE WHEN a_cell IS NULL THEN 1 ELSE 0 END) AS cell_null
FROM <cdr_table> WHERE dt = '<day>';
```

## Appendix B — Telecom profile content (the first Domain Profile)

- **Entity types:** msisdn (e164), imsi, imei, cell, subscriber (resolved as of date); money profile adds wallet, agent, merchant.
- **Link kinds, pair:** `telecom.voice.onnet`, `telecom.voice.offnet`, `telecom.voice.international`, `telecom.sms`.
- **Link kinds, incidence:** `telecom.cell` (presence), `telecom.device` (msisdn↔imei — several numbers on one handset is the
  SIM-box and device-farm signal), later `telecom.gprs.domain`.
- **References:** dial digit table (prefix, country, operator, number type, zone); cell dimension; IMSI↔MSISDN history (scd2).
- **Entity Lists:** `block` ← the blacklist database; `exclusion` seeds — call center, VIP, toll-free (a pattern list resolved
  from the dial table's number type), short codes, voicemail, own service numbers; computed — supernode per link kind, hot cell.
- **Patterns:** the existing call-forwarding relay; candidates to align with the `telco-fraud` and `telco-ra` Space Templates and the
  planned processors (SIM-box detector, velocity) — wangiri, IRSF, bypass, mule ring (money). Not specified here.
- **Value Measures:** call count, duration, distinct days, international share, off-net share.
