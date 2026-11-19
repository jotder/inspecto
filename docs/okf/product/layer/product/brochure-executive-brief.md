# Inspecto — Executive Brief

> Audience: executives & sponsors · Status date: **2026-11-19** · ~3 minutes · Refreshed from
> [`EDITIONS.md`](../../../../EDITIONS.md), `offerings/*.toon`, the
> [competitive landscape](brochure-competitive-landscape.md) and the
> [maturity assessment](plan-maturity-assessment.md). The 2026-07-07 version predated the Basic / Standard /
> Advanced editions and listed as gaps several things that have since shipped.

## What it is

**Inspecto** is the end-to-end data operations platform for regulated environments where data can't leave:
acquire the hard formats, reconcile, investigate, and prove it to an auditor — from **one ~90 MB self-contained
artifact** that runs on a laptop, an air-gapped server or a container. Basic and Standard need **no external
runtime services**; Advanced keeps Incidents and Cases on PostgreSQL. One declarative config file onboards a feed.

**It replaces four tool categories at once:** a NiFi-style collection and Pipeline layer, a Superset-style
self-service BI layer (Studio), a Jira-style operations workflow (Alerts → Incidents → Cases, with four-eyes
approval), and the scripts around them. On top sit Link Analysis and Investigations, Reconciliation → Breaks,
scoring, screening, anomaly and predictive add-ons, and installable Space Templates for telecom revenue assurance,
fraud, bypass, re-rating, partner settlement, mobile money, AML and payment fraud. Inspecto LA (Link Analysis)
also ships as an application of its own.

**The buyer is a situation, not an industry:** organisations that receive data as files from systems they don't
control, under regulation that says the data cannot leave. Telecom is the cheapest place to win the first
customers, because Inspecto decodes ASN.1 call records natively.

## Why it wins

1. **Governance the assurance vendors don't publish** — four-eyes approval on configuration and on outbound
   actions, a tamper-evident audit chain, evidence-grade Link Analysis (sealed step history, a verifiable Dossier,
   an audited reveal of masked values). None of the six telecom / fintech assurance vendors in the
   [capability matrix](brochure-competitive-matrix.md) shows these on its public pages.
2. **Lean enough to go where heavy stacks can't** — one artifact, a small dependency surface under an SBOM guard,
   no cluster to run.
3. **One platform across the chain** — an acquisition gap becomes an Incident carrying lineage to the dashboard
   it made stale; Reconciliation → Breaks → Incidents is one product here and two or three vendors elsewhere.

## Editions and business model

Editions are **build flavours of one codebase** — no forks, one fix lands everywhere. They never sell weaker
analysis; they differ by who uses Inspecto and how much governance surrounds it. *Basic finds the problem;
Standard owns it; Advanced governs it.*

| Edition | For | What it adds |
|---|---|---|
| **Basic** | one analyst on one machine | *Add data* (upload, an import inbox, a PostgreSQL snapshot), Pipelines, the Parquet lakehouse, Studio dashboards, data-quality Expectations, Link Analysis at the Explorer level with Entity Lists. No sign-in |
| **Standard** | a team | sign-in through the customer's identity provider, roles and attributed audit; scheduled feeds and remote Connections; Alerts → Incidents → Cases; Reconciliation; Investigations and the Geo map; the scoring, screening, anomaly, predictive, regulatory-reporting and real-time detection add-ons; the AI Assistant; the domain Space Templates |
| **Advanced** | an organisation | Access Policies down to single rows and per-Space isolation; shared PostgreSQL state for several servers; the intelligence agents; the ASN.1 telecom decoders |

Billing is per node. An offline signed entitlement file narrows which modules run; the node count is recorded,
not enforced. Prices are not published.

## Where it stands (2026-11-19)

- **Pilot-ready, not yet RFP-proven.** The internal assessment (2026-10-20) places every client-facing item at
  L2 (prototype) or L3 (pilot-ready); none is production-ready. Link Analysis and Multi-entity / Group score
  production-ready on their own and are held back only by the platform's missing operational proof.
- **Built since the last brief:** the three editions; the add-ons above; partner Apps installed live as signed
  packages; a deploy kit (reverse-proxy templates, preflight, upgrade, bundle verification, off-site backup);
  real-time detection — windowed Alert Rules evaluated on each commit of a stream Pipeline, p95 2.4 s at 5,000
  events/s in the test harness and 1.9 s on a real broker.
- **Not yet in any customer's hands.** No release has been tagged since `v3.12.0` (2026-06-05), so nothing above
  is in a downloadable bundle, and the signed-release, SBOM and vulnerability-scan path has never run end to end.
  Release 4.0.0 is in preparation.
- **Proof gaps that cap every module:** HA / DR has a one-machine drill only, and no restore has been drilled;
  Kubernetes scale-out is a signed design, not built; no pen test; no SOC 2 or ISO 27001 audit window opened;
  every pack is proven on synthetic data only; no reference customer.
- **Content trails the category leaders:** small scenario libraries (AML 4, payment fraud 4, mobile money 7 at the
  2026-10-20 assessment, against leaders' hundreds); no sanctions / PEP list feeds; no regulator-specific SAR / STR
  format; no inline allow / block on a stream (card authorisation, pre-call blocking); the agentic investigation
  is a deterministic Incident Brief, one of six planned slices.
- **Capacity:** measured end to end at ~8M cells/s on a six-core laptop; sized to ~2.5 billion 100-column rows a
  day on an 8-core server — the server factor is an estimate until one run on the target hardware.

## Competitive position

- **Closest in shape:** Definite (the same DuckDB lakehouse, but on Kubernetes and without incident management or
  link analysis); Palantir Foundry at six to seven figures; Cloudera CDP (cluster-heavy); Microsoft Fabric
  (cloud only).
- **Closest by buyer:** Mobileum RAID, Subex HyperSense, LATRO and peers in telecom assurance; i2 Group in
  investigation; Duco and SmartStream in reconciliation. Inspecto meets them at parity on the assurance core and
  competes below their floor on footprint and price.
- **A single-lane specialist wins a single-lane comparison.** Inspecto wins on the joins between lanes and in two
  deep lanes: hard-format file acquisition, and Reconciliation → Breaks → Incidents.

## Decisions we need

1. **Cut release 4.0.0** — the first under the new edition names; it makes everything built since June
   purchasable and runs the signing, SBOM and vulnerability-scan path for the first time. *Days.*
2. **Fund the operational proof** — a two-host Linux HA drill and a restore drill with committed RTO / RPO
   (*weeks*; on its own it moves Link Analysis and Multi-entity to production-ready), then a pen test and a SOC 2
   Type II window (*months*).
3. **Publish pricing or not** — three edition bundles exist; a buyer who cannot see a price assumes enterprise
   sales.
4. **Inline real-time decisioning** — detection is built; inline allow / block is held until a bid needs it.
   Build, partner, or keep declining card-authorisation and pre-call-blocking RFPs.
5. **One reference deployment on real data** — the only route to "proven", and where vendor-feed mapping and
   threshold calibration finally happen.

*Deeper reading: [`brochure-competitive-landscape.md`](brochure-competitive-landscape.md) (positioning and claims
register) · [`brochure-competitive-matrix.md`](brochure-competitive-matrix.md) (capability by capability) ·
[`plan-maturity-assessment.md`](plan-maturity-assessment.md) (scores and what raises them) ·
[`EDITIONS.md`](../../../../EDITIONS.md) (the edition matrix).*
