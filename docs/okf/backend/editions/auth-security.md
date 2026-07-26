---
type: Concept
title: Auth & Security
description: Auth-free core; the Authenticator/Subject/TokenRelay/AccessDecider SPIs; the shipped inspecto-security module (Standard, OIDC via Keycloak/WSO2, data-driven roles, Access-Profile + sharing enforcement); the Enterprise inspecto-policy ABAC engine (authored Access Policies, space isolation, decision audit); the separate -Dassist.write.root write-gate.
resource: inspecto-security/, inspecto-policy/
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-enterprise]
timestamp: 2026-07-24T00:00:00Z
---

# Auth & Security

**All auth was removed from `master`/the common core on 2026-06-16.** Personal is genuinely auth-free: every
[`ControlApi`](../control-plane/control-api.md) route is open, the SPA boots straight to `/dashboard`, and there
is no token paste / guard / interceptor. The removed hand-rolled bearer-token plane (per-route `Scope`,
`-Dcontrol.token`, the Angular token screen) is gone.

**Standard re-adds auth via SPIs + the shipped `inspecto-security` module.** The core defines three SPIs in
`com.gamma.control`: **`Authenticator`** (validates a request, yields a subject), **`Subject`** (a record of
`id` + capabilities), and **`TokenRelay`**. `inspecto-security/` (artifactId `file-processor-security`, 41
tests) implements them: `OidcAuthenticator` (Nimbus JOSE+JWT), `RoleMapper` (roles from IAM claims), and
`OidcTokenRelay`. It joins the reactor **only under the `edition-standard` Maven profile** — the default
build never compiles it (verify with `-Pedition-standard`); because it's a
[build flavor](editions-model.md), the core still carries zero auth code.

**BFF session shape.** The browser never holds tokens: `POST /auth/exchange|refresh|logout` run the OIDC
exchange server-side and keep the refresh token in an **httpOnly `inspecto_rt` cookie**
(`SameSite=Strict`, plus an `Origin` check for CSRF). HTTPS is served by the pure-JDK `HttpsServer`. The UI
discovers the mode via `GET /bootstrap` → `features.authMode` and its OIDC flow is a no-op on Personal.

**The Capability seam (RBAC groundwork, 2026-07-03; seam proven by Lens Access config 2026-07-14).**
Authorization questions are always asked as **named capabilities** (`canAuthorWorkbench`, `canOperateRuns`,
`canTriageRequirements`, `canOnboardConnections`, …) — never "which lens is active?". A **Lens** is a
self-selected *view* (UX shaping, honor system); a **Role** is an admin-*assigned* authorization enforced
server-side (GLOSSARY §1-A, binding). On Standard, `RoleMapper` resolves IAM claims → roles → capabilities
per the planned taxonomy (Business / Pipeline Developer / Operations / Power / Admin / Super); the UI
re-derives its capability signals from the subject's grants in one file — no pane changes. Rules for
extending: one new named capability per distinct authorization question; never reuse one because its current
value happens to match. **Data-scoped grants (SEC-7d, shipped 2026-07-08):** an object's `caseType` attribute
vs `Subject.dataScopes` (null = unscoped; resolved from a `data_scopes` claim ∪ `case:<scope>` role names),
enforced in `ObjectRoutes` — filtered lists, 404 on out-of-scope access, pruned correlation graphs;
event/audit streams stay capability-gated by design. **`canOnboardConnections` (rbac-groundwork §3/§4.1 Q1,
product sign-off 2026-07-22, IMPLEMENTED):** Connection onboarding is its own Admin-owned grant — the write
routes (`POST`/`PUT`/`DELETE /connections`) gate on `canOnboardConnections`, **not** `canAuthorWorkbench`,
because Connections are the credential + network-egress surface (worse blast radius than authoring a pipeline;
a Pipeline Developer builds against *existing* connections but can't mint new ones). `RoleMapper` maps
`admin → canOnboardConnections` and `super → {all}`; the UI mirrors it as `LensService.canOnboardConnections`
(the connections pane's create/edit/delete gate).

**RBAC shipped end-to-end (workstream R, R0–R5, 2026-07-23).** The groundwork above is now a working
server-side authorization system, all behind the existing SPIs (core stays auth-free):

- **Data-driven roles (R1).** Role→capability/data-scope grants are authorable: core `com.gamma.control.Roles`
  holds the seed table + doc grammar; `ControlApi` stamps the bound space's config root
  (`Roles.ATTR_CONFIG_ROOT`) pre-auth and `Roles.effective(ex)` overlays a per-space `roles.toon` **per role
  name** (authored `[]` revokes; unnamed seed roles keep defaults), mtime-cached so edits apply next request,
  no restart. `RoleMapper` lost its hardcoded switch and resolves through the table. **Fail-closed:** an
  existing-but-unreadable `roles.toon` suspends all role grants. `GET/PUT /access/roles` author it (PUT gated
  `canConfigureAccess`); Settings ▸ Access ▸ **Roles** tab (R5) edits it with source badges + the
  profile-deny strike-through overlay.
- **Access-Profile enforcement (R2).** Enforcement happens at *authentication* time — `AccessGrants` resolves
  the subject's held roles against saved `subjectType: role` Access Profiles over the Access Catalog
  (nearest-ancestor grant, root default allow, **union across roles**, deny binds via catalog action nodes →
  their capability) and `OidcAuthenticator` strips the denied capabilities before building the `Subject`. So
  the `Subject` stays capabilities-only (role names never leave the authenticator), every `requireCapability`
  gate enforces profile denies with zero route changes, and `/bootstrap permissions` report *effective* grants.
- **Component sharing (R3).** Every `ComponentStore` kind accepts an optional `owner` + `shares:
  [{subjectType: role|user, subjectId, access: view|edit}]` envelope (absent on every existing doc). Core
  `ComponentAccess`: no `shares` key ⇒ byte-identical today; once present the component is restricted (owner +
  `canConfigureAccess` holders full, shares grant view/edit, everyone else the SEC-7d 404/filtered contract on
  read *and* mutate). Role matching rides `ComponentAccess.ATTR_HELD_ROLES` (authenticator-stamped, never
  serialized). See [Exchange & sharing](../control-plane/exchange-sharing.md).
- **Capability manifest (R4).** `CapabilityManifest` declares all gated `method+pattern → capability`
  registrations; `CapabilityManifestTest` source-scans the route files and fails the build on drift, asserts
  every route-demanded capability is granted by ≥1 seed role, and is the single source of truth for
  `Roles.KNOWN_CAPABILITIES`.
- **Gateway trust mode (R0 remainder).** `OidcAuthenticator` gains an optional second JWT processor validating
  a gateway-signed `X-JWT-Assertion` header (WSO2 APIM), consulted only when no valid `Bearer` resolves;
  unsigned header identity is never trusted. Opt-in via `-Dauth.oidc.gateway.issuer/.jwksUri`.

## Enterprise ABAC — the Access Policy engine (`inspecto-policy`, workstream A)

The `edition-enterprise` Maven profile = `edition-standard` + the new **`inspecto-policy`** module
(artifactId `file-processor-policy`), which registers a `PolicyEngine` on the core's **`AccessDecider`** SPI
via `META-INF/services`. Personal/Standard never bundle it and behave byte-identically. Build/test with
`mvn -o -Pedition-enterprise clean test` ([build & test](../build-run/build-test.md)).

- **Attribute model (A1).** `Subject` gained an additive `attributes()` map (empty on every pre-A1 caller).
  `roles.toon` carries an optional `identity: {attributeClaims: […]}` **allowlist**; `OidcAuthenticator`
  copies exactly the allowlisted-and-present verified claims onto the Subject (never the raw token; nothing
  when the doc is unreadable — attributes fail closed alongside role grants). Both Bearer and gateway paths.
- **Condition grammar (A2).** `com.gamma.util.Conditions` (inspecto-util, domain-agnostic — the "one policy
  engine, many policy kinds" library): recursive-descent, parse-once → predicate over a nested `Map` context
  via `DottedPath`; grammar `== != in contains and or not ( )` + literals + dotted refs; strict-Boolean
  truthiness, type-mismatch-is-false, offset-bearing parse errors. Core `AccessPolicies` mirrors `Roles`:
  per-space `access-policies.toon` (`{name, effect: allow|deny, target:{actions?,resourceKinds?}, when?}`),
  mtime-cached, one validate grammar shared by the file parser and `GET/PUT /access/policies` (`when`
  parse-gates 422). Unreadable doc ⇒ the engine DENIES loudly, never "no policies".
- **Enforcement (A3).** `AccessDecider` SPI (core) is consulted at two PEPs: the route-level **authorize
  stage** in `ControlApi.routeDispatch` (after authenticate; DENY → 403; skips public paths + subject-less
  exchanges) and the row-level **`RowScope`** filter (generalizes `ObjectRoutes`' SEC-7d filter — DENY hides
  the row 404/filtered). `PolicyEngine` = deny-overrides → allow → ABSTAIN over `AccessPolicies.effective`;
  context = `subject.{id,capabilities,dataScopes,roles}` + A1 claims, `env.{action,route,space}`,
  `resource.*` (row level, `resource.space` defaulting to the bound space). **A policy allow does NOT bypass
  capability gates** (defense in depth — the plan's §2 order was deliberately tightened); ABSTAIN falls
  through to the Standard capability/profile/sharing gates.
- **Space isolation (A4 = SPC-5).** Per-tenant isolation ships as two **engine-resident seed policies**
  (`PolicyEngine.SEED`: `space-isolation`, `space-isolation-rows`) overlaid **per policy name** by the
  authored doc — deny when the subject's mapped `space` home-space claim ≠ the bound space (route + row).
  They engage **only when a `space` claim is mapped** (unmapped ⇒ no isolation, never a bricked API) and
  exempt `canConfigureAccess` holders. Note: `EventLog.currentSpaceId()` never returns null (falls back to
  the default space), so `env.space` is always bound — un-prefixed server-global routes bind the default
  space and only a default-home or operator subject reaches them.
- **Decision audit (A5).** Every policy DENY (403 route / hidden row) and every route-level policy-matched
  ALLOW is recorded: `PolicyEngine` stamps the matched policy name on the exchange
  (`AccessDecider.ATTR_MATCHED_POLICY`; `<policies-unreadable>` marker on a fail-closed deny) and core
  `AuditTrail.policyDecision(...)` emits `access.denied` / `access.granted` (category `authorization`, with
  actor, ABAC action verb, route, row kind/id, matched policy) via the existing event seam — read back via
  `GET /events?type=ACCESS_DENIED|AUDIT` ([events & metrics](../control-plane/events-metrics.md)). A
  row-level *allow* is deliberately not audited (fires per surviving row — would flood list reads); ABSTAIN
  is not a policy decision and is never audited.

- **Policy operability (2026-07-24, BACKLOG §5 slice).** Two reads make the engine legible without
  parsing TOON: `GET /access/policies` now surfaces the engine-resident seed denies too (via a widened
  `AccessDecider.seededPolicies()` default-empty seam), each row tagged `source: authored|seed` — an
  operator sees the built-in space-isolation denies they never wrote; and `GET /access/explain?route=&
  method=&resourceKind=` is a side-effect-free "why denied?" dry-run for the **caller's own session**
  (`AccessDecider.explain` → decision + matched policy + per-policy `{targeted, conditionHeld, source}`
  trace, enforcing/auditing nothing). It is a GET on purpose — a POST would be a `write` the policy under
  test could 403 at the route PEP, locking the denied subject out of their own explanation. Both are
  Enterprise-only (the seam is default-empty; Personal/Standard show authored rows only and
  `{enabled:false}`). UI: Settings ▸ Access ▸ **Policies** tab (read-only effective table + explain panel).
- **Q3 `canTriageRequirements` grant (2026-07-24, product sign-off).** Seeded to Business + Power + Admin +
  Super (`Roles.SEED`) — requirement triage is a business-analyst activity; Pipeline Developer/Operations
  build/run rather than triage.

## Decisions of record — 2026-07-25 product session (BACKLOG D4 / D14 / D15)

- **D15 — no IdP/gateway vendor of record; the vendor is a per-client deployment choice.** The question
  "Keycloak + WSO2 APIM vs. WSO2 IS" is **withdrawn, not answered**: we do not pick, we stay configurable and
  let the client decide. This ratifies the standards-only posture the module already has — `OidcAuthenticator`
  is vendor-agnostic (`-Dauth.oidc.issuer` / `.jwksUri` / `.audience` / `.rolesClaim`, generic RS256 Nimbus
  processing, no vendor SDK). Two **vendor-shaped residuals** were defects against this decision; both are
  now **fixed (2026-07-25)**:
  * **`KeycloakTokenRelay` → `OidcTokenRelay`** (class + `META-INF/services/com.gamma.control.TokenRelay` +
    `OidcTokenRelayTest`), matching `OidcAuthenticator`'s neutral naming.
  * **`auth.oidc.tokenEndpoint` is now mandatory with no default.** The old fallback derived
    `<auth.oidc.issuer>/protocol/openid-connect/token` — one vendor's path layout baked into the product.
    There is no discovery fetch to derive it from (this module configures `auth.oidc.jwksUri` explicitly
    too, by design), so the relay **fails fast** at construction with a message naming the property:
    take `token_endpoint` from the provider's `/.well-known/openid-configuration` and pass
    `-Dauth.oidc.tokenEndpoint=…`. ⚠ **Deployment-breaking for anyone who relied on the derived default** —
    Keycloak deployments must now set the flag explicitly (same value as before).
  * The gateway trust header still defaults to `X-JWT-Assertion` — **unchanged behaviour**, now documented in
    `OidcAuthenticator`'s javadoc as *a* convention (WSO2 APIM's, widely copied), not *the* expected gateway;
    any gateway is accommodated via `-Dauth.oidc.gateway.header`.
  Litmus test for future work: any new auth code that cannot be pointed at a different compliant IdP by
  configuration alone is wrong.
- **D14 — the R1 seed grant set is ratified with one tightening.** The five previously-unreviewed route
  capabilities were checked against `Roles.SEED` in this session. `canConfigureAccess` and `canApproveShares`
  were **already** admin/super-only, so the "bootstrap deadlock left them over-granted" concern was unfounded
  — no change needed there. `canAuthorAlertRules` and `canRequestShares` are ratified as developer/ops-tier
  (authoring a rule and *asking* for access are both reversible and gated downstream — a request still needs
  an owner's approval). **`canOfferDatasets` was tightened to admin/super (implemented same day)**: offering a
  Dataset cross-space is a data-*exposure* decision with no second gate behind it, so it does not belong with
  the build-time capabilities granted to `pipeline-developer`/`app-developer`/`developer`/`power`. It left the
  `builder` set and `power`, and joined `admin` (`super` holds the whole vocabulary via `KNOWN_CAPABILITIES`).
  ⚠ **`Roles.SEED` is asserted by an *equality* check** in `OidcAuthenticatorTest`
  (`adminRoleGrantsOnboardConnectionsAndNotWorkbench`) — every future grant addition must update that test, and
  it must be run under **`-Pedition-enterprise`**: the default reactor omits `inspecto-security` entirely, so a
  plain `mvn -o clean test` cannot see a failure there. That gap had already left this assertion red on
  `master` since `7e90f53d`; see `.claude/skills/build-verify/SKILL.md`.
- **D4 — `canCurateMenus`, split out of `canAuthorWorkbench`. SHIPPED end-to-end 2026-07-25**
  (`c8a40a24` server, `96f8ca4f` UI). Rationale: the `pipeline-developer`/`app-developer`/`developer`/`power`
  seeds got menu curation free, conflating "may edit a pipeline" with "may change what this space's business
  users see" — a navigation change is visible to every user in the space and is not a build activity.
  As built: `Roles.java` constant + seed grant to **admin/power/super** (curation is a space-owner activity;
  `power` is the seeded role closest to "owns this space's presentation") · `CapabilityManifest.java`
  `/nav/menus` entry · `NavRoutes.java` gate · `LensService.canCurateMenus` · the `menus.curate`
  `ACCESS_ACTION_NODES.settings` node · the mock `access.handler.ts` vocabulary + seed table.
  ⚠ **The manifest entry and the route gate must land in the same commit** — `CapabilityManifestTest`
  enforces manifest↔registration congruence in *both* directions, matching on the capability **string
  literal** at the registration site.
  ⚠ **Zero-hosted-spaces trap, hit writing the gate test — INVESTIGATED + FIXED 2026-07-25.** With an
  `Authenticator` active, `ControlApi.authenticate` resolved `writeRoot()` → `SpaceManager.current()` for
  *every* request, which throws `IllegalStateException: No spaces are hosted` on a root with none — so every
  route 500ed, including `/health` and the `POST /spaces` that would recover. Findings on investigation:
  the **bootstrap framing was wrong** (`ControlApi.main` `System.exit(1)`s on an empty `-Dspaces.root`, so a
  fresh install can never reach a running zero-space server, and single-tenant always hosts exactly one), and
  the body was **not** empty — `errorBoundary` returns a structured `INTERNAL` envelope. But it **was**
  reachable by *deleting the last space* at runtime, which had no guard. Two fixes shipped: `authenticate`
  resolves the roles root only when a space is hosted (`Roles.effective(null)` already degrades to the seed
  table, so authentication needs no space), and `DELETE /spaces/{id}?purge=true` is refused with a **409**
  when it would remove the last space *directory on disk* — deliberately a directory count, not a hosted
  count, so a deregistered-but-on-disk space still counts as a survivor and the predicate is exactly the
  negation of `main()`'s boot condition. Deregister-only on the last space stays allowed. Regression tests:
  `ControlApiSpacesTest.authenticatedCreateSucceedsWhenNoSpaceIsHostedYet` and
  `purgingTheLastSpaceOnDiskIsRefused`.

### Identity vs lens-scoped UI capabilities (`651ca48e`, 2026-07-25)

`LensService` capabilities used to be uniformly `granted && !readOnly && allows(…)`, while `allowedLenses`
qualifies Builder/Ops only via `canAuthorWorkbench`/`canOperateRuns`. An OIDC subject holding neither — **the
entire admin seed** — was snapped to the read-only Business lens and evaluated *every* capability false
client-side while the server authorized the calls. Worst case was a bootstrap deadlock: a fresh deployment's
admin saw the Access matrix read-only and could not author the roles that grant access.

The rule is now split, and the split is the durable fact:

| | Predicate | Members |
|---|---|---|
| **Identity** — who the subject *is* | `granted && allows` | `canConfigureAccess`, `canCurateMenus`, `canOnboardConnections`, `canTriageRequirements`, `canOfferDatasets`, `canApproveShares` |
| **Lens-scoped** — the activity a lens *represents* | `granted && !readOnly && allows` | `canAuthorWorkbench`, `canOperateRuns`, `canAuthorAlertRules`, `canRequestShares` |

**The Exchange half landed 2026-07-26, and it was the mirror-image bug.** `canOfferDatasets` and
`canApproveShares` had no `LensService` signal at all, so the UI gated Offer on nothing but the
`exchange` feature flag and gated Approve / Deny / Revoke / Expiry / Refresh on nothing but the by-me
view. Every subject saw the owner's governance actions and got a server 403 on click. Both are identity
capabilities (Admin-owned, and admin never qualifies for a non-Business lens), with `exchange.offer` /
`exchange.approve` action nodes under Data Catalog so the Access-Profile seam can reach them.

> The generalisable lesson: **a capability the server enforces but the client never reads is a bug in
> both directions** — a false negative hides an affordance the subject is entitled to, a false positive
> offers one that 403s. When adding a `withCapability` route, check whether a `LensService` signal and an
> action node exist for it. **As of 2026-07-26 every route capability has both** — the last instance,
> `canRequestShares`, closed below.

**`canRequestShares` is the one Exchange capability that is lens-scoped** (2026-07-26). Same bug shape as
its two siblings — "Request access" and "Pin a snapshot version" rendered for everyone and 403'd on click —
but the opposite classification, and the reason is worth keeping: **which side of the table a capability
falls on is decided by whether lens-scoping can strand a role, not by the feature it belongs to.** Every
seeded role holding `canRequestShares` (the three builder roles, `operations`, `power`, `super`) also holds
`canAuthorWorkbench` or `canOperateRuns`, so it always qualifies for a non-Business lens and the Business
snap that stranded the admin seed cannot reach it; the `business` seed pointedly does not hold it. It is
also genuinely an activity — "I want this dataset to build with" — where offering and approving are
governance. Action node `exchange.request`, same Data Catalog group.

Three things a future change must not undo:

- **`readOnly` is presentation, never a boundary.** No component reads it; the server (`CapabilityManifest`
  + `withCapability`) is the enforcement point. That is *why* dropping the conjunct cannot escalate privilege.
- **Identity capabilities are still lens-suppressed off OIDC.** The exemption is justified by the subject's
  identity, and in honor-system mode there is none (`granted()` short-circuits true for everyone), so the lens
  is the only signal. Without that clause Personal mode's Business lens starts showing Connections and
  Requirements affordances and the "View as" preview stops meaning anything.
- **`canTriageRequirements` is identity by operator call** — it is the `business` seed's *only* capability, so
  lens-scoping it revoked the single grant that role has. Consequence, accepted deliberately:
  **"Business lens ⇒ read-only" is no longer a true statement about the product.**

Still-open (carried to [BACKLOG](../../../BACKLOG.md), non-blocking): a policy-**authoring** UX beyond
TOON+validation (a matrix/create editor — the read-only visibility + explain above shipped, authoring did
not); X-Actor is already rejected on Standard (the SEC-7a spoof guard), so only its full removal remains,
client-migration-gated with the API-v1 legacy sunset.

`package.ps1 -Edition Enterprise` **shipped 2026-07-25** — a superset of Standard (both the `security` and
`policy` jars are bundled), with `serve.sh`/`serve.bat` deriving the edition from bundle contents. No
runtime flag was added: `inspecto-policy` is discovered solely via its `AccessDecider` service file, so the
classpath entry is the switch. Detail in [EDITIONS.md](../../../EDITIONS.md).

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, authored-Pipeline CRUD): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/config-safety.md). It is an ops decision about whether this instance may
write — not authentication.
