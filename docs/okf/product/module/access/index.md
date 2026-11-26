---
type: ProductItem
title: Access policy (roles, grants, row scope, audit trail)
item: module.access
description: Documentation hub for module.access — every document about it, by slot.
tags: [product-item, module]
---

# Access policy (roles, grants, row scope, audit trail) (`module.access`)

> **Documentation hub** (`PRODUCT-DOCS-HUB-1`) for Product Map Item
> [`module.access`](../../../../../product-map/module/access.toon). Editions, add-ons, order codes and dependencies are read
> from the Product Map — never restated here. **Generated** by `tools/render-product-docs.mjs` from the files in
> this folder — add or rename a file (`<slot>-<hub>.md` / `<slot>-<topic>.md`), never edit this table.

| Slot | Status | Document |
|---|---|---|
| plan | current | [module.access - board row detail](plan-board-detail.md) · [Security (SEC) — Decisions, not built, refused and as-built pointers](plan-security-decisions.md) · [Security (SEC) — Purpose, scope and requirements of record](plan-security-scope.md) · [Security (SEC) — Specification](plan-security-specification.md) · [Security (SEC) — Verification](plan-security-verification.md) · [Security (SEC)](plan-security.md) |
| spec | current | [Access policy — spec](spec-access.md) |
| architecture | current | [Identity and Access](architecture-access.md) |
| features | current | [Identity and Access — features](features-access.md) |
| feature-db | n/a | no feature rows derive to this item (`tools/render-feature-db.mjs`) |
| user-guide | current | [Identity and Access — screens](user-guide-access.md) · [Roles and Access Policies](user-guide-concept-roles-access-policies.md) |
| admin-guide | current | [Auth & Security — Advanced ABAC and the 2026-07-25 decisions](admin-guide-auth-security-abac-decisions.md) · [Auth & Security — Fail-closed boot, Space and config guards](admin-guide-auth-security-boot-guards.md) · [Auth & Security — Imports, identity files and maker-checker](admin-guide-auth-security-imports-maker-checker.md) · [Auth & Security](admin-guide-auth-security.md) · [Define Roles and Access Policies](admin-guide-howto-define-roles-and-access-policies.md) |
| api | current | [Identity and Access — API](api-access.md) |
| tests | current | [Access policy (roles, grants, row scope, audit trail) — tests inventory](tests-inventory.md) |
| brochure | n/a | per edition / solution pack |

Status: `current` (in this folder) · `missing` (a real gap) · `n/a` (decided: the reason says why; per-hub decisions in `slots-na.toon`).

## Open work

Generated from the rows of [`docs/BACKLOG.md`](../../../../BACKLOG.md) tagged `module.access` — the board is the only list; change the row there.

| Row | Priority | Kind | Title | Hold |
|---|---|---|---|---|
| `IMPORT-WHOLE-SPACE-SPLIT-1` | P3 | feature | split a whole-Space export into its kinds' own validated routes on import, if ever asked. | demand |
| `SEED-ADMIN-ALERT-RULE-1` | P3 | defect | the seed admin Role cannot create a Space from a template that carries an Alert Rule | — |
| `APPROVAL-POLICY-PRESET-1` | P3 | feature | a Standard install is not gated by default: | — |
| `OIDC-SUPER-ROLE-TEST-RED-1` | P3 | defect | OidcAuthenticatorTest.superRoleGrantsAllCapabilities fails on master: the super role now also grants canDraftInvestigations, which the te… | — |
| `RULE-TEMPLATE-SIMULATE-SHARING-1` | P3 | defect | Rule Template simulate skips ComponentAccess.canView on its source Dataset | — |
| `DB-QUERY-STORE-SHARING-1` | P3 | chore | verify: may a /db/query store read reach a shared-away Dataset? | — |
| `RECON-STATE-READS-UNMASKED-1` | P3 | defect | The recon state reads return recorded Break keys raw, and two skip the Reconciliation's sharing (filed 2026-11-26, RECON-READ-UNGATED-1 f… | — |
| `AGENT-KIT-1` | P2 | feature | The Agent Kit: AI agents build, ship and manage Apps on a running Inspecto safely, through an optional MCP module. | — |
| `AGENT-STORED-RECORD-READS-1` | P2 | defect | 43 GET routes answer stored records that can echo entity keys or row values other features wrote into them, and sit outside the agent dat… | operator |
| `VIEW-DATA-UNMASKED-1` | P2 | defect | GET /views/{id}/data answers a view's rows with no masking and no capability check, to people as well as agents | — |
| `MACHINE-CLIENT-CONSOLE-1` | P3 | feature | a Space's admitted machine clients (identity.machineClients) and attribute claims are set only through PUT /access/roles; the Roles scree… | — |
| `AUDIT-ACTION-VERBS-1` | P3 | defect | A run's quarantine release, drain, retry-now and retry cancel, and a Job Run replay, are audited as pipeline.created / job.created (categ… | — |
