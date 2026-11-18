---
type: Feature
title: "Link Analysis showcase (feature off) — admin guide"
description: "Create and verify the feature-off companion Space."
tags: [la-showcase-off, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis showcase (feature off) — admin guide

Same server as [la-showcase](../la-showcase/admin-guide-variants-runbook.md). No seed script applies; the template carries no runtime state.

1. Create the Space: `POST /spaces` with body `{"id":"la-showcase-off","template":"la-showcase-off"}` (needs `canAdminister`; `docs/api/openapi-v1.json`).
2. Verify: in this Space an `/inv/*` route answers 404 `MODULE_DISABLED` while `GET /pipelines` answers normally.
