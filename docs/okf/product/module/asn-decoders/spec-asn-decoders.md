---
type: Reference
title: ASN.1 decoders — implementation spec
description: Packages, invariants and failure handling of the asn-decoders reactor, for developers changing it.
resource: providers/asn-parser/asn-decoders/
tags: [module, asn-decoders, asn1, spec]
timestamp: 2026-10-19T00:00:00Z
item: module.asn-decoders
audience: internal
status: current
verified: 2026-11-19 last-edit
code: providers/asn-parser/asn-decoders/asn-facade
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
AddressString, TIMESTAMP and the vendor named types listed in [architecture-decoder-modules.md](architecture-decoder-modules.md).

## Framing

`Framing` is declarative: a file header length, a per-record header (skip-only, e.g. Huawei 50 / 4), padding
bytes skipped before a tag (Ericsson zero-fill), and a trailer. Records stay delimited by their own BER length.

## Failure handling

- A value whose declared length exceeds the cap (default 64 MiB, `BerReader.DEFAULT_MAX_VALUE_BYTES`) fails its
  record with a `ParseError` naming the tag, the length and the cap, before any array is sized.
- `SKIP_RECORD` reports through the `ErrorListener` and moves on; `STOP_FILE` stops the reader. The Inspecto
  ingester uses `STOP_FILE` and quarantines the file ([telecom-asn1 spec](../telecom-asn1/spec-telecom-asn1.md)).

## Legacy tree

`providers/asn-parser/src/main/java` (packages `com.gamma.skybase.decoder.asn2` / `asn3`, `transformer2`) is
compiled only by `legacy-code` for parity; it is kept by decision (BACKLOG `LEGACY-ASN-SRC-TREE-UNBUILT-1`).

## References

- `providers/asn-parser/docs/REDESIGN.md` · `providers/asn-parser/docs/ENCODING_RULES_SEAM.md`
- [api](api-asn-decoders.md) · [architecture](architecture-asn-decoders.md)

## DATA-GOV-1 decision (2026-09-06)

🔴 **Status 2026-09-15 (operator): NOT DONE.** Neither the encrypted out-of-band archive nor the fetch script exists; the corpus has not moved. Recorded here so the section stops reading as decided-and-handled — the BACKLOG §2 gate closes only when this section carries the dated archive location and the fetch-script path, and today it carries neither. ⚠ Consequence: `asn-parser`'s corpus tests stay opt-in and data-gated, so that lane (compiled by `legacy-code/pom.xml`, not dead) is tested only against synthetic input; and real carrier data without an archive is a data-governance finding in its own right, independent of any other compliance work.

The real carrier corpus (~57 MB, two carriers, only in working trees) moves to an **encrypted out-of-band
archive on company storage, fetched by script**, access held by the data-agreement owner; the parity harness runs
wherever the archive is provisioned. A **small synthetic subset is committed for CI smoke** — a complement, not a
replacement: every parity defect so far came from real files. **The synthetic subset SHIPPED 2026-09-06**
(`providers/asn-parser/corpus-synthetic/` + `SyntheticCorpusTest` in asn-golden, always-on: two cases — back-to-back records
with OPTIONAL/SEQUENCE OF/CHOICE, and the Huawei file shape with a 50-byte header, 4-byte record headers and 0x00
fill; BER as hex text so the `*.ber` ban stands; regenerate with `-Dasn.synthetic.write=true`). It pinned one
reader rule on the way: fill bytes are skipped BEFORE a record header, so a header must not start with 0x00/0xFF.
Git LFS is refused unless the data agreement permits
third-party hosting. ⚠ Never force-push or reset master to fix this. The archive itself is an org action
(BACKLOG §2); the synthetic subset is BACKLOG §3 `DATA-GOV-SYNTH`.
