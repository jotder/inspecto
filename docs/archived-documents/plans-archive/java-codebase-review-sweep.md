# Java codebase review sweep — module by module

**Status:** IN FLIGHT (started 2026-08-18)
**Objective (`/goal`):** code-review the full Java codebase, one module at a time, simplify and fix,
changing design where warranted.

Sibling to the Angular sweep that closed 2026-08-17 (`bac64964`). Same shape: read every production
file in a module, confirm each finding against the call path before fixing, fix, re-verify.

## Baseline

- Branch `master`, `origin/master` = `bac64964` at start.
- Working tree clean apart from the standing untracked `spaces/**` operator data (never commit) and
  a root-level `architecture_and_design_review.md`.
- Pre-sweep reactor measurement: _pending_.

## Scope — 16 modules, ~105k lines of main Java

| # | Module | Main files / lines | Review | Fixes |
|---|---|---|---|---|
| 1 | `inspecto-api` | 1 / 35 | full | clean |
| 2 | `inspecto-util` | 34 / 4883 | full | 3 (#5, #6, #13) |
| 3 | `inspecto-config` | 18 / 2324 | full | 1 (#9) |
| 4 | `inspecto-sql` | 5 / 754 | full | 1 (#12) |
| 5 | `inspecto-etl` | 36 / 7081 | full | 3 (#1, #6, #11) |
| 6 | `inspecto-event` | 14 / 1711 | full | 1 (#2) |
| 7 | `inspecto-acquire` | 34 / 3500 | full | 1 (#4) |
| 8 | `inspecto-engine` | 274 / 40392 | **sampled** — `job` + `pipeline`(+`exec`) + `consignment`/`inspector`/`enrich`/`parse` full; `ops`/`catalog`/`query`/`notify` ~40 of ~95 | 3 (#7, #8, #10) |
| 9 | `inspecto` (`inspecto-processor`) | 129 / 25652 | **sampled** — dispatch chain, `WriteGates`, `ConfigRoutes`, `AuditTrail` in depth | 1 (#3) |
| 10 | `inspecto-agent` | 88 / 6014 | sampled | clean |
| 11 | `inspecto-agent-hosted` | 2 / 150 | full | clean |
| 12 | `inspecto-connectors` | 33 / 5399 | sampled | clean (1 reuse item) |
| 13 | `inspecto-intelligence` | 43 / 6593 | sampled | clean |
| 14 | `inspecto-security` | 3 / 400 | full | filed (audience decision) |
| 15 | `inspecto-policy` | 1 / 191 | full | clean |

`asn-parser/asn-decoders` has no main sources in this checkout (separate reactor, facade only).

## Ground rules for this sweep

- A reported finding is a **hypothesis** until the call path is read. Ground before fixing —
  the BACKLOG's own history has a row's stated cause wrong 7+ times, and its severity wrong too.
- Both profile gates matter: `mvn -o clean test` misses `inspecto-security` and `inspecto-policy`.
  Anything touching the parent pom or a shared record needs `-Pedition-enterprise`.
- Every JVM launch needs `--enable-native-access=ALL-UNNAMED` (DuckDB JNI).
- Ask for the **exit code**, not just the test tally — a suite can pass every assertion and exit 1.

## Findings

### Fixed in this sweep

| # | Where | Defect | Severity |
|---|---|---|---|
| 1 | `inspecto-etl/.../CsvIngester.java` | **Silent data corruption.** The Java CSV frontend split on physical lines before parsing, so an RFC-4180 quoted field containing a newline became two malformed fragments — both rejected as short, the row lost with no error. Reachable: this path is selected for `csv.engine: java` and for `auto` with non-zero `skip_tail_lines`. | critical |
| 2 | `inspecto-event/.../ParquetEventStore.java` | **Audit data loss.** Any flush failure `buffer.clear()`ed up to 1000 buffered events — AUDIT records included — on the first transient fault. Now retained and retried, dropped only past a hard ceiling and loudly. | major |
| 3 | `inspecto/.../ControlApi.java` + `AuditTrail.java` | **Audit trail lied.** Dispatch recorded a literal HTTP 200 for every handled route, so a 422-refused config write was indistinguishable from a successful one in the one log an investigator trusts. Now records the real status and marks refusals. | major |
| 4 | `inspecto-acquire/.../LocalFileSystemConnector.java` | **Archive data loss.** `MOVE` used `REPLACE_EXISTING`, so a re-ingested relative path silently destroyed the earlier archived copy — defeating the archive's purpose in exactly the reprocess case most likely to hit it. | major |
| 5 | `inspecto-util/.../SchemaExtractor.java` | JDBC `Connection`/`Statement`/`ResultSet` never closed — every call leaked a DuckDB connection. | major |
| 6 | `inspecto-util` (5 files), `inspecto-etl/CsvIngester` | Platform-default charset on reader/writer pairs whose output is read back as UTF-8 — silent mojibake of ledgers, logs and rejected-row evidence on a non-UTF-8 host (⚠ this box is one). | major |
| 7 | `inspecto-engine/.../NotificationRateLimiter.java` | Unbounded map: one live entry per dedupe key (per-incident, so mostly seen once) for the life of the process. Now swept, at most once per window. | major |
| 8 | `inspecto-engine/.../DbTagAssignmentStore.java` | `rename` was delete-then-insert across separate auto-commit statements; a failure between them destroyed every assignment carrying the old tag. Now one transaction. | major |
| 9 | `inspecto-config/.../ConfigSafetyValidator.java` | Gate disagreement: a recognized registry-ref prefix exited the 422 validator with the remainder unvalidated, so a draft passed authoring and then failed at load. Also de-duplicated the scalar and multi-schema-table gates, which had the skip open-coded twice. | major |
| 10 | `inspecto-engine/.../ConsignmentSelector.java` | Unescaped quote in a `glob()` literal, on a path whose catch **fails open** — a configured root containing `'` would silently readmit superseded files. | minor |
| 11 | `inspecto-etl/.../DuckDbCsvIngester.java` | `catch (Exception)` reported a genuine failure to drain rejected rows at DEBUG, making it indistinguishable from a clean file; plus two `read_csv` call sites interpolating an unescaped path while their three siblings escaped it. | minor |
| 12 | `inspecto-sql/.../SqlSandbox.java` | Temp DB file leaked on a failed connection open (one pair per attempt). | minor |
| 13 | `inspecto-util/.../FileOrganizer.java` | Partial-open leak: a failure opening the 2nd or 3rd log writer stranded the ones already open with nothing holding a reference to close them. | minor |

### Refuted by grounding — deliberately NOT changed

- ⛔ **"`day`/`trigger` are DuckDB reserved words, so the unquoted default `PARTITION_BY (year, month, day)`
  is broken"** — reported CRITICAL. Settled by **running DuckDB 1.5.2.1**: unquoted `day` *and* `trigger`
  both succeed as a column alias and inside `PARTITION_BY`. `PartitionWriter.java:129` and
  `DataTransformer.java:117` are correct as written. Fixing this would have been pure churn — and it is
  the most-travelled partition path, which is the tell that it could not have been broken.
- ⛔ **"the registry-ref prefix short-circuits the path jail"** — reported as a MAJOR bypass.
  `PipelineConfigParser.resolveSchemaRef` ends in `PathJail.requireUnderAny`, so the rewritten
  `registry/<kind>/<id>` **is** jailed at load. Real defect downgraded to gate *consistency* (#9 above).

### Filed, not fixed — need a decision or a design pass

- **OIDC audience is optional.** `-Dauth.oidc.audience` unset ⇒ any token from the trusted issuer is
  accepted, with no warning. Making it mandatory is a **breaking change for existing deployments** and
  therefore an operator decision, not a sweep edit.
- **`GenerationModeIngester` orphans flushed generations.** A mid-file failure quarantines the source but
  leaves already-revealed generation Parquet files on disk, in no manifest and no §11.3 registry — an
  unregistered file reads as "not excluded". Flagged critical by review; needs its own grounding and
  design, not a rushed edit on a CDR data path.
- **`CircuitBreaker` has no per-source eviction.** Real, but keyed by `src.id()` (the *collector* id),
  whereas the established `PipelineScheduler.forget(id)` hook passes the *pipeline* id — so the obvious
  wiring would be a silent no-op for any collector with a custom `source.id`. Deliberately not half-fixed.
- **`BatchProcessor.finalizeSource` catch asymmetry** — a member vanishing before backup throws and marks
  the batch FAILED after its outputs already durably landed and registered.
- **Reuse candidates** (no behaviour change): one `SqlIdent` for `RowShaper.q()`/`ScratchTables.q()`; one
  `DirectoryScan` for the four "scan dir → parse → tolerate corrupt file" copies; one
  `AbstractHttpObjectStoreConnector` for S3/GCS/Azure; one `AbstractJdbcStore` for the four `Db*Store`s;
  one `TarUtil.forEachEntry` for the three dry-run peek copies.

### Clean on review

`inspecto-api`, `inspecto-policy` (deny-overrides, fail-closed, attributes server-side — no defects),
`inspecto-agent-hosted`, and the bulk of `inspecto-agent` / `inspecto-connectors` / `inspecto-intelligence`:
host-key verification fail-closed-capable, secrets confined to the `SecretResolver` seam, model-authored
SQL validated through the sealed `SqlOracle` sandbox before display. `com/gamma/pipeline` in particular is
unusually well fenced against the unmodelled-key round-trip loss this project has been bitten by.

### Coverage honesty

The two largest clusters were sampled, not exhausted: `ops/*`+`catalog`+`query`+`notify` (~40 of ~95 files
read) and the control plane's `control`+`service` (the dispatch chain, `WriteGates`, `ConfigRoutes` and
`AuditTrail` in depth; ~55 smaller `control` files and most of `service` not line-by-line). A second pass
should target `PipelineRoutes`/`ObjectRoutes`/`BundleRoutes` for the sibling-gate pattern, `Roles`/
`AccessPolicies` for RBAC/ABAC evaluation itself, and `FindingsSpec` + the `signal` package.
