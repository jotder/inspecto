# Canvas UX compaction plan — the pipeline editor from the builder's chair

**Status: COMPLETE 2026-08-21 — S0–S6 ALL SHIPPED; archived. As-built:
`docs/okf/frontend/features/pipelines.md` §"The canvas-UX compaction".**
**One amendment to the recommendations: D3 — `enrichment` moves in the SAME pass as wave 1
(operator chose "all kinds in one pass"), not after it.**
**Written 2026-08-21**, from (a) a full read of the editor stack and (b) driving the running UI
offline (`inspecto-ui-offline`, mock store) as an end user: opened `cdr_ingest`, created a scratch
pipeline, authored a Delimited Grammar end-to-end (paste sample → Test parse → schema → Apply →
Save → reopen), configured a transform, and deleted the scratch pipeline. Everything below is
grounded in either a file:line or a step reproduced live; hypotheses are marked as such.

## 0. Mandate

Operator request (2026-08-21): analyze the full canvas UI from a smart end user's point of view and
plan a tighter, more compact design. Two seed observations, both confirmed live:

1. **A selected parser's Properties panel carries four commands — Configure, Run to here, Connect,
   Delete — while the toolbar already carries Delete.** Delete is a duplicate; Run to here and
   Connect are commands, not properties, and belong on the toolbar; the Properties tab should carry
   the *configuration itself*.
2. **For the Delimited parser, the main point is: parse a sample → generate the schema/metadata.
   Once generated, the saved schema should load and present for edit / alter / regenerate.**

Both observations generalize. The panel duplicates the toolbar because the inspector predates the
selection-scoped toolbar Delete; the parse experience splits across three surfaces of different
vintages (drawer / grammar dialog / schema dialog), and only the newest one closes the loop.

## 1. The editor today — grounded inventory

### 1.1 Where a selected Step's actions live

- Inspector buttons: Configure · Run to here · Preview data (`sink.view` only) · Connect · Delete —
  `pipeline-inspector.component.ts:109-133`. Edge selection gets a Relationship picker + a second
  **Delete connection** button (`:149-159`).
- Toolbar: **Delete selected** (trash, `hasSelection()`-gated) inside the `canAuthor()` block —
  `pipeline-editor.component.html:194-205`. **Confirmed live: with a node selected, Delete renders
  twice on screen at once** (toolbar + panel).
- Lens gating today: the inspector hides Configure/Connect/Delete in read-only, but keeps **Run to
  here** (ungated input `canRunToHere`) and **Preview data** — deliberate; the scratch run and the
  view preview are reads (`pipeline-inspector.component.ts:186-193`).
- The "connecting from '…' — click a target Step" status already renders in the toolbar's right
  cluster (`pipeline-editor.component.html:228-231`), so the toolbar already narrates the Connect
  gesture it doesn't own yet.

### 1.2 Where configuration happens — three surfaces of different vintages

`openNodeConfig` (`pipeline-editor.component.ts:1524-1563`) routes:

| Node kind | Surface | Canvas visible while editing? |
|---|---|---|
| `acquisition`, per-format parse (`PARSE_NODE_FRONTENDS`), `transform.map` | **Definition drawer in the right dock** (Collector / Parse / Load panes; Apply = in-memory patch, D2) | yes |
| PARSE leftovers: generic `parser`, grammar-bound (`use: grammar/…`), dangling binding, binary fixed-width | `GrammarEditorDialog` popup, 1100px | no |
| Everything else (filter, route, join, summarize, dedup.record, enrichment, sinks, gap detection) | `NodeConfigDialog` popup, 680px | no |

Live observations:

- The **filter dialog is one predicate field plus two collapsed sections** — it would fit the dock
  with room to spare, while the inspector *behind* the open dialog shows the same node's name, type
  and config summary. Two surfaces, same facts, one on top of the other.
- The dialog's whole component-binding half (picker, "New <kind>", "Test <component>…") is **dead in
  production**: `bindKindFor` returns non-null only for PARSE (`pipeline-graph.ts:41-43`), and PARSE
  never reaches this dialog. Recorded in BACKLOG §6 (the 2026-08-17 rewire note).
- **Selecting another Step while a definition drawer is open does not follow the selection**: the
  dock keeps rendering the drawer's node (the template prefers `definitionNode()` over
  `selectedNode()` — `pipeline-editor.component.html:548`), so clicking Step B shows Step A's
  definition with no hint. Reproduced live.
- Adding a Step from the palette **selects it but does not open its config**, while the Recipe
  view's insert **does** (`onRecipeInsert` ends in `openNodeConfig` — `pipeline-editor.component.ts:562`).
  Inconsistent; on canvas, a "Needs config" Step costs an extra click every time.
- Name/Description placement is inconsistent across the three surfaces: the Parse pane moved them to
  the inspector's rename pencil (deferral (a), shipped `e004ae26`), the Load pane and
  `NodeConfigDialog` still ask for them inline.

### 1.3 The Delimited flow as experienced (drawer path — the good path)

Walked live: palette **Delimited** re-types the placeholder in place (parse-slot rule — good) →
Configure → drawer opens at the dock's default 300px:

- At 300px the four option tabs **truncate to "Dialect | Typ…" with scroll arrows**, and the schema
  toolbar stacks; the pane only breathes after maximize (`inspectorSplit` default 300,
  `pipeline-editor.component.html:481`).
- **Test parse is the generate-schema control, but nothing says so.** It sits *below* the tab
  panels; on success the derived schema lands **silently in the Types & columns tab** — the only
  visible feedback at the button is the parsed-rows table below it and the tiny "● unapplied" dot in
  the header. Reproduced live: pressed Test parse and the viewport showed no schema anywhere.
- The Types tab's empty state says "**Test the parse above**" — the button is *below*
  (`pipeline-parse-definition.component.ts:378-381`). Small, but it sends a first-time builder
  scrolling the wrong way.
- **The saved schema does load and present for edit** — verified live: Apply → Save → reopen
  restored Auto mode + all 4 columns from the schema toon (`loadSavedSchema`,
  `pipeline-parse-definition.component.ts:863-893`). Drift is reported with "Add N new"; type
  changes are reported, never applied. This half of the operator's ask is **already built**.
- **What's missing is *regenerate***: once hydrated, a fresh parse deliberately never re-derives
  (`onPreviewed` returns into `checkDrift` — `:663-666`), and the only wholesale replace is a CSV
  import. There is no "re-derive the schema from this sample" affordance, which is the third verb
  the operator named.

### 1.4 The Delimited flow on the dialog path (the shipped example pipeline!)

`cdr_ingest` — the one pipeline in the demo space — carries the **generic `parser`** Step, so
Configure opens `GrammarEditorDialog`, where **none of §1.3 exists**:

- The Types tab shows only bare options (the columns table is a host projection the dialog never
  mounts). The node's own `schema_file: cdr_ingest_schema.toon` is **never loaded, shown, or
  editable**. "Draft Schema…" opens a *third* stacked dialog (`SchemaEditorDialog`) that drafts a
  *new* schema rather than opening the saved one.
- The sample is dialog-local (`sampleMode: 'own'`) and never joins the tab's sample thread — already
  noted in BACKLOG §5 (BUILDER-1 follow-up observations).
- 🔴 **Grounding required before touching this** (hypothesis, not verified end-to-end): the dialog
  seeds only from `config.parsing` (`nodeParsingBlock`, `grammar-editor.dialog.ts:275-278`), but a
  legacy parser node lifts with **`csv_settings`** on the node (OKF pipelines.md; the live inspector
  showed `csv_settings: {"delimiter":",","has_header":true}`). If the engine reads `csv_settings`
  and the dialog shows `parsing` defaults, a legacy node's real dialect is invisible here — masked
  in the demo because its delimiter happens to equal the default `,`. Ground the
  lift → dialog-seed → lower → engine-read square before S5.

**This dialog path is almost certainly where the operator formed complaint #2** — the only pipeline
in the workspace behaves this way.

### 1.5 Defects found while driving (independent of any redesign)

- 🔴 **P0 — the maximized drawer clips its own Apply button off-screen.** `drawerMaximized` sets the
  right dock's width to `100%` of the flex row (`pipeline-editor.component.html:490-492`) while the
  palette aside (215px) and its handle (6px) remain `shrink-0` siblings in the same
  `overflow-hidden` row — so the row overflows by exactly the palette's width and the drawer's
  right edge is clipped. Measured live: row 962px; children 215+6+0+0+962 = 1183px; **Apply fully
  outside the viewport** with the palette open, still 13px clipped with it collapsed to the rail.
  A builder who maximizes (the pane invites it — §1.3) can neither Apply nor Discard. Shipped in U5
  (`172c9525`); invisible to unit tests; the U5 preview pass evidently ran with a different
  palette/viewport combination.
- Copy: "Test the parse above" (§1.3).
- A11y (unverified severity): the grammar editor's tab headers surfaced with **no accessible name**
  in the accessibility tree while driving (label lives in an `ng-template mat-tab-label` beside
  badge spans). Worth an axe pass on the tabbed pane.

### 1.6 What is genuinely good (kept, not churned)

Parse-slot re-typing with an honest toast · per-tab sample thread with raw→parsed→cast chips ·
status glyphs + dashed "needs attention" outlines · drift reported-not-applied · Apply/Discard with
dirty guards everywhere · refusals surfaced in the Validation dock · the read-only exports staying
outside the author gate. None of these move.

## 2. Design principles (the reasons behind every slice)

1. **The canvas is the hero** (the shell's own stated design). Anything that covers it — 680–1100px
   popups included — must justify itself. Config belongs beside the canvas, not over it.
2. **Commands and properties are different things.** Run to here / Connect / Delete act *on* the
   selection and read naturally as toolbar verbs (precedent: Delete selected already moved). The
   Properties dock should hold state you read and edit, not buttons that dispatch elsewhere.
3. **One concept, one surface.** Today "configure a parse Step" has three answers depending on the
   node's vintage. Every consolidation in this repo (collector, grammar, enrichment editors) paid
   off; finishing the job is the compaction the operator is asking for.
4. **The generate loop must be legible**: capture sample → parse → *see* the derived schema →
   edit/alter → regenerate at will. Each verb needs a visible control at the moment it applies.
5. **Ask the minimum, in one place** — identity (name/description) lives on the inspector rename,
   never re-asked inside definition panes (the Parse pane already follows this; others lag).

## 3. Slices

### S0 — fix the maximize overflow (P0 bug; ships first, independent of decisions)

Make the maximized drawer an **overlay**: when `drawerMaximized()`, the right aside gets
`absolute inset-0 z-*` over the (now `relative`) body row instead of `width:100%` beside intact
siblings — flex arithmetic can no longer clip it, and un-maximize restores the split width.
*Verify:* class-state unit pin (jsdom can't do layout) + live preview: palette open → maximize →
Apply/Discard fully visible at every viewport ≥ desktop; footer reachable with keyboard.

### S1 — selection commands move to the toolbar; the panel stops duplicating them

- Toolbar selection cluster (after the existing trash, same separator group):
  **Run to here** ▶ · **Preview data** (sink.view) · **Connect** → · **Delete** 🗑. Stable slots,
  disabled by selection kind (edge ⇒ only Delete; non-view node ⇒ Preview disabled) — matching how
  Delete-selected already behaves rather than appearing/disappearing.
- Gating stays exactly as the inspector has it today: Run to here + Preview data render outside the
  `canAuthor()` block (Business lens keeps them); Connect joins Delete inside it.
- The inspector drops Run to here / Connect / Delete *and* the edge branch's "Delete connection";
  Configure remains the panel's primary until S2/S3 absorb it. Idle-hint copy updated.
- *Why:* removes the literal duplicate; the Connect status text already lives in the toolbar, so the
  verb joins its own narration; the panel gets vertical room back for what S2 puts there.
- *Verify:* inspector + editor specs updated (readOnly matrix: Business lens sees Run-to-here/
  Preview only); preview walk: select node/edge/nothing in both lenses.

### S2 — configuration lives in the Properties dock: retire `NodeConfigDialog` for canvas kinds

Extract the dialog's working parts into a **`pipeline-config-definition` pane** hosted by the same
`inspecto-definition-drawer` (Apply = in-memory patch via the already-shared `buildConfiguredNode`
/ `splitNodeConfig`; the inline **Test this Step** rides along with the tab's `sampleRows`).
`definitionKind()` generalizes from its three-way table to category/type labels
(`pipeline-editor.component.ts:1687-1691`).

- Wave 1: `transform.filter` · `transform.route` · `transform.join` · `transform.summarize` ·
  `transform.dedup.record` · `sink.*` · `control.gap` — all pure schema-form + free-rows surfaces
  (route's unmodeled `branches` travel verbatim through the same split/build path as today).
- Wave 2 (D3): `enrichment` — the biggest pane, but the Parse pane already set the precedent for a
  drawer whose Apply performs its own companion writes before emitting the node (write + register,
  then close with the `use:` binding).
- Name/Description leave the pane (inspector rename owns identity — principle 5; also unifies the
  Load pane, which still asks).
- The dead `bindKind` half is **not carried** (D5); when the last kind migrates, the dialog is
  deleted. ⚠ Its spec's config-key list is one of the FIVE pinned OUTPUT touchpoints
  (`node-config.dialog.spec` key list) — the pin moves with the pane, never silently drops.
- *Why:* the canvas stays visible while configuring (the whole point of a canvas editor); one
  consistent Apply/Discard + dirty-guard model instead of dialog-vs-drawer semantics; the filter
  case proves the size argument (§1.2).
- *Verify:* per-kind config round-trip specs (seed → edit → Apply → node patch carries unmodeled
  keys); preview walk per kind as a builder; the five-touchpoint pin re-verified.

### S3 — fewer clicks: selection and config converge

- **Palette add opens the pane** (canvas gains the parity the Recipe insert already has).
- **Selecting an unconfigured Step opens its pane** directly; selecting a configured one shows the
  summary header + its config pane below it (post-S2 the distinction mostly dissolves — the dock
  *is* the config).
- **Selection follows into a clean drawer**: selecting Step B while A's pane is open and *clean*
  re-targets the pane to B (today it silently keeps showing A — §1.2); a *dirty* pane keeps today's
  confirm.
- *Why:* the operator's "configuration details can place on properties tab directly", completed;
  kills the select-then-Configure two-step for the most common case (a fresh Step).
- *Verify:* preview walk — add Step, land in its pane; click across Steps with clean/dirty panes.

### S4 — the parse loop becomes legible: parse → see schema → edit → regenerate

- **The primary action moves to the sample strip**: a host-owned **Parse sample** button beside the
  chips whose state it changes ("not parsed yet" → "parsed · N cols"), driving the editor's
  existing `test()` (host already holds the `@ViewChild`). The in-pane button disappears for
  `sampleMode:'host'`; `'own'` hosts (the remaining dialog) keep it. The stale "Test the parse
  above" copy becomes true instead of edited.
- **First derivation reveals the schema**: when a parse *creates* `schemaSeed` (not the
  hydrated/drift path), the host steers the editor to the Types & columns tab (small editor API:
  `showTab('types')`). Re-parses never yank the operator's tab.
- **Regenerate**: on a hydrated schema, after a successful parse, offer **"Re-derive schema from
  this sample"** beside the drift line — `confirmDestructive`, naming what it replaces (names,
  types, synonyms, column metadata); implementation = clear `schemaHydrated`, re-run the derive
  branch. CSV import stays the file-based wholesale path.
- Drawer width: opening a **tabbed** pane below ~420px transiently widens the dock to 420 (stored
  preference respected when larger) so the tabs and schema grid render whole (§1.3).
- *Why:* this is the operator's second case verbatim — generate is currently a side effect on
  another tab; loaded-for-edit already works; regenerate doesn't exist.
- *Verify:* preview walk of the full loop (capture → parse → auto-land on schema → alter a type in
  Declared → Apply → Save → reopen hydrated → re-derive after changing the sample); unit pins for
  the reveal-once rule and the destructive confirm.

### S5 — the generic `parser` joins the drawer; the grammar dialog shrinks to custody

- 🔴 **Ground first** (§1.4): how a legacy `csv_settings` node round-trips lift → seed → lower →
  engine read, and whether the dialog today shows defaults over real settings. Fix what that
  grounding finds *before* migrating.
- Then: `isDrawerParse` admits `parser` nodes whose block (or legacy keys, normalized the way
  `grammarContentAsParsingBlock` already normalizes components) maps to a built-in frontend; the
  pane opens locked to that frontend; **Apply re-types the node to `parser.<frontend>`** — the same
  normalization the palette's parse-slot rule already performs, so the graph converges on per-format
  types (B6) through ordinary editing.
- `GrammarEditorDialog` remains only for the recorded-decision cases: grammar-**bound** nodes
  (component custody), **dangling** bindings (nothing faithful to copy), **binary fixed-width**
  (P3b). Its header states that scope.
- *Why:* the shipped example pipeline — most users' first contact — currently demonstrates the worst
  path (§1.4): no schema surface, no thread, third-dialog drafting. After S5 it gets §1.3's loop.
- *Verify:* the grounding note lands in the plan/OKF; migrate, then preview-walk `cdr_ingest`
  itself: open parser → drawer with its real dialect → saved `cdr_ingest_schema.toon` hydrated and
  editable → Apply re-types → Save → lower accepted.

### S6 — polish (only after S1–S5 land)

Tab-header a11y names (axe pass on the tabbed pane) · toolbar separator/grouping audit once the
selection cluster exists · drawer-width persistence per pane kind if S4's transient widen proves
insufficient in use.

## 4. Decisions for the operator — ALL ANSWERED 2026-08-21

| # | Question | Recommendation | Operator call |
|---|---|---|---|
| D1 | Move Run to here / Connect / Preview data to the toolbar and delete the panel's Delete (+ edge "Delete connection")? | **Yes** — the operator's own case 1; gating matrix preserved (S1) | **YES** — as planned |
| D2 | Retire `NodeConfigDialog` for canvas kinds in favour of a drawer config pane? | **Yes, staged** (S2 wave 1) | **YES** |
| D3 | Does `enrichment` move too, or keep its dialog? | **Move, last** — the Parse pane precedent covers Apply-with-companion-writes; it is the biggest pane, so it goes after wave 1 proves the shell | **AMENDED — move it in the SAME pass** ("all kinds in one pass"), not after wave 1 |
| D4 | Auto-open config on palette add + on selecting an unconfigured Step; clean drawer follows selection? | **Yes to all three** (S3) — dirty panes keep the confirm | **YES to all three** |
| D5 | Delete the dialog's dead `bindKind` half with the dialog (not carry it into the pane)? | **Yes** — dead in production per BACKLOG §6; deleting dead code rides the re-home, not a drive-by | **YES** |
| D6 | "Parse sample" into the sample strip + first-derivation reveal of the Types tab? | **Yes** (S4) | **YES** |
| D7 | Add destructive "Re-derive schema from this sample" for hydrated schemas? | **Yes** — confirm names the loss (synonyms + column metadata included) | **YES** |
| D8 | Migrate generic `parser` nodes to the drawer with re-type-on-Apply? | **Yes, after the S5 grounding** — refuse if grounding shows the engine reads `csv_settings` in a way the drawer cannot preserve | **YES, after the grounding** — the refusal condition stands |
| D9 | S0 fix shape: maximized drawer as absolute overlay? | **Yes** — flex-width arithmetic against live siblings is what broke | **YES** — SHIPPED `68ac459a` |

## 5. Verification (every slice)

`npm run lint:tokens` · `tsc --noEmit` ×3 configs · `npm run test:ci` **exit code** checked ·
AOT `npm run build` · **a builder-driven preview walk of the touched flow** (the only gate that has
caught this editor's wiring bugs — BUILDER-1, U5's clipped footer) · GAUNTLET before the final
ship · `/design` gallery + `angular-ui` skill updated where a shared pattern changes ·
`graphify update .` after doc moves.

## 6. Out of scope / kept as-is

- **Recipe view** — its row-verbs idiom already matches its shape; only S3's parity note touches it.
- **Topology mode**, the guided checklist strip, tab strip, Open dialog — unchanged.
- **Edge relationship picker** stays in the dock: it *is* properties, exactly what the dock is for.
- **No backend/API changes** anywhere in S0–S4; S5 may surface a lowering question — that lands as
  its own grounded row, never "just wired".
- The recorded refusals stand: grammar-bound nodes keep the dialog (component custody), binary
  fixed-width keeps it (P3b), `parser` stays out of the palette, Apply stays in-memory (D2).

## 7. Grounding index

`pipeline-editor.component.html` (toolbar :4-259 · docks :353-683 · drawer mount :548-616) ·
`pipeline-editor.component.ts` (`openNodeConfig` :1524 · drawer lifecycle :1589-1723 · maximize
:1714-1716 · `renameSelected` :1908 · `armConnect` :1919) · `pipeline-inspector.component.ts`
(action row :109-133 · edge branch :134-159) · `node-config.dialog.ts` (+ `node-config-build.ts`) ·
`grammar-editor.dialog.ts` · `pipeline-parse-definition.component.ts` (schema seed/hydrate/drift
:509-695, :863-893 · submit :1143-1249) · `inspecto/grammar/grammar-editor.component.{ts,html}`
(tabs :237-255 · test :659) · `inspecto/definition/sample-panel.component.ts` ·
`inspecto/components/definition-drawer.component.ts` · `pipeline-graph.ts` (`bindKindFor` :41) ·
BACKLOG §5 BUILDER-1 notes (dialog sample gap) · §4 "Delimited Grammar redesign" (all four
deferrals shipped 2026-08-19) · OKF `frontend/features/{pipelines,grammar-config}.md`.
