---
type: Feature
title: "Spaces — features"
description: "A **Space** is a fully isolated project environment inside one installation: its own config, data, audit trail and DuckDB stores, its own engine instance, and its own module switches."
tags: [config, spaces, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Spaces — features

**Availability:** Offering: every edition (multi-Space hosting is the default: `<install_dir>/spaces`, or `-Dspaces.root`; data can live elsewhere via `-Dspaces.data.root` / `space.toon data_root`).

## What it does

A **Space** is a fully isolated project environment inside one installation: its own config, data, audit
trail and DuckDB stores, its own engine instance, and its own module switches. A **Space Template** is a
reusable blueprint that seeds a new Space (Type → Instance). This page also covers the **Installed →
Enabled → Permitted** gates, because the middle gate is a per-Space setting.

The requirement of record is the [Spaces & tenancy capability spec](plan-spaces.md); the hosting
mechanism is [Multi-Space Hosting](../processor/architecture-multi-space.md); the module gates are defined in
[module taxonomy](../../layer/platform/architecture-module-taxonomy.md).

## Where it fits

| Piece | Code |
|---|---|
| Space registry and lifecycle | `inspecto/src/main/java/com/gamma/service/SpaceManager.java` |
| Per-Space holder (id + root + manifest + engine) | `inspecto/src/main/java/com/gamma/service/SpaceContext.java` |
| Where a Space's files live | `inspecto/src/main/java/com/gamma/service/SpaceRoot.java` |
| Flat-to-Space migration CLI | `inspecto/src/main/java/com/gamma/service/SpaceMigrator.java` |
| Space CRUD routes | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| Enabled gate (routes) | `ControlApi.requireModuleEnabled` in `inspecto/src/main/java/com/gamma/control/ControlApi.java` |
| Enabled gate (settings file) | `inspecto/src/main/java/com/gamma/control/ModuleSettings.java`, `ModuleSettingsRoutes` |
| Enabled gate (Jobs, sweeps, maintenance) | `inspecto/src/main/java/com/gamma/control/ModuleGate.java`, `JobModuleGate` |
| Installed gate (absent-module stubs) | `inspecto/src/main/java/com/gamma/control/AbsentModuleRoutes.java`, `ModulesRoutes` |
| Permitted gate | `ApiContext.withCapability` in `spi/inspecto-http-spi/src/main/java/com/gamma/spi/http/ApiContext.java` |
| Template catalog | `spaces/_templates/` (one directory per template) |

More: [screens](user-guide-spaces.md) · [API](api-spaces.md) · [architecture](architecture-spaces.md).
