---
type: TestsInventory
title: Action Requests — tests inventory
item: module.action-requests
description: Generated list of the test classes for module.action-requests, with how to run them.
tags: [tests, module]
---

# Action Requests — tests inventory (`module.action-requests`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-action-requests/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.action-requests`](../../../../../product-map/module/action-requests.toon).

8 test class(es): 6 unit, 2 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-action-requests
mvn -o test -pl features/inspecto-action-requests -Dtest=ActionDispatcherTest,ActionRequestApproverCheckTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ActionDispatcherTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ActionDispatcherTest.java` | {@link ActionDispatcher}'s attempt loop against a real in-process {@link HttpServer}: retries under one |
| `ActionRequestApproverCheckTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ActionRequestApproverCheckTest.java` | MODULE-REORG-P7 (Action Requests) - the record-aware reading (an Action Request's author and co-authors are its makers; a record failing its integrity check reads unknown) over the base {@link ApproverCheck}. Pins the four outcomes of the approver-eligibility check BEFORE it moves |
| `ActionRequestRoutesContractTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ActionRequestRoutesContractTest.java` | {@link ActionRequestRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P7): every mutating route is capability-gated. |
| `ActionRequestRoutesManifestParityTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ActionRequestRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule} |
| `ActionRequestsTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ActionRequestsTest.java` | The Action Request store ({@code ASSURE-ACTION-REQUESTS-1}): the signed round trip, tamper detection with the |
| `ControlApiActionRequestsTest` | `com.gamma.actionrequests` | real-HTTP control-plane test | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ControlApiActionRequestsTest.java` | Action Requests over real HTTP ({@code ASSURE-ACTION-REQUESTS-1}): every create / decide / retry gate WITH an |
| `ControlApiSpaceBundleActionRequestsTest` | `com.gamma.actionrequests` | real-HTTP control-plane test | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/ControlApiSpaceBundleActionRequestsTest.java` | {@code ASSURE-ACTION-REQUESTS-RESIDUALS-1} (2), over real HTTP with an ARMED Authenticator: a new Space created from |
| `InvokeApiConsequenceContractTest` | `com.gamma.actionrequests` | unit | `features/inspecto-action-requests/src/test/java/com/gamma/actionrequests/InvokeApiConsequenceContractTest.java` | The module contributes {@code invoke-api} through the ServiceLoader consequence registry and the manifest declares it. |
