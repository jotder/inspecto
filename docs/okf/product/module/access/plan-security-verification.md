---
type: Capability
title: Security (SEC) — Verification
description: Part 4 of 4 of Security (SEC): Verification
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
item: module.access
audience: internal
status: plan
verified: 2026-11-20 last-edit
code: platform/inspecto-access
---

# Security (SEC) — Verification

Part 4 of 4 of [Security (SEC)](plan-security.md) · previous: [Decisions, not built, refused and as-built pointers](plan-security-decisions.md).

## 8. Verification

### 8.1 Core gate tests — `inspecto/src/test/java/com/gamma/control/` (default reactor)

These run in every build because they exercise the *core* seams through `Authenticators.forTest(...)`:

| Class | Proves |
|---|---|
| `ControlApiAuthV1Test` | the AuthN gate: `401 UNAUTHENTICATED` without a Subject, `403` on `X-Actor` with or without an authenticator (`xActorHeaderIsRejectedOnBasic`), public paths open |
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
`-Pedition-advanced` (32 modules, 4126). `Roles.SEED`'s equality assertion was red on `master` for weeks
once because of exactly this.

| Module | Class | `@Test` | Proves |
|---|---|---|---|
| `inspecto-oidc` | `OidcAuthenticatorTest` | 24 | JWKS validation, roles claim → capabilities, `Roles.SEED` equality, profile-deny stripping, gateway path, A1 allowlist |
| `inspecto-oidc` | `OidcTokenRelayTest` | 7 | exchange / refresh / revoke against a fake token endpoint; the mandatory `tokenEndpoint` |
| `inspecto-secrets` | `FileKeystoreSecretsProviderTest` | 3 | `${FILE}` / `${KEYSTORE}` resolution, JCEKS |
| `inspecto-policy` | `PolicyEngineTest` | 12 | deny-overrides → allow → abstain; seeds overlaid by name; fail-closed on an unreadable doc; `explain` trace |
| `inspecto-policy` | `ControlApiPolicyEnforcementTest` | 8 | both PEPs over real HTTP: route `403`, row hidden, `access.denied` audited, space isolation engages only with a mapped claim |

`inspecto-oidc`'s count is **34**, measured 2026-09-08 (24 + 7 + 3); `auth-security.md` said 41 and is
corrected with this spec.

### 8.3 Grammar and secrets

| Class | Module | Proves |
|---|---|---|
| `ConditionsTest` | `inspecto-util` | the A2 grammar: operators, strict-Boolean truthiness, type-mismatch-is-false, offset-bearing errors |
| `SecretResolverTest` | `inspecto-acquire` | `${ENV}` / `${SYS}` / bare forms; the edition refusal when no provider serves `FILE`/`KEYSTORE` |

### 8.4 UI specs — `inspecto-ui/src/app/` (vitest)

Fourteen specs sit directly on this mechanism: `inspecto/auth/session.service.spec.ts` (boot, refresh, the
three-layer logout, `redirect()` seam), `lens.service.spec.ts` (identity vs lens-scoped, honor-system
mode), `auth.interceptor.spec.ts` (Bearer + 401 refresh), `inspecto/access/access-catalog.spec.ts`,
`layout/common/lens-switcher/lens-switcher.component.spec.ts`, `layout/common/user/user.component.spec.ts`
(sign-out), `modules/admin/access/access.component.spec.ts`, `access-roles.component.spec.ts`,
`access-policies.component.spec.ts`, `role-form.dialog.spec.ts`, `inspecto/auth/sign-in.a11y.spec.ts`
and `callback.a11y.spec.ts`, `core/navigation/navigation.service.spec.ts` (module-absent nav hiding),
`inspecto/components/connectivity-banner.component.spec.ts`.

### 8.5 SPI contracts (`META-INF/services`)

| File | Provider |
|---|---|
| `providers/inspecto-oidc/src/main/resources/META-INF/services/com.gamma.spi.auth.Authenticator` | `com.gamma.oidc.OidcAuthenticator` |
| `providers/inspecto-oidc/src/main/resources/META-INF/services/com.gamma.spi.auth.TokenRelay` | `com.gamma.oidc.OidcTokenRelay` |
| `providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider` | `com.gamma.secrets.FileKeystoreSecretsProvider` |
| `providers/inspecto-policy/src/main/resources/META-INF/services/com.gamma.spi.auth.AccessDecider` | `com.gamma.policy.PolicyEngine` |

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
| ✅ ~~**HTTPS is untested**~~ **CLOSED 2026-09-09** | `ControlApiHttpsTest` (5 tests; §5) |
| **No test runs against a real IdP in CI** | `OidcAgainstRealProviderTest` (WSO2 IS) and `OidcKeycloakAgainstRealProviderTest` run the production no-arg constructor against a real provider over real HTTP, but only when one is named (`-Dinspecto.test.oidc.issuer`, `-Dkeycloak.issuer`); without it they skip, so CI still drives fake JWKS / token endpoints only. The Keycloak / WSO2 blueprints in `docs/api/deployment/` are unexercised by CI |
| ✅ ~~**The Standard bundle's boot precondition has no test**~~ **tested since 2026-09-12** | `AuthenticatorDiscoveryFailClosedTest` (4 tests): a registered-but-misconfigured `inspecto-oidc` propagates out of SPI discovery instead of degrading to "no authenticator". `SEC-SIDECAR-BOOT-1` itself was found by a packaging smoke, by hand |
| **`authMode` ↔ enforcement coupling — the LAUNCHER half is now tested; the DERIVATION half is not** | ✅ **Half closed 2026-09-14** (`LAUNCHER-GUARD-1`, §2.2): `tools/check-launchers.mjs` executes both emitted launchers over stub jars and asserts that a bundle carrying the sidecar sets `-Dauth.mode=oidc` (and that a Personal bundle sets nothing of the kind). It is wired into `ci.yml` twice — Linux runs `serve.sh` and checks `serve.bat` statically, and a separate `launchers-windows` job **runs `serve.bat` for real**, because the defect it exists to catch is a cmd.exe parsing rule that no Linux runner can observe. ⚠ **This is the sidecar ⇒ flag half only.** The other half stands: `authMode` is *declared* by the launcher, not *derived* from `Authenticators.active()` (§5 row), so a **hand-launched** jar with the sidecar on the classpath and no flag still leaves the SPA in no-login mode against a server answering 401 — no guard over an emitted script can see that, and deriving the property is what would close it |
| **`permissions[]` has no consumer, and 2026-09-10 measurement says it should stay that way** — the field is `grants ∩ applicable`, so it is always a SUBSET of the session capabilities and the two cannot disagree; only four routes narrow it, three to a single static capability the client derives from the session set, and the fourth (requirements) narrows by a `status` the client already has on every row and **already gates on correctly** (`requirement-decision.dialog.ts`). Its only `GET` does not stamp at all. ⚠ The envelope also carries ONE array per response, so it cannot express per-row permissions for a collection. ⇒ **reserved, not scheduled** — reopen only for a set the client cannot compute AND reachable on a read (decided as `CLIENT-HALVES-1` (b); that row was deleted 2026-09-14, no board row tracks it) | §3.14 |
| ✅ ~~**Tamper-evidence of the audit log**~~ **BUILT** (`ASSURE-AUDIT-CHAIN-1`) | Each Space's audit events carry a hash chain (`AuditChain` in `spi/inspecto-audit-spi`, linked by `AuditChainLinker`), checked by `GET /audit/verify` and signed anchors (`/audit/anchors`, `canAdminister`); tested by `AuditChainTest` and `AuditVerifierTest`. Owned by `OPS`, recorded here because the row is `SEC-09` |
