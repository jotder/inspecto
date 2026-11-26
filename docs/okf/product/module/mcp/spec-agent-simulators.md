---
type: Spec
title: Agent simulators (AGENT-KIT-1 W6 group B) — as-built
description: How an agent simulates a saved Decision Rule or Rule Template and previews an enrichment draft through the shared data seam — the routes, their bucket and weights, the MCP Tools, Decision Rule simulate without its write for an agent, the Rule Template sharing fix, and the enrichment preview raw by decision for a builder's agent (D-AK22).
tags: [agent-kit, mcp, agents, masking, decision-rules, rule-templates]
timestamp: 2026-11-26T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: inspecto/src/main/java/com/gamma/control
---

# Agent simulators (W6 group B)

Group B of the signed design `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` (D-AK13…D-AK21), built
on the shared seam ([spec-agent-data-seam.md](spec-agent-data-seam.md)). An agent tries a rule against data before it
proposes a change, instead of proposing and reading the refusal.

## The routes

`AgentGate.SIMULATORS` (in `ALLOWED` and in `DATA_ROUTES`); each route keeps its own capability, `canAuthorWorkbench`:

| Route | MCP Tool | Weight | Who | What the agent gets |
|---|---|---|---|---|
| `POST /decision-rules/{id}/simulate` | `simulate_decision_rule` (`rule`, `body?`) | 1 | every agent | the rule's content plus `lastSimulation` `{matched, total, checkedAt}` over the body's `sampleRows` |
| `POST /rule-templates/{id}/simulate` | `simulate_rule_template` (`template`, `body?`) | 1 | every agent | the template's matching rows over its source Dataset, masked by `BiResultMasking` |
| `POST /enrichment/preview` | `preview_enrichment` (`body`) | 2 | a person's agent only (`PERSON_ONLY`) | the draft's first 200 enriched rows, **raw** (D-AK22) |

All three are read tools (MCP `readOnlyHint`). An agent's body to any of them is at most 256 KiB on every door
(`AgentGate.PREVIEW_MAX_BODY_BYTES`, 413 before the handler reads it — design §4's cost bound; `ControlApi.handle`); a
person's is not capped. Each call spends its weight in the agent budget at both doors (D-AK17), and an
answer without a masking stamp is 500 `masking not proven` (D-AK13).

## Decision Rule simulate (D-AK20)

The route reads no Dataset: it evaluates the saved rule's `when` tree over the caller's own sample. Its stamp names zero
masked columns (basis `decision-rule <name> (caller sample)`). **For an agent nothing is written**: the answer is the rule
with `lastSimulation` set, and the rule file stays byte-identical (no `lastSimulation`, no `updatedAt`). A person's
simulate still writes both into the file, outside versioning and any approval policy — `DECISION-RULE-SIMULATE-WRITES-1`
stays open for people (design §8 gives it no fix lane). ⚠ A simulation runs a SAVED rule only: a new rule's draft cannot
be simulated before it is approved.

## Rule Template simulate

Masking already ran (`BiResultMasking.of` / `vetFields` / `vetAdHocSql` / `scrub`, BI-QUERY-MASKING-1), and
`BiResultMasking.apply` stamps the answer. The hole vets keep refusing a caller value compared with a masked column
(422); D-AK15 opens no new raw path here. **`RULE-TEMPLATE-SIMULATE-SHARING-1` fixed for everyone:** a source Dataset the
caller cannot view (`ComponentAccess.canView`) answers 404 with the same text as a missing one, as `/bi/query` and
`/queries/{id}/run` do. A Rule Template shared away from the caller is 404 too, as a missing one (found by the lane's
security review: its holes and stored defaults were echoed in `boundTo`). ⚠ A template with no `source` has no masking step, so its answer is never stamped: an agent gets
500 for it (fail closed); a person is unchanged.

## The enrichment preview: raw by decision for a builder (D-AK22)

Design §4 asked that the transform "see masked rows only". The build found it impractical: the Space token is an HMAC
computed in Java (`MaskTokens`), so DuckDB cannot apply it to a Reference view without materializing and tokenizing every
Reference row on each call, and a tokenized Reference never joins the caller's raw sample (against D-AK15). **The operator
decided instead (D-AK22, 2026-11-26, replacing §4 for this route): the answer is UNMASKED for builders.**

- **Who.** A person, or a person's agent, holding `canAuthorWorkbench` gets the raw preview: the agent acts within that
  person's rights (D-AK7). ⚠ **A builder's agent sends raw Reference values to its model provider.** A machine agent is
  refused 403 (`AgentGate.PERSON_ONLY`: "… may not run a Pipeline or the Space tests, nor preview an enrichment"); a caller
  without `canAuthorWorkbench` keeps the route's own 403.
- **The seam still holds.** The handler stamps the answer `AnswerMasking.unmaskedByDecision` (basis `enrichment preview:
  raw for a builder (D-AK22)`), and the route spends weight 2 in the agent budget. That stamp proves an answer only in a
  scope the host opened for a route on `AgentGate.RAW_FOR_BUILDERS` — today this route alone (`ControlApi.provenMasked`).
  In any other scope it records nothing, so a raw answer on any other data route still fails closed with 500.
- **A `path:` Reference** stays as it is for a person: raw and jailed to the safety roots (`PREVIEW-REFERENCE-PATH-UNJAILED-1`).
  A person's agent gets the same; it is not refused.
- **Board.** `ENRICHMENT-PREVIEW-UNMASKED-1` is closed as accepted for builders (D-AK22).

## Tests

| Test | Proves |
|---|---|
| `ControlApiAgentSimulatorsTest` (`features/inspecto-mcp`) | on both doors: an agent's Rule Template answer holds the token, never the raw key; with every stamp a no-op each simulator answers an agent 500 with no row while a person still gets 200; an agent's Decision Rule simulate leaves the file byte-identical and a person's still stamps it; each call spends its weight on each door (the enrichment preview 2), 429 with `Retry-After` past the budget, the person's own budget untouched; a shared-away source Dataset is 404 with the missing-Dataset text; the enrichment preview is raw for a builder's agent on both doors, 403 for a machine agent on both doors and for an agent without `canAuthorWorkbench`; an agent's body over 256 KiB is 413, a person's is not; a shared-away Rule Template is 404 |
| `AgentDataRoutesParityTest` (`inspecto`) | the three routes are allowed, weighted data routes with this proof file; `RAW_FOR_BUILDERS` is exactly the enrichment preview, on `DATA_ROUTES` and `PERSON_ONLY` |
| `AnswerMaskingTest` (`inspecto-util`) | the raw-by-decision stamp proves only a scope opened allowing it; in an ordinary or nested scope the answer still fails closed |
| `AgentGateParityTest`, `ControlApiMcpWriteToolsTest`, `McpToolRoutesTest` | the routes keep `canAuthorWorkbench`; the enrichment preview is person-only; the three tools are listed read-only and each replays an allowed route |

Falsified 2026-11-26: removing the D-AK20 skip, the Decision Rule stamp, either `canView` filter, the body cap, the enrichment preview's
`PERSON_ONLY` entry, or the `RAW_FOR_BUILDERS` allowance on the host's scope each fails `ControlApiAgentSimulatorsTest`;
accepting the raw stamp in every scope fails `AnswerMaskingTest`.

## References

- Signed design: `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` §3.7, §4, §8
- The seam: [spec-agent-data-seam.md](spec-agent-data-seam.md); the agent gate: [spec-mcp.md](spec-mcp.md#the-agent-gate-ak-2a)
- Board: `docs/BACKLOG.md` rows `AGENT-KIT-1`, `DECISION-RULE-SIMULATE-WRITES-1`
