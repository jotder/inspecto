---
type: TestsInventory
title: Authentication and capability contract — tests inventory
item: module.auth-spi
description: Generated list of the test classes for module.auth-spi, with how to run them.
tags: [tests, module]
---

# Authentication and capability contract — tests inventory (`module.auth-spi`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `spi/inspecto-auth-spi/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.auth-spi`](../../../../../product-map/module/auth-spi.toon).

3 test class(es): 3 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl spi/inspecto-auth-spi
mvn -o test -pl spi/inspecto-auth-spi -Dtest=SecretResolverTest,AuthTckSelfTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `SecretResolverTest` | `com.gamma.auth.secrets` | unit | `spi/inspecto-auth-spi/src/test/java/com/gamma/auth/secrets/SecretResolverTest.java` | Connection-profile secrets are references, never values: resolution by scope + literal passthrough. |
| `AuthTckSelfTest` | `com.gamma.control.testkit` | unit | `spi/inspecto-auth-spi/src/test/java/com/gamma/control/testkit/AuthTckSelfTest.java` | Proves the Authenticator and TokenRelay TCKs can fail (MODULE-REORG-1 P5b): each assertion runs against a deliberately |
| `PublicPathsTest` | `com.gamma.spi.auth` | unit | `spi/inspecto-auth-spi/src/test/java/com/gamma/spi/auth/PublicPathsTest.java` |  |
