---
type: Feature
title: "OIDC authenticator and token relay — features"
description: "OIDC sign-in turns an identity provider's JWT into an Inspecto Subject and relays tokens for code exchange, refresh and logout."
tags: [oidc, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# OIDC authenticator and token relay — features

**Product Map Item:** [`module.oidc`](../../../../../product-map/module/oidc.toon) · **Status:** ga · **Availability:** Optional module, staged from Standard and every higher Edition (`offerings/standard.toon`).

## What it is

OIDC sign-in turns an identity provider's JWT into an Inspecto Subject and relays tokens for code exchange, refresh and logout. It is vendor-neutral and selected purely by being on the classpath.

## Who benefits

Enterprises that need single sign-on through their own identity provider.

## Key capabilities

- JWKS-validated resource server with role mapping — [architecture](architecture.md)
- Real sign-in path for Standard and Advanced releases — [admin-guide-real-signin](../la-core/admin-guide-real-signin.md)

## Related hubs

[access](../../module/access/index.md) · [auth-spi](../../module/auth-spi/index.md) · [demo-auth](../../module/demo-auth/index.md)

More in this hub: [architecture](architecture.md).
