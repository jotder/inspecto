---
type: Capability
title: Security (SEC) — Specification
description: Part 2 of 4 of Security (SEC): Specification
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
---

# Security (SEC) — Specification

Part 2 of 4 of [Security (SEC)](plan-security.md) · previous: [Purpose, scope and requirements of record](plan-security-scope.md) · next: [Decisions, not built, refused and as-built pointers](plan-security-decisions.md).

## 3. Specification

### 3.1 The posture: an auth-free core, and modules that add enforcement

All authentication was **removed from the common core on 2026-06-16**. The removed hand-rolled bearer plane
(per-route `Scope`, `-Dcontrol.token`, the Angular token screen) is gone, not gated. In a Personal build every
`ControlApi` route is open, the SPA boots straight to `/dashboard`, and there is no guard or interceptor.

Enforcement returns through five SPIs the core *defines* and never *implements*:

| SPI | Package | Contract | `@PublicApi` | Implemented by |
|---|---|---|---|---|
| `Authenticator` | `com.gamma.spi.auth` | `Optional<Subject> authenticate(HttpExchange)` | `since = "4.0.0"` | `OidcAuthenticator` (`inspecto-oidc`) |
| `Subject` | `com.gamma.spi.auth` | record `(id, capabilities, dataScopes, attributes, email, displayName, machineSub)`; `dataScopes == null` = unscoped | — | built by the authenticator |
| `TokenRelay` | `com.gamma.spi.auth` | `exchangeCode`, `refresh`, default no-op `revoke`; absent ⇒ `/auth/*` answers `503 CAPABILITY_UNAVAILABLE` | `since = "4.0.0"` | `OidcTokenRelay` |
| `AccessDecider` | `com.gamma.spi.auth` | `decide(...)` → `ALLOW | DENY | ABSTAIN`; `explain(...)`; `seededPolicies()` default-empty | `since = "4.0.0"` | `PolicyEngine` (`inspecto-policy`) |
| `SecretsProvider` | `com.gamma.acquire` | `supports(scope)`, `resolve(scope, key)` | — | `FileKeystoreSecretsProvider` (`inspecto-secrets`) |

Discovery is `ServiceLoader` through `META-INF/services`; the core's `Authenticators` / `SpiSlot` wrapper
exposes `active()` (empty on Personal) and a `forTest(...)` seam. ⚠ `Subject` and `SecretsProvider` carry no
`@PublicApi` although both are implemented outside the core — the marker records *intent* (see
`api-stability.md`), and its absence here is an inconsistency, not a policy.

**Editions** (`GLOSSARY.md` §14 *Edition*): Personal bundles neither module; Standard adds
`inspecto-oidc` (Maven profile `edition-standard` in `pom.xml`); Enterprise adds `inspecto-policy` on
top (`edition-advanced`). `package.ps1 -Edition Standard|Advanced` bundles the
**shaded** `inspecto-oidc-*-sidecar.jar` (Nimbus classes verified present at package time — the
`SEC-SIDECAR-BOOT-1` lesson). No runtime flag selects a module: the classpath entry is the switch, and the
default `mvn -o clean test` reactor **never compiles either module** — see §8.

### 3.2 The request-gating order in `ControlApi`

Every request passes, in order (`ControlApi.routeDispatch`, `inspecto/src/main/java/com/gamma/control/ControlApi.java`;
since 2026-11-15 `SpaceAdmission` runs between stages 1 and 2, then the rate limit — see [spec](spec-access.md)):

1. **`authenticate(ex, path)`**. First, on every edition and every matched route (static and SPA assets never reach `authenticate`): a non-blank `X-Actor`
   header is **rejected outright with `403 PERMISSION_DENIED`** (the SEC-7a spoof guard, extended from
   Standard to every edition when the header was removed in 4.0.0 — 2026-11-17, MoSCoW S4: identity comes
   from the `Subject`, or on Basic is `appUser`, never from a header). Then, skipped for `PUBLIC_PATHS` and
   self-verifying public routes: if an `Authenticator` is active, a request that yields no `Subject` gets
   **`401 UNAUTHENTICATED`**. With no `Authenticator` (Personal) the rest of the stage is a no-op.
2. **`authorize(ex, method, path)`** — the ABAC route-level policy enforcement point. No-op when
   no `AccessDecider` is registered, for public paths, or for subject-less exchanges. `DENY` → `403
   PERMISSION_DENIED`; `ABSTAIN` falls through. The action verb is derived by `actionFor`:
   `GET`/`HEAD` → `read`; a route manifested under `canOperateRuns` → `operate`; everything else → `write`.
3. **The route's capability gate** — `ApiContext.withCapability(capability, handler)` (`spi/inspecto-http-spi`)
   wraps every gated registration; a missing capability is `403 PERMISSION_DENIED`. The set of gated
   registrations is the **capability manifest** (§3.8).
4. **`WriteGates`** on mutation routes — independent of everything above (§3.12).

**`PUBLIC_PATHS`** (`ControlApi.PUBLIC_PATHS`, exact-match): `/health`, `/ready`, `/metrics`, `/bootstrap`,
`/auth/exchange`, `/auth/refresh`, `/auth/logout`. ⚠ `/metrics` in this list is what made a Personal bundle
serve full telemetry unauthenticated on every interface until EDG-01 cell 5 moved the exposition into
`inspecto-observability` (2026-09-07) — Personal now answers it `503` naming the module. **Self-verifying public**
routes (`isSelfVerifyingPublic`) carry their own credential in the URL and are deliberately not in
the list: `/public/dashboards/*`, `/public/delivery-status/*` and (a registered prefix, `PublicPaths`, 2026-10-12) `/public/investigations/*`. `GET /api/v1/openapi.json` is **auth-gated
on purpose** (2026-08-25, `38c7a32d`).

Error codes are `ErrorCodes.java` members carried in the v1 envelope: `UNAUTHENTICATED` (401),
`PERMISSION_DENIED` (403), `CAPABILITY_UNAVAILABLE` (503, a module is absent), `CONTROL_PLANE_READ_ONLY`
(503, no write root).

### 3.3 The `Subject` and its data scopes

`Subject(id, capabilities, dataScopes, attributes, email, displayName, machineSub)` (`Subject.java`; the last three
added for verified-email notification, the display name and machine Subjects). `capabilities` is the
**effective** set — role grants minus Access-Profile denies (§3.7) — so role names never leave the
authenticator and every `withCapability` gate enforces profile denies with zero route changes. `dataScopes`
(SEC-7d, 2026-07-08) is `null` for an unscoped subject, otherwise the union of a `data_scopes` claim, any
`case:<scope>` role names, and authored `dataScopes` in `roles.toon`; `ObjectRoutes` applies it as filtered
lists, `404` on out-of-scope access (read *and* mutate) and pruned correlation graphs — event and audit
streams stay capability-gated by design. `attributes` (ABAC A1) holds only the claims allowlisted in
`roles.toon` `identity.attributeClaims`, never the raw token (§3.10).

### 3.4 The OIDC authenticator

`OidcAuthenticator` (`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcAuthenticator.java`) is a
**vendor-agnostic resource server**: Nimbus JOSE+JWT `RemoteJWKSet` + `DefaultJWTProcessor`, RS256, no vendor
SDK. Configuration is system properties only:

| Property | Role | Required |
|---|---|---|
| `-Dauth.oidc.issuer` | expected `iss` | **yes — the process exits without it** (or from discovery) |
| `-Dauth.oidc.jwksUri` | JWKS endpoint (explicit, or from discovery / the `keycloak` preset since 2026-10-13) | **yes** |
| `-Dauth.oidc.audience` | expected `aud` | warn-only by decision (`docs/okf/product/module/oidc/architecture-oidc.md`) |
| `-Dauth.oidc.rolesClaim` | which claim carries role names | default per module |
| `-Dauth.oidc.clientId` | the public client id the BFF exchanges on behalf of | for the relay |
| `-Dauth.oidc.tokenEndpoint` | the provider's `token_endpoint` | **mandatory, no default** (D15) unless discovery or the `keycloak` preset supplies it |
| `-Dauth.oidc.provider` / `.discoveryUrl` | `generic` (default) / `wso2` / `keycloak` preset; an optional discovery document, fetched once (`OidcConfig`, 2026-10-13). Explicit `-D` beats discovery, which beats the preset | opt-in |
| `-Dauth.oidc.gateway.issuer` / `.jwksUri` / `.header` | the optional **gateway trust mode** | opt-in |

**Gateway trust mode** (R0, 2026-07-23): a second JWT processor validates a *gateway-signed* assertion header
(default name `X-JWT-Assertion` — *a* convention, WSO2 APIM's, not *the* gateway), consulted **only when no
valid `Bearer` resolves**. Unsigned header identity is never trusted; `alg:none` and bare strings fail
verification, test-pinned. Clock skew is Nimbus's bounded 60 s default; no knob was added.

`RoleMapper` (`providers/inspecto-oidc`) resolves the roles claim → `Roles.Def` → capabilities through the
**data-driven role table** (§3.6), fail-closed on an unknown role name; `dataScopesFor` builds the scope set
of §3.3. Access-Profile denies are stripped here, at authentication time (§3.7).

### 3.5 The BFF session

The browser never holds a refresh token. `AuthRoutes` (core — `inspecto/src/main/java/com/gamma/control/AuthRoutes.java`)
serves `POST /auth/exchange | /auth/refresh | /auth/logout`, delegating to the `TokenRelay`:

- the refresh token lives in the **httpOnly `inspecto_rt` cookie**, `SameSite=Strict` (`setCookie`), and since
  2026-10-13 the ID token in a second httpOnly cookie, `inspecto_idt`, read only to build the logout hint;
- every `/auth/*` call passes `requireSameOrigin(ex)` — the `Origin` check
  is the CSRF defence, not a token;
- the SPA runs **PKCE (RFC 7636, S256)** as a **public client** — no client secret anywhere in the UI
  (`4x-public-pkce`, 2026-07-25: dead confidential-client code deleted, `appClientSecret` removed from source
  and all four environment files), the callback is parsed with `URLSearchParams` and **validates `state`**
  (`ce49a681` — the CSRF defence P1 only pretended to have);
- `SessionService` (`inspecto-ui/src/app/inspecto/auth/session.service.ts`) is an `APP_INITIALIZER` gate:
  `init()` reads `/bootstrap`, and on `authMode === 'oidc'` tries a silent `refresh()` from the cookie, then
  re-pulls `/bootstrap` with the bearer for effective capabilities; `auth.interceptor.ts` attaches
  `Authorization: Bearer` and does 401 → silent refresh → retry.
- **Sign-out is three layers and all three must fire** (2026-07-26): `POST /auth/logout` revokes and clears
  the cookie; the in-memory access token is dropped; the browser is sent to the provider's
  `end_session_endpoint` (RP-Initiated Logout 1.0) with `client_id` + `post_logout_redirect_uri`. Since
  2026-10-13 `POST /auth/logout` returns that URL with `id_token_hint` too (`OidcTokenRelay.endSessionUrl`, from
  the `inspecto_idt` cookie), so the provider skips its confirmation page; the SPA builds the hint-less URL itself
  only when the server returns none (it omitted the hint until then). `SessionService.redirect()`
  is the single seam that leaves the SPA (it exists because jsdom makes `window.location.assign`
  non-configurable). See §2 (`SEC-12`) on where `endSessionUrl` comes from.
- **Personal renders no user menu at all** — no principal to name, no session to end.

### 3.6 Roles: a seeded, authorable, fail-closed table

`com.gamma.access.Roles` (`platform/inspecto-access`) holds the **seed table** and the doc grammar. Role *assignment* is the IdP's
(claims); role *definitions* are data. `Roles.effective(ex)` overlays the bound Space's `roles.toon`
(`Roles.FILE`) **per role name** — an authored `[]` revokes a seed role, unnamed seed roles
keep their defaults — cached by the file's text (not mtime + size: a same-size rewrite inside one mtime tick read stale and fail-open) so edits apply on the next request with no restart. **Fail-closed:** an
existing-but-unreadable `roles.toon` suspends *all* role grants. `GET/PUT /access/roles` author it
(`AccessRoutes`; PUT gated `canConfigureAccess`); Settings ▸ Access ▸ **Roles** edits it with
`source: authored|seed` badges, a strike-through overlay for capabilities an Access Profile denies, and a
Revert that removes the override.

**The capability vocabulary is exactly twenty names** — `Roles.KNOWN_CAPABILITIES`, derived from the
capabilities `CapabilityManifest` gates on (it said "eleven" until 2026-09-26, four additions after it was
written; "fifteen" went stale too - 2026-10-01 it is eighteen with `canRunLinkGraphAnalysis`, 2026-10-02 nineteen with `canBuildLinkIndex`, 2026-10-12 twenty with `canShareInvestigation`; **23 on 2026-11-17**, re-counted from `CapabilityManifest.ENTRIES`: the last five rows of the table below) — static across editions (a Standard-authored role file
must validate on Personal — a per-edition validator was refused, `EDITIONS.md` SEC-10 note):

| Capability | Gates |
|---|---|
| `canAuthorWorkbench` | Pipeline / Collector authoring writes |
| `canOnboardConnections` | `POST/PUT/DELETE /connections` — its own grant, **not** `canAuthorWorkbench`, because Connections are the credential + network-egress surface (2026-07-22) |
| `canOperateRuns` | run/operate routes (and the `operate` ABAC verb) |
| `canTriageRequirements` | `RequirementRoutes` `/decision` + `/deliver`; submission stays open — ⛔ SEC-7(c), and DELIBERATE: anyone may raise a requirement, only a triager decides. A 2026-09-15 audit read the asymmetry as an oversight and gating it turned the build red against the test that pins it |
| `canAuthorAlertRules` | Alert Rule authoring |
| `canCurateMenus` | `/nav/menus` — split out of `canAuthorWorkbench` 2026-07-25 (D4) |
| `canConfigureAccess` | `PUT /access/roles|policies|catalog|profiles` |
| `canOfferDatasets` | offering a Dataset cross-space — admin/super since D14 (a data-*exposure* decision with no second gate) |
| `canApproveShares` | approve / deny / revoke an Exchange grant |
| `canRequestShares` | request access / pin a snapshot version |
| `canAdminister` | installation administration: `PUT /spaces/{id}`, `DELETE /spaces/{id}` (⛔ NOT `POST /spaces`, which is the recovery route when a server hosts no Space); agent governance (`PUT /agent/policy`, the kill switch, approvals, Case feedback); and since 2026-09-15 step 2b **Incident/Case disposition** — `merge` / `split`, `PATCH /objects/{id}`, `POST /cases/rules/{id}/evaluate` (`ack` / `resolve` / `transition` / `assign` moved to `canWorkIncidents` on 2026-09-26) — while comment / attach / link / RCA-seed stay open as `collaboration` (recorded in `CapabilityManifest.EXEMPTIONS`, whose 73 rows (`PENDING_OPERATOR_CALLS` is empty since 2026-09-16) make every ungated mutating route a *recorded* state; `CapabilityManifestTest` fails the build on a mutating route in no state) — added 2026-09-15 (`ROUTE-UNGATED-DEFAULT-1`). ⛔ Deliberately ONE coarse capability rather than three per-family ones (operator call): the alternative left no catch-all for the next unlisted route. ⚠ Whoever can delete a Space can do everything else this guards |
| `canWorkIncidents` | working an Incident or Case through its lifecycle — `POST /objects/{id}/ack`, `/resolve`, `/transition`, `/assign` (operator, 2026-09-26: they left `canAdminister`, which only Admin/Super hold, so an analyst could not close their own Case). Seeded to `operations`, `support`, `power`, `admin`. Also (operator, 2026-09-26, `INCIDENT-FINISH-GATE-1`) the narrow `PUT /objects/{id}/postmortem` and `PUT /objects/{id}/category`, each writing only its one attribute and refusing any other key (422), so an analyst can FINISH an Incident. ⛔ Not merge / split / the PATCH / Case-Rule evaluate — those stay `canAdminister` |
| `canManageIncidents` | OPENING an Incident or Case — `POST /objects`, `POST /recon/promote`, `POST /cases/from-entities` — plus Link Analysis Investigation authoring (`/inv/investigations*`, `/inv/snapshots*`) and the `findings-spec` component kind |
| `canRevealLinkEntities` | `POST /inv/investigations/{id}/reveal` — unmasking an entity id, per entity, audited (LA-19 / D-U6). ⛔ Deliberately not `canManageIncidents`, which every Investigation owner holds |
| `canApproveLinkExpansions` | approving / denying a PENDING sensitive expand in someone else's Investigation — four-eyes; never makes self-approval legal (LA-19 / D-U7) |
| `canRunLinkGraphAnalysis` | `POST /inv/graph/runs` — starting a server-side graph run over an Investigation's Working Set (LA separation D-4): a run spends compute, a read does not. Seeded to `operations`/`support`/`power`/`admin` (everyone who holds `canManageIncidents`); reads and list inherit the Investigation's access; cancel is the run's starter or an administrator and has no capability (a manifest `self-service` exemption) |
| `canShareInvestigation` | `POST /inv/investigations/{id}/embeds` and `.../embeds/{grantId}/revoke` — minting and revoking a read-only EMBED link to one Investigation (LA-EMBED-VIEW-1, operator 2026-10-12 D6): a link hands a masked live view of the evidence to someone with no login. No four-eyes on mint. Seeded to `admin` and `power`; the handler additionally requires a LEAD of the Investigation, and an Access Policy can forbid it with the ABAC action `embed` |
| `canBuildLinkIndex` | `POST /inv/index/builds` — building a Link Analysis edge/node index over a Dataset (LA separation D-3 step 4): a build spends compute AND disk, a read does not. Seeded to the same roles as `canRunLinkGraphAnalysis`; listing indexes and reading a build inherit the Dataset's view gate; cancel is the build's starter or an administrator and has no capability (a manifest `self-service` exemption) |
| `canUploadData` | uploading and onboarding data: `/inv/uploads*`, `/onboard/files*`, `/datasets/{id}/files`, `/run`, `/feed`, `/filter` |
| `canApproveChanges` | deciding someone else's held change — `POST /pending-changes/{id}/approve|decline` (maker-checker, `ASSURE-MAKER-CHECKER-1`) and `/action-requests/{id}/approve|decline|retry|mark-failed` |
| `canManageExclusionCategories` | `PUT /entity-lists/{id}/attributes` |
| `canOpenInboundInvestigation` | `POST /inv/inbound/investigations` — the one capability a machine Subject keeps (`Roles.MACHINE_CAPABILITIES`; seed role `integration`) |
| `canOfferSignals` | `POST /exchange/signal-offers` |

**Seed roles** (`Roles.SEED`): `pipeline-developer`, `app-developer`, `developer` (the builder set),
`operations`, `power`, `admin` (`canOnboardConnections`, `canConfigureAccess`, `canApproveShares`,
`canOfferDatasets`, `canCurateMenus`, `canTriageRequirements`, `canAdminister`, and since 2026-11-15
`canAuthorWorkbench`: `LA-FULL-CYCLE-TEST-1` D1, reason in `admin-guide-auth-security.md`; the list here is not
exhaustive, `Roles.SEED` is), `super` (the whole vocabulary via
`KNOWN_CAPABILITIES`), `business` (**only** `canTriageRequirements`). ⚠ `Roles.SEED` is asserted by an
*equality* test in `OidcAuthenticatorTest` — every grant change must update it, and that test runs **only**
under `-Pedition-standard|preview|advanced` (§8).

### 3.7 Access Catalog, Access Profiles, and enforcement at authentication time

The **Access Catalog** (`access-catalog` component kind; `PUT /access/catalog`) is the canonical tree of
gateable surface — menu groups → panes → action nodes, each action node bound to exactly one capability. An
**Access Profile** (`access-profile` kind; `PUT /access/profiles/{subjectType-subjectId}`) is one subject's
sparse `nodeId → allow | deny` map; `subjectType` is `lens` in the auth-free core (UI copy **Shown / Hidden /
Inherit**, honor system) and `role` under RBAC — same document, same editor, only the subject and the
enforcement change. Resolution is nearest-explicit-ancestor, **root default allow**, **union across roles**.

**Enforcement happens when the subject is built, not per route** (R2, 2026-07-23 — a deliberate deviation
from the plan's separate authorize middleware): `AccessGrants` resolves the held roles against every saved
`subjectType: role` profile; a deny on an action node binds to that node's capability, and
`OidcAuthenticator` strips the denied capabilities before constructing the `Subject`. Consequences that are
the point: the `Subject` is capabilities-only; `/bootstrap permissions` reports *effective* grants; no route
had to change. Since 2026-07-26 **every route capability has both a `LensService` signal and a catalog action
node** — a capability the server enforces but the client never reads is a bug in both directions (§4).

### 3.8 The capability manifest — one table, asserted in both directions

`CapabilityManifest` (`platform/inspecto-access`, `ENTRIES`) declares every gated `method + pattern → capability`
registration (70 at R4; 293 on 2026-11-17). `CapabilityManifestTest` **source-scans the route files** and fails the build on
drift in either direction — an entry with no registration, a registration with no entry — matching on the
capability **string literal** at the registration site; it also asserts every route-demanded capability is
granted by at least one seed role, and derives `Roles.KNOWN_CAPABILITIES`. ⚠ A manifest entry and its route
gate must land in the same commit.

### 3.9 Component sharing — the intra-Space grant envelope

Every `ComponentStore` kind accepts an optional `owner` + `shares: [{subjectType: role|user, subjectId,
access: view|edit}]` envelope (R3). **No `shares` key ⇒ byte-identical behaviour** (every pre-R3 document);
once present the component is restricted: owner and `canConfigureAccess` holders have full access, shares
grant `view`/`edit`, everyone else gets the SEC-7d contract (filtered lists, `404`) on read *and* mutate.
**The owner is the creator** (operator, 2026-09-28, `OWNER-IS-CREATOR`): a create body naming another
`owner`, or a first claim of an owner-less component for someone else, is `403 PERMISSION_DENIED` unless the
caller holds `canConfigureAccess` or `canAdminister`; with no Subject (Personal) nothing changes.
Role matching rides `ComponentAccess.ATTR_HELD_ROLES`, stamped by the authenticator and never serialized.
The `user` subject is the opaque IdP `sub`. Cross-Space sharing is the **Exchange** family
(`inspecto-exchange`, `ShareGrant` — fail-closed `requested → active | denied → revoked | expired`, every
transition audited), gated by `canOfferDatasets` / `canApproveShares` / `canRequestShares` and absent from
Personal since EDG-01 cell 4; its mechanism is `exchange-sharing.md`.

### 3.10 Enterprise ABAC — the Access Policy engine

`inspecto-policy` registers `PolicyEngine` (`providers/inspecto-policy/src/main/java/com/gamma/policy/PolicyEngine.java`)
on the `AccessDecider` SPI. Personal and Standard never bundle it and behave byte-identically.

- **Attributes (A1).** `roles.toon` `identity: {attributeClaims: [...]}` is an **allowlist**; the
  authenticator copies exactly the allowlisted-and-present verified claims onto `Subject.attributes` — never
  the raw token, and nothing when the doc is unreadable (attributes fail closed alongside role grants). Both
  the Bearer and the gateway path.
- **Condition grammar (A2).** `com.gamma.util.Conditions` (`inspecto-util` — domain-agnostic on purpose:
  "one policy engine, many policy kinds"): recursive-descent, parse-once, `== != in contains and or not ( )`,
  literals and dotted refs over a nested `Map` context, strict-Boolean truthiness, type-mismatch-is-false,
  offset-bearing parse errors. Pinned by `ConditionsTest`.
- **The document.** Per-Space `access-policies.toon` (`AccessPolicyStore.FILE`, `platform/inspecto-access`; the
  `AccessPolicies` record types stay in `spi/inspecto-auth-spi`): rows
  `{name, effect: allow|deny, target: {actions?, resourceKinds?}, when?}`; cached by the file's text; one validate
  grammar shared by the file parser and `GET/PUT /access/policies` (`AccessRoutes`; a `when` that
  does not parse is `422`). **Unreadable doc ⇒ the engine DENIES loudly**, never "no policies". Authoring
  is core (the routes exist in every edition); *evaluation* is Enterprise.
- **Decision (A3).** `decide()` is **deny-overrides → allow → ABSTAIN** over
  `AccessPolicyStore.effective`; context is `subject.{id, capabilities, dataScopes, roles}` + A1 attributes,
  `env.{action, route, space}`, and at row level `resource.*` with `resource.space` defaulting to the bound
  Space. Two enforcement points: the route-level `authorize` stage (§3.2) and the row-level **`RowScope`**
  filter (`RowScope.visible` / `allowed`; used by `inspecto-ops` `ObjectRoutes` and `AnnotationTargets`, and since by the
  Link Analysis Investigation and embed routes, `StandingDetection` and the risk, anomaly and prediction score routes) — a `DENY` hides
  the row (`404` / filtered). 🔴 **A policy `ALLOW` never bypasses a capability gate** — the plan's combining
  order was tightened for defence in depth; `ABSTAIN` falls through to the Standard capability / profile /
  sharing gates.
- **Space isolation (A4 = SPC-5).** Two **engine-resident seed policies** (`PolicyEngine.SEED`:
  `space-isolation`, `space-isolation-rows`), overlaid per policy name by the authored doc: deny
  when the subject's mapped `space` home claim ≠ the bound Space, at route and row level. They **engage only
  when a `space` claim is mapped** (unmapped ⇒ no isolation, never a bricked API) and exempt
  `canConfigureAccess` holders. `env.space` is always bound because `EventLog.currentSpaceId()` never returns
  null — un-prefixed server-global routes bind the default Space.
- **Decision audit (A5).** Every policy `DENY` and every route-level policy-matched `ALLOW` goes through
  `AuditTrail.policyDecision(...)` (`platform/inspecto-access`) as `access.denied` / `access.granted`, category
  `authorization`, with actor, verb, route, row kind/id and the matched policy name
  (`AccessDecider.ATTR_MATCHED_POLICY`; `<policies-unreadable>` on a fail-closed deny). Read back via
  `GET /events?type=ACCESS_DENIED|AUDIT`. A row-level *allow* is deliberately **not** audited (it fires per
  surviving row); `ABSTAIN` is not a decision and is never audited.
- **Operability.** `GET /access/policies` surfaces the seed denies too, each row `source: authored|seed`
  (via `AccessDecider.seededPolicies()`, default-empty). `GET /access/explain?route=&method=&resourceKind=`
  (`AccessRoutes`; `PolicyEngine.explain`) is a side-effect-free **"why denied?"**
  dry-run for the caller's own session — decision, matched policy, per-policy `{targeted, conditionHeld,
  source}` trace, enforcing and auditing nothing. **It is a GET on purpose**: a POST would be a `write` the
  policy under test could `403`, locking the denied subject out of their own explanation. Both are
  Enterprise-only in effect (Personal/Standard answer `{enabled: false}`); UI: Settings ▸ Access ▸
  **Policies** (`access-policies.component.ts`) — read-only until 2026-09-25, an editor since (next bullet).
- **Authoring guards (SHIPPED 2026-09-25, SEC-05).** Every way the code lets a hand edit or a PUT go wrong
  is refused or warned **before it takes effect**, in the one `AccessPolicies.validate` grammar the file
  parser and `PUT /access/policies` share: 422s for an unknown key (`unknown-key` / `ambiguous-key`), a
  `when` ref the engine never binds (`unknown-ref`), an untargeted blank-`when` deny (`deny-everything`) and
  a save that would deny the saver's own next `PUT /access/policies` (`would-lock-out`, via the
  default-ABSTAIN `AccessDecider.simulate`); warnings (`unknown-capability`, `unknown-role`,
  `unknown-resource-kind`, `resource-ref-at-route-level`, `seed-override`) on GET and PUT alike, so a
  hand-edited doc shows them too. An unreadable doc's `error` names the policy and check, to
  `canConfigureAccess` holders only. `POST /access/policies/preview` (gated `canConfigureAccess`) returns the
  draft's role × action (× targeted kind) impact, before → after. The Policies tab authors (new / edit /
  delete, seed "Override…" behind a confirm, `If-Match` full replace, *Preview impact*). ⚠ **Upgrade bites
  at runtime:** an on-disk doc with a stray key or an un-allowlisted `subject.<claim>` becomes unreadable ⇒
  deny-all on Enterprise. Mechanism, test pins and stated limits:
  [auth-security.md](admin-guide-auth-security.md) §"Policy authoring guards". The browser pass over the editor
  ran on 2026-09-27 on the `fff119d6b` demo build, which closed the board row.

### 3.11 Transport and bind address

`-Dhttps.keystore=<PKCS12 path>` + `-Dhttps.keystore.password=<pw>` switch `ControlApi` from `HttpServer` to
the pure-JDK `HttpsServer` with `SSLContext.getInstance("TLSv1.3")` (`ControlApi`); unset ⇒
plain HTTP (the Personal default). `-Dcontrol.bind=<host>` restricts the listen address in every edition
(`ControlApi.bindAddress`) — ⚠ the default binds **every interface**, in Personal too; "localhost-bound" in
`STAKEHOLDER_OVERVIEW.md` is wrong (plan §8 cluster). HTTPS lives in **core**, not in `inspecto-oidc`, and
is tested since 2026-09-09 by `ControlApiHttpsTest` (5 tests, the fail-closed keystore included; `SEC-4`).

### 3.12 Secrets

`SecretResolver` (`spi/inspecto-auth-spi`) resolves `${ENV:NAME}`, `${SYS:NAME}` and bare `${NAME}` in every
edition; `${FILE:path}` and `${KEYSTORE:alias}` (JCEKS) route to a `SecretsProvider` and arrive by
`ServiceLoader` from `inspecto-secrets` (`FileKeystoreSecretsProvider`), which only the Standard and
Enterprise bundles carry. **A Personal bundle refuses those two schemes with an edition-naming message, never
a silent null** (2026-09-06). `SecretScrubber` keeps resolved values out of logs; `tools/check-secrets.mjs`
keeps secret-shaped literals out of the tree (§8.6). Vault / cloud KMS: §5.

### 3.13 The write-root gate — separate from auth, in every edition

`WriteGates.requireWriteRoot(api, what)` (`platform/inspecto-access`, `@PublicApi(since = "4.0.0")`): with
`-Dassist.write.root` absent, every mutation route (config writes, connection writes, authored-Pipeline CRUD)
answers **`503 CONTROL_PLANE_READ_ONLY`** naming the flag; present, writes are jailed to that root
(`PathJail.contains`, `403`), names validated (`422`), conflicts `409`, and the content validated by
`ConfigSafetyValidator`. The file references no `Subject` or `Authenticator`: it is an **ops decision about
whether this instance may write**, and the security module *prepends* AuthN/AuthZ to it rather than
replacing it.

### 3.14 The UI seam

`LensService` (`inspecto-ui/src/app/inspecto/api/lens.service.ts`) exposes the ten capabilities as computed
signals. In auth-free mode `granted()` is true for everyone and the **Lens alone decides** (honor system,
"View as" preview); under OIDC a capability must be in `session.capabilities()` from `/bootstrap`. The
predicate is split (`159b7f0d`, 2026-07-25) and the split is the durable fact:

| | Predicate | Members |
|---|---|---|
| **Identity** — who the subject *is* | `granted && allows` | `canConfigureAccess`, `canCurateMenus`, `canOnboardConnections`, `canTriageRequirements`, `canOfferDatasets`, `canApproveShares` |
| **Lens-scoped** — the activity a lens *represents* | `granted && !readOnly && allows` | `canAuthorWorkbench`, `canOperateRuns`, `canAuthorAlertRules`, `canRequestShares` |

Which side a capability falls on is decided by **whether lens-scoping can strand a role**, not by feature:
every seed role holding `canRequestShares` also qualifies for a non-Business lens, so it is lens-scoped; the
`business` seed's *only* capability is `canTriageRequirements`, so it is identity — with the accepted
consequence that **"Business lens ⇒ read-only" is no longer true of the product**. `readOnly` is
presentation, never a boundary (`LensService.readOnly`); the server is the enforcement point. Per-lens
Access Profiles reach the UI through `AccessStateService.setActionGrants()`; feature flags from `/bootstrap`
(`features.exchange|geoLink|events|ops`) **hide** navigation for an absent module — never a toast.

⚠ **`permissions[]` is emitted and never read.** `Envelope.success` emits `subject.capabilities() ∩
applicable` when a route declares `ApiContext.resourcePermissions(...)` (SEC-7b; no `permissions` key on
Personal). The SPA declares it in `V1Envelope.permissions?: string[]` (`inspecto/api/v1.ts`) and **no code
reads it** — every affordance is gated on `LensService` instead. The contract is correct and the consumer is
absent; recorded in §5 as an untracked gap, not a contradiction: the envelope field was always documented as
"a fail-closed affordance signal, never the security boundary".
