---
type: Reference
title: OIDC authenticator and token relay — implementation spec
description: Classes, config resolution, token validation pipeline and failure handling inside providers/inspecto-oidc.
resource: providers/inspecto-oidc/
tags: [module, oidc, spec, jwt, jwks]
timestamp: 2026-11-01T00:00:00Z
---

# OIDC authenticator and token relay — implementation spec

Package `com.gamma.oidc`, five classes, registered through
`providers/inspecto-oidc/src/main/resources/META-INF/services/com.gamma.spi.auth.Authenticator` and
`providers/inspecto-oidc/src/main/resources/META-INF/services/com.gamma.spi.auth.TokenRelay`. Module manifest:
`providers/inspecto-oidc/src/main/resources/META-INF/inspecto/module.toon` (`bindingTime: boot`).

## `OidcConfig` — endpoint resolution

`OidcConfig.get(key)` for `issuer | jwksUri | tokenEndpoint | authorizeUrl | endSessionUrl`. Precedence per key:

1. explicit `-Dauth.oidc.<key>` (always wins);
2. the discovery document at `-Dauth.oidc.discoveryUrl` (fetched once per URL, cached, 10 s timeouts),
   fields `issuer`, `jwks_uri`, `token_endpoint`, `authorization_endpoint`, `end_session_endpoint`;
3. the `keycloak` preset only: `<issuer>/protocol/openid-connect/{certs,token,auth,logout}`.

Refusals (all `IllegalStateException`, value-free messages): unknown `auth.oidc.provider`; discovery URL
not https (http allowed only for `localhost`, `127.0.0.1`, `[::1]`); discovery unreachable, not HTTP 200 or
without `issuer`; discovery `issuer` not equal to an explicit `-Dauth.oidc.issuer` (trailing slashes ignored).

## `OidcAuthenticator` — Bearer and gateway validation

* One Nimbus `DefaultJWTProcessor` per trust path: RS256 key selector over a `RemoteJWKSet`, type
  allowlist `JWT`, `at+jwt` or absent (RFC 9068; WSO2 and Keycloak stamp `at+jwt`), exact issuer,
  audience when set, required claims `sub` and `exp`, Nimbus default 60 s clock skew.
* `authenticate`: a `Bearer` token decides first; the gateway header (default `X-JWT-Assertion`) is read
  only when no Bearer subject resolves and gateway mode is on. Any exception yields `Optional.empty()` (401).
* On success the Subject is built from: capabilities from `RoleMapper.capabilitiesFor` over
  `Roles.effective(ex)` (the bound Space's `roles.toon` over the seed), minus
  `AccessGrants.deniedCapabilities`; data scopes; attributes limited to the Space's
  `identity.attributeClaims` allowlist; `email` only when `email_verified` is true; a display name from
  `name`, `given_name` + `family_name`, or `preferred_username`. The issuer is stamped on the exchange so
  Bearer and gateway callers get separate idempotency namespaces.
* Bearer path only, after the token verifies: `MachineTrust.admits` when `-Dauth.oidc.clients` is set
  (`OIDC-CC-CLIENT-PIN-1`, `OIDC-CC-LIFETIME-REPLAY-1`, 2026-11-01). Checking after the signature means a
  forged token can never fill the replay cache.

## `MachineTrust` — client pin, lifetime ceiling, replay refusal

`MachineTrust.fromSystemProperties()` returns `null` (off) when `auth.oidc.clients` is unset. Refusals at boot
(`IllegalStateException`, value-free): `maxTokenLifetime` or `refuseReplay` without `clients`; `clients` without
`auth.oidc.audience`; an effective `jwksUri` that is not https (loopback http allowed, `OidcConfig.requireHttps`,
shared with the discovery URL check); `refuseReplay` without `maxTokenLifetime` (bounds the cache); a lifetime
that is not a positive ISO-8601 duration; a `refuseReplay` other than `true` / `false`.

`admits(claims, now)`: the client is `azp`, else `client_id`; none → refused. The SPA client
(`auth.oidc.clientId`, default `inspecto-spa`) → admitted with no further check, so human sign-in is unchanged.
A client not in the list → refused. For a listed client: under a ceiling, `iat` is required and
`exp - iat` may not exceed it; under replay refusal, `jti` is required and recorded until `exp` + 60 s, a second
use is refused, and expired entries are purged on each check. The cache is per node and in memory.

## `RoleMapper`

Reads role names from `rolesClaim` (default `roles`). With `provider=keycloak` and no explicit
`rolesClaim`, unions `realm_access.roles` and `resource_access.<clientId>.roles`, where `clientId` is
`auth.oidc.clientId` (default `inspecto-spa`, as `OidcTokenRelay`). The token's `azp` is never the client:
a caller would then pick its own roles (`OIDC-KEYCLOAK-AZP-ROLES-1`, 2026-11-01). Unknown roles grant
nothing (fail closed).

## `OidcTokenRelay`

* `exchangeCode` posts `authorization_code` + PKCE verifier to `tokenEndpoint`; with a `nonce`, the returned
  ID token must carry the same `nonce` (constant-time compare) or the exchange is refused.
* `refresh` posts `refresh_token`. Any IdP error, 5xx or timeout yields empty (the caller 401s).
* `clientSecret` goes through `SecretResolver`, so it is a reference such as `${ENV:NAME}`.
* `bootstrapAuth` publishes only public config (`authorizeUrl`, `clientId`, `scopes`, `endSessionUrl`) for
  anonymous `GET /bootstrap`; empty when no `authorizeUrl` resolves.
* `endSessionUrl` builds the RP-initiated logout URL with `id_token_hint`, `client_id` and optional
  `post_logout_redirect_uri`.

## Tests

[tests-inventory](tests-inventory.md); the TCK suites (`OidcAuthenticatorTckTest`, `OidcTokenRelayTckTest`)
run the shared contract checks from auth-spi.
