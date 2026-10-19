---
type: Concept
title: Telecom ASN.1 decoder — architecture
description: How the optional inspecto-telecom-asn1 provider plugs the ASN.1 BER parser into the engine's parser family, and what happens when it is absent.
resource: providers/inspecto-telecom-asn1/
tags: [module, telecom-asn1, asn1, parser, spi, editions]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — architecture

`inspecto-telecom-asn1` (module id `telecom-asn1`, `offeringRole: provider`) is the **Telecom industry pack**
parser. It adapts the [asn-decoders](../asn-decoders/index.md) library to the engine's `ParserPlugin` seam, so
BER/DER/CER-encoded CDR files preview in the parse drawer and load into Tables like any other format.

## Plug-in points

| Seam | How it is wired |
|---|---|
| `com.gamma.parse.ParserPlugin` | `Asn1ParserPlugin` listed in `providers/inspecto-telecom-asn1/src/main/resources/META-INF/services/com.gamma.parse.ParserPlugin`; discovered by `Parsers` (`platform/inspecto-engine/src/main/java/com/gamma/parse/Parsers.java`) through `ServiceLoader` at boot. Parser id `asn1`, hierarchical. |
| Ingester | `ParserPlugin.ingesterClass()` names `com.gamma.telecom.asn1.Asn1RecordIngester`, a `StreamingFileIngester` loaded reflectively by the `parsing.plugin` machinery. |
| Module manifest | `providers/inspecto-telecom-asn1/src/main/resources/META-INF/inspecto/module.toon`: `provides.contracts: ParserPlugin`, `provides.stepKinds: parser.asn1.ber`, `bindingTime: boot`. |

## Request paths

```
Parse drawer ──POST /parsers/asn1/preview──▶ Asn1ParserPlugin.preview / previewRows
                                               ├─ no grammar  → structural TLV dump (asn-core RecordReader)
                                               └─ grammar     → Asn1Decoder.compile → decode (SKIP_RECORD)
Pipeline run (frontend: asn1) ──▶ Asn1RecordIngester.ingest
                                   └─ Asn1Decoder.decode (STOP_FILE) → flatten onto segment schemas → Tables
```

- **One grammar resolver.** Preview and ingest both resolve the grammar through `Asn1GrammarSource`
  (inline text wins over a `.asn` file reference; the file is path-jailed), so a grammar that previews is
  the grammar that ingests.
- **One row path.** `previewRows` runs the ingester's own `emitRecords` into a collecting sink, so derived
  columns and child rows shown in the drawer are what ingest writes.
- **Decode Profiles.** `asn1.profile_file` overlays a vendor profile through `com.gamma.etl.DecodeProfile`
  for both preview and load.

## Absent module

When the jar is not on the classpath (Basic), `Parsers` has no `asn1`; an `asn1` ingest fails with the
`PluginIngesters.notLoaded` message naming the module, and a Pipeline using `parser.asn1.ber` is listed
`hosted:false` with `missingModule` and a reason (`docs/okf/product/layer/platform/architecture-module-taxonomy.md`).
The Pipeline itself still loads; only running it needs the decoder.

## Editions

Default reactor module (root `pom.xml`, so its tests always run), but staged into bundles **from Standard up**
only (`tools/bundle-modules.mjs` `from: 'standard'`; `offerings/standard.toon` add-on `telecomIndustryPack`).

## References

- [spec](spec.md) · [admin-guide](admin-guide.md) · [user-guide](user-guide.md) · [plan](plan.md)
- [engine spec-parser-plugins](../engine/spec-parser-plugins.md) — the parser-plugin framework, as built
- [telecomIndustryPack](../../addon/telecomIndustryPack/index.md)
