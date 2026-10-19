---
type: Capability
title: "AI assist and intelligence — plan"
description: "Requirement of record for the intelligence module (AGT-5, AGT-6a, AGT-6b): what is required, built, left and refused."
resource: features/inspecto-intelligence/
tags: [intelligence, plan, agt-5, agt-6]
timestamp: 2026-10-19T00:00:00Z
---

# AI assist and intelligence — plan

The requirement of record for the whole Assistant (`AGT` + `EOI`) is one file,
[Assistant capability spec](../agent/plan-assistant.md); this page is the slice of it the intelligence module
(`features/inspecto-intelligence`, manifest id `intelligence`) owns. Where the two disagree, that file wins.

## Required

| Row | Requirement | MoSCoW |
|---|---|---|
| `AGT-5` | Embedded intelligence: context grounding, tool belt, autonomy ladder (Explain, Draft, Act-with-approval, bounded autonomy) | Should |
| `AGT-6a` | Inline natural-language authoring on every pane, reusing the shipped draft tools | Should |
| `AGT-6b` | Multi-step agent graphs beyond the seeded runbooks | Could |
| `EOI-1`..`EOI-7` | The eoiagent runtime the module is built on (separate repository) | Must / Should |

Rows as stated in [plan-assistant §2](../agent/plan-assistant.md#2-requirements-of-record).

## Built

- `AGT-5` P0–P5 complete: the tool belt is 23 tools, pinned by `InspectoPackTest`
  ([`InspectoPackTest.java`](../../../../../features/inspecto-intelligence/src/test/java/com/gamma/intelligence/pack/InspectoPackTest.java));
  per-tier detail in [Embedded intelligence](architecture-embedded-intelligence.md).
- `AGT-6a` mechanism live: deterministic single-tool dispatch, `<inspecto-ai-assist>` on five panes,
  `<inspecto-ai-explain>` on twelve ([Inline AI authoring](user-guide-inline-ai-authoring.md)).
- Packaging: `ASSURE-INTELLIGENCE-BUNDLE-1` (closed 2026-09-29) stages `inspecto-intelligence.jar` in the Advanced
  bundle only (`docs/EDITIONS.md` `CP-14`); `INTELLIGENCE-AGENT-NEVER-STARTED-1` (closed 2026-09-30) starts it at boot.
- Outbound masking of tool results (`AGENT-EGRESS-MASK-1`, 2026-10-15) and the model endpoint allowlist
  (`ASSIST-MODEL-EGRESS-1`, closed 2026-10-01).

## Left (see [`docs/BACKLOG.md`](../../../../BACKLOG.md))

- **AGT-5 per-tool dry-run seam** — P2, blocked on the upstream `PlatformBuilder.dryRunProvider(...)`;
  `AgentApprovals` stays the previewer.
- **`AGT-6b`** — holds while the upstream approval gate is synchronous per call (nested gates deadlock).
- **`AGT-SEGMENT-1`** — P3, the tier packaging is an unvalidated product read.
- **`NATIVE-LICENCE-SHADE-MERGE-1`** — the shaded sidecar's `META-INF/LICENSE*` merge.
- `AGT-6a`: the `kpi_report_builder` host; "every screen" remains an ambition.
- Untracked gaps: [plan-assistant §5.2](../agent/plan-assistant.md).

## Refused / parked

- **AGT-5 embedding recall** parked (the Case store is a 256-cap ring).
- **AGT-6a tool `args` runtime validation** declined in favour of a contract test.
- **Publishing eoiagent to a registry (`EOI-7b`)** — decided 2026-09-15 that CI keeps building it.
- Full list: [plan-assistant §6](../agent/plan-assistant.md).
