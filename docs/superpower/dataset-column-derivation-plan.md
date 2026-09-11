# Dataset column derivation — design (`TYPEFLOW-DATASET-COLUMNS-1`)

> **Status:** design only, nothing built. Written 2026-09-11 as the split-out (b) of
> `TYPEFLOW-CONSUMERS-1`, whose (a) and (c) shipped the same day.
> **Decision owner:** operator. §6 is the list of calls this needs before code.

## 1. What the row asked for

> *"Dataset auto-registration, where the sink's derived schema becomes the Dataset's
> `columns{name,type,role}` instead of a hand-authored column list."*

## 2. Grounding — what is actually true today

Five findings, each of which changes the shape of the work. **Three contradict the assumption the row
was filed on.**

| # | Finding | Where |
|---|---|---|
| 1 | 🔴 **The server-side role heuristic ALREADY EXISTS** — it does not need writing | `ResultSetDescriptor.roleFor(name, type)` |
| 2 | 🔴 **It is mirrored byte-for-byte in TWO more places, with NO contract test** | `dataset-types.ts:93-97` **and** `result-set.ts:39-43` |
| 3 | 🔴 **`MaterializeTask`'s dataset refresh REPLACES the whole document** | `MaterializeTask:109-115` |
| 4 | ⚠ **`role: temporal` is fail-closed on "exactly one"**, and the heuristic cannot honour that | `DatasetRelation.temporalColumn:128-144` |
| 5 | ⚠ **`TypeFlow` yields DuckDB type NAMES; `roleFor` consumes coarse JDBC-derived types** | `TypeFlow.Column` vs `ResultSetDescriptor:27-34` |

### 2.1 The heuristic exists twice and is unpinned

**Three** copies are already identical — one Java, two TypeScript:

| input | `ResultSetDescriptor.roleFor` (Java) | `dataset-types.ts` `roleFor` | `result-set.ts` `roleFor` |
|---|---|---|---|
| coarse type `date` | `temporal` | `temporal` | `temporal` |
| coarse type `number`, name not `(^|_)id$` | `measure` | `measure` | `measure` |
| otherwise | `dimension` | `dimension` | `dimension` |

The id test is `Pattern.compile("(^|_)id$", CASE_INSENSITIVE)` in Java and `/(^|_)id$/i` in both TS
copies.

⚠ **This is an unpinned three-way drift set**, not a pair — the UI alone carries two copies of it. The
repo's answer to "N sides must agree" is a committed `*.contract.json` plus a test on each side; there
are eight such contracts, and `measure-grammar.contract.json` / `MeasureGrammarContractTest` is the
closest precedent. Column roles have no pin, so all three are free to drift and nothing fails.
⇒ **Pinning is a prerequisite of the work, not a nicety**: the moment the server writes roles into a
stored dataset, a drift becomes a silent data difference rather than a cosmetic one. ⛔ And a pin that
covers only the Java↔`dataset-types.ts` pair would leave `result-set.ts` free to drift — which is
exactly the "audit the guard's CALL-SITE list, not just its rules" failure this repo has hit before.

### 2.2 The refresh destroys authored columns — today, with no derivation involved

`MaterializeTask` builds a fresh map (`name`, `physicalRef`, `description`, `materialized`) and calls
`store.write("dataset", target, content)`, whose own comment calls it *"idempotent overwrite = the
refresh"*. `ComponentStore.write` replaces the document wholesale.

🔴 So a human who opens a materialized dataset in the Studio editor and sets roles, labels, formats or
`hidden` **loses all of it on the next materialize run** — silently, with the job reporting success.
That is an existing defect, independent of this row, and it is the reason the derivation must be a
**merge**, not a write. ⚠ Read from the code, not yet proven by a test; §6 asks for that test first.

### 2.3 "Exactly one temporal" cannot be satisfied by the heuristic

`DatasetRelation.temporalColumn` **throws** when two columns declare `role: temporal`. The heuristic
marks *every* date column temporal, so any dataset with `created_at` + `updated_at` derives two and
would throw.

⚠ It does not throw today because **`temporalColumn` has zero production callers** — only tests
(`DatasetRelationTest:152-184`). So this is a live landmine, not a live failure: auto-populating roles
arms it for whoever wires the first caller. ⛔ Do not treat "nothing throws today" as evidence the
derivation is safe.

### 2.4 The type vocabularies do not meet

`TypeFlow.Column.type` is a DuckDB type name (`VARCHAR`, `INTEGER`, `TIMESTAMP`, `DECIMAL(18,2)`).
`ResultSetDescriptor` derives its coarse `number|string|date|boolean` from **JDBC `Types` constants**,
which it gets from a live `ResultSetMetaData`. There is no DuckDB-type-name → coarse-type mapping in
the tree. **This is the one genuinely new piece of logic the work needs**, and it is small — but it is
a third place where a type vocabulary is interpreted, so it belongs beside one of the existing two, not
in a new helper of its own.

### 2.5 What consumes a dataset's columns

- **Server:** `DatasetRelation.temporalColumn` (`role`) — zero production callers.
- ⚠ `BiRoutes:123` and `DbBrowserRoutes:299` also emit a `role`, but from
  `ResultSetDescriptor.Column` — a **result-set** column described per query, not a stored
  `DatasetColumn`. Same word, different concept; do not conflate them when reading the code.
- **UI — `role` is genuinely consumed**, so this is not another unread primitive:
  `explore.component.ts:102-108` maps `ds.columns` into `VizField`s; `show-me.ts:19-23` buckets them by
  `role` to score chart recommendations; `explore-controls.component.ts:104` filters each channel's
  field picker by `control.acceptRoles.includes(f.role)` and `:121` special-cases `temporal` for axis
  behaviour. `queries.component.ts:299` passes stored `{name,type,role}` as **hints** so a query preview
  over a typed dataset keeps declared roles instead of re-guessing.
- ⚠ **`format` and `hidden` have ZERO readers.** `format` has editor UI and nothing consuming it;
  `hidden` is never even set. ⇒ deriving either buys nothing — the merge in §5 preserves them because
  they are a human's input, not because anything acts on them.

## 3. What the work actually is

Not "write a derivation". It is four things, in this order:

1. **Pin the role heuristic** as a contract (`column-role.contract.json`) with a test on each side,
   mirroring `measure-grammar`. Nothing else here is safe until this exists.
2. **Make the materialize refresh a MERGE**, preserving authored `columns`/`label`/`format`/`hidden`
   over a re-run. Fixes an existing silent data loss on its own; ship it separately and first.
3. **Add the DuckDB-type → coarse-type mapping**, beside the existing JDBC one.
4. **Derive and store the columns** at registration, reusing `roleFor`, with the temporal tie-break
   §6-Q2 settles.

⇒ **Steps 1 and 2 are worth doing even if step 4 is never built.** That is the main recommendation of
this design: the row's headline feature is the least valuable part of it.

## 4. Where derivation fires

| Option | Fires when | Assessment |
|---|---|---|
| **A — `MaterializeTask`** | a materialize job registers/refreshes its target | ✅ **Recommended.** The only place a dataset is created by code today, so it is the sole path where a column list is *missing* rather than *authored*. It already has the real output relation in hand, so it needs no TypeFlow at all — it can describe the Parquet it just wrote. |
| **B — pipeline sink save** | a pipeline declaring a sink is saved | ⛔ A pipeline's sink is not a Dataset; nothing registers one. This would invent a registration path, which is a bigger and separate decision. |
| **C — `GET /config/schema/derived`, on demand** | the Studio editor asks | ⚠ Possible, but it moves the feature into the UI and leaves code-registered datasets (A) still column-less. ⚠ That route is wired end-to-end — `ConfigService.derivedSchema()` → `DerivedSchemaPanelComponent:135` — but the component is **ORPHANED**: its selector appears in no template or route, so nothing mounts it. Wiring this option means first deciding where that panel belongs. |

🔴 **Option A does not need `TypeFlow`.** `MaterializeTask` has already written the Parquet when it
registers, so `DESCRIBE` over the real relation is both simpler and more truthful than deriving the
shape statically. ⇒ the row's framing — *"the sink's derived schema becomes the Dataset's columns"* —
imports a TypeFlow dependency the recommended option does not want. Worth saying plainly, because the
row names TypeFlow in its title.

## 5. Merge rule (option A)

✅ **This rule is not invented here — it already exists client-side and must simply be reused.**
`dataset-editor.component.ts:215-218` merges `bySaved.get(c.name) ?? freshlyInferred`: name-keyed,
saved-wins, with only genuinely new source columns getting a fresh role. Any server-side derivation
that does not replicate that pattern will clobber human edits on the next save.

For each derived column, by `name`:

- **absent from the stored doc** → insert with derived `{name, type, role}`.
- **present** → keep the stored `role`, `label`, `format`, `hidden` (a human's answer beats a
  heuristic); refresh `type` from the derivation, since the physical shape genuinely changed.
- **stored but no longer derived** → ⚠ §6-Q3. Dropping it discards a human's configuration for a
  column that may return; keeping it leaves a phantom column referencing nothing.

⛔ Never write a derived `role` over a stored one. The heuristic is a seed for a human, which is
exactly how the Studio editor already treats it.

## 6. Decisions this needs before code

- **Q1 — Is step 2 (merge-not-replace) its own row, shipped first?** Recommendation: **yes**. It is a
  real data-loss fix, it is small, and it is independently testable.
- **Q2 — Temporal tie-break.** When the heuristic yields several date columns, derive `temporal` for:
  (a) none — leave every date a `dimension` and let a human elect one; (b) the first by ordinal; or
  (c) all, and relax `temporalColumn` to pick deterministically instead of throwing.
  Recommendation: **(a)**. It never manufactures a wrong answer, and it cannot arm the throw in §2.3.
- **Q3 — A stored column the derivation no longer produces:** keep, drop, or mark `hidden`?
- **Q4 — Does the contract in step 1 pin only the heuristic, or the coarse type vocabulary too?**
  Recommendation: **both** — the roles are meaningless without agreement on what `number` and `date` are.

## 7. Outstanding grounding

✅ The UI consumer list is confirmed and folded into §2.5 / §5. Nothing outstanding blocks a decision.

⚠ One thing deliberately **not** established: whether any *stored* dataset in the sample configs
actually carries authored `role`s today. §2.2's data loss is real by code inspection, but its blast
radius in practice is unmeasured — §6-Q1's test should establish it rather than assume it.

## 8. Vocabulary

**Measure** and **Dimension** here are the canonical BI terms (`GLOSSARY.md`) and are the `role` values
themselves — not the banned *Metric*. A **Dataset** is the registered relation; a **Widget** is the
visualization instance. `DatasetColumn.role` (stored, authored) and `ResultSetDescriptor.Column.role`
(per-query, described) are **two different things that share a word** — §2.5. ⚠ That collision is worth
a `GLOSSARY.md` entry if this work proceeds.
