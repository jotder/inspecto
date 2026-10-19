---
type: TestsInventory
title: Workflow and SLA models — tests inventory
item: module.workflow
description: Generated list of the test classes for module.workflow, with how to run them.
tags: [tests, module]
---

# Workflow and SLA models — tests inventory (`module.workflow`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-workflow/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.workflow`](../../../../../product-map/module/workflow.toon).

4 test class(es): 4 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-workflow
mvn -o test -pl platform/inspecto-workflow -Dtest=SlaDecisionsTest,SlaPolicyTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `SlaDecisionsTest` | `com.gamma.workflow` | unit | `platform/inspecto-workflow/src/test/java/com/gamma/workflow/SlaDecisionsTest.java` | The pure SLA / escalation decisions over a {@link GovernedItem}: no store, no engine. |
| `SlaPolicyTest` | `com.gamma.workflow` | unit | `platform/inspecto-workflow/src/test/java/com/gamma/workflow/SlaPolicyTest.java` | ASSURE-WORKFLOW-SLA-1 — SLA targets counted in a business calendar with an explicit IANA zone. |
| `WorkflowTest` | `com.gamma.workflow` | unit | `platform/inspecto-workflow/src/test/java/com/gamma/workflow/WorkflowTest.java` | The Workflow Engine: the built-in lifecycles, case-insensitive matching, and {@code .toon} authoring. |
| `WorkflowValidationTest` | `com.gamma.workflow` | unit | `platform/inspecto-workflow/src/test/java/com/gamma/workflow/WorkflowValidationTest.java` | ASSURE-WORKFLOW-SLA-1 — an authored workflow is refused fail-closed ({@link Workflow#problems}). |
