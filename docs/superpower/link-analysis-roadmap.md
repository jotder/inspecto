<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-05 by consolidating eight Link Analysis (LA) plan/design docs into ONE tracker. This is the ONLY
  place that lists unbuilt LA work. How LA works today: docs/okf/frontend/features/link-analysis.md. How to use it:
  docs/ui/link-analysis-user-manual.md. Retire sections (distil into the OKF concept, rows to BACKLOG §3.12) as
  each ships; archive this file when nothing is open.
-->

# Link Analysis — roadmap (everything not yet built)

**The three Link Analysis documents.** (1) [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) — what is built and why; (2) [`ui/link-analysis-user-manual.md`](../ui/link-analysis-user-manual.md) — the analyst manual; (3) **this file** — what is still open. Backlog rows `LA-*` live in [`BACKLOG.md`](../BACKLOG.md) §3.12; terms in [`GLOSSARY.md`](../GLOSSARY.md).

**Full original designs** (read only when you start the work; archived, never maintained) are in `archived-documents/plans-archive/`: `la-separation-feasibility-plan` · `la-separation-d7-design` · `la-embed-view-design` · `la-data-preparation-plan` · `investigation-store-design` · `link-analysis-entity-model-design` · `la-live-detection-design` · `link-analysis-ui-mockup.html`.

## Status at a glance (2026-10-05)

| Workstream | State | Open |
|---|---|---|
| Separation, option D (D-0…D-6) | built | `LA-APP-REAL-SIGNIN-1`, I1 trust, `LA-INDEX-SCALE-MEASURE-1` (§1) |
| D-7 parallel analyst sandboxes (Drafts) | built D7-1…D7-7 | team Drafts, Draft SPA surface, linear rebase (§1) |
| Embed view | deferred | `LA-EMBED-VIEW-1` (§1) |
| Data preparation (CDR → fast index) | **plan only, nothing built** | LDP-D1…D11, WP0…WP10 (§2) |
| Daily ingestion (CSV / Hive / database to the index) | **plan only, nothing built** | `LA-DAILY-INGEST-1` T1…T9, decisions D-ING1…D-ING5 answered 2026-10-05, phase 1 is file-first |
| Investigation store | S0–S7 closed | demand-gated items (§2) |
| Entity model LA-17 | slice 1 + slice 2 built | SPA member browsing, `excludeBy` range/CIDR (§3) |
| Live detection | LD-1…LD-7 built | operator confirmation of D-LD2…D-LD18; template sharing (§3) |

## Separation (option D) — what is NOT yet built or still open

Drawn from: `archived-documents/plans-archive/la-separation-feasibility-plan.md`, `la-separation-d7-design.md`, `la-embed-view-design.md`. Built detail lives in OKF `frontend/features/link-analysis.md`.

### 1. Option D long-term target and phase status

**Target (signed 2026-09-30, D1-D21 answered; signing starts no phase).** LA is a product with its own core: **part of Inspecto and sellable separately** from the SAME reactor by build flavor plus a few extra pieces (host, landing page, auth wiring). **No duplicated code**: anything both products need is EXTRACTED into a shared module, never copied.
- Shared platform layer = new narrowly named modules `http-spi` / `auth-spi` (+ moved OIDC impl) / `audit-spi` (D8); LA modules `la-core`, `la-graph`, `la-storage`, `la-data`, `la-api`; `la-inspecto` bridge (Inspecto editions) and `la-app` host (LA product).
- ⛔ LA modules never import `inspecto-processor`, `inspecto-etl` or `inspecto-engine`; the bridge is the only place that knows both worlds. Inspecto-only features (Alert Rules bound to Investigations, `ComponentAccess` Dataset sharing, catalog row scope) exist ONLY through the bridge and report themselves ABSENT in the LA product, never half-working.
- Storage: Parquet read by DuckDB, LA-specific partitioning, entity-hash edge index + mirrored to-entity copy (D10). Engine order (D9): DuckDB SQL + index first; DuckDB 2.0's own graph features when the pin moves (⛔ **DuckPGQ dropped 2026-10-01, do not revive**); external graph DB only after a MEASURED gap (D-S3); vector index only with entity-context/fingerprinting work I4 (D13). Any graph/vector DB is LA-exclusive, never a dependency of the rest of Inspecto.
- The index is a SECOND copy of the data: incremental append by partition, retention inherited and never longer than the raw data (D11).
- Sign-in in the LA product: external IAM via the moved OIDC authenticator; demo sign-in for demos only (D12). Geo always in the LA product, one SKU (D2, D14). Same SPA / UI module, no module federation (D3). Option A (LA edition flavor) ships first if the customer has a date, D-0 in parallel (D15). Getting data in: templates first, upload route later (D4). Cases in standalone: local by default, remote when paired (D5). Topology (D1): (c) own data first, query-through remote later.
- ⚠ "DuckDB 2.0" is NOT verified; read the target as "the DuckDB major current when the work starts" (pin now 1.5.6.0).

| Phase | Status |
|---|---|
| D-0 seam hygiene (SEP-01..06, 08) | done (SEP-02 deliberately kept as `AbsentGeoLinkRoutes.SURFACE` + `GeoLinkAbsentSurfaceParityTest`; revisit when a standalone host needs it; SEP-07 not needed, one SKU) |
| D-1 shared platform layer | done |
| D-2 spikes D-S1..S5 | mostly measured; D-S2 dropped; 10^9 axis OPEN (`LA-INDEX-SCALE-MEASURE-1` (3)) |
| D-3 `la-storage` index + `la-data` | Path A steps 1-8 built; index OFF by default; 5-level index walk FAILED the 1.5 s criterion in the spike (path narrowed); SPA surfaces + scale measure open (`LA-INDEX-SPA-SURFACES-1`, `LA-INDEX-SCALE-MEASURE-1`) |
| D-4 `la-graph` server-side algorithms | done; remainder is `LA-GRAPH-RUN-*` rows |
| D-5 `la-app` host + SPA shell + product flavor | steps 1-7 built; real IdP sign-in unverified (`LA-APP-REAL-SIGNIN-1`) |
| D-6 integration | references + Dossier bundle built; embeddable view OPEN (`LA-EMBED-VIEW-1`); I1 machine-to-machine trust not built (no live call needs it) |
| D-7 Drafts | backend D7-1..D7-7 built 2026-10-03/04; open remainder in section 2 |

Integration contracts still unbuilt (feasibility section 2): I1 trust between installations (OIDC client credentials; WSO2 supports it), I2 remote Dataset query (SEP-21), I3 remote Case adapter (SEP-22), I4 entity-context port (SEP-23, fingerprint/360), I5 "Open in Link Analysis" URL contract (SEP-24). Phase 2 (SEP-20..24) is only for a real cross-installation need. ⚠ Phase 0 must precede Phase 2 (D6). Each new mutating route clears the four `endpoint` gates + an `openapi-v1.json` entry. ⛔ Cross-installation security needs a formal review first (SSRF, token scope, replay, audit on both sides; precedent `ses-sns-adapter-design.md`). Masking at export must keep the Dossier SHA-256 root verifiable (LA `D-U6`).

### 2. D-7 parallel analyst working copies ("Drafts")

Name decided (D16): **Draft** (not "sandbox": that is the SQL lockdown; ⛔ not "Workbench": Builder surface). Other signed option-D decisions: D17 private per analyst, team Draft (edit lease) only "later if asked"; D18 rebase-and-promote, approval required when sensitive; D19 membership replaces owner-only; D20 Dossier only from the PROMOTED Investigation; D21 target = 20 analysts, 50 concurrent Drafts, 10^9 edges.

**Signed D7-Q1..Q8 (operator 2026-10-03, every recommendation accepted):**

| Id | Decision | Meaning |
|---|---|---|
| D7-Q1 | (a) | Record the D-E7 change as an IN-PLACE AMENDMENT of D-E7 citing D19, not a new superseding decision |
| D7-Q2 | (a) | A Draft may bind a Dataset with no index (D-E3 seal-at-use) |
| D7-Q3 | (b) | Pinned index version expires after N=30 d (warn at N-7); then ops/promote 409 "must rebase" |
| D7-Q4 | (a) | Checkpoint at every step until a measured cost says otherwise |
| D7-Q5 | (b)+(c) | Hibernate after 1 h idle; expire (discard) after 30 d idle, warn actor/lead first |
| D7-Q6 | (a) | Heavy-job cap default `min(4, cores/3)`, overridable in config |
| D7-Q7 | (b) | Analyst confirms each `superseded` conflict on accept; nothing leaves the record silently |
| D7-Q8 | (a) | Lead and reviewer may read another analyst's Draft |

**Model (built).** Investigation = the record and Dossier source; members `lead|analyst|reviewer` in `members.jsonl` (creator = first lead; no file = legacy owner-only). Main log steps 1..N. A Draft lives INSIDE its Investigation (not a fork; a fork is a peer that never merges back), one open per member (D17), at `drafts/<draft-uuid>/{header.json, log.jsonl, sets/, markers}`.
- State rule: `state(draft) = evaluate(main[1..k] ++ draftLog)`, one list, Draft steps numbered k+1..; `baseLogHash` = sha256 of main's first k lines, rechecked on every read/write (409 if the main prefix changed).
- Pristine: a Draft sees only baseline + own ops; newer main / Entity List / index reaches it ONLY by explicit rebase. Determinism contract: replay of baseline + own ops reproduces every sealed hash.
- Rebase carries EFFECTIVE ops only; conflict kinds `no-op` / `changed` / `superseded` / `blocked`; `superseded` and `blocked` must be listed in `confirm` (blocked can only be dropped, never force-carried). Report carries steps/kinds/counts, never ids or rows.
- Promote = rebase to head then append under the main lock, all-or-nothing, each entry gains `draft{id,actor,baseStep,...}`; head moved = 409; a sensitive expand is HELD as a pending request (202) decided through the existing D-U7 approve/deny (approver never the requester).
- Access: everything passes the one `open` gate; R3 and the Enterprise PDP (D-E7) can only NARROW. Non-members keep 404-as-absence; lead-only routes give members 403.
- Admission: 50 open Drafts per Space (409), heavy permit `min(4,cores/3)` never waited for (429), light reads unadmitted by design; Graph Run keeps its own separate pool (decided).
- Lifecycle: hibernate 1 h / expire 30 d (now `drafts` keys in `link-analysis.toon`), lazy sweep (no scheduler), crash recovery `DraftStore.recover`; no per-Draft `draft.duckdb` was needed.
- Evidence: Drafts are exploration, never Dossier sources; every op audited with draft id and actor; masking (D-U6) unchanged.

**Slices:** D7-1 membership, D7-2 version pins, D7-3 store+routes, D7-4 checkpointed append, D7-5 rebase+conflicts+promote, D7-6 admission/hibernate/expiry, D7-7 bench: ALL BUILT. `LA-DRAFT-PROMOTE-COST-1` closed.

**NOT built / open in D-7:**
- 10^9-edge run: only 10^8 reached (10^8 index build took 24 min under an 8 GB cap); remains `LA-INDEX-SCALE-MEASURE-1` (3). The D21 10^9 target is unproven on real Draft state.
- Team/shared Draft with edit lease (D17): deliberately later, only if asked.
- Draft SPA surface: design says UI layout is "a separate SPA design once signed"; no such design is in the three docs (check the board before assuming it exists).
- Linear rebase: examined and DECLINED (2026-10-04, `LA-DRAFT-REBASE-COST-1` closed as a decision). Reopen only on an analyst-reported slow rebase (800 steps ~ 8.4 s); starting design = chained hash `H_i = sha256(H_{i-1} || canonical(delta_i))` plus an explicit pin change. ⛔ It would change every sealed hash and replace the promote byte-identical oracle.
- Accepted gaps: a pending promote does not freeze the Draft (a later write just makes approval 409); expiry of a Draft with a pending promote => approve 409, redo from a new fork; analyst promote on a NON-sensitive Investigation appends with no second person (as signed, say so if unwanted); conflict report recomputes on every GET; `expiryWarning` field is the only warning (no push channel); a same-size/same-mtime in-place tamper of the main log is caught only by replay; checkpoint is in memory (restart = one cold fold); Draft writes still parse main prefix + own log (parse-free path open).
- ⚠ `reveal` and fork-reorder are lead-only; if analysts must unmask that is a decision to take at the Draft gate, not an accident.

### 3. Embed view (`LA-EMBED-VIEW-1`, P3, board section 3.12) — DEFERRED, design only

- **Why deferred:** operator 2026-10-03 KEEP DEFERRED until a NAMED consumer asks (EMB-1). It needs a NEW principal type in `inspecto-auth-spi` (read-only token scoped to ONE Investigation), a grant store, new gated routes and a host-wide header change.
- **Needs:** reuse the BI share-token PATTERN, not the class (stateless HMAC cannot be revoked one at a time; `ShareTokens` is package-private in core). New `EmbedGrants` store behind the `InvestigationStore` seam from day one; opaque random 256-bit token, only SHA-256 stored; principal `EmbedGrant(grantId, space, investigationId, issuedBy, issuedAt, expiresAt, label)` which is NOT a `Subject` (no Subject attached => fails closed on every capability-gated route; structural safety property).
- TTL mandatory, default 72 h, max 30 d; per-grant + bulk revoke; auto-revoke on close/delete/masking loosened (EMB-9); kill switch `-Dla.embed.enabled` default OFF (mint 503, public 404); uniform 404 for unknown/tampered/expired/revoked/deleted/disabled.
- Always masked, no reveal, even if Space masking is `off` (EMB-6); GET only, no export (Dossier bundle stays the file path); honest limit: screenshots, control = expiry + revocation + audit.
- Routes: `POST/GET /inv/investigations/{id}/embeds`, `POST .../embeds/{grantId}/revoke`, public `GET /public/investigations/{token}`, SPA `/embed/inv/:token`. New verb `canShareInvestigation`; ABAC action `embed` + max-classification refusal 422 (EMB-10). Gates: `CapabilityManifest`, `openapi-v1.json`, `isSelfVerifyingPublic` prefix `/public/investigations/`, per-IP rate limit, `AbsentGeoLinkRoutes`.
- Headers: per-grant `frame-ancestors` allowlist (https origins, max 5, empty = `'none'`); `Referrer-Policy: no-referrer`, `no-store`, `nosniff`, no CORS, no cookies; ⚠ access-log redaction of the token path REQUIRED (EMB-12); `frame-ancestors 'none'` / `X-Frame-Options: DENY` on every other response is a host-wide change (EMB-13).
- Out of scope: I1 trust, any write, per-viewer identity, export from the embed, editor embed, several Investigations per grant.

**EMB-1..EMB-14 (all OPEN, recommended answers):** 1 keep deferred; 2 new store, reuse pattern only; 3 opaque random + SHA-256; 4 iframe opt-in per grant; 5 accept masked-until-expiry leak risk; 6 force masked; 7 new `EmbedGrant` principal; 8 JSON then SPA shell as a separate UI task; 9 auto-revoke yes; 10 ABAC `embed` yes; 11 no four-eyes on mint for v1; 12 log redaction required; 13 frame-deny headers host-wide yes (own test); 14 split auth-spi / la-api / core ControlApi, public-prefix list becomes a registered extension point. Test plan: real HTTP, armed Authenticator, identical status AND body for all 404 causes, no escape to `/inv`, `/bi`, `/public/dashboards`; hand-run mutations (an agent may not edit a guard).

### 4. Other open decisions, spikes and board rows

| Id | State |
|---|---|
| `LA-EMBED-VIEW-1` | P3 open, section 3 |
| `LA-APP-REAL-SIGNIN-1` | P3 open: la-app OIDC proven over Demo auth and a scripted real WSO2 backend run, never from the SPA against a real IdP |
| `LA-INDEX-SCALE-MEASURE-1` | open; item (3) = 10^9 run (bucket count ~256-1024 at 10^9; extrapolation only) |
| `LA-INDEX-SPA-SURFACES-1` | open (index SPA surfaces) |
| `LA-SEP-SPIKES-1` | CLOSED 2026-10-03 (D-S5 re-run at 10^8); 10^9 moved to `LA-INDEX-SCALE-MEASURE-1` |
| `LA-DRAFT-PROMOTE-COST-1` / `LA-DRAFT-REBASE-COST-1` | closed (promote linear; rebase quadratic by decision, reopen trigger in section 2) |
| Spike D-S2 (DuckPGQ) | dropped; re-assess only as a NEW spike against DuckDB 2.0's own graph features when the pin moves |
| External graph DB / vector index | only on a measured depth x volume gap (D-S3 showed the gap is VOLUME, not depth) / with I4 |
| Index bloom filters | not isolated from min/max skipping (D-S1) |
| Hash caveat | DuckDB `hash()` not proven across versions; `md5_number_lower(x) % N` matches Java MD5; the reader must compute and push the bucket predicate |
| Browser behaviour to decide | label propagation floods a bridge between two triangles (Java agrees with TS); `hits` eigenvector rank order is noise within 2e-16, any server ranking needs an explicit tie rule; graph model carries no edge data (weights via an edge-id map) |
| Near-term options | A (LA flavor) = M; B superseded by D; ⛔ C separate codebase rejected (XL, duplicates DuckDB/auth/audit/config) |

## Data preparation: telecom CDR to fast LA index (Domain Profile seam) - PLAN ONLY, nothing built

Drawn from: `docs/archived-documents/plans-archive/la-data-preparation-plan.md` (created 2026-10-01, re-evaluated 2026-10-02 at `8eb2f6a9d`). All 11 decisions are OPEN (operator decides later); every recommendation below is a recommendation, never an answer. Counts: 20 Must, 11 Should, 9 Could, 7 Won't.

Inputs: telecom RA/FM on CDR, domain-neutral design (I-01); ~10^9 CDRs/day, split by record type unknown (I-02); daily freshness (I-03); 4 hops with filters, above ~10K result nodes the analyst adds filters (I-04); 1-3 analysts (I-05); "who was with X" co-location drives Geo (I-06); free to pull full data from Hive and lay out own Parquet (I-10); Domain Profiles separate from core, several active, shipped only by us (I-11).

Critical path: D-1 (modules) and D-4 (Graph Run) are done; **D-3 (the index) is unstarted and unscheduled = WP3 is the critical path**. D-4 ceilings (500,000 nodes / 5,000,000 edges) leave 10^8-10^9 to an index-backed engine; nothing built proves D21. Sequence: Wave 0 spikes SP1/SP2 + LDP-D1 -> WP3 builder -> WP4 (supernode, lists, gate) / WP6 (presence) -> WP7 UI -> WP8 acceptance run.

### Ranked priority list (rule: retire largest unknown, unblock most, Must before Should, no-decision before gated; size S<=2d, M~1wk, L 2-3wk, XL>1mo)

| # | Item | Class | Size | Gate | Wave |
|---|---|---|---|---|---|
| 1 | Facts brief F-01..F-16 to data engineers | enabler (M05, M17) | S | - | 0 |
| 2 | SP1 real writer: bucket+sort+row group (M02) | Must | M | - | 0 |
| 3 | SP2 skewed-corpus traversal; daily vs window table; EXPLAIN of frontier join | Must (M05, M06) | M | - | 0 |
| 4 | SP3 Hive extraction + landing throughput (M01) | Must | M | needs Hive access | 0 |
| 5 | SP4 list pruning at scale; fact-log replay cost (M07) | Must | S | - | 0 |
| 6 | SP5 node dictionary + longest-prefix enrichment (M04) | Must | M | - | 0 - **DONE 2026-10-06: measured, see "SP5 findings"** |
| 7 | Rate-limiter coverage of `/inv` and `/geo` (M16 part) | Must | S | - | 0 |
| 8 | Doc fixes (below) + GLOSSARY entries for signed terms | enabler | S | - | 0 |
| 9 | SP6 presence sizing + co-location (M10) | Must | M | after F-02, F-05 | 0 |
| 10 | Decisions LDP-D1, D2, D6 | - | - | operator | 0-1 |
| 11 | M03 idempotent partition replace, late data, backfill, durable ledger | Must | M | - | 1 |
| 12 | M02 writer controls in product | Must | M | SP1 | 1 |
| 13 | M04 E.164 normalisation + node-dictionary enrichment | Must | M | SP5 | 1 |
| 14 | M01 Hive acquisition path | Must | M-L | LDP-D6 | 1 |
| 15 | M08 build-time as-of identity (SCD2 Reference) | Must | S-M | LDP-D8 (soft) | 1 |
| 16 | M05 + M09 freeze index schemas and manifests | Must | M | LDP-D1, SP1, SP2 | 2 |
| 17 | M19 coverage and quality gates in the build | Must | M | - | 2 |
| 18 | WP3 builder: daily incremental index job (D-3 content), CRITICAL PATH | Must | L | SP1, SP2, LDP-D1 | 2 |
| 19 | M06, M07 supernode policy, SQL-prunable lists, barrier | Must | M-L | LDP-D3, D4 | 2 |
| 20 | M11 result gate | Must | M | LDP-D2 | 2 |
| 21 | M10 presence index + server-side co-location | Must | L | SP6 | 2 |
| 22 | M12 analyst UI: hop ladder, lists, gate, ranked table | Must | M-L | server fields shipped | 2-3 |
| 23 | M20 evidence drill-through (confirm with investigators; else demote to Could) | Must (confirm) | M | LDP-D6 | 2-3 |
| 24 | M18 Domain Profile seam + per-Space enablement | Must | M | LDP-D7 | 3 |
| 25 | Telecom profile extraction (`EntityTypes.DEFAULTS`, `ValueMeasures`, SPA `domain-profile.ts`, normalisers) | Must | M | LDP-D7 | 3 |
| 26 | S08 derived neutrality guard + toy second profile; FX paper sketch | Should | S-M | LDP-D7 | 3 |
| 27 | M14, M15, M16 tiers, read audit, retention/hold/erasure, operations | Must | L | LDP-D9, D11 | 4 |
| 28 | M17 acceptance run | Must | M | LDP-D10 | 4 |
| 29 | M13 + S03 detection scope; scalable batch features | Must/Should | L-XL | LDP-D5 | 5 |
| 30 | S01 mobile money, S02 GPRS incidence, S04-S07, S09-S11, Coulds | Should/Could | - | various | 5 |

Start-now (no decision): #1-9, M11 server gate, M03.

### Work packages

| WP | One-line | Status | Depends on |
|---|---|---|---|
| WP0 | Requirements closure + hygiene: facts brief F-01..F-16, doc fixes, GLOSSARY terms (Working Set, Dossier, Snapshot, Draft, Purpose, Masking mode, Value Measure), cross-check M20/M14 vs RFP gap G-20 if present | not started (Wave 0) | - |
| WP1 | Spikes SP1-SP7; each appends a numbered result block and updates the decision it feeds (= signed D-2) | not started (Wave 0) | - |
| WP2 | Platform data prep (M01-M04, M08, M20 part): writer layout (hash bucket, sort, row group, file size on the one `copyOpts` seam in `PartitionWriter`, via `SafetyPolicy`); deterministic partition replace + durable ledger; E.164 normalisation; node-dictionary enrichment on distinct numbers only; acquisition per LDP-D6 | not started (Waves 0-1) | SP1, SP5, LDP-D6; none on LA |
| WP3 | The index `la-storage` (D-3 content; M05, M06, M08, M09, M19): schemas + manifests, daily build as one builder class (Pipeline node type via bridge + `la-app` job), per-day coverage manifest written last, index-backed `GraphInput` loads a subgraph | not started, CRITICAL PATH (Wave 2) | SP1, SP2, LDP-D1, WP2 |
| WP4 | Traversal + result control (M06, M07, M11): hop ladder over index, supernode policy, derived `list_member`, barrier as `expand` field, gate; new routes clear the four gates | not started (Wave 2) | WP3, LDP-D2/D3/D4 |
| WP5 | Domain Profile seam + telecom profile (M18, S08, W04): extract telecom assumptions, derived guard, toy 2nd profile, FX one-page sketch | not started (Wave 3) | LDP-D7 |
| WP6 | Geo presence (M10, S09): presence tables in two sort orders, cell Reference, server-side "where was X"/"who was with X", hot-cell cap | not started (Wave 2) | SP6, WP3 |
| WP7 | Analyst UI (M12, S04): per-hop filters, list picker (hide/exclude/barrier), gate refusal dialog, ranked table, data-as-of badge; `angular-ui` conventions | not started (Waves 2-3) | WP4 server fields |
| WP8 | Governance + operations (M14-M17, S10): sensitivity tiers as separately registered Datasets, read audit, masking on stateless reads, retention/hold/residency/erasure, build cancel/restart, backup scope, counters, quotas, acceptance run | not started (Wave 4) | LDP-D9/D10/D11, WP3 |
| WP9 | Batch features + detection (M13, S03): index-backed engine behind `GraphEngine` seam -> Risk Score -> Alert -> Case -> Investigation seed; if out of scope, node properties are filter/seed attributes only | not started (Wave 5) | LDP-D5, WP3 |
| WP10 | Extensions: S01 mobile money, S02 GPRS incidence, S05 evidence output, S06 public API, S07 Drafts, S11 valid-time assertions, then Coulds | not started (Wave 5) | LDP-D7/D8/D9 |

Done-when: WP2 sorted row groups survive partitioned write, day re-run gives identical counts / no stale file; WP3 AC-01 + AC-02, a dropped record type shows as a visible gap, index dependency rule (D-1 decision 5) green; WP4 AC-03/04/07/08 on skewed corpus + mutation test proving hidden nodes spend no budget; WP5 AC-09; WP6 AC-06 with planted-crowd cell excluded and reported; WP7 4-hop-with-filters run by clicking incl. refusal path; WP8 AC-08, AC-10, acceptance run AC-01..AC-10 green on reference host.

### Open decisions (all OPEN; operator writes on the Answer line)

| ID | Decision | Recommendation | Blocks |
|---|---|---|---|
| LDP-D1 | Which size target governs: D21 (20 analysts, 50 Drafts, 10^9 edges) or this deployment (1-3 analysts, ~10^9 CDRs/day); does "edge" count mirrored rows | Keep D21 as product ceiling; state separate telecom acceptance target; index the unit SP2 proves sufficient (daily tables or window table) | M05, index unit, retention, SP7 |
| LDP-D2 | What "result > 10K" counts: nodes found or drawn | Gate counts nodes FOUND; canvas keeps caps (500/2,000/750); larger results as ranked table; LA-06/LA-07 stay refused | M11, M12 |
| LDP-D3 | Where large/computed lists live | Fact log for analyst lists; Parquet sidecar is the single SQL-facing form; computed lists write sidecar with `computed` provenance; index derives `list_member` (respects D-M8) | M07 |
| LDP-D4 | How a barrier is expressed | Field `barrierListIds` on the `expand` rung, not a new operation (vocabulary closed) | M07 |
| LDP-D5 | Detection in scope (scheduled scoring -> Alerts seeding Investigations)? | Investigation first; node properties as filters/seeds; detection only once Risk Score -> Alert -> Case is the chosen consumer | WP9, S03 |
| LDP-D6 | How data leaves Hive; where record-level evidence (M20) comes from | (a) Hive-side aggregation -> Parquet files -> landing Collector (~10^8 rows not 10^9, no Kerberos JDBC/CSV); evidence = retained raw Parquet bucketed by A-party unless F-12 shows on-demand Hive fast enough | M01, M20 |
| LDP-D7 | Domain seam name and mechanism | Extend **Domain Profile** (today SPA labels only) to a server+UI concept declared per Space in TOON; Space Templates ship Profiles; enter Domain Profile, pair link kind, incidence link kind in GLOSSARY first (⚠ "pack" already means pattern/job/content pack) | M18, S01, S08, WP5 |
| LDP-D8 | Identity validity model | Build-time as-of resolution from SCD2 Reference for bulk maps (M08); fact log keeps analyst assertions; valid time on assertions = S11 | M08 detail |
| LDP-D9 | Access by sensitivity tier | Tiers as registered Datasets; sensitive tiers audited into the chain; stateless reads honour masking mode | loading browsing (S02) / money (S01) data, M14 completion |
| LDP-D10 | Acceptance criteria numbers AC-01..AC-10 | Adopt as starting targets: AC-01 day build <=4h; AC-02 re-run identical; AC-03 1-hop p95<=100ms; AC-04 4 hops/>=2 filters p95<=5s on skewed corpus; AC-05 refusal <=1s, no partial result; AC-06 co-location 7d p95<=3s; AC-07 excluded nodes spend no budget/degree; AC-08 denied Dataset = 404 on `/inv` AND `/geo`, sensitive read audited; AC-09 core holds no profile-derived word, 2nd profile loads, collision fails closed; AC-10 3 analysts + 1 heavy job moves others' p95 <=2x | M17, final run |
| LDP-D11 | Retention, legal hold, residency, erasure (D-U8 refuses purge of Investigation evidence) | Retention inherits (D11); hold flag makes maintenance skip a partition; erasure needs legal input first | go-live with real customer data |

### Spikes (measure before freezing; each appends its result to plan section 9)

- SP1: does the real `PartitionWriter` produce bucketed, sorted, row-group-tuned files (~2x10^8 rows/day)? Pass: row groups sorted/non-overlapping, files 128-512 MB, one-key lookup reads one file. Feeds M02, D1.
- SP2: CDR-shaped skew (hubs 10^5-10^6) at 10^8..10^9 rows via `InvTraversalBench`; daily tables vs window table; AC-03/04; record whether the frontier join is pushed below pair aggregation. Feeds M05, M06, D1.
- SP3: Hive per-day extraction + landing + ingest of ~2-4x10^8 aggregated rows via `parsing.frontend: parquet` (VARCHAR retype cost); JDBC alt if Kerberos. Pass: day lands within AC-01 budget. Feeds M01, D6.
- SP4: list anti-join cost (1-50 lists, up to 10^6 members vs 10^8 edges) + fact-log replay time. Pass: <=25% added hop latency. Feeds M07, D3.
- SP5: node dictionary 2x10^8-10^9 numbers, longest-prefix enrichment of new numbers, full re-derive. Pass: daily upsert <=30 min, memory + re-derive time stated. Feeds M04.
- SP6: presence at 4.5x10^8 rows/day x 7 days; "who was with X" with/without hot-cell cap. Pass: AC-06. Feeds M10, F-05 (waits on F-02/F-05).
- SP7: concurrency (3 and 20 analysts + one heavy job; extends D-S5). Pass: others' p95 <=2x. Feeds D1, M17 (waits on LDP-D1).

### SP5 findings (2026-10-06, synthetic, DuckDB, `NodeDictionaryBench` + `NodeDictionaryTest` in `inspecto-entity-store`)

Method: file-backed DuckDB, 12 cores, shared box (CPU 25-80% busy and 2-4 other surefire JVMs during the runs, so every number is noisy; each figure is min / median of 3 in one warm JVM; ranges below span the 2-3 separate runs made). Synthetic numbers: `+` prefix from a nested prefix table (80 two-digit countries, then operators, then ranges, lengths 2-6) plus a bijective suffix, 11-13 digits; each id spelled 1-3 ways (`+`, `00`, spaced/hyphenated) plus junk. D = dictionary size; the day = D/5 existing numbers plus 1% new. Measured at D = 10^6 and 10^7 only; **everything at 2x10^8-10^9 is a linear extrapolation, not a measurement.**

| Step (D=10^7, 10^3 prefixes unless noted) | min | median | Scaling 10^6 to 10^7 |
|---|---|---|---|
| (a) normalise 2x10^7 raw spellings to distinct E.164, every row then DISTINCT | 4.7-5.5 s | 5.1-5.7 s | linear (about 0.26 us/raw row on 12 cores) |
| (a) same, DISTINCT raw first then normalise | 6.4-8.2 s | 6.4-8.3 s | slower: with mostly unique raw strings the dedupe is pure cost |
| (b) LPM of 10^7 numbers, **A** cascade: one equi-join per prefix length | 2.4-2.8 s | 2.5-3.0 s | linear |
| (b) **C** one hash join on the unnested candidate-prefix list | 1.7-1.9 s | 1.8-2.1 s | linear |
| (b) **B** ASOF join on flattened disjoint prefix intervals (15-digit BIGINT key) | 1.8-2.2 s | 1.8-2.3 s | linear |
| (b) with 10^4 prefixes instead of 10^3 | A 3.3 s, C 2.2 s, B 2.0 s | A 3.6, C 2.3, B 2.1 | prefix table size barely matters |
| (c) find-new: day (2x10^6) ANTI JOIN dictionary (10^7), VARCHAR key / BIGINT key | 0.23-0.39 s / 0.18-0.25 s | 0.26-0.42 s / 0.19-0.26 s | linear in D (dictionary scan) |
| (c) enrich ONLY the new (2x10^4) | 65-86 ms | - | trivial |
| (c) whole daily upsert (normalise day 4x10^6 raw, find-new, enrich new, insert) | 3.4-4.2 s | 3.5-4.3 s | linear |
| **full re-derive** (LPM over all 10^7, rebuild the dictionary table) | 9.7-11.8 s | 10-13 s | 12-14x for 10x D: slightly super-linear; dominated by the table WRITE (LPM alone is 1.7-2.2 s) |

- **Recommended strategy.** **C** (candidate-prefix list, one hash join, `arg_max(label, prefix_length)`): fastest or tied everywhere, no precomputation, correct for short numbers. **B** (ASOF) ties it but needs the prefix table flattened to disjoint intervals once (a prefix change means a rebuild) and cannot match numbers shorter than the longest prefix (guard `length(d) >= prefix_length`, those must go to C). **A** is 25-40% slower; a fallback. All three agree row for row (disagreement count 0 at 10^6 and 10^7; the fast tests also pin nested prefixes, no-match and short numbers). The decisive design point is not the join but doing it on **new numbers only**: a 1% new share costs under 0.1 s here against about 10 s for a full re-derive.
- **Pass criterion (daily upsert <= 30 min at production scale): PASS on extrapolation, margin about 3x, NOT measured at scale.** Assumptions: D = 10^9, a day carries 4x10^8 raw number strings (2x10^8 CDR rows, two numbers each), 1% new. Normalise 4x10^8 raw at 0.3-1.1 us/row = 2-7 min; find-new scans the 10^9 dictionary ids at 0.2-0.4 s per 10^7 = 25-40 s (BIGINT key about 25% faster); enrich the new 1-2x10^6 = seconds; insert = seconds. Estimated 3-10 min total. Not covered: the hash table built on the day side (up to 2x10^8 ids, very roughly 5-10 GB at 40-50 bytes per id, not measured) and any spill when it does not fit; reading the dictionary from cold disk instead of the page cache; no PRIMARY KEY/ART index was used (uniqueness is by the anti-join, so a duplicate id is not rejected by the store).
- **Memory and size.** Dictionary on disk 11.9 bytes/number at 10^7 (VARCHAR id, prefix length, three label columns, DuckDB compression), about 12 GB at 10^9; the synthetic ids share long prefixes and compress better than real numbers, so treat it as a floor and plan 15-30 GB. Tracked DuckDB memory at the end of a 10^7 run was 1.3 GB including the work tables left alive (not a peak; peak was not sampled). The id also fits a BIGINT (E.164 is at most 15 digits): anti-join 25-40% faster, at the cost of the digit string being canonical (a national number with a leading 0 cannot use it).
- **Full re-derive at production scale (needed when the prefix table changes): about 18 min if linear (10.8 s x 100); plan 20-40 min**, memory-bound, since it rewrites a 10^9-row table. Same order as the daily budget: a prefix-table change is a batch window, not a daily event. An alternative that avoids it (store the matched prefix id, re-enrich only rows whose matched prefix changed) is not built or measured.
- **The sealed `e164` normaliser is not enrichment-ready (what is missing; nothing added, D-M9).** It does: trim, keep digits, keep a leading `+`, `00` becomes `+`, a bare `+` becomes empty. Pinned by `NodeDictionaryTest`: (1) a national number ("07700 900123") stays national (`07700900123`) and a short code ("112") stays as is, so one subscriber written nationally and internationally is TWO ids and only `+` ids are longest-prefix enrichable; there is no default country and no trunk-prefix strip; (2) "(+44) 7700 900123" loses its `+` (leading `(`; the shared fixture pins `(+1) 555 0100` to `15550100` already) and `+44(0)7700...` keeps the trunk 0; (3) no length check (E.164 is at most 15 digits). Three spellings (`+44 7700 900123`, `0044-7700-900123`, `+44 7700.900123`) do collapse to one id (tested). **Missing: a new sealed id (e.g. an `e164` variant with a per-space default country code and trunk prefix) and a D-M9 decision; and a third parity side, because the DuckDB macro used here (test helper, not product) matches the Java normaliser on the shared fixture plus 17 malformed inputs today, but any new normaliser must keep Java, TS and SQL equal.**
- **Platform change this implies (proposal, not built; size M, about 1 week):** one engine step (DuckDB SQL) that (1) normalises the day's distinct raw numbers with the sealed normaliser, (2) anti-joins a persistent dictionary Dataset (id plus enrichment columns) on the id, (3) enriches only the new ids by strategy C against a prefix Reference Dataset, (4) appends; plus a full re-derive mode for prefix-table changes. Persistence is open: measured only as a DuckDB table; a bucketed Parquet Dataset with an anti-join is the alternative, unmeasured (SP1 layout feeds it). **OPEN decision LDP-D-SP5 (operator): do national numbers get a per-space default country, as a new sealed normaliser id? Recommendation: yes; without it the dictionary splits one subscriber into two ids.**
- **What these numbers do NOT prove:** production-scale memory or spill; real number distributions (operator skew, real prefix nesting, real malformed rates); hash-join behaviour at a 2x10^8 build side; contention with the daily index build; cold-disk reads; timing on the production box (this is a noisy 12-core Windows dev box); or that DuckDB is the right home for a 10^9-row mutable dictionary (Parquet and the Postgres store were not tried). Two scale points (10^6, 10^7) scaling linearly give some confidence in the extrapolation, but it is two points, not a curve.
- **Reproduce.** `mvn -o test -pl inspecto-entity-store -am -Dtest=NodeDictionaryTest` (7 fast tests). Bench is gated: add `-Dtest=NodeDictionaryBench -Dbench.run=true -Dbench.n=10000000 -Dbench.prefixes=1000 -DargLine="--enable-native-access=ALL-UNNAMED -Xmx4g"` (optional `-Dbench.newPct`, `-Dbench.out=<file>`).

### Hard constraints carried forward

- ⛔ No decision answered by an implementer in passing; only the operator writes an Answer line (plus date).
- ⛔ Real customer data must not land before LDP-D11 is answered (R-06); development uses synthetic/profiled data only.
- ⛔ Never truncate silently: the gate refuses naming hop, count, suggestions; size checked BEFORE work; the refusal names the cap; lists cut with total/returned/limit stated; over-ceiling requests clamped and echoed (D-4 house style). A missing/failed day is a GAP, never answered as "no links" (M19).
- ⛔ Closed operation vocabulary: no twelfth op; barrier is a field on `expand`. Normaliser set sealed (D-M9): a profile normaliser is a new sealed id. Deterministic identity only, no fuzzy merge (D-M1/W06). One concept one store (D-M8): `list_member` is derived from the sidecar, never a second truth.
- ⛔ Standing refusals LA-06/LA-07 (viewport culling, Web Worker) stand; canvas caps unchanged; W07 no drawing > ~2K nodes.
- ⛔ Domain Profile rules: an entity type owned by exactly one profile (others `requires`); link kinds namespaced `<profile>.<kind>`; total entity types <= 64; any collision fails closed at load (422, never clamped); effective config = profile baseline overlaid by Space `link-analysis.toon` (Space wins); neutrality banned-word list DERIVED from shipped profile manifests, never hand-kept; no new loading machinery (TOON + existing ServiceLoader); per-Space enablement = a `profiles` list in the Space LA settings (does not exist today).
- ⚠ Index design (PROPOSED, spike-confirmed): reader must compute the bucket predicate or the layout does not prune (D-S1 lesson); one hop per server query; exclusion-list members dropped BEFORE counting toward degree/budget; both directions stored (mirror keyed by `b`); no adjacency-list rows; a day is visible only when its manifest is written, last, atomically; each build stage idempotent via partition replace; no new capability constants (scanner is literal-only) so sensitivity tiers = separately registered Datasets; read audit of sensitive tiers needs audit-chain type extension (BACKLOG `ASSURE-AUDIT-CHAIN-RESIDUALS-1` item 8); aggregate in Hive so ~10^8 not 10^9 rows move.
- ⚠ Same-cell co-location is weak evidence: hot-cell cap, minimum overlap and reported exclusions are part of the answer.
- ⚠ Unmeasured: >=2.5B/day on 8 cores; ingest 69-81K rows/s end-to-end (4x10^8 aggregated rows ~85-100 min before writer work); D21 vs daily-grain edges (passes 10^9 in ~2 days); presence probably the largest table (~10 GB/day, ~140 GB at 14 days); bronze ~80-120 GB/day if raw retained (~3 TB at 30 days).
- Won't (reopen trigger): W01 direct/hybrid Hive for traversal (freshness < a day); W02 distributed graph DB / >100M-node interactive graphs (D-S3 shows volume gap past 10^9 rows/hop); W03 customer-authored profiles (operator reverses I-11); W04 FX profile build (an FX design exists); W05 streaming ingest (freshness < hours); W06 fuzzy identity (decision reopens D-M1); W07 canvas > ~2K nodes (BACKLOG demand trigger).
- Could: C01 H3/`spatial` (compute keys in build); C02 external graph DB (DuckPGQ dropped by operator D-S2); C03 vectors (D13); C04 roll-ups; C05 comparison/timeline/burst/calendar (`LA-INVESTIGATION-OPS-DEFERRED-1`); C06 federation; C07 team Drafts; C08 FX normalisers (LEI/BIC/IBAN); C09 MNP-aware attribution.
- Should still open: S01 mobile-money kinds + move money measures out of `ValueMeasures`; S02 GPRS incidence (after F-09, privacy-gated); S03 scalable batch features (Must if LDP-D5 in scope); S04 freshness/coverage UI; S05 Dossier PDF + audited export; S06 public API; S07 Drafts/promotion (post first release); S08 neutrality guard; S09 hot-cell/rarity weighting; S10 per-query cost limits; S11 valid-time assertions.
- Risks (key): R-02 `PARTITION_BY` may not preserve `ORDER BY` (SP1 before schema freeze); R-03 CDR skew breaks a hop (barrier on by default); R-07 initial 30-90 day backfill (parallel per-day builds); R-09 fact-log replay uncached per read; R-11 single-node ceiling at 10^9/day (scale-out plan is escalation); R-12 D-3 unscheduled.
- Facts to collect (data engineers; queries in plan Appendix A): F-01 rows/day per record type; F-02 distinct A/B/(A,B); F-03 contacts per subscriber 30d; F-04 B-party formats; F-05 null shares + presence tuples; F-06 cell dimension; F-07 dial digit table; F-08 blacklist DB; F-09 GPRS feed; F-10 mobile money; F-11 MSISDN re-issue/MNP; F-12 Hive layout/export/Kerberos/when day D lands; F-13 reference host; F-14 retention/look-back/legal basis; F-15 investigators' need for call-level lines; F-16 timezone/day boundary/dup rate. Assumptions A-01..A-09 each closed by an F-id.
- Doc fixes owed (plan section 14): feasibility plan export count (~40 vs ~45); user manual vs OKF (label-hiding 300 nodes vs 20 links, layouts 11 vs 14, path ceiling 10M vs 10^6); PRODUCT_CAPABILITIES/COMPETITIVE_LANDSCAPE call LA-19 "not started" (built 2026-09-24); OKF ~L299 says exclusion/reveal client-side (both server ops with audit); GLOSSARY missing Dossier, Snapshot, Masking mode, Value Measure; EDITIONS SEC-08 / security.md masking edition text; `studio.md` section 6 Won't list conflicts with option D; retire `la-separation-execution-plan.md`; INDEX row for `la-separation-d1-design.md` still says "nothing built".
- Retirement: when a WP ships, distil as-built facts into the OKF concept, move open items to BACKLOG, `git mv` the plan to `docs/archived-documents/plans-archive/` when the last WP ships; update `docs/INDEX.md` in the same change.

## Daily ingestion into Link Analysis (CSV, Hive, database) - PLAN, nothing built (`LA-DAILY-INGEST-1`)

Grounded 2026-10-05 against the acquisition, ingestion and Job docs and `LaDetectJob`. Extends the data-preparation plan above (WP2 platform data prep, WP3 the index); it does not replace it. Stated need: the main XDR arrives in **daily partitions**, **dimension data is updated**, and the feeds come from CSV, Hive and a database on a daily schedule.

### Target flow (one pattern, three entry doors)

```
CSV / Parquet drop ─┐
Database (JDBC)  ───┼─> Collector ─> Pipeline (parse, type, quarantine) ─> daily-partitioned Dataset ─┐
Hive (see below)  ──┘                                                                                 ├─> Job `la.index.build` (append) ─> Link Analysis Index
Dimensions ─> Reference Dataset (replace / upsert), joined at read time or enriched at build ───────────┘
```

Each stage is an existing platform piece except the last arrow. Cadence is chained, not clocked: the Collector or a cron Job lands the day, the Pipeline commits it, and the `job.dataset.produced` Signal (an `on_signal` Job with a `when` guard, as in `spaces/demo/config/jobs/orders_summary_followup_job.toon`) starts the index build.

### What exists today (confirmed)

| Feed | Mechanism | Notes |
|---|---|---|
| CSV / files | Collector `local`, `sftp`, `ftp`, `ftps`, `s3`, `azure`, `gcs` + parsing frontend `delimited` (`csv_settings`) | Reference: `postmed_xdr` (`spaces/demo/config/postmed/`): pipe-delimited, `partitionKey: EVENT_DATE` (UTC date), poll discovery, `.processed` duplicate markers, quarantine. No cron or downstream Job in the sample. |
| Database | Collector `db` (`DbExportConnector`): runs a query over JDBC and lands the result as a CSV file that takes the normal file path | Incremental via `watermark_column`, `watermark_initial`, `watermark_type`; the query MUST contain `:watermark` or the connector refuses to start; watermark persisted per connection-profile id after the batch commits (in-flight fence). PostgreSQL driver ships; any JDBC driver on the classpath works. |
| Hive | **No dedicated support, no Kerberos code.** Only as Parquet files landed by something else (`parsing.frontend: parquet`) or, unconfirmed, through the `db` connector with a Hive JDBC driver | Matches `LDP-D6` recommendation (a): Hive-side aggregation, export to Parquet files, landing Collector. |
| Dedup / exactly-once | `AcquisitionLedger` (in-memory or durable `DbAcquisitionLedger`, `-Dacquire.ledger.backend=db`); NEW / DUPLICATE / CHANGED; `OnChange` IGNORE / REPROCESS (default) / ALERT / ARCHIVE_OLD_VERSION | Ingest is idempotent for already-committed Entries; an end-to-end exactly-once guarantee was NOT verified. |
| Retry, failure | `RetryPolicy` (exponential / fixed), per-source `CircuitBreaker`, `GapDetector` | `retries` on Jobs: which Job types honour it is unconfirmed. No timeout or cancellation on either engine. |
| Schedule | Job `.toon` (`cron`, `on_pipeline`, `on_signal` + `when`, `catch_up`); pipeline entry-node triggers (interval, cron, event, manual, default-poll) | `catch_up` replays exactly ONE missed fire and is Job-only. Dry run exists. |
| Dimensions | `reference: {load: replace or upsert or scd2, key[], refresh_seconds}` | ⚠ **`scd2` is accepted but NEVER honoured** (`transform.dim.scd2` is planned). So M08 (build-time as-of identity) has no engine under it. |

### Gaps (each becomes a task)

1. ⛔ **Nothing triggers an index build from a Job.** `POST /inv/index/builds` (gate `canBuildLinkIndex`, 202 + Location, visible to its starter or an administrator) has no Job Type or Pipeline node calling it. The precedent is `la.detect` (`LaDetectJob`, a `requires: [alerts]` grant, fails closed, dry run is a no-op, emits an aggregate-only Signal). A Job has no Subject, so the caller identity is the open question (`sweep:<id>` precedent, D-LD1).
2. ⚠ **Partition-replace and late-data semantics are unconfirmed.** Writes are `COPY ... PARTITION_BY` into staging then an atomic rename. A late or re-delivered file presumably lands in its event-date partition, but replace-vs-append of that partition is not stated. The Index treats a rewritten input file as removed input (409, full build), so this decides whether a late day costs an append or a full rebuild. Same as M03.
3. ⚠ **No first-class date-range backfill.** Nearest is the `reprocess` CLI and `RecordReplay` (inbox / backup directories). The 30-90 day initial backfill (R-07) needs a plan.
4. ⚠ **Partition columns type unevenly** (`year` BIGINT, `month` and `day` VARCHAR). The Index mapping uses `timeCol` plus `timeColZone`; keep the event-date column, not the partition columns, as the time column.
5. **Hive path is a decision, not a build** (`LDP-D6`).
6. ⚠ **Dimension change vs index staleness - VERIFIED 2026-10-06 (T6): a Reference change is NOT noticed.** Staleness fingerprints only the Dataset's own store files (`DatasetRelation.inputFiles`, never paths scraped from SQL) plus a hash of its relation SQL. A physicalRef Dataset cannot join anything; a virtual `sql` Dataset may name only its one source store (the join is refused); a `view`-backed Dataset can join a Reference, but a view has no enumerable files (`no-files:<sql hash>`), so `GET /inv/index` compares nothing on inputs, and the SQL text is unchanged when the Reference changes: `stale=false`, `fingerprintKnown=false`, no `plan` advice (a new FACT day is equally invisible for a view-backed Dataset). Test: `ReferenceChangeIndexStalenessTest` (`inspecto-geo-link`). Options if a Reference must be able to mark an index stale: (A) keep the rule below (nothing baked in, nothing to go stale) - recommended; (B) add the Reference's files to the fingerprint (needs the Dataset to DECLARE its Reference inputs, since they cannot be read from SQL; touches `DatasetProvider.inputFingerprint`, the manifest and `InputFingerprint`); (C) rebuild `full` on every Reference refresh (daily cost for no gain). Operator decision `D-ING6`.

### Rules of the plan

- ⛔ A missing or failed day is a GAP, never "no links" (M19): the per-day coverage must say so, and the freshness badge (S04) shows data-as-of.
- ⛔ Real customer data does not land before `LDP-D11` is answered (R-06); build and test on the synthetic `postmed_xdr`-style corpus only.
- The index holds XDR facts only (keys, kind, time, immutable attributes). **Mutable dimension attributes stay out of the edge index** and are joined at read time or enriched through the node dictionary (SP5). Bake an as-of value in only when history semantics demand it (M08, blocked on SCD2).
  - **Phase-1 supported rule (T6, verified):** a Reference Dataset is joined at READ time (`ReferenceReader.sqlFor`, as `transform.join` and enrichments do); an updated dimension is visible to the next read with no fact re-ingest and no index change. If an Index owner bakes a dimension attribute into the index anyway (a mapped column, or a view-backed Dataset that joins one), the platform will NOT report it stale when the Reference changes: they own the refresh and must run `la.index.build` mode `full` after each Reference load that matters. Reference load facts that bite: `replace` overwrites the SAME OUTPUT FILE (the output stem is the input file name), so a daily file named with its date leaves yesterday's file in place and the Reference reads BOTH days (duplicate keys, removed keys survive) - land the dimension under a stable file name, or use `upsert`; `upsert` keeps a key absent from the new file and removes a key only via `reference.delete {column, values}`; `refresh_seconds` is carried config, not a load behaviour. Tests: `ReferenceDailyFileLoadTest` (`inspecto-engine`).
- Daily job order: land, Pipeline commit, `job.dataset.produced`, then `la.index.build` mode `append`; `compact` when deltas approach 8 (a ninth delta is a 409); `full` for backfill or when `plan` reports removed or rewritten input. The Job reads `GET /inv/index` `plan` and `stale` and never forces a mode the server refuses.
- Failure paths alert through an `alert.evaluate` Job (the `stream_failure_watch` pattern); the build Job fails the Run closed when the index service is absent, like `la.detect`.

### Tasks (all under `LA-DAILY-INGEST-1`; ids local to this plan)

| # | Task | Verifies | Depends on |
|---|---|---|---|
| T1 | **DEFERRED (D-ING5):** Hive JDBC through the `db` connector and any Kerberos path. Replaced by: a **Hive-style Parquet** landing check (daily partition directories `date=YYYY-MM-DD/` or `year=/month=/day=`, `parsing.frontend: parquet`) on a synthetic corpus: the partition column is read as a column or taken from the path, the `VARCHAR` retype cost is measured, and a day lands inside the AC-01 budget (SP3 re-scoped) | Day lands; partition value reaches the Dataset typed correctly; retype cost stated | - **DONE 2026-10-06: measured, findings below** |
| T2 | LA daily-feed template: a Pipeline + schema for a daily XDR CSV feed (`partitionKey` = event date, UTC, quarantine, duplicate markers) and its Collector + cron or poll trigger, modelled on `postmed_xdr`. **BUILT 2026-10-06 (config only):** `spaces/_templates/la-daily-feed/`, spec in [OKF ingestion §3.10](../okf/capabilities/ingestion/ingestion.md); poll trigger only (no Collector cron exists); applies and validates under `ControlApiSpaceTemplateSeedGateTest`; a landed-day / bad-row-quarantine test is NOT written | Config test: a day lands in its partition; a bad row quarantines | - |
| T3 | **Database door, file-first (D-ING5):** the database owner exports a daily **CSV or Parquet file** (own scheduler, own query); we land it with the file Collectors and the same Pipeline template as T2. The in-product `db` Collector (`:watermark`) is a LATER option, not built into the first slice. Document the export contract per feed (file name pattern, one day per file, event-date column, a correction re-delivers the whole day). **CONTRACT WRITTEN 2026-10-06:** [OKF ingestion §3.10](../okf/capabilities/ingestion/ingestion.md); re-delivery behaviour = see T4 | A corrected day re-delivered replaces, never duplicates (needs T4) | T2 |
| T4 | Confirm (test) and, if needed, build deterministic partition replace and late-data behaviour (M03) | Day re-run gives identical counts and no stale file | - |

**T4 status (2026-10-06): CONFIRMED by characterization, DEFECT FOUND, fix NOT built (operator choice).** Tests: `inspecto-engine/.../inspector/PartitionReplaceCharacterizationTest` (6, real `ConsignmentIngestor` + `PartitionWriter`, DuckDB, temp dirs). The partition is NOT replaced as a unit: the written file is `<inbox-file-stem>_out.<ext>` (`FileNames.outputStem`), revealed by `REPLACE_EXISTING` rename, so the **file NAME** decides replace vs accumulate.

| Case | Observed | AC-02 / Index effect |
|---|---|---|
| (a) same-name day file re-run | Same output file overwritten; one file, identical row count, no staging leftover | Met. Index sees a rewritten input file: removed input, 409, full build |
| (b1) corrected, same name, same dates | Old rows replaced by new; one file | Met for the rows; Index rewritten-file path as (a) |
| (b2) corrected, same name, a date dropped (v1 spanned 04-03 and 04-04, v2 only 04-03) | 04-03 replaced, **04-04 file of v1 survives: stale file, superseded rows still served** | DEFECT. Replace is per written partition file, not per input file |
| (b3) corrected, re-delivered under a different name | Both files kept in the partition: **every row duplicated** | DEFECT. Nothing links the two names |
| (c) late file, earlier date | Only its own event-date partition is added; later days byte-identical and not rewritten | Pure added file: an Index append, not a rebuild |
| (d) two files, same event date | Two sibling files, rows add (append semantics) | Added file: append |

Not tested here: the ledger CHANGED classification and OnChange REPROCESS (acquisition layer; the engine test starts at the Consignment), and a Reference-versioned Dataset (`__v_<batchId>` names are batch-unique, appends by design). The object-store lane documents that repeat writes accumulate (`PartitionWriter.writeToObjectStore`).

Design options for a deterministic day replace (no code written; touches `ConsignmentIngestStrategy` write seam, ledger and Index staleness, more than a small contained fix):
1. **Replace-partition** (delete the target partition directory's files before reveal): simplest, fixes (b3) and two-files-same-day only if the day is delivered whole; BREAKS (d) where two files legitimately share a date, so it needs a declared "one file per partition" contract per feed.
2. **Per-file-version / ledger-driven delete**: on a CHANGED file, delete the outputs recorded for the previous version (lineage already maps source file to output files) before commit; fixes (b2) and same-name (b3); a rename still duplicates unless the feed contract fixes the name.
3. **Feed contract only** (T3): one day per file, stable name, correction re-delivers the same name; closes (b1) but not (b2)/(b3).
Recommendation: option 2 (lineage-driven delete on CHANGED, in the same commit as the new files), plus the T3 stable-name contract; option 1 only for feeds declared single-file-per-day. Operator decision: whether a renamed correction must be supported at all.
| T5 | Job Type `la.index.build` (+ Platform Service over `IndexBuildService`, runs as the delegated service principal (D-ING2), dry run no-op, fails closed, aggregate-only Signal), triggered on `job.dataset.produced` | Job test + real-HTTP: land a day, the index appends; stale / refused modes surface in words | T2 |
| T6 | **Reference data, file-first (D-ING5):** a daily-updated **Reference file** (CSV or Parquet) landed to a Reference Dataset with `load: replace` (or `upsert` with a key) and `refresh_seconds`; a test that a dimension change does not touch the index; SCD2 deferred (D-ING4) | Dimension update visible at read; index version unchanged | T3. **DONE 2026-10-06 (tests + findings, no product change):** `ReferenceDailyFileLoadTest` (6) + `ReferenceChangeIndexStalenessTest` (3); gap 6 answered (not noticed), see Gaps and Rules |
| T5 | **BUILT 2026-10-06 (as-built note below; needs the independent `verification` PASS before merge).** Job Type `la.index.build` (+ Platform Service `link-index` over `IndexBuildService`, runs as the delegated service principal (D-ING2), dry run no-op, fails closed, aggregate-only Signal), triggered on `job.dataset.produced` | Job test + real-HTTP: land a day, the index appends; stale / refused modes surface in words | T2 |
| T6 | **Reference data, file-first (D-ING5):** a daily-updated **Reference file** (CSV or Parquet) landed to a Reference Dataset with `load: replace` (or `upsert` with a key) and `refresh_seconds`; a test that a dimension change does not touch the index; SCD2 deferred (D-ING4) | Dimension update visible at read; index version unchanged | T3 |
| T7 | Backfill: a date-range replay that builds days in parallel and lands them as one index (R-07) | 30-day backfill reproduces a day-by-day run | T4, T5 |
| T8 | Freshness and failure: per-day coverage manifest, gap alert, data-as-of badge | A dropped day shows as a visible gap | T5, WP3 |
| T9 | End-to-end acceptance run on a synthetic corpus, three doors, seven consecutive days plus one late file and one dimension change | AC-01 and AC-02; no stale file; gap reported | T1-T8 |

### T1 findings (2026-10-06, synthetic corpus, `HiveParquetDailyLandingTest` in `inspecto-etl`)

Method: the engine's own stage chain on a generated Hive-layout corpus (`createRawInputView` then `DataTransformer.materialize` then `PartitionWriter.write`, as `NativeCsvStreamingEngine.streamUnit` runs it). The engine reads each file at the path the Collector discovered (archives only are unpacked to a work directory), so the directory names survive to the read. Not run through a live Collector or the full engine (assumed from the code, not exercised). Nothing blocks Hive-style input; no feature is proposed.

- **(a) Partition value.** `date=YYYY-MM-DD/` and `year=/month=/day=` directory levels are readable as columns: a schema field whose selector is the level name (`date`, `year`, `month`) gets the value from the PATH, even when the file does not contain that column. With `hive_partitioning: true` set it is explicit; DuckDB also auto-detects `key=value` levels without it (observed, so the option is not strictly required, but set it anyway to not depend on the DuckDB version). Type: the raw lane always casts to `VARCHAR`; `date=2026-03-05` lands `2026-03-05` (types cleanly to `DATE` downstream), the zero padding the directory wrote is preserved (`month=03` is `03`, `month=7` is `7`), so an unpadded layout will not sort or parse lexically. If a file ALSO carries a column of the same name, the directory value silently wins (no error, no warning). Keep the event-date column, not the partition columns, as the time column (as gap 4 says).
- **(b) Retype cost.** `read_parquet` has no all-VARCHAR option, so every column is `CAST(... AS VARCHAR)` at read and cast back by the mapping. Measured on a shared 12-core Windows box with four other lanes running (noisy), 3 data columns plus the date, 4 part files per day, best of 3: 2M rows typed read-to-table 0.85 s versus VARCHAR lane 2.92 s (+2.1 s); 5M rows 1.43 s versus 3.61 s (+2.2 s). So the retype roughly doubles to triples the parse stage, about 0.4 to 1 microsecond per row. It does NOT prove: cost on wide rows (real XDR has far more columns, and the cost scales with column count, mostly the string columns), cost at 10^8+ rows (the whole day is materialised in one DuckDB database, so memory and spill are untested), or a cold disk.
- **(d) Day budget.** End to end (view, transform, partitioned write, 4 files) 5.2 s for 2M rows and 5.9 s for 5M rows, 0.39 to 0.85 million rows/s including JIT warm-up. A straight extrapolation to the SP3 size of 2-4x10^8 aggregated rows is about 8 to 17 minutes against the AC-01 day-build budget of 4 h (landing is only part of it). Treat this as "no sign of a problem on a narrow synthetic row", NOT as proof for 10^9 rows/day, which stays unmeasured.
- **(c) Re-delivery, late, new day (recorded for T4, nothing fixed).** Output file name derives from the INPUT file name (`<stem>_out.parquet`) inside the event-date partition. A new day adds a partition. A late earlier-date file lands in its own earlier partition. The SAME day re-delivered under the SAME file name replaces the partition file (REPLACE_EXISTING on reveal: 100 then 150 rows reads back as 150). The same day re-delivered under a DIFFERENT file name (a Hive re-export names its parts differently, `part-00001` for `part-00000`) accumulates a second file and DOUBLES the day (150 + 150 = 300). So correctness of a corrected day depends on file naming, not on the platform: T3's export contract must pin one stable file name per day, or T4 must build replace-by-partition.
- **Reproduce.** `mvn -o test -pl inspecto-etl -am -Dtest=HiveParquetDailyLandingTest` (6 tests; `-am` because the installed sibling jars are stale). The measurement is gated: add `-Dbench.run=true -Dbench.rows=2000000 -Dbench.parts=4`.
### T5 as built (`la.index.build`, 2026-10-06) - assistant-decided, pending operator confirmation

- **Seam.** The `alerts` pattern: engine interface `LinkIndexAccess` (Platform Service `link-index`, `com.gamma.linkindex`) + SPI `LinkIndexBuilder`; the `inspecto-geo-link` bridge `ScheduledLinkIndexBuilder` (ServiceLoader) binds it to `ScheduledIndexBuild` in `inspecto-la-api`. The engine names no `la-*` class; `CollectorService` registers the service and resolves the write root exactly as the Investigation probe does. **No new route**, so none of the four endpoint gates applies. `DryRunServices` substitutes a no-build stand-in.
- **Principal and authority (the choice).** `index-build:<job name>`, holding exactly one capability, `canBuildLinkIndex`, a CONSTANT (a configuration can never grant more). Its authority is the `owner` user id **recorded in the Job's own parameters** (bound when an operator configures the Job), re-decided on EVERY run by `ScheduledIndexBuild.decide`: the owner, as a user id, must still be able to view the Dataset (`ComponentAccess.canViewAs`: owner id and `user` shares; a `role` share is refused as `ROLE_SHARE_ONLY`, as in D-LD2). Codes: `NO_OWNER`, `DATASET_GONE`, `DATASET_NOT_SHARED`, `ROLE_SHARE_ONLY`, `MAPPING_INVALID`, `UNDECIDABLE`. Audit: `LINK_INDEX_SCHEDULED_RUN` (actor `index-build:<job>`, actor type `service`) for every attempt plus the standard `LINK_INDEX_BUILD_*` events. Chosen over a new enable route/record because it needs no new surface; the "enable" is writing the Job file (whoever may author Jobs therefore chooses the owner id - a residual, see below).
- **Config shape.** Job parameters: `dataset`, `source_col`, `target_col`, `owner` (required); `kind_col`, `time_col`, `time_col_zone`, `weight_col`, `attr_cols` (a COMMA-SEPARATED STRING, e.g. `cell, lac`: a TOON array becomes `[a, b]` and fails closed as `MAPPING_INVALID`); `allow_full` (default false); `timeout_seconds` (default 3600). Trigger as in `orders_summary_followup_job.toon` (`on_signal: job.dataset.produced` + `when`).
- **Mode.** Reads the same `IndexPlan.classify` advice as `GET /inv/index` and runs ONLY that: `none` is `UP_TO_DATE`; `append`; `compact` (a ninth delta is a 409, so at the cap it compacts and then appends the landed day in the same run); `full` only with `allow_full` (a first build and a rewritten or removed input are both `full`), otherwise `REFUSED FULL_NOT_ALLOWED`. It goes through the SAME per-Space `IndexBuildService` as the HTTP route (`IndexRoutes.scheduledService`), so a server refusal (409 duplicate / not applicable, 422 over budget, 503 busy) is one recorded `REFUSED` (`BUILD_IN_PROGRESS`, `NOT_APPLICABLE`, `OVER_BUDGET`, `BUSY`), never a retry. Anything but `BUILT` / `UP_TO_DATE` fails the Run; a build still running at `timeout_seconds` fails it as `RUNNING`.
- **Residuals.** The control API must be up in the process (else `NO_INDEX_SERVICE`); the PDP and the Dataset row-filter question (index design Decision 2) are not re-asked, as for the index routes; the owner's capability is not re-checked (only Dataset visibility), unlike a request; `.toon` Job parsing of the new parameters and a live `on_signal` end-to-end run are untested (the tests drive the Job and the bridge separately).
- **OPEN, operator decision (verifier LOW-MEDIUM, not fixed): owner spoofing.** Whoever can author a Job can type a restricted Dataset owner's id as `owner`, so the build runs over data the author cannot view. Smallest mitigations: stamp `owner` from the saving Subject unless they hold `canConfigureAccess`, or add a RunAuthority refusal like `MAINT-TASK-AUTHORITY-1`. Until decided, restrict who may author `la.index.build` Jobs.
- **Messages.** The server's `Refused` messages carried into the Run message were checked: they hold only counts, plan reason codes and a build run id, never a column value or entity id.
- **Tests added after verification.** Geo-link `ScheduledIndexBuildTest`: a second run during a live build is `BUILD_IN_PROGRESS`, and a build still running at the timeout is `RUNNING` (which fails the Run; `LaIndexBuildJobTest` covers `RUNNING` -> failed).
- **Tests.** `LaIndexBuildJobTest` (9), `ScheduledIndexBuildTest` in `inspecto-la-api` (5) and in `inspecto-geo-link` (5, real control plane, real Parquet: first build refused then built, a landed day appends, rewritten input refused, compact-then-append at the cap, authority re-decided and audited).

### Decisions owed (operator)

- ✅ **D-ING1 ANSWERED 2026-10-05 (operator): data leaves Hive in daily partitions** — one day per export, landed by a Collector (so it matches the existing daily-partition model and the index `append` per day). This is also `LDP-D6` for the ingest half; the file format (Parquet recommended) and where record-level evidence (M20) comes from stay with `LDP-D6`.
- ✅ **D-ING2 ANSWERED 2026-10-05 (operator): go with the recommendation** — a scheduled `la.index.build` runs as a delegated service principal bound at configuration time and re-checked at each run (the D-LD1 / option A model), holding only `canBuildLinkIndex`.
- ✅ **D-ING3 ANSWERED 2026-10-05 (operator): configured at setup time** — watermark column, type and initial value are per-feed configuration (`watermark_column`, `watermark_type`, `watermark_initial` in the connection profile), never hard-coded. T3 therefore documents the setup choice (event time vs `updated_at`, and what a corrected row needs) instead of waiting on a decision.
- ✅ **D-ING4 ANSWERED 2026-10-05 (operator delegated: "go with the path beneficial"; the assistant chose, so it is pending the operator's confirmation like D-LD2…D-LD18): DEFER SCD2.** Dimensions are Reference Datasets with `replace` / `upsert` and `refresh_seconds`, joined at read time; the index holds XDR facts only. Reasons: `scd2` is accepted but never honoured, so building it is a data-plane project that blocks nothing the daily feed needs; read-time joins make a dimension update free of index rebuilds; as-of history (M08) stays a later, demand-gated item and reopens only if analysts need "who owned this number on day D".
- ✅ **D-ING5 ANSWERED 2026-10-05 (operator): files first; Hive JDBC deferred.** Phase 1 takes three inputs, all as FILES through the existing file Collectors and the parquet / delimited frontends: (a) **Hive-style Parquet** in daily partitions, (b) a **daily CSV or Parquet export** from the database, (c) a **daily-updated Reference data file**. Deferred, not dropped: Hive JDBC and Kerberos, and the in-product `db` Collector (`:watermark`); both reopen when a feed cannot export files. Consequence: the flow's three doors are one door (a file lands), so T2 (the feed template) carries every phase-1 feed, T1 and T3 shrink to landing checks and an export contract, and D-ING3's watermark setup applies only if the `db` Collector is later used (a file feed instead states its event-date column and replace rule).
- Unmeasured: ingest rate at 10^9 rows/day, the S3 incremental mode, Hive JDBC feasibility.

## Investigation store (`LA-INVESTIGATION-STORE-DESIGN-1`)

Drawn from: `docs/archived-documents/plans-archive/investigation-store-design.md`. S0-S7 are all BUILT. (the design header said "S7 next", but design 13.6 and OKF L1883 record S7 closed on BOTH backends 2026-10-05 (FS 4/4 after a fix; PG 18.6 4/4, no race found). Nothing is "next" except the demand-gated items below.

- Open decisions: none. D-IS1..D-IS12 answered 2026-10-04; the three S6 questions closed 2026-10-05 (no per-set cap now; no boot-time refusal; `investigations.backend` stays process-global).
- What S7 proved (done): two real JVMs, own store each, on one directory / one PG schema: append chain (dense, prefix-hash intact), `decide` compare-and-set (one winner), mask key (one key), promote (one wins, typed refusal for the loser).
- Demand-gated / owed: D-IS6 chain hash (if O(n) prefix read hurts; FS 16.8 ms p50 at 2,000 steps); D-IS11 move pin ledger `pins.json` to Postgres (when multi-pod index serving is scheduled); D-IS12 (b) content-addressed sets + per-Investigation set budget (trigger `LA-DRAFT-PROMOTE-COST-1`; 3 GB for 2,000 steps x 10,000 entities); per-set size limit (D-IS2) not built.
- ⛔ Operator-run precondition mutation checks still owed (harness refuses agent edits of guards): FS `expectedVersion` mutant and PG mutants (1)-(14) of design 13.4/13.5.
- Not in the two-JVM harness: creates/forks, references, case-link / Alert Rule binding (last-writer-wins by design), `createDraft` Space-wide cap (two FS pods can overshoot), "sweep vs append" and "promote vs rebase" pairings.
- ⛔ Constraints: log lines and sets are `text`, NEVER `jsonb` (breaks sealed hashes silently); no FS fallback when `db` is selected (503); no FS-to-Postgres importer (a switched Space starts empty); one backend per Space; PG reads/writes use the primary (replica lag out of scope).

## Link Analysis: Entity model (LA-17) and live detection (LA-LIVE-DETECTION-1) - what is still open

Drawn from: `docs/archived-documents/plans-archive/link-analysis-entity-model-design.md`, `docs/archived-documents/plans-archive/la-live-detection-design.md`. Built behaviour lives in `docs/okf/frontend/features/link-analysis.md` and `docs/okf/backend/control-plane/entity-lists.md`.

### 1. Entity model (LA-17)

Status: slice 1 (Entity Types + Entity Lists), the typed projection ids (D-M6/D-M11, 2026-09-27) and slice 2 (assertions, fold, routes, mapping-Dataset import, the `resolve` op, merged traversal; 2026-09-27 to 2026-09-30) are all built. The design's own header still says "steps 1-5 done, step 6 panel done" and is stale on this.

| Id | Decision | Status |
|---|---|---|
| D-M1 | Resolution is deterministic only: identifiers join only by an asserted fact; no fuzzy or similarity merge, not even as a suggestion | Operator-decided 2026-09-26; built |
| D-M2 | Append-only Identity Fact log per Space; the registry is a fold; a Dossier can pin a log position | Operator-decided; built |
| D-M3 | Entity Types are configurable per Space, seeded with the 9-type set | Operator-decided; built |
| D-M4 | First slice = Entity Types + Entity Lists; resolution is slice 2 | Operator-decided; built |
| D-M5 | Name is **Entity List** (not Reference List) | Operator-decided; built |
| D-M6 | Typed id `<type>:<key>`; no back-compat for stored `entity:<value>` ids | Operator-decided; built 2026-09-27 |
| D-M7 | Types live in the per-Space `link-analysis.toon`, not a registry kind (not shareable via the Exchange, accepted) | Operator-decided; built |
| D-M8 | Keep the Identity Fact log, align D-P10 (purposes `allow/block/watch/exclusion`); ranges, expiry, sidecar, four-eyes = assurance WS-12 | Operator-decided; built (shipped 2026-09-28 as `ASSURE-ENTITY-LISTS-1`) |

Slice 2 decisions (design section 8), all operator-decided 2026-09-27 and built: D-M9 record the normaliser per list (`LA17-NORMALISER-CHANGE-1` closed), D-M10 analyst assertions + fold first and mapping import later, D-M11 projection ids before resolution.

**Still open (nothing in LA-17 blocks):**
- Member browsing of an Entity List and the `at=<seq>` read in the SPA (design step 6 "Still owed").
- Design step 7: a `verification` subagent PASS, then distill into OKF and `git mv` the plan to `plans-archive/` (this consolidation).
- Range / CIDR entries do not take part in `excludeBy` / `seedBy` (exact keys only). Decided 2026-10-04: DEFER until a customer asks; it changes the sealed-log format and replay hash and needs its own design (BACKLOG `ASSURE-ENTITY-LISTS-RESIDUALS-1` (1)). No SPA authoring for ranges or expiry (2).
- Not built: a later import never retracts a pair that left the mapping Dataset; `replay {reread}` does not drift-check a `seedBy` read.
- Known limits kept on purpose: a list created before D-M9 folds to `default` (no compat path); `+` in a query parameter is fixed only in `EntityIdentityRoutes.rawQuery` (other routes have the same defect); a deleted LAST fact is undetectable from the log alone (only a cited head hash catches it); the Geo co-location dialog stays untyped; an unknown column classification fails OPEN while an unknown seed `entityType` fails CLOSED.
- LA-19 masking-replacement question (owed to the operator per the memory index): NOT stated in either design doc. **Reconfirm with the operator what the question is before writing it here.** Related in the design: list-member tokens use the per-Space `mask.key`, so they differ from an Investigation's token for the same value; revisit only if analysts need to match a masked member to a masked node.

**What a further resolution cut would need** (only if the operator asks): decide retraction of pairs that left a mapping Dataset; enrichment attributes as filters (LA-18 value measures were blocked on this); keep the design gate "a merge must never hide which identifier matched" (already honoured: every group carries its joining assertion seqs).

### 2. Live detection (LA-LIVE-DETECTION-1)

Status: LD-1 to LD-7 are all built (2026-10-04 to 2026-10-05). The design header still says "LD-4 to LD-6 not started" and is stale.

| Id | Decision (one line) | Status |
|---|---|---|
| D-LD1 | Option A: service principal `sweep:<id>` holding no capability; authority recorded at enable and re-decided every sweep; option C (refuse what cannot be decided) is the floor | Operator-decided 2026-10-04 |
| D-LD2 | Refuse a Dataset whose only grant to the owner is a role share (`ROLE_SHARE_ONLY`, at enable 422 and every sweep) | Assistant-decided, pending operator confirmation |
| D-LD3 | PDP asked with a synthetic Subject from the owner's id and grants recorded at enable (tagged `sweepPrincipal`); a throwing decider is a refusal | Assistant-decided, pending operator confirmation |
| D-LD4 | Evaluate in place; persist nothing unless the Alert fires | Assistant-decided, pending operator confirmation |
| D-LD5 | Owner loses lead role: pause (`NOT_LEAD`), never transfer | Assistant-decided, pending operator confirmation |
| D-LD6 | Template sharing: stay owner-only | Assistant-decided, pending operator confirmation |
| D-LD7 | Professional-and-above only (optional `inspecto-geo-link`); cadence = the `la.detect` Job's own cron, no new minimum | Assistant-decided, pending operator confirmation |
| D-LD8 | `canConfigureAccess` is never honoured by a sweep | Assistant-decided, pending operator confirmation |
| D-LD9 | Only masking TIGHTENING stops a sweep; loosening does not (operator asked to refuse on tightening) | Assistant-decided, pending operator confirmation |
| D-LD10 | Only the owner may enable; only a value-measure rule; a rule edited since binding is refused | Assistant-decided, pending operator confirmation |
| D-LD11 | A value-measure rule is not evaluated until standing detection is enabled (breaking change, free per operator 2026-09-20) | Assistant-decided, pending operator confirmation |
| D-LD12 | Principal is named after the Investigation (`sweep:<investigation id>`), not a template id | Assistant-decided, pending operator confirmation |
| D-LD13 | `la.detect` is a clock over the existing evaluation (`AlertAccess.evaluateInvestigationRules()`), Investigation-bound rules only | Assistant-decided, pending operator confirmation |
| D-LD14 | The Job fails the Run closed when `alerts` or the Link Analysis module is absent; a dry run evaluates nothing | Assistant-decided, pending operator confirmation |
| D-LD15 | Disable is open to anyone who may bind rules and open the Investigation; idempotent | Assistant-decided, pending operator confirmation |
| D-LD16 | Editing a bound rule in place drops standing detection; owner must re-enable (name immutable) | Assistant-decided, pending operator confirmation |
| D-LD17 | Template list is owner-only and summary-only (counts) | Assistant-decided, pending operator confirmation |
| D-LD18 | Status read-back = list of the Investigation's bound rules (`GET .../alert-rules`), not a per-rule route; SPA edit offers severity (and a Working Set rule's threshold) only | Assistant-decided, pending operator confirmation |

Operator confirmation of D-LD2..D-LD18 is itself the open item (BACKLOG `LA-LIVE-DETECTION-1`).

Hard invariants: narrow only (a refusal is EMPTY, never a value); aggregate output only (no entity id in any Alert, event, log line or Signal); masking basis may only tighten between bind and sweep; the sweep never approves anything (it cannot be the second person); one audit event per enable, sweep outcome and refusal (best-effort, no ids).

**Known residual limits:**
- The synthetic Subject's grants are the enable-time snapshot, cannot refresh off a request and carry no role names (guideline 13), so a PDP DENY keyed on `subject.roles` cannot be reproduced.
- A role-share-only Dataset cannot run standing detection (D-LD2); the fix is a `user` share to the owner.
- Owner `canManageIncidents` / `canAuthorAlertRules` cannot be re-checked off a request (token-time); only lead membership can.
- A sealed-Working-Set rule is not standing detection: it moves when the analyst's log moves, never when the Dataset grows; only value-measure rules can be enabled.
- Rules bound before D-LD11 stopped sweeping until their owner enables them.

**The one still-unbuilt item: template sharing (D-LD6).** Templates are write-once and owner-only with no sharing; sharing via the registry envelope was declined for now. It needs an operator decision and a decision on how a shared template interacts with owner-only `instantiate` and each instantiator's own R3 gate.
