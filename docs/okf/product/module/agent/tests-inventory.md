---
type: TestsInventory
title: Assistant agent — tests inventory
item: module.agent
description: Generated list of the test classes for module.agent, with how to run them.
tags: [tests, module]
---

# Assistant agent — tests inventory (`module.agent`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-agent/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.agent`](../../../../../product-map/module/agent.toon).

28 test class(es): 25 unit, 3 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-agent
mvn -o test -pl features/inspecto-agent -Dtest=AssistAuditTest,AssistEndToEndTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AssistAuditTest` | `com.gamma.agent` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/AssistAuditTest.java` | P4: the agent emits one {@link AgentCompleted} per call through the injectable {@code AuditSink} — |
| `AssistEndToEndTest` | `com.gamma.agent` | real-HTTP control-plane test | `features/inspecto-agent/src/test/java/com/gamma/agent/AssistEndToEndTest.java` | P2 end-to-end test: the real {@link UccAssistAgent} (agent module) wired into a live |
| `AssistMetricsTest` | `com.gamma.agent` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/AssistMetricsTest.java` | Per-intent counters (v4.1, B2): tally OK/unavailable/repaired and forward to the wrapped sink. |
| `AssistModelEgressTest` | `com.gamma.agent` | real-HTTP control-plane test | `features/inspecto-agent/src/test/java/com/gamma/agent/AssistModelEgressTest.java` | ASSIST-MODEL-EGRESS-1: {@code POST /assist/settings/test} (and every provider the assist agent builds) goes |
| `EgressGuardTest` | `com.gamma.agent` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/EgressGuardTest.java` | P4 security guard (R3 — fail-closed egress by packaging). M3 ships <b>local-only</b>: no |
| `KpiToSqlEndToEndTest` | `com.gamma.agent` | real-HTTP control-plane test | `features/inspecto-agent/src/test/java/com/gamma/agent/KpiToSqlEndToEndTest.java` | End-to-end test for the {@code kpi-to-sql} hero skill (M6): the real {@link UccAssistAgent} wired |
| `NoopAssistAgentTest` | `com.gamma.agent` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/NoopAssistAgentTest.java` | Proves the v3.0 (M0) cross-module assist SPI wiring end-to-end: the optional agent module |
| `UccConfidenceEstimatorTest` | `com.gamma.agent` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/UccConfidenceEstimatorTest.java` | U1.5: proves the {@link UccConfidenceEstimator} scoring and the {@link EscalationPolicy} abstain |
| `AiDescriptionProviderTckTest` | `com.gamma.agent.catalog` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/catalog/AiDescriptionProviderTckTest.java` | {@link AiDescriptionProvider} (over a deterministic fake model) against the platform's DescriptionProvider TCK (MODULE-REORG-1 P5b). |
| `AiDescriptionProviderTest` | `com.gamma.agent.catalog` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/catalog/AiDescriptionProviderTest.java` | P3 tests for the AI {@link com.gamma.catalog.spi.DescriptionProvider}, CPU-only (a |
| `FailureReactorTest` | `com.gamma.agent.diagnose` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/diagnose/FailureReactorTest.java` | P1 (v3.7.0, M7): the deterministic, model-free core of the failure reactor — the |
| `ModelDiagnoserTest` | `com.gamma.agent.diagnose` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/diagnose/ModelDiagnoserTest.java` | Golden tests for {@link ModelDiagnoser} (M7, event-driven mode) — CPU-only. Proves the abstain-safe |
| `GoldenEvalTest` | `com.gamma.agent.eval` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/eval/GoldenEvalTest.java` | The golden-eval net (Gap E), now running on the kernel's {@code agent-eval} harness (U1.6). The same |
| `EventLogAuditSinkTest` | `com.gamma.agent.kernel.observe` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/kernel/observe/EventLogAuditSinkTest.java` | S1 (event-signal-backbone-plan §4.2): {@link EventLogAuditSink} bridges the agent-kernel's |
| `AssistModelSettingsTest` | `com.gamma.agent.model` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/model/AssistModelSettingsTest.java` | Settings persistence (v4.1): round-trip, secret hygiene, and key resolution order. |
| `AssistTunablesTest` | `com.gamma.agent.model` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/model/AssistTunablesTest.java` | Tunables resolution (v4.1, B1): property wins, settings-file fallback, bad values ignored. |
| `ModelProviderFactoryTest` | `com.gamma.agent.model` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/model/ModelProviderFactoryTest.java` | Provider-id → router mapping (v4.1). Config-only — no network, no model I/O. |
| `ModelSeamTest` | `com.gamma.agent.model` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/model/ModelSeamTest.java` | Unit tests for the model seam, now backed by agent-kernel (U1): the deterministic fake, kernel tier |
| `TimeoutModelProviderTest` | `com.gamma.agent.model` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/model/TimeoutModelProviderTest.java` | Hard-deadline enforcement (v4.1, B1): fast calls pass through, hung calls fail cleanly. |
| `AlertRuleValidatorTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/AlertRuleValidatorTest.java` | Direct branch coverage for the {@code diagnose-and-alert} shape oracle. {@code AlertRuleValidator} |
| `CronDescriberTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/CronDescriberTest.java` | Unit tests for {@link CronDescriber} (deterministic cron → English) and the {@code nextRuns} |
| `DiagnoseAndAlertSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/DiagnoseAndAlertSkillTest.java` | Golden tests for {@link DiagnoseAndAlertSkill}'s NL→alert-rule mode (M7, C1) — CPU-only via a |
| `ExplainEntitySkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/ExplainEntitySkillTest.java` | P2 golden tests for {@code explain-entity}, CPU-only (a {@link FakeModelProvider}, no Ollama). |
| `KpiToSqlSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/KpiToSqlSkillTest.java` | Golden tests for {@code kpi-to-sql} (M6), CPU-only via a {@link FakeModelProvider} and a temp-rooted |
| `NlToScheduleSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/NlToScheduleSkillTest.java` | Golden tests for {@link NlToScheduleSkill} (M4, A2) — fully CPU-only via a deterministic |
| `ReportNarrativeSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/ReportNarrativeSkillTest.java` | Golden tests for {@code report-narrative} (M8), CPU-only via a {@link FakeModelProvider}. The point |
| `ReportSqlSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/ReportSqlSkillTest.java` | Golden tests for {@code report-sql} (M8), CPU-only via a {@link FakeModelProvider} and a temp-rooted |
| `SuggestConfigSkillTest` | `com.gamma.agent.skill` | unit | `features/inspecto-agent/src/test/java/com/gamma/agent/skill/SuggestConfigSkillTest.java` | Golden tests for {@link SuggestConfigSkill} (M5, A3) — CPU-only via a deterministic |
