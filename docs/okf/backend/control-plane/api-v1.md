---
type: Concept
title: Versioned API (/api/v1)
description: The v1 business contract — response envelope, error-code catalog, ETag/contentHash concurrency, bootstrap, async runs, OpenAPI enforcement.
resource: inspecto/src/main/java/com/gamma/control/ControlApi.java
tags: [control-plane, api, v1, envelope, etag, openapi, async]
timestamp: 2026-07-07T00:00:00Z
---

# Versioned API (/api/v1)

The Control API's stable, versioned **business contract** (W1–W8, shipped 2026-07-06/08), designed so a
WSO2-style gateway + external IAM can front it later without reshaping routes. Design of record:
[`api-contract-design.md`](../../../archived-documents/plans-archive/api-contract-design.md) (§10 worklog).

* **Envelope + errors (W1)** — every `/api/v1` response is enveloped; errors carry a machine-readable
  `errorCode` from the catalog in `ErrorCodes.java`; authenticated envelopes include `permissions[]`.
  Write routes pass through fail-closed `WriteGates`. Every response carries a `Correlation-ID`;
  responses are gzip-compressed when accepted.
* **`/api/v1` is the only business surface (W8, API-5 — completed 2026-07-25, BACKLOG D3)** — a bare
  unversioned business path is **no longer served**. `ControlApi.normalizePath` strips the `/api/v1`
  prefix into the route table; anything else under `/api/…` gets a **JSON 404** (never the SPA shell),
  and a bare non-`/api` deep link (`GET /objects`) falls through to `serveStatic` — correct, those are
  SPA deep links. The allow-list of routes that stay unversioned is `ControlApi.isInfraRoute`:
  **`/health`, `/ready`, `/metrics`, `/metrics/acquisition`** — they have no v1 semantics.
  `isInfraRoute` is therefore the allow-list, no longer a metric exemption.
  The whole sunset apparatus is **gone**: `legacyRoutesOff`, `legacySunset`, `recordLegacyUsage`,
  `markDeprecated`, `sunsetHeader`, the `-Dapi.legacy.routes` / `-Dapi.legacy.sunset` flags, and the
  `inspecto_legacy_api_requests_total` metric. (The 30-day-at-zero soak criterion was deliberately
  overridden: there is no live deployment and every in-repo caller was migrated in the same change.)
  Contract for callers: responses are envelope-wrapped and errors are
  `{error:{errorCode, message, recoverable, correlationId, details?}}`, so a client that merely adds the
  prefix without unwrapping `data` compiles and fails at runtime — the trap that caught
  `inspecto-intelligence`'s `ControlPlaneClient`, which now prefixes **and** unwraps (server-side what the
  SPA's `v1Interceptor` does). `ControlApi.LOCAL_BASE_URL_PROP` stays a **root** base URL with no version —
  the version belongs to the client, matching how the SPA composes `apiBaseUrl + /v1 + path`.
  Contract test: `ControlApiVersionedSurfaceTest`.
* **Per-resource `permissions[]` (SEC-7b)** — a single-resource handler declares its applicable capability
  set via `ApiContext.resourcePermissions(ex, Set)`; `Envelope.success` emits
  `subject.capabilities() ∩ applicable` (fail-closed affordance signal, never the security boundary —
  enforcement stays `requireCapability` on writes). No declaration ⇒ session-wide array; Personal (no
  Subject) ⇒ no `permissions` key at all. List-row permissions and stored per-object ACLs are deliberately
  out of scope (see [auth & security](../editions/auth-security.md)).
* **OpenAPI-first (W2)** — the contract lives at [`openapi-v1.json`](../../../api/openapi-v1.json)
  (+ canonical examples) and is **enforced** by `ApiContractTest` against `ErrorCodes.java` and the live
  server.
* **Optimistic concurrency (W3)** — Components carry a `ContentHash` (parity-pinned with the UI's
  `content-hash.ts`); reads return `ETag`, conditional reads honor `If-None-Match`, writes require
  `If-Match`. See [component registry](../components/component-registry.md). The read-side idiom is a
  one-line `ETags.respond(ex, body)` wrapper (`ETags.java`) — hash the body → `If-None-Match` 304 →
  set the header → return body-or-`HANDLED`; the hash captures any body variance, so a changed body
  never yields a false 304. **Extended 2026-07-24** beyond `/bootstrap` + `GET /components/{type}/{id}`
  to the per-space authored config/metadata singleton documents the UI re-reads on load/space-switch:
  `GET /nav/menus`, `/settings/branding`, `/settings/geo`, `/config/icon-map`, and
  `/access/roles|policies|catalog|profiles`. (List/paginated routes are deliberately left out — the
  cursor page varies per query; further singleton reads can adopt `ETags.respond` as demanded.)
* **Bootstrap (W3/W6)** — `GET /bootstrap` returns the metadata-first boot document, including
  `features.authMode`, which drives the UI's OIDC flow (no-op on Personal).
* **Queries (W4)** — the query catalog + `POST /queries/{id}/run`; see [queries](queries.md).
* **Async runs (W5/W5b)** — job triggers *and* pipeline triggers return **`202` + `{runId, status…}` +
  `Location`**; poll the run by id; `Idempotency-Key` gives at-most-once replay. See [jobs](jobs.md).
* **AuthN/AuthZ seam (W6)** — the `Authenticator`/`Subject`/`TokenRelay` SPIs gate v1 routes on Standard;
  see [auth & security](../editions/auth-security.md).
* **Cursor pagination (§7)** — list routes expose opaque keyset cursors via `metadata.pagination`
  (`{cursor, nextCursor, limit, total}`). A route declares the block with `ApiContext.pagination(ex, …)`
  (mirrors the `resourcePermissions`/`ATTR_SELF_PATH` attribute seam) and `Envelope` emits it on v1
  responses only; the opaque token is URL-safe-Base64 over the JSON keyset (`com.gamma.control.Cursor`,
  decode-total — a garbage cursor means "from the top", never a 400). First adopter: `GET /jobs/runs`
  over the DuckDB run projection (`DbJobRunStore.recentRuns(limit, job, afterStartTime, afterRunId)` +
  `countRuns`, `ORDER BY start_time DESC, run_id DESC`, keyset SQL dialect-neutral for DuckDB + Postgres).
  (At the time this shipped an unversioned caller still got the same bare list; that surface has since
  been retired, so the paginated view is the only view.) Other list families adopt the same seam
  on demand. **Second adopter (2026-07-19): `GET /objects`** — with a twist: unlike `/jobs/runs`, this
  route has a SEC-7d visibility post-filter (`ObjectRoutes.visibleOnly`), so an SQL-side keyset would
  make `total`/page sizing wrong or leaky under that filter. The keyset (`createdAt DESC, id DESC`)
  instead runs **in-route over the already-visibility-filtered set** — acceptable because operational
  objects are explicitly low-volume by design (`ObjectQuery.unbounded()` widens the query, the route
  slices/encodes the cursor itself). **Third + fourth adopters
  (2026-07-19): `GET /jobs` and `GET /events`** — one of each variant. `/jobs` follows the `/objects`
  in-route pattern (the registry is an in-memory materialized `JobView` list, low-volume; single-part
  keyset `name` — unique, so name order is total; `JobRoutes.jobsPage`). `/events` follows the
  `/jobs/runs` store-side pattern (events are high-volume rolling Parquet): `EventStore` gained
  `page(limit, afterTs, afterId)` + `count()` (defaults for API compat; exact overrides in both bundled
  stores — ring walk in `InMemoryEventStore`, SQL keyset predicate
  `(ts_ms < ? OR (ts_ms = ? AND event_id < ?)) ORDER BY ts_ms DESC, event_id DESC` merged with the
  unflushed buffer in `ParquetEventStore`). Note `/events` pages the **full retained history**, not just
  the live-tail ring the pre-v1 view served. Tests:
  `ControlApiJobsPageTest` · `ControlApiEventsPageTest` (incl. a shared-timestamp id-tiebreak resume).
  **Adoption policy:** new list endpoints over an *unbounded* table MUST use `Cursor.encode/decode`
  keyset paging; a bounded/in-memory list MAY use `ApiContext.paged` (limit/offset slice) instead.
  Existing endpoints keep their current behavior — `RunRoutes`' `paged()` migrates only if a caller
  reports truncation pain (demand-driven, tracked in BACKLOG), and the `/runs/runs/{id}`
  Location-header quirk stays until a v2 of the API (renaming would break every stored client link).
* **Multi-space** — the space segment sits **after** the version: `/api/v1/spaces/{id}/…`
  (see [multi-space](multi-space.md)).
