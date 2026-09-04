---
type: Plan
title: Parse pane redesign — Delimited first (metadata + sample-driven authoring)
status: SHIPPED 2026-09-04 — S1–S5 built 2026-09-03, S6 found already shipped, reviewed R1–R12 and landed as `d012f721`; archived 2026-09-04. As-built truth: okf/frontend/features/grammar-config.md, okf/frontend/features/schema-mapping-authoring.md, okf/frontend/features/pipeline-editor.md; open follow-ons in docs/BACKLOG.md §4 (AUTHORING-REDESIGN-1).
timestamp: 2026-09-03T00:00:00Z
---

# Parse pane redesign — Delimited first

**Sibling plan:** [`sql-transform-v1-plan.md`](sql-transform-v1-plan.md) (the transformation half).
**Mockup source:** [`assets/authoring-redesign-mockup/`](assets/authoring-redesign-mockup/README.md) (archived beside this plan).
**Supersedes:** `author-schema-1-plan.md` (archived — its validation gates were the wrong lever; see the
sibling plan for what survived). **As-built truth to update when this ships:**
`okf/frontend/features/grammar-config.md` + `okf/frontend/features/schema-mapping-authoring.md`.

## Context

The operator's verdict (2026-09-03): the authoring experience must be *simpler, easier to build, less clumsy* —
a UI to play with **metadata**, tested against **sample or parsed data**, with configuration per parser
and reusable components. The current delimited Parse pane is a 4-tab `mat-tab-group` (Dialect · Types &
columns · Robustness · Files & metadata) built on `AttributeSpec.tab`, with the "R9 rule" hack (tab panels
mounted OUTSIDE the tab bodies, `[hidden]`-toggled, because MatTab instantiates content on first visit
and unvisited tabs silently dropped values on save). We start with Delimited because it is the richest
lane; json / xlsx / fixedwidth share the same shell (`grammarTabsFor`) and inherit the redesign for free.

## Operator decisions (binding — do not re-ask)

| # | Decision | Consequence |
|---|---|---|
| D1 | Name + Description come **back onto the pane** as an inline single-row edit | Must keep the 2026-08-19 property: a rename is NOT a config edit — route through the host's `renameSelected` (bypasses `applyNodePatch`), so a rename never invalidates a green test outcome. |
| D2 | The **Collection pointer moves to the Collector step** when Files & metadata dissolves | The Parse pane no longer anchors "which files this parse reads". Column metadata (description/unit/classification) moves to Transformation (sibling plan). |
| D3 | Row filter (SQL) is **removed from Parse** | Filtering is a separate `transform.filter` Step (already emits DATA + DROPPED). Nothing to build here; the lift/lower's `csv.where` shorthand keeps working for stored configs. |
| D4 | Partitioning controls move to the **Sink** pane | UI relocation only; `partitions[]` storage contract on the schema companion is untouched (two deliberate contracts — see `ingest-wrap-spi.md`). |

## Target layout (Delimited)

```
┌ Name · Description ──────────────────────── (single row, inline edit, D1) ┐
│ Sample | Parsed                              (two tabs — the ONLY tabs)   │
│   Sample: one textarea = pasted text OR uploaded-file preview             │
│           [Upload] [Paste] [Import] [Export]                              │
│   Parsed: result table, paginated 10/25/50/100 (default 10)               │
├ ▸ Dialect          (collapsible, open)  two-column property rows          │
├ ▸ Robustness       (collapsible)        two-column property rows          │
├ ▸ Types            (collapsible)        two-column property rows          │
├ ▸ Output schema    (always open)        columns table, no free-text box   │
│     ☑ Source filename column  [name: filename] (string, default ON)       │
└──────────────────────────────────────────────────────────────────────────┘
```

**Removed:** Options tab · Row filter (SQL) · Files & metadata tab · the text-box column on the schema
table · `mat-tab-group` for properties.

## Slices

### S1 — `<inspecto-property-rows>`: the reusable single-row property editor
A new shared component rendering an `AttributeSpec[]` as **one row per property**: label · control ·
**sample value** (the value the current sample resolves to, when the spec has one — e.g. the detected
delimiter) · reset-to-default. Two columns at full width (`grid-template-columns: repeat(2, 1fr)`,
collapsing to one under the drawer's narrow breakpoint). Wraps the existing `<inspecto-schema-form>`
controls/validators — it is a **layout** over the same form model, not a second form implementation, so
`value()` / `isDirty()` / `markPristine()` / `validate()` keep their contract and every host keeps
reading it unchanged.

### S2 — Collapsible sections replace tabs
`AttributeSpec.tab` is renamed `section` (one mechanical rename; the field is frontend-only). The
grammar editor renders one `<mat-expansion-panel>` per section, each hosting an `<inspecto-property-rows>`.
Because panels stay **mounted**, the R9 hack (`[hidden]` panels outside tab bodies) is deleted, along
with the "steer to first failing tab" logic — an invalid section shows a warn dot on its header and
expands on `validate()`. The count badge (values set away from default) moves to the header.
⚠ Spec trap carried over: assert on `aria-label`, never on Material's own panel roles.

### S3 — Sample | Parsed tabs
Merge "paste sample text" and "sample file preview" into ONE textarea on a **Sample** tab (typing clears
captured bytes — the xlsx `sample.b64` rule already does this; keep it). Upload / Paste / Import /
Export live above it (Import/Export = the existing Grammar CSV round-trip, `grammar-csv.ts`, unchanged).
The parsed result table moves to an adjacent **Parsed** tab with a page-size selector 10 · 25 · 50 · 100
(default 10). These two are real `mat-tab`s and are safe from R9 because neither hosts a form.

### S4 — Output schema table + filename column
Keep `<inspecto-schema-fields-editor>` (include / # / icon-only type menu / Name / Synonym / Selector);
**drop the free-text column**. Add a **"Source filename column"** checkbox, default ON, with an editable
name (default `filename`). When on, a `string` field is appended to the output schema and the engine's
`read_csv(..., filename=true)` column is selected into it. ⚠ Both ingest engines must emit it identically
(Java path + DuckDB path — the all-VARCHAR parity rule), and `FILENAME_DATE` already assumes a filename
column exists — reuse whatever it reads today rather than adding a second source of the name.

### S5 — Dissolve Files & metadata (D2, D4)
- Collection pointer → the Collector step's pane (`collector-config.md` surface). The Parse pane reads it
  from the upstream node for display only.
- `<inspecto-schema-metadata-grid>` (description / unit / classification) → the Transformation pane
  (sibling plan, its v1 "output schema" table gains these three columns). Until that ships, keep the grid
  reachable behind an "Column metadata…" button so nothing is lost mid-migration.
- Partitioning → Sink pane. Pure move of the existing `partitions[]` UI; storage untouched.
- Delete the Options tab and the Row filter field (D3).

### S6 — Name + Description row (D1)
Re-add the row at the top of the pane, bound to the node's identity, **saving through
`renameSelected`**, never through the config patch. Spec: rename → test outcome stays green.

**Already satisfied — shipped 2026-08-22, found during the 2026-09-03 implementation pass.** The row
lives one layer up from where this plan's diagram put it: `pipeline-editor.component.html` renders
`<app-pipeline-inspector [compact]="true" (rename)="renameNode(dn, $event)" />` directly above whichever
definition pane is projected (Parse included), so every node type — not just Parse — already gets this
row from the shared drawer inspector, not from inside each pane's own markup.
`PipelineEditorComponent.renameSelected()`/`renameNode()` (`pipeline-editor.component.ts`) route through
`applyNodePatchInModel`, explicitly bypassing `applyNodePatch`, with the D1 rationale in an inline
comment. The exact spec this section calls for already exists:
`'renameSelected patches name/description into the model without invalidating a test outcome'`
(`pipeline-editor.component.ts:2401`). `pipeline-parse-definition.component.ts:186` already carries a
comment explaining Name/Description deliberately live on the drawer's inspector, not the pane. **No new
code was written for S6** — adding a second Name/Description editor inside the Parse pane would have
created a conflicting identity editor.

## Verification (per slice, per `build-verify`)

- vitest specs: (S1) property rows render one row per spec + sample value + reset; (S2) a value typed in
  a collapsed section survives `value()` and save — *the exact defect R9 existed to prevent, now proven
  without the hack*; (S3) typing clears captured bytes, page size persists; (S4) filename toggle appends
  exactly one `string` field and a lower/lift round-trip keeps it; (S6) rename leaves the test outcome
  untouched.
- Backend: one round-trip test proving a stored delimited grammar lifts into the new pane and lowers
  byte-identical (the redesign is a UI reshaping — the config contract must not move).
- Browser preview walkthrough on the real ControlApi (`npm start` on :4204): author a delimited parse
  from a pasted sample end-to-end, save, reopen, confirm every section re-seeds.

## Review of the 2026-09-03 build (operator `/goal`, 2026-09-04) — simplify, un-hack, un-gate

Driven on the real ControlApi (`csv_example`, delimited, drawer pane). What the operator saw and what
the code says caused it:

| # | Observed on the pane | Cause | Decision |
|---|---|---|---|
| R1 | Dialect section renders "Delimiter", "First line is a header", then **seven separate "Optional settings (1)" collapsed toggles** and a gear icon | S1's `<inspecto-property-rows>` mounts EVERY property in its OWN `<inspecto-schema-form>`, so each optional/advanced spec gets its own tier collapsible; cross-spec `dependsOn` also breaks | **Delete `<inspecto-property-rows>`.** One `<inspecto-schema-form>` per section, rendered FLAT (new `flat` input: no Optional/Advanced disclosure, tier order kept). Expansion panels stay — the S2 mounting proof holds for them. |
| R2 | A dead "—" column and a reset arrow on every row | `sampleValues` is never supplied by any host; reset duplicates "type the default" | Both go with R1. No sample column, no per-row reset. |
| R3 | Two cramped columns in a ~300px drawer; labels truncate ("Deli…"), a toggle label wraps to 4 lines | `sm:grid-cols-2` keys on the VIEWPORT, not the drawer's width | **Single column** everywhere in the drawer — same as Sink and Map. |
| R4 | Suggested values shown as grey placeholders that write nothing (quote `"`, null `NULL`, …) | `placeholder` without `default` | A suggested value that IS the engine default becomes a real `default` (written to the key). A placeholder that is NOT the engine default (would change parsing if written) is dropped from the field and kept only as `help` — grounded per key against `PipelineConfigParser` before deciding; see the defaults table below. |
| R5 | "Files & MetaData" still a section; "Row filter (SQL)" still a property | D2/D3 were decided but `parsing-attributes.ts` still declares `delimited__where` and a `files` section (`compression`), and the pane still projects `[tabFiles]` | Remove `delimited__where`. Remove the `files` section for every format: `compression`/`encoding` move to `dialect`; the filename-column checkbox and "Column metadata…" move under **Output schema** (the target layout in this plan). Stale copy ("set on the Files & metadata tab") fixed. |
| R6 | Sink pane has its own "Additional config" chevron and tier disclosures; Parse has "GRAMMAR" + "OPTIONS" double header | Each pane grew its own disclosure idiom | Sink schema-forms go `flat`; "Additional config" renders flat (no chevron) when it has rows; Parse keeps one "Grammar" header. Map already has no disclosures. One idiom across Parser · Map · Sink: uppercase section header → fields, single column. |
| R7 | (asked) "no separate grammar editor for delimited" | `openNodeConfig` already routes every drawer-capable delimited node to the drawer; `GrammarEditorDialog` remains ONLY for a grammar-BOUND (`use: grammar/x`) node, a dangling binding, or binary fixed-width | Left as is this pass — the visible behaviour for delimited already matches; removing the dialog is a Components-registry decision, flagged in BACKLOG, not made here. |
| R4 detail | Grounded 2026-09-04 against `PipelineConfigParser.java:955-1043` + `DuckDbCsvIngester.java` (every option is emitted to `read_csv` ONLY when set — "absent" = DuckDB's own default) | | **Becomes a real `default` (writing it is a no-op):** `quote` `"` · `encoding` `utf-8` · `compression` `auto` · `skip_header_lines`/`skip_junk_lines`/`skip_tail_lines`/`skip_tail_columns` `0` · `store_rejects` `true` · `ignore_errors` `true` · `rejects_table` `reject_errors` · `rejects_scan` `reject_scans` · `filter_target_column` `0`. **Suggestion moves to `help`, NO default (writing it changes parsing):** `comment` (absent = no comment skipping; `#` would newly drop lines) · `date_formats`/`timestamp_formats` (absent = `TRY_CAST`, flexible; a list RESTRICTS to `TRY_STRPTIME` of exactly those) · `null_strings` (absent = only `""` is null; `NULL` would add a marker). **Left alone, deliberately:** `escape` (engine auto-fills escape = quote — an explicit `"` would diverge the moment `quote` changes) · `strict_mode` (tri-state, unset = DuckDB's) · `engine` (blank = `auto`, recorded code decision) · `rejects_limit` (no natural value). **Per-frontend default:** `null_padding` = `padsShortRowsByDefault` (`false` delimited, `true` line readers) — the shared spec already took that flag for its help text, so the real default follows the same switch. |
| R9 | Parse pane suggested the filename column as `filename`; the Sink help, the engine attribute and the scaffold all say `file_name` | S4 followed this plan's literal `filename` | The codebase's one name wins: `file_name` everywhere (Parse pane placeholder + checkbox default reverted). This plan's S4 text is corrected by this row. |
| R10 (operator, 2026-09-04) | Stacked floating-label fields with help text under each one still made the pane long | Material's default field density and per-field `mat-hint` | **Compact property rows in `flat` mode**: label · value · pencil on ONE line (~32px); help becomes an info-icon tooltip; a row shows its current value (the real default for an untouched grammar) as text and the pencil opens the dense inline control (booleans keep their toggle); **two columns when the form's own measured width ≥ 560px** (ResizeObserver — the drawer's "Full width" button makes the host wide; Tailwind `sm:` prefixes key on the viewport and are wrong here). Non-flat path (jobs/alert dialogs) untouched. |
| R11 (operator, 2026-09-04) | "Output schema" AND "Column metadata…" on the Parse pane — two tables over the same `raw.fields[]` rows | The S5 hedge kept the metadata grid reachable "until Transform takes it" | **One table, the mockup's "Columns that come out"**: Use · # · Name · Type · Sample value · Also known as, a search box, and the filename checkbox appending a visible `file_name` row. The metadata grid leaves the Parse pane (D2: description/unit/classification belong to Transform). ⚠ The save path merged those three keys FROM the grid — without it a hydrated schema's metadata would be dropped on Apply; the carry-through now comes from the loaded schema by selector, invisible here, until the Transform pane edits them. Selector column hidden for positional frontends (`#` IS the selector); the Auto/Declared toggle + "Apply suggested types" chip become one Types-section property ("Detect column types") and rows seed from the detected types. |
| R12 (operator, 2026-09-04) | Property labels were engine keys ("Skip junk lines (adaptive)", "Rejects scan table"), not the approved mockup's language | The 2026-09-03 build relabelled nothing | Sections and rows take the mockup's vocabulary wherever a real key exists: **How the file is written** (Column separator · Text quote · Escape character · First row is the header · Skip lines at the top · Ignore lines starting with · Text encoding · Compression), **How values are understood** (Detect column types · Date formats to try · Timestamp formats to try · Source time zone · Words that mean "no value"), **When a row looks wrong** (Rows that cannot be read → review bin · Fill missing columns with empty · Keep rejected rows for review · Stop after this many bad rows · …). Mockup rows with NO engine key (decimal/thousands separator, "an empty cell means", "words for yes/no", extra-columns handling) are NOT built — nothing is faked. The "sample resolves to" line under each row is NOT wired (no host resolves a sample against a spec today) — deferred, stated. | <!-- vocab-allow: quotes the existing UI label "Source time zone" (the data-origin zone sense, not the acquisition entity) -->
| R8 | "Source filename column" checkbox default | The plan's S4 said default ON; the real mechanism is the SINK's `output.filename_column` (write-time), so ON would write a new column into every saved pipeline | Stays default OFF (only writes when the author asks). Flagged, not silently changed. |

## Order & risk

S1 → S2 first (they carry the reusable components every other lane inherits) → S3/S4 (independent) →
S5 (touches three panes — do it last, one move per commit) → S6. Each slice its own commit + verify pass.
🔴 The one thing that can silently corrupt config is S2: the R9 hack exists because forms in unmounted
tab bodies dropped values on save. Expansion panels avoid it by staying mounted — but *prove* it with the
S2 spec before deleting the hack, never on the assumption.
