# Plan — `4.x` public-PKCE auth (unblock the SEC-INCIDENT-1 rotation)

**Status:** **P0 + P1 SHIPPED 2026-07-25** (`481a68d5`, `89cb3cce` on `4.x`; propagated to master as
no-content `-s ours` merges `54443256`, `37c98c6a`). **Only P2 remains — and P2 is entirely operator
action** (deploy the `4.x` bundle, then rotate at the issuer). No code work is left on this plan.
⚠ **Do not archive this plan until rotation is confirmed** — P2 is the whole point of the exercise.
· **Opened:** 2026-07-25 · **Branch of record:** `4.x`
**Parent item:** [`../BACKLOG.md`](../BACKLOG.md) §5 SEC-INCIDENT-1 · **Concept home on ship:**
[`../okf/backend/editions/auth-security.md`](../okf/backend/editions/auth-security.md)

## 1. Why this exists

Rotating the five leaked OAuth secrets is the real remediation for SEC-INCIDENT-1, but **rotation breaks
every running `4.x` SPA**, because `4.x` authenticates with exactly those values. And re-issuing a secret
that still ships inside a browser bundle just reproduces the incident with fresh values — a public client
cannot hold a confidential secret. So `4.x` must stop needing a client secret *before* rotation, or
rotation stays permanently deferred.

This plan is therefore **the gate on the rotation**, not a cleanup nicety.

## 2. What is actually on `4.x` (verified 2026-07-25 against `291c86a1`)

Read via `git show 4.x:<path>` — no checkout needed.

**The live flow is a confidential-client `authorization_code` grant with no PKCE:**

| Step | Location (`4.x`) | Note |
|---|---|---|
| login redirect | `inspecto-ui/src/app/modules/commons/app-utils.ts:35-40` | builds `/authorize?response_type=code&…` — **no `code_challenge`** |
| code → token | `inspecto-ui/src/app/modules/auth/auth-service.ts:84-90` | sends `client_secret` in the body |
| refresh | `inspecto-ui/src/app/modules/auth/auth-service.ts:106-109` | called from `auth.interceptor.ts:92` |
| Basic credentials | `inspecto-ui/src/app/modules/auth/auth-service.ts:148-149` | `btoa(clientId:clientSecret)` |
| callback | `inspecto-ui/src/app/modules/auth/default-callback/default-callback.component.ts:29` | the one live `retrieveToken` caller |
| secret source | `inspecto-ui/src/app/modules/commons/app.properties.ts:12` | `appClientSecret` ← `environment.*` |

### Three findings that change the shape of the work

1. **⚠ BACKLOG §5 names the wrong file as the live path.** It points at
   `app/app-component.service.ts`. The live token exchange is actually **`modules/auth/auth-service.ts`**
   — a second, near-duplicate implementation the row does not mention. Both must be considered.

2. **✅ The hardcoded inline secret is in DEAD code — deletable today, no design change.**
   `app-component.service.ts:20-27` (`renewAccessToken`, holding the IAM secret as a literal, the worst
   single artifact in the incident) and `:61+` (`retrieveToken`) have **zero call sites** across
   `inspecto-ui/src` on `4.x`. `app.component.ts:73` injects `AppComponentService` but calls **nothing**
   on it; `page.manager.ts` uses only `saveNewAppPage`/`getAppPages`. So the literal can be removed from
   `4.x` **without touching the auth design at all** — this is a real, shippable partial remediation, and
   it contradicts the BACKLOG framing that the `4.x` fix "is a design change, not a deletion" (true only
   of the *live* path).

3. **✅ `master` already has a working PKCE implementation — this is a PORT, not a design exercise.**
   `inspecto-ui/src/app/inspecto/api/pkce.ts` (+ `pkce.spec.ts`) implements RFC 7636: `randomVerifier()`,
   `randomState()`, `challengeFromVerifier()` (S256 via Web Crypto). It has **zero imports** — pure
   platform API — so it ports to `4.x` verbatim.
   **But `session.service.ts` does NOT port:** it is built on master-only infra (`GET /bootstrap`, the
   `apiUrl('/api/v1/…')` helper, the edition switch, capability signals). `4.x` predates all of it. The
   port is `pkce.ts` + a retrofit of `4.x`'s own `auth-service.ts`, **not** a lift of the session layer.

## 3. Plan

Phased so the highest-value, lowest-risk piece can ship alone.

### P0 — delete the dead confidential-client code (no design change) — ✅ SHIPPED `481a68d5`

1. Remove `renewAccessToken()` and `retrieveToken()` from `app-component.service.ts`, plus the now-unused
   imports (`HttpParams`, `HttpHeaders`, `SecurityPrincipal`, `AppProperties`) they orphan.
   → **verify:** `git grep -nE "\.(renewAccessToken|retrieveToken)\(" -- inspecto-ui/src` returns only the
   `AuthService` call from `default-callback.component.ts`; `npm run build` clean.
2. Consider dropping the vestigial `AppComponentService` injection at `app.component.ts:73` if nothing
   else uses it. *(Check before deleting — this is adjacent code, so leave it if it is load-bearing.)*
   → **verify:** `npm run test:ci` and `npm run build` both clean.

**P0 alone removes the hardcoded literal from `4.x`.** It does not make rotation safe — the live path
still uses `environment.appClientSecret` — so P1 is still required.

### P1 — PKCE on the live path — ✅ SHIPPED `89cb3cce`

> **As built** (all five steps below done): `pkce.ts` + `pkce.spec.ts` ported verbatim to
> `modules/auth/`; `app-utils.redirectToAuthServer` is now **async** and persists verifier + state in
> `sessionStorage` under `PKCE_VERIFIER_STORAGE_KEY` / `PKCE_STATE_STORAGE_KEY` (exported from
> `app-utils.ts`, imported by `auth-service.ts`); `retrieveToken` sends `code_verifier` and clears both
> storage keys; the refresh grant sends **`client_id`** instead of Basic auth (public clients
> self-identify); `getBasicAuthHeader` and the dead `checkToken()` are gone, as is the now-orphaned
> `AppProperties` injection in `app-component.service.ts`. **`appClientSecret` no longer exists anywhere
> in `4.x` source** — not in `app.properties.ts`, not in any of the four `environments/*.ts`.
> Verified: `npm run test:ci` 42 pass / 5 skip (incl. the ported `pkce.spec.ts`), `npm run build` clean.
> ⚠ **Not yet verified against a live IdP** — step 7's "real login round trip" is a P2/deploy-time check.
>
> **⚠ P1 shipped with a login-breaking defect; `8c3a7654` fixes it. Deploy the two together.** Adding
> `state` to the `/authorize` redirect changed the *shape of the callback URL*, and P1 did not touch the
> callback, which read the code as `href.substring(indexOf('code=') + 5)` — everything to the end of the
> string. With `state` echoed back, `?code=abc&state=xyz` yields the code `"abc&state=xyz"` and the token
> exchange fails; `?state=xyz&code=abc` works. Parameter order is the IdP's choice, so P1 alone is a
> coin flip on whether login works at all.
>
> `8c3a7654` parses with `URLSearchParams` (exported `authParamsFrom`, order-agnostic, tolerates hash
> routing), **validates `state`** against the sessionStorage copy before exchanging — arming the CSRF
> defence P1 only pretended to have — and moves `pageManager.redirectPath` before the first `await` in the
> now-async `redirectToAuthServer` (a guard returning false can revert the address bar while we yield).
> 8 tests cover it, including both parameter orderings. `test:ci` 50 pass / 5 skip.
>
> **The transferable lesson:** the defect lived precisely in the gap this plan already named — "verified
> by compiler and jsdom, never by an IdP." A green build on an auth change means less here than it looks.

3. Copy `pkce.ts` + `pkce.spec.ts` from `master` into `4.x` (path suits `4.x` layout, e.g.
   `modules/auth/pkce.ts`). Verbatim — no edits; it has no imports.
   → **verify:** `npm run test:ci` shows the ported spec passing.
4. `app-utils.ts:35-40` — generate a verifier + state, persist them (`sessionStorage`), and add
   `code_challenge` + `code_challenge_method=S256` to the `/authorize` params.
5. `auth-service.ts:84-90` — drop `client_secret`, send `code_verifier` instead. Delete the
   `btoa(clientId:clientSecret)` header at `:148-149`.
6. `auth-service.ts:106-109` — refresh without a secret (see Q2 — may need rotation handling).
7. Remove `appClientSecret` from `app.properties.ts:12` and from all four `environments/*.ts`.
   → **verify:** `node tools/check-secrets.mjs` (once merged forward) is green on `4.x`; a real login
   round-trip against the IdP succeeds; a refresh succeeds after access-token expiry.

### P2 — coordinate the rotation

8. Cut a `4.x` release containing P0+P1, deploy it, **then** rotate at the issuer (prod
   `appClientSecret` first, then the shared `iamClientSecret`). Order matters: the new bundle must be
   live before the old secret dies.
9. Close the BACKLOG §5 row on **confirmed rotation**, and merge `tools/check-secrets.mjs` forward to
   `4.x` — it is master-only today precisely because `4.x` still holds live values.

## 4. Open questions — need the operator / IdP owner

- ~~**Q1 (blocking P1): does the IAM at `environment.authServerUrl` support PKCE (S256) and a *public*
  client?**~~ **ANSWERED YES by the operator, 2026-07-25** — P1 was written against that answer.
  ⚠ **This was an operator statement, not an observed IdP capability**: nothing in this repo has yet
  exchanged a code with the real issuer using PKCE. If the first P2 deploy fails at the token endpoint,
  suspect this answer before suspecting the port. The fallback if it turns out to be wrong is unchanged —
  a server-side token exchange (the SPA talks to our backend; the secret lives there), a larger change
  with a different shape.
- **Q2 (now load-bearing — P1 shipped against an assumption here): for a public client, does the IdP allow
  `refresh_token` without client authentication, and is refresh-token rotation enforced?** Public clients
  normally require rotation. **As shipped, the refresh grant sends `client_id` in the body and no Basic
  auth header** — if the IdP rejects that, sessions will fail at first token expiry (~minutes after
  login), *not* at login, so a smoke test that only checks sign-in will miss it. Verify a refresh
  explicitly during the P2 deploy. If unsupported, the fallback is short-lived tokens plus silent
  re-auth instead of a refresh grant.
- **Q3: is the IAM client (`iamClientId`, used by the dead `renewAccessToken`) still needed at all on
  `4.x`?** If nothing uses it after P0, its secret still needs rotating but the client itself may be
  retirable.
- **Q4: which `4.x` deployments are live, and who coordinates the window?** P2 needs a deploy-then-rotate
  sequence per environment; the named-customer prod host is `app1.pronto.lebara.sa`.

## 5. Non-goals

- **No `git-filter-repo` history rewrite** (standing operator call): it invalidates every clone, breaks
  the shift-shared sandbox, and still does not purge forks or GitHub's caches.
- **No backport of master's session/bootstrap layer to `4.x`.** `4.x` deliberately keeps the unversioned
  API surface; dragging `/bootstrap` + `/api/v1` into it is a far larger change than this incident needs.
- **No new auth code that cannot be pointed at a different compliant IdP by config alone** — the D15
  litmus test. There is no vendor of record.

## 6. Watch out

- `4.x` has **four** environment files (no `environment.offline.ts` — that is master-only), so a
  master-shaped checklist of five will not match.
- `app-component.service.ts:73` and `auth-service.ts:90` both build the token URL, and one of them
  concatenates `environment.authVersion` **twice**
  (`authServerUrl + authVersion + authVersion + '/token'`) — looks like a latent bug. Do not silently
  "fix" it as part of this work; confirm which URL the IdP actually serves first.
- Per `docs/BRANCHING.md`, `fix:` work starts on the **oldest supported affected branch**. This lands on
  `4.x` and merges forward to `master`, where the dead code no longer exists — expect the forward merge
  to be mostly a no-op, and do not let it drag `4.x`'s auth files onto `master`.
