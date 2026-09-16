---
type: Plan
title: Dead-property validation — a config key no component reads must FAIL
description: Design + as-built for DUCKLE-C3-DEAD-PROPERTY-1: the accepted-names map, the near-name suggestion, the x- escape hatch, the STRICT seam at /config/write + /config/patch, and the deferred WARNING seam at RecipeCompiler.
resource: inspecto-config/src/main/java/com/gamma/config/spec/AcceptedConfigKeys.java
tags: [config, validation, findings, pipeline, config-spec, backlog-row]
timestamp: 2026-09-16T00:00:00Z
---

# Dead-property validation (`DUCKLE-C3-DEAD-PROPERTY-1`)

A config key no component reads is a **silent loss**: the author writes it, the save answers
`written: true`, and the engine never looks at it. This plan turns that silence into a refusal.

## 1. Grounding (2026-09-16, re-verified against code)

Every fact the BACKLOG row asserts is **CONFIRMED**:

| Fact | Verdict | Evidence |
|---|---|---|
| No enforcement point today; `ConfigSafetyValidator.check` never iterates `raw.keySet()` | CONFIRMED | `ConfigSafetyValidator.java:73,96` — two `check` overloads, zero `keySet` occurrences in the file |
| `NodeAttributes` is a type→accepted-keys table but advisory/UI-only | CONFIRMED | `NodeAttributes.java:29-32` — an absent type *"falls back to the dialog's free-form key/value editor"* |
| `ComponentStore.encode` already does this job, CSV kinds only | CONFIRMED | `ComponentStore.java:191-213` — `CSV_DOC_KEYS`/`CSV_ROW_KEYS` allow-lists + `cannot persist key(s)` |
| No `x-` convention, no edit-distance helper anywhere | CONFIRMED | repo-wide grep: `x-` only in `inspecto-ui/node_modules`; no `levenshtein`/`editDistance`/`damerau` |
| The strict seam is a new `Finding` producer in `ConfigWriteRoutes` (write `:69-84`, patch `:364+`) | CONFIRMED | that is exactly the ERROR→422 findings list at `:69-86` and `:364-381` |
| `RecipeCompiler:254-260` refused a blanket unknown-key refusal on `collect:` | CONFIRMED | `RecipeCompiler.java:254-257` — *"a blanket unknown-key refusal here would break every existing recipe round-trip"* |
| `Finding` carries an optional stable `code`; `FindingCodes` exists | CONFIRMED | `Finding.java:35,61`; `FindingCodes.java` with `ERR_`/`WARN_` pairs per mechanism |

### 1.1 What the row's grounding MISSED — and it changes the design

🔴 **The census already exists, and it is already ratcheted.**
`inspecto-etl/src/test/java/com/gamma/etl/PipelineKeyCoverageContractTest.java` (landed 2026-08-31,
pipeline-spec gap 10) derives, from source, **every block `PipelineConfigParser` reads** and compares
it to **every block `ConfigSpecs.pipeline()` declares**. The residue is
`UNDECLARED_BLOCKS` — 16 entries, *"may only ever SHRINK"*. Current knowledge:
`docs/okf/backend/pipeline-graph/pipeline-config-keys.md`.

⇒ **The union `declared ∪ parser-only` IS the accepted-names map for `pipeline`.** It does not need
inventing; it needs *moving into production code* so a checker can enforce it and a doc can be
generated from it. Building a second, hand-written table beside it would recreate the very drift the
ratchet exists to prevent.

🔴 **Granularity is the BLOCK, and that is not a shortcut — it is a correctness constraint.**
`pipeline-config-keys.md` names the known leaf residue: `dirs.errors` / `dirs.quarantine` /
`dirs.markers` / `dirs.log_dir`, `collector.consignment.max_bytes` / `.order`,
`parsing.source_timezone`, `parsing.delimited.*` are **engine-read and spec-undeclared**. A
leaf-granular checker would 422 configs that run correctly today. ⇒ **the checker accepts a block and
does not descend into it.** Anything finer needs the leaf census to exist first, which it does not.

⚠ **Refinement of the row's wording.** The row says the map "MUST be per node type". At the STRICT
seam that is not the shape available: `/config/write` and `/config/patch` carry a **flat config of a
declared `type`** (`pipeline`/`alert`/`job`/…), never graph node types. Node types are the shape of
`PUT /pipelines/{name}/graph`, and `collect:` is a **recipe** verb that reaches no write route at all
(`RecipeCompiler.compile` is called only from `ConfigMigrator.java:156` and tests). So the map is
keyed **per config type**, and the `collect:` hard constraint is honoured a different way: the
accepted set for `pipeline` includes `collector` as a whole block, which is precisely the set of keys
`RecipeConverter` round-trips through `collect:`. Pinned by a test (§5).

## 2. The design

### 2.1 `AcceptedConfigKeys` (new, `inspecto-config`, `com.gamma.config.spec`)

The one map, and the only thing that may answer "does any component read this key?".

- `acceptedBlocks(type)` → for `pipeline`: `declaredBlocks(ConfigSpecs.pipeline()) ∪ PARSER_ONLY`.
  For every other type: **absent**, which means the checker is a no-op for it (fail-open by
  omission, stated, not accidental — see §6).
- `declaredBlocks(spec)` — a leaf `a.b.c` declares `a` and `a.b`. Lifted verbatim from
  `PipelineKeyCoverageContractTest.declaredBlocks()`, which now calls this instead of keeping a copy.
- `PARSER_ONLY` — the 16 blocks the parser reads and the spec does not declare.
  **`PipelineKeyCoverageContractTest.UNDECLARED_BLOCKS` is replaced by a reference to this field**, so
  there is literally one list: the ratchet keeps ratcheting it, the checker enforces it, the doc is
  generated from it. No drift is representable.
- `CENSUSED_PARENTS = {processing}` — the only block the checker descends one level into.
  🔴 **Found by a failing test, not by design review** (2026-09-16): the first cut descended into any
  block with a declared sub-block, which flagged `dirs.quarantine` / `dirs.markers` — engine-read keys.
  `dirs` has declared leaves *and* undeclared engine-read ones, and so does `collector`. The correct
  rule is **exactly the two scopes the ratchet scans** (the parser's `raw` and `proc` locals):
  top-level, and `processing.*`. Everything else is accepted whole.
- `unknownKeyFindings(type, raw, severity)` — the `Finding` producer.

### 2.2 The `x-` escape hatch

A block whose name starts with `x-` is **accepted unconditionally and never suggested against**. It is
the author's declared "this is mine, not the engine's" marker; the engine reads it nowhere and the
codec round-trips it verbatim. Pinned by a round-trip test through `ConfigCodec` (§5) — `x-` is a
JToon key like any other, and if the codec ever stopped carrying it the marker would become a silent
loss of its own.

### 2.3 Near-name suggestion

Plain Levenshtein, two-row DP, on the block name.

- Compare against every accepted block **at the same depth** (a top-level key never suggests a
  `processing.*` one — the suggestion must be something the author can actually write there).
- Threshold: `distance <= max(1, min(3, len/3))`. `procesing` → `processing` (1) suggests;
  `banana` → nothing within 3 of any accepted name, so **no suggestion**, and the finding says only
  what is wrong. This is the row's "no suggestion when nothing is close" and it gets its own test.
- Ties break on the shortest distance, then lexicographically, so the message is deterministic.

### 2.4 Codes

`FindingCodes.ERR_UNKNOWN_CONFIG_KEY` / `WARN_UNKNOWN_CONFIG_KEY` — the `ERR_`/`WARN_` pair idiom the
catalog already uses for a mechanism that fires at two severities (strict at validate, warning at
run). Registered under a new **Config keys** section: the existing five categories are all about a
config that is *wrong*; this one is about a config that is *ignored*.

### 2.5 Seams

| Seam | Severity | Status |
|---|---|---|
| `ConfigWriteRoutes.writeConfig` (`:69-86` findings list) | ERROR → 422 | **BUILT** |
| `ConfigWriteRoutes.patchConfig` (`:364-381` findings list) | ERROR → 422 | **BUILT** |
| `RecipeCompiler` at compile | WARNING | **DESIGNED, NOT BUILT** — §4 |

`PipelineGraphRoutes.saveGraph:291-316` runs the same gate shape and is the editor's write path. It is
**deliberately left out of this change**: `PipelineEditable.lower` overlays the graph onto the
existing file and preserves unmodeled keys wholesale, so a legacy file carrying an unknown block would
start 422-ing on an edit that never touched it. Adding it needs a migration pass over shipped
configs first; recorded as remaining work, not silently skipped.

## 3. The generated doc

`docs/okf/backend/pipeline-graph/pipeline-config-keys.md` gains a block delimited by
`<!-- generated:accepted-config-keys -->` … `<!-- /generated:accepted-config-keys -->`, rendered from
`AcceptedConfigKeys` by `AcceptedConfigKeysDocContractTest`. The test **fails with the exact expected
text** when the two disagree, so the doc cannot drift from the map the checker enforces — the row's
requirement, met without a build-time generator (the same reasoning `NodeAttributes.java:21-22` gives
for not generating its contract JSON: a generated artifact silently absorbs whichever side ran last).

## 4. Deferred: the WARNING seam at `RecipeCompiler`

Not built. The design, so the next shift does not re-derive it:

- The warning fires at **compile**, over the recipe's top-level verbs, NOT over `collect:`'s inner
  keys — `RecipeCompiler.java:254-257`'s refusal stands and this plan does not reopen it.
- `RecipeCompiler` produces `PipelineCompileException.Refusal`, not `Finding`; a WARNING has no
  refusal channel there today. ⇒ the seam needs a non-fatal diagnostic sink on the compiler before the
  warning has anywhere to go. **That is the actual blocker, and it is bigger than the warning.**
- Until it lands, an unknown block reaches the engine at run time exactly as it does today (ignored).
  The strict seam means it cannot get there through an authoring surface.

## 5. Tests

| Test | Proves |
|---|---|
| `AcceptedConfigKeysTest.suggestsTheNearName` | `procesing` → suggestion naming `processing` |
| `AcceptedConfigKeysTest.offersNoSuggestionWhenNothingIsClose` | `banana` → finding with NO suggestion (row requirement) |
| `AcceptedConfigKeysTest.xPrefixedKeysAreAccepted` | `x-owner` produces no finding |
| `AcceptedConfigKeysTest.xPrefixedKeysRoundTripThroughTheCodec` | `x-` survives `toToon` → `toMap` byte-for-byte |
| `AcceptedConfigKeysTest.aDeclaredBlockIsNotFlagged` / `everyParserOnlyBlockIsAccepted` | no false positive on a real config shape |
| `AcceptedConfigKeysTest.suggestionsStayAtTheSameDepth` | a `processing.*` key never suggests a top-level name |
| `RecipeCollectRoundTripTest` (inspecto-engine) | **the hard constraint**: a flat config with arbitrary `collector.*` keys survives `RecipeConverter.toRecipe` → `RecipeCompiler.compile`, and `AcceptedConfigKeys` flags none of it |
| `PipelineKeyCoverageContractTest` (existing, edited) | the ratchet now ratchets the production map |
| `ControlApiDeadPropertyTest` (inspecto, real HTTP) | `/config/write` 422 + code + suggestion; `/config/patch` 422; `x-` write succeeds |
| `AcceptedConfigKeysDocContractTest` | the doc block matches the map |

## 6. Deliberate deferrals (do not read these as oversights)

- **Three types have an accepted-names table: `pipeline`, `alert` (2026-09-16) and `meta`
  (2026-09-16).** `job`/`widget`/`dashboard`/`expectation`/`enrichment`/`schema` have no parser census,
  so their accepted set is unknown and the checker returns nothing for them. Inventing one by reading
  `ConfigSpecs` alone would refuse keys those parsers read — the exact leaf-granularity mistake §1.1
  rules out. Each needs its own census first.
  ⛔ **`widget` and `dashboard` are NOT a census away** and should be struck from the "remaining" list:
  neither is ever written through `/config/write` (the UI saves both through `POST`/`PUT
  /components/{kind}`), so a table for them would be a no-op. Their gate belongs in `ComponentRoutes`,
  and the persisted body carries `name`/`owner`/`shares`, which no `ConfigSpec` declares.
  ⇒ the real remaining census list is **four**: `enrichment`, `job`, `schema`, `expectation`.
- **Leaf-level checking inside a declared block.** Blocked on the leaf census (§1.1).
- **`PipelineGraphRoutes`.** Blocked on a migration pass (§2.5).
- **The `RecipeCompiler` WARNING seam.** Blocked on a non-fatal diagnostic sink (§4).
