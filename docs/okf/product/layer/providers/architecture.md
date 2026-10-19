---
type: Concept
title: Providers layer — architecture
description: Every module in the providers layer, the SPI each implements, how it is discovered at boot, and which Edition stages it.
resource: providers/
tags: [layer, providers, spi, editions, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Providers layer — architecture

A **provider** (`offeringRole: provider` in its manifest) is an implementation of a contract the platform
owns: an authenticator, a secrets backend, a connector, a parser. The core never names a provider class; it
asks `ServiceLoader` for the contract, so a bundle that leaves a provider jar out simply has no
implementation (and the core falls back or fails closed). Modules whose Product Map Item has
`parent: layer.providers` (`product-map/module/*.toon`) live under `providers/`.

## How a provider plugs in

1. **Contract** — an interface in an SPI or platform module (e.g. `com.gamma.parse.ParserPlugin`,
   `com.gamma.spi.auth.AccessDecider`).
2. **Registration** — `src/main/resources/META-INF/services/<contract FQCN>` names the implementation; the
   core loads it with `ServiceLoader` at boot.
3. **Manifest** — `src/main/resources/META-INF/inspecto/module.toon` declares `id`, `offeringRole: provider`,
   `bindingTime: boot` and `provides.contracts` (plus `stepKinds`, `requires.modules` where relevant). The
   host reads every manifest on the classpath (`platform/inspecto-util/src/main/java/com/gamma/module/ModuleManifests.java`)
   and serves them at `GET /modules` (`inspecto/src/main/java/com/gamma/control/ModulesRoutes.java`), which is
   how a missing provider is named in a refusal instead of a bare "unknown".
4. **Build and Edition** — a module is either in the default `<modules>` of the root `pom.xml` (always
   compiled and tested) or only in the `edition-standard` / `edition-advanced` / `edition-preview` profiles.
   Which bundle **stages** the jar is decided separately by the `from:` floor in `tools/bundle-modules.mjs`
   and listed per Edition in `offerings/*.toon`. Editions are build flavours, never branches
   (`docs/EDITIONS.md`).

## The modules

| Module hub | Directory | Contract(s) provided | Reactor | Staged from |
|---|---|---|---|---|
| [connectors](../../module/connectors/index.md) | `providers/inspecto-connectors` | `CollectorConnectorFactory`, `ExportConnectorFactory`, `DeliveryStatusAdapter` | default | Basic (all) |
| [connectors-kafka](../../module/connectors-kafka/index.md) | `providers/inspecto-connectors-kafka` | `CollectorConnectorFactory` (Kafka) | edition profiles | Standard |
| [oidc](../../module/oidc/index.md) | `providers/inspecto-oidc` | `Authenticator`, `TokenRelay` | edition profiles | Standard |
| [secrets](../../module/secrets/index.md) | `providers/inspecto-secrets` | `SecretsProvider` (file keystore) | edition profiles | Standard |
| [geo-country](../../module/geo-country/index.md) | `providers/inspecto-geo-country` | `GeoCountryResolver` (MaxMind) | edition profiles | Standard |
| [notify-channels](../../module/notify-channels/index.md) | `providers/inspecto-notify-channels` | `NotificationChannel`, `WebhookSinkTransport` | edition profiles | Standard |
| [telecom-asn1](../../module/telecom-asn1/index.md) | `providers/inspecto-telecom-asn1` | `ParserPlugin`, step kind `parser.asn1.ber` | default | Standard |
| [asn-decoders](../../module/asn-decoders/index.md) | `providers/asn-parser/asn-decoders` | none — a library reactor consumed by telecom-asn1 | default (aggregated) | via telecom-asn1 |
| [policy](../../module/policy/index.md) | `providers/inspecto-policy` | `AccessDecider` (ABAC) | edition profiles | Advanced |
| [agent-hosted](../../module/agent-hosted/index.md) | `providers/inspecto-agent-hosted` | `HostedProviderPlugin` (requires `agent`) | default | not a `tools/bundle-modules.mjs` row; the default air-gapped packaging omits it |
| [demo-auth](../../module/demo-auth/index.md) | `providers/inspecto-demo-auth` | `Authenticator`, `TokenRelay` (demo) | default | no Edition; only `inspecto/package.ps1 -DemoAuth` stages it |

"Reactor: default" means the root `pom.xml` `<modules>`; "edition profiles" means it is listed only inside
the `edition-*` profiles, so a plain `mvn -o clean test` (Basic) neither compiles nor tests it — use
`-Pedition-standard` or `-Pedition-advanced`.

## Alternatives on one contract

Some contracts have more than one provider; the one present on the classpath wins. `Authenticator` is
implemented by both `oidc` and `demo-auth` (an offline demo build stages the latter); with neither, Basic runs
without sign-in (`offerings/basic.toon` `authMode: none`). `CollectorConnectorFactory` is implemented by
`connectors` and `connectors-kafka` side by side, each serving its own connector ids.

## References

- [platform architecture-module-taxonomy](../platform/architecture-module-taxonomy.md) — the module taxonomy and manifest fields
- [platform architecture-editions-model](../platform/architecture-editions-model.md) — the Edition model
- [features layer architecture](../features/architecture.md) — the optional feature modules
