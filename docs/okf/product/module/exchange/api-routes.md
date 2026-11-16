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
| GET | `/exchange/datasets/{datasetId}/{datasetId2}` | One offered Dataset's Exchange metadata | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/grants` | List Exchange grants | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/{action}` | Approve, deny or revoke an Exchange grant | exchange | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/expiry` | Set an Exchange grant's expiry | exchange | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/grants/{grantId}/pin` | Pin an Exchange grant to a snapshot version | exchange | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/offers` | List Exchange offers | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/offers` | Offer a Dataset, Widget or saved View on the Exchange | exchange | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/refresh` | Refresh an offered Dataset's snapshot | exchange | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/requests` | Request a share from another Space | exchange | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| POST | `/exchange/signal-offers` | Offer a Signal type on the Exchange | exchange | `canOfferSignals` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/views/{viewId}/{viewId2}` | Render-only view of a shared saved View | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |
| GET | `/exchange/widgets/{widgetId}/{widgetId2}` | Render-only view of a shared Widget | exchange | — | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java` |

## Drift

None — every route here is in the contract.
