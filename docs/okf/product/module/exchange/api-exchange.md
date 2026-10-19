---
type: Reference
title: "Exchange (multi-entity sharing) — API"
description: "Control-plane routes for Exchange (multi-entity sharing), with their gates."
tags: [exchange, exchange, api]
timestamp: 2026-10-19T00:00:00Z
---

# Exchange (multi-entity sharing) — API

Routes for Exchange (multi-entity sharing). Design and internals: [Exchange (multi-entity sharing) architecture](architecture-overview.md).

| Route | Capability (owning Space's role table) |
|---|---|
| `GET /exchange/offers[?owner=]` | read |
| `POST /exchange/offers` | `canOfferDatasets` |
| `POST /exchange/signal-offers` | `canOfferSignals` |
| `POST /exchange/refresh` | `canOfferDatasets` |
| `POST /exchange/requests` | `canRequestShares` |
| `POST /exchange/grants/{id}/approve`, `deny`, `revoke` | `canApproveShares` |
| `POST /exchange/grants/{id}/pin` / `/expiry` | `canRequestShares` / `canApproveShares` |
| `GET /exchange/grants[?space=]` | read |
| `GET /exchange/datasets/{owner}/{item}`, `/widgets/...`, `/views/...` | read; Widgets and views render-only |

Routes are un-prefixed (not under `/spaces/{id}`). Transitions are audited and signalled at the HTTP edge.
