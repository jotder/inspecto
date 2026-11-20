---
type: Concept
title: Auth & Security
description: Auth-free core; the Authenticator/Subject/TokenRelay/AccessDecider SPIs; the shipped inspecto-oidc module (split into inspecto-oidc, inspecto-secrets, inspecto-geo-country by D-MR6) (Standard, OIDC via Keycloak/WSO2, data-driven roles, Access-Profile + sharing enforcement); the Advanced inspecto-policy ABAC engine (authored Access Policies, space isolation, decision audit); the separate -Dassist.write.root write-gate.
resource: providers/inspecto-oidc/, providers/inspecto-policy/
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-advanced]
timestamp: 2026-07-24T00:00:00Z
item: module.access
audience: internal
status: current
verified: 2026-11-20 code-reviewed
code: platform/inspecto-access
---

# Auth & Security

**All auth was removed from `master`/the common core on 2026-06-16.** Basic is genuinely auth-free: every
[`ControlApi`](../processor/api-control-api.md) route is open, the SPA boots straight to `/dashboard`, and there
is no token paste / guard / interceptor. The removed hand-rolled bearer-token plane (per-route `Scope`,
`-Dcontrol.token`, the Angular token screen) is gone.

**Standard re-adds auth via SPIs + the shipped `inspecto-oidc` module.** The core defines three SPIs in
`com.gamma.control`: **`Authenticator`** (validates a request, yields a subject), **`Subject`** (a record of
`id` + capabilities), and **`TokenRelay`**. `providers/inspecto-oidc/` (artifactId `inspecto-oidc`, **34**
tests — measured 2026-09-08: 24 + 7 + 3; an earlier "41" was never true of the tree) implements them: `OidcAuthenticator` (Nimbus JOSE+JWT), `RoleMapper` (roles from IAM claims), and
`OidcTokenRelay`. It joins the reactor **only under an edition profile** (`edition-standard`, `-preview` or
`-advanced`) — the default
build never compiles it (verify with `-Pedition-standard`); because it's a
[build flavor](../../layer/platform/architecture-editions.md), the core still carries zero auth code.

**BFF session shape.** The browser never holds tokens: `POST /auth/exchange|refresh|logout` run the OIDC
exchange server-side and keep the refresh token in an **httpOnly `inspecto_rt` cookie**
(`SameSite=Strict`, plus an `Origin` check for CSRF). HTTPS is served by the pure-JDK `HttpsServer`. The UI
discovers the mode via `GET /bootstrap` → `features.authMode` and its OIDC flow is a no-op on Basic.

**Sign-out is three layers, and all three have to fire** (fixed 2026-07-26; BACKLOG §5 tracked only the
third). `SessionService.logout()` (1) POSTs `/auth/logout` so the backend revokes and clears the
`inspecto_rt` cookie, (2) drops the in-memory access token, and (3) — **RP-Initiated Logout 1.0** — sends the
browser to the provider's `end_session_endpoint` with `client_id` + `post_logout_redirect_uri=<origin>/sign-in`,
so the IdP's SSO session ends too. Without (3) the next sign-in completes with no credential prompt.
**`id_token_hint` is sent since 2026-10-13** (it was omitted until then): the BFF keeps the ID token in a second
HttpOnly cookie, `inspecto_idt` (`AuthRoutes.ID_COOKIE`), and `POST /auth/logout` answers `endSessionUrl` with the
hint, `client_id` and `post_logout_redirect_uri` (`OidcTokenRelay.endSessionUrl`), so Keycloak skips its
confirmation page. The ID token never reaches the SPA; when the server returns no URL, the SPA builds the hint-less
`client_id` redirect itself, as before.

- **`endSessionUrl` comes from the server first** — `bootstrap.auth.endSessionUrl` falling back to
  `environment.oidc.endSessionUrl`, sitting beside `authorizeUrl`. It was declared config, never derived or
  discovered (2026-07-26, D15's call for `auth.oidc.tokenEndpoint` applied again: a hardcoded IdP host in the SPA
  is exactly what caused the leaked-secret incident). Since 2026-10-13 the server resolves it, explicit
  `-Dauth.oidc.endSessionUrl` first, then the discovery document, then the `keycloak` preset (*Provider preset +
  discovery*, below), and publishes it in `/bootstrap` `auth`. Leave it unresolved and sign-out degrades to layers
  (1)+(2) — all a provider without the endpoint can support anyway.
- **`SessionService.redirect()` is the single seam that leaves the SPA** (authorize + end-session). It exists
  because jsdom makes `window.location.assign` non-configurable, so it is the only way to unit-test either
  redirect.
- **Basic renders no user menu at all.** The auth-free shell has no principal to name and no session to
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

- **Data-driven roles (R1).** Role→capability/data-scope grants are authorable: `com.gamma.access.Roles` (`platform/inspecto-access`)
  holds the seed table + doc grammar; `ControlApi` stamps the bound space's config root
  (`Roles.ATTR_CONFIG_ROOT`) pre-auth and `Roles.effective(ex)` overlays a per-space `roles.toon` **per role
  name** (authored `[]` revokes; unnamed seed roles keep defaults), cached by the file's text so edits apply next request,
  no restart. `RoleMapper` lost its hardcoded switch and resolves through the table. **Fail-closed:** an
  existing-but-unreadable `roles.toon` suspends all role grants. `GET/PUT /access/roles` author it (PUT gated
  `canConfigureAccess`); Settings ▸ Access ▸ **Roles** tab (R5) edits it with source badges + the
  profile-deny strike-through overlay. **Per-product view (`LA-PRODUCT-NEXT-1` phase 2, 2026-10-30):**
  `GET /access/roles` also answers `products` — product → the capabilities its screens gate, from
  `CapabilityManifest.PRODUCTS` (today `link-analysis`; `CapabilityManifestTest` derives the floor: every
  capability an `/inv` or `/geo` route demands must be tagged). The Link Analysis app's Access page mounts the same
  Roles editor with `product="link-analysis"` and LA labels, so it lists only those capabilities ("+N used
  elsewhere", *Show all capabilities* to see the rest). A tag is presentation, never authorization.
  `GET /access/roles/{name}/effective` (ungated, like the other `/access` reads) answers one role's capabilities,
  each `allowed` or denied with its `reason` — granted by the role table, or denied by the role's Access Profile as
  `AccessGrants` resolves it against the SAVED catalog; 404 for an unknown role. Access Policies decide per request
  and are not folded in: `policies: true` says an engine is active, and `POST /access/policies/preview` shows them.
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
  serialized). See [Exchange & sharing](../exchange/spec-exchange.md).
- **Capability manifest (R4).** `CapabilityManifest` declares all gated `method+pattern → capability`
  registrations; `CapabilityManifestTest` source-scans the route files and fails the build on drift, asserts
  every route-demanded capability is granted by ≥1 seed role, and is the single source of truth for
  `Roles.KNOWN_CAPABILITIES`.
- **Gateway trust mode (R0 remainder).** `OidcAuthenticator` gains an optional second JWT processor validating
  a gateway-signed `X-JWT-Assertion` header (WSO2 APIM), consulted only when no valid `Bearer` resolves;
  unsigned header identity is never trusted. Opt-in via `-Dauth.oidc.gateway.issuer/.jwksUri`.

## Parts

This page is split by topic into 3 parts; each part lists the sections it holds.

1. **[Advanced ABAC and the 2026-07-25 decisions](admin-guide-auth-security-abac-decisions.md)** — Advanced ABAC — the Access Policy engine (`inspecto-policy`, workstream A) · Decisions of record — 2026-07-25 product session (BACKLOG D4 / D14 / D15)
2. **[Fail-closed boot, Space and config guards](admin-guide-auth-security-boot-guards.md)** — An undeclared mutating route does not boot either (`ROUTE-UNGATED-DEFAULT-1`, 2026-09-16) · A Space a signed-in person creates is closed (`LA-FULL-CYCLE-TEST-1` finding 32, decision D5, 2026-11-15) · A Standard/Advanced bundle does not boot without OIDC configuration · A Space's config cannot name another Space's directory (`CROSS-SPACE-JAIL-1`, 2026-09-24) · The `sql.template` job runs behind SqlGuard and a sealed connection (`SQL-TEMPLATE-SANDBOX-1`, 2026-09-24) · SqlGuard refuses every table function except path-free generators (`SQLGUARD-PARQUET-METADATA-1`, 2026-09-24)
3. **[Imports, identity files and maker-checker](admin-guide-auth-security-imports-maker-checker.md)** — No import may write an identity, access or settings file (`SEC-IMPORT-ROLES-ESCALATION-1`, 2026-09-26) · Maker-checker: a held config change needs a different person (`ASSURE-MAKER-CHECKER-1`, 2026-09-26) — board rows: `IMPORT-WHOLE-SPACE-SPLIT-1`
