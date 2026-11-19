---
type: Plan
title: Product maturity assessment — every client-facing item — Per-item assessments — add-ons for operations, investigation and AI
description: Part 3 of 8 of Product maturity assessment — every client-facing item: 
tags: [maturity, assessment, rfp, competitive, enterprise, product]
timestamp: 2026-10-20T00:00:00Z
---

# Product maturity assessment — every client-facing item — Per-item assessments — add-ons for operations, investigation and AI

Part 3 of 8 of [Product maturity assessment — every client-facing item](plan-maturity-assessment.md) · previous: [Per-item assessments — platform base](plan-maturity-assessment-platform-base.md) · next: [Per-item assessments — add-ons for risk, assurance, data and Link Analysis](plan-maturity-assessment-addons-risk.md).

## 6. Per-item assessments (continued)

### B. Add-ons — operations, investigation and AI

#### addon.incidents — Incidents

- **What it is (customer view):** Alert Rules fire Alerts, and Alerts are promoted into Incidents. Analysts triage, assign, annotate,
  post-mortem and resolve Incidents in a mail-like inbox. Notifications and retention sit around that.
- **Scores:** F 4 · B 4 · Q 4 · E 4 · R 3 → **Level L3 (Pilot-ready)**, close to L4
- **Evidence:**
  - `addon/incidents/plan-incidents.md` §2: INC-1 Alert Rules and INC-2 Alert → Incident → Case are SHIPPED. INC-3 notifications and
    INC-5 Diagnosis are PARTIAL. INC-4 (queues, watchers) was WITHDRAWN 2026-09-14 under `RETIRE-HALVES-1`.
  - `module/ops/api-routes.md`: 43 routes, all 43 in the OpenAPI contract. `module/ops/feature-db.md` has about 55 feature rows.
  - `module/ops/tests-inventory.md`: 70 test classes (47 unit, 23 real-HTTP). They include `ControlApiIncidentFinishGateTest`,
    `ControlApiObjectReadMaskingTest`, `ControlApiScopedObjectsTest`, `PostgresTwoNodeSoakTest`, `ObjectAnalyticsGoldenTest` and
    `IncidentPurgeTaskTest`.
  - UI: 16 vitest specs under `inspecto-ui/src/app/modules/admin/objects/`, for example `object-mail`, `postmortem-panel`, `resolve.dialog`
    and `impact-panel`. Plus `settings/incident-governance.component.spec.ts`.
  - Retention: `incident_purge` with a fail-safe legal hold (MNT-14). The event ledger is append-only. By decision no purge job is shipped
    on a schedule (`module/ops/plan-ops.md`).
  - Committed demo config: 2 Alert Rules under `spaces/demo/config/registry/alert-rules/`, plus
    `spaces/demo/config/ops/incident_burst_caserule.toon` and `critical_incidents_tagrule.toon`.
  - Open work is only P3 chores: `MODULE-REORG-P7-INCIDENTS` and `DOCS-SKILLS-ALERTS-AGENTIC-1` (`addon/incidents/index.md`).
- **Strengths vs market:**
  - Integrated Alert → Incident → Case chain inside the assurance product. The matrix rates RAFM peers P–Y on N (case) and N–P on O
    (workflow and SLA). Peers usually hand this off to an external ITSM.
  - Server-side resolve gate (four post-mortem sections) and MTTR analytics as a Dataset (`objects.analytics`). Object reads are masked
    and scoped.
- **Gaps vs market / RFP risk:**
  - No group or queue routing and no watchers. Assignment is to a person only, and on-call rotation is absent. These are standard
    ServiceNow/Jira lines (general market). Retired by decision, **no row** (plan says re-file on customer demand).
  - Diagnosis → Incident (the defining clause of INC-5) is absent. Verified: no `IncidentAccess`/`INCIDENT` reference in
    `features/inspecto-agent/.../diagnose/`. **No row**: it is kept in spec §5 as UNTRACKED.
  - No native ITSM connector (ServiceNow/Jira). The only routes out are the webhook channel and Action Requests. **No row.**
  - Retention covers `ObjectType.INCIDENT` only, has no UI and has no shipped Job (BACKLOG §6 `MNT-14`).
  - Per-user notification preferences: the plan says "one global set". Per-user read state shipped 2026-09-25 (BACKLOG line 388). Per-user
    *delivery* preferences are unverified.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Configurable lifecycle with assignment and SLA | Comply | ops workflow, plus the workflowSla add-on (`GovernanceSweepTest`) |
| Group or queue routing, on-call escalation | Partial | escalation rules can reassign; queues and watchers retired (`RETIRE-HALVES-1`) |
| Email and webhook notifications, per-user preferences | Partial | channels on Standard and Advanced; preferences global (plan §2 INC-3) |
| Audit trail and record retention / legal hold | Comply | `OBJECT_*` events in an append-only ledger; MNT-14 legal hold (Incident only) |
| MTTR / KPI reporting | Comply | `objects.analytics` → `ops_analytics`, `ObjectAnalyticsGoldenTest` |

- **To reach the next level:** (1) Decide on team queues or assignment groups and file a row. (2) Build Diagnosis → Incident (deduped)
  or retitle INC-5. (3) Add one end-to-end delivery test against a real SMTP and webhook endpoint (plan §8.7 names it absent).

---

#### addon.caseManagement — Case Management

- **What it is (customer view):** related Incidents are grouped into a Case for one business investigation. The add-on covers merge and
  split, Case Rules, dispositions, Findings fields, and opening a Case from Link Analysis entities.
- **Scores:** F 3 · B 3 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/case-management/plan-case-management.md`: roadmap C1–C6, I1 and I2 shipped 2026-07-12. The `caserule.evaluate` Job Type exists.
    `POST /cases/from-entities` is capped at 100 entities. Optimistic lock returns 409, and a real-Postgres two-node soak was run on one
    Case.
  - `module/case-management/api-routes.md`: 7 routes. `feature-db.md` has about 8 rows.
  - `module/case-management/tests-inventory.md`: 21 test classes (15 unit, 6 real-HTTP). They include `CaseRuleEvaluateGoldenTest`, the
    TCK and manifest-parity tests, `ControlApiCaseTriageGateTest` and `ControlApiScopedCaseMergeTest`.
  - UI specs: `merge-cases.dialog`, `case-contents`, `case-analytics.dialog`, `findings-spec-editor` (2 specs).
  - Committed Case Rules: `spaces/demo/config/ops/incident_burst_caserule.toon` and
    `spaces/_templates/telco-fraud/config/ops/fraud_critical_burst_caserule.toon`.
  - Left (plan): the Incident resolution gate is "not started". The disposition list is not deployment-configurable. Case analytics as a
    Studio Dataset is still a draft.
  - Open: P3 defect `LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1` (item 1 masking is done; upload hardening is open) and P3 `D6-REAL-USER-SESSION-1`
    (Findings fields accepted on proxy only).
- **Strengths vs market:**
  - A Case can be opened in place from a Link Analysis working set, and an entity's case history can be read (`GET /objects/by-entity`).
    That LA-to-case link is rare outside i2/Palantir-class tools (general market).
  - Rule-raised Cases group by offender (`CaseRuleGroupByTest`) and have custom Findings fields. Merge and split are capability-gated and
    scoped (SEC-7d).
- **Gaps vs market / RFP risk:**
  - Attachments are references only, with no document upload (refused by design, `plan.md` §Refused). Actimize/SAS case managers store
    evidence documents (general market). **No row.**
  - Dispositions are not configurable per deployment, and the resolution hard-gate is not built. **No row** (listed only in plan Left).
  - No retention or legal hold for Cases: MNT-14 is Incident-only (BACKLOG §6 `MNT-14`). AML case records usually have multi-year retention.
  - No case queues, workload balancing or auto-merge suggestions (refused, design §3). Case-desk usability has never been tested with a real
    user (`D6-REAL-USER-SESSION-1`).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Group alerts or incidents into cases (manual and rule-based) | Comply | Case Rules plus `caserule.evaluate`, `ControlApiCaseRuleTest` |
| Evidence / document attachments | Partial | references only; no upload (refused) |
| Configurable case fields and dispositions | Partial | Findings spec is configurable; disposition list is fixed |
| Investigation link analysis from the case | Comply | `POST /cases/from-entities`, `ControlApiCaseFromEntitiesTest` |
| Case audit trail and retention | Partial | audited via the object ledger; no Case retention policy |

- **To reach the next level:** (1) File and build the Case retention / legal-hold extension of MNT-14. (2) Make the disposition list
  configurable and build the resolution gate. (3) Run the real Case-desk session (`D6-REAL-USER-SESSION-1`).

---

#### addon.workflowSla — Workflow & SLA

- **What it is (customer view):** authored lifecycles (Workflows), SLA policies counted in a business calendar, and Escalation Rules on
  Incidents and Cases. A background sweep stamps deadlines and escalates once per breach.
- **Scores:** F 3 · B 4 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/workflow/plan-workflow.md`: `ASSURE-WORKFLOW-SLA-1` closed 2026-10-04. `ASSURE-MAKER-CHECKER-1` shipped 2026-09-27. The graphical
    state-diagram editor shipped 2026-10-11 (`workflow-state-editor.component.ts`). `ASSURE-WORKFLOW-SLA-RESIDUALS-1` closed 2026-10-18.
  - `features/inspecto-ops/.../module.toon` has `configKinds[3]: workflow,sla-policy,escalation-rule`. The add-on feature-db has about
    70 lines (`addon/workflowSla/feature-db.md`).
  - Tests: `module/workflow/tests-inventory.md` lists 4 unit classes (`SlaDecisionsTest`, `SlaPolicyTest`, `WorkflowTest`,
    `WorkflowValidationTest`). The ops module adds `GovernanceSweepTest`, `GovernanceRegistrySameTickTest`, `ControlApiWorkflowStrandTest`
    and `WorkflowConfigLoadTest`. UI: `incident-governance.component.spec.ts`.
  - No Space Template ships an active Workflow, SLA policy or Escalation Rule, by design. Only illustrative
    `spaces/_templates/*/config/examples/sla-escalation-examples.md` files exist.
  - No open rows (`addon/workflowSla/index.md`).
- **Strengths vs market:**
  - The matrix row O rates the RAFM peers N/P and Inspecto Y. SLA runs in business calendars, escalation fires once, and config changes go
    through maker-checker four-eyes.
  - Governance kinds are written only through `canAdminister` routes, and authored changes are re-read without a restart.
- **Gaps vs market / RFP risk:**
  - Applies only to Incident and Case. Policies are keyed by the closed `ObjectType` enum, so reconciliation breaks and Entity List reviews
    are not covered (plan-incidents last para). **No row**: it waits for a second consumer.
  - No assignment groups, round-robin, skills routing or on-call calendars, which are ServiceNow-class basics (general market). **No row.**
  - A deleted assignee is not re-checked at sweep time. Retired 2026-10-18 because no principal directory exists to ask.
  - Thin direct test depth: 4 unit classes in the module and no real-HTTP class in `module.workflow`.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Configurable workflow states and transitions (graphical) | Comply | `workflow` kind plus the state editor (2026-10-11) |
| SLA targets per priority in a business calendar | Comply | `sla-policy`, `SlaPolicyTest`, `GovernanceSweepTest` |
| Escalation on breach (reassign, priority, notify) | Comply | `escalation-rule`, `OBJECT_ESCALATED` |
| Group / queue routing and workload balancing | Not offered | queues retired 2026-09-14 |
| Workflow on any work item (breaks, reviews, tasks) | Partial | Incident and Case only (Task has a type; generic contract deferred) |

- **To reach the next level:** (1) Add a real-HTTP test of the SLA sweep → breach → escalation chain. (2) Ship one optional, opt-in SLA
  example as a pack. (3) Decide on and file routing groups.

---

#### addon.actionRequests — Action Requests

- **What it is (customer view):** a governed outbound API call raised from an Incident or Case, by a person or by a Decision Rule's
  `invoke-api` consequence. It is sent only after a *different* person approves it, and every answer is recorded.
- **Scores:** F 3 · B 4 · Q 3 · E 4 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/action-requests/plan-action-requests.md`: `ASSURE-ACTION-REQUESTS-1` shipped 2026-09-27. `WEBHOOK-EGRESS-POLICY-1` closed 2026-09-27. The
    approver roster and `approverCheck` landed 2026-09-28 and 2026-10-04. Absent module → every route answers 503.
  - `module/action-requests/api-routes.md`: 7 routes, all in OpenAPI. `feature-db.md` has about 8 rows.
  - `module/action-requests/tests-inventory.md`: 8 classes (6 unit, 2 real-HTTP). They include `ActionDispatcherTest`,
    `InvokeApiConsequenceContractTest` and `ControlApiSpaceBundleActionRequestsTest`. TLS pinning and redirect refusal are tested in
    `HttpWebhookSinkTransportTest` and `PinnedHttpTlsTest` (`spec.md` §Deferred).
  - Gating: grep of the module source finds `canApproveChanges` (17 uses) and `canWorkIncidents` (6).
  - Known gaps, kept without rows (`spec.md` §Deferred): no per-request path below a Connection's base path, and no request history in a
    restored Space. No automatic re-send after a crash (refused).
  - No open rows.
- **Strengths vs market:**
  - Every outbound action is four-eyes approved and sent to an egress-policy-pinned `https` Connection with an idempotency key. The matrix
    notes no RAFM peer publishes four-eyes on outbound actions.
  - Detection → governed action closes the loop without an external SOAR.
- **Gaps vs market / RFP risk:**
  - No packaged ITSM or core-system adapters (ServiceNow, Jira, BSS barring). It is generic HTTP only. **No row.**
  - No bulk approve, no request templates library and no per-request sub-path. **No row** (sub-path is a known gap).
  - Request history is lost on Space restore. Known gap, **no row**.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Trigger an external action from a detection or case | Comply | `invoke-api` consequence, `InvokeApiConsequenceContractTest` |
| Maker-checker approval on actions | Comply | always-on four-eyes, `ActionRequestApproverCheckTest` |
| Secure, allowlisted egress | Comply | egress policy plus pinned TLS (`PinnedHttpTlsTest`) |
| Out-of-the-box ITSM / BSS connectors | Not offered | generic HTTPS Connection only |
| Full action audit and retry | Partial | every answer recorded; manual `mark-failed` then retry; history not kept across restore |

- **To reach the next level:** (1) Ship one packaged ITSM action template (ServiceNow or Jira) with a test. (2) Carry request history
  through Space backup and restore. (3) Add a real-HTTP test for the approve → dispatch → failure → retry path.

---

#### addon.aiAssist — AI Assist & Intelligence

- **What it is (customer view):** an Assistant with seven read-only or draft-only skills on Standard. Advanced adds an embedded
  intelligence layer: a 23-tool belt, inline natural-language authoring and an autonomy ladder. Every action is read-only or
  approval-gated, and local or air-gapped models are supported.
- **Scores:** F 2 · B 3 · Q 3 · E 3 · R 3 → **Level L3 (Pilot-ready, supervised)**
- **Evidence:**
  - `module/agent/plan-assistant.md` §2: AGT-1 (7 skills), AGT-2 (pluggable transport incl. Ollama), AGT-3 (air-gap; `EgressGuardTest`) and
    AGT-4 are built. AGT-6a is partial: `<inspecto-ai-assist>` on 5 panes and `<inspecto-ai-explain>` on 12. AGT-6b is only planned.
  - `module/intelligence/plan-intelligence.md`: AGT-5 P0–P5 complete, with 23 tools pinned by `InspectoPackTest`. Egress masking
    (`AGENT-EGRESS-MASK-1`) and the model allowlist (`ASSIST-MODEL-EGRESS-1`) are in.
  - Packaging: `tools/bundle-modules.mjs` stages `inspecto-agent.jar` from Standard and `inspecto-intelligence.jar` from Advanced.
  - Tests (inventories): agent 28 classes (25 unit, 3 real-HTTP), intelligence 35 classes (all unit, 0 HTTP), agent-hosted 2. UI: about 15
    assist/ai specs.
  - `pom.xml` pins `<eoiagent.version>0.2.0-SNAPSHOT</eoiagent.version>`. CI rebuilds the upstream from a branch head
    (plan-assistant §2 EOI-7), so the runtime dependency is unpublished.
  - Open: P2 `AGENTIC-INVESTIGATION-1` (competitive gap AA), P2 AGT-5 per-tool dry-run seam (BLOCKED-EXTERNAL, BACKLOG line 552) and P3
    `AGT-SEGMENT-1`.
- **Strengths vs market:**
  - Air-gapped, local-model AI with hosted SDKs physically absent, proven by a test. That is a strong answer for regulated on-prem buyers.
  - An explicit governance model (approval inbox, autonomy ladder, egress masking of tool results) where many copilots are
    SaaS-only (general market).
- **Gaps vs market / RFP risk:**
  - No agent works an Incident: nothing explains, gathers evidence or proposes an action. Mobileum RAID Agentic and Subex AI Agent Squads
    have this (matrix row AA = P). Row `AGENTIC-INVESTIGATION-1` (P2).
  - Diagnosis does not open an Incident, and Diagnosis and the agent case store are 256-entry in-memory rings that are not durable
    (plan-incidents §5 *TriageRunStore*, parked). Embedding recall is parked.
  - Supply chain: the agent runtime is a SNAPSHOT built from source (EOI-7b open, plan-assistant §5.1). An enterprise security review will
    flag it.
  - The intelligence module has no real-HTTP tests, and the commercial tiering is unvalidated (`AGT-SEGMENT-1`).
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Natural-language query and explanation of entities and alerts | Comply | `KpiToSqlSkill`, `ExplainEntitySkill`, `<inspecto-ai-explain>` on 12 panes |
| AI-assisted triage / investigation summary | Partial | `DiagnoseAndAlertSkill`, `ReportNarrativeSkill`; no Incident-working agent |
| Agentic automation with human approval | Roadmap | `AGENTIC-INVESTIGATION-1`; AGT-6b planned |
| On-prem / air-gapped LLM | Comply | Ollama provider, `EgressGuardTest` (AGT-3) |
| AI governance: audit, approval, data masking | Comply | approval gate, upstream EOI-4 audit, `AGENT-EGRESS-MASK-1`; dependency is a SNAPSHOT |

- **To reach the next level:** (1) Ship `AGENTIC-INVESTIGATION-1` with an eval suite. (2) Publish and pin a released eoiagent version
  (EOI-7b). (3) Make Diagnosis and triage durable (`TriageRunStore`) and add real-HTTP tests for `/agent/*`.

---

#### addon.anomalyDetection — Anomaly Detection

- **What it is (customer view):** an explainable 0–100 Anomaly Score per entity. It compares the entity with its own seasonal baseline
  and with its peers, can use an isolation forest, and can raise Incidents through Alert Rules.
- **Scores:** F 3 · B 3 · Q 4 · E 3 · R 3 → **Level L3 (Pilot-ready)**
- **Evidence:**
  - `module/anomaly/plan-anomaly.md` lists what is built:
    - `ANOMALY-DETECTION-1` S1–S5 (2026-10-10)
    - `ANOMALY-PEER-GROUPING-1`: 200k entities in about 1–1.6 s
    - `ANOMALY-ML-SCORING-1`: `method: isolation_forest`, closed 2026-10-20
    - evidence rows masked at write
  - `module/anomaly/api-routes.md`: 2 routes. `feature-db.md` has about 4 rows.
  - `module/anomaly/tests-inventory.md`: 23 classes (21 unit, 2 real-HTTP). They include `AnomalyGoldenCorpusTest`,
    `AnomalyPeerGoldenCorpusTest`, `PeerSideEquivalenceTest`, `AnomalyForestScorerTest`, `PackFraudAmlAnomalyPropertyTest` and
    `TelcoFraudPackAnomalyPropertyTest`. UI: 6 specs.
  - Pack content: anomaly models in the `aml`, `telco-fraud` and `mobile-money` Space Templates
    (`spaces/_templates/*/config/registry/anomaly-models/`).
  - Live evidence: the `ANOMALY-UI-FINDINGS-1` live-verification lane on a demo build, 2026-10-13 (BACKLOG line 597).
  - Open P3 defects: `ANOMALY-TF-STORE-CLASH-1` (`AnomalyTemplateModelsTest...[1]` errors on clean master) and `ANOMALY-UI-FINDINGS-1`
    (live token-lapse check).
  - Masking and gates (`spec-anomaly-scores.md`): entity key masked unless `canRevealLinkEntities`. Read is gated by `canWorkIncidents` and
    preview by `canAuthorWorkbench`. Score output has "no retention" (line 164).
- **Strengths vs market:**
  - Explainable per-feature scores that can be recomputed, with peer-cohort shift and masked evidence rows. That transparency is a
    regulator-friendly answer that black-box ML vendors struggle with (general market).
  - Ships pre-wired in three industry packs and feeds the Alert → Incident chain without new plumbing.
- **Gaps vs market / RFP risk:**
  - Batch and daily only: hour buckets are deferred (BACKLOG §6) and there is no real-time scoring. FICO Falcon and Feedzai score in-stream
    (general market). See `REALTIME-DECISIONING-1`; no anomaly-specific row.
  - No supervised models, feedback loop from case dispositions, model drift monitoring or champion/challenger. **No row.**
  - Score history has no retention policy. **No row.**
  - A test errors on clean master (`ANOMALY-TF-STORE-CLASH-1`). The matrix row L still says "statistical, not ML", which is stale since
    2026-10-18.
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Unsupervised / behavioural anomaly detection per entity | Comply | robust baseline plus isolation forest, golden corpora |
| Explainability of each score | Comply | per-feature explanation, `AnomalyScorer.recompute`, evidence rows |
| Peer-group analysis | Comply | Peer Groups, `PeerSideEquivalenceTest`, 200k in about 1.6 s |
| Real-time / intraday scoring | Not offered | daily buckets; hour buckets deferred |
| Model lifecycle (feedback, drift, champion/challenger) | Not offered | no row |

- **To reach the next level:** (1) Fix `ANOMALY-TF-STORE-CLASH-1` and close the live (h) check. (2) Add score-history retention and a
  disposition → model feedback signal. (3) File a sub-day scoring row against a named pack need.

---

#### addon.multiEntity — Multi-entity / Group

- **What it is (customer view):** group companies, or a regulator and its reporting entities, share a Dataset, Widget, saved view or
  Signal type between Spaces. Sharing is per item, opt-in and read-only, through approved Share Grants, as a snapshot or live.
- **Scores:** F 4 · B 4 · Q 4 · E 4 · R 4 → **Level L3 (Pilot-ready)** — module scores at L4. *(Calibrated 2026-10-20 to **L3**: the platform enterprise baseline is L3 and no item can exceed it — this item's own scores support **L4 once the platform gaps in §4.1 close**.)*
- **Evidence:**
  - `module/exchange/plan-exchange.md`: the offer / request / approve ledger is built, with snapshot and live delivery and version pin with drift.
    It also lists `EXCHANGE-OWNING-SPACE-AUTHZ-1` (P1, closed 2026-09-28), `SHARE-SNAPSHOT-ON-APPROVE-1`,
    `EXCHANGE-SNAPSHOT-MULTIPOD-1` and `BI-MASKING-EXCHANGE-SNAPSHOTS-1` (2026-10-13).
  - `module/exchange/api-routes.md`: 12 routes, all in OpenAPI. `feature-db.md` has about 13 rows.
  - `module/exchange/tests-inventory.md`: 17 classes (8 unit, 9 real-HTTP). They include `ControlApiExchangeOwningSpaceAuthzTest`,
    `ExchangeSnapshotMultiPodTest` and the end-to-end `CrossSpaceFraudIncidentAcceptanceTest`. UI: about 6 sharing specs.
  - Gating (grep of the module source): `canRequestShares`, `canOfferDatasets`, `canOfferSignals`, `canRender*`.
  - Open: only P3 `IMPORT-WHOLE-SPACE-SPLIT-1` (demand hold) and the "load as draft" browser pass.
  - Known limits (`architecture.md` §9): Signals cross only within one Pod and are never queued. Duplicate `EXCHANGE_REFRESHED`
    events are possible from two pods.
- **Strengths vs market:**
  - The matrix says no RAFM peer shows a multi-entity / group-regulator model on its public pages. Here sharing fails closed and masks under
    the consumer's own entity types.
  - Sharing needs no reprocessing: the consumer's Widgets, Queries and Alert Rules read the owner's Dataset under a grant.
- **Gaps vs market / RFP risk:**
  - No central rule library pushed to entities. Rules travel by Metadata Bundles with copy semantics; sharing them is out of scope by
    design (`plan.md` §Refused). **No row.**
  - Cross-Space Signals need a single Pod and are not queued. Known limit, **no row**.
  - A live-mode grant stays fully masked until its owner registry is read (BACKLOG §6, retired).
  - There is no group roll-up hierarchy (parent → subsidiaries with automatic consolidation) as a concept, only per-item grants. Unverified
    whether any pack models it. **No row.**
- **RFP stance on the 5 most-asked requirements:**

| Requirement | Stance | Evidence or caveat |
|---|---|---|
| Segregated data per entity / OpCo | Comply | one Space per entity; owning-Space authz (`ControlApiExchangeOwningSpaceAuthzTest`) |
| Consolidated group / regulator view | Comply | shared Widgets and views render-only; snapshot and live delivery |
| Controlled, consented data sharing | Comply | offer → request → approve grant ledger, fail closed |
| Central rule library across entities | Partial | Metadata Bundles (copy), not live-shared |
| Masking of shared sensitive data | Comply | `BI-MASKING-EXCHANGE-SNAPSHOTS-1`; live grants fully masked |

- **To reach the next level:** L5 needs reference-customer evidence; none is recorded. Meanwhile: (1) Record a multi-host deployment
  soak. (2) Queue cross-Pod Signals. (3) Decide on a group roll-up / central-rule story and file a row.

---

#### Group notes

- **Documentation is wrong in places; the code was checked.** `addon/incidents/plan-incidents.md` has stale claims:
  - §5 and §8.7 say the authoring form cannot express a Measure rule. `inspecto-ui/.../alert-rule-attributes.ts` now has a `measure` kind.
  - §8.7 says "zero committed Case Rules / Tag Rules". Two `*_caserule.toon` files and one `*_tagrule.toon` file exist.
  - `module/agent/plan-assistant.md` §2 says AGT rows are "no edition" and §5.2 says intelligence is "bundled by nothing".
    `tools/bundle-modules.mjs` stages `inspecto-agent` from Standard and `inspecto-intelligence` from Advanced.
  - Matrix row L calls anomaly "not ML", but an isolation forest shipped 2026-10-18.
- **Common gaps across the ops add-ons:** no queue or group routing (deliberately retired), no native ITSM connector, and retention
  limited to Incidents. Cases, anomaly scores and action-request history have no retention or legal hold. Most of these have **no BACKLOG
  row**, which is itself a finding.
- **Process:** all ops/case tests run only under `-Pedition-standard|enterprise` (plan-incidents §8.1). No Playwright/e2e covers these
  panes; the only e2e spec is `inspecto-ui/src/e2e/backend-smoke.spec.ts`. Live evidence is limited to the anomaly UI lane, the D6
  proxy session and the Postgres two-node soak.
- **Not verified here:** per-user notification delivery preferences, whether anomaly models are under maker-checker, and any
  reference-customer use. L5 is not awarded anywhere.
