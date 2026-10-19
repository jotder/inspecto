---
type: Feature
title: "Exchange (multi-entity sharing) — features"
description: "The Exchange is the installation-scope surface through which one Space shares a Dataset, a Widget, a saved Link Analysis view or a Signal type with another Space - the multi-entity add-on (group companies, a regulator and its reporting entities)."
tags: [exchange, exchange, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Exchange (multi-entity sharing) — features

**Availability:** Offering: Standard and above (`multiEntity` add-on, module `exchange`).

## What it does

The Exchange is the installation-scope surface through which one [Space](../config/architecture-spaces.md) shares a Dataset, a Widget, a
saved Link Analysis view or a Signal type with another Space - the multi-entity add-on (group companies, a
regulator and its reporting entities). Sharing is per item, opt-in and grant-mediated: nothing is discoverable
unless its owner **offers** it, and nothing is usable without an **active Share Grant**.

Module: `features/inspecto-exchange` (manifest id `exchange`, background work `exchange-snapshot-refresh`).
Concept: [Exchange sharing](spec.md).

## Where it fits

An add-on that lives under `spaces/_shared/` (reserved, never itself a Space). It needs multi-Space mode
(`-Dspaces.root`); with a single Space there is no one to share with, the Exchange is disabled and every route
answers 409. `ExchangeBootHook` installs the host seams at boot; without the module `shared/...` refs fail closed.

More: [screens](user-guide-exchange.md) · [API](api-exchange.md) · [architecture](architecture-overview.md).
