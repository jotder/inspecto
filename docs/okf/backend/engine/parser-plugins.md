---
type: Concept
title: Parser plugins (the self-describing Parser framework)
description: One SPI unifying the two transparent parse engines — DuckDB-native built-ins and custom Java decoders — served to the UI with grammar schemas and a tree-capable preview.
resource: inspecto-engine/src/main/java/com/gamma/parse/ParserPlugin.java
tags: [concept, engine, parsing, spi, plugins]
timestamp: 2026-07-30T00:00:00Z
---

# Parser plugins — the self-describing Parser framework

> The runtime parse model (three byte→row *mechanisms* over one DuckDB backend) is
> [parsing-grammar.md](parsing-grammar.md); the ingest SPI itself is [plugins.md](plugins.md).
> ⚠ **"Frontend" names two different things.** As a *mechanism* there are three (DuckDB-native read,
> DuckDB-native + SQL regex, Java `StreamingFileIngester`). As a **legal `parsing.frontend` config
> value** there are ten tokens / eight distinct formats (`PipelineConfigParser.FRONTENDS:1590-1591`).
> As a **registered `ParserPlugin` id** there are six built-ins (`BuiltinParsers.IDS:29`) plus any
> `ServiceLoader` providers. Read "exactly three" as a mechanism claim, never as a config constraint.
> THIS concept is the self-description layer over both: discovery, served grammar schemas, preview.

The E of ELT: any file loads into one or more **Tables** (the segment → partitioned CSV/Parquet
model, unchanged — see [plugins.md](plugins.md)). A file type is parsed by providing a **Grammar**
(GLOSSARY §6). Internally there are two **fully transparent** parser engines — DuckDB-native reads
for the built-ins, custom Java decoders for everything DuckDB can't consume — unified behind one
self-describing SPI so a new format can be *deployed and configured as a plugin* with **zero UI or
control-plane change**.

## The SPI (`com.gamma.parse.ParserPlugin`, engine, `@PublicApi since = "4.0.0"`)

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

Built once at class-load: the **six** built-in adapters (`BuiltinParsers.IDS:29` — delimited /
fixedwidth / json / text_regex / xlsx / parquet, whose `preview` delegates to
`ComponentPreview.parsing`, i.e. the exact DuckDB read specs `DuckDbCsvIngester` runs at ingest) merged
with
`ServiceLoader.load(ParserPlugin.class)` (`META-INF/services/com.gamma.parse.ParserPlugin`).
⚠ **Duplicate ids fail startup loudly** — deliberately unlike `PipelineNodeTypes`' override-a-
builtin rule: the built-ins' preview IS the engine that ingests, so an override would let a
preview diverge from production parsing. ⚠ `Parsers.load` must NOT use `Map.copyOf` — it discards
iteration order, and catalog order (built-ins first) is part of the contract.

**Plus an owner-keyed pack overlay (2026-09-25, slice P2).** `Parsers.register(plugin, owner)` /
`deregister(owner)` add and remove the parsers a loaded Job Pack contributes, keyed by the pack's jar
filename; `ownerOf(id)`, `sourceOf(id)` (`builtin` | `classpath` | `pack:<jar>`) and
`forIngester(fqcn)` read it. Catalog order is built-ins, then classpath providers, then packs in load
order. `register` refuses an invalid id, **any id the built-ins or classpath providers hold** (a pack can
never replace one), an id another pack owns (first pack wins), and **an ingester class name another
registered parser already names** — otherwise which pack's loader resolved that class would depend on load
order. Details under *Drop-in parser jars* below.

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
note below). Grammar: `asn1.grammar` (the ASN.1 module text) **or** `asn1.grammar_file` (a stored
`.asn` module, 2026-09-23 — see below) / `asn1.root_type` / `asn1.strictness`
(BER/DER/CER) / `asn1.file_header_length` / `asn1.record_header_length` / `asn1.max_value_bytes`
(the single-value cap, see *BER hostile-input handling* below) / `asn1.max_records`.
No `suggest()`.

**A grammar is either pasted TEXT or a stored `.asn` FILE (operator decision 2026-09-23).** A stored
module is a path-jailed `.asn` file under the Space's config — deliberately **not** a new registry kind.
Both spellings resolve through ONE class, `com.gamma.parse.Asn1GrammarSource`, which the preview
(`Asn1ParserPlugin.preview`), the flat config (`frontend: asn1`) and the ingester
(`Asn1RecordIngester`) all call, so a grammar that previews is the grammar that ingests:

- **Text wins when both are set** — the "parsing: keys win" overlay rule the ingester already applied.
- **A relative ref resolves like `schema_file`** — `PathJail.resolveConfigRef`, never the working
  directory (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`). In a Pipeline it is resolved **beside the Pipeline's
  own config file** (a sibling name: `grammar_file: msc_cdr.asn`), by `PipelineConfigParser`, into
  `Schemas.ingesterGrammar()` — the ingester never sees the config's directory. The preview has no config
  file, so the Parse drawer sends its Pipeline's location: `POST /parsers/{id}/preview` takes an optional
  body `subdir` (the Pipeline's config directory relative to the write root — the same meaning as every
  satellite route's `subdir`, `''` = at the root), and the route resolves the ref beside
  `writeRoot/<subdir>` via `ParserPlugin.preview(sample, grammar, configDir)` — so **one spelling
  (`grammar_file: msc_cdr.asn`) previews and ingests** for a Pipeline in a subdirectory too
  (`BUNDLE-ASN1-GRAMMAR-FILE-1`, 2026-09-23). Only with **no** Pipeline context (no `subdir` key: the
  stand-alone Grammar dialog) does it fall back to the **bound Space's config root**
  (`SpaceConfigRoot.current()`). An absolute `subdir` is 400; the base is not jailed — the resolved FILE
  is, once, below.
- The file ref is jailed **once**, by `Asn1GrammarSource`, with `PathJail.requireUnderAny(allowedRoots())`
  **before** the readability probe (an escaping ref is refused, never reported "not readable", which
  would leak whether a path outside the roots exists). The parser resolves but does **not** jail.
- ⚠ **The extension is enforced (`.asn` / `.asn1`) first, before anything touches the disk.**
  `POST /parsers/{id}/preview` is compute-only with no capability, and a compile error can echo the text
  it choked on — an unrestricted ref would let a preview read any file under the roots back through an
  error message.
- `frontend: asn1` carries `asn1.grammar_file` as-authored as the path key `ingester_config.grammar`
  (and `asn1.grammar` as `grammar_text`), so a save writes back the relative value; the resolved path
  lives apart, in `ingesterGrammar()`. The load refuses an `asn1:` block carrying neither spelling.
- The preview route maps the jail's `PathJail.Escape` to **403**; other grammar problems stay 422.
- The drawer needs no bespoke UI: the ASN.1 form is SERVED from `grammarSchema()`, which now lists
  `asn1.grammar_file` beside `asn1.grammar`.
- Proof: `DemoCorpusIngestTest.mscCdrWithItsGrammarInAnAsnFilePreviewsAndIngestsIdenticallyToInline`
  moves the committed `msc_cdr` grammar into a `.asn` file and pins the same preview tree and the same
  rows in every segment as the inline original.
- **A Pipeline bundle carries it and re-points it** (`BUNDLE-ASN1-GRAMMAR-FILE-1`, 2026-09-23). The
  resolved module is in `referencedFiles()`, so the export ships it as a satellite under its basename,
  and `PipelineBundleRoutes.rewriteSatelliteRefs` rewrites `parsing.asn1.grammar_file` to that basename
  like every other satellite ref. ⚠ Before this, an import of a non-sibling spelling
  (`grammars/msc_cdr.asn`) still returned **200 and registered** — nothing resolves the module at
  registration — and would only have failed at the first ingest. Proof (real HTTP):
  `ControlApiPipelineBundleTest.anAsn1GrammarFileTravelsAndIsRepointedBesideTheImportedPipeline` (the
  msc_cdr demo, grammar in a `.asn` file, export → import → the drawer's preview with `subdir` equals the
  inline preview); `ControlApiParsersTest.asn1PreviewResolvesAGrammarFileBesideThePipelineSubdir`.

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

- `GET /parsers` → `[{id, label, hierarchical, ingestable, ingesterClass?, source, grammarSchema}]`.
  `source` (2026-09-25, additive) is `builtin`, `classpath` or `pack:<jar filename>` for a parser a Job
  Pack contributed.
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
- ⛔ **A stand-in must never be more lenient than the server.** *(This was `parsers.handler`'s parity rule,
  pinned in its own spec, until the offline mock was deleted 2026-08-31; the rule outlives the mock and
  applies to any fake, stub or fixture that answers for this catalog.)*

## ASN.1 (the operator's target format) — status

**Served as of 2026-07-31** via `Asn1ParserPlugin` (above) — it appears in the Onboarding Parsing
stage's toggle and the Pipelines Parser dialog with zero UI change, exactly as designed. The plugin
sits on the new `asn-facade` API and serves grammar + framing. ⚠ It was preview-only until
2026-07-31; it now names `com.gamma.ingester.Asn1RecordIngester` via `ingesterClass()`, so
`Parsers.ingestable()` (`Parsers.java:66-68`) is **true** — see "Loading to Tables" below.

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
preview/authoring*, **and also a first-class `parsing.frontend` value**: `PipelineConfigParser`'s
`FRONTENDS` set (`:1590-1591`) has ten tokens —
`{delimited, fixedwidth, fixed_width, json, text_regex, xlsx, excel, parquet, asn1, plugin}` — and
`frontend: asn1` synthesizes this exact plugin wiring (`parsePlugin:1090-1095`), refusing a co-present
`parsing.plugin` / `processing.ingester`. Writing the wiring out by hand under `frontend: plugin`
remains equivalent.

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

`ingester_config`: `grammar` (path to the `.asn` module) or `grammar_text` (inline module; wins when
both are set) — one of the two required · `root_type` (required) ·
`strictness` · `file_header_length` · `record_header_length` · `max_value_bytes`.

Still open, tracked in BACKLOG §4 "Parsing (Stage-1)":
- ~~**Declarative decode profile — the grammar source.**~~ **CLOSED 2026-09-23.** Framing was served
  earlier; the grammar source is now too. *(This bullet said until 2026-09-23 that the plugin takes
  only pasted module text. That was already half-wrong — the ingester accepted a jailed `.asn` path
  under `ingester_config.grammar` all along — and is now wholly wrong: preview, `frontend: asn1` and
  the drawer all take `asn1.grammar_file`, see "A grammar is either pasted TEXT or a stored `.asn`
  FILE" above.)* A per-vendor module is stored as a `.asn` file in the Space's config (the corpus
  keeps them that way, e.g. `mtnOCC.asn`). A per-vendor tx/transform config still has no home.
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
- ⚠ **`asn-parser/src/test/` is a tree of manual scratch programs, NOT tests** (operator decision
  2026-09-24, `LEGACY-ASN-SRC-TREE-UNBUILT-1`: rename, don't wire). No `testSourceDirectory` points
  at it, so its 21 files compile on no build path. The six that carried `*Test` names were renamed
  to the `*Harness` convention so nothing claims coverage it lacks: `Test` → `SchemaCsvHarness`,
  `ASNFileReaderTest` → `ASNFileReaderHarness`, `BERDecoderTest` → `BERDecoderHarness`,
  `SbinHuaMscAsnTest` → `SbinHuaMscAsnHarness`, `FixedLengthFileReaderTest` →
  `FixedLengthFileReaderHarness`, `RTDMS_ASN_Test` → `RTDMS_ASN_Harness`. `asn-golden`'s
  `GoldenCapture` cites `RTDMS_ASN_Harness.<method>` as provenance for 7 of its 9 golden cases —
  rename that file again only together with those comments. Real ASN.1 coverage lives in the
  `asn-parser/asn-decoders` modules.
- ⚠ **Corpus-backed tests are opt-in AND data-gated** (DATA-GOV-1). `RealGrammarsTest` (asn-schema)
  and `ParityCheckTest` (asn-golden) `assumeTrue` on **both** `-Dasn.corpus.tests=true` **and** the
  operator data being present on disk, so by default — and on any corpus-less checkout, including a
  `git worktree` (the corpus is gitignored, so worktrees never receive it) — they SKIP and the build
  is green; *skipped* is the expected state, not a regression. Exercise them where the corpus lives
  (the main checkout) with `mvn test -Dasn.corpus.tests=true`. The property alone does nothing
  without the data; the data alone no longer runs them without the property (2026-08-01).
- **Drop-in parser jars** and the **segments editor** (unlock guided Save for ingestable custom parsers)
  — apply to any custom parser, not ASN.1-specific. The drop-in half's design is
  [`parser-plugins-trust-design.md`](../../../superpower/parser-plugins-trust-design.md); what has shipped
  is below.

### Drop-in parser jars — trust gate + pack parser registration SHIPPED (2026-09-25)

Operator decisions 2026-09-25: **D1** a parser arrives as a **fifth Job Pack kind** through the existing
`JobPackManager` loader (`-Djobs.packs.dir`). There is no second `plugins/` directory. **D2** T1 SHA-256
allowlist is required; signer anchoring (T2) is not built. **D3** fail closed for Job Packs too. **D4**
`POST /parsers/{id}/preview` needs the Pipeline-authoring capability for pack parsers only. **D7** no
edition gate. **D8** no out-of-process host.

**As built (slice P1, the gate every pack kind now passes):**
- `-Djobs.packs.allowlist=<file>` (`PackAllowlist`, `inspecto-engine` `com.gamma.job`). Format: one
  `<64-hex sha256>  <file>  [note]` per line, so `sha256sum` output works as-is. Only the hash decides.
  Blank lines and `#` lines are skipped.
- **Fail closed:** packs dir set with no allowlist ⇒ **every** jar is refused, cause `not trusted: no
  jobs.packs.allowlist configured`. An unreadable file, or a single malformed line, refuses every jar
  (the cause names the file or the line). An unlisted hash is refused with `not trusted: sha256 <hash> is
  not in jobs.packs.allowlist`.
- **Verify before load, no TOCTOU:** the hash that is checked is the SHA-256 of the **staged copy the
  `URLClassLoader` reads**. It is taken after staging and before any class is defined. That is the same
  staged-bytes rule the earlier verify/load TOCTOU fix (`f90ddcf25`) set up for the signature check.
  `JobPackTrustTest` pins both swap windows. A jar swapped before staging is refused under its own hash.
  A jar swapped after staging loads only the vetted bytes, and is then refused on the next rescan.
- **Rescan re-reads the allowlist.** Approve = add the line and rescan (`POST /jobs/packs/rescan` or any
  change in the packs dir). Revoke = remove the line and rescan, which **unloads** the loaded pack and then
  refuses it.
- **A3, boot refusal:** the constructor throws, so the Space does not load, when the allowlist lies inside
  the packs dir or under `assist.write.root`, `spaces.root` or any `PathJail.allowedRoots()` root. By
  design there is no API or UI route that approves a jar.
- **Signals / log / inventory:** each refusal emits `job.pack.rejected {file, hash, cause}` and a WARN
  `[PACKS] rejected …` line. `GET /jobs/packs` lists refused jars as `{file, hash, state: "rejected",
  cause}` next to the `state: "loaded"` rows, and a row is dropped when its jar leaves the dir. The
  startup log line names the allowlist, or says it is `NOT CONFIGURED`.
- `-Djobs.packs.requireSignature` is unchanged. It is an **integrity** check on top of the allowlist, and
  it still never looks at who signed.

**As built (slice P2, a parser is the fifth pack kind):**
- `JobPackManager.load` runs a fifth `ServiceLoader` loop for `ParserPlugin` over the pack's loader, keeping
  only providers that loader defined. A pack carrying **only** a parser is valid. Its parsers register
  through `Parsers.register(p, <jar filename>)` after the other four kinds.
- **Atomic refusal.** A parser id that a built-in or classpath parser holds, that another pack owns, or
  whose ingester class another registered parser already names, throws inside `load`. The catch
  deregisters **all five** kinds, so the pack is rejected whole: its Job Types, tokens, node types and
  executors never stay registered. The cause names the id (`job.pack.rejected`, `GET /jobs/packs`).
- **Unload / revoke** (`unload`: jar removed, hash changed, or hash no longer listed) calls
  `Parsers.deregister(owner)`. The parser leaves `GET /parsers` and `POST /parsers/{id}/preview` 404s.
- **Provenance.** `GET /parsers` rows carry `source: "pack:<jar filename>"` (the Job Type vocabulary). The
  pack's manifest id/version stay in `GET /jobs/packs`.
- ⚠ **One process-wide registry, one packs dir.** `-Djobs.packs.dir` is JVM-wide and every Space's
  `JobService` builds a manager over it, so `register` under the **same** owner replaces rather than
  refuses. Consequence (shared with the node-type overlay): one Space's unload deregisters the parser for
  all of them. A pack is normally replaced, not removed.
- Proof: `JobPackParserTest` (real jars compiled at test time, off the classpath: parser-only pack loads
  with `pack:` provenance; jar removal and hash revocation unregister; a pack whose parser is `delimited`
  is rejected and its Job Type rolled back, paired with the same mixed pack under a free id loading both
  kinds; a second pack claiming a taken id is rejected and the first keeps it). `ParsersTest` pins the
  overlay rules; `ControlApiParsersTest.catalogNamesEachParsersProvenanceIncludingAPacksParser` the wire.

**As built (slice P3, a pack parser ingests):**
- **One resolver**, `com.gamma.inspector.PluginIngesters.open(cfg)`, replaced the two one-argument
  `Class.forName(ingesterClass)` sites (`GenerationModeIngester`, `UnionModeIngester`). They now receive the
  ingester from `StreamingPluginIngestStrategy`. The Pipeline still stores only the FQCN
  (`parsing.plugin.ingester`). The resolver finds the registered parser naming that class
  (`Parsers.forIngester`). A pack-owned one is loaded through **the parser's own class loader**. Anything
  else resolves on the engine loader, as before.
- **The pack is pinned for the whole batch.** `PackRunLeases.acquire(owner)` is the same per-owner counter
  a Job Run and a pipeline-graph walk take, released when the strategy's `try` closes. An unload or
  revocation mid-ingest deregisters the parser at once, but the loader close is **deferred** until the
  ingest ends, so the batch finishes on the vetted bytes it started with. The resolver re-checks the
  registration after taking the pin, so an unload that won the race is reported, not half-used.
- **Named failure.** A Pipeline whose ingester no loaded parser names and the engine loader cannot find
  throws `IllegalStateException` "streaming ingester <fqcn> is not on the classpath and no loaded parser
  names it; if a Job Pack provided it, that pack is not loaded (removed, revoked or refused, see GET
  /jobs/packs)". There is no `ClassNotFoundException` in the cause chain. It fails the Run the same way the
  old "Cannot instantiate" error did (out of `ConsignmentIngestor.process`). That message remains for a
  class that is found but cannot be constructed.
- Proof: `JobPackParserTest` — a pack ingester ingests two CALL rows end to end, paired with the probe that
  `Class.forName(fqcn)` on the engine loader throws `ClassNotFoundException`. An unload while the ingester
  is blocked leaves the pack draining until the ingest ends. **Mutation-checked**: with the pin removed,
  that test goes red on the `isDraining` assertion. A Pipeline naming an unloaded pack's ingester gets the
  named error.

**Not built yet** (design §5): **P4**, D4 preview gating. Still open from **P0**: staging under a server-owned dir instead of
the system temp dir. The decode-profile satellite (C1–C4) waits on D5/D6/D9/D10.

### BER hostile-input handling — fixed 2026-09-17, value cap 2026-09-24

✅ **`BER-LENGTH-OVERFLOW-1` (P1) is closed.** A long-form 8-byte length of `Long.MAX_VALUE` made
`valueOffset + valueLength` wrap negative, so the `end > limit` guard passed and `BerReader` returned a Tlv
with a **negative `endOffset`** that `RecordReader` counted as `recordsOk++` and used as its cursor — a
malformed record recorded as successfully parsed. Both sites now compare against a remaining-bytes budget
(`valueLength > limit - valueOffset`), where both operands are non-negative and the subtraction cannot
overflow. ✅ **`BER-FRAMING-UNCHECKED-READ-1` too**: `Framing.Fixed.recordLength` bounds its header read, and
`RecordReader` takes the framing calls inside the recovery `try`.

⚠ **A truncated TAIL reports `ParseError(STOP_FILE)` even under `RecoveryPolicy.SKIP_RECORD`** — a header that
cannot be read yields no boundary to resync to. Records already read are still delivered.

✅ **`BER-VALID-BUT-HUGE-ALLOCATION-1` is closed (2026-09-24) with a configurable single-value cap** —
operator decision: a cap, not a streaming accessor, so `Tlv.value()` keeps its `byte[]` return type. The
hazard was a length that is *valid* but enormous: measured with a 3 GB stub source, `04 84 95 02 F9 00`
parsed cleanly, and just under 2 GB `Tlv.value()` would have allocated a `byte[]` sized by
attacker-controlled input. No bounds check can reject that, because the length is legitimate.

- **Where it lives:** `BerReader.read(src, offset, limit, strictness, maxValueBytes)` refuses a
  **primitive** value whose declared length exceeds the cap, as a `BerParseException`:
  `value of [APPLICATION 3] declares 5 bytes, over the max_value_bytes cap of 4 (at offset 2)`. The check runs
  at parse time, after the in-bounds check (a truncated value still reports truncation), so no Tlv exists
  for anything to allocate from. The cap passes through `RecordReader` (6-arg constructor) and
  `Asn1Decoder.decode(…, maxValueBytes)`. The old signatures still exist and use the default. A
  refusal is an ordinary record failure: it reaches the `ErrorListener` as a `ParseError`, and
  `SKIP_RECORD` skips a length-prefixed record the way it skips any other bad record.
- **Constructed values are not capped.** Their length only bounds where children are parsed, and no
  decoder copies a constructed value. A record far larger than the cap decodes as long as each leaf is under it.
- **Default `BerReader.DEFAULT_MAX_VALUE_BYTES` = 64 MiB.** Measured 2026-09-24 over every shipped
  sample (`MSC01_20260801_0800.ber`, `CDR_20260801.ber`, `corpus-synthetic`) and the operator corpus
  (Huawei IMS/MSC, Ericsson CCN/OCC/SDP, IMS and SGSN CDRs): the largest primitive value is **128 bytes** and
  the largest record about 9 KB. 64 MiB therefore refuses no real file and still caps the worst case at an
  allocation any JVM that runs the engine can afford.
- **Pipeline config:** `asn1.max_value_bytes` (a served `grammarSchema` field, so the preview and the Parse
  drawer honour it) and `ingester_config.max_value_bytes`. `frontend: asn1` carries it across the same way
  as the framing knobs (`PipelineConfigParser.asn1PluginBlock`). One parser, `Asn1ParserPlugin.maxValueBytes`,
  serves both spellings, so the cap a preview uses is the cap an ingest uses. Unset or blank means the
  default. Anything that is not a whole number ≥ 1 is a config error naming the key. Because of the
  *any parse error fails the file* rule above, an over-cap value quarantines the file `QUARANTINED_UNREADABLE`.
- Not a `ConfigSpecs` / accepted-key census entry: `ConfigSpecs` declares no `parsing.asn1.*` key,
  and the pipeline census stops at the top-level `parsing` block. The parser's served
  `grammarSchema` is the declaration for `asn1.*`.
