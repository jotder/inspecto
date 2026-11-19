---
type: Reference
title: "Spaces — API"
description: "Control-plane routes for Spaces, with their gates."
tags: [config, spaces, api]
timestamp: 2026-10-19T00:00:00Z
item: module.config
audience: internal
status: current
verified: 2026-11-18 last-edit
code: platform/inspecto-config
---

# Spaces — API

Routes for Spaces. Design and internals: [Spaces architecture](architecture-config.md).

Server-global, un-prefixed (`SpaceRoutes`):

| Route | Purpose |
|---|---|
| `GET /spaces` | list Spaces; a closed Space only for a caller it admits, and then with `admission` and `createdBy`; `location {spaceFolder, dataFolder, referenced, holdsData}` for a `canAdminister` holder only |
| `GET /spaces/_meta` | `{multiSpace, locationsEnabled}` capability probe (`locationsEnabled`: `spaces.allowed.roots` is set) |
| `GET /spaces/templates` | template gallery (empty on a single-tenant server) |
| `POST /spaces` | create; optional `template`, `path` (a Space folder kept elsewhere) and `dataRoot` (a data folder). A signed-in caller is recorded (`created_by`) and the Space is created closed |
| `POST /spaces/import` | create a new Space from a zip; recorded and closed the same way, whatever the zip's `space.toon` says |
| `PUT /spaces/{id}` | rename / re-describe |
| `DELETE /spaces/{id}?purge=` | deregister, or also delete files |
| `POST /spaces/_locations/check` | `{path, kind: space\|data, id?}`: every folder check with a reason; writes nothing |
| `POST /spaces/attach` | `{path, id?}`: attach a Space folder kept outside the Spaces root (writes `<id>.ref`, starts it) |
| `POST /spaces/{id}/detach` | stop a Space started through its `<id>.ref` and delete the reference; folders stay |
| `PUT /spaces/{id}/data-root` | `{path\|null}`: point a Space that holds no data at a data folder (409 `SPACE_HOLDS_DATA` otherwise) |

The four folder routes and `POST /spaces` with `path`/`dataRoot` need `canAdminister` and the launch setting
`spaces.allowed.roots` (unset: 409 `SPACE_LOCATIONS_DISABLED`, before the body is read); gate order and checks:
[Space anatomy §2.2](architecture-space-anatomy.md) (`SPACE-LOCATIONS-1`).

Whole-Space zip (`inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java`): `GET /spaces/{id}/export`,
`POST /spaces/{id}/import/preview`, `POST /spaces/{id}/import`. Gates: `GET /modules` (`ModulesRoutes`),
`GET|PUT /settings/modules` (`ModuleSettingsRoutes`; `PUT` needs `canAdminister`).

**Who may use a Space (2026-11-15, `LA-FULL-CYCLE-TEST-1` finding 32).** Every route under `/spaces/{id}/` answers
404 `no such space '{id}'` to a signed-in caller the Space does not admit: a closed Space (`space.toon`
`admission: closed`) admits its creator, a holder of `canAdminister` and a holder of a role its own `roles.toon`
defines. A Space with no `admission` key is open to every signed-in caller, and with no sign-in nothing is checked.
The full statement, how to admit others and the limits:
[Auth & Security](../access/admin-guide-auth-security.md).
