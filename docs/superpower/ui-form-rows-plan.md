# One form idiom: property rows (`UI-FORM-ROWS-1`)

> **PLAN 2026-11-03 — decisions Q-1…Q-5 open (recommendations in §5).** Operator ask: *"we need uniform look and feel
> for the form items"* - single-line field / value rows with a pencil to edit (as elsewhere), a short description in
> tiny type under a field whose name does not explain itself, the whole form at one glance without scrolling (fold
> groups or a wizard for long ones), and every dropdown a popup dialog that can carry hints, search, groups and nested
> options. Board row `UI-FORM-ROWS-1`.

## 1. Where we are (counted on master 2026-11-03, `inspecto-ui/src` + `projects`, specs excluded; files, not uses)

| Piece | What it is | Files using it |
|---|---|---|
| `<inspecto-pen-field>` | one typed value as a row: label · value · ✎ | 25 |
| `<inspecto-option-picker>` | one choice as a row: label · value ▾, the choices in a dialog (hint per option, group headings, search from 8) | 71 |
| `<inspecto-schema-form>` | a spec-driven form with required / optional / advanced tiers | 34 |
| `mat-form-field` (boxed Material field) | the older idiom | **127** holding the tag (114 importing the module) |
| `mat-select` | the older dropdown; the rulebook already bans it for a single choice | **32** holding the tag (22 importing the module) |

The idiom is already the house rule (`.claude/skills/angular-ui/SKILL.md`: pen-field for a value, option-picker for a
choice, "read as ONE idiom"), but two gaps keep the look from being uniform:

- **The two row components do not match.** `pen-field` shows its label in `text-xs` and the value right after it;
  `option-picker` shows its label in `text-sm` and pushes the value to the right edge. Side by side (the Data page's
  Mapping form, 2026-11-03) they read as two styles.
- **`schema-form` itself still draws 9 boxed Material fields**, so its 34 screens are half rows, half boxes.

Done in this lane (the first screen converted): the la-app **Data page** - its Entities card is one picker row per
column, and the Mapping form is property rows two per line with a tiny help line under the unusual fields (`help`, new
on `pen-field`, the same line `option-picker` already had). Fixed on the way: a column's "holds" saved from the Entities
card was followed by a reload served from the read cache, so the row could snap back
(`DatasetColumnsService` now clears `ShortReads`, as every Dataset write does).

## 2. The target

1. **One row geometry** for every value and every choice: the label at the left in one size, the value after it, the
   affordance at the right edge (✎ for a typed value, ▾ for a choice, a toggle for a yes/no). The help line, when there
   is one, sits under the row in `text-xs`. Errors replace the help line, as both components already do.
2. **The whole form at one glance.** Rows go two per line on a wide screen. A form that still does not fit folds into
   named groups (each closed group says how many fields it holds and whether one is unset or wrong); a form whose steps
   depend on each other is a wizard.
3. **Every choice is a dialog**: full labels, a hint per option, search from 8 options, group headings - and, new,
   **nested options** (a parent that opens to its children) and a per-picker layout (a two-column list, or a short
   description panel) when the items need it.
4. **A guard** so the old idiom does not come back: a lint step, like `lint:tokens`, that fails on a new
   `mat-form-field` / `mat-select` outside an allow-list that only shrinks.

## 3. Design notes

- **Row geometry** lives in ONE place - a shared stylesheet class set (`.prop-row`, `.prop-label`, `.prop-value`,
  `.prop-help`) that `pen-field`, `option-picker`, the boolean toggle row and `schema-form` all use, so a later change is
  one edit. The `.sf-*` geometry `schema-form` already uses for definition panes is the starting point.
- **Fold groups**: a small shared `<inspecto-form-section title count [open]>` (a `<details>`-like disclosure with a
  summary line), used by `schema-form` for its optional / advanced tiers and by hand-built forms. ⚠ An error must open
  its section - `schema-form` already does this for its tiers (2026-08-30); the shared section must keep that rule.
- **Wizard**: only where steps depend on each other (Add data already is one). Not a goal in itself: a long form of
  independent fields is folded, not split into steps.
- **Where boxed fields stay**: code and SQL editors, multi-line JSON, grid filter boxes and search fields above a list.
  They are not "a value of a record" and keep their own controls.

## 4. Phases

| Phase | What | Size |
|---|---|---|
| **P0** | one row geometry for `pen-field` and `option-picker` (and the toggle row); `help` on both; the `lint:forms` guard with today's allow-list; the rule in the rulebook and the `/design` gallery | S |
| **P1** | `schema-form` draws only rows (its 9 boxed fields), its tiers as `form-section` folds - 34 screens at once | M |
| **P2** | the Link Analysis panes (la-app, link-analysis library) still using boxed fields | M |
| **P3** | the admin panes, in batches by use, each batch its own commit; the allow-list shrinks with each | L |
| **P4** | nested options and per-picker layout in `option-picker`, built when the first screen needs them | S |

## 5. Decisions (recommendations; the operator decides)

- **Q-1 Row geometry.** (a) label left in `text-sm`, value right after it, affordance at the right edge ⟵
  *recommended* (the label reads first, the value sits where the eye goes next, and the edit control lines up down the
  form); (b) the picker's current geometry everywhere (value right-aligned); (c) the pen-field's current geometry
  everywhere (label `text-xs`).
- **Q-2 When a form folds.** (a) flat up to about 12 rows (6 lines at two per line); beyond that, named groups with the
  required ones open ⟵ *recommended*; (b) always group by tier, whatever the length.
- **Q-3 Wizard.** (a) only when a step's choices depend on an earlier step's answer ⟵ *recommended*; (b) for any form
  over N fields.
- **Q-4 Rollout.** (a) P0 then P1 (`schema-form`, the biggest lever), then the Link Analysis panes, then admin panes by
  use, with the guard from P0 ⟵ *recommended*; (b) one big sweep.
- **Q-5 Nested options.** (a) build when the first screen needs them (P4) ⟵ *recommended*; (b) build in P0.

## 6. Not in scope

Vendored gamma / Fuse screens (`src/@gamma/**`, `modules/auth/**`); code, SQL and JSON editors; grid filters and search
boxes; colours and typography beyond the row (the design tokens stay as they are).
