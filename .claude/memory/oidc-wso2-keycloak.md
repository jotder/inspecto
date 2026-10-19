---
name: oidc-wso2-keycloak
description: "Local offline OIDC: WSO2 IS in Docker on :9443 (admin/admin) or Keycloak (dev-infra); auth.oidc.provider = generic|wso2|keycloak; provider gotchas and the bundle-runtime cacerts import"
metadata:
  type: reference
---

`auth.oidc.provider = generic|wso2|keycloak` plus `auth.oidc.discoveryUrl`; detail in
`docs/api/deployment/README.md`. Keycloak lives in `dev-infra/` (docker-compose + realm).

**WSO2 IS (Docker `wso2is`, https://localhost:9443, console `/console`, default superadmin admin/admin):**
- Discovery: `curl -sk https://localhost:9443/oauth2/token/.well-known/openid-configuration`.
- Client ID must match `[a-zA-Z0-9_]{15,30}` (`inspecto_spa_client`).
- App Access Token type must be **JWT** or every call 401s silently.
- `auth.oidc.audience` = the client ID; `auth.oidc.rolesClaim=groups`.
- Roles reach the ACCESS token only if the claim is in **accessTokenAttributes** — else authenticated with zero
  capabilities.
- `serve.bat` wires issuer/JWKS/audience/client id from env (`AUTH_OIDC_*`) but tokenEndpoint and rolesClaim go via
  `INSPECTO_JAVA_OPTS`.

**Keycloak:** needs the `basic` scope (else no `sub`) and an audience mapper; roles = realm + client roles union.

**TLS:** import the IdP's self-signed cert into the BUNDLE runtime, not the system JDK:
`runtime/bin/keytool -importcert -alias wso2is -file wso2is.crt -keystore runtime/lib/security/cacerts -storepass changeit -noprompt`
(`curl -k` working proves nothing). A fresh bundle runtime drops it — see [[preview-la-demo]].

Lesson from first real-IdP run: an authenticator SPI must be fail-CLOSED when registered-but-broken (a typo once
booted Standard wide open); only a real IdP exposed it — offline tests could not.
