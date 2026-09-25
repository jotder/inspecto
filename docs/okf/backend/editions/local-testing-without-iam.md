---
type: Concept
title: Local feature testing without a real IAM or Postgres
description: Two build/run paths — Demo User sign-in (the offline demo build, no IAM, no Postgres) for local evaluation and feature testing, and the real Professional/Enterprise release path with a genuine OIDC provider and Postgres.
---

# Local feature testing without a real IAM or Postgres

Two separate goals, two separate procedures. Never conflate them. Path A exists so a developer or an
internal evaluator can exercise Professional/Enterprise route modules (geo-link, exchange, metrics,
events, ops, notify-channels, backup, the Enterprise ABAC engine) without standing up an IdP or a
Postgres instance; it must never reach a real deployment.

> **One mock path, not two (decision D-3, 2026-09-25).** Path A used to be a hand-built permit-all
> authenticator under `tools/local-only-auth-bypass/` (retired — the directory was deleted) that
> signed every request in as one fixed user with every capability. Demo User sign-in replaced it:
> it signs in named users with **real roles**, so role-based access, owner-only views and the
> audit actor behave as they would under OIDC.

## Default: build/run defaults to Professional

Per standing operator instruction, build and package the app on the **Professional** edition unless
someone explicitly asks for Personal or Enterprise. (The Demo User build below is the exception: it is
Enterprise-only by construction.)

## Preview edition

**`Preview`** (added 2026-09-21, `-Pedition-preview` / `package.ps1 -Edition Preview`) bundles **every**
optional module unconditionally — see [`EDITIONS.md` §Preview](../../../EDITIONS.md#preview--not-a-customer-facing-tier).
It is not an auth bypass: it ships the real `inspecto-security.jar`/`inspecto-policy.jar` and fails
closed exactly like Enterprise without a real IdP. `package.ps1 -DemoAuth` accepts only
`-Edition Enterprise`, so there is no Preview demo build.

## Path A — Demo User sign-in, no IAM, no Postgres (offline demo build)

**Goal:** evaluate or feature-test Enterprise capabilities on one machine with zero external
dependencies, signed in as named [**Demo Users**](../../../GLOSSARY.md#1-a-personas--surfaces) with
real roles. As built by DEMO-AUTH-1 (`9d0eb5da3` module + SPA, `d9911b793` packaging).

### What it is

- **Module `inspecto-demo-auth`** — a real Maven module in the default reactor (root `pom.xml`) that
  **no edition bundles**. It registers two SPIs through `META-INF/services`:
  - `DemoTokenRelay` (`TokenRelay`) sits behind the existing `/auth/exchange`, `/auth/refresh` and
    `/auth/logout` routes, so it adds **no route**. `exchangeCode` accepts `code = "demo:<Demo User id>"`
    for a known Demo User and mints an access token (15 min) and a refresh token (8 h). A token is
    `base64url(kind|userId|expiry).base64url(HMAC-SHA256)` under a random secret made once per JVM
    (`DemoTokens`): a restart signs everyone out, and the kind (`a`/`r`) is signed, so a refresh
    token is never accepted as a Bearer. `revoke` is the interface's default no-op. No password
    (decision D-4).
  - `DemoAuthenticator` (`Authenticator`) verifies the Bearer, reads the request's bound Space through
    `Roles.configRoot(ex)` (the read half of the attribute `ControlApi` stamps before
    authentication; falls back to `-Dassist.write.root` in legacy single-root mode), looks the Demo
    User up in that Space, and resolves its roles against the Space's effective role table
    (`Roles.effective`). It then removes Access Profile denials (`AccessGrants.deniedCapabilities`)
    and publishes the held roles (`ComponentAccess.heldRoles`) — the same steps the OIDC path takes.
    The `Subject` id is the Demo User id, so it is the audit actor and the owner in owner-only
    checks. A forged, expired or wrong-kind token, or a Demo User unknown to the bound Space, gets
    no Subject (401).
- **`TokenRelay.bootstrapAuth()`** — a core default method (empty by default). `/bootstrap` emits a
  non-empty result as `auth` (`BootstrapRoutes`). The demo relay returns
  `{mock: true, demoUsers: [{id, displayName, title}]}` — never roles or capabilities, because it is
  served before sign-in.
- **`-Dauth.mode=demo`** — `/bootstrap` reports it as `features.authMode`; the SPA's `SessionService`
  treats `demo` like `oidc` (sign-in required, same session flow). The sign-in page
  (`sign-in.component.ts`) renders a Demo User picker under a *Not secure, local only* notice in
  place of the SSO button; picking one sends `code=demo:<id>` through the unchanged
  `/auth/callback` → `POST /auth/exchange` sequence. Sign out routes in-app, so the tester can
  switch Demo User.

### Demo Users live in the Space

`<space>/config/demo-users.toon`, read by `DemoUsers`:

```toon
users[2]{id,displayName,title,roles}:
  ra.analyst,Demo RA Analyst,Revenue Assurance analyst,operations
  admin,Demo Admin,Platform administrator,admin;super
```

- `roles` is `;`-separated and names roles of **that Space's** role table (`roles.toon` over
  `Roles.SEED`); names are lower-cased, and a role the table does not define grants nothing.
  `displayName` defaults to the id, `title` to empty; a row without an `id` makes the file unreadable.
- A missing file means the Space has no Demo Users. The file is re-read on every call, so edits apply
  while the server runs.
- The picker lists the **union across every Space** under `-Dspaces.root` (or the single
  `-Dassist.write.root`). The same id defined **differently** in two Spaces is refused
  (`IllegalStateException` when the list is built — at `/bootstrap` or sign-in, not at boot); the
  same id defined identically is allowed. Roles still resolve per request against the bound Space,
  so one Demo User can hold different capabilities in different Spaces.
- No committed Space ships a `demo-users.toon`; a Space intended for a demo must author one.

### Guardrails

- **Loopback or refuse to load.** Both SPI constructors call `LoopbackOnly.require()`: unless
  `-Dcontrol.bind` resolves to a loopback address, they throw `IllegalStateException` naming the
  property. Unset counts as a refusal (it means every interface). The `Authenticator` slot is
  fail-closed and `ControlApi`'s constructor resolves it eagerly, so a demo build bound anywhere else
  **fails to boot** rather than serving an open sign-in.
- **Never in a real bundle.** The jar is outside the jar enumerations `tools/check-sbom-modules.mjs`
  parses, has no `tools/bundle-modules.mjs` entry, and only `package.ps1 -DemoAuth` stages it.
  **`tools/check-demo-auth-isolation.mjs`** (CI, beside the SBOM guard) holds that: it fails if
  `inspecto-demo-auth` enters any edition's module set (Preview included), is named in `package.ps1`
  outside an `if ($DemoAuth)` block (the `$modules` list, staging steps, boot-smoke `$cp`, `serve.*`),
  is a dependency of any other module or a module of an edition profile, or is named — or
  `-DemoAuth` passed — by any other launcher, script or workflow. Its fixture test
  `tools/check-demo-auth-isolation.test.mjs` plants each violation and requires red.
- **Not with `inspecto-security`.** `-DemoAuth` removes `inspecto-security.jar` (the guard above
  fails if that removal goes, or if the demo launcher's classpath names the security jar). At runtime
  the fail-closed `Authenticator` slot (`SpiSlot`) **refuses to boot when more than one provider is
  registered**, naming both, before constructing either — so a hand-assembled classpath carrying both
  jars fails loudly instead of letting classpath order choose (`SpiSlotFailClosedTest`).

### How to run it

```powershell
$env:JAVA_HOME = "C:\sandbox\.graalvm-cache\jdk-27-win"   # the JDK-27 toolchain
pwsh -File inspecto/package.ps1 -Edition Enterprise -DemoAuth
```

`-DemoAuth` (`inspecto/package.ps1`) requires `-Edition Enterprise` and:

- assembles into `inspecto-demo/` (never `inspecto-deploy/`) and zips as `inspecto-demo-<platform>.zip`;
- builds `inspecto-demo-auth` (unless `-NoBuild`), then, **after** the SBOM step (the SBOM describes
  the Enterprise set), deletes `inspecto-security.jar`, copies in `inspecto-demo-auth.jar`, and checks
  the jar carries both SPI registrations;
- deletes `serve.*`, `Dockerfile`, `.dockerignore` and the service installers (without the security
  jar they would boot an auth-free server on every interface);
- writes `serve-demo.bat` / `serve-demo.sh` with `-Dcontrol.bind=127.0.0.1 -Dauth.mode=demo
  -Dobjects.backend=db -Devents.backend=parquet -Dspaces.root=%SPACES_ROOT%` (default `spaces`,
  port from `PORT`, default 8080), and `DEMO-BUILD.txt` saying *internal evaluation only*;
- adds the demo jar and flags to its boot smoke.

Then drop a Space folder that has a `config/demo-users.toon` into `inspecto-demo\spaces\`, seed its
inbox if needed (below), run `serve-demo.bat` (or `./serve-demo.sh`), open `http://127.0.0.1:8080`
and pick a Demo User.

> ⚠ **The `-DemoAuth` package run has NOT been verified end to end** (as of 2026-09-25). Run 1
> reached the SBOM step (the jar swap worked; an ordering bug was fixed); run 2 stopped at `npm ci`
> on a locked `esbuild.exe`. Nobody has yet booted a packaged demo build and signed in. What *is*
> verified is the module itself: `DemoAuthHttpTest` (real HTTP) covers the picker, exchange, a
> per-user Subject and audit actor, a capability 403, a forged token, the loopback refusal and
> token kinds/expiry.

### Seed a space's pipeline inbox (first run only)

The packaged bundle ships **no Spaces** (only `spaces/_templates`, since 2026-09-25): copy the Space
folder you want to test — e.g. `spaces/demo` from the repo, with its `data/samples/` (the pristine,
committed source feeds) — into the bundle's `spaces/`. Pipeline output (`data/<pipeline>/database/*.parquet`
etc.) is runtime state, generated by actually running the pipeline; don't copy it along. A saved Link Analysis view (or any query) against a dataset with an empty inbox fails with
`IO Error: No files found that match the pattern ...*.parquet` — this is not a bug, it's an unseeded
space.

Each space with sample data ships a `seed-inbox.ps1`/`.sh` under `data/samples/` that copies the
samples into `data/inbox/<pipeline>/` for the engine to pick up on its next poll cycle (or via a
manual pipeline trigger):

```powershell
pwsh -File inspecto-demo\spaces\demo\data\samples\seed-inbox.ps1
```

The already-running `CollectorService` polls every 60s and ingests automatically — no restart needed.

### Verify

The boot log names the active authenticator: `ControlApi started on port 8080 (Professional edition —
authentication enforced via com.gamma.demoauth.DemoAuthenticator)`. `/bootstrap` carries
`auth.mock: true` and the Demo User list. `/bootstrap` also reports `edition: professional` — its
edition label is "anything but `auth.mode=none`", not the jar set.

## Path B — The real release: Professional/Enterprise with a genuine IAM and Postgres

**Goal:** ship or run a bundle that actually enforces authentication.

### 1. Build + package

```powershell
$env:JAVA_HOME = "C:\sandbox\.graalvm-cache\jdk-27-win"
mvn -o clean package -DskipTests -Pedition-enterprise -B   # superset: builds security AND policy
pwsh -File inspecto/package.ps1 -Edition Enterprise        # or -Edition Professional for Standard-equivalent
```

`serve.bat`/`serve.sh` auto-detect the edition from which jars are in the bundle
(`inspecto-security.jar` present ⇒ Professional; `+ inspecto-policy.jar` ⇒ Enterprise) — no separate
runtime flag exists for this.

### 2. Point it at a real OIDC provider

`serve.bat` reads `AUTH_OIDC_ISSUER` / `AUTH_OIDC_JWKS_URI` / `AUTH_OIDC_AUDIENCE` /
`AUTH_OIDC_CLIENT_ID` from environment variables. Two flags it does **not** wire from env —
`tokenEndpoint` and `rolesClaim` — must ride via `INSPECTO_JAVA_OPTS` as raw `-D` flags.

Worked example against a **local WSO2 IS in Docker** (fully offline — everything on `localhost`, no
internet needed once JWKS is fetched once and cached):

```powershell
$env:AUTH_OIDC_ISSUER = "https://localhost:9443/oauth2/token"
$env:AUTH_OIDC_JWKS_URI = "https://localhost:9443/oauth2/jwks"
$env:AUTH_OIDC_AUDIENCE = "inspecto_spa_client"          # WSO2: the CLIENT ID, not a separate API identifier
$env:AUTH_OIDC_CLIENT_ID = "inspecto_spa_client"
$env:INSPECTO_JAVA_OPTS = "-Dauth.oidc.tokenEndpoint=https://localhost:9443/oauth2/token -Dauth.oidc.rolesClaim=groups"
.\serve.bat
```

WSO2-specific gotchas (proven against a real instance, `docs/api/deployment/README.md`):
- Client ID must match `[a-zA-Z0-9_]{15,30}` — no hyphens.
- The application must be switched to `ext_token_type: JWT` or WSO2 issues opaque tokens and
  `OidcAuthenticator` 401s everything with no explanation.
- Roles land under the `groups` claim, not `roles`/`realm_access.roles` (that's Keycloak).
- A role only reaches the **access token** (not just id_token/userinfo) if the WSO2 application lists
  it under **accessTokenAttributes**, not just requestedClaims.
- Default WSO2 IS superadmin login: `admin` / `admin`.

**TLS**: the packaged bundle ships its **own embedded JVM** at `inspecto-deploy/runtime/`, separate
from the build toolchain JDK. A self-signed dev IdP's cert must be imported into *that* JVM's
truststore, not the system one:

```powershell
openssl s_client -connect localhost:9443 -servername localhost </dev/null | openssl x509 > wso2is.crt
inspecto-deploy\runtime\bin\keytool.exe -importcert -alias wso2is -file wso2is.crt `
    -keystore inspecto-deploy\runtime\lib\security\cacerts -storepass changeit -noprompt
```

### 3. Point it at real Postgres (optional — only for `OPS-02` operational stores)

```powershell
$env:INSPECTO_DB_URL = "jdbc:postgresql://<host>:5432/<db>"
$env:INSPECTO_DB_USER = "<user>"
$env:INSPECTO_DB_PASSWORD = "<password>"
```

`serve.bat` only turns on `-Dinspecto.db=postgres` when `INSPECTO_DB_URL` is set — the URL is the
switch, never the driver jar's mere presence. Leaving these three unset keeps DuckDB/local disk, same
as Path A, with zero other change.

### 4. Verify

```powershell
curl http://localhost:8080/health   # 200
```
then confirm the boot log names the real authenticator: `authentication enforced via
com.gamma.security.OidcAuthenticator` (not `com.gamma.demoauth.DemoAuthenticator`) — that string is the
tell for "is this actually a real deployment or did Path A's demo jar end up on the classpath by mistake."

## Comparison

| | Path A (Demo User, local) | Path B (real release) |
|---|---|---|
| Authenticator | `com.gamma.demoauth.DemoAuthenticator` (Demo Users with real roles, `inspecto-demo-auth`) | `com.gamma.security.OidcAuthenticator` (real IdP) |
| Operational DB | DuckDB (default, unset `-Dinspecto.db`) | DuckDB or Postgres (`INSPECTO_DB_URL` set) |
| Needs a running IdP? | No | Yes |
| Safe to expose beyond localhost? | **Never** (refuses to load unless bound to loopback) | Yes, per the edition's transport/TLS story |
| Classpath includes `inspecto-security.jar`? | No (deliberately) | Yes |
| Boot log tell | `...DemoAuthenticator` | `...OidcAuthenticator` |
