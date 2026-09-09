# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here is archived under
> [`archived-documents/`](archived-documents/) — kept for provenance, never maintained, never linked as
> current. When you add or retire a doc, update this index in the same change.
>
> **Structure (binding — see root `CLAUDE.md` "Documentation lifecycle"):** current knowledge lives in the
> **[OKF bundle](okf/index.md)**, the small root canon below, and the audience- and surface-specific trees
> [`stakeholders/`](stakeholders/README.md), [`api/`](api/README.md), `ui/`, `ops/`, `roadmap/`, `wiki/` and
> root `compliance/` · in-flight plans live in [`superpower/`](superpower/) · everything else is archive.
> ⚠ **Those six trees were missing from this sentence and from `CLAUDE.md` until 2026-09-09** (step 9),
> which left ~40 current documents inside no stated tier.
>
> **Consolidated 2026-09-07** — 879 lines → this. The page had become an archive log: 46 struck-through
> per-plan narratives (443 lines) for plans already in `plans-archive/`, sitting under a heading that says
> plans live here only while active, against 12 real ones. The narratives are frozen in
> [`archived-documents/index-snapshot-2026-09-07.md`](archived-documents/index-snapshot-2026-09-07.md);
> the part worth keeping — **which OKF concept each archived plan's truth went into** — is the table at the
> bottom. Same disease and same cure as the BACKLOG consolidation of 2026-09-06 (505 KB → 43 KB).

---

## Start here (session essentials, ~800 tokens)

- `CLAUDE.md` — project rules (vocabulary, doc lifecycle, skills/agents, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Root canon (durable, audience-facing)

- [`USER_GUIDE.md`](USER_GUIDE.md) — **end-user guide** to the web app: navigation, Spaces/Lens, every
  screen, shared UI elements.
- [`GLOSSARY.md`](GLOSSARY.md) — ⚠️ **canonical vocabulary, BINDING** — the single source of truth for every
  concept's name, the banned synonyms, and the UI→model→backend rename map (§13).
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from code
  or git: key decisions, gotchas, engine seams & perf, pointer map.
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — **requirements-of-record**: the full platform requirement set with
  MoSCoW, edition mapping, NFRs, sequencing. ⚠ [`EDITIONS.md`](EDITIONS.md) is authoritative for the Edition
  column; this table mirrors it.
- [`BACKLOG.md`](BACKLOG.md) — **the one board of open work**, grouped by what has to happen next: §0 priority
  · §1 operator decisions · §2 externally gated · §3 unbuilt features · §4 tech-debt · §5 docs · §6 standing
  refusals · §7 duplicate map. **Open work only** — closed rows are deleted, not struck through.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — every feature's TOON shape + where defined, examples,
  packaging, runnability. Pairs with the runnable suite in [`../inspecto/examples/`](../inspecto/examples).
- [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **Advanced Operations & Internals Guide** (the production
  investigation hub): per-component process, events, metrics, persisted state, `-D` flags, the full Control
  API, troubleshooting playbooks. **Living doc.**
- [`EDITIONS.md`](EDITIONS.md) — the edition model (Personal/Standard/Enterprise = build flavors, never
  branches) + the **feature × edition board** and the generated **Step Processors** table
  (`tools/render-processor-board.mjs` from `processor-catalog.contract.json` — edit the catalog, regenerate
  the table; do not hand-edit rows).
- [`BRANCHING.md`](BRANCHING.md) — branch & release policy (versions = branches; merge-forward; SemVer + CC).

## The knowledge bundle — OKF (current as-built truth)

The **one** [OKF](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md) bundle —
one concept per file, cross-linked, indexed by graphify. ⚠ It is a **constraint register, not a backlog**:
its invariants and traps are rules future work must not break, several recorded because the repo already
paid for violating them.

- [`okf/`](okf/index.md) — master index. **Five** parts since 2026-09-08:
  - [`okf/capabilities/`](okf/capabilities/index.md) — **the subject tier, new.** One document per
    capability, keyed to `REQUIREMENTS.md` §3's area IDs, answering *what was required · what is
    built · what is left · what was refused*. The four parts below answer only the second of those,
    and answer it by CODE LAYER — which is why "consignment" could appear in 48 current-tier docs
    with none of them being its entry point. Built: [`ACQ`](okf/capabilities/acquisition/acquisition.md) (the
    pilot), [`SEC`](okf/capabilities/security/security.md), [`INC`](okf/capabilities/incidents/incidents.md) and
    [`API`](okf/capabilities/control-api/control-api.md), [`SPC`](okf/capabilities/spaces/spaces.md),
    [`MET`](okf/capabilities/metamodel/metamodel.md), [`DAT`](okf/capabilities/data-plane/data-plane.md),
    [`ING`](okf/capabilities/ingestion/ingestion.md), [`OPS`](okf/capabilities/observability/observability.md),
    [`Studio`](okf/capabilities/studio/studio.md) (`BI`+`INV`),
    [`Assistant`](okf/capabilities/assistant/assistant.md) (`AGT`+`EOI`),
    [`Surfaces & Lenses`](okf/capabilities/surfaces/surfaces.md) (`UI`),
    [`CMP`](okf/capabilities/compliance/compliance.md),
    [`TOOL`](okf/capabilities/tooling/tooling.md),
    [`Editions & packaging`](okf/capabilities/editions/editions.md) (`PKG`),
    [`Pipeline authoring`](okf/capabilities/pipeline-authoring/pipeline-authoring.md) (`PIP-1`),
    [`Pipeline execution`](okf/capabilities/pipeline-execution/pipeline-execution.md) (`PIP-2`–`PIP-7`).
    ✅ **All seventeen slots are written** (2026-09-09); the area table is `GLOSSARY.md` §14.
  - [`okf/living-operational-system.md`](okf/living-operational-system.md) — the cross-cutting **north star**:
    seven cooperating networks over one metadata model, and what enforces each principle.
  - [`okf/frontend/`](okf/frontend/index.md) — the Angular console: architecture, conventions, the shared
    design system, the feature screens, services.
  - [`okf/backend/`](okf/backend/index.md) — the Java engine + control plane: engine, acquisition, control
    plane (`/api/v1`, API stability, queries, jobs, platform services, metadata bundle, multi-space),
    pipeline-graph, components, config, editions & security, agent, modules, build/run, gotchas.
  - [`okf/agentic/`](okf/agentic/index.md) — **eoiagent** (the embeddable agent framework, separate repo)
    distilled + the Inspecto integration seam.

## Contracts, runbooks, audits

- [`api/`](api/README.md) — **machine-readable v1 HTTP contract**: `openapi-v1.json` + canonical `examples/`,
  enforced by `ApiContractTest`; `schemas/` (metadata-bundle JSON Schema + samples).
- [`ops/`](ops/) — operational runbooks: backup/restore, UAT seeding, maintenance, secret rotation.
- [`ui/accessibility-audit.md`](ui/accessibility-audit.md) — the **living** inspecto-ui WCAG/a11y findings
  register (referenced by `okf/frontend/conventions/accessibility.md`).
- [`../compliance/`](../compliance/) — the NFR-7 compliance tree (repo root, shipped in the deploy bundle's
  docs). [`controls-matrix.md`](../compliance/controls-matrix.md) is the **C2 single mapping table** (SOC 2 TSC
  ↔ ISO 27001 Annex A ↔ NIST 800-53 → implementing file/route/gate → evidence → responsibility); the ISO SoA
  and the FedRAMP customer-responsibility matrix are **exports of it**, never separate documents, and its §4
  is the consolidated gap list (G1–G10). Plus [`evidence/`](../compliance/evidence/) — the C3/C4 auditor
  runbooks: release verification (`.sha256`/`.asc`), audit-log extraction, access review (G8),
  retention configuration (G5 — 🔴 the event store, AUDIT events included, has NO retention), the RTO/RPO
  statement (G6 — targets are operator-fill), audit-record protection (AU-9) and air-gap posture (a
  **packaging** guarantee, not a runtime network control). Scanned by the vocabulary guard.

## Stakeholder set (audience-targeted)

- [`stakeholders/`](stakeholders/README.md) — per-audience reading map: executive brief, product
  capabilities, technical architecture, operations guide, testing guide.
- [`roadmap/`](roadmap/) — stakeholder overview, roadmap (Now/Next/Later), presentation decks.

## In-flight plans (`superpower/` — a plan lives here ONLY while its work is active)

When the work ships: distil the durable as-built facts into the matching OKF concept, move still-open items
to [`BACKLOG.md`](BACKLOG.md), then `git mv` the plan to `archived-documents/plans-archive/` and add its row
to the table below — all in the same change.

| Plan | State | What is actually left |
|---|---|---|
| [`pipeline-spec.md`](superpower/pipeline-spec.md) | IN FLIGHT | The consolidated Pipeline specification (2026-08-30) + §12's wave plan and §13's ten decisions. **16 of 17 waves done**; only row 15 remains. ⚠ Widest inbound surface of any plan here — 7 other docs cite it. |
| [`pipeline-waves-drain-plan.md`](superpower/pipeline-waves-drain-plan.md) | IN FLIGHT | The drain of those waves; §3 is the D-9 design pass. Archive **together with `pipeline-spec.md`** when row 15 closes. |
| [`elt-final-amendment-plan.md`](superpower/elt-final-amendment-plan.md) | PARTLY SHIPPED | Phases 0–5 done and Phase 6's slices A–C2 shipped; the **Phase 6 deletion half is row 15**, release-gated (BACKLOG §2). D-8/D-11 are P3. |
| [`post-consolidation-sprints.md`](superpower/post-consolidation-sprints.md) | SPRINTS 1-2 DONE · 3 PART-DONE (3 of 6 classes) · 4 MOSTLY DONE (steps 5/8/9 ✓, 6 part) | Six sprints (= shifts) sequencing what the 17-spec consolidation left: the ~215 unfiled spec findings, the recurring failure CLASSES, and plan steps 5–9. Written 2026-09-09; archives when its last sprint closes. |
| [`docs-consolidation-plan.md`](superpower/docs-consolidation-plan.md) | IN FLIGHT — steps 1–6, 8, 9 DONE · **step 7 DONE 2026-09-09** | The 17-area documentation consolidation that produced the `okf/capabilities/` tier. Step 7 (dissolve `superpower/`) is discharged for five plans; what remains here is genuinely in flight. 🔴 **This row did not exist until 2026-09-09** — the 1,140-line plan driving the whole programme was absent from its own index, and `INDEX.md` was not mentioning it at all. Same failure class as the two missing rows found 2026-09-08. |
| [`edg-01-edition-gating-plan.md`](archived-documents/plans-archive/edg-01-edition-gating-plan.md) | **ARCHIVED 2026-09-08 — COMPLETE** | EDG-01: all six "not for Personal" cells are now true of the build (CP-15 · OPS-06 · CP-09 · SEC-10 · CP-13 both halves · CP-11). Baselines: Personal 23 modules / 3777 tests, Standard 31 / 4106, Enterprise 32 / 4126. As-built recipe (27 items) distilled to [editions model](okf/backend/editions/editions-model.md); ⚠ two other matrix rows had to be amended with cell 7 — `OPS-01` and `SP-CTL-02`. Nothing open. |
| [`design/record-transformer-review/`](superpower/design/record-transformer-review/) | WORKING FILES | Design-canvas sources for the Record Transformer review (2026-09-05). Re-seed from these; never edit the assembled `.html`. |
| [`assets/`](superpower/assets/) | TOOLING | `pkggraph.py` / `edgeholders.py` / `fanmatrix2.py` — reproduce any reactor or package metric quoted in `okf/backend/modules/reactor.md`. |

## Archived plans — where each one's truth went

⛔ **Never read an archived plan for current behaviour** — read the OKF concept it distilled into. The
archive keeps provenance (the grounding, the refuted premises, the per-slice as-built), and a plan with no
status header has an **unknown** status, not an unbuilt one: this index once called a fully-delivered plan
"NOT APPROVED, NOTHING BUILT" for two days because the status was inferred from a missing header instead of
from the code.

Full list: [`archived-documents/plans-archive/`](archived-documents/plans-archive/) (148 files). The 59
narrated here, newest first (⚠ 2026-09-08: two rows were missing — `record-transformer-replaces-map-plan`
and `path-containment-unification` had been archived with no row — and the count read 46 over 52 rows):

| Archived plan | Archived | Its truth now lives in |
|---|---|---|
| [`deployment-topology-plan`](archived-documents/plans-archive/deployment-topology-plan.md) | 2026-09-09 | [`okf/capabilities/editions/editions.md`](okf/capabilities/editions/editions.md) §3.9–§3.13 (the design as narrative) **and §3.14 (the six tables verbatim** — sizing, failure→tier response, the **signed D6 RPO/RTO service levels**, the nine remaining preflight rows, **VER-1…VER-12**, and the phase sequencing with the T4 promote order). ⚠ Three current-tier docs recorded "the plan stays in `superpower/`" — all amended, not silently contradicted; that unamended sentence is why an earlier archival attempt was reverted. Open: §3 P2 *Deployment topology gaps* · §2 *live validation* · `SPEC-DEPLOY-ROWS-1` (now fifteen items — `SCR-4` was missing from it). |
| [`compliance-certifications-plan`](archived-documents/plans-archive/compliance-certifications-plan.md) | 2026-09-09 | [`okf/capabilities/compliance/compliance.md`](okf/capabilities/compliance/compliance.md) §3.10 (**the C1–C6 definitions the matrix keys on**, SOC 2 Type I/II mechanics + the 6-month/3-month window, the HIPAA/PCI scope language, the declared `compliance/` layout) and §3.2 (the signed **Moderate** baseline) · [`compliance/controls-matrix.md`](../compliance/controls-matrix.md) (C2 itself). ⚠ **It was a DRAFT**: its superseded certification ladder, its "immutable" audit-log claim, an "axe CI" gate that exists in no workflow, three absent directories and three gaps asserted open are enumerated in its banner — ⛔ do not quote it as a decision of record. Open: `N1`–`N7` on §2. |
| [`parser-field-tiers-interview-plan`](archived-documents/plans-archive/parser-field-tiers-interview-plan.md) | 2026-09-09 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) §"The D13 field-tier session" (the two questions, the **pre-agreed analysis rule**, the deliverable, the staleness warning) · [`okf/capabilities/ingestion/ingestion.md`](okf/capabilities/ingestion/ingestion.md) (the lone hard default). ⚠ **Not a spent plan — an UNEXECUTED instrument.** The session is still owed and the kit is still runnable; archived by operator decision before it was run, against the plan's own final line. 🔴 Re-ground its inventory before use: it predates `d012f721`. Open: §2 *D13 parser field tiers*. |
| [`completeness-kpi-plan`](archived-documents/plans-archive/completeness-kpi-plan.md) | 2026-09-09 | [`okf/capabilities/observability/observability.md`](okf/capabilities/observability/observability.md) §3.9 (the design of record: both deviation bases, the two silent-zero traps, the rejected substrates, all four verify gates) · [`okf/capabilities/acquisition/acquisition.md`](okf/capabilities/acquisition/acquisition.md) (K2's two structural limits) · [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) §3.9 (the `dailyVolume` read). ⚠ The **work** stays on hold (operator, 2026-08-30) with no dated lift condition — the hold was never on the document. Open: §3 P2 · `KPI-UNKNOWN-1`. |
| [`agt-6-plan`](archived-documents/plans-archive/agt-6-plan.md) | 2026-09-09 | [`okf/frontend/features/inline-ai-authoring.md`](okf/frontend/features/inline-ai-authoring.md) (A1–A5 as-built, the five safety properties, the kpi-host gotchas) · [`okf/capabilities/assistant/assistant.md`](okf/capabilities/assistant/assistant.md) (the decisions incl. D3's amendment) · [`okf/backend/agent/embedded-intelligence.md`](okf/backend/agent/embedded-intelligence.md) (the two-`ToolSpec` trap) · [`stakeholders/PRODUCT_CAPABILITIES.md`](stakeholders/PRODUCT_CAPABILITIES.md) (the commercial framing, caveated). ⚠ **Its stated reason to stay was stale by 43 days** — the `kpi_report_builder` host shipped 2026-07-28. Open: AGT-6b (§2, gated upstream) · `AGT-SEGMENT-1` (§3). |
| [`step-workbench-s4-design`](archived-documents/plans-archive/step-workbench-s4-design.md) | 2026-09-07 | [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) §"The step workbench" |
| [`consignment-elt-architecture`](archived-documents/plans-archive/consignment-elt-architecture.md) | 2026-09-07 | [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) · [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) · [`okf/backend/engine/consignment-addressing.md`](okf/backend/engine/consignment-addressing.md) · [`okf/backend/engine/duckdb.md`](okf/backend/engine/duckdb.md) · [`okf/backend/engine/post-sync-step-chains.md`](okf/backend/engine/post-sync-step-chains.md) · [`okf/backend/engine/consignment-status-flow.md`](okf/backend/engine/consignment-status-flow.md) |
| [`gate-register`](archived-documents/plans-archive/gate-register.md) | 2026-09-07 | [`okf/index.md`](okf/index.md) |
| [`living-operational-system`](archived-documents/plans-archive/living-operational-system.md) | 2026-09-07 | [`okf/living-operational-system.md`](okf/living-operational-system.md) |
| [`open-dag-pipeline-design`](archived-documents/plans-archive/open-dag-pipeline-design.md) | 2026-09-06 | [`okf/backend/engine/post-sync-step-chains.md`](okf/backend/engine/post-sync-step-chains.md) |
| [`step-workbench-design`](archived-documents/plans-archive/step-workbench-design.md) | 2026-09-06 | [`okf/backend/engine/catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) |
| [`postgres-multi-user-plan`](archived-documents/plans-archive/postgres-multi-user-plan.md) | 2026-09-06 | — PARKED, `BACKLOG.md` §6 |
| [`geo-map-case-studies`](archived-documents/plans-archive/geo-map-case-studies.md) | 2026-09-06 | [`okf/frontend/features/geo-map.md`](okf/frontend/features/geo-map.md) |
| [`delete-transform-map-plan`](archived-documents/plans-archive/delete-transform-map-plan.md) | 2026-09-05 | [`okf/backend/engine/catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) · [`okf/frontend/features/schema-mapping-authoring.md`](okf/frontend/features/schema-mapping-authoring.md) |
| [`record-transformer-replaces-map-plan`](archived-documents/plans-archive/record-transformer-replaces-map-plan.md) | 2026-09-05 | [`okf/frontend/features/schema-mapping-authoring.md`](okf/frontend/features/schema-mapping-authoring.md) · [`okf/backend/engine/catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) · [`okf/backend/pipeline-graph/step-catalog.md`](okf/backend/pipeline-graph/step-catalog.md) |
| [`parse-pane-redesign-plan`](archived-documents/plans-archive/parse-pane-redesign-plan.md) | 2026-09-04 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) · [`okf/backend/engine/node-types.md`](okf/backend/engine/node-types.md) · [`okf/backend/pipeline-graph/pipeline-config-keys.md`](okf/backend/pipeline-graph/pipeline-config-keys.md) |
| [`sql-transform-v1-plan`](archived-documents/plans-archive/sql-transform-v1-plan.md) | 2026-09-04 | [`okf/frontend/features/schema-mapping-authoring.md`](okf/frontend/features/schema-mapping-authoring.md) · [`okf/backend/engine/catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) |
| [`sql-only-transform-feasibility`](archived-documents/plans-archive/sql-only-transform-feasibility.md) | 2026-09-04 | [`okf/backend/engine/catalog-vs-executors.md`](okf/backend/engine/catalog-vs-executors.md) |
| [`mid-branch-transforms-design`](archived-documents/plans-archive/mid-branch-transforms-design.md) | 2026-09-02 | [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) · [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) |
| [`execution-residuals-plan`](archived-documents/plans-archive/execution-residuals-plan.md) | 2026-09-02 | [`okf/backend/pipeline-graph/execution-lanes.md`](okf/backend/pipeline-graph/execution-lanes.md) · [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) |
| [`authoring-residuals-plan`](archived-documents/plans-archive/authoring-residuals-plan.md) | 2026-09-02 | [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) · [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) |
| [`mock-backend-removal-plan`](archived-documents/plans-archive/mock-backend-removal-plan.md) | 2026-08-31 | [`archived-documents/retired-concepts/mock-backends.md`](archived-documents/retired-concepts/mock-backends.md) |
| [`source-timezone-plan`](archived-documents/plans-archive/source-timezone-plan.md) | 2026-08-29 | [`okf/backend/engine/duckdb.md`](okf/backend/engine/duckdb.md) |
| [`java-architecture-plan`](archived-documents/plans-archive/java-architecture-plan.md) | 2026-08-27 | [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md) · [`okf/backend/control-plane/api-stability.md`](okf/backend/control-plane/api-stability.md) |
| [`java-simplification-plan`](archived-documents/plans-archive/java-simplification-plan.md) | 2026-08-27 | [`okf/backend/control-plane/control-api.md`](okf/backend/control-plane/control-api.md) · [`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md) |
| [`d11-resource-caps-plan`](archived-documents/plans-archive/d11-resource-caps-plan.md) | 2026-08-26 | [`okf/backend/engine/duckdb.md`](okf/backend/engine/duckdb.md) · [`okf/backend/engine/consignment-concurrency.md`](okf/backend/engine/consignment-concurrency.md) · [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) |
| [`branch-aware-executor-arming-plan`](archived-documents/plans-archive/branch-aware-executor-arming-plan.md) | 2026-08-26 | [`okf/backend/engine/branch-aware-ingest.md`](okf/backend/engine/branch-aware-ingest.md) |
| [`backend-hardening-plan`](archived-documents/plans-archive/backend-hardening-plan.md) | 2026-08-26 | [`okf/backend/control-plane/api-v1.md`](okf/backend/control-plane/api-v1.md) |
| [`unpack-stage-plan`](archived-documents/plans-archive/unpack-stage-plan.md) | 2026-08-26 | [`okf/backend/engine/unpack-stage.md`](okf/backend/engine/unpack-stage.md) |
| [`scheduler-system-config-plan`](archived-documents/plans-archive/scheduler-system-config-plan.md) | 2026-08-25 | [`okf/backend/engine/consignment-concurrency.md`](okf/backend/engine/consignment-concurrency.md) |
| [`canvas-ux-compaction-plan`](archived-documents/plans-archive/canvas-ux-compaction-plan.md) | 2026-08-21 | [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) |
| [`multiformat-parser-lanes-plan`](archived-documents/plans-archive/multiformat-parser-lanes-plan.md) | 2026-08-20 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) · [`okf/backend/config/parsing-options-reference.md`](okf/backend/config/parsing-options-reference.md) |
| [`pipeline-case-studies`](archived-documents/plans-archive/pipeline-case-studies.md) | 2026-08-20 | — RETIRED, replaced by the `format-examples.seed.ts` pack |
| [`delimited-grammar-properties-plan`](archived-documents/plans-archive/delimited-grammar-properties-plan.md) | 2026-08-19 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) · [`okf/backend/engine/ingest-wrap-spi.md`](okf/backend/engine/ingest-wrap-spi.md) · [`okf/backend/config/parsing-options-reference.md`](okf/backend/config/parsing-options-reference.md) |
| [`java-codebase-review-sweep`](archived-documents/plans-archive/java-codebase-review-sweep.md) | 2026-08-18 | [`okf/backend/modules/review-coverage.md`](okf/backend/modules/review-coverage.md) |
| [`definition-surface-unification-plan`](archived-documents/plans-archive/definition-surface-unification-plan.md) | 2026-08-16 | [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) · [`okf/frontend/features/onboarding.md`](okf/frontend/features/onboarding.md) · [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) |
| [`grammar-templates-not-bindings-plan`](archived-documents/plans-archive/grammar-templates-not-bindings-plan.md) | 2026-08-15 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) |
| [`operational-db-ui-plan`](archived-documents/plans-archive/operational-db-ui-plan.md) | 2026-08-15 | [`okf/backend/engine/db-layer.md`](okf/backend/engine/db-layer.md) |
| [`domain-timezone-behaviour-plan`](archived-documents/plans-archive/domain-timezone-behaviour-plan.md) | 2026-08-15 | [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) |
| [`map-node-config-home-plan`](archived-documents/plans-archive/map-node-config-home-plan.md) | 2026-08-15 | [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) |
| [`pipeline-build-test-run-gaps`](archived-documents/plans-archive/pipeline-build-test-run-gaps.md) | 2026-08-14 | [`okf/backend/engine/pipeline-test-run.md`](okf/backend/engine/pipeline-test-run.md) · [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) |
| [`path-containment-unification`](archived-documents/plans-archive/path-containment-unification.md) | 2026-08-14 | [`okf/backend/config/config-safety.md`](okf/backend/config/config-safety.md) |
| [`consignment-chain-plan`](archived-documents/plans-archive/consignment-chain-plan.md) | 2026-08-13 | [`okf/backend/engine/consignment-status-flow.md`](okf/backend/engine/consignment-status-flow.md) · [`okf/backend/engine/ingestion.md`](okf/backend/engine/ingestion.md) |
| [`pipeline-multiplicity-plan`](archived-documents/plans-archive/pipeline-multiplicity-plan.md) | 2026-08-11 | [`okf/backend/engine/stage1-architecture.md`](okf/backend/engine/stage1-architecture.md) · [`okf/backend/pipeline-graph/multi-location-ingest.md`](okf/backend/pipeline-graph/multi-location-ingest.md) |
| [`job-parameter-contract-plan`](archived-documents/plans-archive/job-parameter-contract-plan.md) | 2026-08-10 | [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) · [`okf/frontend/features/jobs.md`](okf/frontend/features/jobs.md) |
| [`grammar-config-unification`](archived-documents/plans-archive/grammar-config-unification.md) | 2026-08-04 | [`okf/frontend/features/grammar-config.md`](okf/frontend/features/grammar-config.md) |
| [`collector-config-unification`](archived-documents/plans-archive/collector-config-unification.md) | 2026-08-04 | [`okf/frontend/features/collector-config.md`](okf/frontend/features/collector-config.md) |
| [`vocabulary-and-config-contract-plan`](archived-documents/plans-archive/vocabulary-and-config-contract-plan.md) | 2026-08-04 | [`okf/frontend/features/pipeline-editor.md`](okf/frontend/features/pipeline-editor.md) · `tools/check-vocabulary.mjs` |
| [`sinks-config-format-plan`](archived-documents/plans-archive/sinks-config-format-plan.md) | 2026-08-02 | [`okf/backend/engine/output-sinks.md`](okf/backend/engine/output-sinks.md) |
| [`pipeline-rename-and-template-plan`](archived-documents/plans-archive/pipeline-rename-and-template-plan.md) | 2026-08-02 | [`okf/backend/control-plane/pipeline-identity.md`](okf/backend/control-plane/pipeline-identity.md) |
| [`onboarding-pipeline-unification`](archived-documents/plans-archive/onboarding-pipeline-unification.md) | 2026-08-01 | [`okf/backend/pipeline-graph/pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md) · [`okf/backend/pipeline-graph/editable-round-trip.md`](okf/backend/pipeline-graph/editable-round-trip.md) |
| [`link-analysis-projection-authoring-plan`](archived-documents/plans-archive/link-analysis-projection-authoring-plan.md) | 2026-07-27 | [`okf/frontend/features/link-analysis.md`](okf/frontend/features/link-analysis.md) |
| [`widget-tags-assignment-migration-plan`](archived-documents/plans-archive/widget-tags-assignment-migration-plan.md) | 2026-07-27 | [`okf/backend/control-plane/tags.md`](okf/backend/control-plane/tags.md) |
| [`mnt-14-incident-retention-plan`](archived-documents/plans-archive/mnt-14-incident-retention-plan.md) | 2026-07-27 | [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md) |
| [`delivery-status-webhooks-plan`](archived-documents/plans-archive/delivery-status-webhooks-plan.md) | 2026-07-26 | [`okf/backend/control-plane/events-metrics.md`](okf/backend/control-plane/events-metrics.md) |
| [`findings-spec-plan`](archived-documents/plans-archive/findings-spec-plan.md) | 2026-07-26 | [`okf/frontend/features/objects.md`](okf/frontend/features/objects.md) |
| [`generic-tags-plan`](archived-documents/plans-archive/generic-tags-plan.md) | 2026-07-26 | [`okf/backend/control-plane/tags.md`](okf/backend/control-plane/tags.md) |
| [`link-analysis-pattern-packs-plan`](archived-documents/plans-archive/link-analysis-pattern-packs-plan.md) | 2026-07-26 | [`okf/frontend/features/link-analysis.md`](okf/frontend/features/link-analysis.md) |
| [`parser-plugin-framework`](archived-documents/plans-archive/parser-plugin-framework.md) | 2026-08-30 | [`okf/backend/engine/parser-plugins.md`](okf/backend/engine/parser-plugins.md) |
