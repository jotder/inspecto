---
type: Concept
title: Link Analysis — Investigation UI
description: The SPA side of a Link Analysis Investigation - Grow, Held and Exclude on the graph, the Investigation tab as one Expand filter, and the Studio UI reorganization (sections 6.9 and 6.14 of the backend architecture).
resource: la/
tags: [module, link-analysis, investigation, spa, studio, architecture]
timestamp: 2026-11-12T00:00:00Z
---

# Link Analysis — Investigation UI

The SPA side of an Investigation: Grow, Held and Exclude on the graph, the Investigation tab as one Expand filter, and
the Studio UI reorganization.
Section numbers continue those of [architecture-backend.md](architecture-backend.md), from which this page was split on 2026-11-12.

## 6.9 Grow, Held and Exclude on the graph (LA-GROW-EXCLUDE-1, SPA, as built 2026-10-22)

The analyst side of the plan (archived:
[`la-grow-exclude-plan.md`](../../../../archived-documents/plans-archive/la-grow-exclude-plan.md); decisions GE-1…GE-12
built as recommended). How it reads to a user: [user guide §7](user-guide-link-analysis.md#7-expand-and-clean-up-the-graph) "Expand and clean up the graph".

- **Seams.** `grow-exclude.ts` is the pure half (wording, the request a Grow sends, lists in force, what they removed,
  the hint store); `GrowExcludeStore` (provided beside `InvestigationSessionStore`) holds the session state and the
  Investigation actions; the Grow panel, Held tray, Exclusions panel and Exclude dialog are presentational. Every recorded
  change is an existing op — `expand`, `exclude`, `excludeBy`, `undo` (GE-12) — so replay and the Dossier are unchanged.
  The explorer graph (Basic, or the query graph in any edition) is the `basic` mode: nothing recorded.
- **Preview (GE-3).** An Investigation previews with `POST …/expand/preview` over exactly the `expand` body the Grow
  would append (`growExpandBody`). The explorer previews with ONE `POST /inv/projection/neighbors/preview` over the
  whole frontier — `{dataset, sourceCol, targetCol, linkKindCol?, attrCols?, filter?, ids, drawn?, direction, linkKinds?
  (only with a kind column), hubThreshold}` (`neighboursPreviewBody`) — so its counts are distinct neighbours, not a
  per-node sum (the first cut sent one `{value}` call per node and over-counted shared neighbours; the server still accepts
  `value` as `ids: [value]`). ⚠ **Parity with the Grow (fixed 2026-10-22):** the Grow is one neighbours read per frontier
  node merged into `graph()`, so the preview must read with the SAME binding — the run query's `attrCols` and `filter`,
  which `expandNode` passes — and must not count what is already drawn: `drawn` = every other node of `graph()` (hidden
  ones too) by its spellings, the frontier's graph neighbours first, capped at `input.max_ids` and `input.id_length`
  (`drawnValues`); its links are the server's `addsLinks` plus `linksWithinGraph` less the `graph()` links touching the
  frontier in the Grow's direction and kinds (`withUndrawnLinks`). Raw spellings that one typed node folds still count
  once each on the server (pre-existing, small). Before the
  fix a graph its query drew whole ("Follow the money", 112 nodes) previewed "about 5 nodes and 10 links" — neighbours of
  the first 25 nodes that were the other 87 — and the Grow then said 0 and 0; a filtered view previewed neighbours only its
  unfiltered rows reach. Never fetch the neighbours themselves to preview: each `/inv/projection/neighbors` read emits
  `LINK_EXPANDED` and spends the LA rate budget, which a preview must not.
  0 / 404 / 405 / 501 / 503 read as *No preview on this server* and Grow still runs; 404 / 405 / 501 are remembered per mode
  for the session. Any other status, a 403 included, shows the server's reason in the panel (`previewFailure`), never a
  toast. *2 steps* is offered only when a ready preview says the first step stays under the frontier cap.
- **The explorer sends no `excludeBy`.** Its neighbours read (`/inv/projection/neighbors`) cannot apply a list, so a
  preview counting one would promise removals the Grow does not make. Category lists therefore apply to Investigations
  only (auto-apply on create, [architecture-http-surface.md](architecture-http-surface.md) §6.8); the explorer's Exclude hides locally and can add to a list. **Decided 2026-10-24**
  (assistant under operator delegation, pending confirmation): not added. The read emits `LINK_EXPANDED` per call and
  records nothing, so a policy list applied there would be unaudited removal; adding `excludeBy` needs the capability,
  audit, restricted-list and masking gates of [architecture-http-surface.md](architecture-http-surface.md) §6.8 re-proved on a second route for a mode that has no record to replay.
  Also decided: Grow previews stay unaudited (read-shaped, nothing changes; a restricted list's count is already rounded
  or refused), and the SPA keeps naming the user-guide chapter instead of linking it until it serves docs.
- **Undo.** A Grow / Exclude result line offers Undo while the steps it made are still the newest effective steps of the
  log as last read (`valid()`); it sends the `undo` op once per step, newest first, each naming its step
  (`{undoes: step}`). Ctrl+Z runs that Undo first, then the display undo. The explorer's Undo restores the graph
  snapshot only while the graph is the one the Grow drew.
- **The Undo names the step it means** (`LA-GROW-EXCLUDE-UNDO-RACE-1`, fixed 2026-10-23). In a shared Investigation
  another lead may record a step after this browser last read the log; the old route undid the newest effective step,
  so that step was the one taken back. `POST …/undo` (and a Draft's) now takes an optional body `{undoes: step}`
  (`InvestigationRoutes.expectedUndo`: a whole number from 1, else 422): when the newest effective step is another one
  the answer is **409 `CONFLICT_STALE_VERSION`** naming it (*"step 2 is not the newest step any more: step 3 (keep by
  lead-1) is - read the log again; nothing was undone"*) and nothing is written. Checked inside `untilWon`, against the
  log each attempt reads, so a step that lands while the undo retries is caught too. Without `undoes` the route is
  unchanged. The SPA always names it: the Grow / Exclude result line (`undoOf`), *switch a list off* (the list's step)
  and the op log's Undo button (`InvestigationSessionStore.undoTarget`: the newest effective step read; none while
  the log read was cut, `truncated`). On a refusal the store re-reads the log, so the screen shows the newer step,
  the refusal stays in the error line and the result line's Undo is no longer offered.
- **Switch a list off = the `undo` of its step**, so only a list applied by the newest effective step can be switched off;
  a list applied earlier, or in several steps, says why (`listsInForce`). A "skip this list for one Grow" would need an
  `expand` parameter nobody has specified — deferred (2026-10-24, assistant-decided under operator delegation, pending
  confirmation: keep as is; an older list is switched off by undoing back to its step, which the log records).
- **First-time hints** (Grow panel, Held tray, Exclusions panel): each dismissible with *Got it*, remembered per browser in
  `localStorage` (`inspecto.la.hint.grow` · `.busy` · `.exclusions`), every read and write in try/catch so a private
  window shows the hint again and never errors. Each names the user-guide chapter *Expand and clean up the graph* (`GROW_GUIDE`, user guide §7)
  as text, not a link: the SPA serves no docs (they ship in the bundle's `docs/` folder).
- **Pack-aware wording.** Every count, tooltip and hint speaks the active pack's node / link words (`packTerms`, from the
  profile labels: Telecom *subscribers / calls*, Mobile Money *wallets / transfers*); the technical term (expand, super node,
  exclusion list) stays in the tooltip.
- **Keyboard.** **G** Grow, **X** Exclude…, **Ctrl+Z** Undo — not in a text field, grid, menu or dialog; listed in the main
  app's `?` shortcuts help (`shortcuts-help.dialog.ts`) and, since 2026-10-23, the standalone `la-app`'s: its shell
  opens the same dialog on a bare `?` (never in a text field) with its own list, `LA_APP_SHORTCUTS` (no command palette
  or triage-list keys, which la-app does not have), passed as the dialog data `{shortcuts}`.
- **Access Catalog.** `canManageExclusionCategories` has its action node since 2026-10-23: `linkgraph.exclusion-categories`
  (the id `LensService` reads), under Settings, where the Exclusion categories section is its main client gate. Since
  `LA-ACCESS-NODE-IDS-1` (2026-10-23) `canRevealLinkEntities` / `canApproveLinkExpansions` read their nodes' ids
  `linkgraph.reveal` / `linkgraph.approve`; `access-lens-parity.spec.ts` fails on any node/lens id drift
  (detail: `module/access/admin-guide-auth-security.md`).
- **Gotchas and deferrals** (open rows in [BACKLOG](../../../../BACKLOG.md), `LA-GROW-EXCLUDE-RESIDUALS-1`):
  - *Previews are not audited*: neither preview route writes an audit record or event (read-shaped, rate class EXPENSIVE).
  - *A broken auto-apply list blocks every Investigation create* in the Space (fail closed, the refusal names the list)
    until an administrator fixes or retires it.
  - The think-aloud test with two new users (`tests-think-aloud-script.md`) was not run (`LA-GROW-EXCLUDE-THINK-ALOUD-1`).
- Tests: `grow-exclude.spec.ts`, `grow-exclude.store.spec.ts` (each Undo names its step; a refused one is reported, never
  undone), `link-analysis-investigation.store.spec.ts` (the 409 re-reads the log; `undoTarget`), `inv.service.spec.ts`
  (the `undoes` body), the four component specs (axe-clean, hints remembered and blocked storage),
  `link-analysis.component.spec.ts` "Grow, Held and Exclude on the graph" (one explorer preview with its body; a 403
  shown in the panel; the rail, Basic Grow, Undo, Ctrl+Z, degrading without the routes), `shortcuts-help.dialog.spec.ts`
  and `la-shell.component.spec.ts` (la-app's `?`), `access-catalog.spec.ts` (the node).

### 6.9.1 The Investigation tab as one Expand filter (LA-INVESTIGATE-UX-1, SPA, as built 2026-10-31)

How it reads to a user: [user guide §7](user-guide-link-analysis.md#7-expand-and-clean-up-the-graph) and
[§11.2](user-guide-link-analysis.md#112-the-investigation-tab-act-panel). No server change and no new op.

- **One filter, published.** `LinkAnalysisExpandComponent`, embedded in the tab (the explorer's popup never publishes),
  publishes its request to
  `GrowExcludeStore.filterRequest` on every change and its raw values to `filterSnapshot`, which it restores when it is
  mounted again (the tab only exists while the toolbox shows it). Every canvas expand — double-click, node menu, G/E with
  a selection — goes through `expandInInvestigation(nodes, change)` → `onExpand` → `growInvestigation`, i.e. the SAME
  degree walk, hub hold and four-eyes path as the tab's button. With no filter published yet the presets alone apply.
  `change` is the node menu's *Expand, this once* (direction, two degrees, one link kind): this step only. Degrees moved
  by the screen (LA-DEGREES-TRIM-1) is published too. Another Investigation opening clears the store's filter and puts
  a mounted form back on its defaults. The node menu's *Expand anyway* confirms the override first, as the tab does;
  recorded Ctrl+Z / Ctrl+Shift+Z obey the same target guard as G / X / K / H (not inside a dialog, menu or grid).
- **Groups.** `investigation-filter.ts` names the six groups (`FILTER_GROUP_FIELDS`), their defaults and
  `changedGroups()` (blank = default); `la-fold` gained `changed` (a dot), `dense`, `startOpen` and `rememberKey`
  (localStorage `la.fold.*`, try/catch). Depth counts as changed only while it is not bound to the last Expand
  (LA-DEGREES-TRIM-1 keeps that binding). The binding survives a reload (LA-DEGREES-TRIM-2): each `expand` of one
  Expand carries the same optional `group` param (client-made, stored as given, ignored by the evaluator), and the
  newest steps sharing the newest expand's group are the last Expand; a log without groups binds nothing.
- **Quick windows.** The rung's `window` control also takes `last7 | last30 | last90`; `rungOf(…, windowEnd)` sends them
  as `[end − N days, end + 1 s)` (the server's range is half-open). The end is `windowEnd` (the preset window end) or
  `InvestigationSessionStore.latestEvent`, read once on demand by `loadLatestEvent()`; unknown → no window is sent and
  the fold says so. Never "today".
- **Redo.** `InvestigationSessionStore.undone` is the stack of entries this browser undid; `redo()` re-appends the newest
  as `redoRequest(entry)` = `{op, ...params}` minus null values (the sealed params name the request's own keys), through
  `apply(…, true)`. Any other `apply`, `open`, `close` and `useDraft` clear it. A sealed null `window` is sent as
  `'full'`; a note on links is not offered again (sealed as `{source, target, kind}`, while the request names `lk.` ids),
  so `canRedo` is false for it. A redone expand that four-eyes holds leaves the stack like any recorded step.
  **One change, not one step (LA-INVESTIGATE-REDO-PAIR-1, fixed 2026-11-01):** `undone` holds CHANGES (each its entries
  in recorded order). `undo(step, change)`: `GrowExcludeStore.undoOf` undoes a change newest first, `'one'` then
  `'join'`, so one Redo re-records a Clean up / Shrink's `hide` and its `annotate` reason (or a multi-degree Grow) in
  order; a step that fails part-way, or the rest after a step four-eyes holds, stays for the next Redo. The steps one
  Redo recorded are remembered, and the screen's Undo (`'newest'`, the default) naming the newest of them while they are
  still the newest effective steps takes them all back as one change. The Degrees stepper's trim passes `'one'`: it
  undoes, and so redoes, one degree at a time, a redone Expand included. Ctrl+Z / Ctrl+Shift+Z on an
  Investigation's canvas are recorded undo / redo (the last Grow / Exclude change first, as before); elsewhere they stay
  the display history.
  **Live check 2026-11-01 (Preview Demo Auth bundle of `a24fca153`, a saved throwaway Investigation): PASS.** Clean up
  *Hide 14 dangling leaves* recorded `hide` 3 + `annotate` 4; one Undo recorded undo 5, 6; one Redo recorded `hide` 7 +
  `annotate` 8 with the same reason ("Clean up: dangling leaves"); one Undo then took 7 and 8 back ("Undone (2 steps)").
  Node menu *Shrink (hide its leaves)* on the hub: `hide` 11 + `annotate` 12, one Undo (13, 14), one Redo `hide` 15 +
  `annotate` 16, reason "Shrink: hid the leaves of …" both times.
- **Clean up.** `investigation-cleanup.ts` works on the WorkingSet (raw ids, hidden entities skipped, hop-0 and kept
  entities spared), reusing `graph-cleanup.ts` (`keepPathsBetweenSeeds`, `keepOnly`) over `workingSetGraph(ws)`.
  Leaves are ONE pass (entities with at most one link), not the broom's repeated `trimDanglingLeaves`: an Investigation
  grown from one start entity is mostly a tree, which the repeated trim would take away whole. Each press is `GrowExcludeStore.cleanUp(ids, reason, exclude)`: `hide` + `annotate` (one grouped
  Undo, as A3) or ONE `exclude` with the reason. ⚠ The Working Set carries link counts, not link times, so "outside a time
  window" is the one Clean up that reads the Dataset (below).
- **Canvas.** The node menu's recorded items show only on an Investigation's canvas; *Investigate this* (no Investigation
  open) calls `LinkAnalysisStartBoxComponent.prefillEntity`. The selection bar's Exclude opens the same
  `LinkAnalysisExcludeDialog` as X (the `EntityListReasonDialog` path is gone), which offers
  `recentExcludeReasons(ws)` as chips.
- **Unsaved Undo / Redo and Clean up (LA-INVESTIGATE-UX-2, as built 2026-10-31).** The unsaved history lives in
  `LinkAnalysisComponent`: `unsavedHistory` is a `graph-history.ts` stack of `UnsavedCanvas` snapshots (steps, the
  drawn Working Set, graph, expanded ids, held hubs); every unsaved change (Expand, a Degrees resize, Start from
  selected, a Clean up) calls `commitUnsaved(before)` after it draws, and `undoUnsaved()` / `redoUnsaved()` restore a
  snapshot through `InvestigationSessionStore.setUnsavedSteps` (`popUnsaved` is gone). They are offered only while the
  canvas still shows the graph the newest change drew (`unsavedDrawn`), the start itself is never undone, and a new start
  clears the stack. Ctrl+Z / Ctrl+Shift+Z reach them after the last change's own Undo; the tab's act bar is one
  `#actBar` template stamped in both branches, its buttons emitting `unsavedUndo` / `unsavedRedo` while unsaved. The
  drawn unsaved Working Set moved to `InvestigationSessionStore.unsavedWs`, so the tab's Clean up (one `#cleanupFold`
  template) counts it and emits `unsavedCleanUp`; `cleanUpUnsaved` marks the entities `hidden` on that set and records
  `UnsavedStep {kind: 'hide', ids, reason}`. This is exact without a server change because the evaluator's `hide` only
  flags entities (they stay in the set, so later previews seeded with the whole set agree, and `mergeWorkingSets` keeps
  the flag). Save replays it as `hide` + `annotate(reason)` with the ids mapped through `exploreAliases` and kept to the
  replayed set; `unsavedOps` never sends it to `POST /inv/expand/preview`.
- **Exclude instead while unsaved (LA-INVESTIGATE-UX-2 item 5, as built 2026-11-01).** `POST /inv/expand/preview` takes
  `exclude` steps too (`UNSAVED_OPS = seed, expand, exclude`): each goes through the append's own `params` (reason
  required, at most 200 chars; a `merged` exclude is 422 because no resolve is in force unsaved) and the same evaluator,
  in log order, so the excluded entities and their links leave the set and every later expand drops rows touching them.
  The excludes are capped apart from the reads (`UNSAVED_MAX_STEPS` 4 seeds / expands, `UNSAVED_MAX_EXCLUDES` 32), and the
  answer's `workingSet.excluded` ids are masked like the rest. The Studio records `UnsavedStep {kind: 'exclude', ids,
  reason}`; `cleanUpUnsaved(…, exclude)` drops the entities and their links from the drawn set (as the evaluator does),
  and `walkUnsaved` sends every recorded exclude FIRST, then the seed of the drawn set and the degree, so a later Start
  of an excluded value brings it back as a seed after an exclude does. Save replays it as one `exclude` with the reason,
  ids mapped through `exploreAliases` and kept to the replayed set. Known limit: the Studio does not fold excludes, so a 33rd unsaved exclude makes the next Expand's preview 422 (Save, then go on). Tests: `ControlApiUnsavedPreviewTest` (parity with
  a created Investigation, log order, aliases only on a masked Space, malformed excludes 422).
- **Outside a time window (LA-INVESTIGATE-UX-2 item 2, as built 2026-11-01).** No backend change, no new op, no new
  persisted field: D-OPS-1 refuses sealing link times into the Working Set, so the tab reads them live through the
  existing comparison read, `GET /inv/investigations/{id}/compare`, with window A = the analyst's `[from, to)` and window
  B = all time (`bTo=9999-12-31T00:00:00Z`, `ALL_TIME_END`). `entities.onlyB` is then exactly the Working Set entities with
  at least one event on a Working Set link and none in the window; `outsideWindow(ws, ids)` keeps the visible ones and
  spares start and kept entities, and the press is the ordinary `cleanUp(ids, reason, exclude)`: one `hide` (+ `annotate`
  with `Clean up: no link event in [from, to)`) or one `exclude`. So the log stays count-only and replays, and the Dossier
  and sealing are untouched. Ids come back masked exactly as the Working Set's (`EntityMasking.of(inv, [])` on both) and the
  append resolves the pseudonyms. Limits it inherits from the read: `comparison.max_entities` (a larger Working Set is
  422), `comparison.list_cap` (a cut `onlyB` is refused in the tab, never half applied; it is at or below
  `input.max_ids`, the per-op id cap, by default), the R3 gate, and rows with a NULL time are not counted, so an entity
  whose links carry no time is never "outside". The read is audited as `LINK_INVESTIGATION_COMPARED`. The result is
  tied to the window and the Working Set hash it was read over and is dropped when either changes. Known limit: offered
  on a saved Investigation with a time column only, because an unsaved one has no id for the read; Save first. Only
  `from` / `to` are asked (the read route takes no slot, days or excluded dates). Tests: the `LA-INVESTIGATE-UX-2 (2)`
  cases in `link-analysis-investigation.component.spec.ts` and `investigation-cleanup.spec.ts`, and
  `ControlApiInvestigationComparisonTest.allTimeAsWindowBListsTheEntitiesOutsideWindowAForOneHide` (the contract the tab
  relies on, over real HTTP).
- **Pick neighbours… on the node menu (LA-INVESTIGATE-UX-2).** Shown for one Working Set entity on an Investigation's
  canvas (`nodeMenuEntity`); it selects the node, sets `InvestigationSessionStore.neighboursRequested` and opens the
  tab, whose Act instance opens and reads the list once `selectedInSet()[0]` is that id (the tab may not exist when the
  menu is used, hence a store signal rather than a call).
- Tests: `investigation-filter.spec.ts`, `investigation-cleanup.spec.ts`, the Redo case in
  `link-analysis-investigation.store.spec.ts`, and the component specs of the tab, the Expand form and the Exclude dialog;
  the `LA-INVESTIGATE-UX-2` cases in the store, tab, canvas and `unsaved-investigation` specs.
- **Three re-measure defects (as built 2026-11-02, `LA-INVESTIGATE-TIMECOL-DATE-1`, `-THIS-MASKED-1`, `-CLICKS-2`).** No
  backend change.
  - *Time column.* Decision: **do not offer** a column the server refuses, rather than support DATE. `timeColZone` in
    `InvestigationRoutes` accepts only `TIMESTAMP` / `TIMESTAMP WITH TIME ZONE` (a naive TIMESTAMP is read in a zone; a
    DATE has no instant to compare a window's `[from, to)` with), and every windowed read, the latest-event lookup and
    the compare route would need a cast to support DATE. `readStartColumns` now reads the store probe
    (`DatasetRowsService.rows(ds, 1)`), not the declared columns (partial: `BOOKED_AT` is not declared on
    `mule_transfers_dataset`). `/db/table` answers the coarse `date` for DATE and TIMESTAMP alike (seen live), so the
    date columns' stored types are asked in one `/db/query` (`columnTypesSql`: `SELECT typeof(..) …  LIMIT 1`; an empty
    store answers no row, and then no column is offered) and the start form's `timeColumnOptions` keeps only the two
    timestamp types. The Dataset's own column (blank choice) is not re-checked: a refusal of the start's
    walk (`walkUnsaved` → `POST /inv/expand/preview` 422) is now caught in `startUnsaved` and shown as the start notice.
  - *Investigate this on a masked node.* The node carries the exploration alias; Start needs a value. `investigateNode`
    reveals it through the existing audited `POST /inv/deep-links/resolve {entity: "<type>:<alias>", reveal: true}`
    (`canRevealLinkEntities`, audited `LINK_ENTITY_REVEALED` with the alias only) and fills the form with the real key and
    its Entity Type; without the capability no request is made, the value stays blank and the form's
    `start-prefill-note` says why. The type comes from the typed node id (`<type>:<key>`); an untyped node cannot be
    revealed and says so.
  - *Clean up ▸ Outside a time window.* Chips 7 / 30 / 90 days (`outsideChips`, `quickWindow` from the Expand filter):
    From = N days before `store.loadLatestEvent()`, To blank, and the read runs at once; the field placeholders say what
    blank means. The folded ⋯ menu has *Keep the selected (K)* (`la-more-keep`) beside Exclude.
  - Tests: the `LA-INVESTIGATE-TIMECOL-DATE-1`, `-THIS-MASKED-1` and `-CLICKS-2` cases in the start-box, tab and canvas
    specs.

## 6.14 Studio UI reorganization (LA-STUDIO-UI-REORG-1, as built 2026-10-28)

The Studio was reorganized without removing a function; the layout is described in
[user-guide-link-analysis.md](user-guide-link-analysis.md#studio-layout-la-studio-ui-reorg-1-phases-1-3-as-built-2026-10-27) *Studio layout*, and the plan with its "nothing removed" map
is archived as [`la-studio-ui-reorg-plan`](../../../../archived-documents/plans-archive/la-studio-ui-reorg-plan.md) §6.

- **Decisions (operator interview 2026-10-27, UIR-D1…D16):**
  - The Investigation tab split into the Act panel, a bottom dock and one ⚙ Settings dialog. The dialog also holds the old left Query panel, Entity Types, display rules, Entity Lists, Identities and Add / Upload data.
  - A second icon toolbar carries Canvas modes, selection and graph edits.
  - Plain drag pans, Shift+drag lassos, and there is no box select.
  - One Expand dialog for N degrees, with supernodes held.
  - Collapse is visual only, everywhere.
  - Four clean-up tools.
  - Layouts and overlays over the cap are disabled with a reason.
  - One Open dialog and a File menu.
  - One algorithm catalogue with a Browser / Server toggle.
  - The Domain profile picker is removed.
  - Dashboard Widgets and the embed viewer get a read-only toolset.
  - Editions lock single actions and hide whole areas.
- **Visual polish (operator, 2026-10-28):**
  - The title and its explain button moved back up from the status bar; the page-info line is gone.
  - The tool rail and the canvas toolbar share the header row (`la-toolbars`); File ▾ is the rail's first icon.
  - Every header button is icon-only, `aria-label` kept, name as `matTooltip`. A component style shrinks icon buttons to 30 px, with `!important` because the theme pins `.mat-mdc-icon-button` to 40 px with `!important`.
  - The rail does not shrink (it overlapped the canvas toolbar when it could), and the row container wraps as a last resort instead of clipping. The canvas toolbar is still not `overflow-hidden` (LA-REORG-CLEANUP-PANEL-CLIPPED-1). Measured: one row at 1,440 px with all 112 nodes selected and at 1,100 px folded.
  - The canvas toolbar folds by the header row's OWN width (`CANVAS_TOOLS_FOLD_PX` = 1,350, a `ResizeObserver` on `la-header` → `canvasToolsFolded`), not a viewport media query, so the console's side nav counts. Measured: the console at 1,440 px with its nav open (header 1,129 px) is one row with 921 selected; at 1,100 px with the nav open (header ~790 px) the rail alone is 580 px and the canvas toolbar wraps under it, unclipped.
  - Expand uses `mat_outline:open_with` and Clean up `mat_outline:cleaning_services`, from the registered Material sets.
- **Calls UIR-A1…A3, confirmed by the operator 2026-10-28** (proposed by the assistant, built as written):
  - A1: one ⚙ dialog that also holds *This Investigation*.
  - A2: Value Measures appears only in the catalogue.
  - A3: in an Investigation, clean-up records `hide` + `annotate`, one grouped Undo (`GrowExcludeStore.cleanUp`, `recordCleanup`). `keep` is not "keep only".
- **Industry pack (operator, 2026-10-28):**
  - A saved view never applies its own pack; the Space's pack always applies.
  - `loadView` clears `boundProfile`.
  - The view's stored pack is only shown in the Open preview and saved back unchanged.
  - Only an Investigation Template's `pack` binds one.
- **Read-only toolset:** `link-view-widget-tools.ts` (`widgetGraph`, `runWidgetAnalysis`) is shared by the dashboard Widget (`LinkViewWidgetComponent`) and the embed page (`projects/link-analysis/src/link-analysis/embed-view.page.ts`, drawn on `GraphViewComponent`; served at `/embed/inv/:token` by both the console and `la-app` since 2026-10-28, `LA-CONSOLE-EMBED-VIEWER-1`). Everything runs in the browser and sends no request.
  - Export appears only with the Widget's `allowExport`.
  - The embed viewer never offers Export, because an embed grant has no export flag.
  - Minting an embed needs `canShareInvestigation`, the lead role and `-Dla.embed.enabled=true` (503 otherwise).
- **Gotchas found by the drives:**
  - The second toolbar must not be `overflow-hidden`: it clipped the Clean up panel at every width, so every Clean up tool looked dead (`LA-REORG-CLEANUP-PANEL-CLIPPED-1`, fixed 2026-10-28). The toolbar fits at 1,300 px and folds into overflow menus below it, so nothing needs clipping.
  - The ⚙ Settings Esc handler ignores an Esc that another overlay already handled.
  - A case view sets `loading` before its awaits, and an unknown graph source clears it.
- **Live drive 2026-10-28** (a Demo Auth bundle on la-showcase; each Demo User was signed in through the sign-in picker in the browser):
  - **Embed minting:** `fm.analyst` and `admin` get 200; `fm.manager` and `ra.analyst` get 403; `demo.manager`, a non-member, gets 404 on the embed list.
  - **Read-only toolsets:** the embed viewer and the `la_showcase_overview` dashboard Widget (opened as `ra.analyst`) run Centrality, Communities and Cycles with no server request.
  - **`fm.manager` and `admin`:** open `mule-hub-case` with the full File menu and ⚙ → Investigation settings. As `admin`, Share and embed offers *Create link*.
  - **`demo.manager`:** gets "This Investigation is not available…", the same answer whether or not the Investigation exists.
  - **`ra.analyst` (on Standard):** sees a "Read-only" banner. *Save this analysis* and *Delete* are off, with the reason. Expand…, Expand next degree and Expand the whole Working Set are off.
  - **Esc in ⚙ Settings,** after a real Graph model edit: the first Esc asks to discard the change; the second Esc closes that confirm without reopening it, and Settings stays open.
- **Edition and add-on locks, checked live the same day (UIR-D14).** The demo sign-in exists only on Advanced and Preview builds, and Basic has no sign-in at all. So Standard was run as the demo bundle without `inspecto-policy`, and Basic as a real `-Edition Basic` bundle.
  - **Advanced with `-Daddons.laInvestigations=false`:**
    - `/bootstrap` reports `laLevel: explorer`.
    - ⚙ has no *This Investigation* group.
    - File ▸ Close and Delete are off.
    - *Run on the server* and *Start an Investigation* are locked (`aria-disabled`) with "Needs the Investigations add-on…".
  - **Standard:**
    - `/bootstrap` reports `edition: standard`, and the Investigations add-on is present.
    - Geo, Entities and Alerts are in the navigation.
  - **Basic:**
    - `/bootstrap` reports `edition: basic` and `laLevel: explorer`, with `geoMap`, `entityPages` and `laWidgets` false and each with its reason.
    - The graph draws without sign-in.
    - Geo, Entities and Alerts are hidden from the navigation.
    - *Run on the server* is locked with "Investigations are in Standard and above…".
  - **Real `-Edition Basic` build with the console UI:**
    - The Widget picker's *Investigation views* offers only Reconciliation; there is no Link analysis or Geo map type.
    - The saved `la_showcase_overview` Widget still draws. That is by decision: the offer is cosmetic (D-LAE2).
    - Embed and Investigation routes answer 503 with the reason.
  - **Real `-Edition Standard` build with WSO2 sign-in:** `/bootstrap` reports `standard` / `investigations` / `oidc`.
  - **Every user was signed in on that real Standard build.** The browser started SSO and kept its own state, PKCE verifier and nonce.
    - The browser does not trust the local WSO2 self-signed certificate. So only the IdP login form was completed by a localhost script, using the synthetic users from `scripts/wso2-preview-la-setup.mjs`.
    - The browser then opened the returned callback, and the app finished its own exchange.
    - The la-showcase seed ran with real WSO2 tokens.
  - **Results per user:**
    - **`fm.analyst`:** the full File menu, Investigation settings with *Create link*, and *Expand…* available.
    - **`fm.manager`:** opens the Investigation; Share and embed shows no *Create link*.
    - **`admin`:** *Create link* is offered.
    - **`demo.manager`:** gets "This Investigation is not available…".
    - **`ra.analyst`:** may change Investigations here. In WSO2 it is also in the built-in `operations` group, which grants `canManageIncidents`. The demo roster makes it a reviewer only, so this is the identity setup, not a defect. Operator 2026-10-28: `ra.analyst` is a read-only reviewer on WSO2 too; `scripts/wso2-preview-la-setup.mjs` now lists it in `la-viewer` only and removes a user from any of its groups it is no longer listed in.
- **Embed Export:** stays unbuilt (operator 2026-10-28, "not now").
- **Full gate 2026-10-28 (`f0e8a50eb`):**
  - The reactor ran 56 of 56 modules: 11,280 tests, 0 failures, verdict guard PASS. The UI suite passed 6,779 tests.
  - Six stale tests that peer commits left on master were fixed first, none in the Link Analysis UI: two Job tests outran by the new retry, a new scoring route, a DELETE-only Investigation path, and two sign-in specs.
- **Deliberately not built:** Export in the embed viewer (it needs a grant flag first).
