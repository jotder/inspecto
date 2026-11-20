---
type: Seam
title: Choose a table — the Postgres snapshot seam
description: Add data's third way in on every edition — a Postgres table or one read-only SELECT copied into Parquet as a replace-everything import Dataset; the /onboard/postgres/test and /onboard/postgres routes, their gates and refusals, how it reads through DuckDB's postgres extension and the acquisition SPI's postgres scheme, Refresh now, and the tests.
resource: inspecto/src/main/java/com/gamma/control/PostgresSnapshot.java
tags: [control-plane, onboarding, add-data, postgres, snapshot, basic]
timestamp: 2026-11-19T00:00:00Z
item: module.processor
audience: internal
status: current
verified: 2026-11-20 last-edit
code: inspecto
---

# Choose a table: a Postgres snapshot (`PostgresSnapshot`; `BASIC-ONBOARD-1` P3, 2026-11-05)

Part of [Add data: `/onboard/files`](api-onboarding-authoring-add-data.md#add-data-onboardfiles-file-in--dataset-out-la-onboard-1-phase-2-2026-10-22)
in the [onboarding authoring seams](api-onboarding-authoring.md) — the draft and the commit it reuses are described
there; this page is the Postgres way in, kept on its own page so each stays readable whole.

The third way into Add data, on **every edition, Basic included** (reverses DT-4 for Postgres only; other databases
and remote systems stay Standard). A table, or one read-only `SELECT`, is copied into Parquet and becomes an
ordinary import Dataset whose every refresh is a *replace everything* arrival.

| Route | Capability | What it does |
|---|---|---|
| `POST /onboard/postgres/test` `{connection}` | `canUploadData` | connect read-only, refuse a login that could write, list the tables (`{connected, user, readOnly, tables:[{schema, table, view}], truncated}`, at most 500) |
| `POST /onboard/postgres` `{connection, schema+table \| query, kind}` | `canUploadData` | copy the rows into `<data root>/_onboard/<draftId>/<table>.parquet` and answer the SAME draft as `/onboard/files`, plus `snapshot` |

`connection` is `{host, port (5432), database, user, password}`, and `password` MUST be `${ENV:INSPECTO_PG_…}` -
the name of an environment variable on the server, and **only one starting `INSPECTO_PG_`** (`ENV_PREFIX`): the
caller picks both the host and the variable, so any other name would let anyone with `canUploadData` send any server
secret (a cloud key, the keystore password) to a Postgres server they run. Naming the variable `INSPECTO_PG_…` is the
administrator's statement that it may go to a database. Gates: as Add data (401/403 → 503 → `data.self_import` 403),
then 422 for a malformed connection, a raw password (never echoed), a reference outside the prefix (refused by its
name, nothing resolved), a host the Safety Policy refuses (`EgressGate`, checked before the password is looked up,
the policy's reason in the answer; BE-4 part 2, also on Run now), an unset variable (named), a query that is not one
`SELECT` / `WITH` (`;` inside, or another verb), a write-capable login, an empty result; 413 above
`import.max_rows`. Rate class: the standard bucket. The commit is the ordinary `/onboard/files/{draftId}/commit`;
for a snapshot draft it also saves a **`postgres` Connection** `<id>_postgres` (`<write root>/<id>_postgres_connection.toon`:
host, port, database, `username`, the password as its reference, `options.schema` + `options.table` or `options.query`)
and writes `imports/<id>_snapshot.toon` naming it (`{source: postgres, connection: <id>_postgres}`), and stamps the
Dataset entry `importSource: postgres` (`BASIC-ELT-PROFILE-1` BE-4, operator ruling O4, 2026-11-19). The Connection is
saved through the same save as `POST /connections` (`ConnectionRoutes.validateProfile` + `persistConnection`), so the
`postgres` scheme's own check refuses any secret that is not an `INSPECTO_PG_` reference there too - also when an
administrator saves or edits a `postgres` Connection by hand. Both are decided before the commit writes anything:
409 when a Connection `<id>_postgres` or the snapshot file already exists, 422 when the Connection save would refuse
it; a failed commit removes both. Deleting the Dataset deletes the snapshot file and the Connection it names when
that is `<id>_postgres` and no Pipeline is bound to it. While the snapshot stands, `DELETE /connections/{id}` refuses
its Connection: 409 naming the Dataset, no server path in the answer (`PostgresSnapshot.readBy`: the Connection a
`_snapshot.toon` names, or `<id>_postgres` when that file cannot be read; BE-4 part 2). **Snapshots committed before 2026-11-19** keep their connection
inline in `_snapshot.toon` (connection map + schema/table or query) until their next Run now migrates them (O7, below). Only this commit writes the snapshot file: an import into an existing Space carrying a `*_snapshot.toon` anywhere is refused
(`ImportPaths.REFUSED_SUFFIXES`, 2026-11-05), so an import cannot point Run now at another database. A new Space
created from a bundle (`canAdminister`) keeps the file, as it keeps every file it seeds.
**Run now** (`POST /datasets/{id}/run`) on such a Dataset copies the table again and runs the Add-a-file replace path.

**The Pipeline reads Postgres itself (`BASIC-ELT-PROFILE-1` O7, built 2026-11-20).** One idempotent function,
`PostgresSnapshot.switchPipeline`, switches the snapshot's Pipeline to `collector.connector: postgres` with
`collector.connection: <id>_postgres`, `output.load: replace` and `processing.duplicate_check.enabled: false`, every
other key kept, through `DatasetFeed.rewriteHeld` (the approval policy over Pipelines, the save gate, the audit, the
Pipeline's history; the caller holds the import's claim). It serves both ways in:
- **A snapshot commit** imports the draft's copy as before, then switches (`switchAfterCommit`, inline or in the followed
  run). A switch that fails there does not fail the commit: it is logged at WARN and the next Run now switches.
- **Run now** switches first. An old snapshot migrates there: the Connection `<id>_postgres` is saved from the inline
  block through the same save as `POST /connections`, `_snapshot.toon` is rewritten to name it, then the Pipeline is
  switched. A Connection of that name already saved for the same table (a migration a crash cut short) is taken as it
  is; one that reads anything else is never overwritten. A switch that cannot be made never stops Run now: WARN, the
  copy goes through the inline block as before, the next Run now tries again.

Each step looks before it writes, so a second call changes nothing. What then holds:
- **Run now keeps its own export** (the column check before the run, the `import.max_rows` cap and its 413, the 422
  wording for a failed copy) and the Add-a-file path with a run-scoped `replace` naming its export
  (`ArrivalLoadStep.override`; the replacement is final only when the import succeeded, undone by a refusal). Its run
  fetches nothing itself (`RunFiles.noFetch` over the inbox, opened by `Job.runChecked` for every Add data run of a
  snapshot: Add a file too), so the export it checked is its one copy.
- **A run not started by Run now** (`POST /pipelines/{id}/run`, a trigger) exports the table through the Connection and
  replaces the rows at its commit through `output.load: replace` (the config path: final at once; a replacement that
  fails keeps the rows and logs ERROR). Such a run has no column check, no `import.max_rows` cap (a Pipeline's own
  limits apply) and the engine's wording for a failed fetch (logged; the run takes nothing).
- **The export is named apart each cycle** (`<table>_<stamp>.parquet`, `PostgresCollectorConnector.cycleName`): an
  output file is named after its input, so a fixed name would overwrite the previous output in place, where no
  replacement can put it back. With the duplicate check off the Pipeline never refuses its own re-export.
- **Add a file** on a snapshot keeps its answers: its run fetches nothing, and its mode is a run-scoped choice that wins
  over the Pipeline's `output.load` (an append still appends).
**The screen (U2):** Add data asks the connection on the core Connection rows (`inspecto-connection-fields`); the Connections dialog edits the saved `postgres` Connection as itself (no Routing).

**Export, then move (redesigned 2026-11-05, operator).**
- **The export.** Run now EXPORTS the table with DuckDB `COPY (...) TO '<file>.parquet'`, through the same
  `postgres_scanner` session, into a temporary folder `<data root>/_onboard/a<uuid>/`. That folder is under the data
  root and never inside the inbox, so nothing reading the inbox (a concurrent Run now, the engine's discovery) can
  see a half-written file.
- **The move.** Only once the file is complete is it MOVED atomically into the Dataset's inbox
  (`OnboardImport.addStaged(..., moveIntoInbox: true)`, `Job.moveSource`). The ordinary replace path then takes it like
  an uploaded file: name-checked columns, the row filter (`ADD-DATA-ROW-FILTER-1`), the arrival (mode `replace`, the
  rows left out) and compaction.
- **A failed export** (refused login, unset reference, a copy error) removes the temporary folder and leaves the
  inbox and the data as they were.
- **The commit.** The draft's export is already complete and outside the inbox. The commit copies it to a temporary
  file in the same drafts folder (the draft keeps its own copy for a retry) and moves that in; every Add data landing
  now copies outside the inbox the same way. It used to copy to a `.landing-` file inside the inbox.
- **A snapshot is manual only.** The 30 s refresh is removed: `InboxWatch.Snapshot`, its poll target and
  `PostgresSnapshot.poll` are gone. `PUT /datasets/{id}/watch {watch: true}` on a snapshot is 422 ("a Postgres
  snapshot is not polled"). The feed answers `watchable: false`, and an entry switched on before (`watchInbox: true`)
  never reads as watched. There is no longer an "unchanged table adds no arrival" check: every Refresh now is a
  replace, as an upload's *Replace everything* is.

**How it reads (P0 V2, plan §6.1).** DuckDB's `postgres` extension (`postgres_scanner`), already staged in every
bundle - no JDBC driver on Basic, no dependency added. Since BE-4 (2026-11-19) the test, the draft and Run now all go
through the acquisition SPI's `postgres` scheme (`com.gamma.inspector.PostgresCollectorConnector`, built by
`PostgresConnectorFactory`; [acquisition spec](../elt/spec-acquisition.md)); `PostgresSnapshot` keeps the HTTP edge,
Add data's wording and the 413/422 mapping of the connector's `Refused`. The role-refusal text reads "Inspecto
reads Postgres with a read-only login". `Session.open`: resolve the reference → in-memory DuckDB →
`DuckDbExtension.ensureLoaded("postgres_scanner")` → `CREATE TEMPORARY SECRET` → `ATTACH '' AS pg (TYPE postgres,
SECRET …, READ_ONLY)`, all before the seal; then `allowed_directories` = the stage only, external access off.
⚠ Never the inline libpq string: DuckDB's connect error ECHOES it, password included (measured); the secret form
says `at ""`. Every error is scrubbed of the resolved value anyway.

**Read-only, three ways (each mutation-tested):** `READ_ONLY` makes DuckDB refuse a write AND the Postgres session
run `transaction_read_only = on` (so `postgres_execute('INSERT …')` is refused by Postgres); a pasted query must
be one `SELECT`/`WITH`; and the role check (superuser, or INSERT/UPDATE/DELETE/TRUNCATE on any non-system table,
via `has_table_privilege`, so inherited and PUBLIC grants count) runs on the test AND on every copy, Run now
included. Not checked: CREATE on a schema (Postgres < 15 grants it to PUBLIC on `public`; refusing it would refuse
nearly every login).

**Types.** The copy casts to what the import reads: `timestamptz` → its UTC wall time (`timezone('UTC', …)`, never
the host zone); integers, floating, `numeric`, `date`, `timestamp`, `boolean`, text kept; anything else (uuid,
json, arrays, intervals, bytea…) → text. The draft then reads the Parquet like an uploaded one (`numeric` →
DOUBLE).

**The seam.** `OnboardImport.addStaged(space, content, id, key, stage, file, name, mode, ex[, moveIntoInbox])` is
the Add-a-file tail with no HTTP exchange (`ex == null` always runs inline). `PostgresSnapshot.refresh` exports, then
calls it with `moveIntoInbox: true`. (Until 2026-11-05 `PostgresSnapshot.poll` refreshed a snapshot every 30 s and
skipped an unchanged copy by its sha256; removed with the poll.)

Tests: `inspecto/.../ControlApiPostgresSnapshotTest` - the refusals before connecting (raw password never echoed,
unset reference named, malformed host/port, write verbs, `;`, table AND query, a quoted identifier), 503/401/403/
self-import 403, and against a REAL Postgres when `INSPECTO_TEST_PG_URL` (superuser JDBC URL) and
`INSPECTO_PG_TEST_PASSWORD` are set (skipped otherwise - read "Skipped"): a write-capable login refused at
the test and at the copy, the table list, the draft's types, the password in no answer / draft / config, commit,
Run now replacing (35 rows after 5 added and 10 deleted) through the inbox (the inbox empty after the run, the
arrival a `replace`), a snapshot never polled (Watch refused, a tick copies nothing, Refresh now does), the row filter
applied on every refresh, the session itself read-only for a write-capable login, a data-modifying `WITH` changing
nothing. Five mutants, each killed by its test. All 11 ran against a throwaway `postgres:18` container on 2026-11-05.
BE-4 (2026-11-19): the session read-only test moved with the session to `platform/inspecto-engine/.../PostgresConnectorFactoryTest`
(also: the profile checks, the save seam finding the scheme by ServiceLoader, one export per cycle, a Collector
`discover` + `fetchTo` giving typed Parquet, the row cap); `ControlApiPostgresSnapshotTest` adds a `postgres`
Connection saved only with an `INSPECTO_PG_` reference (create and update), the commit's saved Connection and its
removal with the Dataset, a pre-BE-4 inline snapshot refreshing unchanged, and a taken Connection name refusing the
commit whole; `ControlApiSnapshotExportTest` runs its failed-export case on both shapes. 13 + 9 + 6 ran against a
throwaway `postgres:18` container, 0 skipped.
O7 (2026-11-20): `ControlApiSnapshotExportTest` (no database) - Run now switches the Pipeline once and migrates an old
snapshot first, a second Run now changes nothing, a Connection of the snapshot's name for another table is never taken
over, a snapshot commit switches after its first run, and a switched snapshot keeps one copy whoever runs it (Run now's
run fetches nothing itself; a run outside Add data replaces through `output.load`); `RunFilesTest` (the no-fetch scope
stops only its own Collector's acquisition); `PostgresConnectorFactoryTest` (a name of its own per cycle).
`ControlApiPostgresSnapshotTest` (real Postgres, skipped without one) now expects an old snapshot to migrate at its
first Run now and runs the switched Pipeline outside Add data.
BE-4 part 2 (2026-11-20, no database needed): `ControlApiPostgresSnapshotTest` adds the Safety Policy refusing a host
on the test and the copy before the password lookup, and `DELETE /connections/{id}` on a snapshot's Connection over
every gate (503, 401, 403, 404, 409 for a named and an unreadable `_snapshot.toon`, 200 once the snapshot is gone);
`PostgresConnectorFactoryTest` the gate on the export, the table list and a Pipeline's `fetchTo`. Each guard falsified.
`ControlApiSnapshotExportTest` needs no database:
- the export handed over as `refresh` does is moved in and imported like an upload; the row filter applies, the
  arrival is recorded and the temporary folder is gone. A watcher thread saw only the complete file in the inbox; it
  samples, so it is not a proof for a tiny file;
- a failed export leaves no temporary folder, an empty inbox and unchanged data;
- a half-written export in the drafts folder is never taken by the 30 s poll or by Run now;
- a snapshot cannot be watched, and an old `watchInbox: true` never reads as watched.

Two mutants were killed by these tests: allowing Watch on a snapshot, and leaving the failed export's folder behind.
