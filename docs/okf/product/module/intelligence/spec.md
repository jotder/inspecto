---
type: Reference
title: "AI assist and intelligence — spec"
description: "Implementation map of the intelligence module: SPI, agent, tool pack, approvals, autonomy policy, gates and storage."
resource: features/inspecto-intelligence/
tags: [intelligence, spec, agent, autonomy]
timestamp: 2026-10-19T00:00:00Z
---

# AI assist and intelligence — spec

Developer map of `features/inspecto-intelligence`. The full as-built narrative per tier (P0–P5, AGT-6a, egress
masking, gotchas) is [Embedded intelligence](architecture-embedded-intelligence.md); overview:
[AI assist](architecture-overview.md).

## Seam

- **SPI** — `IntelligenceAgent` in the core
  ([`IntelligenceAgent.java`](../../../../../inspecto/src/main/java/com/gamma/intelligence/spi/IntelligenceAgent.java)),
  discovered via `META-INF/services/com.gamma.intelligence.spi.IntelligenceAgent` and loaded through `OptionalSpi`,
  so a host whose Java cannot link the jar boots without it and `/agent/*` answers 503 (`docs/EDITIONS.md`).
- **Manifest** — `META-INF/inspecto/module.toon`: id `intelligence`, `offeringRole: optional`,
  `bindingTime: boot`, provides `IntelligenceAgent`.
- **Routes** — `AgentRoutes` in the host
  ([`AgentRoutes.java`](../../../../../inspecto/src/main/java/com/gamma/control/AgentRoutes.java)); table in
  [AI assist — API](api-ai-assist.md).

## Classes

| Package | Class | Role |
|---|---|---|
| `agent` | [`InspectoIntelligenceAgent`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/agent/InspectoIntelligenceAgent.java) | Sessions (server-issued ids, bound to the opener), tool dispatch (`runTool`), Triage Runs |
| `agent` | [`GatewayFactory`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/agent/GatewayFactory.java) | eoiagent model gateway; checks the endpoint against the Space's `models` allowlist |
| `pack` | [`InspectoPackConfig`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/InspectoPackConfig.java) | Always the offline deployment profile; MCP tools off |
| `pack` | [`Investigator`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/pack/Investigator.java) | Playbook: fixed evidence order, then one model call ranks hypotheses into a Triage Run |
| `pack` | `InspectoToolProvider`, `ToolCapabilities` | The 23-tool belt and each tool's declared capability |
| `action` | [`AgentApprovals`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/action/AgentApprovals.java), [`RunbookActions`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/action/RunbookActions.java) | Approvals inbox with dry-run preview; seeded runbooks |
| `policy` | [`AutonomyPolicy`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/policy/AutonomyPolicy.java), [`AutonomyPolicyEngine`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/policy/AutonomyPolicyEngine.java), [`OpsMonitor`](../../../../../features/inspecto-intelligence/src/main/java/com/gamma/intelligence/policy/OpsMonitor.java) | Kill switch, per-action-class mode (unconfigured = OFF), hourly / daily budgets; pilot class `batch_rerun`; executed actions audited as `actor=agent:ops-monitor` |

## Gates

- A tool the model calls in a session meets the same capability gate as `POST /agent/tools/{name}`.
- `POST /agent/tools/{name}` refuses a mutating tool with 403; a failed tool is 422 `CONFIG_VALIDATION_FAILED`.
- Triage Runs, feedback and approvals reads take `canRevealLinkEntities` (free text cannot be masked per object);
  feedback, approval decisions, `PUT /agent/policy` and the kill switch take `canAdminister`.

## Storage

- Autonomy policy: `<assist.write.root>/agent/policy.json` (durable).
- Triage Run feedback: durable. Triage Runs and the autonomy ledger: in-memory rings.

## Tests

[Tests inventory](tests-inventory.md).
