---
type: Reference
title: "Analytics kernel — API"
description: "Control-plane routes for Analytics kernel, with their gates."
tags: [analytics, analytics, api]
timestamp: 2026-10-19T00:00:00Z
item: module.analytics
audience: internal
status: current
verified: 2026-11-18 last-edit
code: platform/inspecto-analytics
---

# Analytics kernel — API

Routes for Analytics kernel. Design and internals: [Analytics kernel architecture](architecture-analytics.md).

No HTTP routes. The programmatic surface for Jobs and packs is the Platform Service `analytics`:
`requires: [analytics]`, then `ctx.services().get(AnalyticsAccess.class)`. `AnalyticsAccess` lives in
the engine (`com.gamma.query`) and only delegates here ([platform-services](../../layer/platform/architecture-platform-services.md)).
