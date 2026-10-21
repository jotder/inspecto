# Link Analysis edge index — 10^9 scale run plan (clause 3 of `LA-INDEX-SCALE-MEASURE-1`)

**Audience:** whoever runs the measurement on a larger machine. **Status:** plan only; nothing in it was run at 10^9 or 5x10^8
by the author. Derived numbers are marked *derived*; measured ones cite the 2026-10-05 and 2026-10-13 runs recorded in
[`okf/product/module/la-core/user-guide-link-analysis.md`](../la-core/user-guide-link-analysis.md) §*Index scale measurements*.
Harness: `la/inspecto-la-storage/src/test/java/com/gamma/la/storage/IndexScaleBench.java` (the only harness needed;
`DailyIndexScaleBench` and `DraftConcurrencyBench` are separate and not part of this clause).

**Update 2026-10-21 (lane `la-nodes-mem`).** The `nodes` phase that failed every run above 10^8 is now folded one bucket at a time and the link
statistics' degree statement is bounded (see user guide §*The `nodes` phase and the degree statistics, bounded*): a 10^8-edge FULL build now
completes at `buildMemory=1GB` (642 s; the unmodified code died in the statistics at 2 GB). **Correction that changes the sizing below:** the
bench corpus has about 1.8 M distinct node ids at every scale, not `edges / 5` (that is the id domain), so every "per distinct node" memory
figure in the earlier version of this plan was wrong and is withdrawn; what the old statement held grew with the distinct (node, neighbour, kind)
links, about one per edge. No build above 10^8 edges has run since the change, so sections 2, 5 and 6 are the plan for the first one, not a result.

## 1. What is being decided, and what is already known

The edge index (`IndexBuilder`, `IndexReader`, `IndexedTraversal`) is claimed to serve a one-key lookup in about 20 ms and a depth-2
walk within the 20-key frontier cap at any N. That is measured to 10^8. Open: a **completed FULL build at 5x10^8 and 10^9 edges**,
the K = 8 delta behaviour and read latencies there, and COMPACT at that N. The result decides whether the 10^8 claim, the
34 bytes/edge size estimate, the 2x rebuild-peak figure and the delta cap `MAX_DELTAS = 8` move, and what the sizing note for a
customer says. Trigger (BACKLOG row): before `index.enabled` becomes default-on, or before the depth or key cap, `MAX_DELTAS`
or the 10^8 claim moves.

**Measured baseline** (Windows laptop, 12 hardware threads, 32 GB RAM, warm, 60 random keys per series, corpus = D-S5 skew with
`nodes = edges / 5`):

| Edges | Corpus | FULL build | Buckets | Index | 1 key p50 / p95 | 1 key p50 at 8 deltas | compacted p50 | depth-2 walk p50 / p95 |
|---|---|---|---|---|---|---|---|---|
| 10^6 | 20 MB | 4 s | 16 | 0.02 GB | 26.6 / 43.5 ms | 35.3 ms | 18.3 ms | 229 / 538 ms |
| 10^7 | 0.2 GB | 22 s | 16 | 0.23 GB | 20.2 / 27.3 ms | 29.9 ms | 15.7 ms (COMPACT 19.8 s) | 32 / 489 ms |
| 10^8 | 2 GB | 447 s (8 GB cap) | 32 | 2.23 GB | 20.9 / 31.0 ms | 37.8 ms | not run | 21.8 / 32.0 ms |
| 5x10^8 | 10 GB (341 s) | **OOM in `nodes` at 91 min** (8 GB cap; `out` 36 min, `in` 42 min) | 128 | not produced | — | — | — | — |
| 10^9 | 20 GB (1519 s) | **stopped** at 39 min in `out` (disk floor) | 256 | not produced | — | — | — | — |

Also measured: a hub key 36.2 to 56.2 ms and a 20-key lookup 408 to 766 ms at 10^8 (0 to 8 deltas); about +0.2 ms per delta;
spill peak about 18 GB at 5x10^8; 21.8 GB of index plus spill written when the 10^9 run was stopped at free disk 145 GB of 476 GB
against the operator's 30 % floor. Bucket count is `clamp(pow2(ceil(edges / 4e6)), 16, 1024)` (`BucketFunction.bucketsFor`),
so 10^9 gives 256 and 5x10^8 gives 128.

## 2. Sizing (arithmetic)

Per-row costs, all from the measured runs:

| Quantity | Per edge | Basis |
|---|---|---|
| Parquet corpus | 20 B | 20 GB / 10^9 (measured); 10 GB / 5x10^8 |
| Index (no weight columns) | 22.3 B | 2.23 GB / 10^8; the documented 34 B/edge is the planning upper bound |
| Spill + stage peak | 36 B | 18 GB / 5x10^8 (measured); the 10^9 run needed "more than 65 GB working disk" beside the corpus (row) |
| Build memory | n/a | **Before 2026-10-21:** the `nodes` statement died at 6 GB for 2x10^8 and 3x10^8 edges, at 7.4 GiB (8 GB cap) and 11.1 GiB (12 GB cap) for 5x10^8 - about 20 to 30 B of cap per edge, not per node (the corpus has 1.8 M nodes). **After:** completes at 1 GB for 10^8 edges (`out` needs more than 0.5 GB at 8 threads); above 10^8 unmeasured |

**Memory lever.** The build runs inside DuckDB, so the lever is DuckDB's `memory_limit`, set from `-Dinspecto.bench.buildMemory`
(default `8GB`; `IndexBuilder.open` issues `SET memory_limit`), **not** `-Xmx`. The JVM heap holds only key lists and timings; leave
it at the default (a quarter of RAM is far more than needed). DuckDB's own allocations sit outside the heap. The earlier planning
figure (150 B per distinct node, 15 GB at 5x10^8, 30 GB at 10^9) was derived from a node count the corpus does not have and is
withdrawn. With the `nodes` phase bounded the lowest cap that completed at 10^8 edges and 8 threads is 1 GB (0.5 GB fails in `out`, the sort
that writes `out` and `in`, which this change did not touch); nothing above 10^8 edges has run, so how `out`, `in` and the statistics' global
steps grow with N is the thing the first big run measures. The 24 GB / 48 GB rows below are therefore generous first attempts, not
requirements: start at the 12 GB the 5x10^8 lane had, and raise it only if the build dies in `out`, `in` or the statistics.

| Run | `buildMemory` | Machine RAM, minimum | RAM, comfortable | Free disk, minimum | Free disk, comfortable | Wall time (derived) |
|---|---|---|---|---|---|---|
| 5x10^8 retry | `24GB` (15 GB x 1.5 rounded) | 32 GB (tight) | 64 GB | 100 GB | 150 GB | build 1.5 to 2.5 h; whole run 3 to 5 h |
| 10^9 | `48GB` (30 GB x 1.5 rounded) | 64 GB | 128 GB | 250 GB | 400 GB | build 4 to 8 h; whole run 9 to 17 h |

Disk arithmetic (all *derived* except the corpus and spill figures):
* 5x10^8: corpus 10 + spill 18 + staged index 11 to 17 + a COMPACT copy 11 to 17 + the live base index during COMPACT 11 to 17 = 61 to 79 GB; x1.5 margin = 92 to 119 GB, so 100 GB minimum.
* 10^9: corpus 20 + spill 36 (up to 70 if the "65 GB working disk" holds) + staged index 22 to 34 + COMPACT copy 22 to 34 + live base 22 to 34 = 122 to 192 GB; x1.3 margin and a free-space floor you set yourself (the laptop run stopped at 30 % free) = about 250 GB. DuckDB's default `max_temp_directory_size` is 90 % of the free space of the spill disk, so a nearly full disk makes the build fail rather than fill it.
* Put corpus (`-Dinspecto.bench.dir`) and index+spill (`-Dinspecto.bench.idxDir`; the spill is `<idxDir>/idx-N/vNNNNNN.tmp/.spill`) on one fast local NVMe, or the index on NVMe and the corpus on any disk. Network or throttled-IOPS volumes will turn a 4 to 8 h build into days.

**Wall time reasoning.** 10^8 took 447 s; the trend is ~20x per 10x (4 s, 22 s, 447 s), which says 2.5 h at 10^9, but 5x10^8 already
spent 78 min in `out` + `in` alone (about 10x the 10^8 total for 5x the rows) while memory-starved and spilling. More RAM removes
spill, so the 4 to 8 h range assumes the 48 GB limit; the lower end needs NVMe and at least 16 threads. COMPACT at 10^7 cost about
what the FULL build did (19.8 s vs 22 s), so budget **COMPACT at 10^9 = one more FULL build** (not run at 10^8, unknown). Corpus
generation is single-threaded DuckDB `COPY` per 5 M-row file: 1519 s at 10^9.

**Minimum machine (10^9):** 16 hw threads, 64 GB RAM, 250 GB free local NVMe, Linux or Windows, JDK 27. **Comfortable:** 32 hw
threads, 128 GB RAM, 400 GB NVMe. Do the 5x10^8 retry first on the same box: it validates the memory ceiling before the 10 h
run commits.

## 3. Commands

Prerequisites: JDK 27 (`JAVA_HOME` must be JDK 27 **and** the first `java` on `PATH` must be 27; a mismatch fails Maven with
"release version 27 not supported"), Maven, the repo checked out at `29e320dfd` or later, and a populated `~/.m2` (first run on a
new box: drop `-o`, or run `mvn -Pedition-standard -am -pl :inspecto-la-storage dependency:go-offline`). The DuckDB JNI
flag `--enable-native-access=ALL-UNNAMED` is already in the surefire `argLine` (root `pom.xml`), so the Maven route needs nothing
extra; it is required only if you launch `java` by hand. `-D` properties on the Maven command line reach the forked test JVM.

Every property the bench reads (all `-Dinspecto.bench.*`; `IndexScaleBench.java`):

| Property | Default | Meaning |
|---|---|---|
| `dir` | none, **required** | corpus root; the bench is skipped without it. Corpus in `dir/data-<edges>`. |
| `idxDir` | `dir` | parent of `idx-<edges>` (index versions and the build spill) |
| `edges` | 10000000 | N; `nodes = max(1000, N / 5)` |
| `buildMemory` | `8GB` | DuckDB `memory_limit` for build/APPEND/COMPACT |
| `threads` | all | DuckDB build threads (2026-10-13 runs used 8) |
| `samples` | 60 | random keys per latency series |
| `deltaEdges` | 100000 | edges per appended delta file |
| `maxDeltas` | 8 | deltas appended (series measured at 1, 2, 4, 6 and `maxDeltas`) |
| `skipDeltas` | false | stop after the base series (`-Dinspecto.bench.skipDeltas=true`) |
| `skipCompact` | false | stop after the delta series |
| `buildOnly` | false | stop after the FULL build and the `SCALE edges=N phase ms {...}` line (phase timings), before the latency series |

Also readable by the build (not by the bench): `-Dprocessing.duckdb.max_temp_directory_size=<e.g. 200GB>` (spill cap; see
`DuckDbUtil.PROP_MAX_TEMP_DIRECTORY_SIZE`).

**Linux (bash)**

```bash
export JAVA_HOME=/opt/jdk-27 PATH=/opt/jdk-27/bin:$PATH
export B=/data/la-bench            # NVMe, >= 250 GB free
mkdir -p $B/idx && df -h $B
# smoke first (about 2 min after compile): proves the commands before the long run
mvn -o -Pedition-standard -am -pl :inspecto-la-storage test -Dtest=IndexScaleBench \
  -Dinspecto.bench.dir=$B -Dinspecto.bench.idxDir=$B/idx -Dinspecto.bench.edges=1000000 \
  -Dinspecto.bench.samples=20 -Dinspecto.bench.maxDeltas=2 -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee $B/smoke.log | grep -E "SCALE|Tests run|BUILD"
rm -rf $B/data-1000000 $B/idx/idx-1000000
# the series (each is a separate invocation; run in this order)
for N in 500000000 1000000000; do
  nohup mvn -o -Pedition-standard -am -pl :inspecto-la-storage test -Dtest=IndexScaleBench \
    -Dinspecto.bench.dir=$B -Dinspecto.bench.idxDir=$B/idx -Dinspecto.bench.edges=$N \
    -Dinspecto.bench.buildMemory=$([ $N = 500000000 ] && echo 24GB || echo 48GB) -Dinspecto.bench.threads=16 \
    -Dsurefire.failIfNoSpecifiedTests=false > $B/run-$N.log 2>&1
done &
tail -f $B/run-500000000.log | grep --line-buffered "SCALE"
```

**Windows (PowerShell)**

```powershell
$env:JAVA_HOME = 'C:\sandbox\.graalvm-cache\jdk-27-win'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$B = 'D:\la-bench'; New-Item -ItemType Directory -Force "$B\idx" | Out-Null
foreach ($N in 500000000, 1000000000) {
  $mem = if ($N -eq 500000000) { '24GB' } else { '48GB' }
  mvn -o -Pedition-standard -am -pl :inspecto-la-storage test -Dtest=IndexScaleBench `
    "-Dinspecto.bench.dir=$B" "-Dinspecto.bench.idxDir=$B\idx" "-Dinspecto.bench.edges=$N" `
    "-Dinspecto.bench.buildMemory=$mem" "-Dinspecto.bench.threads=16" -Dsurefire.failIfNoSpecifiedTests=false 2>&1 |
    Tee-Object "$B\run-$N.log" | Select-String 'SCALE|Tests run|BUILD'
}
```

(Run the smoke series from the bash block with `edges=1000000`, `samples=20`, `maxDeltas=2` first; it was dry-run for this plan on the
author's laptop: the commands work as written, the tests ran, and the bench printed the lines below.)

**Expected output** (stdout of the test JVM, also in `target/surefire-reports/` of `la/inspecto-la-storage`; grep `SCALE`), in order:
`SCALE edges=N nodes=N/5 files=F inputs generated (secs)` (F = N / 5 M: 100 at 5x10^8, 200 at 10^9), then
`SCALE progress estimate 1/6 ...`, `out 2/6`, `in 3/6`, `nodes 4/6`, `verify 6/6`, `publish 6/6` (each with a wall-clock time:
this is the progress log; `out` and `in` are the long phases), `SCALE edges=N FULL build S s, B buckets, G GB index, E edges kept`,
the four base latency lines (`one-hop (1 key)`, `one-hop (20 keys)`, `hub key (n0..n19)`, `depth-2 walk`), then for deltas 1..8:
the build progress, `SCALE N=... APPEND d (+100,000 edges) S s, deltas in manifest=d`, and latency lines after deltas 1, 2, 4, 6 and 8,
then `SCALE N=... COMPACT S s` and the `compacted` latency lines. End state: `Tests run: 1, Failures: 0` and `BUILD SUCCESS`.

**Watch it** (a build is hours; the bench prints no size line before the end):

```bash
watch -n 300 "df -h $B; du -sh $B/data-* $B/idx/idx-*/* 2>/dev/null"
```

## 4. What to record, and where

One row per N; copy the log's `SCALE` lines into the notes of the run, not into the repo. Table template (same columns as the
existing OKF table, plus the added measurements):

| Edges | FULL build (phases est/out/in/nodes/verify/publish) | buildMemory, threads | Peak RSS / spill peak | Index size | 1 key p50 / p95, 0 deltas | hub key p50 | 20-key p50 | depth-2 walk p50 / p95 | 1 key p50 at 1 / 2 / 4 / 6 / 8 deltas | APPEND time per delta | COMPACT time | 1 key p50 compacted |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 5x10^8 | | | | | | | | | | | | |
| 10^9 | | | | | | | | | | | | |

Peak RSS and the spill peak are not printed by the bench: sample them with `watch`/`Get-Process` while the build runs and write the
maximum down (see follow-up 1). Compare against the 10^8 row; the pass criteria are in section 6.

**Write the result back** (the person running this lane edits; this plan's author did not):
1. `docs/okf/product/module/la-core/user-guide-link-analysis.md` §*Index scale measurements*: add the 5x10^8 and 10^9 rows to the table, replace the
   2026-10-13 attempt bullet's "no 5x10^8 or 10^9 figure exists" sentence, and update the 34 GB / 2x rebuild-peak caveat.
2. The `LA-INDEX-SCALE-MEASURE-1` row in `docs/BACKLOG.md`: close clause (3) with the measured figures, or narrow it to what is still open.
3. Then re-evaluate the `DraftConcurrencyBench` 10^9 re-run noted in the row, and the sizing text in the stakeholder documents that quote 10^8.

## 5. Failure modes seen, and what to do

| Seen | Cause | Do |
|---|---|---|
| 10^9 FULL build stopped in `out` at 21.8 GB written | free disk hit the floor; working disk exceeds 65 GB beside the corpus | the disk in section 2 (250 GB free); put `idxDir` on its own volume; check `df` before starting |
| `Out of Memory Error` in `nodes` (progress stops at `nodes 4/6`) | the unmodified statement (one global fold of all edges), fixed 2026-10-21 | only on a checkout older than the fix: update. On a current one, a failure after `nodes 4/6` and before `verify 6/6` is a bucket that does not fit: report the bucket size (`index.edges_per_bucket`) and the cap |
| `Out of Memory Error` after `verify 6/6` and before `publish` | the link statistics (`linkStats`: distinct-links table, degree table, quantiles); the grouped `count(DISTINCT)` of the degree died at 2 GB over 10^8 edges and was replaced | raise `buildMemory` and record the figure; the statistics have no progress step, so this window is the only sign |
| `Out of Memory Error` in `out` or `in` (progress stops at `out 2/6` or `in 3/6`) | the sort that writes the table needs about 0.5 GB or more at 8 threads; at 10^8 edges 512 MB failed after 88 s | raise `buildMemory`, or lower `-Dinspecto.bench.threads` |
| `index build failed: null` after a long build | the (fixed) relation-hash regex `StackOverflowError`; pinned by `IndexBuilderTest.theRelationHashSurvivesALongPinnedFileList` | only if the repo is older than the fix: update |
| Interrupted corpus generation, then rerun | the bench treats an existing `data-N` directory as complete (`fresh = !isDirectory`), so a half-written corpus is reused | delete `data-N` before rerunning after any interruption in the corpus phase; the file count must be N / 5 M |
| Failed or killed build leaves tens of GB | `idx-N/vNNNNNN.tmp/.spill` and the stage stay; `IndexStore.stage()` removes crashed stages only after one hour | `rm -rf idx-N/v*.tmp` before the retry; never delete a published `vNNNNNN` |
| Rerun after a successful FULL build | the bench reuses the current index and then appends deltas 1..8 again from a fresh corpus | do not rerun to add COMPACT (see follow-up 2); delete `idx-N` to start the series clean |
| Laptop throttling, Windows Defender scans of the bench directory | measured latencies and build time inflate | exclude the directory; run on an otherwise idle box, as the comparison harness (`tools/bench-duckdb.ps1`) already requires |

Fallback ladder if the first attempt fails: 5x10^8 first; if 10^9 will not fit, a **smaller series** (2x10^8, 3x10^8, 5x10^8)
still shows the slope; the corpus is generated per N, so a partial result at a smaller N costs one corpus only. There is no way to
split a single build across machines: the build is one DuckDB COPY pass per table.

## 6. Go / no-go checklist and cost

**Go** only if all are true:
- [ ] Free disk on the `idxDir` volume and the corpus volume matches section 2 for the N about to be run (check with `df`, not the disk size).
- [ ] RAM at least `buildMemory` + 16 GB; nothing else heavy running; swap or pagefile not the way the limit is "met".
- [ ] `java -version` prints 27 and `JAVA_HOME` points at JDK 27; `mvn -v` agrees.
- [ ] The 10^6 smoke finished with `Tests run: 1, Failures: 0`.
- [ ] The run is inside `nohup` / a detached session, the instance will not auto-stop, and the log is on the volume that survives.
- [ ] 5x10^8 completed (or was run in the same session) before 10^9 started.

**Pass criteria** to claim the 10^8 statement holds at 10^9: key lookup p50 under about 50 ms and within about 2.5x of the 10^8 figure;
the 20-key and depth-2 figures dominated by the frontier, not N; 8 deltas within about 2x of the base; COMPACT restores it;
index size within the 34 B/edge planning bound (about 34 GB). Anything else is a finding to record, not a failure of the run.

**Cloud cost (assumptions only; look the prices up on the day, none were browsed).** Assume one memory-optimised Linux instance
with local NVMe, 32 vCPU, 128 GB RAM and at least 400 GB local NVMe (or 500 GB fast network block storage with provisioned IOPS in
the thousands). Hours: 10^9 whole run 9 to 17 h, 5x10^8 3 to 5 h, smoke and compile 0.5 h; plan 14 to 24 h in all, plus the
re-run margin. Cost = hours x on-demand hourly price (look up the instance family with 128 GB and local NVMe in the chosen
region) + block storage GB-months x price (about one day) + egress for the logs (negligible). A spot instance saves most of the
hourly price but the build cannot resume after a reclaim (follow-up 2), so use on-demand for the 10^9 build. A smaller box
(64 GB, 16 vCPU, 250 GB) halves the hourly price but lengthens the 10^9 build; the minimum machine is viable only if the 5x10^8 retry
passes first.

## 7. Follow-ups (not built)

1. **Peak RSS and spill-peak recording** in the bench: a sampler thread printing `SCALE resources rssMB=... spillGB=... freeGB=...` every 60 s would give the figures section 4 asks for without an outside watcher (about 20 lines in `IndexScaleBench`; not done here because the plan was docs-only).
2. **Resumable series** (`-Dinspecto.bench.only=compact|latency` and a guard that a rerun does not re-append deltas): a 10^9 FULL build is hours, and today a crash after it, or a wish to add COMPACT later, means a rebuild.
3. A corpus-complete marker (`data-N/_done`) so an interrupted generation is not silently reused.
4. A `-Dinspecto.bench.freeFloorGB=` (planned, not built) that aborts the build before the disk fills.
