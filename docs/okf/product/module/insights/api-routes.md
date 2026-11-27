---
type: Concept
title: HTTP routes — module.insights
description: Every /api/v1 route the insights module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.insights`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

4 route(s) registered; 4 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/insights` | List an Insight Profile's Insights | insights | — | `features/inspecto-insights/src/main/java/com/gamma/insights/InsightsRoutes.java` |
| POST | `/insights/{profile}/{insightId}/feedback` | Give feedback on an Insight | insights | — | `features/inspecto-insights/src/main/java/com/gamma/insights/InsightsRoutes.java` |
| POST | `/insights/explain` | Explain the change of a Measure between one time bucket and the bucket before it | insights | — | `features/inspecto-insights/src/main/java/com/gamma/insights/ExplainRoutes.java` |
| POST | `/insights/preview` | Replay an unsaved Insight Profile over history | insights | `canAuthorWorkbench` | `features/inspecto-insights/src/main/java/com/gamma/insights/InsightsRoutes.java` |

## Drift

None — every route here is in the contract.
