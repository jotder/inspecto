# Onboarding ↔ Pipeline unification — one model, one guided head

> **Status: IN FLIGHT — direction approved by the operator 2026-07-31. W1 (a+b) SHIPPED same day; W0/W2–W5 open.**
> **Supersedes `onboarding-pipeline-split.md`** (approved 2026-07-30, archived to
> `archived-documents/plans-archive/`). That plan's two-plane model — Onboard declares a contract,
> Pipeline processes landed data, the Dataset is the handoff — is **reversed** by this one. §6 records
> exactly what reverses and what survives, because one slice (S1) already shipped and its behaviour
> stays.
> Standing UI mandate carried forward unchanged (operator, 2026-07-30): **reuse shared components even
> when they need small changes** — never duplicate similar functionality.

## 1. The ask (operator, 2026-07-31)

> "Let's keep Onboarding Stream and Pipelines same backend model and configuration. User should be
> able to create the same thing from both the pipeline creation and the Onboarding Stream frontend;
> the difference is Onboarding Stream will guide the first couple of nodes on the pipeline. We need to
> export all the related config files set the pipeline has for a specific stream. We will use the same
> UI component from graph property, unify as needed."

Named as actually broken (operator, same turn):

1. **Unify config keys.** For collector, glob is fine; both surfaces reference a `connection` from
   Settings, and connection credentials never travel.
2. **Export = the set of config files that imports into a different instance with the least changes
   and runs** — i.e. ship testing → production.
3. **Plugin parser behaviour must be the same in both places.**
4. **Schema must be unique, in a single store — no ambiguity, duplication, or conversion.**
   *(The operator flagged this as the important one.)*

## 2. Verified facts (probed in source 2026-07-31 — read this before designing anything)

**Two backend models exist, and Onboarding is already on the executable one.**

| Surface | Model | Written by | Executes? |
|---|---|---|---|
| Onboarding Stream | `*_pipeline.toon` → `PipelineConfig` | generic `POST /config/write` | **Yes** — this is what the engine runs |
| Pipelines list / graph view | the same `*_pipeline.toon`, *lifted* into `PipelineGraph` | nothing (read-only projection) | n/a |
| Pipelines visual **authoring** editor | `*_flow.toon` → `PipelineGraph` via `PipelineStore`/`PipelineCodec` | `/pipelines/authored/*` | **No** — see below |

- `PipelineGraph.java:14-18` — "A legacy `*_pipeline.toon` is auto-lifted into a `PipelineGraph`; an
  authored `*_flow.toon` parses into one directly." The lift already exists; only the **lower**
  (graph → canonical config) is missing.
- `PipelineStore.java:21-32` — persistence for authored flows via `PipelineCodec`, a codec entirely
  separate from `PipelineConfigParser`/`ConfigCodec`; its own comment describes wiring an authored flow
  into the live executor as a separate, unfinished concern.
- **"Stream" is not a backend entity.** `CatalogRoutes.java:29-58` builds `kind: "STREAM"` from the
  same collector list when `produces != "reference"`. There is no `OnboardingRoutes` and no
  stream-specific model — Onboarding is purely a frontend over the generic `/config/*` + `/runs`
  routes. **This half of the ask is already true.**

**The property renderer is already shared.** `NodeConfigDialog`
(`modules/admin/pipelines/node-config.dialog.ts:52`) delegates to `<inspecto-schema-form>`
(`inspecto/components/schema-form.component.ts:33`) over `AttributeSpec` — the same component
Onboarding's panes use. `PipelineInspectorComponent` is only the read-only drawer that launches it.
So this is **not** "build a shared component"; it is collapsing four duplicated `AttributeSpec` tables.
`node-attributes.ts:16-22` self-describes its shapes as "best-guess… not firmly specced server-side" —
so **Onboarding's engine-real keys are the source of truth for the merge**, not the Pipelines tables.

**The schema stores are not peers.**
- Store A — path-addressed `<base>/config/<pipeline>_schema.toon`, referenced by
  `processing.schema_file` / `processing.schemas[].schema_file` / `parsing.plugin.segments[].schema`.
- Store B — id-addressed `/components/schema/{id}` → `registry/schemas/{id}.toon` via `ComponentStore`
  (id sanitization, path jail, atomic write, `.history/` versioning).
- **All three schema branches in `PipelineConfigParser` (`:269-349`) do `Paths.get` → `Files.exists` →
  `Files.readString`. No code path anywhere resolves a component id into an executable schema.**
- Nothing points a pipeline at store B: `pipeline-graph.ts:26-33` `bindKindFor` offers
  `grammar`/`transform`/`sink` — **not `schema`**. Onboarding's schema pane writes `processing.schema_file`
  directly (`schema-mapping-pane.component.ts:413`).
- Store B's only schema consumers are the admin Components page and `POST /components/schema/{id}/test`
  (`ComponentRoutes.java:281-288`).
- Same document shape in both (`raw.fields[]`, `mapping.rules[]`, `partitions[]`), so this is an
  **addressing** difference, not a format one. **No converter exists** between them.
- On disk: **16** `*_schema.toon` vs **one** real `registry/schemas/*.toon`
  (`spaces/demo/.../payments_schema.toon`; the second apparent copy is the packaged
  `file-processor-deploy/` build artifact, byte-identical). Migration was therefore a single move.

**Three export pipes exist for "a pipeline", not two.**
- Backend `BundleExporter.exportDataSource`/`exportSpace` + `DataSourceBundleResolver.resolve`
  (`inspecto/service/`) — already walks a closure (schemas, grammars, connection by matched in-file
  `id`, jobs by `on_pipeline` name), zips config-relative TOON, strips secrets. **Built for exactly
  this promotion scenario.**
- Frontend `transfer/stream-bundle.ts` + `stream-transfer.service.ts` — `inspecto-stream-config` v1;
  pipeline body + main schema + per-segment schemas + `_enrich`; excludes `name`/`active`/`dirs`;
  emits a `StreamRequirement` for a connection.
- Frontend `transfer/bundle.ts` — `inspecto-metadata-bundle` v2, `BundleKind` incl.
  `'authored-pipeline'`, id-addressed with dependency closure.

**Portability inventory** (what breaks on a cross-instance move):
- `PipelineConfig.Dirs` (`PipelineConfig.java:47`) — all absolute, built from space root + space id
  (`PipelineConfigParser.java:103-122`). **Re-derivable** from the target's convention; `StreamBundle`
  already excludes them — keep that.
- `output.ducklake.data_path`, `input.database`, `output.database`, enrichment `transform_file` —
  **must be re-pointed** if absolute.
- `collector.connection` — a bare **name** (`PipelineConfig.java:257`), resolved via
  `ConnectionRegistry.find(id)` against the *target's own* store; credentials are `${ENV:…}`/`${SYS:…}`/
  `${FILE:…}`/`${KEYSTORE:…}` refs expanded only at connect time by `SecretResolver`. **This is the
  designed portability seam — it already works.**
- **Reference-by-PATH breaks on a move; reference-by-NAME survives.** By path: schema, grammar,
  segment schemas, enrichment `references.*.path`. By name: connection, jobs (`on_pipeline`),
  streams/reference datasets.
- **No `${VAR}` overlay exists** in general config loading — secret refs are scoped to connection
  profiles only. There is no environment-overlay mechanism for `dirs.*`.
- `SpaceMigrator` is **not** a promotion tool — same-machine layout migration, and its own javadoc
  (`:35-37`) warns it cannot rewrite paths inside configs.
- **Nothing validates referential integrity on import.** `ConfigSafetyValidator` checks only
  `pipeline` + `enrichment`, for path-jail/bounds — not existence of referenced artifacts. Earliest
  failure today: a missing schema/grammar fails at config **load**; a missing connection is not caught
  until **first poll**, via `IllegalArgumentException` from the connector factory (loud, but late).

## 3. ⚠ W0 — the gate that decides whether this plan is buildable

**The round-trip is potentially lossy in BOTH directions, and no one has proven otherwise.**

- `PipelineConfig` holds things the node/edge model has no home for: `dirs.*`, `processing.duplicate_check`,
  quarantine/markers/errors wiring, `csv_settings`, `status_dir`, dedup and file-lineage config.
  Lowering a graph to a canonical config must not drop them.
- Conversely the graph can express topologies a flat `PipelineConfig` cannot: branching, multiple
  sinks, fan-out/fan-in.

So **before any of W1–W5 builds**, W0 must produce one of two written answers:

1. a field-by-field coverage map proving the lower is lossless for the shapes we support, **or**
2. an explicitly named **supported subset** — which graph topologies round-trip, and what the editor
   refuses to author because the canonical config cannot carry it.

Answer (2) is the likely and acceptable outcome. What is *not* acceptable is discovering the gap after
the editor starts writing production configs. **Verify: a round-trip test — canonical config → lift →
lower → canonical config — asserting byte/semantic equality over a corpus of the existing 16 configs.**

### W0 finding 2026-07-31 (audited after U-D) — the lower drops the ENTIRE collector block

Measured directly against `PipelineCompiler.java:83-199`, and **independently confirmed** by reading the
`raw.put` sites rather than taking a summary on trust:

- `toConfigMap()` emits **only** `name`, `active`, `trigger`, `dirs`, `output`, `processing`. **There is no
  `collector` key constructed anywhere in the file.**
- From the acquisition node it reads **exactly three** keys: `trigger` (:93), `poll` (:97) and
  `file_pattern` (:118). It does **not** read `include`, `exclude`, `recursive_depth`, `connection`,
  `discovery`, `duplicate.*`, `stability.*`, `guarantee`, `post_action.*`, `incremental.watermark`,
  `gap_detection.*`, `fetch.*`, `retry.*` or `circuit_breaker.*`.

**The sharp consequence, and it implicates U-D's own work:** the shared `COLLECTOR_ATTRIBUTES` offers 11
keys — `connection`, `include`, `discovery`, `duplicate__mode`, `post_action__on_success`, `exclude`,
`recursive_depth`, `duplicate__on_change`, `guarantee`, `stability__window`, `post_action__archive_path` —
and the **intersection with the three keys the compiler reads is EMPTY**. So the Pipelines editor's
`acquisition` form now offers 11 keys the lower discards, and offers none of the 3 it consumes.

Note precisely *where* the defect is: those 11 keys are **engine-real** — `PipelineConfigParser:396-500`
reads every one of them from a `collector:` block. **Onboarding is unaffected**, because it writes a real
`*_pipeline.toon` that the parser reads directly (the W1b path). The gap is that **`PipelineCompiler` is
incomplete**, not that the UI keys are wrong. U-D made the vocabulary honest; it did not — and could not —
prove the keys survive lowering. Those are two different properties, and only the first was verified.

Other roles, same audit: **GAP nodes are grouped by `compile()` and then never read**; `alert`/`event` have
no branch in the classification loop at all; `transform.dedup.fingerprint` is collected into `dedups` and
never inspected; and **only one persistent sink plus one quarantine sink are read** — every additional sink
is ignored, which is exactly the multi-sink topology §3 warned a flat config cannot carry.

**So W0's answer is already trending to (2), a named supported subset** — but the collector block is too
central to leave out, so the realistic W0 scope is: teach `toConfigMap()` to emit `collector:` from the
acquisition node, then name the subset for what genuinely cannot round-trip (multi-sink, CONTROL nodes,
fingerprint dedup). ⚠ Extending the compiler will move the Phase-1 parity tests that currently *encode*
this narrow output as correct — expect to update them deliberately, not to treat their failure as a
regression. ⚠ Also revisit the U-D spec asserting `nodeAttributesFor('acquisition') === COLLECTOR_ATTRIBUTES`:
it is right that one table serves both features, but if the editor ever needs `poll`/`trigger`/`file_pattern`
(pipeline-level keys Onboarding authors elsewhere), that identity is the constraint to renegotiate.

## 4. Decisions pinned

- **U-A — canonical model is `*_pipeline.toon` / `PipelineConfig`.** It is what the engine executes;
  `*_flow.toon` is not wired to the executor. The graph editor becomes an editor **over** the canonical
  config: extend the existing lift into a round-trip. `*_flow.toon` retires as an authoring target
  (existing files grandfathered — read, never newly written).
  *Rationale: moving Onboarding onto the graph IR would move working, executing pipelines onto a model
  that does not run.*
- **U-B — Onboarding is a guided view over the head of the same graph.** Stages gain a typed
  `config` bag like `AuthoredNode`, so `<inspecto-schema-form>` renders them from one spec table. The
  guided rail, sample-as-thread, and resumability are **kept** — they are the guidance, not a second
  model. Bespoke panes survive only where `FieldSpec` genuinely cannot express the shape.
- **U-C — one schema store: path-addressed config TOON (store A).** Decided on engine-executability,
  not preference. `"schema"` retires from `ComponentStore.WRITABLE_TYPES`; the 2 existing components
  migrate; `POST /components/schema/{id}/test` repoints at config-addressed schemas.
  **Schema references become space-relative by convention**, which buys portability (§2) *and*
  path-jailing in one change — see the shared prerequisite below.
- **U-D — one `AttributeSpec` table per concern; Onboarding's keys win** (engine-real vs
  self-described best-guess). Collector carries **both** glob keys and `connection`-by-reference.
- **U-E — plugin parser parity.** Onboarding's read-only guard on a `plugin` frontend
  (`parsing-attributes.ts:4-14`, `parsing-pane.component.ts:29`) lifts. That guard was an honesty guard
  against silent no-ops; it is now obsolete because the segments editor and ASN.1 ingest shipped.
- **U-F — one export pipe, promotion-grade.** The backend `BundleExporter`/`DataSourceBundleResolver`
  closure walk is the single pipe; the frontend formats become callers, not parallel implementations.
- **U-G — no identity churn.** Carried forward from the superseded plan's D-C, which remains correct on
  this point: `pipelineName`, `*_pipeline.toon`, `BatchEvent.pipeline()` all stay. Only the *user-facing*
  vocabulary changes, and it changes toward "one thing", not toward a new name.

**Shared prerequisite (U-C + BACKLOG §6 hardening).** Space-relative schema resolution and the deferred
unjailed-config-paths pass need the *same* change: a space root threaded into
`PipelineConfigParser.parse(Map, String)` (`:52`), which today receives no root and whose `PipelineConfig`
carries no root field. **Do that threading once and both land.** See `BACKLOG.md` §6 for the full
~80-site inventory and the reusable `SafetyPolicy.underAnyRoot` primitive.

## 5. Workstreams (build order; W1–W3 independently shippable and close the operator's named issues)

- **W0 — the round-trip gate (§3).** Blocks W4/W5 only; W1–W3 may proceed in parallel.
  *Verify: lift→lower round-trip test over the existing 16 configs, plus a written supported-subset
  statement if lossless proves impossible.*
- **W1 — single schema store (U-C). ✅ W1a SHIPPED 2026-07-31 — W1b (space-relative paths) still open.**
  *The operator's stated priority.*
  **W1a, done:** `schema` removed from `ComponentStore.WRITABLE_TYPES`; `POST /components/schema/{id}/test`
  and its handler deleted; `schema` dropped from `BundleRoutes.APPLY_ORDER`; `ConfigSpecs.schemaComponent()`
  deleted and `InspectoTools.specFor` de-special-cased (the word now has ONE meaning, so `forType` is again
  the whole answer); frontend `ComponentType`/`COMPONENT_TYPES`/`testSchema()`, `SCHEMA_KIND`,
  `ATOMIC_KINDS`, `PIPELINE_KIND.allowedPartKinds`, `REGISTRY_KINDS`, `PIPELINE_REF_KINDS`, mock integrity
  + 5 seeds all cleaned. The single on-disk component migrated
  (`spaces/demo/config/registry/schemas/payments_schema.toon` → `spaces/demo/config/payments_schema.toon`;
  its content shape already matched store A, so it was a move, not a transform). Guards added at both
  layers: `ComponentStoreTest.schemaIsNotAWritableComponentKind` and
  `ControlApiComponentsTest.schemaIsNotAComponentKind`.
  ⚠ **Old bundles must still parse**: `'schema'` is KEPT in the `BundleKind` type and in a new
  `LEGACY_BUNDLE_KINDS`, so an already-exported bundle loads and the operator gets an honest per-item
  refusal; dropping it from the type instead would make `parseBundle` reject the WHOLE file with
  `unknown kind "schema"` over one obsolete item. It is **not** in `BUNDLE_KINDS` — never offered for
  export again — and `KIND_ORDER` includes legacy kinds because a missing index made the sort comparator
  compute `NaN`. `BundleTransferService.write` refuses a legacy kind with a named reason instead of
  attempting a write the server would answer with an opaque 400.
  ⚠ **Deliberate feature loss, recorded not hidden:** AI drafting in `component-form.dialog` was offered
  ONLY for `schema` (the sole dialog kind with a structural `ConfigSpec`), so it was removed WITH the kind
  rather than left answering *"no structural spec for kind"*. The backend repair loop is untouched and
  generic — see BACKLOG for the follow-on.
  *Verified: full `mvn -o clean test` reactor green; UI 1890 tests + `lint:tokens` + production build green.*
  **W1b, SHIPPED 2026-07-31 — and it did NOT need the space-root threading.** The plan assumed threading a
  `SpaceRoot` through `PipelineConfig.load`'s 8 call sites (several of which are CLI entry points with no
  space concept, and `SpaceRoot` lives in module `inspecto`, *above* `inspecto-etl` — so the parser could not
  even see it). Unnecessary: **a config being loaded always has a directory**, so
  `PipelineConfig.load(configPath)` passes `Paths.get(configPath).getParent()` and no caller changed at all.
  Resolution order in `PipelineConfigParser.resolveSchemaRef` is **config-relative first,
  working-directory second**, applied at all three reference sites (`processing.schema_file`,
  `processing.schemas[].schema_file`, `parsing.plugin.segments`). A bare `x_schema.toon` beside its pipeline
  is portable — the whole space tree relocates/renames/imports and still resolves — and every existing
  working-directory-relative config keeps loading byte-identically because its form is still tried, so
  **nothing on disk needed migrating**.
  ⚠ **The gate had to move with the reader.** `ConfigRoutes.schemaFileFindings` is an **ERROR**-severity
  gate at registration (`RunRoutes`), so leaving it resolving working-directory-only would have made it
  reject a portable config the engine runs happily — blocking the very promotion path W1b exists to enable.
  It now takes a nullable `configDir` and mirrors `resolveSchemaRef` exactly; `RunRoutes` passes
  `resolved.getParent()`. The two WARNING sites (validate / pre-write) still pass `null` because a draft has
  no home yet — see W3 below.
  ⚠ **Jailing is partial and must not be oversold:** the config-relative branch is contained (a `../`
  escape is skipped, not resolved), but the legacy working-directory branch remains unjailed. That is
  deliberate — full containment is the systemic pass in `BACKLOG.md` §6, and `resolveSchemaRef` carries a
  comment saying it is not a security boundary.
  **Still open, deliberately deferred to W3:** the *writers* still emit working-directory-relative
  `spaces/<space>/config/x_schema.toon` (`PipelineCompiler`, `stream-bundle.ts`'s `conventionPath`, and the
  space template's literal `spaces/${SPACE}/...` placeholder). Reading portable refs is now supported;
  *producing* them belongs with promotion export, together with the two WARNING call sites, which would
  otherwise emit a spurious "unresolvable" warning for a portable draft.
  *Verified: full `mvn -o clean test` reactor green, 24/24 modules; `SchemaRefResolutionTest` 8/8 (including
  a real relocate-the-directory-and-load proof, not a proxy for it) and `SchemaFileFindingsTest` 6/6.*
- **W2 — config-key unification (U-D, U-E). 🔄 IN PROGRESS — U-E (the plugin guard) SHIPPED 2026-07-31.**
  **U-E done, and it was a live bug rather than the cleanup this plan assumed.** `pluginManaged` was not a
  cosmetic read-only notice — it was a **whole-pane lockout**: any config with `parsing.plugin` or
  `processing.ingester` rendered a single "author that in the pipeline TOON directly" alert and *nothing
  else* — no sample panel, no type toggle, and no segments editor. Since `savePlugin` writes
  `frontend: 'plugin'`, which is exactly what the guard matched, **a plugin config saved through this
  pane's own segments editor locked itself out of ever being reopened.** The editor shipped 2026-07-31 and
  the guard was never lifted with it.
  Lifting it required `rehydratePlugin`, without which the fix would have been a worse regression: a guided
  Save stores `parsing.plugin.ingester` (the **FQCN**), never the parser id, so the id is recoverable only by
  matching `ingesterClass` against the served `/parsers` catalog — otherwise `frontend: 'plugin'` normalizes
  to `delimited` and the pane would confidently present a plugin pipeline as delimited, and a Save would
  overwrite its parsing block. Restoration deliberately does **not** go through `setType`, so arriving at a
  saved stream does not mark the pane dirty. When the FQCN matches nothing served (plugin jar absent), the
  new `unservedPlugin` alert says so instead of letting the fallback read as "this pipeline is delimited".
  *Verified: `lint:tokens` + 1893 UI tests + production build green, and a real in-preview walk — authored an
  ASN.1 stream with a segment, saved, **reloaded**, and the pane came back with ASN.1 selected and the
  segment key + column re-hydrated, no lockout and no spurious alert (the exact path that was broken).*
  **U-D — `json.records_path` half SHIPPED 2026-07-31; the collector half is BLOCKED on a naming
  decision (see below).** `parsing-attributes.ts` now offers `json__records_path`, gated
  `dependsOn: {json__format, notEquals: 'newline'}` because `PipelineConfigParser.parseJson`
  **hard-fails** a nested path under NDJSON — so the shape the engine rejects is simply not authorable.
  Two specs: the gate's semantics, and that the flat key lowers to the nested `json.records_path`
  (a flat key that leaked to disk would be silently ignored by the parser).
  *Verified: `lint:tokens` + prod build + 1895 UI tests green (was 1893).*

  ### U-D collector half — SHIPPED 2026-07-31: the UI now speaks the engine's vocabulary

  *Operator decision: rename UI → engine (rather than widening `BuiltinNodeType`), so the UI became
  honest without committing the executor to a taxonomy it does not implement.* What follows is the
  finding as diagnosed; the resolution is at the end of this section.

  Checking the collector keys against the engine (as U-D requires) turned up something larger than a
  key collision. **Three of the four things needed for these tables to matter are real; the type
  strings were not.** `BuiltinNodeType` (`inspecto-engine/.../BuiltinNodeType.java:26-96`) is the
  server's authoritative catalog behind `GET /pipelines/node-types`, and it contains **no**
  `collector.file`, `collector.database`, `collector.stream`, `sink.file`, `parser.dsv`,
  `transform.record`, `transform.aggregate` or `transform.alert`. It has `acquisition`, `adapter`,
  `parser`, `transform.map|filter|select|derive|validate|dedup.*|route|split|merge`, `enrichment`,
  `sink.persistent|materialized|view`, and a fifth category **CONTROL** (`alert`, `gap`, `event`)
  the UI does not model at all. The overlap between the UI palette and the engine is exactly
  **`transform.filter` and `transform.route`**.

  Consequences, each verified rather than inferred:
  - `PipelineValidator` flags an unknown `type` as `UNKNOWN_TYPE` — a **warning, non-fatal** — and
    never inspects `config` keys at all. `PipelineCodec` stores `config` as an unchecked map. So the
    whole fictional vocabulary **persists and validates clean**.
  - `PipelineCompiler.compile()` groups nodes by matching `type()` against `BuiltinNodeType`. A
    `collector.file` node matches nothing, so it is **silently dropped** — it can never become the
    acquisition input. Correcting `collector.file`'s KEYS would therefore be polishing a node the
    compiler discards; that is why this half is blocked rather than merely unfinished.
  - This is a textbook breach of *a mock must never be more lenient than the server*: the palette is
    served by `mock/handlers/pipelines.handler.ts:26-45`, so the authored-pipeline editor looks
    correct offline and cannot lower a single collector against the real backend.
  - **This lands squarely on W0.** W0's gate is proving the lift→lower round-trip lossless. With the
    two vocabularies disjoint, W0 cannot pass for any collector or sink node as authored today —
    so the vocabulary reconciliation is a W0 **prerequisite**, not a W2 nicety.

  The keys themselves, for whenever the decision lands (engine-real, from `PipelineConfigParser.java:396-500`):
  `recursive` (bool) and `min_age_seconds` **do not exist** — the real keys are `recursive_depth`
  (int) and a nested `stability.window` (duration, default `30s`). Onboarding's `COLLECTOR_ATTRIBUTES`
  is correct; `node-attributes.ts` is not. And `collector.database`/`collector.stream` keys are not
  pipeline-`collector:` keys **at all** — `query`, `watermark_column`, `topic`, `bootstrap_servers`
  live in the **ConnectionProfile `options:`** map read by each connector
  (`DbExportConnector.java:86-100`, `KafkaConnector.java:88-320`), referenced from the pipeline only
  via `collector.connection`. `fetch_size`, `group_id` and `batch_size` are read **nowhere** in the
  backend — `KafkaConnector` deliberately uses no consumer group (offsets come from the acquisition
  ledger, `enable.auto.commit=false` hardcoded), so `group_id` is absent *by design*, not by omission.
  Those three tables are thus a **different concern** from the collector block — which is why they are
  now simply **gone** rather than merged.

  #### What shipped

  1. **`mock/handlers/pipelines.handler.ts` is a faithful port of `BuiltinNodeType`** — all 20 types in
     enum order with the enum's labels, descriptions and `accepts`/`emits` (`PipelineRel` constants),
     including the **CONTROL** category the palette never had. Five specs pin it, including one that
     fails if any retired fiction reappears and one asserting the two dedup subsystems stay distinct.
  2. **`node-attributes.ts` is keyed by engine types**, and `acquisition` **reuses the shared
     `COLLECTOR_ATTRIBUTES`** — which moved to `inspecto/component-model/` in the same change, because a
     feature-local copy is precisely how the drift happened (and a `pipelines`→`catalog/onboarding`
     import would have broken the no-cross-feature-dependency rule). That is U-D's "one table per
     concern", enforced by a spec asserting *object identity* between the two adopters.
  3. **Sinks keep a schema, trimmed to what the backend reads** — `format` (CSV | PARQUET) and
     `compression` map to `output.*`; `partition_by`, `table`, `mode`, `key_columns` are read nowhere and
     are gone rather than left as convincing dead knobs. All three sink kinds share one table: the kind
     is the materialisation behaviour, not a different config shape.
  4. **The remaining `transform.*` types are deliberately unspecced** (free-form fallback) instead of
     re-guessed — a best-guess table that looks authoritative is the thing this change removed.
  5. **Seeds + edge rels corrected across 6 seed files**: `rel: 'success'` on collector/transform edges
     became `data` (`success` is sink-emitted), and `kept` — never a `PipelineRel` at all — became
     `data`. Two graphs had `filter → alert → sink` chains; since `alert` is CONTROL and emits nothing,
     the rows now **fan out** from the filter to both, which is what the chain always meant. CS5 keeps a
     real `success` edge on the sink that can actually emit one.
  6. **Fictional seed config keys replaced with engine-real ones**: `min_age_seconds` → nested
     `stability.window`, `recursive: true` → `recursive_depth`, and the DB collectors'
     `query`/`watermark_column`/`fetch_size` → `incremental.watermark` (the SQL belongs to the
     Connection profile). The two Kafka sources became `adapter` nodes.
  7. **`MOCK_STORE_KEY` bumped v20 → v21.** Required, not cosmetic: a persisted store keeps its authored
     pipelines, so without the bump every existing browser would have gone on serving the fictional
     types and `kept` edges — corrected only for first-time visitors.

  *Verified: `lint:tokens` + prod build + **1904** UI tests green, and in the offline preview (the default
  environment has `mockFlows: false`, so Pipelines cannot load there at all): the palette renders all 20
  engine types across all five categories, the store reseeded to v21 holding **only** engine-real types
  and rels, all 7 seeded pipelines load, and Validate on the 19-node `mediation_backbone` returns 19
  informational "not yet tested" findings and **zero** structural or unknown-type findings.*

  ⚠ **Still open after this:** the engine has **no grouping transform**, so `transform.aggregate` became
  `transform.derive` to keep the rename a rename — the honest modelling of a rollup is
  `sink.materialized`, which changes graph shape and so was not done here. And `PipelineCompiler` reads
  only a narrow set of keys per role, so a node can still carry config the compiler ignores: W0 must
  measure that, which is now possible because the vocabulary finally matches.
  One spec table per concern under
  `inspecto/component-model` (or a shared `pipeline-specs.ts`), adopted by both features; plugin-parser
  guard lifted.
  *Verify: `lint:tokens` + full `ng test` + prod build; a spec asserting the two features render the
  same keys for the same concern; a live walk authoring one stream through each surface and diffing the
  written TOON — it must be identical.*
- **W3 — promotion export (U-F).** One pipe; extend the closure to decision rules and reference
  datasets (both gaps confirmed in §2); add **import-time referential integrity** so a missing
  connection/schema fails at import rather than at first poll; `dirs` re-derived on the target.
  *Verify: a real two-instance walk — export from one space, import into a second, and RUN it, with
  the only manual step being the connection credentials. The endpoint skill for any new route.*
- **W4 — stages become typed nodes (U-B).** Gated on W0.
  *Verify: full UI gate + a live resume walk (leave mid-stage, return, state intact).*
- **W5 — graph editor writes the canonical config (U-A).** Gated on W0. Largest piece; last.
  *Verify: full reactor; a config authored in the graph editor actually polls and commits a batch.*

## 6. What this reverses, and what survives

**Reverses** (from `onboarding-pipeline-split.md`):
- **D-C** — "Pipeline = the processing graph only." Now one model; "Stream" is a Catalog label on it
  (`kind: "STREAM"`), which is already how the backend behaves. ⚠ D-C's rollout was slice S5 and
  **never shipped**, so `GLOSSARY.md` was not yet touched — nothing to unwind there. Confirm before
  editing the glossary.
- **§7 non-goal "No editor unification."** Now an explicit goal (U-B).
- **D-A** — enrichment as a Stage-2 pipeline template. Now: enrichment is a node in the one graph.
  *`_enrich.toon` grandfathering carries forward unchanged — no forced migration.*
- **S3 / S5 / S6** recast under W4/W5.

**Survives:**
- **S1 (SHIPPED 2026-07-30)** — go-live auto-registers a `dataset` component. Keep it. It stops being
  "the handoff artifact" between two planes, but Datasets remain the query surface, and the behaviour is
  independently useful. **Nothing to revert.**
- **S2** — the shared store/Dataset picker. Still wanted; reinforced by U-D.
- The **§5 UI reuse map** and the standing reuse mandate.
- **D-B** — no new trigger machinery; `on_pipeline` already exists.
- ⚠ **The `EnrichmentService` semantics warning must not be lost** (superseded plan §3): the registered
  enrichment path runs **per committed batch** while `EnrichJob` is a **full recompute**. Any relocation
  of enrichment authoring must not silently convert incremental cost into full-recompute cost.
  **Still a live risk under this plan** — it now belongs to W4.

## 7. Non-goals

- No backend rename or re-keying of Stage-1 identifiers (U-G).
- No forced migration of `_enrich.toon` or of existing `*_flow.toon` files (read, never newly written).
- No new trigger machinery (D-B).
- No `${VAR}` overlay mechanism for general config — out of scope; `dirs` re-derivation covers the
  promotion case without it.
- Not extending `FieldSpec` to nested lists. The segments editor stays bespoke
  (`segments-editor.component.ts:26`: `FieldSpec` cannot express "a list of segments each with a list of
  columns"). Revisit only if W4 finds a second case.

## 8. Verification

Per workstream above. Global gates: UI slices take the angular-ui gate (`lint:tokens` + full `ng test` +
production build); backend slices take the full offline reactor per `build-verify` with the baseline
re-derived, never quoted; any new route takes the `endpoint` skill (real-HTTP test class, every gate).
W3's verdict is a genuine two-instance import-and-run, not a green unit suite — the last two shifts both
found destructive config bugs live that unit tests missed. Commits per `release-workflow`: `feat:` →
master only, no push without an explicit ask.
