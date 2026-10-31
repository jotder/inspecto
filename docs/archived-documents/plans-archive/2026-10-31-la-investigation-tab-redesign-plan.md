# Investigation tab redesign (`LA-INVESTIGATE-UX-1`) — design for approval

Status: **SHIPPED 2026-10-31** (approved by the operator as drafted, with the three §4 recommendations). Item:
`module.la-core`. As-built: `okf/product/module/la-core/architecture.md` §6.9.1 and user guide §7 / §11.2. Residuals:
BACKLOG `LA-INVESTIGATE-UX-2`. See §7 for what was built differently from this design.

## 1. The problem, from an investigator's chair

An investigator does five things over and over: **start** from something suspicious, **grow** the graph along the
links that matter, **clean it up** until only the story is left, **back out** of a wrong turn, and **keep** what
proves the point. The current tab is built the other way around: a long stack of sections (Expand form, Add start
entities, Selected entity, Busy entities, Steps, Scope list, links), each a bordered card, most controls behind a
pencil or a popup picker. The canvas, where the investigator is looking, can do little on its own: right-click offers
Exclude, Collapse and *Expand…*, which only opens the tab's form.

Concretely (read from the code, 2026-10-31):

* **Every expand re-states its filter.** Direction, From and Time are popup pickers (a dialog each); link kinds are a
  comma-separated text field behind a pencil; the filters sit in three closed folds.
* **Clean-up is half there.** Leaves, *keep only / remove selected* and *paths between start entities* exist (broom
  menu, each with Apply / Cancel). Weak links, an entity type, everything outside a time window, and *paths between
  two nodes I picked* cannot be cleaned up as a recorded step at all; the legend chips and the timeline hide them from
  the drawing only.
* **Undo without Redo.** Ctrl+Z takes back the newest step; nothing puts it back.
* **Exclude is two layers deep:** the Exclude popover, then a modal reason dialog.
* **No orientation.** Hints exist only for Expand, Held and Hidden-by-lists; the tab itself never says how to work.

## 2. Baseline and target, per workflow

Clicks are pointer presses (typing a value counts as one); *depth* is the number of popups / dialogs stacked at the
deepest point. Baseline = master `bf2a90392`, read from the templates; the target is what this design delivers. Both
get re-measured in the preview after the build.

| # | Workflow | Today: clicks · depth | Target: clicks · depth |
|---|---|---|---|
| W1 | Seed: start an Investigation from one entity (Dataset not yet picked) | 5 · 1 (Dataset picker dialog) | 4 · 1 (unchanged form; *Investigate this* on a node of an explored graph = **2 · 1**) |
| W2 | Expand the selected node 2 degrees, outgoing, one link kind | 10 + typing · 1 (From, Direction pickers; kinds typed) | **4 · 0** (Out, kind chip, depth +, Expand) — and every later expand with the same filter **1 · 0** (double-click, or the node menu) |
| W3 | Same, but only the last 30 days | +4 · 1 (Time fold, window picker, custom fields) | **+1 · 0** (*30 days* chip) |
| W4 | Cap fan-out at 50 neighbours per entity | 3 + typing · 0 | 2 + typing · 0 (the group remembers being open) |
| W5 | One-off: expand just this node, incoming only | 7 · 1 | **2 · 1** (right-click ▸ *Expand ▸ Incoming*) |
| P1 | Remove leaf entities | 3 · 1 (broom menu, Apply) | **1 · 0** (*Trim 16 leaves*) |
| P2 | Remove weakly linked entities (fewer than N events) | not possible as a step (undo, re-expand with Min events ≈ 8) | **2 + typing · 0** |
| P3 | Remove an entity type | not possible as a step (legend chip hides the drawing only) | **2 · 0** (type chip, *Hide 40*) |
| P4 | Remove everything outside a time window | not possible as a step (timeline filter is drawing-only) | **2 · 0** (window chip, *Hide 23*) — needs link times on the Working Set graph, see §6 R1 |
| P5 | Keep only what lies on a path between two nodes I picked | ≈ 9 across two tabs (Browser ▸ shortest path, select, Keep only selected) | **3 · 0** (click A, Shift+click B, *Keep paths between these 2*) |
| U1 | Undo the last step | 1 · 0 | 1 · 0 |
| U2 | Redo it | not possible | **1 · 0** |
| K1 | Keep (protect) a subgraph | lasso + scroll the tab + Keep = 3 · 0 | **2 · 0** (lasso, *Keep* on the selection bar) |
| X1 | Exclude the selection with a reason | 4 + typing · **2** (popover → reason dialog) | 3 + typing · 1 (reason inline, last reasons as one-click chips) |
| S1 | Save | 2 · 1 | 2 · 1 (unchanged) |

## 3. The design

### 3.1 One rule: the panel holds the filter, the canvas acts

The Investigation tab owns ONE **Expand filter** (direction, depth, link kinds, time window, strength, fan-out). Every
expand anywhere — the Expand button, a double-click, the node menu, the selection bar, **G** — uses it without asking.
The filter starts at the **Industry pack's presets** and the Space's limits (`DegreePresets`, busy limit, row limit),
so an untouched filter behaves exactly like today's untouched form. It lives in the Investigation store for the open
Investigation in this browser tab (a new Investigation starts at the defaults).

### 3.2 The tab, top to bottom (one column, compact, nothing nested)

1. **Header row** (one line): Name (pen) · *Unsaved* chip · **Save** · `?` (how to investigate) · `⋯` (Settings…,
   Close, Delete). Under it ONE quiet counts line: *42 entities · 118 links · degree 2 of 4 · 3 held · 2 hidden by
   lists*, where *held* and *hidden* are links to the existing trays.
2. **How to investigate** strip — first run only, dismissible (*Got it*, remembered in this browser like the existing
   hints; `?` brings it back): *1 Start from an entity · 2 Double-click or right-click to expand with the filter below ·
   3 Clean up what is noise · 4 Ctrl+Z undoes, Ctrl+Shift+Z redoes · 5 Save when it tells the story.*
3. **Action bar** (sticky at the top of the tab, so it never scrolls away): **Expand** (with the From choice as a
   split: *selection* when something is selected, else *outer ring*; the start entities and the whole Working Set in
   its small menu) · **Undo** · **Redo**. Under it the existing **preview line** (*adds about 342 numbers and 1,210
   calls; 3 busy numbers will be held*) and the four-eyes line (*Will need approval from a second person*), unchanged.
4. **Expand filter** — foldable groups. Each group header reads its current value on one line and shows a dot when it
   differs from the default; the filter's own header says *2 changed* and offers **Reset to defaults**; each group has
   a ↺ for its own reset. Groups and their controls (no popups, no pencils for choices):

   | Group | Control | Default | Header line |
   |---|---|---|---|
   | **Direction** | 4 toggle buttons: Both · Out · In · Mutual | Both | *Both ways* |
   | **Depth** | − n + stepper (the existing LA-DEGREES-TRIM-1 behaviour: lowering it while the newest step is an Expand shrinks that Expand) | 1 | *1 degree* |
   | **Link kinds** | one chip per kind, all on; *Only* on a chip's hover | all | *All link kinds* / *2 of 5 kinds* |
   | **Time** | chips: Investigation window · All data · 7 d · 30 d · 90 d · Custom (fields inline) | Investigation window | *Investigation window* |
   | **Strength** | Min events, min distinct days (pen fields, numeric) | pack preset / 1 | *At least 3 events* |
   | **Fan-out and busy** | Hold busy entities over H (toggle + number), most neighbours per entity, neighbour link band (fewest / most), row limit per degree | pack presets and the Space busy limit | *Busy over 500 · 50 per entity* |

   Open state per group is remembered in this browser; on first run only Direction and Depth are open.
5. **Clean up** — a fold holding one-click bulk actions. Each button names its effect with a live count, so it needs
   no Apply / Cancel (Undo is the safety net), and is disabled with the reason when the count is 0:
   *Trim 16 leaves* · *Hide 12 weakly linked (fewer than [3] events)* · *Hide type [chips] (40)* · *Hide 23 outside
   [window chips]* · *Keep paths between the 2 selected (max [4] links)* · *Keep only the 9 selected* · *Hide the 9
   selected*. Start entities and kept entities are never removed (said in the fold's one-line help). Each records ONE
   `hide` step with its reason filled in (as `recordCleanup` does today); **Exclude instead** (a toggle in the fold)
   records `exclude` with that reason. **Highlight shortest paths** stays (glow only).
6. **Selected** — only while something is selected: who it is (masked, held, not in the Working Set), its notes, the
   **Note** field and *Pick neighbours…* (the existing list, unchanged). The action buttons move to the canvas (3.3).
7. **More steps** (one closed fold): Connect start entities, Time window step, Snapshot marker, Threshold, Compare,
   Scope list (Include / Leave out), *Add start entities…*, and the links to the Step log and Investigation settings.
   These are rare; they stay as they are, just out of the way.

Removed from the tab (moved, not lost): the *Selected entity* button row (→ canvas), the *Busy entities* list (→ the
Held tray via the counts line; *Expand anyway* stays there and on the node menu), the separate Expand card chrome.

### 3.3 The canvas

* **Double-click** a node: expand it with the filter (today: only when not expanded yet; a node already expanded
  still folds / unfolds while exploring, unchanged).
* **Right-click** a node (or the selection): **Expand** (filter) · **Expand ▸** Outgoing / Incoming / one item per
  link kind (the filter with that one thing changed, for this step only) · **Shrink** (hide its leaf neighbours: the
  entities only it links to; one `hide`) · **Keep** · **Hide** · **Exclude…** · **Pick neighbours…** · **Select
  neighbours** · **Collapse / unfold** (drawing only) · **Expand anyway** on a held entity. With no Investigation open:
  **Investigate this** (opens the start form filled with that entity).
* **Selection bar** (exists): Expand · Keep · Hide · Exclude… · *Keep only these* · *Keep paths between these 2*
  (exactly 2 selected) · Attach to Case · Clear.
* **Exclude… reason inline**: the Exclude popover gets the reason field itself, with the last three reasons used in
  this Investigation as one-click chips; the modal reason dialog is no longer opened from the canvas. The *Add to a
  list…* branch is unchanged.
* **Keys** (same guards as today): **G** / **E** expand with the filter, **K** keep, **H** hide, **X** exclude,
  **Ctrl+Z** undo, **Ctrl+Shift+Z** / **Ctrl+Y** redo.

### 3.4 Undo and Redo

Undo stays the recorded `undo` (unchanged server semantics: it takes back the newest effective step and refuses when a
colleague's step is newer). **Redo re-records the undone step** as a new step with the same parameters, through the
same path as the original (so four-eyes, budgets, masking and access checks apply again); the redo stack is cleared
by any new step and by a colleague's step. The rail's Undo / Redo keep their scope (drawing and filter changes) and
their tooltips say so (*Undo a display change*), so the two pairs never look like one.

### 3.5 What does not change

The step vocabulary and the server (no new op; Redo and every Clean up action are existing `expand` / `hide` /
`keep` / `exclude` / `annotate` ops), the four-eyes hold (an expand over the thresholds still waits for approval and
says so before you press), *Expand anyway* as the recorded override, access checks (*Needs Incident management.* on
every write control, disabled with the reason), the unsaved flow and Save, the start form, ⚙ Settings, the dock tabs.
The explorer (no Investigation) keeps its Expand popup in this change.

## 4. Decisions for the operator

1. **Entity types in Expand.** An expand cannot filter by Entity Type today (`ExpandRung` has no such field). In a
   single-mapping Investigation the two ends are usually one type each, so a type filter on expand mostly duplicates
   Direction. **Recommended:** Entity types are a **Clean up** action only (P3), no backend change. The alternative is
   an `entityTypes` field on the expand rung, sealed in the step (a la-core + la-api change with its own tests).
2. **Clean up records `hide`, not `exclude`, by default** (reversible, no reason asked; *Exclude instead* is one
   toggle). Recommended.
3. **No Apply / Cancel on Clean up** — the button states its count and Undo takes it back. Recommended.

## 5. Build plan

| Step | Files | Proof |
|---|---|---|
| 1 Filter model | new `investigation-filter.ts` (defaults from presets, `changedCount`, per-group summary, `toRung()`) | `investigation-filter.spec.ts` |
| 2 Clean-up sets | new `investigation-cleanup.ts` (leaves, weak, by type, outside window, paths between two; start + kept spared), reusing `graph/graph-analysis.ts` | `investigation-cleanup.spec.ts` |
| 3 Redo | `link-analysis-investigation.store.ts` (redo stack, cleared on a new step) | store spec |
| 4 Tab | `link-analysis-investigation.component.*`, `link-analysis-expand.component.ts` (toggles and chips in place of pickers, group badges, reset) | component specs + axe |
| 5 Canvas | `link-analysis.component.{ts,html}` (node menu, selection bar, double-click, keys, inline exclude reason in `link-analysis-exclusions.component.ts`) | specs on the handlers |
| 6 Help | how-to strip, empty states, one-line help per group | spec |
| 7 Verify | `npx ng test` on the touched specs (exit code), tsc ×3, lint, lint:tokens; preview drive of every workflow in §2 with before / after screenshots | the §2 table re-measured |
| 8 Ship | la-core hub: user guide §7 / §11.2, `architecture.md`; BACKLOG row closed; this plan archived | — |

Commit `feat(la-core): …` from a clean sibling worktree off `origin/master`; not pushed without the operator's word.
The full reactor gate is not run (UI only): targeted UI specs plus typecheck and lint.

## 6. Risks

* **R1 — time on the Working Set graph.** P4 needs each link's time on the drawn graph. If the Working Set graph
  carries only counts, P4 is offered only when the timeline already has a time column (as the timeline filter is), and
  is disabled with that reason otherwise.
* **R2 — the 1,451-line component spec** asserts the current layout by `data-testid`; kept ids are kept, moved
  controls keep their id where they move, and removed ones are removed from the spec with the reason.
* **R3 — a concurrent session** (`LA-DOC-CODE-SYNC-1`) edits the same la-core docs; the doc step lands last and is
  merged against `origin/master` before the commit.

## 7. As built (2026-10-31)

* **Built as designed:** the one Expand filter in six folds (dots, *N changed*, Reset to defaults, a reset per group,
  open state remembered); toggles and chips in place of the Direction / From / Time pickers and the link-kind checkboxes;
  the filter published to `GrowExcludeStore` so double-click, the node menu and G/E use it; Redo; the Clean up fold
  (leaves, weakly linked, per Entity Type, paths between the 2 selected, keep only selected, *Exclude instead*); Keep on
  the selection bar and K / H keys; node menu *Expand*, *Expand, this once ▸*, *Shrink*, *Keep*, *Hide*, *Select
  neighbours*, *Expand anyway*, *Investigate this*; recent reasons in Exclude…; the *How to investigate* strip; Busy
  entities and More steps folded.
* **Differently:** From is a row of one-press chips, not a split button (one click instead of two). The Undo / Redo bar
  sits under the counts line (sticky), not in the Expand card. *Pick neighbours…* stayed in the tab.
* **Not built:** P4 (outside a time window) — R1 confirmed: `WorkingSetLink` has `count`, no time. Redo and Clean up for
  an UNSAVED Investigation.
* **Baseline correction:** X1 was 3 clicks · depth 1 on master, not 4 · 2: the node menu's Exclude… already asked its
  reason inline (`LinkAnalysisExcludeDialog`, prefilled); only the selection bar's Exclude used a second reason dialog.
  Both now open the same dialog, which adds the last three reasons as one-press chips.
* **Verification:** targeted specs (no full gate), typecheck ×4, lint, lint:tokens; the unsaved tab was driven in the
  Demo Auth preview (filter groups, change dot, reset). The saved-Investigation flows were NOT driven live (it would have
  left records in the shared demo Space): `LA-INVESTIGATE-UX-2`.
