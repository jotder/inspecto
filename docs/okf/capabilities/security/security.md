---
type: Capability
title: Security (SEC)
description: Who may call what — the auth-free core, the Authenticator/Subject/TokenRelay/AccessDecider/SecretsProvider SPIs, the Standard OIDC + RBAC module, the Enterprise ABAC policy engine, secrets, HTTPS and the write-root gate. The requirement of record for the SEC area, its specification, its decisions, and what was refused.
resource: inspecto-security/, inspecto-policy/
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
---

# Security — capability spec (`SEC`)

> **What this page is.** The single entry point for the SEC capability: what was *required*, what is
> *built*, what is *left*, and what was *refused*. It is the front door to the mechanism, not a copy of it —
> §7 points at the `okf/` concepts that own the detail, and §5 points at `BACKLOG.md` rows rather than
> restating them. Second of the capability specs; the template is
> [`docs-consolidation-plan.md` §5.2](../../../superpower/docs-consolidation-plan.md); the area name and
> directory are fixed by [`GLOSSARY.md` §14](../../../GLOSSARY.md#14-capability-areas-the-functional-spine).
>
> **Canonical vocabulary** (`GLOSSARY.md` §1-A, binding). A **Lens** is a self-selected *view* and is never a
> permission. A **Role** is an *assigned* authorization enforced server-side; role *assignment* lives in the
> IdP, only role *definitions* are authorable. A **Capability** is one named authorization question
> (`canAuthorWorkbench`, …) — the seam between Lens and Role; panes and routes gate on a Capability, never on
> Lens identity. **Access Catalog** / **Access Profile** / **Grant** are the gateable surface, one subject's
> sparse grant map over it, and one entry of that map. A **Share** is an intra-Space grant on one component;
> the **Exchange** family shares *across* Spaces. An **Access Policy** is an allow/deny statement over
> **Attributes**; ⛔ never bare *Rule* or *Policy Rule*. An **Edition** is a build flavour, never a branch and
> never a runtime flag (§14).

## 1. Purpose & scope

SEC is everything that decides **whether a request is allowed to happen**: who the caller is, which named
capabilities they hold, whether a policy denies the action or hides the row, whether the secret a config
references may be resolved, whether the transport is encrypted, and — separately from all of those — whether
this instance is permitted to write at all. Its defining property is that **the core carries no
authentication code**: Personal is auth-free by construction, and everything above is added by modules the
build either bundles or does not.

**In scope:** the `Authenticator` / `Subject` / `TokenRelay` / `AccessDecider` / `SecretsProvider` SPIs and
the request-gating order in `ControlApi`; the `inspecto-security` module (OIDC resource server, role
mapping, token relay, the BFF session, file/keystore secrets); the capability vocabulary, seed roles and the
authorable `roles.toon`; Access Catalog / Profile enforcement and the component sharing envelope; the
`inspecto-policy` ABAC engine (attributes, condition grammar, `access-policies.toon`, the two enforcement
points, space isolation, decision audit, explain); HTTPS and the bind address; the write-root gate; the
UI's capability seam (`LensService`) and sign-in / sign-out flow.

**Not in scope, and deliberately so:**

| Adjacent concern | Whose it is |
|---|---|
| The signal ledger, the Events screen, the audit **log** as a product surface | `OPS` — Observability & maintenance. SEC owns only the *actor attribution* and the *authorization* decision events that feed it |
| The compliance program — controls matrix, SBOM, signed releases, SOC 2 window | `CMP` — Compliance. It *cites* SEC mechanisms as evidence (`compliance/controls-matrix.md` CC6, ISO 8.2–8.5, IA) |
| How a module is bundled, which profile compiles it, `package.ps1` flavours | `PKG` — Editions & packaging. SEC states *which* edition carries each mechanism; the mechanism of gating is `editions-model.md` |
| Cross-Space exchange of Datasets (Offer / Share Grant lifecycle) | `DAT` / `MET` — the exchange domain. SEC owns the two governance capabilities and the fail-closed grant contract only |
| Path containment for *config-declared* paths, `ConfigSafetyValidator` | `TOOL`/config safety — `config-safety.md`. SEC owns the write-root 503 decision; what happens *inside* the root is config safety |
| Per-tenant Space isolation as a tenancy feature | `SPC` — SEC delivers it as two seed policies (§3.10); the tenancy contract is SPC-5 |

## 2. Requirements of record

Nine requirements from `REQUIREMENTS.md` §3.12 (that section was stripped to an index on 2026-09-09 — this file is their only home now), all recorded shipped; **two of them are not flat green when
read against the build**, and one is spelled with a vendor name the product retired. ⚠ **`EDITIONS.md`'s
feature × edition matrix is authoritative for the Edition column**; this table mirrors it.

| ID | Requirement | MoSCoW | Status | Edition |
|---|---|---|---|---|
| `SEC-1` | **Auth-free common core** — no auth/RBAC/user-management code in core; Personal boots login-free | Must | ✅ SHIPPED (2026-06-16) | P |
| `SEC-2` | `Authenticator` / `Subject` / `TokenRelay` SPIs; the AuthN gate; per-route capability checks; `UNAUTHENTICATED` / `PERMISSION_DENIED` | Must | ✅ SHIPPED (W6) | All (no-op on P) |
| `SEC-3` | `inspecto-security`: OIDC resource server (Nimbus JWKS), `RoleMapper`, **`OidcTokenRelay`** — reactor-gated behind `edition-standard` | Must (S) | ✅ SHIPPED | S/E |
| `SEC-4` | HTTPS via pure-JDK `HttpsServer` + keystore | Must (S) | ✅ SHIPPED **and TESTED 2026-09-09** — `ControlApiHttpsTest` proves TLS 1.3 is served against a keytool-generated PKCS12 (verified against that same store as its trust store, ⛔ not a trust-all manager), that cleartext no longer works on the port, and that a bad keystore **fails closed**. 🔴 Writing it found and fixed a real defect: a wrong password lost the diagnostic naming `-Dhttps.keystore`, because `KeyStore.load` signals it as a plain `IOException` and the catch covered only `GeneralSecurityException` | S/E |
| `SEC-5` | BFF session: the refresh token never reaches the browser (httpOnly cookie, `SameSite=Strict`, `Origin` CSRF) | Must (S) | ✅ SHIPPED (W6d) | S/E |
| `SEC-6` | UI OIDC login driven by `bootstrap.features.authMode`; Personal = no-op | Must (S) | ✅ SHIPPED (W6d/W7) — ⚠ the coupling is a launcher convention (§2 note 2) | S/E |
| `SEC-7` | RBAC/ABAC hardening: reject `X-Actor` on Standard; per-resource `permissions[]`; `canTriageRequirements` route; data-scoped grants | Must (S) | ✅ SHIPPED (2026-07-07/08) — ⚠ `permissions[]` is **emitted and never read** (§3.13) Designs of record: `docs/archived-documents/plans-archive/resource-permissions-design.md` and `docs/archived-documents/plans-archive/rbac-groundwork.md`. | S/E |
| `SEC-8` | Secrets: env / file / keystore; Vault option future | Should | 🟡 **PARTIAL by design** — `${ENV}`/`${SYS}` everywhere, `${FILE}`/`${KEYSTORE}` S/E (2026-09-06); Vault / KMS **unbuilt, Enterprise-only, gated on a client policy** (§5) | All · S/E · E |
| `SEC-9` | Write-root gate (`-Dassist.write.root` → 503 fail-closed), separate from auth, always on | Must | ✅ SHIPPED | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`SEC-3` named "Keycloak token relay".** The class is `OidcTokenRelay`
  (`inspecto-security/src/main/java/com/gamma/security/OidcTokenRelay.java`) — D15 renamed it on 2026-07-25
  because no IdP vendor is of record (§4). `PROJECT_NOTES.md` §3 and `ROADMAP.md` §3.1 carried the old name
  too; all three are fixed in the same change as this spec.
- **`SEC-8` read `SHIPPED`.** The requirement names a Vault option and the Vault/KMS half is unbuilt by an
  explicit decision (Enterprise-only, on demand — `BACKLOG.md` §6). The built half is exactly what
  `EDITIONS.md` SEC-07 states. A Should recorded green over an unbuilt half is the shape that makes a
  register untrustworthy; it is 🟡 with the unbuilt half owned by §5.
- **`REQUIREMENTS.md` §7 risk R4** said "per-resource permissions & X-Actor rejection *incomplete on
  Standard*" while §3.12 marked `SEC-7` shipped in the same file. Both halves shipped 2026-07-07/08
  (`ControlApi.authenticate` rejects `X-Actor` with `403 PERMISSION_DENIED` whenever an `Authenticator` is
  active; `Envelope.success` emits `permissions`). R4 is retired in the same change.

**Two numbering schemes coexist, and they are not the same rows.** `REQUIREMENTS.md` uses `SEC-1…9`
(requirements); `EDITIONS.md` uses `SEC-01…12` (features in the edition matrix). They overlap but do not
map one-to-one — `SEC-7` (REQ) spans `SEC-03` + `SEC-09` + part of `SEC-10` (EDITIONS); `SEC-05`, `SEC-08`,
`SEC-11`, `SEC-12` (EDITIONS) have no requirement row at all. Read the prefix *and* the zero-padding before
citing either. The EDITIONS rows are the edition truth and are corrected here in three cells:

| EDITIONS row | What it said | What the build does |
|---|---|---|
| `SEC-09` note | Personal events carry `actor=anonymous` | The unauthenticated default actor is **`appUser`** (`ApiContext.actor`, `inspecto/src/main/java/com/gamma/control/ApiContext.java:285`): an authenticated `Subject.id()` first, else `agent:<session>`, else the `X-Actor` header, else `appUser`. No `anonymous` string exists in the tree |
| `SEC-11` note | "client-migration-gated" | The API-v1 sunset apparatus was deleted 2026-07-25; `BACKLOG.md` §2 restated the gate 2026-09-07 as **the next MAJOR tag** |
| `SEC-12` (end-session redirect) | `— / 🔲 / 🔲`, "nobody has asked" | **Half built.** The SPA implements RP-Initiated Logout (`SessionService.logout()` redirects to `endSessionUrl` with `client_id` + `post_logout_redirect_uri`, 2026-07-26) and reads `boot.auth?.endSessionUrl ?? environment.oidc.endSessionUrl`. **The server never publishes an `auth` block** — `BootstrapRoutes` emits only `features.authMode`; no Java file contains `authorizeUrl` or `endSessionUrl` — so the only working path is the build-time `environment.ts` value. 🟡 for S/E: works, but configured at UI build time, not at deployment |

**Two standing notes no status token can carry:**

1. 🔴 **A Standard or Enterprise bundle exits at startup without OIDC configuration.** `ControlApi` calls
   `Authenticators.active()` during construction and `SpiSlot` runs `ServiceLoader` regardless of
   `-Dauth.mode`; the mere presence of `inspecto-security.jar` makes `OidcAuthenticator`'s constructor
   mandatory, and it fails closed without `-Dauth.oidc.jwksUri` + `-Dauth.oidc.issuer`. The message names
   the property; the *timing* surprises. There is **no** "module present, auth off" mode
   (`SEC-SIDECAR-BOOT-1`, 2026-09-07). *A requirement is not delivered until it is reachable* — and this one
   is reachable only with its environment.
2. ⚠ **The UI's login mode and the server's enforcement are coupled by convention, not derivation.**
   `/bootstrap.features.authMode` is `System.getProperty("auth.mode", "none")`
   (`BootstrapRoutes.java:83`), while enforcement is decided by whether an `Authenticator` registered. The
   bundle's `serve.sh`/`serve.bat` set `-Dauth.mode=oidc` when they detect the security sidecar
   (`inspecto/package.ps1:892`), so the shipped bundle is consistent. A hand-launched jar with the sidecar
   on the classpath and no flag leaves the SPA in no-login mode against a server answering 401 on every
   route. `UNTRACKED` — filed in §5.

## 3. Specification

### 3.1 The posture: an auth-free core, and modules that add enforcement

All authentication was **removed from the common core on 2026-06-16**. The removed hand-rolled bearer plane
(per-route `Scope`, `-Dcontrol.token`, the Angular token screen) is gone, not gated. In a Personal build every
`ControlApi` route is open, the SPA boots straight to `/dashboard`, and there is no guard or interceptor.

Enforcement returns through five SPIs the core *defines* and never *implements*:

| SPI | Package | Contract | `@PublicApi` | Implemented by |
|---|---|---|---|---|
| `Authenticator` | `com.gamma.control` | `Optional<Subject> authenticate(HttpExchange)` | `since = "4.0.0"` | `OidcAuthenticator` (`inspecto-security`) |
| `Subject` | `com.gamma.control` | record `(id, capabilities, dataScopes, attributes)`; `dataScopes == null` = unscoped | — | built by the authenticator |
| `TokenRelay` | `com.gamma.control` | `exchangeCode`, `refresh`, default no-op `revoke`; absent ⇒ `/auth/*` answers `503 CAPABILITY_UNAVAILABLE` | `since = "4.0.0"` | `OidcTokenRelay` |
| `AccessDecider` | `com.gamma.control` | `decide(...)` → `ALLOW | DENY | ABSTAIN`; `explain(...)`; `seededPolicies()` default-empty | `since = "4.0.0"` | `PolicyEngine` (`inspecto-policy`) |
| `SecretsProvider` | `com.gamma.acquire` | `supports(scope)`, `resolve(scope, key)` | — | `FileKeystoreSecretsProvider` (`inspecto-security`) |

Discovery is `ServiceLoader` through `META-INF/services`; the core's `Authenticators` / `SpiSlot` wrapper
exposes `active()` (empty on Personal) and a `forTest(...)` seam. ⚠ `Subject` and `SecretsProvider` carry no
`@PublicApi` although both are implemented outside the core — the marker records *intent* (see
`api-stability.md`), and its absence here is an inconsistency, not a policy.

**Editions** (`GLOSSARY.md` §14 *Edition*): Personal bundles neither module; Standard adds
`inspecto-security` (Maven profile `edition-standard`, `pom.xml:73-77`); Enterprise adds `inspecto-policy` on
top (`edition-enterprise`, `pom.xml:112-116`). `package.ps1 -Edition Standard|Enterprise` bundles the
**shaded** `inspecto-security-*-sidecar.jar` (Nimbus classes verified present at package time — the
`SEC-SIDECAR-BOOT-1` lesson). No runtime flag selects a module: the classpath entry is the switch, and the
default `mvn -o clean test` reactor **never compiles either module** — see §8.

### 3.2 The request-gating order in `ControlApi`

Every request passes, in order (`ControlApi.dispatch`, `inspecto/src/main/java/com/gamma/control/ControlApi.java:716-717`):

1. **`authenticate(ex, path)`** (`:760-783`). Skipped for `PUBLIC_PATHS` and for self-verifying public
   routes. If an `Authenticator` is active: the `X-Actor` header is **rejected outright with
   `403 PERMISSION_DENIED`** (`:777-780` — the SEC-7a spoof guard: identity comes from the `Subject`, never
   from a header); a request that yields no `Subject` gets **`401 UNAUTHENTICATED`** (`:783`). With no
   `Authenticator` (Personal) the stage is a no-op.
2. **`authorize(ex, method, path)`** (`:796-808`) — the ABAC route-level policy enforcement point. No-op when
   no `AccessDecider` is registered, for public paths, or for subject-less exchanges. `DENY` → `403
   PERMISSION_DENIED`; `ABSTAIN` falls through. The action verb is derived by `actionFor` (`:812-818`):
   `GET`/`HEAD` → `read`; a route manifested under `canOperateRuns` → `operate`; everything else → `write`.
3. **The route's capability gate** — `ApiContext.withCapability(capability, handler)` (`ApiContext.java:194`)
   wraps every gated registration; a missing capability is `403 PERMISSION_DENIED`. The set of gated
   registrations is the **capability manifest** (§3.8).
4. **`WriteGates`** on mutation routes — independent of everything above (§3.12).

**`PUBLIC_PATHS`** (`ControlApi.java:197-198`, exact-match): `/health`, `/ready`, `/metrics`, `/bootstrap`,
`/auth/exchange`, `/auth/refresh`, `/auth/logout`. ⚠ `/metrics` in this list is what made a Personal bundle
serve full telemetry unauthenticated on every interface until EDG-01 cell 5 moved the exposition into
`inspecto-metrics` (2026-09-07) — Personal now answers it `503` naming the module. **Self-verifying public**
routes (`isSelfVerifyingPublic`, `:756-757`) carry their own credential in the URL and are deliberately not in
the list: `/public/dashboards/*` and `/public/delivery-status/*`. `GET /api/v1/openapi.json` is **auth-gated
on purpose** (2026-08-25, `38c7a32d`).

Error codes are `ErrorCodes.java` members carried in the v1 envelope: `UNAUTHENTICATED` (401),
`PERMISSION_DENIED` (403), `CAPABILITY_UNAVAILABLE` (503, a module is absent), `CONTROL_PLANE_READ_ONLY`
(503, no write root).

### 3.3 The `Subject` and its data scopes

`Subject(id, capabilities, dataScopes, attributes)` (`Subject.java:26-45`). `capabilities` is the
**effective** set — role grants minus Access-Profile denies (§3.7) — so role names never leave the
authenticator and every `withCapability` gate enforces profile denies with zero route changes. `dataScopes`
(SEC-7d, 2026-07-08) is `null` for an unscoped subject, otherwise the union of a `data_scopes` claim, any
`case:<scope>` role names, and authored `dataScopes` in `roles.toon`; `ObjectRoutes` applies it as filtered
lists, `404` on out-of-scope access (read *and* mutate) and pruned correlation graphs — event and audit
streams stay capability-gated by design. `attributes` (ABAC A1) holds only the claims allowlisted in
`roles.toon` `identity.attributeClaims`, never the raw token (§3.10).

### 3.4 The OIDC authenticator

`OidcAuthenticator` (`inspecto-security/src/main/java/com/gamma/security/OidcAuthenticator.java`) is a
**vendor-agnostic resource server**: Nimbus JOSE+JWT `RemoteJWKSet` + `DefaultJWTProcessor`, RS256, no vendor
SDK. Configuration is system properties only:

| Property | Role | Required |
|---|---|---|
| `-Dauth.oidc.issuer` | expected `iss` | **yes — the process exits without it** |
| `-Dauth.oidc.jwksUri` | JWKS endpoint (explicit; no discovery fetch by design) | **yes** |
| `-Dauth.oidc.audience` | expected `aud` | warn-only by decision (`modules/security.md`) |
| `-Dauth.oidc.rolesClaim` | which claim carries role names | default per module |
| `-Dauth.oidc.clientId` | the public client id the BFF exchanges on behalf of | for the relay |
| `-Dauth.oidc.tokenEndpoint` | the provider's `token_endpoint` | **mandatory, no default** (D15) — take it from `/.well-known/openid-configuration` |
| `-Dauth.oidc.gateway.issuer` / `.jwksUri` / `.header` | the optional **gateway trust mode** | opt-in |

**Gateway trust mode** (R0, 2026-07-23): a second JWT processor validates a *gateway-signed* assertion header
(default name `X-JWT-Assertion` — *a* convention, WSO2 APIM's, not *the* gateway), consulted **only when no
valid `Bearer` resolves**. Unsigned header identity is never trusted; `alg:none` and bare strings fail
verification, test-pinned. Clock skew is Nimbus's bounded 60 s default; no knob was added.

`RoleMapper` (`RoleMapper.java:29-40`) resolves the roles claim → `Roles.Def` → capabilities through the
**data-driven role table** (§3.6), fail-closed on an unknown role name; `dataScopesFor` builds the scope set
of §3.3. Access-Profile denies are stripped here, at authentication time (§3.7).

### 3.5 The BFF session

The browser never holds a refresh token. `AuthRoutes` (core — `inspecto/src/main/java/com/gamma/control/AuthRoutes.java`)
serves `POST /auth/exchange | /auth/refresh | /auth/logout`, delegating to the `TokenRelay`:

- the refresh token lives in the **httpOnly `inspecto_rt` cookie**, `SameSite=Strict` (`:106,113`);
- every `/auth/*` call passes `requireSameOrigin(ex)` (`:45,58,70`; gate at `:92-103`) — the `Origin` check
  is the CSRF defence, not a token;
- the SPA runs **PKCE (RFC 7636, S256)** as a **public client** — no client secret anywhere in the UI
  (`4x-public-pkce`, 2026-07-25: dead confidential-client code deleted, `appClientSecret` removed from source
  and all four environment files), the callback is parsed with `URLSearchParams` and **validates `state`**
  (`ce49a681` — the CSRF defence P1 only pretended to have);
- `SessionService` (`inspecto-ui/src/app/inspecto/api/session.service.ts`) is an `APP_INITIALIZER` gate:
  `init()` reads `/bootstrap`, and on `authMode === 'oidc'` tries a silent `refresh()` from the cookie, then
  re-pulls `/bootstrap` with the bearer for effective capabilities; `auth.interceptor.ts` attaches
  `Authorization: Bearer` and does 401 → silent refresh → retry.
- **Sign-out is three layers and all three must fire** (2026-07-26): `POST /auth/logout` revokes and clears
  the cookie; the in-memory access token is dropped; the browser is sent to the provider's
  `end_session_endpoint` (RP-Initiated Logout 1.0) with `client_id` + `post_logout_redirect_uri` —
  `id_token_hint` deliberately omitted because the id token lives behind the BFF. `SessionService.redirect()`
  is the single seam that leaves the SPA (it exists because jsdom makes `window.location.assign`
  non-configurable). ⚠ See §2 on where `endSessionUrl` can come from.
- **Personal renders no user menu at all** — no principal to name, no session to end.

### 3.6 Roles: a seeded, authorable, fail-closed table

`com.gamma.control.Roles` (core) holds the **seed table** and the doc grammar. Role *assignment* is the IdP's
(claims); role *definitions* are data. `Roles.effective(ex)` overlays the bound Space's `roles.toon`
(`Roles.FILE`, `Roles.java:62`) **per role name** — an authored `[]` revokes a seed role, unnamed seed roles
keep their defaults — mtime-cached so edits apply on the next request with no restart. **Fail-closed:** an
existing-but-unreadable `roles.toon` suspends *all* role grants. `GET/PUT /access/roles` author it
(`AccessRoutes.java:46-47`; PUT gated `canConfigureAccess`); Settings ▸ Access ▸ **Roles** edits it with
`source: authored|seed` badges, a strike-through overlay for capabilities an Access Profile denies, and a
Revert that removes the override.

**The capability vocabulary is exactly ten names**, static across editions (a Standard-authored role file
must validate on Personal — a per-edition validator was refused, `EDITIONS.md` SEC-10 note):

| Capability | Gates |
|---|---|
| `canAuthorWorkbench` | Pipeline / Collector authoring writes |
| `canOnboardConnections` | `POST/PUT/DELETE /connections` — its own grant, **not** `canAuthorWorkbench`, because Connections are the credential + network-egress surface (2026-07-22) |
| `canOperateRuns` | run/operate routes (and the `operate` ABAC verb) |
| `canTriageRequirements` | `RequirementRoutes` `/decision` + `/deliver`; submission stays open |
| `canAuthorAlertRules` | Alert Rule authoring |
| `canCurateMenus` | `/nav/menus` — split out of `canAuthorWorkbench` 2026-07-25 (D4) |
| `canConfigureAccess` | `PUT /access/roles|policies|catalog|profiles` |
| `canOfferDatasets` | offering a Dataset cross-space — admin/super since D14 (a data-*exposure* decision with no second gate) |
| `canApproveShares` | approve / deny / revoke an Exchange grant |
| `canRequestShares` | request access / pin a snapshot version |

**Seed roles** (`Roles.java:121-131`): `pipeline-developer`, `app-developer`, `developer` (the builder set),
`operations`, `power`, `admin` (`canOnboardConnections`, `canConfigureAccess`, `canApproveShares`,
`canOfferDatasets`, `canCurateMenus`, `canTriageRequirements`), `super` (the whole vocabulary via
`KNOWN_CAPABILITIES`), `business` (**only** `canTriageRequirements`). ⚠ `Roles.SEED` is asserted by an
*equality* test in `OidcAuthenticatorTest` — every grant change must update it, and that test runs **only**
under `-Pedition-standard|enterprise` (§8).

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

`CapabilityManifest` (`CapabilityManifest.java:18-25`) declares every gated `method + pattern → capability`
registration (70 at R4). `CapabilityManifestTest` **source-scans the route files** and fails the build on
drift in either direction — an entry with no registration, a registration with no entry — matching on the
capability **string literal** at the registration site; it also asserts every route-demanded capability is
granted by at least one seed role, and derives `Roles.KNOWN_CAPABILITIES`. ⚠ A manifest entry and its route
gate must land in the same commit.

### 3.9 Component sharing — the intra-Space grant envelope

Every `ComponentStore` kind accepts an optional `owner` + `shares: [{subjectType: role|user, subjectId,
access: view|edit}]` envelope (R3). **No `shares` key ⇒ byte-identical behaviour** (every pre-R3 document);
once present the component is restricted: owner and `canConfigureAccess` holders have full access, shares
grant `view`/`edit`, everyone else gets the SEC-7d contract (filtered lists, `404`) on read *and* mutate.
Role matching rides `ComponentAccess.ATTR_HELD_ROLES`, stamped by the authenticator and never serialized.
The `user` subject is the opaque IdP `sub`. Cross-Space sharing is the **Exchange** family
(`inspecto-exchange`, `ShareGrant` — fail-closed `requested → active | denied → revoked | expired`, every
transition audited), gated by `canOfferDatasets` / `canApproveShares` / `canRequestShares` and absent from
Personal since EDG-01 cell 4; its mechanism is `exchange-sharing.md`.

### 3.10 Enterprise ABAC — the Access Policy engine

`inspecto-policy` registers `PolicyEngine` (`inspecto-policy/src/main/java/com/gamma/policy/PolicyEngine.java:52`)
on the `AccessDecider` SPI. Personal and Standard never bundle it and behave byte-identically.

- **Attributes (A1).** `roles.toon` `identity: {attributeClaims: [...]}` is an **allowlist**; the
  authenticator copies exactly the allowlisted-and-present verified claims onto `Subject.attributes` — never
  the raw token, and nothing when the doc is unreadable (attributes fail closed alongside role grants). Both
  the Bearer and the gateway path.
- **Condition grammar (A2).** `com.gamma.util.Conditions` (`inspecto-util` — domain-agnostic on purpose:
  "one policy engine, many policy kinds"): recursive-descent, parse-once, `== != in contains and or not ( )`,
  literals and dotted refs over a nested `Map` context, strict-Boolean truthiness, type-mismatch-is-false,
  offset-bearing parse errors. Pinned by `ConditionsTest`.
- **The document.** Per-Space `access-policies.toon` (`AccessPolicies.FILE`, `AccessPolicies.java:49`): rows
  `{name, effect: allow|deny, target: {actions?, resourceKinds?}, when?}`; mtime-cached; one validate
  grammar shared by the file parser and `GET/PUT /access/policies` (`AccessRoutes.java:49-50`; a `when` that
  does not parse is `422`). **Unreadable doc ⇒ the engine DENIES loudly**, never "no policies". Authoring
  is core (the routes exist in every edition); *evaluation* is Enterprise.
- **Decision (A3).** `decide()` (`:74-96`) is **deny-overrides → allow → ABSTAIN** over
  `AccessPolicies.effective`; context is `subject.{id, capabilities, dataScopes, roles}` + A1 attributes,
  `env.{action, route, space}`, and at row level `resource.*` with `resource.space` defaulting to the bound
  Space. Two enforcement points: the route-level `authorize` stage (§3.2) and the row-level **`RowScope`**
  filter (`RowScope.java:26`; used by `inspecto-ops` `ObjectRoutes` and `AnnotationTargets`) — a `DENY` hides
  the row (`404` / filtered). 🔴 **A policy `ALLOW` never bypasses a capability gate** — the plan's combining
  order was tightened for defence in depth; `ABSTAIN` falls through to the Standard capability / profile /
  sharing gates.
- **Space isolation (A4 = SPC-5).** Two **engine-resident seed policies** (`PolicyEngine.SEED`:
  `space-isolation`, `space-isolation-rows`, `:62-66`), overlaid per policy name by the authored doc: deny
  when the subject's mapped `space` home claim ≠ the bound Space, at route and row level. They **engage only
  when a `space` claim is mapped** (unmapped ⇒ no isolation, never a bricked API) and exempt
  `canConfigureAccess` holders. `env.space` is always bound because `EventLog.currentSpaceId()` never returns
  null — un-prefixed server-global routes bind the default Space.
- **Decision audit (A5).** Every policy `DENY` and every route-level policy-matched `ALLOW` goes through
  `AuditTrail.policyDecision(...)` (`AuditTrail.java:73-89`) as `access.denied` / `access.granted`, category
  `authorization`, with actor, verb, route, row kind/id and the matched policy name
  (`AccessDecider.ATTR_MATCHED_POLICY`; `<policies-unreadable>` on a fail-closed deny). Read back via
  `GET /events?type=ACCESS_DENIED|AUDIT`. A row-level *allow* is deliberately **not** audited (it fires per
  surviving row); `ABSTAIN` is not a decision and is never audited.
- **Operability.** `GET /access/policies` surfaces the seed denies too, each row `source: authored|seed`
  (via `AccessDecider.seededPolicies()`, default-empty). `GET /access/explain?route=&method=&resourceKind=`
  (`AccessRoutes.java:56`; `PolicyEngine.explain`, `:113-136`) is a side-effect-free **"why denied?"**
  dry-run for the caller's own session — decision, matched policy, per-policy `{targeted, conditionHeld,
  source}` trace, enforcing and auditing nothing. **It is a GET on purpose**: a POST would be a `write` the
  policy under test could `403`, locking the denied subject out of their own explanation. Both are
  Enterprise-only in effect (Personal/Standard answer `{enabled: false}`); UI: Settings ▸ Access ▸
  **Policies**, read-only by design (`access-policies.component.ts`) — authoring stays TOON + API (§5).

### 3.11 Transport and bind address

`-Dhttps.keystore=<PKCS12 path>` + `-Dhttps.keystore.password=<pw>` switch `ControlApi` from `HttpServer` to
the pure-JDK `HttpsServer` with `SSLContext.getInstance("TLSv1.3")` (`ControlApi.java:301-324`); unset ⇒
plain HTTP (the Personal default). `-Dcontrol.bind=<host>` restricts the listen address in every edition
(`:271-296`) — ⚠ the default binds **every interface**, in Personal too; "localhost-bound" in
`STAKEHOLDER_OVERVIEW.md` is wrong (plan §8 cluster). HTTPS lives in **core**, not in `inspecto-security`, and
is the one SEC mechanism with **no test** (§8.7).

### 3.12 Secrets

`SecretResolver` (`inspecto-acquire`) resolves `${ENV:NAME}`, `${SYS:NAME}` and bare `${NAME}` in every
edition; `${FILE:path}` and `${KEYSTORE:alias}` (JCEKS) route to a `SecretsProvider` and arrive by
`ServiceLoader` from `inspecto-security` (`FileKeystoreSecretsProvider`), which only the Standard and
Enterprise bundles carry. **A Personal bundle refuses those two schemes with an edition-naming message, never
a silent null** (2026-09-06). `SecretScrubber` keeps resolved values out of logs; `tools/check-secrets.mjs`
keeps secret-shaped literals out of the tree (§8.6). Vault / cloud KMS: §5.

### 3.13 The write-root gate — separate from auth, in every edition

`WriteGates.requireWriteRoot(api, what)` (`WriteGates.java:14-24`, `@PublicApi(since = "4.0.0")`): with
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
presentation, never a boundary (`lens.service.ts:79-85`); the server is the enforcement point. Per-lens
Access Profiles reach the UI through `AccessStateService.setActionGrants()`; feature flags from `/bootstrap`
(`features.exchange|geoLink|events|ops`) **hide** navigation for an absent module — never a toast.

⚠ **`permissions[]` is emitted and never read.** `Envelope.success` emits `subject.capabilities() ∩
applicable` when a route declares `ApiContext.resourcePermissions(...)` (SEC-7b; no `permissions` key on
Personal). The SPA declares it in `V1Envelope.permissions?: string[]` (`inspecto/api/v1.ts:37`) and **no code
reads it** — every affordance is gated on `LensService` instead. The contract is correct and the consumer is
absent; recorded in §5 as an untracked gap, not a contradiction: the envelope field was always documented as
"a fail-closed affordance signal, never the security boundary".

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
| 2026-09-07 | The **capability vocabulary is static across editions** | Deriving it from registered routes would make a Standard-authored role file fail validation on Personal |
| 2026-09-08 | **Audit read stays in core** in every edition via `AuditLogRoutes` (`/audit/search`, `/audit/export`), fail-closed so it cannot become the events feed | `EDITIONS.md` promises Personal an audit log; gating the events module would have removed it |

### Session and incident

| Date | Decision | Why |
|---|---|---|
| 2026-07-06 (W6d) | **BFF**: refresh token in an httpOnly `SameSite=Strict` cookie + `Origin` check | The browser never holds a refresh token; the `Origin` check is the CSRF defence |
| 2026-07-25 | The SPA is a **public PKCE client**; every client secret deleted from source and environments | Five secrets had been public in git since 2026-06-12 (SEC-INCIDENT-1). "Verified by compiler and jsdom, never by an IdP" — `ce49a681` had to add real `state` validation afterwards |
| 2026-07-26 | Sign-out is **three layers**, `id_token_hint` omitted | Without RP-Initiated Logout the next sign-in completes with no credential prompt; the id token lives behind the BFF |
| 2026-07-26 | **No user menu on Personal** | Nothing to name, nothing to end |
| 2026-08-17 (operator) | Branch `4.x` deleted; the incident fix commits live on `master` | Nothing had shipped on `4.x` |
| 2026-08-29 | **SEC-INCIDENT-1 closed by decommission** | The affected system was decommissioned, so no issuer entry remains to rotate; the values stay in public history and are immaterial only because none is in use |
| 2026-09-02 (operator) | **SEC-08 masking / classification-driven row scope is Enterprise-only** | Classification (SCH-03) and row scope both exist; the join is Enterprise build work |
| 2026-09-06 | Secrets: `${ENV}`/`${SYS}` everywhere, `${FILE}`/`${KEYSTORE}` by module, **Vault/KMS on client demand only** | A Personal bundle refuses the module-backed schemes with a message, never a silent null |
| 2026-09-07 | `-Dauth.oidc.audience` is **warn-only** | Recorded in `modules/security.md`; a hard fail would brick deployments whose IdP omits `aud` |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| **Policy-authoring UX** — a matrix / create editor beyond hand-authored TOON | `BACKLOG.md` §3 *Security: policy-authoring UX* (P3); `EDITIONS.md` SEC-05 | Seed visibility, explain and the read-only Policies tab shipped; authoring is TOON + `PUT /access/policies` with `422` on a bad `when` |
| **`X-Actor` full removal** | `BACKLOG.md` §2 *X-Actor full removal*; `EDITIONS.md` SEC-11 | Already **rejected** on Standard/Enterprise (§3.2); the header path survives only for Personal's actor attribution. Gate: **the next MAJOR tag** (restated 2026-09-07 — the API-v1 sunset it used to cite was deleted 2026-07-25) |
| **Vault / cloud-KMS secrets** (GAP-6 / SEC-8) | `BACKLOG.md` §3 *Deployment topology gaps*; §6 standing note | Enterprise-only, **only when a client policy requires it**. The `SecretsProvider` SPI is the seam; nothing else is designed |
| **Data masking / row scope by field classification** | `EDITIONS.md` SEC-08; `ProcessorCatalog` `quality.pii.mask` + `quality.compliance.redact` (`Status.PLANNED`) | Enterprise-only by decision; no BACKLOG row of its own — the two catalog entries are the only trace |
| **SEC-INCIDENT-1 carry-forwards** | `BACKLOG.md` §2 *SEC-INCIDENT-1 carry-forwards* | Delete the off-repo pre-rewrite bundle (five cleartext secrets); confirm no reuse; internal hostnames still in-repo; a dated CC6.1 line in `compliance/controls-matrix.md` |
| **Compliance program NFR-7** (G8 evidence, pen test, FIPS leg) | `BACKLOG.md` §2 *Compliance program (NFR-7)*, §5 *Compliance repo-side artifacts* | `CMP`'s, listed here because the controls cite SEC mechanisms as evidence |
| **Enterprise distributed tier** — per-tenant ABAC beyond Space isolation | `BACKLOG.md` §2 *E1 Enterprise distributed tier* | Design only |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-NOPROOF-1`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** `permissions[]` in the v1 envelope **ADOPT** — the shell interceptor stops discarding the envelope FIRST, then affordances gate on it → `BACKLOG.md` §3 `CLIENT-HALVES-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **"Module present, auth off" is impossible** (`SEC-SIDECAR-BOOT-1`) | `auth-security.md` §"does not boot"; `SpiSlot.active()` has no guard | A Standard bundle cannot be smoke-tested without an IdP. Needs a guard around SPI resolution, not a flag — and an operator call on whether that mode should exist at all |
| **`authMode` is declared, not derived** | `BootstrapRoutes.java:83`; `package.ps1:892` | The SPA's login mode and the server's enforcement agree only because the launcher sets both. Deriving `authMode` from `Authenticators.active()` would close it in one line — but changes the contract of a public path |
| **`permissions[]` has no consumer** | `v1.ts:37` declared; zero reads in `src/app` | Either the UI adopts it for per-resource affordances (the SEC-7b intent) or the field is documented as reserved. Today it is neither |
| **Server-published OIDC config** (`bootstrap.auth.*`) | no `authorizeUrl` / `endSessionUrl` in any Java file | The SPA already reads `boot.auth?.endSessionUrl`; `BACKLOG.md` §6 refused it "as scoped" and warns that a server-sent empty string would beat `environment.oidc` because the SPA uses `??`. If it is ever built, that coalescing must change first |
| ✅ ~~**HTTPS has no test**~~ **CLOSED 2026-09-09** | `ControlApiHttpsTest` — 5 tests | SEC-4 now has proof, including the fail-closed half: a broken keystore must throw, never serve cleartext on the TLS port. Mutation-proven (a silent fallback fails 2 of 5) |
| **`Subject` and `SecretsProvider` lack `@PublicApi`** | `Subject.java`, `SecretsProvider.java` | Both are implemented outside the core; the marker records intent and is inconsistent across the five SPIs |

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

### 6.2 OIDC discovery (`/.well-known`) for `tokenEndpoint` or `endSessionUrl` — REFUSED

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
| The pre-2026-06-16 bearer plane (`Scope`, `-Dcontrol.token`, the token screen) | the SPI posture (§3.1) | `auth-security.md` |
| A hand-rolled JOSE library for the authenticator (`rbac-abac-plan` R0 draft) | **MOOT** — Nimbus was already the signed-off, module-confined dependency; R0 was drafted from a stale premise | `rbac-abac-plan.md` §R0 (archive) |
| A separate authorize middleware for Access Profiles (R2 sketch) | enforcement at authentication (§3.7) | §4 |
| Seeding `access-policies.toon` at Space creation | engine-resident `PolicyEngine.SEED` overlaid by name | §3.10 |
| `KeycloakTokenRelay`; the derived Keycloak token endpoint | `OidcTokenRelay`; a mandatory explicit flag | §4 D15 |
| `canAuthorWorkbench` gating Connections and menu curation | `canOnboardConnections` (2026-07-22); `canCurateMenus` (D4) | §3.6 |
| `canOfferDatasets` on the builder and power seeds | admin/super (D14) | §3.6 |
| Uniform `granted && !readOnly && allows` in `LensService` | the identity / lens-scoped split | §3.14 |
| "Business lens ⇒ read-only" as a product statement | no longer true | §3.14 |
| `rbac-abac-plan` Q4 "roles-only subjects in v1?" | both `role` and `user` shipped; `user` = opaque IdP `sub` | §3.9 |
| `rbac-abac-plan` Q2 "fold X-Actor retirement into R2" | X-Actor is *rejected*, not removed; removal is gated on the next MAJOR | §5 |
| The X-Actor gate "client migration with the API-v1 sunset" | the next MAJOR tag (the sunset apparatus was deleted 2026-07-25) | `BACKLOG.md` §2 |
| Lens Access P3's root-default-allow as contractual for roles | flagged forward-compatible only: the server default for roles may flip to deny-by-default — a security-module call, unmade | `lens-access-config-design.md` §P3 (archive) |
| The 2026-06-19 `STAKEHOLDER_OVERVIEW.md` ("a *planned* `inspecto-security` module", "Enterprise (future)", "Personal localhost-bound") | shipped 2026-07-06 / 2026-07-23; binds every interface | plan §8 cluster — reconciled at step 6, not here |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| The whole posture, RBAC R0–R5, ABAC A1–A5, the 2026-07-25 decisions, the boot precondition | `docs/okf/backend/editions/auth-security.md` (`Concept`) | `inspecto-security/, inspecto-policy/` | the long-form narrative every row above was distilled from |
| The Standard module's classes, properties, shading and boot behaviour | `docs/okf/backend/modules/security.md` (`Concept`) | `inspecto-security/` | `audience` warn-only, `SEC-SIDECAR-BOOT-1`, why the sidecar is shaded |
| Edition gating as a mechanism; the EDG-01 recipe items | `docs/okf/backend/editions/editions-model.md` (`Concept`) | `pom.xml` | how a module joins a profile and what the compiler cannot see |
| Request dispatch, `PUBLIC_PATHS`, the envelope, error codes | `docs/okf/backend/control-plane/control-api.md` · `api-v1.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | the contract SEC gates inside |
| Intra-Space shares and the cross-Space Exchange lifecycle | `docs/okf/backend/control-plane/exchange-sharing.md` (`Concept`) | `inspecto-exchange/` | the `ShareGrant` state machine and the attribute-scope pin |
| The write-root jail, `PathJail`, `ConfigSafetyValidator` | `docs/okf/backend/config/config-safety.md` (`Concept`) | `inspecto-config/` | what happens *inside* the root once §3.13 lets a write through |
| Actor attribution, `AuditTrail`, `access.denied` in the ledger | `docs/okf/backend/control-plane/events-metrics.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | how a decision becomes an event |
| 🔴 **The UI seam has no concept file.** `LensService`, `SessionService`, the Access / Roles / Policies editors and the sign-in flow are documented only in source and in this spec | *(none — gap)* | `inspecto-ui/src/app/inspecto/api/lens.service.ts` · `session.service.ts` · `inspecto-ui/src/app/modules/admin/access/` | the code. Same class of gap the plan flagged for `INC`'s missing backend concept; filed under `UI`/Surfaces, not here |

---

## 8. Verification

### 8.1 Core gate tests — `inspecto/src/test/java/com/gamma/control/` (default reactor)

These run in every build because they exercise the *core* seams through `Authenticators.forTest(...)`:

| Class | Proves |
|---|---|
| `ControlApiAuthV1Test` | the AuthN gate: `401 UNAUTHENTICATED` without a Subject, `403` on `X-Actor` with an authenticator active, public paths open |
| `ControlApiAuthSessionV1Test` | the BFF: `/auth/*` cookie shape, `Origin` refusal, relay-absent `503` |
| `ControlApiAccessRolesTest` | `GET/PUT /access/roles`, the per-role overlay, `[]` revokes, fail-closed on an unreadable file |
| `ControlApiAccessPoliciesTest` | `GET/PUT /access/policies`, `422` on a bad `when`, seed rows tagged `source` |
| `ControlApiAccessTest` | Access Catalog + Profiles round trip |
| `AccessGrantsTest` | nearest-ancestor resolution, root default allow, union across roles, deny → capability binding |
| `CapabilityManifestTest` | manifest ↔ registration congruence in both directions; every demanded capability seeded; `KNOWN_CAPABILITIES` derived |
| `ControlApiAuditTest` | audit events emitted and immutable |
| `ControlApiShareTest` | the self-verifying public dashboard share: tamper and expiry |
| `ControlApiSpacesTest` | `authenticatedCreateSucceedsWhenNoSpaceIsHostedYet`; `purgingTheLastSpaceOnDiskIsRefused` (the zero-hosted-spaces trap) |
| `ExchangeAttributeScopeTest` | exchange attributes are private by default, never by guarantee |
| `NoExchangeShipsInThePersonalBuildTest` | the Personal bundle carries no exchange module |
| `ApiContractTest` | every write route passes `WriteGates`; the OpenAPI document matches the routes |

### 8.2 Module tests — run **only** under an edition profile

⚠ **The default `mvn -o clean test` compiles neither module.** A green default build says nothing about the
34 + 20 tests below; run `mvn -o clean test -Pedition-standard` (31 modules, 4106 tests at HEAD) or
`-Pedition-enterprise` (32 modules, 4126). `Roles.SEED`'s equality assertion was red on `master` for weeks
once because of exactly this.

| Module | Class | `@Test` | Proves |
|---|---|---|---|
| `inspecto-security` | `OidcAuthenticatorTest` | 24 | JWKS validation, roles claim → capabilities, `Roles.SEED` equality, profile-deny stripping, gateway path, A1 allowlist |
| `inspecto-security` | `OidcTokenRelayTest` | 7 | exchange / refresh / revoke against a fake token endpoint; the mandatory `tokenEndpoint` |
| `inspecto-security` | `FileKeystoreSecretsProviderTest` | 3 | `${FILE}` / `${KEYSTORE}` resolution, JCEKS |
| `inspecto-policy` | `PolicyEngineTest` | 12 | deny-overrides → allow → abstain; seeds overlaid by name; fail-closed on an unreadable doc; `explain` trace |
| `inspecto-policy` | `ControlApiPolicyEnforcementTest` | 8 | both PEPs over real HTTP: route `403`, row hidden, `access.denied` audited, space isolation engages only with a mapped claim |

`inspecto-security`'s count is **34**, measured 2026-09-08 (24 + 7 + 3); `auth-security.md` said 41 and is
corrected with this spec.

### 8.3 Grammar and secrets

| Class | Module | Proves |
|---|---|---|
| `ConditionsTest` | `inspecto-util` | the A2 grammar: operators, strict-Boolean truthiness, type-mismatch-is-false, offset-bearing errors |
| `SecretResolverTest` | `inspecto-acquire` | `${ENV}` / `${SYS}` / bare forms; the edition refusal when no provider serves `FILE`/`KEYSTORE` |

### 8.4 UI specs — `inspecto-ui/src/app/` (vitest)

Fourteen specs sit directly on this mechanism: `inspecto/api/session.service.spec.ts` (boot, refresh, the
three-layer logout, `redirect()` seam), `lens.service.spec.ts` (identity vs lens-scoped, honor-system
mode), `auth.interceptor.spec.ts` (Bearer + 401 refresh), `inspecto/access/access-catalog.spec.ts`,
`layout/common/lens-switcher/lens-switcher.component.spec.ts`, `layout/common/user/user.component.spec.ts`
(sign-out), `modules/admin/access/access.component.spec.ts`, `access-roles.component.spec.ts`,
`access-policies.component.spec.ts`, `role-form.dialog.spec.ts`, `modules/admin/session/sign-in.a11y.spec.ts`
and `callback.a11y.spec.ts`, `core/navigation/navigation.service.spec.ts` (module-absent nav hiding),
`inspecto/components/connectivity-banner.component.spec.ts`.

### 8.5 SPI contracts (`META-INF/services`)

| File | Provider |
|---|---|
| `inspecto-security/src/main/resources/META-INF/services/com.gamma.control.Authenticator` | `com.gamma.security.OidcAuthenticator` |
| `inspecto-security/src/main/resources/META-INF/services/com.gamma.control.TokenRelay` | `com.gamma.security.OidcTokenRelay` |
| `inspecto-security/src/main/resources/META-INF/services/com.gamma.acquire.SecretsProvider` | `com.gamma.security.FileKeystoreSecretsProvider` |
| `inspecto-policy/src/main/resources/META-INF/services/com.gamma.control.AccessDecider` | `com.gamma.policy.PolicyEngine` |

### 8.6 Guards

- `tools/check-secrets.mjs` — flags secret-shaped literals (`clientSecret:`, `password=`, `client_secret=` in
  URLs) in tracked files and, with `--range`, in the pushed diff; ignores `${ENV:…}` references and
  placeholders; wired into `.githooks/pre-push` and `ci.yml`. Both modes are needed: a secret committed and
  moved one commit later passes a tree scan.
- `CapabilityManifestTest` is a guard in test clothing (§3.8).
- `tools/check-vocabulary.mjs` keeps *Lens-as-permission* language out of the docs by keeping the binding
  §1-A definitions canonical.

### 8.7 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **HTTPS is untested** | zero references to `keystore`, `HttpsServer` or `SSLContext` under `inspecto/src/test`. SEC-4 is a Must (S) |
| **No test runs against a real IdP** | every OIDC test drives a fake JWKS / token endpoint; the incident plan's own lesson was "verified by compiler and jsdom, never by an IdP". The Keycloak / WSO2 blueprints in `docs/api/deployment/` are unexercised by CI |
| **The Standard bundle's boot precondition has no test** | `SEC-SIDECAR-BOOT-1` was found by a packaging smoke, by hand |
| **`authMode` ↔ enforcement coupling has no test** | nothing asserts that a bundle with the sidecar also sets `-Dauth.mode=oidc` |
| **`permissions[]` has no consumer to test** | §3.14 |
| **Tamper-evidence of the audit log** | `EDITIONS.md` SEC-09 says "tamper-evident"; `AuditTrail.java` contains no hash, chain or signature. The ledger is append-only; that is the whole claim. Owned by `OPS`, recorded here because the row is `SEC-09` |
