---
type: TestsInventory
title: Config model and codecs — tests inventory
item: module.config
description: Generated list of the test classes for module.config, with how to run them.
tags: [tests, module]
---

# Config model and codecs — tests inventory (`module.config`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-config/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.config`](../../../../../product-map/module/config.toon).

22 test class(es): 22 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-config
mvn -o test -pl platform/inspecto-config -Dtest=ConfigCodecTest,ConfigLoaderTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ConfigCodecTest` | `com.gamma.config.io` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/io/ConfigCodecTest.java` | Tests for the canonical {@code .toon} codec (P1): the re-encoded form is comment-free and decodes back |
| `ConfigLoaderTest` | `com.gamma.config.io` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/io/ConfigLoaderTest.java` | Tests for the pure decode→validate pipeline (P1): decoding a draft from memory (no temp files), |
| `NoHandRolledToonFileDecodeContractTest` | `com.gamma.config.io` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/io/NoHandRolledToonFileDecodeContractTest.java` | {@code CONFIGCODEC-CALLERS-NO-FILE-NAME-1} — no production code decodes a {@code .toon} FILE by hand. |
| `ConfigSafetyValidatorTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/ConfigSafetyValidatorTest.java` | Adversarial tests for the hard-fail config safety gate (R6). This is the security core of M5, so |
| `DataPathResolutionTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/DataPathResolutionTest.java` | A config's relative DATA path resolves under its <b>Space directory</b>, never the process working directory |
| `DataRefTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/DataRefTest.java` | Pins the data-ref verdict that {@code DatasetRelation} and {@code ExpectationEvaluator} share. |
| `DemoBackupJobPathsResolveUnderTheSpaceRootTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/DemoBackupJobPathsResolveUnderTheSpaceRootTest.java` | Pins the three committed {@code spaces/demo} backup job path values against the rule their runtime |
| `DiscoveredRootsTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/DiscoveredRootsTest.java` | Pins a Space's allowed roots (PATH-2 tier 3, narrowed by {@code CROSS-SPACE-JAIL-1}): operator-declared |
| `EgressGateTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/EgressGateTest.java` | Slice S4 of {@code policy-narrowing-design.md}: the verdicts of {@link EgressGate} over an explicit tier. |
| `JobPathResolutionTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/JobPathResolutionTest.java` | A job's relative path resolves against the <b>Space config root</b>, not the process working directory |
| `PathJailObjectStoreTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/PathJailObjectStoreTest.java` | {@link PathJail#requireObjectStore} — the object-store containment rule (SCALEOUT-OBJSTORE-DATABASE-DIR-1). |
| `PathJailTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/PathJailTest.java` | Adversarial coverage for the shared containment primitive. |
| `RetentionSweepJobPathsResolveUnderTheSpaceRootTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/RetentionSweepJobPathsResolveUnderTheSpaceRootTest.java` | Pins the three committed {@code task: cleanup} job path values against the rule their runtime |
| `SafetyPolicyFilesTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/SafetyPolicyFilesTest.java` | Slice S2 of {@code policy-narrowing-design.md}: strict loading, the unreadable states (§5.1), the fold wiring. |
| `SafetyPolicyPathsTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/SafetyPolicyPathsTest.java` | Slice S3 of {@code policy-narrowing-design.md}: {@code allow.roots} / {@code deny.roots} reach the path jail, |
| `SafetyPolicyTierTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/SafetyPolicyTierTest.java` | The Safety Policy fold (design §3): the worked examples E1–E12, the boundary matchers (§3.3), and the |
| `SchemaCompatibilityTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/SchemaCompatibilityTest.java` | {@link SchemaCompatibility} — the BACKWARD save-gate diff (ELT amendment §3.4.2, D-10). |
| `StateAdvancePlanTimeTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/StateAdvancePlanTimeTest.java` | Policy-narrowing D9 (S6 plan-time half): {@code permit.advance_state: false} refuses a config that implies an advance. |
| `StateGateTest` | `com.gamma.config.safety` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/safety/StateGateTest.java` | Slice S6 of {@code policy-narrowing-design.md}: {@link StateGate} over a run's pinned tier. |
| `AcceptedConfigKeysTest` | `com.gamma.config.spec` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/spec/AcceptedConfigKeysTest.java` | The dead-property census (`DUCKLE-C3-DEAD-PROPERTY-1`): a block no component reads must produce a |
| `ConfigJsonSchemaTest` | `com.gamma.config.spec` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/spec/ConfigJsonSchemaTest.java` | Unit tests for the {@link ConfigSpec} → JSON Schema projection (AGT-6a plan D9): dotted paths nest, |
| `ConfigSpecsTest` | `com.gamma.config.spec` | unit | `platform/inspecto-config/src/test/java/com/gamma/config/spec/ConfigSpecsTest.java` | Unit tests for the declarative config specs (P0): every authored {@link ConfigSpec} resolves, the |
