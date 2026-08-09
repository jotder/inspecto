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
```

- **No archetype**, because archetypes resolve from a repository and this build is air-gapped: plain
  file templates under `tools/templates/` plus `{{token}}` stamping, zero dependencies, beside its
  neighbours `check-vocabulary.mjs` / `check-secrets.mjs`.
- **The engine coordinates are read out of `inspecto-engine/pom.xml` at generation time**, never
  hardcoded, so an artifactId or version change cannot leave the script emitting a dependency that
  does not resolve.
- **`new step` and `new service` refuse** with a pointer to the slice that unlocks them, rather than
  emitting a skeleton for a mount the engine cannot host.

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
- **`AlertService`** — not withheld as policy: its public surface is rule CRUD plus evaluation, with
  no plugin-shaped "raise" entry, and no consumer has demanded the evaluator. An `alerts` service
  joins the menu on the first real demand — see §6.

## 6. Grounding that refuted the plan (do not re-derive)

1. **The CONTROL trio has no per-node dispatch.** `alert`/`event`/`gap` node kinds are declarations;
   their semantics run as `EventLog`/bus subscribers wired in `CollectorService`. Those subscribers
   *are* the "engine dispatch" that was rewired through the services.
2. **`consignment-status` needs no `StatusStore`** — the manifest already carries per-member status —
   and **`JobRunLedger` is unrelated** (it is a package-private Job-run audit).
3. **`alert.evaluate` cannot migrate to `incidents`.** It depends on the *evaluator*
   (`AlertService.evaluateAll()`), which is deliberately outside the v1 menu; the Incidents it causes
   are opened inside `AlertService` through its own `IncidentAccess`. Declaring the grant would be
   **decorative** — remove it and Incidents still open — which contradicts the rule that a
   declaration is what enables the reach. It stays injection-wired and is the first real demand for
   an `alerts` service; the honest migration is "add `alerts` to the menu", a separate slice, not a
   relabel.

## 7. What is still open

Stage 2 (the open Step-kind registry, `LOWERED`/`EXECUTED`) and Stage 3 (pack-contributed services)
are **not built**; Stage 2 stays gated on the branch-aware executor becoming the armed path. The open
items live in [`BACKLOG.md`](../../../BACKLOG.md) §4 under *Platform Services*. Neither side has
timeout or cancellation, which is hardest for a future `EXECUTED` Step (it would stall a poll cycle):
a watchdog is mandatory scope for that slice, and a Job-side watchdog is a recorded gap.

Related: [Job vs Pipeline Step](job-vs-step.md) · [Jobs & Scheduling](jobs.md) ·
[Signal backbone](signal-backbone.md) · [API stability policy](api-stability.md) ·
[`PROJECT_NOTES.md`](../../../PROJECT_NOTES.md) §5
