---
type: TestsInventory
title: File-keystore secrets provider — tests inventory
item: module.secrets
description: Generated list of the test classes for module.secrets, with how to run them.
tags: [tests, module]
---

# File-keystore secrets provider — tests inventory (`module.secrets`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-secrets/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.secrets`](../../../../../product-map/module/secrets.toon).

1 test class(es): 1 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-secrets
mvn -o test -pl providers/inspecto-secrets -Dtest=FileKeystoreSecretsProviderTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `FileKeystoreSecretsProviderTest` | `com.gamma.secrets` | unit | `providers/inspecto-secrets/src/test/java/com/gamma/secrets/FileKeystoreSecretsProviderTest.java` | SEC-07: with this edition module on the classpath, {@code ${FILE}} / {@code ${KEYSTORE}} resolve through the |
