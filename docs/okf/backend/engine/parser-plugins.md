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

Second plugin: `Asn1ParserPlugin` (engine, registered via the same services file) — **the first
hierarchical parser that is `ingestable: true`**, because it names an ingester
(`com.gamma.ingester.Asn1RecordIngester`, below). Wraps the
`asn-facade` module's public `Asn1Decoder`/`RecordMapper` (`asn-parser/asn-decoders/asn-facade`,
depended on as `com.gamma.asn:asn-facade:0.1.0-SNAPSHOT`, installed to the local repo from the
separate `asn-parser/asn-decoders` reactor — not yet resolved from this build, see the coordinate
note below). Grammar: `asn1.grammar` (the ASN.1 module text) / `asn1.root_type` / `asn1.strictness`
(BER/DER/CER) / `asn1.file_header_length` / `asn1.record_header_length` / `asn1.max_records`.
No `suggest()`. Preview-only, same as XML, until the flatten DSL.

**Framing is served, but only the knobs real files vary by.** The two length fields cover every
layout in the parity corpus (file header 0 or 50 bytes; record header absent or 4 bytes, always
`skipOnly` so records stay delimited by their own BER length). 0x00/0xFF inter-record padding is
**unconditional** — the legacy `ASN1Utils.readTag` skips both before every record tag and the
parity harness pins the rewrite to that. ⚠ Consequence: a record header made of those bytes is
eaten as padding before the header is counted. Deliberately NOT served: trailer length and the
length-prefix machinery (`lengthOffset`/`lengthSize`/endianness/`lengthIncludesHeader`) that
`Framing.RecordHeaderSpec` can express — no corpus file uses either, so serving them would be
offering knobs nothing has needed. They stay available in asn-core the moment a real file demands
them.

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
  table or tree. The plugin preview is pane-local — the sample thread's parsed hop stays
  builtin-only.
- **Save gating** is now per-capability, not "plugins can't save": enabled when the selected plugin
  is `ingestable` **and** serves an `ingesterClass`; still disabled (with the honest
  preview-only note) otherwise. XML is the latter, ASN.1 the former.
- **Segments editor** (`segments-editor.component`, in the Parsing pane — deliberately *not* a new
  stage: stages are static arrays with no runtime-conditional precedent, and the editor needs the
  decoded tree directly above it). **Derive from preview** proposes one segment per record type
  with a column per LEAF path; column names are generated through the engine's identifier rule,
  since `Identifiers.validateSchema` makes a violation a hard startup failure. Save writes one
  schema toon per segment via `ConfigService.write('schema', …)` at the Schema stage's convention
  path, *then* patches `parsing.plugin` — in that order, so the pipeline never names a file that
  does not exist yet. Partitions default to the derived `EVENT_TYPE`, because an empty
  `partitions[]` silently sends every row to the `year=1900` sentinel partition.
  ⚠ **Residual:** the editor re-hydrates segment *keys* from a saved config but not their columns
  (those live in the referenced schema toons, which the pane does not read back) — re-editing an
  existing stream needs a re-derive.
  ⚠ Bespoke nested `FormArray` by necessity: `FieldSpec` cannot express "a list of segments, each
  with a list of columns" — `ConfigSpecs.schema()` hits the identical wall and says so.
- **Pipelines Parser dialog**: runs entirely on the served contract now (catalog + real preview) —
  the old mock-only `/components/grammar/preview`, the 9-type hardcoded `parser-types.ts` catalog
  and the ASN.1 module picker are **gone**; grammar components persist as
  `{parser_type, <nested grammar>}` (old prototype flat-key contents simply render empty forms).
- Mock parity: `parsers.handler` mirrors the catalog and every refusal (pinned in its spec — a
  mock must never be more lenient than the server).

## ASN.1 (the operator's target format) — status

**Served as of 2026-07-31** via `Asn1ParserPlugin` (above) — it appears in the Onboarding Parsing
stage's toggle and the Pipelines Parser dialog with zero UI change, exactly as designed. The plugin
sits on the new `asn-facade` API and serves grammar + framing; it is preview-only (no
`ingesterClass()`).

### Loading to Tables — `Asn1RecordIngester` (2026-07-31)

ASN.1 loads through the **existing `parsing.plugin` machinery**, not a new path: `frontend: plugin`
+ `plugin.ingester` + `plugin.segments` + `plugin.ingester_config`. ⚠ `asn1` is a *catalog id for
preview/authoring*, *never* a `parsing.frontend` value — `PipelineConfigParser`'s `FRONTENDS` set is
`{delimited, fixedwidth, fixed_width, json, text_regex, plugin}` and always will be.

`com.gamma.ingester.Asn1RecordIngester` (engine, alongside `TypedRecordIngester`):

- **Segment key = the decoded record's own name.** For the corpus's union-style vendor grammars
  (a SET/SEQUENCE whose tagged components are the record types) `SchemaBinder.bind` names a record
  by the *matched alternative* — e.g. `moCallRecord` — so that name is the segment key. A
  single-type grammar yields the root type name. An undeclared record type is `sink.junk()`,
  mirroring how `TypedRecordIngester` treats an unknown type prefix.
- **`raw.fields[].selector` is a DOTTED PATH**, not a positional index — the one real divergence
  from the text ingesters. `party.number` walks the `RecordMapper.toMap` record map.
- ⚠ **A selector must name a leaf.** A container (sub-record, or a repeated field's list) yields
  `NULL`, not a stringified subtree — deliberately matching the legacy transform engine, which also
  drops lists of scalars rather than inventing a join. A repeated field is not one column; give it
  its own segment.
- A trailing derived **`EVENT_TYPE`** column carries the segment key (the `TypedRecordIngester`
  convention), so schemas can partition by record type without redeclaring it.
- ⚠ **Any parse error fails the whole file** → `QUARANTINED_UNREADABLE`. These framings carry no
  length prefix, so `SKIP_RECORD` cannot resync; half-ingesting a CDR file is worse than
  quarantining it. Input is memory-mapped (`ByteSource.map`), so files >2 GB are fine.

`ingester_config`: `grammar` (path to the `.asn` module, required) · `root_type` (required) ·
`strictness` · `file_header_length` · `record_header_length`.

Still open, tracked in BACKLOG §4 "Parsing (Stage-1)":
- **Declarative decode profile — the remaining half.** Framing is now served (above), which was the
  larger part of `GoldenCapture.CASES`'s hardcoded tuple. What is left is the **grammar source**:
  the plugin takes pasted ASN.1 module *text*, where the profile envisioned a reference to a stored
  schema module (the corpus keeps `.asn` files per vendor, e.g. `mtnOCC.asn`). Until that lands
  there is nowhere to *store* a reusable module, and a per-vendor tx/transform config has no home
  either.
- **The Maven coordinate split** — `Asn1ParserPlugin` depends on the NEW rewrite
  (`com.gamma.asn:asn-decoders:0.1.0-SNAPSHOT`, `asn-parser/asn-decoders/`), installed to the local
  repo as a manual step before this reactor builds (not yet resolved automatically). The OLD
  `asn-parser-v2:1.2.1` (parent `com.gamma.asn.decoders:asn-decoders:1.1.3-dev`,
  `asn-parser/pom.xml`) is untouched, has no consumers anywhere in the tree, and is not wired to
  this plugin — it is dead weight the split still needs to resolve (retire, or fold in).
- **Drop-in `plugins/` jar directory** and the **segments editor** (unlock guided Save for
  ingestable custom parsers) — unchanged from before, apply to any custom parser, not ASN.1-specific.
