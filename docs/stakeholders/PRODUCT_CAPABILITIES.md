# Inspecto — Product Capabilities

> Audience: product owners & business analysts · Status date: **2026-09-23** (was 2026-07-07)
> Requirement of record: the capability specs indexed by [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §3 ·
> edition per capability: [`../EDITIONS.md`](../EDITIONS.md) · forward plan:
> [`../roadmap/ROADMAP.md`](../roadmap/ROADMAP.md).

## Personas — one console, three Lenses

Everyone uses the **same operator console**; a self-selected **Lens** filters it (a Lens is a view,
never a permission — server-enforced **Roles** arrive with the Professional edition and map onto Lenses):

- **Business** — consume Dashboards/KPIs, investigate provenance & lineage, raise **Requirements**
  (KPI / Report / Reconciliation / Rule requests with a triage lifecycle).
- **Builder** — author in the **Workbench** (Connections, Collectors, Pipelines) and the **Studio**
  (Datasets, Queries, Widgets, Dashboards, Link Analysis, Geo Map Analysis).
- **Ops** — operate Runs, the Signal ledger, Alerts → Incidents → Cases.

**Starters:** a Space Template instantiates a ready-made Space from a server-side catalog; the build ships
one (**Orders starter** — a retail-orders feed with pipeline, quality rule, dataset and dashboard). Vertical
blueprints (Telecom Revenue Assurance, Fraud Management, Financial Auditing, Link Analysis) are a roadmap
item, not a shipped artifact (corrected 2026-09-08).

## Capability map (status as of 2026-09-23)

Each status traces to its capability spec's §2 (via [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §3); the
edition column is the lowest edition that bundles it, per [`../EDITIONS.md`](../EDITIONS.md).

| Area | What the user gets | Status | From edition |
|---|---|---|---|
| **Acquire** | Connections (SFTP/FTP/FTPS/DB) + scheduled Collectors with dedup, watermarks, gap detection | ✅ Shipped. Object storage (S3/MinIO, native GCS, Azure Blob) and a Kafka consumer that drains a topic per Collector cycle also ship; network shares (NFS/SMB) were refused (`ACQ-4`, `ACQ-5`) | Personal |
| **Parse & validate** | CSV/delimited grammars, fixed-width (text+binary), plugin formats, compressed input; schema casting + quarantine | ✅ Shipped — the JSON and text/regex frontends closed 2026-07-07 (`ING-5`); xlsx, Parquet and ASN.1 frontends also ship; XML goes through the plugin bridge (no XPath) | Personal |
| **Process** | Visual **Pipeline** DAG authoring; medallion ELT to a Parquet lakehouse; event-driven incremental runs; async run-now | ✅ Shipped — the live end-to-end check closed 2026-07-07 (`PIP-1`, `inspecto/examples/06-serve/pipeline-job`); run replay is in-memory (gone after a restart) | Personal |
| **Query** | **Query Library** — reusable Queries with `$`-Parameters, executed live on the embedded engine | ✅ Shipped | Personal |
| **Visualize** | Studio: Datasets → Widgets → Dashboards (quick filters, drill-through, PNG export); KPI & Reports gallery | ✅ Shipped. Scheduled report delivery renders json/csv/png/pdf; its e-mail carries the artifact **path**, not an attachment (`BI-4`) | Personal (report e-mail: Professional) |
| **Investigate — where** | **Geo Map Analysis**: fully-offline map, heatmaps, routes, time playback, co-location/stay-point intelligence, saved Geo Views | ✅ Shipped, UI **and** server projection (`POST /geo/projection`, `/geo/routes`) | Professional |
| **Investigate — who** | **Link Analysis**: entity/link graphs over Datasets, communities, pattern matching, saved Views | ✅ Shipped (`INV-1`); the Investigation layer (op log, multi-hop traversal, Working Set, dossier with chain of custody) landed 2026-09-23, parts of it backend-only. Open: evidential controls (`LA-19`, not started) | Professional |
| **Operate** | Runs, Signal ledger (events), Prometheus metrics, three-layer audit, run reporting | ✅ Shipped. Runs, run reporting and the audit read/export are in every edition; `/metrics` and the events feed are Professional+. The audit is append-only, **not tamper-evident**, and in-memory on Personal (the Professional/Enterprise launchers make it durable) | Personal (metrics, events feed: Professional) |
| **Respond** | Alert Rules → Alerts → **Incidents** → Cases, SLA, comments, AI Diagnosis | 🟡 Alert Rules in every edition; Incidents → Cases need `inspecto-ops`. SMTP + webhook delivery channels ship, but `INC-3` stays partial (one global preference set). AI Diagnosis drafts; it never raises an Incident | Personal (Incidents, Cases, channels: Professional) |
| **Reconcile** | Dataset-vs-Dataset Reconciliation producing **Breaks** with auto-close lifecycle | ✅ Shipped. Reconciliation is stateless: Break auto-close is a merge rule in the browser, with no server-side Break store | Personal (Break → Incident: Professional) |
| **Data quality** | **Expectation** engine (rules validating records against Schemas) | ✅ Shipped — engine + builder UI (`ING-6`, closed 2026-07-07), five check kinds, run over at-rest Parquet. A breach raises a deduplicated Incident on Professional+; on Personal it is recorded as an event only | Personal (Incident: Professional) |
| **Govern metadata** | Everything is a **Component**; Catalog + reuse/lineage graphs; **Metadata Bundle** export/import with drift detection | ✅ Shipped, bundle export/import routes included (`SPC-4`, `MET-3`) | Personal |
| **Multi-tenant** | Isolated **Spaces** with CRUD, export/import, templates | ✅ Shipped. Isolation is a folder layout on Personal/Professional and **enforced** only on Enterprise (ABAC); one template ships (the Orders starter); cross-Space sharing is Professional+ | Personal (enforced isolation: Enterprise) |
| **Integrate** | Versioned **`/api/v1`** REST contract (OpenAPI-enforced), gateway/IAM-ready | ✅ Shipped — the OpenAPI contract covers every route since 2026-09-15; the gateway blueprints were never run against a live gateway | Personal |
| **Secure** | Auth-free Personal; Professional: OIDC SSO, HTTPS, RBAC seams, attributed audit | ✅ Shipped (`SEC-7` closed 2026-07-08). Open: vault/KMS secrets (demand-gated), field masking (Enterprise, `SEC-08`) | Professional (ABAC: Enterprise) |
| **AI assist** | 7 draft-only assistant skills (diagnose, explain, KPI→SQL, NL→schedule, …), fully offline-capable | ✅ Shipped as an **optional module from Professional** since 2026-09-12 (`CP-14`, `PKG-5`); Personal answers 503 | Professional |
| **AI next** | Embedded intelligence: governed autonomy ladder (explain → draft → act-with-approval) | 🟡 **Built and tested, bundled by no edition** — P0–P5 complete 2026-07-21, inline authoring A1–A5 2026-07-26/28, but `inspecto-intelligence` is staged by no bundle, so `/agent/*` answers 503 everywhere. Open: AGT-6b (model-composed graphs), demand-gated | — (no edition) |

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
| **B — Author & Act with approval** | L1 authoring + L2 gated action | **Professional+**, opt-in (`-Dintelligence.act.enabled`) | "The agent drafts; your operator approves in an inbox with a full diff. Every action rides the same audited route a human uses." |
| **C — Bounded autonomy** | L3 (`ops_monitor`, policy + budgets) | **Enterprise**, demand-gated | "Hands-off remediation inside limits you set — with a kill switch, per-class budgets, and a SHADOW mode to watch what it *would* do first." |

⚠ **The Edition column is the intended packaging, not today's build (checked 2026-09-23).** The assist agent ships
from **Professional**, not All — Personal answers 503 (`CP-14`). The intelligence agent behind the investigation
tier, the approvals inbox and the autonomy policy (`inspecto-intelligence`) is staged by **no** edition, so no
rung beyond the assist skills is in any bundle yet.

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
JSON + text/regex parsing (`ING-5`, 2026-07-07) · Expectation engine (`ING-6`, 2026-07-07) · Professional
security (`SEC-7`, 2026-07-08) · the live end-to-end pipeline check (`PIP-1`, 2026-07-07).

**Still open:**

- **Notification delivery** — SMTP + webhook channels ship, but `INC-3` stays partial (one global preference
  set). ⚠ `REQUIREMENTS.md` §5 lists `INC-3` as closed; its spec, the record, says partial.
- **Packaging verification** — the recorded Professional runtime check covers five of the twelve modules (`PKG-4`).
- **The agent-framework pin has regressed** — the parent pom pins eoiagent `0.2.0-SNAPSHOT` and CI rebuilds it
  from upstream (`EOI-7` half a). Publishing its artifacts (half b) is no longer a gate: it became a standing
  refusal on 2026-09-15.

(Detail and sequencing: [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §5–6 and the capability specs it indexes.)
