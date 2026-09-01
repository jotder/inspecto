---
type: Concept
title: The editable round-trip (lift / lower)
description: How the pipeline editor's graph becomes the canonical *_pipeline.toon and back — PipelineEditable, refusal codes, use-ref homes, the parser family, route branches, settings, dry-run.
resource: inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java
tags: [concept, pipelines, lift, lower, round-trip, authoring]
timestamp: 2026-09-01T00:00:00Z
---

# The editable round-trip — the editor's graph ⇄ the canonical config

> **Scope (2026-09-01 consolidation):** this file owns the AUTHORING half of the backend Pipeline
> model — the `PipelineEditable` round-trip and every named refusal, the `use:` ref homes, the
> parser family's retype/lower rules, route-branch arming, the settings surface, and dry-run.
> The model itself (IR, lift, validator, token execution model) is
> [pipeline-graph-design.md](pipeline-graph-design.md); the editor UI is
> [pipeline-editor.md](../../frontend/features/pipeline-editor.md). Sections keep their
> historical §16–§20 numbers (they moved here whole from `pipeline-graph-design.md`).

## 16. The editable round-trip — the graph editor writes the canonical config (W5, 2026-08-01)

The read-only lift of §15 became a **round-trip**: the Pipelines editor no longer persists its own
`*_flow.toon`; it authors the canonical `*_pipeline.toon` the engine executes (plan U-A). The seam is a
dedicated pair, **not** `PipelineCompiler.toConfigMap` — that class is the Phase-1 *typed-record* parity
gate (it consumes the live `PipelineConfig` sub-records a `PipelineLift` carries). The editable pair
speaks the **config-file vocabulary end to end**, so nothing typed crosses the HTTP boundary:

- **[`PipelineEditable`](../../../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineEditable.java)**
  — `toMap(cfg, raw)` lifts topology via `PipelineLift` but swaps each node's config for the **verbatim
  raw-map section** it owns (from the decoded file); `lower(g, existing, strict)` writes those sections
  back over the existing file. **Ownership rule:** a present node owns its section wholesale (a cleared
  field ⇒ a deleted key); an absent node kind's section is removed in `strict` mode, preserved in
  lenient. Keys the graph does not model (`description`, `dirs.status_dir`, `partitions`, a single-schema
  `dirs.quarantine` that has no owning node) **travel through a save untouched**. `enrichment` nodes are
  ignored by the lower — their truth is the registered `*_enrich.toon` companion (never a mirror; the D7
  split-brain lesson).
- **Named refusals, not silent truncation.**
  [`PipelineCompileException`](../../../../inspecto-engine/src/main/java/com/gamma/pipeline/PipelineCompileException.java)
  carries stable codes — `UNSUPPORTED_NODE` (a node type the flat config has no home for),
  `UNSUPPORTED_BINDING` (a home for the *node*, but not for the `use:` component ref it carries — see
  below), `MULTI_SINK`
  (>1 distinct persistent `database` dir — the flat config expresses exactly one), and the strict
  completeness set `NO_ACQUISITION`/`NO_PARSER`/`NO_PERSISTENT_SINK`/`PARSER_NO_SCHEMA`. This closes the
  old `toConfigMap` behaviour of silently picking the first sink. `strict` = an `active` save or a
  brand-new file; an **inactive draft may be partial** (it overlays only what its present nodes own, so a
  half-built pipeline saves without erasing the rest — the same model Onboarding's stage saves use).
- **An AUTHORED `use:` ref has a home for two node kinds only** (`PipelineEditable.USE_HOME`, fixed 2026-08-14 —
  AUTHOR-1): acquisition's `connection/<id>` lands in the collector block, and the parser's
  `grammar/<id>` (authored Grammar) or `ingester/<fqcn>` (a plugin parser's synthesized binding) land in
  `parsing:`/`processing:`. **Every other node kind carries its settings inline**, so a ref on one is
  refused `UNSUPPORTED_BINDING`.

  ⚠ This matters because the editor's component picker is keyed on a node's **category, not its type**
  (`bindKindFor`), so it used to offer `transform/<id>` on *every* transform node — map, filter, join,
  dedup, summarize, route — and `sink/<id>` on every sink. Before the fix, `lower` read `use:` for the two
  homed kinds and **dropped every other ref in silence**: `PUT /pipelines/{name}/graph` returned
  `200 written:true` while `graph/raw` still showed the node with `{}`.

  **Follow-on (b) — the picker itself, closed 2026-08-15.** `bindKindFor` now answers `grammar` for
  `PARSE` and `null` for everything else, so the dead-end options are gone; `NEEDS_CONFIG` was split out
  so "must be configured" no longer derives from "binds a component" (a transform still needs config
  after losing its picker). The **category/type mismatch itself remains** — the two agree only because
  `PARSE` holds exactly one type, `parser`, and nothing structural forced that. It is now a tripwire
  rather than a latent bug: `bind-kinds.contract.json` is written from `PipelineEditable.typesWithUseHome()`
  by `BindKindHomeContractTest` (Java) and read by `pipeline-graph.contract.spec.ts` (TS), pinning the
  rule **a picker requires a home**. ⚠ Deliberately **one-way** — a home does not require a picker, since
  a `connection/` ref has a home but a Connection is not a `ComponentType` (no `GET /components/connection`),
  so the collector component owns that picker. ⚠ The artifact diff alone is **not** the whole guard: a
  falsification probe that gave `transform.map` a home left `bindableCategories` unchanged (the other
  transform types are still homeless) and was caught only by the explicit
  `neitherTransformNorSinkIsBindable` assertion — a category flips only when **every** type in it is homed.

  **Why refuse rather than preserve:** no code path in the engine resolves `transform/<id>` or
  `sink/<id>`, so writing the ref would produce a config that loads and then does nothing. That is the
  objection that removed the registry `schema` kind in unification W1, re-admitted only once
  `PipelineConfigParser.resolveSchemaRef` made such a ref executable. A named refusal tells the author
  at Save; an inert file tells them much later.

  ⛔ Two traps recorded here because the first diagnosis hit both. **`transform.map`'s absence from
  `STEP_KIND` was never the cause** — the five chain kinds *are* in `STEP_KIND` and their refs were
  dropped too, because `stepsOf` emits config only. And **"a map node is never author-configurable" is
  false**: `RowShaper.columnsOf` honours an authored `columns` list, `mappingSchemaOf` honours authored
  `rules`, and the flat file holds an authored mapping at `processing.mapping_file`. What a map node has
  no home for is the *ref*. Giving map a `steps:` entry remains wrong for a separate reason — a map node
  never enters the chain, so it would change **when `steps:` is emitted at all**.

  The offline mirror (`inspecto-ui/src/app/modules/admin/pipelines/pipeline-editable.ts`) refuses identically, in
  the same commit — a preview that accepts what the backend refuses is the same defect reversed.

  🔴 **A DERIVED ref is not an unhomed one** (`PipelineEditable.DERIVED_USE`, 2026-08-15). The 08-14
  refusal was applied to every lowerable kind, which swept up **enrichment** — whose
  `use: enrichment/<name>` the editor writes onto the node itself each time it saves the companion
  (`node-config.dialog.ts`, W4b) **and `PipelineGraphRoutes` synthesizes on every `GET /graph/raw`** — so an
  untouched open→save round trip was enough to hit it. Since the companion is the truth and lower has
  "nothing to lower" for
  the kind, that ref is *dropped on purpose*, exactly as `MAP_DERIVED`'s `schema`/`csv` are. For one day
  it was refused instead, which made **every pipeline holding an enrichment node unsaveable**. The rule:
  a binding the product itself writes can never be an authoring mistake — only the kind's own derived
  prefix is exempt, so `transform/x` on an enrichment node still refuses.

  🔴 **And the lower was the SECOND gate, not the first.** `saveGraph` runs `parseAndValidateFlow`
  (→ `PipelineValidator.checkWiring`) *before* `PipelineEditable.lower`, and `enrichment` is not a
  `ComponentRegistry` kind at all (a companion registers through `POST /enrichment`, not
  `registry/<dir>/`), so the round trip 422'd on `UNKNOWN_USE_KIND` — an **older** defect that the
  engine-level fix alone left standing, and that a unit test against `lower` could never have seen.
  `PipelineEditable.isDerivedBinding` is now the one place the rule is spelled and both gates consult
  it. ⛔ Whenever a save-path rule is added, check it against **every gate on that path**: a rule spelled
  in one of two sequential gates is a rule that never takes effect. Pinned over real HTTP by
  `ControlApiPipelineCrudTest.anEnrichmentNodesCompanionBindingSavesOverHttp` — the unit test passed
  while the route still refused.

  ✅ **The picker that caused it is gone** (2026-08-15). `bindKindFor` now answers `grammar` for PARSE
  and null for everything else, so no transform or sink is offered a component whose every option ends
  in a refused save; the free-text "Use (component ref)" box went with it (its placeholder advertised
  `transform/my_component` — the refused shape), leaving the control unrendered so a ref an existing
  file carries is refused **by name** rather than silently stripped in the dialog. ⚠ Note what the two
  halves of `bindKindFor` were: "which component does this bind" and "does this node need
  configuration". Only the first is about bindings — the second is now `NEEDS_CONFIG`, or nulling the
  kinds would have quietly turned every blank transform 'configured' and dropped its Validate error.
  Acquisition and parser — the only two homed kinds — never open this dialog at all (a drawer and the
  Grammar editor), which is why every ref it could write was refusable by construction.
- **Routes** ([`PipelineGraphRoutes`](../../../../inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java)):
  `GET /pipelines/{name}/graph/raw` (lift + a synthesized node per registered enrichment companion whose
  `triggers.on_pipeline` names this pipeline) and `PUT /pipelines/{name}/graph` (lower over the existing
  file, then the **same** `ConfigSpecs.pipeline()` + `ConfigSafetyValidator` gate + atomic write that
  `POST /config/write` runs — the editor is a *caller*, not a second write pipe). **Since 2026-09-01 the
  graph route also runs the three arming pre-checks** (`armedWithoutSchemaFindings` ·
  `routeArmingFindings` · `stepDisableFindings`) that `/config/write`/`/config/patch`/`/validate` gained
  2026-08-26 — before that, the editor's own Save answered `200 written:true` for a config that then
  failed to arm at the next `ConfigRegistry.rebuild` (one WARN log, silently skipped every cycle).
  Severity split unchanged: ERROR (422, nothing written) when `active: true`, WARNING on an inactive
  draft. Pinned over real HTTP by `ControlApiGraphSaveArmingTest`; ⚠ its fixture lesson: a node's
  `enabled` rides INSIDE `config` — a top-level node key is silently dropped by the codec. The `*_flow.toon`
  authoring writes (`POST /pipelines/authored`, `PUT`, `/nodes`, `/edges`) **retired**; grandfathered
  flows stay readable / runnable / deletable, never newly written (`CapabilityManifest` updated to match).

#### `processing.map` — the authored half of a map node (shipped 2026-08-15, AUTHOR-1 follow-on (a))

The ⛔ above ("a map node *is* author-configurable") described a hole that was still open: `lower` had no
branch for `transform.map` at all, so a projection typed into the map node's dialog was answered
`200 written: true` and dropped. The flat file now has a home for it, beside `processing.dedup`/`join`/
`summarize`:

```toon
processing:
  map:
    columns[2]{name,expr}:
      amount_major, "amount_minor / 100"
      event_day,    "CAST(event_time AS DATE)"
```

- **Authored vs derived is the whole design.** `columns`/`rules` are authored and lower verbatim;
  `schema` (the legacy schema map `PipelineLift` carries wholesale) and `csv` (moved within the node's
  reach by `PipelineDryRun`) are **derived** — never lowered, never refused. ⛔ A blanket "map has config
  ⇒ refuse" would have refused **every existing pipeline's save**, because every lifted map node carries
  a derived `schema`. Any key outside both sets refuses `UNSUPPORTED_MAP_KEY`.
- ⚠ **This block executes.** Unlike its three neighbours (authoring-only until a recipe-driven executor),
  `RowShaper` reads `columns`/`rules` on the graph executor `PipelineJobRunner` already runs in
  production — so a preserved `columns` changes what the *next* run projects. `PipelineConfig.prepare()`
  therefore does **not** refuse it on an active pipeline, which would break the case it exists to serve.
- **The allow-list is pinned, not documented.** `MapNodeKeyContractTest` asserts
  `PipelineEditable.MAP_AUTHORED ∪ MAP_DERIVED == RowShaper.MAP_NODE_CONFIG_KEYS`, *and* re-derives that
  constant by scanning the `node.cfg("…")` reads in RowShaper's map-path region — because a constant can
  be edited without editing the code it claims to describe. A key that becomes executable without joining
  the allow-list is silently dropped on save: exactly the defect this section closes.
- **Two more refusals, both for losses the file cannot express.** `MAPPING_CONFLICT` — authored `columns`
  next to a declared `processing.mapping_file`: `columnsOf` checks `columns` first and never consults the
  schema, so the authored list would silently outrank a reference the operator declared on purpose. (The
  alternative — making `mapping_file` authoritative — was rejected as a behaviour change to a live
  production path; it stays a separate, deliberate decision.) `MULTI_MAP_CONFIG` — a multi-schema graph
  whose map nodes have drifted apart; one `processing.map` serves them all.
- ⛔ **Still not a `steps:` kind, and deliberately not mutually exclusive with `steps:`.** A map node sits
  between parser and sink in *both* spellings, so a chain entry would change when `steps:` is emitted at
  all (rewriting files that round-trip verbatim today), and removing `map` alongside the singular
  transform keys during a `steps:` rewrite would delete an authored projection.
- **Validation is hand-rolled** in `ConfigSafetyValidator` — the third list-of-objects walker beside
  `processing.schemas` and `sinks`, for the same reason: `FieldType` has scalar `LIST` and untyped `MAP`
  only, no list-of-objects primitive, so no `FieldSpec` can express the shape. Honest cost: the author
  gets a 422 with a hand-written message and **no generated form control**. Building that primitive (and
  migrating all three onto it) stays its own `BACKLOG` item.

*Verified: 6 cases in `PipelineEditableTest` (incl. the rich fixture's verbatim round-trip now carrying a
`processing.map`), 3 in `MapNodeKeyContractTest`, 6 in the mock's `pipeline-editable.spec.ts`, and
`PipelineExecutorTest#anAuthoredProcessingMapProjectsThroughTheRealExecutor` — which runs config →
`PipelineLift` → `PipelineExecutor` over real DuckDB, because a config-format slice is not verified by a
`fromMap` test. Both new guards were falsified before being trusted (a bogus `node.cfg` read and a
disabled emit each turned them red).*

**Boundary (why a pipeline can be a grandfathered flow and not a canonical config):** the flat
`PipelineConfig` cannot represent a graph that uses non-lowerable node types — `transform.derive`,
`transform.route`, `sink.materialized`/`sink.view`, non-`gap` CONTROL, or a second persistent sink. Such
graphs stay `*_flow.toon` and refuse to lower with a named code. Making them *run* needs the branch-aware
executor (§13 R3), still future work — W5 makes the editor *author* the canonical subset, it does not
widen what executes.

*Verified: `PipelineEditableTest` (8 — verbatim round-trip + every refusal), the rewritten
`ControlApiPipelineCrudTest` (7 — the canonical round-trip, grandfathered reads, retired writes), full
`inspecto-engine,inspecto` reactor 618/0/0; UI gate 1945/0 + prod build; a live offline walk (create →
canonical `*_pipeline.toon` written with the full space-convention dir set + `registered:true` → lifted
to the graph). The UI's TS lift/lower (`inspecto-ui/.../modules/admin/pipelines/pipeline-editable.ts`) pins the same
refusals so the offline preview cannot pass a topology the server 422s.*

#### The chain has two spellings, and the FILE owns which one (fixed 2026-08-18)

A pipeline's transform chain is written **one of two ways, never both** — the legacy singular blocks
(`processing.dedup` / `join` / `summarize`, `csv_settings.where`, top-level `route:`) or an explicit
top-level `steps:` list — because `PipelineConfigParser` refuses the two spellings in one file (there is
no non-arbitrary position at which a singular block would join a sequence). Two rules follow, and both
were violated by shipped code until 2026-08-18:

- ⛔ **The spellings are NOT interchangeable, so normalising one into the other is not cosmetic.**
  `PipelineConfig.hasExplicitSteps()` decides whether `PipelineLift` walks the **authored order** or its
  own constant `filter → join → dedup → summarize → route`, and whether `prepare()` demands a top-level
  `output_store:` before it will arm. `lower` used to pick the spelling from the **graph shape alone**
  (`isLegacyShaped`), so a hand-authored `steps:` file whose chain happened to fit the singular keys was
  rewritten into them on save. It now keeps `steps:` whenever `existing` already carries a non-empty one:
  **the spelling is the file's, not the graph's** — the same ownership rule as every unmodelled key. A
  file with no `steps:` key is untouched, so every pre-existing file and every editor-authored graph
  still takes the byte-for-byte legacy path.
- 🔴 **A reader that knows only one spelling cannot tell "absent" from "empty".**
  `RecipeConverter.toRecipe` synthesised its transform steps **only** from the singular blocks — which a
  `steps:` file never carries — so it projected an **empty chain in silence**, and the round trip wrote
  the config back with no transforms at all. Worst for a chain the singular keys *cannot* hold
  (`dedup → summarize → dedup`): every step gone, nothing raised. It now projects the authored list in
  order, through one builder per kind **shared with the legacy path** so the two cannot drift, and an
  unmodelled kind travels **verbatim** so `RecipeCompiler` names it in an `UNSUPPORTED_STEP` refusal — a
  projection must never quietly shorten a chain.

*Verified: `RecipeConverterTest` — the every-repo-fixture round trip (which is what caught it, once a
shift authored the repo's first `steps:` fixture in `ae2c0909`) plus 3 explicit guards: authored order
preserved, round trip in the authored spelling, and an unmodelled kind refusing rather than vanishing.
Full `-Pedition-enterprise -fae` reactor 3458/0/0/5 at `f72f7fc8`.*

---

## 17. Pipeline-level settings — a dedicated surface for what the graph editor never models (2026-08-13)

> **Extended 2026-09-01:** the settings pair also carries the optional top-level `description`
> (display-only; nothing in the engine reads it) — its one post-creation write path. Trimmed on
> write; a blank clears the key from the file rather than persisting an empty string. Pinned by
> `ControlApiPipelineSettingsTest.descriptionRoundTripsAndBlankClearsIt`.

The `produces`/`reference` block (`produces: stream|reference`; `reference: {load, key, refresh_seconds}`)
is a **pipeline-level** property, not a node's — it names what the whole pipeline outputs (an ordinary
Stream, or a versioned Reference dataset other pipelines' enrichments can bind to by name), and
`PipelineEditable` deliberately never models it: §16's "keys the graph does not model … travel through a
save untouched" already covered it as opaque passthrough, but that meant it had **no write path at all**
outside hand-editing the `.toon` file, since there is no node to put a pipeline-level property on.

Rather than teach `PipelineEditable`/`PUT .../graph` about a non-node key (which would blur the "the flat
graph editor authors topology" boundary §16 draws), this got its own dedicated pair:
[`PipelineSettingsRoutes.pipelineSettings`/`savePipelineSettings`](../../../../inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java)
(`GET`/`POST /pipelines/{name}/settings`) read/write `produces`/`reference` straight off the config file,
independent of the graph route. `savePipelineSettings` mirrors `relabel`'s gate: the full `ConfigSpecs.pipeline()`
+ `ConfigSafetyValidator` check runs, but only findings the write **itself introduces** (not ones already
present in the on-disk file) block it — a config already on disk was never subjected to the write-time
safety policy, so re-punishing it here would make most real deployments' pipelines un-settable. The
pre-existing `ConfigSpecs.pipeline()` cross-field rule (`reference-upsert-requires-key`) already enforced
`load: upsert|scd2` needing a non-empty `key` — no backend validation gap needed closing.

The Angular side is a plain reactive-form dialog (`PipelineSettingsDialog`, opened from the pipeline
editor's ⋮ menu → "Settings…"), not `<inspecto-schema-form>` — `reference.key` is a `FieldType.LIST` and
`fieldSpecsToAttributes`'s `TYPE_MAP` still deliberately skips served `LIST` (unchanged here; its only
other driver remains `transform.route`'s `branches`).

*Verified: `ControlApiPipelineSettingsTest` (5, real HTTP round-trip via `V1Body.of` envelope unwrapping —
absent block reads as the parser's own `stream` default, a valid reference block persists and reads back,
`upsert` with no key is refused with the on-disk file untouched, clearing a saved block restores the
default) + `CapabilityManifestTest` drift guard; full `inspecto` reactor 746/0/0. UI: `PipelineSettingsDialog`
spec 6/6, production build clean, no regression in `pipeline-editor.component.spec.ts` (50/50).*

## 18. Dry-run grows reference context and an honest empty answer (2026-08-13)

Two §6 findings from driving the UI end-to-end, both closed in one change because they meet at the same
route and the same result record.

**DRYRUN-1 — a `transform.join` pipeline could not be dry-run at all.** `POST …/pipelines/authored/{id}/dry-run`
answered 422 `no ReferenceResolver supplied` for every join flow, which is most realistic ones: the walk
reached the join node and `RowShaper.ReferenceResolver.NONE` refused. No new machinery was needed — the
seam already existed (`PipelineExecutor.dryRun` has had a resolver overload since the join executor
shipped); `PipelineDryRun` simply called the arity that refuses. Now `PipelineDryRun.run` takes an optional
resolver (the two-arg entry point still passes `NONE`, so nothing starts resolving references by accident),
and `PipelineGraphRoutes.dryRunFlow` supplies one built on the shared `ReferenceReader` — the same resolution
the production join executor and the Stage-2 `EnrichmentEngine` use, so a versioned reference store's
current/as-of view cannot mean one thing in a preview and another in a real run. The view is created on the
throwaway dry-run connection and dies with it.

⚠ **The backlog row's own stated constraint was refuted.** It required that "the write-root/path-jail gate
has to be honoured on the dry-run route too". It is deliberately *not*: a `path:` reference names a **data**
file, which routinely lives outside the config write root, so jailing it there refuses legitimate
references — and it buys nothing, because `POST /enrichment/preview` already resolves the very same `path:`
references through the very same reader with **no jail and no write root at all**. A jail on this route
alone would be security theatre that breaks working configs. If arbitrary-path reads through a preview are
a concern, they are a concern about *both* surfaces and need one deliberate answer.

**DRYRUN-2 — a dry-run that reached nothing returned a silent empty 200**, indistinguishable from success.
`Result` gained a `warnings` list (the shape the sink preview already returns; a compact constructor keeps
the old 3-arg arity for `@PublicApi` compatibility), populated for two silences: the sample reached no node
past the seed, and no sink received a row.

⚠ **The second rule is about SINKS, not "every relation is empty" — and a test caught the difference.** The
first implementation warned when no relation anywhere carried rows; a filter that drops all three sample
rows produces `data`=0 **and `dropped`=3**, so the warning was both false and noise. That run *is*
informative. What the operator cannot see from row counts alone is that **nothing would be written**.

The warning is rendered by `pipeline-dry-run-panel.component.html` and mirrored in the mock handler —
DRYRUN-2's complaint was explicitly "indistinguishable from success **in the UI**", so a server-only field
would not have closed it. The panel spec asserts the rendered text, not the signal.

*Verified: `PipelineDryRunTest` 10/10 (+5: join-with-resolver, join-still-refuses-without, reached-no-node,
no-sink-row with a non-empty `dropped` branch, and no-warning-on-a-normal-run), `RowShaperTest` 16/16,
`PipelineExecutorTest` 5/5, `ControlApiPipelineCrudTest` 14/14 (+2 real-HTTP: a `path:` CSV reference
resolving through the route, and the warning reaching the response body); full backend reactor green. UI:
panel + mock-handler specs 34/34, production build clean.*

## 19. An armed route branch needs a predicate — and why `branches` still has no spec (2026-08-28)

Two conclusions from grounding the last driver of the map-list `AttributeSpec` type. One is a **build**,
one is a **refutation**; they are the same investigation because both turn on what a branch entry is.

### 19.1 What a branch entry actually is — three keys, two of them derived

A `route:` branch is `{key, where, database}`, and the three have different owners:

| key | owner | where it comes from |
|---|---|---|
| `key` | **derived** | the name of the node's `route:<key>` edge |
| `database` | **derived** | stamped by `PipelineEditable.routeSection` from the sink that edge feeds — the branch↔sink JOIN KEY on both halves of the round-trip |
| `where` | **authored** | the branch's SQL predicate; no other home |

### 19.2 BUILD — `RouteArming` now refuses a branch with no `where`

`RowShaper.route` requires `where` on **every** branch of **both** modes (`reqStr(b, "where", …)`, in the
CASE builder and the clone loop). `RouteArming.refusals` did not check it, so a branch missing the
predicate passed every arming rule, **registered, armed, and then threw mid-run** on the first row.

⚠ **Reachable from the product itself, not just hand-editing.** The editor's `addRouteBranch` writes the
entry as `{key}` and wires its sink; the predicate is typed afterwards through a separate
`setRouteBranchWhere`. "Wired but blank" is the normal intermediate state.

The gate is the usual severity split, so this does **not** break mid-authoring saves: `active: false`
→ WARNING ("refuses only once it is activated"), `active: true` → ERROR/422. ⛔ The `default:` branch is
**not** exempt — it is one of `branches[]` and gets a WHEN like any other; "everything else" is the
CASE's ELSE arm, generated from `default:`, which needs no entry of its own.

🔴 **Three separate fixtures encoded the same hole, and each called itself well-formed.**
`RouteArmingTest.branch(key, db)`, `RecordDedupRouteConfigTest`'s arming cases (these did carry `where`)
and `ControlApiRouteArmingTest.wellFormedRouteSavesClean` — the first and last asserted success for a
route that could never have executed. The control-plane one only surfaced in the FULL reactor, not in the
targeted `inspecto-etl` run that made the change green. **A fixture named "well-formed" is a claim, and it
was wrong in two of the three places it was stated.**

### 19.3 REFUTED — do not build a map-list `AttributeSpec` for `branches`

With sink `partitions[]` decided schema-owned (2026-08-13), `transform.route`'s `branches` was the type's
last remaining driver. It does not survive grounding, for two independent reasons:

1. **There is already an authoring surface.** The Recipe view's branch rows add/remove a branch, name it,
   and carry a `when …` input bound to `setRouteBranchWhere`. ⚠ The `NodeAttributes.TRANSFORM_ROUTE` help
   text said the branches are "edited on the canvas edges" — **wrong surface**, corrected here across all
   four mirrors (Java table, TS table, and both committed contract JSONs). That sentence is how a reader
   concludes no surface exists and sets out to build a second one.
2. **Speccing `branches` would destroy the derived pair.** A specced key is form-OWNED, and an owned leaf
   is deleted and replaced wholesale on save (the `ownedLeaves` rule that half-refuted the D7 partitions
   row). `key` and `database` are derived from the graph edge, so a form-owned `branches` re-writes them
   from whatever the form holds — which is precisely the branch-destruction bug `RouteBranch` was
   introduced to make structurally impossible.

⛔ The map-list `AttributeSpec` type now has **no driver at all**. Do not open it again without one.

*Verified: `mvn -o clean test -Pedition-enterprise` → 3692/0/0/5, exit 0, all 25 modules SUCCESS, none
skipped (+5 tests). Rule falsified in both directions — stubbing the check to `if (false)` turns all four
new tests red. UI: `npm run test:ci` 2769 passed/5 skipped exit 0, `lint:tokens` + `build` green, prettier
clean.*

## 20. The parser family — per-format node types (2026-08-15, `6bc685cf`; merged from the former `design.md`, 2026-09-01)

Parse is a **family**, the way sink already was: the generic `parser` plus one type per format —
`parser.delimited` (P3a), `parser.fixedwidth` (P3b), `parser.asn1` (P3c), the `parser.json` /
`parser.text_regex` pair, and `parser.plugin` (all P3d, 2026-08-16) — the family is now closed. Decided as
B6 — no generic parse node with format tabs, because each format owns its own grammar shape and
complexities. `isParserType` is `PARSER` ∪ `SUBTYPE_FRONTENDS.keySet()`, so a subtype joins the family by
declaring its spellings and nothing else.

* **The lift retypes only on an EXPLICIT `parsing.frontend`.** Delimited is also the parser's *implicit*
  default (`PipelineConfigParser` defaults the key), so retyping every bare legacy file would change the
  node type of everything already deployed on a mere read. A file that never says the word keeps the plain
  `parser` type until its author opts in. ⚠ This caveat is **delimited's alone** — every other frontend
  (fixed width, ASN.1, JSON, text/regex) is never implicit, so those configs already declare themselves and
  all of them retype.
* **A subtype answers to every spelling of its frontend, and the set is the source of truth.**
  `SUBTYPE_FRONTENDS` maps each subtype to its spellings and `subtypeForFrontend` inverts it; both the
  lift's retype and the lower's mismatch check go through it, so the comparison is by **subtype, not by
  string**. Fixed width has two (`fixedwidth`, `fixed_width` — `PipelineConfigParser#parseFixedWidth`
  reads both) and neither contradicts the other. ⛔ **A lifted node keeps the spelling its author wrote**:
  canonicalising `fixed_width` on a read would rewrite a deployed file on the next save, and the verbatim
  round-trip is the property `PipelineEditableTest` pins.
* **Lower stamps the CANONICAL frontend** (the first entry in the subtype's list) onto a palette-fresh
  node — the file must say the word its type means, or the next lift silently loses the identity. A
  lifted node already carries one, so the round-trip stays byte-verbatim.
* **A frontend the parser SYNTHESIZES a binding for makes that binding DERIVED** (P3c). `frontend: asn1`
  is sugar: `PipelineConfigParser#asn1PluginBlock` builds the `Asn1RecordIngester` wiring at load, so the
  lift reads a class back and presents `use: ingester/<fqcn>` on a node whose only *authored* home is
  `grammar/`. Refusing that ref would make every ASN.1 pipeline unsaveable — the AUTHOR-1(b) enrichment
  regression reached from the opposite direction — so `DERIVED_USE` maps `parser.asn1 → ingester/` and it
  is dropped in silence. ⚠ The rule generalises: **whenever a load-time synthesis invents something the
  lift can present, check what the save path will then think the author wrote.** ⛔ It does **not**
  generalise to the other built-in subtypes: nothing synthesizes an ingester for a plain built-in, so an
  `ingester/` ref on `parser.json` / `parser.text_regex` / `parser.delimited` / `parser.fixedwidth` is an
  authoring mistake and refuses with `UNSUPPORTED_BINDING`. **`parser.plugin` is the second exception,
  for a different reason**: its `USE_HOME` accepts `ingester/` too (like the plain `parser` type, since a
  plugin's ingester is an AUTHORED `parsing.plugin.ingester`, not synthesized) — but `PipelineLift.parserNode()`
  computes `use = "ingester/" + fqcn` unconditionally whenever `s.ingesterClass() != null`, with no check
  for *which* subtype the node will become, so the lift presents that derived ref on plugin-backed nodes
  too. `DERIVED_USE` therefore also maps `parser.plugin → ingester/`, for the identical reason as ASN.1.
  ⚠ **Found but out of scope**: the plain `parser` type accepts `ingester/` in `USE_HOME` with no matching
  `DERIVED_USE` entry, so a legacy `processing.ingester`-configured pipeline never retyped to
  `parser.plugin` would hit `UNKNOWN_USE_KIND` on validate today — a pre-existing gap, not fixed here.
* ⚠ **A record mode is not a node type.** Binary fixed width (`record: bytes`) lifts to
  `parser.fixedwidth` like any other — the type spans the format — but its field geometry lives in
  `processing.ingester_config` and is executed by `FixedWidthRecordIngester`, **not** by the
  `fixedwidth.fields[]` slices; `ComponentPreview` refuses to preview it and `DuckDbCsvIngester` excludes
  it from the native path. Operator decision 2026-08-16: it keeps the **dialog**, because the drawer
  pane's slice table would govern nothing. It needs no `ingester/` use-home either — binary reaches its
  ingester through the plain `processing.ingester` CLASS key, not a `use:` binding.
* Two refusals, both named: `PARSER_FRONTEND_MISMATCH` (a `parsing.frontend` contradicting the node's own
  type) and `MULTI_PARSER` (a second parser-family node — the flat file has one parse slot, and what used
  to be a silent last-one-wins became authorable once the palette offered two icons).
* Every subtype's `use:` home is `grammar/` **only**, not `ingester/`: a plugin-ingester binding on a node
  whose type already names its format is a contradiction, refused rather than half-honoured.
* ⚠ **`use: grammar/<id>` is read-supported but NEVER authored** (operator decision 2026-08-15). Every
  engine-side piece of it is deliberately unchanged — `resolveGrammarRef`, the `PipelineEditable`
  lift/lower translation, `UNKNOWN_USE_REF`, `PARSER_NO_SCHEMA`'s `grammarBound` branch, `USE_HOME`, and
  the `BindKindHomeContractTest` tripwire — because a hand-authored file may still carry one. What
  changed is upstream: a Grammar component is now a **Template** the UI copies from, so nothing writes a
  new binding, and opening a bound node in the editor migrates it to an inline copy. ⛔ Do not "tidy up"
  the binding read path as dead code — it is the compatibility half of a deliberate split. See
  [`plans-archive/grammar-templates-not-bindings-plan.md`](../../../archived-documents/plans-archive/grammar-templates-not-bindings-plan.md).
* Grouping by **category, not type string**: `PipelineCompiler.compile` and `PipelineDryRun` ask
  `PipelineNodeTypes.isCategory(t, PARSE)`, mirroring the sink family. A new parse subtype needs no edit
  there — but it *does* need its own `LOWERABLE` and `USE_HOME` entries in `PipelineEditable`.
* **No `NodeAttributes` spec is published** for these types on purpose. Their grammars nest two levels
  (`parsing.delimited.*`, `parsing.fixedwidth.*`) while the `key__nested` spec convention has only ever
  carried one, and the UI drawer owns the form shape — a best-guess table that looks authoritative is what
  that class's doc warns against. Consequently `node-attributes.contract.json` / `step-types.contract.json`
  stay byte-unchanged as each subtype lands.
* ⚠ `BindKindHomeContractTest` has fired correctly **twice** — at `parser.delimited` and again at
  `parser.fixedwidth`. Its tripwire asserts the exact PARSE type list plus the derivation the UI's
  category-keyed picker depends on. The category stays *bindable* only because each new type arrived
  **with** a `grammar/` home; one added without one must flip `bindKindFor('PARSE')` to null, and this
  test is where that shows up first.

UI side: [Grammar configuration](../../frontend/features/grammar-config.md).
