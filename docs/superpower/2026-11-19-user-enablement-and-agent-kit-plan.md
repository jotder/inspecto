# User enablement and the Agent Kit — docs, training and AI agent skills

> **Status: APPROVED 2026-11-19 — D-UE1…D-UE7 and D-UE9 signed (operator, the recommended options); D-UE8 (web
> hosting) open and not blocking; P0 done (the names are in `GLOSSARY.md` §1-A and §12).** **Built by 2026-11-20:
> P1 lane A, P2, P3 and AK-1 (§8); P4 in flight; P5 waits on D-UE10; AK-2 next on the Agent Kit; both publish places
> ready as one manifest line each (`handbook.publish`, D-UE8 / D-UE10).** **Developer track added 2026-11-19
> (operator): §3.1, phases P3 and P5, D-UE10 open.** Board rows:
> `USER-HANDBOOK-1`, `USER-TRAINING-1`, `AGENT-KIT-1` (§11).
> **Operator choices, 2026-11-19 (in chat):** wave 1 serves **all three tracks at once** (console builders, partner
> developers, operators and admins); users read the docs as **in-app Help plus a static site**, generated from the
> hubs; AI agents act through an **MCP endpoint inside the server, as an optional module**; the first wave is packaged
> and tested for **Claude, Gemini, OpenAI and local / offline models**.
> Inventory checked against the repository on 2026-11-19; every "exists / missing" below names where it was found
> or what was searched.

## 1. The problem

Inspecto's documentation is organised the way the product is built, not the way it is used.

| What exists | Where | Why it does not answer a user |
|---|---|---|
| 118 product hubs, one per Product Map Item, each with slot files | `docs/okf/product/<kind>/<name>/` | right for the people who build Inspecto; a user asks "how do I build a fraud App", not "which hub" |
| 98 `user-guide-*`, 89 `admin-guide-*` and 53 `api-*` pages | spread across those hubs (`git ls-files`, 2026-11-19) | no order, no reading path, no statement of who each page is for |
| `USER_GUIDE.md` (783 lines) | root canon | screen by screen (Getting around, Business, Operations, Platform, Settings…), not task by task; already stale — it says the build ships two Space Templates, where Basic ships 19 and Standard adds 10 (`offerings/*.toon`) |
| Three journeys: file → dashboard, alert → closed Case, safe config change | `layer/product/user-guide-journey-*.md` | the right shape, but three, and nothing joins them into building one App |
| `user-guide-onboarding.md` | `layer/product` | mixes the user's steps with engineering seams and gotchas |
| The builder kit: Studio, extension points, Control API, the App kit (`tools/inspecto-app.mjs`) | `layer/platform/user-guide-builder-kit.md` | developer-only; no console path from a working Space to a shipped App |
| A 41-episode Inspecto LA video series, all rendered 2026-11-19 (Advanced screens not recorded; operator review open), made with `tools/record-la-live.mjs` and `tools/la-training/` | `module/la-core/user-guide-training-*.md`, `LA-TRAINING-SERIES-1` | Link Analysis only; the platform has no training |
| No in-app Help | no `help` route in `inspecto-ui/src/app/app.routes.ts` | a user in the console cannot reach any of the above |

And for AI agents there is no way in:

- **No MCP endpoint.** An archived decision kept MCP out of the core (`archived-documents/consolidated-2026-06-13/08-Decisions.md`:
  "no MCP/ADK dep in ring-1"); the in-product intelligence pack keeps MCP tools off (`InspectoPackConfig`).
- **A machine identity can do almost nothing.** OIDC client credentials are admitted per Space
  (`Roles.admitMachine`), but `Roles.MACHINE_CAPABILITIES` holds exactly one capability, `canOpenInboundInvestigation`
  (operator decision B1, 2026-11-01).
- **An agent cannot act as the person who runs it.** A token from any client listed in `auth.oidc.clients` is
  classed as a machine (`MachineTrust.machineClient`), so a second interactive client — an agent signing in as a
  person — would lose that person's Roles.
- **No console path from a Space to an App.** The public API has Metadata Bundle export / import (`/bundle/*`) and
  promotion (`/promotion/preview`, `/promotion/apply`), but no route that turns a Space into a Space Template or an
  App; Apps are made with the App kit CLI only.

## 2. The goal — measurable

1. **A user of each track finds the start of their task in two clicks** from the console's Help.
2. **"Your first App" takes a new user from nothing to an installed, upgraded and operated App in one working day**,
   timed with a real new user (not the author).
3. **Every tutorial and how-to page is checked against a running install** on each release (§4.4); a page that fails
   its check fails the docs gate.
4. **Each supported agent — Claude, Gemini, OpenAI and one local model — passes the golden task suite (§7.5)** at a
   pass rate the operator sets in D-UE9, with zero safety failures: no write without a dry-run, no approval granted
   by an agent, no secret or unmasked value returned.

## 3. The organising model — one lifecycle, three tracks, four page types

A business application on Inspecto is an **App** (`GLOSSARY.md`: content as Space Templates, its own landing, nav and
personas, golden data, required Add-ons, a version). Everything a user learns serves one lifecycle:

**Understand → Build → Ship → Manage → Develop.** *(Develop replaced Extend on 2026-11-19, §3.1.)*

| Stage | Console builder | Partner developer | Operator / admin |
|---|---|---|---|
| **Understand** | Concepts: Space, Dataset, Pipeline, Collector, Expectation, Alert Rule, Incident, Case, Widget and Dashboard, Space Template, App, Edition, Add-on | + Product, Industry, Function pack, extension points | + editions, topologies, sign-in and Roles |
| **Build** | connect data (*Add data*, Connections, Collectors) → shape (Pipelines) → check (Expectations) → analyse (Queries, Studio, Link Analysis) → detect (Alert Rules, Real-time Detection, Scoring, Anomaly) → respond (Incidents, Cases, Workflow & SLA, Action Requests) → present (Dashboards, reports) | the App kit (`new`, `validate`, `test`), golden data and ground truth, Function packs, Java extension points, the Control API | — |
| **Ship** | Metadata Bundle export / import, promotion preview / apply; *(gap: a Space → Space Template / App in the console, §4.5)* | App packages: package, sign, install, upgrade, remove into a running install; Product definitions and entitlements | install a bundle by edition; the deploy kit (reverse proxy, preflight, upgrade, bundle verification, off-site backup) |
| **Manage** | runs, Expectation breaches, the App's Incidents and Cases; a safe config change | App upgrades across Platform versions, conformance | sign-in, Roles, Access Policies, masking, backup and restore, upgrades, `/metrics`, audit verification, retention and legal hold |
| **Develop** | — | the developer lifecycle on Inspecto (§3.1) | — |

A fourth, light track — **business users** who read Dashboards and work Cases — reuses the Manage pages of the
builder track. The **AI agent** is a fifth reader, served by the Agent Kit (§7), which links into the same pages.

Every page is exactly one of four types (Diátaxis): **Tutorial** (learning by doing, on the one worked example, §5.1),
**How-to** (one task, assumes the concepts), **Reference** (complete, generated where a contract exists), **Concept**
(why and how it fits). A page that mixes types is split.

### 3.1 The developer track — Develop *(added 2026-11-19, operator)*

Developers read Inspecto along their own lifecycle — **Requirements → Architecture → Design → Build → Test → Release**
— and come in two kinds, so the Develop branch has two halves with one shape:

```
5. Develop
├─ On Inspecto — partner and customer developers             (external: ships in /help)
│   5.1 Requirements  what an App declares: Industry, canonical Datasets, Add-ons, minimum
│                     edition, the conformance it must pass
│   5.2 Architecture  the platform on one page: data plane, control plane, editions, the
│                     extension points, where an App plugs in
│   5.3 Design        design an App: map to canonical Datasets, Function pack, Space Template
│                     layout, personas → Roles, its Expectations and Alert Rules
│   5.4 Build         the App kit (new, validate), Pipelines as configuration, the Control API,
│                     Java extension points (parsers, connectors, Steps, Job types)
│   5.5 Test          golden data and ground truth, conformance, `inspecto-app test`, dry-runs,
│                     testing across editions
│   5.6 Release       package, sign, version and Platform compatibility range, upgrades
└─ Inside Inspecto — Inspecto's own engineers                (internal render only)
    5.7 Requirements  REQUIREMENTS.md · Product Map items · tagged BACKLOG rows
    5.8 Architecture  the architecture-<hub> pages · the platform layer
    5.9 Design        plans in docs/superpower · spec pages · signed decisions
    5.10 Build        CLAUDE.md conventions · the backend, UI and endpoint skills
    5.11 Test         the tests-<hub> pages · the affected-modules protocol · test idioms · CI guards
    5.12 Release      BRANCHING.md · the release workflow · generated release notes · compliance evidence
```

- **On Inspecto** replaces the earlier *Extend* stage; the partner developer column's Build and Ship cells above
  are written as its pages 5.4–5.6. Most of it is new writing — today only `user-guide-builder-kit.md` exists. Three pages are new:
  *the platform on one page* (with a diagram), *design an App* and *test your App*; the builder kit is split into the
  Build and Test pages.
- **Inside Inspecto** is mostly **generated**, because the hub slots already map onto the lifecycle — `spec-` →
  requirements detail and design, `plan-` → design, `architecture-` → architecture, `tests-` → test, `api-` →
  reference (`git ls-files`, 2026-11-19: 106 spec, 63 plan, 81 architecture, 60 tests, 53 api pages). The view is an
  index by stage × product area built from the Product Map, plus six hand-written entry pages (one per stage) that
  say how a requirement becomes a released feature here.
- **Audience flag.** Every manifest entry is `external` or `internal`. The `/help` in customer bundles and the public
  site render external pages only; an internal render for the team adds *Inside Inspecto*. Internal pages name open
  gaps, board rows, repository paths and maturity scores, so they never reach a customer (§4.3).

### 3.2 The Handbook's table of contents *(added 2026-11-19, operator)*

The manifest (§4.2) renders this tree. Chapters follow the lifecycle; each leaf is one page of one type, written into
the hub of the item it explains.

```
Inspecto Handbook
├─ Start here
│   ├─ What Inspecto is, and the editions on one page
│   ├─ Pick your track: Builder · Partner developer · Operator · Business user
│   └─ Your first App in a day (the worked example, §5.1)
├─ 1. Understand — concepts
│   Space · Dataset · Connection & Collector · Pipeline · Expectation · Alert Rule ·
│   Incident & Case · Widget & Dashboard · Space Template · App · Edition & Add-on
├─ 2. Build — console builder
│   2.1 Bring data in    add a file · watch a folder · SFTP / database / object store · a stream
│   2.2 Shape it         Pipelines, mappings, enrichment
│   2.3 Trust it         Expectations, quarantine, duplicates
│   2.4 Analyse it       Queries, Studio, Link Analysis, Geo map
│   2.5 Detect           Alert Rules, real-time detection, Reconciliation, scoring, anomaly, screening
│   2.6 Respond          Incidents, Cases, Workflow & SLA, four-eyes approval
│   2.7 Present          Dashboards, scheduled reports, sharing
├─ 3. Ship
│   3.1 Builder          export / import a Space, promote a change safely
│   3.2 Partner          App kit, golden data, package and sign, install and upgrade
│   3.3 Operator         install an edition, reverse proxy, preflight, upgrade
├─ 4. Manage
│   4.1 Run your App     runs, data-quality breaches, Incidents, safe changes
│   4.2 Run the platform sign-in, Roles, Access Policies, masking, backup / restore,
│                        upgrades, monitoring, audit verification, retention
├─ 5. Develop            the tree in §3.1: On Inspecto (external) · Inside Inspecto (internal render)
├─ Reference (generated) API · configuration · glossary · edition matrix · screen reference (USER_GUIDE.md)
└─ Troubleshooting       symptom → cause → fix
```

**Reuse:** the 98 existing user guides are mapped into this tree (§5.4); the three journeys become tutorial and
how-to pages; the LA video series is the Link Analysis track (§6); the builder kit feeds 5.4–5.5; `USER_GUIDE.md` is
the screen reference.

**One template per how-to:** *Goal* · *Before you start* (edition and role needed) · *Steps* · *Check it worked* ·
*What can go wrong* · *Next*. `tools/check-handbook.mjs` (§4.3) checks that every how-to page has these six headings.

## 4. The Handbook — the user's view, without a second docs tree

### 4.1 Pages stay in the hubs

The information base underneath — page size, headers, one home per fact, history out of current pages — is
restructured and maintained by [`2026-11-19-information-restructure-and-maintenance-plan.md`](../archived-documents/plans-archive/2026-11-19-information-restructure-and-maintenance-plan.md).

Every page is still written into the hub of the item it explains, in the existing slots: `user-guide-<topic>` for
tutorials, how-tos and concepts, `admin-guide-<topic>` for operator pages, `api-<topic>` for reference. No new docs
tree and no new slot (`CLAUDE.md`, three tiers).

### 4.2 The Handbook is a manifest plus a renderer

- **A navigation manifest** (`handbook.toon`, location D-UE2) lists chapters in lifecycle order and, for each page:
  its hub file, page type, track(s), stage, the editions it applies to and its audience (external or internal,
  §3.1).
- **Edition badges are derived**, never typed: the renderer reads `offerings/*.toon` and the module-by-edition matrix,
  so a page cannot claim the wrong edition (the sales docs held several such stale claims, corrected on 2026-11-19 in
  `e7fcc8076` and `07bbe2bb8`).
- **A renderer** (`tools/render-handbook.mjs`) builds static HTML with search: (a) into the bundle, served by the
  console at `/help`, so it works air-gapped, with a `?` on each screen linking to that screen's page; (b) the same
  output, publishable on the web (hosting: D-UE8).
- **Generated reference:** the HTTP API from `docs/api/openapi-v1.json` (627 operations, 2026-11-19), the vocabulary
  from `GLOSSARY.md`, the edition matrix from `offerings/*.toon`, the module list from the Product Map.
- `USER_GUIDE.md` becomes the Handbook's **screen reference** chapter, not its front door; `ADVANCED_GUIDE.md` feeds
  the operator track. Both stay root canon.

### 4.3 Guards

`tools/check-handbook.mjs`: every manifest entry resolves to a tracked hub page; every page has exactly one type;
no `internal` page — and no link to one — appears in the external render, and the bundle build refuses one;
every tutorial and how-to has a check (§4.4); no page outside the manifest claims a track; the vocabulary and link
guards cover the rendered output.

### 4.4 Docs as tests

Each tutorial and how-to carries machine-checkable steps — API calls with expected results, or UI checks in the
form the LA series already uses — run against a seeded demo install by the same recorder machinery
(`tools/record-la-live.mjs`). A page whose product changed fails its check instead of rotting silently.

**The format (P2 lane E, built 2026-11-19; specification:
[`spec-handbook-doc-tests.md`](../okf/product/layer/product/spec-handbook-doc-tests.md)).** One fenced block is one
step, under the step it proves; a page's blocks run in order and share `save`d values:

````markdown
```check
POST /spaces/{space}/jobs/o2c_detect/trigger
as analyst
expect status 202
save data.runId as run
```
```check
GET /spaces/{space}/jobs/runs/{run}
as analyst
expect data.status = "SUCCESS"
retry 30 every 2s
```
```check-ui
{"open":"/data"}
{"waitText":"o2c_orders"}
```
````

- ```` ```check ```` — a Control API call: `METHOD /path` (relative to `/api/v1`), then `as <persona>`, `body <json>`,
  `expect status …` / `expect <path> <op> [value]` (`=` `!=` `>` `>=` `<` `<=` `has` `matches` `exists` `absent`),
  `save <path> as <name>`, `retry <n> every <d>`. ```` ```check-ui ```` — console steps, one JSON per line, in the
  recorder's episode verbs.
- **Readers** see one quiet folded line (*Checked on every release · …*) with the step under it.
- **`tools/check-handbook.mjs`** (pre-commit) parses every block, matches its route against `openapi-v1.json`, refuses
  a variable used before its `save`, and refuses a tutorial or how-to with no block (§4.3).
- **`tools/run-handbook-checks.mjs --base-url <url> [--page <id>] [--space <id>] [--ui]`** runs them: per page pass or
  fail with the failing expectation and the answer, exit 1 on any failure; UI steps only with `--ui` (else NOT RUN,
  counted). Sign-in never comes from the page: a persona's token from `INSPECTO_HANDBOOK_TOKEN_<NAME>` or a
  credentials file, else the Demo Auth exchange; Basic's 503 means no header.
- **Where it runs: on demand against a demo install and before every release — not in CI yet.** No CI job boots an
  install (the reactor jar is thin; the bundle bake is release-only), the checks need a seeded demo build with Demo
  Users, and no page carries a check yet. A nightly is revisited when T1–T6 carry checks.

### 4.5 Product gaps found while writing

A how-to that cannot be written because the product has no path becomes a board row (`kind: feature`), never a
workaround page. The first one is known now: **turning a working Space into a Space Template and an App from the
console** — today only the App kit CLI can (§1). It goes on the board in plan phase P2, the first phase that needs it.

**Found writing T5 and T6 (2026-11-19), each run on two Standard installs — filed 2026-11-20 as `APPS-UPGRADE-CONSOLE-1`,
`SPACE-EXPORT-CLEAN-1`, `KEYCLOAK-REALM-BLUEPRINT-1`, `SEED-ADMIN-ALERT-RULE-1`, `RESTORE-NEW-SPACE-1` and
`UPGRADE-BACKUPDIR-RELATIVE-1`; the App kit's reach and the create-time refusal as noted below:**

- **The App kit reaches no customer.** `tools/inspecto-app.mjs` reads the source tree (products, offerings, module
  manifests); no bundle or download carries it. T5 says "run from the Inspecto developer kit folder", which does not
  exist as a deliverable yet.
- **App conformance misses a create-time refusal.** `test --bundle` builds its scratch Space by copying files, so it
  passed an App whose template `POST /spaces` then refused (an Alert Rule over a Job-written Dataset with no file yet).
  T5 copies the shipped template's empty `seed.parquet` to avoid it.
- **No console for Apps or a Space's upgrade.** Install, upgrade and remove (`/apps`) and a Space's template upgrade
  (`/origin/upgrade`) are API-only; so is `/audit/verify`.
- **A Space is not cleanly exportable as a template.** Copying its folder carries Space-only files (`egress.toon`, which
  an upgrade refuses to write, backup Jobs, the `maintenance_backups` Dataset); T5 tells the reader to remove them.
- **The shipped Keycloak realm blueprint does not work as is** with Keycloak 26: no audience mapper, no `basic` scope,
  the default `account` roles add a second audience that fails the audience check, redirect URIs for
  `inspecto.internal` / `:4200` only, no users, and a file name the import does not pick up. T6 lists the five fixes.
- **The seed `admin` Role cannot create a Space from a template with an Alert Rule** (needs `canAuthorAlertRules`);
  T6 assigns `admin` and `power`.
- **The backup guide's "restore into a new Space" is refused by the path jail** (`target_dir ../../<new>/config` lies
  outside the Job's Space) unless `-Dassist.safety.roots` names the spaces root, which the guide does not say. T6
  shows the in-place restore.
- **`upgrade.ps1` takes a relative `-BackupDir` from the wrong folder.** It changes to the bundle's parent before
  checking the backup, so `.\spaces\…` is not found and nothing is upgraded; it also leaves the shell there. T6 gives a
  full path and changes back.
- **Found writing the P3 how-tos (2026-11-20), filed on the board:** `PARSER-SPI-KIT-1` (the Parser extension
  point has no starter and is missing from the builder kit page), `MACHINE-CLIENT-CONSOLE-1` (machine clients set by
  API only; a machine may hold only `canOpenInboundInvestigation`), `APPS-MULTI-SERVER-1` (`/apps` acts on one server
  of a set), `BACKUP-DATA-1` (the backup archives configuration, not Datasets; seed `admin` cannot run Jobs),
  `HANDBOOK-CLI-CHECK-1` (no check kind for a command, so the App kit pages check only the server side); builder track:
  `ADD-DATA-ENTRY-1` (no menu entry for Add data), `PIPELINE-INBOX-FIELD-1` (no inbox-folder field; Collectors cannot
  be created on their page), `DB-CONNECTION-CONSOLE-1` (no console database Collector; Test proves TCP only),
  `QUARANTINE-RELEASE-1` (no action for a quarantined file), `REALTIME-CONSOLE-1` (no form for windowed or pattern
  rules or a stream trigger), `RISK-SCORE-JOB-1` (a Risk Score needs a hand-made Job), `CASE-REASSIGN-CONSOLE-1` (no
  re-assign, no *Create case* on an Incident), `PIPELINE-SAVE-HELD-TOAST-1` (the editor says *Saved* for a held
  change), `HOWTO-DOC-DRIFT-1` (internal pages that contradict the code); from the live run, `JOB-PARAMS-NESTED-IGNORED-1` (a
  Job saved with its settings nested under `params` keeps them as one text value and runs with defaults). Still true
  from T5/T6: the App kit reaches no customer, no console for `/apps`, `/origin/upgrade` or `/audit/verify`, the
  relative `-BackupDir` of `upgrade.ps1`.
- **Filed 2026-11-20 by the developer-pages lane (P3, 5.1–5.6):** the App kit's reach above, as
  `APP-KIT-DELIVERY-1` (with the Java scaffolds `tools/scaffold.mjs`, also source-tree only), and the create-time
  refusal above folded into `APP-CONFORMANCE-BEYOND-FINDINGS-1` — conformance holds only findings-Dataset keys, never an
  App's Expectations, Alert Rules, Incidents or Case Rules; and `INDUSTRY-CANONICAL-DATASETS-1` — no Industry declares
  the canonical Datasets `GLOSSARY.md` says it owns (`industry.toon` is id, title, pack), so pages 5.1 and 5.3 tell
  authors to name Datasets after the Industry pack's record types. `app.toon` has no edition and no persona key; the
  pages say the editions follow from `requires.addons` and the personas are the template's `config/roles.toon` — no gap.
- **Filed 2026-11-20 by the P4 lane (§6.1), closed the same day:** `TRAINING-BUNDLE-1` — no bundle rendered the
  `training` pages, so the Labs and the instructor kit reached no install. Now the Preview and every demo build render
  `/help` with `--training` (§6.1, D-UE7 ruling).
- **Not proven live:** the reverse proxy itself (no nginx or Caddy on the test machine; the preflight's config check
  passed), the OS-service install and the service-mode upgrade, and the Linux `.sh` scripts.

### 4.6 P1 as built — lane A (2026-11-19)

P1 runs as two parallel lanes against one contract: lane A (the manifest, the renderer, the checker — below) and lane
B (the console's Help entry and `?` screen links, and the bundle shipping the render at `help/`).

- **The manifest** `docs/okf/product/layer/product/handbook.toon` (D-UE2): the 15 chapters of §3.2 in order — `start`,
  `understand`, `build_bring_data_in`, `build_shape`, `build_trust`, `build_analyse`, `build_detect`, `build_respond`,
  `build_present`, `ship`, `manage_app`, `manage_platform`, `develop`, `reference`, `troubleshooting` — each with a
  title, a nav group and a summary, and its pages keyed by id with `file`, `type` (tutorial, howto, reference, concept,
  or `unsorted` for an existing page not yet reviewed), `tracks` and `audience`. The ids are a **contract**: the
  console's help links use them. Seeded with all 187 hub user and admin guides, each `type: unsorted` and
  `audience: internal`, mapped to a chapter by its hub (66 solution-pack guides under `start`), plus the three
  root-canon guides the bundle already ships (`EDITIONS.md`, `USER_GUIDE.md` as the screen reference,
  `ADVANCED_GUIDE.md` as the operations guide) as the only `external` pages.
- **Why internal by default:** `BUNDLE-LAYOUT-1` (operator, 2026-11-06) ships only operator-facing documents, named one
  by one, because the hubs are written for the team. Marking a hub page `external` puts it in every bundle at `/help`,
  so it is a deliberate per-page decision — the review P2 and P3 make page by page as they type and rewrite pages.
- **`tools/render-handbook.mjs --out <dir> [--internal]`**: a dependency-free Markdown converter (headings, tables,
  lists, fenced code, blockquotes, links, emphasis; raw HTML escaped, comments and frontmatter dropped, images as alt
  text) and a static site — home, one page per chapter and per page, `search.json` with inline search, light and dark
  themes, relative links only. A link to a page outside the render becomes plain text. Today: external 20 files (3
  pages), internal 207 files (190 pages).
- **`tools/check-handbook.mjs`** (CI guard, so pre-commit): the contract chapter ids; unique ids, tracked files, each
  file once, valid type / tracks / audience; every hub user or admin guide placed; the six how-to headings on every
  `howto` page; and the external render, built in memory, holding no internal page, no dead relative link and no
  foreign entry in `search.json` — falsified with a renderer made to leak (28 findings, exit 1).
- **Not in P1:** edition badges (P3); the inventory as reviewed types (P2 / P3 as pages are rewritten); the internal
  render's publication place (D-UE10).
- **P2 lane C — concepts (2026-11-19):** 14 new pages, each `type: concept`, `audience: external`, filed in the hub of
  the item it explains as `user-guide-concept-<topic>.md` and placed first in the `understand` chapter (ids
  `concept_*`): the map (*How the pieces fit*), Space, Space Template (`module/config`); Dataset, Widget and
  Dashboard, App, Edition and Add-on (`layer/product`); Connection and Collector, Pipeline and Steps, Expectation
  (`module/elt`); Alert Rule (with Expectation and Decision Rule), Alert / Incident / Case (`addon/incidents`); Roles
  and Access Policies (`module/access`); Link Analysis and Investigations (`module/la-core`). Each says what it is, why,
  its neighbours (ASCII diagram), the console path and the edition (from `EDITIONS.md` and `offerings/*.toon`), and
  links only to external pages. Edition facts are prose, never a Basic / Standard / Advanced table (docs-health
  `editions` ratchet). The LA training series is named, not linked: its pages are still internal. External render:
  17 pages.

### 4.7 P3 as built — edition badges and the generated reference (2026-11-20)

- **Edition badges, derived.** `tools/handbook-editions.mjs` maps a hub page (`docs/okf/product/<kind>/<name>/`) to its
  Product Map Item and reads the Item's **first** edition (editions are cumulative): a module from its manifest
  (`offeringRole` base → Basic) or the first Offering that resolves it; an Add-on, a Space Template or an industry pack
  from the Offerings that list it (an industry pack through the module that ships `industry-packs/<pack>/`); an App from
  its required Add-ons; a Product from `products/*.toon` `editions`; a layer from its modules. The renderer shows the
  sold editions from that one on (*Standard · Advanced*); *Preview* alone for the demo build.
- **Declared only where nothing derives, or to narrow.** A page's `edition:` in `handbook.toon` is needed for the three
  root-canon guides (Basic), the demo authenticator (Advanced — the demo build is an Advanced build with the OIDC jars
  swapped, `offering-classpath.mjs`), the feature-off LA showcase (Preview — in no Offering) and hosted model providers
  (Standard — an extra jar no Offering lists); and it narrows pages that sit in a Basic hub but need more: the five
  tutorials T1–T6 (the Order-to-cash template, sign-in), the Roles and Access Policies concept and Workflow & SLA
  (Standard), the LA training's Advanced chapter (Advanced). `check-handbook.mjs` (check 7) refuses a page with no
  edition, an unknown one, or one earlier than the derived one — a page cannot claim an edition its item does not ship
  in. Narrowing stays a reviewer's call: a hub page whose subject needs a later edition than its module is caught by
  reading it, not by the derivation (the verification found three).
- **Generated reference.** `tools/render-handbook-reference.mjs --write | --check` writes three `reference` pages in
  `layer/product`, each with the *Generated by* banner (docs-health exempt): **`api-reference-http.md`** — all 627
  operations by tag, with a *Not before* edition only where it is a fact: the route is registered by an optional
  module (found by `render-api-routes.mjs`'s scanner, `routeModules`), so no build below that module's first edition
  has it (111 operations, all *Standard and up*). A route a base module or the host registers gets no claim, because
  the feature behind it can still need a later edition (`/access/policies` answers everywhere but Access Policies are
  Advanced; `/assist` answers 503 on Basic) — the first draft said *every edition* there and the verification refused
  it. **`api-reference-editions.md`** — Products, Apps, Add-ons, Space Templates and industry packs, modules × Basic /
  Standard / Advanced, through the same derivation as the badges. **`api-reference-vocabulary.md`** — 249 glossary
  terms, each the first sentence of its definition: a term is a paragraph that opens with bold names (wrapped dated
  notes and `(of …)` qualifiers allowed), kept once; dated notes, links, board ids and repository names (Java
  classes, packages, doc paths) are dropped. CI's Handbook guard runs `--check`; a change to the contract, the
  glossary, the Offerings or the Product Map regenerates them.
- **Not built:** configuration-key and CLI reference (§3.2 names *configuration*; no single key catalogue exists to
  generate from yet — `ConfigSpecs` and `ADVANCED_GUIDE.md` §9 are candidates); a per-operation *licensed* edition
  (needs each route to declare the capability or Add-on it serves); the contract still carries the test-only
  `/test-discovered/ping`, so the page lists it.

## 5. Content — wave 1, all three tracks

### 5.1 One worked example, start to finish

Every tutorial builds the same App, so a learner ends with one complete thing. Recommended (D-UE3):
**Order-to-cash assurance**, grown from the `orders-starter` Space Template:

orders file + payments file → Pipelines → Expectations (amount ≥ 0, no orphan payments) → Reconciliation of orders
against payments → Breaks → an Alert Rule on unmatched value → Incidents → a Case with four-eyes approval →
a Dashboard → packaged as an App → installed on a second server → upgraded to version 2 → operated (runs, backup,
audit verification).

It is industry-neutral, file-based like most buyers, runs on Standard, and touches every Build, Ship and Manage step.
Its data is synthetic and generated with ground truth, like the LA showcases.

### 5.2 Tutorials (six)

| # | Tutorial | Track | Edition |
|---|---|---|---|
| T1 | Your first Space and Dataset | builder | Basic |
| T2 | Make it a Pipeline with Expectations | builder | Basic / Standard |
| T3 | Detect and respond: Reconciliation, Alert Rules, Incidents, Cases | builder | Standard |
| T4 | Present it: a Dashboard for the business user | builder | Basic |
| T5 | Ship it: Space Template → App package → install and upgrade | partner developer (builder once §4.5 ships) | Standard |
| T6 | Run it: install, sign-in, backup, upgrade, audit verification | operator | Standard / Advanced |

### 5.3 How-to library — the first 30

Ranked by how often the task comes up. Builder: add a file; watch a folder; connect SFTP or a database; build a
Pipeline from a sample; add an Expectation; handle quarantined rows; reconcile two Datasets; write an Alert Rule;
detect in real time on a stream; score entities; screen names against an Entity List; open and work a Case; build a
Dashboard; schedule a report; explore links between entities; change a Pipeline safely. Partner developer: create an
App with the kit; add golden data and conformance; package and sign; install into a running server; upgrade an App;
add a parser through an extension point; call the Control API with a machine client. Operator: install an edition;
connect the identity provider; define Roles and Access Policies; put Inspecto behind a reverse proxy; back up and
restore; upgrade; verify the audit chain.

**As built (2026-11-20).** Each how-to is a `user-guide-howto-<topic>.md` (builder, partner) or
`admin-guide-howto-<topic>.md` (operator) page in the hub of the item it explains, `type: howto`, `audience: external`,
manifest id `howto_<topic>`, with the six template headings and at least one check block. Every console label, route,
flag and message was read in the code (the fact sheets were checked against `inspecto-ui` and the route handlers); the
checks were run on 2026-11-20 against a seeded install (below). Edition
badges are derived (§4.7); three pages in Basic hubs are narrowed to Standard in the manifest — install an App,
upgrade an App, Roles and Access Policies.

- **Partner and operator — 14 done:** in `layer/platform`, chapter `ship`: `howto_create_an_app`,
  `howto_golden_data_conformance`, `howto_package_and_sign_an_app`, `howto_install_an_app`, `howto_upgrade_an_app`,
  `howto_install_an_edition`, `howto_reverse_proxy`, `howto_upgrade_the_platform`; chapter `manage_platform`:
  `howto_connect_the_identity_provider` (`module/oidc`), `howto_define_roles_and_access_policies` (`module/access`),
  `howto_back_up_and_restore` (`module/backup`), `howto_verify_the_audit_chain` (`module/event`); chapter `develop`:
  `howto_add_a_parser` (`module/elt`), `howto_machine_client` (`module/oidc`). The machine-client page says plainly
  that a machine client may hold only `canOpenInboundInvestigation` today.
- **Builder — 16 done:** chapter `build_bring_data_in`: `howto_add_a_file` (`layer/product`), `howto_watch_a_folder`
  (`module/elt`), `howto_connect_sftp_or_a_database` (`module/connectors`); `build_shape`:
  `howto_pipeline_from_a_sample` (`module/elt`); `build_trust`: `howto_add_an_expectation`,
  `howto_handle_quarantined_rows` (`module/elt`); `build_analyse`: `howto_explore_links` (`module/la-core`);
  `build_detect`: `howto_reconcile_two_datasets` (`module/reconciliation`), `howto_write_an_alert_rule`
  (`addon/incidents`), `howto_detect_in_real_time` (`module/realtime`), `howto_score_entities` (`module/scoring`),
  `howto_screen_names` (`module/screening`); `build_respond`: `howto_open_and_work_a_case` (`module/case-management`);
  `build_present`: `howto_build_a_dashboard` (`layer/product`), `howto_schedule_a_report` (`module/analytics`);
  `manage_app`: `howto_change_a_pipeline_safely` (`module/elt`). Where the console has no path the page says so and
  shows the configuration or API way: windowed real-time rules, re-assigning a Case, turning four-eyes on, a database
  Collector (console: the PostgreSQL snapshot only). A scheduled report is a Dataset export (CSV or Excel), not a
  Dashboard.
- **The checks, run (2026-11-20): 29 of 30 pages pass, twice in a row** (a create answers 200 or 409, and the Case
  page opens a new Case each run, so every page re-runs). Install: an Advanced demo bundle built from origin/master 43410b8e3
  (`package.ps1 -Edition Advanced -Product inspecto -Distribution demo -NoBakeDemo -SkipBootCheck`) on :8093, a
  roster-only Space whose Demo Users `builder` and `admin` hold `admin;power` (what the pages tell readers to hold),
  and `POST /spaces` of `order-to-cash` with samples; T1–T4 run first, as a reader would. Each page makes what it
  checks through the same API its console step calls (a Connection, a Risk Score and its Job, an Entity List, a
  report Job, a Role, the admitted machine client), all on the worked example's data (`o2c_customer_risk` scores
  C-007 at 100). The App pages need what the App kit makes off the server: a starter App `o2c-assurance` signed
  with `o2c-signing-1`, installed, then version 2.0.0, with `--space o2c-live` (a Space made from it) for install
  and upgrade. **Not run here:** *connect the identity provider* asserts OpenID Connect sign-in, which a Demo Auth
  build does not have — it needs a Standard install on a real provider, as T6 was run. The real-time page checks
  the refusal on the worked example (no stream there); the parser page checks the built-in catalog.
- **Fixed by the run:** the parser page named `/jobs/packs` and `/parsers` without the Space prefix; the upgrade
  page had no check for applying the Space upgrade; the report page now says where the file lands
  (`config\exports\<id>`); the screening page says a different legal form (*Ltd* / *Limited*) scores about 0.66; the Case page opens
  its own Case instead of using up one T3 left open. The parser, real-time and Connection checks prove the route
  and its answer, not the reader's own outcome (no pack jar, no stream, no SFTP server on the test machine).

### 5.4 Concepts (about fifteen) and migration

Concept pages for the terms in §3's Understand row. Then the existing pages are mapped into the manifest: each of
the 98 user guides (the three journeys, the onboarding guide and the builder kit among them) gets a type, track and
stage, or is split. The
inventory table is P1's first deliverable.

## 6. Training materials

- **Learning paths**, one per track (and a short one for business users): ordered tutorials, labs, videos and a
  self-check at the end of each stage.
- **Labs:** Space Templates seeded as starting states (`lab-*`), each with a checker that reads the learner's result
  through the API ("your Pipeline lands Dataset `orders` with ≥ 1,000 rows and an Expectation on `amount`").
  Which bundle carries them: D-UE7.
- **Videos:** a platform series "Build, Ship, Manage", one episode per tutorial step, made with the LA series'
  machinery (episode specs, narration checked against the live UI, `tools/record-la-live.mjs`). The LA series stays
  the Link Analysis track. An episode is recorded only when its screens pass their checks.
- **Instructor kit** for partner and SI workshops: slides, the lab guide, timings and answer keys.
- **Quick-reference cards:** one page per track.
- Certification is deferred until a partner asks (`hold: demand` inside `USER-TRAINING-1`).

### 6.1 P4 as built — wave 1 (2026-11-20)

- **A third audience, `training`** (D-UE7). `tools/render-handbook.mjs --training` renders the external pages plus
  the `audience: training` ones; the default (bundle) render and `--internal` are unchanged, and `check-handbook.mjs`
  counts a training page in the external render as a leak (tests in `render-handbook.test.mjs`,
  `check-handbook.test.mjs`; `docs-health.mjs` accepts the header value). An external page's link to a training
  page renders as plain text there and as a link in the training render.
- **Learning Paths** — four external `concept` pages in `layer/product`, first in chapter `start` (ids `path_builder`,
  `path_partner`, `path_operator`, `path_business`): stages Understand → Build → Ship → Manage, each an ordered list of
  existing tutorials, how-tos and concepts (no new teaching text) and a self-check with its answers; every answer was
  read in the page it comes from.
- **Labs 1 and 2** — `training` tutorials, starting state the `order-to-cash` Space Template itself (no `lab-*`
  template: the worked example's template is the starting state of both, and the plan's `lab-*` templates would ship
  in every edition — every bundle carries the whole committed gallery, operator decision 2026-09-25). *Trust the
  feeds* (`module/elt`, after T2): three Expectations of the learner's own, graded by `POST …/expectations/<id>/evaluate`
  (no single-Expectation GET exists) — kind, target, column, result, 16 violations for `lab_orders_over_800`. *Exposure
  per customer* (`addon/incidents`, after T3): an Alert Rule per customer at ≥ 300 (C-007 620, C-016 480, C-011 310)
  and the C-007 Incident accepted; read-only checks. An Incident's title and description do not carry its rule's
  name, so the checker finds them by the description's words *threshold of 300* and pins them by `attributes.alertKey`.
  **Run live** on an Advanced demo build of `11ccf32d4` (Demo Users `builder` and `ana`, both `admin` and `power`):
  both checkers green on the learner's Space, red on a fresh Space (404 / rule not found) and on a wrong threshold (0
  Incidents found); `builder` graded the learner's *closed* Space. The learner's work was done through the API the
  console forms call — the console labels come from the grounded how-tos, not from a driven console.
- **Instructor kit** (`layer/product/user-guide-instructor-kit.md`, `training`, `reference`): three workshop formats,
  the builder day's timings, the room set-up, a slide outline, the lab guide, both answer keys.
- **Video series spec** (`layer/product/spec-video-series-build-ship-manage.md`): 31 episodes, one per tutorial step
  (B1–B15, S1–S8, M1–M8), the LA episode card, the recording gate, and the LA machinery reused as is (the `episode`
  recorder, the series voice, the scene renderer). Nothing recorded. Two blockers: the tutorials carry no `check-ui`
  blocks, and the recorder captures a browser only (S and M are mostly terminal work).
- **Which bundle carries them (`TRAINING-BUNDLE-1`, closed 2026-11-20).** `inspecto/package.ps1` asks
  `tools/check-bundle-handbook.mjs --audience` which render `/help` gets: `training` when the Edition is Preview
  (any Distribution) or the Distribution is `demo` (Advanced or Preview), `external` for Basic, Standard and Advanced
  production and trial builds. It then judges the rendered folder (`--bundle`: the `.handbook` marker, every training
  page there exactly when the rule says so, never an internal page) before the zip. The guard's no-argument mode (CI
  `guards`) pins package.ps1's render block to the rule and renders both flavors for real; tests in
  `check-bundle-handbook.test.mjs`. The "training bundle" of D-UE7 is the existing demo build — no new Distribution
  (operator ruling 2026-11-20).
- **Not done:** quick-reference cards, more Labs, the episodes.

## 7. The Agent Kit — Claude, Gemini, OpenAI and local models

### 7.1 Three layers

1. **Tools — the MCP endpoint inside the server** (operator choice). An optional module (`inspecto-mcp`, a
   `RouteModule` loaded by `ServiceLoader`) that serves the MCP Streamable HTTP transport (JSON-RPC 2.0) **natively on
   the JDK `HttpServer` with the existing JSON codec — no MCP SDK, so the locked reactor dependency set is
   unchanged.** That keeps the intent of the archived "no MCP dependency in the core" decision while meeting the
   operator's choice. The MCP specification revision is pinned when AK-1 starts (it moves; verify then).
2. **Know-how — Agent Skills** in the open `SKILL.md` format (Agent Skills, now read by Claude Code, OpenAI Codex
   and Gemini CLI, §References), written model-neutral, one per job (§7.3).
3. **Packaging — thin per-vendor adapters** generated from the one Kit (§7.4).

### 7.2 What the endpoint offers, and its safeguards

- **Task-level tools, curated, about 30–40** — not one tool per route (627 operations would drown any model). Each is
  defined against the OpenAPI contract and generated where it maps one-to-one. Examples: `list_spaces`,
  `describe_dataset`, `query_dataset` (read-only SQL through the existing SQL guard), `validate_config`,
  `dry_run_pipeline`, `save_draft`, `run_pipeline_now`, `get_run`, `list_incidents`, `get_incident_brief`,
  `propose_action`, `export_bundle`, `preview_promotion`, `validate_app`, `edition_and_modules`.
- **Resources:** the glossary, the Space's catalog (Datasets, Schemas, Pipelines), the edition and module list.
- **Every write is validate → dry-run → Draft or change set.** Anything outbound, destructive or promoting goes
  through an **Action Request** that a human approves (four-eyes); an agent can propose, never approve (D-UE6).
- **Masking holds:** an agent reads masked values; a reveal stays a human, audited act. No tool returns a secret.
- **Every call is audited** with the person and the agent client both named.

### 7.3 Identity — two modes (decisions D-UE4, D-UE5)

- **Delegated person (recommended for interactive agents** such as Claude Code, Gemini CLI and Codex): the agent signs
  in as the person through the customer's identity provider (authorization code with PKCE, the MCP authorization
  model) and holds exactly that person's Roles. Needs a **separate allowlist of agent clients that stay persons**,
  because today any listed client is classed as a machine (`MachineTrust.machineClient`).
- **Machine (unattended agents):** client credentials, admitted per Space as today, with a new **agent capability
  set** — read capabilities plus Draft authoring, never approve, never reveal — added to the allowlist that holds one
  capability today (`Roles.MACHINE_CAPABILITIES`, operator decision B1). That widens an operator decision, so it is
  the operator's call.
- **Basic has no sign-in**, so the endpoint would be open to anyone who reaches the port. Recommended: the module
  ships from Standard (D-UE4).

### 7.4 Agent Skills — the first set

`build-an-app` (the orchestrator, following §5.1's lifecycle) · `onboard-data` · `author-pipeline` ·
`add-expectations` · `reconcile` · `author-alert-rules` · `build-dashboard` · `investigate-incident` ·
`work-a-case` · `explore-links` · `package-an-app` · `ship-and-upgrade` · `health-check` · `safe-config-change` ·
`explain-inspecto`.

Each skill: when to use it; the steps as tool calls; guardrails (check the edition first, dry-run before save,
propose rather than act); the canonical vocabulary; links into the Handbook page for each step. Generated parts — tool
names, the edition matrix, the vocabulary list — are written by a script from the contracts, and a guard fails the
build if a skill names a tool, route or term that does not exist.

The repo's own skills (`.claude/skills/pipeline-author`, `incident-investigate`, `case-analysis`) serve engineers
working in this repository and keep repo paths. The Agent Skills are written fresh for a running install, with no
repo paths, and link the same Handbook pages. `DOCS-SKILLS-ALERTS-AGENTIC-1` (repo skills for alert authoring and
agentic operation) is absorbed: its agentic-operation half becomes the Agent Skills above.

### 7.5 Per-vendor packaging

| Vendor | Package | Contents |
|---|---|---|
| **Claude** | a Claude Code plugin; skills also uploadable to Claude apps and usable from the Agent SDK | `skills/`, an MCP server entry pointing at the install's endpoint |
| **Gemini** | a Gemini CLI extension | `gemini-extension.json` with `mcpServers`, `skills/`, a short `GEMINI.md` |
| **OpenAI** | Codex configuration; a ChatGPT connector where the endpoint is reachable | `AGENTS.md`, the skills directory, the MCP server entry |
| **Local / offline** | the same Kit for an MCP-capable local client running an open-weight model, inside an air-gapped network; and, by D-UE9's companion choice, the in-product Assistant reading the same skill bodies | identical skills; no cloud dependency |

Exact file names and locations are verified against each vendor's documentation when AK-3 starts, and the versions
tested are recorded in the compatibility matrix (§7.6). The Kit ships inside the bundle (`agent-kit/`) and as
downloadable vendor packages, versioned with the product.

### 7.6 Evaluation — the only proof

- **A golden task suite of about twenty tasks** across Build, Ship and Manage, on a seeded Standard install with the
  §5.1 example. For example: "onboard `orders.csv` into a Dataset with an Expectation that amount ≥ 0"; "why did run R
  fail"; "add an Alert Rule for a 30 % drop in daily order value"; "package this Space as an App and validate it".
- **Graded by the platform's own state** read through the API — deterministic, not an LLM judge — plus **safety
  checks**: no write without a dry-run, no approval by an agent, no secret or unmasked value in any reply.
- **One compatibility matrix:** model, client, versions, pass rate, safety failures, date. Run on demand on a
  workstation with vendor keys, or fully offline with the local model; never inside the offline CI gate.

### 7.7 AK-1 as built (2026-11-20)

The design of record and the as-built facts live in the hub:
[`okf/product/module/mcp/spec-mcp.md`](../okf/product/module/mcp/spec-mcp.md). In short:

- **The MCP revision is pinned to `2026-07-28`** (verified 2026-11-20): stateless, no `initialize`, `server/discover`,
  per-request `_meta`, mirrored `Mcp-Method` / `Mcp-Name` headers. `2025-11-25` and `2025-06-18` are served
  statelessly for clients that still open with `initialize`; no session is ever minted.
- **No side door:** every MCP Tool is an in-process `ApiContext.replay` of an existing route as the caller, so the
  route's own policy, capability, module-switch and masking gates decide. The tool → route table is fixed in code;
  arguments are id-shaped and URL-encoded, never a route.
- **Identity:** AK-1 accepts any Subject the install's `Authenticator` resolves and refuses the endpoint (503) on an
  install with no sign-in. The delegated-person allowlist (`auth.oidc.agentClients`) and the RFC 9728 protected-resource
  metadata are designed in the hub and are AK-2's first item, before any write tool.
- **Audit:** one `mcp.tool.called` / `mcp.resource.read` row per call naming the person, the tool, the replayed route,
  its status and the (self-declared) agent client.
- No decision beyond §9 was needed: the endpoint holds no capability of its own (D-UE5: the person's own Roles), and an
  administrator can switch it off per Space through feature `mcp`.

## 8. Phases

| Phase | Delivers | Depends on | Size |
|---|---|---|---|
| **P0** | D-UE1…D-UE9 signed; the new names in `GLOSSARY.md` — **done 2026-11-19** (D-UE8 left open) | — | days |
| **P1** | Handbook skeleton: manifest, renderer, `/help` route with screen links, `check-handbook.mjs`, the inventory map of existing pages — **lane A done 2026-11-19 (§4.6); lane B (console and bundle) in its own lane** | P0 | 2–3 weeks |
| **P2** | Concepts; the worked example's data and Space Template; tutorials T1–T6 with their checks; the Space → App gap filed — **lane E (docs as tests: the check-block format, the guard, the reader's view, the runner) done 2026-11-19 (§4.4)**; **lane C (concepts) done 2026-11-19 (§4.6)**; **lane D (the worked example) done 2026-11-19: Space Template `order-to-cash`, its generator and ground truth, golden tests; version 2 and the gaps in the hub `solution-pack/order-to-cash`**; **T5 *Ship it* and T6 *Run it* done 2026-11-19 (hub `layer/platform`, chapters `ship` and `manage_platform`; gaps in §4.5)**; **tutorials T1–T4 (builder track) done 2026-11-19: `tutorial_first_space` (chapter *Start here* — the first page a new builder reads), `tutorial_pipeline_expectations`, `tutorial_detect_respond`, `tutorial_dashboard`; external, every step's console label driven in a live console, 31 check blocks green against a seeded demo install** | P1 | 4–6 weeks |
| **P3** | The first 30 how-tos; generated reference; edition badges on every page; the *On Inspecto* developer pages 5.1–5.6 (§3.1) — **edition badges and the generated reference done 2026-11-20 (§4.7)**; **developer pages done 2026-11-20: six external pages in hub `layer/platform` (`user-guide-develop-{requirements,architecture,design,build,test,release}.md`), first in chapter `develop` (ids `develop_*`, track partner): 5.1–5.3 `concept`, 5.4–5.6 `howto` with the six headings; 14 check blocks (the reused o2c ones were green in the T1–T4 and T5 runs; the new `/openapi.json`, `/bootstrap`, `/modules`, `/pipelines` and `/datasets/o2c_open_items/rows` ones are grounded in the route code, not yet run live); three gaps filed (§4.5). `user-guide-builder-kit.md` stays an internal page, not yet retired into 5.4–5.5**; **how-tos: all 30 done 2026-11-20 (§5.3; 29 of 30 checks green on a seeded demo install, the identity-provider one needs OIDC)** | P1 | 4–6 weeks, parallel with P2, plus 2–3 weeks for the developer pages |
| **P4** | Learning paths, the first two labs with checkers, the video series spec on the LA machinery, the instructor kit — **wave 1 done 2026-11-20 (§6.1): four Learning Paths, Labs 1 and 2 with checkers green on a demo build, the instructor kit, the video series spec, a `training` audience; episodes not recorded, quick-reference cards not written; `TRAINING-BUNDLE-1` filed and closed: the Preview and demo builds ship the training pages** | P2 | 3–4 weeks |
| **P5** | The *Inside Inspecto* internal render: the stage × product-area index generated from the Product Map and hub slots, six entry pages 5.7–5.12, the audience guard | P1, D-UE10 | about 1 week |
| **AK-1** | `inspecto-mcp`: transport, read tools, resources, audit; the delegated-identity design and a security review — **done 2026-11-20 (§7.7)**: module `features/inspecto-mcp`, Streamable HTTP pinned to revision `2026-07-28` (dual-era, stateless), 19 read MCP Tools and 3 resources each replaying an existing route as the caller, one audit row per call; design and as-built in the hub `module/mcp` | P0 | 3–4 weeks, parallel with P1 |
| **AK-2** | Write tools through validate → dry-run → Draft; `propose_action` through Action Requests; the agent capability set | AK-1, D-UE5, D-UE6 | 3 weeks |
| **AK-3** | The first fifteen Agent Skills, their guard, and the four vendor packages | AK-2, P2 (links) | 3 weeks |
| **AK-4** | The golden task suite, the compatibility matrix, the in-product Assistant reuse if chosen | AK-3 | 2–3 weeks |

The Handbook (P1–P4) and the Agent Kit (AK-1…AK-4) are independent until AK-3 links skills to pages; they can run as
two lanes. None of it reaches a customer until a release is tagged — the last is still `v3.12.0` (2026-06-05).

## 9. Decisions — signed 2026-11-19

The operator signed every recommended option on 2026-11-19 ("Sign recommended"). D-UE8 had no recommendation, so it
stays open; nothing needs it before P1's renderer exists. D-UE10 came later the same day with the developer track
(§3.1) and is open; P5 needs it.

| # | Question | Signed |
|---|---|---|
| **D-UE1** | The new names: **Handbook**, **Learning Path**, **Lab**, **Agent Kit**, **Agent Skill**, **MCP Tool**. *Assistant Tool* stays the in-product term (one word, one concept) | as listed, into `GLOSSARY.md` at P0 |
| **D-UE2** | Where the manifest lives, and whether pages carry their own type / track metadata | one manifest in the `layer.product` hub; no per-page metadata (98 files untouched) |
| **D-UE3** | The worked example | Order-to-cash assurance on `orders-starter` (§5.1) |
| **D-UE4** | Which editions carry `inspecto-mcp` | Standard and up; nothing on Basic (no sign-in) |
| **D-UE5** | Agent identity: the delegated-person allowlist, and the machine agent capability set (widens operator decision B1) | both; delegated first |
| **D-UE6** | May an agent save a low-risk change directly, or does every save go through a Draft and approval | every save is a Draft; promotion and outbound actions need a human approver |
| **D-UE7** | Which bundle carries the Labs | Preview and a training bundle only, not the sold editions. **Operator ruling 2026-11-20:** the training bundle is the existing demo build (`-Distribution demo`, Advanced or Preview) — no new Distribution; Basic, Standard and Advanced production and trial builds keep the external render (§6.1) |
| **D-UE8** | Where the static site is hosted on the web | **open** — the operator's call (external). **Ready (2026-11-20, operator: "keep it ready so that it can be updated easily"):** the place is one line, `handbook.publish.external` in `handbook.toon` (`none` until decided); `tools/publish-handbook.mjs` renders there once it is set |
| **D-UE9** | The golden-suite pass rate per model, and whether the in-product Assistant reuses the Agent Skills in wave 1 | pass rate set after the first run; Assistant reuse after AK-4 |
| **D-UE10** *(added 2026-11-19 with §3.1)* | Where the *Inside Inspecto* internal render is published for the team | **open** — recommended: built from the repository on demand (`tools/render-handbook.mjs --internal`) into a gitignored folder, never into a bundle or the public site; a team host only if the operator names one. **Ready (2026-11-20):** `handbook.publish.internal` in `handbook.toon` (`none` until decided); meanwhile `tools/publish-handbook.mjs` keeps the render current in the gitignored `target/handbook/internal` |

## 10. Risks

- **Breadth.** Three tracks at once is the operator's choice and risks thin coverage everywhere. Mitigation: the one
  worked example carries all three tracks, and the first-30 how-to list is fixed before writing starts.
- **Drift.** Stale edition and capability claims had to be corrected across five sales and edition docs on
  2026-11-19 alone (`e7fcc8076`, `07bbe2bb8`). Mitigation:
  derived edition badges, docs as tests, generated reference.
- **Agent writes.** Mitigation: Drafts, four-eyes, allowlisted capabilities, masking, attributed audit, and a
  security review before AK-2 ships.
- **Internal pages leaking to customers.** The *Inside Inspecto* half names gaps, board rows and maturity scores.
  Mitigation: the audience flag, a guard that refuses an internal page or link in the external render, and a bundle
  build that fails on one (§4.3).
- **Vendor formats move.** Adapters stay thin and version-pinned; the compatibility matrix records what was tested.
- **Air-gapped buyers cannot use cloud models.** The local path is a first-class target, not an afterthought.
- **Nothing ships without a release.**

## 11. Board rows

- `USER-HANDBOOK-1` (P2) — the Handbook: manifest, renderer, `/help`, concepts, tutorials, how-tos, generated
  reference, docs as tests, the Develop branch and its internal render (P0–P3, P5).
- `USER-TRAINING-1` (P3) — learning paths, labs, the platform video series, the instructor kit (P4).
- `AGENT-KIT-1` (P2) — the MCP endpoint, agent identity, Agent Skills, vendor packages and the golden suite (AK-1…AK-4).

§9 was signed on 2026-11-19, so none carries a hold. `USER-TRAINING-1` starts after `USER-HANDBOOK-1` reaches P2.

## References

- Repository, 2026-11-19: `docs/USER_GUIDE.md`; `docs/okf/product/layer/product/user-guide-journey-*.md`;
  [`user-guide-builder-kit.md`](../okf/product/layer/platform/user-guide-builder-kit.md);
  `module/la-core/user-guide-training-*.md` and `LA-TRAINING-SERIES-1`; `offerings/*.toon`; `docs/api/openapi-v1.json`;
  `Roles.MACHINE_CAPABILITIES` and `Roles.admitMachine` (`platform/inspecto-access`); `MachineTrust`
  (`providers/inspecto-oidc`); `GLOSSARY.md` (*App*, *Space Template*, *Assistant*).
- Agent Skills as a cross-vendor standard: [Anthropic — donating Agent Skills to the Agentic AI Foundation](https://www.anthropic.com/news/donating-agent-skills-to-the-agentic-ai-foundation) ·
  [agentskills.io](https://agentskills.io/home.md).
- Gemini CLI extensions (skills, MCP servers, `GEMINI.md`): [geminicli.com — extensions](https://geminicli.com/docs/extensions/) ·
  [extension reference](https://geminicli.com/docs/extensions/reference/).
- Cross-tool adoption (secondary, counts vary): [SKILL.md across Claude Code, Codex and Gemini CLI](https://dev.to/williamwangai/agent-skills-in-2026-how-to-customize-claude-code-codex-cli-gemini-cli-27i9).
