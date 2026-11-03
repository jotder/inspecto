# One form idiom: property rows (`UI-FORM-ROWS-1`)

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §5). P0 and P1 BUILT 2026-11-03 (§7, §8).** Operator ask: *"we need uniform look and feel
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

## 5. Decisions (operator 2026-11-03: accepted as recommended)

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

## 7. As built: P0 (2026-11-03)

- **One row geometry** (Q-1): `styles.scss` `.prop-row` / `.prop-label`. The label sits at the left in `text-sm`
  (line-height set, since the app's `text-sm` carries none), the value right after it, and ✎ / ▾ at the right edge.
  `pen-field` (whose label was `text-xs`) and `option-picker` (whose value was pushed right, max 70%) both use it now.
  The option-picker spec that pinned the old right-aligned layout now pins the shared one.
- **`help`** on both components (pen-field's came with the Data page change).
- **Guard**: `inspecto-ui/tools/check-form-idiom.mjs` (`npm run lint:forms`) runs in the `ci.yml` guards job, and so
  in the pre-commit hook, with `check-form-idiom.test.mjs`. It refuses a `<mat-form-field>` / `<mat-select>` in a file
  not on `tools/form-idiom-allowlist.txt`, and a listed file that no longer holds one, so the list only shrinks. The
  list was seeded with 127 files; this phase took off the `/design` demo and two files that only named a tag in a comment (the guard now ignores comments): 124. `form-allow` on a tag's
  line marks a deliberate exception.
- **Rulebook and gallery**: `.claude/skills/angular-ui/SKILL.md` carries the row, fold and wizard rules and the guard.
  The `/design` "Reactive form" demo is now "Form: property rows" (a pen-field and a picker with help, two per line).
- **Moved to P1**: the boolean toggle row. Its only shared form is `schema-form`'s, which P1 converts.
- Checks: full UI suite 7156 passed; the 5 failures are the known, older `add-data.page.spec.ts` ones. Typecheck,
  eslint, `lint:tokens` and `lint:forms` are clean, and both apps build. Seen on Basic: the Data page Mapping form's
  value and choice rows now line up. A long one-word label now breaks inside its 45% (`overflow-wrap`) instead of
  running under the value (seen in `explore-controls`, which also overlapped before).

## 8. As built: P1 (2026-11-03)

- **`schema-form` draws only property rows.** The tiered (default) mode now renders through the same row template the
  `[flat]` mode used since 2026-09-04, so its 34 hosts get rows without a change of their own. The old boxed template
  (256 lines) is gone.
  - Required rows are shown. *Optional settings (n)* and *Advanced settings (n)* are named folds; the advanced gear
    is gone. A fold says how many of its shown fields need fixing (touched and invalid), and `validate()` still opens
    a fold that holds an error.
  - Rows go two per line once the host is at least 560 px wide, in both modes (the `ResizeObserver` was flat-only).
  - Focus lands on the first row's control (`cdkFocusInitial`).
- **Features the boxed template had, moved into the row** so no host loses one: suggestions on a list's draft (an
  `optionLoader`), the *Explicitly none* switch for an optional empty list, and a text input plus the token picker for
  a number the host offers tokens for.
- **Help is the tiny line under a row** in both modes, replacing the info-icon tooltip. An error line replaces it.
- **A required choice row shows its error after `validate()`.** The picker's own required line appears only once its
  dialog was opened, so the row draws it and the picker no longer gets `[required]`.
- **Spec support**: `inspecto/testing/schema-form.ts` (`sfOpen`, `sfRow`, `sfHelp`). Five host specs now open a row
  before reading its control and read errors from the row's alert line: schema-form, alert-rule-form, job-form,
  pipeline-collection-definition and risk-score-form. Three new schema-form tests cover the folds, the fold's "to
  fix" count and the choice-row error.
- **Not converted**: the inline editor that opens in a row is still a dense outlined Material field (`.sf-dense`), so
  `schema-form.component.ts` stays on the `lint:forms` allow-list (124). Moving that editor onto pen-field needs
  suggestions and a suffix slot on pen-field; it is left for when a screen needs it.
- Checks (before the verification fixes): full UI suite 7159 passed; the 5 failures are the known, older `add-data.page.spec.ts` ones. Typecheck,
  eslint, `lint:tokens` and `lint:forms` are clean, and both apps build. **Not seen on screen**: the Basic install
  reaches no schema-form screen (Value Measures, templates and index builds are Standard), and the console app was not
  served from this lane.
- **Verification round 1 found five regressions; four are fixed:**
  1. A token picked from the picker's menu never landed: the menu took focus, the row closed itself 200 ms later
     and took the menu with it. Focus inside a CDK overlay pane that does not hold the form (the token menu, an
     autocomplete panel) now counts as still in the row. A dialog is an overlay pane too, so it is excluded by that
     rule: in a dialog, focus leaving the row still closes it (round 2 caught the first cut keeping every dialog row open).
  2. In two columns, help and error lines were their own grid cells and scrambled the layout. A row and its lines now
     share one cell (`data-cell-for`), and the lines are indented under the value only in one column.
  3. A host-disabled field (an immutable id on edit) looked editable. Its row now has no pencil and a disabled value.
  4. Help and error lines were not tied to the control. The value button, the inputs, the list draft and the toggle
     now carry `aria-describedby` pointing to the line shown. `aria-invalid` stays with MatInput, whose own binding wins;
     the choice picker exposes no describedby input, so a choice row is described only by its error once it shows one.
  5. Partly fixed: Enter, Escape or a picked suggestion closes the row and hands focus back to the row's value, not
     the page. The refocus is deferred, because the browser's Enter-to-submit needs the input still in the form, and it
     is skipped when another row was opened meanwhile.
     Still open and lower: a closed list row shows "—" for both "unset" and "explicitly none"; a placeholder shows
     only in an open row; a multiline or list editor is narrow in two columns.
