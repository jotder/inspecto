---
type: TestsInventory
title: Case Management — tests inventory
item: module.case-management
description: Generated list of the test classes for module.case-management, with how to run them.
tags: [tests, module]
---

# Case Management — tests inventory (`module.case-management`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-case-management/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.case-management`](../../../../../product-map/module/case-management.toon).

22 test class(es): 16 unit, 6 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-case-management
mvn -o test -pl features/inspecto-case-management -Dtest=BypassBoxCaseRuleTest,PerEntityAlertCaseRuleTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `BypassBoxCaseRuleTest` | `com.gamma.alert` | unit | `features/inspecto-case-management/src/test/java/com/gamma/alert/BypassBoxCaseRuleTest.java` | {@code TELCO-BYPASS-SIMBOX-1} slice B3 (decision D-BP3: the box Incident is the Case anchor). The SHIPPED |
| `PerEntityAlertCaseRuleTest` | `com.gamma.alert` | unit | `features/inspecto-case-management/src/test/java/com/gamma/alert/PerEntityAlertCaseRuleTest.java` | ASSURE-PER-ENTITY-ALERTS-1 against the REAL object engine and a Case Rule: an existing Case Rule groups the |
| `CaseRuleEvaluateRunInAServiceTest` | `com.gamma.control` | unit | `features/inspecto-case-management/src/test/java/com/gamma/control/CaseRuleEvaluateRunInAServiceTest.java` | {@code caserule.evaluate} (contributed by this module through {@code ServiceLoader}) RUN inside a real |
| `CaseRuleUnmodelledKeysTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/CaseRuleUnmodelledKeysTest.java` | {@code MODULE-REORG-P4-2} for the Case Rule writer (moved from {@code OpsWritersUnmodelledKeysTest}, MODULE-REORG-P7): an |
| `ControlApiCaseFromEntitiesTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/ControlApiCaseFromEntitiesTest.java` | {@code POST /cases/from-entities} over real HTTP (LA-CASE-CREATE-IN-PLACE-1, operator decision 2026-09-23: |
| `ControlApiCaseGroupTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/ControlApiCaseGroupTest.java` | Case group management over real HTTP (GLOSSARY §9): {@code POST /objects/{id}/merge}, |
| `ControlApiCaseRuleTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/ControlApiCaseRuleTest.java` | Rule-raised cases + case analytics over real HTTP (C5/C4): {@code /cases/rules} CRUD behind the |
| `ControlApiCaseTriageGateTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/ControlApiCaseTriageGateTest.java` | The Case-management routes stay {@code canAdminister} (merge / split / Case-Rule evaluate), proven with an armed |
| `ControlApiScopedCaseMergeTest` | `com.gamma.control` | real-HTTP control-plane test | `features/inspecto-case-management/src/test/java/com/gamma/control/ControlApiScopedCaseMergeTest.java` | SEC-7d on the Case merge route (moved from {@code ControlApiScopedObjectsTest}, MODULE-REORG-P7): the by-id scope guard only |
| `CaseRuleEvalJobTest` | `com.gamma.job` | unit | `features/inspecto-case-management/src/test/java/com/gamma/job/CaseRuleEvalJobTest.java` | The {@code caserule.evaluate} Job Type: evaluates a saved {@link CaseRule}, grouping matching in-window |
| `ObjectServiceCaseGroupTest` | `com.gamma.ops` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/ObjectServiceCaseGroupTest.java` | Case group management (GLOSSARY §9 — Split &amp; Merge) at the service level: member {@code CONTAINS} |
| `ObjectServiceCaseParityTest` | `com.gamma.ops` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/ObjectServiceCaseParityTest.java` | P7 Case Management characterisation (MODULE-REORG-P7, step 0): pins the Case behaviour that |
| `ObjectServiceCaseRuleTest` | `com.gamma.ops` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/ObjectServiceCaseRuleTest.java` | Rule-raised cases (GLOSSARY §9, C5): {@link ObjectService#evaluateCaseRule} groups matching |
| `ObjectServiceEntityCaseTest` | `com.gamma.ops` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/ObjectServiceEntityCaseTest.java` | {@link ObjectService#openCaseFromEntities} at the service level (LA-CASE-CREATE-IN-PLACE-1): the Entity |
| `CaseRoutesContractTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRoutesContractTest.java` | {@link CaseRoutes} against the platform test kit's RouteModule TCK (MODULE-REORG-P7 step 2): every mutating Case route is capability-gated, so no exemptions. |
| `CaseRoutesManifestParityTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRoutesManifestParityTest.java` | The module's {@code module.toon} {@code provides.routes} must list EXACTLY the routes its {@code RouteModule}s |
| `CaseRuleEvaluateGoldenTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRuleEvaluateGoldenTest.java` | Golden pins for {@link CaseOperations#evaluateCaseRule} (ANALYTICS-CORE-1 S8), captured from the JVM-side walk |
| `CaseRuleEvaluateTckTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRuleEvaluateTckTest.java` | {@link CaseRuleEvaluate} against the platform's JobTypeProvider TCK (MODULE-REORG-1 P5b). |
| `CaseRuleGroupByTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRuleGroupByTest.java` | A Case Rule with {@code groupBy} groups ONE offender's Incidents into a Case; two offenders never merge. |
| `CaseRuleJobTypeManifestTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseRuleJobTypeManifestTest.java` | {@code provides.jobTypes} of this module's manifest equals the Job Types its providers register (P4e). |
| `CaseSignalsTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/CaseSignalsTest.java` | Pins every emitted Signal type byte-for-byte: they are persisted and matched, so a rename is a data break. |
| `RepoSpacesCaseRuleLoadTest` | `com.gamma.ops.cases` | unit | `features/inspecto-case-management/src/test/java/com/gamma/ops/cases/RepoSpacesCaseRuleLoadTest.java` | The inspecto-case-management half of the committed-Space-config sweep: every authored {@code *_caserule.toon} |
