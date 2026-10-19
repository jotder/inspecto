---
type: Feature
title: "Workflow and SLA models — admin guide"
description: "Configuring and operating Workflows, the SLA sweep, Escalation Rules and maker-checker approval."
tags: [workflow, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Workflow and SLA models — admin guide

## SLA sweep

| Setting | Default | Effect |
|---|---|---|
| `-Dobjects.sla.sweep.seconds` | `60` | Sweep cadence; `0` or less disables it (read in `inspecto/src/main/java/com/gamma/service/CollectorService.java`) |

Each sweep fires at most 500 escalations. With the sweep disabled, deadlines are still stamped at creation
but no breach is recorded and no Escalation Rule fires.

## Files on disk

| Path | Holds |
|---|---|
| `registry/workflows/`, `registry/sla-policies/`, `registry/escalation-rules/` | Authored components, re-read on change; at most 50 Escalation Rules load |
| `*_workflow.toon` | Legacy workflow override |
| `<write-root>/pending-changes/<id>.json` | One file per held Pending Change |
| `approval.toon` (Space config tree) | Approval Policy, also via `GET` / `PUT /settings/approval` |

The legacy single-tenant Space has no config dir, so its authored Workflows, SLA policies and Escalation
Rules live under `-Dassist.write.root`. Back these directories up with the Space config.

## Approval Policy

Per governable kind: `required`, `approverCapability`, `fourEyes`; any other key is refused. A held change
expires after the policy's hours; the author cannot decide their own change. Deciding needs an
Authenticator, so on Basic it cannot be switched on through the API. Screens:
[user-guide-maker-checker](user-guide-maker-checker.md).

## Troubleshooting

| Symptom | Cause | Action |
|---|---|---|
| An authored workflow is not used | It failed `problems()` (no terminal state, unreachable state, no way out, duplicate action, Incident rules) — the last valid version is kept | Fix the file; the API save reports the problems |
| No SLA badges or breaches | Sweep disabled, or no `sla-policy` for the object type | Check the flag; add `registry/sla-policies/<type>.toon` |
| SLA policy refused | Calendar zone missing or not IANA | Add `zone:` |
| A rule fired once and never again | Fire-once per breach, recorded in `attributes.escalations` | Expected |
| A save answers `202 pending` | Approval Policy requires approval for that kind | Approve under Operations > Pending Changes |

Design: [architecture-workflow-sla](architecture-workflow-sla.md) · routes: [api-workflow-sla](api-workflow-sla.md),
[api-maker-checker](api-maker-checker.md).
