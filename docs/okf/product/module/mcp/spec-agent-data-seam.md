---
type: Spec
title: Agent data answers — the shared seam (AGENT-KIT-1 W6 S0) — as-built
description: How every agent-reachable route that answers Dataset values proves its masking ran (the masked-answer stamp and the fail-closed 500), the agent data routes list, the one weighted agent rate budget charged at both doors, the install settings that tune it, the audit rule and the tests that prove each part.
tags: [agent-kit, mcp, agents, masking, rate-limit, security]
timestamp: 2026-11-26T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: inspecto/src/main/java/com/gamma/control
---

# Agent data answers — the shared seam

Change **S0** of the signed design `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` (decisions
D-AK13…D-AK21, signed 2026-11-26). S0 builds the parts every later data change uses, and moves `POST /db/query` and the
Link Analysis reads onto them. **It opens no new route to agents**: groups D1, B, E, D2 and `/lookups/test` join one
change each, each with its own security review (D-AK21).

## The agent data routes

`AgentGate.DATA_ROUTES` maps each agent-allowed route whose answer carries a value read from a Dataset or Reference to
its weight in the agent budget. As built in S0:

| Route | Weight |
|---|---|
| `POST /db/query` (the `query_dataset` MCP Tool) | 1 |
| The Link Analysis reads (`AgentGate.LA_READS`) less `AgentGate.LA_READS_WITHOUT_VALUES` — 16 routes: projection, neighbours, neighbour preview, multi, recursive paths, reference rows, expand preview, case history, Entity resolve and suggest, deep-link resolve, preset run, Dataset query, branching and temporal patterns, Investigation replay | 1 |
| The 28 GET reads of masked values (`AgentGate.VALUE_GETS`, operator 2026-11-26, `AGENT-GET-DATA-READS-1`): Dataset rows, `/db/table`, a KPI value; Risk Score, Risk Score list, Anomaly Score, prediction; an Entity List; screening hits; regulatory reports; the Link Analysis GETs — Dataset discover, Entity and Entity Type profiles and summary, entity identities, value measures, a graph run, an Investigation's log, Working Set, comparison, dossier and bundle, a Draft's log and Working Set, the public Investigation link | 1 |

Group B added the simulators (`AgentGate.SIMULATORS`, 2026-11-26): `POST /decision-rules/{id}/simulate` and
`POST /rule-templates/{id}/simulate`, weight 1 each, and `POST /enrichment/preview`, weight 2 — the one route raw by
operator decision (D-AK22, `AgentGate.RAW_FOR_BUILDERS`): its stamp `AnswerMasking.unmaskedByDecision` proves only a scope
the host opened for such a route, so a raw answer anywhere else still fails closed —
[spec-agent-simulators.md](spec-agent-simulators.md).

Not on the list, because they answer no Dataset value: the overlap profile (names and counts) and the two dossier
verifications (verdicts and hashes). `AgentDataRoutesParityTest` makes every agent-allowed write route either a data
route or one named with its reason, so a new allowed route is classified before it ships. `POST /lookups/test` stays
refused to agents (`AGENT-LOOKUP-PREVIEW-1`).

**Every GET route is classified** in `inspecto/src/test/resources/agent-get-routes.tsv` (pattern, class, reason), and
`AgentDataRoutesParityTest` fails when a `.get("…")` registration in the source is missing from it. The classes:

- **`data`** (28) — answers a masked value: on `VALUE_GETS`, stamped, weighted 1, each proven over real HTTP.
- **`raw`** (9) — answers Dataset, Reference or file values with no masking step, so it can never prove its answer:
  **refused to agents** (`AgentGate.RAW_GETS`, with the reason the agent is told) — a view's rows
  (`VIEW-DATA-UNMASKED-1` for people), a connection sample, a Job artifact's bytes, a run's rejected rows, `/export`,
  the recon state and promoted Breaks, stored Insights, the screening reveal. A person's call is unchanged.
- **`stored`** (43) — event, audit, Incident and Case, note, notification and run-record reads that may echo keys other
  features wrote: still open to agents, outside the seam, open as `AGENT-STORED-RECORD-READS-1`.
- **`none`** (205) — config, names, counts, statuses: no Dataset value (the graph run list among them: run metadata,
  no result).

⚠ The scan reads literal `.get("…")` registrations; ControlApi's bare health, readiness and contract routes and the MCP
OAuth metadata route (a constant) are not scanned and answer no value.

## The masked-answer stamp (D-AK13, D-AK14)

Masking stays at the compute site, where the lineage is known. What is shared is the proof that it ran:
`com.gamma.util.AnswerMasking` (in `inspecto-util`, so engine code can stamp too).

- **The scope.** For an agent's call to a data route, `ControlApi.handle` runs the handler inside an
  `AnswerMasking.Scope` on the request thread. `handle` is the one method both doors call — `routeDispatch` (direct
  REST) and `replay` (an MCP Tool call) — so the seam holds on both.
- **The stamp.** A masking step calls `AnswerMasking.applied(basis, maskedColumns)` once it has decided the answer's
  masking. As built: `BiResultMasking.apply` (`/db/query`; set before its early return, so a Space with masking mode
  `none` or a revealing caller stamps zero), the `ExplorationMasking` and `EntityMasking` constructors (every factory of
  the Link Analysis maskings), `EntityProfileRoutes.resolveKey`, `InvestigationInboundRoutes.typed`,
  `EntityProfiles.suggest`, `CaseHistory` (after its masking decision) and `PatternRoutes.refusal` (an empty answer);
  for the GET reads also `EntityKeyMasking.put` (a score's or prediction's key; its evidence was masked at write),
  `RiskScoreRoutes.list`, `EntityListRoutes.one`, `ScreeningRoutes` hits and hit (an agent's `subjectKey` leaves as the Space token and its `dedupeKey` is withheld: the key is stored raw, `SCREENING-HIT-KEY-RAW-1` for people), `RegulatoryReportRoutes` list and one
  (`ObjectReadMasking`), `KpiRoutes.value` (after `vetMeasure`), `/db/table`'s keyless operational-store branch, the
  entity identities renderer and `EntityProfiles.typeProfile`. ⚠ An entity identity assertion's free-text `reason` and
  `actor` are returned as written, as the stored-record reads are.
- **Fail closed.** A 2xx answer from an unstamped scope is replaced by **500 `masking not proven - the answer is
  withheld`** (`ControlApi.MASKING_NOT_PROVEN`) before any byte is written: for a returned body in `provenMasked`; for a
  body a handler writes itself in the response writers (`ApiContext.respondJson|Text|Binary` call
  `AnswerMasking.requireProvenBeforeSend`). The 500 names nothing of the request; the log line names the route only. A
  4xx refusal passes as it is (it carries no rows).
- **What it does not prove.** A stamp says a masking step ran for this answer, not that every row went through it: for
  the Link Analysis routes the stamp is set when the masking is built, before its `out` calls. A stamp set on another
  thread is not seen, so masking moved off the request thread fails closed, never open. Scopes nest: an inner replay's
  stamp never proves an outer answer.
- **The Space rules (D-AK14).** A Space whose masking mode is `none` masks nothing for anyone, agents included; the stamp
  is still set with zero masked.

## The agent budget (D-AK17)

One token bucket per **agent client and subject** (`AgentGate.agentBudgetKey`: `agent:<agentClient>:<subject id>`), so
a person's agent never spends the person's own budget and two agent clients of one person never share. Each data-route
call spends its weight at **both doors**: `ControlApi.chargeAgentBudget` runs in `routeDispatch` after the agent gate
and in `replay` (the MCP replay used to skip the rate-limit stage). Past the budget: **429** `RATE_LIMITED` with
`Retry-After`, the seconds until the route's whole weight is back (`RateLimiter.retryAfterSeconds(key, weight)`);
the direct refusal is audited as `access.denied`. A weighted call spends its whole weight or nothing
(`RateLimiter.tryConsume(key, weight)`).

- **Install settings.** `rate.agent_burst` (default **60**, range 1–6 000) and `rate.agent_per_s` (default **0.5**,
  0.01–1 000) in the server-wide `la-limits.toon`, next to the Link Analysis `rate.*` items (`LaLimit`), else the
  system properties `control.rateLimit.agent.capacity` / `.refillPerSecond`. `PUT /system/link-analysis/limits` and
  boot re-apply them (`applyInstallRateLimits`); an unchanged budget keeps its buckets. ⚠ The defaults are this change's
  choice (the design names no number): sixty page reads at once, then one every 2 s, the Link Analysis pace.
- **Never a burst a route cannot pay.** `PUT /system/link-analysis/limits` answers 422 for a `rate.agent_burst` below the
  heaviest `DATA_ROUTES` weight (`AgentGate.burstProblem`); a lower value from a hand-edited document or the system
  property is raised to that weight with a warning (`RateLimiter.agent`). Today every route weighs 1, the setting's
  minimum, so the 422 is proven by unit test only (`RateLimiterBudgetTest`).
- **The other buckets.** The existing per-route buckets (`/db/query` standard, `/mcp`, the Link Analysis bucket) still
  apply to an agent, now keyed by the same `agent:<client>:<subject>` key instead of the person's id. So an agent's call
  is never less limited than before, and never touches the person's buckets. A person's request is unchanged.

## Cost and audit

- **Cost (D-AK18).** No agent sandbox and no agent row cap: an agent's call runs with exactly a person's limits.
- **Audit (D-AK19).** No new row. The MCP call keeps its `mcp.tool.called` row. No raw value an agent sent or was
  answered in reaches an audit or event row or a log line, tested on `POST /db/query` and on all 16 Link Analysis data
  routes. Two rows named a caller's entity in clear: `link.expanded` (`value`, from `/inv/projection/neighbors` in both
  forms) and `link.traversed` (`startNode`, `targetNode` and the message, from `/inv/traversal/recursive-paths`). For an
  agent they now keep the **alias** (`InvRoutes.auditedValue`, operator 2026-11-26, `AGENT-AUDIT-EXPAND-VALUE-1`); a
  person's rows are unchanged and keep the raw key, as `ControlApiInvProjectionTest` pins. In a Space whose masking mode
  is `none` the alias is the value itself (D-AK14). ⚠ Accepted: an agent's direct REST call to a read-shaped route leaves
  no audit row.

## Tests

| Test | Proves | Falsified by (2026-11-26) |
|---|---|---|
| `ControlApiAgentDataSeamTest` (`features/inspecto-mcp`) | `/db/query` on both doors: an agent's answer masked and stamped; with every stamp a no-op (`AnswerMasking.ignoreStampsForTest`) 500 on both doors with no row, a person unchanged; a `none` Space answers raw; the budget 429 on each door, per client, the person's own burst of 20 untouched; no request value in any audit row or log line, the `mcp.tool.called` row present; the GET Dataset rows, `/db/table` and KPI value proven the same way | removing the seam check; the direct or the replay charge; keying the rate limit or the budget by the person's id; the `BiResultMasking` stamp |
| `ControlApiAgentLinkAnalysisSeamTest` (`la/inspecto-geo-link`) | each of the 16 Link Analysis data routes answers an agent 200 with no raw id; unstamped, each answers 500 with no value while a person still gets 200; across all 16, no event row or log line written for the agent holds a raw id, and its `link.expanded` row keeps the alias | removing the seam check; the `ExplorationMasking` stamp; `auditedValue` returning the raw key |
| `AgentDataRoutesParityTest` (`inspecto`) | every data route agent-allowed and weighted; every allowed write route classified; every GET route in the source classified in `agent-get-routes.tsv`, `data` = `VALUE_GETS`, `raw` = `RAW_GETS` refused to agents and unchanged for people; every data route has its proving test; weight and key for agents only | — |
| `ControlApiRiskScoreTest`, `ControlApiRiskScoreListTest` (`features/inspecto-scoring`), `ControlApiAnomalyScoreTest`, `ControlApiPredictionReadTest`, `ControlApiEntityListTest`, `ControlApiScreeningTest`, `ControlApiRegulatoryReportsTest` | each module's value GETs: an agent's answer 200 and masked; unstamped, 500 with no value while a person still gets 200; the screening reveal refused to an agent; an agent's screening hit carries no raw subject key | removing any one stamp: `EntityKeyMasking.put`, `RiskScoreRoutes.list`, `EntityListRoutes.one`, either `ScreeningRoutes` stamp, either `RegulatoryReportRoutes` stamp, `KpiRoutes.value`, the identities renderer, `EntityProfiles.typeProfile`; refusing no raw GET (`AgentDataRoutesParityTest`) |
| `AnswerMaskingTest` (`inspecto-util`), `RateLimiterBudgetTest#theAgentBudgetSpendsByWeightAllOrNothing` | scope nesting, other-thread stamps unseen, 2xx-only refusal; weighted all-or-nothing spend, default burst 60 | — |

## References

- Signed design: `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` §3, §5, §6, §7
- The agent gate: [spec-mcp.md](spec-mcp.md#the-agent-gate-ak-2a); group A: [spec-agent-previews.md](spec-agent-previews.md)
- Board: `docs/BACKLOG.md` row `AGENT-KIT-1`
