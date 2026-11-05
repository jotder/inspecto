# Basic data onboarding: three ways in, one default Pipeline, every application (`BASIC-ONBOARD-1`)

> **PLAN 2026-11-05 — the operator accepted the recommendations (§4) and answered the open questions (§5) the same
> day. Nothing built under this name yet.** Operator: *"This is minimum data onboarding (basic version of
> pipelining), reusable functionality that will work across many applications (not only Link Analysis: the Basic
> versions of MFS, rating, roaming analysis…)."* And the rule behind every placement below: **"Basic is not a demo;
> it must be usable."**

## 1. The goal in the operator's words

A Basic user onboards data **three ways**:

1. **Choose an inbox directory.** Files dropped there are imported.
2. **Upload a file** once.
3. **Choose a table** by connecting a database.

What makes it Basic: **no transformation**, **no user-defined schedule** (*Run now*, or an automatic poll), and
**no Pipeline view or edit**. Behind it, a **default Pipeline** that converts, arranges and indexes flat data
(delimited, Excel) into Parquet.

## 2. What exists (master `b4de24231`, 2026-11-05)

| Piece | State | Where |
|---|---|---|
| Hidden one-step Collector + Pipeline per import (`origin: import`); an upgrade to Standard shows it as an ordinary editable Pipeline | built | `DATA-TIERS-1` DT-2; `OnboardImport` |
| Upload once: CSV / TSV, Parquet, JSON / NDJSON, Excel with the bundle's extension | built | `/onboard/files`; console `/add-data` and LA Add data share one component (DT-8, `ONBOARDING-FIRST-RUN-1`) |
| Point at a file in place (fingerprint re-check) | built | DT-3 |
| Inbox: *N files waiting · Import waiting files* | in flight | `LA-ARRIVAL-FEEDS-1` lane (Run now, no poll) |
| Arrival rules: columns checked by name, duplicate refusal, *Replace the period*, arrivals list, compaction | in flight | `LA-ARRIVAL-MODES-1`, `LA-ARRIVAL-LEDGER-1`, `LA-DATASET-COMPACT-1` lanes |
| Expectations, quarantine, dedupe on Basic | built | DT-15 |
| Column review (names, types, roles, Entity Types) | built | Add data review step; `LA-COLUMN-ROLES-1`, `ENTITY-TYPE-CATALOG-1` |
| Database on Basic | **locked** | DT-4 (files only) - **reversed here** |
| Basic per application | **LA only**; others Standard+ until decided | DT-10 - **widened here** |

## 3. Decisions this plan reverses or narrows

| Earlier decision | Now |
|---|---|
| **DT-4** files only on Basic; a database shown locked | **Reversed:** Basic imports a database table as a snapshot (§4.3). Other remote adapters (SFTP, object stores, message buses) stay Standard. |
| **DT-5 / DT-17** no polling on Basic; a trigger-less Pipeline never polls | **Narrowed:** an import inbox may poll every 30 s on Basic (§5 Q1). Still no user-defined schedule, cron or event trigger. |
| **DT-10** each application's Basic form decided one by one; others default to Standard+ | **Widened:** onboarding itself is a platform feature every application's Basic placement reuses; *which* applications get a Basic form stays decided one at a time (§5 Q3). |
| **`ONBOARDING-FIRST-RUN-1` C3** *Open in editor* on the review screen | **Moved here:** on Basic there is no editor; on Standard+ the existing *Open in editor* on the Dataset card after Create stands. Revisit only if a Standard user asks. |

## 4. Design (operator-accepted recommendations)

1. **One onboarding feature, owned by the platform.** *Add data* is a platform feature with three ways in and one
   default Pipeline; each application (LA today; MFS, rating, roaming later) places the same screen in its own UI.
   No application forks the import.
2. **A fixed default Pipeline: read → arrange → write Parquet → follow-on.** *Arrange* is the review step (names,
   types, column roles, Entity Types). *Follow-on* is a small list each application contributes to; it runs inside
   the user's run (DT-16's in-run rule). LA contributes its link index.
3. **A database table is a snapshot you re-run.** Pick a table (or a saved read-only query), copy it into Parquet;
   *Run now* (or the poll, if the user turns it on) refreshes it as a *replace everything* arrival. Read-only
   credentials, stored as a `${ENV:…}` secret reference, never the raw value.
4. **The same arrival rules for all three ways in.** Name-checked columns, duplicate refusal, *Replace the period*,
   the arrivals list, compaction and retention apply to an inbox file, an upload and a snapshot alike.
5. **Placement.** This plan owns the Basic column of `DATA-TIERS-1`; `DATA-TIERS-1` keeps the Standard / Advanced
   gates and the breaking-change refusals (DT-9).

## 5. Operator answers (2026-11-05)

| # | Question | Answer |
|---|---|---|
| Q1 | "Manual scheduling" | ***Run now*, or auto: a 30-second poll.** No user-defined scheduling. The poll is per import inbox (and per snapshot when the user turns it on), off by default, one interval, not configurable on Basic. |
| Q2 | Which databases first | **Postgres.** |
| Q3 | Which applications next | **Decided later, as we go.** The first non-LA placement is its own row when chosen. |
| Q4 | What "index" means | **The follow-on index is for the applications that need one** (LA's link index). For the others, the default Pipeline ends at sorted, partitioned Parquet; an application adds a follow-on step only when it has its own. *(Recorded reading of "Index for non-LA apps only" - confirm at P0.)* |

## 6. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0 verify** | V1 the follow-on list as a seam (where LA's index call sits today in `OnboardImport`); V2 Postgres read via DuckDB's `postgres` extension vs the `db` Collector - which ships in the Basic bundle, and its size; V3 a 30 s poll's cost on an idle install (no Spaces open, many inboxes); V4 confirm Q4's reading | S |
| **P1 Platform seam** | *Add data* as a platform feature: the follow-on list (LA registers its index), the default Pipeline's four steps named in the import's config, the console `/add-data` and LA placements on one component (already shared; make the seam explicit) | M |
| **P2 Inbox poll** | Per-inbox *Watch this folder* toggle (30 s), on Basic and up; one poller for all inboxes; a refused file is set aside with its reason (as *Run now* does); visible in Data status | M |
| **P3 Postgres snapshot** | Connect (host, database, user, `${ENV:…}` password), pick a schema / table or paste a read-only `SELECT`, preview, review columns, Create; *Run now* / poll refreshes as *replace everything*; connection test refuses write-capable statements | L |
| **P4 Docs and sale** | EDITIONS rows, the whitepapers, the user guide; DT-4 / DT-5 / DT-10 / DT-17 amended in `data-tier-editions-plan.md` | S |

### 6.1 P0 V2 result - how Basic reads Postgres (measured 2026-11-05, DuckDB 2.0.0.0, Postgres 18.6 in Docker)

| | DuckDB `postgres` extension (`postgres_scanner`) | The `db` Collector (`DbExportConnector`) |
|---|---|---|
| In the Basic bundle today | **Yes.** `package.ps1` step 6d stages `postgres_scanner.duckdb_extension` for both platforms in every edition (it rides with `ducklake`, AIRGAP-EXTENSIONS-1); loaded through `DuckDbExtension.ensureLoaded`. Windows file 52 MB, already shipped | **No.** The connector class is in `inspecto-connectors` (every edition), but `postgresql.jar` (42.7.12, 1.1 MB) is never bundled on Basic (`package.ps1` skips it for Basic; it is REQUIRED with a Postgres module, `needsPostgresDriver`: Advanced, Preview, and rides along on Standard when the local Maven repo has it) |
| New dependency / bundle growth | **None / 0 bytes** | +1.1 MB driver on Basic, plus the Collector's config surface |
| Offline build | extension already in the local cache (`~/.duckdb/extensions/v2.0.0/…`) | driver in the local Maven repo |
| Types | typed straight into Parquet (BIGINT, DECIMAL, TIMESTAMPTZ, BOOLEAN, UUID…) - no re-inference | JDBC → **CSV text**, every type re-guessed |
| Refusing writes | `ATTACH … (READ_ONLY)`: DuckDB refuses `INSERT` on the attached database **and** the Postgres session runs `transaction_read_only = on`, so even `postgres_query` / `postgres_execute` with `INSERT` is refused by Postgres. Multi-statement text cannot be prepared | `Connection.setReadOnly(true)` would have to be added |
| Secret handling | a `TEMPORARY SECRET` (in memory, `duckdb_secrets()` shows `password=redacted`); a failed connect says `Unable to connect to Postgres at ""` - no password. ⚠ The inline libpq string form ECHOES the password in the error: never use it | JDBC URL built from the profile |
| Engine seal | attach before the seal, then `enable_external_access=false` + `allowed_directories`: the attached tables stay readable, a second `ATTACH` and any file outside the stage are refused | n/a (no DuckDB) |

**Decision: the DuckDB `postgres` extension.** No dependency added and the Basic bundle does not grow, so P3 goes ahead
without a further operator question (the rule in the lane brief). The `db` Collector stays the Standard way to keep a
database table up to date incrementally (watermarks), unchanged.

**Decision recorded for "refuse a write-capable session"** (the plan says read-only credentials; the operator was
away): the connection test **refuses** a role that is a superuser or holds INSERT / UPDATE / DELETE / TRUNCATE on any
table outside the system schemas (`has_table_privilege`, so inherited and PUBLIC grants count), naming what to grant
instead. The session is read-only regardless (above); the role check is so the user gives Basic a read-only login.

**Decision recorded for the password reference** (found by the verification review, operator away): only
`${ENV:INSPECTO_PG_…}` is resolved. The person choosing the host also chooses the variable, so an unrestricted
`${ENV:NAME}` would let anyone with `canUploadData` send any server secret to a Postgres server they run (a
cleartext-password server receives it as is). Naming the variable `INSPECTO_PG_…` is the administrator's decision that
it may go to a database.

**P3 built 2026-11-05 (lane onboard-pg)** - as built in `module/processor/api-onboarding-authoring.md` (*Choose a
table*) and `layer/product/user-guide-onboarding.md`. Seams left for the sibling lanes: `OnboardImport.addStaged` (the
Add-a-file tail with no HTTP exchange) and `PostgresSnapshot.poll(writeRoot, dataRoot, service, id)` for P2's poller,
which still owns the 30 s toggle. Nothing registers a way in: a refresh lands its Parquet through the import's
Pipeline (`addStaged`), which calls `followOn` and `recordArrival` like every other way, so P1's follow-ons run after it.

## 7. Not in scope

- Transformation on Basic (joins, derived columns, filters beyond the review step) - Standard.
- User-defined schedules, cron, event or signal triggers on Basic.
- Other databases (MySQL, SQL Server, Oracle) and other remote adapters - later rows on demand.
- A Pipeline view or editor on Basic.
- Choosing the next application's Basic form (§5 Q3).

## 8. Board

`BASIC-ONBOARD-1` (P2) in `docs/BACKLOG.md`.

## References

- `docs/superpower/data-tier-editions-plan.md` (DT-2, DT-3, DT-4, DT-5, DT-8, DT-10, DT-15, DT-16, DT-17)
- `docs/superpower/first-run-onboarding-plan.md` (C3)
- `docs/archived-documents/plans-archive/la-data-arrival-plan.md` (arrival rules)
