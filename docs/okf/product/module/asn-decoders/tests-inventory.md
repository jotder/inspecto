---
type: TestsInventory
title: ASN.1 decoders — tests inventory
item: module.asn-decoders
description: Generated list of the test classes for module.asn-decoders, with how to run them.
tags: [tests, module]
---

# ASN.1 decoders — tests inventory (`module.asn-decoders`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `providers/asn-parser/asn-decoders/asn-facade/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.asn-decoders`](../../../../../product-map/module/asn-decoders.toon).

1 test class(es): 1 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl providers/asn-parser/asn-decoders/asn-facade
mvn -o test -pl providers/asn-parser/asn-decoders/asn-facade -Dtest=Asn1DecoderTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `Asn1DecoderTest` | `com.gamma.asn.facade` | unit | `providers/asn-parser/asn-decoders/asn-facade/src/test/java/com/gamma/asn/facade/Asn1DecoderTest.java` |  |
