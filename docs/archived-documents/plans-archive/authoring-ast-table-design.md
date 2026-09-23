# Structured AST table over SQL — design (`AUTHORING-REDESIGN-1` clause (c))

> **Status (2026-09-23): COMPLETE + ARCHIVED. Steps 0–4 shipped under the operator's §7 answers; the plan stops at step 4 for good (Q2).** Originally (2026-09-16) DESIGN ONLY. This is the decision
> pass the board asked for on clause (c) — *"v2 structured AST table over the SQL, for WHERE/JOIN
> editing"*.
>
> **The precondition is discharged and then some.** The board recorded 2026-09-07 that
> `json_serialize_sql` works on a sealed connection, so (c) reads an **engine-produced AST** rather than
> re-implementing a SQL parser in TypeScript. That holds, and this pass adds the half the board never
> measured: **`json_deserialize_sql` exists, works on a sealed connection, and accepts a hand-mutated
> AST** — so the round trip is real, not hypothetical (§3, measured).
>
> 🔴 **But three findings refute the clause as written, and one of them is structural.** Read §2 before
> §4. In short: the clause names a **pane that, by three shipped and operator-affirmed decisions, is the
> wrong home** (§2.1); the pin the board cites covers only **half** the capability (§2.2); and for the
> one Step that genuinely has a `WHERE`, a **structured predicate editor already exists and is already
> adopted on five hosts** (§2.4), which makes the AST's job *recognition*, not authoring.
>
> **Step 0 SHIPPED (2026-09-23) — all four premises HELD on the real driver.** Four sibling pins in
> `SqlSandboxTest` (duckdb_jdbc 1.5.2.1, sealed connection): R1+R2 round trip incl. a hand-mutated AST,
> T2, T3, T4. Results in §6 Step 0. Steps 1-5 remain unbuilt, pending §7.
>
> **Decision owner:** the operator, on the five calls in §7. ⚠ Per the `dataset-column-derivation-plan`
> lesson — *a plan is where a decision is described, the board is where it is queued* — §7 must be filed
> into `BACKLOG.md` §1 by the main thread, or this pass stalls the way that one did for three days.
> ⛔ This plan does not answer them.

---

## 1. What the row asked for

> *"(c) v2 structured AST table over the SQL for WHERE/JOIN editing — ✅ precondition DISCHARGED
> 2026-09-07 … `json_serialize_sql`, which returns the whole parsed AST as JSON … ⚠ So (c) reads an
> engine-produced AST rather than re-implementing a SQL parser in TypeScript — the same refusal the step
> workbench made for reference detection."*
> — `docs/BACKLOG.md:608`

And, from the OKF concept this row points at:

> *"It is NOT a SQL parser and needs no DuckDB extension; the AST table of BACKLOG
> AUTHORING-REDESIGN-1 (c) remains the way to make a `WHERE` structurally editable."*
> — `docs/okf/frontend/features/schema-mapping-authoring.md:85`

Two claims are load-bearing and both survive this pass: **the AST comes from the engine**, and **a
`WHERE` is the thing worth making structural**. The *location* does not survive.

---

## 2. Grounding — what is actually true today

Six findings. **Three contradict the clause or the framing it was handed to this pass with.**

| # | Finding | Where |
|---|---|---|
| 1 | 🔴 **The Transform pane is the wrong home.** `transform.sql` shipped with *no* `WHERE` and *no* `JOIN`, by a decision (D3) the operator re-affirmed twice | `schema-mapping-authoring.md:42,84` |
| 2 | 🔴 **The cited pin covers only the READ half** — `json_deserialize_sql` appears nowhere in the repo, test or main | `SqlSandboxTest.java:105-135` |
| 3 | 🔴 **A structured predicate editor already exists**, recursive, with five adopters and a SQL compiler | `inspecto/query/query-condition-group.component.ts` |
| 4 | ⚠ **`transform.join` has no predicate to build a table over** — `reference` + an equality key list, no join type, no condition | `NodeAttributes.java:394` |
| 5 | ⚠ **`SqlGuard` is the wrong gate for a parse-only route** and would false-reject legal predicates | `inspecto-sql/.../SqlGuard.java` |
| 6 | ⚠ **Neither function has a single production caller.** Every behaviour below is measured here first | repo-wide grep |

### 2.1 🔴 The Transform pane deliberately has no WHERE and no JOIN

`transform.sql`'s pane (`pipeline-transform-sql-definition.component.ts`, mounted from
`pipeline-editor.component.html:736`) is a fields grid over a function catalog plus a raw-SQL view. Its
shipped design decisions say, verbatim:

- **D3 — no `where`; filtering stays `transform.filter`.** Recorded 2026-09-04, restated 2026-09-05 as
  *"D3 unchanged: filtering belongs to a Filter Step"*.
- The bounded reconciler (`pipeline-transform-sql-reconcile.ts:34-35`) **refuses** anything past a flat
  projection, and its user-visible refusal string is: *"This SQL is more than a list of output columns,
  so it cannot be shown as fields. Filtering and joining belong to their own Steps."*
- The Fields tab is genuinely `[disabled]` with that reason as its tooltip — not hidden, not soft.

⇒ **Building a WHERE/JOIN editor on that pane would contradict the one sentence the pane already says to
the author.** The surface would tell an operator "filtering belongs to its own Step" in the tab tooltip
and then offer a filter editor two inches below it.

Where the two clauses actually live:

| Clause | Real home | Config key | Pane today |
|---|---|---|---|
| `WHERE` | **`transform.filter`** | `where` — a **bare SQL predicate string**, not a SELECT (`NodeAttributes.java:295`, tier `required`, `required:false`, placeholder `amount > 0`) | the **generic** schema-form pane, `app-pipeline-config-definition` (`pipeline-editor.component.html:773-790`) |
| `JOIN` | **`transform.join`** | `reference` (autocomplete) + `on` (list of equality keys). **No join type, no non-equi condition** (`NodeAttributes.java:394`) | same generic pane |

⚠ Note what finding 4 means: **`transform.join` has no SQL at all to parse.** Its config is already
structured — two typed keys. An "AST table over the SQL" for JOIN editing has no input. The JOIN half of
clause (c) is not blocked; it is **empty**. Whatever a richer join editor should be (join type, non-equi
conditions), it is a *node-attribute* question for `NodeAttributes`/`RowShaper`, not an AST question.

### 2.2 🔴 The pin discharges the read half only

`SqlSandboxTest.jsonWorksOnASealedConnection` (`inspecto-sql/src/test/java/com/gamma/sql/SqlSandboxTest.java:105-135`)
opens a `SqlSandbox`, calls `seal()`, and asserts three things on the sealed connection: `json_extract`,
`json_structure`, and — with the comment *"The AST route itself. A structured editor reads THIS, not a
hand-written parser."* — that `json_serialize_sql('SELECT a FROM t WHERE b > 1')` returns JSON
containing `"error":false`, `where_clause` and `COMPARE_GREATERTHAN`. It then re-proves the seal
(`SET enable_external_access=true` and `INSTALL excel` both throw).

**`json_deserialize_sql` is not mentioned.** *(Closed 2026-09-23 by Step 0 — see §6.)* A repo-wide grep for either function across `src/main` and
`src/test` returns hits only in that one test file and in prose docs. ⇒ The board's *"precondition
DISCHARGED … Pinned by `SqlSandboxTest`"* is accurate about reading and **silent about writing**, which
is the half clause (c) needs the moment the table becomes editable. Extending that test is **step 0** of
the work, not a nicety (§6).

`SqlSandbox` itself: `open(policy)` disables `autoinstall_known_extensions` / `autoload_known_extensions`
and caps memory/threads with file access still on; `seal()` sets `enable_external_access=false` +
`lock_configuration=true` (`SqlSandbox.java:58,93`). The control plane (`inspecto`) already depends on
`inspecto-sql` (`inspecto/pom.xml:53`), so a route can reach it with no new module wiring.

### 2.3 The precedent route does *not* use the sandbox

`POST /components/transform/describe` (`ComponentRoutes.java:68,112-152`) is the closest existing seam —
execution-free type derivation. It runs `SqlGuard.check(sql)` (422 on violation), then
`TypeFlow.describe(inputColumns, sql)`, which opens a **plain in-memory `jdbc:duckdb:` connection**,
creates an empty scratch `input` table and runs `DESCRIBE <sql>` (`TypeFlow.java:92-111`). No
`SqlSandbox`, no temp DB file. Request `{sql, inputColumns[]}` → response `{columns:[{name,type}]}`;
400 on missing input, 422 on guard violation or a DuckDB binder error with the JDBC wrapper line
stripped.

⚠ That precedent matters for cost: `SqlSandbox.open` creates a **temp DB file on disk** per call, which
is the wrong shape for a 300 ms-debounced keystroke route. See §4 and §7-Q4.

### 2.4 🔴 A structured predicate editor already exists — and it already compiles to SQL

`inspecto/query/` is a **Query Core**, not a helper:

- `query-types.ts` — `ColumnMeta`, `Operator` (`= != < <= > >= contains startsWith endsWith in between
  isNull isNotNull`), `Condition {field, operator, value, value2}`, recursive
  `ConditionGroup {op:'AND'|'OR', items[]}`.
- `query-sql.ts:7` `compileSql(model, source)`; **`:21` `compileWhere(where, cols)` emits a bare WHERE
  body** — precisely the string `transform.filter.where` stores.
- `query-condition-group.component.ts` — `<inspecto-query-condition-group>`, recursive, inputs
  `group` (required) / `columns` / `root`, output `changed`. ⚠ **It mutates the bound `ConditionGroup`
  in place** — a host must deep-clone before binding (the trap the `angular-ui` skill already records).
- Adopters: `decision-rule-form.dialog:149`, `alert-rule-form.dialog:117`,
  `expectation-form.dialog:140`, `data-table.component.html:114`, `dashboard-editor.component.html:110`.

⇒ **The forward direction — structure → SQL — is solved and shipped.** What is missing is the *reverse*:
turning a stored free-text `where:` back into a `ConditionGroup` faithfully enough to edit. That, and
only that, is the AST's job. It is the same shape as the bounded reconciler the Transform pane already
uses for projections — with an engine parse where the reconciler uses regexes.

**No JOIN structure exists anywhere in the Query Core.** `transform.join` gets zero reuse from it (§2.1).

### 2.5 `SqlGuard` is the wrong gate here

`SqlGuard` (`inspecto-sql/src/main/java/com/gamma/sql/SqlGuard.java`) is a **lexical** allowlist: it
strips comments, rejects multi-statement, requires the text to start with `SELECT`/`WITH`, and blocks a
function-name and keyword list (`read_*`, `*_scan`, `copy`, `glob`, `system`, `set`, `pragma`, `create`,
`insert`, `replace`, `analyze`, …). It returns findings, never throws.

Two reasons it does not fit a parse-only AST route: a **bare predicate fragment** (`amount > 0`) never
starts with `SELECT` and is refused outright; and its keyword blocklist **false-rejects legal
predicates** containing `set`, `replace` or `analyze` as ordinary identifiers or function calls. A false
refusal in an authoring pane is the failure mode this repo has already paid for once — the pane's own
notes record that assuming VARCHAR made DuckDB refuse valid SQL and *"a false refusal in an authoring
pane is worse than no derivation at all."* See §7-Q3.

---

## 3. The round-trip question, answered with measurement

**This is the hardest question the pass was given, and it has a definitive answer: the round trip goes
back through DuckDB, and it works.**

Measured against `duckdb_jdbc` **1.5.2.1** (`pom.xml:182`), engine reporting `v1.5.2`, on a connection
sealed exactly as `SqlSandbox.seal()` seals it (`SET enable_external_access=false;
SET lock_configuration=true`), with `INSTALL excel` and `SET enable_external_access=true` both re-proven
to fail on the same connection. Throwaway probes; nothing committed.

### 3.1 What works

| # | Property | Evidence |
|---|---|---|
| **R1** | **`json_deserialize_sql` exists and runs on a sealed connection.** `json_deserialize_sql(json_serialize_sql('SELECT a FROM t WHERE b > 1'))` → `SELECT a FROM t WHERE (b > 1)` | measured |
| **R2** | 🟢 **A hand-mutated AST deserializes.** Editing the serialized JSON — literal `1`→`99` **and** `COMPARE_GREATERTHAN`→`COMPARE_LESSTHAN` — emitted `… WHERE ((t.a < 99) AND (r.b IS NOT NULL))`. **This is the finding that makes an *editable* AST table possible at all** | measured |
| **R3** | 🟢 **Deleting a clause works.** Setting `"where_clause":null` emitted the same statement with the `WHERE` gone | measured |
| **R4** | 🟢 **Idempotent after one pass.** `deserialize(serialize(x))` == `deserialize(serialize(deserialize(serialize(x))))`, byte-identical. ⇒ dirty-detection will not churn | measured |
| **R5** | **Parse-only — no catalog binding.** `SELECT nope FROM does_not_exist` serializes with `"error":false`. No table, view or sample data need exist | measured |
| **R6** | **Broad SQL coverage round-trips:** JOIN/ON, `AND`/`OR` conjunctions, `IS NOT NULL`, `BETWEEN`, `LIKE`, `IN (list)`, `IN (subquery)`, CTEs, `GROUP BY`/`HAVING`/`ORDER BY`/`LIMIT`, window functions, `UNION ALL`, `LEFT JOIN … USING`, quoted identifiers with spaces, `TRY_CAST`, escaped string literals | measured |

### 3.2 What breaks — and these are the design constraints

| # | Trap | Evidence |
|---|---|---|
| **T1** | 🔴 **The round trip is LOSSY IN SPELLING.** Comments are **dropped**; `count(*)`→`count_star()`, `LIKE`→`~~`, `<>`→`!=`, `IN (SELECT …)`→`= ANY(SELECT …)`; identifiers are re-quoted (`input`→`"input"`); redundant parentheses are added everywhere (`ON ((t.k = r.k))`); `UNION` operands get wrapped. **The author's text does not survive the first save.** This is the whole of §5 | measured |
| **T2** | 🔴 **`json_serialize_sql` refuses a bare bind parameter.** `SELECT json_serialize_sql(?)` fails **at prepare** with *"json_serialize_sql first argument must be a VARCHAR"*. `?::VARCHAR` and `CAST(? AS VARCHAR)` both bind fine. ⚠ **The trap is that the naive form fails loudly and string concatenation "fixes" it** — in a route whose entire input is author-written SQL. Bind with an explicit cast; never concatenate | measured |
| **T3** | 🔴 **`skip_null := true, skip_empty := true` produces a ONE-WAY AST.** It is 30 % smaller (1065 vs 1554 bytes on a JOIN+WHERE) and **`json_deserialize_sql` refuses it**: *"Expected but did not find property 'cte_map'"*. The obvious payload optimisation silently breaks the write path | measured |
| **T4** | ⚠ **No end offsets.** Every node carries `query_location` (a start offset into the original text) but **no end offset**. ⇒ surgical text-splicing — the one technique that would preserve the author's formatting and comments — is **not reliably available** from this AST. See §7-Q2 | measured |
| **T5** | ⚠ **Only SELECT serializes.** `DELETE FROM t WHERE a=1` returns `{"error":true,"error_type":"not implemented","error_message":"Only SELECT statements can be serialized to json!"}`. A trailing `; DROP TABLE t;` makes the **whole** call refuse. A free safety property — but it is a property of the function, not a gate we own | measured |
| **T6** | ⚠ **A parse error is DATA, not an exception.** `SELECT FROM WHERE` → `{"error":true,"error_type":"parser","error_message":"syntax error at or near \"WHERE\"","position":"12","error_subtype":"SYNTAX_ERROR"}`. ⇒ the route must branch on `error`, not on a thrown `SQLException`. Deserializing an error-AST *does* throw | measured |
| **T7** | ⚠ **A bare predicate is not a statement.** `transform.filter.where` stores `amount > 0`, which cannot be serialized alone. It must be wrapped (`SELECT 1 FROM input WHERE <pred>`), the `where_clause` read out, and on the way back the emitted statement sliced after `" WHERE "`. **Measured to work** on four real predicates — but the slice is a string operation on engine output, and it is exactly where a future DuckDB emitter change breaks us silently | measured |

### 3.3 The answer

**Round-tripping goes back through DuckDB via `json_deserialize_sql` — not through edits applied to the
SQL text.** Text-splicing was the attractive alternative precisely because it preserves formatting, and
**T4 kills it**: the AST gives a start offset and no end, so there is no reliable span to replace.

⚠ **But "the round trip works" is not "the round trip is safe to apply silently."** T1 means the first
structural edit **rewrites the entire statement** in DuckDB's canonical spelling and **discards the
author's comments**. That is a data-loss event in an authoring surface. §5 is the answer to it, and it
is the reason slice 1 in §6 writes nothing at all.

---

## 4. Where the AST crosses the wire

**A new route is needed.** No existing route carries an AST: `/components/transform/describe` returns
`{columns[]}` only; `POST /db/query` and `GET /db/table` (`DbBrowserRoutes.java:54-55,127,145`) **execute**
SQL and return result sets, which is the opposite of what this needs (R5: nothing should execute).

Recommended, both in `ComponentRoutes` beside `describe`, both **non-mutating reads**:

| Route | Direction | Body | Response |
|---|---|---|---|
| **`POST /components/sql/ast`** | SQL → AST | `{sql, fragment?: 'statement' \| 'predicate'}` | `{ok: true, ast: {…}}` · `{ok: false, error: {message, position, subtype}}` (**200 either way** — a parse failure is the answer, not a server error) |
| **`POST /components/sql/text`** | AST → SQL | `{ast, fragment?}` | `{ok: true, sql}` · 422 `{error}` when the AST is unparseable |

Naming notes: `sql/` rather than `transform/` because the first real consumer is **`transform.filter`**,
not `transform.sql` (§2.1), and a predicate editor is not transform-specific. ⛔ Do not name it
`/components/transform/ast` — that would bake in exactly the wrong home the clause already assumed.

**Per the `endpoint` skill's gate order**, both routes are pure reads: no write-root check, no path jail,
no conflict. The gates that do apply are spec/validation (400 on missing `sql`/`ast`) and the guard
question in §7-Q3. Each needs a real-HTTP test class covering every gate, including the 200-with-`ok:false`
case, which is the one an implementer will reflexively turn into a 422.

**Response shape:** the AST is DuckDB's own JSON, **passed through verbatim** — 1.5 KB for a
JOIN+WHERE. ⛔ Do not reshape it server-side into a house model: that is a second parser by another
name, and it is the refusal this clause exists to honour. ⛔ And do not slim it with
`skip_null`/`skip_empty` (T3) — the saving is 30 % and the cost is the entire write path.

**Connection:** mirror `TypeFlow.describe`'s plain in-memory `jdbc:duckdb:` connection (§2.3) rather
than `SqlSandbox.open`, which writes a temp DB file per call. ⚠ But the *pin* lives on the sealed
connection, so the seal must still be applied on whatever connection is used, or the test in §6 step 0
is proving a different thing than production runs. See §7-Q4.

---

## 5. The degrade path — explicit refusal over silent loss

The repo's house style, and the answer to T1. Three tiers, in order of how much the AST can be trusted:

1. **Not parseable** (`ok:false`, T6) → the structured table does not render. In its place an
   `<inspecto-alert variant="warning">` carrying DuckDB's own message and `position` verbatim, and the
   free-text `where` control stays fully editable. ⛔ Never block saving because the AST route could not
   read the text — the existing `where` field saved fine before this feature existed and must keep doing
   so. This mirrors the pane's shipped rule that only a 422 blocks Apply while offline/404/503 leave it
   available.
2. **Parseable but not expressible** in the structured vocabulary — a subquery, a window function, a
   function call the `Operator` union has no member for, an `OR` nested past the editor's depth → the
   table renders **read-only**, with a one-line reason and the free-text control as the edit path. This
   is the reconciler's `unsupported` idiom (`pipeline-transform-sql-reconcile.ts:34`) reused, and its
   refusal string is the tone to match.
3. **Parseable and expressible, but the re-emit differs from the author's text** (T1 — which is
   *almost always*, since even `a > 1` comes back as `(a > 1)`) → **show the author the exact before/after
   SQL and require an explicit accept before the rewrite is written.** ⛔ Never apply it silently. The
   author wrote `amount > 100 AND status <> 'VOID'`; the engine returns
   `((amount > 100) AND (status != 'VOID'))`; those are the same predicate and **not** the same text,
   and comments in between are gone.

⚠ **Tier 3 is the one that will be argued away.** It looks like friction over a cosmetic difference. It
is not: it is the only point at which an author can notice that their comment was deleted, and T1 is
measured, not feared. The cheap mitigation that removes the friction honestly is §6 slice 1 — **a
read-only table writes nothing, so tier 3 never fires.**

---

## 6. Sequenced steps — smallest shippable slice first

Each slice is independently shippable and independently valuable. ⛔ Slice 1 is not a stepping stone to
be skipped; it is the slice with the best value-to-risk ratio in the set.

- **Step 0 — extend the pin (backend, ~20 lines, no feature).** Add `json_deserialize_sql` to
  `SqlSandboxTest.jsonWorksOnASealedConnection` (or a sibling): serialize → deserialize on the sealed
  connection, plus the three traps that will otherwise be rediscovered by whoever implements —
  **T2** (bind needs `::VARCHAR`), **T3** (`skip_null` does not deserialize), **T4** (no end offsets).
  ⇒ **Worth doing even if nothing below is ever built:** it closes the half-discharged precondition in
  §2.2 and converts this plan's measurements into repo-owned evidence that a DuckDB bump would break
  loudly. Do this first.

  ✅ **DONE 2026-09-23 — every premise HELD, none refuted.** Four sibling tests in
  `inspecto-sql/src/test/java/com/gamma/sql/SqlSandboxTest.java`, all on a `SqlSandbox` after `seal()`:

  | Pin | Premise | Result |
  |---|---|---|
  | `astRoundTripsThroughDeserializeOnASealedConnection` | R1 serialize→deserialize; R2 a hand-mutated AST (`COMPARE_GREATERTHAN`→`COMPARE_LESSTHAN`) deserializes | HELD — exact strings `SELECT a FROM t WHERE (b > 1)` / `… (b < 1)` |
  | `serializeNeedsAnExplicitVarcharCastOnItsBindParameter` | T2 bare `?` refused, `?::VARCHAR` binds | HELD — message contains *"must be a VARCHAR"* |
  | `slimmedAstDoesNotDeserialize` | T3 `skip_null/skip_empty` AST is smaller and one-way | HELD — deserialize throws naming `cte_map` |
  | `astCarriesStartOffsetsButNoEndOffsets` | T4 `query_location` present, no end/span-like key | HELD — no key matching `location/offset/end/length/len/stop/span` besides `query_location` |

  ⚠ The T4 pin is a key-name scan of one small AST, so it proves *no end-offset key is emitted for that
  statement*, not a DuckDB guarantee; a bump that adds one fails the pin loudly, which is the intent.
  ⚠ R3-R6 and T1/T5-T7 remain plan-measured only, not pinned — Step 0 named only T2-T4.

- **Step 1 — the route, read direction only.** `POST /components/sql/ast` per §4, plus its real-HTTP
  test class (200/`ok:true`, 200/`ok:false` on a syntax error, 400 on missing `sql`, the `fragment:
  'predicate'` wrapping of T7, and the §7-Q3 guard decision once taken). No UI.

- **Step 2 — the READ-ONLY structured table, on `transform.filter`.** A presentational component in
  `inspecto/query/` (not a feature — `transform.filter` is one host, `expectation`'s `condition` is a
  plausible second), taking the AST and rendering the predicate as a nested table: connector · left ·
  operator · right, one row per leaf, indented per group. **Writes nothing.** The existing free-text
  `where` field stays the only edit path. Degrade tiers 1 and 2 (§5) ship here; tier 3 cannot fire.
  ⇒ This is the smallest slice that delivers the row's actual value — *a non-technical operator can see
  what a stored predicate means* — at zero round-trip risk.

- **Step 3 — recognition into `ConditionGroup`.** A pure, framework-free
  `astToConditionGroup(ast): ConditionGroup | {unsupported: string}` in `inspecto/query/`, unit-tested
  like the reconciler is. **Bounded on purpose**: accept only what `Operator` can express and what
  `compileWhere` regenerates identically — *a recognition that would rewrite the predicate is not a
  recognition*, the rule `pipeline-transform-sql-reconcile.ts` already states for `TRY_CAST`. Still no
  writes; this step's deliverable is the admission test that decides whether step 4 may offer editing.

- **Step 4 — editing, via the existing editor.** Where step 3 succeeds, `transform.filter`'s `where`
  field offers `<inspecto-query-condition-group>` (deep-cloned — §2.4) and saves via the shipped
  `compileWhere`. ⚠ **The AST is never written back through `json_deserialize_sql` on this path** — the
  Query Core compiles the SQL, so T1's rewrite is bounded to a predicate the author has just edited
  structurally, and degrade tier 3 is a genuine before/after on a genuinely changed value.
  ⇒ At the end of step 4 the engine AST has been used exactly as the row intended — **to read** — and the
  write path is the one the repo already ships and tests. The `json_deserialize_sql` write path is
  **held in reserve**, pinned by step 0, for a case steps 3-4 cannot reach.

- **Step 5 — `transform.join`: out of scope, and say so.** §2.1 finding 4: there is no SQL to parse.
  Whatever a richer join editor should be is a `NodeAttributes`/`RowShaper` question. ⛔ Do not open it
  under this clause; file it as its own row so it is not read as blocked on an AST it does not need.

**What this sequence deliberately does not do:** put anything on the `transform.sql` pane (§2.1), reshape
the AST server-side (§4), or write a re-emitted statement without the author's accept (§5).

---

## 7. Operator calls — ANSWERED 2026-09-23

✅ **The operator answered all five on 2026-09-23. The plan is COMPLETE and stops at step 4 for good.**

| Q | Decision | As built |
|---|---|---|
| Q1 | **(a) RE-HOME** (c) to `transform.filter`, the Filter Step's predicate. D3 stands; nothing lands on the `transform.sql` pane | `app-pipeline-filter-predicate` inside the generic config pane, only for `transform.filter` |
| Q2 | **Author formatting and comments MUST survive.** The AST is READ-ONLY; nothing ever writes authored SQL back through it. The plan **stops at step 4 permanently**; the `json_deserialize_sql` reserve path is closed | No AST→SQL route or function exists. Step 4 writes via `compileWhere` only on an explicit accept of the exact before/after; a predicate carrying a comment is never offered for structured editing |
| Q3 | **(a) no extra guard** on `POST /components/sql/ast`: serialize-only, non-SELECT refused by T5. `describe` keeps `SqlGuard` | `ControlApiSqlAstTest.thereIsNoLexicalGuardBecauseNothingBinds` |
| Q4 | **Sealed `SqlSandbox`**: fidelity to the Step 0 pins over per-call cost | `SqlAst.parse` opens + seals per call |
| Q5 | **A contract pinning ONLY the AST keys the SPA reads**, not the whole shape | `sql-ast.contract.json` + `SqlAstContractTest` (engine side) + `sql-ast.spec.ts` (reader side). `query_location` is deliberately NOT pinned: the SPA never reads it |

Built: step 1 `b2abe387` (route), steps 2–4 `7328a251` (SPA). Step 5 (`transform.join`) stays out of scope, as §6 said.

The questions as they were put:

- **Q1 — Does clause (c) move off the Transform pane?** §2.1 says the clause names a pane whose shipped,
  twice-affirmed design excludes both `WHERE` and `JOIN`, and that the real home is `transform.filter`.
  Three ways out: **(a)** re-home the clause to `transform.filter` and reword the row (this plan's
  recommendation, and what §6 assumes); **(b)** reverse D3 and bring filtering into `transform.sql`
  — a product decision far larger than (c), touching the reconciler, the disabled-tab reason and the Step
  vocabulary; **(c)** drop (c). ⚠ The operator has flipped a Pipelines-pane call three times in one week
  before (`angular-ui` §4, the select-opens-configuration call) — so record the reasoning, not just the
  verdict.
- **Q2 — Is formatting preservation a requirement?** T1 + T4: any AST-mediated write reformats the whole
  statement and deletes comments, and no end offsets exist to splice around it. If preserving an
  author's text is required, the AST **cannot** be the write path at any slice and §6 stops at step 4
  permanently. If it is not required, the reserve write path stays open. ⚠ Nobody has asked an author
  whether they comment their `where:` predicates; the committed configs are the place to check before
  this is put to the operator (§8).
- **Q3 — What guards `/components/sql/ast`?** §2.5: `SqlGuard` is lexical, cannot see a bare predicate,
  and false-rejects legal predicates containing `set`/`replace`/`analyze`. Three options: **(a)** no
  guard — nothing executes, and T5 already refuses non-SELECT; **(b)** a new narrow fragment check;
  **(c)** `SqlGuard` anyway, accepting the false refusals. ⚠ Option (a) is defensible on this route
  specifically and would be **wrong** to generalise — the sibling `describe` route *does* execute a
  `DESCRIBE` and must keep its guard. Whoever answers this must not "make the two consistent."
- **Q4 — Sealed sandbox, or a plain in-memory connection?** §2.3/§4: the pinned evidence is on a *sealed*
  connection; the precedent route (`TypeFlow.describe`) uses an unsealed in-memory one; `SqlSandbox.open`
  writes a temp DB file per call, which is the wrong cost for a debounced keystroke route. The trade is
  fidelity-to-the-pin versus per-call cost, and it decides what step 0's test actually proves.
- **Q5 — Does the DuckDB AST shape become a compatibility surface?** The AST is DuckDB's internal parse
  tree, passed through verbatim (§4) and consumed by TypeScript. A DuckDB upgrade may rename a node class
  or add a required property with no deprecation — `json_deserialize_sql` already refuses an AST missing
  `cte_map` (T3). Pinning it as a `*.contract.json` the way the repo pins eight other cross-language
  agreements would be the house answer, but ⛔ **we do not own this contract and cannot hold DuckDB to
  it** — which is a genuinely different situation from every existing contract file. ⚠ Same trap the
  time-zone list carries (`angular-ui` §4): a suggestion list is not a gate.

---

## 8. Outstanding grounding

- ⚠ **Not measured: how much committed SQL would actually degrade.** §5's three tiers are designed
  blind. Before step 2, count across tracked `*_pipeline.toon` how many `transform.filter` nodes exist,
  what their `where:` predicates look like, how many carry SQL comments (→ Q2), and how many fall inside
  the `Operator` union (→ step 3's bound). If the answer is "four predicates, all `col > n`", the
  read-only table in step 2 is most of the feature and steps 3-4 may not be worth building.
- ⚠ **Not measured: AST payload cost at realistic size.** 1.5 KB for a two-table JOIN+WHERE. The pane's
  sibling `describe` route is 300 ms-debounced per keystroke; the AST route would be too, and T3 forbids
  the obvious slimming. Measure on the largest committed predicate before choosing a debounce.
- ✅ Grounded and needing no further work: the round trip (§3, measured), the two Step homes and their
  config keys (§2.1), the pin's coverage (§2.2), the Query Core's API and its five adopters (§2.4),
  `SqlGuard`'s behaviour (§2.5).

---

## 9. Accessibility — an editable data table against WCAG 2.2 AA + axe-core

**There is no inline-cell-editing precedent in `<inspecto-data-table>`** — its ag-Grid hosts are
read-only plus a CodeMirror SQL panel. The two real precedents in the repo are both **hand-built tables
of native controls**, and both are the right model here:

- `pipeline-transform-sql-definition.component.html:160-270` — native `<input>`/`<select>` per cell
  (deliberately *not* `mat-select`, for 600+ rows), each carrying
  `[attr.aria-label]="'<Field> row ' + row.seq"`.
- `pipeline-extra-config.component.ts:97-197` — property rows with an explicit edit toggle,
  `aria-label="(isEditing(e) ? 'Done editing ' : 'Edit ') + e.key"` and `aria-labelledby` on each input.

⇒ **Build the AST table as a semantic `<table>` of native controls, not as an ag-Grid host.** A nested
predicate is a tree, and ag-Grid's row model plus its horizontal virtualization (which the skill records
as having hidden a `[rowActions]` column entirely) are both working against that.

Requirements, beyond the general `angular-ui` §6 rules:

1. **`scope="col"` on every header**; the group nesting is conveyed by `aria-level` on the row, **not by
   indentation alone** — indentation is a visual-only cue and fails the same way color-alone does.
2. **Every cell control is individually labelled.** A dense grid of bare `<select>`s is the classic axe
   `aria-input-field-name` / `select-name` failure. Follow the transform grid: a positional
   `aria-label` naming the column *and* the row (`"Operator, condition 3"`).
3. **Errors are explicit `role="alert"` lines, never `<mat-error>`.** The skill records this twice —
   `type: 'list'` and `<inspecto-option-picker>` both shipped with errors that could never fire because
   no `NgControl` reached the `<mat-form-field>`. A hand-built table has no form field at all. ⚠ **And
   assert the rendered `[role="alert"]` element in the spec**, not that a getter returned the string — a
   spec asserting the getter passes while nothing reaches the screen.
4. **The read-only tier must not be a disabled editable table.** A grid of `disabled` selects is
   unreachable by keyboard and announces nothing useful; render plain text plus the reason. Per §8 of
   the error-handling rules, when the explained state *replaces* the table use `@if/@else` — a
   kept-mounted empty grid fails axe `aria-required-children`.
5. **WCAG 2.2-specific, and easy to miss because 2.1 did not require them:** **2.5.8 Target Size
   (Minimum, 24×24)** — a dense predicate row invites 16px icon buttons; **2.4.11 Focus Not Obscured** —
   the pane lives in the editor's right dock behind an `inspectoSplit` handle, and a sticky header or
   the dock edge must not cover a focused cell; **2.5.7 Dragging Movements** — if group reordering is
   ever added, it needs a keyboard-and-single-pointer path, not drag alone.
6. **`await expectNoA11yViolations(fixture.nativeElement)`** in the new component's spec
   (`inspecto/testing/a11y.ts`), which runs in CI via `npm run test:coverage`. ⚠ Give the spec host an
   explicit `ChangeDetectionStrategy.Eager` — under Angular 22 an unspecified strategy is OnPush and a
   host field mutated after the first `detectChanges` never re-renders. Import
   `{describe, expect, it}` from `vitest` explicitly, and check the **exit code**, not the pass count.

---

## 10. State flow and component ownership

Per `angular-ui` §3/§4/§10 — service → signal → template, no business logic in a component:

| Layer | Artifact | Rule |
|---|---|---|
| **API** | `ComponentsService` (`inspecto/api/components.service.ts`) gains `sqlAst(sql, fragment?)` and, if Q2 ever opens the reserve write path, `sqlText(ast, fragment?)`. Built with `apiUrl()`, exported from the `index.ts` barrel | ⚠ Both are space-scoped through `spaceInterceptor` automatically — no per-feature scoping |
| **Logic** | `astToConditionGroup` + the AST→row-model flattening live **framework-free** in `inspecto/query/`, unit-tested standalone like `pipeline-transform-sql-reconcile.ts` | ⛔ Never in the component. ⚠ `inspecto/query/` already imports `@angular/*` elsewhere, so purity is the target for new files, not a property of the folder |
| **Shared component** | `<inspecto-sql-ast-table>` in `inspecto/query/` — presentational, `@Input` AST / `@Output` change, **no HTTP**, standalone + `OnPush` | It has at least two plausible hosts (`transform.filter`, `expectation.condition`), so it belongs in `inspecto/`, not under `modules/admin/pipelines/` |
| **Host** | `transform.filter` renders through the **generic** `app-pipeline-config-definition` pane (`pipeline-editor.component.html:773-790`) today. Adopting the table means giving `transform.filter` its own branch in that `@if` chain, as `transform.sql` and the parse nodes already have | ⚠ It mounts inside `<inspecto-definition-drawer>` in the right dock — so it inherits the dock's maximize rule (`absolute inset-0 z-20`, never `width:100%`) and, if it tabs, the R9 rule that tab panels live **outside** the `mat-tab` bodies |
| **State** | Host signals: `ast` (fed by an effect off the debounced service call — **not** a `computed`, since it is async), `unsupportedReason`, `pendingRewrite`. Dirty is derived by comparing the **compiled predicate** to what was loaded, never from raw signal mutation | ⚠ The pane's own history: binding an editor's `valueChange` straight to a signal emitted no `dirtyChange` and shipped broken. ⚠ And a picker/dialog change does not bubble a host `click` — a host deriving dirtiness from `@HostListener('click')` needs `document:click` too |

---

## 11. Vocabulary

Canonical per `docs/GLOSSARY.md`. A **Step** is the user-facing name for a pipeline node — the config
keys stay `transform.filter` / `transform.join` (internal `BuiltinNodeType` names are documented as
KEPT), but every user-visible string says **Step**. A **Pipeline**, never a *Flow*. <!-- vocab-allow: the banned term is named here only to forbid it --> `transform.filter`'s
`where` is a **row predicate** (the step catalog's own words) — ⛔ not a "rule", which is reserved for
Expectation / Alert Rule / Decision Rule.

**AST** has no glossary entry and appears in no UI copy. ⇒ ⛔ **it must not acquire one.** "AST" is an
implementation detail of how the predicate is read; the surface says *condition*, *predicate*, or plain
*"what gets kept"*, matching the catalog's own phrasing. Naming a pane after a parse tree is the same
mistake as "parser config" for a Grammar (`GLOSSARY.md:272`). The internal artifacts (`sqlAst`,
`astToConditionGroup`, `<inspecto-sql-ast-table>`) may use the word freely — no user-facing string may.
