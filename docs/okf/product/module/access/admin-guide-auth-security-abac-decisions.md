---
type: Concept
title: Auth & Security — Advanced ABAC and the 2026-07-25 decisions
description: Part 1 of 3 of Auth & Security: Advanced ABAC — the Access Policy engine (inspecto-policy, workstream A); Decisions of record — 2026-07-25 product session (BACKLOG D4 / D14 / D15)
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-advanced]
timestamp: 2026-07-24T00:00:00Z
---

# Auth & Security — Advanced ABAC and the 2026-07-25 decisions

Part 1 of 3 of [Auth & Security](admin-guide-auth-security.md) · next: [Fail-closed boot, Space and config guards](admin-guide-auth-security-boot-guards.md).

## Advanced ABAC — the Access Policy engine (`inspecto-policy`, workstream A)

The `edition-advanced` Maven profile = `edition-standard` + the new **`inspecto-policy`** module
(artifactId `inspecto-policy`), which registers a `PolicyEngine` on the core's **`AccessDecider`** SPI
via `META-INF/services`. Basic/Standard never bundle it and behave byte-identically. Build/test with
`mvn -o -Pedition-advanced clean test` ([build & test](../../layer/platform/spec-build-test.md)).

- **Attribute model (A1).** `Subject` gained an additive `attributes()` map (empty on every pre-A1 caller).
  `roles.toon` carries an optional `identity: {attributeClaims: […]}` **allowlist**; `OidcAuthenticator`
  copies exactly the allowlisted-and-present verified claims onto the Subject (never the raw token; nothing
  when the doc is unreadable — attributes fail closed alongside role grants). Both Bearer and gateway paths.
- **Condition grammar (A2).** `com.gamma.util.Conditions` (inspecto-util, domain-agnostic — the "one policy
  engine, many policy kinds" library): recursive-descent, parse-once → predicate over a nested `Map` context
  via `DottedPath`; grammar `== != in contains and or not ( )` + literals + dotted refs; strict-Boolean
  truthiness, type-mismatch-is-false, offset-bearing parse errors. Core `AccessPolicies` mirrors `Roles`:
  per-space `access-policies.toon` (`{name, effect: allow|deny, target:{actions?,resourceKinds?}, when?}`),
  cached by the file's text, one validate grammar shared by the file parser and `GET/PUT /access/policies` (`when`
  parse-gates 422). Unreadable doc ⇒ the engine DENIES loudly, never "no policies".
- **Enforcement (A3).** `AccessDecider` SPI (core) is consulted at two PEPs: the route-level **authorize
  stage** in `ControlApi.routeDispatch` (after authenticate; DENY → 403; skips public paths + subject-less
  exchanges) and the row-level **`RowScope`** filter (generalizes `ObjectRoutes`' SEC-7d filter — DENY hides
  the row 404/filtered; consumers: Ops objects, annotation targets, and — since LA-20 — an Investigation's
  Working Set relation, `resourceKind: investigation` with `resource.{id,owner,dataset,parent}`). `PolicyEngine` = deny-overrides → allow → ABSTAIN over `AccessPolicies.effective`;
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
  `GET /events?type=ACCESS_DENIED|AUDIT` ([events & metrics](../event/spec-events-metrics.md)). A
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
  Advanced-only (the seam is default-empty; Basic/Standard show authored rows only and
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
  allowlisted, is unreadable after upgrade ⇒ deny-all on Advanced, recoverable only on disk (the PUT is
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
  The browser pass over the Policies-tab editor ran on 2026-09-27 on the `fff119d6b` demo build (*Preview
  impact* matrix, `would-lock-out` refused in the dialog, the `unknown-role` warning under the row, *Why denied?*,
  delete) and closed the board row; the editor is also covered by vitest specs.
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
  * **`KeycloakTokenRelay` → `OidcTokenRelay`** (class + `META-INF/services/com.gamma.spi.auth.TokenRelay` +
    `OidcTokenRelayTest`), matching `OidcAuthenticator`'s neutral naming.
  * **`auth.oidc.tokenEndpoint` is now mandatory with no default.** The old fallback derived
    `<auth.oidc.issuer>/protocol/openid-connect/token` — one vendor's path layout baked into the product.
    There was no discovery fetch to derive it from then (this module configured `auth.oidc.jwksUri` explicitly
    too; since 2026-10-13 discovery or the `keycloak` preset can supply it, below), so without one the relay
    **fails fast** at construction with a message naming the property:
    take `token_endpoint` from the provider's `/.well-known/openid-configuration` and pass
    `-Dauth.oidc.tokenEndpoint=…`. ⚠ **Deployment-breaking for anyone who relied on the derived default** —
    Keycloak deployments must now set the flag explicitly (same value as before).
  * The gateway trust header still defaults to `X-JWT-Assertion` — **unchanged behaviour**, now documented in
    `OidcAuthenticator`'s javadoc as *a* convention (WSO2 APIM's, widely copied), not *the* expected gateway;
    any gateway is accommodated via `-Dauth.oidc.gateway.header`.
  Litmus test for future work: any new auth code that cannot be pointed at a different compliant IdP by
  configuration alone is wrong.
  * **Provider preset + discovery (2026-10-13) — the client chooses WSO2 Identity Server OR Keycloak by
    configuration.** `-Dauth.oidc.provider=generic|wso2|keycloak` (default `generic` = every behaviour above,
    byte-for-byte) and `-Dauth.oidc.discoveryUrl=<issuer>/.well-known/openid-configuration`. `OidcConfig`
    (module-private) resolves `issuer`, `jwksUri`, `tokenEndpoint`, `authorizeUrl`, `endSessionUrl` with the
    precedence **explicit `-D` > the discovery document (fetched once per URL at boot) > the `keycloak`
    preset's fixed `<issuer>/protocol/openid-connect/*` layout**. This does not reopen the D15 ban: the
    Keycloak layout applies only to an operator who asked for `provider=keycloak`, and `generic` still derives
    nothing. A discovery failure (unreachable, non-200, no `issuer`), a discovery `issuer` that differs from an
    explicit `-Dauth.oidc.issuer`, an unknown provider value, or a non-`https` discovery URL (loopback `http`
    allowed) **refuses to boot** with a value-free message. `bootstrapAuth()` reads the same resolver, so
    `/bootstrap` `auth` carries `authorizeUrl` / `clientId` / `endSessionUrl` with no SPA change.
    * **Roles:** `provider=keycloak` unions `realm_access.roles` and `resource_access.<clientId>.roles`
      (`-Dauth.oidc.clientId`, else the token's `azp`) — unless `-Dauth.oidc.rolesClaim` is set explicitly,
      which always wins and switches the union off. `wso2` adds nothing (`groups` via `rolesClaim`, as before).
    * **Audience:** unchanged — only the configured `-Dauth.oidc.audience` is checked and the "OFF" warning
      still fires when unset. ⚠ Keycloak's access-token `aud` is `account` unless the client carries an audience
      mapper, so the realm needs one (`dev-infra/keycloak/inspecto-realm.json` has it).
    * 🔴 **Keycloak 26 access tokens carry NO `sub` unless the client has the built-in `basic` scope** — and
      `OidcAuthenticator` requires `sub` ("JWT missing required claims: [sub]"). Found only by the live run;
      a realm authored from older Keycloak examples omits `basic`.
    * ⚠ Keycloak's realm import REJECTS unknown JSON fields (`Unrecognized field "_comment"`): strip the
      `_comment` keys from `docs/api/deployment/keycloak-realm-blueprint.json` before importing it.
    * Proven live 2026-10-13 against `quay.io/keycloak/keycloak:26.8.1` — `OidcKeycloakAgainstRealProviderTest`
      (SKIPS without `-Dkeycloak.issuer`) plus a booted backend; evidence in `docs/api/deployment/README.md`.
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
  (`adminRoleGrantsExactlyItsSeededSet`, named `adminRoleGrantsOnboardConnectionsAndNotWorkbench` until 2026-11-15) —
  every future grant addition must update that test, and
  it must be run under **`-Pedition-advanced`**: the default reactor omits `inspecto-oidc` entirely, so a
  plain `mvn -o clean test` cannot see a failure there. That gap had already left this assertion red on
  `master` since `63a556f8`; see `.claude/skills/build-verify/SKILL.md`.
- **The seeded `admin` role holds `canAuthorWorkbench` (operator, 2026-11-15; `LA-FULL-CYCLE-TEST-1` D1).** It
  reverses the 2026-07-22 sign-off that kept the capability Builder-only. Reason: `admin` holds `canConfigureAccess`,
  and `PUT /access/roles` lets a holder rewrite any role's capability list (`Roles.validate` checks only that each
  capability is known), so an administrator could already grant itself the capability; withholding it protected
  nothing and left the person who creates a Space unable to save a view or change a Link Analysis setting. ⚠ Where the
  approval policy governs `access-roles`, that self-grant needs a second person, and this seed grant does not: an
  install that wants administrators who do not author states it in `roles.toon` (an authored `admin` row replaces the
  seeded one). Maker-checker is unchanged: nobody approves their own change. Pinned by `ControlApiFirstRunTest` and
  `OidcAuthenticatorTest.adminRoleGrantsExactlyItsSeededSet`.
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

**A node and a signal must also agree on the node ID** (`LA-ACCESS-NODE-IDS-1`, 2026-10-23). Saved Access
profiles key grants by catalog node id, and `LensService` looks a capability's grant up by the id it passes to
`allows`. `canRevealLinkEntities` and `canApproveLinkExpansions` read `linkgraph.reveal-entities` /
`linkgraph.approve-expansions` while their nodes are `linkgraph.reveal` / `linkgraph.approve`, so denying
either node changed nothing on screen (the server still gated both routes). The lens now reads the catalog ids,
the ones stored profiles already use. `access/access-lens-parity.spec.ts` pins the parity from both real sides
(the derived catalog, and the id each `can*` signal actually looks up): every lens capability reads its node's
id, every action node is read by the capability it names, and a saved lens profile denying a node turns that
capability off. Two named exception lists, each checked against the code so a stale entry fails:
- **Node with no screen:** `exchange.offer-signals` (`canOfferSignals`). No screen offers a Signal type
  (`POST /exchange/signal-offers` is API-only), but a **role** profile denying the node still strips the
  capability server-side (`AccessGrants`), so the node stays.
- **Signal with no node (backend-only gates no Access profile can deny yet):** `canBuildLinkIndex`,
  `canUploadData`, `canShareInvestigation`. So the 2026-07-26 claim above that every route capability has both
  is not true for these three.

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
  is the only signal. Without that clause Basic mode's Business lens starts showing Connections and
  Requirements affordances and the "View as" preview stops meaning anything.
- **`canTriageRequirements` is identity by operator call** — it is the `business` seed's *only* capability, so
  lens-scoping it revoked the single grant that role has. Consequence, accepted deliberately:
  **"Business lens ⇒ read-only" is no longer a true statement about the product.**

**X-Actor removed (2026-11-17, MoSCoW S4, 4.0.0 — breaking).** The header carries no identity on any edition:
`RequestAttrs.actor` is the Subject's id, else `agent:<session>` for an `X-Agent-Session` call, else `appUser`. The
SEC-7a spoof guard moved out of the Authenticator branch of `ControlApi.authenticate`, so a request still sending a
non-blank `X-Actor` is refused `403 PERMISSION_DENIED` on every edition, Basic included, and the refusal
is audited under the request's actor, never the claimed name. Refused, not ignored: a stale client fails loudly
instead of having its writes audited under `appUser`, and the console never sent the header. Release note:
[plan-api-stability](../api/plan-api-stability.md) §Release notes. The capability spec [`okf/product/module/access/plan-security.md`](plan-security.md) is the front door for what was required, left and refused; this page stays the mechanism.

`package.ps1 -Edition Advanced` **shipped 2026-07-25** — a superset of Standard (both the `security` and
`policy` jars are bundled), with `serve.sh`/`serve.bat` deriving the edition from bundle contents. No
runtime flag was added: `inspecto-policy` is discovered solely via its `AccessDecider` service file, so the
classpath entry is the switch. Detail in [spec-editions.md](../../layer/platform/spec-editions.md).

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, authored-Pipeline CRUD): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/spec-config-safety.md). It is an ops decision about whether this instance may
write — not authentication.
