---
type: Concept
title: Service contracts (SPI) — architecture
description: Overview of the three SPI contract modules (audit-spi, auth-spi, http-spi), how providers plug in through ServiceLoader and META-INF/services, the fail-closed vs fail-soft discovery rule, and which providers implement each seam.
resource: spi/
tags: [layer, spi, architecture, serviceloader, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Service contracts (SPI) — architecture (`layer.spi`)

The SPI layer is the `spi/` reactor directory: three small **contract** modules, kept at three by decision D-MR1
([spec-module-reorg-decisions](../platform/spec-module-reorg-decisions.md)). Each declares `buildRole: contract`,
`offeringRole: base`, `bindingTime: build` in its `META-INF/inspecto/module.toon`, so all three ship in every
Edition. They hold interfaces, value types and discovery helpers — no policy, no state, no storage.

| Module | Maven artifact | Packages | Hub |
|---|---|---|---|
| Audit contract | `spi/inspecto-audit-spi/` | `com.gamma.audit` | [audit-spi](../../module/audit-spi/index.md) |
| Authentication and capability contract | `spi/inspecto-auth-spi/` | `com.gamma.spi.auth`, `com.gamma.auth.secrets`, `com.gamma.spi` | [auth-spi](../../module/auth-spi/index.md) |
| Route module contract | `spi/inspecto-http-spi/` | `com.gamma.spi.http` | [http-spi](../../module/http-spi/index.md) |

Dependency direction: `http-spi` → `auth-spi`; `audit-spi` stands alone. Implementations sit above, in
`platform/`, `providers/`, `features/` and `la/` (layer map: [architecture-layers](../platform/architecture-layers.md)).

## How a provider plugs in

1. The provider module depends on the contract jar and implements the interface (public no-arg constructor).
2. It lists the implementing class in `src/main/resources/META-INF/services/<interface FQN>`.
3. The host discovers it with `java.util.ServiceLoader` at boot or first use — never by name. Which providers a
   bundle carries is decided at build time by the Edition profile
   ([architecture-editions-model](../platform/architecture-editions-model.md)); there are no runtime switches.

Two discovery disciplines, both in `inspecto-auth-spi`:

| Helper | Rule | Used for |
|---|---|---|
| `com.gamma.spi.OptionalSpi` (`first`, `all`) | **Fail-soft.** A provider that cannot load is skipped with one WARN; the rest of the product boots. | Optional modules: `RouteModule`, host boot hooks, component hooks. |
| `com.gamma.spi.auth.SpiSlot` | At most one provider, first found wins, cached. With `failClosed`, a registered-but-unloadable provider refuses boot. | `Authenticator` and `AccessDecider` (fail-closed); `TokenRelay`, `GeoCountryResolver`, `PrincipalDirectory`. |
| `EventSink.current()` | First provider; none ⇒ logged no-op; present-but-broken ⇒ `IllegalStateException`. | Audit and event emission. |

The rule of thumb: absence may cost a feature (route answers 503, flag off) but must never open the system. That
is why authentication and Space-isolation policy fail closed while everything else fails soft.

## Who implements what

Read from the git-tracked `META-INF/services` files (main sources only).

| Seam | Provider class(es) | Module |
|---|---|---|
| `com.gamma.audit.EventSink` | `CurrentEventSink` | [event](../../module/event/index.md) |
| `com.gamma.spi.auth.Authenticator` | `OidcAuthenticator` · `DemoAuthenticator` | [oidc](../../module/oidc/index.md) · [demo-auth](../../module/demo-auth/index.md) |
| `com.gamma.spi.auth.TokenRelay` | `OidcTokenRelay` · `DemoTokenRelay` | [oidc](../../module/oidc/index.md) · [demo-auth](../../module/demo-auth/index.md) |
| `com.gamma.spi.auth.AccessDecider` | `PolicyEngine` | [policy](../../module/policy/index.md) |
| `com.gamma.spi.auth.GeoCountryResolver` | `MaxMindGeoCountryResolver` | [geo-country](../../module/geo-country/index.md) |
| `com.gamma.auth.secrets.SecretsProvider` | `FileKeystoreSecretsProvider` | [secrets](../../module/secrets/index.md) |
| `com.gamma.spi.auth.PrincipalDirectory` | none registered | — |
| `com.gamma.spi.http.ComponentKindValidator` | `AnomalyKindValidator` · `ReconKindValidator` · `RiskScoreKindValidator` | [anomaly](../../module/anomaly/index.md) · [reconciliation](../../module/reconciliation/index.md) · [scoring](../../module/scoring/index.md) |
| `com.gamma.spi.http.ComponentDeleteHook` | `ReconComponentDeleteHook` | [reconciliation](../../module/reconciliation/index.md) |
| `com.gamma.spi.http.GovernableKindProvider` | `EntityListGovernableKinds` | [entity-list](../../module/entity-list/index.md) |
| `com.gamma.spi.http.RouteModule` | `ActionRequestRoutes` | [action-requests](../../module/action-requests/index.md) |
| | `AnomalyScoreRoutes` | [anomaly](../../module/anomaly/index.md) |
| | `CaseRoutes` | [case-management](../../module/case-management/index.md) |
| | `EntityListRoutes` | [entity-list](../../module/entity-list/index.md) |
| | `ExchangeRoutes` | [exchange](../../module/exchange/index.md) |
| | `MetricsRoutes`, `EventRoutes` | [observability](../../module/observability/index.md) |
| | `ObjectRoutes`, `NoteRoutes`, `TagRoutes` | [ops](../../module/ops/index.md) |
| | `ReconRoutes` | [reconciliation](../../module/reconciliation/index.md) |
| | `RegulatoryReportRoutes` | [regulatory-reporting](../../module/regulatory-reporting/index.md) |
| | `RiskScoreRoutes` | [scoring](../../module/scoring/index.md) |
| | `ScreeningRoutes` | [screening](../../module/screening/index.md) |
| | 25 route classes (`InvestigationRoutes`, `GraphRunRoutes`, ...) | [la-api](../../module/la-api/index.md) |
| | 4 classes (`InvestigationMeasureRoutes`, `IntegrationWebhooks`, ...) | [geo-link](../../module/geo-link/index.md) |

`EventStore` is not discovered: the event module selects its backend. `ApiContext` has exactly one
implementation, `ControlApi` in the core.

## Testing a provider

Each contract ships a TCK in its test-jar (`com.gamma.control.testkit`): `AuthenticatorContract` and
`TokenRelayContract` in `inspecto-auth-spi`; `RouteModuleContract`, `ModuleRoutesParity` and `FakeApiContext` in
`inspecto-http-spi`. A provider's test subclasses the TCK; `AuthTckSelfTest` proves the auth TCKs can fail against a deliberately broken implementer.

## References

- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/OptionalSpi.java`
- `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/SpiSlot.java`
- `spi/inspecto-audit-spi/src/main/java/com/gamma/audit/EventSink.java`
- `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/RouteModule.java`
- [architecture-module-taxonomy](../platform/architecture-module-taxonomy.md) · [Features](features.md)
