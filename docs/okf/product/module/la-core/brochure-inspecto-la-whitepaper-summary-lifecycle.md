---
type: Brochure
title: Inspecto LA whitepaper — Executive summary and the forensic lifecycle
description: Part 1 of 6 of Inspecto LA whitepaper: · Executive summary — graph intelligence without the cluster tax; · The forensic investigation lifecycle & evidentiary chain of custody
item: module.la-core
audience: internal
status: current
verified: 2026-11-19 last-edit
code: la/inspecto-la-core
---

# Inspecto LA whitepaper — Executive summary and the forensic lifecycle

Part 1 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · next: [Analytical depth, graph algorithms and explainable detection](brochure-inspecto-la-whitepaper-analytics.md).

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

### At a glance

Measured headline figures, each sourced in Section 6: one-key link-index lookups at about $20\text{ ms}$ from $10^6$ up to $10^8$ edges; five-hop path searches under $350\text{ ms}$ over $10^6$ edges without the index; and 29 graph algorithms running on the server under explicit node, edge and time budgets. Every limit quoted is a configurable default (Section 6.3). Three properties shape the design:

1. **Every Investigation is evidence.** Each step an analyst takes is sealed into a hash-chained log with the exact rows it read, so the whole Investigation replays byte for byte and exports as a verifiable Dossier (Section 2).
2. **Privacy is the default, not a setting.** Identifiers are masked on the way out, reveals and sensitive expansions need a named capability or a second person, and every read is audited (Section 5).
3. **It runs where the data lives.** Analysis reads the platform's own Datasets, or a versioned link index built from them daily, so there is no copy to keep in sync and no separate graph database to operate (Section 4).

### Why generic graph tools fall short

Telecom and mobile-money fraud is relational: a SIM box or a mule ring is invisible in any single record and obvious in the links between thousands of them. Generic graph tools fail on four counts: they hold thousands of nodes, while a telecom feed runs to about $10^9$ records a day (the input assumption of the call-record data-preparation plan, Section 7.1); they produce pictures, not reproducible evidence; they copy personal data out of the controlled estate; and their findings do not become Alerts or Cases. Sections 2 (evidence), 4 and 6 (scale), 5 (privacy) and 4.8 and 5.6 (operations) answer one count each.

### Three structural enterprise liabilities addressed

1. **The Egress Exclusion & Regulatory Sovereignty**:
   Enterprise fraud and intelligence units operate under stringent regulatory mandates (such as GDPR Article 9, banking secrecy laws, and sovereign defense data-residency directives). Cloud graph databases and SaaS analytics require data replication outside the sovereign perimeter. Inspecto LA runs on the customer's own host and reads no external service. The air-gap claim is deliberately scoped: the standard distribution **physically cannot call hosted AI endpoints**, because the hosted-provider SDK classes are absent from its classpath — a fact checked by an automated test (`EgressGuardTest`), recorded in `compliance/evidence/air-gap-posture.md`. That is a packaging guarantee, not a runtime network control: network isolation of the host remains the operator's deployment responsibility, and the optional third-party integration of Section 5.7 sends only to a registered `https` Connection under the Space's egress allow-list.
2. **The Cluster Tax & Latency Penalty**:
   Distributed graph databases pay for coordination — network hops and consensus — on every traversal. Inspecto's vectorised single-node engine reads Parquet in process and runs its 29 graph algorithms inside one host, with no cluster to coordinate. The price of that design is stated plainly: it is a single-host engine. The link index is measured to $10^8$ edges (one-key reader latency of about $21\text{ ms}$ at $10^8$ on the bench of Section 6.1). Above that it is not proven: full index builds at $2\times10^8$, $3\times10^8$ and $5\times10^8$ edges ran out of memory in their `nodes` phase under the bench's memory caps, and $10^9$ edges has not been run (Section 6.1).
3. **The Evidentiary Fragility of Ad-Hoc Scripts**:
   When forensic analysts export graph snapshots to desktop software or run unversioned scripts, the chain of custody is broken. Upstream data changes can invalidate historical conclusions, and the provenance of the analysis can be challenged. Inspecto LA models every investigation as an **append-only, hash-sealed op log**. Replaying a step does not re-query the live Dataset, and the Dossier carries a SHA-256 manifest over every stored artefact so that a later change is detectable.

### Editions and packaging

Inspecto LA is sold in the same editions as the rest of Inspecto (names fixed by decision `LA-EDITIONS-1`, 2026-10-17: **Basic / Standard / Advanced**, replacing Personal / Professional / Enterprise across the whole product). The principle is that no edition gets weaker analysis or weaker evidence: editions differ by **who** works (one analyst, a team, an organisation), **how much data**, and **how much governance**. **Basic is the explorer**: it has no sign-in and no Investigations. Everything in Sections 2 and 5 that rests on an Investigation — the sealed op log, replay, the Dossier, Drafts, four-eyes approval, audited reveal, embed links, Data Trust, standing detection — is **Standard and above**.

| Capability | Basic | Standard | Advanced | Preview |
| :--- | :---: | :---: | :---: | :---: |
| Graph studio, projections from any Dataset, saved views, paths, neighbours, patterns, Value Measures | yes | yes | yes | yes |
| Entity Lists, identity resolution | yes | yes | yes | yes |
| Entity profile pages (decision `D-LAE4`) | — | yes | yes | yes |
| Geo map, routes and co-location (decision `D-LAE5`, 2026-10-20) | — | yes | yes | yes |
| Getting started, Add data from a file, Start from..., My work, help on every screen (`LA-ONBOARD-1`) | yes | yes | yes | yes |
| Case history beside a node (decision `D-LAE1`); Link Analysis dashboard Widgets (decision `D-LAE2`) | — | yes | yes | yes |
| Browser graph algorithms (28 of the 29) | yes | yes | yes | yes |
| Industry packs (Telecom, Mobile Money), one per Space | yes | yes | yes | yes |
| Masking | off by default; a mode the Space sets is display only (no sign-in, so anyone may reveal) | enforced, `typed` by default | enforced | enforced |
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

How the explorer is separated from the Investigation surface: the five Link Analysis modules ship in every edition, and one edition feature, `investigations`, gates the Investigation routes, Graph Run, the index and the integration routes. On Basic those routes answer `503 CAPABILITY_UNAVAILABLE` ("available in Standard and above") and the single-page application hides the matching controls, so nothing offered can only fail. A second edition feature, `geoMap`, withholds the Geo map routes (`POST /geo/projection`, `POST /geo/routes`) on Basic in the same way. The separation is pinned over real HTTP by `ControlApiBasicEditionLinkAnalysisTest`. The Postgres store is built into Advanced and Preview and stays inert until `-Dinvestigations.backend=db` is set with a `jdbc:postgresql:` URL; every edition, Advanced included, defaults to the filesystem store. Two items are decided but not in the build: retention, legal hold and residency controls (the `docs/EDITIONS.md` row says "not built yet"), and machine-to-machine client-credentials trust for third-party callers, which awaits a security review (Section 5.7). Named adapters for specific case managers or fraud products do not exist: only a generic connector is built, and a named adapter is built only on a customer's request.

Who each edition is for: **Basic** is one analyst exploring, with nothing evidence-grade (masking is off unless the Space turns it on, and then it tidies the display but is not a security control); **Standard** is for investigation teams; **Advanced** adds scale and policy for regulated organisations; **Preview** is for trials only.

### Capabilities at a glance

Every capability has a stated bound, and a request over its bound is refused with the measured size; nothing is truncated without saying so. Every bound is a configuration item, not code (Section 6.3): the values below are the shipped defaults.

| Capability | What it does | Bound or default | Section |
| :--- | :--- | :--- | :---: |
| Projection and neighbours | Draws links between two identifier columns of any Dataset, with kind, time, weight and attributes | Canvas cap per Space (`projection_node_cap`, 1,000 entities) | 2.1 |
| Recursive paths | Paths between entities across hops, filtered by kind, time and attributes | 12 hops by default, 20 at most, 10 s; on the index, depth $\le 2$ and $\le 20$ keys per level | 4.3, 6.2 |
| Patterns and Value Measures | Temporal and branching pattern search, co-location on the Geo map (Standard and above), value measures such as money moved | Rate-limited per user as an expensive call | 3.4, 5.1 |
| Investigations | Append-only op log, replayable to the byte | Expansions over the Space's four-eyes thresholds wait for a second person | 2.2, 5.3 |
| Drafts | Private working copy per analyst; rebase with typed conflicts; all-or-nothing promote | 50 open per Space, 1 per member; hibernate after 60 min idle, expire after 30 days | 2.4 |
| Graph Run | 29 server-side algorithms | Default 50,000 nodes, 500,000 edges, 30 s; install ceilings 500,000, 5,000,000, 300 s | 3.1, 6.3 |
| Link index | Versioned Parquet index per Dataset and column mapping; full, append and compact builds | 8 deltas before compaction; disk estimated against the budget before the build starts | 4.3 |
| Entity Lists and identities | Watch, block, allow and exclusion lists; asserted identity merges; hash-chained fact log | Up to 64 Entity Types per Space (`input.entity_types`); resolution is deterministic only | 2.5 |
| Dossier | Export of graph, steps and sealed rows with a SHA-256 root and a verify route | Verifies with identifiers masked | 2.3 |
| Standing detection | Value-measure Alert Rules on an Investigation, swept on a schedule under the owner's re-checked authority | Ten typed refusal codes; the sweep holds no capability of its own | 5.6 |
| Data Trust | Data-as-of, missing days, waiting Files and index version on every Investigation; delivery problems as Alerts | Freshness Alert after twice the poll interval (`maximumAge: auto`, `la-daily-feed`) | 4.6, 4.8 |

---

### From a blank install to a first finding in ten minutes

Graph tools usually start with a data-engineering project. Inspecto LA starts with a file. A new installation opens on **Getting started**: **Try the sample** loads invented money transfers with a planted mule ring and opens the graph already drawn, or **Create a Space** names your data and its kind (*Mobile Money*, *Telecom* or *Generic*). A **Space guide** ticks each setup step from what the server actually holds.

- **Add data.** Drop a CSV, TSV, Parquet, JSON or NDJSON file, or an Excel (.xlsx) workbook. The screen applies what it detected and says why; a guess is marked as one until the analyst confirms it. The analyst says which columns hold an Account or a Phone number and which way a link goes, presses **Create and run**, and gets the result in plain words: rows kept, rows set aside and why, each Expectation checked. The **Entities** page and its **Columns** tab show and change what each column holds, so two columns holding the same Entity Type join up in the graph.
- **Start from what you know.** **Start from...** draws one entity (with a type-ahead that shows how busy each match is), an Entity List, a pasted list of values, or a whole Dataset (warned first when it is larger than the canvas cap). An entity, a list or a paste starts as one ring, with busy numbers held back, not as a hairball.
- **Expand, clean up and Exclude.** **Expand…** (one dialog: from the selection or the seeds, direction, 1 to N degrees) shows what the next step will add *before* it adds it. **Collapse** folds a branch into a badge, and **Clean up** keeps only the paths between the seeds, highlights the shortest paths, trims dangling leaves or keeps or removes a selection, each stating its effect before it is applied. Call centres, switchboards and big merchants that link to everyone are **held** in a tray instead of flooding the canvas; **Exclude** removes a node from this Investigation or adds it to an exclusion list so it stays out of every later step. Administrators keep **category exclusion lists** (VIPs, politicians) that apply themselves to every new Investigation, as sealed, replayable steps whose members stay hidden from those who may not read them. A list that could no longer be sealed refuses new Investigations rather than letting one start without its policy, and Settings warns the administrator first.
- **Find something interesting.** Three plain questions over the graph: *Who is busiest*, *Which groups stick together* and *How does A reach B*, each answered in one sentence with **Show on graph**, **Start from these** and **Add to a list**.
- **My work.** **File ▸ Save view** keeps the graph's start and the lists it uses; the front page lists the saved views and *what changed in your data* since the last import, so the analyst comes back to the work, not to a blank canvas.
- **Help on every screen.** *What can I do next?* reads what the Space already holds and offers the next step with one line of why; key terms explain themselves on first use; anything an edition or role does not include is shown greyed out with the reason, never as a button that fails. Local beginner counters (never sent anywhere) tell an administrator how far first-time users get.

**By edition.** Getting started, Add data from a file, Start from..., Entities and Columns, Find something interesting, My work and the help are in **every edition, Basic included**. On Basic, Expand, Held, Clean up and Exclude change the analyst's view (Exclude can also add the values to an Entity List); in an Investigation (**Standard and above**) each is a recorded, replayable step, and the auto-applied category exclusion lists apply to Investigations only. Add data also takes an inbox folder (imported on request, or watched every 30 seconds) and a Postgres table (a read-only snapshot, refreshed on request only) in every edition; on Basic, Add data's *Read from another database or a remote system* is locked, and *Keep this up to date* on an imported Dataset is **Standard and above**. Case history beside a node, the Geo map and the Link Analysis dashboard Widgets are **Standard and above**.

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

The evaluator handles fifteen operation labels (`seed`, `seedBy`, `expand`, `exclude`, `excludeBy`, `hide`, `keep`, `threshold`, `annotate`, `window`, `resolve`, `connect`, and the markers `snapshot`, `compare`, `temporal`); an unknown operation is refused, never guessed. Each log entry records the SHA-256 of the Working Set it produced. The fold is pure: same log, same result, on any machine.

| Operation | What it does | What is sealed into the log |
| :--- | :--- | :--- |
| `seed` / `seedBy` | Starts from named entities, or from every member of an Entity List | The resolved seed keys |
| `expand` | Follows links one hop out from chosen entities, with direction, link-kind, time-window, minimum-event, degree, fan-out, budget and hub-threshold options | The rows that answered, with their fingerprint |
| `connect` | Seeds the given ids and adds the routes connecting them | The rows of the connecting routes |
| `exclude` / `excludeBy` | Removes entities (a reason is required), or every member of an exclusion list, before they spend any budget | The keys removed |
| `hide` / `keep` | Hides entities from view only; `keep` protects an entity from exclusion | The selection |
| `threshold` | Keeps entities inside a band of degree, weighted degree or event count | The band and measure applied |
| `window` | Sets the time range later expands inherit (dates, slot, weekdays, excluded dates) | The window bounds |
| `resolve` | Merges identifiers someone has asserted belong to one party (Section 2.5) | The assertions used |
| `annotate` | Adds a note, with an optional NATO/Admiralty confidence grade, to an entity or a link | The note |
| `snapshot` / `compare` / `temporal` | Marks a log position, diffs two windows, or records burst and periodicity findings | The marker; or the diff or findings, with a fingerprint |

An `undo` is itself a recorded step that reverts the previous one, so the log is the full history of how the analyst reasoned, not only where they ended.

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

**Members and roles.** Each Investigation has members with one of three roles, recorded in an append-only membership log (grant and revoke), so who could change what is itself part of the record:
- **Lead**: the only role that writes the main log; manages members; works in their own Draft. The owner is implicitly a lead, and an Investigation always keeps at least one: revoking or demoting the last lead is refused.
- **Analyst**: works in their own Draft and reaches the main log only by promotion.
- **Reviewer**: reads, and — like a lead — may decide a pending four-eyes request (never their own) when they also hold `canApproveLinkExpansions` (Section 5.3).

**Draft lifecycle.** A Draft is `OPEN`, then `HIBERNATED` after the idle limit, and ends `DISCARDED` (expiry is a discard) or `PROMOTED`. A Draft sees only its base (the main log's first steps when it was forked) plus its own steps; newer main-line work reaches it only by an explicit rebase. A rebase re-applies the Draft's effective steps on the current head and classifies each as `no-op`, `changed`, `superseded` or `blocked`; superseded and blocked steps are dropped and named in the panel, never silently. A Draft also pins the link-index version it read for 30 days (`IndexPins`), so its view does not shift under it while it is open.

### 2.5 Entity Lists and asserted identities

Entity Lists (purposes such as watch, block, allow and exclusion) and identity assertions live in one **Identity Fact log** per Space: one file per fact, each carrying the SHA-256 of the previous fact's bytes, published without overwrite. Every read verifies the whole chain; a gap or a wrong hash is an integrity error (`500 INTEGRITY_VIOLATION`), not a silently different list. Analysts assert that two identifiers belong to the same party, and resolution follows only those asserted facts — a deterministic union of assertions, never a fuzzy guess — which is what the `resolve` operation applies inside an Investigation. `seedBy` and `excludeBy` seal exact keys only: range and CIDR entries match within the lists but do not yet drive seeding or exclusion in an Investigation (deferred until a customer asks; `docs/okf/product/module/entity-list/spec-entity-list.md`).

---
