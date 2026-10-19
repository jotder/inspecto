---
type: Concept
title: Authentication and capability contract — architecture
description: How auth-spi keeps the core auth-free — edition providers fill the Authenticator, TokenRelay, AccessDecider, GeoCountryResolver and SecretsProvider seams, with fail-closed discovery where absence would open the system.
resource: spi/inspecto-auth-spi/
tags: [module, auth-spi, spi, architecture, security, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Authentication and capability contract — architecture (`module.auth-spi`)

## Position

`inspecto-auth-spi` is a contract module of the [SPI layer](../../layer/spi/architecture.md) (`buildRole:
contract`, `offeringRole: base`, `bindingTime: build` in its `module.toon`). It sits below `inspecto-http-spi`
(which depends on it) and below the core: `RequestAttrs` and `WriteRootProvider` were split out of `ApiContext`
(D-1 step 3) so the auth classes no longer name the HTTP contract.

```
 request -> ControlApi.dispatch
             1. Authenticators.active()  -> Authenticator.authenticate  -> Subject on the exchange
             2. rate limit
             3. authorize: AccessDeciders.active() -> AccessDecider.decide (route PEP)
                           then capability gates (ApiContext.withCapability)
             4. handler; RowScope -> AccessDecider (row PEP)
             audit: RequestAttrs.actor/ip + GeoCountryResolvers -> AuditAttrs (audit-spi)
```

## Design decisions

- **Auth-free core.** The core ships no `Authenticator`, `TokenRelay`, `AccessDecider`, `GeoCountryResolver` or
  `SecretsProvider`. Which ones a bundle carries is an Edition choice made at build time — see
  [architecture-editions-model](../../layer/platform/architecture-editions-model.md).
- **Fail-closed where absence opens the door.** The usual product rule is "absent module ⇒ no-op wins". It is
  reversed for two slots: an `Authenticator` that is registered but cannot load would leave every route open, and
  an `AccessDecider` that cannot load would drop the Space-isolation policies at route and row level. Both refuse
  to boot instead (`SpiSlot` with `failClosed`). Nothing registered still resolves empty — that is the Basic path.
- **Fail-soft for optional modules.** `OptionalSpi` skips a provider that throws during class loading so a staged
  optional jar compiled for a newer JDK cannot stop the server.
- **Grants, not roles.** `Subject` carries capability verbs and data scopes; raw claims stay inside the
  `Authenticator`. Policy attributes are an allowlisted, additive map no capability gate may read.
- **Policy allow never bypasses capability.** `ALLOW` and `ABSTAIN` both continue to the capability gate (defence in
  depth; recorded in `docs/archived-documents/plans-archive/rbac-abac-plan.md` as a deliberate tightening).
- **Secrets are references.** Connection profiles store `${SCOPE:key}` references; `SecretResolver` expands them
  when a connector needs them and refuses an unsupported scope loudly.
- **Tokens never land in records.** `PublicPaths.redact` strips share / embed tokens from paths before they reach
  logs, audit events or response links.

## Who implements what

| Seam | Provider class | Hub |
|---|---|---|
| `Authenticator`, `TokenRelay` | `OidcAuthenticator`, `OidcTokenRelay` | [oidc](../oidc/architecture.md) |
| `Authenticator`, `TokenRelay` | `DemoAuthenticator`, `DemoTokenRelay` | [demo-auth](../demo-auth/index.md) |
| `AccessDecider` | `PolicyEngine` | [policy](../policy/index.md) |
| `GeoCountryResolver` | `MaxMindGeoCountryResolver` | [geo-country](../geo-country/index.md) |
| `SecretsProvider` | `FileKeystoreSecretsProvider` | [secrets](../secrets/index.md) |
| `PrincipalDirectory` | none in this repo | — |

## Failure modes

| Symptom | Cause |
|---|---|
| Boot refused naming the Authenticator or AccessDecider | Provider registered but unloadable or misconfigured (fail-closed slot). |
| All routes unauthenticated | No `Authenticator` on the classpath — expected only for Basic. |
| `/auth/*` answer `503 CAPABILITY_UNAVAILABLE` | No `TokenRelay` in the bundle. |
| `${KEYSTORE:...}` refused naming the edition | No `SecretsProvider` (inspecto-secrets) in the bundle. |
| `-Dgeoip.db` set, no `geo_country` on audit rows, one WARN | No `GeoCountryResolver` in the bundle. |

Operating the providers (keys, flags, certificates) is in [access admin guide](../access/admin-guide-auth-security.md).

## References

- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/AccessDeciders.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/Authenticators.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/PublicPaths.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/auth/secrets/SecretResolver.java`
- [API](api.md) · [Features](features.md) · [Tests](tests-inventory.md)
