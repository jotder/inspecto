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
    API-stability policy, queries, jobs + Job Framework, metadata bundle, multi-space), pipeline-graph
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
