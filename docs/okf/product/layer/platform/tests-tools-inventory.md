---
type: TestsInventory
title: Platform tooling — tests inventory
item: layer.platform
description: Generated list of the tools/*.test.mjs guard and generator tests for layer.platform, with how to run them.
tags: [tests, layer]
---

# Platform tooling — tests inventory (`layer.platform`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked `tools/*.test.mjs` files — never edit this table. Hub:
> [index](index.md) · Product Map Item [`layer.platform`](../../../../../product-map/layer/platform.toon).

53 test file(s). Run one, or all:

```bash
node --test tools/check-backlog-items.test.mjs
node --test tools/
```

| test file | summary |
|---|---|
| `tools/check-backlog-items.test.mjs` | Fixture test for tools/check-backlog-items.mjs. Run: node --test tools/check-backlog-items.test.mjs |
| `tools/check-bundle-spaces.test.mjs` | node --test tools/check-bundle-spaces.test.mjs |
| `tools/check-data-ops-duplication.test.mjs` | Fixture tests for tools/check-data-ops-duplication.mjs. Run: node --test tools/check-data-ops-duplication.test.mjs |
| `tools/check-demo-auth-isolation.test.mjs` | Fixture test for tools/check-demo-auth-isolation.mjs — each planted violation must turn the guard RED. |
| `tools/check-doc-flags.test.mjs` |  |
| `tools/check-doc-routes.test.mjs` |  |
| `tools/check-docs-touched.test.mjs` | Fixture test for tools/check-docs-touched.mjs. Run: node --test tools/check-docs-touched.test.mjs |
| `tools/check-engine-names-no-la.test.mjs` | Fixture test for tools/check-engine-names-no-la.mjs. Run: node --test tools/check-engine-names-no-la.test.mjs |
| `tools/check-family-count.test.mjs` | Negative fixtures for tools/check-family-count.mjs. Run: node --test tools/check-family-count.test.mjs |
| `tools/check-jar-signatures.test.mjs` | Fixtures for tools/check-jar-signatures.mjs (P3h per-jar signing). Run: node --test tools/check-jar-signatures.test.mjs |
| `tools/check-launchers.test.mjs` | Negative fixtures for tools/check-launchers.mjs. Run: node --test tools/check-launchers.test.mjs |
| `tools/check-module-architecture.test.mjs` | Fixture test for the pure helpers of tools/check-module-architecture.mjs. Run: node --test tools/check-module-architecture.test.mjs |
| `tools/check-module-deps.test.mjs` | Fixture test for tools/check-module-deps.mjs — each way an extracted module can start reaching the core must turn the |
| `tools/check-native-licences.test.mjs` | Fixture tests for check-native-licences.mjs (NATIVE-LICENCE-LINUX-MACOS-1): plants each violation in a |
| `tools/check-offerings.test.mjs` | Fixture test for tools/check-offerings.mjs. Run: node --test tools/check-offerings.test.mjs |
| `tools/check-openapi-fragments.test.mjs` | Negative fixtures for tools/check-openapi-fragments.mjs (+ the split/merge round trip on the REAL document). |
| `tools/check-package-strict.test.mjs` | Run: node --test tools/check-package-strict.test.mjs |
| `tools/check-product-docs.test.mjs` | Fixture test for tools/check-product-docs.mjs. Run: node --test tools/check-product-docs.test.mjs |
| `tools/check-product-map.test.mjs` | Fixture test for tools/check-product-map.mjs. Run: node --test tools/check-product-map.test.mjs |
| `tools/check-release-signing.test.mjs` |  |
| `tools/check-sbom-modules.test.mjs` | Negative fixtures for tools/check-sbom-modules.mjs. Run: node --test tools/check-sbom-modules.test.mjs |
| `tools/compile-clean.test.mjs` | Fixture test for tools/compile-clean.mjs — the verdict must come from the LOG, never from an exit code, and each way |
| `tools/customer-build.test.mjs` | Fixture test for tools/customer-build.mjs. Run: node --test tools/customer-build.test.mjs |
| `tools/fetch-duckdb-extensions.test.mjs` | Offline tests for tools/fetch-duckdb-extensions.mjs's --only / --platform selection (D-8, 2026-09-26). |
| `tools/gen-la-audit-data.test.mjs` | Determinism of tools/gen-la-audit-data.py. Run: node --test tools/gen-la-audit-data.test.mjs |
| `tools/gen-la-claims-data.test.mjs` | Determinism of tools/gen-la-claims-data.py. Run: node --test tools/gen-la-claims-data.test.mjs |
| `tools/gen-la-mm-data.test.mjs` | Determinism of tools/gen-la-mm-data.py. Run: node --test tools/gen-la-mm-data.test.mjs |
| `tools/gen-la-showcase-data.test.mjs` | Determinism of tools/gen-la-showcase-data.py. Run: node --test tools/gen-la-showcase-data.test.mjs |
| `tools/java-closure.test.mjs` | Fixture test for tools/java-closure.mjs — each blind spot that bit the D-1 closure script must now be SEEN. |
| `tools/jlink-modules.test.mjs` |  |
| `tools/la-audit-smoke.test.mjs` | node --test tools/la-audit-smoke.test.mjs  -  the pure helpers of the la-audit smoke runner (no server). |
| `tools/la-claims-smoke.test.mjs` | node --test tools/la-claims-smoke.test.mjs  -  the claims smoke's pure verdicts and its dry run (no server). |
| `tools/la-demo-burst.test.mjs` | node --test tools/la-demo-burst.test.mjs - runBurst against a fake server with the real limits (pool 18 slots, queue 5, bucket 20). |
| `tools/la-demo-figures.test.mjs` | node --test tools/la-demo-figures.test.mjs - verdicts on fixtures shaped like GET /inv/value-measures, plus a fake-server run. |
| `tools/la-demo-smoke.test.mjs` | Unit test for the pure assertion helpers of tools/la-demo-smoke.mjs, plus a stub-server run. node --test tools/la-demo-smoke.test.mjs |
| `tools/la-mm-smoke.test.mjs` | node --test tools/la-mm-smoke.test.mjs  -  the pure verdicts of the mobile-money smoke runner, plus a dry run (no server). |
| `tools/offering-classpath.test.mjs` | Fixture test for tools/offering-classpath.mjs. Run: node --test tools/offering-classpath.test.mjs |
| `tools/product-map-import.test.mjs` | Fixture test for tools/product-map-import.mjs. Run: node --test tools/product-map-import.test.mjs |
| `tools/regroup-modules.test.mjs` | Fixture-based tests for tools/regroup-modules.mjs: a tiny synthetic git repo in a temp dir, never the real tree. |
| `tools/release-notes.test.mjs` | Fixture test for tools/release-notes.mjs. Run: node --test tools/release-notes.test.mjs |
| `tools/render-api-routes.test.mjs` |  |
| `tools/render-feature-db.test.mjs` | Fixture test for tools/render-feature-db.mjs. Run: node --test tools/render-feature-db.test.mjs |
| `tools/render-offerings-matrix.test.mjs` | Fixture test for tools/render-offerings-matrix.mjs. Run: node --test tools/render-offerings-matrix.test.mjs |
| `tools/render-product-map.test.mjs` | Fixture test for tools/render-product-map.mjs. Run: node --test tools/render-product-map.test.mjs |
| `tools/render-tests-inventory.test.mjs` | Fixture test for tools/render-tests-inventory.mjs. Run: node --test tools/render-tests-inventory.test.mjs |
| `tools/sbom-modules.test.mjs` | Fixtures for tools/sbom-modules.mjs (P3f per-module SBOMs). Run: node --test tools/sbom-modules.test.mjs |
| `tools/seed-la-audit.test.mjs` | node --test tools/seed-la-audit.test.mjs  -  request building of the la-audit seed script against a MOCKED fetch (no server). |
| `tools/seed-la-claims.test.mjs` | node --test tools/seed-la-claims.test.mjs  -  request building of the claims seed script against a MOCKED fetch (no server). |
| `tools/seed-la-mm.test.mjs` | node --test tools/seed-la-mm.test.mjs  -  request building of the mobile-money seed script against a MOCKED fetch (no server). |
| `tools/seed-la-showcase.test.mjs` | node --test tools/seed-la-showcase.test.mjs  -  request building of the seed script against a MOCKED fetch (no server). |
| `tools/seed-telco-fraud-demo.test.mjs` | node --test tools/seed-telco-fraud-demo.test.mjs |
| `tools/vuln-db-refresh.test.mjs` | Offline tests for tools/vuln-db-refresh.mjs. Run: node --test tools/vuln-db-refresh.test.mjs |
| `tools/vuln-scan.test.mjs` | Offline tests for tools/vuln-scan.mjs (ASSURE-OPERABILITY-1). Run: node --test tools/vuln-scan.test.mjs |
