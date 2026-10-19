---
type: TestsInventory
title: Telecom ASN.1 decoder — tests inventory
item: module.telecom-asn1
description: Generated list of the test classes for module.telecom-asn1, with how to run them.
tags: [tests, module]
---

# Telecom ASN.1 decoder — tests inventory (`module.telecom-asn1`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/inspecto-telecom-asn1/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.telecom-asn1`](../../../../../product-map/module/telecom-asn1.toon).

10 test class(es): 10 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/inspecto-telecom-asn1
mvn -o test -pl providers/inspecto-telecom-asn1 -Dtest=DemoAsn1CorpusIngestTest,ProcessorCatalogPackTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `DemoAsn1CorpusIngestTest` | `com.gamma.inspector` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/inspector/DemoAsn1CorpusIngestTest.java` | The ASN.1 half of {@link DemoCorpusIngestTest} (MODULE-REORG-1 P7): the {@code spaces/demo} msc_cdr demos and |
| `ProcessorCatalogPackTest` | `com.gamma.pipeline` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/pipeline/ProcessorCatalogPackTest.java` | With THIS module on the classpath the Step Processor catalog's {@code parser.asn1.ber} is the delivered, addable |
| `Asn1ParserPluginRowsTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1ParserPluginRowsTest.java` | PARSING-PREVIEW-DERIVED-1: {@link Asn1ParserPlugin#previewRows} builds the derived columns and the exploded child rows |
| `Asn1ParserPluginTckTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1ParserPluginTckTest.java` | {@link Asn1ParserPlugin} against the platform's ParserPlugin TCK (MODULE-REORG-1 P5b). |
| `Asn1ParserPluginTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1ParserPluginTest.java` |  |
| `Asn1PluginRegistrationTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1PluginRegistrationTest.java` | The ASN.1 plugin is discovered through the engine's {@code ParserPlugin} ServiceLoader seam from THIS module's |
| `Asn1RecordIngesterChildTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1RecordIngesterChildTest.java` | Parsing D9 slice 2 end to end: {@code children} (explode) over the tracked SYNTHETIC corpus case |
| `Asn1RecordIngesterDeriveTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1RecordIngesterDeriveTest.java` | Parsing D9 slice 1 end to end: {@code derive} over the tracked SYNTHETIC corpus case |
| `Asn1RecordIngesterTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1RecordIngesterTest.java` | Unit tests for {@link Asn1RecordIngester} — the ASN.1 tree onto segment schemas. |
| `Asn1StepKindManifestTest` | `com.gamma.telecom.asn1` | unit | `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1StepKindManifestTest.java` | MODULE-REORG-P4-1: this module's {@code provides.stepKinds} equals the catalog processors whose {@code Pack} marker |
