---
type: TestsInventory
title: Predictive Analytics — tests inventory
item: module.predictive
description: Generated list of the test classes for module.predictive, with how to run them.
tags: [tests, module]
---

# Predictive Analytics — tests inventory (`module.predictive`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-predictive/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.predictive`](../../../../../product-map/module/predictive.toon).

7 test class(es): 6 unit, 1 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-predictive
mvn -o test -pl features/inspecto-predictive -Dtest=ControlApiSegmentationModelTest,RfmSegmenterTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `ControlApiSegmentationModelTest` | `com.gamma.predictive` | real-HTTP control-plane test | `features/inspecto-predictive/src/test/java/com/gamma/predictive/ControlApiSegmentationModelTest.java` | The {@code segmentation-model} save gate over real HTTP with an ARMED Subject: the module's validator is discovered |
| `RfmSegmenterTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/RfmSegmenterTest.java` | Binning and labelling by hand: quantile edges, the score direction, ties, and recompute from a stored row. |
| `SegmentationKindValidatorTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/SegmentationKindValidatorTest.java` | The {@code segmentation-model} save gates that need the Space, and the reserved {@code customer_segments_} prefix. |
| `SegmentationModelTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/SegmentationModelTest.java` | The {@code segmentation-model} structure: defaults, the label table, and every fail-closed refusal. |
| `SegmentJobTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/SegmentJobTest.java` | The {@code predict.segment} Job through a real {@link JobService} over the synthetic {@link RfmCorpus}: the history |
| `SegmentJobTypeManifestTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/SegmentJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register. |
| `SegmentJobTypeTckTest` | `com.gamma.predictive` | unit | `features/inspecto-predictive/src/test/java/com/gamma/predictive/SegmentJobTypeTckTest.java` | {@link SegmentJobType} against the platform's JobTypeProvider TCK. |
