---
type: TestsInventory
title: Link analysis storage — tests inventory
item: module.la-storage
description: Generated list of the test classes for module.la-storage, with how to run them.
tags: [tests, module]
---

# Link analysis storage — tests inventory (`module.la-storage`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `la/inspecto-la-storage/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.la-storage`](../../../../../product-map/module/la-storage.toon).

14 test class(es): 14 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl la/inspecto-la-storage
mvn -o test -pl la/inspecto-la-storage -Dtest=BucketFunctionTest,IndexAppendTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `BucketFunctionTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/BucketFunctionTest.java` | The golden proof that {@link BucketFunction#sql} (what DuckDB computes when the builder writes the index) and |
| `IndexAppendTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexAppendTest.java` | D-3 step 8 - incremental append, compaction and the plan that decides between them. The corpus is a seeded random graph (60 |
| `IndexBuilderTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexBuilderTest.java` | D-3 step 3. Every corpus is generated at test time into a TempDir; nothing is committed. |
| `IndexBuildServiceHostTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexBuildServiceHostTest.java` | {@code LA-CONFIG-LIMITS-1} P4 on the build service: the install-wide resources ({@link Host}: memory limit, bucket sizing) |
| `IndexBuildServiceTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexBuildServiceTest.java` | The index-build lifecycle (D-3 step 4). The state-machine tests drive a controllable fake builder through the package-private |
| `IndexFullPinTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexFullPinTest.java` | LA-DAILY-INGEST-1, the live Job-versus-build race: a day that LANDS while a FULL build runs. The relation of a plain store |
| `IndexLinkStatsTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexLinkStatsTest.java` | Entity profile D-EP4: the link statistics a build records in its manifest ({@code kindEdges}, {@code typeNodes}, |
| `IndexManifestTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexManifestTest.java` |  |
| `IndexNodesFoldTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexNodesFoldTest.java` | LA-INDEX-SCALE-MEASURE-1: the node table is folded ONE BUCKET AT A TIME from the written {@code out} and {@code in} |
| `IndexPinsTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexPinsTest.java` | D7-2 - pinned index versions survive gc and pool eviction; pins expire after 30 days; an unreadable pin file fails closed. |
| `IndexReaderPoolTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexReaderPoolTest.java` | LA-INDEX-READER-POOL-1 - sealed readers are reused per version directory and dropped when a newer version is borrowed. |
| `IndexReaderTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexReaderTest.java` | D-3 step 5 - the sealed per-request reader. The index is generated at test time; nothing is committed. |
| `IndexStoreTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexStoreTest.java` |  |
| `SqlGraphEngineParityTest` | `com.gamma.la.storage` | unit | `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/SqlGraphEngineParityTest.java` | D-3 step 7 - the index engine answers what the in-memory engine answers over the SAME graph. The graph is a VALUES relation with |
