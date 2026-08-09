# Platform Services & the Plugin Envelope — plan

**Status: ARCHIVED 2026-08-10 — STAGE 1 COMPLETE (S1-0…S1-8).** Shipped: the seam, `requires:`
grants validated at registration, the whole v1 service menu (`notifications`, `incidents`, `schema`,
`consignment-status`) with the engine as first consumer, `sample.hello` migrated onto a grant with
its injection dropped (a real pack jar proves a pack can do the same), and the pack scaffolder
(`tools/scaffold.mjs` + templates) with `PackTestHarness`. **As-built lives in
[`okf/backend/control-plane/platform-services.md`](../okf/backend/control-plane/platform-services.md);
Stage 2 and Stage 3 and the other open items moved to [`BACKLOG.md`](../BACKLOG.md) §4.** This file is
retained as the record of the D0–D8 decisions and the S1/S2/S3 phasing — it is history, not current
guidance.

Decisions taken during delivery: **D6** (operator) services are open by default — a plugin is only
restricted when absolutely necessary, with the necessity recorded; **D7** `alert.evaluate` does not
migrate (it needs the evaluator, not `incidents`); **D8** a bare registry still accepts a built-in's
declaration. ⚠ **D7 was resolved after this plan closed** (2026-08-10, `ee9f34cc`): the operator
added an `alerts` service to the menu, `alert.evaluate` declares it, and its injection is gone — so
§9's "stays injection-wired" is the historical record, not current truth. The archive is not
maintained; `okf/backend/control-plane/platform-services.md` is. Grounded against source 2026-08-09; every "already exists" claim carries a `file:line`
ref. Items marked ⏲ cited grounded sibling docs rather than fresh reads.

> **Operator scope (2026-08-09):** built-in services that Steps and Jobs can *use* (Notification,
> Alert, Schema Registry, file/Consignment Status, …), openness to **new Step kinds** that do not
> lower to SQL and to **new contributed services**, and **scaffolding** to create a new Job, a new
> Step, a new Service, and consume services — *"that's all we need as of now."* This plan does NOT
> redesign the Job/Step boundary (owned by `job-vs-step.md` + the addressing plan §4), does not
> build the Dataset API (born from the Consignment Selector, addressing plan §6), and does not
> touch RBAC.

---

## 0. Design principles

1. **Capability by registration, never by switch edit** — the same §0 principle the
   job-parameter-contract plan shipped for Expressions; services, Step kinds and (later) contributed
   services are all registries with fail-closed collision handling.
2. **Services, not libraries.** A pack compiles against small `@PublicApi` interfaces; the
   implementation stays engine-owned. No version-skew surface, grants stay operator-visible, and
   every service defines its dry-run behaviour. Handing packs client libraries would give them
   ungoverned reach and couple their builds to engine internals.
3. **One envelope, three mounts.** Job (pack), ConsignmentProcessor, and (stage 2) executable Step
   share one classloader/signature/quiesce lifecycle, one declaration contract, one grant model —
   the code-and-capability reuse this thread exists for.
4. **Open, but fail-closed total.** Anything registered must be executable; anything required must
   be grantable — both checked at registration/arming time, never discovered at run time.

## 0-A. Decisions taken with this plan (operator, 2026-08-09)

- **D0 — the seam is named "Platform Service."** ⛔ Not *capability* — that word is RBAC's:
  `CapabilityManifest.java:8-14` maps route gates to `Roles.KNOWN_CAPABILITIES`, a different concept.
  ⛔ Not *Controller Service* — NiFi's term implies user-instantiated resources with enable/disable
  lifecycle, which is deliberately stage 3, not the v1 concept. GLOSSARY entry lands with S1-0.
- **D0-B — the Step-kind registry opens.** Supersedes the GLOSSARY note "⚠ Closed on purpose —
  contrast Job types" (`GLOSSARY.md` ~§5). The closure's real guarantee — *a pipeline that parses is
  a pipeline that runs* (compiler totality) — is preserved differently: every registered Step kind
  must carry an execution mode (`LOWERED` or `EXECUTED`, §5.2), and a node whose type has neither is
  refused at arming, the same fail-closed posture the compile-only verbs (`summarize`, `join`,
  `route`) already ship with. The enumeration goes; the guarantee stays.

---

## 1. Problem — verified 2026-08-09

| Evidence | Ref |
|---|---|
| `JobContext` carries **no services**: `runId, spaceId, trigger, config, params, log(), signals(), artifacts(), dryRun()` — that is the whole surface | `JobContext.java:13-53` |
| Its own javadoc names a "`JobServices` data-plane façade… arrives with `sql.template` (P3)" — **never built**; repo-wide the only occurrence is that javadoc line | `JobContext.java:10-11` |
| Built-ins reach engine facilities by **constructor injection inside `registerBuiltins()`** (`dataDir`, `ObjectService` supplier, `AlertService` supplier, notification store, `this`); a pack provider is instantiated no-arg by `ServiceLoader` and can receive none of it | `JobService.java:229-327`; `job-vs-step.md` §6 |
| Consequence, already recorded: both sample Job Types added 2026-08-07 (`sample.hello`, `alert.evaluate`) **required engine edits and could not have shipped as packs** | `job-vs-step.md` §6 |
| `NotificationStore.add(...)` exists with a dedupe-collapse contract; it is fed only by the event dispatcher, and the only Job touching the store **prunes** it | `NotificationStore.java:24-58` |
| `AlertService`'s public surface is rule CRUD + evaluation (`rules/has/upsert/remove/recent/onEvent/evaluateAll`) — **no "raise an alert" entry a plugin could call** | `AlertService.java:98-148` |
| `PipelineNodeType` is ServiceLoader-shaped but **descriptor-only — no execution hook**; no third party can ship a Step today | `job-vs-step.md` §5.2 |
| No scaffolding exists: `tools/` holds checkers only (`check-vocabulary.mjs`, `check-secrets.mjs`, `seed-uat.ps1`); no archetype, no example plugin project anywhere in-repo | `tools/` |

Root cause in one line: **the engine's facilities are wired point-to-point to built-ins; there is no
named seam a plugin (or a Step) can be granted access through** — even though the need was named in
the P3 design (`JobServices`) and the narrowing discipline was already invented for
`ProcessorContext`.

## 2. What already exists — the assets this plan assembles

- **The dry-run contract**: `JobContext.dryRun()` (MNT-1 "Safe by Default") — preview fires mutate
  nothing; a thing that cannot preview must do nothing and say so (`JobContext.java:43-52`,
  `ProcessorContext.java:62-66`). Every Platform Service inherits this posture (§3.4).
- **The narrowing discipline, with rationale**: `ProcessorContext` (`ProcessorContext.java:9-35`)
  documents *why* wide surfaces are rejected (a `job()` accessor "leaks the entire Job surface into
  a contract every third-party processor binds to, after which nothing in `JobContext` could ever
  change") and re-exposes only small, stable pieces. §3's grant matrix follows this exactly.
- **The pack-facing API marker**: `@PublicApi(since = "5.0.0")` — `inspecto-api`'s single class
  (`inspecto-api/src/main/java/com/gamma/api/PublicApi.java`), already stamped on
  `ProcessorContext`. Platform Service interfaces carry it too; that *is* the interface-home
  convention (D5 keeps the thin-jar extraction as future work).
- **The plugin lifecycle**: isolated `URLClassLoader`, atomic load-or-reject
  (`JobPackManager.java:189-201` ⏲), in-flight-Run quiesce before unload, and
  `-Djobs.packs.requireSignature` class-entry verification (`job-vs-step.md` §3).
- **The registry+catalog+fail-closed pattern, shipped twice**: `JobTypeRegistry` and (2026-08-06+)
  `ExpressionRegistry`/`BuiltinExpressions` with `GET /jobs/expressions`. `PlatformServices` and the
  Step-kind registry are the third and fourth instances of the same shape.
- **The Step-side precedent**: the CONTROL trio — `ALERT`, `GAP`, `EVENT` node kinds
  (`BuiltinNodeType.java:107-116`) are *declared nodes whose semantics the engine executes* ("Raises
  an alert…", "Emits a notification / event"). "A Step uses a service" already exists in exactly the
  form §5.1 generalises: declaratively, engine-invoked. ✔ Grounded at S1-3: there is **no per-node
  dispatch** — the trio's semantics run event-driven, as `EventLog`/bus subscribers wired in
  `CollectorService` (`NotificationService.dispatch`, `AlertService.persistAlertObject`/
  `promoteToIncident`, `EventObjectBridge`); those subscribers are the "engine dispatch" S1-3/S1-4
  rewired through the services.
- **The facilities to wrap** (v1 menu): `NotificationStore` (`notify` pkg), `ObjectService`
  (`com.gamma.ops` — the Incident/Object authority), schema components + fingerprints
  (`CanonicalHash`, `schema_fingerprint` pinned in manifest + `consignment_outputs` — addressing
  plan §2.1 ⏲), Consignment/file status read surfaces (`ManifestStore`, `StatusStore` +
  `FileStatusStore`/`DbStatusStore`, `DbConsignmentOutputStore`, `JobRunLedger` — addressing plan
  §2 / ELT Phase-4 grounding ⏲). `SignalEmitter` and `ArtifactRecorder` are already service-shaped
  on `JobContext` and stay where they are.
- **The boot wiring point**: `JobService` is constructed in `CollectorService.java:385` (secondary
  site `:1155`) — the one place the registry gets built and threaded in.

## 3. The seam — `PlatformServices`

### 3.1 The interface (v1 — deliberately flat)

```java
@PublicApi(since = "5.1.0")
public interface PlatformServices {
    /** The granted service, or empty — absent when not granted OR not available in this build. */
    <T> Optional<T> find(Class<T> type);
    /** The granted service; throws IllegalStateException naming the missing grant. */
    default <T> T get(Class<T> type) { ... }
    /** Every interface granted to this consumer — for logs, diagnostics, the UI panel. */
    Set<Class<?>> granted();
}
```

No scopes, no lifecycle, no proxies, no annotations-driven injection — a flat typed lookup built
once at boot (`CollectorService.java:385`) and *filtered per grant* when handed to a consumer. This
is explicitly **not** a DI framework (R3).

### 3.2 Declared grants — `requires:`

`JobTypeDescriptor` gains `requires` (list of service ids, e.g. `notifications`, `incidents`,
`schema`, `consignment-status`). Registration **fails closed** when a required service id is unknown
or unavailable in this build — pack-atomic, per the posture set for Expression providers. The
resolved grant set:

- populates `JobContext.services()` (only what was declared — undeclared lookups return empty even
  if the service exists, so grants are honest);
- is served by `GET /jobs/types/{id}` beside the provenance fields (step-9 work, shipped) and
  rendered in the type's "what this does" panel — the operator sees a pack's reach *before* arming
  anything.

### 3.3 Grant matrix by mount

| Mount | v1 | Later |
|---|---|---|
| **Job** (`JobContext.services()`) | full declared menu | — |
| **CONTROL-trio node execution** (engine-side, on the Step's declared behalf) | `NotificationAccess`, `IncidentAccess` (S1-3/S1-4) | more service-invoking node kinds as demanded |
| **ConsignmentProcessor** | none new — keeps its §14.3 façade (`outputs/read/summaries/log/signals`) | a *filtered* read-only subset (`SchemaAccess`, `ConsignmentStatusAccess`) via D4, only when a processor use case demands it |
| **Executable Step** (stage 2) | n/a | `StepContext.services()`, read-only + emit-only ceiling: a Step mid-flight may read schema/status and raise notifications/incidents; it may **not** write datasets or send outbound mail from the data path |

### 3.4 The dry-run contract — per service, mandatory

Every Platform Service interface documents its behaviour under a dry run, and the framework hands
services a run-scoped flag: mutating operations **record instead of act** and say so in the RunLog
(`NotificationAccess` logs the would-be notification; `IncidentAccess` reports the would-be
Incident). Read-only services are unaffected. This extends MNT-1 to everything a plugin can reach —
without it, `dryRun()` on the Job becomes a lie the moment the Job calls a service.

### 3.5 Absent services (editions / flags)

Facilities can be absent by build flavor or flag (e.g. the notification backend). Absence is modeled,
not crashed on: `find()` returns empty; a **`requires:`** on an absent service fails registration
with a message naming the edition/flag (D3). Optional use = `find()`, mandatory use = `requires:` +
`get()`. Never a `NullPointerException` at fire time.

## 4. Built-in services — v1 menu

Each: what it wraps, the surface, dry-run behaviour. Interfaces live beside their engine facility,
stamped `@PublicApi`.

| Service id | Interface (sketch) | Wraps | Dry-run |
|---|---|---|---|
| `notifications` | `NotificationAccess.notify(severity, title, body, dedupeKey)` — honours the collapse contract via `hasActiveDuplicate` | `NotificationStore.add(...)` (`NotificationStore.java:26-40`) | log-only, reports suppressed-by-dedupe |
| `incidents` | `IncidentAccess.open(kind, dedupeKey, attributes)` (+ `comment(id, text)`) | `ObjectService` (`com.gamma.ops`) — the ALERT/INCIDENT `OperationalObject` creation path; respects the active-object convention (no second open ALERT per scope — `AlertService.java:254-277` precedent ⏲) | reports the would-be Incident, creates nothing |
| `schema` | `SchemaAccess.list()` / `get(name)` / `fingerprint(name)` — **read-only** | schema components + `CanonicalHash` fingerprints (addressing plan §2.1 ⏲) | n/a (read-only) |
| `consignment-status` | `ConsignmentStatusAccess.consignment(id)` → status, files, outputs; `latestFor(pipeline)`; `outputs(id)`; `fileStages(sourceId, relPath)` — **read-only** | ✔ S1-6 as built: `ManifestStore` (authoritative — the manifest already carries per-member status, so **no `StatusStore` read is needed**) + `DbConsignmentOutputStore` via `ConsignmentOutputStores` + `FileStages`, both default-off ⇒ empty. **`JobRunLedger` refuted**: package-private Job-run audit, nothing to do with Consignments | n/a (read-only) |

Explicitly **not** in v1:

- **`DatasetAccess`** — deferred to follow the Consignment Selector (addressing plan §6: "should
  follow the Selector, not precede it"); when it lands it arrives with pruning and
  generation-pinning built in, and becomes the seam's flagship service.
- **Outbound mail/webhook send** — belongs to the `mail.send` reference Job Type
  (job-parameter-contract §9) and notification *dispatch* config, not to a v1 grant.
- **`AlertService` itself** — not withheld as policy (see **D6**): it is simply not in the v1 menu
  because its public surface is rule CRUD + evaluation (`AlertService.java:98-148`) — there is no
  plugin-shaped raise entry, and no consumer has demanded the evaluator. `incidents` + `signals`
  cover the known cases today; an `alerts` service joins the menu on the first real demand.

## 5. Steps and services

### 5.1 v1 — declarative, and the seam's first consumer is the engine itself

No new SPI is needed for a Step to *use* a service: the CONTROL trio already declares exactly that
(`BuiltinNodeType.java:107-116` — `alert` "raises an alert", `event` "emits a notification / event").
S1-3/S1-4 rewire the trio's engine-side execution **through `NotificationAccess`/`IncidentAccess`**,
making the engine the seam's first consumer and proving the interfaces are sufficient before any
plugin binds to them. New service-invoking node kinds (e.g. a richer `control.notify` with templated
body) are added by demand after that — as registrations, not enum edits, once S2-1 lands.

### 5.2 Stage 2 — the open Step-kind registry (`EXECUTED` mount)

- **`StepTypeProvider`** registry mirroring `JobTypeRegistry`: id, descriptor (label, category,
  accepts/emits, declared attributes — the widened contract of `job-vs-step.md` §6), `requires:`,
  and an execution mode:
  - **`LOWERED`** — contributes SQL lowering, joins the fused query (zero bridge cost). Built-ins
    register through this path first, making the registry real without new capability. ⛔
    Third-party `LOWERED` stays closed until a SQL-fragment guard exists (R2).
  - **`EXECUTED`** — imperative Java. The engine materialises the node's input relation, invokes
    `StepExecutor.execute(StepContext)`, re-registers the output relation for downstream nodes.
- **`StepContext`** (sketch): `in()` (read-only relation), `emit(rel, relation)` (declared `emits`
  only), `attributes()`, `schema()`, `services()` (§3.3 ceiling), `log()`, `dryRun()`. Same
  delegation discipline as `ProcessorContext` — narrow, stable, documented refusals.
- **Failure grain**: an `EXECUTED` Step failure maps onto the *existing* semantics — reject rows to
  a declared reject stream where the contract allows, else batch `FAILED` — never a half-applied
  node. ⚠ Neither side has timeout/cancellation (`job-vs-step.md` §2); a hung `EXECUTED` Step
  stalls its pipeline's poll cycle. Recorded as R1; a watchdog is stage-2 scope, not optional.
- **Gates, in order**: (1) the branch-aware executor becomes the armed production path — as of the
  2026-08-06 grounding, armed linear runs compile back to the legacy engine and only dry-run rides
  the graph executor, so there is nothing production-grade to mount `EXECUTED` on yet; (2) the S2-2
  **bridge spike**: measure rows/s through a no-op `EXECUTED` Step vs the same pipeline fused —
  publish the number here before GA (the addressing plan's "measure rung A first" discipline).

### 5.3 Vocabulary

"**Step kind**" (the registered type) vs "**Step**" (a node instance in a Pipeline) — mirrors the
Type/Instance rule. The GLOSSARY "closed on purpose" note is rewritten by D0-B (S1-0).

## 6. Stage 3 — contributed services

A pack may *provide* a service, not only consume one: `ServiceProvider` SPI (id, interface,
implementation factory), loaded per the pack lifecycle. Two hard rules, both already precedented:

- **Collision fails the pack atomically** (id or interface already bound) — the
  `JobPackManager.java:189-201` posture, same as Expression providers.
- **Reference-tracked quiesce**: a service's owning pack cannot unload while any Run holds a grant
  on it — extends the existing in-flight-Run quiesce from "runs of this pack's Job Types" to "runs
  granted this pack's services". This is NiFi's enable/disable + referencing-components discipline,
  adopted *only here*, where it pays for itself.

User-instantiated configured resources (a pooled JDBC shared by three Jobs) stay out of scope until
a consumer demands them; the Connection component remains their config substrate.

## 7. Scaffolding — `tools/scaffold.mjs`

The piece that makes openness usable. House-consistent (Node script beside `check-vocabulary.mjs`),
offline-friendly (no archetype resolution; plain file templates + `${token}` stamping).

```
node tools/scaffold.mjs new job     --id acme.reconcile --name "Acme Reconcile"
node tools/scaffold.mjs new service --id geoip          --name "GeoIP Lookup"      # stage 3
node tools/scaffold.mjs new step    --id acme.score     --name "Acme Scorer"       # stage 2
node tools/scaffold.mjs new processor --id acme.masker  --name "Acme Masker"
```

Each generates a **standalone pack project** under `packs-dev/<id>/` (gitignored):

- `pom.xml` — parentless, offline-buildable (`mvn -o package`), depending on the installed
  `inspecto-engine` version property; produces the deployable pack jar.
- `src/main/java/...` — the skeleton for the kind: a `JobTypeProvider` + `Job` with a declared
  parameter, a `requires:` example, and a commented `services().get(NotificationAccess.class)`
  usage; analogous skeletons per kind.
- `src/main/resources/META-INF/services/...` — the ServiceLoader registration, correct per kind.
- `src/test/java/...` — one green test on the **`PackTestHarness`**.
- `README.md` — build, test, deploy (copy to the packs watch dir), and signing
  (`-Djobs.packs.requireSignature`) in five commands.

**`PackTestHarness`** (new, engine test-jar): boots `JobTypeRegistry` + `ExpressionRegistry` + an
in-memory `PlatformServices` (fake notification store, recording incident access), registers the
pack's providers, fires one manual Run, and exposes the RunLog + recorded service calls for
assertions. A pack author tests against the real contract **without booting the engine** — this
harness, not the templates, is the scaffolder's real deliverable.

Guards: generated code passes `node tools/check-vocabulary.mjs`; `new step` **refuses with a
pointer** until S2-3 unlocks it (honest, not aspirational); `new service` likewise until S3-1.

## 8. Delivery

**Stage 1 — the seam + built-in services (backend-first, each step independently green)**

| # | Step | Verify |
|---|---|---|
| S1-0 | Docs/vocabulary: GLOSSARY **Platform Service** entry; D0-B rewrite of the "closed on purpose" Step note; `job-vs-step.md` §0/§6 updated (fulfilled-by pointers); INDEX line | vocabulary guard green (see BUILD note below); cross-refs resolve |
| S1-1 | `PlatformServices` + boot registry in `CollectorService.java:385`; `JobContext.services()` (empty grant default) | engine tests: grant filtering, absent-service `find()` empty, `get()` names the missing grant |
| S1-2 | `requires:` on `JobTypeDescriptor` + fail-closed registration + `GET /jobs/types/{id}` exposure + UI panel row | a type requiring an unknown id is refused at registration with a naming message; real-HTTP test |
| S1-3 | `NotificationAccess` over `NotificationStore` (+ dedupe collapse); rewire the `event` node's engine dispatch through it | unit + the node path emits via the service; maintenance prune untouched; dry-run logs-not-sends |
| S1-4 | `IncidentAccess` over `ObjectService` (active-object convention); rewire the `alert` node dispatch | an open Incident per scope is not duplicated; dry-run reports would-open |
| S1-5 | `SchemaAccess` (read-only) | list/get/fingerprint against seeded schema components |
| S1-6 | `ConsignmentStatusAccess` (read-only) | seeded manifest + outputs answer status/files/outputs |
| S1-7 | ✔ `sample.hello` migrated → `requires: [notifications]`, consumed via `ctx.services()`, **its `ObjectService` injection dropped** (it is now pack-shippable); real pack jar in a test declares + consumes a grant. ⛔ `alert.evaluate` → `incidents` **REFUTED** (see D7) | the pack loads, `requires:` grants resolve, the Run succeeds — the "could not have shipped as a pack" evidence inverted. Plus: a pack declaring an unavailable id is rejected whole, and a bare (host-less) registry still registers a built-in's declaration |
| S1-8 | ✔ `tools/scaffold.mjs` (`new job`, `new processor`; `new step`/`new service` refuse with a pointer) + `tools/templates/` + `PackTestHarness` in engine **main** scope — ⚠ not a test-jar, because `inspecto-engine` publishes none and a pack already depends on the engine to compile. Tokens are `{{name}}`, not `${name}`, so a generated pom's own `${project.version}` survives stamping | ✔ scaffolded → `mvn -o package` offline in `packs-dev/` → 3 harness tests green, jar produced (both kinds, no warnings). `ScaffoldTemplatesTest` additionally stamps + compiles every template source (generated test included), jars it and loads it through `JobPackManager` into a scratch registry — offline, no Node, no Maven |

**Stage 2 — the open Step-kind registry** (gated: branch-aware executor armed path)

| # | Step | Verify |
|---|---|---|
| S2-1 | `StepTypeProvider` registry; built-ins register as `LOWERED`; arming refuses a mode-less type | every existing pipeline arms identically; a fake typeless node is refused |
| S2-2 | **Bridge spike**: no-op `EXECUTED` Step behind a flag; measure vs fused | a rows/s number published in §5.2 — GA decision evidence |
| S2-3 | `EXECUTED` GA: `StepContext` + services ceiling + failure mapping + watchdog; scaffold `new step` unlocked | an `EXECUTED` pack Step runs armed in a branch-executor pipeline; hang is killed by watchdog; quarantine/reject semantics hold |

**Stage 3 — contributed services**

| # | Step | Verify |
|---|---|---|
| S3-1 | `ServiceProvider` SPI via packs; collision fails the pack atomically; scaffold `new service` unlocked | a pack contributes a service a Job from another pack consumes; a colliding pack is refused whole |
| S3-2 | Reference-tracked quiesce + enable/disable | unload while a granted Run is in flight blocks until drain; disable refuses new grants, running grants finish |

Ordering: S1-1 → S1-2 precede S1-3..7; S1-8 needs S1-1..3 only. Stage 2 and 3 are independent of
each other. ⚠ **BUILD note:** the offline reactor and the vocabulary guard both have known defects
(BUILD-1, VOCAB-1 in `docs/BACKLOG.md` §6) — per-module `mvn -o -pl inspecto-engine clean test` is
the trustworthy loop until BUILD-1 is fixed; do not trust a green guard without checking it actually
scanned.

## 9. Decisions & risks

- **D1 (settled)** — naming per §0-A.
- **D2 — grant granularity**: per Job Type (`requires:` on the descriptor), aggregated per pack in
  the UI. Not per-Job-instance in v1 (an instance cannot widen its type's grants; narrowing adds
  config surface with no driving case).
- **D3 — absent services**: typed absence (§3.5); `requires:` on an absent facility fails
  registration naming the edition/flag. Needs a per-edition availability pass at S1-1 ⏲.
- **D4 — `ProcessorContext` exposure**: keep its §14.3 façade; add a *filtered* read-only
  `services()` only when a processor use case demands it — never the full menu (its own javadoc's
  argument, `ProcessorContext.java:14-19`).
- **D5 — interface home**: engine packages + `@PublicApi` marker (existing convention). Extracting
  a thin compile-against devkit jar is future work; revisit when the first *external* pack author
  appears.
- **D6 — services are open by default (operator, 2026-08-09)**: *"let's not restrict any plugin
  from any service unless absolutely necessary."* Withholding a facility from the grantable menu
  requires a **recorded necessity** (integrity/security), never a default posture — the `requires:`
  declaration + operator-visible grants (R4) are the governance, not a curated allow-list. The
  restrictions that stay, each with its necessity: third-party `LOWERED` SQL (R2 — injection into
  the fused query) and the stage-2 Step data-path ceiling (§3.3 — a mid-flight node must not write
  datasets or send outbound mail). Everything else is menu-by-demand: absent from v1 means
  "no consumer yet", not "forbidden".
- **D7 — `alert.evaluate` does NOT migrate to `incidents` (grounded at S1-7, 2026-08-09)**: the plan's
  S1-7 line assumed it could. It cannot honestly: `AlertEvaluateJob` depends on the **evaluator**
  (`AlertService.evaluateAll()`), which §4 deliberately keeps out of the v1 menu. The Incidents it
  causes are opened *inside* `AlertService` through its own `IncidentAccess` (S1-4) — so declaring
  `requires: [incidents]` on the Job would be a **decorative grant it never looks up**: remove the
  grant and Incidents still open. That contradicts R4 (a declaration means the lookup is what
  enables the reach). `alert.evaluate` therefore stays injection-wired and becomes §4's *first real
  demand* for an `alerts` service — the honest migration is "add `alerts` to the menu", a separate
  slice, not a relabel. Its `requires:` stays empty until then.
- **D8 — a bare registry still registers a built-in's `requires:` (S1-7)**: `JobTypeRegistry` with no
  `PlatformServiceRegistry` wired (a lean/embedded `JobService`, e.g. an engine unit test) refuses any
  *third-party* declaration but accepts a **built-in's** — the service ships in the same build, so the
  id is not unknown; only host wiring is absent, and the built-in tolerates the empty lookup. Without
  this, the first built-in to declare a grant would have failed every host-less construction. Packs
  and classpath providers stay strict, and with a registry present built-ins are validated too (so a
  typo still fails boot — pinned by the real-HTTP `sample.hello` grant test).
- **R1 — no timeout/cancellation anywhere** (`job-vs-step.md` §2). Hardest for `EXECUTED` Steps
  (stalls a poll cycle); the S2-3 watchdog is mandatory scope, and a Job-side watchdog is a
  recorded gap beyond this plan.
- **R2 — third-party `LOWERED` is an injection surface** — a pack contributing SQL text into the
  fused query. Stays built-in-only until a fragment guard exists; do not ship it casually.
- **R3 — do not grow a DI framework.** Flat lookup, boot-built, grant-filtered. No scopes, no
  proxies, no lifecycle annotations. If a need appears, it goes through this plan's successor, not
  through "one small helper".
- **R4 — grants are trust, signature is integrity**: `requires:` tells the operator what a pack
  *can* do only because undeclared lookups fail. The S1-2 test that proves undeclared-but-present
  services are refused is load-bearing for the whole security story.

## 10. Glossary & docs touchpoints

Lands with S1-0: GLOSSARY **Platform Service** (+ the D0 ⛔s), **Step kind** vs **Step**, the D0-B
rewrite of the closed-registry note; `job-vs-step.md` §0 ("a Step is not a program" → "a Step is not
an *Executable*; it may be a program via the Step SPI (stage 2)") and §6 (gaps gain fulfilled-by
pointers); `docs/INDEX.md`. The addressing plan's §6 "Capability seam / Controller Services" row
gets this plan as its owner link (done with this commit).

---

Related: [`job-vs-step.md`](../okf/backend/control-plane/job-vs-step.md) ·
[`consignment-addressing-plan.md`](consignment-addressing-plan.md) ·
[`job-parameter-contract-plan.md`](job-parameter-contract-plan.md) ·
[`jobs.md`](../okf/backend/control-plane/jobs.md) ·
[`signal-backbone.md`](../okf/backend/control-plane/signal-backbone.md)
