# The Step workbench — publish the derived schema instead of asking for it

**Status:** DESIGN, not scheduled (operator asked to "fill up the gap", 2026-08-29). No code.
**Follows:** [`sql-only-transform-feasibility.md`](sql-only-transform-feasibility.md) — this is that
analysis's recommended step 2, designed. **Concept home on build:**
`okf/backend/engine/etl-transform.md` + `okf/frontend/features/pipeline-editor.md`.

**The ask:** one place where an author builds a query — naming fields, using functions, choosing the
input relation, free SQL, a column filter and grouping *together* — tests it against sample data, and
gets the output schema **derived and shown** rather than being asked to restate it.

---

## 0. The finding that changed this design

🔴 **Two of the three capabilities this needs are already written, tested-or-not, and have NO
production caller.** Grounded 2026-08-29:

| Capability | State | Evidence |
|---|---|---|
| `TypeFlow.transformedColumns` / `.sinkColumns` — output schema by `DESCRIBE` over the identical SELECT, **without executing it** | **written + tested, ZERO production callers** — the only main-code mention is a javadoc in `DataTransformer:89` | `git log`: shipped `c925fc9d`, ELT Phase 2 slice 2 |
| `RowShaper.fuse` — fuse a chain of map/filter nodes into ONE `SELECT … WHERE …` | **written, NO caller, NO test** — genuinely dead | grep across `src/main` and `src/test` |
| `/components/transform/preview` + `ComponentPreview.transform` | **wired end to end**, drawer button and all | `ComponentRoutes:59`, `pipeline-config-definition.component.ts:423` |

⇒ This is mostly a **wiring** job, not a build. That is the good news. It also means the plan must not
*assume* the unwired code is correct — `fuse` in particular has never run (see §5, where it is
deliberately not used).

---

## 1. What already exists, precisely

**The verbs are complete.** `RowShaper` compiles every one of them to real SQL today:
`transform.map` / `select` / `derive` (`columns: [{name, expr}]`), `filter` (`where`), `summarize`
(`group_by` + `measures`, via `MeasureCompiler`), `join` (`reference` + `on`), `route`, `dedup`,
`split`, `merge`. So "naming the field, functions, filter, grouping" is **already expressible** — it
is not visible, not composable in one place, and not schema-publishing.

**The sample-data loop exists.** `POST /components/transform/preview` takes an **inline** node config
plus `sampleRows` and runs it on a throwaway DuckDB. The drawer already calls it over "the rows your
parse step produced".

**🔴 And the preview already throws away most of what it computes.** `ComponentPreview.Result` carries
`inputColumns` + per-relation `rowCount` **and the rows themselves** (`ScratchTables.readRows`), but
the UI renders only two text lines — `in: N column(s) over M row(s)` / `out 'data': K row(s)`. The
rows are fetched over the wire and discarded.

**A precedent for publishing derived types already shipped.** `ComponentPreview.GrammarResult` carries
`columnTypes` as an **additive key** (old clients ignore it) from a second `auto_detect=true` sniff,
documented as *"advisory only (production ingest stays all-VARCHAR)"*. The same shape and the same
honesty apply here.

---

## 2. The gaps, one line each

| Ask | Gap |
|---|---|
| publish the derived schema | `ComponentPreview.transform` returns column **names only** — no types. `TypeFlow` computes them and nothing calls it. |
| "how to generate the query" | `RowShaper` builds the SQL and **never surfaces it**. The author cannot see what their config compiles to. |
| naming the field | `transform.map`'s `columns: [{name, expr}]` is executable but has **no `NodeAttributes` spec**, so no served form renders it (`NodeAttributes:30` — the mapping-CSV surface owns it). |
| functions | built-ins work. "UDF" can only mean `CREATE MACRO` — works under the seal, but per-connection and with no authoring home (see feasibility §3). |
| table name | not asked and should not be: the input is the **previous Step's output**. Only `transform.join`'s `reference` names an outside relation. §6. |
| "all together" | preview takes **one** node. No chain preview exists on any surface. |
| test with sample data | exists — but shows counts, not rows, types or SQL. |

---

## 3. The design: one call, three panes

**One endpoint answers everything the workbench shows.** Extend `/components/transform/preview`
additively (the `GrammarResult.columnTypes` precedent — new keys, old clients unaffected):

```
POST /components/transform/preview
{
  "configs":    [ {...node config, type: "transform.map"}, {...type: "transform.filter"} ],   // NEW: a chain
  "config":     {...},                                                                        // still accepted: one node
  "sampleRows": [ {...}, ... ]
}
→ {
  "inputColumns": ["call_id", "amount", ...],
  "steps": [                                   // NEW — one entry per config, in order
    { "index": 0, "type": "transform.map",
      "sql": "SELECT \"call_id\", TRY_CAST(\"amount\" AS DOUBLE) AS amount ...",   // NEW
      "relations": [
        { "rel": "data", "rowCount": 8,
          "columnTypes": [ {"name":"call_id","type":"VARCHAR"}, ... ],             // NEW
          "rows": [ ... ] } ] } ],
  "relations": [ ... ]                         // unchanged: the LAST step's relations
}
```

The three panes are then projections of one response:

1. **Build** — the structured form, rendered from the server-published `NodeAttributes` spec for the
   Step's type (the existing `step-types` mechanism). Nothing new except the missing `transform.map`
   spec (§4).
2. **SQL** — `steps[].sql`, read-only. This is the operator's *"transparent on EL"*: the config and
   the SQL are the same thing, shown side by side, and the SQL updates as the form changes.
3. **Result** — `steps[].relations[].rows` in a data table, headed by `columnTypes`. **That header is
   the published derived schema.** The author never restates it.

**Why one call and not three.** The SQL, the rows and the types must describe the *same* execution. Three
endpoints can disagree the moment the draft changes between calls, and the whole point is that the
schema is not a second, driftable statement of the truth.

---

## 4. Naming fields — the one genuinely missing spec

`transform.map` executes `columns: [{name, expr}]` but publishes no attribute spec, so the Build pane
has nothing to render. Add `TRANSFORM_MAP` to `NodeAttributes` with a `columns` attribute whose editor
is a two-column grid (**name**, **expression**).

- `name` — validated with the identifier grammar already served for `group_by`
  (`MeasureCompiler.SAFE_IDENT`, published through `measure-grammar.contract.json`). ⚠ That pattern
  **travels unanchored**: Java `matches()` is whole-string, JS `test()` is a substring search, so the
  client must anchor it itself — this is already documented and already bit once.
- `expr` — free SQL. This is the "free SQL" half of the ask, and it is per-column rather than a
  whole-query escape hatch, which keeps every other guarantee intact.

🔴 **Implementation trap:** the node vocabulary feeds **TWO** committed contracts — `node-attributes`
and `step-types`. Regenerate both, or the full reactor goes red after a green targeted run.

---

## 5. "All together" — chain semantics

Accept `configs: [...]` and run the chain by feeding each Step's `data` relation in as the next Step's
input, materialising one scratch table per Step.

⛔ **Do not use `RowShaper.fuse` for this.** It exists, it fuses map+filter into one SELECT, and it is
**dead code with no test** — it has never executed. It is a *performance* optimisation (avoiding an
intermediate table per node), and a preview runs over at most `MAX_TEST_ROWS` rows, so it buys nothing
here. Wiring untested code into a new surface to save an irrelevant table write is how the surface
inherits a defect it did not create. Per-Step `shape` chaining is correct, simpler, and already
exercised. (Testing and wiring `fuse` on the *execution* path is a separate, legitimate piece of work.)

**What composes and what does not.** map/select/derive/filter compose freely. `summarize` **changes
cardinality** and `join` **changes the column set from outside the batch** — both are real Steps in the
chain, but the workbench must show them as their own stage with their own output schema, never folded
into a neighbour's SQL. `transform.join` additionally needs a `ReferenceResolver`; the 4-arg `shape`
**refuses** without one, so a chain preview containing a join must either supply reference context or
refuse honestly with the reference it could not reach — never silently skip the Step.

---

## 6. The table name

The author should not type one. Inside a chain the input relation is the previous Step's output, and at
the head it is the parse Step's output. The workbench should **show** it (`FROM ‹previous step›`) rather
than ask for it — an editable table name in a Step editor is an invitation to reference a relation that
does not exist at that point in the chain.

The two real exceptions stay as they are: `transform.join`'s `reference`, already an autocomplete over
the registry, and `transform.merge`'s multiple inputs, which the graph supplies.

---

## 7. Where the derived schema gets published — two places, two meanings

These are **not** the same thing and must not be conflated:

| | Per-Step, in the workbench | Per-pipeline, the persisted schema |
|---|---|---|
| Derived from | executing the chain over sample rows | `TypeFlow` — `DESCRIBE`, **no execution** |
| Needs sample rows? | yes | **no** |
| Honest label | *derived from your N sample rows* | *what this pipeline will write* |
| Answers | "did my expression do what I meant?" | "stop making me restate the schema" |

**Both are wanted, and the second is the one that closes the operator's original objection.** Wire
`TypeFlow` behind a read route (`GET /config/schema/derived?pipeline=…`, or a key on the existing
`/config/preview/schema`) and render it read-only beside the authored schema.

🔴 **The types are production-faithful for the CSV path, and this is not luck.** Preview seeds its
scratch table **all-VARCHAR** (`ScratchTables.seed`: `CREATE TABLE … (col VARCHAR, …)`), which is
exactly the shape production's `read_csv columns={…VARCHAR…}` produces. ⚠ **It is NOT faithful for the
plugin/typed-ingester path**, where raw fields carry declared types — which is precisely why
`TypeFlow` takes a `typedSource` flag. The workbench must pass the pipeline's actual path and say which
one it assumed, or it will confidently show the wrong types for ASN.1/fixed-width pipelines.

---

## 8. Traps to carry into the build

1. 🔴 **`ComponentPreview` does NOT seal its connection.** It is absent from every `SqlSandbox` caller
   site, so author-supplied `where`/`expr` SQL executes with `enable_external_access` **on** — a
   preview body could `read_csv('/etc/…')`. This is **pre-existing** (the endpoint already executes
   author SQL) but this design *increases* how much SQL is authored there, so it should be fixed in the
   same slice: the preview reads only seeded scratch tables, so it needs no file access and
   `SqlSandbox.seal()` costs it nothing. Measured precedent: a sealed connection still evaluates
   `timezone()`, macros and ordinary expressions.
2. ⚠ **Advisory ≠ contract.** Label the per-Step schema as sample-derived. `GrammarResult.columnTypes`
   already sets this precedent with an explicit *"advisory only"* note; copying the wording is cheaper
   than re-litigating it later.
3. ⚠ **A sample can lie by omission** — an all-NULL sample column DESCRIBEs as whatever the expression
   forces, and an empty sample cannot preview at all (`transform` already refuses with *"at least one
   sample row is required"*). Keep that refusal; do not invent a synthetic row.
4. ⚠ **The `%z`/`%Z` refusal and the source zone** now live at config load *and* at the write gate. A
   workbench that composes SQL must not become a third path that bypasses them.
5. ⚠ **`MAX_TEST_ROWS`** already bounds what the drawer sends; the chain endpoint must keep a bound of
   its own rather than trusting the client's.

---

## 9. Slices, each shippable, with a verify gate

| # | Slice | Verify gate |
|---|---|---|
| **S1** | ✅ **SHIPPED 2026-08-29** — `RelationPreview.columnTypes` (DESCRIBE-derived) + `Result.sql` (the statements as executed), both additive; the transform preview now runs on a **sealed** `SqlSandbox` | ✅ reactor **3749/0/0/5**, 19 modules, exit 0. Seal falsified in both directions: without it a **readable file was read successfully** through a `where` predicate |
| **S2** | ✅ **SHIPPED 2026-08-29** — `<inspecto-step-preview-result>`: derived schema as chips, rows in a `<inspecto-data-table>`, SQL in a read-only pane; the mock mirrors the keys as **honestly empty** | ✅ UI **2826 passed / 5 skipped**, exit 0; lint:tokens + build + all three tsconfigs green; **driven in the preview** — both arms rendered |
| **S3** | `TRANSFORM_MAP` attribute spec (name/expr grid) — ⚠ regenerate **both** committed contracts | `NodeConfigNameContractTest` + the full reactor, not a targeted run |
| **S4** | `configs: [...]` chain preview, per-Step `shape` chaining; honest refusal for a join without reference context | chain of map→filter→summarize previews with a distinct schema per Step |
| **S5** | Wire `TypeFlow` behind a read route; show the derived pipeline schema beside the authored one | derived columns equal what a real batch writes to Parquet (assert against a written file, not against the SQL) |

**S1 and S2 alone deliver most of the ask** — the author sees rows, types and the generated SQL for the
Step they are editing — and neither changes any execution path. S5 is the one that ends the restating.

---

## 10. Explicitly out of scope

`CREATE MACRO` as a UDF surface (feasible, measured, but needs a component kind, a registry and
re-registration per connection) · deleting the declarative mapping (refuted — feasibility §2/§4) ·
using `fuse` on the execution path (worth doing, separately, with tests first) · a whole-query free-SQL
Step, which would reintroduce exactly the cast-audit denominator problem the feasibility analysis
recorded.

---

## 11. S1 as-built (2026-08-29)

**Shipped:** `ComponentPreview.RelationPreview` gains `columnTypes` — ordered `{name, type}` pairs from
`ScratchTables.columnTypes` (`SELECT column_name, column_type FROM (DESCRIBE <table>)`) — and
`ComponentPreview.Result` gains `sql`. Both are additive, with a pre-S1 compact constructor, so an old
client sees the previous response unchanged. `transform()` now runs on `SqlSandbox.open(defaultPolicy())`,
seeding **before** `seal()`.

**Three things the design did not predict:**

1. 🔴 **The unsealed preview really did read the filesystem — proven, not argued.** Falsifying with a
   `/etc/passwd` probe was *not* a valid test: on Windows it failed as "no such file", which looks
   identical to a refusal. Re-probed with a **temp file that actually exists**, removing the seal makes
   the preview return **200 with no exception at all**. That is the vulnerability, demonstrated. ⚠ A
   negative test whose target does not exist proves nothing — pick a probe that would otherwise SUCCEED.
2. **`RowShaper` was not modified.** SQL capture is a `SqlRecorder` that wraps the JDBC `Connection` in a
   `Proxy` and records the SQL passed to any `Statement` it hands out. The alternative — threading a sink
   through **13 `exec` call sites across 9 private methods** — would have changed production signatures
   for a preview-only feature. The wrapper also cannot miss a statement, since everything reaches the
   database through `Connection`.
3. ⚠ **`sql` is the whole ordered statement list, not one statement per relation.** `route` builds an
   intermediate `labelled` table before splitting, and per-relation attribution would hide exactly the
   statement carrying the author's `CASE` expression.

**Deliberately not done in S1:** the offline mock still returns neither key. There is no consumer yet, so
there is no behavioural divergence — but that changes the moment S2 renders them, which is why it is S2's
first task.

---

## 12. S2 as-built (2026-08-29)

**Shipped:** `<inspecto-step-preview-result>` (`inspecto/components/`) renders one preview: the input
columns, then per relation its **derived schema** as `<inspecto-chip>`s and its rows in an
`<inspecto-data-table tier="mini">`, then the compiled SQL in a read-only pane. The drawer's transform
arm now hands the whole response to it; the **sink** arm keeps its text lines (it has no schema to show).

**The call that shaped it — what should the MOCK return?**

⛔ **Not invented types.** The mock has no SQL engine, and its own contract note already bans writing a
second evaluator. A guessed type is worse than a blank: `CAST(amt AS DOUBLE)` would print as `VARCHAR`
and teach the operator something false. Special-casing verbs (a filter passes columns through, a map does
not) *is* that second evaluator. So the mock returns `columnTypes: []` / `sql: []` and the renderer says
**"Derived offline — the schema and SQL need the query engine, so they are not available here."** ⚠ An
empty chip row would have read as *"this Step produces no columns"*, which is a different and false claim
— that distinction is pinned by a spec in both the handler and the component.

**Two things the build found:**

1. ⚠ **Rendering a data table drags `GAMMA_APP_CONFIG` into every host spec.** `<inspecto-data-table>`
   injects the real `InspectoGridThemeService`, which walks up to that token — so the drawer's own spec
   started failing `NG0201` the moment the table appeared inside it. Stub it as the data-table's spec does.
2. ⚠ **The host spec asserted the old count lines** (`out 'data': 1 row(s)`) and failed correctly — a
   real regression caught by an existing test, updated to assert the new rendering instead.

**Verified in the preview** (offline server, Builder lens, `cdr_ingest` ▸ Row filter ▸ Test this Step):
the component mounts, the rows render in a real table, and the offline arm shows the explanation rather
than an empty schema. Feeding the server's response shape through the same component renders
`DERIVED SCHEMA · msisdn VARCHAR · amt_d DOUBLE` and the SQL pane. ⚠ **One step was synthetic**: the
sample rows were seeded onto the editor's parse thread rather than produced by running a parse, because
S2 changed nothing about where those rows come from. Everything downstream of that — the click, the HTTP
call through the mock interceptor, the response mapping and the render — was real.

**Not done:** the component is not in the `/design` gallery. It composes existing primitives (chip +
data-table) for one feature rather than introducing a design-system primitive, so it is not gallery
material; revisit if a second host adopts it (S4's chain preview is the likely one).
