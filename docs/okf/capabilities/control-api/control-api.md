---
type: Capability
title: Control API (API)
description: The versioned HTTP control plane — the /api/v1 envelope and error catalog, correlation, gzip, ETag concurrency and idempotency, cursor pagination, /bootstrap, the OpenAPI contract and its test, route registration and the RouteModule SPI, static SPA serving, the gateway blueprints, and the Java embedding API stability policy. The requirement of record for the API area, its specification, its decisions, and what was refused.
resource: inspecto/src/main/java/com/gamma/control, inspecto-api/src/main/java/com/gamma/api/PublicApi.java
tags: [api, capability, control-api, v1, envelope, error-codes, etag, idempotency, cursor, openapi, route-module, public-api, semver]
timestamp: 2026-09-08T00:00:00Z
---

# Control API — capability spec (`API`)

> **What this page is.** The single entry point for the API capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Fourth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../superpower/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
> **This spec is the design of record for the `/api/v1` contract.** The 2026-07-06 design document
> (`api-contract-design.md`) is archived and is *provenance*: its 33 guidelines are summarised in §3.1 and its
> decisions carried into §4 so nothing current has to read the archive.
>
> **Canonical vocabulary** (`GLOSSARY.md` §14 — binding). The **Control API** is the versioned HTTP control
> plane (`ControlApi`, JDK `HttpServer`) — it moves configuration and commands, never rows; the data plane
> is `DAT`. A **Capability** is a named authorization question a route gates on (`SEC`). A **Platform
> Service** is an engine facility a Job is *granted* — ⛔ never "capability" in that sense, and never
> "Controller Service". An **Edition** is a build flavour; a route family an edition lacks answers `503`,
> never `404`. The area was renamed from "API & integration": *integration* is undefined and collided with
> the warehouse *integrations* page.

## 1. Purpose & scope

The Control API is **the one door into a running Inspecto**: every screen the SPA draws, every operator
`curl`, every gateway in front of a Standard deployment and every Java embedder reaches the engine through
it. Its promise is a *contract* — one envelope, one error catalog, one versioned prefix, one correlation id
per request — stable enough that a gateway and an external IAM can front it without reshaping a route, and
honest enough that a client which merely adds the prefix without unwrapping `data` fails at runtime rather
than silently.

**In scope:** the request pipeline and its stage order; the `/api/v1` prefix rule and what the unversioned
surface still serves; the envelope, `ErrorCodes`, `Correlation-ID`, gzip; ETag / `If-None-Match` /
`If-Match` concurrency and `Idempotency-Key`; cursor pagination; `GET /bootstrap`; the OpenAPI 3.1 contract,
its examples and `ApiContractTest`; route registration, the `RouteModule` SPI, the duplicate guard and
first-match dispatch; request-scoped attributes; static SPA serving; the per-edition `503` posture of absent
route families; the gateway / IAM blueprints; the Java embedding API stability policy (`@PublicApi`,
SemVer, the release baseline); the Platform Services seam as the API a Job sees; and the SPA client
conventions that consume all of it.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| Who the caller is and what they may do — `authenticate`, `authorize`, capabilities, the write-root gate | `SEC` — the pipeline *order* is here, the *decisions* are there |
| What individual route families do — pipelines, jobs, objects, components, queries, exchange | their areas (`PIP`, `INC`, `MET`, `DAT`, …). This spec owns the transport every one of them rides |
| Which bundle carries which route module | `PKG` — this spec owns the `503`-not-`404` posture and `features.*` derivation, not the profiles |
| The Signal/Event ledger the `Correlation-ID` lands in | `OPS` |
| Config-key contracts (`AttributeSpec`, node attributes, TOON shapes) | `PIP`/`MET` — the cross-language `*.contract.json` mechanism is *cited* in §8 because it is how this repo pins a wire vocabulary |

## 2. Requirements of record

Seven requirements from `REQUIREMENTS.md` §3.11 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped. **Three are not flat green when read
against the build.** ⚠ **`EDITIONS.md`'s feature × edition matrix is authoritative for the Edition
column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `API-1` | Versioned **`/api/v1`** business contract: envelope, error-code catalog, `Correlation-ID`, gzip; the only business surface since `API-5` | Must | ✅ SHIPPED (W1, 2026-07-06) | All |
| `API-2` | OpenAPI 3.1 contract (`docs/api/openapi-v1.json`) enforced by `ApiContractTest` | Must | ✅ SHIPPED (W2) — ⚠ **exemplar coverage, now MEASURED and ratcheted**: 19 paths / **24 operations against 266 live route registrations = 9.0 %** (+73 absent-module stubs), measured 2026-09-09 by `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted`, which prints the figure on every run. 🔴 **The "~6 % of ~332" this row and §5 both carried was a UNIT ERROR** — it divided documented *paths* by *(method, pattern)* registrations. ⚠ **The 9.0 % is the CORE surface and is an UPPER BOUND.** Measured under `-Pedition-enterprise` the live count is **266, identical to the default reactor** — not because the profile does nothing, but because every optional module (`inspecto-ops`, `inspecto-notify-channels`, `inspecto-geo-link`) depends on `inspecto-processor` while the core declares none of them, so a test living IN the core can never have one on its classpath. The 73 stubs are exactly those modules answering 503. A shipped Enterprise bundle registers these 266 **plus** the optional modules' routes against the same 24 documented operations, so its real coverage is **lower**. ⛔ Do not quote 9.0 % as the Enterprise figure. ⛔ Whether exemplar coverage is the right posture is still an operator decision (§5) — the guard measures, it does not decide. The served document's own description claimed the retired legacy table until 2026-09-08 (§2 corrections) | All |
| `API-3` | Optimistic concurrency: `ContentHash` + ETag / `If-None-Match` / `If-Match` on Components | Must | ✅ **server SHIPPED (W3) — no client consumer.** `If-Match` is *honoured* (stale ⇒ `409 CONFLICT_STALE_VERSION`), not *required*; the SPA never sends it, so the product's own client is last-write-wins (§3.5) | All |
| `API-4` | `GET /bootstrap` metadata-first boot (features, `authMode`, permissions) | Must | ✅ SHIPPED (W3/W6) | All |
| `API-5` | Retire the unversioned route surface — business routes require `/api/v1` | Should | ✅ SHIPPED 2026-07-25 (BACKLOG D3); the sunset apparatus deleted with it | All |
| `API-6` | Gateway / IAM drop-in: WSO2 gateway + Keycloak blueprints for Standard | Must (S) | 🟡 **PARTIAL by construction** — the seams are real (gateway trust mode, PKCE public client, D15 vendor neutrality) and the two blueprints exist; **nothing in the tree consumes or tests them, and no live gateway has ever verified them** (the README says so) | S |
| `API-7` | Java embedding API stability policy (SemVer, `@PublicApi`) | Must | ✅ SHIPPED as *policy* — ⚠ **no automated guard**: nothing asserts the marked surface did not shrink; 223 of 245 `since` values name the unreleased `4.0.0` (§3.13) | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`API-3`** read a flat `SHIPPED`; the concept page said "writes require `If-Match`". `ETags.requireMatch`
  (`inspecto/src/main/java/com/gamma/control/ETags.java:33-39`) rejects **a present, non-matching**
  precondition with `409`; an absent header passes. No file under `inspecto-ui/src/app` sends `If-Match`
  or `If-None-Match`, and `V1EnvelopeMetadata.etag` is declared and read nowhere. The concept page is
  corrected with this spec; the requirement stays a Must whose client half is unbuilt (§5).
- **`API-6`** read `SHIPPED (design + security module seams)`. A Must marked shipped on a *design* is the
  shape that makes a register untrustworthy: `docs/api/deployment/README.md` labels both blueprints
  "illustrative starting points — this sandbox has no running WSO2/Keycloak instance to verify against",
  and a repo-wide grep finds no test or main-code reference to either file.
- **`API-2`'s artifact contradicted `API-5`.** `docs/api/openapi-v1.json`'s `info.description` said "the
  entire legacy route table (121 routes) is also served under /api/v1 as-built" — false since 2026-07-25 —
  and pointed at the design at a pre-archive path. The file is served byte-equal at `GET /api/v1/openapi.json`,
  so the contradiction was on the wire. Corrected with this spec (the test compares the served bytes to the
  file, so editing the file is safe).
- **The route count has four incompatible published values** — 121 (the archived design's inventory),
  ~80 (`ADVANCED_GUIDE.md` §10), ~30 (`STAKEHOLDER_OVERVIEW.md`), 19 documented paths (the OpenAPI doc) —
  against **~332 built-in registrations** the code's own census records (`ControlApi.java:1086`), plus
  `ServiceLoader`-discovered module routes. The first two are dated snapshots; the stakeholder figure also
  names retired route vocabulary (`sources`, `flows`, `issues`) and is plan §8's cluster, reconciled at step 6.

**Two standing notes no status token can carry:**

1. ⚠ **`CP-01` (Control API v1) is ✅ for Personal, and five route families answer `503` there.** Both are
   true: the *transport* — envelope, errors, correlation, gzip, ETags, cursors — is edition-neutral core;
   the *route inventory* is not. Geo/link (5 paths), exchange (11), `/metrics` exposition, `/events*` and
   ~49 `/objects|/notes|/queues|/tags` paths are optional modules since EDG-01 (2026-09-07/08) and answer
   `503 CAPABILITY_UNAVAILABLE` naming the module — never `404`. A gateway handed `openapi-v1.json` is
   handed neither the stubs nor the module paths (§5).
2. ⚠ **The contract's own rules and the pending MAJOR disagree on what "breaking" means.** `docs/api/README.md`
   says *additive-only within v1; breaking ⇒ `/api/v2`*. The draft release notes carry wire-visible renames
   (`?flow=` → `?pipeline=` dual-read, `batch_id` → `consignment_id` on persisted surfaces) riding a MAJOR
   version, not a `/api/v2`. The resolution the repo has actually adopted, stated here so it stops being
   re-argued: **nothing after `v3.11.0` has shipped, so no `/api/v1` consumer exists outside this tree; the
   additive-only rule binds from the first release that publishes v1**, and until then a MAJOR may carry
   wire renames with dual-read. `api-stability.md` §Release baseline is the authority for that premise.

## 3. Specification

### 3.1 The 33 guidelines, and how the build answered them

The archived design (2026-07-06) adopted 33 product-owner guidelines. They are the *why* behind every row
below and are summarised here so they need not be read from the archive:

- **Adopted as written (24):** business capabilities over CRUD · bounded contexts as tags · metadata-first
  boot · declarative one-payload screens · DTO + mapper, no entity leakage, strong typing · one envelope ·
  hypermedia from the refs graph · **capability verbs, never roles** · bulk load · immutable ids ·
  queries-as-resources with server-side `$`-parameters and a declared Result Set · ETag + cursor · `202` +
  Run for anything long · server validates everything · **gateway transport-only** · idempotency +
  correlation · one error model · **additive-only within a major**.
- **Adapted, with the reason (6):** *version every object* → staged (`contentHash` + timestamps now;
  draft/published history behind store widening, MET-5) · *an Authentication API* → not ours, the IAM owns
  login; only the BFF `/auth/*` and `GET /bootstrap.session` · *a Scheduler API* → deliberately absent,
  schedule and trigger are Job fields · *never return 500* → a 500 stays truthful; the ban is on
  *unexplained* 500s · *HTTP/2–3* → at the gateway, the JDK core stays HTTP/1.1 · *OpenAPI-first* → adopted
  going forward, the existing surface documented as-built, not retro-specced.
- **Performance targets adopted:** bootstrap ≤ 500 KB compressed / ≤ 3 s cold; metadata ≤ 100 ms from cache
  (the 304 hot path); one bootstrap + parallel data queries per screen; gzip in-app, Brotli at the gateway;
  large results paginated, never unbounded.

### 3.2 The request pipeline

`ControlApi` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`) builds a JDK
`com.sun.net.httpserver.HttpServer` with a **virtual thread per request** and one catch-all `dispatch`
context on `/`. The pipeline is a composed middleware `Chain`, outermost first:

| Stage | What it does | Where |
|---|---|---|
| `correlation` | reads or mints `Correlation-ID` (`UUID.randomUUID()`), echoes it as a response header, puts it on the SLF4J MDC | `:552-566` |
| `cors` | only when `-Dcontrol.cors` is set; answers `OPTIONS` preflight | `:242-243` |
| `errorBoundary` | `ApiException` → its status + `errorCode`; any other exception → `500 INTERNAL` in the envelope (client disconnects excepted) | `:581-601` |
| `normalizePath` | `/api/v1/…` → strips the prefix, stamps `ATTR_SELF_PATH`; **any other `/api/…` → JSON `404 "unknown API version"`**; a bare path passes unmarked | `:627-641` |
| `idempotency` | `Idempotency-Key` replay for `POST/PUT/DELETE` (§3.5) | `Idempotency.java` |
| `bindSpace` | the `/spaces/{id}/…` seam — validate, rewrite, bind the `space` MDC | `multi-space.md` |
| `routeDispatch` | first-match over the route table; **`authenticate` → `authorize` → handler** per matched route; a non-v1, non-infra `GET` falls to `serveStatic`; any other unmatched request → JSON `404`/`405` | `:701-735` |

**The prefix rule (API-5, 2026-07-25).** `/api/v1` is the *only* business surface. The allow-list of routes
that stay unversioned is `isInfraRoute` (`:1119-1122`): `/health`, `/ready`, `/metrics`,
`/metrics/acquisition` — they have no v1 semantics. A bare `GET /objects` is a **SPA deep link** and gets
`index.html`; a bare `POST /objects` gets a JSON 404. The whole sunset apparatus — `Deprecation` / `Sunset`
headers, `-Dapi.legacy.routes`, `inspecto_legacy_api_requests_total`, the 30-day-at-zero soak — is
**deleted**, not disabled. `ControlApi.LOCAL_BASE_URL_PROP` stays a **root** URL with no version: the
version belongs to the client, exactly as the SPA composes `apiBaseUrl + /v1 + path`.

### 3.3 The envelope and the error catalog

Every `/api/v1` response is enveloped (`Envelope.java:30-79`):

```
success  {data, metadata:{timestamp, durationMs, apiVersion:"v1", pagination?}, links:{self}?, permissions?, diagnostics:{correlationId}}
error    {error:{errorCode, message, recoverable, correlationId, details?}}
```

`ErrorCodes` (`ErrorCodes.java:11-27`) is the whole catalog — twelve codes, pinned in both directions
against the OpenAPI `ErrorCode` enum by `ApiContractTest`: `MALFORMED_REQUEST` · `NOT_FOUND` ·
`METHOD_NOT_ALLOWED` · `PATH_JAIL_VIOLATION` · `CONFLICT` · `CONFLICT_STALE_VERSION` ·
`CONFIG_VALIDATION_FAILED` · `INTERNAL` · `CONTROL_PLANE_READ_ONLY` · `CAPABILITY_UNAVAILABLE` ·
`UNAUTHENTICATED` · `PERMISSION_DENIED`. `defaultFor(status)` maps a bare status to a code (`:30-41`).
Bodies ≥ 1024 bytes are gzipped when the client accepts it (`ApiContext.maybeGzip`, `:370-393`).
`permissions[]` on an authenticated envelope is `subject.capabilities() ∩ applicable` when a route declares
`resourcePermissions` — an affordance signal, never the security boundary (`SEC` §3.14). **A client that
adds the prefix and does not unwrap `data` compiles and fails at runtime** — the trap that caught the
intelligence module's `ControlPlaneClient`; the SPA's `v1Interceptor` unwraps at one seam (§3.15).

### 3.4 Routes: registration, the SPI, the guard, the order

`ApiContext` (`@PublicApi`, 413 lines) is what a route module sees: `get/post/put/patch/delete`, `hasRoute`,
`stub`, `withCapability` (wraps a handler in `requireCapability` → `403 PERMISSION_DENIED`),
`resourcePermissions`, `pagination`, `respondJson`. Routes are registered by **40 built-in `RouteModule`s**
(~332 registrations, "0 duplicates" per the census at `:1086`), then by **`ServiceLoader`-discovered
modules**, appended strictly after the built-ins (`:458-461`), then by the five **absent-module stub
groups** (`:466-470`) that answer `503` for a family the bundle lacks. `RouteModule` became a **public SPI
on 2026-09-07** (EDG-01 cell 3a) together with `Handler`, `ApiException`, `WriteGates` and `ApiContext`, all
`@PublicApi(since = "4.0.0")`. **A colliding `(method, pattern)` fails boot** (`register`, `:1102-1110`) —
first-match dispatch means a duplicate would otherwise never fail, only shadow. ⚠ **Registration order is
load-bearing**: `/config/spec/…` must register before the generic two-segment read; `GET /jobs/expressions`
before the `/jobs/{name}` regex. Shared plumbing lives in `RouteErrors` (the domain-exception → status
mappers), `SpiSlot<T>` (the first-wins edition-seam lookup) and `WriteGates`; ⛔ do not re-introduce per-class
copies, and do not generalise `SpiSlot` to the collect-all `ServiceLoader` sites — each carries semantic
per-element logic and was measured as not worth unifying (2026-08-27).

**The write gate order is house style** (`.claude/skills/endpoint/SKILL.md`): write-root unset → **503** ·
spec + `ConfigSafetyValidator` error → **422** · path jail → **403** · conflict → **409** · act atomically.
Twenty-eight files, 120 call sites, fully converged. A read route has no 503 gate. Every returned collection
is bounded with a `truncated` flag that reports the *true* total.

### 3.5 Optimistic concurrency and idempotency

- **ETags.** `ETags.of(body)` is a strong ETag `"sha256:<hex>"` over `ContentHash` — parity-pinned with the
  SPA's `content-hash.ts` by `ContentHashTest`'s vectors. `ETags.respond(ex, body)` is the one-line
  conditional-GET idiom: hash → `If-None-Match` 304 → set header → return. Adopters: `/bootstrap`,
  `GET /components/{type}/{id}`, and since 2026-07-24 the per-space singleton documents the UI re-reads on
  load (`/nav/menus`, `/settings/branding|geo`, `/config/icon-map`, `/access/roles|policies|catalog|profiles`);
  seven route files call `ETags.*`. List routes are **deliberately excluded** — a cursor page varies per query.
- **`If-Match`** is **honoured, not required**: `ETags.requireMatch` rejects a present, non-matching
  precondition with `409 CONFLICT_STALE_VERSION`; no header ⇒ the write proceeds. There is no `412` and no
  `428` anywhere. ⚠ The SPA never sends it (§3.15).
- **`Idempotency-Key`** (`Idempotency.java`): on any `POST`/`PUT`/`DELETE`, the first JSON response is cached
  **per `ControlApi` instance** — TTL 10 min, LRU cap 1000 (`:27-28`) — and a replay skips the handler and
  answers with `Idempotency-Replayed: true`. Per-instance by design (no cross-instance store; no test
  leakage); a multi-instance deployment would need a shared store that does not exist (§5). Long-running
  triggers answer **`202` + `{runId, status…}` + `Location`** and are polled by id (`GET /jobs/runs/{runId}`,
  `GET /runs/runs/{runId}` — the second path's doubled segment is a **known quirk kept until an API v2**,
  because renaming it would break every stored client link).

### 3.6 Cursor pagination

`Cursor` (`@PublicApi(since = "4.0.0")`, because it now crosses module boundaries) encodes an ordered keyset
as URL-safe Base64 over a JSON array; **decode is total** — a garbage cursor means "from the top", never a
`400`. A route declares `ApiContext.pagination(ex, …)` and the envelope emits
`metadata.pagination = {cursor, nextCursor, limit, total}` on v1 responses only. **Four adopters**, two of
each variant: store-side keyset `GET /jobs/runs` (`start_time DESC, run_id DESC`, dialect-neutral for DuckDB
and Postgres) and `GET /events` (rolling Parquet, paging the **full retained history**); in-route keyset over
an already-filtered set `GET /objects` (because the SEC-7d visibility post-filter would make an SQL keyset
leak or miscount) and `GET /jobs` (an in-memory materialised list, keyed by unique name). **Adoption policy
(2026-07-19):** a new list over an *unbounded* table MUST use `Cursor`; a bounded in-memory list MAY use
`ApiContext.paged` (limit/offset). Existing endpoints keep their behaviour; `RunRoutes.paged()` migrates
only on reported truncation pain. ⚠ The SPA consumes none of it (§3.15).

### 3.7 `GET /bootstrap`

One ETag'd document (`BootstrapRoutes.java`), every value **derived from what registered, never declared**
(contrast `authMode`, which is `-Dauth.mode` — `SEC` §2): `edition` · `features.{authoring, multiSpace,
exchange, geoLink, events, ops, authMode}` (`:58-85`; the module flags are `api.hasRoute(...)`) ·
`configSpecs` (every `ConfigSpecs.TYPES`) · `enumerations.{severities, attributeTypes, outputFormats}` ·
`spaces` (id + display name) · `session.{authenticated, actor, capabilities}`. `/bootstrap` is in
`PUBLIC_PATHS`; a Standard SPA re-reads it with a bearer to obtain effective capabilities.

### 3.8 The OpenAPI contract and what the test actually proves

`docs/api/openapi-v1.json` is OpenAPI 3.1, **one JSON file** with 15 bounded-context tags: shared
transport components (Envelope, ErrorObject + the ErrorCode enum, Pagination, Signal, Ref) and **19 paths /
24 operations** of as-built exemplars. JSON rather than the design's per-context YAML because the lean SBOM
has no YAML parser and the toolchain has no offline `$ref` bundler; splitting is reversible and deferred.
`docs/api/examples/` holds six canonical bodies (`envelope-health`, four `error-*`, `signal`).

`ApiContractTest` (`inspecto/src/test/java/com/gamma/control/ApiContractTest.java`) asserts exactly four
things: (a) `GET /api/v1/openapi.json` serves the file **byte-equal**; (b) `ErrorCodes.java`'s constants
equal the doc's `ErrorCode` enum, both directions; (c) every example satisfies its declared schema's
`required`/`enum` tree and the `EXAMPLES` manifest equals the on-disk set; (d) every operation carrying an
`x-probe` is hit live and its status and envelope shape checked. **It does not assert that every registered
route has a documented path** — coverage runs doc → live, not live → doc. The served copy is auth-gated
(deliberately not in `PUBLIC_PATHS`, 2026-08-25), lazily located working-dir → repo-root → module-dir,
and an absent file is a `404` with a log line, never a crash. **Contract rules (binding):** additive-only
within v1 (§2 note 2 for the pre-release caveat); every response is `Envelope` or `ErrorResponse`; every
request/response carries `Correlation-ID`; DTO shapes only, secrets masked `***` on the wire; the enum and
`ErrorCodes.java` stay in lockstep. **Contract-first workflow:** path + schemas first, example if the shape
is new, then the route by the `endpoint` skill, then `mvn -o clean test`.

### 3.9 Request-scoped attributes — never the JDK's exchange map

Everything a stage stamps for a later stage (`ATTR_EFFECTIVE_PATH`, `ATTR_SUBJECT`, `ATTR_RAW_BODY`, the
correlation id, idempotency, pagination, `ATTR_HELD_ROLES`, `ATTR_MATCHED_POLICY`) lives in
`ApiContext.REQUEST_SCOPES`, keyed by exchange **identity** and dropped in the outermost `finally`. ⛔ Never
`HttpExchange.set/getAttribute`: on any pre-JDK-26 runtime — the bundle embeds GraalVM 25 — that map is the
*shared* `HttpContext` map, one for every in-flight request; the route-matching path once rode it and
concurrent bursts crossed requests (53 of 1200 served another request's file; BUNDLE-1, `fb1511c0`). The
reactor gate runs on JDK 26, where the map is per-exchange, so **it structurally cannot catch a regression**;
the pins are `ExchangeAttributeScopeTest`, `ApiContextV1DerivationTest` (v1-ness derived per request, never
cached), and a concurrent probe against the *shipped* runtime.

### 3.10 Static SPA serving

`serveStatic` (`:889-901`) serves `-Dui.dir`: a path with an extension gets its MIME type, an extensionless
deep link gets `index.html`, and the target is jailed by `startsWith(uiDir)`. `writeFile` (`:921-949`) sets
`Cache-Control: no-cache` plus a size/mtime/name ETag on **every** asset and honours `If-None-Match` — the
deliberate cost of a stale-chunk bug that long-lived caching once caused. `-Dui.static.log=DEBUG` logs
`served=<file>` beside the URL so a crossed pairing (§3.9) is visible in one grep. Routing `serveStatic`
through `PathJail.contains` was considered and **left** — a posture change that needs an operator call
(`BACKLOG.md` §6).

### 3.11 Editions: absent is `503`, and the flag is derived

A route family an edition does not bundle is registered by an **absent-module stub** that answers
`503 CAPABILITY_UNAVAILABLE` naming the module — never `404`, so a client can tell "not installed" from
"wrong path". `features.*` in `/bootstrap` is `api.hasRoute(...)`, so the flag cannot disagree with the
build; the SPA hides navigation on it and renders an explained panel, never a toast. The families:
geo/link (`inspecto-geo-link`, 5 paths), exchange (`inspecto-exchange`, 11), `/metrics` exposition
(`inspecto-metrics`), `/events*` (`inspecto-events`; the core keeps `/audit/search|export`), and the
objects domain (`inspecto-ops`, ~49). Gating is **`ServiceLoader` modules, never `-D` switches** (operator,
2026-09-07). The core `AuditLogRoutes` stay in every edition, fail-closed so they cannot serve as the events
feed.

### 3.12 The gateway and IAM blueprints (API-6)

`docs/api/deployment/`: `wso2-api-definition.yaml` wraps `openapi-v1.json` for a transport-only gateway —
routing, throttling tiers, CORS, OAuth2 scope enforcement at most, **no business logic, authz, validation or
audit at the edge**; `keycloak-realm-blueprint.json` defines the `inspecto-spa` PKCE public client and the
role mapper; the README maps the seams (`X-JWT-Assertion` trust mode, `-Dauth.oidc.*`, the BFF cookie). Since
D15 there is **no vendor of record** — these are examples of a compliant configuration, not a choice. ⚠ They
are **documentation-only**: nothing loads, tests or diffs them, and no live gateway has verified them. The
blueprint models neither the per-edition `503` stubs nor the optional-module paths (§5).

### 3.13 The Java embedding API and its stability policy

`@com.gamma.api.PublicApi` (`inspecto-api`, `CLASS` retention, informational `since`) marks the surface an
embedder or plugin may depend on: removed or changed incompatibly only on a MAJOR, additions on a MINOR,
everything unmarked internal. **The marker appears in two spellings** — imported `@PublicApi` and
fully-qualified `@com.gamma.api.PublicApi` — so a grep for one under-counts; together they mark **216
files**, and of 245 `since` values **223 say `4.0.0`**, 22 name a version that shipped (`1.0.0` … `3.10.0`).

**The release baseline governs every rename question.** `git describe` on `master` is
`v3.11.0-<n>-g…`: the newest ancestor release is **`v3.11.0`** (2026-06-03); `v3.12.0` is a tag on the
retired `3.x` line and can never be an ancestor; `v4.0.0` / `v4.0.0-RC1` were deleted with the `4.x` branch
on 2026-08-17. Therefore `since = "4.0.0"` means *"will become public in 4.0.0"*, and **an element that has
never been published may still move, rename or change freely** — `@PublicApi` alone does not make a change
breaking. That premise has been written down and refuted three times (Source→Collector, the <!-- vocab-allow: names the Source→Collector rename itself -->
`ConsignmentProcessor` widening, the Phase C cycle cuts): *check whether the element exists in `v3.11.0`*
(`git ls-tree -r --name-only v3.11.0 | grep …`) before treating a relocation as a break. ⚠ **There is no
automated guard** — no japicmp, no test that the marked surface did not shrink; the policy is prose.

The published surface today: the plugin SPI (`StreamingFileIngester`, `RecordSink`, `IngestResult`,
`PipelineConfig` and its records), the embedder line (`CollectorProcessor`, `MultiCollectorProcessor`,
`PipelineConfig.load`), the service and control line (`CollectorService`, `EnrichmentService`, `ControlApi`,
`JobService`, `JobConfig`, `ReportService`, `DbStatusStore`, `AssistAgent`), and the **agent-consumed core
surface frozen for 4.0.0** — a *freeze*, not a facade: a parallel `agent.spi` DTO layer was refused because
the modularisation is reactor-internal. A JPMS `module-info.java` was declined: a shaded fat JAR over
automatic modules is high-risk for little gain. The **release notes for the pending MAJOR** accrue in
`api-stability.md` and every `feat!:` adds a line in the same commit.

### 3.14 Platform Services — the API a Job sees

`PlatformServices` (`com.gamma.job`, `@PublicApi`) is a **flat typed lookup** — `find(Class)`, `get(Class)`,
`granted()` — built once at boot and filtered per consumer by its declared `requires: [...]`, validated at
registration (a pack fails atomically on an unknown id), substituted under a dry run. v1 menu:
`notifications`, `incidents`, `schema`, `consignment-status`, `alerts`; `requires` travels on
`GET /jobs/types[/{id}]`. ⛔ It must not grow scopes, proxies, lifecycle or annotation injection — the moment
it does it is the dependency-injection framework this codebase deliberately does not have. Mechanism:
`platform-services.md`.

### 3.15 The SPA client

`apiUrl(path)` = `environment.apiBaseUrl + "/v1" + path` (`inspecto-ui/src/app/inspecto/api/api-base.ts:9-11`;
`/api` in dev behind the proxy, `''` in the built bundle). Interceptor order (`app.config.ts:28-31`):
**`v1Interceptor`** (unwraps `{data, metadata, diagnostics}` → `data` at the one seam; warns on
`apiVersion` skew) → `spaceInterceptor` → `authInterceptor` (Bearer + 401 → silent refresh → retry) →
`inspectoErrorInterceptor` (connectivity only). `apiErrorMessage` reads the v1 error object with a legacy
fallback. `Correlation-ID` is **read** from `diagnostics` and shown on the Events, Notification and
Incident-detail screens; it is never sent. DTOs are hand-written per service — **no OpenAPI-generated
client and no drift check** between `openapi-v1.json` and TypeScript; wire vocabularies are pinned instead by
eight `*.contract.json` files each compared by a Java `*ContractTest` and a TS `.spec.ts` (§8.4). ⚠ Three
contract features have **zero client consumers**: `If-Match`/`If-None-Match` (never sent),
`metadata.pagination` (never read — the data table's "Load more" is not cursor-driven), and
`Idempotency-Key` (never sent). ⚠ `error.interceptor.ts:14-16` still says "the backend is fully open, no 401
handling" — stale since W6d; the handling lives in `auth.interceptor.ts`.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The contract

| Date | Decision | Why |
|---|---|---|
| 2026-07-06 (product) | **URI versioning** (`/api/v1`), not media-type versioning | a gateway routes, throttles and analyses by prefix; media types are opaque to it |
| 2026-07-06 | **One envelope, one error catalog**, `Correlation-ID` on every exchange, gzip ≥ 1 KiB | a client should never have to guess a shape; a log line should always be joinable to a request |
| 2026-07-06 | **Contract-first**, one JSON OpenAPI file with tags — not per-context YAML | no YAML parser in the lean SBOM, no offline `$ref` bundler; one file is checkable on both sides with zero new deps; splitting is reversible |
| 2026-07-06 | The gateway is **transport only** — no business logic, authz, validation or audit at the edge | the edge is replaceable; the contract must be enforceable without it |
| 2026-07-06 | Strong ETag `"sha256:<hash>"` over `ContentHash`, parity-pinned with the SPA; `If-Match` stale ⇒ **409** (the code's own `CONFLICT_STALE_VERSION`), not 412 | one hash on both sides; a stale write is a conflict in the domain's vocabulary |
| 2026-07-06 | Async is **`202` + Run + `Location`**; `Idempotency-Key` replays at most once, per instance | a trigger is a Run, and the Run is the resource |
| 2026-07-06 | *Never return 500* adapted: a 500 stays **truthful** | the ban is on *unexplained* 500s; lying about a failure is worse |
| 2026-07-06 | No Authentication API, no Scheduler API | the IAM owns login; schedule and trigger are Job fields |
| 2026-07-07 | The UI migrates to v1 in **one global flip**, not context by context | W1's dispatch seam was fully path-generic; a partial flip would have doubled every shape |
| 2026-07-07 | `GET /runs/runs/{runId}` as the pipeline poll path | single segment, no route collision; **kept until an API v2** because stored client links |
| 2026-07-19 | Cursor **adoption policy**: unbounded table ⇒ keyset MUST; bounded in-memory ⇒ offset MAY; existing endpoints on demand | keyset cost is only worth paying where offset paging breaks |
| 2026-07-19 | `GET /objects` pages **in-route** over the visibility-filtered set | an SQL keyset under the SEC-7d post-filter would leak or miscount; objects are low-volume by design |
| 2026-07-24 | `ETags.respond` extended to per-space **singleton** documents; lists excluded | a cursor page varies per query; a singleton the UI re-reads on every load is the 304 hot path |
| 2026-07-25 (**D3**) | **Retire the unversioned surface; delete the sunset apparatus**; override the 30-day soak | deleting the machinery retires nothing; there is no live deployment and every in-repo caller moved in the same change |
| 2026-07-25 | `LOCAL_BASE_URL_PROP` stays a **root** URL | the version belongs to the client |
| 2026-08-25 | Serve `GET /api/v1/openapi.json` **byte-equal and auth-gated**; absent file ⇒ 404, never a crash | a gateway can fetch its own contract; the document is not public information |

### Routes and the runtime

| Date | Decision | Why |
|---|---|---|
| 2026-06-16 | Remove the token plane (`-Dcontrol.token`, per-route `Scope`) from the core | auth is a module (`SEC`); the core is auth-free |
| 2026-07-25 | Request state lives in **`ApiContext.REQUEST_SCOPES`**, never the exchange map | pre-JDK-26 the exchange map is shared across requests — proven by 53 crossed responses in 1200 on the shipped runtime |
| 2026-08-06 (operator) | **`Job` un-banned** — Jobs routes and `*_job.toon` stay canonical | reversed the 2026-08-05 amendment that demoted Jobs to internal vocabulary |
| 2026-08-07 | Fixed sub-paths register **before** the regex they would otherwise match (`/jobs/expressions` before `/jobs/{name}`); `secret` masking at the **response boundary**, not in the model | first-match dispatch; masking in the model would corrupt bundle export |
| 2026-08-10 | Maven artifactIds `file-processor-*` → `inspecto-*`; the bundle name unchanged | a separate, unmade decision |
| 2026-08-27 | Shared plumbing consolidated: `RouteErrors`, `SpiSlot<T>`, `WriteGates`; `PipelineRoutes` and `ConfigRoutes` split; ⛔ `SpiSlot` not generalised to collect-all sites | measured: the collect-all sites carry per-element semantics |
| 2026-09-07 (EDG-01 3a) | **`RouteModule` is a public SPI**; discovered modules append after the built-ins; a duplicate `(method, pattern)` **fails boot** | first-match dispatch means a duplicate would silently shadow, never fail |
| 2026-09-07 (operator) | Gating is **`ServiceLoader` modules**, never `-D` switches; an absent family answers **`503`, never `404`** | a switch leaves the code in the bundle; a client must distinguish "not installed" from "wrong path" |
| 2026-09-08 | `features.*` in `/bootstrap` are **derived** from `hasRoute` | the flag cannot disagree with the build |

### The embedding API and releases

| Date | Decision | Why |
|---|---|---|
| 2.0.0 era | `@PublicApi` marker + prose policy, **no `module-info.java`** | JPMS over a shaded fat JAR with automatic modules is high-risk for little gain |
| 3.11.0 | `FileIngester` removed for `StreamingFileIngester` — a **deliberate exception** to the within-major promise | taken before wide external adoption |
| 2026-07-07 | The agent-consumed core surface is **frozen** `since = "4.0.0"`, not wrapped in an `agent.spi` facade | the modularisation is reactor-internal; a facade duplicates shapes for no payoff |
| 2026-07-16 | 200 `since` values `4.1.0 … 5.8.0` corrected to `4.0.0` | those versions never existed |
| 2026-08-17 (operator) | `4.x` and its tags deleted; **`v3.11.0` is the release baseline**; trunk is `4.0.0-SNAPSHOT` | 4.0.0 never reached production |
| 2026-08-27 | **`@PublicApi` alone does not make a change breaking** — check the element against `v3.11.0` | the premise had been inherited and refuted three times; measure it |
| 2026-09-07 | The `X-Actor` removal gate is **the next MAJOR tag** | the API-v1 sunset it used to cite was deleted 2026-07-25 |
| 2026-09-08 | The archived design is **provenance**; this spec is the design of record | `REQUIREMENTS.md` §8's rule: read the archive for *why*, never for what is built |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| Cursor pagination on further list families; `ETags.respond` on further singleton reads; the Standard jlink runtime vs Nimbus re-check | `BACKLOG.md` §3 *API v1* (P2) | Demand-driven by policy; `RunRoutes.paged()` migrates only on reported truncation pain |
| `X-Actor` header path removal | `BACKLOG.md` §2 *X-Actor full removal*; `EDITIONS.md` SEC-11 | Gate: the next MAJOR tag |
| Release notes for the pending MAJOR | `BACKLOG.md` §2 *Release notes for the next MAJOR* | The draft accrues in `api-stability.md`; the tag is not cut |
| X5 cross-lane StepInfo envelope | `BACKLOG.md` §2 *X5* | ~1 KB pointer + schema + diagnostics, deferred to the MAJOR |
| Platform Services Stage 2 (open Step-kind registry) and Stage 3 (pack-contributed services); no timeout/cancellation either side | `BACKLOG.md` §4 *Platform Services* | Design only |
| Structured (non-SQL) queries from an external consumer; more `$`-resolvers | `BACKLOG.md` §3 *Queries / BI* (P3) | `422` on non-SQL is the deliberate contract (R6) |
| Draft/published version history as designed (guideline 7, W3b) | `REQUIREMENTS.md` MET-5 (partial) | Behind store widening |
| Per-context split of `openapi-v1.json` | `docs/api/README.md` (deferred, reversible) | Only if the file becomes unwieldy |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-NOPROOF-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| 🟡 **OpenAPI covers a fraction of the live surface** — **the assertion is SHIPPED 2026-09-09; the DECISION is still owed** | `openapi-v1.json`: 19 paths / 24 operations. Live: **266 registrations + 73 absent-module stubs**, measured by `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted` — **9.0 %**, not the 6 % stated here before (that divided *paths* by *registrations*) | ✅ The owed *live → doc coverage assertion* now exists: it measures the gap, **prints it pass or fail**, ratchets the documented counts so an operation cannot quietly disappear, and floors the live count so the figure cannot be computed over nothing. Mutation-proven both ways (removing `/health` fails naming 18/23; raising the live floor above the real count fails naming 266). ⛔ **Still owed, and NOT a guard's call: whether to document the remaining surface or to adopt exemplar coverage deliberately.** ⚠ A per-route live → doc comparison is unavailable — the route table holds compiled patterns (`/config/pipeline/([^/]+)`), OpenAPI holds templates (`/config/pipeline/{name}`). ⚠ And the 266 is the **core** surface: it does not move with `-Pedition-*`, because optional modules depend on the core rather than the reverse |
| **The SPA sends no `If-Match`** | zero hits in `inspecto-ui/src/app` | `API-3` is a Must whose client half is unbuilt: two operators editing one Component are last-write-wins in the product's own UI |
| **The SPA reads no `metadata.pagination`** | zero consumers of `nextCursor` | Four paginated endpoints page for `curl` only; the data table's "Load more" is not cursor-driven |
| **No `@PublicApi` surface guard** | no japicmp, no scanning test | `API-7` is policy in prose; a MINOR that removes a marked member passes the build |
| **The gateway blueprints are untested documentation** | no reference in `src/`; README's own caveat | `API-6` is a Must (S); the WSO2 definition also omits the `503` stubs and the optional-module paths |
| **Idempotency replay is per instance** | `Idempotency.java:32` | A second instance behind a gateway replays nothing; fine today (single instance), a trap for E1 |
| **`error.interceptor.ts` comment is stale** | `:14-16` "fully open, no 401 handling" | A reader editing the chain would place 401 handling in the wrong file |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 Media-type versioning — REFUSED (2026-07-06)

Harder to route, throttle and analyse at a gateway. `/api/v1` in the path, platform-wide; the version
belongs to the client, and the server's own base URL carries none.

### 6.2 A server framework (Spring / Quarkus) — REFUSED (standing)

Incremental hardening on the framework-free JDK `HttpServer` core; at the target user counts a framework
buys nothing the IAM and two small libraries do not, and a lean dependency tree is a compliance asset. The
same reasoning refused OPA for authorization (`SEC` §6.3) and a message broker for notifications (`INC` §6.3).

### 6.3 Per-context YAML contract files — REFUSED (2026-07-06)

No YAML parser in the lean SBOM, no offline `$ref` bundler, and the Angular workspace imports JSON
natively. One file, tags per context; the split is reversible when the file becomes unwieldy.

### 6.4 Business logic, authorization, validation or audit at the gateway — REFUSED (guidelines 26/27)

The edge is replaceable and vendor-neutral (D15). OAuth2 scope enforcement at most; trusting an unsigned
gateway assertion, or the gateway's JWT without re-validation, was refused twice (`SEC` §6.6).

### 6.5 `if (edition == …)` in handlers; `-D` capability switches — REFUSED

The `Authenticator` SPI, the `AccessDecider` SPI and the `RouteModule` SPI are the only edition seams. A
switch leaves the code in the bundle; an absent family is a module that did not register and answers `503`.

### 6.6 `HttpExchange` attributes for request state — REFUSED (BUNDLE-1)

Shared across requests on the shipped runtime; 53 crossed responses in 1200. `ApiContext.REQUEST_SCOPES`,
keyed by exchange identity, is the only home, and the reactor gate cannot catch a regression because it runs
on JDK 26.

### 6.7 Generalising `SpiSlot<T>`; a `PlatformServices` that grows scopes — REFUSED (2026-08-27 / 2026-08-10)

The collect-all `ServiceLoader` sites each carry per-element semantics and were measured as not worth
unifying. A `PlatformServices` with scopes, proxies or lifecycle is a DI framework this codebase does not
have. A decorative `requires: [incidents]` on `alert.evaluate` was refused for the same honesty reason: remove
it and Incidents still open.

### 6.8 A JPMS descriptor; a parallel `agent.spi` DTO layer — REFUSED

Shaded fat JAR over automatic modules: split packages and shade interactions for no gain over the marker. The
agent surface is a *freeze*, not a facade, because there is one deployable.

### 6.9 `serveStatic` through `PathJail.contains` — LEFT (2026-08-26)

A posture change that needs an operator call; recorded in `BACKLOG.md` §6 as "do not build it".

### 6.10 The `/runs/runs/{id}` rename; `RunRoutes.paged()` migration — REFUSED until v2 / demand

Renaming would break every stored client link; migrating an offset pager with no reported pain is churn.

### 6.11 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| Legacy unversioned routes served byte-for-byte beside v1, retired after a 30-day-at-zero soak (W8, 2026-07-08) | `/api/v1` the only business surface; apparatus deleted; soak overridden (D3, 2026-07-25) | `api-v1.md`; the archived removal plan |
| `ControlApiLegacySunsetTest` | `ControlApiVersionedSurfaceTest` | `api-v1.md` |
| Per-context v1 migration | one global flip | archived design §10 |
| The design's full `metadata.etag` / `links` / `permissions` envelope with per-resource ∩ state permissions | session-wide `permissions[]`, refined by SEC-7b `resourcePermissions` | `api-v1.md` |
| `docs/api/openapi-v1.json` per context | `openapi-v1.json` with tags | `docs/api/README.md` |
| `KeycloakTokenRelay`, the issuer-derived token endpoint | `OidcTokenRelay`, mandatory `-Dauth.oidc.tokenEndpoint` (D15) | `SEC` §4 |
| Scoped token auth in the core (`-Dcontrol.token`, `/connect`) | the auth-free core + SPIs (2026-06-16) | `SEC` §3.1 |
| `/flows`, `/sources`, `/issues`; the ingest-ops `/pipelines` | `/pipelines`, `/collectors`, `/incidents`; `/runs` | `GLOSSARY.md` §13 |
| "Job survives as internal scheduling vocabulary only" (2026-08-05) | Jobs un-banned, routes canonical (2026-08-06) | `GLOSSARY.md` §13 |
| Constructor injection of engine facilities into built-in Jobs; a setter for the service registry | Platform Services; a constructor parameter | `platform-services.md` |
| `@PublicApi since` values `4.1.0 … 5.8.0` | all `4.0.0` | `api-stability.md` |
| The 2026-08-11 "`4.x` as branch of record" | `master`; `4.x` deleted 2026-08-17 | `BRANCHING.md` |
| `NodeTargets` → `com.gamma.ops.AnnotationKinds` → `com.gamma.objects.AnnotationKinds` | relocated twice; core vocabulary | `api-stability.md` |
| `control-api.md`'s "legacy routes stay byte-for-byte, counted by `recordLegacyUsage`" and "curation stays an open question" | corrected with this spec — both were superseded on 2026-07-25 | `control-api.md` |
| `api-stability.md`'s "v4.8: … alongside byte-for-byte legacy routes" and its "(2.0.0)" surface heading | corrected with this spec — no v4.8 ever existed; the table is the *pending* 4.0.0 surface | `api-stability.md` |
| `openapi-v1.json` `info.description`'s "the entire legacy route table (121 routes) is also served" | corrected with this spec | `docs/api/openapi-v1.json` |
| `STAKEHOLDER_OVERVIEW.md`'s "~30 routes … sources, flows, issues" | plan §8 cluster — reconciled at step 6, not here | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The v1 contract in long form — envelope, W1–W8, `permissions[]`, OpenAPI serving, ETags, cursors and their four adopters | `docs/okf/backend/control-plane/api-v1.md` (`Concept`) | `ControlApi.java` | the account §3.3, §3.5, §3.6 and §3.8 were distilled from |
| The server itself — launch, `dispatch`, request-scoped attributes, route families, shared plumbing, the 2026-08-27 splits | `docs/okf/backend/control-plane/control-api.md` (`Concept`) | `ControlApi.java` | §3.2, §3.4, §3.9 |
| `@PublicApi`, the release baseline, the pending MAJOR's release notes, the published surface tables | `docs/okf/backend/control-plane/api-stability.md` (`Reference`) | `PublicApi.java` | §3.13 and every rename question |
| The Platform Services seam, the registry, dry-run substitution, the pack scaffolder | `docs/okf/backend/control-plane/platform-services.md` (`Concept`) | `PlatformServices.java` | §3.14 |
| The machine-readable contract, its rules and the contract-first workflow | `docs/api/README.md` · `docs/api/openapi-v1.json` · `docs/api/examples/` | — | what `ApiContractTest` reads |
| The gateway and IAM blueprints and the seams they assume | `docs/api/deployment/README.md` · `wso2-api-definition.yaml` · `keycloak-realm-blueprint.json` | — | §3.12 — illustrative, untested |
| The space seam after the version | `docs/okf/backend/control-plane/multi-space.md` (`Concept`) | — | `/api/v1/spaces/{id}/…` |
| The SPA's API conventions and service catalogue | `docs/okf/frontend/conventions/api-and-data.md` (`Convention`) · `docs/okf/frontend/services/api-services.md` (`Reference`) | `inspecto-ui/src/app/inspecto/api` | §3.15 |
| The house-style route recipe | `.claude/skills/endpoint/SKILL.md` | — | the gate order and the mandatory real-HTTP test |

---

## 8. Verification

### 8.1 Contract-surface tests — `inspecto/src/test/java/com/gamma/control/` (default reactor)

| Class | Proves |
|---|---|
| `ApiContractTest` | the four assertions of §3.8: byte-equal serving, `ErrorCodes` ↔ enum both ways, examples vs schemas + manifest, `x-probe` operations live |
| `ControlApiVersionedSurfaceTest` | API-5: a bare business path is not served; `/api/<non-v1>` is a JSON 404; infra probes stay unversioned |
| `ApiContextV1DerivationTest` | v1-ness derived per request from the URI, never cached — the shared-map race guard |
| `ExchangeAttributeScopeTest` | two exchanges sharing one JDK map stay isolated; the shared map stays empty |
| `RouteModuleDiscoveryTest` | a `ServiceLoader`-discovered `RouteModule` answers over real HTTP; a duplicate `(method, pattern)` is refused at boot |
| `CapabilityManifestTest` | every `withCapability` site ↔ the manifest, both directions |
| `ControlApiStaticAndCorsTest` | SPA fallback, MIME, `no-cache` + ETag, CORS |
| `ControlApiBindTest` | `-Dcontrol.bind` resolution |
| `ContentHashTest` | the hash vectors the SPA's `content-hash.ts` must reproduce |
| `CursorTest` | round trip, URL safety, total decode |
| `ControlApiAsyncV1Test` | `202` + `{runId}` + `Location`; `Idempotency-Key` replay |
| `ControlApiMetadataV1Test` | envelope metadata shapes |
| `ControlApiJobsPageTest` · `ControlApiEventsPageTest` | keyset paging incl. a shared-timestamp id-tiebreak resume |
| `ControlApiAuthV1Test` · `ControlApiAuthSessionV1Test` | the gate order's first two stages (`SEC` §8.1) |
| ~90 further `ControlApi*Test` classes | one real-HTTP class per route family — the `endpoint` skill's mandatory test |

### 8.2 The SPA client — vitest

Twenty-one specs under `inspecto/api/` (`v1.interceptor.spec.ts`, `auth.interceptor.spec.ts`,
`space.interceptor.spec.ts`, `session.service.spec.ts`, per-service specs) cover envelope unwrap, version-skew
warning, error mapping, Bearer attach and refresh-retry, space-scope rewrite and per-service optimistic
mutation paths.

### 8.3 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `inspecto-ops/src/main/resources/META-INF/services/com.gamma.control.RouteModule` | `ObjectRoutes`, `NoteRoutes`, `QueueRoutes`, `TagRoutes` — the first external adopters of the public SPI |
| the same service name in `inspecto-geo-link`, `inspecto-exchange`, `inspecto-metrics`, `inspecto-events` | the other four gated families |

### 8.4 Cross-language wire vocabularies — `inspecto-ui/src/app/inspecto/contracts/`

Eight committed JSON contracts, each compared by a Java `*ContractTest` and a TypeScript spec so neither
side moves alone: `attribute-spec` (`FindingsSpecContractTest`), `bind-kinds` (`BindKindHomeContractTest`),
`processor-catalog` (`ProcessorCatalogContractTest`), `sql-functions` (`RecordTransformContractTest` — both
sides compile SQL independently), `node-attributes`, `expression-guard`, `measure-grammar`, `step-types`.
Build-time *generation* of these artifacts was refused: a generated artifact silently absorbs the drift the
test exists to catch.

### 8.5 Guards

`ApiContractTest` is the contract guard; `CapabilityManifestTest` the gate guard; the vocabulary guard keeps
retired route words (`flows`, `sources`, `issues`) out of the docs; `check-doc-links` keeps `docs/api/`
and the blueprints reachable.

### 8.6 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No live → doc coverage assertion** | `ApiContractTest` iterates the document, never the route table |
| **No `@PublicApi` surface-shrink guard** | no japicmp, no scanning test in any module |
| **No client-side test of `If-Match` or cursor consumption** | there is no such client code to test |
| **Nothing exercises the gateway or IAM blueprints** | no reference under `src/`; the README's own caveat |
| **Idempotency across instances is untested** | the store is per instance by design |
| **The shared-exchange-map race cannot fail on the reactor JDK** | pinned structurally and by a probe on the shipped runtime, not by the gate |
