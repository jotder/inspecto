---
type: TestsInventory
title: Demo authenticator — tests inventory
item: module.demo-auth
description: Generated list of the test classes for module.demo-auth, with how to run them.
tags: [tests, module]
---

# Demo authenticator — tests inventory (`module.demo-auth`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-demo-auth/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.demo-auth`](../../../../../product-map/module/demo-auth.toon).

6 test class(es): 4 unit, 2 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-demo-auth
mvn -o test -pl providers/inspecto-demo-auth -Dtest=DemoAuthenticatorPrincipalsTest,DemoAuthenticatorTckTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `DemoAuthenticatorPrincipalsTest` | `com.gamma.demoauth` | unit | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoAuthenticatorPrincipalsTest.java` | {@code principals} lists exactly the identities {@code authenticate} accepts: the bound Space's table plus the relay's union. |
| `DemoAuthenticatorTckTest` | `com.gamma.demoauth` | unit | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoAuthenticatorTckTest.java` | {@link DemoAuthenticator} against the platform's Authenticator TCK (MODULE-REORG-1 P5b); loopback bind is its load-time gate. |
| `DemoAuthHttpTest` | `com.gamma.demoauth` | real-HTTP control-plane test | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoAuthHttpTest.java` | {@code DEMO-AUTH-1} over real HTTP, with the module's own ServiceLoader registrations on the classpath: the picker, |
| `DemoRosterErrorsHttpTest` | `com.gamma.demoauth` | real-HTTP control-plane test | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoRosterErrorsHttpTest.java` | Two demo failure modes the sign-in page must explain, over real HTTP: a roster conflict, and no Space attached. |
| `DemoTokenRelayTckTest` | `com.gamma.demoauth` | unit | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoTokenRelayTckTest.java` | {@link DemoTokenRelay} against the platform's TokenRelay TCK (MODULE-REORG-1 P5b); loopback bind is its load-time gate. |
| `DemoUsersConflictTest` | `com.gamma.demoauth` | unit | `providers/inspecto-demo-auth/src/test/java/com/gamma/demoauth/DemoUsersConflictTest.java` | LIVEFIX2 #3: a Demo User defined differently in two Spaces is refused with BOTH Spaces named. |
