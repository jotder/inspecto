---
type: Convention
title: Forms & State
description: Reactive forms with inline errors; signal state; optimistic mutation for reversible edits.
resource: inspecto-ui/src/app/inspecto/api/optimistic.ts
tags: [forms, reactive-forms, property-rows, pen-field, option-picker, signals, state, optimistic-ui]
timestamp: 2026-11-04T00:00:00Z
---

# Forms & State

## Forms

**Reactive only** (`FormBuilder`/`FormGroup`/`Validators`) with `markAllAsTouched()` on invalid submit.
Template-driven `ngModel` is legacy — do not add it to new forms (existing screens may still use it); the one
sanctioned use is a one-control bridge from a signal to a row or picker. Use typed `FormGroup`; reach for
`this.form.controls.x` (not `this.form.get('x')!`) to keep the typed-partial signatures.

### Property rows: the one form idiom (`UI-FORM-ROWS-1`, as built 2026-11-04)

Every value of a record is a **row** — label · value · ✎ (`<inspecto-pen-field>`) — and every single choice a
row that opens a **dialog** — label · value · ▾ (`<inspecto-option-picker>`: full labels, a hint per option,
groups, search from 8). Both lay out on `styles.scss` `.prop-row` / `.prop-label` (label left in `text-sm`, value
after it, affordance at the right edge); change the row there, never per component. `<inspecto-schema-form>`
draws the same rows, its optional / advanced tiers as named folds that say how many fields need fixing.

* **Layout**: two rows per line (`grid sm:grid-cols-2 gap-x-8`) so a form fits at a glance; past about 12 rows,
  named fold groups; a wizard only when a step depends on an earlier answer.
* **`help`**: one tiny line under a row whose name does not explain itself (a former `<mat-hint>`); an error
  line replaces it.
* **Errors**: the host computes `[error]` from `control.touched && control.hasError(…)`. **Every validator needs
  its line** — a row has no red outline, so a validator without a message makes Save refuse with nothing on
  screen. A server refusal that names a field marks its row with a short pointer ("Refused: see the message
  above.") until an edit clears it. A picker validated on save gets a host `role="alert"` line, not
  `[required]` (its own line shows only once its dialog opened).
* **Commit, not keystroke**: a row commits on Enter or blur and trims its text. Enter in a row commits the row
  and does not submit the form (the button does); a host that acts on Enter listens to `(entered)`, never
  `(keydown.enter)`, which also fires on the Enter that only OPENS a closed row. A host that derived dirtiness
  or a preview from `input` / `click` must use the control's `valueChanges` (or a child `changed` output via
  `outputFromObservable`, as the enrichment and segments editors do).
* **Pickers report a re-pick** of the shown value; a host whose change handler resets or toggles guards it.
  Values are strings — convert at the boundary.
* **Initial focus**: `cdkFocusInitial` on the `<inspecto-pen-field>` host (the focus trap moves it to the row's
  value; CDK logs a harmless dev-mode warning).
* **Deliberate boxes**: a field stays a boxed Material field only with `form-allow` and its reason on the tag's
  line — a secret (masked), a live search or filter box, a typeahead with an autocomplete panel, a `multiple`
  choice, a code / SQL / JSON editor, a sample or many-line paste box, a message composer, a read-only copy box,
  a cell of a repeating-row editor or a grid-toolbar select (dropdowns, a modal per cell is worse), or a value
  whose whitespace is meaningful (a delimiter; a row trims). `grep -rn form-allow` lists them all.
* **Guard**: `lint:forms` (`inspecto-ui/tools/check-form-idiom.mjs`, in CI and the pre-commit hook) refuses an
  unmarked `<mat-form-field>` / `<mat-select>` outside `tools/form-idiom-allowlist.txt`, and a listed file that no
  longer holds one, so the list only shrinks. It holds one file: `schema-form`, for its dense in-row editor.
* **Specs**: `inspecto/testing/pen-field.ts` (`penByLabel`, `penEdit`, `penOpen`, `penText`, `penError`,
  `pickerSet`, `refresh`) and `inspecto/testing/schema-form.ts` (`sfOpen`, `sfRow`, `sfHelp`); read errors from
  `inspecto-pen-field p[role="alert"]`, never `mat-error`.

### Dialog conventions (ui-design-review as-built)

* **Enter submits** schema-form dialogs (`(submitted)` on `<inspecto-schema-form>`).
* **Dirty guard**: wire editing dialogs through the shared `inspecto/dialog-dirty-guard.ts` so an
  accidental Esc/backdrop click can't drop edits.
* **Autocomplete attribute type**: entity-reference fields use the schema-form `autocomplete` type +
  `entity-option-loaders` (e.g. expectation target/refDataset, alert onPipeline) instead of free text.
  Column-of-a-target fields use `columnOptionLoader('<siblingKey>')` — a 1-row `/db/table` probe of the
  store the sibling field names, labels carrying the column type (expectation `column`/`refColumn`;
  the decision-rule when-clause fetches the same columns per target — no hardcoded record shape).
  Suggestions assist, they never constrain: a missing/unreadable store degrades to free text.
  A field referencing *any* component uses `componentRefOptionLoader()` — cross-kind `<kind>/<id>`
  suggestions (requirements "Delivered via"; the ref-list note becomes Registry `delivered-by` edges).
* **Tag chips**: the object-create dialog's tags are `MatChipGrid` chips suggested from the tag
  registry (`GET /tags`), assignee autocompletes over me + the mailbox's known assignees.
* **Name-at-save**: create dialogs ask config first and name/id last (two-step `saveForm` + `step`
  signal; ids pre-filled `<type>_<context>`; keep the config step `[hidden]` — not destroyed — so
  schema-form ViewChilds survive).
* **Initial focus**: `cdkFocusInitial` on the first field of every dialog — on the row host for a
  property row (auto on schema-form's first row).
* **Gotcha — `type="number"` must be static**: Angular's `NumberValueAccessor` selector matches the
  *static* `type="number"` attribute; a `[type]="…"` binding leaves the default (string) accessor
  attached, so the control emits `"4"` instead of `4`. The schema-form renders its `number` case with
  a literal `type="number"` for this reason — don't "simplify" it back into the shared text case.

## State

* **Signals** for component + shared service state; `computed()` for derived; `effect()` sparingly.
* `linkedSignal(() => source())` for "derived from a source but locally editable, resets when the source changes" (e.g. the SQL editor draft seeded from the generated SQL).
* RxJS for async pipelines + HTTP; unsubscribe via `takeUntilDestroyed(destroyRef)`.
* **No global store / NgRx.** Shared cross-cutting state lives in a root service exposing signals (pattern: `ConnectivityService`, `SpacesService`).

## Mutations

**Optimistic by default for reversible toggles/edits**: `optimisticMutate({apply, commit, reconcile,
rollback, onError})` (`inspecto/api/optimistic.ts`) — apply locally now, reconcile with the server result on
success (silent), roll back + toast on error. Reassign arrays (`rows = [...rows]`) so the grid re-renders.
Keep request→refetch for create/destroy or server-computed results.

The same rule holds for **bulk** verbs (the object-mail triage seam): patch every selected row + the open
detail to the expected post-state immediately, reconcile each row with the authoritative server object;
on any failure reload rather than snapshot-rollback (`forkJoin` fails fast — partial success makes a
rollback dishonest).
