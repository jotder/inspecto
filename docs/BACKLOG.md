# Backlog — every OPEN item, one page, grouped by product area

**Updated:** 2026-09-24 — **consolidated by product area.** The previous page (644 KB, 4,649 lines, of which
roughly nine tenths was struck rows, closure narratives and census history) is frozen as
[`archived-documents/backlog-snapshot-2026-09-24.md`](archived-documents/backlog-snapshot-2026-09-24.md).
Every closed row's as-built narrative, refuted premise and commit SHA lives there and in git history — grep
the snapshot by row id when you need the trail. The one before it is
[`archived-documents/backlog-snapshot-2026-09-06.md`](archived-documents/backlog-snapshot-2026-09-06.md).

**How this page is laid out.** §1 and §2 hold what is waiting on the operator or on the outside world.
§3 holds the open rows, grouped by **product area**, and inside each area by **functionality**. §4
(engineering platform) and §5 (docs and board hygiene) are the two non-product areas. §6 is the standing
refusals, grouped the same way. §7 maps duplicate names. **Find a row by its id or name, not by section
number** — rows moved between sections in this consolidation, and older docs cite the old sections.

> **55<!--count:backlog-rows--> rows: 0<!--count:backlog-p1--> × P1 · 27<!--count:backlog-p2--> × P2 · 28<!--count:backlog-p3--> × P3** —
> derived by `tools/check-doc-counts.mjs` from the rows between `## 3.` and `## 6.`; never hand-count.
> ⬇ 57 → 56 in this consolidation: `RTDMS-ASN-HARNESS-1` had closed on 2026-09-17 (verdict (c), kept
> verbatim with a javadoc) and was still ranked; its tree-wide residual already lived in
> `LEGACY-ASN-SRC-TREE-UNBUILT-1`, which now carries it. No other rank changed.
> ⚠ **Report the 0<!--count:backlog-p1--> P1 + 27<!--count:backlog-p2--> P2 rows as the owed number** —
> §0 defines P3 as demand-gated, so those 28<!--count:backlog-p3--> P3 rows are mostly a list of things
> deliberately NOT being built, and reading all 55<!--count:backlog-rows--> as pending work overstates it.

## Area index

| § | Product area | Active plans (`docs/superpower/`) | §2 gates in this area |
|---|---|---|---|
| [3.1](#31-pipelines--authoring-editor--step-catalog) | Pipelines — authoring, editor & Step catalog | [record-transformer-replaces-map-plan.md](superpower/record-transformer-replaces-map-plan.md) · [dead-property-validation-plan.md](superpower/dead-property-validation-plan.md) | — |
| [3.2](#32-pipelines--execution-lanes--consignments) | Pipelines — execution, lanes & Consignments | [retry-affordance-design.md](superpower/retry-affordance-design.md) · [branch-aware-segment-lift-design.md](superpower/branch-aware-segment-lift-design.md) (built — distil and archive) | Row 15 · X5 |
| [3.3](#33-acquisition-collectors--parsing) | Acquisition, Collectors & Parsing | [stream-consumer-design.md](superpower/stream-consumer-design.md) (built — distil and archive) · [parser-field-tiers-interview-plan.md](superpower/parser-field-tiers-interview-plan.md) | D13 |
| [3.4](#34-catalog-onboarding-datasets--lineage) | Catalog, Onboarding, Datasets & Lineage | — | — |
| [3.5](#35-data-quality-observability-signals--alerting) | Data quality, Observability, Signals & Alerting | [completeness-kpi-k4-design.md](superpower/completeness-kpi-k4-design.md) | OPS-5 · Completeness KPI hold |
| [3.6](#36-analytics--queries-bi-studio--export) | Analytics — Queries, BI, Studio & Export | — | — |
| [3.7](#37-control-plane-api--jobs) | Control plane, API & Jobs | [job-path-compat-survey.md](superpower/job-path-compat-survey.md) · [space-comparison-design.md](superpower/space-comparison-design.md) (postponed) | Release notes for the next MAJOR |
| [3.8](#38-security-policy-editions--compliance) | Security, Policy, Editions & Compliance | [route-gating-audit.md](superpower/route-gating-audit.md) | X-Actor · NFR-7 · SOC 2 · SEC-INCIDENT-1 · DATA-GOV-1 |
| [3.9](#39-cases-incidents--assistant) | Cases, Incidents & Assistant | — | AGT-6b |
| [3.10](#310-deployment-packaging--scale-out) | Deployment, Packaging & Scale-out | [enterprise-scale-out-plan.md](superpower/enterprise-scale-out-plan.md) | Deployment topology live validation · E1 |
| [3.11](#311-web-ui--spa-wide-hygiene) | Web UI — SPA-wide hygiene | — | — |
| [3.12](#312-link-analysis--geo) | Link Analysis & Geo | [link-analysis-backlog-plan.md](superpower/link-analysis-backlog-plan.md) — the ONLY Link Analysis backlog | — |
| [4](#4-engineering-platform--build-test-ci--tooling) | Engineering platform — build, test, CI & tooling | — | — |
| [5](#5-docs--board-hygiene) | Docs & board hygiene | — | — |

## 0. Priority order

**P1** = do next: a live defect, or decided work with user-visible value — ⛔ a P1 must **name the file it
changes**; a row that cannot is a decision (§1) or a design (P2). **P2** = build after P1, or once its gate
or decision lands. **P3** = demand-gated; build only when someone asks by name. Every §3–§5 row carries its
rank.

**P1 is empty.** The P2 rows split three ways — pick from the first group:

| State | P2 rows |
|---|---|
| **Startable now** — no gate, no owed decision | `STREAM-CONSUMER-1` close-out — the `SP-ACQ-09` cell (§3.3) · Onboarding D5-ref — ground the delete-feed first (§3.4) · `RELEASE-PIPELINE-NEVER-EXECUTED-1` — the checksum/signature split (§4) |
| **Design first** — the trigger fired, the shape is not decided | AI drafting on a non-`schema` kind (§3.1) · Platform Services Stage 2/3 (§3.2) · cross-Space consequence (§3.5) · Bundle "load as draft" (§3.7) · D6 `findings-spec` UI (§3.9) · policy-authoring UX (§3.8) |
| **Blocked** — on evidence, a host, an upstream or the operator | `DEPLOY-SERVICE-WRAPPER-1` (a run on two hosts, access details owed in §1) · Postgres multi-user (needs a Postgres) · intake-cap default (a soak) · X4 replay (evidence that does not exist yet) · Completeness KPI (§2 hold) · `SPACES-FROM-PARTITION-MAP-1` (ingress routing absent) · AGT-5 dry-run seam (upstream) · D-8 XLSX bundle proof (operator) · space-to-space comparison (postponed) · W5 forward closure (operator decision) · Consignment ELT `generation` (kept open by operator choice) · Branch-aware residuals (each waits for a real need) · Consignment addressing (waits for a consumer) · Parsing Stage-1 (trust decision) · Deployment topology GAP-4 · `D8-SES-SNS-1` (security review first) |

**Standing rules for editing this board** (distilled from the shifts that grew the old page to 644 KB):

- **Closed rows are DELETED, not struck.** Mark the ship in the row's owning OKF concept first (that stays
  authoritative), then delete the row. A struck row with its narrative underneath is what made the old
  page unreadable — and a lane grepping an id landed on the open-sounding original prose first.
- **Strike or delete by the EXACT id**, never by a "contains" match — that once removed the wrong row.
- **When a row's work ships, fix the HEADLINE, not only the detail** — a reader triaging by headline picks
  up finished work.
- **A lane files AT MOST ONE row.** Anything smaller is fixed in place or dropped; a residual that belongs
  inside an open row stays there; work found and fixed the same day is not filed.
- **A fired trigger removes a GATE; it does not set a RANK.**
- **A row's cause is a HYPOTHESIS** until grounded against the code — grep the SYMBOL from the repo root,
  never re-use a file list a previous pass wrote down.
- **Every count on this page is derived** (`tools/check-doc-counts.mjs`, `tools/check-gate-tally.mjs`);
  fix the sentence to match the rows, never the rows to match the sentence.
- **A capability with no committed example is a capability nobody has ever run.** "The tests pass" is not
  "the path has been exercised" for anything no config under `spaces/` or `inspecto/examples/` declares.

## 1. Operator decisions pending

**Owed inputs** — nothing a shift can supply. Counted from the table:
`awk '/^\| Owed input/,/^$/' docs/BACKLOG.md | grep '^| ' | grep -vc '^| ~~'` minus the header row = **4**.
⚠ Three of the four block rows whose **code is already complete** — a shift reading them will look for
something to build and find nothing. Answer an owed input by **deleting its row**, never by adding a second.

| Owed input | Blocks | Why only the operator can supply it |
|---|---|---|
| **Two dates** — when the off-repo backup bundle was deleted, and when the no-reuse check completed | the `SEC-INCIDENT-1` CC6.1 line (§2) | Both acts happened off-repo and leave no trace here. ⛔ A line dated *when it was written down* would misstate the evidence to an auditor |
| **Per-tier RTO/RPO targets** | `compliance/evidence/rto-rpo-statement.md` · §2 Deployment topology | ⛔ Decided 2026-09-15: do **not** transcribe the signed §3.14 numbers. In `editions.md` they are an engineering target; in `compliance/evidence/` they are a commitment an auditor holds you to, and those are not the same number by default |
| **Access details** — MinIO endpoint/key/secret, the systemd host, the elevated Windows box | `AIRGAP-S3-EXTENSIONS-1` (§5) · `DEPLOY-SERVICE-WRAPPER-1` (§3.10) | ✅ Operator confirmed 2026-09-15 that all three EXIST. Both rows are **code-complete and evidence-blocked** — neither needs a build, only a run |
| **Eager or deferred resolution of an `s3://` `dirs.database`** — validate a profile at PARSE time (forces the deployment-root / pipeline-field split, because `CollectorService` parses every pipeline before `loadConnections` runs) or resolve at FIRST WRITE-TIME USE (no split; `dirs.database` is a plain `String` nothing resolves at parse) | scale-out phase C §5.4 bullet 6 (the credential surface that `AIRGAP-S3-EXTENSIONS-1` left behind when it closed 2026-09-15) | The bootstrap order is measured (`ServiceBootstrap.buildFrom:69` vs `:73-74`); which side of it to build on is a design posture only the operator sets. |

**Decisions owed on individual rows** — an index; the question and its options live on the row.

| Area | Row | The call |
|---|---|---|
| 3.1 | `STEP-TYPES-DEAD-CLIENT-MIRRORS-1` | (A) keep or retire `GET /pipelines/step-types`; (B) delete the dead client mirrors |
| 3.2 | EXECUTION-RESIDUALS X4 + X1 | X4's replay default — ⛔ not yet, the evidence it needs does not exist; X1's §5 decisions in `superpower/retry-affordance-design.md` |
| 3.3 | `LEGACY-ASN-SRC-TREE-UNBUILT-1` | wire, rename or delete the 21 uncompiled test-tree files |
| 3.4 | Onboarding ↔ Pipeline W5 | carry the reference Datasets a Pipeline reads in its export (forward closure) |
| 3.6 | D-8 XLSX export | does it work in a shipped / air-gapped bundle? |
| 3.11 | `API-DEAD-METHODS-1` · `JOB-RUNS-DIALOG-DEAD-1` | wire or delete; retain as a test vehicle or delete |
| 4 | `REACTOR-VERDICT-CI-1` | wire `check-reactor-verdict.mjs` into `ci.yml` |
| 3.12 | Link Analysis | every open D-U* / LA-* call lives in `superpower/link-analysis-backlog-plan.md` |

⛔ **A plan is where a decision is *described*; this section is where it is *queued*.** A decision
described in `superpower/` and not indexed here is how a three-day stall happened (2026-09-11 → 09-14).

## 2. Externally gated

Nothing a shift can close from this checkout. Every gate names something a shift can CHECK from this repo;
where the trigger is external it is phrased as "when X is recorded in \<file\>".
**13 of the 14 gates were RUN here** (full sweep 2026-09-08, recounted 2026-09-15). The 1 not run, and why: **Completeness KPI hold**
has nothing to run — its gate is an engineering action, not a check.
`tools/check-gate-tally.mjs` fails the build when this sentence disagrees with the table.
⚠ A gate you cannot run is not a gate that holds. **Re-run every gate with a probe that can succeed before
trusting a 0** — two `gh search code` probes here were unfalsifiable by construction (the replacement is
the git-tree API: `gh api 'repos/jotder/inspect-agent/git/trees/main?recursive=1' -q '.tree[].path'`).

| Item | Area | Remains | Gate — how a shift CHECKS it |
|---|---|---|---|
| **Row 15 — ELT Phase 6 deletion half** | Pipelines — execution | Delete the legacy flat read path (amendment §6 step 4). **Step 1**, the one-shot converter, is BUILT and dischargeable: `inspecto migrate-configs` (`ConfigMigrator` + `MainApp`) plans by default, refuses rather than loses, and passes `*_enrich.toon` over (an enrich config stays a Job — `MIGRATE-ENRICH-1`, closed 2026-09-16). **Step 2**, the parity gate, is MET: the whole suite under `-Dingest.lane=graph` is green with zero refusals (2026-09-16). Also absorbs RECORD-TRANSFORMER-1 (d): the ingest lane runs exactly one projection slot, so a second `transform.sql` cascades only once the graph lane carries ingest. ⛔ Not closable by code; do not start it on momentum. | **An operator call on D-2, then a release.** D-2 wants the converter and the `-Dingest.lane` flag shipped and exercised in a real release before the legacy readers go. `pom.xml` on master is `4.0.0-SNAPSHOT`, so no "verification minor" was ever available from master, and the retired `3.x` line may not be tagged. ⇒ The one remaining path: amend D-2 so the MAJOR itself is the flagged verification release, with the deletion waiting for the release after it. **Check: `git tag --merged master --sort=-v:refname \| head -1`** — still `v3.11.0`. |
| **X5 cross-lane drill-down + StepInfo envelope** | Pipelines — execution | One-Consignment drill-down across lanes; ~1 KB pointer+schema+diagnostics envelope, failure routed by PORT. Phase-7 convergence. | `git tag` — the next MAJOR (pipeline-spec §13 D2). → `okf/backend/pipeline-graph/execution-lanes.md` |
| **D13 parser field tiers** | Acquisition & Parsing | Run the onboarding-observation session with the kit in `superpower/parser-field-tiers-interview-plan.md` (re-grounded 2026-09-16). ⚠ Before scheduling, **re-state the kit's question 2**: its premise ("every `tier:'required'` field ships `required:false`") is refuted — an omitted `required:` means ENFORCED (`attribute-spec.ts:131`). Tier now controls only ORDER on the Parse pane, not disclosure, and node tiers are authored in Java (`NodeAttributes.java`), not in `node-attributes.ts`. ⛔ The pre-agreed analysis rule in `okf/frontend/features/grammar-config.md` must NOT be re-derived after the observations — agreeing it in advance is the whole point. | The gate FIRED 2026-09-15: a real onboarding user is available. **Close when `parsing-attributes.ts` carries `tier:` values annotated with the observations that earned them, plus a question-2 decision note in `okf/frontend/features/grammar-config.md`.** |
| **OPS-5 provenance conservation** | Observability | Live-feed soak only — no code left; feature built, off by default. The discharge criterion is well written (`docs/ops/provenance-conservation-verification.md` steps 1–3, ground-truth `recordsIn`/`recordsOut`). | **Close when that file gains a dated results section.** Nothing in-repo would otherwise show the soak had been run — the work could be done and the row would still read open. |
| **Completeness KPI hold** | Observability | K2 wiring / K4 / K5 held: whether `{seq}` restarts per hour is a carrier fact. | ⬜ **NOT RUN — there is nothing to run.** The first action is engineering, not a check: no default-on durable store holds processed filenames (`-Dfile.stages.backend` defaults to `none`, the acquisition ledger to `memory`), so a query would report "no gaps" on a stock deployment. The refusal is designed INTO K4 — `requireDurable(spaceId, family, toggle, forWhat)` reading `StoreHealth.of(spaceId)` as its first act (`superpower/completeness-kpi-k4-design.md`); ⛔ nullness cannot express it, because `AcquisitionLedgers` returns a working in-memory ledger. Open: neither filename store can be scanned over a window (K2 needs a new read method), and `file_stages` is best-effort — whether it is an acceptable KPI substrate is an operator call; nine calls are open in the design's §7. ⛔ Do not conscript `ConservationCheck.imbalances`; imitate the `RowShaper` windowed-dedup refusal. → `archived-documents/plans-archive/completeness-kpi-plan.md` §2b |
| **Release notes for the next MAJOR** | Control plane & API | **Drafted** in `okf/backend/control-plane/api-stability.md` §Release notes; append there with every further `feat!:`. ⚠ The `batch_id` rename trio rides this release and is NOT enumerated in that list — see §7. 🔴 **`RETIRE-HALVES-1`'s route removals (2026-09-14) ride this MAJOR and are typed `docs:` in git** — they landed in `519673a7`, a commit whose message describes a whitepaper change, because a concurrent session committed the staged tree under its own heading. They are enumerated under *Breaking — HTTP routes REMOVED*; ⛔ a `git log` scan for `feat!:` will not find them. | `git tag` — the next MAJOR tag. |
| **X-Actor full removal** | Security | Remove the header path entirely (already rejected outright on Standard/Enterprise). | 🔴 **Gate restated 2026-09-07.** It used to read "client migration with the API-v1 sunset" — but that apparatus was **deleted 2026-07-25**, and `api-v1.md` contains **zero** occurrences of "Actor", so the gate pointed at something that no longer exists. The only remaining exposure is Personal; a MAJOR is the sanctioned break. **Gate: the next MAJOR tag.** → `okf/backend/editions/auth-security.md` · `EDITIONS.md` SEC-11 |
| **Compliance program (NFR-7)** | Compliance | External only: C1 applicability statements, ISMS boundary, auditor engagement, pen test, C5 policy content, C6 FedRAMP package + FIPS leg (demand-gated), the ISO 8.8 advisory-watch process. | Each of the seven closes when its own row in `compliance/controls-matrix.md` §4 carries a dated line. **Check: `grep -c '^\| NFR-7 ·.*⬜ open' compliance/controls-matrix.md`** — 7 today; closes at 0. ⚠ Keep it line-anchored — unanchored, it counts its own documentation. → `compliance/controls-matrix.md` §4 |
| **SOC 2 Type II window** | Compliance | Recorded as **NOT STARTED** in `compliance/controls-matrix.md` §4a (2026-09-16), with an operator-fill start date and the preconditions for opening it. The HIPAA/PCI framework choice stays deferred until a prospect is named. | External: an auditor engaged (firm named, window agreed) plus the operator's framework call — then `start + 6 months` is arithmetic. ⛔ Do not backdate the start; ⛔ state the window's status only in §4a, never here or in `NFR-7 · N2`. |
| **SEC-INCIDENT-1 carry-forwards** | Compliance | Incident CLOSED BY DECOMMISSION 2026-08-29. Both carry-forwards are DONE (operator, 2026-09-15): the off-repo pre-rewrite backup bundle was deleted and the no-reuse check completed. Internal hostnames/IPs are still published in-repo (lower severity). | **Close when `compliance/controls-matrix.md` CC6.1 carries a dated line** confirming both — dated when the acts HAPPENED, not when they were reported; both dates are owed in §1. |
| **DATA-GOV-1 archive** | Compliance | Move the real carrier corpus to an encrypted out-of-band archive on company storage with a fetch script; access held by the data-agreement owner. | Org action. **Close when `PROJECT_NOTES.md` §DATA-GOV-1 carries the dated archive location and the fetch-script path.** 🔴 **ANSWERED 2026-09-15: NOT DONE** — neither the archive nor the fetch script exists; recorded as outstanding in `PROJECT_NOTES.md` so it stops reading as decided-and-handled. ⚠ Consequence worth naming: `asn-parser`'s corpus tests are opt-in and data-gated on this row, and `asn-parser/src/main/java` is **not dead** (it is compiled by `legacy-code/pom.xml`) — so live code is tested only against synthetic input until this lands. |
| **AGT-6b multi-step agent graphs** | Assistant | First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools. | Holds on one precondition (run 2026-09-08): the upstream approval gate is still synchronous per call (`ApprovalHandler.onApprovalRequested`), so nested gates deadlock. **Check: `gh api 'repos/jotder/inspect-agent/contents/eoiagent-core/src/main/java/com/eoiagent/host/ApprovalHandler.java' -q .content \| base64 -d`** — reopen when that signature stops being synchronous. → `archived-documents/plans-archive/agt-6-plan.md` §4 |
| **Deployment topology live validation** | Deployment & scale-out | T2/T3/T4 reference deployments, the D8 IAM pair, GAP-7 blueprints, grammar-config live smoke. | A reference deployment. Repo-side half: close when `compliance/evidence/rto-rpo-statement.md` carries operator-stated targets (⛔ do **not** transcribe the signed §3.14 engineering numbers — the targets are owed in §1) and at least one drill row; the hosts to drill on exist. → `archived-documents/plans-archive/deployment-topology-plan.md` (§10 D1–D8 signed 2026-09-06) |
| **E1 Enterprise distributed tier / Stage-2 streaming** | Deployment & scale-out | Design SIGNED 2026-09-10 — T5 partitioned scale-out by Space (`superpower/enterprise-scale-out-plan.md` §9 D1′–D13); phases A–B are also Standard's T4 DR. A0, A1 and A3 shipped (2026-09-11/12); A2 (the pool) is re-scoped Enterprise, deferrable. | External: phase A's acceptance is `PostgresStateStoreTest` 12/12, and this checkout has no Postgres, so the class skips — the coverage exists and has never executed. Stage-2 streaming stays unscoped beyond `STREAM-CONSUMER-1`. |

## 3. Open work by product area

### 3.1 Pipelines — authoring, editor & Step catalog

#### Authoring surfaces

- **P2** · **AUTHORING-REDESIGN-1** — **the authoring redesign's still-open letters.** (c), the structured AST table over the row predicate, is COMPLETE (2026-09-23; step 5 `transform.join` out of scope by design), and (f)(g)(j)(l)(m)(n2)(o) shipped. Open: **(d)** v3 macros as the UDF registry — per-connection re-creation in `EnrichmentEngine`, `PipelineJobRunner`, `ConsignmentIngestStrategy` and preview — demand-gated; **(e)** column metadata editing on the Transform pane (Parse D2) — needs a backend home for metadata on a `transform.sql` node first; **(i)** a per-row "sample resolves to" line — no host resolves a sample against an `AttributeSpec`; and on (f), which COLUMNS a reference carries is the dry run's question (the save checks existence and `on` presence only). → `archived-documents/plans-archive/authoring-ast-table-design.md` · `okf/frontend/features/schema-mapping-authoring.md` §0
- **P2** · **AI drafting on a non-`schema` kind** — trigger FIRED 2026-09-15 (an author asked). Restore `<inspecto-ai-assist>`/`component_draft` for a kind: either give `grammar`/`transform`/`sink` a backend `ConfigSpec` (none has one; `ConfigSpecs.TYPES` excludes all three) or rework `SchemaEditorDialog`. ⛔ No low-risk slice survives — **design first**; the demand answers *whether*, not *how*. → `okf/frontend/features/inline-ai-authoring.md`
- **P3** · **P4 Test mapping on a generic `parser` node** — the (l) discharge unblocked Test mapping on a *dangling per-format* grammar binding, not on a **generic** `parser`, which the owner doc calls unmappable (it falls to `GrammarEditorDialog`). Nothing authors a parse node's mapping any more — mappings are authored in the standalone Mapping component (`mapping-editor.dialog.ts`) — so this would test a mapping an operator cannot author on that node. Build only when asked by name. → `okf/frontend/features/pipeline-editor.md`
- **P3** · `PIPELINE-CONFIG-HISTORY-1` — **a Pipeline has no persisted config history.** The layout half shipped (`9eed24f8`). Proposal: a server-side snapshot per save, a version list and a diff — needs a route and a retention decision. Today undo/redo is capped at 50 per tab and lost on reload. → `okf/frontend/features/pipeline-editor.md`

#### Step catalog & node types

- **P3** · **Step Processor catalog** — ⛔ **ON HOLD (operator, 2026-09-23): no NEW Step Processors — neither the 67 planned nor completing the partials — until the existing ones are releasable.** Build only when the operator names one again. 119 processors: **34**<!--count:processors-delivered--> delivered / **18**<!--count:processors-partial--> partial / 67 planned (`processor-catalog.contract.json`). Each partial is its own product decision (Kafka consumer, XPath grammar, resampler, KPI layer, Jinja, graph tagging, commit controller, SLA object, view/email sinks…) — pick one by name. ⚠ A catalog entry is a `BuiltinNodeType` case compiled by `RecipeCompiler`/`ProcessorCatalog`, **not** the `ConsignmentProcessor` SPI; the namespace is `transform.*`; a new node type needs a flat-config home too; and both contract JSONs under `inspecto-ui/` are generated from Java and must be regenerated in the same change. → `EDITIONS.md` §Step Processors · `okf/backend/pipeline-graph/step-catalog.md`
- **P3** · `STEP-TYPES-DEAD-CLIENT-MIRRORS-1` — **`GET /pipelines/step-types` has no live UI consumer** since the Recipe view was removed (`6d3c68fa`). The client `RECIPE_VERBS`, the write-only `servedVerbs` and its fetch in `pipeline-editor.component.ts`, and the mock lift/lower port in `pipeline-editable.ts` (dead since `f1553136`, and drifted — lookup and chain `sql` drop silently, profile and webhook refuse) are held up only by specs. Operator decisions: **(A)** keep the endpoint as the published recipe vocabulary (add profile + webhook server-side only, drop the client parity spec) or retire it with its contract and count guard; **(B)** delete the dead client code (keep `isProjectionSlot`). → `okf/backend/pipeline-graph/step-catalog.md`
- **P3** · `NODETYPE-SCAFFOLD-EMITS-A-COPY-1` — **the node-type scaffold plants a private SQL-quoting copy into every generated Executor** (`tools/templates/nodetype/src/main/java/__packageDir__/__className__Executor.java:111`), so consolidated identifier quoting regrows from the template. Fixing it means settling the scaffold's dependency contract first — whether a scaffolded module has `inspecto-util` on its classpath; the template's javadoc points at a contract note that is no longer in the file. → `okf/backend/engine/node-types.md`

### 3.2 Pipelines — execution, lanes & Consignments

#### Lanes & the graph

- **P2** · **Branch-aware executor residuals** — (b), the segment-scoped lift for multi-schema + `route:`, is **BUILT 2026-09-24** (S1–S6; as-built in `branch-aware-ingest.md` § *Multi-schema route*); (a) `mode: clone` and (c) mid-branch Steps shipped earlier; the per-sink `ducklake` block shipped 2026-09-23. Still open, each waiting for a real need: **(d)** unimplemented anywhere: `adapter`, `alert`, `event`; still refused at lowering as flat homes: `transform.select/derive/validate/split/merge`, `sink.materialized/view` on ingest; **(e)** an acquisition-side "listed remotely, not yet fetched" gauge — name it first; **(f)** a *pre-fetch* intake cap — the per-cycle file cap (`-Dingest.maxFilesPerCycle`) and byte cap (`-Dingest.maxBytesPerCycle`) are live but run after listing, on local files, so saving remote fetch bandwidth needs a listing-with-sizes connector seam that does not exist (⛔ do not build (f) as a second `IntakeGovernor`); **(g)** `sinks:` follow-ups — Decision Rule routing with `sinks>1`, a versioned reference store with `sinks>1`, and a `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:`. → `okf/backend/engine/branch-aware-ingest.md` · `okf/backend/engine/output-sinks.md` · `archived-documents/plans-archive/mid-branch-transforms-design.md` · `superpower/branch-aware-segment-lift-design.md`
- **P2** · **Pipeline graph — flip the intake cap on by default** — the only open clause. ✅ Decided 2026-09-15: flip **only after a soak** — a real precondition, not waived. `IntakeGovernor` still defaults `ingest.maxFilesPerCycle` to `0` = unbounded (re-grounded 2026-09-23). Everything else this row once carried shipped: the graph-lane parity gate is MET (the whole suite under `-Dingest.lane=graph`, zero refusals), the multiplicity refusals (`MULTI_ACQUISITION` / `MULTI_GAP` / `MULTI_MARKER`) and the byte cap. → `okf/backend/pipeline-graph/editable-round-trip.md` · `okf/backend/pipeline-graph/pipeline-graph-design.md` §8
- **P2** · **Platform Services Stage 2 / 3** — Stage 2, an open Step-kind registry (`StepTypeProvider` with `LOWERED`/`EXECUTED`, `StepContext`, failure mapping, watchdog): no `StepTypeProvider` exists in any module. ✅ Decided 2026-09-15: an intervening node **may** execute at rest (`EXECUTED` anywhere), which also discharges ELT Phase 6's `graphLaneCarries` precondition (§2 Row 15). The S2-2 bridge spike (rows/s through a no-op `EXECUTED` Step vs fused) no longer blocks but is worth running — the only adjacent measurement spans a half to a thirteenth of the native rate. ⛔ Third-party `LOWERED` stays closed until a SQL-fragment guard exists. Stage 3: pack-contributed services (`ServiceProvider` SPI; a collision fails the pack atomically; reference-tracked quiesce); `DatasetAccess` after the Consignment Selector. A missing Job-side watchdog (R1) is a recorded gap; filtered `services()` on `ProcessorContext` (D4) and a devkit jar (D5) only on demand. → `okf/backend/control-plane/platform-services.md`

#### Runs, replay & dry runs

- **P2** · **EXECUTION-RESIDUALS X4 + X1 deferrals** — **X4**, record-level replay from quarantine (sidecar error manifests with offset + reason; all-or-nothing vs eject-and-continue as per-pipeline config). ⛔ **Gate still closed:** the shipped `?dryRun=true` never parses a member (`ConsignmentIngestor.process` fabricates an empty SUCCESS), so it cannot show what X4 needs. The default may be picked only once a run parses real members without landing anything and reports, per member, **which kind** of failure occurred — a validation rejection (`MemberStatus` `QUARANTINED_*`) is not a thrown fault (which fails the whole batch), and `SKIPPED_UNREADABLE` is a third thing; key any manifest on the member vocabulary. Nearest starting point: `PipelineTestRun` (the real `strategy.ingest`, zero side effects, per-FILE results at `POST /pipelines/authored/{id}/run?to=`) — the gap is per-RECORD offsets and reasons. No default picked; that is the operator's call. **X1** deferrals: a per-pipeline `processing.retry` block (regenerate the node-attributes and step-types contracts) and an operator cancel / retry-now affordance — designed in `superpower/retry-affordance-design.md`, decisions owed in its §5. → `okf/backend/pipeline-graph/execution-lanes.md` · `archived-documents/plans-archive/execution-residuals-plan.md`
- **P3** · `FLAT-DRYRUN-COUNTS-ZERO-1` — **a flat-lane dry run's provenance record shows 0 parsed / 0 landed**, because the dry run skips parsing: the overlay shows *that* it ran, not what it would move. Real counts need a parse pass on a dry run (reverses the skip — the same build X4 waits on). Demand-gated. → `okf/capabilities/pipeline-execution/pipeline-execution.md`

#### Consignments

- **P2** · **Consignment addressing** — the ingest-side Consignment-scoped accessor waits for a consumer. ⚠ `DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path. Settled — do not re-open: torn multi-file reads (closed by the pinned `ConsignmentSelector` list); `generation` IS read back (`DbConsignmentOutputStore`); `retire_superseded` is warned about, not silent, and ships as a disabled demo job (`spaces/demo/config/jobs/retire_superseded_job.toon`) — ⛔ its default is **not** flipped, because enabling retirement by default deletes bytes operators may rely on. → `okf/backend/engine/consignment-addressing.md`
- **P2** · **Consignment ELT** — the surviving item is `generation`, **kept OPEN by operator choice 2026-09-23**: it is always a literal `0` (`ConsignmentOutputs.java:375`), nothing reads it for staging, and revisions use batch-id file names instead. The per-schema outputs decision shipped 2026-09-15 (the existing `consignment_outputs` child table made per-schema accurate; `batches` stays one row per ingest). ⛔ Pointers to `CONSIGNMENT-OUTPUTS-NULLRUN-1` are dead (refuted 2026-09-15). Deliberately unbuilt: the §7.4 rollup cache (until read-time aggregation is measurably slow); the §7.3 unpartitioned fallback stands by operator call. → `okf/backend/engine/db-layer.md` §3.9

#### Admission

- **P3** · `DUCKLE-C10-ADMISSION-POOLS-1` — **named execution pools are ADMISSION ONLY.** A pool answers "may this start now" and never widens thread or memory caps; a Pipeline may choose a pool but never define one the server lacks (unknown ⇒ `default`); a queued run gets a durable id immediately with `queueReason`, becoming `running` with `queueMs`; a supervisor takes **no slot** (holding one while waiting for a child that needs the same pool deadlocks); metric = free permits per pool. ✅ The rule set is recorded in the scale-out plan §4.1 beside the `ConcurrencyBroker` seam; the FEATURE is not built. → `superpower/enterprise-scale-out-plan.md` phase B

### 3.3 Acquisition, Collectors & Parsing

#### Collectors & streaming

- **P2** · `STREAM-CONSUMER-1` — **adapter stream-consumer runtime — built, close-out owed.** Option A is BUILT (slices 0–3, 2026-09-24): the loop stays in the Collector scan, and the defect the design pass reproduced — `KAFKA-OFFSET-REKEY-1`, a remote slice's frontier never advancing — is fixed. All four design questions are answered and slice 4 was dropped. **Still open:** only the `SP-ACQ-09` cell in `EDITIONS.md`; then distil and archive the plan and delete this row. → `okf/capabilities/acquisition/acquisition.md` · `superpower/stream-consumer-design.md`

#### Unpack & codecs

- **P3** · **Unpack (11) absent codecs** — trigger: the first `.xz`/`.zst` delivery arrives (it forces the decompression-library sign-off at the same moment). ⬜ Re-confirmed NOT fired 2026-09-15 — asked and declined, so do not re-raise it as an oversight. xz and zstd have no plugin (a dependency sign-off, not a build); `.Z` has no round-trip test; multi-part/split archives (`.z01`, `.part1.rar`) are unhandled — arrival completeness is a Collector question. → `okf/backend/engine/unpack-stage.md`

#### Parsing

- **P2** · **Parsing (Stage-1)** — the ASN.1 grammar from a stored, path-jailed `.asn`/`.asn1` file shipped 2026-09-23, with its bundle residual. **Still open:** a per-vendor transform config home (the grammar file was its prerequisite); a drop-in `plugins/` jar directory (the JobPackManager classloader precedent) so a customer parser deploys without a rebuild — needs a trust decision first. ⚠ `asn-parser/src/main/java` is NOT dead (compiled by `legacy-code/pom.xml`); corpus tests are opt-in and data-gated (DATA-GOV-1). → `okf/backend/engine/parser-plugins.md`
- **P3** · `LEGACY-ASN-SRC-TREE-UNBUILT-1` — **decide the 21 uncompiled files under `asn-parser/src/test/`.** The original premise was refuted 2026-09-17: `asn-parser/src/main/java` IS compiled — `legacy-code/pom.xml` sets `<sourceDirectory>../../src/main/java</sourceDirectory>` and 41 classes ship — ⛔ **do NOT delete it.** What is true: no `testSourceDirectory` exists anywhere in the repo, so the test tree compiles on no path, and six of its files carry `*Test` names claiming coverage they do not have (`Test.java`, `ASNFileReaderTest`, `BERDecoderTest`, `SbinHuaMscAsnTest`, `FixedLengthFileReaderTest`, `RTDMS_ASN_Test`). `RTDMS_ASN_Test` was kept verbatim with an explanatory javadoc on 2026-09-17 because `asn-golden`'s `GoldenCapture` cites it as provenance for 7 of its 9 golden cases. Remaining: wire the tree, or rename/delete it — rename all six together and update `GoldenCapture`'s 8 comments in the same commit; and treat the superseded `ByteSource`/`TxConfig`/`Tag` twins in `main` as a rename/deprecation question. ⛔ Operator's call.

### 3.4 Catalog, Onboarding, Datasets & Lineage

#### Onboarding & bundles

- **P2** · **Onboarding (Stream/Reference)** — **D5-ref is now answerable:** a real delete-feed exists (the gate fired 2026-09-15). Decide how a `delete` tombstone *enters* the reference store (a reserved column? a Decision Rule consequence?) — ⛔ **ground the actual feed first**; the whole point of waiting was to pick the representation from the real shape. **D6-ref:** the within-batch same-key tie-break is arbitrary — add an optional latest-by-`order_by` column only when needed. An optional templates entry (space-template-gallery precedent). ⚠ Enrichment/job configs still derive identity from name. ⛔ Do not implement name-deferral by holding the draft client-side. → `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md`
- **P2** · **Onboarding ↔ Pipeline unification — W5 forward reference-Dataset closure** — W0–W5 shipped; W5's reverse closure shipped 2026-09-23 (an export carries the Decision Rules, Datasets, Expectations and Alert Rules that reference its Pipeline; a global Alert Rule stays behind), and import-time referential integrity closed 2026-09-20 (`POST /pipelines/import` reports an unresolved connection as a WARNING — report, not refuse, deliberately, because a pipeline bundle never carries the connection profile). **Open — operator decision:** extend `BundleExporter`/`DataSourceBundleResolver` to carry the reference Datasets a Pipeline reads (the forward closure). ⛔ `PipelineCompiler.toConfigMap` is deliberately not migrated. → `archived-documents/plans-archive/onboarding-pipeline-unification.md` · `okf/backend/pipeline-graph/editable-round-trip.md`

#### Datasets & lineage

- **P3** · **D-11 hand-authored `relations` component** — deferred until a business relation exists that no Pipeline exercises; ⬜ re-confirmed not fired 2026-09-15. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §3.4
- **P3** · `DUCKLE-C7-AFFECTED-CONTRACTS-1` — **which Pipelines does a change reach, and which contracts break?** ◐ The affected half shipped 2026-09-24: `com.gamma.service.AffectedPipelines` (`analyze` plus a `main <configRoot> <baseRev> [--fail-on-affected]` entry) reports each reached Pipeline with its chain, treats deleting a producer as a change, and marks unloadable Pipelines UNCERTAIN. **Still open, and the reason the row exists:** the reader-dependent contract verdicts — removing a column somebody reads is **breaking**, one nobody reads is **possibly breaking** (never "compatible"), anything downstream of a transform is **revalidate** (there is no column lineage) — ⛔ never collapse the three into breaking/non-breaking; plus non-Pipeline dependents in the output, enrichment-ref and job `on_pipeline` links, and CI wiring. The git question is ruled IN SCOPE: this is a CI check over a diff, not an in-app git feature. → `okf/backend/control-plane/pipeline-related.md`

### 3.5 Data quality, Observability, Signals & Alerting

#### Alerting & freshness

- **P2** · `DUCKLE-C1-DATASET-FRESHNESS-1` — **Dataset freshness: core shipped 2026-09-16, badge 2026-09-24, three residuals.** Freshness is an Alert Rule shape (`dataset:` + `maximumAge:`) evaluated against the last `dataset.write` Signal (`DatasetFreshnessProbe`), with an all-clear that is never cooldown-held; `alert.evaluate` gained `scope: freshness` (2026-09-20). ⛔ `expectedAfterSchedule` and "a missing schedule makes a Dataset stale" are **not buildable** — a Dataset has no producer link and no schedule. Open: **(1)** auto-arm a minute-cadence `alert.evaluate` instance when a `maximumAge` rule exists (a scheduling-policy question, not a thread); **(2)** owner-routed alerting — the design is decided (owner = the authenticated `Subject`, `"appUser"` where none), but the substrate is absent: `AlertRule` has no owner, `NotificationRule` hardcodes the recipient, `ChannelConfig` routes by a flat `target`, and config-authored rules are evaluated with no request `Subject` to capture — a multi-seam design pass; **(3)** duckle S2 retention, as an acceptance criterion: after `EventPruneTask` drops the partition holding a Dataset's last publication and the process restarts, a real breach becomes invisible (under-reporting) — it needs a durable last-publication record or a prune floor, not a flag; **(4)** the Dataset list badge — **SHIPPED 2026-09-24** (read-time, fail-to-*unknown*, never persisted; as-built in `docs/okf/capabilities/studio/studio.md` §3.4). ⚠ `ConfigSpecs.alert()` marks `metric`/`threshold`/`window` required, which a freshness rule omits — harmless while such rules are written through `AlertRoutes`, not `/config/write`.
- **P2** · **Completeness KPI (when the hold lifts)** — K1 (`DbConsignmentOutputStore.dailyVolume()`) and K2 (`FileSequenceGaps`; `SeqScope` already ships) are unwired — `VolumeBaseline`/`FileSequenceGaps` have no production caller; **K4**, a `kpi.completeness` job type (`JobTypeProvider` + descriptor + `ParameterDecl`s, cron'd, one config per pipeline, a Signal plus a deduped Incident on breach, refusing loudly when `-Dconsignment.outputs.backend=none`), is designed but unbuilt (`superpower/completeness-kpi-k4-design.md`); K3, the baseline-window default as a job parameter. K5 shipped. Open inside the design: `kpi.completeness.*` is a **Signal** type and there is no `SignalType` home (the dotted literals are scattered) — whether to create one; `KPI-UNKNOWN-1` — a null-`bounds` sink's daily count is **unknown, not zero**, end to end; and where the sequence template comes from (the Collector's, a job parameter, or the Collector's with an override). Held by the §2 *Completeness KPI hold*. → `okf/capabilities/observability/observability.md` §3.9 · `archived-documents/plans-archive/completeness-kpi-plan.md`
- **P3** · `DUCKLE-C8-BASELINE-EXPECTATION-1` — **a baseline QA Expectation kind:** profile the current input against the median of the last N *accepted* profiles (row count; per-column null count/rate, distinct, min, max, mean), limits in either direction, `groupBy` + `requireExistingGroups`, a profile accepted only if the whole run succeeds, audited `accept`/`clear` ops, and a refused run still records its profile. **Storage-blocked (grounded 2026-09-16):** `transform.profile` writes to the pipeline's ordinary output store; there is no profile-history store, no accepted-profile store and no `accept`/`clear` op, so the first action is **profile storage**, not the kind. The kind itself is four hand sites (`Expectation.java` `KINDS` and its constructor switch, `ExpectationEvaluator.columnPredicate`, `expectation-attributes.ts`); model it on `FileSequenceGaps`. ⚠ Expectations are not covered by `AcceptedConfigKeys` — the record's own constructor is the whole validator.

#### Signals, decisions & notifications

- **P2** · **Signal / Decision networks — cross-Space consequence** — trigger FIRED 2026-09-15: a Signal in one Space must cause something in another. Re-grounded 2026-09-17: this is structurally absent, not merely unguarded — each Space is isolated at the runtime-instance level (`EventLog` keeps one ledger per Space; `SpaceContext` gives each Space its own services), `Signal.space` is descriptive only, and there is no target-space parameter anywhere in the apply path. ⛔ No small safe increment exists — it needs a **design pass** on the cross-space controller (S8): does a consequence need its own authorization, independent of the triggering caller? Under what identity does a target Space accept an externally originated Signal? Scope to the consequence that was asked for; connector-direct emission rides with it; ⛔ RFC 6902 JSON Patch deltas for AG-UI have no consumer and do not ride along. → `okf/backend/control-plane/signal-backbone.md` §"Open / deferred" · `okf/backend/control-plane/decision-rules.md`
- **P2** · **Notification residuals (`D8-SES-SNS-1`)** — Soft-bounce retry shipped 2026-09-15. Remaining: the SES/SNS adapter — SNS subscription confirmation plus a cert-chain fetch from a validated `amazonaws.com` URL; ⚠ an outbound fetch induced by an unauthenticated callback gets its **own security review and its own commit**; GeoIP; auth-gated per-user preferences and security triggers. → `okf/backend/control-plane/events-metrics.md`

### 3.6 Analytics — Queries, BI, Studio & Export

- **P2** · **D-8 XLSX export — prove it in a shipped bundle** — the export shipped 2026-09-16 through DuckDB's `excel` extension (`PipelineDocumentXlsx`, `COPY … (FORMAT xlsx)`); ⛔ **do not add POI** (and `grep poi` over the poms matches only "point"/"policy" — match `org.apache.poi`). Open, owed to the operator: whether it works in a **shipped or air-gapped bundle**. On a clean CI runner the extension cannot load — `PipelineDocumentXlsxTest.writesARealWorkbook` now skips when it is absent — and no release has ever shipped a DuckDB extension, because CI populates no cache; staged is not loadable (autoload ignores `-Dduckdb.extension.dir`). A green reactor is not evidence this works for a customer. → `archived-documents/plans-archive/elt-final-amendment-plan.md` §9 D-8
- **P3** · **Queries / BI** — `graph`/`spatial`/`search`/`api` QueryTypes; more `$`-resolvers. (The DuckDB `spatial` extension itself: zero demand, re-verified 2026-08-26 — do not re-open on speculation.) → `okf/backend/control-plane/queries.md`
- **P3** · **EXPORT-1 outbound object-storage export (S3 / HDFS)** — sequence of record: an operator `aws s3 sync`/rclone of `data/<store>/database/` first (zero code); build the push post-action (the outbound mirror of the connector SPI, reusing `AwsSigV4`) only on demand; HDFS only via an S3-compatible gateway — ⛔ never `hadoop-client`. → `okf/backend/engine/object-storage-export.md`

Ongoing, not a row: **template seed-pack enrichment** (frontend C7) — `kpi-overview`, `quality-monitor`,
`trend-monitor` today. See [`okf/frontend/features/studio.md`](okf/frontend/features/studio.md).

### 3.7 Control plane, API & Jobs

#### Exchange & jobs

- **P2** · **Bundle / Exchange — "load as draft" import** — a per-editor import that lands as a draft instead of writing straight through (today `BundleRoutes.importBundle` over `BundleImporter`/`BundleExporter` has no draft seam and no staging state). Design first, likely multi-session. ⛔ Do not fake it with a cross-kind `enabled:false` stamp. (`requires` present-but-different classification shipped 2026-07-18.) → `okf/backend/control-plane/exchange-sharing.md`
- **P2** · **Job framework — space-to-space comparison** — ⛔ **POSTPONED by the operator 2026-09-16: design only, do not start.** The trigger fired 2026-09-15, which removed the gate but did not set a rank; the design is `superpower/space-comparison-design.md`, its four §5 decisions parked, not owed. No code exists. Predictive maintenance stays deferred to AGT-5 regardless. → `okf/backend/control-plane/jobs.md`
- **P3** · `AUDIT-LOG-UNBOUNDED-READ-1` — `JobRunLedger.java:98,124,149`, `PartitionCompactor.java:163`, `ReferenceCompactor.java:292`, `RunArtifactStore.java:60`, `RunLogStore.java:50` and `CommitLog.java:85` read whole journal files into memory on every read, with no cap. Demand-gated: stream or tail once a file is measured to matter.

#### API contract & vocabulary

- **P3** · `ERRORCODE-DEFAULTED-1` — **explicit error codes on the remaining bare throw sites.** The 403 slice is done (zero bare 403 sites; `ErrorCodes` and its constants are public; `ApiContractTest` pins the catalog). **620 of 648 `ApiException` sites still take `ErrorCodes.defaultFor(status)`** — sweep by file, re-deriving each count first (the row's original figures were wrong by ~3×). Largest as of 2026-09-17: `ComponentRoutes` 36 · `inspecto-ops/ObjectRoutes` 34 · `PipelineGraphRoutes` 30 · `ExchangeRoutes` 30 · `ReconRoutes` 26 · `AgentRoutes` 22 · `RunRoutes` 21.
- **P3** · **Vocabulary rollout, Tier 3 — the release-gated remainder** — the UI half shipped 2026-09-07 (the DTOs read the canonical `pipeline` key). What remains is **wire and needs the MAJOR**: drop `flow` from the Java JSON (`ViewDefinition.toMap`, `LineageRoutes`, `PipelineProjection`) and rename the `ViewDefinition.flow` record component, which carries `@PublicApi(since="4.0.0")`. ⛔ Do **not** rename the `"flow"` test literals (`ViewStoreTest`, `ControlApiViewsTest`) — they are the only proof the dual-read path works. The agent-tool `flow` argument stays until it dual-accepts. ⚠ When the dual-emit ends, delete `CONFIG_ALLOW['spaces/ucc/config/views/sites_active_view.toon::flow-key']` — the guard's stale-allowlist check fails the build until you do. → `GLOSSARY.md` §13 · `PROJECT_NOTES.md`

### 3.8 Security, Policy, Editions & Compliance

- **P2** · **Security: policy-authoring UX** — trigger FIRED 2026-09-15: an install had hand-edited policy TOON go wrong. A matrix/create editor beyond hand-authored TOON. The read-only Policies tab and the "why denied?" endpoint already make the mistake diagnosable, so the build is about **preventing** the error — extend those surfaces rather than duplicate them. → `okf/backend/editions/auth-security.md`
- **P3** · `DUCKLE-C6-POLICY-NARROWING-1` — **a workspace policy that can only NARROW.** Denies union, allowlists intersect, permissions AND; `mode` comes from the server file only; enforced **at plan time AND at the point of the act** (network: every hop plus DuckDB itself; state mutation: every watermark/offset advance); prefixes match at a path boundary, not as strings; a named policy file that cannot be read refuses the run. Parts exist (`PathJail`, `ConfigSafetyValidator`, `DataRef`); the structural narrowing and the unreadable-policy refusal do not. The prefix-boundary rule is the exact defect corrected in scale-out phase C §5.4.
- **P3** · `QUEUES-USER-FACING-COPY-1` — **three shipped strings still promise a capability deleted in 2026-09:** `AbsentObjectRoutes.java:27` (the Personal-edition **503 body**), `inspecto-ui/…/admin/objects/object-mail.component.html:4` and `…/admin/tags/tags.component.html:4` all read "notes, links, tags **and queues** are provided by the…". Product copy, not comments, and none is test-asserted; the two templates need the `angular-ui` skill. → `okf/capabilities/editions/editions.md`

Ongoing, not a row: the **compliance repo-side artifacts** — the customer verification runbook (G2 half), the
CI-evidence doc, a recorded restore drill (G6 — `compliance/evidence/rto-rpo-statement.md` has operator-fill
targets and an empty drill table), G8 RBAC R5 evidence and G9 FIPS. Each closes when its file says so; see
[`compliance/controls-matrix.md`](../compliance/controls-matrix.md) §4.

### 3.9 Cases, Incidents & Assistant

- **P2** · **D6 spec-authoring UI (`findings-spec`)** — trigger FIRED 2026-09-15: a **non-engineer** needs to author a `findings-spec` (today TOON through generic `/components` CRUD). The deliverable is judged by whether an analyst can use it, not by whether it is faster than the generic path. → `okf/frontend/features/objects.md`
- **P2** · **AGT-5 per-tool dry-run seam — BLOCKED-EXTERNAL** (re-gated 2026-09-16). The upstream `DryRunProvider` type ships, but the seam does not: `javap` on the pinned `eoiagent-platform` jar shows `PlatformBuilder` with `approvalHandler(...)` and `approvalDecisionStore(...)` and **no `dryRunProvider(...)`**. **Upstream ask (to `jotder/inspect-agent`): expose `PlatformBuilder.dryRunProvider(DryRunProvider)` and thread it to the gate builder.** Until then `AgentApprovals` stays as the previewer. ⛔ Do not re-discharge on the presence of the type — check the builder. `incident_explain` waits separately on the eoiagent host seam. → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2
- **P3** · `GLOSSARY-CASE-1` — **split the two `Case`s in code** · RELEASE-GATED (next MAJOR), not demand-gated. `ObjectType.CASE` (groups Incidents) keeps the word; the Assistant's `com.gamma.intelligence.investigation.Case` — one RCA playbook run against one Incident — becomes **Triage** / **Triage Run** (operator decision `D-E1`, 2026-09-22; *Investigation* belongs to Link Analysis). ⚠ The gate premise did not survive verification — `v3.11.0` contains no `AgentRoutes` and no `/agent/cases`, so the published-breakage set is empty; re-gating is an operator call and is not made here. ⛔ Do not do the non-breaking half alone (two spellings for one concept); ⛔ do not rename `mode: case` or `caseType` (different words; `caseType` feeds RBAC data scopes). Touchpoints in `GLOSSARY.md` §13.
- **P3** · `AGT-SEGMENT-1` — **the assistant's commercial framing is an unvalidated product read.** The tier packaging (A Explain / B Author-with-approval / C Bounded autonomy), the "Tier A is the wedge" argument and the SHADOW-first on-ramp were never validated against a client segment. ⛔ Decided 2026-09-10: keep the caveat and reopen on the first customer conversation — needs product input, not engineering; the framing is quoted in a stakeholder-facing doc, so it carries its caveat until this closes. → `stakeholders/PRODUCT_CAPABILITIES.md` §"How the ladder is packaged" · `archived-documents/plans-archive/agt-6-plan.md` §2

### 3.10 Deployment, Packaging & Scale-out

- **P2** · **Deployment topology gaps** — only **GAP-4**, a DuckDB `memory_limit` default, is open. Settled: GAP-3 is `DEPLOY-SERVICE-WRAPPER-1`; GAP-5 surge admission shipped as `IntakeGovernor` (its off-by-default cap is the §3.2 intake-cap row — ⛔ do not re-file it here); GAP-6 Vault/KMS is demand-gated — the `SecretsProvider` SPI seam exists, so a provider drops in with no core change; reopen only on a NAMED client policy; GAP-10 was refuted (`package.ps1` stages the whole `docs/` tree; a real bundle diff is confirmatory verification owed, not a defect).
- **P2** · `DEPLOY-SERVICE-WRAPPER-1` — **the live acceptance is UNRUN.** The wrappers shipped 2026-09-11 (`SCR-3`): a systemd unit plus `install-service.sh`, and `install-service.ps1` (a Windows Scheduled Task at boot as SYSTEM, restart-on-failure; `sc.exe` was refused — `java.exe` never reaches the service dispatcher, error 1053). **This row needs a RUN, not a build:** `kill -9` → back on `/health`, plus the reboot leg, on both platforms; the installers print the exact commands. The hosts exist (confirmed 2026-09-15); access details are owed in §1. ⛔ `SCR-3`'s acceptance stays unmet until both are run. → `okf/capabilities/editions/editions.md` §3.14 · `inspecto/package.ps1`
- **P2** · **Postgres multi-user** — trigger FIRED 2026-09-15 (a multi-operator install exists) and the §6 park is lifted. P1 (a HikariCP pool behind `JdbcDrivers`) and P2 (`browseConnection()` removed) **already shipped 2026-09-14** (`3844fc0f`, `OPS-03`) — ⛔ do not rebuild a live pool. Remaining: P3 **schema**-per-space URL wiring (⛔ NOT db-per-space); P4 a `CaseStore` interface plus a PG implementation (a JSONL ring today); `PostgresStateStoreTest` over the three uncovered stores plus a concurrency test. Keep events on Parquet. Not the same work as `OperationalDb`/PG-1 (shipped). ⚠ Acceptance needs a Postgres this checkout lacks — `PostgresStateStoreTest` skips here. → `archived-documents/plans-archive/postgres-multi-user-plan.md` §5–6
- **P2** · `SPACES-FROM-PARTITION-MAP-1` — **answer `/spaces` from the partition map, not a disk scan.** `SpaceRoutes` still answers from `api.spaces().all()` (this Pod's roster) and calls `ApiContext.podScoped(e)`; the remedy is the one the scale-out plan §5.5 sanctions (`partition.toon` already declares every Space and its owner). **Not startable:** it needs ingress path-routing first, and `grep -rn "ingress" inspecto/src/main --include=*.java` is empty — building `/spaces` alone would offer Spaces the UI cannot open. ⛔ Ingress rules must be GENERATED from the map. Once it lands, remove `podScoped: true` from `/spaces` and `/bootstrap`, keeping it only on `GET /system/scheduler`. ⚠ Blocked by construction, so it is a candidate to move to §2. → `superpower/enterprise-scale-out-plan.md` §5.3, §5.5

### 3.11 Web UI — SPA-wide hygiene

- **P3** · `EMPTY-GRID-HSCROLL-1` — **Incidents and Cases draw a horizontal scrollbar on an EMPTY grid**: their column minimum widths exceed the pane at narrower widths. Give the grid `suppressHorizontalScroll` while `rows.length === 0`, or flex-size the columns. → `okf/frontend/conventions/page-chrome.md`
- **P3** · `JOB-RUNS-DIALOG-DEAD-1` — **`modules/admin/jobs/job-runs.dialog.ts` has no opener**; only its own spec references `JobRunsDialog`. Decide retain-as-test-vehicle (the `MOCK-DEAD-COMPUTE-1` precedent) or delete; do not leave it looking like a live surface. → `okf/frontend/conventions/page-chrome.md`
- **P3** · `API-DEAD-METHODS-1` — five exported service methods have no caller in the SPA: `access.service.ts:128` `deleteProfile`, `collectors.service.ts:41` `notify`, and `config.service.ts:180, 187, 203` `previewParsing`/`previewSchema`/`previewEnrichment`. The three previews look like an intended feature that never got a pane — a product call (wire or delete), not a mechanical delete.

### 3.12 Link Analysis & Geo

No board rows, by design: [`superpower/link-analysis-backlog-plan.md`](superpower/link-analysis-backlog-plan.md)
is the **only** open backlog for Link Analysis (`INV-1` / `CP-09`) — its LA-* items, D-U* decisions and
proofs live there, and nothing pending for it lives anywhere else. Geo map deferrals are in §6.

## 4. Engineering platform — build, test, CI & tooling

#### Release & CI

- **P2** · `RELEASE-PIPELINE-NEVER-EXECUTED-1` — **the only buildable remainder is the checksum/signature split.** `release.yml` carries `workflow_dispatch`, and every signing or publishing step is gated on `github.event_name == 'push'`, so a dispatch run proves reactor-install → extension-cache → package → SBOM → smoke only. The checksum half of *Verify checksums and signatures* would be meaningful on a dispatch run; it is push-gated only because the `.asc` files do not exist there — split the two. ⛔ The tag-push half is **parked indefinitely** (operator 2026-09-20: no releases after 3.x, "just carry on master"); do not re-file it as engineering work — the first `v*` tag, if one is ever cut, IS the test. → `.github/workflows/release.yml`
- **P3** · `REACTOR-VERDICT-CI-1` — **wire `check-reactor-verdict.mjs` into `ci.yml`** (residual of `REACTOR-HALT-IS-A-SILENT-PASS-1`). ⛔ **Not pre-push**, deliberately: every other hook guard is a ~1 s repo-state check, and this one judges a BUILD — producing a log at push time means a 20-minute reactor per push. CI already runs a full reactor, so the log is free there. Operator's call to wire. → `okf/backend/build-run/build-test.md`

#### Test infrastructure

- **P3** · `TESTCONFIGS-PREFIX-SUFFIX-TRAP-1` — **the shared fixture writes a name the production scanner cannot discover.** `TestConfigs.write()` emits `pipeline_<hash>.toon`; every directory-scanning loader matches the suffix `*_pipeline.toon`. 88 test files use the fixture and load by explicit path, so the trap springs only for a test that boots by SCAN — and then presents as a JVM crash. Left as-is deliberately (88 files to fix a trap that has sprung once). ⚠ Re-rank to P2 the moment a second scan-booting test is written. → `okf/backend/build-run/build-test.md` · `inspecto-etl/src/test/java/com/gamma/etl/TestConfigs.java:113`

#### Developer tooling

- **P3** · `CODEGRAPH-AFFECTED-UNUSABLE-1` — **`codegraph affected` over-reports to uselessness; only the upstream defect is open.** On v1.6.0 an Angular service returned 953 Java test files, and `SqlGuard.java` returned 1078 when the checkout holds 1059 — it fails toward passing, so `-Dtest=` targets picked from it silently become a full reactor. The repo-side remedy shipped (`CLAUDE.md` strikes `affected`; `impact` and `query` were verified sound). Remaining: report it upstream, and re-drive both commands on any codegraph upgrade before relaxing the guidance — the check is that they stop returning cross-language and larger-than-the-corpus results.

## 5. Docs & board hygiene

- **P3** · `BOARD-STALE-HEADS-1` — **stale heads and a narrative staler than the rows.** Filed 2026-09-19 after a shift in which six of nine grounded rows were already shipped, blocked or duplicates. The 2026-09-24 consolidation removed the two shapes this page itself carried (§0 annotating rows the body closed; closed rows keeping unstruck heads). **Still open:** the candidate guard — fail when a row id appears both struck and unstruck, or when a row is ranked while its body carries a CLOSED marker; and the third shape, rows filed off one doc's prose without checking a sibling doc (the `STREAM-CONSUMER-1` / `roadmap/ROADMAP.md` §3.4 case — correct that paragraph if it still reads as unbuilt). ⚠ P3 only because it is tooling; the cost is measured in whole shifts.

## 6. Standing refusals and won't-do (not work — keep so nobody re-files)

One line each; the reasoning is in the pointer. Reopen only on the stated trigger. ⚠ A trigger audit is a
photograph, not a guarantee — fourteen triggers fired in one sitting on 2026-09-15 — so re-run it whenever
someone asks what is still gated.

#### Pipelines — authoring

- **Mapping sidecars for the committed schemas** — ⛔ decided 2026-09-10: inline `mapping:` is the norm; **0** of 24
  schemas use a `*_mapping.csv` and nothing depends on one (the single sidecar in the tree is untracked evidence).
  Trigger: an operator picks the sidecar form in the mapping editor for a committed pipeline. → `MAPPING-GEN-1` (§3)
- **`transform.merge` attributes — REFUSED 2026-09-07, the same day MERGE-ATTRS-1 was filed as a defect.**
  The row was right that `RowShaper.merge` reads `type` (`union`|`inner`|`left`) and `on` off the node config
  and that `NodeAttributes` declares neither. Its **cause and severity were both wrong.** `transform.merge`
  is absent from `PipelineEditable.LOWERABLE` and from `RECIPE_VERBS` **by decision** — the same set as
  `transform.split`/`select`/`derive`/`validate`, and `PipelineEditable:196` says admitting them would
  "silently reverse all of those". A graph carrying one therefore **refuses at save** with
  `UNSUPPORTED_NODE` (422; pinned by `ControlApiPipelineCrudTest` on the sibling `transform.derive`), and
  neither `PipelineJobRunner` graph source can carry one: the flat `pipeline_config:` path has no home for
  it, and the `pipeline:` path reads the store that refused it. So the node is executable code with **no
  authoring or persistence route at all** — it is not "stuck on union", it is unreachable. ⛔ Declaring
  attributes would hand a config pane to a node that cannot be saved, which is the exact defect the
  `transform.sql` flat-config-home lesson records. The authorable successor is the PLANNED Step Processor
  `transform.join.merge`, whose catalog entry already calls `transform.merge` "the grandfathered … read-only
  ancestor". Reopen only as an operator decision to make merge authorable — that is the four-registration
  recipe plus a deliberate reversal of a standing refusal, not a one-line attribute table.
- **EXPR-1** — expression interpolation inside a longer string only ever per-declaration opt-in, never global

#### Pipelines — execution

- **D11 caps** — `max_temp_directory_size` gets no default; preview/dry-run connections stay uncapped; the semaphore-computed cap is rejected → `okf/backend/engine/duckdb.md`
- **Kafka is not the data path** — decided in the consignment-ELT design and never re-opened: urgency is a *parameter on one node*, not a second execution model. ⛔ Do not re-file "add a Kafka lane"; a Kafka **Collector** (SP-ACQ-09) is a different, open question.

#### Acquisition & Parsing

- **Unpack (10) crash mid-archive** (moved from §4) — re-ingests committed members; relies on
  `OVERWRITE_OR_IGNORE` idempotence (`PartitionWriter:186`, documented at `UnpackOrigins:32` and
  `ConsignmentIngestor:284/508`). By design; revisit only with a measured cost. ⚠ X1's `CommitRetry` does **not**
  cover this — it records only a *returned* FAILED, and a crash writes no attempt record.
  → `okf/backend/engine/unpack-stage.md`
- **Unpack (5)(8)** — a partial archive never fails its Consignment; nested archives refused (`depth` = 1); with `processing.unpack.enabled: false` the same-file engine divergence returns (operator opt-out)
- **Time zone of incoming data (a)** — no editor for `raw.fields[].timezone_column` by decision; a fifth "data offset wins" tier is a separate build; ⛔ never reached by relaxing the `%z`/`%Z` gate → `okf/backend/engine/duckdb.md`
- **Collector rename residual** — the pipeline TOON `source:` block stays (renaming breaks authored TOON); `'SOURCE'` stage category unchanged → `okf/backend/gotchas/cross-cutting.md`

#### Catalog

- **Catalog** — offline `/db/query` returns 501 (honest degrade); `EntityProjectionGraphSource`, Geo point/route sources, `ReconExecService` stay offline arms; ⛔ "backfill the `table` attr" REFUTED — do not re-file → `okf/frontend/features/catalog.md`

#### Data quality & Notifications

- **`quality.schema.drift` refusing a file** — ⛔ decided 2026-09-10: **detection only**. A width change already
  rejects rows or quarantines; refusing a header renamed at equal width would block feeds that parse fine. Trigger:
  an operator asks for a refuse policy by name. → `okf/backend/pipeline-graph/step-catalog.md` DQ row
- **`mail.send` has no true CC** — needs a CC-aware `@PublicApi` SPI overload, not until a second caller asks; ⛔ never a second SMTP transport
- **D8** digest deliveries correlate to the digest, not per notification; `deliverWithReceipt` escape hatch only if a provider won't echo `Message-ID`

#### Control plane & Jobs

- **A removed Job pack leaving a stored pipeline unloadable** — ⛔ decided 2026-09-13: **accepted risk, not
  work.** `JobPackManager.java:278-279` already carries the exposure as its own inline comment and states the
  workaround in the same breath: *"the same exposure a Job typed on an unloaded pack already has, which is why
  a pack is normally REPLACED rather than removed."* ⚠ It sat at P3 instead, so every sweep re-grounded a
  question that had already been answered in the code. Trigger: an operator removes rather than replaces a pack
  in a live install, or a guard is wanted at unload time. *(Was `PACK-UNLOAD-EXPOSURE-1` in §3; the original
  row's grounding is preserved in git history at `fa3780e4`.)*
- **D7 startup backfill** full object scan (`ObjectService.backfillTagAssignments:479`, called from
  `CollectorService:475`) — deliberately unfixed. ⚠ **The stated trigger cannot fire as written**: "shows up in
  measured startup time", but nothing in the repo measures startup — no JMH, no timing test, no recorded
  baseline. Reopening it means *first* adding a startup measurement. → `okf/backend/control-plane/tags.md`
- **MNT-14** — no UI surface / no shipped Job instance (operator opts in); retention derived not stamped; scoped to `ObjectType.INCIDENT`; ⚠ `ObjectQuery`'s 9-arg constructor is load-bearing → `okf/backend/control-plane/jobs.md`
- **WRITE-1** — the implicit adoption ambiguity stays, documented at the code; ⛔ never teach the server the UI slug rule

#### Security & Editions

- **PATH-2 residual** (moved from §4 2026-09-07 — it is a LEAVE, not work) — the `BackupTask.restore` zip-slip
  jail is PINNED by `MaintenanceLibraryTest.restoreRefusesAnArchiveEntryThatEscapesTheTargetBeforeWritingAnything`
  (the page cited a `…ASidecarEntry…` variant that does not exist — a tampered sidecar is refused a layer
  earlier). Family (a), the three store `fileFor` helpers — `ViewStore:100`, `PipelineStore:101`, `ComponentStore:351`
  and `PipelineWatermarkStore:56` — **four sites, not three** (recounted 2026-09-07), none importing `PathJail` — LEAVE unless someone is in those files anyway; their line
  numbers have now drifted three times, which is itself the argument. ⛔ Routing `ControlApi.serveStatic:866`
  through `PathJail.contains` is a posture change needing an operator call — grounded 2026-08-26 "do not build it".
  → `okf/backend/config/config-safety.md`
- **`endSessionUrl` / server-published OIDC config** — not buildable as scoped; 🔴 if ever built, `session.service.ts` uses `??` so a server-sent empty string beats `environment.oidc` → `okf/backend/editions/auth-security.md`

#### Cases, Incidents & Assistant

- **Publishing eoiagent `0.1.0` to a registry (`EOI-7b`)** — ⛔ decided 2026-09-15: **CI keeps building it
  from the upstream tree.** Moved here from §2, where it had sat as an externally-gated item implying
  someone was waiting to publish; nobody was. ⚠ The licence blocker that would have prevented publishing is
  gone either way — `eoiagent-parent` declares **Apache-2.0** since 2026-09-14 — so this is a deliberate
  choice, not an inherited constraint. 🔴 **The cost is now PERMANENT rather than temporary**: neither
  `ci.yml` nor `release.yml` pins a `ref:`, so both follow that repo's default branch, and a commit pushed
  to the wrong branch there reaches no CI while looking landed — which has already happened once. The `ref:` pin was
  answered 2026-09-16: pin `release.yml` only. Trigger: a consumer outside this repo needs the artifacts, or
  the upstream build time becomes a CI constraint.
- **`AiDraft.prerequisites` shared applier** — single producer; extract only when a second tool gains prerequisites
- **AGT-6a tool `args` runtime validation** declined (contract test instead) — revisit after all **23** tool
  schemas are audited; still 23 (`InspectoPackTest:61` pins the count) and the audit has **not** run: the
  2026-07-27 cross-adopter pass covers **5 of 23** — its 6 payloads span 5 distinct tools (`query_author`
  twice), and the test asserts nothing about its own list's size. Precondition unmet.
- **AGT-5 embedding recall** parked (`CaseStore` is a 256-cap ring)

#### Deployment & Packaging

- **PKG-5** agent-absent is the intended shipped default; ⛔ no `package.ps1` switch until the JDK 25+ vs Java 24+ floor is resolved → `okf/backend/build-run/build-test.md`
- **The connector sidecar is NOT edition-gated** (CONNECTORS-BUNDLE-1, shipped 2026-09-07) — remote
  acquisition is a core capability and `EDITIONS.md` marks SFTP shipped in all three editions, so
  `inspecto-connectors.jar` rides every bundle. ⚠ It costs ~32 MB (BouncyCastle via sshj, plus
  kafka-clients). Reopen ONLY if Personal must be leaner than that — the copy is one `if` in
  `package.ps1`, but gating it means correcting the SP-ACQ rows to match.

#### Web UI

- **BUNDLE-1 perf question** — `no-cache` on content-hashed chunks vs `immutable`; unmeasured; only if a revalidation storm is observed

#### Link Analysis & Geo

- **Geo map** — DuckDB `spatial` extension deferred (zero `ST_*` demand); progressive loading obsoleted by `GEO_POINT_CAP = 5000` → `okf/frontend/features/geo-map.md`

#### Engineering platform

- **ARCH-OPS-SCC** LEAVE (85-file ripple — recounted 2026-09-07, still **exactly** 85) · **ARCH-F-CARVEOUT**
  LEAVE (148 refs / 28 files — recounted, 27 files, module split unchanged) · `{etl, etl.unpack}` and
  `{agent.kernel.*}` SCCs LEAVE · intra-module `ops↔ops.link/workflow`, `catalog↔catalog.spi` cycles are
  same-family · **M2** `CollectorService` decomposition → `okf/backend/modules/reactor.md`
- **C2** store-pair base — reopen at the **7th** store; recounted 2026-09-07: **4 true `InMemory*`/`Db*` pairs**
  (Object, Link, Note, TagAssignment), 5 counting `DbStatusStore` by shape. Not close. · **C4** BOM — reopen on
  an external consumer; there is none and **nothing is published as a Maven artifact** (releases ship zip
  bundles; eoiagent is an upstream dependency, not a consumer). · **C6** connection reuse — warm open **24 ms**
  (min 23 / max 27, n=20), no contradicting measurement exists. ✅ **HOMED 2026-09-10 (Sprint 7.2)** in
  [`okf/capabilities/editions/editions.md`](okf/capabilities/editions/editions.md) §6.1 — they were archived
  without being distilled, and `reactor.md`, which this row pointed at, never held them. 🔴 **Two counts here
  were wrong and are corrected at the new home:** `C2` is **half shipped** — the `Db*` side landed 2026-08-18
  as `AbstractJdbcStore` (`JAVA-5`), *20 days before* the recount that called it untouched, with **five**
  subclasses; and the true-pair count is **5**, not 4 (`DeliveryReceiptStore` became a pair on 2026-09-07, the
  recount's own date). What is actually left is the **`InMemory*` half** — all ten implement their interface
  with no shared base. ⛔ Anchor any future reading on the item's TEXT: that plan uses `C2` for two different
  items and `reactor.md` uses `C2`/`C4` for unrelated things.
- **`inspecto-query`/`inspecto-job`/`inspecto-enrich` module extraction** (moved from §4; the row said
  `fp-*`, stale since the artifactIds became `inspecto-*`) — build only on explicit request. Measured
  2026-09-07: `job` → `signal` + `ops`, but **`query` → `signal` only** and **`enrich` → neither**, so the
  old "`query`/`job` depend on `signal` + `ops`" claim was over-broad and `enrich` is the one clean
  candidate. `SharedDottedPathGrammarTest` still up-imports `notify` and would still need cutting.
  → `okf/backend/modules/reactor.md`

#### Docs & vocabulary

- **Vocabulary: the living-system terms are ADOPTED, not proposed** (row corrected 2026-09-07) — `GLOSSARY.md`
  already carries **Signal**, **Consequence**, **Decision Engine** and **Result Set** as binding (the last three
  annotated "§6-proposed → binding (R5)"). Only *Query* and *Parameter* were never formally adopted; use them
  as ordinary words, not as capitalized concepts. → `okf/living-operational-system.md` §Vocabulary
- **`-Dassist.token` / `-Dassist.read.token` mentions in the docs — LEAVE THEM** (from `DOC-DEADTOKEN-1`,
  closed 2026-09-14). Every remaining mention is a HISTORICAL record of the removal — ⛔ a symbol sweep that
  "fixes" them undoes the closure. ⚠ Do not confuse them with **`-Dassist.write.root`**, a different, shipped
  and live key; grep the symbol from the repo root. → `okf/backend/build-run/operations-reference.md`

#### Cross-cutting

- **Decided 2026-09-06, keep as designed:** the fetch lane stays FIFO (revisit on the first observed fetch-lane wait) · `requireTopLevelSinks` is a depth rule, not a jail · bounce/complaint handling stays manual until receipts persist · JAVA-SIMP-2 stops at seam #2 (no defect hangs on the sink casts) · the `batch_id` trio rides the MAJOR (release notes hold it) · D-7 `materialized` is done-by-absence · ~~**Postgres multi-user is PARKED** until a multi-operator install exists~~ 🔴 **PARK LIFTED 2026-09-15 — the trigger FIRED**: the operator confirmed a multi-operator install, so the row is re-ranked P2 in §3. ⛔ Do not re-file this as a park · unpack roll-up + entry grain ratified · SEC-07 Vault/KMS only when a client policy requires it · deployment D1–D8 signed as recommended (🔴 **D3 was signed as a 2GB default that DOES NOT EXIST** — corrected 2026-09-09; `DuckDbUtil.memoryLimit(null)` returns `null`, no `scheduler.toon` ships, and this file's own GAP-4 row says so. See `okf/capabilities/editions/editions.md` §5.3 and `okf/capabilities/pipeline-execution/pipeline-execution.md` §2.4).
- **Working as designed** (from the archived gate register §5): write-root 503 · `ConfigSafetyValidator` 422 · `PathJail` 403 · 409 conflict · `ExpressionGuard` · `SqlGuard` · BI share tokens · active-pipeline delete refusal · Incident resolution backend-gated · editions = build flavors · ~~`AuditTrail` has no *authentication* events~~ ✅ **REVERSED 2026-09-17**: `AuditTrail.authentication` records `auth.exchange` / `auth.refresh` / `auth.logout`, refusals as `ACCESS_DENIED` (`ControlApiAuthSessionV1Test.sessionLifecycleIsAuditedRefusalsIncluded`); the IdP's own credential check (MFA, password) stays out of scope; *authorization* decisions were always in (`access.denied`/`access.granted`, ABAC A5) · air-gap CI · append-only registry · manifest owns existence · nothing prunes by default · 🔴 ~~`-Djobs.maxConcurrentRuns` is the only bound~~ **STALE — refuted by D11 and by a second bound** (corrected 2026-09-09): the Run cap is **ON by default at 4** in code (`JobService.DEFAULT_MAX_CONCURRENT_RUNS`), owned by `scheduler.toon`, with the flag a bootstrap default only; and the INGEST engine has its **own** semaphore plus a per-pipeline `PipelineRunGuard`. Two bounds, neither governed solely by that flag — owner §2.5/§3.3 · refused: Spring/Quarkus, distributed-by-default, per-record lineage, Lens-as-permission, PIP-1, sink-owned `partitions`, Decision-Rule + `route:`, raw `Connection`, `CREATE MACRO` outside AUTHORING-REDESIGN-1 (d), step-workbench S3.

## 7. Duplicate map (same work, several names — update all when closing)

| Canonical row | Also appears as |
|---|---|
| Row 15 — ELT Phase 6 deletion half (§2) | pipeline-spec §12 row 15 · Platform Services Stage 2 precondition (§3) · 🔴 ~~`BatchGraphRunner` parity blocker~~ **THAT CLASS DOES NOT EXIST** (the live one is `ConsignmentGraphRunner`) (corrected 2026-09-09 — see §3) · §5 "archive pipeline-spec + waves-plan when Row 15 closes" |
| D13 parser field tiers (§2) | `superpower/parser-field-tiers-interview-plan.md` (the interview-#2 kit, back in the active tier 2026-09-16) · `okf/frontend/features/grammar-config.md` (the pre-agreed analysis rule) |
| AGT-5 `DryRunProvider` (§2) | AGT-6b row (§2) · `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2 |
| EXECUTION-RESIDUALS X4 record-level replay (§3) | `okf/frontend/features/run-detail.md` + `USER_GUIDE.md` "reprocess is whole-batch only" · `INDEX.md`'s `EXECUTION-RESIDUALS-SKETCHES` pointer (which cites §4 — the row is in §3) |
| `batch_id` rename trio — §6 (decided: rides the MAJOR) + §2 release notes | `okf/backend/control-plane/api-stability.md` §Release notes (D-12) · `archived-documents/plans-archive/consignment-elt-architecture.md` deferred renames · `elt-final-amendment-plan.md` D-12 / Phase 7 · `okf/backend/engine/db-layer.md` (cites §4 — no such row) |
| X-Actor full removal (§2) | `okf/backend/editions/auth-security.md` §Still-open · `EDITIONS.md` SEC-11 · `REQUIREMENTS.md` R4. 🔴 The §2 row's stated gate ("the API-v1 sunset") names apparatus **deleted 2026-07-25**, and `api-v1.md` never mentions X-Actor — re-state the gate before working it |
| Completeness KPI hold (§2) | Completeness KPI K2/K4/K5 (§3) · `archived-documents/plans-archive/completeness-kpi-plan.md` |
| Compliance program NFR-7 (§2) | SOC 2 Type II window (§2) — the same observation window, twice in one table |
| Deployment topology live validation (§2) | Deployment topology gaps (§3) · §6 "D1–D8 signed as recommended" — the §3 row's gate is already discharged |
| Postgres multi-user — 🔴 §6 park LIFTED 2026-09-15 (trigger fired), now a P2 build row | §3 Postgres multi-user row — now says PARKED and points at `plans-archive/` (this row's "contradicts §6 / dead pointer" note was stale by 2026-09-08); `EDITIONS.md` OPS-03 |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays authoritative),
then **delete the row here**. Do not leave a strikethrough as-built narrative behind — the page grew to
505 KB that way before the 2026-09-06 consolidation, and to 644 KB before the 2026-09-24 one. This page
lists **open work only**; new rows go under their product area and functionality.
