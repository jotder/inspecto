---
type: Reference
title: Telecom ASN.1 decoder — implementation spec
description: Classes, config parsing, invariants and failure handling of inspecto-telecom-asn1.
resource: providers/inspecto-telecom-asn1/
tags: [module, telecom-asn1, asn1, spec]
timestamp: 2026-10-19T00:00:00Z
---

# Telecom ASN.1 decoder — implementation spec

Package `com.gamma.telecom.asn1` (module-owned since MODULE-REORG-1 P3g). The as-built narrative of the
parser framework, including the ASN.1 history and decisions, is
[engine spec-parser-plugins](../engine/spec-parser-plugins.md); this page is the per-class map.

## Classes

| Class | Responsibility |
|---|---|
| `Asn1ParserPlugin` | `ParserPlugin` id `asn1`, label "ASN.1 — BER/DER encoded records", `hierarchical() = true`. `grammarSchema()` declares the form fields; `preview` returns a `ParseResult.Tree`; `previewRows` returns `ParseResult.Rows` when the block declares `derive` or `children`. |
| `Asn1RecordIngester` | `StreamingFileIngester`; public no-arg constructor for reflective load. Decodes a memory-mapped file, flattens records onto segment schemas, appends `EVENT_TYPE`. |
| `Asn1GrammarSource` | The single grammar resolver and path jail shared by preview and ingest. |

## Config parsing

- Preview keys live under `asn1.*` (`grammar`, `grammar_file`, `profile_file`, `root_type`, `strictness`,
  `file_header_length`, `record_header_length`, `max_value_bytes`, `max_records`) — see
  [user-guide](user-guide.md).
- Ingest reads `parsing.plugin.ingester_config` (`grammar_text`, `grammar`, `root_type`, `strictness`, framing
  lengths, `max_value_bytes`, `derive`, `children` and their limits) and `parsing.plugin.segments`.
  `frontend: asn1` synthesises this block; `PipelineConfigParser` resolves `grammar` beside the Pipeline
  config into `Schemas.ingesterGrammar()`.

## Invariants

- **Text wins.** Inline module text beats a file reference when both are set.
- **Extension first.** A file ref must end `.asn` / `.asn1`, checked before any disk access, then resolved by
  `PathJail.resolveConfigRef` and jailed once with `PathJail.requireUnderAny(PathJail.allowedRoots())`
  before the readability probe — an escaping ref is refused, never reported "not readable".
- **Preview base.** A relative ref resolves beside the Pipeline's config directory when the caller sends one,
  else against `SpaceConfigRoot.current()`.
- **Structural mode is preview-only.** With no grammar, preview dumps the raw TLV forest labelled by tag;
  `root_type` without a grammar is refused. Ingest always needs a grammar and `root_type`.
- **Bounds.** Preview materialises at most `MAX_RECORDS` = 1000 records (default 50); `previewRows` keeps
  200 rows per segment but counts all; primitive values are rendered to 32 bytes.
- **Selectors are dotted paths to leaves.** A selector resolving to a container yields NULL, never a
  stringified subtree; a repeated field gets its own (child) segment.
- **Segment match.** The decoded record's name (for a union root, the matched alternative) selects the
  segment; an undeclared name is counted as junk and skipped.

## Failure handling

| Condition | Result |
|---|---|
| Bad grammar | `IllegalArgumentException("invalid ASN.1 grammar …")` (preview 4xx; ingest fails the file) |
| Any record parse error during ingest (`RecoveryPolicy.STOP_FILE`) | file fails → `QUARANTINED_UNREADABLE` (no partial load) |
| Zero records emitted | `QUARANTINED_MISMATCH` |
| `derive` list over `max_list_elements`, or children over `max_child_rows_per_record` | `QUARANTINED_UNREADABLE` |
| Value larger than `max_value_bytes` | record fails before allocation |

## Tests

`providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/` — plugin, TCK, rows, registration,
ingester (plain, derive, child) and step-kind manifest tests; see [tests-inventory](tests-inventory.md).

## References

- [architecture](architecture.md) · [asn-decoders api](../asn-decoders/api.md)
- [engine spec-parsing-options](../engine/spec-parsing-options.md)
