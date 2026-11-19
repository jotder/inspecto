---
type: Plan
title: module.agent - board row detail
description: The narrative behind module.agent's long rows on the board (docs/BACKLOG.md), moved here verbatim so each board row stays a short summary.
tags: [module, agent, plan, backlog]
timestamp: 2026-11-19T00:00:00Z
---

# module.agent - board row detail

Each section below is one row of [the board](../../../../BACKLOG.md), headed by its id or name. The board row keeps
the row's priority, a one-line summary of what is open, its state and its tag, and stays the only list of open
work; this page keeps the narrative the row carried, verbatim as it stood on the board on 2026-11-19
(`INFO-RESTRUCTURE-1` W3). Close or narrow the board row first, then trim its section here.

## Open rows

### AGENT-KIT-1

- `AGENT-KIT-1` — **the Agent Kit: AI agents (Claude, Gemini, OpenAI, local / offline models) build, ship and manage Apps on a running Inspecto, safely.** Today there is no MCP endpoint, a machine identity keeps one capability (`Roles.MACHINE_CAPABILITIES` = `canOpenInboundInvestigation`, operator decision B1) and a second interactive client is classed as a machine (`MachineTrust.machineClient`). Plan: an optional `inspecto-mcp` module serving MCP natively on the JDK `HttpServer` (no new dependency), about 30–40 curated task-level MCP Tools with every write through validate → dry-run → Draft and every outbound or promoting act through an Action Request; delegated-person and machine-agent identity; fifteen model-neutral Agent Skills (`SKILL.md`) with a guard against naming a tool, route or term that does not exist; Claude, Gemini, OpenAI and local packages; a golden task suite graded by platform state with a compatibility matrix (AK-1…AK-4). Absorbs the agentic-operation half of `DOCS-SKILLS-ALERTS-AGENTIC-1`. D-UE4…D-UE6 and D-UE9 signed 2026-11-19 (Standard and up; delegated person first, then a machine agent capability set; every save a Draft); AK-1 startable. → [`superpower/2026-11-19-user-enablement-and-agent-kit-plan.md`](../../../../superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) §7

### AGT-5

- **AGT-5 per-tool dry-run seam — BLOCKED-EXTERNAL** (re-gated 2026-09-16). The upstream `DryRunProvider` type ships, but the seam does not: `javap` on the pinned `eoiagent-platform` jar shows `PlatformBuilder` with `approvalHandler(...)` and `approvalDecisionStore(...)` and **no `dryRunProvider(...)`**. **Upstream ask (to `jotder/inspect-agent`): expose `PlatformBuilder.dryRunProvider(DryRunProvider)` and thread it to the gate builder.** Until then `AgentApprovals` stays as the previewer. ⛔ Do not re-discharge on the presence of the type — check the builder. `incident_explain` waits separately on the eoiagent host seam. **2026-11-16 (operator, MoSCoW C12):** raise the missing seam upstream (eoiagent) before the 4.0.0 tag; wiring it here follows the upstream release. `hold: external` removed. **Filed 2026-11-17: [jotder/inspect-agent#1](https://github.com/jotder/inspect-agent/issues/1).** → `archived-documents/plans-archive/agt-6-plan.md` §4.2 G2

## Standing refusals and decisions

Board §6: not work — kept so nobody re-files it.

### Publishing eoiagent 0.1.0 to a registry (EOI-7b)

- **Publishing eoiagent `0.1.0` to a registry (`EOI-7b`)** — ⛔ decided 2026-09-15: **CI keeps building it
  from the upstream tree.** Moved here from §2, where it had sat as an externally-gated item implying
  someone was waiting to publish; nobody was. ⚠ The licence blocker that would have prevented publishing is
  gone either way — `eoiagent-parent` declares **Apache-2.0** since 2026-09-14 — so this is a deliberate
  choice, not an inherited constraint. 🔴 **The cost is now PERMANENT rather than temporary**: neither
  `ci.yml` nor `release.yml` pins a `ref:`, so both follow that repo's default branch, and a commit pushed
  to the wrong branch there reaches no CI while looking landed — which has already happened once. The `ref:` pin was
  answered 2026-09-16: pin `release.yml` only. Trigger: a consumer outside this repo needs the artifacts, or
  the upstream build time becomes a CI constraint.
