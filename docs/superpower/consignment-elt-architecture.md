# Consignment-based ELT: one execution model, batch as the unit of work

**Status:** DESIGN — brainstorm captured 2026-08-03, nothing implemented. No code written, no
vocabulary landed, no config schema drafted. This is the record of a design conversation so the next
shift starts from the decisions rather than the transcript.
**Trigger:** operator question — *"why do we need two execution systems? what processor blocks on our
pipeline make sense?"* — asked while scoping a replacement for an existing Kafka-based record-at-a-time
ETL system.

**Scope:** the ingestion execution model, storage layout, summary semantics, and completeness checking.
Does **not** cover the UI/designer surface, the migration path off the current two paths, or the
`ProcessorContext` SPI (§14 — the one deliberately unresolved piece).

---

## 1. Why there appear to be two execution systems

There aren't two models by design. There is one model built from both ends that never met in the middle.

| | Path 1 — file/batch native | Path 2 — graph native |
|---|---|---|
| **Chain** | `CollectorProcessor` → `BatchProcessor` → `BatchIngestStrategy` → `writeAndTrace` → `PartitionWriter` | `PipelineJobRunner` → `PipelineExecutor` → `RowShaper` → `BranchCommitCoordinator` → `PartitionSinkWriter` |
| **Owns** | inbox discovery, batch planning, members, audit, quarantine, manifests, markers, backup, lineage | immutable graph IR, validator, node-type registry + `ServiceLoader` SPI, topological walk, provenance, dry-run |
| **Missing** | any designer model — shape is hardcoded in the strategy classes | any concept of a *file* — starts from `source_store` seeds, i.e. data already at rest |

Path 1 is "how files become tables." Path 2 is "how tables become other tables." The seam between them
is landed Parquet plus a `BatchEvent` — **and every problem on the motivating list lives at that seam**
(batch-as-transaction, per-file status, incremental summary, completeness). That is not coincidence: the
seam is where batch identity is dropped on the floor.

**The merge is not a third system.** It is: make Path 1's stages into node types in Path 2's graph.

## 2. The idea that collapses them: the manifest is a relation

Make the **Consignment manifest a first-class DuckDB relation** that flows on edges exactly as a row set
does. One row per file: path, size, checksum, collector, arrival time, decode status, row counts
produced, per-schema breakdown.

Consequences:

- File-based reporting and per-file status become ordinary SQL, not a bespoke status subsystem.
- Completeness/availability becomes a predicate over the manifest, not a scheduled sweep.
- **Both paths stay one engine** — the executor's topological walk over DuckDB relations is unchanged.
  Only the *shape* of relation on an edge differs.

The machinery for this mostly exists: `PipelineEdge` already carries a `rel`, `PipelineNodeTypes`
already declares `emits`/`accepts` per node type, and `PipelineValidator` already checks wiring against
those declarations. Typed ports are a **new payload class in an existing contract**, not a new subsystem.

Two existing assets worth naming because they already solve stated problems:

- **Transform failure is already solved.** Set-based transform in DuckDB means a bad record does not
  throw — it lands in the `dropped`/`invalid` rel. Failure becomes a routed output, not an exception.
- **Schema evolution is already solved.** `SqlViews.reader` sets `union_by_name=true` for Parquet (added
  so older un-tagged files stay readable alongside `__tags`-bearing ones). That is the append-only
  schema-evolution story, already in production.

## 3. Vocabulary: Consignment

The unit of work is a **Consignment**; the node that forms it is the **Consignment Former**.

*Instance* was considered and rejected — `docs/GLOSSARY.md` reserves Type-vs-Instance for the
template/instantiation distinction ("Visualization Type → Widget"), so bare *Instance* for a unit of work
violates one-word-one-concept.

**Required in the same change that introduces the node:** a `docs/GLOSSARY.md` entry, plus a ban-list
line ⛔ *Batch* → **Consignment** for this concept. Path 1's existing `BatchProcessor` / `BatchEvent` /
`BatchIngestStrategy` keep "batch" as **legacy internals** — not renamed — with the overlap recorded in
the **GLOSSARY** §13 touchpoint table.

## 4. Processor blocks

NiFi-processor analogy for exposition; on the canvas each block below is a **Step**, drawn from the
closed `BuiltinNodeType` vocabulary (GLOSSARY §5) — not a new node kind. Ingress nodes are
Java-implemented and side-effecting; core nodes are SQL-compiled; the post-Consignment tier is where the
payoff sits.

**Ingress** — maps onto the existing `acquisition` → `adapter` → `parser` Steps; no new ingress types
needed (`BuiltinNodeType` is closed on purpose, plan §3.3):
1. **Collector** (`acquisition`) — polls/fetches, emits a manifest with per-file status.
2. **Consignment Former** — groups files into a transaction. Policy: wait-for-time / wait-for-size /
   wait-for-count / go-immediately, and *may or may not* respect Collector file status. **This is the
   single knob trading latency against file size**, and everything downstream is invariant to it.
3. **Decoder / Preprocessor** (`adapter` → `parser`) — the wrapper for formats DuckDB cannot read
   natively (ASN.1, custom text). Operates at **Consignment grain** with per-file parallelism inside, so
   the transaction is the Consignment while throughput stays per-file. May generate temporary files.

**Core (SQL-compiled, exists today)**
4. **Extract/Load** — DuckDB over the decoded files, mapped to schemas; emits a row set per schema plus
   `invalid`/`dropped`.
5. **Transform** — the existing `transform.*` nodes via `RowShaper`.
6. **Sink** — partitioned Parquet. Emits **its own output manifest**, which is what feeds compaction and
   lineage.

**Post-Consignment** — this tier is **not** graph Steps. In-motion (Pipeline) vs at-rest (Job) is a
binding line (`pipeline-graph-design.md` §3.8: *"an at-rest operator cannot be an in-motion node"*), and
summary/reconciliation work runs after the Consignment has landed — i.e. it is at-rest. So:
7. **Custom processors** declaring `grain: BATCH` — incremental summary, reconciliation, Java
   extensions — are **Jobs triggered `on_commit`**, not Steps on the pipeline canvas. This is also why
   `ON_COMMIT_SAME_GRAPH`'s refusal is correct as-is and should not be revisited: it enforces exactly
   this line.
8. **Compaction** — see §6.

Two design rules:

- **Housekeeping is runtime-guaranteed, not designer-wired.** Status and provenance are recorded
  regardless of graph shape. If a designer can forget to wire the status sink, they will. Only
  *reactions* to status are nodes.
- **The manifest accretes, it does not mutate.** Consignment Former emits v1 (raw files); Decoder emits
  v2 (raw + temp files, with per-file decode status). Functional, so provenance and dry-run stay
  coherent. Temp files get a manifest-owned lifecycle, swept on success, failure, and stale-run recovery,
  scoped per Run.

Grain is a declared property of the node type (`RECORD | FILE | BATCH`) that the validator checks and the
palette displays. **Record grain must be SQL. File grain should be `GROUP BY source_file`, not Java
iteration** — per-file Java iteration is the performance cliff; keep it opt-in and make it feel
discouraged.

## 5. Storage: append-only, no catalog

**Decided.** Hive/Iceberg-style directories that DuckDB reads natively. **No catalog, no table format.**
Records are never altered or deleted; a Consignment appends one file per partition. Iceberg was evaluated
and rejected — it would have answered atomic commit, compaction, and reprocessing, but requires a
metadata layer and DuckDB's Iceberg *write* support is unverified.

### 5.1 The load-bearing invariant

> **No output file ever contains rows from two Consignments.**

Reprocessing, summary replacement, and small-file sizing all depend on it. If anything ever coalesces two
Consignments into one file, all three break silently.

Note the invariant is **one-directional**: one Consignment writing *several* files (one per partition, or
one per record-day for summaries) is fine.

### 5.2 Two kinds of delete are not the same thing

- **Whole-file unlink of a file owned by exactly one Consignment** — what replace-by-batch does. Atomic,
  cheap, no read-modify-write, nobody else's data touched. **This is fine.**
- **Rewriting a file holding several Consignments' rows** — read, filter, rewrite, swap. Expensive,
  non-atomic, puts unrelated Consignments at risk. **This is the one to avoid.**

While the §5.1 invariant holds, only the first ever happens.

### 5.3 Reprocessing

**Policy: reprocess the full Consignment and replace its files.** Deletion must be **manifest-driven,
never a partition scan** — because of *partition drift*: if a reprocessed Consignment corrects a
timestamp, its rows land in a different partition than the original run, and "delete C's files under
`dt=X`" leaves stale rows under the old partition. The manifest knows all files C wrote across all
partitions; a directory scan does not.

### 5.4 Batch-atomic visibility — the one thing "no transactions" does not cover

Record-level transactions are genuinely unnecessary. **Consignment-atomic visibility is not.** If the
writer dies after 3 of 7 files, a reader globbing the partition sees a partial Consignment.

The fix already exists in Path 1: `BatchProcessor.commit(...)` does register/manifest/markers/backup and
runs **only** on `"SUCCESS"`, while `writeAudit` always runs. **That commit discipline is what stands in
for the missing catalog.** Carry it forward; do not reinvent it.

### 5.5 Consignment id in three places, redundantly

| Where | Survives | Purpose |
|---|---|---|
| **Manifest** | — | authoritative; only thing that can answer "all files C wrote, across all partitions" |
| **Filename** | manifest loss | hand-recoverable. Make the id sortable (`YYYYMMDDTHHMM-<seq>` or ULID) so "everything since X" is a range scan |
| **`__consignment_id` column** | **compaction** | see below |

The column looks redundant today and is not. **It is what keeps compaction from being a one-way door.**
Compaction destroys both the filename convention and the §5.1 invariant — but the column survives, so
"replace consignment C" in cold data degrades to "rewrite the partition excluding C, then append the new
C." Expensive but possible. Without it, compacted data becomes permanently unreprocessable.
**Add it before there is data that lacks it.**

Beyond storage, the consignment id is the correlation key for the whole run — MDC, logs, ledger signals,
audit — following the existing space-MDC idiom the batch worker already inherits.

### 5.6 Partition-affecting config must be pinned in the manifest

> **Any config that decides which partition a row lands in must be recorded in the manifest at load
> time, and reprocessing must use the pinned value — never the current config.**

Applies to: the event-time field, the timezone used to cut record-days (§10.2), the partition
granularity, and the day-boundary rule.

Config is mutable; replace-by-batch is not. Edit a Stream's timezone from IST to UTC, then reprocess a
six-week-old Consignment, and its rows cut into *different* record-days than the original run — the
delete-then-write leaves orphans in the old partitions. This is §5.3's partition drift, triggered by an
authoring change rather than by corrected data, and it is the more likely of the two in practice.

Pinning makes a reprocess reproduce the original day-cut exactly, which is what "replace" has to mean.
**Cheap now, effectively impossible to retrofit once there is data.**

## 6. Small files and compaction

**File count is not the metric; file size is.** At 10-minute Consignments (144/day):

| Daily volume per table | File size | Verdict |
|---|---|---|
| 14 GB | ~100 MB | ideal |
| 7 GB | ~50 MB | good |
| 1.5 GB | ~10 MB | acceptable floor |
| 150 MB | ~1 MB | genuinely small |

Above ~1.5 GB/day, 144 files/day is a non-issue — DuckDB parallelizes across files and row groups.

**Small-file risk is fan-out, not batch size**: the real ratio is `consignment size ÷ partition fan-out`.
A large Consignment spread across 30 partitions still writes 30 small files. Partition granularity and
Consignment policy must be chosen *together*, per pipeline.

**Detail and summary behave completely differently.** Summary files are tiny by construction — hundreds
of rows, tens of KB, where the Parquet footer is a meaningful fraction. 144 × 365 = **~52,000 files/year
for one summary table**, and a 12-month query opens 52,000 footers plus (with no catalog) the directory
listing. That is the slow path, and it is slow where users notice.

### 6.1 Compaction past a reprocessing horizon

**Decided: 7 / 14 / 30 day options, plus `none`, configured per table.**

Inside the horizon, per-Consignment files stay separable and replace-by-batch is untouched. Past it, roll
each period's files into one. The horizon is really **an SLA on cheap reprocessing** — "how long do I keep
surgical replacement" — not a storage setting, which is why it must be per-table: a high-volume detail
table wants a short horizon to relieve read pressure; a small summary table can afford 30 days or never.

`none` is required — low-volume tables where 144 files/day is already fine, and domains where
reprocessing can arrive at any age, must not be forced to compact.

### 6.2 Summaries never need the rewrite path

**A summary is derived; detail is source.** A day's summary can always be regenerated by aggregating that
day's detail. So:

- **Detail past the horizon** is a genuine one-way door — surgical replacement needs `__consignment_id`
  and a partition rewrite.
- **Summary past the horizon** has no door at all. Reprocess an old Consignment → recompute the whole
  day's summary from detail → **replace the single compacted summary file**. Still a whole-file unlink.

Treat compacted summaries as a **cache that can always be rebuilt**. For detail, the backstop is raw
retention (Path 1's `commit` already backs up landed files).

### 6.3 Compaction is the only operation that can produce duplicate rows

Write the compacted file, crash before unlinking sources, and a reader sees both copies and
double-counts. **A silent correctness bug, not a crash.** Unlink-first loses data instead, which is worse.

Without a catalog, use a **per-partition generation**: stage the compacted file under `dt=…/_v2/`, flip a
single small marker naming the live generation, then lazily unlink. Readers consult the marker. It is a
mini-catalog but scoped to one partition and one tiny file — consistent with where the no-catalog line is
drawn. Reuse `BatchProcessor.commit`'s staging-and-marker discipline; do not invent a second commit
protocol.

Compaction must also be **re-runnable on an already-compacted partition** (§8, late data).

## 7. Summary semantics

The summary asset this section designs is modeled as a **Derived Table** (`NodeKind.DERIVED_TABLE`);
"summary table" below is the plain-English description of that model type, not a distinct concept. Its
user-facing name is **Matrix** (GLOSSARY §13) — an additive label, not a separate node kind.

### 7.1 Partial aggregates are forced, not a defect

A Consignment's records may span multiple record-days. Combined with append-only and one-file-per-
Consignment, a given day's summary rows are scattered across every Consignment that touched that day, and
the true value only exists after a **read-time rollup**. The alternative — updating a day's row in place —
is exactly the read-modify-write ruled out in §5.2.

### 7.2 Every measure must be algebraically composable

The sharp constraint. Gets wrong numbers quietly if unenforced.

| Measure | Composes? | Handling |
|---|---|---|
| `COUNT`, `SUM`, `MIN`, `MAX` | yes | sum the sums, min the mins |
| `AVG` | **no** — averaging averages is wrong when Consignments differ in size | store `sum` + `count`, derive on read |
| `COUNT(DISTINCT …)` | **no** — two Consignments can see the same subscriber | small cardinality: store values, `UNION`. Large: sketch, or recompute from detail |
| median / percentiles | **no** | fixed-bucket histogram, or recompute |

**Decided: `count` is mandatory on every summary row.** The general rule is *store the components, derive
the ratio at read time* — which covers more than AVG:

- average → `sum` / `count`
- rate or percentage → numerator count + denominator count
- weighted average → `sum(w·x)` + `sum(w)`
- variance / stddev → `count` + `sum` + `sum_of_squares` (naive sum-of-squares is numerically shaky over
  wide ranges; fine for typical volume/duration data)

**Free bonus:** summing `row_count` across a day's summary partials should equal the detail row count for
that day — an automatic per-partition reconciliation at zero cost, which plugs into the existing
`ProvenanceCollector` / `ConservationCheck` machinery rather than needing new alerting.

So the enforceable policy is small enough for the config validator: **count is mandatory; measures must be
additive; anything non-additive is either bucketed, or declared and computed from detail.**

### 7.3 Partition summaries by record-day

Flat summary directories mean "give me day D" globs everything. Partitioning by record-day restores
pruning, and does **not** break §5.1 (one Consignment writing one file per day it touched is fine —
the manifest knows them all).

So: one file per **(Consignment × record-day)**, partitioned by record-day, with its own compaction
horizon. Fan-out stays low in practice — most Consignments touch today plus a straggler day.

Note this **supersedes an earlier suggestion in the conversation** to keep summaries flat with
`partition_key` as a column; the multi-day span makes day-partitioning strictly better.

### 7.4 Optional rollup tier, treated as a cache

If read-time aggregation over partials gets expensive, materialize one row per day — but **never maintain
it incrementally**. Rebuild affected days whenever a Consignment lands or is reprocessed. Cheap, and
being derived it inherits the always-replaceable-in-full property. This keeps exactly one mutable-looking
thing in the system, and it is a cache that can be deleted entirely without data loss.

### 7.5 Non-additive measures: the deferred decision

**Decided in principle:** mandatory count handles the additive family; non-additive measures are handled
by a separate end-of-period summary pass (§8).

For quantiles, a **fixed-bucket histogram in plain SQL** (`CASE`/`width_bucket`, store counts per bucket)
is very likely better than a t-digest: trivially composable, exact to bucket width, no library, no
serialized state, readable when debugging. You lose arbitrary post-hoc quantiles — usually a fine trade
for known measures like call duration or data volume.

**Verify before betting on sketches:** DuckDB's `approx_quantile` / `approx_count_distinct` compute a
*final answer*, and (believed, unconfirmed) do **not** expose serializable mergeable state persistable to
Parquet. If that holds, sketches mean DataSketches on the Java side and a BLOB column from a custom
processor — real work, not a config flag.

## 8. "End of day" is a completeness condition, not a clock

Files arrive late, out of order, nothing guaranteed. Separate two questions: **when is a number correct**
and **when is a number final**. Only the second needs a definition of end-of-day.

### 8.1 Correctness needs no end-of-day at all

Non-additive summaries are derived and whole-file replaceable, so: **when a Consignment lands, recompute
the non-additive summary for every record-day it touched.** No schedule, no watermark, no boundary to
miss. Day D converges as stragglers arrive.

Cost is recompute frequency (144×/day at 10-minute Consignments, scanning a growing day). Two dampeners:
**debounce** (recompute a day at most every N minutes, or once M Consignments accumulate for it) and
**stamp `computed_at`** so consumers know freshness. Additive measures stay exact and instant off the
partials; non-additive ones lag by the debounce interval and say so.

**This solves the motivating "scheduled end-of-day summary" problem with no schedule anywhere.**

### 8.2 Finality is sealing, and it is the completeness check

Sealing matters only when something downstream must commit — a regulatory report, a bill, a
reconciliation, an SLA number.

> Day D **seals** when the completeness rule is satisfied, or when the **lateness horizon** expires,
> whichever comes first.

Emit which condition fired. **Sealed-complete** is the happy path; **sealed-on-timeout** means something
did not arrive and should open an Incident rather than silently producing a short number.

Give a day partition an explicit state — `OPEN → SEALED → REOPENED` — and publish
`PartitionSealed(table, day, complete|timeout)`. Downstream reports, reconciliations and Expectations
subscribe to *that*, not to a cron — the same `on_pipeline` chaining idiom, triggered by a completeness
event instead of an arrival event. **This also closes the gap that Expectations are currently HTTP-pull
only with no automatic trigger** (see §13).

`REOPENED` handles late data after sealing: recompute and republish (cheap — summaries are replaceable),
but emit the reopen event, because anything that already consumed the sealed number has a stale copy.
That is a downstream contract break, not a data problem — the reason to set the lateness horizon
conservatively.

### 8.3 The horizon chain

```
lateness horizon  ≤  seal  ≤  compaction horizon  ≤  raw retention
```

Do not seal before late data can plausibly arrive; do not compact a partition that might reopen; do not
age out raw files while compacted data might need regenerating. All four are per-table.
**The ordering is trivially checkable — put it in the config validator** so a misconfiguration is a 422 at
authoring time rather than a corrupt partition six weeks later.

### 8.4 SLA binds time and condition

Make SLA a first-class config object per stream/table rather than scattering the windows: lateness
horizon, seal point, in-flight checkpoints, completeness rule, with §8.3 validated inside it.

Binding them gives **early warning for free** — "95% of day D by D+2h" is checkable at D+2h with the day
still OPEN and action still possible. So two evaluation points: **in-flight SLA breach** (actionable) and
**at-seal verdict** (final, feeds complete-vs-timeout).

## 9. Completeness is statistical, not a hardcoded list

**Decided:** completeness values are statistical, addressed case-by-case by rules — not a declared
expected-file count.

### 9.1 The expected set is learned, not declared

A Collector that reported for the same weekday in each of the last N comparable periods is expected; one
that reports sporadically is not. Absence carries a confidence, and nodes coming and going stops being a
config-maintenance burden. Two grains:

- **Aggregate** — is day D's volume within the expected band? Catches broad shortfalls.
- **Per-contributor** — is contributor X missing, given it has been reliably present? Catches the silent
  partial failure that barely moves the aggregate. **This is the one that actually hurts.**

### 9.2 Robust statistics, not mean ± stddev

The reflex is z-score; it is the wrong tool. Operational volumes are skewed, and the outlier being hunted
inflates the very stddev it is compared against — one bad day desensitizes the check for weeks.

Prefer **median ± k·MAD**, or a **percentile band** ("within the 5th–95th percentile of the last 8
same-weekdays"). Both are robust to contamination; the percentile form is far easier to explain to an
operator staring at an Incident.

Baselines must be **day-of-week aware** — traffic swings hard between weekday and weekend, and a
rolling-N-days baseline fires every Saturday. Leave room for a calendar suppression override for holidays.

### 9.3 Baseline hygiene

- **Warm-up.** A new pipeline has no history. Without an explicit `min_history` every new stream alerts on
  day one. The check needs a third outcome: **insufficient baseline** — not a pass, not a fail.
- **Eligibility.** An outage last Tuesday drags the baseline down and masks the next one. **Only
  `sealed-complete` days contribute to the baseline**; sealed-on-timeout days are excluded. Neat loop —
  the seal state needed for finality also decides which history is trustworthy.

### 9.4 KNN — considered, deferred, and placed elsewhere

Multivariate anomaly detection (period → feature vector; score by distance to *k* nearest historical
periods) genuinely buys two things: **mix-shift detection** (total volume normal but one node's share
doubled — a real telecom failure mode a volume check cannot see) and **implicit seasonality** (a
Saturday's neighbours are naturally other Saturdays).

Four costs:

1. **Interpretability collapses.** "3.2 MAD above the median for a Tuesday" is actionable; "score 0.87"
   is not. Needs per-feature attribution to be triageable.
2. **Warm-up vs dimensionality.** MAD needs 8 weekdays; six features need hundreds of points, pushing
   daily-grain warm-up toward a year.
3. **Drift fights (2).** Volumes trend upward. KNN wants long history; long history makes a legitimately
   grown period look distant and alert. Shortening the window worsens dimensionality. No clean setting
   without explicit detrending.
4. **Completeness is not anomaly.** Completeness has a ground truth (did X send its files) even when
   measured statistically; anomaly has none. A day at 100% with an odd mix is anomalous but complete; a
   quiet holiday at 95% is normal-ish but incomplete. Same check → Incidents operators cannot classify.

**Recommendation — two separate Expectation kinds, shipped in order:**

- **`baseline`** — univariate, robust, per-measure → **completeness and SLA**. Interpretable, cheap, small
  warm-up, drift-tolerant. Closes the actual requirement. **Ship this.**
- **`anomaly`** — multivariate → **"something changed"**. Lower severity, routed to a review queue, not a
  hard Incident. Add only if univariate proves insufficient.

**Cheap middle ground that captures most of KNN's value:** several univariate checks on *ratios* rather
than one multivariate score — per-contributor share of total, records-per-file, error rate,
distinct-contributor count. Each individually explainable, each firing with a self-describing reason,
together catching the mix-shift case. A composition of simple checks beats one opaque distance for
anything triaged at 3am.

**Placement:** anomaly scoring does **not** belong in the Expectation evaluator. Expectations are
deterministic checks with a pass/fail verdict; a distance score with a confidence is a different animal.
`inspecto-intelligence` (`SignalIngress`, `TriageQueue`, `Diagnoser`, `Incident`) already deals in that
currency. **Expectations emit facts; the intelligence tier emits suspicions.**

If built: **hourly grain is the enabler** (24× the points makes neighbourhoods meaningful in months, not a
year, and gives the arrival-curve feature for free). Implementation is a self-join, a distance expression
and a `LIMIT k` — plain SQL. The hard parts are robust feature scaling and drift, not the query.

## 10. Event time, timezone, and garbage

### 10.1 Computed at load, never at report time

The per-file event-time range (`record_time_min` / `record_time_max`) is captured during Extract/Load.
**Not deferred to report generation** — and the reason is structural, not performance:

- **§8 sealing cannot work without it.** Sealing decides when a record-day is complete; that requires
  knowing which record-days a Consignment touched *at the moment it commits*.
- **Lateness is `arrived_at − record_time_max`** — undefined if recentness is not captured on arrival.
- **§7.3's one-file-per-(Consignment × record-day)** must know the days *before* it writes.

It is also nearly free: the EL step already scans every row and already groups by partition key, so
`MAX(event_ts)` rides the same pass. Computing it later means re-reading Parquet that would otherwise
never be touched.

**`record_time_min`/`max` are a convenience denormalization, not the authority.** A min/max *range*
over-credits — a file covering day 1 and day 3 would mark day 2 as having received data. The authoritative
structure is per-`(record_day, row_count)`, which `consignment_outputs.record_day` (§11.3) already gives as
a byproduct of the partitioned write. Use the range for display and coarse filters; use the output
registry for sealing.

### 10.2 Field selection and timezone

**Decided:** the pipeline designer selects the event-time field; timezone is per-**Stream** configuration
with options *specific zone · UTC · Local*, default Local, used whenever the time field carries no zone.

**No new key.** A partition-deriving field already exists: `partitions[]` (`{column, source, type}`,
`PartitionDef.java:9-25`, `type ∈ VARCHAR|DOUBLE|INTEGER|DATE_YEAR|DATE_MONTH|DATE_DAY`), authored today
via the schema-mapping pane's "Partition key (optional)" picker
(`schema-mapping-pane.component.ts:404`). The event-time field selection this section decides on binds to
`partitions[].source` with a `DATE_*` type, rather than inventing a `timestamp`/`event_time`/`time_field`
key — no such key exists anywhere in the config today, and one UI picker already does this job.

Two constraints on top:

**One table, one event-time meaning.** However it is authored, a given table must resolve to exactly one
event-time field — otherwise days are cut differently depending on which pipeline landed the rows, and
partition state stops having a single meaning. The validator must reject two pipelines writing the same
table with different event-time fields.

Clean split: **the field is a schema property** — i.e. `partitions[].source`, not a new key — (it names a
column); **the timezone is a Stream property** (it describes the sender). Stream is the right granularity
— the same standard CDR format delivered by network elements in different countries needs different
day-cuts.

**`Local` must resolve to a concrete IANA zone at authoring time and be persisted as that.** If `Local`
means "the JVM default", record-day assignment depends on the *host* rather than the data: dev and prod
diverge (containers usually run UTC), and **a reprocess on a different host cuts days differently** — §5.3
partition drift caused by infrastructure. Keep `Local` as a UI affordance; store `Asia/Kolkata`.

**IANA zone ids, not fixed offsets.** `+05:30` is fine for a zone without DST and silently wrong for one
with it — a fixed offset cannot express a 23- or 25-hour day.

The event-time column is **optional/nullable**, because not every dataset has a meaningful event time (a
reference table dropped in daily, a config export). But the consequence must be explicit, not discovered:
a schema with no event-time field **cannot participate in record-day sealing or lateness SLAs** and falls
back to arrival-time partitioning. Configuring a record-day seal policy or SLA on such a schema is a
**422 at authoring time**, not a silently wrong seal.

### 10.3 Garbage timestamps

**Decided:** garbage timestamps are alerted and block compaction. Three refinements:

**Do not let a garbage value create a partition.** Bound-check at load and route out-of-range records to
the existing `invalid` rel (§2). Otherwise one 2099 record creates a `dt=2099-03-14` partition that enters
the state machine, can never seal (no more 2099 data is coming), and needs a manual override workflow —
a lot of machinery for a bad record. Routing to `invalid` preserves the records for inspection, reuses
existing plumbing, and creates no phantom partition.

**Scope the compaction block to the affected partition**, never the Consignment or the table. One poison
record must not freeze housekeeping for good data. And give operators an explicit way to clear the block
("yes, that is garbage, discard it") or the alert accumulates until everyone ignores it — the real failure
mode.

**Alert on rate, not occurrence.** One bad record in a million is noise; five percent is a broken feed.
That makes it a natural **`baseline` Expectation** (§9) over the invalid-record rate rather than a bespoke
check — same evaluator, same Incident path, and it inherits warm-up and day-of-week handling for free.

## 11. Persistence model

Grounded against what exists (2026-08-03 backend trace). Framing is *what is already there* → *what the
Consignment model adds*, so this does not become a parallel structure.

### 11.1 Per-file record — exists, needs 6 columns

`BatchAuditWriter` already writes a per-file `status` CSV (`inspecto-etl/.../etl/BatchAuditWriter.java:43`)
— **this is the per-file status record**, joined to the batch by `batch_id`:

```
start_time, end_time, filename, status, parsed_rows, error_rows,
output_paths, output_sizes_bytes, duration_ms, error, batch_id
```

| Add | Why |
|---|---|
| `checksum` + algo | duplicate detection — nothing today identifies a re-delivered file |
| `size_bytes` (**input**) | only `output_sizes_bytes` exists today; size-based Consignment forming (§4.2) needs input size |
| `collector_id`, `source_uri` | `filename` alone loses which Collector/remote it came from — required for §9.1 per-contributor completeness |
| `arrived_at` | distinct from `start_time` (processing); lateness and SLA measure from arrival |
| `record_time_min` / `record_time_max` | §10.1 |
| `decode_status`, `decoded_paths` | the §4.3 Decoder tier and its temp-file lifecycle |

**Do not collapse intrinsic and inherited status into one enum.** "Did this file decode, how many rows" is
intrinsic; "did its Consignment commit" is inherited. One column for both yields contradictions like a
`COMMITTED` file inside a `FAILED` Consignment.

### 11.2 Consignment record — exists as `batches`, with one structural break

```
batch_id, pipeline, schema_name, output_table, start_time, end_time, status,
member_count, rejected_count, total_input_rows, total_output_rows,
output_file_count, total_output_bytes, duration_ms, error
```

Plus a durable fsync'd `CommitLog` (`inspecto-etl/.../etl/CommitLog.java:38`) —
`committed_at, batch_id, pipeline, status, member_count, output_count, output_rows, output_bytes`.
**That is §5.4's batch-atomic-visibility mechanism, already built.**

**The structural break:** `schema_name` and `output_table` are **singular**. A Consignment's Extract/Load
emits a row set *per schema* (§4.4), so one row per Consignment no longer fits — either one row per
`(consignment, schema)`, or normalise per-schema counts into a child table. **Needs deciding.**

| Add | Why |
|---|---|
| `consignment_id` **stable** + `run_id` separate | **Key decision:** a reprocess keeps the id and starts a new Run. Otherwise `__consignment_id` in the data stops matching after a reprocess and §5.3 replacement needs id translation. ⚠ The second component is **`run_id`, not an invented `attempt`** — GLOSSARY §5 already defines Run as "one execution of an Executable" and §6-A gives `Run ⊇ Consignment ⊇ File`, so the Run *is* the attempt (aligned 2026-08-03) |
| `formed_by` | which policy closed it (`size`/`count`/`time`/`immediate`/`manual`) — first thing wanted when debugging a small Consignment |
| `supersedes` | reprocess lineage |
| `bytes_total` (input) | as above |
| `space` | multi-space; absent today |
| `rows_dropped` | `rejected_count`/`error_rows` do not separate Decision-Rule `drop` from parse failure |
| pinned config (§5.6) | event-time field, timezone, partition granularity actually used |

### 11.3 Output-file registry — **the gap**

`PartitionOutput(partition, outputFile, bytes)` (`inspecto-etl/.../etl/PartitionOutput.java:10`) is an
**in-memory return value**, not a persisted registry. The `lineage` CSV comes closest
(`batch_id, src_id, input_file, output_file, partition, row_count`) but it is an input→output count matrix,
not an output registry with lifecycle state.

Three parts of this design require a durable one — §5.3 manifest-driven reprocessing, §5.5 (the manifest
*is* the catalog), §6.3 compaction generations:

```
consignment_outputs:
  consignment_id, run_id, table_name, partition_key, record_day,
  path, rows, bytes, written_at,
  generation,          -- §6.3 compaction
  state                -- LIVE | SUPERSEDED | COMPACTED_AWAY
```

**The single biggest addition** — it is the catalog substitute the no-catalog decision implies, and it is
also what §10.1 reads for authoritative record-day membership.

### 11.4 Partition state — nothing exists

§8.2 needs a new table outright:

```
partition_state:
  table_name, record_day, state,            -- OPEN | SEALED | REOPENED
  sealed_at, seal_reason,                   -- complete | timeout
  reported_contributors, last_consignment_at,
  summary_computed_at,                      -- §8.1 debounce / freshness stamp
  compaction_generation, compacted_at, compaction_blocked,   -- §10.3
  row_count,                                -- §7.2 conservation cross-check
  baseline_eligible                         -- §9.3, derived from seal_reason
```

### 11.5 Events and signals — most of it is already declared

`EventType` (`inspecto-event/.../event/EventType.java:24-134`) already carries the file lifecycle:
`FILE_DISCOVERED, FILE_FETCHED, FILE_RECEIVED, FILE_VALIDATED, FILE_STABLE, FILE_CHANGED,
FILE_QUARANTINED, FILE_FETCH_FAILED, FILE_ARCHIVED`, plus `BATCH_COMMITTED, BATCH_FAILED, SEQUENCE_GAP,
SOURCE_CIRCUIT_OPEN, FLOW_CONSERVATION_IMBALANCE, EXPECTATION_FAILED, ALERT_FIRED, OBJECT_SLA_BREACH`.
`SEQUENCE_GAP` and `SOURCE_CIRCUIT_OPEN` are directly reusable for completeness and Collector health.

`BatchEvent` (`inspecto-etl/.../etl/BatchEvent.java:44`) is the chaining trigger:
`(pipeline, batchId, status, partitions, outputRows, durationMs, rejectedCount, error, offendingFile, errorRows)`.

**Do not grow the `EventType` enum for the new concepts.** The modern path is `Signal`
(`inspecto-engine/.../signal/Signal.java:27`), which rides losslessly on one `Event` of type `SIGNAL`
(`toEvent`/`fromEvent`, lines 57-95) and is already rich enough:
`signalId, type, at, severity, source(Ref), subject(Ref), correlationId, causationId, space, actor,
message, payload, schemaVersion`.

New signal types: `consignment.formed` · `consignment.committed|failed|skipped|superseded` ·
`partition.sealed` (complete\|timeout) · `partition.reopened` · `summary.recomputed` ·
`compaction.completed|failed` · `sla.at_risk`.

Two things worth fixing while here:

- **There is no signal-type constants class.** Existing types (`pipeline.batch.committed`,
  `decision-rule.applied`, `expectation.violated`, `job.run.completed`) are inline string literals at each
  emission site. Adding ~10 more makes a typo that silently breaks a subscriber likely; a constants class
  is cheap insurance.
- **Per-file facts are ledger rows, not signals.** At high file counts one signal per file floods the Event
  ledger. Emit signals only for exceptional per-file conditions (quarantine, decode failure); routine ones
  live in the `status` table.

### 11.6 Alerts and Incidents — reuse, with one caveat

`ObjectType` is `{ALERT, INCIDENT, CASE, TASK}`, persisted to `inspecto_ops_objects` (`id, object_type,
title, description, status, severity, priority, "owner", assignee, correlation_id, attributes, created_at,
updated_at, closed_at`).

| Condition | Object | correlationId |
|---|---|---|
| sealed-on-timeout (§8.2) | INCIDENT | `partition:<table>:<day>` |
| baseline breach (§9) | INCIDENT via existing `expectation.violated` → `raiseIncident` | `expectation:<name>` |
| SLA at risk in-flight (§8.4) | ALERT | `sla:<table>:<day>` |
| conservation imbalance | existing `FLOW_CONSERVATION_IMBALANCE` | — |
| garbage-timestamp rate (§10.3) | INCIDENT via `baseline` Expectation | `expectation:<name>` |
| repeated Consignment failure | INCIDENT | `consignment:<pipeline>` — *not* per-consignment, or one Incident per failure |

**Caveat:** correlationId dedup is **advisory** — callers check
`ObjectService.active(type, correlationId)` (`inspecto-engine/.../ops/ObjectService.java:300`); there is no
DB uniqueness constraint. Two concurrent seal evaluations can open duplicate Incidents. Harmless today
(evaluation is single-threaded per space) but relevant once sealing is event-driven and concurrent.

### 11.7 Notifications — model exists, persistence may not

`DeliveryReceipt(deliveryId, notificationId, channelConfigId, target, sentAt, statusAt, providerRaw,
digest)`, `DeliveryEvent`, `DeliveryStatus{DELIVERED, BOUNCED_HARD, BOUNCED_SOFT, COMPLAINED, UNKNOWN}`,
behind a `DeliveryReceiptStore` seam (`inspecto-engine/.../notify/`).

**Only an in-memory implementation was located.** If a durable one exists it was not found — worth a
targeted check, because "we notified ops about the SLA breach at 14:02" is exactly the claim that must
survive a restart. Tracked in §15.

## 12. Kafka

**Removed.** Nothing in this model needs it for the ELT path. Record-at-a-time streaming is what *created*
the original problems — uneven parallelism across diverse file sizes, and per-record transform failure with
no transaction to fail into. DuckDB relations replace the record stream.

The reason to stream record-at-a-time was **urgency**, and urgency is now a parameter on one node (§4.2):
a Consignment of one file degenerates gracefully into near-real-time through the same graph. No separate
streaming path, no second execution model creeping back.

Keep Kafka, if at all, as an egress/notification bus or for the signal backbone — never as the data path.

## 13. New model surface this implies

Not config — actual additions:

| Addition | Where | Note |
|---|---|---|
| Manifest as a port/payload type | `PipelineEdge` rel + `PipelineNodeTypes` `emits`/`accepts` | extends an existing contract |
| Java-executed node category | `NodeCategory` (alongside `SINK`) + `PipelineExecutor` dispatch | today's dispatch is SINK / `transform.merge` / `transform.*` / control-terminal |
| `grain` on node type | `PipelineNodeTypes` + validator + palette | `RECORD \| FILE \| BATCH` |
| `__consignment_id` column | writer | §5.5 — must precede any data |
| **`consignment_outputs` registry** | **new durable table** | **§11.3 — the biggest addition; the catalog substitute** |
| **`partition_state` table** | **new durable table** | §11.4 — `OPEN → SEALED → REOPENED` |
| Partition state machine + `PartitionSealed` signal | new | §8.2 |
| Pinned partition-affecting config | manifest / consignment record | §5.6 — cheap now, impossible to retrofit |
| Event-time field + per-Stream timezone | schema config · Stream config · validator | §10.2 — `Local` resolves to a concrete IANA zone |
| Per-file columns (checksum, input size, collector, arrival, record-time range, decode status) | extend `BatchAuditWriter` `status` CSV | §11.1 |
| Per-schema Consignment rows | restructure `batches` CSV | §11.2 — today's `schema_name` is singular |
| Signal-type constants class | `inspecto-engine/.../signal/` | §11.5 — types are inline literals today |
| SLA config object | new | §8.4, validates §8.3 chain |
| **`baseline` Expectation kind** | `Expectation`, `ExpectationEvaluator`, config schema, UI form | see below |
| Per-partition compaction generation + marker + block flag | writer | §6.3, §10.3 |
| `Consignment` GLOSSARY entry + ban line | `docs/GLOSSARY.md` | §3 |

**On the `baseline` Expectation kind specifically:** current kinds are `non_null` / `range` / `regex` /
`referential` / `condition`. None can express this — `condition` compiles a *static* tree via
`ConditionSql` with no way to reference a historical window. So `baseline` needs: measure, baseline window
(N same-weekday periods), comparison method (MAD multiplier or percentile band), `min_history`, severity.
It evaluates against the summary/manifest relation rather than raw detail (cheap) and slots into the
existing `ExpectationRoutes` → `raiseIncident` path, so Incident/Signal plumbing is free.

## 14. Open — `ProcessorContext`

**The one deliberately unresolved piece, and the one where a wrong guess is most expensive**, because every
custom processor anyone writes binds to it.

Requirements gathered so far: a processor needs the Consignment id, a manifest **already scoped to that
Consignment**, the output file list with row counts, and a read-only DuckDB connection. Everything it emits
should be stamped with the Consignment id without the author thinking about it.

The tension: too thin and it is useless; too fat and it can never change. And §7.2's composability rules
mean the context has to make it *easy for a processor author to get summary semantics right without knowing
the storage rules* — which argues for the context offering a summary-emit helper rather than a raw writer.

**Not designed. Next session should start here.**

## 15. What this does not cover

- **Migration** from the two current paths. §1 says the merge is "Path 1's stages behind Path 2's SPI" —
  the sequencing, and what stays legacy, is unplanned.
- **The designer/UI surface** for typed ports, grain, SLA authoring, event-time field selection, and the
  palette.
- **Whether the new durable tables (§11.3, §11.4) replace the three legacy `BatchAuditWriter` CSVs or the
  CSVs become a projection of them.** Open decision.
- **Whether `batch_id` stays the column name in extended legacy artifacts** while `consignment_id` is used
  in new ones. Open decision.
- **Verification items** flagged inline: DuckDB mergeable sketch state (§7.5); whether a durable
  `DeliveryReceiptStore` implementation exists beyond the in-memory one (§11.7).
- **Whether Path 1's `Batch*` classes are eventually renamed** or stay legacy internals (§3 assumes stay).
