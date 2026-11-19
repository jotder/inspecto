---
type: Capability
title: Surfaces & Lenses (UI) — capability spec — Purpose, requirements and specification
description: Part 1 of 2 of Surfaces & Lenses (UI) — capability spec: Purpose & scope; Requirements of record; Specification
status: current
---

# Surfaces & Lenses (UI) — capability spec — Purpose, requirements and specification

Part 1 of 2 of [Surfaces & Lenses (UI) — capability spec](plan-surfaces.md) · next: [Decisions, not built, refused and verification](plan-surfaces-decisions-verification.md).

## 1. Purpose & scope

This capability is **the frame every other capability is seen through**: one Angular application that
decides what a given operator can see, how they move between surfaces, and what a pane looks and
behaves like before it has any data in it.

**In scope**

* **Lenses** — the three roles, how one is chosen and persisted, and each one's landing route.
* **Two gating systems and the line between them** — server-published capabilities and module-presence
  flags, versus the client-side Lens.
* **Navigation** — the default tree, what filters it, and the Menu Builder that lets an operator
  reshape it.
* **Routing** — the shell's route table, lazy loading, the per-Lens root redirect, breadcrumbs.
* **The design system** — the shared component family, the tiered data table, and the token gate that
  forbids hardcoded colour.
* **Theming** — the token layer and light/dark resolution.
* **Accessibility** — the axe harness, what it covers and what it deliberately cannot.
* **Connectivity and errors as shell concerns** — the banner, the interceptor, and the "explain, never
  toast" rule for an absent module.
* **Settings** — the drawer and the master-detail settings pane.
* **Requirement intake** and **Reconciliation/Breaks** — the two panes this area's rows own outright.
* **What the server publishes for the shell** — the bootstrap payload, the capability manifest, static
  serving, branding and the saved menu tree.

**Out of scope (owned elsewhere)**

* **Every feature pane's behaviour and data contract** → the owning capability. This file names a pane
  only where the *shell* is the subject.
* **Authentication, the authorization gate, Access Profiles** → `SEC`
  ([`security/security.md`](../../module/access/plan-security.md)). This area consumes the capability list; it does
  not decide it.
* **The API envelope, versioning and error bodies** → `API`
  ([`control-api/control-api.md`](../../module/processor/plan-control-api.md)).
* **Spaces as a tenancy model** → `SPC`; the shell owns only the picker and the request rewrite.
* **Bundling and what ships** → `PKG`.

## 2. Requirements of record

| ID | Requirement (as it holds) | MoSCoW | Status of record | Edition of record |
|---|---|---|---|---|
| **UI-1** | **Lens** switcher (Business/Builder/Ops) + capability-gated panes | Must | ✅ SHIPPED. ⚠ **Two clarifications.** The Lens is a **client-side presentation choice** persisted in browser storage, not a server role; what the server publishes is a **capability list**, and the two do different jobs (§3.2). And "capability-gated panes" overstates the mechanism twice over. **No route guard and no structural directive enforce a capability** — each pane calls the check itself. And 🔴 **a Lens never hides a pane**: nav filtering by Lens was *evaluated and declined outright* on 2026-07-03, per-route Lens tagging with it. Three current documents still say the navigation is "filtered by the active Lens" (§3.4) | All |
| **UI-2** | Shared design system + no-hardcoded-colour gate + `/design` gallery | Must | ✅ SHIPPED. The gallery is at `/design` and again at `/settings/design`; the gate is a 134-line Node script wired as the **first** CI step. 🔴 **The component count is stated seven different ways across the docs** — 5, 6 (two different sets), 7, 10, 12 and 5 again — while the directory holds **22 units** plus five cross-cutting siblings (§3.5). Two of those documents omit components the same bundle documents as concepts. The data table has **four** tiers, not three | All |
| **UI-3** | Accessibility: WCAG 2.2 AA, axe-core gate in CI | Must | 🟡 **CORRECTION — the gate is real and broad; the conformance claim is not.** `expectNoA11yViolations` is asserted in **204 of 334** spec files. But it runs in jsdom with **seven rules disabled** — colour contrast and every page-level rule — so it cannot see rendered contrast, landmarks or reflow. "WCAG 2.2 AA" rests on a **self-assessed manual walkthrough of 17 pages dated 2026-06-16 whose own findings were explicitly deferred and never applied in that pass**, and which says in its own words that it is not a formal certification. The route-level browser pass is still future work. 🔴 **Seven findings from that audit are still open**, two of them Moderate: a chart canvas with no text alternative, live grid updates not announced, series distinguishable by colour alone, focus obscured by sticky headers, unverified target sizes, an unverified non-drag path for grid resize, and unverified reflow at 320 px. So "SHIPPED" coexists with unremediated Level-AA gaps this repo itself lists. 🔴 And `compliance/controls-matrix.md` contains **no accessibility control at all** — its UI-gate row names the colour-token lint, the unit tests and the build, and omits the accessibility gate that two non-functional requirements cite as their evidence | All |
| **UI-4** | ~~Offline mock-first operation~~ | Must | ⛔ **SUPERSEDED** — the mock backend was deleted 2026-08-31 and the SPA now requires a real control plane. ⚠ The deletion's ripple is **not finished**: at least eight current documents still describe the offline arm as live — including this area's own convention page, which calls menu persistence "mock-first", and a design-system page that cites a mock handler file **that no longer exists**. The requirement file itself still defines a `MOCK-FIRST` status value and still calls the SPA "mock-first + live" two hundred lines above the row that supersedes it (§5) | All |
| **UI-5** | Responsive sweep (32 routes × 2 breakpoints) | Must | 🟡 **CORRECTION — this is provenance, not a gate.** No automated responsive test exists anywhere in the project. The "32 routes, all green" record lives only in an **archived** review sheet, and **nothing re-runs it**. **And no document agrees on the denominator**: the sweep says 32, a review ten days later says 47 lazy routes, three current pages say "~35 screens", the shell's own route file declares 44 uncommented paths, and there are 47 route files under the admin tree. **Nothing in the tree yields 32**, and at least twelve routes have been added since the sweep ran | All |
| **UI-6** | **Requirement** intake: Business submits, Builder triages, delivery recorded | Should | ✅ SHIPPED. Server side is a component kind with a three-step lifecycle; submission is **deliberately open** and only triage and delivery are capability-gated | All |
| **UI-7** | **Reconciliation** + **Breaks** (auto-close on re-match; manual resolutions preserved) | Must | ✅ SHIPPED. ⚠ **Worth knowing where the lifecycle lives:** Break status is computed **in the browser** after each run and persisted **inside the reconciliation component's own document**. There is no Break store and no resolve route — so "auto-close on re-match" is a client merge rule, not a server invariant (§3.11) | All |
| **UI-8** | Settings drawer + consolidated admin settings pane | Should | ✅ SHIPPED. ⚠ The drawer and the pane are **two different components with near-identical names**: the drawer is a navigation launcher that makes no request; the pane is the master-detail surface, with **12** sections ⚠ **Provenance repaired 2026-09-09.** `REQUIREMENTS.md` cited `7e06463`/`12ead9c`; **neither is a valid object in this repository** — casualties of the history rewrite. Matched by subject and by the files they touch: `425f3f9a` (2026-07-07) created `SettingsDrawerComponent`, and `a8218f29` (**2026-07-14**, not 07-07 as the row claimed) is the master-detail `/settings/<section>` pane. | All |

🔴 **The editions board has no row for this area, and the shell is not edition-neutral.** No row covers
the SPA, the design system, Lenses or accessibility — yet `EDITIONS.md` states in its own grouping note
that rows are grouped by feature area **"plus the control-plane / UI / security / compliance surfaces"**,
a UI grouping the board does not contain. And the shell **is** gated in three places: Personal loses the
Events screen (the nav entry hidden, the Ops Lens home falling back, an explained panel on three panes),
and the geo, link-analysis and objects panes go with their modules. So eight requirement rows claim
edition `All` **on the authority of a matrix that never mentions them** — under a file-level note saying
that matrix is authoritative for the Edition column. The sibling areas were all re-grounded on
2026-09-08; **`UI` was the one that never was**, and only `UI-8`'s MoSCoW was ever fixed.

## 3. Specification

### 3.1 Lenses

Three Lenses: `business`, `builder`, `ops`. The choice is a **client-side presentation setting**,
persisted in browser storage under one key and restored synchronously at construction so the first paint
is already correct; the default is `builder`. Each Lens has a landing route: Business goes to the
KPI-and-Reports gallery, Builder to Pipelines, Ops to Events — with a fallback to Pipelines when the
events module is absent, so a Lens never lands on a surface that can only explain itself.

`LensService` exposes the switcher's inputs (`allowedLenses`, `currentLens`, `selectLens`), a
presentation-only `readOnly`, a hook for server-pushed action grants, and one computed per capability
(ten of them, §3.2). The switcher itself is a small menu labelled with the Lens set the caller is
allowed.

**Business is read-only observe by product decision.** That is a *presentation* stance, so a pane which
needs it enforces it twice — hiding the control and guarding the method — because a hidden button is not
a permission. ⛔ **The rule of record is "gate on a capability, never on Lens identity"**, and the
read-only flag's own contract says nothing outside its file should read it. ⚠ One pane document
nonetheless describes its controls as method-guarded on that flag, and the operational default differs
between two panes by design — run-now stays available in every Lens on Jobs, and is Lens-gated on Runs.
The precedent a new pane is told to follow is written in the *exception*, not in the conventions (§5.2).

### 3.2 Two gating systems, and the line between them

This is the part most likely to be got wrong, so it is stated as a table.

| Mechanism | Source of truth | What it does | Where |
|---|---|---|---|
| **Module presence** | `bootstrap.features.*`, each derived from whether a **real** handler registered | **Hides whole nav entries** — an absent module 503s on every path, so an entry would only explain itself | the navigation service drops ids |
| **Capabilities** | `bootstrap.session.capabilities`, drawn from the 22 names of `CapabilityManifest` (2026-11-01) | Constrains **which Lenses are selectable**, and hides or disables **controls inside a visible pane** | `LensService` computeds, called by each pane |
| **Action grants** | pushed per Lens from Access Profiles | A second, finer gate *underneath* capabilities | the access state service feeds `LensService` |
| **The Lens** | the browser | Presentation only — **it never hides a screen** | templates |
| **Access Profiles** | authored per space | The one thing that *can* hide a nav subtree per Lens — and it is a curation tool, deliberately **not** a permission system | the access tree-table |

🔴 **A Lens never hides a pane.** Confirmed with the product owner on 2026-07-03: Business sees the same
navigation as everyone, and only authoring *actions* are hidden. Per-route Lens tagging was **evaluated
and declined outright** — "not built and not planned" — because with nav filtering off the table it had
no consumer. The user guide states the rule plainly: a Lens changes emphasis, not visibility. The one
exception is the Access matrix (2026-07-14), which can hide a subtree per Lens and whose root default is
**allow**, so an empty profile reproduces the prior behaviour exactly.

🔴 **Nothing enforces a capability structurally.** The only route guard checks whether login is
required. There is **no capability guard and no structural directive**, so every authoring surface has
to remember its own check, and a new pane that forgets one is not caught by anything. That is the single
highest-value hardening available in this area (§5.2).

⚠ **Not every feature flag filters navigation.** The exchange flag gates in-pane affordances only and
never hides an entry; `authMode`, `loopbackOnly` and `multiSpace` describe the install, not a module.

🔴 **The per-request `permissions[]` array is structurally unreachable, and this area is why.** The
envelope carries a per-resource permission set, and the version-one interceptor unwraps every response
to its data field, **discarding permissions, links and metadata before any feature service sees the
response**. The type is declared client-side and no consumer exists. Three earlier specs recorded this
as "shipped with no consumer"; the sharper statement is that **the shell's own interceptor makes a
consumer impossible** without changing it first.

### 3.3 What the server publishes for the shell

🔴 **The running product knows its own version (`HOME-VERSION-1`, closed 2026-09-15).** Until then NOTHING in the
system did: no Java read `Implementation-Version`, no route served one, `environment.ts` had no field, and
`inspecto-ui/package.json` says `21.0.0` — the Angular scaffold's number — which is why the sign-in landing
deliberately showed no version at all. Now ONE source: `inspecto/pom.xml`'s shade `ManifestResourceTransformer`
stamps `Implementation-Version: ${project.version}` into the fat JAR, `ProductVersion.current()` reads it back
through `Package.getImplementationVersion()`, and `GET /bootstrap` carries it as `data.version` (documented in
`openapi-v1.json`'s `Bootstrap` schema, required). A `target/classes` run reports the WORD `dev` — never a
number that could pass for a release — and `-Dproduct.version` overrides for a launcher that repackages.
`SessionService.version()` exposes it; the sign-in page appends `v<version>` to its footer once the bootstrap
read lands (nothing until then, pinned by `sign-in.a11y.spec.ts`). **Verified 2026-09-15:** the packaged jar's
manifest reads `Implementation-Version: 4.0.0-SNAPSHOT` and a boot of it answers `/bootstrap` with
`version: 4.0.0-SNAPSHOT`; the reactor's own tests see `dev`. ⚠ `package.json`'s `21.0.0` is left alone on
purpose — the SPA must never be a second source of the product version.


`GET /bootstrap` aggregates six sections behind one ETag'd conditional request: the edition string,
feature flags, config specs, enumerations, the space list and the session. Its stated purpose is to
replace a handful of startup round-trips.

🔴 **The anonymous payload is now NARROWER than the authenticated one** (landing-page plan D2,
2026-09-15). Under a registered `Authenticator` with no subject resolved, the response omits `spaces`,
`configSpecs` and `enumerations` — deployment inventory a sign-in page has no business showing — and
keeps `edition`, `topology`, `features`, `branding` and the anonymous `session`, which is everything the
SPA needs to start the OIDC redirect. **Personal registers no `Authenticator` and is unchanged**: there
is no "before sign-in" there to protect, and the gate is derived from the SPI slot rather than from the
edition string. ⚠ **`branding` was ADDED to the payload in the same change and is served anonymously on
purpose** — it is pre-sign-in *context*, not inventory, and it is the sign-in page's ONLY source, because
`GET /settings/branding` sits behind the auth gate and would 401 on exactly that screen. Both directions
are pinned by `ControlApiAuthV1Test`.

⚠ **The SPA reads about half of it.** Its own bootstrap interface takes the edition, the `features` map
(seventeen keys on 2026-11-01), the session and the auth block — and nothing else. Config specs are still fetched **per type** from the
spec route, and the space list still comes from the spaces routes, so the aggregation's benefit is only
partly realised.

⚠ **The edition string is derived, not declared.** `BootstrapRoutes.edition()` answers `basic` when
nobody signs in, `advanced` when an access decider (the policy module) is active, and `standard` otherwise —
`basic | standard | advanced`, never the retired names. The auth-mode flag no longer feeds it. The trustworthy
signals are still the
feature flags, each derived from whether a handler actually registered, which is exactly why the edition
programme chose module presence over a switch.

**Capabilities** are a closed vocabulary of ten names, and a manifest maps every capability-gated route
to its capability. A test **regex-scans every reactor module's sources** for declaration sites and
asserts the manifest matches in both directions — a rare case of a doc-shaped artifact that cannot drift.

**Static serving.** With a UI directory configured, a request outside the API tries the filesystem
before returning a JSON 404; an **extensionless** path with no matching file falls back to the
application shell, which is what makes deep links work. Files carry `no-cache` plus a synthesised
validator so an unchanged file revalidates to a bodiless 304 and a redeployed one gets fresh bytes —
`no-cache` rather than `no-store` deliberately, after a stale-chunk failure. Absent the directory,
nothing is served and the answer stays a JSON 404.

🔴 **A server-sent-events URL is NOT space-scoped by the interceptor** *(found and fixed 2026-09-10)*. The
space prefix is applied by an `HttpInterceptorFn`, which rewrites `HttpClient` requests only, so every
`EventSource` bypasses it and reaches the unscoped path — in a multi-space deployment that tails the default
space while the screen shows another. The rule now lives in one shared function that the interceptor and
every stream call, and both live streams (notifications, and the Events pane's new signals tail) go through
it. ⚠ It is invisible in a single-space deployment, because there the rule is a no-op — which is why the
notifications stream carried it from the day it shipped.

🔴 **Static files are public by CALL ORDER, not by the public-path allowlist** *(landed 2026-09-10 — Sprint
7.2, from the console's genesis plan, which was its only home)*. The static branch runs **before**
authentication, which only executes inside the route-match loop — so assets answer without a token even
though they appear in no `PUBLIC_PATHS` entry. ⚠ Anyone reading that allowlist to decide what is
unauthenticated will get the wrong answer, and anyone moving the static branch below authentication will
lock the shell out.

**Cross-origin serving exists for a dev topology nobody uses.** The CORS stage is off unless its property is
set, and it answers preflight only when on; it was added when a separately hosted development SPA was
contemplated. The dev server proxying to the control plane made it unnecessary, so ⛔ treat CORS as a legacy
escape hatch rather than a supported deployment shape (`okf/product/module/processor/plan-control-api.md` for the
stage itself).

**Branding and the saved menu tree** are per-space documents under the write root: logo, caption and
footer for branding; the operator's customised navigation for the Menu Builder, version-checked on write
so a stale client gets a 422 rather than clobbering it.

### 3.4 Navigation and the Menu Builder

The default tree is a single data file: a Business group, an Operations group, a Platform group
containing Workbench, Studio and Catalog, plus system maintenance, settings and the two AI surfaces. The
navigation service clones that default, **drops the ids belonging to absent modules**, back-fills the
alternate layout variants, and prepends the operator's own Menu Builder tree and favourites.

⚠ **Three current documents in this very tier say the navigation is "filtered by the active Lens".** The
code comment that owns the behaviour says the opposite in as many words, and so does the user guide. The
reconcilable middle — Access Profiles hiding a subtree — is documented in one convention page and
nowhere a reader would look for it.

The **Menu Builder** persists server-side and mirrors to browser storage so the tree paints instantly
and survives a reload; it is gated on its own capability, **split out of the authoring capability on
2026-07-25 because changing a menu is a user-visible act rather than a build act**. **Favourites are
deliberately the other way round**: a per-space browser-local overlay that is **never written to the
shared tree**, because a favourite is personal while the tree is shared.

**Screen links and landing pages (UIE-7, 2026-09-25).** A Menu item binds either a library Component
(Dashboard, Widget, saved view) or, with `kind: route`, an **in-app route** such as `/cases`, so a
business user reaches platform screens from the Space menu. The server accepts only an absolute in-app
path — no scheme, no `//` or backslash, no `.`/`..` segment, no whitespace, at most 512 characters — and the
Menu Builder's *Add screen link* dialog mirrors that check. A `:` is refused in the path but allowed after the
first `?` or `#` (`/cases?since=2026-09-01T00:00`, operator 2026-09-25); both validators are pinned to one
table, `inspecto-ui/src/app/inspecto/contracts/menu-route.contract.json`. The tree also carries an optional `landing`
(a leaf id, refused on write when it names no leaf, dropped on read when stale). Opening the app at `/`
resolves **per-Demo-User landing → Space landing → platform Home**: the Demo User's `landing` column in
`demo-users.toon` rides on the sign-in picker entry (a menu item id, never a role), and the first
candidate still naming a leaf wins. A landing naming nothing, or a Menu tree that cannot be read, opens
Home with one console warning — never a blank screen. Switching Space now reloads at `/`, so it lands on
the new Space's landing instead of the Lens home.

### 3.5 The design system

The shared family lives in one directory — **22 non-spec units**, including the alert, breadcrumb,
chart, chip, connectivity banner, definition drawer, editable grid, empty state, option picker, parser
tree, schema form, skeleton, split directive, status badge and token picker — plus five cross-cutting
siblings: the ag-Grid wrapper with its light and dark themes and shared actions cell, the tiered data
table, the tree table, the shared query-builder widgets, and the save-as-rule dialog.

**The data table has four tiers, not three**: `mini` is the themed grid; `standard` adds the toolbar
with a column chooser, search and CSV export; `pro` adds an always-on SQL editor that runs real SQL in
the browser plus a filter builder that regenerates it; `proMax` adds saving a parameterised rule.

🔴 **No two documents agree on how many shared components there are.** The counts on record are 5, 6
(twice, over different sets), 7, 10, 12 and 5 — and two of the lists omit the chip and the tree table,
which the same bundle documents as concepts. **Eleven shipped shared components have no design-system
page at all**, including the schema form that one source names as a member, and the confirm service that
two sources call a must-reuse. Read the directory and the gallery; do not trust a list.

The **gallery** at `/design` (and `/settings/design`) live-renders the family, which is what keeps it
honest — a component that cannot render in the gallery is broken.

### 3.6 Theming and the colour gate

Tokens are CSS custom properties generated per scheme, with light and dark blocks; the only sanctioned
exceptions are canvas surfaces that cannot read CSS variables, which have their own token modules. The
scheme is a configuration value of light, dark or auto — `auto` resolving against the operating system
preference — applied as a class on the document body. The default is dark.

**The gate** walks the shared and admin trees and fails on hardcoded hex or rgba values, on
hand-rolled tone-class helpers, and on status-tinted background fills. It allowlists exactly five files
as the sanctioned colour owners and offers one inline escape hatch. It is the **first** step of the UI
pipeline, before formatting, types, tests and the build.

### 3.7 Accessibility

The harness runs axe against a rendered fixture in jsdom and fails on any violation. It is threaded
through **204 of 334** spec files, so this is a broad gate rather than a token one.

**Seven rules are disabled, for stated reasons**: colour contrast needs real painting, and the six
page-level rules — landmark region, one main landmark, a page heading, a language attribute, a document
title and a bypass link — are meaningless against an isolated fixture.

⚠ **So the automated half verifies roles, names, ARIA and structure — not rendered contrast, landmarks
or reflow.** Contrast is covered by the manual audit and by the token gate. The manual half is a
**self-assessment by heuristic walkthrough dated 2026-06-16 over 17 pages**, which states that its
findings were deferred and that it is **not a formal certification**. A route-level browser pass remains
the documented next step. Both halves are honest about their limits; the requirement's flat "WCAG 2.2
AA" is the part that overstates.

🔴 **Seven findings from that audit remain open**, listed with a fix-priority order and never
scheduled: a chart canvas with no text alternative and unannounced live grid updates (both Moderate),
series distinguishable by colour alone, focus obscured by sticky headers, target sizes unverified on
dense controls, an unverified keyboard path for grid resize and reorder, and unverified reflow at
320 px. Only the first is cross-referenced from the concept tier.

🔴 **And no compliance control covers any of this.** The controls matrix has no accessibility row; its
UI-gate row names the token lint, the unit tests and the build. Two non-functional requirements cite the
accessibility gate as evidence of a control that the matrix does not carry.

### 3.7b Previewing the sign-in page locally (`SIGNIN-PREVIEW-1`, closed 2026-09-15)

The restyled `/sign-in` renders only when `GET /bootstrap` reports `features.authMode: 'oidc'`, and until
2026-09-15 no shift had ever opened it in a browser — the `mockAuthMode: 'oidc'` dev switch its own javadoc
promised **never existed**. The recipe, no source edit required:

```bash
pwsh tools/run-backend.ps1 -AuthMode oidc      # the CORE echoes -Dauth.mode on /bootstrap
cd inspecto-ui && npx ng serve                 # proxy.conf.json forwards /api to :8080
# open http://localhost:4204/sign-in
```

⚠ **Preview only.** The core has no `Authenticator`: it reports `edition: standard` / `authMode: oidc` and
authenticates NOTHING, so the page renders while "Sign in with SSO" cannot complete — for a real session run the
Standard bundle with the OIDC properties (§security). The optional-module 503s in the console are the absent-module
stubs, not the page. **Seen 2026-09-15:** desktop 1280×800 — hero + card side by side; phone 375×812 — one column,
no horizontal scroll, card intact.

### 3.8 Routing

**The root redirects to `home`** (landing-page plan D1, 2026-09-15). It previously redirected per Lens,
straight to that Lens's landing route — which dropped the three Lenses onto three unrelated screens and
left a fresh install with no front door. **Home is now the one landing route in every edition, and the
Lens landing route is its primary action**; `lensHomeRedirect` survives, and the Ops-without-Events
fallback it carries was extracted into a pure `lensHome(lens, eventsEnabled)` that both it and the Home
button call, so the two cannot drift. ⚠ Home itself is **not** edition-aware: the listen-address warning
keys on `authMode === 'none'`, the Incident affordances on the ops module flag, the administration action
on `canAdminister`, and the grants card on multi-space — §3.2's rule applied, never the edition string.

✅ **Two more tiles, each behind a cheap server count** (`HOME-TILES-1`, closed 2026-09-16). The approved
mockups' *Expectations breached* and *Datasets written* tiles were dropped because no cheap call existed —
`ExpectationsService.list()` takes no limit and nothing counted dataset writes — so the backend got the count
FIRST: `GET /expectations/breached-count` (`ExpectationRoutes`; enabled Expectations whose persisted
`lastResult.status` is FAILED, `{count}`) and `GET /signals/count?type=…&since=…` (`SignalRoutes`; a typed window
over the Signal ledger, `{type, count, datasets, capped}` where `datasets` is the distinct `payload.dataset` set
and `capped` says the ledger page was full, so the tile reads "at least"). Home calls both in `load()` — the
second as `dataset.write` over the last 24 h — and a tile whose call fails is **hidden, not dashed**: the
numbers shown are always the server's, never a client-side sweep. ⚠ Both tiles sit on the operational Home
only; the first-run panel (no runs yet) does not show them. Read-shaped GETs, so neither needed a
`CapabilityManifest` entry; both are in the generated OpenAPI skeleton.

✅ **The Datasets-written tile counts BOTH write signals** (2026-10-17). A live telco-fraud drive showed it at 0
beside 17 Job writes: `dataset.write` is emitted only by `ConsignmentProcessJobType` and `MaterializeTask`, while
`sql.template` Jobs emit `job.dataset.produced` and a polled Collector emits neither. `/signals/count` takes ONE
`type` and returns a distinct-Dataset *number*, not names, so Home calls it twice (`forkJoin`, one failed call
tolerated) and SUMS `count` and `datasets`; `capped` is the OR. The two types come from disjoint emitters, so a
write is never double-counted, but a Dataset written both ways counts once per type — `datasets` is an upper
bound. Exact distinct-across-types would need a route change (several `type` values returning names); not built.

✅ **One count over both types, exact distinct Datasets** (2026-10-23, `TELCO-FRAUD-STORY-GAPS-1`). `type` on
`GET /signals/count` now takes a comma-separated list (each entry exact or a `prefix.*` glob; blanks dropped, an
all-blank list is the same 422 as none). The route reads the raw Signal page of the window once, keeps the Signals
matching ANY entry, and counts distinct `payload.dataset` across them, so a Dataset written both ways counts once.
Home makes ONE call, `type=dataset.write,job.dataset.produced`, and shows the server's figure as is (no client sum).
`capped` now tests the RAW page size (the window held `EventQuery.MAX_LIMIT` Signals of any type), not the filtered
count: the old test almost never fired, so a window flooded with other Signals reported an exact-looking floor.
Pinned by `ControlApiSignalsTest` (`countsSeveralTypesInOneCall…`, `capsWhenTheWindowIsFull…`) and
`home.component.spec.ts`. **Live 2026-10-23** (Advanced `-DemoAuth` *(removed 2026-11-10 by `PRODUCT-ASSEMBLY-1` PA-2; today `-Distribution demo`)* bundle, a fresh `telco-fraud` Space, the three
sample feeds, 14 detection Jobs): the tile read *14 / 14 writes*; two Jobs re-run with Home open moved it to
*14 / 16 writes* on the next tick with no reload (distinct Datasets held). The same drive found a fresh Space's Home
stuck on the first-run panel (*Nothing has been onboarded yet*) beside 14 finished Jobs until a reload, because the
run list was read once at init: the 30 s tick now re-reads the run list too while the page is on the first-run panel
(and stops once it is operational).

Two guest routes and one public share route sit outside the shell; the
rest hang off a shelled route with the login guard and an initial-data resolver, with roughly 40 lazily
loaded children. Breadcrumbs are a shared component fed by each detail pane rather than derived from
the router.

**Counting routes is itself a trap, which is why `UI-5` has four answers.** The shell's own route file
declares **44** uncommented paths (39 shell children plus four top-level), with a further eight inside a
commented-out legacy block that a naive count includes. Across all **48** route files (51 on 2026-11-01) there were **108**
non-comment path entries, counting parents, children, parameterised routes and redirects. Three current
pages say "~35 screens" and an archived review says 47 lazy routes. **State which unit you mean.**

### 3.9 Connectivity and errors

The connectivity service holds online and reachability signals, derives a degraded state with a reason,
and retries against the health probe. The error interceptor treats a zero status as unreachable and any
success as reachable; it does **not** handle 401, because the core is open and authentication is a
module. The banner announces itself assertively to assistive technology.

⛔ **The convention for an absent module is to explain, never to toast.** A 503 from a module that is not
installed disables the affordance and says so in place. It is the same reasoning that makes navigation
*hide* an absent module's entries rather than leave them to fail.

### 3.10 Settings

Two surfaces with confusingly similar names. The **settings drawer** is a slide-out **launcher**: pure
navigation, no requests. The **settings pane** is the master-detail surface at `/settings/<section>`,
switching content on the route parameter, with **17 sections** (2026-11-01): config, connections, notifications, operational database, scheduler,
egress, approvers, modules, incident governance, spaces, access, models, icons, map, link analysis, transfer
and design.

### 3.11 The two panes this area owns

**Requirement intake.** A component kind with four kinds and a three-state lifecycle: submitted, then
accepted or rejected, then delivered. **Submission is deliberately open** and only the triage and
delivery transitions are capability-gated — Business must be able to ask without being granted anything.
Fail-closed everywhere else: no write root is a 503, a bad body a 422, a duplicate id a 409, an
out-of-lifecycle transition a 409.

**Reconciliation and Breaks.** Three stateless compute routes accept either a saved reconciliation or an
inline draft. 🔴 **The Break lifecycle is SERVER-side operational state since R2-03 (operator, 2026-09-26 —
reversing the C9 "client-side by contract" decision).** Under C9 the browser merged each run into the stored
breaks and wrote the list back **inside the reconciliation component's own document** through the
`canAuthorWorkbench` component PUT, so an operations-only user could never record a run or resolve a Break.
Now `POST /recon/{id}/record` (the server computes every Break of the saved reconciliation — no 200-row page —
and merges: resolutions and **`firstSeenAt`** carry forward, a vanished break auto-closes) and
`POST /recon/{id}/breaks/status` (resolve / re-open) are gated **`canOperateRuns`** and write
`<write-root>/recon-state/<id>.json`; `GET /recon/{id}/state` and `GET /recon/state` read it. The config
carries no run state at all. `POST /recon/promote` stores an **Incident**. A scheduled job runs the same
reconciliation on a cron, records its run the same way, and opens a deduplicated incident when breaks
appear. Detail: [`reconciliation.md`](../../module/reconciliation/user-guide-reconciliation.md).

## Changes

- 2026-09-07: the UI-8 row corrected — it had claimed in-flight, uncommitted work.
