# Inspecto LA (Link Analysis) — Graph Intelligence Whitepaper
> **Sovereign Graph Operations: In-Process Entity Investigation, High-Scale Parquet Indexing, and Forensic Integrity Without Cluster or Cloud Sprawl**
> *Audience: Chief Risk Officers (CRO), Chief Information Security Officers (CISO), Heads of Financial Crime & AML, Telecom Fraud Operations, Lead Enterprise Architects, Forensic Investigators*
> *Publication: October 2026 · Document version **1.1** (supersedes 1.0 of 2026-10-14)*

> **Release basis & evidentiary grounding.** This whitepaper specifies **Inspecto LA (Link Analysis)** — the product name; inside the Inspecto console the capability, its routes, configuration keys and modules keep the name **Link Analysis** — as delivered in the **Basic, Standard and Advanced** editions of Inspecto (a fourth edition, **Preview**, carries everything with a demo sign-in and is never sold). It reflects the committed codebase: the five `la/` Maven modules (`inspecto-geo-link`, `inspecto-la-api`, `inspecto-la-core`, `inspecto-la-storage`, `inspecto-la-store-pg`), the two platform modules they stand on (`platform/inspecto-graph`, `platform/inspecto-entity-store`), the shared Angular library `@inspecto/link-analysis`, and the standalone `la-app` shell (an Angular project, `inspecto-ui/projects/la-app`, not a Maven module). Every performance figure names the benchmark that produced it, the host and the date; where a number comes from a single run it says so. Section 7 is a Claims Register that classifies each capability as **PROVEN**, **MEASURED**, **DOCUMENTED** or **NOT YET**. What changed since version 1.0 is listed at the end of this document.

---

# 1 · Executive summary — graph intelligence without the cluster tax

Modern financial institutions, sovereign intelligence services, and telecommunications operators face a profound operational dilemma. Fraud, money laundering, cyber intrusion, and organized financial crime manifest as complex, multi-hop relationship networks. Yet, the enterprise graph technologies sold to detect and investigate these networks have introduced crippling architectural liabilities: **cluster infrastructure cost**, **data-egress risk under sovereignty and banking-secrecy laws**, and **brittle, un-audited evidence chains**.

**Inspecto LA** resolves this contradiction by eliminating the external graph database entirely. Delivered as an in-process, sovereign capability within the unified Inspecto platform, it performs graph projection, interactive link investigation, and network analytics directly over standard Parquet lakehouse tables.

```
       TYPICAL MULTI-VENDOR GRAPH STACK                              INSPECTO LA (LINK ANALYSIS)
 ┌─────────────────────────────────────────────────────────┐    ┌──────────────────────────────────────────────┐
 │ Data Lake / Warehouse (S3 / Snowflake / Hadoop)         │    │           ONE IN-PROCESS ARTIFACT            │
 │        │ Custom ETL / Spark Glue Script                 │    │  Vectorised Parquet Lakehouse (DuckDB)       │
 │ Ingest & Messaging (Kafka / NiFi / Airbyte)             │    │                       │                      │
 │        │ Custom Connector / Network Transfer            │    │  Entity Projection read in place             │
 │ Dedicated Graph Database (Neo4j / TigerGraph / Neptune) │    │                       │                      │
 │        │ Unencrypted Network Export / CSV Dump          │ VS │  Bidirectional Parquet Link Index (tested    │
 │ Desktop Forensic Tools (i2 Analyst's Notebook / Gephi)  │    │  to 10^8 edges)                              │
 │        │ Disconnected Screenshots & Spreadsheets        │    │                       │                      │
 │ Case Management / Ticketing (ServiceNow / JIRA)         │    │  29 In-Process Graph Algorithms              │
 └─────────────────────────────────────────────────────────┘    │                       │                      │
   • Licences and operations spread across several vendors      │  SHA-256 Hash-Sealed Investigation Op Log    │
   • Data replicated into a separate graph store                │                       │                      │
   • Exports leaving the controlled perimeter                   │  Tamper-Evident Dossier Bundle               │
   • No cryptographic chain of custody for evidence             │                       │                      │
   • Graph caches that drift from the lakehouse                 │  Four-Eyes Oversight of Sensitive Expansion  │
                                                                └──────────────────────────────────────────────┘
                                                                  • No external graph database to run
                                                                  • Analysis reads no external service
                                                                  • Deterministic replay from sealed reads
                                                                  • One security model, one audit trail
```

### Three structural enterprise liabilities addressed

1. **The Egress Exclusion & Regulatory Sovereignty**:
   Enterprise fraud and intelligence units operate under stringent regulatory mandates (such as GDPR Article 9, banking secrecy laws, and sovereign defense data-residency directives). Cloud graph databases and SaaS analytics require data replication outside the sovereign perimeter. Inspecto LA runs on the customer's own host and reads no external service. The air-gap claim is deliberately scoped: the standard distribution **physically cannot call hosted AI endpoints**, because the hosted-provider SDK classes are absent from its classpath — a fact checked by an automated test (`EgressGuardTest`), recorded in `compliance/evidence/air-gap-posture.md`. That is a packaging guarantee, not a runtime network control: network isolation of the host remains the operator's deployment responsibility, and the optional third-party integration of Section 5.7 sends only to a registered `https` Connection under the Space's egress allow-list.
2. **The Cluster Tax & Latency Penalty**:
   Distributed graph databases pay for coordination — network hops and consensus — on every traversal. Inspecto's vectorised single-node engine reads Parquet in process and runs its 29 graph algorithms inside one host, with no cluster to coordinate. The price of that design is stated plainly: it is a single-host engine. The link index is measured to $10^8$ edges (one-key reader latency of about $21\text{ ms}$ at $10^8$ on the bench of Section 6.1); $5\times10^8$ and $10^9$ edges are not measured.
3. **The Evidentiary Fragility of Ad-Hoc Scripts**:
   When forensic analysts export graph snapshots to desktop software or run unversioned scripts, the chain of custody is broken. Upstream data changes can invalidate historical conclusions, and the provenance of the analysis can be challenged. Inspecto LA models every investigation as an **append-only, hash-sealed op log**. Replaying a step does not re-query the live Dataset, and the Dossier carries a SHA-256 manifest over every stored artefact so that a later change is detectable.

### Editions and packaging

Inspecto LA is sold in the same editions as the rest of Inspecto (names fixed by decision `LA-EDITIONS-1`, 2026-10-17: **Basic / Standard / Advanced**, replacing Personal / Professional / Enterprise across the whole product). The principle is that no edition gets weaker analysis or weaker evidence: editions differ by **who** works (one analyst, a team, an organisation), **how much data**, and **how much governance**. **Basic is the explorer**: it has no sign-in and no Investigations. Everything in Sections 2 and 5 that rests on an Investigation — the sealed op log, replay, the Dossier, Drafts, four-eyes approval, audited reveal, embed links, Data Trust, standing detection — is **Standard and above**.

| Capability | Basic | Standard | Advanced | Preview |
| :--- | :---: | :---: | :---: | :---: |
| Graph studio, projections from any Dataset, saved views, paths, neighbours, patterns, Value Measures | yes | yes | yes | yes |
| Geo map, Entity Lists, identity resolution, Entity profile pages | yes | yes | yes | yes |
| Browser graph algorithms (28 of the 29) | yes | yes | yes | yes |
| Industry packs (Telecom, Mobile Money) and the Domain Profile | yes | yes | yes | yes |
| Masking | display only (no sign-in, so anyone may reveal) | enforced | enforced | enforced |
| Sign-in | none | OIDC | OIDC | demo sign-in |
| Investigations: sealed op log, replay, compare, Dossier and bundle, Drafts, templates, external references | — | yes | yes | yes |
| Four-eyes approval, audited reveal, members and roles, embed links, Case linking | — | yes | yes | yes |
| Data Trust strip, Files, coverage, delivery Alerts | — | yes | yes | yes |
| Server Graph Run (29 algorithms), link index and its scheduled build | — | yes | yes | yes |
| Alert Rules on Investigations, standing detection (`la.detect`) | — | yes | yes | yes |
| Third-party integration (inbound, deep link, hand-off, webhooks) | — | yes | yes | yes |
| Investigations on PostgreSQL (`la-store-pg`, opt-in) | — | — | yes | yes |
| Policy engine (ABAC, can only narrow access) | — | — | yes | yes |
| Retention, legal hold, residency, scale-out (placed in Advanced by decision) | — | — | decided, not yet in the build | decided, not yet in the build |

*Basis: `docs/EDITIONS.md` (Inspecto LA feature placement and *Basic — the Link Analysis explorer*), `offerings/basic.toon`, `offerings/standard.toon`, `offerings/advanced.toon`, `offerings/preview.toon`, and the as-built record of the editions plan (`docs/archived-documents/plans-archive/inspecto-la-editions-plan.md`, section 7). All seven phases of that plan shipped on 2026-10-17 (commit `f1b64ce03`).*

How the explorer is separated from the Investigation surface: the five Link Analysis modules ship in every edition, and one edition feature, `investigations`, gates the Investigation routes, Graph Run, the index and the integration routes. On Basic those routes answer `503 CAPABILITY_UNAVAILABLE` ("available in Standard and above") and the single-page application hides the matching controls, so nothing offered can only fail. The separation is pinned over real HTTP by `ControlApiBasicEditionLinkAnalysisTest`. The Postgres store is built into Advanced and Preview and stays inert until `-Dinvestigations.backend=db` is set with a `jdbc:postgresql:` URL; every edition, Advanced included, defaults to the filesystem store. Two items are decided but not in the build: retention, legal hold and residency controls (the `docs/EDITIONS.md` row says "not built yet"), and machine-to-machine client-credentials trust for third-party callers, which awaits a security review (Section 5.7). Named adapters for specific case managers or fraud products do not exist: only a generic connector is built, and a named adapter is built only on a customer's request.

---

# 2 · The forensic investigation lifecycle & evidentiary chain of custody

*Standard and above.* Inspecto LA approaches network exploration not as ephemeral canvas drawing, but as a formal forensic discipline. Every node expanded, path traversed, and filter applied in an Investigation is recorded in an append-only, hash-sealed log. (Basic offers the same exploration without an Investigation: nothing is recorded, sealed or replayable there.)

### 2.1 Entity Projection read in place

Rather than requiring an asynchronous ETL pipeline to duplicate lakehouse data into an external property graph, Inspecto introduces **Entity Projection**: a mapping that translates relational rows from existing Datasets directly into graph entities and links at query time.

$$\text{Projection}: R(c_1, c_2, \dots, c_k) \xrightarrow{\text{declarative mapping}} G(V, E)$$

- **From and To Entities**: Mapped from arbitrary column pairs (e.g., `payer_account_id` $\to$ `payee_account_id`, `caller_msisdn` $\to$ `callee_msisdn`).
- **Link Attributes**: Preserved from transaction metadata (e.g., `amount`, `timestamp`, `channel`, `call_duration`); a link kind column, a time column and an events-count column may be bound.
- **Multi-Mapping Projections**: A single graph view can bind multiple heterogeneous Datasets (e.g., bank wire transfers, mobile money transactions and shared IP login records into one graph).
- **Foreign-Key Schema Inference**: `GET /inv/schema/relationships` proposes candidate links from Dataset metadata (`<base>_id` column naming) and `POST /inv/schema/overlap-profile` measures cardinality and Jaccard overlap across candidate key columns, so an analyst can start a multi-entity projection without hand-writing SQL.
- **Assisted mapping**: The Assistant's `projection_author` tool (part of the Intelligence feature, which needs the agent module; Standard and above) proposes a mapping from column names using an ordered list of endpoint pairs (`caller/callee`, `payer/payee`, `origin/destination`, `from/to`, and others), most specific first. It returns a **draft** that the analyst reviews before applying; when no defensible pair of endpoint columns exists it returns none rather than inventing one.

### 2.2 Append-only Investigation Op Log & deterministic replay

At the core of an Investigation is the **Investigation Op Log**. An Investigation does not store arbitrary visual layouts; it stores a sequence of ordered, atomic operations ($O_1, O_2, \dots, O_n$).

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                      INVESTIGATION OP LOG (APPEND-ONLY LEDGER)                         │
 │                                                                                        │
 │  Step 0: Seed Entity          Step 1: 1-Hop Expand         Step 2: Time Window         │
 │  ┌──────────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐    │
 │  │ op: "seed"           │     │ op: "expand"         │     │ op: "window"         │    │
 │  │ entity: "ACC-9921"   │ ──► │ direction: "both"    │ ──► │ window: {from, to}   │    │
 │  │ state hash: 3a8f...  │     │ sealed rows + hash   │     │ state hash: 7d11...  │    │
 │  └──────────────────────┘     └──────────────────────┘     └──────────────────────┘    │
 └──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                            │
                                            ▼ Pure Functional Fold
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │                       CURRENT WORKING SET (DETERMINISTIC STATE)                        │
 │  Entities: 112 | Links: 348 | Measures: 4 | Anomaly Decile: Top 10% | Hash: a7e4...9c │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

*The figures in the Working Set box are illustrative.* The current analytical state — the **Working Set** ($W_n$) — is computed as a pure functional fold over the operation log:

$$W_n = \text{fold}(W_0, [O_1, O_2, \dots, O_n])$$

The evaluator handles fifteen operation labels (`seed`, `seedBy`, `expand`, `exclude`, `excludeBy`, `hide`, `keep`, `threshold`, `annotate`, `window`, `resolve`, `connect`, and the markers `snapshot`, `compare`, `temporal`); an unknown operation is refused, never guessed. Each log entry records the SHA-256 of the Working Set it produced.

**The Upstream Mutation-Resilience Invariant**:
A persistent failure mode in legacy graph tools occurs when underlying databases change: an analyst re-opens a case six months later, only to find the graph topology altered because old records were purged or updated. In Inspecto LA, **the op log seals the exact retrieved rows into the log entry at execution time** (an `expand` stores the rows it read; `compare` and `temporal` store their findings with a fingerprint). Replaying an Investigation never re-queries the live Dataset; history is not rewritten by a later Dataset change. If an analyst wishes to check whether upstream data has evolved, replay accepts an explicit `reread` option that re-runs every recorded query and reports drift per step, without altering past steps. This is pinned with a real file append by `ControlApiReplayDriftOnFileAppendTest`.

### 2.3 Cryptographic sealing & the Dossier bundle

An Investigation can be exported as a **Dossier**: `GET /inv/investigations/{id}/dossier` answers in four renderings — `json`, `steps` (numbered plain-language steps), `method` (a method statement) and `html` (one self-contained printable page: inline CSS, no scripts, no external fetches). `…/dossier/bundle` packages the masked Dossier, the masked external references, a custody block and a SHA-256 seal as a single JSON document (`inspecto-dossier-bundle/1`).

```
                           DOSSIER MANIFEST (ILLUSTRATIVE SAMPLE)
 ┌────────────────────────────────────────────────────────────────────────────────┐
 │ manifest:                                                                      │
 │   investigation: "INV-2026-0881"                                               │
 │   at: 7                                  (log position the Dossier was cut at) │
 │   root: "8f7e2a...c014"          (SHA-256 over the canonical manifest body)    │
 │   artefacts:                                                                   │
 │     path                       sha256 (raw stored bytes)                       │
 │     ─────────────────────────  ─────────────────────────                       │
 │     header.json                4a8c19b...f82e                                  │
 │     log.jsonl#1 ... log.jsonl#7                                                │
 │     sets/1.json ... sets/7.json                                                │
 │     snapshots/<id>.json        (each included snapshot)                        │
 │   content: sha256 of the canonical entities, links, excluded, scores           │
 └────────────────────────────────────────────────────────────────────────────────┘
```

- **Hash chain of custody**: The manifest hashes the raw stored bytes of every artefact the Dossier draws on — the Investigation header, each log line, each sealed Working Set file and each included snapshot — plus the canonical content it exposes (entities, links, exclusions, score vectors). Its `root` is the SHA-256 of the canonical manifest body (every field except `root` itself); it is a single digest over the manifest, not a Merkle tree.
- **Verification is a route, not an offline utility**: `POST …/dossier/verify` rebuilds the manifest from the store as it is now and compares it entry by entry, naming every artefact that changed; `POST …/dossier/bundle/verify` checks the bundle's seal, the embedded manifest root, that the bundle's references are still the first N in the store, and then runs the same manifest comparison. A failure is a result, not an error. What a holder can check **offline** is narrower: the bundle's own seal and the manifest's self-consistent root; whether the store still agrees needs the verify route. `ControlApiDossierTest` covers an untouched store verifying and a tampered step being reported.
- **Masking-invariant roots**: The manifest is computed over the **raw** store, before masking. The root is therefore identical whether the Dossier is read by a cleared reviewer or an anonymised one, and a masked bundle still verifies; the bundle's seal covers what actually shipped.
- **A Dossier is not sealed against the store's owner**: an attacker who can rewrite the store files **and** recompute every hash defeats an on-box check. External anchoring of the Dossier root is the countermeasure (Section 7).

### 2.4 Collaborative branching: Analyst Drafts & atomic promotion

Investigations often involve multiple analysts formulating competing hypotheses. Inspecto prevents concurrent investigators from corrupting the master case record through **Analyst Drafts**.

```
                               DRAFT BRANCHING & PROMOTION
                               
 Master Log    [Step 0: Seed] ──► [Step 1: 1-Hop] ──► [Step 2: Filter] ──────► [Step 4: Promoted]
                                        │                                             ▲
                                        │ Fork Private Draft                          │ Rebase onto head,
                                        ▼                                             │ append under lock
 Draft Log                      [Draft Step 0: Walk] ──► [Draft Step 1: Detect] ──────┘
                                                                │
                                                                ▼ (carries a sensitive expand)
                                                        ┌───────────────────────┐
                                                        │ FOUR-EYES OVERSIGHT   │
                                                        │ Status: PENDING       │
                                                        │ Decider: lead/reviewer│
                                                        └───────────────────────┘
```

1. **Private Forking**: A member forks a private Draft from the Investigation. The analyst can test traversals and algorithm runs without exposing preliminary findings to other members. One live Draft per member; by default 50 open Drafts per Space, hibernated after 60 minutes of inactivity and expired after 30 days (`drafts.*` limits).
2. **Rebase Mechanics**: If the main Investigation advances while the analyst works, promotion rebases the Draft's operations onto the current head and classifies conflicts as `no-op`, `changed`, `superseded` or `blocked`.
3. **Four-Eyes Gating on Promotion**: If a Draft carries an expansion that exceeds the Space's four-eyes thresholds, promotion becomes a pending request in the approver inbox instead of merging; a different person must decide it (Section 5.3).
4. **Atomic Append**: Promotion is all-or-nothing under the main-log lock — a per-directory monitor plus an operating-system file lock on the filesystem store (safe across two JVMs), or one transaction on the PostgreSQL store.

---

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
- **Mobile money (`la-mobile-money`)**: 420 wallets, 40 agents, 40 merchants and about 5,700 transactions over 14 days with seven planted stories, pinned by `LaMobileMoneyGoldenTest` and the `mm_links` Job's `MmLinksJobGoldenTest`; smoke runner `tools/la-mm-smoke.mjs`; runbook `docs/ops/la-showcase-mm-runbook.md`.
- **Finance and audit (`la-audit`)**: procure-to-pay and the journal, seven planted stories, whose headline is the Dossier as an audit workpaper (seal, verify, change one byte, watch verification fail); `tools/la-audit-smoke.mjs`; `docs/ops/la-showcase-audit-runbook.md`.
- **Insurance claims (`la-claims`)**: synthetic claims for a Special Investigations Unit, a graph built from shared attributes (one phone, one address, one garage) rather than money moving; seven planted stories, nine algorithm scenes, Dossier external references (pointers that are never fetched and never trusted) and a 12-minute script; `LaClaimsGoldenTest`; `tools/la-claims-smoke.mjs`; `docs/ops/la-showcase-claims-runbook.md`.
- **Telecom**: the `telco-fraud` and `la-showcase` templates behind the Telecom industry pack (Section 3.6).

### 3.6 Industry packs and the Domain Profile

An **Industry pack** maps a vertical's vocabulary onto the neutral concepts; it relabels and never adds a concept, so the core stays neutral. A pack is four files (manifest, terms, Entity Types, link kinds) loaded from the classpath of `inspecto-entity-store`, so **every edition ships the packs, Basic included**; a pack with any broken file is refused whole and reported. Two packs ship: **Telecom** (MSISDN, IMSI, IMEI, cell and similar terms) and **Mobile Money** (wallet, agent, cash-in and cash-out). A Space chooses its pack as its **Domain Profile** (`GET`/`PUT /settings/link-analysis/domain-profile`); the single-page application reads the vocabulary from the server and no longer ships industry profiles of its own. A vocabulary guard and `IndustryPackNeutralityTest` keep pack words out of the core text. Pack relabels of link kinds, pattern packs and typology terms are published but not yet drawn on the canvas legend or the pattern picker (`LA-EDITIONS-RESIDUALS-1`). Tests: `IndustryPacksTest`, `ControlApiDomainProfileTest`.

---

# 4 · Systems architecture & data plane engineering

Inspecto LA runs inside a single host process. The backend is partitioned into five `la/` Maven modules and two platform modules, with dependency arrows that a guard enforces.

### 4.1 Modular component architecture & seam enforcement

Link Analysis is **an optional, ServiceLoader-discovered extension of the one host process**: there is no Link Analysis main class and no separate server. The standalone `la-app` is only a second Angular shell served by that same host.

```
                               MODULE DEPENDENCY TOPOLOGY
                               (an arrow points to what a module depends on)

   ┌─────────────────────────────────────┐   ┌──────────────────────────────────────┐
   │ Inspecto console (/studio/...)      │   │ la-app (inspecto-ui/projects/la-app) │
   │ shared library @inspecto/link-analysis  — two Angular shells, one library      │
   └──────────────────┬──────────────────┴───┴──────────────────┬───────────────────┘
                      │            HTTP REST / OpenAPI v1       │
                      ▼                                         ▼
   ┌───────────────────────────────────────────────────────────────────────────────┐
   │ host process: ControlApi — discovers RouteModules through ServiceLoader       │
   └──────────────────────────────────────┬────────────────────────────────────────┘
                                          │
   ┌──────────────────────────┐   ┌───────┴──────────────────┐
   │ la/inspecto-geo-link     │──►│ la/inspecto-la-api       │   routes, gates, masking,
   │ bridge: engine ⇄ ports   │   │ (86 routes)              │   Drafts orchestration
   │ (7 routes)               │   └───┬──────────────────┬───┘
   └──────┬────────┬──────────┘       │                  │
          │        │                  ▼                  ▼
          │        │      ┌────────────────────┐ ┌──────────────────────┐
          │        └─────►│ la/inspecto-la-core│◄┤ la/inspecto-la-storage│  link index
          │               │ domain, ports,     │ │ (DuckDB, Parquet)     │
          │               │ op evaluator,      │ └──────────────────────┘
          │               │ filesystem store   │◄── la/inspecto-la-store-pg
          │               └─────┬──────────┬───┘     (Postgres store provider)
          ▼                     ▼          ▼
   entity-list, entity-store   ┌─────────────────────┐  ┌──────────────────────────┐
                               │ platform/            │  │ platform/                │
                               │ inspecto-graph       │  │ inspecto-entity-store    │
                               │ (algorithms, JDK     │  │ (Entity Types, Lists,    │
                               │  only)               │  │  identity fact log,      │
                               └─────────────────────┘  │  mask tokens, limits)    │
                                                         └──────────────────────────┘
```

The surface is **93**<!--count:la-routes--> routes: **86**<!--count:la-api-routes--> in `la-api` and **7**<!--count:geo-link-routes--> in `geo-link`, counted from each module's `module.toon` (`provides.routes`) and kept in step with the code by `LaApiRoutesManifestParityTest`. Every route is in `docs/api/openapi-v1.json`. `la-storage` depends on `la-core`, the SQL module and DuckDB; `geo-link` depends on `la-api`, `la-core`, `entity-list` and `entity-store`; `la-core` depends on `inspecto-graph` and `entity-store` among others. Ports point inward: `la-core` defines the engine, dataset, case and store ports, and storage, the bridge and the Postgres module implement them.

- **Clean Inversion of Control**: `inspecto-graph` and `la-storage` contain **no HTTP or UI dependencies**. Algorithms and storage primitives can be tested, benchmarked and embedded headless. `inspecto-graph` reaches no other Inspecto module at all.
- **Seam Enforcement**: Module dependency arrows are checked twice — by `tools/check-module-deps.mjs` (a CI guard that derives the module graph from the poms and compares it with a policy table) and by a Maven Enforcer rule in each of the governed modules. A reverse import (for example `la-core` reaching `la-storage` or `la-api`) fails the build. A second guard, `tools/check-engine-names-no-la.mjs`, keeps the engine from naming any `la-*` class; the host reaches Link Analysis only through ServiceLoader interfaces it owns. Seam enforcement describes structure, not behaviour. In D-AN2 (2026-10-17) the graph library moved to the platform module `inspecto-graph` so that other capabilities can use it without Link Analysis, and the windowed motif matcher (`TemporalMotifMatcher`) moved there as well; Link Analysis keeps its pattern compiler. Link Analysis's SQL builders quote identifiers through the shared `SqlIdent` in `inspecto-util`.
- **Host Discovery via ServiceLoader**: Link Analysis registers with the core process through ServiceLoader. If a Link Analysis module is absent from the classpath, its routes answer `503 CAPABILITY_UNAVAILABLE` from the core's absent-module stub and the core boots cleanly with no Link Analysis footprint; Basic withholds the Investigation surface by the edition feature described in Section 1.

### 4.2 Dual-storage engine architecture

Depending on the deployment, Investigations (their op logs and Working Sets) are persisted by one of two backends behind the `InvestigationStore` port:

```
               STORAGE ENGINE SELECTION & TOPOLOGY

                               InvestigationStore
                                (Abstract Port)
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
        ┌───────────────────────┐             ┌───────────────────────┐
        │  FsInvestigationStore │             │  PgInvestigationStore │
        │ (default, every       │             │ (Advanced and Preview,│
        │  edition)             │             │  opt-in)              │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
   <write root>/audit/snapshots/              one schema per Space; tables
   investigations/<id>/                       la_space, la_investigation,
   ├── header, log.jsonl (append)             la_log, la_set, la_member,
   ├── sets/<step>.json (sealed)              la_reference, la_alert_binding,
   ├── members, references, templates         la_pending, la_template,
   ├── pending, caselink, binding             la_draft
   ├── mask.key                               sealed bytes stored as text,
   └── drafts/<draft>/...                     immutability trigger
```

1. **Filesystem store (the default in every edition, Advanced included)**:
   - Persists under the Space write root: a header, an append-only `log.jsonl`, one sealed Working Set file per step (`sets/<step>.json`, written create-new), members, references, templates, pending requests, the Case link, the Alert Rule binding, the Investigation's mask key and Drafts. There is no manifest file in the store: the Dossier route builds its manifest on request.
   - Concurrency: a per-directory monitor plus an operating-system file lock (`.store.lock`) make it safe across two JVMs; appends check an expected version.
   - Requires no database, which suits ruggedized laptops, air-gapped units and isolated field servers.
2. **PostgreSQL store (Advanced and Preview, opt-in)**:
   - Selected by `-Dinvestigations.backend=db` with `investigations.db.url` (or `inspecto.db.url`) naming a `jdbc:postgresql:` database; the password is read through the secret resolver. The jar alone keeps the filesystem store.
   - One schema per Space; the tables are listed above. Sealed bytes are stored as text and an immutability trigger refuses their change; writers take row locks (`FOR NO KEY UPDATE`) in one short transaction, and a Draft promotion is one transaction with nothing to recover.
   - It lets a second pod share a Space. Contract tests: `PgInvestigationStoreContractTest` and, for two JVMs racing on one store, `PgInvestigationStoreTwoJvmRaceTest` (module `la/inspecto-la-store-pg`).
   - **Embed links are not supported on the PostgreSQL store yet**: the embed routes answer 503 until that backend implements the embed-grant store contract.

### 4.3 Dual query & execution engines

Graph traversal workloads fall into two categories: ad-hoc exploration over moderate Datasets, and localized lookups over very large ones. Inspecto provides two read paths:

```
               QUERY EXECUTION PATH ROUTING DECISION
                                 
                         Client Request (/inv/...)
                                     │
                                     ▼
                  Index enabled, matching and not too stale,
                  depth ≤ 2, frontier ≤ 20 keys, rung indexable?
                                     │
                    ┌────────────────┴────────────────┐
             YES    ▼                                 ▼    NO
        ┌───────────────────────┐             ┌───────────────────────┐
        │  Indexed read         │             │  Flat recursive CTE   │
        │  (inspecto-la-storage)│             │  (sandboxed DuckDB)   │
        └───────────┬───────────┘             └───────────┬───────────┘
                    │                                     │
         Bucket read, one key per               The fallback names a closed
         statement, one hop per query           Reason; the answer never mixes
                                                index and flat rows
```

A Graph Run is routed by its **input type** (`RoutingGraphEngine`), never by size: a Working Set input runs in the in-memory engine; an index input runs in the SQL-backed engine, which serves neighbourhood, ego network and seeds-only degree with hard caps. A traversal deeper than 2 hops, or wider than the frontier cap, falls back to the flat path with a stated Reason.

#### Engine A: Sandboxed DuckDB recursive CTEs (flat path)
For un-indexed Datasets and for deep, iterative walks, Inspecto compiles traversals into sandboxed, recursive Common Table Expressions executed in embedded DuckDB. A simplified, illustrative form (the shipped statements add direction, link-kind, time-window, fan-out and budget fences):

```sql
WITH RECURSIVE traversal(entity, depth, path) AS (
    SELECT source_entity, 0, [source_entity]
    FROM input_relation
    WHERE source_entity = 'ACC-9921'
  UNION ALL
    SELECT r.target_entity, t.depth + 1, list_append(t.path, r.target_entity)
    FROM input_relation r
    JOIN traversal t ON r.source_entity = t.entity
    WHERE t.depth < 2 AND NOT list_contains(t.path, r.target_entity)
)
SELECT * FROM traversal;
```

DuckDB executes such queries vectorised across CPU cores. Measured through the real route (gate `G-R4`, `InvTraversalBench`): a 5-hop recursive path search over a heavy-tailed $10^6$-edge Dataset answered at a warm p95 of $209 / 188 / 171\text{ ms}$ from a median-degree, a 99th-percentile-degree and a top-hub start, all under the $350\text{ ms}$ gate, with no index.

#### Engine B: Versioned Parquet Link Index (high-scale path)
Scanning flat lakehouse files makes interactive latency unattractive as edge counts grow into the tens of millions. `la-storage` builds a **Versioned Parquet Link Index** (Standard and above):

```
                       VERSIONED PARQUET INDEX LAYOUT
                       
 <write root>/la-index/<datasetId>/<mappingHash>/
 ├── CURRENT                    ◄── Atomic 1-line pointer to the live version (e.g. "v000002")
 ├── pins.json                  ◄── Versions kept alive for in-flight Draft reads
 └── v000002/
     ├── manifest.json          ◄── Schema, input-file fingerprints, DuckDB version, bucket sizing,
     │                              link statistics (kindEdges, typeNodes, degree, timeSpan)
     ├── out/                   ◄── Edges keyed by source, sorted by (bucket, key, time)
     │   ├── bucket=0/data.parquet
     │   └── bucket=B/data.parquet        (+ up to 8 delta files per bucket)
     ├── in/                    ◄── The same edges keyed by target
     │   └── bucket=B/data.parquet
     └── nodes/                 ◄── Distinct nodes with degree counts
         └── bucket=B/data.parquet
```

- **Bidirectional Partitioning**: Every link is stored twice — keyed by source in `out/` and by target in `in/` — so an expansion in either direction reads one direction. The data roughly doubles.
- **MD5 Hash Bucketing**: Entities are assigned to buckets by a fixed, golden-tested hash:
  $$\text{Bucket}(v) = \text{md5\_number\_lower}(v) \bmod N, \qquad N = \text{clamp}\left(2^{\lceil \log_2(\lceil \text{edges} / E_b \rceil) \rceil},\; N_{\min},\; N_{\max}\right)$$
  where $E_b$ is `index.edges_per_bucket` (default $4{,}000{,}000$), $N_{\min}$ is `index.min_buckets` (default 16) and $N_{\max}$ is `index.max_buckets` (default 1,024) — all install-configurable; a changed sizing forces a full rebuild. The reader computes the bucket in Java and writes it as a literal, because DuckDB cannot derive the partition from a key equality; with that, a single-key lookup reads **one bucket** (plus up to 8 delta files, `MAX_DELTAS`).
- **Incremental Appends & Compaction**: `IndexPlan.classify` decides the build mode. When a new day lands and the manifest's input files are an unchanged subset of today's, an `append` version indexes only the delta. When the delta count reaches its cap, `compact` merges main and deltas. Compaction runs **on demand** (`POST /inv/index/builds`) or from the `la.index.build` Job, which runs only the mode the plan advises (a `full` build only when the Job allows it); it is not a background process. Measured append and full-build times are in Section 6.1.
- **Index statistics**: The manifest records link statistics (`kindEdges`, `typeNodes`, `degree`, `timeSpan`), served by `GET /inv/index` and read first by the Entity profile pages (Section 4.7).

### 4.4 User interface dual-delivery

Inspecto LA accommodates different personas through two Angular shells that share one library:

```
                       FRONTEND DELIVERY ARCHITECTURES
                       
      ┌─────────────────────────────────────────────────────────────┐
      │                      INSPECTO PLATFORM                      │
      └──────────────┬──────────────────────────────┬───────────────┘
                     │                              │
                     ▼                              ▼
      ┌─────────────────────────────┐┌──────────────────────────────┐
      │   Embedded Builder Lens     ││     Standalone `la-app`      │
      │   (/studio/link-analysis)   ││    (Dedicated Forensic UI)   │
      ├─────────────────────────────┤├──────────────────────────────┤
      │ • Part of main web console  ││ • Independent Angular bundle │
      │ • Full access to Pipelines, ││ • Left rail, no header chrome│
      │   Datasets, BI, Catalog     ││ • Maximum screen for canvas  │
      │ • Persona: Data Engineers,  ││ • Persona: Dedicated Fraud   │
      │   Analytics Authors         ││   & Intelligence Analysts    │
      └─────────────────────────────┘└──────────────────────────────┘
```

1. **Embedded Studio Lens (`/studio/link-analysis`)**: Integrated into the Inspecto console for data engineers and authors who build and test graph views alongside dashboards and Pipelines.
2. **Standalone `la-app` shell**: A dedicated bundle (`package.ps1 -Ui la-app`; it swaps the bundled application, not the edition). It has a left navigation rail (Link Analysis, Geo, Entities, Entity Lists, Alerts, Settings), no header chrome, and OIDC sign-in in Standard and above. Its **Settings** page is a master-detail split by audience: *My settings*, *This Space* (Link Analysis settings, analysis limits and an **Access** section with the console's roles editor), and *This server* (host resources); a section the user may not change opens read-only and says so, and the server remains the judge. The footer status bar carries the node and link counts, the caps in force and the installed modules. Working Set tiles never sum an identifier column.

### 4.5 Spatio-temporal & cross-domain convergence

Fraud and national security threats rarely occur in a topological vacuum; they exist across physical geography and time.
- **Cross-Route Geo Brushing**: The single-page application's brushing service keeps the graph and the **Geo Map Analysis** page in step. A selection on one canvas is published and the other canvas highlights the matching elements, joined only by the entity key (never by a display label); selecting entities on the graph highlights their points on the map, and an area drawn on the map selects the matching entities. This is front-end behaviour, not a backend module.
- **Continuous Timeline Slicing**: Every link retains its event timestamp. A timeline cut-off slider and a playback strip step through time over a chosen time column and highlight the links whose date falls in the current slice; they write nothing to the graph.

### 4.6 Data Trust Architecture & provenance strip (LA-DATA-TRUST-1)

*Standard and above.* A persistent hazard in intelligence operations is analyzing a graph without knowing whether the underlying data is fresh, complete, or corrupted by pipeline delays. Inspecto LA resolves this with an integrated **Data Trust Strip** rendered above the open Investigation:

```
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ DATA TRUST STRIP: mule_ring_legs_dataset (Consignment #1048)            (ILLUSTRATIVE) │
 │ [● Fresh: 24m ago] [✔ 3/3 Files Landed] [⚡ Index: Synced v4] [⚠ 0 Quarantined Rows]   │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Graph Lineage & Landed File Attribution**: `GET /inv/datasets/{id}/files` lists one row per landed File with its event day, rows, error rows, ingest time, outcome (success, partial, quarantined, failed) and index state. Analysts can see which landed files support the current view.
- **Continuous Staleness & Lag Detection**: The strip compares event time, lakehouse ingest commits (`pipeline.commit`) and the index version. A corrected-day warning (`GET …/data-changes`) appears when a delivery manifest corrects a day inside the Investigation's window after the analyst's last look; only a step that reads data counts as a look.
- **Data Trust Cache**: The `data-as-of` read is cached against the input-file stamp (the relation plus the path, size and modification time of every input file), so the cache can never show fresher or staler data than the disk. Measured on 70 Files and 322 thousand rows on a live bundle over loopback: a cache miss took $895\text{--}993\text{ ms}$, a hit $132\text{--}138\text{ ms}$ (a single recorded set of live measurements, not a benchmark gate).
- **Integrated Trust Alerts**: Freshness and index-lag Alert Rules, a volume band against the median of the prior days, and one Alert per refused or failed index build and per problem File (the Alert text carries no entity key) surface upstream delivery problems. The Dossier footer states the Dataset's state at export time and is explicitly outside the custody hash and the seal. Stated limits: completeness is judged on days and File sequence, not against an expected-Collector roster.

### 4.7 Entity profile pages (built 2026-10-15)

*Explorer feature: every edition, Basic included.* An Entity profile answers *what is this identifier, where does it appear, and how does it behave* before an analyst commits to an Investigation. Three pages are served by the shared library and mounted in `la-app` (navigation entry **Entities**) and in the console: **Entities** (one card per Entity Type), the **Entity Type Profile** and the **Entity Profile**; the graph's node dialog links to the Entity page.

- **Routes** (`EntityProfileRoutes`): `GET /inv/entity-types` (types, bindings and the cached headline; never runs a query), `GET /inv/entity-types/{id}/profile?sections=`, `GET /inv/entities/{type}/{key}/profile`, and `POST /inv/entities/{type}/resolve`.
- **Entity Type Profile sections**: Identity, Where it appears, Lineage, Volume and quality, Value shapes, Time, Links, Relationships, and *What is not here*. **Entity Profile** shows: seen, rows per day (timeline), where, links, related identifiers, lists and groups, and freshness.
- **Where the Links statistics come from**: the link index first (the manifest's `kindEdges`, `typeNodes`, `degree`, `timeSpan`), else a budgeted Dataset query, else the section answers `assessed: false`.
- **Masking**: A raw identifier never travels in a URL. For a type masked under the Space's mode, the `GET` is alias-only (`masked:<16 hex>`) and a raw key is refused `422` before anything is read, without repeating it; a raw value goes in the **body** of the `POST …/resolve`, and the page navigates by the key it returns. Type pages are aggregates only: a dominant value is reported as `kind: "dominant"` with `value: null`, and only the built-in sentinels (blank, `-99`, `0`, `NULL`, `null`, `unknown`, `n/a`) are shown by value.
- **Gating**: no dedicated capability. Every Dataset read goes through the caller's view gate, masking applies, and the profile routes are rate-limited as expensive. Audited as `LINK_ENTITY_TYPES_LISTED`, `LINK_ENTITY_TYPE_PROFILED`, `LINK_ENTITY_PROFILED` and `LINK_ENTITY_KEY_RESOLVED` (never a key).
- **Honesty rules**: an approximate count says *approx.*; a section the server could not read says *Not assessed* with its reason and is listed again under *What is not here*; a failed read answers `assessed: false, retryable: true` and is **never cached**.
- **Cache**: keyed by the input-file fingerprint of every bound Dataset. Measured cold at $2.2\text{--}5.4\text{ s}$ and cached at about $200\text{ ms}$ on a Space of 29,650 rows.
- **Scale hardening (2026-10-16, after a 132-million-row day, Section 6.4)**: profile SQL runs under its own memory budget (`entity_profile.memory_limit_mb`, default 4,096 MB) instead of the 1 GB sandbox default; above `entity_profile.shape_sample_rows` the dominant value is read from a row reservoir; a relationship read that runs out of memory or times out is retried over a 1-in-64 Entity hash sample and flagged `sampled`, `sampleEvery` and `approximate`; and a Dataset column may carry a `party`, so relationships pair only columns of the same party (or where either has none) — a served IMSI pairs with the served number, not the other party's.
- **Limits** are listed in Section 6.3. **Not built**: P4 — a scheduled `la.entity.profile` Job, a profile Dataset, drift Alert Rules and a 30-day history. **Open residuals** (`LA-ENTITY-PROFILE-RESIDUALS-1`): dominance ignores values that fail the normaliser; a 99.95 % share renders as 100 %; *Links* reads *Not assessed* for a type that is no link end; a cold profile still reads the Dataset several times.
- Tests: `ControlApiEntityProfileTest`, `ControlApiEntityProfileScaleTest` (sandbox-scale figures only).

---

# 5 · Enterprise governance, security & access control

Link analysis often exposes highly sensitive personal data, communication records (CDRs), and financial histories. Inspecto enforces a defense-in-depth perimeter. **Sign-in, enforced masking, four-eyes approval, audited reveal, embed links and the Investigation-scoped controls of this section are Standard and above**; on Basic there is no sign-in, masking is display-only (anyone may reveal) and the Investigation surface answers 503 (Section 1).

### 5.1 The seven-gate request pipeline

Every HTTP request reaching Link Analysis routes (`/inv/*`, `/geo/*`) is decided by the following gates, in this order, before a handler produces data:

```
               THE SEVEN-GATE REQUEST PIPELINE
               
  Client HTTP Request
         │
         ▼
 ┌──────────────────────┐
 │ 1. AUTHENTICATE      │ ──► 401 (no valid session or bearer token), audited
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 2. RATE LIMIT        │ ──► 429 on the expensive routes only (burst 30, one more every 2 s, per user)
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 3. ABAC (host PEP)   │ ──► 403 when the host's access policy refuses the action
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 4. MODULE GATE       │ ──► 404 MODULE_DISABLED (Link Analysis switched off for this Space);
 └──────────┬───────────┘     503 when the module is absent or, on Basic, the edition lacks it
         │
         ▼
 ┌──────────────────────┐
 │ 5. CAPABILITY        │ ──► 403 when the caller lacks the route's capability
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 6. INVESTIGATION     │ ──► Investigation routes only: write root 503 → id 422 → header 404 →
 │    OPEN GATE         │     membership 404 → Dataset visibility 404 → policy 404 → role 403
 └──────────┬───────────┘
         │
         ▼
 ┌──────────────────────┐
 │ 7. HANDLER           │ ──► masks on the way out, writes the audit event
 └──────────────────────┘
```

- **Rate limit.** One per-user bucket covers the routes the code classes as *expensive* — about twenty (`LinkAnalysisRateClasses`): whole-Dataset projections, traversals and patterns, Graph Run and index starts, Entity profiles and resolve, the inbound and hand-off calls (the anonymous embed read has its own per-link and per-address buckets). Default burst 30, refilling one request every 2 seconds, set by the install limits `rate.link_analysis_burst` and `rate.link_analysis_per_s`. Investigation routes are exempt. `LinkAnalysisRateClassCoverageTest` fails when a route is in neither class.
- **Module gate.** Every Link Analysis route module declares the `geoLink` feature and is gated per Space (`ControlApiGeoLinkModuleGateTest`). On Basic the Investigation surface additionally answers `503 CAPABILITY_UNAVAILABLE` ("available in Standard and above") at dispatch, before the Space's own switch.
- **Capability.** Reads of the exploration surface need only a signed-in subject. Writes and costly actions need named capabilities: `canManageIncidents` (create and step Investigations, Drafts, snapshots, Case links, inbound and hand-off), `canRunLinkGraphAnalysis` (start a Graph Run), `canBuildLinkIndex` (start an index build), `canAuthorAlertRules` (bind a measure to an Alert Rule), `canShareInvestigation` (mint and revoke embed links), `canApproveLinkExpansions` (decide a held expansion) and `canRevealLinkEntities` (unmask). Seeded defaults: `canManageIncidents`, `canRunLinkGraphAnalysis` and `canBuildLinkIndex` go to `operations`, `support`, `power` and `admin`; `canAuthorAlertRules` to `power` and the builder roles; `canShareInvestigation` to `admin` and `power`; **`canApproveLinkExpansions` and `canRevealLinkEntities` only to `admin`**; and `super` holds everything. With no Subject (Basic) the capability gate is a no-op.
- **Investigation open gate.** Membership applies only to Investigation routes: a non-member gets **404 Not Found, never 403**, so a user cannot confirm that an Investigation into a sensitive target exists; a member who lacks the needed role gets 403 only after Dataset visibility and (in Advanced) the policy engine have spoken. A member of the linked Case may read. Pinned by `ControlApiInvestigationMembersTest`.

### 5.2 Entity masking

To comply with data-privacy frameworks while allowing analysts to discover structural patterns, Inspecto masks entity identifiers on the way **out** of every Link Analysis answer; engine results and caches stay raw.

- **Masking Modes** (a Space setting, `maskingMode`):
  - `typed` (default, fails closed): only columns whose classification is claimed by a masked Entity Type (e.g., MSISDN, IMSI, IBAN) are tokenised; a masked type's seed, endpoint and unknown types are masked.
  - `all`: every node identifier and label is tokenised.
  - `none`: raw identifiers are shown. It is a Space setting, not a personal privilege.
- **Keyed Aliases**: An identifier becomes `masked:` plus the first 16 hexadecimal characters of an HMAC-SHA256 under a 32-byte key. There are two keys: **one per Investigation** (kept with it) and **one per Space** (shared by exploration reads, Entity Lists, Screening, BI and risk scores; stored outside every exported, imported and backed-up tree). An alias is deterministic under its key, so clustering, counting and path-finding still work; the same identifier therefore carries a different alias in an Investigation than in a Space-level read, and the Working Set carries a map between them. Basic (no sign-in) renders aliases but cannot enforce them.
- **Anti-Oracle Defense**: A raw identifier submitted where an alias is required on a masked Space is refused or answered as a nonexistent node, so the API cannot be used to probe whether a given number or account is in the graph; an alias the server never issued (or has forgotten after a restart) is refused `422`, never guessed. *Open an Entity* resolves a typed value first by posting it in a request body, and an Entity List match accepts a masked node's alias.

#### 5.2.1 Masking beyond Link Analysis (hardened 2026-10-15)

The same classification-driven masking now reaches other surfaces that can carry the identifiers an Investigation names — Incident and Alert objects, events, notifications and the Assistant. For a caller **without** `canRevealLinkEntities` (a holder, and a request with no Subject, see raw values), the following mask classified keys and are covered by real-HTTP or unit tests:

| Surface | What is masked | Evidence |
| :--- | :--- | :--- |
| Object reads: `GET /objects` (and its v1 page), `GET /objects/{id}`, `GET /alerts`; the graph node titles | the object's classified `key.*` values, `entityKey`, and the raw value wherever it stands in title, description and correlation id | `ControlApiObjectReadMaskingTest` |
| Mutation echoes (acknowledge, resolve, transition, assign, patch, category, postmortem and the Case open, merge and split bodies) | the same, through one helper (`ObjectReadMasking.echo`) | `ControlApiObjectReadMaskingTest` |
| `GET /objects/by-entity` | fails closed: a non-revealer cannot look up a classified key by its raw number (Link Analysis keeps its own alias route, `POST /inv/case-history`) | `ControlApiObjectReadMaskingTest` |
| `OBJECT_OPENED` events on `/events`, `/events/search`, `/events/{id}` and `/events/export` (JSON and CSV) | masked at read time; fails closed to `<type> opened [id]`; new events carry no title | `ControlApiEventsReadMaskingTest`, `AuditReadMaskingOpenedEventTest` |
| The `q` and `correlationId` probes on objects and events | matched against the **masked** row, so a raw number matches nothing | `ControlApiObjectReadMaskingTest` |
| Object notes, comments, attachment text | the object's own classified keys, inline | `ControlApiObjectReadMaskingTest` |
| Notification content (in-app, mail, webhook) | **always** masked, for every recipient: an operator receives a token, never the number | `NotificationContentMaskingTest` |
| Assistant tool results | direct dispatch masked unless the caller can reveal; results sent to a model are masked whenever the provider is not local, even for a revealer; the anomaly and expectation scans are restricted to an allow-list of operational metric stores | `EgressMaskingTest` (module-level; the agent is an add-on) |
| `POST /cases/from-entities` | refuses a mask-token key for everyone; a non-revealer naming an entity of a classified Dataset gets an identical value-free `422` before any lookup | `ControlApiCaseFromEntitiesTest` |

**This is not a claim that every read surface is masked.** What is open or undecided, as recorded in the backlog row `OBJECT-READ-MASKING-RESIDUALS-1`:
- The Regulatory Report object view still renders raw objects (an operator decision is pending: mask per template field, or gate the route).
- Link Analysis alias generation and the Space evidence masker give **different pseudonyms for one key**, so one identifier can show two tokens.
- `POST /db/query` and `GET /db/table` on operational (`ops:*`) groups return raw operational rows to any holder of `canAuthorWorkbench`.
- `/agent/triage-runs*`, `/agent/feedback` and `/agent/approvals*` carry no read-capability gate.
- Not covered: the root-cause-apply echo, attachment-create echoes, a direct `notify()` call by a granted Run, and a Space with no write root.
- The set of classified columns ignores the Space's `maskingMode`, and the Assistant core's serialisation of tool results is inferred, not read.

### 5.3 Four-Eyes oversight & separation of duties

Large entity expansions can reveal vast swathes of non-target data or exhaust the runtime. When a Space sets its **four-eyes thresholds** (`four_eyes_budget_above` rows read, `four_eyes_fan_out_above` neighbours) an expansion above them is **held for approval** instead of running. The thresholds have **no shipped default**: four-eyes is something a Space turns on; the shipped showcase Space Templates set a fan-out threshold between 30 and 2,000 links. An expansion restricted to at most 50 chosen neighbours is exempt.

```
                         FOUR-EYES EXPANSION PIPELINE
                         
 Analyst Action: Expand beyond a threshold
                       │
                       ▼
            Pending Request Created (never in the log)
            (the requester cannot decide it)
                       │
                       ▼
            Approver inbox (GET /inv/pending)
            (a lead or reviewer of the Investigation who holds canApproveLinkExpansions)
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          APPROVE              DENY
             │                   │
             ▼                   ▼
    Expansion executes     Rejection and reason recorded;
    and is logged          nothing is read
```

- **Enforced Separation**: a pending expansion is never written to the sealed log, so four-eyes holds by construction. The requester cannot approve it — attempting it is `403`, even with the capability — and cannot deny it either.
- **Atomic Concurrency Resolution**: a compare-and-swap on the pending record makes exactly one decision land; a second decision is `409`.
- **Audit**: `LINK_EXPANSION_REQUESTED`, `LINK_EXPANSION_APPROVED`, `LINK_EXPANSION_DENIED`. Pinned by `ControlApiInvestigationOversightTest.aSensitiveExpandWaitsForADifferentPersonsApproval`.

### 5.4 Audited Reveal

When an investigator must identify an entity behind an alias, **Reveal** unmasks it under audit:
- **Dedicated Capability**: `canRevealLinkEntities`, held by default only by `admin` (and `super`). It is deliberately not part of `canManageIncidents`, which every Investigation owner holds; there is no "legal officer" role in the seeded set.
- **Granular Scope**: a reveal names the aliases it wants (at most 100 per call by default, `input.reveal_ids`) and answers only those, from the Investigation's own alias book; an alias it does not know is listed as unknown, never guessed. It never unmasks the rest of the canvas.
- **Immutable Audit Event**: every reveal writes `LINK_ENTITY_REVEALED` with the actor and the **aliases** revealed — never the raw ids, so the trail does not re-leak them. Pinned by `ControlApiInvestigationOversightTest.revealIsPerEntityGatedOnItsOwnCapabilityAndAudited`.

### 5.5 Ephemeral secure embed sharing & the standalone Embed Viewer (LA-EMBED-VIEW-1)

Senior leadership, prosecuting agencies, or external regulators frequently need read-only access to specific findings without a platform account. Inspecto LA delivers this via **Ephemeral Embed Links** and a dedicated **Embed Viewer** shell. It is **off by default** (the kill switch `-Dla.embed.enabled`: when off, minting answers 503 and every public request the same 404), needs the **filesystem** store (the PostgreSQL store answers 503 until it implements the embed-grant contract), and is Standard and above.

```
                         EMBED VIEWER SECURITY ARCHITECTURE
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │ EXTERNAL BROWSER / PORTAL IFRAME                                                       │
 │   URL: https://<host>/embed/inv/{token}            (the viewer page)                   │
 │   CSP: frame-ancestors <the token's own Space's allow-list>  (none when the list is    │
 │        empty)                                                                          │
 │   Headers: X-Content-Type-Options: nosniff | Cache-Control: no-store | no cookie       │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ EMBED VIEWER APPLICATION (Read-Only Angular Shell)                                     │
 │   • Renders the graph with pan, zoom and search                                        │
 │   • Stripped view: no Reveal, pending expansions, Drafts, Case, Dataset names or SQL   │
 │   • Masking follows the Space; a link may be stricter than its Space, never looser     │
 ├────────────────────────────────────────────────────────────────────────────────────────┤
 │ SERVER VERIFICATION (GET /public/investigations/{token})                               │
 │   • 256-bit token: only its SHA-256 is stored and compared, in constant time           │
 │   • The token is never logged; audit rows record presentedHash only                    │
 │   • Audit: LINK_EMBED_VIEWED on load; LINK_EMBED_DENIED on any refusal                 │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Who may mint**: a **lead** of the Investigation who also holds `canShareInvestigation` (seeded to `admin` and `power`). At most **20 live links** per Investigation. A mint may narrow the scope (Working Set only, with notes, or full) and the masking mode.
- **Expiry**: default **72 hours** (`embed.default_ttl_hours`); the Space's maximum defaults to **30 days** (`embed.max_ttl_hours`, 720 h) and a Space administrator may raise it, within the validation ceiling of 8,760 h (365 days); a request above the Space's maximum is refused `422`, never clamped. A sweep every ten minutes drops the token hash of expired links.
- **Per-Space Frame Ancestors Allow-List**: framing is defended by `Content-Security-Policy: frame-ancestors` built from the Space's `embed.frame_ancestors` (canonical `https://host[:port]`, no wildcard). The list applied is the **token's own Space's** only; a revoked, forged or expired token is framed by nobody.
- **Font & Asset Stripping**: the viewer page is the application shell with every external stylesheet and font reference removed (no preconnect, no font host) and a strict content policy, so the viewer makes no third-party request.
- **Token Security & Log Redaction**: tokens are `<grantId>.<256-bit secret>`, shown once at mint and never listed, logged or audited. The audit key is `presentedHash` (the audit sink redacts any attribute whose key contains "token").
- **One answer to every bad token**: disabled, malformed, unknown, tampered, expired, revoked, deleted and the auto-revoke cases below are all the same `404`; the reason goes to the audit row only.
- **Automatic revocation**: a link stops working (a) when it expires, (b) when its minter is no longer a lead of the Investigation, (c) when the Investigation's linked Case is closed, or (d) when the Space **loosens** its masking mode after the link was minted (a stricter mode never revokes). There is no Investigation delete or close route today, so "closed" means the linked Case closed.
- Tests: `ControlApiInvestigationEmbedTest`, `ControlApiEmbedMultiSpaceTest` and a live browser pass (Section 7).

### 5.6 Standing detection & operational closed loops

Findings need not stay on a canvas. The scheduled **`la.detect` Job** connects an Investigation's measures to the platform's alerting:
- **Standing Detection Sweeps**: A measure over an Investigation's Working Set (for example accounts showing a high pass-through ratio) is bound to an Alert Rule. A standing-detection record names the principal `sweep:<investigation id>`, its owner, Dataset and masking basis; the sweep **re-decides its authority before every read** (the owner must still lead the Investigation and view the Dataset; masking must not have tightened; the policy engine must not deny) and holds no capability of its own. The job needs the alerts module.
- **From an Alert to the rest of the platform**: A breach fires an **Alert**. Under the platform's severity promotion, an Alert Rule of `CRITICAL` severity also opens a de-duplicated **Incident** linked `ESCALATED_FROM` its Alert. Grouping Incidents into **Cases** is done by the platform's Case Rules or by an analyst; Link Analysis does not open Cases by itself, and no automatic Incident-to-Case chain is claimed here.
- **Privacy Preservation in Alerts**: the Alert's evidence carries only aggregates — the Measure, its threshold line, `breachCount`, and the worst entry's `worstOf` / `worstValue`. It carries no entity id, name or alias; the entities are named only inside the gated Investigation. Notification content is in any case always masked (Section 5.2.1).

### 5.7 Third-party integration (Standard and above)

Target products were unknown, so **one generic connector** is built, configured per Space; a named adapter would be built only on a customer's request.
- **Inbound**: `POST /inv/inbound/investigations` lets a fraud product or case manager hand over its own record and the entities it concerns; Inspecto LA opens a seeded Investigation and answers its id and a link. It is idempotent on the caller's system and record id, composes the existing write routes (so every gate and audit event applies), and records the caller's record as an **external reference** that is never fetched and grants nothing. `POST /inv/deep-links/resolve` serves the "Open in Inspecto LA" deep link (`/open`); a raw value of a masked type is refused and never repeated.
- **Outbound**: `GET`/`PUT /inv/integration` (administrator-only; the endpoint is a registered `https` Connection, never a literal secret in configuration) and `POST /inv/investigations/{id}/handoff`, which sends the Dossier bundle and findings to the configured case manager, retries under one idempotency key, and links the returned case id as an external reference. **Signed webhooks** (HMAC-SHA256 over a timestamp and the body) announce `investigation.created`, `draft.promoted`, `alert.fired` and `approval.needed`; they carry no entity id. Every outbound call passes the Space's egress allow-list.
- **Not enabled**: machine-to-machine client-credentials trust (an inbound token scoped to one integration role). A formal security review — SSRF, token scope, replay, audit on both sides, secret handling, masking — is owed before it is built. Tests: `ControlApiInvestigationInboundTest`, `ControlApiLaIntegrationTest`.

---

# 6 · Performance benchmarks, sizing & operational limits

Inspecto LA's figures come from **three different benchmarks, run on different corpora and configurations**. They are kept apart below and must not be read as one curve. All use synthetic corpora with a skewed (heavy-tailed) degree distribution unless stated; every one is a recorded run on the named hardware, not a service-level guarantee.

### 6.1 Benchmark measurements, one bench per table

**Bench A — the index design spike** (`InvIndexSpikeBench`, a gated harness; Intel Core i7-9850H, 6 cores / 12 threads, 32 GB RAM, DuckDB 1.5.2, warm cache, mean degree 5, no memory cap; single run). At $10^8$ edges:

| Measure | Value |
| :--- | :--- |
| Full index build | $368\text{ s}$ (6.1 minutes), peak working set $17.9\text{ GB}$ |
| One hop, flat unsorted file | $1{,}031\text{ ms}$ |
| One hop, sealed index path (the spike's hop, not a production request) | $43\text{ ms}$ — about $24\times$ faster |
| Java-driven 5-level walk | $3.0\text{ s}$ and $4.3\text{ s}$ p50 against a $1.5\text{ s}$ criterion — **failed** |

The failed walk is why the index serves only depth $\le 2$ and $\le 20$ keys per level (Section 6.2).

**Bench B — `IndexScaleBench`** (the production `IndexBuilder`, `IndexReader` and `IndexedTraversal`; a Windows laptop with 12 hardware threads, warm; skewed corpus with nodes = edges / 5; DuckDB build memory capped at $8\text{ GB}$ by the bench property `inspecto.bench.buildMemory` — **a bench setting, not the production default** (production gives a build 80 % of RAM by default, `index.build_memory_pct`); 60 random keys after one warm-up; single run, 2026-10-05):

| Edges | Full build | Index size | One key p50 / p95 | One key p50, 8 delta files | Depth-2 walk p50 / p95 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| $10^6$ | $4\text{ s}$ | $0.02\text{ GB}$ | $26.6 / 43.5\text{ ms}$ | $35.3\text{ ms}$ | $229 / 538\text{ ms}$ |
| $10^7$ | $22\text{ s}$ | $0.23\text{ GB}$ | $20.2 / 27.3\text{ ms}$ | $29.9\text{ ms}$ | $32 / 489\text{ ms}$ |
| $10^8$ | $447\text{ s}$ (32 buckets) | $2.23\text{ GB}$ | $20.9 / 31.0\text{ ms}$ | $37.8\text{ ms}$ | $21.8 / 32.0\text{ ms}$ |

A one-key lookup is flat in the number of edges at reader level (about $20\text{ ms}$ from $10^6$ to $10^8$); each delta file adds about $0.2\text{ ms}$. A walk costs its frontier: a full 20-key frontier costs about $0.4\text{ s}$ (about $20\text{ ms}$ per key, sequential; $408\text{ ms}$ at $10^8$ with no delta files). The $22\text{ ms}$ depth-2 figure at $10^8$ is over typical random keys, most of which have degree near 1, not over a 20-key frontier. **Beyond $10^8$**: at $5\times10^8$ edges the build ran out of memory at the $8\text{ GB}$ cap after 91 minutes; the $10^9$ attempt of 2026-10-13 was stopped when free disk reached its floor. So the scaling run is multi-hour on this class of host and **incomplete**: no $5\times10^8$ or $10^9$ latency figure exists, and a $10^9$ run needs a host with roughly $250\text{ GB}$ free disk and at least $16\text{ GB}$ for the build (`docs/ops/la-index-scale-run-plan.md`).

**Bench C — the daily chain** (`LaDailyIngestScaleBench` then `DailyIndexScaleBench`, measured 2026-10-09; a 12-thread Windows laptop with 31.7 GB RAM that was 19–31 % busy with other work, so wall times are noisy; a synthetic 10-column day whose generator caps distinct entities at about 1.8 million nodes (real records carry far more, which raises sort and dictionary cost); index builds with no memory limit and all threads; one run each):

| Step | $10^7$ rows | $10^8$ rows |
| :--- | :--- | :--- |
| Day ingest (CSV to partitioned Parquet) | $35\text{--}40\text{ s}$ | $489\text{ s}$ (ten parts of $10^7$) |
| Full index build (first build) | $31\text{ s}$, peak $3.3\text{ GB}$ | $852\text{ s}$ (14.2 min), peak $3.6\text{ GB}$ |
| New day lands (+10 %), **append** onto the live index | $4.5\text{ s}$, peak $0.54\text{ GB}$ | $30.6\text{ s}$, peak $3.3\text{ GB}$ |
| Full rebuild of base plus the day | $31\text{ s}$ | $929\text{ s}$ (15.5 min) |

An append costs only the new day, so the daily Job is about $7\times$ ($10^7$) and about $30\times$ ($10^8$) cheaper than a full rebuild. Peak memory is flat with row count (DuckDB spills to disk) rather than proportional. One consequence: the `la.index.build` Job's default wait for an in-flight build is bounded (about two minutes), so behind a full build at $10^8$ it gives up and the next trigger indexes the day; the Job parameter `wait_seconds` lifts the bound.

**Through the real route** (`InvTraversalBench` for the $10^6$ gate; the scale table of the Link Analysis feature concept for the rest): every request pays a fresh sandboxed connection of about $100\text{--}200\text{ ms}$, so at $10^6$ edges the index is **not** faster (one hop $209\text{--}466\text{ ms}$ against $293\text{--}373\text{ ms}$ flat); from $10^7$ it wins about $2\text{--}5\times$ for a hop and $1.5\text{--}3\times$ at depth 2. At $10^8$ the route comparison was not re-run. That is why the index is consulted only when it is enabled, matching and fresh enough.

### 6.2 Algorithmic boundaries & traversal routing conditions

To prevent denial-of-service conditions during interactive exploration, Inspecto applies stated, configurable routing conditions:

1. **Index scope (depth $\le 2$, frontier $\le 20$ keys per level)**: `index.traversal_depth` and `index.frontier_cap`. Single-key reads are the cheap case; the cost of a walk is linear in its frontier, and a hub key costs more (a hub key $36\text{--}56\text{ ms}$ in Bench B). The frontier cap bounds the number of keys, not the cost of a hub.
2. **Deeper or wider requests**: a traversal deeper than 2 hops or wider than the frontier cap is **not** served by the index; it falls back to the flat recursive CTE over the Dataset with a closed, stated Reason in the answer (the traversal fences `traversal.*` bound depth, edge yield, paths and time). Whole-graph algorithms run in memory over the Working Set or through a Graph Run, routed by input type, not by size.

### 6.3 Layered configurable limits system (LA-CONFIG-LIMITS-1)

Inspecto LA avoids hard-coded limits: the `LaLimit` catalogue (146 items on this tree) holds every limit with its key, layer, default and validated range. A value outside its range is refused `422` naming the key and the range; a stored value outside its range reads as the default. Limits fall in three layers, separating platform sizing, organisational policy and client rendering:

```
                    LAYERED CONFIGURATION ARCHITECTURE
                    
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ INSTALL: HOST RESOURCE CEILINGS (system administrator, la-limits.toon)  │
  │ • Graph Run pool and ceilings · index build threads, memory, buckets    │
  │ • Rate limits · traversal memory and threads · Entity profile memory    │
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │ Space values are clamped to these
                                       ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ SPACE: ANALYSIS AND POLICY LIMITS (Space administrator, link-analysis.  │
  │ toon; edited under Settings > Link Analysis)                            │
  │ • Four-eyes thresholds, hub threshold, expansion and traversal budgets  │
  │ • Investigation size caps · Draft, embed and index policy · profile     │
  └────────────────────────────────────┬────────────────────────────────────┘
                                       │ Items marked "browser" may be lowered
                                       ▼
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ BROWSER: RENDERING CAPS (the analyst, kept in the browser)              │
  │ • Canvas, algorithm and level-of-detail caps, clamped to the Space value│
  └─────────────────────────────────────────────────────────────────────────┘
```

A selection of the shipped items (defaults and ranges are those of the catalogue on this tree):

| Layer | Key | Default | Validated range | Enforced where, and on overflow |
| :--- | :--- | :--- | :--- | :--- |
| **Install** | `graph_run.ceiling_nodes` / `ceiling_edges` | 500,000 / 5,000,000 | 1 to $10^8$ / 1 to $10^9$ | clamps a Graph Run's Space budget; the response says `budgetClamped` |
| | `graph_run.ceiling_timeout_ms` | 300,000 | 1 to 86,400,000 | clamps the Space timeout |
| | `graph_run.threads` / `queue` | 2 / 16 | 1 to 64 / 1 to 1,000 | worker pool; a full queue is `REJECTED`, HTTP 503 `STORE_BUSY` |
| | `index.threads` / `queue` | 1 / 4 | 1 to 64 / 1 to 1,000 | one live build per Dataset and mapping (409); a full queue is 503 |
| | `index.build_memory_pct` | 80 | 1 to 100 | share of physical RAM given to a build |
| | `index.edges_per_bucket`, `min_buckets`, `max_buckets` | 4,000,000, 16, 1,024 | 1,000 to $10^9$; 1 to 1,024; 1 to 65,536 | bucket sizing; a change forces a full rebuild |
| | `rate.link_analysis_burst` / `per_s` | 30 / 0.5 | 1 to 3,000 / 0.01 to 1,000 | per-user bucket; 429 |
| | `traversal.memory_limit_mb`, `traversal.threads` | 1,024, 4 | 64 to 1,048,576; 1 to 64 | one recursive path search |
| | `entity_profile.memory_limit_mb` | 4,096 | 64 to 1,048,576 | memory of one Entity profile query |
| **Space** | `graph_run.max_nodes` / `max_edges` / `timeout_ms` | 50,000 / 500,000 / 30,000 | 1 to $10^7$ each (clamped to the install ceiling) | `POST /inv/graph/runs`; overrun ends `BUDGET_EXCEEDED` with no result |
| | `four_eyes_budget_above`, `four_eyes_fan_out_above` | none (off) | 1 to 100,000 | an expansion above is held for approval (Section 5.3) |
| | `hub_threshold` | 500 | 1 to 100,000 | a candidate with more distinct contacts is flagged high-connectivity and not expanded further |
| | `max_set_bytes` / `max_investigation_bytes` | 64 MiB / 4 GiB | 1 B to 1 GiB / 1 B to 1 TiB | one step's sealed Working Set / one Investigation's total; 413 `PAYLOAD_TOO_LARGE` |
| | `investigation.expand_budget_default` / `_max`, `max_frontier` | 4,000 / 40,000 rows, 2,000 | 1 to 200,000 / 2,000,000; 1 to 100,000 | one expand; clamped or refused |
| | `index.traversal_depth`, `frontier_cap`, `max_deltas`, `max_age_hours` | 2, 20, 8, 24 | 1 to 10; 1 to 2,000; 1 to 100; 1 to 8,760 | indexed reads; flat fallback with a Reason |
| | `drafts.max_open`, `hibernate_after_minutes`, `expire_after_days` | 50, 60, 30 | 1 to 1,000; 1 to 10,080; 1 to 3,650 | Draft admission and lifecycle |
| | `embed.default_ttl_hours` / `embed.max_ttl_hours` | 72 / 720 | 1 to 8,760 each | embed expiry; above the maximum is 422 |
| | `entity_profile.timeout_s` | 30 | 1 to 600 | one profile statement |
| | `entity_profile.max_datasets`, `top_n` | 20, 20 | 1 to 2,000 each | Datasets read per type profile; entries per list |
| | `entity_profile.shape_sample_rows` | 1,000,000 | 1 to $10^9$ | above it, value shapes read a sample |
| | `entity_profile.sentinel_share` | 20 % | 1 to 100 | a single value above this share is reported as dominant |
| | `entity_profile.timeline_days`, `relationship_threshold` | 400, 6 | 1 to 36,600; 2 to $10^6$ | timeline length; outlier count |
| **Browser** | `projection_node_cap` | 1,000 | 1 to 100,000 | entities admitted to one canvas projection |
| | `analysis_node_cap`, `suspicion_node_cap` | 2,500, 1,000 | 1 to 100,000 each | above it the browser refuses or hands over to the server |
| | `display.lod_label_cap`, `layered_max_nodes`, `force_atlas_max_nodes` | 300, 30, 800 | up to 30,000; 3,000; 80,000 | labels drawn; layouts offered |

### 6.4 Measured on one production-scale day (single run)

One day of an IMS call-and-message record feed was profiled and indexed end to end to see what the product does at production scale. **This is one run on one laptop, on private data that a reader cannot reproduce; it is reported as a measurement, not a benchmark gate.** Only aggregate volumes and timings are published.

**Host and data.** A Windows laptop, 12 logical cores, 31.7 GB RAM, one SSD. One day (2026-10-01): 100 Parquet files, $12.25\text{ GB}$, $132{,}165{,}497$ records, 83 columns, one schema.

| Activity | Volume | Where | Time |
| :--- | :--- | :--- | :--- |
| Schema and row count from Parquet metadata | 100 files | laptop | under $1\text{ s}$ |
| Value profile of 12 columns | 132 M rows | laptop | about $45\text{ s}$ (sum of 2–9 s queries) |
| Threshold calibration | 132 M rows | laptop, 10 threads, 12 GB | $72\text{ s}$ |
| Link build, dry run | 132 M rows → 39.0 M links | laptop, 10 threads, 12 GB | $335\text{ s}$ |
| **Link build Job** | 132 M rows → 39.4 M links | server, DuckDB cap 10 GB | $572\text{ s}$ (9.5 min) |
| **Link index, full build** | 39.4 M links → 12.35 M nodes, $838\text{ MB}$ | server, one build thread | $316\text{ s}$ (5.3 min) |
| Typology counts Job | 39.4 M links | server | $7\text{--}45\text{ s}$ |
| Alert evaluation | one row | server | under $1\text{ s}$ |
| Whole entity-and-link profile (offline) | 132 M rows + 39.4 M links | laptop, 10 threads | $166\text{ s}$ |
| Entity profile, warm (cached) | — | server | $0.4\text{ s}$ |
| Entity profile, cold, IMSI / IMEI (after the scale fixes of Section 4.7; the relationship to the number timed out at 300 s in that run) | 132 M rows | server, 4 GB, sampling | $669\text{ s}$ / $731\text{ s}$ |
| Entity profile, cold, IMSI, after same-party pairing | 132 M rows | server | $453\text{ s}$ for the whole section; relationships exact, not sampled |

*Before the scale fixes* the profile ran out of memory on two sections at the 1 GB sandbox default (after $31\text{ s}$ and $131\text{ s}$), and one number-level profile exceeded 25 minutes. With a 4 GB budget the two reads took $58\text{ s}$ and $149\text{ s}$; hash-sampled at 1 GB, $41\text{ s}$ and $123\text{ s}$.

**Estimates, not measurements.** The source note marks these with "~" and they are *estimates*: a nightly link build at the server's default DuckDB memory (about 3.2 GB there, not the 10 GB above) of **15 to 25 minutes**; a nightly chain (links, index, typology counts, Alerts) of **25 to 35 minutes**; DuckDB spill up to **about 4 GB** during the link build; a cold number-level profile of **20 to 30 minutes**.

**Caveats to carry with these figures.** (1) A single run on a single laptop. (2) The server configuration differs between rows — a 10 GB cap for the link Job, one build thread for the index, two threads for the profile — so the rows do not form one consistent configuration. (3) The data is private, so no reader can reproduce it. (4) Two outliers in the data (one handset and one number carrying an unusually large count of the other identifier) are unexplained. (5) The scale test in the repository (`ControlApiEntityProfileScaleTest`) pins behaviour at sandbox scale, not these timings. (6) In this feed a delivery attempt is recorded as a record, so burst-style counts are inflated: a rule's count there must be read as *failed delivery attempts*, not as distinct targets.

---

# 7 · Transparent Claims Register (Evidentiary disclosure)

Inspecto adheres to an uncompromising engineering standard: **never over-claim**. Every technical assertion in this whitepaper is classified below into its evidentiary status, citing the verification suite or benchmark.

```
                           EVIDENTIARY STATUS DEFINITIONS
 ┌────────────────────────────────────────────────────────────────────────────────────────┐
 │  PROVEN       A concrete automated unit test, integration suite, or gated benchmark    │
 │                harness exists.                                                         │
 │  MEASURED     A single recorded run on stated hardware (often on private data); not a  │
 │                repeatable gate.                                                        │
 │  DOCUMENTED   The design specification is committed; not yet certified by live tests.  │
 │  NOT YET      A known engineering boundary or future roadmap item. Stated openly.      │
 └────────────────────────────────────────────────────────────────────────────────────────┘
```

| Area | Feature or Architectural Claim | Status | Evidentiary Basis & Reference |
| :--- | :--- | :--- | :--- |
| **Scale** | One-key index lookups flat at about $21\text{ ms}$ p50 from $10^6$ to $10^8$ edges (reader level) | **PROVEN** | `IndexScaleBench` (Bench B; 8 GB bench cap, one run, 2026-10-05) |
| | $10^8$ one-hop $43\text{ ms}$ indexed against $1{,}031\text{ ms}$ flat ($24\times$) in the design spike | **PROVEN** | `InvIndexSpikeBench` (Bench A; i7-9850H, uncapped, one run) |
| | Full index build at $10^8$: $368\text{ s}$ (spike), $447\text{ s}$ (8 GB cap), $852\text{ s}$ (daily chain) | **PROVEN** | Three different benches and configurations: `InvIndexSpikeBench`, `IndexScaleBench`, `DailyIndexScaleBench` |
| | Daily append at $10^8$: $30.6\text{ s}$ against $929\text{ s}$ for a full rebuild | **PROVEN** | `DailyIndexScaleBench`; append correctness by `IndexAppendTest` and `ScheduledIndexBuildTest` |
| | 5-hop recursive path search on $10^6$ edges at warm p95 under $350\text{ ms}$ | **PROVEN** | `InvTraversalBench` (gate `G-R4`) |
| | Index faster than flat through the real route from $10^7$ edges ($2\text{--}5\times$ a hop); not faster at $10^6$ | **MEASURED** | `okf/frontend/features/link-analysis.md` index design record; not re-run at $10^8$ |
| | $5\times10^8$ and $10^9$ edge index builds | **NOT YET** | $5\times10^8$ ran out of memory at the 8 GB cap; the $10^9$ attempt stopped at the disk floor (2026-10-13); `docs/ops/la-index-scale-run-plan.md`, backlog `LA-INDEX-SCALE-MEASURE-1` |
| | One production-scale day (132 M records) profiled and indexed in the times of Section 6.4 | **MEASURED** | Single laptop run, private data, 2026-10-16 (`docs/superpower/la-ims-production-day-measurements.md`); estimates in that section are marked as such |
| **Editions** | Basic is the explorer (no sign-in, no Investigations); the Investigation surface answers 503 on Basic and works on Standard | **PROVEN** | `ControlApiBasicEditionLinkAnalysisTest` |
| | The Preview bundle carries every module | **PROVEN** | `tools/offering-classpath.test.mjs`; a Preview demo bundle booted and answered `/health` |
| | Industry packs (Telecom, Mobile Money) and the server-side Domain Profile in every edition | **PROVEN** | `IndustryPacksTest`, `ControlApiDomainProfileTest` |
| | Retention, legal hold, residency and scale-out | **NOT YET** | Placed in Advanced by decision; not built (`docs/EDITIONS.md`) |
| **Security** | Seven-gate order: Link Analysis routes gated per Space by the module gate; every route in the manifest, in the rate classes and in the API contract | **PROVEN** | `ControlApiGeoLinkModuleGateTest`, `LaApiRoutesManifestParityTest`, `LinkAnalysisRateClassCoverageTest` (the first pins the module gate only, not the whole order) |
| | Strict 404 for non-members of an Investigation | **PROVEN** | `ControlApiInvestigationMembersTest` |
| | Keyed masking (`typed`, `all`, `none`) | **PROVEN** | `ControlApiExplorationMaskingTest`, `ControlApiInvestigationOversightTest` |
| | Anti-oracle: a raw identifier refused where an alias is required | **PROVEN** | `ControlApiExplorationMaskingTest.everyExplorationReadShowsAliasesAndNeverARawIdWhenTheSpaceMasks` |
| | Four-eyes: requester cannot approve or deny; a denial reads nothing; self-approval is 403 | **PROVEN** | `ControlApiInvestigationOversightTest.aSensitiveExpandWaitsForADifferentPersonsApproval` |
| | Per-entity reveal gated on its own capability and audited | **PROVEN** | `ControlApiInvestigationOversightTest.revealIsPerEntityGatedOnItsOwnCapabilityAndAudited` |
| | Embed links: expiry, auto-revoke, frame allow-list, one answer to every bad token | **PROVEN** | `ControlApiInvestigationEmbedTest`, `ControlApiEmbedMultiSpaceTest`; backlog row `LA-EMBED-VIEW-1` closed 2026-10-14 (`2dad1638f`) |
| | Embed Viewer shell with token redaction, driven live | **PROVEN** | Live browser pass on an Advanced demo bundle with two Spaces (2026-10-14; backlog row above) |
| | Classified keys masked on object reads, mutation echoes, `OBJECT_OPENED` events, notifications, Assistant tool results and `POST /cases/from-entities` for a non-revealer (2026-10-15) | **PROVEN** | `ControlApiObjectReadMaskingTest`, `ControlApiEventsReadMaskingTest`, `NotificationContentMaskingTest`, `EgressMaskingTest`, `ControlApiCaseFromEntitiesTest` |
| | **All** read surfaces masked | **NOT YET** | Open: Regulatory Report object view; two pseudonyms for one key; `ops:*` database browsing; ungated `/agent/triage-runs*`, `/agent/feedback`, `/agent/approvals*`; uncovered echoes (Section 5.2.1; backlog `OBJECT-READ-MASKING-RESIDUALS-1`) |
| | Real OIDC sign-in of the standalone app | **PROVEN** | `LA-APP-REAL-SIGNIN-1`, closed 2026-10-13: a real WSO2 IS 7.3.0 with a scripted identity-provider page; `docs/ops/la-app-real-signin-runbook.md` (`4cae924e1`) |
| | Configurable limits: every limit a validated catalogue item, SPA fallbacks equal to the catalogue | **PROVEN** | `LaLimitCatalogueParityTest`, `LaLimitsTest`; backlog row `LA-CONFIG-LIMITS-1` (`7dd9938fb`) |
| | Third-party integration: inbound seeding (idempotent), deep link, outbound hand-off, signed webhooks | **PROVEN** | `ControlApiInvestigationInboundTest`, `ControlApiLaIntegrationTest` |
| | Machine-to-machine client-credentials trust for integrations | **NOT YET** | Awaits a formal security review (Section 5.7) |
| **Data Trust** | Data Trust strip, landed-File attribution, corrected-day warning | **PROVEN** | `LA-DATA-TRUST-1`; live drive in `docs/ops/la-data-trust-live-drive.md` |
| | Cached data-as-of: $895\text{--}993\text{ ms}$ miss, $132\text{--}138\text{ ms}$ hit | **MEASURED** | 70 Files / 322 thousand rows, live bundle over loopback, 2026-10-14 (`LA-DATA-TRUST-POLISH-1`) |
| **Forensics** | Deterministic Working Set fold from the op log | **PROVEN** | `ControlApiInvestigationsTest.replayIsDeterministicAndEquivalentToTheIncrementalSteps`, `InvestigationExpandedFoldTest` |
| | Sealed reads survive upstream data growth; `reread` reports drift | **PROVEN** | `ControlApiReplayDriftOnFileAppendTest` |
| | SHA-256 Dossier manifest; a tampered step is reported | **PROVEN** | `ControlApiDossierTest.anUntouchedStoreVerifiesAndATamperedStepIsReported` |
| | Resilience to a rewrite that also recomputes every hash (store owner with root access) | **DOCUMENTED** | On-box evidence has this stated limit; external anchoring of the Dossier root is the countermeasure |
| | Retention purge of closed Investigations | **NOT YET** | **Declined** (decision `D-U8`, 2026-09-24): the store is deliberately append-only; reopen on a regulator's or customer's retention demand (backlog standing refusals) |
| **Analytics** | 29 in-process graph algorithms; browser twins of 28 | **PROVEN** | `GraphAlgorithmsParityTest`, `GraphSuspicionParityTest`, `ControlApiGraphRunTest` (`propagatedRisk` has no TypeScript twin, by design) |
| | Explainable Suspicion Score with a factor breakdown | **PROVEN** | `GraphSuspicionParityTest`, `ControlApiGraphRunTest`; the user manual `docs/ui/link-analysis-user-manual.md` |
| | Propagated Risk with top-five attribution and a capped score | **PROVEN** | `ControlApiGraphRunTest.propagatedRiskAnswersWithScoresAndFactorsAndRefusesBadWeights422` |
| | Showcase figures: `TILL-06` 97 % of cash-out from 8 payers; Pass-Through lists 13 accounts of which two are the planted relays | **PROVEN** | `ControlApiLaShowcaseFiguresTest`, `LaShowcaseGoldenTest` (planted data; no detection rate implied) |
| | Entity profile pages: sections, honesty rules, masking, alias-only URLs | **PROVEN** | `ControlApiEntityProfileTest`; scale behaviour `ControlApiEntityProfileScaleTest` (sandbox scale) |
| | Published false-positive or precision rates on real data | **NOT YET** | Depend on customer thresholds; unmeasured |
| **Platform** | In-process execution with no external graph database | **PROVEN** | Architectural invariant; seam enforced by `tools/check-module-deps.mjs` and `tools/check-engine-names-no-la.mjs` |
| | 93 Link Analysis API routes (86 in `la-api`, 7 in `geo-link`) | **PROVEN** | `LaApiRoutesManifestParityTest`; marker-checked against each `module.toon` |
| | PostgreSQL Investigation store (Advanced, opt-in) | **PROVEN** | `PgInvestigationStoreContractTest`, `PgInvestigationStoreTwoJvmRaceTest`; `docs/ops/la-showcase-variants-runbook.md`. Embed links are not supported on this store |
| | Whole-product test baseline at the editions commit | **PROVEN** | Full reactor `-Pedition-advanced`, 56 modules, 10,566 tests, 0 failures; SPA 5,913 passed, 5 skipped (commit `f1b64ce03`) |
| | Coverage measured 2026-10-15 | **MEASURED** | Backend instruction 86.08 % / branch 73.59 % (48 modules); UI statements 81.51 % / branch 76.64 % (553 spec files) |
| | Multi-host high availability | **NOT YET** | A laptop-scale drill (two server processes, one Postgres) ran 2026-10-10; the two-host drill on plain Linux has not run (`docs/ops/ha-dr-drill-runbook.md`; backlog `ASSURE-OPERABILITY-1`) |
| | Native Apache Hive / Kerberos JDBC connector | **NOT YET** | Supported through landed Parquet files via file Collectors (decision `D-ING5`) |
| | Hosted-AI endpoints cannot be called by the standard distribution | **PROVEN** | `EgressGuardTest` (classpath absence); `compliance/evidence/air-gap-posture.md` — a packaging guarantee, not a network control |
| | Accessibility: automated axe-core gate and live passes on Link Analysis pages | **PROVEN** | `docs/ui/accessibility-audit.md`; `LA-A11Y-AUDIT-1` |
| | Full WCAG 2.2 AA conformance of Link Analysis | **NOT YET** | Two vendor data-grid findings and a not-audited list remain open (backlog `LA-A11Y-AUDIT-1`); a self-assessment, not a third-party certification |

---

# 8 · Conclusion & architectural recommendation

Inspecto LA redefines the economics and operational posture of graph intelligence for organisations that can run a capable single host. By collapsing the legacy stack — extractors, an external graph database, visualisation servers and unversioned desktop clients — into one in-process runtime, an organisation gains three advantages, each with its limits stated above:

1. **Sovereignty by construction**: sensitive records stay on the customer's own host, behind keyed masking and four-eyes controls (Standard and above), and the standard distribution cannot call hosted AI endpoints. Masking is strong where tested and honestly incomplete elsewhere (Section 5.2.1).
2. **Operational simplicity**: no dedicated graph cluster or bespoke glue scripts, with the stated price that it is a single-host engine measured to $10^8$ edges, and that PostgreSQL-backed multi-pod sharing is an Advanced option rather than the default.
3. **Evidentiary rigor**: Investigations are sealed, replayable records, and the Dossier is hash-verifiable by a route; it is not proof against an attacker who can rewrite the store and recompute every hash, which is why external anchoring is recommended.

For institutions seeking to modernize financial crime, intelligence and fraud operations without moving data outside their perimeter, Inspecto LA offers a graph platform whose claims are listed, sourced and bounded in Section 7.

---

### What changed in version 1.1 (October 2026)

- **Naming and editions**: the product is *Inspecto LA (Link Analysis)*; editions are Basic, Standard, Advanced and Preview (Section 1, *Editions and packaging*); Basic is the explorer, and the Investigation-based content is stated to be Standard and above.
- **Corrections** against the code: five `la/` modules (the standalone `la-app` is an Angular project); the route count (93, marker-checked); all 29 algorithms listed, with minimum cut returned by maximum flow rather than a separate algorithm; the Suspicion Score and Propagated Risk formulas as shipped; the filesystem store as the default in every edition and the real PostgreSQL schema; the real index layout and the on-demand compaction; the benchmark section rebuilt with one bench per table; the gate order, capabilities and rate limits; audit event and error names; embed rules; the Dossier manifest keys and verification routes; the pattern packs and their windows; the layered limits table rebuilt from the catalogue; and the Claims Register's test names, statuses and bases.
- **Removed** because they could not be sourced: dollar-cost figures, the "air-gap certified" wording, per-algorithm complexity classes, and several unsourced latency, speed-up and memory cells.
- **Added**: Entity profile pages (Section 4.7), third-party integration (Section 5.7), industry packs (Section 3.6), the further showcase verticals (Section 3.5), the masking hardening and its open items (Section 5.2.1), and the single-day production-scale measurement (Section 6.4).
