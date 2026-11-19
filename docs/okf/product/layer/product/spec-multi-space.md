---
type: Convention
title: Multi-Space Scoping
description: SpacesService holds the active space; the spaceInterceptor rewrites API paths so features stay space-agnostic.
resource: inspecto-ui/src/app/inspecto/api/space.interceptor.ts
tags: [multi-space, multi-tenant, interceptor, spaces]
timestamp: 2026-07-07T00:00:00Z
---

# Multi-Space Scoping

The server hosts many isolated **spaces** (projects). Do **not** re-roll per-space logic in features.

* `SpacesService` (`inspecto/api`) holds the active space as a signal (`currentSpaceId`, restored from `localStorage` in its ctor).
* The global `spaceInterceptor` rewrites every feature call so feature services stay **space-agnostic**. The space id goes **after** the version segment: `/api/v1/<path>` → `/api/v1/spaces/<id>/<path>` (the backend strips `/api/v1` at dispatch, then matches `/spaces/{id}/…`); legacy `/api/<path>` callers keep the unversioned rewrite.
* It **no-ops** when there's no active space (single-tenant, byte-identical) and **exempts** the eight `SERVER_GLOBAL` paths (`/health`, `/ready`, `/metrics`, `/spaces*`, `/bootstrap`, `/auth`, `/public`, `/exchange`) — see `space.interceptor.ts`.
* Detect the mode via `GET /spaces/_meta` → `{multiSpace}` (never infer from the space-list length).
* The header **space-switcher** and the [spaces admin view](user-guide-spaces.md) are the only space-aware UI; switching reloads.

## Multi-space decision

- **Multi-space (multi-project), `master`-only `feat:` track.** One server hosts many isolated **spaces**
  (`-Dspaces.root`, default `./spaces`); each = `spaces/<id>/{config,data,audit,duckdb,flows}` + `space.toon`.
  The ~40-method `@PublicApi` per-instance `CollectorService` is **wrapped, not rewritten**: `SpaceManager` →
  `SpaceContext` → unchanged `CollectorService`. Isolation of the five process-wide singletons (EventLog /
  MetricRegistry `space` label / ConnectionRegistry / StabilityGate / AcquisitionLedgers) is by the **`space`
  SLF4J MDC** (`EventLog.currentSpaceId()`; fallback `"default"` = no MDC = byte-identical single-space). API
  seam: `/spaces/{id}/…` (`ControlApi.dispatch` strips + MDC-binds; un-prefixed → current/default). Space CRUD
  (no restart): `GET/POST /spaces`, `DELETE /spaces/{id}?purge=` (purge = opt-in file removal). **No flat
  fallback** — migrate once via `com.gamma.service.SpaceMigrator`. Editions/auth stay future SPI (no
  `if(edition==)`). → [`configuration.md` §Spaces](../../module/config/admin-guide-configuration-reference-spaces.md#spaces-multi-project-layout).
  **UI (Stage 7):** `SpacesService` (signals) + a global `spaceInterceptor` rewrite `/api/<p>` →
  `/api/spaces/<id>/<p>` so every feature service stays space-agnostic (no-op single-tenant = byte-identical);
  header space-switcher + `modules/admin/spaces` admin (CRUD + per-space/per-data-source zip export + import
  with dry-run preview + create-from-bundle). The UI tells discover from single-tenant via the additive
  **`GET /spaces/_meta` → `{multiSpace}`** (= `SpaceManager.supportsCrud()`), never by space-list length (a
  fresh discover server returns `[]`). See the `angular-ui` skill §7.
