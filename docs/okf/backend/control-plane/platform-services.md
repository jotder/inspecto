---
type: Concept
title: Platform Services — the plugin envelope
description: The named seam through which a Job (and later a Step) reaches engine facilities — a flat typed lookup filtered by a declared `requires:` list, validated at registration, substituted under a dry run — plus the pack scaffolder and test harness that make it authorable.
resource: inspecto-engine/src/main/java/com/gamma/job/PlatformServices.java
tags: [control-plane, jobs, plugins, packs, platform-services, grants, scaffolding]
timestamp: 2026-08-10T00:00:00Z
---

# Platform Services — the plugin envelope

A **Platform Service** is an engine facility a plugin may be *granted*. Before this seam existed, the
engine's facilities were wired point-to-point into built-ins by constructor injection inside
`JobService.registerBuiltins()`, and a pack provider — instantiated no-arg by `ServiceLoader` — could
receive none of it. A hot-deployed pack could log, emit a Signal and record artifact metadata, and
nothing else; both sample Job Types added 2026-08-07 needed engine edits and could not have shipped
as packs. Stage 1 of the platform-services work (S1-0…S1-8, 2026-08-09/10) inverted that.

⛔ **Not "capability"** — that word is RBAC's (`CapabilityManifest` maps route gates to
`Roles.KNOWN_CAPABILITIES`). ⛔ **Not "Controller Service"** — NiFi's term implies user-instantiated
resources with an enable/disable lifecycle, which is deliberately not the v1 concept.

## 1. The seam

`PlatformServices` (`com.gamma.job`, `@PublicApi`) is a **flat typed lookup**: `find(Class)`,
`get(Class)`, `granted()`. Nothing more — no scopes, no proxies, no lifecycle, no annotation-driven
injection. It is built once at boot and *filtered per consumer*, and it must stay that way: the
moment it grows a scope it becomes a dependency-injection framework this codebase deliberately does
not have.

- **`PlatformServiceRegistry`** is the boot-built binding table (`id → interface → impl`). It fails
  closed on a duplicate id **and** on a duplicate interface, and `grant(ids)` throws naming any id
  not bound in this build.
- **`JobContext.services()`** carries the grant. It defaults to the empty grant, so a consumer that
  declared nothing sees nothing.
- The registry is a **`JobService` constructor parameter, not a setter.** `registerBuiltins()` and
  the pack scan both run inside that constructor and must validate `requires:` there — a
  post-construction setter arrives too late and was removed for exactly that reason.

## 2. Grants are declared, and honest

A Job Type declares `requires: [notifications, incidents, …]` on its `JobTypeDescriptor`. Two rules
carry the whole trust story:

1. **Validated at registration, never at fire time.** An unknown or build-absent id refuses the type
   — pack-atomically — naming the id and what is available. A typo can never surface as an empty
   lookup half-way through a Run.
2. **An undeclared service is invisible even though the engine has it.** That is what makes the
   declaration worth showing an operator: `requires:` describes a pack's reach *only because*
   undeclared lookups fail. The test that pins undeclared-but-present services as refused is
   load-bearing for the security story; grants are trust, and the pack signature
   (`-Djobs.packs.requireSignature`) is integrity — different guarantees, do not conflate them.

`requires:` travels on `GET /jobs/types[/{id}]` and renders as the job-form dialog's "Uses services"
chip row, so the reach is visible *before* anything is armed.

⚠ **One deliberate leniency.** A `JobTypeRegistry` with **no** registry wired (a lean/embedded
`JobService`, e.g. an engine unit test) still accepts a **built-in's** declaration: the service ships
in the same build, so the id is not unknown — only the host wiring is absent, and a built-in tolerates
the empty lookup. Third-party providers stay strict. Without this, the first built-in to declare a
grant would have broken every host-less construction. With a registry present, built-ins are
validated too, so a typo still fails boot.

## 3. The v1 menu

Interfaces live beside their engine facility and carry `@PublicApi`.

| Id | Interface | Wraps | Dry run |
|---|---|---|---|
| `notifications` | `NotificationAccess.notify(Notification)` | `NotificationStore` + live listeners; `NotificationService`'s own event dispatch goes through it | logs the would-be notification, stores nothing |
| `incidents` | `IncidentAccess.openIncident(…, dedupeAttribute)` | `ObjectService`; honours the active-object convention via a caller-named dedupe attribute. `AlertService.promoteToIncident` opens through it | reports the would-be Incident, opens nothing |
| `schema` | `SchemaAccess.list/get/fingerprint` | `registry/schemas/*.toon` via a per-call `ComponentRegistry.scan`; the fingerprint is the same `CanonicalHash.sha256` pinned into manifests | n/a — read-only |
| `consignment-status` | `ConsignmentStatusAccess.consignment/latestFor/outputs/fileStages` | the loaded pipelines' manifests, plus the two default-off registries | n/a — read-only |
| `alerts` | `AlertAccess.evaluateRules()` → `List<Alert>` | `AlertService`'s evaluator; `alert.evaluate` is the only consumer (added 2026-08-10 on D7's demand — see §6) | ⚠ **cannot be previewed**: logs the would-be evaluation and returns empty, and a consumer must report that nothing was *checked* |

**The engine is the seam's first consumer** — the CONTROL trio's dispatch was rewired through
`NotificationAccess`/`IncidentAccess` before any plugin could bind to them, which is how the
interfaces were shown sufficient rather than assumed so.

### The dry-run contract is per service and mandatory

`DryRunServices` wraps a Run's grant when the fire is a dry run: mutating services record instead of
act (the stand-in logs to the RunLog and performs nothing), read-only services pass through
unchanged, and visibility is never widened. **Every new mutating service must be substituted there**
— otherwise `dryRun()` on the Job becomes a lie the moment the Job calls it.

### Absence is modeled, not crashed on

A facility can be absent by build flavor or flag. `find()` is then empty (the same answer as "not
granted"), and a `requires:` on it refuses registration naming the edition/flag. Optional use =
`find()`; mandatory use = `requires:` + `get()`. Never a `NullPointerException` at fire time.

## 4. Authoring a pack

`tools/scaffold.mjs` generates a standalone, offline-buildable pack project under `packs-dev/<id>/`
(gitignored — a pack is versioned in its own repository, never inside the engine's):

```bash
node tools/scaffold.mjs new job       --id acme.reconcile --name "Acme Reconcile"
node tools/scaffold.mjs new processor --id acme.masker    --name "Acme Masker"
node tools/scaffold.mjs new step      --id acme.score     --name "Acme Score"
```

- **No archetype**, because archetypes resolve from a repository and this build is air-gapped: plain
  file templates under `tools/templates/` plus `{{token}}` stamping, zero dependencies, beside its
  neighbours `check-vocabulary.mjs` / `check-secrets.mjs`.
- **The engine coordinates are read out of `inspecto-engine/pom.xml` at generation time**, never
  hardcoded, so an artifactId or version change cannot leave the script emitting a dependency that
  does not resolve.
- **`new service` and `new step`** both emit real packs: `new step` opened at S2-3 (§7c), `new service` at
  S3-1 (§7d). A kind the engine cannot host yet is gated by name rather than emitted as a skeleton.

**`PackTestHarness`** (`com.gamma.job`, main scope) is the real deliverable: it fires one Run without
booting the engine, applying the *same* registration-time `requires:` check, Parameter resolution,
grant filtering and dry-run substitution as a real Run, and exposes the RunLog, the recorded service
calls, the emitted Signals and the resolved parameters for assertions. Its recording stand-ins honour
the contracts they stand in for — the feed's dedupe-collapse and the Incident active-object
convention — so a Job that depends on either behaves in the harness as it will in production.

⚠ It lives in **main scope**, not a test-jar: `inspecto-engine` publishes no test-jar, and a pack
already depends on the engine to compile against `JobTypeProvider`. It sits inside `com.gamma.job`
so it can reuse the real `JobTypeRegistry`, `ParameterResolver` and `DryRunServices` — reimplementing
their semantics is exactly how a harness stops being a proxy for a real Run.

`ScaffoldTemplatesTest` guards the templates against the rot they are uniquely prone to (they are
Java no compiler ever sees): it stamps them as the script does, compiles every generated source —
the generated *test* included — packages the main classes into a real pack jar, and loads it through
`JobPackManager` into a scratch registry. Offline, no Node, no Maven.

## 5. What is deliberately not in v1

Services are **open by default**: withholding a facility from the grantable menu needs a recorded
necessity (integrity or security), never a default posture. Absent from v1 means *"no consumer yet"*,
not *"forbidden"*. The two restrictions that do stand, each with its necessity: third-party `LOWERED`
SQL (injection into the fused query) and the stage-2 Step data-path ceiling (a mid-flight node must
not write Datasets or send outbound mail).

- **`DatasetAccess`** — deferred by design to follow the Consignment Selector, so it arrives with
  pruning and generation-pinning built in. It becomes the seam's flagship service.
- **Outbound mail/webhook send** — belongs to the `mail.send` reference Job Type and to notification
  *dispatch* config, not to a grant.
- **Alert Rule CRUD** — the `alerts` service carries the **evaluator only** (`evaluateRules()`), which
  is what its one consumer demanded. Rule authoring stays a control-plane concern; a plugin that wants
  to *raise* something directly wants `IncidentAccess` or a Signal, not this. That is the menu-by-demand
  rule working as intended, not a withheld capability.

## 6. Grounding that refuted the plan (do not re-derive)

1. **The CONTROL trio has no per-node dispatch.** `alert`/`event`/`gap` node kinds are declarations;
   their semantics run as `EventLog`/bus subscribers wired in `CollectorService`. Those subscribers
   *are* the "engine dispatch" that was rewired through the services.
2. **`consignment-status` needs no `StatusStore`** — the manifest already carries per-member status —
   and **`JobRunLedger` is unrelated** (it is a package-private Job-run audit).
3. **`alert.evaluate` could not migrate to `incidents`** (D7). It depends on the *evaluator*, not the
   Incident opener — the Incidents it causes are opened inside `AlertService` through its own
   `IncidentAccess`. Declaring `requires: [incidents]` would have been **decorative**: remove it and
   Incidents still open, which contradicts the rule that a declaration is what enables the reach.
   ✔ **Resolved 2026-08-10 the honest way** — `alerts` joined the menu and `alert.evaluate` now
   declares it, so its `Supplier<AlertService>` injection is gone (with `JobService`'s `alerts` field
   and setter, and both `CollectorService` wiring sites) and **no built-in Job reaches the engine by
   constructor injection for its own work any more**. The migration also fixed a live MNT-1 violation
   it exposed: `alert.evaluate` previously ignored `dryRun()`, so a preview fire really evaluated and
   really opened Incidents. It now does nothing and says so, because evaluation *is* the action.

## 7. S2-0 — pack isolation for pipeline node types (as built, 2026-09-24)

S2-0 of the Stage 2 design ([`platform-services-stage2-design.md`](../../../superpower/platform-services-stage2-design.md),
whose §7 calls the operator decided 2026-09-28) is two defects that design pass found. Both reproduced red first;
the tests are in `JobPackManagerTest` and `PipelineNodeTypesPackOverlayTest`. The lease test blocks the walk
inside its sink write (after the pack executor ran), unloads the pack, and asserts the close is deferred AND,
since 2026-09-28, that the resumed walk still delivers the pack executor's 3 rows to the sink.

1. **A pack executor may run only its own pack's node type.** (Since S2-3 the rule lives in
   `StepExecutors.register`, the only pack-facing execution seam; §7c.) `PipelineNodeTypes.register` already
   refused a pack node type that reuses a built-in name, but `PipelineNodeExecutors.register` did not.
   `RowShaper.shape` checks for a contributed executor *before* the built-in chain, so a jar in the
   packs dir could change how `transform.filter` ran for every pipeline. The registry now refuses a
   pack executor whose kind is a `BuiltinNodeType`, and one whose kind is not a node type registered
   by the **same** pack. `JobPackManager` registers node types before executors, so that check sees
   them. The refusal throws inside the pack's atomic load, so the whole pack is rejected (its legal
   node type too), logged `[PACKS] rejected` and signalled `job.pack.rejected`. A **classpath**
   provider (an edition, shipped and reviewed with the build) may still specialise a built-in verb.
   ⚠ For a pack, the same-pack clause alone already covers built-ins, because a pack can never own a
   built-in node type. The explicit built-in clause is there for its clearer message, and only a
   message assertion catches its removal.
2. **A pipeline run pins the packs it uses.** Before this fix, only `JobService`'s Job path held the
   in-flight-Run lease (`acquireRun`/`releaseRun`). A pipeline whose Step was a pack's node type did
   not, so an unload could close the pack's classloader mid-run. `PipelineExecutor.execute` and
   `dryRun` (both terminal overloads) now hold a `PackRunLeases` lease for the whole walk, validation
   included, and release it in `finally`. The lease covers every pack that owns a node type in the
   graph. The counting still lives in `JobPackManager`: it installs itself as the `Leaser` when packs
   are enabled and uninstalls on close. The seam exists only because `com.gamma.pipeline.exec` cannot
   see `com.gamma.job`. Every installed manager is pinned, one per Space's `JobService`. Node-type
   owners are enough, because rule 1 guarantees that no pack executor runs a kind its pack does not own.
   ⚠ `ComponentPreview` (single-node preview) calls `RowShaper.shape` directly and holds no lease.

## 7a. S2-1 — execution mode on the node type (as built, 2026-09-28)

`PipelineNodeType.mode()` returns `Optional<ExecutionMode>` (`LOWERED` | `EXECUTED`); the default is
empty, meaning **undeclared**.

1. Every `BuiltinNodeType` answers `LOWERED`: each one compiles to SQL inside `RowShaper`.
2. `PipelineNodeTypes.register` (the pack overlay) refuses a type declaring `LOWERED`, so no third-party
   SQL reaches the engine's query (R2). The throw happens inside the atomic pack load, so the whole pack is
   rejected and signalled `job.pack.rejected`, executor included. A classpath provider may declare either mode.
3. An undeclared type still **loads**, so it renders and its wiring validates. `PipelineValidator` then
   reports ERROR `NODE_MODE_UNDECLARED` for any node using it. Arming is `validateOrThrow` in
   `PipelineExecutor.execute` / `dryRun`, so such a graph cannot run. A save through the graph routes
   also sees the ERROR.
4. `mode` is **not served** in the palette catalog (`PipelineProjection.catalog`), so the node-attributes
   contract is unchanged.
5. The `nodetype` scaffold template declares `EXECUTED`.

Tests: `PipelineNodeTypesPackOverlayTest` (every built-in `LOWERED`; pack `LOWERED` refused, `EXECUTED` and
undeclared accepted), `PipelineValidatorModeTest` (undeclared is refused at arming; `EXECUTED` and
built-ins arm), `JobPackManagerTest.aPackNodeTypeDeclaringLoweredIsRejectedWhole` (real jar).

## 7b. S2-2 — the bridge spike, measured (2026-09-28)

This is a measurement only, from `BridgeSpikeBenchmark` (test scope, skipped unless `-Dbench.run=true`).
The method, host and deviations are in design §5.2. The fixture is 2M generated mixed-type rows. Each
variant ends in the same Parquet `COPY`. Figures are the median of 5 warm runs; *÷ fused* is the total
over the fused total.

| Between map and the write | 10 cols rows/s | ÷ fused | 50 cols rows/s | ÷ fused |
|---|---:|---:|---:|---:|
| V0 nothing (flat lane, fused) | 4,806,419 | 1.00× | 814,714 | 1.00× |
| V1 one built-in CTAS (graph-lane node) | 3,468,811 | 1.39× | 618,290 | 1.32× |
| V2 no-op executed Step (JDBC read + appender) | 150,267 | 31.99× | 20,829 | 39.11× |

Per-node materialisation is cheap. The JVM row round trip is the cost: ~0.6–0.9 µs per **cell**, so it
scales with columns × rows. V3 (Arrow) was not measured, because no current Arrow Java is available
offline. Under D-4 the Arrow bridge is therefore out of S2-3's scope. S2-3's `StepContext` should
stream typed values straight to the engine-owned appender, not a boxed row. Anything SQL can express
stays a built-in `LOWERED` verb.

## 7c. S2-3 — the pack Step seam (as built, 2026-09-28)

A pack runs a node type through **`StepExecutor`** (`com.gamma.pipeline.exec`): `type()`, `requires()`,
`timeout()`, `execute(StepContext)`. `StepTypeProvider` is not a class; under D-1 it is only the name of
the pack-facing pair, `PipelineNodeType` + `StepExecutor`.

1. **D-2: a pack may not carry a raw-`Connection` executor.** `JobPackManager` rejects a pack whose
   `META-INF/services` lists a `PipelineNodeExecutor`, naming `StepExecutor` in the cause. The pack
   overlay on `PipelineNodeExecutors` is gone, so that registry is classpath-only. The S2-0 rules (not a
   built-in, only the same pack's node type, first pack wins) moved to `StepExecutors.register`.
2. **`StepContext` has no connection.** `in()` is a forward cursor with typed getters (`getLong`,
   `getString`, `getDate`, …, JDBC-style `wasNull()`). The engine issues its one statement,
   `SELECT * FROM <input>`; a Step writes no SQL at all, so there is nothing for `SqlGuard` to check. This is
   stricter than the design's "through `SqlGuard`". `emit(rel)` and `emit(rel, columns)` return a typed
   appender writer. `copyRow(in)` passes the current row through. The engine refuses a relation the node type
   does not `emits()`. Explicit columns take only `BOOLEAN`/`INTEGER`/`BIGINT`/`DOUBLE`/`VARCHAR`/`DATE`/
   `TIMESTAMP` (a closed set, so no author text reaches DDL). `copyRow` also carries `DECIMAL`. An input
   of any other type must be cast upstream. The context also has `attributes()`, `schema()`, `log()`,
   `signals()`, `dryRun()` and `services()`. Every data call throws once the Step has returned, failed or
   timed out.
3. **`requires:` resolves at pack load**, fail-closed, against the loading Space's
   `PlatformServiceRegistry` (the same strict rule as a pack Job Type). An unknown id, or any id with no
   registry wired, rejects the pack. The stage-2 ceiling is `StepExecutors.CEILING = {mail}`: a Step
   requiring `mail` rejects the pack even when the host binds it. No Dataset-writing service exists yet.
   The grant is bound to the registration, so a pack Step runs with the services of the Space that loaded
   it. A classpath `StepExecutor` has no loading Space and is granted nothing. A dry run wraps the grant in
   `DryRunServices`, as a Job's is. `signals()` only logs in a dry run.
4. **Dry run.** `RowShaper.ExecutionContext` gained `dryRun`. `PipelineExecutor.dryRun` and
   `ComponentPreview` pass `ExecutionContext.NONE.asDryRun()`. Every armed path passes a context with
   `dryRun = false`, including `NONE` itself, which the ingest lane uses.
5. **Failure grain.** Any throw, a timeout or a failed flush drops **every table the Step created** and
   throws `StepFailure` (a `RuntimeException`) with `code()` `STEP_FAILED` / `STEP_TIMEOUT` /
   `STEP_DISABLED`. The batch fails through the existing path. A declared `data` or `reject:*` relation the
   Step never wrote is created **empty** with the input's columns, so a zero count reaches provenance
   instead of a missing branch.
6. **Reject streams.** `PipelineRel.REJECT_PREFIX` = `reject:`. `ConservationCheck.relCounts` tags a
   `reject:<reason>` relation as diverted, as it does the four built-in reject reasons.
7. **Watchdog (D-7).** `execute` runs on its own virtual thread. The deadline is `timeout()` (default
   5 min), overridden by the node's `timeout_seconds` attribute (decimals allowed), capped by
   `-Dpipeline.step.timeoutCeilingSeconds` (default 1800). On expiry the context closes, the input
   statement is `cancel()`ed and the thread is interrupted. A thread still alive 1 s later is
   **abandoned**: it keeps its pack pinned, because the thread holds its own `PackRunLeases` lease for its
   whole life; the kind is disabled until its pack is deregistered (replaced or removed); and a CRITICAL
   `pipeline.step.abandoned` Signal is emitted. A pure-Java loop that ignores interrupts cannot be killed
   on this JVM. ⚠ An abandoned thread may still hold appenders on tables the teardown drops; that is the
   honest limit.
8. 🔴 **A DuckDB appender is thread-confined.** Closed from another thread it refuses to flush, and the
   rows are silently lost. The Step's own thread therefore closes its appenders before it signals done.
9. **Cost.** `BridgeSpikeBenchmark` gained variant **S**: the V2 no-op as a real `StepExecutor` through
   `StepRunner`. It asserts that S's node time is within 1.5× of V2's. On 2026-09-28, at 2M rows × 10
   columns and 3 runs, S took 5,808 ms and V2 took 6,254 ms: the seam adds no overhead to the bridge.
10. **Scaffold.** `node tools/scaffold.mjs new step` generates a pack (template `tools/templates/step/`):
    an `EXECUTED` node type emitting `data` + `reject:missing`, a `StepExecutor`, both service files, and a
    test that runs it through `RowShaper.shape`. `ScaffoldTemplatesTest.theStepTemplateCompilesLoadsAndRuns`
    compiles it, loads it through `JobPackManager` and runs it.

Tests: `StepRunnerTest` (armed vs dry run, throw ⇒ no tables, undeclared emit, `reject:` tagging, empty
declared relation, deadline kill, abandon ⇒ disable ⇒ re-enable, deadline resolution, closed context),
`JobPackManagerTest` (D-2, built-in/orphan/own Step, `LOWERED`, armed and dry run with a real grant,
undeclared service invisible, ceiling and unknown id, the lease), `PipelineNodeTypesPackOverlayTest`,
`ScaffoldTemplatesTest`. Each was mutation-checked red.

## 8. What is still open

Stage 2 is built through S2-3. S2-4 is closed as a no-op (D-11, operator 2026-10-04): a config-derived
chain executes on the at-rest path (`output_store:`), so `graphLaneCarries` is not widened and ELT Phase 6
Row 15 is discharged there. S2-5 shipped 2026-10-04: a pack kind is spelled by the generic `- step: {kind: <suffix>, …}` recipe verb (D-5;
`RecipeCompiler` compiles it to `transform.<kind>` and `RecipeConverter` projects it back; a built-in, unloaded or
kind-less step is refused) and stays out of `ProcessorCatalog` (D-6). ⚠ It also fixed two latent save-path defects:
`PipelineEditable.lower` gated on the closed `LOWERABLE` set, and `isLegacyShaped` NPE'd on a contributed kind, so a
contributed step could not be saved at all before. S3-1, S3-2 and S3-3 (pack-contributed services and `DatasetAccess`, §7d/§7e) shipped 2026-10-04. The open items
live in [`BACKLOG.md`](../../../BACKLOG.md) §4 under *Platform Services*. A Job-side watchdog is still a
recorded gap; the Step watchdog is §7c.

### 7d. Pack-contributed services (S3-1, 2026-10-04)

- **`ServiceProvider`** is the sixth kind a pack carries: `id()`, `type()`, `create()`, `readOnly()`,
  `dryRun(RunLog)`. It binds under the pack's owner key and is taken back on unload or rejection.
- **D-12: the interface is engine-published.** Consumers match on `Class` identity and pack loaders cannot share
  a type, so `type()` must be an interface the engine classpath exposes. A pack supplies an implementation of an
  interface that exists but is not bound in this build. A pack-defined interface (a shared pack-API loader) is a later design.
- **Fail-closed, whole pack:** a colliding id or interface, a pack-defined interface, a factory result that is not
  an instance, or a mutating service with no dry-run stand-in rejects the pack and leaves nothing bound.
- **Dry run:** `DryRunServices` substitutes the contributed stand-in; a `readOnly()` service passes through.
- **D-9 retry pass:** `rescan()` retries a pack refused for an unavailable service while a pass still loads something.
- **S3-2 pinning (2026-10-04):** a Run granted a pack's service pins the *provider* pack with the same
  `acquireRun`/`releaseRun` counter. `JobService` pins `PlatformServiceRegistry.ownersOf(requires)` for the Run
  (the owner set is taken once, before the body, so an unload mid-Run cannot lose the release); a pipeline walk
  pins the providers of every pack Step's granted ids (`StepExecutors.Grant.serviceIds()` +
  `PackRunLeases.Leaser.serviceOwners`). Unload still removes the binding at once (no NEW grant), and the
  provider's classloader close is deferred until the granted Runs drain.
- **S3-2 enable/disable (operator 2026-10-04):** per service id, engine-internal only
  (`PlatformServiceRegistry.disable/enable`, no route). A disabled id's `grant` throws naming it; `has()` stays true;
  running grants finish; not persisted (a restart re-enables). ⚠ A loaded pack Step's grant is a load-time snapshot,
  so a disable reaches later Job Runs, not an already-loaded Step.

### 7e. `datasets` — `DatasetAccess` (S3-3, 2026-10-04)

- **D-10 (operator): inherit `ConsignmentSelector` as-is.** `com.gamma.query.DatasetAccess`, service id `datasets`,
  `read(spaceId, datasetId)` -> `Read(datasetId, relationSql, Optional<files>)`. The relation and file list come from
  `DatasetRelation`, so unreadable files are pruned and the list is pinned per call. No held snapshot handle yet:
  each read pins afresh. A view-backed Dataset reports no files ('cannot know').
- **Read-only, per-Space, fail closed:** bound to one Space; another Space or an unknown Dataset throws naming the
  ids, never an empty result. A Job's authority is its `requires: [datasets]` grant (no Subject at fire time).
- Engine-published (D-12), `readOnly()` when pack-contributed (no stand-in); disable refuses the grant.
  Design as-built: `superpower/platform-services-stage2-design.md` §5.6.

Related: [Job vs Pipeline Step](job-vs-step.md) · [Jobs & Scheduling](jobs.md) ·
[Signal backbone](signal-backbone.md) · [API stability policy](api-stability.md) ·
[`PROJECT_NOTES.md`](../../../PROJECT_NOTES.md) §5
