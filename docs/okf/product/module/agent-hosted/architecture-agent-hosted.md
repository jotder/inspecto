---
type: Module
title: Agent Hosted Module (providers/inspecto-agent-hosted/)
description: Hosted model providers (external LLM SDKs); physically omitted from air-gapped builds.
resource: providers/inspecto-agent-hosted/
tags: [module, agent-hosted, llm, providers, air-gapped]
timestamp: 2026-06-28T00:00:00Z
item: module.agent-hosted
audience: internal
status: current
verified: 2026-11-18 last-edit
code: providers/inspecto-agent-hosted
---

# Agent Hosted Module (`providers/inspecto-agent-hosted/`)

artifactId `inspecto-agent-hosted`. Provides `ModelProvider` implementations backed by external/hosted
LLM SDKs (langchain4j). It is **physically omitted from air-gapped builds** — the [core](../elt/architecture-elt.md) and the
[agent](../agent/architecture-agent.md) module carry no hosted-SDK dependency, so an offline deployment simply doesn't bundle this
jar. See [Hosted providers](spec-agent-hosted.md).
