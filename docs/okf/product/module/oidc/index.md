---
type: ProductItem
title: OIDC authenticator and token relay
item: module.oidc
description: Documentation hub for module.oidc — every document about it, by slot.
tags: [product-item, module]
---

# OIDC authenticator and token relay (`module.oidc`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.oidc`](../../../../../product-map/module/oidc.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [OIDC authenticator and token relay — plan of record](plan-oidc.md) |
| spec | current | [OIDC authenticator and token relay — implementation spec](spec-oidc.md) |
| architecture | current | [Security module (inspecto-oidc, with inspecto-secrets and inspecto-geo-country split out by D-MR6)](architecture-oidc.md) |
| features | current | [OIDC authenticator and token relay — features](features-oidc.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | n/a | end users only see the sign-in button, documented with the other access screens in module.access user-guide-access.md |
| admin-guide | current | [OIDC authenticator and token relay — admin guide](admin-guide-oidc.md) |
| api | n/a | implements the Authenticator and TokenRelay contracts owned by module.auth-spi; the /auth/* routes live in the core and are documented in module.access api-access.md |
| tests | current | [OIDC authenticator and token relay — tests inventory](tests-inventory.md) · [Security review: inbound OIDC client-credentials tokens for Link Analysis](tests-security-review-oidc-client-credentials.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.oidc` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `AGENT-KIT-1` | P2 | feature | The Agent Kit: AI agents build, ship and manage Apps on a running Inspecto safely, through an optional MCP module. | — |
