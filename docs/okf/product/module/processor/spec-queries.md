---
type: Concept
title: Query catalog & execution
description: Query as a first-class Component — the Query Library store, $-Parameters, the Result Set descriptor, and POST /queries/{id}/run on DuckDB.
resource: platform/inspecto-engine/src/main/java/com/gamma/query/
tags: [control-plane, query, parameter, result-set, duckdb, studio]
timestamp: 2026-07-16T00:00:00Z
---

# Query catalog & execution

A **Query** is a first-class, reusable Component (R3): `{ type (sql | structured), source Dataset,
text | model, Parameters }` — lifted out of the artifacts that embed it so one Query serves many
renderings (Widgets, Dashboards, exports). Vocabulary: [`GLOSSARY.md`](../../../../GLOSSARY.md) §6-B.

* **Storage** — the `query` kind is writable in the component store
  (see [component registry](spec-component-registry.md)); authored in the Studio's
  **Query Library** pane.
* **Execution (W4)** — `POST /queries/{id}/run` executes on the embedded DuckDB with **server-side
  `$`-Parameter resolution** (`$today`, `$day(-7)`, user-declared `$name`, …) and returns rows plus the
  **Result Set** descriptor (columns with type + analytic role: dimension / measure / temporal) that
  the Presentation layer matches renderings against.
* **Access (R3, fixed 2026-09-27)** — `/run` applies the same component-access model as `/bi/query`
  (`ComponentAccess.canView`): a Query the caller cannot view is a 404 byte-identical to a missing one
  (`no query '<id>'`), and a Query over a Dataset the caller cannot view gets the same 404
  (`references unknown dataset`) — rows are never returned. Before this fix the route loaded both with
  no check, so any caller who knew an id ran an owner-only Query. Query CRUD/list/versions go through
  the generic `/components/query` routes, which already enforce the model. Held by
  `ControlApiQueryRunAccessTest` (armed authenticator; both checks mutation-verified).
* **Three parameter namespaces, deliberately distinct** — `$name` (runtime query Parameters, resolved
  server-side here) · `:fieldValue` (Expectation/rule templates) · `${ENV:…}` (secret references). Each
  resolver leaves the other two untouched.
* **Structured editor (2026-07-19 SHIPPED)** — the Query Library authors both types now: the
  `<inspecto-query-panel>` Query Core builder (projection + nested AND/OR filter, already reused by
  Decision Rules/Alert Rules/Expectations) is wired in for `type: 'structured'`, `queries.component.ts`.
  The panel gained an `@Input initialModel` so re-opening a saved structured query seeds its builder
  state (backward-compatible — other reused call-sites don't bind it, unaffected). `$`-parameters stay
  SQL-only (detected by scanning `text`; a structured query has no text to scan) — deliberate cut.
* **`structured` queries are client-compiled by design** (R6, product sign-off 2026-07-22 — an
  *accepted design*, not a risk). The builder UI emits valid SQL text that `QueryExecutor` runs, so
  a server-side structured compiler would duplicate that logic for no functional gain; the server
  therefore returns `422` for a non-SQL body **deliberately** (an explicit contract, never silent).
  Revisit only if an external `/api/v1` consumer must submit structured (non-SQL) bodies directly.
  ⚠ **Two facts this page omitted until 2026-09-08:** (1) `POST /bi/query` *is* a server-side structured
  evaluator for the widget *spec* grammar (`MeasureCompiler`), failing honestly on what it cannot map — the
  422 here and the compiler there are two routes over one boundary; (2) **the SPA never calls
  `POST /queries/{id}/run`** — the Query Library resolves `$`-parameters client-side (`inspecto/query/parameters.ts`)
  and previews through `/db/query`, so server-side `$current_user`/`$role` resolution has no product caller.
  Requirement of record: [DAT capability spec](../../layer/platform/plan-data-plane.md).
  (Widgets don't execute *any* bound query server-side yet — a separate, pre-existing follow-on.)
  Pagination is offset-based in this slice. `graph`/`spatial`/`search`/`api` query types are
  deliberately not built (geo/link views keep their own query shapes).
* **Contract** — part of the versioned [`/api/v1`](api-v1.md) surface; envelope + error codes apply.

## Calculated columns (DAT-5, shipped 2026-07-08; authoring UI 2026-07-10)

A calculated column is caller-authored SQL **fragment** text spliced inside the trusted relation —
`SqlGuard` validates whole statements, so fragments get their own guard:

* **`ExpressionGuard.check(expr)`** validates with three cooperating rules: (1) a **closed token
  alphabet** (plain identifiers, numeric/single-quoted literals, arithmetic/comparison operators,
  parens, commas — no semicolons, double quotes, comments, backslashes; 500-char cap); (2) a
  **keyword deny-set** for bare identifiers (`select from where … drop create attach copy pragma …`)
  — this kills scalar-subquery smuggling; (3) a **function-call whitelist** (`abs round coalesce
  upper substr cast try_cast …`) — `read_parquet(`, `glob(`, UDFs are rejected by name; `cast` types
  are themselves whitelisted. Deliberate v1 cuts: no quoted identifiers, no subqueries ever.
  Rationale: DuckDB has no offline-safe Java parser; the three-rule model is a provably closed surface
  — grow the whitelist, never "parse harder".
* **Window functions (v2, 2026-07-24).** A `WINDOW_FUNCTIONS` set (`sum avg count min max row_number
  rank dense_rank percent_rank cume_dist ntile lag lead first_value last_value nth_value`) is callable
  **only when the call is immediately followed by a valid `OVER (…)` clause** — the state machine
  gates `expectOver`/`expectOverParen` right after a window call's close-paren, so a bare aggregate
  (`sum(x)` with no `OVER`) still fails exactly as before (the windowed aggregates are absent from the
  scalar `FUNCTIONS` set). This is row-safe because the wrap is a projection (`SELECT *, (expr) …`),
  where a per-row window value is legal alongside `*` but a group-collapsing bare aggregate is not. The
  `OVER` clause admits only `PARTITION BY`/`ORDER BY` over columns + scalar functions + `ASC`/`DESC`/
  `NULLS FIRST|LAST` (explicit `ROWS|RANGE` frame clauses are deliberately unsupported); the `DENIED`
  deny-set is still checked ahead of the window-keyword rule, so no subquery smuggles in inside `OVER`.
* **Enforcement point** — `dataset` config gains `calculated: [{name, expr}]`;
  `DatasetRelation.relationSql` wraps `SELECT *, (expr) AS "name" FROM (<base>) AS __base`. Bad
  name/expr throws → **422 at every route, fail-closed** — enforced at relation-build time so every
  consumer inherits it (`/bi/query`, `/queries/{id}/run`, reports, measure alerts, DAT-4
  materialize, shares). Surviving bare identifiers are column refs resolved by DuckDB's binder
  (clean 4xx if unknown).
* **Date/string scalars (2026-09-25).** The whitelist grew by a vetted pure, deterministic set so a
  text-typed source can yield typed fields (`'March 22,2025'` → a DATE, `'Eden Gardens, Kolkata'` →
  a city): `strptime try_strptime strftime date_trunc date_part datepart year month day dayname
  monthname week quarter split_part starts_with ends_with contains regexp_matches regexp_extract
  regexp_replace left right lpad rpad if`. ⛔ The admission rule for a new name: row-local, no file/
  network/extension access, no settings or environment (`current_setting`, `getenv`), no clock or
  randomness (`now`, `random`) — a calculated column is re-evaluated on every read.
  `ControlApiDatasetRowsTest.everyNewlyAllowedScalarRunsInDuckDb` executes each one, so a name the guard
  admits but DuckDB cannot bind fails the build.
* The UI's inline `calculated-column-guard.ts` mirrors the three rules for instant feedback but is
  **not authoritative** — the server re-validates at query time regardless.
* **The relation page — `GET /datasets/{id}/rows?limit=` (2026-09-25).** `/db/table` reads the raw
  store, which has no calculated column, so the Widget Builder could neither type, count nor offer one.
  This read route runs `SELECT *` over the Dataset's relation (same payload as `/db/table`: typed
  `columns` with role + cardinality, `rows`, `statistics.truncated`; limit 1..5000, default 200; 503/404/
  422 like `/bi/query`, ungated like it). The SPA rows seam (`DatasetRowsService`) uses it only for a
  **saved** Dataset with calculated columns and no embedded Query Core model; every other read stays on
  `/db/table` / `/db/query`. ⚠ A dashboard drill-through (`filtered()` in the dashboard editor) still
  reads the raw store through `/db/query`, so a cross-filter ON a calculated column cannot resolve there.

## Classified entity keys are masked in BI result rows (`BI-QUERY-MASKING-1`, built 2026-10-12; first pack consumer telco-fraud classified 2026-10-18)

`BiResultMasking` (`inspecto/.../control/`) is the ONE helper behind `/bi/query`, `/queries/{id}/run`,
`/datasets/{id}/rows` and `POST /public/dashboards/{token}/query`. A column is masked when the Space's Link Analysis
`maskingMode` says so (`typed`: its classification is claimed by a masked Entity Type; `all`: any MSISDN / IMSI /
ACCOUNT / PII; `none`: off) and the caller lacks `canRevealLinkEntities`; the cell becomes the Space-key alias
`masked:<16 hex>` (`MaskTokens.spaceKey`, the same alias Link Analysis exploration and Entity Lists show) AFTER
execution, so grouping and `countDistinct` are unchanged. A column classified `PII` masks only under `all`: in `typed` mode it masks only when a masked Entity Type claims `PII` (the telecom pack does not). The response carries a `masking` block (`mode`, `masked`,
`columns`, `basis`); a Dataset with no classified column returns today's body, with no block.
Fail closed: an untraceable lineage masks every column the Dataset does not declare; a calculated column naming a masked
column is masked; a saved query's string output is masked when its text names a masked column. A viewer's filter, `when`,
order, or `min / max / sum / avg` over a masked column answers 422 (inference channels); a public share recipient never
reveals. Design record (path inventory, decisions D-M1..D-M7, the security review in section 12):
[`bi-query-masking-design.md`](../../../../archived-documents/plans-archive/bi-query-masking-design.md). Tests:
`ControlApiBiQueryMaskingTest`, `ControlApiBiMaskingReviewTest`, `ExplorationMaskingSpaceKeyTest`.
Also covered since 2026-10-13: `/db/table` and `/db/query`, scheduled Report exports (`ReportRowMasking`), Exchange snapshots
(D-M6 (a)), numeric key derivations. **2026-10-18:** a column classified `PII` stays unmasked in `typed` mode by decision (the
telecom pack does not claim `PII`; use `maskingMode: all`); a table Widget's `limit` is a server-side row limit, so a top-N
tile is masked like any other result. Retired to BACKLOG §6: D-M5 `revealKeys`, D-M4, live-mode Exchange grants, other
file-writing Job types. `BI-QUERY-MASKING-1` closed 2026-10-23 after its security review (below).

**Security review (2026-10-23, `BI-QUERY-MASKING-1` closed).** Every BI consumer was attacked as a viewer without
`canRevealLinkEntities` (and free SQL as an author without it) on DuckDB 2.0.0.0; what it found and how each now behaves:
- **A Widget bound to a saved Query** (`/bi/query` with `query`): the masking reads the saved query's OWN text. It used to read
  the `WITH "__bound_query" AS (` prefix, which does not parse, so `SELECT #1 AS x` or a renaming star went unseen and `x`
  came back raw (a filter on it was an oracle). When that text reads a key (names it, holds a star / `#n`, or the lineage is
  untraceable) every output column is masked and a filter or order on an undeclared column answers 422
  (`BiResultMasking.applyBound`).
- **Error text.** DuckDB quotes the offending VALUE in a run-time error: `error(msisdn)` answers `Invalid Input Error:
  4417..`, a failed `CAST(msisdn AS DATE)` (free SQL, or a calculated column) names the string. While masking is in force for
  the caller, a 422 keeps the driver's text only when every error type it names is compile-time (`Parser` / `Binder` /
  `Catalog`, raised before any row is read); any other answers a fixed sentence (`BiResultMasking.scrub`), on
  `/queries/{id}/run`, `/bi/query`, `/datasets/{id}/rows`, `/db/table` + `/db/query`, rule simulate, `/kpis/{id}/value`
  and the public share. A revealer, an unclassified Dataset and `maskingMode none` keep the full text. ⚠ The compile-time
  rule holds because `SqlGuard` refuses the PIVOT forms that read data: in raw DuckDB 2.0 a PIVOT Binder error lists raw
  values as "Candidate bindings", so relaxing the guard for PIVOT reopens this.
- **The `$parameter` oracle** is refused whenever the saved query reads a key by any route the result masking recognises,
  not only by name (`WHERE COLUMNS('msi.*') LIKE $p` was a bisection).
- **A calculated column naming `__base`** (the alias `DatasetRelation.withCalculated` gives the row it is computed over:
  `concat(__base)` is the whole row) is masked like one naming a masked column.
- **The public share on an install where nobody signs in.** The sign-in `MaskingDefault` makes an unstated mode `none` there
  ("anyone could reveal anyway", `LA-ONBOARD-1` OB-6), which handed classified keys raw to an anonymous link holder.
  `BiResultMasking.forPublicShare` reads the mode the Space STATES, else the shipped `typed`; a stated `none` keeps raw shares
  (D-M2). Session decision 2026-10-23, open to the operator's overturn.
- **The public share and a chart over a masked key.** The share viewer sends the chart's cosmetic category order; the public
  route now drops it as `/bi/query` does instead of answering 422 (every bar / line tile grouped by a masked key failed on a
  shared link).
Consumer matrix, pinned by `ControlApiBiMaskingReviewTest`: `/bi/query`, `/queries/{id}/run`, `/datasets/{id}/rows` and the
public share are masked for a viewer and raw for a revealer; the public share stays masked when a revealer's credential rides
along; a stated `none` opens every path; Report exports are always masked (`ReportJobMaskingTest`). Not changed, stated: a
reveal is not audited per BI read (D-P8 / D-M4; the audited reveal is Link Analysis's explicit one); a Report Job Run that
fails keeps the driver's text in its Run record (a two-step author channel through a calculated column; the Report rows
themselves are masked); two view-backed Datasets over the same view do not share a classification (`EvidenceMasker` matches
siblings by `physicalRef`; a pipeline-produced view is classified by its schema). Free-SQL shapes the same review found
already filed (the row as a value, renames in FROM, a decodable alias, a sort on an aliased key) are
`BI-MASKING-ROW-VALUE-LEAKS-1`. ⚠ Still open, found by the review's independent verification and filed as
`BI-MASKING-FREE-SQL-ORACLES-1` (an operator call): free SQL can still TEST a key while every output cell is masked - a saved
query's own `WHERE msisdn = '..'` (its author's text is not vetted the way `/db/query` text is), and a run-time error as a
yes / no answer (`CASE WHEN msisdn = '..' THEN error('x') END`: R2 withholds the text, not the failure).

**SQL shapes the position model cannot prove fail closed (`BI-MASKING-SQL-SHAPE-LEAKS-1`, fixed 2026-10-23, verified on
DuckDB 2.0.0.0).** Free SQL (a saved query, `/db/query`) is read through `json_serialize_sql`. An item that can expand to several
columns — any star (`*`, `t.*`, `COLUMNS(..)`) or `unnest` / `unpack` — makes the output positions untrusted, and then
EVERY output column is masked, a declared-clean name included (a key aliased `AS amount` would otherwise pass). Any star
or `#n` anywhere in the statement (a UNION, subquery or CTE included) engages this, since a self-join returns a second key
as `msisdn_1`; on `/db/query` a statement that is not one plain SELECT and holds one is refused 422. A
pattern star (`COLUMNS(..)`, `EXCLUDE`, `REPLACE`, `RENAME`) counts as reading a key even when the text never spells
it, and an alias of a key (lateral `msisdn AS k, k = '..'`) is followed. Ad-hoc `/db/query` is refused 422 for
`WHERE COLUMNS('msi.*') = ..`, `WHERE` / `HAVING` / `QUALIFY` / `ORDER BY` on an alias of a key, and `ORDER BY 1` /
`ORDER BY ALL` while a key (or a plain `*`) is selected, `ORDER BY COLUMNS(* REPLACE / EXCLUDE ..)`, and any `#n`
positional reference outside the select list; `ORDER BY 1` with no key selected and a bare `count(col)` / `count(*)` are
unchanged. Gotcha: on 2.0 `ORDER BY ALL` serialises as a `STAR` with `columns: true`, and a `REPLACE` star sits inside
`count(..)` as its argument. Tests: `ControlApiDbBrowserMaskingTest` (shape + oracle probes),
`ControlApiBiQueryMaskingTest.aSavedQueryWhosePositionsCannotBeProvenMasksEveryCell`. Still open, older shapes:
`BI-MASKING-ROW-VALUE-LEAKS-1` (table column aliases, the row as a value, a decodable alias).

## Row cap on `/bi/query`: ordered and honestly flagged (`BI-QUERY-TRUNCATION-1`, shipped 2026-10-08)

`MeasureCompiler.compile` always ends in a `LIMIT` (the route default is 500). Two defects made that cap lie:

* **Arbitrary subset.** A grouped spec with a `LIMIT` and no `orderBy` returned whichever groups DuckDB
  produced first (it promises no order), so a 532-group Dataset lost a planted offender on some runs. Now such
  a spec is ordered by its `groupBy` keys ascending (a scalar has nothing to order). An explicit `orderBy`
  is untouched, and `limit = Integer.MAX_VALUE` is the "no cap" sentinel (`RowShaper`, `DatasetMeasureProbe`)
  and stays unsorted. Materialize and report outputs were unordered before, so ordering them is a
  compatible narrowing, not a change of content.
* **`statistics.truncated` was always `false`.** The compiled statement carries its own `LIMIT n`, and
  `QueryExecutor` wraps it in `LIMIT n+1` to detect a cut, so it could never see row n+1. `/bi/query` and
  the share-link query now compile with `Spec.withLimit(limit + 1)` and let the executor trim, so the flag is
  exact (the risk-score evaluator already used this pattern). The echoed `sql` therefore shows `LIMIT n+1`.
  No response shape changed: `statistics.truncated` was already on the wire and the Widget host already shows
  *Showing the first N rows - the result was cut* when it is true.

The three other consumers that cut at a cap now use the same one-row-past probe (`BI-QUERY-TRUNCATION-1`, closed):

* **Risk Score evidence** - `evidenceSpec(cap + 1)`, so `evidenceTruncated` (already in the run's log line) is
  exact and a cut run logs a WARN. Evidence is explanation material: the score is never refused over it (the
  indicator VALUE read still refuses on truncation).
* **`materialize` Job** (default `limit` 1,000,000, ceiling 100,000,000) - FAILS the run when the result passes the
  cap, naming the cap and `limit:`; the prior snapshot stays visible and no `.tmp` is left. A snapshot that
  silently drops groups makes every downstream aggregate wrong, so completeness is a hard property. The only
  shipped config (`spaces/demo` `orders_by_region_materialize`, grouped by `region`) is far below the cap.
* **`report` Job, dataset scope** (default `limit` 10,000) - a human document, so it still delivers, but a cut
  report says so: the Job result reads `(TRUNCATED at N rows)`, a `REPORT_TRUNCATED` WARN event is emitted,
  `REPORT_READY` carries `truncated`, and `csv`/`xlsx` get a final row whose `_note` column reads
  `truncated at N rows`. A report that fits is byte-identical to before. `png`/`pdf` keep their own documented
  snapshot cap and `json` has no marker: for those the result message and the WARN event are the signal.

## Time grain on `/bi/query` (shipped 2026-08-14)

A widget's time bucket is part of the query, not a client-side afterthought:

* **Wire** — the body's optional `grains` object maps a **`groupBy` column** to `day`/`week`/`month`.
  A grain naming a column that is not grouped is a **422, not a silent no-op** — the caller believes it
  asked for a bucket. The vocabulary is pinned across the two languages by
  `inspecto-ui/src/app/inspecto/contracts/measure-grammar.contract.json` (`MeasureGrammarContractTest`),
  the same mechanism that pins the aggregate set.
* **Compilation** — `MeasureCompiler` emits `STRFTIME(DATE_TRUNC('<grain>', "col"), '<fmt>')` in the
  select list **and repeats the whole expression in the GROUP BY** (the bucketed dimension is aliased
  back to its own column name, so grouping by the alias would be ambiguous with the raw column).
* ⚠ **The bucket is TEXT, not a TIMESTAMP** — `%Y-%m-%d` for day/week, `%Y-%m` for month — because
  those are the exact keys the UI's offline `bucketValue` produces. Returning a timestamp would make
  one widget label its categories differently live and offline, which is the class of defect this
  change closed. DuckDB's `DATE_TRUNC('week', …)` is Monday-based and so is the offline bucket; that
  agreement is held by `MeasureCompilerGrainExecutionTest`, which **runs** the compiled SQL in the
  sandbox rather than string-comparing it.
* `SqlGuard` passes the bucketed statement unchanged — neither `STRFTIME` nor `DATE_TRUNC` is a
  file/extension surface — and the route re-checks it as always.

## Hidden-aggregate `orderBy` (`CATEGORY-SORT-ORDER-1` sub-item 1, shipped 2026-10-11)

An `orderBy` entry `{field, dir, agg?}` may name a **Dataset column that is not in the result** (an ungrouped ordinal
such as `stage_order`): `MeasureCompiler` emits `ORDER BY MIN("stage_order") ASC` while `GROUP BY` and the returned
columns stay unchanged. `agg` is the closed set `min` (default) | `max`; an entry with `agg`, or whose `field` is not a
grouped Dimension / Measure id, is a hidden term (`Sort.hidden()`). It needs at least one Measure and one `groupBy`
(else 422). `BiRoutes.vetHiddenOrderColumns` then 422s a column the Dataset's declared `columns` do not list, and
**refuses a classified column** (`EvidenceMasker.sensitiveColumns`, incl. fail-closed untraceable lineage) because the
row order would leak the values' rank. Absent `agg` over a result column is exactly the old behaviour. UI side:
Widget `options.categoryOrder:{field,dir,agg?}` read by `buildXyQuery`.
