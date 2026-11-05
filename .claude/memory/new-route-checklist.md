---
name: new-route-checklist
description: "A new ControlApi route must clear gates that fail FAR from the route (OpenAPI contract, CapabilityManifest literal scan, authgate coverage); targeted -Dtest runs never catch them"
metadata:
  type: reference
---

Complements the `endpoint` skill. Order:
1. Register the route; any event constant must exist in `EventType`.
2. Mutating route: `ApiContext.withCapability("literal")` — **string literal at the registration site**
   (`CapabilityManifestTest` scans with a literal-only regex; a constant reads as "not registered") — plus an
   `Entry` in `CapabilityManifest`. Do not borrow a `read-shaped` exemption for a route that persists.
3. **Armed test** (`Authenticators.forTest` + `Subject`): 401 no credential, **403 Subject lacking the capability**
   (the only proof of gating), 200 with it. `withCapability` is a NO-OP without a Subject, so unarmed tests pass on
   an ungated route.
4. `docs/api/openapi-v1.json` entry — regenerate, never hand-edit:
   `mvn -o -pl inspecto -am test -Dtest=OpenApiPathsContractTest -Dopenapi.paths.write=true -Dsurefire.failIfNoSpecifiedTests=false`
   (add the edition profile you build with). `OpenApiPathsContractTest` fails in `inspecto-processor` and skips
   ~13 downstream modules.
   ⚠ A route of an optional MODULE (scoring, LA, …) lives in that module's `META-INF/inspecto/openapi.fragment.json`:
   edit the fragment, then `node tools/openapi-merge.mjs --write`. The Maven write above never sees module fragments and
   only reformats the file (592 noise lines, 2026-10-31).
5. `node tools/check-authgate-coverage.mjs` and `route-gating-report.mjs --check` with real exit codes.
6. **The processor's writer / inventory scanners** - a route or helper that writes a registry component, a Pipeline or a
   fixed-name file trips them, and a `-Dtest` naming only your own tests never runs them (four were red on master for
   a day, 2026-11-05). Add them to every new-route run:
   `-Dtest=…,DecisionRuleWritersTest,ImportCapabilityGuardTest,JobWritersTest,ImportLoaderInventoryTest,AbsentModuleRoutesTest,LinkAnalysisRateClassCoverageTest`
   and answer each with a table entry and its reason (or a real guard call). A file read by name under the CONFIG
   root (not the data root) is refused in `ImportPaths.REFUSED_SUFFIXES` / reserved, never just allow-listed.

**Merging openapi-v1.json conflicts:** `json.loads` passing proves nothing (duplicate keys are legal; a route can
land outside `paths`). Assert the parsed `paths` key set = base + new routes and `components` unchanged, or rebuild
from last good + each lane's entry.

**Hand-kept mirror lists** (e.g. `AbsentGeoLinkRoutes.SURFACE` for an optional module) drift silently and feed both
503 stubs and the OpenAPI generator: give them a two-direction parity test where both halves share a classpath.

Related: [[ci-gates-beyond-the-reactor]], [[guard-scope-is-a-silent-exemption]].
