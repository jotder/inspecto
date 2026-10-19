---
type: Feature
title: "Orders starter — admin guide"
description: "Install and verify the Orders starter Space."
tags: [orders-starter, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Orders starter — admin guide

## Prerequisites

- Any Edition, Basic and up (`offerings/basic.toon`; the template sets no `minEdition`).
- Order code `INS-SP-ORDERS-STARTER` (`product-map/solution-pack/orders-starter.toon`).

## Install

1. Confirm the Space Template is offered: `GET /api/v1/spaces/templates` lists `orders-starter`.
2. Create a Space from it (needs `canAdminister`):

   ```
   POST /api/v1/spaces
   {"id": "<space-id>", "template": "orders-starter"}
   ```

   A refusal creates no Space (`docs/api/openapi-v1.json`, `postSpaces`).
3. Copy the three files of `data/samples/orders` into `data/inbox/orders`.

## Verify

- The files leave `data/inbox/orders` and Parquet appears under `data/orders/database`.
- A file that fails goes to `data/orders/errors` or `data/orders/quarantine`; logs are in `data/orders/logs`.
- `orders_overview` shows a non-zero Total orders tile and one bar per region.
