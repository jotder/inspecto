---
type: Concept
title: DuckDB Integration — Sealed connections, time zones and aggregate state
description: Part 2 of 2 of DuckDB Integration: Every ingest connection is sealed (SEC-INGEST-EXPR-EXTERNAL-ACCESS-1, 2026-09-30); Every in-memory scratch connection is sealed by default (ENGINE-INMEMORY-UNSEALED-1, 2026-10-03); The source time zone for temporal data; Aggregate state cannot be persisted and restored (§7.5 probe)
tags: [engine, duckdb, performance, appender, threads]
timestamp: 2026-06-28T00:00:00Z
---

# DuckDB Integration — Sealed connections, time zones and aggregate state

Part 2 of 2 of [DuckDB Integration](spec-duckdb.md) · previous: [DuckDB 2.0.0.0, the pinned version](spec-duckdb-version-pin.md).

## Every ingest connection is sealed (`SEC-INGEST-EXPR-EXTERNAL-ACCESS-1`, 2026-09-30)

A schema mapping's `fn: custom` expression (and the legacy `EXPR`) is spliced verbatim into ingest SQL, and
Pipeline/schema files are authorable by any `canAuthorWorkbench` holder, including through a Space import.
Until 2026-09-30 the ingest connection ran at DuckDB defaults, so `(SELECT content FROM read_text('/etc/…'))`
landed a host file in the store, `read_csv`/`read_parquet` read another Space's data, and an `http://` URL
autoloaded httpfs (SSRF — verified: the unsealed connection dialled the address).

- **One seam.** `ConsignmentIngestStrategy.configure(conn, cfg, tempDb, inputs)` is called by every ingest
  lane — `CsvIngestStrategy` (native + Java parse), `UnionModeIngester`, `GenerationModeIngester` and
  `DrainCommand` (inputs = the parked Parquet files). Push ingest (`POST /streams/{id}/records`) writes into
  the inbox and rides the same lanes. After the resource settings it calls
  `SqlSandbox.disableExtensionAutoload` and then `SqlSandbox.sealAllowing(conn, ingestAllowedDirs(...))`:
  external access off, configuration locked.
- **The allowlist** (`ingestAllowedDirs`): the spill dir, this Pipeline's own `dirs:` (poll, database,
  backup, temp, errors, quarantine, markers, manifests), every `sinks[]` database, and each input file's
  directory. NOT the temp DB's own directory — the open database needs no file permission, and with no
  `dirs.temp` it would be `java.io.tmpdir`. Everything else is refused by DuckDB itself ("file system
  operations are disabled by configuration"), whatever a lexical check missed.
- **The allowlist is author-controlled, so its entries are judged too** (adversarial verification of the
  first fix: `dirs.errors: <Space root>` passed the jail, which only contains a path to the Space base, and
  then `read_text('<S>/config.secrets/.pending-changes.key')`, `glob('<S>/**')` and `ATTACH` all worked).
  Two layers now:
  - **write gate / import** — `PathJail.refuseDataHome` refuses a Pipeline `dirs.*`, `sinks[].database`,
    `output.ducklake.data_path` or `processing.duckdb.temp_directory` that is (or contains) the Space root,
    is under its `config/` tree, or names a `*.secrets` directory (`refuseSecrets`, now public).
    `ConfigSafetyValidator.checkDataHome` applies it on every save; `ImportCapabilityGuard.checkFiles` applies
    it to every carried `*_pipeline.toon` at every import door (403, nothing written) — relative values
    against a stand-in Space base, absolute ones against every hosted Space (`DiscoveredRoots.all()`).
  - **run time** — `PathJail.readAllowlistRefusal(dir)` (public, in `inspecto-config`, so every sealed
    connection built from authored config reuses it — the enrichment seal included) names why a dir may not be
    allowlisted: a `*.secrets` directory, anything under a directory named `config`, a dir that is or contains
    a registered Space base or holds a `config/` subdirectory (the Space shape, for CLI entry points that
    register none), or `java.io.tmpdir` itself / an ancestor of it. The ingest seal refuses the run
    (`SQLException`, batch fails) on any non-null answer. Fail closed.
  - **the temp DB** stays where `openTempDb` puts it (`dirs.temp`, else `java.io.tmpdir`), but its directory
    is no longer allowlisted, so no per-run temp directory is needed; `IngestExpressionSandboxTest
    .theAllowlistNeverContainsTheSystemTempDirectory` pins it (with tmpdir allowlisted the three
    host/other-Space reads all landed).
- **Every comparison is canonical, never lexical** (round 3: `dirs.poll: <S>/config.` and `CONFIG` walked
  around a `startsWith(base.resolve("config"))` — Windows strips a trailing dot and ignores case, so the sealed
  connection could `read_text('<S>/config./orders/p.toon')`). `PathJail.canonical(p)` = absolute + normalised
  + the real path of its nearest existing ancestor + each segment through `canonicalSegment` (trailing dots and
  spaces stripped on every platform — fail closed — and lower-cased on Windows). `refuseDataHome`,
  `readAllowlistRefusal` (Space bases and tmpdir too) and `spaceDirOf` all compare that form.
- **The restricted-quarantine sibling needs no rule of its own**: a refused file lands at `<quarantine>/../restricted-quarantine`, a CHILD of the quarantine's parent, so it is never the Space root, and it reaches `config/` or a `*.secrets` dir only when the quarantine sits directly in one — which is refused. Pinned as a property in `ConfigSafetyValidatorTest`.
- ⚠ **Residual — links.** DuckDB's `allowed_directories` check is lexical: a symlink or junction INSIDE an
  allowed directory is followed. Config cannot plant one (no config key creates a link), so it is recorded,
  not fixed; an operator who links a data dir elsewhere extends the ingest connection's reach to that target.
- **Extensions load BEFORE the seal.** After the lock a `LOAD` is file access and is refused, and
  `duckdb_extensions()` itself scans the extension directory, so it cannot be used as an "already loaded?"
  probe. `configure` pre-loads `excel` for an `xlsx` Pipeline; `ExcelExtension.ensureLoaded` returns early
  when `read_xlsx` is already in `duckdb_functions()`. ⚠ A future lane that needs another extension must
  load it in `configure`, before the seal.
- **Deliberately not allowed:** an object-store `dirs.database` (it would need httpfs plus URL prefixes; it
  is refused at the write gate today, so it stays refused here — fail closed).
- **Still works on a sealed connection:** scalar SQL the content packs use — `error()`, lambdas,
  `list_transform`, `regexp_matches` (the payment-fraud PAN tripwire shape).
- **Pinned by** `IngestExpressionSandboxTest` (real path, `CollectorProcessor.run`; plus `configure` refusing a Space-root allowlist, an `errors` dir at the Space root landing nothing, and the key / `glob` / `ATTACH` / `SET threads` / `SET allowed_directories` all refused after the seal — so dropping `lock_configuration` goes red), `ConfigSafetyValidatorTest.aPipelineDataHome*` and `ControlApiImportCapabilityGateTest.aRawImportPipelineWhoseDataHomeIsTheSpaceRootOrConfigIsRefused`. First-fix cases: `read_text` of a host
  file, `read_csv` and `read_parquet` of another Space's data land nothing; an `http://` URL is refused by
  the configuration (not by a failed fetch) and `SET enable_external_access=true` is refused after the lock;
  a custom mapping with `regexp` + `error()` + a lambda still lands. All four refusal cases go red with the
  seal removed.

### Enrichment connections are guarded and sealed too (`SEC-ENRICH-TRANSFORM-SQL-UNSEALED-1`, 2026-09-30)

An Enrichment's `transform` is spliced into `CREATE TABLE __enriched AS …`, so until 2026-09-30 a
`;`-chained `COPY … TO`, `ATTACH`, `LOAD`/`INSTALL`, or a `read_text` / replacement-scan read ran verbatim
on a DuckDB-defaults connection. **Reproduced before the fix:** the capability-free
`POST /enrichment/preview` returned a host file's content with a 200, and all five attack shapes ran on
both the run and the preview path. Authoring an Enrichment needs `canAuthorWorkbench`
(`POST /enrichment`, and `POST /bundle/import` carries the `enrichment` kind); the preview needed nothing until it was gated the same day.

- **Two layers, both in `EnrichmentEngine`, on both `runResult` and `preview`.** `guardedTransform`
  refuses any `SqlGuard` finding (one read-only `SELECT`/`WITH`, no file/extension functions, no path in a
  relation position) with `IllegalArgumentException("… transform refused: …")` before any view exists —
  the preview route maps it to 422. Then `SqlSandbox.sealAllowing(conn, allowedDirs(...))`, before the
  reference and `input` views are created, so the views' own reads prove the allowlist.
- **The allowlist** (`allowedDirs`): the scratch DB's directory, the global
  `-Dprocessing.duckdb.temp_directory` when set, `input.database`, `output.database` (routed destinations
  live under it) and its `_quarantine` sibling, each `path:` reference's directory, and each by-name
  reference's producing Pipeline's `dirs.database`.
- **The allowlist is filtered, fail closed.** Every entry but the scratch dir comes from authored config, so
  `EnrichmentEngine.seal` refuses the run/preview when `PathJail.readAllowlistRefusal` rejects any entry (a
  Space root, a `config/` tree, a `*.secrets` dir, tmpdir) — otherwise `output.database: .` or a `path:`
  reference at `config.secrets/.pending-changes.key` would hand the connection the Pending Change key. The
  same dirs are **data homes** at the write gate (`ConfigSafetyValidator.checkEnrichment` → `checkDataHome`
  for `input/output.database`, `refuseDataHome` on each `path:` reference's directory) and at import
  (`ImportCapabilityGuard.refuseDataHomes` reads `*_enrich.toon` too — 403, nothing written). ⚠ A `path:`
  reference file sitting directly in the Space root is therefore refused: its directory is the root.
- **`POST /enrichment/preview` is gated `canAuthorWorkbench`** (it was un-gated until 2026-09-30).
- ⚠ **The scratch DB gets its own fresh directory per call.** A DB made straight in `java.io.tmpdir`
  allows the WHOLE tmpdir — found by the seal test, which read a file from a `@TempDir` sibling until the
  scratch moved into `Files.createTempDirectory`. (The ingest seal allows its temp DB's directory the same
  way; there it is the Pipeline's own temp dir, not the system tmpdir, unless none is configured.)
- **Pinned by** `EnrichmentTransformSandboxTest` (engine: the five attacks on both paths, a legitimate
  transform, the allowlist, and the seal refusing a file outside it) and
  `ControlApiEnrichmentPreviewTest.aTransformThatReadsAHostFileIsRefused` (real HTTP: red with both
  layers removed — 200 with the file's content).

### The Pipeline dry run is sealed too (`SEC-DRYRUN-EXPR-UNSEALED-1`, 2026-09-30)

`PipelineDryRun.runSeeded` — behind `POST /pipelines/authored/{id}/dry-run` and `POST …/run?to=` — runs the authored
graph (`fn: custom` mapping expressions, `transform.sql` columns, filters, contributed Steps) over seeded sample
rows. No Decision Rule runs in the walk. Until 2026-09-30 its connection used DuckDB defaults, so a mapping
expression could `read_text` any host file.

- **Sealed after seeding, before the walk.** The seed tables are trusted inserts. After them comes
  `SqlSandbox.sealAllowing(conn, allowed)`.
- **The allowlist:** the run's own scratch dir (`Files.createTempDirectory("dryrun_")`, never tmpdir itself), the
  global `-Dprocessing.duckdb.temp_directory` when set, and, for each `transform.join` node, what its
  `reference` reads as declared by `RowShaper.ReferenceResolver`: `readDirs` (directories) and `readFiles`
  (single files). Both default to none. The route's resolver (`PipelineGraphRoutes.dryRunReferences`) declares
  - a `path:` reference as the **one file** (jailed first), admitted by exact path through
    `SqlSandbox.sealAllowing(conn, dirs, files)` (`allowed_paths`), so a sibling file in the same directory
    stays refused;
  - a by-name reference as the producing Pipeline's `dirs.database`, **only if that Pipeline declares
    `produces: reference`** (as `ReferenceReader.sqlFor` requires). A join naming any other loaded Pipeline
    does not open its store, so a mapping expression in the same graph cannot read it.
  References still resolve lazily. Their views are created after the seal, so the views' reads prove the allowlist.
- **Fail closed.** Every entry passes `PathJail.readAllowlistRefusal`; a reference file is judged by its
  directory too, so a file directly in the Space data root, a `config/` or a `*.secrets` tree is refused. A refused entry, such as a Space root, a
  `config/` tree, a `*.secrets` dir, tmpdir or a restricted store, refuses the whole run with
  `IllegalArgumentException`. The route maps that to 400.
- ⚠ **A glob `path:` reference fails closed.** The allowlist takes literal files, so `dir/*.csv` names no
  file: the join's view creation is refused (422 `JOIN_REFERENCE_MISSING`, or a 4xx from the path check) and no
  sibling is ever read. Point the reference at one file, or make the files a Pipeline that declares
  `produces: reference` and join it by name (its whole `dirs.database` is admitted). A native (Windows
  backslash) spelling of the same file is the same entry, since the path is normalised first.
- **The scratch dir is removed recursively** (`PipelineDryRun.removeScratch`): DuckDB leaves a `<db>.tmp` spill
  dir in it when it spills, and `deleteIfExists` on a non-empty dir fails silently. It is removed on every exit: a mid-walk
  exception, a seal refusal, and a failure creating the scratch DB.
- **Pinned by** `PipelineDryRunTest` (engine): `read_text` of a host file and of `config.secrets/…` → DuckDB
  `Permission Error`; a sealed join still reads its declared dir; a `path:` file's sibling is refused; a refused
  dir fails closed; the scratch dir is removed with its spill. Over real HTTP: `ControlApiDryRunBlindSpotsTest`
  (a sibling of a `path:` reference, a host file, a reference file in the data root → 400) and
  `ControlApiPreviewReferenceJailTest` (a non-reference by-name join opens nothing; a `produces: reference`
  Parquet store still joins, 200). Each goes red with its fix reverted (the glob test pins the fail-closed
  outcome rather than a single line of code).

### The at-rest Pipeline Job is sealed too (`SEC-ATREST-PIPELINE-UNSEALED-1`, 2026-10-01)

`PipelineJobRunner.execute` (the only caller is `JobService`) runs the authored graph over `SourceStoreReader`
views on a scratch DuckDB. The same authored SQL as the dry run runs there (`fn: custom` expressions,
`transform.sql`), so it was the same hole.

- **Sealed after the source-store views, before the walk** (`PipelineJobRunner.seal`, `SqlSandbox.sealAllowing(conn,
  dirs, files)`). Allowlist: a per-run scratch dir (the scratch DB now lives in it, so its `<db>.tmp` spill is
  covered; removed recursively via `PipelineDryRun.removeScratch`, now `public`), the global spill dir, each seed's
  and each on-disk sink's `<data_dir>/<store>`, and each `transform.join` reference's `readDirs`/`readFiles`. The
  runner's own resolver declares them: a by-name reference is the producer's `dirs.database` (only if it declares
  `produces: reference`), a `path:` reference is the one file. Reference views are still created lazily, after the seal.
- **Fail closed.** Every entry, and a reference file's parent, goes through `PathJail.readAllowlistRefusal`. A store
  that resolves outside `data_dir` (or to `data_dir` itself) refuses the run, because `readAllowlistRefusal` alone
  would not stop a `../../etc` store name.
- **Containment is canonical and runs before any view is created** (`PipelineJobRunner.storeDirs`): each store dir
  is `PathJail.canonical` (real path, trailing dots/spaces, case) and must sit strictly under the canonical
  `data_dir`, so `../x`, an absolute store, `.`, `a/..`, a link or junction store pointing out, and a Windows `...`
  store are refused ("outside the data directory") before anything is read. The allowlist entry is the canonical path.
  `incremental_column` containing a `"` is refused up front (it is spliced as a quoted identifier before the seal).
  `PipelineDryRun.removeScratch` deletes recursively, so it only acts on a `dryrun_*` / `flowjob_*` direct child of
  the canonical `java.io.tmpdir`.
- ⚠ **What the seal does not bound.** Pipelines sharing a `data_dir` can already read each other's stores: the seal
  bounds hosts, secrets and URLs, not cross-Pipeline reads. Known gap: a glob outside `data_dir` is bound (a Parquet
  footer read) before the store refusal fires on the seal path; the early containment check closes it for stores, and
  what remains is metadata only.
- **DuckLake is unaffected.** `registerInLakehouse` → `DuckLakeRegistrar.registerInto` opens its own connection.
- **Pinned by** `PipelineJobRunnerTest`: `read_text` of a host file and of `config.secrets/…` → `Permission Error`; a
  sealed run still joins its `path:` reference but not a sibling of it. All three go red with the `seal(...)` call
  removed; the existing DuckLake-registration and join tests in that class cover the positive paths.

## Every in-memory scratch connection is sealed by default (`ENGINE-INMEMORY-UNSEALED-1`, 2026-10-03)

- `DuckDbUtil.openInMemory(spill[, dirs[, beforeSeal]])` turns autoinstall + autoload off, runs the trusted
  `beforeSeal` (e.g. `ExcelExtension::ensureLoaded`), then `allowed_directories` = `dirs` and
  `enable_external_access=false`. No dirs ⇒ no file at all. Each caller declares what it touches; a Dataset
  relation reader declares `DatasetRelation.readRoots` (data root + a `shared/` ref's Exchange snapshot).
- **Not locked by default** — measured: after the seal, `allowed_directories` / `enable_external_access` cannot
  be changed and `LOAD` is refused even with autoload re-enabled; other settings stay settable. Authored-text
  callers add `DuckDbUtil.lockConfiguration`.
- ⚠ The `temp_directory` subtree is **implicitly reachable** (read + write) under the seal: a spill dir must be
  dedicated (`.duckdb_tmp`), never a temp or data root. The `null` default is `<java.io.tmpdir>/.duckdb_tmp`.
- **No opt-in remains.** The LA index build (`IndexBuilder`) was the last holder of `openInMemoryWithFileAccess`; it now
  declares its roots instead: `DatasetProvider.readRoots` → `IndexBuildService.Relation.readRoots` →
  `IndexBuilder.Request.readRoots`, and the connection is sealed to those plus the store directory (stage, `.spill`,
  parent version, `.delta`). `countRows(sql, timeout, roots)` takes the roots too; `verify` seals to the version dir.
  The spill is passed to `openInMemory` (it sets `temp_directory`; a later `SET temp_directory` is refused). The
  opt-in method and its pin were deleted.
- Tests: `DuckDbInMemorySealTest` (stub-counted network refusal with a bare-connection twin; declared vs sibling
  dir), `MaterializeTaskTest.anAuthoredViewThatReadsAUrlNeverReachesTheNetwork`,
  `IndexBuilderTest.aBuildReadsOnlyDeclaredRootsAndNeverTheNetwork` (an undeclared dir is refused, its twin builds).

## The source time zone for temporal data

**SHIPPED 2026-08-29** (engine + config `44ecef76`, surfaces `dd02d377`). Plan + the full live-probe
transcript: [`archived-documents/plans-archive/source-timezone-plan.md`](../../../../archived-documents/plans-archive/source-timezone-plan.md).

A parsed wall-clock timestamp used to mean whatever the *host* running the batch thought it meant.
Temporal columns now carry a declared **source zone** and are normalised to **naive UTC** at parse
time, so a value stops depending on which box processed it.

* **The policy is three tiers, precedence `raw.fields[].timezone_column` > `raw.fields[].timezone` >
  `parsing.source_timezone` > none.** `SourceZones` (`inspecto-etl`) is the one home for the concept —
  `validate` / `resolve` / `wrap`. `none` is the default and is **byte-identical to the old behaviour**,
  pinned by a test.
* **The compile shape is `timezone('UTC', timezone(Z, <naive-parse>))`** — measured, not reasoned. It
  yields a naive-UTC `TIMESTAMP`, which is what keeps everything downstream (partitions, BI grains,
  dedup) naive and consistent.
* **Four temporal sites, not the "one choke point" the board row assumed.** Applied at
  `SchemaFieldTypes.castSql` (the mapped column), `TransformCompiler.dateExpr` (`DATE_*` partitions and
  `__event_time`) and `TransformCompiler.concatDt`. ⛔ **`FILENAME_DATE` and `DATE` are zone-exempt by
  design** — a date has no instant to shift, and shifting one moves a file dated `20260829` into the
  previous day for any negative offset. Documented at the method, not silently skipped. The `EXPR` rule
  is out of scope by the same rule that leaves it un-sandboxed.
* 🔴 **The `TIMESTAMPTZ` host-zone trap is CLOSED by refusal, not by a default.** A naive value cast
  `::TIMESTAMPTZ` is interpreted in the **session** zone (i.e. the host — see the bullet above), so a
  `TIMESTAMPTZ` field with **no** zone source is now refused at config load
  (`PipelineConfigParser.requireZoneForTimestampTz`, called at all three schema-resolution points:
  single, multi, segment). No shipped config declared one, so nothing broke.
* 🔴 **`TRY()` does NOT catch DuckDB's *Unknown TimeZone*.** *Not implemented* errors are outside what
  `TRY` intercepts, so one bad cell in a `timezone_column` would kill the whole batch with no soft
  failure available. A per-row zone therefore resolves through a **`pg_timezone_names()` lookup**, which
  yields NULL for an unknown or NULL zone — the same "bad value becomes NULL" contract every other
  coercion has, already counted by the cast-failure audit. ~2µs/row, paid only when a `timezone_column`
  is configured; the fixed-zone form compiles to a literal.
* 🔴 **The zone-column reference needs `CAST(… AS VARCHAR)`** — `lower()` binds only to VARCHAR, so a
  typed zone column was a binder error that killed the batch, precisely the failure the lookup exists to
  prevent. Found by a test, not by inspection.
* ⚠ **The gate is membership in `ZoneId.getAvailableZoneIds()`, and `ZoneId.of` alone is NOT a valid
  gate.** Offset forms (`+05:30`, `Z`) are **rejected by DuckDB** though `ZoneId.of` accepts them; the
  two engines disagree in both directions (DuckDB takes `utc`, Java does not; Java knows `UT`, DuckDB
  does not). Measured containment: all 604 Java zone ids appear in DuckDB's 638-row
  `pg_timezone_names()`, so the Java set is a proven-safe subset — pinned against the live engine by
  `everyZoneTheGateAdmitsIsOneDuckDbAccepts` rather than trusted.
* **ICU needed no work.** It is statically bundled, `loaded=true` on a bare `jdbc:duckdb:` connection,
  and named-zone arithmetic **survives the `SqlSandbox` seal** (`autoload_known_extensions=false` +
  `enable_external_access=false` + `lock_configuration=true`). This was the build's biggest assumed risk
  and it was not one.
* **JSON needed no work either, and the same is true for the same reason** (measured 2026-09-07 on
  `duckdb_jdbc 1.5.2.1`). `json` is statically linked, so `autoload_known_extensions=false` and a locked
  configuration are irrelevant to it: `json_extract`, `json_structure`, the `->>` operator and
  **`json_serialize_sql`** — which returns a statement's whole parsed AST as JSON — all work on a SEALED
  `SqlSandbox`, while `INSTALL excel` and re-opening `enable_external_access` still fail. Pinned by
  `SqlSandboxTest.jsonWorksOnASealedConnection`; it discharged AUTHORING-REDESIGN-1 (c)'s precondition.
  ⚠ **Do not generalise "statically linked" to every extension** — `excel` is the counter-example, and it
  is loaded through a 3-layer fail-closed path precisely because it is not.
* **DST-ambiguous and non-existent local times raise nothing** — Berlin `02:30` on both switch days
  resolves silently to `01:30` UTC. Nothing to guard; worth knowing.
* ⚠ **A blank cell in TOON's tabular field form is ABSENT, not empty.** `fields[N]{…}` declares one
  header per column, so a schema giving *any* field a zone writes `""` for all the others — null-only
  checks would refuse every such schema at load.
* **Surfaces** (`inspecto-ui`): a `source_timezone` select on the Grammar editor's **Types** tab across
  the **6**<!--count:ui-specced-frontends--> UI-specced frontends but Parquet (whose file declares its own types, U3) — parsing-level (no `delimited__` prefix, matching `encoding`/`compression`) and
  with **no default** — plus a **Source zone** column in the columns table, rendered only on rows whose <!-- vocab-allow: "Source zone" is the shipped UI column label for a temporal ORIGIN zone, not the acquisition entity -->
  type carries an instant. One shared vocabulary in `inspecto/schema/time-zones.ts`. The server's
  refusals on the schema and the pipeline write are the only ones *(the offline mock backend was deleted 2026-08-31)*.
  * ⚠ **`ConfigSpecs` needed nothing.** Parsing-block keys are frontend `AttributeSpec`s, and no backend
    allow-list gates an unknown config key (`ConfigSafetyValidator` is path-jail + output formats only),
    so the key saves through the control plane untouched. `date_formats` / `timestamp_formats` are not
    in `ConfigSpecs` either.
  * ⚠ **Placement deviates from the board row deliberately.** The row asked for the metadata grid; that
    grid is documented as Catalog-facing and *never read by the ETL*, and a source zone IS ETL-read. It
    went beside the **type it qualifies** in the columns table, on the DECIMAL-parameters precedent —
    self-limiting, so no timestamps means no column.
  * ⛔ **`timezone_column` has no editor, by decision.** Offering a per-row column beside ~418 zone names
    in one cell invites exactly the ambiguity the engine's mutual-exclusion rule prevents. A
    hand-authored one is **carried through a save** and shown read-only on its row, so the fixed-zone box
    cannot silently contradict it.
* 🔴 **Config-level key homes.** `parsing.source_timezone` is a **sibling of `delimited:`**, because it
  is format-agnostic — which means it had to be added to `PipelineConfigParser.mergeParsing`'s explicit
  **scalar allow-list**, the only path that carries a parsing-level scalar through. `timezone` /
  `timezone_column` are validated fail-closed in `Identifiers.validateSchema`; a `timezone_column`
  naming a field that does not exist is a load error. **`meta.domain.timezone` stays display-only** —
  activating it here would reverse the 2026-08-15 decision recorded in
  [`OperationsZone`](../processor/spec-jobs.md).
* 🔴 **A pre-existing UI defect this work exposed, fixed with it.** A `type: 'select'` asks in a MatDialog
  that the CDK attaches to `document.body`, so choosing an option never bubbles a click through the pane;
  all three `pipelines/*-definition` panes derived dirtiness from `@HostListener('click')`, so **Apply
  stayed greyed out over a choice just made** — for every select on those drawers, not just this one. A
  2816-test suite missed it and the preview caught it. Fixed with `@HostListener('document:click')` and
  pinned by a regression spec.
* 🔴 **The `%z` defect — GROUNDED and CLOSED FAIL-CLOSED 2026-08-29. The board row was wrong in
  three ways, and there is a fourth directive it never mentioned.** It is **not** the plain branch, it does **not** need a mixed list, and it is worse than
  "session-interpreted".
  * ✅ **Confirmed:** a format list mixing a `%z` pattern with a plain one unifies the `COALESCE` to
    `TIMESTAMP WITH TIME ZONE` (a plain-only list stays `TIMESTAMP`).
  * 🔴 **REFUTED — the plain branch is NOT corrupted by the mixing.** `SqlBuilder.appendCoalesce`
    appends a trailing `::TIMESTAMP`, which converts *back* through the same session zone, so the
    promotion round-trips to an identity. Measured: `2026-03-01 10:00:00` returns `10:00:00` under
    `Asia/Calcutta`, `Europe/Berlin`, `UTC` and `America/New_York` alike, plain-only list and mixed
    list, bare and wrapped in `SourceZones.toNaiveUtc`. The one exception is narrow and is its own
    bullet below.
  * 🔴 **The real defect is the `%z` branch, and a `%z`-ONLY list has it too — mixing is irrelevant.**
    `TRY_STRPTIME` parses the offset correctly, and then the trailing `::TIMESTAMP` **throws the
    instant away by rendering it in the HOST's wall clock**. The same input
    `2026-03-01 10:00:00+00:00` yields `15:30` · `11:00` · `10:00` · `05:00` under those four host
    zones. The correct naive-UTC value is `10:00`, reachable as `timezone('UTC', <the TIMESTAMPTZ>)`.
    This is exactly the host-dependence the rest of this section exists to remove.
  * 🔴 **With a declared source zone it is doubly wrong and still host-dependent.** A `%z` value under
    `toNaiveUtc(zone='Asia/Tokyo')` lands on `2026-03-01 06:30` on an `Asia/Calcutta` host and
    `2026-02-28 20:00` on an `America/New_York` one — **a different calendar day**. The host render
    happens first, so the declared zone is then applied to an already-corrupted wall clock.
  * 🔴 **A `DATE` column is affected too, and it feeds partitions.** `date_formats` containing `%z`
    puts `2026-03-01 23:00:00+00:00` on `2026-03-02` (Calcutta host) vs `2026-03-01` (New York host)
    — so `DATE_*` partition keys become host-dependent.
  * ⚠ **The one thing mixing does add** is a DST-gap shift on the plain branch: a local time that does
    not exist in the **host's** zone is silently moved forward (Berlin `2026-03-29 02:30:00` → `03:30`
    on a mixed list, `02:30` on a plain-only one). The ambiguous fall-back case (`2026-10-25 02:30:00`)
    is an identity.
  * ⚠ **Nothing validates a format string.** `ConfigValidator` only *warns* on an empty
    `timestamp_formats`; `%z` is unvalidated free text, offered by no UI list and refused by nothing.
  * **Still latent:** no `.toon` under `spaces/` uses `%z` (the only grep hits are binary parquet).
  * 🔴 **There are exactly TWO offending directives, not one: `%z` (numeric offset) AND `%Z` (zone
    name).** `%Z` behaves identically (`10:00:00 UTC` → `15:30` on a Calcutta host, `05:00` on a New
    York one) and is named nowhere in the row, the plan or the original probe. It was found by
    **sweeping every ASCII letter directive against the live engine** and reading the return type — not
    by reading the strptime documentation. Every other accepted directive returns a naive `TIMESTAMP`
    (`%n` returns `TIMESTAMP_NS`, also naive).
  * **✅ DECIDED (operator, 2026-08-29) and SHIPPED: fail closed.** `SourceZones.assertNoZoneDirective`
    refuses a `%z`/`%Z` format in `date_formats` or `timestamp_formats` at config load, called from
    `PipelineConfigParser`'s single parse site for those two keys — the same posture and the same
    neighbourhood as the zone-less `TIMESTAMPTZ` refusal above. The message names the key, the format,
    the directive and the fix (declare a zone instead). No shipped config was affected.
  * ⚠ **`%%` is an escaped literal percent**, so `'%%z'` is the two characters `%z` in the input text
    and parses naive — measured, and deliberately admitted. The scan consumes `%%` as a pair, so
    `'%%%z'` (a literal percent then a real directive) is still refused.
  * 🔴 **The guard's SCOPE is pinned by re-running the sweep in a test, and was falsified in BOTH
    directions before being trusted** — dropping `%Z` from the gate fails it with *"these lose their
    offset to the host zone but the gate admits them: [%Z]"*, and adding a harmless `%y` fails it with
    the converse. A DuckDB upgrade that introduces a third zone directive therefore breaks the build
    instead of shipping the corruption.
  * **The feature is NOT built, deliberately.** The original row wanted "an offset in the data should
    **WIN** over any configured zone"; that remains a separate, deliberate build (emit
    `timezone('UTC', …)` and make the data's offset a fifth precedence tier). ⛔ Do not reach it by
    relaxing this gate.
  * **✅ The authoring-time half SHIPPED 2026-08-29 — and it closed a real divergence.** The mock was
    **ahead of the server**: `onboarding.handler.ts` 422'd a bad `parsing.source_timezone` while **no
    Java route validated it on write at all**, so an author saw a refusal offline and a silent
    load-time failure against a real backend. `ConfigSpecs.pipeline()` now carries two ERROR
    `CrossFieldRule`s — `parsing-source-timezone-resolvable` and
    `parsing-formats-carry-no-zone-directive` — evaluated by `ConfigLoader.validate` and turned into a
    422 by `ConfigWriteRoutes`. This is the house pattern the `template-cannot-be-active` rule states
    outright: **the parser's load-time throw is what makes the guarantee hold for a hand-edited file;
    the rule is what makes it a 422 while the author is still present.**
  * 🔴 **The shared predicate lives in the LOWER module and is delegated to, not mirrored.**
    `inspecto-config` sits below `inspecto-etl`, so a rule in `ConfigSpecs` cannot import the engine's
    copy — the codebase's usual answer is a mirror plus a pinning test (see
    `processing.unpack.data_extensions`). These predicates need nothing from the engine, so they moved
    **down** into `com.gamma.config.spec.SourceZoneGrammar` and `SourceZones` delegates to it. One
    implementation, no pom change, and no second copy to drift.
  * ⚠ **Two same-looking zone checks, two different correct answers.** `SourceZoneGrammar.zoneRefusal`
    gates on `ZoneId.getAvailableZoneIds()` because the value reaches **DuckDB**; `ConfigSpecs.meta()`'s
    `domain-timezone-resolvable` uses plain `ZoneId.of` because that value is resolved by the **JVM**
    for date math and never reaches DuckDB. ⛔ Do not "unify" them.
  * ⚠ **Only the `delimited:` block is checked** for formats — a list authored at `parsing:` level is
    not read by `mergeParsing` at all, so refusing one there would 422 on something inert.
* **Dead code flagged, not touched:** `SqlBuilder.buildPartitionExpr` has no production caller (only
  `buildCastExpr` at `TransformCompiler` does).

Output is written via DuckDB `COPY` — see [output & sinks](../elt/spec-sinks.md).

## Aggregate state cannot be persisted and restored (§7.5 probe)

Measured on a live connection, and it decides the summary tier's representation:

* `approx_quantile` **cannot** `EXPORT_STATE` at all — DuckDB rejects it ("custom binders"). There is no
  mergeable state to persist.
* `approx_count_distinct` **can** export state; it casts to `BLOB` and survives a Parquet round trip — but
  there is **no `BLOB → AGGREGATE_STATE` cast back**. So it is *persistable but not restorable*, which for a
  rollup cache is the same as useless.

⇒ **The fixed-bucket histogram is the decided representation** for non-additive measures. Sketches would mean
pulling in Java DataSketches, which is a dependency decision, not a query trick. ⛔ Do not re-litigate this by
reading DuckDB's docs — the docs describe `EXPORT_STATE`; the probe is what found the missing return cast.

*Distilled 2026-09-07 from `consignment-elt-architecture.md` when that plan was archived ([archive copy](../../../../archived-documents/plans-archive/consignment-elt-architecture.md)).*
