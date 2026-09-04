---
type: Plan
title: Record Transformer replaces transform.map — one authoring model for the projection slot
status: ACTIVE — approved 2026-09-04, Phase 1 in progress
timestamp: 2026-09-04T00:00:00Z
---

# Plan: Record Transformer replaces `transform.map` — one authoring model for the projection slot

## Context

`transform.map` is the last surface still authored the old way. The catalog fold (`b603e791`) made
**Record Transformer** the single entry for sanitize/cast/rename/compute, but it only *runs* on the
at-rest lane — so Map still exclusively owns the **ingest lane's cast layer**, and every stored pipeline
still lifts a Map node.

Retiring Map cannot be a deletion: it is not a chain step. `PipelineEditable.STEP_KIND` deliberately
omits it — *"the lift emits it as the schema projection between parser and sink, so it never enters the
chain"* — and `PipelineLift.java:313` creates one **unconditionally** for every pipeline. Map is a
**slot**, not a step. So the work is to make that slot accept the Record Transformer's model.

The operator endorsed *"unify the authoring model"*: one grid, one model, engine picks the lane. This
plan does that for the projection slot only — it does **not** merge the execution lanes (that stays the
separate §7.1 question).

## The finding that makes this tractable

**Both lanes already share one Java SQL compiler.** `RowShaper.columnsOf` (at rest) calls
`DataTransformer.dataColumns(schemaConfig, csv, sourceTable)` — its own comment: *"the same authority the
legacy engine's own SELECT uses"* — and `DataTransformer.selectFor` (ingest) assembles from that same
list. One seam, `[{name, expr}]`, feeds both lanes.

So this is not "make Record Transformer run on the ingest lane". It is **"teach that one compiler a
second input spelling"**, and both lanes light up together.

🔴 **The one real gap:** there is **no Java mirror of the function catalog** — `sql-functions.ts` (21
entries) is frontend-only. `generateSql(fields)` (`pipeline-transform-sql.ts:81-87`) builds the SELECT in
TypeScript, and Java stores it verbatim. That mirror is the substance of Phase 1.

⛔ **This plan deliberately reverses a currently-documented rule, and that must be explicit.** Four places
state that `fields[]` is an authoring artifact the engine never reads —
`pipeline-transform-sql.ts:12-15`, `sql-functions.ts:15-17`, `PipelineEditable.java:653-654` and
`PipelineConfig.java:307-311`. Phase 1 makes `fields[]` **engine-read**, so every one of those comments
moves with the change or the next reader is misled.

⚠ **Why not the obvious alternative.** The naive substitution — point the projection slot at a
`transform.sql` node and let the ingest lane run its stored `sql` — is **refused**. It loses exactly the
two properties Map exists to provide: `DataTransformer.dataColumns`' schema-driven compilation *with the
pipeline's date/timestamp format lists and `SourceZones`* (`RowShaper.java:565-593`), and coverage by the
cast-failure audit — which is why `PipelineValidator.java:263-268` raises a standing `SQL_STEP_UNAUDITED`
warning on every `transform.sql` node today. Compiling from `fields[]` in Java keeps both; consuming the
string keeps neither.

✅ **The audit survives, and this is the argument for the whole approach.** `countCastFailures`
(`DataTransformer.java:165`) needs exactly two things per rule: a resolvable **source column**
(`coercedSourceColumn` — returns null for `EXPR`, which is why EXPR is skipped) and the **compiled
expression**, then emits `SUM(CASE WHEN source non-blank AND expr IS NULL …)`. A `fields[]` row carries
`from` (the source) and compiles to an expr — so the denominator transfers unchanged, and a
`fn: custom` row is skipped for exactly the reason `EXPR` is. **A structured grid preserves the audit
that raw SQL destroys** — the reason typing stayed declarative in the first place.

## Phases

### Phase 1 — the Java function catalog + compiler (the whole unlock)

New `RecordTransform` in `inspecto-etl` (beside `TransformCompiler`, which it parallels):

- A catalog mirroring `sql-functions.ts` (21 entries across Keep · Text · Numbers · Dates · Logic ·
  Convert · Custom): id → `template` with `{source}` + one `{param}` per declared param.
- `compile(fields, fieldTypes, csv, zones, sourceTable) → List<{name, expr}>` — the **same return shape
  as `DataTransformer.dataColumns`**, so it drops into the existing seam. A field row is
  `{id, name, from, fn, args}` (`pipeline-transform-sql.ts:19-30`).
- Mirror the TS rendering pipeline exactly: `renderExpression` (`sql-functions.ts:324-345`) →
  `quoteIdentifier` for `{source}` (`:283-285`), `renderParam` per param type (`:299-318`) with
  `quoteLiteral` for text (`:288-290`). 🔴 The param **type** drives the escaping, so a `text` param
  quoted as an identifier (or vice versa) is an injection-shaped bug — port the type dispatch, not just
  the templates.
- Reuse `TransformCompiler`'s existing machinery for the typed paths (DATE/TIMESTAMP go through the
  `TRY_STRPTIME` chain and `SourceZones` exactly as rules do — do not re-implement coercion).
- ⛔ Every cast emits `TRY_CAST`, never `CAST` — a hand-written `CAST` kills the whole batch where
  `TRY_CAST` nulls one cell. In the catalog today only `convert.type` casts (`sql-functions.ts:237-244`),
  with `date.parse` → `TRY_STRPTIME` and `num.divide` → `NULLIF(divisor,0)` following the same
  forgiving-by-construction rule (`:19-21`). Preserve all three.

**Contract test** pinning the Java catalog against `sql-functions.ts`, following the house pattern
(`ProcessorCatalogContractTest` / `StepTypesContractTest` / `NodeAttributesContractTest`): a committed
JSON contract, regenerated with `-D…write=true`, asserted from both sides. Without this the two catalogs
drift and a field silently compiles differently per lane.

### Phase 2 — wire both lanes through the seam

- `DataTransformer.dataColumns` / `selectFor`: when the schema carries `fields[]`, compile through
  `RecordTransform`; else the existing `mapping.rules[]` path. ✅ `selectFor` has **only two callers**
  (`DataTransformer:69`, `TypeFlow:45`), so this one branch leaves all **five** `materialize` call sites
  (`CsvIngestStrategy:163`, `NativeCsvStreamingEngine:136,267`, `UnionModeIngester:147`,
  `DuckDbRecordSink:271`) untouched. Mirror the shape `RowShaper.columnsOf:567-572` already uses —
  authored wins, else fall back.
- `RowShaper.columnsOf`: same branch, so the at-rest lane uses the identical compiler instead of the
  stored `sql` string.
- 🔴 **`countCastFailures` must be branched SEPARATELY — it does not go through `selectFor`.** It reads
  `schema.mapping.rules[]` directly (`DataTransformer:173-174`), so a `selectFor`-only branch would leave
  the audit iterating an empty list and silently reporting 0 failures on every `fields[]` pipeline. Give
  it the same two-spelling switch, or refactor both onto one compiled
  `[{source, expr, target}]` list.
- 🔴 **The ingest graph-fork admission assumes the seed is a Map node** —
  `ConsignmentIngestStrategy.seedFeedingTheWrite:430-448` / `flatReason:211-213` require
  `transform.map` to be the node feeding the write, on the reasoning that anything else "would have to
  EXECUTE at rest". Once the projection slot can be `transform.sql`, that predicate must accept it too,
  or every converted pipeline silently drops to the flat lane.
- **`SQL_STEP_UNAUDITED` becomes conditional** (`PipelineValidator.java:263-268`).
  A node compiled from `fields[]` *is* audited, so the standing warning must fire only for a
  hand-written-`sql` node; leaving it unconditional tells authors the opposite of what now happens.

⚠ After this, a Record Transformer runs on the **ingest** lane — which is the actual thing Map does.

**Two audit subtleties to preserve exactly** (both from `coercedSourceColumn:218-232`):
- The declared type is looked up by the **source** column against `raw.fields[]`, not the target, and a
  column whose declared type is VARCHAR is **skipped** (`SchemaFieldTypes.coerces` = `!VARCHAR`) — a
  pass-through cannot null out. Port that skip, or the count inflates.
- `from` is a **single** column (`pipeline-transform-sql.ts:25`), so a two-source function (the
  `CONCAT_DT` analogue) can only test one of its inputs for non-blankness. That is a real narrowing
  versus today; record it rather than pretending parity.

### Phase 3 — a home for `fields[]` in the flat file

- `PipelineConfig.MapConfig(columns, rules)` gains `fields` (`PipelineConfig.java:1026`), read from
  `processing.map`. Keep `columns`/`rules` readable forever; stop *writing* them.
- `PipelineLift`: when `fields[]` is present, emit a `transform.sql` node in the projection slot instead
  of `transform.map`; otherwise emit Map exactly as today. `PipelineEditable.lower()` mirrors it.
- ⛔ Do NOT give the projection node a `STEP_KIND` entry — that would change *when `steps:` is emitted at
  all* (AUTHOR-1's ⛔). The slot stays a slot.

**Cascading — asymmetric by design, and deliberately left that way (operator Q, 2026-09-04):**
- **At rest it already works.** `steps:` is a list, `transform.sql` HAS a `STEP_KIND` (`sql`), and
  `PipelineLift.stageTwo:187-196` emits one node per entry with a repeat-id scheme (`sql__s2`, …) so the
  same verb may appear many times. `src → sql → sql → sink` needs nothing new.
- **On ingest there is exactly ONE projection slot** (`PipelineLift:313`), so no cascade. ⚠ That is not
  cosmetic: N projections do NOT collapse into one SELECT, because SQL cannot reference a sibling alias
  in the same select list (`SELECT TRIM(x) AS a, UPPER(a) AS b` is invalid). On ingest an author must
  nest functions in one field or use `custom`.
- 🔴 If an ingest cascade is ever wanted, the mechanism already exists: `materialize:77-79` wraps its
  projection in `SELECT * FROM (<select>) AS __shaped WHERE …` for the row filter, for exactly this
  aliasing reason. An N-deep cascade is "wrap N times", not a new concept. **Out of scope here** — a
  single ingest slot is what Map does today, which keeps Phase 4 a like-for-like swap.

### Phase 4 — migration + deprecation (⛔ NOT deletion)

- A converter `mapping.rules[]` → `fields[]`: `DIRECT` → `keep`, `EXPR` → `custom`,
  `CONCAT_DT` / `FILENAME_DATE` → their catalog functions (these pack args in a `|`-delimited string —
  unpack into typed `args`).
- Round-trip test **per stored schema** across all three spaces (`default`, `demo`, `ucc`) — 10+ files —
  asserting the compiled SELECT is byte-identical before and after conversion. That equality is the
  migration's whole safety argument.
- Mark `transform.map` deprecated in `BuiltinNodeType` + a banner on the Load pane pointing at Record
  Transformer.

🔴 **`mapping.rules[]` stays READABLE permanently — deletion is out of scope and should stay out.**
Roughly **twenty** sites read it beyond `DataTransformer`: `Identifiers:141-152` (identifier safety
gate), `PipelineConfigParser:1495-1499` (`columnNamesOf`, feeds the `reference.key` check) plus its two
producers `mergeSiblingMapping:1245-1250` / `applyMappingFile:1276-1281`, `RowShaper.mappingSchemaOf`,
`MappingRules`, `PipelineLift:311`, the `mapping` component kind (`ComponentStore:197,294`,
`ComponentRegistry:155`), `ComponentPreview:645-652`, `PipelineDocument:168-180`,
`ConfigPreviewRoutes:280-289,332`, `ComponentRoutes:600-615`, `ConfigWriteRoutes.splitMapping:211-226`,
`ConfigFileSupport:126-137`, `PipelineGraphRoutes:171`, `SchemaExtractor:183-194`, and
`ConfigSafetyValidator.checkMapEntries:203-212`. Stop *writing* it; never stop reading it.

⚠ **`raw.fields[]` survives regardless** — `TransformCompiler.partitionColumn` / `eventTimeColumn`
(`:244-261`) need the declared types even when no mapping exists, and `SchemaProjection:32-49` projects
the Catalog's columns from it. Only `mapping.rules[]` is being superseded, never the field declarations.

## Verification

- `mvn -o clean test` — baseline **3971/0/0/5 across 17 test-bearing modules** (2026-09-04); report the
  mechanical total, never an eyeballed sum.
- New backend tests: the Phase-1 contract test; a compile test per catalog function; an audit test
  proving `fn: custom` is skipped and a `convert.type` row is counted; the Phase-4 per-schema round-trip.
- **End-to-end, both lanes, on real files** — the check that actually matters:
  1. Convert `csv_example` to `fields[]`, run the flat CLI (ingest lane) per `build-verify`, and assert
     the parquet output and `cast_failures` are identical to the pre-conversion run.
  2. Run the at-rest chain (`output_store:` + a `pipeline_config:` job) over the same data.
  🔴 Clear `.processed` markers and prior output first — `duplicate_check` means a re-run otherwise skips
  every file and still exits 0.
- ⚠ Rebuild + restart before judging anything in the browser: `mvn clean test` never refreshes
  `inspecto-deploy/inspecto.jar`, and a Java-served route 404s into the SPA's `index.html`, which the
  palette swallows as a silent fallback.

## Sequencing

One commit per phase, each verified before the next.

Phase 1 is the only hard part and is independently verifiable (a compiler + contract test, no behaviour
change). Phase 2 is wiring but carries the three sharp edges above — the separate audit branch, the
graph-fork admission predicate, and the conditional warning. Phase 3 is the config home. Phase 4 lands
with its round-trip evidence.

**Do not delete `transform.map`, and do not delete `mapping.rules[]`.** Deprecate and migrate; the
deletion is a separate decision once nothing lifts a Map node, and ~20 readers say it should stay
possible to read old configs indefinitely.

**Prior art to read first:** `docs/archived-documents/plans-archive/sql-only-transform-feasibility.md`
§2/§4 — the grounded 2026-08-29 analysis of this exact question, whose audit-denominator objection this
plan answers by compiling from `fields[]` rather than from a SQL string.
