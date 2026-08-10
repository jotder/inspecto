# Consolidated Backlog — every OPEN item, one page

**Updated:** 2026-07-27 (**MNT-14 COMPLETE — the `incident_purge` maintenance task ships**, the last root
enabler, and `ObjectStore.delete` finally has its production caller. `ObjectService.purge` cascades to
notes/attachments, links and tag edges; selection is `ObjectQuery.purgeEligible`; legal hold is enforced
**inside** `purge()` so a hold applied between preview and run still wins; the dry run reports held-but-
expired as its own count. ⚠ **G4's premise was WRONG** — the four `JobService` store hooks it called for
were never needed: `objects(ObjectService)` already existed and was already wired, and `ObjectService` holds
all four stores as non-null finals, so **there is no partial cascade to fail closed on**. ⚠ Three
`@PublicApi` interfaces gained abstract methods earlier the same day ⇒ a **third** reason the next release
is MAJOR. Plan archived; as-built in `okf/…/jobs.md`; residuals — chiefly operator retention docs carrying
the G3 "a purge is not all-trace-removed" stance — in §6.) · Previously 2026-07-27 (**AGT-6a plan D9 SHIPPED** — `ConfigJsonSchema` projects any `ConfigSpec` into a
real JSON Schema and the new `config_schema` L1 read tool exposes it, closing A5's stated prerequisite;
⚠ deliberately *not* a tighter `component_draft` schema. Same day: **D11 MEASURED — the number is `2GB`**,
and two of its premises are wrong: ingest peak does **not** scale with file size (~1 GiB flat at 1.0 and
3.1 GiB inputs), and blocking operators **hard-OOM rather than spill** below ~900 MiB, so an aggressive cap
breaks working jobs; `memory_limit` alone still doesn't bound total exposure while `jobs.maxConcurrentRuns`
defaults to unbounded. Same day: **MNT-14 re-scoped — its stated blocker was a WRONG PREMISE**, the
`ARCHIVED` state already ships; the real prerequisites are an oldest-first object query and bulk
delete-by-target on three stores, now planned in `superpower/mnt-14-incident-retention-plan.md`. Same day:
**local secret hygiene verified clean** — no stale worktrees remain, guard green, history redacted on every
local ref; the exposure itself is unchanged and rotation is still outstanding.) · Previously 2026-07-27
(**D7 widget tags MIGRATED — call (c) built end-to-end**, so all five
mock-whitelisted kinds are adopters and no kind carries a private tag system; the save dialog's comma field
is gone. Same day: **Link-analysis V2 (d) authoring half SHIPPED end-to-end** ⇒ V2 complete and the §4
Link-analysis row is retired: a new deterministic non-mutating `projection_author` tool + the query panel as
the 5th `<inspecto-ai-assist>` adopter. It also fixed a pre-existing load-path bug — `patchFormFromView`
ignored `projections[]`, so a multi-mapping *saved* view loaded first-only. Plan archived.) · Previously
2026-07-26 (**AGT-6a A5 SCOPED** — the NL→structure model hop now has a real shape in plan
§3.4; the investigation found the transport **already** does schema-constrained function-calling, that the
cost sits in the unconstrained **nested** payload schemas, and that a `ConfigSpec`→JSON-Schema projection
was designed years ago and never wired. Three new plan-level calls, D9–D11. Same day: **AGT-6a A4-status
closed** — all four operational panes adopted. **D6 configurable Findings sections SHIPPED end-to-end** — `findings-spec`
ComponentStore kind + `GET /findings/{type}` + schema-form rendering, plan archived, residuals in §6; two of
D6's own premises were wrong and are corrected in its row. **D8 SHIPPED end-to-end** — inbound delivery-status
webhooks (SPI + SendGrid/HMAC adapters + fail-closed callback route + receipt store), plan archived; it corrected
three wrong premises, chief among them that nothing correlated a sent message back to a Notification. Same day: **sign-out fixed end to end** — the user-menu button was navigating to a
nonexistent `/logout` after wiping all of `localStorage`, and never reached the backend; RP-initiated IdP
logout shipped with it. Same day: **every route capability now has a client signal + action node** — the
`canRequestShares` residual closed, decided lens-scoped; earlier same day **D7 complete end-to-end**,
plan archived, residuals only in §6. Previously 2026-07-25: **decision session — all seventeen §2 calls answered**; each call's rationale
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
1. **Root enablers — DRAINED, now including MNT-14.** RBAC/ABAC R0–R5 + A1–A5, job-concurrency bound,
   Incidents I1 resolution gate, `ObjectStore.delete`, and off-request-thread legacy triggers all shipped
   2026-07-23/24; the last survivor **MNT-14 shipped 2026-07-27** as the `incident_purge` maintenance task
   (§2 D5's retention tier, enforced). `ObjectStore.delete` finally has its production caller. **Its residuals
   are CLOSED 2026-07-27 too** — the operator retention procedure now lives in
   `okf/backend/build-run/operations-reference.md` §*Retention & purging*, and "no scheduled instance" was
   resolved as **the decision, not a gap** (nothing schedules a task that hard-deletes business records —
   standing it up is an operator act). §6 keeps the two wrong premises it corrected.
2. **Decision gates — DRAINED 2026-07-25.** §2 is empty; see it for what each call was and where the
   rationale lives. Several rows below changed shape as a result, and three had **wrong premises**
   corrected (D3 legacy-route framing, D7 tags-are-greenfield, D14 already-tightened) — trust §2 over
   any older phrasing you remember.
3. **Small, concrete quick wins — DRAINED 2026-07-25.** `canOfferDatasets` → admin (D14, `2b1e7e9d`) ·
   the `canCurateMenus` split (D4) · `KeycloakTokenRelay` → `OidcTokenRelay` + no derived
   `tokenEndpoint` (D15) · chunking on by default at 8 GiB (D12). **One deliberate deviation: D11's
   on-by-default `memory_limit` was NOT shipped** — operator call, see §6.
4. **Dependent chains (sequence behind a build, no longer behind a decision):** ~~Lens Access P3~~
   **ALREADY SHIPPED — this entry was STALE (corrected 2026-07-27).** Lens Access P3 *is* RBAC **R2**,
   Access-Profile enforcement, which shipped **2026-07-23**; it was still listed here as pending work
   under its other name. → `archived-documents/plans-archive/rbac-abac-plan.md` R2. · NFR-7
   execution (now parallel — C1 is not a predecessor, D1) — **not an engineering chain**: the plan
   exists (`superpower/compliance-certifications-plan.md`), the CC6 controls are live, and what remains
   is the Type II observation window plus external-party pacing, so it is scheduled by the org, not
   built by a shift ·
   Postgres multi-user — **plan written 2026-07-27**
   (`superpower/postgres-multi-user-plan.md`); build starts at P1 pooling.
   *(MNT-14 left this list 2026-07-27 — shipped.)*
5. **Independent — schedule by value, no ordering constraint:**
   - **AGT-6a inline AI authoring** — **A1–A4 shipped 2026-07-26**; what is left are the three
     residual rows in §3 — **A4-status closed 2026-07-26** (all four operational panes adopted), leaving
     the `kpi_report_builder` host and A5's NL model hop, each independently schedulable. **A5 is now
     **A5.1 SHIPPED end-to-end 2026-07-27** — the `/derive` hop on `query_author` + `<inspecto-ai-assist>`'s
     opt-in `prompting` mode, adopted on Queries. Plan **D9 closed** (`ConfigJsonSchema` + the
     `config_schema` L1 tool); **D10/D11 turned out to be already answered in the plan** (opt-in per pane,
     Queries first; the turn cap is A5.2's), so A5.1 was never actually blocked. **A5.2 SHIPPED
     end-to-end 2026-07-27** — the bounded repair loop (cap 3) + schema-constrained regeneration, adopted
     on the Components pane's **schema** kind. ⚠ Its host was a **wrong premise**: no pane adopted
     `component_draft` at all, and of the Components dialog's four kinds only `schema` has a `ConfigSpec`.
     **A5.3 SHIPPED end-to-end 2026-07-27 ⇒ A5 is COMPLETE** — `pipeline_author` reuses the loop
     (a `REPAIRABLE` map keys each tool to the argument it rewrites) rather than the hop the plan budgeted:
     ⚠ its "graph errors are structural, not field-level" premise was **wrong**, `PipelineValidator` has
     always produced coded issues. ⚠ It also found the **A2 Pipelines adoption had never worked against a
     real backend** — flat args where the tool wants `flow`, and a name string where the adapter wants the
     graph — both masked by a mock more lenient than the server.
     ⚠ "No new backend capability" turned out **false** for A1 — see the AGT-6a row. *(Its plan's
     **D1–D11** are a separate numbering space from §2's D1–D17 — and they now COLLIDE on D9/D10, which
     mean different things in each. Always say "plan D9" or "§2 D9".)*
   - ~~**Generic tag system (D7)**~~ — **COMPLETE end-to-end 2026-07-26/27**, plan archived; all five
     annotatable kinds adopted, and the `NoteTargets` misnomer closed 2026-07-27 (→ `AnnotationKinds`).
     Only the O(objects) startup backfill scan remains in §6, deliberately.
   - ~~Link-analysis V2~~ — **COMPLETE end-to-end 2026-07-27.** (b) sharing + (c) pattern packs + (d)'s
     vocabulary half shipped 2026-07-26; **(d)'s authoring half shipped 2026-07-27** as the deterministic
     `projection_author` tool + a `<inspecto-ai-assist>` adopter on the query panel, with the **pane**
     supplying the column list because no tool-layer route returns one. All four plans archived; as-built:
     `okf/frontend/features/link-analysis.md`. No open Link-analysis items remain — the §4 row is gone.
   - M4 Fuse remainder · eoiagent `DryRunProvider` · DuckDB `spatial` extension.
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
| D6 | **✅ SHIPPED end-to-end 2026-07-26.** Configurable Findings = a **`findings-spec` ComponentStore kind** (one per `ObjectType`) served by **`GET /findings/{type}`**, rendered by `<inspecto-schema-form>`; absent one, the built-in default is today's exact shape, so unconfigured deployments are unchanged. Validation is fail-closed at authoring time (422) via a per-kind hook in `writeComponent`. ⚠ **Two premises in this row's old wording were wrong**: the workflow/TOON pattern is a **boot-time CLI-path scan** with no write root or CRUD (unusable for operator-editable config), and the `attribute-spec` renderer is the **frontend** `<inspecto-schema-form>`, **not** backend `ConfigSpecs`/`FieldSpec` (compiled-in Java, not authorable). A ComponentStore kind still adds **no endpoint** — `/components/{type}` CRUD is generic — so it satisfies the constraint while joining the `alert-rule`/`notification-rule` idiom rather than being "a third idiom". Plan archived; residuals in §6 | `okf/frontend/features/objects.md` |
| D7 | **Rescoped by the operator** from "a `tags` filter on `GET /objects`" to a **generic Gmail-label-style grouping concept** spanning streams/rules/alerts/datasets, on a central registry + `(tag, entity_kind, entity_id)` assignments. ⚠ **Not greenfield, and the old row was wrong twice** — object-scoped tags already ship (`Tag` registry + `TagRule` + `/tags` + `/tags/rules` + a Tags folder in the mail nav), and `attributes.tags` **is** written (`ObjectService.ATTR_TAGS`, five call sites), so the dismissed narrow filter would have worked. This is that system *generalized*: CSV-inside-the-entity → central registry + assignment store, and beyond `OperationalObject`. **✅ PHASES 1 + 2 SHIPPED 2026-07-26** — `TagAssignment*` store (both backends), four `/tags/assignments…` + `/tags/{name}/targets` routes, per-Space wiring, and the CSV reconciliation: the assignment store is the **source of truth** and `attributes.tags` is a **projection** re-derived on every mutation path, with an idempotent startup backfill. 25 tests. **Q5 dissolved** (no capability — gate per target via the shared `AnnotationTargets`, so a tag can never become an access grant); **Q6 split** (the plain "everything tagged X" read shipped; the Gmail saved-search layer deferred). **Rename/delete shipped the same day** (`POST /tags/{name}/rename`, `DELETE /tags/{name}` — registry + edges + CSV projections + Tag Rules move together). **✅ COMPLETE END-TO-END 2026-07-26** — the `/tags` pane (vocabulary + "everything carrying this tag" across kinds + rename/delete/untag, `TagsService`, offline mock) closed the last gap and the plan is archived. Residuals only (§6): the startup backfill scan (reviewed 2026-07-27, deliberately still open). ~~assignment UI for non-object targets~~ CLOSED 2026-07-27 (all five kinds adopted); ~~the `NoteTargets` misnomer~~ CLOSED 2026-07-27 (renamed `com.gamma.ops.AnnotationKinds`) | `okf/backend/control-plane/tags.md` |
| D8 | **✅ SHIPPED end-to-end 2026-07-26.** Inbound provider callbacks tracking `delivered`/`bounce`/`complaint`: a `DeliveryStatusAdapter` SPI (core, ServiceLoader) + SendGrid and generic-HMAC adapters in `inspecto-connectors`, a per-delivery `DeliveryReceipt` store, the fail-closed `POST /api/v1/public/delivery-status/{adapterId}` route, and `GET /notifications/deliveries` as the read surface. 66 tests. ⚠ **Three premises in this row's old wording were wrong or incomplete**: (1) nothing correlated a sent message back to a `Notification` — fixed by minting our own id and embedding it outbound (SMTP `Message-ID` / `X-Inspecto-Delivery-Id`) via a **`default` `deliver` overload**, so the `@PublicApi` SPI never broke; (2) handlers could not see raw request bytes ⇒ new `ApiContext.rawBody`, **cached on the exchange** so `body()` still works after it; (3) **SendGrid signs with ECDSA P-256, not Ed25519** as planned — an Ed25519 impl would have rejected every genuine callback and only shown it against the real provider. Load-bearing: `statusAt` is a per-status **map** (first observation wins), verification precedes every write, unknown **and** unconfigured adapters 404 identically, and an unknown `deliveryId` is **202** (receipts are prunable; providers retry forever on non-2xx). Plan archived; residuals in §6 | `okf/…/events-metrics.md` |
| D9 | **✅ SHIPPED end-to-end 2026-07-26.** The Exchange `kind` axis carries `link-analysis-view`; `Offer.dataset`→`Offer.datasets` and `Exchange.DERIVED_KINDS` generalized the widget→dataset closure instead of special-casing it twice (approve activates the whole closure, revoking **any one** dataset cascades back, an **empty closure is a denial**). View grants are live-only — an explicit `snapshot` is **422, never coerced** — enforced in the ledger, not just the edge. New `GET /exchange/views/{owner}/{item}?consumer=`; `ExchangeRefResolver` needed **no change** because mapping `datasetId`s are rewritten to the same `shared/<owner>/<id>` string a local Dataset uses. ⚠ **A premise was wrong**: an entity-projection view's Datasets are its projection **mappings** (`query.projections[].datasetId`, else `query.projection.datasetId`), **not** `query.roots`/`query.from` — that is the lineage/provenance shape whose roots are Pipelines/catalog assets, and every shipped view uses the single-mapping form, so a roots/from reading 422s all of them while passing hand-written tests. Only entity-projection views are shareable. ⚠ `Offer.fromMap` still reads the pre-D9 scalar `dataset` key — **do not unify it away, and do not write it again** | `okf/…/exchange-sharing.md` |
| D10 | **Generalize the note model** to any `(kind, id)` target — do not re-key `ObjectNote` by component `type`+`id`. The narrow re-key buys the same feature and guarantees a third caller becomes a third special case. **SHIPPED (backend)**: `ObjectNote.targetKind` + `inspecto_ops_notes.target_kind` (added with `ADD COLUMN IF NOT EXISTS`, legacy rows backfilled to `object`), `NoteStore.forTarget`, kind-agnostic `NoteService` with a per-family existence gate, `/notes/{targetKind}/{targetId}/comments|attachments`. Vocabulary = `"object"` + `ComponentStore.WRITABLE_TYPES` (`AnnotationKinds`, renamed from `NoteTargets` 2026-07-27). `/objects/{id}/comments|attachments` unchanged. Authz: objects keep the SEC-7d `scoped()` gate, component kinds use the R3 `ComponentAccess.requireView` gate. **SHIPPED (UI, 2026-07-25)**: a "Comments" action next to "Version history" in the Link Analysis saved-views menu opens `LinkAnalysisCommentsDialog`, backed by a new `NotesService` (`/notes/link-analysis-view/{id}/comments`). D10 is now fully shipped end-to-end. | `okf/frontend/features/link-analysis.md` |
| D11 | **Conservative fixed per-instance cap + spill, on by default.** ⚠ **NOT IMPLEMENTED — deliberately declined by the operator 2026-07-25** in favour of "spill routing only". No default `processing.duckdb.memory_limit` ships, so **the overcommit exposure this decision existed to close is still open**: each concurrent run still gets DuckDB's ~80%-of-RAM-per-instance default. Spill *routing* already shipped independently (`BatchIngestStrategy.scratchDir` → `dirs.temp` on the data volume), and `max_temp_directory_size` has no fixed default because none is defensible without knowing the volume size (DuckDB uses ~90% of disk). **Reopen with a measured value** — see §6 | `okf/…/duckdb.md` |
| D12 | **Chunking on with a large threshold.** **SHIPPED 2026-07-25** at **8 GiB** (`processing.chunking.max_file_bytes`, was `0`/disabled) — far above routine inputs so normal workloads never change shape. ⚠ It was meant to land *after* D11, because a memory cap turns the failure mode into "spill" and makes a high threshold safe. D11 was declined, so **chunking is now the only bound on a pathological single file** | `okf/…/duckdb.md` |
| D13 | **Confirmed parked** — stays gated on a real onboarding-observation session (interview #2). An engineering placeholder would bake in an arbitrary answer that is expensive to unwind once forms ship | §7 · interview #2 |
| D14 | **Ratified with one tightening — SHIPPED 2026-07-25 (`2b1e7e9d`).** ⚠ `canConfigureAccess` + `canApproveShares` were **already** admin/super-only in `Roles.SEED`, so the "bootstrap deadlock left them over-granted" premise was unfounded. `canAuthorAlertRules`/`canRequestShares` ratified as developer/ops-tier. `canOfferDatasets` moved to admin (cross-space data exposure with no second gate) | `okf/…/auth-security.md` |
| D15 | **Withdrawn, not answered — there is no vendor of record.** The IdP/gateway is a per-client deployment choice; standards-only and configurable. Litmus test: new auth code that can't be pointed at a different compliant IdP by config alone is wrong. **Both residuals SHIPPED 2026-07-25**: `KeycloakTokenRelay` → **`OidcTokenRelay`** (incl. the `META-INF/services` entry), and the Keycloak-shaped `tokenEndpoint` default deleted. ⚠ **BREAKING for existing deployments** — `-Dauth.oidc.tokenEndpoint` is now **required** and fails fast at startup; it is no longer derived from the issuer, so a Keycloak deployment that relied on the derived path will not boot until the flag is set from the provider's `/.well-known/openid-configuration`. `X-JWT-Assertion` default kept, now documented as *a* convention | `okf/…/auth-security.md` |
| D16 | ~~A dedicated system Space owns the domain-seeded pattern packs~~ — **OVERTURNED 2026-07-26 (operator): per-Space forking is acceptable.** The central-fix rationale (a system Space so a fix to a shipped pattern reaches every copy) was weighed and dropped; packs are ordinary per-Space `pattern-pack` components. ⚠ Two costs of the system-Space shape are recorded in the plan so it is not re-proposed blind: a `_`-sentinel dir holding `config/` passes `SpaceManager.discover`'s filter and then dies in `SpaceId.of` (a spurious `Skipping space dir` WARN every boot), and a sentinel without `config/` can't be reached through `/spaces/{id}/…` at all ⇒ a dedicated cross-space read route. **✅ SHIPPED end-to-end 2026-07-26** — the kind (2 registrations, no new endpoint or capability), 18 seed files across the three tracked spaces, and the toolbox reading `GET /components/pattern-pack` with the `PATTERN_PACKS` const as the fallback. ⚠ Load-bearing: a kind absent from `WRITABLE_TYPES` is **unreadable**, not read-only; a step's start `direction` persists as the **empty string** because TOON cannot encode `{}` in a list; `patternPacks` must be a **signal** (`OnPush`); and `spaces/uat/` is gitignored so it stays on the fallback. Plan archived | `okf/frontend/features/link-analysis.md` |
| D17 | **Open, unscheduled** — acknowledged gap, no demand pressure, no build time committed | §7 |

## 3. Product remainder (MoSCoW of record: `REQUIREMENTS.md` §5)

| ID | Item | Status / blocker |
|---|---|---|
| OPS-5 | Provenance conservation on live data (built, off by default) | **Live-feed soak only — no code left.** Offline de-risk + the imbalance→`NotificationRule` question both closed 2026-07-22/23. `docs/ops/provenance-conservation-verification.md` |
| NFR-7 | Compliance certifications | PARTIAL (not started) — **UNGATED 2026-07-25 (D1): runs in parallel, C1 is not a predecessor.** Real remaining constraints are the Type II observation window (needs CC6 live — it is) and external-party pacing. `superpower/compliance-certifications-plan.md` |
| EOI-7b | Publish eoiagent `0.1.0` artifacts to a registry | Infra/product call; CI rebuilds from tag meanwhile |
| AGT-6a | **AI behind every screen** — inline authoring on every console pane | **A1–A4 SHIPPED 2026-07-26; A4's status half + one pane open.** `<inspecto-ai-assist>` adopted on Pipelines, Queries and the Expectation form dialog; D1–D4 + D8 answered. ⚠ **Two plan premises were WRONG:** the L1 tools had **no invocable route** (⇒ new `POST /agent/tools/{name}`, so "no new backend capability" was false), and four of the five take **structured** input, not NL (⇒ deterministic-derive first). As-built: `okf/frontend/features/inline-ai-authoring.md`. Open: the three rows below |
| AGT-6a · kpi host | `kpi_report_builder` had **no host pane** | **✅ SHIPPED end-to-end 2026-07-28 ⇒ AGT-6a is COMPLETE** (this was its last open row). Host = **`studio/dashboards/dashboard-editor`** as a `prompting` `<inspecto-ai-assist>` adopter + `applyKpiReport()`. ⚠ **This row's premise was WRONG twice.** (1) "Needs a new flow, not an adoption" — no third pane was needed. (2) The deciding factor is **not** "which pane holds operator-built measures": with this tool **the tool builds the measures**, so the pane only supplies a dataset. The real question is *which pane can perform the multi-component write and still end in a reviewable unsaved state* — and `explore` cannot: it is single-widget **by construction** (one controls signal, one dialog, one `save()`, then it routes away), so its `ChannelValue.agg`≡`AGG_CANON` match is irrelevant. `dashboard-editor` already holds a `DashboardTile[]` whose element type **is** the tool's `{widgetId, span}` verbatim, already injects both `WidgetsService` and `DashboardsService`, and already loads `datasetsApi.list()`. ⚠ **`[args]` is `aiKpiArgs()` — identity/context ONLY (`dataset` + `title`)**; pane args are applied after the model's and win, so measures/`groupBy`/`filter` must never appear there or the derived value is silently overwritten (same trap as Queries' `aiQueryArgs`/`aiPromptArgs` split). ⚠ **Partial-failure call:** the widget creates are N non-atomic POSTs (no batch route exists); on any failure the pane **stops, does not tile, and names the created widgets**. Broken tiles are worse than no dashboard, and compensating deletes are unsafe — on an id collision the "orphan" may be a pre-existing widget someone else owns. ⚠ **`AiDraft.prerequisites` is still display-only** — nothing generic applies them and `configDiff` ignores them, so the reviewed diff shows the **dashboard only**; the widgets are invisible to review (§6). ⚠ **The mock was divergent in THREE ways and had ZERO coverage** — widget drafts were `{kind,title,measures}` not `{vizType,datasetId,controls,options.title}`, tiles were an x/y/w/h grid rect not `{widgetId,span}`, and the `groupBy` branch was **absent**; an adopter built on it would have saved empty widgets and untileable dashboards while looking correct offline. Now pinned against `InspectoTools.widgetDraft`/`tile`. UI suite 1790 green, production build green | `okf/frontend/features/inline-ai-authoring.md` |
| AGT-6a · A4 | Read-only "explain this screen" on the remaining panes | **✅ VOCABULARY HALF SHIPPED 2026-07-26** — `<inspecto-ai-explain>` (icon button + dialog) on **11 panes / 12 routes**, resolving each pane-declared canonical term through `glossary_lookup` with a `docs_search` citation fallback. **Backend: none needed**, as this row predicted. ⚠ **The row was one item but the work is two**: "why is this red" is a *different* affordance (`status_get`/`signal_timeline`/`timeline_build`), needs real entity ids, and is therefore operational-panes-only — **not** a breadth win. Operator call: vocabulary first; the status half is the row below. ⚠ Not a mode of `<inspecto-ai-assist>` and **not gated on `canAuthorWorkbench`** (no write path; Business lens needs it most). → `okf/frontend/features/inline-ai-authoring.md` |
| AGT-6a · A4-status | "Why is this red" — the status half of A4 | **✅ SHIPPED 2026-07-26** (surface + reference adoption). `<inspecto-ai-status>` over `status_get` / `signal_timeline` / `timeline_build` — all non-mutating, so **no new backend**, as predicted. Picks the **exact causal chain** when the pane has a `correlationId`, a focused window otherwise; the status and timeline halves **degrade independently**. Adopted on the **Alerts** fired-alert grid (a fired Alert *is* the red thing; `FiredAlert` carries `pipeline` but **no `correlationId`**, hence the window path). Ungated, like its vocabulary sibling. ⚠ Offline it answers from the **mock store's own ledger**, never a canned shape — an empty ledger honestly says "nothing was recorded". ⚠ A `[rowActions]` column on a wide grid is **horizontally virtualized out of view** — needed `[pinActions]`. **✅ CLOSED 2026-07-26 — all four named panes adopted**: + Processing Status (`RunStatus.pipeline`, window), Events/signals (`correlationId` ?? `pipeline`, **chain** when present, action hidden on a row with neither), Incidents/Cases (`object-detail` header, chain only, **no status half** — an Incident has no pipeline). ⚠ Offline the chain path is only exercisable from Incidents: no mock producer sets a `correlationId` on an event row | `okf/frontend/features/inline-ai-authoring.md` |
| AGT-6a · A5 | True **natural-language** authoring | **✅ COMPLETE 2026-07-27 — A5.1 + A5.2 + A5.3 all shipped end-to-end.** A5.3 (`pipeline_author`) reuses A5.2's loop via a `REPAIRABLE` tool→argument map; ⚠ the plan budgeted a *hop* on the wrong premise that graph errors cannot be fed back (`PipelineValidator` has always emitted coded issues — the tool just was not reporting them), and it uncovered that the A2 Pipelines adoption never worked against a real backend (flat args vs `flow`; a name string vs the graph), masked by an over-lenient mock. `flow`'s schema is hand-written since D9 cannot reach an IR, with node `type` from the live `PipelineNodeTypes` registry and `rel` left open for `route:<key>`. Only the separate `kpi_report_builder` host row remains under AGT-6a. History follows. **A5.1 SHIPPED end-to-end 2026-07-27** — `POST /agent/tools/{name}/derive` (single-turn, single-offered-tool, arguments-only) + `<inspecto-ai-assist>`'s opt-in `prompting` mode, adopted on **Queries only** (plan D10). ⚠ The merge is **schema-keyed, not `putAll`** (a model-emitted `sql`/`text` key is dropped, pinned by a test) and **pane args win**, so an adopter must pass **identity fields only** — Queries needed a separate `aiPromptArgs()` because `aiQueryArgs()`'s `when` would have overwritten the derived condition and silently no-opped. ⚠ Three distinct failures: no model **503** (not 422 — the sentence is not the problem) · `_raw` **422** · no tool call **422, different message**; the 503 sets `noModel`, NOT `unavailable`, so the deterministic affordance on the same pane keeps working. ⚠ The derive route must be registered **before** the greedy `/agent/tools/(.+)`. → `okf/frontend/features/inline-ai-authoring.md`. **Open: A5.2** (`component_draft`, a bounded repair LOOP, turn cap 3 per plan D11) **and A5.3** (`pipeline_author`). Original scoping follows. **SCOPED 2026-07-26. ⚠ Plan D9 is CLOSED 2026-07-27: the `ConfigSpec`→JSON-Schema projection SHIPPED** — `ConfigJsonSchema` (`inspecto-config`) + the `config_schema` L1 read tool (23 tools now). F3's "designed years ago and never wired" is wired. ⚠ It is deliberately a **separate tool, not a tighter `component_draft` schema** — `component_draft` is a validator and must be able to receive a malformed draft to report findings on it. ⚠ `query_author.when` and `pipeline_author.flow` are **still bare** and are NOT covered: neither is `ConfigSpec`-shaped, so they need hand-written schemas with A5.1/A5.3. → `okf/frontend/features/inline-ai-authoring.md`. Full scope: `superpower/agt-6-plan.md` **§3.4**. Shape: one new non-mutating `POST /agent/tools/{name}/derive` doing a **single-turn, single-tool, schema-constrained** model call to produce the tool's *args*, then the same deterministic invoke A1 already does — so `AgentAskResult` is never involved. Four findings changed the estimate: (F1) the transport **already** does native function-calling (`ToolSpec.jsonSchema` → LangChain4j `ToolSpecification`), so ⚠ **do not build this as prompt-then-scrape** — `Investigator`'s JSON-scrape is the wrong precedent; (F2) but every **nested** payload schema is `{"type":"object"}` (`component_draft.config`, `query_author.when`, `pipeline_author.flow`) — the envelope is constrained, the payload is prose-guided, and **that is the cost centre**; (F3) the fix is already designed and never wired — `FieldSpec`'s Javadoc names "LLM grammar-constrained generation" as one of its drivers, so project JSON Schema from `ConfigSpecs.forType(kind)` and constrain the model with the spec that judges it (**D9**); (F4) with no local model, `GatewayFactory` returns a stub answering **prose with no tool call**, so a naive hop reports "could not understand you" when the truth is "no model configured" ⇒ must be **503** (which the surface already latches). Phasing: A5.1 `query_author` (M) · A5.2 the projection + a **bounded repair loop** for `component_draft` (L — ⚠ it is a *loop*, not a hop) · A5.3 `pipeline_author`. `suggest_expectations` **excluded** (the pane already supplies `table`+`column`; NL buys nothing); `kpi_report_builder` blocked on its host row. NL is a **mode of `<inspecto-ai-assist>`, not a fourth sibling** — all four of its properties apply. ⚠ Traps: the greedy `POST /agent/tools/(.+)` must be registered **after** the derive route; two unrelated `ToolSpec` types exist (use `com.eoiagent.core`, not `inspecto-agent`'s kernel record); `/agent/tools/{name}` enforces **no** `Role`/`Capability` (it bypasses `DefaultToolRegistry`) and the derive route must inherit that exactly, not add a half-gate. ⚠ A5 **amends D3's reasoning** — it reintroduces local inference, so "not even an inference cost" no longer holds (the conclusion, no edition gate, stands) |
| AGT-6a · schema kind | `component_draft(kind='schema')` validated the **wrong** `schema` — the A5.2 Components adoption was broken against every real backend | **✅ FIXED 2026-07-27.** `ConfigSpecs.schemaComponent()` (the registry component's column list) + `InspectoTools.specFor(kind)`, which routes `schema` there while `ConfigSpecs.forType` keeps the config reading for `/validate`. `config_schema` shared the resolution and so advertised the wrong shape to the model — the defect was **two tools, not one**. ⚠ **The design call recorded below rested on a FALSE premise; do not restore it.** "`ConfigSpecs.TYPES` and `WRITABLE_TYPES` are disjoint vocabularies sharing three words" is wrong: checked against the shipped sample Space, `ConfigSpecs.widget()` and `dashboard()` describe `registry/widgets/*.toon` and `registry/dashboards/*.toon` **accurately** — **`schema` is the one overloaded word**, and a blanket "registry kinds have no `ConfigSpec`" reroute would have broken two working kinds (pinned by `widgetAndDashboardKeepResolvingThroughTheirConfigSpecs`). ⚠ A fieldless draft stays an ERROR by cross-field rule as well as required field: `applySchemaDraft` discards one, so `clean` there would mean a dead Apply button. Mock mirrors both, closing the same latent leniency on `dashboard.tiles`. Reactor 2334 green. History follows. **Found 2026-07-27 by the cross-adopter audit (`feb6f6e7`).** The word names two unrelated things: a **registry component** (`ComponentStore.WRITABLE_TYPES`; content a bare `{fields:[{name,type,format?}]}` — what `component-form.dialog.ts` authors and what `applySchemaDraft` reads back) and the **TOON schema config** (`ConfigSpecs.schema()`; `raw.name` **required**, `raw.format`, `raw.fields`, `mapping.canonicalName`). `component_draft` resolves the latter via `configType(kind)`. ⇒ the pane's draft **always** draws *"Missing required field 'raw.name'"*, and the A5.2 repair loop then pushes the model toward `{raw:{name,…,fields}}` — a draft `applySchemaDraft` cannot read, so Apply silently no-ops. Both outcomes are broken: a spurious ERROR the operator can't act on, or a dead button. ⚠ Hidden for two slices by a mock that validated nothing; now pinned as a failing-shape test in `agent.handler.spec.ts`. Two ways out: **(a)** reshape what the pane drafts (emit `{raw:{name,fields}}`, read `raw.fields` back) — but then the draft is not the registry content the pane saves; **(b)** accept that **registry component types have no `ConfigSpec` at all** and A5.2 needs a different validator. (b) looks right — `ConfigSpecs.TYPES` and `ComponentStore.WRITABLE_TYPES` are disjoint vocabularies that happen to share three words. → `okf/frontend/features/inline-ai-authoring.md` |
| AGT-6a · tool `args` validation | Nothing validates a tool call's `args` against the tool's declared `jsonSchema` | **✅ ANSWERED 2026-07-28 — runtime validation DECLINED, a contract test shipped instead** (`ToolSchemaAdopterContractTest`). The gap is real: `AgentRoutes` passes `mapField(body.get("args"))` verbatim and `InspectoIntelligenceAgent.runTool` invokes with it directly. But ⚠ **a validator would not have caught either audit defect, and would have broken a working feature**: both were the *schema* being wrong while the code and the caller were right, so validating `projection_author.columns` against its objects-only `items` would have **rejected the link-analysis panel's legitimate `string[]`**. The schema is what was out of step with reality, so a test holds it against reality — a red there is a documentation bug, a red in production would not be. The test pins each real `<inspecto-ai-assist>` adopter's payload against its tool's declared property types (8 payloads / 6 tools; `kpi_report_builder` has no adopter). ⚠ It deliberately does **not** check `required`: the NL variants of `query_author`/`pipeline_author` omit `when`/`flow` on purpose so the model's derived args survive the merge, so an absent key is legal by design. **Keep the table in step when a pane's `[args]` changes.** Revisit runtime validation only after all 23 schemas have been audited — until then every schema inaccuracy would become a live 422 |
| AGT-6a · projection schema | `projection_author`'s declared `columns.items` is stale | **✅ FIXED 2026-07-28** — now `{"type":["object","string"]}`, the two spellings `columnNames()` has always read. Pinned twice: a schema assertion in `projectionAuthorFallsBackToIdShapedColumnsThenRefusesToGuess`, and the new cross-adopter contract test below that would have caught it. History follows. **Found 2026-07-27 with the row above.** The schema says `{"items":{"type":"object"}}`; the link-analysis panel sends a `string[]`. `columnNames` accepts both **deliberately** (documented at the tool) and so does the mock, and nothing validates `args` against `jsonSchema` anyway — so nothing breaks on `POST /agent/tools/{name}`. It is wrong only where the schema is actually read: the model, on the `ask` path. Fix = `{"type":["object","string"]}` or drop the `items` constraint. ⚠ Do **not** "fix" it by making the pane send objects — the pane's column list is the right shape and the tool's doc-string promises both |
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
| **Bundle / Exchange** | ~~missing kind `connection`~~ **DONE 2026-07-25 (D2: reference-only, secrets stripped)** — the `BundleRoutes` kind set is now complete · ~~widen the Exchange `kind` axis to carry saved views (**§2 D9**)~~ **DONE 2026-07-26** — `link-analysis-view` is a first-class Exchange kind, live-mode only, closure over every dataset it reads; as-built in the OKF doc · `requires` present-but-different classification · per-editor "load as draft" import — **not a small buildable**: `BundleTransferService.write` commits straight through each store, there is no generic draft seam, and editors open by route/id (not injected content); design-first, likely multi-session, **do not fake it with a cross-kind `enabled:false` stamp** | `okf/backend/control-plane/exchange-sharing.md` |
| **Job framework** | ~~MNT-14 archived-Incident sweep~~ **SHIPPED 2026-07-27** as `incident_purge` (D5's retention tier, enforced; dry-run-first, legal-hold exemption, cascade to notes/links/tag edges). ~~Residuals — operator retention docs carrying the G3 stance, and no scheduled instance~~ **BOTH CLOSED 2026-07-27**: `okf/backend/build-run/operations-reference.md` now carries a *Retention & purging* section — a what-each-task-forgets table, a worked `incident_purge` job TOON, the dry-run-first + legal-hold procedure, and the G3 stance stated for a legal/DPA reader. **"No scheduled instance" was resolved as the decision, not the gap**: nothing schedules it and nothing should — shipping a default that deletes business records is indefensible, so standing up the job is an operator act like `receipt_prune`, and that is now written down · maintenance COULD tier: space-to-space comparison · predictive maintenance (AGT-5/self-healing territory, deliberately deferred) · **D6 reclassified 2026-08-04 — a scheduling feature, NOT the missing UI surface it was filed as:** `meta.domain.timezone` is parsed (`SemanticModel.DomainNotes`) and served (`CatalogRoutes` → `/catalog/kpis`, plus `ExplainEntitySkill`) but **never behaviourally consumed** — the two places that actually need a zone, `PipelineScheduler:117` (cron/trigger firing) and `JobService:205` (feeding `ExpressionContext.zone`, i.e. `$today`/`$yesterday`/`$day(-1)` — the record was `ParameterResolver.Context` until `74989b3c`), both hardcode `ZoneId.systemDefault()` and never read it. ⛔ **Do not close this by adding a timezone picker** — a control implying it governs date math when it does not is worse than the gap. The real buildable is making a configured zone govern cron firing + date-macro substitution, which is a **behaviour change on existing schedules** (every `$today` job shifts) and so needs operator sign-off and a migration story, not a form field. Note the key is also unvalidated — any string is accepted, no `ZoneId.of` check · **EXPR-1 (new 2026-08-07): Expression interpolation inside a longer string is deferred, deliberately.** Step 4 of `superpower/job-parameter-contract-plan.md` settled on **whole-value** evaluation, so `Daily report for $yesterday` in a Subject field stays literal (the plan's own §9 example, now corrected). Blanket interpolation is **refuted, not merely unbuilt**: a `sql.template` body's `$name` tokens are that Job's template parameters, so interpolating them would capture the namespace *and*, under the fail-closed unknown-token rule, REJECT every existing `sql.template` Job — `spaces/demo/config/jobs/orders_summary_sql_job.toon` is a live instance. If interpolation is ever wanted it must be **per-declaration opt-in** (the `expressions` component is the natural carrier), never global | `okf/backend/control-plane/jobs.md` |
| **Collector config** | Unification SHIPPED 2026-08-04 (one `<inspecto-collector-config>` for both hosts, `POST /config/patch`, `transform.dedup.fingerprint` removed). Open: **a Connection-mode save is refused when the Connection list cannot load** — `resolveConnector` needs the profile to derive `connector`, so an unreachable `ConnectionsService` turns an unchanged, previously-valid node into "not a saved Connection". Affects BOTH hosts identically (it predates the unification on the Onboarding side). The likely fix is to keep the stored `connector` when the picked id is unchanged **and** the list failed to load — but that must not weaken the ghost-id guard, so it needs a deliberate "list failed" vs "list says no" distinction, which `connectionOptionLoader`'s degrade-to-empty contract does not currently give · `<inspecto-collector-config>` is deliberately **not** in the `/design` gallery — it is a domain composite needing `ConnectionsService`, like `<inspecto-enrichment-editor>`, and the gallery holds primitives | `okf/frontend/features/collector-config.md` |
| **Grammar config** | Unification SHIPPED 2026-08-04 (one `<inspecto-grammar-editor>` for both hosts, `ParserConfigDialog` → `GrammarEditorDialog`, inline `parsing:` by default with explicit extract to a `grammar` component). Plan archived to [`archived-documents/plans-archive/grammar-config-unification.md`](archived-documents/plans-archive/grammar-config-unification.md). ~~Open: **the unknown-`use:`-prefix refusal is still unbuilt**~~ **SHIPPED 2026-08-10** — a `use:` binding whose kind is valid but whose NAMED component does not exist is now refused at save with `UNKNOWN_USE_REF` (422), pinned over real HTTP in `ControlApiPipelineCrudTest`. ⚠ **Two of this row's own claims were wrong, checked against the code before building:** (1) `ComponentRegistry.resolve` **does** have a main caller (`ComponentStore.get`) — only `isKnown` had none; and (2) the seam is **NOT `ConfigSafetyValidator`**, which switches on `pipeline`/`enrichment` `.toon` safety only and never sees an authored graph. The real seam was `PipelineValidator.checkWiring`, which already checked the `use:` KIND prefix via `ComponentRegistry.isComponentType` but never the named target. As built: a `validate(g, ComponentRegistry)` overload (the registry-less `validate(g)` is unchanged, so `PipelineExecutor.validateOrThrow` and `InspectoTools` keep their behaviour), and `PipelineRoutes` passes the registry it already scans for dry-run. ⚠ The check is **skipped when the space has no write root** — `componentRegistry()` yields an EMPTY registry there, against which every binding would look dangling, so a read-only draft-validate must not invent refusals. A bad kind reports once, not twice. **Still open, and BIGGER than this row was:** `PipelineJobRunner` (real execution) never calls `ComponentRegistry.effectiveGraph`/`effectiveConfig` at all — only the dry-run route does (`PipelineRoutes:1104`), so a `use:` binding is resolved for PREVIEW but not for an actual run; `ComponentRegistry`'s own javadoc warns about this. Save-time refusal narrows the blast radius but does not close it · **live browser smoke run 2026-08-05 found and FIXED a real backend bug, unrelated to the grammar editor itself**: `PipelineRoutes.saveGraph` (`PUT /pipelines/{name}/graph`) hardcoded its write target to `<name>_pipeline.toon` at the config root instead of resolving the pipeline's actually-registered file via `CollectorService.pathFor`. For any pipeline living at a legacy/non-canonical path (e.g. `subscriber/subscriber_pipeline.toon`), a save silently created a **second, shadow file** — the UI toasted "saved" and the new file had the edit, but the live pipeline (still bound to its original file) kept serving stale content until a restart, at which point the space-loader found two files claiming the same pipeline id and **the whole space failed to boot** (`SpaceManager.bootQuietly` swallows the conflict into a WARN and drops the space entirely, so `GET /spaces` silently lost an entry with no user-facing error). What looked in the browser like "the extracted `grammar:` binding doesn't survive a reload" was this file split-brain, not a dialog hydration bug — the dialog's `use:` read-back logic was already correct. **Fixed**: `saveGraph` now resolves the target via `pathFor(name)` when the pipeline is already registered, falling back to the canonical `<name>_pipeline.toon` only for a genuinely new pipeline. Verified via direct API round-trip (inline edit and extract-to-grammar both write to the original file, no duplicate created, GET after re-registration shows the correct `parsing:`/`use:` shape) — full reactor test run pending. `grammar-editor.dialog.spec.ts`'s "bound-edit round-trip" case is same-session only; a `PipelineRoutes`-level regression test asserting a save to a non-canonically-named pipeline overwrites the ORIGINAL file (not a new one) would have caught this — worth adding | `okf/frontend/features/grammar-config.md` |
| **Queries / BI** | `graph`/`spatial`/`search`/`api` QueryTypes · more `$`-resolvers | `okf/backend/control-plane/queries.md` |
| **Notifications** | ~~delivery-status webhooks~~ **SHIPPED 2026-07-26 (D8)** — inbound provider callbacks, SPI + SendGrid/HMAC adapters + fail-closed route; residuals (suppression policy, soft-bounce retry, SES/SNS, receipt pruning) in §6 · GeoIP · auth-gated per-user prefs / security triggers | `okf/backend/control-plane/events-metrics.md` |
| **Signal / Decision networks** | optional S8 (connector-direct emission + cross-space controller) · a general **event-triggered consequence policy gate** (still `/apply`-only) · RFC 6902 JSON Patch state deltas for AG-UI (no consumer yet) · ⚠ **no producer threads `causationId`**, so `/signals/tree` is flat today | `okf/backend/control-plane/signal-backbone.md` · `okf/backend/control-plane/decision-rules.md` |
| **Geo map** | DuckDB **`spatial` extension** — deliberately deferred: plain SQL covers today's projection/aggregation, and loading it means bypassing the hardened `SqlSandbox` extension lockdown **and** bundling a per-platform native binary for offline installs. Only worth it once a real geometry op (ST_Distance/ST_Contains, spatial join) is demanded. *(Progressive loading + worker binning CLOSED 2026-07-24 as obsoleted by the server-side fold + the hard `GEO_POINT_CAP = 5000`. Revisit ONLY if that cap is raised — the candidate then is worker-izing the O(n²) toolbox analyses, not binning.)* | `okf/frontend/features/geo-map.md` |
| **Consignment ELT (`consignment_outputs`)** | Plan §11.3 + §14 SHIPPED 2026-08-04 (registry, all three write paths, state mutators, the ledger/manifest `batch_id` → `consignment_id` rename, and the `ProcessorContext` SPI). **Three `batch_id` renames were deliberately deferred, each its own decision:** (1) the **DDL columns** in `DbProvenanceStore` and `DbStatusStore` — needs real `ALTER TABLE … RENAME COLUMN` against existing `.duckdb` files, and `DbStatusStore`'s `payload` blob embeds the literal too, so it is a data migration and not a text change; (2) **`__batch_id`**, the data-plane system column materialised **into output Parquet/CSV** — ⛔ accept-both-on-read is **impossible** here (files already written carry the old name), so this is a user-visible output-schema break needing operator sign-off, not polish; (3) `batch_id` as a **`.toon` config key** (`PipelineJobRunner:77/136`) — renaming breaks operators' existing pipeline configs, so it belongs to the config-key contract. §7.2/§7.3's summary tier also SHIPPED 2026-08-04 (`SummaryWriter`: one Parquet file per (Consignment × record-day), a `_measures.csv` composability sidecar, registered under `<target>__summary`). Also open: **§7.4's rollup cache** — deliberately not built, since it is a cache deletable without data loss and read-time aggregation over partials has not been shown too slow; building it adds the one mutable-looking thing in the system, so wait for evidence · **§7.3's unpartitioned fallback** — a target whose rows omit `record_day` is written flat, which is the glob-everything read §7.3 exists to fix; an operator call taken against advice, revisit if flat summary targets appear in practice · **§7.5's histogram-vs-sketch question**, whose premise ("DuckDB `approx_quantile` exposes no serializable mergeable state") is ⚠ **still unverified** — check before betting either way · `generation` is on the registry but compaction does not yet stage generations · `run_id` is `null` everywhere (no path has a Run identity distinct from its unit of work) | `okf/backend/engine/db-layer.md` §3.9 · `okf/backend/control-plane/jobs.md` |
| **Consignment addressing** | **Plan DELIVERED 2026-08-10** — steps 1–7 and 10 shipped, archived to [`archived-documents/plans-archive/consignment-addressing-plan.md`](archived-documents/plans-archive/consignment-addressing-plan.md); as-built in the OKF concept. **Step 8 is CLOSED as specified — decision settled AND its replacement SHIPPED 2026-08-10 as [`superpower/job-parameter-contract-plan.md`](superpower/job-parameter-contract-plan.md) §5-B + delivery step 17** (that plan's §2 owns the `$upstream` attr set); as-built in the OKF concept §5-A. The scope question as posed — Run artifact vs Consignment-scoped accessor — had a **false premise**: both store a value nothing can consume. The real grammar is `$upstream(<job>).artifact(<name>).time_range` (never the short `$upstream(job).time_range` this row and the plan both used); its `"<min>..<max>"` format is fixed only by a test fixture; `SqlParamScanner.substitute` wraps the whole string in one SQL literal and **nothing splits on `..`**; `ParameterResolver.matchesType` rejects it for the `DATE`/`INSTANT` params that would want it; and repo-wide it has **zero live consumers** (no config, no UI, no guide). Shipped replacement: the attr is gone (as is `RunArtifact.timeRange`), replaced by two scalars `event_time_min`/`event_time_max` resolved **live** via a new `DbConsignmentOutputStore.bounds(table)` folding min/max over the `<> 'SUPERSEDED'` predicate, keyed on the sink `store` name; `PipelineJobRunner` now records one `RunArtifact` per store it wrote (it recorded none before). ⚠ The attrs yield **`STRING`, not `INSTANT`** — the stored bounds are zone-less local date-times, so `Instant.parse` rejects them; they are nonetheless valid SQL timestamp literals, which is the property that matters and is now pinned by a test. ⛔ A **stored** range is rejected — revisions mean a recompute leaves the snapshot describing a superseded revision. ⛔ Do not bridge `RunArtifact.ref` → `table_name` by store→dataset reverse lookup (ambiguous by construction); of the 4 existing recorders, 3 use synthetic catalog labels and **none writes registry rows at all**. Ingest stays out structurally (no `JobContext` in `com.gamma.inspector`); a Consignment-scoped accessor for it is deferred until a consumer demands one. ⛔ **Step 9 (late-arrival segregation) is REFUTED, not deferred** — a date partition is `DATE_YEAR/MONTH/DAY` computed *from* the event-time source and `PartitionWriter.reveal` collapses each partition to one file, so a late record already lands in its own partition/file with its own tight bounds; there is nothing to route and no index being poisoned, and where bounds genuinely are wide (unpartitioned stores, non-date partitions) routing would write to a partition nobody declared. The actionable half was `event_time_spread_ms`, shipped in step 3 · ⚠ **`retire_superseded` must be configured by an operator** or every full recompute leaves a complete extra copy on disk permanently (the pre-2026-08-10 overwrite was O(1) disk) · ~~**enrichment and `ConsignmentProcessJobType` still record no producer and no bounds**~~ **PRODUCER SHIPPED 2026-08-10; BOUNDS SPLIT INTO TWO DIFFERENT PROBLEMS.** ⚠ This row's framing was slightly wrong: both writers **already recorded registry rows** — `producer` and `bounds` simply came through null, so this was two columns to fill, not a missing write path. Producer is now wired for both: enrichment records `cfg.name()` (its own identity, on the main **and** the routed/quarantine write — a `dest` can be written by more than one enrichment, so `dest` is not the producer), and `ConsignmentProcessJobType` records the **processor id** threaded through a new `SummaryWriter.write(…, producer)` parameter (not the Job Type — two processors can summarise one target, and `producerHighWater` groups by producer). Both pinned, including end-to-end that the resolved id is the id that reaches the row. **Bounds remains open, and the two halves are NOT the same task:** (i) **enrichment needs a config-schema decision** — computing bounds needs an event-time column the enrichment can *declare*, and `EnrichmentConfig.Output.partitions` is a bare `List<String>` with no home for the sink's `source:` key; giving it the map-entry form `SinkPartitions` reads is a **public `.toon` schema change** and so belongs to the config-key contract, not to a wiring commit. Once declared, the fold itself is `ConsignmentOutputs.boundsByPartition` over `__enriched` — copy-paste from `PartitionSinkWriter.boundsFor`. (ii) ⛔ **`ConsignmentProcessJobType` bounds is a DESIGN CALL, possibly a won't-do** — a `SummaryRow` is pre-aggregated over an author-chosen `keys` grain with **no enforced time key** (the unpartitioned fallback exists precisely for rows with no `record_day`), so there is no per-row timestamp to fold and no declaration seam to read one from. Either the `ProcessorContext`/`SummaryEmitter` SPI grows a "which key is event time" contract — affecting every third-party processor — or this Job Type is accepted as out of scope for the per-stream watermark because its output is not a stream of raw events. ⛔ Do **not** close it by folding the `record_day` key into a range: that describes the *grain*, not the events, and a wrong watermark is worse than the null a reader correctly treats as "cannot prune". Pinned as a deliberate null by `SummaryWriterTest#boundsStayNullBecauseASummaryRowHasNoEventTime` · **four glob sites remain unwired** (`EnrichmentEngine` ×2, `BatchIngestStrategy`, `ReferenceCompactor`) — all read *ingest* stores so step 6 does not endanger them; `PipelineJobRunner.deriveViewSql` is the awkward one, since its SQL is **persisted and executed later**, so it needs filtering at read time rather than build time · **torn multi-file reads across a recompute are unfixed** — subtraction cures stale *inclusion*, not a read that starts before a recompute and finishes after; closing it means a reader capturing a file list once and reusing it, at every call site · `generation` remains a **dead field** (always stamped 0, never read) — the revision model uses the batch id instead · ⚠ **`DatasetRelation.temporalColumn` has no caller and cannot safely gain one on a write path** — the store→dataset reverse lookup is ambiguous by construction, so a write path must read a declaration it already holds · a typo'd sink `partitions[].source` silently yields no bounds with no authoring feedback (`ComponentPreview` reads only `column`) | `okf/backend/engine/consignment-addressing.md` |
| **Pipeline identity: rename & templates** | **T1/T2/T4 (label, save-as-template, UI) + T3 (full `rename` migration) all SHIPPED 2026-08-02.** Open: no UI action wired to the full `rename` route yet — the ⋮ menu offers `label` ("Rename…", display-only) and `save-as-template`, but a real identity-changing "change id" action needs its own menu entry, clearly distinguished from `label`'s copy ("the identity stays…"), which is only accurate for the route it names · automated resumability from `rename.journal` (today it's an audit trail an operator reads by hand, not a resume mechanism) | `okf/backend/control-plane/pipeline-identity.md` |
| **Pipeline graph** | ~~**D9**~~ **CLOSED 2026-08-04** — `duplicate__*` moved onto `transform.dedup.fingerprint`, the node `PipelineEditable.lower:255` actually overlays them from, with the pipelines `acquisition` adopter taking a **derivation** of `COLLECTOR_ATTRIBUTES` (never a prune — Onboarding still authors the block whole). Turning dedup **on** works because the node is in `LOWERABLE` and on the palette: `PipelineLift:70` only synthesises it when the policy is already content-based, so a fresh one is added by hand. Positive contracts now in `NodeConfigNameContractTest.contracts()`. The general rule this and D3 established: **a shared attribute table is correct per BLOCK, not per NODE** · ~~**D3-remainder**~~ **CLOSED 2026-08-04** — the acquisition `connection` attribute now writes the binding (`use: connection/<name>`) instead of cfg, where lower stripped it. ⚠ The plan's proposed `bindKindFor('SOURCE') → 'connection'` was **infeasible** and was not used: the component picker calls `GET /components/{kind}` and a Connection is not a `ComponentType` — it has its own service/route. The free-text `use` box is suppressed for acquisition so two controls can't write one field · **D8 (still open, and deliberately NOT a key fix):** `sink.materialized`'s `mode: 'upsert'`/`key_columns` in the case-study seeds have zero Java readers but must **not** be deleted — upsert-by-key is real, just **pipeline-level** (`reference: {load: upsert\|scd2, key: [...]}` + `produces: reference`, `PipelineConfigParser.java:413-429` → `BatchIngestStrategy.java:107-119`). There is **no node-level equivalent to rename to**, and the capability is **unauthorable from any UI**: `reference:` is a top-level block the flat editable graph carries as opaque passthrough and never surfaces, and `sink.materialized` isn't in `PipelineEditable.LOWERABLE` (refused `UNSUPPORTED_NODE` on save). Real buildable = surface the pipeline-level `reference:` block in the editor; until then the seed keys stay as demo narrative, documented in the seed header · **D7 residual (small, unblocked 2026-08-03):** `AttributeSpec` now has a `list` type (`string[]` as chips), so the enrichment `input.partitions`/`output.partitions` lists can finally be specced — they were left unspecced only because no list type existed (`enrichment-attributes.ts`), and they still travel verbatim through a save until someone does it. Does **not** apply to lists of maps (`transform.route`'s `branches`). Separately, `fieldSpecsToAttributes`' `TYPE_MAP` still deliberately skips served `FieldType.LIST` — wiring it up would newly render list controls in the parser dialog / Parsing stage, so it needs its own look · T15 residuals (non-blocking): per-flow TOON override of the back-pressure thresholds (globals only) · flipping the intake cap on by default (needs a soak) · remote-fetch economy (the cap applies post-dedup, so a remote source still materialises its full ready set — unchanged from pre-T15, but a pre-materialise cap would save bandwidth) · mock-only: run-to-here `POST …/run` (path deliberately reserved for the editor's scratch-only contract) · `/asn1/modules` **stays mock-only** — no backend ASN.1 capability exists · **NEW 2026-08-10, opened by the join-verb slice:** (a) **three more single-slot, last-one-wins node kinds** — `recordDedup`, `routeNode` and `summarizeNode` in `PipelineEditable.lower` each keep ONE node and silently discard a second, exactly as `joinNode` did before `MULTI_JOIN`; the join case was guarded because the recipe palette made it reachable. ⚠ **A first pass at this row hedged that "a second route may be meaningful" — that is WRONG, checked against the code:** `lower` writes a single `route` key (`out.put("route", routeSection(g, routeNode))`) and multiple branches live *inside* one route node's section, each stamped with the destination its `route:<key>` edge feeds — so a second route node loses the first exactly as a second join did. All four slots are the same hazard. The reason to generalise carefully is therefore **not** "is a second one meaningful" but that a shared refuse-on-duplicate helper **changes save behaviour for existing graphs**: a pipeline someone already has with two dedup/route/summarize nodes saves today (losing one) and would start being refused. That needs an operator call. ⛔ Do not add a single-use helper for join alone. **⚠ BLAST RADIUS NOW MEASURED 2026-08-10 — the refusal itself is still the operator's call, but it is no longer an unmeasured one.** `PipelineEditableTest` gained a case per slot pinning today's behaviour: the discard is real, it is silent, and the **LAST** node survives in all three (`secondDedupNodeSilentlyDiscardsTheFirst`, `…Route…`, `…Summarize…`). The contrast case `secondJoinNodeRefusesInsteadOfDiscarding` also closed a gap the join slice left — `MULTI_JOIN` had **no engine-level test at all**, only a palette-contract assertion. So "flip the three to refuse" is now a change whose before-state is pinned, and the three tests are what would have to be rewritten to invert. Note the mock (`inspecto-ui/src/app/inspecto/mock/pipeline-editable.ts`) mirrors the same four slots and must move in the same commit as any refusal · ~~(b) **a step card shows the node's CATEGORY, not its type**~~ **SHIPPED 2026-08-10** — a card now captions the type's own label ("Join", "Filter") via a new optional `[typeLabel]` input on `<app-pipeline-step-cards>`, built by `typeLabelMap()` from the served node-type catalog (which already carried `label`; `typeCategoryMap` was throwing it away). A type the catalog gives no label for falls back to the CATEGORY label, so a plugin node degrades to "Transformer" rather than printing a raw `transform.bespoke` at the user. It did **not** "touch every card" as feared: the input is optional and the fallback reproduces the old output exactly, so no existing spec needed changing. ⚠ **Still open from (b): `uniqueNodeId` bakes the type into the id** the card falls back to as a title (`transform_join_1`), so any future retype affordance makes that id lie — untouched here, since nothing retypes a node yet · ⚠ **NEW, found while verifying (b) in the browser: `categoryLabel` renders a GLOSSARY-BANNED word.** `categoryLabel('SOURCE')` returns **"Source"**, which `GLOSSARY.md:150` explicitly bans as the acquisition entity (canonical: **Collector**), and `'SINK'` returns "Writer", which is not the canonical **Sink** either. Serving type labels off the backend catalog removed both from the step cards *incidentally* (they now read "Acquisition" / "Sink (persistent)"), but `categoryLabel` is **still live** at `pipeline-editor.component.ts:1150` (the canvas hover tip), `pipeline-graph.ts` `authoredToG6`, and the step card's own no-label fallback — so the banned word is narrowed, not gone. Fixing it is a **vocabulary decision, not a rename**: the node-type category axis (`SOURCE`/`SINK`) is the engine's `BuiltinNodeType` enum, so the display map is the only safe place to change, and "Collector" vs the served node label "Acquisition" must be reconciled first or the two surfaces disagree | `okf/backend/pipeline-graph/pipeline-graph-design.md` §14 · `okf/frontend/features/pipelines.md` |
| **Acquisition / connections** | the JDBC-based connectors each need their own library-specific proxy wiring · an actual **HTTP CONNECT** handshake for any connector (SOCKS5 is wired for SFTP/FTP/FTPS; HTTP fails closed) | `okf/backend/acquisition/connectors.md` |
| **Incidents / cases** | ~~C3 configurable Findings sections~~ **SHIPPED end-to-end 2026-07-26 (D6)** — `findings-spec` ComponentStore kind + `GET /findings/{type}` + schema-form rendering; residuals in §6 · ⚠ the old `category`/`tags` params row is **superseded** — D7 rescoped tags into a generic cross-entity concept, now shipped (`okf/backend/control-plane/tags.md`); do not build an `attributes.tags` filter — the CSV is a projection, and `GET /tags/{name}/targets` is the cross-kind read. *(Case-analytics dataset SHIPPED 2026-07-25 as the `objects.analytics` Job Type — plan archived, as-built in `okf/backend/control-plane/jobs.md`.)* | `okf/frontend/features/objects.md` · `okf/backend/build-run/operations-reference.md` |
| **Menu builder** | the `canCurateMenus` split is **COMPLETE end-to-end 2026-07-25** — server gate + the UI half (`LensService.canCurateMenus`, the `menus.curate` action node, Menu Builder's `canCurate`, mock seed table). No open items | `okf/backend/editions/auth-security.md` · `archived-documents/plans-archive/menu-builder-plan.md` |
| **Onboarding (Stream/Reference)** | Reference Phase-2 is **COMPLETE** (P0–P4, plan archived). Residual non-blocking deferrals: **D5-ref** — how a `delete` tombstone *enters* the reference store is undefined (the write path always stamps `'upsert'`; the views only *honour* an existing tombstone) — needs a call on the input signal (reserved column? Decision Rule consequence?) when a real delete-feed use case lands · **D6-ref** — within-batch same-key tie-break is arbitrary; add the optional latest-by-`order_by` column only when a batch can legitimately carry ordered same-key versions · optional templates entry (space-template-gallery precedent) · **defer the name to first save** (operator ask 2026-07-27): land on the rail with no create dialog and ask name/description at the first stage save. **⚠ UNBLOCKED 2026-07-28 by a decision that avoids rename entirely — do NOT build `POST /config/rename`.** The original framing (below) was right that a rename is a multi-write migration, and the 2026-07-28 inventory made it worse than recorded: beyond the file name it touches the name-derived **`<pipelineName>_commits.log`** (the durable per-pipeline audit trail, `PipelineConfigParser.java:128`), three in-memory maps in `CollectorService` (registry + scheduler/`forget` + `paused`, `:874-943`), the acquisition ledger's **`sourceId`, which *defaults to* the pipeline name** (`PipelineConfigParser.java:373-377`) and keys dedup/watermark/gap state, and Catalog **Stream** identity (`PipelineConfig.java:685`). ⚠ And the only precedent, `ObjectService.renameTag`, is **itself not atomic** — `TagRoutes.java:207-222` documents its own partial-failure window as a known, unsolved risk. A pipeline rename would inherit that character while risking an orphaned audit trail and a reset dedup history. **DECISION (operator, 2026-07-28): split identity from display name instead.** Identity is *already derived*, not authored — `PipelineConfigParser.java:61` lowercases/underscores `name` into `pipelineName`, and everything downstream keys on that. So give a pipeline a **stable generated id** and make `name` a mutable **display label**: "rename" becomes a one-field edit with **zero migration**, and name-deferral falls out for free. **✅ SLICE 1 SHIPPED 2026-07-28 — the decoupling itself.** An explicit top-level `id:` is now the identity (`ConfigSpecs.pipeline()` declares it optional, pattern `[a-z0-9][a-z0-9_]*`); absent **or blank** it falls back to the old derivation, so **every existing config is byte-identical and this is NOT a data migration** (`PipelineIdentityTest`, 5 tests; full reactor 2359 green with **no** cross-module regression — the risk to watch, since ~140 main-source call sites key on `identity().pipelineName()`). ⚠ A blank `id:` must keep falling back — otherwise a half-authored value keys every downstream artifact on `""`. **STILL OPEN (slices 2–3):** (a) *generate* an id at draft creation and stop writing name-derived ids for new pipelines; (b) the UI name-deferral itself (ask name/description at first stage save); (c) an operator call on whether to **backfill** existing configs with explicit ids — not required for correctness (the fallback covers them) but until then those pipelines still cannot be renamed. **⚠ SUPERSEDED 2026-08-02 — full identity migration SHIPPED as `POST /pipelines/{name}/rename`.** The "do
NOT build `POST /config/rename`" line above was the right call for THIS feature's original narrower scope
(name-deferral needed zero migration, not a full one) — but a separately-scoped later ask *for* a full
rename got the safety analysis this warning demanded: ledger `renameSource`, audit-file/DuckDB-mirror
migration, dependent-config rewrite, fail-closed gates incl. an active/running check, and a
`rename.journal` recording each completed step since steps 2–7 are not one transaction. Works on any
pipeline whether or not it already has an explicit `id` — (c) above is moot now. As-built:
`okf/backend/control-plane/pipeline-identity.md`. Open: no UI action wired to it yet (only `label` and
`save-as-template` are in the ⋮ menu). ⚠ Do not "finish" this by making the parser re-derive identity from a changed `name` — that silently orphans the commit log, the ledger `sourceId` and the Catalog Stream. ⚠ Slice 1 touches only the *pipeline* spec; enrichment/job configs still derive identity from name. ⚠ Do **not** implement name-deferral by holding the draft client-side — the server-held-draft design rejected that deliberately, because it loses shift-handover resumability. | `okf/backend/control-plane/onboarding-authoring.md` · `okf/frontend/features/onboarding.md` |
| **Parsing (Stage-1)** | **The flatten configuration (tree → Tables) — now THE gating slice** (2026-07-30: the parser-plugin framework shipped; `okf/backend/engine/parser-plugins.md`). Two halves, one concept: (a) **JSON flatten DSL (engine)** — ~~nested field selectors~~ **DONE 2026-07-31**, but NOT as a new DSL: the JSON read path already built `json_extract_string("line", '$."<selector>"')` from `raw.fields[].selector`, so nesting belongs at the *selector* layer and `DuckDbCsvIngester.selectorSegments` now splits a selector on unescaped dots — `addr.city` reaches the nested value, in **both** the newline and array/auto branches (the latter now declares a nested selector's root column as `JSON` rather than `VARCHAR` so the projection can walk into it). Deliberately the **same dotted convention `Asn1RecordIngester` uses** — one path notation across ingesters, not a third. ⚠ **Breaking for configs whose JSON has a literal dotted key**: escape it, and in TOON write the backslash **doubled** (`selector: "odd\\.key"`) because TOON's own decoder rejects a bare `\.` as an invalid escape before the engine sees it (found by an end-to-end test, not by inspection). Proven by `JsonPipelineTest.dottedSelectorReachesNestedValuesAndEscapedDotsStayLiteral`, a real DuckDB ingest. ~~**Still open: `records_path` is still locked to `$`**~~ **DONE 2026-07-31** — a nested `records_path` now works, using the SAME dotted convention (`payload.records`, optional `$.` prefix), so the JSON frontend has one path notation at both layers and they compose (`records_path: payload.records` + `selector: "addr.city"`). The SQL was **probed against DuckDB 1.5.2 before being written** (per the earlier refusal to emit unverified SQL): `records_path: "$"` keeps the original `read_json` path byte-for-byte, and only a nested path switches to `read_json_objects` + `unnest(json_extract(…)::JSON[])` (`DuckDbCsvIngester.buildNestedJsonReadSpec`) — `read_json`'s `columns=` map describes the RECORD shape and so cannot also describe the wrapper. ⚠ **The `[*]` wildcard form was tried and REJECTED**: `unnest(json_extract(json,'$.p[*]'))` returns ZERO ROWS when the path is absent, or names an object or a scalar — a mis-authored `records_path` would have ingested nothing and reported success. The `::JSON[]` cast raises `Expected ARRAY, but got OBJECT` instead, and a `json_type(…) IS NULL` guard turns an absent path into a named `json.records_path not found in document` error; an **empty** array stays zero rows, which is the honest answer. A nested path with `format: newline` is **rejected at load** — NDJSON lines are already records, so there is no enclosing document to walk. Proven by 7 new `JsonParsingTest` cases (real DuckDB ingests, incl. the composition and both loud-failure modes); the old `nonRootRecordsPathFailsLoad` became `recordsPathOnNdjsonFailsLoad`. A new `JSON_PATH` transformType was considered and **rejected**: `TransformCompiler`'s `EXPR` already passes a raw DuckDB scalar through, so `json_extract_string(col, '$.a.b')` worked already; a second mechanism would have duplicated it. (b) **the tree→segments ingest bridge for hierarchical `ParserPlugin`s** — XML previews as a record tree today but is honestly `ingestable: false`; the flatten config maps a record tree onto one or more segment schemas, after which a hierarchical parser loads via the existing `StreamingFileIngester` machinery · **ASN.1 adoption prerequisites** (for the ASN shift — its plugin drops into the served catalog with zero UI change): ~~a public bytes→records facade over `ByteSource`/`RecordReader`/`SchemaBinder`/`LegacyTransformEngine`; promote the package-private `asn-golden` `RecordMapper.toMap(NamedNode)` to public API~~ **DONE 2026-07-31** — new `asn-facade` module (`asn-parser/asn-decoders/asn-facade`): `Asn1Decoder` (compile once, `decode`/`decodeToRows`) + a public `RecordMapper`, depending only on asn-core/asn-schema/asn-transform (never asn-golden/legacy-code) · **`Asn1ParserPlugin` SHIPPED 2026-07-31** (`inspecto-engine/src/main/java/com/gamma/parse/Asn1ParserPlugin.java`, registered in `META-INF/services/com.gamma.parse.ParserPlugin`) — ASN.1 now IS served, appearing in the Onboarding Parsing stage and Pipelines Parser dialog with zero UI change; grammar is flat (`asn1.grammar`/`asn1.root_type`/`asn1.strictness`), framing fixed to bare TLVs, preview-only (`ingestable: false`, same as XML); **framing served 2026-07-31** — `asn1.file_header_length` + `asn1.record_header_length` cover every layout in the parity corpus (file header 0/50, record header absent/4, always `skipOnly`), with 0x00/0xFF padding unconditional per legacy `ASN1Utils.readTag`; trailer length + the length-prefix machinery stay unserved deliberately (no corpus file uses them); **grammar-less structural dump SHIPPED 2026-07-31** — `asn1.grammar` is now OPTIONAL for preview: BER is self-describing, so a blank grammar walks the raw TLV forest via `RecordReader` (no schema) and labels nodes by tag (`[APPLICATION 1]`, `[0]`), letting an operator inspect an unknown vendor file *before* obtaining its `.asn` module. ⚠ Preview-only — ingest still requires a grammar (anonymous tags cannot become segment columns); `root_type` without a grammar is a caller error, not a silent fallback. Values render **hex-first** with text as an annotation (`6869 "hi"`), because 0x2A is printable and rendering INTEGER 42 as `"*"` would be a lie dressed as a decoded value; still open: the **remaining half of the declarative decode profile** — the *grammar source*, i.e. a reference to a stored schema module instead of pasted ASN.1 module text (the corpus keeps per-vendor `.asn` files), which is also the prerequisite for giving a per-vendor tx/transform config a home; ~~resolve the `asn-parser-v2:1.2.1` vs `asn-decoders:0.1.0-SNAPSHOT` coordinate split~~ **DONE 2026-08-01** — the root `pom.xml` now aggregates `asn-parser/asn-decoders` (aggregation only; that tree keeps its own parent), so `com.gamma.asn:asn-facade` resolves from the reactor and the manual `mvn install` is gone — verified with `~/.m2/repository/com/gamma/asn` deleted, asn-facade building at [7/23] before inspecto-engine, `mvn -o clean test` green (2178 tests). *(First marked done 2026-07-31, but the `<modules>` edit was never committed and was lost across a shift; re-landed 2026-08-01 — until then a fresh `~/.m2` still needed the manual install.)* `asn-parser/pom.xml` (`asn-parser-v2:1.2.1`) **deleted**: zero consumers, and its parent `com.gamma.asn.decoders:asn-decoders:1.1.3-dev` existed nowhere, so it never built. ⚠ **`asn-parser/src/main/java` is NOT dead and must not be cleaned up as an orphan** — `legacy-code/pom.xml` compiles those 45 files via `<sourceDirectory>../../src/main/java</sourceDirectory>` as the parity baseline; they retire with `legacy-code` after Phase 4. ⚠ Corpus-backed tests (`RealGrammarsTest` asn-schema, `ParityCheckTest` asn-golden) are opt-in AND data-gated (DATA-GOV-1): they `assumeTrue` on both `-Dasn.corpus.tests=true` and the operator data being present, so they SKIP by default and on any corpus-less checkout (worktrees never get the gitignored corpus) — a fresh checkout builds green and a *skipped* corpus test is expected, not a regression; run them where the corpus lives with `mvn test -Dasn.corpus.tests=true` · **drop-in `plugins/` jar directory** (JobPackManager classloader precedent) so a customer parser deploys without a rebuild — today ServiceLoader needs the jar on the server classpath · ~~**segments editor** to unlock guided Save for ingestable custom parsers~~ **SHIPPED 2026-07-31** — `segments-editor.component` in the Parsing stage (NOT a new onboarding stage: stages are static arrays with no runtime-conditional precedent, and the editor needs the preview tree directly above it). Shown only when the selected plugin is `ingestable` **and** names an `ingesterClass` (so XML stays correctly locked out); **Derive from preview** proposes one segment per record type with a column per leaf path; Save writes one schema toon per segment (`ConfigService.write('schema')`, the Schema stage's convention-path idiom) then patches `parsing.plugin`. `GET /parsers` now also serves `ingesterClass` so the UI never hardcodes an implementation class. Bespoke nested `FormArray` by necessity — `FieldSpec` cannot express "a list of segments each with a list of columns" (`ConfigSpecs.schema()` hits the same wall). ~~⚠ **Residual:** `initialSegments` re-hydrates segment *keys* only~~ **CLOSED 2026-07-31** — the pane now reads each referenced schema toon back (`ConfigService.read('schema', …)`, the Schema stage's own call) and rebuilds the columns via `segmentDraftFrom`, the exact inverse of the `schemaDraftFor` that wrote them, so re-editing no longer needs a destructive re-derive. The schema NAME is taken from the stored path's **basename** (`schemaNameFromPath`) rather than recomputed from the segment key, so a hand-authored path or one written under a different space id still re-hydrates. Reads are per-segment and non-fatal (404 silent — an interrupted save may leave a dangling reference; other errors warn), and a late read is DROPPED if the operator has already edited, because the editor's `initial` setter rebuilds the whole `FormArray` and would otherwise silently discard their work. ⚠ Deliberately NOT done: inline segment maps in `PipelineConfigParser` (it hard-casts the segment value to a path `String`) — the write-schema-then-patch-pipeline precedent already exists, and a second config shape is not worth saving one round-trip · *(CLOSED 2026-07-30: the Parser dialog's mock-only caveat — it now runs on `GET /parsers` + `POST /parsers/{id}/preview`; `/asn1/modules` and `/components/grammar/preview` mocks removed with the prototype.)* | `okf/backend/engine/parser-plugins.md` · `okf/frontend/features/onboarding.md` |
| **Onboarding ↔ Pipeline UNIFICATION (design approved 2026-07-31 — REVERSES the 2026-07-30 split)** | **One** model: `*_pipeline.toon`/`PipelineConfig` is canonical because it is what the engine EXECUTES — the Pipelines authoring editor's `*_flow.toon` is not wired to the executor (`PipelineStore.java:21-32`). Onboarding becomes a guided view over the head of the same graph; "Stream" is already only a Catalog label (`kind: "STREAM"`, `CatalogRoutes.java:29-58`) — there is no stream-specific backend model. Plan with pinned U-A–U-G and workstreams W0–W5 → **`superpower/onboarding-pipeline-unification.md`**; the superseded split plan is archived to `archived-documents/plans-archive/`. Closes the four issues the operator named: **single schema store** (decided on engine-executability — no code path resolves a registry schema id, all three `PipelineConfigParser` branches do `Paths.get`→`Files.readString`, and `bindKindFor` never offered `schema`; 16 config files vs 2 registry components, so this is a RETIREMENT not a merge) · **config-key unification** (one `AttributeSpec` table per concern; Onboarding's engine-real keys win because `node-attributes.ts:16-22` self-describes as best-guess) · **plugin-parser parity** (Onboarding's read-only guard is obsolete now the segments editor + ASN.1 ingest shipped) · **promotion-grade export** (reuse the EXISTING backend `BundleExporter`/`DataSourceBundleResolver` closure walk rather than a third pipe; extend it to decision rules + reference datasets; add import-time referential integrity — today a missing connection is not caught until first poll). **S1 from the split plan SHIPPED 2026-07-30 and STAYS** (go-live auto-registers the Dataset, idempotent by physicalRef) — nothing reverted; S2 (`SAMPLE_SOURCE_NAMES` → shared store picker) still wanted. ⚠ **W0 is a hard gate**: the lift→lower round-trip is potentially lossy BOTH ways — `dirs`/dedup/quarantine/`csv_settings` have no node home, and the graph expresses branching/multi-sink shapes a flat config cannot — so prove it lossless over the existing 16 configs or NAME the supported subset before W4/W5 build. ⚠ The `EnrichmentService` incremental-vs-full-recompute risk carries forward (now W4): the registered path runs per committed batch, `EnrichJob` is a full recompute — do not silently convert one into the other. **W1 (a+b) SHIPPED 2026-07-31.** W1b needed **no** space-root threading after all — a config being loaded always has a directory, so `PipelineConfig.load` passes `Paths.get(configPath).getParent()` and not one caller changed. Schema references now resolve **config-relative first, working-directory second** at all three sites, so a bare `x_schema.toon` beside its pipeline is portable while every existing config loads byte-identically — **no on-disk migration**. ⚠ `ConfigRoutes.schemaFileFindings` had to move with it: it is an **ERROR** gate at registration, so a working-directory-only check would have rejected a portable config the engine runs. ⚠ Reading portable refs works; **writing** them does not — `PipelineCompiler`, `stream-bundle.ts`'s `conventionPath` and the space template's `spaces/${SPACE}/…` placeholder all still emit working-directory-relative paths. That migration, plus the two WARNING call sites (which would spuriously warn on a portable draft), is deliberately folded into **W3**. **W2/U-E SHIPPED 2026-07-31** (`05c93cf1`) — and it was a live bug, not the predicted obsolete guard: `parsing.plugin`/`processing.ingester` triggered a **whole-pane lockout**, while the pane's own `savePlugin` writes `frontend: 'plugin'`, so a plugin stream saved through this pane could never be re-opened; lifting the guard alone would have shown it as delimited and overwritten its parsing block on Save, so the fix pairs the lift with `rehydratePlugin` (matches on `ingesterClass`, since Save stores the FQCN not the parser id) + an `unservedPlugin` warning. **W2/U-D is HALF shipped:** `json__records_path` now authorable, gated `notEquals: 'newline'` because the parser hard-fails a nested path under NDJSON. **W2/U-D COMPLETE 2026-07-31 — and the collector half turned out to be a vocabulary problem, not a key problem.** Checking the keys against the engine found the UI's *type vocabulary* was fictional, not merely its keys: `BuiltinNodeType` (the catalog behind `GET /pipelines/node-types`) has **no** `collector.*`/`sink.file`/`parser.dsv`/`transform.record|aggregate|alert`, only `acquisition`/`adapter`/`parser`/`transform.*`/`enrichment`/`sink.persistent|materialized|view` plus a CONTROL category the UI ignores — the overlap is `transform.filter` + `transform.route` alone. `PipelineValidator` only *warns* `UNKNOWN_TYPE` and never checks config keys, and `PipelineCompiler` **silently drops** an unknown-typed node, so the fiction persists and validates clean while no collector can ever lower. Correcting `collector.file`'s keys would have polished a discarded node. Also: `recursive`/`min_age_seconds`/`fetch_size`/`group_id`/`batch_size` are read **nowhere** server-side (`group_id` absent *by design* — `KafkaConnector` uses the acquisition ledger, not a consumer group), and the real `query`/`watermark_column`/`topic` belong to a **ConnectionProfile `options:`**, not a pipeline `collector:` block — a different concern, so those tables were removed rather than merged. **Operator decision: rename UI → engine** (not widen `BuiltinNodeType`, which would commit the executor to a taxonomy it does not implement). Shipped: the mock palette is a faithful port of the enum incl. the missing CONTROL category (5 pinning specs); `node-attributes.ts` is engine-keyed and `acquisition` **reuses** `COLLECTOR_ATTRIBUTES`, moved to `inspecto/component-model/` so one table serves both features (spec asserts object identity); sinks keep only the `output:` keys the backend reads (`format`, `compression`); remaining `transform.*` are deliberately unspecced rather than re-guessed; 6 seed files corrected for types, config keys and edge rels (`success`→`data` off non-sinks, `kept` was never a `PipelineRel`), with two `filter → alert → sink` chains re-wired to fan out since `alert` is CONTROL and emits nothing; `MOCK_STORE_KEY` bumped v20→v21 (**required** — a persisted store would otherwise keep serving the fiction). Verified: `lint:tokens` + build + **1904** tests, plus offline-preview proof (palette shows all 20 types/5 categories, store holds only engine-real types+rels, all 7 pipelines load, Validate on 19-node `mediation_backbone` = 19 informational findings, **zero** unknown-type or wiring findings). ⚠ Two things this deliberately did NOT do, both W0's: the engine has **no grouping transform** (`transform.aggregate`→`transform.derive` kept it a rename; modelling a rollup honestly means `sink.materialized`, which changes graph shape), and `PipelineCompiler` still reads only a narrow key set per role, so nodes can carry config it ignores — **W0 can finally measure that, now the vocabulary matches** | `superpower/onboarding-pipeline-unification.md` |
| **Catalog lifecycle (holistic review 2026-07-30)** | Findings of the user-aspect Catalog walkthrough (manual: `USER_GUIDE.md` §4.3): **no take-offline control** — the only write of `active` is `true` (publish pane), yet `DELETE /config/pipeline` 409s while active ⇒ **a Live stream cannot be removed from the UI at all**; needs a deactivate affordance (publish pane or Catalog row action) before a delete path can exist · **live edits are silent** — every stage save on a Live stream hot-reloads immediately while the shell copy still says "it runs only when you go live"; needs at minimum a Live-state warning + copy fix in the onboarding shell · **no dependent check on origin delete** — enrichment by-name bindings, Dataset `physicalRef`s, KPIs/Widgets all dangle (only the read-only `metadata_validate` task detects); consider a pre-delete impact read over the metadata graph · ~~**the Stream→Dataset hop is entirely manual**~~ **half CLOSED 2026-07-30 (split S1: go-live now registers the Dataset)**; the Dataset editor's source picker is still `SAMPLE_SOURCE_NAMES`, not the Catalog's real stores (= split S2) · **no per-date/partition retention for stream data** — `cleanup` is filename-glob (matches `getFileName()` only — cannot target `day=…`) + mtime-gated; a D5-style retention tier for partitions is unbuilt · `processing.duplicate_check.retention_days: 30` is injected silently by the create dialog and surfaced by no stage pane · lineage surfaces don't cross-link (Catalog row ↛ Runs, batch ↛ Catalog node; the per-store panel is the only bridge) · *(rename is NOT re-recorded here — it is the identity/display split above)* | `okf/frontend/features/catalog.md` · `okf/frontend/features/onboarding.md` |
| **Platform Services / the plugin envelope** | **Stage 1 SHIPPED 2026-08-09/10 (S1-0…S1-8)** — the `PlatformServices` seam, `requires:` grants validated at registration, the v1 menu (`notifications` · `incidents` · `schema` · `consignment-status`) with the engine as first consumer, `sample.hello` migrated onto a grant with its injection dropped, and `tools/scaffold.mjs` + `PackTestHarness`. Plan archived to [`archived-documents/plans-archive/platform-services-plan.md`](archived-documents/plans-archive/platform-services-plan.md). **Open:** **Stage 2 — the open Step-kind registry** (`StepTypeProvider` with a `LOWERED`/`EXECUTED` execution mode, `StepContext`, failure mapping, watchdog). ⚠ **Hard-gated on the branch-aware executor becoming the armed production path** — armed linear runs still compile back to the legacy engine and only dry-run rides the graph executor, so there is nothing production-grade to mount `EXECUTED` on; and gated again on the S2-2 **bridge spike** (measure rows/s through a no-op `EXECUTED` Step vs the same pipeline fused, and publish the number) before GA. ⛔ Third-party `LOWERED` stays closed until a SQL-fragment guard exists — a pack contributing SQL text into the fused query is an injection surface · **Stage 3 — pack-contributed services** (`ServiceProvider` SPI; a collision fails the pack atomically; reference-tracked quiesce so a service's owning pack cannot unload while a Run holds a grant on it) · ~~**an `alerts` service**~~ **SHIPPED 2026-08-10** — `AlertAccess.evaluateRules()` (the evaluator only; rule CRUD stays a control-plane concern), and `alert.evaluate` declares it, so its `Supplier<AlertService>` injection is gone along with `JobService`'s `alerts` field/setter and both `CollectorService` wiring sites. ⚠ It also fixed a live MNT-1 violation the migration exposed: `alert.evaluate` ignored `dryRun()`, so a preview fire really evaluated and really opened Incidents; evaluation has no preview form, so the Job now does nothing and says so · **`DatasetAccess`** — deferred by design to follow the Consignment Selector so it lands with pruning and generation-pinning built in; it then becomes the seam's flagship service · a *filtered* read-only `services()` on `ProcessorContext` — only when a processor use case demands it, never the full menu (D4) · **no Job-side watchdog** (R1) — the `EXECUTED`-Step watchdog is mandatory scope for S2-3, but a Job that hangs is still a recorded gap on both sides · extracting a thin compile-against devkit jar (today a pack depends on `inspecto-engine` itself) — revisit when the first *external* pack author appears (D5) | `okf/backend/control-plane/platform-services.md` |
| **Collector rename residual** | Pipeline TOON config-key `source:` block kept (renaming breaks authored TOON) — separate migration if ever wanted; `'SOURCE'` stage category unchanged | `okf/backend/gotchas/cross-cutting.md` |
| **Quarantine / D-ETL** | reprocess is **whole-batch only** — no record-level replay (tracked only if prioritized) | `okf/frontend/features/run-detail.md` |

## 5. UI residuals + security-module residuals

> ### 🟡 DATA-GOV-1 — operator CDR data lives on disk only; git exposure CLOSED (opened 2026-07-30, git half closed 2026-07-31)
>
> **The git hazard is resolved — verified 2026-07-31.** The original row flagged 7 commits
> (`d37eaef2..53619f13`, ~72 MB across 110 files) carrying production operator data. Those commits
> **no longer exist**: `git cat-file` reports both ends of the range GONE, no ref contains them, and
> the ASN.1 work was re-landed as the clean rewrite `0facf359` instead. Confirmed today: **no
> `.ber`/`.asn`/`.dat`/`.jsonl` or `corpus/`/`rtdms/` path is tracked anywhere in the repo**, and
> nothing in `origin/master` reaches the old range. ⚠ Do not "fix" this by force-pushing or resetting
> `master` — there is nothing left to strip.
>
> **`asn-parser/.gitignore` is now deny-by-default** (2026-07-31). It previously enumerated known-bad
> extensions, which measurably leaked: a `.parquet`/`.txt`/`.csv` dropped into `corpus/` was
> committable. Now `corpus/*` + `!corpus/README.md`, so a new file type cannot slip in. `config/`,
> `data/`, archives and raw capture extensions were already covered.
>
> **What is still open is distribution, not git.** ~57 MB of real carrier data sits in every shift's
> working tree — `asn-parser/config/rtdms/` (64 files, 22 MB, operator names in the paths),
> `asn-parser/data/` (17 files, 21 MB), `config+data.7z` (13.8 MB). Ignored, never committed, but the
> parity harness depends on it and there is **no sanctioned way to obtain it** on a fresh checkout, so
> parity runs are not reproducible for anyone who lacks the tree. Options unchanged: distribute
> out-of-band · Git LFS with access control · a synthetic/redacted corpus (best if this repo ever goes
> public). Owner: the ASN shift + whoever owns the customer-data agreements.
>
> **The standing rule this leaves behind:** commits carry code, config schemas and docs — never
> payloads. Test fixtures are hand-written and obviously synthetic (`imsi="42"`), never trimmed from a
> real capture. Domain *vocabulary* (`imsi`, `moCallRecord`) is schema language and fine; real *values*
> are not.
>
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
> **⚠️ HISTORY REWRITE DONE 2026-07-26 — AND IT DID NOT END THE PUBLIC EXPOSURE. `refs/pull/*` DEFEATS IT.**
> Reversing the 2026-07-25 "deliberately not done" call, the operator ordered the rewrite. Executed with
> `git-filter-repo --replace-text` (all 5 values → `REDACTED-SEC-INCIDENT-1`): 73 occurrences replaced,
> **0 secrets in 22 598 objects**, all 1008 commits preserved, `HEAD` tree byte-identical (zero code change),
> `1.x`/`2.x`/`3.x` SHAs untouched (they never carried the values, so the retired-line rule stayed intact).
> Force-pushed: `master` `bb2c486f`→`f275b6f6`, `4.x` `27780fee`→`f1fb6f20`, tags `v4.0.0-RC1` +
> `backup-pre-squash-20260725` re-pointed, and the stray `origin/claude/brave-pascal-55aae7` (which had NOT
> been rewritten — it was deleted locally *before* the rewrite, so filter-repo never saw it) deleted.
>
> **THEN VERIFIED AGAINST THE LIVE API — THE SECRETS ARE STILL SERVED.** All five PRs were merged
> **2026-06-21 → 06-24**, i.e. *after* the 2026-06-12 leak commit, so every `refs/pull/N/head` pins the old
> lineage. A `gh api .../contents/…environment.prod.ts?ref=<PR-head-sha>` still returned secret-shaped
> literals at PR heads #1/#4/#5 **after** the force-push. **`refs/pull/*` cannot be deleted or rewritten by
> a repo owner — only GitHub Support can purge them.** So:
>
> - **The rewrite bought local/branch hygiene, NOT remediation.** Anyone can still
>   `git fetch origin refs/pull/1/head`.
> - **Open a GitHub Support request to purge unreachable objects + PR refs.** Until that completes, treat the
>   exposure as ONGOING, not historical.
> - **ROTATION IS NOW UNAVOIDABLE AND URGENT** — it was always the real fix, and the exposure is live.
> - ⚠ **The issuer auth logs are GONE** (operator, 2026-07-26) ⇒ the "pull logs before rotating" step is moot
>   and we can **never** establish whether the exposure was exercised. "Assume compromised" is the only
>   defensible reading. Rotation is no longer gated on anything.
> - ⚠ **Lesson for any future rewrite: enumerate ALL remote refs first** (`git ls-remote origin`), not just
>   branches. A stray branch nearly slipped through, and `refs/pull/*` cannot be fixed client-side at all.
> - ⚠ **~84 short SHAs across `docs/`, the skills and the memory index now dangle** — every pre-rewrite SHA
>   on the master/4.x lineage is invalid. Mapping for this shift's commits is in `SESSION_STATUS.local.md`.
> - Pre-rewrite backup bundle: `C:/sandbox/ucc-prerewrite-backup-20260726-203545.bundle` (all refs).
>   ⚠ **It contains the secrets — delete it once the incident is closed.**
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
> **⚠ Stale agent worktrees keep coming back — treat this as recurring, not resolved.** The two dirs named
> here on 2026-07-25 (`quirky-lalande-a4a696/`, `vigorous-ptolemy-911ed7/`) were deleted, but by 2026-07-26
> `git worktree list` showed **six** under `.claude/worktrees/`, five of them agent-isolation branches
> pinned at pre-`8dd072c6` commits and therefore holding live copies of the leaked
> `inspecto-ui/src/environments/*.ts` (plus copies in their `.angular/` caches). One
> (`eloquent-bose-14c91c`) is already `prunable`. **Any agent run with `isolation: "worktree"` re-creates
> this.** Sweep with `git worktree prune` + `git worktree remove` at handoff. Deleting them never reduced
> the incident's severity — the values are public via git history regardless — it only stops a local grep
> from handing them out. Rotation remains the fix.
>
> **✅ Local checkout verified CLEAN 2026-07-27** (the sweep found nothing left to sweep): `git worktree
> list` shows only the main checkout, `.claude/worktrees/` is empty and `.git/worktrees/` does not exist,
> so all five stale trees are already gone. Also verified, so the next shift need not re-check: the shipped
> guard `node tools/check-secrets.mjs` exits 0 on the working tree; **all five values are `REDACTED-SEC-INCIDENT-1`
> in local history on every ref** (`git log --all -S` over the redaction marker hits the leak commits, and
> the secret literals return nothing), and the stray local branch `claude/brave-pascal-55aae7` — which
> `refs/pull/*` notes as never rewritten — is at `8f30d548`, carries **0 commits not already in `master`**,
> and holds no secret in `environments/*.ts`. ⚠ **None of this touches the actual exposure**: `refs/pull/*`
> on the public remote still serves the literals, so severity is unchanged and rotation is still the fix.
> ⚠ The pre-rewrite backup bundle `C:/sandbox/ucc-prerewrite-backup-20260726-203545.bundle` (16 MiB) is
> **still present and still holds all five secrets in cleartext** — deliberately NOT deleted (operator call
> 2026-07-27: it is the only pre-rewrite recovery point and the incident is still open). Delete it at
> incident close, per the note above.
>
> Lower severity, same files, unaddressed: internal hostnames/IPs are published in-repo
> (`68.183.16.242`, `p20.prod.pronto`, `app1.pronto.lebara.sa`).

**UI residuals (small, valuable):**
- **⚠ A deployment that wants IdP sign-out must now set `endSessionUrl`.** RP-initiated logout shipped
  2026-07-26, but it is **inert until configured** — `bootstrap.auth.endSessionUrl` or
  `environment.oidc.endSessionUrl` (declared, never derived; see `okf/backend/editions/auth-security.md`).
  Blank ⇒ sign-out ends the Inspecto session only, exactly as before. **The backend does not emit
  `bootstrap.auth` at all today** (no `authorizeUrl`/`clientId` either — real deployments configure the SPA
  via `environment.oidc`), so wiring an `auth.oidc.endSessionEndpoint` property through `BootstrapRoutes` is
  the follow-on if server-supplied OIDC config is ever wanted.
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
- X-Actor **full removal** — client-migration-gated (see §4 API v1).

> **Do not partially implement security concerns elsewhere** — this section stays the single scope.

## 6. Engineering / tech-debt

The engineering MoSCoW (build hygiene, `CollectorService` decomposition, `agent.spi` facade,
Fuse-leftover removal, reactor split, shutdown robustness, `@PublicApi` freezing) is **COMPLETE and
archived**; the 16-module reactor as-built + the extraction playbook live in
[`okf/backend/modules/reactor.md`](okf/backend/modules/reactor.md).

**Open:**
- ~~🔴 BUILD-1 — the offline reactor build is BROKEN~~ **CLOSED 2026-08-06 — NOT A BUILD DEFECT. The
  diagnosis was an artifact of running as the wrong Windows profile.** `mvn -o clean test` completes
  the full **23-module reactor: BUILD SUCCESS, 2799 tests, 0 failures, 0 errors, 6 skipped** (3m32s),
  offline, with the committed surefire **3.5.3** pin left exactly as-is.
  **Root cause:** the shift that filed BUILD-1 ran as Windows user `User`, so Maven read
  `C:\Users\User\.m2` — a near-empty cache. Every artifact it reported "absent" is present and a real
  jar in the checkout owner's `C:\Users\jotder\.m2`: `maven-surefire-common:3.5.3`,
  `junit-platform-launcher:1.12.2`, `jackson-annotations:2.17.1`, `postgresql:42.7.2`. There is **no
  `.m2` gap** and the "months old" framing was the same illusion.
  ⚠ **Retraction:** the uncommitted local retarget of that pin to 3.5.2 was **never needed** and has
  been **reverted**. The 3.5.3-vs-3.2.5 outlier is a cosmetic inconsistency, *not* a build blocker —
  do not "fix" it to chase a failure that does not exist.
  ✅ **The earlier "full reactor, BUILD SUCCESS ×3" baseline is REINSTATED** — it was always correct;
  it simply could not be reproduced from a profile with no dependency cache.
  **Lesson (the reusable part):** on this shared sandbox, `whoami` before trusting any build or git
  verdict. A wrong profile silently swaps `~/.m2`, `~/.claude`, and git's ownership check at once, and
  every symptom it produces looks like a repo defect. `mvn -o -pl inspecto -am install -DskipTests`
  remains a valid fast path, but it is a convenience now, not a workaround.
- ~~VOCAB-1 — the vocabulary guard is red on `master`, and silently under-reports.~~ **CLOSED
  2026-08-06 — both halves fixed; the guard is green and can no longer pass vacuously.**
  (a) The violation is gone: the offending table header in `job-parameter-contract-plan.md` meant *where
  the value came from*, not an acquisition entity, so the column is now **Origin** — the banned word was
  never load-bearing there. (b) The silent under-reporting is fixed in `tools/check-vocabulary.mjs`:
  `git ls-files` failing has two causes and only one is benign, so the guard now probes git **once, up
  front**, and distinguishes a source tarball with no `.git` (skip the git-backed passes, as designed)
  from git refusing to read a real checkout (**exit 2** with the `safe.directory` fix spelled out).
  A third pass was worse than recorded: `treeDocs` used `?? []`, so `docs/{okf,superpower}` silently
  scanned **zero** files without even printing "skipped" — the summary now names that scope too.
  Verified both ways: green run reports real counts (9 user-facing + 151 tree + 143 TOON + 1399 source);
  the previously-silent failure mode now exits 2 instead of 0. **No `GIT_CONFIG_*` incantation is needed
  any more** — just run `node tools/check-vocabulary.mjs`, and believe it when it is green.
- **GRAPHIFY-1 — `graphify-out/` was rebuilt on a newer tool; keep the two in step (2026-08-06).**
  `graphify update .` refusing to run was **tool-version skew, not a corrupt graph**: the artifacts were
  written by a 0.9.x release whose stat-index entries carry `hashes: {path: sha}`, while this machine had
  **graphifyy 0.8.44**, whose `cache.py` reads `entry["hash"]` → `KeyError: 'hash'`. The earlier
  "26567 vs 29841 nodes" fail-closed was the same skew: a downlevel tool re-extracting a newer graph.
  **Resolved** (operator-approved): upgraded to **0.9.34**, re-synced the skill, `graphify update .`
  exit 0 → **26646 nodes, 69607 edges, 979 communities**; queries verified working and doc-derived nodes
  present. `graphify-out/` is gitignored, so nothing about this is committed.
  ⚠ **Two things to know.** (1) `graphify install` writes the skill to **`~/.claude/skills/graphify/`**,
  which this repo's `CLAUDE.md` forbids as the source of truth — the repo copy under
  `.claude/skills/graphify/` was re-synced by hand and **is the one that must be committed**. Re-sync it
  after every `graphify install`. (2) `graphify update .` is **AST-only**; the semantic/doc pass needs
  `GEMINI_API_KEY` (not set here), so don't read a node-count drop after a code-only update as loss.
  Optional cleanups it reported, neither blocking: `pip install "graphifyy[sql]"` for the 2 skipped
  `.sql` files, and 97 `.json` files that yield zero nodes (upstream #1666).
- **UI-S7 — table-entry `collect` authoring is S3-blocked; the summarize half shipped 2026-08-06.**
  The ELT-amendment UI plan's S7 split in two on grounding. The **summarize** half shipped as measure-
  grammar validation (contract-pinned to `MeasureCompiler.AGGS`; see the plan's "S7 SPLIT" section) and
  fixed a latent defect where a `type: 'list'` field's `<mat-error>` could never fire, making every list
  error — `required` included — invisible.
  The **table-entry** half is **not** buildable and must not be forced: it needs the deferred Phase 3 S3
  (a Dataset-write Signal), and separately `PipelineConfigParser.java:129-130` requires
  `dirs.poll`/`dirs.database` **at parse time**, so a table-sourced draft cannot even load. Revisit only
  when S3 lands. Two deliberate non-goals recorded there: a structured per-measure builder (drift risk
  against the string grammar) and `group_by` entry validation (same shape, easy follow-up).
- **Build → Test → Run authoring journey — G1–G5 SHIPPED 2026-08-02; only "test against real data"
  remains.** Plan of record:
  [`superpower/pipeline-build-test-run-gaps.md`](superpower/pipeline-build-test-run-gaps.md).
  Build and Run both work. The journey is now *honest* — no dead buttons, no silent activation
  failure, no save-time surprises — but **a user still cannot test a pipeline against their own data.**
  - ~~G1 two of three test affordances 404~~ **CLOSED** — both gated behind `environment.mockFlows`
    rather than deleted (run-to-here is a complete mock-backed feature and is literally the Step 5 UI).
    ⚠ **Retraction:** a mid-flight note here claimed `/components/*` did not exist in the backend —
    **that was wrong** (bad grep, stale cwd). `ComponentRoutes.java:42-44` registers
    `POST /components/{transform|grammar|sink}/{id}/test`; the dialog just sends the wrong two segments
    (dotted node type + node id instead of the family + a *registered component name*). Verified live:
    list 200, dotted-type test 404. A real repoint is plausible, but a node with inline config binds no
    registered component, so it is not purely a URL fix.
  - ~~G2 no authoring-time signal~~ **CLOSED** — `lowerable` added to `GET /pipelines/node-types`
    (`PipelineProjection.catalog()` + new `PipelineEditable.isLowerable`); non-lowerable palette
    entries are disabled/dimmed/non-draggable. Verified live: **9 enabled / 11 disabled of 20**. The
    mock↔server list is pinned by name in `pipelines.handler.spec.ts` — a laxer mock is the failure
    mode this flag exists to kill.
  - ~~G3 first-only transient toast~~ **CLOSED** — every refusal now lands in the Validation dock
    (persistent, click-to-select); the toast is just a count pointing there.
  - ~~G4 armed pipeline silently never runs~~ **CLOSED** — `ConfigRoutes.armedWithoutSchemaFindings`
    422s `active:true` with no schema source. ⚠ The residual unknown resolved *against* us: the wizard
    registers **only on create**, and create always writes `active:false`; activation is a later plain
    `/config/write overwrite:true` with no register call — so the moment of arming had no feedback path
    at all. Fix is deliberately narrower than the load-check originally proposed: a blanket
    `PipelineConfig.fromMap` gate would also hard-fail an *unresolvable* schema ref, which is
    intentionally only a WARNING.
  - ~~G5 silent one-way door~~ **CLOSED** — warning banner naming the offending types on load. ⚠ The
    plan said "mark the editor read-only"; **that was wrong** — deleting the offending node is the only
    repair path, so read-only would have locked users out of the fix.
  - **⬜ REMAINING — bounded test run over real inbox files.** The *only* thing left between Build and
    Run. UI already exists and works against the mock; this is a backend job. Missing: reading actual
    inbox files + running the real ingest/parse stage (`PipelineDryRun` is synthetic-rows-only and
    skips parsing), a stop-at-node cutoff (`PipelineExecutor.dryRun` has no partial-graph primitive),
    and registering the reserved `POST /pipelines/authored/{id}/run?to=` (`PipelineRoutes.java:69`,
    must stay scratch-only). **Medium/large.**
- **Branch-aware executor — run what the graph editor can now author** (surfaced 2026-08-01 by
  unification W5). W5 made the graph editor *author* the canonical `*_pipeline.toon` for the
  single-source subset, refusing everything else with `UNSUPPORTED_NODE`. **11 of the 20
  `BuiltinNodeType`s are still refused at lowering** and stay grandfathered `*_flow.toon`,
  editable-read only: `adapter`, `transform.select`/`derive`/`validate`/`route`/`split`/`merge`,
  `sink.materialized`/`view`, and the non-`gap` CONTROL pair `alert`/`event`.
  ⚠ **Multi-sink came OFF this list 2026-08-02** — the plural `sinks:` block runs multi-destination
  ingest through the `BatchIngestStrategy.writeAndTrace` fan-out, and `MULTI_SINK` is now a
  declared-but-never-raised constant (`PipelineEditable.java:37`; the `>1 database` path lowers to a
  `sinks:` block instead, `79dcb3e6`). Don't re-derive the gap from that code — it reads as live.
  ⚠ **The gap is the config format, not engine capability.** `RowShaper.shape` already executes the
  whole `transform.*` family (`select`/`derive`→`project`, `validate`, `route`, `split`, `merge`), and
  it reaches production **through jobs** — `PipelineJobRunner:172-176` is the real caller of
  `PipelineExecutor`. What's missing is a home for those nodes in the flat `*_pipeline.toon` and the
  ingest-path wiring, not a shaper. Genuinely unimplemented anywhere: `adapter`, `alert`, `event`.
  Closing this = the branch-aware executor
  of [`okf/backend/pipeline-graph/pipeline-graph-design.md`](okf/backend/pipeline-graph/pipeline-graph-design.md)
  §13 R3, which would let the editor lower (and the ingest path run) the full graph vocabulary. Largest
  remaining pipeline-graph piece; explicitly out of scope for W5.
  - **IN FLIGHT — plan of record:**
    [`superpower/branch-aware-executor-plan.md`](superpower/branch-aware-executor-plan.md). The operator
    reordered the stages 2026-08-01: **throughput/decoupling (Stage B) first**, multi-destination sinks
    later as a plural `sinks:` section, the executor bridge (Stage A) deferred behind B.
    Shipped so far: **B1** per-pipeline run guard (`ingestLock` was one global lock across the whole
    cycle), **B2** non-blocking poll dispatch (a tick no longer waits on the runs it starts), **B3a**
    remote fetches stage outside the inbox and land atomically, **B3b** acquisition has its own driver —
    `dispatchAcquireCycle()` on `acquire.pollSeconds`/`acquire.maxConcurrent` with a dedicated per-pipeline
    `acquireGuard`; the poll tick is ingest-only; `countPending` is now the exact landed backlog, **B4**
    acquisition back-pressure — `selectDueForAcquire` skips a pipeline whose `countPending` reached
    `-Dacquire.backpressure.highWater` (0=off), so a slow ingest cannot make acquisition fill local disk;
    the durable inbox is the spill queue (§3.5), throttling the producer (negative feedback, unlike T15),
    **B5** reconcile §3.8 docs (docs-only) — the design doc now carries a **"Collection is a unit, not a
    second scheduler"** block distinguishing Stage B (one loop-scheduler-side driver split into
    producer/consumer timers with a guarded, back-pressured hand-off over the durable inbox) from the deleted
    `ingest` job type (two schedulers racing one inbox with no lock). **✅ STAGE B IS CLOSED.** ⚠ **B4 was
    rescoped** from the plan's "queue-driven multiplexer, §3.5 verbatim": the verbatim per-edge escalation
    needs the deferred Stage A, and B2+B3b already met the skew motivation — see the plan's B4 section.
    **Stage A steps 1–3 SHIPPED as tested-but-dormant machinery** (`318acf2a`, `6965f6f3`): `BatchGraphRunner`
    (run one materialised batch through `PipelineExecutor` on the ingest path), `BatchProcessor.finalizeSource`
    (the crash-ordered commit body re-homed so the graph path reuses it; `commit` delegates, flat path
    byte-for-byte), and the engagement predicate `BatchGraphRunner.engages` (>1 data-fed sink, excluding the
    `unmatched`-wired quarantine).
    **UPDATE 2026-08-02 — `sinks:` shipped (`0cdc9dff` + `79dcb3e6`), so re-read this:**
    - **Step 4 is DONE.** The editor's `MULTI_SINK` refusal is lifted; a 2-database graph lowers to a
      `sinks:` block. `PipelineConfig` carries `record Sink` + `List<Sink> sinks()` (never empty — it
      synthesises the `dirs.database` + `output:` shorthand), so the old note that
      "`PipelineConfig.Output` is a single record" **no longer holds**.
    - **Multi-destination did NOT need Stage A.** It shipped as flat-path fan-out in
      `BatchIngestStrategy.writeAndTrace`, not through `BatchGraphRunner`.
    - **Step 3 is still NOT wired.** `BatchGraphRunner` remains uncalled from main code — the only
      references are two prose comments in `BatchProcessor.java:87,95`. Its `engages` predicate wants
      **>1 data-fed sink _node_**, which `sinks:` does not create (that's N destinations for one branch),
      so the predicate is still `false` for every real ingest config. The remaining trigger for Stage A
      is a genuinely branching graph, which is exactly the refused-node list above.
    Stage C is unstarted (needs sign-off).
    - **Deferred from B3b — acquisition-side "listed remotely but not yet fetched" gauge.** With ingest now
      walking the inbox, `countPending` is the exact *landed* backlog; the remote-side pending signal ("the
      connector listed N files we have not fetched yet") is a distinct, still-unbuilt metric. Decide its
      name (e.g. `inspecto_files_awaiting_fetch`) before adding it. No metric was renamed in B3b — none was
      misleading.
    - **Deferred from B4 — `acquire.maxFilesPerCycle`.** B4's high-water gate bounds inbox backlog *across*
      acquire ticks, not *within* one: a single cycle still fetches the whole discovered listing, so one tick
      can overshoot the mark. A per-cycle acquisition intake cap (the acquisition-side mirror of T15's
      `-Dingest.maxFilesPerCycle`, oldest-first) would bound a single fetch. Build only if overshoot is a
      real problem — the across-tick gate already bounds steady-state.
    - **Deferred from `sinks:` (shipped 2026-08-02) — three documented follow-ups.** The plural `sinks:`
      config-format shipped all 4 slices (as-built: `okf/backend/engine/output-sinks.md`). Refused/deferred,
      build only if a real need appears: (a) **per-sink `ducklake` block in the flat `.toon`** — the
      indexed-tuple row can't carry a nested map, so multi-sink + ducklake needs a nested representation or
      the editor; (b) **decision-rule *routing* + `sinks>1`** — routed outputs are single-destination, refused
      at runtime in `writeAndTrace`; (c) **versioned reference store + `sinks>1`** — refused at
      `PipelineConfig.prepare()` (one version history is ill-defined across destinations). Also open: a
      `ConfigSpecs`/`ConfigJsonSchema` structural spec for `sinks:` — authoring-UX only (`ConfigLoader.validate`
      ignores unknown keys, so round-trip needs none), pairs naturally with (a).
  - **Duplicate pipeline id at construction — DECIDED 2026-08-01: fail fast.** `CollectorService`'s
    constructor accepted two config files declaring the same in-file `name:`, while
    `registerPipeline` rejected exactly that at runtime. The two surfaces now agree: a new
    `requireDistinctPipelineIds` runs as the first statement of the shared constructor and throws the
    same `IllegalStateException("pipeline id '<id>' is already registered from <path>")`. It is placed
    before any store/executor/`EventLog` allocation so the throw cannot leak a half-built service, at
    the cost of one extra parse per config at startup. **Why throw rather than warn:** the poll cycle
    iterates registered *paths* (`PipelineScheduler.runCycle`), so a duplicate was not shadowed — both
    files ran, concurrently, under one identity, writing to the same name-keyed status/audit/log
    destinations, while `paused`/`running`, the cadence map, `IntakeGovernor` caps and
    `ConfigRegistry.getPath` all collapsed them into one. `ConfigRegistry.rebuild`'s existing
    "keeping the latter" WARN only ever fixed the read surface. **Blast radius checked:** no space
    has a duplicate id (the only repeated name, `orders`, is `spaces/demo` vs the
    `spaces/_templates/orders-starter` template, and `SpaceManager.discover` admits only dirs with a
    direct `config/` child, so templates are never booted); `SpaceManager.bootQuietly` catches
    per-space and skips, so at worst one space drops out rather than the process failing to boot.
    The legacy `ControlApi.main` path is the exposed one — it feeds raw CLI paths to a recursive
    `resolveConfigs` walk with no directory exclusions, so pointing it at a tree containing
    `_templates` now fails loudly instead of double-running `orders`.
- **`ConfigSpecs.enrichment()` does not spec the `references:` map** (surfaced 2026-08-01 by
  unification W4b). Per-entry keys (`ref` XOR `path`, `format`, `as_of` — `EnrichmentConfig.fromMap`
  hard-fails them at LOAD) are validated only in the UI; a hand-authored or API-written config with a
  bad reference passes `POST /config/write` clean and fails at registration/first run. Close it
  server-side (fail-closed at write, like every other spec'd shape). The urgency dropped with W4b —
  both authoring UIs now share ONE validator (`inspecto/enrichment/enrichment-editor.component.ts`)
  — but API/hand-authored writes remain unchecked. Needs a ConfigSpec map-of-objects notion (the same
  wall `references` hit when it was first left unspecced).
- **AI drafting has no applicable component kind** (created 2026-07-31 by unification W1a, a deliberate
  and recorded feature loss). `component_draft` was offered ONLY on the `component-form.dialog` `schema`
  kind, because of that dialog's kinds only `schema` had a structural `ConfigSpec`. `schema` is no longer a
  registry component, so the affordance was removed WITH it rather than left answering *"no structural spec
  for kind"* on every use. **The backend repair loop is untouched and still generic** — nothing was deleted
  server-side beyond `ConfigSpecs.schemaComponent()`. To restore the button, give a surviving kind
  (`grammar`/`transform`/`sink`) a structural `ConfigSpec`; the dialog then renders `<inspecto-ai-assist>`
  again with no further wiring. Related: the guided Schema stage keeps its own derive-from-sample, so a
  Stream's schema authoring did NOT regress — only the registry pane's. `okf/frontend/features/inline-ai-authoring.md`
- **Config-declared paths resolve unjailed against the server CWD** — one systemic pass, **not** the
  "3 sites" an earlier note claimed (inventoried 2026-07-31). Hardening, **not an open hole**: config
  writes are already privilege-gated, so this is defence-in-depth against a mis-authored or hostile
  config, not a reachable-by-anonymous escape. Scope, accurately:
  - **~80 call sites** turn a config string into a filesystem path. Three groups, and the group
    decides the difficulty: **(i) route/server layer** — a space root IS in scope (`ConfigRoutes`,
    `RunRoutes`, `EnrichmentRoutes`); **(ii) job tasks** — `writeRoot`/`dataDir` are already sibling
    locals (used to build `ComponentStore`) but are not applied to the neighbouring `cfg.require(...)`
    path fields (`BackupTask`, `MaintenanceJob`, `MaterializeTask`, `ReportJob`, …); **(iii) the config
    layer — NO root in scope at all**, the actual blocker.
  - ⚠ **The blocker as recorded was overstated — corrected 2026-07-31 by unification W1b.** The claim was
    that the config layer has *no root in scope at all* and so needs a root threaded through public
    signatures. Not so for the **loaded-from-file** path: `PipelineConfig.load(configPath)` trivially knows
    `Paths.get(configPath).getParent()`, and W1b now passes exactly that into
    `PipelineConfigParser.parse(Map, String, Path)` **with zero caller changes**. The three schema reads
    (`parsing.plugin.segments[]`, `processing.schemas[].schema_file`, legacy `processing.schema_file`) already
    route through `resolveSchemaRef`, whose config-relative branch **is** contained. So the remaining work is
    smaller than recorded:
    - `processing.grammar` (`PipelineConfigParser.java:857-861`) and `Asn1RecordIngester:96`
      (`ingester_config.grammar`) are **not** yet routed through it — the same `configDir` is available to the
      grammar branch and should reuse `resolveSchemaRef`.
    - The genuinely rootless case is the **in-memory draft** (`PipelineConfig.fromMap`, and
      `Asn1RecordIngester.ingest(File, RecordSink, int, PipelineConfig)` which receives a config, not a path).
      A draft has no directory by definition, so containment there needs a root supplied by the *caller* —
      which is the route/server layer, group (i), where a space root already IS in scope.
    - `resolveSchemaRef`'s **legacy working-directory branch stays unjailed** and is explicitly documented
      as not a security boundary. Closing that is the load-time enforcement this item is really about.
  - **A containment primitive already exists and should be reused, not rewritten:**
    `SafetyPolicy` + `ConfigSafetyValidator.checkPathValue`/`underAnyRoot`
    (`inspecto-config/…/config/safety/`) already normalize-to-absolute, `startsWith(root)`, and
    re-check symlink escape via `toRealPath()`. Two gaps: it is **advisory-only** (findings from
    `RunRoutes:168` / `EnrichmentRoutes:100` at authoring time, never called from
    `PipelineConfigParser`/`PipelineConfig.load`/any job task, so nothing enforces at load time), and
    its checked-field list **omits `schema_file` and `grammar`** — precisely the keys above.
    ⚠ It also **cannot simply be called from the parser**: `SafetyPolicy`/`ConfigSafetyValidator` live in
    `inspecto-config`, which `inspecto-etl` does not depend on (checked 2026-07-31). Either add the dependency
    deliberately or move the primitive down — W1b's containment check is a local few-liner precisely because
    of this, and that duplication should be resolved when this pass is scheduled, not left to drift.
  - **Do it as one pass or not at all.** Fixing one site in isolation is inconsistent and gives false
    assurance. Sequence when scheduled: extend the field list → make containment a
    resolve-and-fail-fast helper → thread a root into the config layer → apply at (i), (ii), (iii).
  - **No test today** exercises `../../etc/passwd`-style escape for `schema_file`/`grammar`, or
    `Asn1RecordIngester`'s grammar path. `ConfigSafetyValidatorTest` covers only the primitive.
- **`DatasetRelation.baseRelationSql` bypasses `SqlViews.reader`** (found 2026-07-30, Catalog lifecycle
  review): it hand-builds `read_parquet(<glob>)` with no options
  (`inspecto-engine/…/query/DatasetRelation.java:76-77`), while the engine-shared reader passes
  `union_by_name=true` (`inspecto-sql/…/SqlViews.java:51-62`). Consequence: a `physicalRef`-backed Dataset
  can fail on schema drift (columns added/reordered across partitions after a live schema edit) where a
  `view`-backed read of the SAME store silently unions by name. Fix = route it through `SqlViews.reader`
  (or add the option) + a mixed-schema partition test.
- **`SpacesService.reconcile` — ⚠ CONTAINED + INSTRUMENTED 2026-07-28, STILL OPEN, but the search space is
  now CLOSED.** ⚠ **Do not re-run the by-inspection hunt — all four candidate mechanisms in current source
  were positively ELIMINATED 2026-07-28:**
  1. **Backend envelope** — `Envelope.success` (`inspecto/…/control/Envelope.java:30-47`) *unconditionally*
     puts `data` **and** `metadata.apiVersion="v1"`, so an enveloped success can never fail `isV1Envelope`;
     a legacy (non-`/api/v1`) response is the bare array. Neither shape is a non-array object.
  2. **The deployed jar is NOT the culprit** — `javap` on `file-processor-deploy/file-processor.jar`'s
     `Envelope.class` shows the `apiVersion` constant present. The "stale bundle" suspicion named in the
     original finding is **disproved for the backend**; only the *UI* bundle remains unverified.
  3. **Interceptor order is correct** — `v1Interceptor` is first in `app.config.ts`'s `withInterceptors([…])`,
     as its own Javadoc requires (the mock short-circuits below it).
  4. **Idempotency replay cannot cross methods** — `Idempotency.keyFor` returns `null` for anything but
     POST/PUT/DELETE *and* keys on `method + path + header`, so a GET can never replay a POST's body.
  5. The **offline mock** returns a sorted array (`mock/handlers/spaces.handler.ts:43-45`).
  ⇒ The only surviving hypothesis is a **bundle/server version skew** (a UI bundle whose unwrap seam
  disagrees with the server that answered). Because that cannot be found by reading current source, it is
  now **instrumented instead**: `envelopeVersionSkew` + a `console.error` in `v1.interceptor.ts` fire when a
  body carries `data` **and** an object `metadata` whose `apiVersion` is not `v1` — naming the method, URL and
  the version seen. Deliberately narrow (legacy JSON with a bare `data` key must stay quiet) and deliberately
  **not** a widening of `isV1Envelope`, which must keep declining text/blobs/unknown contracts. This is
  **global**, not `/spaces`-specific: a declined unwrap was previously silent in every service.
  **Close this row when that error is seen once (it names its own cause) — or when the `file-processor-deploy`
  UI bundle is rebuilt and the symptom is confirmed gone.** Original finding follows.
  **`SpacesService.reconcile` is unguarded against a non-array `GET /spaces` body** (found 2026-07-27,
  `inspecto-ui/src/app/inspecto/api/spaces.service.ts`). `spaces.some(...)` runs on the raw response; the
  `catchError(() => of([]))` covers only a *failed* request, so a 200 with an unexpected shape throws during
  app bootstrap and cascades into an `NG0201` injector error. The same unvalidated value feeds the
  `availableSpaces` signal, so `currentSpace` (`.find`) and `showSwitcher` (`.length`) are equally exposed.
  ⚠ **Root cause of the observed non-array is NOT known** — the envelope contract was checked and is correct
  (backend emits `{data:[…],metadata:{apiVersion:'v1'}}`, `isV1Envelope` matches, and a live server built from
  current source returns a proper array). It did **not** reproduce on a freshly built server, which points at
  the stale `file-processor-deploy` bundle rather than current code. Adding the guard converts a crash into a
  silent wrong-state, so it is worth doing **but is not a fix** — do not close this by guarding alone.
- **⚠ Running the reactor MUTATES tracked sample-space TOON files** (found 2026-07-28). `mvn -o clean test`
  boots services against `spaces/`, and the write-back is **not byte-stable with the committed form**:
  `spaces/demo/space.toon` came back with its `description`/`created_at` values newly **double-quoted**,
  `spaces/default/config/registry/widgets/events_by_type.toon` came back **key-reordered with a `name:`
  injected**, and both lost their **trailing newline**. It also leaves new untracked files behind
  (`nav-menus.toon`, `branding.toon`, `registry/widgets/.history/`, stray `*_pipeline.toon`).
  **Two costs:** a shift cannot tell its own edits from test noise in `git status` (and this shift nearly
  committed two such files), and the TOON writer is not idempotent against what is checked in. **Fix the
  round-trip, not the samples** — re-committing the rewritten form just moves the diff. Until then: after a
  reactor run, `git checkout --` any tracked `spaces/**` file you did not deliberately edit.
- **`InvRoutes`' hand-escaped literal can now become a bind** (found 2026-07-28 while building Rule Template
  execution). `InvRoutes.java:192` carries the comment *"No bind-parameter support in
  `QueryExecutor.Request` — the value is a literal, SQL-escaped"*. That premise is **no longer true**:
  `Request` gained a `binds` component and `QueryExecutor` a `PreparedStatement` branch. Replacing that
  hand-escape with a bind removes a hand-rolled escaping site. Small, self-contained, and strictly a
  reduction in surface — but out of scope for the change that enabled it, so it is recorded rather than
  bundled. ⚠ Check for other hand-escaped literals at the same time; the comment suggests this was a known
  workaround, not a one-off.
- **`AiDraft.prerequisites` is modelled and rendered but never generically APPLIED** (found 2026-07-28 with the
  kpi host). `ai-draft.ts:107-129` populates it and `ai-assist.component.html:176-182` renders the sentence
  *"N dependent components will be applied first"*, but nothing applies them — each adopting pane must
  sequence the writes itself, and only `dashboard-editor.applyKpiReport` does. More importantly **`configDiff`
  ignores `prerequisites`**, so for `kpi_report_builder` the "review before apply" diff shows the **dashboard
  only** and the N widgets are invisible to review. Deliberately deferred: `kpi_report_builder` is the sole
  tool with prerequisites, so a shared helper would have exactly one caller. **Close this when a second tool
  gains prerequisites** — at that point extract the sequencing and make the diff cover them. — build it on the `$` namespace.**
  Persistence landed 2026-07-27 (`rule-template` component kind), but the engine half was never scoped.
  ⚠ **Two premises in the original row were wrong:** there is **no Java `RuleTemplate` at all** (it is a
  TS-only type, `inspecto-ui/src/app/inspecto/rule/rule-types.ts`; the backend treats `rule-template` as an
  opaque TOON blob through generic `/components` CRUD), and `:fieldValue` is **not a placeholder awaiting a
  binder** — it is a display string built by `compileSqlWithParams` (`inspecto-ui/…/query/query-sql.ts:94-96`)
  and consumed by nothing. So "wire up `paramSql`" was never the task.
  **DECISION (operator, 2026-07-28): reuse the existing seams — do NOT build a second bind namespace.** Add a
  Java `RuleTemplate` whose params **are** `$`-params resolved by `Parameters.resolve`
  (`inspecto-engine/…/query/Parameters.java:41-108`, which already does safe SQL-literal quoting), and execute
  through `DecisionRuleApplier` (`inspecto-engine/…/query/DecisionRuleApplier.java`), the codebase's existing
  "stored authored condition → predicate → run against DuckDB" path. Drop `:fieldValue` from the UI.
  ⚠ **Rationale to preserve: a second `:name` syntax would give the product two param languages** for
  operators to learn and for us to secure independently — and `$` is the one already hardened and audited.
  Route precedent = `DecisionRoutes:62/64` (`/simulate` on `canAuthorWorkbench`, `/apply` on `canOperateRuns`);
  `rule-template` has no dedicated routes class yet, unlike `decision-rule`/`expectation`.
- **`.claude/launch.json`'s `inspector-backend` classpath is hand-maintained and machine-specific** (rebuilt
  2026-07-27 after it had rotted to a single module and could no longer boot). It hardcodes
  `C:/sandbox/ucc-file-processor` and 9 module `target/classes` + 30 `.m2` jars. **It goes stale whenever a
  module's dependencies change** — regenerate with `mvn -o dependency:build-classpath -pl inspecto -am` and see
  the `PROJECT_NOTES.md` §4 note on why `*/target/classes` globbing breaks the edition model. A launcher that
  derives the classpath at run time (or uses the fat JAR) would retire this whole class of breakage.
- ~~**`tools/check-secrets.mjs` gives a FALSE RED on a local deploy bundle**~~ **CLOSED 2026-07-28** — the
  guard now enumerates **git-tracked files** (`git ls-files -z`) instead of walking the filesystem, matching
  its own name: a *committed*-secret guard should only read what is committed. It falls back to the walk
  outside a git checkout (a tarball export). `SKIP_DIRS` is now applied by path segment, since the tracked
  list cannot be pruned by refusing to descend. Verified both ways: exit 0 on a clean tree, and still exit 1
  on a planted `clientSecret = '<40 hex>'`. ⚠ **The `4.x` copy is unchanged and now DIVERGES** — the file's
  own header requires the two be identical. Port it. Original finding follows. It reports
  *"Committed-secret guard: 4 probable secret(s)"* against `file-processor-deploy/ui/chunk-*.js`, but that
  directory is **gitignored** (`.gitignore:44`) with **zero tracked files** — nothing is committed, and the four
  hits are minified library property assignments (`withCredentials`, `apiKey`), not credentials. CI is unaffected
  (a fresh clone has no such directory), but **any shift that builds the deploy bundle then follows the
  Definition of Done sees a red security gate** and may waste time or, worse, learn to ignore it. Fix: either add
  `file-processor-deploy` to `SKIP_DIRS`, or — better, matching the guard's own name — have it scan only
  git-tracked files. ⚠ Do not "fix" this by adding `secret-allow` to generated bundle files; they are rebuilt.
- ~~**`dependency:build-classpath` litters `cp.txt` into every module dir**~~ **CLOSED 2026-07-28** —
  `cp.txt` is gitignored.
- **D8 delivery-status residuals** (shipped 2026-07-26; each deliberately deferred, not forgotten):
  - **Auto-disable / suppression policy on hard bounce + complaint** — needs an operator call. This build
    *records* status; acting on it has a denial-of-notification blast radius, so it was never in D8's scope.
  - **Soft-bounce retry scheduling.** The hard/soft distinction is recorded; nothing retries.
  - **SES/SNS adapter** — needs SNS subscription confirmation plus a cert-chain fetch from a validated
    `amazonaws.com` URL. ⚠ An outbound fetch from an unauthenticated callback path deserves its own review.
  - ~~**Receipt retention/pruning is unwired.**~~ **CLOSED 2026-07-26** — new `receipt_prune` maintenance
    task (`MaintenanceJob`), `retention_days` required like every other prune, dry-run previews via
    `countPrunable`. Needed a `JobService.deliveryReceiptStore` hook: the store was wired into
    `NotificationService` only, so nothing in the job layer could reach it. Fail-open (no store attached ⇒
    no-op). ⚠ The in-memory store's oldest-first cap at 5000 is a **backstop, not retention** — it is
    unconditional on `add()` and unrelated to the age sweep; scheduling the task is still an operator act.
  - **Digest deliveries correlate to the digest, not per notification** — a bounce says the digest bounced,
    not which of its N notifications was in it. Inherent to per-delivery receipts; flagged via
    `DeliveryReceipt.digest`.
  - **`deliverWithReceipt` SPI escape hatch stays unbuilt** — only needed if a provider is ever adopted that
    will not echo our `Message-ID`.
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
- ~~**D7 — assignment UI for non-object targets**~~ **COMPLETE 2026-07-27 — every kind adopted.** The
  kind-agnostic `TagAssignmentDialog` (`inspecto/tags/tag-assignment.dialog.ts`) is the shared surface —
  it takes `{targetKind, targetId, label}` as data and persists through `TagsService`'s assignment edges,
  so a further adopter is **a menu item, not another dialog**. First adopter wired: the **Link Analysis
  saved-views menu** (`link-analysis-view`), following the D10 Comments idiom. Verified end-to-end offline:
  tagging a saved view makes it appear under that tag in the `/tags` pane beside object targets.
  **ALL FIVE mock-whitelisted kinds are now adopters (2026-07-27)** — `link-analysis-view`, `geo-map-view` (its flat
  saved-views menu was refactored to the same per-view `#viewActions` submenu), `dataset` and `dashboard`
  (a Tags icon button in the card action cluster; datasets keeps the sibling `writesDisabled()` gate,
  since assignment is a write), and **`widget`, which needed a migration first — see the row below.**
  Verified offline: `finance` lists dashboard + dataset + object, and `network` lists geo-map-view +
  link-analysis-view + object + widget. ⚠ Deliberately **not** merged with the
  mail pane's `TagDialog`: that one is bulk/tri-state and persists via the `attributes.tags` CSV, and one
  dialog straddling both persistence paths is how the phase-2 split-brain returns.
- ~~**D7 — `widget` needs a call**~~ **CLOSED 2026-07-27 — call (c) built end-to-end.** `WidgetConfig.tags`
  is now a **projection** of the assignment store, so the widget card is the fifth `TagAssignmentDialog`
  adopter and there is no second tag system on it. New `WidgetTags` (`com.gamma.control` — at the **edge**,
  like D6's findings gate, because the engine has no `ComponentStore`) re-derives the array on every path:
  component write, assign/unassign, rename/delete (both now report a `widgets` count), bundle import, and a
  per-Space backfill. The save dialog's comma field is **gone** (it was a second writer). ⚠ Load-bearing:
  **adopt on create, overwrite on update** (a create carries bundle/seed tags; an update must not resurrect
  a removed tag) · the backfill is **lazy, once per Space, not at `register()`** — `register` runs before any
  Space is hosted, so `api.service()` there throws `IllegalState No spaces are hosted` and `ControlApi`
  fails to construct (26 test errors caught it) · a no-op projection **writes nothing**, since every
  `ComponentStore.write` archives a version · the widget card **reloads on dialog close**, unlike the
  dashboards adopter, or the chips stay stale. Plan archived. → `okf/backend/control-plane/tags.md`
- **D6 — no spec-authoring UI.** A `findings-spec` is authored as TOON through the generic `/components`
  CRUD, exactly as `notification-rule` shipped backend-only. A matrix/editor is a separate item; nothing is
  broken without it. → `okf/frontend/features/objects.md`
- ~~**D6 — Findings *values* are not validated server-side against the spec.**~~ **CLOSED 2026-07-26** —
  `FindingsSpec.validateValues(submitted, merged)` + a `validateFindings` gate on `PATCH /objects/{id}`
  (→ 422). Enforces `select` membership, `number` + `min`/`max`, `boolean`, and `pattern`; skips a section
  hidden by its `dependsOn`. ⚠ **Two premises in the old wording were wrong, and the shipped rules differ
  accordingly:** (1) *"a key no section declares"* **cannot** be rejected — `attributes` is a shared bag
  also carrying `tags`/`caseType`/`dueAt`, so an undeclared key is indistinguishable from a non-Findings
  attribute; what is enforced is that a **declared** key holds a renderable value. (2) It is **not** in
  `ObjectService` — the spec lives in the space's `ComponentStore`, an edge concern, so the gate is in
  `ObjectRoutes` and the engine stays store-agnostic (`effectiveFindingsSpec` was extracted from
  `findingsSpecOf` for reuse). Also load-bearing: nothing is judged unless the patch **touches a declared
  key**, and `required` is judged against the **merged** bag — otherwise a tag write starts 422ing on an
  incomplete triage form. `autocomplete` options stay suggestions, never a closed set.
- **D6 — the spec vocabulary is defined by a frontend file.** `AttributeSpec`
  (`inspecto-ui/.../component-model/attribute-spec.ts`) is the canonical shape the backend `FindingsSpec`
  mirrors and validates against. Deliberate — the alternative was a third schema plus a lossy mapper — but a
  new `AttributeType`/`AttributeTier` must be added in **both** places or the backend will 422 a section the
  renderer could actually draw. The backend validator is what keeps this safe.
- ~~**`NoteTargets` is now misnamed.**~~ **CLOSED 2026-07-27** — renamed to **`com.gamma.ops.AnnotationKinds`**
  and moved out of the note package, since it has been the shared annotation-target vocabulary for notes
  (D10) and tags (D7) since D7 shipped. Purely mechanical: every reference was an import / qualified name /
  Javadoc `{@link}` — no reflection, no `Class.forName`, and **no wire format touched** (the JSON/TOON field
  was already the neutral `targetKind`, and the SQL column is `target_kind`). ⚠ It carries
  `@PublicApi(since="4.9.0")`, so the rename is **breaking for a library consumer** and shipped as `feat!:`
  — deliberately taken **inside** the already-MAJOR release window rather than deferred, because after the
  release the same rename costs a deprecation cycle. No forwarding alias: the release is major, and an alias
  would leave the misleading name readable for another cycle, which is the thing being fixed.
  ⚠ **Do not merge it with `com.gamma.control.AnnotationTargets`** — near-identical names, different
  questions: the engine class is the *kind vocabulary*, the control-plane class is the *per-target
  authorization gate*. The split is what keeps the engine identity-agnostic.
- **D7 — the startup backfill is a full object scan. REVIEWED 2026-07-27, deliberately still not fixed.**
  Re-confirmed against the shipped code: it is idempotent, lazy once-per-Space, and O(objects) at
  human-scale Incident/Case volumes, not telemetry volumes. A persisted "migrated" marker is the fix and
  remains **cheaper to add later than to carry now** — it adds a durable state file whose staleness becomes
  its own failure mode (a marker written before a partial backfill would suppress the repair). Closing
  trigger unchanged: it shows up in measured startup time.
- ~~**`ObjectStore.delete` — reserved seam**~~ **RETIRED 2026-07-27: MNT-14 consumed it**, exactly as this
  row's stated closing trigger required. It is now called by `ObjectService.purge`, behind the dry-run-first
  sweep and the legal-hold exemption D5 demanded. There is still no `DELETE /objects/{id}` and hard-delete
  is still not generally supported — the retention sweep is the one caller. Original rationale, kept for
  provenance:
  `12cf20eb` added `void delete(String)` to `com.gamma.ops.ObjectStore` (`:50`) with both impls and unit
  tests; it still has **no production caller and no route** — there is no `DELETE /objects/{id}`, and
  Incidents/Cases are only closed, merged, or split. Dropping it is wrong because **MNT-14's purge needs
  exactly this API** (D5 retention tier: expiry makes an archived object purge-eligible, and purge is a
  real deletion). Wiring it now would ship an unbounded hard-delete ahead of the dry-run-first sweep and
  the legal-hold exemption that D5 requires. The seam is therefore **documented in place** as reserved-for-
  MNT-14, which closes the actual risk (a reader assuming hard-delete is generally supported and building
  cascade logic against it). Row closes when MNT-14 consumes it.
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
  can go unresponsive, and chunking is now the only bound on a pathological file. ~~**To reopen, bring a
  measured value**~~ — **MEASURED 2026-07-27, see the row below.** A semaphore-computed cap stays rejected
  (`maxConcurrentRuns` defaults to unbounded; batch-ingest has a second limiter). Spill *routing* already
  ships (`scratchDir` → `dirs.temp` on the data volume); `max_temp_directory_size` has no fixed default
  because none is defensible without the volume size. Read-path is **not** the risk (see C6 below).
  `okf/backend/engine/duckdb.md`
- **D11 — MEASURED 2026-07-27; the number is `2GB`, and one of D11's premises is WRONG.** The blocker was
  "no defensible value", so a value was measured (host: 32 GiB RAM, DuckDB 1.5.2.1; CDR-shaped 12-column
  CSV; harness kept out-of-repo, method reproduced in the OKF doc). Findings, in order of importance:
  - ⚠ **Peak does NOT scale with file size — the "pathological single file" premise is wrong for the
    ingest path.** `read_csv_auto` → `COPY … TO parquet` is fully streaming: a **1.0 GiB** input peaked at
    **1081 MiB** and a **3.1 GiB** input at **981 MiB** — i.e. flat, not proportional. So a huge file is
    *not* what exhausts memory, and **D12 chunking was never really the bound on memory** it is described
    as (it bounds scratch/unit-of-work, which is still worth having).
  - ⚠ **What a cap actually governs is the blocking operators, and they HARD-FAIL rather than spill.**
    A 9M-group `GROUP BY` peaked at 937 MiB and a wide `DISTINCT` at 895 MiB; at `512MB` **both died with
    `Out of Memory Error`** after spilling only ~192 MiB. Graceful degradation is NOT the failure mode, so
    **an aggressive cap converts working jobs into failing ones** — this is the trap that makes "just set
    it low" wrong. (`ORDER BY` ~200 MiB and a self-`JOIN` ~385 MiB are cheap; they are not the constraint.)
  - **`2GB` is the defensible default**: ~2.2× the highest peak observed across every probe (1081 MiB),
    above the OOM cliff with real headroom, and it costs **nothing measurable** — capped runs came in at or
    slightly *faster* than uncapped (ingest 3741 ms @ 2GB vs 4928 ms uncapped; `GROUP BY` 1711 vs 2123 ms).
    `1GB` also passed everything but sits only ~1.3× over the observed peak — too close to the cliff.
  - ⚠ **`memory_limit` alone still does not bound total exposure, so shipping it alone does not close
    D11.** Total = `memory_limit` × concurrent runs, and **`-Djobs.maxConcurrentRuns` defaults to `0` =
    unbounded** (`JobService.java:134-140`, whose own Javadoc already calls itself "root enabler for an
    eventual on-by-default DuckDB memory cap"). The pair is the fix: `memory_limit=2GB` +
    `maxConcurrentRuns=4` ⇒ ≤8 GiB worst case (~25% of a 32 GiB box) instead of ~25 GiB *per run*.
  - **Not measured, so not claimed:** scaling with thread count / core count (DuckDB sizes per-thread
    buffers, so a much larger box may want more than 2 GiB), non-CSV frontends, and the `materialize`
    task's real query shapes. A per-edition or RAM-relative default was not evaluated.
  - **Still an operator call** — this row brings the number D11 was declined for; it does not ship it.
- **MNT-14 archived-Incident retention sweep — ✅ COMPLETE 2026-07-27, plan archived.** All five gaps
  closed; as-built in [`okf/backend/control-plane/jobs.md`](okf/backend/control-plane/jobs.md)
  (`incident_purge`). Residual open items only:
  - **Operator-facing retention docs** — the runbook (`docs/ops/backup-restore-runbook.md`) does not yet
    carry a retention section. ⚠ It must state the G3 stance explicitly: **the append-only event trail
    survives a purge, so "purge" never means "all trace removed"**. That is the first question a legal/DPA
    reviewer asks, and the code says it while the operator docs still don't.
  - **No UI surface and no shipped Job instance** — the task exists and is reachable by config, but nothing
    schedules it and the Scheduler UI has no retention affordance. Deliberate: an operator should opt into a
    destructive sweep, and the MNT-13 nightly-chain template is the natural host when someone wants one.
  - **Retention is derived, not stamped** (`closedAt + retention_days`), so shortening `retention_days`
    retroactively makes older records eligible — the sweep does not honour "what was promised when this was
    archived". Accepted consciously; becomes a stamped attribute if that guarantee is ever required.
  - **Not generalised beyond Incidents.** `ARCHIVED` is only in the INCIDENT workflow today; the task is
    scoped to `ObjectType.INCIDENT`. Widen when a second type gains a terminal archive state.
  - ⚠ **Two premises in the original scoping were WRONG — a pattern worth remembering.** (i) "The blocker is
    building the `Archived` state": it already shipped. (ii) "G4 needs four `JobService` store hooks,
    fail-CLOSED on partial attachment": **no new hooks were needed** — `JobService.objects(ObjectService)`
    already existed and was already wired, and `ObjectService` holds all four stores as non-null final
    fields, so **there is no partially-attached cascade to fail closed on**. Do not add per-store hooks
    beside `objects()`; that would reintroduce the half-cascade hazard the current shape rules out.
  - ⚠ **`ObjectQuery`'s 9-arg constructor is load-bearing** — the only reason widening the record was
    non-breaking. Positional callers depend on it; do not tidy the overload away.
  - ⚠ Three `@PublicApi` store interfaces gained **abstract** methods ⇒ `feat!:`. A **third** independent
    reason the next release is MAJOR, alongside D15's required `-Dauth.oidc.tokenEndpoint` and D4's
    `DELETE /spaces/{id}?purge=true` 409. **A fourth landed 2026-08-04**: `61dc8280` `feat(pipeline)!` —
    file dedup folded into the acquisition node, `transform.dedup.fingerprint` config key removed. Pushed
    to `origin/master`; live and undocumented for clients until release notes are actually written at the
    next tag cut. **A fifth landed 2026-08-07**: `e8a8a755` `feat(jobs)!` — a Job's `deduce:`/`bind:`
    value naming an *unregistered* `$`-token now fails the Run **REJECTED** instead of falling through to
    the next parameter layer, so a declaration carrying a misspelled token that was quietly running on its
    `defaultValue` is now rejected until corrected (job-parameter-contract §6.3; the whole point is that
    the old behaviour hid the typo). No shipped built-in is affected — every built-in `deduce:` value
    resolves through `BuiltinExpressions`. Release notes must also mention the `$$` escape: in a
    `deduce:`/`bind:` position `$$x` now yields the literal `$x`. **A sixth landed 2026-08-07**:
    `8504b782` `feat(jobs)!` — authored Job parameter values (trigger `args` and the `params:` block) are
    now **evaluated**: a value that is a whole `$`-token resolves at fire time instead of passing through
    literally, and one naming an unregistered token fails the Run REJECTED. Values that merely *contain* a
    `$` are unaffected, and `$$`/`${ENV:…}`/`$100` are literals by grammar. Release notes should pair this
    with the `$$` note above — they are one story for an operator. **A seventh landed 2026-08-10**:
    `feat(consignment)` — `consignment.outputs.backend` now defaults to `duckdb` (addressing D1). Not
    breaking by config (`none` still honoured, and every space just gains a small DuckDB file), but
    **operator-visible**: `ReprocessCommand` now *refuses* to reprocess a Consignment whose output a
    compaction merged away. That reprocess previously succeeded **while silently duplicating rows**, so the
    refusal is the fix — but an operator who has been doing it will see a new failure. Release notes must
    say what to do instead: rewrite the whole partition (§6.2), or set `min_age_days` beyond the reprocess
    horizon so compaction never overtakes it. **An eighth landed 2026-08-10**: `feat(consignment)` —
    addressing step 6. A full pipeline recompute's sink files are now named `<pipeline>_<batchId>` instead
    of a stable `<pipeline>`, so **output file names change** for anyone who parsed them, and a recompute
    writes a new revision rather than overwriting. Release notes must lead with the operational
    consequence: **define a `retire_superseded` maintenance job**, or every full recompute leaves a
    complete extra copy of its output on disk permanently (retention is never on by default here, and this
    is the one retention task whose absence is a growth bug rather than just a longer history).
    **A ninth landed 2026-08-10**: `fix(pipeline)` — a sink `partitions[]` entry declaring no `column`
    (or a blank one) now **fails the sink branch** instead of stringifying the whole entry into a
    partition directory literally named `{source=TXN_DATE}`. Filed as a fix, not `feat!`, because no
    layout that broken could have been depended on — but it is a config that "ran" yesterday and stops
    today, so release notes should say it plainly. The sink component preview warns on the same
    declaration at authoring time, alongside new warnings for a partition `source` that names no column,
    is blank, is not a plain identifier, or disagrees between entries — each of which silently costs the
    store its event-time bounds.
    **A tenth landed 2026-08-10**: `feat(jobs)` — the job-parameter-contract §5-B / step 17 change.
    Two operator-visible edges, both small: the `GET /jobs/{name}/runs/{runId}/artifacts` and
    `…/artifacts/latest` responses **no longer carry a `timeRange` key**, and the Expression attr
    `$upstream(<job>).artifact(<name>).time_range` is **gone**, replaced by `event_time_min` and
    `event_time_max`. Release notes can be brief and should say why nobody is affected in practice: the
    field was written as a literal `null` at every recording site since it shipped, and the attr could
    never be bound to anything — one opaque `"<min>..<max>"` string that the SQL substituter inserts
    whole and nothing split. The replacements resolve **live** from the Consignment output registry, so
    they answer for the current revision rather than a frozen one, and they yield **strings** (the
    registry stores zone-less local date-times, valid as SQL timestamp literals but rejected by an
    `INSTANT`-typed parameter). Only a pipeline Job's sinks report a range; the other Job Types write no
    registry rows and answer unknown, which is honest rather than a gap.
    **An eleventh landed 2026-08-10**: `build(pom)` — every reactor **artifactId** was renamed
    `file-processor-*` → `inspecto-*` so it matches its directory (`inspecto-api`, `inspecto-util`, …;
    parent `inspecto-parent`; the core is `inspecto-processor`, since `inspecto` would collide with the
    aggregator directory). `groupId` (`com.gamma.inspector`) and the version are unchanged. Operator-visible
    only to someone **building from source or depending on these coordinates** — anyone consuming the
    shipped bundle sees nothing, because the deployment file name **`file-processor.jar` is deliberately
    unchanged** (`serve.sh`, `run-example`, `docs/EDITIONS.md` all still reference it); only the artifact in
    `inspecto/target/` is now `inspecto-processor-<version>.jar`. Release notes should carry the coordinate
    table for downstream poms. Renaming the bundle itself is a **separate, unmade decision** — do not treat
    it as leftover work.
    **A twelfth landed 2026-08-10**: `feat(jobs)` — a new built-in Job Type **`mail.send`** appears in the
    type picker, and a new Platform Service id **`mail`**. Operators can now schedule an email to named
    recipients; it sends through the existing `notify.smtp.*` channel, so a deployment with no SMTP
    configured gets a Job that reports "no email channel configured — nothing sent" rather than failing.
    Release notes should say that plainly, because "SUCCESS, nothing sent" is otherwise a surprising read.
    ⚠ **Deferred with it — `mail.send` has no true CC.** `NotificationChannel.deliver(n, target)` takes one
    recipient list, so `cc` addresses are appended to it and arrive as ordinary addressees (visible to
    everyone in To). The declaration keeps `cc` because the field is real and the §9 contract demo needs it;
    closing the gap means widening a `@PublicApi` SPI with a CC-aware overload, which is not worth doing for
    one caller until a second asks. **Do not "fix" this by having `mail.send` open its own SMTP connection**
    — one mail transport, one place.
    **A thirteenth landed 2026-08-10**: `feat(jobs)` — every expression-accepting job parameter grows a
    **token picker** listing the runtime vocabulary with each token's resolved preview, so `$`-tokens stop
    being undiscoverable (job-parameter-contract step 13, plan §8.5). Release notes should say the preview
    is the **server's** evaluation, not a client guess, and that a token replaces the field's *whole* value.
    ⚠ **Deferred with it — an INTEGER/DECIMAL parameter has no reachable token.** `widgetFor` maps those to
    a native `type="number"` input, which cannot display `$now.epoch_seconds`: the control would hold the
    token while the field read as blank, so the picker is deliberately withheld there — and the token cannot
    be typed in either, for the same reason. `$now.epoch_seconds` / `$now.epoch_millis` are therefore
    UI-unreachable (still authorable in a config file). Closing it means a numeric widget that can hold a
    token — a text input that validates numerically, plus a decision about what the spinner affordance
    becomes — which is a renderer change, not a filter tweak. **Do not "fix" it by offering the picker on
    the existing number widget**: that produces a field that looks empty and saves a token.
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
| ~~MNT-14 archived-Incident sweep~~ | **RETIRED 2026-07-27** — shipped as the `incident_purge` maintenance task; plan archived, as-built in `okf/…/jobs.md`, residuals in §6. ⚠ Its two long-standing framings were both WRONG: the `Archived` state was never the blocker, and G4's four `JobService` hooks were never needed |
| Parser field tiers | D13 (parked) · §5 UI attribute tiers · interview #2 |
| Generic tag system | D7 (rescoped) · **COMPLETE end-to-end 2026-07-26**, plan archived; residuals only (§6) · `okf/backend/control-plane/tags.md` · ⚠ supersedes the old §4 "`category`/`tags` params on `GET /objects`" row — the *existing* `Tag`/`TagRule`/`/tags` system is what gets generalized, not a separate feature. Keep aligned with **D10** (generalized notes): same `(kind, id)` addressing problem, so one adopts the other's scheme |
| ~~Configurable Findings sections~~ | **RETIRED 2026-07-26** — D6 shipped end-to-end (`findings-spec` kind + `GET /findings/{type}`); plan archived, as-built in `okf/frontend/features/objects.md`, residuals in §6 |
| ~~Delivery-status webhooks~~ | **RETIRED 2026-07-26** — D8 shipped end-to-end (`DeliveryStatusAdapter` SPI + two adapters + the fail-closed callback route); plan archived, as-built in `okf/…/events-metrics.md`, residuals in §6 |
| ~~Saved-view sharing~~ | **RETIRED 2026-07-26** — §2 D9 shipped end-to-end (Exchange `kind` axis carries `link-analysis-view`, backend + UI + offline mock); as-built in `okf/…/exchange-sharing.md`, link-analysis-side notes in `okf/frontend/features/link-analysis.md`. No residuals |
| ~~Geo-map Phase 4 backend~~ | **RETIRED 2026-07-25** — the endpoints + `ComponentStore` widening shipped; only the `spatial` extension + `spatial` QueryType survive, tracked in their own §4 rows |
| ~~INV-1 Entity Projection backend~~ | **RETIRED** — shipped |

---

**Maintenance rule:** when an item ships, mark it in its *source* doc first (that stays
authoritative), then **delete the row here** — do not leave a strikethrough as-built narrative behind
(that is what the OKF concept docs and git history are for; this page had grown to ~40k tokens that
way before the 2026-07-25 compaction). New pending items discovered mid-shift get a row here at
handoff time (see the `handoff` skill). This page lists **open work only** — no DONE rows.
