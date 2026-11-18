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
8080); a shutdown hook closes the API + spaces. (`com.gamma.inspector.MainApp` is a **separate** one-shot CLI (`migrate-configs` only)
suite, not the server — see [build & run](../../layer/platform/admin-guide-launch-flags.md).)

## `dispatch`

Per request: strip an optional `/api` prefix (Angular dev-proxy); apply CORS if `-Dcontrol.cors` set; answer
`OPTIONS` preflight; match the `/spaces/{id}/…` seam (extract + validate id, rewrite path, bind the `space`
MDC — see [multi-space](architecture-processor.md)); match `routes` by pattern+method; fall through to static SPA assets
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
(`/events/search|export|views` — ⚠ the OPTIONAL `inspecto-observability` module since EDG-01 cell 6, 503 on Personal; the core audit read is `/audit/search|export`), connections, components ([registry](spec-component-registry.md)),
objects (ops), catalog, config/assist, enrichment, per-space settings docs (`/settings/branding|geo` and
`/nav/menus` — the Menu Builder tree; each a fixed-filename TOON in the space's config tree, PUT gated by
write-root 503 + `canAuthorWorkbench`, no jail/conflict gates since nothing caller-supplied touches a path.
**`/nav/menus` is gated by `canCurateMenus`** — split out of `canAuthorWorkbench` on 2026-07-25 (D4,
shipped end-to-end `fc637b1b`/`0c375ff5`; `okf/product/module/access/admin-guide-auth-security.md`). ⚠ This paragraph said
until 2026-09-08 that curation reused `canAuthorWorkbench` and that the split "stays an open product
question" — both were superseded the same day they were written. Since UIE-7 (2026-09-25) the PUT also 422s
an unsafe `kind: route` binding target and a `landing` that names no leaf — see
[`surfaces.md` §3.4](../../layer/product/plan-surfaces.md))
— plus the v1-era additions: `GET /bootstrap`
(server capabilities incl. `features.authMode`), `/auth/*` (the Standard-edition BFF session routes),
`POST /queries/{id}/run` (the `com.gamma.query` catalog, W4), and async run polling
(`GET /jobs/runs/{runId}`, `GET /runs/runs/{runId}`).

### `GET /consignments/{consignmentId}` — one Consignment across lanes (X5 L4, 2026-11-17)

`ConsignmentRoutes`; design of record `archived-documents/plans-archive/2026-11-17-x5-stepinfo-envelope-plan.md` §3.7 (D8 = a). One read
joins five stores for one id: the ingest ledger row (`status`, `members` = its `/runs/{name}/files` rows, `parked`
from the manifest), its Step Receipts (`steps`, `stepsTotal`; first 500), its registry rows (`outputs`,
`outputsTotal`; first 200, the `/runs/{name}/outputs` row shape), the X2 run links (`upstream` = what its own job
run read, `downstream` = the runs that read it, each with **its own** `consignmentId`), and its newest 50 Signals.

- **Absent ≠ empty.** A store that cannot answer leaves its key ABSENT and `known.{steps,runs,outputs}` false.
  With `-Dconsignment.steps=false`, `steps` and `lanes` are absent — never `[]`; with no Consignment registry (a
  Basic-like install) `outputs` and `steps` are absent and `known.outputs` is false. 404 only when no store knows
  the id (ledger, registry, receipts, run sources).
- **The `run_id` join** (`DbConsignmentOutputStore.consignmentsOfRuns`, one query per table per 1 000 runs — PostgreSQL caps a statement at 65 535 parameters): a job run
  row does not name its own Consignment, so `downstream[].consignmentId` comes from the run's receipts, else from
  `consignment_outputs.run_id`; `upstream` comes from the receipts'/outputs' `run_id` (the newest 20 runs) through
  the run store's sources. Ingest → job → back is one hop each way.
- **Bounded.** Every collection is cut in its read (`LIMIT`) and carries its true total: `steps` 500
  (`stepCount`, always the stored count: a receipt this build cannot read is skipped from the page, not the total), `outputs` 200 (`outputCount`), `members` 200 (`StatusStore.consignmentFiles`; the total is the
  ledger row's `member_count` when cut), `upstream` / `downstream` 50 (`runsDerivedFrom(id, limit)`,
  `countRunsDerivedFrom`), Signals 50. The ledger row is found by id (`StatusStore.consignment`) and is the NEWEST row of the id — a drain appends its
  SUCCESS after the PARKED row, so the oldest would serve a drained Consignment as PARKED; `members` are the newest
  row of each member file. The hint Pipeline (the receipts' or registry's producer) is asked first, every other
  Pipeline once; `DbStatusStore` answers both by a query
  on the payload's `"consignment_id":<id>` text (LIKE, wildcards escaped, exact match re-checked), the file store
  by filtering its rows.
- **Status** is the ledger row's; for a job-lane Consignment the newest run's, from the live-run registry, else
  from `DbJobRunStore.run(runId)` (after a restart).
- **Masking.** Signal messages as `GET /signals` serves them (scrubbed at emit; `SecretScrubber` again on read). A
  receipt's `diag.msg` is the raw exception text on the job lane, so it is masked on read with `FailureText.scrub`
  (quoted literals and echoed lines → `<redacted>`; no Space salt is read, since reading it may create it) and then
  `SecretScrubber`.
- **Open read** under Space admission, like its siblings `/runs/{name}/batches`, `/files`, `/outputs`, `/errors`
  (operator decision on the L4 review, 2026-11-17; no `CapabilityManifest` entry).
- Tests: `ControlApiConsignmentTrailTest` (open read + 404, receipts off, no registry, the hop both ways, the
  run-store status fallback, the output cap, masking); `DbConsignmentOutputStoreTest`, `DbJobRunStoreTest`,
  `DbStatusStoreTest.readsOneConsignmentsRowAndFilesById`.

## Shared route plumbing (as-built 2026-08-27)

Consolidated by the Phase-1 simplification arc (plan archived at
`archived-documents/plans-archive/java-simplification-plan.md`). These are the homes to reuse — do
not re-introduce per-class copies:

- **`RouteErrors`** — the domain-exception → status mappers, one per contract shape:
  `mapErrors` (404/400 lookup), `mapCaseErrors` (404/422/400 case workflow),
  `mapPreviewErrors` (400/422 preview, takes a checked-throwing `PreviewSupplier`), plus
  `exists(store,type,id)` / `existing(store,type,label,id)` — the component-existence gate that five
  route classes used to copy, parameterised by type constant with the 404 noun as `label` so every
  message stays byte-identical. ⚠ Sites whose mapping genuinely differs keep their local shape.
- **`SpiSlot<T>`** — the edition-seam lookup (first-wins `ServiceLoader`, cached, `forTest` override).
  `Authenticators` / `AccessDeciders` / `TokenRelays` are thin typed facades over one slot each; the
  facade *names* are the documented seam and stay. ⛔ Do NOT generalise this to the collect-all
  ServiceLoader sites (`Parsers`, `Decompressors`, `PipelineNodeTypes`, `JobPackManager`, …) — each
  carries semantic per-element logic (id validation, override layering, best-suffix match,
  same-classloader filter) and was measured as not worth unifying.
- **`WriteGates`** — the 4-gate write chain, already fully converged before that arc (28 files, 120
  call sites; no hand-rolled duplicates exist).

**Route-module granularity.** `PipelineRoutes` (1677 lines, 20 routes) became
`PipelineListRoutes` / `PipelineGraphRoutes` / `PipelineSettingsRoutes` / `PipelineRenameRoutes`
plus `PipelineSupport` (the three genuinely shared statics: `liftedPipelines` — also
`ComponentRoutes`' safe-delete check — `pipelinesRootOrNull`, `findingKey`). `ConfigRoutes`' 10
routes became `ConfigPreviewRoutes` / `ConfigWriteRoutes` / `ConfigReadRoutes` plus
`ConfigFileSupport`. ⚠ **`ConfigRoutes` still exists** and is no longer a `RouteModule` — it is the
static findings-helper home (`schemaFileFindings`, `armedWithoutSchemaFindings`,
`routeArmingFindings`, `unknownConnectionFindings`, `resolves`), because `PipelineRoutes`'
successors, `RunRoutes`, `DataSourceRoutes` and `SchemaFileFindingsTest` all call it statically.
Registration order in `ControlApi` is preserved and load-bearing (`/config/spec/…` must register
before the generic two-segment read).

## `/api/v1`

Every business route is dispatched **only** under the versioned **`/api/v1`** prefix (API-5, 2026-07-25)
with a success/error **envelope** (structured errors from the `ErrorCodes` catalog), `WriteGates`, a
`Correlation-ID` response header, and gzip (W1). The unversioned allow-list is `isInfraRoute` (`/health`,
`/ready`, `/metrics`, `/metrics/acquisition`); `recordLegacyUsage` and the whole sunset apparatus were
**deleted** with the surface. *(This section described the legacy routes as still served until 2026-09-08.)*
Contract detail: [API v1](api-v1.md); requirement of record:
[Control API capability spec](plan-control-api.md).

## Auth by edition

The core/Personal edition is **auth-free**: every route is open — no token, guard, or login. On **Standard**
the `inspecto-oidc` module (reactor-gated behind the `edition-standard` Maven profile) enforces OIDC by
implementing the `Authenticator` / `Subject` / `TokenRelay` SPIs in `com.gamma.control` (see
[auth & security](../access/admin-guide-auth-security.md)). The `-Dassist.write.root` 503 write-gate is **separate** from
auth and stays in all editions.
