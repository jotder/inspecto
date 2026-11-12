---
type: Concept
title: Assist Agent
description: The AssistAgent SPI, UccAssistAgent's seven read-only skills on agent-kernel, abstain-only policy, /assist routes.
resource: inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java
tags: [agent, assist, spi, agent-kernel, skills]
timestamp: 2026-11-07T00:00:00Z
---

# Assist Agent

The assist surface is an SPI in the core, implemented by the optional [agent module](architecture.md).

> **Which bundles ship it (corrected 2026-11-07).** Standard and every higher edition: `offerings/standard.toon` lists
> `agent` (add-on `aiAssist`), and `package.ps1` stages the shaded sidecar as `lib/inspecto-agent.jar` (`PKG-5`). A Basic
> bundle does not carry it, so there `/assist/*` answers **503**. Requirement-of-record:
> [Assistant capability spec](plan-assistant.md) §3.10.

* **SPI** — `AssistAgent` (`inspecto/src/main/java/com/gamma/assist/spi/AssistAgent.java`), discovered via
  `ServiceLoader`. When no provider is present the assist routes degrade gracefully.
* **Implementation** — `UccAssistAgent` (`features/inspecto-agent/src/main/java/com/gamma/agent/UccAssistAgent.java`)
  on the vendored kernel layer (`com.gamma.agent.kernel.*`, ex agent-kernel — discontinued; eoiagent supplies model transport), registering seven **read-only / draft-only** skills: `DiagnoseAndAlertSkill`,
  `ExplainEntitySkill`, `KpiToSqlSkill`, `NlToScheduleSkill`, `ReportNarrativeSkill`, `ReportSqlSkill`,
  `SuggestConfigSkill`. Dispatch runs `SyncOrchestrator` → `CapabilityRegistry` → skill →
  `UccConfidenceEstimator`, then either surfaces the result or `EscalationRung.Abstain` (**abstain-only** — no
  tier-bump, no human handoff; `applyVia` is always `null`).

## The skill catalog

Moved here 2026-11-07 from `inspecto/README.md` when that file became the bundle's operator README. Every skill is
draft-only: it proposes, and a tested endpoint applies the draft with the person's own credential. Generated SQL is
validated in a locked-down DuckDB sandbox (no filesystem or extension access, a statement allow-list).

| Intent (`POST /assist/{intent}`) | Since | Does | Replaces |
|---|---|---|---|
| `explain-entity` | v3.3.0 | Explains an entity (Pipeline, batch, run, report, error) grounded on the catalog, Control API reads and the docs. Read-only. | A multi-screen drill-down to diagnose. |
| `nl-to-schedule` | v3.4.0 | "every weekday 6am after voucher_etl" → a validated JobConfig draft with `nextRuns[]`. | The cron-builder widget. |
| `suggest-config` | v3.5.0 | A sample plus a partial config → pre-filled fields with rationale, validated by the loader and `ConfigSafetyValidator`. | A long config form and a docs hunt. |
| `kpi-to-sql` | v3.6.0 | A KPI in business terms → transformation SQL, validated in the sandbox (EXPLAIN / LIMIT 0), surfacing the chosen join keys and interpretation. | Hand-writing transform SQL. |
| `diagnose-and-alert` | v3.7.0 | Event-driven: on a FAILED batch, a severity and root-cause draft; and a sentence → Alert Rule draft. | Manual log triage and Alert Rule config. |
| `report-sql` | v3.8.0 | A sentence → a read-only query over the operational ledgers (batches, files, lineage, runs), sandbox-validated. | A report-builder UI. |
| `report-narrative` | v3.8.0 | A report JSON → a short, strictly extractive plain-language narrative (abstains when unsure). | Reading dense report JSON by hand. |

The response is an `AssistResult` (`intent`, `status`, `answer`, `citations`, `links`, `rationale`, `confidence`,
`validated`, `applyVia`, `message`, `data`). "Validated" means the
draft passed its oracle (config parser or sandboxed DuckDB), not that it is correct: review the surfaced
interpretation before applying it.

## Routes

`POST /assist/{intent}` (dispatches by intent; unknown → unsupported, model down → unavailable),
`GET /assist/diagnoses`, `GET/POST /assist/settings` (live reconfigure under the write-gate),
`POST /assist/settings/test` (probe each model tier), `GET /assist/metrics` (counts only, never data values).

The write-back routes are governed by the `-Dassist.write.root` gate (`503` when unset — see
[auth & security](../access/admin-guide-auth-security.md)). Hosted model backends come from
[agent-hosted](../agent-hosted/spec.md).

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
