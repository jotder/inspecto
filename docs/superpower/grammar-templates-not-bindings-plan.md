# Grammar templates, not bindings — retire the live `use: grammar/<id>` reference

**Status: ACCEPTED (operator, 2026-08-15) — design recorded, not yet built.** Owner: UI (engine
unchanged). Created 2026-08-15, out of the definition-surface plan's P3a residual #2.

---

## 1. Problem & intent

A reusable **Grammar** is today a *live binding*: "Save as reusable Grammar" writes a `grammar`
registry component, **moves** the `parsing:` block off the node, and leaves the node carrying
`use: grammar/<id>`. Editing such a node writes the shared component — so one edit silently changes
every pipeline bound to it.

That linkage is the only reason two authoring surfaces still exist for one node type. The P3a Parse
drawer takes inline-grammar `parser.delimited` nodes; grammar-**bound** nodes stay on
`GrammarEditorDialog` purely because the drawer's Apply is an in-memory patch (D2) and cannot perform
the component write. It also puts the definition-surface plan in contradiction with itself: P3d says
the dialog is *"retired entirely"*, while P3a's as-built says bound nodes keep it permanently.

**Operator decision: drop the live binding. A Grammar component becomes a template — you copy from
it, you never bind to it.** The store survives; the reference does not.

**Target capability:** an operator can save the Grammar they just authored as a named template, and
start a new node from any saved template — with the resulting node owning an independent inline
copy that no later template edit can reach behind their back.

### Why this is cheap right now

Grounded 2026-08-15 before accepting: **nothing in the repo binds a Grammar.** The only component is
`spaces/demo/config/registry/grammars/pipe_delimited.toon`, a one-line `delimiter: "|"` fixture with
no referrer. The `grammar:` keys in `subscriber_pipeline.toon` / `voucher_pipeline.toon` are
`processing:` **file paths**, a different key that this plan does not touch. Combined with the standing
"nothing is in production after 3.x" position, **no migration is owed.**

### What this reverses

The grammar-config unification (archived `plans-archive/grammar-config-unification.md`, all 16 commits
pushed `ba8b87ce`) built extract-to-component deliberately, and `okf/frontend/features/grammar-config.md`
documents the bind as the store contract. This is a **considered reversal of a shipped decision**, at
GLOSSARY level — not a tweak. The §5 doc slice is therefore not optional bookkeeping.

---

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| **D1** | A **Grammar component is a template**: a stored, named starting point. Copying happens once, at author time; the copy is independent. ⛔ Never a binding target. | One concept, one word (`docs/GLOSSARY.md`). "Reusable" must not imply "live". |
| **D2** | Authoring **never produces `use: grammar/<id>`**. A node always owns its Grammar inline. | Removes the only node shape the drawer cannot serve. |
| **D3** | The **engine is unchanged** — `PipelineConfigParser.resolveGrammarRef`, the `PipelineEditable` lift/lower translation and the `UNKNOWN_USE_REF` refusal all stay. The form becomes *supported but never authored*. | Costs nothing, is already pinned by tests, and a hand-authored file may still use it. Refusing it would break such files for no gain. Reject: a breaking engine slice. |
| **D4** | A **legacy bound node opens in the drawer**, seeded from the resolved component, and **Apply materialises it inline** (dropping the `use:`). Editing migrates it. | Coherent with D1 and needs no new write route. The alternative — read-only — strands the node with no way forward. |
| **D5** | **"Save as template" is a HOST write, not a pane write.** The pane emits; the host calls `components.create('grammar', …)`. | P2 pure-pane rule (c): a *third entity* is the host's write; only a stage's own companion artifact is the pane's. |
| **D6** | **"Save as template" does NOT move the block.** The node keeps its inline `parsing:`; the component is a copy. | The whole point of D1. This is the single behavioural difference from today's `persist()`. |
| **D7** | The `grammar` component type, `registry/grammars/*.toon`, the Components page and its versions/restore history all **survive unchanged**. | Operator decision: same store, copy not bind. |
| **D8** | After this, `GrammarEditorDialog` serves **only the plain `parser` type**. P3d retires it when `parser` gets a pane — **P3d's "retires entirely" stands**, this plan removes its blocker. | Resolves the plan-vs-as-built contradiction in the stated direction. |

---

## 3. What changes, precisely

`GrammarEditorDialog` (`inspecto-ui/src/app/modules/admin/pipelines/grammar-editor.dialog.ts`) has two
exits today. `closeInline()` (:221) returns the node with its `parsing:` block and performs no HTTP —
**unchanged**. `persist()` (:226-249) is the one that goes:

```ts
const req$ = update
    ? this.components.update('grammar', name, block)          // :229  bound-edit — DELETED (D2)
    : this.components.create('grammar', { id: name, ...block }); // :230  extract — becomes "save as template"
// on success: delete config.parsing; close with use: `grammar/${name}`  // :236-238 — DELETED (D6)
```

The `components.update` call disappears with the bound case. The `components.create` call survives, but
loses the two lines after it: the block is no longer moved off the node and no `use:` is set.

Routing (`pipeline-editor.component.ts:1222`) loses its exclusion — every `parser.delimited` reaches
the drawer:

```ts
private isDrawerParse(node: AuthoredNode): boolean {
    return node.type === 'parser.delimited';   // was: && !node.use?.startsWith('grammar/')
}
```

**Not changed by this plan:** the engine (D3); the mock's `parser.delimited: ['grammar/']` binding
allowance, which mirrors the still-supported read path; `bindKindFor('PARSE') === 'grammar'` and the
`BindKindHomeContractTest` PARSE tripwire; `PARSER_NO_SCHEMA`'s `grammarBound` branch.

---

## 4. Slices

| Slice | Work | Verified by |
|---|---|---|
| **S1 — Save as template** ✅ **SHIPPED 2026-08-15** | Drawer gains a "Save as template" action; the pane emits, the **host** writes via `components.create('grammar', …)` (D5) and the node **keeps** its inline block (D6). Dialog's extract path follows the same new semantics. | A spec asserting the component is created **and** `node.config.parsing` survives with no `use:` set — the exact pair the old `:146` extract spec asserted the opposite of. ✅ |
| **S2 — Start from a template** ✅ **SHIPPED 2026-08-15** | A picker in the drawer listing `components.list('grammar')`; choosing one patches the editor with a **copy**. Read-only fetch, no binding. Must mark the pane dirty (it is an edit) and must not fire on the load-time path. | A spec: pick a template → editor value matches the component → Apply emits an inline block, `use` undefined. ✅ |
| **S3 — Bound nodes migrate on edit** | `isDrawerParse` drops the `use:` exclusion; the host resolves the bound component before opening and seeds the pane; Apply materialises inline and drops `use` (D4). Delete the dialog's bound-edit branch and its specs (`grammar-editor.dialog.spec.ts:116`, `:180`). | A spec on a legacy bound node: opens in the drawer with the resolved block, Apply yields inline + no `use`. |
| **S4 — Docs** | `docs/GLOSSARY.md` — Grammar component = **template**, ⛔ not a binding target. `okf/frontend/features/grammar-config.md` — rewrite "One store — inline by default, extractable to a component". `okf/backend/pipeline-graph/design.md` — note the `use: grammar/` form is read-supported, never authored. Amend the definition-surface plan's P3a + P3d rows. | `graphify update .`; the P3a/P3d contradiction is gone. |

Order is S1 → S2 → S3 → S4. S3 is last because it is the only one that needs a resolve-on-open step,
and S1/S2 are independently useful without it.

### S1 as-built (2026-08-15)

- **The one behavioural line** is `persist()`'s tail. It split into `saveAsTemplate()` (create → then
  `closeInline(block)`, so the node keeps its block and gains no `use:`) and `updateBoundComponent()`
  (update → close bound, **unchanged on purpose**), over a shared `write()` helper that kept the
  503/`apiErrorMessage` handling in one place. ⚠ The first attempt edited the shared tail and would have
  migrated **bound** nodes to inline as a side effect — S3's job, arriving two slices early and silently.
  Splitting the callers, rather than parameterising the tail, is what made that visible.
- **New `GrammarTemplateDialog`** modelled on `PipelineTemplateDialog` (same `uniqueNameValidator`,
  same normalise-before-validate ordering). Its alert states the copy semantics explicitly *because*
  this reverses the old behaviour — an operator carrying the previous expectation would edit a template
  and wait for a change that never arrives.
- **Existing template ids come from `validRefs`**, the set the editor already loads for `use:`
  validation — no extra HTTP call for the duplicate check.
- **Vocabulary checked, not assumed:** "template" already means "a reusable blueprint you instantiate"
  in `GLOSSARY` (Space Template, Rule Template, and `PipelineTemplateDialog`'s pipeline copy), so
  Grammar Template extends one concept rather than colliding with it. No new word was needed.
- The pane's `requestSaveAsTemplate()` deliberately does **not** mark itself pristine: a template write
  neither persists to the node nor consumes unapplied edits, so a dirty pane stays dirty. Pinned.
- **Verified live** in the offline preview on a palette-fresh `parser.delimited`: the action renders in
  the drawer, the dialog opens with the copy-semantics alert and the suggested id `delimited_grammar`,
  the mock store gained `component:grammar/delimited_grammar` with the correct nested content
  (`{frontend: 'delimited', delimited: {delimiter: ',', has_header: true}}`), and **the drawer stayed
  open** — under the old behaviour the surface closed and handed back a rebound node.
- Baselines at this slice: UI `npm run test:ci` **2472 passed / 5 skipped**, 329 files passed (330 total,
  1 file skipped — pre-existing); `lint:tokens`, `build` and `tsc -p tsconfig.spec.json` all clean.

### S2 as-built (2026-08-15) — and the silent-data-loss bug it uncovered

🔴 **Grounding the picker found a live defect, not just a gap.** A Grammar component can be **legacy
flat** (`{delimiter: '|', has_header: false}` — the shape the Components page still writes, and the
shape of `pipe_delimited.toon`, the only real component in the repo) or nested `parsing:`-shaped. The
editor seeds by flattening the block to `delimited__*` keys, so a flat component matches **no spec key**
and the property sheet falls back to its **declared defaults**. Measured with a throwaway probe before
any fix:

| Stored content | Editor produced |
|---|---|
| `{delimiter: '\|', has_header: false}` (flat) | `{delimited: {delimiter: ',', has_header: true}}` ← **stored values lost** |
| `{frontend, delimited: {delimiter: '\|', …}}` (nested) | correct |

This was **already live in the dialog's bound-node path** — selecting an existing flat Grammar showed,
and would have re-saved, `delimiter: ','`. It failed silently and looked like a successful load. Fixed
by a shared `grammarContentAsParsingBlock()` (`inspecto/grammar/grammar-block.ts`) that lifts legacy
top-level csv settings under `delimited:`; the dialog's local `grammarBlock` is now an alias of it, so
both surfaces are fixed at once. The new dialog regression test was **falsified** against the old code
(it fails with exactly `{delimiter: ',', has_header: true}`).

- **`isDelimitedGrammar()` filters the picker**, and deliberately does NOT reuse the editor's
  `normalizeFrontend` — that maps anything unrecognised to `delimited`, which would offer an
  `xlsx`/`html`/`asn1` component as a delimited starting point. A component qualifies only if it
  declares delimited explicitly or declares nothing (the legacy flat shape).
- **The pane stayed pure**: templates arrive as an `[templates]` input, fed by the `components.list`
  call the host already makes for `validRefs`. No service injected into the pane.
- ⚠ **The pristine-on-reseed trap fired here** (P2 rule (a), inverted): a picked template re-seeds
  `[initial]`, which marks the editor **pristine** — so `editor.isDirty()` reads false immediately
  after a real change and the drawer's Apply would stay disabled. Tracked with a pane-owned
  `templateDirty` flag instead of inferred from the editor, cleared on Apply and on a node swap.
- **Verified live** in the offline preview: the picker offered **3 of the store's 10** Grammars (the
  xml/asn1/xlsx/fixedwidth/json/html/parquet ones correctly withheld); picking the legacy flat
  `pipe_delimited` put **`|`** in the Delimiter field, not the `,` default; the unapplied badge lit on
  the pick and cleared on Apply, which was enabled throughout.
- Baselines: UI `npm run test:ci` **2478 passed / 5 skipped**, 329 files passed (330 total, 1 skipped —
  pre-existing); `lint:tokens`, `build`, `tsc -p tsconfig.spec.json` clean.

---

## 5. Known gap, deliberately not fixed here

The Components page's grammar form (`component-form.dialog.ts:212-223`) builds **DSV-only** content —
`delimiter`, `has_header`, `skip_header_lines`, `quote`, `escape`, `encoding`, all at top level (the
legacy flat shape). It cannot author `frontend`, a plugin/ingester, or segment structure. That gap
predates this plan, but it gets more visible once the Components page *is* the template library: a
template saved from the drawer carries the nested shape, and re-editing it on the Components page would
flatten it back. S2's normaliser makes flat content **readable** everywhere, so nothing is lost today —
but the registry form is still the wrong editor for a modern Grammar. **→ log to `docs/BACKLOG.md`; do
not fix in this plan.**

---

## 6. Open questions

None blocking S1. Two worth answering before S3:

1. Where does the host resolve a bound component from — `components.get('grammar', id)` on open, or a
   resolved block served alongside the graph? The former needs no backend change and is assumed.
2. Should materialising a legacy bound node warn ("this node no longer follows the template")? Assumed
   **no** for now — the node was never following it *live* in any deployed config, since none exist.
