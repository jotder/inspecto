---
type: Guide
title: Link Analysis — User Guide — Reference notes (continued) and screens
description: Part 7 of 7 of Link Analysis — User Guide: Screens
tags: [module, la-core, link-analysis, user-guide]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — User Guide — Reference notes (continued) and screens

Part 7 of 7 of [Link Analysis — User Guide](user-guide-la-core.md) · previous: [Reference notes](user-guide-la-core-reference-notes.md).

## Reference notes (continued)

### Executive demo polish, round 2: decisions (`LA-EXEC-DEMO-POLISH-1` items 16-19, as built 2026-11-16)

The sixth review passed the screen full-screen *with notes*; these are its four notes and the call taken for each.

- **16. A label never lies on a start tab: the label moves, the tab stays.** `labelSidesClearOfStartTabs`
  (`graph-view.component.ts`) reads where each node is drawn; a label whose box under its node meets a start tab's box
  moves to the first side of its own node, of top, right and left, that meets no start tab, preferring one that covers
  no node either; with none it stays under. The tab never moves, because "a tab on top of the node" is how a start
  entity is recognised on every canvas; moving the tab to the far side of a colliding label would make that mark mean
  nothing at a glance. G6 5.1.1 has no collision avoidance that could do it: its `auto-adapt-label` behaviour hides
  labels by priority and does not know badges, and hiding a name is worse than moving it. A label beside its node sits
  2 px below the middle, because a start tab is wider than a small node and would otherwise graze it. The rule runs
  120 ms after drawing settles (`GraphEvent.AFTER_DRAW`, so a sliced layout's bursts count once) and when a dragged node
  is let go, never mid-drag; it repaints only when a label changes side, every node pinned where it is (no layout runs,
  so the phase-6 placements are untouched). Text widths are estimated at 0.62 em a character (no canvas to measure in
  jsdom), a little wider than the ink. Spec: `graph-view-placement.spec.ts` (the 17-node fixture with start entities:
  no label box meets a start-tab box; the showcase case, a leaf just above a start entity, moves its label once and
  settles).
- **17. A held request that repeats a recorded Expand says how it differs.** Round 1's *Requested next, not yet run:*
  still named the same op as the recorded line above it (*Expand one hop from every entity*), so it read like a
  repeat. `recordedBefore` (`expansion-decision.ts`) finds the latest Expand before the held line with the same head
  that read data (not held, not denied); `requestDifference` compares the request's sealed sensitivity with that
  step's rung, and the line becomes *Requested next, not yet run: Expand one hop from every entity again, this time
  adding every link of each entity, not only the 6 strongest. Demo FM Analyst asks for your approval. That is more
  than the 2,000 allowed without a second approval.* Decisions: the difference is said in the recorded line's own
  words (*the 6 strongest links of each entity*, *reading up to N rows*), never "fan-out" (the presentability rule for
  approver text stands); the limit sentence names only the number when the difference already says what crosses it,
  and keeps the full sentence when both limits are crossed, so "without a second approval" still covers both; with no
  Expand to repeat, or nothing known that differs, the line is as before. Specs: `expansion-decision.spec.ts`,
  `link-analysis-investigation.component.spec.ts` (the rendered approver line).
- **18. The Legend starts open while more than one link style is drawn.** The yellow and the dashed links on the mule
  case are link kinds the automatic look styled (`autoStyle`: the commonest kind stays neutral, each rarer one gets a
  colour and a line pattern); a dashed link is not a mark of a Connect step. Folded, the Legend explained none of them.
  Rule (`linkStyleCount`, `overlayStartsOpen(stored, needed)` in `link-analysis-overlays.component.ts`): count the
  distinct colour and pattern pairs over the drawn, not hidden, link kinds; more than one and nobody chose, the Legend
  starts open. Why this rule: one style needs no key, so the LA-OVERLAYS-HIDE-GRAPH-1 pill stays the default for most
  graphs, and the box covers a corner only when it explains something a reader cannot otherwise read; an unexplained
  colour costs more than the corner. The reader's own open or minimise is remembered in this browser and wins either
  way, and a saved view's *collapsed* still stands. Labelling the styled links in place was rejected: one link label per
  styled link clutters the canvas and repeats the kind on every link. The chip's swatch now draws a dashed or dotted
  kind as that line (`edgePatterns` on the legend, also on the Dashboard Widget), since a coloured block could not
  explain a dash. `legendOpen` is a `linkedSignal` over the choice and the need, so it follows a graph that gains a
  second style until the reader chooses. Specs: `link-analysis-overlays.component.spec.ts`, `link-analysis.component.spec.ts`.
- **19. Plain words, and the keys follow the role.**
  - *Data OK* → **All clear**: it says only what green means on the strip, that nothing needs a look, the plain
    partner of *Check data*. Not *Data current*: the sentence beside the badge already opens *Data current to …*, and
    green does not promise a complete window (days not delivered yet stay green).
  - *expanded 1 of 4 degrees* → **1 degree from the start, up to 4**. The GLOSSARY defines neither word for the Expand
    distance (its only *degree* is an Entity Profile's link count), so the term in use stays: the Expand button
    (*Expand 2 degrees*), the result lines (*Degree 2: …*) and this guide all say degree. What read as developer words
    was the progress-meter phrasing; it is now a distance from the start entities, as "degrees of separation" is.
  - *Working Set rows* → **Working Set table** (the dock tab, its heading and its section label). *Working Set* is the
    Glossary's term and stays; *rows* was the developer word. *Table*, because it is a paged table with columns.
  - **Ctrl+Z / Ctrl+Shift+Z do nothing** on a saved Investigation's canvas for a caller who may not change its steps
    (no permission, a reviewer, a reader through its Case), as its Undo and Redo buttons are disabled: no undo the
    server would refuse, and no display undo standing in for it (the rail's display Undo button still works). The page
    now knows the role: the caller's role moved from the Investigation panel into `InvestigationSessionStore`
    (`callerRole`, `readsOnly`, `loadCallerRole`, read once per open Investigation and only used for the one it was
    read for); the page reads it when an Investigation opens, the panel reads the same answer. While the role is
    unread nothing is withheld, as before; the server stays the gate. Specs: `data-trust.spec.ts`,
    `link-analysis-investigation.component.spec.ts`, `link-analysis-investigation.store.spec.ts`,
    `link-analysis.component.spec.ts`.

### Search around, Merge and Possible duplicates internals

- **Search around:** `search-around.ts` is the pure model (`SearchAroundSpec`, `stepsToSearchAround(steps)` and
  `searchAroundToSteps(spec)` convert to and from `PatternStep[]`); `link-analysis-search-around.component.ts`
  (presentational, OnPush) is the form. The toggle appears only while `aroundSpec()` is non-null; editing rewrites
  `patternSteps` and the button runs the same `runPattern`. `stepsToSearchAround` returns null on `afterPrevious` /
  `maxGapHours`; the toggle sits in the non-branching branch, after `branchStages()`. The *Custom* reset is pinned in
  `link-analysis-toolbox.component.spec.ts`. Driven live 2026-10-18 (Advanced la-app demo bundle, Demo Analyst): a
  *Shared associates* run answered 200 matches as masked aliases.
- **Merge:** `mergePairOf` (`investigation-state.ts`) takes the two selected entities and
  `InvestigationSessionStore.mergeDraft` carries the pair into `LinkAnalysisIdentitiesComponent`, which prefills *Assert
  same entity*; *Add a resolve step* sends `{op:'resolve'}`. `mergeUnavailable` is pinned by five specs in
  `link-analysis.component.spec.ts`. The alias refusal on the server: architecture-http-and-ui.md §6.7. Driven live
  2026-10-28 (Advanced demo bundle, Demo Admin, `mule-hub-case`, masking set to `none` for the drive then restored):
  shift-click two accounts, toolbar ⋯ ▸ *Merge...* opened ⚙ Settings ▸ Identities prefilled (SMURF-01, MULE-HUB-01);
  *Assert same entity* answered 201 ("the group now holds 3 identifiers"); *Add a resolve step* sealed step 9.
- **Possible duplicates** (`LA-PRODUCT-NEXT-1` phase 6, 2026-10-30): `possibleDuplicates` in `graph-analysis.ts`
  (Adamic-Adar); node cap `browser_algorithms.linkPrediction`; **Merge…** calls the host's `mergeNodes`, shared with
  `mergeSelected`; spec *phase 6 Merge… on a suggested pair…* in `link-analysis.component.spec.ts`. The spike is in
  `archived-documents/plans-archive/la-product-next-plan.md` §3 phase 6. Not driven in the browser yet.

### Upload a CSV internals (as built 2026-10-19)

Route and Collector rules: architecture-http-and-ui.md §6.6. Components: `LinkAnalysisUploadsComponent`
(`link-analysis-uploads.component.ts`), words and checks in `upload-state.ts`, the four calls in `InvService`. Status
codes behind the words: delete after pickup 409, in-flight run 409, over the limit 413, too many 429, someone else's
delete 403. The analyst-upload plan was archived 2026-10-22 (`docs/archived-documents/plans-archive/la-analyst-upload-plan.md`);
its open items are in `docs/superpower/link-analysis-roadmap.md` §D4. Driven live 2026-10-28 (Advanced demo bundle,
today `-Product la -Distribution demo`; Demo Admin, `la-showcase`): a two-row CSV to `crm_kyc` answered
`POST /inv/uploads/crm_kyc` 201 and was listed *Waiting for the Collector* with its size, line count and landing time.

### Entity profile and Open page internals

- **Entity profile pages** (as built 2026-10-15; plan archived
  [`la-entity-profile-plan`](../../../../archived-documents/plans-archive/la-entity-profile-plan.md); routes:
  architecture-http-and-ui.md §6.3): `projects/link-analysis/src/entities/*`, mounted by la-app (nav **Entities**) and
  the full SPA (D-EP6). *Open an Entity* POSTs the value to `resolve` and navigates by the returned key. The pen field
  takes Enter to commit, so the form never submits; the lookup listens for Enter on the host, from the input and on the
  closed row when it holds a value (`preventDefault` suppresses the button's own Enter-click that re-opened the editor,
  LA-ONBOARD-FIXES-1 F-12). The **Masked** tile: `masking.masked` from the server, else
  `maskedUnder(mode, type.masked)` in `entity-profile-view.ts`; the cards read `/settings/link-analysis`
  `maskingModeInForce` (`none` by default on Basic) and say nothing until it is read (F-6). Driven live (IMS Space,
  Enterprise demo bundle, Demo Analyst): the 15-SIM device shows as IMEI to IMSI, most per Entity 15; the lookup of
  the burst number lands on `/entities/msisdn/masked:...` with no raw identifier in the page or the URL.
- **Open in Inspecto LA page** (as built 2026-10-17; wire contract: architecture-http-and-ui.md §6.4):
  `projects/link-analysis/src/open/*` (`open.routes.ts`, `OpenInLaComponent`, `open-link.ts` with `parseOpenLink` and
  `DeepLinkService`). The page reads its base from the route, so its links are its siblings
  (`link-analysis?investigation=`, `entities/<type>/<key>`). The server check is `POST /inv/deep-links/resolve`; Reveal
  needs `canRevealLinkEntities`.

### Read-only client gates and Case history caps (2026-10-12)

- Every Link Analysis write control asks the same `LensService` signal the server route gates on
  (`canManageIncidents`, `canAuthorAlertRules`, `canAuthorWorkbench`, `canRunLinkGraphAnalysis`, `canBuildLinkIndex`)
  and, without it, is disabled with a visible reason (`aria-describedby`) or replaced by a note: Investigation start and
  step handlers (also inert on Enter), Case link, Members grant / revoke, References add, Templates save / instantiate,
  bound-rule edit, the Identity Fact log (read and import). The Dossier, bundle download and both verify POSTs are
  read-shaped and stay enabled. User-facing table: [§14](user-guide-la-core-examples-roles.md#14-who-can-do-what-roles-and-editions).
- `POST /inv/case-history` counts only records the caller may read against its caps
  (`case_history.max_linked_investigations`, default 200; `case_history.max_attachments`, default 1,000): hidden ones
  neither consume a cap nor set `truncated`, so a low-privilege caller learns nothing about the Space and its own
  matches are never crowded out. A DoS bound of 5x the caps (hidden included) ends the scan; it sets `truncated` only
  for a caller admitted at least one record (see the `CaseHistory` javadoc).

### Investigation pivot internals (shipped 2026-07-20)

Analyst view: [§6.3](user-guide-la-core-building-graph.md#63-pivot-between-graph-and-map). The ui-design-review R8 "pivot bar", scoped to what R8 specified
(design: [`ui-design-review.md`](../../../../archived-documents/plans-archive/ui-design-review.md) R8; BACKLOG §4).

- **`PivotService`** (`inspecto-ui/projects/link-analysis/src/investigation/pivot.service.ts`, root-provided):
  `pivotTo(view, ref)` navigates to `/studio/link-analysis` (`'graph'`) or `/studio/geo-map` (`'map'`) carrying the
  selection's `ElementObjectRef` (Incident / Case id) as `pivotId` / `pivotType` query params; `readIncoming(route)`
  reads a valid incoming pivot, or `undefined`.
- **`ElementDetailDialog`** gained `pivotViews?: PivotView[]` on `ElementDetailData`: a button per offered view, shown
  only alongside `objectRef`. A pivot button calls `PivotService.pivotTo` and closes the dialog with no result; hosts
  handle no new `ElementDetailResult` kind. `table` was already the dialog's "Open record".
- **link-analysis** offers `pivotViews:['map']` when a clicked node carries `data.objectRef`; on `ngOnInit` it stashes an
  incoming pivot and, once the next graph query resolves, scans `nodes[].data.objectRef` and calls `focusNode(id)` (the
  `emphasis` signal); the pending pivot is cleared after one attempt.
- **geo-map** offers `pivotViews:['graph']` when a point's row resolves an `objectRef` (`objectRefFromAttrs`); on a
  match it sets `selectedId` and calls `mapView.setCamera({center, zoom})` — not `mapView.flyTo(id)`, which reads the
  map child's `@Input data`, still the previous value in the same tick as `this.geo.set(...)`.
- Not found: `toastr.info`, no error state, no retry. Deliberately not built: a persistent bar widget, a `timeline`
  target, any pre-fetch of the pivoted-to record.
- Tests: `pivot.service.spec.ts`, `element-detail.dialog.spec.ts`, and additions to `link-analysis.component.spec.ts` /
  `geo-map.component.spec.ts`.

---

## Screens

Screens for Link Analysis. Design and internals: [Link Analysis architecture](architecture-la-core.md).

- **Studio - Link Analysis** (`studio/link-analysis`), **Entities** (`studio/entities`) and **Geo map**
  (`studio/geo-map`), all from the library `inspecto-ui/projects/link-analysis/`.
- The standalone **la-app** shell (`inspecto-ui/projects/la-app/`), served by the same host.
- **Open in Inspecto LA** (`/open` in la-app, `studio/open` in the console; [§12.1](user-guide-la-core-investigations.md#121-links-that-open-inspecto-la)).
- Public embed view at `GET /public/investigations/{token}`.
