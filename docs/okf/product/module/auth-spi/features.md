---
type: Feature
title: "Authentication and capability contract — features"
description: "The authentication and capability contract defines `Authenticator`, `TokenRelay`, `AccessDecider`, `Subject`, `SecretsProvider` and `SecretResolver`."
tags: [auth-spi, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Authentication and capability contract — features

**Product Map Item:** [`module.auth-spi`](../../../../../product-map/module/auth-spi.toon) · **Status:** ga · **Availability:** Platform base — present in every Edition (Basic, Standard, Advanced, Preview).

## What it is

The authentication and capability contract defines `Authenticator`, `TokenRelay`, `AccessDecider`, `Subject`, `SecretsProvider` and `SecretResolver`. The core implements none of the enforcement; providers plug in through `ServiceLoader`.

## What this enables

- Sign-in, access decisions and secret resolution are swappable by bundle — OIDC, demo sign-in, attribute-based policy and keystore secrets all plug into this one contract — [Identity and Access](../access/features-access.md)

## Related hubs

[access](../../module/access/index.md) · [oidc](../../module/oidc/index.md) · [policy](../../module/policy/index.md) · [secrets](../../module/secrets/index.md) · [demo-auth](../../module/demo-auth/index.md)
