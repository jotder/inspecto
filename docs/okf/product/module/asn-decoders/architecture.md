---
type: Concept
title: ASN.1 decoders — architecture
description: How the separate asn-decoders reactor is layered, how it reaches the Inspecto build, and which Inspecto module consumes it.
resource: providers/asn-parser/asn-decoders/
tags: [module, asn-decoders, asn1, telecom, architecture]
timestamp: 2026-10-19T00:00:00Z
---

# ASN.1 decoders — architecture

`module.asn-decoders` is a JDK-only ASN.1 BER/DER decoding library. It is **not** an Inspecto plugin by
itself: it has no `META-INF/inspecto/module.toon` and registers nothing with the engine. The engine-facing
plugin is [telecom-asn1](../telecom-asn1/index.md), which wraps this library's public facade.

## A separate reactor, aggregated

`providers/asn-parser/asn-decoders/pom.xml` is its own Maven reactor (parent `com.gamma.asn:asn-decoders`).
The root `pom.xml` lists it as the **first** `<module>` purely so that `com.gamma.asn:asn-facade:0.1.0-SNAPSHOT`
is built before anything that depends on it; the tree inherits nothing from `inspecto-parent`
(aggregation only). Its corpus-backed tests skip when the operator CDR corpus is absent (BACKLOG `DATA-GOV-1`).

## Layers (sub-modules, in build order)

| Sub-module | Role |
|---|---|
| `asn-core` | Byte sources (heap and memory-mapped), the `Tlv` model, the single tag/length codec `BerReader`, declarative `Framing` (file header, per-record header, padding), `RecordReader` with `SKIP_RECORD` / `STOP_FILE` recovery, `Strictness` |
| `asn-schema` | X.680 tokenizer and parser (`Asn1Parser`), `SchemaCompiler` (IMPLICIT/EXPLICIT/AUTOMATIC tagging), `SchemaBinder` (TLV to a named tree), `DecoderRegistry` with the universal and telecom value decoders (TBCD, AddressString, IP address, PLMN-Id) |
| `asn-plugin-api` | The `TransformFunctionProvider` SPI (see [api](api.md)) |
| `asn-transform` | The legacy-compatible transform engine (`LegacyTransformEngine`, `FunctionRegistry`) |
| `asn-plugin-vendors` | Vendor transform functions, registered through `META-INF/services` |
| `asn-facade` | The public bytes-to-records API: `Asn1Decoder` and `RecordMapper` |
| `legacy-code` | Compiles the legacy `providers/asn-parser/src/main/java` tree so the golden harness can drive it |
| `asn-golden` | Golden-corpus capture and the parity check between the legacy and new stacks |

The full design rationale is in `providers/asn-parser/docs/REDESIGN.md`; the module list and deliberate
deviations are in `providers/asn-parser/asn-decoders/README.md`.

## How Inspecto uses it

```
Pipeline (frontend: asn1 / parsing.plugin)
   └─ telecom-asn1: Asn1ParserPlugin (preview) / Asn1RecordIngester (ingest)
         └─ asn-facade: Asn1Decoder.compile(grammar, rootType).decode(src, framing, strictness, recovery, ...)
               └─ asn-schema (compile + bind)  └─ asn-core (read TLVs)
```

- Preview uses `RecoveryPolicy.SKIP_RECORD`; ingest uses `STOP_FILE` and fails the whole file into
  quarantine (see [telecom-asn1 architecture](../telecom-asn1/architecture.md)).
- A grammar-less structural dump uses `asn-core`'s `RecordReader` directly, with no schema.

## Editions

The library carries no edition gate of its own; it reaches a bundle only through its consumer
`inspecto-telecom-asn1`, which is staged from Standard up (`tools/bundle-modules.mjs`, `docs/EDITIONS.md`).

## References

- [plan](plan.md) · [spec](spec.md) · [api](api.md) · [features](features.md)
- `providers/asn-parser/docs/MIGRATION_STATUS.md` — what is proven against the corpus
- [engine spec-parser-plugins](../engine/spec-parser-plugins.md) — the parser-plugin seam it sits under
