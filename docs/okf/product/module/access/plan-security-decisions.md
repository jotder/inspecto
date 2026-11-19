---
type: Capability
title: Security (SEC) — Decisions, not built, refused and as-built pointers
description: Part 3 of 4 of Security (SEC): Decisions; Not built; Refused & superseded; As-built mechanism (pointers only)
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
---

# Security (SEC) — Decisions, not built, refused and as-built pointers

Part 3 of 4 of [Security (SEC)](plan-security.md) · previous: [Specification](plan-security-specification.md) · next: [Verification](plan-security-verification.md).

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### Posture and vendor neutrality

| Date | Decision | Why |
|---|---|---|
| 2026-06-16 | **Remove all auth from the common core**; Personal is auth-free, not auth-disabled | Auth code in the core is auth code in every bundle; the hand-rolled token plane was also the one that leaked (below) |
| 2026-07-06 (W6) | Re-add auth through **SPIs + a profile-gated module**, never a flag | A module Personal never bundles cannot be re-enabled by a mis-set flag; the SPI keeps the core honest |
| 2026-07-23 (operator) | RBAC = Standard, policy engine = **Enterprise**, Personal stays fail-open | Edition placement follows blast radius; the Personal audience is single-user |
| 2026-07-23 (operator) | ABAC is a **hand-rolled, dependency-free** engine — no OPA / rego | The backend is framework-free and builds offline (`-o`); a lean tree is a compliance asset |
| 2026-07-25 (**D15**) | **No IdP or gateway vendor of record** — the "Keycloak + WSO2 APIM vs WSO2 IS" question is *withdrawn*, not answered | The client's IAM is the client's; the product stays configurable. Litmus test: any auth code that cannot be pointed at a different compliant IdP by configuration alone is wrong |
| 2026-07-25 (D15) | `KeycloakTokenRelay` → **`OidcTokenRelay`**; `auth.oidc.tokenEndpoint` **mandatory with no default** | The old default derived one vendor's path layout; there is no discovery fetch to replace it (this module configures `jwksUri` explicitly by design). ⚠ Deployment-breaking for anyone who relied on the derived value |
| 2026-07-26 | `endSessionUrl` is **declared config, never discovered** | The D15 precedent applied again; a hardcoded IdP host in the SPA is what caused the secret leak. The BACKLOG row asked for `/.well-known` discovery and contradicted the precedent it cited |
| 2026-10-13 | **Provider preset + optional discovery** (`-Dauth.oidc.provider`, `-Dauth.oidc.discoveryUrl`, `OidcConfig`) supersede the two rows above for an operator who opts in; `generic` still derives nothing, and the server publishes the result in `/bootstrap` `auth` | The client chooses WSO2 Identity Server or Keycloak by configuration; the `keycloak` path layout applies only when asked for, so D15's ban on a vendor default stands (§6.2) |
| 2026-09-07 (operator) | Edition gating is **ServiceLoader modules for all six EDG-01 cells**, ⛔ not `-D` switches | Personal ships no authenticator and binds every interface; a switch leaves the code in the bundle. `/metrics` in `PUBLIC_PATHS` had served telemetry unauthenticated |

### Authorization model

| Date | Decision | Why |
|---|---|---|
| 2026-07-03 | Authorization is always asked as a **named Capability**, never "which Lens is active" | A Lens is self-selected; a Role is assigned. One new name per distinct authorization question; never reuse one because its value happens to match |
| 2026-07-08 | **Data-scoped grants** by `caseType` vs `Subject.dataScopes`, `404` on out-of-scope | Attribute scope over ACLs; event/audit streams stay capability-gated so scoping cannot hide operations |
| 2026-07-22 (product) | **`canOnboardConnections`** is its own Admin grant, not part of `canAuthorWorkbench` | Connections are the credential + egress surface; a Pipeline Developer builds against existing connections but cannot mint new ones |
| 2026-07-23 (R1) | Role **definitions are data** (`roles.toon`); assignment stays in the IdP; unreadable file ⇒ **all grants suspended** | Authorable without a redeploy; fail-closed because a half-read grant table is worse than none |
| 2026-07-23 (R2) | Access-Profile denies are enforced **at authentication**, stripping capabilities before the `Subject` exists | Zero route changes; the `Subject` stays capabilities-only; `/bootstrap` reports effective grants. Supersedes the plan's separate authorize middleware |
| 2026-07-23 (R3) | Sharing is an **optional envelope**; absent ⇒ byte-identical | Every existing document is unrestricted without migration |
| 2026-07-23 (R4) | The **capability manifest** is the single source of truth, tested in both directions | A gate the manifest does not know about, or vice versa, is drift the compiler cannot see |
| 2026-07-23 (R0) | Gateway assertions are trusted **only when signed and only when no Bearer resolves** | The X-Actor lesson: header identity is spoofable |
| 2026-07-24 (A3) | A policy **allow never bypasses** a capability gate | Defence in depth; the plan's combining order was tightened on purpose |
| 2026-07-24 (A4) | Space isolation is **two engine seeds** overlaid by name, engaging only when a `space` claim is mapped | The auth-free core must never write Enterprise policy text; an unmapped claim must not brick the API |
| 2026-07-24 (A5) | Audit every policy deny and route-level allow; **never** a row-level allow | A row allow fires per surviving row and would flood list reads |
| 2026-07-24 | `GET /access/explain` is a **GET** | A POST is a `write` the policy under test could `403` |
| 2026-07-24 (product) | `canTriageRequirements` seeded to Business + Power + Admin + Super | Triage is a business-analyst activity; builders and operators build and run |
| 2026-07-25 (**D14**) | R1 seed set ratified; **`canOfferDatasets` tightened to admin/super** | Offering a Dataset cross-space is a data-exposure decision with no second gate; `canConfigureAccess` / `canApproveShares` were already admin-only, so the "bootstrap deadlock" concern was unfounded |
| 2026-07-25 (**D4**) | **`canCurateMenus`** split out of `canAuthorWorkbench`, seeded admin/power/super | Editing a pipeline and changing what every business user sees are different activities |
| 2026-07-25 | `LensService` capabilities split into **identity** vs **lens-scoped** | The whole admin seed held neither lens-qualifying capability and was snapped to read-only Business — a bootstrap deadlock |
| 2026-07-26 | `canTriageRequirements` is **identity by operator call**; "Business lens ⇒ read-only" is no longer true | It is the `business` seed's only grant; lens-scoping it revoked the role's single capability |
| 2026-09-26 (operator) | **Narrow postmortem + category routes on `canWorkIncidents`** (`INCIDENT-FINISH-GATE-1`): `PUT /objects/{id}/postmortem` and `PUT /objects/{id}/category` each write one attribute and refuse any other key (422); the `PATCH /objects/{id}` stays `canAdminister`. A signed-in comment's `author` is the Subject, never the body (`CASE-UI-GATE-LEFTOVERS-1`) | An analyst who may move an Incident must be able to finish it without being handed the whole field editor; a body field must not re-attribute a Subject's act. Detail: `incidents.md` §4 |
| 2026-09-07 | The **capability vocabulary is static across editions** | Deriving it from registered routes would make a Standard-authored role file fail validation on Personal |
| 2026-09-08 | **Audit read stays in core** in every edition via `AuditLogRoutes` (`/audit/search`, `/audit/export`), fail-closed so it cannot become the events feed | `EDITIONS.md` promises Personal an audit log; gating the events module would have removed it |

### Session and incident

| Date | Decision | Why |
|---|---|---|
| 2026-07-06 (W6d) | **BFF**: refresh token in an httpOnly `SameSite=Strict` cookie + `Origin` check | The browser never holds a refresh token; the `Origin` check is the CSRF defence |
| 2026-07-25 | The SPA is a **public PKCE client**; every client secret deleted from source and environments | Five secrets had been public in git since 2026-06-12 (SEC-INCIDENT-1). "Verified by compiler and jsdom, never by an IdP" — `ce49a681` had to add real `state` validation afterwards |
| 2026-07-26 | Sign-out is **three layers**, `id_token_hint` omitted | Without RP-Initiated Logout the next sign-in completes with no credential prompt; the id token lives behind the BFF |
| 2026-10-13 | The hint is sent after all: the BFF keeps the ID token in the HttpOnly `inspecto_idt` cookie and `POST /auth/logout` returns the end-session URL with `id_token_hint` | Keycloak shows a confirmation page without the hint; the token still never reaches the SPA |
| 2026-07-26 | **No user menu on Personal** | Nothing to name, nothing to end |
| 2026-08-17 (operator) | Branch `4.x` deleted; the incident fix commits live on `master` | Nothing had shipped on `4.x` |
| 2026-08-29 | **SEC-INCIDENT-1 closed by decommission** | The affected system was decommissioned, so no issuer entry remains to rotate; the values stay in public history and are immaterial only because none is in use |
| 2026-09-02 (operator) | **SEC-08 masking / classification-driven row scope is Enterprise-only** | Classification (SCH-03) and row scope both exist; the join is Enterprise build work |
| 2026-09-06 | Secrets: `${ENV}`/`${SYS}` everywhere, `${FILE}`/`${KEYSTORE}` by module, **Vault/KMS on client demand only** | A Personal bundle refuses the module-backed schemes with a message, never a silent null |
| 2026-09-07 | `-Dauth.oidc.audience` is **warn-only** | Recorded in `docs/okf/product/module/oidc/architecture-oidc.md`; a hard fail would brick deployments whose IdP omits `aud` |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| ✅ ~~**Policy-authoring UX**~~ **CLOSED 2026-09-27** | was `BACKLOG.md` §3.8 *Security: policy-authoring UX*; `EDITIONS.md` SEC-05 | Guards, impact matrix and editor shipped 2026-09-25 (§3.10 *Authoring guards*); the browser pass over the Policies-tab editor ran 2026-09-27 and closed the row |
| **Vault / cloud-KMS secrets** (GAP-6 / SEC-8) | no open row: the `BACKLOG.md` decided note "SEC-07 Vault/KMS only when a client policy requires it" (`DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1`, the row once named here, closed 2026-09-27 and was about DuckDB memory) | Enterprise-only, **only when a client policy requires it**. The `SecretsProvider` SPI is the seam; nothing else is designed |
| **Data masking / row scope by field classification** | `EDITIONS.md` SEC-08; `ProcessorCatalog` `quality.pii.mask` (DELIVERED 2026-10-06, `transform.mask`) + `quality.compliance.redact` (`Status.PLANNED`) | Enterprise-only by decision. The `mask` Step is built and gated through `EditionFeatures.PII_MASK`, which only `inspecto-policy` declares ([step catalog](../elt/spec-step-catalog.md)). Dataset-wide masking at read time is not built and has no BACKLOG row of its own. Link Analysis entity-id masking by classification is built and ships with `inspecto-geo-link` (not Enterprise-only) |
| ✅ ~~**SEC-INCIDENT-1 carry-forwards**~~ **DONE** | no board row left | The off-repository pre-rewrite bundle was deleted 2026-08-01 and the no-reuse check completed 2026-08-02; the incident closed by decommission 2026-08-29, recorded in `compliance/controls-matrix.md` CC6.1 ([plan-compliance](../../layer/platform/plan-compliance.md)). The old "internal hostnames still in-repo" clause has no record either way |
| **Compliance program NFR-7** (G8 evidence, pen test, FIPS leg) | `BACKLOG.md` §2 *Compliance program (NFR-7)* | `CMP`'s, listed here because the controls cite SEC mechanisms as evidence |
| **Enterprise distributed tier** — per-tenant ABAC beyond Space isolation | `BACKLOG.md` §2 *E1 Enterprise distributed tier* | Design only |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-NOPROOF-1`.
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `permissions[]` in the v1 envelope **ADOPT** — the shell interceptor stops discarding the envelope FIRST, then affordances gate on it → `CLIENT-HALVES-1`. ⚠ Reversed the same day by measurement: adoption is **reserved, not scheduled** (§8.7), and the row was deleted 2026-09-14 once its other halves shipped ([plan-control-api](../processor/plan-control-api.md) keeps the record).
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **"Module present, auth off" is impossible** (`SEC-SIDECAR-BOOT-1`) | `auth-security.md` §"does not boot"; `SpiSlot.active()` has no guard | A Standard bundle cannot be smoke-tested without an IdP. Needs a guard around SPI resolution, not a flag — and an operator call on whether that mode should exist at all |
| **`authMode` is declared, not derived** | `BootstrapRoutes.features`; the launcher here-strings in `package.ps1` | The SPA's login mode and the server's enforcement agree only because the launcher sets both. Deriving `authMode` from `Authenticators.active()` would close it in one line — but changes the contract of a public path |
| **`permissions[]` has no consumer** | `v1.ts` declared; zero reads in `src/app` (re-checked 2026-11-17) | Either the UI adopts it for per-resource affordances (the SEC-7b intent) or the field is documented as reserved. Today it is neither |
| ✅ ~~**Server-published OIDC config**~~ (`bootstrap.auth.*`) **BUILT 2026-10-10** | `TokenRelay.bootstrapAuth`, `OidcTokenRelay.bootstrapAuth` | The server publishes `authorizeUrl`, `clientId`, `scopes` and `endSessionUrl`. The `??` hazard does not bite: `OidcTokenRelay` omits a key whose value is blank, and publishes nothing without an authorize URL |
| ✅ ~~**HTTPS has no test**~~ **CLOSED 2026-09-09** | `ControlApiHttpsTest` — 5 tests | SEC-4 now has proof, including the fail-closed half: a broken keystore must throw, never serve cleartext on the TLS port. Mutation-proven (a silent fallback fails 2 of 5) |
| **`Subject` and `SecretsProvider` lack `@PublicApi`** | `Subject.java`, `SecretsProvider.java` (still true 2026-11-17) | Both are implemented outside the core; the marker records intent and is inconsistent across the five SPIs |

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 A vendor of record — REFUSED (D15, 2026-07-25)

The question "Keycloak + WSO2 APIM, or WSO2 Identity Server for both?" was **withdrawn, not answered**. The
plan of 2026-07-23 had recorded "auth stack direction: Keycloak / WSO2" and left the split as its open Q5;
two days later the product decided it does not pick. Two vendor-shaped residuals were treated as *defects*
against this decision and fixed the same day (§4). ⛔ Do not re-open Q5; do not add a default that encodes
one vendor's URL layout. `docs/api/deployment/README.md` ships Keycloak and WSO2 **blueprints** — examples of
a compliant configuration, not a choice.

### 6.2 OIDC discovery (`/.well-known`) for `tokenEndpoint` or `endSessionUrl` — REFUSED (later superseded)

> ⚠ 2026-10-17: superseded by the provider presets — `OidcConfig` now reads an optional `-Dauth.oidc.discoveryUrl`, fetched once at first use (`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcConfig.java`). The refusal below is kept as history.

Both are declared configuration. The relay fails fast at construction without `auth.oidc.tokenEndpoint`;
the SPA's `endSessionUrl` is a value, not a lookup. Reason: this module configures `jwksUri` explicitly by
design, so a discovery fetch would be the one place the product reaches out to derive its own security
configuration — and a hardcoded IdP host in the SPA is exactly what leaked in SEC-INCIDENT-1. The BACKLOG
row that asked for discovery cited the D15 precedent it contradicted.

### 6.3 OPA / embedded rego, or any framework, for authorization — REFUSED (operator, 2026-07-23)

The backend is framework-free and builds offline. A Spring/Quarkus migration for security was refused for
the same reason (`ROADMAP.md` §3.1, `EDITIONS.md`): at the target user counts a framework buys nothing the
IAM and two small libraries do not, and a lean dependency tree is itself a compliance asset. `Conditions` is
the whole grammar; it is deliberately domain-agnostic so the dataset-side rule families could reuse it —
but they were **not forced onto it**: Expectation / Alert Rule / Decision Rule keep the Query-Core SQL filter
model, and no generic "Policy" or "Rule" kind exists (`GLOSSARY.md` one-word-one-concept).

### 6.4 Lens as a permission — REFUSED (standing)

A Lens is a self-selected, freely switchable view. `readOnly` is presentation. Every attempt to gate on Lens
identity has been a bug — most recently the bootstrap deadlock of 2026-07-25 (§4). The enforcement point is
`CapabilityManifest` + `withCapability`, server-side, and the UI mirrors it through named capabilities only.

### 6.5 A policy allow that bypasses capability gates — REFUSED (A3)

The `rbac-abac-plan` §2 combining order would have let a matched `allow` short-circuit the Standard gates.
Tightened before shipping: ABAC can only *narrow* what a capability grants, never widen it.

### 6.6 Trusting unsigned gateway identity; a clock-skew knob — REFUSED (R0)

The gateway assertion is verified against its own JWKS or it is ignored; `alg:none` and bare strings are
test-pinned failures. Nimbus's bounded 60 s skew default was reviewed and kept — no property added.

### 6.7 Auditing row-level allows; a POST explain endpoint — REFUSED (A5, operability)

Both for the same reason class: the mechanism would defeat itself. A row allow fires per surviving row and
floods the ledger; a POST explain is a `write` the policy under test could deny.

### 6.8 A per-edition capability validator — REFUSED (2026-09-07)

Rejecting a role file that grants a capability with no route behind it on Personal would make a
Standard-authored `roles.toon` fail on a Personal install. Dead vocabulary is harmless — there is no route to
reach.

### 6.9 `-D` switches for edition gating — REFUSED (operator, 2026-09-07)

Recorded here because it is a *security* refusal: Personal ships no authenticator and binds every interface,
so a switch that leaves the code in the bundle turns a packaging boundary into a policy boundary that one
mis-set flag re-opens. Every EDG-01 cell became a `ServiceLoader` module instead.

### 6.10 SEC-INCIDENT-1 responses that were refused

`git-filter-repo` history rewrite (invalidates every clone, breaks the shared sandbox, does not purge forks
or caches — and `refs/pull/*` defeated the one rewrite that was tried); backporting master's session layer to
`4.x` (far larger than the incident needed); "pull the auth logs first" as a rotation precondition (moot —
the logs were deleted 2026-07-26 before export).

### 6.11 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| The pre-2026-06-16 bearer plane (`Scope`, `-Dcontrol.token`, the token screen — removed) | the SPI posture (§3.1) | `auth-security.md` |
| A hand-rolled JOSE library for the authenticator (`rbac-abac-plan` R0 draft) | **MOOT** — Nimbus was already the signed-off, module-confined dependency; R0 was drafted from a stale premise | `rbac-abac-plan.md` §R0 (archive) |
| A separate authorize middleware for Access Profiles (R2 sketch) | enforcement at authentication (§3.7) | §4 |
| Seeding `access-policies.toon` at Space creation | engine-resident `PolicyEngine.SEED` overlaid by name | §3.10 |
| `KeycloakTokenRelay`; the derived Keycloak token endpoint | `OidcTokenRelay`; a mandatory explicit flag | §4 D15 |
| `canAuthorWorkbench` gating Connections and menu curation | `canOnboardConnections` (2026-07-22); `canCurateMenus` (D4) | §3.6 |
| `canOfferDatasets` on the builder and power seeds | admin/super (D14) | §3.6 |
| Uniform `granted && !readOnly && allows` in `LensService` | the identity / lens-scoped split | §3.14 |
| "Business lens ⇒ read-only" as a product statement | no longer true | §3.14 |
| `rbac-abac-plan` Q4 "roles-only subjects in v1?" | both `role` and `user` shipped; `user` = opaque IdP `sub` | §3.9 |
| `rbac-abac-plan` Q2 "fold X-Actor retirement into R2" | X-Actor was *rejected* on Standard, then removed on every edition in 4.0.0 (2026-11-17, MoSCoW S4) | §3.2 |
| The X-Actor gate "client migration with the API-v1 sunset" | the next MAJOR tag (the sunset apparatus was deleted 2026-07-25); discharged 2026-11-17 | `api/plan-api-stability.md` §Release notes |
| Lens Access P3's root-default-allow as contractual for roles | flagged forward-compatible only: the server default for roles may flip to deny-by-default — a security-module call, unmade | `lens-access-config-design.md` §P3 (archive) |
| The 2026-06-19 `STAKEHOLDER_OVERVIEW.md` ("a *planned* `inspecto-oidc` module", "Enterprise (future)", "Personal localhost-bound") | shipped 2026-07-06 / 2026-07-23; binds every interface | plan §8 cluster — reconciled at step 6, not here |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The whole posture, RBAC R0–R5, ABAC A1–A5, the 2026-07-25 decisions, the boot precondition | `docs/okf/product/module/access/admin-guide-auth-security.md` (`Concept`) | `providers/inspecto-oidc/, providers/inspecto-policy/` | the long-form narrative every row above was distilled from |
| The Standard module's classes, properties, shading and boot behaviour | `docs/okf/product/module/oidc/architecture-oidc.md` (`Concept`) | `providers/inspecto-oidc/` | `audience` warn-only, `SEC-SIDECAR-BOOT-1`, why the sidecar is shaded |
| Edition gating as a mechanism; the EDG-01 recipe items | `docs/okf/product/layer/platform/architecture-editions.md` (`Concept`) | `pom.xml` | how a module joins a profile and what the compiler cannot see |
| Request dispatch, `PUBLIC_PATHS`, the envelope, error codes | `docs/okf/product/module/processor/api-control-api.md` · `api-v1.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | the contract SEC gates inside |
| Intra-Space shares and the cross-Space Exchange lifecycle | `docs/okf/product/module/exchange/spec-exchange.md` (`Concept`) | `features/inspecto-exchange/` | the `ShareGrant` state machine and the attribute-scope pin |
| The write-root jail, `PathJail`, `ConfigSafetyValidator` | `docs/okf/product/module/config/spec-config-safety.md` (`Concept`) | `platform/inspecto-config/` | what happens *inside* the root once §3.13 lets a write through |
| Actor attribution, `AuditTrail`, `access.denied` in the ledger | `docs/okf/product/module/event/spec-events-metrics.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | how a decision becomes an event |
| 🔴 **The UI seam has no concept file.** `LensService`, `SessionService`, the Access / Roles / Policies editors and the sign-in flow are documented only in source and in this spec | *(none — gap)* | `inspecto-ui/src/app/inspecto/api/lens.service.ts` · `session.service.ts` · `inspecto-ui/src/app/modules/admin/access/` | the code. Same class of gap the plan flagged for `INC`'s missing backend concept; filed under `UI`/Surfaces, not here |

---
