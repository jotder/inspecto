# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here has been archived under
> [`archived-documents/`](archived-documents/) (historical plans, superseded designs, point-in-time snapshots) —
> kept for provenance, not maintained. When you add or retire a doc, update this index in the same change.
>
> **Structure (binding — see root `CLAUDE.md` "Documentation lifecycle"):** current knowledge lives in the
> **[OKF bundle](okf/index.md)** + the small root canon below · in-flight plans live in
> [`superpower/`](superpower/) · everything else is archive. *(Consolidated 2026-07-16: ~80 shipped
> plans/reviews distilled into OKF and archived; the former root reference docs relocated into OKF.)*

---

## Start here (session essentials, ~800 tokens)

- `CLAUDE.md` — project rules (vocabulary, doc lifecycle, skills/agents, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Root canon (durable, audience-facing)

- [`USER_GUIDE.md`](USER_GUIDE.md) — **end-user guide** to the web app: navigation, Spaces/Lens, every
  screen, shared UI elements. Canonical `GLOSSARY.md` vocabulary.
- [`GLOSSARY.md`](GLOSSARY.md) — ⚠️ **canonical vocabulary, BINDING** — single source of truth for every
  concept's name, the banned synonyms, and the UI→model→backend rename map.
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from
  code or git: key decisions, gotchas, engine seams & perf, pointer map.
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — **requirements-of-record**: full platform requirement set with
  reconciled MoSCoW, edition mapping, NFRs, sequencing.
- [`BACKLOG.md`](BACKLOG.md) — **the consolidated open-items index** (one line + pointer each). Refreshed
  2026-07-16 from all archived plans' deferrals.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — every feature's TOON shape + where defined, examples,
  packaging, runnability. Pairs with the runnable suite in [`../inspecto/examples/`](../inspecto/examples).
- [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **Advanced Operations & Internals Guide** (the production
  investigation hub): per-component process, events, metrics, persisted state, `-D` flags, full Control
  API, troubleshooting playbooks. **Living doc.**
- [`EDITIONS.md`](EDITIONS.md) — edition model (Personal/Standard/Enterprise = build flavors, never branches).
- [`BRANCHING.md`](BRANCHING.md) — branch & release policy (versions = branches; merge-forward; SemVer + CC).

## The knowledge bundle — OKF (current as-built truth)

The **one** structured, agent- and human-readable [OKF](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle — one concept per file, cross-linked, indexed by graphify. As of 2026-07-16 it also **contains the
former root reference docs** (each index lists them):

- [`okf/`](okf/index.md) — master index, three sections:
  - [`okf/frontend/`](okf/frontend/index.md) — the Angular console: architecture, conventions, design
    system (incl. data-table + tree-table), the feature screens, services.
  - [`okf/backend/`](okf/backend/index.md) — the Java backend: engine (incl. Stage-1 architecture,
    DB/persistence layer, plugins), acquisition (incl. the full framework doc), control plane (`/api/v1`,
    API-stability policy, queries, jobs + Job Framework, the Job-vs-Step capability boundary, metadata
    bundle, multi-space), pipeline-graph
    (incl. the full design doc), components, config (incl. the configuration + parsing-options
    references), editions & security, agent, modules (incl. the Maven reactor & module-extraction
    playbook, new 2026-07-21), build-run (incl. operations reference, troubleshooting,
    performance), gotchas, integrations, architecture layers.
  - [`okf/agentic/`](okf/agentic/index.md) — **eoiagent** (the embeddable agent framework, separate repo)
    distilled + the Inspecto integration seam.

## Contracts, runbooks, audits

- [`api/`](api/README.md) — **machine-readable v1 HTTP contract**: `openapi-v1.json` + canonical
  `examples/`, enforced by `ApiContractTest`; `schemas/` (metadata-bundle JSON Schema + samples).
- [`ops/`](ops/) — operational runbooks: backup/restore, UAT seeding, maintenance, secret rotation.
- [`ui/accessibility-audit.md`](ui/accessibility-audit.md) — the **living** inspecto-ui WCAG/a11y
  findings register (referenced by `okf/frontend/conventions/accessibility.md`).

## Stakeholder set (audience-targeted)

- [`stakeholders/`](stakeholders/README.md) — per-audience reading map: executive brief, product
  capabilities, technical architecture, operations guide, **testing guide** (added 2026-07-16).
- [`roadmap/`](roadmap/) — stakeholder overview, roadmap (Now/Next/Later), presentation decks.

## In-flight plans (`superpower/` — plans live here ONLY while active)

- [`superpower/platform-services-plan.md`](superpower/platform-services-plan.md) — **v1.0 DRAFT
  2026-08-09, not approved — Platform Services & the plugin envelope.** The named seam
  (`PlatformServices`, ⛔ not "capability" — that's RBAC's word) granting engine facilities to
  plugins by declared `requires:`: v1 services `notifications` · `incidents` · `schema` ·
  `consignment-status`; one envelope, three mounts (Job pack / ConsignmentProcessor / stage-2
  executable Step). Opens the Step-kind registry (`LOWERED`/`EXECUTED` modes, supersedes the
  "closed on purpose" note; totality preserved by fail-closed arming) and stage-3 pack-contributed
  services. Ships `tools/scaffold.mjs` + `PackTestHarness` (new job/step/service/processor
  skeletons, offline-buildable). Fulfils the `JobServices` façade named-but-never-built in
  `JobContext`'s javadoc.
- [`superpower/consignment-addressing-plan.md`](superpower/consignment-addressing-plan.md) —
  **v1.1 DRAFT 2026-08-09, not approved — the addressing layer: naming a set of Consignments as one
  relation.** Thesis: in-motion and at-rest are the *same type* here (both a DuckDB relation), so the
  Job/Step boundary is **selection, not data state** — which replaces the in-motion/at-rest framing in
  `okf/backend/control-plane/job-vs-step.md`. The one mechanical change: reads become a
  **catalog-pruned explicit file list** instead of a `**/*.parquet` glob. Grounding (verified
  2026-08-09) reshaped the plan: the catalog is **80% built and switched off** —
  `consignment_outputs` already carries `path`/`row_count`/`bytes`/**`generation`**/`state` but is
  gated behind `-Dconsignment.outputs.backend`; **no event-time min/max is persisted anywhere**
  (`record_day` is a write-time approximation its own javadoc calls silently divergent;
  `RunArtifact.timeRange` is always null); dataset `role: temporal` exists in config but
  `DatasetRelation` never reads it; and **no windowed scan of ingested data exists at all** — alert
  `window: 1h` filters the *batch audit ledger* by wall-clock, so content rules are new capability,
  not an extension. §5 is a **strategy framework, not a design**: pinned hopping-window vocabulary
  (size/hop/pane/dirty window), a T1–T3 rule tier test, firing discipline (monotonic thresholds fire
  on crossing — no completeness wait), a six-rung evaluation ladder (A catalog-pruned rescan → F
  decayed counters) with escalation triggers, and a per-source instantiation template. Scope guard
  (operator): BI, RA, Warehouse and Fraud are **deferred** to §6 extension points. 10-step delivery
  table; step 1 is *measure rung A* and nothing past step 3 should start before that number exists.

- [`superpower/job-parameter-contract-plan.md`](superpower/job-parameter-contract-plan.md) —
  **REFINED + GROUNDED 2026-08-06 (UI + backend) — the Job authoring contract + extensible runtime
  Expressions.** **§0-A: user-facing 'Job' un-banned** (operator decision, reverses the ELT
  amendment's Phases 3/6 retirement — see `docs/GLOSSARY.md` §6-A/§13). Guiding principle:
  **versatility over built-ins** — capability is added by *registration*, never by editing a
  `switch`. Findings, verified against source: (1) `AttributeSpec` (UI) is strictly richer than
  `ParameterDecl` (backend — 9 registered Job Types vs 5 hardcoded in the UI picker); (2)
  **`ParameterResolver` evaluates `$`-expressions in only 2 of its 5 layers** (plus a 6th legacy
  `pipeline`/`flow` shim) — authored `config` values (what the UI writes) and trigger `args` are
  returned raw, so `$yesterday` typed in the UI reaches the Job as a literal string; (3) the
  vocabulary is a hardcoded `switch` (10 tokens, confirmed exhaustive) — not extensible, not
  discoverable, unknown tokens fall through silently. Plan: an `ExpressionProvider` SPI + registry
  mirroring `JobTypeProvider`'s load order, `$$` literal escape, fail-closed unknown tokens (all
  three load paths, not just Packs — the ServiceLoader path today only warns), `GET
  /jobs/expressions` catalog with **server-evaluated previews**, a widened `ParameterDecl` +
  **§7.4 decl→widget mapping table** (the generation contract), `JobTypeDescriptor` provenance,
  `secret` masking at the route boundary (not `JobConfig.toMap()`, which also feeds bundle export),
  and `on_signal` trigger authoring in the UI (new). Builder-on-record is confirmed house style
  (`EventQuery`/`Event`/`ObjectQuery`), not a deviation. **§6.1 recommended resolution:** scoped
  evaluation — `sql.template`'s SQL body stays `$`-as-template-parameter; expressions resolve in
  parameter *values* via indirection (`params: {from: "$event_day(-7)"}`), serving the driving use
  case with zero migration. **Event Day deferred** to its own plan; **dataset-triggered Jobs (§5-A)**
  are pure configuration once the ELT amendment's `dataset.write` Signal (slice S3a) ships — no new
  machinery here beyond `on_signal` UI authoring. 17-step delivery table (0–16); step 0 (vocabulary
  docs) done with this refinement.

- [`superpower/elt-final-amendment-plan.md`](superpower/elt-final-amendment-plan.md) —
  **APPROVED v1.0 (2026-08-05) — the ELT final amendment: one model, one vocabulary, one authoring
  surface.** The operator's unification directive: a Pipeline is an ordered chain of uniform Steps
  (seven verbs + plugins — record `dedup` is a Step by operator call; file dedup stays a Guarantee),
  Schema and Mapping split into separate reusable CSV component files,
  Enrichment and Matrix become table-entry Pipelines, housekeeping becomes declared Guarantees, and
  the 20-id node palette leaves the user surface. Routing is first-class in the recipe (§2.6:
  `route` with nested branch chains — recipe = tree, rejects Guarantee-routed, fan-in stays canvas),
  and the generated **Pipeline Document** (§5.1) serves business verification/sign-off with a
  CSV-mapping import change loop. Schema-registry *semantics* without a registry service (§3.4):
  compatibility save-gate, schema fingerprint pinned per Consignment, compile-time type flow via
  DuckDB `DESCRIBE`, relations derived from usage. Umbrella for the branch-aware-executor thread
  (Stage C SIGNED OFF 2026-08-05, folded as its §2.4/Phase 4) and headline of the already-MAJOR
  release (breaking reason #5). **All thirteen §9 decisions resolved 2026-08-05** — incl. D-12
  (Batch→Consignment = Phase 7, in-window, sequenced last) and D-13 (per-Step `enabled:` pause with
  durable park/drain, Phase 4 — the NiFi-style testing loop over production data). **Phase 0 DONE 2026-08-05** (GLOSSARY amended — new §5 entries, §6-A Job retirement note,
  six §13 rename rows; vocabulary guard green). **Phase 1 GROUNDED 2026-08-05** — five premise
  corrections in the plan's §8 block (no SchemaConfig class; ConfigCodec has no format registry;
  the gate seam is `ConfigRoutes.writeConfig`; Mapping stays path-addressed in slice 1; the
  fixture count was wrong). Phase 1 slices 1–3 SHIPPED 2026-08-05: sibling `_mapping.csv` dual-read;
  split-write + BACKWARD compatibility gate at `ConfigRoutes`; `schema`/`mapping` component kinds
  with `schema/<id>` + `mapping_file` execution wiring. **Phase 2 S1–S5 SHIPPED (S1-S4 2026-08-05,
  S5 2026-08-06) — Phase 2 fully closed**: schema fingerprint pinned per Consignment (manifest +
  `consignment_outputs`); per-Step type flow (`TypeFlow` DESCRIBE-derived output schemas,
  footer-parity gate green); `RecipeCompiler` (linear verbs onto `PipelineEditable.lower`);
  `RecipeConverter` + fixture round-trip parity over the whole repo corpus; **S5**: `route` +
  `dedup` (QUALIFY) lowering — `dedup` ships with real execution at the `writeAndTrace` seam,
  `route` is lowering-only behind a fail-closed `prepare()` arming gate (no production call site
  yet for the branch-aware executor). Remaining pre-Phase-3 loose ends (TypeFlow save/dry-run
  wiring, the recipe API route) are UI-adjacent plumbing, not compiler work. **Phase 3 S1 SHIPPED
  2026-08-06**: `summarize` compiles (`transform.summarize` node, `processing.summarize
  {group_by[], measures[]}` reusing `MaterializeTask`'s measure shorthand grammar) — compile-only,
  same fail-closed `prepare()` arming posture as `route` (`MaterializeTask` stays the runtime).
  **Phase 3 S2 SHIPPED 2026-08-06**: the reference join compiles per D-4 (`transform: {join:
  references/x, on: k}` → `transform.join` node → `processing.join {reference, on}`) — as its own
  node kind, NOT the companion-persisted `enrichment` node (which lower deliberately ignores);
  compile-only, same arming posture. **S3 design spike 2026-08-06**: table-entry `collect` does
  NOT get the S1/S2 compile-only treatment — no real runtime shape to mirror (unlike
  `MaterializeTask`/`EnrichmentEngine`) and `dirs.poll`/`dirs.database` are hard-required at parse
  time, before any arming gate could apply. Needs real new machinery (a Dataset-write Signal +
  a Pipeline-bindable collect variant), not a slicing choice — left as a documented gap pending
  operator scheduling. **S4 SHIPPED 2026-08-06**: the parity gate, scoped to representation (the
  verbs are compile-only): a real draft fixture (dedup+join+summarize) joined the walked corpus;
  every real `*_enrich.toon` reference proven expressible as `transform.join`; summarize measures
  pinned to `MaterializeTask`'s grammar via `MeasureCompiler` (`RecipeVerbParityTest`). The
  execution half of the gate is S3-blocked. **Phase 3: S1/S2/S4 shipped, S3 deferred.** Next: the
  S3 design (operator call), Phase 4 (Guarantees + Stage C + per-Step `enabled:`), or UI S1–S3.

- [`superpower/elt-amendment-ui-plan.md`](superpower/elt-amendment-ui-plan.md) — **v1.0
  (2026-08-05) — the amendment's unified UI plan** (companion to the above). One design call:
  the recipe editor is a **second projection of the existing `AuthoredPipeline` model** — same
  reducers, same `PUT /pipelines/{name}/graph` save, canvas kept behind a mode toggle for
  non-expressible graphs. Seven slices S1–S7 (S1–S3 unblocked before any backend phase): Step
  cards + Guarantees panel + route-branch UI + "Step" copy sweep now; step-types palette,
  Schema/Mapping grid editors with cell-level compatibility findings, Pipeline Document
  export/import, and table-entry collect as the backend phases land. Applies the `angular-ui`
  skill; reuses the shared editors (grammar/collector/enrichment/schema-form/data-table) —
  no new dialog kinds.

- ~~`superpower/grammar-config-unification.md`~~ — **SHIPPED and ARCHIVED 2026-08-04**
  ([archive copy](archived-documents/plans-archive/grammar-config-unification.md), provenance only).
  One `<inspecto-grammar-editor>` for the Onboarding Parsing stage and the Pipelines `parse` node
  dialog (renamed `ParserConfigDialog` → `GrammarEditorDialog`). The sequel to
  `collector-config-unification` and its **opposite**: those two surfaces were already one feature,
  these shared only the renderer layer and the gap between them held two live defects — a
  `use: grammar/<id>` binding that never reached disk (the save was *refused*, not merely lossy), and
  two competing config keys where the editor wrote the losing one. Both closed before the UI
  unified. As-built knowledge: [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md).
  Deferred to [`BACKLOG.md`](BACKLOG.md): the unknown-`use:`-prefix refusal, and the slice-6 live
  browser smoke (the preview pane was unreachable this shift).

- ~~`superpower/collector-config-unification.md`~~ — **SHIPPED and ARCHIVED 2026-08-04**
  ([archive copy](archived-documents/plans-archive/collector-config-unification.md), provenance only).
  As-built knowledge: [`okf/frontend/features/collector-config.md`](okf/frontend/features/collector-config.md).

- ~~`superpower/vocabulary-and-config-contract-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-04**
  ([archive copy](archived-documents/plans-archive/vocabulary-and-config-contract-plan.md), provenance only).
  All nine defects (D1–D9) and all twelve sequence items closed or reclassified. As-built knowledge lives in
  [`okf/frontend/features/pipelines.md`](okf/frontend/features/pipelines.md); the deliberate deferrals (D6
  timezone-governs-scheduling, D8 authorable `reference:` block, the agent-tool `flow` argument, bare-word
  `flow` identifiers) are in [`BACKLOG.md`](BACKLOG.md). What it left behind, all still live:
  the four-pass vocabulary guard (`tools/check-vocabulary.mjs` — docs, TOON keys, knowledge trees, Java/TS
  identifiers), `NodeConfigNameContractTest` (a declared key must reach its engine field), and
  `NodeAttributesContractTest` + the committed `node-attributes.contract.json` (the server publishes the node
  cfg vocabulary; client and server are byte-compared).

- [`superpower/consignment-elt-architecture.md`](superpower/consignment-elt-architecture.md) —
  **IN FLIGHT (opened 2026-08-03) — three sections are now CLOSED, the rest is still design (2026-08-04).**
  **§11.3 `consignment_outputs`** is complete: `DbConsignmentOutputStore` behind
  `-Dconsignment.outputs.backend` (default-off), production callers on all three write paths — ingest
  (`BatchProcessor.finalizeSource`), enrichment, Pipeline sinks — each with a real per-file `row_count` asserting
  §7.2 reconciliation; `supersede`/`markCompactedAway` wired to `ReprocessCommand` + `PartitionCompactor`, which
  turns the §11.3(a) silent row-duplication bug into a refusal; and the `batch_id` → `consignment_id` rename for
  the audit ledgers + `BatchManifest` (⚠ a **breaking** API-v1 key change — `/runs/*/batches` and `/runs/*/files`
  now emit `consignment_id`). **§14** is complete: the `ConsignmentProcessor` SPI, `ProcessorContext`, the
  `consignment.process` Job Type, `ConsignmentReader`, `SummaryEmitter`. **§7.2/§7.3's summary tier** is
  complete: `SummaryWriter` persists one Parquet file per (Consignment × record-day) with a `_measures.csv`
  composability sidecar, registered under `<target>__summary`. As-built in
  [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) §3.4/§3.9 and
  [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md); deferred items in
  [`BACKLOG.md`](BACKLOG.md) §4. Nothing built now waits on the plan — the next section anything depends on is
  §8's end-of-period pass, which needs a decision rather than code. Captures the design conversation behind a
  Consignment-based ELT model that replaces a Kafka record-at-a-time ETL. Core claim: the two apparent
  execution systems (`BatchProcessor` file/batch path · `PipelineExecutor` graph path) are one model built
  from both ends, joined by making the **Consignment manifest a first-class DuckDB relation** flowing on
  graph edges. ⚠ **Its vocabulary is now BINDING, not just this doc's:** *Consignment* was accepted
  2026-08-03 as the canonical name for the unit-of-work entity — GLOSSARY §2 redefines it, §6-A is now
  **`Run ⊇ Consignment ⊇ File`**, and §13 carries the Batch→Consignment row, which **supersedes
  Flow→Pipeline as the largest blast radius** (517 Java files, 39 `@PublicApi` ⇒ breaking, needs a version
  bump; `batch` survives for the generic grouping sense). Identity is `(consignment_id, run_id)` — Run is
  already canonical for "one execution". Settled here too: ⛔ *Instance* (collides with GLOSSARY Type/Instance);
  append-only, no catalog; the invariant *no file holds two Consignments*; manifest-driven reprocessing;
  compaction past a 7/14/30/`none` horizon with the `lateness ≤ seal ≤ compaction ≤ raw-retention` chain;
  mandatory `count` + additive-only measures; `OPEN → SEALED → REOPENED` partitions with a
  `PartitionSealed` event replacing end-of-day cron; statistical completeness via a new **`baseline`
  Expectation kind** (KNN deferred to `inspecto-intelligence`, §9.4); event-time captured at load not
  report time, field per schema + timezone per **Stream** with `Local` resolved to a concrete IANA zone
  (§10). Two rules worth reading even out of context: **partition-affecting config must be pinned in the
  manifest** or a config edit silently breaks replace-by-batch (§5.6), and **garbage timestamps route to
  `invalid` rather than creating a phantom partition** (§10.3). §11 grounds the persistence model against
  the existing `BatchAuditWriter` CSVs / `EventType` / `Signal` / `ObjectType` — most of the event surface
  already exists; the real gap is **no durable output-file registry** (`PartitionOutput` is in-memory only),
  which §5.3 reprocessing, §5.5 and §6.3 compaction all need. **`ProcessorContext` (§14) is now DESIGNED
  (2026-08-04), still unbuilt** — grounded against the code, and the grounding **corrected §4.7**: there is no
  `on_commit` Job trigger (commit-fired Jobs ride the `pipeline.commit` **Signal**, which carries `batchId`),
  and `ON_COMMIT_SAME_GRAPH` is a graph-structure refusal that says nothing about Jobs, so it must not be cited
  as enforcing the in-motion/at-rest line. §14's decisions: the context is a Consignment-scoped façade over the
  **existing** Job seam (`JobTypeProvider`/`JobTypeRegistry` — no new SPI), an 8-member surface that delegates
  three `JobContext` emitters rather than exposing `JobContext`, a `SummaryEmitter` that enforces §7.2
  (`count` mandatory, additive-only) instead of a raw writer, and no writable connection / no `PipelineConfig`
  / no in-motion types. **§14 and §11.3 are one unit of work** — `outputs()` needs row counts that
  `PartitionOutput` does not carry. Build order + verify conditions in §14.4. New model surface listed in §13;
  unverified items and open decisions in §15.

- [`superpower/pipeline-build-test-run-gaps.md`](superpower/pipeline-build-test-run-gaps.md) —
  **IN FLIGHT (opened 2026-08-02). Steps 0–4 SHIPPED same day** (`4fe388a1`): the armed-pipeline silent
  failure closed (G4), the two 404ing test affordances gated off rather than deleted (G1), a
  `lowerable` signal added to the palette (G2), refusals routed to the Validation dock (G3), a
  grandfathered-flow warning banner (G5, not read-only as first drafted — see the plan's inline
  correction). **Step 5 — a bounded test run over real inbox files — is the one gap left**, and is a
  backend job (`PipelineDryRun` is synthetic-only; no stop-at-node primitive; the run-to-here route is
  reserved but unregistered). Gaps tracked in [`BACKLOG.md`](BACKLOG.md) §Pipelines.

- ~~`superpower/sinks-config-format-plan.md`~~ — **SHIPPED end-to-end 2026-08-02 (all 4 slices, `0cdc9dff`
  + `79dcb3e6`), plan archived** to
  [`archived-documents/plans-archive/sinks-config-format-plan.md`](archived-documents/plans-archive/sinks-config-format-plan.md).
  A `*_pipeline.toon` now declares a top-level plural `sinks:` list; as-built lives in
  [`okf/backend/engine/output-sinks.md`](okf/backend/engine/output-sinks.md) (ingest fan-out via
  `BatchIngestStrategy.writeAndTrace`, predicate `cfg.sinks().size() > 1`, editor round-trip). Deferred
  follow-ups → `BACKLOG.md` §6.
- ~~`superpower/onboarding-pipeline-unification.md`~~ — **SHIPPED end-to-end 2026-08-01 (W0–W5), plan
  archived** to
  [`archived-documents/plans-archive/onboarding-pipeline-unification.md`](archived-documents/plans-archive/onboarding-pipeline-unification.md).
  Onboarding and Pipelines are now **one model**: `*_pipeline.toon`/`PipelineConfig` is canonical (it is
  what the engine executes), stages are typed nodes over the head of the same graph, and the graph editor
  writes that canonical config through the **editable lift/lower round-trip** — the W5 as-built lives in
  [`okf/backend/pipeline-graph/pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md)
  §16 (`PipelineEditable`, named `PipelineCompileException` refusals, `PUT /pipelines/{name}/graph` reusing
  the `/config/write` gate; the old `*_flow.toon` authoring writes retired, grandfathered flows read-only).
  Shipped U-A–U-G: canonical config wins · typed-node stages · one path-addressed schema store · one
  `AttributeSpec` table per concern · plugin-parser parity · one promotion-grade export pipe · no identity
  churn. Residuals in `BACKLOG.md` §6 (branch-aware executor to *run* the full graph vocabulary;
  `ConfigSpecs.enrichment()` `references:` spec).
- [`superpower/4x-public-pkce-plan.md`](superpower/4x-public-pkce-plan.md) — **`4.x` public-PKCE auth,
  SCOPED 2026-07-25** — the gate on the SEC-INCIDENT-1 rotation (BACKLOG §5): `4.x` must stop needing a
  client secret before the leaked secrets can be rotated. Verified against `4.x` `291c86a1`, and three
  findings reshape it: the live token exchange is `modules/auth/auth-service.ts` (**not** the file BACKLOG
  §5 names), the hardcoded inline secret sits in **dead** code and is deletable today with no design
  change (P0), and `master`'s `inspecto/api/pkce.ts` is a zero-import RFC 7636 impl that ports verbatim —
  so P1 is a **port, not a design exercise** (master's `session.service.ts` does *not* port: it needs
  `/bootstrap` + `/api/v1`). Blocking ask Q1: does the IdP support PKCE + a public client?
- [`superpower/agt-6-plan.md`](superpower/agt-6-plan.md) — **AGT-6 plan — AGT-6a A1–A4 (incl. A4-status)
  SHIPPED 2026-07-26, plan STILL ACTIVE** (**A5** + the `kpi_report_builder` host + all of AGT-6b remain).
  Splits the requirement: **AGT-6a** inline AI authoring (one shared `<inspecto-ai-assist>` surface + a pane
  adoption wave, phases A1–A5) vs **AGT-6b** model-composed agent graphs (`Could`, demand-gated behind the
  eoiagent `DryRunProvider` seam). **§3.4 is the A5 scope** — the NL→structure model hop: build it on the
  transport's existing native function-calling, not on a prose-scrape, and expect the cost in the
  unconstrained *nested* payload schemas (`ConfigSpec`→JSON-Schema projection, plan D9). Also carries the
  ladder-as-packaging commercial framing (Tiers A/B/C ↔ edition flavors, air-gap moat, SHADOW-first
  adoption). D1–D4 + D8 answered; **D9–D11 (A5) new and pending**; D5–D7 pending.
  ⚠ **Two of the plan's own premises were WRONG and are corrected in its as-built header** — the L1 tools
  had **no invocable route** (so "no new backend capability" was false ⇒ `POST /agent/tools/{name}`), and
  four of the five take **structured** input, not natural language (⇒ deterministic-derive first, NL
  authoring split out as A5). As-built:
  [`okf/frontend/features/inline-ai-authoring.md`](okf/frontend/features/inline-ai-authoring.md) +
  [`okf/backend/agent/embedded-intelligence.md`](okf/backend/agent/embedded-intelligence.md).
- [`superpower/compliance-certifications-plan.md`](superpower/compliance-certifications-plan.md) —
  **NFR-7 certifications plan, DRAFT 2026-07-23** (SOC 2 Type I → II → ISO 27001 → FedRAMP
  800-53 alignment/ATO-support; HIPAA/PCI scoping statements only); control-level coverage in
  §2b, workstreams C1–C6, sequencing sign-off pending.
- [`superpower/postgres-multi-user-plan.md`](superpower/postgres-multi-user-plan.md) — **Postgres
  multi-user backend, PLAN ONLY 2026-07-27, build not started** (BACKLOG §6 required a plan first).
  Pool behind `JdbcDrivers`, scheme-derived sizing, schema-per-space; phases P0–P4. ⚠ Names four
  things that break under a pool, chiefly `DbAcquisitionLedger.record()`'s DELETE+INSERT being atomic
  only via the single connection + monitor — **a latent data bug worth fixing (P0) even if the rest
  stays deferred**. Two operator questions open.
- [`superpower/deployment-topology-plan.md`](superpower/deployment-topology-plan.md) — **deployment
  topology & operations plan, DRAFT 2026-07-24** — the client-facing deployment offering: tiers T1–T4
  (workstation → single hardened server → gateway-fronted Enterprise → active/passive DR) mapped 1:1 to
  edition flavors, security overlay matrix (TLS, IAM-delegated Kerberos/SSO, secrets/KMS), scaling + RPO/RTO
  posture, per-tier deployment plans, script workstreams SCR-1..11, preflight + post-deploy verification
  blocks; decision asks D1–D8 pending stakeholder sign-off.
- ~~`superpower/delivery-status-webhooks-plan.md`~~ — **SHIPPED end-to-end 2026-07-26 (BACKLOG D8), plan
  archived** to `archived-documents/plans-archive/`. As-built in
  [`okf/backend/control-plane/events-metrics.md`](okf/backend/control-plane/events-metrics.md): the raw-body
  seam (`ApiContext.rawBody`, cached on the exchange), our-id-embedded correlation via a `default` `deliver`
  overload, a per-delivery `DeliveryReceipt` store with per-status timestamps, a `DeliveryStatusAdapter` SPI
  with SendGrid + generic-HMAC impls, and the fail-closed self-verifying callback route. ⚠ SendGrid signs
  **ECDSA P-256, not Ed25519** as the plan said. Residuals in `BACKLOG.md` §6.
- ~~`superpower/findings-spec-plan.md`~~ — **SHIPPED end-to-end 2026-07-26 (BACKLOG D6), plan archived** to
  [`archived-documents/plans-archive/findings-spec-plan.md`](archived-documents/plans-archive/findings-spec-plan.md).
  As-built in [`okf/frontend/features/objects.md`](okf/frontend/features/objects.md): a `findings-spec`
  ComponentStore kind served by `GET /findings/{type}` and rendered by `<inspecto-schema-form>`, with the
  built-in default preserving today's shape. ⚠ It records **two wrong premises in D6's wording** (the
  workflow TOON pattern is boot-scan-only; the `attribute-spec` renderer is the frontend one, not
  `ConfigSpecs`).
- ~~`superpower/generic-tags-plan.md`~~ — **SHIPPED end-to-end 2026-07-26 (BACKLOG D7), plan archived** to
  [`archived-documents/plans-archive/generic-tags-plan.md`](archived-documents/plans-archive/generic-tags-plan.md).
  As-built lives in [`okf/backend/control-plane/tags.md`](okf/backend/control-plane/tags.md): central
  assignment store + `(targetKind, targetId)` addressing shared with **D10** notes, the CSV as a
  projection, rename/delete across the whole vocabulary, and the `/tags` pane. Residuals in BACKLOG §6.
- ~~`superpower/link-analysis-pattern-packs-plan.md`~~ — **SHIPPED end-to-end 2026-07-26 (Link-analysis
  V2 (c); BACKLOG §2 D16 overturned), plan archived** to
  [`archived-documents/plans-archive/link-analysis-pattern-packs-plan.md`](archived-documents/plans-archive/link-analysis-pattern-packs-plan.md).
  As-built lives in [`okf/frontend/features/link-analysis.md`](okf/frontend/features/link-analysis.md):
  pattern packs as a per-Space `pattern-pack` component kind, the `PATTERN_PACKS` const as the fallback,
  and the two costs that ruled out a reserved system Space.
- ~~`superpower/link-analysis-projection-authoring-plan.md`~~ — **SHIPPED end-to-end 2026-07-27
  (Link-analysis V2 (d) authoring half ⇒ V2 complete), plan archived** to
  [`archived-documents/plans-archive/link-analysis-projection-authoring-plan.md`](archived-documents/plans-archive/link-analysis-projection-authoring-plan.md).
  As-built lives in [`okf/frontend/features/link-analysis.md`](okf/frontend/features/link-analysis.md):
  the deterministic `projection_author` tool, the **pane**-supplied column list (no tool-layer route returns
  one), refusal instead of a guess, and the `patchFormFromView` `projections[]` fix it carried.
- ~~`superpower/widget-tags-assignment-migration-plan.md`~~ — **SHIPPED end-to-end 2026-07-27 (D7 call (c)
  — widget chips are now a projection of the assignment store), plan archived** to
  [`archived-documents/plans-archive/widget-tags-assignment-migration-plan.md`](archived-documents/plans-archive/widget-tags-assignment-migration-plan.md).
  As-built lives in [`okf/backend/control-plane/tags.md`](okf/backend/control-plane/tags.md): the edge-side
  `WidgetTags` projection, adopt-on-create vs overwrite-on-update, the lazy per-Space backfill (it cannot run
  at route registration), and why the save dialog's tags field had to go.
- ~~`superpower/mnt-14-incident-retention-plan.md`~~ — **SHIPPED end-to-end 2026-07-27 (the
  `incident_purge` maintenance task — the last root enabler), plan archived** to
  [`archived-documents/plans-archive/mnt-14-incident-retention-plan.md`](archived-documents/plans-archive/mnt-14-incident-retention-plan.md).
  As-built lives in [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md): the derived
  retention window (`closedAt + retention_days`, no new column), why correctness comes from the **cutoff**
  and not the ordering, the `ObjectService.purge` cascade and its dependents-before-object ordering, legal
  hold enforced inside `purge()` rather than only at preview, and the stated G3 decision that the
  append-only event trail **survives** a purge. ⚠ Two premises the plan itself corrected or got wrong are
  recorded there: the `ARCHIVED` state was never the blocker, and G4's four `JobService` store hooks were
  never needed.
- [`superpower/living-operational-system.md`](superpower/living-operational-system.md) — standing
  **architecture north-star** (seven networks over one Component metamodel); R1–R6 all shipped.
- [`superpower/geo-map-case-studies.md`](superpower/geo-map-case-studies.md) — Geo Map CS1–CS5
  case-study pack (spec-pinned demo seeds) — reference.
- [`superpower/pipeline-case-studies.md`](superpower/pipeline-case-studies.md) — Pipelines CS1–CS5
  case-study pack (spec-pinned demo seeds) — reference.
- ~~`superpower/pipeline-rename-and-template-plan.md`~~ — **ALL PHASES SHIPPED 2026-08-02, plan archived**
  to [`archived-documents/plans-archive/pipeline-rename-and-template-plan.md`](archived-documents/plans-archive/pipeline-rename-and-template-plan.md).
  As-built lives in [`okf/backend/control-plane/pipeline-identity.md`](okf/backend/control-plane/pipeline-identity.md):
  the three-tier cost model (`label` display-only → `save-as-template` a fully-isolated non-runnable sibling
  → `rename` the full id migration), the `template: true` gate at the three places work actually starts
  (never "unregister it" — `ConfigRegistry` rebuilds from the run registry, so that would hide it from the
  UI), the recurring findings-diff gotcha (`dirs.*` outside the default allowed roots were never subject to
  the write-time safety policy — block only on findings a rewrite *introduces*, found by a test in both
  `label` and `rename`), and `rename`'s full inventory of persistent state that moves (ledger `source_id` on
  BOTH backends incl. the in-memory default, the commit log + audit CSVs, the DuckDB status mirror,
  dependent enrich/job/expectation/decision-rule/dataset configs) plus its one deliberate exception to
  "`PipelineRunGuard.isRunning` is never a gate." Residuals in `docs/BACKLOG.md` §4: no UI action wired to
  the full `rename` route yet; `rename.journal` is an audit trail, not an automated resume mechanism.

---

## Archive

[`archived-documents/`](archived-documents/) holds **all** superseded/historical material, not maintained:
[`plans-archive/`](archived-documents/plans-archive/) (every shipped/superseded plan & design — incl. the
2026-07-16 consolidation sweep: ui-design-review, incidents-mail + case-management, job-framework-design,
api-contract-design, component-model, the vocabulary renames, and ~60 more),
[`superpower-reviews/`](archived-documents/superpower-reviews/) (all 37 screen review sheets, incl.
`user-guide-audit.md`), the `consolidated-2026-06-13/` stakeholder snapshot, and the pre-4.x planning sets.
Move a doc back up and re-list it here if it becomes current again.

Archived 2026-07-25: [`plans-archive/embedded-intelligence-plan.md`](archived-documents/plans-archive/embedded-intelligence-plan.md)
— AGT-5 P0–P5 COMPLETE (2026-07-21 + polish); as-built distilled into
[`okf/backend/agent/embedded-intelligence.md`](okf/backend/agent/embedded-intelligence.md), open follow-ons in
[`BACKLOG.md`](BACKLOG.md) §2. Retained as the **§8 phasing record**.

Archived 2026-07-25: [`legacy-api-sunset-runbook.md`](archived-documents/legacy-api-sunset-runbook.md)
— the unversioned control-plane API surface was retired (BACKLOG **D3** / **API-5**), so the sunset
runbook is history, not current guidance. `/api/v1` is the only surface; no migration window remains.

Archived 2026-07-25: [`plans-archive/legacy-surface-removal-plan.md`](archived-documents/plans-archive/legacy-surface-removal-plan.md)
— API-5 / BACKLOG **D3** COMPLETE; as-built distilled into
[`okf/backend/control-plane/api-v1.md`](okf/backend/control-plane/api-v1.md). Retained as the record of
the **blast-radius and failure taxonomy** behind the migration.

---

**Last Updated**: 2026-07-25
