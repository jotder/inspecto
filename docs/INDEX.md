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
- [`EDITIONS.md`](EDITIONS.md) — edition model (Personal/Standard/Enterprise = build flavors, never branches) + the **feature × edition board** and the generated **Step Processors** table (`tools/render-processor-board.mjs` from `processor-catalog.contract.json`).
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
    API-stability policy, queries, jobs + Job Framework, the Job-vs-Step capability boundary,
    Platform Services & the plugin envelope, metadata
    bundle, multi-space), pipeline-graph
    (incl. the full design doc + the multi-location-ingest pattern, new 2026-08-11), components, config (incl. the configuration + parsing-options
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
- [`../compliance/`](../compliance/) — the NFR-7 compliance tree (repo root, shipped in the deploy
  bundle's docs). Today: [`controls-matrix.md`](../compliance/controls-matrix.md), the **C2 single
  mapping table** (SOC 2 TSC ↔ ISO 27001 Annex A ↔ NIST 800-53 → implementing file/route/gate →
  evidence → responsibility) — the ISO SoA and the FedRAMP customer-responsibility matrix are
  **exports of it**, never separate documents; its §4 is the consolidated gap list (G1–G10). Plus
  [`evidence/`](../compliance/evidence/) — the C3/C4 auditor runbooks:
  [`release-verification.md`](../compliance/evidence/release-verification.md) (`.sha256`/`.asc`),
  [`audit-log-extraction.md`](../compliance/evidence/audit-log-extraction.md) (CSV and JSON are both
  audit-complete since AUDIT-CSV-1 was fixed 2026-08-28),
  [`access-review.md`](../compliance/evidence/access-review.md) (G8 — role-level grants + the
  `/access/*` JSON; subject→role is IdP-owned by design),
  [`retention-configuration.md`](../compliance/evidence/retention-configuration.md) (G5 — the seven
  prune tasks; 🔴 the event store, AUDIT events included, has NO retention) and
  [`rto-rpo-statement.md`](../compliance/evidence/rto-rpo-statement.md) (G6 — targets are
  operator-fill; the drill table is the evidence),
  [`audit-record-protection.md`](../compliance/evidence/audit-record-protection.md) (AU-9 — what the
  audit store really guarantees, and the four things it does NOT) and
  [`air-gap-posture.md`](../compliance/evidence/air-gap-posture.md) (ISO 8.12 / NFR-4 — a
  **packaging** guarantee, not a runtime network control). Scanned by the vocabulary guard. Plan:
  [`superpower/compliance-certifications-plan.md`](superpower/compliance-certifications-plan.md).

## Stakeholder set (audience-targeted)

- [`stakeholders/`](stakeholders/README.md) — per-audience reading map: executive brief, product
  capabilities, technical architecture, operations guide, **testing guide** (added 2026-07-16).
- [`roadmap/`](roadmap/) — stakeholder overview, roadmap (Now/Next/Later), presentation decks.

> **Docs consolidation (2026-09-01), passes 1–2 — the Pipeline module.** Layer split: OKF owns
> as-built truth; `superpower/pipeline-spec.md` stays the active plan. **UI:**
> [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) (replaces
> `pipelines.md`, left as a pointer). **Backend:** the
> [`okf/backend/pipeline-graph/`](okf/backend/pipeline-graph/index.md) bundle is the module home —
> [`pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md) (the model,
> token voice; 2026-06 design-era prose archived),
> [`editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) (the former
> §16–§20), NEW [`execution-lanes.md`](okf/backend/pipeline-graph/execution-lanes.md) (the one
> owner of "which lanes run a pipeline") and NEW
> [`pipeline-config-keys.md`](okf/backend/pipeline-graph/pipeline-config-keys.md) (the key census:
> 42 read / 25 declared / 17 parser-only). Re-grounded the same day: `job-vs-step.md` (the
> PipelineNodeExecutor + packs reality), `ADVANCED_GUIDE.md` §5.3/§10 (retired flow-authoring
> surface removed, current route list), the Batch→Consignment prose sweep (code names; wire/DDL
> residuals stay BACKLOG §4), and `configuration.md`'s ghost `source:` block → `collector:`
> (COLLECTOR-ERRMSG-1 filed). This pattern is the template for the remaining modules.

## In-flight plans (`superpower/` — plans live here ONLY while active)

- [`superpower/parse-pane-redesign-plan.md`](superpower/parse-pane-redesign-plan.md) — **DECIDED
  2026-09-03, not started.** Delimited Parse pane: tabs → collapsible sections of single-row property
  edits with sample values (deletes the R9 hidden-panel hack), Sample | Parsed tabs, filename column,
  Files & metadata dissolved (Collection pointer → Collector, column metadata → Transformation,
  partitioning → Sink), Name/Description back on the pane via `renameSelected`.
- [`superpower/sql-transform-v1-plan.md`](superpower/sql-transform-v1-plan.md) — **DECIDED 2026-09-03,
  not started.** New `transform.sql` Step: one SELECT over the TYPED source (`FROM input`),
  `DESCRIBE`-derived output schema with editable target names, filter stays a separate Step (rejected
  rows preserved), audit-boundary WARNING. Typing stays declarative on Parse (the all-VARCHAR raw fact —
  see `sql-only-transform-feasibility.md`). v2 AST smart table + v3 macros parked; `json` extension
  under the seal needs a probe first. Supersedes `author-schema-1-plan.md` (archived same day).

- ~~`superpower/mock-backend-removal-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-31** →
  [`archived-documents/plans-archive/mock-backend-removal-plan.md`](archived-documents/plans-archive/mock-backend-removal-plan.md).
  The offline UI mock backend is gone (`f1553136`): `inspecto-ui/src/app/inspecto/mock/` (~22k LOC),
  `environment.offline.ts`, the `offline` build/serve configurations, `npm run start:offline`, the ten
  `environment.mock*` flags and every production branch on them. 🔴 Six **server-published contract JSONs**
  and the TS lift/lower lived under `mock/` by accident and were promoted out first
  (`inspecto/contracts/`, `modules/admin/pipelines/pipeline-editable.ts`, `inspecto/fixtures/`); six Java
  contract tests read those files **by path** and moved with them, as did the `.prettierignore` exemption
  that keeps them byte-identical to Jackson's output. The retired concept is
  [`archived-documents/retired-concepts/mock-backends.md`](archived-documents/retired-concepts/mock-backends.md).
  Still open: **MOCK-DEAD-COMPUTE-1** in `BACKLOG.md` (four dead in-browser compute blocks).

- [`archived-documents/plans-archive/authoring-residuals-plan.md`](archived-documents/plans-archive/authoring-residuals-plan.md) — **FULLY
  DRAINED 2026-09-02, ready to distill+archive**: R1/R2/R4/R5/R6 shipped 2026-09-01 (diagnostics
  codes+guidance incl. COLLECTOR-ERRMSG-1 · the pipeline bundle routes closing TRANSFER-ARCH-1 ·
  snapshot undo/redo · Open-dialog MRU+pins · Dataset-hop retry banner); **R3 mid-branch
  `steps:` + the bundle-UI migration shipped 2026-09-02** (as-built: `editable-round-trip.md`
  §19/§21, `pipeline-editor.md`). Only R7 (convert-to-composite) remains — a future sketch by
  design; two small rows R3 opened live in BACKLOG (RECIPE-SCOPE-1, MIDBRANCH-UI-1). Prior art:
  the sibling DataForge wiki (`C:\sandbox\incubetor\docs\wiki`).
- [`archived-documents/plans-archive/execution-residuals-plan.md`](archived-documents/plans-archive/execution-residuals-plan.md) — **DRAINED, INTEGRATED
  onto master and ARCHIVED 2026-09-02** (X3 `ca3e885c` · X2 `452fdc7a` · X1 `d54772f8`; as-built in
  `okf/backend/engine/db-layer.md` §3.5, `okf/backend/pipeline-graph/execution-lanes.md`,
  `okf/backend/pipeline-graph/step-park-drain.md`, `okf/backend/build-run/operations-reference.md`;
  X4/X5 sketches → BACKLOG §4 `EXECUTION-RESIDUALS-SKETCHES`); originally specified 2026-09-01: the stage-2 execution solution spec (X1 persistent retry queue —
  consignment-grain recommended · X2 cross-lane provenance link · X3 PARK-1(a) re-grounding ·
  X4 record-replay shape · X5 StepInfo envelope feeding Phase 7 · X6 consignment-identity already
  held), plus the R3 unblock verdict. The same-shift build package (default-on orphan gate · D-9
  finish via the ExecutionContext seam · `POST /jobs/runs/{runId}/replay`) is recorded there.
- [`superpower/gate-register.md`](superpower/gate-register.md) — the register of decision-gated
  rows: what each is waiting on and who owns the call.
- [`superpower/pipeline-waves-drain-plan.md`](superpower/pipeline-waves-drain-plan.md) — **IN FLIGHT
  2026-08-31**, the drain of `pipeline-spec.md`'s remaining waves. §1 records what grounding found the
  spec's own wave tables got wrong (three of six rows), §2 the work left and its single gate, **§3 the
  D-9 design pass** that satisfies D8's three conditions. ⛔ Records one thing deliberately NOT done:
  declaring `steps` in `ConfigSpecs` would shrink the key-coverage ratchet while leaving its own
  justification true, which is gaming the guard rather than closing the gap.
- [`superpower/pipeline-spec.md`](superpower/pipeline-spec.md) — **the single consolidated Pipeline
  specification** (2026-08-30), written as the basis for a redesign: vocabulary, config surface, the
  DERIVED graph, Step types and connection rules, execution, extension seams, transfer, and §10's
  grounded list of what is broken, §11's proposed token model, §12's wave plan, and **§13's ten
  decisions, taken 2026-08-31**. ✅ D1: finish the approved amendment
  (`elt-final-amendment-plan.md`, already ~90% shipped) and treat the token model as its next step.
  **Waves 0 and 1 DRAINED 2026-08-31 — 11 of 17 items**, and **Wave 2 re-grounded the same day**: rows
  **11** (the `steps:` authoring surface — the Recipe view's step cards ARE that editor) and **13**
  (fan-in, decided by D6) were already **CLOSED** and mis-recorded; rows **12** (an ordered-step editor
  for the post-sync chain) and **7** (node-type packs hot-load through an owner-keyed pack overlay) were
  **BUILT the same day**, and Wave 3's row **14** (D-9) is **DESIGNED**, satisfying D8's three
  conditions. **Row 1 (`Batch`→`Consignment`) SHIPPED 2026-08-31** (`ff33246a`) — one commit, 155 files, reactor
  unmoved at 3841/0/0/5. That leaves **one row: 15** (Phase 6's deletion half), and its block is now
  measured rather than inferred — D-2 needs a *flagged verification minor* to have shipped, and the
  newest tag `v3.12.0` predates the converter by ten weeks. **Not closable by code.** See [`superpower/pipeline-waves-drain-plan.md`](superpower/pipeline-waves-drain-plan.md). As-built facts from the drain live in
  [`okf/backend/control-plane/pipeline-related.md`](okf/backend/control-plane/pipeline-related.md)
  (gap 5) and [`okf/backend/control-plane/metadata-bundle.md`](okf/backend/control-plane/metadata-bundle.md)
  (gap 6); the one item it opened rather than closed is **BUNDLE-SCHEMA-1** in `BACKLOG.md` §6.

- [`superpower/completeness-kpi-plan.md`](superpower/completeness-kpi-plan.md) — **DECIDED
  2026-08-30~~, ready to build~~ — ⏸ **ON HOLD by operator 2026-08-30 (corrected 2026-09-01: this
  entry had drifted from the plan's own status)**: K4/K5 not to be picked up; two owed answers
  (processed-filename source · `{seq}` scope) block the wiring. The plan describes: a scheduled
  per-pipeline completeness KPI (gap/sequence analysis +
  file/record count deviation). 🔴 **This REPLACES Consignment §8 sealing and §11.4 `partition_state`,
  overriding §8's "no schedule anywhere" claim by explicit operator decision** — sealing is dropped,
  not deferred. 🔴 The operator's chosen count source (`CommitLog`) was REFUTED — it is per-batch with
  no day column; the KPI reads `consignment_outputs` instead. Slices K1–K5.

- [`superpower/open-dag-pipeline-design.md`](superpower/open-dag-pipeline-design.md) — **DESIGN, not
  scheduled** (operator direction, 2026-08-29): open the pipeline into a NiFi-style DAG — steps after the
  sink, and pluggable **authorable** steps. Grounded: most machinery exists (the graph executor already
  runs arbitrary topologies; `ParserPlugin` already serves its grammar; `PipelineNodeExecutor` shipped
  the execution half). 🔴 **Corrected the same day** once the operator described the dataflow model: the
  post-sync carrier is the **Consignment output registry**, not a piped relation — a step reads via
  `ProcessorContext.outputs()`/`read()` and what it emits is written AND REGISTERED back onto the same
  Consignment, so the next step sees it. Schema **propagates** through `TypeFlow`, never re-declared.
  ⛔ So the sink does NOT need to emit `DATA` — that framing solved the wrong problem. What is missing is
  **composition and authoring** (one processor per Job run, no DAG, not on the canvas) plus the closed
  `RecipeCompiler` verb switch and hardcoded `LOWERABLE` for plugin steps. Five stages; the open decision
  is whether a post-sync step may create an arbitrary table or must stay in the summary guardrail.

- [`superpower/step-workbench-design.md`](superpower/step-workbench-design.md) — **DESIGN, not
  scheduled** (2026-08-29): one Step editor where the author builds a query (named fields, functions,
  filter, grouping), sees the **generated SQL**, tests it on sample rows, and gets the **output schema
  derived** instead of restating it. 🔴 Grounded finding: this is mostly WIRING — `TypeFlow` (schema by
  `DESCRIBE`, no execution) and `RowShaper.fuse` are both written with **no production caller**, and the
  shipped preview already computes rows the UI discards. ⛔ `fuse` is deliberately NOT used (dead,
  untested, and only a perf win on a bounded sample). Five slices, S1+S2 deliver most of it.

- [`superpower/sql-only-transform-feasibility.md`](superpower/sql-only-transform-feasibility.md) —
  **ANALYSIS ONLY, nothing built or scheduled** (operator idea, 2026-08-29): drop the mapping and keep
  only SQL Map+Filter, with the Parquet schema taken from the query's resultset metadata. Verdict: the
  mechanism is **already built** (`TypeFlow` derives the output schema by `DESCRIBE` over the identical
  SELECT), but the strong form is refuted — 🔴 the raw relation is **deliberately all-VARCHAR**, so
  resultset metadata carries no types until something casts, and whatever casts *is* the mapping.
  Measured: `CREATE MACRO` works and survives the `SqlSandbox` seal, but duckdb_jdbc exposes **no**
  Java-side UDF API. Carries the four guarantees a declarative mapping buys (cast-failure audit,
  forgiving coercion, per-field metadata, the BACKWARD compatibility contract).

- ~~`superpower/source-timezone-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-29** →
  [`archived-documents/plans-archive/source-timezone-plan.md`](archived-documents/plans-archive/source-timezone-plan.md).
  Temporal columns carry a declared **source zone** and normalise to naive UTC (S1 engine + config
  `44ecef76`, S2 surfaces `dd02d377`), and the `TIMESTAMPTZ` host-zone trap is closed by refusal at
  config load. As-built + every finding: [`okf/backend/engine/duckdb.md`](okf/backend/engine/duckdb.md)
  §*The source time zone for temporal data*. Kept for its live DuckDB probe transcript (§1). Two open
  items only, both in `BACKLOG.md` §4: no `timezone_column` editor (by decision) and the
  `%z`-mixed-`COALESCE` latent defect.

- [`archived-documents/plans-archive/parser-plugin-framework.md`](archived-documents/plans-archive/parser-plugin-framework.md)
  — **ARCHIVED 2026-08-30, FULLY DELIVERED.** The operator's "E of ELT" framework: self-describing
  Parser plugins, grammar-driven, tree-capable preview. P1–P5 shipped 2026-07-30/31; the last gating
  item (the tree→segments ingest bridge) shipped 2026-08-30 (`8f7bee75`). Current behaviour lives in
  [`okf/backend/engine/parser-plugins.md`](okf/backend/engine/parser-plugins.md).
  🔴 **This row said "NOT APPROVED, NOTHING BUILT" from 2026-08-28 to 2026-08-30 — false on both counts**,
  while every deliverable (the OKF concept, `ParserRoutes`, `ParsersService`, the segments editor, the
  GLOSSARY *Grammar* entry) was already in the tree. The status was inferred from the plan file's missing
  header instead of from the code. **A plan with no status header has an UNKNOWN status, not an unbuilt
  one** — grep for its deliverables before writing a status into this index.
- [`superpower/deployment-topology-plan.md`](superpower/deployment-topology-plan.md) — **DRAFT for
  stakeholder review (2026-07-24), decision asks in §10 unsigned** (listed here 2026-08-28, same
  omission). Deployment offerings — topologies, security overlays, scaling/DR posture — plus the
  script/preflight workstreams. Phases 0–1 look like plain build work but ride the same unsigned §10
  decisions (container image, Postgres driver bundling, DuckDB cap default, OS matrix, RPO/RTO, IAM
  pairing), so nothing here is schedulable until those are answered.
- [`superpower/parser-field-tiers-interview-plan.md`](superpower/parser-field-tiers-interview-plan.md) —
  **READY TO RUN 2026-08-28**: the D13 observation-session kit (protocol, per-lane capture sheets,
  the grounded field inventory, and the pre-agreed analysis rule). D13 stays parked until the
  session is scheduled with a real onboarding user; the kit removes the preparation excuse.
- ~~`superpower/java-architecture-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-27** →
  [`archived-documents/plans-archive/java-architecture-plan.md`](archived-documents/plans-archive/java-architecture-plan.md).
  Phase 2 of the reorganization: coupling, module organization, functional style, dependency
  reduction. **All five operator decisions are settled** (#1 built, #3 built, #4 granted,
  #5 recommend-LEAVE, #2 moot) and **every reactor cycle is dispositioned**.
  **As-built lives in [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md)** — the
  cycle map, the whole-reactor census (850 files / 73 packages / six SCCs), and the LEAVE
  rationales. Reproduce any number with `superpower/assets/pkggraph.py` + `edgeholders.py`.
  **Shipped:** Track 1 (D2a `parse()` 801→280, Phase A `ReadModel`, D2b 4/4 LEAVE) · **C2 cut**
  (`cf48d335`) · **C1 cut** (`15205362`) · **agent SPIs narrowed to `ReadModel`** (`c23489da`,
  `CollectorService` fan-in **16 → 8**).
  ⭐ **The finding worth carrying forward:** the plan's ⛔ "cycle cuts BLOCKED, relocation needs a
  major-version decision" gate was a **false premise** — nothing after 3.x has ever been released,
  so `@PublicApi` marks *intent to publish, not exposure*. Policy of record:
  [`okf/backend/control-plane/api-stability.md`](okf/backend/control-plane/api-stability.md)
  §*Release baseline*. ⚠ Two claims the plan made about its own work were wrong and are corrected
  in place: C1 was **not** cuttable by relocation, and decision #1 cut **no** package edge.
  **Still open, carried to [`BACKLOG.md`](BACKLOG.md) §6:** ARCH-OPS-SCC (LEAVE) ·
  ARCH-F-CARVEOUT (LEAVE) · C3 is nine packages, not four.
  **Closed as grounded LEAVEs:** B (`ObjectService`), F (`PipelineConfig` broad split), S7
  (`CollectorService` wiring) — read their evidence before reopening.
- ~~`superpower/java-simplification-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-27**
  (`archived-documents/plans-archive/java-simplification-plan.md`). Phase 1: ~60 duplicated helper
  copies consolidated into `Values`/`SqlBuilder.quoteIdent`/`RouteErrors`/`SpiSlot`, `PipelineRoutes`
  (1677 lines) and `ConfigRoutes` split into seven cohesive route modules, `MaintenanceJob` 855→190.
  11 commits `d5791116`…`f211b455`, baseline 3630 → **3657/0/0/5**, zero behavior change. As-built
  distilled into [`okf/backend/control-plane/control-api.md`](okf/backend/control-plane/control-api.md)
  (route modules, `RouteErrors`, `SpiSlot`) and
  [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md) (the `inspecto-util` helper
  homes); residuals → `BACKLOG.md` §6 **JAVA-SIMP-1/2**.
- ~~`superpower/mid-branch-transforms-design.md`~~ — **BUILT and ARCHIVED 2026-09-02**
  ([archive copy](archived-documents/plans-archive/mid-branch-transforms-design.md), kept for the
  refusal-era decisions). Shipped as authoring-residuals **R3** (`ce2fe675`): a `route:` branch
  carries its own `steps:` sub-chain in the shared step grammar, flattened by `PipelineLift` and
  reversed by `PipelineEditable`. As-built:
  [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md)
  §19 + [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md);
  the one residual is **MIDBRANCH-UI-1** in `BACKLOG.md` (insert-into-branch affordance).
- ~~`superpower/d11-resource-caps-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-26**
  (`archived-documents/plans-archive/d11-resource-caps-plan.md`). Shipped BACKLOG **D11**: the measured
  resource pair `memory_limit=2GB` + `maxConcurrentRuns=4`, both on by default and both owned by the server
  configuration (`scheduler.toon` → `GET/PUT /system/scheduler`, UI at Settings ▸ Scheduler ▸ Resource caps).
  Six slices (`67bbf70e`), then the same-day review hardening (`8baa38ee` — the run bound's permit
  accounting broke on a live resize) and both follow-ups: the cap's **provenance seizure** (`dbac167d`) and
  the **served memory-limit grammar** + DuckDB's `80%` proportional form (`60ed94f6`). Nothing left open.
  As-built facts live in [`okf/backend/engine/duckdb.md`](okf/backend/engine/duckdb.md),
  [`okf/backend/engine/consignment-concurrency.md`](okf/backend/engine/consignment-concurrency.md) and
  [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md).
- ~~`superpower/scheduler-system-config-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-25**
  (`archived-documents/plans-archive/scheduler-system-config-plan.md`). Shipped in six commits
  (`1b872515` → `275bf764`): the `ConcurrencyBroker` four-layer hierarchy (per-Pipeline / per-space /
  per-server caps + a 1–3 priority share that cannot starve), `scheduler.toon` + `/system/scheduler`
  and `/settings/scheduler` with provenance and hot-apply, the Settings ▸ Scheduler page, cadence and
  intake globals live-tunable, per-Pipeline `trigger:` cadence and remote fetch parallelism made
  first-class, and S8 free-slot/throttle visibility. 🔴 The plan's own "retire `runPermits`" row was
  **refuted by the code** — it guards the pre-broker phase. As-built:
  [`okf/backend/engine/consignment-concurrency.md`](okf/backend/engine/consignment-concurrency.md);
  two operator decisions moved to `BACKLOG.md` §4.
- ~~`superpower/branch-aware-executor-arming-plan.md`~~ — **COMPLETE + ARCHIVED 2026-08-26 (late).**
  The largest open pipeline-graph piece (design §13 R3) closed in one day: `route:` pipelines ARM
  and execute on the ingest path via `BatchGraphRunner` at the `writeAndTrace` choke point, with
  output parity by shared code (the flat commit/audit tail is the SAME code, zero mirrors). Full
  enterprise reactor **3615/0/0/5** — the new baseline. As-built:
  [`okf/backend/engine/branch-aware-ingest.md`](okf/backend/engine/branch-aware-ingest.md);
  provenance: `archived-documents/plans-archive/branch-aware-executor-arming-plan.md`.
  **Extended 2026-08-29 (ELT Phase 6 slices A–C2, `52a10577`…`8e14ee7d`): the graph lane is no longer
  route-only** — it carries every non-route shape a pipeline can actually be armed in (one or many
  destinations, versioned reference stores, several writes per batch), proven by a two-lane
  output-parity diff. That was Phase 6's precondition, and it is MET; the phase's remaining half is
  deleting the legacy readers, which is release-gated (D-2).
- ~~`superpower/backend-hardening-plan.md`~~ — **COMPLETE + ARCHIVED 2026-08-26** (items 1–5 shipped
  `38c7a32d` 2026-08-25; optional item 6 → BACKLOG §6 PKG-3, trigger-gated). As-built facts distilled
  into [`okf/backend/control-plane/api-v1.md`](okf/backend/control-plane/api-v1.md); provenance in
  `archived-documents/plans-archive/backend-hardening-plan.md`.
- ~~`superpower/unpack-stage-plan.md`~~ — **COMPLETE + ARCHIVED 2026-08-26 evening.** Every phase and
  every §6 question shipped; the `logical_name` status-ledger column (step 4d) and Phase 6's UI half
  were the last two rows and both landed 2026-08-26. As-built lives in
  [`okf/backend/engine/unpack-stage.md`](okf/backend/engine/unpack-stage.md) — read that, not the
  archived plan, for how the stage behaves. Provenance:
  `archived-documents/plans-archive/unpack-stage-plan.md`. Pluggable
  decompression (ServiceLoader `DecompressorPlugin`) expanding archives/compressed inputs into
  ordinary candidates at `CollectorProcessor` BEFORE `ConsignmentPlanner.plan` — never mutating a
  Consignment; Archive verdict = a Run-level `unpack` ledger; per-file end status via recording
  non-survivors in the manifest (Phase 4); Collector-level placement + original↔actual filename
  tracking + extension-insensitive duplicate identity (`logicalName`, §2.3) confirmed by operator
  refinement same day. §6's four operator questions: Archive status vocabulary **ANSWERED 2026-08-26**
  · lineage grain **ANSWERED 2026-08-26 (entry name)** · Phase-4 appetite resolved by building it
  · data-extension allow-list/collision posture **ANSWERED 2026-08-26**. ⛔ The `.csv`/`.json`
  collision is INHERENT to the requirement, not a defect — narrow the list, never redesign the strip.
- ~~`superpower/canvas-ux-compaction-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-21**
  (`archived-documents/plans-archive/canvas-ux-compaction-plan.md`). S0–S6 all shipped the same day
  the operator answered D1–D9: the maximize-clipped-Apply P0 fixed as an overlay; the selection
  commands consolidated onto the toolbar; `NodeConfigDialog` retired (every canvas kind, enrichment
  included, configures in the Properties dock); selection/config converge; the parse loop reads as
  parse → see schema → edit → re-derive; and the generic `parser` migrates to the per-format drawer
  (grounded against the engine's `csv_settings`/`parsing:` merge first) — a parser is always
  format-specific by operator directive, the demo seed included. As-built:
  [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md).

- ~~`superpower/multiformat-parser-lanes-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-20**
  ([archive copy](archived-documents/plans-archive/multiformat-parser-lanes-plan.md)). Excel joins
  the parser family end to end (new `frontend: xlsx` on DuckDB `read_xlsx`; the `excel` extension
  is NOT statically linked — `ExcelExtension` loads it fail-closed in three layers, air-gap via
  `-Dduckdb.extension.dir`; `PARSER_XLSX` node type; binary `sample_b64` preview + B2 sniff; the
  tabbed *Sheet & range* drawer); JSON gains the two honest `read_json` knobs (array/auto only —
  probed: `ignore_errors` keeps a malformed record as an all-NULL row, `maximum_object_size` is
  clamped and pinned at assembly level) and the *Format & records* tabs; fixed length keeps its
  single-column + vectorized-substring engine (already shipped — the ask was UI) and gains the
  *Record layout* tabs with the slice table homed into tab 1. As-built:
  [okf/frontend/features/grammar-config.md](okf/frontend/features/grammar-config.md) +
  [okf/backend/config/parsing-options-reference.md](okf/backend/config/parsing-options-reference.md).

- ~~`superpower/delimited-grammar-properties-plan.md`~~ — **COMPLETE and ARCHIVED 2026-08-19**
  ([archive copy](archived-documents/plans-archive/delimited-grammar-properties-plan.md)). The
  delimited Grammar properties redesign, shipped same-day: B1 quote/escape/comment pass-through on
  both engines (escape defaults to the quote — RFC doubling); B2 preview-inferred `columnTypes`;
  B3 additive schema `synonym` + `raw.types`; B4 `filename_column` source-file lineage (ingest +
  wrap lanes; graph lane refused — data at rest has no source files); U1 the 4-tab editor surface
  (`AttributeSpec.tab`, panels `[hidden]`-mounted outside the MatTab bodies); U2 the ①–⑤ columns
  table with icon-only type menu + unique synonym; U3 Data-types Auto/Declared persisted as
  `raw.types`; U4 the Grammar CSV round-trip replacing Save-as-template (`GrammarTemplateDialog`
  deleted; Name/Description KEPT — the drawer is those nodes' only rename path); U5 drawer maximize.
  Part II: E1 one writer with truly optional partitioning (the `year=1900` sentinel retired for new
  writes), E3 the wrap-SPI canonized (`okf/backend/engine/ingest-wrap-spi.md`) + end-to-end contract
  test, E4 the ledger-contiguity stress pin (narrowed). **E2 REFUSED** — `SinkPartitions` records the
  deliberate two-contract split. Deferrals in BACKLOG §4. As-built:
  `okf/frontend/features/grammar-config.md` · `okf/backend/engine/ingest-wrap-spi.md` ·
  `okf/backend/config/parsing-options-reference.md`.

- ~~`superpower/java-codebase-review-sweep.md`~~ — **COMPLETE and ARCHIVED 2026-08-18**
  ([archive copy](archived-documents/plans-archive/java-codebase-review-sweep.md), kept for the
  per-file evidence base). The module-by-module Java review: 13 fixes in the first pass, then JAVA-1…6
  all closed in the second (orphaned Parquet generations on quarantine, the OIDC audience warning,
  `CircuitBreaker` eviction, the post-commit FAILED demotion, five duplication extractions, and the
  second-pass review itself — which found a data-scope bypass on five `ObjectRoutes` routes, a dedup
  predicate that silently swallowed Incidents, and an ungated `/components/schema` back door).
  **As-built:** [Java review coverage](okf/backend/modules/review-coverage.md) — what has been read
  line-by-line, what has not, and the defect classes worth hunting first.

- ~~`superpower/definition-surface-unification-plan.md`~~ — **SHIPPED END-TO-END and ARCHIVED 2026-08-16**
  ([archive copy](archived-documents/plans-archive/definition-surface-unification-plan.md), kept for the
  grounding and the per-slice as-built). Collapsed the onboarding wizard and the pipeline-editor dialogs
  onto one host: the `/pipelines` editor with a right-dock definition drawer. **All of P0–P7**: the drawer
  shell and five pure panes, the whole per-format parser family (P3a–P3d), schema/mapping (P4), the marker
  dedup fold (P5), and the host collapse (P6-a…P6-e) — **the wizard shell is deleted and its route is a
  redirect**. ⚠ `grammar-editor.dialog` is NOT retired: it still serves a **dangling** `use: grammar/<id>`,
  **binary fixed-width**, and any **generic `parser` node** — all deliberate keeps, and the last of them is
  why the sample thread does not reach every pipeline. As-built lives in
  [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) (the editor, incl. the per-tab
  sample thread), [`okf/frontend/features/onboarding.md`](okf/frontend/features/onboarding.md) (what
  onboarding still means), [`okf/backend/pipeline-graph/pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md)
  and [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md).

- ~~`superpower/grammar-templates-not-bindings-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-15**
  ([archive copy](archived-documents/plans-archive/grammar-templates-not-bindings-plan.md), kept for the
  grounding and the four as-built sections). Retired the live
  `use: grammar/<id>` binding: a Grammar component becomes a **template** you copy from, never bind to.
  Grew out of the definition-surface plan's P3a residual #2 and resolves that plan's P3a-vs-P3d
  contradiction in P3d's favour. ⚠ A considered **reversal of a shipped decision** (grammar-config
  unification, `ba8b87ce`) at GLOSSARY level. Cheap because **nothing in the repo binds a Grammar** —
  the sole component is an unreferenced one-line demo fixture, so no migration is owed. Engine
  deliberately unchanged: the `use: grammar/` form stays read-supported, just never authored.

- ~~`superpower/operational-db-ui-plan.md`~~ — **Stage 1 SHIPPED and ARCHIVED 2026-08-15**
  ([archive copy](archived-documents/plans-archive/operational-db-ui-plan.md), kept for the grounding).
  PG-1 Open 2, the UI half of the operational-database selection. ⚠ **All four clauses of the row's
  "workable shape" landed on something that did not exist** — no route exposed the selection,
  `ConnectionTester` is TCP-only, **no server-level config file is read at boot at all**, and there is
  no reload endpoint. Shipped as read + validate (`GET /system/operational-db`,
  `POST /system/operational-db/test`, Settings ▸ Operational database) with **no write path**:
  persistence would create a second declaration of the same fact beside `-D`, so Q1 chose read+validate
  as the END STATE — ⛔ Stage 2 is not an owed follow-on. As-built:
  [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) §5.0-a.

- ~~`superpower/domain-timezone-behaviour-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-15**
  ([archive copy](archived-documents/plans-archive/domain-timezone-behaviour-plan.md), kept for the
  grounding in §1-§2). The `meta.domain.timezone` behaviour half, whose validation half shipped
  2026-08-14. ⚠ **Grounding refuted the BACKLOG row's framing:** it conflated the **data** zone (what
  the timestamps mean — a domain note, whose real consumer is the consignment §5.6 event-time cut) with
  the **operations** zone (what the operator's schedule is expressed in), and `domain.timezone` is
  merged **last-non-blank-wins across every `*_meta.toon` in a space**, so promoting it would have made
  cron firing depend on file scan order. All 3 questions answered as recommended: the split shipped as
  `-Dops.timezone` (`OperationsZone`), which **dissolved the migration blocker** the row was gated on —
  unset ⇒ `systemDefault()`, so no existing schedule moved. As-built:
  [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) *The operations zone*.
- ~~`superpower/map-node-config-home-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-15**
  ([archive copy](archived-documents/plans-archive/map-node-config-home-plan.md), provenance only).
  AUTHOR-1 follow-on (a): a map node's authored `columns`/`rules` now have a flat home at
  `processing.map` instead of being dropped by `PipelineEditable.lower`. Decisions taken as
  recommended — new key (not relocating authoring to the parser node), **refuse** an authored `columns`
  next to a declared `processing.mapping_file`, hand-rolled validation. As-built in
  [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) §16.

- ~~`superpower/consignment-chain-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-13**
  ([archive copy](archived-documents/plans-archive/consignment-chain-plan.md), provenance only).
  S1–S7 all delivered; as-built in [`okf/backend/engine/consignment-status-flow.md`](okf/backend/engine/consignment-status-flow.md)
  (status/audit/gauge) and [`okf/backend/engine/ingestion.md`](okf/backend/engine/ingestion.md)
  (grouping + ordering). Open remainder in [`BACKLOG.md`](BACKLOG.md) §4.

- ~~`superpower/pipeline-multiplicity-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-11**
  ([archive copy](archived-documents/plans-archive/pipeline-multiplicity-plan.md), provenance only).
  A pipeline is now constrained by whether a Step *accepts its neighbours*, not by how many Steps of a
  kind exist. **Part A (A1–A6, all shipped):** the flat `*_pipeline.toon` holds an ordered `steps:` chain
  (`lower()` writes it only for a chain the singular keys cannot hold, so every existing file round-trips
  verbatim), all `MULTI_*` transform refusals are gone, `ILLEGAL_PAIRING` refuses a wiring-invalid
  neighbour pairing, and **every Stage-2 kind executes** — dedup, summarize (via `MeasureCompiler`) and
  join (LEFT JOIN through the `ReferenceResolver` seam). A5 was re-scoped to **at-rest** routing and
  completed: `output_store:` + `PipelineLift.stageTwo` + a `pipeline_config:` flow job run the chain over
  the landed store. As-built in [`okf/backend/engine/stage1-architecture.md`](okf/backend/engine/stage1-architecture.md)
  *Step 3*. **Part B (decided, not built):** multi-location acquisition is **composition**, not a
  `collector` list — as-built in
  [`okf/backend/pipeline-graph/multi-location-ingest.md`](okf/backend/pipeline-graph/multi-location-ingest.md).
  Residuals in `BACKLOG.md` §4 (the orphan-`output_store` audit finding; ingest-side `route:` demux and
  multi-sink arming still wait on the branch-aware executor).

- ~~`superpower/job-parameter-contract-plan.md`~~ — **SHIPPED and ARCHIVED 2026-08-10**
  ([archive copy](archived-documents/plans-archive/job-parameter-contract-plan.md), provenance only).
  All 17 steps: the Job authoring form is now generated **entirely** from a Job Type's declaration, and
  the `$`-expression vocabulary is a registry that plugins and Job Packs extend rather than a `switch`.
  As-built: [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) *§The parameter
  contract & runtime Expressions* + [`okf/frontend/features/jobs.md`](okf/frontend/features/jobs.md).
  Guiding principle held: **versatility over built-ins** — capability arrives by *registration*
  (`mail.send` is the reference generic type). Also settled here: **§0-A un-banned user-facing 'Job'**
  (reversing the ELT amendment's Phases 3/6 retirement, GLOSSARY §6-A/§13), **whole-value evaluation**
  as the answer to the `sql.template` `$` collision (§6.1 — the SQL body keeps its own namespace; a
  window binds by indirection), and **§5-B's two typed bounds** replacing the unconsumable `time_range`.
  ⚠ Grounding refuted several of its own premises — most consequentially that a job's write body had
  **always** been flat snake_case with unknown keys absorbed as parameters, so every UI-authored event
  trigger was silently inert until step 16. Deferrals (no CC on `mail.send`; INTEGER tokens unreachable
  from the UI because a native number input cannot display one) → [`BACKLOG.md`](BACKLOG.md) §4.

- [`superpower/gate-register.md`](superpower/gate-register.md) — **the gate register (2026-08-29)** —
  every item currently gated, blocked or restricted across BACKLOG, the active plans, the root canon +
  `compliance/` and the OKF tier, deduplicated and sorted by **who can actually lift the gate**. Three
  findings: much of what reads as blocked is doc-rot whose gate is already gone (§1, corrected in place
  that day); the real external gates collapse into **five clusters**, not fifty rows (§2); and the
  restriction register — write-root gate, PathJail, ExpressionGuard, SqlGuard, edition boundaries,
  air-gap posture, append-only registry — is reasoned design that must NOT be "resolved" (§5). ⚠ It
  records two of its own rows as wrong (Platform Services Stage 2's gate survives in a different shape;
  `DbAcquisitionLedger` was already fixed) — sweeps read struck-through annotations badly, so verify a
  row against the repo before acting on it. Retire this file once §2's clusters are answered.

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
  the gate seam is `ConfigWriteRoutes.writeConfig`; Mapping stays path-addressed in slice 1; the
  fixture count was wrong). Phase 1 slices 1–3 SHIPPED 2026-08-05: sibling `_mapping.csv` dual-read;
  split-write + BACKWARD compatibility gate at `ConfigWriteRoutes`; `schema`/`mapping` component kinds
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
  execution half of the gate is S3-blocked. **Phase 3: S1/S2/S4 shipped, S3 deferred.** **Phase 4 S4 (D-13, per-Step `enabled:` with durable park/drain) SHIPPED WHOLE 2026-08-28/29** across five slices — `cb12032d` · `9873ebfe` · `575c9912` · `bb7a3225` · `5f0d9637`; its slice plan is [archived](archived-documents/plans-archive/elt-s4-park-drain-plan.md) and the durable knowledge is the OKF concept [`okf/backend/pipeline-graph/step-park-drain.md`](okf/backend/pipeline-graph/step-park-drain.md). Next: the
  S3 design (operator call), Phase 6 (non-route lane parity) / Phase 7, or UI S1–S3.

- [`archived-documents/plans-archive/elt-amendment-ui-plan.md`](archived-documents/plans-archive/elt-amendment-ui-plan.md)
  — **ARCHIVED 2026-08-17** (S1–S6 + S7's summarize half shipped; only S7's table-entry half remains and
  it is triple-gated — no `dataset.write` Signal publisher, `PipelineConfigParser` still requires
  `dirs.poll`/`dirs.database` at parse time, and there is no `table` collector mode. Open item lives in
  `BACKLOG.md`.) **v1.0 (2026-08-05) — the amendment's unified UI plan** (companion to the above). One design call:
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
  [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md); the deliberate deferrals (D6
  timezone-governs-scheduling, D8 authorable `reference:` block, the agent-tool `flow` argument, bare-word
  `flow` identifiers) are in [`BACKLOG.md`](BACKLOG.md). What it left behind, all still live:
  the four-pass vocabulary guard (`tools/check-vocabulary.mjs` — docs, TOON keys, knowledge trees, Java/TS
  identifiers **and, since 2026-08-26, the operator-visible MESSAGES** — a 4xx body, a Signal message, an
  attribute description or a template label), `NodeConfigNameContractTest` (a declared key must reach its engine field), and
  `NodeAttributesContractTest` + the committed `node-attributes.contract.json` (the server publishes the node
  cfg vocabulary; client and server are byte-compared).

- **Consignment addressing — DELIVERED + ARCHIVED 2026-08-10.** The addressing layer (naming a set of
  Consignments as one relation) shipped steps 1–7 and 10 in a single stretch; step 8 is blocked on a scope
  decision and step 9 was **refuted**. As-built:
  [`okf/backend/engine/consignment-addressing.md`](okf/backend/engine/consignment-addressing.md) — the
  Selector **filters** a glob rather than replacing it (an optional catalog cannot be an existence oracle),
  event-time bounds per output file from a declaration each write path already holds, the batch-id revision
  model that lets a recompute stop overwriting in place, and the per-stream **Watermark**. Open items in
  [`BACKLOG.md`](BACKLOG.md) §4 *Consignment addressing*; the original proposal — wrong about the code in ten
  places, each carrying a correction box — is at
  [`archived-documents/plans-archive/consignment-addressing-plan.md`](archived-documents/plans-archive/consignment-addressing-plan.md).

- [`superpower/consignment-elt-architecture.md`](superpower/consignment-elt-architecture.md) —
  **IN FLIGHT (opened 2026-08-03) — three sections are now CLOSED, the rest is still design (2026-08-04).**
  **§11.3 `consignment_outputs`** is complete: `DbConsignmentOutputStore` behind
  `-Dconsignment.outputs.backend` (**default `duckdb` since 2026-08-10**; `none` still honoured), production callers on all three write paths — ingest
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

*(Path containment shipped and was archived 2026-08-14 — current knowledge lives in
[`okf/backend/config/config-safety.md`](okf/backend/config/config-safety.md).)*

- ~~`superpower/pipeline-build-test-run-gaps.md`~~ — **COMPLETE end-to-end 2026-08-14; plan ARCHIVED**
  to `archived-documents/plans-archive/`. Opened 2026-08-02. **Steps 0–4 shipped same day** (`4fe388a1`):
  the armed-pipeline silent failure closed (G4), the two 404ing test affordances gated off rather than
  deleted (G1), a `lowerable` signal added to the palette (G2), refusals routed to the Validation dock
  (G3), a grandfathered-flow warning banner (G5, not read-only as first drafted). **Step 5 — a bounded
  test run over real inbox files — shipped 2026-08-14 in three slices**: 5a real files (`1f0937ee`,
  `141caf84`), 5c route + ungate (`0b2a80ba`, `0c542829`), 5b stop-at-node cutoff (`4c99c12a`). A user
  can now test a pipeline against their own data, and run-to-here bounds the run where they asked.
  **Current knowledge → [`okf/backend/engine/pipeline-test-run.md`](okf/backend/engine/pipeline-test-run.md)**
  (containment, the `files` jail, the `to=` cutoff, the falsification probes) and
  [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md). The one residual — the node
  config dialog's `/components/*` "Test" affordance, still mock-gated because a node with inline config
  binds no registered component — is a [`BACKLOG.md`](BACKLOG.md) §Pipelines row.

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
- **`4.x` public-PKCE auth — ARCHIVED 2026-08-29, its purpose lapsed.** The plan existed to unblock the
  SEC-INCIDENT-1 rotation (`4.x` had to stop needing a client secret before the leaked secrets could be
  rotated); that incident is **closed by decommission** — the system no longer exists — so P2 has nothing
  to deploy to and Q1–Q4 (IdP PKCE/public-client support, refresh-token behaviour, which deployments are
  live) have no subject. P0/P1 shipped 2026-07-25 and stand on their own. Provenance:
  `archived-documents/plans-archive/4x-public-pkce-plan.md`; the incident record is `BACKLOG.md` §5 and
  `compliance/controls-matrix.md` CC6.1. The two incident-only runbooks moved with it:
  `archived-documents/secret-rotation-runbook.md`, `archived-documents/github-support-purge-request.md`.
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
  §2b, workstreams C1–C6. ⚠ "sequencing sign-off pending" was **stale** — §6 Q1 was answered
  2026-07-25 (parallel; SOC 2 is not a gate). **C2 shipped 2026-08-28** →
  [`../compliance/controls-matrix.md`](../compliance/controls-matrix.md); C1 stays org-gated (it
  needs facts the repo does not hold).
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
- ~~`superpower/pipeline-case-studies.md`~~ — **RETIRED 2026-08-20**
  ([archive copy](archived-documents/plans-archive/pipeline-case-studies.md)). Replaced by the
  `format-examples.seed.ts` pack (one pipeline per DuckDB-native parser frontend) — operator
  request, part of the multiformat parser-lanes work.
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

Archived 2026-08-10: [`plans-archive/platform-services-plan.md`](archived-documents/plans-archive/platform-services-plan.md)
— **Stage 1 COMPLETE (S1-0…S1-8, 2026-08-09/10)**: the `PlatformServices` seam, `requires:` grants
validated at registration, the whole v1 menu (`notifications` · `incidents` · `schema` ·
`consignment-status`) with the engine as first consumer, `sample.hello` migrated onto a grant with its
injection dropped, and the pack scaffolder + `PackTestHarness`. As-built distilled into
[`okf/backend/control-plane/platform-services.md`](okf/backend/control-plane/platform-services.md);
Stage 2 (open Step-kind registry, gated on the branch-aware executor) and Stage 3 (contributed
services) moved to [`BACKLOG.md`](BACKLOG.md) §4. Retained as the record of the D0–D8 decisions and
the S1/S2/S3 phasing.

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
