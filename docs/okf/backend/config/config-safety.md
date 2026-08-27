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
* **Numeric bounds** — `processing.threads`, `processing.duckdb_threads`, `processing.batch.max_files`, and
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

⚠ **Relative values resolve against the working directory, not against the root.** Every config this
product ships authors its refs from the server root (`schema_file: spaces/default/config/…`), so
resolving them against the root would double the prefix and break every space. ⛔ Do not "simplify"
this, and never jail a config path against its own `configDir`.

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
