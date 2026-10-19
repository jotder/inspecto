---
type: Reference
title: Demo authenticator — implementation spec
description: Classes, token format, roster loading and refusals inside providers/inspecto-demo-auth.
resource: providers/inspecto-demo-auth/
tags: [module, demo-auth, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Demo authenticator — implementation spec

Package `com.gamma.demoauth`, registered in
`providers/inspecto-demo-auth/src/main/resources/META-INF/services/com.gamma.spi.auth.Authenticator` and
`providers/inspecto-demo-auth/src/main/resources/META-INF/services/com.gamma.spi.auth.TokenRelay`.

| Class | Role |
|---|---|
| `LoopbackOnly` | `require()` throws unless `-Dcontrol.bind` resolves to a loopback address; unset means every interface and is refused |
| `DemoTokens` | Mint and verify tokens of the form base64url(kind, userId, expiry) dot base64url(HMAC-SHA256); 32-byte random secret per JVM; kind `a` (access, 15 min) or `r` (refresh, 8 h) is signed |
| `DemoUsers` | Load `<space>/config/demo-users.toon`; union across Spaces; roster-conflict detection |
| `DemoTokenRelay` | `TokenRelay`: code exchange, refresh, `/bootstrap` picker data |
| `DemoAuthenticator` | `Authenticator`: Bearer access token to `Subject` |

## Token relay

* Constructor: `LoopbackOnly.require()`, then a roster check; a conflict is logged as a "DEMO SIGN-IN IS
  DISABLED" block and the server keeps running.
* `exchangeCode(code, …)`: accepts only `code = "demo:<id>"` for a known Demo User; PKCE verifier and redirect
  are ignored. Returns an access and a refresh token.
* `refresh`: a valid `r` token for a still-known user mints a fresh pair. `revoke` is the interface default (no-op).
* `bootstrapAuth`: `{mock: true, demoUsers: [{id, displayName, title, landing?}], spacesAttached}` — never roles
  or capabilities, since it is served before sign-in.
* Known users: every Space under `-Dspaces.root`, or the single `-Dassist.write.root` in legacy mode.

## Authenticator

1. Bearer must verify as an unexpired `a` token.
2. Look the user up in the bound Space (`Roles.configRoot(ex)`, else the legacy write root), then in every
   known Space (the identity is global; the bound Space decides which role names it grants).
3. Roles filtered to those the Space's effective role table defines; capabilities are their union minus
   Access Profile denials; data scopes are the union, or unscoped if any held role is unscoped.
4. Subject attributes: `displayName`, `demoUser: true`. Held roles are published for component sharing.

`principals(configRoot)` returns the same identities `authenticate` accepts, for approver and sharing pickers.

## Roster file

```
users[2]{id,displayName,title,roles,landing}:
  ra.analyst,Demo RA Analyst,Revenue Assurance analyst,operations,
  admin,Demo Admin,Platform administrator,admin;super,
```

`roles` is `;`-separated; `landing` is an optional menu item id. A row with no `id` makes the file unreadable.
The file is re-read on every call. The same id defined differently in two Spaces raises `ApiException` 409
`DEMO_ROSTER_CONFLICT` naming the user and both Spaces.

## Tests

[tests-inventory](tests-inventory.md) — TCK suites for both contracts plus real-HTTP sign-in and roster-error tests.
