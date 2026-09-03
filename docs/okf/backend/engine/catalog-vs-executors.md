---
type: Concept
title: Step Processor catalog vs. real node executors — the mapping/transform family
description: Why 5 catalog "processors" (schema validator, whitespace sanitizer, expression builder, cast/rename matrix, lookup transcoder) collapse onto only two node types (transform.map, transform.join) with no per-processor Java class.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/ProcessorCatalog.java
tags: [engine, catalog, transform, node-types, gotcha]
timestamp: 2026-09-03T00:00:00Z
---

# Step Processor catalog vs. real node executors

The [Step Processor catalog](../../frontend/features/pipeline-editor.md) (121 entries, 8 families,
`ProcessorCatalog.java`) is a **taxonomy/palette layer**, not a 1:1 map to engine code. Five catalog entries
that read like a "Mapping and Transformer" family — spanning the `DQ` and `XFM` families — all resolve to
just two [node types](node-types.md): `transform.map` (4 of them) and `transform.join` (1 of them). There is
no dedicated Java class, config schema, or dispatch path per catalog id.

## The five entries

| Catalog id | Family | Status | `nodeType` | Catalog note |
|---|---|---|---|---|
| `quality.schema.validator` | DQ | DELIVERED | `transform.map` | "the schema registry: typed fields, TRY_CAST, structural rejects → quarantine" |
| `quality.cleanse.trim` | DQ | PARTIAL | `transform.map` | "any `EXPR` rule does it today; no dedicated step" |
| `transform.expression` | XFM | DELIVERED | `transform.map` | "the `EXPR` / `CONCAT_DT` / `FILENAME_DATE` rules" |
| `transform.cast` | XFM | DELIVERED | `transform.map` | "the mapping rows (`DIRECT` + typed target)" |
| `transform.lookup` | XFM | PARTIAL | `transform.join` | "a reference join covers it; no inline static map" |

(`ProcessorCatalog.java:98,106,113,114,119`)

## `transform.map` group (4 of the 5)

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
  (`PipelineNodeExecutors.get(type)`) first, then a hardcoded `if (BuiltinNodeType.X.equals(type))` chain,
  else throws. No per-catalog-id branch exists; several catalog ids fan into the same `transform.map`
  branch.
* **Palette activity is gated on `Status.PLANNED` only** — `DELIVERED` and `PARTIAL` both render as
  active/addable in the palette (`ProcessorCatalog.java:12-14`). So `quality.cleanse.trim` and
  `transform.lookup` look fully live in the UI despite being partial implementations.
* Searched broadly for a class named for any of these 5 processors ("schema validator", "whitespace
  sanitizer", "expression builder", "cast matrix", "lookup transcoder") — none exist. This is a confirmed
  gap, not an unexplored one.
