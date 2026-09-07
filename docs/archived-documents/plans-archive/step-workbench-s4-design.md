# Step workbench S4 — the one-surface design (DESIGN, awaiting operator review)

> **Status 2026-09-07: SHIPPED and ARCHIVED — all three slices.** The design was reviewed and the build
> ordered ("do workbench-s4"); both §5 questions are answered below with the design's own recommendations,
> §6–§8 record what was actually built, and S4b's picker half was REFUSED on grounding (§7). The durable
> facts are distilled into
> [`okf/frontend/features/pipeline-editor.md`](../../okf/frontend/features/pipeline-editor.md)
> §"The step workbench"; ⛔ this file is history, never maintained, never cited as current. Parent truth: [`catalog-vs-executors.md`](../../okf/backend/engine/catalog-vs-executors.md)
> §"The step workbench design, distilled"; the original ask is the archived
> [`step-workbench-design.md`](step-workbench-design.md).

## 1. What "one surface" means, grounded

The archived design asked for **one place where a Step is authored against its real input relation**. Of its five
slices, the plumbing shipped: S1 relation preview + `Result.sql` on the sandbox, S2 `<inspecto-step-preview-result>`,
S5 `GET /config/schema/derived` + `<inspecto-derived-schema-panel>` and `POST /components/transform/describe`
(`TypeFlow.describe`: body `{ sql, inputColumns:[{name,type}] }` → `{ columns:[{name,type}] }`, guarded by the same
`SqlGuard` as execution). S3 was refuted. **What S4 adds is the three authoring controls that today do not exist
anywhere in `inspecto-ui/src/app/modules/admin/pipelines/`:**

| Control | Today | S4 |
|---|---|---|
| Input-relation picker | implicit — the upstream node's output arrives as `upstreamColumns` (`pipeline-transform-sql-definition.component.ts`) | a visible, read-only statement of WHICH relation feeds the Step, and for merge/join Steps a picker among the node's inbound edges |
| Column filter | none — the SQL pane lists upstream columns unfiltered | a type-ahead filter over the field list, plus "used / unused in this SQL" grouping |
| Grouping beside the field list | none — `transform.summarize` `group_by`/`measures` validate server-side only | a group-by chip row beside the field list that writes `group_by` and offers the measure functions the catalog declares |

## 2. Design

**One host, three regions.** The existing right-dock definition drawer stays the host (S2 of the authoring
redesign: every kind defines there; Apply/Discard mean one thing). Inside a Step pane the workbench is a
`<inspecto-step-workbench>` component with:

1. **Input strip (top).** "Reads `<relation>`" — the upstream node id and its emitted relation (`DATA`,
   `DROPPED`, a route branch key). For single-input Steps read-only text; for `transform.merge`/`join` a select
   over the inbound edges. Columns come from the already-computed upstream column list; types from
   `upstreamColumnTypes`. **No new endpoint**: the relation is a graph fact the editor already holds
   (`pipeline-graph.ts` edges), not a server lookup — the "list a node's input relations" endpoint the grounding
   looked for is unnecessary because the canvas IS the source of truth for edges.
2. **Field list (left).** The upstream columns with type badges, a filter box, and two groups: *referenced by
   this Step* / *not referenced*. Reference detection is lexical over the pane's current SQL or field grid — the
   same identifier scan `SchemaMappingDrift` performs server-side, ported as a pure function with a spec.
   Clicking a field inserts its quoted identifier at the caret (SQL pane) or adds a row (structured grid).
3. **Shaping region (right).** The kind's existing editor, unchanged: the SQL editor for `transform.sql`, the
   structured grid for the Record Transformer, `where` for filter, and for `transform.summarize` a **group-by chip
   row + measures table** whose function choices come from the processor catalog (`ProcessorCatalog` /
   `step-types.contract.json`), never hard-coded in the UI.

**Below all three, the two shipped previews**, unchanged: `<inspecto-step-preview-result>` (rows on the sample)
and `<inspecto-derived-schema-panel>` (output schema by `describe`). They must agree — the doc's standing rule —
and the workbench re-runs `describe` on every change, debounced, exactly as the SQL pane does today.

**Chain semantics.** A preview over a chain runs each Step's `shape()` in order over the previous relation;
never fused (`RowShaper.fuse` was deleted 2026-09-06 for this reason).

## 3. What is NOT in S4

* No new server endpoint. `describe` + the graph edges cover input and output.
* No change to what a Step WRITES: the workbench edits the same config keys the panes write today
  (`sql`, `columns`, `where`, `group_by`, `measures`); the save round trip (`PipelineEditable.toMap` → codec →
  strict lower) is untouched and the existing round-trip specs stay the gate.
* No canvas change. The picker reads edges; it does not create them.

## 4. Build shape (when accepted)

| Slice | Touches | Gate |
|---|---|---|
| ✅ **S4a SHIPPED 2026-09-07** — field list + filter + referenced/unreferenced grouping | new `inspecto-step-workbench` component; `pipeline-transform-sql-definition` hosts it; a pure `referencedIdentifiers(sql, columns)` with spec | vitest specs; `npm run lint:tokens` (design-system guard) |
| ✅ **S4b SHIPPED 2026-09-07** (read-only — see §7) — input strip | reads `pipeline-graph.ts` inbound edges; merge/join select writes the existing input keys | editor spec: two inbound edges ⇒ select rendered; one ⇒ text |
| ✅ **S4c SHIPPED 2026-09-07** — summarize grouping | chip row + measures table over catalog functions; writes `group_by`/`measures` | round-trip spec against `RecipeConverterTest` fixtures; contract test that every function offered is in the catalog |

Order S4a → S4b → S4c; each ships alone. Estimated 3 commits; UI-only.

## 5. Questions — ANSWERED 2026-09-07

Both were taken as the design recommended. ⚠ Each is **reversible in one constant / one method** and is
pinned by a spec that would fail if it were changed silently — so a later operator call costs an edit, not a
redesign.

1. **Wide feed → capped, filtered.** `FIELD_LIST_CAP = 50` in `step-workbench-fields.ts`, with the filter box
   always visible. Consistent with the Parse pane's wide-feed decisions D8–D10. 🔴 The cap applies to what is
   **rendered**, never to what is **searched**: filtering runs over every column and the result is then capped,
   so a column at position 300 is one keystroke away. A cap that also narrowed the search would hide columns
   with no way to reach them — `step-workbench-fields.spec.ts` and the component spec both pin this.
2. **Grid click → add a `keep` row.** One click, reversible by the existing ×. Implemented as
   `PipelineTransformSqlDefinitionComponent.pickField`, which is where the branch lives because the workbench
   **writes nothing itself** — it emits a name and the host decides. In the SQL view the same click inserts the
   quoted identifier at the caret. ⚠ One case the question did not cover: the column is **already** an output
   field. Adding a duplicate row would be a surprise, so the grid **searches** for it instead — the row comes on
   screen, which on a 600-column feed is the actual ask.

## 6. As built — S4a (2026-09-07)

| Piece | File |
|---|---|
| Pure field logic — `referencedIdentifiers` / `workbenchFields` / `filterFields` / `FIELD_LIST_CAP` | `inspecto-ui/src/app/modules/admin/pipelines/step-workbench-fields.ts` (+ `.spec.ts`) |
| The component — filter box, two groups, type badges, `fieldPicked` output | `…/step-workbench.component.ts` (+ `.spec.ts`) |
| Host — side rail in both views; `pickField()` decides what a click means | `…/pipeline-transform-sql-definition.component.{ts,html}` |
| Caret-insert seam | `…/inspecto/data-table/sql/sql-codemirror.component.ts` → `insertAtCursor()` |

🔴 **The one trap worth remembering.** Reference detection cannot use ``: `_` is a word character, so
`amount` matches inside `total_amount` and the list would claim a column is used when nothing reads it.
The match requires a non-identifier character on each side — which is what a SQL identifier boundary actually
is. Both specs pin the `amount` / `total_amount` pair.

⚠ The detection is **lexical, not a parser** — deliberately. A SQL parser here would be a second implementation
of the dialect the engine owns, and it would rot. The failure mode is a field shown in the wrong **group**
(cosmetic), never a wrong config write; the workbench writes nothing.

🔴 `insertAtCursor` is **not** a write to the codemirror `value` input: it dispatches through the editor's own
update path so `onChange` fires and `valueChange` carries the new document. A caller that ignored `valueChange`
would silently lose the insert on the next external `value` push.

## 7. As built — S4b (2026-09-07): the strip is READ-ONLY, and the picker is REFUSED

| Piece | File |
|---|---|
| Pure logic — `inputRelations` / `inputSummary` | `…/pipelines/step-workbench-inputs.ts` (+ `.spec.ts`) |
| The strip | `…/step-workbench.component.ts` (one line above the filter box) |
| The model → drawer wire | `pipeline-editor.component.{ts,html}` → `definitionInputs()` |

The read half is exactly as designed: the inbound edges are a fact of the authored model, so the strip is
a projection and there is **no endpoint**, no fetch and no state to keep in sync.

🔴 **The write half is refused, on grounding.** §4 planned "for `transform.merge`/`join` a select over the
inbound edges [that] writes **the existing input keys**". There are no such keys:

* **`transform.merge` declares no attributes at all.** `NodeAttributes` has no `TRANSFORM_MERGE` constant
  and its type→attrs map registers none. Its inputs arrive as a positional `List<String>` that the executor
  builds from the graph edges (`RowShaper.merge(conn, node, inputs, outPrefix)`) — **the edges ARE the input
  binding.**
* **`transform.join`'s second input is a Reference component** (`reference` + `on`), not an inbound edge, so
  a select over edges would not be editing its inputs either.

A picker here would therefore have written a key nothing reads — the failure this repo has already made
often enough to name it. Edges stay authored where they are authored: on the canvas. ⚠ The design's gate
("two inbound edges ⇒ select rendered; one ⇒ text") is replaced by the honest one: the strip **states** both,
and names the relation whenever it is not a plain `DATA` edge, because a route branch is a different input.

⚠ Filed on the way past: **`MERGE-ATTRS-1`** (BACKLOG §4) — `RowShaper.merge` *does* read `type`
(`union`|`inner`|`left`) and `on` off the node config, and neither is declared anywhere, so a merge node can
only ever run as a `union` unless someone hand-edits TOON. That is the mirror of the usual defect: config
**read and never declared**, which no round-trip test can catch because the key never enters the round trip.

## 8. As built — S4c (2026-09-07)

| Piece | File |
|---|---|
| Pure logic — `parseMeasures` / `formatMeasures` / `measureRowError` / `needsField` | `…/pipelines/summarize-editor.ts` (+ `.spec.ts`) |
| The editor — group-by chips + measures table | `…/summarize-editor.component.ts` (+ `.spec.ts`) |
| Host | `pipeline-config-definition.component.ts` — `isSummarize()` · `formSpecs()` · the `buildNode` merge |

**Where it lives, and why not a pane of its own.** The drawer's generic config pane carries an explicit
decision — S2 — that every remaining kind shares ONE pane and that an arm per type is a second copy of the
routing rule, free to drift. So S4c is a **region inside that pane**, exactly like the enrichment editor and
the partitions editor already are, rather than a fourth `@else if` in `pipeline-editor.component.html`.
Apply, dirty, the extra-config rows and everything else stay where they were.

🔴 **The two traps this shape creates, both pinned by spec:**

1. **Two surfaces for one key.** The editor renders `group_by`/`measures`, so the schema form must STOP
   rendering them — `formSpecs()` filters them out. `specs()` itself is deliberately left whole, because
   `buildConfiguredNode` reads the spec list to decide where each key is placed.
2. **Apply would DELETE both.** `buildNode()` builds from the schema form's values; once the form no longer
   carries these two keys, they have to be merged back in from the editor. The pane spec asserts an
   untouched summarize node applies with both keys intact — that is where this bug would surface.

⛔ The aggregate list is `MEASURE_AGGS`, read from `measure-grammar.contract.json` and pinned to the engine's
`MeasureCompiler.AGGS` by `MeasureGrammarContractTest`. The component spec asserts the rendered `<option>`
list equals it, so a hard-coded list cannot creep back in.

🔴 **A measure this UI cannot parse round-trips verbatim.** A hand-written or newer-grammar value is kept in
`MeasureRow.raw`, rendered as a text row, and written back byte for byte — and it is *refused* by `validate()`
with the grammar's own message rather than applied silently. Opening a node and pressing Apply must never be
a way to lose what someone wrote.
