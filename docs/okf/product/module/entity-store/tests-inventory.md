---
type: TestsInventory
title: Entity store — tests inventory
item: module.entity-store
description: Generated list of the test classes for module.entity-store, with how to run them.
tags: [tests, module]
---

# Entity store — tests inventory (`module.entity-store`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-entity-store/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.entity-store`](../../../../../product-map/module/entity-store.toon).

14 test class(es): 14 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-entity-store
mvn -o test -pl platform/inspecto-entity-store -Dtest=EntityFactLogTest,EntityFactsForTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `EntityFactLogTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/EntityFactLogTest.java` | The identity fact log's chain (LA-17, design §4.2) and the fold over it. The property: a log that does not verify is |
| `EntityFactsForTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/EntityFactsForTest.java` | Test access to the Identity Fact log and its fold from other test packages. |
| `EntityListEntriesTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/EntityListEntriesTest.java` | ASSURE-ENTITY-LISTS-1 (WS-12): range / CIDR entries, expiry and the Parquet sidecar, below HTTP. The properties: |
| `EntityResolutionFoldTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/EntityResolutionFoldTest.java` | LA-17 slice 2 (design §8.1) — the union-find fold over {@code identity.asserted} / {@code identity.retracted} facts. |
| `EntityTypesTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/EntityTypesTest.java` | LA-17 step 2 — the Entity Type normalisers, the validator, and the {@code entity_types} TOON round trip. |
| `FactLogReplaySp4Test` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/FactLogReplaySp4Test.java` | SP4 fast checks: the bulk generator writes a log the REAL chain verification accepts, and the head-cache prototype's contract. |
| `IndustryPackNeutralityTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/IndustryPackNeutralityTest.java` | Data-prep acceptance <b>AC-09</b> (LA-EDITIONS-1 §3): the core holds no word derived from a shipped Industry pack, a |
| `IndustryPacksTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/IndustryPacksTest.java` | The Industry pack format and its fail-closed discovery (LA-EDITIONS-1 §3, LDP-D7). |
| `LaLimitsTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/LaLimitsTest.java` | {@code LA-CONFIG-LIMITS-1} P1: the {@link LaLimit} catalogue, {@link LaLimits} reads and writes, and the raw-map writers. |
| `LegacySpaceFactLogTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/LegacySpaceFactLogTest.java` | SEP-08: moving the fact log from {@code inspecto-geo-link} to this module changed NO on-disk format or path, so a |
| `LinkAnalysisDraftsSettingsTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/LinkAnalysisDraftsSettingsTest.java` | LA-DRAFT-PROMOTE-COST-1: the {@code drafts} block of {@code link-analysis.toon} and its shipped defaults. |
| `LinkAnalysisEmbedSettingsTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/LinkAnalysisEmbedSettingsTest.java` | LA-EMBED-VIEW-1 (operator 2026-10-12 D4): the {@code embed} block of {@code link-analysis.toon} and its shipped defaults. |
| `ListPruningSp4Test` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/ListPruningSp4Test.java` | SP4 correctness (fast): a list member never counts toward degree or budget, whichever SQL form carries the list, over the |
| `NodeDictionaryTest` | `com.gamma.entitystore` | unit | `platform/inspecto-entity-store/src/test/java/com/gamma/entitystore/NodeDictionaryTest.java` | SP5 correctness (fast, small): the DuckDB E.164 macro agrees with the sealed Java {@code e164} normaliser, the |
