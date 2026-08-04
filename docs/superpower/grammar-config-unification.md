# Unify grammar configuration: onboarding Parsing stage ↔ pipeline `parse` node

> Sibling of the shipped `collector-config-unification` (archived 2026-08-04). That one found the two
> surfaces were *already* one feature. **This one is the opposite: they share almost nothing, and the
> gap between them contains two live defects.** Read §1 before scoping.

Status: PLANNED 2026-08-04 · not started.

## 0. Decisions (user, 2026-08-04)

1. **Store → "both": inline by default, extractable to a reusable Grammar component.**
2. **UI scope → the whole parser surface** becomes one shared component; hosts keep only their chrome.

Two constraints the grounding added, not negotiable:

3. **Vocabulary.** `docs/GLOSSARY.md:272` — **Grammar** is canonical; *"parser config" / "parse options"*
   are **banned** UI synonyms. The new component is **`<inspecto-grammar-editor>`**; `ParserConfigDialog`
   is renamed `GrammarEditorDialog`; the node's "Configure parser" label becomes "Edit Grammar".
   (*Parser* stays canonical for the engine that applies a Grammar — they are different concepts.)
4. **One config key must win** (§1.2). Unifying the UI without this leaves the split-brain behind a
   single component — strictly worse, because it would then look unified.

## 1. Grounding — what is actually true today

Surface | Onboarding `/catalog/onboard/<stream>/parsing` | Pipeline `parse` node
---|---|---
Component | `catalog/onboarding/parsing-pane.component.ts` | `pipelines/parser-config.dialog.ts`
Specs | `parsingAttributesFor(frontend)` — 4 locally-authored built-ins — **or** `fieldSpecsToAttributes(plugin.grammarSchema)` | always `fieldSpecsToAttributes(def.grammarSchema)`
Catalog on `GET /parsers` failure | built-ins still render (deliberate degrade) | dropdown goes **empty**
Preview | built-in → `POST /config/preview/parsing`; plugin → `POST /parsers/{id}/preview` | only `POST /parsers/{id}/preview`
Writes | `parsing:` block of `<stream>_pipeline.toon` via `POST /config/patch`, + one `_schema.toon` per segment | a **Grammar component** via `POST/PUT /components/grammar/{id}`; node config left empty, bound `use: grammar/<name>`

Genuinely shared already: `fieldSpecsToAttributes`, `ParsersService`, `<app-parser-tree>`,
`<inspecto-data-table>`, `<inspecto-schema-form>`. That is the renderer layer only — not the model.

### 1.1 DEFECT A — the `use: grammar/<name>` binding is dropped on save

`GrammarEditorDialog.persist()` closes with `{node: {...node, use: 'grammar/'+name}}`
(`parser-config.dialog.ts:331`) → `applyNodePatch` → `PUT /pipelines/{name}/graph`.

`PipelineEditable.lower()` translates exactly one `use:` prefix — `connection/`
(`PipelineEditable.java:248-249`). `PARSER_OWNED` (`:67-68`) is
`{csv_settings, schema_file, schemas, segments, ingester, ingester_config}` — **no `grammar`**.
So the binding is written to the graph model and **silently discarded** when lowered to the file:
the Grammar component is saved, the pipeline never references it. If the node carries none of the
`PARSER_OWNED` keys, the same save is instead refused `PARSER_NO_SCHEMA` (`:225`).

Nothing catches this because **no unknown-`use:` validation exists** — `PipelineValidator` never reads
`node.use()`; `ComponentRegistry.resolve/isKnown` (`ComponentRegistry.java:163-193`) have **no caller**.

*Static reading; reproduce with a failing test before fixing (§2.1).*

### 1.2 DEFECT B — two keys, and the loser is the one the editor writes

- Onboarding writes top-level **`parsing:`**.
- `lower()` writes **`processing.csv_settings`**.
- `PipelineConfigParser:227` — `csv = mergeParsing(csv, parsing)`, *"keys from `parsing:` overlay the
  legacy blocks"*. **`parsing:` wins.**

⇒ An edit made in the pipeline editor is invisibly masked by a stale `parsing:` block. **`parsing:` is
the design-of-record** (`docs/okf/backend/config/parsing-options-reference.md` §5), so it wins by
design: `lower()` must be taught to write `parsing:`, not `processing.csv_settings`.

### 1.3 Adjacent gap (in-scope because §2.2 touches the same method)

`resolveGrammar` (`PipelineConfigParser.java:956-973`) resolves `processing.grammar` with a bare
`Paths.get()` — **no config-dir-relative resolution and no path jail** — unlike its sibling
`resolveSchemaRef` (`:577-587`), which jails under `configDir`. Fix while wiring the component ref.

## 2. Slices

Ordered so **correctness lands before cosmetics**: after slice 3 the feature is correct with the old
duplicated UI, so slices 4–6 can be reverted without re-opening a defect.

### Slice 1 — pin the defect with a failing test (no behaviour change) — **DONE (uncommitted)**
- `PipelineEditableTest.grammarBindingSurvivesLowering`: a parser node carrying
  `use: grammar/pipe_delimited` lowers to a config that still references it.
  **CONFIRMED RED**, and the failure is the harsher of the two predicted halves:
  `PipelineCompile graph cannot be lowered: [PARSER_NO_SCHEMA(parse) → the parser names no
  schema_file / schemas / segments]` — a grammar-bound node isn't merely dropped, it can't be saved
  at all. (`mvn -o test -pl inspecto-engine -am -Dtest=PipelineEditableTest` → 10 run, 1 error.
  ⚠ `-am` is required; without it `-pl` resolves siblings from a stale local repo and test-compile
  fails on unrelated files.)
- Defect B needs **no new test** — `UnifiedParsingBlockTest.parsingKeysOverrideLegacyCsvSettings`
  (`inspecto-etl`, :61) already pins `parsing:` winning over `processing.csv_settings`.
- ⚠ The test is **red until slice 3**, so it was NOT committed with slice 2 (master stays green).
  Re-add it verbatim at the top of slice 3:

  ```java
  @Test
  void grammarBindingSurvivesLowering() {
      PipelineGraph g = new PipelineGraph("x", true, List.of(
              node("acq", "acquisition", Map.of("poll", "in")),
              new PipelineNode("parse", "parser", Map.of(), "grammar/pipe_delimited"),
              node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
      Map<String, Object> lowered = PipelineEditable.lower(g, new LinkedHashMap<>(), true);
      Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
      assertNotNull(parsing, "a grammar-bound parser lowers to a parsing: block");
      assertEquals("grammar/pipe_delimited", parsing.get("grammar"));
  }
  ```

### Slice 2 — the parser node owns the `parsing:` block — **SHIPPED**
**Design changed during implementation, for the better.** The plan said "map `parsing.*` ↔
`processing.*` key by key". Grounding killed that: `mergeParsing` folds `parsing.delimited` +
`frontend`/`encoding`/`compression` into ONE flat csv map while `parsing.plugin` is read separately
(`PipelineConfigParser:303-320`), and `schema_file`/`schemas` have **no `parsing:` equivalent at all**
— so a key-by-key map is lossy in both directions, and rewriting legacy files into `parsing:` would
break `editableRoundTripIsVerbatim`.

Shipped instead: **the top-level `parsing:` block is carried VERBATIM as a parser-node config key.**
Three touchpoints, no mapping code, nothing can be lost:
- `PipelineEditable.editableConfig` — `putIfPresent(c, "parsing", raw.get("parsing"))`.
- `PipelineEditable.lower` — `overlayOwned(out, "parsing", parser.cfg("parsing"), strict)`
  (strict-only removal, so a partial merge cannot drop a block it was never given).
- `PARSER_NO_SCHEMA` now accepts a `parsing:` block as naming the parse.
- Mock parity in all three places (`mock/pipeline-editable.ts`) + a new `pipeline-editable.spec.ts`
  (5 tests) — the mock had **no spec at all** before.

**The defect was worse than §1.2 stated.** `parsing:` survived `lower()` only as an *unmodeled* key
(via `deepCopy`), so the editor never displayed it: an onboarding-authored pipeline opened in the
graph editor showed the parser's real options **nowhere**, and any edit went to
`processing.csv_settings` — the key that loses the overlay. Pinned by
`parsingBlockIsCarriedOnTheParserNode` + `editedParsingBlockLowersBackIntoParsing`.

Branch: `fix:` on **master only** — `PipelineEditable` does not exist on `4.x` (W5 is master-only), so
master is the oldest affected line and there is no merge-forward set.
Verified: full reactor **BUILD SUCCESS** (23 modules, `inspecto-processor` 661 tests) with only the
slice-1 red test excluded; UI 2035 passed / 310 files; `lint:tokens` clean.

### Slice 3 — the Grammar binding resolves (closes Defect A) — **SHIPPED**
- `PipelineEditable.lower()` translates `use: grammar/<id>` → `parsing.grammar`; the **editable lift**
  (not `PipelineLift`) synthesizes it back onto `use:` and strips it from the node's `parsing` config,
  so a bound Grammar shows as a binding and never *also* as a corruptible free-text key. Never
  clobbers an existing `use:` — a plugin parser's `ingester/<fqcn>` is a different thing.
  `PARSER_NO_SCHEMA` accepts a grammar-bound parser. Unbinding in the editor clears the ref on disk.
- `PipelineConfigParser`: new `resolveGrammarRef` accepts a **registry ref** (`grammar/<id>` →
  `<configDir>/registry/grammars/<id>.toon`, the path `ComponentStore` writes) alongside a plain path,
  both through `resolveSchemaRef` — so a grammar and a schema ref in one file resolve alike.
  `parsing.grammar` (design-of-record) wins over legacy `processing.grammar`. Inline-wins precedence
  was already in `resolveGrammar` and is kept: it **is** "inline by default, extractable".
- ⚠ **Correction to §1.3:** this is a *consistency* fix, NOT a containment one. `resolveSchemaRef`'s
  own javadoc says it is "deliberately not a security boundary". Full containment stays BACKLOG §6.

**DEFERRED from this slice: the unknown-`use:`-prefix refusal.** Grounding refuted its safety.
`parseUseRef` (`component-model/refs.ts:21`) deliberately maps **`connections/`** (plural) to the
`connection` kind, and the mock **seeds** `use: 'connections/cdr_sftp_prod'` — while `lower()` emits
and reads `connection/` (singular). A blanket refusal would fail-closed on graphs the UI legitimately
produces today; allow-listing both spellings would bake the inconsistency into the engine contract.
Fix the vocabulary first (BACKLOG), then add the refusal. Until then a bad binding still fails
silently — but a *grammar* binding no longer can, which was the actual defect.

Verified: full reactor **BUILD SUCCESS**; the 9 new test methods confirmed present + passing in the
surefire XML (not silently filtered); UI 2039 passed / 310 files; `lint:tokens` clean.

### Slice 4 — extract `<inspecto-grammar-editor>` (the whole surface)
`inspecto/grammar/grammar-editor.component.ts`, standalone/OnPush/signals, **no write path** (hosts
persist — the rule that made `<inspecto-collector-config>` and `<inspecto-enrichment-editor>` safe).

Absorbs, per decision 2: type catalog (built-ins ∪ served, **with onboarding's degrade-on-error**,
not the dialog's empty dropdown) · schema-form property sheet · sample + sniff suggestion ·
Test/preview (**both** routes — `/config/preview/parsing` for built-ins, `/parsers/{id}/preview` for
plugins) · table/tree result renderer · the **fixed-width field-spec editor** · the **segments editor**
· the unserved-plugin honesty banner.

Hosts keep: onboarding's `<app-onboarding-sample-panel>` state coupling, stage-nav dirty registry and
`canAuthorWorkbench()` gate; the dialog's shell, fullscreen/maximize chrome, choose-or-create dropdown,
two-step name save and `ref.close` node binding.

API mirrors the collector precedent: inputs `specs`/`initial`/`sample`; output `submitted`; methods
`validate()/isDirty()/value()/markPristine()`. Spec incl. axe.
⚠ Reassigning `specs` rebuilds every control from its default — carry values across a type switch
(the exact bug found in collector slice 4); pin it with a regression test.
- Verify: UI `test:ci`, `lint:tokens`, build.

### Slice 5 — the Parsing stage adopts it
Pane becomes a thin host over `state.saveBlock({parsing})`. Verify: `test:ci` + preview smoke
(round-trip, segments still written as `_schema.toon`).

### Slice 6 — the node dialog adopts it; rename to Grammar
`GrammarEditorDialog` renders the shared component; keeps choose-or-create + the 2-step save, now
writing **inline node config by default** and only creating a Grammar component when the operator
names one ("Save as reusable Grammar"). Rename per decision 3, incl. the node label and
`pipeline-editor.component.ts:796`.
- Verify: GAUNTLET + preview smoke (inline edit → save → `parsing:` in the file; extract → save →
  `use: grammar/<id>` survives a reload).

### Close-out
Distill into `docs/okf/frontend/features/` (new `grammar-config.md`, cross-linked from `onboarding.md`
+ `pipelines.md`), archive this plan, update `docs/INDEX.md` + `docs/GLOSSARY.md` §13 touchpoints,
`graphify update .`.

## 3. Risks
- **Slice 4 is large** — it merges two mature UIs, one of which (onboarding) owns three bespoke
  sub-editors. If it grows past ~600 lines, split the fixed-width and segments editors into their own
  shared children rather than one god component.
- **Slice 2 changes what a graph save writes.** Legacy files are unaffected until touched, but a
  half-migrated space will have both keys present; §1.2 precedence makes that safe (`parsing:` wins,
  which is what the save just wrote) — assert it in the slice-2 test.
- Spotted in passing, **not in scope**: `pipeline-editor.component.ts:831` matches
  `use?.startsWith('connections/')` (plural) where `lower()` emits `connection/` (singular) — the
  run-to-here connection lookup likely never matches. Log to BACKLOG.
