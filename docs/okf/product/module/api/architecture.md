---
type: Feature
title: "Public API annotations — architecture"
description: "Where inspecto-api sits: a dependency-free foundation module that every platform module and the auth SPI depend on to mark their stable surface."
tags: [api, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# Public API annotations — architecture

`platform/inspecto-api` is a **foundation** module with no dependencies of its own, so any module can depend on
it without pulling anything else in. Its [pom](../../../../../platform/inspecto-api/pom.xml) is depended on by
the platform modules (access, acquire, config, engine, entity-store, etl, event, sql, util, workflow), by
`spi/inspecto-auth-spi`, and by the `inspecto` application module.

```
inspecto-api  (one annotation, no deps)
   ^
   |-- platform/* modules, spi/inspecto-auth-spi, inspecto
```

Design choices:

- **Annotation only, `CLASS` retention.** Marking costs nothing at runtime and needs no reflection.
- **Intent, not a gate.** Nothing enforces the marker in the build; whether a change is breaking is decided
  against the newest released tag, as [plan-api-stability](plan-api-stability.md) describes.
