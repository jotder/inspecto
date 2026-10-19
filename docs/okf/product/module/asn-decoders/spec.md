---
type: Reference
title: ASN.1 decoders — implementation spec
description: Packages, invariants and failure handling of the asn-decoders reactor, for developers changing it.
resource: providers/asn-parser/asn-decoders/
tags: [module, asn-decoders, asn1, spec]
timestamp: 2026-10-19T00:00:00Z
---

# ASN.1 decoders — implementation spec

## Build

- Separate reactor `providers/asn-parser/asn-decoders/pom.xml`, Java release 27 (FFM `Arena` /
  `MemorySegment` for memory-mapped files over 2 GB needs Java 22+). Runtime is JDK-only; tests use JUnit 5.
- Aggregated by the root `pom.xml` (first `<module>`); inherits nothing from `inspecto-parent`.
- Run one sub-module: `mvn -o test -pl providers/asn-parser/asn-decoders/asn-facade` (see
  [tests-inventory](tests-inventory.md)). BACKLOG `TEST-ENV-PG-ASN-UNRUN-1`: these cannot run offline on
  the shared profile today.

## Packages

| Package | Key classes | Invariant |
|---|---|---|
| `com.gamma.asn.core` | `ByteSource`, `HeapSource`, `MappedSource`, `BerReader`, `Tlv`, `Framing`, `RecordReader`, `Strictness`, `RecoveryPolicy`, `TlvDump` | One tag/length codec; indefinite length is handled structurally, never by scanning for `00 00`; offsets are absolute; mmap is unmapped deterministically. |
| `com.gamma.asn.schema` | `Asn1Parser`, `Tokenizer`, `SchemaCompiler`, `CompiledSchema`, `SchemaBinder`, `DecoderRegistry`, `Decoders` | Strict mode fails with line:column; lenient mode records each skip as a warning. `DecoderRegistry` is explicit and immutable per Pipeline. Unknown nodes keep their tag path and hex. |
| `com.gamma.asn.plugin` | `TransformFunctionProvider`, `TransformFunction`, `PluginContext` | Name collisions fail at load. |
| `com.gamma.asn.transform` | `LegacyTransformEngine`, `FunctionRegistry`, `TxConfig`, `CoreFunctions` | Reads the legacy transform JSON shape. |
| `com.gamma.asn.facade` | `Asn1Decoder`, `RecordMapper` | Immutable and thread-safe; a `SchemaBinder` is created per `ByteSource`. |

## Value decoding

The legacy byte-to-text logic is ported verbatim into `Decoders` and dispatched by grammar type name in
`DecoderRegistry.withDefaults()`: uppercase hex fallback, OCTET STRING as ISO-8859-1, TBCD (A-E letters),
AddressString, TIMESTAMP and the vendor named types listed in `providers/asn-parser/asn-decoders/README.md`.

## Framing

`Framing` is declarative: a file header length, a per-record header (skip-only, e.g. Huawei 50 / 4), padding
bytes skipped before a tag (Ericsson zero-fill), and a trailer. Records stay delimited by their own BER length.

## Failure handling

- A value whose declared length exceeds the cap (default 64 MiB, `BerReader.DEFAULT_MAX_VALUE_BYTES`) fails its
  record with a `ParseError` naming the tag, the length and the cap, before any array is sized.
- `SKIP_RECORD` reports through the `ErrorListener` and moves on; `STOP_FILE` stops the reader. The Inspecto
  ingester uses `STOP_FILE` and quarantines the file ([telecom-asn1 spec](../telecom-asn1/spec.md)).

## Legacy tree

`providers/asn-parser/src/main/java` (packages `com.gamma.skybase.decoder.asn2` / `asn3`, `transformer2`) is
compiled only by `legacy-code` for parity; it is kept by decision (BACKLOG `LEGACY-ASN-SRC-TREE-UNBUILT-1`).

## References

- `providers/asn-parser/docs/REDESIGN.md` · `providers/asn-parser/docs/ENCODING_RULES_SEAM.md`
- [api](api.md) · [architecture](architecture.md)
