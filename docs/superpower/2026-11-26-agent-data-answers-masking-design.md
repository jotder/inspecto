# Agent data answers: masking, enumeration, rate and cost bounds, audit redaction (AGENT-KIT-1 W6 groups B, D, E)

> 🟡 **SIGNED 2026-11-26 — D-AK13…D-AK21 signed in an operator interview (D-AK13, 14, 17, 20, 21 as recommended;
> D-AK15, 16, 18, 19 not as recommended, §5); the people-facing defects of §8 filed and fixed first. **S0 built 2026-11-26**
> (the shared seam, as built: [`spec-agent-data-seam.md`](../okf/product/module/mcp/spec-agent-data-seam.md)); D1, B, E, D2
> and the lookup change not built.**
> Unblocks widening plan
> [`2026-11-25-agent-kit-ak2-widening-plan.md`](2026-11-25-agent-kit-ak2-widening-plan.md) § W6 groups B, D and E (D-AK10:
> "once masking is proven per route") and board row `AGENT-LOOKUP-PREVIEW-1`. The operator chose on 2026-11-26 to settle
> one shared design before any of them is built. Code facts below were read on `origin/master` `a890ebcb4`.

## 1. The question

Plan rule §2: *a route that returns data rows needs its masking, its SQL guard and its rate limit shown by a test before
it is allowed.* Fourteen routes are in scope. For each: what an agent would see, what it could enumerate, what bounds its
volume and cost, and what the audit trail keeps of its inputs. One mechanism where one fits; per-route work where it does
not.

## 2. What is true today

### 2.1 The shared parts

- **An agent never reveals.** `OidcAuthenticator` strips `Roles.AGENT_NEVER_CAPABILITIES` (approve, approve link
  expansions, `canRevealLinkEntities`) from every agent Subject (`providers/inspecto-oidc/.../OidcAuthenticator.java:208`;
  set at `platform/inspecto-access/.../Roles.java:255`). So every existing masking step that asks
  `BiResultMasking.mayReveal` (`inspecto/.../control/BiResultMasking.java:115`) masks for an agent. ⚠ It answers `true` when
  there is no Subject (sign-in off); an agent always has one.
- **The masking that exists is per route, at the compute site**, keyed by the Dataset's classification:
  `BiResultMasking.of(writeRoot, store, views, datasetId, dataset, reveal)` (`BiResultMasking.java:120`) masks classified
  entity-key columns in result rows (`apply`, :449), refuses value-revealing aggregates over them (`vetMeasure`, :863), and
  vets oracles (`vetFields`, `vetAdHocSql`, `vetSort`, `vetParameterValues`, `scrub` for error text). Space mode
  `none` masks nothing for anyone (:141). Score previews use `EvidenceMasker.of(registry, root, datasetIds)`
  (`platform/inspecto-engine/.../mask/EvidenceMasker.java:90`) and `EntityKeyMasking.put(..., reveal)` for the echoed key.
- **`POST /db/query`** (`DbBrowserRoutes.java:155-186`, already agent-allowed): `SqlGuard.check` (:173) → 422; `ops:*`
  groups need `canAuthorWorkbench` and reveal (:197-218, so an agent never runs SQL there); a store read masks through
  `storeMasking` → `BiResultMasking` (:392-404); `limit` clamped to 5 000 (:436).
- **Rate limit.** `ControlApi.isRateLimited` (`ControlApi.java:1412-1428`) names `/db/query`, `/bi/query`, `/recon/*`,
  `/agent/*`, `/mcp`, the LA expensive routes and a few others; `rateLimit` (:1449-1477) keys a token bucket by
  `Subject.id` — **a person's agent has the person's id** (`OidcAuthenticator.java:213`), so it shares the person's
  bucket; a machine agent is `client:<name>`. Buckets: standard 20 burst, 1 per 3 s (`RateLimiter.java:30`); `/bi/query`
  dashboard 120 burst, 2/s (:35). **An MCP Tool call counts once, on `/mcp`; its in-process replay skips the stage**
  (`ControlApi.java:1416`, `replay` :1233-1281 has no `rateLimit`). A direct REST call with an agent token pays the
  route's own bucket, if any.
- **Cost.** Every row route runs in `SqlSandbox` with `SqlSandboxPolicy` defaults: 1 GB, 2 threads, 30 s
  (`platform/inspecto-sql/.../SqlSandboxPolicy.java:23-25`). Row caps are per route (below).
- **Audit.** `AuditTrail.record` (`platform/inspecto-access/.../AuditTrail.java:53-99`) stores method, path, status,
  actor, capability and `ATTR_AUDIT_ATTRS`; **never the body**. It skips every manifest `read-shaped` POST and every
  path ending `/test`, `/preview`, `/dry-run` (`classify`, :236-259). The MCP row `mcp.tool.called`
  (`features/inspecto-mcp/.../McpRoutes.java:354`, `audit` :459-487) stores tool name, `mcpReplay` = method + path,
  status, declared client and the verified `agent_client`; **never the arguments**. So today no sensitive input reaches
  an audit row — and **a direct agent call to a read-shaped route leaves no audit row at all**.

### 2.2 Per route

| Route (group) | Gate today | Reads | Masking today | Caller-supplied value compared to data | Rate limit today | Row / cost cap |
|---|---|---|---|---|---|---|
| `POST /decision-rules/{id}/simulate` (B) | `canAuthorWorkbench` | **no Dataset**: the body's `sampleRows` | n/a | — | none | sample size |
| `POST /rule-templates/{id}/simulate` (B) | `canAuthorWorkbench` | the template's `source` Dataset | ✅ `BiResultMasking` + `vetFields`, `vetAdHocSql`, `scrub` (`RuleRoutes.java:109-131`) | template holes (vetted) | none | 10 000 (:189) |
| `POST /enrichment/preview` (B) | `canAuthorWorkbench` | body `sampleRows` **joined to live Reference rows**; a path reference under the safety roots | ❌ none (`EnrichmentRoutes.java:143-165`; `EnrichmentEngine.preview` :293-325, sealed) | the transform is the caller's SQL (`SELECT * FROM <ref>` returns Reference rows) | none | 200 rows (:40) |
| `POST /bi/query` (D) | exemption | one Dataset, `ComponentAccess.canView` (`BiRoutes.java:97`) | ✅ `BiResultMasking` + `vet` (:107-152) | filters (vetted) | dashboard bucket | `ListLimitSettings.MAX_ROWS` (:51) |
| `POST /queries/{id}/run` (D) | exemption | the saved query's Dataset, `canView` (`QueryRoutes.java:90`) | ✅ `BiResultMasking` + vets + `scrub` (:97-125) | parameters (vetted) | **none** | 10 000 (:45) |
| `POST /recon/columns` (D) | exemption | named Datasets' schemas | ❌ (names only) | — | standard | — |
| `POST /recon/run` (D) | exemption | two or three Datasets, one day | ❌ none (`ReconRoutes.java:112-185`) | `filter` | standard | page 1..200 |
| `POST /recon/breaks` (D) | exemption | same | ❌ none (:186-240); break keys come back raw | `path`, `type` | standard | 5 000 (:63) |
| `POST /recon/rows` (D) | exemption | same | ❌ none (:251-283): raw rows of both sides | **`key` = every key column's value** (an oracle) | standard | 5 000 |
| `POST /risk-scores/preview` (E) | `canWorkIncidents` | the model's Datasets | ✅ evidence `EvidenceMasker`, key `EntityKeyMasking` (`RiskScoreRoutes.java:153`, :199-205) | **`entityKey`** (a score for any guessed key) | none | one entity, evidence capped |
| `POST /anomaly-scores/preview` (E) | `canWorkIncidents` | the model's Datasets | ✅ same (`AnomalyScoreRoutes.java:83-87`, :239) | **`entityKey`** | none | one entity |
| `POST /screening/check` (E) | exemption | Entity Lists | ✅ the matched `entry` is the list's shown (masked) form (`Screener.java:137`); a restricted list is refused unless the caller sees members (`ScreeningRoutes.java:78-79`) | **names and identifiers** (fuzzy) | none | `maxMatches` per subject |
| `POST /lookups/test` (`AGENT-LOOKUP-PREVIEW-1`) | **none — open read** | a loaded Pipeline's keyed join Reference | ❌ `matchedKey` and `mappings` raw (`LookupRoutes.java:176-240`) | `value`, `blockValues`; fuzzy `candidates` list keys never sent | **none** | `refresh:true` rebuilds an index up to 256 MiB (:96-130) |

Two premises in the widening plan are wrong and change the work:
- **Decision Rule simulate reads no Dataset and is not stateless:** it evaluates the body's sample and **writes**
  `lastSimulation` and `updatedAt` into the Decision Rule file (`DecisionRoutes.java:174-190`). It is a write, not a
  preview.
- **Recon caches answers across callers:** `ReconDay.Cache` is keyed by config fingerprint, day and arguments, not by
  caller (`ReconRoutes.java:259-262`, :280). A masking step must run after the cache read, or the cache key must carry
  the reveal flag.

## 3. The shared mechanism (as signed)

### 3.1 Masking: per route at the compute site, behind one fail-closed seam (D-AK13, D-AK14)

Lineage is known only where the rows are computed (which Dataset, which column, through which join), so masking stays
at the compute site, reusing `BiResultMasking` / `EvidenceMasker`. What is shared is the **proof that it ran**:

- `AgentGate.DATA_ROUTES` — the agent-allowed routes that answer data rows (starting with `POST /db/query` and the LA
  reads already allowed).
- `AnswerMasking.applied(ex, basis)` — a one-line stamp a handler sets after its masking step ran (basis = the Dataset
  or Reference ids and the masked column count). `BiResultMasking.apply` and `EvidenceMasker` set it themselves, so
  existing routes need no edit.
- In `ControlApi.handle` (both doors: dispatch and replay, the seam group A added at :1378): for an agent on a
  `DATA_ROUTES` entry, **a 2xx answer with no stamp is replaced by 500 `masking not proven` and the rows are never sent.**
  A route that forgot masking fails closed instead of leaking.
- **The Space's masking mode rules (D-AK14).** A Space with mode `none` masks nothing for any reader, agents included;
  the stamp is still set (zero masked), so the seam holds.

### 3.2 Caller-supplied values: raw values allowed, an accepted oracle (D-AK15, operator-accepted)

**Signed: an agent may send a raw value to compare with a masked column** — the recon `key`, a score preview's
`entityKey`, a lookup `value` and `blockValues`, a Rule Template hole. The answer stays masked (the matched key comes back
as the Space token), but the question is not.

⚠ **The risk, stated plainly: this is a membership oracle on masked data.** An agent that holds a guessed raw value
(a phone number, an account, a name from the person's prompt or a planted instruction) learns whether it is in a
Dataset or Reference, its score, its matched row's other (unmasked) columns and how it links to the masked token — and
so can unmask a token by guessing values that match it. Masking hides values from an agent that does not already know
them; it does not hide them from one that guesses.

**What still bounds it:** the per-agent budget (D-AK17: every guess is a weighted call, 429 past the budget); the
answer caps (D-AK16, D-AK18); the existing value-oracle vets where they already run (`vetFields`, `vetAdHocSql`,
`vetParameterValues` refuse comparing a hole or parameter with a masked column in `rule-templates` simulate and
`queries/run` — they keep refusing; D-AK15 adds no new raw path there); the audit rows of D-AK19 (the MCP row names
every call); and a machine agent's reach is its client's capability set. Screening was always name-matching by design.

### 3.3 Enumeration bounds (D-AK16, not as recommended: looser)

- `lookups/test`: fuzzy `candidates` **kept, capped at 5 and masked** like `matchedKey` and `mappings`.
- `screening/check`: **up to 10 matches** per subject (`maxMatches` clamped to 10 for an agent); entries stay the list's
  shown form.
- Recon: **ad-hoc reconciliations allowed** (an inline `config` naming any Datasets the caller can see); every side is
  masked and, after the §8 fix, checked with `ComponentAccess.canView`.

### 3.4 Rate limit: one agent budget, both doors (D-AK17)

A bucket keyed `agent:<agentClient>:<subject>` (so a person's agent cannot drain the person's own UI budget, and two
clients of one person do not share), charged **per data-route call at both doors** — the MCP replay included, which today
skips the stage. Each route has a weight (1 for a page read, 2 for a lookup or recon rows, 5 for a recon day run or a
score preview, 20 for a lookup call that builds its index). Budgets are install settings next to the LA `rate.*` limits
(`LaLimits`, re-applied by `applyInstallRateLimits`, `ControlApi.java:267-275`). 429 with `Retry-After`, as today. With
D-AK15 and D-AK16 looser, this budget is the main bound on guessing.

### 3.5 Cost bounds: the same as a person's request (D-AK18, not as recommended)

**No agent sandbox and no agent row cap.** An agent's call runs with exactly what a person's request has today:
`SqlSandboxPolicy` defaults (1 GB, 2 threads, 30 s), each route's own row cap (§2.2) and the 256 KiB body cap group A
set. `lookups/test` `refresh: true` stays allowed; it costs weight 20 in the agent budget, and the §8 lookup fix puts
the route on a rate limit for everyone. The only agent-specific cost control is therefore D-AK17.

### 3.6 Audit redaction rule (D-AK19, not as recommended: existing rows only)

1. **No new row.** The trail keeps `mcp.tool.called` (tool name, replay path, status, `agent_client`) and the route's
   own `AuditTrail` row where it writes one. ⚠ Accepted consequence: **an agent's direct REST call to a read-shaped
   route leaves no audit row** (`AuditTrail.classify` skips read-shaped and `/preview` paths, :236-259); only a call
   through MCP is recorded.
2. **No body value is ever written to an audit row or a log line** — the current behaviour, now a tested rule on every
   `DATA_ROUTES` entry.
3. **Screened names:** never in clear on any row. Should a row ever need to name a screened subject (for example a
   future `screening.check` row), it carries a keyed digest (HMAC under the Space masking key, `MaskTokens`), never the
   name or identifier.
4. Error text for an agent is scrubbed (`BiResultMasking.scrub`'s rule) on every `DATA_ROUTES` entry: a failed cast
   quotes a value.

### 3.7 Decision Rule simulate (D-AK20)

For an agent, the route answers the simulation (`matched`, `total`, `checkedAt`) **without writing** `lastSimulation`
or `updatedAt` into the rule file. A person's simulate is unchanged.

## 4. Per route (as signed)

| Route | Masking source | Caller values / enumeration | Agent weight | Cost bound (as a person's) | Audit | Test that proves it |
|---|---|---|---|---|---|---|
| decision-rules simulate | n/a (body sample) | — | 1 | body 256 KiB | route row | agent call leaves the rule file byte-identical |
| rule-templates simulate | `BiResultMasking` (exists) | holes vetted (exists) | 1 | 10 000 rows | none (simulate) + MCP row | masked column is a token |
| enrichment preview | **new**: joined Reference columns masked by the Reference's Dataset classification; path references refused for an agent (as group A's `withoutConfigRefs`) | transform over the Reference sees masked rows only | 2 | 200 rows | none (`/preview`) + MCP row | masked Reference column is a token; path reference 422 |
| bi/query | `BiResultMasking` (exists) | vets (exist) | 1 | `MAX_ROWS` | none (read-shaped) + MCP row | masked dimension is a token |
| queries/{id}/run | `BiResultMasking` (exists) | vets (exist) | 1 | 10 000 | as above | as above; agent 429 past budget |
| recon/columns | names only | — | 1 | — | as above | stamp set, zero masked |
| recon/run, breaks | **new** (the §8 recon fix): `BiResultMasking` per side, after the cache read; break keys as tokens | ad-hoc config allowed | 5 | page 200 / 5 000 | as above | masked key column is a token in breaks; shared-away Dataset 404 |
| recon/rows | as above | raw `key` allowed (D-AK15) | 2 | 5 000 | as above | rows masked; matched key echoed as token |
| risk / anomaly preview | `EvidenceMasker` + key masking (exist) | raw `entityKey` allowed (D-AK15) | 5 | one entity | none (`/preview`) + MCP row | key echoed as token, evidence masked |
| screening/check | list shown entry (exists) | ≤ 10 matches per subject | 2 | `maxMatches` | MCP row; no name in any row | sink and log hold no submitted name |
| lookups/test | **new** (the §8 lookup fix): `matchedKey`, `mappings`, `candidates` masked by the Reference's classification | `candidates` ≤ 5; raw `value` allowed | 1 (20 when it builds) | 256 MiB index, as today | none (`/test`) + MCP row | masked mapping and candidate are tokens; 6th candidate absent |

## 5. Decisions — signed 2026-11-26

Signed in an operator interview on 2026-11-26. The **Recommended** column is what this design proposed; **Signed** is the
decision.

| # | Question | Recommended | Signed |
|---|---|---|---|
| **D-AK13** | How is masking proven on agent data answers? | Per route at the compute site, behind one fail-closed stamp-or-500 seam in `ControlApi.handle` | **As recommended.** |
| **D-AK14** | A Space with masking mode `none`: do agents see raw values? | Yes — follow the Space | **As recommended.** |
| **D-AK15** | May an agent compare a raw value with a masked column? | No: tokens only, raw 422 | **Not as recommended — allow raw values**, operator-accepted with the membership-oracle risk of §3.2, bounded by D-AK16, D-AK17 and the audit rows. |
| **D-AK16** | Fuzzy and list answers for an agent | Drop lookup `candidates`; screening ≤ 3; recon on a saved Reconciliation only | **Not as recommended — looser:** `candidates` kept (≤ 5, masked); screening ≤ 10 matches; ad-hoc recon allowed. |
| **D-AK17** | Rate limit | One agent bucket family, weighted, both doors, install settings | **As recommended.** |
| **D-AK18** | Cost bounds | Agent sandbox 10 s / 512 MB / 1 thread, 500 rows, no index rebuild | **Not as recommended — the same as a person's request** (no agent sandbox, route caps as today). |
| **D-AK19** | Audit | A new `agent.data.read` row with a keyed digest | **Not as recommended — existing rows only:** `mcp.tool.called` and the route rows, with the redaction rule of §3.6 (screened names never in clear). |
| **D-AK20** | Decision Rule simulate writes a stamp | For an agent, answer without writing | **As recommended.** |
| **D-AK21** | Order and split | S0, D1, B, E, D2, `lookups/test`; one change and one security review each | **As recommended**, and the people defects first (§8): the recon fix lands before D2, the lookup fix before the lookup change. |

## 6. Proof plan

Every change: the widening plan's §2 proof (real-HTTP test with an agent token per route, person and machine, direct REST
behaves as the tool, `McpRoutesManifestParityTest`, `McpToolRoutesTest`, `openapi.fragment.json`, `spec-mcp.md`,
`node tools/check-agent-kit.mjs`), plus:

- **Masked column.** A Space Dataset (or Reference) with one classified key column; the agent's answer holds the token,
  never the raw value, per route — including the matched key of a raw-value question (D-AK15). **Falsify:** make the
  route's masking step a no-op in a test double — the seam answers 500 and the masked-column test fails; remove the seam
  check too and the test fails on the raw value.
- **Seam parity.** `AgentDataRoutesParityTest`: every `AgentGate.DATA_ROUTES` entry has a masked-column test; a route on
  the list whose handler sets no stamp answers 500 to an agent. A `none` Space answers raw (D-AK14).
- **Bounds (D-AK16).** Six fuzzy lookup candidates exist → five come back, masked; screening `maxMatches: 50` → at most 10.
- **Rate limit (D-AK17).** Past the agent budget → 429, once through MCP and once direct; the person's own bucket is
  untouched; a lookup that builds its index costs 20 (test clock seam, `ControlApi.java:277`).
- **Same cost as a person (D-AK18).** No agent-specific test; the route caps are pinned by the existing tests.
- **Audit (D-AK19).** Submit a distinctive name to `screening/check` and a key to `risk-scores/preview`, through MCP and
  direct; scan every event the sink received and the log capture: the strings are absent; `mcp.tool.called` is present
  for the MCP call.
- **Nothing persisted (D-AK20).** A before-and-after read for every route; Decision Rule simulate by an agent leaves the
  rule file byte-identical, by a person still stamps it.
- **People fixes (§8).** Each defect gets its own real-HTTP test, falsified: a Viewer's recon read of a shared-away
  Dataset → 404 and a masked key column → token; `lookups/test` without its capability → 403, masked mapping → token,
  past the budget → 429.

## 7. Effort (as signed)

| Change | Work | Estimate |
|---|---|---|
| S0 shared seam ✅ built 2026-11-26 | `DATA_ROUTES`, stamp, 500 seam, agent budget at both doors, no-body audit test, retrofit `/db/query` and LA reads, parity test ([as built](../okf/product/module/mcp/spec-agent-data-seam.md); budget defaults burst 60, 0.5/s chosen in S0) | 2–3 days |
| D1 | `bi/query`, `queries/run`: masking exists; tests | 1–1.5 days |
| B | rule-templates (exists, tests), decision simulate without stamp, enrichment Reference masking + path refusal (fixes the people defect too) | 3 days |
| E | score previews (raw keys, masked echo), screening ≤ 10, name-redaction tests | 2 days |
| Recon fix (people, §8) | `canView` and masking per side on four routes, post-cache | 2–3 days |
| D2 | recon in `DATA_ROUTES`, weights, agent tests | 1–1.5 days |
| Lookup fix (people, §8) | capability, masking incl. capped candidates, rate limit | 1.5–2 days |
| `lookups/test` for agents | `DATA_ROUTES`, weight 20 on a build, tests | 1 day |
| **Total** | | **~3–3.5 weeks**, one change and one security review each |

Against the proposal (~3–4 weeks, people fixes not counted): the agent-only work falls by about 4–5 days (no agent
sandbox, no new audit row or digest, no token resolution, no saved-id and candidate refusal), and the people fixes,
now in the order, add about 4–5 days, two of which D2 and the lookup change no longer spend.

## 8. Unsafe for people today — filed 2026-11-26, fixed first (D-AK21)

| Board row | Rank | What | Before |
|---|---|---|---|
| `RECON-READ-UNGATED-1` | P2 defect | `POST /recon/columns`, `/recon/run`, `/recon/breaks` and `/recon/rows` carry no capability, no `ComponentAccess.canView` and no masking (`ReconRoutes.java:70-76`, :251-283, :708-715): any signed-in caller reads the raw rows of any Dataset, a shared-away one or one with classified keys included; the answer cache is shared across callers. **Fixed 2026-11-26** for people: `canView` on every side (404 as an unknown Dataset), per-side masking through `DatasetCellMasking` after the cache read; spec `okf/product/module/reconciliation/spec-reconciliation.md` § Access and masking | D2 |
| `LOOKUP-TEST-OPEN-READ-1` ✅ **fixed 2026-11-26** (`canAuthorWorkbench`, `canView` on Datasets over the Reference's store, masking by classification, ≤ 5 masked candidates, per-caller test and reload budgets; a `path:` Reference has no classification and answers raw) | P2 defect | `POST /lookups/test` had no capability (`LookupRoutes.java:47`): any signed-in caller reads a Reference's mapped columns unmasked, enumerates keys through fuzzy `candidates`, and rebuilds an index of up to 256 MiB per call with `refresh: true`, with no rate limit | the lookup change |
| `ENRICHMENT-PREVIEW-UNMASKED-1` | P3 defect | a `canAuthorWorkbench` author without reveal gets Reference columns raw (the transform may select the Reference whole, 200 rows), and a path reference reads a store under the safety roots past the Dataset's masking (`EnrichmentRoutes.java:143-165`); group B's enrichment masking fixes it for people too | B |
| `RULE-TEMPLATE-SIMULATE-SHARING-1` | P3 defect | Rule Template simulate skips `canView` on its source Dataset (`RuleRoutes.java:99-102`); rows stay masked | B |
| `DECISION-RULE-SIMULATE-WRITES-1` | P3 defect | Decision Rule simulate writes the rule file outside versioning and any approval policy (`DecisionRoutes.java:185-189`) | — (D-AK20 covers agents) |
| `DB-QUERY-STORE-SHARING-1` | P3 chore (verify) | a `/db/query` store read checks the write root, `SqlGuard`, the path jail and the restricted quarantine but never `ComponentAccess.canView` (`DbBrowserRoutes.java:155-186`, :251-300); business-store reads were kept open by DB-QUERY-UNGATED-1, so whether a shared-away Dataset's store must be refused is unproven over HTTP and unsettled | S0 retrofits `/db/query` |

## References

- Widening plan § W6 and §2: [`2026-11-25-agent-kit-ak2-widening-plan.md`](2026-11-25-agent-kit-ak2-widening-plan.md)
- Group A as built: [`spec-agent-previews.md`](../okf/product/module/mcp/spec-agent-previews.md)
- Board: `docs/BACKLOG.md` rows `AGENT-KIT-1`, `AGENT-LOOKUP-PREVIEW-1`
