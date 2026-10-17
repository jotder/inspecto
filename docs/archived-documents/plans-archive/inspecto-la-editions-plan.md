# Inspecto LA — product name, editions, industry packs and third-party integration (`LA-EDITIONS-1`)

**Status: ARCHIVED 2026-10-17 — SHIPPED (all seven phases; §7 = as built and lane decisions; residuals on `LA-EDITIONS-RESIDUALS-1`).** When a phase ships: distil its
as-built facts into the matching OKF concept and `docs/EDITIONS.md`, move residuals to `docs/BACKLOG.md`, and archive
this file when the last phase ships.

## 1. Decisions (operator, 2026-10-17)

| # | Decision |
|---|---|
| ED-1 | The product is named **Inspecto LA**. First use in any document: "Inspecto LA (Link Analysis)". The capability inside the console, its routes, keys and modules keep the canonical term **Link Analysis** (GLOSSARY binding). |
| ED-2 | Editions are named **Basic / Standard / Advanced**, replacing Personal / Professional / Enterprise **across the whole Inspecto product** (⚠ assistant's reading of the operator's answer, recorded as an assumption — confirm before phase 2 renames build profiles). |
| ED-3 | A fourth edition, **Preview**, contains everything with demo (fake) sign-in; it already exists as a build flavour (`edition-preview`, `package.ps1 -DemoAuth`) and is never sold. |
| ED-4 | **Basic has no sign-in** and **no Investigations** (no sealed op log, replay or Dossier): it is the explorer. Evidence-grade work starts in Standard. |
| ED-5 | **Industry packs are bundled into the editions**, Basic included. First packs: **Telecom** and **Mobile Money** (both have demo data). |
| ED-6 | **Third-party integration** with case managers and fraud-analysis products, in **Standard and above**. Target products are not known yet, so a generic connector comes first; named adapters only on a customer's request. |
| ED-7 | Feature placement per §2, accepted as proposed (Value Measures and patterns stay in Basic; embed links in Standard). |

Editions remain **build flavours plus capability gates, never branches** (`docs/BRANCHING.md`). Mapping onto today's
build flavours: Basic = Personal (default build), Standard = Professional (`edition-standard` is already its alias),
Advanced = Enterprise, Preview = Preview.

## 2. Feature placement

Principle: never sell weaker analysis or weaker evidence. Editions differ by **who** (one analyst, a team, an
organisation), **how much data**, and **how much governance**.

| Area | Feature | Basic | Standard | Advanced | Preview |
|---|---|---|---|---|---|
| Explore | Graph studio, projections from any Dataset, saved views | ✅ | ✅ | ✅ | ✅ |
| | Find paths, neighbours, "Investigate a number", connect seeds | ✅ | ✅ | ✅ | ✅ |
| | Temporal and branching patterns, pattern packs | ✅ | ✅ | ✅ | ✅ |
| | Value Measures | ✅ | ✅ | ✅ | ✅ |
| | Geo map, routes, co-location | ✅ | ✅ | ✅ | ✅ |
| | Browser graph algorithms | ✅ | ✅ | ✅ | ✅ |
| | Entity Lists, identity resolution, Entity profile pages | ✅ | ✅ | ✅ | ✅ |
| | Reference data beside a node | ✅ | ✅ | ✅ | ✅ |
| | Export (PNG, CSV), snapshots (not evidence-grade) | ✅ | ✅ | ✅ | ✅ |
| | Link Analysis widgets on dashboards | ✅ | ✅ | ✅ | ✅ |
| | Industry packs (Telecom, Mobile Money) | ✅ | ✅ | ✅ | ✅ |
| | Masking | display only (no sign-in: anyone may reveal) | ✅ enforced | ✅ | ✅ |
| Investigate | Investigations: sealed op log, replay, compare, temporal findings | — | ✅ | ✅ | ✅ |
| | Dossier and Dossier bundle with verification | — | ✅ | ✅ | ✅ |
| | Templates, external references | — | ✅ | ✅ | ✅ |
| | Data trust: data strip, Files, coverage, delivery Alerts | — | ✅ | ✅ | ✅ |
| Team | Sign-in, members and roles, four-eyes, audited reveal | — | ✅ | ✅ | demo sign-in |
| | Drafts: parallel analysts, rebase, promote | — | ✅ | ✅ | ✅ |
| Share | Embed links (read-only Investigation view) | — | ✅ | ✅ | ✅ |
| | Case linking, Case history beside a node | — | ✅ | ✅ | ✅ |
| Scale | Server Graph Run (29 algorithms) | — | ✅ | ✅ | ✅ |
| | Link index, daily ingestion | — | ✅ | ✅ | ✅ |
| Operate | Alert Rules on Investigations, standing detection | — | ✅ | ✅ | ✅ |
| | Assistant: AI-proposed column mappings | — | ✅ | ✅ | ✅ |
| Integrate | Third-party integration (§4) | — | ✅ | ✅ | ✅ |
| Govern | Investigations on Postgres (multi-node, HA) | — | — | ✅ | ✅ |
| | Policy engine (ABAC; can only narrow) | — | — | ✅ | ✅ |
| | Scale-out, retention, legal hold, residency (not built yet) | — | — | ✅ | ✅ |
| All | Configurable limits (Basic: Space layer; Standard: Space + install; Advanced: all) | ✅ | ✅ | ✅ | ✅ |
| | Standalone Inspecto LA app or inside the console | ✅ | ✅ | ✅ | ✅ |

⚠ Today the Link Analysis modules ship from Professional up (`docs/EDITIONS.md` offerings matrix) and Basic's
explore features depend on modules the Personal flavour lacks only where noted (Case management, assistant, Alerts,
policy are Standard+ anyway). Phase 2 must move `la-core`, `la-api`, `la-storage`, `geo-link` (and `la-graph`) into
the Basic flavour with the Investigation routes gated off.

## 3. Industry packs

Glossary: an **Industry pack** maps a vertical's data and vocabulary onto the neutral concepts. For Inspecto LA a
pack is one installable unit of:

1. **Terminology layer** — display names, abbreviations and hover definitions mapped onto canonical concepts. A pack
   relabels; it never adds a concept, so the core stays neutral.
   - Telecom: MSISDN, IMSI, IMEI, CDR / XDR, A-party / B-party, Cell ID, SIM box, IRSF, Wangiri.
   - Mobile Money: Wallet, Agent, Cash-in / Cash-out, P2P, Float.
2. **Entity Types, link kinds and normalisers** (e.g. E.164 for telecom numbers).
3. **A pattern pack** of that industry's fraud typologies.
4. **A Space Template** with demo data (existing: `telco-fraud`, `la-mobile-money`).

Prerequisite: promote **Domain Profile** from SPA-only labels (`domain-profile.ts`) to a server + UI concept
declared per Space — this settles roadmap decision `LDP-D7`. Guards: the vocabulary guard allows pack terms only
inside a pack's labels; a neutrality test proves the core holds no pack word (data-prep acceptance AC-09).

## 4. Third-party integration (Standard+)

| Direction | Capability | Builds on |
|---|---|---|
| In: alert → Investigation | A fraud product posts an alert with entity ids; Inspecto LA opens a seeded Investigation and returns its link | Investigations API; I5 "Open in LA" contract |
| In: deep link | "Open in Inspecto LA" from another tool: entity, optional window, token | I5; the embed token pattern |
| Out: case hand-off | Push a Dossier bundle and findings to an external case manager; keep its case id linked | `CasePort` → remote Case adapter (I3) |
| Out: events | Webhooks: Investigation created / promoted, Alert fired, approval needed | Action Requests, notification channels |
| Trust | OIDC client credentials, narrowly scoped tokens, audited both sides | I1; ⛔ formal security review first (SSRF, token scope, replay) |

One generic connector (REST + webhooks with a field mapping) first; named adapters only on demand. Every new route
clears the four endpoint gates and an `openapi-v1.json` entry.

## 5. Phases

1. **Rename and matrix** (S): Inspecto LA branding in `la-app`, whitepaper, stakeholder docs; GLOSSARY entry; the
   §2 matrix into `docs/EDITIONS.md`.
2. **Edition rename** (M): Basic / Standard / Advanced across build profiles, `package.ps1 -Edition`, launchers,
   bundle names, docs and guards (`tools/bundle-modules.mjs`, `EDITIONS.md` generated matrix). Confirm ED-2 first.
3. **Basic flavour** (M): ship the Link Analysis explorer in Basic with Investigation routes gated off; no-sign-in
   paths verified; masking documented as display-only.
4. **Domain Profile on the server and the pack format** (M–L): settles `LDP-D7`; Telecom and Mobile Money packs.
5. **Integration, inbound** (M): alert → Investigation API; "Open in Inspecto LA" deep link.
6. **Integration, outbound** (L): generic connector, remote Case adapter, webhooks; client-credentials trust after
   the security review.
7. **Preview check** (S): prove the Preview bundle carries every module and boots with demo sign-in.

## 6. Open questions

- ED-2 scope confirmation (whole product, as recorded).
- Integration targets: unknown; ask the stakeholders which case managers and fraud products they run.
- Do Basic's Value Measures and patterns stay free after pricing review? (ED-7 keeps them in Basic for now.)

## 7. As built (2026-10-17, five parallel lanes, integrated on `lane/la-ed`)

All seven phases shipped. Lane decisions LD-* are listed so a reviewer can overturn any of them.

| Phase | As built |
|---|---|
| 1 Naming | `la-app` reads "Inspecto LA" (tab, rail, landing, sign-in via `AppEnvironment.productName`); GLOSSARY entry *Inspecto LA*; user manual, end-user guide, six runbooks, both OKF concepts use "Inspecto LA (Link Analysis)" on first use. |
| 2 Edition rename | Basic / Standard / Advanced / Preview everywhere: Maven profiles `edition-standard`, `edition-advanced`, `edition-preview` (Basic = default build; `edition-professional` / `edition-enterprise` removed), `package.ps1 -Edition`, launchers, offerings `basic|standard|advanced.toon`, CI workflows, skills, `/bootstrap` edition id, refusal messages, EDITIONS.md, BRANCHING.md, GLOSSARY *Edition*. |
| 3 Basic explorer | `entity-list`, `la-core`, `la-storage`, `la-api`, `geo-link` ship in Basic. Edition feature `investigations` (declared by `inspecto-ops`) gates 18 Investigation route modules plus the integration routes: on Basic they answer 503 `CAPABILITY_UNAVAILABLE` "available in Standard and above"; `/bootstrap` reports `features.investigations`; the SPA hides Investigation affordances. Masking on Basic is display-only (documented). |
| 4 Packs | Industry packs are classpath resources in `inspecto-entity-store` (every edition): manifest, terms, Entity Types, link kinds; refused whole when invalid. Packs `telecom` and `mobile-money`; Space key `domain_profile`; `GET`/`PUT /settings/link-analysis/domain-profile`; SPA hardcoded profiles removed; vocabulary rule `industry-pack-term` keeps pack words out of the core (AC-09); `LDP-D7` settled. |
| 5 Inbound | `POST /inv/inbound/investigations` (idempotent on system + external id; seeds through the normal ops; links an external reference) and `POST /inv/deep-links/resolve`; SPA `/open` ("Open in Inspecto LA"). |
| 6 Outbound | Generic connector: `GET`/`PUT /inv/integration` (canAdminister, `la-integration.json`, endpoint = a registered https Connection, token by secret reference), `POST /inv/investigations/{id}/handoff` (Dossier bundle + findings, case id kept as an external reference); signed webhooks (HMAC, retries, audited). Inbound client-credentials trust NOT enabled: security review owed. |
| 7 Preview | Preview carries every module (proven by `tools/offering-classpath.test.mjs`); `-DemoAuth` accepts Preview; a real Preview demo bundle booted and answered `/health`. |

**Lane decisions** (one line each; details in the lane commits):
- Brand: product name reaches the shared sign-in screen through `AppEnvironment.productName`; route, module and nav names keep "Link Analysis"; bare "LA" in UI text banned in GLOSSARY only.
- Editions: gate = edition feature + route features at dispatch (no module split); 503 precedes the Space's 404 MODULE_DISABLED; Graph Run, index and Dataset Files count as Investigation surface; case-history stays explorer; Basic "Investigate a number" explains instead of seeding; Javadoc and comments keep old edition names (hundreds); old EDITIONS cell letters remapped; integration routes gated with the Investigation surface (ED-6).
- Packs: classpath packs in a base module; file names reserved through `ImportLoaderInventoryTest.ALLOWED` on the `module.toon` precedent (⚠ a test inventory edit — operator to confirm); no pack code (normalisers chosen from the closed set); pack Entity Types overlay the defaults; default Entity Type labels made neutral; finance / supply-chain / cyber SPA profiles removed (views saved under them open as generic).
- Inbound: no "alert" in paths (Glossary: an Alert is a fired Alert Rule); idempotency by derived id + create-once header; binding inferred from a saved view or index, else 422; both routes rate-classed EXPENSIVE; no new audit types.
- Outbound: endpoint from a registered Connection (SSRF and egress guards for free); JSON settings file; case id as a non-granting external reference; webhooks carry no entity ids; retries default 2.
- Integration: the three LA showcase and audit / claims / mobile-money templates gained the inert SLA examples file, and `la-showcase-off` mirrors the analyst reveal capability (both were red on origin/master).
