---
type: Capability
title: Control API (API) — Specification
description: Part 2 of 3 of Control API (API): Specification
tags: [api, capability, control-api, v1, envelope, error-codes, etag, idempotency, cursor, openapi, route-module, public-api, semver]
timestamp: 2026-09-08T00:00:00Z
---

# Control API (API) — Specification

Part 2 of 3 of [Control API (API)](plan-control-api.md) · previous: [Purpose, scope and requirements of record](plan-control-api-scope.md) · next: [Decisions, not built, refused and verification](plan-control-api-decisions-verification.md).

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
| `correlation` | reads or mints `Correlation-ID` (`UUID.randomUUID()`), echoes it as a response header, puts it on the SLF4J MDC | `ControlApi.correlation` |
| `cors` | only when `-Dcontrol.cors` is set; answers `OPTIONS` preflight | `ControlApi.cors` |
| `errorBoundary` | `ApiException` → its status + `errorCode`; any other exception **or `Error`** → `500 INTERNAL` in the envelope with a generic message + the correlation id (§4 `ERR-500-GENERIC`), logged at ERROR with the `Correlation-ID` and the stack (client disconnects excepted; an `OutOfMemoryError`/`InternalError` is then rethrown, §4 2026-09-28) | `ControlApi.errorBoundary` |
| `normalizePath` | `/api/v1/…` → strips the prefix, stamps `ATTR_SELF_PATH`; **any other `/api/…` → JSON `404 "unknown API version"`**; a bare path passes unmarked | `ControlApi.normalizePath` |
| `idempotency` | `Idempotency-Key` replay for `POST/PUT/DELETE` (§3.5) | `Idempotency.java` |
| `bindSpace` | the `/spaces/{id}/…` seam — validate, rewrite, bind the `space` MDC | `multi-space.md` |
| `routeDispatch` | first-match over the route table; **`authenticate` → `authorize` → handler** per matched route; a non-v1, non-infra `GET` falls to `serveStatic`; any other unmatched request → JSON `404`/`405` | `ControlApi.routeDispatch` |

**The prefix rule (API-5, 2026-07-25).** `/api/v1` is the *only* business surface. The allow-list of routes
that stay unversioned is `isInfraRoute`: `/health`, `/ready`, `/metrics`,
`/metrics/acquisition` — they have no v1 semantics. A bare `GET /objects` is a **SPA deep link** and gets
`index.html`; a bare `POST /objects` gets a JSON 404. The whole sunset apparatus — `Deprecation` / `Sunset`
headers, `-Dapi.legacy.routes` (removed), `inspecto_legacy_api_requests_total`, the 30-day-at-zero soak — is
**deleted**, not disabled. `ControlApi.LOCAL_BASE_URL_PROP` stays a **root** URL with no version: the
version belongs to the client, exactly as the SPA composes `apiBaseUrl + /v1 + path`.

### 3.3 The envelope and the error catalog

Every `/api/v1` response is enveloped (`Envelope.java`):

```
success  {data, metadata:{timestamp, durationMs, apiVersion:"v1", pagination?}, links:{self}?, permissions?, diagnostics:{correlationId}}
error    {error:{errorCode, message, recoverable, correlationId, details?}}
```

`ErrorCodes` (`ErrorCodes.java`) is the whole catalog — nineteen codes, pinned in both directions
against the OpenAPI `ErrorCode` enum by `ApiContractTest`: `MALFORMED_REQUEST` · `NOT_FOUND` ·
`METHOD_NOT_ALLOWED` · `PATH_JAIL_VIOLATION` · `CONFLICT` · `CONFLICT_STALE_VERSION` ·
`CONFIG_VALIDATION_FAILED` · `INTERNAL` · `CONTROL_PLANE_READ_ONLY` · `CAPABILITY_UNAVAILABLE` ·
`UNAUTHENTICATED` · `PERMISSION_DENIED` · `RATE_LIMITED` (429) · `NOT_SUPPORTED` (501) ·
`PAYLOAD_TOO_LARGE` (413) · `INTEGRITY_VIOLATION` (500) · `STORE_BUSY` (503, a store lock's bounded wait timed
out — the Pending Change store). `defaultFor(status)` maps a bare status to a code.
**0 of 1155** `new ApiException(` sites under `*/src/main` still take that default (2026-09-30: the last 15 —
all 422s in `inspecto-geo-link` `AdmiraltyGrade` / `InvestigationCoverageRoutes` / `PatternRoutes` /
`WorkingSetRoutes` — now pass `CONFIG_VALIDATION_FAILED`, the status default, so no wire change; the derive below
still prints 4 lines because `ImportRollback` / `PendingChangeRoutes` / `ServerFaults` / `TemplateSeedGate` pass a
*computed* code, not a default) — after the `BiRoutes` / `DatasetRoutes` / `PipelineListRoutes` / `BiTemplates` /
`AuditLogRoutes` / `AssistRoutes` / `ViewRoutes` / `SignalRoutes` / `DeliveryStatusRoutes` / the five `Absent*Routes`
and 13 smaller files (incl. `inspecto-ops` `NoteRoutes`/`OpsEngine`, `inspecto-observability` `EventRoutes`,
`inspecto-exchange` `ExchangeRoutes`) slice — 82 sites, every one the status default, so no wire change (the
`Absent*Routes`/assist/ops 503s are `CAPABILITY_UNAVAILABLE`; `DeliveryStatusRoutes`' public-callback 404/422 and
its non-durable-receipts 409 keep `NOT_FOUND`/`CONFIG_VALIDATION_FAILED`/`CONFLICT`). Before that (2026-09-25), the
`AlertRoutes` / `CatalogRoutes` / `DataSourceRoutes` / `PipelineHistoryRoutes` / `RequirementRoutes` /
`SpaceComparisonRoutes` / `WriteGates` / `BundleRoutes` / `DecisionRoutes` slice — 56 sites, all the status default
but one: `DataSourceRoutes`' "no write root configured" 503 is now `CONTROL_PLANE_READ_ONLY` (was
`CAPABILITY_UNAVAILABLE`), a wire change asserted over HTTP by `ControlApiDataSourceExportTest` and noted in
[api-stability](../api/plan-api-stability.md); `AlertRoutes`' two 503s are an absent alert engine
(`CAPABILITY_UNAVAILABLE`), and `WriteGates`' 422/409 gates keep their default codes, so its ~43 caller files are
unchanged (its write-root 503 and path-jail 403 were already explicit); the `SettingsRoutes` / `PipelineSettingsRoutes` / `ConfigWriteRoutes` / `ComponentAccess` + `inspecto-geo-link`
`InvestigationTemplateRoutes` / `InvestigationMeasureRoutes` / `GeoRoutes` slice — 56 sites, every one the status
default, so no wire change: the one 403 is the template store's path-containment escape (`PATH_JAIL_VIOLATION`),
the one 503 is an absent alert engine (`CAPABILITY_UNAVAILABLE`), and `ComponentAccess`' shared-helper callers keep
their codes; the `QueryRoutes` / `ParserRoutes` / `ConfigReadRoutes` / `RuleRoutes` / `EnrichmentRoutes` / `RouteErrors` slice —
58 sites, all the status default but one: `RuleRoutes`' "no write root configured" 503 is now
`CONTROL_PLANE_READ_ONLY` (was `CAPABILITY_UNAVAILABLE`), a wire change asserted over HTTP by
`ControlApiRuleTemplateTest` and noted in [api-stability](../api/plan-api-stability.md);
`RouteErrors`' mappers fix their status per branch, so every caller keeps its code; the
`SpaceRoutes` / `SchedulerRoutes` / `Roles` / `PipelineRenameRoutes` / `ExpectationRoutes` / `AccessPolicies` /
`ShareRoutes` / `NotificationRoutes` slice — 98 sites, all the status default but one: `SchedulerRoutes`'
"no home for the server-wide scheduler document" 503 is `CONTROL_PLANE_READ_ONLY`, which gate 1
(`requireBoundWriteRoot`) makes unreachable over HTTP, so no observable wire change; the
`ConnectionRoutes` / `ConfigPreviewRoutes` / `PipelineBundleRoutes` / `DbBrowserRoutes` / `AccessRoutes` +
`inspecto-ops` `TagRoutes` slice of `ERRORCODE-DEFAULTED-1` — 108 sites, every one the status default, so
no wire change; the `ReconRoutes` / `AgentRoutes` / `JobRoutes` / `RunRoutes` + `inspecto-geo-link` slice
before it was 130 sites, likewise all defaults; the earlier `InvestigationRoutes` + `InvRoutes` slice's four-eyes
403s are `PERMISSION_DENIED`, not the defaulted `PATH_JAIL_VIOLATION`). Re-derive with
`git grep -nP 'new ApiException\([^,()]+,(?!\s*(\w+\.)?[A-Z][A-Z_]+\s*,)' -- '*/src/main/**.java' | wc -l`.
Bodies ≥ 1024 bytes are gzipped when the client accepts it (`ApiContext.maybeGzip`).
`permissions[]` on an authenticated envelope is `subject.capabilities() ∩ applicable` when a route declares
`resourcePermissions` — an affordance signal, never the security boundary (`SEC` §3.14).
**`HEAD` is answered with headers only** (`HEAD-RESPONSE-STREAM-CLOSED-1`, 2026-09-24): `respondJson` sends
`-1` (no body) for a `HEAD` request. Before, it sent the body's length — the JDK server then logged
*"being invoked with a content length for a HEAD request"* and the body write threw, once per preview or
uptime probe. Routes match on their declared method, so a `HEAD` still answers `405`/`404` — only the
framing changed. Pinned by `ControlApiHeadRequestTest`, which captures the JDK's `com.sun.net.httpserver`
warnings through JUL. ⚠ Only `respondJson` is guarded; the text/binary/static helpers are reached only by
`GET` routes today.

⛔ **SPA adoption is RESERVED, not scheduled** (decided 2026-09-10 as `CLIENT-HALVES-1` (b); the row was
deleted 2026-09-14 once its other halves shipped, so this is the record). The field stays **emitted and
unread**, deliberately. Three measured facts remove the value: **(i)** `stamped()` is applied to exactly
three *mutating* routes, and `GET /requirements` — the sole read, with no single-requirement GET at all —
carries no per-resource set, so it can never gate a list's affordances; **(ii)** the client already holds
the input the server derives from (`view()` puts `status` on every list row and the server's rule is
literally `switch (status)`); **(iii)** the pane already implements that exact rule locally and correctly
(`requirement-decision.dialog.ts`). ⇒ Adopting it would duplicate a correct local derivation over
the network and change no behaviour. ⚠ A structural limit worth knowing first: the envelope carries **ONE**
`permissions` array per response, so it cannot express per-row permissions for a collection — "per-resource"
only ever works for a single-resource response. Every affordance gate in the SPA today is a
`LensService.can*` signal fed from a **session-wide** array populated once from `GET /bootstrap`, so
adopting a per-response field is net-new plumbing at a *narrower* scope.
✅ **The trigger to reopen:** a route that stamps a set the client **cannot compute from the payload it
already has** — per-record ownership or an ABAC data scope — *and* is reachable on a **read**. **A client that
adds the prefix and does not unwrap `data` compiles and fails at runtime** — the trap that caught the
intelligence module's `ControlPlaneClient`; the SPA's `v1Interceptor` unwraps at one seam (§3.15).

### 3.4 Routes: registration, the SPI, the guard, the order

`ApiContext` (`@PublicApi`, 413 lines) is what a route module sees: `get/post/put/patch/delete`, `hasRoute`,
`stub`, `withCapability` (wraps a handler in `requireCapability` → `403 PERMISSION_DENIED`),
`resourcePermissions`, `pagination`, `respondJson`. Routes are registered by **61 built-in `RouteModule`s**
(~332 registrations, "0 duplicates" per the census), then by **`ServiceLoader`-discovered
modules**, appended strictly after the built-ins, then by the five **absent-module stub
groups** that answer `503` for a family the bundle lacks. `RouteModule` became a **public SPI
on 2026-09-07** (EDG-01 cell 3a) together with `Handler`, `ApiException`, `WriteGates` and `ApiContext`, all
`@PublicApi(since = "4.0.0")`. **A colliding `(method, pattern)` fails boot** (`register`) —
first-match dispatch means a duplicate would otherwise never fail, only shadow. ⚠ **Registration order is
load-bearing**: `/config/spec/…` must register before the generic two-segment read; `GET /jobs/expressions`
before the `/jobs/{name}` regex. Shared plumbing lives in `RouteErrors` (the domain-exception → status
mappers), `SpiSlot<T>` (the first-wins edition-seam lookup) and `WriteGates`; ⛔ do not re-introduce per-class
copies, and do not generalise `SpiSlot` to the collect-all `ServiceLoader` sites — each carries semantic
per-element logic and was measured as not worth unifying (2026-08-27).

**The write gate order is house style** (`.claude/skills/endpoint/SKILL.md`): write-root unset → **503** ·
spec + `ConfigSafetyValidator` error → **422** · path jail → **403** · conflict → **409** · act atomically.
`WriteGates` is called from 71 main-source files (253 call lines, 2026-11-01). A read route has no 503 gate. Every returned collection
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
  🔴 **And it is honoured on ONE route only — re-measured 2026-09-11.** `ETags.requireMatch` has exactly
  **one** production call site in the tree: `ComponentRoutes.java` (`PUT /components/{type}/{id}`).
  ⛔ **The config write family does not participate**: `ConfigWriteRoutes.java` (`POST /config/write`,
  `POST /config/patch`) contains no `ETags`, no `If-Match` and no `ContentHash`. Its concurrency story is a
  different one by design — `write` gates on an existence check plus an `overwrite` flag, and `patch`
  merges **server-side against the file as it is now**, chosen explicitly *instead of* a
  client-held-version check. ⇒ **`API-3`'s signed "the SPA adopts `If-Match` in both writing panes" is not
  achievable without a server change**, because the routes those panes call could not read the header.
  ✅ **RESOLVED 2026-09-11 — the operator granted the server change (option A).** `POST /config/write` now
  calls `ETags.requireMatch`, and `GET /config/{type}/{name}` publishes the matching `ETag`; both hash
  **`ConfigFileSupport.storedContent`**, the one statement of "the stored content", because a `schema` is
  split storage and a write hashing the bare TOON would disagree with the read on every schema.
  ⚠ `/config/write` also publishes the **post-save** ETag: without it a caller's second consecutive save
  is refused as stale. ⚠ And `ETag` had to be added to `Access-Control-Expose-Headers` — the SPA is
  cross-origin, so it could not read the header it must echo. `ETags.requireMatch` now has **two**
  production call sites. Full write-up: `CLIENT-HALVES-1` (a) (row closed).
  ✅ **The Components route's `If-Match` gained its first client 2026-09-11** (`SCHEMA-DIALOG-IFMATCH-1`):
  the Schema editor dialog sends the list's `contentHash` on `PUT /components/schema/{id}`.
  🔴 **The handle is the BARE hash; the header is `"sha256:<hash>"`.** `componentDoc` publishes
  `contentHash` as bare hex while `ETags.requireMatch` compares against the quoted, prefixed tag — echoing
  the published value verbatim matches nothing and refuses **every** save. `ComponentsService.update`
  builds the tag in one place. ⚠ There is no ETag *header* to echo for a list-opened editor: list routes
  are deliberately excluded from ETags (above), so `contentHash` in the body **is** the only handle, and
  that asymmetry is why the conversion cannot live at the call site.
  ⛔ **A schema's two write routes address different files** — `registry/schemas/<id>.toon` for the
  component vs `<subdir>/<name>.toon` for `/config/write`. That is a seam, not a bug to collapse: the parse
  editor authors a pipeline's **satellite** schema, the Components pane authors a registry component. It
  became a silent data loss only because the *editor* chose the wrong one. Pinned by
  `ControlApiComponentsTest.configWriteSchemaDoesNotUpdateTheRegistryComponentOfTheSameName`.
  ✅ **`SCHEMA-SATELLITE-SUBDIR-1`.** 🔴 The parse editor's write to the write ROOT
  was never on purpose — a satellite belongs **beside its
  pipeline**, and a root-level write is the `SATELLITE-WRITE-1` defect: the root file then **wins the read**
  (`resolveSatelliteForRead` scans only when the convention path misses), so the drawer edits a schema the
  engine never loads while a duplicate is orphaned beside the real one. The opener now threads the
  pipeline's `configSubdir` through `GrammarEditorDialogData` → `SchemaEditorData.subdir`. ⚠ A root-level
  pipeline still sends **no `subdir` key at all** — absent means "fall back to the server's scan", which is
  a different instruction from `''`.
  ⚠ **The sibling `openMappingEditor` was checked and is NOT the same defect**: it writes through
  `ComponentsService` (the registry), not `/config/write`. Recorded so a later sweep of this shape does
  not re-investigate it.
  ⚠ Also: `Envelope.java` **never sets an `etag` key in `metadata`** for any route, so the SPA's
  `V1EnvelopeMetadata.etag` (`v1.ts`) is a field the server has never populated. The ETag travels only
  as an HTTP header — which means `v1.interceptor.ts` is **not** what withholds it: that interceptor clones
  the **body** only and leaves headers intact.
- **`Idempotency-Key`** (`Idempotency.java`): on any `POST`/`PUT`/`DELETE`, the first JSON response is cached
  **per `ControlApi` instance** — TTL 10 min, LRU cap 1000 — and a replay skips the handler and
  answers with `Idempotency-Replayed: true`. 🔴 **As-built after `SEC-IDEMPOTENCY-REPLAY-1` (2026-09-29):**
  the lookup is NOT a pipeline stage any more — it runs inside `routeDispatch` **after authenticate → rate
  limit → authorize and before the handler**, so a replay is refused, throttled and policy-checked exactly
  like the live request (it used to run before all three: anyone could read another caller's cached
  `/db/query` rows, and an anonymous 401 cached against a key suppressed the owner's write). The cache key is
  `method + canonical route path + bound Space + principal + key`; the principal is
  the Subject id namespaced by its token's issuer (`Subject.stampIssuer`, set by `OidcAuthenticator` — the bearer and gateway processors have different issuers; an Authenticator that stamps none is namespaced by its class), or `anon@<client IP>` when none is attached (Basic / public routes). The path is the one the router MATCHED — percent-decoded, `/api/v1` and `/spaces/{id}` stripped — with duplicate slashes collapsed and a trailing slash dropped; case stays exact because routes match case-sensitively. The query string joins the body hash. Each entry binds a
  SHA-256 of the request body: the same key with a different body is **`422 "Idempotency-Key reused with a
  different request"`**. Only **2xx, 400, 409, 422** are cached — deterministic outcomes of the request's own
  content; 401/403/404/429 depend on who asks and when, 5xx may be transient. Bounds: **50 keys per
  principal** in its own LRU partition (its oldest evicted — one caller cannot flush another), at most **20
  principals** held (the least recently used principal's partition is evicted whole, so 1000 entries stay the bound). **Limits, stated plainly:** 20 distinct authenticated principals each doing one keyed write can flush another principal's partition (20 writes, not 1000); every anonymous caller shares ONE partition (one slot, 50 entries — anonymous floods cannot displace an authenticated principal, but anonymous callers can evict each other), while each entry key stays scoped to `anon@<client IP>` so they never share a replay; a request with neither a Subject nor a client address is not keyed at all (fails closed: no caching, no replay, no in-flight fence); and a retry after eviction **re-executes the write** rather than replaying. **256 KiB** per cached response, **1 MiB**
  request body hashed; past either size cap the request runs un-keyed and answers `Idempotency-Cached: false`.
  Every write route honours the header generically (it is captured in `ApiContext.respondJson`); the SPA never
  sends it. Pinned by `ControlApiIdempotencyScopeTest`. Per-instance by design (no cross-instance store; no test
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
exchange, geoLink, events, ops, authMode}` (the module flags are `api.hasRoute(...)`) ·
`configSpecs` (every `ConfigSpecs.TYPES`) · `enumerations.{severities, attributeTypes, outputFormats}` ·
`spaces` (id + display name) · `session.{authenticated, actor, capabilities}`. `/bootstrap` is in
`PUBLIC_PATHS`; a Standard SPA re-reads it with a bearer to obtain effective capabilities.

### 3.8 The OpenAPI contract and what the test actually proves

`docs/api/openapi-v1.json` is OpenAPI 3.1, **one JSON file** with 15 bounded-context tags: shared
transport components (Envelope, ErrorObject + the ErrorCode enum, Pagination, Signal, Ref), **147 hand-written operations** (24 when generation landed), and — since `OPENAPI-GEN-1` (2026-09-15) — a **generated path/method skeleton
for every other live route** (434 operations marked `x-generated: true` on 2026-11-01, 186 of them `x-optional-module: true`
because they are known to the core only as 503 stubs). `OpenApiPathsContractTest` regenerates the skeleton from
`ControlApi`'s route table (`-Dopenapi.paths.write=true`), matching templates STRUCTURALLY so a hand-written
`/config/{type}/{name}` satisfies the live `/config/([^/]+)/([^/]+)`, and never overwrites a hand-written
operation; generated param names come from the preceding segment (`/spaces/{spaceId}`). ⚠ A skeleton has no
schemas — `responses.default` only — so "documented" now has two grades, and the exemplar-coverage decision (§5)
is about promoting skeletons, not about undocumented routes. Since 2026-11-17 (`OPENAPI-SKELETON-SUMMARIES-1`) every
public operation also has a real summary and description, written in its fragment; `tools/check-openapi-summaries.mjs`
fails on the generated `(generated skeleton)` summary outside a shrinking allow-list (only the test-only
`GET /test-discovered/ping` is on it). The `x-generated` mark stays until the operation gets schemas. JSON rather than the design's per-context YAML because the lean SBOM
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
reactor gate runs on JDK 27, where the map is per-exchange, so **it structurally cannot catch a regression**;
the pins are `ExchangeAttributeScopeTest`, `ApiContextV1DerivationTest` (v1-ness derived per request, never
cached), and a concurrent probe against the *shipped* runtime.

### 3.10 Static SPA serving

`serveStatic` serves `-Dui.dir`: a path with an extension gets its MIME type, an extensionless
deep link gets `index.html`, and the target is jailed by `startsWith(uiDir)`. It is reached only for a request
OUTSIDE `/api/v1`: an unmatched `/api/v1/…` GET is a JSON `404` in the v1 error envelope, never the shell
(LA-ONBOARD-FIXES-1 F-13 — `GET /api/v1/spaces/{id}/datasets`, a route that does not exist, used to answer 200 `text/html`), so the SPA and
its assets live at the root only. `writeFile` sets
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
(`inspecto-observability`), `/events*` (`inspecto-observability`; the core keeps `/audit/search|export`), and the
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
fully-qualified `@com.gamma.api.PublicApi` — so a grep for one under-counts; together they mark **244
files**, and of 286 `since` values **256 say `4.0.0`**, 3 the equally unreleased `5.x`, and 27 name a version
that shipped (`1.0.0` … `3.10.0`) (measured 2026-11-01).

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
registration (a pack fails atomically on an unknown id), substituted under a dry run. Menu (2026-10-17):
`notifications`, `mail`, `incidents`, `objects`, `schema`, `consignment-status`, `datasets`, `link-index`, `analytics`,
`alerts` (the owner concept `platform-services.md` §3 is authoritative); `requires` travels on
`GET /jobs/types[/{id}]`. ⛔ It must not grow scopes, proxies, lifecycle or annotation injection — the moment
it does it is the dependency-injection framework this codebase deliberately does not have. Mechanism:
`platform-services.md`.

### 3.15 The SPA client

`apiUrl(path)` = `environment.apiBaseUrl + "/v1" + path` (`inspecto-ui/src/app/inspecto/api/api-base.ts`;
`/api` in dev behind the proxy, `''` in the built bundle). Interceptor order (`app.config.ts`):
**`v1Interceptor`** (unwraps `{data, metadata, diagnostics}` → `data` at the one seam; warns on
`apiVersion` skew) → `spaceInterceptor` → `authInterceptor` (Bearer + 401 → silent refresh → retry) →
`inspectoErrorInterceptor` (connectivity only). `apiErrorMessage` reads the v1 error object with a legacy
fallback. `Correlation-ID` is **read** from `diagnostics` and shown on the Events, Notification and
Incident-detail screens; it is never sent. DTOs are hand-written per service — **no OpenAPI-generated
client and no drift check** between `openapi-v1.json` and TypeScript; wire vocabularies are pinned instead by
ten `*.contract.json` files each compared by a Java `*ContractTest` and a TS `.spec.ts` (§8.4). ⚠ Two
contract features still have **zero client consumers**: `metadata.pagination` (never read — the data
table's "Load more" is not cursor-driven) and `Idempotency-Key` (never sent). `If-None-Match` is also
never sent. ⚠ **`If-Match` no longer belongs on this list** — as of 2026-09-11 two panes send it
(`/config/write`, `CLIENT-HALVES-1` (a)) and the Schema editor sends it on `PUT /components/schema/{id}`
(`SCHEMA-DIALOG-IFMATCH-1`). ⚠ `error.interceptor.ts` still says "the backend is fully open, no 401
handling" — stale since W6d; the handling lives in `auth.interceptor.ts`.

## Changes

- 2026-09-13: this passage said the parse editor writes to the write ROOT "on purpose".
