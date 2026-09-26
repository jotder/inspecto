---
type: Feature
title: Incidents & Cases (Objects)
description: Operational objects in a mail-like 3-pane UI — one ObjectMailComponent serving /incidents and /cases via route data, with workflow-driven lifecycle, tags, and case management.
resource: inspecto-ui/src/app/modules/admin/objects/object-mail.component.ts
tags: [feature, objects, incidents, cases, operations, mail-ui, workflow]
timestamp: 2026-07-16T00:00:00Z
---

# Incidents & Cases (Objects)

Routes `/incidents` and `/cases` (Operations nav group) — a single `ObjectMailComponent` parameterized by
route data (`incidents.routes.ts` / `cases.routes.ts`), the canonical
[pane-reuse pattern](../conventions/routing-and-navigation.md). The chain is **Alert → Incident → Case**
([`GLOSSARY.md`](../../../GLOSSARY.md) §9) — never "Issue". Backed by `ObjectsService`; offline via the
the real ControlApi.

* **Mail shell** — Gmail-metaphor 3 panes: folder nav (My Incidents / Escalated / Identified / Diagnosing /
  Resolved / Archived + Tags) · list · detail panel; both side panes resize via the shared
  `InspectoSplitDirective`. High volume loads honestly via the data-table's
  [Load more strip](../design-system/data-table.md).
  * The pinned "assigned to me" folder is named per type by `mineFolder()` in `mail-model.ts` — **My
    Incidents** on `/incidents`, **My Cases** on `/cases`. ⚠ It was one literal "My Cases" on both panes
    until 2026-09-26 (telco demo review): Incident and Case are distinct concepts (GLOSSARY §9).
  * **"Me" is the signed-in Subject** — `currentOperator(SessionService.actor())`, falling back to the
    Personal placeholder (`inspecto.operator` / `operator`) only when no one is signed in. 🔴 Until
    2026-09-26 it was the placeholder everywhere: nothing in the SPA writes that key, so on OIDC or Demo User
    sign-in the Mine folder matched no one and the create dialog offered "operator" as an assignee. The mail
    pane reads it through a getter, so the folder counts follow the session.
  * **Landing folder** — with no explicit choice (there is no folder in the URL and none remembered, so
    only a click counts), the pane lands on the default (Identified / the workflow's `initial`) while it has
    items, else the first non-empty folder in display order, else the default. Counts are client-side over
    the one list fetch — no per-folder request. It runs once after the first load (and again when the
    served CASE workflow lands after the list), never on Refresh; a folder or tag click always wins.
* **Lifecycle** — `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` (+ reopen → Diagnosing); priority
  ladder Critical · Major · Minor · Low. The UI reads **`GET /workflows/{type}`** (BFS-ordered states)
  instead of hardcoding transitions, so TOON-overridden workflows drive the same panes. Resolve requires
  a resolution comment; a soft resolution-readiness warn checks timeline/cause-analysis/corrective
  actions — a *mirror* of the **server-side hard gate** (I1, 2026-07-24: `ObjectService.commit` rejects
  `INCIDENT → RESOLVED` with the same four checks; this line called it "a documented follow-up" until 2026-09-08).
* **Create contract (product sign-off + enforced 2026-07-22)** — assignment is **direct**: an `assignee`,
  optional at creation, settable at triage; queue-based routing is deferred (no multi-analyst consumer yet).
  Mandatory at creation: **title** (400) **+ at least one linked entity** (a case/incident with nothing
  linked isn't useful). `POST /objects` now takes a `links:[{to,relationship?}|id…]` array (≥1); the route
  validates every target exists *before* `open()` so a dangling link can't orphan the object, then links
  after opening — empty/absent `links` → 400, an unknown target → 404, `relationship` defaults to
  `RELATED_TO` (`ObjectRoutes.createObject`). The create dialog collects them via a required "Linked
  entities" multi-select + relationship select (`object-create.dialog`; a case defaults to `CONTAINS`, an
  incident to `RELATED_TO`). **Unaffected:** the
  auto-creation paths (`AlertService`/`DecisionRoutes`/`ExpectationRoutes`/`ReconRunJob`/`EventObjectBridge`)
  open objects directly via `ObjectService.open`, bypassing the route. **Bootstrap consequence:** the first
  object in an empty space must come from an auto-creation path — there is nothing to link to yet, so the
  dialog shows a "no existing objects to link to" hint and blocks manual creation until one exists.
* **Lifecycle verbs are gated on `canWorkIncidents`** (operator, 2026-09-26) — Accept / Resolve / Reopen /
  Archive, the Case workflow verbs (toolbar, detail panel, `/objects/{id}` page) render only with
  `LensService.canWorkIncidents()` (identity capability, action node `incidents.work`), the grant the server
  demands on `ack` / `resolve` / `transition` / `assign`. Accept self-assigns through `POST /objects/{id}/assign`,
  not the `canAdminister` PATCH. ⚠ Priority, the Incident Escalate flag, tagging and merge are not these verbs
  and keep their own gates (the PATCH and merge are `canAdminister` server-side, not hidden client-side).
* **Triage is optimistic** — every bulk verb (accept / resolve / archive / reopen / escalate /
  prioritize / tag / case actions) patches the loaded rows + open detail to the expected post-state,
  then reconciles each row with the authoritative server object; failures reload
  ([forms & state](../conventions/forms-and-state.md)). Merge/split/create stay request→refetch.
* **Tags & Tag Rules** — `/tags` + `/tags/rules`, auto-applied when an object opens; TOON-persisted,
  survive restart. **Scope note (2026-07-25):** this is an *object-scoped* tag system — the registry and the
  rules both assume the tagged thing is an Incident/Case. BACKLOG **D7** decided tags become a **generic
  cross-entity** grouping concept (streams, Alert Rules, datasets, …) backed by a central registry plus a
  `(tag, entity_kind, entity_id)` assignment store, i.e. **this system generalized**, not a second one built
  beside it. ⚠ Note for anyone reading the old backlog row: its claim that "nothing writes `attributes.tags`"
  was **simply false** — `ObjectService.ATTR_TAGS` (`= "tags"`) is written on manual apply, tag-rule merge,
  rule-raised creation, merge union, and split. The narrow `GET /objects` tag filter that row dismissed as
  "would silently match nothing" would in fact have worked. **D7 SHIPPED end-to-end 2026-07-26** — the
  generalization landed, `attributes.tags` is now a **projection** of the central assignment store rather
  than storage, and the cross-kind surface lives in the `/tags` pane. The mail pane's tag menu is still the
  only place a tag is *applied*; as-built:
  [`../../backend/control-plane/tags.md`](../../backend/control-plane/tags.md).
* **Case management** — case **Contents** (member incidents) with **Split & Merge**; variable **Cause
  Analysis** (`postmortem.causeAnalysis[]` + `causeMethod`); **Findings** (disposition/impact/records,
  soft no-disposition prompt); team `assignees` + `targetDate`. **Rule-raised cases**: `CaseRule`
  (`/cases/rules`, evaluate-on-demand, opens-or-attaches idempotently); **case analytics** via
  `GET /objects/analytics?type=` (stat tiles + by-category bar; Studio-dataset binding is a follow-up).
* **Configurable Findings sections (C3 / BACKLOG D6) — SHIPPED end-to-end 2026-07-26.** The Findings field
  set is deployment-authored: a **`findings-spec` ComponentStore kind** (one per `ObjectType`, id = the
  lowercased type) resolved and served by **`GET /findings/{type}`**, rendered by `<inspecto-schema-form>`.
  Absent a component, `FindingsSpec.defaultFor()` serves today's exact shape
  (disposition/impactAmount/recordsAffected/summary, all `tier:'required'` + `required:false` — always
  visible, never mandatory), so an unconfigured deployment is byte-for-byte unchanged. A present spec
  **fully replaces** the default for its type; field-level merge is unsupported on purpose because it makes
  "remove a section" inexpressible. Full rationale + the rejected alternatives:
  [`plans-archive/findings-spec-plan.md`](../../../archived-documents/plans-archive/findings-spec-plan.md).
  **Writing a spec needs `canManageIncidents`, not `canAuthorWorkbench`** (D1 = (b), operator 2026-09-25):
  the Case desk that resolves Cases authors its Findings, so `operations`/`support`/`admin`/`power` may save
  one and the builder roles may not; every other component kind is unchanged
  ([auth-security.md](../../backend/editions/auth-security.md) has the route shape).
  **Saving the values is collaboration (operator 2026-09-25)** — open to anyone who can see the Case, like a
  comment, through its own route **`PUT /objects/{id}/findings {findings:{…}}`** (`ObjectRoutes.saveFindings`
  → `ObjectService.saveFindings`). `PATCH /objects/{id}` stays `canAdminister` because it also edits priority /
  severity / assignee, so the Findings save could not ride it. The route writes **only** the blob and its flat
  copies, refuses any other body key (422 — it is not a way round the PATCH), keeps the scope guard's 404, and
  audits an `OBJECT_ACTIVITY` event (`action: findings`, actor = the authenticated Subject). The flat
  `impactAmount`/`recordsAffected` copies are now **derived on the server** from the blob (`""` when absent),
  where the panel used to compute them. The panel's **team + target date** (C6) still go on the PATCH, and
  only when that form was edited — so a viewer's Findings save succeeds, and their team edit would 403.
  Pinned by `ControlApiFindingsWriteTest` (real scoped Subject without `canAdminister`).
  **Values are validated too, since 2026-07-26** — `FindingsSpec.validateFindings(findings, previous)`,
  called from `ObjectRoutes.validateFindings` on `PUT /objects/{id}/findings` and `PATCH /objects/{id}` (→ **422**): `select` membership,
  `number` with `min`/`max`, `boolean`, and `pattern`; a section hidden by its `dependsOn` is skipped
  entirely (the form never showed it, so it cannot be required). Four rules are load-bearing:
  * **The `attributes.findings` JSON blob is the canonical home of Findings values** (D3 = (a), operator
    2026-09-25 — no migration). Only a patch carrying `findings` is judged, and the blob is judged **as it
    will be stored** (it replaces the stored one whole). 🔴 Until 2026-09-25 the gate judged **top-level**
    attribute keys, where the panel never writes a Findings value: the panel's own saves were never
    type-checked, and because it also sends the flat `impactAmount`/`recordsAffected` copies, a spec with a
    `required: true` section refused **every correctly filled panel save** with 422 (reproduced by
    `ControlApiFindingsSpecTest.theFindingsPanelsOwnSaveIsAcceptedWhenARequiredFieldIsFilled`). Top-level
    keys — the flat copies included — are now ordinary attributes.
  * **An undeclared key is never rejected** (inside the blob or out). `attributes` is a *shared* bag (it
    also carries `tags`, `caseType`, `dueAt`, …), and a blob key no section declares is how a **removed**
    field's stored value survives (D7).
  * **An unchanged stored value is not re-judged.** The panel re-sends the whole blob on every save, and a
    spec edit (a removed choice, a tightened bound) keeps the values Cases already hold; re-judging them would
    refuse an edit to some *other* field on that Case. A changed value is always judged.
  * **The gate is in `ObjectRoutes`, not `ObjectService`** — the spec lives in the space's `ComponentStore`,
    an edge concern; the engine stays store-agnostic. `effectiveFindingsSpec` was extracted out of
    `findingsSpecOf` so the read route and the write gate resolve the same spec exactly once.
  `autocomplete` options stay *suggestions*, never a closed set — only `select` is closed.
  ⚠ **Two premises in D6's wording were wrong** — if you remember the old framing, re-read this:
  * *"Reuse the C6 workflow/TOON pattern"* — **not viable.** `*_workflow.toon` is a **boot-time scan of CLI
    path arguments** (`ServiceBootstrap.resolveBySuffix`), with no write root, no CRUD and no hot reload;
    cloning it would have shipped a config surface an operator cannot edit through the product. The
    ComponentStore kind was chosen instead, and **still adds no endpoint** (D6's actual constraint) because
    `/components/{type}` CRUD is generic — the kind inherits create/update/delete, ETags and version
    history, joining the idiom `alert-rule`/`notification-rule`/`expectation` already use.
  * *"the `attribute-spec` renderer"* — that is the **frontend** `<inspecto-schema-form>` driven by
    `AttributeSpec[]`, **not** backend `ConfigSpecs`/`FieldSpec` (compiled-in Java for nine pipeline/Studio
    config types, not runtime-authorable). Sections are therefore authored in the `AttributeSpec`
    vocabulary and served verbatim; `ConfigSpecs` is deliberately untouched, since mapping the two shapes
    (`path` vs `key`, `enumValues` vs `options`, `visibleWhen` vs `dependsOn`, no `tier` at all) would be
    lossy for zero reuse.
  * ⚠ **That canonical-frontend-shape choice is paid for by a hand-kept backend mirror, so it is pinned by
    a cross-language contract test** (2026-08-15, the `MeasureCompiler.AGGS` idiom). One committed
    artifact, `inspecto-ui/src/app/inspecto/contracts/attribute-spec.contract.json`, is compared by `FindingsSpecContractTest`
    (Java) and `attribute-spec.contract.spec.ts` (TS), so neither side moves alone. Drift used to be
    silent in the worse direction: a type added only in TypeScript makes the server **422 a section the
    renderer could have drawn**, with an error naming a control the author's own form offers. The unions
    are DERIVED from exported `ATTRIBUTE_TYPES`/`ATTRIBUTE_TIERS` arrays so the vocabulary is enumerable
    at run time; `ATTRIBUTE_KEYS` comes from a `Record<keyof Required<AttributeSpec>, true>`, so adding a
    field to the interface **fails to compile** until it is classified.
  * ⚠ **`group` and `secret` are frontend-only** — on `AttributeSpec` but absent from
    `FindingsSpec.SECTION_KEYS`, so authoring `group:` on a findings-spec is a 422 even though the form
    would render it. Deliberate rather than accidental now: `secret` on a Findings value would be
    misleading (it is stored and returned in `attributes` regardless), and `group` is an unasked-for
    widening. The contract's `frontendOnlyKeys` records the pair and the Java side asserts the parser
    really refuses them.
  * **Validation is fail-closed at authoring time (422)** via a per-kind hook in `ComponentRoutes.writeComponent`:
    unknown `type`/`tier`, a `select` with no `options`, an invalid `pattern`, `min > max`, a `dependsOn`
    naming no sibling, an `objectType` disagreeing with the component id, and **unknown section keys**
    (a typo'd `tier` silently defaulting is how a required field becomes invisible). A spec hand-edited into
    an unreadable state on disk degrades to the built-in with a logged warning rather than 500ing triage.
  * **UI consequences:** `Findings` is now an open `Record<string, string>` (`mail-model.ts`), the panel's
    team + target date moved to a sibling `teamForm` (they are C6, not Findings), the flat
    `impactAmount`/`recordsAffected` copies the C4 analytics roll-up sums are written **on every Findings
    save** (by the server since 2026-09-25) — as `''` when the section is not configured, so removing those sections blanks the roll-up for
    each Case as it is next saved (corrected 2026-09-25; this line used to say "only while configured",
    which the code never did) — and the soft no-disposition prompt on resolve **only fires while
    `disposition` is a configured section**. `CASE_DISPOSITIONS` was removed from `mail-model.ts` — the
    ladder now lives in the backend default, which is its only home *(the offline mock backend was deleted 2026-08-31)*.
* **Authoring UI — the *Findings fields* dialog (2026-09-25, BUILT; ⚠ ACCEPTANCE OWED).** Design + the ten
  signed decisions: [`findings-spec-authoring-ui-design.md`](../../../superpower/findings-spec-authoring-ui-design.md).
  A toolbar icon in the Cases pane (beside Case rules, D4) opens `FindingsSpecEditorDialog`
  (`objects/findings-spec-editor.dialog.ts`) for the Case spec only (D2 — the dialog takes `data.objectType`, so
  Incident is a one-line entry if a panel ever renders it). All rules live in the framework-free
  `findings-spec-editor.model.ts`. As built:
  * **Loads the effective spec** (`GET /findings/case`) plus the authored component (`GET
    /components/findings-spec/case`, 404 ⇒ built-in) and badges **Built-in / Customised**. Saving the built-in
    `POST`s the component; an authored one `PUT`s with `If-Match` (409 ⇒ "someone else changed these fields" +
    Reload, never an overwrite). A 422 that gets past the client mirror shows verbatim. *Restore built-in* is a
    `DELETE` behind a destructive confirm; *History* is the shared `ComponentHistoryDialog`.
  * **Nothing technical on the default path:** five plain answer kinds (Choose one from a list · Short text ·
    Long text · Number · Yes / No), keyed by `Record<AttributeType, …>` so a contract type added without a
    plain label fails to compile; **Always shown / Under “More”** only (D5 — a stored `advanced` renders as a
    third, kept-as-saved choice); *Must be filled in* separate from where it appears. Keys and choice values are
    **derived from the label on first save and frozen after**; ID-style text / List of values / Suggested
    values, `pattern`, and the stored key/values sit under a collapsed **Technical details** (D6).
  * **"Show only when"** points at fields **above** only (the server accepts forward references — the picker
    does not offer them), by local uid so a rename never breaks it; ⚠ **Number and List targets are not
    offered**: the renderer compares with `===` and the server as text, so a numeric condition cannot be
    authored to match on both sides. A Yes/No condition is written as a real boolean for the same reason.
  * **Problems list** mirrors `FindingsSpec.fromMap` rule for rule in the field's label, and Save is disabled
    while it is non-empty. **Removing** a saved field or choice warns generically that stored values stay but
    stop showing (D7 — no count read); removing `impactAmount`/`recordsAffected` adds a Case-analytics warning.
  * **Preview** is the real `<inspecto-schema-form>` over the draft, values carried across edits.
  * **Gate:** Save / Add / Restore render only with `LensService.canManageIncidents()` (a new *identity*
    capability, action node `incidents.manage` under Case Manager); everyone else sees the same screen
    read-only. The components pane never lists the kind (D8).
  * ⚠ **Not driven in a browser preview in the build shift** (`preview_start` serves the main checkout, and
    the routes need a packaged `inspecto-ops`) — unit specs only (`findings-spec-editor.*.spec.ts`).
  * 🔴 **ACCEPTANCE OWED (D10) — the BACKLOG row does NOT close on this build.** It closes on ONE think-aloud
    session with a real Case-desk lead doing, unaided: T2 add a "Root cause category" dropdown · T6 show a
    field only when Disposition = Recovered · T7 preview before saving · T5 remove a field. Record pass/fail per
    task **here**, with the date and the lead's role. Not yet run.

As-built designs (archived):
[`incidents-mail-ui-design.md`](../../../archived-documents/plans-archive/incidents-mail-ui-design.md) ·
[`case-management-design.md`](../../../archived-documents/plans-archive/case-management-design.md).
