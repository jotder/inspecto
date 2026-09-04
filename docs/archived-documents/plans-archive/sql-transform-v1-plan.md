---
type: Plan
title: SQL transformer v1 — one SELECT over the typed source, DESCRIBE-derived output schema
status: SHIPPED 2026-09-04 — B1+B2+B5 engine `98ffc90b`, B3+B4 Transform pane `7e13dd82`; archived 2026-09-04. As-built truth: okf/backend/engine/catalog-vs-executors.md, okf/frontend/features/pipeline-editor.md, okf/frontend/features/schema-mapping-authoring.md; open follow-ons in docs/BACKLOG.md §4 (AUTHORING-REDESIGN-1). Parked v2/v3 items are BACKLOG rows, not this plan.
timestamp: 2026-09-03T00:00:00Z
---

# SQL transformer v1

**Sibling plan:** [`parse-pane-redesign-plan.md`](parse-pane-redesign-plan.md).
**Grounding it rests on:** [`sql-only-transform-feasibility.md`](sql-only-transform-feasibility.md)
(2026-08-29 — read §2 and §4 before touching anything here) and
`okf/backend/engine/catalog-vs-executors.md`.
**Mockup source:** [`assets/authoring-redesign-mockup/`](assets/authoring-redesign-mockup/README.md) (archived beside this plan).
**Supersedes:** `author-schema-1-plan.md` (archived).

## Context

The operator wants a generic transformer: fire a SQL over the source schema, derive the output/target
schema from its resultset metadata, and edit it in a smart table. The feasibility analysis established
the one fact that shapes this: **the raw relation is deliberately ALL-VARCHAR** (both ingest engines must
agree on types), so `SELECT * FROM raw` carries no types until something casts. The casting layer *is*
the mapping, and it must stay a declarative rule table for three non-stylistic reasons: the cast-failure
audit needs a declared source column + target type as its denominator (`EXPR` is already excluded by
definition); `TRY_CAST`/`TRY_STRPTIME` null one cell where a hand-written `CAST` kills the whole batch;
and `SchemaCompatibility`'s BACKWARD contract needs a stable schema to diff against.

So the design is a **two-layer split**, and v1 builds only the second layer:

- **Layer 1 — typing.** Stays on the Parse step (Types section, Auto/Declared, `TRY_CAST` audit). This IS
  the "Schema validator & type coercion" processor. Unchanged mechanism.
- **Layer 2 — transforming.** ONE SQL `SELECT` over the **typed** output of Layer 1, where resultset
  metadata is finally meaningful. Everything the operator asked for falls out of DuckDB: nested functions
  (plain SQL), type inference and validation without executing (`DESCRIBE`), row filter (a separate Step,
  D3 below), dynamic values (render at read time — the repo's standing rule), and an AST for the v2 smart
  table (`json_serialize_sql` — built in, probe pending).

## Operator decisions (binding)

| # | Decision | Consequence |
|---|---|---|
| D3 | **Filter stays a separate Step**, never a `WHERE` buried in free SQL | `transform.filter` already splits into DATA + DROPPED via `predicateSplit` on its `where` key (`RowShaper.java:188`) — the rejected rows feed quarantine/audit. A `WHERE` in free SQL would discard them silently. Zero new filter code in v1. |
| D4 | **v1 ships first**: textarea + derived output schema + editable target names. **No AST in v1.** | The smart table (v2) and macros-as-UDFs (v3) are parked below, not in scope. |
| D5 (2026-09-03, from the clickable mockup) | **v1 gets a Simple mode: a Fields table that GENERATES the SQL** — one row per output field, exactly **five plain-language verbs**: Keep as it is · Remove extra spaces · Make UPPERCASE · Change type to… · Calculate… | Table → SQL is forward-only generation (no parser, no AST) — it does NOT contradict D4. Business users never see SQL unless they open Advanced. Adding a sixth verb is a product decision, not a dev convenience. |
| D6 | **A hand edit in Advanced LOCKS the step**: the Simple table becomes read-only (greyed, non-interactive) until the author explicitly "starts over from the table", which discards the hand-written SQL. | The step persists `sql` + a `locked` flag (or: the presence of a `fields[]` block means unlocked; its absence means locked). Never try to parse hand-written SQL back into rows in v1 — that is v2. |
| D7 | **The Fields table must scale to 600+ columns**: search (name + source), view-only filter chips with counts (All · Changed · Calculated · Text · Numbers · Dates), a visible `#` = **output position in the full list** (never the filtered or paged index — page 2 starts at #11), **pagination 10 / 20 / 100 (default 10)**, and a sample preview limited to the fields on the current page. | Filters and search never change the output — they are a lens; changing either resets to page 1. `#` is computed on the full ordered list BEFORE filtering/paging, so it stays stable across pages. The same grid component serves the Parse pane's "Columns that come out" table (search added there too). |

## v1 — what gets built

### B1 — a new node type `transform.sql`
`BuiltinNodeType.TRANSFORM_SQL("transform.sql", TRANSFORM, "SQL")`, alongside `map`/`select`/`derive`.
Attributes (`NodeAttributes.TRANSFORM_SQL`, mirrored into `node-attributes.ts` — the
`NodeAttributesContractTest` ratchet WILL move, regenerate both contracts):
- `sql` — `text`, required. One `SELECT`. The input relation is addressed by the fixed alias **`input`**
  (`FROM input`); the engine rewrites it to the real relation name at execution. Pin this convention in
  the attribute's `help` and in `pipeline-config-keys.md`.
- No `where` on this node (D3).

**Execution:** `RowShaper.shape()` gains one branch → `sql(conn, node, input, outPrefix)`:
`CREATE TABLE <out> AS <sql with 'input' bound to the input relation>`. Emits exactly ONE relation
(`PipelineRel.DATA`) — it is a `project()`-class verb, like `map`/`select`/`derive`. Refuses (throws,
naming the node) if the SQL is not a single `SELECT` — no DDL/DML/multi-statement, ever.
⚠ `SqlSandbox.seal()` applies (no external access, no extension autoload) — the sealed connection is the
one the SQL runs on, same as `EXPR` today.

### B2 — derived output schema via DESCRIBE (authoring-time, no execution)
Generalize `TypeFlow.transformedColumns` (`inspecto-etl/.../TypeFlow.java:42`): today it builds an
empty scratch table shaped like the raw ingest table and `DESCRIBE`s the mapping SELECT. Add
`TypeFlow.describe(List<Column> inputColumns, String sql)` — scratch table named `input`, shaped by the
**upstream Step's typed output schema**, then `DESCRIBE <sql>`. Returns `[{name, type}]` or throws
`IllegalArgumentException` carrying DuckDB's binder message (which names the offending column — this is
the validation, free). Exposed as `POST /pipelines/authored/{id}/nodes/{nodeId}/describe` (or extend
`/components/transform/preview` with a `describeOnly` flag — pick whichever the `endpoint` skill's gate
order fits more cleanly; the former is more honest, it never touches rows).

### B3 — the Simple/Advanced pane (D5–D7; clickable mockup: Artifact "Pipeline Authoring Redesign")
A bespoke component next to `pipeline-load-definition.component.ts` (NOT the generic schema-form), two
modes behind a segmented toggle in the drawer header:

**Simple (default)** — the Fields grid. Columns: `#` (output position in the FULL list) · Field name
(editable) · From (source-column select; "— calculated —" for formulas) · What to do (the five verbs; a
type select appears for *Change type*, a mono formula input for *Calculate*) · Comes out as (derived type
chip; derived/cast rows tinted) · Sample (the verb applied to the sample value) · leave-out (×). Above the
grid: search, filter chips with counts, "Showing N of M fields". Below the rows: a pager (rows per page
10 / 20 / 100, "11–20 of 65", prev/next). Then "Add a calculated field" and the **Left out** chips (click
to restore). 600+ rows is the design point; `#` is the full-list position so it survives paging. A one-line plain summary sits under the step name
("65 fields out · 1 renamed · 2 tidied · 1 calculated · 2 left out"). Seed for a new step: one *Keep* row
per upstream column (operator's item 1).

**The generator** — pure function `fields[] → SELECT`: `keep → col`, `trim → TRIM(col)`,
`upper → UPPER(col)`, `cast → TRY_CAST(col AS T)` (always TRY_, never CAST — the forgiving semantic),
`formula → <text verbatim>`; every row `AS name`; `FROM input`. Lives in `inspecto/transform/` as a
unit-tested TS module mirrored by the Java side's derive (B2) — the Java engine still only ever sees the
SQL, so `fields[]` is an **authoring artifact stored beside** `sql`, not a second engine contract.

**Advanced** — the SQL (plain `<textarea>` in v1; CodeMirror is already in `package.json`, wiring it is a
follow-on) + the DESCRIBE-derived output schema (B2). Read-only reflection of Simple until the author
types into it; the first hand edit sets `locked` (D6): Simple greys out with a banner and a
"start over from the table" action that drops `sql` back to the generator's output.

**Describe the fields** — a collapsed section under the preview: `Field · What it means · Unit ·
Sensitivity`, rows following the same search/filter lens. This is where
`<inspecto-schema-metadata-grid>` from the dissolved Files & metadata tab lands (sibling plan S5).

### B4 — test against sample
Reuse the existing "Test this Step" mechanism verbatim: `ComponentsService.previewTransform` already
accepts any `transform.*` config + the tab's sample rows (`components.service.ts:217-234`). Nothing new;
`transform.sql` just qualifies by prefix.

### B5 — audit honesty at the boundary
`transform.sql` is *by construction* outside the cast-failure audit (same reason `EXPR` is). Emit the
same WARNING `Finding` the `EXPR` rule got on 2026-08-29 ("runs author-owned SQL verbatim and is not
covered by the batch's cast-failure audit"), anchored to `sql`. ⛔ The load-bearing half of that fix was
that `clean`/gate computations key off `Severity.ERROR`, not "any finding" — a WARNING must never block
save. Verify with a test, don't trust the earlier fix by reference.

## Where the four "processors" land (operator's item 8)

| Catalog entry | Home after v1 |
|---|---|
| Schema validator & type coercion | Parse step, Types section (Layer 1, declarative, unchanged) |
| Whitespace & string sanitizer | `TRIM(x)` in the SQL (v2: a one-click quick-action row) |
| Expression builder & computed columns | **Is** `transform.sql` |
| Field type cast & renamer matrix | Splits: cast → Layer 1 (declarative); rename → editable Name in B3 (alias) |
| Lookup & static map | `transform.join` stays for reference datasets (v2 may allow `JOIN` inside the SQL) |
| Summarizer (operator's item 7) | Same shape: `transform.sql` with `GROUP BY`; keep `transform.summarize`'s `measures`/`group_by` validators for the structured form, don't delete the node |

Update `ProcessorCatalog.java`'s `nodeType` for `transform.expression` / `transform.cast` to
`transform.sql` when v1 ships (they point at `transform.map` today), and refresh
`catalog-vs-executors.md`.

## Parked (NOT v1 — do not start)

- **v2 — smart table over the AST.** `json_serialize_sql('SELECT …')` → one row per output column
  (alias, expression tree, function, literal args) with in-place literal/alias editing →
  `json_deserialize_sql` back. 🔴 **Probe first:** it lives in the `json` extension; `SqlSandbox.seal()`
  sets `autoload_known_extensions=false`. `json` is *normally* statically linked in the JDBC jar — but so
  was assumed of `excel`, which is NOT. Five-minute measurement on the sealed connection before any design.
- **v2 — quick actions** (add TRIM / add cast / rename) as AST edits; `JOIN reference/x` in the SQL.
- **v3 — macros as the UDF registry.** Measured to work under the seal (feasibility §3); needs a component
  kind, a registry, and re-creation on every scratch connection (`EnrichmentEngine`, `PipelineJobRunner`,
  `BatchIngestStrategy`, preview). A real project.
- Dynamic/environment values: v1 has none. When needed: render at read time (standing rule) or DuckDB
  `SET VARIABLE`/`getvariable()`; type inference of a variable needs a sample value for DESCRIBE.

## What survived from the superseded AUTHOR-SCHEMA-1 plan

- `DESCRIBE`-based validation (B2) *replaces* the proposed EXPR-syntax endpoint — better, because it
  validates the whole SELECT with binder errors that name columns.
- The `transform.join` reference-existence check stays a valid small follow-on (BACKLOG).
- The schema-drift-into-mapping check stays valid *because* Layer 1 remains a rule table (BACKLOG).
- Everything else (async validators, debounce scaffolding, preview auto-recompute, RunToHereDialog
  cross-link) is dropped as premature for a surface that is being redesigned.

## Verification

- Backend (`mvn -o test`): B1 — a `transform.sql` node materializes one relation, refuses non-SELECT
  and multi-statement input, refuses when the SQL names a column absent from the typed input (binder
  error propagates with the column name); B2 — `describe()` returns DuckDB's types for a nested-function
  SELECT WITHOUT executing (assert no rows were read — scratch table is empty); B5 — WARNING finding
  present, save still `clean`.
- Frontend (`npm test`, exit code checked): B3 — editing Name rewrites exactly that alias in the SQL and
  nothing else; seed is `SELECT * FROM input`; derive populates the table; metadata columns round-trip.
- Contract: `NodeAttributesContractTest` green after regenerating both sides.
- Browser preview on the real ControlApi: parse a pasted sample → add SQL Step → type
  `SELECT TRIM(name) AS customer, TRY_CAST(amt AS DOUBLE) * 100 AS cents FROM input` → Derive → two
  typed columns appear → rename `cents` in the table → SQL alias updates → Test this Step shows rows.

## Order

B1 + B2 together (engine + describe, one commit, tests first) → B5 (small, same shift) → B3 + B4 (UI)
→ catalog `nodeType` update + docs distillation (`catalog-vs-executors.md`, `pipeline-config-keys.md`,
`node-types.md`). ⛔ Do not start the v2 probe until v1 is in master.
