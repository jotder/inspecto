# Agent data answers: masking, enumeration, rate and cost bounds, audit redaction (AGENT-KIT-1 W6 groups B, D, E)

> 🟡 **PROPOSED 2026-11-26 — D-AK13…D-AK21 open; design only, nothing built.** Unblocks widening plan
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

## 3. The shared mechanism

### 3.1 Masking: per route at the compute site, behind one fail-closed seam

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

### 3.2 Enumeration: the caller's own values on a masked column

Masking the answer does not stop an oracle ("does key X exist? what is its score?"). Rule for an agent: **a value it
sends that is compared with a masked column must be the Space's `masked:` token, resolved server-side** (as
`RiskScoreRoutes` already resolves an alias, :335-340); a raw value there is 422. Screening is exempt (it matches names by
design) and is bounded instead (§3.4). Fuzzy lists (`candidates`) are dropped for an agent.

### 3.3 Rate limit: one agent bucket family, both doors

A bucket keyed `agent:<agentClient>:<subject>` (so a person's agent cannot drain the person's own UI budget, and two
clients of one person do not share), charged **per data-route call at both doors** — the MCP replay included, which today
skips the stage. Each route has a weight (1 for a page read, 5 for a recon day run or a score preview, 20 for an index
build). Budgets are install settings next to the LA `rate.*` limits (`LaLimits`, re-applied by
`applyInstallRateLimits`, `ControlApi.java:267-275`). 429 with `Retry-After`, as today.

### 3.4 Cost bounds

For an agent on a `DATA_ROUTES` entry: a tighter `SqlSandboxPolicy` (proposed 10 s, 512 MB, 1 thread); an answer cap of
500 rows (an agent pages); no index rebuild on request (`lookups/test` `refresh` refused, 422); `recon/run`, `breaks` and
`rows` only on a saved Reconciliation `id` (no inline `config`, which can name any Datasets); screening `maxMatches` ≤ 3 and
a threshold floor.

### 3.5 Audit redaction rule

1. **No body value is ever written to an audit row or log line** — the current behaviour, made a tested rule.
2. **A new row `agent.data.read`** (category `agent`) on every agent call to a `DATA_ROUTES` entry, both doors, so a
   direct REST read is no longer invisible: route, Dataset / Reference / Entity List ids, rows returned, masked column
   count, status, `agent_client`.
3. **`inputDigest`**: an HMAC of the sensitive inputs (names, identifiers, keys, lookup values) under the Space masking
   key (`MaskTokens`), so an investigator can tell that two calls asked about the same name, and one holding reveal can
   test a name, without the row holding it.
4. Error text for an agent is scrubbed (`BiResultMasking.scrub`'s rule) on every `DATA_ROUTES` entry: a failed cast
   quotes a value.

## 4. Per route

| Route | Masking source | Enumeration control | Rate weight | Cost bound | Audit inputs digested | Test that proves it |
|---|---|---|---|---|---|---|
| decision-rules simulate | n/a (body sample) | — | 1 | sample ≤ body cap 256 KiB | none | agent call writes nothing (before/after read of the rule file) |
| rule-templates simulate | `BiResultMasking` (exists) | holes vetted (exists); token rule | 1 | 500 rows, agent sandbox | hole values | masked column is a token; raw hole on masked field 422 |
| enrichment preview | **new**: mask joined Reference columns by the Reference's Dataset classification; refuse path references for an agent (as group A's `withoutConfigRefs`) | transform `SELECT *` of a Reference returns masked rows only | 2 | 200 rows (exists), agent sandbox | none (sample is the caller's) | masked Reference column is a token; path reference 422 |
| bi/query | `BiResultMasking` (exists) | vets (exist) | 1 | 500 rows | filter values | masked dimension is a token (agent token) |
| queries/{id}/run | `BiResultMasking` (exists) | vets (exist) | 1 | 500 rows | parameter values | as above; 429 past budget (route had none) |
| recon/columns | names only | — | 1 | — | none | stamp set with zero masked |
| recon/run, breaks | **new**: `BiResultMasking` per side, applied after the cache read; break keys as tokens | saved `id` only | 5 | page 200 / 500, agent sandbox | none | masked key column is a token in breaks; inline config 422 |
| recon/rows | **new**: as above | `key` on a masked column must be a token | 2 | 500 | key values | raw key 422; token key answers masked rows |
| risk / anomaly preview | `EvidenceMasker` + key masking (exist) | `entityKey` must be a token where the Entity Type is masked | 5 | one entity (exists) | entity key | raw key 422; key echoed as token |
| screening/check | list `shown` entry (exists) | `maxMatches` ≤ 3, threshold floor | 2 | subjects per call ≤ 20 | names, identifiers | audit sink holds no submitted name; digest present |
| lookups/test | **new**: mask `matchedKey` and `mappings` by the Reference's classification | `candidates` dropped; `value` on a masked key must be a token | 1 (20 if it builds) | `refresh` refused | value, block values | masked mapping is a token; no `candidates`; `refresh` 422 |

## 5. Decisions for the operator

| # | Question | Recommended |
|---|---|---|
| **D-AK13** | How is masking proven on agent data answers? | **Per route at the compute site, behind one fail-closed stamp-or-500 seam in `ControlApi.handle`** (§3.1). Not a generic post-pass on the JSON by column name: it cannot see joins, renames or derived columns. |
| **D-AK14** | A Space with masking mode `none`: do agents see raw values? | **Yes — follow the Space.** The mode is the Space's stated policy for every reader; an agent-only override would be a second policy to explain. |
| **D-AK15** | May an agent compare a raw value with a masked column (recon `key`, score `entityKey`, lookup `value`, template holes)? | **No: only the Space `masked:` token, resolved server-side; raw is 422.** Screening names exempt, bounded by D-AK16. |
| **D-AK16** | Fuzzy and list answers for an agent | **Drop lookup `candidates`; screening `maxMatches` ≤ 3 with a threshold floor and ≤ 20 subjects per call; recon only on a saved Reconciliation.** |
| **D-AK17** | Rate limit | **One agent bucket family keyed by agent client and subject, weighted per route, charged at both doors including the MCP replay, budgets as install settings.** |
| **D-AK18** | Cost bounds | **Agent sandbox 10 s / 512 MB / 1 thread, 500 rows per answer, no index rebuild on request.** |
| **D-AK19** | Audit | **A new `agent.data.read` row on every agent data read, ids and counts only, sensitive inputs as a keyed digest; the no-body rule pinned by a test.** |
| **D-AK20** | Decision Rule simulate writes a stamp | **For an agent, answer without writing the stamp** (a person's simulate is unchanged). Alternative: leave it out of group B. |
| **D-AK21** | Order and split | **S0 (the shared seam) first, then D1 (`bi/query`, `queries/run`), B, E, D2 (recon), then `lookups/test`; each one change with its own security review (D-AK10).** File and fix the people defects in §8 for recon and lookups before D2 and the lookup change. |

## 6. Proof plan

Every change: the widening plan's §2 proof (real-HTTP test with an agent token per route, person and machine, direct REST
behaves as the tool, `McpRoutesManifestParityTest`, `McpToolRoutesTest`, `openapi.fragment.json`, `spec-mcp.md`,
`node tools/check-agent-kit.mjs`), plus:

- **Masked column.** A Space Dataset (or Reference) with one classified key column; the agent's answer holds the token,
  never the raw value, per route. **Falsify:** make the route's masking step a no-op in a test double — the seam answers
  500 and the masked-column test fails; remove the seam check too and the test fails on the raw value.
- **Seam parity.** `AgentDataRoutesParityTest`: every `AgentGate.DATA_ROUTES` entry has a masked-column test; a route on
  the list whose handler sets no stamp answers 500 to an agent.
- **Oracle.** Raw value on a masked column → 422; the same as a token → 200 with masked rows.
- **Rate limit.** Past the budget → 429, once through MCP and once direct; the person's own bucket is untouched (test clock
  seam, `ControlApi.java:277`).
- **Cost.** A query past the agent timeout → 422 value-free; 501 rows asked → 500 and `truncated`.
- **Audit.** Submit a distinctive name to `screening/check` and a key to `risk-scores/preview`; scan every event the sink
  received and the log capture: the strings are absent, `agent.data.read` with `inputDigest` is present.
- **Nothing persisted.** A before-and-after read for every route (decision simulate: the rule file is byte-identical).

## 7. Effort

| Change | Work | Estimate |
|---|---|---|
| S0 shared seam | `DATA_ROUTES`, stamp, 500 seam, agent bucket at both doors, agent sandbox policy, `agent.data.read` + digest, retrofit `/db/query` and LA reads, parity test | 3–4 days |
| D1 | `bi/query`, `queries/run`: masking exists; token rule for parameters, caps, tests | 2 days |
| B | rule-templates (exists, tests), decision simulate without stamp, enrichment Reference masking + path refusal | 3–4 days |
| E | score previews (token keys), screening bounds, audit tests | 2–3 days |
| D2 | recon masking on four routes, post-cache, token keys, saved-id only | 4–5 days |
| `lookups/test` | masking, no candidates, no refresh, weight | 2 days |
| **Total** | | **~3–4 weeks**, one change and one security review each |

## 8. Unsafe for people today (candidate board rows — not fixed here)

- **Defect — recon reads ignore Dataset sharing and masking.** `POST /recon/columns|run|breaks|rows` carry no
  capability, no `ComponentAccess.canView` and no masking (`ReconRoutes.java:70-76`, :251-283, :708-715): any signed-in
  caller reads the raw rows of any Dataset, a shared-away one or one with classified keys included, and the answer cache
  is shared across callers. [items: module.reconciliation, module.access]
- **Defect — `POST /lookups/test` is an open read.** No capability (`LookupRoutes.java:47`): any signed-in caller reads a
  Reference's mapped columns unmasked, enumerates keys through fuzzy `candidates`, and with `refresh: true` rebuilds an
  index of up to 256 MiB per call with no rate limit. `AGENT-LOOKUP-PREVIEW-1` covers agents only. [items: module.elt,
  module.access]
- **Defect — enrichment preview returns Reference rows unmasked.** A `canAuthorWorkbench` author without reveal gets
  Reference columns raw (the transform may select the Reference whole, 200 rows), and a path reference reads any store
  under the safety roots, bypassing the Dataset's masking (`EnrichmentRoutes.java:143-165`). [items: module.elt]
- **Low — Rule Template simulate skips `canView`** on its source Dataset (`RuleRoutes.java:99-102`); rows stay masked.
- **Low — Decision Rule simulate writes the rule file** outside versioning and any approval policy
  (`DecisionRoutes.java:185-189`).
- **To verify — `POST /db/query` store reads:** no `ComponentAccess.canView` call was found in `DbBrowserRoutes`; if a
  store's Dataset is shared away, its rows may still be readable (masked). Check before filing.

## References

- Widening plan § W6 and §2: [`2026-11-25-agent-kit-ak2-widening-plan.md`](2026-11-25-agent-kit-ak2-widening-plan.md)
- Group A as built: [`spec-agent-previews.md`](../okf/product/module/mcp/spec-agent-previews.md)
- Board: `docs/BACKLOG.md` rows `AGENT-KIT-1`, `AGENT-LOOKUP-PREVIEW-1`
