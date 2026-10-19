---
type: Guide
title: OIDC authenticator and token relay — admin guide
description: Configure OIDC sign-in (auth.oidc.* flags, Keycloak or WSO2 preset, discovery), trust the IdP certificate, and read the boot failures.
resource: providers/inspecto-oidc/
tags: [module, oidc, admin-guide, keycloak, wso2, tls, cacerts]
timestamp: 2026-10-19T00:00:00Z
---

# OIDC authenticator and token relay — admin guide

`inspecto-oidc.jar` ships in Standard, Advanced and Preview bundles (not Basic, not a `-DemoAuth` demo bundle).
Being on the classpath is what turns sign-in on: the control plane resolves the `Authenticator` at boot and
refuses to start rather than serve unauthenticated. Wider security posture: [admin-guide-auth-security](../access/admin-guide-auth-security.md).

## 1. Configuration keys (`-D` JVM flags)

| Flag | Required | Default | Meaning |
|---|---|---|---|
| `auth.oidc.provider` | no | `generic` | `generic`, `wso2` or `keycloak`. Any other value refuses boot. |
| `auth.oidc.discoveryUrl` | no | none | `<issuer>/.well-known/openid-configuration`; fills every endpoint below that is not set explicitly. https only (http allowed for localhost). |
| `auth.oidc.issuer` | yes* | from discovery | Exact `iss` the tokens must carry. |
| `auth.oidc.jwksUri` | yes* | from discovery / keycloak preset | Signing keys (RS256). |
| `auth.oidc.tokenEndpoint` | yes* | from discovery / keycloak preset | Server-side code and refresh exchange. Never derived for `generic` or `wso2`. |
| `auth.oidc.audience` | no (warned) | none | Expected `aud`. Unset logs "OIDC audience validation is OFF" at boot. |
| `auth.oidc.clientId` | no | `inspecto-spa` | The SPA's client id. |
| `auth.oidc.clientSecret` | no | none | Confidential client only; pass a reference such as `${ENV:AUTH_OIDC_CLIENT_SECRET}`, never the value. |
| `auth.oidc.rolesClaim` | no | `roles` | Claim holding role names (WSO2: `groups`). Setting it switches the Keycloak union off. |
| `auth.oidc.authorizeUrl`, `auth.oidc.endSessionUrl`, `auth.oidc.scopes` | no | from discovery / preset | Published to the SPA by `GET /bootstrap`; no `authorizeUrl` means the SPA gets no OIDC config from the server. |
| `auth.oidc.gateway.issuer`, `.jwksUri`, `.audience`, `.header` | no | header `X-JWT-Assertion` | Gateway trust mode: accept a gateway-signed JWT when no valid Bearer token is present. Setting `.issuer` makes `.jwksUri` required. |

\* Required after precedence: explicit flag, then discovery, then the `keycloak` preset
(`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcConfig.java`).

The bundle launchers map environment variables to these flags (`inspecto/package.ps1`, `serve.bat` / `serve.sh`):
`AUTH_OIDC_ISSUER`, `AUTH_OIDC_JWKS_URI`, `AUTH_OIDC_AUDIENCE`, `AUTH_OIDC_CLIENT_ID`, `AUTH_OIDC_PROVIDER`,
`AUTH_OIDC_DISCOVERY_URL`, `AUTH_OIDC_CLIENT_SECRET` (passed as an `${ENV:…}` reference). Anything else
(`tokenEndpoint` without discovery, `rolesClaim`) goes in `INSPECTO_JAVA_OPTS`.

## 2. Keycloak or WSO2

**Keycloak** — the shortest setup:

```
AUTH_OIDC_PROVIDER=keycloak
AUTH_OIDC_ISSUER=https://<host>/realms/<realm>
AUTH_OIDC_CLIENT_ID=<client>
AUTH_OIDC_AUDIENCE=<client>
```

The preset derives JWKS, token, authorize and logout URLs and unions realm and client roles. Realm needs:
an audience mapper (otherwise `aud` is `account`) and the built-in `basic` scope on the client (Keycloak 26
tokens carry no `sub` without it, and a token with no `sub` is refused). Reference realm:
`dev-infra/keycloak/inspecto-realm.json`.

**WSO2 Identity Server** — use discovery (or set every endpoint):

```
AUTH_OIDC_PROVIDER=wso2
AUTH_OIDC_DISCOVERY_URL=https://<host>:9443/oauth2/token/.well-known/openid-configuration
AUTH_OIDC_CLIENT_ID=<client>          # WSO2: [a-zA-Z0-9_]{15,30}
AUTH_OIDC_AUDIENCE=<client>           # WSO2: the client id
INSPECTO_JAVA_OPTS=-Dauth.oidc.rolesClaim=groups
```

The WSO2 application must issue JWT access tokens (`ext_token_type: JWT`) and list roles under
`accessTokenAttributes`; display names need a `name` or `preferred_username` claim. Full walk-through:
[admin-guide-real-signin](../la-core/admin-guide-real-signin.md) and `docs/api/deployment/README.md`.

## 3. Certificates and truststore

All IdP calls (discovery, JWKS, token endpoint) use the JVM default truststore. A packaged bundle runs its
**own** embedded runtime, so a private or self-signed IdP certificate must be imported into the bundle's
`runtime/lib/security/cacerts`, not the system JDK's:

```
openssl s_client -connect <idp-host>:<port> -servername <idp-host> | openssl x509 > idp.crt
<bundle>\runtime\bin\keytool -importcert -alias idp -file idp.crt -keystore <bundle>\runtime\lib\security\cacerts -storepass changeit -noprompt
```

Replacing the runtime folder on a redeploy discards that import — re-import, or keep the old `cacerts`.
A bundle built with `-NoRuntime` uses the target JVM's truststore. The control plane's own HTTPS listener is a separate setting (`-Dhttps.keystore`, `-Dhttps.keystore.password`;
[admin-guide-auth-security](../access/admin-guide-auth-security.md)).

## 4. Failure modes

| Symptom | Cause |
|---|---|
| Boot fails: "inspecto-oidc requires -Dauth.oidc.issuer" / "jwksUri" / "tokenEndpoint" | Value neither set nor derivable. Set it or add `auth.oidc.discoveryUrl`. |
| Boot fails: "-Dauth.oidc.provider must be one of generic, wso2, keycloak" | Typo in the provider value. |
| Boot fails: "could not load the OIDC discovery document" | IdP unreachable, non-200, not a discovery document, or a TLS trust failure (see section 3). |
| Boot fails: "discoveryUrl must be https" | http used for a non-loopback host. |
| Boot fails: "the issuer in the discovery document does not equal -Dauth.oidc.issuer" | Explicit issuer disagrees with the IdP; drop the explicit value or fix it. |
| Boot fails with missing `com/nimbusds` classes | Plain jar staged instead of the shaded sidecar ([architecture](architecture.md)). |
| Every request 401 | Opaque (non-JWT) tokens, wrong issuer or audience, no `sub`, expired, or a JWKS fetch blocked by TLS trust. Errors are deliberately not returned to the caller. |
| Sign-in works, nothing visible | Role names do not match the Space's role table, or the wrong `rolesClaim`. |
| Warning "OIDC audience validation is OFF" | `auth.oidc.audience` unset; any token from the issuer is accepted. |
