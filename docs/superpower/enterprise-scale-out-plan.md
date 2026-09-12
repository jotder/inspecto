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
  half is **also fixed, 2026-09-10** — the operator chose the cycle-free home: `CurrentSpace` in
  `inspecto-util` (which both modules already depend on), with `EventLog.currentSpaceId()` delegating to it
  so the key keeps one definition. Passing the space in was refused on measurement: it is **not in scope** at
  the call sites. ⚠ The fix also exposed that `inspecto-etl` had **no slf4j binding on its test classpath**,
  so its MDC was a no-op and no test in that module could ever have observed space keying — closed with a
  test-scoped binding that leaves the runtime dependency lock unchanged.
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

### 3.3 Fourteen operational store families — thirteen with a Postgres round-trip, one without

⚠ Premise corrected: this plan's author had "9 of 12". The roster of record is
`OperationalDb.Family` (`inspecto/src/main/java/com/gamma/service/OperationalDb.java:77-135`),
**fourteen** entries since 2026-09-12: `JOB_RUNS, EVENTS, RUN_LEASE, PROVENANCE, CONSIGNMENT_OUTPUTS,
FILE_STAGES, DELIVERY_RECEIPTS, DEDUP_LEDGER, OBJECTS, LINKS, NOTES, TAGS, STATUS, ACQUISITION_LEDGER`.
All fourteen
route through `JdbcDrivers.connect` (`inspecto-util/src/main/java/com/gamma/util/JdbcDrivers.java:24-40`),
which handles `jdbc:postgresql:` uniformly.

✅ **A3 (2026-09-12) closed the coverage gap this section recorded.** `PostgresStateStoreTest` now
round-trips **twelve** store classes — it gained `DbEventStore` (new with D6) plus `DbDeliveryReceiptStore`
and `DbDedupLedger`, the two this section called out as *"same factory, portable SQL, simply untested"*.
Only the **acquisition ledger** is still uncovered.

⛔ **But read that as COVERAGE, not evidence.** Every method in the class `assumeTrue`s on a configured
server, so with no `INSPECTO_TEST_PG_URL` the whole class SKIPS — **the three added in A3 have never
executed anywhere**, and neither have the original nine on this machine. The number to read is the SKIP
count. This is the same trap as a guard that reports nothing because it was never armed.

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

### 4.1 The unit of distribution — four candidates *(added 2026-09-11, operator challenge)*

🔴 **D3 ("partition by Space") was signed as an invariant, and it does not survive the single-tenant
case.** An operator asked the obvious question — *what if there is only one Space, under huge load?* —
and under D3 as written, N pods buy that customer **nothing**. For a tier-1 telecom that most likely has
one Space with many feeds, that is the common case, not an edge case, and it is the flagship Enterprise
story. This section records the grounding; the resulting choices are **D14–D16** in §9, unsigned.

D3's stated justification is that pipelines in a Space share **inboxes**, **ledgers** and **dedup state**.
Two of those three do not survive grounding:

- **Ledgers and dedup state — phase A itself retires this.** All twelve `OperationalDb.Family` entries,
  `DEDUP_LEDGER` included, route through `JdbcDrivers.connect`, which handles `jdbc:postgresql:`
  uniformly (§3.3). Moving them to shared Postgres *is* phase A. The objection argues against splitting
  **today**, not against splitting.
- **Shared inbox — real, but not a property of a Space.** `dirs.poll` is a **required per-Pipeline**
  field (`ConfigSpecs.java:92`). In the committed corpus `payments` and `shipments` have distinct
  inboxes while `orders` and `orders_enriched_rollup` share one. Sharing is a *config fact about a set
  of Pipelines*, and it is **decidable at boot**.

⇒ The indivisible unit is not the Space. It is the **inbox-sharing group**: the connected components of
*"names the same `dirs.poll`"*.

**Four candidate units (`U1`–`U4`), independent of one another — a deployment can adopt one without the
others. ⚠ Lettered `U*` on purpose: §6's phases are already A/B/C and the two must not be conflated.**

| | Unit | Lifts the per-feed ceiling? | Needs shared file storage? | Cost |
|---|---|---|---|---|
| **U1** | **Remote acquisition** — one remote file | Yes, for the fetch | **No** | Low — the claim surfaces already exist |
| **U2** | **Consignment execution** | **Yes** | Yes (volume or object store) | Medium — a claim table + an ordering decision |
| **U3** | **Pipeline / inbox-group** | No | No | Low — a finer partition map |
| **U4** | **Space** (today's D3) | No | No | None — already the model |

**U1 — remote acquisition is the best first move, and §5.3 misses it entirely.**
`RemoteAcquisitionHandler` already materialises bytes listed by a remote `CollectorConnector` into a
local staging tree "so the rest of the engine … treats them exactly like local files", with rate-limited,
**optionally parallel** fetch and pre-fetch dedup through `AcquisitionLedgers`.

🔴 **§3.7's "an inbox must have exactly one owning pod" does not apply to a remote origin.** A local
inbox needs a single owner because `MarkerManager` does a bare `Files.exists` with no claim. A remote
origin is *already shared by definition*, listing it is idempotent, and a pre-fetch dedup ledger already
exists — and **`ACQUISITION_LEDGER` and `FILE_STAGES` are both among the fourteen families** (§3.3), so
both are already Postgres-capable and phase A puts them on shared state as a side effect.

⇒ N pods can pull from one remote origin safely, claiming per file, **with no new mechanism and no
shared filesystem**. For telecom — thousands of small files over SFTP, I/O-bound — this is the cheapest
horizontal scale in the system and the one that needs the least new design.

**U2 — consignment execution is the only unit that lifts the per-feed ceiling**, which §4 above calls an
unavoidable limit. It is unavoidable only while the unit is the Space or the Pipeline. The seam already
exists in-process: `ConcurrencyBroker` is admission control for **Consignment execution slots** — at most
`processing.threads` concurrent Consignments **per Pipeline**, plus per-space and per-server caps, with
stride-scheduling fairness. The engine already runs N Consignments of one Pipeline at once; it is JVM-local.
Distributing it = **single dispatcher, N workers**: the owning pod keeps the poll (§3.7 intact, because
*discovery* stays single-process) and enqueues Consignments; any pod claims and executes one.
⚠ Three costs, stated: workers must read the staged files (D4's object store, or (i)'s shared volume);
the broker's three tiers become **approximate** per-pod unless the counters move to Postgres (take the
approximation first — exact global fairness is not worth a round trip per admission); and **ordering needs
a decision**, because the broker deliberately preserves FIFO per Pipeline and distribution breaks that.

**What still does not scale, honestly:** a **single Pipeline with a single enormous feed** remains bounded
by one pod's discovery rate even under U2. Only splitting at origin, or intra-Pipeline parallelism (a
different design, not this plan), changes that.

### 4.2 Combating split-brain — the lease is not enough *(added 2026-09-11)*

⚠ **A TTL lease does not prevent split-brain; it makes it unlikely.** A GC pause or partition longer than
the TTL leaves the old owner still believing it holds the lease. ⛔ This is equally true of a ZooKeeper
session, so **changing coordinator does not fix it** — see D15.

The defences that do, best-fit first for this system:

- **Idempotent writes keyed on Consignment / file id — the right destination, but 🔴 NOT reachable from
  where the code actually is.** The principle stands: stop trying to make double execution *impossible*;
  make it *harmless*. ⛔ **What this bullet originally claimed — that `DbDedupLedger` and `FileStages`
  "already exist", so a second pod's work is "wasted effort rather than corruption" — was measured on
  2026-09-11 and is FALSE for three of its four surfaces.** `DbDedupLedger` is genuinely conditional
  (`PRIMARY KEY` + `ON CONFLICT DO NOTHING`); `DbFileStageStore` and `DbConsignmentOutputStore` have **no
  unique constraint and no CAS**; and the `batchId` every write keys on is **wall-clock derived at second
  granularity**, so two executors cannot agree on it and the failure flips between clobber and duplication
  depending on clock alignment. ⇒ **Prerequisite, not a detail: a deterministic, content-derived Consignment
  identity, plus unique constraints on the registry and a CAS on the stage store.** Only then does this
  bullet make `U1` and `U2` cheap. Full evidence and line refs in §12; D15 is re-posed accordingly.
- **Fencing tokens.** A monotonic epoch issued with the claim, carried on every write, validated **at the
  resource** (`UPDATE … WHERE epoch <= :token`). The only thing that closes the paused-owner hole.
- **Make the DuckLake catalog commit the fencing point.** §5.4 already makes it the *visibility* boundary;
  making it the *serialization* boundary gives one place where correctness is decided.
- **Reuse the `If-Match` optimistic-concurrency pattern already shipped** (`CLIENT-HALVES-1`, on
  `/config/write`) rather than inventing a second concurrency story.
- **Object-store conditional PUT** in place of §3.6's POSIX atomic-rename assumption, which this plan
  already flags as a risk on shared storage.

⛔ **ZooKeeper / etcd is refused** (D15), and not on taste: Postgres is already mandatory for Enterprise
(D9) and is already a linearizable store in the critical path; ZK would be a **third** stateful system to
run, back up and upgrade; **two coordinators is a new split-brain surface** (ZK says pod A owns it while a
transaction from pod B commits to Postgres anyway); the coordination rate — polls per N seconds,
Consignments per minute — does not justify it; D5 already refused both advisory locks and the Kubernetes
Lease API, and ZK is heavier than either.

---

## 5. The five workstreams

These are `ROADMAP.md` L1's four, grounded and reshaped by §3. Each names its invariant — the thing
a test must be able to falsify.

### 5.1 Shared state — every store on Postgres, none may degrade

**Invariant:** *In Enterprise mode, no operational store falls back to memory or a local file; a store
that cannot reach its backend fails boot.* Today stores "degrade to memory, never block boot"
(`editions.md` §3.11) — the acceptance row `VER-3` exists because of it. On one node that is graceful
degradation. On N pods it is **silent split-brain**: two pods each believing they own the truth.

Work:
- An **edition-level profile** that sets every `*.backend` to Postgres and makes fallback a boot
  failure. Not a new flag per store — one switch, `-Dinspecto.topology=partitioned` (name SIGNED as D12,
  2026-09-10; values `single` | `partitioned`, and what `/bootstrap` reports),
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

> 🔴 **⚠ QUALIFIER on "COMPLETE" below, added 2026-09-12: §7's `RunLeaseContractTest` WAS NEVER WRITTEN.**
> §7 calls it "the first test to write — **before any Enterprise code**", and the phase-A/B row in the
> archived sprint plan names it as the gate ("the §7 contract test green on two JVMs sharing one
> Postgres"). Phases A and B both shipped without it, and the "COMPLETE" below means **every slice is
> built and unit-verified**, NOT that the plan's stated gate is met. ⛔ Do not cite §5.2 as gate-passed.
> The unit coverage is real (`DbRunLeaseTest` drives two lease instances over one DuckDB file, which is
> two *processes* as far as the lease is concerned) but it is **not** two `ControlApi` instances, and
> `ControlApiMultiSpaceTest` is not it either — §7 warns explicitly against mistaking one for the other.
> Filed as `RUNLEASE-CONTRACT-TEST-1`.
>
> ✅ **§5.2 COMPLETE 2026-09-12 — B0, B1, B2, B3 all shipped.** Four lease scopes now exist and are
> deliberately disjoint: `run` (collector-pipeline names), `acquire` (remote fetch), `job` (job names),
> `authored` (authored-pipeline ids). `TriggerCoalescer` is an explicit non-item — coalescing stays per-pod.
> ⛔ The four scopes key **four different id spaces**; collapsing any of them together excludes unrelated
> units of work. See the `SCOPE_AUTHORED` note in `DbRunLease` for why that is not merely a tidiness rule.

Work:
- ✅ **SHIPPED 2026-09-12 (slice B0).** `RunLease` extracted at the `PipelineRunGuard` seam (§3.2),
  with `PipelineRunGuard` itself as the heap implementation — the default, so **Personal and single-node
  Standard are byte-identical** — and **`DbRunLease`** as the shared one: a row per pipeline with owner,
  epoch, acquired-at and expiry; `tryAcquire` is the conditional
  `UPDATE … WHERE owner IS NULL OR expires_at < now()`. ⛔ Advisory locks stay rejected (D5): they die
  with the connection and the §5.1 pool recycles connections — a lease must outlive its connection.
  ⚠ **The extraction touched NO call site.** `Claim` is declared on `RunLease` and inherited, and Java
  resolves a nested type through the implementing class, so every `PipelineRunGuard.Claim` reference
  still compiles.

  🔴 **The Space is bound at CONSTRUCTION, not passed per call — and that is the load-bearing decision.**
  A pipeline id is unique only *within* a Space; today's guards get away with a bare id purely because
  there is one guard instance per `CollectorService` and therefore per Space. A shared table has no such
  boundary, so keyed on the id alone two Spaces running an `orders` pipeline would share one row and each
  would block the other — the `IntakeGovernor` bug at cluster scale. `PRIMARY KEY (space, pipeline)`,
  pinned by a test.

  🔴 **Fenced, not merely TTL'd.** A TTL alone does not prevent split-brain: a pod paused past its TTL
  wakes up still believing it holds the lease. Every write is conditional on `owner = me AND epoch = mine`,
  so a stale owner can neither release nor extend a lease someone else now holds. ⚠ **This is the half of
  D15 that survives its refutation** — fencing works here precisely because `(space, pipeline)` is a
  *stable* id, which `batchId` is not (`CONSIGNMENT-ID-DETERMINISTIC-1`). ⛔ It fences the lease, not the
  writes a run performs; do not read B0 as discharging D15.

  ⚠ **The fencing test was wrong on the first attempt and a mutation run caught it.** The obvious
  scenario — pod A paused, pod B takes over, pod A releases — is blocked by the `owner` predicate alone,
  so it left a fencing-defeating mutant alive. The discriminating case needs the **same owner id** on
  both (one pod reconnecting, or any deployment that sets a stable owner such as a StatefulSet pod name).
  ⛔ Do not "simplify" that test back.
- ✅ **DECIDED and SHIPPED 2026-09-12 (slice B1) — the two guards stay SEPARATE, via a scope key.**
  §5.2 only ever described one guard; there are two. `CollectorService.runGuard` gates pipeline **runs**,
  `PipelineScheduler.acquireGuard` (`:141`) gates **remote acquisition**, and the scheduler's own comment
  says acquisition runs independently of pipeline execution.
  🔴 A shared lease table would have collapsed that independence: keyed on `(space, pipeline)` alone,
  pointing both at one lease makes a remote fetch **block a run** of the same pipeline — pipelines would
  stall whenever an upstream was slow. **Operator decision: keep them separate.** The key is now
  `PRIMARY KEY (space, scope, pipeline)` with scopes `run` and `acquire`, and
  `CollectorService` opens **two** leases, one per scope.
  ⛔ Do not collapse the `scope` column away — `DbRunLeaseTest.acquisitionAndExecutionDoNotBlockEachOther`
  is the test that fails if it goes, and a companion test proves the scope did not merely disable
  exclusion within a scope.
- ✅ **WIRED (B1).** `ServiceStores.openRunLease(root, scope)` selects on **`-Drun.lease.backend`**
  (`heap` default · `db` · `postgres` · raw `jdbc:`), mirroring `openEventStore`'s shape, with
  `OperationalDb.Family.RUN_LEASE`, `SpaceRoot.runLeaseDbUrl()` and `StoreHealth` on both arms.
  ⛔ Default `heap` — a lease is exclusion *across processes*; on one node the in-heap guard is correct
  and free, and a DB default would create a file for every Personal install to coordinate a fleet of one.
  ⚠ A failed open **degrades to the heap guard and records DEGRADED**, which
  `-Dinspecto.topology=partitioned` turns into a boot failure (A1): on N pods a per-process lease is not
  a weaker guarantee, it is none.
- ✅ **SHIPPED 2026-09-12 (slice B2) — `lastRunAtMs` moved onto the lease.** It was a
  `ConcurrentHashMap` field on `PipelineScheduler`, i.e. per process; on N pods that is not a shared
  baseline but N independent ones, so a pod that had never run the pipeline read "never" — which
  `dueThisTick` turns into "due now" — and re-ran a pipeline another pod had just run, however long the
  interval. `RunLease` gained `lastRunAt(pipeline)` / `recordRun(pipeline, epochMs)`; `PipelineRunGuard`
  holds the identical map (⛔ **Personal and single-node Standard are unchanged**) and `DbRunLease` holds
  a `last_run_at` column on the row it already had.

  🔴 **The scope question here is answered by the code, not by the operator — and it is NOT the B1
  question repeated.** The cadence belongs to the **run** scope alone: `selectDueForAcquire` never calls
  `dueThisTick` and never touches the baseline, because acquisition runs on *the acquisition timer's own
  interval* and deliberately ignores a pipeline's `trigger:` (a cron-gated pipeline still wants its files
  staged before the cron fires). `theRunAndAcquireCadencesAreIndependent` pins it.

  🔴 **The read is unfenced and owner-independent; the write is fenced.** "Who may run it now" and "when
  did it last run" are different questions — a released or expired lease still carries a valid baseline,
  and a pod that has never held the lease *must* be able to read it or the whole fix is undone. The write
  is fenced on `owner = me AND epoch = mine` exactly like `release`, so a pod paused past its TTL cannot
  push another owner's pipeline out by a full interval. ⛔ Do not add an `owner`/`expires_at` predicate to
  the read.

  ⚠ **The poll cycle stamps only triggers that READ the baseline** (`usesCadence`). Unconditional
  stamping was free as a heap map; against a shared lease it is one `UPDATE` per due pipeline per tick,
  and `DEFAULT_POLL` pipelines — the common case, due *every* tick — never read the value back. This is a
  write-elision, not a behaviour change: the elided value is unreadable. `recordManualRun` stays
  unconditional (operator path, not the hot loop). ⛔ Keep `usesCadence` in step with `dueThisTick`.

  ⚠ **A guarded `ALTER` migrates tables B0/B1 already created** — `CREATE TABLE IF NOT EXISTS` would
  leave them without the column and fail on the first cadence read, a fault that cannot appear on a fresh
  install. `aLeaseTableFromBeforeTheCadenceColumnIsMigrated` builds the pre-migration table by hand.

  ⚠ **All three new guards were mutation-verified 2026-09-12**: unfencing the write fails exactly the
  fencing + unclaimed-write tests, deleting the `ALTER` fails exactly the migration test, and stubbing
  `lastRunAt` fails the cross-pod test. The elision is itself guarded by the pre-existing
  `CollectorServiceTriggerTest.intervalTriggerGatesTheLoopByItsOwnCadence` (runs on tick 1, must NOT run
  on tick 2) and `noTriggerRidesEveryPollCycle`.
- ✅ **SHIPPED 2026-09-12 (slice B3) — `JobService` arming + the authored-pipeline guard.** The last item in
  §5.2. The per-instance `Scheduler` keeps firing everywhere; `runJob` now passes **three** exclusions,
  cheapest first, each a *skip* and never a queue: this pod's own `LockingRunner`, then a **cross-pod
  arming claim keyed by job NAME**, then — for a pipeline job — a claim on the **authored PIPELINE** it targets.

  🔴 **Two keys, because the operator's decision was "job name arms, pipeline guards"** (2026-09-12).
  The arming claim is the cross-pod form of the in-process lock: N pods arm one cron, one wins. The authored-pipeline
  claim closes a **different, pre-existing** hole — `triggerPipelineRun` builds a synthetic config named
  after the *pipeline id*, while a registered job targeting that same pipeline carries its *own* name, so the
  job-name lock never excluded them and both ran the one pipeline at once. Two registered jobs sharing a
  `pipeline:` param are the same hole; nothing validates that param for uniqueness.

  🔴 **⛔ Neither claim is `runGuard`, and the premise that they could be was WRONG.** When this bullet
  was written it was assumed a cron pipeline-job and a poll-cycle run could collide on one pipeline. They
  cannot: `SCOPE_RUN` keys **collector-pipeline config names** (`*_pipeline.toon`, via `ConfigRegistry` /
  `CollectorService.pathFor`), while a pipeline job keys an **authored-pipeline id** (`*_flow.toon`, via
  `PipelineStore`) — disjoint stores, no uniqueness rule between them (`PipelineStore`'s class note states
  the split). Sharing one lease would never have produced the intended exclusion; it would only ever fire
  on an *accidental* name collision between two unrelated units of work. Hence `SCOPE_JOB` and
  `SCOPE_AUTHORED` as their own scopes, pinned by `DbRunLeaseTest.theJobAndAuthoredScopesAreDisjoint`.

  ⚠ **The claim is held for the WHOLE run, not just the submit.** `if (lease.tryAcquire(job)) submit` —
  this bullet's original wording — is *not* sufficient: releasing at submit time lets the next pod claim
  and submit the same firing, which is the double run the slice exists to stop.

  ⚠ **The seam is `com.gamma.job.RunClaims`, not `RunLease`.** `RunLease` lives in **inspecto**, and
  **inspecto depends on inspecto-engine**, so `JobService` cannot name it. `RunClaims` is the narrow
  engine-side view; `CollectorService.claimsOver` adapts the lease onto it at wiring time. ⛔ Do not
  "simplify" by moving `RunLease` down into the engine — it is bound to a `SpaceRoot` and opens
  operational-DB families, neither of which the engine knows about.

  ⚠ **Heap-backed by default** like every other lease, so Personal and single-node Standard *do* get the
  authored-pipeline fix (two differently-named jobs on one pipeline stop overlapping) without a DB. The arming
  claim is a no-op on one node, where the `LockingRunner` already covers it.

  ⚠ **`authoredPipelineKey()` is shared with the deletion fence** (`trackPipelineStart` calls it rather than
  recomputing the id). ⛔ They must not drift: if the fence and the claim ever keyed differently a job
  would be excluded from one and not the other. Mutating `authoredPipelineKey` to return the job name fails the
  pre-existing `flowJobRunsEndToEndAndIsTrackedWhileRunning` as well as the two new authored-pipeline tests — that
  shared failure *is* the proof the two key off one value.

  ⚠ **All four mutations were verified 2026-09-12**, each discriminating: disabling the arming gate fails
  exactly `aFiringIsTurnedAwayWhileAnotherNodeHoldsTheJobsArmingClaim` + `twoPodsSharingOneLeaseRunAJobOnceBetweenThem`;
  disabling the authored-pipeline gate fails exactly `aPipelineJobIsTurnedAwayWhileItsAuthoredPipelineIsClaimed`; keying
  `authoredPipelineKey` on the job name fails the two authored-pipeline tests plus the fence test; collapsing `SCOPE_JOB`/`SCOPE_AUTHORED`
  onto `"run"` fails `theJobAndAuthoredScopesAreDisjoint`. ⛔ Do not "simplify" any of them away.
- **`TriggerCoalescer`** stays local: coalescing is per-pod, the lease is what makes that safe.

**Why this ships value with zero pods:** this *is* T4. The signed RPO/RTO table promises a T4
active/passive standby with a 30-minute RTO and a promote runbook (`editions.md` §3.14). With a lease,
the standby is simply a second pod that never wins the lease until the first stops heart-beating —
the promote runbook's "stop A → start B" becomes automatic.

### 5.3 Work distribution — Spaces to pods

**Invariant:** *Every Space has exactly one owning pod at any moment; no pod polls an inbox it does
not own.*

> ✅ **§5.3 CLOSED 2026-09-12.** C1 (static Space→pod assignment) and C2 (shared-inbox detection) shipped;
> one item remains open only as a filed row, `INBOX-REGISTRY-CROSS-POD-1`.
>
> 🔴 **THREE of this section's five bullets were STALE — already shipped before the section was read.**
> `IntakeGovernor`'s Space key (`SPACES-GOVERNOR-1`, 2026-09-10), per-tenant ABAC (SEC-06, **2026-07-24** —
> seven weeks before the plan bullet was written), and "partition by Space" which was always a statement of
> direction rather than work. ⚠ **A plan section can be staler than the code it plans**, and §12 of *this
> same document* already recorded one of them as closed. ⛔ Ground every bullet against the code before
> building it — see PROJECT_NOTES §4.

Work:
- **Partition by Space, not by pipeline** (D3). Spaces are already the tenant boundary and the
  namespace for every store and directory (§3.8); pipelines within a Space share inboxes, ledgers and
  dedup state. Splitting a Space across pods would re-open every §3.7 race.
- ✅ **SHIPPED 2026-09-12 (slice C1) — static assignment.** `partition.toon` beside the spaces root maps
  Space id → pod ordinal; `SpaceManager.discover` gates each discovered directory through
  `SpacePartition` before booting it. Dynamic rebalancing (a pod dies, its Spaces migrate) remains
  **phase C+1**, explicitly deferred — it needs the lease's heartbeat and a controller.

  ⛔ **Absent the file, nothing changes** — `hostsEverything()`, every discovered Space boots. That
  default is load-bearing: Personal and single-node Standard must never need this file, and
  `withNoPartitionFileEverySpaceIsHosted` is what fails if it is ever lost.

  🔴 **This file deliberately does NOT follow the fail-soft idiom every other global TOON file uses.**
  `SchedulerSettings.read` (and `branding.toon`, `roles.toon`, …) swallow a malformed file and fall back
  to defaults, because for them the fallback is harmless. Here the fallback would be **"host everything"**
  — every pod hosting every Space, precisely the invariant this file exists to hold — and it would surface
  as *duplicate processing*, not as a config error. So present-but-unreadable, no `spaces:` section, a
  non-integer ordinal, or an **empty** section are all boot failures. ⛔ Do not "make it robust" by
  catching and defaulting; mutation-verified 2026-09-12 — wrapping the load in catch-and-default fails
  exactly `aMalformedMapRefusesToBootRatherThanHostingEverything` and
  `anEmptyMapIsRefusedRatherThanTreatedAsNoMap`.

  ⚠ **Three failure modes, deliberately different, because their blast radii differ:**
  | Case | Behaviour | Why |
  |---|---|---|
  | Map present, **pod ordinal unknown** | **fail boot** | hosting nothing idles the pod silently; hosting everything double-hosts. Same call as A1's partitioned-mode boot failure |
  | Map present, **local Space unassigned** | **skip it, WARN** | ⚠ *not* fatal — zero owners stalls one Space, a boot failure takes down every other Space on the pod, so a ConfigMap lagging a new directory would turn a small mistake into a pod-wide outage. The dangerous violation is **two** owners, never zero |
  | Map names a Space **not present here** | ignore, silent | normal with per-pod volumes — it lives on its owner's volume |

  ⚠ **The partition key is the Space DIRECTORY NAME**, verified not assumed: `DirSpaceRoot.id()` is
  `base.getFileName().toString()` (`SpaceRoot.java:190`), so it equals `SpaceContext.id()`. It has to be
  the directory name regardless — the context id is not known until the Space loads, and whether to load
  it is what the gate is deciding.

  ⚠ **Pod identity did not exist and is introduced minimally here**: `-Dinspecto.pod.ordinal`, else the
  trailing `-N` of `HOSTNAME` (the StatefulSet convention), so an ordinary deployment needs no extra flag.
  ⛔ This is **not** the general "instance id" §10 estimates at 12+ classes / 60–90 sites — that one exists
  to give per-process registries an instance dimension. 🔴 **Static partitioning removes the need for it
  there**: with each Space owned by exactly one pod, the Space-keyed statics (`CircuitBreaker`,
  `GapTracker`, `IntakeGovernor`'s caps) are already disjoint across pods. ⚠ It does **not** rescue
  genuinely process-scoped state — see the `INTAKE-POLICY-SYSTEM-SCOPE-1` row above.

  ✅ **Gating at discovery is sufficient for the read paths — SWEEP COMPLETE 2026-09-12.** Exactly **four**
  sites iterate every hosted Space: `SpaceRoutes.java:45` (`GET /spaces`), `BootstrapRoutes.java:108`,
  `SchedulerRoutes.java:85` and `:256`. All read `SpaceManager.all()`, which only ever holds what this pod
  booted, so all agree with the partition automatically — no code change needed. (The owed sweep from C1
  is hereby discharged.)

  ⚠ **But the sweep found a user-visible consequence, filed as `SPACES-LIST-PER-POD-1`:** the space list a
  UI receives now **depends on which pod served the request**, and nothing in the response says so — an
  operator sees a partial estate and cannot tell that it is partial. 🔴 Third instance of one pattern
  today, beside `INTAKE-POLICY-SYSTEM-SCOPE-1` and `INBOX-REGISTRY-CROSS-POD-1`: **a read or write that is
  implicitly global on one node silently becomes per-pod on N.** ⛔ These are worth deciding as one
  question, not three rows.

  ⚠ **`partition.toon` bypasses `ConfigSafetyValidator`**, like every other global settings file — that
  validator only covers path-bearing `pipeline`/`enrichment` configs. Its own parse is the fail-closed
  gate here instead.
- 🟡 **PARTIALLY SHIPPED 2026-09-12 (slice C2) — inbox ownership: the DETECTABLE half.** `dirs.poll`
  should live on the owning pod's volume (a per-pod PVC) or an object-store prefix only that pod polls.
  ⛔ No shared inbox (§3.7). `SpaceManager.discover` now ends with `auditInboxOwnership()`, which WARNS
  when two hosted Spaces declare the same normalised `dirs.poll`.

  🔴 **Grounded 2026-09-12: there is NO pre-poll claim, so a shared inbox double-ingests silently.**
  `MarkerManager` writes its marker only *after* a batch commits, i.e. a file is claimed retroactively,
  never before it is read. Two pollers on one directory both see the same un-marked files as pending and
  both ingest them. ⛔ Do not assume the dedup ledger or the marker rescues this — neither runs early
  enough. That is why the bullet exists at all, and why a *detector* is worth shipping ahead of a fix.

  🔴 **And nothing constrains `dirs.poll` to its Space.** It is a free-form path
  (`PipelineConfigParser`: `require(dirs, "poll")`), jailed only to the JVM-wide allowed roots by
  `PathJail` — never to the declaring Space. The per-Space default comes from the bundle/settings routes
  and is **convention, not a guard**. `PipelineDataDirs.conflictsFor` looks like the check but fires only
  at pipeline *deletion*, within one write root.

  ⛔ **This does NOT enforce the invariant, and must not be described as doing so.** The audit compares
  Spaces booted in *this process*. Once Spaces are partitioned (C1), two Spaces on **different pods**
  sharing a directory is precisely the dangerous case and is **invisible** — no pod can see the other's
  config. C2 catches the single-node and same-pod cases, which are real but the lesser half.
  → the remaining half is filed as `INBOX-REGISTRY-CROSS-POD-1`.

  ⚠ **WARNS, never refuses** — same blast-radius reasoning as C1's unassigned-Space case: a config smell
  that may predate the check must not take down every Space on the pod.

  ⚠ **Within one Space, two pipelines MAY share an inbox** and that is deliberately not a finding: one
  `CollectorService` on one pod polls them, which is what makes it safe. Mutation-verified — flagging
  per-declaration instead of per-Space fails exactly `twoPipelinesInTheSameSpaceMayShareAnInbox`, and
  dropping path normalisation fails exactly `theComparisonNormalisesEquivalentPaths` (a `./` or `..`
  spelling is how this gets authored in practice).
- ✅ **`IntakeGovernor`'s Space key — ALREADY FIXED, this bullet was STALE** (`SPACES-GOVERNOR-1`,
  2026-09-10; §12 of this same document records it closed). Re-grounded 2026-09-12: the `caps` and
  `overrides` maps key on `EventLog.currentSpaceId() + '/' + pipelineId`
  (`IntakeGovernor.java:166-168`), pinned by three tests — `aSaturatedSpaceDoesNotThrottleAnotherSpaces
  SameNamedPipeline`, `aPerPipelineOverrideBelongsToItsSpaceOnly`, `forgetDropsOnlyTheCallingSpacesState`
  (`IntakeGovernorTest.java:33,46,55`). Its sibling `SPACE-UNKEYED-STATICS-1` is closed too. **The
  "two Spaces may share a pod safely" precondition is therefore MET** — nothing to build here.

  ⚠ **It does NOT follow `RunLease`'s bind-at-construction idiom, and could not.** The governor is a
  process-wide singleton created at class-init before any Space exists (`IntakeGovernor.java:91`), so it
  reads the *ambient* Space from the MDC per call instead — the same idiom as `EventLog.current()`. ⛔ Do
  not "unify" these two: both are established, and which one applies is decided by whether the object's
  lifetime can start after the Space is known.

- 🔴 **NEW, found while re-grounding the bullet above (2026-09-12): `IntakeGovernor.policy` is
  deliberately process-wide, and that is a REAL gap in this model.** The fleet-wide `Policy` is
  intentionally *not* Space-keyed because `PUT /system/scheduler` is system scope
  (`IntakeGovernor.java:21`) — a correct single-node decision. On N pods "system scope" silently becomes
  **per-pod** scope: an operator setting the intake policy on the pod that happens to serve their request
  changes admission on that pod alone, while every other pod keeps its boot-time policy, and nothing
  reports the divergence. ⚠ This is the same class of defect as B2's cadence (per-process state that
  reads as global) but it is **configuration**, not run state, so the lease is the wrong home for it.
  ⛔ Do not fold it into §5.2. It needs its own decision: either a shared config row, or route
  system-scope writes through a single owner. **Not built; filed for the board.**
- ✅ **Per-tenant ABAC — ALREADY SHIPPED, this bullet was STALE** (SEC-06, 2026-07-24; `EDITIONS.md:111`).
  Re-grounded 2026-09-12: `PolicyEngine` seeds `space-isolation` and `space-isolation-rows`
  (`PolicyEngine.java:60-66`), enforced **per request** at both PEPs — route level
  (`ControlApi.authorize:809-818`, DENY→403) and row level (`RowScope.visible:30-42`, DENY→404/filtered).
  Pinned by `PolicyEngineTest.java:150-154,208-236` and `ControlApiPolicyEnforcementTest`.

  ⚠ **"Identically on whichever pod" is already true, for a reason worth stating**: grants live in the
  per-space `roles.toon` / `access-policies.toon` under the Space's config root and are re-read per request
  (mtime-checked) — only the authored *document* is cached, never a decision. So there is no per-process
  grant state to converge. ⛔ Do not add a cross-pod grant store; there is nothing per-pod to share.

  ⚠ **The residual is wiring, not engineering**: the seed policies engage only once a `space` claim is
  mapped onto `Subject.attributes()` — unmapped means no isolation, deliberately ("never a bricked API").
  🔴 That is **not** blocked on an IdP: ABAC needs a `space` claim from *some* `Authenticator`, not OIDC
  specifically. Personal ships auth-free by design, so ABAC is inert there — an edition boundary, not a gap.

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

### 5.5 Request distribution — the UI and the API *(added 2026-09-11)*

**Invariant:** *What an operator sees must not depend on which pod answered.*

⚠ §5.3 distributes **work** and says nothing about distributing **requests**. Grounded 2026-09-11:

- ✅ **The routing key is already in the URL.** `spaceScopedUrl` rewrites every non-global call to
  `/api/v1/spaces/<id>/…`, so an ingress can dispatch on the path with **zero UI change**.
- ✅ **A misrouted API call fails cleanly.** `ControlApi`'s static-SPA fallback applies only to an
  extensionless GET matching *no* API route; "API paths that match a route keep returning JSON (incl.
  JSON 404s)". Space-scoped routes exist on every pod, so a misroute is a JSON error, never HTML.
- 🔴 **`GET /spaces` is server-global but its answer is pod-local** — it comes from
  `SpaceManager.discover` scanning the local `-Dspaces.root`. With per-pod PVCs each pod sees only its
  own slice, so behind a round-robin Service **the space switcher shows a different subset on every page
  load**. This is the first screen an operator sees. Filed in §12.

Which pod serves what:

| Request | Pod | Why |
|---|---|---|
| SPA shell (`index.html`, hashed assets) | any | `-Dui.dir=./ui`; identical bundle on every pod |
| `/bootstrap` `/spaces` `/auth` `/health` `/metrics` | any | `SERVER_GLOBAL` — never space-scoped |
| `/api/v1/spaces/<id>/…` | **the owner** | ingress path-routes on `<id>` |

Work:
- **Answer `/spaces` from the partition map, not a disk scan.** The map declares every Space and its
  owner, so this needs no fan-out — and it is the same change that gives the map a coverage check no pod
  can perform locally (§5.3).
- **Route only what must be routed.** After phase A most reads come from shared Postgres and any pod can
  serve them. The owner is required only for: config reads/writes (the write root is on its PVC), run and
  trigger calls, anything touching the Space's filesystem, and — **SSE streams, which D6 does NOT fix**
  (corrected 2026-09-12: the original text said "until D6"; D6 shipped and the stream is unchanged) — since
  `EventLog` is a per-process, per-Space static registry (§3.3), so a stream served by a non-owner is empty.
- **Ingress path-routing, with the rules GENERATED from the partition map.** ⛔ Never hand-maintain them:
  that makes the ingress a second copy of the map and the two drift. An in-app forward was considered and
  is the weaker option — it costs new code and would have to proxy long-lived SSE streams, which an
  ingress already does well. ⛔ Client-side routing is refused outright: it leaks topology to the browser
  and needs per-pod hostnames plus CORS.
- **Serve the SPA from the ingress or a CDN, not from the pods** (D16). 🔴 Angular emits hashed chunk
  filenames, so during a rolling update a browser that fetches `index.html` from a new pod and a chunk
  from an old one gets a 404 — intermittently, because round-robin decides each asset independently. This
  appears the first time a multi-pod deployment is **rolled**, with or without partitioning. Moving the
  assets off the pods removes the skew *and* makes "which pod serves the UI" a non-question;
  `-Dui.dir` stays exactly as-is for the single-node Personal/Standard deployment it was built for.
- ⚠ **Verify `/auth/refresh` is stateless** before any multi-pod deployment. The BFF keeps the refresh
  token in an httpOnly cookie; if it is validated from a shared signing key, round-robin is fine, but any
  server-side session state breaks the moment a refresh lands on another pod. **Unverified** — §12.

---

## 6. Sequencing — three M's, not one XL

`ROADMAP.md` L1 is sized XL as one project. Sequenced as below, each phase is an **M**, ships alone,
and is worth having if the next phase never happens. Stop between any two.

⚠ **This sequencing is UNCHANGED by the 2026-09-11 amendment and does not yet incorporate it.** §4.1's
units `U1`–`U4` and §5.5's routing work hang on **D14–D16, which are unsigned**. If D14 is signed, the
natural revision is to pull `U1` (distributed remote acquisition) into phase A — it needs only the claim
surfaces phase A already moves to Postgres, and it is the cheapest horizontal scale in the system — and to
place `U2` in phase B beside the lease. ⛔ Do not re-sequence before the decisions are signed; ⚠ and note
the phases here are A/B/C while the units are `U1`–`U4` — they are different axes.

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
| **D6** | Events across pods | ✅ **SIGNED 2026-09-10 (operator)** — **Add `events.backend=db`** on the existing `EventStore` seam. ✅ **BUILT 2026-09-12 (phase A3)**: `DbEventStore` + `OperationalDb.Family.EVENTS` + the `db` branch in `ServiceStores.openEventStore`, covered by `DbEventStoreTest` (10, over DuckDB so it runs everywhere) and a Postgres round-trip in `PostgresStateStoreTest`. 🔴 **It closes the QUERY half only** — see §5.5's corrected note: `/signals/stream` and `EventObjectBridge` both read `EventLog`'s **in-heap** subscriber list and never consult a store, so a `SEQUENCE_GAP` on pod B still never becomes an ALERT if the bridge runs on pod A. ⛔ Do not record D6 as making the live tail cross-pod |
| **D7** | Connection pool | ✅ **SIGNED 2026-09-10 (operator)** — **Un-park `postgres-multi-user-plan.md` P1 + P2** as phase A work (spike S3 first) |
| **D8** | Tier naming | ✅ **DECIDED 2026-09-10 (operator):** **T5 — partitioned scale-out on Kubernetes** = **Enterprise**, added to §3.9; **T4 active/passive DR moves to Standard**. Applied |
| **D9** | The "zero external runtime services" claim | ✅ **SIGNED 2026-09-10 (operator)** — refined by D8: **Personal — zero. Standard — zero unless DR (T4) is enabled, then Postgres. Enterprise — Postgres + S3-compatible object store.** The 90 MB artifact claim stays true: same artifact, plus YOUR services |
| **D10** | `DuckLakeRegistrar` failure in partitioned mode | ✅ **SIGNED 2026-09-10 (operator)** — **Fatal.** A pod that cannot reach the shared catalog must not write files nobody can see; single-node mode keeps today's opt-in, warn-only behaviour |
| **D11** | Dynamic rebalancing | ✅ **SIGNED 2026-09-10 (operator)** — **Deferred to phase C+1**; static Space→pod assignment first |
| **D12** | The partitioned-mode switch's name | ✅ **SIGNED 2026-09-10 (operator)** — **`-Dinspecto.topology=partitioned`** (values `single` \| `partitioned`; also what `/bootstrap` reports). Not `mode=cluster` — D2 refused the cluster engine, and Standard's two-pod T4 standby is partitioned without being a cluster |
| **D13** | *(new, operator 2026-09-10)* An external SQL/BI query surface: Postgres views over the Hive-partitioned Parquet, executed by Postgres's DuckDB extension (pg_duckdb-style) | ✅ **SIGNED 2026-09-10 (operator)** — **Added as the external query surface; DuckLake catalog commit stays the write-visibility event.** A Hive glob sees a half-written file the moment it appears, so visibility must remain the commit, not file existence. Spike **S5** first: is `pg_duckdb` installable on the customer's Postgres (managed services such as RDS do not allow it)? → §5.4. 🔴 **S5 ANSWERED 2026-09-10 — SELF-MANAGED POSTGRES ONLY.** `pg_duckdb` is installed by **building from source** (`make install`), and it appears on **none** of the curated extension lists of Amazon RDS/Aurora, Google Cloud SQL or Azure Database for PostgreSQL Flexible Server — all three publish a fixed set, so a customer cannot add one that is not on it. ⚠ **Evidence strength, stated so it can be re-checked:** Azure's list was read in full from the primary source (Microsoft Learn, *List of Extensions and Modules by Name*, dated 2026-07-10) and contains **no** extension whose name contains "duck"; RDS/Aurora and Cloud SQL rest on their published lists as surfaced by search rather than a full read. ⇒ Treat Azure as settled and the other two as very likely; **the live half of S5 is what confirms all three.** It supports Postgres 14–18 and reads Parquet/CSV/JSON/Iceberg/Delta from S3, GCS, Azure and R2. ⇒ **The external query surface is NOT general.** It is available to a self-managed Postgres and unavailable to the managed services an Enterprise customer is most likely to already run — so D13 must be sold as an option with a deployment precondition, never as a default. 🔴 **And it needs one more thing to be correct at all:** DuckLake **inlines** small writes into the catalog (S1), so a view over the Hive Parquet prefix would silently omit them unless inlining is disabled — see §3.5. ⚠ The live half of S5 (install it, build the view, query it from `psql`) is still owed; this sandbox has no Postgres and no container daemon. |
| **D14** | *(new, 2026-09-11 — operator challenge to D3)* **Relax D3**: is the indivisible unit the **inbox-sharing group** (connected components of "names the same `dirs.poll`") rather than the Space, with the Space kept as the default grouping? | ⬜ **UNSIGNED — asked.** Recommend **yes.** Under D3 as written a single-Space customer gets **nothing** from N pods, and that is the likely tier-1 telecom shape, not an edge case (§4.1). Two of D3's three justifications do not survive grounding: ledgers and dedup move to shared Postgres **in phase A itself**, and the shared inbox is a per-Pipeline *config fact*, decidable at boot — not a property of a Space. ⛔ Preconditions if signed: phase A (stores on Postgres) **and** D6 (`events.backend=db`), because splitting a Space without D6 splits that Space's Signal ledger across two pods' memory and a Space is precisely the unit Ops looks at. Plus a boot check that **refuses** a map splitting a shared-inbox group |
| **D15** | *(new, 2026-09-11)* Split-brain posture: **idempotent writes + fencing tokens** over the existing Postgres claim surfaces — and **refuse ZooKeeper/etcd**? 🔴 **RE-POSED 2026-09-11 (same day) — its premise was measured and REFUTED; see §12.** The phrase "over the **existing** claim surfaces" is what failed: `DbFileStageStore` and `DbConsignmentOutputStore` have **no unique constraint and no CAS**, and the `batchId` every write keys on is **wall-clock derived at second granularity**, so two executors cannot agree on it. ⇒ **The question is no longer "which posture?" but "will you fund the precondition?":** a **deterministic, content-derived Consignment identity** plus **unique constraints on the registry and a CAS on the stage store**. Until those exist, neither idempotent writes nor fencing tokens are implementable — a fencing token is validated *at the resource*, and the resource key is the same unstable `batchId`. ⚠ **Standard is affected, not only Enterprise**: a T4 standby taking over from a *paused* owner double-executes. ⛔ Do not sign the recommendation below as written — it describes a destination reachable only after that precondition. | ⬜ **UNSIGNED — asked, and now re-posed (see the question cell).** The ZK half stands unchanged and can be signed on its own. The idempotency half is superseded by the precondition above. Original recommendation, kept for provenance: **yes, and refuse ZK.** ⚠ A TTL lease does not *prevent* split-brain, it makes it unlikely — and a ZK session expiring in a GC pause has the identical hole, so changing coordinator fixes nothing. Lead with **idempotent writes keyed on Consignment/file id** (`DbDedupLedger` + `FileStages` already exist) so double execution is *wasted effort, not corruption*; add **fencing tokens** validated at the resource for the paused-owner case. ZK refused: Postgres is already mandatory (D9) and already linearizable in the critical path; ZK is a **third** stateful system and **two coordinators is a new split-brain surface**; D5 already refused advisory locks and the Kubernetes Lease API, both lighter than ZK (§4.2) |
| **D16** | *(new, 2026-09-11)* Request routing: **ingress path-routing on `/spaces/<id>/` with rules generated from the partition map**, `/spaces` answered **from the map**, and the **SPA served off the pods** (ingress/CDN)? | ⬜ **UNSIGNED — asked.** Recommend **yes, all three.** The routing key is already in the URL (`spaceScopedUrl`), so this needs **zero UI change**. ⛔ Generate the ingress rules from the map — hand-maintaining them makes the ingress a second copy that drifts. An in-app forward is the weaker option (new code, and it would have to proxy SSE); client-side routing is refused (leaks topology to the browser). Serving the SPA off the pods also removes the rolling-update chunk-skew defect (§12) and keeps `-Dui.dir` unchanged for single-node (§5.5) |

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

*(2026-09-11, later — found while answering "how do we resolve D15?", by measuring the premise D15 rests on
rather than signing it. ⛔ **D15's premise is REFUTED; see its row in §9, which is re-posed rather than
withdrawn.**)*

- 🔴 **The data plane is NOT idempotent on re-execution of the same Consignment, and the identity everything
  keys on cannot be agreed by two processes.** D15 argues that idempotent writes are the cheap answer
  *"because `DbDedupLedger` and `FileStages` already exist"*. Measured, one of those two is what the row
  claims and the other is not:
  - ✅ **`DbDedupLedger` is genuinely idempotent** — `PRIMARY KEY (pipeline, key_hash, window_start)`
    (`DbDedupLedger.java:78`) with `INSERT … ON CONFLICT DO NOTHING` (`:131`). The database arbitrates; a
    concurrent claim of the same key converges. This half of the premise holds.
  - ❌ **`DbFileStageStore` is an insert-only audit log, not a compare-and-swap** — `CREATE TABLE IF NOT
    EXISTS` with **no primary key and no unique constraint** (`:59`), written by a bare `INSERT INTO …
    VALUES (?,?,?,?,?)` (`:75`). There is no `UPDATE … WHERE stage = :expected` anywhere in it. It cannot
    arbitrate anything, and its own class doc says so. ⛔ **D15 names it as a surface that makes idempotency
    cheap. It is not one.**
  - ❌ **`DbConsignmentOutputStore` likewise** — `CREATE TABLE IF NOT EXISTS` (`:81`) and a bare `INSERT
    INTO` (`:123`), no unique constraint, no `ON CONFLICT`. Two executors both append.
  - 🔴 **The root cause is the Consignment identity itself.** `batchId` is
    `String.format("%s_%s_%04d", ts, slug, seq)` (`ConsignmentPlanner.java:115`) where `ts` comes from
    `PipelineConfig.forNewRun()` — `LocalDateTime.now()` at **second** granularity
    (`PipelineConfig.java:1472-1475`). `slug` and `seq` are deterministic from the input files; **only the
    wall clock differs.** So two executors of the same work land in one of two regimes:
    **same second** ⇒ identical `batchId` ⇒ the manifest (`<batchId>.json`) clobbers, last writer wins;
    **one second apart** ⇒ two manifests, two registry rows, and — because `consolidatedBaseName` names a
    multi-member batch by `batch.batchId()` — **two differently-named output files that are BOTH visible**,
    i.e. duplicate rows on read. ⚠ The failure mode therefore **flips between clobber and duplication on
    clock alignment**, and `LocalDateTime.now()` is host-zone, so skewed nodes diverge routinely.
    Non-deterministic corruption is worse than either outcome on its own.
  - ⚠ **Nothing lets a reader clean up afterwards**: ordinary partitioned output rows carry no per-row
    `__batch_id`/`__consignment_id` (only versioned reference stores stamp one), so a duplicate-detecting
    reader has no per-row key to fall back on — consistent with the standing note that the registry, not
    the row, is the source. And a versioned reference store writes `…__v_<batchId>` *by design*, so two
    executions always produce two versions of identical data.
  - ⚠ **Not re-verified in this pass** (reported by the grounding sweep, worth confirming before it is
    built on): `DbAcquisitionLedger`'s dedup gate is a `find()` at poll time separated from its `record()`
    by the entire ingest run — a check-then-write window as wide as a pipeline execution.

  ⇒ **Consequence for D15, and it is the whole answer:** "idempotent writes keyed on Consignment/file id"
  is **not implementable today, because there is no stable Consignment id to key on.** Fencing tokens do
  not rescue it either — a fencing token is validated *at the resource*, and the resource key is the same
  unstable `batchId`. Resolving D15 therefore requires building **(1) a deterministic, content-derived
  Consignment identity** to replace the wall-clock `batchId`, and **(2) unique constraints on the output
  registry plus a CAS on the stage store**, before the question it poses can even be answered.
  ⚠ **This bites Standard, not only Enterprise:** a T4 standby taking over from a *paused* — not dead —
  owner double-executes, and the lease alone only narrows the window.

- ⚠ **The only concurrency test that exists switches off the leg where the corruption lives.**
  `FinalizeSourceConcurrencyTest` pins the marker race, but passes **empty outputs and lineage**
  (`:291-294`, `finalizeSource(cfg, survivors, List.of(), List.of())`, commented *"the registry leg is
  deliberately out of play"*). So the populated-outputs case — the one D15 actually asks about — is
  **untested, not proven safe**. ⛔ Do not read that test as evidence of concurrent safety; it is the
  guard-scope-is-a-silent-exemption pattern again.

*(2026-09-11 — found while answering the operator's challenge to D3 and the routing question:)*

- 🔴 **`GET /spaces` returns a POD-LOCAL answer while being server-global** (§5.5). The listing comes from
  `SpaceManager.discover` scanning the local `-Dspaces.root`; with per-pod PVCs each pod sees only its own
  slice, and `/spaces` is in the UI's `SERVER_GLOBAL` set so it is never routed. Behind a round-robin
  Service **the space switcher shows a different subset of Spaces on every page load** — the first screen
  an operator sees. ⚠ Independent of which unit §4.1 partitions on; it breaks the moment there is a second
  pod. Fix is the one the map needs anyway: **answer it from the partition map**, which also supplies the
  coverage check no pod can perform locally.
- 🔴 **The SPA bundle skews during a rolling update.** Angular emits hashed chunk filenames, so a browser
  that fetches `index.html` from a new pod and a chunk from an old one gets a 404 — intermittently, since
  round-robin decides each asset request independently. ⚠ **Not a partitioning defect**: it appears the
  first time ANY multi-pod deployment is rolled. → D16 (serve the SPA off the pods).
- ⚠ **`/auth/refresh`'s statelessness is UNVERIFIED.** The BFF keeps the refresh token in an httpOnly
  cookie; if it is validated from a shared signing key a round-robin fleet is fine, but any server-side
  session state breaks the moment a refresh lands on a different pod. ⛔ Check before **any** multi-pod
  deployment — this is not gated on partitioning either.
- ⚠ **Ordering under distributed Consignment execution is undecided** (§4.1 `U2`). `ConcurrencyBroker`
  deliberately preserves FIFO **per Pipeline**; distributing execution breaks that. What actually depends
  on it — sequence-gap detection, dedup semantics, late partitions — is **not established**, and is the
  first thing to ground before `U2` is built.
- ⚠ **`StabilityGate.SHARED` and `AcquisitionLedgers`' transient maps are in-heap and per-Space-keyed**
  (§3.7) — "safe in one process, disagreeing across pods". Whether they are genuinely *inbox*-scoped in
  practice (and therefore disjoint when inboxes are) is **not verified**, and it gates §4.1 `U1` and `U3`.

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
