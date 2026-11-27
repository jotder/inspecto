---
type: Spec
title: Agent data answers — the shared seam (AGENT-KIT-1 W6 S0, D1, D2, lookups) — as-built
description: How every agent-reachable route that answers Dataset values proves its masking ran (the masked-answer stamp and the fail-closed 500), the agent data routes list, the one weighted agent rate budget charged at both doors, the install settings that tune it, the audit rule, the analytic reads opened by D1, the reconciliation reads opened by D2, the lookup test opened last and the tests that prove each part.
tags: [agent-kit, mcp, agents, masking, rate-limit, security]
timestamp: 2026-11-27T00:00:00Z
item: module.mcp
audience: internal
status: current
verified: 2026-11-26 code-reviewed
code: inspecto/src/main/java/com/gamma/control
---

# Agent data answers — the shared seam

Change **S0** of the signed design `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` (decisions
D-AK13…D-AK21, signed 2026-11-26). S0 builds the parts every later data change uses, and moves `POST /db/query` and the
Link Analysis reads onto them. S0 opened no new route to agents; groups D1, B, E, D2 and `/lookups/test` join one change each, each with its own
security review (D-AK21). **D1 is built** (2026-11-26): [the analytic reads](#d1--the-analytic-reads); **D2 is built**
(2026-11-27): [the reconciliation reads](#d2--the-reconciliation-reads); **the lookup test is built** (2026-11-27), the
last change of the order: [the lookup test](#the-lookup-test-agent-lookup-preview-1).

## The agent data routes

`AgentGate.DATA_ROUTES` maps each agent-allowed route whose answer carries a value read from a Dataset or Reference to
its weight in the agent budget. As built in S0:

| Route | Weight |
|---|---|
| `POST /db/query` (the `query_dataset` MCP Tool) | 1 |
| The Link Analysis reads (`AgentGate.LA_READS`) less `AgentGate.LA_READS_WITHOUT_VALUES` — 16 routes: projection, neighbours, neighbour preview, multi, recursive paths, reference rows, expand preview, case history, Entity resolve and suggest, deep-link resolve, preset run, Dataset query, branching and temporal patterns, Investigation replay | 1 |
| The analyst previews (`AgentGate.ANALYST_PREVIEWS`, group E, [below](#group-e-the-analyst-previews)): the two score previews; the screening check | 5; 2 |
| `POST /bi/query` (MCP Tool `query_measure`) and `POST /queries/{id}/run` (`run_saved_query`), `AgentGate.ANALYTIC_READS` (D1) | 1 |
| The reconciliation reads (MCP Tool `reconcile_preview`), `AgentGate.RECON_READS` (D2, [below](#d2--the-reconciliation-reads)): `POST /recon/run`, `/recon/breaks`; `/recon/rows`; `/recon/columns` | 5; 2; 1 |
| `POST /lookups/test` (MCP Tool `test_lookup`), `AgentGate.LOOKUP_TEST` ([below](#the-lookup-test-agent-lookup-preview-1)) | 1; 20 when it builds its index |
| The 28 GET reads of masked values (`AgentGate.VALUE_GETS`, operator 2026-11-26, `AGENT-GET-DATA-READS-1`): Dataset rows, `/db/table`, a KPI value; Risk Score, Risk Score list, Anomaly Score, prediction; an Entity List; screening hits; regulatory reports; the Link Analysis GETs — Dataset discover, Entity and Entity Type profiles and summary, entity identities, value measures, a graph run, an Investigation's log, Working Set, comparison, dossier and bundle, a Draft's log and Working Set, the public Investigation link | 1 |

Group B added the simulators (`AgentGate.SIMULATORS`, 2026-11-26): `POST /decision-rules/{id}/simulate` and
`POST /rule-templates/{id}/simulate`, weight 1 each, and `POST /enrichment/preview`, weight 2 — the one route raw by
operator decision (D-AK22, `AgentGate.RAW_FOR_BUILDERS`): its stamp `AnswerMasking.unmaskedByDecision` proves only a scope
the host opened for such a route, so a raw answer anywhere else still fails closed; since D-AK26 (2026-11-27) it reads no
data folder by path and no Dataset the caller cannot view —
[spec-agent-simulators.md](spec-agent-simulators.md).

Not on the list, because they answer no Dataset value: the overlap profile (names and counts) and the two dossier
verifications (verdicts and hashes). `AgentDataRoutesParityTest` makes every agent-allowed write route either a data
route or one named with its reason, so a new allowed route is classified before it ships.

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

## Group E: the analyst previews

Change **E** of the signed design (built 2026-11-26): `AgentGate.ANALYST_PREVIEWS`, in `ALLOWED` and `DATA_ROUTES`, for a
person's agent and a machine agent alike (the design makes neither person-only). Each route's own gate still decides.

| Route | MCP Tool | Weight | Masking (the stamp) | Agent bound |
|---|---|---|---|---|
| `POST /risk-scores/preview` | `preview_score` `kind: risk` | 5 | key as the Space token, evidence by `EvidenceMasker` (`EntityKeyMasking.put`) | `canWorkIncidents`; a draft (`content`, also `canAuthorWorkbench`) is **raw** for a person's agent and **403** for a machine agent (D-AK23) |
| `POST /anomaly-scores/preview` | `preview_score` `kind: anomaly` | 5 | as above | as above |
| `POST /screening/check` | `check_screening` | 2 | each entry as its list shows it: a masked list's entry is its token (`ScreeningRoutes.check`, stamped with the masked-list count) | weight **2 per started 10 subjects** (`ceil(n/10)*2`, D-AK24: the host charges the first 2, the route the rest through `com.gamma.util.AgentBudget`; a check costing more than the burst is 429) and at most **10 matches per subject** (`AGENT_MAX_MATCHES`, D-AK16) |

- **Raw values in (D-AK15).** An agent may send the raw `entityKey` or a raw name: the operator-accepted membership
  oracle. The check echoes the subjects the caller sent (its own values); the key of a score preview leaves as the token.
- **The clamp.** An agent's `maxMatches` above 10 is answered with 10, not refused (a person's 1..20 range and its 422
  are unchanged; an agent's `0` is refused as a person's). ⚠ This change's choice: the design says "clamped to 10".
- **Draft score previews (D-AK23, operator 2026-11-26, from the security review).** A draft's factor measure or filter
  over a classified column (`max(msisdn)`, `msisdn < …`) answers its values raw in `factors[].value` or the anomaly
  `series` — only evidence is masked. As D-AK22 does for the enrichment preview: a person's agent holding
  `canAuthorWorkbench` gets the draft raw, stamped `AnswerMasking.unmaskedByDecision` (accepted only on
  `AgentGate.RAW_FOR_BUILDERS`); a machine agent's draft is 403; a saved model's preview keeps its key and evidence masked for every agent (its own measures are the open part of the people row).
  ⚠ The draft answer also carries the key stamp (`EntityKeyMasking.put`), so the seam would pass it without the raw
  stamp; the raw stamp records the decision. People: `RISK-SCORE-MEASURE-UNMASKED-1` (accepted for builders).
- **Screening weight per subject (D-AK24, operator 2026-11-26).** An agent's check costs `ceil(n/10)*2`: the host
  charges the route's 2, the check the rest before reading any list (`ScreeningRoutes.chargeAgentSubjects`, through the
  host's charger in `com.gamma.util.AgentBudget.ATTR`; fails closed without it). A check costing more than the burst
  (1000 subjects: 200 > 60) is 429. So the match score cannot be hill-climbed a thousand guesses at a time. A person is
  not charged and keeps 1..1000.
- **Unmasked lists.** On a list the Space does not mask (masking mode `none`, or an Entity Type not masked), the entry is
  the member as stored, for an agent as for a person (design §3.3, "entries stay the list's shown form").
- **Budget.** The heaviest data route now weighs 5, so `PUT /system/link-analysis/limits` answers 422 for a
  `rate.agent_burst` below 5 and a lower system property is raised to 5.
- **Audit (D-AK19).** No new row. A direct call leaves none (`/preview` and the read-shaped check are skipped by
  `AuditTrail.classify`); an MCP call leaves `mcp.tool.called` with the tool name only. No submitted name, identifier,
  subject key or entity key reaches an audit row or a log line (tested).
- **Tests.** Masked column and unstamped 500 per route: `ControlApiRiskScoreTest`, `ControlApiAnomalyScoreTest`
  (`anAgentsScorePreviewIsProvenMaskedOrWithheld`), `ControlApiScreeningTest.anAgentsCheckIsProvenMaskedOrWithheldAndCappedAtTen`
  (also the 10-match clamp). Both doors, a person's agent and a machine, the weights 5 and 2 with 429 on each door, the
  person's budget untouched, and the audit and log scan: `ControlApiAgentAnalystPreviewsTest` (`features/inspecto-mcp`;
  the anomaly preview there meets an unknown model, 404 after its weight is charged — its masked answer is proven over
  the corpus in its own module).
  Tool routes: `McpToolRoutesTest.theAnalystPreviewToolsReplayTheirDataRoutes`.

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
  heaviest call's weight (`AgentGate.PEAK_DATA_WEIGHTS`, `AgentGate.burstProblem`); a lower value from a hand-edited document or the system
  property is raised to that weight with a warning (`RateLimiter.agent`). Since the lookup change the heaviest call weighs 20 (a
  lookup test that builds its index), so a burst of 1–19 is refused (`RateLimiterBudgetTest`).
- **The other buckets.** The existing per-route buckets (`/db/query` standard, `/mcp`, the Link Analysis bucket) still
  apply to an agent, now keyed by the same `agent:<client>:<subject>` key instead of the person's id. So an agent's call
  is never less limited than before, and never touches the person's buckets. A person's request is unchanged.

## D1 — the analytic reads

Change D1 of the signed design (widening plan W6 group D): the Measure query a Widget runs and a saved query's run, for
every agent (a person's agent and a machine agent; neither is person-only). Both routes keep their manifest exemption
(`read-shaped`) and every gate a person's request has; for an agent they are `AgentGate.ANALYTIC_READS`, in `ALLOWED`
and on `DATA_ROUTES` at weight 1.

- **Masking.** No new masking code: both routes already mask through `BiResultMasking` (`apply` stamps the answer,
  zero masked in a `none` Space or for a caller who may reveal), and an agent never reveals. The value-oracle vets keep
  refusing an agent as they refuse a person who cannot reveal — on `/bi/query` a filter, an ordering or a
  value-revealing Measure (`max`, `min`…) over a masked column (`vet`, `vetHiddenOrderColumns`, `vetAdHocSql` on a bound
  saved query); on `/queries/{id}/run` a sort (`vetSort`) or a caller parameter (`vetParameterValues`) on one. D-AK15
  adds no raw path here. Error text is scrubbed (`scrub`) as for a person.
- **Projection after masking** (D1 security review, fixed for every caller). `apply` judges free-SQL columns by their
  position in the author's select list, which a caller's `projection` reordered: `SELECT amount, msisdn AS region` run
  with `projection: [region, amount]` returned the raw key, stamped. With masking in force, `QueryRoutes.runQuery` now
  runs the author's whole select, masks it, then projects (an unknown projection name is 422).
- **A saved query that names no Dataset** (`datasetId` absent) runs no masking step, so for an agent it is withheld as
  500 `masking not proven`; a person's run is unchanged. ⚠ This is a deliberate default of D1, not a design decision:
  such a query reads no Dataset relation, but the sandbox attaches the shared lakehouse catalog when one is configured,
  so its answer cannot be proven masked.
- **Budget.** Each call spends 1 of the agent budget on both doors. `/bi/query`'s own dashboard bucket (120 burst, 2/s)
  still applies, keyed by the agent key; `/queries/{id}/run` has no bucket of its own, so for an agent the agent budget
  is its only rate bound (for a person, as before, none).
- **MCP Tools.** `query_measure` (`body` = the Measure query, as `POST /bi/query` takes it) and `run_saved_query`
  (`query` = the saved query id, `body?` = `{parameters?, limit?, offset?, projection?, sort?}`), both read-only. The
  skill `build-dashboard` computes each Widget's number with `query_measure`. No tool lists saved queries yet.

## D2 — the reconciliation reads

Change D2 of the signed design (widening plan W6 group D, built 2026-11-27): the four reconciliation reads, for every
agent (a person's agent and a machine agent; the design makes neither person-only). They keep their manifest exemptions
(`read-shaped`; `/recon/run` `stateless-compute`) and every gate a person's request has (`RECON-READ-UNGATED-1`, fixed
for people first: `ComponentAccess.canView` on every side, masking per side). For an agent they are
`AgentGate.RECON_READS`, in `ALLOWED` and on `DATA_ROUTES`.

| Route | `reconcile_preview` step | Weight | What it answers |
|---|---|---|---|
| `POST /recon/columns` | `columns` | 1 | each Dataset's column names and types, the cross-side matches: no value (stamped, zero masked) |
| `POST /recon/run` | `run` | 5 | a page of one day's Board rows, the exact totals and the Break summary |
| `POST /recon/breaks` | `breaks` | 5 | the paged Break sets of one day |
| `POST /recon/rows` | `rows` | 2 | the rows behind one key on the anchor and the compared side |

- **Masking and the cache.** `ReconDay.Cache` holds a day's answer keyed by config fingerprint, day and arguments, not
  by caller, so one caller's raw answer is read back for the next. The masking runs on a copy after the cache read
  (`ReconRoutes.Read`), and **the stamp is set there**: each of `maskKeys`, `maskBreakSets` and `maskRows` stamps as it
  makes the caller's copy (basis = the sides' Dataset ids, masked = the key columns hidden from the caller). A path that
  served a cached answer without masking it would carry no stamp, so for an agent it fails closed (500). A unified key
  hidden on any side is the Space token in every grain and Break row; a side's raw rows (`/recon/rows`) are masked by
  that side's own Dataset, as for a person.
- **Raw values in (D-AK15).** The `key` of the rows read may be the raw value; the answer echoes it as the token. A
  `masked:` alias as a key or path value is 422 (it matches no raw value), as for a person without reveal.
- **Bounds (D-AK16, D-AK18).** An inline Reconciliation (`config`) is allowed, over Datasets the agent may view (a
  shared-away side is the 404 of an unknown Dataset, before any SQL). The caps are a person's: a Board page 1..200 rows,
  a `sample` at most 100 000 keys, a Break or rows `limit` clamped to 5 000. The value-oracle refusals keep refusing an
  agent: a `sum` (compared or carried impact) over a hidden column, a side filter naming a hidden column or an internal
  `__` relation name (422).
- **Error text (D2 security review, fixed for every caller).** A 4xx passes the seam unstamped, and DuckDB quotes a
  stored value in a run-time error: a classified VARCHAR key compared with another side's integer or date key failed
  with `Could not convert string '<value>'`, in the 422. While any side's masking is in force, every SQL-derived 422 of
  run, breaks and rows now goes through `BiResultMasking.scrub` (`DatasetCellMasking.scrub`, `ReconRoutes.Read.scrub`),
  as `/bi/query`'s does: a run-time error's text is withheld, a compile-time one (Parser, Binder, Catalog) kept. ⚠ The
  day resolution in `scoped()` (temporal column, available days) runs before the masking is known and is not scrubbed;
  its SQL reads the date column only.
- **Out of scope.** `POST /recon/{id}/record`, `/recon/{id}/breaks/status` (they persist) and `/recon/promote` stay
  refused. The recorded-state GETs (`/recon/{id}/state`, `/recon/promoted`) were already refused to agents as `RAW_GETS`;
  their people defect stays open as `RECON-STATE-READS-UNMASKED-1`.
- **Budget.** At D2 the heaviest data route still weighed 5 (the lookup change raised the floor to 20). The `/recon/*` standard
  bucket still applies, keyed by the agent key.
- **Audit (D-AK19).** No new row: a direct call is read-shaped and leaves none; an MCP call leaves `mcp.tool.called`.
  No key value an agent sends reaches an audit row or a log line (tested).

## The lookup test (AGENT-LOOKUP-PREVIEW-1)

The last change of the signed order (built 2026-11-27): `POST /lookups/test` — one value through a loaded Pipeline's
keyed join — for every agent, a person's and a machine's. It keeps its gate `canAuthorWorkbench` and everything the
people fix `LOOKUP-TEST-OPEN-READ-1` (2026-11-26) built: `canView` on every Dataset over the Reference's store (else the
404 of an unknown Step), masking by classification, at most 5 fuzzy candidates, per-caller budgets. For an agent it is
`AgentGate.LOOKUP_TEST`, in `ALLOWED` and on `DATA_ROUTES`; it was never an authoring preview.

- **Masking (D-AK16).** Over a by-name Reference (`reference/<pipeline>`) the matched key, the mapped columns and every
  fuzzy candidate are masked as `/bi/query` masks a Dataset over that store; `LookupRoutes.test` stamps the answer
  (basis `lookup <reference>`, masked = the key and answered columns hidden from the caller). A raw `value` is allowed
  (D-AK15); the answer echoes it in `compared` and names the match masked. A sixth candidate is never listed. A by-name
  Reference with no write root has no registry to classify it: no stamp, so an agent's answer is withheld (500) — ⚠ a
  default of this change, not a design decision.
- **A `path:` Reference (D-AK25, operator 2026-11-27, as D-AK22).** It has no classification. A person's agent gets the
  raw answer, stamped `AnswerMasking.unmaskedByDecision` (the route is on `AgentGate.RAW_FOR_BUILDERS`, so that stamp
  proves it); a machine agent gets 403 before any index is built. ⚠ A builder's agent sends that file's values to its
  model provider. **D-AK26 (operator, 2026-11-27):** a path into a data folder — a Dataset's store, a Pipeline's folders
  (rejected rows, raw copies, inbox, working folders) — is refused 422
  for every caller before any index is built (`PathReferenceGuard`, shared with the enrichment preview); that data is
  tested by name, masked and sharing-checked. The raw stamp now covers only a file outside every data folder.
  `LOOKUP-TEST-PATH-RAW-1` closed.
- **Budget (D-AK17).** Weight 1. A call that builds the index (asked, cold, evicted, its Step or Reference changed) pays
  20 in all: the host charges 1, the route spends 19 more through the `AgentBudget` charger before the build (429 past
  the budget, the kept index unchanged). The agent burst floor is the heaviest call, so `AgentGate.PEAK_DATA_WEIGHTS`
  holds 20 for the route and a burst below 20 is refused or raised. The route's own per-caller budgets (20 calls then
  one per 3 s; 3 builds then one per 30 s) are keyed by the agent key for an agent, so they never spend the person's.
  The 256 MiB index cap is unchanged.
- **A refused build names no value (security review, 2026-11-27).** A duplicated key or overlapping ranges are reported
  with the Reference values involved, so the `POST /lookups/test` 422 and `GET /lookups` `index.error` carry the fixed
  text `LookupRoutes.WITHHELD_REFUSAL` to every caller without `canRevealLinkEntities` (people too, as D2 withholds a
  failed comparison's text); a caller who may reveal still reads the detail.
- **One Space, one index.** The kept indexes (`WARM`, `BUILDING`, `FAILED`) are keyed by the Space (its service and write
  root) as well as the Pipeline and Step, so a second Space with the same Pipeline name never answers from, or reports,
  the first Space's index.
- **MCP Tool.** `test_lookup` (`body` = `{pipeline, step, value, at?, blockValues?, refresh?}`), read-only; `GET
  /lookups` lists the Steps. The skills `author-pipeline` and `safe-config-change` test a lookup before proposing it.
- **Audit (D-AK19).** No new row; an MCP call leaves `mcp.tool.called`. The value is not logged (tested).

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
| `ControlApiAgentAnalyticReadsTest` (`features/inspecto-mcp`) | D1: `/bi/query` and `/queries/{id}/run` for a person's agent and a machine agent, direct and through `query_measure` / `run_saved_query`: the masked column a token; unstamped, 500 with no row while a person gets 200; a saved query naming no Dataset withheld from an agent; a `projection` that reorders an aliased key still masked; a filter, `max` or sort on the masked column 422; a `none` Space raw; the budget spent across both routes and doors, 429 past it, the machine's and the person's untouched; no request value in an audit row or log line, both MCP rows present | removing the D1 data-route entries (no 500 when unstamped); the `BiResultMasking.apply` stamp; projecting before masking |
| `ControlApiAgentReconReadsTest` (`features/inspecto-mcp`) | D2: the four recon reads for a person's agent and a machine agent, direct and through `reconcile_preview`: the classified key a token on every side, the raw `key` asked for echoed as the Break list's token; unstamped, 500 with no row while a person gets 200; a revealer's raw answer read back from the cache reaches an agent (that revealer's own included) masked, and an agent-filled cache entry reaches the revealer raw and a person without reveal masked; a shared-away side 404; page, sample and offset bounds 422; a sum or filter over the key 422; a classified VARCHAR key against an integer key: the 422 never quotes a stored key; a `none` Space raw; the weights 5, 5, 2, 1 spent across both doors, 429 past the budget, the machine's and the person's untouched; no sent key in an audit row or log line | removing the stamp from the recon masking; serving a cached Break list unmasked; dropping the D2 data-route entries; a no-op `Read.scrub` |
| `ControlApiAgentLookupTest` (`features/inspecto-mcp`) | the lookup test for a person's agent and a machine agent, direct and through `test_lookup`: the matched key, the classified mapping and every candidate a token, a raw value asked included; seven fuzzy matches → five candidates; unstamped, 500 with no value while a person gets 200; a `path:` Reference raw to a person's agent, 403 to a machine with no index built, and 500 unstamped; no `canAuthorWorkbench` 403; a build spends 20 and a plain test 1 on both doors, 429 past the budget, the person's own budgets untouched; a refused build names no Reference value to an agent, a machine or `GET /lookups`; a second Space with the same Pipeline name never shares the first's index; no sent value in a log line | — |
| `AgentDataRoutesParityTest` (`inspecto`) | every data route agent-allowed and weighted; every allowed write route classified; every GET route in the source classified in `agent-get-routes.tsv`, `data` = `VALUE_GETS`, `raw` = `RAW_GETS` refused to agents and unchanged for people; every data route has its proving test; weight and key for agents only | — |
| `ControlApiRiskScoreTest`, `ControlApiRiskScoreListTest` (`features/inspecto-scoring`), `ControlApiAnomalyScoreTest`, `ControlApiPredictionReadTest`, `ControlApiEntityListTest`, `ControlApiScreeningTest`, `ControlApiRegulatoryReportsTest` | each module's value GETs: an agent's answer 200 and masked; unstamped, 500 with no value while a person still gets 200; the screening reveal refused to an agent; an agent's screening hit carries no raw subject key | removing any one stamp: `EntityKeyMasking.put`, `RiskScoreRoutes.list`, `EntityListRoutes.one`, either `ScreeningRoutes` stamp, either `RegulatoryReportRoutes` stamp, `KpiRoutes.value`, the identities renderer, `EntityProfiles.typeProfile`; refusing no raw GET (`AgentDataRoutesParityTest`) |
| `AnswerMaskingTest` (`inspecto-util`), `RateLimiterBudgetTest#theAgentBudgetSpendsByWeightAllOrNothing` | scope nesting, other-thread stamps unseen, 2xx-only refusal; weighted all-or-nothing spend, default burst 60 | — |

## References

- Signed design: `docs/superpower/2026-11-26-agent-data-answers-masking-design.md` §3, §5, §6, §7
- The agent gate: [spec-mcp.md](spec-mcp.md#the-agent-gate-ak-2a); group A: [spec-agent-previews.md](spec-agent-previews.md)
- Board: `docs/BACKLOG.md` row `AGENT-KIT-1`
