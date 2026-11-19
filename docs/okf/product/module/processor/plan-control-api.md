---
type: Capability
title: Control API (API)
description: The versioned HTTP control plane — the /api/v1 envelope and error catalog, correlation, gzip, ETag concurrency and idempotency, cursor pagination, /bootstrap, the OpenAPI contract and its test, route registration and the RouteModule SPI, static SPA serving, the gateway blueprints, and the Java embedding API stability policy. The requirement of record for the API area, its specification, its decisions, and what was refused.
resource: inspecto/src/main/java/com/gamma/control, platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java
tags: [api, capability, control-api, v1, envelope, error-codes, etag, idempotency, cursor, openapi, route-module, public-api, semver]
timestamp: 2026-09-08T00:00:00Z
item: module.processor
audience: internal
status: plan
verified: 2026-11-20 last-edit
code: inspecto
---

# Control API — capability spec (`API`)

> **What this page is.** The single entry point for the API capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Fourth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
> **This spec is the design of record for the `/api/v1` contract.** The 2026-07-06 design document
> (`api-contract-design.md`) is archived and is *provenance*: its 33 guidelines are summarised in §3.1 and its
> decisions carried into §4 so nothing current has to read the archive.
>
> **Canonical vocabulary** (`GLOSSARY.md` §14 — binding). The **Control API** is the versioned HTTP control
> plane (`ControlApi`, JDK `HttpServer`) — it moves configuration and commands, never rows; the data plane
> is `DAT`. A **Capability** is a named authorization question a route gates on (`SEC`). A **Platform
> Service** is an engine facility a Job is *granted* — ⛔ never "capability" in that sense, and never
> "Controller Service". An **Edition** is a build flavour; a route family an edition lacks answers `503`,
> never `404`. The area was renamed from "API & integration": *integration* is undefined and collided with
> the warehouse *integrations* page.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Purpose, scope and requirements of record](plan-control-api-scope.md)** — 1. Purpose & scope · 2. Requirements of record
2. **[Specification](plan-control-api-specification.md)** — 3. Specification
3. **[Decisions, not built, refused and verification](plan-control-api-decisions-verification.md)** — 4. Decisions · 5. Not built · 6. Refused & superseded · 7. As-built mechanism (pointers only) · 8. Verification
