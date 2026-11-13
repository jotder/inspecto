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

104 test file(s). Run one, or all:

```bash
node --test tools/affected-modules.test.mjs
node --test tools/
```

| test file | summary |
|---|---|
| `tools/affected-modules.test.mjs` | Fixture test for tools/affected-modules.mjs. Run: node --test tools/affected-modules.test.mjs |
| `tools/assemble.test.mjs` | Tests for tools/assemble.mjs — PRODUCT-ASSEMBLY-1 PA-2. Run: node --test tools/assemble.test.mjs |
| `tools/check-backlog-items.test.mjs` | Fixture test for tools/check-backlog-items.mjs. Run: node --test tools/check-backlog-items.test.mjs |
| `tools/check-bundle-spaces.test.mjs` | node --test tools/check-bundle-spaces.test.mjs |
| `tools/check-data-ops-duplication.test.mjs` | Fixture tests for tools/check-data-ops-duplication.mjs. Run: node --test tools/check-data-ops-duplication.test.mjs |
| `tools/check-demo-auth-isolation.test.mjs` | Fixture test for tools/check-demo-auth-isolation.mjs — each planted violation must turn the guard RED. |
| `tools/check-doc-flags.test.mjs` |  |
| `tools/check-doc-routes.test.mjs` |  |
| `tools/check-docs-touched.test.mjs` | Fixture test for tools/check-docs-touched.mjs. Run: node --test tools/check-docs-touched.test.mjs |
| `tools/check-engine-names-no-la.test.mjs` | Fixture test for tools/check-engine-names-no-la.mjs. Run: node --test tools/check-engine-names-no-la.test.mjs |
| `tools/check-entitled-loaders.test.mjs` | Fixture test for tools/check-entitled-loaders.mjs: the real repo is green, and each planted hole turns it red. |
| `tools/check-family-count.test.mjs` | Negative fixtures for tools/check-family-count.mjs. Run: node --test tools/check-family-count.test.mjs |
| `tools/check-jar-signatures.test.mjs` | Fixtures for tools/check-jar-signatures.mjs (P3h per-jar signing). Run: node --test tools/check-jar-signatures.test.mjs |
| `tools/check-launchers.test.mjs` | Negative fixtures for tools/check-launchers.mjs. Run: node --test tools/check-launchers.test.mjs |
| `tools/check-module-architecture.test.mjs` | Fixture test for the pure helpers of tools/check-module-architecture.mjs. Run: node --test tools/check-module-architecture.test.mjs |
| `tools/check-module-deps.test.mjs` | Fixture test for tools/check-module-deps.mjs — each way an extracted module can start reaching the core must turn the |
| `tools/check-native-licences.test.mjs` | Fixture tests for check-native-licences.mjs (NATIVE-LICENCE-LINUX-MACOS-1): plants each violation in a |
| `tools/check-offerings.test.mjs` | Fixture test for tools/check-offerings.mjs. Run: node --test tools/check-offerings.test.mjs |
| `tools/check-openapi-fragments.test.mjs` | Negative fixtures for tools/check-openapi-fragments.mjs (+ the split/merge round trip on the REAL document). |
| `tools/check-package-strict.test.mjs` | Run: node --test tools/check-package-strict.test.mjs |
| `tools/check-product-build.test.mjs` | node --test tools/check-product-build.test.mjs - the Product matrix check (PRODUCT-ASSEMBLY-1 PA-5d). |
| `tools/check-product-docs.test.mjs` | Fixture test for tools/check-product-docs.mjs. Run: node --test tools/check-product-docs.test.mjs |
| `tools/check-product-map.test.mjs` | Fixture test for tools/check-product-map.mjs. Run: node --test tools/check-product-map.test.mjs |
| `tools/check-products.test.mjs` | Tests for tools/products.mjs checkModel (the rules of tools/check-products.mjs) — PRODUCT-ASSEMBLY-1 PA-1. |
| `tools/check-public-api.test.mjs` | Fixture test for tools/check-public-api.mjs. Run: node --test tools/check-public-api.test.mjs |
| `tools/check-reactor-verdict.test.mjs` | tools/check-reactor-verdict.mjs, driven end to end over a synthetic log and surefire reports. |
| `tools/check-release-signing.test.mjs` |  |
| `tools/check-sbom-modules.test.mjs` | Negative fixtures for tools/check-sbom-modules.mjs. Run: node --test tools/check-sbom-modules.test.mjs |
| `tools/check-tool-tests-wired.test.mjs` | Negative fixtures for tools/check-tool-tests-wired.mjs. Run: node --test tools/check-tool-tests-wired.test.mjs |
| `tools/compile-clean.test.mjs` | Fixture test for tools/compile-clean.mjs — the verdict must come from the LOG, never from an exit code, and each way |
| `tools/compose-space.test.mjs` | Tests for tools/compose-space.mjs - PRODUCT-ASSEMBLY-1 PA-5 (D-PA14). Run: node --test tools/compose-space.test.mjs |
| `tools/customer-build.test.mjs` | Fixture test for tools/customer-build.mjs. Run: node --test tools/customer-build.test.mjs |
| `tools/dev-doctor.test.mjs` | Fixture test for tools/dev-doctor.mjs. Run: node --test tools/dev-doctor.test.mjs |
| `tools/entitle.test.mjs` | tools/entitle.mjs - signed entitlements (PRODUCT-ASSEMBLY-1 PA-7a). |
| `tools/fetch-duckdb-extensions.test.mjs` | Offline tests for tools/fetch-duckdb-extensions.mjs's --only / --platform selection (D-8, 2026-09-26). |
| `tools/gen-la-audit-data.test.mjs` | Determinism of tools/gen-la-audit-data.py. Run: node --test tools/gen-la-audit-data.test.mjs |
| `tools/gen-la-benefits-data.test.mjs` | Determinism and planted facts of tools/gen-la-benefits-data.py. Run: node --test tools/gen-la-benefits-data.test.mjs |
| `tools/gen-la-card-data.test.mjs` | Determinism and planted facts of tools/gen-la-card-data.py. Run: node --test tools/gen-la-card-data.test.mjs |
| `tools/gen-la-claims-data.test.mjs` | Determinism of tools/gen-la-claims-data.py. Run: node --test tools/gen-la-claims-data.test.mjs |
| `tools/gen-la-crypto-data.test.mjs` | Determinism and planted facts of tools/gen-la-crypto-data.py. Run: node --test tools/gen-la-crypto-data.test.mjs |
| `tools/gen-la-health-data.test.mjs` | Determinism and planted facts of tools/gen-la-health-data.py. Run: node --test tools/gen-la-health-data.test.mjs |
| `tools/gen-la-insider-data.test.mjs` | Determinism and planted facts of tools/gen-la-insider-data.py. Run: node --test tools/gen-la-insider-data.test.mjs |
| `tools/gen-la-logistics-data.test.mjs` | Determinism and planted facts of tools/gen-la-logistics-data.py. Run: node --test tools/gen-la-logistics-data.test.mjs |
| `tools/gen-la-mfs-data.test.mjs` | Determinism and planted facts of tools/gen-la-mfs-data.py. Run: node --test tools/gen-la-mfs-data.test.mjs |
| `tools/gen-la-mm-data.test.mjs` | Determinism of tools/gen-la-mm-data.py. Run: node --test tools/gen-la-mm-data.test.mjs |
| `tools/gen-la-ownership-data.test.mjs` | Golden test of tools/gen-la-ownership-data.py. Run: node --test tools/gen-la-ownership-data.test.mjs |
| `tools/gen-la-police-data.test.mjs` | Determinism and planted facts of tools/gen-la-police-data.py. Run: node --test tools/gen-la-police-data.test.mjs |
| `tools/gen-la-procurement-data.test.mjs` | Determinism and planted facts of tools/gen-la-procurement-data.py. Run: node --test tools/gen-la-procurement-data.test.mjs |
| `tools/gen-la-showcase-data.test.mjs` | Determinism of tools/gen-la-showcase-data.py. Run: node --test tools/gen-la-showcase-data.test.mjs |
| `tools/gen-la-telco-ra-data.test.mjs` | Determinism and planted facts of tools/gen-la-telco-ra-data.py. Run: node --test tools/gen-la-telco-ra-data.test.mjs |
| `tools/gen-la-trade-data.test.mjs` | Golden test of tools/gen-la-trade-data.py. Run: node --test tools/gen-la-trade-data.test.mjs |
| `tools/gen-la-utility-data.test.mjs` | Determinism and planted facts of tools/gen-la-utility-data.py. Run: node --test tools/gen-la-utility-data.test.mjs |
| `tools/gen-la-video7-data.test.mjs` | Determinism and planted facts of tools/gen-la-video7-data.mjs. Run: node --test tools/gen-la-video7-data.test.mjs |
| `tools/inspecto-app.test.mjs` | node --test tools/inspecto-app.test.mjs - the App authoring kit (PRODUCT-ASSEMBLY-1 PA-6a, D-PA18 / D-PA19). |
| `tools/java-closure.test.mjs` | Fixture test for tools/java-closure.mjs — each blind spot that bit the D-1 closure script must now be SEEN. |
| `tools/jlink-modules.test.mjs` | ── PACKAGE-DEMOAUTH-ADVANCED-1: the demo bundle's module set follows the bundle it really stages ───────────────────────────── |
| `tools/la-audit-smoke.test.mjs` | node --test tools/la-audit-smoke.test.mjs  -  the pure helpers of the la-audit smoke runner (no server). |
| `tools/la-claims-smoke.test.mjs` | node --test tools/la-claims-smoke.test.mjs  -  the claims smoke's pure verdicts and its dry run (no server). |
| `tools/la-demo-burst.test.mjs` | node --test tools/la-demo-burst.test.mjs - runBurst against a fake server with the real limits (pool 18 slots, queue 5, bucket 20). |
| `tools/la-demo-figures.test.mjs` | node --test tools/la-demo-figures.test.mjs - verdicts on fixtures shaped like GET /inv/value-measures, plus a fake-server run. |
| `tools/la-demo-smoke.test.mjs` | Unit test for the pure assertion helpers of tools/la-demo-smoke.mjs, plus a stub-server run. node --test tools/la-demo-smoke.test.mjs |
| `tools/la-mm-smoke.test.mjs` | node --test tools/la-mm-smoke.test.mjs  -  the pure verdicts of the mobile-money smoke runner, plus a dry run (no server). |
| `tools/la_gen_io.test.mjs` | Unit test of tools/la_gen_io.py, the safe output helper of the gen-la-*-data.py generators (LA-GENERATOR-DELETE-FIRST-1). |
| `tools/maven-repo.test.mjs` | Run: node --test tools/maven-repo.test.mjs |
| `tools/offering-classpath.test.mjs` | Fixture test for tools/offering-classpath.mjs. Run: node --test tools/offering-classpath.test.mjs |
| `tools/package-la-showcase.test.mjs` | Unit tests for tools/package-la-showcase.mjs (LA-SHOWCASE-DIST-1): the Space / data split and the space.toon it writes. |
| `tools/pick-addons.test.mjs` | tools/pick-addons.mjs - the Add-on picker (PRODUCT-ASSEMBLY-1 PA-6c). |
| `tools/precommit.test.mjs` | Fixture test for tools/precommit.mjs. Run: node --test tools/precommit.test.mjs |
| `tools/product-documents.test.mjs` | Tests for tools/product-documents.mjs (PRODUCT-ASSEMBLY-1 PA-10d, D-PA36). Run: node --test tools/product-documents.test.mjs |
| `tools/product-map-import.test.mjs` | Fixture test for tools/product-map-import.mjs. Run: node --test tools/product-map-import.test.mjs |
| `tools/regroup-modules.test.mjs` | Fixture-based tests for tools/regroup-modules.mjs: a tiny synthetic git repo in a temp dir, never the real tree. |
| `tools/release-notes.test.mjs` | Fixture test for tools/release-notes.mjs. Run: node --test tools/release-notes.test.mjs |
| `tools/render-api-routes.test.mjs` |  |
| `tools/render-feature-db.test.mjs` | Fixture test for tools/render-feature-db.mjs. Run: node --test tools/render-feature-db.test.mjs |
| `tools/render-offerings-matrix.test.mjs` | Fixture test for tools/render-offerings-matrix.mjs. Run: node --test tools/render-offerings-matrix.test.mjs |
| `tools/render-product-hosts.test.mjs` | Tests for tools/render-product-hosts.mjs - PRODUCT-ASSEMBLY-1 PA-4. Run: node --test tools/render-product-hosts.test.mjs |
| `tools/render-product-map.test.mjs` | Fixture test for tools/render-product-map.mjs. Run: node --test tools/render-product-map.test.mjs |
| `tools/render-space-links.test.mjs` | Tests for tools/render-space-links.mjs (LC-7): the census parse reads every link line and refuses one it cannot |
| `tools/render-tests-inventory.test.mjs` | Fixture test for tools/render-tests-inventory.mjs. Run: node --test tools/render-tests-inventory.test.mjs |
| `tools/sbom-modules.test.mjs` | Fixtures for tools/sbom-modules.mjs (P3f per-module SBOMs). Run: node --test tools/sbom-modules.test.mjs |
| `tools/seed-la-audit.test.mjs` | node --test tools/seed-la-audit.test.mjs  -  request building of the la-audit seed script against a MOCKED fetch (no server). |
| `tools/seed-la-benefits.test.mjs` | node --test tools/seed-la-benefits.test.mjs  -  request building of the benefits fraud seed script against a MOCKED fetch (no server). |
| `tools/seed-la-card.test.mjs` | node --test tools/seed-la-card.test.mjs  -  request building of the card fraud seed script against a MOCKED fetch (no server). |
| `tools/seed-la-claims.test.mjs` | node --test tools/seed-la-claims.test.mjs  -  request building of the claims seed script against a MOCKED fetch (no server). |
| `tools/seed-la-crypto.test.mjs` | node --test tools/seed-la-crypto.test.mjs  -  request building of the crypto laundering seed script against a MOCKED fetch (no server). |
| `tools/seed-la-health.test.mjs` | node --test tools/seed-la-health.test.mjs  -  request building of the provider billing fraud seed script against a MOCKED fetch (no server). |
| `tools/seed-la-insider.test.mjs` | node --test tools/seed-la-insider.test.mjs  -  request building of the insider-threat seed script against a MOCKED fetch (no server). |
| `tools/seed-la-logistics.test.mjs` | node --test tools/seed-la-logistics.test.mjs  -  request building of the cargo-theft seed script against a MOCKED fetch (no server). |
| `tools/seed-la-mfs.test.mjs` | node --test tools/seed-la-mfs.test.mjs  -  request building of the MFS fraud seed script against a MOCKED fetch (no server). |
| `tools/seed-la-mm.test.mjs` | node --test tools/seed-la-mm.test.mjs  -  request building of the mobile-money seed script against a MOCKED fetch (no server). |
| `tools/seed-la-ownership.test.mjs` | node --test tools/seed-la-ownership.test.mjs  -  request building of the ownership and sanctions seed script against a MOCKED fetch (no server). |
| `tools/seed-la-police.test.mjs` | node --test tools/seed-la-police.test.mjs  -  request building of the police seed script against a MOCKED fetch (no server). |
| `tools/seed-la-procurement.test.mjs` | node --test tools/seed-la-procurement.test.mjs  -  request building of the procurement fraud seed script against a MOCKED fetch (no server). |
| `tools/seed-la-showcase.test.mjs` | node --test tools/seed-la-showcase.test.mjs  -  request building of the seed script against a MOCKED fetch (no server). |
| `tools/seed-la-telco-ra.test.mjs` | node --test tools/seed-la-telco-ra.test.mjs  -  request building of the telecom revenue-assurance seed script against a MOCKED fetch (no server). |
| `tools/seed-la-trade.test.mjs` | node --test tools/seed-la-trade.test.mjs  -  request building of the trade-based money-laundering seed script against a MOCKED fetch (no server). |
| `tools/seed-la-utility.test.mjs` | node --test tools/seed-la-utility.test.mjs  -  request building of the utility seed script against a MOCKED fetch (no server). |
| `tools/seed-telco-fraud-demo.test.mjs` | node --test tools/seed-telco-fraud-demo.test.mjs |
| `tools/seed-telco.test.mjs` | node --test tools/seed-telco.test.mjs - the Inspecto Telco demo seed (PRODUCT-ASSEMBLY-1 PA-5d): the scaffold against the |
| `tools/ship.test.mjs` | Tests for tools/ship.mjs — PRODUCT-ASSEMBLY-1 PA-8. Run: node --test tools/ship.test.mjs |
| `tools/telecom-usage.test.mjs` | Tests for tools/telecom-usage.mjs - PRODUCT-ASSEMBLY-1 PA-5c (D-PA17). Run: node --test tools/telecom-usage.test.mjs |
| `tools/unreadable-dirs.test.mjs` | Fixture test: the two whole-tree walkers that a local, permission-locked folder used to crash |
| `tools/vuln-db-refresh.test.mjs` | Offline tests for tools/vuln-db-refresh.mjs. Run: node --test tools/vuln-db-refresh.test.mjs |
| `tools/vuln-scan.test.mjs` | Offline tests for tools/vuln-scan.mjs (ASSURE-OPERABILITY-1). Run: node --test tools/vuln-scan.test.mjs |
