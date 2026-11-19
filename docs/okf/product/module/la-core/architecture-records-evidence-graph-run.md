---
type: Reference
title: Link Analysis — as-built reference and design records — Evidence, the op log and Graph Run
description: Part 3 of 6 of Link Analysis — as-built reference and design records: Graph Run
tags: [module, la-core, link-analysis, architecture]
timestamp: 2026-11-15T00:00:00Z
---

# Link Analysis — as-built reference and design records — Evidence, the op log and Graph Run

Part 3 of 6 of [Link Analysis — as-built reference and design records](architecture-records.md) · previous: [Grounded limits and consequences](architecture-records-grounded-limits.md) · next: [Studio UI record and the link index](architecture-records-studio-index.md).

## Grounded limits and consequences (continued)

### Evidence: snapshots, the op log and the Dossier

* **An evidence snapshot is SEALED by the server** (LA-03, 2026-09-23): `POST /inv/snapshots` writes one immutable file
  per id (409 on re-POST); `POST /inv/snapshots/attach` appends to a separate log and never reopens the sealed record
  (that would invalidate `manifestHash`). ⛔ The client updates only after the server confirms; a refused seal leaves the
  dialog open. ⚠ A failed ATTACHMENT does not fail the save. 🔴 A DI change is invisible to type-checking.
  **A snapshot captures what is on screen** (A1, 2026-09-30): `canvasData()` — the Working Set while an Investigation
  draws it, else the query graph; a Working Set snapshot's origin is `{sourceId: 'investigation', dataset, query:
  {investigationId}}`. The SPA's `manifestHash` is SHA-256 (2026-09-28: Web Crypto over canonical JSON, `sha256:<hex>`,
  the server's recipe, pinned in `graph-snapshot.spec.ts`); ⚠ `snapshotGraph`/`verifySnapshot` are async and
  `crypto.subtle` needs a secure context — elsewhere the dialog refuses to seal (never a weaker digest). A snapshot sealed
  while an Investigation is open carries its `investigationId` (outside `manifestHash`).
* **An Investigation is a server-side append-only op log** (LA-10, 2026-09-23; model and hashing:
  [architecture-la-core.md](architecture-la-core.md) §3.1). `POST /inv/investigations` binds a Dataset + mapping; `/{id}/ops` answers the
  Working Set **delta** + `truncated`; `/{id}/undo` is a recorded log edit (state after = state before the undone op);
  `/{id}/replay` re-evaluates; `GET /{id}/log` renders plain-language lines. ⚠ Replay uses PREFIX semantics (position k
  honours only undos at or before k). ⛔ **Nothing is pinned** (D-E3): `datasetVersion` is `null`; each `expand` SEALS its
  rows with a SHA-256 fingerprint, and `replay {reread:true}` reports drift per step. ⛔ **Re-ordering forks** (D-E4):
  `/{id}/reorder` creates a new Investigation naming its parent; the original stays byte-identical. Writes need
  `canManageIncidents` (Replay and Re-read stay enabled read-only); access is the member gate below.
* **Delete** (`LA-INVESTIGATION-DELETE-1`, 2026-10-27; `DELETE /inv/investigations/{id}`): a lead with
  `canManageIncidents` (else 404 / 403). **409** while linked to an OPEN Case or holding a pending approval. Removes header,
  log, sets, Drafts, members, references, alert-rule bindings, pending records, Case link and embed grants (moved to a
  tombstone, then removed; id freed). Audit `LINK_INVESTIGATION_DELETED` after the removal. ⚠ Filesystem only: Postgres
  answers **503** (append-only triggers; `LA-INVESTIGATION-DELETE-PG-1`). An exported Dossier stays; a bound Alert Rule
  stays registered (sweeps log "no investigation").
* **Rename / restate purpose** (`LA-INVESTIGATION-RENAME-1`, 2026-10-29; `PATCH /inv/investigations/{id}` `{title?,
  purpose?}`): pen fields in Settings ▸ General and the Act panel header. Lead + `canManageIncidents` (non-lead member
  403, stranger 404). Title non-blank ≤ 200; purpose non-blank within `input.purpose_length` (422), the same cap in every
  form. The sealed header is never rewritten: the edit is a `details` record (`investigation-details.json`, or the
  `details` column on Postgres; it also keeps the creator's display name, T-186). **The Dossier does not show the edit**
  (operator-confirmed 2026-11-01): an unsealed "edited" field beside a sealed manifest would let a reader take it for
  evidence. Concurrency (`LA-INVESTIGATION-EDIT-HARDENING-1`): one read-modify-write per Investigation under the store
  lock (monitor + `.store.lock`; `FOR NO KEY UPDATE` on Postgres), so edits never undo each other; an edit racing a delete
  answers 404. A corrupt details file is ignored with a WARN. Audit `LINK_INVESTIGATION_EDITED` with before / after, title
  and purpose masked per `maskingMode`.
* **Case-team sharing** (LA-24, D-U10, 2026-09-30; `InvestigationCaseRoutes`): optional `caseRef` (on create, `PUT`/
  `DELETE …/case`; owner-only, `canManageIncidents`, a visible Case, a closed one 409), stored in `case-link.json` OUTSIDE
  the sealed header. The Case's owner or assignee gets **read-only** access (log, Working Set, Dossier + verify, measures,
  `GET …/case` `access: case-member`), decided live on every read in `openForRead`. ⛔ Membership is a grant, **not a
  policy ALLOW**: R3 and the PDP still narrow. ⛔ Independence: geo-link names no `inspecto-ops` type (core
  `ObjectAccess.summary`); without the module the link is `verified:false` and grants nothing. Fail closed: closed /
  deleted Case or a member who leaves ends access; a fork or template instance does not inherit the link.
* **An `expand` is one hop-ladder rung; a `window` op sets the time window** (LA-13, 2026-09-23; `InvestigationTime`).
  Rung fields: `direction` (either · out · in · reciprocal) · `linkKinds` · `window` (`inherit` default · `full` · override)
  · `minEvents` · `minDistinctDays` · `candidateDegreeMin/Max` (over the WINDOWED graph of the whole Dataset) · `maxFanOut`
  (strongest first, `fanOutCapped`, never `truncated`) · `budget` (row cap; ⚠ `limit` is refused 422). Sealed as
  `read.query` so `reread` re-runs it exactly. ⛔ **Timezone contract:** the Investigation binds `timeCol`; a naive
  `TIMESTAMP` is wall clock in `timeColZone` (default `UTC`, recorded); a `TIMESTAMPTZ` refuses a zone; `from`/`to` carry
  an offset (half-open, epoch ms); a `slot` (crosses midnight; start inclusive) and a `days` mask need an IANA `timezone`
  and test the local day the EVENT fell on. `window` re-filters nothing already admitted. Templates carry the rung; a
  `window` becomes a `kind: "window"` parameter.
  **Calendar exclusions** (2026-10-05): `exclude` list of `"YYYY-MM-DD"` | `{date}` | `{from, to}` (inclusive, optional
  `name` 1–80), ≤ 366, canonicalised and sorted so the same calendar seals the same bytes; local calendar day in the
  window's `timezone` (required). One implementation, `InvestigationTime.predicates` (expand, `window`, `compare`, the
  Dossier rung read); `GET …/coverage` treats an excluded day as not expected. **Recurring:** `{rule:"yearly", month,
  day}`, `{rule:"nthWeekday", month, weekday, nth}` (1–5; a missing 5th never matches; yearly `02-29` leap years only),
  `{rule:"lastWeekday", month, weekday}` (2026-10-12). **The RULE is sealed, not its expansion** (a per-day test, SQL
  `month/day/isodow` arithmetic identical to `InvestigationTime.matches`); order dates then rules; caps 366 dates + 32
  rules; unknown rule / bad date / extra key 422, never logged; a window with no rules seals byte-identically
  (`InvestigationTimeRecurringTest`). SPA syntax `2026-12-25, Winter break=2026-12-24..2026-12-26`, `Xmas=*-12-25`,
  `*-11-THU#4`, `*-05-MON#L` (the `L` form server-side only; a11y spec in `investigation-window-op.component.spec.ts`). Not
  built: Easter-style and lunar holidays. ❌ **Derived Collector roster NOT built** (pinned by
  `PipelineStoreMappingPinTest`): only 1 of 89 Pipeline configs declares an `output_table`, so a roster would list ~every
  Pipeline and raise false alarms.
  **SPA:** the *Expand* dialog's *Advanced* fold (`link-analysis-expand.component`) sends only the fields set: `budget`,
  `direction`, rung `window`, `linkKinds` (comma list ≤ 100; server 422s without a link-kind column), `minEvents`,
  `minDistinctDays`, `candidateDegreeMin/Max` (min ≤ max), `maxFanOut`. The *Time window* form
  (`investigation-window-op.component`) and the rung override share `investigation-window-fields.component`; bounds in
  `investigation-rung-form.ts`; a server 422 renders verbatim. (Current expand budget bounds: [architecture-la-core.md](architecture-la-core.md) §9.)
* **The Investigation tab drives it** (LA-10 SPA, 2026-09-23; `link-analysis-investigation.component` over the
  pane-provided `InvestigationSessionStore`). Start binds Dataset + columns; ⚠ the query's `filter` is not sent (the
  create route takes none). While one is open a canvas click picks the entity and the canvas draws the **Working Set**.
  Ops send RAW spellings (the Working Set is folded through `projectTriples`/`entityId()`, the server's untrimmed value
  added to `spellings`). Exclude needs a reason; undo, replay (re-read → drift table), re-order (fork explanation) wired;
  truncation shown from the log. ⚠ `/ops` and `/undo` answer counts, so the store re-reads `GET /log` + `POST /replay`
  after each mutation (writing a `link.investigation.replayed` event per step). Listing:
  [Closed-plan record](architecture-records-closed-plans.md#listing-and-link-notes).
* **The Dossier and its chain of custody** (LA-12, 2026-09-23; `DossierRoutes`, `GraphDossierBuilder`):
  `GET /inv/investigations/{id}/dossier` (`?at=`, `?snapshots=`, `?format=json|steps|method|html`): summary, topology,
  ledger, centrality / risk tables, **negative space**, integrity report and three renderings (re-runnable JSON, numbered
  steps, a method statement citing the custody hash). Every exclusion (id, step, author, reason) appears in all three
  (G-E10). **`format=html`** (LA-DOSSIER-OUTPUT-1, 2026-10-03; `DossierHtml`): one self-contained page (inline CSS +
  `@media print`, no scripts / links / fetch → browser *Save as PDF*), rendering the same already-masked map
  (`maskedDossier`); ⛔ every value through `DossierHtml.esc`; `Content-Security-Policy: default-src 'none'; style-src
  'unsafe-inline'`; pinned by `ControlApiDossierTest`. No server PDF (no PDF library). The **manifest** hashes raw stored
  bytes of `header.json`, each `log.jsonl#<step>`, each `sets/<step>.json`, each included snapshot, plus canonical
  entities, links, exclusions and score vectors (G-R6); `root` = SHA-256 over the manifest body, independent of build
  time. `POST …/dossier/verify` names each `changed` / `missing` / `added` artefact; an edited manifest fails its own root
  (`selfConsistent:false`). ⚠ **Two independent checks:** `integrity` re-checks LA-10's recorded hashes (so the first
  Dossier after a tamper reports it); a tamper that keeps internal hashes consistent, or one to the header, is caught by
  the manifest alone. The Dossier computes no centrality itself: its score tables are vectors a snapshot sealed, each
  labelled. A snapshot must be **anchored** (`investigationId` names this Investigation) or 422. Neither route persists;
  audited `LINK_DOSSIER_BUILT` / `_VERIFIED`. The server stores `manifestHash` verbatim; the manifest is the custody root.
  **Data-state footer** (LA-DATA-TRUST-1 S9, 2026-10-14): `dataFooter` on json, html and bundle — data-as-of
  (`DatasetProvider.dataAsOf`), newest index version + stale flag, Files waiting for the index, days with no rows over the
  window at the Dossier's step. 🔴 **NOT sealed**: assembled AFTER `GraphDossierBuilder.build` as a sibling of `manifest`
  (dropped by `DossierBundleRoutes.seal`), says `sealed:false`, an edited footer does not fail verification; an untaken
  reading is `null` and named in `notAssessed`; counts, days, versions only. `steps`/`method` unchanged. Proof
  `ControlApiDossierDataFooterTest`. **SPA** (`link-analysis-dossier.component`): renders json, downloads
  `steps`/`method`/HTML as Blobs through HttpClient at the dossier's own `at` and `snapshots` (never a bare href: it
  would skip the bearer); verifies the issued manifest or an uploaded file and names why a verify failed. *At step*
  (0..`summary.steps`) and *Include snapshots…* (`GET /inv/snapshots`, newest first, `limit=100`, ≤ 20 checked,
  `DossierRoutes.MAX_SNAPSHOTS`); ⚠ the list is not filtered to this Investigation, so a foreign snapshot is refused 422
  *not anchored* (verbatim).
* **The Working Set as rows** (LA-20, 2026-09-23; `WorkingSetRoutes`): `GET …/working-set?of=entities|links|excluded&limit&offset`,
  fixed columns with provenance (`opSeq`, `seedId`, `hop`, `reason`), true `total` + `truncated`, from the sealed log
  alone; **cached** under the hash of the committed log bytes (access gate runs before the cache). Readers (D-E7 amended
  by D19): a member or a linked-Case member; on Advanced also the PDP's row verdict for `resourceKind: investigation`. ⛔
  Not a Dataset or DuckDB view (`/bi`, `/db` cannot reach it). SPA: 200 a page, *Load more*, keyed
  `la-working-set-<relation>`; an empty relation renders an empty state (an empty ag-grid fails axe).
* **Templates and Measures** (LA-23 SPA, 2026-09-23): a Measures strip reads `GET …/measures`; *Watch* binds an Alert Rule
  and shows current value, `wouldFire` and the disclosure verbatim (503 explained; 403 names the Alert-Rule capability).
  *Save as template* previews the D-E8 extraction (seeds → parameters, exclude/hide/keep dropped, named expands
  generalised) as a client mirror of `InvestigationTemplateRoutes.save` (`investigation-template.ts`); the server's
  `exact` answer is the authority (no dry run; templates are write-once). *Instantiate* lists
  `GET /inv/investigation-templates` (DR-D4), asks one seed list per parameter plus Dataset and roles, and opens the new
  Investigation.
* **Working Set Widgets** (LA-21, D-E6): *Pin to a Widget* saves a `working-set` Widget (`workingSet{relation, mode,
  pin{step, workingSetHash}}`). **Frozen** re-reads `?at=<pin.step>` and refuses rows if the hash differs; **Live** reads
  the head and states the drift. Stores no rows; a viewer who cannot open the Investigation sees *Not available to you*.
  ⛔ The Exchange refuses Working Set Widgets; a bundle export converts Live → Frozen (`converted`, and the SPA warns).
* **Annotation and coverage** (LA-19, 2026-09-24): `{op:'annotate', ids, note}` (≤ 2,000 chars) through `store.apply`;
  notes from the sealed `annotations` (absent when empty); undo needs nothing special. An Admiralty grade `confidence`
  (`B2`: A–F × 1–6, D-U9) is accepted and shown, never asked by the panel. *Check coverage* reads `GET …/coverage` over
  the Investigation's own window (`from`/`to`/`timezone` accepted, no picker) and names every zero-row day; it needs a
  `timeCol` and a bounded window (422 verbatim otherwise). Start sends `timeCol` (prefilled, DR-D7, from the timeline
  column, else the Domain Profile hint, else the first all-dates edge attribute `isTemporalColumn`) and `timeColZone` only
  with it. Per-Collector coverage: [decisions of record](architecture-records-closed-plans.md#decisions-of-record).
* **Purpose, masking, four-eyes** (D-U5/U6/U7, 2026-09-24): Start and Instantiate REQUIRE a *Purpose / legal basis*
  (422 without), sealed and shown in the Dossier, never enforced. Ids may arrive as `masked:<16 hex>` per `maskingMode`
  (default `typed`; rules: [architecture-la-core.md](architecture-la-core.md) §3.4). Since 2026-10-04 (`ASSURE-CLASSIFICATION-PROPAGATION-1`)
  column classification also comes from the pipeline schema's `raw.fields[].classification` followed through its mapping
  by `DatasetProvider.schemaClassification` (shared with `publish.postgres` and Risk Score evidence): strictest-wins on a
  computed column; untraceable lineage of a masked class masks every id. A pseudonym may be sent back in an op's `ids`.
  ⚠ With a four-eyes threshold set, every stateless graph read (`/inv/projection`, `/projection/multi`,
  `/projection/neighbors`, `/traversal/recursive-paths`) is **403** above it (a stateless read has nothing to approve); a
  budget threshold below the default `limit` refuses the canvas's initial load, and the SPA's display of that refusal is
  unchecked.
* **Members, Drafts, Oversight panels** (2026-10-03..11): Members reads `GET …/members`, a lead grants / revokes (`you`
  decides the controls; 404 "not available to you"; 422 last-lead). Drafts drives fork, `working-set`, `conflicts`,
  `rebase` (confirms exactly the listed conflicts), `promote` (`expectHead` = base + `behind`; 202 = four-eyes hold) and
  `discard`. **Draft as working scope** (`LA-UI-DRAFT-OPS-1`): `useDraft(draftId)` routes `apply`/`undo` to
  `…/drafts/{id}/ops|undo` and draws the Draft's log / replay; `LA-UI-DRAFT-FIX-1`: the Draft is loaded FIRST and the
  scope switches only on success; Replay is equivalence-only (no `reread`, checkbox disabled with a reason); re-order is
  disabled (a fork re-orders the MAIN log); Undo only after the Draft's `baseStep`; the list re-reads on each `lastStep`.
  **Oversight** (`link-analysis-oversight.component`): `pending[]` with requester, thresholds crossed and status;
  `canApproveLinkExpansions` → Approve / Deny (deny reason ≤ 200); `canRevealLinkEntities` → per-entity Reveal (audited).
  ⚠ The server is the boundary: self-approval and a Subject-less approve are 403 even WITH the capability; nothing is
  re-read until a decision succeeds. **Approver inbox** (2026-10-11): `GET /inv/pending?status=…&limit`
  (`canApproveLinkExpansions`; 503 / 403 / 422), each Investigation judged by `openForApproval` (explicit members:
  lead / reviewer; owner-only: any approver; R3 + PDP), the caller's own requests never listed, masked, true `total`,
  audited `LINK_EXPANSION_INBOX_READ` (counts). SPA: **Approvals → Link expansions**.
* **Entity Lists section** (LA-17 step 6, 2026-09-26; foot of the Investigation panel): title, purpose, Entity Type label
  (a type out of force says so), size, *Retired* badge — ⛔ never members (they arrive masked). *New list…*, *Add
  selection* (raw spellings via `rawIdsOf`; ⚠ selection exists only while an Investigation is open; ⚠ `masked:` pseudonyms
  are split out by `listableIds` — the members route would store the pseudonym), *Retire* (reason dialog). On an open
  Investigation *Exclude by list* / *Seed by list* append `excludeBy`/`seedBy` through `store.apply`; the notice counts
  `list.unmatched`, never displays it. Errors via `entityListErrorMessage(…, true)` (a 404 may be list or Investigation).
  Writes gated on `canManageIncidents`; hidden when `geoLinkEnabled` is off.
* **Identity resolution section** (LA-17 slice 2, 2026-09-30; `link-analysis-identities.component`): assert two typed
  identifiers are one entity (cross-type allowed, reason), *Find group*, groups with their assertions, *Retract*. ⚠ Keys go
  out NORMALISED (`identityKeyOf`); ⚠ the `key` param uses `STRICT_QUERY_CODEC` (Angular sends `+` raw, the server decodes a
  space). Reads need `canManageIncidents` too. Wire contract: [Consolidated as-built facts](architecture-records-closed-plans.md#entity-model-and-live-detection).

### The call-records feed (postmed_xdr)

* `postmed_xdr` is the call-records feed (LA-16 / D-U2, extended not duplicated): `IMEI` (deliberately invalid: no TAC
  prefix, no Luhn digit), explicit `DIRECTION`, and a UTC contract in `raw.fields[].timezone`. ⚠ DATA rows keep
  `OTHER_PARTY='N/A'` (a packet session's far end is the APN). 🔴 It drew a fresh subscriber PER ROW (~1,200 disconnected
  edges); a fixed population plus four planted stories (burner rotation, one-way hub, daily repeating pair, SIM moved
  between handsets) makes **873 edges over 174 nodes**.
* ✅ **Ingested end to end** (2026-09-23): 1,283/1,283 rows across three Hive partitions, zero rejected files / rows /
  cast failures, every row reconciles by `REC_SEQ`; `IMEI` keeps leading zeros; the 352 `DIRECTION='NA'` rows are the 352
  with a NULL counterparty; the planted stories survive.
* 🔴 **The UTC declaration is correct but UNFALSIFIABLE:** `SourceZones.toNaiveUtc` with `Z = 'UTC'` is an identity, and
  so is the no-declaration path, so this run proves the values, not that the declaration applied. Pinning it needs a
  NON-UTC zone in a fixture.
* ⚠ **The `.psv.defect` fixture is INERT:** excluded by filename (`glob:**/PMXDR_*.psv`), never opened, so its five
  planted defects are never exercised; renaming it `.psv` would prove the quarantine path.

## Graph Run

*Server-side graph analysis, as built 2026-10-01 (LA separation D-4; GLOSSARY "Graph Run").* One execution of one of the
29 algorithms on the SERVER over an Investigation's Working Set — the way past the browser caps; both implementations
held equal by parity fixtures. Modules, input routing, budgets, pool, cache, `propagatedRisk`, the motif and temporal
platform split: [architecture-la-core.md](architecture-la-core.md) §5. Below: what only this page states.

* **Modules.** `inspecto-graph` (pure JDK, `platform/` since D-AN2 2026-10-17): the six `Graph*` classes plus `RunControl` /
  `GraphAborted`; old signatures delegate with a no-op `RunControl`. 17 algorithms are instrumented; the 11 without a
  `RunControl` overload (paths, ego, neighborhood, components, degree, structure, forest) cannot be cancelled mid-run.
  `inspecto-la-core`: `Algorithm` (id = the TS export name, `cost`, `inlineNodeCeiling`, typed `params`, `needs*`,
  `resultKind`, `resolve`), `GraphEngine` SPI, `InMemoryGraphEngine` (exhaustive switch, no `default`), `GraphInput`,
  `GraphBudget`, `GraphRunService`. `inspecto-la-api`: `GraphRunRoutes`, `GraphResultJson` (exhaustive switch over the
  sealed `Payload`, so a new result shape does not compile). `inspecto-graph.jar` is a core thin jar in every bundle.
* **Routes** (`/api/v1`). `GET /inv/graph/algorithms` (catalogue, `ceilings`, `defaults{…,clamped}`, `pool`, `inlineWaitMs`,
  `engines`, `resultItems`; no capability) · `POST /inv/graph/runs` (`{investigationId, at?, algorithm, params?, weights:
  "count"|"none", kinds?, budget?, input?}`; unknown field 422) → `200` with the run view when terminal or finished within
  the inline wait, else `202` + `Location` · `GET /inv/graph/runs[?investigationId=]` · `GET …/{id}` · `POST …/{id}/cancel`
  → `202 {runId,status,cancelRequested}`. At or under `inlineNodeCeiling` the request waits `INLINE_WAIT_MS` = 3,000 ms;
  above it never. Queue full `503 STORE_BUSY` (retryable); no write root `503 CONTROL_PLANE_READ_ONLY`. Reads re-run
  `openForRead` every time (lose the Investigation, lose the run: 404). Four route gates done (openapi,
  `CapabilityManifest`, the la-api / geo-link manifests' `provides.routes`, real-HTTP tests with an armed Authenticator).
* **Capability.** Start needs `canRunLinkGraphAnalysis` (seeded to `operations` / `support` / `power` / `admin`; `super` by
  convention; listed in `okf/product/module/access/plan-security.md`; SPA `LensService.canRunLinkGraphAnalysis`,
  access-catalog node `linkgraph.run`). Cancel carries none (`self-service` exemption): see gotchas.
* **States.** `QUEUED → RUNNING → COMPLETED | CANCELLED | BUDGET_EXCEEDED | FAILED`, terminals final. The budget is always
  fully stated (request, else Space `graph_run`, else default), clamped, echoed `budgetClamped`. **Never a silent cap:**
  params validated first (`InvalidGraphRequest` = 422, no run), size checked BEFORE work (`BUDGET_EXCEEDED` with reason
  NODES or EDGES and the measured size), then cache, then pool. `timeoutMs` counts from execution start. A run returning
  AFTER its deadline is `BUDGET_EXCEEDED`, answer discarded; a cancel losing the race to an uncancellable algorithm is
  `CANCELLED`, discarded. `result` (and `masking`) only for `COMPLETED`; `FAILED` names the exception class, never its
  message (it could carry an entity id).
* **Input rows.** `WorkingSetGraphInput.from(entityRows, linkRows, kinds)`: the Working Set at `at` MINUS hidden entities
  and their links (`input.hiddenEntities`); `resolve` groups NOT merged; links with a non-entity endpoint dropped and
  counted (`droppedDangling`). Edge id = wire `LinkIds.encode(source,target,kind)` (not the SPA canvas id). Weight =
  `WorkingSetGraphInput.edgeWeight(count, kind)` (positive count, else 1; `weights: "none"` unweighted). Parallel edges of
  one `(source,target,kind)` fold, so `degree` legitimately differs from a browser graph that kept them.
* **Masking after the cache.** Computed on RAW ids, cached once per Working Set, masked on the way out (`GraphResultJson.of`
  + `Ids.of(EntityMasking)`). **Edge ids: `LinkIds.decode` → mask both endpoints AND the kind → `encode`**, so a masked edge
  id equals the masked Working Set's `linkId` (asserted over the wire). A pseudonym is accepted as `from`/`to`/`node`.
  Communities are an ordered `[{id, community}]` list. **Masking oracle closed (2026-10-02):** a RAW id of a masked
  entity sent as `from`/`to`/`node` is treated as absent (`GraphRunRoutes.ABSENT_NODE`): the same empty answer an unknown
  id gives.
* **Cache scope.** Two Subjects share entries safely today because the Working Set is a pure function of the sealed log and
  Dataset gates / masking run outside the cache (`ControlApiWorkingSetSubjectScopeTest` must keep passing). **The key must
  gain the Subject's resolved row scope the day a Dataset is read live or gains row filtering.**
* **Canonical order `canonical-v1`.** Equal scores rank by UTF-16 code-unit order of the NORMALISED id, then label (Java
  `BY_SCORE_THEN_ID_THEN_LABEL`, TS `compareCanonicalV1`), replacing six `localeCompare` sites; host-independent; pinned by
  a mixed-case fixture row in both languages.
* **Audit.** `LINK_GRAPH_RUN_STARTED / _COMPLETED / _CANCELLED / _BUDGET_EXCEEDED / _FAILED`. Terminals come from a
  `GraphRunService` hook (exactly once, outside every lock, exceptions swallowed); STARTED from the request thread, so a
  fast run's terminal can precede it (both carry `runId`). Never params; a terminal event's actor is the owner.
* **SPA** (`GraphRunsService`, `LinkAnalysisServerRunComponent`, `graph-run-apply.ts`): browser-first under the cap,
  server above. A tool group shows **Run on server** INSTEAD of the local button when the Working Set exceeds that
  algorithm's `browser_algorithms.<id>` limit (suspicion: `suspicion_node_cap`); the count is the Working Set's (hidden
  left out); no Investigation → disabled with "open an Investigation". No `at` sent. Polls at 1 s. `buildServerIdMap`
  matches server ids against every raw spelling a node folded and maps a `linkId` by re-minting the canvas edge id; an id
  the canvas does not draw is dropped, never invented. BUDGET_EXCEEDED shows code, budget, size and ONE next action ("run
  again with a budget of N" only if it fits under `ceilings`, else "filter the Working Set or pick a cheaper algorithm").
  Progress: prefers `progress.fraction` and `consumed.elapsedMs` when > 0, else "N steps done" and a browser-measured wait.
* **Selection algorithms** (2026-10-01): shortest / weighted shortest path, all paths, cycles, cut points (articulation
  points and bridges = TWO server runs) and maximum spanning forest use one threshold, `selectionNodeCapValue()` (= the
  analysis cap), lowered to the server's `inlineNodeCeiling` (`serverCeilings`), never raised. The local button is
  replaced ONLY when a server run can start (`serverFirst`: over threshold, Investigation open, capability held). Server
  selections go through `applyServerResult` into the same `applyPath` / `applyAllPaths` / `applyCycles` / `applyCutPoints`
  / `applySpanningForest`. Cut-point halves combine only when computed against the same id map; "No cut points" only when
  both halves ran on it.
* **Not drawn on the canvas.** `countDropped` / `droppedNotice` state `N of M result nodes and K of L result links are not
  drawn on the canvas right now`; no reveal action (collapse and filters act on the query graph).
* **The Investigation canvas draws the Working Set whole, up to ONE render ceiling.** `workingSetToGraph` folds links
  through `projectTriples` with the node cap OFF (the 2026-10-01 preview drew `1200 nodes · 551 links` for 1,200 / 1,501
  because the cap dropped every link past the 500th node). `WORKING_SET_LINK_RENDER_CEILING` = 5,000 links (constant):
  above it the heaviest are drawn (count desc, then id), `omittedLinks` counted, notice `Showing 5,000 of 20,000 links
  (render limit)`. Kind filter, timeline and super-node grouping act on the query graph only, so they are disabled with
  the reason while the Working Set is drawn.
* **Parity.** The six `graph-*-parity.fixture.json` files are asserted in TS and Java (`GraphEngineParityTest` too), plus a
  route-level test feeding `graph-algorithms-parity.fixture.json` through Dataset → Investigation → Working Set → run
  (`degree` deliberately not asserted). `GraphComplexityEquivalenceTest` / `graph-complexity-equivalence.spec.ts` keep the
  pre-fix `kCore`, `weightedShortestPath`, `linkPrediction` as references (`LA-GRAPH-QUADRATIC-1`). `GraphAlgorithmsBench`
  (`-Dinspecto.bench=true`, `-Dinspecto.bench.only=`) sets `inlineNodeCeiling` = largest benched node count with median ≤
  1,000 ms (500 for betweenness and suspicion).
* **Gotchas and deliberate decisions.**
  * A well-formed node id naming no node yields the algorithm's empty answer (as the browser and fixtures do); only an
    absent / blank / mistyped id, unknown name, bad type or out-of-range value is refused. Count params accept 0.
  * `linkPrediction` stays a JOB (every pair sharing a neighbour: quadratic by definition at a hub);
    `jaccardSimilarity`'s cost follows the chosen node's degree, so a node ceiling alone does not bound it.
  * **Live progress:** `consumed.elapsedMs` is monotonic since execution began (0 while QUEUED); `progress {work, fraction,
    known}`, `fraction` meaningful only when `known` (reported next to `checkpoint()`, never in an inner loop).
    Reporting: betweenness, closeness, link prediction (per source); PageRank, eigenvector, Katz, HITS, label propagation
    (per iteration of the requested maximum — early convergence finishes below 1). Louvain and the 11 uninstrumented
    report nothing.
  * **Cancel on someone else's run is 404** (same body as an unknown run; no existence oracle), and cancel re-runs
    `openForRead`. Starter and administrator (`Roles.CAN_ADMINISTER`, or no Subject) cancel; a finished run is 409
    (operator-delegated 2026-10-02; `LA-GRAPH-RUN-CANCEL-STARTER-COVERAGE-1` pins the Case-member 404 with a mutant).
  * With an Investigation open, browser runs and From / To pickers use the Working Set canvas (`canvasData()`), the graph
    server runs map onto (operator 2026-10-02).
  * **Service lifetime:** one `GraphRunService` per Space write root (`GraphRunServices`), created lazily, closed by
    `ApiContext.onClose` or when unused for 1 h (`IDLE_TTL_MS` = finished-run retention) with nothing in flight AND
    nothing finished within that hour (`lastActivityMillis()` counts a finish), or at once when the Space directory is
    gone. Sweep on access; one lock covers sweep, lookup, create, close. Worker threads time out after 30 s idle
    (`allowCoreThreadTimeOut`).
  * **`maxRuns` is soft:** it bounds FINISHED runs; live runs are bounded by the pool, so the table holds at most
    `maxRuns + threads + queue`.
  * **Result size — never silent:** each list (`scores`, `hubs`, `authorities`, `groups`, `ids`, `communities`, `links`,
    `selections`, sub-graph `nodes`/`edges`, `nodeIds`/`edgeIds`) is cut to `graph_run.max_result_items` (default 10,000,
    ceiling 1,000,000; echoed `resultItems{limit,default,ceiling,clamped}`) **on the way out, like masking** (the cache
    keeps the full result; raising needs no re-run). Keeps the FIRST canonical-v1 entries; `result.truncated` +
    `result.lists.<name> = {total, returned, limit, truncated}` (nested lists such as `groups[0]` only when cut). A sub-graph
    returns only edges with both nodes returned. SPA `truncationNotice` ("Showing the first 10,000 of 25,311 scores (the
    server cap is 10,000; raise graph_run.max_result_items in Settings)").
* **Closed 2026-10-02:** `LA-COVERAGE-FLOOR-HOME-1` (geo-link's coverage profile writes `jacoco-aggregate/jacoco.csv`;
  `check-coverage.mjs` keeps the best row per class; floor 78.0%); `LA-INDEX-FINGERPRINT-COST-1` (`InputFingerprint.MAX_FILES`
  10,000); `LA-INDEX-READER-POOL-1` (`IndexReader.borrow`, max 4 idle per version; nothing calls `evictAll()` at close
  yet); `LA-INDEX-STAGE-RACE-1` (`IndexStore.stage()` retries on `FileAlreadyExistsException`, 16-thread test);
  `LA-INDEX-BUILDER-TEST-TIMING-1` (cancel test polls for stage bytes).
