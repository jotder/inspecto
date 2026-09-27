# LA-17 — Entity model: design (slice 1: Entity Types + Entity Lists)

> **Status:** 🟡 APPROVED 2026-09-26 (D-M1..D-M8); steps 1–5 done, step 6 panel done. Parent backlog: [`link-analysis-backlog-plan.md`](link-analysis-backlog-plan.md)
> row **LA-17** (§5, un-deferred 2026-09-24) and §2.6. Current knowledge: [`okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md).
> When this plan and the code disagree, re-ground.

## 1. Operator decisions (2026-09-26)

| Id | Question | Answer |
|---|---|---|
| **D-M1** | How is cross-identifier resolution decided? | **Deterministic only.** Identifiers join only by an *asserted* fact (a mapping Dataset such as a SIM register, or an analyst assertion). No fuzzy / similarity merging, not even as a suggestion. Every merge is explainable. |
| **D-M2** | Where do identity facts and lists live? | **Append-only fact log per Space.** The registry is a fold over the log; history is kept, a Dossier can pin a log position. |
| **D-M3** | Fixed or configurable Entity Types? | **Configurable per Space**, seeded with the §2.6 set. |
| **D-M4** | First slice? | **Entity Types + Entity Lists.** Resolution is slice 2. |

## 2. Vocabulary (✅ in `GLOSSARY.md` §11 since 2026-09-26)

- **Entity Type** *(Type)* — a named kind of business identifier: `subscriber`, `imsi`, `imei`, `msisdn`, `wallet`,
  `account`, `agent`, `handset`, `cell`. Carries a normaliser and an optional masking class.
- **Entity** *(Instance)* — unchanged meaning (GLOSSARY §11); gains a **typed key** `<type>:<normalised value>`.
  An untyped key stays `entity:<value>` (today's id) — no forced migration.
- **Entity List** *(new)* — a named, persisted, Space-scoped set of typed Entity keys with a purpose
  (`allow`, `block`, `watch`, `exclusion` — D-P10). The object D-E8 said "travels with the template".
  ⛔ **Not "Reference List"** — *Reference* is the Catalog's dimension data origin (GLOSSARY §3). ⛔ Not "watchlist"
  as the noun: *watch* is one *purpose* of an Entity List.
- **Identity Fact** — one immutable log record: `asserted` (by a person or a named mapping Dataset) or, in
  slice 2, `resolved` (derived deterministically). Retracted by a later `retract` fact, never edited (§2.6: *asserted
  fact kept distinct from inference*).

## 3. Grounding (verified 2026-09-26)

- `InvestigationEvaluator` folds 7 ops; `excludeBy`, `seedBy`, `threshold`, `snapshot` are **named-but-refused**
  (`InvestigationRoutes.java:101-107`). `excludeBy` is the natural consumer of an Entity List.
- `Entity.type` is set **only at seed** (`InvestigationEvaluator.java:64,231-235`); expand-admitted entities have
  `type = null` (`:248`). Typed masking therefore misses them (`EntityMasking.java:33-73`).
- The typed identifier set `{MSISDN, IMSI, ACCOUNT}` is a **hard-coded closed set** (`EntityMasking.TYPED_IDENTIFIERS`, `:73`);
  Dataset `columns[].classification` is free text (`PipelineDocumentModel.java:172`).
- Templates drop analyst `exclude/hide/keep/annotate` and hold a placeholder comment for lists
  (`InvestigationTemplateRoutes.java:56-59,78`).
- The log precedent is the Investigation store itself (immutable files under `audit/snapshots/…`, writes serialised
  by a per-directory JVM lock, `InvestigationRoutes.java:119,199,222`). ⚠ `DbFileStageStore`, named in the backlog as
  "the reusable shape", is actually a JDBC insert-only **table** (`DbFileStageStore.java:42-193`) — the Investigation
  store is the closer precedent and is reused instead.
- SPA mint sites: `entityId()` `entity-projection.ts:83` (+ callers) and `geo-analysis.ts:342` via
  `normalizeEntityKey`.

## 4. Design

### 4.1 Entity Types — per-Space config
- Stored in the existing per-Space settings doc `link-analysis.toon` (`ConfigSpecs.linkAnalysisSettings()`), new key
  `entity_types[]: {id, label, normaliser, masked, classifications[]}` (wire: `entityTypes`; GET also returns `entityTypesInForce`). It is a small curated preference, not a registry
  component → **no new `WRITABLE_TYPES` kind**.
- `normaliser` is a closed enum: `default` (today's case/whitespace/punctuation fold) · `digits` (IMSI/IMEI) ·
  `e164` (MSISDN; `+` or `00` prefix → `+`, no country inference) · `upper-trim`. A closed set keeps Java and TS
  provably identical: the shared fixture `inspecto-ui/src/app/inspecto/graph/entity-normaliser-parity.fixture.json`
  feeds both `entity-key.spec.ts` and `EntityTypesTest`. ⚠ "Whitespace" means the **JavaScript `\s` set** on both
  sides (Java spells it as an explicit class — Java's `\s`, `trim()` and `strip()` all miss NBSP).
- `masked` is a plain boolean (simpler than the drafted `maskClass`; nothing needs more than two classes yet).
- Validation (422): id `^[a-z][a-z0-9_]{0,31}$`, unique; label non-blank; normaliser in the set; a classification claimed
  by at most one type; ≤ 64 types; a stated list **replaces** the defaults wholesale and may not be empty.
- `classifications[]` maps Dataset `columns[].classification` values to the type, so a projection column is
  **typed by its Dataset**, and expand-admitted entities inherit the column's type — fixes the `type = null` gap.
- Seeded default = the §2.6 set; absent file ⇒ defaults (existing settings behaviour). Invalid ⇒ 422, never clamped (as D-S3 decided).
- `EntityMasking.TYPED_IDENTIFIERS` is replaced by "types with `masked: true`" (step 5).

### 4.2 Identity fact log — per Space
- Location: `spaces/<space>/audit/entity-facts/` — one immutable JSON file per fact, name = zero-padded sequence;
  appends under the same per-directory lock as Investigations. Content-hashed chain (each fact carries the previous
  fact's SHA-256) so a Dossier can cite `{logHead, seq}` and custody verify stays SHA-256 end to end.
- Slice-1 fact kinds: `list.created`, `list.member.added`, `list.member.removed`, `list.retired`. Every fact:
  `{seq, at, actor, reason, prevHash, …}`; `reason` required (same stance as D-U5 purpose).
- Fold: `EntityRegistry.fold(facts, atSeq?)` → lists as of a position. Cached by head hash (as `WorkingSetRoutes` caches by log hash).
- Retention: none — append-only, consistent with D-U8.

### 4.3 Entity Lists — routes (`inspecto-geo-link`, new `EntityListRoutes`)
Following the `endpoint` skill's gate order (503 write root → 422 spec → 403 → 409 → act):
- `GET /inv/entity-lists` · `GET /inv/entity-lists/{id}?at=<seq>`
- `POST /inv/entity-lists` `{id, title, purpose, entityType}` (409 on existing id)
- `POST /inv/entity-lists/{id}/members` `{add[], remove[], reason}` — keys normalised by the type's normaliser server-side
- `POST /inv/entity-lists/{id}/retire` `{reason}`
- Writes under `canManageIncidents`; members masked at render per `maskingMode` (D-U6), reveal via the existing capability.
- Audit: `ENTITY_LIST_CHANGED` carrying list id + counts (never raw keys, as `LINK_ENTITY_REVEALED`).

#### 4.3.1 Wire contract (fixed 2026-09-26 for step 3 — backend and SPA build against this)
- A **list summary**: `{id, title, purpose, entityType, size, retired, createdAt, createdBy, lastSeq}`.
- `GET /inv/entity-lists` → `{lists: [summary…], headSeq, headHash}` (retired lists included, flagged).
- `GET /inv/entity-lists/{id}?at=<seq>` → `summary + {members: [key…], atSeq, headHash}`; `at` beyond head → 422;
  a list that did not exist at `at` → 404. Members sorted, rendered through `maskingMode` (a list whose Entity Type
  has `masked: true` is masked under `typed`; `all` masks every list; `none` masks nothing) using the same token as
  `EntityMasking`. Reveal of list members is **not** in this slice.
- `POST /inv/entity-lists` `{id?, title, purpose, entityType, reason}` → **201** + the list. `purpose` ∈
  `allow · block · watch · exclusion` (D-P10); `entityType` must be in force (`entityTypesInForce`); id minted when absent,
  else `^[a-z0-9][a-z0-9_-]{0,63}$`; an id ever used (retired too) → **409**.
- `POST /inv/entity-lists/{id}/members` `{add?: [raw…], remove?: [raw…], reason}` → 200 + the list. Values are
  normalised server-side with the list's Entity Type normaliser; a value empty after normalising → 422; a key in both
  `add` and `remove` → 422; ≤ 5 000 values per call. Only effective changes write facts; a call that changes nothing
  writes nothing and answers 200 with `changed: 0`. Retired list → 409.
- `POST /inv/entity-lists/{id}/retire` `{reason}` → 200; already retired → 409.
- Every write: `reason` non-blank (422), capability `canManageIncidents`, no write root → 503, unknown list → 404.
  Reads need only Space access (a list is a Space object, not owner-scoped like an Investigation).
- Audit: `ENTITY_LIST_CHANGED` `{listId, kind, added, removed, seq}` — counts, never keys.

#### 4.3.2 As built (step 3, 2026-09-26) — readings of §4.3.1
- Every write answers **the full list** (summary + members + `atSeq` + `headHash`), retire included; members
  answers also carry `changed` (always, not only when 0).
- The two GETs also answer **503** with no write root (the log lives under it, as `GET /inv/snapshots`).
- `headHash` on `GET /{id}` is the hash of the fact **at `atSeq`**, so `{atSeq, headHash}` pins exactly what was read.
- Members route order: 404 → retired 409 → normalisation 422 (normalising needs the list's type). A list whose Entity
  Type is no longer in force: member writes 409; `typed` masking masks it anyway (fail closed).
- Extra 422 bounds: title ≤ 200, reason ≤ 1 000, value ≤ 512 chars, values must be strings. No path-jail 403 (no
  caller-named file).
- Store: `<write root>/audit/entity-facts/<12-digit seq>.json`, fields `seq, at, actor, reason, kind, listId, …, prevHash`;
  temp file + fsync + move **without** replace (`AtomicFiles` always replaces, so it is not reused). One fact per call per
  kind carrying the sorted changed `keys[]`. The whole chain is re-verified on every read → `INTEGRITY_VIOLATION` (500)
  when broken; no cache (verification already parses every fact). ⚠ A deleted **last** fact is undetectable from the
  log alone — only a head hash cited elsewhere (a Dossier) catches it.
- ⚠ Masking uses its own per-Space key (`entity-facts/mask.key`), so a list member's token differs from an
  Investigation's token for the same value. Deliberate for now (tokens are not correlatable across objects); revisit if
  analysts need to match a masked list member to a masked graph node — `excludeBy` compares raw keys server-side, so
  step 4 is unaffected.
- ⚠ **Changing a type's normaliser strands existing members**: they were stored under the old rule, so a later
  `remove` (normalised by the new rule) cannot match them. Not handled in slice 1 — see the backlog plan's
  `LA17-NORMALISER-CHANGE-1`. Orphaned `.fact-*.tmp` files from a crash are ignored on read and never swept.
- Review fixes before the first commit: final sigma folded to σ on both sides (Java and JS place Final_Sigma
  differently); facts and `mask.key` published by hard link (atomic no-replace on every OS, not only Windows);
  responses sent after leaving the write lock.
- Routes `EntityListRoutes` (ServiceLoader `RouteModule`), `EntityFactLog`, `EntityRegistry`; event `ENTITY_LIST_CHANGED`
  (one per fact); `CapabilityManifest` + `AbsentGeoLinkRoutes.SURFACE` (Personal edition 503) updated.
- SPA: `InvService` gained the five methods (`inspecto-ui/src/app/inspecto/api/inv.service.ts`); settings typing gained
  `entityTypes` / `entityTypesInForce`. No panel yet (step 6).

### 4.4 Investigation ops
- Ship **`excludeBy {list}`** (and `seedBy {list}` — same mechanism): the op records `{listId, atSeq}` so replay is
  pinned to the list as it was (D-E3 fingerprint stays sound).
- Templates: a list-bound op **travels** with the template (D-E8); plain `exclude` still does not.

#### 4.4.1 Op contract (fixed 2026-09-26 for step 4)
The evaluator is pure over **sealed** reads (an `expand` carries its rows), so a list op seals what it resolved at
append time and replay never re-reads the list:
- Request `POST /inv/investigations/{id}/ops` `{op: "excludeBy" | "seedBy", listId, reason}` (`reason` required for
  `excludeBy`, as for `exclude`). Resolved at the list's **head**; the log entry stores
  `list: {listId, atSeq, headHash, entityType, normaliser, members[]}` — the members as they were.
- Refusals at append: unknown list 404 · retired list 409 · the list's Entity Type not in force 409 · a list over
  5 000 members 422 (bounded like every other op payload).
- **`excludeBy`** — members are *normalised keys*, Investigation ids are *raw column values*, so the evaluator
  excludes an entity when `EntityTypes.normalise(normaliser, id) ∈ members`: every admitted entity now, and every
  entity a later `expand` would admit (remembered by key, the way `exclude` remembers ids). `keep` still protects,
  reported as `protected`. The step reports how many entities it removed and how many members matched nothing.
- **`seedBy`** — needs the raw values whose normalised form is a member, so the route reads
  `SELECT DISTINCT` of the bound `sourceCol` and `targetCol` (the Investigation's Dataset and filter, bounded at
  `MAX_LIMIT` distinct values per column — above it → 422 naming the cap, never a silent sample), normalises in
  Java, and **seals** the matched raw ids beside `list` (`read: {ids[], fingerprint, readAt}`, like `expand`). The
  evaluator then seeds those ids exactly as `seed` does, with `entityType` = the list's type. Members matching no
  row are reported, not errors.
- Undo, replay, reorder/fork and the Dossier treat both like any other op; the log line reads e.g. *"Excluded 12
  entities on Entity List `known-mules` (exclusion, 40 members, as of fact 17)"*.
- Templates: `excludeBy` / `seedBy` carry `{op, listId}` only; instantiating re-resolves at that moment's head
  (method travels, the old membership does not).
- Audit: the existing op event, plus `listId` and `atSeq` — never members.

#### 4.4.2 As built (step 4, 2026-09-26) — readings of and deviations from §4.4.1
- **Seal.** `list` also carries `purpose` (the log line names it). `atSeq`/`headHash` are the fact log's head seq and
  head-fact hash at append. `params` is `{listId, reason}` (excludeBy) / `{listId}` (seedBy); `ids` on a list op → 422,
  `listId` shape → 422. Refusal order: 404 · retired 409 · type not in force 409 · > 5 000 members 422.
- **excludeBy records each entity it removes in `excluded`** (reason + step), exactly as `exclude` — so the Working Set's
  `excluded` view, the expand SQL's prune and the Dossier's negative space (basis *"stated rule (Entity List …)"*) all see
  them. Keys are remembered in a new state field `excludedKeys [{normaliser, key, step}]`, emitted only when non-empty, so
  every pre-LA-17 hash is unchanged. ⚠ A remembered key the SQL cannot prune (normalisers are Java): a not-yet-admitted
  matching entity still spends the expand budget and counts toward degree, and is dropped by the evaluator.
- A remembered key does **not** block an entity already in the Working Set — only a later `seed`/`seedBy` (a later
  explicit op wins, as `seed` re-admits an excluded id) or a `keep` can have put it there.
- **seedBy.** No Investigation filter exists, so the read is `SELECT DISTINCT CAST(col AS VARCHAR) … WHERE col IS NOT NULL`
  per bound column over the whole Dataset (R3 gate, default sandbox policy, identifiers only — no value inlined). Cap
  `SEED_BY_DISTINCT_CAP` = 20 000 (`InvRoutes`' row cap); above it 422 naming column and cap (unit-tested in
  `InvestigationSeedByReadTest` with a small cap). `read` = `{dataset, readAt, query:{columns, distinctCap}, ids[], rowCount,
  fingerprint}`, fingerprint over `ids`; the Dossier's sealed-read integrity check hashes `ids` for a seedBy.
  ⚠ `replay {reread}` re-checks expand reads only — a seedBy read is not drift-checked.
- **Step result**: `list {listId, atSeq, headHash, entityType, purpose, members (count), removed | seeded, unmatched[keys]}`,
  plus `protected` for excludeBy; excludeBy's `unmatched` = members no entity admitted *before* the step normalises to.
- **Log / Dossier.** The log view and the Dossier's JSON rendering show `list` with `size` instead of `members`, and drop
  `read.ids` (as sealed rows are dropped); the `log.jsonl#n` manifest artefact still hashes them. The removed count is
  derived by evaluation (`InvestigationEvaluator.entityCounts`), never stored; the Dossier line names every removed id (G-E10).
- **Gates.** Four-eyes is an expand budget/fan-out rule — list ops have neither, so none applies; purpose is create-time.
- **Masking** (fixed after review): `sealList` also seals the Entity Type's `masked` flag in `list`, and `EntityMasking`
  (still reading only the Investigation log) treats a list as typed when that flag is `true`, when it is **absent** (fail
  closed), or when the type is a `TYPED_IDENTIFIERS` one. Under `typed` such a list masks its member keys (so
  `list.unmatched` and `/replay`'s `excludedKeys`), the ids a seedBy sealed (delta, Dossier line), **and every id whose
  key under the list's normaliser is a member** (a raw `0044 7700-900123` is the member `+447700900123` in another
  form). A `masked: false` type stays raw. ⚠ The first cut checked only `TYPED_IDENTIFIERS`, so wallet / subscriber /
  custom masked lists leaked raw keys. Tokens use the Investigation's key, not the list's.
- An excludeBy over an empty list leaves no `excludedKeys` entry. A template-generated excludeBy reason clips the
  template id to 100 chars so it stays within the 200-char reason cap.
- **Templates** carry `{op, step, listId}`; at instantiate an excludeBy's reason is stated as
  *"Entity List x (from template t)"* (the authored reason is case text); refusals are prefixed *"template step n:"*. A
  template whose only seeding is a seedBy needs no seed parameter (the *no effective seed* 422 now also accepts a seedBy).
- **Fork (reorder)** carries a list op's sealed `list` — and a seedBy's `read`, which does not depend on order — verbatim;
  it does not re-resolve (expand still re-reads).
- Tests: `ControlApiInvestigationEntityListOpsTest` (13, real HTTP), `InvestigationSeedByReadTest` (2).
  `compliance/evidence/route-gating.md` regenerated (line numbers only).

### 4.5 SPA
- `normalizeEntityKey(value, type?)` gains the typed normalisers from the shared fixture; typed ids `<type>:<key>`.
- Toolbox: *Entity Lists* panel (list, create, add selection, `excludeBy` / `seedBy`). Masking of members as elsewhere.

## 5. Out of scope (slice 2+)
- Resolution (D-M1): `identity.asserted {a, b, via}` facts, mapping-Dataset import, merged-node rendering with
  provenance — needs its own gate for "a merge must never hide which identifier matched".
- Enrichment attributes as filters; LA-18 value measures (blocked on this).
- LA-24 Case link.

## 6. Operator answers (2026-09-26)
1. **D-M5 — Name:** ✅ **Entity List** (not Reference List).
2. **D-M6 — Typed id format:** ✅ `<type>:<key>` for typed columns; no back-compat for saved views / snapshots holding `entity:<value>` ids.
3. **D-M7 — Where types live:** ✅ in the per-Space settings doc `link-analysis.toon`, not a registry kind (not shareable via the Exchange — accepted).

4. **D-M8 — Reconciling with the assurance plan's `D-P10`** (decided by another shift at 19:27 the same day: *"one
   component kind, named Entity List"* for assurance allow / block / watch and `LA-17`): ✅ **operator 2026-09-26: keep the
   Identity Fact log, align the rest.** `D-P10`'s "one kind" is read as **one concept, one store** — the fact log, because
   as-of reads and the SHA-256 chain are what custody needs and a `ComponentStore` kind would give neither. Purposes are
   now `D-P10`'s `allow · block · watch · exclusion`. Ranges (number prefix, CIDR), `expires-at`, the 10⁵-entry Parquet
   sidecar and D-P5's four-eyes on permanent blocks are **assurance wave 2.2 (WS-12)**, which extends this store and these
   routes rather than adding a kind. ⚠ Assurance needs the lists outside Link Analysis, so WS-12 will likely move the
   store out of the optional `inspecto-geo-link` module into core — owed there, not here.

## 7. Delivery steps
1. ✅ **DONE 2026-09-26** — GLOSSARY §11 terms + INDEX entry → verify: `tools/check-vocabulary.mjs` passes.
2. ✅ **DONE 2026-09-26** — Entity Types in settings + normaliser parity fixture (Java + TS) → verify: `ConfigSpecs`/settings route tests, parity spec.
3. ✅ **DONE 2026-09-26** — Fact log store + fold + `EntityListRoutes` → verify: real-HTTP `ControlApiEntityListTest` covering every gate.
4. ✅ **DONE 2026-09-26** — `excludeBy` / `seedBy` ops + template carry (as built: §4.4.2) → verify: `InvestigationEvaluator` + template tests; replay pinned to `atSeq`.
5. ✅ **DONE 2026-09-26** — Masking via types (replace `TYPED_IDENTIFIERS`) → verify: `EntityMasking` tests incl. expand-admitted typed entity.
   **As built.** `TYPED_IDENTIFIERS` is gone; under `typed` an id is masked when its Entity Type is masked, the types
   being `LinkAnalysisSettings.effectiveEntityTypes()`. Resolution order: (1) a list op's sealed `list.masked` (absent ⇒
   masked, fail closed) **OR** today's type for `list.entityType` masked or no longer in force — so a Space that
   tightens a type after the op cannot leave an old Investigation showing that list raw while the list route masks it
   (review fix 2026-09-26; render-time only, replay unaffected); (2) a `seed`'s `entityType` →
   the in-force type whose id equals it case-insensitively, and an `entityType` naming no in-force type ⇒ masked (fail
   closed); (3) the bound Dataset's registry `columns[].classification` of `sourceCol`/`targetCol` → the in-force type
   claiming it (trimmed, case-insensitive) → when masked, every id (an id records no column); a classification no type
   claims stays untyped. The type's flag is the one truth (as `EntityListRoutes`): no msisdn/imsi/account override.
   ⚠ **Behaviour change:** with the default types imsi/msisdn/account stay masked, but `subscriber`, `imei` and
   `wallet` seeds/columns are now masked too, and a seed whose `entityType` is not an in-force type is masked (was raw
   unless one of the three); a Space redefining `msisdn` as `masked:false` now sees it raw. ⚠ Asymmetry, by design: an
   unknown seed `entityType` fails CLOSED, but a column classification no type claims stays raw (fails OPEN) — so a
   Space whose custom `entity_types` drops the `MSISDN` classification un-masks a column that used to be masked. Four non-masking tests that
   seed `subscriber` now set `masking_mode: none`. Tests: `EntityMaskingTest` (2, fail-closed + sealed `false`),
   `ControlApiInvestigationOversightTest` +2 (wallet/unknown/handset seeds, basis text, `none`; IMEI/HANDSET columns),
   `ControlApiInvestigationEntityListOpsTest` +2 (`all` masks members/excludedKeys/unmatched; msisdn `masked:false` raw
   on both the Investigation and the list route). `ConfigSpecs` `masking_mode` description updated.
   Owed from the step-4 re-review (2026-09-26), all closed by this step: (a) a plain `seed {entityType: "wallet"}`
   (or any masked type outside MSISDN/IMSI/ACCOUNT) is still UNMASKED under `typed` — the one known gap left;
   (b) `EntityMasking` forces a type named msisdn/imsi/account to masked even when it says `masked: false`, while the
   list route shows it raw — align on the type's flag; (c) the `typed` basis string and the class javadoc still say
   entity typing is not built; (d) no unit test for the fail-closed branch (a sealed `list` with no `masked` key);
   (e) no test of the list ops under `all`.
6. 🟡 **PANEL DONE 2026-09-26** — SPA panel + typed normaliser → verify: vitest specs, preview drive.
   Shipped: the *Entity Lists* section in the Investigation tab (`link-analysis-entity-lists.component.ts` + dialogs):
   list · create · add the canvas selection · retire · *Exclude by list* / *Seed by list* on the open Investigation
   (OKF `link-analysis.md`). Preview-driven 2026-09-26 against a rebuilt Enterprise bundle: create → `POST 201`, the
   row reads *Watch · MSISDN · 0 members*, *Add selection* stays disabled until an Investigation is open.
   ✅ **Typed projection ids DONE 2026-09-27** (D-M6, D-M11 first half). As built: the server resolves a column's
   type (`InvRoutes.columnTypes`: Dataset registry `columns[].classification` → the first in-force Entity Type
   claiming it, trimmed + case-insensitive) and returns `columnTypes {col: {id, normaliser}}` on `/inv/projection` +
   `…/neighbors`, and `entityType` / `sourceType` / `targetType` on `/inv/projection/multi` rows. The SPA mints
   through ONE function, `endpointId` (`entity-projection.ts`): typed → `typedEntityKey`, untyped → `entity:<key>`
   (a column type overrides the free-text `entityType` scope). Mint sites changed: `projectEntities`,
   `projectTriples`, `projectMultiResult`, `recursivePathsToGraph`, `branchingResultToGraph`, `workingSetToGraph`,
   `nodeIdsForKeys` (brush), `caseMemberCandidates` (accepts typed ids); the typed mappings ride on the graph
   (`idMappings`), `lastRun` and `InvestigationRef`. Server/SPA agreement: the evaluator, Working Set and
   `excludeBy` never compare node ids (raw values / per-normaliser keys), so nothing to align there; the one server
   node-id reader, `ObjectService.EntityMember`, was widened from `entity:…` to `^[a-z][a-z0-9_]{0,31}:.+`.
   Gotchas: a value arriving without its column (seed, path hop, pattern match value, Geo key) resolves to the
   candidate already drawn, else source-for-seeds/start and target-for-later-hops — a column pair typed
   differently with a colliding raw value could pick the wrong end; the Geo co-location dialog stays untyped; no
   back-compat for stored `entity:` ids (D-M6). Tests: `ControlApiInvProjectionTest` +1, `MultiProjectionContractTest`
   +1, `ObjectServiceEntityCaseTest` +1; SPA specs in `entity-projection`, `multi-projection`, `geo-link-brush`,
   `investigation-state`, `case-members`.
   Still owed: member browsing, the `at` read.
7. `verification` subagent PASS; distill into OKF, move plan to archive when shipped.

## 8. Slice 2 — resolution (operator decisions 2026-09-27)

| Id | Question | Answer |
|---|---|---|
| **D-M9** | Fix for `LA17-NORMALISER-CHANGE-1`? | **Record the normaliser per list.** `list.created` seals `normaliser`; every later member write on that list normalises with the sealed rule, whatever the Entity Type says today. A `list.created` fact without the field reads as `default`. A settings change never strands members. |
| **D-M10** | Scope of the first slice-2 cut? | **Analyst assertions + fold.** Mapping-Dataset import is a later cut. |
| **D-M11** | Order? | **Projection ids through `typedEntityKey` first** (the owed slice-1 step 6 item), then resolution over typed keys. |

### 8.1 Design (to be confirmed against code by the build; deviations recorded in §8.2)
- **Facts** (same log, same chain, same lock as §4.2): `identity.asserted {a, b, via, reason}` — `a`, `b` are typed
  keys `<type>:<normalised value>` (normalised with the type's rule at assert time, sealed in the fact); `via` is
  `analyst` in this cut (`dataset:<id>@<fingerprint>` reserved for the import cut). `identity.retracted {assertionSeq,
  reason}` retracts one assertion; never edits it. `reason` required.
- **Fold**: `EntityRegistry` gains a deterministic union-find over live (unretracted) assertions as of `atSeq` →
  `resolved` groups. A group's id is its lexicographically smallest member key (stable, explainable). Every group
  carries its members AND the assertion seqs that join it (a merge never hides which identifier matched — the gate).
- **Routes** (`inspecto-geo-link`, `EntityListRoutes` family): assert, retract, read groups (`?at=`), read one key's
  group. Capability as list writes. 422 on a self-assertion, an untyped key, or a type not in force; 409 on retracting an
  already-retracted or unknown assertion.
- **Investigation**: the resolution is applied at a pinned `atSeq` (sealed like a list op), so replay is deterministic.
  A merged node shows every member identifier and the joining assertions. Masking applies per member key.

### 8.2 As built (2026-09-27)
- **D-M9 shipped.** `list.created` carries `normaliser` (the Entity Type's rule at creation); `EntityRegistry` reads a
  fact without it as `default`; member add/remove normalise with it, and so does `excludeBy`/`seedBy` sealing
  (`InvestigationRoutes.sealList` — the members were stored under that rule, so matching must use it too). The list
  summary gained `normaliser`. Member writes on a list whose type is no longer **in force** still 409 (unchanged).
  `LA17-NORMALISER-CHANGE-1` closed; pinned by `ControlApiEntityListTest` (mutation-checked: reverting to the type's
  current rule turns both new tests red on the expected values).
- **Resolution routes** — new `EntityIdentityRoutes` (ServiceLoader `RouteModule`, not grown into `EntityListRoutes`):
  - `GET /inv/entity-identities?at=` → `{groups, atSeq, headSeq, headHash}`;
  - `GET /inv/entity-identities/group?key=<typed key>&at=` → `{group, atSeq, headHash}` — a key in no live assertion
    resolves to itself (one member, no assertions). The lookup is **exact** (no normalising); `key` is a query
    parameter because a typed key may hold `/`.
  - `POST /inv/entity-identities` `{a, b, reason}` → **201** `{assertion, group, atSeq, headHash}`;
  - `POST /inv/entity-identities/{seq}/retract` `{reason}` → 200 `{retracted, groups, atSeq, headHash}` (the groups of
    the assertion's two keys afterwards — two when it split).
  - A group: `{id, members[], assertions[{seq, a, b, via, actor, at, reason}]}`.
- **Gates**: `canManageIncidents` → no write root 503 (reads too) → 422 (reason; non-string / untyped / empty-type key;
  type not in force; value empty after the normaliser; `a == b` after normalising; retract seq not an integer) →
  409 (retract: no `identity.asserted` at that seq — a list fact's seq included — or already retracted) → append under
  the log lock. `CapabilityManifest`, `AbsentGeoLinkRoutes.SURFACE` (Personal 503), `openapi-v1.json` (3 path
  skeletons, spliced in so the file's unrelated inline formatting is untouched) and `route-gating.md` updated.
- **Facts**: `identity.asserted {a, b, via:"analyst"}` / `identity.retracted {assertionSeq}` with the usual
  `seq, at, actor, reason, kind, …, prevHash`; they carry **no `listId`** (`EntityFactLog.append` omits a null one).
- **Fold**: `EntityRegistry.assertions(facts, atSeq)` + `resolve(facts, atSeq)` — union-find over live assertions,
  root = smallest key, groups sorted by id; order-independent (pinned by `EntityResolutionFoldTest`). Not cached,
  as the list fold.
- **Event** `ENTITY_IDENTITY_CHANGED` `{kind, seq, assertionSeq, groupSize}` — one per fact, never keys.
- **Masking** per member key by its own Entity Type under `typed` (a type not in force masks, fail closed); the group
  id and the assertion's `a`/`b` are rendered the same way. Same token/key as list members.
- ⚠ Deviations / readings: a redundant assertion (two keys already in one group) is **accepted** and listed as another
  joining seq — it is extra evidence, not an error. Cross-type assertions (`msisdn:` ↔ `imsi:`) are the point and allowed.
- ⚠ Gotcha: `ApiContext.query` decodes the JDK's already-decoded `getQuery()`, so `%2B` becomes a space; the group
  lookup reads `getRawQuery()` instead (`EntityIdentityRoutes.rawQuery`). Any other route taking a `+` in a query
  parameter has the same defect — not fixed here.
- ⚠ Known limit (D-M9): a list created before D-M9 has no `normaliser` on its `list.created` fact and folds to
  `default`, whatever its Entity Type's rule. Dev data only; there is deliberately no compat path (breaking changes
  are free).
- **Review fixes (2026-09-27)**: (1) an empty typed key (`N/A` under `digits`/`e164`) mints no node and no edge in the
  SPA (`typedOrEntityId` returns `null`, every mint site skips it like a blank value) — the server already refuses
  empty keys, and its projection routes only ship `columnTypes` (they mint no ids), while the evaluator/masking only
  test normalised ids against member sets that never hold `""`. (2) Entity Type id `entity` is reserved (422 on
  settings save — it would collide with untyped `entity:<value>`). (3) **Membership oracle, confirmed by probe**: under
  masking a Space-only caller sent a guessed raw key to the group read and got a 2-member group (plus the key's token)
  for a grouped key vs 1 member otherwise. Both identity reads now require `canManageIncidents`, always
  (`ControlApiEntityIdentityTest.identityReadsNeedCanManageIncidentsSoARawKeyCannotBeProbed`). **Entity List reads
  stay Space-open**: they take no key input, and masked members are keyed-HMAC tokens a caller cannot compute, so with
  the group read gated there is no guess-and-check path.
- **Owed**: applying resolution in Investigations (pinned `atSeq`, merged nodes showing members + joining assertions,
  §8.1 last bullet) waits on the parallel SPA lane; D-M11's `typedEntityKey` projection step; SPA client methods.
