# One form idiom: property rows (`UI-FORM-ROWS-1`)

> **PLAN 2026-11-03 — decisions Q-1…Q-5 accepted by the operator 2026-11-03 (the recommended options, §5). P0 and P1 BUILT 2026-11-03 (§7, §8); P2 BUILT 2026-11-04 (§9); P3 BUILT 2026-11-04 in seven batches (§10) - the allow-list holds only `schema-form`; P4 open.** Operator ask: *"we need uniform look and feel
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
| **P4** | nested options and per-picker layout in `option-picker`, built when the first screen needs them; a disabled option with its reason (the model provider list, P3 batch 4) | S |

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

## 9. As built: P2 (2026-11-04)

- **The Link Analysis library holds no unmarked boxed field.** Its 10 files are off the `lint:forms` allow-list
  (124 to 114). la-app had none left after the Data page.
  - Rows (`pen-field`): the geo toolbox's radius, window and dwell; the geo map's radius search and note; both
    *Save view as* forms (geo map and graph); the Entity List dialogs (title, reason, expiry); the snapshot and
    attach-to-Case dialogs (titles, description); the Open dialog's rename; the preset query parameters; and the
    template dialogs (title, id, Alert Rule name, threshold).
  - Pickers (`option-picker`): the Open dialog's five `mat-select` filters (owner, show, Dataset, sort, changed). Its
    *Changed* filter now holds a string (`'0'`, `'7'`, ...) read through `Number`, because a picker's values are strings.
  - Signal-bound toolbar numbers moved to small reactive forms (`params` on the toolbox, `toolForm` on the geo map;
    `searchRadiusM` is now a `toSignal` of its control), since `pen-field` is a `ControlValueAccessor`.
- **Kept boxed, each marked `form-allow` with its reason:** live search boxes (points, places, nodes, the Open
  dialog's search and tag), the snapshot dialog's node filter, the start box's value typeahead, and two kinds the plan
  had not named: a **message composer** (Comments) and a **many-line paste box** (Entity List entries, the start
  box's pasted values). Both are text you write or paste, not one value of a record. The rulebook now lists them.
- **`pen-field` gains `type="datetime-local"`** (the Entity List expiry). It opens on Enter, Space or a click, never
  on one typed key, as `date` does.
- Error lines: each host computes its row's line from `touched` (as the Data page does), so nothing shows red before
  the field was left or the form submitted.
- Behaviour to know: Enter in an open row commits that row only; it no longer submits the surrounding form (Save
  view, Rename, the Entity List dialogs). The form's button does. Escape closes only the row, never the menu or
  dialog around it (checked on Basic, la-mobile-money: *Save view as* menu, Open dialog filters, Edit entries).
- Still open and lower: a closed *Expires* row shows the raw `2026-11-04T10:00`; the blank filter choices ("Anyone",
  "Any") draw in the picker's grey placeholder tone.

## 10. As built: P3 (admin panes, in batches)

**Batch 1 - Objects (Incidents and Cases), 2026-11-04.** The 11 files under `modules/admin/objects/` leave the
allow-list (114 to 103).
- Rows: the Case Rule and Tag Rule dialogs (two per line), New tag (the tag dialog and the mail view), Resolution
  (a multiline note), Split case, the Impact panel's amounts, currency, period and basis, the Create dialog's title,
  description and SLA, the attachment name and URI, every Postmortem field (dates stay text, YYYY-MM-DD, so a loose
  stored value is not lost), and the Findings spec editor (its new choice is focused through the row's `focus()`).
- Kept boxed with `form-allow`: the assignee and tag typeaheads, the Tag Rule's *Apply tag* (a typeahead over the tag
  registry), the linked-entities **multiple** choice (not one value) and the Comments composer.
- Behaviour to know: Enter in a row commits the row; the Attachments form submits from its button. The Findings
  editor's preview follows each committed row, not each keystroke. A button that is disabled until a value exists
  (the tag dialog's +, the Findings Save) still takes ONE mouse click while the row is open: the click's mousedown
  blurs the row, which commits it and enables the button before the click lands (checked in Chromium). Tab from an
  open row skips such a button; Enter in the row covers the keyboard.
- **`pen-field` gains `(entered)`**, fired after Enter commits an open row. New tag (the tag dialog and the mail
  view) and Case type act on it. A host `(keydown.enter)` was wrong here: the Enter that only OPENS a closed row
  bubbles to the host too, so it created the tag or showed "Name the Case type." on opening (verification finding).
- **`cdkFocusInitial` goes on the `<inspecto-pen-field>` host**: the CDK focus trap moves it to the host's first
  tabbable child, the row's value. The first cut dropped it from six dialogs and focus landed elsewhere (a checkbox,
  a saved rule); it is back on Rule name, Title, Resolution, New case title and New tag.
- Lost and lower: the Impact amounts no longer bring up a decimal keypad (`inputmode`), and a row trims its text, so
  a stored search text, category or pattern loses leading or trailing spaces.

**Batch 2 - Studio (dashboards, Datasets, queries, templates, widgets), 2026-11-04.** The 15 files under
`modules/admin/studio/` leave the allow-list (103 to 88).
- Rows: the dashboard id, description and *As of*; the Dataset id, physical reference and the bind / materialize /
  calculated / measure names and labels; the query name, description and each `$param` default; the template id
  prefix; the new dashboard id and the widget id and description.
- Pickers: the dashboard date-range preset, and one per exposed field in the dashboard filter bar. A re-pick of a value
  whose filter is already on does nothing, as the old select did (the picker reports a re-pick; a first cut toggled the
  filter off).
- Kept boxed with `form-allow`: the date-range day, from and to (live date filters with a calendar panel and a max
  day), the dashboard date-field typeahead, two **multiple** selects (exposed fields, the explore field list), the share
  link (a read-only copy box), the SQL expression editors, and the Datasets columns table's *Role* select (a
  table-cell select, which the rulebook keeps a dropdown).
- **`[ngModel]` as a one-control bridge**: the date-range picker and the query `$param` rows bind a signal to the row
  through `[ngModel]` / `(ngModelChange)` (standalone), as the filter bar already did. A reactive control would need a
  second, synced copy of the signal. The rulebook now names this the one sanctioned use of `ngModel`.
- Behaviour to know: the query parameter defaults, calculated and measure names, column label and format and the
  dashboard *As of* now update per committed row, not per keystroke; Enter in a row no longer submits the query, the
  materialize, apply-template, add-to-dashboard or widget-save forms (their buttons do). The filter bar picker is named
  by the field alone (it was "Filter by <field>").

**Batch 3 - Pipelines, 2026-11-04.** The 12 files under `modules/admin/pipelines/` leave the allow-list (88 to 76).
- Rows: the change-id, duplicate, rename and template dialogs, the six Pipeline settings, the new Pipeline name (it
  still creates on Enter, through `(entered)`), the inspector's node name (compact mode emits the rename when the row
  commits), the Enrichment name, the parse step's filename column (an `[ngModel]` bridge: it is bound to the host's
  target, not a form) and the extra config's new key; the new key's type is a picker.
- Kept boxed with `form-allow`: the dry-run's JSON sample rows, the combined view's **multiple** Pipeline choice, and
  the extra config's in-row editor. That editor is the same dense field as `schema-form`'s rows, which P1 kept on
  purpose; converting it alone would put two row layouts in one list. It moves when `schema-form`'s editor does.
- Behaviour to know: Enter no longer submits the change-id, duplicate, rename, template, settings or inspector rename
  forms (their buttons do). Per commit, not per keystroke: the change-id confirm (its button enables when the row
  commits), the settings warning about a missing order-by column, the extra config's Add and duplicate check, and the
  Enrichment name's dirtiness (a `valueChanges` subscription re-derives it; without it Apply stayed grey after a Tab).
  An unnamed compact node shows its id as a grey placeholder. The confirm field lost `autocomplete="off"`.

**Batch 4 - Access, Model Settings, Settings, 2026-11-04.** The 11 files under `modules/admin/access/`,
`model-settings/` and `settings/` leave the allow-list (76 to 65).
- Rows: the access policy route and resource kind, the policy and role names, the role's data scopes, the Model
  Settings URLs, API-key variable name, tier models and timeout (two per line), the approver roster and egress entries
  (both still add on Enter, through `(entered)`), the new exclusion category list, every Incident governance field
  (labels shortened, the parenthetical moved to `help`), the operational database URL and user, and the scheduler's
  limits (two per line; the in-force cap stays a placeholder, never a `default`, so a blank row never writes the
  inherited value into `scheduler.toon`). Pickers: the model provider (grouped Hosted / Local) and the exclusion
  category's Entity Type.
- **Secrets stay boxed**: the Model Settings API key (masked) and the operational database's password reference. A row
  would show its value as plain text when closed.
- Also kept boxed: the access lens search box and the policy condition (an expression editor).
- Behaviour to know: Enter no longer submits the explain, policy, role, exclusion, governance, test-connection or
  scheduler forms. The governance state diagram follows each committed state. The model provider picker lists only the
  providers this server can run, and the alert says the others are left out (`mat-select` greyed them; the picker has
  no disabled option - a P4 candidate). A re-pick of the current provider is ignored, so it never resets the edited
  form. Model Settings keeps its existing `[(ngModel)]` bindings (it has no form; moved, not added).

**Batch 5 - the shared `inspecto/` library, 2026-11-04.** 18 files leave the allow-list (65 to 47); only
`schema-form.component.ts` stays, for its in-row editor (P1).
- Rows and pickers: the connection form (type, auth method, proxy type and every per-type attribute; the bastion and
  proxy hosts, ports and users; the saved name and description), the enrichment editor's reference cards (alias, file
  path, as-of column; bind, published Reference, format), the reconciliation toolbar (day and show pickers, sample
  keys, the tolerance bands), the segment key, the offer-share description, the type-to-confirm box of
  `confirmDestructive` (the exact text must still match before the button enables), the rule id and parameters, the
  six Space form fields, and New tag in tag assignment (creates on Enter, through `(entered)`).
- Kept boxed with `form-allow`: the cells of repeating-row editors (query condition rows, schema fields and
  partitions, grammar slices, segment columns) and grid-toolbar selects, which the rulebook keeps as dropdowns; the AI
  prompt boxes (message composers); the data-table and filter-bar search; the grammar sample text; the import bundle's
  per-row action; and the connection form's secret references (`${ENV:...}` values, never shown in a closed row).
- **Editors tell their host they changed.** The enrichment and segments editors gain a `changed` output
  (`outputFromObservable` over their form's `valueChanges`). The Pipeline config and parse panes derived "dirty" from
  `input` / `click` events, and a row committed by Tab or Enter fires neither, so Apply could stay grey; they now
  bind `(changed)="emitDirty()"`. A test proves it (it fails without the binding).
- Re-picks are ignored where the old select never reported one: the connection type (a rebuild would wipe the entered
  attributes), the reconciliation day and filter.
- Behaviour to know: Enter no longer submits the connection form's steps, the tolerance bands, the rule id or the Space
  form. Per commit, not per keystroke: the type-to-confirm match, the Space form's Create button and its id-from-name
  sync (a committed id stops the sync, through `valueChanges`), the enrichment editor's views and dirty state. The
  connection type's description is now its option hint. The confirm box and Space form lost `autocomplete="off"`.
- Known and lower: a reactive picker marks its form dirty when its current value is picked again (bind, format, auth
  method); the picker reports every pick, which the dashboard filter bar relies on, so a host that must ignore a re-pick
  guards it (connection type, reconciliation day and filter, model provider).

**Batch 6 - Catalog, Components, Reconciliation, Risk Scores, 2026-11-04.** 10 files leave the allow-list (47 to 37).
- Rows: the lineage toolbar (from node, depth, node and edge kinds; direction is a picker), grant governance (version,
  expiry), request-share purpose, the onboarding dialog (name, description, inbox and output directories), the
  component form (id, skip lines, quote, escape, encoding, store), the mapping and schema editor ids, the break
  assignee, the reconciliation name and bands, and the Risk Score factor card (id, label, measure, weight, cap).
- Kept boxed with `form-allow`: the grammar **Delimiter** (a row trims, so a tab or space delimiter would save as
  ","), the component config JSON, partitions, sample boxes, the reconciliation key columns and the factor evidence
  columns (**multiple** choices), and the cells of the compare-row and factor filter-row editors.
- **A server refusal that names a field marks its row.** `setRowError` sets `{ server: true }` on the field and puts
  the server's message on the card; the old boxed field showed only a red outline, which a row lacks. The row now
  reads "Refused: see the message above." until an edit of that row clears it; a local error on the same field wins.
  A remove or move drops these marks with the positional card messages they point at.
- **Every validator needs its line** (verification finding). A row has no red outline, so a validator with no message
  makes Save refuse with nothing on screen: the factor Id's pattern and a cleared Weight did exactly that. The card's
  `fieldError` now takes every message of a field (first failing wins); new wording where the old field had only the
  outline: "Id may hold only letters, digits and _", "Weight is required", and the reconciliation bands' "A percentage
  is required.".
- Behaviour to know: Enter no longer submits the onboarding, component, assign-break or reconciliation forms. Per
  commit, not per keystroke: the onboarding directory defaults and import notes, the component form's value for its
  id and grammar rows, the reconciliation name and bands (each commit resets the test result). Weight and cap lost the
  decimal keypad.

**Batch 7 - the remaining 36 admin and layout files, 2026-11-04.** The allow-list ends at ONE file (37 to 1):
`schema-form.component.ts`, for its in-row editor (P1).
- Rows: the alert, decision, expectation and Job ids (each with its required / pattern / duplicate line), the anomaly
  model features (id, label, measure, weight, spread floor, filter value), the action-request, link-expansion,
  pending-change, report and screening reasons, the config path, the tile server URL, "Save current as" in Events
  (saves on Enter, through `(entered)`), the menu names, the template id, the requirement and decision notes, every
  runbook field (each step is a card, not a table cell), the reprocess batch id and the tag rename (both act on Enter,
  through `(entered)`), the screening threshold and match limit, the import and template-gallery Space ids.
- Kept boxed with `form-allow`: the global search and the icon / screen / delivered-via / processor typeaheads, the
  agent chat and the regulatory report narrative (composers), the Data Browser filter, report and lineage toolbar
  filters and date ranges, the list limits and poll cadence, the JSON config editors and sample boxes, the names to
  check (a many-line paste box), the icon select (each option draws its glyph), and the cells of the decision
  consequence, Job parameter and transfer rows.
- The anomaly features card follows batch 6: every validator has its line, a server refusal marks the named field
  "Refused: see the message above." and a remove clears it.
- Behaviour to know: Enter no longer submits the link-expansion deny, map settings, menu, report draft, template,
  runbook, screening check or template-gallery forms. Per commit, not per keystroke: the import-bundle and gallery
  Create buttons. The id messages lost their `<code>` markup; the import and gallery ids lost `autocomplete="off"`;
  the screening threshold lost its 0.01 step; the anomaly weight and spread floor lost the decimal keypad
  (`inputmode`), and the template id and tag rename lost `autocomplete="off"`.

**P3 is done.** Every boxed field left in the app is a marked exception of a named kind (`grep -rn form-allow`), and
`lint:forms` refuses any new one. Still open: P4 (nested and disabled picker options, the per-picker layout),
`schema-form`'s in-row editor, and the lower items noted per batch (decimal keypads, `autocomplete="off"`, the
picker's re-pick dirtying a reactive form).
