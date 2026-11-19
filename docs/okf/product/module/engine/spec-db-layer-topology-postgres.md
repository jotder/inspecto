---
type: Reference
title: Database / persistence layer — File topology, Postgres and the raw tables
description: Part 4 of 4 of Database / persistence layer: File topology (per space); Running operational data on Postgres; Browsing the raw tables; Why __consignment_id is on the row, not just in the registry; Proving the JDBC stores on real PostgreSQL (DAT-6)
tags: [persistence, duckdb, postgres, schema, topology, stores]
timestamp: 2026-07-16T00:00:00Z
---

# Database / persistence layer — File topology, Postgres and the raw tables

Part 4 of 4 of [Database / persistence layer](spec-db-layer.md) · previous: [Schemas — consignment outputs onward](spec-db-layer-schemas-continued.md).

## 4. File topology (per space)

**One DuckDB file per capability** — not one shared DB, and not one file per space. Each file is
single-writer-locked (documented in `ServiceStores`). Locations come from
[`SpaceRoot`](../../../../../inspecto/src/main/java/com/gamma/service/SpaceRoot.java):

| Layout | Capability file locations |
|---|---|
| **`DirSpaceRoot`** (per-space dir) | `<dataRoot>/duckdb/<file>` — e.g. `spaces/demo/duckdb/inspecto-ops.db`; `<dataRoot>` is the Space dir unless `space.toon data_root` / `-Dspaces.data.root` moves it (`SPACE-DATA-ROOT-1`) |
| **`LegacySpaceRoot`** (flat working dir) | `./inspecto-ops.db`, `./inspecto-ops-links.db`, `./inspecto-ops-notes.db`, `./inspecto-status.db`, `./jobs_report.duckdb`, `./provenance.duckdb`, `./inspecto-acquisition.db`, `./inspecto-consignment-outputs.db`, `./inspecto-file-stages.db`, `./inspecto-delivery-receipts.db` |

So across N spaces you get N separate sets of these files. Events live under `<dataDir>/events/`
(`DirSpaceRoot`) or `./inspecto-events/` (legacy). Every `-D<capability>.db.url` flag overrides the
per-space default explicitly — note that a global `-D*.db.url` therefore funnels EVERY space into one
shared file; leave them unset in multi-space mode so each space keeps its own `duckdb/` set.

`DirSpaceRoot` **mints `<spaceBase>/duckdb/` on first URL build** (`SpaceRoot.java`, guarded by
`SpaceRootTest`): repo-checked-out spaces gitignore `duckdb/` and DuckDB does not create parent
dirs, so without the mkdir every DB-backed store silently degraded to in-memory on a fresh checkout.

**In-memory scratch spill (`DUCKDB-INMEMORY-SCRATCH-UNCAPPED-1`).** Transient in-memory DuckDB
connections (job tasks, compactors, Risk Score writes, `SqlGuard`, `TypeFlow`) are not stores, but they
spill: an in-memory database's default `temp_directory` is `.tmp` relative to the **process CWD**. Every such
open goes through `DuckDbUtil.openInMemory(spillDir)` — spill lands in `<data root>/.duckdb_tmp` when the
caller has a Space data root, else in `java.io.tmpdir` (session decision 2026-09-28: the JVM temp dir is the
only writable directory every opener has, and it is never the CWD). Details and the full caller list:
[spec-duckdb.md](spec-duckdb.md); `NoRawInMemoryDuckDbOpenContractTest` fails the build on a new raw open.

---

## 5. Running operational data on Postgres

The layer was **designed** for this: stores are JDBC-pluggable by URL scheme, the DDL is deliberately
portable (`VARCHAR`/`BIGINT`, composite PKs, no auto-increment, no upserts — explicit DELETE-then-INSERT),
and there is a **real embedded-Postgres round-trip test**
([`PostgresStateStoreTest`](../../../../../inspecto/src/test/java/com/gamma/service/PostgresStateStoreTest.java),
moved to `inspecto` 2026-09-17 so it runs in the DEFAULT reactor — the four `com.gamma.ops` store cases stayed
behind as [`PostgresOpsStoreTest`](../../../../../features/inspecto-ops/src/test/java/com/gamma/service/PostgresOpsStoreTest.java))
the two together covering **15 of the 16 families** (every `OperationalDb.Family` constant but
`ALERTS`, plus the four object families) — **Alerts (`DbAlertStore`) is the one with no Postgres round-trip**
(re-counted 2026-11-15; this sentence said "9 of the 12", measured 2026-09-08, and before that "6 of the 9"). For Alerts, Postgres portability is reasoned, not proved.

### 5.0 One selection: `-Dinspecto.db` (2026-08-14)

The edition model, stated once: **Personal = embedded DuckDB for everything; Standard = PostgreSQL for
the operational stores, DuckDB retained as the non-updateable query engine over Parquet.** Business data
is never in either database — it is always Parquet.

`-Dinspecto.db=duckdb|postgres` (default `duckdb`) plus `-Dinspecto.db.url` / `.user` / `.password` is
therefore **all a Standard deployment sets**, and `OperationalDb` feeds every family in §5.1 including the
acquisition ledger. Personal sets nothing at all.

- ⚠ It selects a **connection, never an on/off switch.** Every `*.backend` toggle keeps its own default,
  so choosing Postgres *moves* stores rather than enabling capabilities nobody asked for.
- ⚠ **A per-family `*.db.url` still wins** — that is what keeps existing deployments byte-identical, and
  it is the escape hatch for pointing one store elsewhere. Credentials resolve the same way, per key, so
  an overridden `status.db.user` does not drag the shared password with it.
- ⛔ **An unhonourable `postgres` selection now fails at boot** (`SpaceManager.discover` →
  `OperationalDb.verifySelectable`), naming the missing property or driver. It used to be caught *per
  store*, logged WARN, and leave that store `null` — so a deployment pointed at Postgres came up
  "healthy" with job reporting, provenance and Objects **switched off rather than moved**. The shipped
  `inspecto.jar` bundles **no JDBC driver** (`platform/inspecto-engine/pom.xml`: *"runtime stays JDBC-driver-free
  by design"*) and that stays true — **the driver rides the Standard/Enterprise bundle as the
  `postgresql.jar` sidecar** (PG-1 Open 1, decided 2026-08-14): `package.ps1` copies it from the local
  Maven repo (version = the parent pom's `postgresql.version`), and `serve.sh`/`serve.bat` auto-detect
  it exactly as they do `inspecto-oidc.jar`. ⛔ The considered alternative — an edition Maven profile
  gating the dependency — was rejected: it would be the first edition seam gating a *runtime dependency*
  rather than a ServiceLoader SPI, and the sidecar mechanism already existed. The classpath entry is
  inert until `-Dinspecto.db=postgres` selects it, and the serve scripts honour a `postgresql.jar`
  dropped beside `inspecto.jar` on **any** bundle (the boot-failure message says exactly that).
- **The password is a `SecretResolver` reference, resolved at use** (PG-1 Open 2's password half,
  decided 2026-08-14): `-Dinspecto.db.password` (and any per-family `*.db.password`) takes
  `${ENV:PGPASSWORD}`, `${KEYSTORE:alias}` (the existing `secrets.keystore.*` machinery), `${FILE:…}`,
  or a literal — the `auth.oidc.clientSecret` precedent, so the secret need not sit on the process
  command line. A literal passes through unchanged, which is what keeps existing deployments working.
  ~~⚠ Open 2's **UI half stays open**~~ — **SHIPPED 2026-08-15, see §5.0-a.**

### 5.0-a The read-and-validate surface (Open 2, 2026-08-15)

**The UI reports and validates; it never writes.** Two routes, both `canConfigureAccess`:
`GET /system/operational-db` (the effective config, per family, **with the source of each value**) and
`POST /system/operational-db/test` (open the URL for real, run `SELECT 1`, return a **named** outcome —
`OK` / `DRIVER_MISSING` / `AUTH_FAILED` / `UNREACHABLE`, because "the driver is missing" means *drop
`postgresql.jar` beside `inspecto.jar`*, a different action from bad credentials). Surface:
Settings ▸ **Operational database**.

🔴 **There is deliberately no PUT, and adding one is not a follow-on.** The process serving the UI is the
one that needs the database, so it cannot configure its own dependency and no change could take effect
without a restart — and persisting from the UI would create a **second declaration of the same fact**
beside `-D`, the split-brain the enrichment companion already refused (D7). Decided 2026-08-15: the
operator applies flags through their own deployment tooling; this screen tells them what is in force.

- ⛔ **Two ledger homes were rejected for the keyed dedup window, and the reasons still bind.** The
  file-grained output ledger keys on `(consignmentId, runId, tableName, partitionKey, path)` and
  has **no column for a business key**, so carrying key hashes there would be a new table shape, not a new
  column. And **manifests are the crash-recovery record of existence, not a query surface**. *(Distilled 2026-09-10 (Sprint 7.6) from the three archived plans; this was their only home.)*
- ⚠ **Adding a `Family` is a COMPILING change, not a config toggle** — a label, a `*.backend` property, a
  default, a `Mode`, url/user/password properties and a root supplier. Budget it. A **module's** family is a
  `StoreFamily` in its own jar (`OpsStoreFamily` is the model) plus a `StoreFamilyProvider` service file and a
  `provides.storeFamilies` line in its `module.toon`; `tools/check-family-count.mjs` then expects the docs and the
  tripwires to say core + contributed.
- **The roster is OPEN since 2026-10-08 (`MODULE-REORG-P1-FAMILY`)** — the **sixteen** families on an Enterprise
  classpath are the **twelve core** `OperationalDb.Family` constants plus the four Operational Object families
  that `inspecto-ops` contributes (`StoreFamily` / `StoreFamilyProvider`, `OperationalDb.core()`/`loaded()`/`all()`);
  their property names live in those declarations and nowhere else, so the store openers and the report cannot
  drift; naming a core family off the list stops compiling.
  ⛔ They had been ten **string literals** across `ServiceStores` + `SpaceBootstrap`.
  ⚠ This bullet read **fourteen** until 2026-09-16; `INBOX_REGISTRY` (scale-out §5.3) was added to the enum
  without it. The count is `Family.values()` and nothing else — see §5.0-b, which depends on it.
- ⚠ **Three irregularities the report models rather than flattens:** three different "is it on" spellings
  with three different defaults (`none` / `duckdb` / `memory` / `file`); a **`*.backend` starting with
  `jdbc:` IS the URL** and bypasses `OperationalDb` entirely (a third source beyond per-family and
  shared; since 2026-10-06 only `jdbc:duckdb:` is accepted there, a raw `jdbc:postgresql:` is refused, operator call); and **URL grain ≠ credential grain** — the four `objects.*` families each carry their own
  `*.db.url` but share one `objects.db.user`/`.password`.
- ⛔ **A family that sends no credentials reports `user: null`, never the shared one.** Five families
  (`JOB_RUNS`, `PROVENANCE`, `CONSIGNMENT_OUTPUTS`, `FILE_STAGES`, `ACQUISITION_LEDGER`) open via
  `open(url)` and pass **no user and no password at all**, so the report uses `reportedUser` rather than
  `userFor` — `userFor`'s fall-back to `-Dinspecto.db.user` is right for a *credentialed* family
  inheriting the shared value and wrong for one that sends nothing. ⚠ Caught only by probing: the first
  cut reported `"user":"ops_user"` beside `"userProperty":null` for `JOB_RUNS`, naming a credential
  `DbJobRunStore` never sends. A diagnostic whose entire value is being trusted must not guess.
- ⛔ **No password leaves the server, in any form** — not the value, not a redaction, not a length. And
  because a JDBC URL may legally embed credentials (`jdbc:postgresql://user:pw@host/db`), every URL is
  passed through `stripUserInfo` first. ⚠ **A redaction test asserting the `password` FIELD is absent
  proves nothing** — the guard asserts the secret appears nowhere in the whole body, and was falsified by
  stubbing `stripUserInfo` to a no-op (1 failure, naming five families that leaked).
- ⛔ **A supplied password is a `SecretResolver` reference, never a literal** (422) — a literal in a form
  post is a credential in transit and in every access log. The scheme allow-list (`jdbc:postgresql:` /
  `jdbc:duckdb:`) is the other 422: an admin-gated endpoint that opens connections must not become a
  general-purpose port scanner.
- ⚠ **A new gated route must also be declared in `CapabilityManifest.ENTRIES`** — `CapabilityManifestTest`
  compares the manifest against the actual `withCapability` registration sites and fails the build on
  drift. It caught exactly this omission on the first full reactor run.

### 5.0-b Who reads a `*.backend` — `resolve()` is a MIRROR, not the implementation (2026-09-16)

*(Distilled from `ACQUIRE-LEDGER-DUPLICATE-RESOLUTION-1`, closed as REFUTED 2026-09-16 — the third
successive framing of that row to fail grounding. This subsection is its only home.)*

**Every family reads its own `*.backend` with its own `System.getProperty` at its own opener.** That is
the house idiom, not an outlier, and "bypasses `OperationalDb.resolve()`" is therefore **not a defect
signature** — it describes all fifteen:

- `OperationalDb.resolve(Family, SpaceRoot)` / `resolveAll(SpaceRoot)` have **exactly one caller
  repo-wide**: the diagnostic [`OperationalDbReport.java`](../../../../../inspecto/src/main/java/com/gamma/service/OperationalDbReport.java).
  **No store opener calls either**, and [`ServiceStores.java`](../../../../../inspecto/src/main/java/com/gamma/service/ServiceStores.java)
  says so outright — *"Mirrors OperationalDb.resolve"*. ⛔ Read `resolve()` as a **mirror of the openers
  for reporting**, never as their implementation; changing it changes what the report says, not what the
  service opens.
- **The shared seam that IS load-bearing is `OperationalDb.urlFor(Family, default)`** — the URL half.
  Every family goes through it, the acquisition ledger included
  ([`SpaceBootstrap.java`](../../../../../inspecto/src/main/java/com/gamma/service/SpaceBootstrap.java),
  and `ServiceBootstrap.java` since `ACQUIRE-LEDGER-SHARED-URL-1`). ⚠ So "one source of truth" already
  holds for URLs and does **not** hold for backends — they are separate questions, and only the second is open.
- ⛔ **`resolve()` is public but unusable outside `com.gamma.service`**: it returns
  `record Resolved` ([`OperationalDb.java`](../../../../../inspecto/src/main/java/com/gamma/service/OperationalDb.java)),
  which is **package-private** (`OperationalDb` is a `public final class`, so nested members are not
  implicitly public the way an interface's would be).
- ⛔ **Routing a leaf module's backend read through `OperationalDb` is a MODULE CYCLE.** `inspecto-acquire`
  is a deliberate leaf; `inspecto/pom.xml` depends **on it**, and `OperationalDb.java` imports
  `com.gamma.auth.secrets.SecretResolver`. `Family` is invisible from the leaf and cannot be made visible
  without inverting that dependency.
- ⚠ **`Resolved` is not value-identical to what an opener needs**: it discards the raw backend string, and
  [`AcquisitionLedgers.java`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java)
  needs it **verbatim** for its `StoreHealth` message. Substituting it — even inside the package — would
  change an invalid-value message.
- ⇒ **What a real de-duplication would cost:** a public, `SpaceRoot`-free accessor (e.g.
  `backendOf(Family)` returning the raw trimmed string) in a module **below** `inspecto-acquire`, plus all
  fifteen sites. ⛔ That is an architecture decision, not a clean-up, and nothing on the board authorises
  it. Re-file only as that systemic question.
- ⚠ **`AcquisitionLedgers.shared()` never returns null** — it falls back to `InMemoryAcquisitionLedger`, so
  a caller **cannot tell memory from durable by nullness** and must ask `StoreHealth.of(spaceId)`, the one
  place the resolved backend is still known. *(This is the fact the Completeness-KPI K4 design tripped over;
  it survives the refutation.)*

🔴 **The standing lesson: "X bypasses the central helper" is only a defect if the other N sites do not.**
Count the sites before filing. This row was filed three times — first as *"not a `Family` member at all"*
(false: `OperationalDb.java` declares it), then as *"duplicated resolution bypassing `resolve()`"*
(true but not a defect), and only the site count settled it.

### 5.0-c Schema-per-space (2026-10-04, BACKLOG "Postgres multi-user" P3)

⛔ **Schema**-per-space on one database, NOT database-per-space. `OperationalDb.urlFor(Family, SpaceRoot, default)`
(the 3-arg overload every per-Space store opener now calls) appends `currentSchema=space_<id with - as _>` to a
`jdbc:postgresql:` URL, so two Spaces sharing `-Dinspecto.db.url` keep separate tables.
`OperationalDb.schemaFor(id)` is injective (a Space id has no `_`), only ever `[a-z0-9_]`, so it is never quoted;
an id that is not a valid `SpaceId`, or whose schema would exceed PostgreSQL's 63-byte limit (ids over 57 chars),
is **refused**, and `withSchema` refuses a non-PostgreSQL URL.

- **Created at Space start:** `SpaceBootstrap.load` calls `OperationalDb.ensureSpaceSchemas(root)` before any store
  opens — `CREATE SCHEMA IF NOT EXISTS` on every distinct PostgreSQL server the Space's enabled families resolve to.
  A failure is an `IllegalStateException` (fail closed), never a fallback to the shared `public` schema.
- **Untouched on purpose:** DuckDB URLs (already one file per Space); the legacy single-tenant root
  (`config() == null`, keeps its unscoped tables); a URL that already carries `currentSchema` (an explicit operator
  choice wins); the cross-pod `INBOX_REGISTRY` (it is opened against the SPACES root and must stay shared; it keeps
  the 2-arg `urlFor`); the legacy acquisition ledger.
- ⛔ **A raw `jdbc:postgresql:` value in a family's own `*.backend` is REFUSED (operator, 2026-10-06).** Such a
  value IS the URL and bypasses `urlFor`, so it would never get `currentSchema`. One shared gate,
  `OperationalDb.rawBackendUrl(Family, raw)`, runs in every `ServiceStores` opener and in `OperationalDb.resolve`, and
  throws `IllegalStateException` at store open (startup). The message names the key and the alternative,
  `-D<family>.backend=postgres` plus `-D<family>.db.url` / `-Dinspecto.db.url`, and never echoes the value. A raw
  `jdbc:duckdb:` value still works unchanged (the root pom's test pins). Pinned by `RawJdbcBackendRefusalTest`
  (mutation-checked).
- Proof: `PostgresSchemaPerSpaceTest` (URL rules always run; the two-Spaces-cannot-see-each-other probe needs
  `INSPECTO_TEST_PG_URL` and skips per test). Existing single-schema installs on a shared server will see empty
  tables on first start (breaking change, accepted — nothing after 3.x is in production).
- **Multi-user proof on a real PostgreSQL (2026-10-05):** `PostgresSchemaPerSpaceOpsTest` (inspecto-ops) drives the
  objects / links / notes / tags families on two Spaces with 12 racing writers each — the same object id lives in both
  Spaces, 12 concurrent `ObjectService.patch` calls per Space all land (version == 12), appends are counted per Space,
  and racing to apply one tag yields one edge per Space with every caller succeeding.
  `PostgresRunLeaseSchemaPerSpaceTest` (inspecto) races 10 pods per Space for one pipeline: exactly one wins per Space,
  and the same pipeline name in the other Space is an independent lease. Gotcha: the test reactor pins
  `-Dobjects.backend=memory`, so a test must set `objects.backend=postgres` or `ensureSpaceSchemas` skips those
  families and the stores fail with "no schema has been selected".
- 🔴 **Defect found and fixed — pool footprint.** Hikari's `minimumIdle` defaults to `maximumPoolSize`, so every open
  store pinned 10 server connections while idle; there is one pool per family per Space, so a few Spaces exhaust
  PostgreSQL's default `max_connections=100` ("too many clients already" at 20 pools). the per-store pool (since deleted) then
  set `minimumIdle=1` (grows to `-Ddb.pool.size` on demand). DuckDB is unaffected (not pooled).
  ⚠ Still open: even at 1 idle, N Spaces × ~15 families × 1 is a connection budget operators must size; a shared
  pool per server (or PgBouncer) is the real answer at scale.
  **Decided (operator, 2026-10-06):** the connection budget is ONE shared pool per process with a per-family cap;
  the build follows the live PG probe.
- ✅ **Remaining families probed 2026-10-06 (PostgreSQL 18.6, Docker `postgres:latest`, 127.0.0.1 only).**
  `PostgresSchemaPerSpaceStoresTest` (inspecto) writes the SAME keys into two Spaces' schemas concurrently for
  status, provenance, dedup ledger, events, file stages, consignment outputs and delivery receipts, and asserts
  each Space reads back only its own rows (dedup: both Spaces win every key; receipts: one Space's hard bounce is
  invisible to the other's suppression list). 7/7 green, no isolation defect found.
- ✅ **Connection budget BUILT 2026-10-06** (the decision above). `JdbcDrivers.source` now hands every
  `jdbc:postgresql:` store a view over ONE `SharedPoolConnectionSource` pool per process per server+user (key = the
  URL with `currentSchema` lifted off, so TLS/options still split pools). `-Ddb.pool.process.size` (default 20)
  sizes it; `-Ddb.pool.size` (default 10) is now the **per-family** cap on concurrent borrows across all Spaces
  (fair semaphore, fails on `-Ddb.pool.timeoutMs`). Each borrow sets the view's schema (`Connection.setSchema`) or
  `RESET search_path` for a schema-less view, so a pooled connection never carries the previous Space's schema.
  The pool closes with its last view (ref-counted). Proven: 40 stores over 20 Space schemas add ≤ 20 server
  connections (mutation: the old per-store pool adds 40, red); offline `SharedPoolConnectionSourceTest`.
  ⚠ Outside the budget by design: each `DbEventStore` on PostgreSQL holds one dedicated, never-pooled advisory-lock
  connection (the chain writer) — N event stores = N extra connections. ⚠ A nested borrow from a DIFFERENT store
  of the same family on one thread takes a second permit; with a family cap of 1 it fails on the timeout rather
  than deadlocking. The old per-store `PooledConnectionSource` was deleted (2026-10-06); its offline
  pool tests moved to `SharedPoolConnectionSourceTest`.
- ✅ **Two-node and soak proof 2026-10-18 (PostgreSQL 18, Docker, 127.0.0.1 only).**
  `PostgresTwoNodeSoakTest` (inspecto-ops) covers the last two residuals that need no second host.
  **Two nodes:** a child JVM with its own driver and pool, standing in for a second host, and the test JVM each run
  60 read-modify-write increments on ONE Case in ONE Space at the same moment. Nothing in-process is shared, so
  only the `WHERE version=?` guard keeps a write from being lost. Result: 120/120 increments landed, version 120.
  **Soak:** 4 writers per Space across two Space schemas for `-Dinspecto.test.pg.soak.seconds` (default 10).
  Result: no lost increment, one version bump per landed write, and peak server backends stay within the
  per-process budget (20) of the pre-run baseline. Mutation check: with the version guard turned into
  `(version=? OR TRUE)`, both tests fail (the soak landed 681 of 1213 increments). The child JVM reuses
  `java.class.path`, so the test runs forked (surefire's default), never with `-DforkCount=0`.
  ⚠ **Still not proven:** two physical hosts (network partitions, clock skew between nodes, a lease holder that
  dies on another host) and an hours-long soak on a real deployment. Both need a deployment this repo cannot
  provide.

### 5.1 Flags (all read in `ServiceStores` unless noted)

| Capability | Backend flag | URL flag | Credentials |
|---|---|---|---|
| Objects **+ links + notes** | `-Dobjects.backend=db` | `-Dobjects.db.url`, `-Dobjects.links.db.url`, `-Dobjects.notes.db.url` | `-Dobjects.db.user` / `-Dobjects.db.password` (shared) |
| Status | `-Dstatus.backend=db` | `-Dstatus.db.url` | `-Dstatus.db.user` / `.password` |
| Jobs | `-Djobs.backend=postgres` | `-Djobs.db.url` | (in URL) |
| Provenance | `-Dprovenance.backend=postgres` | `-Dprovenance.db.url` | (in URL) |
| Acquisition ledger | `-Dacquire.ledger.backend=db` | (property in [`AcquisitionLedgers`](../../../../../platform/inspecto-acquire/src/main/java/com/gamma/acquire/AcquisitionLedgers.java)) | — |
| Consignment outputs | `-Dconsignment.outputs.backend=postgres` | `-Dconsignment.outputs.db.url` | (in URL) |
| File stages | `-Dfile.stages.backend=postgres` | `-Dfile.stages.db.url` | (in URL) |
| Delivery receipts | `-Ddelivery.receipts.backend=postgres` | `-Ddelivery.receipts.db.url` | (in URL) |
| Delivery receipts | `notify/DeliveryReceiptStore` | [`DbDeliveryReceiptStore`](../../../../../platform/inspecto-engine/src/main/java/com/gamma/notify/DbDeliveryReceiptStore.java) | `delivery.receipts.backend=duckdb\|postgres\|jdbc:…` | `none` (in-memory) |
| Events | `-Devents.backend=db` (or `postgres`, or a raw `jdbc:`) | `-Devents.db.url` | (in URL) — `-Devents.db.user` / `.password` |

Point each URL at `jdbc:postgresql://…`; the three ops URLs may share one database/schema (table names
don't collide). ⚠ Since 2026-08-14 you normally set **none** of these — §5.0's single `-Dinspecto.db`
supplies them all; the per-family flags remain as overrides and for back-compat.

### 5.2 Dialect notes & landmines

- **Only `DbJobRunStore` has a dialect branch** — it swaps DuckDB `quantile_cont(col,p)` for Postgres
  `percentile_cont(p) WITHIN GROUP (ORDER BY col)`. The probe behind it is the shared
  `JdbcDrivers.isPostgres(Connection)` (also backs `BrowsableStore.browseEngine()`'s catalog label).
  Everything else is ANSI SQL (incl. `FILTER (WHERE …)`, supported by both engines).
- **`CHECKPOINT` in `maintenance()`** (`DbJobRunStore`, `DbAcquisitionLedger`) is superuser-only on
  Postgres — currently caught-and-logged, so it degrades to a no-op VACUUM cycle. Verify that's acceptable.
- Reserved words already quoted: `"owner"`, `"trigger"`; `object_version` deliberately avoids `version`.
- No `COPY`, sequences, or DuckDB-specific types in the operational stores — those idioms live only in
  the **business-data** path (out of scope here).
- **No connection pooling anywhere** — every store uses one raw `DriverManager` connection. A real
  Postgres deployment should add a pool (e.g. HikariCP); it doesn't exist today.
- 🔴 **A raw `jdbc:` backend value used to be LOWERCASED before it was used as the URL** (fixed
  2026-09-11, scale-out phase A). Six openers read `System.getProperty(…).trim().toLowerCase()` and then
  passed that same string on as the URL, so `-Djobs.backend=jdbc:postgresql://db/MyDb?user=Alice&password=Secret`
  silently connected as `mydb`/`alice`/`secret` — Postgres database names, roles and passwords are all
  case-sensitive — and on a case-sensitive filesystem `jdbc:duckdb:/srv/Inspecto/x.duckdb` opened a
  different file. Affected: jobs, provenance, consignment outputs, dedup ledger, delivery receipts, file
  stages. `status` and `events` never lowercased and were never affected. ⛔ The rule the fix restores,
  already used by `OperationalDb.resolve`: **compare on a lowercased copy, pass on the raw value.**
- ⚠ **A store that fails to open degrades and says so only at WARN** — thirteen openers across
  `ServiceStores`, `OpsEngineProvider` and `AcquisitionLedgers` catch, log, and hand back an in-memory or
  `null` store. Since 2026-09-11 each one also records its outcome in `com.gamma.util.StoreHealth`, and
  `GET /health/details` reports one `store.<family>` subsystem per family: `UP`, `NOT_CONFIGURED` (the
  toggle is off — not a failure), or `DOWN` (a durable backend was asked for and could not be opened).
  That is what makes `VER-3` checkable. ⛔ A family with **no** entry was never opened at all — absence is
  not health, and must never be read as `UP`.
- 🔴 **`-Dinspecto.topology=partitioned` turns a degradation into a BOOT FAILURE** (`com.gamma.util.Topology`,
  D12; values `single` — the default — and `partitioned`, and it is what `/bootstrap` reports). Graceful
  degradation is right for one node and is **silent split-brain** across several sharing one database: two
  nodes each holding their own in-memory truth, neither aware of the other. ⚠ The check lives in
  `StoreHealth.record`, the one place every opener already reports through — **not** in the thirteen catch
  blocks, because thirteen checks are thirteen places a fourteenth store can forget one. ⛔ An unrecognised
  value refuses the boot rather than defaulting to `single`: defaulting would turn one typo into exactly the
  degradation the flag exists to prevent. ⚠ `partitioned` means "more than one process shares this state", so
  it covers Standard's two-node T4 standby as well as Enterprise's N pods.

### 5.3 Migration checklist

1. Put the Postgres JDBC driver on the runtime classpath (`org.postgresql.Driver`).
2. Set the flags in §5.1 with `jdbc:postgresql://…` URLs + credentials.
3. **Existing DuckDB rows don't move automatically** — there is no export/import tool
   (`BackupTask` is a filesystem zip, not a DB-row mover). Either write a one-off per-table
   `SELECT → INSERT` script, or accept a clean cutover with empty Postgres tables that the writers
   repopulate going forward.
4. Before relying on it: confirm the `CHECKPOINT` no-op is fine. (Nine store classes have a Postgres round-trip in `PostgresStateStoreTest`; `DbAcquisitionLedger`, `DbDedupLedger`
   and `DbDeliveryReceiptStore` do **not** — this parenthesis said "all seven … including `DbAcquisitionLedger`" until 2026-09-08.)
5. ~~Events cannot move — `ParquetEventStore` has no DB sibling~~ ✅ **It has one since 2026-09-12**:
   `DbEventStore` (`events.backend=db`). Events move like every other family now.

For the 9 covered stores this is essentially a **configuration change** — flags + URLs + driver + a
Postgres instance — not a code change.

---

## 6. Browsing the raw tables

The **Data Browser** pane (a per-space DB client) browses these stores live. Backend: `/db/catalog`,
`/db/table`, `/db/query` in [`control/DbBrowserRoutes.java`](../../../../../inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java)
(read-only, `SqlGuard`-checked). UI: `inspecto-ui` → **Catalog › Data Browser**. Design + phasing in
[`archived-documents/plans-archive/db-browser-design.md`](../../../../archived-documents/plans-archive/db-browser-design.md).

- **Business-data stores** (§1) read via an ephemeral DuckDB sandbox (`read_parquet`/`read_csv`).
- **A store id may itself be path-like** (e.g. a pipeline's `<name>/database` output dir) without being
  a smuggled file reference. `SqlGuard.check(sql)`'s `PATH_LIKE` rule can't tell the two apart from the
  SQL text alone, so `POST /db/query` calls the two-arg `SqlGuard.check(sql, trustedRelation)` overload
  (2026-09-22) with the request's own `table` param — but only once that name is proven to sit inside
  this space's data root (`DbBrowserRoutes.isJailedStore`, the same jail `browseStore` enforces). A
  relation ref matching that exact, already-jailed name is exempt from `PATH_LIKE`; anything else —
  a mismatched name, a string literal, an unrelated path — still gets rejected exactly as before. This
  closed a real gap: Link Analysis's own SQL editor couldn't query a dataset whose registered name
  contained a slash (`"mule_transfers/database"`), because the guard rejected its own FROM target.
- **Who may read which rows — SHIPPED 2026-10-03 (`DB-QUERY-UNGATED-1`, operator decision, option A).**
  Reading an **operational** group (`ops:*`, the control-plane DB) through `POST /db/query` or
  `GET /db/table` requires `canAuthorWorkbench` — the same capability the row-reading agent tools
  `anomaly_scan` / `suggest_expectations` carry through `POST /agent/tools/{name}`
  (`ToolCapabilities.ROW_READERS`; `ToolCapabilitiesCoverageTest` reads `DbBrowserRoutes.java` and fails if
  the two drift). **Business-store reads stay open** (Viewer dashboards read Dataset rows through these
  routes, via the SPA's `DatasetRowsService`), and `GET /db/catalog` (names only) stays open. The check runs
  **in the handler** (`DbBrowserRoutes.requireOperationalRead`), before `SqlGuard` or any read, because the
  gate depends on the request's group. ⚠ So `route-gating-report.mjs` and `check-authgate-coverage.mjs`,
  which see only `withCapability` literals, do **not** count it as gated: `CapabilityManifest` records
  `/db/query` as a `group-gated` exemption (unaudited, like `read-shaped`, via `AuditTrail`), and the real-HTTP
  `ControlApiDbBrowserTest.operationalRowReadsRequireCanAuthorWorkbench` is what pins it. The Data Browser
  pane hides operational groups from a caller without `canAuthorWorkbench` instead of letting a click 403.
- **Operational tables** (§3) browse through each store's own `ConnectionSource` via
  [`util/BrowsableStore.java`](../../../../../platform/inspecto-util/src/main/java/com/gamma/util/BrowsableStore.java) —
  a browse read borrows like any other operation and appears only when that capability runs on
  a `db`/`postgres` backend. ⚠ `browseConnection()` and `browseMonitor()` were **removed** 2026-09-14
  (`OPS-03`): a pooled store has no single long-lived connection to hand back, and exclusion moved onto
  the source — for DuckDB that is still one connection behind one monitor, so browse reads serialise
  against the store's writes exactly as before. Every `Db*Store` in §2/§3 implements this seam.

## Why `__consignment_id` is on the row, not just in the registry

It is not redundant with the output registry. The column is what stops compaction being a **one-way door**:
once files from several Consignments are merged, the registry can still say which files *were* produced, but
without a per-row id nothing can say which rows came from which Consignment — so a targeted supersede or a
replace-by-Consignment becomes impossible on compacted data. It is also a sortable id, so range scans over it
are cheap. ⚠ Add it **before there is data that lacks it**; backfilling it means re-deriving provenance that
no longer exists.

**Incident dedup by `correlationId` is advisory, not enforced.** `ObjectService.active(...)` looks for an open
Incident with the same correlation id; there is **no uniqueness constraint** in the schema. Concurrent
evaluations can therefore open duplicates. Treat the dedup as best-effort and do not build a guarantee on it.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*

## Proving the JDBC stores on real PostgreSQL (DAT-6)

`PostgresStateStoreTest` opens JDBC-backed store classes against a real server and round-trips each one
(nine until 2026-09-12, when A3 added the event store, delivery receipts and the dedup ledger — the last
two being the gap DAT-6's own coverage claim had), plus the run lease since B1 and `DbInboxRegistry` since
2026-09-13 — **fifteen** store classes in all.

✅ **The roster is sixteen families and COVERAGE IS NOW COMPLETE** (re-measured 2026-09-16). ⚠ This
paragraph used to claim **thirteen** store classes, put the roster one short, and name the acquisition
ledger as the one still uncovered — three numbers and a named gap, all stale: it is round-tripped at
`PostgresStateStoreTest`, and `INBOX_REGISTRY` had joined the enum without this paragraph moving.
🔴 **Do not re-open that "gap" — measure before believing it.** ⛔ And measure with a probe that can
return a hit: a count of `Db*.open(` call sites reports **fourteen** and drops `DbDedupLedger`, which is
built with `new DbDedupLedger(conn)`. The two idioms are the whole discrepancy, and a probe
matching only one of them reports an absence that is not there.

⚠ **That count is mirrored in nine places** and was missed by hand twice in two shifts, so
`tools/check-family-count.mjs` now fails the build when any of them drifts from
`OperationalDb.Family`.
⛔ **"Covered" is not "verified".** Every method in that class `assumeTrue`s on a configured server, so with
no `INSPECTO_TEST_PG_URL` the whole class SKIPS — the three added in A3 have never executed anywhere. Read
the skip count, not the test count.
Its load-bearing case is `DbJobRunStore.metrics`: p50/p95 are the one piece of non-portable SQL — DuckDB's
`quantile_cont` versus Postgres's `percentile_cont(..) WITHIN GROUP` — so only a real engine pins the
dialect fix.

**It needs a server you supply** (operator decision 2026-09-07). The embedded-Postgres harness and its
per-platform binaries are gone; only the JDBC *client driver* remains, so Postgres is installed separately
or pointed at:

```bash
mvn -o test -pl inspecto -am -Dtest=PostgresStateStoreTest     -Dinspecto.test.pg.url='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=…'
```

`INSPECTO_TEST_PG_URL` works too. With neither, all 11 methods report **SKIPPED with the reason** — the
skip is per-test, not on `@BeforeAll`, because an assumption there aborts the container and surefire
prints `Tests run: 0, Skipped: 0`, hiding the absent coverage entirely.

⚠ **Each run creates and drops its own schema** (`inspecto_test_<nanos>`, reached via `currentSchema`).
The assertions are exact counts, so they need a clean database and a shared server is not one — never
point this at a default schema you care about, and never remove the `@AfterAll` drop.
