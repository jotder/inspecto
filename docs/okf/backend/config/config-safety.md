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

⚠ **`PathJail` unified the CONFIG-path implementations, not every containment check in the codebase.**
A 2026-08-14 sweep found ~15 more outside that scope — the registry stores (three byte-identical
copies), archive extraction, static file serving, remote listings — disagreeing on symlinks,
absolutisation and failure mode. They are **four different problems**, and ⛔ putting them all on
`PathJail` is the wrong fix: remote object keys are not local `Path`s, and id-shaped names are not
containment at all. The family split and the ranked fix order are `BACKLOG.md` §6 **PATH-2**.

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
times". Drive `run(ctx)` with `CapturingJobContext` (`inspecto-engine` test sources) instead.

**Why these live here and not in a `ConfigSpec`.** `FieldSpec`/`ConfigSpec` are flat-dotted-path only —
`FieldType.MAP`/`LIST` assert the container type and never walk into entries, and there is no
map-of-objects/list-of-objects primitive. Every repeated sub-shape in the codebase (`sinks[]`, and now
`references.<name>`) is therefore validated by a hand-written per-entry method here; `checkSink` is the
precedent `checkReference` follows. A future map-of-objects notion in the spec layer would subsume both.

Only `pipeline` and `enrichment` config types have a write surface to gate. This is tied to the write-gate:
when `-Dassist.write.root` is set, writes are jailed to that root and validated here (see
[auth & security](../editions/auth-security.md)).
