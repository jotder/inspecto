---
type: Capability
area: UI
title: Surfaces & Lenses (UI) — capability spec
description: The requirement-of-record and as-built specification for the SPA shell — the three Lenses and how they differ from server capabilities, navigation and the Menu Builder, routing, the design system and its token gate, theming, accessibility, connectivity and error handling, settings, and what the server publishes for the shell to read.
status: current
written: 2026-09-08
supersedes-rows: REQUIREMENTS §3.15 UI-1..UI-8 (this file corrects them, see §2)
---

# Surfaces & Lenses (`UI`)

> **How to read this file.** §1–§2 are the *requirement of record*: where they disagree with
> `docs/REQUIREMENTS.md` §3.15 or `docs/EDITIONS.md`, **this file wins** and the disagreement is stated
> in place. §3 is the as-built specification, §4 the dated decisions, §5 what is not built, §6 what was
> refused or superseded, §7 the pointers, §8 how it is verified.
>
> ⛔ **The area is *Surfaces & Lenses*, not "operator console UX".** `docs/GLOSSARY.md` §14 renamed it:
> *console* is not canonical, and a `ui/` directory would collide with `inspecto-ui/`. The rows are the
> **shell** — Lens switching, navigation, the design system, theming, accessibility, the responsive
> sweep. Individual panes belong to the capability whose data they show.

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
  ([`security/security.md`](../security/security.md)). This area consumes the capability list; it does
  not decide it.
* **The API envelope, versioning and error bodies** → `API`
  ([`control-api/control-api.md`](../control-api/control-api.md)).
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
| **UI-8** | Settings drawer + consolidated admin settings pane | Should | ✅ SHIPPED (row corrected 2026-09-07 — it had claimed in-flight, uncommitted work). ⚠ The drawer and the pane are **two different components with near-identical names**: the drawer is a navigation launcher that makes no request; the pane is the master-detail surface, with **12** sections | All |

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
| **Capabilities** | `bootstrap.session.capabilities`, ten names published by the server | Constrains **which Lenses are selectable**, and hides or disables **controls inside a visible pane** | `LensService` computeds, called by each pane |
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

⚠ **Only three of the four feature flags filter navigation** — geo/link, events and ops. The exchange
flag gates in-pane affordances only and never hides an entry. Do not write "all four".

🔴 **The per-request `permissions[]` array is structurally unreachable, and this area is why.** The
envelope carries a per-resource permission set, and the version-one interceptor unwraps every response
to its data field, **discarding permissions, links and metadata before any feature service sees the
response**. The type is declared client-side and no consumer exists. Three earlier specs recorded this
as "shipped with no consumer"; the sharper statement is that **the shell's own interceptor makes a
consumer impossible** without changing it first.

### 3.3 What the server publishes for the shell

`GET /bootstrap` aggregates six sections behind one ETag'd conditional request: the edition string,
feature flags, config specs, enumerations, the space list and the session. Its stated purpose is to
replace a handful of startup round-trips.

⚠ **The SPA reads about half of it.** Its own bootstrap interface takes the edition, four feature flags,
the session and the auth block — and nothing else. Config specs are still fetched **per type** from the
spec route, and the space list still comes from the spaces routes, so the aggregation's benefit is only
partly realised.

🔴 **The edition string is inert twice over.** It is computed from the auth-mode flag — `personal` when
that is unset, `standard` otherwise — and that flag is a **label with nothing branching on it**. So a
Personal bundle started with single sign-on configured reports `standard`, regardless of which modules
are present. And the SPA **types the field without ever reading it**. The trustworthy signals are the
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

### 3.8 Routing

The root redirects per Lens. Two guest routes and one public share route sit outside the shell; the
rest hang off a shelled route with the login guard and an initial-data resolver, with roughly 39 lazily
loaded children. Breadcrumbs are a shared component fed by each detail pane rather than derived from
the router.

**Counting routes is itself a trap, which is why `UI-5` has four answers.** The shell's own route file
declares **44** uncommented paths (39 shell children plus four top-level), with a further eight inside a
commented-out legacy block that a naive count includes. Across all **48** route files there are **108**
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
switching content on the route parameter, with **12 sections**: config, connections, notifications,
operational database, scheduler, spaces, access, models, icons, map, transfer and design.

### 3.11 The two panes this area owns

**Requirement intake.** A component kind with four kinds and a three-state lifecycle: submitted, then
accepted or rejected, then delivered. **Submission is deliberately open** and only the triage and
delivery transitions are capability-gated — Business must be able to ask without being granted anything.
Fail-closed everywhere else: no write root is a 503, a bad body a 422, a duplicate id a 409, an
out-of-lifecycle transition a 409.

**Reconciliation and Breaks.** Three stateless compute routes accept either a saved reconciliation or an
inline draft. ⚠ **The Break lifecycle is client-side by contract**: after each run the browser merges the
new breaks with the stored ones, carrying a manually resolved break's status and note forward and
marking a break that has disappeared as auto-closed, then writes the merged list back **inside the
reconciliation component's own document** through the generic component route. There is **no Break store
and no resolve route**. A scheduled job runs the same reconciliation on a cron and opens a deduplicated
incident when breaks appear.

## 4. Decisions (dated one-liners)

| Date | Decision | Where |
|---|---|---|
| 2026-06-13 | The house template is adopted (**P-29**): Material, Tailwind, ag-Grid Community, Chart.js and G6, as a like-for-like port with redesign deferred — every target permissively licensed | recorded decision |
| 2026-07-02 | **One application with a persona Lens**, not three applications: surfaces are tagged Business/Builder/Ops behind a "View as" switcher that maps onto roles when the security module lands | product owner |
| 2026-07-02 | Forms are **schema-driven**: one renderer over server-published attribute specs with required/optional/advanced tiers | product owner |
| 2026-07-03 | ⛔ **Business sees the same navigation as everyone; only authoring actions are hidden.** No Lens-level nav filtering | product owner, confirmed before build |
| 2026-07-03 | **The default Lens is Builder**, not Business, so a fresh session is not unexpectedly read-only | — |
| 2026-07-03 | **Switching Lens does not reload the page** (unlike switching space), because a Lens never changes what data is fetched | — |
| 2026-07-03 | **Defence in depth over button-hiding**: on canvas and drag-drop surfaces, guard the *mutating method*, not the discoverable entry point | — |
| 2026-07-03 | Each Lens's home is simply **the first pane listed for it**, rather than a fresh product call | — |
| 2026-07-03 | The **Requirement lifecycle is deliberately minimal** — submitted, accepted or rejected, delivered — with no separate approver role and no timer | product owner |
| 2026-07-05 | The responsive sweep is run at two widths across every route and recorded as green; **a real-browser accessibility pass is deliberately not added**, to keep the pipeline browser-free | owner scope call |
| 2026-07-06 | Menu Builder decisions: a forward-compatible saved tree, **shared per space** (per-user awaits roles), the same sidebar with new groups, and a generic host route for placeable kinds | user |
| 2026-07-07 | **Branding is merged into the Spaces admin page**; the standalone branding pane and both its menu entries are removed | — |
| 2026-07-07 | The versioned-API migration is taken **in one slice** with the unwrap at a single interceptor seam, so every service keeps its signature and rollback is one function | user |
| 2026-07-14 | The **Access matrix** ships: per-space catalog, profiles and grants edited in one tree-table, **explicitly not a permission system**, with a root default of **allow** so an empty profile changes nothing | — |
| 2026-07-15 | The design review locks a **binding priority order**: usability, then information hierarchy, speed, learnability, accessibility, consistency, aesthetics | review complete |
| 2026-07-19 | Paging is **true offset paging** with an explicit "showing N, load more" — **never silently cap a list**; and chart tiles observe **the host element, not the canvas**, because observing the canvas feeds back on itself | — |
| 2026-07-20 | The pivot bar ships **scoped to switching the view** over the same selection, and no further; each host resolves the pivot from data it already has | — |
| 2026-07-23 | Role definitions become an authorable per-space document while **role assignment stays in the identity provider**; under single sign-on each capability additionally requires its granted name | — |
| 2026-08-16 | The onboarding **stage rail is deleted**, superseded by the editor's guided checklist | — |
| 2026-08-31 | Operator directive: **remove the offline facility** — the start script goes, the five demo seeds are **not** ported, and the 381 tests inside the mock are deleted with it | operator |
| 2026-06-16 | The accessibility work is **two halves**: an automated axe gate in unit tests, and a manual walkthrough whose **fixes are deferred to a separate pass**. The audit states it is a self-assessment, not a certification | the accessibility audit |
| 2026-06-16 | Seven axe rules are **disabled in jsdom** for stated reasons; contrast moves to the manual audit and the token gate | the axe harness |
| 2026-07-01 | The information architecture is reorganised produce → catalog → consume; the operational landing page is renamed to stop it colliding with Studio's dashboards, and Datasets are re-homed into the Catalog | operator brainstorm |
| 2026-07-03 | **Business is read-only observe**, enforced twice on a pane that needs it — hidden control *and* guarded method | product decision |
| 2026-07-07 | The root route no longer lands everyone on one page: it **redirects per Lens** | routing |
| 2026-07-25 | **Curating menus is split out of the authoring capability** — changing a menu is user-visible, not a build act | the menu routes |
| 2026-08-31 | The **offline mock backend is deleted**; the SPA requires a real control plane, and `UI-4` is superseded rather than descoped | `INDEX.md`, `REQUIREMENTS` `UI-4` |
| standing | **An absent module hides its navigation entries** and an absent capability explains itself in place — never a toast, never a blank pane | the navigation service, the AI affordances |
| standing | Static UI files are served **`no-cache`, not `no-store`**, with a synthesised validator — chosen after a stale-chunk failure | the control plane |
| standing | The shell's **module flags are derived from what actually registered**, never guessed from an edition string | the bootstrap builder |
| 2026-09-07 | `UI-8` corrected: it had claimed in-flight uncommitted work for a shipped pane | `BACKLOG.md` §5 |
| 2026-09-08 | The area is renamed **Surfaces & Lenses** with directory `surfaces/`: *console* is not canonical and `ui/` would collide with the project directory | `GLOSSARY.md` §14 |
| 2026-09-08 | This spec: `UI-3`'s conformance claim scoped to what the gate actually checks; `UI-5` reclassified from gate to provenance; `UI-1`'s "capability-gated panes" corrected to say nothing enforces it structurally; the inert edition string and the unreachable permission array recorded | this file §2 |

## 5. Not built

### 5.1 Tracked (a `docs/BACKLOG.md` row exists)

This area has **almost no board presence** — one row, and it is bookkeeping:

| Item | Row |
|---|---|
| Requirement-board edition and MoSCoW reconciliation, which fixed `UI-8` on 2026-09-07 | §5 *REQUIREMENTS MoSCoW / edition columns* |

That thinness is itself worth noting: eight requirements, a 300 KB documentation tier and a 334-file
test suite, with one board row between them. Everything in §5.2 is consequently new.

### 5.2 UNTRACKED — found 2026-09-08, no board row yet

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `CONSUMER-PAIRS-1`, `SPEC-STALEREF-1`, `SPEC-COUNTS-1`, `SPEC-GLOSSARY-1`, `SPEC-ORPHANPAGE-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`superpower/post-consolidation-sprints.md`](../../../superpower/post-consolidation-sprints.md) §Sprint 2.

1. 🔴 **No capability guard and no structural directive exist.** Gating is per-pane and by convention, so
   a new authoring surface that forgets its check is caught by nothing. A route guard, a directive, or a
   lint rule would make the convention enforceable. This is the area's highest-value hardening.
2. 🔴 **The per-request permission array cannot reach a consumer** because the shell's interceptor
   discards it with the rest of the envelope. Either the interceptor surfaces it or the server stops
   computing it — but the current state means three specs' worth of "no consumer" findings all trace to
   one line here.
3. 🔴 **The `docs/wiki/` tier holds one 35 KB unbuilt design specification for a UI shell that does not
   exist** — a docking system with anchor rails, floating panels, a telemetry dock and a copilot,
   calling itself the master architectural specification. Nothing in the code implements it, nothing
   cites it, it appears in no index, and it arrived on 2026-09-02 inside an unrelated commit. It is in
   the vocabulary guard's scope, so it is *maintained* while being unowned. Either adopt it as stated
   intent for this area or move it to history.
4. 🔴 **There is no responsive regression gate.** The claim rests on an archived review sheet, and the
   app has grown to 108 route entries since. Either a breakpoint sweep becomes a test, or the row says
   "audited once, at this date, over this many screens".
5. 🔴 **`GET /bootstrap` is half-consumed.** Config specs and the space list are still fetched
   separately, so the aggregation's own purpose is partly unmet. Either the shell reads what is served
   or the payload stops serving it.
6. 🔴 **The edition string is unreliable and unread.** It is derived from a label-only flag and no client
   reads it. Retire it, or derive it from what actually registered the way the feature flags are.
7. **The editions board has no row for this area**, so the shell's edition-neutrality is inferred rather
   than stated (§2).
8. **The accessibility audit's own coverage claim is stale in the conservative direction** — it names
   three primitives where the gate now spans 204 spec files. A stale doc that *understates* is still a
   doc that misleads, and it is the one a reader consults for the gate's scope.
9. **The route-level browser accessibility pass** — landmarks, contrast as rendered, reflow — is
   documented as future work in the audit's own out-of-scope section and tracked nowhere.
10. **The console naming decision is stale and nearly closable.** The stakeholder risk row says shipped
    documents and UI still call this surface "Inspector". The SPA's user-facing brand is "Inspecto" in
    both the page title and the logo; the survivors are **two lines of one reference document**, a Java
    package name, and a legitimate inspector *panel* concept. Re-scope the row to the package or close it.
11. **The mock-backend deletion's documentation ripple is unfinished** and spans areas — the Studio spec
    filed it after finding a named directory that no longer exists, and this area's `UI-4` is where the
    requirement side lives. One sweep, one row.
12. **The settings drawer and the settings pane share a name.** Two components, one navigation-only, one
    the real surface. A rename or a stated convention would stop the next reader conflating them.
13. **The design-system count is stated seven ways across the docs** (§3.5), and two lists omit components
    the same bundle documents. Point every row at the directory and the gallery instead of enumerating.
14. 🔴 **Two current documents tell a contributor to edit a file that does not exist.** The
    architecture page and the routing convention both name a navigation data file under a deleted mock
    directory — and the convention states it as the definition of done for adding a page ("two edits").
    The real file moved into the core navigation directory.
15. 🔴 **Three documents in this tier say navigation is filtered by Lens** (§3.4). The code, the
    product decision and the user guide all say it is not. This is the single most misleading claim in
    the area, because it describes a security-shaped behaviour that does not exist.
16. **The framework version is stated two ways** — two documents say Angular 21, the manifest says 22.1.1
    after an August upgrade. One of them is the accessibility audit, whose date is the reason.
17. **Nine shipped panes have no concept page**, two of them the very rows this area owns — requirement
    intake, and reconciliation with breaks — plus processing status, the notification centre, audit logs,
    expectations, decision rules, map settings and notification preferences. The feature index lists them
    as "not yet documented", and for the settings pane still says "in-flight, uncommitted": the exact
    stale phrasing that was corrected in the requirement board on 2026-09-07 and left here.
18. **Eleven shared components and six shared libraries are undocumented.** The design-system tier has
    twelve concepts for twenty-two units; the architecture page lists nine shared libraries where the
    tree has thirty-six directories. The agent render channel is documented only from the server side.
19. **The conventions tier's timestamps all predate the work that edited it.** Every convention page is
    dated 2026-06-28 or 2026-07-07, before the Access matrix, the design review, the chip, offset paging,
    the framework upgrade and the mock removal — all of which changed those files' bodies.
20. **The area's own board line is stale in the consolidation plan**: it credits two backlog rows that no
    longer exist and sizes the tier at 20 files and 38 KB, which is the conventions and design-system
    directories only — the tier is 61 files and roughly 373 KB, four fifths of it feature pages.
21. **The glossary's area table says "fifteen" over seventeen rows.** The plan states the reconciliation
    (fifteen areas, seventeen slots, one area holding two); the glossary prose is the only place the two
    numbers are conflated.

## 6. Refused & superseded

| Item | Verdict | Why / where |
|---|---|---|
| Offline mock-first operation (`UI-4`) | **Superseded 2026-08-31** | the mock backend was deleted; the SPA requires a real control plane |
| A toast for an absent module | **⛔ Refused** | the affordance explains itself in place; a toast is for a transient event, not a permanent state |
| Leaving an absent module's navigation entries visible-but-disabled | **Refused** | every path 503s, so the entry could only explain itself — it is hidden instead |
| `no-store` on static UI files | **Refused** | a stale-chunk failure motivated `no-cache` plus a validator, which keeps revalidation cheap |
| Guessing module availability from the edition string | **⛔ Refused** | flags are derived from what actually registered; the edition string is a label |
| Deriving the Lens from the server | **Not done, by design** | the Lens is presentation; the server publishes capabilities, and conflating the two would make a view preference a permission |
| Relying on a hidden control as a permission | **⛔ Refused** | a pane that needs read-only enforces it twice — hidden *and* method-guarded |
| Gating requirement **submission** on a capability | **Refused** | Business must be able to ask; only triage and delivery are gated |
| A dedicated Break store or resolve route | **Not built, by contract** | the Break lifecycle is a client merge persisted inside the reconciliation document |
| Curating menus under the authoring capability | **Split 2026-07-25** | a menu change is user-visible, not a build act |
| Colour contrast in the automated gate | **Refused (cannot run)** | jsdom does not paint; contrast lives in the manual audit and the token gate |
| Page-level axe rules against component fixtures | **Refused as meaningless** | they belong to a route-level browser pass |
| Hardcoded colour anywhere outside five files | **⛔ Refused by gate** | with one inline escape hatch, checked first in the pipeline |
| The commercial component suite the first console was built on | **Superseded 2026-06-13** | it is commercial; every replacement target is permissively licensed, and the port completed |
| Two alternative component stacks considered with it | **Rejected** | one was "not the product look", the other more theming glue |
| Per-route Lens tagging on navigation | **⛔ Declined outright, not deferred** | with nav filtering off the table it had no consumer |
| **Lens as a permission** | **⛔ Standing refusal** | a Lens is self-selected and freely switchable; a role is assigned and enforced |
| A visual-theme rework | **Non-goal** | density and tokens are already right, and aesthetics is last in the priority order |
| The grid suite's paid tier, a global store library, any new heavy dependency | **Non-goal** | |
| A compact/comfortable density toggle | **Non-goal** | uniform density is fine until a user asks |
| Turning the space switch into a routed switch instead of a reload | **Refused** | the reload is a deliberate isolation boundary, not a defect |
| Sorting in the tree table | **Deliberate omission** | it would break parent-child order |
| The grid's tree, row-group and master-detail features | **Not used** | only the community tier ships, so the flatten is hand-built |
| Standalone routes for six settings sections, and standalone run- and job-detail pages | **Removed** | context preservation — they open inside the pane or as a side panel over the live list |
| Favourites on the server | **Refused** | favourites are personal; they are never written to the shared tree |
| A separate registry pane | **Superseded** | folded into the Catalog's usage view, with a redirect |
| The old group names for operations, data origins and cubes | **Renamed or dissolved** | one collided with Studio's dashboards, one used a banned term, one used a verb |
| *Issues* as a label | **⛔ Banned** | retired by the object-mail rewrite; two current pages still carry it (§5.2) |
| A per-screen unreachable toast, per-screen authentication gating, per-feature capability helpers | **Refused** | the global banner and the interceptor seam own it |
| Hand-rolled tone-class helpers and status-tinted background fills | **Refused, build-failing** | the token gate |
| Template-driven forms in new code, and a global store library | **Refused** | reactive forms, and signals in a root service |
| Restyling, auditing or guarding the vendored shell and auth directories | **Out of scope** | |

## 7. As-built pointers

| Concern | Code | Docs |
|---|---|---|
| Lenses + capability computeds | `inspecto-ui/src/app/inspecto/api/lens.service.ts`; `inspecto-ui/src/app/layout/common/lens-switcher/lens-switcher.component.ts` | [`routing-and-navigation.md`](../../frontend/conventions/routing-and-navigation.md) |
| Session, flags, capabilities | `inspecto-ui/src/app/inspecto/api/session.service.ts`, `auth.guard.ts`, `v1.ts`, `v1.interceptor.ts`; `inspecto-ui/src/app/inspecto/access/access-state.service.ts` | [`api-and-data.md`](../../frontend/conventions/api-and-data.md); `SEC` §3 |
| Navigation + Menu Builder | `inspecto-ui/src/app/core/navigation/navigation.service.ts`, `navigation-data.ts`; `inspecto-ui/src/app/inspecto/menu/menu.service.ts`, `menu-api.ts`; `inspecto-ui/src/app/modules/admin/menu/menu-builder.component.ts` | [`routing-and-navigation.md`](../../frontend/conventions/routing-and-navigation.md) |
| Routing + shell | `inspecto-ui/src/app/app.routes.ts`, `app.config.ts`, `app.routes.spec.ts`; `inspecto-ui/src/app/layout/layout.component.ts` | [`architecture.md`](../../frontend/architecture.md) |
| Design system | `inspecto-ui/src/app/inspecto/components/` (22 units); `inspecto-ui/src/app/inspecto/grid/index.ts`; `inspecto-ui/src/app/inspecto/data-table/`; `inspecto-ui/src/app/inspecto/tree-table/tree-table.component.ts`; `inspecto-ui/src/app/inspecto/query/`; `inspecto-ui/src/app/inspecto/rule/rule-save.dialog.ts`; gallery `inspecto-ui/src/app/modules/admin/design-system/design-system.component.ts` | [`design-system/index.md`](../../frontend/design-system/index.md) + 12 component pages |
| Theming + the colour gate | `inspecto-ui/src/app/inspecto/theme/`; `inspecto-ui/tools/check-design-tokens.mjs`; `.github/workflows/ui.yml` | [`design-system-tokens.md`](../../frontend/conventions/design-system-tokens.md) |
| Accessibility | `inspecto-ui/src/app/inspecto/testing/a11y.ts`, `vitest-setup.ts` | [`accessibility.md`](../../frontend/conventions/accessibility.md); the audit at `docs/ui/accessibility-audit.md` |
| Connectivity + errors | `inspecto-ui/src/app/inspecto/api/connectivity.service.ts`, `error.interceptor.ts`; `inspecto-ui/src/app/inspecto/components/connectivity-banner.component.ts` | [`errors-and-connectivity.md`](../../frontend/conventions/errors-and-connectivity.md) |
| Settings | `inspecto-ui/src/app/layout/common/settings-drawer/settings-drawer.component.ts`; `inspecto-ui/src/app/modules/admin/settings/settings.component.ts`, `settings.routes.ts` | — (gap, §7 note) |
| Space scoping | `inspecto-ui/src/app/inspecto/api/space.interceptor.ts`, `spaces.service.ts` | [`multi-space.md`](../../frontend/conventions/multi-space.md); `SPC` |
| Server side of the shell | `inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java`, `CapabilityManifest.java`, `Roles.java`, `SettingsRoutes.java`, `NavRoutes.java`, `RequirementRoutes.java`, `ReconRoutes.java`, `ControlApi.java` (static serving, CORS) | `API` §3; `SEC` §3 |
| Reconciliation client lifecycle | `inspecto-ui/src/app/inspecto/reconciliation/reconciliation-types.ts`, `reconciliations.service.ts`; `inspecto-ui/src/app/modules/admin/reconciliation/recon-board.component.ts` | — (gap) |

**Gap rows.** This is the **largest documentation tier in the repo and the one with the most holes**: 61
files and roughly 373 KB, four fifths of it feature pages, of which the five largest (60 KB, 42 KB,
37 KB, 36 KB, 25 KB) are all authoring surfaces owned by other areas and none is shell material. Against
that: **nine shipped panes have no page**, including this area's own requirement-intake and
reconciliation rows; **eleven shared components and six shared libraries are undocumented**; the agent
render channel is described only from the server side; and the `docs/wiki/` file (§5.2) is 35 KB of
design for a shell nobody built, in no index. The shell's own truth has **no concept file** — it is
spread across four root files, nine conventions and thirteen component pages, which is why this spec had
to be assembled from the code rather than distilled from a page.

**Archive cited as authority** (history tier, never maintained): the rule-builder design (called a
"north-star"), the tree-table design ("as-built"), the design review (the authority for dialog
conventions, the keyboard layer and pivots), the incident-mail design, the reconciliation and
requirement-intake review sheets (the lifecycle decisions of record), the Access-config design, and the
frontend completion plan (the origin of the personas section and of the read-only rule the shipped code
implements). 🔴 **Two are load-bearing and cited by nothing**: the hardening review sheet, which is
the sole evidence for `UI-5`'s claim, and the first console's plan, which holds the only surviving detail
of the component-suite refusal and which the consolidation plan assigns to this file's §6.

## 8. Verification

* **Pointer check** — a capability-pointer check over this file (⚠ **the checker is NOT in this repo** — it was a session scratch script (recorded 2026-09-09 as `TOOL` §5.2 item 1). Until it is committed to `tools/`, re-derive the check by grepping this file's backticked paths and class names against `git ls-files`.).
* **The pipeline is the gate**, in order: the colour-token check, formatting, three type-check
  configurations, the test suite with coverage, a coverage floor, then the production build. The colour
  check runs **first** deliberately — it is the cheapest and the most often tripped.
* **Test suite** — 334 spec files, of which 29 are shell or design-system scoped, run through the
  framework's own test builder. No skipped or excluded tests exist anywhere in the suite. The recorded
  coverage baseline is statements 73.82%, branches 70.04%, functions 62.41%, lines 77.77%.
* **Falsify, don't read** — three claims worth probing directly. Serve a bundle with no UI directory and
  confirm an unknown path is a JSON 404 rather than a shell. Start a Personal bundle with single sign-on
  configured and confirm `bootstrap.edition` says `standard` while the feature flags still say what is
  installed. And add a new capability-gated route without a client check: nothing will fail, which is
  finding 1 of §5.2.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, from the repo root. ⚠ Stage a new document (`git add -N`) before
  trusting a green vocabulary run: the local guard reads tracked files only.
