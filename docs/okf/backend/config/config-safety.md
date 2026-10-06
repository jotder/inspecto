---
type: Concept
title: Config Safety Validator
description: The hard-fail gate that path-jails writes, bounds numeric config, and allow-lists output formats.
resource: inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator

`ConfigSafetyValidator` (`inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java`) is a
purely-static, zero-dependency hard-fail gate (since v3.5.0). `check(configType, rawMap, policy)` returns
`ERROR`-severity `Finding`s for any violation. It enforces three things:

* **Path jail** — every `dirs.*` field + `output.ducklake.data_path` must resolve under the policy's
  `allowedRoots`; rejects `..` escapes, UNC paths, and symlink escapes (real-path re-checked).
* **Numeric bounds** — `processing.threads`, `processing.duckdb_threads`, `collector.consignment.max_files` (and the legacy `processing.batch.max_files`), and
  the `skip_*` values against policy limits; `retention_days >= 1` when duplicate-check is on.
* **Output allow-list** — `output.format`/`output.compression` restricted to known values; DuckLake requires
  its connection fields when enabled.
* **Enrichment `references.<name>` entries** (2026-08-13) — each entry must be a map carrying **exactly one
  of `path` or `ref`**; the entry name and a by-name `ref` must be SQL identifiers; `as_of` must be an ISO
  date/date-time and requires a by-name `ref` (a plain `path` file carries no version history). These mirror
  the hard-fails `EnrichmentConfig.fromMap` applies at LOAD, so a hand-authored or API-written config is
  refused at the 422 write gate rather than at registration.

* **Config-declared refs** (2026-08-14) — `processing.schema_file`, `processing.mapping_file`,
  `parsing.grammar`, `processing.grammar`, and **every row** of the multi-schema table form
  (`schemas[N]{…,schema_file,…}`). These mirror what `PipelineConfigParser` now enforces at LOAD
  (see [path containment](#path-containment-one-primitive-five-callers) below), so a ref is refused at
  authoring for the same reason and against the same roots.
  * ⚠ **`grammar` has two spellings and `parsing.grammar` is the design-of-record** — it *wins over*
    the legacy `processing.grammar`, so gating only the legacy key leaves the preferred one open.
  * ⛔ **A registry reference is an id, not a path.** `schema/<id>`, `grammar/<id>` and `mapping/<id>`
    share these keys with plain paths. Jailing one resolves it against the working directory and
    reports a false escape **whenever `allowedRoots` is not the CWD** — i.e. on precisely the
    deployments that declare `-Dassist.safety.roots`. `checkConfigRef` skips them; the parser rewrites
    a reference to `registry/<kind>/<id>` and jails *that*, which is the value actually read. The
    prefix list is duplicated from `PipelineConfigParser` deliberately — that class is in
    `inspecto-etl`, **above** this module, so importing it would invert the dependency.

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
  `PipelineSettingsRoutes.copySchemaFile` (template copy), and `com.gamma.parse.Asn1GrammarSource` (the
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
  (`JOB-DIR-CWD-CONTAINMENT-1`; see [jobs](../control-plane/jobs.md)).

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
[jobs](../control-plane/jobs.md)): from any other directory an unmigrated `spaces/<space>/config/…` ref is
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
| the `ura` CLI's `dirs.*` (`search` / `copy` / `copy-tars` / `extract` / `backup` / `prepare-inbox`) | `MainApp.loadToon`. `TarInboxPreparer` has no `main` and no path-taking constructor any more (both read `dirs.*` from the CWD) — `ura prepare-inbox` is its only entry (`DATA-PATH-RESIDUALS-1` (b)) |
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
(a) `DataPathResolutionTest`, (b) `MainAppPrepareInboxDirsTest`, (c) `ControlApiConnectionsTest` (the GET →
PUT round trip), (d) `SpaceConfigRootTest` + `PipelineJobRunnerTest` (a Space job's `data_dir` runs under the
Space dir), (e) `SchemaExtractorFieldListTest` — `create-schema` emits `data/inbox/<x>` and `data/<x>/<kind>`.

## A Space's allowed roots: declared + its OWN base (tier 3 2026-08-14, narrowed 2026-09-24)

`SafetyPolicy.defaultPolicy()` returns `SafetyPolicy.forSpace(CurrentSpace.id())`: the operator-declared
`-Dassist.safety.roots` plus **the bound Space's own base**, as registered (keyed by Space id) in
`com.gamma.config.safety.DiscoveredRoots`. Another Space's base is never an allowed root.

🔴 **Until 2026-09-24 this was the union of every hosted base** (`CROSS-SPACE-JAIL-1`). Probed and
confirmed: with two Spaces hosted and operator roots holding neither, `POST /spaces/acme/jobs` with an
absolute `backup_dir` inside `beta`'s `data/` returned **200** — every Space was an allowed root for every
other Space, at the 422 gate and in every run-time jail (`PathJail.allowedRoots()` reads the same
policy). Now it is **422**, and `ControlApiCrossSpaceJailTest` pins that plus the positive controls (a
Space's own base and an operator root stay allowed). Mutation-checked: restoring the union turns it,
`DiscoveredRootsTest.anotherSpacesBaseIsNotAnAllowedRoot` and `SpaceManagerTest` red.

- **Which Space is "current"** is the thread's space MDC — `ControlApi.bindSpace` for a `/spaces/{id}`
  request, `JobService` for a run, `CollectorService.underSpace` for a cycle. **An unbound thread is the
  `default` Space** and gets only a base registered under `default` — never a named Space's. That is the
  fail-closed direction: an un-prefixed request on a multi-Space host with no `default` Space dir now
  gets the operator roots only, where it used to get every Space's base.
- ⚠ **Boot must run AS the Space.** `SpaceBootstrap.load` is where a Space's `schema_file` refs meet the
  jail, and the boot thread is unbound — so `SpaceManager.loadAsSpace` binds the MDC to the Space being
  loaded. Without it a named Space's own pipelines are refused at boot (`SpaceManagerTest.aNamedSpacesOwnSchemaRefLoadsAtBootUnderNarrowedRoots`
  goes 1 → 0 loaded when the binding is removed).
- All 15 `ConfigSafetyValidator.check*` callers take `defaultPolicy()` on a bound thread, so none needed
  an explicit id; `forSpace(id)` is for a caller that already holds one.

The per-Space base (tier 3, 2026-08-14) removes the misconfiguration PATH-2 tier 3 named: the
write root was per-space and **dynamic** (`writeRoot()` derives from the current space) while the
policy list was global and **static** — create a space and forget to extend the property, and writes
into its `config/` passed the 403 gate while every schema/grammar ref inside it was refused at load.
Now both halves derive, and the declared list goes back to meaning only what it is for — destinations
**outside** the layout (`backup_dir: /mnt/backups`).

- **The seam points downward.** `inspecto` (SpaceManager) depends on `inspecto-config`, never the
  reverse — so the lifecycle **pushes** bases in and the policy reads. `discover()` registers each
  space **before** `SpaceBootstrap.load` (boot is exactly when refs meet the jail; registering after
  would refuse the configs the root exists to allow), and deregisters when the boot fails — a space
  that never joined leaves no root behind. The three runtime create paths share one `bootStarted`
  helper with the same order and the same failure cleanup.
- **A runtime-created space extends the roots immediately** — `defaultPolicy()` recomputes per call,
  so the next check sees it, no restart. **A deleted space's base leaves with it** (in `delete`'s
  per-space-registry teardown block), so the root set cannot only ever grow within a process lifetime.
- **The legacy flat / single-tenant space keeps property-only behaviour** — `SpaceManager.single`
  registers nothing, by construction (it never touches `DiscoveredRoots`). Likewise the engine CLI and
  job-runner entry points (`MainApp`, `CollectorProcessor`, `EnrichmentProcessor`, the job tasks) never
  run discovery, so for them the set is empty and the property remains their only source — unchanged.
  ⚠ **This is a decision, not a gap — and it was re-affirmed 2026-08-28** (PKG-6). A fresh bundle's
  `run.sh csv_example` died here (*"no allowed roots configured for 'schema_file'"*) because the
  one-shot CLI has no roots and the format-example pack uses `schema_file:` refs. The tempting fix —
  teach `CollectorProcessor` to derive roots the way the server does — would have reversed this
  sentence, so it was **refused**; `DiscoveredRootsTest`'s empty-means-empty invariant stands.
  **The launcher declares the root instead**, which is this section's own model: configuring roots is
  a deployment step, and `run.sh`/`run.bat` already resolve the pipeline path, so they pass
  `-Dassist.safety.roots=<that pipeline's space dir>` — the ONE space per invocation, never the whole
  `spaces/` tree, with an operator-supplied value still overriding it. See
  [Operations reference](../build-run/operations-reference.md).
- ⚠ **The registry is process-global static.** A test that registers must `DiscoveredRoots.clear()` in
  a finally — a leaked base flips containment verdicts in unrelated tests. And an assertion about a
  discovered root must inspect `allowedRoots()` **content**, not run a jail check: surefire's
  reactor-wide roots already cover every `@TempDir`, so a verdict-based test passes vacuously.
- ⚠ `SafetyPolicy.withRoots(...)` (skill workspace, tests) deliberately **bypasses** the registry — an
  explicit policy is scoped, not widened.

⚠ **`PathJail` unified the CONFIG-path implementations, not every containment check in the codebase.**
A 2026-08-14 sweep found ~15 more outside that scope — the registry stores (three near-identical
copies; `ComponentStore` actually splits the logic in two), archive extraction, static file serving,
remote listings — disagreeing on symlinks, absolutisation and failure mode. They are **four different
problems**, and ⛔ putting them all on `PathJail` is the wrong fix: remote object keys are not local
`Path`s, and id-shaped names are not containment at all. The family split and the ranked fix order are
`BACKLOG.md` §6 **PATH-2** — which also records that grounding the sweep **refuted three of its own
claims**, so trust that row's 2026-08-14 close-out over the original framing.

## Data refs: a second verdict, deliberately not `PathJail`

`com.gamma.config.safety.DataRef` (2026-08-14) is the one answer for a `physicalRef`-shaped value — a
store ref resolved under a **space's data root**. It sits beside `PathJail` and is emphatically *not* a
caller of it.

⛔ **A data ref must never be routed through `PathJail`.** `PathJail` resolves a relative value against
the **working directory** — load-bearing for config refs, as above — while a data ref is meaningless
except relative to the data root. Forcing one onto the other would silently resolve `orders` against the
server CWD. Two roots, two verdicts.

The shape rule is a character class, and that is what makes these refs **structurally** safe rather than
merely filtered: `[A-Za-z0-9][A-Za-z0-9._/-]*` admits no `\` (no UNC, no Windows separator) and no `:`
(no drive prefix), and the alphanumeric first character rejects a leading `/`, `-` or `.`. Traversal is
excluded by a separate `".."` substring test because `.` must stay legal *inside* a segment (a store
named `orders.v2`). `requireShape` is for the branch that resolves elsewhere — an Exchange
`shared/<owner>/<item>` snapshot; `requireUnder` adds containment. Violations throw
`IllegalArgumentException` → **422**: an unusable ref is a bad request about a dataset, not a containment
incident, so it deliberately does *not* throw `PathJail.Escape`.

⚠ **`requireUnder`'s containment branch is unreachable while the shape rule holds — and is kept anyway.**
It exists so the two rules cannot drift apart, which is the failure this class was created to end:
`DatasetRelation` and `ExpectationEvaluator` each carried their **own copy** of that pattern (the
latter's Javadoc admitted it was the "same shape as" the former), and the copies *had* drifted — both
checked shape, only one re-checked containment after resolving. Nothing was reachable through the gap,
but a boundary spelled twice is a boundary that drifts.

⚠ **Test the reason, not the throw.** Both rules raise `IllegalArgumentException`, so a type-only
assertion cannot tell "refused by shape" from "refused by containment" — and since shape refuses first
in every case, such a test reports the containment half as covered when it never ran. `DataRefTest`
asserts each exclusion's message individually.

**Symlinks under data refs: closed 2026-08-14 (tier 4, data-ref half).** `DataRef.requireUnder`'s
containment verdict is now `PathJail.contains` — resolution stays DataRef's (a ref resolves against the
data root, never the CWD), the verdict is the jail's single definition, symlink re-check included. Third
use of S2's trick: **unify the verdict, never the resolution.** The shape rule cannot see a link *inside*
the data root pointing out of it (`innocent/stolen` is perfectly ref-shaped); only the real-path re-check
can, and `DataRefTest` pins both directions (an escaping link is refused, an internal alias is not) via
`TestLinks`' junction fallback so the tests actually run on Windows. **Pinned in the same pass:
`PathJail.contains` returning `true` when the filesystem will not answer is DELIBERATE**, not an
oversight — it skips only the symlink *re*-check (structural `startsWith` has already passed), and the
null means perms or a race, which says nothing about which way to fail; refusing would turn transient IO
noise into a refusal of every legitimate config. ⚠ Tier 4's *other* sites (static serving, archive
extraction targets, the store `fileFor` helpers) still do not re-check symlinks — those are untidy, not
decided; nothing schedules them.

⚠ **The store glob is interpolated into SQL, never bound** — DuckDB's table functions take a literal.
`SqlViews.reader`/`pathList` double `'` → `''` (so a store under `O'Brien` renders a valid literal
rather than a broken one); `SqlViewsTest` pins it and ⛔ it must not be "simplified" away. The caller-supplied
store name on `DbBrowserRoutes` is sound on both axes — normalize + `startsWith` against an already
normalised `dataRoot`, then the escaped literal — and is now pinned at **403** (not merely "some 4xx":
a 404 would mean the jail never ran and the name fell through to the existence check).

⛔ **A containment check must never report success when it refuses.** Two did, and both were fixed
2026-08-14: `MetadataValidateTask.missingPhysical` skipped an escaping `physicalRef` as "not ours to
verify", so a space carrying one **audited clean** — an escaping ref is a *worse* finding than a missing
store, and it now emits one; and `SpaceManager.delete` logged *"Deleted + purged"* whether it deleted
the tree, found nothing on disk, or refused for escaping the spaces root — the three outcomes are now
distinct and the refusal throws. ⚠ Neither was a live escape: `spacesRoot` is absolute and normalized at
`discover()` and `SpaceId` forbids separators, so that guard held by construction. These were *reporting*
defects, which is exactly why they survived — a guard whose failure reads as success is not a guard.

⚠ **A Job's findings do not travel in its `JobResult`** — that record carries status + one message +
duration, and it is what `JobRunLedger`/`DbJobRunStore` persist. Detail goes to `JobContext.log()`
(persisted per-run) and to the Signal ledger (`metadata_validate` puts the whole list in
`maintenance.metadata.findings`), so **RCA is served**; ⛔ do not "fix" this by widening `JobResult`
(72 construction sites, and a findings list does not belong in a ledger column). The consequence is for
*tests*: driving the ctx-less `Job.run()` overload discards every finding and can only count them,
which cannot tell "reported the right thing" from "reported the wrong thing the right number of
times". Drive `run(ctx)` with `CapturingJobContext` (`com.gamma.job`, test sources — **one** double for
every Job test; the consignment copy was collapsed onto it 2026-08-14).

⛔ **Do NOT record audit findings as a Run Artifact.** Considered and rejected 2026-08-14 on visibility
+ stability: the Run Log is *already* queryable per run (`GET /jobs/{name}/runs/{runId}/log` and
`/logs`, the UI's live-tail panel) and the findings also ride the Signal ledger, so an artifact adds no
reachability — it adds a **second copy of the same information**, which is the exact failure mode that
produced the disagreeing-guards bug above. `ArtifactRecorder` is the right home for a *produced
thing* (`BackupTask`'s zip, `SqlTemplateJob`'s Parquet, `storage_report`'s per-axis sample), not for a
report the Run Log already carries.

**Why these live here and not in a `ConfigSpec`.** `FieldSpec`/`ConfigSpec` are flat-dotted-path only —
`FieldType.MAP`/`LIST` assert the container type and never walk into entries, and there is no
map-of-objects/list-of-objects primitive. Every repeated sub-shape in the codebase (`sinks[]`, and now
`references.<name>`) is therefore validated by a hand-written per-entry method here; `checkSink` is the
precedent `checkReference` follows. A future map-of-objects notion in the spec layer would subsume both.

Only `pipeline` and `enrichment` config types have a write surface to gate. This is tied to the write-gate:
when `-Dassist.write.root` is set, writes are jailed to that root and validated here (see
[auth & security](../editions/auth-security.md)).

## One save gate for every door — `SaveGate` (2026-09-23)

`SaveGate.check` (`inspecto/src/main/java/com/gamma/control/SaveGate.java`) is **the** list of content
checks a config save runs, and every Pipeline-config door calls it: `POST /config/write`,
`POST /config/patch`, `PUT /pipelines/{name}/graph`, `POST /pipelines/import`, and `POST /validate`
(both branches — it reports what a save would refuse; it never refuses). A caller refuses on any ERROR
(`SaveGate.refuses`). The list, in order: spec validate · `ConfigSafetyValidator` (a job judged from the
Space config root, everything else from its own directory) · schema-file resolution (arming split — see
below) · the five arming checks · unknown collector Connection · the `webhook:` block · `AcceptedConfigKeys`
census · route-predicate columns · summarize measure types · step configs (lookup, filter, dedup, profile — G4).

🔴 **An ACTIVE pipeline whose `schema_file` resolves nowhere is REFUSED** (`ERR_SCHEMA_FILE_UNRESOLVED`,
`ConfigRoutes.schemaArmingFindings`, 2026-09-25); an inactive draft keeps the WARNING
(`WARN_SCHEMA_FILE_UNRESOLVED`), so a scaffold may name its `<id>_schema.toon` before the Parse Apply
writes it. It used to be a WARNING at every severity, reasoning the file "may be created after the save,
or belong to another host" — but an active config in this Space's tree is loaded by THIS server, and a
missing schema turns it into a "Does not load" row with no run. Driven 2026-09-25: a pipeline whose Parse
Apply had written its schema under the wrong name (`SCHEMA-FILE-NAME-1`) saved, validated and **activated**
clean, then never ran. Activate is `PUT …/graph` with `active: true`, so it is refused with the reason;
`/validate` reports the ERROR. The file-for-another-host case is exactly the inactive draft (and bundle
import, which always lands inactive). Pinned by `ControlApiSchemaFileRefTest`. ⚠ Only the save doors
changed: `RunRoutes`/`DataSourceRoutes` already used ERROR, the template copy keeps WARNING (templates
never run).

🔴 **Why one function.** Before G3 (`PROCESSOR-RELEASE-READINESS-1`) each route carried its own hand-kept
copy of that list, and the copies had drifted — reproduced over real HTTP before the fix:

| door | missing before G3 |
|---|---|
| `PUT …/graph` (its comment claimed "the same gate") | unknown collector Connection, key census |
| bundle import | key census, both TypeFlow checks |
| `/validate` draft | Connection, key census, both TypeFlow checks; safety was opt-in (`safety:true`) |
| every door, `/config/write` included | any `webhook:` check at all |

⚠ The row's other half — "config write lacks `routeColumnFindings` + `summarizeMeasureFindings`" — was
**already false**: `/config/write` ran both in a second gate after deriving the target. The shared gate
retired that second gate by running once, BEFORE any path is resolved (422 still precedes the subdir-jail
403, `WriteGateOrderTest`), judged from the *prospective* directory — the write root, or the requested
`subdir` when it stays inside it.

**The `webhook:` block** is judged at save as `WebhookSink.plan` judges it at run time, regardless of
`active`: it must parse (`ERR_WEBHOOK_INVALID` — an authored `url:`/`token:`, an unknown key, a
`batch_size` out of bounds; the parser's own message), its `connection` must name a Connection this Space
holds (`ERR_WEBHOOK_CONNECTION_UNKNOWN`), and that Connection must be `https`
(`ERR_WEBHOOK_CONNECTION_NOT_HTTPS`). Host, tunnel and proxy stay the Connection's own validation.

**The `excel:` block** (`sink.excel`, 2026-10-06) is judged the same way, regardless of `active`: it must parse
(`ERR_EXCEL_INVALID` — no `path`, a path that is absolute or carries `..`, not `.xlsx`, a sheet name Excel
refuses or a duplicate, `max_rows` out of bounds, an unknown key; the parser's own message), and every
sheet's `sql` must pass `SqlGuard` (`ERR_EXCEL_INVALID` on `excel.sheets[i].sql`). The run re-checks the
path with `PathJail` against the data root, symlinks included.

⛔ **The one deliberate difference is bundle import, and it is a recorded decision, not drift**
(`SaveGate.Referents.MAY_ARRIVE_LATER`): a pipeline bundle never carries its Connections (secrets never
travel) and always lands inactive, so a **missing** Connection — collector or webhook — is a
`WARN_UNRESOLVED_CONNECTION`, never a refusal. A Connection that exists but is the wrong kind is refused on
import too. `/validate` drops its `safety` flag: `safetyChecked` is always `true`.

Pinned by `ControlApiSaveGateParityTest`: every fault × every door, one verdict (35 cases + a clean control + a valid `excel:` block
+ the graph editor's own `sink.webhook` node shape). A new check added to `SaveGate` reaches all five doors
at once; a door that stops calling it goes red there.

### The four Pipeline edits joined the gate — and a guard enumerates every door (2026-09-26)

*`ASSURE-MAKER-CHECKER-1` S0.* `POST /pipelines/{name}/label`, `/settings`, `/save-as-template`
(`PipelineSettingsRoutes.java`) and `/rename` (`PipelineRenameRoutes.java`) used to hand-roll spec +
`ConfigSafetyValidator` only. They now run `SaveGate.check`. Label, settings and rename edit a config already
on disk, so they judge it **before and after** and refuse only the ERRORs the edit *introduces*
(`SaveGate.introduced`) — the posture they had, over the whole list now. Save-as-template runs the full gate
on the new config (its schema-file check is the gate's inactive-draft WARNING, as before).

`ConfigWriteFunnelTest` enumerates every mutating registration site in every reactor module (the scan
`CapabilityManifestTest` trusts), follows each handler through the methods it calls **in its own source
file**, and fails when a route that encodes TOON (`ConfigCodec.toToon` / `JToon.encode`) never reaches
`SaveGate`. The routes that write a kind `SaveGate` has no arm for are on its `NO_SAVE_GATE` table with a
reason: Tags and Case Rules (inspecto-ops), Connections (secret-aware CRUD), the five `/jobs` writers
(`JobRoutes` runs the job spec + `checkJob` itself — the two checks `SaveGate`'s job arm runs, not the whole
list), and `/bundle/import` (components, jobs, enrichments; Pipelines import through `/pipelines/import`).
Proved by mutation: stripping `SaveGate` from `/settings` turns the test red, naming the route.
⚠ The closure stops at the file boundary — a route whose write lives in another class (the settings
documents, whose records write themselves) is invisible to it.

### Step config checks (G4)

*Added 2026-09-23 (`PROCESSOR-RELEASE-READINESS-1` G4).* `ConfigRoutes.stepConfigFindings`, run by
`SaveGate` after the dedup-window check, refuses at save what `RowShaper` used to refuse only on the first
run. It reads the `steps:` chain and the legacy `processing.dedup` / `processing.profile` blocks:

| Step | refused at save |
|---|---|
| `lookup` | no `column`; no `mappings`; a mapping that is not `key=value` (named); a `column` the schema does not declare |
| `filter` | a blank `where`; a `where` that does not bind against the known columns (or is not a safe read-only expression) |
| `dedup` | an empty or absent `keys` list; a key the schema does not declare |
| `profile` | a named column the schema does not declare (an empty block still means "every column") |

Codes `ERR_STEP_CONFIG_INVALID` (active) / `WARN_STEP_CONFIG_INVALID` (inactive draft), the same
severity split the arming checks use. ⚠ **Columns are judged only while the row is still the schema's.**
From the first `sql`, `join`, `summarize`, `profile` or `route` step on, the inbound columns are unknown
here, and unknown is not wrong. A `lookup` with a `target` adds that column. An unreadable schema says
nothing, as with the TypeFlow checks.

**Branch sub-chains are walked** (as-built 2026-09-23): a `route:` branch's own `steps[]` — under a
`route` step in the chain (`steps[i].route.branches[b].steps[j].<kind>`) or under the legacy top-level
`route:` block (`route.branches[b].steps[j].<kind>`) — gets the same checks. Each branch starts from a
copy of the columns known at the route point, and the same reshaping rule applies inside it. **The filter
`where` column check reuses the route-predicate bind** of `routeColumnFindings`: `SqlGuard.check` on the
assembled `SELECT * FROM "input" WHERE …` probe, then `TypeFlow.describe` against the known columns, so
DuckDB's binder decides (no second predicate parser). The known columns are the raw fields **plus the
schema's `mapping.fields[]` names** (steps see the mapped row, so a `custom`-derived column like the shipped
filter_step's `GROSS` is real). 🔴 Only a genuine `Referenced column … not found` binder error is refused;
every other bind failure (unknown function, type mismatch, the probe's own mechanics) **fails open** — the
first cut refused the shipped `filter_step` Pipeline because it knew only the raw fields, and DuckDB's JDBC
wraps every bind error as "Attempting to execute an unsuccessful or closed pending query result".
**The legacy filter is checked too** (as-built 2026-09-24): it is `processing.csv_settings.where` (the key
`PipelineConfig.resolveSteps` projects as the first `filter` step — there is no `processing.filter` key),
bound first in the legacy order against raw + mapped columns, anchored at `processing.csv_settings.where`
with the `*_STEP_CONFIG_INVALID` codes; a blank value means "no filter" and is not refused. **A `route`
step's own branch predicates** (`steps[i].route.branches[<key>].where`) are bound against the columns
known at that point in the chain (skipped once they are unknown, e.g. after an `sql` step) by the same
helper `routeColumnFindings` uses (`branchPredicateFindings`), so they carry the
`ERR_/WARN_ROUTE_PREDICATE_COLUMN` codes, the same fail-open rule and the same SqlGuard-on-assembled-probe
rule; a blank predicate stays `routeArmingFindings`' refusal and is not double-reported. Pinned by
`StepConfigSaveFindingsTest`.

### Collector checks — throttle, breaker, archive (2026-09-24)

*Added 2026-09-24 (`PROCESSOR-RELEASE-READINESS-1`, the "fails clearly at save" point for
`control.throttle`, `control.circuitbreaker` and `sink.archive`).* Three faults used to save clean and do
nothing, or fail only at load:

| Fault | Before | Now |
|---|---|---|
| `collector.fetch.rate_limit: fast` | saved; `parseRate` threw at LOAD | 422 at save — the spec carries `parseRate`'s grammar (number, optional `KB`/`MB`/`GB`/`B`, optional `/s`/`ps`/`/sec`) |
| `collector.post_action.on_success: MOEV` (or an unknown `on_unsupported`) | saved; the run logged a warning and silently RETAINED | 422 at save — both are spec ENUMs (any case) |
| `on_success: MOVE` with a blank `archive_path` | saved; the connector moved the file onto its own path | `ERR_COLLECTOR_CONFIG_INVALID` (active) / `WARN_COLLECTOR_CONFIG_INVALID` (inactive draft) |
| `fetch`, `retry`, `circuit_breaker` or a non-RETAIN `post_action` on a **local** inbox | saved; `CollectorProcessor.acquire` returns at once for `local`, so none of them ever engaged | `WARN_COLLECTOR_KEY_INERT`, naming every inert key — a warning, never a refusal (harmless, and a Connection may be bound later) |

`ConfigRoutes.collectorFindings`, run by `SaveGate`, so every save door gets it. A Collector bound to a
`connection` is judged remote even with no `connector` — the form derives it from the Connection. Pinned
by `CollectorSaveFindingsTest` and, for the spec half, `CollectorResilienceSpecParityTest` (spec ⇔ parser on
the rate grammar; the unknown post-action is one-way — the engine would retain, the spec refuses).

## Decision 2026-09-06 — job configs get a save-time spec; the depth rule stays

(a) Job `.toon` files bypass `ConfigSafetyValidator` at save because no `ConfigSpecs.job()` exists, so
containment is enforced only at run (`PipelineJobRunner`). **Decided:** close it — add a job spec and route job
writes through the same 422 gate, keeping the run-time check as belt. **Shipped 2026-09-06:** grounding found
`ConfigSpecs.job()` already existed but `POST/PUT /jobs` skipped both it and the validator; `JobRoutes.parseJob`
now runs both (ERRORs 422 with `field: message`), `ConfigSafetyValidator` gained a `job` case jailing
`data_dir` / `pipeline_config` / `dir` / `backup_dir` / `archive` / `target_dir`, and the spec's stale
"type must be enrich|report|maintenance" rule — which would have refused every pipeline job — was removed.
(b) `requireTopLevelSinks` is a rule about literal directory nesting ("no store inside another store's tree"),
not a jail; resolving real paths would change its answer for the wrong reason. **Decided:** keep as designed —
a standing refusal (BACKLOG §6).

## The accepted-key census — which config types have one, and why not the rest

`AcceptedConfigKeys` (`inspecto-config/src/main/java/com/gamma/config/spec/AcceptedConfigKeys.java`) is the
second gate folded into the same 422 at every save path — since G3 (2026-09-23) through the one shared
gate `SaveGate.check`, see [one save gate](#one-save-gate-for-every-door--savegate-2026-09-23) below:
**a key no component reads is refused** with
`ERR_UNKNOWN_CONFIG_KEY` and a near-name suggestion (`DUCKLE-C3-DEAD-PROPERTY-1`). A dead key is a
*silent loss* — the save answers `written: true` and the engine never looks at it.

**Four of the nine config types have a census: `pipeline`, `alert`, `meta` and `enrichment`.** The
other five (`job`, `schema`, `expectation`, `widget`, `dashboard`) are **fail-open by
omission, and that is stated rather than accidental**: `unknownKeyFindings` returns nothing for a type
with no census.

🔴 **Why the missing five cannot simply be switched on.** Two authorities read every config: what
`ConfigSpecs` *declares*, and what the engine's hand-written parser *navigates*. An accepted set derived
from `ConfigSpecs` **alone is unsound** — it would refuse keys the engine honours today. This is not
hypothetical; each of these has confirmed undeclared-but-engine-read keys:

| Type | Undeclared keys the engine reads | Blocker |
|---|---|---|
| `job` | `on_signal`, `when`, `catch_up`, `coalesce`, `args`, `bind` | `JobConfig.fromMap` also funnels **any** other key into an open `params` bag — a census needs a job-type registry, not a parser walk |
| `expectation` | `when` (required for `kind: condition`; ⚠ the spec's `kind` enum omitted `condition` entirely until 2026-09-17) | ⛔ **a census here is close to a no-op too**: expectations are authored through `/expectations*` (`ExpectationRoutes`), not `/config/write`, and the persisted content carries `lastResult` / `createdAt` / `updatedAt` bookkeeping (`ExpectationRoutes.java:105-117`) that no `ConfigSpec` declares — the same shape that struck `widget`/`dashboard` |
| `schema` | `mapping.fields`, `mapping.rules[].targetColumn`, `partitions[]` | ⛔ **there is no single schema parser to census.** Its blocks are navigated by a dozen classes across `inspecto-etl` — `Identifiers.validateSchema:124-170`, `DataTransformer:96,169,239,267`, `PartitionDef:95`, `ParserSpec:28`, `SourceZones:84`, `TypeFlow:125`, `BoundaryScanner:115`, `SchemaMappingDrift:74,84`, `DuckDbCsvIngester:698`, `ConsignmentPlanner:152`, `PipelineConfigParser:1262-1884` — so "the reads" are not enumerable from one source file and the ratchet idiom cannot be written |
| `widget`, `dashboard` | none found in a Java reader | ⛔ **a census here would be a no-op**: neither type is ever written through `/config/write`. The UI saves both through the component-store routes (`POST`/`PUT /components/{kind}`), which never call this class. A gate belongs in `ComponentRoutes` — and it is not a table away, because the persisted body also carries `name`, `owner` and `shares`, which no `ConfigSpec` declares |

⇒ **A type earns a census only when its parser's reads can be PROVEN from source**, and the proof is a
ratchet test that fails when the two authorities drift.

**`alert` earned one (2026-09-16).** `AlertRule.fromMap` (`inspecto-engine/.../alert/AlertRule.java:115-128`)
is the easiest case in the codebase: one flat block of literal `alert.get("…")` calls, no dynamic key
access, no nested sub-parsers. Seven of its ten keys are declared by `ConfigSpecs.alert()`; the other
three are `AcceptedConfigKeys.ALERT_PARSER_ONLY` — `alert.dataset`, `alert.measure` (the BI-5
measure-rule shape) and `alert.when` (the ledger-row scope filter). All three are **live**, which is
exactly why a spec-only census would have broken every measure rule authored today.

⚠ **Granularity differs per type, and must.** For `pipeline` the census stops at the top level and
descends only into `processing.*`. An alert file is **one** block — `alert:` — so a top-level-only
census would accept every alert config whole and catch nothing; `alert` is therefore a *censused parent*
and the checker descends one level into it. `censusedParents(type)` is per-type for this reason.

**`meta` earned one (2026-09-16), and it is the case that shows the test is about GRANULARITY, not
about the word `entrySet`.** The one reader of a `*_meta.toon` is `SemanticModel.load`
(`inspecto-engine/.../catalog/SemanticModel.java:95-138`; its only caller is `ServiceBootstrap:154`),
and at the level the census works — the **top level** — it is five literal `raw.get("…")` reads:
`name`, `tables`, `kpis`, `reports`, `domain`. All five are declared by `ConfigSpecs.meta()`, so
**`meta` has no parser-only list at all** — five reads, five accounted for.

🔴 `SemanticModel.load` **does** call `entrySet()`, three times — but one level DOWN, over `tables`,
`kpis` and `reports`, whose keys are names the **author invents** (a table ref, a KPI name, a report
name). That is an unbounded namespace, so `meta` is deliberately **not** a censused parent: descending
would refuse every KPI anyone ever names. A scan that merely asked *"does this file contain
`entrySet`?"* would have refused the type for the wrong reason. ⚠ Both committed `*_meta.toon` files
carry exactly the six declared keys, so nothing on disk regresses — pinned, not assumed.

**`enrichment` earned one (2026-09-16), and it is the first type where the DESCENT carries the value.**
🔴 The premise this table carried until that day was **wrong**: it listed `input`, `output`,
`triggers.*` and `transform`/`transform_file` as *undeclared keys the engine reads*, and
`ConfigSpecs.enrichment()` declares **every one of them**. Grounded against the code, exactly **one**
top-level block is undeclared — `references` — and that is the whole of
`AcceptedConfigKeys.ENRICHMENT_PARSER_ONLY`.

`EnrichmentConfig` (`inspecto-engine/.../enrich/EnrichmentConfig.java:143-236`) reaches the root map
through seven literal reads — `name`, `transform`, `transform_file`, `references`, `triggers`, and
`ToonHelper.requireSection(raw, "input"|"output")` — with no `keySet`/`entrySet`/`forEach` over the
root. Seven reads, seven accounted for. Every **other** component that reads a `*_enrich.toon` map
reads a subset of the same declared blocks: `PipelineGraphRoutes:349-352`,
`PipelineBundleRoutes:539-560` + `:608-612`, `PipelineRenameRoutes:505-515`.

⚠ **`input`, `output` and `triggers` are censused parents**, and unlike `alert` that is about value
rather than necessity: an enrichment's real settings live one level down, so a top-level-only census
would accept `input: {databse: …}` whole. The descent is sound because `fromMap` reads those three
blocks through plain locals (`in`, `out`, `tr`) with a literal key each — via **two** spellings,
`local.get("…")` *and* the `req(local, "…", …)` helper — and every leaf it reads is spec-declared.
🔴 The `req(…)` half is why the falsify-the-scan test is not ceremony: the first version of the ratchet
scanned only `local.get(…)`, missed `input.database` / `output.database`, and **the falsify test is
what caught it**.

⛔ `references` is accepted **whole** and is deliberately not a censused parent: `fromMap` iterates its
`entrySet()` over view names the author invents. Same shape as `meta`'s `tables`/`kpis`/`reports`.

⚠ **BREAKING, and one committed file moved.** `spaces/demo/config/orders/orders_daily_enrich.toon`
carried `version: 1`, which no component reads — the same dead key `pipeline` started refusing on
2026-09-16 — so it is removed rather than declared, and
`EnrichmentKeyCoverageContractTest.everyCommittedEnrichmentConfigSurvivesTheCensus` pins that nothing
on disk regresses. 🔴 **The committed `*_pipeline.toon` samples were NOT given the same treatment when
`pipeline` was censused**: **24** of them still carry `version:`, so re-saving any sample pipeline
through `/config/write` 422'd. ✅ Fixed 2026-09-16 (`45eff375e`) — `PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1` below.

⚠ The **flat** `alert-rule` component shape (`AlertRoutes`, `ComponentStore`) is a *different config
type string* and is written through `/alerts/rules*`, not `/config/write` — it never reaches this census.

Pinned by `AcceptedConfigKeysTest` (the checker), `AlertKeyCoverageContractTest`,
`MetaKeyCoverageContractTest`, `EnrichmentKeyCoverageContractTest` and
`PipelineKeyCoverageContractTest` (the four source-derived ratchets), and
`AcceptedConfigKeysDocContractTest` (the generated pipeline table). Each ratchet includes a
*falsify-the-scan* test, because a scan that silently matches nothing passes every other assertion.

## Open rows this concept owns

- ~~**`COMPONENT-KIND-KEY-CENSUS-1`**~~ ✅ **CLOSED 2026-09-16 (`afaa4005c`).** The `widget`/`dashboard`
  top-level key census lives in `ComponentRoutes.validateKind`, the only place it can work — both kinds are
  saved through `POST|PUT /components/{kind}` and never reach `/config/write`, so `AcceptedConfigKeys` stays
  correctly a no-op for them. Accepted = `ConfigSpec` fields ∪ the store envelope (`name`/`owner`/`shares`)
  ∪ a documented parser-only set (e.g. `dashboard.description`, read by `MetadataGraphBuilder` and declared
  by no spec) ∪ the `x-` extension marker. ⛔ It had to be written explicitly: `ConfigLoader.validate` walks
  DECLARED fields only and never emits an unknown-key finding, so copying the `schema` branch's idiom would
  have shipped a gate that refuses nothing.

- ~~**`COMPONENT-BULK-WRITERS-UNGATED-1`**~~ ✅ **CLOSED 2026-09-17** (bundle half `a0f82161d`, templates
  half `6708c0baf`) — see the as-built section below.
- ~~**`PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1`**~~ ✅ **CLOSED 2026-09-16 (`45eff375e`).** The dead top-level
  `version:` was stripped from all 36 committed `*_pipeline.toon` samples (24 under `spaces/`, 12 under
  `inspecto/examples/`) — nothing reads a pipeline's `version`. ⛔ The guard was the deliverable:
  `PipelineKeyCoverageContractTest` now WALKS both trees for *"no committed config regresses"* (rather than
  hardcoding paths, which would miss a new sample), with a falsification test so an empty sweep fails.

- ~~**`EXPECTATION-SPEC-STALE-VS-CONDITION-1`**~~ ✅ **CLOSED 2026-09-17.** `ConfigSpecs.expectation()` now
  declares `when` as `FieldType.MAP` — the `widget.controls` / `dashboard.filter` precedent, which
  validates the envelope and leaves the tree to the parser — and lists `condition` in its `kind` enum.
  🔴 **It was LIVE, not latent, and the row said otherwise.** `POST /validate` and `POST /config/write`
  both resolve `ConfigSpecs.forType("expectation")` from the request body, so the value rules already ran:
  driven over real HTTP, a condition body returned `clean:false` on **two** errors. ⛔ The second was a
  contradiction nobody had named — `column` was declared `required` although `Expectation` exempts the
  `condition` kind, so fixing only `when` and the enum would have left condition expectations refused
  anyway. It moved to a `column-needed-unless-condition` cross-field rule, since `FieldSpec` has no
  conditional-required. No new refusals: a widened enum and an optional field cannot refuse more.

### Both bulk writers now run `validateKind` — `COMPONENT-BULK-WRITERS-UNGATED-1` CLOSED 2026-09-17

`ComponentBundleSource.write` took the gate on 2026-09-16; `BiTemplates.apply` took it on 2026-09-17, in its
**resolve** loop — before any write, so apply stays all-or-nothing and never plants a partial board — mapping
`IllegalArgumentException` to 422 the way `writeComponent` does. ⚠ A bare IAE would have been a **500**: only
`ApiException` maps to a status in `ControlApi`. The two postures differ deliberately: bundle import fails
**per item**, templates fail **whole**.

🔴 **What held the second half open was a false claim recorded in a test javadoc** —
`ControlApiBiTemplatesTest` justified its build-time-only shape with *"the gate cannot be called from here
(`validateKind` is private)"*. It is **package-private** (widened for `BundleRoutes`) and `BiTemplates` is in
that very package. The claim was already false when written; the javadoc now retracts it in place.
⇒ **A stated blocker is a hypothesis too — re-check it before treating it as the reason something is open.**

⚠ **Severity is defence-in-depth, established BY CONSTRUCTION rather than by reading the accepted-set:**
`substituteTree` copies keys verbatim and `substituteAny` rewrites only String *values*, and every key
originates in a hardcoded `Map.of(...)` — so `dataset`/`prefix` cannot introduce a top-level key and a KEY
census cannot fire on today's templates. ⛔ **No new test was added, deliberately:** with no reachable input
able to trip the gate, a new test would be one that CANNOT FAIL. The gate was mutation-proven instead (bogus
key ⇒ 422, nothing written; gate removed ⇒ 200 and all four components planted), and the build-time
`everyTemplateWritesABodyTheAuthoringRouteAccepts` remains the guard that can actually go red.

⇒ Open row: **`BITEMPLATES-GATE-ORDER-1`** — `apply` checks the 409 conflict BEFORE the 422 spec gate,
inverting the `endpoint` skill's order. Observable only when a template is both conflicting and invalid,
which no curated template can be today.

### `BUNDLE-ESCAPE-TEST-IS-ENVIRONMENT-DEPENDENT-1` (open, P1, filed 2026-09-17)

`ControlApiBundleNewKindsTest.jobImportRefusesAnEscapingPathValueWithoutAbortingTheBatch` is the only guard
that a bundle cannot plant a job config whose `dir` escapes the Space. It **fails in a clean detached worktree**
at `c62b46a4` and at `218d0cff` — `failed: 0`, the escaper reported `imported` — while **passing 11/11 in the
main checkout at the same commits**.

⛔ The obvious explanation was wrong and is recorded so nobody re-derives it: a peer's uncommitted work was
NOT the cause. The commit in between (`ad29e683`) touches no bundle, `PathJail` or `ConfigSafetyValidator`
code, and the failure reproduces at a commit that predates it.

🔴 The likely mechanism is **escape depth**: the test uses `@TempDir` and climbs exactly six `..`, so
whether the resolved path lands outside an allowed root depends on how deep the temp directory is on the
machine running it. A security property is being pinned by a test whose verdict depends on filesystem layout,
and a fresh clone — the CI case — is the failing side.

⚠ Two possibilities must be separated before anything is changed: either the **gate** does not refuse the
escape and the main checkout is green by accident (so `JOB-CONFIG-THIRD-PRODUCER-1`'s fix is incomplete), or
the gate holds and only the **test** is fragile. ⛔ Do not make the test green first — that is how a real gate
gets papered over.

## Maker-checker — Pending Changes (2026-09-26)

*`ASSURE-MAKER-CHECKER-1` S1–S3.* Glossary: **Pending Change**, **Approval Policy**. Security side:
[auth & security](../editions/auth-security.md#maker-checker-a-held-config-change-needs-a-different-person-assure-maker-checker-1-2026-09-26).

**The policy** — `approval.toon` in the Space config root, `GET|PUT /settings/approval`:
`{approval: {<kind>: {required, approverCapability, fourEyes}}, expiresAfterHours}` (defaults: off, the
`canApproveChanges` capability, four-eyes on, 168 h). Validated fail-closed at save: a kind outside
`ApprovalPolicy.GOVERNABLE`, a capability no route demands, an unknown key or a bad value is 422. A file that
is present but unreadable is **not** read as off: every governable kind is held until it is fixed
(`failedClosed: true` on the GET).
**The route is refused** (operator, 2026-09-28): an empty or absent body is 422 — turning four-eyes off must be
explicit, `{"approval": {}}`; and with no Authenticator configured `PUT /settings/approval` is 403 before any
other gate, because the `canAdminister` wrap is a no-op without a Subject and the policy must never be switched
off unauthenticated. **Personal edition therefore cannot change the approval policy through the API.**

**Governable** — `pipeline`, `schema`, `enrichment`, `meta` (the `/config/write` + `/config/patch` types) and
every `ComponentStore` kind except `requirement` — `channel` and `notification-rule` too since 2026-10-06
(their six `/notifications/channels*` + `/notifications/rules*` writers hold after their 422/404/409 gates; the
replay applies on approval, so a held channel edit never re-points a destination early) — and `job` (operator,
2026-09-28): the six `/jobs` writers — create, update, delete, enable, disable, reschedule — hold after their
gates (the Job spec + `checkJob`, the `event_prune`/`restore` `canAdminister` check, `If-Match`), keyed by the
Job name; the replay applies as the author, so `JobAuthority.stamp` writes the AUTHOR as `updatedBy`, never the
approver, and the run-time `canAdminister` re-check (MAINT-TASK-AUTHORITY-1) judges the author. A Pipeline rename
refuses to rewrite a governed Job's `on_pipeline`. ⛔ A Job's RUN-time writes (`MaterializeTask` restating its
target Dataset, engine writes) are NOT held, by decision (operator, 2026-09-28): holding them would stall
scheduled runs. ⛔ Not governable: Connections, Tags and Case Rules, the Space settings documents — the
approval policy above all, which could otherwise never be lifted.

**The hold** — `PendingChanges.hold(api, ex, kind, name, proposed, current)`, called by each authoring route
after every validation it runs and before any side effect: `/config/write`, `/config/patch`,
`DELETE /config/{type}/{name}`, `PUT /pipelines/{name}/graph`, the history restore, `/label`, `/settings`,
`/save-as-template` (its schema copy is taken back while held), `/rename` (before the journal's step 0), and
the component writers — `/components/*` create / update / restore / delete, `/alerts/rules*`,
`/decision-rules*`, `/expectations*`, `/access/catalog`, `/access/profiles*`. With no rule for the kind it
returns and the route writes exactly as before. With one, it stores the Pending Change and throws
`PendingChanges.Held`, which `ControlApi.routeDispatch` answers `202 {status: pending, written: false,
pendingChange}`. One pending change per kind + name at a time (a second is 409). The author may send
`X-Change-Reason`.

Writers that cannot be ONE Pending Change **refuse** under a policy (409) instead —
`PendingChanges.holdRefusing`: `/bundle/import`, `/pipelines/import` (a binary bundle a replay cannot
carry), BI template apply, and the Investigation Alert Rule bind (owner-only, so no approver could apply it).
Added after the 2026-09-26 verification:
- **`POST /import`** refuses (409) when any zip entry is a governed kind or cannot be classified
  (`PendingChanges.holdRefusingPaths` / `kindOfConfigPath`); and every import refuses the reserved files
  whatever the policy (see [auth & security](../editions/auth-security.md)).
- **A Pipeline rename** that would rewrite a dependent (Expectation / Decision Rule target, Dataset store ref,
  Alert Rule `onPipeline`, Enrichment trigger) of a governed kind is refused (409) naming it
  (`refuseGovernedDependents`, rename and resume, before step 0).
- **Tag routes** (assign / unassign / rename / delete) that would re-project a widget's `tags` refuse under a
  policy on `widget`, before the edge moves (`WidgetTags.refuseUnderPolicy`).
- **The agent's fix drafts** never overwrite an existing component of a governed kind (`PendingChanges.governs`).
- `ConfigWriteFunnelTest#everyConfigWriterRepoWideReachesTheHoldOrIsOnTheInventory` scans EVERY module —
  not only routes — for ComponentStore-shaped writes, bundle unpacks and Entity Fact log appends; each site's
  method must reach the hold or sit on its `WRITERS` inventory with a reason (shared helpers, result stamps,
  non-governable kinds, Job/engine writes, the Identity Fact log). Removing the `/import` refusal turns it red.
  `PendingAlertRules#ensureLatestDataset` is on it as a background template materializer (operator, 2026-10-06) —
  see [spaces §3.5.2](../../capabilities/spaces/spaces.md); `DecisionRuleWritersTest.GUARDED_BY` lists it and
  `#onRiskScoreProduced`, which runs `DecisionRuleGuard.refuseUnattended` before writing.

**The apply** — approve replays the stored request (method, route path + query, body, `If-Match`) through
`ApiContext.replay`, with the Pending Change stamped on the replay (`ATTR_APPROVED_CHANGE`). Every gate of that
route runs again. When the replay reaches the hold it lets the write through only if it is the same write:
the same kind + name, the content it replaces still at the **base version** (else 409, closed as `stale` —
the route's own `If-Match` 409 counts too), and the content it produces still the approved one. Versions are
content hashes that leave out write-time and result stamps (`createdAt`, `updatedAt`, `lastResult`,
`lastSimulation`), so a routine evaluation does not make a waiting change stale. Any other refusal of the
replay comes back as its status and leaves the change pending.

**Storage** — one JSON document per change at `<write-root>/pending-changes/<id>.json` (ids
`pc-<yyyyMMddHHmmss>-<6 hex>`), the `ReconStateStore` pattern: atomic temp + move, jailed, an unreadable
document is an error, never "absent". Not an OperationalDb family, so there is no backup / bundle-staging
lockstep; it sits in the config tree. Expiry is recorded lazily — on the next list, read or decide.
Each record carries a MAC (HMAC-SHA256); a record that fails it reads as `status: invalid`, is never re-saved
and cannot be decided. **The key** (`PendingChanges.keyFile`) lives OUTSIDE the config tree, in the sibling
`<config root>.secrets/.pending-changes.key` — `<space>/config.secrets/` for a hosted Space,
`<assist.write.root>.secrets/` for the default Space — so no export (`/export` walks the config tree), import
(writes only under the config root) or Exchange share (`<spaces-root>/_shared/`) can reach it; `BackupTask`
skips any `*.secrets` directory and the key file by name; `.gitignore` ignores `spaces/*/config.secrets/` and
`spaces/*/config/pending-changes/`. It is created with `CREATE_NEW` on every first use (no exists() pre-check),
so exactly one writer ever creates it and a racer reads the winner's key — a replaced key would invalidate every
record the first one signed. Owner-only where the platform allows: POSIX `rw-------`; on Windows an ACL with one
entry, the owner. Pinned by `PendingChangesKeyTest` (location, a two-thread race, permissions, git-ignore) and
`BackupTaskTest#aBackupNeverCarriesAPendingChangeKey`. ⚠ The store stays in the config
root rather than a data directory because the default Space has no reliable per-Space data dir (its data dir is
the CWD-relative `database`); the whole-Space export skips `pending-changes/`, and every import refuses it.
Approve dispatches only a route on `PendingChanges.REPLAYABLE` (the routes that hold before they write).

**Reads** — `GET /pending-changes[?status=&kind=]` (capped at 500, `total` + `truncated`),
`GET /pending-changes/{id}` (with `current` and `proposed`), `GET /pending-changes/{id}/diff` — the Pipeline
history's line diff (`PipelineHistory.diff`) over both contents encoded as TOON.

`ConfigWriteFunnelTest#everyConfigWritingRouteReachesTheMakerCheckerHoldOrIsExempt` fails a config-writing
route that reaches neither `hold` nor `holdRefusing` unless it is on `NO_HOLD` with a reason. Pinned over
real HTTP, with an armed Authenticator, by `ControlApiPendingChangesTest`.

⚠ Known limits: the guard's closure stops at the file boundary (a write in another class is invisible). (Four-eyes still forbids self-decline, as in
Link Analysis; since 2026-09-28 the author instead WITHDRAWS their own change — `POST /pending-changes/{id}/withdraw`,
status `withdrawn`, author-only, see [auth-security](../editions/auth-security.md).)

**The inbox (UI)** — `/pending-changes` (`modules/admin/pending-changes/`, nav *Operations ▸ Pending
Changes*, beside the agent *Approvals Inbox*, which is untouched): the waiting (or all) changes in a
data-table; selecting one shows author, reason, expiry, decision and the diff through the shared
`<inspecto-line-diff>` (extracted from the Pipeline config-history dialog, which now uses it too). Approve /
Decline, with an optional reason, show only with `LensService.canApproveChanges` (action node
`changes.approve`); a server refusal — four-eyes, a stale base, the route's own 403/422 — is shown in place.
**Withdraw** shows only to the change's author (`SessionService.actor` equals `author`) while it waits,
behind `confirmDestructive`; a refusal toasts the server's message.

## Safety Policy tier files — loading and the unreadable refusal (2026-10-03/04, `DUCKLE-C6-POLICY-NARROWING-1` S2a + S2b)

Design: [`policy-narrowing-design.md`](../../../archived-documents/plans-archive/policy-narrowing-design.md). `SafetyPolicyFiles` (`inspecto-config`, `config/safety/`) reads `safety-policy.toon` from `-Dsystem.config.dir` (server tier) and `<space>/config/` (Space tier), strictly, and folds them through `SafetyPolicyTier.fold`. `SafetyPolicy.forSpace` — hence `defaultPolicy()` and all 15 `ConfigSafetyValidator.check` callers — now takes its roots, thread/batch caps and formats from that effective tier, so a Space file can only narrow the operator-declared roots + the Space base.
- **Durable decisions (operator, 2026-09-28; the plan is archived 2026-10-06).** D1 the name is **Safety Policy**, the existing `SafetyPolicy` record extended, never a second type (⛔ bare *Policy*). D2 tiers are **server + Space**; a Pipeline tier is safe under the same fold but nothing asks for it. D3 the server file is `safety-policy.toon` in the server config dir; `-Dassist.safety.roots` stands in for `allow.roots` when the file omits them. D4 a Space's default roots are **its own base + the operator's declared roots**, never every hosted Space's. D5 `mode: enforce | audit`, server file only, default `enforce`; `audit` logs the would-refuse and proceeds. D10 `permit.rewind_state` is separate from `permit.advance_state` (default `true`), so "may not advance" never blocks a reprocess. D11 it is **core, every edition** (config safety, not ABAC) so the unreadable refusal cannot vanish on a classpath without `inspecto-policy`. D14 caps fold by **MIN**; the validator's existing caps are the server tier's defaults. Fold law: booleans AND, sets intersect (allow) / union (deny), numbers MIN, prefixes matched at a path or dot boundary — a lower tier can only narrow. ⚠ Absent ≠ empty: an absent `allow.*` does not constrain, `allow.*: []` permits nothing.
- 🔴 **Unreadable ⇒ throws, never "no policy".** TOON damage, a stat/IO failure (a *directory* named `safety-policy.toon` forces it), an unknown key (a mistyped `alow:` would otherwise silently drop the narrowing), a bad value, a relative root, `mode` or `require_spaces` in a Space file → `SafetyPolicyUnreadableException` (`ERR_SAFETY_POLICY_UNREADABLE`). `require_spaces[n]` in the server file (D7) makes a named Space with no file unreadable too; an un-named absent file narrows nothing.
- Parsed files are cached by (mtime, size); a failure is cached too and re-evaluated when the stamp changes.
- **S2b (2026-10-04) — the refusal surfaces, fail-closed everywhere.**
  - *Plan-time gates:* `ControlApi.errorBoundary` maps `SafetyPolicyUnreadableException` to **422** with `errorCode ERR_SAFETY_POLICY_UNREADABLE` (a new `ErrorCodes` entry + the contract's `ErrorCode` enum) and a `findings` array whose ERROR finding's `fieldPath` is the file. One boundary mapping covers every `ConfigSafetyValidator.check` caller that runs inside a route (SaveGate, RunRoutes, JobRoutes, DataSourceRoutes, EnrichmentRoutes, ComponentRoutes, BundleRoutes), because `defaultPolicy()` is what throws. The three agent-tool callers (`ComponentActions.apply`/`preview`, `InspectoTools` validate) catch it themselves and fail closed as a refusal/ERROR finding. Audit status is 422, not 500 (`failureStatus`).
  - *Runs:* `SafetyPolicy.pinnedForRun(Supplier)` resolves the Space's effective policy **once, before the run claims anything**, throws if unreadable, and pins the snapshot (D8) in a `ScopedValue` that `defaultPolicy()` returns for the rest of the run on that thread; a file tightened mid-run applies to the next run. Wired at `CollectorService.runPipeline` (async trigger records a `FAILED` Run whose message is the reason; the sync off-thread path rethrows into the 422 mapping), `PipelineScheduler.runOne` (poll failed, counted) and `JobService.runJob` (a `FAILED` job Run naming the file; the job body never starts).
  - *Health (D6):* `GET /health` stays 200 and serves, but reports `{"status":"DEGRADED","safetyPolicy":[...]}` while any file in scope (server file, each hosted Space) is unreadable; `SafetyPolicy.unreadable()` backs it.
  - ✅ **The load-time vanish is closed (S3, 2026-10-04).** `PathJail.allowedRoots()` is a third consumer of `defaultPolicy()`, so an unreadable file used to make `ConfigRegistry` log "Could not load config … ERR_SAFETY_POLICY_UNREADABLE" and drop the pipeline. The one load-time jail (`PipelineConfigParser.resolveSchemaRef`) now catches the exception and judges the ref against `SafetyPolicy.baseRoots(space)` (the operator roots + the Space base, no policy file read, never wider than the pre-narrowing posture); the pipeline stays registered and its **run** is the visible refusal (failed Run + `ERR_SAFETY_POLICY_UNREADABLE`, `/health` DEGRADED, 422 at every gate). Loading is not running; every act-time gate still throws.
  - ⚠ The pin is a `ScopedValue`: visible on the planning thread, **not** on a worker pool. S3 hands it over where a run fans out: `SafetyPolicy.pinned()` captures the snapshot, `SafetyPolicy.runWithPinned(policy, run)` rebinds it on the worker (`MultiCollectorProcessor.runAll` / `runConfigs`). A new act-site on its own pool must do the same, or it re-reads the files (a mid-run change) and, with no Space MDC, reads the `default` Space's policy.
  - Tests: `SafetyPolicyUnreadableRoutesTest` (T5, T6 at run level, the 422 + `/health` over HTTP), `JobServiceTest.anUnreadableSafetyPolicyFileRefusesTheRunAsAFailedRun`, `SafetyPolicyFilesTest` (pin + refusal-before-start). Mutation-checked: disabling `pinnedForRun` turns T5/T6 red with `SUCCESS` where `FAILED` is expected.
- 🔴 **`safety-policy.toon` is a RESERVED config file (2026-10-04).** `ReservedConfigPaths.FILES` lists it, so every import door (`POST /import`, `/spaces/import`, bundle/template seed) refuses it by name, whatever its case or Windows alias. An imported copy could narrow or brick a Space, and a *tightened* Space file is operator intent that no bundle may rewrite. The file is authored on disk by the operator only. `ImportLoaderInventoryTest` caught the unreserved read; `ControlApiImportReservedPathsTest.ALIASES` pins the refusal on every door. `POST /spaces/import` now has an armed-Authenticator literal in `ControlApiSpacesTest` (the guard matches the bare path, not `?id=`).
- **S3 (2026-10-04) - paths.** `allow.roots` already narrowed `SafetyPolicy.allowedRoots` (S2a); `deny.roots` is now `SafetyPolicy.denyRoots` (a new record component, from the folded tier). `PathJail.requireUnderAny(roots, deny, value, field)` refuses a contained path that also lies under a deny root (deny beats allow; boundary-correct, `secrets2` is not under `secrets`); the 3-arg form and therefore `requireJobPathUnderAny` and every act site in design 1.2 take the deny set from `PathJail.deniedRoots()` = the calling thread's `defaultPolicy()`. `ConfigSafetyValidator.checkPathValue` gives the same verdict as a finding. P0-c twin: an absolute path into a string-prefix sibling Space (`s10` vs `s1`) is refused, the Space's own path passes. Tests: `SafetyPolicyPathsTest` (7; the deny loop mutation-checked: 3 red) and `SafetyPolicyUnreadableRoutesTest.s3_...` (the load fallback, mutation-checked). Hooks note: a bare unit test has no Space MDC (no slf4j binding), so the unit tests use the `default` Space.
- **S4 (2026-10-04) — egress: `EgressGate`.** `EgressGate.require(host, port, purpose)` (`config/safety/`) refuses on `permit.network false`, an `allow.hosts` miss, a `deny.hosts` hit, and (D13) a permitted *name* that resolves into a denied CIDR (DNS is resolved only when a CIDR deny exists); `mode: audit` logs and proceeds. `EgressGate.current()` reads the calling thread's pinned tier (`SafetyPolicy.effectiveTier()`, new — the `Pin` now carries the tier); `EgressGate.of(tier)` is the explicit hand-off, `EgressGate.server()` the server-tier-only gate (D16). Helpers `requireJdbcUrl` (every host of a multi-host URL; in-process `duckdb`/`sqlite`/`h2` pass; a URL whose host cannot be read is **refused**, not waved) and `requireHostList` (Kafka `bootstrap.servers`).
  - *Act sites:* **N1** `CollectorConnectors.forConfig` gates the whole profile before any connector exists — target, SSH bastion, proxy, `options.jdbc_url`, `options.bootstrap_servers` — so SFTP/FTP/DB-export hops (N2/N3/N6) are covered at the one seam and **T14** (a pipeline placed on disk, no save gate) refuses there. **N4** object stores: `AbstractHttpObjectStoreConnector.send` gates every request, which includes the GCS token endpoint (it sends through `sendReadingBody`). **N5** `KafkaConnector` checks every partition **leader** host after each `partitionsFor` (discover, backlog probe, `fetchTo`) before any offset lookup or fetch (D12). **N7** `ConnectionTester` gates its probe socket. **N8** `POST /system/operational-db/test` passes the **server** tier (422 + the refusal) before the password is resolved.
  - 🔴 **The design's "N4 redirect rewrite" was already shipped, stronger.** `Redirect.NORMAL` no longer exists: `PinnedObjectStoreHttp` (OBJECT-STORE-EGRESS-POLICY, 2026-09-27) never follows a 3xx, so the `Location` host is never dialled — T7's "B allowed → followed" half is superseded on purpose (a SigV4 signature covers `host`, so a followed redirect would fail anyway). `ObjectStoreEgressTest.aRedirectIsNeverFollowed` pins it.
  - ⚠ **Worker threads:** connectors capture `EgressGate.current()` in a field when they are *built* (planning thread, inside the run pin); a component that dials from a pool must do the same, never call `current()` there — off the pin it re-resolves the thread's Space, which on a pool thread is the `default` Space.
  - Tests: `EgressGateTest` (10), `SafetyPolicyEgressTest` in `inspecto-connectors` (T14, T8, T7 request gate, T12 leader hosts — each with an allowed twin and an observed socket/request count), `ConnectionTesterTest`, `ControlApiSystemRoutesTest`. Mutation-checked: removing the N1 gate turns T14 and T8 red.
  - Not gated by S4 (by design or deferred): N10–N12 (server-configured, D15); `allow.connectors` (scheme gate, not a host); the DuckDB-side egress N13–N15 is S5.
- **S5 (2026-10-04):** `DuckDbExtension` honours `allow.extensions` and refuses the `INSTALL` fallback under `permit.install_extensions: false`; `DuckLakeRegistrar` gates the catalog host and a remote data path through `EgressGate` before its catch-all. Still open: the `IndexBuilder` file-access opt-in.
- **S7 (2026-10-04) - explain.** `GET /settings/safety-policy` (`SafetyPolicyRoutes`; `canAdminister`, in `CapabilityManifest`) returns the Space's effective policy, one row per field with `effective` + `constrainedBy` (`server`/`space`/`default`), the files consulted, and the D15 `exempt` surfaces. `SafetyPolicy.tiersForSpace` -> `SafetyPolicyFiles.Tiers` (server as stated, Space as stated, fold) -> `SafetyPolicyExplain`. An unreadable file answers 200 `readable:false` + the reason (a diagnostic must name the broken file; the gates still 422). Test `SafetyPolicyExplainRoutesTest`.
- ⚠ **Not yet done (S8 onward):** the explain UI (its own design). The sentinel format set `{"-"}` stands for "allow.formats ∩ defaults is empty" because the record constructor treats an empty set as "use defaults".

### S6 - state: `StateGate` (2026-10-04, `DUCKLE-C6-POLICY-NARROWING-1`)

`StateGate.requireAdvance(what)` / `requireRewind(what)` (`config/safety/`) read the run's pinned tier (`permit.advance_state` / `permit.rewind_state`); refusal is `StateRefusedException`, `mode: audit` logs and proceeds. Wired at: batch start in `ConsignmentIngestor.process` (before `strategy.ingest`, so nothing is written - M1-M3 run after outputs are durable), the M1 marker, M2 fingerprint-ledger and M3 DB/Kafka-watermark sites in `finalizeSource` (backstop), M4 `RemoteAcquisitionHandler.land` (only when a frontier exists), M5 `PipelineJobRunner` (before the sink writer for an incremental, non-dry run, and again in `advanceWatermarks`), M6 `RowShaper.windowedDedup` (before the first dedup claim), M8 `ReprocessCommand.run`, `DedupPruneTask` and `LedgerPruneTask` (`requireRewind`). The batch-start check fires when markers or the duplicate check are on, **or** any member carries a stashed watermark / durable slice-frontier record (2026-10-06 - a watermark-only Kafka/DB-export Pipeline otherwise wrote outputs and was refused only at M3, stranding them). Tests: `StateGateTest`, `KafkaSliceAdvanceStateTest` (T12), `PipelineJobRunnerTest` (T11), `PipelineExecutorDedupWindowTest` (M6), `MaintenanceLibraryTest` (prune).

✅ **D9 plan-time refusal (2026-10-06):** `ConfigSafetyValidator` (every save/register gate, like S2b/S3) refuses, under an effective `permit.advance_state: false` at save time, each key that implies an advance with `ERR_SAFETY_STATE_ADVANCE_REFUSED`: `collector.connector` other than `local`/`dataset`, `collector.duplicate.mode` other than `path`, `processing.duplicate_check.enabled` with `dirs.markers`, a `window(...)` scope on `processing.dedup` or a `processing.steps[n].dedup`, and a job's `incremental_column` (operator, 2026-10-06). Carried as `SafetyPolicy.advanceState` (a `mode: audit` policy reads as permitted). A policy tightened after the save is caught only by the act-time gates (operator, 2026-10-06). Tests: `StateAdvancePlanTimeTest`.
