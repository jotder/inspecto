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
tree-shaped records cannot honestly land in Tables until the flatten configuration exists.
⚠ **No shipped parser is preview-only any more** (XML gained its bridge 2026-08-30), so the
preview-only *mechanism* is pinned by a stub plugin in `ParsersTest`, not by a deployed example —
otherwise a regression in the flag would go unseen precisely because every real parser passes.

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
and external entities disabled outright (XXE), grammar `ingester_config.record_element` (local name
or slash path; blank = the root's direct children) / `namespace_aware` / `encoding` / `max_records`;
`suggest()` proposes the root's repeated child. **Ingestable since 2026-08-30** — it names
`com.gamma.ingester.XmlRecordIngester` (below), the tree→segments bridge.

⚠ **The grammar keys live under `ingester_config`, not an `xml.` root** (moved 2026-08-30). That
block is exactly what the pipeline persists for the ingester, so preview and load are configured by
ONE set of keys; a separate preview-only spelling would have had to be mapped onto the ingest one
somewhere, and that mapping is precisely where a silent drift lives. `max_records` is the one
preview-only key and the ingester ignores it. Nothing operator-authored used the old `xml.` spelling
(only code, tests and the mock), so this was a clean flip, not a compat layer.

Second plugin: `Asn1ParserPlugin` (engine, registered via the same services file) — **the first
hierarchical parser that is `ingestable: true`**, because it names an ingester
(`com.gamma.ingester.Asn1RecordIngester`, below). Wraps the
`asn-facade` module's public `Asn1Decoder`/`RecordMapper` (`asn-parser/asn-decoders/asn-facade`,
depended on as `com.gamma.asn:asn-facade:0.1.0-SNAPSHOT`, installed to the local repo from the
separate `asn-parser/asn-decoders` reactor — not yet resolved from this build, see the coordinate
note below). Grammar: `asn1.grammar` (the ASN.1 module text) / `asn1.root_type` / `asn1.strictness`
(BER/DER/CER) / `asn1.file_header_length` / `asn1.record_header_length` / `asn1.max_records`.
No `suggest()`.

**The grammar is OPTIONAL for preview — structural dump (2026-07-31).** BER is self-describing
(every value carries its own tag and length), so with `asn1.grammar` blank the plugin skips the
facade entirely and walks the raw TLV forest via `RecordReader` (which takes no schema), labelling
nodes by tag — `[APPLICATION 1]`, `[0]`, `[PRIVATE 3]` — instead of by schema name. This is what
lets an operator inspect an unknown vendor's file **before** they have its `.asn` module, which is
exactly the onboarding situation; supply the grammar and the same bytes return with real names.
⚠ Preview-only: **ingest still requires a grammar**, because anonymous tags cannot be mapped onto
segment columns (a column named `[0]` is worthless). ⚠ `root_type` without a grammar is a caller
error, not a silent fallback — it is a half-filled form.
⚠ **Values render hex-first** (`2A`, and `6869 "hi"` for 2+ printable bytes). Text is an annotation,
never a replacement: without a grammar there is no type, and 0x2A *is* printable, so rendering
INTEGER 42 as `"*"` would be a lie dressed as a decoded value. Truncated at 32 bytes.

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

### The tree→segments bridge — `XmlRecordIngester` (2026-08-30)

**This is what closed "Parsing Stage-1 (b)"**, the slice that kept every hierarchical parser
preview-only. There was never a structural blocker: `Parsers.ingestable()` is a *display* flag
derived from `ParserPlugin.ingesterClass()`, and nothing in the config, validation or ingest-dispatch
path ever consulted it. XML was preview-only because no ingester existed, full stop.

`com.gamma.ingester.XmlRecordIngester` (engine) loads through the same `parsing.plugin` machinery as
ASN.1 (`frontend: plugin` + `plugin.ingester`/`segments`/`ingester_config`) and follows its rules
verbatim — segment key = the record element's local name, `raw.fields[].selector` is a dotted path,
undeclared records are `sink.junk()`, a trailing derived `EVENT_TYPE` carries the segment key, and a
malformed document fails the file (`QUARANTINED_UNREADABLE`) rather than loading its prefix.

🔴 **The load-bearing decision is that preview and ingest share ONE walker**, `com.gamma.parse
.XmlRecordReader`. An operator authors a selector against the labels they saw in the preview tree; a
second StAX walker that labelled nodes even slightly differently would resolve those selectors to
`NULL` at load while the preview kept looking correct — a silent, per-column data loss with no error
anywhere. `XmlRecordIngesterTest.previewLabelsAreTheSelectorsThatResolve` pins this by asserting the
selector vocabulary against the *plugin's own* preview output, not a hand-written list.

The selector vocabulary is therefore the preview's labels: a child element by name, an attribute as
`@id`, and an element carrying both text and children as `#text`.

⚠ **A selector must name a leaf that occurs ONCE.** A container yields `NULL` (the ASN.1 rule), and
so does a step matching *repeated* sibling elements — XML has no first-one-wins rule that is not a
guess, and silently taking one of five `<line>` elements would be a lie dressed as a decoded value.
A repeated element is not a column; give it its own segment by naming it as the `record_element`.

**Several record kinds in one document** load by leaving `record_element` blank (every direct child
of the root is then a record) and declaring one segment per kind; an undeclared kind is junk, not a
silent drop. With an explicit `record_element` the other kinds are simply never records.

`ingester_config`: `record_element` · `namespace_aware` · `encoding` (`max_records` is preview-only
and ignored here).

⚠ **A plugin ingester with no segments is refused at CONFIG LOAD** (`PipelineConfigParser
.parsePlugin`), before the ingester is constructed — so the ingester's own segment guard is
unreachable through a loaded config and exists only for the public SPI's direct callers. A test that
tries to reach it through `PipelineConfig.load` is testing the parser, not the ingester.

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
- ~~**The Maven coordinate split**~~ **RESOLVED 2026-08-01.** The root `pom.xml` now aggregates
  `asn-parser/asn-decoders`, so `com.gamma.asn:asn-facade` resolves from the reactor and the manual
  `mvn install` is gone (verified with the local repo's `com/gamma/asn` deleted: 23 modules,
  asn-facade [7/23] before inspecto-engine, `mvn -o clean test` green, 2178 tests). Aggregation
  only — that tree keeps its own parent and inherits nothing from `inspecto-parent`. *(First
  documented as done 2026-07-31, but that pom edit was never committed — the `<modules>` entry was
  lost across a shift and re-landed 2026-08-01; a fresh `~/.m2` still needed the manual install in
  between.)* The OLD `asn-parser-v2:1.2.1`
  (`asn-parser/pom.xml`) is **deleted**: zero consumers, and its parent
  `com.gamma.asn.decoders:asn-decoders:1.1.3-dev` existed nowhere, so it could not build.
  ⚠ **`asn-parser/src/main/java` survives the deletion and must not be cleaned up as an orphan** —
  `legacy-code/pom.xml` compiles it via `<sourceDirectory>../../src/main/java</sourceDirectory>`
  (45 files, confirmed in the build log). It retires with `legacy-code` after Phase 4.
- ⚠ **Corpus-backed tests are opt-in AND data-gated** (DATA-GOV-1). `RealGrammarsTest` (asn-schema)
  and `ParityCheckTest` (asn-golden) `assumeTrue` on **both** `-Dasn.corpus.tests=true` **and** the
  operator data being present on disk, so by default — and on any corpus-less checkout, including a
  `git worktree` (the corpus is gitignored, so worktrees never receive it) — they SKIP and the build
  is green; *skipped* is the expected state, not a regression. Exercise them where the corpus lives
  (the main checkout) with `mvn test -Dasn.corpus.tests=true`. The property alone does nothing
  without the data; the data alone no longer runs them without the property (2026-08-01).
- **Drop-in `plugins/` jar directory** and the **segments editor** (unlock guided Save for
  ingestable custom parsers) — unchanged from before, apply to any custom parser, not ASN.1-specific.
