---
type: TestsInventory
title: Case management and objects — tests inventory
item: module.ops
description: Generated list of the test classes for module.ops, with how to run them.
tags: [tests, module]
---

# Case management and objects — tests inventory (`module.ops`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-ops/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.ops`](../../../../../product-map/module/ops.toon).

70 test class(es): 47 unit, 23 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-ops
mvn -o test -pl features/inspecto-ops -Dtest=AlertMigrationOpsTest,AlertServicePersistenceTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AlertMigrationOpsTest` | `com.gamma.alert` | unit | `features/inspecto-ops/src/test/java/com/gamma/alert/AlertMigrationOpsTest.java` | The one-shot ALERT adoption (MODULE-REORG-P7-INCIDENTS slice 2) against a REAL seeded {@link ObjectService}, and the |
| `AlertServicePersistenceTest` | `com.gamma.alert` | unit | `features/inspecto-ops/src/test/java/com/gamma/alert/AlertServicePersistenceTest.java` | Phase-2 promotion: a fired alert (the runtime half of {@code diagnose-and-alert}) is persisted as a |
| `PerEntityAlertObjectsTest` | `com.gamma.alert` | unit | `features/inspecto-ops/src/test/java/com/gamma/alert/PerEntityAlertObjectsTest.java` | ASSURE-PER-ENTITY-ALERTS-1 against the REAL object engine: a healed key's Incident moves through the real |
| `ScalarMeasureAlertObjectsTest` | `com.gamma.alert` | unit | `features/inspecto-ops/src/test/java/com/gamma/alert/ScalarMeasureAlertObjectsTest.java` | ASSURE-PER-ENTITY-ALERTS-RESIDUALS-1 (2) against the REAL object engine: a scalar (no-{@code by}) Dataset |
| `ControlApiAccessDeciderObjectsTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiAccessDeciderObjectsTest.java` | The ABAC A3 row-level PEP on the operational-objects routes (no policy module — a fake |
| `ControlApiDbBrowserOpsTablesTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiDbBrowserOpsTablesTest.java` | The operational-store half of the raw table browser, split out of {@code ControlApiDbBrowserTest} when |
| `ControlApiDecisionRuleApplyTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiDecisionRuleApplyTest.java` | The Incident-promoting half of {@code POST /decision-rules/{name}/apply}, split out of |
| `ControlApiExpectationTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiExpectationTest.java` | Real-HTTP + real-DuckDB tests for the ING-6 Expectation engine ({@code /expectations*}): authored CRUD |
| `ControlApiFindingsImpactFieldTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiFindingsImpactFieldTest.java` | FINDINGS-EDITOR-PER-CASE-TYPE-1, option (c) (operator, 2026-10-06): every Case Findings form carries a BUILT-IN |
| `ControlApiFindingsSpecTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiFindingsSpecTest.java` | Configurable Findings sections over real HTTP (C3 / BACKLOG D6): {@code GET /findings/{type}} resolves |
| `ControlApiFindingsWriteTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiFindingsWriteTest.java` | {@code PUT /objects/{id}/findings} over real HTTP, with an armed {@code Authenticator} (operator decision |
| `ControlApiImpactTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiImpactTest.java` | WS-10 ({@code ASSURE-IMPACT-LEDGER-1}): {@code PUT /objects/{id}/impact} records an Incident's or Case's typed |
| `ControlApiIncidentFinishGateTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiIncidentFinishGateTest.java` | INCIDENT-FINISH-GATE-1 (operator, 2026-09-26: "narrow route"): an analyst holding {@code canWorkIncidents} can |
| `ControlApiInertObjectsTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiInertObjectsTest.java` | An object of a type this build does not know (a legacy {@code ALERT} row, or a module that is not installed) is |
| `ControlApiNoteRoutesTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiNoteRoutesTest.java` | BACKLOG D10 over real HTTP: the kind-addressed {@code /notes/{targetKind}/{targetId}/…} surface. |
| `ControlApiObjectReadMaskingTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiObjectReadMaskingTest.java` | Read-time masking of the {@code key.<column>} attributes on {@code GET /objects} and {@code GET /objects/{id}} |
| `ControlApiObjectsByEntityTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiObjectsByEntityTest.java` | {@code GET /objects/by-entity} over real HTTP — an Entity's case history. One test per gate: 401 with no |
| `ControlApiObjectsPageTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiObjectsPageTest.java` | {@code GET /objects} cursor pagination over real HTTP (api-contract-design §7): on the {@code /api/v1} |
| `ControlApiObjectsTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiObjectsTest.java` | Phase-2 Alert Center routes over real HTTP: {@code /objects} query + by-id, the ack/resolve lifecycle, |
| `ControlApiScopedObjectsTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiScopedObjectsTest.java` | SEC-7d data-scoped grants over real HTTP ("a fraud analyst sees fraud cases"): a scoped Subject gets a |
| `ControlApiTagRoutesTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiTagRoutesTest.java` | Tags + Tag Rules over real HTTP (GLOSSARY §9): the tag registry and rule CRUD behind the |
| `ControlApiTriageGateTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiTriageGateTest.java` | Incident/Case triage gates over real HTTP, with an armed {@code Authenticator} whose Subjects carry the |
| `ControlApiWorkflowStrandTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ControlApiWorkflowStrandTest.java` | ASSURE-WORKFLOW-SLA-1: a Workflow PUT / restore / DELETE that drops a state live objects still occupy is refused |
| `NoActionRequestsWithOpsAloneTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/NoActionRequestsWithOpsAloneTest.java` | MODULE-REORG-P7: a Standard install WITHOUT inspecto-action-requests - this module's own test class path has |
| `NoCaseManagementWithOpsAloneTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/NoCaseManagementWithOpsAloneTest.java` | MODULE-REORG-P7: a Standard install WITHOUT inspecto-case-management - this module's own test class path has |
| `ObjectsBackendEditionBootTest` | `com.gamma.control` | unit | `features/inspecto-ops/src/test/java/com/gamma/control/ObjectsBackendEditionBootTest.java` | {@code OBJECTS-BACKEND-DEFAULT-MEMORY-1} (operator decision 2026-09-25), through the real boot path — |
| `OpsJobTypesRunInAServiceTest` | `com.gamma.control` | unit | `features/inspecto-ops/src/test/java/com/gamma/control/OpsJobTypesRunInAServiceTest.java` | The Job Type {@code inspecto-ops} contributes through {@code ServiceLoader} — {@code objects.analytics} |
| `OpsLinkedSubjectsTest` | `com.gamma.control` | unit | `features/inspecto-ops/src/test/java/com/gamma/control/OpsLinkedSubjectsTest.java` | MODULE-REORG-P7 step 3: {@code inspecto-ops} contributes the {@code incident} and {@code case} linked-subject |
| `OpsWritersUnmodelledKeysTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/OpsWritersUnmodelledKeysTest.java` | {@code MODULE-REORG-P4-2} (the ops writers): Tag and Tag Rule persist (the Case Rule writer moved to inspecto-case-management) what they REBUILD from a typed |
| `ProfessionalAlertRulesAcceptedTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-ops/src/test/java/com/gamma/control/ProfessionalAlertRulesAcceptedTest.java` | The STANDARD half of `PROCESSOR-RELEASE-READINESS-1` G9 for Alert Rules: with this module on the |
| `IncidentPurgeTaskTest` | `com.gamma.job` | unit | `features/inspecto-ops/src/test/java/com/gamma/job/IncidentPurgeTaskTest.java` | {@code incident_purge} — Incident retention with legal-hold rules (BACKLOG D5 / MNT-14). |
| `ObjectsAnalyticsJobTest` | `com.gamma.job` | unit | `features/inspecto-ops/src/test/java/com/gamma/job/ObjectsAnalyticsJobTest.java` | The {@code objects.analytics} Job Type: samples {@link ObjectService#analytics(ObjectType)} for each object |
| `CreateIncidentConsequenceTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/CreateIncidentConsequenceTest.java` |  |
| `DbObjectStoreTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/DbObjectStoreTest.java` | {@link DbObjectStore} over an in-memory DuckDB: INSERT/SELECT round-trips every column (incl. the |
| `FindByAttributeFragmentParityTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/FindByAttributeFragmentParityTest.java` | {@link ObjectStore#findByAttributeFragment}: the in-memory default and {@link DbObjectStore} scan the SAME capped |
| `GovernanceRegistrySameTickTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/GovernanceRegistrySameTickTest.java` | A same-size rewrite of an SLA policy inside one mtime tick must be re-read: keyed on (mtime ms, size) the registry |
| `GovernanceSweepTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/GovernanceSweepTest.java` | ASSURE-WORKFLOW-SLA-1 — the authored governance an {@link ObjectService} reads from its registry: a Workflow that |
| `IncidentAccessTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/IncidentAccessTest.java` | The {@code incidents} Platform Service (S1-4): opening under the active-object convention — one |
| `InertObjectTypeTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/InertObjectTypeTest.java` | A stored row whose {@code type} this build does not know (a legacy type such as {@code ALERT}, or the type of a |
| `InMemoryObjectStoreTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/InMemoryObjectStoreTest.java` | {@link InMemoryObjectStore}: create/get, duplicate + missing guards, mutating update, filter/sort/page. |
| `ObjectAnalyticsGoldenTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/ObjectAnalyticsGoldenTest.java` | Golden pins for {@link ObjectService#analytics(ObjectType)} (ANALYTICS-CORE-1 S8): the rollup's exact output — |
| `ObjectCoreTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/ObjectCoreTest.java` | The Phase-2 value types: {@link OperationalObject} (builder/withStatus/toMap), {@link ObjectType} |
| `ObjectServiceRaceTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/ObjectServiceRaceTest.java` | {@link ObjectService} read-modify-write is race-free on the optimistic version: writers that each change a |
| `ObjectServiceTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/ObjectServiceTest.java` | The {@link ObjectService} orchestrator: opening an object in its initial state, walking the |
| `ObjectStoreContractTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/ObjectStoreContractTest.java` | The {@link ObjectStore} contract for a <b>Case</b> (the store a Case lives in — there is no separate |
| `OpsEditionFeaturesTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/OpsEditionFeaturesTest.java` | LA-EDITIONS-1 phase 3 (ED-4): {@code inspecto-ops} - the module only Standard, Advanced and Preview bundle - is what brings |
| `PostgresTwoNodeSoakTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/PostgresTwoNodeSoakTest.java` | The two residuals of the "Postgres multi-user" row that need no second host, on <b>real PostgreSQL</b>: |
| `RetentionSweepSeamTest` | `com.gamma.ops` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/RetentionSweepSeamTest.java` | The MNT-14 retention-sweep seams: purge-<b>eligibility</b> selection (G1), legal hold (G5), the bulk |
| `DbLinkStoreTest` | `com.gamma.ops.link` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/link/DbLinkStoreTest.java` | {@link DbLinkStore} over an in-memory DuckDB: add/incident round-trip and the case-group |
| `LinkCoreTest` | `com.gamma.ops.link` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/link/LinkCoreTest.java` | The Phase-4 OBJECT_LINK value type + both stores: validation/normalisation/{@code other()}, the |
| `NoteCoreTest` | `com.gamma.ops.note` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/note/NoteCoreTest.java` | The Phase-4-follow-up note value type + both stores: the comment/attachment factories (attachment |
| `ObjectTagProjectionTest` | `com.gamma.ops.tag` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/tag/ObjectTagProjectionTest.java` | D7 phase 2: the assignment store is the truth for an object's tags and the legacy |
| `RepoSpacesOpsConfigLoadTest` | `com.gamma.ops.tag` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/tag/RepoSpacesOpsConfigLoadTest.java` | The inspecto-ops half of the committed-Space-config sweep: the two authored kinds whose real loaders |
| `TagAssignmentCoreTest` | `com.gamma.ops.tag` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/tag/TagAssignmentCoreTest.java` | Core behaviour of the cross-entity tag graph (BACKLOG D7), mirroring {@code NoteCoreTest}. |
| `TagRuleFilterParityTest` | `com.gamma.ops.tag` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/tag/TagRuleFilterParityTest.java` | Golden corpus for {@link TagRule.Filter#matches} — the set of objects each criterion selects, pinned |
| `TagRuleTest` | `com.gamma.ops.tag` | unit | `features/inspecto-ops/src/test/java/com/gamma/ops/tag/TagRuleTest.java` | Tag + Tag Rule domain semantics (GLOSSARY §9): filter matching (every set criterion must hold; |
| `NoteRoutesContractTest` | `com.gamma.opsapi` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsapi/NoteRoutesContractTest.java` | {@link NoteRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `ObjectRoutesContractTest` | `com.gamma.opsapi` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsapi/ObjectRoutesContractTest.java` | {@link ObjectRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `OpsRoutesManifestParityTest` | `com.gamma.opsapi` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsapi/OpsRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `TagRoutesContractTest` | `com.gamma.opsapi` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsapi/TagRoutesContractTest.java` | {@link TagRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-1 P5a): no host, no processor boot. |
| `WorkflowConfigLoadTest` | `com.gamma.opsboot` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsboot/WorkflowConfigLoadTest.java` | Validates that {@code *_workflow.toon} overrides are loaded, registering the valid ones and |
| `ObjectsAnalyticsTckTest` | `com.gamma.opsjob` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsjob/ObjectsAnalyticsTckTest.java` | {@link ObjectsAnalytics} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). |
| `OpsBackgroundManifestTest` | `com.gamma.opsjob` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsjob/OpsBackgroundManifestTest.java` | {@code MODULE-REORG-1 P4f}: what the {@code ops} manifest declares as gateable non-Job work equals what is actually |
| `OpsJobTypeManifestTest` | `com.gamma.opsjob` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsjob/OpsJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). |
| `OpsMaintenanceTasksTckTest` | `com.gamma.opsjob` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsjob/OpsMaintenanceTasksTckTest.java` | {@link OpsMaintenanceTasks} against the platform's MaintenanceTaskProvider TCK (MODULE-REORG-1 P5b). |
| `OpsSignalsTest` | `com.gamma.opsjob` | unit | `features/inspecto-ops/src/test/java/com/gamma/opsjob/OpsSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `ObjectFamiliesOperationalDbTest` | `com.gamma.service` | unit | `features/inspecto-ops/src/test/java/com/gamma/service/ObjectFamiliesOperationalDbTest.java` | The four Operational Object families, contributed by this module (MODULE-REORG-P1-FAMILY): the roster they join, |
| `PostgresOpsStoreTest` | `com.gamma.service` | unit | `features/inspecto-ops/src/test/java/com/gamma/service/PostgresOpsStoreTest.java` | DAT-6, the {@code com.gamma.ops} half — the four operational stores (object / link / note / |
| `PostgresSchemaPerSpaceOpsTest` | `com.gamma.service` | unit | `features/inspecto-ops/src/test/java/com/gamma/service/PostgresSchemaPerSpaceOpsTest.java` | Schema-per-space on <b>real PostgreSQL</b> for the four operational-object families (objects / links / notes / |
| `StoreFamilyParityTest` | `com.gamma.service` | unit | `features/inspecto-ops/src/test/java/com/gamma/service/StoreFamilyParityTest.java` | MODULE-REORG-P1-FAMILY parity pin: how EVERY operational-store family is addressed (label, properties, |
