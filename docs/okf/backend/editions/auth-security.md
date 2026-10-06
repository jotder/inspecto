---
type: Concept
title: Auth & Security
description: Auth-free core; the Authenticator/Subject/TokenRelay/AccessDecider SPIs; the shipped inspecto-security module (Professional, OIDC via Keycloak/WSO2, data-driven roles, Access-Profile + sharing enforcement); the Enterprise inspecto-policy ABAC engine (authored Access Policies, space isolation, decision audit); the separate -Dassist.write.root write-gate.
resource: inspecto-security/, inspecto-policy/
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-professional, edition-enterprise]
timestamp: 2026-07-24T00:00:00Z
---

# Auth & Security

**All auth was removed from `master`/the common core on 2026-06-16.** Personal is genuinely auth-free: every
[`ControlApi`](../control-plane/control-api.md) route is open, the SPA boots straight to `/dashboard`, and there
is no token paste / guard / interceptor. The removed hand-rolled bearer-token plane (per-route `Scope`,
`-Dcontrol.token`, the Angular token screen) is gone.

**Professional re-adds auth via SPIs + the shipped `inspecto-security` module.** The core defines three SPIs in
`com.gamma.control`: **`Authenticator`** (validates a request, yields a subject), **`Subject`** (a record of
`id` + capabilities), and **`TokenRelay`**. `inspecto-security/` (artifactId `inspecto-security`, **34**
tests — measured 2026-09-08: 24 + 7 + 3; an earlier "41" was never true of the tree) implements them: `OidcAuthenticator` (Nimbus JOSE+JWT), `RoleMapper` (roles from IAM claims), and
`OidcTokenRelay`. It joins the reactor **only under the `edition-professional` Maven profile** (with `edition-standard` retained as an alias) — the default
build never compiles it (verify with `-Pedition-professional`); because it's a
[build flavor](editions-model.md), the core still carries zero auth code.

**BFF session shape.** The browser never holds tokens: `POST /auth/exchange|refresh|logout` run the OIDC
exchange server-side and keep the refresh token in an **httpOnly `inspecto_rt` cookie**
(`SameSite=Strict`, plus an `Origin` check for CSRF). HTTPS is served by the pure-JDK `HttpsServer`. The UI
discovers the mode via `GET /bootstrap` → `features.authMode` and its OIDC flow is a no-op on Personal.

**Sign-out is three layers, and all three have to fire** (fixed 2026-07-26; BACKLOG §5 tracked only the
third). `SessionService.logout()` (1) POSTs `/auth/logout` so the backend revokes and clears the
`inspecto_rt` cookie, (2) drops the in-memory access token, and (3) — **RP-Initiated Logout 1.0** — sends the
browser to the provider's `end_session_endpoint` with `client_id` + `post_logout_redirect_uri=<origin>/sign-in`,
so the IdP's SSO session ends too. Without (3) the next sign-in completes with no credential prompt.
`id_token_hint` is deliberately omitted: the id token lives behind the BFF, and the spec accepts `client_id`
as the RP identifier instead.

- **`endSessionUrl` is declared config, never derived or discovered** — `bootstrap.auth.endSessionUrl`
  falling back to `environment.oidc.endSessionUrl`, sitting beside `authorizeUrl`. This is D15's call for
  `auth.oidc.tokenEndpoint` applied again: no vendor's path layout is assumed, and a hardcoded IdP host in
  the SPA is exactly what caused the leaked-secret incident. *(The BACKLOG row asked for `/.well-known`
  discovery; that contradicts the D15 precedent it cited in the same sentence, so declaration won.)* Leave it
  blank and sign-out degrades to layers (1)+(2) — all a provider without the endpoint can support anyway.
- **`SessionService.redirect()` is the single seam that leaves the SPA** (authorize + end-session). It exists
  because jsdom makes `window.location.assign` non-configurable, so it is the only way to unit-test either
  redirect.
- **Personal renders no user menu at all.** The auth-free shell has no principal to name and no session to
  end; showing the menu meant a blank "Signed in as" over a Sign out that could not work.
- **Under OIDC the menu now NAMES the subject** (2026-09-15). `bootstrap.session.actor` was parsed by
  `SessionService` and thrown away, while the menu rendered a hardcoded empty string fed by a
  `UserService.user$` that nothing ever populated — so "Signed in as" was blank on every deployment. The
  actor is now a signal, set only when the session reports `authenticated` and cleared with the session.
  ⚠ The anonymous read's `actor` is the honour-system placeholder `appUser` (`ApiContext.actor`), which is
  **not** a principal and is deliberately never displayed.

**The Capability seam (RBAC groundwork, 2026-07-03; seam proven by Lens Access config 2026-07-14).**
Authorization questions are always asked as **named capabilities** (`canAuthorWorkbench`, `canOperateRuns`,
`canTriageRequirements`, `canOnboardConnections`, …) — never "which lens is active?". A **Lens** is a
self-selected *view* (UX shaping, honor system); a **Role** is an admin-*assigned* authorization enforced
server-side (GLOSSARY §1-A, binding). On Professional, `RoleMapper` resolves IAM claims → roles → capabilities
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
**The `findings-spec` kind gates on `canManageIncidents`, not `canAuthorWorkbench` (D1 = (b), operator
2026-09-25, IMPLEMENTED).** The Findings spec is Case-desk configuration, so its writes go to the people who
resolve Cases (seed: `operations`, `support`, `admin`, `power`, `super`) and **not** to the builder roles.
It is the one per-kind exception on the generic `/components` CRUD, built as **four literal routes**
(`POST /components/findings-spec`, `PUT`/`DELETE /components/findings-spec/{id}`, `…/versions/{v}/restore`)
registered before the generic ones in `ComponentRoutes` — so the manifest, the boot posture check and
`compliance/evidence/route-gating.md` each see one capability per route. ⚠ The generic routes URL-decode the
kind segment a second time, so `findings%252Dspec` misses the literal routes; the generic handlers therefore
also demand `canManageIncidents` for that kind (fail-closed: such a caller needs both). Pinned by
`ControlApiFindingsSpecGateTest` with a real Subject. Since 2026-09-27 the generic handlers read the SAME table
the import doors do (`ImportCapabilityGuard.requireKind`), so `/components/alert-rule` needs
`canAuthorAlertRules` too, and the dedicated-only kinds are refused there (below).
**Saving Findings VALUES on a Case is collaboration, not administration (operator 2026-09-25, IMPLEMENTED).**
Writing the *spec* needs `canManageIncidents`; filling it in on a Case needs only that the caller **can see
the Case** — the same posture as comments and attachments. It has its own route, `PUT /objects/{id}/findings`,
because `PATCH /objects/{id}` also edits priority / severity / assignee and stays `canAdminister`. The route
sits behind the SEC-7d/ABAC scope guard (out-of-scope → 404, existence-hiding), accepts **only** a `findings`
object (any other key → 422, so it cannot be used to change a disposition), validates against the effective
findings-spec (422), writes only `attributes.findings` + its flat `recordsAffected` copy, and
audits an `OBJECT_ACTIVITY` `findings` event with the authenticated Subject as actor. Recorded as a
`collaboration` exemption in `CapabilityManifest.EXEMPTIONS`; pinned by `ControlApiFindingsWriteTest` with a
real scoped Subject lacking `canAdminister` (200 on a visible Case, 422 on `priority`, 404 out of scope, 422
off-spec, 403 on the PATCH).

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

The `edition-enterprise` Maven profile = `edition-professional` (or `edition-standard`) + the new **`inspecto-policy`** module
(artifactId `inspecto-policy`), which registers a `PolicyEngine` on the core's **`AccessDecider`** SPI
via `META-INF/services`. Personal/Professional never bundle it and behave byte-identically. Build/test with
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
  the row 404/filtered; consumers: Ops objects, annotation targets, and — since LA-20 — an Investigation's
  Working Set relation, `resourceKind: investigation` with `resource.{id,owner,dataset,parent}`). `PolicyEngine` = deny-overrides → allow → ABSTAIN over `AccessPolicies.effective`;
  context = `subject.{id,capabilities,dataScopes,roles}` + A1 claims, `env.{action,route,space}`,
  `resource.*` (row level, `resource.space` defaulting to the bound space). **A policy allow does NOT bypass
  capability gates** (defense in depth — the plan's §2 order was deliberately tightened); ABSTAIN falls
  through to the Professional capability/profile/sharing gates.
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
  Enterprise-only (the seam is default-empty; Personal/Professional show authored rows only and
  `{enabled:false}`). UI: Settings ▸ Access ▸ **Policies** tab (effective table + explain panel; authoring
  since 2026-09-25, next bullet).
- **Policy authoring guards (2026-09-25, `archived-documents/plans-archive/policy-authoring-ux-design.md` S1–S4, operator D1 =
  guard all nine failure modes at save time).** The one grammar `AccessPolicies.validate` (shared by the
  PUT and the file parser) now refuses, as 422s naming the policy and a bracketed check code: an unknown
  key on a policy or `target` (**F8** `unknown-key`/`ambiguous-key`); a `when` reference the engine never
  binds (**F2** `unknown-ref` — `subject.<k>` must be `id/capabilities/dataScopes/roles` or a claim
  allowlisted in `roles.toon identity.attributeClaims`, `env.<k>` must be `action/route/space`, any
  `resource.*` passes); an untargeted `deny` with no `when` (**F9** `deny-everything`). The reference facts
  come from the new `Conditions.refs(source)` (every dotted ref → the string literals compared with it).
  Judgement calls are **warnings** (`AccessPolicies.lint`), returned as `warnings[]` by GET and PUT alike
  so a hand-edited doc shows them too: **F3** `unknown-capability` / `unknown-role`, **F4**
  `unknown-resource-kind` (vocabulary `AccessPolicies.RESOURCE_KINDS` — the lower-cased `ObjectType`s plus
  `investigation`, served as `resourceKinds` so the SPA never mirrors it), **F5**
  `resource-ref-at-route-level`, **F6** `seed-override` (the message quotes the built-in condition it
  replaces). **F7**: `PUT /access/policies` runs the validated draft through the new default-ABSTAIN seam
  `AccessDecider.simulate` for the saver's own Subject against their next `PUT /access/policies` and 422s
  `would-lock-out` before writing; `PolicyEngine.decide`/`explain`/`simulate` share ONE private evaluator
  so the guard cannot drift from enforcement. **F1**: a hand edit that fails a 422-class check still marks
  the doc unreadable (fail-closed, unchanged), but `GET`'s `error` now names the policy and check —
  to `canConfigureAccess` holders only (D5); and `PUT /access/roles` 422s a claim-allowlist change that
  would turn the policies doc unreadable as a side effect (the doc's cache is keyed on the allowlist).
  `POST /access/policies/preview` (gated `canConfigureAccess`, S4) returns the draft's impact: every
  effective role as a synthetic subject (capabilities + role name — no id, no claims) × read/write/operate
  at route level, plus × each known kind a saved or draft policy targets, each cell before → after.
  UI: the Policies tab authors (new/edit/delete, built-in "Override…" behind a confirm, full replace with
  `If-Match`, warnings under each row, *Preview impact* in `PolicyFormDialog`).
  ⚠ **Upgrade bites at runtime:** an existing doc with a stray key, or a `subject.<claim>` not
  allowlisted, is unreadable after upgrade ⇒ deny-all on Enterprise, recoverable only on disk (the PUT is
  itself denied while the doc is unreadable). Pinned by `ControlApiAccessPolicyLintTest`
  (`f8_upgradePath_…`) and `ControlApiPolicyEnforcementTest.d4_upgradePath_…`.
  ⚠ Limits, stated: the preview's role subjects carry no id or claims, so a policy keyed on
  `subject.id` or a claim shows no flip; the lockout guard protects the SAVER only (D9) — a draft that
  denies every *other* access configurer is accepted; and the preview is a POST, so a subject the saved
  doc already denies cannot preview (F7 keeps the in-product path from reaching that state). The preview's
  optional `route` body field binds `env.route` (default `/`); the design's free-form `probes` argument was
  **not built** — D7 fixed the axes (roles × read/write/operate, plus each targeted known kind) instead.
  Decisions D2–D9 were taken on the design's recommendation (D5, D7 on the most fail-closed option) and are
  reversible.
  ⚠ **Owed: a browser pass over the Policies-tab editor** (create / edit / delete / override confirm /
  *Preview impact* / 409 and 422 messages). The editor is covered by vitest specs only; it has not been
  driven in the preview against an Enterprise backend.
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
  `master` since `63a556f8`; see `.claude/skills/build-verify/SKILL.md`.
- **D4 — `canCurateMenus`, split out of `canAuthorWorkbench`. SHIPPED end-to-end 2026-07-25**
  (`fc637b1b` server, `0c375ff5` UI). Rationale: the `pipeline-developer`/`app-developer`/`developer`/`power`
  seeds got menu curation free, conflating "may edit a pipeline" with "may change what this space's business
  users see" — a navigation change is visible to every user in the space and is not a build activity.
  As built: `Roles.java` constant + seed grant to **admin/power/super** (curation is a space-owner activity;
  `power` is the seeded role closest to "owns this space's presentation") · `CapabilityManifest.java`
  `/nav/menus` entry · `NavRoutes.java` gate · `LensService.canCurateMenus` · the `menus.curate`
  `ACCESS_ACTION_NODES.settings` node.
  ⚠ **The manifest entry and the route gate must land in the same commit** — `CapabilityManifestTest`
  enforces manifest↔registration congruence in *both* directions, matching on the capability **string
  literal** at the registration site.
  ⚠ **Zero-hosted-spaces trap, hit writing the gate test — INVESTIGATED + FIXED 2026-07-25.** With an
  `Authenticator` active, `ControlApi.authenticate` resolved `writeRoot()` → `SpaceManager.current()` for
  *every* request, which throws `IllegalStateException: No spaces are hosted` on a root with none — so every
  route 500ed, including `/health` and the `POST /spaces` that would recover. Findings on investigation:
  the **bootstrap framing was wrong** (`ControlApi.main` `System.exit(1)`ed on an empty `-Dspaces.root`, so a
  fresh install could never reach a running zero-space server — ⚠ no longer true since 2026-09-25: bundles
  ship no Spaces, so `main` boots an empty root and Space-scoped routes answer 503), and
  the body was **not** empty — `errorBoundary` returns a structured `INTERNAL` envelope. But it **was**
  reachable by *deleting the last space* at runtime, which had no guard. Two fixes shipped: `authenticate`
  resolves the roles root only when a space is hosted (`Roles.effective(null)` already degrades to the seed
  table, so authentication needs no space), and `DELETE /spaces/{id}?purge=true` is refused with a **409**
  when it would remove the last space *directory on disk* — deliberately a directory count, not a hosted
  count, so a deregistered-but-on-disk space still counts as a survivor and the predicate is exactly the
  negation of `main()`'s boot condition. Deregister-only on the last space stays allowed. Regression tests:
  `ControlApiSpacesTest.authenticatedCreateSucceedsWhenNoSpaceIsHostedYet` and
  `purgingTheLastSpaceOnDiskIsRefused`.

### Identity vs lens-scoped UI capabilities (`159b7f0d`, 2026-07-25)

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

Still-open (carried to [BACKLOG](../../../BACKLOG.md), non-blocking): X-Actor is already rejected on Professional (the SEC-7a spoof guard), so only its full removal remains,
gated on **the next MAJOR tag** (restated 2026-09-07 — the API-v1 sunset apparatus this used to cite was deleted 2026-07-25). The capability spec [`okf/capabilities/security/security.md`](../../capabilities/security/security.md) is the front door for what was required, left and refused; this page stays the mechanism.

`package.ps1 -Edition Enterprise` **shipped 2026-07-25** — a superset of Professional (both the `security` and
`policy` jars are bundled), with `serve.sh`/`serve.bat` deriving the edition from bundle contents. No
runtime flag was added: `inspecto-policy` is discovered solely via its `AccessDecider` service file, so the
classpath entry is the switch. Detail in [EDITIONS.md](../../../EDITIONS.md).

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, authored-Pipeline CRUD): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/config-safety.md). It is an ops decision about whether this instance may
write — not authentication.

## An undeclared mutating route does not boot either (`ROUTE-UNGATED-DEFAULT-1`, 2026-09-16)

Route gating used to be **fail-OPEN**: `withCapability` was opt-in, so a route that declared nothing was
simply open to every authenticated caller. The audit that opened this found **83 ungated mutating routes**
and — the finding that mattered more — that only 12 could be *expressed* with the capability vocabulary that
existed: there was no name meaning "administrator", so `DELETE /spaces/{id}` could not be gated even in
principle. `canAdminister` closed the vocabulary gap; this closes the mechanism one.

**The rule.** A mutating route (`POST`/`PUT`/`PATCH`/`DELETE`) must declare a posture — a capability, or a
recorded `CapabilityManifest` exemption carrying a **category and a reason**. One that declares neither
**fails `ControlApi`'s construction**, naming the route and both ways out. ⛔ **No warn-only switch, by
decision**: a control with an off switch is not a control. CI catches an undeclared route before it can
refuse a boot in a customer's bundle; the boot check is what makes the guarantee true of the *deployed*
artifact rather than of the repository.

**Reads are open BY POLICY**, and that is a stated position rather than an omission: confidentiality sits at
the Space/ABAC layer, where a caller's data scopes decide what a read can see — ⚠ **which exists on Enterprise
only** (`inspecto-policy`, seed `space-isolation`, active only with an IdP `space` claim; `AccessDeciders` reads
absence as ALLOW), so on Professional an authenticated subject reads every hosted Space (stated 2026-09-17). Affirmed as the **compliance**
position 2026-09-16 knowing it becomes an auditor-facing claim (`controls-matrix.md` CC6). ⚠ If reads are
ever gated, that matrix line moves with the code.

**Three enforcement points, answering different questions** — none replaces another:

| Mechanism | Sees | Where |
|---|---|---|
| Boot refusal | what was **deployed**, incl. modules discovered from other jars | `ControlApi.requireDeclaredPosture` |
| CI scan | **any module's source**, before it can refuse anyone's boot | `CapabilityManifestTest` · `tools/route-gating-report.mjs --check` |
| Runtime inventory | what a **specific running server** registered, digested once per boot | `GET /audit/route-inventory` · the `route.inventory.snapshot` audit event |

⚠ The scan and the inventory genuinely disagree in count, and that is correct: the test classpath carries no
optional modules, so only the scan sees all source, and only the server knows what it loaded.

⛔ **Two traps this cost, both cheap to repeat.** (1) `CapabilityManifestTest` matches the capability as a
string literal at the registration site — passing `Roles.CAN_…` compiles and then reports drift. (2) A
capability gate **wraps** the data-scope guard, so adding one to a scoped route turns an out-of-scope request
from **404 into 403**: existence-hiding now answers second. Tests that exercise the scope guard must carry the
new capability, or they silently start asserting the gate instead.

🔴 **A `self-service` exemption must be scoped IN THE STORE, not in the reason text** (SEC review F2,
2026-09-24). `POST /notifications/read-all`, `POST /notifications/{id}/read`, `DELETE /notifications/{id}` and
`PUT /notifications/preferences` were exempt as "the caller's own" feed and preferences — but
`NotificationStore` and `NotificationPreferences` hold **one** state per Space with no recipient, so any
authenticated user could silence a category's email/webhook delivery, or mark read and archive the feed,
for everyone. All four are now `canAdminister` (fail closed; reads stay open), pinned by
`CapabilityManifestTest.sharedNotificationStateWritesStayAdminGated` and exercised WITH a Subject in
`ControlApiNotificationsTest`. ⚠ Consequence: a non-admin's bell could no longer mark read or dismiss.
**Resolved for READ state, 2026-09-25 (operator):** read state is now per reader — `NotificationReadState`,
keyed by `ApiContext.actor` (the Subject id; on Personal the `appUser` / `X-Actor` fallback) — so `POST
/notifications/read-all`, `POST /notifications/{id}/read` and the new `POST /notifications/{id}/unread` are
`self-service` exemptions again, this time scoped in the store. Delete (archive) stays `canAdminister`: it
still writes the one shared feed. See [events-metrics](../control-plane/events-metrics.md) §Notifications
for the one shared side effect a read keeps (the dedupe acknowledgement).
**Resolved for preferences, 2026-09-25 (operator; ses-sns-adapter-design §7):** `PUT
/notifications/preferences` now writes ONLY the calling Subject's sparse override
(`NotificationPreferenceOverrides`, keyed by the stable Subject id, one per-deployment file) and is a
`self-service` exemption again; the shared deployment-default grid moved to the new **`PUT
/notifications/preferences/default`, `canAdminister`** (literal capability). The pin is now "exactly these
four may be exempt". Personal (no Subject) has no override layer — the PUT keeps writing the one grid.
⛔ **The notification email address is `Subject.email()` — the IdP's `email` claim ONLY when `email_verified`
is `true`** (`OidcAuthenticator.verifiedEmail`). It is never taken from a request body and never
user-editable: an editable address would let any user route notifications, security ones included, to an
arbitrary mailbox. A Subject with no verified claim can turn on in-app delivery only. ⚠ An IdP that does not
put `email_verified` in the ACCESS token therefore yields no personal email at all — fail closed, by design.
Tests: `ControlApiSubjectPreferencesTest` (real HTTP, with Subjects), `OidcAuthenticatorTest.onlyAVerifiedEmailClaimBecomesTheSubjectsEmail`.

🔴 **The client IP is the socket peer unless a trusted proxy vouches otherwise** (SEC review F3,
2026-09-24). `ApiContext.ip` — the audit trail's `ip` and the throttle key for callers with no Subject —
took the first `X-Forwarded-For` entry from ANY caller, so both were caller-chosen: forge the audit IP, or
rotate the header to get a fresh rate-limit bucket per request. It now reads the value `ControlApi`
resolves once per request against `-Dcontrol.trustedProxies` (`TrustedProxies`: default empty ⇒ ignore
XFF; with a list, only from a listed peer, right-most untrusted hop). Operator detail in
[operations reference](../build-run/operations-reference.md).

**The sign-in row names the verified Subject and, with GeoIP, its country** (D12, 2026-09-28). A successful
`POST /auth/exchange` audits `auth.exchange` with the actor the edition's `Authenticator` resolves from the
MINTED access token (`unknown` if it cannot), and — only when an operator-supplied `-Dgeoip.db` resolves the
F3 client IP — `geo_country` plus `geo_db_build`, never a city. Trigger T5 alerts on a new country per subject.
As built: [events & metrics](../control-plane/events-metrics.md) § *Security triggers*.

**Evidence** is `compliance/evidence/route-gating.md`, whose inventory table is **generated** by
`tools/route-gating-report.mjs` and CI-enforced in `--check` mode — the document cannot say something the
code does not. Plan of record: `archived-documents/plans-archive/route-gating-compliance-plan.md`.

## A Professional/Enterprise bundle does not boot without OIDC configuration

Discovered 2026-09-07 by the packaging boot smoke, and worth stating plainly because it is a deployment
precondition, not a runtime one:

* `ControlApi` calls `Authenticators.active()` **during construction**, and `SpiSlot` runs
  `ServiceLoader` **regardless of `-Dauth.mode`**. So the mere PRESENCE of `inspecto-security.jar` on the
  classpath makes `OidcAuthenticator`'s constructor mandatory.
* That constructor requires **`-Dauth.oidc.jwksUri`** and **`-Dauth.oidc.issuer`**, and fails closed with
  a named message (`inspecto-security requires -Dauth.oidc.jwksUri (Professional edition …)`) otherwise.

⇒ Dropping the security sidecar into a bundle without also supplying the `AUTH_OIDC_*` environment
variables `serve.sh` reads is not a degraded deployment — it is one that **exits at startup**. The
message names the missing property, so the failure is legible; it is the *timing* that surprises, since
nothing about "auth is optional per edition" suggests the process will not start.

⚠ There is no way to run a Professional/Enterprise bundle "with the module present but auth off". If that is
ever wanted, it needs a guard around the SPI resolution, not a config flag.

### 🔴 This guarantee was SILENTLY LOST and restored 2026-09-12

The sentence above used to end *"— `SpiSlot.active()` has no try/catch, which is also why a missing
transitive there is a boot failure (SEC-SIDECAR-BOOT-1)"*. **That was true when written (2026-09-07) and
false by the time anyone relied on it.** `PKG-5` later routed `SpiSlot.active()` through
`com.gamma.service.OptionalSpi`, whose entire purpose is to catch `ServiceConfigurationError` /
`LinkageError` so an unloadable OPTIONAL module is an *absence* rather than a boot failure — correct for
the assistant sidecar compiled against a newer JDK, and the exact opposite of what this SPI needs.

⛔ **The effect: a Professional deployment with a mistyped `-Dauth.oidc.jwksUri` booted WIDE OPEN.**
`ServiceLoader` wraps the constructor's `IllegalStateException` in a `ServiceConfigurationError`;
`OptionalSpi` caught it, logged *"the product runs without it and its routes answer 503"*, and returned
empty — and an empty `Authenticator` means `ControlApi.dispatch` skips authentication for **every** route.
There are no "its routes" for this SPI. Nothing failed; nothing 503'd; the control plane simply served
unauthenticated, while `ControlApi`'s own constructor comment promised it would *"fail to boot instead of
silently accepting traffic"*.

✅ **Fixed:** `SpiSlot` gained a `failClosed` posture and `Authenticators` is the one slot that uses it
(`new SpiSlot<>(Authenticator.class, true)`). ⚠ The distinction it draws is **registered-but-broken** vs
**never-registered** — it has to be, because Personal ships no registration at all and that absence is
legitimate. `OptionalSpi` itself is unchanged and still fail-soft: the defect was never in that helper,
it was in the Authenticator sharing it. Pinned by `SpiSlotFailClosedTest` and
`AuthenticatorDiscoveryFailClosedTest`, mutation-proven both ways.

⚠ **A second defect surfaced while proving the first.** `SpiSlot.forTest(null)` read
`cached = Optional.ofNullable(t)`, which caches an EMPTY Optional rather than clearing the cache — so a
teardown that documented itself as restoring the classpath scan actually **pinned the slot to "no
provider" for the rest of the JVM**. `SpiSlotTest` asserted that contract and passed anyway, because it
probed an SPI with no registration, where pinned-empty and scanned-empty are the same observation. Fixed,
and the test strengthened to re-arm a slot whose provider genuinely resolves.

✅ **`AccessDeciders` FLIPPED TOO — operator decision 2026-09-13.** It had the same shape, and the cost of
absence turned out to be higher than the first reading suggested. Both PEPs read an absent decider as
**allow** — `ControlApi.authorize` returns early (`:811`) and `RowScope.visible` returns `true` (`:32`) —
and `PolicyEngine`'s seeded policies are `space-isolation` / `space-isolation-rows`. So a
registered-but-unloadable `inspecto-policy` would silently stop enforcing the **multi-tenant Space
boundary** at both route and row level, letting a subject bound to one Space address and read another.

⚠ **Its trigger is genuinely different from the Authenticator's, and the decision was made knowing that.**
`PolicyEngine` takes no configuration, so no typo can break it — only a packaging fault or `LinkageError`,
which is exactly what PKG-5 chose to make non-fatal. ⛔ The judgement: a tenancy boundary is not a feature,
so losing it silently is worse than refusing to boot. (`TokenRelays` needs nothing: absence already yields
a 503 at `AuthRoutes:86`, which is fail-safe.)

## A Space's config cannot name another Space's directory (`CROSS-SPACE-JAIL-1`, 2026-09-24)

The allowed roots every config-path check uses (`SafetyPolicy.defaultPolicy()` → the 422 write gate and
every run-time `PathJail`) were the **union** of all hosted Space bases, so on a multi-Space host a job in
Space `acme` could name `beta`'s data directory by absolute path and pass. Probed live (HTTP 200) and fixed:
a Space's allowed roots are **its own base plus the operator-declared `-Dassist.safety.roots`**, keyed on the
thread's Space binding. This is the path-level twin of the Enterprise `space-isolation` policy above — and
unlike it, it holds in **every** edition. Detail, caveats and pinning tests:
[Config safety](../config/config-safety.md#a-spaces-allowed-roots-declared--its-own-base-tier-3-2026-08-14-narrowed-2026-09-24).

## The `sql.template` job runs behind SqlGuard and a sealed connection (`SQL-TEMPLATE-SANDBOX-1`, 2026-09-24)

`SqlTemplateJob` ran `CREATE TABLE … AS <authored sql>` on a plain `jdbc:duckdb:` connection. Probed and
confirmed on six vectors, every one of which succeeded: `read_csv` of a file outside the data root, a
`FROM '<file>'` replacement scan, a `; COPY … TO` writing outside the data root, a `; ATTACH` creating a
database outside it, `; LOAD httpfs`, and `read_csv('http://127.0.0.1:<port>/x')` — the local test server
was **hit twice**. The fix reuses both existing layers, it invents neither:

1. **Lexical — `SqlGuard.check`** on the **substituted** text (a `$param` becomes a string literal, and a
   literal in `FROM` is a file read, so only the final text can be judged). The same allow-list
   `transform.sql` (`RowShaper`) applies.
2. **Connection — `SqlSandbox.disableExtensionAutoload`** before the trusted source views are registered,
   then **`SqlSandbox.sealAllowing(conn, [dataDir])`**: `allowed_directories` = the data root (trailing `/`,
   so `data-other` is not admitted), `enable_external_access=false`, `lock_configuration=true`. The sink,
   Decision Rule route and quarantine writes all land under the data root, so they still work.
   *(Since `ENGINE-INMEMORY-UNSEALED-1`, 2026-10-03: the autoload-off + `allowed_directories` half is done by
   `DuckDbUtil.openInMemory(spill, [dataDir])` itself, and the job adds `DuckDbUtil.lockConfiguration` before the
   authored SQL — see `okf/backend/engine/duckdb.md`.)*

Both layers are mutation-checked independently in `SqlTemplateJobSandboxTest`: removing the guard turns
`aFileLiteralInsideTheDataRootIsStillRefusedByTheGuard` red (the seal admits that file), and removing the
seal turns `aFileFunctionTheGuardMissesIsStoppedByTheConnectionSeal` red.

🔴 **That second probe is also a finding against `SqlGuard` itself:** `parquet_metadata('<any file>')` passes
the lexical check (it is not `read_*`/`*_scan`, and `FROM parquet_metadata(` is not path-like), and it
returns the file's schema, row counts and min/max statistics. Any caller that relies on `SqlGuard` **alone**
on an unsealed connection — `QueryExecutor` documents exactly that posture — was exposed. Fixed the same day
as `SQLGUARD-PARQUET-METADATA-1`, next section.

## SqlGuard refuses every table function except path-free generators (`SQLGUARD-PARQUET-METADATA-1`, 2026-09-24)

`SqlGuard` had a hand-kept block-list (`read_*`, `*_scan`, `glob`, …) and the file **inspectors** were not on
it. The fix makes the function check **fail-closed over table functions**: every function call in the text
— bare **or double-quoted**, because DuckDB resolves `"PARQUET_SCHEMA"('<file>')` as the function (the old
`\bread_csv\s*\(` regex was bypassed by `"read_csv"(…)` too) — is tested by whole name against one pattern,
and the only table functions allowed are the generators in `SqlGuard.SAFE_TABLE_FUNCTIONS`: `range`,
`generate_series`, `unnest`, `repeat`, `repeat_row`, `json_each`, `json_tree`, `pg_timezone_names`,
`icu_calendar_names`. Refused, beyond the old list: `parquet_metadata`/`_schema`/`_file_metadata`/
`_kv_metadata`/`_full_metadata`/`_bloom_probe`, `duckdb_*` and `pragma_*` (settings, databases and
extensions expose paths), `which_secret`, `enable_logging`/`enable_profiling` (take a storage path),
`force_checkpoint`, `truncate_duckdb_logs`, `json_execute_serialized_sql`, `summary`/`histogram*`, and the
autoloadable extensions' surfaces (ducklake, postgres, sqlite, mysql, odbc, iceberg, delta, azure, aws,
excel `read_xlsx`, spatial `st_read*`, vortex, lance, motherduck `md_*`, ui, tpch/tpcds `dbgen`/`dsdgen`).

**Derived, not hand-typed — `SqlGuardFileFunctionContractTest`** (DuckDB `1.5.2.1`): it LOADs every
installed extension best-effort, reads `duckdb_functions()`, and asserts every `table`/`table_macro`
function is refused unless `SAFE_TABLE_FUNCTIONS` names it (and every SAFE entry is still a table function
and still passes), and every scalar whose parameter is *named* like a path (`path`/`file`/`url`/`uri`/
`location`/`directory`) is refused unless it is one of the pure string splitters `parse_path`/
`parse_dirname`/`parse_dirpath`/`parse_filename`. **A DuckDB upgrade that adds a table function fails the
build until it is classified.** Extensions not installed offline (iceberg, delta, spatial, …) cannot be
enumerated, so a short hand-kept list pins them. Mutation-checked: dropping `parquet_\w+` from the pattern
reports exactly the six `parquet_*` inspectors; dropping the quoted-name handling turns
`aQuotedFunctionNameIsStillRefused` red.

⚠ **Known false rejects, accepted:** the `histogram(x)` **aggregate** shares its name with the table
macro and is refused too, as is a CTE with a column list named like a blocked function
(`WITH summary(a) AS …`). A false reject costs a repair round; a false accept is a file read. The
`read_parquet('<registered path>')` masking in `GuardedDerivedTableEmitter` is unaffected — it masks the
call to an identifier before the guard runs.

**Layer two — DuckDB's own parse tree (`SQLGUARD-COMMA-RELATION-1`, closed 2026-09-24).** The
replacement-scan check (`RELATION_REF`) judged only the token after `FROM`/`JOIN`, so
`SELECT b.secret FROM (SELECT 1) a, '<any file>' b` passed the guard and returned the file's rows on an
unsealed connection. Not patched with another regex: text the lexical checks pass is now parsed by DuckDB
itself — `SELECT json_serialize_sql(?::VARCHAR)` on one lazily-opened, in-memory, extension-autoload-off,
sealed connection (`SqlGuard.parseTreeViolations`) — and the tree is walked. Refused: an error from the
parser (it serializes **SELECT only**, so DML, `PIVOT` statements, `EXPLAIN` and syntax errors all refuse),
more than one statement, a relation kind outside `JOIN`/`SUBQUERY`/`BASE_TABLE`/`TABLE_FUNCTION`/`EMPTY`/
`EXPRESSION_LIST`/`PIVOT` (so `SHOW_REF` — `DESCRIBE`/`SUMMARIZE`/`SHOW` — refuses), a `BASE_TABLE` whose
catalog, schema or name contains `/ \ . : * ? [` (a string literal and a quoted identifier are the same
node; the caller's `trustedRelation` is exempt only as an exact unqualified name), a `BASE_TABLE` named
`duckdb_*`/`pragma_*` (the catalog **views** — `FROM duckdb_databases` with no parens listed every attached
database's file path, and the lexical scan only sees a name followed by `(`), a `TABLE_FUNCTION` not in
`SAFE_TABLE_FUNCTIONS`, and any function call `BLOCKED_FUNCTION` names. Because every relation node is
judged where it sits, FROM-list commas, subqueries, CTE bodies, set-op branches, `LATERAL`, scalar and
lambda subqueries and the `FROM 'file'` shorthand are all covered. The lexical layer stays first (defence
in depth, and it keeps its messages for what it already caught). **Cost: ~0.2 ms/call warm** (DuckDB
1.5.2.1; 0.6 ms in the surefire JVM including JIT) — no cache. `SqlGuardParseTreeTest` (mutation-checked:
skipping layer two turns the comma probe, the `"x.csv"` identifier and the trusted-relation cases red).
`SqlTemplateJobSandboxTest.aFileLiteralTheGuardMissesIsStoppedByTheConnectionSeal` now isolates the seal
by swapping the job's guard for a pass-everything one (`SqlTemplateJob.guard`, a test seam) — mutation-checked:
removing `sealAllowing` turns it red.

⚠ `SqlSandbox.open` was not reused for the job: its interactive memory/timeout caps (`assist.sql.*`) do not
fit a batch job. The two static helpers are the same statements `open`/`seal` now call, so there is still
one definition of the lockdown.

## No import may write an identity, access or settings file (`SEC-IMPORT-ROLES-ESCALATION-1`, 2026-09-26)

**The escalation it closed.** `POST /import` (gated `canAuthorWorkbench` only) unpacked every zip entry under
the Space config root (`DataSourceRoutes` → `BundleImporter.writeConfig`). A builder importing a `roles.toon`
got 200, and `Roles.effective` then gave their `developer` role `canAdminister`. The sibling doors were
`POST /pipelines/import?conflict=overwrite` (satellites land in the REGISTERED pipeline's directory, which
may be the config root itself) and `POST /bundle/import` (`access-profile` / `access-catalog` items, and
`pipeline` items whose closure carries satellites). Two more fixed-name loaders were reachable the same way:
`demo-users.toon` (`inspecto-demo-auth` `DemoUsers.java:31`, read from every Space's config root, so a
`roles: super` Demo User) and `agent/` (`AgentWriteRoot` → `agent/policy.json`, `agent/approvals.jsonl`).

**One judge, four layers, every door** — `com.gamma.service.ImportPaths`, called by `/import` (all entries
up front), `/pipelines/import` (every satellite, every companion, the pipeline file), `/bundle/import`
(`PipelineBundleRoutes.judgeClosure` over every `pipeline` item BEFORE any item is written) and the
`BundleImporter.writeConfig` backstop:

1. **Segment rules**, on every platform: each segment matches `[A-Za-z0-9][A-Za-z0-9._-]*`, never ends in
   `.`, never contains `..`, is never a Windows device name (`CON`, `NUL`, `AUX`, `PRN`, `COM1-9`,
   `LPT1-9`, with or without an extension). So no `~` (8.3 short names like `ROLES~1.TOO`, `PENDIN~1`),
   `:`, space, control character or leading dot. The manifest's own spelling is judged before any `Path`
   is built from it (`roles.toon ` does not even parse as a Windows path).
2. **A shape allowlist**: a `.toon` / `.csv` config; at the config ROOT only `_pipeline.toon`,
   `_enrich.toon`, `_schema.toon`, `_job.toon`, `_connection.toon`, `_mapping.csv`, `_structure.csv`,
   `.grammar.toon`, `_grammar.toon`, `_profile.toon`; under `registry/` exactly `registry/<kind dir>/<name>`
   for an importable kind (never the access config); in any OTHER subdirectory the same data-source
   suffixes plus `_view.toon` (`ImportPaths.SUBDIR_SUFFIXES`), and never a reserved root-level name one
   directory down (a `roles.toon` in any subfolder; `registry/` excepted, where a name is a component id). A file a carried
   config names through a **real reference key** may also carry any plain name at the root or in a
   subdirectory and may be `.asn` / `.asn1` / `.sql` — that is all a reference buys. ⛔ **No
   suffix-scanned ops or semantic config, anywhere, referenced or not** (`ImportPaths.REFUSED_SUFFIXES`,
   `SEC-IMPORT-OPS-CONFIGS-1`, 2026-09-26): `*_workflow.toon`, `*_caserule.toon`, `*_tagrule.toon`,
   `*_tag.toon` (`OpsEngineProvider.loadConfigs`), `*_meta.toon`, `*_rca.toon`, `*_job_template.toon`
   (`ServiceBootstrap.buildFrom`), and the loader-less `*_escalation.toon` / `*_queue.toon`. The boot scans
   (`ServiceBootstrap.resolveBySuffix`, a recursive `Files.walk`) read them from ANY depth, so a directory is
   no containment: an `incident_workflow.toon` in a zip subfolder returned 200 and the last workflow per object type wins,
   which replaced the Incident lifecycle the Disposition gate is keyed on. None travels in a data-source or
   Pipeline bundle; each has its own validated route (`POST /cases/rules`, `/tags*`). The real keys (`ImportPaths.referenceValues`) are the loader's own:
   `processing.schema_file`, `schemas[].schema_file`, `parsing.grammar` / `processing.grammar`,
   `processing.mapping_file`, the `segments` values, `ingester_config.grammar`,
   `parsing.asn1|plugin.grammar_file` / `grammar` / `profile_file` and, inside a Decode Profile,
   `asn1.grammar_file` / `asn1.segments`; for an Enrichment, `transform_file`. ⚠ Before this, ANY string
   of a carried pipeline counted, so `notes.r0: "demo-users.toon"` wrote the Demo User table; and every
   `/pipelines/import` satellite counted as referenced.
3. **The denylist** `ReservedConfigPaths`: `roles.toon`, `demo-users.toon`, `access-policies.toon`,
   `approval.toon`, `offers.toon`, `grants.toon`, every settings document (`branding`, `geo`,
   `link-analysis`, `pipeline-history`, `icon-map`, `scheduler`, `nav-menus`, `notification-preferences`,
   `partition`, `space`), `rename.journal`, `dataset-publications.tsv`, `day-manifest.tsv` (LA-DAILY-INGEST-1 T8 delivery manifest, operator 2026-10-06); the directories `pending-changes/`,
   `recon-state/`, `.history/` (anywhere), `audit/`, `agent/`, `expectation-baselines/`,
   `registry/access-catalog/`, `registry/access-profiles/`. `approval.toon` and `pending-changes/` are
   reserved as plain paths ahead of maker-checker, which is not on `master`.
4. **A real-path check**: the nearest existing parent is resolved with `toRealPath` and compared against
   every reserved file and directory (`isSameFile` / real-path prefix), failing closed on any I/O error.

⚠ For a DIRECTORY alias on a Space where the directory does not exist yet (`audit./x.toon`,
`pending-changes./x.toon`), the trailing-dot rule is the only layer that fires: the string is not
`audit/`, and the real path has nothing to compare against. `ImportPathsTest` pins that the refusal comes
from that rule.

**All-or-nothing.** Every write goes through `ImportJournal`, which records each target's prior bytes (or
its absence) and the directories it creates. `/import` runs every refusal it can before the first write
(the judge, the id conflict, and a pipeline id registered from a different file); a later refusal (the
integrity gate's 422, a registration's 422/409) unregisters what the import registered, restores the
connections it replaced, rolls the tree back byte for byte, and re-reads the configs. `/pipelines/import`
parses, judges and conflict-checks its companions before writing anything, and a SaveGate ERROR now restores
an overwritten satellite (the old cleanup deleted every satellite, pre-existing ones included).
**A rollback never clobbers a newer write** (`IMPORT-RESIDUALS-1` (3), 2026-09-28). There is no config write
lock, so another request can save a file between an import's write and its rollback. `ImportJournal` also keeps
a SHA-256 of what the import last wrote to each file and restores (or deletes) a file only while it still holds
exactly those bytes; a file changed or deleted since is left as the other writer made it, logged, and named in
the refusal — a `notRolledBack` list on `/import`'s 422 body and on a `/pipelines/import` SaveGate refusal,
`; changed concurrently, not rolled back: [...]` appended to any other refusal's message (a `/bundle/import`
pipeline item's `failed` row, and the 500 of a write failure — `ImportRollback`). The list lives on
`ImportJournal.notRolledBack()`, so it survives a rollback that throws and one run inside
`BundleImporter.writeConfig`. **A rollback that itself fails never replaces the refusal**: the 4xx (or the original
failure) stays the answer, with `rollbackIncomplete` / `; rollback incomplete: …` and the failure attached as
suppressed and logged. `SpaceManager.createFromBundle` does not report the list — it seeds a brand-new Space no
other request can write yet. Chosen over serialising imports behind a lock because none exists to reuse (`ComponentStore`
and `/config/write` write through `AtomicFiles` unlocked) and a new one would have to be taken by every config
writer to help. ⚠ The compare and the restore are two steps, not an atomic swap: a write landing in that
sub-millisecond gap is still overwritten. Tests: `ImportJournalTest` (interleaved writes),
`ControlApiImportConcurrentRollbackTest` (real HTTP; the `ImportJournal.beforeRollback` test seam saves the file
between the write and the rollback).

**A whole-Space export is not importable into an existing Space** (operator 2026-09-29, `IMPORT-RESIDUALS-1`
(1) decided). The zip `GET /spaces/{id}/export` produces (manifest `kind: space`) carries the suffix-scanned ops
configs no import may write, so `POST /spaces/{id}/import` refuses it up front with **403** and one message: *a
whole-Space export cannot be imported into an existing Space; import data sources one at a time, or create a new
Space from it* (`DataSourceRoutes.WHOLE_SPACE_IMPORT_REFUSED`, pinned by `ControlApiImportReservedPathsTest`).
The two supported flows are per-data-source export → import, and seeding a NEW Space from the zip through Space
creation (`SpaceManager.createFromBundle`, a separate path this check does not touch). Splitting a whole-Space
zip into its kinds' own validated routes is filed, not built (P3 `IMPORT-WHOLE-SPACE-SPLIT-1`).

**The loader guard.** `ImportLoaderInventoryTest` scans every reactor module's `src/main/java` for fixed
names: literal `.resolve("a").resolve("b")` chains, `AgentWriteRoot.resolve("x")` (as `agent/x`), and any
string literal naming a `.toon` / `.json` / `.jsonl` / `.tsv` / `.journal` / `.key` file. Each must be
reserved, be a name the segment rules refuse (a suffix literal), or sit on its `ALLOWED` table with a written
reason. Un-reserving `demo-users.toon` or `agent/` turns it red. Its SUFFIX half scans for every suffix
literal (`"_x.toon"`, `".grammar.toon"`, `"_x.csv"`): each must be refused by `ImportPaths` at the root, in a
subdirectory and under `registry/`, referenced or not, or sit on `SUFFIX_ALLOWED` (the data-source shapes, and
the data-dir output sidecars) with a reason. Un-refusing `_workflow.toon` turns it red.

**An import is never a way around a kind's own route gate** (`IMPORT-CONNECTION-JOB-GATE-1`, session decision
2026-09-27, overturnable). Every import door is `canAuthorWorkbench` (a new Space: `canAdminister`), but some
kinds it can carry are written on their own route under a different capability. `ImportCapabilityGuard`
requires THAT capability per carried item, at all four doors — `/import` and `/spaces/import` over the raw
entries, `/pipelines/import` over its zip, `/bundle/import` over its items and each `pipeline` item's closure
files — before the first write: the first missing capability is 403 naming the kind and the capability, and
nothing is written.

| Carried kind | Recognised by | Needs (its own route) |
|---|---|---|
| Connection | a `*_connection.toon` file, a `connection` item | `canOnboardConnections` (`/connections`) |
| Alert Rule | `registry/alert-rules/`, an `alert-rule` item | `canAuthorAlertRules` (`/alerts/rules`) |
| Findings Spec | `registry/findings-specs/`, a `findings-spec` item | `canManageIncidents` (`/components/findings-spec`) |
| `event_prune` Job | a `*_job.toon` file or `job` item parsed by `JobConfig` as `maintenance`/`event_prune` | `canAdminister` (`/jobs`) |

Every other Job, and every other importable kind, is `canAuthorWorkbench` on its own route too — no mismatch
(swept 2026-09-27 over `CapabilityManifest` and the in-handler `requireCapability` calls). The access config,
role table and settings documents are not on the table because no import writes them at all (below). ⚠ A
`/bundle/import` `event_prune` Job is still refused per item even for an administrator (`JobBundleSource`,
`ASSURE-AUDIT-CHAIN-1`); the guard only makes the non-administrator case atomic. Tests:
`ControlApiImportCapabilityGateTest` (real HTTP, armed Subject; mutating the check to a no-op turns all 7 red,
403 → 200).

**Dedicated-only kinds** (`IMPORT-DEDICATED-ONLY-KINDS-1`, operator decision 2026-09-27). `access-profile`,
`access-catalog` and `requirement` are written through their dedicated routes ONLY (`/access/*`, which validates
under `canConfigureAccess`; `/requirements`, whose triage lifecycle only `canTriageRequirements` advances).
`ImportCapabilityGuard.DEDICATED_ONLY` refuses them, 403 for ANY caller, on the generic
`/components/{kind}` POST/PUT/DELETE/version-restore (before: a `canAuthorWorkbench` builder could DELETE an
Access Profile — which WIDENS that subject's access — or save a Requirement with `status: delivered`) and on
every import door except Space creation (`/spaces/import`, which may seed them). ⚠ **Breaking change:** a
`/bundle/import` or `/import` bundle carrying a `requirement` (item or `registry/requirements/` file) is
refused WHOLE — nothing is written; move Requirements with `POST /requirements` instead.
⚠ **`POST /spaces/import` is `canAdminister` always** (operator 2026-10-03; the `recovery-route` exemption and `SpaceRoutes.requireAdministerUnlessRecovering` are gone, so the blank-server hole where ANY authenticated caller could seed `access-profile` / `access-catalog` / `requirement` files is closed). Pinned by `ControlApiSpacesTest` (a no-capability Subject gets 403 on an empty container). ⚠ `checkFiles` classifies an entry by its FIRST `/registry/` segment — safe only while no import door re-roots entry paths (a door that strips or re-prefixes paths before writing must re-classify). `POST /spaces` is the same since 2026-10-03 (`TEMPLATE-RECOVERY-IMPORT-GATE-1`). A fresh install is not bricked: with no Space hosted `ControlApi.authenticate` binds no roles root, so `Roles.effective` serves the seed table and the IdP `admin` role holds `canAdminister`.
**Classification is spelling-proof:** each raw entry is classified after `ImportCapabilityGuard.normalizedPath`
(case, `.`/empty segments, the trailing dots and spaces Windows drops), and a file under `registry/` whose kind
cannot be told is refused (defence in depth behind `ImportPaths`' shape rule); `DecisionRuleGuard.guardImport`
uses the same normalisation. **Completeness is derived:** `ImportCapabilityGuardTest` walks
`CapabilityManifest` for every `ComponentStore.WRITABLE_TYPES` kind (plus `connection`) and fails when a kind's own
write route is stricter than `canAuthorWorkbench` and neither `KIND_CAPABILITY` nor `DEDICATED_ONLY` covers it
(mutation-checked: changing the alert-rule entry turns two cases red). Real-HTTP tests:
`ControlApiDedicatedOnlyKindsTest`. A `/bundle/import` `kpi` item also meets the `/components/kpi` save gate
(`KpiRoutes.requireMeasure`: Dataset exists and is readable, fields in its Schema, `timeField` a DATE/TIMESTAMP).
`SpaceManager.createFromBundle` (a new Space, `canAdminister`) keeps only the segment rules.

Tests: `ControlApiImportReservedPathsTest` (real HTTP, armed Subject; every alias at all three doors on a
Space with and without a `roles.toon`, 403 and a byte-identical config tree; and every ops suffix in a
subdirectory, under `registry/`, at the root and through a real reference key, at `/import`, as a
`/pipelines/import` satellite and a `/bundle/import` closure file), `ImportPathsTest`,
`ImportLoaderInventoryTest`. The Incident gate's own half (a workflow with a `CLOSED` terminal state) is in
the incidents concept, [`incidents.md`](../../capabilities/incidents/incidents.md).

## Maker-checker: a held config change needs a different person (`ASSURE-MAKER-CHECKER-1`, 2026-09-26)

A Space's **Approval Policy** (`approval.toon`, `PUT /settings/approval`, gated `canAdminister`) can require
approval per config kind. Under it an authoring write becomes a **Pending Change** (answered `202`) and is
applied only when `POST /pending-changes/{id}/approve` succeeds. The decide gates follow the Link Analysis
four-eyes precedent (D-U7): `canApproveChanges` on the route (a literal gate, in `CapabilityManifest`) → an
authenticated Subject, else 403 → already decided or expired 409 → the kind's `approverCapability` (default
`canApproveChanges`) → with `fourEyes` (the default) the author deciding their own change is 403, whether
approving or declining. `canApproveChanges` is seeded to `admin` only (`super` holds everything).

- **A policy that could never approve is refused.** Requiring approval on a build with no Authenticator is
  422 at save: without Subjects an author and an approver cannot be told apart.
- **The apply runs AS THE AUTHOR, re-checked at apply time** (D-P13, operator 2026-09-26 — a checker need not
  be a builder). Approve replays the author's original request in-process through the same route
  (`ApiContext.replay`) with a Subject for the author: their recognised roles, recorded at propose time, are
  re-resolved against the Space's role table and Access Profiles as they stand NOW
  (`PendingChanges.authorNow`), exactly as `OidcAuthenticator` resolves them. The approver needs only the
  approver capability, so the seeded `admin` (no `canAuthorWorkbench`) approves a builder's change. An author
  who has since lost the grant gets 403 with that reason, and the change stays pending. ⚠ Role MEMBERSHIP is
  the IdP's view as of the proposal — a server cannot re-ask the IdP about a user who is not in the request.
  🔴 With an Authenticator that stamps no roles the apply FAILS CLOSED (403, "the author's roles are
  unknown"); a propose-time capability snapshot is recorded for the reviewer and never trusted.
- **Approve replays only a maker-checker route, from a record this server wrote.** Two checks run BEFORE
  anything is dispatched: the record's MAC (HMAC-SHA256 over its canonical JSON, a per-Space key) must
  verify, else the record lists as `invalid` and approve / decline are 409; and the recorded method + path must
  be on `PendingChanges.REPLAYABLE` — exactly the routes that reach the hold before they write, pinned to the
  scanned inventory by `ConfigWriteFunnelTest#theReplayAllowlistIsExactlyTheRoutesThatHold` — else 409. Found
  by re-verification: a forged record naming `PUT /access/roles` had rewritten `roles.toon` while approve
  reported "not applied". ⚠ The MAC defends against a record written through any door that cannot READ the
  key (an import, an upload); a local administrator can read both.
- **The author may withdraw their own change** (`POST /pending-changes/{id}/withdraw`,
  `ASSURE-MAKER-CHECKER-RESIDUALS-1` (2), 2026-09-28). It closes `withdrawn` — terminal, `decidedBy` = the
  author, the optional `reason` as `decisionReason`, MAC-signed and audited as a decline is. No capability on
  the route (a recorded `self-service` exemption in `CapabilityManifest`): the only person who may withdraw is
  the Subject who proposed it, so the author check IS the gate. Gates: no authenticated Subject 403 (Personal
  is refused, as for decide) → write root 503 → `reason` over 500 chars 422 → unsafe id 422 → unknown 404 →
  integrity 409 → not pending (decided, withdrawn, expired) 409 → not the author 403. It runs under the same
  store lock as decide, so a withdraw racing an approve leaves one outcome (`PendingChangesMultiPodTest`). The
  SPA offers **Withdraw** on the Pending Changes inbox to the session Subject (`SessionService.actor`) who
  authored a waiting change.
- **Every step is audited** as an `AUDIT` event, `actionCategory: configuration`: `pending-change.proposed`,
  `.approved`, `.declined`, `.withdrawn`, `.expired`, `.stale`, `.apply-refused`, and `approval-policy.changed` (the actor
  and the policy before and after, JSON). ⚠ Know which row says what: the approve REQUEST's own route-level
  row (`AuditTrail`) has the **approver** as actor; the replayed WRITE's row has the **author** as actor plus
  `approvedBy` and `pendingChange`; `pending-change.approved` has the approver as actor and the author as an
  attribute.
- **An import writes only an allowlist of shapes** (re-verification finding 1, `ImportPaths`) — a string
  denylist alone was walked by Windows aliases (`roles.toon.`, `ROLES~1.TOO`, `PENDIN~1/`, `audit./`). Three
  layers, all required, on `/import`, `/pipelines/import` satellites and the `BundleImporter` backstop: segment
  rules on every platform (plain names only — no trailing `.`, `~`, `:`, space, control character, leading dot,
  `..` or Windows device name); the allowlist (a file another carried config references, or a `.toon` / `.csv`
  of a known shape — at the config root only the conventional suffixes, under `registry/` only
  `registry/<importable kind>/<name>`); and the denylist below plus a real-path check (`toRealPath` +
  `isSameFile` / prefix against every reserved file and directory, fail closed on I/O errors). A carried kind
  whose own route is stricter needs that route's capability (`IMPORT-CONNECTION-JOB-GATE-1`, above).
- **An import may never write the files a narrower gate owns** (D-P14, `ReservedConfigPaths`): `roles.toon`,
  `access-policies.toon`, the Access Catalog and Access Profiles, `approval.toon`, `pending-changes/`, every
  Space settings document, `rename.journal`, `recon-state/`, `.history/`, `audit/`. `/import` and a
  `/pipelines/import` satellite refuse them (403), `/bundle/import` refuses `access-*` items, and
  `BundleImporter.writeConfig` refuses them as a backstop. 🔴 **This closed a privilege escalation older than
  maker-checker**: `POST /import` needs only `canAuthorWorkbench`, and until 2026-09-26 a zip carrying
  `roles.toon` rewrote the role table the Authenticator resolves every Subject from (reproduced: 200, the
  builder's `developer` role gained `canAdminister`); `/pipelines/import ?conflict=overwrite` could land it as
  a satellite, and `/bundle/import` could carry `access-profile` items. `SpaceManager.createFromBundle` (a NEW
  Space, `canAdminister`) still writes them — there is nothing there yet to overwrite.
- **Deciding is a cross-process compare-and-set** (`ASSURE-MAKER-CHECKER-MULTIPOD-1`, 2026-09-27). Every
  read-check-write on the Pending Change store — propose (the one-pending-per-target check), list/read (expiry),
  approve, decline, withdraw — runs under `PendingChanges.underStoreLock`: the JVM monitor, then an OS-level
  `FileChannel.tryLock()` on `<write-root>/pending-changes/.lock`, so two Pods sharing a Space's directory
  serialise. The file-lock wait is BOUNDED (default 5000 ms, `-Dinspecto.pendingChanges.lockWaitMs`): a Pod
  that stalls holding it yields 503 `STORE_BUSY` (retryable) on the others, never a hung request thread
  (`PendingChangesLockTimeoutTest`). Deciding re-reads the record under the lock and moves `status` off `pending` only if it is still
  `pending`; the APPLY (the replay) runs inside the same lock, so a loser can never apply. The loser gets 409
  `pending change '<id>' is already approved (decided by <who> at <when>)`. Records are saved atomically (temp
  + `ATOMIC_MOVE`). ⚠ There is no OperationalDb-backed Pending Change store, so no conditional-UPDATE variant
  exists; the lock needs a filesystem whose locks are honoured across hosts (a local disk or a correctly
  configured shared volume — NFSv3 without lockd is not). Proven by `PendingChangesMultiPodTest` (two
  control planes over one directory racing approve/approve and approve/decline over real HTTP, and two JVM
  processes racing on the lock).
  **(operator, 2026-09-28): kept closed — deciding a Pending Change takes a file lock and re-checks the record is
  still pending (proven by `PendingChangesMultiPodTest`); a live two-pod test is owed at the first HA deployment** (tracked on `BACKLOG.md` §2, *Deployment topology live validation*).
- The AI-agent approvals inbox (`/agent/approvals*`) is a different thing and is unchanged.

As-built detail (the hold, the funnels, what is governable): [config safety](../config/config-safety.md#maker-checker--pending-changes-2026-09-26).
