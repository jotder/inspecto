---
type: Capability
title: Security (SEC) — Purpose, scope and requirements of record
description: Part 1 of 4 of Security (SEC): Purpose & scope; Requirements of record
tags: [sec, capability, security, oidc, rbac, abac, capabilities, roles, access-policy, secrets, https, write-gate]
timestamp: 2026-09-08T00:00:00Z
item: module.access
audience: internal
status: plan
verified: 2026-11-20 last-edit
code: platform/inspecto-access
---

# Security (SEC) — Purpose, scope and requirements of record

Part 1 of 4 of [Security (SEC)](plan-security.md) · next: [Specification](plan-security-specification.md).

## 1. Purpose & scope

SEC is everything that decides **whether a request is allowed to happen**: who the caller is, which named
capabilities they hold, whether a policy denies the action or hides the row, whether the secret a config
references may be resolved, whether the transport is encrypted, and — separately from all of those — whether
this instance is permitted to write at all. Its defining property is that **the core carries no
authentication code**: Personal is auth-free by construction, and everything above is added by modules the
build either bundles or does not.

**In scope:** the `Authenticator` / `Subject` / `TokenRelay` / `AccessDecider` / `SecretsProvider` SPIs and
the request-gating order in `ControlApi`; the `inspecto-oidc` module (OIDC resource server, role
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
| `SEC-3` | `inspecto-oidc`: OIDC resource server (Nimbus JWKS), `RoleMapper`, **`OidcTokenRelay`** — reactor-gated behind `edition-standard` | Must (S) | ✅ SHIPPED | S/E |
| `SEC-4` | HTTPS via pure-JDK `HttpsServer` + keystore | Must (S) | ✅ SHIPPED **and TESTED 2026-09-09** — `ControlApiHttpsTest` proves TLS 1.3 is served against a keytool-generated PKCS12 (verified against that same store as its trust store, ⛔ not a trust-all manager), that cleartext no longer works on the port, and that a bad keystore **fails closed**. 🔴 Writing it found and fixed a real defect: a wrong password lost the diagnostic naming `-Dhttps.keystore`, because `KeyStore.load` signals it as a plain `IOException` and the catch covered only `GeneralSecurityException` | S/E |
| `SEC-5` | BFF session: the refresh token never reaches the browser (httpOnly cookie, `SameSite=Strict`, `Origin` CSRF) | Must (S) | ✅ SHIPPED (W6d) | S/E |
| `SEC-6` | UI OIDC login driven by `bootstrap.features.authMode`; Personal = no-op | Must (S) | ✅ SHIPPED (W6d/W7) — ⚠ the coupling is a launcher convention (§2 note 2) | S/E |
| `SEC-7` | RBAC/ABAC hardening: reject `X-Actor` on Standard; per-resource `permissions[]`; `canTriageRequirements` route; data-scoped grants | Must (S) | ✅ SHIPPED (2026-07-07/08) — ⚠ `permissions[]` is **emitted and never read** (§3.14). Design of record: §§3.3, 3.6, 3.10 and 3.14 of this spec. *(Provenance: `docs/archived-documents/plans-archive/resource-permissions-design.md` and `rbac-groundwork.md`, not maintained.)* | S/E |
| `SEC-8` | Secrets: env / file / keystore; Vault option future | Should | 🟡 **PARTIAL by design** — `${ENV}`/`${SYS}` everywhere, `${FILE}`/`${KEYSTORE}` S/E (2026-09-06); Vault / KMS **unbuilt, Enterprise-only, gated on a client policy** (§5) | All · S/E · E |
| `SEC-9` | Write-root gate (`-Dassist.write.root` → 503 fail-closed), separate from auth, always on | Must | ✅ SHIPPED | All |

**Corrections this table makes to its predecessor**, each verified against source:

- **`SEC-3` named "Keycloak token relay".** The class is `OidcTokenRelay`
  (`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcTokenRelay.java`) — D15 renamed it on 2026-07-25
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
| `SEC-09` note | Personal events carry `actor=anonymous` | The unauthenticated default actor is **`appUser`** (`RequestAttrs.actor`, `spi/inspecto-auth-spi/src/main/java/com/gamma/spi/auth/RequestAttrs.java`; `ApiContext.actor` delegates to it): an authenticated `Subject.id()` first, else `agent:<session>`, else the `X-Actor` header, else `appUser`. No `anonymous` string exists in the tree |
| `SEC-11` note | "client-migration-gated" | The API-v1 sunset apparatus was deleted 2026-07-25; `BACKLOG.md` §2 restated the gate 2026-09-07 as **the next MAJOR tag** |
| `SEC-12` (end-session redirect) | `— / 🔲 / 🔲`, "nobody has asked" | **Built.** The SPA implements RP-Initiated Logout (`SessionService.logout()`, 2026-07-26) and reads `boot.auth?.endSessionUrl ?? environment.oidc.endSessionUrl`. Since 2026-10-10 the server publishes the `auth` block: `BootstrapRoutes` puts `TokenRelay.bootstrapAuth()` under `auth`, and `OidcTokenRelay` fills it with `authorizeUrl`, `clientId`, `scopes` and `endSessionUrl` whenever an authorize URL is configured or discovered (`OidcConfig`). Since 2026-10-13 `POST /auth/logout` answers the end-session URL **with** `id_token_hint`, built from the HttpOnly `inspecto_idt` cookie (`AuthRoutes.ID_COOKIE`, `OidcTokenRelay.endSessionUrl`); the SPA falls back to the hint-less `client_id` redirect when the server sends none. So it is configured at deployment, not at UI build time |

**Two standing notes no status token can carry:**

1. 🔴 **A Standard or Enterprise bundle exits at startup without OIDC configuration.** `ControlApi` calls
   `Authenticators.active()` during construction and `SpiSlot` runs `ServiceLoader` regardless of
   `-Dauth.mode`; the mere presence of `inspecto-oidc.jar` makes `OidcAuthenticator`'s constructor
   mandatory, and it fails closed without `-Dauth.oidc.jwksUri` + `-Dauth.oidc.issuer`. The message names
   the property; the *timing* surprises. There is **no** "module present, auth off" mode
   (`SEC-SIDECAR-BOOT-1`, 2026-09-07). *A requirement is not delivered until it is reachable* — and this one
   is reachable only with its environment.
2. ⚠ **The UI's login mode and the server's enforcement are coupled by convention, not derivation.**
   `/bootstrap.features.authMode` is `System.getProperty("auth.mode", "none")`
   (`BootstrapRoutes.features`), while enforcement is decided by whether an `Authenticator` registered. The
   bundle's `serve.sh`/`serve.bat` set `-Dauth.mode=oidc` when they detect the security sidecar
   (the `$serveShContent` / `$serveBatContent` here-strings in `inspecto/package.ps1`), so the shipped bundle is consistent. A hand-launched jar with the
   sidecar on the classpath and no flag leaves the SPA in no-login mode against a server answering 401 on
   every route. `UNTRACKED` — filed in §5.
   🔴 **`serve.bat` did NOT actually do this until 2026-09-11 (`SERVEBAT-OPTS-1`).** The Windows branch was a
   parenthesised `if exist inspecto-oidc.jar ( … )` block containing six `set "OPTS=%OPTS% …"`
   statements — and **cmd.exe expands every `%OPTS%` in a block once, when the block is PARSED**, so all six
   expanded to the value `OPTS` held *before* the block and only the last one executed survived. Measured on
   an Enterprise bundle with all four `AUTH_OIDC_*` env vars set, the emitted flags were exactly
   `--enable-native-access=ALL-UNNAMED -Dcontrol.port=8080 -Dspaces.root=spaces -Dauth.oidc.clientId=abc`:
   **`-Dauth.mode=oidc` and three of the four OIDC flags were gone**, while the script still printed
   `edition: Enterprise`. So every Windows Standard/Enterprise bundle booted with the SPA in no-login mode —
   the §5 hand-launch hazard above, shipped by default on one platform. Fixed by flattening the branch to
   single-line `if`s (the form the adjacent Postgres lines already used and documented). ⛔ The fix must not
   be "tidied" back into a block, and ⛔ `setlocal EnableDelayedExpansion` is **not** the alternative: these
   values carry client secrets and keystore passwords, and delayed expansion eats `!` inside them.
   ✅ **Guarded since 2026-09-14** (`LAUNCHER-GUARD-1`). `tools/check-launchers.mjs` extracts the
   `$serveShContent` / `$serveBatContent` here-strings, writes each into a throwaway bundle of stub jars,
   puts a stub `java` first on PATH and **runs the launcher verbatim**, asserting the argv it produced for
   five edition scenarios — Personal (no `-Dauth.mode`, no `-Devents.backend`, and nothing starting
   `-Dauth.oidc.`), Standard with and without the `AUTH_OIDC_*` environment, Enterprise, and operator
   `INSPECTO_JAVA_OPTS` appended last. The scenario that carries all five OIDC variables at once is the
   direct regression test for this defect. It also asserts the client secret reaches the JVM as the
   `${ENV:…}` **reference**, never the value. ⚠ Nothing rewrites the launcher — the stub is on PATH — so
   the `exec` / launch line runs as shipped.
