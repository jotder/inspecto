---
type: Feature
title: "Providers — features"
description: "Providers are the pluggable implementations behind the SPI contracts — sign-in, secrets, policy, connectors, decoders, geo lookup."
tags: [providers, features, presales]
timestamp: 2026-10-19T00:00:00Z
item: layer.providers
audience: internal
status: current
verified: 2026-11-18 last-edit
---

# Providers — features

**Product Map Item:** [`layer.providers`](../../../../../product-map/layer/providers.toon) · **Status:** ga · **Availability:** Grouping layer; providers are staged per Edition by `offerings/*.toon`.

## What it is

Providers are the pluggable implementations behind the SPI contracts — sign-in, secrets, policy, connectors, decoders, geo lookup.

## What this enables

- Customers and integrators can swap an implementation by changing what is on the classpath, without changing the core — [platform architecture](../platform/architecture-platform.md)

## Related hubs

[spi](../spi/index.md) · [oidc](../../module/oidc/index.md) · [secrets](../../module/secrets/index.md) · [connectors](../../module/connectors/index.md)
