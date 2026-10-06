---
type: Feature
title: Spaces
description: Multi-space (project) administration — the only space-aware admin view.
resource: inspecto-ui/src/app/modules/admin/spaces/spaces.routes.ts
tags: [feature, spaces, settings, multi-space]
timestamp: 2026-06-28T00:00:00Z
---

# Spaces

Route `/spaces` (Settings nav group). Administers the server's isolated spaces (projects). Together with the
header **space-switcher**, this is the only space-aware UI; everything else stays space-agnostic via the
`spaceInterceptor` (see [multi-space](../conventions/multi-space.md)). Backed by `SpacesService`; switching the
active space reloads the app.

* **Per-space branding lives here** (no separate Settings pane): `SpaceFormDialog` covers create **and**
  edit including logo (≤200 KB upload) / caption / footer, persisted via `GET|PUT /settings/branding`
  (per-space doc; empty → shipped default). The signal-backed `BrandingService`
  (`inspecto/api/branding.service.ts`) reloads on active-space change so the layout header stays live.
* Create asks a **Display name** and auto-derives the SpaceId slug (editable); the space id is immutable
  on edit. Each card gets **Activate** (single-select, hard-reloads to re-scope); the `default` space is
  not editable.

**"New space from template"** opens the template gallery (two-step ask-the-minimum: pick a card, then name
— id pre-filled from the template id). Templates are a server-global catalog (`GET /spaces/templates` +
`POST /spaces {template}`), deliberately *not* a Component kind. **Bundle export/import are
real-backend-only by design** (blob/zip round-trips). *(The mock backend this sentence used to mention was
deleted 2026-08-31; the template gallery renders whatever the server publishes.)* **Edition fit (2026-09-30):**
each gallery entry carries `creatable` and `missingFeatures[{feature, message}]`. These come from
`SpaceManager.missingFeatures`, which covers two cases:
- **Alert Rules:** a registry Alert Rule or a Decision Rule's `create-alert` needs `alert.dispatch`. A legacy
  per-file alert config is deliberately not counted: nothing loads one at runtime and the seed gate does not refuse
  one.
- **Pipeline features:** a Pipeline's archive or DuckLake use, through `EditionFeatures.pipelineRefusals`.

A template this build cannot create renders as an **`aria-disabled`** card that names the missing feature,
so the gallery never offers a create that `POST /spaces` would refuse with a 422.
- It is not native `disabled`, so the card stays focusable.
- `aria-describedby` points at the reason, and no `aria-label` overrides the card's own text.
- `choose()` still refuses the card. This is pinned in two places:
`ControlApiSpaceTemplatesTest.galleryMarksATemplateThisEditionCannotCreate` (a Personal context over real HTTP)
and the gallery spec.

**Per-Space settings screens (Settings drawer sections).** Egress Allowlist, Approvers and **Modules**
(`modules/admin/settings/module-settings.component.ts`, 2026-10-07). Modules lists installed Modules by offering role
from `GET /modules`, with a labelled switch ("Enabled in this Space") only for Optional/Provider Modules that declare
Features; Save PUTs `/settings/modules` (administer capability), then `SessionService.reloadFeatures()` and a
`NavigationService.get()` rebuild refresh the menu without a page reload. Unknown (inert) ids are listed and sent back untouched.
