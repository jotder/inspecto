---
type: Feature
title: "Public API annotations — spec"
description: "The one type in platform/inspecto-api: the @PublicApi annotation, its targets, retention and since() attribute."
tags: [api, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Public API annotations — spec

Module `platform/inspecto-api` holds one source file,
[`PublicApi.java`](../../../../../platform/inspecto-api/src/main/java/com/gamma/api/PublicApi.java), and its
[module.toon](../../../../../platform/inspecto-api/src/main/resources/META-INF/inspecto/module.toon)
(`buildRole: foundation`, `offeringRole: base`, `bindingTime: build`).

## `@com.gamma.api.PublicApi`

| Aspect | Value |
|---|---|
| Targets | `TYPE`, `METHOD`, `CONSTRUCTOR` |
| Retention | `CLASS` — visible to tooling and Javadoc, absent at runtime |
| `@Documented` | yes |
| Attribute | `String since() default ""` — informational; `4.0.0` means "becomes public in 4.0.0", not "published since" |

No runtime code reads the annotation; it marks intent, not exposure. What it promises and the release baseline
are in [plan-api-stability](plan-api-stability.md).
