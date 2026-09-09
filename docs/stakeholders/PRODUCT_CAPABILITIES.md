# Inspecto — Product Capabilities

> Audience: product owners & business analysts · Status date: **2026-07-07**
> Full requirement-level detail + MoSCoW: [`../REQUIREMENTS.md`](../REQUIREMENTS.md). Forward plan:
> [`../roadmap/ROADMAP.md`](../roadmap/ROADMAP.md).

## Personas — one console, three Lenses

Everyone uses the **same operator console**; a self-selected **Lens** filters it (a Lens is a view,
never a permission — server-enforced **Roles** arrive with the Standard edition and map onto Lenses):

- **Business** — consume Dashboards/KPIs, investigate provenance & lineage, raise **Requirements**
  (KPI / Report / Reconciliation / Rule requests with a triage lifecycle).
- **Builder** — author in the **Workbench** (Connections, Collectors, Pipelines) and the **Studio**
  (Datasets, Queries, Widgets, Dashboards, Link Analysis, Geo Map Analysis).
- **Ops** — operate Runs, the Signal ledger, Alerts → Incidents → Cases.

**Starters:** a Space Template instantiates a ready-made Space from a server-side catalog; the build ships
one (**Orders starter** — a retail-orders feed with pipeline, quality rule, dataset and dashboard). Vertical
blueprints (Telecom Revenue Assurance, Fraud Management, Financial Auditing, Link Analysis) are a roadmap
item, not a shipped artifact (corrected 2026-09-08).

## Capability map (status honest as of 2026-07-07)

| Area | What the user gets | Status |
|---|---|---|
| **Acquire** | Connections (SFTP/FTP/FTPS/DB) + scheduled Collectors with dedup, watermarks, gap detection | ✅ Shipped |
| **Parse & validate** | CSV/delimited grammars, fixed-width (text+binary), plugin formats, compressed input; schema casting + quarantine | ✅ Shipped (JSON + text/regex frontends: planned MUST) |
| **Process** | Visual **Pipeline** DAG authoring; medallion ELT to a Parquet lakehouse; event-driven incremental runs; async run-now | ✅ Shipped (final live e2e verification pending) |
| **Query** | **Query Library** — reusable Queries with `$`-Parameters, executed live on the embedded engine | ✅ Shipped |
| **Visualize** | Studio: Datasets → Widgets → Dashboards (quick filters, drill-through, PNG export); KPI & Reports gallery | ✅ Shipped |
| **Investigate — where** | **Geo Map Analysis**: fully-offline map, heatmaps, routes, time playback, co-location/stay-point intelligence, saved Geo Views | ✅ Shipped (UI; spatial backend later) |
| **Investigate — who** | **Link Analysis**: entity/link graphs over Datasets, communities, pattern matching, saved Views | 🟡 UI complete, backend projection pending |
| **Operate** | Runs, Signal ledger (events), Prometheus metrics, three-layer audit, run reporting | ✅ Shipped |
| **Respond** | Alert Rules → Alerts → **Incidents** → Cases, SLA, comments, AI Diagnosis | ✅ Shipped (notification delivery channels: MUST remainder) |
| **Reconcile** | Dataset-vs-Dataset Reconciliation producing **Breaks** with auto-close lifecycle | ✅ Shipped |
| **Data quality** | **Expectation** engine (rules validating records against Schemas) | 🟡 UI pane exists; engine = MUST remainder |
| **Govern metadata** | Everything is a **Component**; Catalog + reuse/lineage graphs; **Metadata Bundle** export/import with drift detection | ✅ Shipped (bundle backend endpoints pending) |
| **Multi-tenant** | Isolated **Spaces** with CRUD, export/import, templates | ✅ Shipped |
| **Integrate** | Versioned **`/api/v1`** REST contract (OpenAPI-enforced), gateway/IAM-ready | ✅ Shipped |
| **Secure** | Auth-free Personal; Standard: OIDC SSO, HTTPS, RBAC seams, attributed audit | ✅ Module shipped; hardening = MUST remainder |
| **AI assist** | 7 draft-only assistant skills (diagnose, explain, KPI→SQL, NL→schedule, …), fully offline-capable | ✅ Shipped |
| **AI next** | Embedded intelligence: governed autonomy ladder (explain → draft → act-with-approval) | ✅ **P0–P5 complete 2026-07-21**; inline authoring A1–A5 shipped 2026-07-26/28. Open: AGT-6b (model-composed graphs), gated upstream |

### How the ladder is packaged — sell the ladder, not the ceiling

> ⚠ **This is a product read from the codebase and roadmap, NOT a validated market position.** It is
> recorded here because it was the only copy, in a plan now archived; the client-segment confirmation that
> would firm it up is `AGT-SEGMENT-1` on the board. Do not quote it to a customer as researched.

Clients buy the rung they trust, and can prove what happened at every rung:

| Tier | Rungs | Edition | Pitch |
|---|---|---|---|
| **A — Explain & Investigate** | L0 QA + L1 investigation (Cases, RCA, ranked root cause + fix draft) | **All**, incl. air-gapped | "Ask your platform why a batch failed; get a ranked root cause and a fix draft. Fully offline, local models, nothing leaves the box." |
| **B — Author & Act with approval** | L1 authoring + L2 gated action | **Standard+**, opt-in (`-Dintelligence.act.enabled`) | "The agent drafts; your operator approves in an inbox with a full diff. Every action rides the same audited route a human uses." |
| **C — Bounded autonomy** | L3 (`ops_monitor`, policy + budgets) | **Enterprise**, demand-gated | "Hands-off remediation inside limits you set — with a kill switch, per-class budgets, and a SHADOW mode to watch what it *would* do first." |

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

Object-storage connectors (S3/GCS/Azure) · JSON + text/regex parsing · Expectation engine ·
notification delivery · Standard security hardening + packaging verification · one live end-to-end
pipeline verification · pin the agent framework to a released version.
(Detail and sequencing: [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §5–6.)
