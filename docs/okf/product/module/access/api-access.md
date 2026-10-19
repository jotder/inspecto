---
type: Reference
title: "Identity and Access — API"
description: "Control-plane routes for Identity and Access, with their gates."
tags: [access, access, api]
timestamp: 2026-10-19T00:00:00Z
---

# Identity and Access — API

Routes for Identity and Access. Design and internals: [Identity and Access architecture](architecture-overview.md).

| Route | Gate | Purpose |
|---|---|---|
| `POST /auth/exchange`, `POST /auth/refresh`, `POST /auth/logout` | public; `503` without a `TokenRelay` | BFF session (`AuthRoutes`) |
| `GET` / `PUT /access/roles` | PUT: `canConfigureAccess` | Effective role table / author it |
| `GET` / `PUT /access/policies` | PUT: `canConfigureAccess` | Access Policies |
| `POST /access/policies/preview` | `canConfigureAccess` | Impact matrix for an unsaved draft |
| `GET /access/explain` | ungated | "Why denied?" for the caller; `{enabled:false}` without the engine |
| `GET` / `PUT /access/catalog` | PUT: `canConfigureAccess` | Access Catalog |
| `GET /access/profiles`, `PUT` / `DELETE /access/profiles/{id}` | writes: `canConfigureAccess` | Access Profiles (`role-…` / `lens-…`) |

All in `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` and `AuthRoutes.java`.
