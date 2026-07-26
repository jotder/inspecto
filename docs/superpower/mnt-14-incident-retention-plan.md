# MNT-14 — archived-Incident retention sweep (plan)

**Status:** ACTIVE — scoped 2026-07-27; **§4 steps 1–3 BUILT + verified the same day** (G5 legal hold, G1
purge-eligibility query, G2 bulk delete-by-target). Steps 4–6 open: `JobService` hooks, the
`incident_purge` task itself, docs. · **Decision of record:** BACKLOG §2 D5 (retention tier, not
archive-is-terminal) · **Home when shipped:** `docs/okf/backend/control-plane/jobs.md`

> **As-built for steps 1–3** (`RetentionSweepSeamTest`, 8 tests; reactor 2296/0/0/3):
>
> - **G1 shipped as two `ObjectQuery` fields, not a bespoke store method** — `closedBefore` (epoch millis,
>   `0` = no constraint) + `oldestFirst`. Chosen over the plan's recommended option (a) because
>   `ObjectQuery` is *already* the single shape that drives both `matches()` (in-memory) and `WHERE`
>   generation (`DbObjectStore`), so the two backends cannot diverge on the predicate — which is the whole
>   risk G1 names. `ObjectQuery.purgeEligible(type, status, cutoff, limit)` is the one entry point; use it
>   rather than re-deriving. **No breaking change**: a 9-arg constructor delegates to the new canonical one,
>   so all existing positional callers still compile.
> - ⚠ **Correctness comes from the cutoff, NOT the ordering.** Because `closedBefore` is part of the
>   `WHERE`, every row a capped page returns is eligible whichever end of the corpus it came from — that
>   alone kills the "0 prunable against a fully-expired corpus" failure. `oldestFirst` only decides *which*
>   eligible objects a capped sweep takes first (longest-expired). The plan framed ordering as the fix; it
>   is the smaller half.
> - **G2 shipped as abstract interface methods, not throwing `default`s** — `NoteStore.deleteForTarget`,
>   `LinkStore.removeAllIncident`, `TagAssignmentStore.removeAllForTarget`, all returning the row count.
>   Verified first that **each interface has exactly two implementors and no test fakes or anonymous
>   impls**, so the MAJOR-release widening §3 G2 authorised is clean. A silent no-op default would have
>   orphaned rows, which is the exact failure these methods exist to prevent.
> - ⚠ **The Db bulk deletes throw on `SQLException`** rather than logging and returning 0 like their
>   sibling *reads*. A cascade that quietly "deleted 0" orphans the rows the caller is purging. This
>   deliberately inverts the local fail-open idiom — the same inversion §3 G4 calls for.
> - **G5 legal hold is fail-safe on anything unrecognised**: only `false`/`0`/`no`/`off` (case-insensitive)
>   or blank clears a hold; every other value holds. The two mistakes are not symmetric — wrongly keeping a
>   record costs storage, wrongly purging one is unrecoverable. `ObjectService.hasLegalHold(o)` is the one
>   definition; the store cannot filter on it (attribute bag, not a column), so **the sweep must apply it
>   itself, at purge time**.
> - **Nothing consumes any of this yet** — no task, no route, no caller. That is what makes the slice
>   safely shippable: with no cascade wired, nothing can orphan anything.

> Per the doc lifecycle in `CLAUDE.md`: this file lives here only while the work is in flight. On ship,
> distil the as-built facts into the OKF concept, move open items to `docs/BACKLOG.md`, and
> `git mv` this into `docs/archived-documents/plans-archive/`.

## 1. Why this plan exists (read this first)

The backlog said MNT-14's last blocker was "building the backend Incident `Archived` state". **That is a
wrong premise, corrected 2026-07-27.** `Workflow.defaultFor(ObjectType.INCIDENT)`
(`inspecto-engine/src/main/java/com/gamma/ops/workflow/Workflow.java:173-183`) already ships `ARCHIVED` as
the sole terminal state, with `archive` from `IDENTIFIED`/`DIAGNOSING`/`RESOLVED` and `reopen` back to
`DIAGNOSING`. Terminal transitions already stamp `closedAt` and non-terminal ones clear it
(`OperationalObject.withStatus`), so **an archived Incident already carries its own archive timestamp and
the retention window needs no new column.**

What MNT-14 is *actually* blocked on is referential cleanup and a query-ordering gap. That is roughly 4×
the work the old line implied, which is why it gets a plan instead of being improvised inside a shift.

## 2. What already exists (do not rebuild)

| Seam | Where | State |
|---|---|---|
| `ARCHIVED` terminal state + `archive`/`reopen` | `Workflow.java:173-183` | **ships** |
| Archive timestamp | `OperationalObject.closedAt` (stamped on terminal, cleared on reopen) | **ships** |
| Hard delete | `ObjectStore.delete(id)` — `ObjectStore.java:39-56` | **ships, zero callers**, reserved for exactly this |
| Prune-task template | `receipt_prune` — `MaintenanceJob.java:234-250` | **ships** — clone its shape |
| `retention_days` param idiom | `cfg.require("retention_days")`, reject `< 1` | **ships** |
| Dry-run preview idiom | `countPrunable(cutoff)` vs `prune(cutoff)`, fail-open when no store attached | **ships** |
| Store-hook idiom on the job layer | `JobService.java:836-853` (`notificationStore`/`deliveryReceiptStore`) | **ships** — copy for 4 more |
| Column-add migration idiom | `DbNoteStore.java:117-119` (`ADD COLUMN IF NOT EXISTS` + backfill) | **ships** (probably not needed — see §1) |
| Attribute-key constants | `ObjectService.java:61-72` (`ATTR_WATCHERS`, `ATTR_TAGS`, …) | **ships** — legal hold joins these |

## 3. The five real gaps

### G1 — `ObjectStore.query` is newest-first with no ascending mode ⚠ correctness, not perf

`ObjectQuery` has no sort control and `ObjectStore.query` is contractually newest-first
(`ObjectStore.java:20`), capped at `MAX_LIMIT` 10 000. **A sweep that takes one page gets the newest
archived Incidents and systematically misses the oldest — the only purge-eligible ones.** It would report
"0 prunable" on a corpus full of expired records: a silent, plausible-looking wrong answer, and the worst
possible failure mode for a retention feature.

No existing caller hits this (all eight internal `store.query` call sites are "everything relevant, well
under 10k" reads), so **there is no idiom to copy.** Two options:

- **(a) Push the selection into the store.** Add `List<String> purgeEligible(ObjectType, String status,
  long closedBefore, int limit)` (or a cutoff-shaped `ObjectQuery` field). `DbObjectStore` answers with
  `WHERE object_type=? AND status=? AND closed_at>0 AND closed_at<?`; `InMemoryObjectStore` filters and
  sorts ascending. **Recommended** — it is the only shape that stays correct at scale, and it lets the DB
  backend avoid materialising anything.
- (b) Page ascending in Java over a new sort flag. More code in the task, same store changes anyway.

⚠ Whichever is chosen, `closed_at > 0` must be part of the predicate — `closedAt == 0` means "not closed",
and a reopened Incident must never be eligible.

### G2 — no bulk delete-by-target on any dependent store ⚠ the bulk of the work

`ObjectStore.delete`'s own Javadoc says it does not cascade and the caller must. Today:

| Store | Delete capability | Needed |
|---|---|---|
| `NoteStore` (`ops/note/NoteStore.java`) | **none at all** — append-only, no delete of any kind | `deleteForTarget(targetKind, targetId)` |
| `LinkStore` (`ops/link/LinkStore.java`) | per-edge `remove(from, to, relationship)` only | `removeAllIncident(objectId)` |
| `TagAssignmentStore` (`ops/tag/TagAssignmentStore.java`) | `remove(tag,kind,id)` and `removeTag(tag)` — wrong axis | `removeAllForTarget(kind, id)` |

That is 3 interfaces × (interface + in-memory + DuckDB) ≈ 3 + 6 units. Note "attachments" is not a separate
store — attachments are `ObjectNote` rows, so `NoteStore` covers both.

⚠ **`ObjectStore` is `@PublicApi` and the others should be assumed to be too.** New interface methods must
be `default` (throwing `UnsupportedOperationException` is *not* acceptable here — a silent no-op default is
also wrong, since it would orphan rows quietly). Prefer: `default` method that throws, plus an explicit
capability probe the task checks, **or** widen the interface in a MAJOR release — the next release is
already MAJOR (D15 + D4), so widening is on the table this cycle.

### G3 — `EventStore` can never be cascaded (decide, then document)

`EventStore` is append-only by contract with no update or delete. A purged Incident's `OBJECT_ACTIVITY`
trail therefore outlives the Incident permanently.

**Recommended stance:** accept it, and say so in the task's Javadoc and the operator docs. The audit log is
not the record being retention-managed, and Parquet event retention is a separate mechanism. But this must
be a *stated* decision, because it means **"purge" never means "all trace removed"** — which is exactly the
question a legal/DPA reviewer will ask. Do not let this be discovered later.

### G4 — nothing is reachable from the job layer

`JobService` exposes hooks for `notificationStore` and `deliveryReceiptStore` only. Add four more
(`objectStore`, `noteStore`, `linkStore`, `tagAssignmentStore`) following `JobService.java:836-853`
verbatim: `volatile` field + `Optional<X> x()` getter + `void x(X)` setter, wired by `CollectorService`.

⚠ Fail-open like every sibling: no store attached ⇒ the task reports "nothing to prune" and returns ok.
⚠ But **fail-CLOSED on a partial cascade**: if `objectStore` is present and any dependent store is missing,
the task must **refuse to run**, not purge objects and orphan their notes. This is the one place the
prevailing fail-open idiom must be inverted, and it needs a comment saying why.

### G5 — legal hold has no representation

Nothing on an object expresses a hold today. Add `ObjectService.ATTR_LEGAL_HOLD = "legalHold"` to the block
at `ObjectService.java:61-72`, in the existing style (one-line Javadoc naming the feature).

Semantics to implement:
- Any truthy value ⇒ **never purge-eligible**, regardless of age. Absence ⇒ no hold.
- The dry run must report held-and-otherwise-expired objects **as a separate count**, not silently skip
  them — an operator needs to see "12 eligible, 3 held" to trust the sweep.
- ⚠ A hold must be checked **at purge time**, not at preview time only, or a hold applied between preview
  and run is ignored.

## 4. Build order

1. ✅ **G5 legal hold** — `ObjectService.ATTR_LEGAL_HOLD` + `hasLegalHold`. Verified: truthiness table,
   and that an age-eligible held object still comes back from the store for the *caller* to exclude.
2. ✅ **G1 oldest-first/cutoff query** — `ObjectQuery.closedBefore`/`oldestFirst` + both backends. Verified
   against a corpus whose newest 500 are inside the window and whose oldest 50 are expired: a 100-row page
   finds all 50 and offers nothing unexpired (this is the assertion that fails on the old query), plus a
   reopened `closedAt == 0` object is never returned, plus the no-cutoff default is still newest-first.
3. ✅ **G2 bulk deletes** on all three dependent stores × both backends. Verified per store: one target's
   rows vanish, a sibling target's do not, and **the same id in another `targetKind` does not** (the D10
   pair-scoping rule); `LinkStore` clears edges at *both* ends; repeat calls return 0, not an error.
4. **G4 `JobService` hooks** + `CollectorService` wiring → verify: partial attachment refuses to run.
5. **The task itself** — `incident_purge` (see naming, §5) in `MaintenanceJob` → verify: dry run mutates
   nothing and counts correctly; real run deletes object + notes + links + tag edges; event trail survives.
6. **Docs** — OKF `jobs.md` as-built, `MaintenanceJob` class-doc catalogue entry (that Javadoc is the living
   task catalogue), operator retention docs incl. the G3 stance.

Verify loop throughout (build-verify skill — all three false-green rules apply):

```bash
mvn -o clean test -Pedition-enterprise
```

## 5. Decisions to make before/while building

- **Task name.** Every sibling is `*_prune`, but this deletes **operator business records**, not
  housekeeping telemetry. **Recommend `incident_purge`** — a distinct verb so nobody reads it as another log
  trim, and it matches D5's own "purge-eligible" wording. (Counter-argument: consistency. Weak here — the
  blast radius genuinely differs.)
- **Retention source.** Derived (`closedAt + retention_days` from the job param) vs stamped-at-archive-time
  (a per-object expiry). **Recommend derived** — it needs no schema change and `closedAt` is already exactly
  the archive time. ⚠ The trade-off to accept consciously: shortening `retention_days` later retroactively
  makes older records eligible, so the sweep does not honour "what was promised when it was archived". If
  that guarantee is ever required, it becomes a stamped attribute — cheap to add later, so not worth
  pre-building.
- **Scope beyond Incidents.** D5/MNT-14 say Incidents. `ARCHIVED` is only in the INCIDENT workflow today, so
  scoping the task to `ObjectType.INCIDENT` costs nothing and avoids inventing policy for Cases/Alerts.
  Keep it narrow; generalise when a second type gets an `ARCHIVED` state.
- **Whether the whole selection should just be a `DELETE … WHERE` in `DbObjectStore`.** Attractive (skips
  the Java loop entirely) but `InMemoryObjectStore` still needs a correct path and the cascade still has to
  run per-id, so it does not remove G1 or G2 — only optimises them.

## 6. Traps carried in from elsewhere

- ⚠ **`RouteModule.register(api)` runs before any Space is hosted** — if this work grows a route, do not
  call `api.service()` in `register` (it kills `ControlApi` construction: 26 test errors, not one focused
  failure). `docs/PROJECT_NOTES.md` §4.
- ⚠ **`-pl X` without `-am`** resolves siblings from a stale `~/.m2` and reports bogus "cannot find symbol"
  errors in untouched files. Always `-pl X -am`, and re-run the FULL reactor before believing a green.
- ⚠ **`-Dtest=A,B`** needs commas *and* `-Dsurefire.failIfNoSpecifiedTests=false`.
