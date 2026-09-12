# `docs/api/deployment/` — WSO2 gateway + Keycloak realm blueprints (W6)

> Companion to the [Control API capability spec](../../okf/capabilities/control-api/control-api.md) §3.12 (the design of record; the 2026-07-06 design is archived provenance at [`api-contract-design.md`](../../archived-documents/plans-archive/api-contract-design.md) §8)
> (security architecture) and [`../../EDITIONS.md`](../../EDITIONS.md) (Standard edition assembly).
> These are **illustrative starting points**, not a tested live deployment — this sandbox has no
> running WSO2/Keycloak instance to verify against. Adapt hostnames, realm/API names, and throttling
> tiers to the actual deployment before use.

## What lives here

- **[`wso2-api-definition.yaml`](wso2-api-definition.yaml)** — a WSO2 API Manager `apictl`-importable
  API project descriptor for the `/api/v1` surface. It wraps
  [`../openapi-v1.json`](../openapi-v1.json) — the gateway is **transport only** (§8): routing,
  throttling tiers, CORS, and OAuth2 scope enforcement at most. It never re-implements validation,
  business rules, or persistence (those stay backend-only, per the design's "guideline 26/27: gateway
  transport-only" adoption).
- **[`keycloak-realm-blueprint.json`](keycloak-realm-blueprint.json)** — a partial Keycloak realm
  export: the `inspecto-spa` public client (Authorization Code + PKCE, no client secret — a browser
  SPA cannot keep one), a `roles` protocol mapper so an access token's role grants land in the JWT
  claim `inspecto-security`'s `RoleMapper` reads, and realm roles matching the taxonomy in
  [`../../archived-documents/plans-archive/rbac-groundwork.md`](../../archived-documents/plans-archive/rbac-groundwork.md) §3.

## How the pieces fit (§8 recap)

```
Browser ── HTTPS/HTTP2 ──> WSO2 API Gateway ── HTTPS/HTTP1.1 ──> Inspecto (OIDC resource server)
                 │                                    │
                 └── OIDC Auth Code + PKCE ──> Keycloak (users, roles, LDAP/AD federation)
```

1. **Keycloak** authenticates the user via Auth Code + PKCE. The SPA hands the resulting one-time
   `code` to the backend's `POST /auth/exchange` (**backend-mediated session**, W6d): the backend
   redeems it server-to-server and keeps the **refresh token in an httpOnly + SameSite=Strict
   cookie** the page's JavaScript can never read — only short-lived access tokens reach the browser
   (`POST /auth/refresh` mints new ones from the cookie). The access token's `roles` claim (or
   Keycloak's default `realm_access.roles` nesting — `RoleMapper` reads either) carries the
   subject's realm roles.
2. **WSO2** fronts the backend, terminates client TLS, enforces OAuth2 (token introspection or JWT
   validation at the edge — a fast pre-check), rate-limits, and forwards the bearer token upstream
   unchanged.
3. **Inspecto** (`inspecto-security`'s `OidcAuthenticator`) validates the same JWT again — signature
   against Keycloak's JWKS, issuer, audience, expiry — "defense in depth, never trust the gateway
   blindly" — then maps claims → Roles → Capabilities (`RoleMapper`) and attaches a `Subject` the
   control plane's `requireCapability` gates and the v1 envelope's `permissions[]` read from.

## Configuring the backend to match

The blueprint's realm/client names map to the `-Dauth.oidc.*` flags `OidcAuthenticator` reads
(`docs/EDITIONS.md`, [`ControlApi`](../../../inspecto/src/main/java/com/gamma/control/ControlApi.java)):

| Flag | Value for this blueprint |
|---|---|
| `-Dauth.oidc.issuer` | `https://<keycloak-host>/realms/inspecto` |
| `-Dauth.oidc.jwksUri` | `https://<keycloak-host>/realms/inspecto/protocol/openid-connect/certs` |
| `-Dauth.oidc.audience` | `inspecto-api` |
| `-Dauth.oidc.rolesClaim` | `roles` (default — falls back to `realm_access.roles` automatically) |
| `-Dauth.oidc.tokenEndpoint` | **REQUIRED** (BACKLOG D15) — the provider's `token_endpoint`, copied from its `/.well-known/openid-configuration`. No longer derived from the issuer: no vendor path layout is assumed, so a missing value fails fast at startup rather than guessing a Keycloak shape |
| `-Dauth.oidc.clientId` | `inspecto-spa` (default) |
| `-Dauth.oidc.clientSecret` | *optional* (public PKCE client needs none). Pass a **`SecretResolver` reference** — `${ENV:NAME}` / `${SYS:prop}` — never the raw value |

### 🔴 Proven against a real provider 2026-09-13 — four corrections to the table above

The blueprint is Keycloak-shaped and was never run against an IdP. Standing up **WSO2 Identity Server
7.3.0** (`docker run -d --name wso2is -p 9443:9443 -p 9763:9763 wso2/wso2is`) and authenticating a real
token through `OidcAuthenticator` contradicted four of its assumptions. Pinned by
`OidcAgainstRealProviderTest`, which SKIPS without a configured provider — so read its skip count, not its
pass count.

1. ⛔ **A provider's default access token may not be a JWT at all.** WSO2 issues an **opaque** token
   until the application is switched to `ext_token_type: JWT`; `OidcAuthenticator` is a Nimbus RS256 JWT
   validator, so every request 401s until then, with nothing in the log saying why.
2. 🔴 **The code rejected every RFC 9068 access token, and this was a real defect — now fixed.** RFC 9068
   mandates `typ: at+jwt` on an OAuth 2.0 JWT access token; Nimbus's default type verifier allows only
   `JWT` or an absent typ, so validation failed with *"JOSE header typ (type) at+jwt not allowed"*.
   ⚠ **Not a WSO2 quirk** — recent Keycloak stamps the same type, so the blueprint's own vendor was
   affected. The offline suite could not have caught it: it mints headers with no `typ` at all.
3. ⚠ **`aud` is the CLIENT ID, not a separate API audience.** The table's `inspecto-api` is a Keycloak
   convention. Set `-Dauth.oidc.audience` to what the provider actually emits — read it out of a token,
   do not assume it.
4. ⚠ **`-Dauth.oidc.clientId=inspecto-spa` is not issuable on WSO2**, whose client ids must match
   `[a-zA-Z0-9_]{15,30}` — too short, and hyphens are refused. The default is a default, not a contract.

⚠ **Roles are the remaining gap, and it is a real one.** A WSO2 client-credentials token carries **no
roles claim at all** — neither `roles` nor Keycloak's `realm_access.roles`. `RoleMapper` grants nothing,
so the caller authenticates with **zero capabilities**: fail-closed and correct, but *authenticated is not
authorized*. Issuing roles needs a user-bearing grant and an IdP-side claim mapping, which this pass did
not configure.

🔴 **TLS: there is no skip switch, by design.** JWKS is fetched with stock Nimbus `RemoteJWKSet` over the
JVM default truststore. A self-signed dev IdP must have its certificate imported
(`keytool -importcert -alias wso2is -file wso2is.crt -keystore ts.jks`) and the JVM pointed at it.
⚠ On this repo `-DargLine` does **not** reach a forked surefire JVM — the parent POM's `@{argLine}`
resolves the project property — so TLS flags need `-DforkCount=0` plus `MAVEN_OPTS`.

`serve.sh`/`serve.bat` (bundled by `package.ps1 -Edition Standard`) read these from
`AUTH_OIDC_ISSUER` / `AUTH_OIDC_JWKS_URI` / `AUTH_OIDC_AUDIENCE` / `AUTH_OIDC_CLIENT_ID` /
`AUTH_OIDC_CLIENT_SECRET` environment variables — the secret is forwarded as an `${ENV:…}` reference
the backend resolves at use, so neither the bundle nor the process command line ever holds it.

### Gateway trust mode (WSO2 APIM `X-JWT-Assertion`, RBAC R0 — optional)

When the gateway terminates end-user auth and forwards its own **gateway-signed** backend JWT
(APIM's Backend JWT feature, default header `X-JWT-Assertion`), configure a second trust anchor —
`OidcAuthenticator` verifies that header through the exact same signature/issuer/audience/expiry
pipeline against the *gateway's* JWKS:

| Flag | Value |
|---|---|
| `-Dauth.oidc.gateway.issuer` | the backend-JWT issuer APIM signs with (default `wso2.org/products/am`) — setting this enables the mode |
| `-Dauth.oidc.gateway.jwksUri` | the gateway's JWKS endpoint (APIM: `https://<gw-host>/oauth2/jwks`) — required once the issuer is set |
| `-Dauth.oidc.gateway.audience` | *optional* exact-audience check on the assertion |
| `-Dauth.oidc.gateway.header` | *optional*, default `X-JWT-Assertion` |

A `Bearer` token, when present and valid, always decides first; the assertion is consulted only when
no valid Bearer subject resolves (APIM may pass the client's opaque gateway token through in
`Authorization`). A plain **unsigned** header is never trusted — `alg:none` and bare identity strings
fail verification. With the flags unset the mode is off and the header is ignored entirely. Both
paths tolerate Nimbus's default bounded clock skew (60 s) on `exp`/`nbf`.

## Explicitly out of scope

Case-type/business-function data-scoped grants (rbac-groundwork §4 Q2 — **closed 2026-07-08**, SEC-7d) and the
`canOnboardConnections` split (Q1 — **closed 2026-07-22**, implemented) both shipped and are simply not modeled in
this blueprint; Enterprise multi-realm/multi-tenant federation still needs a product decision. Decisions of
record: `../../okf/capabilities/security/security.md` §4. *(Corrected 2026-09-08: this paragraph called both
questions "still open" for seven weeks after they closed.)*
