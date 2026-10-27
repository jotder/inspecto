# Link Analysis Studio — UI reorganization plan (`LA-STUDIO-UI-REORG-1`)

Status: **P1-P3 ON MASTER (e80e9d511, 2026-10-27); P4 in flight.** Direction decided by the operator in an interview (UIR-D1 … UIR-D16); assistant calls ⚑UIR-A1 … A3 await confirmation. Independent verification of P1-P3: **FAIL** (2026-10-27) — every §6 row has a home at ≥ 1,300 px, but the defects are open as `LA-SETTINGS-OVERLAY-NEVER-HIDES-1` (P1), the `LA-REORG-*` rows and the still-open `LA-REVOKE-DISCARD-NO-CONFIRM-1` in `docs/BACKLOG.md` §3.12. User guides and glossary updated for P1-P3.
Item: `module.la-core` (UI in `inspecto-ui/projects/link-analysis/`). Board row: `LA-STUDIO-UI-REORG-1` in
`docs/BACKLOG.md` §3.12.

**Goal.** Make the Link Analysis Studio easier and cleaner to work in **without removing any function**. Every
control that exists today keeps a home; §6 is the map and is the acceptance test for "nothing removed".
Editions and the LA level keep hiding or locking what an installation lacks (§5.14).

**Non-goals.** No new server analysis. No change to the Investigation op vocabulary (the clean-up tools in §5.6
record existing ops). No change to sealing, the Dossier or masking.

---

## 1. What is there today (measured 2026-10-27)

Driven live in the MFS console (`inspecto-mfs`, port 8091, saved view `mfs_money_trail`, 921 nodes) and read
in code (`inspecto-ui/projects/link-analysis/src/link-analysis/`; host `link-analysis.component.ts` 4,403
lines, markup in `link-analysis.component.html`).

| Area | Today | Pain |
|---|---|---|
| Top bar | One `<nav data-testid="la-tool-rail">` with 23 icons (search, filter, layout, timeline, snapshot, algorithms, Investigation, Grow, Hidden, Attach to Case, help, saved views, import/export, save as a case, glossary, display, undo, redo, fit, list, full screen, export, rows, side panels, close). It wraps to two rows below ~1,300 px. | No grouping. File actions (open, save, close) are spread out, and there is no delete, rename or save-as for a saved view. Layout and display toggles are duplicated in the View tab (HTML:440-626 and 1432-1520). |
| Left panel | The Query panel: Domain profile picker, Graph source, Dataset, entity columns, link type, attributes, multi-mapping rows, node and edge mappings, root / depth / direction, Pipeline, Run / Edit / Refresh. Resizable, collapsible to a rail. | Setup you do once per graph takes a permanent column beside the canvas. |
| Right panel | Three tabs: Analysis, View, Investigation. | — |
| Analysis tab | Discover (3 plain questions), Temporal findings, an accordion of 17 groups. | Server algorithms are hidden in two places: a per-tool *Run on server* that replaces the local button only when `serverFirst()`, and an "All algorithms (server)" group. Nobody finds them. |
| View tab | 14 layouts, 12 overlay checkboxes, and "Lenses & selection" as 5 checkboxes (Fisheye, Link filter, Snap lines, Hover activate, Lasso). | Canvas interaction modes are hidden in a list of checkboxes. |
| Investigation tab | **27 folding sections** (`<la-fold>` × 27 in `link-analysis-investigation.component.html`), plus a non-folding header, data strip, seed queue and start form. | The main pain: config, actions, results tables and governance in one scrolling column. |
| Pointer | `canvasInteraction` = `panByDrag:false, boxSelect:true` (`link-analysis.component.ts:2750`). Plain drag draws a box. Panning is by minimap, arrows and wheel only. Lasso is an optional Shift + freehand behaviour. No Shift+click. | The box fights with drag-based lenses (Fisheye, Link filter), and the old drag-to-pan is gone. |
| Expand | Two tools: **Grow** (1-2 rings, busy nodes held, exploration or Investigation) and the Investigation's **Expand by degree** (one degree per click, max `profile.investigate.maxDegree`, default 6). Plus Expand anyway, Expand one hop, Expand the whole Working Set, Add selected, Connect seeds, the rung form. | No "N degrees, hubs held" in one action. |
| Collapse | Double-click folds a branch, and an "N collapsed" chip unfolds them. | Exploration only (`link-analysis.component.ts:2905`). |
| Clean-up | Per-entity Keep, Hide, Exclude; Hidden by lists. | No "keep only the paths between seeds", no trim of dead ends, no bulk keep. |
| Over cap | Radial / Information density / Clustering force fall back silently to another layout (`la-limits.ts:114-129`). Hulls are refused silently. Algorithms refuse with an alert. | The user does not see why a control did something else. |
| Saved views | Folder menu → per-view submenu (Load, Version history, Comments, Tags, Offer for sharing). The service's `remove` is never called from the UI. | No search, preview, rename, duplicate or delete. |
| Investigations | "Open an Investigation" fold → *List Investigations* (title + truncated id + Open/Delete) or *Open by id*. | No search, no date, owner or status, no preview. |
| Domain profile | Picker at the top of the Query panel (`link-analysis.component.html:866`) choosing an Industry pack per view. | Overrides the Space's pack per view, and loading a view flips the vocabulary mid-demo (`LA-SAVED-VIEW-DOMAIN-PROFILE-1`). |
| Read-only widget | `link-view-widget.component.ts` renders the graph with pan, zoom, drag and a legend. | No search, select, details, filter or algorithms for a viewer. |

**Also seen live (to fix inside the phases):**
- The minimap draws over the right panel when the panel is open.
- After an Investigation opens, the Working set overlay keeps the query graph's counts (921 nodes) while the
  canvas draws the Investigation's Working Set. Two graphs coexist, and the overlay does not say which it
  counts.

**Vocabulary clashes (binding glossary rule: one word, one concept):**
- "Save as a case" saves a **View**, while **Case** is an Incident-management object (*Attach to Case* sits beside
  it). → **Save view**.
- The canvas "Lenses" (Fisheye, Link filter) collide with the console **Lens** (Business / Builder / Ops). →
  **Canvas modes**. The glossary gets both rows.

---

## 2. Decisions (operator interview 2026-10-27)

| ID | Question | Decision |
|---|---|---|
| UIR-D1 | Where does the Investigation tab's content go? | **Act / Dock / Dialogs.** The right panel keeps only what you do while looking at the graph. Tables and results go to a bottom dock with tabs. Setup and governance go into one *Investigation settings* dialog with a left nav of nested forms, each with help (§5.8–§5.10). |
| UIR-D2 | How are the canvas interaction tools reached? | **A second icon toolbar** under the top bar: Canvas mode, selection actions and graph edits. One active mode at a time, shown highlighted (§5.2). |
| UIR-D3 | Pointer model | **Plain drag pans (as before). Shift+click adds or removes. Shift+drag lassos.** No rectangle select. Canvas modes are exclusive, so a Fisheye drag never fights the lasso. Esc returns to Pan (§5.3). |
| UIR-D4 | Read-only Widget / report toolset | **Look + browser algorithms:** pan, zoom, search, select, details, filter by type and time, hide, layout, Canvas modes, browser algorithms. Nothing is saved or recorded. Export only if the sharer allowed it. No Grow, no server runs, no reveal (§5.13). |
| UIR-D5 | Expand N degrees without supernodes | **One Expand dialog** replaces Grow and Expand by degree: degrees 1 to N (N = the pack's `maxDegree`, default 6), supernodes held at the hub threshold, a preview before it runs, early stop at the frontier cap. In an Investigation each degree is its own recorded `expand` step, grouped as one action for Undo (§5.4). |
| UIR-D6 | Collapse | **Visual only, everywhere**, inside an Investigation too. Never a step, never changes the Working Set; kept in the view's display state (§5.5). |
| UIR-D7 | Clean-up tools | All four: **Keep paths between seeds**, **Highlight shortest paths** (glow, nothing removed), **Trim dangling leaves**, **Keep / remove selection** (§5.6). |
| UIR-D8 | "Keep shortest paths glowed" | **Highlight only:** the shortest paths between seeds glow, the rest dims, one click turns it off. *Keep paths between seeds* stays the tool that actually hides the rest. |
| UIR-D9 | Domain profile picker | **Removed from the Studio.** The Space's Industry pack (Settings) always applies. An Investigation Template may name a pack for a Space that mixes verticals. Saved views and Investigations keep their pack, shown read-only (§5.12). |
| UIR-D10 | Browser vs server algorithms | **One catalogue with a Browser / Server toggle.** Each algorithm appears once, grouped by question, with a badge (browser, server, both) and its cap. Over the browser cap, Server is preselected with the reason (§5.8). |
| UIR-D11 | Layouts and overlays over the UI cap | **Disabled with a reason**, plus a "reduce first" hint. The silent fallback goes (§5.7). |
| UIR-D12 | Opening and saving | **One Open dialog** (Saved views / Investigations tabs) with search, filters and a preview pane. File actions become a **File menu**: New, Open, Save, Save as, Close, Delete (§5.11). |
| UIR-D13 | Duplicated toolbar / View tab | **The toolbar menus win.** Layout and Display stay as top-bar menus, Overlays merge into Display, Canvas modes move to the second toolbar, and the View tab is removed. The right panel keeps Analysis and Investigation. |
| UIR-D14 | What a missing edition / add-on shows | **Lock in place, hide whole areas.** A single action the edition lacks is shown locked with a one-line reason (today's `inspecto-locked-action`). A whole area the edition lacks (the Investigation dock tabs and ⚙ Settings → This Investigation, the Server toggle, Geo) is hidden behind one locked entry point that explains. A read-only viewer never sees locked items (§5.14). |
| UIR-D15 | Order | **Canvas first** (§7): canvas work, then the Investigation split, then Open / File / catalogue / profile, then the read-only toolset. |
| UIR-D16 | Where does setup live? (operator, 2026-10-27, after the interview) | **Configuration you change rarely or once goes under one ⚙ Settings icon:** the graph model (Dataset, entity columns, relations / link type, attributes, mappings: today's left Query panel), Entity Types, display rules, Entity Lists, Identities, Add / Upload data. The left Query panel is removed and the canvas gets its width. Frequent per-item actions (add the selection to a list, exclude, merge) stay on the canvas (§5.10). |

Decided by the assistant while writing (confirm or override; marked ⚑ in the text):
- ⚑ **UIR-A1:** The ⚙ Settings dialog also holds the open Investigation's settings (UIR-D1) as a third group, so there is one settings icon, not two. *Investigation settings…* in the Act `⋯` menu opens the same dialog at that group.
- ⚑ **UIR-A2:** Value Measures appears once, in the Analysis catalogue. Its duplicate Investigation fold goes; inside an Investigation the catalogue entry fills its Dataset and columns, as the fold does today.
- ⚑ **UIR-A3:** In an Investigation, the clean-up tools record `hide` steps over the entities they take away, so they can be undone and appear in the Dossier. Phase 1 confirms in code whether `keep` already means "keep only".

---

## 3. Target layout

```
┌ Top bar (row 1: file and view) ──────────────────────────────────────────────────────────────┐
│ File▾ │ Search  Filter▾  Time▾ │ Layout▾ Display▾ │ Undo Redo │ Fit List Full │ Export▾ │ ? Glossary │ ⚙ │
├ Second toolbar (row 2: work on the canvas) ──────────────────────────────────────────────────┤
│ Mode: [Pan] Lasso Fisheye LinkFilter │ Select: all invert neighbours clear │                 │
│ Expand… Collapse Hide Exclude Merge │ Clean up▾ (paths, shortest glow, trim, keep/remove sel) │
│ Held(3) Hidden(41) Attach to Case │ "N selected" chip                                        │
├──────────────────────────────────────────────────────────────────────┬────────────────────────┤
│ Canvas (+ legend, Working set tiles, minimap, held tray)              │ Right panel            │
│ (no left Query panel: the graph model is in ⚙ Settings, UIR-D16)     │  Analysis | Investigation │
│                                                                      │  (catalogue)  (Act)     │
├──────────────────────────────────────────────────────────────────────┴────────────────────────┤
│ Dock (tabs): Rows · Op log · Working Set rows · Coverage · Annotations · Excluded · Results   │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│ Status bar: Caps · source and query chips · counts · modules                                  │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

Below ~1,300 px the second toolbar collapses its groups into overflow menus. It never wraps to a third row.

---

## 4. Principles for every control

1. **One home per function.** A second entry point is allowed only as a shortcut to the same home (key, right-click, selection toolbar), never as a second copy of the form.
2. **Act on the canvas, read in the dock, configure in a dialog.**
3. **Help where it is used.** Every dialog section and dock tab has a `?` with one line of help and a link to the user guide section. Today's first-use notes ("Got it") stay.
4. **Say why, never fall back silently.** A disabled control names its limit and the current size.
5. **Destructive actions confirm and name their target.** This also closes `LA-REVOKE-DISCARD-NO-CONFIRM-1`.

---

## 5. Area designs

### 5.1 Top bar (row 1)
- **File ▾**: New (back to *Start from…*); Open… (§5.11); Save view; Save view as…; Save this analysis (Snapshot, optionally attached to a Case); Import / export views; Close; Delete (the open view or Investigation, with a confirm).
- **⚙ Settings** (right end, UIR-D16): opens the settings dialog (§5.10).
- **Search, Filter node types, Filter by time**: unchanged menus.
- **Layout ▾**: the 14 layouts. Tree layouts and over-cap layouts are disabled with a reason (§5.7).
- **Display ▾**: today's Display options, plus the View tab's overlays (Legend, Working set, labels, Level of detail, Minimap, Grid, Community hulls, Group lone counterparties, Bubble sets, Link bundling) and display rules.
- **Undo, Redo, Fit, Show as list, Full screen, Export ▾, Help, Glossary**: unchanged. *Show the rows* moves to the dock toggle, and *Show the side panels* stays at the right end.

### 5.2 Second toolbar (row 2)
- **Canvas mode** (one active, highlighted; key in the tooltip): Pan (default, `Esc`), Lasso (`L`), Fisheye (`F`), Link filter. Snap lines and Hover activate are display preferences, so they move to Display.
- **Selection**: Select all (`Ctrl+A`, super-nodes excluded as today), Invert, Add neighbours, Clear (`Esc` twice). An "N selected" chip.
- **Graph edits** (act on the selection, or the right-clicked node): Expand… (`G`, §5.4), Collapse (`C`, §5.5), Hide, Exclude (`X`), Merge (Investigation only, as today), Attach to Case.
- **Clean up ▾** (§5.6).
- **Held (n)** opens the held tray. **Hidden (n)** opens Hidden by lists.
- The floating selection bar (HTML:1058) is removed: its six actions live here. Right-click keeps the per-node menu.

### 5.3 Pointer model (UIR-D3)
- Pan mode: drag on empty canvas pans; drag on a node moves it (and the selection with it); wheel zooms; Shift+click toggles a node in the selection; **Shift+drag draws a lasso** (add to the selection; Alt+Shift+drag removes).
- The G6 `brush-select` (rectangle) is removed. A saved view that names it opens as Pan.
- Fisheye and Link filter own the drag while active. Shift+click still selects. `Esc` returns to Pan.
- The Lasso mode exists for touchpads (drag = lasso without a key).
- Keyboard panning (arrows, Shift+arrows) and `+`/`-` stay.
- Code: `canvasInteraction` (`link-analysis.component.ts:2749`) and `buildBehaviors` (`src/app/inspecto/graph/graph-view.component.ts:113-145`, shared with the console graph, so the Studio passes its own options and the shared default does not change).

### 5.4 Expand dialog (UIR-D5)
- One dialog replaces the Grow popover (`link-analysis-grow.component.ts`), the *Expand by degree* fold and the rung form (`investigation-expand-rung.component.ts`).
  - **Basic** fields: From (selection, seeds, or the whole Working Set), Direction, **Degrees 1…N**, Hold supernodes above *H* links (on by default; *H* = the hub threshold in force).
  - **Advanced** fold: Link kinds, Time window, min events, min distinct days, candidate degree band, maximum fan-out, row budget.
  - A **preview line** ("Degree 1 adds about 240 entities, 3 held; degree 2 is estimated after degree 1") before it runs.
- Running walks degree by degree and stops at the frontier cap (2,000) with a message naming the degree reached. Held supernodes go to the Held tray (Expand anyway stays there).
- In an Investigation: one `expand` step per degree, tagged with one group id, so Undo takes back the group. A four-eyes hold on any degree stops there and says so (today's behaviour per step).
- Entry points: `G`, the second toolbar, right-click, double-click on an unexpanded node (one degree, no dialog). *Expand anyway*, *Expand one hop*, *Add selected* and *Add the N strongest* stay as quick actions that open nothing.

### 5.5 Collapse (UIR-D6)
- Collapse folds a node's branch (the neighbours that link to nothing else on the canvas) into a `+n` badge on the node. `C`, right-click, the second toolbar. Double-click on an expanded node keeps folding as today.
- Inside an Investigation it is display state only: no op, the Working Set is unchanged, and the Dossier is unaffected. It is saved with the view's display state.
- The "N collapsed" chip on the second toolbar unfolds all.

### 5.6 Clean-up tools (UIR-D7, UIR-D8)
- **Keep paths between seeds**: keep the seeds (Investigation seeds, or the selection in exploration) and every node on a path of at most *L* links between two of them (default 4, a field). Everything else is hidden.
- **Highlight shortest paths**: the shortest paths between every pair of seeds glow and everything else dims. Nothing is removed. One click (or `Esc`) clears it. This reuses the Connect-seeds emphasis (`showConnectRoute`).
- **Trim dangling leaves**: repeatedly remove non-seed nodes with one link until none is left. Pinned and held nodes stay.
- **Keep only selected / Remove selected**: bulk versions of today's per-entity Keep and Hide.
- Every tool first states its effect ("hides 612 of 921 entities") with Apply / Cancel.
  - In exploration: a presentation hide, with Undo.
  - In an Investigation: recorded ⚑UIR-A3, reason prefilled ("not on a path between seeds, L = 4"), grouped for Undo.
- Caps: path enumeration uses the browser path code under its node cap. Over the cap the tool is disabled with the reason and offers *Find paths (server)*.

### 5.7 Over the UI cap (UIR-D11)
- Each layout and each overlay declares its limit (today's `CAPPED_LAYOUT_FALLBACK` limits, the hull limit, Level of detail's 300). Above it, the control is disabled with a tooltip: *needs at most 300 nodes — this graph has 921*.
- The disabled control has a "reduce first" link that opens Clean up / Filter / Collapse.
- The silent fallback (`la-limits.ts:114-129`) is removed, and so is the fallback notice (HTML:1162).
- Algorithms keep their behaviour through the catalogue (§5.8).

### 5.8 Right panel
- **Analysis** = Discover (3 plain questions) on top, then **one algorithm catalogue** (UIR-D10).
  - A Browser | Server toggle sets where it runs. Each row shows the algorithm, the question it answers, a badge and the cap.
  - The 29 server algorithms (`GET /inv/graph/algorithms`) and the 17 browser groups merge by question. A server-only algorithm shows only under Server.
  - Over the browser cap, Server is preselected with the reason.
  - The Server toggle is hidden on the Explorer level and on Basic (UIR-D14). A locked entry explains it once.
  - Results show in the panel. A long table has *Open in the dock* (Results tab).
  - Temporal findings stays in the catalogue as "Burst and periodicity".
- **Investigation (Act)**. The order is fixed; nothing scrolls past more than one screen.
  1. Header: title, purpose, status, LA level chip, counts, degree reached, a `⋯` menu (Settings…, Close, Delete), and the data-trust strip as one chip that opens its detail.
  2. Seed queue (when not empty).
  3. **Expand…** button + *Expand next degree* quick action, and the four-eyes waiting line when there is one.
  4. **Selected entity** (open when something is selected): Seed, Keep, Hide, Exclude, Annotate, Expand one hop, neighbour list.
  5. **Held** (high-connectivity entities, the same list as the canvas Held tray).
  6. **Scope list** (include / leave out).
  7. **More steps ▾**: Time window, Threshold, Snapshot marker, Compare, Connect seeds. Each opens a small dialog.
  8. **Last 5 steps** with *Open the Op log* (dock).
- Without an Investigation the tab shows the start form and *Open…* (§5.11), or the locked entry on the Explorer level.

### 5.9 Bottom dock
- Tabs: **Rows** (today's Links/Nodes strip), **Op log** (with Re-order steps as an action), **Working Set rows**, **Coverage**, **Annotations** (entity and link), **Excluded entities**, **Results** (long algorithm tables).
- Resizable, collapsible to a tab strip, remembered per browser. Investigation tabs appear only when one is open, and are hidden on the Explorer level.

### 5.10 ⚙ Settings dialog (UIR-D16, UIR-D1)
One full dialog with a left nav in three groups and nested forms, each with a `?`. Configuration you change rarely
or once lives here; what you do many times a session stays on the canvas, second toolbar and Act panel.

**This graph** (today's left Query panel and canvas setup):
- **Graph model**: the Dataset, the two entity columns (which way a link runs), relations (link type column,
  multi-mapping rows, node and edge mappings), attribute columns (amount, time, country), root node, depth and
  direction, Pipeline. *Run* and *Refresh* sit at the foot of the form.
  - The status bar keeps the Dataset / Mapping / Link type / Attributes chips. Clicking a chip opens this section.
  - The status bar gets a **Refresh** button beside them, so re-running the same query needs no dialog.
  - *Start from…* (the empty canvas) and *Explore a Dataset* stay the first-run path. *Explore a Dataset* opens this section.
- **Entity Types**: the types the graph is drawn as, New Entity Type, and Retire (closes sweep S3: today a type cannot be deleted or retired from the UI).
- **Display rules**: node colour, shape and icon rules (today inside Display options), per-kind colours and line styles.

**Data:**
- **Entity Lists**: create, edit, purpose, members. Adding the selection to a list stays a canvas action.
- **Identities**: identity resolution facts and their reasons.
- **Add / Upload data**: Add data, Upload a CSV.

**This Investigation** (shown when one is open; ⚑UIR-A1):
- General: title, purpose, time column and zone, lineage, Delete.
- Case
- Members (confirm on revoke)
- Share and embed
- Pin to a Widget
- Drafts (confirm on discard)
- Oversight and approvals
- Evidence: Dossier, Replay and drift
- Templates and measures, including *Save as template* and the template's pack (UIR-D9)
- References

Rules for the dialog:
- A section the edition or role lacks is hidden (§5.14).
- The dialog remembers the last section per browser.
- A form with unsaved changes asks before closing.

**As built (P2b, 2026-10-27):** `LinkAnalysisSettingsComponent` (`link-analysis-settings.component.ts`) is an in-page
overlay, not a MatDialog: the host PROJECTS each section (`<div laSection="…">`) and the dialog only hides the others, so
the query panel stays the host's `@ViewChild` and a half-edited form survives closing. The left panel and its split are
gone; a run closes the dialog and marks the form pristine; closing over an un-run edit asks. Refresh (status bar and the
Graph model foot) is `refreshFromServer()`, the cache-bypassing re-read of the last run. Entity Types
(`link-analysis-entity-types.component.ts`) lists the types in force, reuses *New Entity Type*, and **Retire** states the
types in force minus one through the existing `PUT /settings/link-analysis` (no new route; sweep S3 closed). Display
rules moved out of the Display menu. Entity Lists, Identities and Upload are mounted only while ⚙ is open, because the
Investigation tab still holds copies until P2a moves them (two live Identities would both consume a merge hand-off).
This Investigation is a placeholder slot until P2a's `InvestigationSettingsSections` lands.

### 5.11 Open dialog and File menu (UIR-D12)
- One dialog with two tabs, **Saved views** and **Investigations**.
  - A search box, filters (owner, tag, Dataset, date, status for Investigations), and a sortable list.
  - A preview pane: thumbnail, description, Dataset, entity and link counts, owner, last changed, linked Case, pack, Not-evidence note for views.
  - Row actions: Open, Rename, Duplicate, Delete (confirm), Version history, Comments, Tags, Offer for sharing.
- It replaces the folder menu with submenus, *List Investigations*, *Open by id* (kept as a search on an id) and the remembered-Investigations picker (kept as a "Recent" filter).
- Server: views already have list, get, save and remove (`link-analysis.service.ts:94-117`). Phase 3 checks whether rename and a thumbnail need a route (new routes follow `.claude/memory/new-route-checklist.md`).

### 5.12 Domain profile (UIR-D9)
Today the profile is not dead. It relabels terms, finds the measure and time columns for the Working set tiles, badges suggested tools, and supplies the *Investigate a number* default binding, degree presets and max degree. All of that is kept; only the per-view override goes.
- Remove the picker (`link-analysis.component.html:866`, `profileControl` `link-analysis.component.ts:1326`). It sits at the top of the Query panel, which itself moves into ⚙ Settings → Graph model (UIR-D16) without it.
- `profile` reads the Space's pack (`activeDomainProfileId()`), unless an open Investigation or a Template bound another.
- Saved views and Investigations show their stored pack read-only in the Open preview and in Settings → General.
- The Template gains an optional pack field.
- This closes `LA-SAVED-VIEW-DOMAIN-PROFILE-1`.

**As built (P2b, 2026-10-27):** no picker; `profileId = investigation.boundProfileId() ?? boundProfile() ??
activeDomainProfileId()`. `boundProfile` is set by an *Investigate a number* binding and cleared by Close; a saved view's
stored pack never binds it (`LA-REORG-VIEW-PACK-OVERRIDE-1`): it is shown in the Open preview and re-saved unchanged. *Save as template* takes an optional `pack` (`POST /inv/investigations/{id}/template`, shape-checked
as an id, 422 otherwise); an instantiated Investigation's header carries it as `pack`, which `boundProfileId` reads when
the pack is installed. The pack in force shows read-only at the top of ⚙ → Graph model. `LA-SAVED-VIEW-DOMAIN-PROFILE-1`
closed: a view without a pack no longer overrides the Space's.

### 5.13 Read-only widget toolset (UIR-D4)
- `link-view-widget.component.ts` gains a compact toolbar:
  - Search, Filter node types, Filter by time, Layout, Fit
  - Canvas modes (Pan, Lasso, Fisheye, Link filter), selection with a details pop-over
  - Hide (session only)
  - A small **Analyse** menu with the browser algorithms: Shortest path, Centrality, Communities, Components, Cycles, Cut points
  - Export only when the sharer allowed it
- Nothing is saved or recorded, no server call beyond the projection the widget already reads, no reveal, no Grow.
- The same toolset applies to the embed viewer (read-only Investigation) where the viewer lives (outside this library — locate it in phase 4).
- Sharer setting "Allow export" on the Widget.

### 5.14 Editions and capability gating (UIR-D14)
Whole areas are hidden. Single actions are locked with a reason. A read-only viewer sees neither.

| Area / control | Basic (Explorer) | Standard+ without Investigations add-on | Standard+ with add-on | Read-only viewer |
|---|---|---|---|---|
| Second toolbar: modes, selection, Expand, Collapse, Clean up (exploration) | ✅ | ✅ | ✅ | modes + selection only |
| Merge, recorded clean-up, Investigation tab (Act) | locked entry | locked entry | ✅ | — |
| Dock: Op log, Working Set rows, Coverage, Annotations, Excluded | hidden | hidden | ✅ | — |
| ⚙ Settings: This graph (Graph model, Entity Types, Display rules) | ✅ | ✅ | ✅ (New / Retire Entity Type by role) | — |
| ⚙ Settings: This Investigation | hidden | hidden | ✅ (by role) | — |
| Algorithm catalogue: Browser | ✅ | ✅ | ✅ | subset (§5.13) |
| Algorithm catalogue: Server toggle | hidden + one locked line | hidden + one locked line | ✅ (`canRunLinkGraphAnalysis`) | — |
| Save view / Open views | ✅ | ✅ | ✅ | — |
| Attach to Case, Save view (needs `canManageIncidents`) | locked | per role | per role | — |
| Geo | hidden (`geoMap`) | ✅ | ✅ | — |
| ⚙ Settings: Data (Entity Lists, Identities, Add / Upload data) | ✅ (per `EDITIONS.md`) | ✅ | ✅ | — |

Signals: `LA_FEATURES` (`la-host/la-host.ts:240-282`), `investigationsEnabled`, `LensService` capabilities, and `/bootstrap` `featureReasons` for the lock text. This agrees with the glossary's LA level rule: actions locked, whole pages out of navigation.

---

## 6. Function preservation map (acceptance: every row has a home)

| Today | New home |
|---|---|
| Search, Filter node types, Timeline | ✅ P1-P3 — Top bar (unchanged) |
| Layout menu + View-tab layout grid | ✅ P3 — Top bar Layout ▾ (one copy) |
| Display options + View-tab overlays | ✅ P3 — Top bar Display ▾ (one copy) |
| Lenses & selection checkboxes | ✅ P1a — Second toolbar Canvas mode. Snap lines and Hover activate → Display ▾ |
| Snapshot ("Save this analysis") | ✅ P3 — File ▾ Save this analysis |
| Graph algorithms button | ✅ P3 — Right panel Analysis (button kept as the panel toggle) |
| Investigation button | ✅ P2 — Right panel Investigation |
| Grow popover | ✅ P1 — Expand dialog |
| Hidden by lists, Held tray | ✅ P1a — Second toolbar Hidden (n), Held (n) |
| Attach to Case | ✅ P1a — Second toolbar |
| Saved views menu + submenus | ✅ P3 — File ▾ Open… dialog |
| Import / export views | ✅ P3 — File ▾ |
| Save as a case | ✅ P3 — File ▾ Save view / Save view as… |
| Glossary, Help | ✅ P1-P3 — Top bar (unchanged) |
| Undo, Redo, Fit, Show as list, Full screen, Export | ✅ P1-P3 — Top bar (unchanged) |
| Show the rows | ✅ P2a — Dock: Rows tab (the toolbar toggle opens and closes the dock) |
| Show the side panels, Close the analysis | ✅ P3 — Side panels: top bar right end. Close: File ▾ only (the right-end close icon was removed in P3a) |
| Floating selection bar (Expand, Hide, Exclude, Merge, Attach, Clear) | ✅ P1a — Second toolbar (Expand = today's Grow until the P1b dialog) |
| Right-click node menu | ✅ P1 — Kept (Expand…, Collapse, Hide, Exclude, Seed, Details) |
| Domain profile picker | ✅ P3 — Removed. The Space pack applies; stored pack shown read-only; Template field (UIR-D9) |
| Query panel (Dataset, entity columns, link type, attributes, mappings, root / depth / direction, Pipeline, Run, Edit, Refresh) | ✅ P2 — ⚙ Settings → Graph model; status-bar chips open it; status-bar Refresh (UIR-D16) |
| "Change the query" pencil (status bar) | ✅ P2 — Opens ⚙ Settings → Graph model |
| Display rules (inside Display options) | ✅ P2 — ⚙ Settings → Display rules; Display ▾ keeps the quick toggles |
| Entity Types, New Entity Type (Entities area) | ✅ P2 — ⚙ Settings → Entity Types (+ Retire) |
| Discover, Temporal findings | ✅ P3 — Analysis tab |
| 17 browser groups, *Run on server*, *All algorithms (server)* | ✅ P3 — One catalogue with a Browser / Server toggle |
| Value Measures (toolbox + Investigation fold) | ✅ P3 — Catalogue entry only ⚑UIR-A2 (✅ P2a removed the Investigation fold) |
| Inv: Open an Investigation, remembered picker | ✅ P3a — File ▾ Open… ▸ Investigations (Recent filter) |
| Inv: Case, Members, Share and embed, Drafts | ✅ P2a — `InvestigationSettingsSections` (temporary host: Act ⋯ ▸ Settings…); ⚙ Settings → This Investigation in P2b |
| Inv: Expand by degree | ✅ P2a — Act: Expand… / Expand next degree |
| Inv: Scope list, High-connectivity entities, Selected entity | ✅ P2a — Act |
| Inv: More steps (window, threshold, snapshot, compare, connect) | ✅ P2a — Act: More steps ▾ (each step opens in place, one at a time) |
| Inv: Op log, Re-order steps | ✅ P2a — Dock: Op log; Act keeps the last 5 steps |
| Inv: Annotations, Link annotations | ✅ P2a — Dock: Annotations (with Annotate a link) |
| Inv: Coverage | ✅ P2a — Dock: Coverage |
| Inv: Replay and drift, Dossier | ✅ P2a — `InvestigationSettingsSections` (Evidence group in P2b) |
| Inv: Pin to a Widget | ✅ P2a — `InvestigationSettingsSections` |
| Inv: Oversight and approvals | ✅ P2a — `InvestigationSettingsSections`; the waiting line stays in Act |
| Inv: Working Set rows | ✅ P2a — Dock |
| Inv: Measures and templates, References | ✅ P2a — `InvestigationSettingsSections` |
| Inv: Excluded entities | ✅ P2a — Dock |
| Inv: Entity Lists, Identities, Upload data | ✅ P2a — `InvestigationSettingsSections`; ⚙ Settings → Data in P2b (UIR-D16) |
| Inv: header (Close, Delete, lineage, read-only / Draft alerts, data strip, counts) | ✅ P2a — Act header + `⋯` menu (Settings…, Close, Delete) |
| Status bar (Caps, chips, counts, modules) | ✅ P1-P3 — Unchanged |

---

## 7. Phases (UIR-D15)

Each phase is one lane (`.claude/memory/lane-integration-workflow.md`). Tests: the touched component specs under
`npx ng test` (`.claude/memory/ui-test-traps.md`), an axe pass on the changed screens, and a live drive in a
Space with data (MFS or `la-showcase`). Each phase ends with the §6 rows it covers checked off in this file.

Status 2026-10-27: **P1 ✅ shipped · P2 ✅ shipped (revoke / discard confirms NOT built: `LA-REVOKE-DISCARD-NO-CONFIRM-1` open) · P3 ✅ shipped · P4 in flight.** Open defects: BACKLOG §3.12 `LA-SETTINGS-OVERLAY-NEVER-HIDES-1`, `LA-REORG-*`.

| Phase | Scope | Done when |
|---|---|---|
| **P1 — Canvas** | Pointer model (§5.3); second toolbar (§5.2) with the selection bar folded in; Collapse in Investigations (§5.5); clean-up tools (§5.6); Expand dialog (§5.4); over-cap disabling (§5.7); minimap overlap fix; "Canvas modes" rename. | Drag pans, Shift+drag lassos, and no box exists. Fisheye and lasso never both take one drag. Expand 3 degrees with hubs held in exploration and in an Investigation (3 grouped steps, one Undo). Each clean-up tool shown in both modes. Radial over 100 nodes is disabled with its reason. |
| **P2 — Investigation split + ⚙ Settings** | Act panel (§5.8), dock (§5.9), the ⚙ Settings dialog with all three groups (§5.10): the Query panel moves into Graph model and the left panel is removed, Entity Types with Retire, Display rules, Data, This Investigation; status-bar Refresh; confirms on revoke and discard. | The Investigation tab has at most 8 blocks and no fold. Every Investigation and Query-panel row of §6 is reachable. No left panel. `LA-REVOKE-DISCARD-NO-CONFIRM-1` and sweep S3 closed. |
| **P3 — Open, File, catalogue, profile** | Open dialog and File menu (§5.11); one algorithm catalogue (§5.8); View tab removed (UIR-D13); Domain profile picker removed (§5.12); "Save view" rename; Working set overlay says which graph it counts. | Search, preview, rename, duplicate and delete a view. Find a server algorithm in at most two clicks. `LA-SAVED-VIEW-DOMAIN-PROFILE-1` closed. |
| **P4 — Read-only toolset** | Widget toolbar (§5.13), "Allow export" on the Widget, the embed viewer. | A Business-lens viewer on a dashboard searches, selects, filters and runs Shortest path, and no write or server-run request leaves the page (network log). |

On ship: update `okf/product/module/la-core/user-guide-end-user.md` (§4 The screen, §6, §7, §8),
`user-guide-link-analysis.md`, the glossary rows (Canvas mode, saved View wording), and archive this plan.

---

## 8. Open points (for the builder, not the operator)
- **OP-1:** Does the `keep` op already mean "keep only these"? If yes, *Keep only selected* records `keep`; otherwise `hide` over the complement (⚑UIR-A3).
  **Answered (P1c, 2026-10-27): no.** `InvestigationEvaluator` (`case "keep"`) only adds the ids to `kept`, which
  protects them from a later `exclude` / threshold; nothing else is hidden. So *Keep only selected* records `hide` over
  the complement. A `hide` takes no reason, so the prefilled reason rides on a following `annotate` step over the same
  ids. Both are recorded in one function, `GrowExcludeStore.cleanUp` (`grow-exclude.store.ts`), so ⚑UIR-A3 can switch
  there. Since 2026-10-27 the pair is ONE change: its Undo is OP-4's grouped Undo over both steps.
- **OP-2:** Rename and thumbnails for saved views: is a new route needed (PUT with a new name, or a stored PNG)?
  **Answered (P3a, 2026-10-27): no new route.** A view's name travels as `title` in its content, so Rename is the
  existing update of the same id, and Duplicate is a create under `<id>-copy`. No thumbnail: nothing stores one, and a
  view re-projects live data, so a picture of the last draw would show a graph the view may no longer draw. The
  preview says so instead (counts are taken when it opens). Saved views carry no owner (the Component store records
  none), so the Owner filter applies to Investigations only.
- **OP-3:** Where does the embed viewer live? It is outside `projects/link-analysis`.
  **Answered (P4, 2026-10-27):** `projects/la-app/src/app/embed-view.page.ts` (`/embed/inv/:token`, LA-EMBED-SPA-1), the
  no-login viewer of ONE shared Investigation. It renders the graph, but not through the shared G6 host: it draws its
  own static SVG (one ring per hop, `layoutEmbedGraph`), with zoom, pan, Reset view, a find box over the Working Set
  table and Highlight. The §5.13 toolset rides on the G6 host (Canvas modes are its lasso and lens plugins, Layout is
  its layouts), so giving the embed page the same toolset means replacing its renderer with `GraphViewComponent` and
  re-proving its no-login contract (one read, keyboard canvas, token scrub). Not done in P4: it is the open residual on
  `LA-STUDIO-UI-REORG-1` in the BACKLOG. The Widget's toolset (§5.13) is built: `link-view-widget.component.ts`, its pure
  half `link-view-widget-tools.ts` (filters, the six analyses, their blocks), and the Widget option `allowExport`
  (Widget editor checkbox, passed through `viz-render` only when on).
- **OP-4:** Grouped Undo for an N-degree expand: the server's `{undoes: step}` takes one step; the client undoes the group newest-first and stops on a 409 (another person's newer step).
  **Built (2026-10-27):** one mechanism, `GrowExcludeStore.undoOf(steps)`, serves the Expand dialog's N degrees AND a
  clean-up's `hide` + `annotate` pair. Undo (the change line's button or Ctrl+Z) is offered while those steps are still
  the newest; it undoes them newest-first, each `undo` naming its step, and on the first 409 it stops and shows the
  server's refusal, which names the colleague's newer step. Nothing older is undone under it.
- **OP-5:** Sweep defects S1 (stale list picker), S2 (list start draws 0 links), S5 (`coverage` 422) and S6 (unprompted `PUT /settings/link-analysis`) touch the same screens. Fix S5 and S6 before P2. S1 and S2 fit P3's Start-from.

## 9. Risks
- `link-analysis.component.ts` is 4,403 lines and peers edit it. Each phase is one lane, integrated alone.
- The shared `graph-view.component.ts` also serves the console graph. The Studio passes options; the shared defaults do not change.
- Demo scripts and user guides cite today's buttons (MFS `rehearsal.md`, `la-studio-demo.md`, showcase runbooks). Re-run the run-of-show after P1 and after P2.
