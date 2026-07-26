# Link Analysis V2 (d) — the authoring half: AI-assisted projection mapping

**Status:** ACTIVE — design pass complete 2026-07-26, build not started.
**Closes:** the BACKLOG §4 Link-analysis row's "(d) authoring half — needs a call on which L1 tool backs it."
**Reads with:** `docs/okf/frontend/features/link-analysis.md` · `docs/okf/frontend/features/inline-ai-authoring.md` ·
`docs/superpower/agt-6-plan.md` §3.4 (A5, the NL hop this shares a seam with).

## 1. What the authoring act actually is

A link-analysis entity-projection view is authored by choosing, over one Dataset's real columns, which
column is the **source** entity, which is the **target**, optionally which carries the **link kind**, and
which travel as node **attributes**. That is the `EntityProjection` record
(`inspecto-ui/src/app/inspecto/graph/graph-source.ts:19-32`):

| field | meaning |
|---|---|
| `datasetId` | the Dataset the mapping reads |
| `sourceCol` / `targetCol` | the two endpoint columns — required in practice |
| `linkKindCol?` | column whose value labels the edge |
| `attrCols?` | columns carried onto the node |
| `entityType?` | ⚠ **not cosmetic** — see §5 T3 |

It is carried as `GraphSourceQuery.projection` (single) or `projections[]` (multi, takes precedence),
`graph-source.ts:58-64`, inside a `link-analysis-view` component.

Authored today in **`LinkAnalysisQueryPanelComponent`** — an **inline bottom-panel tab**, not a dialog
(`link-analysis.component.html:376,403-411`). The primary mapping is flat controls on `queryForm`
(`link-analysis-query-panel.component.ts:56-73`); extras are a `FormArray` of the same group (`:83,91-96`);
every column picker is a `mat-select` over the `datasetColumns()` signal.

**So the act is column *selection*, not prose.** That single observation decides the tool.

## 2. The call — which L1 tool backs it

**Decision: a new non-mutating tool, `projection_author`, invoked through the existing
`POST /agent/tools/{name}` dispatch. NOT `component_draft`, NOT `query_author`.**

Why each alternative loses:

- **`component_draft` cannot draft.** It echoes the `config` it was given back with findings attached — it
  is a *validator*, not an author (`InspectoTools.java:736`). Worse, its `kind` resolves through
  `ConfigSpecs.forType()`, and **`ConfigSpecs.TYPES` has no `link-analysis-view`**
  (`inspecto-config/.../ConfigSpecs.java:31-52`), so `component_draft(kind='link-analysis-view')` returns
  `ok=false, "no structural spec for kind"` today. Using it would mean *first* writing a ConfigSpec for a
  free-form kind (like `pattern-pack`, it has no `validateKind` branch) — real work that buys validation,
  not authoring.
- **`query_author` is the wrong shape** despite surface similarity: it emits `{type:'sql',text,datasetId}`
  — a Query, not a mapping — and hard-errors without `-Dassist.write.root` (`InspectoTools.java:781,793-795`).
- **`suggest_expectations` / `kpi_report_builder` / `pipeline_author`** author other artifacts entirely.

`projection_author` shape (single-turn, deterministic-derive first, exactly the A1 precedent):

```
args   : { datasetId, columns: [{name, type?}], hint? }
result : { kind: 'link-analysis-view', clean, findings[], draft: { query: { projections: [ … ] } } }
```

**The pane supplies the columns.** This is the pivot of the whole design: there is **no agent tool and no
tool-layer route that returns a Dataset's columns**. The belt's only column access is
`BrowsableStore.browseTables()` (`InspectoTools.java:562,643`), which is the *operational-store* `table`
vocabulary — the same `target`-vs-`table` mismatch already documented for Expectations. The backend does
know, in exactly one place — `InvRoutes.schemaRelationships()` builds a `columnsByDataset` map
(`inspecto/src/main/java/com/gamma/control/InvRoutes.java:70-88`) — but it **emits only the inferred FK
`relationships[]` and throws the column lists away**, and it is a control-plane HTTP route, not reachable
from the tool layer (which is wired with `ComponentStore`/`BrowsableStore` suppliers).

Meanwhile the pane already holds the real list in `columnsForDataset()`
(`link-analysis-query-panel.component.ts:119-125`). Passing it as an arg is both cheapest and *more*
correct than a second server-side resolver — and it dodges the `assist.write.root` dependency that any
`ComponentStore`-backed column tool would inherit (§5 T7).

**Deliberately deferred: no ConfigSpec for `link-analysis-view`.** It would only add validation, and the
UI mapper is already the defensive boundary (the `pattern-pack` precedent). Revisit if a second producer
of views appears.

## 3. Build steps

1. **`projection_author` in `InspectoTools`** — non-mutating, deterministic derivation from the column
   list: score column-name pairs for endpoint-likeness (`*_id`/`from`/`to`/`src`/`dst`/`caller`/`callee`
   shapes), emit one mapping, carry the obvious remainder as `attrCols`, leave `entityType` **unset**.
   `hint?` narrows, it does not become a model prompt — the NL hop is A5's job (§3.4), and this tool must
   be its `derive` target, not a second path. → verify: a unit test over a telecom-shaped column list
   picks `caller`/`callee`, and an unmappable list yields `clean=false` with an anchored finding, never a
   guess.
2. **Register it in the closed frontend union** — `AiToolName` (`ai-assist/ai-draft.ts:8-13`) **and**
   `adaptToolResult`. Both, or the pane silently shows "no suggestion" (§5 T2). → verify: the spec
   asserts a non-empty draft list.
3. **Adopt `<inspecto-ai-assist>` in the query panel**, button beside Run. Provide `tool`, `[args]`
   (datasetId + `datasetColumns()`), `[current]` (the built query, as the diff baseline), `label`,
   `[disabled]` when no dataset is chosen. Gating, the 503 latch and the diff are all in the shared
   component (`ai-assist.component.ts:52-118`). → verify: adoption spec mirrors the Queries one
   (`queries.component.ts:279-305`).
4. **`(applyDraft)` patches the form and stops there** — `dirty`, never a save. Reuse the
   `patchFormFromView` path but **fix it to read `projections[]` as well as `projection`** (§5 T1).
   → verify: applying a two-mapping draft populates the `FormArray`, and nothing is persisted.
5. **Offline branch in `agent.handler.ts`** or the affordance dies in mock mode. → verify: the offline
   pane returns a draft over `SAMPLE_SOURCES` columns.

## 4. Out of scope

Natural language (A5 owns it — this tool becomes A5's derive target, it does not pre-empt it) · a
`link-analysis-view` ConfigSpec · authoring anything but the projection (roots, filters, layout) ·
`kpi_report_builder`'s host pane.

## 5. Traps — read before building

- **T1 — `patchFormFromView` ignores `projections[]`.** It reads only `view.query.projection`
  (`link-analysis-query-panel.component.ts:171`), so a multi-mapping draft (or saved view) loads blank /
  first-only. **This is a pre-existing load-path bug**, not something the draft introduces; step 4 must fix
  it or applying a good draft looks broken.
- **T2 — `AiToolName` is a closed union plus an `adaptToolResult` switch.** A backend tool missing from
  either yields `[]` ⇒ "no suggestion" with **no error**. This is the failure mode that makes a new tool
  look like a model problem.
- **T3 — `entityType` is not cosmetic.** Set on a *single* mapping it changes node ids from
  `entity:<v>` to `entity:<type>:<v>` (`entity-projection.ts:30-32`), breaking byte-identity with existing
  saved views and exports. But `buildQuery` **requires** it on every mapping once extras exist
  (`link-analysis-query-panel.component.ts:145-147`). ⇒ a drafter must leave it unset for one mapping and
  set it on all of them for two or more.
- **T4 — a view's Datasets are its projection *mappings*, never `query.roots`/`from`.** This is the exact
  premise that 422'd every shipped view in V2 (b). A drafter emitting `roots`/`from` produces an
  **unshareable** view that still passes hand-written tests.
- **T5 — `POST /agent/tools/{name}` enforces no `Role`/`Capability`**, only `ToolSpec.mutating()`. Inherit
  that exactly; do not add a half-gate on one tool (agt-6-plan §3.4.7).
- **T6 — offline column truth differs.** The mock fallback is `SAMPLE_SOURCES` keys, so an offline draft is
  over sample columns. Don't treat an offline draft as evidence the real column path works.
- **T7 — anything `ComponentStore`-backed inherits the write-root dependency** that makes `query_author`
  hard-error without `-Dassist.write.root`. Another reason the columns come from the pane.
