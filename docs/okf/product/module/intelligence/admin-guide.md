---
type: Guide
title: "AI assist and intelligence — admin guide"
description: "Enabling the intelligence module, its permissions, configuration, storage and common failures."
resource: features/inspecto-intelligence/
tags: [intelligence, admin-guide, autonomy, egress]
timestamp: 2026-10-19T00:00:00Z
---

# AI assist and intelligence — admin guide

## Enabling

- Edition: **Advanced** (and Preview). `intelligence` is listed in
  [`offerings/advanced.toon`](../../../../../offerings/advanced.toon); the bundle stages `inspecto-intelligence.jar`
  (a shaded sidecar with the onnxruntime natives inside). Basic and Standard do not carry it, and `/agent/*` answers
  503 `CAPABILITY_UNAVAILABLE` there (`docs/EDITIONS.md` `CP-14`).
- The module starts at boot. On a host whose Java is older than the module needs, `OptionalSpi` skips it and the
  product starts normally without `/agent/*`.
- The reflex assist layer (`/assist/*`) is the separate [agent module](../agent/admin-guide.md).

## Permissions

| Action | Capability |
|---|---|
| Read Triage Runs, feedback, approvals | `canRevealLinkEntities` |
| Give Triage Run feedback, decide an approval, edit the policy, flip the kill switch | `canAdminister` |
| Call a tool (`POST /agent/tools/{name}`) | the tool's declared capability |
| Use a session | its opener only |

Defined in [`AgentRoutes.java`](../../../../../inspecto/src/main/java/com/gamma/control/AgentRoutes.java).

## Configuration

- **Model endpoints** — the gateway refuses a `base.url` outside the Space's `models` list in `egress.toon`. The
  default list is empty, so even a local model must be named (`localhost` / `127.0.0.1`); an `https` endpoint
  named by DNS host is refused. See [Assist agent](../agent/spec-assist-agent.md).
- **Provider settings** are shared with the agent module (`config/assist-settings.properties`, see the
  [agent admin guide](../agent/admin-guide.md)).
- **Autonomy policy** — `<assist.write.root>/agent/policy.json`, edited through `/agent/policy`; survives restart.
  Global kill switch; each action class has a mode (an unconfigured class is OFF) and rolling hourly / daily
  budgets. With `-Dassist.write.root` unset the write routes answer 503.

## Storage and retention

Durable: the autonomy policy and Triage Run feedback. In-memory rings (lost on restart): Triage Runs, the autonomy
ledger and the Case store (256 entries).

## Common failures

| Symptom | Cause |
|---|---|
| `/agent/*` 503 | Not an Advanced bundle, or the jar was skipped on this Java |
| A model call refused | Endpoint not in the `egress.toon` `models` allowlist |
| 403 from `POST /agent/tools/{name}` | The tool is mutating; it goes through approvals, not direct dispatch |
| Agent actions stop | Kill switch on, budget exhausted, or the class mode is OFF |

More: [AI assist — limits](architecture-overview.md), [Embedded intelligence](architecture-embedded-intelligence.md).
