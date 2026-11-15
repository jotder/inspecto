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
`spaceInterceptor` (see [multi-space](spec-multi-space.md)). Backed by `SpacesService`; switching the
active space reloads the app.

* **Per-space branding lives here** (no separate Settings pane): `SpaceFormDialog` covers create **and**
  edit including logo (≤200 KB upload) / caption / footer, persisted via `GET|PUT /settings/branding`
  (per-space doc; empty → shipped default). The signal-backed `BrandingService`
  (`inspecto/api/branding.service.ts`) reloads on active-space change so the layout header stays live.
* **With sign-in, branding and the default timezone need `canAuthorWorkbench`** (their `PUT /spaces/{id}/settings/…` routes
  are gated on it, as is the Link Analysis kind of data; creating, renaming or removing a Space is gated on
  `canAdminister`; without sign-in no gate applies). The dialog offers those fields only when `LensService.canSaveSpaceSettings`
  holds — identity, not lens, and asked of the current Space (the server asks of the target Space; a difference shows
  as the named warning below): a seeded `admin`, who lacks it, sees a one-line note instead and sends no branding or
  timezone. The Link Analysis create's kind of data is still offered. Every setting is its own step after the Space: a refused one is named
  (`Space "x" was created, but its branding was not saved: …`) and the dialog still closes on the saved Space —
  never an open dialog over a Space that exists (`SPACE-CREATE-PARTIAL-1`, 2026-11-15;
  `space-form.dialog.spec.ts`).
* Create asks a **Display name** and auto-derives the SpaceId slug (editable); the space id is immutable
  on edit. Each card gets **Activate** (single-select, hard-reloads to re-scope); the `default` space is
  not editable.

**"New space from template"** opens the template gallery (two-step ask-the-minimum: pick a card, then name
— id pre-filled from the template id). Templates are a server-global catalog (`GET /spaces/templates` +
`POST /spaces {template}`), deliberately *not* a Component kind. **Bundle export/import are
real-backend-only by design** (blob/zip round-trips). *(The mock backend this sentence used to mention was
deleted 2026-08-31; the template gallery renders whatever the server publishes.)* **Edition fit (2026-09-30):**
each gallery entry carries `creatable` and `missingFeatures[{feature, message}]`. These come from
`SpaceManager.missingFeatures`, which covers three cases:
- **Alert Rules:** a registry Alert Rule or a Decision Rule's `create-alert` needs `alert.dispatch`. A legacy
  per-file alert config is deliberately not counted: nothing loads one at runtime and the seed gate does not refuse
  one. **Exception (operator 2026-11-08, "A: allow, unarmed"):** a template whose `template.toon` says
  `minEdition: basic` (the shipped `la-showcase`) ships its Alert Rules unarmed where `alert.dispatch` is absent. The
  gallery lists it creatable, `POST /spaces` creates it with each rule's content still judged
  (`AlertRoutes.parseContent`), and the boot leaves every rule unarmed with a WARN. A Decision Rule's `create-alert`
  still counts (`ControlApiSpaceTemplatesTest.aBasicTierTemplateShipsItsAlertRulesUnarmedOnBasic`; the deferred
  `pending/alert-rules/` and the `create-alert` limit:
  `aBasicTierTemplateShipsItsPendingAlertRulesUnarmedButNotACreateAlertDecisionRule`).
- **Pipeline features:** a Pipeline's archive or DuckLake use, through `EditionFeatures.pipelineRefusals`.
- **Demo User sign-in** (`demo-auth`, `LA-SAMPLE-GALLERY-DEMOAUTH-1`, 2026-10-23): a template carrying
  `config/demo-users.toon` (the shipped `la-showcase-off`) needs an installed Authenticator that names its principals from a Space's config - only Demo
  Auth does; Basic has none and OIDC cannot say who exists. ⚠ Unlike the two above this is the gallery's verdict
  only: `POST /spaces` does not refuse such a template, so the API still creates it (its Demo Users are then inert).
  Pinned by `ControlApiSpaceTemplatesTest.galleryCountsTheDemoUserSignInATemplateNeeds`. **Exception (operator
  2026-11-08):** a template whose `template.toon` says `minEdition: basic` (the shipped `la-claims`, `la-mobile-money`,
  `la-audit` and `la-showcase`) has a Basic tier;
  its roster is the inert Advanced tier and does not make it uncreatable
  (`ControlApiSpaceTemplatesTest.aBasicTierTemplateKeepsItsDemoUserRosterAsAnInertAdvancedTier`).

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
