---
type: Guide
title: OIDC authenticator and token relay — admin guide
description: Configure OIDC sign-in (auth.oidc.* flags, Keycloak or WSO2 preset, discovery), trust the IdP certificate, and read the boot failures.
resource: providers/inspecto-oidc/
tags: [module, oidc, admin-guide, keycloak, wso2, tls, cacerts]
timestamp: 2026-11-01T00:00:00Z
---

# OIDC authenticator and token relay — admin guide

`inspecto-oidc.jar` ships in Standard, Advanced and Preview bundles (not Basic, not a `-Distribution demo` demo bundle).
Being on the classpath is what turns sign-in on: the control plane resolves the `Authenticator` at boot and
refuses to start rather than serve unauthenticated. Wider security posture: [admin-guide-auth-security](../access/admin-guide-auth-security.md).

## 1. Configuration keys (`-D` JVM flags)

| Flag | Required | Default | Meaning |
|---|---|---|---|
| `auth.oidc.provider` | no | `generic` | `generic`, `wso2` or `keycloak`. Any other value refuses boot. |
| `auth.oidc.discoveryUrl` | no | none | `<issuer>/.well-known/openid-configuration`; fills every endpoint below that is not set explicitly. https only (http allowed for localhost). |
| `auth.oidc.issuer` | yes* | from discovery | Exact `iss` the tokens must carry. |
| `auth.oidc.jwksUri` | yes* | from discovery / keycloak preset | Signing keys (RS256). |
| `auth.oidc.tokenEndpoint` | yes* | from discovery / keycloak preset | Server-side code and refresh exchange. No path preset for `generic` or `wso2`: set it or use discovery. |
| `auth.oidc.audience` | no (warned) | none | Expected `aud`. Unset logs "OIDC audience validation is OFF" at boot. |
| `auth.oidc.clientId` | no | `inspecto-spa` | The SPA's client id. On `keycloak` it is also the only client whose client roles count (`resource_access.<clientId>.roles`); the token's `azp` is never used, so set it if your SPA client has another id. |
| `auth.oidc.clientSecret` | no | none | Confidential client only; pass a reference such as `${ENV:AUTH_OIDC_CLIENT_SECRET}`, never the value. |
| `auth.oidc.rolesClaim` | no | `roles` | Claim holding role names (WSO2: `groups`). Setting it switches the Keycloak union off. |
| `auth.oidc.authorizeUrl`, `auth.oidc.endSessionUrl`, `auth.oidc.scopes` | no | URLs from discovery / keycloak preset; `scopes` none (explicit only) | Published to the SPA by `GET /bootstrap`; no `authorizeUrl` means the SPA gets no OIDC config from the server. |
| `auth.oidc.gateway.issuer`, `.jwksUri`, `.audience`, `.header` | no | header `X-JWT-Assertion` | Gateway trust mode: accept a gateway-signed JWT when no valid Bearer token is present. Setting `.issuer` makes `.jwksUri` required. |
| `auth.oidc.clients` | no | none (any client) | Comma-separated machine (client-credentials) clients that may call with a Bearer token. Setting it pins clients: see section 5. |
| `auth.oidc.maxTokenLifetime` | no | none | Machine tokens only: ISO-8601 ceiling on `exp - iat`, such as `PT5M`. Requires `auth.oidc.clients`. |
| `auth.oidc.refuseReplay` | no | `false` | Machine tokens only: `true` accepts each `jti` once per node. Requires `auth.oidc.clients` and `auth.oidc.maxTokenLifetime`. |

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
| Boot fails: "-Dauth.oidc.clients requires -Dauth.oidc.audience" | Client pinning is on without an audience (section 5). |
| Boot fails: "-Dauth.oidc.jwksUri must be https" | Client pinning is on and the JWKS (explicit, discovered or preset) is plain http on a non-loopback host. |
| Boot fails: "maxTokenLifetime and refuseReplay apply to machine clients only" | One of them is set without `auth.oidc.clients`. |
| Boot fails: "refuseReplay requires -Dauth.oidc.maxTokenLifetime" | Replay refusal without a ceiling; the `jti` cache would be unbounded. |
| Boot fails: "maxTokenLifetime must be a positive ISO-8601 duration" / "refuseReplay must be true or false" | Malformed value (`5m` is refused; write `PT5M`). |
| A machine client 401s, people still sign in | Client not in `auth.oidc.clients`, token names no `azp` or `client_id`, no `iat` or too long-lived under a ceiling, or a reused or missing `jti` under replay refusal. |

## 5. Machine clients (client-credentials tokens)

Without `auth.oidc.clients`, any client of the issuer is accepted, as before. To let an integration call with a
client-credentials token (security review: [tests-security-review-oidc-client-credentials](tests-security-review-oidc-client-credentials.md)):

```
AUTH_OIDC_AUDIENCE=<audience>
INSPECTO_JAVA_OPTS=-Dauth.oidc.clients=la-integration -Dauth.oidc.maxTokenLifetime=PT5M -Dauth.oidc.refuseReplay=true
```

* With `auth.oidc.clients` set, boot requires `auth.oidc.audience` and an https JWKS (http only on loopback).
* A Bearer token is admitted only when its client, `azp` else `client_id`, is listed, or is the SPA client
  (`auth.oidc.clientId`, default `inspecto-spa`). A token naming no client is refused. People sign in through the
  SPA client, so their tokens are never checked for lifetime or replay; do not list the SPA client as a machine client.
* `maxTokenLifetime` requires `iat` on machine tokens and refuses `exp - iat` above it.
* `refuseReplay` makes every machine token single-use: the client must fetch a new token for each call. Most
  client-credentials libraries reuse a token until it expires, and those get a 401 on the second call; turn it on
  only for a client built that way.
* `refuseReplay` requires `jti` and refuses a `jti` already seen, until its `exp` plus the 60 s skew. The cache is
  per node: behind a load balancer a token can still be used once on each node.
* Not pinned: the gateway trust path (`auth.oidc.gateway.*`), whose assertion the gateway mints for its own callers.
* **What a machine may do, and where** (`OIDC-CC-INTEGRATION-ROLE-1`, operator-decided 2026-11-01). Give the service
  account the seed role `integration` (capability `canOpenInboundInvestigation`: `POST /inv/inbound/investigations` and
  reads of the Investigations it leads). A machine keeps only that capability, whatever roles it holds: every other
  granted capability is dropped at authentication. It is admitted only in a Space whose `roles.toon` names its client:

  ```
  identity:
    machine_clients[1]: la-integration
  ```

  Elsewhere every call answers 403 (a public path just treats it as anonymous). Saving roles from the role editor keeps
  the list. Keycloak may send no `gty`, so list the client in `auth.oidc.clients` too, or its token is taken for a person's.
* **Audit.** A machine call is recorded as actor `client:<client id>` (`azp`, else `client_id`) with `actor_type: service`,
  and the token's `sub` (on Keycloak the service-account user's UUID) as `idp_subject` on the same row. A token is a
  machine's when its client is listed in `auth.oidc.clients`, or, list or not, when it carries `gty: client_credentials`
  (Auth0: `client-credentials`). Keycloak may send no `gty`, so list the client there. The same `client:<client id>` is
  the owner of what the machine creates (an inbound Investigation's lead, for example). A person's token is unchanged.
  Upgrading: what a machine created before 2026-11-01, and its earlier audit rows, stay under the old `sub` (the
  service-account UUID). Nothing is migrated; reassign the lead by hand if the machine must read it back.
