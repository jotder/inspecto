# Backlog — every OPEN item, one page

**Updated:** 2026-09-06 — consolidated. The previous page (505 KB, 3,288 lines, roughly half of its rows
already closed) is frozen as
[`archived-documents/backlog-snapshot-2026-09-06.md`](archived-documents/backlog-snapshot-2026-09-06.md);
every closed row's as-built narrative, commit SHAs and refuted premises live there and in git history.
This rewrite also folded in the open items that had been living outside this page: the gate register's
pending decisions and "simply unbuilt" list (`superpower/gate-register.md` §3/§4, 2026-08-29), the
remainders of every plan still in `docs/superpower/`, and the last handoff's next steps.

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
1. §1 decisions, cheapest first: three name rules · `description` · `duplicate_check` owner · blank-`output_table` · BI-5 vocabulary · AUTHORING-REDESIGN-1 (n2)/(j)/(l)/(o).
2. **Release notes for the next MAJOR** — the draft now lives in `okf/backend/control-plane/api-stability.md`; append a line with every further `feat!:` (§2).
3. **Step Processor catalog** — 17 partial processors, each its own product decision; the cheapest to finish is `transform.lookup` (an inline static map over the shipped `transform.join`) (§3).

## 1. Operator decisions pending

Cheap calls that unblock work. Each is a question; none has a default. Answer, record the answer in the
owning doc, then move the resulting work to §3/§4 or delete the row.

| Decision | Question | Owning doc |
|---|---|---|
| **BI-5 vocabulary** | Is the `measure-threshold` guard rule over-broad, or is the shipped BI-5 capability (`*_alert.toon` measure rules over `dataset:` + `measure: agg(field)`) misnamed? Allowlisted as `docs/REQUIREMENTS.md::measure-threshold` meanwhile. ⛔ Not resolved by rewording BI-5 to make the guard green. | `REQUIREMENTS.md` BI-5 · `GLOSSARY.md` §4/§8 |
| **Three disagreeing name rules** | A pipeline named with spaces stamps a slugged `id` while `path` and every `dirs.*` keep the raw name; the id is omitted when the derived slug violates `[a-z0-9][a-z0-9_]*` (`my-pipe`, `has.dot`). Is the derivation wrong or the pattern? ⛔ Don't widen the pattern without checking the filename derivation (`ConfigRoutes.identityField`). Also the only surviving BUILDER-1 observation. | `okf/backend/control-plane/pipeline-identity.md` · `okf/backend/control-plane/onboarding-authoring.md` |
| **Onboarding `description`** | The Catalog create dialog's `description` is undeclared in `ConfigSpecs.pipeline()` and never read. Decide what it is FOR before anything builds around it. | `okf/frontend/features/onboarding.md` |
| **`duplicate_check` stage owner** | Which onboarding stage pane surfaces `processing.duplicate_check.*` (Collection shows the different collector-level `duplicate__*` block)? UX design, not a quick build. | `okf/frontend/features/catalog.md` |
| **Blank-`output_table` Catalog link** | Should a batch with a blank `output_table` link to its pipeline's single event node (ambiguous for segments)? A product call, not a bug. | `okf/frontend/features/catalog.md` |
| **Delimited robustness tri-state** | Two knobs whose ENGINE default is ON render as OFF-looking tri-state toggles; fix is a 3-way picker per knob. Also: `null_padding`'s per-frontend default divergence is not surfaced. | `okf/backend/config/parsing-options-reference.md` |
| **Consignment `batch_id` rename trio** | Three separately gated renames after the code rename shipped (`ff33246a`): (1) `batch_id` DDL columns in `DbProvenanceStore`/`DbStatusStore` — a real `ALTER TABLE` migration incl. the `payload` blob literal; (2) the `__batch_id` data-plane column in output Parquet/CSV — ⛔ accept-both-on-read impossible, a user-visible output-schema break; (3) `batch_id` as `.toon` config key (`PipelineJobRunner`) — a config-key contract break. Each needs its own sign-off; (2)/(3) also a MAJOR cut. | `superpower/consignment-elt-architecture.md` status table · `superpower/elt-final-amendment-plan.md` §9 D-12 |
| **ELT D-4 / D-7 re-decision** | Per-kind authoring migrations (D-4 enrichment, D-7 materialize) were made "S3-gated options to re-decide when S3 lands". S3a–S3d are complete, so the re-decision is due. | `superpower/elt-final-amendment-plan.md` "Phases 3/6 user-surface scope SUPERSEDED 2026-08-06" |
| **ELT Phase 1 CSV shape** | The schema-STRUCTURE first-table CSV shape (§3.2) was never built and its gate ("revisit with Phase 2's type flow") is spent. Re-scope or drop it deliberately. | `superpower/elt-final-amendment-plan.md` "Phase 1 remaining" |
| **`mode: clone` arming** | Engine ready; real gate is that nothing surfaces partial-commit state to an operator (B9 accepted constraint). Arm it, or leave refused. | `okf/backend/engine/branch-aware-ingest.md` |
| **PARK-1 (b)** | A batch with unpack-EXPANSION members is REFUSED, not drained: the shared archive original stays in the inbox and re-parks each cycle. Needs the shared-original lifecycle decided first. | `okf/backend/pipeline-graph/step-park-drain.md` |
| **Unpack roll-up + entry grain** | (4)/(1) archive-LEVEL roll-up verdict (status vocabulary stays §6 Q1's); (3) ratify the entry grain now in force (§6 Q2). | `okf/backend/engine/unpack-stage.md` · `archived-documents/plans-archive/unpack-stage-plan.md` |
| **D8 auto-disable / suppression** | Policy on hard bounce + complaint — denial-of-notification blast radius. | `okf/backend/control-plane/events-metrics.md` |
| **Consignment concurrency (b)** | Should `processing.priority` also weight ACQUISITION (fetch lane `acquirePermits` is plain FIFO)? Revisit only if remote-fetch contention shows. ⛔ Do NOT retire `PipelineScheduler.runPermits`. | `okf/backend/engine/consignment-concurrency.md` |
| **Config-declared paths — two bypasses** | Each its own decision, never folded in silently: (a) `PipelineJobRunner`'s documented `ConfigSafetyValidator` bypass; (b) `requireTopLevelSinks` is a depth rule about literal nesting — resolving real paths would change the answer for the wrong reason. | `okf/backend/config/config-safety.md` |
| **AUTHORING-REDESIGN-1 (n2)** | Admit `sql` to `RouteArming.BRANCH_STEP_KINDS` (needs the branch walker to execute it) or keep the refusal permanently. 🔴 Mid-branch a `transform.sql` compiles but does not ARM. | `okf/frontend/features/schema-mapping-authoring.md` §0 |
| **AUTHORING-REDESIGN-1 (j)** | Does the lift normalise a stored literal `engine: auto` away? ⛔ Do not give the spec `default: 'auto'`. | `okf/backend/pipeline-graph/pipeline-config-keys.md` |
| **AUTHORING-REDESIGN-1 (l)** | Where is a grammar-bound / dangling-binding / binary fixed-width parse node edited? A Components-registry decision; the same question blocks Test-mapping on a generic `parser` node (P4). | `okf/frontend/features/pipeline-editor.md` |
| **AUTHORING-REDESIGN-1 (o)** | `superpower/step-workbench-design.md`: distil into OKF and archive, or keep the one-surface workbench (input-relation picker, column filter, grouping) + `RowShaper.fuse` as scheduled work. | `superpower/step-workbench-design.md` |
| **JAVA-SIMP-2 next seam** | Typed records at config seams, one operator-named seam at a time with a round-trip fixture. Seams #1 (`ComponentStore.write`) and #2 (`RouteBranch`) done. Candidates: `ConfigRoutes` read-only; sink (spans three raw sections, own grounding first). Refused: collector/acquisition, `PipelineConfigParser`. ⛔ A record on a config path carries the unmodelled-keys remainder or stays read-only. | `okf/backend/modules/reactor.md` |
| **Feature × edition SEC-07** | Secrets providers per edition — the one open ❓ cell on the board. | `EDITIONS.md` §Feature × edition matrix |
| **Deployment topology D1–D8** | Container image · bundle the Postgres driver · DuckDB cap default · Vault/KMS priority · OS matrix · RPO/RTO SLOs · Gov/FIPS · IAM pair (Keycloak+WSO2 vs WSO2 IS). The plan is DRAFT; nothing in it is schedulable until these are signed. | `superpower/deployment-topology-plan.md` §10 |
| **Compliance shape** | Which of HIPAA / PCI, and for which prospect — C1 applicability statements cannot be scoped without it (⛔ never generated from the controls matrix). Also Q6: SOC 2 Type II window length. | `superpower/compliance-certifications-plan.md` §6 |
| **Completeness KPI hold** | ⏸ ON HOLD since 2026-08-30. Lifting it needs two answers: where do processed filenames come from durably (🔴 no default-on store holds them), and does `{seq}` restart per bucket or run continuously. | `superpower/completeness-kpi-plan.md` §2b |
| **Postgres multi-user backend** | Direction captured, plan written, operator-deferred since 2026-07-27. Is any deployment multi-operator yet? Pool library (HikariCP vs PgBouncer)? | `superpower/postgres-multi-user-plan.md` §7 |
| **Open-DAG design questions** | Q1 may a post-sync step create an arbitrary table (naming/lifecycle/registration half); Q3 idempotence of step B when A re-runs; Q4 reports/matrices as Step or Job. | `superpower/open-dag-pipeline-design.md` §5 |
| **SAMPLE-1 corpora** | Retired seed arms + `samples/{subscriber,cdr,gwlog,events}/` corpora are deliberately left in place, labelled. Keep or remove. | `FEATURE_INVENTORY.md` §2 |
| **DATA-GOV-1 distribution** | ~57 MB of real carrier data (`asn-parser/config/rtdms/`, `asn-parser/data/`, `config+data.7z`) exists only in working trees; the parity harness depends on it. Out-of-band · Git LFS with access control · synthetic corpus. ⚠ Never force-push/reset master to fix this. Owner: ASN shift + data-agreement owner. | `PROJECT_NOTES.md` |

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

## 3. Product features — decided or unblocked, simply unbuilt

Grouped by area. A row with lettered items keeps the letters of its source doc so the two stay aligned.

### Authoring (Parse / Transform / pipeline editor)

- **P2** · **AUTHORING-REDESIGN-1** — open letters (decisions (j)(l)(n2)(o) are in §1; (f)(g)(m) SHIPPED 2026-09-06 — `JOIN_REFERENCE_MISSING`/`JOIN_ON_MISSING`/`UNKNOWN_JOIN_REFERENCE` at save, `SchemaMappingDrift` on all three schema save paths, `?pipeline=` sent by the UI): (c) v2 structured AST table over the SQL for WHERE/JOIN editing — 🔴 precondition: probe that the `json` extension loads on the SEALED `SqlSandbox` connection; (d) v3 macros as the UDF registry (per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `BatchIngestStrategy`, preview) — demand-gated; (e) column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; (i) per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`. Still open on (f): which COLUMNS the reference carries is the dry-run's question (it reads the store); the save checks existence and `on` presence only. → `okf/frontend/features/schema-mapping-authoring.md` §0
- **P2** · **Step Processor catalog** — 121 processors: 33 delivered / **17 partial / 69 planned**. Each partial is a product decision (Kafka consumer, XPath grammar, drift report, profiler, static lookup, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email/webhook sinks…) — pick one by name; `transform.lookup` is the cheapest. Delivering one = node type + attributes + lift/lower, flip status, regenerate `processor-catalog.contract.json` and the EDITIONS §Step Processors table (`tools/render-processor-board.mjs --check`). → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`
- **P2** · **P4 Test mapping on a generic `parser` node** — only reachable where the parse node is per-format; blocked on §1 decision (l). ⚠ Offline, non-`DIRECT` types show blank (mock has no SQL engine) — recorded. → `okf/frontend/features/pipeline-editor.md`
- **P2** · **`kpi_report_builder` host (AGT-6a)** — no viable host pane; a new surface, not an adoption. Plus one cosmetic defect: `projection_author` stale `columns.items` (plan §3.4.8). This row is what keeps `superpower/agt-6-plan.md` out of the archive. → `superpower/agt-6-plan.md`
- **P2** · **Canonical-pipeline selective bundle export/import** — the metadata bundle's `authored-pipeline` kind still targets the RETIRED `*_flow.toon` `PipelineStore`; a canonical `*_pipeline.toon` transfers only via the datasource zip or the client-side stream-config bundle. Wanted: one selective export/import with dependency closure (schemas, per-segment schemas, grammar/enrichment companions, Connection as secret-free requirement) and retire/repoint the `authored-pipeline` kind. ⚠ Decide the config-namespace vs registry-id collision on the word *schema* before touching `BundleRoutes.WRITABLE_TYPES`. → `okf/frontend/features/onboarding.md`

- **P3** · **AI drafting has no applicable component kind** — restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes them) or rework `SchemaEditorDialog`. No low-risk slice survives — design first. → `okf/frontend/features/inline-ai-authoring.md`
### Onboarding, Catalog, Parsing

- **P3** · **Unpack (11) absent codecs** — (xz/zstd need a new decompression library: a dependency sign-off, not a build) — xz and zstd have no plugin; `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) unhandled — arrival-completeness is a Collector question. ⚠ The UI cannot author the explicit empty-list `data_extensions[0]:` opt-out (schema-form `list` writes empty as `null`). 🔴 Stale `META-INF/services` "ORDER MATTERS" header. → `okf/backend/engine/unpack-stage.md`
- **P2** · **Onboarding (Stream/Reference)** — D5-ref: how a `delete` tombstone *enters* the reference store (reserved column? Decision Rule consequence?) — wait for a real delete-feed; D6-ref: within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed; optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⚠ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`
- **P2** · **Onboarding ↔ Pipeline unification W4/W5** — (W0 PROVEN 2026-09-06: `LiftLowerFixtureSweepTest` runs every `spaces/**/*_pipeline.toon` through the editor's own seam — `PipelineEditable.toMap` → codec → STRICT lower — and all 22 survive verbatim; it stays as the standing gate.) W4: `EnrichmentService` incremental-vs-full recompute — never silently convert one into the other. W5 promotion-grade export: extend `BundleExporter`/`DataSourceBundleResolver` to decision rules + reference datasets; import-time referential integrity (a missing connection is not caught until first poll). Engine has no grouping transform (rollup honestly = `sink.materialized`). ⛔ `PipelineCompiler.toConfigMap` deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`
- **P2** · **Catalog: per-date/partition retention for stream data** — `cleanup` is filename-glob + mtime-gated; a D5-style retention tier for partitions is unbuilt. → `okf/frontend/features/catalog.md`
- **P2** · **Parsing (Stage-1)** — ASN.1 grammar source: a reference to a stored schema module instead of pasted module text (also the prerequisite for a per-vendor transform config home); drop-in `plugins/` jar directory (JobPackManager classloader precedent) so a customer parser deploys without a rebuild. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
- **P3** · **Problem-files view: `logical_name`** — carry `logical_name` beside `origin` so a re-delivery groups to its earlier compression spelling. Additive; on demand. → `okf/backend/control-plane/control-api.md`

### Execution, Consignments, Pipeline graph

- **P2** · **Branch-aware executor residuals** — ((b) and (c) are design passes before code; (d)–(g) wait for a real need) — (b) multi-schema + route needs a **segment-scoped lift** (`writeAndTrace` runs once per segment while the divert lifts the whole graph) — ⛔ do NOT just lift the refusal; (c) mid-branch transforms in the recipe route verb — no per-branch scaffolding in `RecipeCompiler.route()` / `PipelineLift.branch()`, design pass written; (d) still unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; (e) acquisition-side "listed remotely, not yet fetched" gauge — name it first; (f) `acquire.maxFilesPerCycle` — only if overshoot is real; (g) `sinks:` follow-ups: per-sink `ducklake` block in flat `.toon`, decision-rule routing with `sinks>1`, versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. ((a) `mode: clone` is a §1 decision.) → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md`
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
- **P2** · **Acquisition / connections** — the JDBC-based connectors each need their own library-specific proxy wiring (HTTP CONNECT proxy shipped 2026-08-13). Rule: a proxy test must assert the proxy was USED. → `okf/backend/acquisition/connectors.md`
- **P2** · **EDG-01 edition gating** — modules or `-D` capability switches with the 503-panel UI for SEC-08 + CMP-08 (Enterprise-only) and OPS-05/OPS-06/SEC-10/CP-09/CP-11/CP-13/CP-15 (not Personal) — all core code today. Deliver cell-wise; a scheduled cell cites its row. → `EDITIONS.md` §Feature × edition matrix

- **P3** · **Job framework** — Maintenance COULD tier: space-to-space comparison; predictive maintenance (AGT-5 territory) deliberately deferred. → `okf/backend/control-plane/jobs.md`
- **P3** · **D6 spec-authoring UI** — a matrix/editor for `findings-spec`; today authored as TOON through generic `/components` CRUD. Nothing broken without it. → `okf/frontend/features/objects.md`
- **P3** · **Signal / Decision networks** — optional S8 (connector-direct emission + cross-space controller); a general event-triggered consequence policy gate (still `/apply`-only); RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet). → `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md`
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (DuckDB `spatial` extension itself: zero demand re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (outbound mirror of the connector SPI reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`
- **P3** · **Security: policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility, "why denied?" endpoint and read-only Policies tab already shipped). Non-blocking. → `okf/backend/editions/auth-security.md`
### Deployment & packaging

- **P2** · **Deployment topology gaps** (after §1 D1–D8 are signed) — GAP-2 Enterprise packaging (SCR-8) · GAP-3 service wrappers (SCR-3) · GAP-4 DuckDB cap default · GAP-5 T15 surge admission · GAP-6 Vault/KMS (SEC-8) · GAP-8 Postgres driver · GAP-9 launcher token-line debris (SCR-9) · GAP-10 bundle missing 13 archived docs (SCR-10). Phases 0–5 all unbuilt. → `superpower/deployment-topology-plan.md` §11
- **P2** · **Postgres multi-user (after the §1 decision)** — P1 pool behind `JdbcDrivers` (each `Db*Store` holds ONE `synchronized` connection); P2 replace `browseConnection()` (F2: it hands out the store's long-lived connection, a pool has no such thing); P3 **schema**-per-space URL wiring (NOT db-per-space); P4 `CaseStore` interface + PG impl (JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores + a concurrency test. Keep events on Parquet. ⚠ Not the same work as `OperationalDb`/PG-1 (shipped). → `superpower/postgres-multi-user-plan.md` §5–6

## 4. Engineering / tech-debt

- **P3** · **PATH-2 (residual, untidy only)** — (the `BackupTask.restore` zip-slip jail is PINNED 2026-09-06 by `MaintenanceLibraryTest.restoreRefusesASidecarEntryThatEscapesTheTargetBeforeWritingAnything`.) Family (a) three store `fileFor` helpers: LEAVE unless someone is in those files anyway. ⚠ Routing `ControlApi.serveStatic` through `PathJail.contains` is a posture change needing an operator call — ⛔ grounded 2026-08-26 "do not build it". → `okf/backend/config/config-safety.md`
- **P2** · **Vocabulary rollout, Tier 3** — bare `flow` as a standalone identifier and in comments (needs per-occurrence judgement, no guard rule); the agent-tool argument named `flow` (`flowSchemaJson`) is an external contract needing dual-accept; the `?flow=` provenance params are AUTHORING-REDESIGN-1 (m) above. Tracked in `CONFIG_ALLOW` of `tools/check-vocabulary.mjs`. → `GLOSSARY.md` §13 · `PROJECT_NOTES.md`


- **P3** · **Vocabulary: GLOSSARY adoption of the living-system terms** — Signal, Consequence, Query, Parameter, Result Set, Decision Engine are proposed, not adopted — do not use unilaterally. → `superpower/living-operational-system.md` §6
- **P3** · **Unpack (10) crash mid-archive** — re-ingests committed members; relies on `OVERWRITE_OR_IGNORE` idempotence. By design today; revisit only with a measured cost. → `okf/backend/engine/unpack-stage.md`
- **P3** · **`fp-query`/`fp-job`/`fp-enrich` module extraction** — build only on explicit request; not a single clean increment (`query`/`job` depend on `signal` + `ops`; `SharedDottedPathGrammarTest` cut needed). → `okf/backend/modules/reactor.md`## 5. Docs & hygiene

- **Doc-lifecycle violations** (shipped work still in `docs/superpower/`; the rule is distil → `git mv` to `plans-archive/` → update `INDEX.md`): `living-operational-system.md` (R1–R6 shipped; INDEX calls it a standing north-star — decide reference vs archive) · (`geo-map-case-studies.md` ARCHIVED 2026-09-06, distilled into `okf/frontend/features/geo-map.md`) · `compliance-certifications-plan.md` C2 half (delivered; home is `compliance/controls-matrix.md`) · `step-workbench-design.md` (§1 decision (o)) · `pipeline-spec.md` and `pipeline-waves-drain-plan.md` (archive together when Row 15 closes).
- ~~Stale statements inside live docs~~ — the seven-item pass ran 2026-09-06 (pipeline-spec D-9 · waves-plan flag · INDEX A5 · topology GAP-1 · consignment-elt §7.5 · gate-register Q2 numbering collision · release-verification caveat); the audit-log entry had been fixed the same morning. Re-open with new items, not this list
- **Hand-authored space fixtures must be in the editor's canonical form** — `route_step_pipeline.toon` shipped 2026-09-06 with a `dirs.database` that was not its first destination and failed `RecipeConverterTest`'s round trip on master for a day (lower() makes `dirs.database` = the first destination when `sinks:` has several). Save a new fixture through the editor once, or run that test before committing it.
- **REQUIREMENTS MoSCoW columns** — §3.1 ACQ-4 / §3.9 SPC-5 / §3.15 UI-8 are contradicted by `EDITIONS.md` and `INDEX.md`. → `REQUIREMENTS.md`
- **Template seed-pack enrichment (frontend C7)** — ongoing, not a discrete item: `kpi-overview`, `quality-monitor`, `trend-monitor` today. → `okf/frontend/features/studio.md`
- **GRAPHIFY-1 tool sync** — re-sync `.claude/skills/graphify/` after every `graphify install`; optional `pip install "graphifyy[sql]"`.

## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger.

- **ARCH-OPS-SCC** LEAVE (85-file ripple, cannot block anything) · **ARCH-F-CARVEOUT** LEAVE (148 refs / 28 files) · `{etl, etl.unpack}` and `{agent.kernel.*}` SCCs LEAVE · intra-module `ops↔ops.link/workflow`, `catalog↔catalog.spi` cycles are same-family · **M2** `CollectorService` decomposition · **C2** store-pair base (reopen at the 7th store) · **C4** BOM (reopen on an external consumer) · **C6** connection reuse (warm open 24 ms) → `okf/backend/modules/reactor.md`
- **PKG-5** agent-absent is the intended shipped default; ⛔ no `package.ps1` switch until the JDK 25+ vs Java 24+ floor is resolved → `okf/backend/build-run/build-test.md`
- **D11 caps** — `max_temp_directory_size` gets no default; preview/dry-run connections stay uncapped; the semaphore-computed cap is rejected → `okf/backend/engine/duckdb.md`
- **D7 startup backfill** full object scan — deliberately unfixed; trigger = shows up in measured startup time → `okf/backend/control-plane/tags.md`
- **MNT-14** — no UI surface / no shipped Job instance (operator opts in); retention derived not stamped; scoped to `ObjectType.INCIDENT`; ⚠ `ObjectQuery`'s 9-arg constructor is load-bearing → `okf/backend/control-plane/jobs.md`
- **`mail.send` has no true CC** — needs a CC-aware `@PublicApi` SPI overload, not until a second caller asks; ⛔ never a second SMTP transport
- **WRITE-1** — the implicit adoption ambiguity stays, documented at the code; ⛔ never teach the server the UI slug rule
- **`AiDraft.prerequisites` shared applier** — single producer; extract only when a second tool gains prerequisites
- **D8** digest deliveries correlate to the digest, not per notification; `deliverWithReceipt` escape hatch only if a provider won't echo `Message-ID`
- **Time zone of incoming data (a)** — no editor for `raw.fields[].timezone_column` by decision; a fifth "data offset wins" tier is a separate build; ⛔ never reached by relaxing the `%z`/`%Z` gate → `okf/backend/engine/duckdb.md`
- **Unpack (5)(8)** — a partial archive never fails its Consignment; nested archives refused (`depth` = 1); with `processing.unpack.enabled: false` the same-file engine divergence returns (operator opt-out)
- **Collector rename residual** — the pipeline TOON `source:` block stays (renaming breaks authored TOON); `'SOURCE'` stage category unchanged → `okf/backend/gotchas/cross-cutting.md`
- **Geo map** — DuckDB `spatial` extension deferred (zero `ST_*` demand); progressive loading obsoleted by `GEO_POINT_CAP = 5000` → `okf/frontend/features/geo-map.md`
- **Catalog** — offline `/db/query` returns 501 (honest degrade); `EntityProjectionGraphSource`, Geo point/route sources, `ReconExecService` stay offline arms; ⛔ "backfill the `table` attr" REFUTED — do not re-file → `okf/frontend/features/catalog.md`
- **`endSessionUrl` / server-published OIDC config** — not buildable as scoped; 🔴 if ever built, `session.service.ts` uses `??` so a server-sent empty string beats `environment.oidc` → `okf/backend/editions/auth-security.md`
- **AGT-5 embedding recall** parked (`CaseStore` is a 256-cap ring) · **AGT-6a tool `args` runtime validation** declined (contract test instead; revisit after all 23 tool schemas are audited)
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global
- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed
- **Working as designed** (gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · AuditTrail has no auth events (⚠ disclose) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · `-Djobs.maxConcurrentRuns` is the only bound · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | PIPELINE-WAVES-REMAINDER · pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · `BatchGraphRunner` parity blocker (§3 Pipeline graph) · RECORD-TRANSFORMER-1 (d) ingest cascade |
| Three disagreeing name rules (§1) | BUILDER-1 residual · Onboarding id-pattern decision · gate register §3.5 |
| D13 parser field tiers (§2) | §5 UI attribute tiers · interview #2 · gate register Cluster E |
| AGT-5 `DryRunProvider` (§2) | AGT-6b prerequisite · agt-6-plan §4.2 G2 · gate register Cluster D |
| X4 record-level replay (§3) | Quarantine / D-ETL "reprocess is whole-batch only" · EXECUTION-RESIDUALS-SKETCHES |
| `batch_id` rename trio (§1) | Consignment ELT deferred renames · ELT Phase 7 residue · gate register §3.3 |
| Step-workbench S4 + `RowShaper.fuse` (§1 (o)) | AUTHORING-REDESIGN-1 (o) · `step-workbench-design.md` |
| X-Actor full removal (§2) | API v1 row · security-module residuals |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the previous page
grew to 505 KB that way before the 2026-09-06 consolidation. This page lists **open work only**.
