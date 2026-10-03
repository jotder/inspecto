# DuckDB 2.0 snapshot trial (2026-10-03) - BLOCKED, nothing to measure

Trial only, never for master. Worktree fast-forwarded to `1da0ad734`.

## Verdict: the snapshot cannot be consumed. No compile or test result exists.

- `2.0-2848c61d-SNAPSHOT` is NOT in the local `~/.m2` (only `1.5.2.1` and `1.5.6` are).
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
