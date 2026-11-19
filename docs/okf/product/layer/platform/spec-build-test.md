---
type: Concept
title: Build & Test
description: The offline Maven verify loop, the mandatory DuckDB native-access JVM flag, and package.ps1 edition bundles.
resource: pom.xml
tags: [build, test, maven, duckdb, packaging]
timestamp: 2026-06-28T00:00:00Z
---

# Build & Test

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[The verify loop, affected modules and CI](spec-build-test-verify-loop.md)** — Verify loop (offline, authoritative) · Affected-module protocol — build/test only what a change reaches (`AFFECTED-BUILD-1`, 2026-10-19) · When GitHub CI runs (operator, 2026-10-09) · Nightly Postgres-gated tests (`nightly-postgres.yml`, ASSURE-OPERABILITY-1 D2, 2026-10-20) · Mandatory DuckDB native-access flag · Seeding a fresh checkout — `tools/seed-samples.mjs`
2. **[Packaging — package.ps1](spec-build-test-packaging.md)** — Packaging — `package.ps1`
3. **[Packaging — bundle contents; open rows](spec-build-test-bundle-contents.md)** — Open rows this concept owns
