---
name: endpoint
description: >
  Add a new ControlApi route in house style. Trigger on "ENDPOINT <METHOD /path>" or any request
  to add or change a control-plane HTTP route. Encodes the fail-closed gate order (write-root
  503 → spec/ConfigSafetyValidator 422 → path jail 403 → conflict 409 → act atomically),
  ConfigCodec reuse, and the mandatory real-HTTP test class covering every gate.
---

# /endpoint — new ControlApi route

House style for control-plane routes. The core is **auth-free** — no auth/scope/401 logic.

## Gate order (fail closed, in this order)

1. **Write-root disabled → 503** — when `-Dassist.write.root` is unset (this is a write gate, not auth).
2. **Spec + `ConfigSafetyValidator` ERROR → 422** — validate the payload against the ConfigSpec.
3. **Path jail → 403** — `resolve().normalize().startsWith(root)` on every user-supplied path.
4. **Conflict → 409** — existing resource / concurrent-change checks.
5. **Act atomically** — write temp + move, or single mutation; no partial state on failure.

## Rules

- Reuse `ConfigCodec` for (de)serialization — never hand-roll TOON/JSON mapping.
- 🔴 **A route wrapped in `ApiContext.withCapability(...)` MUST also get an `Entry` in
  `CapabilityManifest`.** The manifest is the published declaration of what each route is gated on; the
  code is the enforcement. `CapabilityManifestTest.manifestMatchesTheRegistrationSitesExactly` fails the
  build in BOTH directions (gated-but-undeclared, declared-but-unregistered), and because the reactor is
  fail-fast that one failure leaves ~13 later modules SKIPPED — i.e. unverified, not passing. ⚠ It fails in
  `inspecto-processor`, nowhere near the route you added, so the failure does not look like yours.
  Entries are grouped by `// XxxRoutes` in rough alphabetical order — add the group, not just the line.
- 🔴 **EVERY live route MUST have an operation in `docs/api/openapi-v1.json`.**
  `OpenApiPathsContractTest.everyLiveRouteHasAnOperationInTheContract` fails the build otherwise, naming
  the routes it could not find. Regenerate rather than hand-editing:
  `mvn -o -pl inspecto -am test -Dtest=OpenApiPathsContractTest -Dopenapi.paths.write=true -Dsurefire.failIfNoSpecifiedTests=false -Pedition-professional`
  — it writes the skeleton itself; fill in schemas by hand afterwards if the route deserves them.
  ⚠ **This has the same shape as the `CapabilityManifest` trap above and bites the same way:** it fails in
  `inspecto-processor`, far from your route, and because the reactor is fail-fast it leaves ~13 later
  modules **SKIPPED — unverified, not passing**. ⛔ **A targeted `-Dtest=` run of your own new test class
  will NOT catch it** (measured 2026-09-22: a new settings route passed its own 4 tests and every guard,
  and still took the whole reactor red). The route checklist is: register → `CapabilityManifest` if gated
  → **the absent-module stub if the route lives in an OPTIONAL module** → **openapi-v1.json** →
  `tools/route-gating-report.mjs` if mutating.
- 🔴 **A route in an OPTIONAL edition module must ALSO be listed in that module's absent-module stub**
  (`AbsentGeoLinkRoutes.SURFACE` for `inspecto-geo-link`). The core declares no dependency on these
  modules — deliberately, since Personal must not ship them — so **that hand-kept table is the ONLY
  thing the core can see**, and TWO mechanisms read it: the 503 "not installed" stubs a Personal build
  serves, and the OpenAPI skeleton generator. ⛔ **Omit it and you get two silent failures from one
  mistake**: the path **404s instead of 503ing** on Personal (a 404 reads as "wrong URL" and sends the
  operator hunting a typo), and it is **missing from `openapi-v1.json` while the contract guard still
  reports GREEN** — that guard enforces "every LIVE route has an operation" where *live* means *what it
  can see*. Measured 2026-09-22: four routes had drifted this way — `/inv/schema/overlap-profile`
  (LA-15) and the three `/inv/snapshots*` (LA-03) — despite the stub class's javadoc having asked since
  2026-09-07 that both lists be "kept in sync". ⚠ `GeoLinkAbsentSurfaceParityTest` now asserts that
  table against the live registrations in BOTH directions, because a comment asking two lists to agree
  is not a mechanism. **Check for a third copy too** — `NoGeoLinkShipsInThePersonalBuildTest` carried its
  own private duplicate of the same list and passed by never touching the routes that had drifted.
- Register the route in `ControlApi` following the surrounding pattern (JDK HttpServer, manual DI).
- **Real-HTTP test class covering every gate**, modeled on `ControlApiConfigWriteTest`
  (ephemeral port, actual requests, one test per gate + the happy path).
- ⚠ **A 2xx `/api/v1` body is the ENVELOPE `{data, metadata, links, diagnostics}`** (`Envelope.shape`),
  so a happy-path test must read its payload under `data` — `JSON.readTree(body).get("data")`, the
  idiom in `ControlApiAsyncV1Test`/`ControlApiDbBrowserTest`. Gate tests that only assert a status
  code pass either way, so this surfaces as NPEs in the happy path ALONE and reads like a handler bug
  when it is a test bug. Non-2xx uses `{error: {…}}` instead — a different shape, not the envelope.
- A **read** route has no write-root gate: skip 503 rather than inventing one. Prefer a key that
  cannot traverse (a bare name, separators refused → 403) over jailing a caller-supplied path, and
  jail the resolved result anyway.
- Bound any collection a route returns (`?limit=` or a hard cap + a `truncated` flag reporting the
  TRUE total) — a diagnostic read must not become an unbounded export.
- Full verify before reporting done: GAUNTLET — see the `build-verify` skill
  (`mvn -o clean test`; UI trio only if UI files changed).
- Leave the change uncommitted unless the operator asks (release-workflow skill governs commits).
