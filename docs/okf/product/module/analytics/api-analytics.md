---
type: Reference
title: "Analytics kernel — API"
description: "Control-plane routes for Analytics kernel, with their gates."
tags: [analytics, analytics, api]
timestamp: 2026-10-19T00:00:00Z
---

# Analytics kernel — API

Routes for Analytics kernel. Design and internals: [Analytics kernel architecture](architecture-overview.md).

No HTTP routes. The programmatic surface for Jobs and packs is the Platform Service `analytics`:
`requires: [analytics]`, then `ctx.services().get(AnalyticsAccess.class)`. `AnalyticsAccess` lives in
the engine (`com.gamma.query`) and only delegates here ([platform-services](../../layer/platform/architecture-platform-services.md)).
