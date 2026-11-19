---
type: Reference
title: Link Analysis — as-built reference and design records — Studio UI record and the link index
description: Part 4 of 6 of Link Analysis — as-built reference and design records: Studio UI and accessibility record; Index; Index design record; Index scale measurements
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — as-built reference and design records — Studio UI record and the link index

Part 4 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · previous: [Evidence, the op log and Graph Run](architecture-records-evidence-graph-run.md) · next: [External references, the Dossier and the separation records](architecture-records-dossier-separation.md).

## Studio UI and accessibility record

As-built UI notes not on the [Investigation UI page](architecture-http-and-ui.md) (§6.9, §6.14); history lines dropped
where a later bullet supersedes them.

* **Starter cards, Dataset suggestions, result actions** (2026-10-10, operator walkthrough: "the screen gives a new user
  nothing to do"). Empty canvas = `inspecto-link-analysis-starter-cards`: *Follow the money (example)* loads the view
  `pickFollowTheMoney` scores by money words (hidden when none scores); *Explore a Dataset* opens the Query dock and
  `startExplore()` (the next full mapping also runs); *Open a saved view* (hidden with none). `la-starter.ts`:
  `suggestLinkColumns` (role pairs payer/payee … a/b over string-like columns; a shared remainder like `_ACCOUNT` scores
  higher), `rankDatasets`, `topSeeds`. Columns of a Dataset that declares none are probed through
  `DatasetRowsService.columns` (≤ 12, background); no row counts (no metadata carries one; no new route). A Dataset pick
  pre-fills a blank Source/Target only. `inspecto-option-picker` has an opt-in `outlined` input. A ranking row (Centrality,
  Suspicion, Similarity) → `pickNode`: emphasis, `GraphViewComponent.centerOn` (`graph.focusElement`; lands within 3px of
  centre) and selection. **Start an Investigation from the top results** fills `queuedSeeds` (top 5, pseudonyms skipped);
  nothing is created until Start (one `seed` op) or *Seed the queued entities*.
* **la-app Settings by audience** (2026-10-16): `settings.page.ts` is a compact master-detail page (`:section` route): **My
  settings** (*My browser*), **This Space** (*Link Analysis* via `canAuthorWorkbench`, *Analysis limits* via per-item
  `editable`, *Access* = `AccessRolesComponent` via `canConfigureAccess`), **This server** (*Host resources*). Every
  section listed for everyone; one not changeable opens read-only with a *Read-only* chip (the server's 403 still judges).
  Default-preserving inputs `LinkAnalysisLimitsComponent.sections` and `LinkAnalysisSettingsComponent.withLimits`. No
  unsaved-changes guard yet. Role ASSIGNMENT stays in the IdP (WSO2 groups = role names).
* **Settings ▸ My settings ▸ Investigation access** (2026-10-30, `LA-PRODUCT-NEXT-1` phase 3; leads only):
  `investigation-access.page.ts` lists Investigations the caller can open with members and ACTIVE embed links; Revoke only
  where the caller is lead AND holds `canManageIncidents` (members) or `canShareInvestigation` (links), through the
  Investigation's own routes. The last lead never gets Revoke; the owner folds in as lead. Revoking a member revokes their
  links (server side), so links are re-read. A Case-shared Investigation has no member list (its card says so). ⛔ No
  admin-wide view (a non-member gets 404 by design, D19). Not paged ("Showing the first N of M"); four reads at a time; a
  failing read degrades only its card.
* **Footer and limits** (2026-10-29, `LA-INV-EXPAND-1`; supersedes the 2026-10-16 footer status bar): no status bar under
  the canvas; graph model and Refresh in ⚙ Settings ▸ Graph model; counts on the Graph counts card, which leads with *N
  nodes · M links on screen* (`onScreenCounts()` over `canvasData`, so the Working Set while one is open) above *Totals for
  the full graph, before grouping*. **Limits** = `inspecto-link-analysis-caps` on that card (icon button
  `heroicons_outline:scale`, popover `top-full right-0`, Escape returns focus). **Installed modules** is the ⚙ Settings
  section `modules` (*This server*; `GET /modules` read when first shown, filtered to `geo-link` + `la-*`). The canvas
  render strip keeps only what the render left out (`role="group"`: axe refuses an `aria-label` on a role-less empty div).
* **Expand in the Investigation tab** (`LA-INV-EXPAND-1`, 2026-10-29): `LinkAnalysisExpandComponent` `embedded` is
  projected into the tab (`<ng-content select="[laExpand]">`, writers only; a reader sees *Needs Incident management.*);
  every entry point (toolbar, G, More ▸ Expand…, right-click) calls `openExpand()`, which opens the tab and focuses the
  section. Order: From, Degrees (`inspecto-stepper-field`, `role="spinbutton"`, inert bound buttons with reasons from
  `disabled-reasons.ts`), Direction, Expand with preview / four-eyes / why-not lines, then closed Links / Time / Limits
  folds. The embedded preview reads 300 ms after its start changes and only while active. The tab lists no steps: **Open
  the Step log** / **Investigation settings…**; Replay stays in Settings ▸ *Replay and drift*. Tab strip: plain text tabs,
  `flex-nowrap overflow-x-auto`.
* **Unsaved start** (`LA-UNSAVED-INVESTIGATION-1`, 2026-10-29): the start form (`LinkAnalysisStartBoxComponent`
  `mode="investigation"`) asks no Name or Purpose (those come on Save) — Time column, Time zone, 1-based Degrees. Unsaved
  state is `InvestigationSessionStore.unsaved` (`unsaved-investigation.ts`); `saveUnsaved` creates with
  `POST /inv/investigations` and replays each step through `/ops`, stopping at a refused or held step; no server route
  added. More steps menu = `STEP_GROUPS` (Connect · Time · Compare). Plan:
  [`la-unsaved-investigation.md`](../../../../archived-documents/plans-archive/la-unsaved-investigation.md). Later work
  (undo / redo, clean up, exclude while unsaved): [architecture-http-and-ui.md](architecture-http-and-ui.md) §6.9.1.
* **Find the important things** (`LA-FULL-CYCLE-TEST-1` phase 5, findings 16, 22, 23, 25; 2026-11-16). Decisions, each
  taken under the operator's delegation:
  * **Finding 16: `LA-UNSAVED-INVESTIGATION-1` D1 stands.** One start form, in the Investigation tab, is the recorded
    decision, and the tested user was not stuck (the pointer opened it) but was told nothing. So the empty canvas with
    Investigations is now an `inspecto-empty-state` (*Start an Investigation*: what to pick, that work is unsaved until
    Save, File ▸ Open… for earlier work) keeping the button **Start from the Investigation tab →**
    (`data-testid="la-start-pointer"` is now the empty state's host; click its inner button). The guide was the side
    that was wrong: its Studio-screen bullet described Basic's tabbed Start area as the only one.
  * **Finding 22: Build Dossier… and Members…** in the Investigation's `⋯` menu (after Settings…) and in File (while an
    Investigation is open, after Save snapshot…). Both open `InvestigationPanelDialog` (`investigation-panel.dialog.ts`),
    which hosts the SAME `LinkAnalysisDossierComponent` / `LinkAnalysisMembersComponent` as the settings folds; the folds
    stay, so settings still lists everything about the Investigation. A dialog, not "open Settings at the Dossier fold":
    the fold sits near the bottom of up to eleven in a section of ⚙ Settings, which is the finding. The File
    entries are hidden, not greyed, while nothing is open: with nothing open there is no Dossier to name.
  * **Finding 23: the approver's waiting line.** `canDecideExpansion` (`expansion-decision.ts`): a pending request,
    `canApproveLinkExpansions`, a signed-in actor (`SessionService.actor`), and not the requester. Those are exactly the
    server's own refusals (`InvestigationRoutes.decide`: route capability, 403 without a Subject, 403 for the
    requester), so nothing is offered that the server would refuse on those grounds; membership / PDP refusals still
    come back from the server and show on the line (`oversightErrorMessage`). Such a reader gets a **N to approve**
    badge in the Act header (a button that scrolls the line into view) and, instead of the requester's text, *X asks for
    your approval* (X = `SessionService.subjectName`: the Demo User's display name, the signed-in subject's own name, else
    the id; no request id) with what the expand would do beyond the limits in words (*It would add every neighbour of each
    entity, more than the 2,000 allowed without a second approval.*; no Settings keys) and **Approve** / **Deny**.
    Approve confirms first (`confirmApproveExpansion`, shared with Settings ▸ Oversight, whose Approve was one click):
    it runs the expand at once as the requester's step. Deny is a second step on the line: an optional reason
    (`pen-field`, ≤ 200, the server's 422 bound) and **Deny request** / **Cancel**. **The reason stays optional**: the
    server takes it as optional, Oversight and the console's Approvals inbox both do, the denial's who and when are
    always recorded, and a required reason with no server gate would be a rule only one screen enforces. No server
    change. The requester's own text (*Ask an approver…*) is unchanged. Not done: an inbox or badge ACROSS
    Investigations in Inspecto LA (the console has the Approvals inbox; la-app has none) — the badge shows once the
    approver opens the Investigation.
  * **Finding 25: Busiest nodes never offers a start entity.** `busiest(g, t, limit, startIds)`: a start entity (hop 0
    of the saved or unsaved Investigation on the canvas, `LinkAnalysisComponent.discoverStartIds`) is busy by
    construction, so it gets no exclusion hint and its row says *It is a start entity: the graph was drawn round it.*;
    the hint goes to the busiest non-start entity when it is at least 5 links and twice the next non-start one, both
    taken from the whole ranking (with several start entities on top, the next may lie past the five rows shown). The
    only other exclusion offers (Exclude…, Clean up) act on a selection the analyst made, so they are not offers.
* **Degrees trims the last Expand** (`LA-DEGREES-TRIM-1`, 2026-10-30): the form takes `lastExpand` and emits
  `degreesChange` after 300 ms; unsaved, `trimExpandStep` / `extendExpandStep` cut or extend from `UnsavedStep.stages` with
  no server call; saved, `GrowExcludeStore.trimLastExpand` posts one `undo` per removed step and `growInvestigation(…,
  extend=true)` adds degrees. The log is append-only, so a trim leaves undone steps; a refusal restores the stepper.
  **`LA-DEGREES-TRIM-2`** (2026-11-01): every `expand` of one Expand carries a client `group` id (`InvestigationRoutes.expandGroup`:
  non-blank ≤ 64 chars else 422; never read by the evaluator); without a run from this browser, `boundRun` binds the newest
  steps sharing the newest group. Plan: [`la-degrees-trim.md`](../../../../archived-documents/plans-archive/la-degrees-trim.md).
* **Graph text alternative** (2026-10-03): the shared graph host is `role="figure"` with an `aria-label` of the counts
  (`graphSummaryLabel`); the positive `tabindex="1"` G6 puts on its canvas layers is reset to `0` after `render()`
  (`demotePositiveTabindex`; stacked layers are one tab stop). The node-list view was removed 2026-11-02: the dock table
  (Links / Nodes, Enter selects) is the text alternative. The Analysis / View / Investigation switch is a `role="tablist"`.
  Focus on first draw (2026-10-14, spec-level): the canvas wrapper is `role="region"` *Link graph*, `tabindex="-1"`; on the
  FIRST draw out of the landing state focus moves there only if it was on the removed trigger or `body`, and a sr-only
  `role="status"` says *Graph drawn, N nodes, M links*.
* **Accessibility fixes, durable** (`LA-A11Y-AUDIT-1`, passes 2026-10-03 to 2026-11-01; axe 4.12.1, wcag2a/aa/21a/21aa/22aa
  + best-practice, both schemes): `<footer>`, `role="navigation"` on the nav host, `role="status"` on the loading bar;
  `ariaModal: true` via `MAT_DIALOG_DEFAULT_OPTIONS` (⚠ the token REPLACES `new MatDialogConfig()` — spread it or dialogs
  lose `role`; it was never copied into `projects/la-app`, so every la-app dialog was `aria-modal="false"` until
  2026-10-14); `text-secondary` not `opacity-60` on headings (light contrast); docks STACK below `md` (a row of fixed-px
  docks left the canvas 0px at 320px / 400% zoom); the busy sign-in button holds a decorative spinner + hidden *Signing in*
  (`nested-interactive`); `aria-controls` only while the bottom panel is rendered; legend link-kind toggles min 24px
  (2.5.8); *Open Investigation <title>* buttons; `<inspecto-ai-explain>` `label` input (*About Link Analysis tools*); a
  forced-colors `Highlight` outline on pressed toggles (`forced-colors.spec.ts`); a *Skip to main content* link first in the
  console shell (`skipToContent()` focuses `main#main-content` without touching the URL); switcher names *<label>, switch
  lens/space* (2.5.3). 2026-11-01 la-app reflow fixes at 320 / 640px: top bar `min-h-12 max-md:flex-wrap`; Geo query
  summary `inline-block`; the Geo page's map gets a `min-h-64` floor (page only — the dashboard Geo widget must still
  shrink). Driven live clean: Dossier and identity dialogs over a real Investigation, a real server run, Geo in `la-claims`.
  ⚠ Testing traps: finish `document.getAnimations()` before axe after a scheme switch; the ag-Grid `aria-required-children`
  / `scrollable-region-focusable` hits appear only while `ag-delay-render` is on `.ag-root`, which needs an animation frame
  the hidden Browser pane does not deliver — a **documented vendor exception with no workaround** (decided 2026-10-18);
  `inspecto-data-table` never mounts ag-Grid's empty header (empty state / skeleton). Recorded, not fixed: Material menu
  overlays report best-practice `region` (CDK overlay); in the Settings read-only state the older numeric knobs stay
  enabled while only Save is withheld (server answers 403). **Still manual:** a screen-reader reading, forced-colors paint
  (the pane cannot emulate it), WebGL map canvases. Spec-level audit table: every screen (sign-in, la-app shell / Settings /
  Entity Lists / Access, Settings > Link Analysis, Investigation tab sections, all-algorithms group, Installed modules,
  canvas, Alerts grid, data tables) clean in jsdom axe; `color-contrast` and dark scheme are real-browser steps.
* **Still open** (`docs/BACKLOG.md` §3.12): `LA-A11Y-AUDIT-1` (manual items above). The la-app OIDC run against a real IAM
  closed 2026-10-13 ([admin guide](admin-guide-la-core.md)).

## Index

*D-3, as built 2026-10-02 to 2026-10-06.* The edge/node index of a Dataset — Parquet under `<Space write root>/la-index`,
built by `IndexBuildService` (`inspecto-la-storage`), exposed by `IndexRoutes` (`inspecto-la-api`). Layout, bucket,
build memory, modes, staleness, build service, reader, served routes and the age gate: [architecture-la-core.md](architecture-la-core.md)
§4; reasons and measurements: [Index design record](#index-design-record). Below: route contracts and SPA.

* `IndexBuildService.close()` records every cancel BEFORE interrupting the pool, so a builder that throws on interrupt still
  ends `CANCELLED`, never `FAILED`.
* **Routes.** `POST /inv/index/builds` (`{dataset, sourceCol, targetCol, kindCol?, timeCol?, timeColZone?, weightCol?,
  attrCols?, mode?}`, 202 + Location; 409 second live build of the same mapping, the build id named only to its starter;
  422 bad column or estimate over budget) · `GET /inv/index` (viewable Datasets' current versions: rows / bytes / builtAt,
  `droppedNull`, `tables {name: {rows, files, bytes}}`, `versions [{version, bytes, current}]` from manifests, `stale` +
  `reason` + `reasons` + `removedInput` + `fingerprint: known|unknown` + `inputFiles`, `deltas`, `plan`) · `GET …/builds/{id}`
  · `POST …/builds/{id}/cancel`. Every route runs the base-Dataset view gate first: an unviewable Dataset and its builds are
  the same 404 as absent (valid only while no per-Subject row filter exists).
* **Capability and settings.** Start needs `canBuildLinkIndex`; cancel is starter or administrator. `index {enabled,
  max_disk_bytes, keep_versions, threads, queue}`; the estimate (rows × 34 × 2; 0 = no limit) is counted after the duplicate
  check, bounded (10 s, ≤ 2 at once; timeout 422, busy 503, never skipping the budget). Audit `LINK_INDEX_BUILD_STARTED |
  _COMPLETED | _CANCELLED | _FAILED`.
* **Pinned versions (D7-2).** `IndexStore.pins()` (`pins.json`): gc never deletes a version with an unexpired pin (30-day
  TTL, warning in the last 7 days; an unreadable pin file → gc deletes nothing); the reader pool spares pinned readers.
* **Recursive paths from the index** (step 5): only when `index.enabled` is not false, a published index matches source /
  target, covers weight, temporal and every `filter` field (not the weight column), `maxDepth` ≤ 2 and ≤ 20 distinct
  frontier keys per level; else the flat Dataset answers as before. Every answer carries `source`: `{kind:'index', version,
  stale, staleReason?, fingerprint}` or `{kind:'dataset', reason, details?}` (closed reasons `index_disabled`, `no_index`,
  `mapping_not_indexed`, `column_not_indexed`, `time_zone_not_servable`, `filter_not_indexed`, `index_stale_refused`,
  `depth_over_index_cap`, `frontier_over_index_cap`, `index_read_failed`, `rung_not_indexable`, `stale_unknown`). A frontier
  over the cap mid-walk discards the partial answer and re-answers whole from the flat Dataset. Staleness = the
  `GET /inv/index` computation: removed / replaced input, changed relation SQL, different bucket function or unapplied
  deltas → refused (removed rows could be exposed); files only ADDED → served `stale: true`; a DuckDB version difference →
  served stale; unlistable files → served `fingerprint: 'unknown'`. Fingerprint cached 30 s per (Dataset, mapping), dropped
  on build completion. `link.traversed` gains `source`, `indexVersion` / `indexStale` / `fingerprint` /
  `indexStaleReasons` or `sourceReason`.
* **Neighbours and Investigation `expand`** (step 6, 2026-10-03; `IndexedRead.select` shared): `/inv/projection/neighbors`
  folds the value's `out` and `in` copies (self-loop once), flat order `cnt DESC, source, target`, cut at `limit`; needs
  `linkKindCol`, every `attrCols` entry and `filter` field indexed. An `expand` uses the index only for a SIMPLE rung (no
  `window`, `minDistinctDays`, candidate degree bound, merged traversal, `only`; ≤ 20 frontier entities) and reproduces the
  CTE's fold, `excluded`, `linkKinds`, direction (incl. `reciprocal`), `minEvents`, per-anchor `maxFanOut` with
  `fanOutCapped`, and the budget cut, so the sealed `fingerprint` is identical on both paths. The sealed read carries
  `read.index = {version, stale, fingerprint}` BESIDE the fingerprint; `replay` with `reread` adds `indexVersionSealed` /
  `indexVersionNow` to drift rows (`diverged` stays about the fingerprint). Why the flat path answered:
  `read.fallback = {reason, details?}` beside the fingerprint, outside the state hash (DR-U4, 2026-10-10); audit
  `link.investigation.stepped` gains `source` / `indexVersion` / `indexStale` / `fallbackReason`.
* **Graph Run from the index** (step 7, 2026-10-03): `input: "index"` with `dataset`, `sourceCol`, `targetCol`,
  `linkKindCol?`, and `seeds` (1..20) for `degreeCentrality`; only `neighborhood` (hops ≤ 2), `egoNetwork` and seeds-only
  `degreeCentrality` (engine `index`, listed per algorithm in `engines`). Every unservable case is a stated 422
  (`index_disabled`, `no_index`, unfit mapping / column, `index_stale_refused`, non-native algorithm, hops > 2, seeds > 20,
  `at`, an Investigation that hides entities); a walk past 20 keys per level (the final induced-edge pass included) ends
  `BUDGET_EXCEEDED` / `INDEX_CAP`. Pre-work size check uses the seeds' degrees. Response adds `source` and `input.kind:
  "index"`; cache key carries the index version; audit `link.graph.run.*` gains `source` / `indexVersion`. Order
  canonical-v1. Detail: [D-3 design](../../../../archived-documents/plans-archive/la-separation-d3-design.md) §5.6.
* **Incremental append, compaction and the plan** (step 8, 2026-10-03): `mode` `full` | `append` | `compact`. `append`
  indexes only input files added since the live version as a self-sorted delta and publishes the next immutable version
  (parent files hard-linked, manifest `builder: append`, `parent`, `deltas[]`); reads union main + deltas through the
  reader's glob, so every read path answers exactly what a full rebuild answers. Sound only for pure additions — a removed,
  superseded or rewritten file, changed relation SQL, bucket function or DuckDB version, a Dataset that is not a plain local
  store read, no new file, or 8 deltas already is a 409 naming why. `compact` merges deltas by reading the INDEX, restoring
  one file per bucket. `plan {recommended: none|append|full|compact, appendable, reasons, added, removed, changed, samples}`
  is advice only. The per-append `nodes` rows are per-file partial folds (nothing reads them; compaction recomputes).
* **Staleness detail.** Grounded in `DatasetProvider.inputFingerprint`: an added file → `input_files_changed`,
  `removedInput: false`; deleted or touched → `removedInput: true`; a view, > 10,000 files, a listing past its 2 s budget or
  reached after `GET /inv/index`'s 5 s request budget → `fingerprint: unknown` with `fingerprintReason` (`no-files` /
  `too-many-files` / `timeout` / `budget`), never claimed current.
* **SPA: Run on index** (2026-10-03): offered only when the algorithm's `engines` include `index` AND `GET /inv/index` is
  `enabled` with ≥ 1 index (otherwise absent, never disabled-with-fallback). Sends the index's OWN mapping; several indexes
  → an Index picker; client fences state themselves (hops > 2, seeds 0 or > 20, no Investigation, no capability). The
  Explain tab hosts index-only `neighborhood`, `egoNetwork` and seeds-only `degreeCentrality` (*Node to score* chips). An
  index answer is NOT applied to the canvas (no apply path): version, stale chip, a summary. A 422 is worded by
  `indexRefusalMessage` + the server's sentence, never retried on the Working Set. Specs
  `link-analysis-server-run.index.spec.ts`.
* **SPA: Edge index tool** (`LA-INDEX-SPA-SURFACES-1`, closed 2026-10-04; lens `canBuildLinkIndex`, no Access-Catalog
  node): lists version, deltas, stale chip and the `plan` advice; **Build mode** radios preselected from the advice; polls
  the build and refreshes on COMPLETED; a mode the server cannot apply is its 409 in words (`indexBuildErrorMessage`), never
  retried as another mode. *Cancel build* (`indexBuildCancelMessage`). *Index a different mapping instead* (`index-mapping.ts`)
  starts with `mode: 'full'`; a zone never travels without its time column. DR-U5: shows the in-force serving state, an
  Index/Flat line per mapping, and builds the FIRST index. `expandSourceNote` (one `role="status"` sentence:
  `Answered from the link index vN` / `Answered from the Dataset because <words>`, `FALLBACK_REASON_TEXT` in
  `index-source.ts`) also shows under *Find paths (server)* and after a canvas neighbour expand. ⚠ The log step line adds
  `read from link index vN` only when the log view carries `read.index`, which today it strips. Not driven live.
* **Scheduled build** (`la.index.build`, LA-DAILY-INGEST-1 T5, 2026-10-06; wiring: [architecture-la-core.md](architecture-la-core.md)
  §4, §7): triggered by `pipeline.commit` (`job.dataset.produced` is emitted only by the `sql.template` Job); `attr_cols`
  must be a comma string (a TOON array fails closed `MAPPING_INVALID`); runs only the mode `plan` recommends (`append`;
  `compact` then `append` at the 8-delta cap; `full` only with `allow_full`); a server refusal is one recorded `REFUSED`,
  never a retry; audit `LINK_INDEX_SCHEDULED_RUN`. The `owner` is server-stamped on save unless the saver holds
  `canConfigureAccess` (`JobAuthority.stamp`, at every write door incl. Space Template seeding); the run reads every input
  only from the saved config. Config shape and residuals: [roadmap](../../../../superpower/link-analysis-roadmap.md) "T5
  as built".

## Index design record

*Scope, decisions, measurements, seams.* Retired design
[archived](../../../../archived-documents/plans-archive/la-separation-d3-design.md); steps 1-8 built; step 1 was a gated
spike (`InvIndexSpikeBench`).

**Scope: Path A (operator 2026-10-02), set by the spike.** At 10^8 edges on one laptop (i7-9850H, 32 GB, DuckDB 1.5.2,
warm, heavy-tailed, mean degree 5): one hop through the sealed path **43 ms** (flat unsorted file 1,031 ms); full build
**368 s** (peak working set 17.9 GB); the Java-driven 5-level walk **3.0 s / 4.3 s p50 against a 1.5 s criterion: FAIL**.
Only an equality on ONE key reaches the scan as a zone-map filter; a multi-key `IN`, `OR`, `VALUES` join or `unnest` read
most row groups (~72% of rows at 50 keys: `IN` 3.2 s, `VALUES` join 4.2 s), and per-key `UNION ALL` is linear (0.7 s at 20
keys, 2.2 s at 50). So the index serves single-entity lookups, one hop both ways, **depth ≤ 2 and ≤ 20 keys per level**
(`IndexedTraversal.MAX_DEPTH` / `FRONTIER_CAP`, the same 20 in `SqlGraphEngine`) and seeds-only `degreeCentrality`; deep or
wide multi-hop, global and iterative algorithms and bidirectional BFS stay on the flat CTE and the in-memory engine. Depth 3
is derived (~1.4 s, no margin), never measured, so it is out.
⚠ **Where the index loses:** through the route every request pays a fresh sealed connection (~100-200 ms); at 10^6 edges it
is NOT faster (one hop 209-466 vs 293-373 ms flat; a 20-key depth-2 walk loses ~3x); from 10^7 it wins 2-5x for a hop and
1.5-3x at depth 2; 10^8 not re-run through the route. Hence `index.enabled` defaulted false until 2026-10-10 (operator: on
by default; an unfresh index still falls back).

**Layout details beyond architecture §4.** `CURRENT` is a one-line pointer (temp file `force`d then `ATOMIC_MOVE`). Ids are
RAW values cast to VARCHAR (masking stays at the route, after the caches, so a mask-mode change needs no rebuild); `ts` naive
UTC, the effective zone in the manifest. Size is 34 bytes per edge for all three tables (3.43 GB at 10^8, 1.28x the flat
file), so the disk estimate is `rows × 34 × 2` with no node term. Classes: la-storage (host-free; DuckDB JDBC its only
non-`inspecto-*` need) `BucketFunction`, `IndexMapping`, `IndexManifest`, `IndexStore`, `IndexBuilder`, `IndexBuildService`,
`IndexPlan`, `IndexReader`, `IndexedTraversal`, `SqlGraphEngine`, `RoutingGraphEngine`; la-core ports `DatasetProvider`
(`relationSql`, `inputFingerprint`, `relationSqlOverFiles`), `InputFingerprint`, sealed `GraphInput`, `IndexCapExceeded`;
la-api `IndexRoutes`, `IndexedRead` (the ONE gate, closed `Reason` enum), `IndexedRecursivePaths`, `IndexedNeighbors`,
`IndexedExpand`, `IndexStaleness`, `InputFingerprintCache`, `IndexBuildServices`. `ALLOWED` in `check-module-deps.mjs` and
each pom's enforcer list agree (la-storage → la-api and la-core → la-storage stay red); `bundle-modules.mjs` stages
la-storage. `IndexReader` seals its sandbox with `sealAllowing` = that version directory only,
`enable_external_access=false`, configuration locked. The builder's own DuckDB takes only trusted relation SQL from the port
AFTER the view gate and never sees a Subject.

**Signed decisions (1-8, operator 2026-10-02).**
1. One index per (Dataset, edge mapping) under the Space write root (cross-Dataset and per-Investigation indexes are other
   products).
2. **Row scope (R3) is Dataset-level:** every read runs the base-Dataset view gate (revocation bites on the next read);
   `relationSqlHash` marks a definition change stale. Valid ONLY while a Dataset has no per-Subject row filter; the day one
   lands it becomes a per-scope index.
3. Entity-hash buckets, sort (entity, ts). **Amendment: `md5_number_lower(entity) % N`, not DuckDB `hash()`** —
   `hash()` is deterministic across connections and processes but not reproducible in Java nor proven stable across DuckDB
   versions; MD5 matches Java `MessageDigest` (bytes 8-15 little-endian, `Long.remainderUnsigned`) on 1,004/1,004 ids and
   4,000+ in `BucketFunctionTest`, builds ~16% slower, looks up at the same speed. The manifest records `bucketFn` and
   `duckdb.version` (mismatch = stale). MD5 byte order inside DuckDB across versions is still not proven.
4. The builder is an LA-owned `IndexBuildService` with its own routes (la-* cannot import `JobService`); no `IndexBuildPort`.
   Scheduling later came as the `la.index.build` Job Type through the `link-index` Platform Service.
5. Full build first; append and compaction only on explicit request — staleness is reported, never silently acted on.
6. Serve a stale index flagged, pin the version at Graph Run submit, record `read.index` BESIDE the fingerprint; refuse only
   when removed rows could be exposed. `read.index` is provenance, NOT a replay pin (promoting it needs a decision).
7. One module, `inspecto-la-storage`.
8. Automatic selection for read routes when `index.enabled` is on, always echoing `source`; a Graph Run needs explicit
   `input: "index"` and never falls back to the Working Set.

**Gotchas worth keeping.**
* **Fingerprint cost:** ~0.19 ms per file (1 file 3 ms, 1,000 files 182 ms, 10,000 files 1.9 s; Windows, warm), one listing
  per indexed Dataset per `GET /inv/index`; cap 10,000; cache 30 s, ≤ 256 entries. The manifest records ≤
  `IndexManifest.MAX_INPUT_FILES` = 5,000 files; above that only the hash, so such a Dataset rebuilds in full only.
* **Consignment SQL hash:** a `physicalRef` relation SQL pins the file list, so `IndexBuilder.relationSqlHash` blanks the
  list inside `read_parquet(` / `read_csv(` before hashing (the hash is the DEFINITION; files are tracked by the
  fingerprint). Manifests from before the fix read once as `relation_sql_changed`. A list of ~20 long paths once threw
  `StackOverflowError` in that regex (surfaced as `index build failed: null`); fixed with possessive quantifiers
  (`IndexBuilderTest.theRelationHashSurvivesALongPinnedFileList`).
* **Two copies of the data:** a rebuild peaks at ~2x (old and new coexist until GC; `keep_versions` default 2; a stage
  touched in the last 5 minutes is never collected).
* A non-UTC `timeColZone` makes a temporal constraint unservable (`time_zone_not_servable`: the flat path compares wall
  clock, the index stores UTC instants). A filter on the weight column is unservable (stored as DOUBLE).
* `neighbors` and `expand` fold EVERY row of a value, so a node of millions of rows is bounded only by the 5 s statement
  timeout (then `index_read_failed`, flat answers). When the per-level `LIMIT` yield fence fires the rows differ from the
  flat path's (no `ORDER BY`); only the flag compares.
* An `egoNetwork` of a node with > 19 distinct neighbours ends `INDEX_CAP` (the induced-edge pass looks up one key per kept
  node). Exactness over reach; a hub is a Working Set job.
* Mutation-pinned (each red): Java bucket off by one, partial walk served instead of fallback, view gate removed, frontier
  cap disabled, version missing from the cache key, `index` silently rerouted to `workingSet`, "removed file forces a full
  build" in `IndexPlan`, reader glob narrowed to main files.

**Not built (D-3).** `window` / `at`, hidden entities and `merged` rungs on the index; auto-compact; `IndexSubgraph` and deep
multi-hop (Path A); a completed 10^9 run; cross-Dataset graphs.

## Index scale measurements

*`LA-INDEX-SCALE-MEASURE-1`, 2026-10-05 to 2026-10-21.* Harness `IndexScaleBench` (la-storage tests;
`-Dinspecto.bench.dir`, `-Dinspecto.bench.edges=N`; knobs `deltaEdges`, `maxDeltas`, `skipDeltas`, `skipCompact`,
`buildOnly`, `buildMemory`, `threads`, `samples`; never in the default suite). Method: D-S5 skew corpus (source ~ nodes ×
u^3, target ~ nodes × u^2, nodes = edges / 5 — the id DOMAIN, see the correction below) as real Parquet, a real FULL build,
1-8 appended deltas of 100,000 edges, then `COMPACT`; reads through `IndexReader.edges` and `IndexedTraversal.walk`
(undirected, depth 2) over 60 random keys after one warm-up. Windows laptop, 12 hardware threads, warm.

| Edges | FULL build | Index size | 1 key p50 / p95, 0 deltas | 1 key p50, 8 deltas | 1 key p50, compacted | depth-2 walk p50 / p95 |
|---|---|---|---|---|---|---|
| 10^6 | 4 s | 0.02 GB | 26.6 / 43.5 ms | 35.3 ms | 18.3 ms | 229 / 538 ms |
| 10^7 | 22 s | 0.23 GB | 20.2 / 27.3 ms | 29.9 ms | 15.7 ms | 32 / 489 ms |
| 10^8 | 447 s (32 buckets, 8 GB cap) | 2.23 GB | 20.9 / 31.0 ms | 37.8 ms | not run | 21.8 / 32.0 ms |

* **`K = 8` holds:** ~+0.2 ms per delta on a single key at 10^8 (20.9, 24.4, 27.8, 33.9, 37.8 ms at 0-8); a hub key 36.2 →
  56.2 ms; a 20-key lookup 408 → 766 ms; no cliff; compaction restores the single-file figure.
* **Latency is flat in N** for a key lookup (~20 ms from 10^6 to 10^8). A 20-key frontier is ~0.4 s at every scale (~20 ms
  per key, sequential), so the 20-key cap, not N, bounds a walk; the 5 of 60 walks refused `FrontierOverCap` at 10^8 are hubs.
* **Caveats:** no weight / extra columns (22 B per edge here; does not retire the 34 B estimate); fixed delta size; COMPACT
  not run at 10^8.
* **10^9 is not measured.** Build time grows ~20x per 10x, so ~2.5 h. 2026-10-13: the 10^9 corpus (1,519 s, 20 GB) was
  built but the FULL build stopped at the 30% free-disk floor (21.8 GB written): it needs > ~65 GB working disk beside the
  corpus (a host with ~250 GB free and ≥ 16 GB build memory). Runs at 5x10^8 (8 GB, then 12 GB cap), 3x10^8 and 2x10^8 (6 GB)
  each failed with Out of Memory in the then-global `nodes` phase (5x10^8 at 12 GB: `out` 24.5 min, `in` 52.5 min, OOM at
  11.1 GiB; sampled peak working set 11.77 GB; bench dir peak 22.8 GB). Their "distinct nodes" and "B/node" readings are
  WITHDRAWN (the id domain, not distinct nodes). The `nodes` phase is now bounded (below), but no build above 10^8 has been
  re-run. Recipe for a large host: `tests-index-scale-run.md`.

### The `nodes` phase and the degree statistics, bounded

*2026-10-21, lane `la-nodes-mem`.* **What was wrong.** `nodes` was one statement over the whole edge relation (two global
`GROUP BY`s each with `count(DISTINCT (neighbour, kind))`, a `FULL JOIN`, a global `ORDER BY`); its state is the set of
distinct (node, neighbour, kind) links, about one per edge, so it grows with the EDGE count. The corpus has at most ~2x10^6
distinct ids at any scale (587,878 at 3x10^6 edges, 1,551,076 at 3x10^7, 1,778,237 at 10^8). DuckDB spilled it but not
enough: with a spill directory it died `failed to pin block` at 256 MB for 3x10^7 edges / 8 threads (completed at 512 MB),
64 MB for 3x10^6 / 1 thread (96 MB completed), 128 MB for 10^7 / 2 threads. A global `GROUP BY src, dst, kind` rewrite
died at the same 256 MB (the structure, not the spelling). Failure is not monotone in the cap: at 10^8 the old code
completed at 8, 2 and 1 GB. The degree table's per-key `count(DISTINCT)` had the same flaw (died at 2 GB over 10^8).

**The change** (`IndexBuilder.copyNodes`, `linkStats`): `out` and `in` first; `nodes` folded one bucket at a time from those
written tables (per non-empty bucket one `COPY` of the `FULL JOIN` of that bucket's `out` and `in` folds — a node's two sides
share its bucket), sorted by id, into `nodes/bucket=B/data_0.parquet`. Working set = one bucket (~4x10^6 edges); the relation
is no longer scanned for `nodes` (it was scanned twice). Degree table = `SELECT x, count(*) FROM (SELECT DISTINCT x, o …)
GROUP BY x`.

**Same index.** `IndexNodesFoldTest` (9 cases; the replaced statement kept verbatim as reference, both ways `EXCEPT ALL`,
column types, bucket placement, id order, one file per non-empty bucket, no dir for an empty bucket, `IndexBuilder.verify`;
1 and 5 edges over 64 buckets, 400 over 1 and 7, 5,000 and 40,000 skewed with NULLs, parallel and self edges, a mapping with
neither kind nor time, FULL + two APPENDs + COMPACT vs one FULL). `IndexLinkStatsParityTest` (kind counts, degree quantiles,
histogram, node counts per type; untyped, typed, many-to-one key, two-type endpoints). At scale: two FULL builds of 3x10^7
(old, new) are multiset-identical incl. bucket; at 10^8, 4 of 32 buckets (221,912 nodes) equal the old statement.

**The bound at small scale** (gated `IndexNodesMemBench`): 3x10^6 edges, 64 MB, 1 thread — FULL completes (73 s first run:
out 24, in 25, nodes 9.7, statistics 13), the replaced statement OOMs (60.9/61.0 MiB). 3x10^7, 512 MB, 2 threads — completes
(214 s: out 63, in 62, nodes 14.5, statistics 69.7); the old degree statement OOMs (485.4/488.2 MiB).

**Whole FULL builds at 10^8 edges** (32 buckets, 8 threads, seconds; shared noisy host — phase times indicative):

| `buildMemory` | code | out | in | nodes | statistics | total | result |
|---|---|---|---|---|---|---|---|
| 8 GB | unmodified | 115 | 212 | 420 | 270 | 1024 | completes |
| 8 GB | new | 117 | 235 | 36 | 180 | 575 | completes |
| 2 GB | unmodified | 161 | 179 | 547 | - | - | **OOM in the statistics** (1.8 GiB) |
| 2 GB | new | 146 | 167 | 35 | 235 | 590 | completes |
| 1 GB | unmodified | 153 | 163 | 695 | 316 | 1334 | completes |
| 1 GB | new | 157 | 160 | 38 | 280 | 642 | completes |
| 512 MB | new | OOM in `out` after 88 s | | | | | `out` (unchanged) needs > 0.5 GB with 8 threads |

At 3x10^7 (4 GB, 8 threads): 276 s (nodes 112, statistics 66) old vs 153 s (nodes 14.8, statistics 40) new. `nodes` now
costs ~0.35-0.5 µs per edge at 3x10^7 and 10^8 (old 3.7 and 4.2-7.0). Peak working set follows the cap (1.4 / 2.4 / 8.1 GB
at 1 / 2 / 8 GB: DuckDB fills what it is given), so it is no measure of need — the lowest cap that completed is. Entity
cardinality and the production `index.build_memory_pct`: [architecture-la-core.md](architecture-la-core.md) §9, §10.

**Not measured.** Any build above 10^8 edges; the `out` / `in` sorts and the statistics' other global steps beyond 10^8; a
corpus with a realistic node count; APPEND, COMPACT and read latency at these caps.
