# Reconciliation uplift: usability and performance plan

> 🟡 **PLAN 2026-10-18 (`RECON-UPLIFT-1`): design only, nothing built.** Decisions D-RU1…D-RU8 were taken on the operator's
> behalf (the operator asked for smart decisions; each carries its reason and how to reverse it, §6). **Method:** three
> read-only reviews run in parallel (backend, UI, a measured baseline), then every top finding was checked against the code
> before it went into this plan. **What is NOT known:** behaviour above about 400k rows. No benchmark or test above that
> exists, so every claim about 10M rows is a hypothesis until slice B0 measures it.
> 🟢 **Lane A (backend) 2026-10-18: B0 measured and B1 built; B2 and B3 closed unbuilt** (no failure at 10M key groups, no deep-offset
> cost): numbers, verdicts on P2/P4/P5/P6 and the as-built shared grain are in the OKF concept below. UI lanes B and C are separate.
> As-built behaviour: [`okf/frontend/features/reconciliation.md`](../okf/frontend/features/reconciliation.md); module page:
> [`stakeholders/modules/reconciliation.md`](../stakeholders/modules/reconciliation.md).

## 1. What was measured

`ReconPerfTest` (`RECON_PERF=1`, the `RECON-PERF-1` harness, 30 days × 4,000 keys × 3 sides, 12 logical CPUs, run 2026-10-18
with `-Pedition-standard`; the reconciliation module is not in the default reactor, and `-pl` without `-am` tests the installed
sibling jars):

| Measure | Result |
|---|---|
| whole-relation run, first page (limit 5000) | best of 3 = 155 ms |
| whole-relation **record compute** | 455 ms for 2,164 Breaks |
| one day, first page, unpruned / pruned | 131 ms / 60 ms |
| one day, Breaks compute, unpruned / pruned | 149 ms / 54 ms |
| one day, cached page | 0.35 ms |
| one day, sample of 1,000 keys | 184 ms (pruned: 93 ms) |

Reading: a cached page is free and day pruning halves the cost, which is why the existing design is sound at this size. Recording
a run costs about **3× a page** (455 vs 155 ms), which fits the code finding below that recording re-scans each side. At 360k
rows these are all fast, and nothing here predicts 10M-row behaviour.

## 2. Backend findings (performance), checked against the code

`ReconService.java`, `ReconBreaks.java`, `ReconRunJob.java`, `ReconRoutes.java`, `ReconStateStore.java`, all under
`features/inspecto-reconciliation/src/main/java/com/gamma/recon/`.

| # | Finding | Status |
|---|---|---|
| P1 | **A run scans each side several times.** `ReconRunJob:102` calls `dayRun` (builds `__grain`: each side pre-aggregated, then a FULL OUTER JOIN chain), then `:110` calls `ReconBreaks.compute`, which opens another sandbox and re-aggregates each side once per Break type (`ReconService.breaks`, per pair on a 3-way run). `/record` (`ReconRoutes:553`) repeats `compute`. `__grain` already carries the classification, so the second pass is redundant work. | **Verified** in code; the cost at scale is **not measured** |
| P2 | Every call uses a fresh in-memory DuckDB sandbox: 1 GB memory, 2 threads, 30 s timeout (`SqlSandboxPolicy`). A 10M-row GROUP BY plus join may spill or hit the timeout. | Verified limits; the effect is **a hypothesis** |
| P3 | `/recon/run`, `/breaks`, `/rows` and `/record` run synchronously in the HTTP handler. Only the `recon.run` Job Type is async. | Verified |
| P4 | A day of up to 200,000 key groups is materialised as `List<Map>` and cached (32 large entries). | Verified; the memory size is **a hypothesis** |
| P5 | Recorded Breaks are capped at 50,000 (`MAX_BREAKS`) and refused with 422 past it; state is **one JSON file per reconciliation**, re-read and re-written whole on each status change (cap 200,000). | Verified; deliberate caps |
| P6 | Break paging is `LIMIT/OFFSET`, so a deep offset re-sorts the set. `/breaks` limit is clamped to 5,000, offset is not bounded. | Verified |
| P7 | Sampling scans and sorts every key (a hash order), so it cuts the output but not the scan. Capped at 100,000 keys. | Verified |

## 3. UI findings (usability), from the SPA review

`inspecto-ui/src/app/modules/admin/reconciliation/`. The review read code only and ran nothing in a browser.

| # | Finding | Status |
|---|---|---|
| U1 | The Break detail page loads **all** Breaks from `/recon/breaks` into the data table: no server paging, and the only break-type filter is the three section tables. The Board already pages server-side (`recon-grain-table.component.ts`), so the pattern exists. | Verified; table sort/export features **not checked** |
| U2 | Key and compare columns are picked from the **left** Dataset only; the right and third Dataset's columns are never shown, and nothing checks that a key exists on every side or that a compare column is numeric. A bad definition fails at run time. | Verified |
| U3 | "Run Reconciliation Now" and the Refresh icon do the same thing; the Board's Refresh and "Record run" are easy to confuse. | Verified |
| U4 | Progress is an indeterminate spinner (no elapsed time, no cancel). | Verified |
| U5 | The `recon.run` Job has **no schedule screen** in this module: the one backend capability the UI does not surface. All 10 routes are otherwise called. | Verified (by route name) |
| U6 | No skeleton loaders (spinner only); one banner uses literal `border-gray-200 dark:border-gray-700`. Whether that breaks `lint:tokens` was **not checked**. | Partly verified |
| U7 | There is no Case link from a Break, only Incident. | Verified, and **correct**: a Case groups Incidents (GLOSSARY §9) |
| U8 | No named saved views; day, filter, page and sample are already in the URL, so a view is shareable. | Verified |

No open `BACKLOG.md` row exists for reconciliation (`RECON-PERF-RESIDUALS-1` and `RECON-3WAY-RECORDED-BREAKS-1` are closed).

## 4. The design

**Principle: measure first, then change only what a number justifies.** The backend is already fast at the size tested and
has deliberate caps, so a rewrite with no measurement would be a guess. The UI findings are structural and need no
measurement.

### Backend slices

| Slice | What | Done when |
|---|---|---|
| **B0** | **Scale measurement.** Parameterise `ReconPerfTest` (keys per day and days) and measure at 1M, 5M and 10M key groups: run, record compute, one status change on a 50k-Break state file, deep-offset Break page, peak JVM heap. Record the numbers in `okf/frontend/features/reconciliation.md` next to the existing ones. | a table of measured numbers, and a written verdict on each of P2, P4, P5 and P6 |
| **B1** | **One scan per side.** Run and record share one sandbox: build `__grain` once, then derive the missing, value and cardinality Break sets from it by `SELECT` over the `__cls` bitmask. Keep the old path as a test oracle until a parity test proves identical Breaks (same keys, types, values, order) on the fixture, a 3-way fixture and a day-pruned fixture. | the parity test is green, and B0's numbers show the record-compute ratio to a page (3× today) falling toward 1× |
| **B2** | **Raise the limits only if B0 says so.** If B0 shows the 1 GB / 30 s sandbox fails at a realistic size, make memory and timeout server-gated config items for reconciliation (the `LA-CONFIG-LIMITS-1` pattern), not a new knob per reconciliation. | B0 shows a failure, or this slice is closed unbuilt |
| **B3** | **Keyset paging for Breaks** (page by the last key instead of an offset), only if B0 shows deep-offset cost. | B0 shows a cost, or closed unbuilt |

### UI slices

| Slice | What | Done when |
|---|---|---|
| **U1** | Break detail: server-side paging, a break-type filter and CSV export, following the Board's grain-table pattern. | 100k Breaks open instantly; the page never holds more than one page |
| **U2** | Definition check: columns from **every** Dataset side; flag a key missing on any side and a non-numeric compare column; a **Test definition** button that runs the existing `/recon/run` on a small sample (no new route). | a bad definition is caught in the dialog, not at run time |
| **U3** | Copy and controls: one primary "Run now"; the icon becomes "Reload"; "Record run" gets a one-line explanation of what it persists. | no two buttons do the same thing |
| **U4** | Progress: show elapsed time on the spinner, with skeleton placeholders for the tables. | skeletons from the design system; no layout jump |
| **U5** | A "Schedule this reconciliation" action that opens the Jobs screen with job type `recon.run` and this reconciliation prefilled (check that screen first). | a scheduled run can be created from the reconciliation |
| **U6** | Design-system audit: replace literal gray classes with tokens, run `lint:tokens` and the axe test on the three screens. | the guards are green |

### Lanes (disjoint files, so they run in parallel)

| Lane | Slices | Files |
|---|---|---|
| A, backend | B0, then B1 (B2/B3 only on B0's evidence) | `inspecto-reconciliation` Java and `ReconPerfTest` |
| B, UI detail page | U1, U3, U4, U6 on the detail and Board components | `reconciliation-detail.*`, `recon-board.*` |
| C, UI definition | U2, U5 | `reconciliation-form.dialog.ts`, `reconciliations.component.*` |

Lane A must not change a response shape. If B1 needs one, it stops and says so, because lanes B and C read those shapes.

## 5. Verification

- Unit level per the repo rule: the affected test classes only, never the full reactor per edit.
- B1 is proven by the parity test **and** by B0's before/after numbers. A faster result that differs by one Break is a failure.
- UI slices: `npx ng test` for the touched specs, then drive the real screens in the preview and read the console and network. A unit test cannot see a layout or a paging bug.
- Each lane ends with a `verification` pass before it is called done.

## 6. Decisions taken on the operator's behalf (2026-10-18)

| Id | Decision | Why, and how to reverse |
|---|---|---|
| D-RU1 | Measure at scale (B0) **before** any backend change. | The tested size cannot predict 10M rows; a rewrite on a guess may add risk for no gain. Reverse: skip B0 and start B1 straight away. |
| D-RU2 | B1 shares one `__grain` between run and record, with a parity test, instead of changing the SQL strategy. | It removes the verified repeated scan and keeps every result byte-identical. |
| D-RU3 | No async run routes. Heavy work stays on the `recon.run` Job; interactive routes stay synchronous and cached. | A cached page costs 0.35 ms and the 30 s sandbox timeout bounds the rest; a second async model would double the surface. Reverse: revisit if B0 shows interactive runs past the timeout. |
| D-RU4 | Break caps (50,000 / 200,000) stay as they are. | They are deliberate correctness limits. Changing them is a design change, not a performance fix. |
| D-RU5 | No cancel button and no named saved views. | Runs are paged and bounded by the timeout, and views are already URL-shareable. Reverse: build when a user asks for it. |
| D-RU6 | No Case link from a Break; Incident stays the only path. | A Case groups Incidents (GLOSSARY §9). |
| D-RU7 | "Test definition" reuses the existing `/recon/run` on a sample; no new dry-run route. | No new route means no new gate to clear. |
| D-RU8 | Three lanes, backend and two UI, with disjoint files; lane A may not change a response shape. | They can run at once without overwriting each other. |

## 7. Risks

- **B1 changes a result silently.** This is the main risk, because the output is a financial-style comparison. The parity
  test must cover 2-way, 3-way, a tolerance edge, a duplicate key and a null key before the old path is deleted.
- **B0 may show nothing is wrong.** Then B1 is still worth doing as the cheapest scan reduction, but B2 and B3 close unbuilt,
  and that is a good outcome.
- **The test run used installed sibling jars** (`-pl` without `-am`), so a sibling's change would not show. B0 and B1 must run
  with `-am` or after installing the siblings.
