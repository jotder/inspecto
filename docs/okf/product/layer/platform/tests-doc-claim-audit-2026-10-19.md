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
| `module/la-core/features.md` | features | 22 | 0 |

## Wrong claims and corrections (all fixed in place)

| Doc | As written | Correction | Evidence |
|---|---|---|---|
| `layer/product/admin-guide.md` | `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* requires `-Edition Advanced` | Accepts `-Edition Advanced` **or** `-Edition Preview` | `inspecto/package.ps1` (`-DemoAuth` guard, LA-EDITIONS-1 ED-3) |
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

1. **Stale `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* edition rule** (it predates LA-EDITIONS-1). Fixed also in
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
   ✅ **FIXED 2026-11-01** (re-verified against code first): `agent/plan-assistant` (bundles: `inspecto-agent`
   Standard+, `inspecto-intelligence` Advanced; `AgentAskResult.artifact` has a producer; `component_draft` has
   hosts — 17 claims); `plan-observability` (audit hash chain, `/audit/verify`, sign-in audit events, the
   `/signals/stream` EventSource consumer — 7); `plan-data-plane` and `plan-studio` (the SPA calls
   `POST /queries/{id}/run` via *Run saved*; Materialize button and job exist — 10); `plan-incidents`,
   `admin-guide-operations-reference` (Escalation Rules mutate an Incident and emit `OBJECT_ESCALATED` — 2).
   ⏸ **Open, owned by the access lane:** `access/plan-security` and `admin-guide-auth-security` (HTTPS test,
   policy-editor browser pass, maker-checker, OIDC discovery, the `id_token_hint` cookie, the `auth` block in
   `/bootstrap`). ✅ **FIXED 2026-11-17 (batch 4)** — item 9.
2. **Routes that no longer exist**: ✅ **FIXED 2026-11-01** in `admin-guide-operations-reference`:
   `/pipelines/{name}/…` → `/runs/{runId}/…` (route table and prose; `GET|POST /pipelines` → `/runs` for the
   run list and live registration — 4 claims) and the `batch_id` columns → `consignment_id` (status CSV,
   batches CSV, commit log — 3). ✅ **FIXED 2026-11-01 (batch 2):** Alerts live in `AlertStore` / `/alerts*`,
   not as `ALERT` objects (`ObjectType` = `INCIDENT | CASE | TASK`); the Incident lifecycle is
   `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`; there is no `ingest` Job (`JobType` + `JobTypeProvider`);
   a `SEQUENCE_GAP` becomes a stored Alert; Case links name Incidents, and the stale "Issue Tracker heading"
   note went — 9 claims, all in `admin-guide-operations-reference`.
3. **Edition model**: ✅ **FIXED 2026-11-01 (batch 2):** Personal / Professional / Enterprise → Basic / Standard /
   Advanced in 16 plan / admin-guide docs (156 lines; lines that cite the old names as history kept them);
   `plan-editions` Java floor (compile target 27 — the table and two "stays 24" statements, the stale "two
   documents claim 26"), profile count (three edition profiles + `coverage`, ×2), example count (49 examples,
   144 `.toon`); `plan-surfaces` `/bootstrap` edition derivation (`basic | standard | advanced` from
   `BootstrapRoutes.edition()`). Module and jar counts were already guarded (21 / 28). ✅ **FIXED 2026-11-15
   (batch 3)** in `layer/product/plan-maturity-assessment`, by hand: the four 2026-10-14 rehearsals ran on an
   "Enterprise" bundle, now "Enterprise (today Advanced)" (the rename landed 2026-10-17, `LA-EDITIONS-1`); "no
   licence engine" (×5) now records D-PA22 — since 2026-11-11 `Entitlements` verifies a signed entitlement file
   that narrows the Installed gate, and its `nodes` is recorded, not enforced; `LA17-NORMALISER-CHANGE-1` (×3)
   closed 2026-09-27 (D-M9, `EntityListRoutes` seals the normaliser); four line pointers (`spec.md l.…`,
   "`docs/BACKLOG.md` line 76") now name a section — 16 claims. Already right: "Enterprise grade", "Enterprise
   hooks" and the market comparisons (the market word), the nine `No*ShipsInThePersonalBuildTest` classes, and the
   seven code facts behind its `file:line` citations (ledger default `memory`, `LIVE_RUN_CAP = 1000`, Recon
   tolerances and gates, Jaro-Winkler, Kafka SASL default, `ColumnClassification.SENSITIVE`) — 14.
4. **Counts that drifted**: ✅ **FIXED 2026-11-01 (batch 2), re-measured that day:** `plan-control-api`
   (OpenAPI 487 paths / 581 operations, 147 hand-written + 434 generated of which 186 optional-module;
   `@PublicApi` 244 files, 256 of 286 `since` values `4.0.0`; `WriteGates` 71 main files / 253 call lines);
   `plan-tooling` (61 `check-*.mjs`, 3 CI jobs, `.claude/` = 3 entry docs + memory, 4 agents, 5 hooks, 12
   skills, 50 tracked files); `plan-surfaces` (22 capabilities, 17 `features` keys, 17 Settings sections, 51
   route files); `plan-compliance` (9 evidence files; the assistant module is packaged since `PKG-5`) — 16 claims.
   Not changed: ETag call sites and the `WriteGates` importer count (no such count left in the docs),
   `plan-compliance`'s 89 locked dependencies (guarded, correct).
5. **Stale `file:line` citations**, everywhere. Cite the symbol, not the line; drop the numbers on the next touch.
   ✅ **Done 2026-11-15 (batch 3) in every doc this batch touched:** about 100 line numbers dropped (all of
   `acquire/plan-acquisition`, and `plan-control-api`, `plan-metamodel`, `plan-maturity-assessment`,
   `plan-assistant`, `plan-ingestion`, `plan-spaces`, `plan-data-plane`, `plan-api-stability`,
   `user-guide-inline-ai-authoring`), each cited file checked to exist first; the one that did not
   (`onboarding.md`) is marked retired. Line numbers were wrong where sampled (`AgentRoutes`, `JobService`,
   `ConsignmentIngestor`). ⏸ Still open: `access/plan-security` (23, with the access lane) and about 150 in
   docs not touched yet — the same rule, on their next touch. ✅ **`access/` done 2026-11-17 (batch 4)** — item 9;
   the ~150 in untouched docs stay open. ✅ **Those done 2026-11-17 (batch 5)** — item 10.
6. **Dangling BACKLOG pointers**: ✅ **FIXED 2026-11-01 (batch 2):** `CLIENT-HALVES-1` (×4 — row closed),
   `OPENAPI-GEN-1` (shipped 2026-09-15), `SEC-INCIDENT-1` (`plan-compliance` §5.2 said the carry-forwards were
   not done; the controls matrix CC6.1 records both done), `DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1` in
   `plan-editions` (×2 → the *Deployment topology live validation* row) — 8 claims. ✅ **FIXED 2026-11-15
   (batch 3):** the "filed as cross-cutting rows" blockquotes of 14 plans (`plan-metamodel`,
   `plan-pipeline-authoring`, `plan-ingestion`, `plan-pipeline-execution`, `plan-control-api`, `plan-assistant`,
   `plan-security`, `plan-surfaces`, `plan-studio`, `plan-observability`, `plan-editions`, `plan-data-plane`,
   `plan-tooling`, `plan-compliance`) each gained a dated line: none of the ids is an open board row —
   `SPEC-STALEREF-1` and `SPEC-NOPROOF-1` closed 2026-09-09, the rest closed, were refuted or were split by the
   2026-09-24 consolidation (verified with `git log -S` on `docs/BACKLOG.md`). Two pages said work "stays open
   under" / "belongs to" `SPEC-NOPROOF-1`: `plan-data-plane` §8 (the DuckLake real-driver failure; it also named
   one registrar caller where there are two lanes, `ConsignmentIngestor` and `PipelineJobRunner`) and
   `intelligence/user-guide-inline-ai-authoring` (the UI's 403/404/422 renderings) — both now say no board row
   tracks it — 17 claims. Already right: the three "closing part of `SPEC-NOPROOF-1`" history notes
   (`plan-control-api` §8, `plan-pipeline-execution`, `plan-editions`).
7. **Batch 2 (config, connectors, engine, etl)**: `plan-metamodel` (30 writable kinds not 24; delete protection also owner-only, stranded-objects and runbook-link 409s; schemas already use `mapping.fields[]`; the Components pane does send `If-Match`; `transform.mask` enforces classification; `findings-spec` editor and Entity Projection are built); `plan-spaces` (13 templates not 2 or 5; telco-fraud is `minEdition: standard`; test counts; zero Spaces is a clean state); `plan-pipeline-execution` (23 built-in maintenance tasks + 5 contributed; six trigger kinds; DuckDB memory default exists; `JobDeadline`, replay-rejects, retry block, scaffold-step and Platform Services stages 2/3 all shipped; parity gate met); `plan-pipeline-authoring` (SQL is guarded on save; 46 node types; Step-kind registry seam is live); `plan-warehouse-targets` (`sinks:` fan-out, `objectstore.export` and `publish.postgres` are built); `plan-ingestion` (Expectations UI exists; 144 example `.toon`); `connectors` runbook (seven schemes, secrets from `inspecto-secrets`); `entity-store/admin-guide` (`masking_mode`); `entity-list/plan` (`LA17-NORMALISER-CHANGE-1` closed).
   ✅ **FIXED 2026-11-15 (batch 3), re-verified against code first — 28 of 30 claims; two of the work list's own
   numbers were stale too:** writable kinds are **34** (`ComponentStore.WRITABLE_TYPES`), not 30, and only 4 have
   a `ConfigSpec`; delete refusals listed (owner-only 403, `use:` / grant / stranded-objects / runbook-link /
   Mapping 409s, census links refused only under `refuse`); 201 `*_schema.toon` use `fields[]`; `If-Match` is sent
   by the schema editor only in the Components pane (`component-form` and `mapping-editor` send none — partly
   true); the `findings-spec` editor and Entity Projection (`InvRoutes`) exist; **30** Space templates (10 + 20
   `la-*`), telco-fraud `minEdition: standard`, Space test counts re-counted (130); maintenance tasks 23 + 5,
   six trigger kinds (`STREAM`), DuckDB memory default (`DuckDbUtil.defaultMemoryLimit`), `JobDeadline`,
   replay-rejects, the retry block, `tools/scaffold.mjs new step`, Platform Services S2/S3 and the parity gate
   (met 2026-09-16); SQL guarded on save (`PipelineEditable.refuseUnsafeSql`), **49** node types (now
   count-guarded), the Step-kind registry live; `sinks:` fan-out, `objectstore.export`, `publish.postgres`; the
   Expectations UI (since 2026-07-04), 144 example `.toon`; seven connector schemes plus `kafka` in its own
   module, `${FILE}` / `${KEYSTORE}` from `inspecto-secrets`; `masking_mode`; `LA17-NORMALISER-CHANGE-1` moved to
   Built. Already right: zero Spaces is a clean state; classification as a closed vocabulary.
8. **Minor**: `admin-guide-gotchas` (JToon tabular round-trip, `dirs.*` jail, `source:` vs `collector:`),
   `admin-guide-local-testing` (demo-users template, Postgres default on Advanced, edition detection),
   `acquire/plan-acquisition` (about a dozen), `la-core/admin-guide-demo` (Alerts page and embed SPA exist,
   `fm.analyst` can Reveal, smoke count), `case-management/plan` (resolution gate), `api/plan-api-stability`.
   ✅ **FIXED 2026-11-15 (batch 3), re-verified against code first — about 65 claims checked, 36 fixed, 28
   already right:** `admin-guide-gotchas` (a Java-built `List<Map>` now round-trips `ConfigCodec`; `grammar`
   resolves beside the config and `dirs.*` under the Space through `PathJail.dataPath`; the parser reads only
   `collector:` — 3); `admin-guide-local-testing` (18 `la-*` templates ship `demo-users.toon`; Advanced boots on
   Postgres and refuses without `INSPECTO_DB_URL`; launchers read `lib/edition.properties`, and `/bootstrap` says
   `advanced` for the demo build — 3; the note on the demo switch removed 2026-11-10 was already right); `acquire/plan-acquisition`
   (about 24: the acquire tick lives in `PipelineScheduler`, the intake cap is read in `CollectorProcessor.collect`
   with default 500, seven schemes, twelve acquisition metrics, connection test/probe gated by
   `canOnboardConnections`, an unknown connection is a save-time ERROR, the Completeness-KPI hold lifted, test and
   example counts, retired-doc pointers); `case-management/plan` (the resolution gate is built:
   `ObjectService.incidentResolutionGaps` answers 422, the UI hard-gates — 1); `api/plan-api-stability` (three
   `since` values that never existed — `"5.0.0"` ×2, `"5.2.0"` — `EnrichmentAuditReader` / `JobConfig` are
   `4.0.0`, `ConsignmentEvent` has 12 components, `ConsignmentPlanner` / `Consignment` — 5). Also fixed: the
   *Collector rename residual* decided note in `docs/BACKLOG.md`, which still said the `source:` block stays.
   ⏸ Skipped: `la-core/admin-guide-demo` (a peer owns the `la-*` hubs). Found in code, not docs: the three
   `since = "5.x"` annotations, and a `PipelineExecutionParityTest` comment repeating the old `toToon` warning.
   ✅ **`la-core/admin-guide-demo` FIXED 2026-11-17 (batch 4)** — item 9.
9. **Batch 4 (2026-11-17, MoSCoW S16)**, every claim re-verified against code first:
   - **`access/` (item 1 and item 5's 23).** `plan-security`: 28 claims fixed — `SEC-12` is built (the server
     publishes `/bootstrap` `auth` through `TokenRelay.bootstrapAuth` since 2026-10-10, and `POST /auth/logout`
     returns the end-session URL with `id_token_hint` from the HttpOnly `inspecto_idt` cookie since 2026-10-13, so
     §3.5 and two new §4 rows say so); OIDC discovery and the provider preset (`OidcConfig`, 2026-10-13) in the §3.4
     property table and §4; the Policies-editor browser pass ran 2026-09-27 (§3.10, §5); HTTPS is tested
     (`ControlApiHttpsTest`, §3.11 and §8.7 still said "no test"); real-IdP tests exist but are opt-in
     (`OidcAgainstRealProviderTest`, `OidcKeycloakAgainstRealProviderTest`); the boot precondition is tested
     (`AuthenticatorDiscoveryFailClosedTest`); audit tamper evidence is built (`AuditChain`, `/audit/verify`);
     §5's dangling pointers (`DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1` for Vault, the done SEC-INCIDENT-1
     carry-forwards, a §5 *Compliance repo-side artifacts* row that does not exist, `CLIENT-HALVES-1` ×2); the
     `Subject` record has seven components, not four (×2); `AccessPolicies.FILE` / `.effective` are
     `AccessPolicyStore`'s; the gating order is `ControlApi.routeDispatch` with `SpaceAdmission` after stage 1; the
     capability vocabulary is 23, not 20 (five rows added: `canUploadData`, `canApproveChanges`,
     `canManageExclusionCategories`, `canOpenInboundInvestigation`, `canOfferSignals`), the manifest 293
     registrations, not 70; `RowScope` has six more users; `-Pedition-standard|enterprise` named a profile that
     does not exist; and a reading note for the old edition names (about 50 mentions, dated decisions keep them).
     **38 line numbers dropped** (the work list said 23; each cited file and symbol checked to exist first). Already
     right, 12: `authMode` is declared, not derived (×3); "module present, auth off" is impossible; `permissions[]`
     has no reader (×2); `Subject` / `SecretsProvider` lack `@PublicApi`; the default reactor compiles neither
     module; the default bind is every interface; the UI seam has no concept file; the seeds; `/access/explain` is
     an ungated GET. `admin-guide-auth-security`: 7 fixed (`id_token_hint`, where `endSessionUrl` comes from, the
     browser pass, "no discovery fetch", "maker-checker, which is not on `master`", an incomplete denylist — now
     points at `ReservedConfigPaths` (34 files, 12 directories) — and "only under `edition-standard`") and 3 line
     numbers dropped; 5 already right. `access/architecture`: the "discovery drift" bullet is resolved (1).
     `spec`, `features-access`, `user-guide-access`, `api-access`: about 20 claims, all right.
   - **`la-core/admin-guide-demo` (item 8).** 11 claims fixed in 15 places: the smoke prints `17/17`, not `9/9`
     (eight probes added 2026-10-12; ×4); `analyst` holds `canRevealLinkEntities` and `canShareInvestigation`
     (`la-analyst`) and `admin` also `fraud-analyst`; the la-app has its own Alerts area with Open Investigation
     since 2026-10-12 (×4: S12, its UNVERIFIED note, the Q&A line, discrepancy 5); an embed UI exists (S14 said
     "no SPA"); Q25's editions (Link Analysis jars staged in every edition since `LA-EDITIONS-1`, Basic = the
     Explorer, no "Professional alias", not "absent in Personal") and Q8's "Enterprise". Already right, 8: the
     `manager` / `reviewer` / `outsider` rows, `reviewer` has no working Reveal, embed is built and dark without
     `-Dla.embed.enabled` (503), the six gate probes. Left: the "Personal 503" variant mentions (a runbook name).
   - **Item (c), the unsampled `spec*` / `user-guide*` / `architecture*` docs.** The population is 100 docs of
     those slots added by the seven 2026-10-19 commits; 11 were in the first sample, so 89 were unsampled. **19
     done**, about 200 claims checked, 1 wrong: `ops/spec` named one feature, the module provides `ops` and
     `alert.dispatch`. Right as written: `access/spec`, `access/user-guide-access`, `oidc/spec`,
     `policy/spec` · `user-guide` · `architecture`, `demo-auth/spec` · `architecture`, `auth-spi`, `audit-spi` and
     `http-spi` `architecture`, `ops/architecture`, `reconciliation/spec` (every constant), `entity-store/spec`,
     `connectors-kafka/spec` · `architecture`, `observability/spec`, `secrets/architecture`. These recently
     maintained hubs were near-clean, as the first sample was. **70 left**: the `la-*`, `geo-*`, `graph`, `sql`,
     `util`, `workflow`, `scoring`, `case-management`, `intelligence`, `asn-decoders`, `telecom-asn1` and smaller
     module hubs, five `layer/*` pages, and 23 solution-pack user guides and architecture pages.
   - **Found in code, not docs:** the `AccessPolicies` Javadoc cites `{@value #FILE}`, a constant that moved to
     `AccessPolicyStore`; the `Authenticators` Javadoc says its slot is "the only one" that is fail-closed, but
     `AccessDeciders` and `InvestigationStores` are too.
   - ✅ **The 70 and the ~150 line numbers: done 2026-11-17 (batch 5)** — item 10.
10. **Batch 5 (2026-11-17, MoSCoW S16) — the rest of item 9 (c) and item 5's line numbers**, every changed claim
   re-verified against code first:
   - **Item (c): all 70 remaining docs checked**, about 780 claims, **45 fixed** in 30 docs; the other 40 docs were
     right as written. About half the fixes are lists that missed members added after 2026-10-19 (new modules,
     route modules, industry-pack sizes); the rest are wrong facts. By hub:
     - **Module hubs.** `case-management/spec` (`-Dobjects.backend` also takes `memory`); `intelligence/spec`
       (Triage Runs and approvals are durable `DurableJsonlRing` files under `<assist.write.root>/agent/`, not
       in-memory; only the autonomy ledger is); `graph/spec` (`shortestPath` and k-core live in `GraphAlgorithms`,
       not `GraphPaths` / `GraphStructure`; `UnionFind` also serves `GraphPaths` and `EntityGrouping` — 3);
       `sql/architecture` (the guard and sandbox also serve Consignment readers, Reconciliation, the BI, query,
       rule and save gates and the agent's SQL skills); `util/spec` · `architecture` (the spill directory is
       `<data root>/.duckdb_tmp`; `memory_limit` is never left to DuckDB — server value, flag, then the
       `defaultMemoryLimit` code default; `db.pool.timeoutMs` defaults to 30000 — 4); `etl/user-guide-parsing-ingest`
       (Run detail has seven tabs, not five); `entity-list/user-guide` (Refresh is open to every reader, and the
       category attributes need `canManageExclusionCategories` — 2); `api/architecture` (the `@PublicApi` surface
       IS held, by `tools/check-public-api.mjs` since 2026-11-12); `geo-link/architecture` · `spec` (the
       `ImportFollowOn` registration `LinkIndexFollowOn` was missing — 2); `la-store-pg/spec` · `architecture` (the
       connections are a view over the process's one shared HikariCP pool, capped per family by `db.pool.size` —
       2); `la-storage/spec` · `architecture` (it also depends on `inspecto-sql` and `inspecto-analytics` — 2);
       `la-api/architecture` (32 route modules, not 25; four features; `GeoRoutes` and `EntityPageRoutes` are
       gated by `geoMap` / `entityPages`, not explorer-open — 3); `la-core/spec` (`TemporalCut` was missing).
     - **`layer/*` pages.** `features/architecture` (no rows for `predictive` and `realtime`; `realtime` also needs
       `ops` — 2); `la/architecture` (104 routes and 32 route modules, not 87 / 25; the Geo map and Entity pages are
       Standard-and-up too — 3); `platform/user-guide` (the Space switcher follows `multiSpace` hosting, not "more
       than one Space"); `spi/architecture` (the predictive validators and `PredictionRoutes`, `IncidentBriefRoutes`,
       32 la-api route classes, and la-core's `InvestigationStoreProvider` as a third fail-closed slot — 5).
       `providers/architecture`: right.
     - **Solution packs (23 pages).** `industry-mobile-money/architecture` (26 terms not 16, 13 Entity Types not 3,
       the optional `industry-pack-record-types.toon`, the wider fail-closed rule — 4) and its user guide (1);
       `industry-telecom/architecture` (33 terms not 20, 14 Entity Types not 6, record types, fail-closed — 4);
       `la-mobile-money/architecture` (an `la.index.build` Job CAN start on another Job's output: `sql.template`
       emits `job.dataset.produced`, which `la-audit` and `la-claims` use; the cron is the template's choice).
       Right as written: `aml`, `business-assurance`, `la-audit` (2), `la-claims` (2), `la-daily-feed` (2),
       `la-showcase`, `la-showcase-off`, `mobile-money`, `orders-starter` (2), `payment-fraud`, `telco-fraud`,
       `telco-ra` (2) — every Job, schedule, Alert Rule, severity, threshold, Dataset and file name checked
       against `spaces/_templates/`.
     - **Right as written (module hubs):** `case-management/user-guide`, `intelligence/user-guide`,
       `graph/user-guide`, `sql/spec`, `workflow/spec` and both user guides, both `scoring` user guides,
       `addon/incidents/user-guide-incidents`, `action-requests`, `analytics`, `anomaly`, `config/user-guide-spaces`,
       `entity-store/user-guide-storage`, `exchange`, `observability`, `regulatory-reporting`,
       `backup/architecture`, `notify-channels/spec`, `geo-country/architecture`, `asn-decoders/spec` ·
       `architecture`, `telecom-asn1/spec` · `user-guide`.
   - **Item 5 / (d): every `file:line` citation left in `docs/okf/product` is gone** — about 320 line numbers in
     41 docs: 146 `File.ext:N`, about 55 `Class:N` / `ci.yml:N` / `pom.xml:N` forms and 120 orphan `` `:N` ``
     shorthands that batch 3 had left after a named symbol (`plan-spaces`, `plan-metamodel`, `plan-control-api`
     among them). Each cited file was checked to exist first; one does not (`pipeline-load-definition.component.ts`,
     the Load pane deleted 2026-09-05 — now said so). Where the file alone said too little, the symbol replaced the
     number (`ControlApi.bindAddress`, `ControlApi.correlation` … in the middleware table, `CaseOperations.mergeCases`,
     `BuiltinNodeType.TRANSFORM_JOIN`). Kept on purpose: ports (`:8080` …), quoted guard output, and the
     `NoSuch.java:1` example in `tests-operator-mutation-checklist`. Three claims found stale on the way were fixed:
     `plan-incidents` (merge and split live in `CaseOperations`, not `ObjectService`; `features.ops` is the
     registered feature, not `hasRoute("POST", "/objects")`) and `spec-catalog-vs-executors` (six call sites reach
     `MeasureCompiler.splitShorthand`, not three).
   - **Found in code, not docs:** the `la-mobile-money` template's `la_index_build_job.toon` description still says
     the Job type "only fires on a clock or a pipeline commit, never on a Job's output".
   - **Left:** nothing of the row's scope. As with every batch, the depth is one pass per doc against code; prose
     opinions and measured numbers (the la-core performance section) were not re-measured.

### Verified correct — do not "fix"

`OperationalDb.Family` "sixteen" (12 core + 4 from `inspecto-ops`); the guarded counts 21 modules / 28 jars /
89 locked dependencies; the `v3.11.0` merged tag.
