# Findings-spec authoring UI — design

> **DESIGN ONLY, 2026-09-24. Nothing is built.** BACKLOG §3.9 row *D6 spec-authoring UI (`findings-spec`)*.
> Owner concept: [`okf/frontend/features/objects.md`](../okf/frontend/features/objects.md) (§ *Configurable
> Findings sections*). UI work follows the [`angular-ui`](../../.claude/skills/angular-ui/SKILL.md) skill;
> chrome follows [`okf/frontend/conventions/page-chrome.md`](../okf/frontend/conventions/page-chrome.md).
> The shipped backend half is archived in
> [`plans-archive/findings-spec-plan.md`](../archived-documents/plans-archive/findings-spec-plan.md), whose
> §5 put an authoring UI out of scope (lines 165–166, 172). **This design asks for ten operator calls (§9).**

**The test the row sets:** can an analyst use it? Being faster than the generic path does not count. So every
choice below is judged by one question: *could a case-management lead who has never seen TOON, a regex or an
identifier add a "Root cause category" dropdown to the Case Findings panel, see it before saving, and not
break the Cases they already hold?*

---

## 1. Persona and tasks

**Persona: the case-management lead ("Priya").** She runs a fraud / revenue-assurance desk. She resolves Cases
every day in the Cases pane (`/cases`), knows the Disposition ladder by heart, and owns what her team records
when a Case closes. She is not a developer. She does not know what a `key`, a `tier`, a `pattern` or TOON is,
and she should never need to. She thinks in terms of *"the questions my team answers when closing a Case."*

Her tasks, in order of how often they come up:

| # | Task | Today |
|---|---|---|
| T1 | **See** what the Findings panel asks, and whether it is the built-in or her own version | Impossible from the product. `GET /findings/case` over HTTP only |
| T2 | **Add** a field — a dropdown ("Root cause category"), a number ("Recovered amount"), a yes/no, free text | Write a `findings-spec` component by hand through `POST /components/findings-spec` |
| T3 | **Edit** a field's label, help text, choices, or whether it must be filled | Same, plus `If-Match` so she does not overwrite a colleague |
| T4 | **Reorder** fields, or move one into a collapsed "More" area | Same |
| T5 | **Remove** a field she no longer wants | Same — and nothing warns her about Cases that already hold a value |
| T6 | **Show a field only when** another has a given value ("Recovered amount" only when Disposition = Recovered) | Same, with `dependsOn: {key, equals}` |
| T7 | **Preview** the panel exactly as her team will see it, before saving | Impossible |
| T8 | **Undo** — go back to an earlier version, or back to the built-in | Version restore works through `/components/.../versions/.../restore`; "back to built-in" means `DELETE` the component |

T1, T2, T3 and T7 are the core. T6 is the most error-prone for a non-engineer. T5 and T8 are where she can do
real damage without knowing it.

---

## 2. As-is (grounded)

### 2.1 The model and its validator — shipped and strict

* `FindingsSpec` is a record of `objectType` + ordered `sections`
  (`inspecto-engine/src/main/java/com/gamma/objects/FindingsSpec.java:47`). A section is the frontend
  `AttributeSpec` vocabulary, served verbatim (`:33-38`, `:71-78`).
* **Accepted section keys:** `key label type tier required default options pattern min max dependsOn help
  placeholder` (`FindingsSpec.java:60-62`). `group` and `secret` are rejected even though the renderer draws
  them (objects.md, *frontend-only keys*).
* **Types and tiers** come from `NodeAttribute.TYPES` / `TIERS`
  (`inspecto-engine/src/main/java/com/gamma/pipeline/NodeAttribute.java:56-64`): `string identifier number
  boolean select autocomplete multiline list` × `required optional advanced`. The TS mirror is
  `ATTRIBUTE_TYPES` / `ATTRIBUTE_TIERS` (`inspecto-ui/src/app/inspecto/component-model/attribute-spec.ts:19`,
  `:37`), pinned by the cross-language contract (`attribute-spec.contract.spec.ts` ↔ `FindingsSpecContractTest`).
* **Authoring-time validation is fail-closed** (`FindingsSpec.fromMap`, `:128-198`): no sections; blank or
  duplicate `key`; unknown `type`/`tier`; a `select` with no options; an unparseable `pattern`; `min > max`;
  a `dependsOn` naming no sibling (`:149-153`) or holding both/neither of `equals`/`notEquals` (`:230-233`);
  any unknown section key (`:158-161`). An unknown `objectType` throws (`:133`). Every message names the
  offending section by **key**, not by label — that matters for §6.
* **The built-in default** (`defaultFor`, `:99-107`): `disposition` (select, five-value ladder) ·
  `impactAmount` · `recordsAffected` · `summary`, all `tier:required` + `required:false`.
* **Value validation** (`validateValues`, `:262-280`) runs on `PATCH /objects/{id}` from
  `ObjectRoutes.validateFindings` (`inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:728`,
  `:745-755`).

### 2.2 Storage, routes and gates

* Persisted as a `findings-spec` ComponentStore kind, one component per `ObjectType`, id = lowercased type
  (`inspecto-engine/src/main/java/com/gamma/pipeline/ComponentStore.java:81-85`).
* Served as the **effective** spec (authored, else built-in) by `GET /findings/{type}`
  (`ObjectRoutes.java:95`, `:236-254`). A malformed file on disk falls back to the built-in with a warning.
* The per-kind write hook refuses an `objectType` that disagrees with the component id and runs `fromMap`
  (`inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:618-629`).
* **Every write needs `canAuthorWorkbench`** (`ComponentRoutes.java:49-51`, restore `:54`). By the role seed
  (`inspecto/src/main/java/com/gamma/control/Roles.java:162-180`) that is `pipeline-developer`,
  `app-developer`, `developer` and `power` — **not `admin`, not `business`, and not `operations`/`support`**,
  who hold `canManageIncidents`. 🔴 **So the persona in §1 cannot save a spec under any seeded role except
  `power` today.** That is decision **D1**.
* `ObjectType` is `ALERT, INCIDENT, CASE, TASK`
  (`inspecto-engine/src/main/java/com/gamma/objects/ObjectType.java:19`), so four specs are authorable —
  but **only the Case spec is ever rendered**: the Cases pane fetches `findingsSpec('CASE')`
  (`inspecto-ui/src/app/modules/admin/objects/object-mail.component.ts:434`) and hands the Incident pane
  `null` (`object-mail.component.html:216`). Decision **D2**.

### 2.3 What the SPA has — renderer yes, editor no

* **There is no authoring surface at all.** The SPA's `ComponentType` union does not list `findings-spec`
  (`inspecto-ui/src/app/inspecto/api/components.service.ts:13-29`), and the Components pane lists only the
  five palette kinds (`COMPONENT_TYPES`, `:34`). The row's "TOON through generic `/components` CRUD"
  means **HTTP or a file on disk**, not a screen. For a non-engineer the as-is is *no path*.
* **The renderer is shipped and reusable:** `<inspecto-schema-form>`
  (`inspecto-ui/src/app/inspecto/components/schema-form.component.ts:57`) takes `[specs]` (`:806`),
  `[initial]` (`:855`) and `[readOnly]` (`:707`), and sorts fields into the three disclosure tiers.
  `findingsAttributes()` narrows a served spec to `AttributeSpec[]`
  (`inspecto-ui/src/app/modules/admin/objects/mail-model.ts:171`, `dependsOnOf` `:209`), and the Postmortem
  panel renders it (`postmortem-panel.component.ts:114-115`). **A live preview therefore costs nothing new:**
  feed the draft through `findingsAttributes()` into a second `<inspecto-schema-form>`.
* **Bespoke-editor precedents in the same pane:** `openForm` sends `mapping` and `schema` to dedicated grid
  editors and everything else to the generic form (`inspecto-ui/src/app/modules/admin/components/components.component.ts:122-150`),
  whose config field is a raw JSON textarea (`component-form.dialog.html:56`). The schema editor
  (`schema-editor.dialog.ts`) is a row table on `<inspecto-editable-grid>`
  (`inspecto-ui/src/app/inspecto/components/editable-grid.component.ts:11`, `:96`).
* **Case-desk admin dialogs already exist and are opened from the Cases pane toolbar:** Case Rules, Case
  analytics and Tag Rules (`object-mail.component.ts:658`, `:667`, `:689`; toolbar icons
  `object-mail.component.html:138-143`). That is where Priya already goes to configure her desk.
* **An authoring form driven by `<inspecto-schema-form>` already ships:** the notification-rule dialog declares
  its fields as `RULE_ATTRIBUTES`
  (`inspecto-ui/src/app/modules/admin/notification-center/rule-attributes.ts:9`).
* **Save plumbing is there:** `ComponentsService.update(..., {ifMatch})` sends `If-Match` → `409
  CONFLICT_STALE_VERSION` on a stale write (`components.service.ts:194-202`); `ComponentHistoryDialog`
  lists and restores versions (`inspecto-ui/src/app/inspecto/components/component-history.dialog.ts:77`);
  `guardDirtyClose` blocks losing an unsaved draft (`inspecto-ui/src/app/inspecto/dialog-dirty-guard.ts:22`).

### 2.4 🔴 A seam defect this UI would make reachable (grounded by reading, NOT reproduced)

The Postmortem panel saves Findings as **one JSON blob**, `attributes.findings`, plus two flat copies:
`impactAmount` and `recordsAffected`, sent **on every save**, as `''` when the field is not configured
(`postmortem-panel.component.ts:331-337`). The server's `validateValues` judges **top-level** attribute
keys only (`FindingsSpec.java:262-280`), and the real-HTTP test drives it with top-level keys
(`inspecto-ops/src/test/java/com/gamma/control/ControlApiFindingsSpecTest.java:150-153`). Read together:

1. A value of an authored section (for example `rootCause`) lives only inside the blob, so its `select`,
   `number`, `pattern` checks **never run on the UI's own writes**.
2. If a spec marks any section `required: true` **and** still declares `impactAmount` or `recordsAffected`,
   every panel save touches a declared key. The server then judges the required section against the
   top-level bag, where it is absent, and **refuses the save with 422 even though the form was filled in.**

Today only an engineer can author `required: true`. The editor in this design puts that switch in front of
an analyst. ⇒ **Slice S0 must reproduce this with a real-HTTP test before any UI slice ships**, and D3 picks
which storage shape is canonical. (Also note: objects.md says the flat copies are written "only while those
sections are configured"; the code writes `''` unconditionally, so that sentence needs a check in S0 too.)

---

## 3. What an analyst must never have to do

These are design constraints. Each comes from a validator rule in §2.1 that a non-engineer would hit.

| She must not… | Because the validator… | So the editor… |
|---|---|---|
| type a `key` | rejects blank/duplicate keys and names errors by key | derives the key from the label on first save (`Root cause category` → `rootCauseCategory`), shows it read-only under "Technical details", and **freezes it once saved** (§5.4) |
| know what a tier is | rejects unknown tiers | offers **"Always shown" / "Under *More*"** (`required` / `optional`). `advanced` is hidden by default (D5) |
| confuse "shown" with "must be filled" | keeps `tier` and `required` separate on purpose | shows two independent controls: *Where it appears* and a *Must be filled before resolving* switch |
| write a regex | rejects bad patterns | hides `pattern` behind "Technical details" (D6) |
| pick among 8 control types | accepts all 8 | offers 5 plain answers (§5.2); `identifier`, `list`, `autocomplete` stay reachable only under "Technical details" |
| hand-write `dependsOn` | needs a sibling key and exactly one of `equals`/`notEquals` | builds it from two pickers — *Show only when* [field ▾] [is / is not] [value ▾] — over fields she already made |
| read "section 'x' has min > max" | names the key | the editor validates the same rules **client-side first** and phrases them in her words against the field's label (§6); a server 422 still shows, verbatim, as a fallback |

---

## 4. Options

### Option A — the generic form (`ComponentFormDialog`) with the kind widened

Add `findings-spec` to the `ComponentType` union and let the Components pane open it in the generic form.
The body is the raw JSON textarea (`component-form.dialog.html:56`).

* ➕ About ten lines of code.
* ➖ **It fails the row's test outright.** It is TOON/JSON with a textarea instead of a file. Priya still
  types keys, tiers and `dependsOn` objects. It is also in the Components pane, which is a builder's pane
  she never visits.
* Verdict: **rejected.** This is the "faster than the generic path" answer the row explicitly rules out.

### Option B — `<inspecto-schema-form>` over a meta-spec, per field

Declare the section shape itself as an `AttributeSpec[]` (a `FINDINGS_FIELD_ATTRIBUTES` const, the
`RULE_ATTRIBUTES` idiom) and render one `<inspecto-schema-form>` per field inside a list the dialog owns.
The section list (add / remove / reorder) is bespoke; the per-field form is generic.

* ➕ Reuses the renderer, its validation, its tier disclosure and its a11y. Adding a section key later is a
  one-line change in the meta-spec.
* ➖ The meta-spec cannot express the three hard parts: a label-derived frozen key, an **options list editor**
  (the schema-form has no "list of `{value,label}`" control; `list` is a list of strings), and a
  **`dependsOn` builder whose choices are the *other* fields' keys and *their* options**. Each of these needs
  a bespoke control beside the schema-form, and the memory note *per-spec schema-form instances multiply
  tier disclosures* warns that N forms in a list produce N "More" toggles.
* ⚠ **It is NOT "schema-form over a ConfigSpec".** The backend `ConfigSpec`/`FieldSpec` family was
  deliberately kept out of Findings (`FindingsSpec.java:35-38`; objects.md). A `ConfigSpec` for the
  findings-spec kind would reopen a decision already recorded, so the "ConfigSpec" variant of this option
  is ruled out rather than weighed.

### Option C — a bespoke field-list editor with a live preview, opened from the Cases pane

A dialog with three parts: a **field list** on the left (label, control-type icon, "must fill" and
"shown only when" chips, drag handle and ↑/↓ buttons); a **field editor** for the selected field; and a
**live preview** on the right, which is the real `<inspecto-schema-form>` rendering the draft through
`findingsAttributes()`. Opened from a new *Findings fields* icon in the Cases pane toolbar, beside Case Rules
and Tag Rules.

* ➕ It answers every §3 constraint directly, and the preview makes T7 free: what she sees is the shipped
  renderer, not an imitation.
* ➕ It lives where the persona already works, next to Case Rules.
* ➖ It is the most code, and the field editor is a hand-built form, so the vocabulary is mirrored a third time
  (TS union, Java set, editor). This is mitigated by building the editor's type/tier choices **from
  `ATTRIBUTE_TYPES` / `ATTRIBUTE_TIERS`** (§5.2), so the contract test still pins the only real list.

### Option D — hybrid (C's shell, B's per-field body)

C's list, preview and `dependsOn` builder, with the *simple* per-field properties (label, help, placeholder,
where it appears, must fill) rendered by one `<inspecto-schema-form>` bound to the **selected** field only.
The options editor and the `dependsOn` builder stay bespoke.

* ➕ One schema-form instance at a time, so only one "More" toggle. The plain properties get the house
  renderer for free.
* ➖ Two form technologies in one panel. The editor must merge schema-form output with bespoke-control output
  into one section object.

---

## 5. Recommendation — Option D, in the Cases pane

**Build Option D, opened from the Cases pane toolbar, for the Case spec only (D2), and save through the
existing `/components/findings-spec/case` CRUD. No new endpoint.** Option C is a close second. If D's
two-technology seam proves awkward in S2, fall back to C, which changes only the field editor's body.

### 5.1 Where it lives

* **Entry:** a toolbar icon button *Findings fields* (`matTooltip` + `aria-label`) in the Cases pane, placed with
  Case Rules / Case analytics / Tag Rules (`object-mail.component.html:138-143`), visible only when
  `!isIncident`. It is hidden without the write capability (D1), and a disabled read-only view is offered
  instead (T1 still works for everyone).
* **Surface:** a resizable dialog (`inspecto-dialog-resizable`, the shared drag grip), not a routed pane, in
  line with its three siblings. So page-chrome's header rules do not apply. If D4 promotes it to a routed
  settings section, it must take `<inspecto-page-header>` with `[headingLevel]="2"` when dual-hosted (page-chrome
  *One `<h1>` per page*).
* **Secondary entry:** the Components pane *may* list the kind read-only with an "Open in Cases" link, so a
  builder can find it (D8). It must not open the generic JSON form.

### 5.2 The five plain control types

| Analyst sees | Wire `type` | Extra properties shown |
|---|---|---|
| Choose one from a list | `select` | Choices editor (required — the validator refuses an empty one) |
| Short text | `string` | Placeholder |
| Long text | `multiline` | Placeholder |
| Number | `number` | Smallest / largest allowed (`min` / `max`) |
| Yes / No | `boolean` | — |
| *(Technical details)* ID-style text · List of values · Suggested values | `identifier` · `list` · `autocomplete` | Pattern |

The table's wire column is generated from `ATTRIBUTE_TYPES`, with a `Record<AttributeType, …>` label map, so
adding a type in the contract **fails to compile** until it gets a plain label (the `ATTRIBUTE_KEY_SET` idiom,
`attribute-spec.ts:105`).

### 5.3 The choices editor

Rows of *Label* only. The stored `value` is derived from the label once and then frozen, as with keys. So
"Card not present" becomes `CARD_NOT_PRESENT`, matching the ladder's own `FALSE_POSITIVE` style. Reorder
works with ↑/↓, and remove needs a confirmation when Cases already use the value (§5.4). A "Technical
details" disclosure shows the frozen values.

### 5.4 Protecting existing Cases (T5, T8)

Stored values are keyed by section `key`, so **renaming a key orphans data** (it stays in `attributes`
unrendered), and **removing a section hides values** that existing Cases hold.

* Keys and choice values are **frozen after first save**. Only the label changes. This is the single most
  important analyst-safety rule in this design.
* Removing a field or a choice shows how many Cases hold a value for it (D7: a count query, or a plain
  "Cases that already recorded this field keep the value, but it will no longer be shown" warning if no
  count is built).
* Removing `impactAmount` or `recordsAffected` warns that **Case analytics stops receiving impact figures**
  (the C4 roll-up reads the flat copies, `postmortem-panel.component.ts:333-336`).
* **Restore built-in** is a secondary action behind a destructive confirmation. It `DELETE`s the component,
  and the server then serves `defaultFor` again. **History** opens `ComponentHistoryDialog`.
* Save sends `If-Match` from the read's `contentHash`. A `409` shows the "someone else changed this" alert
  with *Reload* and never silently overwrites.

### 5.5 Starting point

On open, load `GET /findings/case` (the **effective** spec) and `GET /components/findings-spec/case` (to know
whether an authored one exists, and for its `contentHash`). A badge in the dialog title reads **Built-in** or
**Customised** (`<inspecto-status-badge>`). Editing the built-in creates the component on first save (the
`POST` path). So "start from what my team sees today" is the default, and nobody starts from a blank list.

---

## 6. Screen outline

```
┌ Findings fields — Case ─────────────────────────────── [Customised] ─ [⟲ History] [⋯] ┐
│ ┌ Fields ─────────────────────┐ ┌ Field ──────────────────────┐ ┌ Preview ───────────┐ │
│ │ ≡ Disposition     ▾  must  │ │ Label  [Root cause category] │ │ (the real schema-  │ │
│ │ ≡ Impact amount   #        │ │ Help   [Pick the main cause] │ │  form, draft specs,│ │
│ │ ≡ Records affected #       │ │ Control ( ) Choose one ▾     │ │  editable, values  │ │
│ │ ≡ Summary         ¶        │ │ Appears (•) Always ( ) More  │ │  not saved)        │ │
│ │▸≡ Root cause cat. ▾  when… │ │ [ ] Must be filled to resolve│ │                    │ │
│ │ [+ Add field]              │ │ Choices  Card not present ↑↓✕│ │ [ Reset preview ]  │ │
│ └────────────────────────────┘ │          Account takeover ↑↓✕│ └────────────────────┘ │
│                                │          [+ Add choice]      │                        │
│                                │ Show only when [Disposition▾]│                        │
│                                │   [is ▾] [Confirmed ▾]  [✕]  │                        │
│                                │ ▸ Technical details (key, …) │                        │
│                                └──────────────────────────────┘                        │
│ ⚠ 2 problems — "Root cause category" needs at least one choice.  (role="alert" list)  │
│                                              [Cancel]  [Restore built-in]  [ Save ]    │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

* **One filled primary** (Save) at the right end. *Restore built-in* is a stroked destructive button, and
  History is an icon button (page-chrome *one filled primary action*).
* **Problems list:** client-side checks mirror `fromMap` one for one and name the field by **label**: *needs at
  least one choice* · *two fields have the same name* · *smallest is larger than largest* · *"Show only
  when" points at a field that was removed*. Save stays disabled while the list is non-empty. A server 422
  that gets past the mirror shows its message verbatim below the list, so drift is visible rather than
  swallowed.
* **The `dependsOn` builder** offers only fields **above** this one. The validator allows forward references
  (`FindingsSpec.java:148`), but a field whose visibility depends on something below it is unreadable in a
  form. Its value picker shows the target's choices when the target is a select, a Yes/No for booleans, and
  free text otherwise. `notEquals` is the *is not* choice.
* **Preview** is editable (so she can flip Disposition and watch the dependent field appear), holds values in
  memory only, and has *Reset preview*.
* Narrow widths (< `md:`) stack the three columns, with Preview collapsed behind a "Preview" toggle.
* Vocabulary: the UI says **Findings field**, never "section" (wire only) and never "finding" in the
  validation sense. Problems are *problems*, because `Finding`/`ConfigFinding` is the validator's word and
  "Findings" is the Case artifact (GLOSSARY *Findings*). See D9.

---

## 7. Slices

Each slice ships with the tests named in §8 and runs **unit-level** per the CLAUDE.md rule (`npx ng test
--include=…`, `-pl inspecto-ops -Dtest=ControlApiFindingsSpecTest`).

| Slice | Content | Blocks on |
|---|---|---|
| **S0** | Reproduce §2.4 with a real-HTTP test (UI-shaped PATCH with a `findings` blob + `required:true` authored section → today 422). Fix per **D3**, either by teaching `validateFindings` to read the blob or by moving the panel to top-level keys. Correct the objects.md sentence on the flat copies. | D3 |
| **S1** | Widen `ComponentType` with `'findings-spec'` (not in `COMPONENT_TYPES`). Pure model helpers in `modules/admin/objects/findings-spec-editor.model.ts`: `labelToKey`, `labelToOptionValue`, `validateDraft` (the `fromMap` mirror → label-phrased problems), `toWire` / `fromWire`. No UI. | — |
| **S2** | The dialog, read-only first: load effective + authored, Built-in/Customised badge, field list, live preview. Toolbar entry in the Cases pane. | D1, D2 |
| **S3** | Editing: add / remove / reorder, field editor (label, help, placeholder, control, appears, must fill), choices editor, frozen keys, problems list, Save with `If-Match`, 409 handling, dirty-close guard. | S2 |
| **S4** | `dependsOn` builder (fields above only; is / is not; value picker by target type). | S3 |
| **S5** | Safety: removal warnings (D7), analytics roll-up warning, Restore built-in, History. | S3, D7 |
| **S6** | Docs: distil into objects.md (as-built), glossary touchpoint if D9 adds a term, `docs/USER_GUIDE.md` task section written for the persona. Archive this plan. | S5 |

---

## 8. Test plan

**vitest (house idioms: one `TestBed.configureTestingModule` per `it`, explicit host `changeDetection`):**

* `findings-spec-editor.model.spec.ts` (S1) — `labelToKey` (spaces, punctuation, leading digit, collision →
  suffix), `labelToOptionValue`; `validateDraft` has **one case per `fromMap` rule in §2.1**, each asserting
  the label-phrased message; `toWire(fromWire(x))` round-trips `defaultFor`'s shape. A **parity test**
  drives the committed `attribute-spec.contract.json` so an added type or key fails here too.
* `findings-spec-editor.dialog.spec.ts` (S2–S5) — Built-in vs Customised badge; editing the built-in `POST`s
  and editing an authored one `PUT`s with `If-Match`; 409 → alert + Reload, no overwrite; a 422 message is
  shown verbatim; a saved field's key is read-only; renaming a label does not change the key; Save is
  disabled while problems exist; the `dependsOn` picker lists only fields above; removing `impactAmount`
  shows the analytics warning; Restore built-in asks first, then `DELETE`s; the dirty-close guard fires.
  ⚠ Drive handlers, not synthetic clicks, for any Material tab (page-chrome trap).
* `expectNoA11yViolations` on the dialog in three states: built-in read-only, editing with problems, and the
  `dependsOn` builder open.
* **Negative test with a probe that would otherwise succeed** (memory note): the "fields above only" test
  must include a field *below* that would be a legal forward reference to the server, and assert it is
  absent from the picker.

**Java (S0):** extend `ControlApiFindingsSpecTest` with the UI-shaped PATCH. Mutation-check it: revert the
fix and confirm the test fails **with the 422 message naming the required field**, not for an unrelated
reason.

**Preview drive (`.claude/launch.json`, Professional edition by default, rebuilt `inspecto-ui/dist` or `ng
serve` — never a stale `:8080` bundle):** as a `power` user (or the D1 role), open `/cases` → *Findings
fields*. Then:

1. Add "Root cause category" (Choose one, two choices, shown only when Disposition is Confirmed). Watch it
   appear and disappear in the preview as Disposition is flipped.
2. Save, then open a Case, and confirm the panel shows the field under the same condition.
3. Fill it in, resolve, and confirm the save succeeds (S0's fix live).
4. Reopen the editor and rename the label. Confirm the Case still shows the stored value.
5. Mark the field *Must be filled* and confirm resolve is blocked **inline** in the Case panel, not by a
   422 toast.
6. Restore built-in, and confirm the panel returns to the four default fields.
7. Open as a user without the capability and confirm the view is read-only with no Save.

Count click events before filing any dead-button defect (viewport-emulation trap), and read console errors.

**Analyst acceptance (the row's actual criterion):** one think-aloud session with a real case-management
lead doing T2, T6, T7 and T5 unaided, recorded as pass or fail per task. That session, not the test
suite, closes the BACKLOG row (D10).

---

## 9. Decisions owed (operator)

1. **D1 — Who may author.** Today the spec needs `canAuthorWorkbench`, which `admin`, `business`,
   `operations` and `support` lack (`Roles.java:162-180`), so only `power` among desk-facing roles can save.
   (a) keep it and tell desks to use `power`; (b) gate the `findings-spec` kind on `canManageIncidents`
   instead, a per-kind capability in `ComponentRoutes` (a new-route-style four-gate change); (c) a new
   `canConfigureCaseDesk` capability. *Recommend (b):* the people who resolve Cases already hold it.
   **DECIDED (2026-09-25, operator): (b).** Built as four literal `/components/findings-spec…` write routes
   gated on `canManageIncidents`, registered ahead of the generic ones; other kinds unchanged
   (`ControlApiFindingsSpecGateTest`; as-built in `docs/okf/backend/editions/auth-security.md`).
2. **D2 — Scope to Case only?** Four object types are authorable, but only Case renders Findings.
   *Recommend Case only*, with the dialog parameterised on type so Incident is a later one-line entry if a
   panel ever renders it.
3. **D3 — Canonical storage for Findings values** (§2.4, S0). (a) the server reads `attributes.findings`
   (the blob) for validation; (b) the panel writes each field as a top-level attribute key and drops the
   blob. *Recommend (a):* no data migration, and existing Cases keep working. But this must be decided
   **before** analysts can author `required`.
4. **D4 — Dialog or routed settings section?** *Recommend dialog* in the Cases toolbar, matching Case
   Rules and Tag Rules.
5. **D5 — Expose the `advanced` tier?** For Findings it means "behind the gear" in a triage panel.
   *Recommend no:* offer Always / Under *More* only, and keep `advanced` readable when present.
6. **D6 — Expose `pattern`, `identifier`, `list`, `autocomplete` at all?** *Recommend under "Technical
   details" only*, never in the default path.
7. **D7 — Removal impact:** build a count of Cases holding a value for a key or choice (a new read), or
   ship the generic warning only? *Recommend the generic warning first*, with the count as a follow-up
   row.
8. **D8 — Components pane:** list `findings-spec` read-only with "Open in Cases", or keep it off that pane
   entirely? *Recommend off* until a builder asks.
9. **D9 — UI word:** "Findings field" (recommended) vs "Findings question". Either needs a GLOSSARY entry
   under *Findings*, since the wire says `section`.
10. **D10 — Acceptance:** does one analyst think-aloud session (§8) close the row, or is a named pilot desk
    required? *Recommend one session with a real lead*, recorded in objects.md.
