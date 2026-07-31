# asn-decoders

New-generation ASN.1 decoder per [`../docs/REDESIGN.md`](../docs/REDESIGN.md). JDK-only at
runtime (test scope uses JUnit 5). Build: `mvn -o clean test` (offline works; surefire 3.5.3).

## Modules (§4.1 of the redesign)

- **asn-core** — `ByteSource` (heap + FFM-mmap, no 2 GB cap, deterministic unmap),
  `Tlv` model with absolute offsets, the single tag/length codec (`BerReader`, indefinite
  length handled structurally — never by scanning for `00 00`), declarative `Framing`
  (file header / length-prefixed record headers / padding / trailer), `RecordReader` with
  `SKIP_RECORD`/`STOP_FILE` recovery + counters + error listener, `Strictness.BER/DER`,
  `TlvDump`.
- **asn-schema** — X.680 tokenizer + recursive-descent parser (`Asn1Parser`),
  tag resolution incl. IMPLICIT/EXPLICIT/AUTOMATIC and COMPONENTS OF (`SchemaCompiler` →
  `CompiledType`/`CompiledSchema`), `SchemaBinder` (TLV → named tree, CHOICE by tag,
  unknown nodes keep tag-path + hex), `DecoderRegistry` (explicit registration, immutable,
  per-pipeline) with universal + generic telecom decoders (TBCD, AddressString, IPAddress,
  PLMN-Id).

## Deviations from the redesign doc (deliberate)

- **Java 25 LTS, not 21**: the FFM API (`Arena`/`MemorySegment`) used for >2 GB mmap is
  final only since Java 22.
- **Lenient mode** (`Asn1Parser.parseLenient`, `SchemaCompiler.compileLenient`): the
  grammars in `config/` are hand-doctored (lowercased keywords in `nrtrde_2.1.asn`, a
  botched `Currency`→`OCTET STRING` global replace in `tap 3.12.asn`). Lenient mode
  recovers per component/assignment and reports every skip as a warning; unresolved type
  refs decay to OCTET STRING with a warning. Strict mode (default `parse`/`compile`)
  still fails loudly with line:column. `RealGrammarsTest` pins both files loading.
- Tokenizer/keyword tolerance is documented in `Asn1Parser` javadoc: trailing/missing
  commas, case-insensitive keywords.

## Phase 0 — done

- **legacy-code** module compiles the unmodified legacy sources (`../../src/main/java`,
  Java 8 code, builds clean at release 25) so the harness can drive today's behaviour.
- **asn-golden** module + `run-golden.ps1` capture the golden corpus to `../corpus/`
  (see `corpus/README.md`): 9 cases, ~29k records decoded, ~13k transform rows,
  deterministic, one JVM per case (legacy static state + cartesian-join OOM).

## Parity — Phase 1 exit met; Phase 2 vendor decoders ported

`ParityCheck` (asn-golden) drives the new stack over every corpus case and diffs against
the legacy capture → `corpus/<case>/parity.json` + `corpus/PARITY.md`. `ParityCheckTest`
pins one fast case per build; the full sweep is a manual main (see PARITY.md header).

- **Structural parity: 16/16 data files** — identical record counts on every case,
  including the Ericsson OCC/CCN zero-fill (legacy skips 0x00/0xFF before every tag —
  now declarative `paddingBytes`) and the Huawei 50/4 skip-only framing. Legacy's
  "+1 emptyRecord" on padded files is an EOF artifact of its reader, not a record.
- **Content parity (leaf name+value multisets, 2026-07-29): 10/16 files at 1.0**
  (occ ×3, ccn, sdp ×3, huwims ×2, zain_pgw), aftel_ims 0.99, mtna_huwmsc 0.9998,
  zain_ims 0.96–0.97, awcc_sgsn 0.91.
- The legacy byte→text logic is ported VERBATIM into `Decoders` and dispatched by
  grammar type name in `DecoderRegistry.withDefaults()` exactly like legacy
  `TagHelper`/`BERTags`/`BERDecoder`: uppercase hex fallback, OCTET STRING as
  ISO-8859-1 text, TBCD with A–E letters, AddressString/DirectoryNumber, TIMESTAMP,
  CCNTIMESTAMP, CHARGINGCHARACTERISTICS, USERLOCATIONINFORMATION (incl. its LAC
  byte-overlap bug), LOCATIONAREACODE, TELESERVICECODE, TOPDPTYPE, TOIMEI (Luhn
  check digit). `IMEI`/`NumberString` are deliberately NOT name-registered — the
  corpus grammars define them (TBCD-STRING / IA5String) and legacy dispatched the
  resolved base type.
- Vendor-dialect tolerances (each pinned by a test): anonymous `DEFINITIONS` header,
  unterminated `EXPORTS everything`, `_` in identifiers, missing `END`, `HEX STRING`
  two-word pseudo-types, bodyless `ENUMERATED`, stray idents from `--x, --` comment
  pairs, non-consuming expect + brace-safe recovery (each desync used to eat the rest
  of the module); binder-side: union-SET/SEQUENCE record types matched by component
  tag, mandatory components skippable, repeated tags via wrap-around (legacy tag-map
  behaviour), SEQUENCE/SET universal-tag interchange.
- **Deliberate deviations from legacy** (visible in the sub-1.0 cases): ENUMERATED
  decodes as the raw integer like legacy, but names remain on `CompiledType.valueNames()`;
  OBJECT IDENTIFIER decodes as a real dotted OID (legacy: empty string); where legacy
  emitted raw undecoded bytes as text because a typedef resolved to OCTET STRING
  (awcc `servedMSISDN`/`locationAreaCode`, huwMsc `forwarded`), the new stack applies
  the proper telecom decoder — the remaining awcc/huwmsc mismatches are exactly these.

## Phase 3 — asn-transform, rows parity

- **asn-transform** (JDK-only): own minimal JSON parser (`Json`), legacy tx.json loader
  with the exact comment-stripping (`TxConfig`), explicit `FunctionRegistry` (no
  reflection in core), and `LegacyTransformEngine` — a faithful, static-state-free port
  of the legacy transformer2 row semantics (map-flatten + cartesian join of list
  sub-records, `@keepSource`/`@transform`/`@derivedFields`/`@autoJoin`/renames,
  `"literal"`/`$field`/`$$indirect`/`@self` params; a failed function yields a null
  value, a null `$param` drops the field — both legacy-faithful). Legacy
  `@group`/`@reduce` only fed a static list nobody read; deliberately not implemented.
- ~~Vendor functions stay legacy for now~~ — ported to plugins in Phase 4 (below).
- **RecordMapper** (asn-golden) converts the binder's `NamedNode` tree into the legacy
  record-map shape (OF lists keyed by decapitalised element type name, INTEGER/BOOLEAN
  typed values) so legacy tx configs drive the new stack unchanged. `NamedNode` now
  carries its `CompiledType`.
- **Rows parity (2026-07-29)**: row counts identical on all 8 row-bearing corpus files;
  exact-row matches occ 592+592+594 of 592+592+594 (100%), ccn 293/296,
  huwmsc 9057/9097, aftel ≈85% exact with row-leaf ratios ≥ 0.99 — every residual
  traces to the documented decode deviations above (e.g. `forwarded` AddressString).

## Phase 4 — vendor functions in plugins

- **asn-plugin-api** (REDESIGN §4.5): `TransformFunction`, `TransformFunctionProvider`
  (ServiceLoader SPI), `PluginContext` carrying the config's `@simpleLookup` tables
  per pipeline — the legacy static `TransformUtils.setCache` is gone.
  `ValueDecoderProvider`/`FramingProvider` are deliberately deferred: nothing
  vendor-specific needs them yet (all corpus decoders are the generic telecom set).
- **asn-plugin-vendors**: VERBATIM port of the 30 vendor functions the production tx
  configs actually invoke (Ericsson CCN/OCC, Huawei MSC, operator numbering, lookups),
  quirks included. One jar for now — split per operator when real deployments migrate.
  Names the configs call that legacy never implemented (`getStartEndTime`,
  `convertedClientDate`, `interOperatorIdentifiers`, `subscriptionId`, `firstKey`) stay
  unregistered on purpose: legacy resolved them to null and the corpus rows depend on it.
- Generic `add`/`div` moved into asn-transform core (`CoreFunctions`), legacy semantics.
- `FunctionRegistry.fromProviders(ctx, loader)` wires core + plugins; duplicate names
  fail loudly at load time. `LegacyFunctionBridge` is deleted — **no vendor names in
  core, and asn-golden no longer routes any transform through legacy code**.
- Gate held: full corpus sweep after the port produces a bit-identical `PARITY.md`
  (rows parity exactly as Phase 3).

## Phase 5 — hardening & future-ER groundwork

- **CER conformance**: `Strictness.CER` added (indefinite length required on constructed values,
  DER's minimal-length rule on primitives). CER's "SET components sorted by tag" is deliberately
  NOT in the codec — it is a component-sequence property, checkable at the binder; no input needs
  it yet. `Strictness` gained a third component, so `new Strictness(...)` callers must update.
- **Fuzz/property tests** (`BerFuzzTest`, fixed seeds): 500 random TLV trees round-trip
  (tag classes, short/long-form tags to 56 bits, lengths straddling the 0x80 and 0x100 edges,
  indefinite mixed with definite); **every** prefix truncation of 50 trees must raise
  `BerParseException`; 4000 single-byte mutations must either error cleanly or parse within
  bounds — never leak another exception, never read past the buffer.
- **Performance** (`Benchmark` in asn-golden, corpus-wide, legacy vs new, 2026-07-29).
  The **codec is 6–20x faster than legacy** (framing+BER only: 21k–1.24M rec/s vs legacy's
  5k–95k). Full decode *including schema bind* started at **0.7x legacy** — the binder, not
  the codec, was the bottleneck. Three fixes, each measured, took it to **1.2x overall with
  every corpus file now ≥1.0x** (was 0.4x–1.6x):
  1. `CompiledType.acceptedTags()` allocated a `HashSet` per tag comparison inside the
     per-child × per-component loops → memoized, plus a direct-tag fast path in `matches()`.
     0.7x → 0.9x.
  2. `DecoderRegistry.decode()` re-`normalize()`d (uppercase + strip spaces/dashes) every
     name in the chain **per leaf per record** — up to 9 string allocations before decoding
     anything. Added `resolve()` and cached the resolved `ValueDecoder` per `CompiledType`
     in the binder (one binder owns one registry, so resolution can't change under it).
     0.9x → 1.2x; the single biggest win.
  3. Component matching was O(children × components) — a linear rescan per child in
     `bindComponents`, `bindUnionFallback` and `selectAlternative`. Replaced by a memoized
     tag → ascending-positions index (`CompiledType.ComponentIndex`) that preserves the exact
     legacy match order (first component at-or-after the ordered cursor, else the wrap-around
     to an earlier one). Fixes the quadratic tail: zain_pgw 54k → 95k rec/s (0.9x → 1.1x),
     huwmsc 16 → 21 rec/s (0.8x → 1.1x), total 1.60s → 1.44s.

  Not pursued: per-node tag-path string building, measured at ~10% — the tag-path contract is
  worth more. What remains is leaf decoding and `NamedNode` allocation, which legacy pays too.
  ⚠ **This box is a shared sandbox and a single measured pass swings ±30%** — enough to invent
  or hide a regression (an early run "showed" a slowdown that was pure noise). `Benchmark` now
  warms up twice and reports **best of 3**; that reads stable to ±2%. Re-measure that way.
- **Docs** (in `../docs/`): [CONFIG_REFERENCE.md](../docs/CONFIG_REFERENCE.md),
  [PLUGIN_GUIDE.md](../docs/PLUGIN_GUIDE.md), [X680_SUBSET.md](../docs/X680_SUBSET.md),
  [ENCODING_RULES_SEAM.md](../docs/ENCODING_RULES_SEAM.md) (per-ER seam assessment — records
  that PER is the one ER needing an additive change: `CompiledType` must retain constraints),
  and [MIGRATION_STATUS.md](../docs/MIGRATION_STATUS.md).

## Phase 6 — engine adoption: public facade (in progress)

- **asn-facade** (new module, depends only on asn-core/asn-schema/asn-transform — never
  asn-golden or legacy-code): the public bytes→records API a future `com.gamma.parse.ParserPlugin`
  adapter builds on (docs/BACKLOG.md "ASN.1 adoption prerequisites", items 1–2).
  - `Asn1Decoder` — compile a grammar once (`compile`/`compileLenient`/`of`), then `decode(ByteSource, …)`
    lazily streams schema-bound `NamedNode`s (composes `RecordReader` + `SchemaBinder` exactly like
    `ParityCheck.checkFile`, now reusable outside the harness). `decodeToRows(...)` carries a record
    through `RecordMapper` + `LegacyTransformEngine` to flattened rows in one call.
  - `RecordMapper` — the `NamedNode`→legacy-record-map converter, promoted out of `asn-golden`
    (package-private there) so production code doesn't need to depend on the harness module.
  - Still open (items 3–6 of the same backlog entry): the declarative decode profile (replacing
    `GoldenCapture.CASES`), the `asn-parser-v2`/`asn-decoders` coordinate split, a drop-in
    `plugins/` jar directory, and the segments editor that unlocks guided Save for hierarchical
    parsers.

## Not yet built (per phase plan)
- `CompiledSchema` stable text serialization + `asn compile`/`asn dump` CLI (asn-cli module).
- `StreamSource` (pipes), `RESYNC(pattern)` recovery, CER canonical checks (Phase 5).
- The NEW transform config format (`meta`/`framing`/`outputs` sections, compiled
  expression AST, first-class lookups, real group/reduce) + `asn migrate`/`asn validate`.
- asn-sinks (CSV/JSONL writers).
- **Migrating the remaining ~21 grammars and deprecating `asn2`/`asn3`/`transformer2`** — blocked
  on sample data, not on code: only the 9 corpus cases have files to prove parity against, and
  every parity bug so far was invisible at compile time. Per-format gate and inventory in
  [MIGRATION_STATUS.md](../docs/MIGRATION_STATUS.md).
- Further decode throughput if ever needed: leaf decoding + `NamedNode` allocation are what
  is left (see Phase 5 above); the cheap structural wins are taken.
