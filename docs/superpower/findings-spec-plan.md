# Configurable Findings sections (C3 / BACKLOG D6) — build plan

**Status:** planned, not started · **Opened:** 2026-07-26 · **Decision of record:** BACKLOG §2 D6
**Concept home (update on ship):** [`../okf/frontend/features/objects.md`](../okf/frontend/features/objects.md)
**Backend home for the kind:** [`../okf/backend/control-plane/objects.md`](../okf/backend/control-plane/objects.md) *(or the
`operations-reference` doc if no objects concept exists — check at ship time)*

> **What this is.** Findings on an Incident/Case are a fixed disposition/impact/records shape today. This
> plan makes the **section set** a deployment-authored, server-served configuration, rendered by the
> existing shared form renderer. Detail of the decision lives in D6; detail of the as-built will move to
> the OKF concept when this ships.

---

## 1. Two corrections to the decision's premises

D6's wording — *"reuse the C6 workflow/TOON pattern **+** the `attribute-spec` renderer; no new
endpoint"* — names two mechanisms that do not compose, and one that isn't what it sounds like. Both were
verified 2026-07-26. **Trust this section over D6's phrasing.**

1. **The workflow pattern is boot-scan-only and cannot carry this.** `*_workflow.toon` files are resolved
   by CLI path *suffix* at startup (`ServiceBootstrap.java:88-91` → `resolveBySuffix(paths, "_workflow.toon")`),
   parsed by `Workflow.load` (`Workflow.java:200-207`), and registered whole-replace per `ObjectType`. There
   is **no write root, no CRUD, no hot reload** — changing a workflow means restarting with a different file
   argument. Cloning it for Findings would ship a configuration surface an operator cannot edit through the
   product.
2. **The `attribute-spec` renderer is not fed by `ConfigSpecs`.** Backend `ConfigSpecs`/`FieldSpec`
   (`ConfigSpecs.java:31-52`, `FieldSpec.java:30`) is **hard-coded Java** served at `GET /config/spec/{type}`
   for nine pipeline/Studio config types — compiled-in metadata, not runtime-authorable. The renderer the
   Findings panel would actually use is the **frontend** `<inspecto-schema-form>`, driven by
   `AttributeSpec[]` (`inspecto-ui/src/app/inspecto/component-model/attribute-spec.ts`), which every pane
   supplies from a **client-authored** `*-attributes.ts` file. So "reuse the attribute-spec renderer" means
   the *frontend* renderer, and the server-authored requirement is precisely the part that does not exist yet.

**Also:** Findings has **no backend model at all** today. The fields are flat untyped attributes —
`attributes.impactAmount` and `attributes.recordsAffected`, aggregated in `ObjectService.java:289-332` —
plus a free-form JSON blob at `attributes.postmortem` (`ObjectService.java:1222-1229`). The UI shape lives
in `mail-model.ts` / `postmortem-panel.component.ts`. There is nothing to refactor, which makes this
additive.

## 2. The decided mechanism — a `findings-spec` ComponentStore kind

**Operator call, 2026-07-26:** the section definitions are a **`findings-spec` ComponentStore kind**.

D6's rationale rejected this as "a third configuration idiom". **That objection does not survive contact
with the code** and the call is right on the merits:

- `/components/{type}` CRUD is **fully generic** — list, get (with a strong `ETag`), create, update, delete,
  plus version history and restore, all `canAuthorWorkbench`-gated (`ComponentRoutes.java:30-38`,
  `CapabilityManifest.java:41-44`). A new kind inherits all of it.
- It is therefore **not a third idiom but the established one**: `expectation`, `decision-rule`,
  `alert-rule`, `channel` and `notification-rule` were all promoted onto exactly this contract
  (`ComponentStore.WRITABLE_TYPES`, `ComponentStore.java:46-70`), `alert-rule` explicitly *off* raw
  `*_alert.toon` files in 2026-07-18 — i.e. the direction of travel is away from the workflow boot-scan.
- **It adds no endpoint**, satisfying D6's actual constraint. It also gets per-space persistence, live
  re-read (`ComponentStore` re-scans per call), and version history for free.

### 2.1 Vocabulary: author in `AttributeSpec` shape, serve verbatim

The TOON is authored in the **frontend `AttributeSpec` vocabulary** (`key`, `label`, `type`, `tier`,
`required?`, `default?`, `options?`, `pattern?`, `min?`/`max?`, `dependsOn?`, `help?`, `placeholder?`) and
served through generic `/components` CRUD unchanged.

**Rejected: serving `FieldSpec` and mapping client-side.** The two schemas differ structurally
(`path` vs `key`; `enumValues` vs `options[{value,label}]`; `visibleWhen: "otherPath=value"` string vs
`dependsOn: {key, equals|notEquals}`; `FieldSpec` has no `tier`, which is the whole disclosure model). Since
a ComponentStore kind never touches `ConfigSpecs`, introducing `FieldSpec` here would add a lossy
translation for zero reuse. **`ConfigSpecs` is deliberately not modified by this plan.**

⚠ **Consequence to accept explicitly:** the canonical spec vocabulary for this kind is defined by a
frontend file. That is a real coupling. It is the lesser evil versus a third schema plus a mapper, but the
backend validator (§3.2) is what makes it safe — the server must reject a spec the renderer cannot draw,
rather than trusting the author.

### 2.2 Shape

`registry/findings-specs/<objectType>.toon` — one spec per object type, id = the lowercased `ObjectType`
(`incident`, `case`), so a deployment can differ them:

```toon
findings-spec {
  name = incident
  sections [
    { key = disposition, label = "Disposition", tier = required, type = select,
      options [ { value = confirmed, label = "Confirmed" }, { value = false-positive, label = "False positive" } ] }
    { key = impactAmount, label = "Impact amount", tier = optional, type = number, min = 0 }
    { key = recordsAffected, label = "Records affected", tier = optional, type = number, min = 0 }
  ]
}
```

**Backwards compatibility is non-negotiable:** absent a `findings-spec` component, the panel renders the
**current hardcoded shape** as the built-in default. This mirrors the `NotificationRules` overlay idiom
(authored entries checked *ahead of* `defaults()`) rather than the workflow whole-replace idiom, so an
existing deployment sees no change until it authors a spec. A present spec **fully replaces** the default
for that object type — partial field-level merge is deliberately **not** supported (it makes "remove a
section" unexpressible).

## 3. Build steps

Each step names its own verification. `mvn -o` per [`build-verify`](../../.claude/skills/build-verify/SKILL.md);
the UI half follows [`angular-ui`](../../.claude/skills/angular-ui/SKILL.md) — **read it before touching
`inspecto-ui/`**.

### 3.1 Register the kind (backend, small)
- `ComponentRegistry.TYPE_BY_DIR` (`ComponentRegistry.java:44-64`) — add `findings-specs` → `findings-spec`.
- `ComponentStore.WRITABLE_TYPES` (`ComponentStore.java:46-70`) — add `findings-spec` with a comment in the
  house style (what it is, why it is a kind, pointer to this plan).
→ **verify:** `GET/POST/PUT/DELETE /components/findings-spec[/{id}]` round-trips in a real-HTTP test.

### 3.2 A fail-closed spec validator (backend, the substance)
A malformed spec must be rejected at **CRUD time (422)**, never at render time — the renderer degrading
silently in a triage pane is the failure mode to prevent.
- New `FindingsSpec` model (in the `ops` package beside the object model, framework-free, `fromMap`/`toMap`
  mirroring `ChannelConfig.java:33-71`): parses `sections[]`, validates each entry.
- Reject: a missing/duplicate `key`; an unknown `type` (not in the `AttributeType` union); an unknown
  `tier`; `type = select` with no `options`; a `dependsOn.key` that names no sibling section; `min > max`;
  an unparseable `pattern`. Unknown *extra* keys are **rejected, not ignored** — a typo'd `tier` silently
  defaulting is how a required field becomes invisible.
- `FindingsSpec.defaultFor(ObjectType)` — the current hardcoded disposition/impact/records shape as data.
→ **verify:** `FindingsSpecTest` for every rejection above + a `defaultFor` round-trip; a real-HTTP test
asserting 422 on each malformed body (per the `endpoint` skill's mandate that every gate is covered).

### 3.3 Serve the effective spec (backend)
The UI needs *the spec in force*, not "the authored one if any" — resolving the overlay client-side would
duplicate `defaultFor` in TypeScript.
- Extend the existing `GET /workflows/{type}` handler's neighbourhood in `ObjectRoutes.java:128-131` with the
  effective-spec read on the **existing** objects route family (e.g. as an additional field on the workflow
  response, or a sibling read on the same `ObjectRoutes` module — decide at implementation time, preferring
  whichever keeps the pane's request count unchanged).
- ⚠ **Open sub-question, resolve while building:** folding it into the workflow response is one fewer
  request and both are "how this object type behaves", but it overloads a route whose name says
  *workflow*. If it reads as a stretch, a sibling read is acceptable — D6's "no new endpoint" was aimed at
  a *new configuration idiom*, which the ComponentStore kind already avoids. **Do not add a new
  configuration mechanism; an additional read on the existing module is not that.**
→ **verify:** real-HTTP test — no component ⇒ built-in default; authored component ⇒ authored spec;
malformed on disk ⇒ default + a logged warning, never a 500.

### 3.4 Render it (frontend)
- `postmortem-panel.component.ts` currently hardcodes the Findings fields; move it onto
  `<inspecto-schema-form>` fed by the served spec, via `ObjectsService`.
- Keep the existing **soft no-disposition prompt** working when `disposition` is present in the spec, and
  make it not fire when a deployment removed that section.
- Offline: the `mockOps` handler answers the new read (per
  [mock-backends](../okf/frontend/features/../conventions/mock-backends.md) convention) — seed the built-in
  default so offline behaviour is unchanged.
→ **verify:** `postmortem-panel.component.spec.ts` extended — default shape, an authored 2-section spec, a
removed-disposition spec (no prompt); `test:ci` green; axe-core clean per the a11y gate.

### 3.5 Docs (same change, per the docs lifecycle)
- Rewrite the C3 bullet in `okf/frontend/features/objects.md` as-built, **including §1's corrections** so
  the next reader is not misled by D6's wording.
- Note the kind in the backend concept doc + the `WRITABLE_TYPES` list wherever it is enumerated.
- Delete the D6 row from `BACKLOG.md` §2 and the C3 row from §4 Incidents/cases per the maintenance rule;
  record any residual in §6. `git mv` this plan to `docs/archived-documents/plans-archive/`, update
  `docs/INDEX.md`, run `graphify update .`.

## 4. Scope boundaries

- **Not** making the *values* schema-validated server-side. Findings values remain flat attributes; this
  plan configures the **form**, not a typed persistence model. Server-side validation of submitted values
  against the spec is a defensible follow-on — note it in §6 rather than smuggling it in.
- **Not** touching `ConfigSpecs`/`FieldSpec` (§2.1).
- **Not** touching the workflow boot-scan path.
- **Not** building a spec-authoring UI. Authoring is TOON through `/components` CRUD, exactly as
  `notification-rule` shipped backend-only. A future editor is one item, not this one.

## 5. Residuals to expect in §6 on ship

- The spec vocabulary is defined by a frontend file (§2.1) — the coupling is deliberate; revisit only if a
  second consumer needs it.
- No spec-authoring UI (mirrors the `notification-rule` residual).
- Server-side validation of submitted Findings *values* against the spec (§4).
