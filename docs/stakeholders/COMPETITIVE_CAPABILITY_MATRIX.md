# Inspecto — Competitive Capability Matrix

> Audience: product owner and market/sales · Status date: **2026-10-07** · Companion to
> [`COMPETITIVE_LANDSCAPE.md`](COMPETITIVE_LANDSCAPE.md) (the living two-sided landscape) and to the offering map
> in [`../superpower/module-architecture-reorg-plan.md`](../superpower/module-architecture-reorg-plan.md) §8a
> (where every Inspecto gap below is filed as a module or pack).
>
> **What this is.** A capability-by-capability comparison of Inspecto against six telecom / fintech assurance
> vendors, mapped onto Inspecto's module model (platform base, domain-neutral add-ons, function packs, industry
> packs). **Vendor columns compare published claims, not verified product behaviour** — every mark comes from
> the vendor's public product pages on the date above (see *Confidence* and *References*). Re-verify before any
> external use older than a quarter.

## 1. The vendors

| Vendor | Product lines (as published) | Deployment / segments |
|---|---|---|
| **LATRO** | *Defend* (Bypass Shield, SMS Blaster Shield, Interconnect Shield, Fraud Shield, Scammer Shield) · *Assure* (Assure Rev, Assure Rev LITE, Assure Biz, Assure Digital) · *Explore* (SalesX, MarketingX) · *Assure Fintech* (mobile-money RA, fraud & risk, AML, link-analysis investigation) · TAMS and RAFM managed services · Forensics | cloud-native or on-prem, heavy managed-service component; MNOs, MVNOs, mobile-money providers, emerging markets |
| **Subex** | *HyperSense* AI platform (RA, FM, business assurance, partner settlement) · legacy *ROC* RA / FM / Partner Settlement · Integrated Bypass Fraud (test call generator + FM) · *FraudZap* (fast-deploy real-time fraud, incl. mobile money) · *AI Agent Squads* · Partner Lifecycle Management | on-prem enterprise installs, fast-deploy cloud for FraudZap; MNOs / CSPs |
| **Mobileum** (incl. former **WeDo Technologies** RAID) | *RAID* integrated risk management (revenue assurance, fraud, IoT and mobile-money risk) and *RAID Agentic* (2026) · Active Intelligence Platform · roaming (GlobalRoamer, steering) · network security (signalling, SMS and voice firewalls) · testing and observability · customer experience · data insights and monetisation · managed services | cloud, hybrid or on-prem; MNOs, MVNOs (roaming), mobile-money providers, enterprises (caller ID, IoT) |
| **Neural Technologies** | *Optimus* platform: revenue protection (scam blocking, fraud, RA/BA, credit and application risk, AML) · data integration (mediation, charging, complex event processing, streaming analytics, ActivML) · signalling (SMS/USSD gateways, EIR, SS7 stack) | modular, on-prem or embedded; operators and mobile-money providers |
| **TEOCO** | SmartCOGS (network cost and invoice assurance) · SmartXDR · SmartRoute · SmartCircuit · SmartOps · HELIX (5G service assurance) · SmartServices (managed) | on-prem plus managed services; tier-1/2 operators; network cost and service assurance, little fraud |
| **Araxxe** | End-to-end billing verification (retail, roaming, data bundles, pay TV, mobile money) · interconnect fraud protection (voice, messaging and OTT bypass, flash calls, CLI refiling) · QoS monitoring · regional observatories | subscription managed service, nothing installed at the operator; MNOs and carriers |

## 2. The matrix

**Y** offered · **P** partial or adjacent · **N** not found on the pages checked. The Inspecto column is grounded in
the repository (shipped modules and Space Templates); the last column names the module that covers it, or the
backlog row that would.

| # | Capability | LATRO | Subex | Mobileum | Neural | TEOCO | Araxxe | **Inspecto** | Inspecto module · gap row |
|---|---|---|---|---|---|---|---|---|---|
| A | Revenue assurance (billing, usage, interconnect controls) | Y | Y | Y | Y | Y | Y | **Y** | `telco-ra` Space Template |
| B | Generic reconciliation engine | Y | P | P | Y | Y | P | **Y** | Reconciliation add-on |
| C | Re-rating / tariff validation | Y | P | Y | Y | P | Y | **P** | SQL controls in `telco-ra` only |
| D | Fraud management (rules over CDRs / events) | Y | Y | Y | Y | P | N | **Y** | Alerts + `telco-fraud` Space Template |
| E | Bypass / SIM-box detection | Y | Y | Y | Y | N | Y | **P** | CDR-based only |
| F | Signalling detection (SS7 / Diameter / SIP, probes, firewalls) | Y | P | Y | P | P | N | **N** | out of scope (network probes) |
| G | Test call generation | Y | Y | Y | N | N | Y | **N** | out of scope (hardware / service) |
| H | Interconnect / wholesale fraud (Wangiri, IRSF, flash calls, CLI) | Y | Y | Y | Y | P | Y | **P** | IRSF / Wangiri from CDRs · `TELCO-FRAUD-CONTENT-GAPS-1` |
| I | Real-time / pre-call blocking | Y | P | Y | Y | N | N | **N** | `REALTIME-DECISIONING-1` |
| J | Entity risk scoring | Y | P | Y | Y | N | N | **Y** | Scoring & Lists add-on |
| K | Watch / deny lists | Y | P | P | Y | N | N | **Y** | Scoring & Lists (Entity Lists) |
| L | Explainable ML anomaly detection | Y | Y | Y | Y | P | P | **P** | Expectation baselines and forecast bands only · `ANOMALY-DETECTION-1` |
| M | Link analysis / graph investigation | Y | P | P | P | N | N | **Y** | Link Analysis & Geo add-on |
| N | Case management / investigation | P | Y | Y | Y | P | P | **Y** | Incidents + Case Management add-ons |
| O | Workflow and SLA | N | P | P | P | P | N | **Y** | Workflow & SLA add-on |
| P | Business assurance (margin, forecast, KPI drift) | Y | Y | Y | Y | Y | P | **Y** | `business-assurance` Space Template |
| Q | Partner / settlement / wholesale | P | Y | P | P | Y | P | **P** | partner-statement reconciliation in `telco-ra` |
| R | Mobile money / fintech assurance | Y | Y | Y | Y | N | Y | **P** | `payment-fraud` (card-centric) · `PACK-MOBILE-MONEY-1` |
| S | AML typologies, sanctions / PEP screening | Y | N | N/P | P | N | N | **N** | `PACK-AML-1` + `SCREENING-1` |
| T | Regulatory reporting (SAR / STR) | Y | N | P | N | N | N | **N** | `REGULATORY-REPORTING-1` |
| U | 5G / IoT / cloud / digital service assurance | Y | P | Y | P | Y | P | **N** | candidate industry pack (cable / IPDR, digital) |
| V | Sales, commissions, marketing, churn | Y | P | Y | N | N | N | **N** | `PREDICTIVE-ANALYTICS-1`, `PACK-SALES-MARKETING-1` |
| W | Identity / digital trust (caller ID, device, ID checks) | N | P | P | P | N | N | **N** | not planned |
| X | Network analytics / capacity / inventory | P | P | P | N | Y | N | **N** | not planned (different buyer) |
| Y | Cybersecurity (network / IoT security) | P | P | Y | P | N | N | **N** | not planned (firewall territory) |
| Z | Managed services / RAFM as a service | Y | Y | Y | P | Y | Y | **N** | a delivery model, not a module |
| AA | Generative AI / agentic investigation | P | Y | Y | P | P | N | **P** | AI assist + intelligence agents; no agent that works an Incident |

## 3. Reading the matrix

**Parity — the assurance core.** Revenue assurance, reconciliation, rule-based fraud over CDRs, risk scoring,
watch lists, case management and business assurance are offered by most vendors and by Inspecto.

**Where Inspecto leads.** Link analysis is a clear published capability only at LATRO; workflow and SLA is weak or
absent elsewhere. None of the six shows, on its public pages: maker-checker with four-eyes approval on
configuration and on outbound actions; a tamper-evident audit trail; data-quality Expectations and lineage from a
full ingest-and-pipeline platform (rather than an assurance tool over someone else's ETL); a multi-entity /
group-regulator model; air-gapped on-prem editions; installable Space Templates; publication to an external BI
tool.

**Where Inspecto trails — gaps worth closing (filed in `docs/BACKLOG.md`):**

| Gap | Who has it | Row |
|---|---|---|
| Agentic investigation (an agent that explains and works an alert) | Mobileum RAID Agentic, Subex AI Agent Squads | to file: `AGENTIC-INVESTIGATION-1` |
| AML typologies, sanctions / PEP screening, SAR / STR reporting | LATRO (full), Neural (partial) | `PACK-AML-1`, `SCREENING-1`, `REGULATORY-REPORTING-1` |
| Mobile-money assurance (wallet / agent / bank reconciliation, agent fraud) | LATRO, Subex, Mobileum, Neural, Araxxe | `PACK-MOBILE-MONEY-1` |
| Explainable ML anomaly detection | all but TEOCO and Araxxe | `ANOMALY-DETECTION-1` |
| Real-time / pre-call decisioning | LATRO, Mobileum, Neural | `REALTIME-DECISIONING-1` |
| Partner settlement | Subex, TEOCO | to file: `PACK-PARTNER-SETTLEMENT-1` |
| Sales / marketing / churn analytics | LATRO, Mobileum | `PREDICTIVE-ANALYTICS-1` |

**Out of reach unless the product changes direction:** signalling firewalls and probes (F), test call generation
(G), cybersecurity (Y), identity / digital trust (W) and network inventory (X) — they need network equipment,
carrier interconnects or a different buyer. Managed services (Z) is a business decision, not a module.

## 4. Confidence

- **Evidence level.** Vendor marks come from marketing pages, not product documentation or demos.
- **LATRO** — read in full from its product pages (23 pages).
- **Subex** — `subex.com` refused automated fetching (HTTP 403); the column rests on search snippets, press
  releases and secondary profiles, and several P marks are unverified. Its March 2026 investor presentation was
  not read and should be the first check.
- **Mobileum** — product index and RAID pages read; *watch lists* and *link analysis* are inferred from the WeDo-era
  RAID product, not from current pages.
- **Neural Technologies, TEOCO, Araxxe** — homepage plus at most one search each; an **N** means "not found", not
  "confirmed absent". TEOCO's former RAID fraud line no longer appears on its site.
- **Inspecto** — "Y" means a module or shipped Space Template exists; it does not claim parity of depth with a
  vendor's mature product.

## References

Read 2026-10-07.

- LATRO: <https://latro.com/>, product pages under <https://latro.com/products/> (Bypass Shield, SMS Blaster
  Shield, Fraud Shield, Interconnect Shield, Scammer Shield, Assure Rev, Assure Rev LITE, Assure Biz, Assure
  Digital, SalesX, MarketingX, Forensics, TAMS, RAFM managed services, Assure Fintech and its three module pages).
- Subex: <https://www.subex.com/products/> (via search), press releases on RAFM modernisation (North Africa) and
  FraudZap (North America); <https://www.moneylife.in/article/subex-launches-integrated-bypass-fraud-solution/24255.html>.
- Mobileum: <https://www.mobileum.com/products/>, <https://www.mobileum.com/ecosystems/raid/>,
  <https://www.mobileum.com/ecosystems/raid/agentic-ai>,
  <https://www.telecomtv.com/content/ai/mobileum-showcases-signal-to-value-at-mwc-barcelona-2026-55100>.
- Neural Technologies: <https://neuralt.com/>.
- TEOCO: <https://www.teoco.com/products/>.
- Araxxe: <https://www.araxxe.com/>.
