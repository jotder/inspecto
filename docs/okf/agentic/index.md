---
okf_version: "0.1"
---

# eoiagent — Agentic Framework (distilled)

**eoiagent** (the *Enterprise Operational Intelligence Agent Platform*) is an embeddable, plain-Java
"Agent Operating System" that runs *inside* a host application. It powers Inspecto's AI assist model
transport and can power other applications. It is a **separate repo**: the upstream CI clones is
**`jotder/inspect-agent`** on GitHub, Maven groupId `com.eoiagent`, and this reactor pins
**`0.2.0-SNAPSHOT`** (`pom.xml`). ⚠ **Corrected 2026-09-08:** this line named a local path
`C:/sandbox/agent-brainstorm` that **does not exist on the shared sandbox**, and a version two bumps
stale. Requirement-of-record: [Assistant capability spec](../capabilities/assistant/assistant.md) §3.9.

> **Authoritative docs live in that repo** (clone `jotder/inspect-agent` to read them) (`docs/` — itself an OKF bundle: architecture, 14 ADRs,
> per-component specs, roadmap/backlog, packaging & licensing, security review, CI gates). This section
> is the distilled map plus the Inspecto-specific integration seam — enough to reason about the
> dependency without leaving this repo.

## Concepts

* [Overview](overview.md) - identity, tech stack, the 18-module reactor, maturity, licensing.
* [Architecture](architecture.md) - 11 core ports, hexagonal adapters, deployment profiles,
  core vs application packs, host integration.
* [Governance & safety](governance.md) - approval gate + dry-run, policy RBAC, guardrails,
  append-only audit, eval-based certification.
* [ADR log](adr-log.md) - the 14 architecture decisions, one line each.
* [Inspecto integration](inspecto-integration.md) - how Inspecto consumes eoiagent (narrow
  model-transport seam, not the full embed), and the risks to watch.
