<!--
  ACTIVE PLAN — docs/superpower/
  Created 2026-10-03 (D-7 design of la-separation-feasibility-plan.md). DESIGN ONLY — nothing built. Decisions D7-Q1…Q8
  SIGNED 2026-10-03 (section 13). Retire per the three-tier lifecycle once D-7 ships.
-->

# LA separation — D-7 design (Drafts: parallel analyst working copies)

Option D's phase **D-7** ([`la-separation-feasibility-plan.md`](la-separation-feasibility-plan.md) §7.7 and the D-7 row of
§7.8: membership model, pinned baselines, per-Draft DuckDB files, incremental evaluation + checkpoints, admission control,
promote/rebase with conflict report, hibernate/rehydrate; depends on D-3, D-4, D16–D21). The concept is named **Draft**
(D16; GLOSSARY §11 entry and §13 rename row). This file is the design the operator signs before any code.

⛔ Grounding method: every fact below was read from the file and line cited, on `master` at `353d1142d` (2026-10-03).
Where the plan and the code disagree, **the code wins** and the disagreement is listed in §2.

## 1. Scope

**In:** the Draft object and its lifecycle (fork → work → promote | discard), its storage, baseline pinning, evaluation
and checkpoints, the admission controller, promote/rebase with a conflict report, hibernate/rehydrate, the membership
model that replaces owner-only access (D19), the threat model, phased steps.

**Out:** team Drafts with an edit lease (D17: "later if asked for"); evidence from Drafts (D20: Dossier from the promoted
Investigation only); UI layout (a separate SPA design once §12 is signed); the 10⁹-edge read path itself (D-3's job — a
Draft reads whatever the index serves).

## 2. Facts — what exists today, and where the plan is wrong

| Fact | Where it was read |
|---|---|
| The Investigation is a JSONL op log plus one persisted Working Set **per step** in `SnapshotStore`, under `investigations/<id>/{header.json, log.jsonl, sets/<step>.json}` (CREATE_NEW) | `inspecto-la-core/src/main/java/com/gamma/la/core/SnapshotStore.java` l.168–174, `appendStep` l.238–243 |
| The evaluator is pure over the log: every Dataset-reading op carries its **sealed, materialised read** (D-E3), so a log replays identically against any data | `inspecto-la-core/src/main/java/com/gamma/la/core/InvestigationEvaluator.java` l.23–27 |
| The evaluator is **total**: an op naming an id no longer in the Working Set is a no-op, which is what lets a re-ordered log (D-E4 fork) evaluate at all | `InvestigationEvaluator.java` l.76–80 |
| `appendOp` **re-folds the whole log** on every append (`evaluate(log, -1, null)`) under a per-directory monitor | `inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` l.307–309 |
| The lock is an in-process `Object` per path (`LOCKS.computeIfAbsent`) — single JVM only | `InvestigationRoutes.java` l.1946–1948 |
| No version-addressable Dataset read exists: `datasetVersion` is always null; reads are sealed at use, explicitly **not** a replay pin | `InvestigationRoutes.java` l.88–93, l.218 |
| Fork (D-E4): `POST /inv/investigations/{id}/reorder` makes a NEW Investigation whose header names `parent {id, parentSteps}`, re-applies ops, re-seals expand reads, keeps sealed list / resolution payloads, assembled off to the side and moved in with one `ATOMIC_MOVE` | `InvestigationRoutes.java` l.69–70, l.151, l.376–462; `SnapshotStore.java` l.248–269 |
| A fork's owner is the **caller**, not the parent's owner | `InvestigationRoutes.java` l.407 |
| Access: owner-only for writes; a non-owner gets 404 indistinguishable from absence | `InvestigationRoutes.java` l.98–102, l.1626–1630 |
| **Reads already go beyond the owner**: LA-24 `openForRead` admits members of the linked Case, read-only (`access: owner\|case-member`, `readOnly`) | `InvestigationRoutes.java` l.1609–1617, l.265–267; `inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` l.136 |
| A second exception: the four-eyes approver (D-U7) opens with `ownerOnly = false` | `InvestigationRoutes.java` l.1599–1606 |
| The Enterprise PDP (D-E7) is checked in the one `open` every route passes through; the resource it judges is `{id, owner, dataset, parent?}` | `InvestigationRoutes.java` l.1638–1652 |
| Index versions: `vNNNNNN` directories + an atomically repointed `CURRENT`; `gc` keeps the live version plus the newest `keepVersions` (default **2**) and nothing younger than a minimum age | `inspecto-la-storage/src/main/java/com/gamma/la/storage/IndexStore.java` l.35–47, `current()` l.117 |
| Build modes FULL / APPEND / COMPACT each publish the next immutable version; a manifest names its `parent` version | `inspecto-la-storage/src/main/java/com/gamma/la/storage/IndexBuilder.java` l.104; `IndexManifest.java` l.42–48 |
| `IndexReader` reads ONE pinned version on a connection sealed to that directory | `inspecto-la-storage/src/main/java/com/gamma/la/storage/IndexReader.java` l.19–21, l.108–125 |
| The reader pool **closes idle readers of every sibling version** on borrow ("CURRENT moved on") | `IndexReader.java` l.59–72 |
| Graph Run: a fixed `ThreadPoolExecutor(threads, threads)` over a bounded `ArrayBlockingQueue(queue)`, `AbortPolicy`; budgets resolved against defaults and ceilings, over-budget ends `BUDGET_EXCEEDED` before the engine runs | `inspecto-la-core/src/main/java/com/gamma/la/core/GraphRunService.java` l.63, l.142–147, l.192–199 |
| D-S5 harness (first run): each Draft its own DuckDB file over a `read_parquet` base. **Re-run in D7-7 on real Draft state** - the file moved to `inspecto-la-api`, because it now calls `DraftAdmission` and `DraftPromote`, which `inspecto-la-storage` cannot see | `inspecto-la-api/src/test/java/com/gamma/la/api/DraftConcurrencyBench.java` |

**Plan vs code (code wins):**

1. **§7.7.1 says access is owner-only, "a non-owner reads 404".** Partly stale: LA-24 already grants read-only access to
   linked-Case members, and D-U7 lets an approver in. Writes are owner-only. D19 therefore replaces a *three-way* gate,
   not a single owner check.
2. **§7.7.2 pins "Dataset version(s)".** No Dataset version exists (D-E3, l.218). The only versioned input is the **LA
   index** version (`IndexStore`), and the D-3 index is off by default (D-3 path A). A Draft can pin an index version
   only for Datasets that have one; otherwise the existing seal-at-use read is its pin (§5).
3. **§7.7.3 "incremental evaluation … instead of replaying the whole log".** The fold is already per-op
   (`apply`); the waste is in the route, which re-folds from step 0 on every append (l.307–309). And a per-step Working
   Set is **already persisted** (`sets/<step>.json`) — that is a checkpoint at k = 1, not something to add.
4. **§7.7.3 "each sandbox owns a DuckDB database file".** The op log and Working Sets are JSON, not DuckDB. The evaluator
   never touches DuckDB. A per-Draft DuckDB file is useful only for *derived* tables (Graph Run results, grouping
   tables, index reads under exclusion) — so the log stays in `SnapshotStore` and the DuckDB file is evictable (§4).
5. **Pinned index versions die after two publishes.** `gc` keeps live + 2 (l.47), and the reader pool evicts sibling
   versions (l.59–72). Both assume "only CURRENT matters" — false once Drafts pin older versions.
6. **The lock is per JVM.** Fine for one ControlApi; noted because promote adds a cross-object critical section.

## 3. Object model

```
Investigation  (header: owner, members{subject→role}, sensitive?, parent?)      ← the record; Dossier source (D20)
 ├─ main log  step 1 … N   (append-only; promote appends here)
 └─ Drafts   (one per member per Investigation, D17)
      header : draftId, investigationId, actor, baseStep k, baseLogHash, pins{index: {dataset→version}, lists, resolution}
      log    : own ops, steps k+1 … (same entry shape and sealed payloads as the main log)
      sets   : Working Set per step (as the main log)            ← durable, never evicted
      derived: draft.duckdb (Graph Run results, grouping tables)  ← evictable, rebuilt by replay
```

| | Investigation | Fork (D-E4, exists) | Draft (new) |
|---|---|---|---|
| Identity | own id | own id, `parent` lineage | own id, **inside** its Investigation |
| Visible to | owner, Case members (read), PDP | its creator | its actor (+ lead/reviewer read, §9) |
| Evidence (Dossier) | yes | yes (it is an Investigation) | **no** (D20) |
| Returns to parent | — | never | **promote** (rebase, conflict report) |
| Inputs | sealed at use | re-sealed at fork | pinned at fork, re-pinned only at rebase |

A Draft is **not** a fork: a fork is a peer Investigation and never merges back. Reuse the fork *mechanics* (re-apply with
the total evaluator, keep sealed list / resolution payloads, assemble-and-rename) for promote and rebase; do not reuse its
identity model.

## 4. Storage layout

```
audit/snapshots/investigations/<id>/
  header.json  log.jsonl  sets/                   (unchanged)
  members.jsonl                                    append-only role grants/revokes (§9)
  drafts/<draftId>/
    header.json                                    CREATE_NEW; baseStep, baseLogHash, pins
    log.jsonl  sets/<step>.json                    same writer as the main log (appendStep)
    state.json                                     open | hibernated | promoted | discarded (atomic replace)
<derived root>/la-drafts/<draftId>/draft.duckdb    evictable; per-Draft memory_limit, threads, temp_directory
```

The log stays where the Investigation's log is, so the existing `jail` / `SAFE_ID` and audit paths apply unchanged. The
DuckDB file lives under the derived layer so eviction never touches the record. It attaches nothing writable outside
itself; index reads go through `IndexReader` (sealed to the pinned version directory).

**As built (D7-3, 2026-10-03).**

* **Layout as written:** `drafts/<draftId>/header.json` (write-once: `draftId, investigationId, actor, createdAt, baseStep, baseLogHash,
  pins{index{dataset->version}, indexes[{dataset, mappingHash, version, pinnedAt}]}`), `log.jsonl`, `sets/<step>.json`, and
  `discarded.json` after a discard. `draftId` is `draft-<uuid>`, generated by the server and matched EXACTLY on every route
  (`DraftStore.DRAFT_ID`; 422 for a member who sends anything else). `state.json` of the table above is not written: "open" is the
  absence of `discarded.json` (`hibernated` / `promoted` arrive with D7-5 / D7-6 as further marker files).
* **The writer is the main log's.** `SnapshotStore.appendStep` now delegates to a static `appendStepAt(dir, ...)`; the main log's
  behaviour is byte-identical (every existing suite is green unchanged) and a Draft calls the same method through
  `DraftStore.appendStep`, which additionally takes the log line back if the step's set file cannot be written (a Draft's failed
  append leaves its log as it was; the main log keeps its deliberate log-first order).
* **Exact base-state rule.** `state(draft) = evaluate(mainLog[1..k] ++ draftLog)` where the two are ONE list: the Draft's own
  steps are numbered k+1, k+2, ... so the evaluator, prefix semantics, hashes and sealed payloads run unchanged. `baseLogHash` is
  `sha256` of the bytes of the main log's first k lines (each newline-terminated); every Draft read and write recomputes it and
  answers 409 if the main prefix no longer hashes to it (the header route still answers, with `baseIntact: false`). Proof:
  `ControlApiDraftsTest` compares `GET draft working-set`, `GET draft replay` and the last entry's recorded hash with a FULL re-fold
  computed in the test from the two files, after ops, a hide, an exclude and an undo, and shows the state at the fork equals the
  main `working-set?at=k`; the main log moving afterwards does not change it (`behind`, `stale`, `mainHead` are reported).
  `Inv` gained an optional `draft` reference, so `appendOp`, `undo`, `log`, masking and the Working Set relation are the SAME code
  over a different log; the relation cache key hashes the main prefix bytes AND the Draft's own log bytes.
* **Role x route** (stranger = non-member or Case member; "absent" = the 404 of an unknown id):

  | Route | lead | analyst (own Draft) | analyst (peer's) | reviewer | stranger |
  |---|---|---|---|---|---|
  | `POST .../drafts` (fork) | 201 | 201 | n/a | 403 | 404 |
  | `GET .../drafts` | all Drafts | own only | n/a | all Drafts | 404 |
  | `GET` draft, `/log`, `/working-set`, `/replay` | 200 | 200 | 404 | 200 | 404 |
  | `POST` `/ops`, `/undo` | 403 (not the actor) | 200 | 404 | 403 | 404 |
  | `POST /discard` | 200 (any Draft) | 200 | 404 | 403 | 404 |

  A lead's write to another's Draft is refused (403: the lead can see it exists); the actor of a Draft whose membership was revoked
  loses access (404) and the Draft freezes - a lead may still read and discard it.
* **Fork atomicity and pins.** The Draft is staged in `drafts/.fork-*` (a leading dot never matches `DRAFT_ID`) and moved in with
  one `ATOMIC_MOVE`; pins are taken first and released if anything fails, so a failed fork leaves no directory, no scratch directory
  and no pin (fault-injected through the `DraftStore.mover` test seam). The pinned version is the CURRENT version of each index
  whose mapping serves the Investigation's `sourceCol` / `targetCol`; with no index nothing is pinned (D7-Q2). The whole
  check-one-per-member + pin + rename runs under the Investigation's `drafts` lock.
* **Discard.** The actor or a lead. `discarded.json` (`discardedBy, discardedAt, headStep, logHash`) is written first (`CREATE_NEW`,
  so it is idempotent), then `log.jsonl` and `sets/` are DELETED and every pin is released; `header.json` stays. Why delete:
  the sealed rows (D-E3) are materialised data that may be personal and must not outlive the Draft, while the per-step audit events
  (`LINK_DRAFT_OP_APPENDED`, fingerprints and row counts) and the kept header + marker are the record that it existed. A repeat
  answers `alreadyDiscarded` and re-runs the idempotent unpin (a discard that crashed before the unpin is finished by the retry).
  A discarded Draft does not count against D17 and refuses writes and reads of its log (409).
* **Deviations / decisions.** (1) A sensitive (four-eyes) expand is REFUSED 422 in a Draft: the pending queue and its approvers are
  the main log's; the promote path of D7-5 is where a sensitive step meets approval. (2) Pins live per index in `pins.json` (D7-2),
  and the header records them for the Draft's own bookkeeping only. (3) Entity-List and resolution pins stay sealed inside the ops
  that use them; the header does not duplicate them. (4) The fork route answers 201 + `Location`, not 202 (nothing runs async). (5)
  Listing hides discarded Drafts unless `?discarded=true`. (6) No Draft cap (D21) or idle policy yet: D7-6.
* **Left open for D7-4..D7-7.** A Draft's `expand` still reads CURRENT and seals the rows (D-E3): the pin keeps the old version on
  disk but nothing reads it - D7-5's rebase needs a version-addressable `IndexedRead.select`, and the pin TTL (D7-Q3) has no
  "must rebase" refusal yet (it is reported as `pinWarning` / `pinExpiry` only). The 50-Draft cap and the hibernate marker need a
  place in `drafts/` listing that does not read every header (D7-6).
* **As built (D7-4) - checkpointed append (Q4 (a), every step).** `DraftCheckpoints` (inspecto-la-core) holds two in-memory
  accelerators; a miss recomputes exactly the old path, so neither can change an answer. (1) The evaluated State after each step,
  valid only while the Draft's `log.jsonl` keeps its size + mtime and entry count: an op resumes by `State.copy()` + `apply` (the
  same incremental rule replay uses, so replay == incremental by construction) - zero whole-log folds; an undo still folds (an
  incremental state cannot pop) and re-seeds the checkpoint. (2) The verified-base verdict (main prefix hashes to `baseLogHash`),
  keyed by the main log file and valid while its size + mtime hold: any main append/rewrite forces one re-hash, an unchanged one
  none. The replay route clears the base cache first - the audit never trusts it. Deviations: the on-disk `sets/<step>.json` is
  a response-shaped view, NOT a restorable State, so the checkpoint is in memory and a restart takes one cold fold; Draft writes
  still READ and parse the main prefix + own log (linear parse, no fold/hash) - a parse-free path is left open; a same-size,
  same-mtime in-place tamper of the main log is only caught by replay (or the next main change). Test seam
  `InvestigationEvaluator.foldCount()`; `DraftCheckpointsTest` + `ControlApiDraftsTest` (fold count 0 over 4 appends; mutation
  of `stateBefore` back to a fold fails it 80 vs 84).

* **As built (D7-5, 2026-10-03) - rebase, conflict report, promote** (`DraftRebase`, `DraftPromote`, `DraftRoutes`).
  * **Routes.** GET `.../drafts/{id}/conflicts` (the report; writes nothing; actor, lead, reviewer read), POST `.../rebase`
    (`{confirm?: [step], expectHead?}`; the actor only, 403 for a lead/reviewer, 404 for a peer), POST `.../promote` (`{expectHead?}`;
    the actor if lead/analyst, or a lead for any Draft; reviewer 403; peer analyst / stranger 404). Capability literal
    `canManageIncidents` on the two POSTs; the GET has none (a read, like the other Draft reads).
  * **Rebase carries EFFECTIVE ops only.** Undone ops and undo entries are compacted away (their history is in the per-step audit
    events; the old log is not kept - sealed rows may be personal data). Steps are renumbered M+1.. over the current main head M, each
    carries `rebasedFrom: <old step>`, and the header is REWRITTEN (not write-once any more): new `baseStep` / `baseLogHash` / `pins`,
    plus a `rebases[]` history. Replay goes through `InvestigationRoutes.replayOp` = the append validation (ids still in the Working Set,
    merged exclude re-resolved, expand re-sealed; list / resolution / seedBy payloads kept as a fork does). Rebase at the current head is
    legal - that is how an expired pin is renewed.
  * **Conflict kinds as implemented** (per op: did it change the old Working Set, does it change the new one): `blocked` = the append
    validation refuses it on the new base; `superseded` = it changed the old base, changes nothing now, and an effective op with the
    same `op` + canonical params exists on main since the fork; `no-op` = changed old, changes nothing now, no such twin (kept, reported);
    `changed` = an expand whose re-sealed fingerprint differs (both row counts reported; kept). A report carries steps, kinds, counts -
    no id, no row. **Q7:** `superseded` AND `blocked` are never carried and must be listed in `confirm` (409 naming the missing steps; 422
    for a step with nothing to confirm); `blocked` can only be dropped - there is no way to force-carry it.
  * **Optimistic commit.** The expensive replay (it re-reads) runs with no lock; commit takes the main lock THEN the Draft lock (promote's
    order) and re-verifies that main still has exactly `M` entries hashing to the plan's `toBaseHash` and the Draft log is byte-identical,
    else 409 "read the conflict report again"; it also re-folds main ++ carried and checks every recorded hash. The swap
    (`DraftStore.replaceRebased`) stages `.rebase-*`, moves the Draft aside, moves the stage in, deletes the aside; if the second move
    fails the old one is moved back (fault-injected through `mover`). New pins are taken first and restored on failure.
  * **Version-addressable read (D7-3 open item, closed).** `IndexStore.version(n)`; `IndexedRead.select(..., pinned)` and
    `IndexedExpand.attempt(..., pinned)` take `mappingHash -> version`. A Draft's `expand` now reads its header's pinned versions
    (`InvestigationRoutes.draftPins`); a rebase resolves CURRENT once and reads exactly those versions, then pins them. A pinned version
    that is no longer published reads as "no index" and the flat Dataset answers (existing fall-through) - the staleness gate still applies.
  * **Q3 expiry.** A Draft with an expired pin refuses `ops` and `promote` with 409 `must rebase`; undo (reads nothing), reads and
    rebase still work; `pinWarning` / `pinExpiry` were already reported (warn from day 23).
  * **Promote** (`DraftPromote.execute`): under main lock then Draft lock re-verifies open, `baseStep == main head`, the main prefix hash,
    no expired pin, **no undo entry in the Draft** (the Working Set records the step an entity was admitted at, so a log compacted without
    renumbering is not the state the Draft showed - such a Draft rebases first, which compacts it; found when the equivalence check
    caught it), then folds the new entries over main and requires the Draft's own state hash (500 if not). Entries are appended one by
    one with `draft{id, actor, baseStep, step, promotedBy}` (the state hash is unaffected: it folds only op fields); any failure
    truncates `log.jsonl` back and deletes the new `sets/` files, so main gains every step or none (`DraftStore.promoteHook` seam).
    Then `promoted.json` (CREATE_NEW; log + sets deleted, header + marker kept, as a discard), pins released. A promoted Draft is closed
    like a discarded one (`DraftStore.isClosed`; not counted by D17; listing `?closed=true`; state `promoted`; discard 409).
  * **Four-eyes - the recorded call.** A Draft cannot hold a sensitive expand (D7-3 422), but thresholds can fall after the op was taken.
    Sensitivity is judged at PROMOTE against the thresholds then in force. If any carried expand is sensitive nothing is appended: the
    promote is HELD as a pending request (`kind: "promote"`, 202) on the main log's one-at-a-time pending queue, decided through the
    EXISTING `approve` / `deny` routes (lead or reviewer, never the requester; needs an authenticated Subject, else 403). Approve re-checks
    that the Draft log hash and main head are what was requested (409 "deny and request again"), then runs the same atomic promote with
    `approval{request, requestedBy, approvedBy, ...}` on the sensitive entries. Rebase itself re-reads without approval: a Draft's reads
    are private to it; D-U7 governs what enters the main log.
  * **Gates.** `CapabilityManifest` x2, `AbsentGeoLinkRoutes.SURFACE` x3, `openapi-v1.json` (+3 paths, verified inside `paths`), route-gating
    report regenerated, `ImportLoaderInventoryTest` allow-list (`promoted.json`), audit `LINK_DRAFT_REBASED` / `LINK_DRAFT_PROMOTED`
    (ids, steps, counts - never rows). Tests: `ControlApiDraftRebasePromoteTest` (13, real HTTP, armed Authenticator, six Subjects).
  * **Left open for D7-6 / D7-7.** (1) `drafts/` listing still reads every header; a rebase appends to `rebases[]` so headers grow slowly.
    (2) A crash between the two renames of a rebase leaves `.old-<draftId>-*` and no Draft directory (data intact in the aside; no
    recovery sweep exists - D7-6's listing work is the natural home). (3) The pending promote does not freeze the Draft: a later Draft
    write only makes the approval 409. (4) Promote by an analyst on a NON-sensitive Investigation appends with no second person (design
    section 9 as signed); say so if that is not wanted. (5) The conflict report recomputes (re-reads) on every GET; no cache.
    (6) Rebase does not carry a Draft's `annotate`-only or other no-effect ops specially: a no-effect op both before and after is carried unreported.

## 5. Baseline pinning

At fork the Draft header records:

- `baseStep k` and `baseLogHash` = the evaluator's state hash at k (`evaluate(log, k, hashes)`, `InvestigationEvaluator.java`
  l.447–462), so a tampered or rewritten main log is detectable on promote;
- per Dataset with a published index: the **version number** `IndexStore.current()` resolved once (l.117);
- Entity List pins `{atSeq, headHash}` and the resolution pin — already sealed inside the ops that use them, so nothing new.

Datasets without an index keep D-E3: every read is materialised into the op (this already gives determinism; it only
lacks "same answer as yesterday's re-read", by decision).

**Retention:** `IndexStore.gc` must keep every version a live Draft pins (a pin set read from Draft headers), and the
reader pool's sibling eviction must spare pinned versions (step D7-2). A hibernated Draft's pin still holds; a discarded
or promoted one releases it. Bound: ≤ 50 Drafts (D21) × Datasets bound — in practice a handful of versions, each ≈ 1.28× the
flat edge file (D-3 step 1 measurement) — disk, not memory. §12 D7-Q3 decides whether an old pin may force a rebase instead.

**As built (D7-2, 2026-10-03).**

* **The pin set lives with the index, not in Draft headers.** `IndexPins` (`inspecto-la-storage`) keeps `pins.json` in the index
  directory: `{formatVersion:1, pins:[{pinId, version, pinnedAt, expiresAt}]}`. It is rewritten whole (temp name, fsync,
  ATOMIC_MOVE) rather than appended, since unpin, expiry and moving a pin are removals and the set is tiny. D7-3's fork calls
  `store.pins().pin(version, draftId)`; discard/promote call `unpin`; re-pinning an id moves it. `pin` refuses a version that is
  not a published directory (a `.tmp` stage never is one).
* **TTL (D7-Q3, signed).** `PIN_TTL_DAYS = 30`, `PIN_WARN_DAYS = 7` (the caller may pass another TTL). `expiresAt` is
  exclusive: at exactly day 30 the pin no longer protects, and the version falls under the normal keep/minAge rules (it is not
  deleted by expiry itself). `expiresSoon(now[, warnDays])` returns unexpired pins within the window for D7-3's admission to warn;
  `expire(now)` drops expired entries from the file.
* **gc.** `IndexStore.gc(minAge)` (signature unchanged, so `IndexBuildService` needed no edit) reads the pins and runs its whole
  sweep under the per-directory pin lock (JVM monitor plus `.pins.lock` file lock): a pin granted before the sweep is honoured, a
  pin after it is refused because the version is gone, and none can slip in between. An unreadable or truncated `pins.json`
  THROWS and gc deletes nothing (fail closed). Pinned append versions stay whole after their parent is collected: append
  versions are made of per-file hard links, so deleting the parent's directory removes only the parent's names (tested, plus
  `IndexBuilder.verify` after several publish+gc cycles).
* **Pool finding.** `IndexReader.borrow` kept only IDLE sealed readers per version directory and, on borrowing a version,
  closed the idle readers of every sibling. That was never a correctness hazard (a reader is re-openable from the version
  directory while it exists, and an in-use reader is not in the pool) but it thrashed: a Draft on v3 and CURRENT on v5 would evict
  each other's connections on every borrow. Changed: pinned siblings are spared, and borrowing a pinned version evicts nothing.
  An unreadable pin set spares everything. `evictAll()` is unchanged (shutdown closes all).
* **Deviation from the text above:** pins are stored per index, not read from Draft headers, so gc needs no Draft store (which
  would be a dependency from storage up to the investigation layer); the Draft records its own `pinId` and keeps the registry
  in step. A Draft header that loses its pin is therefore recoverable only by the TTL, which is the intent of D7-Q3.

## 6. Evaluation and checkpoints

- **Append on a Draft:** fold from the latest persisted Working Set (`sets/<step>.json`) instead of from step 0, then
  `apply` the new op. Undo keeps today's prefix re-fold (an incremental state cannot pop, l.440–446) but starts from the
  checkpoint at or below the undone step.
- **Checkpoint = the per-step Working Set that already exists.** No new artefact. k stays 1; §12 D7-Q4 decides whether
  to thin it for large Working Sets.
- **Determinism contract:** replaying `main[1..k] + draft[k+1..]` with the evaluator reproduces every persisted
  `sets/` hash — the existing `/replay` equivalence check, run on a Draft.
- **Graph in memory / long jobs:** reuse Graph Run (D-4) unchanged; a Draft-scoped run writes its result into
  `draft.duckdb`, keyed by the Draft step it ran on, so a later step invalidates it by key, not by deletion.

* **As built (D7-6, 2026-10-03) - admission, idle policy, listing, crash sweep** (`DraftAdmission`, `DraftLifecycle`, `DraftIndex`,
  `DraftStore.recover`; no new route, so none of the four route gates moved).
  * **Open-Draft cap (D21).** 50 open Drafts per **Space** (the design said "per installation"; one write root is one Space, and the count
    is across all its Investigations by directory names + marker files, no header read). The 51st fork answers **409** with the way out
    ("discard or promote one, or wait for an idle one to expire"), as the design table says - 409 not 429/503, because the condition is
    the Space's state, not load. At the cap the Space's other Investigations are swept first so an expired Draft never holds a seat. A
    hibernated Draft still counts. Check + seat are one step under a Space-wide monitor (`DraftAdmission.CAP`).
  * **Heavy-job cap (D7-Q6).** `min(4, cores/3)`, at least 1 (`-Dinspecto.la.draft.heavy=N` or `DraftAdmission.setHeavyLimit`). A permit is
    `tryAcquire`d, never waited for: full = **429 RATE_LIMITED** with a sentence. Heavy here = the rebase / conflict-report replay
    (`DraftRebase.compute`), a Draft's COLD Working Set relation build (`WorkingSetRoutes.relation`, cache miss) and a Draft `expand`
    op. The warm relation cache and checkpointed ops are light and not admitted (D-S5 reading 2).
  * **No `draft.duckdb`.** The design reserved a per-Draft derived DuckDB file; nothing needs it today. A Draft's derived state is the
    in-memory checkpoint (D7-4) plus the shared Working Set relation LRU, both keyed by the log bytes, so there is no file to open, to
    evict or to rebuild and no `NoRawInMemoryDuckDbOpen` question. If D7-7's 10^9-edge run shows the cold fold is the cost, that file is
    where the derived tables would live; hibernate/rehydrate already has the right shape for it (release on idle, rebuild by replay).
  * **Hibernate (1 h idle) / rehydrate.** `hibernated.json` marker + `DraftCheckpoints.forget` + `WorkingSetRoutes.evict` (the Draft's
    cached relations). Log, sets, pins stay. Any AUTHORISED access (a read or write of that Draft; not a list, not a refused or absent
    probe) deletes the marker and the first read is one cold fold - tested as `foldCount` +1 and a replay equal to the pre-sleep set.
    `state: hibernated` in list and describe; the waking describe also says `rehydrated: true`.
  * **Expire (30 d idle).** A discard with `expired:true` and `discardedBy: system:expiry` (log + sealed rows deleted, header + marker
    kept, pins released) and `LINK_DRAFT_EXPIRED` (actor `system`; `draftActor`, `step`, `idleDays`, `unpinned`). describe carries
    `lastAccessAt`, `expiresAt`, `expiryWarning` (true from 7 days out: the D7-Q5 "warn first" is this field, nothing is pushed). A
    GET on an expired Draft's log/working-set answers 409 "expired after 30 days idle".
  * **Idle clock.** `DraftLifecycle.touch`: memory at once, `accessed.json` at most every 5 min (a read must not cost a write), so a
    restart still knows the idleness; a Draft with neither falls back to its header file's time. `DraftLifecycle.clock` is the test seam
    (no test sleeps). Unreadable idleness reads as "just now": never expire on a read failure.
  * **Where the sweep runs.** Lazily, in `DraftAdmission.maintain`, on every Draft list / open / fork of an Investigation. **No scheduler
    was added**: the engine has `ScheduledExecutorService`s (`ControlApi`, `OpsMonitor`) but wiring one from a `RouteModule` would be a new
    framework seam; an idle Draft that nobody touches stays until someone lists its Investigation, which is harmless (it holds only
    disk) except for the cap, which sweeps the whole Space when it is reached.
  * **Marker precedence** (`DraftLifecycle.state`): `promoted` > `discarded` (an expiry is a discard) > `hibernated` > `open`. Closing
    deletes `hibernated.json` / `accessed.json`, and the order of checks hides a leftover marker after a crash anyway.
  * **Listing reads no header.** `drafts/index.json` = per draft id a compact header copy (no `baseLogHash`, no `rebases[]`) plus the
    header file's `size:mtime`. A listing stats each header and re-reads only the one whose identity changed (a rebase rewrites it),
    rebuilds a missing/corrupt index, drops vanished ids; state comes from marker files. Counted by `DraftStore.headerReads` (test seam).
    The D17 one-per-member check at fork uses it too.
  * **Crash sweep** (`DraftStore.recover`, run by `maintain`, under the same monitor a rebase holds while it swaps): `.old-<id>-*` with no
    `<id>` directory = crashed between the renames, so the aside (the complete pre-rebase Draft) is moved back **only if its header names
    that id** (the rebase "did not happen"); `.old-<id>-*` beside a live `<id>` that has its header = the delete was lost, the aside is
    deleted; any pair that fails verification is left alone and counted `held`; `.rebase-*` / `.fork-*` older than 1 h are deleted.
  * **Allow-list.** `ImportLoaderInventoryTest`: `hibernated.json`, `accessed.json`. Tests: `DraftLifecycleTest` (10, fixtures),
    `ControlApiDraftAdmissionTest` (10, real HTTP, armed Authenticator).
  * **Left open for D7-7 / later.** (1) The heavy cap is not yet applied to Graph Run (it has its own `Limits.threads`); the two pools
    are separate. (2) The cap and idle periods are static settings (`DraftLifecycle`), not yet keys in `link-analysis.toon`. (3) Expiry
    notifies only through the `expiryWarning` field. (4) A pending promote on the four-eyes queue does not stop an expiry; approve then
    answers 409 (the Draft is closed). (5) Light reads are still unadmitted by design; D7-7 measures them with real Drafts.
  * **Settled by D7-7 (2026-10-03).** (1) **Separate budgets, kept.** Graph Run's pool (`Limits.standard()`: threads 2, queue 16) runs
    the in-memory engine over a materialised Working Set; the Draft heavy permit guards index walks, the cold relation build and the
    rebase. Different work, different bound; together at most 4 + 2 = 6, half the 12 hardware threads, and D7-7 measured the light
    reads holding with all 4 heavy permits busy (below). One shared pool would let a long Graph Run starve a rebase. (3) The
    `expiryWarning` field is the D7-Q5 warning, by design (there is no push channel for it). (4) An expiry closing a Draft that has a
    pending four-eyes promote is accepted: the approve answers 409 and the request is made again from a new fork; a Draft idle for 30
    days with a pending approval is a process failure that the 7-day warning already shows. (5) **Light reads stay unadmitted**: with
    think time they ran at the solo latency; the closed-loop rise is CPU saturation, which an admission queue would turn into waiting,
    not into speed. Still open: (2) the cap and idle periods as `link-analysis.toon` keys, and the promote cost (below), both in
    `LA-DRAFT-PROMOTE-COST-1`.
  * **D7-7 note - fork rename denied on Windows (fixed 2026-10-03).** The bench saw 4 of 50 forks fail with
    `AccessDeniedException` on `DraftStore.create`'s `ATOMIC_MOVE` of the `.fork-*` scratch dir. Not a leaked handle: every write
    there closes its stream; Windows refuses a directory rename while ANY handle is open inside it, and the just-written `header.json`
    is a transient antivirus / indexer target. Fix as `IndexStore.moveCurrent`: `DraftStore.moveRetrying` (fork + both rebase
    renames) retries the denial 20 times, 5 ms doubling to 80 ms (~1.2 s), never a copy fallback; exhausted, the fork route answers
    503 `STORE_BUSY` and unpins - no scratch, no Draft, no pin. Tests: `DraftStoreTest` (transient then permanent denial),
    `ControlApiDraftsTest.aForkWhoseRenameStaysDeniedAnswers503AndLeavesNothing`; both mutation-checked. The bench retry is now
    redundant.

* **As built (D7-7, 2026-10-03) - `DraftConcurrencyBench` on real Draft state at 10^8 edges.** The bench now drives the shipped
  seams instead of plain JDBC: a real 32-bucket index from `IndexBuilder` (10^8 edges over 2x10^7 nodes, the D-S1 power law), read
  through the pooled `IndexReader.borrow`; a real Investigation in a `SnapshotStore` (20-step main log); each Draft forked through
  `DraftAdmission.requireRoom` under `DraftAdmission.CAP`, with a version pin, a base fold and a first index touch; analysts that
  read one hop (`fold`) and append it as a sealed `expand` op through the checkpoint (`DraftCheckpoints.after` / `remember`);
  3-hop walks under `DraftAdmission.heavy`; `DraftLifecycle.hibernate` + `evictCaches`, then a rehydrate with one cold fold; and
  `DraftPromote.execute`. Manual and opt-in as before (`@Tag("bench")`, not named `*Test`, gated on `-Dinspecto.bench.dir`). Same box
  as D-S5 (i7-9850H, 6 cores / 12 threads, 32 GB, Windows 11, JDK 27); 50 Drafts open, 20 active, 30 s per phase, think time
  exponential with a 2 s mean. One run.

  | Measure (10^8 edges) | Result |
  |---|---|
  | Index build (`buildMemory 8GB`) | 1 463 s (24 min), 2.2 GB |
  | Open a Draft (cap check + seat + pin + base fold + first index touch) | p50 **37 ms**, p95 92 ms, max 576 ms |
  | 51st fork | **409 CONFLICT** (the D21 cap) |
  | Memory per open Draft | heap **0.02 MB** at open, **0.40 MB** after ~160 own steps each (the checkpointed state); process commit +0.21 MB per Draft at open |
  | One-hop read, solo | p50 16, p95 **28**, p99 36 ms |
  | 20 active, closed loop (no think time) | p50 47, p95 **815**, p99 1 573 ms |
  | 20 active, 2 s think time | p50 16, p95 **28**, p99 33 ms |
  | 20 active closed loop + 6 heavy callers (limit 4) | p50 52, p95 804, p99 1 386 ms; 35 walks ran (p50 70 ms), 852 refused 429 |
  | 20 active, 2 s think time + heavy limit saturated | p50 24, p95 **43**, p99 87 ms |
  | Append (checkpointed): solo / think / think + heavy | p50 32 / 21 / 40 ms; p95 70 / 55 / 89 ms |
  | Hibernate (marker + cache eviction) | p50 0.4 ms, max 5.4 ms; 19 MB heap released for 50 Drafts |
  | Rehydrate (one cold fold) | p50 < 1 ms, max 71 ms (the 796-step Draft); state hash equal to the pre-sleep one for 50 of 50 |
  | Promote | 796 steps in **25.1 s** (235 steps in 3.0 s in a 10^6 trial run), holding the main and Draft locks throughout |

  **Verdict.** The **50-Draft cap holds** and is not a memory bound (50 Drafts cost about 20 MB of heap); it stays the D21 governance
  cap. **`min(4, cores/3)` holds** (4 on this box): with analysts thinking, four heavy walks in flight moved the light p95 from 28 to
  43 ms and the p99 to 87 ms, and every refusal was an immediate 429, never a wait. The **one number that does not hold is promote**:
  it grows faster than linearly in the Draft's steps (3.0 s at 235, 25.1 s at 796; cause not isolated - the per-step re-hash and set
  document over a growing state is the hypothesis) while holding the main log's lock, so a long Draft's promote stalls every writer
  of that Investigation. Filed `LA-DRAFT-PROMOTE-COST-1`.

* **As built (2026-10-03) - promote made linear (`LA-DRAFT-PROMOTE-COST-1`, promote half).** Profiled with
  `DraftPromoteCostTest` (a 5-step main log, then a Draft of one seed and expands admitting 20 fresh links each, an exclude every 7th
  and a hide every 11th step), phase timers around `DraftPromote.execute`:

  | Draft steps | promote before (total / per-step `state.hash()` / `setDoc` + canonical / write) | after, warm / cold |
  |---|---|---|
  | 100 | 686 ms / 172 / 303 / 147 | 189 / 805 ms |
  | 200 | 2 215 ms / 674 / 1 159 / 314 | 433 / 1 781 ms |
  | 400 | 8 248 ms / 2 621 / 4 694 / 831 | 1 102 / 3 607 ms |
  | 800 | 34 915 ms / 10 728 / 19 000 / 4 998 | 3 346 / 8 303 ms |

  The hypothesis held: reading and parsing both logs, the two folds and `apply` were under 0.2 s at 800 steps; 85% was serialising the
  whole Working Set per step - `state.hash()` for the line, then `setDoc` hashing it again and writing it out - O(state) per step,
  so quadratic in steps, plus writing those full-state set files. **Fix:** an undo-free Draft based at the main head numbers its steps
  exactly as they land on main and the added provenance is not folded, so the state after promoted step i IS the Draft's state after
  its step i: promote takes the Draft's own sealed `workingSetHash` per step and lands its set file by hard link
  (`SnapshotStore.appendStepSharingSet`; a byte copy where links are unsupported). **Nothing unverified reaches main (operator,
  2026-10-03):** a set file is reused only if its head is exactly `{"hash":"<that hash>","step":<step>,"workingSet":` (the canonical,
  key-sorted form) AND the bytes between that head and the closing brace - which are `canonical(state.toMap())`, exactly what
  `State.hash()` hashed - SHA-256 to the step's sealed `workingSetHash`: one read and one hash per file, no parse, fold or
  canonicalisation. A missing, foreign or tampered one is re-sealed from the fold as before (the oracle test proves the re-seal is
  today's bytes, so no 409 is needed), and the fold's final hash must equal both the Draft's fresh fold and the last line's hash.
  The verify reads every set file, so promote is linear in the BYTES the Draft's sets hold (which grow with its state), not strictly
  in steps - still about 10x under the old cost at 800 steps, with no serialisation.
  Output is unchanged: `DraftPromoteCostTest` keeps the pre-fix loop as an oracle and compares the promoted lines and every set file
  byte for byte (also with one set deleted, one replaced by the previous step's, and one with an intact head but an altered byte in
  its working set - re-sealed, never carried), and counts re-sealed steps (0 at 25 and 100 steps) as the deterministic guard against a
  return to per-step serialisation; four mutants (off-by-one set, no head check, no reuse, no content verify) each fail it. "Warm"
  warms the Draft's set files first: on Windows the first open of a file written a moment ago costs about 6 ms (the on-access
  scan, measured: a second open costs 0.1 ms), which a real Draft written over its life does not pay. **Lock:** what remains under the
  main lock is the verify (one read + SHA-256 per set file) and the appends (log line + link + marker); the
  reads and folds (under 0.1 s at 800 steps) were left where they were rather than adding an optimistic pre-lock phase. The 10^8 bench
  was not re-run. **Still open in the row:** the Draft cap and idle periods as `link-analysis.toon` keys. Rebase was not in scope here; see the
  next bullet.

* **As built (2026-10-03) - rebase profiled and cut 4x (`LA-DRAFT-REBASE-COST-1`, no row: fixed in the same change).** Profiled with
  `DraftRebaseCostTest` (the promote fixture, then main moves 3 steps - one of them seeds `hub`, so the Draft's first op is
  `superseded`; the replay stands in the sealed read for the index read, which is per-step and does not grow with the state):

  | Draft steps | plan before (total / replay / copy+apply / 2x hash for the no-op test / line + hash / `setDoc`) | plan after | commit fold |
  |---|---|---|---|
  | 100 | 572 ms / 3 / 4 / 231 / 119 / 201 | 127 ms | 117 ms |
  | 200 | 2 226 ms / 7 / 18 / 905 / 472 / 806 | 567 ms | 488 ms |
  | 400 | 9 070 ms / 17 / 57 / 3 757 / 1 902 / 3 306 | 2 011 ms | 1 894 ms |
  | 800 | 36 706 ms / 31 / 229 / 15 259 / 7 645 / 13 493 | 8 424 ms | 7 901 ms |

  **Quadratic, confirmed** (4x per doubling): 99% was serialising the whole Working Set FIVE times per step (`state.hash()` and
  `after.hash()` for the no-op test, `after.hash()` again for the line, `setDoc`'s hash plus its `toMap`). **Reuse is not valid
  here**, unlike promote: main's new steps renumber the carried steps, and every entity / link records the step that admitted it, so
  each rebased state differs from its sealed one (only a rebase onto an unmoved, undo-free base would match, and that is not a case
  worth a path). **Fix:** each rebased state is serialised ONCE - `canonical(after.toMap())` gives the `workingSetHash` (`State.hash()`
  is `sha256` of exactly those bytes) and the set file, whose canonical form is `{"hash":..,"step":..,"workingSet":<those bytes>}`;
  the previous state's hash is carried instead of recomputed. Nothing is reused from disk, so nothing needs verifying beyond what
  already ran. The commit's fail-closed fold (one hash per step over main + carried lines) moved OUT of the main lock to
  `DraftRebase.verify`, run before `commit` locks over a fresh read of main checked against the plan's `toBaseHash`; under the lock
  the existing byte checks (main == the plan's main, Draft log == the plan's) prove the verified inputs are the committed ones. The
  conflict report is untouched. It stays O(state) per step - the set files the rebase must write hold Σ state bytes, so linear in
  steps is impossible without changing the set-file format - but 4.4x cheaper, and the main lock no longer holds an O(steps x state)
  fold (7.9 s at 800 steps). **Equivalence:** `DraftRebaseCostTest` keeps the pre-fix loop as an oracle and compares the conflict
  report, every line, every set file, the steps and the final hash byte for byte at 1 / 2 / 30 / 60 steps; the guard counts
  full-state serialisations (`InvestigationEvaluator.serialisationCount`, every `toMap`) and requires exactly one per replayed op
  plus two. Mutants: a set file one step off fails the oracle; restoring `state.hash()` in the no-op test fails the guard (52 vs 27).

  **Scale reached: 10^8, not 10^9.** The 10^8 index build alone took 24 min under the 8 GB build memory cap (the D-3 spike's uncapped
  build took 6.1 min at a 17.9 GB peak); 10^9 is at least ten times that, over 4 h capped or a peak beyond this 32 GB box uncapped, so
  it fails the "10^8 well under an hour" condition. Disk was not the limit (231 GB free; 10^9 is about 22-34 GB). The Draft-side numbers
  depend on edge count only through the one-hop read, which D-S1 / D-3 already bound (43 ms at 10^8 through the sealed path); the
  10^9 run stays with `LA-INDEX-SCALE-MEASURE-1` (3). Closed-loop numbers are the worst case: 20 threads on 12 hardware threads, with
  the bench JVM and DuckDB sharing them.

## 7. Admission control (numbers from D-S5)

D-S5 (feasibility §7.10.2) measured, at 5×10⁶ edges: open 54 ms p50; **3.8 MB process commit per open Draft**; one-hop
p95 66 ms with 20 active of 50 open vs 13 ms solo; one heavy 5-hop walk did not move the others' p95.

| Knob | Value | Derivation |
|---|---|---|
| Open Drafts per Space | **50 hard cap** (D21), 409 beyond - **confirmed by D7-7** | D7-7: 0.4 MB heap per Draft with ~160 steps, about 20 MB for 50 — not the constraint |
| Per-Draft DuckDB `memory_limit` | 256 MB, `threads 1` (light ops) | the D-S5 setting; 50 × 256 MB = 12.8 GB is a *ceiling*, not use — idle Drafts hibernate (§8) |
| Concurrent **heavy** jobs (multi-hop expand, cold relation build, rebase; Graph Run keeps its own pool, D7-7) | `min(4, cores/3)`; 4 on the 6-core / 12-thread test box (`availableProcessors` counts hardware threads) - **confirmed by D7-7** | reuses `GraphRunService.Limits.threads` (l.63, l.142); a heavy job gets `threads ≥ 2` and up to 4 GB |
| Heavy-job queue | `GraphRunService.Limits.queue`; beyond it 429 with a retry hint (today `AbortPolicy`, l.147) | fail loudly, never silently slow |
| Light reads | not admitted — CPU-bound only (D-S5 reading 2) | 20 closed-loop threads on 12 HW threads gave the 7× rise |

✅ **D7-7 (2026-10-03) re-ran `DraftConcurrencyBench` on real Draft state, with think time, at 10⁸ edges** (section 6, "As
built (D7-7)"): the cap and the heavy limit hold; the per-Draft DuckDB `memory_limit` row is moot (D7-6 built no `draft.duckdb`).
The 10⁹ axis was not run (build time; `LA-INDEX-SCALE-MEASURE-1`).

## 8. Promote, rebase and the conflict report

**Rebase** (Draft → newer main head M, or newer index version): re-apply the Draft's ops on `main[1..M]` with the total
evaluator, as reorder does (l.415–447). Expand reads are **re-sealed** against the new pins (as a fork re-seals);
list / resolution payloads keep what they sealed (as a fork does, l.425–431). Assembled off to the side, moved in with
one rename (`createFork`'s pattern).

**Conflict report** — computed, never dropped. Per Draft op, compare its effect alone on the old base vs the new base:

| Kind | Example | Default |
|---|---|---|
| `no-op` | `exclude X` where the new base no longer holds X (the evaluator silently ignores it, l.76–80) | report; keep op |
| `changed` | an `expand` re-sealed to different rows (fingerprint differs) | report with both counts |
| `superseded` | main already has an op with the same effect | report; drop on accept |
| `blocked` | a merged exclude whose group no longer resolves (today a 422 at append) | promote refused until fixed |

**Promote** = rebase to the current head, then append the Draft's ops to the main log under the Investigation lock in
one critical section; each entry gains `draft {id, actor, baseStep}` provenance (audited, D20). Refused (409) if the head
moved between report and accept — the analyst re-reads the report. If the Investigation is **sensitive**, promote
creates a pending request decided through the existing D-U7 approve/deny routes (D18).

## 9. Access model vs D-E7

D19 (signed) replaces owner-only with **members**: `lead · analyst · reviewer`, append-only in `members.jsonl`; the creator
is the first lead. Rules:

| Act | lead | analyst | reviewer | Case member (LA-24) |
|---|---|---|---|---|
| Read Investigation | ✓ | ✓ | ✓ | ✓ (unchanged) |
| Fork own Draft / write own Draft | ✓ | ✓ | — | — |
| Read another's Draft | ✓ | — | ✓ | — |
| Promote | ✓ | ✓ (approval if sensitive) | — | — |
| Approve promote / pending expand | ✓ | — | ✓ (four-eyes: never own) | — |
| Grant / revoke members | ✓ | — | — | — |

Everything still passes the one `open` gate: R3 Dataset visibility and the **Enterprise PDP (D-E7) can only narrow** — the
PDP resource gains `members` and, for Draft routes, `draftId` + `actor`. Non-members keep the 404-as-absence answer. The
direct writer of the main log becomes *promote*; `POST …/ops` on the main log stays lead-only. This **amends signed
D-E7's Professional answer** (owner-only) — it must be recorded as an amendment, not done silently (D7-Q1).

**As built (D7-1, 2026-10-03).** Recorded as an in-place amendment of the D-E7 row in
`archived-documents/plans-archive/link-analysis-backlog-plan.md` (the only place that decision lives) and in the OKF
`link-analysis.md` (*Investigation members*). What the code does, where it differs from the table above:

| Route group | lead | analyst | reviewer | Case member | stranger |
|---|---|---|---|---|---|
| Read: `log`, `working-set`, `dossier` (+ `verify`, `bundle`, `bundle/verify`), `measures`, `coverage`, `references`, `case` (GET), the list | ✓ | ✓ | ✓ | ✓ (unchanged) | 404 |
| `replay`, `members` (GET) | ✓ | ✓ | ✓ | 404 (replay was never open to them) | 404 |
| Graph Run start / get / list / cancel (the Investigation's read gate; a run stays its starter's) | ✓ | ✓ | ✓ | ✓ | 404 |
| Write the main log: `ops`, `undo`, `reorder` (fork), `template`, `alert-rules`, `case` PUT/DELETE, `references` POST, `reveal` | ✓ | 403 | 403 | 404 | 404 |
| Grant / revoke (`members`, `members/revoke`) | ✓ | 403 | 403 | 404 | 404 |
| Approve / deny a pending expand (D-U7) | ✓ | 403 | ✓ | 404 | 404 |

* **No `members.jsonl` = legacy**: the owner is the sole lead; the four-eyes approver stays "any holder of
  `canApproveLinkExpansions`" there (narrowing it would change every existing Investigation). Once the file exists, only a
  lead or reviewer may decide.
* A member refused a lead-only route gets **403** (the caller already knows the Investigation exists); a non-member and a Case
  member keep the 404 that reads as absence. The 403 is raised only after R3 and the PDP, so a policy DENY still reads as 404.
* `reveal` and `reorder` (fork) are LEAD-only here although the table above does not name them: unmasking and copying sealed
  rows into a new Investigation are not reads of the Working Set. ⚠ If analysts must unmask, that is a decision (D7-3's Draft
  gate is the natural home), not an accident of this step.
* The last lead cannot be revoked or demoted (422); the owner is only the FIRST lead and can be revoked once another lead exists.
* The PDP resource gained `members` (`subject → lead|analyst|reviewer`, the owner included as lead when no record exists).

## 10. Threat model

| Threat | Control |
|---|---|
| Analyst reads a peer's exploration | Draft routes open through a Draft gate: actor, or lead/reviewer; 404 otherwise |
| Promote smuggles unaudited ops | promote re-runs append validation per op; every entry carries `draft` provenance; audit emitted after the act |
| Rebase silently changes meaning | conflict report is mandatory; `blocked` refuses |
| Self-approval on a sensitive Investigation | approver ≠ promoting actor (existing D-U7 rule) |
| Revoked member keeps a Draft | membership checked live on every open (as LA-24 does); a revoked actor's Draft freezes, lead may read or discard |
| Draft DuckDB reads outside its pin | index reads only through `IndexReader`, sealed to the version directory (l.19–21); the Draft file attaches nothing else |
| Noisy neighbour / DoS | §7 caps; 409 at 50 open; 429 when the heavy queue is full |
| Pin defeats GC and fills disk | pin set bounded by open Drafts; idle-Draft expiry (D7-Q5) releases pins |
| Masking bypass via a Draft export | D-U6 masking at render/export applies unchanged; Drafts are not Dossier sources (D20) |

## 11. Hibernate / rehydrate

Hibernate after an idle period (D7-Q5): close the DuckDB connection (frees the ~3.8 MB + buffer pool), drop in-memory
Graph Run caches, keep log, sets and pins. Optionally evict `draft.duckdb` under disk pressure. Rehydrate on next open:
reopen the file (54 ms p50 in D-S5) or, if evicted, rebuild derived tables by replay from the pinned baseline. **The log
and `sets/` are never evicted.**

## 12. Phased delivery

| Step | Content | Verify |
|---|---|---|
| D7-1 ✅ **BUILT 2026-10-03** | Membership: `members.jsonl`, roles, gate change in `open`/`openForRead`, PDP resource gains `members`; D-E7 amendment recorded | real-HTTP tests: each role × each route; non-member 404; PDP DENY still hides from a lead (`ControlApiInvestigationMembersTest`, `InvestigationMembersTest`) |
| D7-2 | **BUILT 2026-10-03** (section 5 "As built"). Version pins survive: `IndexStore.gc` honours a pin set; reader pool spares pinned siblings | unit test: publish 3 versions with one pinned → pinned survives gc and pool borrow of CURRENT |
| D7-3 ✅ **BUILT 2026-10-03** (section 4 "As built") | Draft store + routes: fork (baseStep, baseLogHash, pins), append, undo, log, discard | Draft `/replay` equivalence green; fork writes nothing on failure (fault-injected rename) |
| D7-4 | Checkpointed append (fold from latest `sets/`), main log and Drafts | equal state hashes vs full re-fold on a 500-step fixture; append latency flat in log length |
| D7-5 ✅ **BUILT 2026-10-03** (section 4 "As built (D7-5)") | Rebase + conflict report + promote (+ D-U7 approval when sensitive) | fixture with one op of each conflict kind → report lists all four; promote with moved head 409 |
| D7-6 ✅ **BUILT 2026-10-03** (section 4 "As built (D7-6)"; no `draft.duckdb` was needed) | Admission + hibernate/rehydrate + expiry | 51st open 409; full heavy queue 429; evicted file rebuilds to identical result hashes |
| D7-7 ✅ **BUILT 2026-10-03** (section 6 "As built (D7-7)"; 10⁸ edges, not 10⁹) | Re-run `DraftConcurrencyBench` with real Drafts, think time, and the 10⁹ (or largest feasible) partitioned index | D-S5 pass condition on real state; §7 numbers confirmed or replaced in this file |

## 13. Operator decisions owed

| Id | Question | Options | Recommendation |
|---|---|---|---|
| D7-Q1 | How is the D-E7 change recorded? | (a) amend D-E7 in the LA decision record citing D19 · (b) new decision superseding it | **(a)** — one decision, one history |
| D7-Q2 | Can a Draft bind a Dataset without an index? | (a) yes, D-E3 seal-at-use · (b) index required for Drafts | **(a)** — the index is off by default (D-3 path A) |
| D7-Q3 | What happens when a pinned index version ages? | (a) pin holds forever while the Draft is open · (b) pin expires after N days → forced rebase · (c) cap pinned versions per Dataset | **(b)** with N = 30, warns at N − 7 |
| D7-Q4 | Checkpoint density | (a) every step (today) · (b) every k steps for Working Sets over a size | **(a)** until a measured cost says otherwise |
| D7-Q5 | Idle policy | hibernate after (a) 15 min · (b) 1 h; expire (discard) after (c) 30 d · (d) never | **(b) + (c)** — expiry warns the actor and lead first |
| D7-Q6 | Heavy-job cap default | (a) `min(4, cores/3)` · (b) operator-set only | **(a)**, overridable in config |
| D7-Q7 | `superseded` conflicts on accept | (a) drop automatically · (b) analyst confirms each | **(b)** — nothing leaves the record silently |
| D7-Q8 | Who may read another analyst's Draft? | (a) lead + reviewer · (b) actor only · (c) all members | **(a)** — supervision without peer exposure |

**Answers — operator 2026-10-03 (every recommendation accepted; the build is unblocked, start at D7-1 and D7-2):**

| Id | Answer |
|---|---|
| D7-Q1 | **(a)** Amend D-E7 in the LA decision record, citing D19 — one decision, one history. |
| D7-Q2 | **(a)** A Draft may bind a Dataset with no index (D-E3 seal-at-use). |
| D7-Q3 | **(b)** A pinned index version expires after N = 30 days (warning at N − 7), then the Draft must rebase. |
| D7-Q4 | **(a)** A checkpoint at every step, until a measured cost says otherwise. |
| D7-Q5 | **(b) + (c)** Hibernate after 1 h idle; expire (discard) after 30 d idle, warning the actor and the lead first. |
| D7-Q6 | **(a)** Heavy-job cap default `min(4, cores/3)`, overridable in config. |
| D7-Q7 | **(b)** The analyst confirms each `superseded` conflict on accept; nothing leaves the record silently. |
| D7-Q8 | **(a)** The lead and the reviewer may read another analyst's Draft. |
