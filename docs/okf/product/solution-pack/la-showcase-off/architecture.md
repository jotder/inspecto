---
type: Feature
title: "Link Analysis showcase (feature off) — architecture"
description: "Differences from la-showcase: an empty Space with geoLink disabled."
tags: [la-showcase-off, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Link Analysis showcase (feature off) — architecture

Companion of [la-showcase](../la-showcase/architecture.md); only the differences are listed (`spaces/_templates/la-showcase-off/template.toon`).

- `config/modules.toon` disables the `geoLink` feature, so every `/inv/*` and `/geo/*` route in this Space answers 404 `MODULE_DISABLED`; other Space routes (for example `GET /pipelines`) keep working.
- No feeds and no data: none of la-showcase's `data/`, `config/jobs/`, `config/registry/`, `config/mule/`, `config/telecom/` or `link-analysis.toon`.
- Same Demo Users and role table (`demo-users.toon`, `roles.toon`) as la-showcase.
