---
type: Brochure
title: Inspecto LA whitepaper — Enterprise governance, security and access control
description: Part 4 of 6 of Inspecto LA whitepaper: · Enterprise governance, security & access control
---

# Inspecto LA whitepaper — Enterprise governance, security and access control

Part 4 of 6 of [Inspecto LA whitepaper](brochure-inspecto-la-whitepaper.md) · previous: [Systems architecture and data plane engineering](brochure-inspecto-la-whitepaper-architecture.md) · next: [Performance, sizing, limits and the claims register](brochure-inspecto-la-whitepaper-performance-claims.md).

# 5 · Enterprise governance, security & access control

Link analysis often exposes highly sensitive personal data, communication records (CDRs), and financial histories. Inspecto enforces a defense-in-depth perimeter. **Sign-in, enforced masking, four-eyes approval, audited reveal, embed links and the Investigation-scoped controls of this section are Standard and above**; on Basic there is no sign-in, masking is off by default and display-only when a Space turns it on (anyone may reveal) and the Investigation surface answers 503 (Section 1).

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
- **Capability.** Reads of the exploration surface need only a signed-in subject. Writes and costly actions need named capabilities: `canManageIncidents` (create and step Investigations, Drafts, snapshots, Case links and hand-off), `canOpenInboundInvestigation` (inbound, seed role `integration`), `canRunLinkGraphAnalysis` (start a Graph Run), `canBuildLinkIndex` (start an index build), `canAuthorAlertRules` (bind a measure to an Alert Rule), `canShareInvestigation` (mint and revoke embed links), `canApproveLinkExpansions` (decide a held expansion) and `canRevealLinkEntities` (unmask). Seeded defaults: `canManageIncidents`, `canRunLinkGraphAnalysis` and `canBuildLinkIndex` go to `operations`, `support`, `power` and `admin`; `canAuthorAlertRules` to `power` and the builder roles; `canShareInvestigation` to `admin` and `power`; **`canApproveLinkExpansions` and `canRevealLinkEntities` only to `admin`**; and `super` holds everything. With no Subject (Basic) the capability gate is a no-op.
- **Investigation open gate.** Membership applies only to Investigation routes: a non-member gets **404 Not Found, never 403**, so a user cannot confirm that an Investigation into a sensitive target exists; a member who lacks the needed role gets 403 only after Dataset visibility and (in Advanced) the policy engine have spoken. A member of the linked Case may read. Pinned by `ControlApiInvestigationMembersTest`.

### 5.2 Entity masking

To comply with data-privacy frameworks while allowing analysts to discover structural patterns, Inspecto masks entity identifiers on the way **out** of every Link Analysis answer; engine results and caches stay raw.

- **Masking Modes** (a Space setting, `maskingMode`):
  - `typed` (the default in Standard, Advanced and Preview; fails closed; in Basic the default is `none`): only columns whose classification is claimed by a masked Entity Type (e.g., MSISDN, IMSI, IBAN) are tokenised; a masked type's seed, endpoint and unknown types are masked.
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
- **Standing Detection Sweeps**: A measure over an Investigation's Working Set (for example accounts showing a high pass-through ratio) is bound to an Alert Rule. A standing-detection record names the principal `sweep:<investigation id>`, its owner, Dataset and masking basis; the sweep **re-decides its authority before every read** (the owner must still lead the Investigation and view the Dataset; masking must not have tightened; the policy engine must not deny) and holds no capability of its own. A refusal carries one of ten stable codes, which ride the audit trail (`LINK_STANDING_DETECTION_REFUSED`) and the response: `NOT_ENABLED`, `NO_OWNER`, `BINDING_CHANGED`, `DATASET_GONE`, `DATASET_NOT_SHARED`, `ROLE_SHARE_ONLY`, `NOT_LEAD`, `MASKING_TIGHTENED`, `POLICY_DENIED` and `UNDECIDABLE` — anything the sweep cannot decide is a refusal. The job needs the alerts module.
- **From an Alert to the rest of the platform**: A breach fires an **Alert**. Under the platform's severity promotion, an Alert Rule of `CRITICAL` severity also opens a de-duplicated **Incident** linked `ESCALATED_FROM` its Alert. Grouping Incidents into **Cases** is done by the platform's Case Rules or by an analyst; Link Analysis does not open Cases by itself, and no automatic Incident-to-Case chain is claimed here.
- **Privacy Preservation in Alerts**: the Alert's evidence carries only aggregates — the Measure, its threshold line, `breachCount`, and the worst entry's `worstOf` / `worstValue`. It carries no entity id, name or alias; the entities are named only inside the gated Investigation. Notification content is in any case always masked (Section 5.2.1).

### 5.7 Third-party integration (Standard and above)

Target products were unknown, so **one generic connector** is built, configured per Space; a named adapter would be built only on a customer's request.
- **Inbound**: `POST /inv/inbound/investigations` lets a fraud product or case manager hand over its own record and the entities it concerns; Inspecto LA opens a seeded Investigation and answers its id and a link. It is idempotent on the caller's system and record id, composes the existing write routes (so every gate and audit event applies), and records the caller's record as an **external reference** that is never fetched and grants nothing. `POST /inv/deep-links/resolve` serves the "Open in Inspecto LA" deep link (`/open`); a raw value of a masked type is refused and never repeated.
- **Outbound**: `GET`/`PUT /inv/integration` (administrator-only; the endpoint is a registered `https` Connection, never a literal secret in configuration) and `POST /inv/investigations/{id}/handoff`, which sends the Dossier bundle and findings to the configured case manager, retries under one idempotency key, and links the returned case id as an external reference. **Signed webhooks** (HMAC-SHA256 over a timestamp and the body) announce `investigation.created`, `draft.promoted`, `alert.fired` and `approval.needed`; they carry no entity id. Every outbound call passes the Space's egress allow-list.
- **Not enabled**: machine-to-machine client-credentials trust (an inbound token scoped to one integration role). A formal security review — SSRF, token scope, replay, audit on both sides, secret handling, masking — is owed before it is built. Tests: `ControlApiInvestigationInboundTest`, `ControlApiLaIntegrationTest`.

### 5.8 Audit trail

Every state change and every sensitive read emits a typed audit event: Investigation creation and each step (`LINK_INVESTIGATION_CREATED`, `LINK_INVESTIGATION_STEPPED`), reveals (`LINK_ENTITY_REVEALED`), four-eyes requests and decisions, membership grants and revokes, Draft fork, rebase, promote, discard and expiry, Dossier builds, exports and verifications, Graph Runs (`LINK_GRAPH_RUN_STARTED`, `_COMPLETED`, `_BUDGET_EXCEEDED`, `_CANCELLED`, `_FAILED`), index builds (`LINK_INDEX_BUILD_STARTED` through `_FAILED`), embed mints, views and denials, hand-offs and webhook deliveries. Refused and failed requests are recorded too — a refused sign-in at the authentication gate, a denied embed token, a refused standing-detection sweep, a failed Graph Run — and an audit row never carries a raw identifier that masking would hide (reveals record aliases, embed rows a token hash).

---
