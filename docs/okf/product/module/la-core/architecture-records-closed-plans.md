---
type: Reference
title: Link Analysis — as-built reference and design records — Closed-plan record and consolidated as-built facts
description: Part 6 of 6 of Link Analysis — as-built reference and design records: Closed-plan record; InvestigationStore port; Consolidated as-built facts
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — as-built reference and design records — Closed-plan record and consolidated as-built facts

Part 6 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · previous: [External references, the Dossier and the separation records](architecture-records-dossier-separation.md).

## Closed-plan record

`link-analysis-backlog-plan.md` was retired 2026-10-01 when gate `G-R4` certified
([archived](../../../../archived-documents/plans-archive/link-analysis-backlog-plan.md); §5 holds per-route contracts and
tamper-test narratives). Every item `LA-01`…`LA-24` and decision `D-S1`…`D-U11` is shipped, answered or declined; the
remainder is on `docs/BACKLOG.md` §3.12. This section keeps what no other section states.

### Measured limits: the shape, not the numbers

One host, one browser, one synthetic shape (2026-09-22). The default `dagre` layout has a **cliff between 500 and 750
nodes** (0.9 s → 10.7 s; 16.2 s at 1,000; force ~2.4x dearer at 500) — the reason the projection cap was set at 500; it has
since risen to 1,000 (`LA-CONFIG-LIMITS-1`) and every layout is now capped where it reaches G6 ([spec-la-core.md](spec-la-core.md)).
Analysis is not the constraint except **betweenness** (425 ms / 2.9 s / 9.8 s at 500 / 1,000 / 2,000 nodes; the other 26
algorithms ~129 ms total at 2,000). 🔴 Suspicion score was first blamed because it was the only thing measured that called
betweenness; the sweep timed only the centrality control's default — **time every option a control offers**. Suspicion
score's measured curve (D-S3) is QUADRATIC: 250 → 103 ms, 500 → 402 ms, 750 → 972 ms, 1,000 → 1,608 ms, 2,000 → 7,277 ms
(doubling nodes ≈ 4.5x time); 25 other algorithms finish under 60 ms at 2,000 nodes. (D-S3 chose 750; the default is now
1,000, see [Limits in force](architecture-records-grounded-limits.md#limits-in-force).) `LA-07` (Web Worker) CLOSED 2026-09-23, not built (a serialised-graph round
trip plus worker build config does not repay one slow algorithm; reconfirmed by PERF-D5).

### The model's closed rules

The Investigation is the program, the Working Set its derived state, the Artifact immutable and anchored to a log position.
The op vocabulary is **closed — no new op without a decision entry** (today's evaluable set:
[architecture-la-core.md](architecture-la-core.md) §3.1); reasons: the backend is safe because it is narrow (`SAFE_IDENT` + bind
parameters), a closed set renders mechanically as numbered plain-language steps for a court, and every open query language
converges on SQL. `undo` is a recorded log edit, not an op; `resolve` (LA-17) and `connect` came under their own decisions;
an op outside the vocabulary answers 422 *"unknown"*. The log is **ordered and non-commutative** (`exclude → expand` ≠
`expand → exclude`; re-ordering forks); **`hide` ≠ `exclude` ≠ `keep`** (hide: gone from display, still traversed and
counted; exclude: gone from all three, inspectable as `excluded`; keep: protected from later excludes). Two evaluators, one
spec (incremental on append, full replay), with an `equivalent` check pinned to be able to FAIL. **Negative space is part
of every rendering** (G-E10).

### Investigation ops: threshold, snapshot, temporal and compare

* **`threshold {min?, max?, measure?}` and `snapshot {label?}`** (2026-10-04/05, `LA-INVESTIGATION-OPS-DEFERRED-1`, the
  narrowest reading of plan §2.2). `threshold` measures each entity over the whole Working Set: `degree` (default, absent from
  the entry so older hashes replay; distinct counterparties, hidden still counts, self-loops don't), `weightedDegree` (links
  touching it, each kind + direction once — the multigraph degree) or `eventCount` (sum of folded `count`). ⚠ Call: the
  Working Set carries ONE weight, the folded count, so sum-of-weights would equal `eventCount`; renaming is one line if the
  operator prefers. Band `min` inclusive / `max` exclusive (≥ one bound, `min < max`, no `ids`); outside is excluded as
  `exclude` (keep protects; reason `threshold: <measure> outside [min, max)`; never re-admitted by a later expand), measured
  ONCE (no cascade). `snapshot` is a **marker**: moves nothing, writes no file (the log position is the artifact). Both ride
  `/ops`; templates carry both. SPA `investigation-band-ops.component.ts` (*Degree threshold*, *Log marker*, a *Measure*
  select) with a client mirror of the 422s; the log line is the server's `text`.
  **Declined, reasoned (2026-10-05):** `snapshot` sealing a `/inv/snapshots` Artifact (every log position is already
  reconstructible and verified, and that Artifact is client-computed; the SPA snapshot dialog meets the need); a
  re-evaluable `threshold` (an exclusion deletes the entity's links and later expands drop rows touching it, so a
  re-measure would be on a truncated graph; fixing it means shadow links in the state and hash, changing every old log —
  the honest path is a later `seed`).
* **The `temporal` op** (2026-10-05): `{op:"temporal", mode:"burst"|"periodicity", series?, windowSeconds?, minEvents?,
  maxCv?, limit?}` — a marker (Working Set and hash do not move). It runs the SAME scan as `POST /inv/pattern/temporal`
  (`PatternRoutes.scan`: gates, four-eyes refusal, row cap, index serving) over the Investigation's OWN dataset and columns,
  narrows to the Working Set at that position, and seals `entry.temporalFindings` (`{dataset, params, workingSet, count,
  results, outsideWorkingSet, truncated, rowCapped, skippedNoTime, timeNote, servedFrom, readAt, sealed:true, basis,
  fingerprint}`; fingerprint SHA-256 over content, not `basis`/`readAt`/`servedFrom`). Knobs validated with the route's
  bounds (422), stored with defaults filled. Calls: (1) the Investigation's columns, never the request's; (2) a link finding
  kept when some Working Set link has that source and target, an entity finding when admitted — the rest counted in
  `outsideWorkingSet`, never named; (3) the scan reads at the route maximum (1,000) and `limit` cuts after narrowing; (4) the
  Investigation's window is NOT applied; (5) a time column is required. Append, `replayOp` (Draft rebase / promote) and
  `reorder` RE-SEAL over the new Working Set; replay never re-reads; the log view carries counts
  (`TemporalFindings.summary`); `GraphDossierBuilder` hashes the fingerprint (a forged count is `integrity` red, `/dossier/verify`
  names `log.jsonl#N`) and adds `temporalFindings [{step, findings}]` only when an effective step carries one; masking at
  render; templates drop it (`CASE_OPS`). Test `ControlApiInvestigationTemporalOpTest`. SPA *Seal these findings* sends the
  knobs on screen (`sealTemporal`); the server re-runs the scan, so what is sealed is its answer. Dossier HTML renders
  *Comparisons* and *Temporal findings* only when present (every other Dossier byte-identical).
  ❌ **Windowing the scan DECLINED (2026-10-05):** the scan is shared with the stateless route and the index path (neither
  has a window); a windowed seal would differ silently from what the panel shows; clipping changes the statistics (a burst
  cut at an edge, a `cv` over a clipped series); old logs are not at risk. `compare` with `window: "inherit"` and activity
  mode covers the need.
* **Comparison read** (2026-10-04): `GET /inv/investigations/{id}/compare?aFrom&aTo&bFrom&bTo&timezone&at&mode&minAbsDelta`
  (`InvestigationComparisonRoutes`, `WindowComparison`) diffs two windows over the Working Set at `at`: links and entities in
  A only, B only, both (`countA`/`countB`), `neither`, each capped at 500 with the true `count`. *Why a read:* a sealed Working
  Set has no timestamps, so a time-sliced diff needs the LIVE Dataset (`sealed: false`). Universe = the Working Set's
  entities and links only (never-admitted pairs and excluded entities never appear); present = ≥ 1 event in the window;
  rungs not applied; one shared optional `timezone`, no slot / day mask; needs `timeCol`; > 50,000 links in one window is
  422 (no silent sample); ≤ 2,000 entities. Masked at render (names nothing new; a `masking_mode: all` test pins it).
  Access `openForRead` + R3; audited `LINK_INVESTIGATION_COMPARED`. The Dossier carries a `comparison` section ONLY when the
  same parameters are passed, labelled unsealed, outside the manifest. `mode=activity` and `minAbsDelta` as for the op.
  Test `ControlApiInvestigationComparisonTest`.
* **Comparison as a SEALED op — `compare`** (2026-10-05): `{op:"compare", windowA, windowB, mode?, minAbsDelta?}` (each side a
  `window` object via `InvestigationTime.window`, so slot / days / timezone work per side; needs `timeCol`). A marker;
  `WindowComparison.compare` runs over the Working Set *before* the step and `seal`s it (JSON-normalised, `sealed: true`,
  fingerprint over `{windowA, windowB, workingSet, links, entities}`, no `readAt`, so the Dossier root stays deterministic).
  Custody: the diff is inside the hashed `log.jsonl#<step>` line; `GraphDossierBuilder` recomputes the fingerprint per step
  (a hand-edited `eventsA` goes red on verify AND integrity); the Dossier gets `comparisons [{step, comparison}]` (absent
  when none). Fork and Draft promote RE-SEAL; templates DROP it (`CASE_OPS`). Log view: counts only. No new route. Test
  `ControlApiInvestigationCompareOpTest`. **`window: "inherit"`** per side: params keep `"inherit"`, the seal carries the
  concrete window it resolved to plus `inherited: ["B"]`; refused 422 when the Investigation has no window at that step.
  **`mode: "activity"`**: adds `activity {links:{changed, unchanged}, entities:{…}}`, `changed` capped at 500 with
  `delta = countB - countA`, sorted by |delta| then id; an entity's count = in-window events on its Working Set links
  (self-loop once). **`minAbsDelta`** (≥ 1, activity only, else 422): smaller moves counted under `belowMinDelta`, floor
  echoed. `mode`, `activity`, `inherited` and the floor join the fingerprint ONLY when present, so earlier seals verify
  byte-identically. SPA `InvestigationCompareOpComponent` (two window field sets, per-side *Use the Investigation's window*,
  *By event count*, *Smallest count change to list*). ⚠ DR-D1: every plain `<form (ngSubmit)>` must import `FormsModule`
  (`ReactiveFormsModule` alone leaves the native submit uncancelled and the page reloads; specs click the real button and
  assert `defaultPrevented`). DR-D3/D4/D6 (2026-10-10): a successful *Seed* sets `showWorkingSet` true; server-run controls
  take `[investigationOpen]`; Value Measures prefill from the Investigation; 413/429 through `limitRefusalMessage`.

### Timeline playback

*2026-10-05, SPA only* (`link-analysis-timeline-playback.component`, `timeline-playback.ts`). Steps the timeline column's
`[min, max]` in 20 equal slices (play 1 s per slice, pause, previous, next, scrubber, reset) and highlights links whose
date falls in the slice (half-open; the last slice includes the max) through canvas `emphasis`. Writes NOTHING to the graph
or the time filter; a link without a parseable date is in no slice. `role=status` names slice and count (`aria-live` off
while playing). Play is offered under `prefers-reduced-motion` too (it starts only on a press; WCAG 2.2.2; operator
2026-10-29). Calls: the range is the TIMELINE column's (a Working Set has no per-link timestamps, so playback is query-graph
only); an aggregated link plays at the one date it carries; fixed 20 slices.
**Window, age fade, Seal this window** (`LA-PRODUCT-NEXT-1` phase 4, 2026-10-30): *Window* = This slice (default) / Last day /
7 / 30 days / From the start, reaching back from the handle; *Age fade* (on by default) runs opacity from 1 at the handle to
0.45 at the window start (above the 0.2 out-of-window dim); *Seal this window* (Investigation open, `canManageIncidents`,
the Investigation's event-time column = the timeline's) appends a `window` op with `[from, to)` UTC instants. Calls: links
outside are dimmed not hidden (hiding re-runs layout every tick); the scrubber is not recorded by Undo; dates are read like
the timeline filter (`Date.parse`, a naive timestamp in the browser's zone) while the server reads `timeColZone`, so on
such a Dataset a sealed window can sit an offset away from the highlight.

### Temporal patterns: time-respecting paths, burst and periodicity

*2026-10-04/05.* Built as **stateless Dataset reads**, not ops or graph-run algorithms: the Working Set and
`/inv/graph/runs` carry counts, never edge timestamps.
* **Time-respecting paths** already existed: `temporalConstraint {timestampCol, monotonic, maxTotalDurationHours}` on
  `/inv/traversal/recursive-paths` since LA-11 (*monotonic* = non-decreasing, ties pass). Added
  **`temporalConstraint.maxGapHours`** (positive; 422 without `monotonic: true`); flat CTE and index walk both apply it
  (`ControlApiInvIndexedTraversalTest`). It only removes paths; gates unchanged.
* **`POST /inv/pattern/temporal`** (`PatternRoutes`; maths in `LinkTemporalDetector`; the DuckDB cut and current row cap:
  [architecture-la-core.md](architecture-la-core.md) §5): `{dataset, sourceCol, targetCol, timeCol, mode, series?, filter?, limit?,
  windowSeconds?, minEvents?, maxCv?}`. Series = event times of one directed link as spelled (not normalised). *Burst:* a
  `windowSeconds` window (default 60, 1–86,400) holding ≥ `minEvents` (default 5, 2–1,000), overlapping windows merged,
  edge inclusive. *Periodicity:* ≥ `minEvents` (default 5, min 3) with gap CV (population σ / mean) ≤ `maxCv` (default 0.1,
  0–1); reports the MEDIAN gap as `periodSeconds`. Out-of-range is 422, never clamped. Strongest first, cut to `limit`
  (default 200, max 1,000). Fences: one bound statement with a row cap (`rowCapped` + `truncated`), 5 s timeout, four-eyes
  `refuseIfSensitive` (403; no Investigation to hold an approval), unparseable times skipped and counted (`skippedNoTime`).
  Times return as wall-clock text with NO zone claimed. Masking: returns only the asked columns, the same exposure as
  `/inv/pattern/branching`. Read-shaped; audited `LINK_PATTERN_MATCHED`. 🔴 DuckDB reserves `at`: a test column so named
  made every read 422.
* **Per-entity series and index serving** (2026-10-05): `series: "entity"` pools every event an entity takes part in (a
  self-loop row is ONE event); findings carry `entity`; the answer gains `series` and `source`. `IndexedTemporal` (via
  `IndexedRead`) serves when an index maps the same columns AND its time column IS the request's `timeCol` in UTC AND every
  filter field is indexed — a full ordered scan of `out` feeding the SAME detection, identical by construction
  (`ControlApiInvTemporalIndexedTest`); otherwise a closed reason. Calls: a new field `series` (not an overload of `mode`); an
  entity series fans the same edge rows out in Java; the index path is a full scan (saves the relation evaluation, not the
  Dataset-size cost); view gate and four-eyes run before the index. Gotcha: `ResultSet.wasNull()` speaks of the LAST column
  read — a `getString` between `getLong` and `wasNull` turned NULL times into 1970 until the equivalence test caught it.
* **SPA overlay** (`link-analysis-temporal-findings.component.ts`, Whole Dataset tab): needs a time column and an edge
  mapping (`traversalTargets`); 🔴 unlike paths and branching it **never adds nodes or links** — each finding is matched to
  drawn links via `resolveEntityId`; undrawn ones stay listed, disabled, counted. States `truncated` / `rowCapped` /
  `skippedNoTime` and the server's `timeNote`; numbers clamped client-side; not wired to a saved view.

### Listing and link notes

*2026-10-01.* `GET /inv/investigations` lists what the caller may read — own, member, and shared through an open linked
Case, each judged by `openForRead` (R3 and a PDP DENY hide it) — `{id, title, dataset, owner, createdAt, headStep, caseRef?,
access, readOnly}`, newest first, `limit`/`offset`/`total`/`truncated`. Link notes are sent by `linkId` (*Annotate link*),
the sealed `linkAnnotations` render, and the Dossier stamps `linkId` on its link annotations.

### Decisions of record

* **D-E3 deferral — byte-identical replay (`G-E2`) needs version-addressable Dataset reads and is the ONLY gate that does.**
  `G-E11` and `G-E3` are met by sealing + fingerprints. A recorded file list was **rejected** as a pin (compaction moves
  files to `COMPACTED_AWAY`; a pin that rots is worse than none). When replay is wanted, copy `ReferenceReader`'s SCD2
  `asOf` read.
* **D-U8 — NO purge** (operator 2026-09-24): no retention period, purge task or legal-hold record; reopen only on a
  regulator or retention policy (pattern: `incident_purge`). **Amended by LEGAL-HOLD-SCOPE-1 (2026-11-17):** still no
  Investigation-level hold record, but an Investigation linked to a Case under legal hold (`CasePort.legalHold`, the
  Case's own hold or a held Incident it contains; any Case state) refuses `DELETE /inv/investigations/{id}`,
  `DELETE …/case` and a `PUT …/case` to another Case with 409 and an `AUDIT` `legal_hold.refused` event.
* **D-U9 — per-Collector coverage, shipped 2026-10-05** (`LA-COLLECTOR-COVERAGE-1`): `collectors` attributed through
  `CollectorCoveragePort` (bridge `HostCollectorCoveragePort`): each Pipeline's `batches` CSV (Consignment → output store)
  joined to its `lineage` CSV (Consignment → event-day partition, rows), kept when they wrote the Dataset's
  `physicalRef`/`sourceName`, attributed to `collector().id()`. The Dataset-filename join was **rejected** (lineage `src_id`
  is a member index; the ledger / `file_stages` are opt-in with no list-by-file read; `file_name` optional; basenames
  collide). No evidence ⇒ `assessed:false`, never "no gaps". The Dossier does not seal live coverage. Residuals:
  never-delivering Collectors are invisible ([architecture-la-core.md](architecture-la-core.md) §10); partition days use the Pipeline's
  pinned zone. `HostCollectorCoveragePort.day()` is pinned for `year=/month=/day=` and `dt=` forms. SPA: a table
  (Collector · days covered of expected · missing days); `assessed:false` prints "Per-Collector coverage is not assessed
  for this Dataset." plus the server's note.
* **D-U11 answers (2026-09-30):** A1 a snapshot captures what is on screen · A2 the cash-out share denominator is ALL
  cash-out · A3 the agent Entity List is read LIVE and its head seq/hash recorded on each firing (`evidence.agentList` /
  `agentListSeq` / `agentListHash`) · A4 a retired list is 409 · A5 value-measure queries run in UTC · A9 `coverage` is
  readable by Case members (reverses D-U10's owner-only) · A10 pinned Widgets are unaffected. Merged-exclude follow-ups: the
  resolution view widens by the SAME matching rule as exclude / expand; only an UNAMBIGUOUS untyped match counts; old dev
  logs replaying not-equivalent are acceptable; the Dossier line states the full outcome.
* **Naming (D-E1):** *Investigation* / *Investigation Template*; the Assistant's RCA run took *Triage* (`GLOSSARY-CASE-1`).
* **`SEP-02`** (2026-10-01): keep the hand-kept la-api / geo-link manifests' `provides.routes`, guarded both ways by
  `GeoLinkAbsentSurfaceParityTest` (a drift incident is recorded in its header); a module-contributed list cannot serve the
  stub, which must exist precisely when the module is NOT on the classpath; a build-time generated descriptor was weighed and
  not chosen.

### `G-R4` certification (LA-11)

5-hop recursive paths over a heavy-tailed 10^6-edge Dataset through the real route: warm p95 **209 / 188 / 171 ms** from a
median-degree / p99 / top-hub start (all < 350 ms; cold p95 388 / 181 / 163 ms over 5 runs). The fix that mattered:
`EXPLAIN ANALYZE` showed the recursion running TWICE (`__walk` read by the paths and by the level widths) →
`__walk AS MATERIALIZED`. The column probe is folded into the walk's session (`QueryExecutor.runPlanned`); the walk has its
own thread cap (install limit `traversal.threads`, else `-Dassist.sql.traversal_threads`; default 4; other sandbox queries
keep `assist.sql.threads`, 2). Harness `InvTraversalBench` (`@Tag("bench")`); method in
[`la-separation-feasibility-plan.md`](../../../../archived-documents/plans-archive/la-separation-feasibility-plan.md)
§7.10.1.

### Gotchas worth keeping

* All `/inv/…` paths are in `docs/api/openapi-v1.json` (an old note said they could not be) — trust the file.
* A DI change is invisible to type-checking; `link-analysis.component.spec.ts` (the only end-to-end snapshot-and-attach
  spec) was found 28/28 red on master (2026-09-23), hiding every regression it guards — read a flow spec's last green date.
* A well-tested evaluator can be DEAD while the live path has none (`evaluateRows` had zero callers).
* Plan claims went stale within days; re-ground against code before acting on any plan row — including these.
* Live check 2026-09-30 (Enterprise demo bundle, three Demo Users): 33/33 steps passed once the demo Dataset classified its
  account columns `ACCOUNT` and `account_links_dataset` existed for identity import.

Design (archived): [`link-analysis-and-graphsource.md`](../../../../archived-documents/plans-archive/link-analysis-and-graphsource.md)
· [`link-analysis-projection-authoring-plan.md`](../../../../archived-documents/plans-archive/link-analysis-projection-authoring-plan.md)
§7 · [`link-analysis-studio-plan.md`](../../../../archived-documents/plans-archive/link-analysis-studio-plan.md) §6–7 ·
[`link-analysis-toolboxes-plan.md`](../../../../archived-documents/plans-archive/link-analysis-toolboxes-plan.md).

## InvestigationStore port

*S0 + S1, as built 2026-10-04; backends compared in [architecture-la-core.md](architecture-la-core.md) §3.2.* Sidecar records sit behind
`InvestigationStore` (la-core), keyed by id and `Scope(investigationId, draftId|null)`, never a Path;
`InvestigationStores.of(writeRoot)` is the one place a backend is chosen. Writers carry the version they read
(`append(scope, expectedVersion, …)`, `appendMember(expectedCount)`); a lost race is
`InvestigationVersionConflictException`, retried by the route (`untilWon`, 20 attempts) and answered
`409 CONFLICT_STALE_VERSION` if it keeps losing. Lines and sets come back byte for byte (the sealed hashes depend on it).
Drafts are port operations with preconditions (`promoteDraft` / `replaceDraft`); `decide` claims a pending request by
compare-and-set (`replacePending`) before its append and hands the claim back only if the act fails before any append; the
mask key is `maskKey(id)`; per-pod caches are keyed by `cacheKey(scope)` + `logToken(scope)`; an interrupted promote is
finished or undone by `recoverDrafts` (an intent file written before the first main step; `promoting.json` allow-listed in
`ImportLoaderInventoryTest` `NOT_CONFIG`). FS `log()` returns committed lines only (a torn last line ignored). Design:
[`investigation-store-design.md`](../../../../archived-documents/plans-archive/investigation-store-design.md) §13.

* **Postgres selection (S2-S6).** `-Dinvestigations.backend=fs|db` (default `fs`); `db` is the optional
  `inspecto-la-store-pg` (`InvestigationStoreProvider` by ServiceLoader): one schema `space_<id>` per Space (the platform's
  `OperationalDb.schemaFor` naming re-implemented, since la-core cannot see core; validated `[a-z_][a-z0-9_]{0,62}`; ids
  `COLLATE "C"`), one pooled HikariCP source per database, schema created once under a transaction-scoped advisory lock.
  Connection `-Dinvestigations.db.url/.user/.password`, each falling back to `-Dinspecto.db.*`. **Fail closed:** unknown
  backend, `db` without the module, no / non-PostgreSQL URL, unreachable or vanished database → `503 CAPABILITY_UNAVAILABLE`
  (SQLSTATE class 08 / 28 / 53 / 57P or a pool that cannot lend = the same); nothing cached, the next request retries; never
  falls back to the filesystem (that would fork the evidence). Main log, sets, members and references are append-only by
  trigger (`WHEN (OLD.draft = '')`, SQLSTATE 23000, PostgreSQL 14+ for `CREATE OR REPLACE TRIGGER`; a trigger not a REVOKE
  because the app connects as table owner; a DBA can drop them). A Space that switches backend starts empty (no importer,
  D-IS10). Operator 2026-10-05: no per-set cap yet; no boot-time refusal (`db` judged on first use per Space); process-global
  switch.
* **PG locking:** `SELECT … FOR NO KEY UPDATE`, not `FOR UPDATE` (deliberate deviation from D-IS4: a Draft append's FK takes
  `FOR KEY SHARE` on the Investigation row, so `FOR UPDATE` would deadlock promote vs Draft append). Order Space row →
  Investigation row → Draft row; no advisory lock except the bootstrap one. Deliberate PG/FS differences: `recoverDrafts`
  no-op; `logToken` = entry count (main) / `version:state` (Draft); promote seals a Draft set by `INSERT … SELECT` (D-IS12 a);
  `touchDraft` writes at most every 5 min; a write to an Investigation with no header row is an IOException.
  `drafts/index.json` and `.fork-* / .rebase-* / .old-*` do not exist on PG.
* **Two-JVM race proof (S7, 2026-10-05).** `InvestigationStoreTwoJvmRace` (append chain, `decide` CAS, mask key, promote)
  found the filesystem store NOT safe across processes (3 of 4 cases tore; mask key held); `FsInvestigationStore` now takes
  an OS `FileChannel.lock` on `<investigation>/.store.lock` inside the Investigation monitor (order: monitor, file lock,
  Draft monitor; Draft-scope appends take the Investigation monitor first; recovery waits for a live promote). Creates,
  forks, references, Case link / Alert Rule binding and the Space-wide Draft cap remain per-JVM (last-writer-wins).
  Known race-only deviations: a fork pins the index before the D17 / cap judgment (a refused fork pins then unpins);
  promote / rebase 409 share one message for two causes; the expiry marker is built under the Draft monitor but idle time is
  read just before.
* **Proofs.** Postgres passed the shared contract and the race harness (4/4) on PG 18.6 (2026-10-05); end to end 2026-10-13
  the contract ran 45/45 and an Advanced `la-app` bundle on `-Dinvestigations.backend=db` seeded and smoke-tested
  `la-showcase` (17/17, again after restart; recipe in
  [`admin-guide-variants-runbook.md`](../../solution-pack/la-showcase/admin-guide-variants-runbook.md) §A); 2026-11-01
  57/57 (contract 47, race 4, embed grants 6) incl. `updateDetailsIsOneReadModifyWriteAndAnAbsentInvestigationWritesNothing`
  (8 writers × 25 edits, none lost). Run recipe (javadoc of `PgInvestigationStoreContractTest`): env
  `INSPECTO_TEST_PG_URL` (not `-D`), `-DforkCount=0`, the driver in the owning module, `MAVEN_OPTS=-Duser.timezone=…`;
  per-test SKIP without a URL; the contract runs a 4-connection pool against 16-writer races. Operator-run mutation checks
  (FS + PG mutants 1-14) are still OWED.
* **S0 measurements:** ~291 B per entity per sealed set (10,000 = 2.9 MB; 100,000 = 29.1 MB); 10,000 entities × 2,000 steps
  ≈ 3,061 MB per Investigation; an expand log line ~74 B per sealed row (5,000 rows = 370 KB); FS `readLog` p50 9.2 ms and
  `prefixHash` p50 16.8 ms at 2,000 steps / 5.95 MB. NUL is written as the escape `\u0000`.
* **Packaging:** staged with the Advanced and Preview bundles; `package.ps1` launcher classpath and service-file check; Space
  id = the directory above `config` in the write root, `default` for the legacy root. Out of the seam: the Parquet index,
  pin ledger, snapshots / attachments, Dossier bundles; sweeps run in-request.

## Consolidated as-built facts

Carried over 2026-10-05 when eight LA plan / design docs were archived into the
[roadmap](../../../../superpower/link-analysis-roadmap.md); only facts not stated above are kept.

### Separation and D-7 Drafts

* `SnapshotStore.appendStep` delegates to a static `appendStepAt(dir, …)`; a Draft appends via `DraftStore.appendStep`, which
  takes the log line back if the set file cannot be written (main keeps log-first order). Promote lands sets via
  `SnapshotStore.appendStepSharingSet` (hard link, byte copy where unsupported); the reuse test is that the set file's head
  is exactly `{"hash":"<h>","step":<n>,"workingSet":` and its bytes SHA-256 to the sealed hash (operator rule "nothing
  unverified reaches main", 2026-10-03).
* Promote refuses a Draft holding an undo entry (a log compacted without renumbering is not the state shown); rebase first.
* Four-eyes: a Draft cannot hold a sensitive expand (422 at append); sensitivity is judged at PROMOTE; approve re-checks the
  Draft log hash and main head (409 "deny and request again"); rebase re-reads without approval (a Draft's reads are private).
* Real-state D-S5 bench at 10^8 edges: open p50 37 ms; 0.4 MB heap per Draft (50 ≈ 20 MB); light p95 28 ms solo vs 815 ms
  closed loop (CPU saturation, 20 threads on 12); a saturated heavy limit moves light p95 28 → 43 ms. `DraftConcurrencyBench`
  lives in `inspecto-la-api` (la-storage cannot see `DraftAdmission` / `DraftPromote`).
* `IndexStore.gc` runs under a per-directory pin lock (JVM monitor + `.pins.lock`); an unreadable `pins.json` THROWS and gc
  deletes nothing; pinned append versions survive their parent's gc (per-file hard links).
* Plan-vs-code corrections: access was never purely owner-only (LA-24 Case member, D-U7 approver); no Dataset version exists;
  op log and Working Sets are JSON and the evaluator never touches DuckDB; the evaluator is TOTAL (an op naming an absent id
  is a no-op — what makes fork / rebase evaluate).
* Draft role matrix: a lead gets 403 writing another's Draft; a revoked actor's Draft freezes (a lead may read / discard); the
  owner is only the FIRST lead; legacy (no `members.jsonl`) approver = any `canApproveLinkExpansions` holder; 403 is raised
  only after R3 + PDP, so a policy DENY still reads 404.
* Fork atomicity: staged in `drafts/.fork-*` (a leading dot never matches `DRAFT_ID`); pins taken first, released on failure;
  the one-per-member check + pin + rename under the Investigation's `drafts` lock; server-generated `draft-<uuid>` matched
  EXACTLY (422 otherwise); fork answers 201 + Location.
* Rebase: the Draft header is REWRITTEN (not write-once) with `rebases[]`; steps carry `rebasedFrom`; commit takes main lock
  THEN Draft lock; a rebase at the current head is legal (renews an expired pin); `IndexedRead.select(…, pinned)` /
  `IndexedExpand.attempt(…, pinned)` take `mappingHash → version`.
* D-S4 graph traps: closed-graph assumption (a dangling edge leaks a phantom neighbour); multigraph rules (degree counts
  parallel edges and a self-loop twice; kCore / triangles / cliques collapse them); `hits` tie noise; `GraphAlgorithms.Edge`
  carries no weight (weighted functions take an edge-id map); an iterative mutant can hang the suite (parity tests need a
  timeout).
* SPA D-5 prep: `no-restricted-imports` ignores `ImportExpression`, hence the `no-restricted-syntax` selector set
  `laDynamicImportSelectors`; `<inspecto-la-host-slot>` (`LaHostSlotComponent`, `ngDoCheck` inputs, subscribed outputs) exists
  because `NgComponentOutlet` cannot apply inputs / outputs.
* Packaging trap: a static Maven profile module list must stay in step with `tools/bundle-modules.mjs`.

### Entity model and live detection

*Entity List storage and the fact chain are outlined in [entity-list spec](../entity-list/spec-entity-list.md) and
[architecture-la-core.md](architecture-la-core.md) §3.5.*

* **Entity Types:** normaliser closed enum `default | digits | e164 | upper-trim`, Java and TS identical via
  `entity-normaliser-parity.fixture.json` (`entity-key.spec.ts`, `EntityTypesTest`). "Whitespace" = the JavaScript `\s` set
  on both sides (Java `\s`, `trim()`, `strip()` miss NBSP); final sigma folds to sigma. Settings validation (422, never
  clamped): id `^[a-z][a-z0-9_]{0,31}$` unique, `entity` reserved, non-blank label, a classification claimed by ≤ 1 type, ≤ 64
  types, a stated list replaces the defaults and may not be empty.
* **Phone numbers (SP5, 2026-10-06):** the sealed `e164` normaliser keeps a national number national (no default country, no
  trunk-prefix strip), loses the `+` after a leading `(`, does no length check, so only `+` ids are longest-prefix
  enrichable. DuckDB longest-prefix enrichment of 10^7 numbers ~2 s; a new-only daily upsert ~4 s vs ~10 s full re-derive
  (10^9 extrapolated). Evidence and the open normaliser decision: roadmap "SP5 findings".
* **Entity List wire contract:** `GET /entity-lists` → `{lists:[summary], headSeq, headHash}`, summary
  `{id,title,purpose,normaliser,entityType,size,retired,createdAt,createdBy,lastSeq}`; `GET /entity-lists/{id}?at=<seq>` →
  summary + sorted masked `members` + `atSeq` + `headHash` (`at` past head 422; absent at `at` 404). `POST /entity-lists`
  `{id?,title,purpose,entityType,reason}` → 201; purpose `allow|block|watch|exclusion`; id `^[a-z0-9][a-z0-9_-]{0,63}$`; an
  ever-used id 409. `POST …/members` `{add?,remove?,reason}` → 200 with `changed` (empty after normalising or in both sets
  422; ≤ 5,000 values; a no-op writes nothing; retired or type out of force 409). `POST …/retire` (already retired 409).
  Every write: non-blank `reason`, `canManageIncidents`, no write root 503, unknown 404; title ≤ 200, reason ≤ 1,000, value ≤
  512. Reads need only Space access (503 without a write root). Audit `ENTITY_LIST_CHANGED {listId,kind,added,removed,seq}`
  (counts).
* **Fact log:** `<write root>/audit/entity-facts/<12-digit seq>.json` (`seq, at, actor, reason, kind, listId?, …, prevHash`),
  published temp file + fsync + hard link (atomic no-replace on every OS; `AtomicFiles` always replaces, so it is not used);
  appends under the per-directory lock, responses sent after leaving it. Kinds `list.created` (seals `normaliser`; absent
  reads `default`), `list.member.added/removed` (sorted `keys[]`), `list.retired`, `identity.asserted {a,b,via}`,
  `identity.retracted {assertionSeq}`. The chain is re-verified on every read (break → 500 `INTEGRITY_VIOLATION`, no cache);
  a deleted LAST fact is undetectable from the log alone; orphan `.fact-*.tmp` files are ignored, never swept.
* **List ops in Investigations:** `excludeBy`/`seedBy` `{op, listId, reason?}`; refusal order 404, retired 409, type not in
  force 409, > 5,000 members 422; sealed `list {listId, atSeq, headHash, entityType, normaliser, purpose, masked,
  members[]}`. `excludeBy` records `excluded` and remembers `excludedKeys [{normaliser,key,step}]` (hashed only when
  non-empty; a remembered key does not block an already-admitted entity). `seedBy` reads `SELECT DISTINCT CAST(col AS
  VARCHAR)` per bound column, capped by `seed_by_distinct_cap` (1..100,000, default 20,000; 422 above), sealed as `read{ids[],
  fingerprint,…}`. Templates carry `{op, step, listId}` and re-resolve at the head; fork carries the seal verbatim.
* **Identity resolution routes:** `GET /inv/entity-identities?at=`; `GET …/group?key=&at=` (exact; key via `getRawQuery()` so
  `%2B` is not a space); `POST /inv/entity-identities` `{a,b,reason}` → 201; `POST …/{seq}/retract`; `POST …/import`. Group
  `{id (smallest member key), members[], assertions[{seq,a,b,via,actor,at,reason}]}`; a redundant assertion is extra
  evidence; cross-type allowed. Both reads need `canManageIncidents` (membership-oracle probe fixed). Event
  `ENTITY_IDENTITY_CHANGED {kind,seq,assertionSeq,groupSize}`. Import `{dataset,aCol,bCol,aType?,bType?,reason,limit?}` → 201
  (200 when nothing new), counts only (`imported`, `skipped{alreadyAsserted,duplicate,empty,self}`, `rowsRead`, `truncated`,
  `fingerprint`, `via: "dataset:<id>@<fingerprint>"`, `fences`); `limit` 1..10,000 (default 1,000), 10 s; idempotent (a
  retracted pair is never re-asserted); four-eyes from the D-U7 thresholds.
* **`resolve` op and merged traversal:** `resolve {atSeq?}` seals `{atSeq, atHash, headSeq, headHash, groups[], types{},
  columnTypes{}}`; a VIEW, not a rewrite (traversal, exclusion, counts stay raw); `toMap` adds `resolution` / `resolvedTo`
  only when in force (older hashes unchanged); measure `identities`; `/log` shows `resolutionSummary`; fork keeps the seal,
  template re-seals. 422: `ids` given, bad / past-head `atSeq`, > 5,000 member keys. `merged: true` on `expand` / `exclude`
  (422 without a resolve; hashed only when true): merged expand widens the frontier via a DISTINCT read capped by
  `merged_distinct_cap` (default 20,000), frontier cap 1,000; merged exclude seals groups and remembers `excludedGroups`; an
  untyped value is a member only when UNAMBIGUOUS (`rawMemberKeys`). Fork re-seals; a merged op re-ordered before its resolve
  is 422. Tests `ControlApiInvestigationResolveTest`, `…MergedTraversalTest`, `ControlApiEntityIdentityTest`,
  `…ImportTest`.
* **Live detection details:** decision register D-LD1..D-LD18 (only D-LD1 operator-decided): D-LD8 `canConfigureAccess`
  never honoured; D-LD9 only tightening refuses; D-LD16 an edit drops `standing`, write order component → binding → re-arm.
  Template list → `{templates:[{id,title,createdAt,dataset,investigation,parameters,ops}]}` (counts; a host without sign-in
  lists all); disable → `{rule,investigation,enabled:false,wasEnabled}`; PUT adds `replaced:true`,
  `standingDetection:"disabled: …"`, 404 not bound, 409 armed rule edited out of band. Events
  `LINK_STANDING_DETECTION_DISABLED`, `LINK_INVESTIGATION_ALERT_RULE_EDITED {rule,investigationId,standingDetectionDropped}`.
  `la.detect` is authored as `config/jobs/<name>.toon` `job:{name,type:la.detect,schedule:"<cron>"}`; Signal
  `la.detect.completed {job,fired,rules[]}`. `RowScope.visibleAs` uses an inert package-private `SweepExchange`. Enable reads
  the ARMED rule (a stored copy hashes differently after the TOON number round-trip). Gates per new route:
  `CapabilityManifest`, the manifests' `provides.routes`, `openapi-v1.json`, `compliance/evidence/route-gating.md`, auth-gate
  baseline; `JobWritersTest` row for the PUT; `ConfigWriteFunnelTest.WRITERS` / `DecisionRuleWritersTest` list `sealList` /
  `sealResolution` as fact-log readers (operator-approved 2026-09-30).
