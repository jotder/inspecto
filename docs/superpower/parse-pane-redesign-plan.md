---
type: Plan
title: Parse pane redesign — Delimited first (metadata + sample-driven authoring)
status: ACTIVE — decided 2026-09-03, not started
timestamp: 2026-09-03T00:00:00Z
---

# Parse pane redesign — Delimited first

**Sibling plan:** [`sql-transform-v1-plan.md`](sql-transform-v1-plan.md) (the transformation half).
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

## Order & risk

S1 → S2 first (they carry the reusable components every other lane inherits) → S3/S4 (independent) →
S5 (touches three panes — do it last, one move per commit) → S6. Each slice its own commit + verify pass.
🔴 The one thing that can silently corrupt config is S2: the R9 hack exists because forms in unmounted
tab bodies dropped values on save. Expansion panels avoid it by staying mounted — but *prove* it with the
S2 spec before deleting the hack, never on the assumption.
