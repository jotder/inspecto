# Backlog — every OPEN item, one page

**Updated:** 2026-09-07 — §4–§7 re-grounded and drained (see each section's note).
**2026-09-06 — consolidated.** The previous page (505 KB, 3,288 lines, roughly half of its rows
already closed) is frozen as
[`archived-documents/backlog-snapshot-2026-09-06.md`](archived-documents/backlog-snapshot-2026-09-06.md);
every closed row's as-built narrative, commit SHAs and refuted premises live there and in git history.
That rewrite also folded in the open items that had been living outside this page: the gate register's
pending decisions and "simply unbuilt" list (§3/§4, 2026-08-29 — that register was itself archived
2026-09-07, its retirement trigger having fired), the remainders of every plan still in
`docs/superpower/`, and the last handoff's next steps.

**What this page is.** The single board of open work, grouped by what has to happen next. Each row is
one line of *what remains* plus a pointer to the document that owns the detail. It lists **open work
only** — nothing here is done.

**Rules of use.**
- A row's stated cause and severity are a **hypothesis** recorded when the row was filed. Ground it in the
  code before building; the archived snapshot records how often that grounding overturned a row.
- When an item ships: mark it in its *source* doc first (that stays authoritative), then **delete the row
  here**. No strikethrough as-built narrative — that is what the OKF concept docs and git history are for.
- New pending items discovered mid-shift get a row at handoff time (see the `handoff` skill). Keep the row
  to one line of remaining work; the detail goes to the owning OKF concept or plan.
- ⛔ marks a refusal or a precondition that must not be bypassed. 🔴 marks a live defect or a trap.
- §6 lists standing refusals so nobody re-files them. They are not work.

---

## 0. Priority order

**P1** = do next: a live defect, or decided work with user-visible value. **P2** = build after P1, or once its §1
decision lands. **P3** = demand-gated; build only when someone asks by name. Every §3/§4 row carries its rank.

**P1 is drained (2026-09-06).** The shift that consolidated this page took every P1 row: three were built
(join-reference and schema-drift refusals at save, the audit over every input, the CI examples smoke), three
were already shipped and only the row was stale (AUTHORING-WIDE-1, the torn multi-file read, the auditor
evidence note), and four were re-ranked because the row hid a gate — a design pass, an operator decision, or
a new dependency — not a build. The rule that fell out: **a P1 must name the file it changes.** A row that
cannot is a decision (§1) or a design (P2).

Do next, in order:
1. **NAME-DIRS-1** (§3) — the one P1 that names its file; decided 2026-09-06, filed 2026-09-07.
2. **Release notes for the next MAJOR** — keep appending (§2).
3. **Step Processor catalog** — pick a partial by name (§3).

## 1. Operator decisions pending

**None.** All 28 rows were decided on 2026-09-06 in one sitting; every answer is recorded in its owning doc (grep
`Decision 2026-09-06` / `Decided 2026-09-06` / `Ratified 2026-09-06`), and the work each unblocked is ranked below — P1 where it was
decided and buildable, §2 where it became an org action, §6 where the answer was "keep as designed". Three rows
turned out to be already answered by shipped code (`description`, `duplicate_check` owner, `engine: auto`) and one
half of a fourth (the id slug). New decisions get a row here at handoff time.

## 2. Externally gated

Nothing a shift can close from this checkout. Listed so the gate is named, not guessed.

| Item | Remains | Gate |
|---|---|---|
| **Row 15 — ELT Phase 6 deletion half** | Delete the legacy flat read path (amendment §6 step 4). The `-Dingest.lane=auto|graph|flat` flag exists (`ConsignmentIngestStrategy.admittedLift`, 2026-09-02); the verification minor must SHIP first. ⛔ Not closable by code; ⛔ do not start it on momentum. Then `withMappingContext` → `PipelineLift` comes due only if the graph lane executes the map node. The waves board is 16 of 17. Also absorbs RECORD-TRANSFORMER-1 (d): the ingest lane runs exactly one projection slot, so a second `transform.sql` cascades only once the graph lane carries ingest — not by wrapping the SELECT N times in `DataTransformer.materialize`, which would run chain Steps at ingest against the at-rest decision. | Release — newest tag `v3.12.0` is not an ancestor of `master`; D-2 verification window. `superpower/pipeline-waves-drain-plan.md` · `superpower/elt-final-amendment-plan.md` Phase 6 |
| **Release notes for the next MAJOR** | **Drafted 2026-09-06** in `okf/backend/control-plane/api-stability.md` §Release notes; append there with every further `feat!:`. Covers: three `@PublicApi` store abstract methods (MNT-14) · `-Dauth.oidc.tokenEndpoint` required (D15) · `DELETE /spaces/{id}?purge=true` 409 (D4) · dedup folded into acquisition, `transform.dedup.fingerprint` removed (`61dc8280`) · `$`-token evaluation, REJECTED + `$$` escape (`e8a8a755`, `8504b782`) · `consignment.outputs.backend=duckdb` default + `ReprocessCommand` refusal · `<pipeline>_<batchId>` sink naming (define a `retire_superseded` job) · blank `partitions[].column` fails the sink branch · artifacts `timeRange` → `event_time_min`/`event_time_max` · artifactId rename `file-processor-*` → `inspecto-*` (bundle name unchanged — a separate unmade decision) · `mail.send` "SUCCESS, nothing sent" · `transform.map` deleted (`42fa41fe`) · `NoteTargets` → `AnnotationKinds`. §11 token *runtime* model deferred to the same release (pipeline-spec D2). | The tag cut |
| **X5 cross-lane drill-down + StepInfo envelope** | One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence. | Major bump (pipeline-spec §13 D2). `okf/backend/pipeline-graph/execution-lanes.md` |
| **OPS-5 provenance conservation** | Live-feed soak only — no code left; feature built, off by default. | A live deployment. `docs/ops/provenance-conservation-verification.md` |
| **Deployment topology live validation** | T2/T3/T4 reference deployments, the D8 IAM pair, GAP-7 blueprints; G6 RTO/RPO statement still carries `<OPERATOR TO STATE>` placeholders and a drill table with zero rows; grammar-config live smoke. | A reference deployment. `superpower/deployment-topology-plan.md` · `docs/ops/backup-restore-runbook.md` |
| **Compliance program (NFR-7)** | C1 applicability statements, ISMS boundary, auditor engagement, pen test, incident-response + crypto policy content (C5), FedRAMP Moderate package + FIPS test leg (C6, demand-gated), Type II observation window, ISO 8.8 advisory-watch process. Repo-side remainder: customer verification runbook (G2 half), CI-evidence doc, G6 recorded restore drill, G8 (RBAC R5 evidence), G9 FIPS. | Org action + external parties. `superpower/compliance-certifications-plan.md` · `compliance/controls-matrix.md` §4 |
| **D13 parser field tiers** | Run the onboarding-observation session with the READY kit. Second question for the session: every `tier:'required'` field ships `required:false` validators — should "required" validate? ⛔ Explicitly NOT an engineering guess. The UI's attribute tiers stay a best guess until then. | A real onboarding user. `superpower/parser-field-tiers-interview-plan.md` |
| **SEC-INCIDENT-1 carry-forwards** | Incident CLOSED BY DECOMMISSION 2026-08-29. 🔴 Due on closure and NOT done: delete the off-repo pre-rewrite backup bundle (five cleartext secrets; retention condition lapsed). Also: one confirmation none of the five values was reused elsewhere; internal hostnames/IPs still published in-repo (lower severity). | Operator, off-repo. `compliance/controls-matrix.md` CC6.1 · `PROJECT_NOTES.md` |
| **EOI-7b** | Publish eoiagent `0.1.0` artifacts to a registry; CI rebuilds from tag meanwhile. | Infra call (cross-repo) |
| **AGT-6b multi-step agent graphs** | First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools. Blocked upstream: the eoiagent approval gate is synchronous per-call (nested gates deadlock) and there is no per-tool `DryRunProvider` seam. | Demand + eoiagent. `superpower/agt-6-plan.md` §4 |
| **AGT-5 `DryRunProvider` + `incident_explain`** | Per-tool preview seam on `PlatformBuilder` (lets inspecto drop its parallel `AgentApprovals` previewer); `incident_explain` waits on the eoiagent host seam. Local-models-only scope cut stands. | Cross-repo `jotder/inspect-agent`. `superpower/agt-6-plan.md` §4.2 G2 |
| **X-Actor full removal** | Remove the header path entirely (already rejected outright on Standard/Enterprise). | Client migration with the API-v1 sunset. `okf/backend/control-plane/api-v1.md` |
| **E1 Enterprise distributed tier / Stage-2 streaming** | Unscoped. | Demand |
| **SOC 2 Type II window** | Open the 6-month observation window now (decided 2026-09-06); the HIPAA/PCI framework choice stays deferred until a prospect is named. | Org action. `superpower/compliance-certifications-plan.md` §6 |
| **DATA-GOV-1 archive** | Move the real carrier corpus to an encrypted out-of-band archive on company storage with a fetch script; access held by the data-agreement owner. | Org action (data-agreement owner + ASN shift). `PROJECT_NOTES.md` §DATA-GOV-1 |
| **Completeness KPI hold** | Stays on hold: whether `{seq}` restarts per hour is a carrier fact not yet confirmed. | The carrier's feed spec. `superpower/completeness-kpi-plan.md` §2b |

## 3. Product features — decided or unblocked, simply unbuilt

Grouped by area. A row with lettered items keeps the letters of its source doc so the two stay aligned.

### Authoring (Parse / Transform / pipeline editor)

- **P2** · **WORKBENCH-S4** — the one-surface Step workbench. **Operator 2026-09-06: design now, build later** — the design is written and awaiting review: `superpower/step-workbench-s4-design.md` (three UI-only slices S4a field list+filter · S4b input strip · S4c summarize grouping; NO new endpoint — the canvas edges are the input-relation truth). Build only after the two open questions in its §5 are answered.

- **P2** · **AUTHORING-REDESIGN-1** — open letters (⚠ the old "(j)(l)(n2)(o) are in §1" clause was stale in all four: (o) is now the WORKBENCH-S4 row above, (l) and (n2) SHIPPED, (j) `engine: auto` was already answered by shipped code; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — 🔴 precondition: probe that the `json` extension loads on the SEALED `SqlSandbox` connection; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `BatchIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P1** · **NAME-DIRS-1** — the UI scaffold still derives every `dirs.*` from the **raw display name**, so a
  pipeline named `my order feed` gets paths with spaces in them. Decided 2026-09-06: `dirs.*` derive from the
  **slug id**, one identity for id, file and paths; existing pipelines are untouched (dirs are stored, not
  re-derived). Names the file it changes: `inspecto-ui/src/app/inspecto/component-model/pipeline-scaffold.ts`
  + its spec. *(Filed 2026-09-07 — `okf/backend/control-plane/pipeline-identity.md` §Decision 2026-09-06 has
  cited this row since the day it was decided, but the row was never created.)*
  → `okf/backend/control-plane/pipeline-identity.md`
- **P2** · **Step Processor catalog** — 121 processors: 34 delivered / **16 partial / 69 planned** (`transform.lookup` DELIVERED 2026-09-06). Each partial is a product decision (Kafka consumer, XPath grammar, drift report, profiler, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email/webhook sinks…) — pick one by name. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`
- **P2** · **P4 Test mapping on a generic `parser` node** — only reachable where the parse node is per-format. ⚠ The "blocked on §1 decision (l)" gate is **discharged** — (l) was decided and shipped 2026-09-06 (`okf/frontend/features/pipeline-editor.md`); re-scope this row before building. ⚠ Offline, non-`DIRECT` types show blank (mock has no SQL engine) — recorded. → `okf/frontend/features/pipeline-editor.md`
- **P2** · **`kpi_report_builder` host (AGT-6a)** — no viable host pane; a new surface, not an adoption. (Its `projection_author` ‘stale `columns.items`’ half was **fixed 2026-07-28** and the clause is retired.) This row is what keeps `superpower/agt-6-plan.md` out of the archive. → `superpower/agt-6-plan.md`
- **P2** · **Canonical-pipeline selective bundle export/import** — the metadata bundle's `authored-pipeline` kind still targets the RETIRED `*_flow.toon` `PipelineStore`; a canonical `*_pipeline.toon` transfers only via the datasource zip or the client-side stream-config bundle. Wanted: one selective export/import with dependency closure (schemas, per-segment schemas, grammar/enrichment companions, Connection as secret-free requirement) and retire/repoint the `authored-pipeline` kind. **Decided 2026-09-06 (operator): in bundle manifests `schema` = the REGISTRY id (`registry/schemas/<id>`); a pipeline-owned `<name>_schema.toon` (+ its `_mapping.csv`/`_structure.csv` siblings) travels under its own kind, not as `schema`.** Apply this in `BundleRoutes`/`transfer/bundle.ts` when the row is built → `okf/frontend/features/onboarding.md`

- **P3** · **AI drafting has no applicable component kind** — restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes them) or rework `SchemaEditorDialog`. No low-risk slice survives — design first. → `okf/frontend/features/inline-ai-authoring.md`
### Onboarding, Catalog, Parsing


- **P3** · **Unpack (11) absent codecs** — (xz/zstd need a new decompression library: a dependency sign-off, not a build) — xz and zstd have no plugin; `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) unhandled — arrival-completeness is a Collector question. ⚠ The UI cannot author the explicit empty-list `data_extensions[0]:` opt-out (schema-form `list` writes empty as `null`). 🔴 Stale `META-INF/services` "ORDER MATTERS" header. → `okf/backend/engine/unpack-stage.md`
- **P2** · **Onboarding (Stream/Reference)** — D5-ref: how a `delete` tombstone *enters* the reference store (reserved column? Decision Rule consequence?) — wait for a real delete-feed; D6-ref: within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed; optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⚠ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`
- **P2** · **Onboarding ↔ Pipeline unification W4/W5** — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets; import-time referential integrity (a missing connection is not caught until first poll). Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Parsing (Stage-1)** — ASN.1 grammar source: a reference to a stored schema module instead of pasted module text (also the prerequisite for a per-vendor transform config home); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
- **P3** · **Problem-files view: `logical_name`** — carry `logical_name` beside `origin` so a re-delivery groups to its earlier compression spelling. Additive; on demand. → `okf/backend/control-plane/control-api.md`

### Execution, Consignments, Pipeline graph


- **P2** · **Branch-aware executor residuals** — ((b) and (c) are design passes before code; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; (c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written; (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — only if overshoot is real; (g) `sinks:` follow-ups: per-sink `ducklake` block in flat `.toon`, decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` **shipped 2026-09-06** — `RouteArming` no longer refuses it.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md`
- **P2** · **Platform Services Stage 2 / 3** — (gated on the at-rest execution decision + the S2-2 spike) — Stage 2 open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog): needs the decision to execute an intervening node at rest (the `graphLaneCarries` boundary = Phase 6 precondition) plus the S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) before GA; ⛔ third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3 pack-contributed services (`ServiceProvider` SPI; collision fails the pack atomically; reference-tracked quiesce). `DatasetAccess` after the Consignment Selector. No Job-side watchdog (R1) — a hanging Job is a recorded gap. Filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`
- **P2** · **Consignment addressing** — (torn multi-file reads: CLOSED 2026-08-29 by the pinned `ConsignmentSelector` list — this row said "open" for a week; the two readers that still re-globbed, `DbBrowserRoutes.browseStore` and `ExpectationEvaluator`, were pinned 2026-09-06) `generation` is a dead field (always 0, never read); ⚠ `retire_superseded` must be configured or every full recompute leaves a complete extra copy on disk; ingest-side Consignment-scoped accessor waits for a consumer; ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — X4 record-level replay from quarantine: sidecar error manifests (offset/reason), all-or-nothing vs eject-and-continue as per-pipeline CONFIG — ⛔ no build without a driver (same item as the run-detail "reprocess is whole-batch only" note). X1 deferrals: per-pipeline `processing.retry` block (regenerate node-attributes + step-types contracts); operator cancel / retry-now affordance (today: delete the sidecar under `<status_dir>/retries/`, or `reprocess`). → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`
- **P2** · **Pipeline graph** — flip the intake cap on by default (needs a soak); a pre-materialise cap to save remote-fetch bandwidth (cap applies post-dedup); ⚠ four kinds still last-one-wins, deliberately out of A2 scope: `acquisition`, `parser`, `gap`, `dedup.marker`; ⛔ `BatchGraphRunner` has zero production callers, blocked on ingest output parity (Phase 6) — do not discharge by wiring `engages()`; → `okf/backend/pipeline-graph/pipeline-graph-design.md` §14
- **P2** · **Consignment ELT** — `generation` is on the registry but compaction does not stage generations; `run_id` is `null` everywhere; §7.4 rollup cache deliberately unbuilt until read-time aggregation is measurably slow; §7.3 unpartitioned fallback stands by operator call — revisit if flat summary targets appear. → `okf/backend/engine/db-layer.md` §3.9
- **P2** · **Completeness KPI (when the hold lifts)** — K2 wiring (`FileSequenceGaps` analysis shipped `14c6ef0e`, wiring not built, needs `SeqScope`); K4 `kpi.completeness` job type (`JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, one config per pipeline, signal + deduped Incident on breach, must refuse loudly when `-Dconsignment.outputs.backend=none`); K5 mark the consignment-architecture §8/§11.4 sealing sections superseded everywhere. Non-blocking: signal type naming `kpi.completeness.evaluated`/`.breached` (⚠ do not grow the `EventType` enum), K3 baseline-window default as a job parameter. ⚠ `VolumeBaseline`/`FileSequenceGaps` have no production caller today. → `superpower/completeness-kpi-plan.md`
- **P3** · **D-8 XLSX export** — zero groundwork (no spreadsheet library in any pom); gated only by a bare label — state the operator question before answering it. → `superpower/elt-final-amendment-plan.md` §9 D-8
- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises. → `superpower/elt-final-amendment-plan.md` §3.4

### Control plane, jobs, notifications, queries

- **P2** · **API v1** — adopt the cursor-pagination seam on further list families as demanded (4 adopters live); adopt `ETags.respond` on further singleton reads as demanded; Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed). → `okf/backend/control-plane/api-v1.md`
- **P2** · **Bundle / Exchange** — `requires` present-but-different classification; per-editor "load as draft" import — design first, likely multi-session (`BundleTransferService.write` commits straight through; no generic draft seam). ⛔ Do not fake it with a cross-kind `enabled:false` stamp. → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **Notifications** — D8 residuals: soft-bounce retry scheduling (distinction recorded, nothing retries); SES/SNS adapter (needs SNS subscription confirmation + a cert-chain fetch from a validated `amazonaws.com` URL — ⚠ outbound fetch from an unauthenticated callback path deserves its own review); GeoIP; auth-gated per-user prefs / security triggers. (Auto-disable policy is a §1 decision.) → `okf/backend/control-plane/events-metrics.md`
- **P2** · **EDG-01 edition gating** — modules or `-D` capability switches with the 503-panel UI for SEC-08 + CMP-08 (Enterprise-only) and OPS-05/OPS-06/SEC-10/CP-09/CP-11/CP-13/CP-15 (not Personal) — all core code today. Deliver cell-wise; a scheduled cell cites its row. → `EDITIONS.md` §Feature × edition matrix

- **P3** · **Job framework** — Maintenance COULD tier: space-to-space comparison; predictive maintenance (AGT-5 territory) deliberately deferred. → `okf/backend/control-plane/jobs.md`
- **P3** · **D6 spec-authoring UI** — a matrix/editor for `findings-spec`; today authored as TOON through generic `/components` CRUD. Nothing broken without it. → `okf/frontend/features/objects.md`
- **P3** · **Signal / Decision networks** — optional S8 (connector-direct emission + cross-space controller); a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet). → `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md`
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P3** · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`
### Deployment & packaging

- **P3** · **D8-SUPPRESS-1** — per-recipient suppression list (TTL for hard bounces, permanent for complaints) — gated on a DB-backed `DeliveryReceiptStore`. → `okf/backend/control-plane/events-metrics.md` §Decision

- **P2** · **Deployment topology gaps** (after §1 D1–D8 are signed) — GAP-2 Enterprise packaging (SCR-8) · GAP-3 service wrappers (SCR-3) · GAP-4 DuckDB cap default · GAP-5 T15 surge admission · GAP-6 Vault/KMS (SEC-8) · GAP-8 Postgres driver · GAP-9 launcher token-line debris (SCR-9) · GAP-10 bundle missing 13 archived docs (SCR-10). Phases 0–5 all unbuilt. → `superpower/deployment-topology-plan.md` §11
- **P3** · **Postgres multi-user** — ⛔ **PARKED by §6** until a multi-operator install exists; the old "(after the §1 decision)" heading outlived its decision, which was *park it*. Kept for the shape when it lifts: P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing); P3 **schema**-per-space URL wiring (NOT db-per-space); P4 `CaseStore` interface + PG impl (JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores + a concurrency test. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6

## 4. Engineering / tech-debt

*(Drained 2026-09-07. Three of the five rows here were standing refusals wearing a tech-debt label — a
"LEAVE unless someone is already in the file" is not work — and moved to §6. A fourth was already closed by
a test that post-dates it. What is left is one release-gated wire change.)*

- **P3** · **Vocabulary rollout, Tier 3 — the release-gated remainder.** The **UI half SHIPPED 2026-09-07**:
  every emitter was verified to dual-emit (`LineageRoutes:112/113`, `ViewRoutes:100/101`,
  `PipelineProjection:198/207/242`), so the DTO fields now read the canonical key — `DownstreamPipeline.pipeline`,
  `PipelineViewSummary.pipeline`, `CombinedNode.pipeline` / `CombinedEdge.pipeline` / `PipelineCombined.pipelines`.
  What remains is **wire and needs the MAJOR** (§2 release notes): dropping `flow` from the Java JSON
  (`ViewDefinition.toMap:52`, `LineageRoutes:113`, `PipelineProjection:199/208/243`) and renaming the
  `ViewDefinition.flow` record component, which carries `@PublicApi(since="4.0.0")` — GLOSSARY §13 already
  puts an `@PublicApi` member rename at Tier 2 as "a tier boundary on its own". ⛔ Do **not** rename the
  `"flow"` test literals: `ViewStoreTest:66/71` and `ControlApiViewsTest:98` are the only proof the dual-read
  compatibility path works, and `PipelineJobRunnerTest`/`JobServiceTest` cover the legacy job param. The
  agent-tool `flow` argument stays until it dual-accepts (`SOURCE_ALLOW` records it as tracked debt).
  ⚠ When the dual-emit ends, delete `CONFIG_ALLOW['spaces/ucc/config/views/sites_active_view.toon::flow-key']`
  — the guard's stale-allowlist rule fails the build until you do, which is the rename announcing itself.
  → `GLOSSARY.md` §13 · `PROJECT_NOTES.md`

## 5. Docs & hygiene

- **Doc-lifecycle violations** (shipped work still in `docs/superpower/`; the rule is distil → `git mv` to
  `plans-archive/` → update `INDEX.md`). Re-grounded 2026-09-07 — **two of the four listed rows were wrong**:
  - `living-operational-system.md` — **genuine, still open.** R1–R6 all shipped 2026-07-06. Distil §1/§2/§3/§7
    (the thesis, the seven-network map, the Component-kind coverage map, the principle→enforcement table) into
    `okf/backend/architecture.md`; there is no OKF concept for it today. 🔴 Re-ground §5's as-built first — it
    cites `inspecto/mock/signals.ts` and `inspecto/mock/decision.ts`, both **deleted** with the mock backend.
    ⚠ Cited by 4 other docs (`GLOSSARY.md` §Signal, `REQUIREMENTS.md` ×2, this page) — repoint in the same change.
  - `consignment-elt-architecture.md` — **newly identified, open.** Its own header says "nothing in the plan has
    code waiting on it any more"; the built parts are distilled into `okf/backend/engine/db-layer.md` §3.9.
    ⛔ Do §3's completeness-KPI **K5** first: §8 carries its SUPERSEDED banner but §11.4's heading does not.
  - ~~`compliance-certifications-plan.md`~~ — **NOT a violation.** Only C2 of six workstreams is delivered; C1/C3/C5/C6
    are open and org-gated (§2). It stays live; `INDEX.md` already records the C2 half correctly.
  - ~~`step-workbench-design.md`~~ — **was already archived 2026-09-06.** Row was doubly stale (file moved; decision made).
  - `pipeline-spec.md` + `pipeline-waves-drain-plan.md` — correctly live; archive together when Row 15 closes (§2).
  - ✅ `gate-register.md` — **ARCHIVED 2026-09-07.** Its own retirement trigger had fired and it had become
    actively misleading (§3.5 and §3.3 still framed items resolved weeks earlier as open calls). Its one durable
    note is now `okf/index.md` §*How to read this tier*.
- 🔴 **`INDEX.md` has become an archive log, not a map** — 879 lines, of which **44 are struck-through tombstones**
  for plans already in `plans-archive/`, sitting under a heading that reads "plans live here ONLY while active",
  against just **12 real plan files**. This is the same failure the 2026-09-06 BACKLOG consolidation cured
  (505 KB → 43 KB). Wanted: freeze the current page as an archived snapshot, then rewrite INDEX as a true
  curated map (root canon · OKF · contracts · genuinely in-flight plans · one pointer to `plans-archive/`).
  ⚠ **Trap**: `DOC_ALLOW['docs/INDEX.md::bare-flow']` protects exactly one line (the Batch→Consignment
  "largest blast radius" claim, whose *517 Java files* figure is itself stale — the Tier-3 sweep took it to 166).
  Delete that waiver in the same change or the guard's stale-allowlist rule fails the build.
- **REQUIREMENTS MoSCoW / edition columns** — §3.1 ACQ-4, §3.9 SPC-5 and §3.15 UI-8 were **fixed 2026-09-07**
  (all three were contradicted by their own §5 and by the code; UI-8 had read "IN-FLIGHT, uncommitted, another
  session" for two months over a pane that shipped 2026-07-07). An authority note now says `EDITIONS.md`'s matrix
  wins for the Edition column. Still open, same root cause — the column predates the 2026-09-02 "not for Personal"
  decisions: **SEC-8** (says PARTIAL/`S/E`; EDITIONS SEC-07 says shipped 2026-09-06 with a three-edition split),
  **OPS-2**, **INC-3**, **INV-2** (all say `All`; EDITIONS gates them Standard+), and **DAT-6** wants a caveat that
  the multi-user half is unbuilt. ⚠ ACQ-4's *other* half is unresolved and needs grounding, not a doc edit:
  EDITIONS' generated board marks `SP-ACQ-06`/`SP-ACQ-08` (S3/GCS) planned while the **connectors** ship with tests
  — check whether the *Step processor* exists before flipping `ProcessorCatalog`, because a connector is not a Step.
  → `REQUIREMENTS.md` · `EDITIONS.md`
- **Template seed-pack enrichment (frontend C7)** — ongoing, not a discrete item: `kpi-overview`,
  `quality-monitor`, `trend-monitor` today. → `okf/frontend/features/studio.md`
- **GRAPHIFY-1 tool sync** — ⚠ the row's own check is blind: `.graphify_version` and `graphify --version` both
  read `0.9.53` while `.claude/skills/graphify/SKILL.md` differs from the installed package's copy by ~300 lines
  (the repo copy carries a uv/pipx detection block labelled "fixes #831" that 0.9.53 does not). Either re-sync from
  the package or record why the repo copy deliberately diverges — comparing version markers will never tell you.
  (The optional `graphifyy[sql]` half is **already satisfied** — `tree-sitter-sql 0.3.11` is installed.)

## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger.
*(Triggers audited 2026-09-07 — every countable one was recounted against the code; none had fired.)*

- **ARCH-OPS-SCC** LEAVE (85-file ripple — recounted 2026-09-07, still **exactly** 85) · **ARCH-F-CARVEOUT**
  LEAVE (148 refs / 28 files — recounted, 27 files, module split unchanged) · `{etl, etl.unpack}` and
  `{agent.kernel.*}` SCCs LEAVE · intra-module `ops↔ops.link/workflow`, `catalog↔catalog.spi` cycles are
  same-family · **M2** `CollectorService` decomposition → `okf/backend/modules/reactor.md`
- **C2** store-pair base — reopen at the **7th** store; recounted 2026-09-07: **4 true `InMemory*`/`Db*` pairs**
  (Object, Link, Note, TagAssignment), 5 counting `DbStatusStore` by shape. Not close. · **C4** BOM — reopen on
  an external consumer; there is none and **nothing is published as a Maven artifact** (releases ship zip
  bundles; eoiagent is an upstream dependency, not a consumer). · **C6** connection reuse — warm open **24 ms**
  (min 23 / max 27, n=20), no contradicting measurement exists. 🔴 These three are **not** in `reactor.md`,
  which this page pointed at — they survive only in
  `archived-documents/plans-archive/modularization-optimization-plan.md` and the 2026-09-06 snapshot. They were
  archived without being distilled into an OKF concept; that is the real residual here.
- **PATH-2 residual** (moved from §4 2026-09-07 — it is a LEAVE, not work) — the `BackupTask.restore` zip-slip
  jail is PINNED by `MaintenanceLibraryTest.restoreRefusesAnArchiveEntryThatEscapesTheTargetBeforeWritingAnything`
  (the page cited a `…ASidecarEntry…` variant that does not exist — a tampered sidecar is refused a layer
  earlier). Family (a), the three store `fileFor` helpers — `ViewStore:100`, `PipelineStore:101`,
  `ComponentStore:351`, none importing `PathJail` — LEAVE unless someone is in those files anyway; their line
  numbers have now drifted three times, which is itself the argument. ⛔ Routing `ControlApi.serveStatic:866`
  through `PathJail.contains` is a posture change needing an operator call — grounded 2026-08-26 "do not build it".
  → `okf/backend/config/config-safety.md`
- **Unpack (10) crash mid-archive** (moved from §4) — re-ingests committed members; relies on
  `OVERWRITE_OR_IGNORE` idempotence (`PartitionWriter:186`, documented at `UnpackOrigins:32` and
  `ConsignmentIngestor:284/508`). By design; revisit only with a measured cost. ⚠ X1's `CommitRetry` does **not**
  cover this — it records only a *returned* FAILED, and a crash writes no attempt record.
  → `okf/backend/engine/unpack-stage.md`
- **`inspecto-query`/`inspecto-job`/`inspecto-enrich` module extraction** (moved from §4; the row said
  `fp-*`, stale since the artifactIds became `inspecto-*`) — build only on explicit request. Measured
  2026-09-07: `job` → `signal` + `ops`, but **`query` → `signal` only** and **`enrich` → neither**, so the
  old "`query`/`job` depend on `signal` + `ops`" claim was over-broad and `enrich` is the one clean
  candidate. `SharedDottedPathGrammarTest` still up-imports `notify` and would still need cutting.
  → `okf/backend/modules/reactor.md`
- **Vocabulary: the living-system terms are ADOPTED, not proposed** (row corrected 2026-09-07) — `GLOSSARY.md`
  already carries **Signal**, **Consequence**, **Decision Engine** and **Result Set** as binding (the last three
  annotated "§6-proposed → binding (R5)"). Only *Query* and *Parameter* were never formally adopted; use them
  as ordinary words, not as capitalized concepts. → `superpower/living-operational-system.md` §6
- **PKG-5** agent-absent is the intended shipped default; ⛔ no `package.ps1` switch until the JDK 25+ vs Java 24+ floor is resolved → `okf/backend/build-run/build-test.md`
- **D11 caps** — `max_temp_directory_size` gets no default; preview/dry-run connections stay uncapped; the semaphore-computed cap is rejected → `okf/backend/engine/duckdb.md`
- **D7 startup backfill** full object scan (`ObjectService.backfillTagAssignments:479`, called from
  `CollectorService:475`) — deliberately unfixed. ⚠ **The stated trigger cannot fire as written**: "shows up in
  measured startup time", but nothing in the repo measures startup — no JMH, no timing test, no recorded
  baseline. Reopening it means *first* adding a startup measurement. → `okf/backend/control-plane/tags.md`
- **MNT-14** — no UI surface / no shipped Job instance (operator opts in); retention derived not stamped; scoped to `ObjectType.INCIDENT`; ⚠ `ObjectQuery`'s 9-arg constructor is load-bearing → `okf/backend/control-plane/jobs.md`
- **`mail.send` has no true CC** — needs a CC-aware `@PublicApi` SPI overload, not until a second caller asks; ⛔ never a second SMTP transport
- **WRITE-1** — the implicit adoption ambiguity stays, documented at the code; ⛔ never teach the server the UI slug rule
- **`AiDraft.prerequisites` shared applier** — single producer; extract only when a second tool gains prerequisites
- **AGT-6a tool `args` runtime validation** declined (contract test instead) — revisit after all **23** tool
  schemas are audited; still 23 (`InspectoPackTest:61` pins the count) and the audit has **not** run: the
  2026-07-27 cross-adopter pass covers **6 of 23** (`ToolSchemaAdopterContractTest`). Precondition unmet.
- **AGT-5 embedding recall** parked (`CaseStore` is a 256-cap ring)
- **D8** digest deliveries correlate to the digest, not per notification; `deliverWithReceipt` escape hatch only if a provider won't echo `Message-ID`
- **Time zone of incoming data (a)** — no editor for `raw.fields[].timezone_column` by decision; a fifth "data offset wins" tier is a separate build; ⛔ never reached by relaxing the `%z`/`%Z` gate → `okf/backend/engine/duckdb.md`
- **Unpack (5)(8)** — a partial archive never fails its Consignment; nested archives refused (`depth` = 1); with `processing.unpack.enabled: false` the same-file engine divergence returns (operator opt-out)
- **Collector rename residual** — the pipeline TOON `source:` block stays (renaming breaks authored TOON); `'SOURCE'` stage category unchanged → `okf/backend/gotchas/cross-cutting.md`
- **Geo map** — DuckDB `spatial` extension deferred (zero `ST_*` demand); progressive loading obsoleted by `GEO_POINT_CAP = 5000` → `okf/frontend/features/geo-map.md`
- **Catalog** — offline `/db/query` returns 501 (honest degrade); `EntityProjectionGraphSource`, Geo point/route sources, `ReconExecService` stay offline arms; ⛔ "backfill the `table` attr" REFUTED — do not re-file → `okf/frontend/features/catalog.md`
- **`endSessionUrl` / server-published OIDC config** — not buildable as scoped; 🔴 if ever built, `session.service.ts` uses `??` so a server-sent empty string beats `environment.oidc` → `okf/backend/editions/auth-security.md`
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global
- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed
- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · **Postgres multi-user is PARKED** until a multi-operator install exists · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (D3 = the shipped 2GB cap).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · AuditTrail has no auth events (⚠ disclose) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · `-Djobs.maxConcurrentRuns` is the only bound · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

*(Rebuilt 2026-09-07. The previous table carried six dead aliases, two canonical rows pointing at the now-empty
§1, and four duplicate pairs it never recorded. A dead alias is worse than none: it makes a closed row look open.)*

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · `BatchGraphRunner` parity blocker (§3) · §5 "archive pipeline-spec + waves-plan when Row 15 closes" |
| D13 parser field tiers (§2) | `superpower/parser-field-tiers-interview-plan.md` (the interview-#2 kit) |
| AGT-5 `DryRunProvider` (§2) | AGT-6b row (§2) · `superpower/agt-6-plan.md` §4.2 G2 |
| EXECUTION-RESIDUALS X4 record-level replay (§3) | `okf/frontend/features/run-detail.md` + `USER_GUIDE.md` "reprocess is whole-batch only" · `INDEX.md`'s `EXECUTION-RESIDUALS-SKETCHES` pointer (which cites §4 — the row is in §3) |
| `batch_id` rename trio — §6 (decided: rides the MAJOR) + §2 release notes | `okf/backend/control-plane/api-stability.md` §Release notes (D-12) · `consignment-elt-architecture.md` deferred renames · `elt-final-amendment-plan.md` D-12 / Phase 7 · `okf/backend/engine/db-layer.md` (cites §4 — no such row) |
| WORKBENCH-S4 (§3) | AUTHORING-REDESIGN-1 (o) (§3) · `superpower/step-workbench-s4-design.md` |
| X-Actor full removal (§2) | `okf/backend/editions/auth-security.md` §Still-open · `EDITIONS.md` SEC-11 · `REQUIREMENTS.md` R4. 🔴 The §2 row's stated gate ("the API-v1 sunset") names apparatus **deleted 2026-07-25**, and `api-v1.md` never mentions X-Actor — re-state the gate before working it |
| Completeness KPI hold (§2) | Completeness KPI K2/K4/K5 (§3) · `superpower/completeness-kpi-plan.md` |
| Compliance program NFR-7 (§2) | SOC 2 Type II window (§2) — the same observation window, twice in one table |
| Deployment topology live validation (§2) | Deployment topology gaps (§3) · §6 "D1–D8 signed as recommended" — the §3 row's gate is already discharged |
| Postgres multi-user — §6 PARKED | §3 Postgres multi-user row, which contradicts §6; its plan pointer is dead (the file is in `plans-archive/`) |

**Deleted 2026-09-07:** *Three disagreeing name rules* — resolved **2026-08-17**
(`okf/backend/control-plane/pipeline-identity.md`), not on 2026-09-06, and all three of its aliases were dead or
stale. Its live successor is the new §3 row **NAME-DIRS-1**.

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the previous page
grew to 505 KB that way before the 2026-09-06 consolidation. This page lists **open work only**.
