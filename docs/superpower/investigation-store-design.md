<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-04. S0 DONE and S1 COMPLETE 2026-10-04 (section 13.1 to 13.3); decisions D-IS1…D-IS12 answered (section 11). BACKLOG row: `LA-INVESTIGATION-STORE-DESIGN-1`. Retire per the three-tier lifecycle
  once the InvestigationStore seam ships: distil the as-built facts into okf/frontend/features/link-analysis.md, move leftovers
  to BACKLOG.md, then git mv this file to archived-documents/plans-archive/.
-->

# InvestigationStore — a Postgres-capable home for the Link Analysis Investigation sidecar files

Operator ask (2026-10-04): design a seam so the per-Investigation files (`members.jsonl`, `log.jsonl`, sealed Working Set sets,
the Draft store, its listing index) can live on Postgres for **multi-pod** deployments. This file is the design the operator
signs before any code. Companion records: [`la-separation-d7-design.md`](la-separation-d7-design.md) (Drafts),
[`../okf/frontend/features/link-analysis.md`](../okf/frontend/features/link-analysis.md) (D-3 index, D-5 SPA, D-6 integration
records), [`enterprise-scale-out-plan.md`](enterprise-scale-out-plan.md) (D4, D5, D15 — the platform's multi-pod stance).

⛔ **Grounding method.** Every "today" claim cites `file:line` read on `master` at `f4658c498` (2026-10-04). A claim marked
**⚠ UNVERIFIED** was inferred, not read or run. Nothing here was executed: no test, no Postgres.

## 1. The problem in three lines

1. Every writer of an Investigation is serialised by a **JVM monitor keyed on a `Path`** (`InvestigationRoutes.java:141`, `:2165`),
   plus two more global monitors (`SnapshotStore.java:425` references; `DraftAdmission.java:47` the Draft cap). Nothing in the
   LA write path takes a cross-process lock. Two pods on one volume would interleave appends and both compute `step = count + 1`.
   (⚠ UNVERIFIED by a run; read from the code: `InvestigationMemberStore.append` reads the count then appends, `:64-78`.)
2. The layers talk to each other in **`Path`s**, not ids: `Inv.dir()` / `logDir()` (`InvestigationRoutes.java:177-179`), `DraftStore`,
   `DraftLifecycle`, `DraftCheckpoints`, `DraftIndex` are all static and Path-keyed, and `new SnapshotStore(writeRoot)` is built
   inside the routes (`InvestigationRoutes.java:237`) and in `inspecto-geo-link` (`WorkingSetMeasures.java:168`). A Postgres
   implementation has nothing to hang off. The seam is therefore a **de-pathing refactor first** (S1), a new backend second.
3. The sealed-evidence guarantees (section 4) are defined over **bytes on disk**; any backend must hand back the same bytes.

## 2. Inventory — what exists per Investigation, and how it is written

Layout root: `<audit root>/investigations/<id>/` (`SnapshotStore.java:165-171`).

| File / object | Writer (file:line) | Write mode | Lock today | Rewritten ever? | Size profile |
|---|---|---|---|---|---|
| `header.json` | `SnapshotStore.createInvestigation` `:179-189` | `CREATE_NEW`, never rewritten | `lock(investigations root)` `InvestigationRoutes.java:256` | no | small |
| `log.jsonl` (main) | `SnapshotStore.appendStepAt` `:248-254` (undo, ops, promote all append) | `APPEND`, one line per step | `lock(inv dir)` `:347`, `:474` | no — a `reorder` / fork writes a NEW Investigation via `createFork` `:280-300` | small lines; sealed `read` rows make some lines large |
| `sets/<step>.json` (main) | same call `:251-253` | `CREATE_NEW`, written AFTER the log line (`:238-242` documents the order) | same | no | **full canonical Working Set per step — O(state) per step, O(state x steps) in total** |
| set shared at promote | `appendStepSharingSet` `:261-274` | hard link to the Draft's sealed set, byte copy if links fail | main + Draft lock `DraftPromote.java:130-131` | no | zero extra bytes on FS |
| `members.jsonl` | `InvestigationMemberStore.append` `:64-78` | `APPEND`; seq = count + 1 | caller's Investigation lock (class note `:25-26`) | no | tiny |
| `references.jsonl` | `SnapshotStore.appendReference` `:432-` | `APPEND` | **one global monitor** `REFERENCES_LOCK` `:425`, `:433` | no | tiny |
| `case-link.json` | `writeCaseLink` `:398-` | temp + `ATOMIC_MOVE` replace | none seen (⚠ UNVERIFIED) | yes, whole | tiny |
| `alert-rules/<rule>.json` | `bindAlertRule` `:340-` | temp + `ATOMIC_MOVE` replace | none seen | yes, whole | tiny |
| `pending/<rid>.json` | `writePending` `:362-` | temp + `ATOMIC_MOVE` replace (status moves) | `lock(inv dir)` in `decide` `:1968` | yes, whole | tiny |
| `investigation-templates/<id>.json` | `createTemplate` `:316-` | `CREATE_NEW` | none | no | small |
| `drafts/<d>/header.json` | `DraftStore.create` `:115-` (staged in `.fork-*`, one rename) | rewritten whole by rebase | `lock(draftsDir)` + `DraftAdmission.CAP` `DraftRoutes.java:175-176` | yes (rebase) | small; grows with `rebases[]` |
| `drafts/<d>/log.jsonl`, `sets/` | `DraftStore.appendStep` `:327-342` (log first, **truncates the log back** if the set fails) | `APPEND` / `CREATE_NEW` | `lock(draft dir)` | **yes — replaced whole by rebase** `replaceRebased` `:288-314` (stage, 2 renames, restore on failure) | like main |
| `promoted.json` / `discarded.json` | `markPromoted` `:269-281`, `markDiscarded` `:350-362` | `CREATE_NEW`, then **delete log and sets** | draft lock | no | tiny |
| `accessed.json`, `hibernated.json` | `DraftLifecycle.touch` `:52-`, `hibernate` `:104-` | overwrite / marker, persisted at most every 5 min `:42` | none | yes | tiny |
| `mask.key` | `MaskTokens.key` (`inspecto-entity-store`), first use; since S1 `InvestigationStore.maskKey` | `CREATE_NEW` via a hard link, race-safe | none needed | no | 32 random bytes, a SECRET (the entity-masking HMAC key); added to this inventory 2026-10-04 |
| `drafts/<d>/promoting.json` | `FsInvestigationStore.promoteDraft` (S1) | `CREATE`, before the first main step; removed on success or rollback | main + Draft monitors | no | tiny; the crash-recovery intent (`from`, `to`, lines hash, marker), read by `recoverDrafts` |
| `drafts/index.json` | `DraftIndex.store` `DraftIndex.java:69-77` | temp + `ATOMIC_MOVE`; a **rebuildable cache** (class note `:12-18`) | `static synchronized` `:27` | yes, whole | small |
| `.fork-*`, `.rebase-*`, `.old-*` scratch dirs | `DraftStore.recover` `:201-` sweeps crash leftovers | — | per-Draft monitor via `lockFor` | — | — |
| in-memory only | `DraftCheckpoints.STATES` `:40` (validity = log size:mtime, `:44-55`); `DraftLifecycle.ACCESS` `:44`; `WorkingSetRoutes.CACHE` (key = sha256 of the log bytes, `WorkingSetRoutes.java:201`) | — | — | — | caches |

**Sweeps.** Hibernate (idle 1 h) and expire (idle 30 d) run **inside requests** — `DraftAdmission.maintain` `:107-121`, "no new
scheduler is started" (class note `:39`). Under N pods every pod runs them.

**Not an Investigation sidecar (out of scope, section 5):** the D-3 dataset index (`la-index/<dataset>/<mappingHash>/CURRENT`,
immutable `v<n>/` Parquet) and its pin ledger `pins.json` guarded by a `.pins.lock` **file lock** (`IndexPins.java:36-38`,
`:142-146`). Drafts only reference it (a pin per Draft; unpinned at promote `DraftPromote.java:237-242`).

## 3. Operations: append-only versus rewrite

| Class | Operations | Store requirement |
|---|---|---|
| **A. Append-only, never rewritten** | main log lines; main sets; members; references; header | idempotent-by-key insert; no UPDATE / DELETE; readers see a dense prefix |
| **B. Whole-record replace (relationship / workflow state)** | case-link, Alert Rule binding, pending request, Draft header | compare-and-swap on a version, or last-writer-wins where today's code is |
| **C. Whole-set swap** | Draft rebase (`replaceRebased`) | one transaction: delete Draft log + sets, insert the new ones, bump the Draft version |
| **D. Cross-scope atomic** | promote: N appends to main + `promoted.json` + delete Draft log/sets, **all or nothing** (rollback truncates the main log and deletes the new set files, `DraftPromote.java:207-231`, `:253-266`) | one transaction over main and Draft rows |
| **E. Close** | discard / expire / promote: marker first, then delete the evidence (sealed rows may hold personal data, `DraftStore.java:344-349`) | marker + delete in one transaction |
| **F. Housekeeping** | hibernate, expire, scratch recovery, listing index | idempotent conditional updates; scratch recovery disappears |

## 4. What the sealed hash and the replay guarantee actually require

Read from the code, not from the doc: there is **no hash chain across entries**. The guarantees are four, and each is byte-sensitive.

| # | Guarantee | Defined at | Byte requirement on the store |
|---|---|---|---|
| G1 | `workingSetHash` of a step = `sha256(canonical(state.toMap()))`, written INTO the log line | `InvestigationRoutes.java:829`; `DraftRebase.java:128`, `:144`, `:165` | the **log line verbatim** (it carries the hash and `roundTrip` form); the hash is computed from the in-memory state, not from stored bytes |
| G2 | The set file is `{"hash":h,"step":n,"workingSet":<canonical bytes>}`, and `DraftPromote.sealedSet` **re-hashes the stored bytes** between the head and the closing brace, and reuses the file only if they equal the sealed hash | `DraftPromote.java:54-71` | the **set text verbatim** — byte-identical, no normalisation, no key reorder, no whitespace change |
| G3 | `prefixHash(lines, k)` = sha256 over the first `k` lines, each followed by exactly one `\n`; it is `baseLogHash` (Draft header), `toBaseHash`, `draftLogHash` (four-eyes request) | `DraftStore.java:364-376`; used `DraftPromote.java:104`, `:142`, `:147`; `DraftRebase.java:188`, `:229-242` | `readLog` returns the exact lines, in step order, no blanks; the store may not re-encode them |
| G4 | Replay: re-fold the log, compare every `workingSetHash`; mismatch list is the audit answer | `InvestigationRoutes.java:693-702`; `DraftRoutes.java:360` | a log that cannot be edited in place (class A is append-only) |
| G5 | The Working Set relation cache is keyed by sha256 of the committed log bytes (a half-written last line is ignored) | `WorkingSetRoutes.java:188-201` | content-addressed, so it is already safe across pods — **but** it reads `log.jsonl` as a file; it must read through the seam |

**Consequence for Postgres (the decision that matters most): store log lines and set documents as `text` (or `bytea`), NEVER `jsonb`.**
`jsonb` reorders keys and drops whitespace, which breaks G2 and G3 silently while every ordinary test stays green. Pin it with a
mutation test (section 9). A line never contains a raw newline (canonical JSON escapes it), and a NUL cannot occur in a JSON
text, so `text` is safe (⚠ UNVERIFIED for a NUL inside a string value: canonical JSON writes it as the escape `\u0000`, which is
plain ASCII; confirm with a fixture).

## 5. Scope — what moves, what stays

| Item | Verdict | Why |
|---|---|---|
| header, main log, main sets, members, references, case-link, Alert Rule bindings, pending, templates | **moves** | all small or per-step documents; all multi-writer in a multi-pod world |
| Draft header, Draft log, Draft sets, markers, lifecycle (access time, hibernated) | **moves** | the Draft lock and promote / rebase atomicity are the hardest multi-pod problems |
| `drafts/index.json` | **disappears** | a listing is one `SELECT`; the index existed to avoid reading 50 headers from disk (`DraftIndex.java:12-18`) |
| `.fork-*` / `.rebase-*` / `.old-*` + `recover` | **disappears** | a transaction replaces stage-and-rename |
| `DraftCheckpoints`, `DraftLifecycle.ACCESS`, `WorkingSetRoutes.CACHE` | **stay per pod, in memory** | caches; validity must stop reading `size:mtime` of a file and read the Draft `version` + head step from the store |
| D-3 Parquet index (`la-index/`), DuckDB-per-Draft files | **stay on disk** | big, immutable, rebuildable; multi-pod placement is the scale-out plan's D4 (object store / shared volume), not this seam |
| D-3 pin ledger `pins.json` | **stays for now** (D-IS11) | a file lock is wrong on NFS, but moving it only helps if the Parquet versions it protects are shared too |
| Dossier export bundles, snapshots (`snapshots/`, `attachments.jsonl`) | **out** | not Investigation sidecars; the Dossier is computed from the log + sets on read (`DossierRoutes.java:119`, `:193`, `:213`) |

## 6. The seam

One host-free interface in `inspecto-la-core` (next to `DatasetProvider`, `CasePorts`), keyed by **ids and a scope**, never a Path.
`Scope` = `(investigationId, draftId | null)`.

```
InvestigationStore                                   -- obtained once per Space (not per request)
  // identity + immutable record
  boolean   create(id, headerJson)                   -- false if the id exists (409)
  Optional<String> header(id)         List<String> ids()
  boolean   createFork(id, headerJson, lines, sets)  -- all-or-nothing; false if id taken
  // the sealed log (class A)
  Head      head(Scope)                              -- {step, logHash?}  (cheap)
  List<String> log(Scope)             Optional<String> set(Scope, step)
  Appended  append(Scope, expectedHead, lineJson, setJson)      -- Conflict(actualHead) if head moved
  // members, references (class A)        -> appendMember(id, ...)   appendReference(id, key, line, max)
  // workflow records (class B)           -> put/get case-link, alert-rule binding, pending request
  // Drafts
  DraftHandle createDraft(id, draftHeader, spaceCap)           -- cap checked in the same tx
  closeDraft(Scope, kind=DISCARDED|PROMOTED|EXPIRED, marker)   -- marker + delete evidence, one tx, idempotent
  Optional<Promoted> promote(Scope main, draftId, expectedMainHead, expectedDraftLogHash, lines, sharedSets, marker)
  Replaced  replaceDraft(Scope, expectedDraftVersion, expectedMainHead, header, lines, sets)   -- rebase swap
  touch(Scope) / hibernate / rehydrate / listDrafts(id) / sweep(now, hibernateAfter, expireAfter)
```

**Atomicity and ordering (the contract every implementation must pass):**

1. `append` is one atomic act: either the step **and** its set exist, or neither (Postgres: one transaction). The FS
   implementation keeps today's log-first order for the MAIN log (`SnapshotStore.java:238-242`: a crash leaves a step whose set
   replay can recompute) and the Draft's undo-on-failure (`DraftStore.java:322-342`); the contract states only the weaker common
   guarantee — **no set without its step; a failed append leaves the head unchanged for a Draft**; Postgres is stricter.
2. Steps are **dense and 1-based**; `log()` returns them in step order. `append` carries `expectedHead` (the step the caller
   evaluated against); a mismatch is a `Conflict`, which the route maps to the 409 it already uses for "the main log moved".
3. `promote` and `replaceDraft` carry **preconditions instead of holding a lock across the compute**: today promote computes
   under both monitors (`DraftPromote.java:130-231`), rebase computes outside the main lock and re-verifies inside
   (`DraftRebase.java:224-242`). The store verifies `expectedMainHead` (step + `prefixHash`) and the Draft's identity inside
   its commit and answers a typed conflict; the routes keep their message texts. This **replaces `lock(Path)` entirely** — no lock
   is exposed, so no implementation can be asked to provide a distributed one.
4. `closeDraft` is idempotent (second call returns false, as `markPromoted` / `markDiscarded` do today, `DraftStore.java:269-275`).
5. `listDrafts` hides closed Drafts exactly as `openIds` does (`DraftStore.java:159-168`).
6. The sweep takes `now` as a parameter (today's `DraftLifecycle.clock` test seam, `:37`).

**Who holds the lock — today and after.**

| Critical section | Today (JVM monitor on a Path) | Postgres replacement |
|---|---|---|
| append to main log | `lock(inv dir)` | PK `(investigation_id, step)` + `append` inserts step = `expectedHead + 1`; a concurrent same-step insert is a unique violation = `Conflict`. No lock held outside the statement. |
| members append (last-lead rule is read-then-write, `InvestigationMembers.refusal`) | same lock | one tx: `SELECT ... FOR UPDATE` the Investigation row, fold, check `refusal`, insert |
| create / fork / reorder | `lock(investigations root)` `:256`, `:569`, `:674` | `INSERT ... ON CONFLICT DO NOTHING`, rows-affected = 0 means taken |
| Draft append | `lock(draft dir)` | `la_draft.version` check in the same tx as the insert |
| promote (main, then Draft; order fixed) | two nested monitors | one tx: `FOR UPDATE` on the Investigation row, then the Draft row (same order everywhere), verify preconditions, insert main rows, close Draft |
| rebase commit | two nested monitors after an unlocked compute | optimistic: `UPDATE la_draft SET version = version + 1 WHERE id = ? AND version = ?`; 0 rows = 409; delete + insert of Draft log/sets in the same tx |
| Draft cap (50 / Space) | `synchronized (DraftAdmission.CAP)` `DraftRoutes.java:175` | `FOR UPDATE` on the Space's `la_space` row, count open Drafts, insert (D-IS9) |
| references | global `REFERENCES_LOCK` | `FOR UPDATE` on the Investigation row, count, insert |

**Why a row lock and not an advisory lock or a lease.** The platform already signed against advisory locks and for leases
(`enterprise-scale-out-plan.md` D5: an advisory lock dies with a recycled pooled connection; `DbRunLease.java:27-47`). That
objection is about a lock that must **outlive a connection** (a pipeline run). Every LA critical section here is **one short
transaction**; a row lock lives exactly as long as the transaction, so it cannot outlive or be orphaned by the connection, and
needs no heartbeat, TTL or fencing token. The long computations (rebase plan, promote fold) are unlocked and optimistic.
**Fencing for the Draft main-lock** is therefore the compare-in-commit: a pod that stalls mid-compute and resumes after another
pod promoted or rebased fails the `expectedMainHead` / `version` check and answers 409 — it can never write stale rows. ⚠
UNVERIFIED: the row-versioning convention another lane is adding to the platform now (named in the brief) was not visible to
this lane; D-IS4 (answered) makes `la_draft.version` follow that convention's name and error mapping.

## 7. Postgres shape (sketch, for review — not DDL to copy)

Schema-per-Space (`enterprise-scale-out-plan.md:214`, "P3 schema-per-Space URL wiring"; ⚠ UNVERIFIED whether that wiring has shipped).

| Table | Key | Columns of note | Notes |
|---|---|---|---|
| `la_investigation` | `id` | `header text`, `head_step int`, `created_at` | the row the commit locks; header immutable |
| `la_log` | `(investigation_id, step)` | `line text` | **append-only**: `REVOKE UPDATE, DELETE` or a trigger that raises |
| `la_set` | `(investigation_id, step)` | `doc text`, `hash text` | append-only; TOAST compresses it |
| `la_member` | `(investigation_id, seq)` | the `members.jsonl` fields as columns | append-only |
| `la_reference`, `la_pending`, `la_case_link`, `la_alert_binding`, `la_template` | natural keys | `doc text`, `version` where class B | small |
| `la_draft` | `(investigation_id, draft_id)` | `header text`, `version bigint`, `state` (open/hibernated/discarded/promoted/expired), `last_access`, `marker text`, `base_step` | replaces header, markers, `accessed.json`, `hibernated.json`, `index.json` |
| `la_draft_log`, `la_draft_set` | `(investigation_id, draft_id, step)` | `line` / `doc text` | **deletable** (close, rebase) — deliberately NOT the same tables as main, so the main tables can be append-only by privilege |
| `la_space` | `space` | `open_drafts int` or none | only if D-IS9 takes the counter option |

Id ordering: `ids()` is `Comparator.naturalOrder()` on the string (`SnapshotStore.java:205`) — use `ORDER BY id COLLATE "C"`.

## 8. Failure modes

| Failure | Today (FS) | With the seam on Postgres |
|---|---|---|
| Crash between log line and set | step without set; replay recomputes (`SnapshotStore.java:238-242`) | impossible — one tx |
| Crash mid-promote | rollback truncates main log and deletes set files (`DraftPromote.java:228-231`); a crash BEFORE the rollback leaves partial main steps (⚠ UNVERIFIED: no recovery pass found) | tx rolls back; no partial steps |
| Crash mid-rebase | `.rebase-*` / `.old-*` leftovers; `recover` re-moves or sweeps them (`DraftStore.java:201-235`) | tx rolls back; no scratch |
| Two pods append the same step | **undefined** (monitor is per JVM) | PK violation → `Conflict` → 409 |
| Concurrent promote vs rebase on one Draft | serialised in one JVM | row lock order main → Draft; loser sees closed / moved head → 409 |
| Concurrent promotes of two Drafts onto one main | serialised; the second sees `base != size` → "must rebase" (`DraftPromote.java:139-141`) | same check inside the tx |
| Pod stalls, resumes after another pod changed state | n/a | stale `expectedHead` / `version` → 409, never a stale write |
| Postgres unreachable | n/a | **fail closed**: the store throws; routes answer 503, never fall back to FS (a silent FS fallback on one pod forks the evidence) |
| Sweep on two pods at once | n/a | conditional `UPDATE ... WHERE state = 'open' AND last_access < ?`; second affects 0 rows; expire's marker + delete is one tx and idempotent |
| Oversized set (>1 GB `text` limit) | file system limit | `append` refuses with a typed error; S0 measures how close real sets come (⚠ UNVERIFIED sizes) |
| Replica lag | n/a | out of scope: reads and writes use the primary (state this in the deployment doc) |

## 9. Test strategy

Model: `inspecto-ops` `ObjectStoreContractTest` — one contract run against the in-memory / FS implementation always and the
Postgres one when `INSPECTO_TEST_PG_URL` / `-Dinspecto.test.pg.url` is set, **skipping per test** (an `assumeTrue` in
`@BeforeAll` would drop the class from surefire totals), throwaway schema per run (`ObjectStoreContractTest.java:21-40`).
A real Postgres 18.6 is on `localhost:5432` for the build lane.

| Layer | Cases |
|---|---|
| Contract (FS + PG, same class) | create 409; append dense order; `Conflict` on a stale head; two threads racing one step → exactly one wins; fork all-or-nothing; members last-lead rule under a race; references cap; close idempotent; promote all-or-nothing under an injected failure; rebase swap restores on injected failure; cap race with N threads |
| Byte-identity (the guarantee tests) | lines and sets with non-ASCII, a 1 MB line, reordered keys, extra whitespace: read-back equals write **byte for byte**; `prefixHash` over the store's lines equals `prefixHash` over the written list; `DraftPromote.sealedSet`-style re-hash accepts a set read back from the store |
| Mutations that must go red | store as `jsonb`; trim/normalise on read; drop the `expectedHead` check; swap the lock order main/Draft; skip the marker-before-delete order |
| Fault injection | replaces `DraftStore.mover` / `promoteHook` (`DraftStore.java:53-57`, `:87`) with a store-level hook so the same test drives both implementations |
| Multi-pod | two JVMs (or two `InvestigationStore` instances over two connection pools) against Postgres 18.6: N appends each, a promote vs a rebase, a sweep vs an append; assert no lost step, no duplicate, replay green |
| Routes | the existing real-HTTP tests (`InvestigationRoutes`, `DraftRoutes` families) re-run unchanged on the FS implementation in S1 — that is the regression net for the de-pathing |

Run per slice with `-pl <module> -Dtest=A,B` (commas), per the unit-test-per-change rule in `CLAUDE.md`; the full reactor only at the
S1 and S6 push points (S1 touches shared seams).

## 10. Edition, packaging, wiring, compatibility

* **Packaging (recommended).** The port + the FS implementation (a thin wrapper over today's `SnapshotStore` / `DraftStore`
  logic) live in `inspecto-la-core`; the Postgres implementation is a **new optional module** `inspecto-la-store-pg`, discovered
  through `ServiceLoader` (the project's SPI style), so Personal / Professional bundles carry no extra jar and no JDBC driver
  they do not already have. JDBC helpers come from `inspecto-util` (`JdbcDrivers`, `AbstractJdbcStore` live there, so the
  `inspecto-la-*` dependency allow-list needs only the new module's own entry in `tools/check-module-deps.mjs:32-36`).
  `tools/bundle-modules.mjs:59-62` stages the LA jars `from: 'professional'`; the new jar is staged at the Enterprise tier
  (⚠ UNVERIFIED that `from: 'enterprise'` is a value the staging script accepts — check `bundle-modules.mjs`) and by default in the
  PREVIEW edition.
* **Gating.** FS stays the default for every edition. Selecting Postgres is a deployment setting mirroring `-Dobjects.backend=memory|db`
  (`InMemoryObjectStore.java:12`, `LinkStore.java:18`): `investigations.backend=fs|db` + a JDBC URL. Postgres is already
  mandatory for Enterprise (`enterprise-scale-out-plan.md:456`), so multi-pod LA is an Enterprise capability by construction. No new capability
  string and no new route: nothing here changes an HTTP route, so no `openapi-v1.json` entry and no four-gate work.
* **Compatibility.** Breaking changes are free (nothing after 3.x is in production). **No FS-to-Postgres import tool**: a Space
  that switches backend starts empty; its old Investigations remain readable only by the FS backend. One backend per Space —
  no dual write, no hybrid. Existing tests that call `new SnapshotStore(root)` migrate in S1.
* **Audit events** (`emit` after the act, `InvestigationRoutes.java:2169`) are unchanged: they go to the event store, not here.

## 11. Decision register — ANSWERED 2026-10-04 (operator: every recommendation accepted as written)

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **D-IS1** | Scope: which files move? | (a) everything per-Investigation incl. Drafts and sets; (b) log, members, Drafts only, sets stay on a shared volume; (c) main-only, Drafts later | **(a)**. Sets are written in the same act as their log line (G2); splitting them re-creates the crash window the transaction removes. Parquet index stays out regardless. |
| **D-IS2** | Where do sealed sets live on Postgres? | (a) `text` rows in `la_set` (TOAST); (b) Postgres holds `(step, sha256, uri)`, the bytes live in an object store; (c) do not store, recompute | **(a)**, guarded by a per-set size limit and measured first (S0). (c) is wrong: the Dossier and promote read the stored bytes (`DossierRoutes.java:213`, `DraftPromote.java:54`). (b) only if S0 shows sets near the 1 GB field limit. |
| **D-IS3** | Seam shape | (a) expose `withLock(scope, fn)`; (b) preconditioned atomic operations, no lock exposed | **(b)** — no lock to distribute; the routes already do compute-then-verify (`DraftRebase.java:224-242`). |
| **D-IS4** | Concurrency primitive | (a) short-tx `FOR UPDATE` row lock + PK + `version` column; (b) `pg_advisory_xact_lock`; (c) lease table with TTL + fencing token | **(a)**. (c) is for operations longer than a connection (D5, `DbRunLease`); none here are. **Operator 2026-10-04: the LA store adopts the convention the ObjectStore optimistic-lock lane is building now — the same names and mapping: a monotonic `long version` column, a typed conflict exception, answered as 409 CONFLICT through the error funnel.** (Convention itself not visible to this lane; S1 reads it before naming anything.) |
| **D-IS5** | Column type of log lines and sets | (a) `text`; (b) `bytea`; (c) `jsonb` | **(a)** `text` (greppable, UTF-8 by construction). **Never (c)** — breaks G2/G3 silently. |
| **D-IS6** | Definition of the log prefix hash | (a) keep byte-prefix sha256 (`DraftStore.prefixHash`); (b) switch to a chain hash `H_k = sha256(H_{k-1} || line_k)` stored per step | **(a)** now: both backends must agree and values already recorded in headers stay meaningful; (b) is a cheap follow-up (O(1) head compare) once S0 shows the O(n) read hurts. |
| **D-IS7** | Packaging | (a) port + FS in `la-core`, Postgres in a new optional `inspecto-la-store-pg`; (b) Postgres inside `la-core`; (c) inside `inspecto-ops` | **(a)** — keeps JDBC and the Postgres dependency out of Personal / Professional bundles; (c) would break the `la-*` dependency boundary. |
| **D-IS8** | Backend selection and tenancy | key `investigations.backend=fs|db`; Postgres tenancy: (a) schema per Space; (b) one schema, `space` column | **Key as written; (a)** to match the platform's schema-per-Space stance and make per-Space cleanup a `DROP SCHEMA`. |
| **D-IS9** | Draft cap (50/Space) and sweeps across pods | (a) cap under a `FOR UPDATE` on a per-Space row; sweeps as idempotent conditional updates in-request; (b) a scheduler / lease for sweeps; (c) per-pod cap | **(a)**. No new scheduler (today's choice, `DraftAdmission.java:39`); (c) would let N pods admit N x 50 Drafts. |
| **D-IS10** | Migration of existing FS Investigations | (a) none, switching starts empty; (b) one-shot importer; (c) dual-read | **(a)** (breaking changes are free). Revisit only if a real install is found with Investigations to carry. |
| **D-IS11** | The D-3 pin ledger (`pins.json`, `.pins.lock`) and index Parquet | (a) out of scope, documented as "needs a shared volume"; (b) move the ledger to Postgres now | **(a)**: the ledger protects Parquet versions that are not shared anyway (scale-out D4). File a row when multi-pod index serving is scheduled. |
| **D-IS12** | Promote's set reuse | (a) `INSERT ... SELECT` copy of the Draft's set rows into main; (b) content-addressed set blobs referenced by hash (O(1) promote) | **(a)** — server-side copy, no network round trip; matches the FS hard-link semantics' result (same bytes). (b) only if promote cost resurfaces (`LA-DRAFT-PROMOTE-COST-1`). |

## 12. Slices

| Slice | Content | Size | Gate (verify) |
|---|---|---|---|
| **S0** | Measure on the real data: set bytes per step at 10^4 / 10^5 entities, rows per Investigation, p50 of reading a 2,000-step log from Postgres 18.6. Decides D-IS2 (a/b) and D-IS6. | S (0.5 d) | numbers in this file |
| **S1** (DONE 2026-10-04: sections 13.2, 13.3) | **De-path + port on FS.** Define `InvestigationStore` + `Scope`; `FsInvestigationStore` wraps current logic; `Inv` carries the Scope not a Path; `lock(Path)` replaced by the precondition operations; `DraftCheckpoints` / `WorkingSetRoutes` read validity from `head()`; `geo-link` caller; delete `DraftIndex` use behind the port. **No behaviour change.** | **L** (about 15 production files, `InvestigationRoutes` 2,181 lines) | all existing LA route tests green unchanged; `-pl inspecto-la-core,inspecto-la-api,inspecto-geo-link` units, then the full reactor once |
| **S2** (DONE 2026-10-04: section 13.4; the FS half of the old S2 text was already in S1) | The same `InvestigationStoreContract` run on a SECOND implementation: `PgInvestigationStore` in the new optional `inspecto-la-store-pg`, on a real PostgreSQL. Absorbs the core of S3 and S4 (every port method, Drafts included) | M-L | 22 contract + 5 Postgres-only cases green on PG 18.6 (27 run, 0 skipped); the operator's mutation checks remain |
| **S3** (DONE 2026-10-04: sections 13.4, 13.5; the schema, members, references were in S2) | `inspecto-la-store-pg`: header, main log, sets, members, references; schema bootstrap; append-only privileges | M | contract green on PG 18.6 |
| **S4** (DONE: in S2; fault injection beyond the rollback case is S7) | Postgres Drafts: create, append, close, promote, rebase swap, cap, sweeps, lifecycle | **L** | contract + fault injection green |
| **S5** (DONE: in S2) | Class B records: case-link, Alert Rule binding, pending, templates | S | contract |
| **S6** (DONE 2026-10-04: section 13.5) | Wiring: `ServiceLoader`, `investigations.backend`, fail-closed 503, bundle staging, module-deps allow-list, OKF + EDITIONS + INDEX rows | M | `check-module-deps`, bundle build, docs guards |
| **S7** | Multi-pod proof: two-JVM race suite on PG 18.6 | M | section 9 multi-pod row |
| **later** | D-IS6 chain hash; D-IS11 pin ledger; D-IS12 content-addressed sets | — | demand-gated |

Order: S0 and S1 are independent; S2 follows S1; S3 to S5 follow S2; S6 and S7 last. S1 is the only slice that touches shared
seams — it is the one that needs the full reactor gate before a push.

## 13. Claims not verified — carried as S0 checks (S0 resolves each before S1 relies on it)

1. No test, build or Postgres run happened; every "today" statement is from reading source at the cited line.
2. "Two pods on a shared volume would corrupt the log" is inferred from the absence of any cross-process lock in the LA write path
   (grep of `synchronized`, `FileLock`, `ATOMIC_MOVE` in `inspecto-la-*`); the only file lock found is `IndexPins`.
3. `case-link.json` and Alert Rule binding writers take **no** lock (none seen at the cited functions; a caller may hold one).
4. A crash before `DraftPromote.rollback` runs may leave partial main steps; no recovery pass was found (only `DraftStore.recover` for Draft scratch dirs).
5. Whether schema-per-Space URL wiring and the platform optimistic-versioning convention have shipped is unknown to this lane.
6. `bundle-modules.mjs` staging tier value for an Enterprise-only optional jar (`from: 'enterprise'`) was not checked.
7. Set sizes are unmeasured; the 1 GB `text` field limit is Postgres' documented maximum, not a measured risk here.
8. NUL / invalid-UTF-8 safety of `text` for canonical JSON is argued, not tested.
9. `InvestigationRoutes.java:569` and `:674` are both `synchronized (lock(investigations root))`; which route each sits in (reorder at `:493`, a fork path before `:693`) was inferred from method boundaries.

### 13.1 S0 findings — measured / read 2026-10-04 (as-built; each answers the numbered claim above)

Method: `InvestigationStoreSizeBench` (`inspecto-la-api` test, gated `-Dinspecto.bench.s0=true`, prints `S0 ...` lines) over a
synthetic Working Set (msisdn-shaped ids, 2 links per entity, the real `InvestigationRoutes.setDoc` + `canonical`). JDK 27, Windows
11 local disk. `DraftConcurrencyBench` was NOT re-run (it needs a 5M-edge index build and measures concurrency, not set bytes).

| Claim | Finding |
|---|---|
| 7 — set sizes | **~291 bytes per entity** (with its 2 links). 1,000 entities = 0.29 MB; **10,000 = 2.9 MB; 100,000 = 29.1 MB** per sealed set file. The 1 GB `text` field limit is 34x away at 100,000 entities; a single set only nears it near 3.4 million entities. |
| 7 — O(state x steps) total | State growing linearly to N over S steps, every step sealing its full set: **10,000 entities / 100 steps = ~153 MB; 10,000 / 2,000 steps = ~3,061 MB; 100,000 / 100 steps = ~1,530 MB** of set files per Investigation. The per-FIELD limit is not the risk; the per-Investigation TOTAL is. D-IS2 stays (a) `text` rows, but this is the number that makes D-IS12 (b) (content-addressed sets) and a per-Investigation set budget worth scheduling: a 2,000-step Investigation over 10,000 entities is 3 GB in `la_set` TOAST. |
| 7 — log lines | An `expand` sealing R rows: 50 rows = 3.9 KB, 500 = 37 KB, **5,000 = 370 KB** per line (about 74 bytes per sealed row). 2,000 steps of 50-row expands = 5.95 MB log. Lines are far below any field limit. |
| 1 / D-IS6 — reading the log | FS `readLog` of a 2,000-step, 5.95 MB log: **p50 9.2 ms**; `DraftStore.prefixHash` over the same lines: **p50 16.8 ms** (O(n) in bytes). Both are cheap; **D-IS6 stays (a)** byte-prefix hash. The Postgres read p50 was NOT measured (no credentialed server for this lane, see claim 8); S3 owns it. |
| 8 — NUL in `text` | **Half resolved.** `canonical(Map.of("v","a<NUL>b"))` writes `{"v":"a\u0000b"}` — the six-character ASCII escape, no raw 0x00 byte (`S0 nul` line). So the string handed to a Postgres `text` column cannot contain a NUL character and the `text` choice stands. NOT run against Postgres: a localhost:5432 listener answers, but no URL/password was supplied to this lane and none was guessed. S3's contract test must include the fixture. |
| 9 — the two root monitors | **`:569` is in `reorder`** (`POST .../reorder`, method at `:493`; wraps `createFork(forkId, ...)`). **`:674` is in `instantiate`** (LA-23 template instantiation, method at `:595`; wraps `createFork(id, ...)`; `InvestigationTemplateRoutes#instantiate` calls it). `:256` is `create`. All three lock `lock(<investigations root>)`, a DIFFERENT monitor from the per-Investigation `lock(inv.dir())` every append takes, so a create and an append never exclude each other (harmless: different ids). The root monitor only serialises the check-then-move of `createFork` against another create / fork of the same id. |
| 3 — case-link / Alert Rule | **Confirmed lock-free.** `InvestigationCaseRoutes.write` (`:122`) calls `SnapshotStore.writeCaseLink` and `InvestigationMeasureRoutes` (`:176`) calls `bindAlertRule` with no `synchronized` / `lock(` anywhere in `inspecto-geo-link/src/main` or the case / reference / template routes. Both are whole-record temp + `ATOMIC_MOVE` replaces, i.e. last-writer-wins by design (class B). |
| 4 — promote crash recovery | **Confirmed: there is none.** `DraftPromote.rollback` (`:254`) runs only from the `catch` in `execute` (`:229`); it truncates the main log to its pre-promote byte length and deletes the new set files, best-effort (`IOException` swallowed). `DraftStore.recover` (`:201`) handles ONLY `.rebase-*` / `.old-*` / `.fork-*` scratch directories. A JVM death between the first main append and `markPromoted` leaves partial main steps AND an OPEN Draft; nothing on startup or in `DraftAdmission.maintain` repairs it, and a retry meets "must rebase" because the main head moved. Pre-existing; S1 keeps it (behaviour-preserving); Postgres removes it structurally (one transaction). |
| 2 — "two pods corrupt the log" | Unchanged: inferred from the absence of any cross-process lock; not demonstrated with two JVMs (S7 owns the proof). |
| 5 — convention + schema-per-Space | **Convention shipped** (`54ea82bcf`): `inspecto-ops` `ObjectVersionConflictException(objectId, expectedVersion)` — a `RuntimeException` (deliberately not `IllegalStateException`, which routes map to 422), thrown when the stored row is at a different version; `ObjectRoutes#scoped` answers it `409 CONFLICT_STALE_VERSION`. The LA port adopts the same shape (S1: `InvestigationVersionConflictException(investigationId, expectedHead)`). Schema-per-Space URL wiring: still unverified (S6). |
| 6 — bundle staging tier | Not checked (S6). |

### 13.2 S1 as built — vertical 1 (2026-10-04): the port, the FS implementation, and everything that is not a Draft

**Shipped (all on the filesystem, behaviour-preserving).** `inspecto-la-core`: `InvestigationStore` (the port), `InvestigationStore.Scope` (`investigationId`, `draftId | null`), `InvestigationVersionConflictException`, `FsInvestigationStore` (a thin wrapper over `SnapshotStore` / `DraftStore.appendStep`, so every byte written is what they wrote before), `InvestigationStores.of(writeRoot)` (the single place a backend will be chosen, S6). Port surface: `create` / `header` / `ids` / `createFork`; `version(Scope)` / `log(Scope)` / `set` / `append(Scope, expectedVersion, step, line, set)`; `members` / `appendMember(expectedCount)`; `appendReference` / `references`; Alert Rule binding, pending, Case link, template. Convention adopted from the ObjectStore lane (`54ea82bcf`): a monotonic `long version` (a log's version is its entry count), a typed non-`IllegalStateException` conflict, `409 CONFLICT_STALE_VERSION` at the edge when a writer keeps losing (`InvestigationRoutes.untilWon`, `InvestigationMemberRoutes`; 20 attempts).

**Behaviour decisions to know.**
- `appendOpOn` and `undoOn` no longer hold a route monitor across read-evaluate-write: they run the whole attempt, `append` carries the version read, and a lost race re-reads and re-evaluates (same observable result as the old monitor: concurrent writers land in turn). A sealed `expand` re-reads on a retry (budget and audit unchanged: nothing is persisted before the append).
- The three `synchronized (lock(investigations root))` sites (`create`, `reorder`, `instantiate`) are now inside `FsInvestigationStore.create` / `createFork`. The `jail` helper is gone from the routes: the store refuses an id that would escape it (`IllegalArgumentException`, the route answers the same 403).
- `FsInvestigationStore.log()` returns COMMITTED lines only (a trailing fragment with no newline is a line being appended, ignored), so `WorkingSetRoutes.relation`'s cache key is built from `log(scope)` lines + `\n`: identical bytes to the old file read, now backend-neutral (G5).
- ONE monitor map: `InvestigationRoutes.lock(Path)` delegates to `FsInvestigationStore.monitor`, so the still Path-keyed compound sections exclude the port's own writers.

**Tests.** `InvestigationStoreContract` (abstract, 15 cases: create 409, verbatim header, fork all-or-nothing, dense append, byte identity incl. 1 MB line / non-ASCII / reordered keys / NUL escape, sealed `workingSetHash` + `prefixHash` + `sealedSet`-style re-hash round trip, stale version writes nothing, 16 writers race one step = exactly one wins, 8 x 25 retrying writers lose none and leave a dense log, members conditional + race, reference cap race, workflow records) run by `FsInvestigationStoreContractTest` (+ files-on-disk byte identity vs the legacy writers, torn last line, Draft scope, escaping ids). Existing suites re-run with plumbing-only edits (`new SnapshotStore(root)` to `InvestigationStores.of(root)`; `readLog` to `log(Scope)`): la-core, la-api, geo-link ControlApi LA classes, 390 tests green.
**⚠ The precondition mutation check (drop the `expectedVersion` comparison, expect `aStaleVersionWritesNothing` / `racingOneStepExactlyOneWriterWins` red) was NOT run**: the harness refused the mutation edit as a security-test removal. It is owed (run it by hand: change `actual != expectedVersion` to `false &&` in `FsInvestigationStore.append`, expect those tests red).

**Superseded by 13.3:** the Draft vertical, `decide`, the masking key and the `SnapshotStore` cleanup (originally the remaining checklist here) are done.

### 13.3 S1 as built — vertical 2 (2026-10-04): Drafts, `decide`, masking key, cache identity, crash recovery. S1 is COMPLETE.

**The port now covers Drafts.** Added to `InvestigationStore`: `createDraft` (D17 one live Draft per actor, D21 Space cap and the create are ONE act, outcomes `CREATED` / `ID_TAKEN` / `ACTOR_HAS_LIVE` / `SPACE_FULL`), `draftHeader` / `draftHeaders` (the listing: `DraftIndex` is now an FS detail behind it) / `openDraftIds` / `openDraftCount` / `draftState` / `draftExpired` / `discardMarker` / `promoteMarker` / `draftSet`, `touchDraft` / `rehydrateDraft` / `draftLastAccess` / `draftIdle` / `hibernateDraft(after)`, `closeDraft(idleAtLeast, markerFromOwnLines)` (marker first, evidence deleted, idempotent: discard AND expiry), `promoteDraft` and `replaceDraft`, `recoverDrafts`, `replacePending` (class-B compare-and-set), `maskKey`, `cacheKey(scope)` / `logToken(scope)`. `Inv.DraftRef` no longer carries a Path; `DraftAdmission.CAP` and every `lock(Path)` are gone from `inspecto-la-api` (`InvestigationRoutes.lock`, `DraftAdmission.CAP`, `requireRoom` deleted); the monitors live in `FsInvestigationStore`.

**promote / rebase keep their shape, now with preconditions.** `DraftPromote.execute` computes EVERYTHING outside any lock (checks, re-fold, sealing; the `sealedSet` re-hash now runs over the set text the store hands back, same head/tail/`sha256` rule) and calls `promoteDraft(expectedMainVersion, expectedMainHash, expectedDraftLogHash, lines, sets, marker)`; the store re-verifies main (`prefixHash` of the whole main log) and the Draft's own log hash inside its commit (main monitor, then Draft monitor: the one order) and answers `InvestigationVersionConflictException` (route: 409) / `DraftClosedException`. A `null` entry in `sets` means "seal the Draft's own set of that step as it is" (hard link: LA-DRAFT-PROMOTE-COST-1 kept). `DraftRebase.commit` keeps its two-phase shape (verify outside, re-verify inside) via `replaceDraft`; the index pins are taken before the swap and put back on any failure (`restorePins`), as before. `DraftStore.prefixHash` is unchanged and still the only byte definition.

**`decide` has no lock.** The status moves `pending -> approved` by `replacePending(expectedRaw, claimedJson)` BEFORE the authorised append; a second decider (or a retry) loses the CAS and is answered 409 `request '..' is already approved`. The append retries on a lost log race (`untilWon`); if the authorised act fails before anything was appended the claim is handed back (`replacePending(claimed -> original)`), and once the append has landed it never is. The step number is written to the record afterwards, as before. Test: `ControlApiInvestigationFourEyesRaceTest` (6 approvers x 4 rounds over real HTTP: exactly one 200, five 409, one log step per request, replay green); `replacePendingIsACompareAndSetAndExactlyOneRacerWins` in the contract. A retry loop without the CAS would find the request still `pending` (status was written after the append) and append twice.

**Promote crash recovery (the S0 gap) is closed.** `promoteDraft` writes `promoting.json` in the Draft (`from`, `to`, hash of the new lines, the marker text) BEFORE touching main. `recoverDrafts` (run from `DraftAdmission.maintain`, i.e. on every Draft list / open / fork) finishes or undoes it, idempotently: every main step `from+1..to` present and matching the recorded hash = COMPLETE it (`markPromoted` with the recorded marker); any other state = truncate main back to its first `from` lines, delete the stray sets, drop the intent; the Draft stays open and can be promoted again. A normal failure still rolls back in the `catch` and removes the intent. Tests (FS, injected failure; an `Error` stands in for the process dying, which the rollback `catch` does not see): `aFailedPromoteLeavesTheMainLogAsItWasAndTheDraftOpen`, `aPromoteInterruptedMidWayIsRolledBackByRecovery`, `aPromoteInterruptedAfterTheLastStepIsCompletedByRecovery`. New fixed loader file `promoting.json` is allow-listed in `ImportLoaderInventoryTest` (`NOT_CONFIG`).

**Cache identity moved behind the port.** `DraftCheckpoints` (state + verified base) and `WorkingSetRoutes.CACHE` are keyed by `store.cacheKey(scope)` and valid while `store.logToken(scope)` is unchanged (FS: the log file's `size:mtime`, exactly the old rule; Postgres: Draft version + head). The Path-keyed `DraftCheckpoints` methods remain for the la-core unit tests. The per-Investigation mask key is `maskKey(id)` (`MaskTokens` file `mask.key` on the FS, minted once, race-safe); it is a SECRET and the section 2 inventory now lists it as a sidecar.

**`SnapshotStore` cleanup.** Its Investigation methods moved verbatim into the package-private `FsInvestigationLayout` (an implementation detail of `FsInvestigationStore`); `SnapshotStore` is snapshots + attachments only. `DraftConcurrencyBench`, `DraftPromoteCostTest`, `DraftRebaseCostTest` seed through the port (`createDraft`, `append(Scope.draft ...)`); tests that tamper with set files on disk reach the directory through `FsInvestigationStore.investigationDir`, which is not part of the port.

**Contract extended (29 cases on the FS):** Draft create (D17, cap, write-once id, verbatim header), cap race (12 creators, cap 3: exactly 3), Draft log dense + stale version writes nothing + 6x15 racing writers, promote byte identity (verbatim lines, shared and re-sealed sets, Draft closed, evidence gone), promote refused when either log moved (nothing written), two Drafts promoted onto one main (exactly one wins), `replaceDraft` within its preconditions, `closeDraft` idempotent, `replacePending` CAS race, mask key stable under a race. **The precondition mutation checks are the OPERATOR's** (the classifier refuses them): drop the `expectedVersion` / `expectedMainVersion` / hash comparisons in `FsInvestigationStore.append` / `promoteDraft` / `replaceDraft` and expect `aStaleVersionWritesNothing`, `racingOneStepExactlyOneWriterWins`, `promoteIsRefusedAndWritesNothingWhenEitherLogMovedUnderIt`, `twoDraftsPromotedOntoOneMainExactlyOneWins` red.

**Known deviations (deliberate, behaviour in the rare race only):** a fork now pins the index before the store judges D17 / the cap, so a refused fork pins then unpins (it used to refuse first); promote's and rebase's 409 for "the main log or the Draft moved while this was computed" is one message for both causes (rebase still distinguishes them by re-reading the head); `closeDraft` for an expiry builds its marker from the log under the Draft's monitor but reads the idle time just before.

**Not done here (belongs to S2+):** the full-reactor gate before a push; the operator's mutation checks; the Postgres implementation (S3 to S5) must also implement `recoverDrafts` as a no-op (a transaction cannot leave a partial promote) and `logToken` as Draft version + head.

### 13.4 S2 as built (2026-10-04): the contract on a second implementation, `PgInvestigationStore`

**Shipped.** New optional module `inspecto-la-store-pg` (D-IS7): `com.gamma.la.store.pg.PgInvestigationStore implements InvestigationStore` over plain `java.sql` (a `ConnectionSource` supplier plus a schema name; the driver is the host's). Built only under `edition-enterprise` and `edition-preview`; **not staged in any bundle, not discovered by `ServiceLoader`, not returned by `InvestigationStores.of`** (all S6). `tools/check-module-deps.mjs` has its `ALLOWED` entry (la-core's closure plus la-core) and the pom carries the matching enforcer rule. `inspecto-la-core` now publishes a test-jar so the contract is shared, not copied. No new dependency coordinate (the pgjdbc driver is test scope, declared in the owning module because test scope is not transitive), so `tools/dependencies.lock` is unchanged; no `jdbc:duckdb:` literal.

**Schema (one schema per Space, name validated against `[a-z_][a-z0-9_]{0,62}`; created idempotently at construction):** `la_space` (one row, the cap lock), `la_investigation` (`id`, `header` text, `mask_key` bytea, `case_link` text), `la_log (inv, draft, seq, line text)` and `la_set (inv, draft, step, body text)` (`draft = ''` is the main log; a Draft's own rows carry its id), `la_member`, `la_reference` (unique `(inv, key)`), `la_alert_binding`, `la_pending`, `la_template`, `la_draft (inv, id, header, actor, state, marker, last_access timestamptz, version bigint)`. Every line, set, header and marker is `text` (D-IS5; a test reads the column types back and writes duplicate keys, odd spacing and `1.0e0` to prove `jsonb` would have changed them).

**Concurrency, as decided (D-IS3, D-IS4).** Every port method is ONE short transaction; the preconditions are checked inside it. Writers take `SELECT ... FOR NO KEY UPDATE` on the Investigation row (or on the Draft row for a Draft's own log). **Deviation from the register's wording `FOR UPDATE`, deliberate:** a Draft append inserts log rows, whose foreign key takes `FOR KEY SHARE` on the Investigation row; with `FOR UPDATE` a promote (Investigation row, then Draft row) and a Draft append (Draft row, then the key share) form a deadlock cycle. `FOR NO KEY UPDATE` still excludes every other writer and does not conflict with a key share. Lock order everywhere: Space row, Investigation row, Draft row. No advisory lock anywhere. A log line and its set are one commit (a failed set insert rolls the line back: a test pins it). `replacePending` is one conditional `UPDATE ... WHERE body = <expected>` (a loser waits on the row, re-reads, no longer matches). `maskKey` is one `UPDATE ... WHERE mask_key IS NULL` then a read. The Space cap (D-IS9) is checked and the seat taken under the `la_space` row lock.

**What differs from the filesystem store, on purpose.** `recoverDrafts` is a no-op (a transaction cannot leave a partial promote or a scratch directory). `logToken` is the log's entry count for main and `version:state` for a Draft (the `version` column moves on append, rebase swap and close). A promote's "seal the Draft's own set" is a server-side `INSERT ... SELECT` (D-IS12 a), not a hard link; a missing Draft set is an `IOException`. `touchDraft` writes at most every five minutes (same throttle as `DraftLifecycle.PERSIST_EVERY`). A write to an Investigation that has no header row is an `IOException` (the filesystem store would create the directory); no caller does it. An unreachable database is an `IOException` (route: 503), never a fallback.

**Tests.** `PgInvestigationStoreContractTest` (in package `com.gamma.la.core`, because the contract is package-private) extends the unchanged 22-case `InvestigationStoreContract` and adds 5: text not jsonb, a failed append leaves no line without its set, `logToken` moves with every change, idle / hibernate / rehydrate / expire on the injectable clock, header listing minus `baseLogHash` / `rebases[]`. **27 run, 0 failures, 0 skipped on PG 18.6** (the FS contract run is 29: the same 22 plus 7 directory-only cases). Without `INSPECTO_TEST_PG_URL` all 27 SKIP (and the count shows). Command: `MAVEN_OPTS="-Duser.timezone=Asia/Kolkata"`, env var `INSPECTO_TEST_PG_URL`, `mvn -o -B test -Pedition-enterprise -pl inspecto-la-store-pg -am -Dtest=PgInvestigationStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0` (the three traps of `PostgresStateStoreTest`: env var not `-D`, `forkCount=0`, the driver in the owning module).

**Operator-run precondition mutation checks (the classifier refuses an agent editing the check; run each by hand, expect the named cases RED, then revert).** In `PgInvestigationStore`: (1) `append`: change `if (actual != expectedVersion)` to `if (false)`: `aStaleVersionWritesNothing`, `racingOneStepExactlyOneWriterWins`, `aDraftLogAppendsDenselyAndAStaleVersionWritesNothing`. (2) `appendMember`: same change: `membersAreAppendOnlyAndConditionalOnWhatTheCallerRead`, `racingMemberAppendsExactlyOneWinsPerCount`. (3) `verifyPreconditions`: drop the main-hash term `|| !DraftStore.prefixHash(main, main.size()).equals(expectedMainHash)`: the third refusal in `promoteIsRefusedAndWritesNothingWhenEitherLogMovedUnderIt`; drop the whole `if (main.size() != ...)` or the own-log `if`: that case and `twoDraftsPromotedOntoOneMainExactlyOneWins` / `replaceDraftSwapsHeaderLogAndSetsWithinItsPreconditions`. (4) `lockInvestigation` / `lockDraft`: remove ` FOR NO KEY UPDATE` from the SQL: the racing cases go flaky-red (a lost lock is a race, so run each 3 times). (5) `createDraft`: remove the `la_space` `FOR UPDATE` line: `aDraftRacingCreatorsNeverExceedTheCap`. (6) `replacePending`: drop `AND body = ?` (and its argument): `replacePendingIsACompareAndSetAndExactlyOneRacerWins`. (7) `maskKey`: drop `AND mask_key IS NULL`: `theMaskKeyIsMintedOnceAndStable`. (8) `append`: insert the set before the line and swallow the set error: `aFailedAppendLeavesNoLineWithoutItsSet`. Whole-feature mutants are better than single-clause ones (a one-clause mutant can be red for the wrong reason: read the failing values).

**Deferred, not done.** (a) Append-only privileges (the S3 bullet: a role that can INSERT but not UPDATE/DELETE the log) and the per-set size limit (D-IS2). (b) Selection and wiring: `ServiceLoader`, `investigations.backend=fs|db`, the fail-closed 503, bundle staging, OKF / EDITIONS / INDEX rows (S6). (c) Fault injection beyond the rollback case, and the two-JVM race suite (S7): the 16-writer races here run in ONE JVM over separate connections, which proves the row lock, not multi-pod. (d) Concurrent first-start bootstrap of one empty schema is retried three times, not serialised (S6). (e) A full-reactor run: only `-pl inspecto-la-store-pg -am` with the two contract classes was run. (f) Throughput: the 22 contract cases take about 60 s on a local Docker PG (a connection per call, no pool); a pooled `ConnectionSource` is S6.

### 13.5 S3 remainder + S6 as built (2026-10-04): selection, pool, fail-closed, bundle, append-only

**S3, S4, S5 were absorbed by S2** (every port method, Drafts and the class-B records are in `PgInvestigationStore`); what the S3 row still owed was the append-only protection and the serialised bootstrap, both built here. **S6 shipped in full** except what is listed under "Decisions owed".

- **Selection (D-IS8).** `-Dinvestigations.backend=fs|db`, default `fs`, read per call by `InvestigationStores.of(writeRoot)`. `db` finds an `InvestigationStoreProvider` (new la-core interface, `ServiceLoader` through the fail-closed `SpiSlot`) and hands it the Space id and the connection. Connection keys `investigations.db.url` / `.user` / `.password`, each falling back to `inspecto.db.url` / `.user` / `.password` (the platform's shared pair); the password may be a `${ENV:..}` reference. The Space id is the directory above `config` in the write root (`spaces/<id>/config`), `default` for the legacy single-tenant root; the schema is `space_<id with - as _>`, the name `OperationalDb.schemaFor` gives (re-implemented in the module, which cannot see core; a drift would put one Space's evidence in a schema the platform's other stores do not use, which is harmless but surprising).
- **Fail closed, one answer.** `503 CAPABILITY_UNAVAILABLE` for: an unknown backend value, `db` with no module in the bundle, a broken or doubled provider, no URL, a non-PostgreSQL URL, a database that cannot be reached when the Space is first used, and (inside `PgInvestigationStore.tx`) a SQL failure of SQLSTATE class 08 / 28 / 53 / 57P or a pool that cannot lend (`SQLTransientConnectionException`). The store throws `ApiException` for that last case, because the routes' `catch (IOException)` sites would otherwise turn it into a 500 (the control plane's error boundary answers a bare `IOException` 500). Any other SQL failure stays an `IOException`. Nothing about a failure is cached (no store, no pool), so the next request retries; there is no filesystem fall-back anywhere.
- **Pool.** One HikariCP source per URL + user, from `JdbcDrivers.source` (`-Ddb.pool.size`, default 10), shared by every Space; one `PgInvestigationStore` per Space schema, cached by the provider. `PgInvestigationStore` borrows through the reentrant `ConnectionSource.with`, so one operation is one connection. The contract now runs on a **4-connection pool** against 16-writer races (a nested borrow would starve it and time out): the Postgres class went from about 60 s to about 13 s.
- **Serialised bootstrap.** The DDL runs in one transaction that first takes `pg_advisory_xact_lock(hashtext('inspecto-la-store:' || schema))`; the three-attempt retry is gone. A transaction-scoped advisory lock cannot outlive its connection, so the D-IS4 objection to advisory locks (a lock that must outlive a connection) does not apply. Test: 8 stores built at once over one empty schema, all succeed.
- **Append-only (the S3 bullet), by trigger, not privilege.** The main log rows (`la_log` / `la_set` with `draft = ''`), `la_member` and `la_reference` refuse UPDATE and DELETE, and all four refuse TRUNCATE, with SQLSTATE `23000` (`integrity_constraint_violation`). A Draft's own rows (`draft <> ''`) stay deletable, because a close and a rebase delete them; that is why the row triggers carry a `WHEN (OLD.draft = '')`. A trigger and not `REVOKE UPDATE, DELETE` because the application connects as the table owner, which a REVOKE does not bind (and `la_log` holds both main and Draft rows, so a privilege cannot split them). Honest limit: it stops the application and a stray statement, not a DBA, who can drop the trigger or the schema (dropping the schema is the intended per-Space cleanup). Needs PostgreSQL 14 (`CREATE OR REPLACE TRIGGER`).
- **Packaging.** `tools/bundle-modules.mjs`: `inspecto-la-store-pg`, `from: 'enterprise'` (a value the script already accepts; Preview takes everything), counts Enterprise 19, Preview 19. `inspecto/package.ps1`: built in `$modules`, a jar-source lookup, a staged copy with a check that the `InvestigationStoreProvider` service file is inside, and the jar on every generated launcher classpath, the demo jar list and the boot-smoke classpath. `check-module-deps` already had its `ALLOWED` entry (S2). No dependency coordinate added (HikariCP and the driver are already locked), no `jdbc:duckdb:` literal.
- **Tests.** la-core `InvestigationStoresTest` (7: default fs, unknown value, no module, no/foreign URL, provider failure, key precedence + Space id + per-field fallback, password never printed). `PgInvestigationStoreContractTest` 32 on PG 18.6 (the 22 contract + 5 from S2 + 5 new: concurrent bootstrap, append-only, an outage mid-life is 503 and the same store recovers, selected-and-unreachable is 503 twice with nothing cached, the registered provider serves each Space its own schema and caches the store).
- **Operator-run mutation checks (the classifier refuses an agent editing a guard; run each by hand, expect the named case RED, then revert).** (9) In `appendOnly`/the DDL list drop the `la_log` `BEFORE UPDATE OR DELETE` trigger line: `theSealedMainRecordsCanNeitherBeChangedNorRemovedButADraftsOwnRowsCanBe`. (10) Drop `WHEN (OLD.draft = '')` from the `la_log` trigger: the same case goes red at its final `closeDraft` step (the trigger would then bind a Draft's rows). (11) Remove the `pg_advisory_xact_lock` statement in `bootstrap`: `firstStartOfOneSpaceOnManyPodsAtOnceBootstrapsTheSchemaOnceAndAllSucceed` goes red on most runs (a catalog race; run 5 times). (12) In `unreachable`, return `false`: `aDatabaseThatGoesAwayIs503...` (expects `ApiException`, gets `IOException`). (13) In `InvestigationStores.database` replace the `orElseThrow` with a fall-back to `new FsInvestigationStore(writeRoot)`: `dbWithNoModuleInTheBundleIsRefusedNotServedFromTheFilesystem`. (14) In `PgInvestigationStoreProvider.pool`, cache the failure: `selectedAndUnreachableIs503...` second attempt differs.

**Decided by the operator 2026-10-05 (accept the lane defaults):** (a) no per-set size cap now: the real pressure is the per-Investigation total, so it waits for D-IS12 (b) content-addressed sets; (b) no boot-time refusal: `db` stays judged on first use per Space (503); (c) the process-global switch stays, like `objects.backend`. The three questions below are CLOSED by that decision; they are kept as the reasoning.

**Decisions owed (nothing here was guessed; closed above).** (a) **The per-set size limit (D-IS2 said "guarded by a per-set size limit")**: needs a number and a scope. S0 measured about 291 B per entity, so a 100,000-entity set is 29 MB and the 1 GB `text` limit is 34x away; the pressure is the per-Investigation TOTAL (3 GB for 2,000 steps over 10,000 entities), which a per-set limit does not bound. Options: a per-set cap (say 256 MB, refuse with a typed 413/422), a per-Investigation set budget, or nothing until D-IS12 (b). Not built. (b) **Boot-time verification**: `-Dinvestigations.backend=db` is judged on first use per Space (503), not at boot like `OperationalDb.verifyObjectsBackend`; boot-time would need a hook in core, which la-core cannot reach. Say if boot should refuse to start. (c) **Which Spaces may use `db`**: any Space of a JVM started with the flag; there is no per-Space switch (the key is process-global like `objects.backend`). **Blocks S7:** nothing from this slice; S7 needs two JVMs against one Postgres, a launcher for them, and the operator to schedule it (trigger on the row: a multi-pod LA deployment).
