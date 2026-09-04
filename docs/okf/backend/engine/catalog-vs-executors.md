---
type: Concept
title: Step Processor catalog vs. real node executors — the mapping/transform family
description: Why 5 catalog "processors" (schema validator, whitespace sanitizer, expression builder, cast/rename matrix, lookup transcoder) collapse onto three node types (transform.map, transform.sql, transform.join) with no per-processor Java class — and the transform.sql executor, TypeFlow.describe and the SQL_STEP_UNAUDITED boundary.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java
tags: [engine, catalog, transform, node-types, gotcha]
timestamp: 2026-09-04T00:00:00Z
---

# Step Processor catalog vs. real node executors

The [Step Processor catalog](../../frontend/features/pipeline-editor.md) (121 entries, 8 families,
`ProcessorCatalog.java`) is a **taxonomy/palette layer**, not a 1:1 map to engine code. Five catalog entries
that read like a "Mapping and Transformer" family — spanning the `DQ` and `XFM` families — all resolve to
three [node types](node-types.md): `transform.map` (2), `transform.sql` (2 — repointed 2026-09-04 when the
SQL transformer v1 shipped, see below) and `transform.join` (1). There is no dedicated Java class, config
schema, or dispatch path per catalog id.

## The five entries

| Catalog id | Family | Status | `nodeType` | Catalog note |
|---|---|---|---|---|
| `quality.schema.validator` | DQ | DELIVERED | `transform.map` | "the schema registry: typed fields, TRY_CAST, structural rejects → quarantine" |
| `quality.cleanse.trim` | DQ | PARTIAL | `transform.map` | "any `EXPR` rule does it today; no dedicated step" |
| `transform.expression` | XFM | DELIVERED | `transform.sql` | "computed columns as SELECT expressions in the SQL Step; the `EXPR` / `CONCAT_DT` / `FILENAME_DATE` map rules remain" |
| `transform.cast` | XFM | DELIVERED | `transform.sql` | "type casts stay on the Parse step's Types section (declarative typing); renames/aliases via the SQL Step" |
| `transform.lookup` | XFM | PARTIAL | `transform.join` | "a reference join covers it; no inline static map" |

(`ProcessorCatalog.java:98,106,113,114,119`; the two `transform.sql` rows and their mirror in
`processor-catalog.contract.json` were repointed on 2026-09-04 — the catalog contract test pins the pair.)

## `transform.sql` — the SQL transformer v1 (SHIPPED 2026-09-04, `98ffc90b` engine · `7e13dd82` pane)

**The one fact that shaped it:** the raw relation is deliberately **ALL-VARCHAR** (`read_csv` is issued with
explicit `columns={'c0':'VARCHAR',…}` so the Java and DuckDB ingest paths agree on types), so `SELECT * FROM
raw` carries no types until something casts — and whatever casts *is* the mapping. It has to stay a
declarative rule table for three non-stylistic reasons: the cast-failure audit counts `source non-blank AND
result IS NULL` per rule and needs a declared source column + target type as its denominator (`EXPR` is
excluded by definition); `TRY_CAST`/`TRY_STRPTIME` null one cell where a hand-written `CAST` kills the whole
batch; and `SchemaCompatibility`'s BACKWARD contract needs a stable schema to diff against. Hence the
**two-layer split**, decided 2026-09-03 (`sql-transform-v1-plan.md`, archived; grounding in
`sql-only-transform-feasibility.md`, archived beside it):

- **Layer 1 — typing** stays on the Parse step (Types section, declarative, `TRY_CAST` audit). Unchanged
  mechanism — this IS the "Schema validator & type coercion" processor.
- **Layer 2 — transforming** is ONE SQL `SELECT` over the **typed** output of Layer 1, where resultset
  metadata is finally meaningful. That is `transform.sql`.

As built:

* **Node type:** `BuiltinNodeType.TRANSFORM_SQL("transform.sql", TRANSFORM, "SQL")`
  (`BuiltinNodeType.java:118`) — `DATA` in, `DATA` out, single-input; a `project()`-class verb like
  `map`/`select`/`derive`, never a split. ⛔ **Filtering stays `transform.filter`** (operator D3): a `WHERE`
  buried in free SQL would discard rows silently, where `transform.filter` emits them as `DROPPED` for
  quarantine/audit. There is deliberately no `where` attribute on this node.
* **Attributes:** exactly one — `sql` (`multiline`, required; `NodeAttributes.java:374-379`, mirrored in
  `node-attributes.ts:347-353`, pinned by `NodeAttributesContractTest`). Help text pins the convention: the
  input relation is addressed by the fixed alias **`input`** (`FROM input`); no DDL/DML, no multiple
  statements. The Angular pane also stores a `fields[]` block beside `sql` (the Simple-mode rows that
  *generated* the SQL) — an **authoring artifact, not an engine contract**: the engine reads only `sql`,
  and a node without `fields[]` is a hand-written ("locked") one. See
  [pipeline-editor.md](../../frontend/features/pipeline-editor.md) for the pane.
* **Executor:** `RowShaper.shape()` gains one branch (`RowShaper.java:174`) → `sql(conn, node, input,
  outPrefix)` (`:494-512`): the statement passes `SqlGuard.check` (allow-list — anything that is not a
  single `SELECT` throws naming the node), then `CREATE OR REPLACE TEMP VIEW input AS SELECT * FROM <in>`
  binds the alias, `CREATE TABLE <out> AS <sql>` materializes exactly one relation (`PipelineRel.DATA`),
  and the view is dropped in a `finally`. It runs on the same sealed [`SqlSandbox`](duckdb.md) connection
  `EXPR` uses — no file/network access, no extension autoload.
* **Derived output schema without execution:** `TypeFlow.describe(List<Column> inputColumns, String sql)`
  (`inspecto-etl/.../TypeFlow.java:92-111`) generalizes `transformedColumns`: an in-memory connection, a
  scratch table literally named `input` shaped by the **upstream Step's typed schema**, then `DESCRIBE
  <sql>` → `[{name, type}]`. A binder/parse failure surfaces as `IllegalArgumentException` carrying
  DuckDB's message verbatim (it names the offending column — that IS the validation). No rows are ever
  read. **Exposed since 2026-09-04 as `POST /components/transform/describe`**
  (`ComponentRoutes.describeTransform`) — body `{ sql, inputColumns: [{name, type}] }` → `{ columns:
  [{name, type}] }`, 400 on a malformed body, 422 on the guard's reason or DuckDB's binder message (with the
  driver's "closed pending query result" wrapper line stripped — `duckDbMessage`, pinned by test).
  ⚠ Callers must send the DECLARED input types: VARCHAR-for-everything makes the binder refuse valid
  arithmetic over a typed column. It
  sits with the other `/components` previews (un-gated: reads nothing, writes nothing) rather than at the
  planned `POST /pipelines/authored/{id}/nodes/{nodeId}/describe`, because it needs no pipeline and no
  node — the pane has the SQL and the upstream columns in hand. ⚠ **`TypeFlow.describe` opens a plain
  in-memory DuckDB connection, NOT the sealed [`SqlSandbox`](duckdb.md) the executor and the preview run
  on**, so the route applies `SqlGuard.check` itself before calling it: `DESCRIBE` never executes the
  plan, but the binder still OPENS a `read_csv('…')` target to infer its schema, which would have made
  this the one author-SQL entry point that reads arbitrary files. Guarding at the route also keeps
  describe and execute agreeing — SQL refused at run time is refused while it is being written
  (`ControlApiComponentsTest.describeRefusesFileReadingSqlTheExecutorWouldAlsoRefuse`, whose probe is a
  CSV that really exists, so an un-guarded route would answer 200).
* **Audit honesty at the boundary:** `transform.sql` is, by construction, outside the cast-failure audit
  (the same reason `EXPR` is). `PipelineValidator.validate` emits one WARNING `SQL_STEP_UNAUDITED`
  (`PipelineValidator.java:86, 263-267`) per `transform.sql` node, naming the node id and its `sql`
  attribute. Unlike the 2026-08-29 `EXPR` fix, **no `clean`/gate bug was found alongside it**:
  `PipelineValidator.Result.ok()` and `PipelineGraphRoutes.saveGraph`'s findings gate already keyed off
  `Severity.ERROR` only — proven, not trusted, by `PipelineValidatorTest`
  (`theUnauditedSqlWarningAloneDoesNotBlockSave`, `anActualErrorOnAGraphWithASqlStepStillBlocksSave`).
* **Recipe view:** since 2026-09-04 `transform.sql` IS a recipe verb — `sql`, ordered between `transform`
  and `summarize` in `PipelineProjection.RECIPE_VERBS` and in the UI's `RECIPE_VERBS` and generated
  `step-types.contract.json` (regenerate with `mvn -o test -Dstep.types.write=true`, pinned by
  `StepTypesContractTest`). `RecipeCompiler` compiles `sql: {sql, fields}` on the trunk and inside a
  `route:` branch; `RecipeConverter.sqlStep` converts it back, so a config carrying a sql step round-trips
  through the Recipe view instead of failing with `UNSUPPORTED_STEP`. ⚠ **Mid-branch, `sql` compiles but
  does not ARM:** `RouteArming.BRANCH_STEP_KINDS` is `{FILTER, DEDUP, SUMMARIZE}`, so a route branch
  chaining a sql step is a save-time finding and an `IllegalStateException` from
  `PipelineConfig.prepare()` once active — deliberate (fail-closed until the branch walker runs it), and
  the compiler still accepts it so the round-trip of an inactive draft stays lossless.

**Where the catalog's processors land after v1** (operator's question, answered):

| Catalog entry | Home |
|---|---|
| Schema validator & type coercion | Parse step, Types section (Layer 1, declarative, unchanged) |
| Whitespace & string sanitizer | `TRIM(x)` in the SQL — the Simple mode's "Remove extra spaces" verb |
| Expression builder & computed columns | **is** `transform.sql` |
| Field type cast & renamer matrix | splits: cast → Layer 1; rename → the editable Name (alias) in the Simple grid |
| Lookup & static map | `transform.join` stays for Reference datasets (v2 may allow `JOIN` inside the SQL) |
| Summarizer | same shape — `transform.sql` with `GROUP BY`; `transform.summarize` keeps its `measures`/`group_by` validators for the structured form, not deleted |

**Parked with a recorded decision (BACKLOG AUTHORING-REDESIGN-1, not this file):** v2 smart table over the
AST (`json_serialize_sql`/`json_deserialize_sql`) — 🔴 it lives in the `json` extension and
`SqlSandbox.seal()` sets `autoload_known_extensions=false`; `json` is *normally* statically linked in the
JDBC jar, but so was assumed of `excel`, which is NOT — measure on the sealed connection before designing.
v3 macros as the UDF registry — measured 2026-08-29 on `duckdb_jdbc 1.5.2.1`: `CREATE MACRO` (scalar and
table) works and **survives the seal**, but `DuckDBConnection` exposes **no** Java-side scalar-UDF API, so
"UDF" can only ever mean a SQL macro, per-connection, re-created on every scratch connection
(`EnrichmentEngine`, `PipelineJobRunner`, `BatchIngestStrategy`, preview) and needing a component kind +
registry that do not exist. Dynamic/environment values — none in v1; when needed, render at read time
(the standing rule) or `SET VARIABLE`/`getvariable()`.

## `transform.map` group (2 of the 5 today; 4 before 2026-09-04)

* **No `NodeAttributes`/`AttributeSpec` entry exists for `transform.map`** — deliberately absent
  (`NodeAttributes.java:28-31`, and `PipelineEditable.java:115`: "the lift emits it as the schema"). Its
  config surface is informal `columns`/`rules` keys documented at `RowShaper.java:53-55,69-77`, not a
  declared spec.
* **Engine:** `DataTransformer.materialize()` (`inspecto-etl/.../DataTransformer.java:50`) builds the
  transformed table. Type coercion is `TRY_CAST`; a failed cast is counted + WARN-logged and the row is
  **kept with NULL** (`DataTransformer.java:202-204`) — it is NOT routed to quarantine, despite the catalog
  note for `quality.schema.validator` implying a reject path.
* **Rule kinds** (`transformType`) are compiled by `TransformCompiler` (`inspecto-etl/.../TransformCompiler.java:14-19`):
  `DIRECT` (default), `EXPR` (free-form author SQL — this is literally what "whitespace sanitizer" reduces
  to, e.g. `TRIM(col)`), `CONCAT_DT`, `FILENAME_DATE`.
* **"Cast & rename matrix"** is nothing more than a `DIRECT` rule where the row's `targetColumn` differs
  from the source field name — no separate rename mechanism exists.
* **UI:** bespoke rule-grid component `app-pipeline-load-definition`
  (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-load-definition.component.ts`) — NOT the generic
  `<inspecto-schema-form>` used by attribute-spec-driven node types.
* **Persistence:** rules save as a sidecar CSV next to the schema file — `x_schema.toon` → `x_mapping.csv`
  — via `MappingCsv.siblingFor()` (`inspecto-util/.../MappingCsv.java:34-42`), pushed through
  `PUT /pipelines/{name}/graph`.
* **Dispatch:** hardcoded in `RowShaper.shape()` (`RowShaper.java:171-173`) → `project(...)`, after the
  optional `PipelineNodeExecutor` SPI seam (`RowShaper.java:162-163`) is checked and found empty.

## `transform.join` (the 5th — "Lookup & Static Map Transcoder")

* Only the **reference-join half is real**; there is no inline literal key→value static map anywhere in
  the engine — confirmed by the catalog's own `PARTIAL` status and note.
* **Config schema exists and is real** (unlike the map group):
  `NodeAttributes.TRANSFORM_JOIN` (`NodeAttributes.java:361-366`) — `reference` (autocomplete, required,
  points at a registered `reference/<id>` component) and `on` (list, required, join key columns).
  Registered at `NodeAttributes.java:384`; kept in sync with the Angular mirror
  (`node-attributes.ts:326-343,357-359`) by `NodeAttributesContractTest`.
* **Engine:** `RowShaper.join()` (`RowShaper.java:442`, LEFT JOIN semantics doc'd at lines 46-49) resolves
  the reference via a `ReferenceResolver` seam, throwing if unresolvable or if `on` is missing
  (`RowShaper.java:97,455`).
* **Authoring-only / at-rest gated** — refused mid-branch, since no reference resolver exists on the
  ingest lane (`BuiltinNodeType.java:137-141`).
* **UI:** the generic path — `app-pipeline-config-definition` rendering `<inspecto-schema-form>` off the
  server-published `AttributeSpec[]` (`GET /pipelines/node-types`).
* **Dispatch:** `RowShaper.shape():164`, before the `transform.map` branch.

## Execution philosophy: SQL builders over embedded DuckDB, not row-by-row Java

None of the 5 processors run row-by-row Java logic. `RowShaper` and `DataTransformer` are **SQL builders**
that generate a `SELECT`/`JOIN` and hand it to the embedded [DuckDB](duckdb.md) connection — DuckDB does all
the actual execution:

* **Type coercion** (`quality.schema.validator`) is DuckDB's native `TRY_CAST` function, spliced into the
  generated `SELECT`. There is no Java-side type-checking pass. This is also *why* a failed cast can't be
  routed to quarantine: `TRY_CAST` returns SQL `NULL` inline, in the same query, with no per-row branch
  point for Java to intercept — the NULL count is only measured after the query returns
  (`DataTransformer.java:202-204`).
* **Whitespace sanitizing / expression building** (`quality.cleanse.trim`, `transform.expression`) are raw
  SQL fragments (`TRIM(col)`, `CONCAT`, date functions) spliced verbatim into the generated column list by
  `TransformCompiler` — DuckDB parses and runs them like any other SQL expression, not a distinct transform
  pipeline stage.
* **Cast & rename** (`transform.cast`) is column aliasing in the same generated `SELECT` — `SELECT
  src_col AS target_col` — no separate rename mechanism.
* **Lookup** (`transform.lookup`) is a DuckDB `LEFT JOIN` between the row relation and the resolved
  Reference dataset's relation (`RowShaper.join()`), keyed on `on`. DuckDB executes the join; Java only
  resolves which reference table to bind and builds the join SQL.

The consequence: the catalog's 5 "processors" are product labels for different **shapes of generated SQL**,
not 5 pieces of transform logic. Adding a genuinely new processor here means either (a) adding a new
`transformType` to `TransformCompiler`'s SQL-fragment registry, which still executes as DuckDB SQL, or (b) a
real engineering effort to add a new node type with its own Java-side row processing — which is what the
missing "inline static map" half of `transform.lookup` would require, since a literal key→value map is not
naturally expressible as a join against a stored relation.

## Cross-cutting

* **Dispatch architecture:** `RowShaper.shape()` (`RowShaper.java:155-183`) — SPI seam
  (`PipelineNodeExecutors.get(type)`) first, then a hardcoded `if (BuiltinNodeType.X.equals(type))` chain
  (`transform.sql` at `:174`), else throws. No per-catalog-id branch exists; several catalog ids fan into
  the same `transform.map` or `transform.sql` branch.
* **Palette activity is gated on `Status.PLANNED` only** — `DELIVERED` and `PARTIAL` both render as
  active/addable in the palette (`ProcessorCatalog.java:12-14`). So `quality.cleanse.trim` and
  `transform.lookup` look fully live in the UI despite being partial implementations.
* Searched broadly for a class named for any of these 5 processors ("schema validator", "whitespace
  sanitizer", "expression builder", "cast matrix", "lookup transcoder") — none exist. This is a confirmed
  gap, not an unexplored one.
