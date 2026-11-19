---
type: Concept
title: Hosted Model Providers
description: The inspecto-agent-hosted module — external LLM ModelProviders, physically omitted from air-gapped builds.
resource: providers/inspecto-agent-hosted/
tags: [agent, hosted, llm, providers, air-gapped]
timestamp: 2026-06-28T00:00:00Z
item: module.agent-hosted
audience: internal
status: current
verified: 2026-11-18 last-edit
code: providers/inspecto-agent-hosted
---

# Hosted Model Providers

[`providers/inspecto-agent-hosted/`](architecture-agent-hosted.md) supplies `ModelProvider` implementations backed by
external/hosted LLM SDKs (langchain4j). It is **physically omitted from air-gapped builds** — neither the
[core](../elt/architecture-elt.md) nor the [assist agent](../agent/spec-assist-agent.md) module depends on a hosted SDK, so an
offline deployment simply doesn't bundle this jar (and the assist agent then runs only against any
locally-available model, or abstains). Model/provider selection is configured via the `/assist/settings`
route.
