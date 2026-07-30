---
type: Concept
title: Parser plugins (the self-describing Parser framework)
description: One SPI unifying the two transparent parse engines — DuckDB-native built-ins and custom Java decoders — served to the UI with grammar schemas and a tree-capable preview.
resource: inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [concept, engine, parsing, spi, plugins]
timestamp: 2026-07-30T00:00:00Z
---

# Parser plugins — the self-describing Parser framework

> The runtime parse model (three frontends / one DuckDB backend) is
> [parsing-grammar.md](parsing-grammar.md); the ingest SPI itself is [plugins.md](plugins.md).
> THIS concept is the self-description layer over both: discovery, served grammar schemas, preview.

The E of ELT: any file loads into one or more **Tables** (the segment → partitioned CSV/Parquet
model, unchanged — see [plugins.md](plugins.md)). A file type is parsed by providing a **Grammar**
(GLOSSARY §6). Internally there are two **fully transparent** parser engines — DuckDB-native reads
for the built-ins, custom Java decoders for everything DuckDB can't consume — unified behind one
self-describing SPI so a new format can be *deployed and configured as a plugin* with **zero UI or
control-plane change**.

## The SPI (`com.gamma.parse.ParserPlugin`, engine, `@PublicApi 5.3.0`)

| Method | The authoring question it answers |
|---|---|
| `id()` / `label()` / `hierarchical()` | what am I called; are my records tree-shaped? |
| `grammarSchema()` → `List<FieldSpec>` | which options do I take? (the SAME `FieldSpec` vocabulary `GET /config/spec/{type}` serves — dotted paths relative to the grammar map) |
| `suggest(byte[])` | clues sniffed from a sample (offered as chips, never auto-applied) |
| `preview(byte[], grammar)` → `ParseResult` | parse part of the contents: a `Table` (columns/rows) or a `Tree` (record forest) |
| `ingesterClass()` → `Optional<String>` | the FQCN of my `StreamingFileIngester` when the format can load to Tables TODAY |

`ParseResult.Tree`'s node shape mirrors the UI's `ParserTreeNode` verbatim, so the control plane
serializes without translation. **Preview and ingest are deliberately separate capabilities**: a
hierarchical parser without an ingester is *preview-only* (`ingestable: false` in the catalog) —
tree-shaped records cannot honestly land in Tables until the flatten configuration exists
(BACKLOG §4 "Parsing (Stage-1)").

## Registry (`com.gamma.parse.Parsers`) + discovery

Built once at class-load: the four built-in adapters (`BuiltinParsers` — delimited / fixedwidth /
json / text_regex, whose `preview` delegates to `ComponentPreview.parsing`, i.e. the exact DuckDB
read specs `DuckDbCsvIngester` runs at ingest) merged with
`ServiceLoader.load(ParserPlugin.class)` (`META-INF/services/com.gamma.parse.ParserPlugin`).
⚠ **Duplicate ids fail startup loudly** — deliberately unlike `PipelineNodeTypes`' override-a-
builtin rule: the built-ins' preview IS the engine that ingests, so an override would let a
preview diverge from production parsing. ⚠ `Parsers.load` must NOT use `Map.copyOf` — it discards
iteration order, and catalog order (built-ins first) is part of the contract.

Reference plugin: `XmlParserPlugin` (engine, registered via the services file) — JDK StAX, DTDs
and external entities disabled outright (XXE), grammar `xml.record_element` (local name or slash
path; blank = the root's direct children) / `namespace_aware` / `encoding` / `max_records`;
`suggest()` proposes the root's repeated child. Preview-only until the flatten DSL.

## Control plane (`ParserRoutes`, both compute-only — no write gate, no capability)

- `GET /parsers` → `[{id, label, hierarchical, ingestable, grammarSchema}]`.
- `POST /parsers/{id}/preview` `{grammar, sample_text | sample_b64}` → the UI's `ParserPreview`
  union `{kind: 'table' | 'tree', …}`. 404 unknown id · 400 missing/oversized sample (text 1MB,
  b64 4MB — binary formats need bytes) · 422 caller errors with the reason. The grammar-shaped
  sibling of `POST /config/preview/parsing`, which stays byte-identical as the draft-true path.

## UI adoption

- **Onboarding Parsing stage**: the file-type toggle appends the served non-builtin parsers; their
  options form renders the served schema via `fieldSpecsToAttributes`
  (`inspecto/component-model/field-spec-mapper.ts` — unknown field shapes are SKIPPED, never
  guessed, the `findingsAttributes` idiom); Test parse hits `/parsers/{id}/preview` and renders
  table or tree; **Save is disabled for plugin types** with an honest note (preview-only until
  flatten; ingestable customs are TOON-authored until the segments editor exists). The plugin
  preview is pane-local — the sample thread's parsed hop stays builtin-only.
- **Pipelines Parser dialog**: runs entirely on the served contract now (catalog + real preview) —
  the old mock-only `/components/grammar/preview`, the 9-type hardcoded `parser-types.ts` catalog
  and the ASN.1 module picker are **gone**; grammar components persist as
  `{parser_type, <nested grammar>}` (old prototype flat-key contents simply render empty forms).
- Mock parity: `parsers.handler` mirrors the catalog and every refusal (pinned in its spec — a
  mock must never be more lenient than the server).

## ASN.1 (the operator's target format) — status

Not served yet, deliberately: `asn-parser/` is a separate Maven project owned by another active
workstream, and it is not plugin-ready (no public facade; `RecordMapper` is package-private; the
decode tuple — grammar/root type/framing — is hardcoded in `GoldenCapture.CASES`, not config;
two conflicting sets of Maven coordinates). The adoption prerequisites are recorded in BACKLOG §4;
when its `ParserPlugin` jar lands, ASN.1 appears in every dropdown with its grammar (schema module
picker as a served field) and tree preview — no UI change.
