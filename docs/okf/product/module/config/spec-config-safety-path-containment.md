---
type: Concept
title: Config Safety Validator — Path containment and the config write routes
description: Part 1 of 4 of Config Safety Validator: Path containment: one primitive, five callers; Object-store containment — PathJail.requireObjectStore (2026-10-18, SCALEOUT-OBJSTORE-DATABASE-DIR-1); The /config/* write routes never reach registry/ or a reserved file (CONFIG-WRITE-REGISTRY-1, 2026-09-27); Decision 2026-09-23 — a relative DATA path resolves under the Space directory
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator — Path containment and the config write routes

Part 1 of 4 of [Config Safety Validator](spec-config-safety.md) · next: [Allowed roots, data refs and the one save gate](spec-config-safety-roots-save-gate.md).

## Path containment: one primitive, five callers

`com.gamma.config.safety.PathJail` (2026-08-14) is the **single** answer to "is this path under this
root". Before it the codebase spelled that question five ways at differing strengths — the *advisory*
validator re-checked symlink escape while the *enforcing* gate on the HTTP write surface did not even
absolutise. `require` (throws `PathJail.Escape`) and `contains` (predicate) reach their verdict through
the same code path, so the enforcing and advisory surfaces cannot drift; a test pins that.

**The roots are `SafetyPolicy.defaultPolicy()`** — `-Dassist.safety.roots` (`;`-separated), defaulting
to the working directory. ⛔ Not `-Dspaces.root`, which only `ControlApi` reads for space *discovery*,
sits above the engine in the module graph, and is unset in single-tenant mode and in the job runner.

Enforcing callers: `WriteGates.jail` (HTTP writes), `LocalConnectionWorkbench.jail` (connector paths,
keeps its `PathEscape` → 403 contract), nine operator-supplied path fields across `BackupTask` /
`MaintenanceJob` / `ReportJob`, and `PipelineConfigParser.resolveSchemaRef` at load. Advisory caller:
this validator.

⚠ **`PathJail.require` reads a relative value against the working directory — so every caller holding a
relative value RESOLVES it first, against the base that value means, and then jails it once.** Three
resolvers, one rule (`resolveAgainst`):

* **`PathJail.resolveConfigRef(configDir, …)`** — a config's reference to ANOTHER config file resolves
  **beside the referring config** (`SCHEMA-FILE-RESOLVES-AGAINST-CWD-1`, 2026-09-23). Keys:
  `processing.schema_file`, every `schemas[].schema_file`, `processing.mapping_file`,
  `parsing.grammar` / `processing.grammar`, every `segments` value (`parsing.plugin.*`, `processing.*`,
  `asn1.*`), and Asn1RecordIngester's `ingester_config.grammar` (= `asn1.grammar_file`). Callers:
  `PipelineConfigParser.resolveSchemaRef` (load), `ConfigSafetyValidator.checkPathValue` (422 gate, handed the same resolver),
  `ConfigRoutes.resolvedPath` (the schema-file WARNING and `declaredColumns`),
  `PipelineSettingsRoutes.copySchemaFile` (template copy), and `com.gamma.telecom.asn1.Asn1GrammarSource` (the
  ASN.1 grammar file: the parser resolves `ingester_config.grammar` beside the config WITHOUT jailing it,
  and this one resolver — shared with the stand-alone preview, whose base is the Space config root —
  checks the `.asn`/`.asn1` extension and jails it once, at use). Before this they were hand-kept copies
  of one rule.
* **`PathJail.resolveDataPath(configDir, …)`** — a config's DATA path resolves **under its Space
  directory** (`spaces/<id>/`), derived from the config's own directory by `PathJail.spaceDirOf` (the parent
  of the nearest `config/` ancestor — the layout `SpaceRoot.under` and `SpaceManager.discover` use)
  (`DATA-DIRS-RESOLVE-AGAINST-CWD-1`, 2026-09-23). `PathJail.dataPath` is the reader's form (a string:
  resolved, or as authored when blank / a URI / no Space). See the decision below.
* **`PathJail.resolveJobPath(spaceConfigRoot, …)`** — a job's path values, against the Space config root
  (`JOB-DIR-CWD-CONTAINMENT-1`; see [jobs](../processor/spec-jobs.md)).

🔴 **Why the config-ref rule changed.** Until 2026-09-23 the shipped configs spelled their refs from the
server root (`schema_file: spaces/demo/config/orders/orders_schema.toon`) and the loader fell back to the
working directory (W1b's *"config-relative first, CWD second"*). That meant something from exactly one
directory: a bundle launched from `inspecto-deploy/` with `-Dspaces.root=..\spaces` resolved
`inspecto-deploy\spaces\demo\…`, **31 of 32 shipped Pipelines** failed to register, and Pipelines, Runs
and Processing Status rendered empty. Every shipped ref is now spelled beside its pipeline (a bare
sibling name), and the fallback is gone. Pinned by `ShippedPipelinesLoadFromAnyWorkingDirectoryTest`
(surefire's CWD is the module dir, so it IS the "launched elsewhere" condition — its premise is asserted,
not assumed).

⛔ **The ambiguous ref is REFUSED, not relocated.** A relative ref with nothing beside its config, while the
old working-directory spelling DOES exist, throws `PathJail.Escape` naming both paths — the same rule as
jobs. ⚠ **That refusal only fires when the old CWD file exists** (see the `resolveJobPath` note in
[jobs](../processor/spec-jobs.md)): from any other directory an unmigrated `spaces/<space>/config/…` ref is
simply *not found*. Both fail closed; neither reads the wrong file.

⚠ **`../` is legal** — it resolves from the config's directory and the jail judges the result. W1b skipped
a config-relative candidate that climbed out of `configDir` and fell through to the (then unjailed) CWD
reading; a narrower "stay under `configDir`" rule beside the jail would be a path jailed twice.

⚠ **Data paths are the third resolver** — `dirs.*`, `sinks[].database`, enrichment and `local` connection
paths resolve under the Space directory; see *Decision 2026-09-23* below.

⚠ **`POST /validate {configPath}` is jailed under `PathJail.allowedRoots()`** (`VALIDATE-CONFIGPATH-UNJAILED-1`,
reproduced and fixed 2026-09-23). Until then it loaded any server path the caller named: a file outside
every root was read and parsed (422 with the parser's message), a missing one answered 500 naming the full
path — an existence oracle. Now, before any filesystem access: relative value with no write root → 400
(it resolves against the write root, like `POST /runs`, never the CWD) · outside the roots → 403
`PATH_JAIL_VIOLATION`, identical for a present and a missing file · inside but not a file → 404. The roots
are the load-time ones, not the write root: the load already refuses a config whose schema refs sit
outside them, so a config with satellites was never loadable from outside them anyway. Pinned by `ControlApiValidateConfigPathJailTest`.
⚠ **A preview's reference `path:` is jailed under the same `PathJail.allowedRoots()`**
(`PREVIEW-REFERENCE-PATH-UNJAILED-1`, operator decision 2026-09-24). Until then three read-shaped routes
opened a caller-supplied reference data file with no jail and returned its rows: `POST
/pipelines/authored/{id}/dry-run` and `POST /pipelines/authored/{id}/run?to=` (a `transform.join`
`reference`, both through `PipelineGraphRoutes.dryRunReferences`) and `POST /enrichment/preview`
(`references.<n>.path`, checked in `EnrichmentRoutes.previewEnrichment` before the engine opens it). Now a
path reference outside the roots — absolute, `..` traversal, or a symlink out (the `PathJail.contains`
real-path re-check) — or no roots configured → 403 `PATH_JAIL_VIOLATION` *"'<field>' is outside the allowed
roots"*, before the file is read. By-name `reference/<pipeline>` refs are ids, not paths, and are not
jailed. The roots are the safety roots, not the write root, because a data file routinely lives outside
the write root. One helper, `WriteGates.jailToAllowedRoots`, serves these and `/validate {configPath}`.
Pinned by `ControlApiPreviewReferenceJailTest` (a readable probe with the accepted file's content is
refused; removing the jail turns the four refusals into 200s carrying its rows). ⚠ The `run?to=` route
shares the resolver but has no dedicated real-HTTP test of the refusal.

⚠ **`/validate {configPath}` writes nothing** (`VALIDATE-PREPARE-WRITES-STATUS-DIR-1`, reproduced and fixed
2026-09-23). It used `PipelineConfig.load()`, whose `prepare()` CREATED the Pipeline's status directory, so
the `CapabilityManifest` "read-shaped, writes nothing" exemption was false. `prepare()` is now two halves:
`requireRunnable()` (every arming refusal, no filesystem access) and a private `createStatusDir()`.
`PipelineConfig.loadForValidation()` = parse + `requireRunnable()` — same answer as a registration, no
write; `load()` = `loadForValidation()` + `createStatusDir()`, so every real-run caller (`ConfigRegistry`,
`CollectorService.registerPipeline`, the engine CLIs, `PipelineJobRunner`) creates the directory as before.
Pinned over real HTTP by `ControlApiValidateConfigPathJailTest` (the listing of the whole root, before vs
after). Audited siblings: `/validate`'s was the only read-shaped route calling `load()` — the graph lift,
Pipeline list and bundle export read configs the registry already loaded (and whose directory registration
already made), and the draft/preview paths use the pure `fromMap()`. Two non-route callers still use
`load()` for a read and were left alone because a write follows anyway:
`CollectorService.requireDistinctPipelineIds` (boot pre-check; the registry build right after runs `load()`
on the same files) and `PipelineRenameRoutes` resume (a mutating route over an already-registered Pipeline).

⚠ **`/validate {configPath}` runs the shared `SaveGate` too** (`VALIDATE-CONFIGPATH-SKIPS-SAVEGATE-1`, fixed 2026-09-23). Until then that branch ran spec validation + arming only (G3 `ccda98a8` moved just the draft branch), so a file carrying a fault the gate refuses, such as an unread block (`ERR_UNKNOWN_CONFIG_KEY`), validated with no ERROR. It now calls `SaveGate.check` over the decoded file, judged from the file's own directory (`Referents.MUST_EXIST`), after the load. ⚠ A fault the LOADER already refuses (e.g. an authored `webhook.url:`) still answers 422 from the load, not a finding: that ordering is unchanged. Pinned by `ControlApiValidateConfigPathJailTest#aConfigPathRunsTheSaveGate`.

⚠ **Containment does not require the file to exist.** A ref resolved from the wrong working directory
still passes the jail while pointing at nothing — so a parser-level unit test proves nothing about
whether a space boots. Verify with a real server boot from the repo root.

Two implementations were deliberately **not** migrated: `PipelineConfigParser.validateDirs` is an
*anti*-containment business rule (output must not land in the poll dir) and only borrows the verdict;
`PipelineJobRunner.requireTopLevelSinks` is a **depth** rule about literal directory nesting, where
resolving real paths would change the answer for the wrong reason.

⛔ **Existence and containment are two questions, and a gate needs both.** `ConfigRoutes.resolves` asks
only "does this ref exist" and must stay that way — teaching it containment would report *"schema file
does not resolve on the server"* about a ref that resolves fine but escapes, sending the operator after
the wrong thing. Containment comes from `ConfigSafetyValidator`, and every pipeline gate pairs the two:
`ConfigPreviewRoutes` validate, `ConfigWriteRoutes` write/patch, the `Pipeline*Routes` modules, `RunRoutes` — and, since 2026-08-14,
**`DataSourceRoutes` bundle import on both the commit and preview sides**, which had the existence half
only. The consequence there was not a read escape (the loader still jails) but a *partial import*: the
escaping ref survived the all-or-nothing gate and was refused later by `registerPipeline`, one file at a
time. ⚠ Containment **is** answerable at preview — it needs the allowed roots, not the filesystem —
unlike existence, which only becomes answerable once the bundle's files land.

⚠ **`SafetyPolicy.defaultPolicy()` has no working-directory fallback — and until late 2026-08-14 that
was only a claim.** The record's compact constructor silently substituted `[CWD]` for an empty root
list, so an unconfigured deployment quietly granted the server's working directory to every containment
check — the exact posture the Javadoc (corrected that morning) said could not happen. A one-file probe
falsified the doc; the constructor now keeps empty empty, `PathJail.requireUnderAny` throws on it, and
`DiscoveredRootsTest` pins it. Fail-closed for real now; configuring the roots is a deployment step.
⚠ Surefire sets the roots to `<repo>;<temp>`, so **nothing under a `@TempDir` escapes by default** — a
containment test that does not narrow the roots passes vacuously.

## Object-store containment — `PathJail.requireObjectStore` (2026-10-18, `SCALEOUT-OBJSTORE-DATABASE-DIR-1`)

A bucket URI cannot be contained by `Path` comparison (and on Linux `Paths.get("s3://b/x")` silently becomes a
local `s3:` directory — see `PathJail.isUri`), so an object-store location has its own rule, dispatched on
`isUri`:

* **Allow-list:** `-Dassist.safety.object_roots`, a `;`-separated list of `s3://bucket[/prefix]`. A **server
  property, never a Space key** — a Space must not be able to widen its own jail. **Blank (the default)
  refuses every URI.** A malformed entry (traversal, glob, bad bucket) is dropped, never loosened into a match.
* **Recognised form only:** `s3://` (lower case) + a valid S3 bucket name + key segments of
  `[A-Za-z0-9._=+-]`. Refused, never decoded or collapsed: `.`/`..` segments, empty segments (`//`), `%`
  escapes, `?`, `#`, backslashes, glob bytes (DuckDB expands them), quotes, whitespace, userinfo, a port, any
  other scheme (`s3a://`, `gs://`, `file://`, `S3://`).
* **Containment is segment-wise:** `s3://b/pX` is not under `s3://b/p`.
* **Where it applies:** only `dirs.database`. `ConfigSafetyValidator.checkPipeline` sends a URI there to
  `requireObjectStore`; every other `dirs.*` (and every other path key) keeps the flat "is a URI" refusal.
  `PartitionWriter.writeToObjectStore` re-checks the same rule before any I/O, so a caller that skipped the
  gate still cannot COPY outside an allowed prefix.
* **The Connection it writes through, `output.connection` (2026-11-01).** An `s3://` `dirs.database` must name the `s3` Connection
  that reaches it, and a local one may not name any (`ConfigSafetyValidator.checkOutputConnection`, 422 on
  `output.connection`). The pipeline stores an id, never a secret; whether the id is registered is checked
  where the run resolves it (`ObjectStoreDatabase`, module.elt `spec-sinks.md` (Part III — Sinks) § *Object-store `dirs.database`*).
* **The run's seal admits the prefix alone.** `SqlSandbox.sealAllowing(conn, dirs, files, objectPrefixes)` adds
  each contained prefix to DuckDB's `allowed_directories` (with a trailing `/`, which is redundant: removing it
  left every test green, because DuckDB treats each entry as a directory itself). Measured on DuckDB 2.0.0.0
  against MinIO with external access off: under the prefix, COPY and reads work; `s3://b/pX`, another
  prefix in the bucket, another bucket and any `http://` URL are refused with "file system operations are
  disabled by configuration", before a dial. ⚠ DuckDB's check is **lexical**: `s3://b/p/../q` passes it, and
  the store judges the key as written (MinIO 403; on AWS `..` is a literal key segment, so it stays under
  the prefix). The DuckDB secret is also scoped to the prefix, so a request outside it is not signed — a
  second, independent line: with the seal widened to the whole bucket (a mutant), the live test's
  expression read outside the prefix still landed nothing, and only the seal test caught the mutant.
* Previously (2026-10-18 → 2026-11-01) `ConsignmentIngestStrategy.configure` refused a URI `dirs.database`
  by name; that refusal is gone. Call (BACKLOG §1; **ASSISTANT-DECIDED under operator delegation 2026-10-18; CONFIRMED 2026-10-20 (operator, accepted the recommended answer).** server list only, no per-Space key): whether a Space may narrow the
  server list to its own prefix.

Pinned by `PathJailObjectStoreTest`, `ConfigSafetyValidatorTest.anAllowListedObjectStoreDatabaseDirIsAcceptedAndNothingElseIs`
and `PartitionWriterObjectStoreContainmentTest`; each refusal is paired with the accepted
value it was derived from, and dropping the `..` check turns the traversal test red.

## The `/config/*` write routes never reach `registry/` or a reserved file (`CONFIG-WRITE-REGISTRY-1`, 2026-09-27)

Being contained in the write root is not the same as being a file those routes own. `POST /config/write`,
`POST /config/patch` and `DELETE /config/{type}/{name}` take a caller `subdir`, and until this fix a
`canAuthorWorkbench` Subject could aim it at `registry/<dir>` and **plant, overwrite, patch or delete any
component**. Measured on real HTTP for `kpi`, `findings-spec`, `alert-rule` and `access-profile`: every
operation answered 200, `GET /components/<kind>/<id>` then served the planted file, and no Pending Change
was held although a policy governed each kind. That bypassed the kind's own capability (`canManageIncidents`,
`canConfigureAccess`, `canAuthorAlertRules`), its `fromMap` validation (a KPI with no Measure) and its
maker-checker rule (the write was held — or not — as `meta`). Deleting `registry/access-profiles/role-<r>.toon`
**widens** that role: a role with no saved profile allows everywhere. Only the `meta` spec's unknown-key ERROR
stopped a kind-shaped payload, so what landed was `name`-only — a guard by coincidence, not by design. The
same routes also wrote the reserved Space documents: `meta` named `roles` **was** `roles.toon`.

**The rule** — `WriteGates.refuseReservedConfigTarget`, called on the FINAL target of all three routes, after
the 422 content gate and before any existence check (so a refusal is not an existence oracle) → **403
`PATH_JAIL_VIOLATION`**:

- the config-relative path's first segment is `registry` → refused, naming `/components/<kind>` as the door.
  Judged on the normalised path **and** its real path, case-insensitively and with the trailing dots/spaces
  Windows drops — `Registry/`, `REGISTRY/KPIS`, `registry./`, `registry\kpis`, `x/../registry/`, a
  double-encoded `?subdir=registry%252Fkpis` on a DELETE (its query is decoded twice) and a link into the
  registry all count;
- the path is a reserved file or directory (`ImportPaths.reservedRefusal`, i.e. `ReservedConfigPaths` plus
  its real-path check — the same list every import already honours).

⛔ No shipped caller wrote under `registry/` through these routes: the UI's `subdir` is always a Pipeline's own
config directory, and `schema-editor.dialog.ts` already routes a registry schema through `/components/schema`
(its comment explains why `subdir: 'registry/schemas'` was wrong anyway — the component read does no
sibling-CSV merge).

**The read side follows the same rule** (review follow-up, same day). `GET /config/{type}/{name}` is ungated, so a
`?subdir=registry/<dir>` read skipped the `ComponentAccess.requireView` that `/components/<kind>` applies to a
private or shared-away component, and `/config/meta/demo-users` (or `roles`, `approval`) served a reserved file by
name. The read now calls the same check on its convention target → 403 before the 404. Its no-`subdir` satellite
scan (`ConfigFileSupport.resolveSatelliteForRead`) **skips** registry and reserved hits instead of refusing them — a
403 there would itself say "a registry file by that name exists"; skipped, the answer is the same 404 as an absent
name. ⛔ No UI or test read goes through `/config/*` for a registry component or a reserved file: the UI's reads
all name a Pipeline's `subdir`, and registry schemas are read via `/components/schema`. Pinned by
`ControlApiConfigWriteRegistryJailTest`
(its symlink case needs link privilege and skips on a stock Windows account).

## Decision 2026-09-23 — a relative DATA path resolves under the Space directory

**Operator decision (`DATA-DIRS-RESOLVE-AGAINST-CWD-1`):** a config's relative data paths resolve under the
**Space directory**, so a config says `data/orders/database`, never `spaces/<space>/data/…`. Chosen over
the Space *data* root (`spaces/<id>/data/`) so a path reads the same as the tree on disk and a config can
still name a sibling of `data/`. Same cause and same shape as the config-ref rule above: the shipped configs
spelled every data path from the server root and it resolved against the working directory, so a bundle
launched from `inspecto-deploy/` wrote its data under the launch dir, and a test loading a shipped config
littered the module dir (`inspecto/spaces`, `inspecto/out`, `inspecto/templates`), which fooled repo-root
walkers like `MappingMigrationTest`.

**Keys** — every one through `PathJail.resolveDataPath` / `PathJail.dataPath`, resolved ONCE at load and
carried as an absolute string, so the ~100 typed readers of `dirs()` / `sinks()` need no base of their own:

| Key | Resolved by |
|---|---|
| `dirs.*` (all of them, `status_file` included), `processing.duckdb.temp_directory`, `output.ducklake.data_path`, `sinks[].database`, `sinks[].ducklake.data_path`, `route.branches[].database` (it pairs with a sink BY VALUE, so it must resolve identically), a join's path `reference` (`processing.join`, a `steps[]` / branch `join` step; a by-name `reference/<id>` is an id and untouched) | `PipelineConfigParser` (load) |
| enrichment `input.database`, `output.database`, `references.<n>.path` | `EnrichmentConfig.load` (`fromMap(raw, sql, configDir)`) |
| a **`local`** connection's `base_path` (a remote connector's is a path on the remote system — untouched) | `ConnectionProfile.load` / `resolvedBeside` (and `ConnectionRoutes.persistConnection` registers it resolved). ⚠ Resolution is INTERNAL: `basePath()` (what a connector reads) is resolved, `authoredBasePath()` keeps the value as written, and `toMap()` (`GET /connections`), `toBundleMap()` and the persisted `*_connection.toon` all carry the AUTHORED value — so a UI GET → PUT round trip stores `data/…` again (`DATA-PATH-RESIDUALS-1` (c), 2026-09-23) |
| the same keys at the 422 gate | `ConfigSafetyValidator.check(…, configDir)` — every caller that knows the config's location passes it |
| a pipeline's owned dirs at deletion | `PipelineDataDirs` (conflicts, removal, the sharing scan) |
| a job's `data_dir` | `SpaceConfigRoot.jobPathBase("data_dir")` — the Space DIR (`PathJail.spaceDirOf` of the config read root) in a Space, the read root unchanged when it has no `config/` ancestor (single-tenant); `PipelineJobRunner` then RUNS on the resolved path whenever it differs from the value's CWD reading (`DATA-PATH-RESIDUALS-1` (d)) |
| a store-authored graph's join path `reference` | `PipelineJobRunner.references()`, against `SpaceConfigRoot.current()` |

**No Space → the working-directory reading, by design.** `spaceDirOf` is `null` for a single-tenant example
(`inspecto/examples/…` has no `config/` ancestor), for `SpaceManager.single()`, and for an in-memory draft
(`PipelineConfig.fromMap`). The value is then left as authored — relative, i.e. the launch directory. That
IS the single-tenant Space-dir equivalent, and the same answer `SpaceConfigRoot.jobPathBase` gives a job's
`data_dir` there (the config READ root = `LegacySpaceRoot.base()` = the launch dir; `serve-example.sh` /
`run-example.sh` `cd` into the example, so `out/inbox` is the example's own). In a multi-Space server a
job's `data_dir` resolves under the Space dir too (since 2026-09-23, `DATA-PATH-RESIDUALS-1` (d)); until then
it was JAILED against the Space config root while the run read it against the CWD — three meanings for one
value. The single-tenant string still travels unchanged (it is baked into view SQL), because there its CWD
reading IS the resolved path.

⛔ **The ambiguous value is REFUSED, not relocated** — the shared rule: nothing under the Space dir while the
old working-directory spelling exists throws naming both paths. ⛔ **And a value that repeats its own Space's
path is refused too** (`DATA-PATH-RESIDUALS-1` (a), 2026-09-23): `spaces/demo/data/…` in a config under
`spaces/demo/`, loaded where that CWD path does NOT exist, used to resolve silently to
`spaces/demo/spaces/demo/data/…`. `resolveDataPath` now throws `repeats its own Space's path ('spaces/demo')`
(a 422 finding at the gate) when the value's leading segments equal the Space dir's trailing ones — **two or
more** of them, so a Space named `data` keeps its `data/…` paths. Data paths only; config refs and job paths
keep the old limit. Every shipped config was respelled (302 values in 34 files, the
`_templates` `spaces/${SPACE}/data/…` ones included); the SPA's scaffolds (`pipeline-scaffold.ts`, the
stream import) write `data/…` too.

**Retired with it:** the whole-space bundle's "space rebasing" (`BundleImporter` rewrote `spaces/<src>/` to
the target's prefix — there is nothing space-qualified left to rewrite, so bytes now travel verbatim and the
`rebased` response fields are gone), the pipeline bundle's `source_prefix` manifest key and `spacePrefix`
derivation, and the pipeline template sandbox moved `templates/<id>` → `data/templates/<id>` (Space-relative
now, and a top-level `templates/` is not a Space layout-contract entry). ⚠ With **no Space** (a single-tenant write root) these two writers — pipeline import and the template
sandbox — prefix the write root's parent, absolute (`PipelineBundleRoutes.dataPrefix`): there `data/…` would mean
the launch dir, which need not be inside the allowed roots the new config is judged against.

**Not data paths, left alone:** a view's `derived_sql` (`read_parquet('spaces/ucc/data/…')`) is SQL DuckDB
reads against the process CWD; an enrichment's `transform_file` is a config ref still read from the CWD by
`EnrichmentConfig.load` (the 422 gate judges it from there too, so the two agree).

Pinned by `ShippedPipelinesWriteUnderTheirSpaceDirectoryTest` (inspecto-engine: a relocated verbatim copy of
`spaces/demo` RUNS the orders Pipeline and its output + status land under the copy; every shipped
Pipeline / Enrichment / local Connection's data paths resolve under their Space; nothing appears under the
CWD) and `DataPathResolutionTest` (inspecto-config: the resolver, `spaceDirOf`, the refusal, the gate, the
repeated-Space-path refusal). The five residuals shipped 2026-09-23 (`DATA-PATH-RESIDUALS-1`), each pinned:
(a) `DataPathResolutionTest`, (b) the `ura` CLI's `dirs.*` (its test went with the CLI, retired 2026-11-06), (c) `ControlApiConnectionsTest` (the GET →
PUT round trip), (d) `SpaceConfigRootTest` + `PipelineJobRunnerTest` (a Space job's `data_dir` runs under the
Space dir), (e) `create-schema`'s generated dirs (retired with the `ura` CLI, 2026-11-06).
