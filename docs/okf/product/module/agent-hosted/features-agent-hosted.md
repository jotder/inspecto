---
type: Feature
title: "Hosted model providers for the assistant — features"
description: "Hosted model providers plug external LLM SDKs into the assistant as ModelProviders."
tags: [agent-hosted, features, presales]
timestamp: 2026-10-19T00:00:00Z
item: module.agent-hosted
audience: internal
status: current
verified: 2026-11-18 last-edit
code: providers/inspecto-agent-hosted
---

# Hosted model providers for the assistant — features

**Product Map Item:** [`module.agent-hosted`](../../../../../product-map/module/agent-hosted.toon) · **Status:** ga · **Availability:** Not listed in any `offerings/*.toon` Edition; physically omitted from air-gapped builds ([architecture](architecture-agent-hosted.md)).

## What it is

Hosted model providers plug external LLM SDKs into the assistant as ModelProviders. The module is physically left out of air-gapped builds, so an offline install carries no external model client at all.

## What this enables

- Customers who allow cloud AI can point the assistant at a hosted model; customers who do not get a bundle with no hosted-model code in it — [spec](spec-agent-hosted.md)

## Related hubs

[agent](../agent/index.md) · [aiAssist](../../addon/aiAssist/index.md)

More in this hub: [architecture](architecture-agent-hosted.md) · [spec](spec-agent-hosted.md)
