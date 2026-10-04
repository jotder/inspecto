<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-04. DESIGN ONLY — no code. Decisions D-IS1…D-IS12 OPEN (section 11). Retire per the three-tier lifecycle
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
this lane; section 11 D-IS4 asks that `la_draft.version` follow that convention's name and error mapping.

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

## 11. Decision register (open — one interview)

| ID | Question | Options | Recommendation |
|---|---|---|---|
| **D-IS1** | Scope: which files move? | (a) everything per-Investigation incl. Drafts and sets; (b) log, members, Drafts only, sets stay on a shared volume; (c) main-only, Drafts later | **(a)**. Sets are written in the same act as their log line (G2); splitting them re-creates the crash window the transaction removes. Parquet index stays out regardless. |
| **D-IS2** | Where do sealed sets live on Postgres? | (a) `text` rows in `la_set` (TOAST); (b) Postgres holds `(step, sha256, uri)`, the bytes live in an object store; (c) do not store, recompute | **(a)**, guarded by a per-set size limit and measured first (S0). (c) is wrong: the Dossier and promote read the stored bytes (`DossierRoutes.java:213`, `DraftPromote.java:54`). (b) only if S0 shows sets near the 1 GB field limit. |
| **D-IS3** | Seam shape | (a) expose `withLock(scope, fn)`; (b) preconditioned atomic operations, no lock exposed | **(b)** — no lock to distribute; the routes already do compute-then-verify (`DraftRebase.java:224-242`). |
| **D-IS4** | Concurrency primitive | (a) short-tx `FOR UPDATE` row lock + PK + `version` column; (b) `pg_advisory_xact_lock`; (c) lease table with TTL + fencing token | **(a)**. (c) is for operations longer than a connection (D5, `DbRunLease`); none here are. Name the version column and the 409 mapping after the platform's new optimistic-versioning convention (⚠ UNVERIFIED — not visible to this lane). |
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
| **S1** | **De-path + port on FS.** Define `InvestigationStore` + `Scope`; `FsInvestigationStore` wraps current logic; `Inv` carries the Scope not a Path; `lock(Path)` replaced by the precondition operations; `DraftCheckpoints` / `WorkingSetRoutes` read validity from `head()`; `geo-link` caller; delete `DraftIndex` use behind the port. **No behaviour change.** | **L** (about 15 production files, `InvestigationRoutes` 2,181 lines) | all existing LA route tests green unchanged; `-pl inspecto-la-core,inspecto-la-api,inspecto-geo-link` units, then the full reactor once |
| **S2** | Abstract `InvestigationStoreContract` (section 9) + byte-identity tests + mutation checks, run on FS | M | red mutants proven red |
| **S3** | `inspecto-la-store-pg`: header, main log, sets, members, references; schema bootstrap; append-only privileges | M | contract green on PG 18.6 |
| **S4** | Postgres Drafts: create, append, close, promote, rebase swap, cap, sweeps, lifecycle | **L** | contract + fault injection green |
| **S5** | Class B records: case-link, Alert Rule binding, pending, templates | S | contract |
| **S6** | Wiring: `ServiceLoader`, `investigations.backend`, fail-closed 503, bundle staging, module-deps allow-list, OKF + EDITIONS + INDEX rows | M | `check-module-deps`, bundle build, docs guards |
| **S7** | Multi-pod proof: two-JVM race suite on PG 18.6 | M | section 9 multi-pod row |
| **later** | D-IS6 chain hash; D-IS11 pin ledger; D-IS12 content-addressed sets | — | demand-gated |

Order: S0 and S1 are independent; S2 follows S1; S3 to S5 follow S2; S6 and S7 last. S1 is the only slice that touches shared
seams — it is the one that needs the full reactor gate before a push.

## 13. Claims not verified (read these before signing)

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
