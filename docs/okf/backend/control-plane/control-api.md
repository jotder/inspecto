---
type: Concept
title: HTTP Control API
description: The JDK HttpServer control plane — manual DI, virtual-thread requests, the dispatch seam, route families, the /api/v1 surface, editions-aware auth.
resource: inspecto/src/main/java/com/gamma/control/ControlApi.java
tags: [control-plane, http, api, routes, v1, editions]
timestamp: 2026-07-07T00:00:00Z
---

# HTTP Control API

`ControlApi` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`) is the control plane. It builds a
`com.sun.net.httpserver.HttpServer` (JDK built-in, zero added deps), sets a
`newVirtualThreadPerTaskExecutor()` (a fresh virtual thread per request), registers routes, and binds a
single catch-all `dispatch` context on `/`.

## Launch

`ControlApi.main` is the server entry: if `-Dspaces.root` is set it `SpaceManager.discover(...)`s, else it
builds a single `CollectorService` wrapped via `SpaceManager.single(...)`. Port from `-Dcontrol.port` (default
8080); a shutdown hook closes the API + spaces. (`com.gamma.inspector.MainApp` is a **separate** CLI pre-ETL tool
suite, not the server — see [build & run](../build-run/operations.md).)

## `dispatch`

Per request: strip an optional `/api` prefix (Angular dev-proxy); apply CORS if `-Dcontrol.cors` set; answer
`OPTIONS` preflight; match the `/spaces/{id}/…` seam (extract + validate id, rewrite path, bind the `space`
MDC — see [multi-space](multi-space.md)); match `routes` by pattern+method; fall through to static SPA assets
for unmatched GETs (`-Dui.dir`); always clear the space MDC in `finally`.

## Request-scoped attributes — never the JDK's exchange map

Everything a stage stamps for later stages (`ATTR_EFFECTIVE_PATH`, `ATTR_SUBJECT`, `ATTR_RAW_BODY`,
correlation id, idempotency, pagination, `ATTR_HELD_ROLES`, `ATTR_MATCHED_POLICY`) lives in
`ApiContext.REQUEST_SCOPES` — a map keyed by exchange **identity**, dropped in the outermost stage's
`finally` — accessed via `ApiContext.attr(ex, key[, value])` in-package and via the typed seams
`Roles.configRoot` / `ComponentAccess.heldRoles` / `AccessDecider.matchedPolicy` from the security and
policy modules. **Never store request state via `HttpExchange.set/getAttribute`:** on any pre-JDK-26
runtime (the bundle embeds GraalVM 25) that map is the *shared HttpContext map*, one map for every
in-flight request. The route-matching path once rode it, and concurrent bursts crossed requests — the
server served one URL with another request's file (BACKLOG §5 BUNDLE-1, closed `fb1511c0`: 53/1200
crossed on GraalVM 25, 0/3000 with the fix). The reactor gate runs on JDK 26, where exchange attributes
are per-exchange — **it structurally cannot catch a regression here**; the pins are
`ExchangeAttributeScopeTest` (two exchanges sharing one JDK map stay isolated, and the shared map stays
empty) and, for behaviour, a concurrent probe against the *shipped* runtime
(`inspecto-deploy/runtime/bin/java.exe`). The static handler's `-Dui.static.log=DEBUG` line logs
`served=<file>` beside the request URL precisely so a crossed pairing is visible in one grep.

## Route families

Registered via `RouteModule`s: health/ready (`/health`,`/ready`), metrics (`/metrics`, Prometheus text),
spaces (`/spaces`,`/spaces/_meta`), pipelines, jobs (`/jobs/{name}/runs|trigger`), events
(`/events/search|export|views`), connections, components ([registry](../components/component-registry.md)),
objects (ops), catalog, config/assist, enrichment, per-space settings docs (`/settings/branding|geo` and
`/nav/menus` — the Menu Builder tree; each a fixed-filename TOON in the space's config tree, PUT gated by
write-root 503 + `canAuthorWorkbench`, no jail/conflict gates since nothing caller-supplied touches a path.
**`canAuthorWorkbench` is deliberately the menu-curation gate too** (menu-builder open point O1, settled
2026-07-25): curating the tree every business user sees is space config-authoring, so it reuses that
capability rather than minting a distinct one — the UI now mirrors this gate instead of offering edits that
the server would 403. Splitting curation into its own capability stays an open product question, see
`BACKLOG.md` §3 Menu builder)
— plus the v1-era additions: `GET /bootstrap`
(server capabilities incl. `features.authMode`), `/auth/*` (the Standard-edition BFF session routes),
`POST /queries/{id}/run` (the `com.gamma.query` catalog, W4), and async run polling
(`GET /jobs/runs/{runId}`, `GET /runs/runs/{runId}`).

## `/api/v1`

Every business route is also dispatched under the versioned **`/api/v1`** prefix with a success/error
**envelope** (structured errors from the `ErrorCodes` catalog), `WriteGates`, a `Correlation-ID` response
header, and gzip (W1). Legacy unversioned routes stay **byte-for-byte unchanged**; their use is counted by
`ControlApi.recordLegacyUsage` (see [events & metrics](events-metrics.md)). Contract detail:
[API v1](api-v1.md).

## Auth by edition

The core/Personal edition is **auth-free**: every route is open — no token, guard, or login. On **Standard**
the `inspecto-security` module (reactor-gated behind the `edition-standard` Maven profile) enforces OIDC by
implementing the `Authenticator` / `Subject` / `TokenRelay` SPIs in `com.gamma.control` (see
[auth & security](../editions/auth-security.md)). The `-Dassist.write.root` 503 write-gate is **separate** from
auth and stays in all editions.
