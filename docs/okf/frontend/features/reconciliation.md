---
type: Feature
title: Reconciliation
description: Dataset-vs-Dataset (and 3-way) reconciliation read one day at a time — a server-paged, band-tinted Board table with banded Δ%, Breaks drill with live record sets, usable as a Widget.
resource: inspecto-ui/src/app/modules/admin/reconciliation/
tags: [feature, reconciliation, breaks, board, paging, dat-7, recon-perf-1]
timestamp: 2026-07-16T00:00:00Z
---

# Reconciliation

> **Module (MODULE-REORG-1 P7, 2026-10-07).** Everything server-side on this page — the ten `/recon` routes (`ReconRoutes`),
> the comparison engine (`ReconService`, `ReconBreaks`, `ReconConfigLoader`), the run state (`ReconStateStore`) and the
> `recon.run` Job Type — lives in the optional **`inspecto-reconciliation`** module (id `reconciliation`, package
> `com.gamma.recon`; the plan §8a *Reconciliation* add-on), staged from **Professional** up. **Personal no longer ships it**
> (a deliberate behaviour change): its `/recon` paths answer 503 "not installed" (`AbsentModuleRoutes`), `/bootstrap`
> reports `features.reconciliation=false`, and `recon.run` is an unknown Job Type. The `reconciliation` config component kind
> stays core — authoring is generic component CRUD; deleting the component deletes its run state through the module's
> `ComponentDeleteHook`. The tests below moved with it (`features/inspecto-reconciliation/src/test`).

**One day at a time** (`RECON-PERF-1`, **operator, 2026-10-09**). The 3-way ra_c01 Board was slow because every
open compared each side's WHOLE store (no date predicate) in ~7 scans, returned up to 5,000 rows into one tree, showed
"No saved Reconciliation" while in flight, and recorded up to 50k Breaks on every open. As built:

* **Day scoping** (`ReconDay`): `/recon/run`, `/recon/breaks`, `/recon/rows` and `/recon/{id}/record` take `day`
  (ISO); absent = the **latest** day present on any side. Each side's relation is filtered to that day of its
  Dataset's temporal column — `columns[].role: temporal` (`DatasetRelation.temporalColumn`), else `dateField`. A DATE
  column is compared bare, a TIMESTAMP as a half-open range (so Parquet row-group statistics can skip), anything else
  through `TRY_CAST(… AS DATE)`. ⛔ **Decision: a Dataset with no temporal column is a 422 naming the fix**, not the
  whole relation with `dayScoped:false` (operator, 2026-10-09 — fail-closed: an unscoped side would compare 30 days of
  one system against one day of another and report every key as a Break). `availableDays` = the distinct days across
  the sides, newest first, capped 366. A `day` that is not an ISO date is a 422 and is the only form that reaches SQL.
* **One pass** (`ReconService.dayRun`): the grain FULL OUTER JOIN chain is materialised once as `__grain`, each row
  classified by `__cls` bits (missing on A/B/C, value break, cardinality break); totals, every pair's summary and the
  group count are ONE aggregate over it. ⚠ That is exact because each grouped side holds a key once, so every
  anchor-relative pair reads off the chain, and per-side totals sum the per-key groups (counts COALESCE to 0 on an empty
  side, as the old ungrouped totals did). `run(spec, limit)` now delegates to it, so the long-standing `ReconServiceTest`
  run cases pin the merge.
* **Server paging + filter**: `limit` 1..200 (default 50), `offset`, `filter` = `all | breaks | missing_a | missing_b |
  missing_c | value_break` (`ReconService.filterMask` — one mask read by both the cached and the SQL page; `missing_c`
  on a 2-way is a 422). Answer: `page{offset, limit, total}`, `day`, `availableDays`, `filter`, `sample`, and the whole
  day's `totals`/`summary` — never the page's.
* **Per-day cache** (`ReconDay.Cache`, 32 entries, LRU, in memory): key = sha-256 of the spec + every side's input-file
  stamps (path, size, mtime from `DatasetRelation.inputFiles`) + day + sample. A changed file or config is a NEW key,
  so stale entries are never read, only aged out; a view-backed/virtual Dataset or a store over 50,000 files is not
  cached (it still runs). The day's rows are held when the day has ≤ 200,000 key groups; a bigger day pages in SQL on
  every request (leftover). `statistics.cached` says which.
* **Sample** (`sample` 1..100000, not saved): the union of every side's keys as text, `ORDER BY hash(keys), keys
  LIMIT n`; each side becomes a NULL-safe semi-join on it. The answer says `sample{sampled, size, keys, totalKeys}` and
  the Board shows a *Sampled* chip.
* **Bands are saved config**: `bands: {okBelow, warnAbove}` (percent), validated `0 ≤ okBelow ≤ warnAbove ≤ 100` by
  `ReconKindValidator` (a `ComponentKindValidator`, so the existing `canAuthorWorkbench` component writes 422 a bad
  band; `ReconConfigLoader.bands` refuses it at run time too). The SPA keeps `{warnPct, breachPct}` internally and maps
  at the service edge; absent = `DEFAULT_BANDS`.
* **Record is day-scoped and explicit**: the Board no longer records on open; **Record run** records the selected day.
  ⚠ Decision: the button is gated `canOperateRuns` — the route's existing server gate — not the author capability the
  brief named, because an author without operate would only ever get a 403 (operator, 2026-10-09). Recording day D2
  after D1 auto-closes D1's Breaks (the lifecycle is per Reconciliation, not per day — leftover). `recon.run` takes an
  optional `day` parameter, else the latest.
* **SPA**: `<inspecto-recon-toolbar>` (day limited to `availableDays`, filter, sample, band legend with thresholds,
  author-only band editor) and `<inspecto-recon-grain-table>` (one page, `mat-paginator` 25/50/100/200) replace the
  all-rows tree on the Board and widget; `ReconPageLoader` runs every request through `switchMap`, so a newer
  day/page/filter cancels the one in flight. Day/page/filter/sample are URL query params on the Board and Breaks page,
  component state in the widget — never saved. While a request runs a spinner says *"Comparing HLR, CRM and CBS for
  2026-09-26…"*; the empty state is never shown in flight; an error offers Retry.
* **Row tint, no RAG column** (operator, 2026-10-09): rows are tinted by band (ok / warn / breach, and a key missing
  on a side) with the design-system status tones (`statusRowClasses`/`statusEdgeClasses`/`statusIconClasses` in
  `status-badge.component.ts`, the sanctioned colour owner), never a status text column. Colour is not the only cue
  (WCAG 1.4.1): a thin left edge, a shape icon whose tooltip names the band, and the band name as visually-hidden text
  in the row's first cell. The Breaks page tints its data-table rows through the new `[rowClass]` input and puts the
  glyph + hidden name in the Key cell (`bandKeyCell`).
* **Measured** (`ReconPerfTest`, `RECON_PERF=1`; 30 days × 4,000 msisdns × 3 sides, one Parquet file per day, this
  laptop): before — whole-store run 284–307 ms, record compute 391–410 ms (2,164 Breaks over all days), up to 4,000 rows
  per answer. After — the merged one-pass whole-store run 131–133 ms; one day: resolve (days list) 93 ms, first page
  125 ms, a further page from the cache 0.4 ms, sample of 1,000 keys 195 ms, record compute 297 ms (267 Breaks), 50 rows
  per answer. ⚠ The day filter does not prune files when the temporal column is a plain column (all 30 files are still
  opened); only a hive partition column used as the temporal column would let DuckDB skip them (leftover).
* **Open residuals** are tracked as `RECON-PERF-RESIDUALS-1` in [`BACKLOG.md`](../../../BACKLOG.md).

Route `/reconciliation` (Business + Builder lenses). Vocabulary is locked
([`GLOSSARY.md`](../../../GLOSSARY.md) §7): a **Reconciliation** compares **Datasets** on key columns
with per-column tolerances; a **Break** is
`missing_left | missing_right | value_break | cardinality_break` with an `open/assigned/resolved/auto_closed`
lifecycle (auto-close on re-match within tolerance). Never a parallel "comparison" concept.

**Cardinality is an ASSERTION, not a matching strategy** (`RECON-CARDINALITY-1` tier 1, 2026-09-12). A
Reconciliation may declare `cardinality: one_to_one | one_to_many | many_to_one | many_to_many`; a
violation on a **matched** key becomes a `cardinality_break` carrying the per-side row count as its
evidence (in `leftValue`/`rightValue`).

⚠ **Default `many_to_many` asserts nothing**, and the server omits the key entirely unless a cardinality
is declared — so a Reconciliation authored before the option gets a byte-identical payload.

🔴 **Why it is an assertion and not a matcher.** Each side is pre-aggregated to one row per key *before*
the join, so for the canonical case — one invoice against three payments — summing the payments and
comparing to the invoice is **already the right arithmetic**. The defect was narrower and is a
*correctness* hole: a **duplicated** row was indistinguishable from a genuinely larger value, so it
reconciled clean. The multiplicity was in fact already computed (`COUNT(*) AS mr`) and carried through the
join, then discarded as a presence boolean — this reads the number that was always there, with no new SQL
and no change to the join.

⚠ A `cardinality_break` is one per **key**, unlike `value_break` which is one per (key × column): a key
has one cardinality, not one per compare column. ⛔ Row-level pairing — *which* of the N counterparts
matched — is deliberately NOT built: the Incident attrs (`reconciliation`/`breakKey`/`breakType`/`column`/
`runId`) cannot express it, and its Break shape cannot be designed without a named workflow.

🔴 **Client seam, because the obvious one is dead.** `aggregateRecon`/`reconBreakSets` in
`recon-board.ts` are an offline mirror of the backend with **no caller since the mock backend was removed
(2026-08-31)**; they survive only as a parity mirror and are the most test-covered recon code in the SPA.
The live path is `/recon/breaks` → **`breaksFromSets`** → the Breaks page, overlaid with the recorded
lifecycle (below). Wiring a new break type into the mirror would turn specs green and change nothing in the
running app.

**Run + Break lifecycle are SERVER-SIDE operational state** (R2-03, **operator, 2026-09-26 — reverses C9**,
which kept the lifecycle in the browser). Until then the Board merged the lifecycle client-side and saved
`{…recon, breaks, lastRunAt}` through the whole-body `PUT /components/reconciliation/{id}`, gated
`canAuthorWorkbench` — so a user holding only the `operations` role (`canOperateRuns`) got a **403 on every
run**: nothing was recorded, the list read *"Last run: never"* and the Breaks page *"No first-seen date"*, and
resolving a Break was a config write that 403'd too. As built:
* **Store** — `ReconStateStore` (`inspecto-reconciliation`, `com.gamma.recon`): one JSON document per Reconciliation at
  `<write-root>/recon-state/<id>.json` = `{reconciliation, lastRunAt, runs, breaks[]}` (a Break is the SPA's
  `ReconBreak` shape, carrying its `pair` — below). Atomic write (`AtomicFiles`), one lock for read-merge-write, **fail closed** (a corrupt
  file is 503, never "never run"), the id a bare name and the file jailed with `PathJail.contains` against the
  **write root** — not `recon-state/`, so a `recon-state` directory that is itself a link out is caught (403).
  Deleting the `reconciliation` component deletes its state (a re-created id starts fresh). The mirror of
  `expectation/BaselineProfileStore` and of an Expectation's `lastResult` split.
* **Routes** (`ReconRoutes`): `GET /recon/{id}/state` and `GET /recon/state` (every saved Reconciliation's
  `{reconciliation, lastRunAt, runs}`, capped 1000 with the true `total`) are ungated reads (no write-root
  503; an unset root is 404/empty, like `/recon/promoted`). **`POST /recon/{id}/record`** and
  **`POST /recon/{id}/breaks/status {pair?, type, key, column?, status: resolved|open|assigned, note?, assignee?}`** are gated
  **`canOperateRuns`** (`CapabilityManifest` group `// ReconRoutes`), like an Expectation's evaluation. Record
  loads the SAVED Reconciliation and computes every Break of every pair itself (`ReconBreaks.compute`); status
  resolves / re-opens by identity (`pair` defaults to `AB`, `AC` on a 2-way Reconciliation is 422; a blank note
  clears it; an unrecorded live Break is appended identity-only; notes
  ≤ 2000 chars; `auto_closed` is never a caller's to set). The authoring PUT stays `canAuthorWorkbench` and
  knows nothing about state.
* **Merge** — `ReconBreaks.merge`, a faithful port of the retired TS `mergeBreaks` (+ `breaksFromSets`/
  `breakKeyOf` for the fresh set): new → `open` + `firstSeenAt = runAt`; still present → keeps `firstSeenAt`,
  a `resolved` one stays resolved with its note; gone → `auto_closed`; already `auto_closed` and still gone →
  dropped. ⚠ One deliberate widening: a still-present **open** Break keeps its note too (the TS dropped a
  re-open note at the next run). 🔴 `keyText` must equal the browser's `String(value)` of the JSON — JavaScript
  number spelling included (`1.0` → `"1"`, `1e-7` → `"1e-7"`) — or the Breaks page's overlay misses every
  recorded Break; `ReconBreaksTest` pins it.
* 🔴 **The 200-row fix.** The Board merged ONE `/recon/breaks` page (200 per set, `DEFAULT_BREAKS_LIMIT`), so
  `mergeBreaks` auto-closed every recorded Break beyond it. Record is unpaged; a set larger than
  `ReconStateStore.MAX_BREAKS` (50 000) is **refused (422), never recorded short** — truncating would
  re-create exactly that defect. Pinned by `ControlApiReconStateTest.moreThanAPageOfBreaksIsRecorded…`. On a
  3-way the cap applies per set AND to both pairs together (`…moreThanTheCapOverBothPairsIsRefused…`).
* **3-way: both pairs are recorded** (2026-09-26). `ReconBreaks.compute` runs the Break sets once per
  anchor-relative pair — A↔B (`other = 1`) and, with a third Dataset, A↔C (`other = 2`) — and tags every Break
  with `pair: "AB" | "AC"`. The pair is in the **lifecycle identity** `ReconBreaks.lifecycleId(pair, type, key,
  column)` = `pair|` + `identity(type, key, column)` (SPA: `lifecycleId()`, the byte-identical string), which
  `merge` and the status route match on — so an A↔B and an A↔C Break on one key and column age, resolve and
  auto-close independently (RA-C01: an MSISDN whose `active_flag` differs in CRM *and* CBS is two Breaks).
  Mutation-checked: dropping the pair from `lifecycleId` turns `ReconBreaksTest` and
  `ControlApiReconStateTest.resolvingAnAcBreakLeavesTheSameKeyAbBreakOpen` red. 🔴 **Migration rule:** a
  recorded Break with no `pair` (every state file R2-03 wrote) **reads as `AB`** — it was an A↔B Break —
  and is written back with `pair: "AB"` on the next write; an unknown pair is an unreadable state (503). The
  scheduled `recon.run` Job records both pairs through the same `compute`. The Breaks page tags its live
  Breaks with the pair of the open tab and overlays by `lifecycleId`, so the *A vs C* tab shows first-seen /
  status / resolve like *A vs B*; the Board's aging strip counts the open Breaks of both pairs (it reads the
  whole recorded state). ⚠ The **Incident dedupe grain is unchanged** — `breakId` / `ReconRoutes.breakIdentity`
  `(type, key, column)`, no pair — so promoting the same key/type/column from both tabs yields ONE Incident.
* **Scheduled runs record too** — `ReconRunJob` calls the same store after its Signal/Incident, best-effort:
  a refusal is logged and named in the Job result (`— run not recorded: …`), never fails the run.
* **Occurrences, recurrence, ageing, assignment** (`ASSURE-BREAK-LIFECYCLE-1`, 2026-09-26). Each recorded
  Break also carries `lastSeenAt`, `occurrences` (recorded runs it was present in), `recurrences` and an
  optional `assignee`; the status set is now `open | assigned | resolved | auto_closed`. Rules
  (`ReconBreaks.merge`, class note):
  * every run a Break is present in adds one occurrence and stamps `lastSeenAt = runAt`; a status change is
    neither a run nor an occurrence;
  * 🔴 **an auto-closed Break that reappears is RE-OPENED, not new.** The lifecycle id is deterministic and
    the state holds one record per id, so the same id again can only be the same Break: it keeps its
    `firstSeenAt` (age runs from the FIRST sighting) and counts `recurrences + 1`. Recurrence is countable
    while the auto-closed record survives — see the recurrence window below;
  * **the recurrence window** (`ASSURE-BREAK-RECURRENCE-REACH-1`, session decision 2026-09-28). An
    auto-closed record carries `absentRuns` — the consecutive recorded runs it has been gone (1 on the run it
    auto-closes, persisted only while > 0, reset by its return). It survives
    `ReconBreaks.RECURRENCE_WINDOW_RUNS` = **3** absent runs; on the 4th it is dropped. So a Break back after
    1–3 absent runs is a **recurrence** (re-opened, `firstSeenAt` kept, back to its assignee), and one gone
    longer is a **new** Break (`recurrences 0`, `occurrences 1`, stamped by that run). Before this the window
    was one run. Decided:
    * **runs, not days** — recurrence means "came back on a later run", and cadence varies per
      Reconciliation: a day window would keep an hourly one's rotating keys for hundreds of runs yet forget a
      monthly one's after a single run;
    * **3** — covers a key that skips a short outage or a late feed across a couple of runs, while a
      Reconciliation with rotating keys forgets each one after three runs instead of accumulating every key
      it ever saw;
    * **bounded twice** — by the window, and by a cap on the retained `auto_closed` records at
      `ReconStateStore.MAX_BREAKS` (50,000), dropping the longest-absent first (ties keep their recorded
      order). Present Breaks are never dropped: the >50,000 refusal of `ReconBreaks.compute` is unchanged, so a
      state holds at most one run's Breaks plus that many auto-closed records;
    * **per pair** — `absentRuns` lives on the lifecycle record, so an A↔B Break can auto-close and recur
      while the same-key A↔C one stays present (`ControlApiReconStateTest.anAbBreakRecursWhileTheSameKeyAcBreakStaysPresent`);
    * **legacy state** — an `auto_closed` record written before the window reads `absentRuns` 1 (it had been
      gone exactly one run).
    Tested in `ReconBreaksTest` and over real HTTP in `ControlApiReconStateTest` (back after 2 and 3 absent
    runs = recurrence; after 4 = dropped then new). Mutation-checked: window 1 (the old rule), no drop, and an
    unsorted cap each turn them red. The SPA's *Seen* header tooltip names the 3-run window (pinned in
    `reconciliation-detail.component.spec.ts` — a literal mirror of the Java constant; change both together);
  * `assigned` = an unresolved Break with an `assignee`. It stays assigned while present, **auto-closes like
    any other when it disappears, keeping the assignee on record**, and a Break with an assignee that
    reappears comes back `assigned` to that assignee (a recurrence returns to its owner). Resolve keeps the
    assignee; re-open (`open`) clears it;
  * **ageing is server-side and read-time**: `ageDays` = whole days `now − firstSeenAt` for an `open` or
    `assigned` Break, absent otherwise and for an unstamped one (never 0). It is added by
    `State.toWire` / `Break.toWire` on `GET /recon/{id}/state`, `POST /recon/{id}/record` and the status
    route's `break` — never persisted;
  * **assign rides the resolve route and its gate** — `POST /recon/{id}/breaks/status {…, status: assigned,
    assignee}`, `canOperateRuns`; no new route or capability. `assigned` needs a non-blank `assignee`
    (≤ 200 chars, trimmed) and only `assigned` accepts one (422 otherwise);
  * **legacy state files load**: a Break written before this reads `occurrences` 1 when a run stamped it (0
    for an identity-only one), `lastSeenAt = firstSeenAt`, `recurrences` 0, no assignee
    (`ReconStateStoreTest.aStateFileWithoutTheCountersLoadsWithDefaults`). Mutation-checked: dropping the
    recurrence increment or the return-to-assignee turns `ReconBreaksTest` red.
  * **SPA** — the per-Break view is the **Breaks page**, not the Board (the Board is one day's grain table and
    lists no Breaks). Every Break table there gains *Age* (server `ageDays`), *Seen* (`4 runs · recurred 1×`)
    and *Assignee* columns, and an **Assign** row action (`ReconAssignDialog`, pre-filled with the current
    assignee or the signed-in actor, trimmed, ≤ 200) shown only when `LensService.canOperateRuns()` — the
    route's own gate. Assign sends the Break's note back unchanged, because a status change replaces it.
    The overlay carries the counters and age of an `auto_closed` record, but not its status or assignee
    (the live Break is open again until a run records its return). The **Board**'s age strip adds
    *Assigned: N* and *Recurring: N* chips (`lifecycleCounts`, unresolved Breaks only). The status badge
    reads `assigned` as the info tone, like `open`.
* **SPA** — the Board's **Record run** button calls `ReconApiService.record(id, day)` (since RECON-PERF-1 it no longer records on open; a failure toasts
  *"This run was not recorded"* with the server's reason and falls back to `state(id)`); its aging strip reads
  the recorded state. The Breaks page reads `state(id)` and overlays status/note **and `firstSeenAt`** (it
  overlaid no stamp before, so every age read `—`); resolve/re-open calls `setBreakStatus`; Promote sends the
  recorded `lastRunAt` as `runId`. The list's *Last run* reads `states()` (`—` when that read fails, never
  "never"). `Reconciliation` no longer carries `breaks`/`lastRunAt`, and the config keys are gone from every
  sample (`spaces/demo/…/orders_regional_recon.toon`); `BundleRoutes` no longer strips them (nothing to strip).

**Aging** (`BREAK-AGING-1`, 2026-09-11). A Break carries `firstSeenAt`, stamped when it is first observed
and **carried forward by the server's merge on every later run**. Age is derived from it and rolled up by
`openAgeBuckets` into **0–30 / 30–60 / 60–90 / 90+** days, rendered as a chip strip on the Board and on the
Breaks page, plus an `Age` column on the Breaks grid. Before this a Break had a status but no time at all,
so "how long has this been broken" was unanswerable.

Four rules that are easy to get wrong and are each pinned by a test:
* 🔴 **A fresh break arrives from the engine with NO stamp on every run**, so the merge must carry the
  previous one rather than re-stamp. Re-stamping resets every age to zero each run and the view then
  permanently reads *"everything is new"* — a plausible-looking display that is always wrong.
* ⛔ **A missing stamp is `unknown`, never 0.** A live Break no run has recorded yet has no first
  sighting; reporting it as fresh makes the oldest untracked breaks look newest. `breakAgeDays` returns
  `null` and the UI shows an em-dash.
* **Buckets are upper-exclusive**, so day 30 is `30-60` and lands in exactly one bucket.
* **Aging counts UNRESOLVED breaks only** — `open` and, since `ASSURE-BREAK-LIFECYCLE-1`, `assigned`
  (`isUnresolved`; owning a Break does not make it younger). Including resolved or auto-closed ones would make
  the backlog look *older* the more of it you cleared. `breakAgeDays` prefers the server's `ageDays` and
  derives from `firstSeenAt` only when the server sent none.

⚠ The rollup lives in `reconciliation-types.ts`, not in either component: two panes deriving the same
histogram is how one concept ends up with two drifting definitions, and the "open only" rule is exactly
what drifts first. ⚠ A recorded run writes ONE instant to both the new Breaks' `firstSeenAt` and `lastRunAt`.

* **Board** (`:id` default view) — the aggregate dimension-order tree on
  [`inspecto-tree-table`](../design-system/tree-table.md): unified dimension/measure selection, parents
  carry rollups, Δ% columns **banded** ok/warn/breach (defaults 1/2 %; independent of record-level
  tolerance). Δ% is **Anchor-relative** (`datasets[0]`; 0-anchor ⇒ exact).
* **Breaks page** — drill from a Board cell to the three live record sets (only-in-A / only-in-B /
  common-but-different) with path scoping; 3-way adds a **Presence Pattern** filter bar. A value Break's
  **Δ is `B − A`** (the change along the *Field diff (A → B)* arrow: 149 → 99 is ▼ −50), the same direction
  as the Board's anchor-relative Δ%; *Impact* stays an absolute amount (R2-15, 2026-09-26).
* **3-way** — N=3 anchor reconciliation shipped (DAT-7 P4); N>3, non-additive aggs, and fuzzy key
  matching are explicit non-goals for now.
* **Reusable** — a Reconciliation renders as a **Widget** and rides bundle export/template flows
  (DAT-7 P3); persisted as the `reconciliation` component kind with real backend routes
  `/recon/columns|run|breaks` (DAT-7 P0 — the query gate pattern from the Data Browser).
* Runs are manual from the Board (no auto-refresh; each is recorded, above), **or scheduled**: the `recon.run` Job Type
  (`ReconRunJob`, 2026-07-18; contributed by `inspecto-reconciliation` through `ReconRunJobType`, a `ServiceLoader` `JobTypeProvider`, since 2026-10-07) runs a saved `reconciliation` on a `cron:` and emits a `recon.run.completed`
  Signal carrying the Break counts (`WARNING` when any break exists) — it builds the identical
  `ReconService.Spec` the interactive route does, via the shared `ReconConfigLoader`. **A breach
  (`breaks > 0`) also opens a managed `ObjectType.INCIDENT`** (2026-07-19), deduped to one open Incident
  per reconciliation (correlationId = the reconciliation id), reusing the `ExpectationRoutes`
  dedup+open pattern; the job reaches the Object Engine through the `objects` Platform Service its descriptor `requires:`
  (`JobContext.services()`, resolved at run time; an `inspecto-ops`-less bundle leaves it signal-only). Break-level assignment stays with Cases.

**The rows behind a cardinality break** (`RECON-CARDINALITY-2`, 2026-09-15). A `cardinality_break` carries per-side
ROW COUNTS; the rows themselves are gone by the time the break is detected, because `ReconService.sideSql`
pre-aggregates with `GROUP BY`. They are re-selected ON DEMAND: `POST /recon/rows {id|config, key:{col:val…}, side?,
limit?}` (`ReconService.rows`) returns each side's raw physical rows for ONE key — every column the relation exposes,
capped with `truncated` — and the detail page's tree grid gains a *Show the rows behind this break* action on
cardinality rows, rendering both sides side by side. `ReconBreak.keyValues` keeps the server's key map so the
follow-up call never parses the display string. ⛔ Deliberately NOT a pairing: which anchor row matches which
compared row for an N:M key is undefined until a pairing rule is chosen (the archived plan's open question), so
the two row sets are shown and nothing is claimed about pairs. ⛔ The key must name every key column — a partial
key is a 422, never a guess. Read-shaped (recorded in `CapabilityManifest.EXEMPTIONS`).

**Break impact** (UIE-10, 2026-09-25; carried column by operator decision the same day). A Reconciliation may declare
`impact: {column, currency}`; the Breaks page shows an *Impact (SAR)* column and the selected Break's impact,
through the shared `formatNumber`. `column` is **any** column of the reconciled Datasets:
* **Compared column** → impact is |A − B| at the Break's key, an absent side counting 0 (`breakImpacts` in
  `recon-board.ts`). Nothing new on the wire — the compared values are already on the Break.
* **Non-compared column → CARRIED.** `ReconConfigLoader` passes it to `Spec.withImpact`; the Break queries (only
  them — `/recon/run` builds byte-identical SQL) add `SUM(TRY_CAST(col AS DOUBLE)) AS mi` per side, and each
  `/recon/breaks` row gains `impact: {a, b}` (roles as the row: `b` is side C when `side: c`). A side absent at the
  key is `null`; a Dataset **without** the column is re-registered with a NULL column so its side carries `null`;
  on **no** Dataset → 422 (a misspelt column). The SPA's impact for a carried column is the value on the side that
  has it, **anchor first** — a carried column says what the key is *worth*, not how far apart the sides are (a
  subscriber active in the HLR but not billed → that subscriber's monthly fee).
* 🔴 **A carried column is never compared**, so it cannot create or suppress a Break —
  `ControlApiReconTest.aCarriedImpactColumnRidesOnEveryBreakAndNeverChangesTheBreakSet` asserts the same Breaks
  with and without it. ⚠ `serverConfig` (`recon-exec.service.ts`) must forward `impact`, or the server has
  nothing to carry and every carried impact reads `—`. ⚠ A key with no value (null both sides) shows `—`,
  never an invented 0.

**Duplicate keys on the screens** (2026-09-25). Until then `breaksFromSets` mapped `cardinality_break` but only the
*Grouped* view showed it — the default *Tables* view and the Board had no place for it, so RA-C01's 4 MSISDNs that
CBS bills twice were invisible. Now:
* **Breaks page** — a *Duplicate keys* table (Key · *Repeated on* (the "one" side(s) with more than one record) ·
  *Records* `A 1 · C 2` · Impact · Status · Resolve / Promote to Incident / *Show the rows behind this break*) plus a
  *Duplicate keys* count card. Selecting a duplicate opens the `/recon/rows` panel. Both show **only when the
  Reconciliation declares a cardinality** — `reconCardinality(recon)` reads the stored `raw.cardinality` (the model
  does not carry it) and a blank / `many_to_many` is null. Role `b` is labelled C on a 3-way *A vs C*.
* **Impact of a duplicate = the value of its extra copies** (`duplicateImpacts`): the impact column arrives SUMMED
  per side, so on a "one" side with n records the surplus is `sum × (n − 1) / n`; under `one_to_one` both sides add
  up, and a "many" side's repeats count nothing. ⚠ A separate map from `breakImpacts` (which now skips the
  cardinality set): the same key usually also carries a value break (a duplicate doubles the compared SUM), and
  one key-indexed map let the cardinality row overwrite the value break's impact.
* **Board** — `duplicate keys A·B: n · A·C: n` beside the other counts, per compared side from
  `summary.pairs[].byType.cardinality_break` (the server sends it only when a cardinality is declared); per pair
  because the flat summary mirrors A↔B and RA-C01's duplicates sit on C. The Board's `<h1>` now follows the
  Breaks page's rule — `description`, falling back to `name` — with the name (the code) leading the subtitle.
  All three screens now title through the one helper `reconciliationTitle` (description → name → id).

**Readable side and column names** (R2-16, 2026-09-26). The Board and Breaks page name each side by its
Dataset's `datasetLabels` label — the Dataset's `description`, else its `name`, else its id (the
`reconciliationTitle` order) — read ONCE on open through `DatasetsService.list()` (no cached Dataset read exists
in the SPA; a failed read leaves the ids). Board columns read `<side label> · <measure>` / `Δ% <measure>`, the
measure through the shared `humanizeColumn` (`measureLabel`: the COUNT(*) is "Records"); the tree header and the
TOTAL strip humanise too. The builder's view — side letter, Dataset id, raw column — rides in each header's
`headerTooltip` and in a `title` on the Breaks page's record-set headings. `boardColumns` without `sides` falls
back to the letters (the dashboard widget tile). ⚠ The Studio `Dataset` model reads `description` but
`toContent` still does not write it back, so a Studio save of a Dataset likely drops it (pre-existing; not verified end-to-end).

**Visual-drive polish** (R3-01…03, R3-05, 2026-09-26).
* **Dates** — *Last run* (list) and *Last evaluated* (Breaks page) print through the shared `fmtDateTime`,
  which now writes ONE spelling on every host — `26 Sep 2026, 08:05:09`, viewer's zone, month name from the
  `viz/number-format` `LOCALE` — instead of `toLocaleString()` (`9/26/2026, 8:05:09 AM` on a US host). The fix is
  in the formatter, so every `fmtDateTime` grid/detail column in the app changed with it. ⚠ `fmtWhen`'s
  absolute branch (beyond ±24 h) still uses Luxon's host-locale `toLocaleString()` — not changed here.
* **The list names its sides** like the Board (`datasetLabels`, read once per load); the row carries
  `leftLabel`/`rightLabel` so the grid re-renders when the Dataset list lands; the id is the cell tooltip and
  stays searchable.
* **Field diff** (Breaks grid and the selected-Break table) names the column through `humanizeColumn`
  (`monthly_fee_sar` → "Monthly fee (SAR)"), the raw name in the tooltip / `title`.
* **Authoring is gated on `canAuthorWorkbench`** — the list's *New reconciliation* and *Duplicate* row action,
  and the Board's edit pencil (and `edit()` itself), because each writes the `reconciliation` Component whose
  PUT the server gates on that capability. *Run* stays `canOperateRuns`.

As-built design (archived):
[`reconciliation-board-design.md`](../../../archived-documents/plans-archive/reconciliation-board-design.md) ·
review sheet: [`reviews/reconciliation.md`](../../../archived-documents/superpower-reviews/reconciliation.md).
