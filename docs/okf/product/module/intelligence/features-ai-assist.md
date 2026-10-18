---
type: Feature
title: "AI assist — features"
description: "AI assist is the optional model-backed help layer, in two tiers:  - **Assist agent** (features/inspecto-agent, manifest id agent): explain, diagnose, draft and triage skills   that are read-only or draft-only and abstain rather than ship a low-confidence answer; plus failure diagnosis of   failed batches."
tags: [intelligence, ai-assist, features, presales]
timestamp: 2026-10-19T00:00:00Z
---

# AI assist — features

**Availability:** Offering: `aiAssist` add-on - Standard: `agent` (the `agent-hosted` provider exists in the reactor but ships in no bundle, `docs/EDITIONS.md`); Advanced adds `intelligence`.

## What it does

AI assist is the optional model-backed help layer, in two tiers:

- **Assist agent** (`features/inspecto-agent`, manifest id `agent`): explain, diagnose, draft and triage skills
  that are read-only or draft-only and abstain rather than ship a low-confidence answer; plus failure diagnosis of
  failed batches.
- **Intelligence agent** (`features/inspecto-intelligence`, manifest id `intelligence`, Advanced): multi-turn
  sessions, tool calls, playbook investigations that produce **Triage Runs**, an approvals gate for mutating
  actions, and bounded autonomy behind a kill switch.

Hosted model providers come from `providers/inspecto-agent-hosted` (manifest id `agent-hosted`, requires `agent`).
Concepts: [Assist agent](../agent/spec-assist-agent.md),
[Embedded intelligence](architecture-embedded-intelligence.md),
[Hosted providers](../agent-hosted/spec.md), [Assistant](../agent/plan-assistant.md).

## Where it fits

An add-on over the platform base. Routes live in the host (`AssistRoutes`, `AgentRoutes` in
`inspecto/src/main/java/com/gamma/control/`) and answer 503 `CAPABILITY_UNAVAILABLE` when the module is absent.
The agents hold no write credential of their own; anything that changes state goes through the control plane's
own gates and audit.

More: [screens](user-guide-ai-assist.md) · [API](api-ai-assist.md) · [architecture](architecture-overview.md).
