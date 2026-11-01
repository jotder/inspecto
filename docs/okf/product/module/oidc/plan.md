---
type: Plan
title: OIDC authenticator and token relay — plan of record
description: What module.oidc is required to do, what is built, what is left and what was refused.
resource: providers/inspecto-oidc/
tags: [module, oidc, plan, security]
timestamp: 2026-10-19T00:00:00Z
---

# OIDC authenticator and token relay — plan of record

Requirement: Standard and Advanced bundles sign users in through the customer's own OIDC identity
provider, with the core staying auth-free behind the `Authenticator` / `TokenRelay` contracts
(see [auth-spi](../auth-spi/index.md)). The full security plan is [plan-security](../access/plan-security.md);
the design history is `docs/archived-documents/plans-archive/rbac-abac-plan.md`.

## Built

| Item | Where |
|---|---|
| JWKS-validated resource server (RS256, issuer, optional audience, `exp`, `typ` JWT or `at+jwt`) | `providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcAuthenticator.java` |
| Gateway trust mode (RBAC R0): a second, gateway-signed JWT in a header | same file |
| Per-Space role table, Access Profile denials, held roles, ABAC attribute allowlist (RBAC R1-R3, ABAC A1) | `OidcAuthenticator.verify`, `providers/inspecto-oidc/src/main/java/com/gamma/oidc/RoleMapper.java` |
| Code + PKCE exchange, refresh, nonce check, RP-initiated logout, public bootstrap config | `providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcTokenRelay.java` |
| Provider preset `generic` / `wso2` / `keycloak` and discovery document (2026-10-13) | `providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcConfig.java` |
| Shaded sidecar jar so Nimbus ships (SEC-SIDECAR-BOOT-1) | [architecture](architecture.md) |

Proven live against WSO2 Identity Server 7.3.0 and Keycloak
(`providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcAgainstRealProviderTest.java`,
`providers/inspecto-oidc/src/test/java/com/gamma/oidc/OidcKeycloakAgainstRealProviderTest.java`; runbook
[admin-guide-real-signin](../la-core/admin-guide-real-signin.md)).

## Decided

* **No IdP of record** (`access/admin-guide-auth-security.md`; not BACKLOG D15, which is the scale-out ZK call): the module stays vendor-neutral; the provider is configuration.
* **Audience is warn-only**, not mandatory, so existing deployments keep booting (`warnIfNoAudience`).
* **Fail closed at boot** when issuer or JWKS URI is missing, the provider value is unknown, or discovery fails.

## Left

* Inbound OIDC client-credentials trust for Link Analysis integration: reviewed 2026-11-01, verdict
  **enable with conditions** ([security review](tests-security-review-oidc-client-credentials.md)). A machine token is
  already accepted like a user's, so the conditions are deployment rules until the filed rows (in Open work) put them in code.
* A live check of the SPA's sign-in return path under a real token lapse needs a running provider
  (`ANOMALY-UI-FINDINGS-1`).
