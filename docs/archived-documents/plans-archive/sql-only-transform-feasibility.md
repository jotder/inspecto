# "Drop the mapping, keep only SQL Map+Filter" — feasibility, grounded

**Status:** ARCHIVED 2026-09-04 — analysis of 2026-08-29 whose de-risking order (§6) is now done or parked with a recorded decision: step 1 SHIPPED 2026-08-29 (`EXPR` WARNING) and extended to `transform.sql` in `98ffc90b`; step 2 SHIPPED as `transform.sql`'s `DESCRIBE`-derived output schema (`TypeFlow.describe`, same commit); step 3 ("does the rule table still earn its place") was ANSWERED by the two-layer split — typing stays declarative on Parse, transforming is one SQL over the typed source (`sql-transform-v1-plan.md`, archived beside this file). The two still-live threads (§3 macros-as-UDFs, the `json`-extension-under-the-seal probe for the v2 AST table) are BACKLOG rows (AUTHORING-REDESIGN-1), not this file. Durable facts distilled into `okf/backend/engine/catalog-vs-executors.md`. **Concept home if any of it is ever built:** `okf/backend/engine/duckdb.md` +
`okf/backend/engine/etl-transform.md`.

**The idea, as put:** Extract parses the data, generates a schema, and visualises it for testing. The
persistence schema (Parquet) is then the **resultset metadata** of a SQL query, so no separate mapping
is needed. Transformation collapses to **Map + Filter expressed as SQL** — built-in functions plus
UDFs — authored through the SQL-building interfaces that already exist over a data table.

---

## Verdict in one line

**The mechanism the idea rests on is already built and already load-bearing — but the strong form
("no mapping would be required") is refuted by a measured fact: the raw relation is deliberately
ALL-VARCHAR, so resultset metadata carries no types until something casts. Whatever does that casting
*is* the mapping, whatever it is called.** The weak form — stop making the author restate a schema
that DuckDB can derive — is not only feasible, it is the shipped design.

---

## 1. What already exists (more than the idea assumes)

| The idea says | Already built | Where |
|---|---|---|
| "the persistence schema is the resultset metadata" | **`TypeFlow.transformedColumns`** runs DuckDB `DESCRIBE` over the *identical* SELECT `materialize` executes, without executing it. Its javadoc already states the thesis: *"DuckDB is the type authority; the returned types are its own inferred types, which is exactly what the Parquet footer will carry."* | `inspecto-etl/.../TypeFlow.java` |
| "transformation is SQL" | **`EXPR`** is a mapping `transformType` whose `sourceExpression` is author-owned verbatim SQL, deliberately un-sandboxed | `DataTransformer:158` |
| "keep only Map + Filter" | `transform.map`, `transform.filter`, `transform.join`, `summarize` already exist as recipe verbs / graph nodes | ELT amendment Phases 2–4, shipped |
| "E generates a schema, visualises data for testing" | `ComponentPreview` sniffs with `SELECT column_name, column_type FROM (DESCRIBE preview_sniff)` and renders it | `ComponentPreview:297,412` |
| "UDFs" | `CREATE MACRO` (scalar **and** table) works, **and still works after `SqlSandbox.seal()`** — measured, see §3 | probe, 2026-08-29 |

⇒ The idea is not a new direction. It is roughly the ELT amendment's direction, stated more
aggressively. That is a point in its favour, and it also means the remaining disagreement is narrow
and specific rather than philosophical.

---

## 2. The blocking fact: the raw relation is ALL-VARCHAR on purpose

🔴 **`read_csv` is issued with an explicit `columns={'c0':'VARCHAR','c1':'VARCHAR',…}` (and
`all_varchar=true` stamped).** So `SELECT * FROM raw_input` has resultset metadata of *all VARCHAR*.
It carries **no type information at all**. Every type in the Parquet footer today comes from the
mapping's casts (`SchemaFieldTypes.castSql`), not from inference.

So "extracted schema + SQL ⇒ resultset metadata ⇒ persistence schema" has a gap: **something must
still say `TRY_CAST(x AS DOUBLE)`.** Two ways to close it, and both have a cost:

**(a) Turn on DuckDB type inference at read.** ⛔ There is a stated reason it is off: **two ingest
engines must agree.** `DuckDbCsvIngester`'s javadoc pins its semantics to the Java path
("*Matches the Java path (everything VARCHAR; `DataTransformer` casts later)*"), and the `auto`
engine policy routes any config using `skip_tail_columns` / `skip_junk_lines` / `skip_tail_lines` to
the Java path. Inference on one path only means **the same file gets different column types depending
on which engine ran it** — a far worse defect than the duplication being removed.

**(b) Put the casts in the authored SQL.** Then the SQL *is* the mapping. The duplication is not
removed, it is relocated — from a declarative table into a string. §4 is what that costs.

---

## 3. UDFs — feasible, but narrower than "UDF" suggests (measured)

Probed against `duckdb_jdbc:1.5.2.1`, the version in `~/.m2`:

| Probe | Result |
|---|---|
| `CREATE MACRO half(x) AS x/2`, then `SELECT half(10)` | **works** → `5.0` |
| `CREATE MACRO … AS TABLE SELECT …` (table macro) | **works** |
| Same, **after** `autoload_known_extensions=false` + `enable_external_access=false` + `lock_configuration=true` | **still works** — a macro can be created *and* called on a sealed connection |
| Java-side scalar-UDF API on `DuckDBConnection` (reflection over every method containing function/udf/macro/scalar) | 🔴 **NONE** |

⇒ **"UDF" here can only mean a SQL macro.** No Java function, no library call, no extension (the seal
blocks autoloading). Two practical consequences if this is ever built: a macro is **per-connection**,
so it must be re-created on every scratch connection (`EnrichmentEngine`, `PipelineJobRunner`,
`BatchIngestStrategy`, preview), and macros need an authoring home + a registry, which does not exist.

---

## 4. What the declarative mapping buys that SQL cannot

These are the reasons to keep it, and none is stylistic.

1. 🔴 **The cast-failure audit loses its denominator — by definition, not by oversight.** Today a bad
   value becomes NULL and the row is *kept*, so a whole column of mis-formatted timestamps can land as
   NULLs with nothing saying so. `countCastFailures` counts, per rule,
   `source was non-blank AND result IS NULL`. That needs a declared **source column** and a declared
   **target type**. `EXPR` is *already excluded* for exactly this reason — the code says counting it
   "would invent a denominator". **So "everything becomes SQL" sets the audit's coverage to zero.**
2. 🔴 **Forgiving coercion is a semantic, not a syntax.** The generated SQL uses `TRY_STRPTIME` /
   `TRY_CAST`, so one bad cell nulls one value. A hand-written `CAST` **kills the whole batch**. Every
   author now owns that discipline on every column, and the failure mode is a dead batch, not a
   warning.
3. 🔴 **Per-field metadata has nowhere to live.** `ResultSetMetaData` is name + type. The schema also
   carries `selector` (which physical column feeds a field), `timezone` / `timezone_column` (shipped
   2026-08-29), `partitions[]`, duplicate-check and dedup keys, `reference.key`, and
   `mapping.canonicalName` / `rawName` which the catalog projects. None of these are derivable from a
   query's output.
4. 🔴 **The schema is a CONTRACT, and deriving it from the SQL makes it float.**
   `SchemaCompatibility` enforces a **BACKWARD** class on edit: adding a field and widening a type are
   allowed; removing, narrowing, or **moving a `selector`** are ERRORs, because existing raw files
   would parse into different columns. If the schema is "whatever the current SQL returns", editing a
   `SELECT` silently rewrites the contract and the gate has nothing stable to diff against.
5. ⚠ **20 files read the declared schema** (`DataTransformer`, `PartitionDef`, `Identifiers`,
   `SchemaProjection`, `BoundaryScanner`, the three ingesters, `DuckDbRecordSink`, …). "Delete the
   mapping" is not one seam.

---

## 5. The version that is actually feasible

Split the idea in two — the halves have very different costs.

**✅ Feasible, and mostly already true: stop asking the author to RESTATE what DuckDB can derive.**
`TypeFlow` already derives the output schema from the SELECT. Publishing that as the Step's output
schema — rather than having an author declare it a second time — removes the duplication the idea is
actually objecting to, and removes it without touching the audit, the coercion semantics, or the
compatibility gate. This is the ELT amendment's own §3.4.4 direction.

**✅ Feasible, additive: let a Step be authored as SQL where the author wants that.** `EXPR` and
`transform.map` already are this. What is missing is honesty at the boundary: an author choosing raw
SQL should be *told* they are leaving the audited path (today `EXPR` drops out of the audit silently).
That is a small, high-value piece of work and it is independent of everything else here.

**§6 step 1 SHIPPED 2026-08-29.** `MappingRules.validate` now emits a WARNING `Finding` for every
`EXPR` rule, anchored to `rules[N].transformType`: *"EXPR runs author-owned SQL verbatim and is not
covered by the batch's cast-failure audit…"* — rendered by the mapping grid editor exactly like any
other finding (it already distinguished WARNING from ERROR styling; only ERROR ever reached it before).
⚠ **The load-bearing half of the fix wasn't the warning — it was two `clean`/gate computations that
had silently equated "any finding" with "unclean" and would have blocked every `EXPR` save the moment
it stopped being finding-free.** Both `ComponentRoutes.validateMapping`'s `clean` field and the
`PUT /components` save-path gate now key off `Severity.ERROR` specifically, matching the pattern the
adjacent schema-findings check already used. `countCastFailures` itself is untouched — the exclusion
stays, only the boundary is now visible before a batch runs, not discovered from it.

**§6 step 1 extended to `transform.sql` 2026-09-03 (sql-transform-v1-plan.md B5).** The same
audited/unaudited boundary now applies to the whole-node SQL Step, not just the `EXPR` mapping rule:
`PipelineValidator.validate` emits one WARNING `Issue` (`SQL_STEP_UNAUDITED`) for every
`transform.sql` node, naming the node id and its `sql` attribute — *"runs author-owned SQL verbatim
over the typed source and is not covered by the batch's cast-failure audit…"*. Unlike the `EXPR` fix,
no `clean`/gate bug was found alongside it: `PipelineValidator.Result.ok()` already keyed off
`Severity.ERROR` only, and so did `PipelineGraphRoutes.saveGraph`'s own findings gate — a WARNING on a
`transform.sql` node saves cleanly, verified by test (`PipelineValidatorTest`,
`theUnauditedSqlWarningAloneDoesNotBlockSave` / `anActualErrorOnAGraphWithASqlStepStillBlocksSave`).

**⚠ Feasible but a real project: SQL macros as the UDF surface.** Measured to work under the seal.
Needs a component kind, a registry, and re-registration on every scratch connection.

**🔴 Not feasible as stated: "no mapping would be required."** It reduces to either engine-dependent
types (a) or the same mapping written as a string (b), and it takes the cast audit, the forgiving
coercion, the per-field metadata and the compatibility contract with it.

---

## 6. If it is pursued, the order that de-risks it

1. ~~Make the audited/unaudited boundary visible at authoring (`EXPR` today, any SQL Step tomorrow).~~
   **SHIPPED 2026-08-29** — see §5.
2. Publish `TypeFlow`'s derived schema as the Step's output schema on the read surfaces, so the
   duplication disappears before any deletion is attempted.
3. Only then ask whether a declarative rule table still earns its place — with the audit and the
   compatibility gate as the acceptance criteria, not line count.

⛔ **Do not start at "delete the mapping".** Steps 1 and 2 deliver most of the value the idea is
after and are reversible; step 3 is the one that trades away guarantees, and it should be taken with
those two already in hand.
