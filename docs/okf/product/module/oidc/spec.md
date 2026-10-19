---
type: Reference
title: OIDC authenticator and token relay — implementation spec
description: Classes, config resolution, token validation pipeline and failure handling inside providers/inspecto-oidc.
resource: providers/inspecto-oidc/
tags: [module, oidc, spec, jwt, jwks]
timestamp: 2026-10-19T00:00:00Z
---

# OIDC authenticator and token relay — implementation spec

Package `com.gamma.oidc`, four classes, registered through
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

## `RoleMapper`

Reads role names from `rolesClaim` (default `roles`). With `provider=keycloak` and no explicit
`rolesClaim`, unions `realm_access.roles` and `resource_access.<clientId>.roles`. Unknown roles grant
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
