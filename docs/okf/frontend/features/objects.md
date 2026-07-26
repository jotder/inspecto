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
`mockOps` [interceptor](../conventions/mock-backends.md).

* **Mail shell** — Gmail-metaphor 3 panes: folder nav (My Cases / Escalated / Identified / Diagnosing /
  Resolved / Archived + Tags) · list · detail panel; both side panes resize via the shared
  `InspectoSplitDirective`. High volume loads honestly via the data-table's
  [Load more strip](../design-system/data-table.md).
* **Lifecycle** — `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` (+ reopen → Diagnosing); priority
  ladder Critical · Major · Minor · Low. The UI reads **`GET /workflows/{type}`** (BFS-ordered states)
  instead of hardcoding transitions, so TOON-overridden workflows drive the same panes. Resolve requires
  a resolution comment; a soft resolution-readiness warn checks timeline/cause-analysis/corrective
  actions (backend workflow guard is a documented follow-up).
* **Create contract (product sign-off + enforced 2026-07-22)** — assignment is **direct**: an `assignee`,
  optional at creation, settable at triage; queue-based routing is deferred (no multi-analyst consumer yet).
  Mandatory at creation: **title** (400) **+ at least one linked entity** (a case/incident with nothing
  linked isn't useful). `POST /objects` now takes a `links:[{to,relationship?}|id…]` array (≥1); the route
  validates every target exists *before* `open()` so a dangling link can't orphan the object, then links
  after opening — empty/absent `links` → 400, an unknown target → 404, `relationship` defaults to
  `RELATED_TO` (`ObjectRoutes.createObject`). The create dialog collects them via a required "Linked
  entities" multi-select + relationship select (`object-create.dialog`; a case defaults to `CONTAINS`, an
  incident to `RELATED_TO`), and the `mockOps` handler mirrors the contract. **Unaffected:** the
  auto-creation paths (`AlertService`/`DecisionRoutes`/`ExpectationRoutes`/`ReconRunJob`/`EventObjectBridge`)
  open objects directly via `ObjectService.open`, bypassing the route. **Bootstrap consequence:** the first
  object in an empty space must come from an auto-creation path — there is nothing to link to yet, so the
  dialog shows a "no existing objects to link to" hint and blocks manual creation until one exists.
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
  * **Validation is fail-closed at authoring time (422)** via a per-kind hook in `ComponentRoutes.writeComponent`:
    unknown `type`/`tier`, a `select` with no `options`, an invalid `pattern`, `min > max`, a `dependsOn`
    naming no sibling, an `objectType` disagreeing with the component id, and **unknown section keys**
    (a typo'd `tier` silently defaulting is how a required field becomes invisible). A spec hand-edited into
    an unreadable state on disk degrades to the built-in with a logged warning rather than 500ing triage.
  * **UI consequences:** `Findings` is now an open `Record<string, string>` (`mail-model.ts`), the panel's
    team + target date moved to a sibling `teamForm` (they are C6, not Findings), the flat
    `impactAmount`/`recordsAffected` copies the C4 analytics roll-up sums are written **only while those
    sections are configured**, and the soft no-disposition prompt on resolve **only fires while
    `disposition` is a configured section**. `CASE_DISPOSITIONS` was removed from `mail-model.ts` — the
    ladder now lives in the backend default and the offline mock's mirror of it.

As-built designs (archived):
[`incidents-mail-ui-design.md`](../../../archived-documents/plans-archive/incidents-mail-ui-design.md) ·
[`case-management-design.md`](../../../archived-documents/plans-archive/case-management-design.md).
