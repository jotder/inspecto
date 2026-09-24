# AI drafting on a non-`schema` component kind: design

**State:** IN FLIGHT (2026-09-25). All seven operator decisions are DECIDED (§7): the named kind is
`transform`. S1 (the `schema` slice, its own row) built 2026-09-25. Slices built for `transform`: S0 (record corrected + the `ComponentSpecs` spec home), S2+S3 (the
deterministic, preview-backed `component_draft`), S4 (UI re-adoption in `ComponentFormDialog`), S6 (the NL
instance on the same dialog). See §8.
**Board row:** `docs/BACKLOG.md` §3.1, *AI drafting on a non-`schema` kind* (P2, trigger fired 2026-09-15).
**Owner doc:** [`okf/frontend/features/inline-ai-authoring.md`](../okf/frontend/features/inline-ai-authoring.md).
**Grounded at:** `7bb36309c` (master). Every claim below cites the tree at that commit.

## 1. Problem

An author asked for AI drafting (`<inspecto-ai-assist>` with `component_draft`) on a component kind
other than `schema`. The Components pane dialog has three such kinds, `grammar` (a **Grammar Template**
in the glossary), `transform` and `sink`. `component_draft` cannot judge any of them because none has
a structural `ConfigSpec`. The board row offers two ways forward: give the three kinds a backend
`ConfigSpec`, or rework `SchemaEditorDialog`. It also says no low-risk slice survives.

Grounding changes that picture in three ways (§2.5). The short version:

- The reason the affordance was removed is **false**. The `schema` component kind was never retired.
  So the "rework `SchemaEditorDialog`" branch is a real, small slice. It just does not answer a
  non-`schema` ask.
- The three kinds are **not one problem**. `grammar` already has backend `FieldSpec`s, one set per
  Parser. `sink` has real checks, but they return warning strings rather than a spec. `transform`
  has no declared shape anywhere.
- A `ConfigSpec` that declares no required fields makes `component_draft` report almost any draft as
  `clean`. The repair loop then stops after one turn with an unreviewed draft. Giving a kind a spec
  is necessary, but it is not enough.

## 2. As-is seams

### 2.1 How `component_draft` judges a kind

| Seam | Where | What it does |
|---|---|---|
| Tool | `inspecto-intelligence/.../pack/InspectoTools.java:789-821` | Resolves `specFor(kind)`. When that is `null`, returns the error *"no structural spec for kind"*. Otherwise returns `ConfigLoader.validate(spec, draft)` plus `ConfigSafetyValidator.check(type, draft)` as anchored findings. |
| Spec resolver | `InspectoTools.java:1231-1260` | `configType` (maps `alert-rule` to `alert`), then `specFor`, which is `ConfigSpecs.forType(configType(kind))` and nothing more. The javadoc at `:1237-1257` records that the `schema` special case was removed in W1. |
| Schema projection | `InspectoTools.java:763-787` (`config_schema`), `:1225-1229` (`configSchemaJson`) | The same resolver, projected to JSON Schema. This is what the NL path constrains the model with (`ArgumentDeriver.java:168`). |
| Repair loop | `inspecto-intelligence/.../InspectoIntelligenceAgent.java:316-317, 335-340` | `REPAIRABLE = {component_draft→config, pipeline_author→flow}`, capped at 3 turns. It stops when a draft is `clean`. |
| Safety gate | `inspecto-config/.../safety/ConfigSafetyValidator.java:105-110` | Has branches only for `pipeline`, `enrichment` and `job`. Every other type falls to `default -> {}`, so the three kinds get **no** safety findings. |
| Spec registry | `inspecto-config/.../spec/ConfigSpecs.java:39-41` (`TYPES`), `:44-59` (`forType`) | Nine config types. `grammar`, `transform` and `sink` are absent from both. |

### 2.2 Why `ConfigSpecs.forType` is the wrong place to add a kind

`forType` is not just a lookup for the agent. It is the **admission gate for config TOON types** across
the control plane:

- `ConfigWriteRoutes.java:54` (`POST /config/write`)
- `SaveGate.java:72`
- `ConfigPreviewRoutes.java:36` (`GET /config/spec/{type}`) and `:167` (`/validate`)
- `ConfigReadRoutes.java:66, :225`
- `BootstrapRoutes.java:120` (every type in `TYPES`)
- `ComponentRoutes.java:795` (`refuseUnknownComponentKeys`)
- `ConfigJsonSchema.java:76`
- `SuggestConfigSkill.java:112`

If `grammar` were added there, `/config/write {type:"grammar"}` would become a **new write surface**
for a config type the engine never loads. It would also appear in bootstrap and in `/config/spec`.
⛔ **A component-kind spec must be resolved beside `forType`, never inside it.** The `schema` precedent
shows the hazard: one word naming two shapes cost two broken tools (owner doc, *"FIXED 2026-07-27"*).

### 2.3 The three kinds, one at a time

| | `grammar` (Grammar Template) | `transform` | `sink` |
|---|---|---|---|
| Stored shape | Two shapes: the legacy flat `{delimiter,…}` and the nested `parsing:` block `{frontend, delimited:{…}}`. The engine reads both (`inspecto-ui/.../grammar/grammar-block.ts:7-51`). | `{type: transform.<op>, …operator config}` (`component-form.dialog.ts:284-288`) | `{type: sink.*, store?, format?, partitions?}` (`component-form.dialog.ts:289-299`) |
| Backend shape source | ✅ **Exists**: `ParserPlugin.grammarSchema()` returns a `List<FieldSpec>` per Parser (`inspecto-engine/.../parse/ParserPlugin.java:56-61`). Built-ins are at `BuiltinParsers.java:37-…`, served by `GET /parsers` (`ParserRoutes.java:35`, `:54`). | ❌ **None**. There are 8 subtypes (`component-form.dialog.ts:68-77`), and their semantics live in the `RowShaper` executor. No `FieldSpec` for them exists anywhere. | ⚠ **Checks exist, no spec**: `ComponentPreview.sink` (`inspecto-engine/.../exec/ComponentPreview.java:690-…`) checks `store`, `format` against `ALLOWED_SINK_FORMATS` (`:738`) and partitions. It returns these as **warning strings**. |
| Required fields | None. Every delimited key has a default. | Only `type` | None at write time. A missing `store` is only a *warning* (`:696`). |
| Write gate | None. `ComponentRoutes.validateKind` (`:618-…`) has no branch for any of the three, and none is in `CENSUSED_COMPONENT_KINDS` (`:727`). | None | None |
| Behavioural check | `POST /components/grammar/preview` (inline, `ComponentRoutes.java:65, :521-524`). It returns a 422 message and needs a sample text. | `POST /components/transform/preview` (`:64, :512-519`). It needs sample rows. | `POST /components/sink/preview` (`:66, :526-533`). It needs sample rows. |
| Dialog form | Six delimited inputs only. It **refuses** a non-delimited Template (`component-form.dialog.ts:195-197`), and `buildContent` writes back only those six keys (`:267-282`). | A subtype select plus a free JSON textarea (`:284-288`) | Four fields (`:289-299`) |
| Deterministic draft source | `ParserPlugin.suggest(byte[])` (`ParserPlugin.java:86-93`). Only `XmlParserPlugin` implements it (`:109`), and **no route or caller** uses it. | None | None |

### 2.4 The UI host

- The Components pane routes `schema` to `SchemaEditorDialog`, `mapping` to `MappingEditorDialog`, and
  everything else to `ComponentFormDialog` (`inspecto-ui/.../components/components.component.ts:122-145`).
- `ComponentFormDialog` has a tombstone comment at `component-form.dialog.ts:242-247`. It says the
  affordance was removed "WITH" the `schema` kind and that it can come back once another kind has a
  `ConfigSpec`.
- `SchemaEditorDialog` authors `raw.fields[]` (`schema-editor.dialog.ts:70-83`). It already has a
  deterministic draft button, *Suggest from sample* (`:246-280`, `POST /config/suggest/schema`), which
  shows only when the opener passes `sampleRows`. It saves through the component CRUD (`:351-359`).
- Grammars are mostly authored **inline** on the parse Step, in `GrammarEditorDialog` (the shared
  `<inspecto-grammar-editor>`, `inspecto-ui/.../grammar/grammar-editor.component.ts:209-230`). A
  Grammar Template is a **copy source** (GLOSSARY, *Grammar Template*). A Template drafted in the
  Components pane therefore reaches no Step until someone copies from it.
- The surface's tool union already includes `component_draft` (`inspecto/ai-assist/ai-draft.ts:12-18`).
  `adaptToolResult` handles its envelope (`:133`), so no adapter work is needed.

### 2.5 Corrections to the record (the row's cause is a hypothesis)

1. 🔴 **"`schema` is no longer a registry component" is FALSE.** `schema` is in
   `ComponentStore.WRITABLE_TYPES` (`inspecto-engine/.../pipeline/ComponentStore.java:55-56`), and the
   pane still opens it (`components.component.ts:134-141`). `ComponentRoutes.validateKind` gates it
   with **`ConfigSpecs.forType("schema")`** (`ComponentRoutes.java:656-668`), which is the same spec
   `component_draft(kind="schema")` judges by. The code knows this. Two comments correct the claim
   (`ComponentRoutes.java:652-655`, `InspectoTools.java:1245-1255`). The claim survives only in the
   dialog tombstone (`component-form.dialog.ts:243-245`) and in the owner doc (*"RETIRED 2026-07-31"*).
2. ⚠ The owner doc cites `ConfigSpecs.schemaComponent()` and an `InspectoTools.specFor` that routes
   `schema` to it (*"As fixed:"* paragraph). **Neither exists.** A grep for `schemaComponent` finds only
   test names. W1 removed the method (`InspectoTools.java:1237-1244`).
3. ⚠ `ToolSchemaAdopterContractTest.java:49-50` still lists `components/component-form.dialog.ts` as a
   `component_draft` adopter. It has not been one since W1. It also says (`:45-46`) that no pane calls
   `kpi_report_builder`, but `dashboard-editor` does (owner doc, *SHIPPED 2026-07-28*). The test passes
   anyway, because it checks declared property types against a hand-kept table. It never reads the panes.

**Consequence:** the row's line *"No low-risk slice survives"* is **false for `schema`**. It is
**true for the three kinds the author asked about**, and for different reasons for each one (§2.3).

## 3. Options

### Option A: a component-kind spec registry beside `forType`

Add `ComponentSpecs.forKind(kind)` in `inspecto-engine`, because it needs `Parsers`. `InspectoTools.specFor`
falls back to it only when `ConfigSpecs.forType` returns `null`. `ConfigSpecs` and its eight callers
stay untouched (§2.2).

- **grammar:** derive the spec from `Parsers.get(frontend).grammarSchema()`. First lift a legacy flat
  body to the nested block, using the same rule the SPA's `grammarContentAsParsingBlock` uses. That
  rule must have a single Java home, not a second copy.
- **sink:** a hand-written spec with `type` (enum of the three `sink.*`), `store`, `format` (enum of
  `ALLOWED_SINK_FORMATS`) and `partitions`. A contract test pins it to `ComponentPreview.sink`.
- **transform:** one hand-written spec per subtype, 8 in all.

| | |
|---|---|
| Cost | grammar: S–M. sink: S. transform: **L**, and it is a second declaration of the `RowShaper` operator vocabulary. |
| Risk | 🔴 **A vacuous validator.** grammar and sink have no required fields, so `ConfigLoader.validate` returns `clean` for nearly any map. The repair loop then stops after turn 1 and hands the author a draft nothing actually checked. That is the "a stand-in more lenient than the server" failure class the owner doc records. ⚠ For transform, a hand-written spec drifts from `RowShaper` exactly the way `DERIVED_USE` and `MAP_AUTHORED` drifted, unless a contract test pins it. |
| Gain | Anchored `fieldPath` findings, which are what the loop and the surface's diff need. `config_schema` also starts constraining NL drafts for these kinds. |

### Option B: a validator backed by the production preview

For the three kinds, `component_draft` runs the **inline preview** (`ComponentPreview.grammar`,
`.transform` and `.sink`) over a sample the pane supplies, and turns the outcome into findings.
A preview error becomes an ERROR, and each sink warning becomes a WARNING.

| | |
|---|---|
| Cost | M. It is a new branch in one tool plus a `sample` argument. `ComponentFormDialog` already holds the sample for its Test panel (`sampleText`, `:131-132`). |
| Risk | Findings are **unanchored messages** with no `fieldPath`, so the loop repairs from prose. That is weaker than the substrate A5.2/A5.3 were built on. Without a sample there is no check at all. ⚠ Sink warnings are WARNING, so `clean` stays true and the loop never repairs them. |
| Gain | The judge is the **production path**, so the tool cannot be stricter or looser than execution. |

### Option C: re-adopt on `schema` in `SchemaEditorDialog` only

Place `<inspecto-ai-assist tool="component_draft" [args]="{kind:'schema'}">` in the dialog. Apply
patches the grid's rows, and the operator presses the existing Save. `validateKind` gates that Save with
the same spec (§2.5-1).

| | |
|---|---|
| Cost | S. It is UI only. The backend, the loop and the adapter are unchanged. |
| Risk | Low, with one known trap from the owner doc: a derived field `type` must come from the form's own vocabulary, or Apply silently leaves the Type cell blank. The grid column is free text (`schema-editor.dialog.ts:76-78`), so any value lands, but it may be refused on save. |
| Gain | It restores the capability and proves the re-adoption path end to end. ⛔ **It does not satisfy the author's ask.** Shipping it and closing the row would misreport what was asked. |

## 4. Recommendation

**Build a hybrid of A and B for each kind, and only for the kind the author named.** Treat C as an
independent small slice the operator may also choose.

1. **First find out which kind the author wants** (D1). Each of the three has a different cost and a
   different failure mode, so "all three" would be the largest build, driven by the least demand.
2. **grammar:** use A's derived spec for anchoring and B's preview for truth. The spec gives
   `fieldPath`s, and the preview over the Test panel's sample catches what the spec cannot. This is the
   only kind where A reuses a real backend source instead of inventing one.
3. **sink:** use A with a spec pinned to `ComponentPreview.sink` by a contract test. It has so few
   fields that the gain from AI drafting is small. Recommend building it only if it is the named kind.
4. **transform:** ⛔ **Defer A.** If this is the named kind, build B alone first. Revisit a per-subtype
   spec only if the `RowShaper` vocabulary gets a single declared home that both the executor and the
   spec read, so the spec is derived rather than retyped.
5. ⛔ **Never add a kind to `ConfigSpecs.TYPES` or `forType`** (§2.2).
6. ⛔ **The loop must not report `clean` from a vacuous check.** For a kind with no required fields,
   a draft is `clean` only when its preview ran and passed. Pin this with a negative test that uses a
   probe that would otherwise succeed.

## 5. Phased slices and test plan

Each slice is shippable alone. Every slice runs unit tests per change (`-pl <module> -Dtest=A,B`, commas).

| # | Slice | Depends on | Tests |
|---|---|---|---|
| **S0** | **Correct the record** (no code). Fix the owner doc (§2.5-1 and -2) and the `component-form.dialog.ts:242-247` tombstone. Fix `ToolSchemaAdopterContractTest` `ADOPTERS` (§2.5-3): drop the phantom `component_draft` row and correct the `kpi_report_builder` note. | none | `ToolSchemaAdopterContractTest` still green. Doc guards. |
| **S1** *(optional, D2)* ✅ **DONE 2026-09-25** (`AI-ASSIST-SCHEMA-DIALOG-1`; as-built in the owner doc, *Its host*. `ToolSchemaAdopterContractTest` `ADOPTERS` not yet retargeted — backend, out of that lane) | **Option C:** `SchemaEditorDialog` adopts `<inspecto-ai-assist>`. Apply replaces the grid rows with `raw.fields[]`, marks the grid dirty, and the operator saves. | S0 | Vitest: args are `{kind:'schema'}`. Apply patches the rows. A `clean:false` draft still applies but the save is refused 422 with cell findings. Gated on `canAuthorWorkbench`. A 503 latches `unavailable`. Add the adopter row to `ToolSchemaAdopterContractTest`. Drive it in the preview, because the blank-Type trap is caught only there. |
| **S2** | **Backend `ComponentSpecs.forKind`** for the named kind only. `InspectoTools.specFor` falls back to it only when `forType` returns `null`. | D1, D3 | `InspectoToolsTest`: `component_draft` and `config_schema` resolve the kind. Also assert `ConfigSpecs.TYPES` is **unchanged** and `/config/spec/<kind>` still returns **404**, as a negative pin on §2.2. For grammar, add a test that a legacy flat body and its nested twin produce the same findings. |
| **S3** | **Preview-backed findings** (B) for the named kind. Add an optional `sample` arg, and set `clean` only when the preview ran and passed (§4-6). | S2 (grammar and sink), none (transform) | Tool test: a draft that is spec-clean but fails preview is **not** `clean`. **Mutation-check it:** revert to spec-only and confirm the test goes red on the `clean` value, not for some other reason. Test that no sample gives a WARNING finding, not `clean:true`. `InspectoIntelligenceAgentTest`: the loop takes more than one turn when turn 1 only fails preview. |
| **S4** | **UI re-adoption in `ComponentFormDialog`** for the named kind. `[args]` are identity only (`kind`, plus `sample` from the Test panel). Apply maps the draft onto the kind's controls. For grammar, Apply merges into the stored `grammarBlock` so undrafted keys survive, and it refuses a non-delimited draft the same way the form does (`:195-197`). | S2, S3 | Vitest per kind: args sent, Apply lands in the form, dirty flag set, a non-delimited grammar draft refused. Update `ToolSchemaAdopterContractTest` `ADOPTERS`. Drive it live in the preview (a real backend, since there is no mock). |
| **S5** *(D4)* | **Inline host:** the same affordance on `GrammarEditorDialog` for the parse Step, where Grammars are actually authored (§2.4). | S2, S3 (grammar) | Vitest on the shared `<inspecto-grammar-editor>` host. It must not disturb the section-split rendering (`grammar-editor.component.ts:238-256`). |
| **S6** *(D5)* | **NL mode** (`/derive`) for the kind. Only this slice exercises the repair loop with a model. | S3 | `ArgumentDeriverTest`: `kind` is a pane identity field and the model cannot override it. `InspectoIntelligenceAgentTest`: returns the best-by-findings draft, and a vacuous-clean draft does not stop the loop. |

Full reactor (GAUNTLET) is not required per slice. It is required before a push that touches
`InspectoTools` together with `ComponentRoutes`, because that is a shared seam.

## 6. Out of scope

- A write gate for the three kinds in `ComponentRoutes.validateKind`. Today they save ungated
  (§2.3), and AI drafting does not widen that: Apply goes through the same `PUT` a human uses. Closing
  it is a separate row, and it would make the write stricter than it is today.
- Wiring `ParserPlugin.suggest(byte[])`, which has no caller (§2.3). It is a possible deterministic
  grammar draft source for later, not part of this design.
- Runtime validation of tool `args` against `jsonSchema`. It stays off on purpose (owner doc, *Why
  the schema is pinned by a TEST*).

## 7. Decisions (operator, DECIDED 2026-09-25)

| # | Question | Decision |
|---|---|---|
| **D1** | Which kind? | **`transform`.** Every slice below builds for it alone; `grammar` and `sink` stay unspecced. |
| **D2** | Ship S1 (Option C) as well? | **Yes, as a separate row** (`AI-ASSIST-SCHEMA-DIALOG-1`), built by another lane. This lane does not touch `SchemaEditorDialog`. |
| **D3** | Where does a component-kind spec live? | **`ComponentSpecs.forKind` in `inspecto-engine`** (beside `Parsers`), never `ConfigSpecs.forType`/`TYPES`. |
| **D4** | Grammar host | **N/A** — grammar was not chosen. |
| **D5** | Deterministic or NL? | **Both, deterministic first.** The validate-and-repair surface works with no model; S6 (NL) only if it fits after. |
| **D6** | `clean` semantics | **`clean` requires a passing preview.** With no sample the draft carries a WARNING, so it is never `clean`. |
| **D7** | transform | **Option B alone** — unanchored preview findings. The `RowShaper` operator vocabulary is NOT re-homed first. |

The original questions, kept for provenance:

1. **D1: Which kind did the author ask for?** Choose `grammar`, `transform` or `sink`. The row records
   only "a non-`schema` kind". Every later slice builds for that one kind. *Recommended:* ask the author,
   and build nothing past S0 until it is answered.
2. **D2: Ship S1 (Option C, `schema` in `SchemaEditorDialog`) as well?** It is cheap and low-risk, but
   it does not answer the ask. *Recommended:* yes, but as its own row, so that closing it does not
   close this one.
3. **D3: Where does a component-kind spec live?** Either `ComponentSpecs.forKind` in `inspecto-engine`,
   beside `Parsers` (recommended), or a `ConfigSpecs` method outside `forType`. Adding it to
   `forType` or `TYPES` is ruled out (§2.2).
4. **D4: Grammar host.** Choose the Components pane (a Grammar Template, which is a copy source), the
   inline `GrammarEditorDialog` (where Grammars are actually authored), or both. *Recommended:* inline
   first, if grammar is the kind.
5. **D5: Deterministic only, or NL too?** The deterministic surface validates a draft the author
   already typed. Only NL (S6) *drafts* from a sentence. *Recommended:* confirm with the author which
   one "AI drafting" meant, because S6 needs a configured model.
6. **D6: `clean` semantics for a kind with no required fields.** Recommended: `clean` requires a
   passing preview, and without a sample the draft carries a WARNING (§4-6). The alternative is to
   accept spec-only `clean`, knowing the loop will stop after one turn.
7. **D7: transform.** Accept Option B alone (unanchored preview findings), or require the `RowShaper`
   operator vocabulary to get a single declared home first, so that a per-subtype spec can be derived?
   *Recommended:* B alone, and only if transform is the named kind.

## 8. As built (2026-09-25, branch `lane-ai-transform`)

- **S0.** The record is corrected: the owner doc (§2.5-1, -2), the `component-form.dialog.ts` tombstone and
  `ToolSchemaAdopterContractTest.ADOPTERS` (the phantom `component_draft` row dropped, the
  `kpi_report_builder` adopter added). The spec home is
  `inspecto-engine/src/main/java/com/gamma/pipeline/exec/ComponentSpecs.java`: `KINDS = [transform]`,
  `forKind(kind)`, and `previewFindings(kind, draft, sampleRows)`. It sits in `pipeline.exec` beside
  `ComponentPreview` rather than in `com.gamma.pipeline`, so `pipeline` does not gain an edge onto `exec`.
  The `transform` spec is deliberately thin (`type` required, pattern `transform\..+`). Pinned by
  `ComponentSpecsTest`, including a negative pin that no kind in `KINDS` reaches `ConfigSpecs.forType`/`TYPES`.
- **S2 + S3 (deterministic surface).** `InspectoTools.specFor` falls back to `ComponentSpecs.forKind` only
  when `ConfigSpecs.forType` returns `null`, so `component_draft` and `config_schema` (and the repair loop's
  `configSchemaJson` constraint) all resolve `transform`. `component_draft` gained an optional
  `sampleRows` argument; for a component kind with no spec ERROR it appends
  `ComponentSpecs.previewFindings`, so `clean` means *the production preview ran and passed* (D6).
  **No route changed**, so none of the four ControlApi route gates applied. Tests:
  `InspectoToolsTest` (+6: preview-passing clean · spec-clean-but-preview-failing NOT clean, mutation-checked
  red on the `clean` value · no sample ⇒ WARNING, not clean · missing `type` ⇒ anchored spec ERROR, not
  previewed · `config_schema` projects it · `TYPES` unchanged and grammar/sink still refused),
  `ComponentDraftRepairLoopTest` (+2: a preview-only failure is repaired on turn 2; a no-sample draft never
  stops the loop as clean), `ControlApiConfigSpecTest` (`/config/spec/transform` is still **404**).
- **S4 (UI).** `ComponentFormDialog` renders `<inspecto-ai-assist tool="component_draft">` for the
  `transform` kind only. `[args]` = `{kind, config: <the draft as Save writes it>, sampleRows?}`; the Test
  panel's sample box now shows on create for a transform. Disabled with a reason while config or sample is
  not valid JSON. Apply → Operator picker + Config JSON, `markAsDirty()`, never saves; a non-`transform.*`
  draft is refused. The shared surface renders an unanchored finding as its message alone. Adopter row
  added to `ToolSchemaAdopterContractTest`. Vitest: `component-form.dialog.spec.ts` (+6),
  `ai-assist.component.spec.ts` (+1). ⚠ **Not driven live in the preview** (no backend with the
  intelligence module in this lane) — the owed live check is the design's S4 "drive it live".
- **S6 (NL).** A second, `prompting` `<inspecto-ai-assist>` on the same dialog with identity-only args
  `{kind:'transform', sampleRows?}` (never the config — pane args win). UI-only: the backend loop already
  resolved `transform` after S2. Pinned by a vitest (exact args, no `config` key) and a second
  `ToolSchemaAdopterContractTest` row; the pane-identity rule is `ArgumentDeriverTest.thePanesIdentityFieldsOutrankTheModels`.
- **Remains:** the live preview drive of S4/S6 against a real backend (and with a configured model for S6);
  the GAUNTLET before any push (this change touches `InspectoTools`, a shared seam, though not
  `ComponentRoutes`). S1 is the other lane's row. Then distil into the owner doc (done for as-built) and
  archive this plan.
