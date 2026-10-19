# Doc claim audit — 2026-10-19 (PRODUCT-DOCS-HUB-1)

The doc guards (`check-doc-links`, `check-doc-citations`, `check-vocabulary`, …) prove that links, paths and
words are valid. They do not prove that what a doc says is true. This audit measures how far the
agent-written hub docs of 2026-10-19 match the implemented code.

## Method

- **Population.** 347 docs added under `docs/okf/product/**` by commits `63de97985`, `c9e6160dd`, `7d306dfaf`,
  `95f24f046`, `d25c8f91a`, `5b339d1d0` and `282e913f7`. Generated files (`index.md`, `feature-db.md`,
  `tests-inventory.md`, `api-routes.md`) were excluded.
- **Sample.** 30 docs weighted by slot: 10 admin-guide, 8 plan, 5 spec, 4 user-guide, 2 architecture and
  1 features. The docs were picked with a seeded shuffle, at most one doc per hub per slot, then topped up by
  hand to cover platform, LA, provider and solution-pack hubs.
- **Claims.** A claim counts only if it can be checked: a config/TOON key or default, a `-D` flag, an HTTP
  route and method, a class or interface name, a path on disk, a permission, edition or add-on membership,
  a built / not-built status, a count or a script parameter. Prose opinions were not counted.
- **Verification.** Each claim was checked against Java/TS sources, `offerings/*.toon`, `product-map/**`,
  `inspecto/package.ps1`, `docs/api/openapi-v1.json` and `docs/BACKLOG.md`. Each claim was classed as
  **verified** (evidence found), **wrong** (the code says otherwise) or **unverifiable** (no evidence either
  way). Five agents each checked six docs; the coordinator re-checked every reported error and every
  unverifiable claim.

## Results per slot

| Slot | Docs | Claims | Verified | Wrong | Unverifiable | Error rate |
|---|---|---|---|---|---|---|
| admin-guide | 10 | 210 | 206 | 4 | 0 | 1.9 % |
| plan | 8 | 153 | 151 | 2 | 0 | 1.3 % |
| spec | 5 | 131 | 130 | 1 | 0 | 0.8 % |
| user-guide | 4 | 24 | 24 | 0 | 0 | 0 % |
| architecture | 2 | 40 | 40 | 0 | 0 | 0 % |
| features | 1 | 22 | 22 | 0 | 0 | 0 % |
| **Total** | **30** | **580** | **573** | **7** | **0** | **1.2 %** |

One claim was first reported as unverifiable: `entity-store/plan.md` says its `LA-19` row is "recorded in
BACKLOG". The coordinator re-checked it and found `LA-19` in `docs/BACKLOG.md`, so it is counted as verified.

## Results per doc

| Doc (`docs/okf/product/…`) | Slot | Claims | Wrong |
|---|---|---|---|
| `module/case-management/admin-guide.md` | admin-guide | 22 | 0 |
| `module/secrets/admin-guide.md` | admin-guide | 14 | 0 |
| `module/agent-hosted/admin-guide.md` | admin-guide | 24 | 0 |
| `layer/product/admin-guide.md` | admin-guide | 24 | 1 |
| `solution-pack/la-daily-feed/admin-guide.md` | admin-guide | 12 | 0 |
| `solution-pack/telco-ra/admin-guide.md` | admin-guide | 18 | 1 |
| `solution-pack/orders-starter/admin-guide.md` | admin-guide | 12 | 0 |
| `module/la-store-pg/admin-guide.md` | admin-guide | 20 | 0 |
| `module/connectors-kafka/admin-guide.md` | admin-guide | 34 | 0 |
| `module/oidc/admin-guide.md` | admin-guide | 30 | 2 |
| `module/notify-channels/plan.md` | plan | 13 | 0 |
| `module/anomaly/plan.md` | plan | 16 | 1 |
| `module/entity-store/plan.md` | plan | 22 | 1 |
| `module/la-core/plan.md` | plan | 28 | 0 |
| `module/geo-link/plan.md` | plan | 14 | 0 |
| `module/la-store-pg/plan.md` | plan | 18 | 0 |
| `module/la-storage/plan.md` | plan | 22 | 0 |
| `module/reconciliation/plan.md` | plan | 20 | 0 |
| `module/api/spec.md` | spec | 9 | 0 |
| `module/backup/spec.md` | spec | 30 | 1 |
| `module/secrets/spec.md` | spec | 16 | 0 |
| `module/la-api/spec.md` | spec | 52 | 0 |
| `module/geo-country/spec.md` | spec | 24 | 0 |
| `module/notify-channels/user-guide-notifications.md` | user-guide | 6 | 0 |
| `solution-pack/la-showcase-off/user-guide.md` | user-guide | 4 | 0 |
| `module/demo-auth/user-guide.md` | user-guide | 9 | 0 |
| `module/screening/user-guide-screening.md` | user-guide | 5 | 0 |
| `module/telecom-asn1/architecture.md` | architecture | 24 | 0 |
| `module/entity-list/architecture.md` | architecture | 16 | 0 |
| `module/la-core/features-link-analysis.md` | features | 22 | 0 |

## Wrong claims and corrections (all fixed in place)

| Doc | As written | Correction | Evidence |
|---|---|---|---|
| `layer/product/admin-guide.md` | `-DemoAuth` requires `-Edition Advanced` | Accepts `-Edition Advanced` **or** `-Edition Preview` | `inspecto/package.ps1` (`-DemoAuth` guard, LA-EDITIONS-1 ED-3) |
| `solution-pack/telco-ra/admin-guide.md` | Job `ra_switch_kpi_completeness_job` | That is the file name; the Job is `ra_switch_kpi_completeness` | the pack's `*_job.toon` |
| `module/oidc/admin-guide.md` | `auth.oidc.tokenEndpoint` is never derived for `generic` / `wso2` | Discovery fills it for every provider; only the built-in URL paths are Keycloak-only | `OidcConfig.java` |
| `module/oidc/admin-guide.md` | `auth.oidc.scopes` default "from discovery / preset" | No default; read only when set | `OidcTokenRelay.java` |
| `module/anomaly/plan.md` | `ANOMALY-UI-FINDINGS-1` (a)–(j) all shipped 2026-10-14 | Shipped 2026-10-14 to 2026-10-17; (g) only partly | `docs/BACKLOG.md`, git log |
| `module/entity-store/plan.md` | `ASSURE-ENTITY-LISTS-RESIDUALS-1` still open | Closed 2026-10-18; its rest moved to BACKLOG §6 | `docs/BACKLOG.md` |
| `module/backup/spec.md` | Allowed roots = `-Dassist.safety.roots` plus every hosted Space base | Plus the calling Space's own base only (`CROSS-SPACE-JAIL-1`) | `SafetyPolicy.defaultPolicy()` |

The `backup/spec.md` row for `maintenance.restore.completed` also had a blank severity. It now says
`INFO`.

## Patterns and sweeps

The 7 errors fall into four patterns. No pattern is systematic: no slot shows invented defaults,
made-up routes or made-up class names. Every pattern was still searched for across all of
`docs/okf/product/**`:

1. **Stale `-DemoAuth` edition rule** (it predates LA-EDITIONS-1). Fixed also in
   `layer/platform/admin-guide-local-testing.md`, which said "accepts only `-Edition Advanced`, so there is
   no Preview demo build". That doc was not agent-written, so it is outside the error-rate counts.
2. **Allowed roots from before `CROSS-SPACE-JAIL-1`** ("every hosted Space base"). Fixed also in
   `module/backup/admin-guide-backup-restore.md`. The other hits are about different things:
   read access in `admin-guide-auth-security.md`, demo-user lists in `action-requests/spec.md`,
   DuckDB `DiscoveredRoots` in `spec-duckdb.md`, and a decision record that already says "never every hosted
   Space's". They were left as they are.
3. **Job cited by its file name.** The sweep found no other doc that names a Job with a `_job` suffix outside a
   file name.
4. **A shared "Default" cell over several keys** (the OIDC row). In the sample, this is the only multi-key
   row whose cell was wrong. It is a review hint, not a sweepable string.

Also flagged, not a doc error: the Javadoc at `PgInvestigationStore.java` still says "NOT wired anywhere
yet". `la-store-pg/plan.md` already reports this.

## Verdict

About 1 checkable claim in 80 was wrong (1.2 %; 95 % Wilson interval ≈ 0.6–2.5 %), and none was
unverifiable. Admin guides carry the most risk, because they hold the most defaults and flags. Every
error was **drift**: the code changed after the source text was written (LA-EDITIONS-1, CROSS-SPACE-JAIL-1,
BACKLOG closures on 2026-10-18). None was a fabrication. Docs therefore need a drift check when such
changes land, more than a check for invented content.

## Second pass — the unsampled admin-guide and plan docs (2026-10-19)

`DOCS-AGENT-WRITTEN-REVIEW-1` asked for the unsampled `admin-guide*` and `plan*` docs to be reviewed first.
102 docs were split into six batches and audited by read-only agents with the same method as above. The
coordinator re-checked a sample of each batch's reports against the code before editing. Counts are the agents'
own tallies and approximate.

| Batch | Docs | Wrong claims reported |
|---|---|---|
| 0 (layer/platform: incidents, ops guides, plans) | 16 | ~110 |
| 1 (access, acquire, agent, api, config) | 18 | ~65 |
| 2 (config, connectors, engine, etl, entity, event) | 17 | ~57 |
| 3 (exchange, geo, intelligence, la, observability) | 18 | ~31 |
| 4 (ops, processor, scoring, util, …) | 20 | ~26 |
| 5 (solution packs, workflow) | 13 | ~21 |

**The error rate is far above the first sample's 1.2 %.** The first sample was a seeded shuffle with one doc per
hub per slot; the long plan docs (`plan-control-api`, `plan-security`, `plan-editions`, `plan-observability`,
`admin-guide-operations-reference`) carry most of the wrong claims, because they were written in 2026-09 and
describe a state that has since shipped.

### Fixed in this pass (verified, then edited)

`module/workflow/plan`, `solution-pack/aml/admin-guide`, `la-audit` · `la-claims` · `la-showcase` runbooks,
`module/ops/plan`, `processor/admin-guide`, `scoring/admin-guide`, `util/admin-guide`,
`processor/plan-control-api` (counts), `geo-link/admin-guide`, `oidc/plan`, `intelligence/plan`,
`la-core/admin-guide-demo` · `-real-signin`, `observability/plan-observability` (one claim),
`config/admin-guide-configuration-reference`, `acquire/plan-acquisition` (one claim), `access/plan-security` (two).

### Verified stale and NOT yet fixed — work list

1. **"Not built / not shipped" claims that have since shipped** (highest value):
   `agent/plan-assistant` (bundles do ship `inspecto-agent` / `inspecto-intelligence`; `AgentAskResult.artifact`
   has a producer; `component_draft` has hosts); `plan-observability` (audit hash chain, `/audit/verify`,
   sign-in audit events, the `/signals/stream` EventSource consumer); `access/plan-security` and
   `admin-guide-auth-security` (HTTPS test, policy-editor browser pass, maker-checker, OIDC discovery, the
   `id_token_hint` cookie, the `auth` block in `/bootstrap`); `plan-data-plane` and `plan-studio` (the SPA does
   call `POST /queries/{id}/run`; Materialize button and job exist); `plan-incidents`, `admin-guide-operations-reference`
   (escalation rules mutate an Incident and emit `OBJECT_ESCALATED`).
2. **Routes that no longer exist**: `admin-guide-operations-reference` documents `/pipelines/{name}/…` (now
   `/runs/{id}/…`), an `ALERT` object type, an `ISSUE` lifecycle and `ingest` Job kind; a `batch_id` column in
   the status CSV (now `consignment_id`).
3. **Edition model**: `/bootstrap` reports `basic | standard | advanced`; the retired names Personal /
   Professional / Enterprise survive in most plans; `plan-editions` module and jar counts, Java floor (compile
   target is 27), profile count, example count.
4. **Counts that drifted**: `plan-control-api` (OpenAPI 466 paths / 556 operations; WriteGates 241 calls in 69
   files; PublicApi `since` values; ETag call sites); `plan-tooling` (40 `check-*.mjs`, 3 CI jobs, 12 skills,
   49 `.claude` files); `plan-surfaces` (21 capabilities, 14 feature flags, 17 Settings sections, 51 route
   files); `plan-compliance` (9 evidence files, 60 files import `WriteGates`, 89 artifacts).
5. **Stale `file:line` citations**, everywhere. Cite the symbol, not the line; drop the numbers on the next touch.
6. **Dangling BACKLOG pointers**: `CLIENT-HALVES-1`, `SPEC-STALEREF-1`, `SPEC-NOPROOF-1`, `OPENAPI-GEN-1`,
   `DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1` (wrong row), `SEC-INCIDENT-1`.
7. **Batch 2 (config, connectors, engine, etl)**: `plan-metamodel` (30 writable kinds not 24; delete protection also owner-only, stranded-objects and runbook-link 409s; schemas already use `mapping.fields[]`; the Components pane does send `If-Match`; `transform.mask` enforces classification; `findings-spec` editor and Entity Projection are built); `plan-spaces` (13 templates not 2 or 5; telco-fraud is `minEdition: standard`; test counts; zero Spaces is a clean state); `plan-pipeline-execution` (23 built-in maintenance tasks + 5 contributed; six trigger kinds; DuckDB memory default exists; `JobDeadline`, replay-rejects, retry block, scaffold-step and Platform Services stages 2/3 all shipped; parity gate met); `plan-pipeline-authoring` (SQL is guarded on save; 46 node types; Step-kind registry seam is live); `plan-warehouse-targets` (`sinks:` fan-out, `objectstore.export` and `publish.postgres` are built); `plan-ingestion` (Expectations UI exists; 144 example `.toon`); `connectors` runbook (seven schemes, secrets from `inspecto-secrets`); `entity-store/admin-guide` (`masking_mode`); `entity-list/plan` (`LA17-NORMALISER-CHANGE-1` closed).
8. **Minor**: `admin-guide-gotchas` (JToon tabular round-trip, `dirs.*` jail, `source:` vs `collector:`),
   `admin-guide-local-testing` (demo-users template, Postgres default on Advanced, edition detection),
   `acquire/plan-acquisition` (about a dozen), `la-core/admin-guide-demo` (Alerts page and embed SPA exist,
   `fm.analyst` can Reveal, smoke count), `case-management/plan` (resolution gate), `api/plan-api-stability`.

### Verified correct — do not "fix"

`OperationalDb.Family` "sixteen" (12 core + 4 from `inspecto-ops`); the guarded counts 21 modules / 28 jars /
89 locked dependencies; the `v3.11.0` merged tag.
