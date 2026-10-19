---
type: TestsInventory
title: Exchange (publication and ingestion contracts) — tests inventory
item: module.exchange
description: Generated list of the test classes for module.exchange, with how to run them.
tags: [tests, module]
---

# Exchange (publication and ingestion contracts) — tests inventory (`module.exchange`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-exchange/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.exchange`](../../../../../product-map/module/exchange.toon).

17 test class(es): 8 unit, 9 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-exchange
mvn -o test -pl features/inspecto-exchange -Dtest=ControlApiDecisionRuleSignalOfferTest,ControlApiExchangeOwningSpaceAuthzTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiDecisionRuleSignalOfferTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiDecisionRuleSignalOfferTest.java` | Cross-Space consequence slice 5 ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §6; operator |
| `ControlApiExchangeOwningSpaceAuthzTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeOwningSpaceAuthzTest.java` | The Exchange's capability gates are decided by the role table of the Space that OWNS the thing acted on |
| `ControlApiExchangeSignalDeliveryTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeSignalDeliveryTest.java` | Cross-Space Signal delivery — slice 3 ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §5.5, tests |
| `ControlApiExchangeSignalOfferTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeSignalOfferTest.java` | The Exchange's {@code signal} kind — cross-Space consequence slice 2 |
| `ControlApiExchangeSnapshotTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeSnapshotTest.java` |  |
| `ControlApiExchangeTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeTest.java` |  |
| `ControlApiExchangeViewTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeViewTest.java` |  |
| `ControlApiExchangeWidgetTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/ControlApiExchangeWidgetTest.java` |  |
| `CrossSpaceFraudIncidentAcceptanceTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-exchange/src/test/java/com/gamma/control/CrossSpaceFraudIncidentAcceptanceTest.java` | The cross-Space consequence that was asked for — D10 (operator 2026-09-28), the acceptance test of slice 4 |
| `ExchangeBootHookTest` | `com.gamma.control` | unit | `features/inspecto-exchange/src/test/java/com/gamma/control/ExchangeBootHookTest.java` | The Exchange's host-wide installs moved out of {@code ExchangeRoutes.register} into {@code ExchangeBootHook} |
| `ExchangeRefResolverTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeRefResolverTest.java` | S3 delivery-mode routing through the production {@link ExchangeRefResolver}: a {@code live} grant reads |
| `ExchangeRoutesContractTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeRoutesContractTest.java` | {@link ExchangeRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P5-TCKS): no host, no processor |
| `ExchangeRoutesManifestParityTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes {@code ExchangeRoutes} |
| `ExchangeSnapshotMultiPodTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeSnapshotMultiPodTest.java` | EXCHANGE-SNAPSHOT-MULTIPOD-1: two JVMs sharing a store may publish the same due snapshot at once. Neither |
| `ExchangeSnapshotSweepTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeSnapshotSweepTest.java` | The scheduled snapshot refresh's due rule and outcome recording (SHARE-SNAPSHOT-ON-APPROVE-1). |
| `ExchangeSnapshotTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeSnapshotTest.java` | S2 end-to-end at the unit level: an owner publishes a versioned snapshot, and a consumer resolves |
| `ExchangeTest` | `com.gamma.exchange` | unit | `features/inspecto-exchange/src/test/java/com/gamma/exchange/ExchangeTest.java` | The Exchange ledger + Share Grant lifecycle: offer → request → approve/deny/revoke, all fail-closed. |
