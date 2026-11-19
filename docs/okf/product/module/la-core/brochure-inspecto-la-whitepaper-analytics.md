---
type: Brochure
title: Inspecto LA whitepaper — Analytical depth, graph algorithms and explainable detection
description: Part 2 of 6 of Inspecto LA whitepaper: · Analytical depth, graph algorithms & explainable detection
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA whitepaper — Analytical depth, graph algorithms and explainable detection

Part 2 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · previous: [Executive summary and the forensic lifecycle](brochure-inspecto-la-whitepaper-summary-lifecycle.md) · next: [Systems architecture and data plane engineering](brochure-inspecto-la-whitepaper-architecture.md).

# 3 · Analytical depth, graph algorithms & explainable detection

Inspecto LA avoids the "black box" machine learning trap common in legacy fraud software. High-stakes financial and intelligence decisions require **transparent, explainable heuristics** whose mathematical factors can be explicitly articulated to regulators and judges.

### 3.1 The 29 in-process graph algorithms

The algorithm library is the platform module `inspecto-graph` (pure Java, no Inspecto dependencies; a line-for-line port of the browser's `graph-analysis.ts`). Link Analysis keeps the catalogue (`Algorithm`, 29 entries), the dispatch, the cost ceilings and the budget. The browser runs the TypeScript twin of **28** of the 29 in every edition (`propagatedRisk` is server-only); a **server Graph Run** (`POST /inv/graph/runs`) runs all 29 over an Investigation's Working Set or over the link index, in a bounded in-process pool, and is Standard and above. Java and TypeScript assert the same hand-derived fixtures with the same tie-breaks (`GraphAlgorithmsParityTest`, `GraphSuspicionParityTest`, and the specs that read the same fixtures).

| Category | Algorithm (API id) | Server cost class | Primary use case & forensic relevance |
| :--- | :--- | :---: | :--- |
| **Communities & structure** | **Louvain communities** (`louvainCommunities`) | job | Dense groups such as coordinated mule rings. |
| | **Label-propagation communities** (`detectCommunities`) | job | Fast community identification on large subgraphs. |
| | **Cliques** (`cliques`, Bron–Kerbosch with pivoting) | job | Maximal complete subgraphs: tightly knit cells. |
| | **k-core** (`kCore`) | inline | Prunes peripheral noise to the cohesive core of a network. |
| | **Connected components** (`connectedComponents`) | inline | Separates unrelated clusters in one Working Set. |
| | **Triangle count** (`triangleCount`) | inline | Closed triads: loops of credit or contact. |
| | **Find cycles** (`findCycles`) | job | Every simple directed cycle up to a limit and hop count: circular funds routing. |
| | **Is forest** (`isForest`) | inline | Tests whether a graph is acyclic. |
| **Centrality** | **PageRank** (`pageRank`, damping 0.85) | inline | Structurally dominant entities by inbound influence. |
| | **Betweenness centrality** (`betweennessCentrality`) | job | Bottleneck nodes that bridge otherwise separate networks. |
| | **Closeness centrality** (`closenessCentrality`) | job | Entities with the shortest paths to all others. |
| | **Eigenvector centrality** (`eigenvectorCentrality`) | job | Influence weighted by connections to influential entities. |
| | **Katz centrality** (`katzCentrality`, defaults $\alpha=0.1$, $\beta=1$) | job | Attenuated path-based influence. |
| | **HITS** (`hits`) | job | Separates hubs (relays) from authorities (collection points). |
| | **Degree centrality** (`degreeCentrality`) | inline | Distinct counterparties and volume per entity. |
| **Paths & flow** | **Shortest path** (`shortestPath`) | inline | Fewest-hop route between two entities. |
| | **Weighted shortest path** (`weightedShortestPath`, Dijkstra, cost $1/\text{weight}$) | inline | Route weighted by inverse tie strength. |
| | **All paths** (`allPaths`) | job | Every route between two entities within bounds. |
| | **Maximum flow** (`maxFlow`, Edmonds–Karp) | job | Maximum capacity between two accounts; the result also returns the **minimum cut**, the set of links whose removal separates them. <!-- vocab-allow: Max-Flow is the algorithm name --> |
| | **Bridges** (`bridges`) | inline | Single links whose removal splits a network. |
| | **Articulation points** (`articulationPoints`) | inline | Intermediaries whose removal disconnects cells. |
| | **Maximum spanning forest** (`maximumSpanningForest`, Kruskal) | inline | The dominant backbone across fragmented clusters. |
| | **Descendants** (`descendants`) | inline | Everything reachable downstream of an entity. |
| **Neighbourhood & similarity** | **Neighborhood** (`neighborhood`) | inline | The k-hop neighbourhood of a root. |
| | **Ego network** (`egoNetwork`) | inline | The immediate local network around a suspect. |
| | **Jaccard similarity** (`jaccardSimilarity`) | inline | Entities sharing phone, device or address sets. |
| | **Link prediction** (`linkPrediction`, common neighbours or Adamic–Adar) | job | Candidate links that are not observed. |
| **Forensic scoring** | **Suspicion score** (`suspicionScore`) | job | Explainable composite triage score, 0 to 100 (Section 3.2). |
| | **Propagated risk** (`propagatedRisk`, server only) | job | Directed taint tracking with per-origin attribution (Section 3.3). |

*The cost class is the catalogue's hint (inline answer or job); the budget, not the class, refuses a run that is too large. Complexity classes are omitted on purpose: they are textbook properties of the algorithms, not measurements of this implementation.* Server budgets are Space settings clamped by install-wide ceilings (Section 6.3); a run that exceeds its budget ends `BUDGET_EXCEEDED` with no result — never a silent cap.

**The investigative question each family answers.** The same 29 algorithms, grouped by the question an analyst brings to them:

| Family | Algorithms | Investigative question |
| :--- | :--- | :--- |
| Paths and reach | Shortest path, weighted shortest path, all paths, neighbourhood, ego network, descendants | How is this suspect connected to that one, and through whom? |
| Centrality | Degree, betweenness, closeness, PageRank, eigenvector, Katz, HITS | Who is the hub, the broker or the controller of this ring? |
| Communities | Connected components, Louvain, label propagation, k-core, cliques, triangles | Which accounts behave as one group, and which group is densest? |
| Structure | Articulation points, bridges, cycles, is-forest, maximum spanning forest | Which single number holds the network together? Is money going round in a loop? |
| Capacity and similarity | Maximum flow, Jaccard similarity, link prediction | How much value can move from A to B? Which two SIMs share the same contacts, as a swapped device would? |
| Scoring | Suspicion score, propagated risk | Which entities combine several risk signals at once? How far does a known bad actor's risk spread, decaying hop by hop up to 6 hops? |

**How a server Graph Run executes.**
1. The request is validated first: an unknown field, an out-of-range value or a missing node is refused (`422`) before any work.
2. The input is sized against the run's budget before work starts; over budget, the run ends `BUDGET_EXCEEDED` with the measured size and no result.
3. The input is either the Investigation's Working Set, loaded in memory, or a reference into the link index. The engine is chosen by that input type alone (`RoutingGraphEngine`) and never switches silently because a graph is large.
4. Each algorithm's node ceiling decides only whether the caller waits: at or under it, the request waits up to 3 s for an inline answer; above it, the request answers `202` and the run continues as a job. The ceiling is not a limit — the budget is (Section 6.2 lists the ceilings).
5. The algorithm runs in a fixed server pool (2 threads, queue 16 by default) and checks for cancel and deadline at regular checkpoints; an overrun ends with no result rather than a late or partial one.
6. Completed results are cached under a key that includes the caller's row scope, so a user who sees fewer rows never receives a result computed over more. Identifiers are masked after the cache, on the way out.
7. Ties are broken by one canonical ordering in Java and TypeScript, so the same graph ranks the same way in the browser and on the server.

### 3.2 Explainable multi-factor Suspicion Score

To accelerate triage without introducing opaque neural networks, Inspecto blends five structural properties into a composite **Suspicion Score** ($S_v \in [0, 100]$) computed per entity:

$$S_v = 100 \cdot \frac{\sum_{i=1}^{5} w_i \cdot \hat{f}_i(v)}{\sum_{i=1}^{5} w_i}$$

Each factor is normalised by dividing by its maximum over the graph, so $\hat{f}_i(v) \in [0, 1]$; the weights $w_i$ default to $1$ each (an analyst may set them per run), which makes the default score the plain mean of the five factors:
1. **$\hat{f}_{\text{deg}}(v)$** (`degree`): degree centrality (distinct counterparties).
2. **$\hat{f}_{\text{bet}}(v)$** (`betweenness`): betweenness centrality (sitting on shortest conduits).
3. **$\hat{f}_{\text{pr}}(v)$** (`pageRank`): PageRank (influence from connected entities).
4. **$\hat{f}_{\text{core}}(v)$** (`core`): $k$-core depth (embedding in highly connected groups).
5. **$\hat{f}_{\text{tri}}(v)$** (`triangles`): triangle count (involvement in closed triads).

**Explainability Invariant**: Every suspicion score rendered in the UI or emitted via the API is accompanied by its factor breakdown (illustratively, `factors: {degree: 0.82, betweenness: 0.94, pageRank: 0.65, core: 0.90, triangles: 0.78}`), so an investigator can point to the structural drivers that raised the entity. A single node cap, `suspicion_node_cap` (default 1,000), governs the browser refusal and the server's inline-or-job decision. The composite is a triage aid and not a classifier: no precision or false-positive rate is claimed (Section 7).

### 3.3 Propagated Risk (taint analysis)

When known illicit entities exist, an analyst must see how that contamination reaches others. **Propagated Risk** is server-only (no TypeScript twin, so no parity fixture; its tests are hand-computed graphs and `ControlApiGraphRunTest`). Each node has an **own** indicator score, supplied with the request (`nodeScores`, 0 to 100); each origin $o$ with a positive own score pushes a distance-weighted share to every other node $n$ it reaches within a bounded number of shortest hops:

$$\text{raw}(n) = \text{own}(n) + \sum_{o \ne n,\; d(o,n) \le H} \text{own}(o) \cdot w[d(o,n)] \qquad\qquad \text{score}(n) = \min(\text{raw}(n),\, 100)$$

where $d(o,n)$ is the shortest hop distance from the origin, $H$ is the number of weights (at most 6), and the weights default to $[1,\ 0.6,\ 0.35,\ 0.15]$ (each in $[0,1]$; a Space setting, `graph_run.propagated_risk_weights`, changes the default and the response echoes the weights used and whether they came from the request or the Space). The `direction` parameter says whether the walk follows the links, runs against them, or ignores direction. For every node the API returns its **top five contributions** (origin, distance, weight, contribution) and `contributors`, the count of all origins that contributed, so a cut factor list is said and never silent. `raw` is reported beside the capped `score`, so the cap never hides how far over it a node is.

### 3.4 Pattern packs and Value Measures

Inspecto LA ships built-in **pattern packs**: named starter motifs that pre-fill the motif builder (and any Space can author more as ordinary components). They are starting points for an analyst to review, not detection rules, and their thresholds are shown and editable in the toolbox.

```
  LAYERING CHAIN                 PASS-THROUGH INTERMEDIARY      INBOUND COLLECTOR
  [A] ──► [B] ──► [C] ──► [D]    [A] ──► [M] ──► [B]            [P_1] ──┐
  each hop after the one before, forward after receipt,         [P_2] ──┼─► [M] ──► [T]
  within 48 hours                within 48 hours                [P_3] ──┘
```

1. **Layering chain**: a chain of intermediaries (A → B → C → D); each hop must come after the one before it, within 48 hours (a flow claim, not just a topology claim).
2. **Pass-through intermediary**: an account that receives and then forwards (A → M → B), the forward after the receipt and within 48 hours.
3. **Inbound collector**: many parties converging into one account.
4. **Relay chain (any link kind)**: a generic relay hop over any link kind, the building block of relaying that hides where contact ends up.
5. **Circular flow** and **Shared associates**: hand the analyst to the Cycles and Similarity tools, because a closed loop is not a simple path motif.
6. **Structuring (smurfing)**: five or more deposits in a configurable amount band (the shipped band is 900 to under 1,000, to be set to the reporting limit) converging on one collector within 24 hours, which splits the total across two or more intermediaries that re-converge on one exit, each stage within 48 hours.
7. **Layering network** (split and merge) and **Circular financing** (two successive split-and-merge rounds that close on the starter) as branching, time-ordered patterns.

**Value Measures** summarise the money or contact behaviour of entities over a time window — for example pass-through ratio, velocity, structuring, cash-out concentration and benefit transfer. `GET /inv/value-measures` evaluates them over a Dataset, and a measure over an Investigation's Working Set can be bound to an Alert Rule (Section 5.6).

### 3.5 Synthetic showcase corpora

Inspecto LA bundles Space Templates with deterministic synthetic data for demonstration and for testing. All values are invented, the plants are planted, and **no detection rate is implied by any of them**.

**The mule ring (`la-showcase`).** Generated by `tools/gen-la-showcase-data.py` and pinned by `ControlApiLaShowcaseFiguresTest` and `LaShowcaseGoldenTest`: 112 accounts and 392 transfers over three landed days, with these planted stories — a hub account `MULE-HUB-01` receiving structured deposits, two relays `RELAY-01` and `RELAY-02` forwarding to an offshore account `OFFSHORE-77`, a circular layering ring `SHELL-A` through `SHELL-D`, a cash-out till `TILL-06`, and a benefit skim `SKIMMER-01`. What the pinned tests establish, honestly stated:
- The **Structuring** measure ranks `MULE-HUB-01` first, with 96 in-band legs from 12 payers.
- The **Pass-Through** measure flags **13 accounts**. Exactly two of them sit at the planted relay ratio (about 0.985): `RELAY-01` and `RELAY-02`. The list also contains `MULE-HUB-01` and other accounts. It is a triage list that needs an analyst, not a precision-100 detector.
- The **Cash-out concentration** measure puts `TILL-06` first with 97 % of cash-out value from 8 payers; the **Benefit-transfer** measure names `SKIMMER-01` with 6 recipients.
- Over the same corpus `MULE-HUB-01` ranks in the top three of the Suspicion Score, and the planted ring is the only (hence shortest) directed cycle — by construction, because the ordinary traffic is generated acyclic and triangle-free.

**Further showcase verticals** (each a Space Template with a seed script, a smoke runner, golden tests and a runbook; each reuses the same product and adds only data, saved views and scripts):
- **Mobile money (`la-mobile-money`)**: 420 wallets, 40 agents, 40 merchants and about 5,700 transactions over 14 days with seven planted stories, pinned by `LaMobileMoneyGoldenTest` and the `mm_links` Job's `MmLinksJobGoldenTest`; smoke runner `tools/la-mm-smoke.mjs`; runbook `docs/okf/product/solution-pack/la-mobile-money/admin-guide-showcase-runbook.md`.
- **Finance and audit (`la-audit`)**: procure-to-pay and the journal, seven planted stories, whose headline is the Dossier as an audit workpaper (seal, verify, change one byte, watch verification fail); `tools/la-audit-smoke.mjs`; `docs/okf/product/solution-pack/la-audit/admin-guide-showcase-runbook.md`.
- **Insurance claims (`la-claims`)**: synthetic claims for a Special Investigations Unit, a graph built from shared attributes (one phone, one address, one garage) rather than money moving; seven planted stories, nine algorithm scenes, Dossier external references (pointers that are never fetched and never trusted) and a 12-minute script; `LaClaimsGoldenTest`; `tools/la-claims-smoke.mjs`; `docs/okf/product/solution-pack/la-claims/admin-guide-showcase-runbook.md`.
- **Telecom**: the `telco-fraud` and `la-showcase` templates behind the Telecom industry pack (Section 3.6).

### 3.6 Industry packs and the Domain Profile

An **Industry pack** maps a vertical's vocabulary onto the neutral concepts: it relabels them, and adds its own Entity Types only under a neutral family (Party, Organisation, Account, Identifier, Device, Place, Document) and its own record types (what one row of a Dataset is), so the core stays neutral. A pack is four files (manifest, terms, Entity Types, link kinds) plus an optional record-types file loaded from the classpath of `inspecto-entity-store`, so **every edition ships the packs, Basic included**; a pack with any broken file is refused whole and reported. Two packs ship: **Telecom** speaks of subscribers, calls and call detail records, A-party and B-party, MSISDN, IMSI, IMEI, handset and cell, voice, SMS and call-forwarding links, SIM box, IRSF and Wangiri; **Mobile Money** of wallets and agents, transfers, P2P, cash-in, cash-out and merchant payment, time to cash-out, cash-out concentration and float. The demonstration data sets are separate Space Templates (Section 3.5), not part of a pack. A Space chooses its pack as its **Domain Profile** (`GET`/`PUT /settings/link-analysis/domain-profile`); the single-page application reads the vocabulary from the server, has no profile picker of its own, and shows the pack in force read-only in the Studio's Graph model settings. A vocabulary guard and `IndustryPackNeutralityTest` keep pack words out of the core text. The canvas legend and the pattern picker draw the pack's terms for link kinds and Entity Types, and a glossary popover explains typology terms (display text only; saved views keep the raw kinds). Tests: `IndustryPacksTest`, `ControlApiDomainProfileTest`.

---
