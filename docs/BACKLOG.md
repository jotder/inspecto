# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-25 (compaction pass — shipped strikethrough narrative removed in favour of OKF
pointers; all decision-gated items consolidated into §2) · **Owner:** whole team (update at every
handoff that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work: one line + status + where the
> detail lives. Detail stays in the linked source doc. **Shipped work is not recorded here** — it
> lives in git history, `REQUIREMENTS.md` status columns, the OKF concept docs under
> [`okf/`](okf/), and the archived plans under
> [`archived-documents/plans-archive/`](archived-documents/plans-archive/).
>
> **Where we are (2026-07-25):** the REQUIREMENTS MUST + SHOULD engineering backlogs are empty, the
> RBAC/ABAC plan is complete, and the fast/low-risk/independent seam has been drained across four
> shifts. What remains is: a handful of **product decisions** (§2 — cheapest unblock on the board),
> one large **schedulable feature** (AGT-6a), externally-gated soaks, and deliberate won't-do/
> trigger-gated tech debt (§6).

---

## 1. Act first / in-flight repo state

_(no in-flight repo state — working tree clean as of 2026-07-25)_

**Dependency-ordered priority.** Order of attack across the open rows below, by dependency fan-out;
each row's detail stays in its own section.

1. **Root enablers — DRAINED.** RBAC/ABAC R0–R5 + A1–A5, job-concurrency bound, Incidents I1
   resolution gate, `ObjectStore.delete`, and off-request-thread legacy triggers all shipped
   2026-07-23/24. The one survivor is **MNT-14**, now blocked on a backend Incident `Archived`
   lifecycle state + a retention product call (§2 D5).
2. **Cheap decision gates** — see **§2**. Near-zero build, largest unblock-per-hour on the board.
3. **Dependent chains (sequence behind a decision):** Lens Access P3 · NFR-7 SOC 2 execution
   (after C1 sign-off) · MNT-14 (after the `Archived` state) · API-5 physical deletion (after the
   soak) · Postgres multi-user (write the `docs/superpower/` plan first, then store pooling).
4. **Independent — schedule by value, no ordering constraint:**
   - **AGT-6a inline AI authoring** — largest schedulable item; no new backend capability. *(Ready
     pending decision asks D1–D4 in its plan.)*
   - Link-analysis V2 (b)/(c)/(d) · notification delivery-status webhooks · M4 Fuse remainder ·
     eoiagent `DryRunProvider` · DuckDB `spatial` extension.
   - ⚠ **"Geo Phase 4 backend" is effectively closed** — the projection/aggregation endpoints
     shipped 2026-07-24 and the `ComponentStore` view-kind widening landed 2026-07-08. All that
     survives under that heading is the `spatial` extension decision (§3 Geo row) and the
     `spatial` QueryType (§3 Queries row). Retire the phrase rather than re-scoping it.
5. **Externally gated (parked — not schedulable by us):** OPS-5 live soak (deployment) · EOI-7b
   (infra) · E1 (demand) · **AGT-6b** agent graphs (demand + the `DryRunProvider` prerequisite) ·
   parser field tiers (UX session) · C6 (profiling evidence).

## 2. Open decisions — the cheapest unblock on the board

Every item here is blocked on a **call, not a build**. Batch them into one product session.

| # | Decision | Blocks | Detail |
|---|---|---|---|
| D1 | NFR-7 C1 **sequencing sign-off** (SOC 2 first?) | all NFR-7 execution | `superpower/compliance-certifications-plan.md` |
| D2 | **Secret-in-bundle policy** — may a `connection` carry secrets through a bundle? | the sole missing `BundleRoutes` kind | §3 Bundle/Exchange |
| D3 | **API-5 soak sign-off** once `inspecto_legacy_api_requests_total` reads zero for 30 days | legacy-route physical deletion | `docs/ops/legacy-api-sunset-runbook.md` |
| D4 | Split **`canCurateMenus`** out of `canAuthorWorkbench`? Today every `pipeline-developer`/`app-developer`/`developer`/`power` seed role gets menu curation free — conflating "may edit a pipeline" with "may change what the space's business users see". Needs a call on the split **and** on default grants. Touch list if taken: `Roles.java` constant + seed grant · `CapabilityManifest.java` `/nav/menus` entry (its test enforces manifest↔registration congruence both ways) · `NavRoutes.java` gate · a `LensService` signal · an `ACCESS_ACTION_NODES.settings` node | nothing (surfaced by O1, not a defect) | §3 Menu builder |
| D5 | **MNT-14 retention model** — retention tier vs. archive-is-terminal — plus a backend Incident `Archived` lifecycle state (today only OPEN→…→CLOSED) | MNT-14 archived-Incident sweep | §3 Job framework |
| D6 | **Findings sections config source** (C3) — reuse the C6 workflow/TOON pattern + `attribute-spec` renderer, or a new endpoint? Not UI-only | configurable Findings sections | `mail-model.ts:133` |
| D7 | Is **`tags`** a first-class `OperationalObject` concept? Nothing writes `attributes.tags` today, so a `GET /objects` filter would silently match nothing | `category`/`tags` query params | §3 Incidents/cases |
| D8 | **Delivery-status model** — which inbound provider statuses (bounce/complaint/delivery) do we track? | notification delivery-status webhooks | §3 Notifications |
| D9 | **Saved views in the Exchange?** Sharing a link-analysis view needs the Exchange seam widened backend-side (`OfferShareDialog`/`ExchangeService.offer` are hard-typed to `dataset`/`widget`) | link-analysis V2 (b) sharing | §3 Link analysis |
| D10 | **Component-attached note model** — re-key `ObjectNote` by component `type`+`id`, or generalize it? | link-analysis per-view comments | §3 Link analysis |
| D11 | **On-by-default DuckDB memory cap** — a computed cap accounting for both semaphores, or a conservative fixed per-instance cap + spill? Prerequisite (`-Djobs.maxConcurrentRuns`) shipped 2026-07-24 | the chunking default (D12) | §6 DuckDB capping |
| D12 | **On-by-default chunking value** for pathological single files (`processing.chunking.max_file_bytes = 0` today) | — | §6 DuckDB capping |
| D13 | **Parser required-vs-advanced field tiers** — genuine UX judgment; needs someone who has watched real onboarding sessions, NOT an engineering guess (a placeholder bakes in an arbitrary answer that's expensive to unwind once forms ship) | parser `AttributeSpec` tiers, §4 attribute tiers | interview #2 |
| D14 | Product review of the **R1 seed grant set** for the five previously-ungranted route capabilities (`canConfigureAccess`, `canAuthorAlertRules`, `canOfferDatasets`, `canRequestShares`, `canApproveShares`) — seeded to builder/ops/admin/super to break a bootstrap deadlock, never product-reviewed | — | §5 |
| D15 | Final **IdP/gateway vendor split** — Keycloak + WSO2 APIM vs. WSO2 IS (ops/evidence, not code) | — | §5 |
| D16 | Which **Space + schema** for domain-seeded link-analysis pattern packs | link-analysis V2 (c) | §3 Link analysis |
| D17 | **`record_split`** (blank-line/delimiter block records for `text_regex`) — genuinely unsupported; needs a DuckDB block-reading approach | `text_regex` block records | `FEATURE_INVENTORY.md` |

## 3. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| OPS-5 | Provenance conservation on live data (built, off by default) | **Live-feed soak only — no code left.** Offline de-risk + the imbalance→`NotificationRule` question both closed 2026-07-22/23. `docs/ops/provenance-conservation-verification.md` |
| NFR-7 | Compliance certifications | PARTIAL (not started) — gated on **D1**; `superpower/compliance-certifications-plan.md` |
| API-5 | Legacy route **physical deletion** (sunset mechanism + meter shipped W8) | Gated on **D3** (30-days-at-zero soak) |
| EOI-7b | Publish eoiagent `0.1.0` artifacts to a registry | Infra/product call; CI rebuilds from tag meanwhile |
| AGT-6a | **AI behind every screen** — inline NL authoring on every console pane | **SCOPED, promoted Could→`Should`** — `superpower/agt-6-plan.md` §3 (A1 shared inline surface → A2 four-pane adoption → A3 context grounding → A4 explain-everywhere). Reuses the shipped L1 draft tools ⇒ **no new backend capability**; draft-only, human applies through the normal validated route. Ready to schedule pending its own D1–D4 |
| AGT-6b | **Multi-step agent graphs** — model-composed plans (provision → watch → roll back) | **PLANNED, demand-gated** — `superpower/agt-6-plan.md` §4. Blockers: the eoiagent approval gate is synchronous per-call (nested gates deadlock) + no per-tool `DryRunProvider` seam (a **prerequisite** — a composed plan can't be previewed per step without it). First cut = generalize `RunbookActions`, never free-form ReAct over mutating tools |
| AGT-5 · DryRunProvider | Cross-repo (`jotder/inspect-agent`): per-tool `DryRunProvider`/preview seam on `PlatformBuilder`, letting inspecto drop its parallel `AgentApprovals` previewer | **OPEN, low priority** — functional parity today, but reclassified 2026-07-25 as an **AGT-6b prerequisite** (`agt-6-plan.md` §4.2 G2), not merely a refactor. Push-first discipline applies |
| AGT-5 · embedding recall | Replace `CaseSimilarity` Jaccard with embedding/vector recall | **PARKED — not warranted.** `CaseStore` is a 256-cap ring; Jaccard is adequate. Drop-in seam preserved behind `CaseSimilarity.score` |
| AGT-5 cuts | QA-only (`incident_explain` waits on the eoiagent host seam); local-models-only | Open scope cuts |
| E1 | Enterprise distributed tier / Stage-2 streaming | Demand-gated |

## 4. Feature follow-ons (deferral sections of shipped work)

As-built detail for each area lives in its OKF concept (right column) — **not here**.

| Area | Open items | OKF home |
|---|---|---|
| **API v1** | X-Actor **full removal** (already rejected outright on Standard/Enterprise; removal is client-migration-gated with the API-v1 sunset) · UI sign-out affordance (absorbed by the §5 gateway topology) · adopt the cursor-pagination seam on further list families **as demanded** (4 adopters live) · adopt the `ETags.respond` wrapper on further singleton reads **as demanded** (list/paginated routes deliberately excluded) · Standard-edition jlink runtime vs Nimbus not re-verified (`-NoRuntime` until confirmed) | `okf/backend/api/api-v1.md` |
| **Bundle / Exchange** | missing kind `connection` (gated on **D2**) · `requires` present-but-different classification · per-editor "load as draft" import — **not a small buildable**: `BundleTransferService.write` commits straight through each store, there is no generic draft seam, and editors open by route/id (not injected content); design-first, likely multi-session, **do not fake it with a cross-kind `enabled:false` stamp** | `okf/backend/control-plane/exchange-sharing.md` |
| **Job framework** | MNT-14 archived-Incident sweep (**blocked on D5**) · maintenance COULD tier: space-to-space comparison · predictive maintenance (AGT-5/self-healing territory, deliberately deferred) | `okf/backend/control-plane/jobs.md` |
| **Queries / BI** | `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers | `okf/backend/control-plane/queries.md` |
| **Notifications** | delivery-status webhooks — **inbound** provider bounce/complaint/delivery callbacks (the outbound `webhook` channel shipped 2026-07-22); gated on **D8**, Standard/Enterprise flavor territory · GeoIP · auth-gated per-user prefs / security triggers | `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | optional S8 (connector-direct emission + cross-space controller) · a general **event-triggered consequence policy gate** (still `/apply`-only) · RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet) · ⚠ **no producer threads `causationId`**, so `/signals/tree` is flat today | `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md` |
| **Link analysis** | V1 + four V2 tracks + timeline + version history all shipped. **Open V2:** (b) **sharing** a saved view — needs the Exchange seam widened backend-side, **D9** *(⚠ the old "sharing is frontend-only" claim was wrong)* · (b) **per-view comments** — needs **D10** · (c) domain-seeded pattern packs — needs **D16** · (d) AI assist (routes through the Assist seam) | `okf/frontend/features/link-analysis.md` |
| **Geo map** | DuckDB **`spatial` extension** — deliberately deferred: plain SQL covers today's projection/aggregation, and loading it means bypassing the hardened `SqlSandbox` extension lockdown **and** bundling a per-platform native binary for offline installs. Only worth it once a real geometry op (ST_Distance/ST_Contains, spatial join) is demanded. *(Progressive loading + worker binning CLOSED 2026-07-24 as obsoleted by the server-side fold + the hard `GEO_POINT_CAP = 5000`. Revisit ONLY if that cap is raised — the candidate then is worker-izing the O(n²) toolbox analyses, not binning.)* | `okf/frontend/features/geo-map.md` |
| **Pipeline graph** | T15 residuals (non-blocking): per-flow TOON override of the back-pressure thresholds (globals only) · flipping the intake cap on by default (needs a soak) · remote-fetch economy (the cap applies post-dedup, so a remote source still materialises its full ready set — unchanged from pre-T15, but a pre-materialise cap would save bandwidth) · mock-only: run-to-here `POST …/run` (path deliberately reserved for the editor's scratch-only contract) · `/asn1/modules` **stays mock-only** — no backend ASN.1 capability exists | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 |
| **Acquisition / connections** | the JDBC-based connectors each need their own library-specific proxy wiring · an actual **HTTP CONNECT** handshake for any connector (SOCKS5 is wired for SFTP/FTP/FTPS; HTTP fails closed) | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | C3 configurable Findings sections (**D6**) · `category`/`tags` params on `GET /objects` (**D7**, low value). *(Case-analytics dataset SHIPPED 2026-07-25 as the `objects.analytics` Job Type — plan archived, as-built in `okf/backend/control-plane/jobs.md`.)* | `okf/backend/control-plane/operations-reference.md` |
| **Menu builder** | the `canCurateMenus` capability split (**D4**) — a product question O1 surfaced, not a defect | `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | Reference Phase-2 is **COMPLETE** (P0–P4, plan archived). Residual non-blocking deferrals: **D5-ref** — how a `delete` tombstone *enters* the reference store is undefined (the write path always stamps `'upsert'`; the views only *honour* an existing tombstone) — needs a call on the input signal (reserved column? Decision Rule consequence?) when a real delete-feed use case lands · **D6-ref** — within-batch same-key tie-break is arbitrary; add the optional latest-by-`order_by` column only when a batch can legitimately carry ordered same-key versions · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |
| **Quarantine / D-ETL** | reprocess is **whole-batch only** — no record-level replay (tracked only if prioritized) | `okf/frontend/features/run-detail.md` |

## 5. UI residuals + security-module residuals

**UI residuals (small, valuable):**
- `ComponentKind.deriveParts` seam — formalize when a 3rd composite kind needs it.
- Parser/node attribute tiers are a best guess pending firm backend specs — same call as **D13**.

**Security module.** The RBAC/ABAC plan is **COMPLETE** (R0–R5 + A1–A5); the plan was archived and the
durable as-builts now live in **`okf/backend/editions/auth-security.md`** — read them there, not the
archived plan. Direction of record: external OIDC IdP (Keycloak) + WSO2 APIM gateway; RBAC = Standard,
ABAC = Enterprise; standards-only. **Residual opens, all non-blocking:**
- **Policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility + a
  "why denied?" explain endpoint + a read-only Policies tab all shipped 2026-07-24).
- Product review of the R1 seed grant set — **D14**.
- Final IdP/gateway vendor split — **D15**.
- X-Actor **full removal** — client-migration-gated (see §4 API v1).

> **Do not partially implement security concerns elsewhere** — this section stays the single scope.

## 6. Engineering / tech-debt

The engineering MoSCoW (build hygiene, `CollectorService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) is **COMPLETE and
archived**; the 16-module reactor as-built + the extraction playbook live in
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

**Open:**
- **`fp-query`/`fp-job`/`fp-enrich` module extraction** — **build only on explicit request.** Nobody
  has asked; it is a preference, not a need. Main-code layering is already clean and acyclic, but it
  is **not a single clean increment**: `query`/`job` also depend on `signal` + `ops` (outside the
  group, test-side up-imports unscanned), and a known test cut is required (`job`'s
  `SharedDottedPathGrammarTest` imports `com.gamma.notify.NotificationTemplate`; playbook rule 5).
- **M4 Fuse remainder** — the dead `src/@gamma/lib/mock-api/` (8 files, `GammaMockApiService`/
  `mockApiInterceptor`, never wired; the live mock is the app-owned `app/inspecto/mock/`) is
  functionally orphaned but **not a clean delete**: `@gamma/gamma.provider.ts` still imports
  `GAMMA_MOCK_API_DEFAULT_DELAY`/`mockApiInterceptor` and has the `mockApi` provider branch. Removing
  it means editing vendored `@gamma` shell code, which the angular-ui skill declares out of scope.
  **Defer unless the team explicitly accepts touching `gamma.provider.ts`.**
- **DuckDB capping remainder** — all scratch connections are now cappable by one operator knob
  (`-Dprocessing.duckdb.memory_limit`/`.temp_directory`/`.max_temp_directory_size`/`.threads`) and
  job concurrency is boundable (`-Djobs.maxConcurrentRuns`, default `0`=unbounded). Still open, both
  policy calls: an **on-by-default memory value** (**D11**) and an **on-by-default chunking value**
  (**D12**). Unset `memory_limit` ⇒ DuckDB defaults to ≈80% RAM *per instance* ⇒ concurrent runs
  overcommit ⇒ the whole box (incl. the HTTP API) can go unresponsive. Read-path is **not** the risk
  (see C6 below). `okf/backend/engine/duckdb.md`
- **Postgres multi-user transactional backend** — DIRECTION captured, deferred by operator. **Write a
  `docs/superpower/` plan before building.** Most of it exists: the stores are interface-seamed with a
  `-D*.backend` toggle in `ServiceStores`, JDBC is dialect-aware, alerts/incidents/cases are already
  `ObjectStore` rows, and `PostgresStateStoreTest` round-trips all 7 JDBC stores against embedded
  Postgres. **The real multi-user gap is connection pooling** — every `Db*Store` holds ONE
  `synchronized` connection. Build items: pool the stores (HikariCP/PgBouncer) · **schema**-per-space
  URL wiring (NOT db-per-space — a PG conn binds to one DB, fragmenting pools) · a `CaseStore`
  interface + PG impl (JSONL ring today, no seam) · keep events on Parquet (right fit). **Don't route
  all reads through the postgres-duckdb plugin** (wire-protocol scans compete with OLTP) — read PG
  directly for OLTP, reserve the plugin (or a materialize-to-Parquet CQRS split) for cross-engine
  analytical joins. Editions: DuckDB-file stays the Personal default (zero external deps / jlink),
  Postgres for Standard/Enterprise via the existing toggle. `okf/backend/engine/db-layer.md`

**Closed — do not re-propose without the stated trigger:**
- **M2 `CollectorService` decomposition — won't-do.** Already reads as a composition-root/facade
  (wires ~15 extracted collaborators, 6 focused test files). Maintainability-only, not a split
  blocker. No god-class emergency.
- **C2 store-pair generic base — won't-do.** A `DbBackedStore` base could absorb only ~30–40 lines
  across 5 classes; the duplication that mattered is already de-duped by `JdbcDrivers`/`JdbcRows`/
  `BrowsableStore`. **Reopen only if the `Db*Store` family grows materially** (a 7th+ store, or the
  shared shape starts drifting).
- **C4 BOM — moot.** The precondition (artifacts consumed outside this reactor) doesn't exist — we
  ship a fat JAR, not a library, and M1 gave shared version hygiene via parent
  `dependencyManagement`. Reopen only if an external consumer appears.
- **C6 DuckDB cross-run connection reuse — trigger-gated on profiling evidence that doesn't exist.**
  Code already opens ONE connection per run against a fresh unique temp scratch DB. Cross-*run* reuse
  is a genuinely risky re-architecture (per-run scratch DBs are deliberately ephemeral/isolated, JDBC
  connections aren't thread-safe, jobs run concurrently). Read-only sharing was investigated
  2026-07-22 and does **not** apply (ephemeral instances over immutable Parquet globs, each connection
  issues scratch DDL so `READ_ONLY` can't be used as-is). If read-path open cost ever shows up, the
  cheap fix is switching `SqlSandbox` from a temp *file* to `:memory:` — not connection sharing.
- The intra-module `ops↔ops.link/workflow` and `catalog↔catalog.spi` cycles are same-family, **not**
  reactor-split blockers.

## 7. Docs & ongoing

| Item | Status |
|---|---|
| `record_split` for `text_regex` block records | Open — **D17** |
| Parser required-vs-advanced field tiers | Open — **D13** |
| Template seed-pack enrichment (frontend C7) | Ongoing, continuous — not a discrete item |
| Reconciliation explicit **non-goals** (N>3, non-additive aggs, fuzzy keys) | Recorded, not open work — `okf/frontend/features/reconciliation.md` |

## 8. Duplicate map (same work, multiple IDs — update all sources when closing)

| Canonical | Also recorded as |
|---|---|
| API-5 legacy sunset | w7-ui-v1-migration deferred follow-ons · X-Actor full removal (§4, §5) |
| EOI-7b eoiagent publish | agent-kernel-replacement §open-items |
| eoiagent `DryRunProvider` | AGT-5 follow-on (§3) · AGT-6b prerequisite (§3) |
| MNT-14 archived-Incident sweep | blocked-on = D5 (retention model + `Archived` state) |
| Parser field tiers | D13 · §5 UI attribute tiers · interview #2 |
| ~~Geo-map Phase 4 backend~~ | **RETIRED 2026-07-25** — the endpoints + `ComponentStore` widening shipped; only the `spatial` extension + `spatial` QueryType survive, tracked in their own §4 rows |
| ~~INV-1 Entity Projection backend~~ | **RETIRED** — shipped |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then **delete the row here** — do not leave a strikethrough as-built narrative behind
(that is what the OKF concept docs and git history are for; this page had grown to ~40k tokens that
way before the 2026-07-25 compaction). New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
