# Inspecto — Product Capabilities

> Audience: product owners & business analysts · Status date: **2026-09-23** (was 2026-07-07); **edition placements
> re-derived 2026-11-19** from [`EDITIONS.md`](../../../../EDITIONS.md) and `offerings/*.toon` — the Personal /
> Professional / Enterprise ladder became Basic / Standard / Advanced on 2026-10-17 (`LA-EDITIONS-1`), and modules
> moved between rungs, not only names
> Requirement of record: the capability specs indexed by [`spec-requirements.md`](../platform/spec-requirements.md) §3 ·
> edition per capability: [`spec-editions.md`](../platform/spec-editions.md) · forward plan:
> [`../platform/plan-roadmap.md`](../platform/plan-roadmap.md).

## Personas — one console, three Lenses

Everyone uses the **same operator console**; a self-selected **Lens** filters it (a Lens is a view,
never a permission — server-enforced **Roles** arrive with the Standard edition and map onto Lenses):

- **Business** — consume Dashboards/KPIs, investigate provenance & lineage, raise **Requirements**
  (KPI / Report / Reconciliation / Rule requests with a triage lifecycle).
- **Builder** — author in the **Workbench** (Connections, Collectors, Pipelines) and the **Studio**
  (Datasets, Queries, Widgets, Dashboards, Link Analysis, Geo Map Analysis).
- **Ops** — operate Runs, the Signal ledger, Alerts → Incidents → Cases.

**Starters:** a Space Template instantiates a ready-made Space from a server-side catalog. Basic ships the
**Orders starter** (a retail-orders feed with Pipeline, Expectation, Dataset and dashboard) and 18 Link Analysis
showcase templates; Standard adds the domain templates — `telco-ra`, `telco-fraud`, `telco-bypass`,
`telco-rerating`, `partner-settlement`, `business-assurance`, `mobile-money`, `aml`, `payment-fraud` — and
`la-daily-feed` (`offerings/basic.toon`, `offerings/standard.toon`). *(Corrected 2026-11-19: this said the vertical
blueprints were a roadmap item; they ship as these Space Templates.)*

## Capability map (status as of 2026-09-23; edition column 2026-11-19)

Each status traces to its capability spec's §2 (via [`spec-requirements.md`](../platform/spec-requirements.md) §3); the
edition column is the lowest edition that bundles it, per [`spec-editions.md`](../platform/spec-editions.md) and
[`EDITIONS.md`](../../../../EDITIONS.md). Where a status sentence names an edition, it was re-checked 2026-11-19.

| Area | What the user gets | Status | From edition |
|---|---|---|---|
| **Acquire** | Connections (SFTP/FTP/FTPS/DB) + scheduled Collectors with dedup, watermarks, gap detection | ✅ Shipped. Object storage (S3/MinIO, native GCS, Azure Blob) and a Kafka consumer that drains a topic per Collector cycle also ship; network shares (NFS/SMB) were refused (`ACQ-4`, `ACQ-5`) | Basic (a new remote Connection in the screens, scheduled *Keep this up to date*, the Kafka consumer: Standard) |
| **Parse & validate** | CSV/delimited grammars, fixed-width (text+binary), plugin formats, compressed input; schema casting + quarantine | ✅ Shipped — the JSON and text/regex frontends closed 2026-07-07 (`ING-5`); xlsx, Parquet and ASN.1 frontends also ship; XML goes through the plugin bridge (no XPath) | Basic (the ASN.1 and telecom decoders: Advanced, since 2026-11-17) |
| **Process** | Visual **Pipeline** DAG authoring; medallion ELT to a Parquet lakehouse; event-driven incremental runs; async run-now | ✅ Shipped — the live end-to-end check closed 2026-07-07 (`PIP-1`, `inspecto/examples/06-serve/pipeline-job`); run replay is in-memory (gone after a restart) | Basic (Pipeline editor Steps that compute, join, enrich or summarise: Standard) |
| **Query** | **Query Library** — reusable Queries with `$`-Parameters, executed live on the embedded engine | ✅ Shipped | Basic |
| **Visualize** | Studio: Datasets → Widgets → Dashboards (quick filters, drill-through, PNG export); KPI & Reports gallery | ✅ Shipped. Scheduled report delivery renders json/csv/png/pdf; its e-mail carries the artifact **path**, not an attachment (`BI-4`) | Basic (report e-mail: Standard) |
| **Investigate — where** | **Geo Map Analysis**: fully-offline map, heatmaps, routes, time playback, co-location/stay-point intelligence, saved Geo Views | ✅ Shipped, UI **and** server projection (`POST /geo/projection`, `/geo/routes`) | Standard |
| **Investigate — who** | **Link Analysis**: entity/link graphs over Datasets, communities, pattern matching, saved Views | ✅ Shipped (`INV-1`); the Investigation layer (op log, multi-hop traversal, Working Set, dossier with chain of custody) landed 2026-09-23, parts of it backend-only. Evidential controls (`LA-19`: annotation, coverage indicator, purpose, masking, four-eyes) built 2026-09-24 | Basic, at the Explorer level (Investigations, Dossiers and the evidential controls: the Investigations add-on, Standard) |
| **Operate** | Runs, Signal ledger (events), Prometheus metrics, three-layer audit, run reporting | ✅ Shipped. Runs, run reporting and the audit read/export are in every edition; `/metrics` and the events feed are Standard and up. The audit is append-only, hash-chained with signed anchors (`AuditAnchors`, `/audit/verify`), and kept on disk in every edition *(corrected 2026-11-19: this said not tamper-evident, and in memory on the free edition)* | Basic (metrics, events feed: Standard) |
| **Respond** | Alert Rules → Alerts → **Incidents** → Cases, SLA, comments, AI Diagnosis | 🟡 Alert Rules are kept on Basic but not armed; Incidents → Cases need `inspecto-ops`. Windowed Alert Rules evaluated on each commit of a stream Pipeline ship in the Real-time Detection add-on (2026-11-12). SMTP + webhook delivery channels ship, but `INC-3` stays partial (one global preference set). AI Diagnosis drafts; it never raises an Incident | Standard (an Alert Rule saved on Basic is kept, not armed) |
| **Reconcile** | Dataset-vs-Dataset Reconciliation producing **Breaks** with auto-close lifecycle | ✅ Shipped. Reconciliation is stateless: Break auto-close is a merge rule in the browser, with no server-side Break store | Standard (out of the free edition since 2026-10-07, `MODULE-REORG-1` P7) |
| **Data quality** | **Expectation** engine (checks validating records against Schemas) | ✅ Shipped — engine + builder UI (`ING-6`, closed 2026-07-07), six check kinds (`baseline` added 2026-09-24), run over at-rest Parquet and in an import's own run. A breach raises a deduplicated Incident on Standard and up; on Basic it is recorded as an event only | Basic (Incident: Standard) |
| **Govern metadata** | Everything is a **Component**; Catalog + reuse/lineage graphs; **Metadata Bundle** export/import with drift detection | ✅ Shipped, bundle export/import routes included (`SPC-4`, `MET-3`) | Basic |
| **Multi-tenant** | Isolated **Spaces** with CRUD, export/import, templates | ✅ Shipped. Isolation is a folder layout on Basic and Standard and **enforced** only on Advanced (Access Policies); the Space Templates are listed under *Starters* above; cross-Space sharing is Standard and up | Basic (enforced isolation: Advanced) |
| **Integrate** | Versioned **`/api/v1`** REST contract (OpenAPI-enforced), gateway/IAM-ready | ✅ Shipped — the OpenAPI contract covers every route since 2026-09-15; the gateway blueprints were never run against a live gateway | Basic |
| **Secure** | Auth-free Basic; Standard: OIDC sign-in, roles, attributed audit; HTTPS in every edition once a keystore is set | ✅ Shipped (`SEC-7` closed 2026-07-08). Masking is enforced from Standard (mode `typed`; display only on Basic); the PII masking Step is Advanced only. Open: vault/KMS secrets (demand-gated) | Standard (Access Policies, ABAC: Advanced) |
| **AI assist** | 7 draft-only assistant skills (diagnose, explain, KPI→SQL, NL→schedule, …), fully offline-capable | ✅ Shipped as an **optional module from Standard** (since 2026-09-12 under the old edition names, `CP-14`, `PKG-5`); absent from Basic | Standard |
| **AI next** | Embedded intelligence: governed autonomy ladder (explain → draft → act-with-approval) | 🟡 **Built and tested, bundled in Advanced only** — P0–P5 complete 2026-07-21, inline authoring A1–A5 2026-07-26/28; `inspecto-intelligence` entered the top edition on 2026-09-29 (`ASSURE-INTELLIGENCE-BUNDLE-1`) and is absent from Basic and Standard. Open: AGT-6b (model-composed graphs), demand-gated | Advanced |

⚠ **"Shipped" means on `master` and staged by `package.ps1` for that edition — not published.** No release has
been tagged since `v3.12.0` (2026-06-05), and only a `v*` tag publishes, so nothing that landed after that date is
in a downloadable artifact yet.

### How the ladder is packaged — sell the ladder, not the ceiling

> ⚠ **This is a product read from the codebase and roadmap, NOT a validated market position.** It is
> recorded here because it was the only copy, in a plan now archived; the client-segment confirmation that
> would firm it up is `AGT-SEGMENT-1` on the board. Do not quote it to a customer as researched.

Clients buy the rung they trust, and can prove what happened at every rung:

| Tier | Rungs | Edition | Pitch |
|---|---|---|---|
| **A — Explain & Investigate** | L0 QA + L1 investigation (Cases, RCA, ranked root cause + fix draft) | **All**, incl. air-gapped | "Ask your platform why a batch failed; get a ranked root cause and a fix draft. Fully offline, local models, nothing leaves the box." |
| **B — Author & Act with approval** | L1 authoring + L2 gated action | **Advanced** (first planned for Professional; `inspecto-intelligence` ships in Advanced only), opt-in (`-Dintelligence.act.enabled`) | "The agent drafts; your operator approves in an inbox with a full diff. Every action rides the same audited route a human uses." |
| **C — Bounded autonomy** | L3 (`ops_monitor`, policy + budgets) | **Advanced**, demand-gated | "Hands-off remediation inside limits you set — with a kill switch, per-class budgets, and a SHADOW mode to watch what it *would* do first." |

⚠ **The Edition column is the intended packaging, not today's build (re-checked 2026-11-19).** The assist agent
ships from **Standard**, not All — it is absent from Basic (`CP-14`). The intelligence agent behind the
investigation tier, the approvals inbox and the autonomy policy (`inspecto-intelligence`) ships in **Advanced only**
(since 2026-09-29, `ASSURE-INTELLIGENCE-BUNDLE-1`), so Tier A's investigation rung is not in a Basic or Standard
bundle today, and Tier B and Tier C are Advanced only.

**Tier A is the wedge** — highest value, lowest risk, and it works in the deployments these buyers
actually have: regulated, air-gapped, no cloud LLM permitted. Most competitors cannot offer a capable
agent under those constraints. **That, not autonomy, is the moat.**

**SHADOW-first is the Tier C on-ramp. Never lead with `AUTO`.** The recommended sequence is: enable
`SHADOW` for one action class → review the `/autonomy` ledger for a soak period → promote a single class
to `AUTO` with a conservative hourly budget.

⛔ **Anti-positioning, deliberate.** Do **not** market autonomy as the default or the differentiator — for
this buyer profile an over-eager autonomous agent is a liability. The winning sentence is: *"AI that helps
you understand and author, that acts only when you approve, that can run with zero network egress, and
that logs everything."*

## What's deliberately NOT in scope (this horizon)

No Spring/framework migration · no distributed-by-default clustering · no per-record replay ·
no login/user management inside the product (identity is delegated to the customer's IAM) ·
no auth code in the free core.

## The remaining MUST list (release-gating)

*(Refreshed 2026-09-23 — the 2026-07-07 list named seven items; five have closed.)*

**Closed:** object-storage connectors (`ACQ-4`, 2026-07-22 — S3/MinIO, GCS, Azure Blob; network shares refused) ·
JSON + text/regex parsing (`ING-5`, 2026-07-07) · Expectation engine (`ING-6`, 2026-07-07) · the paid edition's
security, then Professional, now Standard (`SEC-7`, 2026-07-08) · the live end-to-end pipeline check (`PIP-1`, 2026-07-07).

**Still open:**

- **Notification delivery** — SMTP + webhook channels ship, but `INC-3` stays partial (one global preference
  set). ⚠ `REQUIREMENTS.md` §5 lists `INC-3` as closed; its spec, the record, says partial.
- **Packaging verification** — the recorded runtime check of the Professional bundle (now Standard) covers five of
  the twelve modules it had then (`PKG-4`).
- **The agent-framework pin has regressed** — the parent pom pins eoiagent `0.2.0-SNAPSHOT` and CI rebuilds it
  from upstream (`EOI-7` half a). Publishing its artifacts (half b) is no longer a gate: it became a standing
  refusal on 2026-09-15.

(Detail and sequencing: [`spec-requirements.md`](../platform/spec-requirements.md) §5–6 and the capability specs it indexes.)
