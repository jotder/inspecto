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
⚠ Open beside it: `POST /pipelines/authored/{id}/dry-run` and `POST /enrichment/preview` read a join step's
caller-supplied `path:` data file with **no** jail and return its rows (or the resolved path in a 422) —
deliberate for now per the `PipelineGraphRoutes.dryRunReferences` javadoc (data files live outside the write
root), awaiting one operator decision for both (`PREVIEW-REFERENCE-PATH-UNJAILED-1`).

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

## The allowed roots are a union: declared ∪ discovered (tier 3, shipped 2026-08-14)

`SafetyPolicy.defaultPolicy()` now returns the **union** of the operator-declared
`-Dassist.safety.roots` and every hosted space base registered in
`com.gamma.config.safety.DiscoveredRoots`. This removes the misconfiguration PATH-2 tier 3 named: the
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
  so the next check sees it, no restart. **A deleted space leaves the union** (in `delete`'s
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
- ⚠ `SafetyPolicy.withRoots(...)` (skill workspace, tests) deliberately **bypasses** the union — an
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
Space config root, everything else from its own directory) · schema-file resolution (WARNING) · the five
arming checks · unknown collector Connection · the `webhook:` block · `AcceptedConfigKeys` census ·
route-predicate columns · summarize measure types · step configs (lookup, filter, dedup, profile — G4).

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

⛔ **The one deliberate difference is bundle import, and it is a recorded decision, not drift**
(`SaveGate.Referents.MAY_ARRIVE_LATER`): a pipeline bundle never carries its Connections (secrets never
travel) and always lands inactive, so a **missing** Connection — collector or webhook — is a
`WARN_UNRESOLVED_CONNECTION`, never a refusal. A Connection that exists but is the wrong kind is refused on
import too. `/validate` drops its `safety` flag: `safetyChecked` is always `true`.

Pinned by `ControlApiSaveGateParityTest`: every fault × every door, one verdict (25 cases + a clean control
+ the graph editor's own `sink.webhook` node shape). A new check added to `SaveGate` reaches all five doors
at once; a door that stops calling it goes red there.

### Step config checks (G4)

*Added 2026-09-23 (`PROCESSOR-RELEASE-READINESS-1` G4).* `ConfigRoutes.stepConfigFindings`, run by
`SaveGate` after the dedup-window check, refuses at save what `RowShaper` used to refuse only on the first
run. It reads the `steps:` chain and the legacy `processing.dedup` / `processing.profile` blocks:

| Step | refused at save |
|---|---|
| `lookup` | no `column`; no `mappings`; a mapping that is not `key=value` (named); a `column` the schema does not declare |
| `filter` | a blank `where` |
| `dedup` | an empty or absent `keys` list; a key the schema does not declare |
| `profile` | a named column the schema does not declare (an empty block still means "every column") |

Codes `ERR_STEP_CONFIG_INVALID` (active) / `WARN_STEP_CONFIG_INVALID` (inactive draft), the same
severity split the arming checks use. ⚠ **Columns are judged only while the row is still the schema's.**
From the first `sql`, `join`, `summarize`, `profile` or `route` step on, the inbound columns are unknown
here, and unknown is not wrong. A `lookup` with a `target` adds that column. An unreadable schema says
nothing, as with the TypeFlow checks. ⚠ **Not covered:** a `route:` branch's own `steps[]` sub-chain is
not walked. Pinned by `StepConfigSaveFindingsTest`.

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
| `job` | `on_signal`, `when`, `catch_up`, `args`, `bind` | `JobConfig.fromMap` also funnels **any** other key into an open `params` bag — a census needs a job-type registry, not a parser walk |
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
through `/config/write` 422s today. Worth a row.

⚠ The **flat** `alert-rule` component shape (`AlertRoutes`, `ComponentStore`) is a *different config
type string* and is written through `/alerts/rules*`, not `/config/write` — it never reaches this census.

Pinned by `AcceptedConfigKeysTest` (the checker), `AlertKeyCoverageContractTest`,
`MetaKeyCoverageContractTest`, `EnrichmentKeyCoverageContractTest` and
`PipelineKeyCoverageContractTest` (the four source-derived ratchets), and
`AcceptedConfigKeysDocContractTest` (the generated pipeline table). Each ratchet includes a
*falsify-the-scan* test, because a scan that silently matches nothing passes every other assertion.

## Open rows this concept owns

- **`COMPONENT-KIND-KEY-CENSUS-1`** — `widget` and `dashboard` can never be censused by
  `AcceptedConfigKeys`: they never reach `/config/write`, because the UI saves them through
  `POST|PUT /components/{kind}`. ⚠ Their persisted body also carries `name`, `owner` and `shares`, which
  no `ConfigSpec` declares, so a naive spec-derived refusal would reject essentially every real save.
  ✅ The seam already exists — `ComponentRoutes.java:605` calls `ConfigSafetyValidator.check("schema", …)`
  at `:602-611`, so this extends a live pattern rather than inventing one.

- **`COMPONENT-BULK-WRITERS-UNGATED-1`** — `BiTemplates.apply` (`BiTemplates.java:125`) and bundle import
  (`BundleRoutes.java:425`) call `store.write` directly, so **no** `validateKind` gate runs on that path —
  not the 2026-09-16 key census and not the pre-existing `schema` validation. ⚠ The same "gate on one
  route, not its sibling" shape that produced the census in the first place. The authoring route is the
  UI's only door, so the reachable half is closed; this is the rest.
- **`PIPELINE-SAMPLES-CARRY-DEAD-VERSION-1`** — 36 committed `*_pipeline.toon` samples (24 under `spaces/`,
  12 under `inspecto/examples/`) carry a top-level `version:` that `ConfigSpecs.pipeline()` does not
  declare, so re-saving any shipped sample pipeline through `/config/write` has 422'd since the pipeline
  census landed. ⛔ **The root cause is a missing guard, not a missing fix:** that census shipped without a
  *"no committed config regresses"* test. `meta` escaped only because it happens to declare `version`;
  `enrichment` added the test and cleaned its one sample. ⇒ Owed call: strip the key from 36 files, or
  declare it and say why.

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
