# Plan — Landing pages: sign-in landing + one Home route (opened 2026-09-15)

> Active plan. Lives here only while the work is in flight; when it ships, distill the as-built facts
> into `docs/okf/capabilities/surfaces/surfaces.md` (§3.8 routing, §3.3 what the server publishes) and
> `docs/okf/backend/editions/auth-security.md`, move open items to `docs/BACKLOG.md`, then `git mv`
> this file to `docs/archived-documents/plans-archive/`. Mockups: `landing-page-mockups/` (three
> `.dc.html` artboards + `canvas.json`; published canvas https://claude.ai/artifact/K2rnipDoHMsRbs5XYLnzbG).

## 1. Decisions of record (operator, 2026-09-15)

| # | Decision | Consequence |
|---|---|---|
| D1 | **Root redirects to Home** in every edition. The per-Lens landing route (`LENS_HOME` in `app.routes.ts`) becomes Home's primary action, not the root target. | The `lensHomeRedirect` function is kept for the Home card; root `''` → `home`. Returning users lose one click. |
| D2 | **Pre-sign-in page shows branding + version only.** No edition badge, no Space roster, no module list. The public `GET /bootstrap` stops serving `spaces` and `configSpecs`/`enumerations` to an unauthenticated caller under OIDC. | A backend change with a test; the SPA never read `spaces` from bootstrap anyway (surfaces §3.3 "half-consumed"). |
| D3 | **No edition upsell on Personal Home.** Footer carries a plain `Editions` docs link. | Nothing else. |
| standing | **No `if (edition == …)` anywhere.** Every difference between the three screens is driven by `bootstrap.features.*` (module presence) and `session.capabilities`. The `edition` string stays inert. | Pinned by review; the shell rule of record (surfaces §3.2). |

## 2. Grounding (verified against code 2026-09-15)

- Root route: `inspecto-ui/src/app/app.routes.ts:11-39` — function-based `redirectTo` per Lens. No Home component exists.
- Sign-in: `modules/admin/session/sign-in.component.ts` — one card, one SSO button; reached only when `authMode==='oidc' && !authenticated` (`auth.guard.ts:11-15`).
- Identity is DEAD WIRE: `session.service.ts` parses `bootstrap.session.actor` (L37) but never stores it; `user.component.ts:64` sets `user_name = ''` and subscribes to a `UserService.user$` that nothing populates (`core/user/user.service.ts` targets a mock route that no longer exists).
- `bootstrap.session.actor` is `Subject.id()` under OIDC (`ApiContext.actor`, `ApiContext.java:299-305`), `"appUser"` on Personal.
- `canAdminister` (eleven-name vocabulary, `Roles.java:66-85`) has **zero** consumers in `inspecto-ui`.
- Edition string: `BootstrapRoutes.java:68-70` derives `personal|standard` from `-Dauth.mode`; nothing branches on it; the SPA types it and never reads it.
- Public paths: `ControlApi.java:197-199` — `/bootstrap` is public; static files are public by CALL ORDER, not by that list (surfaces §3.3).
- `bootstrap.auth.*` (authorizeUrl etc.) is **never populated by the backend** (grep across `*.java` = 0 hits); the SPA falls back to compile-time `environment.oidc`. Not blocking; recorded here so nobody builds the sign-in page on a field that is not served.
- No `/me` route and no per-user Space list: `GET /spaces` is the whole pod roster (`SpaceRoutes.java:45-56`). An Enterprise Home lists every Space the pod hosts until that exists.
- Branding per Space: `BrandingSettings.java` (`branding.toon`: logo data URL, caption, footer) behind the settings routes; the SPA has `BrandingService`.

## 3. Steps

| # | Step | Verify |
|---|---|---|
| 1 | **Actor wiring (SPA).** `SessionService.actor` signal set from `bootstrap.session.actor` when `authenticated`, cleared in `onAuthLost()`. `UserComponent` renders it; the dead `user_name`/`UserService.user$` plumbing goes. | `session.service.spec.ts` + `user.component.spec.ts` assert the rendered name; `npm run lint:tokens`, three tsconfig typechecks, `npx ng test`. |
| 2 | **Trim the public bootstrap (backend).** Under an `Authenticator` with no subject, `GET /bootstrap` omits `spaces`, `configSpecs`, `enumerations`; keeps `edition`, `features`, `session` (anonymous), `auth`. Personal (no authenticator) unchanged. | New real-HTTP test in the security module's test tree: anonymous → keys absent; bearer → keys present; Personal → unchanged. `mvn -o test -Pedition-standard` for `inspecto-security` + `-am`. |
| 3 | **Home route** `modules/admin/home/` at `/home`; root `''` → `home`. Sections and their gates: greeting (`actor()` under OIDC, "Welcome to Inspecto." otherwise) · listen-address notice (`authMode()==='none'`, dismissible per browser) · Lens card (current Lens + `LENS_HOME[lens]` primary button + Switch Lens) · quick start / quick actions (`canOnboardConnections`, `canAuthorWorkbench`, `opsEnabled` for Triage Incidents, `canOperateRuns` for Run now, `canAdminister` for Administer this Space) · activity strip + needs-attention list from existing Runs / Incidents / Expectations routes, `<inspecto-empty-state>` when empty · access card (`multiSpace` true) · footer version + `Editions` link. Uses `<inspecto-alert>`, `<inspecto-status-badge>`, `<inspecto-empty-state>`, `<inspecto-chip>`; no hardcoded colour. Nav gains `home` (first item) — three edits: route, `navigation-data.ts`, `ACCESS_ACTION_NODES` untouched (Home hosts no gateable action of its own). | Spec with `expectNoA11yViolations`; module-flag and capability gating each asserted both ways; preview at `/` on Personal and with `mockAuthMode: 'oidc'`. |
| 4 | **`LensService.canAdminister`** computed (first UI consumer) — the same derivation shape as the other ten. | Spec: granted under OIDC only when the capability is published; Personal honour-system per the existing precedent. |
| 5 | **Sign-in landing restyle** (`sign-in.component.ts`): left panel = product mark, Space branding (default Space's `BrandingService`), headline, footer text + version + reachability from `/health`; right = the existing card. Version comes from the build (`environment` / `package.json` at build time), never from bootstrap. | Spec + a11y; preview with `mockAuthMode: 'oidc'`. |
| 6 | **Docs**: surfaces §3.8 (root → Home), §3.3 (trimmed bootstrap), auth-security (actor wired), `USER_GUIDE` first-run section; `INDEX.md` untouched (no new root doc). | `node tools/check-vocabulary.mjs`; `graphify update .` |

Order: 1 → 2 → 3 → 4 → 5 → 6. Steps 1 and 2 are independent of each other.

## 4. Deliberately not in scope

- A per-user Space list or a `/me` route (needs a backend design call; Home lists the pod roster meanwhile).
- Serving `bootstrap.auth.*` from the backend (separate row; the sign-in page must not depend on it).
- Any change to the Lens landing routes themselves, or to what a Lens can see (UI-1 rule of record).
- Marketing content in-product.

## 5. Open questions (none blocking)

- Home's activity strip counts come from list routes with `limit`; if any of them lacks a count field, show the list and drop the number rather than paging to count.
