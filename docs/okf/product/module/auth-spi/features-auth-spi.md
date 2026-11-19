---
type: Feature
title: "Authentication and capability contract — features"
description: "The authentication and capability contract defines `Authenticator`, `TokenRelay`, `AccessDecider`, `Subject`, `SecretsProvider` and `SecretResolver`."
tags: [auth-spi, features, presales]
timestamp: 2026-10-19T00:00:00Z
item: module.auth-spi
audience: internal
status: current
verified: 2026-11-18 last-edit
code: spi/inspecto-auth-spi
---

# Authentication and capability contract — features

**Product Map Item:** [`module.auth-spi`](../../../../../product-map/module/auth-spi.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

## What it is

The authentication and capability contract defines `Authenticator`, `TokenRelay`, `AccessDecider`, `Subject`, `SecretsProvider` and `SecretResolver`. The core implements none of the enforcement; providers plug in through `ServiceLoader`.

## What this enables

- Sign-in, access decisions and secret resolution are swappable by bundle — OIDC, demo sign-in, attribute-based policy and keystore secrets all plug into this one contract — [Identity and Access](../access/features-access.md)

## Related hubs

[access](../access/index.md) · [oidc](../oidc/index.md) · [policy](../policy/index.md) · [secrets](../secrets/index.md) · [demo-auth](../demo-auth/index.md)
