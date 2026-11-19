# User enablement and the Agent Kit — docs, training and AI agent skills

> **Status: APPROVED 2026-11-19 — D-UE1…D-UE7 and D-UE9 signed (operator, the recommended options); D-UE8 (web
> hosting) open and not blocking; P0 done (the names are in `GLOSSARY.md` §1-A and §12); nothing built; P1 and AK-1
> startable.** **Developer track added 2026-11-19 (operator): §3.1, phases P3 and P5, D-UE10 open.** Board rows:
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
restructured and maintained by [`2026-11-19-information-restructure-and-maintenance-plan.md`](2026-11-19-information-restructure-and-maintenance-plan.md).

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

### 4.5 Product gaps found while writing

A how-to that cannot be written because the product has no path becomes a board row (`kind: feature`), never a
workaround page. The first one is known now: **turning a working Space into a Space Template and an App from the
console** — today only the App kit CLI can (§1). It goes on the board in plan phase P2, the first phase that needs it.

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

## 8. Phases

| Phase | Delivers | Depends on | Size |
|---|---|---|---|
| **P0** | D-UE1…D-UE9 signed; the new names in `GLOSSARY.md` — **done 2026-11-19** (D-UE8 left open) | — | days |
| **P1** | Handbook skeleton: manifest, renderer, `/help` route with screen links, `check-handbook.mjs`, the inventory map of existing pages — **lane A done 2026-11-19 (§4.6); lane B (console and bundle) in its own lane** | P0 | 2–3 weeks |
| **P2** | Concepts; the worked example's data and Space Template; tutorials T1–T6 with their checks; the Space → App gap filed | P1 | 4–6 weeks |
| **P3** | The first 30 how-tos; generated reference; edition badges on every page; the *On Inspecto* developer pages 5.1–5.6 (§3.1) | P1 | 4–6 weeks, parallel with P2, plus 2–3 weeks for the developer pages |
| **P4** | Learning paths, the first two labs with checkers, the video series spec on the LA machinery, the instructor kit | P2 | 3–4 weeks |
| **P5** | The *Inside Inspecto* internal render: the stage × product-area index generated from the Product Map and hub slots, six entry pages 5.7–5.12, the audience guard | P1, D-UE10 | about 1 week |
| **AK-1** | `inspecto-mcp`: transport, read tools, resources, audit; the delegated-identity design and a security review | P0 | 3–4 weeks, parallel with P1 |
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
| **D-UE7** | Which bundle carries the Labs | Preview and a training bundle only, not the sold editions |
| **D-UE8** | Where the static site is hosted on the web | **open** — the operator's call (external) |
| **D-UE9** | The golden-suite pass rate per model, and whether the in-product Assistant reuses the Agent Skills in wave 1 | pass rate set after the first run; Assistant reuse after AK-4 |
| **D-UE10** *(added 2026-11-19 with §3.1)* | Where the *Inside Inspecto* internal render is published for the team | **open** — recommended: built from the repository on demand (`tools/render-handbook.mjs --internal`) into a gitignored folder, never into a bundle or the public site; a team host only if the operator names one |

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
