---
type: Seam
title: Onboarding authoring seams (draft lifecycle, previews, register pair)
description: The control-plane routes the guided Stream/Reference onboarding authors against — a draft is an inactive pipeline; enrichments need explicit hot-registration.
resource: inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java
tags: [control-plane, onboarding, config, enrichment, pipeline, reference]
timestamp: 2026-07-16T00:00:00Z
item: module.processor
audience: internal
status: current
verified: 2026-11-20 last-edit
code: inspecto
---

# Onboarding authoring seams

The guided onboarding (frontend: [onboarding](../../layer/product/user-guide-onboarding.md)) authors real
Stage-1 configs through these seams — **a draft is just a pipeline with `active: false`** (parsed,
indexed, catalog-visible, never executed; D3 of the design). Shipped P0–P3, 2026-07-16.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Drafts, previews, the register pair and reference production](api-onboarding-authoring-drafts-previews.md)** — Draft lifecycle (`ConfigWriteRoutes` write/patch, `ConfigReadRoutes` read/delete, `ConfigPreviewRoutes` previews) · Stateless sample previews · The register pair · Reference production (`produces: reference`, P0) · Engine fixes the live walks surfaced (apply beyond onboarding) · An event-triggered enrichment is never silently widened to a full recompute (2026-09-15)
2. **[Add data — /onboard/files](api-onboarding-authoring-add-data.md)** — Add data: `/onboard/files` (file in → Dataset out; `LA-ONBOARD-1` phase 2, 2026-10-22)
3. **[Add data — the commit onward](api-onboarding-authoring-commit.md)**
