---
type: Concept
title: Security module (inspecto-security)
description: Standard-edition OIDC resource server behind the Authenticator/Subject/TokenRelay SPIs — Nimbus JWKS, RoleMapper, vendor-neutral OIDC token relay; reactor-gated behind the edition-standard profile.
resource: inspecto-security/
tags: [module, security, oidc, editions, standard, spi]
timestamp: 2026-07-07T00:00:00Z
---

# Security module (inspecto-security)

A profile-gated Maven module (`inspecto-security`, in the `edition-standard`/`edition-enterprise` profiles only — `pom.xml:76`, `:115`), shipped W6 (2026-07-06). It supplies the
**Standard/Enterprise** auth implementation; the common core stays auth-free.

* **SPI seam (in core)** — `com.gamma.control.Authenticator` / `Subject` (id + capabilities) /
  `TokenRelay`, discovered via `ServiceLoader`. **No-op wins**: with no provider on the classpath the
  Personal edition is byte-for-byte unchanged.
* **Contents** — `OidcAuthenticator` (OIDC resource server on Nimbus JOSE+JWT / JWKS),
  `RoleMapper` (claims → Roles → Capabilities; ⚠ **since RBAC R1 the role→grant table is NOT hardcoded here** — it resolves per request from `Roles.effective`, the bound space's authored `roles.toon` over the shipped seed, fail-closed on an unknown role, plus SEC-7d data scopes via `dataScopesFor` — `RoleMapper.java:14-27`), `OidcTokenRelay`, `FileKeystoreSecretsProvider` (the SEC-07 `${FILE}`/`${KEYSTORE}` secret schemes).
* **Reactor gating** — the module only builds under the `edition-standard` Maven profile; the default
  `mvn -o clean test` never compiles it (verify with `-Pedition-standard`: **34 tests** in this module — `OidcAuthenticatorTest` 24 + `OidcTokenRelayTest` 7 + `FileKeystoreSecretsProviderTest` 3 — inside a **31-module / 4106-test** reactor, measured 2026-09-08; the bare default reactor is 23 / 3777).
* **Around it (in core, W6/W6d)** — the AuthN gate + per-route capability checks
  (`UNAUTHENTICATED`/`PERMISSION_DENIED`), HTTPS via the pure-JDK `HttpsServer`, and the BFF routes
  `POST /auth/exchange|refresh|logout` (refresh token only in the httpOnly `inspecto_rt` cookie;
  CSRF = SameSite=Strict + Origin check).
* **Live caveats** — (1) **no boot without OIDC config**: `ControlApi` resolves the `Authenticator`
  eagerly at startup, and `OidcAuthenticator` calls `requireProperty` for both
  `-Dauth.oidc.issuer` and `-Dauth.oidc.jwksUri`, so a Standard/Enterprise bundle missing either
  **fails to boot** rather than accepting every request (`OidcAuthenticator.java:29-32,61,166`);
  `-Dauth.oidc.audience` is warn-only by decision (`:96-101`). (2) **SEC-SIDECAR-BOOT-1** — this
  module must ship **shaded** (`inspecto-security-*-sidecar.jar`): the plain 16 KB jar carries no
  `com/nimbusds` classes and every Standard/Enterprise bundle failed to boot until 2026-09-07; see
  [build & test](../build-run/build-test.md).
* **jlink** — ✅ **PKG-4 closed 2026-07-07**: the embedded runtime's module set is verified sufficient
  for Nimbus JOSE+JWT (`inspecto/package.ps1:19-25`; `REQUIREMENTS.md:254`, `:398`), so a Standard
  bundle may embed it. `-NoRuntime` is now only an option (the target must then supply Java 24+), used
  by the CI release and while the jlink *step* is re-proven on a host with no stale JVM holding
  `runtime/bin/server/jvm.dll` ([build & test](../build-run/build-test.md)).

Edition context: [editions model](../editions/editions-model.md) ·
[auth & security](../editions/auth-security.md) · consumer flow: [`/api/v1`](../control-plane/api-v1.md).
