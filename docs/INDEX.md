# Documentation Index

> The curated map of **current** Inspecto docs. Anything not listed here is archived under
> [`archived-documents/`](archived-documents/) — kept for provenance, never maintained, never linked as
> current. When you add or retire a doc, update this index in the same change.
>
> **Structure (binding — see root `CLAUDE.md` "Documentation lifecycle"):** current knowledge lives in the
> **[OKF bundle](okf/product/index.md)** — one hub per Product Map Item — the small root canon below, the HTTP
> contract [`api/`](api/README.md) and root `compliance/` · in-flight plans live in [`superpower/`](superpower/) ·
> everything else is archive. The archive's front page, [`archived-documents/README.md`](archived-documents/README.md),
> maps each archived plan to the hub its truth went into.

---

## Start here (session essentials, ~800 tokens)

- [`../README.md`](../README.md) — the engineering front door: what Inspecto is, repo layout, build, where docs live
- `CLAUDE.md` — project rules (vocabulary, doc lifecycle, skills/agents, branch policy)
- `.claude/COMMON_MISTAKES.md` — ⚠️ read FIRST
- `.claude/QUICK_START.md` — essential commands
- `.claude/ARCHITECTURE_MAP.md` — file locations

## Root canon (durable, audience-facing)

- [`USER_GUIDE.md`](USER_GUIDE.md) — **end-user guide** to the web app: navigation, Spaces/Lens, every
  screen, shared UI elements.
- [`GLOSSARY.md`](GLOSSARY.md) — ⚠️ **canonical vocabulary, BINDING** — the single source of truth for every
  concept's name, the banned synonyms, and the UI→model→backend rename map (§13).
- [`spec-requirements.md`](okf/product/layer/platform/spec-requirements.md) (platform hub; was root `REQUIREMENTS.md`, D-IR6) — **requirements-of-record**: the full platform requirement set with
  MoSCoW, edition mapping, NFRs, sequencing. ⚠ [`spec-editions.md`](okf/product/layer/platform/spec-editions.md) is authoritative for the Edition
  column; this table mirrors it.
- [`BACKLOG.md`](BACKLOG.md) — **the one board of open work**, grouped by **product area → functionality**
  (consolidated 2026-09-24; the previous 644 KB page is frozen as
  [`archived-documents/backlog-snapshot-2026-09-24.md`](archived-documents/backlog-snapshot-2026-09-24.md)):
  area index · §0 priority · §1 operator decisions · §2 externally gated · §3.1–§3.12 product areas · §4
  engineering platform · §5 docs & board hygiene · §6 standing refusals · §7 duplicate map. **Open work
  only** — closed rows are deleted, not struck through.
- [`features-feature-inventory.md`](okf/product/layer/platform/features-feature-inventory.md) (platform hub; was root `FEATURE_INVENTORY.md`, D-IR6) — every feature's TOON shape + where defined, examples,
  packaging, runnability. Pairs with the runnable suite in [`../inspecto/examples/`](../inspecto/examples).
- [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **Advanced Operations & Internals Guide** (the production
  investigation hub): per-component process, events, metrics, persisted state, `-D` flags, the full Control
  API, troubleshooting playbooks. **Living doc.**
- [`EDITIONS.md`](EDITIONS.md) — **the customer guide to the editions** (Basic / Standard / Advanced), shipped in
  every bundle: what each edition includes, security and deployment by edition, the Investigations add-on,
  upgrade notes. No class, test or decision ids and no internal links (split by audience 2026-11-06). The
  engineering record — gate mechanisms, decisions, Preview, the assembly model, the generated Offerings matrix,
  the **feature × edition board** with its generated **Step Processors** table (`tools/render-processor-board.mjs`
  from `processor-catalog.contract.json` — edit the catalog, regenerate the table; do not hand-edit rows) and the
  edition-gating debt — is [`okf/product/layer/platform/spec-editions.md`](okf/product/layer/platform/spec-editions.md).
- [`BRANCHING.md`](BRANCHING.md) — branch & release policy (versions = branches; merge-forward; SemVer + CC).
- *Retired 2026-11-19 (D-IR5):* `PROJECT_NOTES.md` — split into team memory and hub pages, archived as
  [`archived-documents/project-notes-2026-11-19.md`](archived-documents/project-notes-2026-11-19.md), whose header maps
  each part to its new home. Its topic map and related-sandboxes list are at the end of this index.

## The knowledge bundle — product hubs (current as-built truth)

**Every current document lives in exactly one hub**, one hub per Product Map Item:
[`okf/product/<kind>/<name>/`](okf/product/index.md), mirroring `product-map/<kind>/<name>.toon`
(`PRODUCT-DOCS-HUB-1`, 2026-10-19 — replaced the old `okf/backend|frontend|capabilities|agentic/`,
`stakeholders/`, `ops/`, `ui/`, `roadmap/`, `wiki/` trees). File names say what a document is: a slot's main
file carries the hub folder name, `<slot>-<hub>.md` (`architecture-la-core.md`), and a topic file is
`<slot>-<topic>.md` (`DOCS-HUB-NAMES-1`, 2026-11-18):

| Slot | File | Reader |
|---|---|---|
| plan | `plan*.md` | requirement of record (the former capability specs `ACQ`, `INC`, …; area table `GLOSSARY.md` §14) |
| spec | `spec*.md` | developer — implementation detail |
| architecture | `architecture*.md` | solution architect, troubleshooter (incl. the former L3 module / solution pages) |
| features | `features*.md` | marketing, presales |
| feature-db | `feature-db*.md` | build / release engineer (generated — wave 3) |
| user-guide | `user-guide*.md` | end user — screens and configuration |
| admin-guide | `admin-guide*.md` | system administrator — runbooks, operations |
| api | `api*.md` | API integrator (the contract itself stays in [`api/`](api/README.md)) |
| tests | `tests*.md` | QA / QC — test plans, verification runs |
| brochure | `brochure*.md` | customer, sales — on `layer/product`, editions and solution packs |

Where to look first: whole-product material → [`layer/product`](okf/product/layer/product/index.md)
(UI-wide docs, brochures, executive brief, competitive landscape) · platform-wide backend → [`layer/platform`](okf/product/layer/platform/index.md)
(architecture, build/run, editions model, roadmap, operations) · **architecture, any item** → start at
[`layer/platform/architecture-platform.md`](okf/product/layer/platform/architecture-platform.md); its last section is the map of
every hub's `architecture-<hub>.md` · everything else → the module, add-on or
solution pack it is about. Link Analysis has **one** user guide,
[`module/la-core/user-guide-la-core.md`](okf/product/module/la-core/user-guide-la-core.md) (analysts); its engineering reference and design records are
[`architecture-records.md`](okf/product/module/la-core/architecture-records.md) (the la-core hub was consolidated from 28 to 15 files on 2026-11-15). Each hub's `index.md` is **generated** (`node tools/render-product-docs.mjs`) —
add or rename files, never edit the table. Shape checked by `tools/check-product-docs.mjs`.

## Contracts and audits

- [`api/`](api/README.md) — **machine-readable v1 HTTP contract**: `openapi-v1.json` + canonical `examples/`,
  enforced by `ApiContractTest`; `schemas/` (metadata-bundle JSON Schema + samples).
- [`../compliance/`](../compliance/) — the NFR-7 compliance tree (repo root, shipped in the deploy bundle's
  docs). [`controls-matrix.md`](../compliance/controls-matrix.md) is the **C2 single mapping table** (SOC 2 TSC
  ↔ ISO 27001 Annex A ↔ NIST 800-53 → implementing file/route/gate → evidence → responsibility); the ISO SoA
  and the FedRAMP customer-responsibility matrix are **exports of it**, never separate documents, and its §4
  is the consolidated gap list (G1–G10). Plus [`evidence/`](../compliance/evidence/) — the C3/C4 auditor
  runbooks: release verification (`.sha256`/`.asc`), audit-log extraction, access review (G8),
  retention configuration (G5 — 🔴 the event store, AUDIT events included, has NO retention), the RTO/RPO
  statement (G6 — targets are operator-fill), audit-record protection (AU-9) and air-gap posture (a
  **packaging** guarantee, not a runtime network control). Scanned by the vocabulary guard.

## In-flight plans (`superpower/` — a plan lives here ONLY while its work is active)

When the work ships: distil the durable as-built facts into the matching OKF concept, move still-open items
to [`BACKLOG.md`](BACKLOG.md), then `git mv` the plan to `archived-documents/plans-archive/`, add its row
to the archived-plans table in [`archived-documents/README.md`](archived-documents/README.md) and drop its row
here — all in the same change.

Pruned 2026-10-19: this table lists only plans whose work is still open; shipped, closed and superseded plans are in the archived-plans table of [`archived-documents/README.md`](archived-documents/README.md). `superpower/assets/` keeps the reactor-metric scripts (`pkggraph.py`, `edgeholders.py`, `fanmatrix2.py`) cited by `okf/product/layer/platform/architecture-modules.md`.

| Plan | State | What is actually left |
|---|---|---|
| [`2026-11-25-spaces-consolidation-plan.md`](superpower/2026-11-25-spaces-consolidation-plan.md) | 🟢 **APPROVED 2026-11-25 — D-SC1…D-SC11 decided; no engine change; P0 closed, P1 done for five Spaces, P2 built, P3 built, P4 done, P5 boot verified, P6 inventory written, classes A, B, C and the legacy rehearsal quarantined** (`SPACES-CONSOLIDATION-1`) | Space config in git, data never: the ~93 MB of committed sample data generated or supplied instead (generators take `--out`, `tools/seed-space-data.mjs`), a data root `C:\sandbox\inspecto_spaces\<space>\` for every build, a guard that keeps `spaces/` config-only; the launchers repointed and the unneeded sibling folders and worktree cleaned up |
| [`2026-11-27-space-coherence-plan.md`](superpower/2026-11-27-space-coherence-plan.md) | 🟡 **DRAFT 2026-11-27 — nothing built, D-CO1…D-CO6 open** (`SPACE-COHERENCE-1`) | Space config at agent speed: complete the link census, one graph read for server, UI and agents, a what-if preview for a set of edits, agents strict first, duplicates and restated facts reported, provenance and use per component; builds on `SPACE-LINK-CENSUS-1` and `SPACE-EVOLUTION-1` |
| [`2026-11-20-agent-kit-ak2-plan.md`](superpower/2026-11-20-agent-kit-ak2-plan.md) | 🟡 **APPROVED for phase 1 2026-11-20 — D-AK1…D-AK4 signed; phase 1 (AK-2a, AK-2b) built 2026-11-20; D-AK5 ("allow within rights", machines held), D-AK6 ("Pending Action") and D-AK7 ("two postures") signed 2026-11-24; phase 1 complete for Claude 2026-11-24; phased by agent role** (`AGENT-KIT-1`) | Agent Kit phase AK-2, built one agent role per phase — developer, operations support engineer, Incident and Case analyst: the agent-client allowlist and RFC 9728 metadata, an agent's every write held as a Pending Change by identity (not by endpoint), the write MCP Tools, operational and case actions within a person's rights (a machine's held as Pending Actions), `propose_action` through Action Requests, the machine agent capability set |
| [`2026-11-25-agent-kit-ak2-product-review.md`](superpower/2026-11-25-agent-kit-ak2-product-review.md) | 📋 **REVIEW 2026-11-25 — AK-2 vs. full product surface** | Deep analysis of implemented vs. planned Agent Kit coverage across all 50 modules, lifecycle usage (develop/test/use/operate/manage/maintain), and 6 improvement proposals (`PendingChangeSet`, `proofToken`, Sandbox Clone, MCP Prompts/Tasks, Requirement loop, `422` telemetry) |
| [`2026-11-20-assistant-screen-help-plan.md`](superpower/2026-11-20-assistant-screen-help-plan.md) | 🟡 **PROPOSED 2026-11-20 — D-SH1…D-SH3 open; nothing built** (`ASSISTANT-SCREEN-HELP-1`) | Agent role 4, the helpdesk: the in-product Assistant explains the screen or Widget a user is on — grounded in the Handbook's external pages, the screen's object resolved under the user's own rights, no write path |
| [`2026-11-25-agent-kit-ak2-widening-plan.md`](superpower/2026-11-25-agent-kit-ak2-widening-plan.md) | 🟡 **APPROVED 2026-11-25 — D-AK8…D-AK12 signed; W1, W3, W10 built** (`AGENT-KIT-1`) | What an agent reaches after phases 1–3b, as eleven task specs (W1–W11; W11, the analyst writes an Incident's or Case's record, added by D-AK8): release a quarantined file, `propose_change` for the kinds already held, read tools for Space health and scores, the live golden runs, shapes and errors as MCP Resources, previews, simulators and Space tests one group at a time, a second set of operational actions, adversarial golden tasks, two read-only Agent Skills, a wider Kit guard |
| [`2026-11-26-agent-data-answers-masking-design.md`](superpower/2026-11-26-agent-data-answers-masking-design.md) | 🟡 **SIGNED 2026-11-26 — D-AK13…D-AK21 signed (D-AK15, 16, 18, 19 not as recommended); S0 shared seam built 2026-11-26; people defects filed and first** (`AGENT-KIT-1` W6 groups B, D, E; `AGENT-LOOKUP-PREVIEW-1`; `RECON-READ-UNGATED-1`, `LOOKUP-TEST-OPEN-READ-1`) | How agent-reachable routes that answer data rows are made safe: masking proven by one fail-closed seam, raw values accepted as an oracle risk, looser enumeration bounds, one agent rate budget at both doors, a person's cost limits, existing audit rows with screened names never in clear; per-route table, proof plan, effort, order, and six people-facing rows filed |
| [`2026-11-19-user-enablement-and-agent-kit-plan.md`](superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) | 🟡 **APPROVED 2026-11-19 — D-UE1…D-UE7 and D-UE9 signed (recommended options), D-UE8 open; P0, P1 lane A, P2, P3 and AK-1 built (2026-11-20); P4 in flight; P5 waits on D-UE10** (`USER-HANDBOOK-1`, `USER-TRAINING-1`, `AGENT-KIT-1`) | The Handbook (manifest + renderer → in-app `/help` and a static site, tutorials on one worked example, how-tos, docs as tests, a Develop branch with an internal render for Inspecto's engineers; D-UE10 open), training (Learning Paths, Labs, a platform video series) and the Agent Kit (an MCP endpoint in the server, Agent Skills, Claude / Gemini / OpenAI / local packages, a golden task suite) |
| [`2026-11-18-business-insights-plan.md`](superpower/2026-11-18-business-insights-plan.md) | 🟡 **APPROVED 2026-11-18 — D-BI1…D-BI3 signed, D-BI4…D-BI10 open; nothing built; P0 startable** (board row `BI-INSIGHTS-1`) | Advanced insights on business data in Widgets and Dashboards (what happened, why, unusual, next, at risk, act), authored and tuned as Insight Profiles: inventory of what exists, 20 capabilities tiered now / extend / new module, architecture, phases P0–P5, decisions, rows to file on approval |
| [`2026-11-10-product-assembly-plan.md`](superpower/2026-11-10-product-assembly-plan.md) | 🟡 **APPROVED 2026-11-10 — D-PA1…D-PA12 signed; PA-0 (words), PA-1 (`products/`, `apps/`, `industries/`, one module table), PA-2 (`tools/assemble.mjs`, `package.ps1 -Product -Distribution`) PA-3 (`/bootstrap.product`: Apps, gates, nav) and PA-4 (generated host data, the LA shell drawing the Product's nav, one shell provider set) and PA-5a (Inspecto Telco defined; la-app `-c telco`; one Composed Space Template) and PA-5b (the suite shell: App switcher, Telco App areas, My work across Apps) and PA-5c (one usage feed in the Composed Space Template, D-PA17) and PA-5d (the Telco demo Distribution, the Product matrix) done; PA-6 designed (D-PA18…D-PA20 signed) and PA-6a (the App kit, `tools/inspecto-app.mjs`) done; PA-6b next** (board row `PRODUCT-ASSEMBLY-1`) | One Inspecto Platform (Base modules + Add-ons), Industries, Apps and two Product shapes (Domain product, Platform product), with an assembler, Product lock, suite shell, entitlements and live App install. Build PA-5…PA-10 (proof: Inspecto Telco, RA/FM/LA/CM over one ingest); apply the board re-check in §13 |
| [`link-analysis-roadmap.md`](superpower/link-analysis-roadmap.md) | **ACTIVE — the ONE Link Analysis tracker (2026-10-05; six more LA plans folded in 2026-10-22)** | Everything NOT yet built for Link Analysis: option-D separation and D-7 Drafts leftovers, the CDR data-preparation plan (`LDP-D1`…`D11`), Investigation-store leftovers, entity-model and live-detection leftovers (D-LD2…D-LD18 await operator confirmation), and §D1–§D5 from the folded plans: demo readiness (nothing open), the whitepaper review demo run of show (`LA-WHITEPAPER-DEMO-1`), D-LAE3 / D-LAE4 / D-LAE6 (`LA-EDITION-PLACEMENT-1`), analyst-upload leftovers, showcase rehearsal (`LA-SHOWCASE-REHEARSAL-1`). Built behaviour: [`okf/product/module/la-core/user-guide-la-core.md`](okf/product/module/la-core/user-guide-la-core.md); backend: [`okf/product/module/la-core/architecture-la-core.md`](okf/product/module/la-core/architecture-la-core.md). |
| [`2026-11-16-la-training-series-plan.md`](superpower/2026-11-16-la-training-series-plan.md) | 🟡 **DECIDED 2026-11-16 — D-TR1…D-TR7 answered; all 41 episodes rendered 2026-11-19 (I1 and I2 as explainers), review and fixes open** (board row `LA-TRAINING-SERIES-1`) | A user training video series for Inspecto LA: 41 episodes of 5–7 min in 9 categories, intent → action → impact for every toolbar, toolbox, dock and settings control, recorded live; coverage matrix against a 2026-11-16 control inventory, production method, user-guide gaps |
| [`2026-11-19-la-case-videos-v2-plan.md`](superpower/2026-11-19-la-case-videos-v2-plan.md) | 🟡 **PROPOSED 2026-11-19 — D-CV1…D-CV7 all answered 2026-11-20 (Standard, the fake Demo Auth sign-in, the bundled examples; the recommended voice, audio and length); the twenty specs drafted; voice and audio stack, presentation layer and the Telecom_SimSwap pilot film done 2026-11-22; all twenty films pushed 2026-11-23; review (listen to all twenty, narrated-versus-shown) and the v1 hand-over open** (board row `LA-CASE-VIDEOS-2`) | The twenty industry case films re-specified to the training-series standard: a case-first seven-beat arc with a decoy, a voice, audio and presentation standard (lexicon, pacing, -16 LUFS, captions, chips, case-file card), the spec template, the twenty-case table, gates and phases |
| [`2026-11-08-la-industry-spaces-plan.md`](superpower/2026-11-08-la-industry-spaces-plan.md) | 🟡 **ACTIVE 2026-11-08 — approved; step 1 (spike, *Utilities*) BUILT: industry recipes composed by the packer, `la-utility` mapped, views renamed; steps 2–5 open** | One Link Analysis Space per industry (composed from the use-case showcase templates), every Dataset mapped (Entity Types and Mappings), saved views renamed to short use cases; spike on `la-utility` → *Utilities* first. |
| [`2026-11-10-la-basic-ui-test-report.md`](superpower/2026-11-10-la-basic-ui-test-report.md) | 🟡 **OPEN 2026-11-10 — hands-on UI test of Inspecto LA Basic; 14 defects filed** | Fix the 14 `LA-*` defects it filed in `BACKLOG.md` (4 × P2: placeholder Cases in Basic, empty sample Space, Data dock grows the page, Basic 503s shown as errors); archive the report when they close. |
| [`telco-packs-plan.md`](superpower/telco-packs-plan.md) | 🟡 **ACTIVE 2026-10-22 — the ONE in-flight plan for the three telco packs being built** (`TELCO-BYPASS-SIMBOX-1` B0–B3 built, B4–B7 open, D-BP10 open · `PACK-PARTNER-SETTLEMENT-1` S0–S4 built, S5–S8 and W1–W3 open, PS-O1…PS-O7 · `PACK-RERATING-1` R0–R3 built, R4–R7 open, D-RR10 open) | Open slices only, the partner-settlement ↔ re-rating contract (R1–R8) against what re-rating shipped, the RFP lines still in play and those CDRs cannot close, gates G-SB and G-K. As built: each hub's `plan-as-built.md` ([bypass](okf/product/solution-pack/telco-bypass/plan-as-built.md) · [settlement](okf/product/solution-pack/partner-settlement/plan-as-built.md) · [re-rating](okf/product/solution-pack/telco-rerating/plan-as-built.md)). |
| [`enterprise-scale-out-plan.md`](superpower/enterprise-scale-out-plan.md) | **SIGNED 2026-09-10** (§9 D1′–D13; §2 amendments applied; spikes S1–S5 next) — **audited 2026-10-12: phase A (shared state) and phase B (run lease) BUILT, phase C PARTLY BUILT** (Space→pod map and shared catalog done; `s3://` `dirs.database`, the N-pod Helm chart, ingress/`/spaces` from the map, D14–D16, U1/U2, D13 not) — see the plan's "Audit 2026-10-12" table | Enterprise scales out on **Kubernetes by shared-nothing partitioning** (N pods, each owning Spaces, one DuckLake catalog on Postgres over object storage) — ⛔ not a distributed engine. Three M phases, the first two valuable on a single node (durable shared state; a run lease that makes T4 standby automatic). ⚠ **Revises signed decisions** — container D1 and two `editions.md` §6 refusals — with the exact amendments in §2, applied on signature, not before. Written 2026-09-10. |
| [`assets/`](superpower/assets/) | TOOLING | `pkggraph.py` / `edgeholders.py` / `fanmatrix2.py` — reproduce any reactor or package metric quoted in `okf/product/layer/platform/architecture-modules.md`. |
| `superpower/rfp-mvno-assurance/` (local only — does not exist in a clone: git-excluded, client-adjacent) | **LOCAL WORKING SET — MVNO assurance RFP and its demo** | Kept out of git (`.git/info/exclude`); listed here so a developer with the folder can find it. Project- and Space-specific plans live there, not in `superpower/`: `build-plan.md` (WS-01 … WS-46), `gap-register.md`, `demo-script.md` (day-in-the-life storyline + DW worklist), `demo-experience-review.md` (business-lens review, UIE-1 … UIE-10), `demo-auth-plan.md` (Demo User sign-in for an offline demo build, D-1 … D-5 owed), two workbooks and `generator/`, which also builds the local `spaces/telco-assurance` Space. |
| `superpower/group-regulator-assurance/` (local only — does not exist in a clone: git-excluded, client-adjacent) | **LOCAL WORKING SET — DRAFT 2026-09-27: federated RA/FM/BA for a multi-country telecom Group and a national regulator** | Kept out of git (`.git/info/exclude`). `solution-proposal-group.md` and `solution-proposal-regulator.md` are the per-buyer detailed proposals (internal sections marked 🔒); `proposal-group.html` and `proposal-regulator.html` are the client-facing proposals; `solution-proposal.md` is the combined master, superseded and retained for provenance; `research/` holds R1 … R8 and `C-platform-fit.md`. Nothing is signed and nothing is on the board. |

### Parked designs — in their product hubs, not in flight (2026-10-22)

Design-only plans for demand-gated work. They are not in flight, so they sit in the hub of the item they would build, as a `plan-<topic>.md`. **To resume one:** move it back to `superpower/` and rank its BACKLOG row. The console first-time use cases became a spec: [`okf/product/module/elt/user-guide-elt.md`](okf/product/module/elt/user-guide-elt.md).

| Parked plan | State | What it designs |
|---|---|---|
| [`plan-irsf-wangiri.md`](okf/product/solution-pack/telco-fraud/plan-irsf-wangiri.md) | ⏸ **PARKED 2026-10-22 in its hub — design only, demand-gated** · was: 🟡 **PLAN 2026-10-18 (`PACK-IRSF-WANGIRI-1`, competitive gap H) — design only, nothing built; D-IW1…D-IW10 open** | CDR-only interconnect / wholesale fraud: what `telco-fraud` detects today (per-subscriber IRSF and Wangiri) and the eight gaps; a destination-risk Reference with provenance and review dates; IRSF line features, trunk / partner roll-up and a cost-exposure Measure; Wangiri campaign and many-to-one call-back features; seasonal Anomaly Models; watch / allow / block Entity Lists; Incident → Case flow with a never-autonomous recommend-block; RFP traceability (compliance-matrix lines, `G-xx`, `WS-xx`, and the lines CDRs cannot meet); slices IW0–IW9 |
| [`plan-5g-iot-assurance.md`](okf/product/solution-pack/industry-telecom/plan-5g-iot-assurance.md) | ⏸ **PARKED 2026-10-22 in its hub — design only, demand-gated** · was: 🟡 **PLAN 2026-10-18 (`PACK-5G-IOT-ASSURANCE-1`, competitive gap U) — design only, nothing built; D-5G1…D-5G10 open** | What a file-fed platform with no probes can assure (5G charging and session records, IoT usage and SIM / eUICC lifecycle, IPDR, cloud and digital-service exports) and what is out (probes, real-time, counters); 21 controls in six families, Schemas and Parsers (reuse first), Measures / KPIs / Alert Rules, two Space Templates (`telco-data-assurance`, `digital-service-assurance`), a Traceability section to the MVNO RFP working set, slices U0…U7 with done-criteria, and the demand gate. |
| [`plan-sales-commissions-churn.md`](okf/product/solution-pack/business-assurance/plan-sales-commissions-churn.md) | ⏸ **PARKED 2026-10-22 in its hub — design only, demand-gated** · was: 🟡 **PLAN 2026-10-18 (`PACK-SALES-MARKETING-1`, competitive gap V) — design only, nothing built; decisions D-SM1…D-SM12 open** | Four lanes (sales performance, commissions, marketing, churn): three run on shipped engines, only a churn probability and a surfaced Forecast need `PREDICTIVE-ANALYTICS-1`. Commissions first: independent re-computation versus payout (scheme Reference, accelerators, clawbacks, dealer-gaming controls, dispute Case), the lane that traces to RFP `RA-24` / `FM-10`; recommends splitting the row in three; RFP traceability and unsatisfiable lines in §8 |
| [`plan-predictive-analytics.md`](okf/product/module/anomaly/plan-predictive-analytics.md) | ⏸ **PARKED 2026-10-22 in its hub — design only, demand-gated** · **S2 BUILT 2026-11-01** (`PREDICTIVE-SEGMENT-RFM-1`, module `inspecto-predictive`; as built: [`spec-segmentation.md`](okf/product/module/predictive/spec-segmentation.md)) · **S3 BUILT 2026-11-01** (`PREDICTIVE-CHURN-DECLARED-1`; as built: [`spec-churn.md`](okf/product/module/predictive/spec-churn.md)) · **S4 BUILT 2026-11-01** (`PREDICTIVE-FORECAST-1`; as built: [`spec-forecast.md`](okf/product/module/predictive/spec-forecast.md)) · was: 🟡 **DESIGN 2026-10-12 — interview held (§9a): OD-1…OD-9, OD-14, OD-17 decided and terms recorded in `GLOSSARY.md`; S0 done; nothing else built, priority stays P3 design-only; OD-10…OD-13, OD-15, OD-16 answered when the module is scheduled** | `PREDICTIVE-ANALYTICS-1`: segmentation (RFM, then k-means), churn (declared-coefficient logistic, then fitted) and demand forecasting (seasonal-naive + Holt-Winters with a stored backtest) as an optional `inspecto-predictive` module copying the Anomaly Score pattern; no ML runtime exists today, DuckDB-only compute, reproducibility / masking / drift rules, reuse map, MoSCoW slices S0…S14, risks, proposed BACKLOG rows. |
| [`plan-agentic-investigation.md`](okf/product/module/intelligence/plan-agentic-investigation.md) | ⏸ **PARKED 2026-10-22 in its hub — design only, not started; row P2, its §8 decisions await operator confirmation** · was: 🟡 **DESIGN 2026-10-18 (`AGENTIC-INVESTIGATION-1`) — nothing built; decisions §8 open** | An agent that works an Incident: why it fired, cited evidence, declared value at risk, and one proposed Consequence filed as a four-eyes Action Request (never self-executed); grounding, slices S0…S6, seams, risks. |
| [`elt/plan-elt.md` appendix *Parked: parser field tiers interview kit*](okf/product/module/elt/plan-elt.md) | ⏸ **PARKED 2026-10-22 in its hub — an UNEXECUTED instrument** (D13 parked the tier question 2026-07-25; kit ready since 2026-08-28, waiting for a session with a real onboarding user) | The observation-session kit for D13 parser field tiers: the two questions, the pre-agreed analysis rule, the deliverable. 🔴 Re-ground its inventory before use: it predates `d012f721`. Open: BACKLOG §2 *D13 parser field tiers*. |

## Topic map — go here first

| Topic | Doc |
|---|---|
| **What a capability REQUIRED / has BUILT / has LEFT / REFUSED** (new tier 2026-09-08) | the `plan-*.md` files in the [product hubs](okf/product/index.md) — one doc per area ID; **9 of 17 slots written** as of 2026-09-08 (`ACQ` pilot, then `SEC` · `INC` · `API` · `SPC` · `MET` · `DAT` · `ING` · `OPS`). Each one's §2 is the requirement-of-record and **overrides `REQUIREMENTS.md` §3 and `EDITIONS.md` where they differ**; §5 lists what is not built. The remaining 8 slots are named in [`GLOSSARY.md`](GLOSSARY.md) §14 and sized in [`docs-consolidation-plan.md`](archived-documents/plans-archive/docs-consolidation-plan.md) §5.1. ⚠ The `okf/` sections answer only "what is built", and answer it by CODE LAYER |
| Production investigation (process/events/metrics/state/`-D` flags/Control API/troubleshooting) | [`ADVANCED_GUIDE.md`](ADVANCED_GUIDE.md) — **living doc** |
| Pipeline-graph design (IR, lift, validator, executor, registry, T-checklist §14) | [`pipeline-graph-design.md`](okf/product/module/elt/architecture-elt.md) |
| Live execution of authored Pipelines (`JobType.PIPELINE`, T32) | [`live-execution.md`](okf/product/module/elt/spec-pipeline-execution.md) |
| The token model + decisions D1–D10 | [`okf/product/module/elt/spec-pipeline-model.md`](okf/product/module/elt/spec-pipeline-model.md) § *The token model* · [`okf/product/module/elt/plan-elt.md`](okf/product/module/elt/plan-elt.md) § *Decisions* *(the plan was archived 2026-09-10)* |
| Execution residuals — X3 park detail · X2 cross-lane provenance · X1 bounded COMMIT retry (**SHIPPED + archived 2026-09-02**; X4/X5 sketches in BACKLOG §4) | [`execution-residuals-plan.md`](archived-documents/plans-archive/execution-residuals-plan.md) |
| Pipeline editor UI (Recipe/graph views, drawer, attribute forms) | [`pipeline-editor.md`](okf/product/module/elt/user-guide-elt.md) |
| **Pipelines workbench as a GENERIC ingest-authoring surface (2026-09-22)** — where it stands per format × surface, and the plan to make every trust surface give one answer | [`pipelines-workbench-moscow.md`](archived-documents/plans-archive/pipelines-workbench-moscow.md) (v2, all 26 shipped Pipelines swept; §3.1 coverage matrix — ARCHIVED 2026-09-23, executed) · [`workbench-trust-plan.md`](archived-documents/plans-archive/workbench-trust-plan.md) (`WB-01`..`WB-20`, decisions `D2`–`D9` awaiting signature, §8 exit contract with a 30-day stall clock) · ⚠ the *Recipe view* named on this page's editor row **no longer exists** (`6d3c68fa`, 2026-09-18) — `pipeline-editor.md:550` records the removal |
| Authoring redesign for business users — the sectioned Parse pane (Delimited first: compact property rows, plain-language labels, grounded defaults, ONE "Columns that come out" table) + the **SQL-first** `transform.sql` Step (the 2026-09-03 Simple fields grid was superseded and deleted the same day the operator reviewed it live) (**SHIPPED + archived 2026-09-04**: `98ffc90b` · `7e13dd82` · `d012f721` · `c119a6af` (steps: home) · `1d557bbd` (one property-row idiom, no Description) · `24171333` (SQL-first); follow-ons in BACKLOG §4 AUTHORING-REDESIGN-1) | as-built: [`grammar-config.md`](okf/product/module/config/user-guide-grammar-config.md) (Parse pane) · [`schema-mapping-authoring.md`](okf/product/module/elt/user-guide-elt.md) §8.2 (Transform pane) · [`pipeline-editor.md`](okf/product/module/elt/user-guide-elt.md) · [`catalog-vs-executors.md`](okf/product/module/elt/spec-pipeline-model.md) (engine) · decisions + R1–R12 review: [`parse-pane-redesign-plan.md`](archived-documents/plans-archive/parse-pane-redesign-plan.md), [`sql-transform-v1-plan.md`](archived-documents/plans-archive/sql-transform-v1-plan.md); mockup source `archived-documents/plans-archive/assets/authoring-redesign-mockup/` |
| **Pipeline authoring finalized (2026-09-04, `0a2fa82f`)** — the redesign's open seams closed in one pass: the **`sql` Recipe verb** (between `transform` and `summarize`; `RecipeCompiler`/`RecipeConverter` round-trip `{sql, fields}`, so `GET /pipelines/{id}/recipe` no longer throws `UNSUPPORTED_STEP` on a SQL Step — mid-branch it compiles but deliberately does NOT arm, `RouteArming.BRANCH_STEP_KINDS` excludes it) and **`POST /components/transform/describe`** over `TypeFlow.describe`, which fills "Comes out as" and surfaces DuckDB's binder message as-you-type without executing a row. 🔴 Three defects were found by reading, not by tests: the describe route ran author SQL with NO `SqlGuard` and no sandbox (a `DESCRIBE` never executes the plan, but the binder OPENS a `read_csv('…')` target to infer its schema — the one author-SQL entry point that could read arbitrary files; now guarded, with a negative test whose probe is a CSV that really exists); hand-written SQL in CodeMirror could not be SAVED at all (`valueChange` bound straight to the signal emitted no `dirtyChange`, and `canApply()` refused `sqlOnly` outright); and the Load pane's new legacy-`columns` fallback would have written `rules` beside a surviving `columns`, which `RowShaper.columnsOf` PREFERS — a save that is real and dead at once. Two more came from DRIVING the pane on a real backend: it sent VARCHAR for every upstream column, so `AMOUNT * 2` over a declared DOUBLE was reported as a binder error and BLOCKED Apply (a false refusal — the declared types now travel via `[upstreamColumnTypes]`), and the JDBC wrapper line ("…closed pending query result") led every message, so the first thing the author read was driver plumbing. Also: only a 422 blocks Apply (offline/404/503 must never lock the author out), and the Parse↔Sink `partitions[]` stale-Apply race is closed by re-reading the companion before the write. Verified `mvn -o clean test` exit 0 (23 modules, 3971/0/0/5) + UI gate exit 0 (2788 tests). ⚠ The commit itself was created by a CONCURRENT peer session sweeping the working tree, so its message under-describes the three defects above — this row and BACKLOG `AUTHORING-REDESIGN-1` are the provenance. | [`schema-mapping-authoring.md`](okf/product/module/elt/user-guide-elt.md) §8.2 · [`catalog-vs-executors.md`](okf/product/module/elt/spec-pipeline-model.md) · [`pipeline-config-keys.md`](okf/product/module/config/spec-pipeline-config-keys.md) · [`grammar-config.md`](okf/product/module/config/user-guide-grammar-config.md) · BACKLOG `AUTHORING-REDESIGN-1` (a)/(h)/(n1)/(n2)/(n3)/(o) |
| Step park & drain (disabled route branches, `POST /runs/{n}/drain`) | [`step-park-drain.md`](okf/product/module/elt/spec-pipeline-execution.md) |
| Branch-aware ingest executor (armed `route:`, engagement) | [`branch-aware-ingest.md`](okf/product/module/elt/spec-ingest.md) |
| Data acquisition framework (Phases A–F, connectors, dedup, watermarks) | [`data-acquisition-framework.md`](okf/product/module/elt/spec-acquisition.md) |
| All TOON config keys | [`configuration.md`](okf/product/module/config/admin-guide-configuration-reference.md) |
| Editions (Personal/Professional/Enterprise = build flavors); feature × edition board; Step Processor catalog table | [`spec-editions.md`](okf/product/layer/platform/spec-editions.md) |
| Branch & release policy (versions=branches; merge-forward; SemVer+CC) | [`BRANCHING.md`](BRANCHING.md) |
| Parsing/grammar | [`parsing-options-reference.md`](okf/product/module/elt/spec-parsing.md), [`delimited-grammar-design.md`](archived-documents/plans-archive/delimited-grammar-design.md) |
| Perf benchmarks & tuning | [`performance.md`](okf/product/layer/platform/tests-performance.md) |
| Strategy / roadmap / stakeholder decks | [`roadmap/`](okf/product/index.md) |
| Curated index of all current docs | [`INDEX.md`](INDEX.md) |
| Engineering knowledge bundle (OKF, consolidated 2026-07-07) | [`okf/`](okf/product/index.md) — sections [`frontend/`](okf/product/index.md) · [`backend/`](okf/product/index.md) · [`agentic/`](okf/product/index.md) |
| Requirements-of-record + MoSCoW · stakeholder set | [`REQUIREMENTS.md`](okf/product/layer/platform/spec-requirements.md) · [`stakeholders/`](okf/product/index.md) |

## Related sandboxes (separate repos — pointers only)

- **agent-kernel** (`C:/sandbox/agent-kernel`) — DISCONTINUED; Inspecto vendored its reasoning layer 2026-07-07.
- **eoiagent** (upstream repo `jotder/inspect-agent`, ⚠ not a local path) — agent platform; Inspecto's model transport. Pinned to the
  released **`0.1.0`** (tag `v0.1.0`, EOI-7a 2026-07-08; trunk now `0.2.0-SNAPSHOT`). Rebuild into local `.m2`
  with `git checkout v0.1.0 && mvn -o clean install` until a registry is chosen (EOI-7b).
- **CVVE** (`C:/sandbox/agentic-doc-validation`) — kernel's 3rd consumer; first real `HumanHandoff` driver.

(Detailed progress for these lives in the per-user agent memory, not in this repo — they are different projects.)
