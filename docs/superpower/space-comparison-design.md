# Design — space-to-space comparison (Job framework, Maintenance COULD tier)

**Row:** `BACKLOG.md` §3 *Job framework — Maintenance COULD tier*, trigger **FIRED 2026-09-15** (a real
comparison was asked for). ⚠ The trigger governs the **comparison half only** — predictive maintenance stays
deferred to AGT-5 regardless, and one firing must not discharge the other half.
**Status:** DESIGN ONLY, written 2026-09-16. **Four decisions are owed before any code** (§5).

## 1. What this actually is — narrower than the row's title

`jobs.md` records it as a follow-on to the **growth-trend** work, not as a config diff:

> *"Open COULD follow-ons: space-to-space comparison, predictive maintenance"* — in the `storage_trend`
> bullet (`okf/backend/control-plane/jobs.md`).

So the subject is the **`maintenance_storage` sample series**, the same one `storage_report` accumulates and
`storage_trend` reads: bytes per axis over time. A comparison answers *"space A is growing faster than space
B on this axis"*, ⛔ **not** *"these two spaces' pipelines differ"*. Anyone reading the row's bare title will
assume config diffing; it is not that, and building that instead would be a different feature entirely.

## 2. Grounding — what is true today

| Fact | Where |
|---|---|
| `storage_report` writes one Parquet per run under `<dataDir>/maintenance_storage/`, rows unioned by glob | `StorageReportTask:98-107` |
| That write is **space-scoped on purpose** — the comment cites `MATERIALIZE-SPACE-ROOT-1` | `StorageReportTask:108` |
| `storage_trend` reads `Path.of(dataDir).resolve("maintenance_storage")` — **its own** space's data root, handed in by the runner | `StorageTrendTask:51-53` |
| `created_ms` (epoch millis) is the sortable sample key; ISO strings are not reliably chronological | `jobs.md`, `StorageReportTask` |
| Trend is read-only and **fail-soft on < 2 samples** | `StorageTrendTask` |
| There are **21 built-in maintenance tasks**, and a `case` missing from `BUILT_IN_TASKS` (or the reverse) fails a test by design | `MaintenanceJob:167-172` |

🔴 **The blocking fact: a job cannot see another space today, and that is deliberate.**
`SpaceConfigRoot` holds `ROOTS` and `DATA_ROOTS` as **private** maps (`:36-39`) with `register` /
`registerDataRoot` / `forSpace(id)` — and **no enumeration accessor at all**. A comparison must therefore
either be handed the spaces explicitly or gain a new "list the registered spaces" seam.

⛔ **That seam is the exact shape of two recorded defect families.** `SPACE-UNKEYED-STATICS-1` was
process-wide static state leaking across spaces, and a live multi-space run found **every registry-reading
job type reading a JVM-wide root**. The hardening that followed is *why* these maps are private and why
`storage_report` is space-scoped. ⇒ adding a public enumeration is not a convenience; it is re-opening a door
that was deliberately shut, and it needs to be decided as such. ⚠ `PathJail`'s own note already refuses the
neighbouring shortcut: *"do not reach for `-Dspaces.root` — it is read only by `ControlApi` for space
discovery… a jail whose root disagrees with the gate's is a jail with a documented bypass."*

## 3. Shape, once the decisions land

A new `space_comparison` maintenance task (a 22nd built-in; remember `BUILT_IN_TASKS` **and** the `case`, or
the test fails by design), read-only and fail-soft like `storage_trend`:

* **Params:** `spaces` (which spaces — see Q1), `window_days` (default 30, mirroring trend), `axes`
  (optional filter), `top` (how many axes to report).
* **Per space:** the same two queries `storage_trend` already runs over that space's
  `maintenance_storage` glob — latest bytes per axis (`arg_max(bytes, created_ms)`) and the two-point
  bytes/day slope over the window.
* **Output:** per axis, each space's latest bytes and slope, plus the spread (max−min) and the fastest
  grower. ⚠ **Fail-soft per space, not per run:** a space with < 2 samples reports *"not comparable"* and the
  others still compare — a comparison that refuses entirely because one space is new is useless in exactly
  the situation someone asks for it.
* **Emits** a `maintenance.space.comparison` event, consistent with `maintenance.storage.trend`.

⚠ **Reuse, do not re-implement, the trend's SQL.** Two copies of the slope calculation will disagree the
first time either is touched, and a slope that disagrees between two reports is worse than no report. The
extraction (`StorageTrendTask` → a shared reader taking a data root) is the first code step.

## 4. What this design deliberately does NOT do

* ⛔ **No config/pipeline diffing.** Not what the row means (§1), and a much larger feature.
* ⛔ **No predictive maintenance.** The row says so explicitly; AGT-5 territory.
* ⛔ **No write into another space.** Read-only across the boundary at most; a task that WRITES cross-space
  is a different and much sharper decision than one that reads.

## 5. ⛔ The four decisions owed before code

**Q1 — Which spaces does one run compare?** (a) an explicit `spaces: [a, b]` job param — no new enumeration
seam, the caller names what it may see, and the job stays inert if a name is wrong; or (b) *"every registered
space"* — friendlier, but needs the new public enumeration on `SpaceConfigRoot` that §2 warns about.
**Recommendation: (a).** It gets the feature with **no** new cross-space discovery surface, and (b) can be
added later if listing proves necessary — the reverse is not true.

**Q2 — Is a cross-space READ open, or capability-gated?** Reads are open by policy *within* a space because
confidentiality sits at the Space/ABAC layer — affirmed as the **compliance** position on 2026-09-16
(`controls-matrix.md` CC6). ⚠ A job that aggregates several spaces' volumes into one report **crosses that
boundary by design**, so "reads are open" does not automatically extend to it: the isolation the claim rests
on is precisely what this feature spans. **Recommendation: gate the task behind a capability** (`canAdminister`
is the existing installation-level name) and say so in CC6, rather than let an open read quietly become
cross-tenant aggregation.

**Q3 — Where does the output land?** The running space's catalog is the obvious home, but then space A's
catalog holds space B's volumes — a data-residency question, not a plumbing one. Alternative: emit the event
only and persist nothing. **Recommendation: event only in v1**; persisting cross-space rows is a separate call.

**Q4 — Does `SpaceConfigRoot` gain a public enumeration?** Only if Q1 answers (b). ⛔ If it does, it needs the
`SPACE-UNKEYED-STATICS-1` lesson applied in the same change: an accessor that returns registered ids is also
an accessor a future job will use to read a JVM-wide root by accident.

## 6. Verification, when it is built

`mvn -o -Pedition-enterprise -Dtest=MaintenanceJobTest,SpaceComparisonTaskTest test` — plus the
`BUILT_IN_TASKS`/`case` congruence test that already exists, which will fail by design if the new task is
added to only one of the two. A live check needs two spaces that have both run `storage_report` at least
twice; ⚠ the sample tree may not have that, and **a comparison test over one space proves nothing**.
