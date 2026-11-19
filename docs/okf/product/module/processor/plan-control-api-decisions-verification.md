---
type: Capability
title: Control API (API) — Decisions, not built, refused and verification
description: Part 3 of 3 of Control API (API): Decisions; Not built; Refused & superseded; As-built mechanism (pointers only); Verification
tags: [api, capability, control-api, v1, envelope, error-codes, etag, idempotency, cursor, openapi, route-module, public-api, semver]
timestamp: 2026-09-08T00:00:00Z
---

# Control API (API) — Decisions, not built, refused and verification

Part 3 of 3 of [Control API (API)](plan-control-api.md) · previous: [Specification](plan-control-api-specification.md).

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
| 2026-09-28 (session, `ERR-BOUNDARY-ERROR-1`) | `errorBoundary` answers a server-side **`Error`**, not only an `Exception`. `StackOverflowError` and every non-`VirtualMachineError` `Error` (`LinkageError`, `AssertionError`, …) get a logged `500 INTERNAL`. Any other `VirtualMachineError` (`OutOfMemoryError`, `InternalError`) is logged, answered with a best-effort 500, then **rethrown**. A response the handler already committed (SSE, or a failing audit call after the answer) is logged and not answered twice. The idempotency stage, the SSE streams, `ShareRoutes` and the `AuditTrail` callers need no catch of their own: they all unwind into this boundary, and a 5xx is never cached for replay | An escaping Throwable reached the JDK HttpServer, which catches every Throwable in `ServerImpl.Exchange.run`, logs it at **TRACE** only, and drops the connection. The client saw "header parser received no bytes" with nothing in the log. That happened twice (a `NoClassDefFoundError` from a jdk.* module missing in the jlinked runtime) and cost hours each time. The stack has unwound by the time a `StackOverflowError` gets here, so it is safe to answer. An OOM is not swallowed, because the JVM may be unsound. ⚠ The rethrow lands in that same TRACE-only catch, so it ends the exchange but reaches no uncaught-exception handler. `-XX:+ExitOnOutOfMemoryError` fires at allocation, so it works either way. ⚠ An errored request, `Exception` or `Error`, writes **no AUDIT row**: `routeDispatch` records only after the handler returns. The two are at parity (`ControlApiErrorBoundaryTest`), and the gap is not changed here |
| 2026-09-28 (operator, `ERR-500-GENERIC`) | A 500 from `errorBoundary`/`fail500` carries only **"Internal error — correlation id `<id>`"** as its message (v1 envelope, `errorCode` `INTERNAL`). The exception text and the stack stay in the ERROR log line under that id. `ApiException` bodies, 4xx and 5xx alike, are **unchanged**: each is authored by its route as a client-facing message | `String.valueOf(e)` / `getMessage()` put class names, SQL fragments and paths on the wire. ⚠ Some route-authored `ApiException` 5xx messages embed a cause's `getMessage()` (e.g. the `ExchangeRoutes` snapshot 500, the `ConnectionRoutes` explore/sample 502s, the 503 "query sandbox unavailable: …" family). They are judged deliberate diagnostics and left as they are: each is a route-level call, not the boundary's |
| 2026-09-28 (operator, `ERR-5XX-ROUTE-BODIES`) | Route-built 5xx bodies carry no exception text either. The `ExchangeRoutes` snapshot 500 and the `PipelineGraphRoutes` workbook-render / scratch-root 500s answer the same generic message through `ServerFaults.internal`. The `ConnectionRoutes` explore/sample 502s and every "query sandbox unavailable" 503 (`QueryRoutes`, `DatasetRoutes`, `DbBrowserRoutes` query, `KpiRoutes`, `RuleRoutes`, five in `ReconRoutes`) go through `ServerFaults.curated`: "`<what>`: `<reason>` — correlation id `<id>`", the reason picked by exception type from a fixed list (connection refused · timed out · authentication failed · host not found · sandbox busy) or the route's fallback, never copied. The full exception is logged at ERROR under the id. Pinned by `ServerFaultsTest` (marker-carrying probe, over real HTTP) | supersedes the "left as they are" note on the row above. ⚠ Still echoing a cause and not in these families (listed, not changed): `ExpectationRoutes` baseline 503s, `ReconRoutes` "reconciliation state unavailable" 503s, `DbBrowserRoutes` data-directory 503, `ComponentRoutes` SQL-structure 500 (a DuckDB parse message for the author), `EntityListRoutes` integrity 500, and the typed-exception 503s in `AgentRoutes`/`NotificationRoutes`/`PipelineGraphRoutes`/`ControlApi` whose messages are authored server-side |
| 2026-10-09 (LIVEFIX2) | A `POST /bi/query` 422 keeps DuckDB's message (the author needs binder errors) but never the server's layout: `ServerFaults.withoutServerPaths` replaces the Space data root and config root (native or forward-slashed, any case) with their directory names, then any remaining absolute path — including the one DuckDB truncates in its `LINE 1:` echo — with `<path>`. Pinned by `ServerFaultsTest` and `KpiCompletenessWidgetTest` | Swept to every route 2026-10-10 (next row) |
| 2026-10-10 (ERR-4XX-PATH-ECHO-1) | Every route that puts a DuckDB / SQL / IO message into a 4xx goes through ONE seam, `ServerFaults.sqlMessage(message, api)` = pending-query preamble dropped + `withoutServerPaths` over the Space's `dataRoot()` and `writeRoot()`: Dataset rows, DB browser query, Query run, View, Share, KPI, Expectation, Decision-template (`RuleRoutes`), Enrichment preview, Pipeline dry-run / test run, `RouteErrors.mapPreviewErrors` (component previews, now taking the `ApiContext`), `ComponentRoutes` SQL-structure (`/sql/ast`, `/transform/describe`), BI query and reconciliation. A new route echoing a driver message uses `sqlMessage`, never `DuckDbUtil.withoutPendingQueryPreamble` alone. Pinned by `ControlApiDbBrowserTest` / `ControlApiDatasetRowsTest` (a non-Parquet file DuckDB names by absolute path) | `ComponentSpecs` validation findings (engine, no Space context) still use the preamble strip only; they carry sample-row previews, not Space files |

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
| Release notes for the pending MAJOR | `BACKLOG.md` §2 *Release notes for the next MAJOR* | The draft accrues in `api-stability.md`; the tag is not cut |
| X5 cross-lane StepInfo envelope | `BACKLOG.md` §2 *X5* | ~1 KB pointer + schema + diagnostics, deferred to the MAJOR |
| Platform Services Stage 2 (open Step-kind registry) and Stage 3 (pack-contributed services); no timeout/cancellation either side | `BACKLOG.md` §4 *Platform Services* | Design only |
| Structured (non-SQL) queries from an external consumer; more `$`-resolvers | no board row (the *Queries / BI* row was deleted 2026-11-12; add a resolver when a caller asks by name) | `422` on non-SQL is the deliberate contract (R6) |
| Draft/published version history as designed (guideline 7, W3b) | `REQUIREMENTS.md` MET-5 (partial) | Behind store widening |
| Per-context split of `openapi-v1.json` | `docs/api/README.md` (deferred, reversible) | Only if the file becomes unwieldy |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-STALEREF-1`, `SPEC-NOPROOF-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| 🟡 **OpenAPI covers a fraction of the live surface** — **assertion SHIPPED 2026-09-09; DECIDED 2026-09-10 — generate the path/method skeleton from the route table (`OPENAPI-GEN-1`, shipped 2026-09-15), the exemplars keep the schemas** | `openapi-v1.json`: 19 paths / 24 operations. Live: **266 registrations + 73 absent-module stubs**, measured by `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted` — **9.0 %**, not the 6 % stated here before (that divided *paths* by *registrations*) | ✅ The owed *live → doc coverage assertion* now exists: it measures the gap, **prints it pass or fail**, ratchets the documented counts so an operation cannot quietly disappear, and floors the live count so the figure cannot be computed over nothing. Mutation-proven both ways (removing `/health` fails naming 18/23; raising the live floor above the real count fails naming 266). ⛔ **Still owed, and NOT a guard's call: whether to document the remaining surface or to adopt exemplar coverage deliberately.** ⚠ A per-route live → doc comparison is unavailable — the route table holds compiled patterns (`/config/pipeline/([^/]+)`), OpenAPI holds templates (`/config/pipeline/{name}`). ⚠ And the 266 is the **core** surface: it does not move with `-Pedition-*`, because optional modules depend on the core rather than the reverse |
| ~~**The SPA sends no `If-Match`**~~ **CLOSED 2026-09-11** | `config.service.ts` (`/config/write`) and `components.service.ts` (`PUT /components/{type}/{id}`) both send it | `API-3`'s client half is built. 🔴 The Components handle is the **bare** `contentHash`, not an ETag — the service wraps it as `"sha256:<hash>"`, since a list serves no ETag header to echo |
| **The SPA reads no `metadata.pagination`** | zero consumers of `nextCursor` | Four paginated endpoints page for `curl` only; the data table's "Load more" is not cursor-driven |
| **No `@PublicApi` surface guard** | no japicmp, no scanning test | `API-7` is policy in prose; a MINOR that removes a marked member passes the build |
| **The gateway blueprints are untested documentation** | no reference in `src/`; README's own caveat | `API-6` is a Must (S); the WSO2 definition also omits the `503` stubs and the optional-module paths |
| **Idempotency replay is per instance** | `Idempotency.java` | A second instance behind a gateway replays nothing; fine today (single instance), a trap for E1 |
| **`error.interceptor.ts` comment is stale** | "fully open, no 401 handling" | A reader editing the chain would place 401 handling in the wrong file |

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
on JDK 27.

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
| Scoped token auth in the core (`-Dcontrol.token`, `/connect` — removed) | the auth-free core + SPIs (2026-06-16) | `SEC` §3.1 |
| `/flows`, `/sources`, `/issues`; the ingest-ops `/pipelines` | `/pipelines`, `/collectors`, `/incidents`; `/runs` | `GLOSSARY.md` §13 |
| "Job survives as internal scheduling vocabulary only" (2026-08-05) | Jobs un-banned, routes canonical (2026-08-06) | `GLOSSARY.md` §13 |
| Constructor injection of engine facilities into built-in Jobs; a setter for the service registry | Platform Services; a constructor parameter | `platform-services.md` |
| `@PublicApi since` values `4.1.0 … 5.8.0` | all `4.0.0` | `api-stability.md` |
| The 2026-08-11 "`4.x` as branch of record" | `master`; `4.x` deleted 2026-08-17 | `BRANCHING.md` |
| `NodeTargets` → `com.gamma.ops.AnnotationKinds` → `com.gamma.objects.AnnotationKinds` | relocated twice; core vocabulary | `api-stability.md` |
| `plan-control-api.md`'s "legacy routes stay byte-for-byte, counted by `recordLegacyUsage`" and "curation stays an open question" | corrected with this spec — both were superseded on 2026-07-25 | `plan-control-api.md` |
| `api-stability.md`'s "v4.8: … alongside byte-for-byte legacy routes" and its "(2.0.0)" surface heading | corrected with this spec — no v4.8 ever existed; the table is the *pending* 4.0.0 surface | `api-stability.md` |
| `openapi-v1.json` `info.description`'s "the entire legacy route table (121 routes) is also served" | corrected with this spec | `docs/api/openapi-v1.json` |
| `STAKEHOLDER_OVERVIEW.md`'s "~30 routes … sources, flows, issues" | plan §8 cluster — reconciled at step 6, not here | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The v1 contract in long form — envelope, W1–W8, `permissions[]`, OpenAPI serving, ETags, cursors and their four adopters | `docs/okf/product/module/processor/api-v1.md` (`Concept`) | `ControlApi.java` | the account §3.3, §3.5, §3.6 and §3.8 were distilled from |
| The server itself — launch, `dispatch`, request-scoped attributes, route families, shared plumbing, the 2026-08-27 splits | `docs/okf/product/module/processor/api-control-api.md` (`Concept`) | `ControlApi.java` | §3.2, §3.4, §3.9 |
| `@PublicApi`, the release baseline, the pending MAJOR's release notes, the published surface tables | `docs/okf/product/module/api/plan-api-stability.md` (`Reference`) | `PublicApi.java` | §3.13 and every rename question |
| The Platform Services seam, the registry, dry-run substitution, the pack scaffolder | `docs/okf/product/module/processor/spec-platform-services.md` (`Concept`) | `PlatformServices.java` | §3.14 |
| The machine-readable contract, its rules and the contract-first workflow | `docs/api/README.md` · `docs/api/openapi-v1.json` · `docs/api/examples/` | — | what `ApiContractTest` reads |
| The gateway and IAM blueprints and the seams they assume | `docs/api/deployment/README.md` · `wso2-api-definition.yaml` · `keycloak-realm-blueprint.json` | — | §3.12 — illustrative, untested |
| The space seam after the version | `docs/okf/product/module/processor/architecture-processor.md` (`Concept`) | — | `/api/v1/spaces/{id}/…` |
| The SPA's API conventions and service catalogue | `docs/okf/product/layer/product/spec-api-and-data.md` (`Convention`) · `docs/okf/product/layer/product/api-services.md` (`Reference`) | `inspecto-ui/src/app/inspecto/api` | §3.15 |
| The house-style route recipe | `.claude/skills/endpoint/SKILL.md` | — | the gate order and the mandatory real-HTTP test |

---

## 8. Verification

> **The four-stage write-gate ORDER is pinned as of 2026-09-09** (`WriteGateOrderTest`, closing part of
> `SPEC-NOPROOF-1`). The sequence `.claude/skills/endpoint` makes binding — write-root **503** →
> spec/`ConfigSafetyValidator` **422** → path jail **403** → conflict **409** — was depended on by thirteen
> route modules and verified only by reading call sites, and `WriteGates` itself had **no test of any
> kind**. Every rung of the new ladder violates **more than one** gate, so the responder proves the order.
> ⚠ A grep of `WriteGates.` call sites cannot see this: canonical gate 2 is thrown as
> `respondJson(ex, 422, …)` carrying `findings`, never through the helper.

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
| ~188 further `ControlApi*Test` classes (this directory alone) | one real-HTTP class per route family — the `endpoint` skill's mandatory test |

### 8.2 The SPA client — vitest

Twenty-one specs under `inspecto/api/` (`v1.interceptor.spec.ts`, `auth.interceptor.spec.ts`,
`space.interceptor.spec.ts`, `session.service.spec.ts`, per-service specs) cover envelope unwrap, version-skew
warning, error mapping, Bearer attach and refresh-retry, space-scope rewrite and per-service optimistic
mutation paths.

### 8.3 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `features/inspecto-ops/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule` | `ObjectRoutes`, `NoteRoutes`, `QueueRoutes`, `TagRoutes` — the first external adopters of the public SPI (the Case routes `CaseRoutes` live in the optional `inspecto-case-management` module since MODULE-REORG-P7) |
| the same service name in `inspecto-geo-link`, `inspecto-exchange`, `inspecto-observability`, `inspecto-observability` | the other four gated families |

### 8.4 Cross-language wire vocabularies — `inspecto-ui/src/app/inspecto/contracts/`

Ten committed JSON contracts, each compared by a Java `*ContractTest` and a TypeScript spec so neither
side moves alone: `attribute-spec` (`FindingsSpecContractTest`), `bind-kinds` (`BindKindHomeContractTest`),
`processor-catalog` (`ProcessorCatalogContractTest`), `sql-functions` (`RecordTransformContractTest` — both
sides compile SQL independently), `node-attributes`, `expression-guard`, `measure-grammar` (`step-types` was retired 2026-09-24).
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
