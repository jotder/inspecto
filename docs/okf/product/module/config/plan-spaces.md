---
type: Capability
title: Spaces & tenancy (SPC)
description: One server, many isolated Spaces — the on-disk layout and the one-time migrator, runtime CRUD and the /spaces seam, MDC-routed singleton isolation, whole-Space zip export/import, the shipped template catalog, the Metadata Bundle v2 for cross-instance promotion, the Exchange boundary, and the seeded per-tenant isolation policies. The requirement of record for the SPC area, its specification, its decisions, and what was refused.
resource: inspecto/src/main/java/com/gamma/service/SpaceManager.java, inspecto/src/main/java/com/gamma/control/BundleRoutes.java
tags: [spc, capability, spaces, tenancy, multi-space, space-root, migrator, templates, metadata-bundle, exchange, isolation]
timestamp: 2026-09-08T00:00:00Z
item: module.config
audience: internal
status: plan
verified: 2026-11-20 code-reviewed
code: platform/inspecto-config
---

# Spaces & tenancy — capability spec (`SPC`)

> **What this page is.** The single entry point for the SPC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Fifth of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../../archived-documents/plans-archive/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §1, §14 — binding). A **Space** is a fully isolated project
> environment in one installation; activity in one Space is invisible to another. A **Space Template** is a
> reusable blueprint that instantiates a new Space — Type → Instance. A **Metadata Bundle** is a selective,
> **configuration-only** export for moving definitions between *instances*; **Bundle v2** carries its own
> refs, provenance and `requires`. Both are distinct from the **whole-Space zip** (a clone of one Space's
> config tree). The **Exchange** family shares an item *across* Spaces by grant; a **Share** is intra-Space
> (`SEC`). An **Edition** is a build flavour.

## Parts

This page is split by topic into 4 parts; each part lists the sections it holds.

1. **[Purpose, requirements and the Space model](plan-spaces-scope-model.md)** — 1. Purpose & scope · 2. Requirements of record · 3. Specification
2. **[Specification — Space Templates](plan-spaces-templates.md)** — board rows: `ASSURE-PACK-TELCO-FRAUD-RESIDUALS-1`
3. **[Specification — Space Templates (continued)](plan-spaces-templates-continued.md)**
4. **[Anomaly Model examples, bundles, decisions and verification](plan-spaces-bundles-decisions.md)** — 4. Decisions · 5. Not built · 6. Refused & superseded · 7. As-built mechanism (pointers only) · 8. Verification
