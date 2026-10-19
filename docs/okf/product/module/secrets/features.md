---
type: Feature
title: "File-keystore secrets provider — features"
description: "The file-keystore secrets provider resolves `${FILE:…}` and `${KEYSTORE:…}` references in configuration."
tags: [secrets, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# File-keystore secrets provider — features

**Product Map Item:** [`module.secrets`](../../../../../product-map/module/secrets.toon) · **Status:** ga · **Availability:** Optional module, staged from Standard and every higher Edition (`offerings/standard.toon`).

## What it is

The file-keystore secrets provider resolves `${FILE:…}` and `${KEYSTORE:…}` references in configuration. Credentials stay out of config files.

## What this enables

- Customers keep connection passwords in a file or keystore their security team controls, never in plain config — [Identity and Access](../access/features-access.md)

## Related hubs

[access](../../module/access/index.md) · [auth-spi](../../module/auth-spi/index.md) · [connectors](../../module/connectors/index.md)
