# Link Analysis — telecom fraud demo storyboard (customer wish list)

**Status: IN FLIGHT 2026-10-11 — sprint plan approved (§5). §1-§4 fit column was a first pass; §5 carries the
audited corrections (code-verified, not yet run in the app).** Sibling of
[`la-demo-readiness-plan.md`](la-demo-readiness-plan.md), which covers the *governance* story (op log, Dossier,
four-eyes, masking). This page is the *telecom-fraud analyst* story. Vocabulary per `docs/GLOSSARY.md`
(Dataset, Collector, Incident, Alert Rule, Measure, Pipeline).

Fit key: **HAVE** (manual/code shows it) · **PARTIAL** · **GAP** (not found) · **VERIFY** (could not tell).

## 1. Wish list, deduplicated (source note numbers in brackets)

| # | Capability | Notes folded in | Fit |
|---|---|---|---|
| W1 | Start from one suspect MSISDN: typed, from a fraud alert, or from a Case [1] | | PARTIAL — typed seed/Entity List yes; start-from-Alert/Case VERIFY |
| W2 | Grow the network gradually to 4 degrees, manual traversal [2, 12] | Progressive: 1-2 load first, 3-4 on demand/background [16] | HAVE — expand per hop with direction/kind/window/fan-out/budget; background job VERIFY |
| W3 | Interactive graph + table, filters, drill-down, risk indicators [3] | Expand/collapse, hide, watchlist/blacklist, attach, export [21] | PARTIAL — graph, Data strip, expand/hide/keep/exclude, export yes; add-to-blacklist GAP |
| W4 | Enrichment: subscriber, device, fraud history [4, 13] | CRM/KYC, alarms, blacklist, case history on node panel | GAP/VERIFY — multi-Dataset nodes list their Datasets, but no attribute-card enrichment found |
| W5 | Export/attach to Case Manager [5, 14] | | HAVE — *Attach to Case*, *Save this analysis*, new Case from nodes |
| W6 | Platform: ingestion, data, query, Execution Engine, App APIs [6, 11] | CDR, HLR/EIR, CRM, provisioning ingest | HAVE (Pipelines/Collectors/Datasets, 75 LA routes) — telecom Datasets must be seeded |
| W7 | Fraud typologies: Wangiri, IRSF, SIM box, subscription fraud, mules [7] | | PARTIAL — structuring/cash-out patterns exist for money; telecom patterns GAP |
| W8 | Rotated numbers, forwarding chains, shared handsets/identities [8] | | PARTIAL — identity resolution + shared-entity links yes; forwarding chain = a link kind (below) |
| W9 | Show/hide by connection type [9, 10] | 8 link kinds, table in note 10 | PARTIAL — link-kind filter exists; the 8 kinds need Datasets/mappings (§3) |
| W10 | Guardrails [16] | supernode, min strength, per-degree cap, 30-day window, progressive, timeout/audit | PARTIAL — see §4 |
| W11 | Node indicators + propagated risk score [17-19] | weights 1.0/0.6/0.35/0.15, explainable | GAP — only Suspicion score, centrality, PageRank found; distance-decayed propagation not found |
| W12 | Community detection [20] | components, Louvain, raise alarms | HAVE for Louvain/label-propagation/components; "raise alarm" via Measure + Alert Rule VERIFY |

## 2. Storyboard (one analyst, one ring, ~15 min)

Persona: a fraud analyst. Story: an IRSF/Wangiri alert fires on `MSISDN-A`; the analyst unmasks a SIM-box ring.

| Scene | What the audience sees | Wish | Needs seeded |
|---|---|---|---|
| 1. Alert in | Alert names `MSISDN-A`; one click opens Link Analysis with it as the root | W1 | Alert → LA deep link (VERIFY) |
| 2. First look | Degree 1: voice + SMS neighbours; footer states node/link counts and any cap | W2, W3 | CDR voice + SMS Datasets |
| 3. Widen, gradually | Expand to degree 2, then 3, then 4 on demand; per-degree cap message shown | W2, W10 | fan-out cap + budget set low enough to trip once |
| 4. Slice by link kind | Toggle Voice / SMS / Forwarding / Shared device / Shared identity / SIM history / Shared payment | W9 | one mapping per kind (§3) |
| 5. Ring appears | Shared IMEI joins A, B, C; forwarding chain exposes a rotation | W8 | HLR/EIR IMSI-IMEI + redirect fields |
| 6. Why this node | Node panel: indicators, contributing factors, case history | W4, W11 | enrichment + risk (GAP) |
| 7. Community | Louvain colours the ring; Measure + Alert Rule on ring size | W12 | none beyond data |
| 8. Act | Add to watchlist (Entity List), attach graph to a Case, export | W3, W5 | Case Manager reachable |
| 9. Under the hood | Same actions via App APIs; Pipelines that ingested CDR/HLR/CRM | W6 | Pipeline run evidence |

## 3. Connection types → what must exist (note 10)

| Link kind | Source Dataset | Mapping (from → to) | Fit |
|---|---|---|---|
| Voice call | Voice CDR | A-number → B-number | HAVE |
| SMS | SMS CDR | orig MSISDN → term MSISDN | HAVE |
| Call forwarding | Voice CDR redirect, HLR | A → forwarded-to number | VERIFY (needs a Dataset with that pair) |
| Shared device | CDR/HLR/EIR IMEI/IMSI | MSISDN ↔ MSISDN via common IMEI | PARTIAL — needs a derived self-join Dataset or identity resolution; not a direct column pair |
| Shared identity | CRM/KYC | MSISDN ↔ MSISDN via ID doc/address | PARTIAL — same: derived Dataset or identity resolution |
| SIM history | Provisioning | old subscriber ↔ new subscriber | PARTIAL — same |
| Shared payment | Recharge/billing | MSISDN ↔ MSISDN via voucher/card/agent | PARTIAL — same |

Design decision to take: the four "shared X" kinds are *bipartite* in the raw data (MSISDN–IMEI). Either (a) a Pipeline
step derives MSISDN–MSISDN pair Datasets, or (b) the graph keeps IMEI/voucher as a node type (Entity Types already list
imei and handset). (b) is cheaper to demo and truer; (a) matches the customer's picture. **Ask the customer which.**

## 4. Guardrails vs what exists (note 16)

| Guardrail | Fit |
|---|---|
| Supernode suppression (display, don't expand, flag "high connectivity", override) | GAP as an *expand guard* — the UI "super node" is a visual aggregate; the hop-ladder has fan-out cap + candidate degree bounds, which may cover it (VERIFY) |
| Minimum link strength | HAVE — expand option minimum events / distinct days |
| Per-degree cap, UI says when truncated | PARTIAL — fan-out cap + budget exist; per-degree ranking by strength+risk VERIFY |
| Time window (default 30 days, widen to retention) | HAVE — `window` op; the 30-day default VERIFY |
| Progressive expansion; 3-4 in background | VERIFY (Job-backed graph runs exist for heavy algorithms) |
| Query timeout + audit | HAVE audit (`LINK_*` events); per-degree timeout VERIFY |

## 5. Sprint plan (approved 2026-10-11)

**Approach — data first, code second.** One Pipeline-built Dataset `telecom_links` (`a_msisdn, b_msisdn, link_kind,
first_seen, last_seen, events, total_duration_s, via`; `link_kind` ∈ voice | sms | forwarding | shared_device |
shared_identity | sim_history | shared_payment) feeds an **Investigation**, which already carries the hop ladder,
fan-out cap ranked by event count, budget + truncation wording, window, four-eyes and replay. The four "shared X"
kinds are folded to MSISDN–MSISDN pairs in SQL (shared key in `via`), so no bipartite-fold algorithm is needed.
Per-MSISDN indicators come from a second SQL Dataset `telecom_msisdn_indicators`. Code goes only where the gaps are
real.

Verified 2026-10-11 by two read-only audits (backend + UI), replacing the VERIFY rows above:
* No seed/depth for entity graphs in exploration; no `?seed=` deep link; Alerts link only `?investigation=`.
* `candidateDegreeMax` drops a hub entirely — no "shown, not expanded", no override.
* No default window (unbounded unless a `window` op is appended); expand is synchronous, bounded only by `budget`.
* No propagated / distance-decayed risk; `suspicionScore` is a 5-factor structural composite, weights per request.
* Node dialog has attributes + provenance only; no score, factors, list status or actions beyond expand/focus.
* Kind filter is node-kind only; link kinds are plain text in the legend.
* Entity Lists support `allow | block | watch | exclusion` (`POST /entity-lists/{id}/members`) — no node-level button.
* Attach to Case attaches a sealed snapshot (save first); creates no Incidents.
* Multi-Dataset projection returns one node row per mapping on the server; the manual says "one node" — reconcile.

| Sprint | Deliverables |
|---|---|
| 0 — data (3-5 d) | Synthetic telecom Datasets + planted rings per typology, `telecom_links` + `telecom_msisdn_indicators` Pipelines; Wangiri / SIM-box feasibility on pattern stages |
| 1 — "Investigate a number" | `?seed=&entityType=` deep link + starter card → Investigation, auto-expand degrees 1-2 with Telecom profile presets (30 d, minEvents, fan-out, budget); "Expand next degree" to 4; link-kind chips; "Investigate in Link Analysis" from Alert / Incident / Case |
| 2 — risk + enrichment | `propagatedRisk` algorithm (own + Σ neighbour × w[d], default 1.0/0.6/0.35/0.15, factors returned); node panel shows score, factors, CRM/KYC, list status, case history |
| 3 — guardrails + actions | Hub flag `highConnectivity` (shown, not expanded, audited override); node actions add to watch/block list, hide, attach; one-step attach (+ optional Incidents); Louvain + "Watch this measure"; telecom value measures if stages can't express them |
| 4 — harden + rehearse | Smoke + browser walkthrough of §2, volume check, distil to OKF, archive this page |

Deferred: background (Job-backed) expand — degrees 3-4 fit the budget on demo data.

**In flight (parallel, isolated worktrees):** seed data (S0); `propagatedRisk` (S2); hub flag on expand (S3);
Sprint 1 UI flow; pattern-stage feasibility (read-only).

## 6. Open items before this can be rehearsed

1. **Seed**: a synthetic telecom Space (CDR voice/SMS, HLR/EIR, CRM, recharge, provisioning) with one planted ring per
   typology. Fits the existing `la-showcase` template work (`LA-DEMO-SEED-1`).
2. **Decide** bipartite vs derived-pair modelling for the four "shared X" kinds (§3).
3. **Risk**: scope the propagated score (W11). Smallest honest version: a per-node indicator score (note 18) plus a
   distance-weighted neighbour sum computed as a Measure; weights configurable. Likely new backend work.
4. **Enrichment** (W4) and **add to blacklist** (W3): confirm whether Entity Lists / node detail can carry them.
5. **Alert/Case → LA deep link** (W1).
6. Verify every VERIFY row by driving the app; update this page, then add rows to `docs/BACKLOG.md` §3.12.

## 7. Raw notes (verbatim intent, for provenance)

The customer's source list, with the notes' numbering, is the table in §1's bracket references; the three data-model
tables (link types, source data, guardrails) are reproduced in §3, §4 and below.

| Source | Fields needed |
|---|---|
| Voice CDR | A-number, B-number, start time, duration, call type, redirecting/forwarding number, IMSI, IMEI, destination type |
| SMS CDR | originating and terminating MSISDN, timestamp, message type |
| HLR / HSS / EIR | MSISDN-IMSI-IMEI mapping, forwarding settings, SIM-swap events |
| CRM / KYC | identity attributes, activation date, subscriber type, KYC completeness |
| Recharge / provisioning | voucher or agent id, top-up events, SIM lifecycle events |

Node indicators (note 18): blacklisted or in open/closed fraud case · fraud alarms in last N days · recent SIM activation
with high outbound/international activity · shared IMEI with a known fraudulent number · traffic to high-risk/premium
destinations · abnormal patterns (very short calls, high volume, one-way traffic).
Propagated risk (note 19): node risk = own indicator score + weighted neighbour contribution decayed with distance from
the flagged node; default weights 1.0 / 0.6 / 0.35 / 0.15 for degrees 1-4, configurable per operator; score and
contributing factors shown in the node detail panel.
