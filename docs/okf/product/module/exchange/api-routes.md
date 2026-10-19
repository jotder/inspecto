---
type: Concept
title: HTTP routes — module.exchange
description: Every /api/v1 route the exchange module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.exchange`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

12 route(s) registered; 12 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/exchange/datasets/{datasetId}/{datasetId2}` | GET /exchange/datasets/{datasetId}/{datasetId2} (generated skeleton) | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/grants` | GET /exchange/grants (generated skeleton) | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/{action}` | POST /exchange/grants/{grantId}/{action} (generated skeleton) | exchange | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/expiry` | POST /exchange/grants/{grantId}/expiry (generated skeleton) | exchange | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/pin` | POST /exchange/grants/{grantId}/pin (generated skeleton) | exchange | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/offers` | GET /exchange/offers (generated skeleton) | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/offers` | POST /exchange/offers (generated skeleton) | exchange | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/refresh` | POST /exchange/refresh (generated skeleton) | exchange | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/requests` | POST /exchange/requests (generated skeleton) | exchange | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/signal-offers` | POST /exchange/signal-offers (generated skeleton) | exchange | `canOfferSignals` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/views/{viewId}/{viewId2}` | GET /exchange/views/{viewId}/{viewId2} (generated skeleton) | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/widgets/{widgetId}/{widgetId2}` | GET /exchange/widgets/{widgetId}/{widgetId2} (generated skeleton) | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |

## Drift

None — every route here is in the contract.
