---
type: Concept
title: Assist Agent
description: The AssistAgent SPI, UccAssistAgent's seven read-only skills on agent-kernel, abstain-only policy, /assist routes.
resource: inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java
tags: [agent, assist, spi, agent-kernel, skills]
timestamp: 2026-06-28T00:00:00Z
---

# Assist Agent

The assist surface is an SPI in the core, implemented by the optional [agent module](../modules/agent.md).

> 🔴 **No bundle ships that implementor.** `inspecto-agent` is a plain reactor module that
> `package.ps1` never copies, so `/assist/*` answers **503 in every artifact** — the intended default
> (`EDITIONS.md` `CP-14`, [`build-test.md`](../build-run/build-test.md), `BACKLOG.md` `PKG-5`). Using the
> Assistant means building and placing the jar by hand. Requirement-of-record:
> [Assistant capability spec](../../capabilities/assistant/assistant.md) §3.10.

* **SPI** — `AssistAgent` (`inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java`), discovered via
  `ServiceLoader`. When no provider is present the assist routes degrade gracefully.
* **Implementation** — `UccAssistAgent` (`features/inspecto-agent/src/main/java/com/gamma/agent/UccAssistAgent.java`)
  on the vendored kernel layer (`com.gamma.agent.kernel.*`, ex agent-kernel — discontinued; eoiagent supplies model transport), registering seven **read-only / draft-only** skills: `DiagnoseAndAlertSkill`,
  `ExplainEntitySkill`, `KpiToSqlSkill`, `NlToScheduleSkill`, `ReportNarrativeSkill`, `ReportSqlSkill`,
  `SuggestConfigSkill`. Dispatch runs `SyncOrchestrator` → `CapabilityRegistry` → skill →
  `UccConfidenceEstimator`, then either surfaces the result or `EscalationRung.Abstain` (**abstain-only** — no
  tier-bump, no human handoff; `applyVia` is always `null`).

## Routes

`POST /assist/{intent}` (dispatches by intent; unknown → unsupported, model down → unavailable),
`GET /assist/diagnoses`, `GET/POST /assist/settings` (live reconfigure under the write-gate),
`POST /assist/settings/test` (probe each model tier), `GET /assist/metrics` (counts only, never data values).

The write-back routes are governed by the `-Dassist.write.root` gate (`503` when unset — see
[auth & security](../editions/auth-security.md)). Hosted model backends come from
[agent-hosted](hosted-providers.md).

### Model endpoint allowlist (2026-10-01)

Every route by which this module builds a model client checks the endpoint against the Space's `models` list in
`egress.toon` (`ModelEgress`, the check the intelligence module's `GatewayFactory` makes) and builds the client on
the CHECKED address (`ModelEgress.pin`), resolving once. The routes, all in `ModelProviderFactory`:
`create` (a settings entry), `fromPersisted`, and `fromEnvironment` (`-Dagentkernel.ollama.baseUrl` /
`AGENTKERNEL_OLLAMA_BASEURL`, only when `agentkernel.ollama.enabled` is on). `AiDescriptionProvider`'s no-arg
constructor and `UccAssistAgent.testSettings()` (`POST /assist/settings/test`, rebuilt per call so the CURRENT
allowlist decides) go through them. A refused endpoint is an unavailable provider whose name carries the reason;
nothing dials it.

A missing `baseUrl` is checked against the provider's default: ollama `http://localhost:11434` and llamacpp
`http://localhost:8080/v1` (loopback, and inspecto's own port). Only Grok's `https://api.x.ai/v1` default is treated
as a fixed vendor host, and the anthropic / openai / gemini builders use hardcoded vendor hosts. Verified in the
langchain4j 1.16.3 sources: none of those SDK jars reads an environment variable or system property for its base URL
(no `OPENAI_BASE_URL`), and `langchain4j-http-client-jdk` never sets `followRedirects`, so the JDK client's default
of NEVER applies and a 3xx cannot bounce a checked call to another host. An explicit `baseUrl` on a hosted provider
is checked and pinned like any other.

`POST /assist/settings` refuses a `baseUrl` for `anthropic` and `gemini` (400, "always uses its vendor endpoint"):
their clients ignore it, so accepting one would only be checked and never used. `OllamaModelProvider.fromEnvironment`
(unchecked) is package-private; only `ModelProviderFactory.fromEnvironment` reaches it.

Consequences: an empty allowlist (the default) refuses even the local Ollama default, so a local model needs
`localhost` / `127.0.0.1` named; an `https` endpoint named by DNS host is refused (it cannot be pinned).
`inspecto-agent-hosted` carries `inspecto-processor` as `provided` for this. Tests: `AssistModelEgressTest` (real
HTTP; metadata address refused, unlisted loopback never dialled, env-var router and `AiDescriptionProvider` never
dial, the client dials the first-resolved address) and `LangChain4jProviderPluginTest` (llamacpp default).
