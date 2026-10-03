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
| D-S5 harness: each Draft is its own DuckDB file, `memory_limit` + `threads` per connection (default 1), base read through `read_parquet` | `inspecto-la-storage/src/test/java/com/gamma/la/storage/DraftConcurrencyBench.java` l.51, l.81–86 |

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
  "must rebase" refusal yet (it is reported as `pinWarning` / `pinExpiry` only). A Draft append still re-folds main prefix + own
  log from step 0 on every write (D7-4). The 50-Draft cap and the hibernate marker need a place in `drafts/` listing that does not
  read every header (D7-6).

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

## 7. Admission control (numbers from D-S5)

D-S5 (feasibility §7.10.2) measured, at 5×10⁶ edges: open 54 ms p50; **3.8 MB process commit per open Draft**; one-hop
p95 66 ms with 20 active of 50 open vs 13 ms solo; one heavy 5-hop walk did not move the others' p95.

| Knob | Value | Derivation |
|---|---|---|
| Open Drafts per installation | **50 hard cap** (D21), 409 beyond | 50 × 3.8 MB ≈ 190 MB — not the constraint |
| Per-Draft DuckDB `memory_limit` | 256 MB, `threads 1` (light ops) | the D-S5 setting; 50 × 256 MB = 12.8 GB is a *ceiling*, not use — idle Drafts hibernate (§8) |
| Concurrent **heavy** jobs (Graph Run, multi-hop expand) | `min(4, cores/3)`; 2 on the 6-core test box | reuses `GraphRunService.Limits.threads` (l.63, l.142); a heavy job gets `threads ≥ 2` and up to 4 GB |
| Heavy-job queue | `GraphRunService.Limits.queue`; beyond it 429 with a retry hint (today `AbortPolicy`, l.147) | fail loudly, never silently slow |
| Light reads | not admitted — CPU-bound only (D-S5 reading 2) | 20 closed-loop threads on 12 HW threads gave the 7× rise |

⚠ **Unproven, owed before D-7 closes** (D-S5's own list): the heavy-job cap at 10⁹ edges, real Draft state under
contention, analysts with think time. Step D7-7 re-runs `DraftConcurrencyBench` with real Drafts; the numbers above are
starting values, not a verdict.

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
| D7-5 | Rebase + conflict report + promote (+ D-U7 approval when sensitive) | fixture with one op of each conflict kind → report lists all four; promote with moved head 409 |
| D7-6 | Admission + `draft.duckdb` derived tables + hibernate/rehydrate | 51st open 409; full heavy queue 429; evicted file rebuilds to identical result hashes |
| D7-7 | Re-run `DraftConcurrencyBench` with real Drafts, think time, and the 10⁹ (or largest feasible) partitioned index | D-S5 pass condition on real state; §7 numbers confirmed or replaced in this file |

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
