---
type: Concept
title: Assistant agent and eoiagent
description: The assistant agent in four parts — the eoiagent architecture (eleven stable ports behind hexagonal adapters, deployment profiles with a fail-closed capability matrix, and the core vs application-pack split); the eoiagent overview (embeddable plain-Java agent platform — JDK 25, LangChain4j-based, 18-module reactor, offline-first, permissive-only licensing); the Agent Module features/inspecto-agent/ (optional AI assist skills — vendored kernel layer com.gamma.agent.kernel + eoiagent model transport; discovered via the AssistAgent SPI); and the Inspecto ↔ eoiagent integration (Inspecto consumes eoiagent as a narrow model transport, EoiGatewayModelProvider over LlmGateway, with the reasoning layer vendored in inspecto-agent — not the full Agent-OS embed).
resource: jotder/inspect-agent/docs/architecture/, jotder/inspect-agent, features/inspecto-agent/, features/inspecto-agent/src/main/java/com/gamma/agent/  # the two jotder/… entries are the upstream GitHub repo; NOT local paths (corrected 2026-09-09)
tags: [agentic, architecture, hexagonal, ports, application-pack, eoiagent, overview, langchain4j, module, agent, assist, vendored-kernel, optional, inspecto, model-transport, intelligence]
timestamp: 2026-07-07T00:00:00Z  # the first three parts; the integration part carried 2026-07-16. Part types were Concept, Overview, Module, Concept
---

# Assistant agent and eoiagent

The architecture of the `agent` hub, in four parts: two about **eoiagent**, the upstream embeddable agent
platform (its ports and profiles, then its stack and maturity), one about the optional Agent Module
`features/inspecto-agent/`, and one about how narrowly Inspecto consumes eoiagent. The parts were written
at different dates and each keeps its own correction notes.

**On this page**

- [eoiagent architecture](#eoiagent-architecture) — ports, hexagonal discipline, deployment profiles, packs.
- [eoiagent overview](#eoiagent-overview) — what it is, stack, reactor, version, licensing.
- [Agent Module (`features/inspecto-agent/`)](#agent-module-featuresinspecto-agent) — the optional assist module.
- [Inspecto ↔ eoiagent integration](#inspecto--eoiagent-integration) — the model transport and its limits.

## eoiagent architecture

* **Eleven core ports** (each with swappable adapters): Model Access (`LlmGateway` — Ollama,
  OpenAI-compatible `baseUrl`, Anthropic, Gemini, routing + stub), Knowledge/RAG (`Retriever` /
  `VectorStore` / `DocumentIngestor`), Tools (`Tool`/`ToolRegistry` — host `@Tool` methods + MCP),
  Runtime (`Planner`/`Orchestrator`/`TaskManager` — ReAct, agentic supervisor, LangGraph, reflection),
  Memory, Scratchpad (virtual-FS context offloading), Safety (`ApprovalGate`/`Guardrail`/`PolicyEngine`),
  Persistence (`CheckpointStore`), Observability (`AuditSink`/`TraceCollector`), Host
  (`AgentService`/`AgentSession`), Config (`ConfigProvider`).
* **Hexagonal discipline (ADR-0004)** — ports import no framework; third-party libs appear only in
  adapters; experimental deps are quarantined behind feature flags (ADR-0010). Enforced by JDK
  Class-File-API arch tests.
* **Deployment profiles** — `OFFLINE` / `ON_PREM_HOSTED` / `CLOUD` select model placement through a
  **capability matrix**: a feature is on only if the matrix permits AND config enables it; classpath
  presence never enables; OFFLINE **fails closed** (hosted models always off).
* **Core vs Application Pack (ADR-0011)** — mechanism is CORE (reused unchanged); policy/content/
  bindings live in a per-product **pack** implementing the SPI (metadata, model profile, knowledge
  sources, tools, navigation catalog, prompts, policy, config). One pack per deployment; a new product
  is a new pack, never a fork; the `AppId` is stamped into every context + audit event.
* **Host integration** — `AgentService.open(...)` → `AgentSession.ask/askStream/close`; the host
  supplies `PageContext` per ask, an `AnswerSink` for streaming, and an `ApprovalHandler`; it gets a
  typed `AgentAnswer` (TEXT / INLINE_ARTIFACT / NAVIGATION / CLARIFICATION / ERROR) — faults come back
  as `ERROR` answers, never raw exceptions. The host depends only on `host` + `core`.

## eoiagent overview

An **embeddable agent platform**, not a chatbot: it analyzes metadata/schemas, drafts pipelines/SQL,
investigates production problems, executes **gated** operational actions, and gives page-aware
in-product help (signature output: a `NavigationIntent` that routes the user to the right screen).

* **Stack** — JDK 25 (`release=25`; class-file v69), Maven, **no Spring/Quarkus** (ADR-0001).
  Foundation: **LangChain4j 1.16.3** (BOM-pinned) for models/RAG/tools/memory/MCP;
  `langchain4j-agentic` + **LangGraph4j** for orchestration; in-JVM **ONNX MiniLM** embeddings;
  JDK `HttpClient` transport (ADR-0002).
* **Reactor** — 18 modules: a BOM; `core` (all ports + domain records, zero framework imports);
  adapter modules per port (`model`, `knowledge`, `tool`, `runtime`, `memory`, `scratchpad`, `safety`,
  `persistence`, `observability`, `config`); the host facade (`host`); the Application-Pack SPI
  (`app-api`) + bootstrap (`platform`); `eval`; a worked reference pack (`app-reference`); `examples`.
* ⚠ **Version — this part's `0.1.0-SNAPSHOT` is stale (corrected 2026-09-08).** A `v0.1.0` tag was cut
  2026-07-08 and the trunk bumped; this reactor now pins **`0.2.0-SNAPSHOT`**, rebuilt from the upstream
  branch head by CI. See [the Assistant spec](plan-assistant.md) §3.9.
* **Maturity** — phases 0–2 implemented, phase 3 + the 3.5 "wire everything into the live ask() path"
  integration pass largely done per the 2026-07-03 hardening docs; builds green and runs fully offline.
  Version **`0.1.0-SNAPSHOT`** — no release tag yet (see
  [Inspecto integration](#inspecto--eoiagent-integration) for why that matters here).
* **Licensing** — permissive-only dependency policy (Apache-2.0/MIT/BSD; no *GPL) enforced by a
  license-report gate in CI (ADR-0012, formally still Proposed).

## Agent Module (`features/inspecto-agent/`)

artifactId `inspecto-agent`. The **optional** AI assist module: when present on the classpath the core
discovers it via `ServiceLoader<com.gamma.assist.spi.AssistAgent>`; when absent, the assist routes degrade
gracefully. The concrete implementation `UccAssistAgent` registers seven read-only/draft-only skills with an
abstain-only escalation policy — see [Assist agent](spec-assist-agent.md).

The former external `agent-kernel` library is **discontinued**: its reasoning layer is now **vendored
in-tree** at `features/inspecto-agent/src/main/java/com/gamma/agent/kernel/**` (package `com.gamma.agent.kernel`;
`ModelProfile` at `com.gamma.agent.model`). Model transport is **eoiagent**
(`com.eoiagent:eoiagent-core|-model:0.1.0-SNAPSHOT`) via the `EoiGatewayModelProvider` bridge, plus a native
`OllamaModelProvider`. `langchain4j-open-ai` is **excluded** from this module — the `EgressGuardTest`
air-gap invariant enforces it.

## Inspecto ↔ eoiagent integration

Since 2026-07-07 (the agent-kernel replacement), Inspecto's integration is **deliberately narrow** —
a model transport, *not* the full eoiagent host embed:

* **Vendored reasoning layer** — the discontinued agent-kernel's orchestration layer lives in-tree at
  `features/inspecto-agent/src/main/java/com/gamma/agent/kernel/**` (`SyncOrchestrator`, `Orchestrations`;
  `ModelProfile` at `com.gamma.agent.model`). The historical `agentkernel.*` sysprop names are kept
  for deployment compatibility.
* **Transport bridge** — `EoiGatewayModelProvider` bridges the vendored `ModelProvider` seam onto
  eoiagent's `LlmGateway`/`Lc4jChatGateway`; a native `OllamaModelProvider` keeps the same public
  surface (`format=json`, 120 s timeout). In `inspecto-agent`, consumed artifacts are
  **`com.eoiagent:eoiagent-core` + `eoiagent-model` only**. Transport behaviors deliberately kept
  Inspecto-side (eoiagent's stock adapters don't carry them): native `ResponseFormat.JSON` for
  Ollama, per-request client timeouts from `ProviderSettings`; token accounting maps eoiagent
  `Usage` `0` back to the kernel's `-1 = unknown` convention.
* **Embedded intelligence (AGT-5 P0, shipped 2026-07-07 + hardened 2026-07-08)** — the exception to
  "transport only": module `inspecto-intelligence` **does** embed the eoiagent platform
  (`eoiagent-core/app-api/platform/model`) — `InspectoPack` (ApplicationPack) +
  `InspectoIntelligenceAgent`, surfaced through the core `IntelligenceAgent` SPI and the
  `/agent/sessions` routes (SSE `/ask/stream`); ONNX one-doc RAG corpus proven offline; UI
  navigation catalog auto-derived from `app.routes.ts`. Open scope cuts: **QA-only**
  (`incident_explain` waits on an eoiagent host seam) and **local-models-only** — tracked in the
  archived plan [`embedded-intelligence-plan.md`](../../../../archived-documents/plans-archive/embedded-intelligence-plan.md) §8
  and in [`BACKLOG.md`](../../../../BACKLOG.md) §2.
* **Air-gap invariant** — `langchain4j-open-ai` is excluded in `inspecto-agent`; the `EgressGuardTest`
  invariant holds; hosted model SDKs stay in `inspecto-agent-hosted`.
* **Runtime floor** — eoiagent jars are class-file v69 ⇒ the agent modules need a **JDK 25+ runtime**.
  ✅ **No longer a constraint in practice since 2026-09-17**: the product compiles at `release=27`, so
  every host that can run Inspecto at all clears 25 by construction. (Historically a bare JDK 24 host
  failed with `UnsupportedClassVersionError` — the case `OptionalSpi` was built for.)
* **Version pin** — eoiagent is pinned to the **released `0.1.0`** (EOI-7a, 2026-07-08: tag `v0.1.0`
  on eoiagent `main`; both agent poms pin `eoiagent.version 0.1.0`, no SNAPSHOT anywhere). Remaining
  EOI-7b: publish artifacts to a registry (infra/product call); until then CI reproduces the pin with
  `git checkout v0.1.0 && mvn -o clean install`. Note: the eoiagent docs describe no named consumer —
  the Inspecto-side facts live in this repo.

Related: [assist agent](spec-assist-agent.md) · [agent module](#agent-module-featuresinspecto-agent) ·
archived plans: [`agent-kernel-replacement-plan.md`](../../../../archived-documents/plans-archive/agent-kernel-replacement-plan.md)
· [`agent-brainstorm-assessment.md`](../../../../archived-documents/plans-archive/agent-brainstorm-assessment.md).
