---
type: Guide
title: Inspecto LA - administration (LA level, Investigations add-on, masking default, real OIDC sign-in)
description: How an administrator runs Link Analysis at the Explorer or the Investigations level, what decides the masking default, and the runbook that proves a real OIDC sign-in end to end. The stakeholder demo is in admin-guide-demo.
tags: [module, la-core, link-analysis, editions, add-on, masking, oidc, admin-guide]
timestamp: 2026-11-15T00:00:00Z
---

# Inspecto LA - administration

- [1. The Link Analysis level, the Investigations add-on and the masking default](#1-the-link-analysis-level-the-investigations-add-on-and-the-masking-default)
- [2. Real OIDC sign-in runbook](#2-real-oidc-sign-in-runbook-la-app-local-wso2-identity-server)
- Stakeholder demo runbook and Q&A: [admin-guide-demo](admin-guide-demo.md). Limits and Space settings:
  [architecture](architecture-la-core.md) §5 and §9.

## 1. The Link Analysis level, the Investigations add-on and the masking default

*As built 2026-10-22 (`LA-ONBOARD-1` phase 1: OB-5, OB-6, OB-10). Design and seams:
[architecture](architecture-la-core.md) §8 "Trust vs level"; placement: [`EDITIONS.md`](../../../../EDITIONS.md).*

Three things are decided separately:

| Question | Decided by | Where |
|---|---|---|
| Who the user is: sign-in, roles, enforced masking | The **edition** (Basic: no sign-in; Standard and above: sign-in) | The bundle you install |
| Which Link Analysis screens exist | The **LA level**: *Explorer*, or *Investigations* when the installation holds the Investigations add-on | The install setting below |
| Where data comes from | The Space (`LA-ONBOARD-1` phase 2) | Space settings |

### 1.1 The Investigations add-on

- **Order Code** `INS-AO-LA-INVESTIGATIONS` (Product Map `addon.laInvestigations`), billable, **included by default on
  Standard, Advanced and Preview**. Basic never has it.
- **Remove it** from an installation by starting the server with the JVM setting `-Daddons.laInvestigations=false`, for
  example `set "INSPECTO_JAVA_OPTS=-Daddons.laInvestigations=false"` in the `.bat` that calls `serve.bat` (any value other
  than `true` removes it). Restart to change it.
- **What users see without it:** Link Analysis at the Explorer level - the graph, Grow, the browser algorithms, Entity Lists
  and saved views. Investigation actions stay on screen, **locked**, with one line saying why ("Needs the Investigations
  add-on, which this installation does not hold."). Sign-in, roles and enforced masking are unchanged.
- **What the API answers without it:** every Investigation route answers `503 CAPABILITY_UNAVAILABLE` naming the add-on and
  its Order Code; `GET /api/v1/bootstrap` reports `laLevel: "explorer"` and `featureReasons.investigations`.
- ⚠ Until a signed entitlement exists, whoever runs the server can claim the add-on (the setting is a plain JVM flag).

Check an installation: `GET /api/v1/bootstrap` → `data.laLevel` is `investigations` or `explorer`, and `data.featureReasons`
lists what is withheld (empty when nothing is).

### 1.2 The masking default follows sign-in

A Space that does not state a `masking_mode` (Settings → Link Analysis) masks entity ids as `typed` wherever people sign in,
**with or without the Investigations add-on**, and masks nothing where nobody signs in (Basic). A mode the Space states always
wins. `GET /api/v1/settings/link-analysis` → `maskingModeInForce` shows the mode in force.

## 2. Real OIDC sign-in runbook (`la-app`, local WSO2 Identity Server)

*Status: run end to end 2026-10-13 against WSO2 IS 7.3.0 in Docker and an LA bundle (then built as Professional
`-Ui la-app`; today `-Product la`, `LA-APP-REAL-SIGNIN-1`). Rerun this after any change to `SessionService`,
`CallbackComponent`, `OidcTokenRelay` or `OidcAuthenticator`.*

Purpose: prove the whole sign-in against a real IAM - guard redirect, IdP login, callback, `/auth/exchange`, `groups` to
capabilities, refresh/resume, sign-out, expired token, a user with no mapped group. The demo bundle uses Demo User auth
(`-Distribution demo`) and proves none of the IdP half; this runbook is the other half. Background and WSO2 gotchas:
[`api/deployment/README.md`](../../../../api/deployment/README.md) "Proven against a real provider".

### 2.1 Prerequisites

* Docker with the `wso2/wso2is` container up on `https://localhost:9443` (self-signed certificate). The console login is the
  local throwaway superadmin (the image default; never commit it, never paste it into a doc).
* A **worktree** (never the main checkout), JDK 27 (`JAVA_HOME=C:\.jdks\jdk-27-win`), `pwsh` 7.
* Do **not** pass `-Distribution demo`: the build must be a plain Standard (or Advanced) bundle.

### 2.2 WSO2 side (once; through the console, or `/api/server/v1` and `/scim2`)

1. Application (standard-based, OIDC), public client, client id matching `[a-zA-Z0-9_]{15,30}` (no hyphens), e.g. `la_app_local_signin_1`.
2. Grants `authorization_code` + `refresh_token`; **PKCE mandatory**, plain disabled.
3. Callback URLs, as one regexp: `regexp=(http://localhost:8098/auth/callback|http://localhost:8098/sign-in)`.
   The `/sign-in` entry is the RP-initiated-logout return (`post_logout_redirect_uri`); WSO2 has no separate field for it.
4. Access token: **type JWT**; **accessTokenAttributes = `groups`, `given_name`, `family_name`**. A claim missing here is missing
   from the access token the API validates: no `groups` means zero capabilities, no name claims means the user menu shows the
   raw subject UUID ("Signed in as d13b9996-...").
5. Requested claims on the app: `groups`, `username`, `givenname`, `lastname`.
6. Groups named exactly as the Space's `config/roles.toon` roles (the `la-showcase` template: `fraud-analyst`, `la-manager`,
   `la-viewer`, `la-settings`, plus the seeded `admin`), one user per persona (analyst, manager, viewer, admin = `admin` +
   `fraud-analyst` + `la-settings`) and **one user in no group**. Generate the passwords and keep them in a local ignored file.

### 2.3 Build and stage

```powershell
pwsh -NoProfile -File inspecto\package.ps1 -Edition Standard -Product la -NoBakeDemo   # 10-25 min; retry on a font-fetch failure
```

Output: `inspecto-deploy\` at the worktree root. Then:

1. Trust the IdP in the **bundle's** JVM, not the system one: `inspecto-deploy\runtime\bin\keytool.exe -importcert -alias wso2is
   -file wso2is.crt -keystore inspecto-deploy\runtime\lib\security\cacerts -storepass changeit -noprompt` (`openssl s_client
   -connect localhost:9443 -servername localhost` piped to `openssl x509` writes `wso2is.crt`).
2. A Space carrying the roles: copy `inspecto-deploy\spaces\_templates\la-showcase` to `inspecto-deploy\spaces\la-showcase`.
3. A wrapper `inspecto-deploy\run-oidc.bat`. The flags must ride **inside a .bat** (an `INSPECTO_JAVA_OPTS` set in a preview
   launcher entry does not reach the JVM through its quoting). Note `%~dp0`: a bare `call serve.bat` fails because the current
   directory is not on cmd's search path.

   ```bat
   @echo off
   set "AUTH_OIDC_ISSUER=https://localhost:9443/oauth2/token"
   set "AUTH_OIDC_JWKS_URI=https://localhost:9443/oauth2/jwks"
   set "AUTH_OIDC_AUDIENCE=la_app_local_signin_1"
   set "AUTH_OIDC_CLIENT_ID=la_app_local_signin_1"
   set "INSPECTO_JAVA_OPTS=-Dauth.oidc.tokenEndpoint=https://localhost:9443/oauth2/token -Dauth.oidc.rolesClaim=groups -Dauth.oidc.authorizeUrl=https://localhost:9443/oauth2/authorize -Dauth.oidc.endSessionUrl=https://localhost:9443/oidc/logout"
   set "PORT=8098"
   call "%~dp0bin\serve.bat"
   ```

   `audience` is the client id itself on WSO2. `serve.bat` wires neither `tokenEndpoint` nor `rolesClaim` from env.
4. From `inspecto-deploy\` run `cmd /c .\run-oidc.bat` in the background (about 60 s with the template's ingest). Expect
   `ControlApi started on port 8098 (authentication enforced via com.gamma.oidc.OidcAuthenticator)`; an anonymous
   `GET /api/v1/bootstrap` carries `auth.authorizeUrl`, `auth.clientId`, `auth.endSessionUrl`, so no baked `environment.oidc` is needed.

### 2.4 Driving it, and the one limit

The Browser pane stops at Chrome's untrusted-certificate page for `https://localhost:9443` and treats it as a failed load
(`computer` and `read_page` refuse). Trusting the certificate is a browser security setting, so an agent run does not do it.
The SPA is therefore driven for everything **except the IdP page itself**:

1. Open `http://localhost:8098/` (the guard lands on `/sign-in`). In that tab register a `beforeunload` listener that copies
   `sessionStorage` into `localStorage['dbg.ss']`, then click *Sign in with SSO* (the redirect carries `response_type=code`,
   client id, `scope`, `state`, `nonce`, S256 `code_challenge`).
2. In a second tab on the same origin read `localStorage['dbg.ss']` (`inspecto.pkce.verifier|state|nonce`).
3. Do the IdP leg with a script: GET `/oauth2/authorize` with the same challenge, POST `/commonauth` with the user and the
   `sessionDataKey`, follow redirects (consent form if shown) to the `redirect_uri`. It yields `.../auth/callback?code=...&state=...`.
4. In the second tab copy the three keys into `sessionStorage` and navigate to that callback URL: the SPA's own
   `CallbackComponent` / `SessionService` exchange the code. (A person whose browser trusts the certificate skips 1-4.)

### 2.5 What to check (all passed 2026-10-13)

| Step | Pass |
|---|---|
| Guard | `/` goes to `/sign-in`; the SSO button is enabled |
| Callback | lands on `/`, the shell shows; `inspecto_rt` is an httpOnly cookie (`document.cookie` is empty) |
| Persona mapping | analyst: `canManageIncidents canWorkIncidents canRunLinkGraphAnalysis canBuildLinkIndex canAuthorAlertRules`; manager: `canManageIncidents canWorkIncidents canApproveLinkExpansions canOperateRuns`; viewer: none; admin: 18 (`canAdminister`, `canAuthorWorkbench`, `canRevealLinkEntities` among them) - the Demo Users' roles |
| Authenticated calls | `GET /spaces/la-showcase/inv/investigations` 200 with the token; 401 without, with garbage, and with a tampered signature |
| Resume | a full page reload re-enters without the IdP (`POST /auth/refresh` 200) |
| Expired token | set the app's access-token expiry to 60 s (restore 900 after): 70 s later the SPA's calls still return 200 (silently refreshed) |
| Dead refresh | after `POST /auth/logout`, the next navigation past expiry lands on `/sign-in` |
| Sign out | user menu *Sign out* clears `inspecto.session.resumable` and goes to the IdP end-session URL; a reload is on `/sign-in`, refresh 401 |
| No mapped group | signs in with zero capabilities and sees the read-only Link Analysis, the same as the viewer: authenticated, authorised for nothing gated |
| Exchange refusals | wrong nonce 401, wrong verifier 401, replayed code 401, omitted nonce 401 (was 200 before `OidcTokenRelay` refused an omitted nonce when the ID token carries one) |

### 2.6 Teardown

Stop the bundle's java (`netstat -ano | findstr :8098`) and delete `inspecto-deploy*` from the worktree. The WSO2 container is
the operator's long-lived one: leave it running and restore the app's token expiry to 900 s. Leave no password in the repo.
