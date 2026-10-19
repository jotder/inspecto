---
type: Plan
title: Hosted model providers — plan
description: Requirement of record for the hosted-model jar - what was required, built, left and refused.
resource: providers/inspecto-agent-hosted/
tags: [module, agent-hosted, llm, plan]
timestamp: 2026-10-19T00:00:00Z
---

# Hosted model providers — plan

## Required

Customers who allow cloud AI can point the assistant at a hosted model; customers who do not get a
bundle with **no** hosted-model code in it. The air-gap guarantee is physical (a missing jar), not a
switch.

## Built

| When | What |
|---|---|
| v4.1 | `HostedProviderPlugin` SPI in the agent module; `LangChain4jProviderPlugin` + `LangChain4jChatProvider` for anthropic, openai, gemini, grok, llamacpp; settings record, properties-file persistence and in-memory keys ([api](api.md), [admin guide](admin-guide.md)). |
| 2026-07-07 | Request/response mapping moved into the shared eoiagent bridge (`EoiGatewayModelProvider` over `Lc4jChatGateway`) when agent-kernel was replaced; this jar now only builds the chat model. |
| 2026-10-01 | ASSIST-MODEL-EGRESS-1: every configured endpoint is checked against the Space's `models` egress allowlist before a client is built ([assist agent spec](../agent/spec-assist-agent.md)). |
| — | Per-request timeout enforced as a hard deadline (`TimeoutModelProvider`) in `ModelProviderFactory`. |

As-built: [spec](spec.md), [architecture](architecture.md).

## Left open

* Not listed in any `offerings/*.toon` Edition: staging the jar is a manual packaging step today
  ([features](features.md)).
* Model defaults (`ProviderSettings.defaultModels`) are hard-coded ids that age with vendor releases;
  they are free-text overridable in the settings screen.

## Refused (by decision)

* **Hosted SDKs in the core or agent module:** they stay in this jar so air-gapped builds omit them.
* **Persisting raw API keys:** only the variable name is stored; a key typed in the screen lasts until
  restart.
