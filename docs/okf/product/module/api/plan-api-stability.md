---
type: Reference
title: API stability policy
description: What the `@PublicApi` marker means and does not mean, the release baseline (nothing after 3.x has shipped), and the running draft of release notes for the pending MAJOR.
resource: platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java
tags: [api, stability, publicapi, semver, release]
timestamp: 2026-07-16T00:00:00Z
---

# API Stability Policy
> **Deep reference — the detail tier.** Start at [Control-plane section index](../../index.md) for the summary; this page is the long form it points to. *(Moved from the retired root-level `plan-api-stability.md` (docs consolidation, 2026-07-16).)*

> Part of the [Inspecto](../../../../../inspecto/README.md) documentation.

The framework distinguishes its **stable public API** from internal implementation.
Types, methods, and constructors marked [`@com.gamma.api.PublicApi`](../../../../../platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java)
are the surface external code may depend on; everything else is internal and may
change in any release.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[The marker, the release baseline and the pending release notes](plan-api-stability-marker-baseline.md)** — What the marker means · Release baseline — nothing after 3.x has shipped · The baseline guard (`PUBLIC-API-SURFACE-1`, 2026-11-12) · Release notes — the pending MAJOR (draft, kept current until the tag is cut)
2. **[Release notes — breaking configuration and CLI changes onward](plan-api-stability-breaking-config.md)**
3. **[The public surface and what is internal](plan-api-stability-public-surface.md)** — The public surface (as it stands for the pending 4.0.0; unchanged since 2.0.0 except where noted) · Explicitly internal (do not depend on) · What is *not* covered by code-level stability — but is still stable · Why no `module-info.java`
