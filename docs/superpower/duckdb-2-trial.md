# DuckDB 2.0 snapshot trial (2026-10-03) - BLOCKED, nothing to measure

Trial only, never for master. Worktree fast-forwarded to `1da0ad734`.

## Verdict: the snapshot cannot be consumed. No compile or test result exists.

- `2.0-2848c61d-SNAPSHOT` is NOT in the local `~/.m2` (`1.5.2.1`, `1.5.6.0` (trial-downloaded) and a stray `1.5.6` are).
- It IS listed online. `https://central.sonatype.com/repository/maven-snapshots/org/duckdb/duckdb_jdbc/maven-metadata.xml`
  names it as `latest`, so the repository was reachable. It was added through a throwaway `-s` settings file, nothing committed.
- The version's own `maven-metadata.xml` publishes exactly ONE artifact: classifier `windows_arm64`,
  `2.0-2848c61d-20260716.180839-11`. There is no classifier-less `duckdb_jdbc` jar.
- The same holds for all five other 2.0 snapshots checked (`a43a3b49`, `ad35f054`, `cddf0e38`, `3a986521`, `93d59aa6`).
  Each lists only `windows_arm64`. The snapshot publish looks partial, or platform-split.
- Maven stopped at `inspecto-util` (the first module on the DuckDB dependency):
  `Could not find artifact org.duckdb:duckdb_jdbc:jar:2.0-2848c61d-SNAPSHOT in sn`.
  The reactor was `-Pedition-enterprise -pl inspecto-la-storage,inspecto-la-core,inspecto-la-api,inspecto-geo-link -am`,
  JDK 27. Every later module, all four LA modules included, was SKIPPED.

## Not answered

Compile and test status of the LA modules, Parquet partition pruning, `md5_number_lower` bucket pruning,
`hash()` semantics and `read_parquet` hive partitioning on 2.0 are all untested.

## What would unblock it

1. A 2.0 snapshot that carries the main jar, or a published 2.0 release. Re-check the metadata first:
   `curl .../duckdb_jdbc/<ver>/maven-metadata.xml` must list a classifier-less jar.
2. Or a local build of DuckDB 2.0 JDBC installed into `~/.m2`.
3. Or building on windows-arm64 with `<classifier>windows_arm64</classifier>`. This is untested, and the
   dependency declaration lives in the root pom (~line 395), so it would be a pom change for trial only.

Re-run recipe: a settings file adding `https://central.sonatype.com/repository/maven-snapshots/` with
snapshots enabled, then `-Dduckdb.version=<ver>`. In PowerShell, quote every dotted `-D` argument or it is split.

## Recommendation: NO-GO for now. Not a regression finding, an availability one. Retry when a full 2.0 artifact is published.

## Update 2026-10-03: the 1.5.6.0 bump

JDBC `1.5.6.0` (four-part Maven version) resolved and the DuckDB-touching reactor (26 modules, -Pedition-enterprise) passed with it; `duckdb.version` is now `1.5.6.0` in the root pom. 2.0 remains blocked as above.

## DuckDB v1.5.6 release notes - what touches this repo (2026-10-03)

Read from the v1.5.6 release notes only (v1.5.3 to v1.5.5 notes not read). Bugfix release; nothing breaking listed.

- Parquet: a VARIANT-metadata fix (no `sorted_strings`), and geometry row-group pruning now copes with nulls and empties. Neither is a path this repo uses today.
- Hive partitioning: partition columns now resolve for non-pushdown table functions, and MAX over a hive partition column after file pruning was fixed. Relevant to `read_parquet(hive_partitioning)` readers (LA storage); re-run the pruning measurements if a result ever looked odd.
- ICU / strptime: "ICU strptime leaking time zone state between rows" is fixed; tz data moves to 2026c/2026d. Relevant to timestamp parsing and the session-TimeZone notes in `docs/okf/backend/control-plane/jobs.md`.
- WAL: the main WAL handle is closed before the rename during recovery (Windows-relevant).
- Extensions: C-API symbol versioning unified and v1 APIs stabilised; httpfs/delta/iceberg bumped. Extensions stay ABI-bound to the exact engine version.
- md5/hash functions and JDBC driver behaviour: no entry in the notes.

Extension staging: `tools/fetch-duckdb-extensions.mjs` and `inspecto/package.ps1` derive the ABI dir from the first three components of `duckdb.version`, so `1.5.6.0` maps to `v1.5.6`. No committed manifest lists per-version dirs, so nothing needs editing. The local `~/.duckdb/extensions` already holds `v1.5.6`. Extensions were not downloaded; the CI/bundle fetch must supply `v1.5.6` binaries (excel, ducklake, postgres_scanner, httpfs, aws).

Pins aligned: `tools/templates/step/pom.xml` and `tools/templates/nodetype/pom.xml` test-scope DuckDB 1.5.2.1 -> 1.5.6.0 (`packs-dev/acme.redact` does not exist in this tree). Dated measurements "probed on 1.5.2.1" are left as history.
