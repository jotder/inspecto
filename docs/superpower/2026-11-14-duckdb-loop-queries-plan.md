# DuckDB loop queries — C3 temporal scan in DuckDB, C1 `USING KEY` traversal (plan, 2026-11-14)

> 🟡 **IN PROGRESS.** Board row `DUCKDB-2-LOOP-QUERIES-1`. Candidates and their gates come from
> [`duckdb-2-plan.md`](../archived-documents/plans-archive/duckdb-2-plan.md) §7. The operator chose **C3, then C1**
> on 2026-11-14; C2, C4, C5 and C8 stay on the row.

## 1. Scope and decisions (operator, 2026-11-14)

| # | Decision |
|---|---|
| D-LQ1 | Take **C3 then C1**. C2 (components by label propagation), C4 (`ASOF JOIN` branching legs), C5 (one-statement expand ladder) and C8 (`ducklake` spike) stay open on the row. |
| D-LQ2 | **C1 is benchmarked at 10^6 and 10^7 edges on both drivers now**, not 10^8: the shared machine is busy and a 10^8 set is several GB. Adopt or reject only if the 10^7 result is clear; otherwise the 10^8 run stays on the row for a quiet window. |
| D-LQ3 | **C3 keeps the `pattern.temporal_max_rows` cap and raises its default**, sized by the 10^7-event bench. The cap still bounds what a scan reads, so `rowCapped` keeps its meaning. |

## 2. C3 — temporal `burst` / `periodicity` in DuckDB

**Today.** `POST /inv/pattern/temporal` (`PatternRoutes.scan`, also behind the Investigation `temporal` op,
`TemporalFindings`) reads up to `pattern.temporal_max_rows` rows (`source, target, ts`) ordered by link and time, flat
or from the link index (`IndexedTemporal`), hauls every row to the JVM, and runs `LinkTemporalDetector` per link or
per entity.

**Design (assistant, D-LQ4).** DuckDB does the cut, the JVM keeps the arithmetic. One statement over the same
ordered, capped rows returns only the rows of series that **can** hold a finding, plus the two counts the response
states (`skippedNoTime`, `rowCapped`):

- **burst**: a series is a candidate when, in time order, some event's `(minEvents - 1)`-th successor is within the
  window — `lead(ts, minEvents - 1) OVER (PARTITION BY series ORDER BY ts) - ts <= w`. That is exactly the detector's
  own condition, so no series that the detector would report is dropped (first written as a `RANGE` frame count; see
  §2a for why it changed).
- **periodicity**: a candidate needs `n >= max(minEvents, 3)`, a positive mean gap and a gap CV within `maxCv` plus
  a small tolerance; DuckDB's `stddev_pop / avg` can differ from the kernel's in the last bits, so the tolerance only
  widens the cut and the detector re-decides exactly.
- `LinkTemporalDetector` then runs unchanged on the candidate series, so findings, their order, `cv` values, and a
  sealed Investigation's fingerprint are byte-identical to the haul-everything read **by construction**.

**Proof before adoption.** A golden parity test runs the old read (every row hauled, detector in Java) and the new
one over fixtures covering ties at a window edge, null times, self-loops in entity mode, a series cut by the row cap,
zero gaps and a CV at the threshold, for both modes, both series kinds, flat and index-served. Then a 10^7-event bench
of the new statement against the old haul, which sizes the new cap default (D-LQ3).

### 2a. C3 bench (2026-11-14, `TemporalCutBench`, 10 088 000 events, 200 000 links, 12 threads, shared machine)

Best of 3, DuckDB in-memory. "Haul" is the old read up to the JDBC row loop only (it leaves out the dataset provider's row
maps and the JVM detector, so it understates the old cost); "cut" is the whole new statement.

| Cap | Mode | Haul | Cut | Rows to the JVM |
|---|---|---|---|---|
| 400 000 | burst / periodicity | 1.22 / 1.19 s | 1.34 / 1.42 s | 400 000 → 1 660 / 1 992 |
| 2 000 000 | burst / periodicity | 4.38 / 5.00 s | 3.59 / 4.07 s | 2 000 000 → 7 980 / 9 576 |
| 4 000 000 | burst / periodicity | 9.00 / 8.97 s | 6.11 / 6.54 s | 4 000 000 → 15 920 / 19 104 |
| 10 000 000 | burst / periodicity | 18.1 / 18.4 s | 14.7 / 23.9 s | 10 000 000 → 39 680 / 47 616 |

**Reading.** The DuckDB time is dominated by the ordered, capped read both versions share, so the statement itself is
not much faster; the win is that about 0.4 % of the rows reach the JVM, which is what made the old read's memory and
detection cost grow with the cap. **D-LQ5 (assistant): default `pattern.temporal_max_rows` 400 000 → 2 000 000** -
about 4 s, well inside the 10 s `pattern.timeout_s`; 4 000 000 (6.5 s) is too close to the timeout on a busy host. A
Space can still raise it to its 20 000 000 ceiling with a matching timeout.

**After the bench, the burst cut changed form.** The bench's burst rows used a `RANGE` frame count. A single link with
2 000 000 events (`ControlApiInvTemporalTest.aScanPastTheRowCapSaysSo` at the new cap) ran that frame past the 10 s
timeout, since one partition gets no parallelism. The cut now asks `lead(ts, minEvents - 1) - ts <= w`, which is the
detector's own condition exactly and an offset window, not a frame; that test now answers in about 8 s of test time and
the parity test still holds. The burst rows above were not re-measured with the new form.

## 3. C1 — `USING KEY` traversal behind `SqlGraphEngine`

Not started. Gate as in §7 of the DuckDB 2 plan: `InvTraversalBench` G-R4 vs `IndexedTraversal` / `SqlGraphEngine`,
parity against `graph-paths-parity.fixture.json`, the `SqlGuard` allow-list for the `USING KEY` node, the visited-node
cap and 5 s timeout in place of the 2-hop / 20-key caps. Scale per D-LQ2.

## 4. Status

| Step | State |
|---|---|
| C3 statement + parity test | **built 2026-11-14**: `TemporalCut` (la-core) behind both the flat read and `IndexReader.scanTemporalCut`; `TemporalCutParityTest` (192 combinations, findings / `skippedNoTime` / `rowCapped` equal to the haul-everything read); the temporal route tests green (flat, index-served, Investigation op) |
| C3 10^7 bench, new cap default | **done 2026-11-14**: default `pattern.temporal_max_rows` 400 000 → 2 000 000 (§2a) |
| C1 engine, parity, bench | — |

## References

- [`duckdb-2-plan.md`](../archived-documents/plans-archive/duckdb-2-plan.md) §7 — the candidates and their gates
- `la/inspecto-la-core/src/main/java/com/gamma/la/core/LinkTemporalDetector.java` — the detector kept as the arithmetic
- `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java` — `scan`, the route both callers share
