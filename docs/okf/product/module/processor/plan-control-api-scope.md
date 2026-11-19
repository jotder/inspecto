---
type: Capability
title: Control API (API) — Purpose, scope and requirements of record
description: Part 1 of 3 of Control API (API): Purpose & scope; Requirements of record
tags: [api, capability, control-api, v1, envelope, error-codes, etag, idempotency, cursor, openapi, route-module, public-api, semver]
timestamp: 2026-09-08T00:00:00Z
---

# Control API (API) — Purpose, scope and requirements of record

Part 1 of 3 of [Control API (API)](plan-control-api.md) · next: [Specification](plan-control-api-specification.md).

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
| `API-2` | OpenAPI 3.1 contract (`docs/api/openapi-v1.json`) enforced by `ApiContractTest` | Must | ✅ SHIPPED (W2) — ✅ **STRUCTURAL coverage 100 % by construction since 2026-09-15 (`OPENAPI-GEN-1`)**: `OpenApiPathsContractTest` derives every path + method from the live route table (and the absent-module stubs, which mirror the optional modules' routes) and fails the build on a route with no operation or a generated skeleton with no route; regenerate with `-Dopenapi.paths.write=true`, which never touches a hand-written operation. **487 paths / 581 operations = 147 hand-documented + 434 generated skeletons (186 of them optional-module)** (measured 2026-11-01; 281 / 336 when generation landed). What still moves by hand is SCHEMA coverage — the figure `ApiContractTest` now prints. The history below is the pre-generation state. ⚠ **exemplar coverage, then MEASURED and ratcheted**: 19 paths / **24 operations against 266 live route registrations = 9.0 %** (+73 absent-module stubs), measured 2026-09-09 by `ApiContractTest.openApiCoverageOfTheLiveSurfaceIsMeasuredAndRatcheted`, which prints the figure on every run. 🔴 **The "~6 % of ~332" this row and §5 both carried was a UNIT ERROR** — it divided documented *paths* by *(method, pattern)* registrations. ⚠ **The 9.0 % is the CORE surface and is an UPPER BOUND.** Measured under `-Pedition-advanced` the live count is **266, identical to the default reactor** — not because the profile does nothing, but because every optional module (`inspecto-ops`, `inspecto-notify-channels`, `inspecto-geo-link`) depends on `inspecto-processor` while the core declares none of them, so a test living IN the core can never have one on its classpath. The 73 stubs are exactly those modules answering 503. A shipped Advanced bundle registers these 266 **plus** the optional modules' routes against the same 24 documented operations, so its real coverage is **lower**. ⛔ Do not quote 9.0 % as the Advanced figure. ⛔ Whether exemplar coverage is the right posture is still an operator decision (§5) — the guard measures, it does not decide. The served document's own description claimed the retired legacy table until 2026-09-08 (§2 corrections) | All |
| `API-3` | Optimistic concurrency: `ContentHash` + ETag / `If-None-Match` / `If-Match` on Components | Must | ✅ **SHIPPED both halves — server W3, client 2026-09-11.** `If-Match` is *honoured* (stale ⇒ `409 CONFLICT_STALE_VERSION`), never *required*, so an old client still writes. The SPA now sends it from `/config/write` (`CLIENT-HALVES-1` (a)) and from the Schema editor's `PUT /components/schema/{id}` (`SCHEMA-DIALOG-IFMATCH-1`) — the Components half of this row finally has the consumer it names (§3.5) | All ✅ **Decided 2026-09-10 (`CONSUMER-PAIRS-1`):** the SPA **ADOPTS** `If-Match` in both writing panes (`CLIENT-HALVES-1` (row closed)); cursor pagination is **KEPT as API surface** — the SPA adopts a cursor only when a list outgrows one page, no build row. |
| `API-4` | `GET /bootstrap` metadata-first boot (features, `authMode`, permissions) | Must | ✅ SHIPPED (W3/W6) | All |
| `API-5` | Retire the unversioned route surface — business routes require `/api/v1` | Should | ✅ SHIPPED 2026-07-25 (BACKLOG D3); the sunset apparatus deleted with it | All |
| `API-6` | Gateway / IAM drop-in: WSO2 gateway + Keycloak blueprints for Standard | Must (S) | 🟡 **PARTIAL by construction** — the seams are real (gateway trust mode, PKCE public client, D15 vendor neutrality) and the two blueprints exist; **nothing in the tree consumes or tests them, and no live gateway has ever verified them** (the README says so) | S |
| `API-7` | Java embedding API stability policy (SemVer, `@PublicApi`) | Must | ✅ SHIPPED as *policy* — ⚠ **no automated guard**: nothing asserts the marked surface did not shrink; 256 of 286 `since` values name the unreleased `4.0.0` (2026-11-01) (§3.13) | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`API-3`** read a flat `SHIPPED`; the concept page said "writes require `If-Match`". `ETags.requireMatch`
  (`inspecto/src/main/java/com/gamma/control/ETags.java`) rejects **a present, non-matching**
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
  against **~332 built-in registrations** the code's own census records (`ControlApi.java`), plus
  `ServiceLoader`-discovered module routes. The first two are dated snapshots; the stakeholder figure also
  names retired route vocabulary (`sources`, `flows`, `issues`) and is plan §8's cluster, reconciled at step 6.

**Two standing notes no status token can carry:**

1. ⚠ **`CP-01` (Control API v1) is ✅ for Basic, and five route families answer `503` there.** Both are
   true: the *transport* — envelope, errors, correlation, gzip, ETags, cursors — is edition-neutral core;
   the *route inventory* is not. Geo/link (5 paths), exchange (11), `/metrics` exposition, `/events*` and
   ~46 `/objects|/notes|/tags` paths are optional modules since EDG-01 (2026-09-07/08) and answer
   `503 CAPABILITY_UNAVAILABLE` naming the module — never `404`. A gateway handed `openapi-v1.json` is
   handed neither the stubs nor the module paths (§5).
2. ⚠ **The contract's own rules and the pending MAJOR disagree on what "breaking" means.** `docs/api/README.md`
   says *additive-only within v1; breaking ⇒ `/api/v2`*. The draft release notes carry wire-visible renames
   (`?flow=` → `?pipeline=` dual-read, `batch_id` → `consignment_id` on persisted surfaces) riding a MAJOR
   version, not a `/api/v2`. The resolution the repo has actually adopted, stated here so it stops being
   re-argued: **nothing after `v3.11.0` has shipped, so no `/api/v1` consumer exists outside this tree; the
   additive-only rule binds from the first release that publishes v1**, and until then a MAJOR may carry
   wire renames with dual-read. `api-stability.md` §Release baseline is the authority for that premise.
