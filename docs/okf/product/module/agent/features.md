---
type: Feature
title: "Assistant agent — features"
description: "The Assistant agent adds optional AI assist skills to Inspecto, discovered through the AssistAgent SPI."
tags: [agent, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# Assistant agent — features

**Product Map Item:** [`module.agent`](../../../../../product-map/module/agent.toon) · **Status:** ga · **Availability:** Optional module, staged from Standard and every higher Edition (`offerings/standard.toon`). Add-on: [aiAssist](../../addon/aiAssist/index.md).

## What it is

The Assistant agent adds optional AI assist skills to Inspecto, discovered through the AssistAgent SPI. Its seven skills are read-only and abstain rather than guess, and model transport runs over a narrow gateway so air-gapped installs keep working.

## Who benefits

Analysts and pipeline authors who want guided help inside the product, and security teams who need AI that cannot act without approval.

## Key capabilities

- Seven read-only assist skills behind the `/assist` routes, abstain-only policy — [spec-assist-agent](spec-assist-agent.md)
- Approval gate and dry-run for every mutating action, Role × Capability × Profile policy, append-only audit — [spec-governance](spec-governance.md)
- Model / provider settings for the assistant and AI-backed features — [user-guide-model-settings](user-guide-model-settings.md)
- Offline-first agent platform with permissive-only licensing — [architecture-overview](architecture-overview.md)

## Related hubs

[aiAssist](../../addon/aiAssist/index.md) · [intelligence](../../module/intelligence/index.md) · [agent-hosted](../../module/agent-hosted/index.md)

More in this hub: [architecture-integration](architecture-integration.md) · [architecture-module](architecture-module.md) · [architecture-overview](architecture-overview.md) · [architecture](architecture.md) · [plan-assistant](plan-assistant.md) · [spec-adr-log](spec-adr-log.md) · [spec-assist-agent](spec-assist-agent.md) · [spec-governance](spec-governance.md) · [user-guide-model-settings](user-guide-model-settings.md).
