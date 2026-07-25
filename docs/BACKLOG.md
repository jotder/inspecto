# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-25 (**decision session — all seventeen §2 calls answered**; each call's rationale
now lives in its OKF/plan concept home, and the items they gated moved from "blocked on a call" to
"schedulable build". Earlier same day: compaction pass, shipped strikethrough narrative removed in favour
of OKF pointers) · **Owner:** whole team (update at every handoff that closes/opens an item)

> **What this is.** The single index of ALL pending/deferred/open work: one line + status + where the
> detail lives. Detail stays in the linked source doc. **Shipped work is not recorded here** — it
> lives in git history, `REQUIREMENTS.md` status columns, the OKF concept docs under
> [`okf/`](okf/), and the archived plans under
> [`archived-documents/plans-archive/`](archived-documents/plans-archive/).
>
> **Where we are (2026-07-25, after the decision session):** the REQUIREMENTS MUST + SHOULD engineering
> backlogs are empty, the RBAC/ABAC plan is complete, the fast/low-risk/independent seam has been drained
> across four shifts, and **§2 is now empty — every product decision on the board has been made.** The
> board has therefore flipped character: it is no longer "blocked on calls", it is **a queue of
> schedulable builds** (§3/§4/§6) plus externally-gated soaks and deliberate won't-do/trigger-gated tech
> debt. The two largest items are **AGT-6a** (inline AI authoring) and the newly-rescoped **generic tag
> system** (D7). Nothing on this page is waiting on a decision anymore.

---

## 1. Act first / in-flight repo state

_(no in-flight repo state — working tree clean as of 2026-07-25)_

**Dependency-ordered priority.** Order of attack across the open rows below, by dependency fan-out;
each row's detail stays in its own section.

1. **Root enablers — DRAINED.** RBAC/ABAC R0–R5 + A1–A5, job-concurrency bound, Incidents I1
   resolution gate, `ObjectStore.delete`, and off-request-thread legacy triggers all shipped
   2026-07-23/24. The one survivor is **MNT-14**; its retention question is now answered (§2 D5 —
   retention tier), so the only thing left in its way is the backend Incident `Archived` state — a
   build, not a call.
2. **Decision gates — DRAINED 2026-07-25.** §2 is empty; see it for what each call was and where the
   rationale lives. Several rows below changed shape as a result, and three had **wrong premises**
   corrected (D3 legacy-route framing, D7 tags-are-greenfield, D14 already-tightened) — trust §2 over
   any older phrasing you remember.
3. **Small, concrete, newly unblocked (do these first — each is hours, not sessions):** `canOfferDatasets`
   → admin/super (D14) · the `canCurateMenus` split (D4) · the DuckDB memory-cap default then the
   chunking default (D11 → D12, in that order) · rename `KeycloakTokenRelay` → vendor-neutral (D15).
4. **Dependent chains (sequence behind a build, no longer behind a decision):** Lens Access P3 · NFR-7
   execution (now parallel — C1 is not a predecessor, D1) · MNT-14 (after the `Archived` state) ·
   Postgres multi-user (write the `docs/superpower/` plan first, then store pooling).
5. **Independent — schedule by value, no ordering constraint:**
   - **AGT-6a inline AI authoring** — largest schedulable item; no new backend capability. *(Ready
     pending decision asks D1–D4 **in its own plan** — a separate numbering space from §2's D1–D17,
     which are all answered. Do not confuse the two.)*
   - **Generic tag system (D7)** — newly rescoped, plan-first, second-largest item on the board.
   - Link-analysis V2 (b)/(c)/(d) — all three of its blockers were §2 calls and are now answered
     (D9/D10/D16) · notification delivery-status webhooks (D8) · the `connection` bundle kind (D2) ·
     configurable Findings sections (D6) · M4 Fuse remainder · eoiagent `DryRunProvider` · DuckDB
     `spatial` extension.
   - ⚠ **"Geo Phase 4 backend" is effectively closed** — the projection/aggregation endpoints
     shipped 2026-07-24 and the `ComponentStore` view-kind widening landed 2026-07-08. All that
     survives under that heading is the `spatial` extension decision (§3 Geo row) and the
     `spatial` QueryType (§3 Queries row). Retire the phrase rather than re-scoping it.
6. **Externally gated (parked — not schedulable by us):** OPS-5 live soak (deployment) · EOI-7b
   (infra) · E1 (demand) · **AGT-6b** agent graphs (demand + the `DryRunProvider` prerequisite) ·
   parser field tiers (UX session, D13 confirmed) · C6 (profiling evidence).

## 2. Open decisions — ALL ANSWERED 2026-07-25

**The §2 decision board is empty.** All seventeen calls were made in one product session on 2026-07-25.
Each call's rationale + the build shape it implies now lives in its **concept home** (right column) — that
doc is authoritative, not this table. What remains is **build work**, tracked in §3/§4/§6.

Three rows turned out to rest on **wrong premises**, corrected during the session and flagged ⚠ below — if
you remember the old framing, re-read those three.

| # | Call | Home |
|---|---|---|
| D1 | NFR-7 runs **in parallel — SOC 2 is not a gate**; C1 is no longer a blocking predecessor. The Type II observation window still needs CC6 controls live (they are), and external-party steps stay paced by the third party | `superpower/compliance-certifications-plan.md` §6 Q1 |
| D2 | A bundle may carry a `connection` **reference-only, secrets stripped** — `${ENV:…}` travels, no secret value in any form (not even bundle-encrypted: bundles land in git, CI, and support tickets). Unblocks the last missing `BundleRoutes` kind | `okf/…/metadata-bundle.md` |
| D3 | **Delete the legacy surface; soak criterion consciously overridden** — justified: there is **no live deployment** (the runbook's own owner is "whoever operates the *first* one") and the Angular UI is **fully v1-migrated** (`apiUrl()` → `/api/v1`; `space.interceptor` preserves the version segment), so no caller would break. **Build still pending** — ⚠ two premise errors in the old row, both material: there were never separate legacy route *classes*, **and** deleting the sunset *mechanism* retires nothing (the bare routes are registered independently and keep serving). See the §3 API-5 row for the correct shape and its trap | §3 API-5 |
| D4 | **Split `canCurateMenus`** out of `canAuthorWorkbench` — a nav change is visible to every business user in the space and is not a build activity. Default grant: admin/super + `power`. Touch list unchanged; the manifest entry and the route gate must land **together** (the congruence test runs both directions) | `okf/…/auth-security.md` |
| D5 | **Retention tier, not archive-is-terminal** — `Archived` becomes a real state but carries a retention window, and expiry is what makes an Incident purge-eligible. Needs a dry-run-first sweep + a legal-hold exemption. `ObjectStore.delete` already shipped, so the one remaining blocker is the `Archived` state itself | `okf/…/jobs.md` |
| D6 | **Reuse the C6 workflow/TOON pattern + the `attribute-spec` renderer**; no new endpoint — it would be a third config idiom for a problem the shipped `GET /workflows/{type}` precedent (same pane) already solves. **Not UI-only**: sections must be server-authored TOON | `okf/frontend/features/objects.md` |
| D7 | **Rescoped by the operator** from "a `tags` filter on `GET /objects`" to a **generic Gmail-label-style grouping concept** spanning streams/rules/alerts/datasets, on a central registry + `(tag, entity_kind, entity_id)` assignments. ⚠ **Not greenfield, and the old row was wrong twice** — object-scoped tags already ship (`Tag` registry + `TagRule` + `/tags` + `/tags/rules` + a Tags folder in the mail nav), and `attributes.tags` **is** written (`ObjectService.ATTR_TAGS`, five call sites), so the dismissed narrow filter would have worked. This is that system *generalized*: CSV-inside-the-entity → central registry + assignment store, and beyond `OperationalObject` | `superpower/generic-tags-plan.md` |
| D8 | Track **all three** of `delivered`/`bounce`/`complaint` — complaint is the only class with a *deliverability* consequence, so it can't be the one dropped. Needs per-status timestamps (not one mutable enum — `delivered` then `complaint` is the normal spam-button case), adapter-edge normalization of provider vocabularies, a hard/soft bounce split, and a signature-verified fail-closed callback | `okf/…/events-metrics.md` |
| D9 | **Yes — widen the Exchange `kind` axis** to carry saved views rather than build parallel sharing. A view grant must **require its datasets' grants** (generalize the widget→dataset cascade, don't special-case it twice) and is **live-mode only** (a view has no rows of its own, so `snapshot` must be rejected, not silently treated as live) | `okf/…/exchange-sharing.md` |
| D10 | **Generalize the note model** to any `(kind, id)` target — do not re-key `ObjectNote` by component `type`+`id`. The narrow re-key buys the same feature and guarantees a third caller becomes a third special case | `okf/frontend/features/link-analysis.md` |
| D11 | **Conservative fixed per-instance cap + spill, on by default.** A semaphore-computed cap was rejected: `jobs.maxConcurrentRuns` defaults to `0`/unbounded so the divisor is usually unknown, and batch-ingest has a second independent limiter — any formula is wrong in exactly the overcommit case it was meant to prevent | `okf/…/duckdb.md` |
| D12 | **Chunking on with a large threshold** — the cap exists for *pathological* single files, so the default must be high enough that normal workloads never change shape. Lands after D11 (a memory cap makes the failure mode "spill", which makes the threshold easier to pick) | `okf/…/duckdb.md` |
| D13 | **Confirmed parked** — stays gated on a real onboarding-observation session (interview #2). An engineering placeholder would bake in an arbitrary answer that is expensive to unwind once forms ship | §7 · interview #2 |
| D14 | **Ratified with one tightening.** ⚠ `canConfigureAccess` + `canApproveShares` were **already** admin/super-only in `Roles.SEED`, so the "bootstrap deadlock left them over-granted" premise was unfounded — nothing to fix there. `canAuthorAlertRules`/`canRequestShares` ratified as developer/ops-tier (a *request* still needs an owner's approval). **`canOfferDatasets` → admin/super**: cross-space data exposure with no second gate behind it | `okf/…/auth-security.md` |
| D15 | **Withdrawn, not answered — there is no vendor of record.** The IdP/gateway is a per-client deployment choice; we stay standards-only and configurable. Two vendor-shaped residuals are now defects against this decision: `KeycloakTokenRelay`'s name + its Keycloak-shaped `tokenEndpoint` default, and the WSO2 `X-JWT-Assertion` header default (keep the default, document it as *a* convention). Litmus test: new auth code that can't be pointed at a different compliant IdP by config alone is wrong | `okf/…/auth-security.md` |
| D16 | **A dedicated system Space** owns the domain-seeded pattern packs — the space-template-gallery path would fork packs per Space, so a fix to a shipped pattern could never reach the copies | `okf/frontend/features/link-analysis.md` |
| D17 | **Open, unscheduled** — acknowledged gap, no demand pressure, no build time committed | §7 |

## 3. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| OPS-5 | Provenance conservation on live data (built, off by default) | **Live-feed soak only — no code left.** Offline de-risk + the imbalance→`NotificationRule` question both closed 2026-07-22/23. `docs/ops/provenance-conservation-verification.md` |
| NFR-7 | Compliance certifications | PARTIAL (not started) — **UNGATED 2026-07-25 (D1): runs in parallel, C1 is not a predecessor.** Real remaining constraints are the Type II observation window (needs CC6 live — it is) and external-party pacing. `superpower/compliance-certifications-plan.md` |
| API-5 | Legacy route **physical deletion** | **DECIDED, NOT YET DONE (D3, 2026-07-25).** Soak criterion consciously overridden — justified: no live deployment exists and the UI is fully v1-migrated (`apiUrl()` → `/api/v1`), so no caller would break. ⚠ **But the obvious change is a trap, discovered 2026-07-25:** deleting the sunset *mechanism* (flag, `recordLegacyUsage`, `Deprecation`/`Sunset` headers) does **not** retire anything — the bare unversioned routes are registered independently in `RunRoutes`/`ConfigRoutes`/etc. and keep serving. It would remove the only kill switch and the only usage signal while leaving the surface live and unobservable. **Real retirement means making business routes require `/api/v1`** (plus a `serveStatic` guard: after the `/api/`-strip branch goes, a bare `/api/<anything>` falls through to the SPA deep-link fallback and returns **200 text/html**, not 404, whenever `-Dui.dir` is set). **Blast radius — measured 2026-07-25, and it is the reason this is a dedicated piece of work, not a quick cleanup: 62 of the 83 HTTP-exercising test files under `inspecto/src/test/java/com/gamma/control/` never use `/api/v1`** and call bare business paths throughout; all of them fail the moment business routes require the version prefix. The test suite is by far the largest consumer of the unversioned surface. Because the routing change and the test migration must land together (anything less is a red build), the atomic commit is **~63 files**. Named breakages beyond the mechanical sweep: `ControlApiLegacySunsetTest` (whole file retires), `ControlApiV1Test.legacyUsageIsCountedForVersionedRoutesButNotV1OrInfra`, `ControlApiStaticAndCorsTest.apiPrefixIsStrippedToMatchRoutes` (+ its bare `/runs`, `/runs/nope/commits` cases). `isInfraRoute` **stays** (it is the `/health`+`/metrics` exemption — those remain unversioned by decision); `ApiContext.v1` stays used (7 other sites). Decide the unversioned-business-path answer explicitly: a **JSON 404 with a migration hint** is right, *not* a fall-through to `serveStatic`, which would hand a stale API caller a 200 HTML shell. Docs to reconcile in the same change: `okf/…/api-v1.md`, `okf/…/events-metrics.md`, `REQUIREMENTS.md` API-5 row, `ADVANCED_GUIDE.md`, `stakeholders/OPERATIONS_GUIDE.md`, `superpower/deployment-topology-plan.md`, and the runbook (archive it) |
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
| **Bundle / Exchange** | missing kind `connection` — **UNBLOCKED (D2: reference-only, secrets stripped)**, now a build · **widen the Exchange `kind` axis to carry saved views (D9)** — live-mode only, a view grant requires its datasets' grants · `requires` present-but-different classification · per-editor "load as draft" import — **not a small buildable**: `BundleTransferService.write` commits straight through each store, there is no generic draft seam, and editors open by route/id (not injected content); design-first, likely multi-session, **do not fake it with a cross-kind `enabled:false` stamp** | `okf/backend/control-plane/exchange-sharing.md` |
| **Job framework** | MNT-14 archived-Incident sweep — **retention model decided (D5: retention tier + a real `Archived` state, dry-run-first sweep, legal-hold exemption)**; the remaining blocker is building the `Archived` lifecycle state, not a call · maintenance COULD tier: space-to-space comparison · predictive maintenance (AGT-5/self-healing territory, deliberately deferred) | `okf/backend/control-plane/jobs.md` |
| **Queries / BI** | `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers | `okf/backend/control-plane/queries.md` |
| **Notifications** | delivery-status webhooks — **inbound** provider bounce/complaint/delivery callbacks (the outbound `webhook` channel shipped 2026-07-22); **status model decided (D8: track `delivered`+`bounce`+`complaint`, per-status timestamps, adapter-edge normalization, hard/soft bounce split, signature-verified fail-closed callback)** — now a build; Standard/Enterprise flavor territory · GeoIP · auth-gated per-user prefs / security triggers | `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | optional S8 (connector-direct emission + cross-space controller) · a general **event-triggered consequence policy gate** (still `/apply`-only) · RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet) · ⚠ **no producer threads `causationId`**, so `/signals/tree` is flat today | `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md` |
| **Link analysis** | V1 + four V2 tracks + timeline + version history all shipped. **All three V2 blockers were §2 calls and are now answered — every open item here is a build:** (b) **sharing** a saved view → widen the Exchange `kind` axis (**D9**; ⚠ the old "sharing is frontend-only" claim was wrong) · (b) **per-view comments** → generalize the note model to any `(kind, id)` target, don't re-key `ObjectNote` (**D10**) · (c) domain-seeded pattern packs → a dedicated system Space owns them (**D16**) · (d) AI assist (routes through the Assist seam) | `okf/frontend/features/link-analysis.md` |
| **Geo map** | DuckDB **`spatial` extension** — deliberately deferred: plain SQL covers today's projection/aggregation, and loading it means bypassing the hardened `SqlSandbox` extension lockdown **and** bundling a per-platform native binary for offline installs. Only worth it once a real geometry op (ST_Distance/ST_Contains, spatial join) is demanded. *(Progressive loading + worker binning CLOSED 2026-07-24 as obsoleted by the server-side fold + the hard `GEO_POINT_CAP = 5000`. Revisit ONLY if that cap is raised — the candidate then is worker-izing the O(n²) toolbox analyses, not binning.)* | `okf/frontend/features/geo-map.md` |
| **Pipeline graph** | T15 residuals (non-blocking): per-flow TOON override of the back-pressure thresholds (globals only) · flipping the intake cap on by default (needs a soak) · remote-fetch economy (the cap applies post-dedup, so a remote source still materialises its full ready set — unchanged from pre-T15, but a pre-materialise cap would save bandwidth) · mock-only: run-to-here `POST …/run` (path deliberately reserved for the editor's scratch-only contract) · `/asn1/modules` **stays mock-only** — no backend ASN.1 capability exists | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 |
| **Acquisition / connections** | the JDBC-based connectors each need their own library-specific proxy wiring · an actual **HTTP CONNECT** handshake for any connector (SOCKS5 is wired for SFTP/FTP/FTPS; HTTP fails closed) | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | C3 configurable Findings sections — **decided (D6: reuse the C6 workflow/TOON pattern + `attribute-spec` renderer; server-authored TOON, not UI-only)**, now a build · ⚠ the old `category`/`tags` params row is **superseded** — D7 rescoped tags into a generic cross-entity concept with its own plan (`superpower/generic-tags-plan.md`); do not build an `attributes.tags` filter. *(Case-analytics dataset SHIPPED 2026-07-25 as the `objects.analytics` Job Type — plan archived, as-built in `okf/backend/control-plane/jobs.md`.)* | `okf/frontend/features/objects.md` · `okf/backend/build-run/operations-reference.md` |
| **Menu builder** | the `canCurateMenus` capability split — **DECIDED (D4: split it; default grant admin/super + `power`)**, now a small build. Land the `CapabilityManifest` entry and the `NavRoutes` gate **together** — the congruence test runs both directions | `okf/backend/editions/auth-security.md` · `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | Reference Phase-2 is **COMPLETE** (P0–P4, plan archived). Residual non-blocking deferrals: **D5-ref** — how a `delete` tombstone *enters* the reference store is undefined (the write path always stamps `'upsert'`; the views only *honour* an existing tombstone) — needs a call on the input signal (reserved column? Decision Rule consequence?) when a real delete-feed use case lands · **D6-ref** — within-batch same-key tie-break is arbitrary; add the optional latest-by-`order_by` column only when a batch can legitimately carry ordered same-key versions · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |
| **Quarantine / D-ETL** | reprocess is **whole-batch only** — no record-level replay (tracked only if prioritized) | `okf/frontend/features/run-detail.md` |

## 5. UI residuals + security-module residuals

**UI residuals (small, valuable):**
- `ComponentKind.deriveParts` seam — formalize when a 3rd composite kind needs it.
- Parser/node attribute tiers are a best guess pending firm backend specs — same call as **D13**, which was
  **confirmed parked** 2026-07-25 (needs a real onboarding-observation session, not an engineering guess).

**Security module.** The RBAC/ABAC plan is **COMPLETE** (R0–R5 + A1–A5); the plan was archived and the
durable as-builts now live in **`okf/backend/editions/auth-security.md`** — read them there, not the
archived plan. Direction of record (**revised 2026-07-25, D15**): external OIDC IdP + gateway, **no vendor
of record — the vendor is a per-client deployment choice**; RBAC = Standard, ABAC = Enterprise;
standards-only. *(Keycloak + WSO2 APIM are a supported example, not the answer.)* **Residual opens, all
non-blocking:**
- **Policy-authoring UX** — a matrix/create editor beyond hand-authored TOON (seed visibility + a
  "why denied?" explain endpoint + a read-only Policies tab all shipped 2026-07-24).
- **`canOfferDatasets` → admin/super** (D14) — small, concrete, ready. The rest of the R1 seed set is
  ratified; ⚠ `canConfigureAccess`/`canApproveShares` were **already** admin/super-only, so the
  "over-granted" premise behind the old row was unfounded.
- **Vendor-shaped residuals, now defects against D15** (both non-blocking): rename `KeycloakTokenRelay` →
  vendor-neutral and stop defaulting `auth.oidc.tokenEndpoint` to Keycloak's path shape; keep the
  `X-JWT-Assertion` gateway-header default but document it as *a* convention, not *the* gateway.
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
  **DECIDED 2026-07-25 — both are now builds, not calls**: a **conservative fixed per-instance cap +
  spill, on by default** (**D11** — a semaphore-computed cap was rejected because `maxConcurrentRuns`
  defaults to unbounded and batch-ingest has a second limiter), then **chunking on with a large
  threshold** (**D12**), in that order. Unset `memory_limit` ⇒ DuckDB defaults to ≈80% RAM *per instance* ⇒ concurrent runs
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
| `record_split` for `text_regex` block records | Open, **unscheduled by decision (D17, 2026-07-25)** — real gap, no demand pressure, no build time committed |
| Parser required-vs-advanced field tiers | **Parked by decision (D13, 2026-07-25)** — needs a real onboarding-observation session (interview #2); explicitly NOT an engineering guess |
| Template seed-pack enrichment (frontend C7) | Ongoing, continuous — not a discrete item |
| Reconciliation explicit **non-goals** (N>3, non-additive aggs, fuzzy keys) | Recorded, not open work — `okf/frontend/features/reconciliation.md` |

## 8. Duplicate map (same work, multiple IDs — update all sources when closing)

| Canonical | Also recorded as |
|---|---|
| API-5 legacy sunset | D3 answered (delete it, soak overridden) but **the build is NOT done** — see the §3 row for why the obvious change is a trap. Runbook still live · X-Actor full removal (§4, §5) is a separate client-migration-gated item |
| EOI-7b eoiagent publish | agent-kernel-replacement §open-items |
| eoiagent `DryRunProvider` | AGT-5 follow-on (§3) · AGT-6b prerequisite (§3) |
| MNT-14 archived-Incident sweep | D5 answered (retention tier); remaining blocker = build the `Archived` state |
| Parser field tiers | D13 (parked) · §5 UI attribute tiers · interview #2 |
| Generic tag system | D7 (rescoped) · `superpower/generic-tags-plan.md` · ⚠ supersedes the old §4 "`category`/`tags` params on `GET /objects`" row — the *existing* `Tag`/`TagRule`/`/tags` system is what gets generalized, not a separate feature. Keep aligned with **D10** (generalized notes): same `(kind, id)` addressing problem, so one adopts the other's scheme |
| Saved-view sharing | D9 (Exchange `kind` widening, §4 Bundle/Exchange) · link-analysis V2 (b) (§4 Link analysis) |
| ~~Geo-map Phase 4 backend~~ | **RETIRED 2026-07-25** — the endpoints + `ComponentStore` widening shipped; only the `spatial` extension + `spatial` QueryType survive, tracked in their own §4 rows |
| ~~INV-1 Entity Projection backend~~ | **RETIRED** — shipped |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then **delete the row here** — do not leave a strikethrough as-built narrative behind
(that is what the OKF concept docs and git history are for; this page had grown to ~40k tokens that
way before the 2026-07-25 compaction). New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
