# HA/DR drill runbook: two-node active/passive on plain Linux (T4)

> **Status: runbook; a LAPTOP-SCALE local drill was run 2026-10-10 (two server processes on one Windows
> machine, one PostgreSQL) — see [Drill results 2026-10-10 (laptop-scale)](#drill-results-2026-10-10-laptop-scale).
> The two-host drill on plain Linux is still NOT run, and no number in this file is a two-node RTO or RPO.**
> The signed targets are in
> [`okf/capabilities/editions/editions.md`](../okf/capabilities/editions/editions.md) §3.14 (T4: RPO ≤ 5–15 min
> async, RTO ≤ 30 min promote, quarterly drill, acceptance row **VER-12**). Record the first real drill in
> the table at the end, and in `compliance/evidence/rto-rpo-statement.md`, which the operator fills in.
> (`ASSURE-OPERABILITY-1`, 2026-09-29; local drill 2026-10-10.)

## What has actually been proven (2026-09-29)

- `PostgresStateStoreTest` — **11 / 11 passed against a real PostgreSQL 18.6** (a local `postgres:latest`
  container on `127.0.0.1:55432`, started for the run and stopped afterwards). That includes
  `runLease_exclusionAndFencingRoundTrip`: a second owner cannot take a held lease, and a stale epoch cannot
  act after a takeover. Command:

  ```
  MAVEN_OPTS="-Duser.timezone=Asia/Kolkata" \
  INSPECTO_TEST_PG_URL='jdbc:postgresql://localhost:55432/postgres?user=postgres&password=postgres' \
  mvn -o -B test -Pedition-enterprise -pl inspecto -am \
      -Dtest=PostgresStateStoreTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=0
  ```

  CI runs the same class against a `postgres:16` service container (`.github/workflows/ci.yml`, `test` job).
- ✅ Proven 2026-10-10 (laptop-scale, below): a lease handover between two PROCESSES on one machine, with timing.
- ⛔ Still not proven: a lease handover between two HOSTS, promotion of a Postgres standby, a `spaces/` sync
  restore, a network partition (D4), or any timing over a real network. Those remain the drill.

## Topology

```
          LB / DNS (health check: GET /ready)
             |                        :
      node A (active)          node B (passive, service stopped)
      inspecto.service         inspecto.service (disabled until promote)
      spaces/ tree  -- rsync after CHECKPOINT -->  spaces/ tree
             |                        |
      Postgres primary  -- streaming replication (async) -->  Postgres standby
             \______ WAL archive (both read it) ______/
```

Shared state goes in Postgres; the DuckDB files and Parquet under `spaces/` are node-local and copied.
Select every state family that must survive a failover with its Postgres backend
(see [`okf/backend/engine/db-layer.md`](../okf/backend/engine/db-layer.md) for the full table), and the lease:

```
# inspecto.env on BOTH nodes (read by inspecto.service via EnvironmentFile=)
INSPECTO_JAVA_OPTS=-Drun.lease.backend=postgres -Drun.lease.db.url=jdbc:postgresql://pg-vip:5432/inspecto
  -Drun.lease.db.user=inspecto -Drun.lease.db.password=${secret} -Drun.lease.owner=node-a
  -Dobjects.backend=postgres -Devents.backend=postgres ...
```

`run.lease.owner` must differ per node (`node-a` / `node-b`); it is the row's owner.

## The lease: TTL and fencing (what the drill must observe)

- Table `inspecto_run_lease`, one row per (Space, scope, Pipeline). TTL is **60 s**
  (`DbRunLease.DEFAULT_TTL`, not configurable). The holder renews every **TTL/3 = 20 s**.
- A dead holder's Pipelines stay frozen for **up to 60 s**, then another owner may take them. So the
  lease-only part of RTO is at most ~60 s plus the next schedule tick.
- Every takeover bumps `epoch`; every write the lease makes is conditional on `owner = me AND epoch = mine`.
  A paused node that wakes after its TTL finds its epoch stale and loses. A run re-checks its claim
  before it commits a Consignment or lands fetched files, and a lost claim aborts the commit
  (`LEASE-TAKEOVER-INFLIGHT-1`). ⚠ The check is at the commit point, not on every write: Parquet
  outputs the lost run already wrote stay on disk until the new holder's run overwrites them.
- ⛔ Active/passive here means node B's **service is stopped**. The lease is the safety net if both run by
  mistake, not the failover mechanism.

## Drill scenarios

Before each: note the last committed row (`SELECT max(...)` on a known Dataset, and the Postgres
`pg_current_wal_lsn()` on the primary), start a steady ingest (drop files into a watched inbox at a known rate),
and start a clock.

| # | Fault | Steps | Pass when |
|---|---|---|---|
| D1 | Process kill | on A: `kill -9 $(systemctl show -p MainPID --value inspecto)` | systemd restarts it (`Restart=on-failure`, 5 s); `/ready` 200 again; no file processed twice (provenance) |
| D2 | Host reboot | on A: `systemctl reboot` | A comes back, service starts (`WantedBy=multi-user.target`), resumes the inbox |
| D3 | Node loss (promote B) | power off A. Then the T4 order: stop A if alive → `pg_ctl promote` (or `SELECT pg_promote()`) on the standby → restore the last `spaces/` sync on B → `systemctl start inspecto` on B → repoint LB/DNS → run the acceptance block | B serves `/ready` 200, Pipelines run under `owner=node-b` with a higher `epoch` |
| D4 | Network partition | on A: `iptables -A OUTPUT -d <pg-host> -j DROP` for > 60 s, then remove it | A logs `Could not renew the run lease`; B (if started) takes the lease after 60 s with a higher epoch; after heal A logs `was stolen while we still believed we held it`. A's `GET /health/details` shows `live.runLease.run` **DOWN** (with `since`) within about 20 s of the cut and UP within about 20 s of the heal — time both; `GET /ready` stays 200 by design (operator 2026-10-03), so it is NOT the RTO signal here. A run already in flight on A must **not** commit after the steal (`LEASE-TAKEOVER-INFLIGHT-1`, fixed 2026-09-30): expect `Run lease lost for '<pipeline>'` on A and its Consignment audited FAILED with the reason `lease lost: …` (not `commit failed`, and no COMMIT-retry attempt is spent), its files left in the inbox for B; check provenance shows each file committed once |
| D5 | Split brain guard | start B while A is healthy | B acquires nothing A holds (rows keep `owner=node-a`) |
| D6 | Fail back | reverse D3 once A is rebuilt as the new standby | same pass criteria, roles swapped |

## Measuring RTO and RPO

- **RTO** = time from the fault (the `kill`/power-off timestamp) to the first `200` from `GET /ready` via the
  LB **and** the first Pipeline run completing on the surviving node. Poll once a second:
  `while ! curl -fsS https://lb/ready >/dev/null; do sleep 1; done; date -u +%FT%TZ`.
  ⚠ `/ready` proves only that a process serves — it stays 200 through a lease-DB outage by design (operator
  2026-10-03). The failover is complete at the **first Pipeline run on the survivor**; for a partition (D4) time
  `live.runLease.run` on `GET /health/details` (DOWN with `since`, then UP), not `/ready`.
- **RPO** = work lost. Two parts, report both:
  1. Postgres: bytes/transactions not replicated at the fault —
     `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication;` sampled before
     the fault, and after promotion compare the last row time in the state tables with the clock.
  2. `spaces/`: files ingested on A after its last `rsync`. Count inbox files whose provenance row exists on
     A's last known state but whose output is absent on B. These must be re-ingested (the inbox is
     idempotent via the dedup ledger if it lives in Postgres).

## Drill results 2026-10-10 (laptop-scale)

> ⚠ **Laptop-scale. Not representative of a real two-node deployment.** Two server processes on ONE Windows 11
> machine, one local PostgreSQL 18.6 (a Docker container) as the shared lease store, loopback networking, an
> inbox on the same disk. Read these as "the lease mechanism behaves as documented, and its timing is dominated
> by the 60 s TTL" — not as an RTO or RPO for any customer topology. Operator-approved 2026-10-10
> (`ASSURE-OPERABILITY-1`, which stays open for the real drill).

### Method

- **Build:** the existing Enterprise **demo** bundle (`inspecto-demo`, Demo User sign-in, no IdP, jlinked JDK 27
  runtime) — NOT a Professional bundle, which needs a real IdP to boot. The run-lease code (`DbRunLease`,
  `CommitFence`) is the same in every edition; no edition-specific path was exercised.
- **Two nodes:** `node-a` (port 8201) and `node-b` (port 8202), each its own working directory, its own copy of
  one throw-away Space (`drill`, one CSV Pipeline `ev`, 4 columns) and its own DuckDB state (dedup ledger,
  status, consignment outputs). Both started with `-Drun.lease.backend=postgres`, the SAME
  `-Drun.lease.db.url` (database `drill`, Space schema `space_drill`), `-Drun.lease.owner=node-a|node-b` and
  `-Dservice.poll.seconds=5`. Ports 8096/8098/4391 and the other containers were not touched.
- **Load (the write path under test):** the ingest path this runbook names — files dropped into the watched
  inbox. A harness thread wrote 1 file/s of 100 rows (EV_ID unique per row) by temp-name then atomic rename; the
  rename is the **acknowledgement**. Before each mid-run failure a 1,500,000-row file was dropped too, so a run
  holds the lease for roughly 10-20 s and the failure lands mid-run.
- **Failure injection:** poll the lease row (`inspecto_run_lease`, every 25 ms) until one node has held the SAME
  epoch for at least 1.5 s, then `kill -9` it (Java `Process.destroyForcibly`, i.e. TerminateProcess; the process
  was gone 0-3 ms later) or stop it gracefully (a harness wrapper class calls `System.exit(0)`, the shutdown-hook
  path SIGTERM takes on Linux). The survivor was already running. After each sequence the victim was restarted.
  Two idle variants stop a node in a gap between poll cycles (lease free).
- **Measured from the kill instant T0:** lease takeover = lease row owned by the survivor at a higher epoch;
  first run = first file newly backed up on the survivor; big file done = the 1.5 M-row file completed on the
  survivor. `GET /api/v1/health/details` (`live.runLease.run`) was sampled on both live nodes every 2 s.
- **RPO:** after the survivor drained the inbox, every acknowledged EV_ID was looked up in the Parquet output of
  BOTH nodes (DuckDB `read_parquet`): lost = acknowledged minus distinct present; duplicate = rows present twice.
  Files left in the inbox, `errors/` or `quarantine/` were counted too (none at the end of any sequence).

### Results

| Sequence (n) | Lease taken by survivor (min / median / max) | First run on survivor (min / median / max) | Acknowledged rows lost | Duplicate rows |
|---|---|---|---|---|
| `kill -9` of the lease holder, mid-run (5) | 59.7 / 61.5 / 62.1 s | 61.2 / 63.3 / 64.8 s | 0 of 7.55 M | 100 (1 run of 5) |
| graceful stop of the holder, mid-run (3) | 60.2 / 60.5 / 60.9 s | 61.7 / 72.5 / 79.7 s | 0 of 4.53 M | 0 |
| graceful stop, idle (3) | 2.5 / 2.7 / 4.6 s | 0.9 / 3.0 / 3.0 s | 0 of 2.7 k | 0 |
| `kill -9`, idle (3) | 3.5 / 3.5 / 3.9 s | 3.8 / 3.9 / 4.6 s | 0 of 2.4 k | 0 |

Per-sequence detail for the five mid-run `kill -9` runs (lease taken / first run / 1.5 M-row file done, seconds):
61.6/63.3/79.1, 61.5/63.7/80.3, 62.1/64.8/86.3, 59.9/61.2/76.5, 59.7/61.3/109.4. The 109 s outlier is the
survivor re-ingesting the big file on a loaded laptop, not the lease. Graceful mid-run process exit took 10.4 s
each time (the Space close hit its 10 s limit — below). Steady state before the failures (60 s, both nodes up,
61 files, 6,100 rows): node-b won the lease for every cycle and processed all 61 files, node-a none; 0 lost, 0
duplicated; the lease row was held in about 47 % of the 50 ms samples. On both nodes `live.runLease.run` read `UP`
("lease database answered") throughout, because Postgres was never interrupted (D4, the partition case, was NOT
drilled). At the instant of each failure 2-8 files (about 200-800 rows) were still waiting in the inbox.

### What the numbers mean

- **RTO is the lease TTL.** A holder killed mid-run keeps its lease until `expires_at` (TTL 60 s from its last
  renew): takeover came at 59.7-62.1 s, i.e. TTL plus at most one 5 s poll tick, as the lease section above
  predicts. The survivor was serving `/health` the whole time (it was never down), so "time until a node
  serves" is about 0 here; the meaningful RTO is the time until the survivor can run the Pipeline.
- **A graceful stop does not help while a run is in flight.** The process exited after about 10.4 s (the Space
  close hit its 10 s limit: `Close of space 'drill' did not finish within 10000 ms; abandoning it`), but the lease
  row was never released, so the survivor still waited the full TTL (60.2-60.9 s). A graceful stop only gives a
  fast handover when the node is between cycles (2.5-4.6 s, bounded by the survivor's poll tick).
- **Acknowledged writes lost: 0 in 14 of 14 sequences.** Take that for what it is: the inbox here is SHARED by
  the two processes and Postgres was never failed, so neither of the runbook's two RPO terms (replication lag,
  `spaces/` not yet rsync'd) could occur. The commit fence did its job in the one place it could be tested: no
  file was lost when the holder died mid-batch, and the survivor re-planned the same inbox.
- **At-least-once, not exactly-once, across a kill.** In one of five mid-run kills 100 rows (one 100-row file)
  were present twice. Most likely mechanism (NOT root-caused): the dead node committed that file's output and was
  killed before moving the original out of the inbox; the survivor's dedup ledger is node-local, so it ingested
  the file again. D1's pass criterion "no file processed twice (provenance)" is therefore not guaranteed on
  `kill -9` while the dedup ledger is node-local DuckDB (a Postgres-backed ledger was not tried here).
- **No designed standby.** With two equal pollers the work was not shared evenly: node-b took every cycle in
  the steady-state minute. That looks like active/passive by luck of timing, not by design.

### Adaptation: what changed from the runbook, and what that changes

| Runbook assumes | This drill did | Effect on the result |
|---|---|---|
| Two Linux hosts, LB/DNS, systemd | Two processes on one Windows machine, started by script | No LB, no `Restart=on-failure`; D1's "systemd restarts it in 5 s" was not tested (the harness restarted the victim after measuring) |
| Node B's service stopped until promote | Both nodes running; the lease alone arbitrates each cycle | Measures lease takeover, not a cold start of B (a cold B adds its boot time, 10-30 s here) |
| Postgres primary + streaming standby, promote | One Postgres, never interrupted | Postgres RPO and promote time NOT measured; a Postgres outage (D4) NOT exercised |
| Node-local inbox, `spaces/` rsync | One inbox directory shared by both nodes | RPO term 2 (files since the last rsync) is not measurable; the 2-8 files pending at failure are its upper bound if the inbox were local and unsynced |
| Professional bundle behind a real IdP | Enterprise demo bundle, Demo User sign-in | Same lease code; no IdP failover tested |
| `-Dobjects.backend=postgres -Devents.backend=postgres ...` | Only the run lease on Postgres (`objects.backend=db`, `events.backend=parquet`) | Other state families stayed node-local DuckDB, which is what allowed the duplicate |
| Lease TTL 60 s | 60 s (unchanged); poll cadence lowered from 60 s to 5 s | Takeover = TTL + up to one 5 s tick; at the default 60 s cadence it could be up to about 120 s |

### Caveats

- n = 5 / 3 / 3 / 3: min / median / max, not percentiles. One laptop, one Postgres, loopback, other processes
  running; ingest times (not the 60 s lease times) vary with load (the 109 s outlier).
- Each mid-run failure landed 1.5-4 s after a fresh acquire, so lease expiry fell 56-58 s later; a kill late in a
  renew cycle would shift takeover by up to the 20 s renew period.
- The harness (not product code; kept outside the repo in `C:\sandbox\drill-ha`: `Drill.java`, `Wrap.java`,
  `start-node.ps1`, per-run trace) only ever killed processes it started itself.

### Findings (surprises worth fixing or documenting)

1. **`-Drun.lease.db.user` / `.password` do NOT authenticate the per-Space schema creation.** With
   `-Drun.lease.backend=postgres -Drun.lease.db.url=jdbc:postgresql://…/db` and `-Drun.lease.db.user/password` set
   (the `inspecto.env` example above), the Space was skipped at boot — `could not create PostgreSQL schema
   space_drill … The server requested SCRAM-based authentication, but no password was provided` — because
   `OperationalDb.ensureSpaceSchemas` reads the shared `-Dinspecto.db.user/password`, not the run-lease ones. The
   node still came up healthy with **0 Spaces** (`/health` 200). Workaround used: credentials in the URL (they
   then appear in the `GET /health/details` `store.runLease.*` detail). Needs a decision: honour the per-family
   credentials here, or fix the runbook example and make a node with no bootable Space not report ready.
2. **A graceful stop does not release a held lease** (above) — restarting the holder costs the full TTL whenever
   a cycle is in flight.
3. **At-least-once on `kill -9` with a node-local dedup ledger** (above).
4. `GET /health/details` shows `live.runLease.run` UP on the survivor while the dead holder's lease row is still
   held — nothing surfaces "lease held by an owner that has stopped renewing", so an operator watching only
   `/health/details` sees no takeover wait.

## Postgres backup and WAL runbook

1. **Base backup** (nightly, on the primary or a replica):
   `pg_basebackup -h pg-primary -U replicator -D /backup/base/$(date +%F) -Ft -z -Xs -P`.
2. **WAL archiving** (`postgresql.conf` on the primary):
   `wal_level = replica`, `archive_mode = on`,
   `archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/%f'`, `archive_timeout = 300`
   (bounds async RPO at 5 min even under low write volume). Put `/wal-archive` on a different host or
   filesystem.
3. **Streaming standby**: `pg_basebackup -h pg-primary -U replicator -D $PGDATA -R -Xs` on node B (writes
   `standby.signal` and `primary_conninfo`); add `restore_command = 'cp /wal-archive/%f %p'`.
4. **Point-in-time restore test** (half-yearly per T3): restore a base backup to a scratch host, set
   `recovery_target_time`, create `recovery.signal`, start, and check the state tables against the clock.
5. **`spaces/` tree**: run the `maintenance` job's DuckDB `CHECKPOINT` first, then
   `rsync -a --delete spaces/ node-b:/opt/inspecto/spaces/` on a schedule no longer than the RPO target.
   See [`backup-restore-runbook.md`](backup-restore-runbook.md) for the per-Space config backup.
6. Retain: base backups 7 days minimum plus WAL back to the oldest retained base.

## Drill record

| Date | Scenario | RTO measured | RPO measured | Result | Operator |
|---|---|---|---|---|---|
| 2026-10-10 | D1/D3-shaped, **laptop-scale** (two processes, one machine, one Postgres; no standby promote, no `spaces/` sync): holder `kill -9` mid-run x5, graceful stop mid-run x3, graceful stop idle x3, `kill -9` idle x3 | lease takeover **59.7 / 61.5 / 62.1 s** (min / median / max, holder killed mid-run); first Pipeline run on the survivor 61.2 / 63.3 / 64.8 s | **0 acknowledged rows lost in 14 of 14 sequences**; 100 rows (one file) committed twice in one of the five `kill -9` runs | RUN, laptop-scale only — NOT a two-node drill | agent (operator-approved) |
| — | two-host drill on plain Linux (D1–D6) | — | — | **not run — still owed** | — |
