# ASN.1 Decoder — Redesign & Rewrite Plan

**Status:** Draft for review · **Target:** Java 21 LTS · **Runtime dependencies:** none (JDK only; test scope may use JUnit 5 etc.)

---

## 1. Goals

1. Parse any ASN.1 **BER** file into a TLV tree and dump it (DER/CER as validation modes of the same core).
2. Given an **ASN.1 grammar** (real X.680 subset, properly parsed), name every TLV node and decode values via registered decoders; unknown values fall back to hex string.
3. A **JSON transform config** flattens the named tree into one or more CSV event rows.
4. Architecture leaves clean seams for **PER / OER / XER / JER** later, without implementing them now.
5. **Library + CLI** deliverable; vendor/operator logic lives in **plugin jars**, never in core.
6. Existing deployments migrate via **converters** for today's `.csv` tag maps and `*_tx.json` configs — no requirement that old formats load natively.

## 2. Why a rewrite (audit summary)

The current codebase (~11k LOC, Java 8, no unit tests) contains three generations of readers
(`asn2.reader.BerDecoder`, `asn2.ASN1Reader`+`TagReader`, `asn3.ASNStreamReader`), with tag/length
parsing implemented five times and two copies of several classes. Key findings:

- **The "grammar parser" is not a parser.** `ASNConf`/`Asn1Parser` are a regex/string-replace
  pipeline. It cannot read `[APPLICATION n]` tags (breaks NRTRDE/TAP grammars as shipped), ignores
  `IMPLICIT/EXPLICIT/AUTOMATIC` tagging entirely, skips `IMPORTS` by a caller-supplied line count,
  and flattens CHOICE into its parent. Grammar files in `config/` are hand-doctored to fit the
  parser (CHOICE alternatives commented out, CHOICE rewritten as SEQUENCE).
- **The CSV tag-map path is dead code** — `CSVConf.buildTagDefMap` is never called and its body is
  commented out. Only the `.asn` path works.
- **No CSV writer exists in `src/main`** — CSV emission lives in test `main()` classes.
- **Indefinite-length records are silently corrupted**: `ASN1Reader.readUntilEndOfContent` only
  copies the first byte of each nested TLV into the output buffer.
- **Static mutable state**: `Transformer.txConfig`/`records` and `TransformUtils.cache` are static
  singletons — one config per JVM, a memory leak (`records` grows forever), and no thread safety.
- **Reflection-driven dispatch** with name collisions (`BERDecoder.locationAreaCode` vs
  `BERTags.locationareacode` both key `"LOCATIONAREACODE"`; winner depends on `getMethods()` order).
- **Vendor logic baked into core**: `ccn*`/`occ*` methods, Kabul UTC+4:30 offsets, MSISDN prefix
  `"93"`, serviceKey thresholds — all hardcoded in `TransformUtils`.
- **Lossy error handling**: `printStackTrace`, empty catches, commented-out logging; bad records are
  indistinguishable from empty ones. No counters, no dead-letter.
- **Config semantics drift**: `tap_tx.json` uses `group_by`/`reduce`; the engine reads
  `@group`/`@reduce` and its reduce dispatch is commented out — silently produces nulls.
- **Tests are `main()` classes** with commented-out assertions and hardcoded Windows paths; there is
  no regression corpus, so today "preserve behaviour" is unverifiable.

The v3 attempt (`asn3`) validated the right parsing ideas — a real `TLVNode` tree with offsets,
pluggable `ByteSource`, offset-bounded constructed parsing (no per-level byte copies) — but never
got framing, padding, error recovery, or >2 GB mmap right, and nothing in production uses it.

## 3. Behaviour to preserve (compatibility contract)

These are what deployments bill on; the rewrite must reproduce them byte-for-byte (verified by the
golden corpus, §6 Phase 0):

- Dotted tag-path addressing, including synthetic `.16`/`.17` segments for SEQUENCE OF / SET OF.
- Hex-string fallback for unknown tags/types.
- Vendor pseudo-type decoders and their exact output formats: TBCD (0xF filler, A–E mapping),
  `IPADDRESS`, `USERLOCATIONINFORMATION`, `PLMNID`, `CHARGINGCHARACTERISTICS`, IMEI with check-digit
  synthesis, `CCNTIMESTAMP`, etc.
- File-header / record-header byte skipping and `0xFF` padding tolerance (declarative framing).
- Cartesian-join flattening semantics and the `@`-directive vocabulary of `*_tx.json` — as the
  *migration source*; the new format may express them differently but the converter must map them.

Known bugs are **not** part of the contract; each intentional deviation is documented in the parity
report (§6 Phase 2/3): indefinite-length corruption, `0x00`/`0xFF` tag-skip destroying EOC detection,
`convertedDate` ignoring its format arguments, `@reduce` producing nulls, decoder-name collisions.

## 4. Target architecture

### 4.1 Module layout (Maven multi-module, all JDK-only at runtime)

```
asn-decoders/
  asn-core/          TLV model, ByteSource, framing, BER/DER/CER codec, error model
  asn-schema/        X.680 tokenizer+parser, compiled schema model, schema binder, decoder registry
  asn-transform/     JSON config parser (own ~300-line JSON reader), expression engine, flattener
  asn-sinks/         CSV writer (deterministic columns, quoting), JSONL, TLV tree dump
  asn-plugin-api/    SPIs: TransformFunction, ValueDecoder, Framing — loaded via ServiceLoader
  asn-cli/           `asn` command: dump | compile | validate | run | migrate | golden
  asn-migrate/       converters: legacy .csv tag map → compiled schema; legacy *_tx.json → new config
  plugins/           separate repos/jars per operator (ccn, mtn, …) implementing asn-plugin-api
```

### 4.2 asn-core

- **`ByteSource`** — random-access byte abstraction: `HeapSource` (byte[]), `MappedSource` using
  Java 21 FFM `MemorySegment` + `Arena` (fixes the 2 GB int cap and the Windows unmap/file-lock
  problem), `StreamSource` (buffered, for pipes). All reads are offset-based; **no per-level byte
  array copies**.
- **`Tlv`** — record type: `tagClass`, `tagNumber`, `constructed`, `valueOffset`, `valueLength`,
  lazy `children()`. Carries absolute offsets so any node can be re-read, dumped, or reported in
  errors precisely.
- **One tag/length codec.** Single implementation of tag and length read/write (long-form tags,
  long-form and indefinite length, EOC). Indefinite length handled structurally by descending into
  children until EOC — never by byte-scanning for `00 00` (fixes the corruption class of bugs).
- **`Framing`** — declarative per-format spec (replaces the `fileStruct` map): file header
  (fixed length or self-describing), record header (length-prefix layouts), inter-record padding
  bytes, trailer. Ships fixed-length + length-prefixed implementations; SPI for exotic layouts.
- **`RecordReader`** — `Iterator<Tlv>` over framed records with an explicit **recovery policy**:
  `SKIP_RECORD` (boundary known → position at next record), `STOP_FILE` (boundary unknown — no
  reliable resync), or `RESYNC(pattern)`. Emits typed `ParseError { fileOffset, recordIndex, cause,
  action }` to an error listener; maintains counters (records ok / failed / bytes skipped). This
  generalises the fixes already applied to v2's `ASN1Reader` in this branch.
- **`EncodingRules` SPI** — the seam for future encodings:
  ```java
  interface EncodingRules {
      String name();                            // "BER", "DER", "PER-U", …
      boolean schemaRequired();                 // false for BER/DER/CER, true for PER/OER
      ValueTree decode(ByteSource src, CompiledSchema schema /* nullable when !schemaRequired */);
  }
  ```
  BER decodes schema-less to a TLV tree and binds names afterwards; PER/OER cannot exist without a
  schema, so the SPI passes the schema *into* decode. BER/DER/CER ship now (DER/CER = BER core +
  strictness flags: definite-length only, canonical defaults, sorted SET). PER/OER/XER/JER are
  future implementations of the same interface — no core changes required.

### 4.3 asn-schema

- **Real tokenizer + recursive-descent parser** for the X.680 subset that CDR grammars actually
  use: module header with tagging environment (`IMPLICIT`/`EXPLICIT`/`AUTOMATIC TAGS`),
  `IMPORTS`/`EXPORTS` (resolved across files, not line-skipped), type assignments,
  `SEQUENCE`/`SET`/`CHOICE`/`SEQUENCE OF`/`SET OF`, tags with class (`[APPLICATION 3]`,
  `[PRIVATE 1]`, context), `IMPLICIT`/`EXPLICIT` per-component, `OPTIONAL`/`DEFAULT`,
  `ENUMERATED` with named values (preserved, not coerced to INTEGER), `COMPONENTS OF`, subtype
  constraints (parsed and retained; enforcement optional), `ANY`. Out of scope initially:
  parameterized types, information object classes (parse-error with location, not silent garbage).
- **`CompiledSchema`** — fully resolved, tag-annotated type tree. Serializable to a **stable text
  format** (the successor of the `.csv` tag map): human-diffable, loadable at runtime without the
  grammar parser. Workflow: `asn compile grammar.asn -o format.schema` offline, or parse `.asn`
  directly at startup — both produce the same in-memory model.
- **`SchemaBinder`** — walks TLV tree + CompiledSchema → named tree. CHOICE modelled as a
  discriminated union (alternative selected by tag, not flattened). IMPLICIT vs EXPLICIT resolved
  from the schema, never guessed from constructedness. Unmatched nodes keep tag-path names and hex
  values (never dropped).
- **`DecoderRegistry`** — explicit `Map<String, ValueDecoder>` registration (no reflection scan, no
  case-collision roulette). Core registers the universal types + telecom pseudo-types listed in §3;
  plugins add or override by name. Registry is immutable once built; per-pipeline, not static.

### 4.4 asn-transform

- **Own JSON parser** (~300 lines, JDK only) producing an immutable config AST. Jackson is gone.
- **New transform config format** — evolved from the aspirational `zain_ims_tx2.json` sketch found
  in the repo: explicit `meta` / `framing` / `outputs` / `errors` sections. Key changes vs legacy:
  - **Explicit event definitions**: each output declares its name, its row source (a tree path),
    join behaviour, and an ordered column list → deterministic CSV headers (legacy emits whatever
    key order falls out of the map).
  - **Expression language, compiled not reflected**: literals, `$field`, `$$indirect`, `@self`,
    function calls, simple conditionals. Parsed once into an AST at config load; evaluated with a
    typed function registry (arity/type-checked at load time → config errors surface at startup,
    not per-record).
  - Lookup tables (`@simpleLookup` equivalent) as first-class named resources, loadable from
    inline JSON or sidecar CSV.
  - `group`/`reduce` implemented for real (the legacy engine silently nulls them).
- **No static state.** A `Pipeline` object owns config + registries; N pipelines per JVM, each
  thread-confined or explicitly shared-immutable.

### 4.5 Plugins (asn-plugin-api)

```java
public interface TransformFunctionProvider { Map<String, TransformFunction> functions(); }
public interface ValueDecoderProvider      { Map<String, ValueDecoder> decoders(); }
public interface FramingProvider           { Map<String, Framing> framings(); }
```
Discovered via `ServiceLoader` from jars on a plugin path. Everything operator-specific — `ccn*`
functions, Kabul offsets, MSISDN prefixes, serviceKey thresholds — moves into per-operator plugin
jars. Core ships only generic functions (date conversion honouring its arguments, TBCD, string ops,
arithmetic, lookups).

### 4.6 CLI (`asn`)

| Command | Purpose |
|---|---|
| `asn dump <file> [--schema s] [--framing f]` | TLV tree dump, named when schema given; offsets + hex |
| `asn compile <grammar.asn> -o <out.schema>` | parse + resolve grammar, emit compiled schema |
| `asn validate <config>` | schema/transform/framing config validation with precise errors |
| `asn run <pipeline.json> <files…>` | end-to-end file → CSV, with error report + counters |
| `asn migrate tx <old_tx.json>` / `asn migrate csv <map.csv>` | legacy config converters with an "unsupported constructs" report |
| `asn golden capture / compare` | regression corpus tooling (§6 Phase 0) |

## 5. Error handling & observability (cross-cutting)

- Typed exceptions with file offset, record index, tag path; no `printStackTrace`, no empty catches.
- Per-file `ProcessingReport`: records read/decoded/transformed/failed, bytes skipped, first N
  errors, wall time. Returned by the API and printed by the CLI.
- Dead-letter hook: failed record's raw bytes + error, pluggable sink.
- Logging via `System.Logger` (JDK platform logging — zero dependencies; deployments can bridge it
  to whatever they run).

## 6. Phased delivery

Each phase ends demonstrable and merged; later phases never require reworking earlier ones.

**Phase 0 — Golden corpus (before any new code).**
Harness that runs *today's* code over every format with sample data (Zain IMS, Huawei GGSN/MSC/IMS,
Ericsson AIR/CCN/MSC, TAP3, NRTRDE, …) and captures the decoded record maps as canonical JSON plus,
where transforms exist, flattened rows. Store under `corpus/<format>/`. Anonymise/trim samples.
This is the only objective definition of "current behaviour". *Exit: corpus committed, re-runnable.*

**Phase 1 — asn-core.**
ByteSource (heap/mmap/stream), Tlv model, single tag/length codec, framing, RecordReader with
recovery policy + report, BER with DER/CER strictness flags, `asn dump`. Validate structural
parity (tag paths, offsets, record counts) against every corpus file. *Exit: `asn dump` reproduces
record framing/structure for the whole corpus; unit tests incl. indefinite-length, long-form tags,
padding, truncation, >2 GB mmap.*

**Phase 2 — asn-schema.**
Grammar tokenizer/parser, CompiledSchema + text serialization, SchemaBinder, DecoderRegistry with
all §3 decoders, `asn compile`. Repair the hand-doctored grammars in `config/` into real ASN.1 (or
compile from vendor originals where available). *Exit: named+decoded output matches Phase 0 corpus
for every format, with a written deviation report for each intentional bug fix.*

**Phase 3 — asn-transform + asn-sinks.**
JSON parser, config AST + validation, expression engine, flattener, CSV/JSONL sinks, `asn run`,
`asn migrate` for 2–3 pilot configs (suggest: Zain IMS, GGSN, TAP3 — they exercise join, lookup,
and group/reduce respectively). *Exit: migrated pilot configs reproduce corpus rows; `asn validate`
rejects the known-broken legacy constructs with clear messages.*

**Phase 4 — Plugins + full migration.**
Plugin SPI + ServiceLoader wiring; port `ccn*`/operator logic into plugin jars; migrate remaining
production configs; deprecate `asn2`/`asn3`/`transformer2` (keep for one release behind the corpus
harness, then delete). *Exit: no vendor names in core; all production formats green on corpus.*

**Phase 5 — Hardening & future-ER groundwork.**
DER/CER conformance tests, fuzz/property tests on the codec (random TLV round-trips, mutated
inputs must error — never mis-parse silently), performance pass (target: mmap + zero-copy should
beat v2 comfortably; measure records/sec on corpus files), docs (config reference, plugin author
guide, X.680 subset statement), and a short design note per future ER (PER/OER/XER/JER) proving the
`EncodingRules` seam suffices.

**Relative sizing** (imprecise, for planning): Phase 0 ~ small; Phase 1 ~ medium; Phase 2 ~ large
(the grammar parser is the biggest single item); Phase 3 ~ large; Phase 4 ~ medium; Phase 5 ~ small-medium.

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Grammar parser scope creep (X.680 is huge) | Freeze the subset in §4.3; parser fails loudly with location on anything outside it; subset statement in docs |
| Hand-doctored `.asn` files in `config/` are not valid ASN.1 | Phase 2 repairs them against vendor spec PDFs (present in repo); compiled-schema text format is the escape hatch when no clean grammar exists |
| Golden corpus enshrines bugs | Deviation report per phase distinguishes "bug fixed on purpose" from "regression"; corpus comparer supports per-format waivers |
| IMPLICIT/EXPLICIT now handled correctly may change some names/values vs legacy | Surfaced by corpus diff; waived or migrated per format deliberately |
| Zero-dependency JSON/logging | Small, boring, well-tested code; JSON parser is ~300 lines with its own test suite |
| Plugin classloading complexity | Plain ServiceLoader + isolated URLClassLoader per plugin dir; no OSGi, no dynamic reload in v1 |

## 8. Out of scope (v1)

PER/OER/XER/JER implementations (seam only); ASN.1 value notation; information object classes;
schema-aware *encoding* (write path) beyond the TLV dump; daemon/watcher mode; dynamic plugin reload.
