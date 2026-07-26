# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-26 (**D7 tags backend complete** — phases 1+2 plus rename/delete; only the UI is
open, see §6. Previously 2026-07-25: **decision session — all seventeen §2 calls answered**; each call's rationale
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

0. 🔴 **SEC-INCIDENT-1 — rotate the leaked client secrets (§5).** Ahead of everything else and not an
   engineering task: five OAuth secrets, one of them a named-customer production credential, were public
   on GitHub for six weeks. The code is clean as of 2026-07-25 but **rotation at the issuer is
   outstanding** — history keeps the values, so deletion remediated nothing.
1. **Root enablers — DRAINED.** RBAC/ABAC R0–R5 + A1–A5, job-concurrency bound, Incidents I1
   resolution gate, `ObjectStore.delete`, and off-request-thread legacy triggers all shipped
   2026-07-23/24. The one survivor is **MNT-14**; its retention question is now answered (§2 D5 —
   retention tier), so the only thing left in its way is the backend Incident `Archived` state — a
   build, not a call.
2. **Decision gates — DRAINED 2026-07-25.** §2 is empty; see it for what each call was and where the
   rationale lives. Several rows below changed shape as a result, and three had **wrong premises**
   corrected (D3 legacy-route framing, D7 tags-are-greenfield, D14 already-tightened) — trust §2 over
   any older phrasing you remember.
3. **Small, concrete quick wins — DRAINED 2026-07-25.** `canOfferDatasets` → admin (D14, `2b1e7e9d`) ·
   the `canCurateMenus` split (D4) · `KeycloakTokenRelay` → `OidcTokenRelay` + no derived
   `tokenEndpoint` (D15) · chunking on by default at 8 GiB (D12). **One deliberate deviation: D11's
   on-by-default `memory_limit` was NOT shipped** — operator call, see §6.
4. **Dependent chains (sequence behind a build, no longer behind a decision):** Lens Access P3 · NFR-7
   execution (now parallel — C1 is not a predecessor, D1) · MNT-14 (after the `Archived` state) ·
   Postgres multi-user (write the `docs/superpower/` plan first, then store pooling).
5. **Independent — schedule by value, no ordering constraint:**
   - **AGT-6a inline AI authoring** — largest schedulable item; no new backend capability. *(Ready
     pending decision asks D1–D4 **in its own plan** — a separate numbering space from §2's D1–D17,
     which are all answered. Do not confuse the two.)*
   - **Generic tag system (D7)** — newly rescoped, plan-first, second-largest item on the board.
   - Link-analysis V2 (b)/(c)/(d) — all three of its blockers were §2 calls and are now answered
     (D9/D10/D16) · notification delivery-status webhooks (D8) ·
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
| D2 | A bundle may carry a `connection` **reference-only, secrets stripped** — `${ENV:…}` travels, no secret value in any form (not even bundle-encrypted: bundles land in git, CI, and support tickets). **BUILT AND SHIPPED 2026-07-25** — `ConnectionProfile.toBundleMap()` strips (never masks) a literal secret, import rejects a non-reference secret-looking field, `connection` is first in `APPLY_ORDER` and stays out of `INTEGRITY_KINDS` | `okf/…/metadata-bundle.md` |
| D3 | **Delete the legacy surface; soak criterion consciously overridden** — justified: no live deployment and the SPA was already fully v1-migrated. **BUILT AND SHIPPED 2026-07-25** (`be498f35` code+tests, `bbf569df` docs) — `/api/v1` is the only business surface, the four infra probes stay unversioned, `isInfraRoute` is now the allow-list | `okf/…/api-v1.md` |
| D4 | **Split `canCurateMenus`** out of `canAuthorWorkbench` — a nav change is visible to every business user and is not a build activity. **SHIPPED 2026-07-25**: granted to admin/power/super, `PUT /nav/menus` re-gated, manifest + gate landed together. **UI half completed the same day** — `LensService.canCurateMenus`, the `menus.curate` action node under Settings, and the mock seed table; no residual. ⚠ Finishing it surfaced an unrelated pre-existing lens/capability mismatch for admin-only subjects (§5) | `okf/…/auth-security.md` |
| D5 | **Retention tier, not archive-is-terminal** — `Archived` becomes a real state but carries a retention window, and expiry is what makes an Incident purge-eligible. Needs a dry-run-first sweep + a legal-hold exemption. `ObjectStore.delete` already shipped, so the one remaining blocker is the `Archived` state itself | `okf/…/jobs.md` |
| D6 | **Reuse the C6 workflow/TOON pattern + the `attribute-spec` renderer**; no new endpoint — it would be a third config idiom for a problem the shipped `GET /workflows/{type}` precedent (same pane) already solves. **Not UI-only**: sections must be server-authored TOON | `okf/frontend/features/objects.md` |
| D7 | **Rescoped by the operator** from "a `tags` filter on `GET /objects`" to a **generic Gmail-label-style grouping concept** spanning streams/rules/alerts/datasets, on a central registry + `(tag, entity_kind, entity_id)` assignments. ⚠ **Not greenfield, and the old row was wrong twice** — object-scoped tags already ship (`Tag` registry + `TagRule` + `/tags` + `/tags/rules` + a Tags folder in the mail nav), and `attributes.tags` **is** written (`ObjectService.ATTR_TAGS`, five call sites), so the dismissed narrow filter would have worked. This is that system *generalized*: CSV-inside-the-entity → central registry + assignment store, and beyond `OperationalObject`. **✅ PHASES 1 + 2 SHIPPED 2026-07-26** — `TagAssignment*` store (both backends), four `/tags/assignments…` + `/tags/{name}/targets` routes, per-Space wiring, and the CSV reconciliation: the assignment store is the **source of truth** and `attributes.tags` is a **projection** re-derived on every mutation path, with an idempotent startup backfill. 25 tests. **Q5 dissolved** (no capability — gate per target via the shared `AnnotationTargets`, so a tag can never become an access grant); **Q6 split** (the plain "everything tagged X" read shipped; the Gmail saved-search layer deferred). **Rename/delete shipped the same day** (`POST /tags/{name}/rename`, `DELETE /tags/{name}` — registry + edges + CSV projections + Tag Rules move together). **✅ COMPLETE END-TO-END 2026-07-26** — the `/tags` pane (vocabulary + "everything carrying this tag" across kinds + rename/delete/untag, `TagsService`, offline mock) closed the last gap and the plan is archived. Residuals only (§6): assignment UI for non-object targets, the startup backfill scan, the `NoteTargets` misnomer | `okf/backend/control-plane/tags.md` |
| D8 | Track **all three** of `delivered`/`bounce`/`complaint` — complaint is the only class with a *deliverability* consequence, so it can't be the one dropped. Needs per-status timestamps (not one mutable enum — `delivered` then `complaint` is the normal spam-button case), adapter-edge normalization of provider vocabularies, a hard/soft bounce split, and a signature-verified fail-closed callback | `okf/…/events-metrics.md` |
| D9 | **Yes — widen the Exchange `kind` axis** to carry saved views rather than build parallel sharing. A view grant must **require its datasets' grants** (generalize the widget→dataset cascade, don't special-case it twice) and is **live-mode only** (a view has no rows of its own, so `snapshot` must be rejected, not silently treated as live) | `okf/…/exchange-sharing.md` |
| D10 | **Generalize the note model** to any `(kind, id)` target — do not re-key `ObjectNote` by component `type`+`id`. The narrow re-key buys the same feature and guarantees a third caller becomes a third special case. **SHIPPED (backend)**: `ObjectNote.targetKind` + `inspecto_ops_notes.target_kind` (added with `ADD COLUMN IF NOT EXISTS`, legacy rows backfilled to `object`), `NoteStore.forTarget`, kind-agnostic `NoteService` with a per-family existence gate, `/notes/{targetKind}/{targetId}/comments|attachments`. Vocabulary = `"object"` + `ComponentStore.WRITABLE_TYPES` (`NoteTargets`). `/objects/{id}/comments|attachments` unchanged. Authz: objects keep the SEC-7d `scoped()` gate, component kinds use the R3 `ComponentAccess.requireView` gate. **SHIPPED (UI, 2026-07-25)**: a "Comments" action next to "Version history" in the Link Analysis saved-views menu opens `LinkAnalysisCommentsDialog`, backed by a new `NotesService` (`/notes/link-analysis-view/{id}/comments`). D10 is now fully shipped end-to-end. | `okf/frontend/features/link-analysis.md` |
| D11 | **Conservative fixed per-instance cap + spill, on by default.** ⚠ **NOT IMPLEMENTED — deliberately declined by the operator 2026-07-25** in favour of "spill routing only". No default `processing.duckdb.memory_limit` ships, so **the overcommit exposure this decision existed to close is still open**: each concurrent run still gets DuckDB's ~80%-of-RAM-per-instance default. Spill *routing* already shipped independently (`BatchIngestStrategy.scratchDir` → `dirs.temp` on the data volume), and `max_temp_directory_size` has no fixed default because none is defensible without knowing the volume size (DuckDB uses ~90% of disk). **Reopen with a measured value** — see §6 | `okf/…/duckdb.md` |
| D12 | **Chunking on with a large threshold.** **SHIPPED 2026-07-25** at **8 GiB** (`processing.chunking.max_file_bytes`, was `0`/disabled) — far above routine inputs so normal workloads never change shape. ⚠ It was meant to land *after* D11, because a memory cap turns the failure mode into "spill" and makes a high threshold safe. D11 was declined, so **chunking is now the only bound on a pathological single file** | `okf/…/duckdb.md` |
| D13 | **Confirmed parked** — stays gated on a real onboarding-observation session (interview #2). An engineering placeholder would bake in an arbitrary answer that is expensive to unwind once forms ship | §7 · interview #2 |
| D14 | **Ratified with one tightening — SHIPPED 2026-07-25 (`2b1e7e9d`).** ⚠ `canConfigureAccess` + `canApproveShares` were **already** admin/super-only in `Roles.SEED`, so the "bootstrap deadlock left them over-granted" premise was unfounded. `canAuthorAlertRules`/`canRequestShares` ratified as developer/ops-tier. `canOfferDatasets` moved to admin (cross-space data exposure with no second gate) | `okf/…/auth-security.md` |
| D15 | **Withdrawn, not answered — there is no vendor of record.** The IdP/gateway is a per-client deployment choice; standards-only and configurable. Litmus test: new auth code that can't be pointed at a different compliant IdP by config alone is wrong. **Both residuals SHIPPED 2026-07-25**: `KeycloakTokenRelay` → **`OidcTokenRelay`** (incl. the `META-INF/services` entry), and the Keycloak-shaped `tokenEndpoint` default deleted. ⚠ **BREAKING for existing deployments** — `-Dauth.oidc.tokenEndpoint` is now **required** and fails fast at startup; it is no longer derived from the issuer, so a Keycloak deployment that relied on the derived path will not boot until the flag is set from the provider's `/.well-known/openid-configuration`. `X-JWT-Assertion` default kept, now documented as *a* convention | `okf/…/auth-security.md` |
| D16 | **A dedicated system Space** owns the domain-seeded pattern packs — the space-template-gallery path would fork packs per Space, so a fix to a shipped pattern could never reach the copies | `okf/frontend/features/link-analysis.md` |
| D17 | **Open, unscheduled** — acknowledged gap, no demand pressure, no build time committed | §7 |

## 3. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| OPS-5 | Provenance conservation on live data (built, off by default) | **Live-feed soak only — no code left.** Offline de-risk + the imbalance→`NotificationRule` question both closed 2026-07-22/23. `docs/ops/provenance-conservation-verification.md` |
| NFR-7 | Compliance certifications | PARTIAL (not started) — **UNGATED 2026-07-25 (D1): runs in parallel, C1 is not a predecessor.** Real remaining constraints are the Type II observation window (needs CC6 live — it is) and external-party pacing. `superpower/compliance-certifications-plan.md` |
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
| **Bundle / Exchange** | ~~missing kind `connection`~~ **DONE 2026-07-25 (D2: reference-only, secrets stripped)** — the `BundleRoutes` kind set is now complete · **widen the Exchange `kind` axis to carry saved views (D9)** — live-mode only, a view grant requires its datasets' grants · `requires` present-but-different classification · per-editor "load as draft" import — **not a small buildable**: `BundleTransferService.write` commits straight through each store, there is no generic draft seam, and editors open by route/id (not injected content); design-first, likely multi-session, **do not fake it with a cross-kind `enabled:false` stamp** | `okf/backend/control-plane/exchange-sharing.md` |
| **Job framework** | MNT-14 archived-Incident sweep — **retention model decided (D5: retention tier + a real `Archived` state, dry-run-first sweep, legal-hold exemption)**; the remaining blocker is building the `Archived` lifecycle state, not a call · maintenance COULD tier: space-to-space comparison · predictive maintenance (AGT-5/self-healing territory, deliberately deferred) | `okf/backend/control-plane/jobs.md` |
| **Queries / BI** | `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers | `okf/backend/control-plane/queries.md` |
| **Notifications** | delivery-status webhooks — **inbound** provider bounce/complaint/delivery callbacks (the outbound `webhook` channel shipped 2026-07-22); **status model decided (D8: track `delivered`+`bounce`+`complaint`, per-status timestamps, adapter-edge normalization, hard/soft bounce split, signature-verified fail-closed callback)** — now a build; Standard/Enterprise flavor territory · GeoIP · auth-gated per-user prefs / security triggers | `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | optional S8 (connector-direct emission + cross-space controller) · a general **event-triggered consequence policy gate** (still `/apply`-only) · RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet) · ⚠ **no producer threads `causationId`**, so `/signals/tree` is flat today | `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md` |
| **Link analysis** | V1 + four V2 tracks + timeline + version history all shipped. **D10 (per-view comments) SHIPPED end-to-end 2026-07-25.** Remaining: (b) **sharing** a saved view → widen the Exchange `kind` axis (**D9**; ⚠ the old "sharing is frontend-only" claim was wrong) · (c) domain-seeded pattern packs → a dedicated system Space owns them (**D16**) · (d) AI assist (routes through the Assist seam) | `okf/frontend/features/link-analysis.md` |
| **Geo map** | DuckDB **`spatial` extension** — deliberately deferred: plain SQL covers today's projection/aggregation, and loading it means bypassing the hardened `SqlSandbox` extension lockdown **and** bundling a per-platform native binary for offline installs. Only worth it once a real geometry op (ST_Distance/ST_Contains, spatial join) is demanded. *(Progressive loading + worker binning CLOSED 2026-07-24 as obsoleted by the server-side fold + the hard `GEO_POINT_CAP = 5000`. Revisit ONLY if that cap is raised — the candidate then is worker-izing the O(n²) toolbox analyses, not binning.)* | `okf/frontend/features/geo-map.md` |
| **Pipeline graph** | T15 residuals (non-blocking): per-flow TOON override of the back-pressure thresholds (globals only) · flipping the intake cap on by default (needs a soak) · remote-fetch economy (the cap applies post-dedup, so a remote source still materialises its full ready set — unchanged from pre-T15, but a pre-materialise cap would save bandwidth) · mock-only: run-to-here `POST …/run` (path deliberately reserved for the editor's scratch-only contract) · `/asn1/modules` **stays mock-only** — no backend ASN.1 capability exists | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 |
| **Acquisition / connections** | the JDBC-based connectors each need their own library-specific proxy wiring · an actual **HTTP CONNECT** handshake for any connector (SOCKS5 is wired for SFTP/FTP/FTPS; HTTP fails closed) | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | C3 configurable Findings sections — **decided (D6: reuse the C6 workflow/TOON pattern + `attribute-spec` renderer; server-authored TOON, not UI-only)**, now a build · ⚠ the old `category`/`tags` params row is **superseded** — D7 rescoped tags into a generic cross-entity concept, now shipped (`okf/backend/control-plane/tags.md`); do not build an `attributes.tags` filter — the CSV is a projection, and `GET /tags/{name}/targets` is the cross-kind read. *(Case-analytics dataset SHIPPED 2026-07-25 as the `objects.analytics` Job Type — plan archived, as-built in `okf/backend/control-plane/jobs.md`.)* | `okf/frontend/features/objects.md` · `okf/backend/build-run/operations-reference.md` |
| **Menu builder** | the `canCurateMenus` split is **COMPLETE end-to-end 2026-07-25** — server gate + the UI half (`LensService.canCurateMenus`, the `menus.curate` action node, Menu Builder's `canCurate`, mock seed table). No open items | `okf/backend/editions/auth-security.md` · `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | Reference Phase-2 is **COMPLETE** (P0–P4, plan archived). Residual non-blocking deferrals: **D5-ref** — how a `delete` tombstone *enters* the reference store is undefined (the write path always stamps `'upsert'`; the views only *honour* an existing tombstone) — needs a call on the input signal (reserved column? Decision Rule consequence?) when a real delete-feed use case lands · **D6-ref** — within-batch same-key tie-break is arbitrary; add the optional latest-by-`order_by` column only when a batch can legitimately carry ordered same-key versions · optional templates entry (space-template-gallery precedent) | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |
| **Quarantine / D-ETL** | reprocess is **whole-batch only** — no record-level replay (tracked only if prioritized) | `okf/frontend/features/run-detail.md` |

## 5. UI residuals + security-module residuals

> ### 🔴 SEC-INCIDENT-1 — leaked client secrets, ROTATION OUTSTANDING (opened 2026-07-25)
>
> Five OAuth client secrets sat in `inspecto-ui/src/environments/*.ts` and were pushed to a **PUBLIC**
> GitHub remote (`jotder/ucc-file-processor`, `isPrivate:false`) from **2026-06-12** until removed in
> `8dd072c6` (2026-07-25) — roughly six weeks, 4 commits. **Removing them from HEAD did not remediate anything:**
> the values remain in git history, in every clone and fork, and in GitHub's caches. **Treat all five as
> compromised and rotate at the issuer.** This row closes only when rotation is confirmed, not when the
> code edit landed.
>
> | Secret | Scope | Values |
> |---|---|---|
> | `iamClientSecret` | IAM server — **same value reused in all 5 environment files** | 1 (`f6f6…e65c1`) |
> | `appClientSecret` | dev · gamma · gammadev | 3 |
> | `appClientSecret` | **`app1.pronto.lebara.sa`** — named-customer production (`environment.prod.ts`) | 1 |
>
> Rotate the production `appClientSecret` first, then the shared `iamClientSecret` (one rotation covers
> every environment). Execution checklist: [`ops/secret-rotation-runbook.md`](ops/secret-rotation-runbook.md). Client IDs are retained in-repo — they are not secrets — so the issuer-side entries
> are identifiable: `appClientId` 8738429453654150144 (prod), 8825302933668759552 (dev/offline),
> 5829657973124606976 (gamma), 2826856297262914560 (gammadev); `iamClientId` 1070682796450139008.
>
> **Why deletion was safe:** the only consumer, `app/app-component.service.ts` (HTTP Basic with
> `clientId:clientSecret`), was removed with the dead Fuse code — nothing in `src/` has read these keys
> since. UI verify after removal: `test:ci` 1642 pass, `ng build` clean.
>
> **Deliberately NOT done** (operator call 2026-07-25): no `git-filter-repo` history rewrite — it
> invalidates every clone and breaks the shared-sandbox shift model while still not purging GitHub's cache
> or forks; rotation is the real fix.
>
> **✅ Reintroduction guard SHIPPED** (2026-07-25): `tools/check-secrets.mjs`, wired into `ci.yml` beside
> the vocabulary guard (~1s, pure Node). Flags a secret-ish key assigned a ≥16-char literal; ignores
> `${ENV:…}`/`%VAR%`/`process.env`, placeholders, `*Ref`/`*File`/`*Name` indirection keys, `token`-suffixed
> keys (D15 made `tokenEndpoint` required config), and anything containing `EXAMPLE` (AWS's published SigV4
> vectors). Line hatch: `secret-allow`. Verified both ways — green on the repo, and red on a synthetic
> fixture in the incident's exact shape. ~~Master-only by design: do NOT merge it forward until the `4.x`
> PKCE fix lands~~ — **that gate was satisfied by P1 (`89cb3cce`), and the guard now runs on `4.x` too**
> (`27780fee`). The branch that leaked four of the five credentials is guarded against reintroducing them.
> Re-verified on `4.x` both ways: green on the branch, and exit 1 on an injected 32-char `appClientSecret`
> in `environment.gamma.ts`. **Keep the two copies of the script identical** — a divergence means one
> branch is guarded by weaker rules than the other.
>
> **✅ `4.x` IS NOW FIXED CODE-SIDE (2026-07-25) — ROTATION IS UNBLOCKED.** P0 (`481a68d5`) removed the
> dead confidential-client code holding the inline IAM literal; **P1 (`89cb3cce`) put the live path on
> PKCE and removed `appClientSecret` from `4.x` entirely** — `app.properties.ts` and all four
> `environments/*.ts`. `git grep appClientSecret` on `4.x` now returns nothing. Both propagated to master
> as no-content `-s ours` merges (`54443256`, `37c98c6a`). **Nothing code-side blocks rotation any more;
> what remains is P2 — deploy the `4.x` bundle, then rotate.** Two follow-ons this unlocked or exposed:
>
> - **✅ `tools/check-secrets.mjs` now runs on `4.x`** (`27780fee`) — the "would pin `4.x` CI permanently
>   red" objection died with P1. Done.
> - **✅ Callback `state` validation — FIXED (`8c3a7654`), and it caught a worse bug.** P1 generated and
>   sent a `state` but never checked it, so the CSRF defence was not armed. Closing that exposed a
>   **login-breaking regression P1 had introduced**: the callback read the code as
>   `href.substring(indexOf('code=') + 5)` — everything to the end of the URL — which was correct only
>   while `code` was the last param. Once `state` was echoed back, `?code=abc&state=xyz` parsed the code
>   as `"abc&state=xyz"` and the token exchange fails; `?state=xyz&code=abc` still worked. **Parameter
>   order is the IdP's choice, so `89cb3cce` alone is a coin-flip login break — never deploy it without
>   `8c3a7654`.** Neither the build nor the unit suite could have caught it (nothing covered callback
>   parsing; no live-IdP round trip runs in CI) — now covered by 8 tests including both orderings.
>   ⚠ **The lesson generalizes: this line of work is verified only against a compiler and jsdom.** Treat
>   any further `4.x` auth change as unverified until it survives a real IdP.
> - **⚠ The refresh grant now sends `client_id` with no client authentication** — an assumption about the
>   IdP that has not been tested against the real issuer. If wrong, sessions break at *first token
>   expiry*, not at login, so a sign-in-only smoke test will not catch it. Verify a refresh explicitly
>   during the P2 deploy (plan §4 Q2).
>
> **Historical — the state that motivated the P0/P1 work:** On `master` the removal was a no-op because
> the consumer was already gone; on `4.x` `app/app-component.service.ts` still exists, is wired into
> `app.component.ts` + `modules/commons/page.manager.ts`, and actively sends `client_secret` in a token
> request plus `Authorization: Basic btoa(clientId:clientSecret)` — with the IAM secret **hardcoded inline
> as a literal at line 26**, not even read from the environment file. So:
>
> - **Rotation will break running `4.x` SPAs.** They authenticate with exactly these values and will fail
>   until rebuilt with new config. Rotation needs a deployment-coordination window, not a quiet swap.
> - **The `4.x` LIVE-path fix is a design change** — a browser bundle cannot hold a confidential client
>   secret, so re-issuing a secret that still ships in the SPA just reproduces this incident with fresh
>   values. That path needs public PKCE (or a server-side token exchange) first.
> - **⚠ Two corrections to the paragraph above, verified 2026-07-25 against `4.x` `291c86a1`** — full
>   detail + phased plan in [`superpower/4x-public-pkce-plan.md`](superpower/4x-public-pkce-plan.md):
>   - **The live token exchange is `modules/auth/auth-service.ts`** (`:84-90` code→token, `:106-109`
>     refresh, `:148` Basic header), a second near-duplicate implementation this row never mentioned.
>     `app-component.service.ts` is *not* the live path.
>   - **The hardcoded inline secret is in DEAD code and IS deletable today, with no design change.**
>     `app-component.service.ts`'s `renewAccessToken`/`retrieveToken` have **zero call sites** on `4.x`;
>     `app.component.ts` injects the service but calls nothing on it. So "not a deletion" holds only for
>     the live path — the worst single artifact can go now (plan P0).
>   - Also: **`master`'s `inspecto/api/pkce.ts` is a zero-import RFC 7636 implementation that ports
>     verbatim**, so the fix is a port, not a design exercise. (`session.service.ts` does *not* port — it
>     needs `/bootstrap` + `/api/v1`, which `4.x` predates.)
> - Merge-forward was therefore **deliberately not followed** here (operator call 2026-07-25): the
>   `master` fix shipped alone, and the `4.x` remediation is tracked as its own item rather than
>   improvised inside a shift.
>
> Retired lines `1.x`–`3.x` very likely carry the values too; policy forbids committing there, and
> rotation covers them.
>
> **✅ Orphaned worktrees on disk — RESOLVED.** The two dirs under `.claude/worktrees/`
> (`quirky-lalande-a4a696/`, which held live copies of four `inspecto-ui/src/environments/*.ts` plus
> further copies in its `.angular/` build cache, and `vigorous-ptolemy-911ed7/`) are **gone as of
> 2026-07-25** — confirmed absent from both disk and `git worktree list`. Their deletion never reduced the
> incident's severity (the values are public via git history regardless); it only stopped a local grep
> from handing them out. Rotation remains the fix.
>
> Lower severity, same files, unaddressed: internal hostnames/IPs are published in-repo
> (`68.183.16.242`, `p20.prod.pronto`, `app1.pronto.lebara.sa`).

**UI residuals (small, valuable):**
- **No IdP end-session on sign-out (opened 2026-07-25).** `user.component.ts`'s logout used to redirect to
  `environment.authServerUrl + '/confirm-logout'`; that key went with the published internal hostnames/IPs
  in `ca3680df`, which broke the UI build. It now routes to the in-app `/logout` (operator call), so the
  Inspecto session ends but **the IdP's SSO session does not** — a later sign-in may not re-prompt. The
  proper fix is standards-based OIDC RP-initiated logout with `end_session_endpoint` **discovered from the
  provider's `/.well-known/openid-configuration`**, never a hardcoded host — same D15 litmus test that made
  `tokenEndpoint` required config.
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
- **⚠ Lens/capability mismatch for admin-only subjects (surfaced 2026-07-25 finishing the D4 UI half).**
  Under OIDC, `LensService.allowedLenses` offers Builder only for `canAuthorWorkbench` and Ops only for
  `canOperateRuns`; everyone else is snapped to **Business, which is `readOnly`** — and every capability
  signal is `granted && !readOnly && …`. So a subject holding the **admin** seed and nothing else
  (`canConfigureAccess`, `canCurateMenus`, `canOnboardConnections`, `canApproveShares`,
  `canOfferDatasets`) evaluates **every one of them false in the UI** and sees no admin affordances,
  even though the server would authorize the calls. Pre-existing (it predates D4 — `canConfigureAccess`
  has always had it), not introduced by the split, and invisible in the honor-system/Personal mode
  where `granted()` short-circuits true. **Needs a call:** either project admin capabilities onto a
  lens, or stop conflating "read-only lens" with "no admin rights" — the `!readOnly()` conjunct is the
  actual bug surface. `inspecto-ui/src/app/inspecto/api/lens.service.ts:60`
- X-Actor **full removal** — client-migration-gated (see §4 API v1).

> **Do not partially implement security concerns elsewhere** — this section stays the single scope.

## 6. Engineering / tech-debt

The engineering MoSCoW (build hygiene, `CollectorService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) is **COMPLETE and
archived**; the 16-module reactor as-built + the extraction playbook live in
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

**Open:**
- ~~**D7 phase 2 — the tag split-brain**~~ **CLOSED 2026-07-26.** The assignment store is now the source
  of truth and `attributes.tags` is a projection re-derived on every path (open/adopt, `applyTagRule`,
  merge union, split carry-over, `applyTag`/`removeTag`); `backfillTagAssignments()` adopts legacy CSV
  tags once per Space at startup. → `okf/backend/control-plane/tags.md`
- ~~**D7 — `rename`/`removeTag` have no route**~~ **CLOSED 2026-07-26.** `POST /tags/{name}/rename` and
  `DELETE /tags/{name}` (both `canAuthorWorkbench`) go through `ObjectService.renameTag`/`deleteTag`, which
  move the registry entry, the assignment edges, **every affected object's CSV projection** and any **Tag
  Rule** applying the tag together; the routes then rewrite the `*_tag.toon`/`*_tagrule.toon` files.
  Rename-onto-existing **merges**; deleting a tag a rule still applies is **409**.
  → `okf/backend/control-plane/tags.md`
- **D7 — the startup backfill is a full object scan.** Idempotent and cheap at Incidents/Cases volumes
  (human-scale, not telemetry), but it is O(objects) on every boot even long after migration. Revisit only
  if it shows up in startup time — a persisted "migrated" marker would be the fix, and is not worth it yet.
- ~~**D7 — no UI**~~ **CLOSED 2026-07-26.** The `/tags` pane (`modules/admin/tags/`, nav under Operations)
  lists the vocabulary, shows *everything carrying a tag across kinds*, and renames / deletes / untags
  through `TagsService`; the offline mock answers all six routes. **D7 is complete end-to-end and the plan
  is archived.** → `okf/backend/control-plane/tags.md`
- **D7 — applying a tag to a non-object target is API-only.** `TagsService.assign` ships and the mock
  serves it, but the only *assignment* UI is still the mail pane's tag menu (objects). Labelling a saved
  view or a dataset in place needs a tag menu on those panes — a small per-pane addition, deliberately not
  faked with a cross-kind target picker in the vocabulary screen.
- **`NoteTargets` is now misnamed.** It is the shared annotation-target vocabulary for both notes (D10)
  and tags (D7), but still lives in `com.gamma.ops.note` under a note-specific name. Rename to something
  neutral when a change is already touching it — not worth its own churn, but it will mislead a reader.
- **`ObjectStore.delete` is unwired SPI surface** (found 2026-07-25 while researching D7). `12cf20eb`
  added `void delete(String)` to `com.gamma.ops.ObjectStore` (`:50`) with both impls and unit tests, but
  it has **no production caller and no route** — there is no `DELETE /objects/{id}`; Incidents/Cases are
  only closed, merged, or split. Either wire it or drop it; leaving a tested-but-unreachable delete on an
  SPI invites someone to assume hard-delete is a supported operation and build cascade logic against it.
  ⚠ Corrects a breadcrumb that recorded this as a shipped capability — the *SPI method* shipped.
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
  **D12 SHIPPED, D11 DECLINED (2026-07-25).** Chunking is now on by default at **8 GiB**. The
  **on-by-default `memory_limit` (D11) was deliberately not shipped** — operator call, "spill routing
  only". ⚠ **The exposure D11 existed to close therefore remains open:** unset `memory_limit` ⇒ DuckDB
  defaults to ≈80% RAM *per instance* ⇒ concurrent runs overcommit ⇒ the whole box (incl. the HTTP API)
  can go unresponsive, and chunking is now the only bound on a pathological file. **To reopen, bring a
  measured value** — that is what blocked it, not the decision. A semaphore-computed cap stays rejected
  (`maxConcurrentRuns` defaults to unbounded; batch-ingest has a second limiter). Spill *routing* already
  ships (`scratchDir` → `dirs.temp` on the data volume); `max_temp_directory_size` has no fixed default
  because none is defensible without the volume size. Read-path is **not** the risk (see C6 below).
  `okf/backend/engine/duckdb.md`
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
| ~~API-5 legacy sunset~~ | **RETIRED 2026-07-25** — D3 built and shipped (`be498f35`, `bbf569df`); plan and runbook both archived, as-built in `okf/…/api-v1.md`. The still-open **X-Actor full removal** (§4, §5) was always a separate client-migration-gated item and stays open |
| EOI-7b eoiagent publish | agent-kernel-replacement §open-items |
| eoiagent `DryRunProvider` | AGT-5 follow-on (§3) · AGT-6b prerequisite (§3) |
| MNT-14 archived-Incident sweep | D5 answered (retention tier); remaining blocker = build the `Archived` state |
| Parser field tiers | D13 (parked) · §5 UI attribute tiers · interview #2 |
| Generic tag system | D7 (rescoped) · **COMPLETE end-to-end 2026-07-26**, plan archived; residuals only (§6) · `okf/backend/control-plane/tags.md` · ⚠ supersedes the old §4 "`category`/`tags` params on `GET /objects`" row — the *existing* `Tag`/`TagRule`/`/tags` system is what gets generalized, not a separate feature. Keep aligned with **D10** (generalized notes): same `(kind, id)` addressing problem, so one adopts the other's scheme |
| Saved-view sharing | D9 (Exchange `kind` widening, §4 Bundle/Exchange) · link-analysis V2 (b) (§4 Link analysis) |
| ~~Geo-map Phase 4 backend~~ | **RETIRED 2026-07-25** — the endpoints + `ComponentStore` widening shipped; only the `spatial` extension + `spatial` QueryType survive, tracked in their own §4 rows |
| ~~INV-1 Entity Projection backend~~ | **RETIRED** — shipped |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then **delete the row here** — do not leave a strikethrough as-built narrative behind
(that is what the OKF concept docs and git history are for; this page had grown to ~40k tokens that
way before the 2026-07-25 compaction). New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
