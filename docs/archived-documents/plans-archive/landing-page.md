# Plan — Landing pages: sign-in landing + one Home route (opened 2026-09-15)

> ⛔ **ARCHIVED 2026-09-15 — all six steps shipped. History, never maintained, never cited as current.**
> The durable facts live in `docs/okf/capabilities/surfaces/surfaces.md` (§3.3 the bootstrap payload and
> what an anonymous caller gets, §3.8 root → Home), `docs/okf/backend/editions/auth-security.md` (the
> actor wiring) and `docs/USER_GUIDE.md` §1. The three residuals are BACKLOG rows `HOME-VERSION-1`,
> `HOME-TILES-1` and `SIGNIN-PREVIEW-1`. Mockups: `landing-page-mockups/` (three
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
| 1 ✅ | **Actor wiring (SPA).** SHIPPED `9ed28c35`. `SessionService.actor` signal set from `bootstrap.session.actor` when `authenticated`, cleared in `onAuthLost()`. `UserComponent` renders it; the dead `user_name`/`UserService.user$` plumbing goes. | `session.service.spec.ts` + `user.component.spec.ts` assert the rendered name; `npm run lint:tokens`, three tsconfig typechecks, `npx ng test`. |
| 2 ✅ | **Trim the public bootstrap (backend).** SHIPPED `6fadbd81`. Under an `Authenticator` with no subject, `GET /bootstrap` omits `spaces`, `configSpecs`, `enumerations`; keeps `edition`, `features`, `session` (anonymous), `auth`. Personal (no authenticator) unchanged. | New real-HTTP test in the security module's test tree: anonymous → keys absent; bearer → keys present; Personal → unchanged. `mvn -o test -Pedition-standard` for `inspecto-security` + `-am`. |
| 3 ✅ | **Home route** SHIPPED `1029a292`. `modules/admin/home/` at `/home`; root `''` → `home`. Sections and their gates: greeting (`actor()` under OIDC, "Welcome to Inspecto." otherwise) · listen-address notice (`authMode()==='none'`, dismissible per browser) · Lens card (current Lens + `LENS_HOME[lens]` primary button + Switch Lens) · quick start / quick actions (`canOnboardConnections`, `canAuthorWorkbench`, `opsEnabled` for Triage Incidents, `canOperateRuns` for Run now, `canAdminister` for Administer this Space) · activity strip + needs-attention list from existing Runs / Incidents / Expectations routes, `<inspecto-empty-state>` when empty · access card (`multiSpace` true) · footer version + `Editions` link. Uses `<inspecto-alert>`, `<inspecto-status-badge>`, `<inspecto-empty-state>`, `<inspecto-chip>`; no hardcoded colour. Nav gains `home` (first item) — three edits: route, `navigation-data.ts`, `ACCESS_ACTION_NODES` untouched (Home hosts no gateable action of its own). | Spec with `expectNoA11yViolations`; module-flag and capability gating each asserted both ways; preview at `/` on Personal and with `mockAuthMode: 'oidc'`. |
| 4 ✅ | **`LensService.canAdminister`** SHIPPED `19e3e239`. computed (first UI consumer) — the same derivation shape as the other ten. | Spec: granted under OIDC only when the capability is published; Personal honour-system per the existing precedent. |
| 5 ✅ | **Sign-in landing restyle** SHIPPED `21866ef6`. (`sign-in.component.ts`): left panel = product mark, Space branding (default Space's `BrandingService`), headline, footer text + version + reachability from `/health`; right = the existing card. Version comes from the build (`environment` / `package.json` at build time), never from bootstrap. | Spec + a11y; preview with `mockAuthMode: 'oidc'`. |
| 6 ✅ | **Docs**: surfaces §3.8 (root → Home), §3.3 (trimmed bootstrap), auth-security (actor wired), `USER_GUIDE` first-run section; `INDEX.md` untouched (no new root doc). | `node tools/check-vocabulary.mjs`; `graphify update .` |

Order: 1 → 2 → 4 → 3 → 5 → 6. ⚠ **4 before 3**, not as written: Home's administration action gates on
`canAdminister`, so the capability had to exist first.

## 3a. As-built deviations from the mockups (step 3, 2026-09-15)

- 🔴 **The "Expectations breached" and "Datasets written" tiles are NOT built.** Neither has a backing
  call: `ExpectationsService.list()` takes no limit and there is no breach endpoint, and no route reports
  a dataset-write count at all. Counting breaches would mean fetching every Expectation on a landing
  page. ⛔ Do not add either tile until the backend serves a cheap count — a landing page is the wrong
  place to discover this cost.
- **Home renders no footer.** The shell already has one; the mockup's version/edition line would double it.
- **A failed run-history call is not a first run.** `GET /jobs/runs` needs the DuckDB jobs backend, so its
  404/503 is an expected deployment state. `runsUnavailable` is tracked separately from "no runs", because
  showing the first-run page to a running deployment would tell it nothing had ever run. Pinned by a test.
- **`lensHome(lens, eventsEnabled)`** was extracted from `lensHomeRedirect` so the Home button and the root
  redirect share one statement of the Ops-without-Events fallback instead of mirroring it.
- **The Space name renders only in a multi-Space deployment** — a single-Space install would print the
  seeded name "default" as a page eyebrow.

## 4. Deliberately not in scope

- A per-user Space list or a `/me` route (needs a backend design call; Home lists the pod roster meanwhile).
- Serving `bootstrap.auth.*` from the backend (separate row; the sign-in page must not depend on it).
- Any change to the Lens landing routes themselves, or to what a Lens can see (UI-1 rule of record).
- Marketing content in-product.

## 5. Open questions (none blocking)

- Home's activity strip counts come from list routes with `limit`; if any of them lacks a count field, show the list and drop the number rather than paging to count.

## 6. Residuals at archive (2026-09-15)

| Row | What is left |
|---|---|
| `HOME-VERSION-1` | 🔴 **No version was shown anywhere**, because nothing in the running system knows one — no manifest read, no route, no `environment` field, and `inspecto-ui/package.json` carries the Angular scaffold's **21.0.0**, not `4.0.0-SNAPSHOT`. D2 asked for "branding + version"; only branding was buildable. |
| `HOME-TILES-1` | The mockups' "Expectations breached" and "Datasets written" tiles are **not built** — no cheap call backs either. |
| `SIGNIN-PREVIEW-1` | 🔴 **The restyled sign-in page has never been opened in a browser.** It renders only under `authMode === 'oidc'`, and the `mockAuthMode: 'oidc'` dev switch its own javadoc names **does not exist in the tree**. Unit-tested only; responsive behaviour unverified. |

⚠ **`bootstrap.auth.*` is still never populated by the backend** (§2). The sign-in page does not depend
on it — the SPA falls back to compile-time `environment.oidc` — but the dead wire survives this work.
