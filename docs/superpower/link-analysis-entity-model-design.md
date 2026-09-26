# LA-17 — Entity model: design (slice 1: Entity Types + Entity Lists)

> **Status:** 🟡 APPROVED 2026-09-26 (D-M1..D-M8); steps 1–3 done, step 4 next. Parent backlog: [`link-analysis-backlog-plan.md`](link-analysis-backlog-plan.md)
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
4. `excludeBy` / `seedBy` ops + template carry → verify: `InvestigationEvaluator` + template tests; replay pinned to `atSeq`.
5. Masking via types (replace `TYPED_IDENTIFIERS`) → verify: `EntityMasking` tests incl. expand-admitted typed entity.
6. SPA panel + typed normaliser → verify: vitest specs, preview drive.
7. `verification` subagent PASS; distill into OKF, move plan to archive when shipped.
