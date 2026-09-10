# Enterprise Scale-Out on Kubernetes — Design Plan

> **Status: SIGNED 2026-09-10 (operator) — D1′–D13, all recommendations accepted, D9 refined by D8, D13 added by the
> operator. Nothing here is built yet.** The §2 amendments are applied.
>
> ✅ **SPIKES RUN 2026-09-10 — S2, S3 and S4 are CLOSED; S1 and S5 are answered in part and their live
> halves are still owed** (they need a reachable Postgres and an S3-compatible endpoint, which the sandbox
> that ran them has not got). Each result is written into the §3 seam it tests, and §10 carries the
> summary table. 🔴 **Phase A must NOT start on the strength of S1**: the concurrent-registration
> assumption is still unverified on Postgres, and a failed S1 reopens D4. S3 and S4 clear their own
> preconditions outright, and S4 changes how §7's test must be built.
>
> ✅ **Two directions taken by the operator on 2026-09-10, ahead of the full signature:** *fault-tolerant DR
> is a **Standard** capability; the Kubernetes cluster is **Enterprise**.* Recorded in `editions.md` §4 and
> applied to its §3.9 topology table (T4 → Standard; T5 added as Enterprise). 🔴 **This reshapes the
> sequencing more than it reshapes the design**: phases A and B are no longer "valuable on a single node" —
> they ARE the Standard DR deliverable and must ship in the Standard bundle. D8 was decided first; D1′–D7
> and D9–D13 were signed later the same day (§9).
>
> **What this plan does.** Turns the operator's 2026-09-10 direction — *"on Enterprise I plan K8s
> scaling"* — into a design the code can actually carry, sequenced so that the first two of its three
> phases pay for themselves on a **single node** before a pod exists. It proposes **shared-nothing
> partitioning** (N pods, each owning a slice of Spaces, all writing one shared lakehouse) and
> **refuses** replacing the embedded engine with a distributed one.
>
> ⚠ **This plan REVISES signed decisions, and says so.** `NFR-8` already names the *"Enterprise
> distributed tier"* as the *"opt-in escape hatch"* — it does **not** need reversing. What does: the
> container decision **D1** (signed 2026-09-06: *"a container image as a convenience with orchestration
> out of scope"*) and two refusals in `okf/capabilities/editions/editions.md` §6 — *Active/active
> deployment: ⛔ NOT OFFERED* and *Orchestration platform support: ⛔ OUT OF SCOPE "until that decision
> changes."* That decision is changing. §2 carries the exact amendments, to be applied to the recording
> documents **on sign-off, not before** — a contradicted-but-unamended decision is what caused a plan
> archival to be reverted on 2026-09-09, and this repo's rule is amend, never silently contradict.
>
> **On approval + ship:** distil the as-built facts into `okf/capabilities/editions/editions.md`
> (a new §3.15 alongside the T1–T4 topologies) and `okf/backend/build-run/`, move still-open items to
> `BACKLOG.md`, then archive this plan per the doc lifecycle.
>
> Companions: [`okf/capabilities/editions/editions.md`](../okf/capabilities/editions/editions.md)
> §3.9–§3.14 (the topologies, the signed RPO/RTO table, the T4 promote order) ·
> [`roadmap/ROADMAP.md`](../roadmap/ROADMAP.md) L1 (the four workstreams, sized XL) ·
> [`archived-documents/plans-archive/postgres-multi-user-plan.md`](../archived-documents/plans-archive/postgres-multi-user-plan.md)
> §5–6 (the connection pool this plan un-parks) ·
> [`archived-documents/plans-archive/deployment-topology-plan.md`](../archived-documents/plans-archive/deployment-topology-plan.md)
> §10 (D1–D8 as signed) · [`REQUIREMENTS.md`](../REQUIREMENTS.md) (`NFR-8`).

**Coverage map:** what changes and why §1 · what it amends §2 · the seams, grounded §3 · the scaling
model §4 · the four workstreams §5 · sequencing §6 · the first test §7 · risks §8 · decisions asked §9 ·
spikes §10 · positioning §11 · defects found while grounding §12.

---

## 1. What changes, and why

Inspecto's scale ceiling is single-node by signed design (`NFR-8`, *accepted constraint*). Measured on
one node it is fast — native ingest at 523K rows/s on 12 columns, DuckDB transforms at 1.4M rows/s
(`okf/backend/build-run/performance.md`) — but wide telecom schemas bring the ceiling down hard: a
537-column CDR projects to ~11K rows/s per batch, ~45K rows/s aggregate on a 16-core box. A tier-1
operator at billions of records a day sits **at or beyond** that. The market read of 2026-09-10 also
found the closest competitor (Definite) shipping the same DuckDB + DuckLake stack **on Kubernetes**.

The operator's direction: Enterprise scales out on Kubernetes. This plan's job is to make that true
**without abandoning the thesis** — one artifact, one config, DuckDB's per-core speed — and without
pretending that `replicas: 4` is scale.

🔴 **The one architectural truth to design around: Kubernetes does not make DuckDB distributed.**
DuckDB is an embedded, single-process engine. Four replicas of today's artifact are four in-process
schedulers firing the same pipelines four times into four private databases. Scale must come from
**partitioning the work**, and correctness from **coordinating the partitions**. Everything below
follows from that.

---

## 2. What this plan amends — exact text, APPLIED 2026-09-10 with the signature

| Recording document | Today (verbatim) | Proposed on sign-off |
|---|---|---|
| `REQUIREMENTS.md` `NFR-8` | *"Single-node by design; Enterprise distributed tier is the opt-in escape hatch \| ACCEPTED CONSTRAINT"* | **Unchanged in substance** — the escape hatch is now being built. Status cell gains: *"escape hatch IN DESIGN 2026-09-10 → `superpower/enterprise-scale-out-plan.md`"* |
| `editions.md` §4, 2026-09-06 row | *"a container image as a convenience with orchestration out of scope"* | Add a dated row: *"2026-09-10 — D1 REVISED by operator: the container image becomes the **Enterprise unit of deployment**; orchestration is IN SCOPE for Enterprise only (Personal/Standard stay single-artifact, no orchestrator). Design: `superpower/enterprise-scale-out-plan.md`."* |
| `editions.md` §6 *Active/active deployment* | *"⛔ NOT OFFERED — both schedulers are in-process; the single-node ceiling is an accepted constraint (`NFR-8`). The escape hatch is a priced roadmap conversation, not a configuration"* | *"⚠ **SUPERSEDED 2026-09-10** for Enterprise: active/active becomes **partitioned scale-out** (shared-nothing by Space), not replicated active/active. Personal/Standard: still not offered. → plan §4"* |
| `editions.md` §6 *Orchestration platform support* | *"⛔ OUT OF SCOPE per the signed container decision … not on the roadmap until that decision changes"* | *"⚠ **That decision changed 2026-09-10.** Kubernetes is the Enterprise orchestrator; Personal/Standard remain orchestrator-free. → plan §5.4"* |
| `ROADMAP.md` L1 | *"Enterprise distributed tier … \| XL \| A deployment whose scale or multi-tenancy actually exceeds the single-node design"* | Trigger cell: *"Operator direction 2026-09-10; sized as three M phases in the plan, each independently shippable"* |
| `editions.md` §3.9 topology table | T1–T4, T4 = Enterprise | ✅ **APPLIED 2026-09-10 (operator direction, not a signature of this plan):** T4 → **Standard** (fault-tolerant DR); **T5 — partitioned scale-out on Kubernetes** added as **Enterprise**. The one row in this table already amended |

⛔ None of these edits is made by this draft **except the last row**, which records a decision the operator
took directly on 2026-09-10 ("fault-tolerant DR Standard, K8s cluster Enterprise") and is therefore already
applied. The other five are the operator's to sign.

---

## 3. The seams, grounded (2026-09-10)

Every claim below was read from source on 2026-09-10, with the premise it corrected noted where there
was one. A design plan that mis-states where a scheduler lives is the failure class this repo spent
Sprint 3 closing.

### 3.1 Two in-process schedulers, no coordination anywhere

- **`PipelineScheduler`** (`inspecto/src/main/java/com/gamma/service/PipelineScheduler.java:86-170`) is
  constructed **per Space** by `CollectorService` (`CollectorService.java:580`), holding the registry,
  the `PipelineRunGuard` and a virtual-thread `triggerWorkers` pool (`CollectorService.java:201`) by
  reference. "Run now" is decided at `:228-259` (`selectDue`/`dueThisTick`) and `:372-399` from the
  **local clock and a local `lastRunAtMs` map**. Event fan-out (`onUpstreamCommit` `:407-425`,
  `onDatasetWrite` `:435-449`) hands off through an in-heap per-pipeline `TriggerCoalescer`.
- **`JobService`** (`inspecto-engine/src/main/java/com/gamma/job/JobService.java:129, :527-562`) arms
  cron on a per-instance 2-thread `ScheduledExecutorService` (`com.gamma.util.Scheduler:37-100`) —
  self-rearming one-shots, no cross-process timer.
- **Neither file contains a lock service, a lease, or a database-backed claim.** Two pods hosting the
  same Space run every cron twice.

### 3.2 `PipelineRunGuard` is pure JVM heap — the seam a lease replaces

`inspecto/src/main/java/com/gamma/service/PipelineRunGuard.java:55-112`: a binary `Semaphore` per
pipeline id in a `ConcurrentHashMap` (`:58, :80-82`), deliberately non-reentrant (`:31-35`);
`tryAcquire` for the poll cycle (skip, never queue — `:89-92`), `acquire` for operator triggers
(`:94-98`); released by `Claim.close()` in `runOne`'s `finally` (`PipelineScheduler.java:302-322`).
It guards **ingest exclusion per pipeline**, not acquisition (that is the separate `acquireGuard`,
`PipelineScheduler.java:141`). It is the single most important seam in this plan and it is small.

✅ **S4 ANSWERED 2026-09-10 — and the answer changes §7's approach.** `PipelineRunGuard` is **not** static:
each instance owns its own, which is precisely why a lease is needed — two instances' guards do not
coordinate, so both can "win" their own local exclusion at the same time.

On the collisions, the plan's four named suspects are real but **already Space-keyed** (`EventLog.SPACES`,
`StabilityGate.SHARED`, `AcquisitionLedgers.LEDGERS`, and the `IntakeGovernor` admission maps), so they
collide only when two simulated pods host the **same Space id** — which is exactly §7's setup, so the
caveat stands. What the plan did **not** name matters more:

- 🔴 **Four statics have NO Space dimension at all**: `CircuitBreaker.SHARED` and `GapTracker.SHARED` keyed
  on a bare collector id, and `IngestProgress.CURRENT` and `StepProgress.CURRENT` keyed on a bare pipeline
  name. These collide across **any** two Spaces that share a collector or pipeline name — a **pre-existing
  defect independent of this plan**, filed on `BACKLOG.md`. ✅ **The two breakers were fixed 2026-09-10**
  (per-Space `shared()`, a `forgetSpace`, 5 tests, 3/3 mutants; no call site changed). 🔴 **The two progress
  registries could NOT be**: they live in `inspecto-etl`, and `inspecto-event` already depends on
  `inspecto-etl`, so reaching `EventLog.currentSpaceId()` from there would be a dependency **cycle** — that
  half is now a design call (a cycle-free home in `inspecto-util`, or pass the space in), on the board.
- 🔴 **`IntakeGovernor`'s fleet policy is process-wide by design, and `setGlobalPolicy` calls `caps.clear()`**
  — so one simulated pod would wipe *every* Space's learned admission caps, not just its own.
- ⚠ **`AcquisitionLedgers` has three MORE process-wide maps** (pending checksums, listings, DB watermarks)
  keyed on an **absolute file path** and documented as deliberately not per-Space. Two pods sharing one
  `dirs.poll` collide there directly. The plan's "transient maps" citation undercounted them.

**Sizing:** genuine per-*instance* scoping is **not spike-sized** — no instance-id concept exists anywhere
today, and adding one touches **12+ classes and 60–90+ sites** (8 registries already share one Space-keyed
idiom; the 4 unkeyed classes need a dimension added from scratch, across ~15 call sites). ⇒ **Take the
plan's own escape hatch for §7: give each simulated pod its own classloader.** That needs **zero**
production changes and the technique is **already proven in this repo** for pack-jar isolation. ⛔ No
existing test boots two control planes in one JVM — `ControlApiMultiSpaceTest` puts several Spaces inside
**one** instance, which is the trap §7 warns about.

### 3.3 Twelve operational store families — ten verified on Postgres, two code-capable

⚠ Premise corrected: this plan's author had "9 of 12". The roster of record is
`OperationalDb.Family` (`inspecto/src/main/java/com/gamma/service/OperationalDb.java:77-135`),
**twelve** entries: `JOB_RUNS, PROVENANCE, CONSIGNMENT_OUTPUTS, FILE_STAGES, DELIVERY_RECEIPTS,
DEDUP_LEDGER, OBJECTS, LINKS, NOTES, TAGS, STATUS, ACQUISITION_LEDGER`. All twelve route through
`JdbcDrivers.connect` (`inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java:24-40`), which
handles `jdbc:postgresql:` uniformly. `PostgresStateStoreTest` (`inspecto-ops/src/test/java/com/gamma/service/PostgresStateStoreTest.java:50-51`)
round-trips **ten** against real Postgres; **`DbDeliveryReceiptStore` and `DbDedupLedger` are not
exercised** — same factory, portable SQL, simply untested.

The defaults that a second pod turns into split-brain (`ServiceStores.java`):

| Property | Default | Backends | Line |
|---|---|---|---|
| `jobs.backend` | `none` | duckdb · postgres · `jdbc:` | `:60-72` |
| `provenance.backend` | `none` | duckdb · postgres · `jdbc:` | `:82-94` |
| `consignment.outputs.backend` | **`duckdb`** (local file) | duckdb · postgres · `jdbc:` | `:119-131` |
| `dedup.ledger.backend` | **`duckdb`** (local file) | duckdb · postgres · `jdbc:` | `:143-155` |
| `file.stages.backend` | `none` | duckdb · postgres · `jdbc:` | `:194-206` |
| `delivery.receipts.backend` | `none` | duckdb · postgres · `jdbc:` | `:178-191` |
| `status.backend` | `db` | — | `:259-293` |
| `objects.backend` (4 families) | `memory` | resolved in `inspecto-ops` | `:232-239` |
| **`events.backend`** | **`memory`** | **`memory` · `parquet` only — NO database backend** | `:216-230` |

🔴 **Events have no shared backend at all.** `EventLog` is a per-process, per-Space static registry
(`inspecto-event/src/main/java/com/gamma/event/EventLog.java:42, :61`). The Signal ledger — the spine
of Ops — is invisible across pods today.

### 3.4 No connection pool — one `Connection` per store

`AbstractJdbcStore.java:39-40`: *"all subclass access is serialised on the store's monitor"*; one
connection per store, opened at `CollectorService` construction, closed at shutdown;
`browseConnection()` (`:66`) hands that same connection to `DbBrowserRoutes`. The parked
`postgres-multi-user-plan.md` (PARKED 2026-09-06) designed exactly what N pods need: **P1** a pool
behind `JdbcDrivers` (HikariCP in-process, never a customer-run PgBouncer), **P2** a borrow-scoped
replacement for `browseConnection()`, **P3** schema-per-Space URL wiring (not database-per-Space),
**P4** a `CaseStore` seam for the JSONL ring. Its reopen trigger was *"the first multi-operator
install"*. **This plan is that trigger.**

✅ **S3 ANSWERED 2026-09-10 — HikariCP is available offline and needs NO dependency sign-off.** Measured, not
read off a pom: `HikariCP 6.3.0` is in the local Maven cache with clean checksums, and `dependency:list`
under this repo's own parent resolves **offline, BUILD SUCCESS**, with exactly **one** transitive
dependency — `org.slf4j:slf4j-api`, pinned by the parent's `dependencyManagement` to **2.0.17** rather than
Hikari's requested 1.7.36. slf4j is already first-class here: parent-managed, declared by **11** modules,
used by **179** Java files, and already in `tools/dependencies.lock` (twice). ⇒ **The lock delta is a single
line**, `com.zaxxer:HikariCP:jar:6.3.0:compile`, so the no-heavy-transitive rule is satisfied without a
sign-off. It is also a proper JPMS module (`com.zaxxer.hikari`), which matters because the bundle jlinks.
⚠ The cache also holds `2.6.1` from 2017, with `.lastUpdated` markers from a failed resolution — do not pick
it up by accident.

### 3.5 `DuckLakeRegistrar` — the shared-lakehouse enabler already exists

`inspecto-etl/src/main/java/com/gamma/etl/DuckLakeRegistrar.java:40-63` reads
`output.ducklake.{enabled, catalog_url, data_path, schema, table}`, wired at
`PipelineConfigParser.java:860` (and plural `sinks[].ducklake` at `:890`), and issues
`ATTACH 'ducklake:%s' AS lake (DATA_PATH '%s')` with the catalog URL **interpolated raw** — free-form.
DuckLake's own documentation: *"If you would like to operate a multi-user lakehouse with potentially
remote clients, use PostgreSQL as the catalog database"*, with concurrent writers coordinated by
Postgres transactions. A Postgres-backed shared catalog is therefore **a configuration change, not a
code change** in the sense that the catalog URL is interpolated raw and accepts a `postgres:` spec.

🔴 **This paragraph claimed "verified 2026-09-10, spike S1 confirms it end-to-end" BEFORE S1 had been run.**
It has now been run (§10) and **the end-to-end claim is still not established** — the Postgres-catalog and
object-store halves need a Postgres and a MinIO, neither of which the sandbox has. What S1 *did* establish,
by measurement against the pinned DuckDB 1.5.2.1:

- ✅ **The `ducklake` extension loads**, and the three catalog spellings all parse at `ATTACH`: a DuckDB
  file, `ducklake:sqlite:…`, and `ducklake:postgres:…`. The Postgres path is **compiled in and reaches a
  real connection attempt**, failing only for want of a listening server.
- ✅ **Cross-process visibility holds.** Three separate OS processes against one catalog: writer A commits
  25 rows, writer B commits 25 after A exits, and a **fourth, fresh process sees all 50** (52 snapshots).
  A commit does cross a process boundary.
- 🔴 **Concurrent registration FAILS on a SQLite catalog, so SQLite is not a stand-in for Postgres.** Two
  writer processes at once: one could not even `ATTACH` (*"database is locked"*), the other committed **0 of
  40** over 210 seconds. ⇒ The catalog backend is **load-bearing, not incidental** — which is the plan's own
  reason for specifying Postgres, now measured rather than assumed. ⛔ **A failed S1 on Postgres reopens D4**,
  and S1 on Postgres is still owed.
- 🔴 **NEW — DuckLake INLINES small writes into the catalog, and the plan does not account for it.** Those 50
  committed rows produced **zero Parquet files**: `ducklake_data_file` was empty and one table was inlined.
  Setting `DATA_INLINING_ROW_LIMIT 0` at `ATTACH` forces every write to Parquet (verified: 3 inserts → 3 data
  files → 3 files on disk). **Consequences:** (a) under §5.4(ii) the catalog Postgres becomes a *data* path
  for small batches, not only a metadata path; (b) **D13's external reader over the Hive Parquet prefix would
  silently MISS inlined rows** — see §5.4 and D13.

⚠ Two caveats. It registers files **after** the Parquet write rather than making the write visible
through the catalog (§3.6). And it is **non-fatal on any failure** (`:83-85`) — correct for an opt-in
sidecar, wrong for the mechanism every pod's visibility depends on (D10).

### 3.6 🔴 The Parquet reveal assumes POSIX atomic rename

`inspecto-etl/src/main/java/com/gamma/etl/PartitionWriter.java:226-243` (`reveal`): a cross-directory
`Files.move` to a temp name, then a **same-directory `ATOMIC_MOVE`**, with the comment at `:220-224`
saying why: *"a same-dir rename is atomic on every platform, unlike a cross-dir one."* This is a hard
filesystem assumption. An S3/GCS mount — even through a FUSE gateway — does not give same-directory
atomic rename. ⚠ `PartitionSinkWriter` (`inspecto-engine/src/main/java/com/gamma/pipeline/exec/PartitionSinkWriter.java`) follows the same
convention by its documentation; not re-read line by line (spike S2).

**This is the design fork of §5.4:** keep the rename on a shared POSIX volume, or make DuckLake's
catalog commit the visibility mechanism and retire the rename. This plan recommends the latter — it is
what DuckLake is for.

### 3.7 The inbox is single-process by construction

`CollectorProcessor.java:313` walks `dirs.poll` locally. The duplicate check
(`MarkerManager.java:40-59`) is a bare `Files.exists` on a marker — **no lock, no claim** — so two
pods polling one inbox both see "not processed" and both ingest. `StabilityGate.SHARED`
(`StabilityGate.java:75-80`) and `AcquisitionLedgers`' transient maps (`:29-38`) are in-heap and
per-Space-keyed: safe in one process, disagreeing across pods. **Consequence: an inbox must have
exactly one owning pod.** Shared-nothing partitioning gives that for free; a shared inbox would need
a claim protocol this plan does not propose.

### 3.8 Spaces are the partition unit — almost

`SpaceRoot` (`inspecto/src/main/java/com/gamma/service/SpaceRoot.java:25-227`) namespaces every store
URL and directory under `<space>/{config,data,audit,duckdb}`; `ConfigRegistry` is instantiated per
`CollectorService` (`CollectorService.java:448`), i.e. per Space. Almost everything is already
Space-scoped. ✅ **`IntakeGovernor` now is too (fixed 2026-09-10, `SPACES-GOVERNOR-1`)**: it was documented *"process-wide
and keyed by pipeline id"* with no Space key — two Spaces with a same-named pipeline shared admission-control state.
It keys by (Space, pipeline) from the calling thread's MDC, which `CollectorService.underSpace` already binds around
every poll; the fleet policy stays process-wide. See `spaces.md` §3.9. `MetricRegistry.global()` is process-global but labels carry pipeline and space, so it is
additive, not colliding.

### 3.9 Write gates pass a shared mount unchanged

`WriteGates.requireWriteRoot` (`inspecto/src/main/java/com/gamma/control/WriteGates.java:19-25`) jails
against `-Dassist.write.root`; `WriteGates.jail` (`:54-59`) delegates to `PathJail.contains`
(`inspecto-config/src/main/java/com/gamma/config/safety/PathJail.java:142-159`) over `SafetyPolicy.defaultPolicy()` roots
(`SafetyPolicy.java:83-92`). Both are pure path-containment checks with no host or process identity: a
shared volume mounted at the same path on every pod **passes both, and neither prevents two pods
writing under one root concurrently**. That protection has to come from the lease (§5.2), not the gates.

### 3.10 The container image is one process, one volume

`inspecto/package.ps1:1052-1067`: `FROM eclipse-temurin:24-jre`, `COPY . /app`, `EXPOSE 8080`, a
`/dev/tcp` health probe, `ENTRYPOINT ["./serve.sh"]`; the comment at `:1056-1057` says *"Persist data
by mounting the spaces root: `-v /srv/inspecto/spaces:/app/spaces`"*; `.dockerignore` strips the
jlink `runtime/` so the container uses the base JVM. It packages exactly one `ControlApi` process and
knows nothing of replicas, shared storage or coordination. It is the right **unit**; it is not yet a
**member**.

---

## 4. The scaling model — decision D2

Two models exist. Only one preserves the thesis.

| | **A — shared-nothing partitioning** *(recommended)* | **B — distributed engine** *(refused)* |
|---|---|---|
| Unit of scale | A pod owns a slice of Spaces; N pods, N slices | A cluster runs one logical engine |
| Analytical engine | DuckDB per pod, unchanged, per-core speed intact | Trino / Spark replaces DuckDB |
| Shared state | Postgres (operational stores, lease, DuckLake catalog) + object store (Parquet) | Same, plus the engine's own coordination |
| Cross-slice query | Any pod attaches the shared DuckLake catalog and reads all slices' Parquet | Native |
| What it costs | A lease, a partition map, one Parquet-visibility change | The lean thesis, the 90 MB artifact, and years |
| Ceiling | Linear in pods for ingest; a single very wide feed still lands on one pod unless split at source | Effectively unbounded |
| Competitive position | "Same binary, same config, N pods" — a cleaner scale story than Definite's Helm chart | Cloudera-lite, on their ground, without their maturity |

**Recommendation: A.** Model B is not deferred; it is refused, for the same reason active/active was
refused on the single-node design — it changes what the product is.

⚠ A's honest limit, stated up front: **one feed's throughput is bounded by one pod.** A 537-column CDR
stream that cannot be split at source scales by the pod's cores, not by the cluster. Partitioning
raises the *aggregate* ceiling, not the *per-feed* one. For tier-1 telecom that means splitting feeds
by switch, region or hour at source — an onboarding pattern, not a code change, and one to say aloud
in the first sales conversation.

---

## 5. The four workstreams

These are `ROADMAP.md` L1's four, grounded and reshaped by §3. Each names its invariant — the thing
a test must be able to falsify.

### 5.1 Shared state — every store on Postgres, none may degrade

**Invariant:** *In Enterprise mode, no operational store falls back to memory or a local file; a store
that cannot reach its backend fails boot.* Today stores "degrade to memory, never block boot"
(`editions.md` §3.11) — the acceptance row `VER-3` exists because of it. On one node that is graceful
degradation. On N pods it is **silent split-brain**: two pods each believing they own the truth.

Work:
- An **edition-level profile** that sets every `*.backend` to Postgres and makes fallback a boot
  failure. Not a new flag per store — one switch, `-Dinspecto.topology=partitioned` (name is D-open),
  that `ServiceStores` reads once. ⚠ Personal/Standard behaviour is unchanged.
- **`events.backend=db`** — the one store with no shared backend (§3.3). A Postgres `EventStore`
  behind the same `EventStore` interface `InMemoryEventStore` and `ParquetEventStore` implement.
  The Signal ledger must be visible from every pod or Ops sees a different world per replica.
- **Cover the two untested stores** in `PostgresStateStoreTest` — `DbDeliveryReceiptStore`,
  `DbDedupLedger` (§3.3). Twelve of twelve, or the plan's own claim is unverified.
- **Un-park the connection pool** — `postgres-multi-user-plan.md` P1 + P2 (HikariCP behind
  `JdbcDrivers`; a borrow-scoped `browseConnection()`). One connection per store per pod is fine for
  one pod and a connection storm for twenty.
- **`DuckLakeRegistrar` failure becomes fatal** in partitioned mode (D10).

**Why this ships value with zero pods:** durable restarts, `VER-3` becomes true rather than aspired
to, and every T2/T3 Postgres-backed deployment gets a pool. It is also the *entire* prerequisite for
T4 active/passive standby.

### 5.2 The lease — one run per pipeline per trigger across N pods

**Invariant:** *Across N processes sharing one Postgres, a given pipeline runs at most once per
trigger, and a lease abandoned by a dead pod is reclaimable within a bounded time.*

Work:
- Extract **`RunLease`** as an interface at the `PipelineRunGuard` seam (§3.2). Two implementations:
  `HeapRunLease` (today's `Semaphore` map, the default — Personal/Standard change nothing) and
  `PostgresRunLease` — a lease row per pipeline with owner id, acquired-at and a TTL heartbeat;
  `tryAcquire` is a conditional `UPDATE … WHERE owner IS NULL OR expires < now()`. ⛔ Postgres
  **advisory locks** are the tempting alternative and are rejected: they die with the connection, and
  a pool (§5.1) recycles connections — a lease must survive its connection.
- **`lastRunAtMs` moves to the lease row.** Today it is a local map (§3.1); an interval trigger on a
  pod that has never run the pipeline would otherwise fire immediately after a failover.
- **`JobService` cron arming goes through the same lease** — the per-instance `Scheduler` keeps
  firing everywhere, but `submit` becomes `if (lease.tryAcquire(job)) submit`. Two arming pods, one run.
- **`TriggerCoalescer`** stays local: coalescing is per-pod, the lease is what makes that safe.

**Why this ships value with zero pods:** this *is* T4. The signed RPO/RTO table promises a T4
active/passive standby with a 30-minute RTO and a promote runbook (`editions.md` §3.14). With a lease,
the standby is simply a second pod that never wins the lease until the first stops heart-beating —
the promote runbook's "stop A → start B" becomes automatic.

### 5.3 Work distribution — Spaces to pods

**Invariant:** *Every Space has exactly one owning pod at any moment; no pod polls an inbox it does
not own.*

Work:
- **Partition by Space, not by pipeline** (D3). Spaces are already the tenant boundary and the
  namespace for every store and directory (§3.8); pipelines within a Space share inboxes, ledgers and
  dedup state. Splitting a Space across pods would re-open every §3.7 race.
- **Static assignment first.** A `partition.toon` (or the Kubernetes ConfigMap that renders it)
  mapping Space → pod ordinal, read at boot; a pod hosts only the Spaces assigned to it. Dynamic
  rebalancing (a pod dies, its Spaces migrate) is **phase C+1**, explicitly deferred — it needs the
  lease's heartbeat and a controller, and static assignment already delivers scale.
- **Inbox ownership follows Space ownership.** `dirs.poll` lives on the owning pod's volume (a
  per-pod PVC) or on an object-store prefix only that pod polls. ⛔ No shared inbox (§3.7).
- **Fix `IntakeGovernor`'s missing Space key** (§3.8, §12) — required before two Spaces may ever
  share a pod safely, which they do in this model.
- **Per-tenant ABAC** — the security module's existing data-scoped grants, extended so a subject's
  Space grant is enforced identically on whichever pod serves the request.

### 5.4 The shared lakehouse — visibility is the catalog commit

**Invariant:** *A Parquet file written by any pod is visible to every pod exactly when its DuckLake
catalog transaction commits — never before, never partially.*

The fork (D4):
- **(i) Shared POSIX volume** (NFS / CephFS PVC mounted on every pod). Keeps `PartitionWriter`'s
  rename reveal (§3.6) untouched. Costs a POSIX-semantics filer in every Enterprise deployment, and
  the reveal's atomicity is then only as good as the filer's rename guarantee.
- **(ii) Object store + DuckLake catalog on Postgres** *(recommended)*. Pods write Parquet to
  S3/MinIO under a path they own; the write becomes visible when `DuckLakeRegistrar`'s catalog
  transaction commits. The same-directory rename is **no longer the visibility mechanism** and can be
  skipped on object-store paths. DuckLake's documented multi-client mode is exactly this.

Work under (ii):
- `PartitionWriter` gains a **visibility strategy**: `RenameReveal` (today, local paths) and
  `CatalogCommit` (object-store paths, partitioned mode). ✅ **S2 CONFIRMS the shared seam by reading the
  code, not the javadoc** (2026-09-10): `PartitionSinkWriter` makes **no filesystem call of its own** — it
  has no `java.nio.file` import — and delegates the whole write to `PartitionWriter.write`, and there is
  exactly **one** `reveal()` in the repo. The two lanes cannot drift on reveal semantics.
- ⚠ **S2 also found the spike's own question is ill-posed today: `CatalogCommit` does not exist in code.**
  It is this plan's proposed name. What exists is `DuckLakeRegistrar.register`, with **one call site in the
  whole repo** (`ConsignmentIngestor.finalizeSource`), on the **flat ingest lane only**, once per batch,
  **after** every file's reveal. The graph lane registers **nothing** today. So per logical write it is 1
  registration on the flat lane and 0 on the graph lane, and *"lands twice"* is not a present defect.
  🔴 **It becomes one on a specific implementation order:** wiring `CatalogCommit` *inside* `reveal()` (per
  file, both lanes) while leaving that batch-level `register` call in place would double-register the flat
  lane. ⛔ Do the two in one change, and note **no test would catch it** — `DuckLakeRegistrarTest` covers only
  the no-op, disabled and no-flag branches with `assertDoesNotThrow`, and asserts no call count.
- 🔴 **Disable DuckLake's data inlining in partitioned mode** (`DATA_INLINING_ROW_LIMIT 0` at `ATTACH`, S1).
  Otherwise a small batch never reaches the object store at all: it lands in the catalog database, which
  makes the catalog Postgres a data path and makes any external Parquet reader (D13) incomplete.
- `DuckLakeRegistrar` moves from *opt-in sidecar* to *the write path's commit step* in partitioned
  mode; its catalog URL is `ducklake:postgres:…`; failure is fatal (D10).
- **Reads attach the shared catalog.** Any pod's dashboards and Query Library can read every slice's
  Parquet through one `ATTACH`. This is what makes model A a platform rather than N isolated islands —
  and it is the property that lets one pod serve BI over data another pod ingested.
- **An external query surface on Postgres (D13, operator 2026-09-10).** Postgres views over the same
  Hive-partitioned Parquet, executed by Postgres's DuckDB extension, give BI tools and SQL clients one
  standard Postgres endpoint over every slice — without going through a pod. It is a READ surface only:
  visibility stays the catalog commit above, because a Hive glob exposes a half-written file the moment
  it appears. Gated on spike S5 (extension availability on the customer's Postgres); if S5 fails on managed
  Postgres, the surface is documented as self-managed-only, not dropped.
- `dirs.database` becomes a URI (`s3://…`) in partitioned mode; `PathJail` and the write-root gate
  (§3.9) need an object-store-aware containment rule, since prefix containment is not path
  containment.

---

## 6. Sequencing — three M's, not one XL

`ROADMAP.md` L1 is sized XL as one project. Sequenced as below, each phase is an **M**, ships alone,
and is worth having if the next phase never happens. Stop between any two.

| Phase | Delivers | Value before any pod exists | Verify gate |
|---|---|---|---|
| **A — shared state** (§5.1) | Postgres profile, `events.backend=db`, 12/12 stores tested, connection pool, fatal registrar | Durable restart; `VER-3` truthful; T2/T3 deployments get pooling. **Since 2026-09-10: the foundation of Standard's DR tier — ships in the Standard bundle** | `PostgresStateStoreTest` 12/12 · a boot with an unreachable backend in partitioned mode **fails** (falsified: reachable → boots) · pool saturation test |
| **B — the lease** (§5.2) | `RunLease` seam, `PostgresRunLease`, shared `lastRunAtMs`, cron through the lease | **T4 active/passive standby becomes automatic** — the signed 30-min RTO with no runbook step. **Since 2026-09-10 this IS the Standard fault-tolerant-DR deliverable**, so `RunLease` and `PostgresRunLease` live in core or `inspecto-security`-tier modules, ⛔ never behind `inspecto-policy` | §7's test · a killed owner's lease is reclaimed within TTL · `HeapRunLease` behaviour byte-identical for Personal/Standard |
| **C — partition + lakehouse** (§5.3, §5.4) | Space→pod map, inbox ownership, `CatalogCommit` visibility, Helm chart, per-tenant ABAC | Horizontal scale | 3 pods · 3 Spaces · one Postgres · one MinIO: every pipeline runs once per trigger, every pod reads every slice, killing a pod loses nothing committed |

⚠ **The Helm chart is the last artefact of phase C, not the first of phase A.** By then Kubernetes is
packaging, not architecture. Writing the chart first is how a team ends up with `replicas: 4` and
four schedulers.

---

## 7. The first test to write — before any Enterprise code

**`RunLeaseContractTest`**: boot **two** `ControlApi` instances in one JVM against one Postgres (the
same gating `PostgresStateStoreTest` uses), sharing one Space's config; fire one interval trigger and
one operator trigger; assert **exactly one run** per trigger across both instances, and that the
losing instance's `tryAcquire` returned false rather than blocking. Then kill the owner mid-run
(close its lease connection) and assert the lease is reclaimable after TTL and **not before**.

Mutation-proven before it counts: remove the conditional `WHERE` from the lease `UPDATE` → both
instances run (fails); shorten TTL to zero → the live owner's lease is stolen (fails); swap in
`HeapRunLease` → the two-instance test fails, because heap state is not shared — which is the point.

⚠ Two instances in one JVM share `static` registries (`EventLog.SPACES`, `StabilityGate.SHARED`,
`AcquisitionLedgers`, §3.7–3.8). The test must either run each instance in its own classloader or
scope those registries per instance — the second is a §5.3 deliverable anyway. **Do not let the test
pass because two "pods" secretly shared a heap.**

✅ **S4 SETTLES WHICH (2026-09-10): use a classloader per simulated pod.** The alternative — genuine
per-*instance* scoping — is **not** a §5.3 by-product and not spike-sized: **no instance-id concept exists
anywhere in the codebase**, and introducing one touches **12+ classes and 60–90+ sites** (eight registries
already share one Space-keyed idiom and would need their key widened; four more have no Space dimension at
all and need one added from scratch, across ~15 call sites). A classloader per pod needs **zero production
changes**, and the technique is **already proven in this repo** for pack-jar isolation. Since this test's
actual assertion is about the Postgres-backed lease and not about in-heap registries, classloader
isolation sidesteps the question rather than pretending to solve it.

⛔ **And no existing harness gives you this for free.** `ControlApiMultiSpaceTest` puts several Spaces
inside **one** `ControlApi` — it is not two instances, and mistaking it for one is exactly the trap the
paragraph above warns about. Nothing in the repo boots two control planes in one JVM today.

---

## 8. Risks

| Risk | Why it is real here | Mitigation |
|---|---|---|
| **Split-brain that looks like success** | Every store degrades silently today (§3.3); two pods each "own" a Space and both report healthy | Phase A's fail-closed boot; the §7 test; `VER-3` asserting every subsystem is `UP` **and shared** |
| **Duplicate runs from cron** | `JobService` arms per instance (§3.1) | Lease on `submit`, not on arming; test with two instances and one cron |
| **A stolen lease** | TTL too short vs a long ingest; heartbeat missed under GC pause | TTL ≥ 3× heartbeat; heartbeat on its own thread; the §7 TTL test |
| **Rename atomicity on object storage** | `PartitionWriter:220-243` is explicit about needing POSIX (§3.6) | Model (ii): catalog commit is visibility; `RenameReveal` only on local paths |
| **Wide single feeds** | Partitioning does not split one feed (§4) | Say it in sales; onboarding pattern: split by switch/region/hour at source |
| **Connection storms** | One connection per store per pod (§3.4) × 12 stores × N pods | Phase A's pool, sized per scheme |
| **Dynamic rebalancing creep** | "A pod died, move its Spaces" is a controller, not a config | Explicitly phase C+1; static assignment first |
| **The 90 MB claim** | Enterprise now needs Postgres + object store | Keep the claim for Personal/Standard; Enterprise says "same artifact, plus your Postgres and S3" (D9) |

---

## 9. Decisions asked — operator to sign

| # | Question | Recommendation |
|---|---|---|
| **D1′** | Revise the signed container decision so the image is the Enterprise unit of deployment and orchestration is in scope for Enterprise? | ✅ **SIGNED 2026-09-10 (operator)** — **Yes.** Personal/Standard stay orchestrator-free; §2 amendments APPLIED with this signature |
| **D2** | Scaling model | ✅ **SIGNED 2026-09-10 (operator)** — **A — shared-nothing partitioning.** B refused, not deferred |
| **D3** | Partition unit | ✅ **SIGNED 2026-09-10 (operator)** — **Space**, not pipeline — it is already the namespace for every store and directory (and, since `SPACES-GOVERNOR-1`, for admission state) |
| **D4** | Shared lakehouse | ✅ **SIGNED 2026-09-10 (operator)** — **(ii) object store + DuckLake catalog on Postgres**, catalog commit as visibility; (i) shared POSIX volume kept as a documented fallback for sites without object storage |
| **D5** | Lease mechanism | ✅ **SIGNED 2026-09-10 (operator)** — **A lease table with TTL heartbeat.** ⛔ Not advisory locks (die with pooled connections); ⛔ not the Kubernetes Lease API (couples the engine to the orchestrator). Per D8 this lease is also Standard's T4 standby |
| **D6** | Events across pods | ✅ **SIGNED 2026-09-10 (operator)** — **Add `events.backend=db`** on the existing `EventStore` seam |
| **D7** | Connection pool | ✅ **SIGNED 2026-09-10 (operator)** — **Un-park `postgres-multi-user-plan.md` P1 + P2** as phase A work (spike S3 first) |
| **D8** | Tier naming | ✅ **DECIDED 2026-09-10 (operator):** **T5 — partitioned scale-out on Kubernetes** = **Enterprise**, added to §3.9; **T4 active/passive DR moves to Standard**. Applied |
| **D9** | The "zero external runtime services" claim | ✅ **SIGNED 2026-09-10 (operator)** — refined by D8: **Personal — zero. Standard — zero unless DR (T4) is enabled, then Postgres. Enterprise — Postgres + S3-compatible object store.** The 90 MB artifact claim stays true: same artifact, plus YOUR services |
| **D10** | `DuckLakeRegistrar` failure in partitioned mode | ✅ **SIGNED 2026-09-10 (operator)** — **Fatal.** A pod that cannot reach the shared catalog must not write files nobody can see; single-node mode keeps today's opt-in, warn-only behaviour |
| **D11** | Dynamic rebalancing | ✅ **SIGNED 2026-09-10 (operator)** — **Deferred to phase C+1**; static Space→pod assignment first |
| **D12** | The partitioned-mode switch's name | ✅ **SIGNED 2026-09-10 (operator)** — **`-Dinspecto.topology=partitioned`** (values `single` \| `partitioned`; also what `/bootstrap` reports). Not `mode=cluster` — D2 refused the cluster engine, and Standard's two-pod T4 standby is partitioned without being a cluster |
| **D13** | *(new, operator 2026-09-10)* An external SQL/BI query surface: Postgres views over the Hive-partitioned Parquet, executed by Postgres's DuckDB extension (pg_duckdb-style) | ✅ **SIGNED 2026-09-10 (operator)** — **Added as the external query surface; DuckLake catalog commit stays the write-visibility event.** A Hive glob sees a half-written file the moment it appears, so visibility must remain the commit, not file existence. Spike **S5** first: is `pg_duckdb` installable on the customer's Postgres (managed services such as RDS do not allow it)? → §5.4. 🔴 **S5 ANSWERED 2026-09-10 — SELF-MANAGED POSTGRES ONLY.** `pg_duckdb` is installed by **building from source** (`make install`), and it appears on **none** of the curated extension lists of Amazon RDS/Aurora, Google Cloud SQL or Azure Database for PostgreSQL Flexible Server — all three publish a fixed set, so a customer cannot add one that is not on it. ⚠ **Evidence strength, stated so it can be re-checked:** Azure's list was read in full from the primary source (Microsoft Learn, *List of Extensions and Modules by Name*, dated 2026-07-10) and contains **no** extension whose name contains "duck"; RDS/Aurora and Cloud SQL rest on their published lists as surfaced by search rather than a full read. ⇒ Treat Azure as settled and the other two as very likely; **the live half of S5 is what confirms all three.** It supports Postgres 14–18 and reads Parquet/CSV/JSON/Iceberg/Delta from S3, GCS, Azure and R2. ⇒ **The external query surface is NOT general.** It is available to a self-managed Postgres and unavailable to the managed services an Enterprise customer is most likely to already run — so D13 must be sold as an option with a deployment precondition, never as a default. 🔴 **And it needs one more thing to be correct at all:** DuckLake **inlines** small writes into the catalog (S1), so a view over the Hive Parquet prefix would silently omit them unless inlining is disabled — see §3.5. ⚠ The live half of S5 (install it, build the view, query it from `psql`) is still owed; this sandbox has no Postgres and no container daemon. |

---

## 10. Spikes before phase A starts (each ≤ half a day)

### Outcome (2026-09-10) — 3 of 5 CLOSED, 2 partially answered and still owed

| Spike | Verdict |
|---|---|
| **S1** | 🟡 **PARTIAL — the Postgres/object-store half is still owed** (no Postgres, no container daemon here). Established: the extension loads on the pinned DuckDB 1.5.2.1; all three catalog spellings parse and the `postgres:` path reaches a real connection attempt; **cross-process visibility holds** across three separate OS processes. 🔴 **Concurrent registration FAILS on a SQLite catalog** (one process could not `ATTACH`, the other committed 0 of 40), so SQLite is **not** a valid stand-in and the catalog backend is load-bearing. 🔴 **New finding: DuckLake inlines small writes into the catalog** — 50 rows produced **zero** Parquet files, and `DATA_INLINING_ROW_LIMIT 0` is the knob. → §3.5 |
| **S2** | ✅ **CLOSED — shared seam confirmed by reading the code.** `PartitionSinkWriter` has no filesystem call of its own. ⚠ But `CatalogCommit` **does not exist in code** — it is this plan's own name — so *"lands once, not twice"* is not a present defect: the flat lane registers once per batch and the graph lane not at all. The double-emit is a **specific implementation-order hazard**, and no test would catch it. → §5.4 |
| **S3** | ✅ **CLOSED — no sign-off needed.** HikariCP resolves offline with one transitive dependency that is already first-class here; the lock delta is one line. → §3.4 |
| **S4** | ✅ **CLOSED — and it redirects §7.** Per-instance scoping is 12+ classes and 60–90+ sites, so use a classloader per simulated pod instead. Four statics the plan never named have no Space dimension at all. → §3.2 |
| **S5** | 🟡 **PARTIAL, but the DECISION half is answered: self-managed Postgres only.** `pg_duckdb` builds from source and is on none of the curated extension lists of RDS/Aurora, Cloud SQL or Azure Flexible Server. The live install-and-query half is still owed. → D13 |

🔴 **Two spike results were asserted in this plan BEFORE the spikes ran.** §3.5 said *"verified 2026-09-10,
spike S1 confirms it end-to-end"* and §5.4 said *"Spike S2 confirms…"*, while §10 still listed both as
work to do. S2's assertion turned out to be true; **S1's was not, and still is not.** ⛔ This is the same
class Sprint 7.5 closed elsewhere — *a false ✅ is worse than a blank, because nobody re-checks a tick* —
and it appeared inside a **signed** plan, in the two places a reader would most trust.

⚠ **What the two owed halves need**, so the next environment can run them unattended: a reachable Postgres
(any 14–18) and an S3-compatible endpoint (MinIO). This sandbox has the Docker CLI but **no running
daemon**, and starting one is outside a spike's remit.


- **S1** — `ATTACH 'ducklake:postgres:…'` from two DuckDB processes writing to one MinIO bucket:
  confirm concurrent registration and cross-process visibility with DuckDB 1.5.2's `ducklake`
  extension. This is the plan's load-bearing assumption; it is verified from documentation, not yet
  from this codebase.
- **S2** — Read `PartitionSinkWriter` line by line; confirm it shares `PartitionWriter`'s reveal
  seam (§3.6) so `CatalogCommit` lands once, not twice.
- **S3** — HikariCP offline availability in the Maven cache (`-o`); if absent, the pool is a
  dependency sign-off per the repo's no-heavy-transitive rule.
- **S4** — Two `ControlApi` instances in one JVM: which `static` registries collide (§7's caveat),
  and whether per-instance scoping is a small change.
- **S5** *(D13)* — Install the DuckDB extension on a Postgres, create a view over a Hive-partitioned
  Parquet prefix on MinIO, query it from `psql`; then record which managed Postgres services permit the
  extension. Decides whether the external query surface is general or self-managed-only.

---

## 11. What it does to the positioning

A **three-message product** since the 2026-09-10 tier decision, and each message holds:

- **Personal** — *the 90 MB artifact: zero external services, runs on a laptop or an air-gapped server.*
  *(D9: **Standard** is zero too unless T4 DR is enabled, which needs a Postgres; **Enterprise** states Postgres +
  an S3-compatible object store — the same artifact, plus your services.)*
  Unchanged.
- **Standard** — *the same artifact, and if the node dies the standby takes over inside the signed RTO.*
  **Fault-tolerant DR** (T4) is the Standard headline beside the Ops workflow and the audit pack. Its
  honest dependency: Postgres — Standard DR is database-backed, not optionally so.
- **Enterprise** — *the same artifact as a pod, scaled by partition: one binary, one config, N pods,
  your Postgres, your S3.* The **cluster** (T5) and policy-level tenant isolation. A cleaner scale story
  than the closest competitor's Helm chart, because there is no second architecture to learn.

⚠ The ladder now reads as *survive a node* (Standard) → *outgrow a node* (Enterprise), which is the
distinction a buyer already understands.

It does not break the air-gap wedge — regulated on-prem estates already run Kubernetes (OpenShift is
standard in banks and telecoms). It **does** put tier-1 telecom back in reach, with the per-feed
caveat of §4 said aloud.

---

## 12. Defects found while grounding — filed, not fixed here

- ✅ **`IntakeGovernor` has no Space key** (§3.8) — **FIXED 2026-09-10** (`SPACES-GOVERNOR-1`, pinned by a two-Space
  isolation test); the row is closed. Kept here as the record that the grounding found it.
- **`DbDeliveryReceiptStore` and `DbDedupLedger` are the two stores `PostgresStateStoreTest` does not
  cover** (§3.3). Not a scale-out item — a gap in DAT-6's own claim of coverage. → phase A, or a
  board row if phase A does not start.
- **`events.backend` has no database option** (§3.3). Single-node consequence: a Postgres-backed T2
  deployment still loses its Signal ledger on restart unless `parquet` is chosen. → phase A.
- 🔴 **Four registries have no Space dimension at all** (§3.2, found by S1–S5's spike round on 2026-09-10):
  `CircuitBreaker.SHARED` and `GapTracker.SHARED` keyed on a bare collector id, `IngestProgress.CURRENT` and
  `StepProgress.CURRENT` on a bare pipeline name. **Wrong today, on one node** — two Spaces sharing a name
  already share a breaker, a gap set and a progress snapshot. Filed `SPACE-UNKEYED-STATICS-1`. ⛔ Not a
  scale-out item; do not fold it into this plan's phases.
- **No test would catch a double catalog registration** (§5.4). Filed `DUCKLAKE-COMMIT-COUNT-1`; it is the
  precondition for the visibility-strategy work, because the double-emit hazard is an implementation-order
  one and would otherwise ship green.
- ✅ **Six raw NUL bytes in five tracked source files made those files invisible to every recursive
  ripgrep** — found while running S4, when an agent's report of "drops out of a naive grep" turned out to
  understate it: a recursive search over the module listed three files and silently omitted a fourth that
  plainly matched. **FIXED 2026-09-10** (each was a separator in a composite map key written as a raw byte
  instead of the escape) with a guard wired into both the hook and the pipeline, falsified four ways. ⛔ Not
  a formatting nicety: this repository reviews itself with grep, so a silent false negative there is worse
  than a wrong answer. ⚠ **The same byte had a second, quieter effect:** because git also classified the
  file as binary, it was silently exempt from the repository's own `text=auto eol=lf` policy and was
  stored with CRLF — so one stray byte bought an exemption from two separate repo-wide rules at once,
  and neither rule reported it.
