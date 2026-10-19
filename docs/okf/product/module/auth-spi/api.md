---
type: Reference
title: Authentication and capability contract — Java API
description: The auth-spi Java contract — Authenticator, TokenRelay, AccessDecider, GeoCountryResolver, PrincipalDirectory, SecretsProvider, Subject, SpiSlot and OptionalSpi — with semantics, ServiceLoader registration and how to implement a provider.
resource: spi/inspecto-auth-spi/
tags: [module, auth-spi, spi, api, security]
timestamp: 2026-10-19T00:00:00Z
---

# Authentication and capability contract — Java API (`module.auth-spi`)

Packages `com.gamma.spi.auth`, `com.gamma.auth.secrets` and `com.gamma.spi` in `spi/inspecto-auth-spi/`. The core
implements **none** of the enforcement seams; providers plug in through `META-INF/services/<interface FQN>`. The
`/auth/*` HTTP routes that use them are documented in [access api](../access/api-access.md).

## Provider seams (ServiceLoader)

| Interface | Methods | Resolved by | Absent ⇒ | Registered providers |
|---|---|---|---|---|
| `com.gamma.spi.auth.Authenticator` | `Optional<Subject> authenticate(HttpExchange)`; default `principals(Path configRoot)` | `Authenticators.active()` | no authentication at all (Basic) | `OidcAuthenticator` ([oidc](../oidc/index.md)), `DemoAuthenticator` ([demo-auth](../demo-auth/index.md)) |
| `com.gamma.spi.auth.TokenRelay` | `exchangeCode(code, verifier, redirectUri[, nonce])`, `refresh(refreshToken)`; defaults `endSessionUrl`, `revoke`, `bootstrapAuth` | `TokenRelays` in the core (`inspecto/src/main/java/com/gamma/control/TokenRelays.java`) | `/auth/*` answer `503 CAPABILITY_UNAVAILABLE` | `OidcTokenRelay`, `DemoTokenRelay` |
| `com.gamma.spi.auth.AccessDecider` | `Decision decide(ex, subject, action, route, resourceKind, resource)`; defaults `seededPolicies`, `explain`, `simulate` | `AccessDeciders.active()` | both PEPs skip policy evaluation | `PolicyEngine` ([policy](../policy/index.md)) |
| `com.gamma.spi.auth.GeoCountryResolver` | `Optional<Geo> resolve(String ip)`; `record Geo(String country, long dbBuild)` | `GeoCountryResolvers.active()` | `-Dgeoip.db` is inert, one WARN | `MaxMindGeoCountryResolver` ([geo-country](../geo-country/index.md)) |
| `com.gamma.spi.auth.PrincipalDirectory` | `boolean exists(String userId) throws IOException` | `PrincipalDirectories.active()` / `requireKnown` | user ids get the id-shape check only | none registered in this repo |
| `com.gamma.auth.secrets.SecretsProvider` | `String resolve(String scope, String key)` | `SecretResolver` | `${FILE:}` / `${KEYSTORE:}` refused with a message naming the edition | `FileKeystoreSecretsProvider` ([secrets](../secrets/index.md)) |

### Method semantics

- **`Authenticator.authenticate`** — validate the request's credentials and return the acting `Subject`, or empty.
  The `Subject` carries resolved grants only — never raw token claims or role names.
- **`AccessDecider.decide`** — returns `Decision.ALLOW`, `DENY` or `ABSTAIN`. `DENY` is a 403 at the route PEP and
  invisibility (404 / filtered) at the row PEP (`RowScope`). `ALLOW` and `ABSTAIN` both fall through to the
  capability gates: a policy allow never bypasses a capability check.
- **`TokenRelay`** — server-to-server OIDC code exchange and refresh for the backend-mediated session; the SPA never
  sees the refresh token (it stays in an `httpOnly` cookie).
- **`GeoCountryResolver.resolve`** — runs in-process on the audit path: must be fast, must never make a network
  call, and should answer empty (not throw) for private, loopback or unknown addresses. Country only — `Geo` has no
  city field by design (decision D12).
- **`PrincipalDirectory.exists`** — authoritative and fail-closed when registered: an unknown user, or an
  `IOException`, refuses the save that asked.
- **`SecretsProvider.resolve`** — return `null` for an absent or unreadable secret; never log a resolved value.

## Shared value types

| Type | Purpose |
|---|---|
| `Subject` (record) | `id`, `capabilities`, `dataScopes` (`null` = unscoped, empty set = only untyped objects), `attributes` (allowlisted IdP claims bound as `subject.*`; nothing capability-gated may read them), verified `email`. |
| `RequestAttrs` | Request-scoped attribute names (`ATTR_SUBJECT`, `ATTR_CORRELATION_ID`, `ATTR_EMBED_GRANT`, ...) and identity helpers `actor`, `actorType`, `ip`, `userAgent`. |
| `AccessPolicies` | Parsed Access Policy shapes (`Policy`, `Doc`, `Warning`); `ACTIONS` = read, write, operate, embed; `RESOURCE_KINDS` = alert, incident, case, task, investigation. Contract only — storage is the core's `AccessPolicyStore`, evaluation the policy engine's. |
| `EmbedGrant` | Deliberately powerless principal of a read-only embed link: not a `Subject`, so every capability gate fails closed for it. |
| `PublicPaths` | Registry of self-verifying route prefixes (share / embed tokens) and `redact(path)`, applied before a path reaches a log line, audit event or link. |
| `ApiException`, `ErrorCodes` | HTTP status + machine-readable v1 error code; codes are additive only, never renamed. |
| `SecretResolver` | Expands `${ENV:}`, `${SYS:}` and bare `${NAME}` in every edition; `${FILE:}` and `${KEYSTORE:}` via `SecretsProvider`; anything else passes through as a literal. |

## Discovery helpers

- **`SpiSlot<T>`** — resolve at most one provider, first found wins, cached after the first lookup. The
  `failClosed` constructor flag makes a registered-but-unloadable provider fatal. `Authenticators` and
  `AccessDeciders` are fail-closed: when nothing is registered they still resolve empty, but a broken registration
  refuses to boot rather than silently serving unauthenticated or dropping the Space-isolation policies (pinned by
  `providers/inspecto-oidc/src/test/java/com/gamma/oidc/AuthenticatorDiscoveryFailClosedTest.java`).
- **`OptionalSpi.first` / `OptionalSpi.all`** — fail-soft discovery for optional modules: a provider that fails to
  load (for example `UnsupportedClassVersionError`) is skipped with one WARN instead of failing boot.

## Implementing a provider

1. Depend on `inspecto-auth-spi`; implement the interface with a public no-arg constructor.
2. Register it in `src/main/resources/META-INF/services/com.gamma.spi.auth.Authenticator` (one class name per line).
3. Ship one provider per seam in a bundle — slots take the first found and ServiceLoader order is unspecified.
4. Prove it with the TCK: subclass `AuthenticatorContract` (implement `authenticator()`) or `TokenRelayContract`
   (implement `relay()`) from the `inspecto-auth-spi` test-jar, package `com.gamma.control.testkit`.

```java
public final class HeaderAuthenticator implements com.gamma.spi.auth.Authenticator {
    @Override
    public java.util.Optional<com.gamma.spi.auth.Subject> authenticate(com.sun.net.httpserver.HttpExchange ex) {
        String user = ex.getRequestHeaders().getFirst("X-Test-User");   // illustration only — never trust a header
        return user == null ? java.util.Optional.empty()
                : java.util.Optional.of(new com.gamma.spi.auth.Subject(user, java.util.Set.of(/* capability verbs */)));
    }
}
```

## References

- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/SpiSlot.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/Authenticators.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/OptionalSpi.java`
- `spi/inspecto-auth-spi/src/test/java/com/gamma/control/testkit/AuthenticatorContract.java`
- [Architecture](architecture.md) · [Features](features.md) · [Tests](tests-inventory.md)
