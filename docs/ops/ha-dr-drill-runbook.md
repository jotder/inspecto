# HA/DR drill runbook: two-node active/passive on plain Linux (T4)

> **Status: runbook; drill NOT yet run.** Nothing below has been executed on two Linux hosts. No RTO or
> RPO in this file is measured. The signed targets are in
> [`okf/capabilities/editions/editions.md`](../okf/capabilities/editions/editions.md) §3.14 (T4: RPO ≤ 5–15 min
> async, RTO ≤ 30 min promote, quarterly drill, acceptance row **VER-12**). Record the first real drill in
> the table at the end, and in `compliance/evidence/rto-rpo-statement.md`, which the operator fills in.
> (`ASSURE-OPERABILITY-1`, 2026-09-29.)

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
- ⛔ Not proven: a lease handover between two processes on two hosts, promotion of a Postgres standby,
  a `spaces/` sync restore, or any timing. Those are the drill.

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
  A paused node that wakes after its TTL finds its epoch stale and loses. ⚠ This fences the lease, not the
  writes a run performs (see the `DbRunLease` class note on D15).
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
| D4 | Network partition | on A: `iptables -A OUTPUT -d <pg-host> -j DROP` for > 60 s, then remove it | A logs `Could not renew the run lease`; B (if started) takes the lease after 60 s with a higher epoch; after heal A logs `was stolen while we still believed we held it`. ⚠ Expected gap, record it: a run already in flight on A is **not** aborted by the steal (the renewer only logs), so check provenance for a double-processed file |
| D5 | Split brain guard | start B while A is healthy | B acquires nothing A holds (rows keep `owner=node-a`) |
| D6 | Fail back | reverse D3 once A is rebuilt as the new standby | same pass criteria, roles swapped |

## Measuring RTO and RPO

- **RTO** = time from the fault (the `kill`/power-off timestamp) to the first `200` from `GET /ready` via the
  LB **and** the first Pipeline run completing on the surviving node. Poll once a second:
  `while ! curl -fsS https://lb/ready >/dev/null; do sleep 1; done; date -u +%FT%TZ`.
- **RPO** = work lost. Two parts, report both:
  1. Postgres: bytes/transactions not replicated at the fault —
     `SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) FROM pg_stat_replication;` sampled before
     the fault, and after promotion compare the last row time in the state tables with the clock.
  2. `spaces/`: files ingested on A after its last `rsync`. Count inbox files whose provenance row exists on
     A's last known state but whose output is absent on B. These must be re-ingested (the inbox is
     idempotent via the dedup ledger if it lives in Postgres).

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
| — | none run yet | — | — | — | — |
