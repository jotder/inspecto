---
type: Capability
title: Surfaces & Lenses (UI) — capability spec — Decisions, not built, refused and verification
description: Part 2 of 2 of Surfaces & Lenses (UI) — capability spec: Decisions (dated one-liners); Not built; Refused & superseded; As-built pointers; Verification
status: current
item: layer.product
audience: internal
verified: 2026-11-20 last-edit
---

# Surfaces & Lenses (UI) — capability spec — Decisions, not built, refused and verification

Part 2 of 2 of [Surfaces & Lenses (UI) — capability spec](plan-surfaces.md) · previous: [Purpose, requirements and specification](plan-surfaces-scope-specification.md).

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
> ⚠ **None of these ids is an open board row today** (re-checked 2026-11-15, `DOCS-AGENT-WRITTEN-REVIEW-1`): `SPEC-STALEREF-1` closed 2026-09-09 when `tools/check-doc-citations.mjs` became a guard, `SPEC-NOPROOF-1` closed 2026-09-09 when all six Musts had tests, and the rest were closed, refuted or split into their own rows by the 2026-09-24 board consolidation. Their history: `docs/archived-documents/backlog-snapshot-2026-09-24.md` and `docs/archived-documents/plans-archive/post-consolidation-sprints.md`.
> ✅ **`CONSUMER-PAIRS-1` decided 2026-09-10 (operator, per row):** the SPA **sends `If-Match`** from both config-writing panes and gates affordances on `permissions[]` once the interceptor exposes it → `CLIENT-HALVES-1` (row closed; no longer on the board).
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

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
| A dedicated Break store or resolve route | **Built 2026-09-26 (R2-03, operator — reverses C9)** | the client merge wrote run state through the authoring PUT, so an operations-only user could never record a run; now `recon-state/` + the `canOperateRuns` record / status routes |
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
| Lenses + capability computeds | `inspecto-ui/src/app/inspecto/api/lens.service.ts`; `inspecto-ui/src/app/layout/common/lens-switcher/lens-switcher.component.ts` | [`routing-and-navigation.md`](spec-routing-navigation.md) |
| Session, flags, capabilities | `inspecto-ui/src/app/inspecto/auth/session.service.ts`, `auth.guard.ts`, `v1.ts`, `v1.interceptor.ts`; `inspecto-ui/src/app/inspecto/access/access-state.service.ts` | [`api-and-data.md`](spec-api-and-data.md); `SEC` §3 |
| Navigation + Menu Builder | `inspecto-ui/src/app/core/navigation/navigation.service.ts`, `navigation-data.ts`; `inspecto-ui/src/app/inspecto/menu/menu.service.ts`, `menu-api.ts`; `inspecto-ui/src/app/modules/admin/menu/menu-builder.component.ts` | [`routing-and-navigation.md`](spec-routing-navigation.md) |
| Routing + shell | `inspecto-ui/src/app/app.routes.ts`, `app.config.ts`, `app.routes.spec.ts`; `inspecto-ui/src/app/layout/layout.component.ts` | [`architecture-product.md`](architecture-product.md) |
| Design system | `inspecto-ui/src/app/inspecto/components/` (22 units); `inspecto-ui/src/app/inspecto/grid/index.ts`; `inspecto-ui/src/app/inspecto/data-table/`; `inspecto-ui/src/app/inspecto/tree-table/tree-table.component.ts`; `inspecto-ui/src/app/inspecto/query/`; `inspecto-ui/src/app/inspecto/rule/rule-save.dialog.ts`; gallery `inspecto-ui/src/app/modules/admin/design-system/design-system.component.ts` | [`design-system/index.md`](../../index.md) + 12 component pages |
| Theming + the colour gate | `inspecto-ui/src/app/inspecto/theme/`; `inspecto-ui/tools/check-design-tokens.mjs`; `.github/workflows/ui.yml` | [`design-system-tokens.md`](spec-design-tokens.md) |
| Accessibility | `inspecto-ui/src/app/inspecto/testing/a11y.ts`, `vitest-setup.ts` | [`accessibility.md`](spec-accessibility.md); the audit at `docs/okf/product/layer/product/tests-accessibility-audit.md` |
| Connectivity + errors | `inspecto-ui/src/app/inspecto/api/connectivity.service.ts`, `error.interceptor.ts`; `inspecto-ui/src/app/inspecto/components/connectivity-banner.component.ts` | [`errors-and-connectivity.md`](spec-errors-connectivity.md) |
| Settings | `inspecto-ui/src/app/modules/admin/settings/settings.component.ts`, `settings.routes.ts` — the ONE entry point since 2026-09-22; the header flyout `inspecto-ui/src/app/layout/common/settings-drawer/settings-drawer.component.ts` was **deleted** that day (mounted nowhere, UI consolidation plan D2), and `/connections`, `/config`, `/spaces`, `/design`, `/notification-center` now redirect into `/settings/<section>` | — (gap, §7 note) |
| Space scoping | `inspecto-ui/src/app/inspecto/api/space.interceptor.ts`, `spaces.service.ts` | [`multi-space.md`](spec-multi-space.md); `SPC` |
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

* **Pointer check** — a capability-pointer check over this file (`tools/check-doc-citations.mjs` — **committed 2026-09-09**, wired into `ci.yml` and `.githooks/pre-push`).
* **The pipeline is the gate**, in order: the colour-token check, formatting, three type-check
  configurations, the test suite with coverage, a coverage floor, then the production build. The colour
  check runs **first** deliberately — it is the cheapest and the most often tripped.
* **Test suite** — 553 spec files (5,820 tests, 5 skipped, 2026-10-15), run through the framework's own
  test builder. Coverage measured 2026-10-15: statements 81.51%, branches 76.64%, functions 71.25%, lines
  85.18% (first baseline 2026-09-08: 73.82% / 70.04% / 62.41% / 77.77%).
* **Falsify, don't read** — three claims worth probing directly. Serve a bundle with no UI directory and
  confirm an unknown path is a JSON 404 rather than a shell. Start a Personal bundle with single sign-on
  configured and confirm `bootstrap.edition` says `standard` while the feature flags still say what is
  installed. And add a new capability-gated route without a client check: nothing will fail, which is
  finding 1 of §5.2.
* **Guards** — `node tools/check-vocabulary.mjs`, `node tools/check-doc-links.mjs`,
  `node tools/check-gate-tally.mjs`, from the repo root. ⚠ Stage a new document (`git add -N`) before
  trusting a green vocabulary run: the local guard reads tracked files only.
