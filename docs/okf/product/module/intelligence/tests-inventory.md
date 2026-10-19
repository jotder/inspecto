---
type: TestsInventory
title: AI assist and intelligence — tests inventory
item: module.intelligence
description: Generated list of the test classes for module.intelligence, with how to run them.
tags: [tests, module]
---

# AI assist and intelligence — tests inventory (`module.intelligence`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `features/inspecto-intelligence/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.intelligence`](../../../../../product-map/module/intelligence.toon).

35 test class(es): 35 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl features/inspecto-intelligence
mvn -o test -pl features/inspecto-intelligence -Dtest=AgentApprovalsTest,ApprovalResumeTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AgentApprovalsTest` | `com.gamma.intelligence.action` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/action/AgentApprovalsTest.java` | Unit tests for the P3 approval spine: the blocking eoiagent-gate ↔ async-inbox bridge |
| `ApprovalResumeTest` | `com.gamma.intelligence.action` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/action/ApprovalResumeTest.java` | AGT-5 P3 resume-after-restart: a durable {@link ApprovalStore} keeps pending approvals and |
| `ComponentActionsTest` | `com.gamma.intelligence.action` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/action/ComponentActionsTest.java` | End-to-end tests for the P3 component act tools ({@link ComponentActions}) driving the loopback |
| `OperationalActionsTest` | `com.gamma.intelligence.action` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/action/OperationalActionsTest.java` | End-to-end tests for the P3 operational act tools ({@link OperationalActions}) driving the loopback |
| `RunbookActionsTest` | `com.gamma.intelligence.action` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/action/RunbookActionsTest.java` | End-to-end tests for the {@code runbook_operator} compound act tool ({@link RunbookActions}) against a |
| `ComponentDraftRepairLoopTest` | `com.gamma.intelligence.agent` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/agent/ComponentDraftRepairLoopTest.java` | AGT-6a A5.2 — the bounded repair loop for {@code component_draft}. |
| `GatewayFactoryTest` | `com.gamma.intelligence.agent` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/agent/GatewayFactoryTest.java` | Round-2 verification item 2 (2026-09-29): the gateway dials a model endpoint only if the allowlist permits it. |
| `InspectoIntelligenceAgentTest` | `com.gamma.intelligence.agent` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/agent/InspectoIntelligenceAgentTest.java` | End-to-end test of the AGT-5 (P0) session lifecycle, deterministic and offline via |
| `PipelineAuthorRepairLoopTest` | `com.gamma.intelligence.agent` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/agent/PipelineAuthorRepairLoopTest.java` | AGT-6a A5.3 — natural-language authoring for {@code pipeline_author}. |
| `SessionToolGateTest` | `com.gamma.intelligence.agent` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/agent/SessionToolGateTest.java` | Round-2 verification, 2026-09-29: a read-only user's SESSION could make the model call a gated tool and get |
| `ContextBrokerTest` | `com.gamma.intelligence.context` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/context/ContextBrokerTest.java` | The situation frame must be deterministic, budgeted, and honour the WARN+ overlay floor. |
| `SignalIngressTest` | `com.gamma.intelligence.context` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/context/SignalIngressTest.java` | The triage ingress must keep only elevated signals, newest first, and — because it runs on the |
| `NativeEmbeddingLoadTest` | `com.gamma.intelligence.natives` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/natives/NativeEmbeddingLoadTest.java` | Exercises the platform's onnxruntime + DJL tokenizers natives for real (NATIVE-LICENCE-LINUX-MACOS-1): |
| `AnomalyScanGoldenTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/AnomalyScanGoldenTest.java` | Golden figures of the Assistant's {@code anomaly_scan} tool (ANALYTICS-CORE-1 S7), pinned against the tool as it |
| `ArgumentDeriverTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/ArgumentDeriverTest.java` | AGT-6a A5.1 — the derive hop's safety properties, pinned against a deterministic stub gateway. |
| `DraftArtifactsTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/DraftArtifactsTest.java` | AGT-ARTIFACT-1 slice one: a draft skill's ok result is remembered per run, consumed on read, transparent otherwise. |
| `EgressMaskingTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/EgressMaskingTest.java` | AGENT-EGRESS-MASK-1: the row-reading scan tools and every other tool result are masked on the way out - on the |
| `InspectoPackTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/InspectoPackTest.java` | Shape checks for {@link InspectoPack} (AGT-5 P0) — mirrors eoiagent's own ReferenceApplicationPackTest. |
| `InspectoPolicyProfileTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/InspectoPolicyProfileTest.java` | Unit coverage for the host-role → platform-Role/capability mapping (AGT-5 P0). |
| `InspectoToolsTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/InspectoToolsTest.java` | Direct (no-LLM) tests of the S5 signal tools: they read the canonical ledger through |
| `InvestigatorTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/InvestigatorTest.java` | Golden test of the AGT-5 P1 slice C RCA playbook (the P1 exit criterion): a seeded incident — |
| `KillSwitchActTierTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/KillSwitchActTierTest.java` | Round-2 verification item 4 (2026-09-29): the autonomy kill switch reached only the ops_monitor loop, so an |
| `ToolCapabilitiesCoverageTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/ToolCapabilitiesCoverageTest.java` | Every tool the pack hands the platform is classified. If a tool is gated by {@link ToolCapabilities}, its |
| `ToolSchemaAdopterContractTest` | `com.gamma.intelligence.pack` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/ToolSchemaAdopterContractTest.java` | Pins each agent tool's declared {@code jsonSchema} against what its REAL adopter actually sends. |
| `ActionBudgetTest` | `com.gamma.intelligence.policy` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/policy/ActionBudgetTest.java` | Unit coverage for the rolling-window rate budget (AGT-5 P4). The clock is injected, so window rollover |
| `AutonomyPolicyEngineTest` | `com.gamma.intelligence.policy` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/policy/AutonomyPolicyEngineTest.java` | AGT-5 P4 (L3) policy substrate: the kill switch / mode / budget precedence in |
| `OpsMonitorTest` | `com.gamma.intelligence.policy` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/policy/OpsMonitorTest.java` | AGT-5 P4 slice 2: the {@code ops_monitor} driver — that a {@code pipeline.batch.failed} Signal is |
| `AgentWriteRootTest` | `com.gamma.intelligence.store` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/store/AgentWriteRootTest.java` | Unit coverage for the {@code -Dassist.write.root} path resolver shared by the agent stores (M7). |
| `DurableJsonlRingTest` | `com.gamma.intelligence.store` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/store/DurableJsonlRingTest.java` | Unit coverage for the bounded/durable JSONL ring (M7) — the mechanic shared by ApprovalStore, |
| `FeedbackStoreTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/FeedbackStoreTest.java` | AGT-5 P5: durable Triage Run feedback storage + rating parsing. |
| `TriageQueueTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/TriageQueueTest.java` | Slice E triage dispatcher behavior — driven with a synchronous executor ({@code Runnable::run}) over |
| `TriageRunRecallTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/TriageRunRecallTest.java` | AGT-5 P5 slice 2: deterministic {@link TriageRunSimilarity} + durable {@link TriageRunStore} recall. |
| `TriageRunSimilarityGoldenTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/TriageRunSimilarityGoldenTest.java` | Golden pin of Triage Run recall similarity, written BEFORE the Jaccard moved to the Analytics Kernel |
| `TriageRunSimilarityTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/TriageRunSimilarityTest.java` | Unit coverage for the deterministic Jaccard Triage Run-recall scorer (AGT-5 P5). |
| `TriageRunStoreTest` | `com.gamma.intelligence.triage` | unit | `features/inspecto-intelligence/src/test/java/com/gamma/intelligence/triage/TriageRunStoreTest.java` |  |
