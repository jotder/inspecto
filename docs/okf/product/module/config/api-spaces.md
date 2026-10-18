---
type: Reference
title: "Spaces — API"
description: "Control-plane routes for Spaces, with their gates."
tags: [config, spaces, api]
timestamp: 2026-10-19T00:00:00Z
---

# Spaces — API

Routes for Spaces. Design and internals: [Spaces architecture](architecture-spaces.md).

Server-global, un-prefixed (`SpaceRoutes`):

| Route | Purpose |
|---|---|
| `GET /spaces` | list Spaces |
| `GET /spaces/_meta` | `{multiSpace}` capability probe |
| `GET /spaces/templates` | template gallery (empty on a single-tenant server) |
| `POST /spaces` | create; optional `template` |
| `POST /spaces/import` | create a new Space from a zip |
| `PUT /spaces/{id}` | rename / re-describe |
| `DELETE /spaces/{id}?purge=` | deregister, or also delete files |

Whole-Space zip (`inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java`): `GET /spaces/{id}/export`,
`POST /spaces/{id}/import/preview`, `POST /spaces/{id}/import`. Gates: `GET /modules` (`ModulesRoutes`),
`GET|PUT /settings/modules` (`ModuleSettingsRoutes`; `PUT` needs `canAdminister`).
