---
type: Guide
title: Link Analysis — User Guide — Reference notes
description: Part 6 of 7 of Link Analysis — User Guide: Reference notes
tags: [module, la-core, link-analysis, user-guide]
timestamp: 2026-11-15T00:00:00Z
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Link Analysis — User Guide — Reference notes

Part 6 of 7 of [Link Analysis — User Guide](user-guide-la-core.md) · previous: [Worked examples, roles, limits and troubleshooting](user-guide-la-core-examples-roles.md) · next: [Reference notes (continued) and screens](user-guide-la-core-reference-screens.md).

## Reference notes

Engineering as-built detail behind the chapters above that no architecture doc holds yet. The backend is
[architecture-la-core.md](architecture-la-core.md); the HTTP surface and the Investigation UI (Studio reorganization §6.14) are
[architecture-http-and-ui.md](architecture-http-and-ui.md); onboarding is
[architecture-data-onboarding.md](architecture-data-onboarding.md); design records are
[architecture-records.md](architecture-records.md).

### Studio layout internals (`LA-STUDIO-UI-REORG-1` phases 1-3, as built 2026-10-27)

Analyst view: [§4](user-guide-la-core-building-graph.md#4-the-screen) and [§18](user-guide-la-core-examples-roles.md#18-if-you-used-the-old-studio); decisions and drives:
architecture-http-and-ui.md §6.14; plan archived as `docs/archived-documents/plans-archive/la-studio-ui-reorg-plan.md`.

- **Header** `header[data-testid="la-header"]`: the title (the page's one `<h1>`, `la-title`, `text-lg` since
  2026-10-29), the tool rail and the canvas toolbar side by side (`la-toolbars`), **Full screen** (`la-fullscreen`), the
  one **About** explain button (`la-about`) and **⚙ Settings** (`la-settings-open`). No side-panel toggle and no
  Analysis / Investigation shortcuts in the header (operator 2026-10-29): the right panel opens, collapses and switches
  tab from its own rail and tabs. The selection chip shows the count only (the word is screen-reader text and the
  tooltip). The explain control renders through the `LA_AI_ASSIST` host slot
  (`<inspecto-la-host-slot [component]="aiExplain" [inputs]=...>`), not a direct `@inspecto/core/ai-assist` import: the
  library's `no-restricted-imports` rule forbids host edges outside `la-host`.
- **Tool rail** (`la-tool-rail`): **File ▾** is `la-file`; the list icon becomes `share` while the list shows (*Show
  graph*); *Select neighbours* (selection group) uses `cursor-arrow-ripple`.
- **Canvas toolbar** (`la-canvas-toolbar`): groups `la-group-mode` (Canvas mode, `canvas-mode.ts`: `pan` | `lasso` |
  `fisheye` | `linkFilter`; Lasso is session-only, Fisheye and Link filter are the saved view's plugin flags),
  `la-group-selection`, `la-group-edits`, Clean up, `la-group-held`; below the fold width the groups after Canvas mode
  fold into `la-canvas-tools-more`. The G6 `brush-select` box is removed; a saved view naming it opens as Pan.
- **Clean up** (`inspecto-link-analysis-cleanup`): in an Investigation `recordCleanup` records `hide` over the
  complement plus an `annotate` carrying the reason (OP-1).
- **⚙ Settings** is `LinkAnalysisSettingsComponent`, an in-page overlay whose sections are projected `laSection` blocks;
  *This Investigation* is `InvestigationSettingsSections`. Its backdrop handler is `onBackdropClick($event)` (returns
  nothing): the earlier `$event.target === $event.currentTarget && requestClose()` returned `false` for a click inside
  the panel, which Angular treats as `preventDefault()`, so no submit button inside Settings submitted (found by the
  2026-10-28 Merge drive; spec *a submit button inside a section still submits* in
  `link-analysis-settings.component.spec.ts`).
- **Dock** (`la-dock`, `DOCK_TABS`): state in `localStorage` `inspecto.la.dock`, height
  `inspecto.split.link-analysis.dock`.
- **Industry pack in force:** `profileId = investigation.boundProfileId() ?? boundProfile() ?? activeDomainProfileId()`.
- **Known defects** of the reorganization: open rows in `docs/BACKLOG.md` §3.12 (`LA-SETTINGS-OVERLAY-NEVER-HIDES-1`
  and the other `LA-REORG-*` rows).

### Graph host pointer model (as built 2026-10-15)

The Link Analysis page (`link-analysis.component.html/.ts`) drives the shared G6 host
(`src/app/inspecto/graph/graph-view.component.ts`) through an opt-in `GraphViewInteraction` (input `[interaction]`;
`null` keeps every other graph exactly as before). `buildBehaviors()` is the pure, unit-tested wiring.

- `panByDrag: false` drops G6's `drag-canvas`. (`boxSelect`, a plain-drag `brush-select`, was removed by the
  2026-10-27 reorganization: plain drag pans again.)
- `doubleClick: true` emits `nodeDblClick` and delays the single click by `NODE_DBLCLICK_DELAY_MS` (230 ms), so a
  double click does not also open the node dialog. A double click on an expanded node folds the branch it brought in
  (`collapseBranch`) and a second unfolds it (`expandBranch`); a node with nothing downstream says so in the live region;
  a super-node toggles its members.
- `fitOnDataChange` re-fits after an expand, fold or hide (never after a hover, a selection or a restyle);
  `fitOnResize` re-fits once the canvas size settles and has changed by more than `RESIZE_REFIT_MIN_PX` (8 px) (G6's
  `resize()` keeps the old zoom, which left the graph off to a side when a dock opened). The Minimap pans through its
  own mask drag (`translateBy`), so it needs no `drag-canvas`.
- **Selection:** the page's `selectedNodeIds` (read as `selectedOnCanvas`, which drops nodes no longer drawn) is painted
  as a thicker outline and a halo (`[selection]`) and spoken in a live region. The host mirrors it into G6's `selected`
  state (`syncSelectionState`), which `drag-element` groups by, so dragging one selected node moves them all. Selection
  actions never resize the canvas (a layout shift used to throw the analyst's zoom away). While exploring, an expand
  from the selection is one `expandNode` per node, at most 25 per press, and a hide is part of undo/redo
  (`PresentationSnapshot.hiddenNodeIds`); in an Investigation an expand from the selection is ONE logged `expand` step
  with the pack presets, and an exclude asks its reason in the shared `EntityListReasonDialog` (200 characters). The
  Attach snapshot records exactly the selection in `selectedNodeIds`.
- **Not driven live** (unit-tested only, 2026-10-15): fold / unfold by double click, Minimap pan, wheel zoom, the
  selection actions, Attach with a selection, the Investigation expand / exclude path.

### Evidence UI (DR-U6..U9, as built 2026-10-10)

- **Dossier bundle:** `LinkAnalysisDossierComponent` has *Download sealed bundle* (`GET …/dossier/bundle` at the
  on-screen step, saved `<id>-bundle.json`) and *Verify a bundle…* (the uploaded file is POSTed WHOLE, never re-shaped:
  the seal covers all of it). `sealIntact:false` reads "edited after export"; `problems[]` is shown verbatim.
- **References:** `LinkAnalysisReferencesComponent` (under the Dossier) lists and adds `…/references`; `url` is
  rendered as text, never a link; no edit or delete (append-only by design).
- **Compare:** the `compare` op through the Investigation tab hits the same `…/ops` route; checked live against a demo
  backend (activity mode, two windows), answered with a sealed `comparison`.
- **Identity Facts** tab: `LinkAnalysisIdentityComponent` reads `GET /inv/entity-identities`; the server re-verifies the
  whole chain on every read (`EntityFactLog.read`), so a 500 renders *Chain NOT verified*. Import →
  `POST /inv/entity-identities/import` (counts only).
- **Alert → Investigation (DR-U9) — decision:** the Alert does NOT carry the breaching entity names.
  `la-live-detection-design` invariant 2 ("aggregate output only"; D-LD9 relies on it) holds: an Alert is readable by
  people outside the Investigation's gates, so names on it would bypass R3 / PDP / membership even masked per Space. The
  Alerts page offers *Open Investigation* (`?investigation=<id>` deep link, handled in `LinkAnalysisComponent.ngOnInit`),
  where Value Measures name them under the Investigation's own gates and masking. Reversing this needs the invariant
  changed first.

### Data trust internals (`LA-DATA-TRUST-1`, as built 2026-10-14)

Analyst view: [§11.11](user-guide-la-core-investigations.md#1111-data-trust-what-data-am-i-looking-at). Plan archived as
`archived-documents/plans-archive/la-data-trust-plan.md`; Files-only demo scope (Hive and the `db` Collector stay on
hold, `D-ING5`). Live-drive findings: [tests-live-drives.md](tests-live-drives.md).

- **Files read** `GET /inv/datasets/{id}/files` (`DatasetFilesRoutes`, la-api). Ingested-at is the Consignment's commit
  time; index state `UNKNOWN` is past `IndexManifest.MAX_INPUT_FILES`. It extends `CollectorCoveragePort` /
  `HostCollectorCoveragePort`, which join each Pipeline's `_batches_`, `_lineage_` and `_status_` CSVs per Dataset. Gated
  like `GET /inv/index` (`geoLink`, R3 Dataset view, write root 503). `DatasetFilesRoutes.FILE_ORDER`: event day newest
  first (no day last), then name A-Z, then Collector, Pipeline, Consignment and ingested-at, so the order is total and a
  page boundary neither repeats nor drops a File; the bridge order is irrelevant, the route sorts. The route serves no
  server path.
- **Corrected-day warning** `GET /inv/investigations/{id}/data-changes` (`DataChanges`, geo-link):
  `collector.delivery.anomaly` Signals whose day lies inside the Investigation's window and that are newer than its last
  look; the banner component `link-analysis-corrected-days` is mounted in the strip. **Last-look rule** (operator,
  2026-10-14): `DataChanges.lastLook` moves only for a logged op that carries a sealed read — `read` (seedBy, connect,
  expand), `comparison` (compare) or `temporalFindings` (temporal) — at the newer of the step's `at` and the sealed
  `readAt`; `seed`, `exclude`, `excludeBy`, `hide`, `keep`, `threshold`, `window`, `resolve`, `snapshot`, `annotate` and
  every `undo` never silence the warning (an undo of a read step does not either). `DataChanges.parseDay` accepts
  compact (`20260902`, as the delivery manifest signals its bucket key), hourly and ISO days.
- **Gap vs not delivered yet:** `splitMissing` (`data-trust.ts`); a pending day cell has state `pending`. Without a
  Window step in the whole log the strip never sends a bare `GET …/coverage` (the server refuses it with 422,
  2026-10-29); a log with a Window step, or a truncated log, is left to the server.
- **Alerts:** freshness `maximumAge` `auto` (2x the feeding Collector's poll) or `Nxpoll` (`AlertRule.followsPoll`);
  index lag (`indexLag: true`, a LITERAL `maximumAge`, fed by `IndexLagProbe`); `EventAlertBridge` raises one Alert per
  Job for a refused or failed `la.index.build` (`la_index_build`) and one per (pipeline, File, day) for a
  `collector.file.problem` Signal (`FileProblemSignal`, emitted from `ConsignmentIngestor`). Texts: `Alert.duration`
  (no decimals) and `EventAlertBridge.isoDay`. A POLLED Collector emits `pipeline.commit`, never `dataset.write`, so the
  freshness and index-lag clocks read it through `PipelineCommitProbe` and the commit half of `IndexLagProbe` (a Job-run
  Pipeline still uses `dataset.write`).
- **Volume Expectation:** `Expectation` kind `baseline` gained `trailingDays` / `minPriorDays` (each day judged against
  the median of the days before it).
- **Template** `spaces/_templates/la-daily-feed`: `daily_xdr_freshness`, `daily_xdr_index_lag` (`2h`),
  `la_daily_rows_band`, plus `la_index_build` and `la_rows_band_evaluate` (both on `pipeline.commit` of `daily_xdr`);
  `LaDailyFeedTemplateRulesTest` reads the shipped tree.
- **Dossier footer** (`DossierDataFooter`): outside the custody hash and the bundle seal (design
  `archived-documents/plans-archive/la-data-trust-s9-dossier-footer.md`). It keeps `asOf` (the raw covered-through
  instant) and adds `asOfDay`, the last covered UTC day (`DossierDataFooter.coveredDay`: a UTC-midnight instant names the
  day BEFORE); the SPA renders `formatDay(asOf)` and never steps back a second time. A DATE event column is covered
  "through its end", the next UTC midnight.
- **Times:** Files `ingestedAt` is a UTC ISO instant (`HostCollectorCoveragePort.utcInstant` reads the ledger's
  zone-naive `end_time` in the HOST zone it was written in, as `AlertService` does; a zoned or unreadable value passes
  through); the drawer shows it via `formatInstant` (`5 Sep 2026 10:03 UTC`).
- **Accessibility:** the drawer's Files table was run through axe-core 4.12.1 on a served Enterprise `la-app` bundle
  with the Browser pane visible, 70 Files (wcag2a/2aa/21a/21aa/22aa + best-practice): 0 violations, also at 1100x420
  and 420 px wide. The grid renders in auto-height layout; earlier hits were a hidden-pane artefact.
- **Freshness cache:** `DatasetMeasureProbe.dataAsOf` (behind `GET /datasets/{id}/freshness` and
  `DatasetProvider.dataAsOf`) caches the parquet maximum per (roots, Dataset, column) under an input-file stamp (the
  relation SQL plus path, size and mtime of every file `DatasetRelation.inputFiles` resolves, Consignment subtraction
  included), so it can never show fresher or staler data than the disk. No cache for a Dataset with no enumerable files
  (`view`-backed), more than 2000 files, no registry, a relation that reads the clock (`now`, `current_*`, `today`,
  `random`) or a failed read. Reading it from the manifest / ledger was rejected: a Pipeline's partition day is not the max
  of a TIMESTAMP `eventDate` column. Measured on 70 Files / 322k rows: miss 895-993 ms, hit 132-138 ms;
  `GET .../files` 46-103 ms.
- **Coverage cache:** `InvestigationCoverageRoutes.gaps` (behind `GET /inv/investigations/{id}/coverage` and the Dossier
  footer's days) caches the DuckDB local-day `GROUP BY` per (roots, Dataset, relation SQL, time column, naive zone,
  window zone, from, to, input-file fingerprint `DatasetProvider.inputFingerprint`). No cache past
  `InputFingerprint.MAX_FILES`, for an unknown listing, a clock-reading relation, a failed read or a failure to stamp;
  256 entries, then cleared. It sits BELOW the gates (`openForRead`, the R3 `relationFor` check and the
  `LINK_INVESTIGATION_COVERAGE` audit run on every call). The per-Collector read (`CollectorCoveragePort.deliveries`) is
  not cached. Measured (`ControlApiCoverageCacheTest#coldOpenBench`, `-Dinspecto.bench.coverage=true`): coverage 480 ms
  uncached → 683 ms first read, then 86-102 ms; the Dossier 524-714 ms → 97-166 ms (the replay itself is ~100 ms).
- **Open remainder:** a literal-suffix `file_template` cannot see a RENAMED correction (see
  `okf/product/module/elt/plan-elt.md`; the shipped template ends in `*`); completeness against an expected
  Collector roster is `LA-COLLECTOR-COVERAGE-1`.

### Industry pack in the browser (`LA-EDITIONS-1` phase 4, as built 2026-10-17)

Server side: [architecture-la-core.md](architecture-la-core.md) §3.6.

- The SPA ships no industry profiles: `projects/link-analysis/src/graph/domain-profile.ts` holds only the neutral
  `GENERIC_PROFILE` and a registry (module-level signals) that `DomainProfileService`
  (`projects/link-analysis/src/api/domain-profile.service.ts`) fills from `GET /settings/link-analysis/domain-profile`
  once per Space (and after a save). `domainProfiles()`, `domainProfile(id)`, `profileForEntityType(type)` and
  `activeDomainProfileId()` are reactive, so a `computed` re-runs when the packs arrive; until then, and when the read
  fails, everything reads `generic`.
- Relabels: the Working set tiles (`concept.node|link|row`), Entity Type labels (the server relabels
  `entityTypesInForce`), `measure.<id>` (read when the panel opens), `entity_type.msisdn` on the starter card;
  the legend (`inspecto-link-analysis-legend` in `link-analysis-overlays.component.ts`, also used by the saved-view
  Widget) draws `link_kind.<kind>` and `entity_type.<kind>`; the pattern picker (`link-analysis-toolbox.component`:
  `packOptions()`, `packDescription()`) draws `pattern.<pack id>`. `data-kind`, the hidden-kind set, the saved view and
  the pack id keep the raw value. One lookup: `termLabel(profile, key, neutral)`.
- Default reading: the page applies the Space's selected profile once, when the packs arrive, unless a view, a case or
  an Investigation has already chosen one; a deep link (`?seed=&entityType=`) waits for the first answer.
- Settings page: `LinkAnalysisDomainProfileComponent` (`link-analysis-domain-profile.component.ts`), console and la-app.
- Neutral words are enforced by `check-vocabulary.mjs` rule `industry-pack-term` (AC-09); specs that need the telecom
  binding install `TELECOM_PACK_FIXTURE` (`graph/domain-profile.testing.ts`).
- Glossary popover: `inspecto-link-analysis-glossary` (presentational, OnPush; `glossaryEntries(profile)` is the pure
  model), a `laFormMenu` popover over `term.<id>`. Other term families keep their own surfaces; the Settings Domain
  Profile table lists every key. Not built: search inside the popover, deep links from a pattern or Incident to its term.

### Canvas styling internals (2026-10-18 … 2026-11-06)

Decisions behind the canvas work (D-M1; D-UP1…D-UP8 with `canUploadData`; heroicons glyphs with no pack field; *Search
around* inside the Pattern section) were decided under operator delegation, recorded 2026-10-18
(`LA-I2-GAPS-RESIDUALS-1` tracks what is left). The canvas-styling, visual-query and entity-merge plans are archived in
`docs/archived-documents/plans-archive/`; what they deferred is retired to `BACKLOG.md` §6 with a reopen trigger.

- **Glyphs:** `catalog-graph.ts` `GLYPH_LIBRARY` holds inlined Heroicons outline paths (MIT; attribution in the
  comment), `ENTITY_TYPE_GLYPH` keys nine Entity Type ids (subscriber, imsi, imei, msisdn, wallet, account, agent,
  handset, cell), `entityTypeIcon(...)` builds the tile; the shared icon picker gains the same glyphs. Opt-in:
  `<inspecto-graph-view [entityIcons]>`; Link Analysis sets it; a Display `nodeShapes` override wins. No runtime sprite
  extraction and no pack field. Driven live 2026-10-28: the seeded `mule-hub-case` draws its typed `account` seeds as
  the glyph tile; a saved view over `mule_transfers_dataset` draws untyped `entity` nodes, so no icons.
- **Size and spacing (2026-10-24):** the host's own default node size for the catalog and Pipeline graphs stays 32 px.
  Spacing multipliers Compact 1 · Roomy 2 · Spacious 3 · Wide 4 · Widest 5, applied by
  `layoutConfig(id, { nodeSize, spacing })` to the force, layered, radial, clustering, concentric, Fruchterman and tree
  layouts (`grid`, `circular` and `mds` have no spacing parameter in G6); with neither argument every layout is
  byte-for-byte what it was. A change re-runs the layout (`layoutKey()` in `graph-view.component.ts`).
- **Automatic look:** `auto-style.ts` (pure). The look starts from an FNV-1a hash of the type name; on a clash the more
  common type claims its slot first (ties by name). Colours come from `ICON_COLOR_SWATCHES`; `AUTO_COLORS` drops every
  swatch within `NEUTRAL_DISTANCE` = 40 (RGB) of the default link colour in either colour scheme. Ties for most common go
  to the alphabetically first. A type whose colour the graph feed stamps keeps it, and a type with a glyph keeps its
  tile. `displayOptions()` merges the automatic look under the analyst's choices (the legend and canvas read the same
  merged colours for nodes and links); `chosenDisplay()` is what a saved view keeps. Not driven live (vitest only).
- **Reopen on refresh:** opening or saving a view writes `?view=<id>` (kept, no longer consumed); `forgetOpenView()`
  drops it; `openViewId` is what the page tracks.
- **Display rules:** `inspecto/graph/node-rules.ts` is the pure model (`NodeRule {kind?, attr, op, value, color}`); it
  is on the core allow-list in `eslint.config.mjs` because `graph-view` reads it. Saved as
  `GraphDisplayOptions.nodeRules` (written only when non-empty), undo via `PresentationSnapshot.nodeRules`; UI
  `inspecto-link-analysis-display-rules` (presentational). A Reference-detail rule carries `reference` (the Reference's
  Dataset id); values come from the masked `POST /inv/reference-rows`, batched over the drawn nodes of that Entity Type
  (`LA-REFERENCE-DETAILS-MORE-1`, 2026-11-06).

### Canvas readability internals (`LA-FULL-CYCLE-TEST-1` phase 6, as built 2026-11-16)

Findings 15, 17, 18, 19 and 20 of the 2026-11-15 full-cycle test. Each decision was taken by the lane under the
operator's delegation ("clean, CXO-presentable"); the operator may reverse any of them.

- **Labels on under 80 nodes** (finding 15). `nodeLabelsChoice` (`boolean | null`, null = automatic) and
  `nodeLabelsAuto` (canvas ≤ `AUTO_NODE_LABEL_MAX` = 80 nodes) in `link-analysis.component.ts`; `nodeLabels` is the
  choice, else the automatic value. Why 80: it is the embedded Investigation view's own label default
  (`display.embed_label_limit`), so a shared embed and the studio agree, and a fitted ~1,100 × 650 px canvas gives each
  of 80 nodes about 95 px square, room for a node and its 11 px label (masked aliases are drawn short). A constant, not a
  new `LaLimit`: it is a default the tick overrides, not a cap the server enforces. A saved view stores the state shown
  (`display.nodeLabels`); a view that never stated it follows the size. The render strip says *node labels off above 80
  nodes: Display options turns them on* (`data-testid="la-labels-off"`) when the rule, not the analyst, turned them off.
- **Expand places outside** (finding 17). `placeBesideNeighbours` (`graph-view.component.ts`) puts a new node on a ring
  round its anchors, starting on the side facing away from the graph's centre (an anchor at the centre: the widest
  opening between its links, beyond its ring), fanning to both sides; a spot is taken only when it keeps
  `placementGap(nodeSize, spacing)` (the node plus Radial's gap: 80 px at LA's defaults) from every drawn node, sits
  0.4 × gap off every drawn link, and the new node's own links pass no drawn node; else the ring widens. Where no such
  spot exists near the anchors (an evenly spaced Grid, where every own link passes a node), the link rules are dropped
  after `PLACEMENT_STRICT_TRIES` = 120 spots each and the node takes the first spot that only keeps the gap, which a ring
  just past the drawing always has (a grid of cells finds it fast): the cost per node stays bounded and the node stays
  beside the graph. Before, a golden-angle spiral at 60 px round the anchors landed inside a star, on its links.
- **The opening layout is kept** (finding 17). `openingLayout()` decides `defaultLayoutFor` when a graph opens and keeps
  it while the graph shares a node with the last one (an Expand, a Save) unless the kept layout is now over its node
  cap. Re-deciding it on every Expand flipped a growing graph from Layered to Force, and a layout change re-runs the
  layout over every node.
- **Save keeps positions** (finding 17). `saveUnsaved` first records the canvas positions by each entity's raw
  spelling (`carriedPositions`, from `GraphViewComponent.nodePositions()`); `positionHints` maps the saved Working Set's
  ids back through `exploreAliases` (the saved Investigation speaks its own aliases, DR-D2) and the graph view takes them
  as `[positionHints]`: a hinted new node is drawn at its hint, and a fresh build whose every node is hinted runs no
  layout (the layout is set after the first draw). The hints belong to the Investigation saved; a new start clears them.
- **Start entity marked** (finding 17). `workingSetToGraph` sets `data.start` on hop-0 entities (unsaved and saved
  alike, only when some entities are not a start, so a seedless start badges nothing); the graph view draws a 3.5 px
  outline and a *start* badge: a tab centred on the node's top edge (`top`, lifted `START_BADGE_RISE` = 10 px, so it
  overlaps the outline by 3 px; presentability 2), beside *held* (`right-top`) and *+n* (`right-bottom`).
- **Banner clears on the next step** (finding 19). An effect clears `startNotice` when `growExclude.lastChange` or
  `investigation.lastStep` gets a value: every Expand, Exclude, Clean up, Undo and recorded step (Save replays steps)
  sets one of them, and its own result line takes the banner's place. Drawing the start is not a step.
- **The change line at the top centre** (finding 19, the "snackbar" over the minimap). The Grow / Exclude result line
  (`la-change-line`) moved from the canvas's bottom right, where it covered the minimap, to the top band every fit keeps
  clear (`CANVAS_FIT_PADDING` top 44 px, 54 since presentability 2), centred in the gap between the Legend pill (6.5rem) and the Graph counts pill
  (15rem), so it stays one line beside an open toolbox (a 1,000 px canvas). Tried and dropped: the bottom centre, where it covered the nodes a fit draws along the bottom.
- **Notices at the top centre in la-app** (finding 19). `provideToastr({ positionClass: 'toast-top-center' })` after
  the shell's provider in `la-app/app.config.ts`. Bottom right covered the minimap; top right covers the page's own
  actions (Save's notice covered Save). The console keeps its bottom right.
- **Rows grid number columns** (finding 19). *Count* and *Links* are `flex: 0, width: 130`: stretched like the text
  columns, a right-aligned name sat at the far edge with its filter funnel alone at the other, read as a column with no
  name.
- **Force pulls to the centre** (finding 18, re-driven 2026-11-16 on `claims_network`, 426 nodes: two unlinked groups
  were still thrown to the corners after `LA-OVERLAYS-HIDE-GRAPH-1`). `layoutConfig('force')` adds d3 `forceX`/`forceY`
  at `FORCE_GRAVITY` = 0.05 (off in G6's defaults): an unlinked group settles at the main group's edge
  (≈ √(N · |push| / pull)); d3's own 0.1 squeezed the main group.
- **Colour scheme** (finding 20). la-app's Gamma `scheme` is `auto`; `LaAppComponent` follows `prefers-color-scheme`
  live: on the media query's `change` it emits the Gamma config again (same values), because the graph and map canvases
  read the scheme only on a config emit and draw with colours they cannot take from CSS. `SchemePreferenceService` (`inspecto/shell/`) keeps *Match system / Light / Dark* per
  person in this browser (`inspecto.scheme.<actor>`, `inspecto.scheme` where nobody signs in; every storage call in
  try/catch) and is started by la-app's root only, so the console keeps its configured dark. The shared `<user>` menu
  offers it with `[schemeChoice]="true"` (la-shell), as `menuitemradio` items; the menu then shows on Basic too.
- Specs: `graph-view-placement.spec.ts` (the 17-node fixture: no two node boxes overlap after Expand and after Save, no
  layout run through a Save), `link-analysis.component.spec.ts` ▸ *a drawn graph stays readable*, the label specs,
  `scheme-preference.service.spec.ts`, `user.component.spec.ts` ▸ *colour scheme*. Live drive (Basic, 2026-11-16):
  labels note, notices at top centre, scheme switch, `claims_network` fit; the Investigation Save path was not driven
  live (the drive server is Basic, no Investigations).

### Presentability round 2: decisions (`LA-FULL-CYCLE-TEST-1`, as built 2026-11-16)

An independent review of live screenshots for a bank's executives found these; each wording or layout call and why:

- **Waiting for a first run is not an error.** A Pipeline-fed Dataset whose store holds no rows yet (`empty: true`)
  says *Waiting for its first run* in the secondary ink with a clock, on its menu row and in its header (was a red *No
  rows yet — its Pipeline has not run*). The server reports only empty / not empty / unknown, never a failed run, so the
  Data page has no red state to show here; a failed run is the Pipeline's own status, not this page's.
- **The "Kept up to date by a Pipeline or a Job" card is gone.** It held one heading and one sentence: the header's
  *Kept up to date by a Pipeline* chip already says where the rows come from, and the page's own intro already says
  columns and Mappings are set here. A card that only repeats the header reads as unfinished; the one thing it added (the
  wait for a first run) now sits in the header.
- **Record type: "Any kind of record", not "Not set".** A Dataset with no record type is a complete answer (every Entity
  Type in its usual order), so the blank choice says so, with that as its hint. One wording for Add data and the Data
  page (`anyRecordOption` in `api/entity-type-options.ts`). The row still shows only when the Space's packs offer record
  types.
- **The Dataset menu line never clips.** *From a Pipeline* (the header says the rest), the Mappings only once there is
  one (no *0 Mappings*), and the line wraps instead of `truncate`. Dropping zero beat shortening every count: it is the
  common case for a Pipeline-fed Dataset and says nothing.
- **Start badge as a tab on the node.** Centred on the node's top edge and overlapping the 3.5 px start outline by 3 px,
  in the node's own colour (theme tokens via the node colour and surface, as before). Off the top-left corner it read as
  belonging to whichever node was nearest. Fit: measured on the live build (Advanced demo, `mule-hub-case`, 1440 × 860),
  G6's fit bounds include badges, so the badge stayed inside the 44 px band; the band now also adds the badge's rise
  (`CANVAS_FIT_PADDING` top 54) so a start entity at the top never has its badge under the Legend / Graph counts pills.
- **Toolbox header in words.** The Investigation tab names its Dataset by title, else the first sentence of its
  description, else its id, and its link as *Links from payer account to payee account* (`bindingWords` in
  `la-starter.ts`; column names as words, since a Dataset column carries no label of its own; the whole first sentence,
  wrapped, not the Start box's 90-character cut). la-app's `LA_DATASETS` now carries the Data page's `title` (as the
  Dataset's name) and `description`, which it dropped before, so the Start box names Datasets the same way. The stored
  `mule_transfers_dataset · PAYER_ACCOUNT → PAYEE_ACCOUNT` stays in the line's tooltip and, for keyboard and screen
  reader users too, under ⚙ Settings ▸ Investigation settings ▸ General (*Dataset* and a new *Link columns* row);
  the recorded header and every op still use the stored names.
- **Working Set line.** Zero excluded / hidden / kept counts are left out; *degree 1 of 4* became *expanded 1 of 4
  degrees* (*not expanded yet* before the first Expand). The word stays *degree*, not *hop*: the Expand stepper, its
  results (*Degree 1: …*) and its limits all say degree, and one concept keeps one word.
- **Plain sentences.** *Window: all available data* → *The next Expand reads all available data.*; *12 weaker links
  were left out: each entity keeps its 50 strongest links* → *Only the 50 strongest links of each entity were added; 12
  weaker links were left out.* (both computed on screen, not stored in the Investigation).
- **Reveal wording.** *Reveal the ids on screen* → *Reveal the masked entities on screen* (button, dialog title, node
  details), and *N ids masked* → *N entities masked*. Not *identities*: in the GLOSSARY that word belongs to identity
  resolution (**Identity Fact**); what is masked is an **Entity**, and Oversight already said *Reveal the masked entities*.
- **Hover text after a Reveal.** A revealed node's hover card and the hover text of its links name it by its real id,
  as its canvas label does (`hoverLabel` on the node data, set from `revealedIdOf`); every op still speaks the alias.
- Specs: `data.page.spec.ts`, `add-data.component.spec.ts`, `graph-view.component.spec.ts` (start tab, hover name),
  `link-analysis.component.spec.ts` (fit band), `link-analysis-investigation.component.spec.ts` (header words, counts
  line, window sentence), `la-starter.spec.ts` (`bindingWords`), `investigation-state.spec.ts`, `investigate-number.spec.ts`.

### Executive demo polish: decisions (`LA-EXEC-DEMO-POLISH-1`, as built 2026-11-16)

The final executive review of six live screens judged the Investigation screen fit for a bank COO *when cropped*. What
it noted, and the call taken for each so the uncropped screen passes:

- **The minimap folds to a button.** It covered the node labels in the canvas's bottom-right corner. Padding the fit
  instead was rejected: G6 pads a fit per side, not per corner, so keeping a 160 × 100 px box clear costs about a fifth
  of the canvas height (or width) on every graph, and a fitted graph is wholly on screen anyway, so an open minimap only
  repeated it. It now starts folded to an icon button at the right end of the line under the canvas (*Show the
  minimap* / *Hide the minimap*: the label says the state, so no `aria-pressed` repeats it); open, that line and its button stay to the minimap's left. The View
  ▸ **Minimap** tick keeps its meaning (does the canvas offer one) and stays in saved views, so no showcase view
  changed; ticking it opens the minimap at once. The fold is a class on `<inspecto-graph-view>`
  (`minimapCollapsed`, hiding G6's `.g6-minimap` element), never a plugin change, because a plugin change rebuilds the
  graph and runs its layout again. Per screen, not stored.
- **The approver's line says it is a further Expand.** *Requested next, not yet run: Expand one hop from every entity.
  Demo Analyst asks for your approval. It would add every neighbour of each entity, more than the 2,000 allowed
  without a second approval.* It sits under the recorded line *Expand one hop from every entity (step 2): 6 new
  entities, 7 links added.*, and with the same head the two read as one Expand that both ran and was over the limit.
  The "missing full stop" before *Only the 5 strongest links* is in the source and always was (`degreeOutcomeMessage`
  ends the count sentence with one); the review's screenshots are 800 × 477 px, where a full stop is under a pixel. A
  spec now pins that exact line.
- **The header separates its facts.** The name wraps to two lines (its whole in the tooltip) instead of being cut at one
  (*Mule hub: six feeder accounts and a r…*): a new `wrap` input on `<inspecto-pen-field>`, and the same clamp for a
  reader who may not rename it. Then three lines with space between them: the purpose in the body ink (it is the
  case's own sentence), *Data:* and the Dataset in words, and *Links from payer account to payee account*, both in the
  secondary ink. Not a labelled definition list: a *Links* label would repeat the line's first word, and the purpose
  needs no label under the name. Theme classes only.
- **One spelling: *On screen*.** The tab label exists once in the source (`allToolboxTabs`, the G16 wording, matching
  *on screen* in the counts); no *Onscreen* is written anywhere, and the GLOSSARY has no entry for it. The two readings
  came from the 800 px screenshots, where the space between the words is about a pixel. The tabs are now
  `whitespace-nowrap`, so a narrow toolbox can never break or squeeze the label.
- **Undo and Redo follow the role, not only the permission.** A reviewer, or someone reading through the Case, held
  the permission, so both looked enabled under a banner saying only a lead changes steps. They now follow `canChange`
  (permission and not read-only), disabled but focusable, with the tooltip *Only a lead changes the steps of this
  Investigation.*; the component's `undo()` / `redo()` check the same. Ctrl+Z on the canvas still reached the store
  for them (the page did not know the role), where the server refused it; round 2, item 19, closes that.
- **The start tab beside a neighbour's label: left as it is** (superseded by round 2, item 16 below: the full-screen
  review saw the label lie on the tab). On the mule case the force layout puts SMURF-02 directly
  above RELAY-01, so SMURF-02's label backing ends just above RELAY-01's start tab. The
  tab already rises the least it can while staying a tab of its own node (10 px: its 13 px less a 3 px overlap), and the label's
  4 px drop and 1 px padding are what keep link lines off the letters. Moving either changes the phase-6 and
  presentability-2 placements for every graph to fix one pair, and spacing the layout further changes every layout,
  so no offset was changed.
- **Graph counts say entities.** *9 nodes · 8 links on screen* → *9 entities · 8 links on screen*, as the Working Set
  line, the *N entities masked* line and the toolbox say them.
- **Reveal says identifiers.** *Shows the real ids of …* → *Shows the real identifiers of …* in the canvas and
  Oversight Reveal dialogs, the Reveal button's tooltip, the Start box note and the Case-member refusal. The permission
  keeps its granted name, *See unmasked ids*, and the GLOSSARY's **Masking mode** has no other word for the unmasked
  value.
- Specs: `graph-view.component.spec.ts` (fold class, no rebuild), `link-analysis.component.spec.ts` (fold button, tab
  labels, counts words, Reveal dialog text), `link-analysis-investigation.component.spec.ts` (header lines and wrapped
  name, reviewer Undo / Redo), `expansion-decision.spec.ts`, `investigate-number.spec.ts`.
