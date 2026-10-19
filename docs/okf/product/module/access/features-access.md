---
type: Feature
title: "Identity and Access — features"
description: "Identity and Access decides **who is calling** and **what they may do**."
tags: [access, access, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Identity and Access — features

**Availability:** Offering: auth-free on Basic; OIDC sign-in and file/keystore secrets on Standard and up; Attribute-based Access Policies (ABAC) on Advanced and Preview; Demo User sign-in only in a `-DemoAuth` demo bundle.

## What it does

Identity and Access decides **who is calling** and **what they may do**. The core defines the contracts and
implements none of the enforcement; optional modules plug in through `ServiceLoader`:

| Piece | Module | What it contributes |
|---|---|---|
| SPI contracts | `spi/inspecto-auth-spi` | `Authenticator`, `TokenRelay`, `AccessDecider`, `Subject`, `SecretsProvider`, `SecretResolver` |
| Roles, grants, row scope, audit | `platform/inspecto-access` | `Roles` (role → capability table), `AccessGrants`, `AccessPolicyStore`, `RowScope`, `WriteGates` |
| OIDC sign-in | `providers/inspecto-oidc` | `OidcAuthenticator` (JWT → `Subject`), `OidcTokenRelay` (code exchange / refresh / logout) |
| Demo sign-in | `providers/inspecto-demo-auth` | `DemoAuthenticator`, `DemoTokenRelay` — pick a Demo User, no password |
| ABAC engine | `providers/inspecto-policy` | `PolicyEngine` (the `AccessDecider`), plus the `PII_MASK` edition feature |
| Secrets schemes | `providers/inspecto-secrets` | `FileKeystoreSecretsProvider` — `${FILE:…}` and `${KEYSTORE:…}` |

The full specification, decisions and refusals live in [Security](plan-security.md); this page is the
module-level map.

## Where it fits

Platform base, in front of every `ControlApi` route. The access module is `bindingTime: build`
(`platform/inspecto-access/src/main/resources/META-INF/inspecto/module.toon`); the four providers are
`bindingTime: boot` and are selected purely by being on the classpath — no runtime flag turns them on.
Maven profiles in `pom.xml`: `edition-standard`, `edition-preview` and `edition-advanced` add `inspecto-oidc` and
`inspecto-secrets`; `edition-preview` and `edition-advanced` add `inspecto-policy` too (Basic is the default
build, no profile).
`inspecto-demo-auth` is a default reactor module but `inspecto/package.ps1` stages it only under
`-DemoAuth`, replacing `inspecto-oidc.jar`; `tools/check-demo-auth-isolation.mjs` fails CI if it leaks into a
non-demo bundle.

More: [screens](user-guide-access.md) · [API](api-access.md) · [architecture](architecture-overview.md).
