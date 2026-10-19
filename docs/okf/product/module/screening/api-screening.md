---
type: Reference
title: "Screening — API"
description: "Control-plane routes for Screening, with their gates."
tags: [screening, screening, api]
timestamp: 2026-10-19T00:00:00Z
---

# Screening — API

Routes for Screening. Design and internals: [Screening architecture](architecture-overview.md).

| Route | Gate | Notes |
|---|---|---|
| `POST /screening/check` | read-shaped | `{subjects[], lists[], threshold?, maxMatches?}`; a POST so names never ride in a URL |
| `GET /screening/hits?state=` | read | newest first |
| `GET /screening/hits/{id}` | read | one hit |
| `GET /screening/hits/{id}/reveal` | reveal | unmasked value for a permitted caller |
| `POST /screening/hits/{id}/decide` | `canWorkIncidents` | `{decision, reason, version}` |

Decide gate order: capability -> no write root 503 -> body 422 -> unknown hit 404 -> forged record 409 -> stale
`version` 409 -> transition not allowed 409 -> save under the store lock. Ids must match `sh-<14 digits>-<6 hex>`.
