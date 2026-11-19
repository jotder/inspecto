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
- [`PROJECT_NOTES.md`](PROJECT_NOTES.md) — consolidated cross-cutting knowledge that isn't obvious from code
  or git: key decisions, gotchas, engine seams & perf, pointer map.
- [`REQUIREMENTS.md`](REQUIREMENTS.md) — **requirements-of-record**: the full platform requirement set with
  MoSCoW, edition mapping, NFRs, sequencing. ⚠ [`spec-editions.md`](okf/product/layer/platform/spec-editions.md) is authoritative for the Edition
  column; this table mirrors it.
- [`BACKLOG.md`](BACKLOG.md) — **the one board of open work**, grouped by **product area → functionality**
  (consolidated 2026-09-24; the previous 644 KB page is frozen as
  [`archived-documents/backlog-snapshot-2026-09-24.md`](archived-documents/backlog-snapshot-2026-09-24.md)):
  area index · §0 priority · §1 operator decisions · §2 externally gated · §3.1–§3.12 product areas · §4
  engineering platform · §5 docs & board hygiene · §6 standing refusals · §7 duplicate map. **Open work
  only** — closed rows are deleted, not struck through.
- [`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) — every feature's TOON shape + where defined, examples,
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
| [`2026-11-19-information-restructure-and-maintenance-plan.md`](superpower/2026-11-19-information-restructure-and-maintenance-plan.md) | 🟡 **APPROVED 2026-11-19 — W1 (lossless moves) done the same day; W2–W4 open; D-IR5, D-IR6 open** (`INFO-RESTRUCTURE-1`) | W2 header schema and ratchet guards plus a docs health report (`tools/docs-health.mjs`, which does not exist yet); W3 board rows to summaries, `PROJECT_NOTES.md` split, root canon generated or folded, 46 oversize pages split, history notes out; W4 header fill with the Handbook's P1 |
| [`2026-11-19-user-enablement-and-agent-kit-plan.md`](superpower/2026-11-19-user-enablement-and-agent-kit-plan.md) | 🟡 **APPROVED 2026-11-19 — D-UE1…D-UE7 and D-UE9 signed (recommended options), D-UE8 open; P0 done (names in `GLOSSARY.md`); nothing built; P1 and AK-1 startable** (`USER-HANDBOOK-1`, `USER-TRAINING-1`, `AGENT-KIT-1`) | The Handbook (manifest + renderer → in-app `/help` and a static site, tutorials on one worked example, how-tos, docs as tests, a Develop branch with an internal render for Inspecto's engineers; D-UE10 open), training (Learning Paths, Labs, a platform video series) and the Agent Kit (an MCP endpoint in the server, Agent Skills, Claude / Gemini / OpenAI / local packages, a golden task suite) |
| [`2026-11-19-basic-elt-profile-plan.md`](superpower/2026-11-19-basic-elt-profile-plan.md) | 🟡 **APPROVED 2026-11-19 — P0 verify and UI-0 running** (`BASIC-ELT-PROFILE-1`) | Basic onboarding as a locked profile of ELT: P0 findings, UI-1, BE-1…BE-4, the Edition gate |
| [`2026-11-18-business-insights-plan.md`](superpower/2026-11-18-business-insights-plan.md) | 🟡 **APPROVED 2026-11-18 — D-BI1…D-BI3 signed, D-BI4…D-BI10 open; nothing built; P0 startable** (board row `BI-INSIGHTS-1`) | Advanced insights on business data in Widgets and Dashboards (what happened, why, unusual, next, at risk, act), authored and tuned as Insight Profiles: inventory of what exists, 20 capabilities tiered now / extend / new module, architecture, phases P0–P5, decisions, rows to file on approval |
| [`2026-11-10-product-assembly-plan.md`](superpower/2026-11-10-product-assembly-plan.md) | 🟡 **APPROVED 2026-11-10 — D-PA1…D-PA12 signed; PA-0 (words), PA-1 (`products/`, `apps/`, `industries/`, one module table), PA-2 (`tools/assemble.mjs`, `package.ps1 -Product -Distribution`) PA-3 (`/bootstrap.product`: Apps, gates, nav) and PA-4 (generated host data, the LA shell drawing the Product's nav, one shell provider set) and PA-5a (Inspecto Telco defined; la-app `-c telco`; one Composed Space Template) and PA-5b (the suite shell: App switcher, Telco App areas, My work across Apps) and PA-5c (one usage feed in the Composed Space Template, D-PA17) and PA-5d (the Telco demo Distribution, the Product matrix) done; PA-6 designed (D-PA18…D-PA20 signed) and PA-6a (the App kit, `tools/inspecto-app.mjs`) done; PA-6b next** (board row `PRODUCT-ASSEMBLY-1`) | One Inspecto Platform (Base modules + Add-ons), Industries, Apps and two Product shapes (Domain product, Platform product), with an assembler, Product lock, suite shell, entitlements and live App install. Build PA-5…PA-10 (proof: Inspecto Telco, RA/FM/LA/CM over one ingest); apply the board re-check in §13 |
| [`link-analysis-roadmap.md`](superpower/link-analysis-roadmap.md) | **ACTIVE — the ONE Link Analysis tracker (2026-10-05; six more LA plans folded in 2026-10-22)** | Everything NOT yet built for Link Analysis: option-D separation and D-7 Drafts leftovers, the CDR data-preparation plan (`LDP-D1`…`D11`), Investigation-store leftovers, entity-model and live-detection leftovers (D-LD2…D-LD18 await operator confirmation), and §D1–§D5 from the folded plans: demo readiness (nothing open), the whitepaper review demo run of show (`LA-WHITEPAPER-DEMO-1`), D-LAE3 / D-LAE4 / D-LAE6 (`LA-EDITION-PLACEMENT-1`), analyst-upload leftovers, showcase rehearsal (`LA-SHOWCASE-REHEARSAL-1`). Built behaviour: [`okf/product/module/la-core/user-guide-la-core.md`](okf/product/module/la-core/user-guide-la-core.md); backend: [`okf/product/module/la-core/architecture-la-core.md`](okf/product/module/la-core/architecture-la-core.md). |
| [`2026-11-16-la-training-series-plan.md`](superpower/2026-11-16-la-training-series-plan.md) | 🟡 **DECIDED 2026-11-16 — D-TR1…D-TR7 answered; all 41 episodes rendered 2026-11-19 (I1 and I2 as explainers), review and fixes open** (board row `LA-TRAINING-SERIES-1`) | A user training video series for Inspecto LA: 41 episodes of 5–7 min in 9 categories, intent → action → impact for every toolbar, toolbox, dock and settings control, recorded live; coverage matrix against a 2026-11-16 control inventory, production method, user-guide gaps |
| [`2026-11-19-la-case-videos-v2-plan.md`](superpower/2026-11-19-la-case-videos-v2-plan.md) | 🟡 **PROPOSED 2026-11-19 — D-CV1…D-CV6 open; the twenty specs being written, nothing re-recorded** (board row `LA-CASE-VIDEOS-2`) | The twenty industry case films re-specified to the training-series standard: a case-first seven-beat arc with a decoy, a voice, audio and presentation standard (lexicon, pacing, -16 LUFS, captions, chips, case-file card), the spec template, the twenty-case table, gates and phases |
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
