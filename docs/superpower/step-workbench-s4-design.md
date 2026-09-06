# Step workbench S4 — the one-surface design (DESIGN, awaiting operator review)

> **Status 2026-09-06: DESIGN ONLY, nothing built.** The operator decided "design it now, build later" for BACKLOG
> `WORKBENCH-S4`. This document is the design to review; when accepted it becomes a build plan, when refused it is
> archived. Parent truth: [`../okf/backend/engine/catalog-vs-executors.md`](../okf/backend/engine/catalog-vs-executors.md)
> §"The step workbench design, distilled"; the original ask is the archived
> [`step-workbench-design.md`](../archived-documents/plans-archive/step-workbench-design.md).

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
| S4a field list + filter + referenced/unreferenced grouping | new `inspecto-step-workbench` component; `pipeline-transform-sql-definition` hosts it; a pure `referencedIdentifiers(sql, columns)` with spec | vitest specs; `npm run lint:tokens` (design-system guard) |
| S4b input strip | reads `pipeline-graph.ts` inbound edges; merge/join select writes the existing input keys | editor spec: two inbound edges ⇒ select rendered; one ⇒ text |
| S4c summarize grouping | chip row + measures table over catalog functions; writes `group_by`/`measures` | round-trip spec against `RecipeConverterTest` fixtures; contract test that every function offered is in the catalog |

Order S4a → S4b → S4c; each ships alone. Estimated 3 commits; UI-only.

## 5. Open questions for the operator

1. Does the field list show **all** upstream columns for a wide feed (hundreds), or a capped, filtered view by
   default? (Recommend: capped at 50 with the filter box always visible; the Parse pane's wide-feed decisions
   D8–D10 point the same way.)
2. Should clicking a field in the *structured grid* mode add a `keep` row, or open the row editor? (Recommend:
   add `keep`; one click, reversible.)
