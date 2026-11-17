# C12 — upstream report drafts (MoSCoW C12)

**Status: FILED 2026-11-17** (operator go-ahead, as drafted; the DuckDB environment line names only the platform it was reproduced on): DuckDB [#28947](https://github.com/duckdb/duckdb/issues/28947), CodeGraph [colbymchenry/codegraph#3320](https://github.com/colbymchenry/codegraph/issues/3320) (the repository of the installed `@colbymchenry/codegraph` 1.6.0), eoiagent [jotder/inspect-agent#1](https://github.com/jotder/inspect-agent/issues/1). Archived; the links are on the board rows. Originally: **DRAFTS, NOT FILED.** The operator decided on 2026-11-16 (MoSCoW §8 C12) that these three reports are filed before the
4.0.0 tag. Filing one publishes text outside this repo, so each needs the operator's go-ahead on its final wording
and target. When all three are filed, record each link on its board row, then `git mv` this page to
`archived-documents/plans-archive/`.

Rules for the text: no install, client, user or host names; a repro that runs on its own; the version we pin.

## 1. DuckDB — `window_self_join` loses hive-partition pruning (board `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1`)

- **Target:** the `duckdb/duckdb` issue tracker (bug report form).
- **Pinned version:** DuckDB 2.0.0 (JDBC `org.duckdb:duckdb_jdbc:2.0.0.0`). 1.5.x does not show it.
- **Our evidence:** `platform/inspecto-util/src/test/java/com/gamma/util/DuckDbWindowSelfJoinWorkaroundTest.java`
  (the canary test goes red when a driver stops miscounting).

**Title:** `window_self_join` rewrite ignores hive-partition filter: windowed aggregate counts pruned partitions

**Body:**

> **What happens.** A window aggregate with `PARTITION BY` over a hive-partitioned `read_parquet`, filtered on the
> partition column, counts rows from partitions the filter excludes. Disabling the `window_self_join` optimizer
> gives the right answer, so the rewritten plan seems to copy the scan without its partition filter.
>
> **Repro (DuckDB 2.0.0, CLI or any client):**
> ```sql
> COPY (SELECT * FROM (VALUES ('a','x',DATE '2026-07-01'), ('b','x',DATE '2026-07-01'),
>                             ('c','x',DATE '2026-07-02'), ('d','y',DATE '2026-07-01')) t(k, grp, d))
>   TO 'p' (FORMAT PARQUET, PARTITION_BY (d));
>
> SELECT n FROM (
>   SELECT k, count(*) OVER (PARTITION BY grp) AS n
>   FROM read_parquet('p/**/*.parquet', hive_partitioning = true)
>   WHERE d = DATE '2026-07-01'
> ) WHERE k = 'a';
> ```
> **Expected:** `2` (on 2026-07-01, group `x` holds rows `a` and `b`). **Actual:** `3`; row `c` from the
> 2026-07-02 partition is counted.
>
> `SET disabled_optimizers = 'window_self_join';` then the same query returns `2`. Other `PARTITION BY` aggregates
> (for example `sum(...) OVER`) are affected the same way. DuckDB 1.5.x returns `2`.
>
> **Environment:** DuckDB 2.0.0 (JDBC 2.0.0.0), Windows 11 and Linux, x86_64.

## 2. CodeGraph — `affected` returns cross-language and larger-than-the-corpus results (board `CODEGRAPH-AFFECTED-UNUSABLE-1`)

- **Target:** the CodeGraph project's issue tracker. ⚠ **The operator confirms the repository** before filing (this
  repo records only the installed version, v1.6.0).
- **Our evidence:** the board row; `CLAUDE.md` § CodeGraph and the `codegraph` skill (the guidance that strikes
  `affected`).

**Title:** `codegraph affected` over-reports: returns tests in other languages and more files than the repo holds

**Body:**

> **Version:** codegraph 1.6.0, on a mixed Java (Maven reactor) and TypeScript (Angular) repository.
>
> **What happens.** `codegraph affected <file>` returns far more test files than can depend on the input, so a
> test selection built from it becomes a full run:
> - for one Angular service (`*.service.ts`) it returned 953 Java test files, none of which can import a
>   TypeScript file;
> - for one Java class it returned 1078 files, while the whole checkout holds 1059 test files. The result is
>   larger than the corpus, which suggests duplicates or paths outside the index.
>
> `codegraph impact` and `codegraph query` on the same symbols give sound, narrow answers, so the graph itself
> seems right and the defect is in how `affected` walks or unions it.
>
> **Expected:** only tests that reach the changed file through the graph, never a test in another language with no
> edge to it, and never more files than exist.
>
> **Repro outline:** any repository with both a Java and a TypeScript tree; run `codegraph affected` on a TS
> service file and count the `.java` results; run it on a widely used Java class and compare the count with
> `git ls-files '*Test.java' | wc -l`.

## 3. eoiagent — expose the dry-run seam on `PlatformBuilder` (board `AGT-5`)

- **Target:** `jotder/inspect-agent` (the operator's own upstream).
- **Pinned version:** `eoiagent-platform` `0.2.0-SNAPSHOT` (root `pom.xml` `eoiagent.version`).

**Title:** `PlatformBuilder` has no `dryRunProvider(DryRunProvider)`: the dry-run type ships but cannot be wired

**Body:**

> `DryRunProvider` ships in `eoiagent-platform`, but `PlatformBuilder` exposes only `approvalHandler(...)` and
> `approvalDecisionStore(...)` (checked with `javap` on the 0.2.0-SNAPSHOT jar). A host cannot register a per-tool
> dry run, so it has to keep its own previewer beside the approval gate.
>
> **Ask:** add `PlatformBuilder.dryRunProvider(DryRunProvider)` and pass it to the gate builder, so a tool call
> that needs approval can show the host's dry-run preview to the approver. A release (or a tagged snapshot) with it
> lets the host replace its own previewer.

## References

- `docs/okf/product/layer/platform/plan-architecture-moscow.md` §8 C12
- `docs/BACKLOG.md` rows `DUCKDB-2-WINDOW-SELF-JOIN-UPSTREAM-1`, `CODEGRAPH-AFFECTED-UNUSABLE-1`, `AGT-5`
