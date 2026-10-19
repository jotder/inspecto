---
type: Reference
title: "Events and event log — API"
description: "Control-plane routes for Events and event log, with their gates."
tags: [event, events, api]
timestamp: 2026-10-19T00:00:00Z
---

# Events and event log — API

Routes for Events and event log. Design and internals: [Events and event log architecture](architecture-overview.md).

| Route | Gate |
|---|---|
| `GET /events` (on `/api/v1`: cursor-paginated, `limit` 1..500) | none beyond AuthN |
| `GET /events/search`, `GET /events/export`, `GET /events/{id}` | none beyond AuthN |
| `GET /events/views` | none beyond AuthN |
| `POST /events/views`, `POST /events/views/{id}/delete` | `canAuthorWorkbench` |
| `GET /audit/search`, `GET /audit/export` (core; `type` must be `AUDIT` or `ACCESS_DENIED`, anything else is 400), `GET /audit/route-inventory` | none beyond AuthN |
| `GET /audit/verify`, `GET /audit/anchors`, `POST /audit/anchors`, `POST /audit/anchors/rebaseline` | `canAdminister` |

Filter parameters on search: `level`, `type`, `pipeline`, `correlationId`, `q`, `from`, `to`, `limit`,
`offset`. Contract: `docs/api/openapi-v1.json`.
