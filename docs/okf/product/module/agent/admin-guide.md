---
type: Guide
title: "Assistant agent — admin guide"
description: "Enabling the agent module, its permissions, model settings, -D flags, storage and common failures."
resource: features/inspecto-agent/
tags: [agent, assist, admin-guide, model-settings]
timestamp: 2026-10-19T00:00:00Z
---

# Assistant agent — admin guide

## Enabling

- Edition: **Standard and above**. `agent` is listed in
  [`offerings/standard.toon`](../../../../../offerings/standard.toon) (offering `aiAssist`); the bundle stages
  `inspecto-agent.jar`, discovered by jar presence. On Basic `/assist/*` answers 503 (`docs/EDITIONS.md` `CP-14`,
  `PKG-5` shipped 2026-09-12).
- Hosted model providers (`agent-hosted`) ship in no bundle; see [agent-hosted](../agent-hosted/spec.md).
- With no model configured every skill abstains ("model unavailable"); the module is safe to ship enabled.

## Permissions

| Action | Capability |
|---|---|
| Change model settings (`POST /assist/settings`) | `canAdminister` |
| Probe the models (`POST /assist/settings/test`) | `canAuthorWorkbench` |
| Ask a skill (`POST /assist/{intent}`) | per skill |
| Read diagnoses, metrics, settings, skills | read |

Routes: [Assistant agent — API](api.md).

## Configuration

| Key | Meaning |
|---|---|
| `-Dassist.settings.file` | Settings file; default `config/assist-settings.properties` under the working directory |
| `provider`, `base.url`, `model.small`, `model.medium`, `model.large`, `timeout.seconds` | Keys in that file |
| `api.key.ref` | The NAME of an env var or system property holding the key — never the key itself |
| `-Dassist.write.root` | Write root for the assist write-back routes; unset = 503 |
| `-Dassist.docs.dir` | Docs directory the agent reads |
| `-Dagentkernel.ollama.baseUrl` / `AGENTKERNEL_OLLAMA_BASEURL` | Native Ollama endpoint, used only when `agentkernel.ollama.enabled` is on |

Defined in [`AssistModelSettings.java`](../../../../../features/inspecto-agent/src/main/java/com/gamma/agent/model/AssistModelSettings.java).
The pane is [Model Settings](user-guide-model-settings.md).

**Model endpoint allowlist** — every model client is checked against the Space's `models` list in `egress.toon` and
pinned to the checked address. The default list is empty, so even the local Ollama default must be named
(`localhost` / `127.0.0.1`). Details: [Assist agent](spec-assist-agent.md).

## Storage and retention

Settings live in the properties file. A key posted through the API is held in memory only; after a restart it must
come from the env var `api.key.ref` names. Failure diagnoses are kept in memory.

## Common failures

| Symptom | Cause |
|---|---|
| `/assist/*` 503 "inspecto-agent not on classpath" | Basic bundle, or the jar is missing |
| Skill answers 503 "model unavailable" | No model configured for its tier, or the endpoint was refused by the allowlist |
| `POST /assist/settings` 400 with a `baseUrl` | `anthropic` and `gemini` always use their vendor endpoint |
| Key lost after restart | Keys posted through the API are not persisted; set the env var named by `api.key.ref` |

More: [AI assist — limits](../intelligence/architecture-overview.md),
[Governance](spec-governance.md).
