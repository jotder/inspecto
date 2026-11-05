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
6. **One poller, a small "poll this source" seam (P2, built 2026-11-05).** `InboxWatch` (`inspecto/.../control/`)
   runs ONE daemon thread for the whole JVM, every 30 s at a fixed delay (a slow tick delays the next, never overlaps
   it). It polls each registered `InboxWatch.PollTarget`, one at a time, with the Space's MDC set:
   ```java
   interface PollTarget {
       String key();          // unique in the JVM; registering the same key again replaces the poll target
       String space();        // the Space id the poll runs under (MDC)
       ApiContext owner();    // the ControlApi that registered it: its poll targets go when it closes
       boolean poll() throws Exception;   // one look; false = stop polling me; an exception is logged, the poll target kept
   }
   InboxWatch.register(source);  InboxWatch.unregister(key);
   ```
   The inbox poll target (`InboxWatch.Inbox`) re-checks on every poll that the Space exists, Add data is on, the Dataset
   entry still says `watchInbox: true`, it is still an import Add data wrote (`OnboardImport.requireOwnImport`) and no
   schedule runs its Pipeline; then it starts `OnboardImport.importWaiting` - the same code as *Run now* - only when
   every waiting file has settled. **P3's snapshot refresh registers its own poll target** (key e.g.
   `snapshot|<writeRoot>|<id>`); its `poll()` decides whether a refresh is due and runs it the way *Run now* does. A
   poll target that must survive a restart persists its own on/off flag and re-registers it: the inbox does this from the
   Dataset entries on the first tick after start-up (`InboxWatch.attach`); a snapshot poll target needs the same step.

### 4.1 The follow-on seam (P1 - read this if you call it from P2 or P3)

- **Interface:** `com.gamma.onboard.ImportFollowOn` in `platform/inspecto-engine`, found with
  `OptionalSpi.all(ImportFollowOn.class)` (a `META-INF/services` entry). Methods: `key()` (the run summary key its
  outcome goes under, LA: `index`), `does()` (the review sentence's clause, LA: *builds its link index*), `needs()`
  (the Dataset entry keys it needs, LA: `defaultLinkMapping`), `deferred()` (its outcome when the Space's Jobs do it),
  `run(Context)` with `Context(writeRoot, dataRoot, dataset, entry, actor)`. It returns an outcome map
  (`result`, `message`, plus its own fields), never throws for a refusal.
- **LA registers** `com.gamma.geolink.LinkIndexFollowOn` in `la/inspecto-geo-link` (the module that already provides
  `LinkIndexBuilder`). The link-mapping readers it shares with the platform (`primary`, `carriedColumns`, the
  `defaultLinkMapping` / `primaryTime` / `primaryMeasure` / `carried` keys) move from `DatasetEntryRules` to
  `com.gamma.linkindex.LinkMapping` (engine).
- **Where it runs:** `OnboardImport.Job.followOn(summary)` is the fourth step, unchanged in name and in call sites
  (commit, *Add a file*, *Run now*). **P2 / P3: land your Parquet the way those three do, then call
  `job.followOn(summary)` and `job.recordArrival(summary)`; never call a follow-on directly.** Its order: compaction,
  `followOnInRun`, each registered follow-on under its `key` (or its `deferred()` outcome when `pipeline.automation`
  is present), then the Expectations.
- **No follow-on registered** (an application without one, or an install without LA): the summary has no `index`
  key and the arrival records `index: null`; the import ends at the Parquet it wrote. Before P1 it said
  `NOT_AVAILABLE`.
- **The four steps in the import's config:** every `<id>_pipeline.toon` Add data writes carries
  `import_steps: [read, arrange, write_parquet, follow_on]` (declared in `ConfigSpecs`, read by no engine code, like
  `origin`). An import written before P1 has none and means the same.
- **UI:** the draft (`POST /onboard/files`) lists `followOns: [{key, does, needs}]`; the review sentence names those
  whose `needs` the review satisfies. The shared screen stays in `projects/link-analysis/src/add-data/` for now (P2
  and P3 edit it); moving it to a platform library is a follow-up row once they land.
- **Code P1 moves that P2 / P3 may touch:** `OnboardImport.Job.buildIndex` is deleted (now in LA's follow-on);
  `OnboardImport.pipeline(...)` gains the `import_steps` line; `DatasetEntryRules.primary` / `carriedColumns` /
  `CARRIED*` move to `LinkMapping`. Nothing else in `OnboardImport` moves.
- **Left in the platform on purpose:** `DatasetFeed`'s *Keep this up to date* proposes LA's `la.index.build` Job
  (`DatasetFeed.java` ~336) - a Standard-only path, not this seam.

## 5. Operator answers (2026-11-05)

| # | Question | Answer |
|---|---|---|
| Q1 | "Manual scheduling" | ***Run now*, or auto: a 30-second poll.** No user-defined scheduling. The poll is per import inbox (and per snapshot when the user turns it on), off by default, one interval, not configurable on Basic. |
| Q2 | Which databases first | **Postgres.** |
| Q3 | Which applications next | **Decided later, as we go.** The first non-LA placement is its own row when chosen. |
| Q4 | What "index" means | **The follow-on index is for the applications that need one** (LA's link index). For the others, the default Pipeline ends at sorted, partitioned Parquet; an application adds a follow-on step only when it has its own. **Confirmed by the operator at P0 (2026-11-05)**, with one correction: today an import writes one snappy Parquet file per run and compaction merges them later; nothing sorts or partitions it. P1 ends at that Parquet (no behaviour change); sorted, partitioned output is a later board row. |

## 6. Phases

| Phase | Scope | Size |
|---|---|---|
| **P0 verify** | V1 the follow-on list as a seam (where LA's index call sits today in `OnboardImport`); V2 Postgres read via DuckDB's `postgres` extension vs the `db` Collector - which ships in the Basic bundle, and its size; V3 a 30 s poll's cost on an idle install (no Spaces open, many inboxes); V4 confirm Q4's reading. **V1 done (2026-11-05):** the link index is built in `OnboardImport.Job.followOn` → `buildIndex`, which calls LA's `LinkIndexBuilder` SPI (`la/inspecto-geo-link` `ScheduledLinkIndexBuilder`) when the Dataset has a `defaultLinkMapping`; `followOn` is called by the commit (`finishCommit`), *Add a file* and *Run now*, after compaction and before the Expectations. The seam (§4.1) keeps that call and replaces `buildIndex` with the registered list; the operator chose LA's own module for LA's entry. **V4 done:** §5 Q4. | S |
| **P1 Platform seam** | *Add data* as a platform feature: the follow-on list (LA registers its index), the default Pipeline's four steps named in the import's config, the console `/add-data` and LA placements on one component (already shared; make the seam explicit) | M |
| | **P1 built 2026-11-05** as §4.1 describes. Proved by the LA tests unchanged (`ControlApiOnboardLinkAnalysisTest` still sees `index: BUILT` through the seam), `aRegisteredFollowOnIsListedAndRunsInsideTheRun` (a registered follow-on runs after the Parquet is written, in the commit and in *Run now*) and the platform tests without LA (no `index` key, `followOns: []`). Follow-ups for P4 to file: move `projects/link-analysis/src/add-data/` to a platform UI library once P2 / P3 land (chore); sorted, partitioned Parquet in the write step (§5 Q4, feature). Seen, not caused here: `ImportCapabilityGuardTest.everyStricterRouteOfAWritableKindIsCoveredByTheTable` is red on master `ec33608ff` (the `/datasets/{id}/feed` and `/compact` routes are missing from `ImportCapabilityGuard`). | |
| **P2 Inbox poll** | Per-inbox *Watch this folder* toggle (30 s), on Basic and up; one poller for all inboxes; a refused file is set aside with its reason (as *Run now* does); visible in Data status | M |
| **P3 Postgres snapshot** | Connect (host, database, user, `${ENV:…}` password), pick a schema / table or paste a read-only `SELECT`, preview, review columns, Create; *Run now* / poll refreshes as *replace everything*; connection test refuses write-capable statements | L |
| **P4 Docs and sale** | EDITIONS rows, the whitepapers, the user guide; DT-4 / DT-5 / DT-10 / DT-17 amended in `data-tier-editions-plan.md` | S |

### P0 V3 - what a 30 s poll costs on an idle install (measured 2026-11-05)

**What one tick does (the P2 design the measurement assumes).** The poll is to keep the list of watched inboxes
in memory (read once from each Space's Dataset entries, then kept by the *Watch this folder* toggle), so an idle tick
reads no registry and opens no Space: per watched inbox it lists the folder (depth 4, as *Import waiting files* does)
and reads each visible file's size and modified time. A run starts only when a file is waiting and has settled.

**Measured** (JDK 27, Windows 11, 12 logical CPUs, local NTFS disk with the default virus scanner on; synthetic
folders; 20 warm ticks averaged; CPU and allocation of the poll thread only):

| Watched inboxes | Files waiting in each | CPU per tick | Wall per tick | Allocated per tick | Share of one core at 30 s |
|---:|---:|---:|---:|---:|---:|
| 10 | 0 | 1.6 ms | 1.8 ms | 25 KB | 0.005 % |
| 100 | 0 | 22 ms | 31 ms | 255 KB | 0.07 % |
| 1,000 | 0 | 176 ms | 185 ms | 1.9 MB | 0.6 % |
| 10 | 5 | 6 ms | 6 ms | 101 KB | 0.02 % |
| 100 | 5 | 66 ms | 66 ms | 770 KB | 0.2 % |
| 1,000 | 5 | 1,010 ms | 1,041 ms | 7.6 MB | 3.4 % |

**Verdict: affordable; no change to the design.** An idle install with empty inboxes costs under 1 % of one core
at 1,000 watched inboxes (a verification re-run measured 0.87 %, so "under 1 %" is close to its limit there), and the
garbage per tick is small. The cost grows with the files *left* in inboxes, each stat-ed every tick. A Basic user
leaves files there in two ways: a file whose columns do not match is moved to `refused/` by the import (so it stops
costing), and a file already imported under the same name stays and is counted (the inbox says *rename to import
again*). Not in the harness: for each waiting file the poll also asks whether it was already imported under its name
(`MarkerManager.isAlreadyProcessed`, one or two more file look-ups), so the "files waiting" rows understate the cost
somewhat - the same order of magnitude. The design runs one thread for every inbox, at a fixed delay, so a tick that
overruns 30 s delays the next one rather than overlapping it. A slow network folder is the risk the numbers do not
cover: there each listing is a round-trip.

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
