---
type: Concept
title: Editions
description: Basic, Standard and Advanced (plus Preview) are build flavors of one codebase - Maven profiles, optional modules discovered by ServiceLoader, edition features and -D flags, never branches.
resource: docs/EDITIONS.md
tags: [editions, build-flavors, maven, serviceloader]
timestamp: 2026-11-12T00:00:00Z
---

# Editions

> **Status:** current as of 2026-11-12, checked against the code.

**Editions are BUILD FLAVORS, never git branches.** One source tree (`master`) is the auth-free common core;
an edition is *which Maven modules + flags* are assembled from a given commit. A fix lands once in core and
every edition inherits it at build time — no cross-line cherry-picking. Authoritative doc: `docs/EDITIONS.md`.
The module list, with the profile and the lowest edition of every module, is in
[architecture-module-taxonomy.md](architecture-modules.md) §1.

**Names and the Link Analysis split (`LA-EDITIONS-1`, 2026-10-17).** Basic / Standard / Advanced (formerly Personal /
Professional / Enterprise) plus Preview; profiles `edition-standard`, `edition-advanced`, `edition-preview` (Basic = no
profile). Basic carries the Link Analysis **explorer** — `inspecto-entity-list`, `inspecto-la-core`,
`inspecto-la-storage`, `inspecto-la-api`, `inspecto-geo-link` are default-reactor modules — while the Investigation
surface is withheld by the `investigations` edition feature (`EditionFeatures.INVESTIGATIONS`), declared only by
`inspecto-ops`: absent, the host answers every route module that names it in `featureIds()` 503
`CAPABILITY_UNAVAILABLE` (`EditionFeatures.gatesRoutes`, checked in `ControlApi.requireModuleEnabled`) and `/bootstrap`
reports `features.investigations: false`. Still module-shaped: Basic lacks the surface because it lacks the module.
Two more route features work the same way and are also declared by `inspecto-ops`: `geoMap` (the Geo map and routes)
and `entityPages` (the Entity profile pages). Detail: `docs/EDITIONS.md` (*Inspecto LA (Link Analysis) by edition*).

**The Investigations add-on.** `investigations` is the one edition feature that an install can also lose at
run time: it is present only while a module declares it **and** the install holds the Investigations add-on
(`EditionFeatures.holdsInvestigationsAddon`). With a signed entitlement installed, the entitlement decides;
without one, the setting `-Daddons.laInvestigations=false` removes the add-on (absent = held). Detail:
`docs/EDITIONS.md` (*The Investigations add-on*).

Assembly mechanisms:

* **Maven profiles** — `-Pedition-standard` / `-Pedition-advanced` / `-Pedition-preview` are **`<modules>` lists
  and nothing else**: no `<build>`, no `<properties>`, no `<dependencies>`. The edition profiles exist only in the
  root pom; the only other profiles in the tree are `coverage` (root pom, `la/inspecto-geo-link/pom.xml` and the
  nested `providers/asn-parser/asn-decoders/pom.xml`). `edition-standard` lists 17 modules; `edition-advanced`
  and `edition-preview` list the same 19 (the 17 plus `inspecto-policy` and `inspecto-la-store-pg`). ⚠ The
  profile lists are static XML: a module added to one profile must be added to the others that should have it.
  🔴 **Profiles do NOT vary the shaded content of the processor jar**: `inspecto/pom.xml` has ONE unconditional
  shade configuration (it EXCLUDES the 16 first-party base libraries, which ship as thin jars), and the optional
  modules ship as **separate jars**, never shaded in. 🔴 **There is no `edition-basic` profile**: **Basic is the
  default build**, no profile at all. ⚠ Maven only *warns* on a profile that does not exist and then builds the
  default, so `-Pedition-basic` produces a correct Basic build for an incorrect reason — the kind of instruction
  that survives because it appears to work.
* **An optional Maven module** — the primary mechanism: a feature an edition lacks is a module that edition
  does not bundle. The 19 profile-scoped modules are `inspecto-oidc` (OIDC/Nimbus, role mapping, token relay — see
  [auth & security](../../module/access/admin-guide-auth-security.md)), `inspecto-secrets`,
  `inspecto-geo-country`, `inspecto-connectors-kafka`, `inspecto-notify-channels`, `inspecto-backup`,
  `inspecto-exchange`, `inspecto-observability`, `inspecto-ops`, `inspecto-case-management`,
  `inspecto-action-requests`, `inspecto-regulatory-reporting`, `inspecto-reconciliation`, `inspecto-scoring`,
  `inspecto-screening`, `inspecto-anomaly`, `inspecto-predictive`, and for Advanced and Preview `inspecto-policy`
  (ABAC) and `inspecto-la-store-pg` (the PostgreSQL Investigation store). Basic never compiles them.
  ⚠ **Reactor membership is not bundle membership.** Some optional modules are in the default reactor, so their
  tests run in every build, but ship only from a higher edition: `inspecto-agent` and `inspecto-telecom-asn1`
  ship from Standard, `inspecto-intelligence` from Advanced; `inspecto-agent-hosted` and `inspecto-demo-auth` are
  in no edition's Offering. What an edition ships is decided by its Offering (`offerings/<edition>.toon`), which
  `tools/check-offerings.mjs` holds equal to the pom profile and the manifests.
* **`ServiceLoader`** — an absent module means the no-op impl is the only one discovered (same pattern as the
  optional [assist agent](../../module/agent/spec-assist-agent.md) and [connectors](../../module/connectors/architecture.md)).
* **Edition features** — for a feature whose code cannot leave a base module, a module declares the feature and
  every door asks `EditionFeatures.present(id)` (item 28 below).
* **`-D` flags** — `-Dauth.mode` is `none` by default (Basic); the `serve` launchers of every other edition add
  `-Dauth.mode=oidc`, and a demo build runs with `-Dauth.mode=demo`.
* **`package.ps1 -Edition …`** — builds one edition's bundle (`Basic` | `Standard` | `Advanced` | `Preview`;
  Advanced is a superset of Standard that also bundles the ABAC `policy` jar, the PostgreSQL Investigation store
  and `inspecto-intelligence`; Preview ships the Advanced module set; see [build & run](spec-build-test.md)).

One version spans all editions. The bundle's file name does not carry the edition; the edition is recorded
inside the bundle, in `lib/edition.properties`, which the launchers read. The matching branch policy is in
[branching & release](spec-branching-release.md).

## Extracting a feature into an edition module — the as-built recipe (EDG-01, 2026-09-07/08)

EDG-01 made the "not for Basic" cells of the feature × edition matrix true of the build, after they had been a
stated product decision the code did not apply. Seven cells were gated out of Basic this way (`9fdb99f8` ·
`c323f35c` · `91b6c9de`+`1de693a3` · `f39b531f` · `d409921a` · cell 6). One of them, the Link Analysis module
`inspecto-geo-link`, has since moved into Basic (`LA-EDITIONS-1`). The shape that worked, and the traps that
cost a rebuild each:

1. **Contribute through an SPI, never an `if (edition == …)`.** Routes go through the public
   `com.gamma.spi.http.RouteModule` (made public + `@PublicApi` in `91b6c9de`); maintenance tasks through
   `MaintenanceTaskProvider`; delivery through `NotificationChannel`. `ControlApi` registers its hard-coded
   list first, then appends every `RouteModule` that `OptionalSpi.all` finds.
2. **Absent ⇒ 503 with an explanation, never 404.** The stubs are no longer hand-written: `AbsentModuleRoutes`
   synthesises them from the absent module's manifest (`provides.routes` and `absentMessage`), which the
   processor build copies for every known module (see [architecture-module-taxonomy.md](architecture-modules.md) §3).
3. 🔴 **Register stubs through `ApiContext.stub`, not `api.get`/`api.post`.** A stub registered as a real
   route counts in `hasRoute`, so a derived feature flag reports the module present. `features.geoLink` shipped
   `true` on Basic for exactly this reason and was caught only by the falsification test.
4. 🔴 **Derive every `/bootstrap` feature flag from `ApiContext.hasRoute`**, never from the edition or a
   proxy. `features.exchange` read `containerRoot() != null`, so a Basic install with `-Dspaces.root`
   advertised a Share button that 404'd on click.
5. 🔴 **An import-based census cannot see fully-qualified use.** `ComponentRoutes` reached into
   `com.gamma.exchange` by FQN with no import; the delete fence broke the build after the move and needed a
   new `SharedItemConsumers` seam. Let the compiler find the couplings, not grep.
6. 🔴 **Find every test that USES the surface, not just the tests ABOUT it.** Gating `/metrics` reddened four
   core tests, three of which were testing space isolation, API versioning and sunset retirement and merely
   read the scrape as a convenient read-out. Re-seat such a test on the underlying seam
   (`MetricRegistry.global().scrape()` is the exact string the route serves) or move it into the module —
   deleting it loses a real assertion.
7. **Pair a falsification test with each cell**, running in the DEFAULT build, asserting the feature is
   genuinely absent (503, empty SPI, refused task) — not merely unregistered.
8. ⚠ **A capability-gated route needs more than its registration**, `CapabilityManifest` being the site most
   easily missed; route matching is FIRST-MATCH across modules, so a duplicate would silently never run (hence
   the duplicate-registration guard added in `91b6c9de`, which fails boot).
9. ⚠ **`package.ps1` emits four launchers** (`run.sh`/`run.bat`/`serve.sh`/`serve.bat`, plus `serve-demo.*` for `-Distribution demo`)
   and `.gitignore` is per-module for `target/`. ✅ Since P3d (2026-10-08) none of them keeps a jar list: each reads the
   bundle's `lib/modules.list` (one jar per line, classpath order) and `serve.*` read `lib/edition.properties`, both written at
   package time by `tools/offering-classpath.mjs` from the Offering (`offerings/<edition>.toon`; since `PRODUCT-ASSEMBLY-1`
   PA-1 `tools/bundle-modules.mjs` derives its table from the Offerings, so there is one module table). A new module is
   placed once in `CLASSPATH_ORDER` (`tools/bundle-modules.mjs`), not in four launchers.
10. ⛔ **Grantable capability vocabulary stays static across editions.** Deriving it from registered routes
    would make a role file authored on Standard fail validation on Basic. Dead vocabulary is not a hole;
    there is no route behind it.

11. 🔴 **Census the CONSUMERS of the surface, not just its providers — and count UI panes, not only Java
    classes.** Cell 6's plan said gating `/events*` costs "the Events screen". It actually reached **four**
    SPA panes (Events, **Audit log**, Dashboard, Incident/Case detail), because they all inject the same
    `EventsService`. One `grep -rln` on the service in the UI would have found them; the plan had only ever
    looked at the backend. Scope a cell from the consumer census, or you promise a product change you have
    not measured.
12. 🔴 **A gated surface can carry a capability another matrix row PROMISES.** `docs/EDITIONS.md` promises
    every edition an audit trail that is kept on disk and readable (*Security and deployment by edition*), and
    the Audit-log screen read it through the very
    `/events/search` cell 6 was gating — so the packaging change would have silently withdrawn a documented
    compliance capability. The fix is a **narrow core route** (`AuditLogRoutes`: `/audit/search`,
    `/audit/export`) that serves only the audit projection, **fail-closed to a closed type set** so it
    cannot become the feed under a new name. ⚠ Before gating anything, read the OTHER rows of the matrix
    for what they promise the edition you are gating.
13. ⚠ **Gate the landing route too, not just the nav entry.** Hiding a nav item protects a user who
    clicks; it does nothing for `/`. `LENS_HOME.ops` pointed at `events`, so an Ops-lens user on Basic
    would have been redirected straight onto a pane whose every call 503s — the one path a hidden entry
    cannot cover, because nothing was clicked. Any per-persona or default home target that names a gated
    screen needs a fallback.
14. ⚠ **A shared helper stops being the route's private business the moment the route leaves.** `Cursor`
    (the keyset codec `/events`, `/jobs/runs` and `/objects` all page with) and `TimeBounds` (the
    operations-zone bound parser) both had to go **public in core** rather than travel with the module — a
    second copy of either drifts silently, and drifted pagination or a bound read in the wrong zone is
    invisible until it is wrong in production.
15. ⚠ **Absent-module stubs are an ORDERED surface, not a set.** `/events/([^/]+)` is a catch-all that
    swallows `/events/search`, `/events/export` and `/events/views` if stubbed first, because matching is
    first-match. The stub order has to mirror the module's registration order (today that is the order of the
    manifest's `provides.routes`, which `ModuleRoutesParity` holds equal to the registration order), and the
    falsification test must probe the specific literal paths — every one of them 503s either way, so a
    status-code-only test cannot tell a correct stub from a catch-all that ate its siblings.
16. 🔴 **A default-false feature flag silently re-points existing specs at the gated path.** Adding
    `SessionService.eventsEnabled` (false by default, the honest Basic state) made three component
    specs exercise the absent-module branch, where the component makes no HTTP call at all — so their
    event assertions would have failed for a reason unrelated to what they test. Arm the flag in the
    spec setup; and remember the geoLink precedent that a `SessionService` stub must name every signal the
    code under test reads.

17. 🔴 **A moved test cannot see the core module's TEST tree — and the compiler only says so under the
    edition profile.** `V1Body` (the v1-envelope unwrapper every control-plane test uses) lives in
    `inspecto/src/test`, which is published to nothing, so a test moved into an optional module fails
    `testCompile` with a bare "cannot find symbol". Cells 4 and 6 both hit it; the established fix is a
    **small local `json()`/`envelope()` helper per module**, not a `test-jar` dependency. ⚠ Two ways this
    bites late: the DEFAULT build never compiles the module, so `mvn -o clean test` stays green while the
    Standard/Advanced build is broken — always run **both** profiles before calling a cell done; and if
    the reactor dies in an earlier module, the new module is merely SKIPPED, which reads like success in a
    summary that only counts failures. Check the module actually **contributed tests**, not just that the
    build passed.

## Extracting a feature that mandatory core CONSTRUCTS (EDG-01 cell 7, CP-11)

The first six cells were leaves — routes, tasks or an exposition. `CP-11` (operational objects, now
`inspecto-ops`) was not: a whole domain with many code dependants, several of which **built** the feature. What
that changes:

18. 🔴 **A provider SPI goes where its ARGUMENTS live, not where the seam lives.** `ObjectAccess` belongs
    in `inspecto-engine`, expressed in engine types. Its provider could not: opening the stores needs
    `SpaceRoot` and `OperationalDb` from the **host** module above the engine, and that dependency may not
    be inverted. So `ObjectEngineProvider` is declared in `com.gamma.service` — the `RouteModule`
    precedent, not the `MaintenanceTaskProvider` one. Decide this before writing the interface; discovering
    it afterwards means moving the file and every implementor.
19. ⚠ **A seam is rarely enough on its own — expect to return a HANDLE.** `ObjectAccess`'s domain methods
    covered none of: the boot-time backfill count core logs, the SLA sweep core *schedules*, the browsable
    stores the DB browser lists, or `close()` for four DuckDB connections. Those are lifecycle, not domain
    calls, so they sit on an `ObjectEngine` handle rather than widening the seam for every consumer.
20. 🔴 **Watch for an erasure collision when the domain object implements the seam.** `ObjectService.open`
    returns an `OperationalObject` and `link` an `ObjectLink`, both with the same parameter erasure as the
    SPI's `String open` / `void link` — Java rejects it. Renaming the SPI would push awkward names
    (`openObject`, `linkObjects`) into core to accommodate a module detail. Adapt on the **module's** side
    instead, and let the module's own routes and jobs cast that adapter back down to the concrete engine —
    resolved per-Space through `api.service()`, never a static, or one Space serves another's rows.
21. 🔴 **Check what the OTHER matrix rows promise, and expect to amend some.** `OPS-01` promised Basic
    an `objects` store and `SP-CTL-02` a gap watchdog that raises ALERT objects; both had to change with
    the cell (operator-approved) rather than be left contradicting the build. Cell 6 hit the same wall with
    the audit-trail promise and resolved it the other way, by keeping a narrow core read. **Read the neighbours before
    scoping the cell** — this is recipe item 12, and it has now decided the shape of two cells running.
22. ⚠ **A ServiceLoader-contributed Job Type is constructed with NO arguments** and `create` receives only
    a `JobConfig` — neither the engine nor the data root. Defer resolution to run time:
    `JobContext.services()` carries whatever core registered, and `spaceId()` keys a module-side registry.
    ⚠ And a named `switch` case always beats a contributed provider, so a task must be **removed** from the
    switch, not merely also provided.
23. 🔴 **An optional module may only depend on one that ships in every edition where IT ships.**
    `inspecto-ops` is Standard-and-above, `inspecto-policy` is Advanced-only, so the test edge is legal
    in exactly one direction. ⚠ `-Pedition-standard -DskipTests` **passed** with the wrong direction,
    because this machine's `~/.m2` held a stale jar — a green local build is not evidence here, reactor
    membership per profile is.
24. ⚠ **A three-way outcome must not be squeezed into two.** `DecisionRoutes` reported "Incident already
    open" when the module was merely ABSENT, so a Basic deployment claimed triage was underway for an
    Incident that cannot exist. Absent, present-and-open, present-and-new are three answers. Same class as
    cell 3b's `features.geoLink`, which advertised a button that 404'd.
25. ⚠ **Registration order in a constructor is behaviour.** A Platform Service registered EAGERLY beside a
    neighbour that reads the same field through a SUPPLIER produced 632 NPEs across 40 test classes: the
    field is assigned 40 lines later. When adding to a list of registrations, ask whether the line
    evaluates now or later — not whether it looks like its neighbours.
26. ⚠ **Tests that needed a real engine were only ever testing the Standard shape.** Re-seating
    `AlertServiceTest`/`ReconRunJobTest`/`DryRunServicesTest` onto a `FakeObjectAccess` (a test double in the
    engine's test tree, `com.gamma.objects`) is a gain, not
    a compromise: they now assert what core is responsible for (dedupe-then-open, right kind/scope/attrs),
    which is identical on every edition. Move the ones that genuinely assert PERSISTENCE or the object
    GRAPH into the module — a fake asserting its own bookkeeping is a test of the fake.
27. ⚠ **The `V1Body` trap scales.** At one or two moved HTTP tests a local `json()` helper is right (cells
    4, 6). At **seven** (cell 7) one module-local copy in the split package is better: the moved tests stay
    byte-identical and reviewable as *moves*. The drift risk is bounded because core's `ApiContractTest`
    pins the served envelope independently.

## Gating a feature whose code stays in core (`PROCESSOR-RELEASE-READINESS-1` G9, 2026-09-24)

28. **When the executing code cannot leave core, a module DECLARES the feature instead.** Alert Rules
    (`AlertService`), the Collector's `post_action: MOVE` archive and DuckLake registration (`DuckLakeRegistrar`)
    are core code with many dependants, so extraction was disproportionate. `com.gamma.etl.EditionFeatureProvider`
    is a ServiceLoader SPI that a module above Basic registers, and `EditionFeatures.present(id)` is the one
    question every door asks. The declarations today:

    | Module | Feature ids it declares |
    |---|---|
    | `inspecto-ops` (Standard and above) | `alert.dispatch` · `investigations` · `geoMap` · `entityPages` · `laWidgets` · `pipeline.automation` |
    | `inspecto-backup` (Standard and above) | `sink.archive` · `sink.ducklake` |
    | `inspecto-policy` (Advanced and Preview) | `quality.pii.mask` (the `mask` Step) |

    `investigations`, `geoMap` and `entityPages` are **route features**: the host answers their routes 503
    `CAPABILITY_UNAVAILABLE` when the feature is absent. `laWidgets` is a UI flag only (`/bootstrap`
    `features.laWidgets`), and `pipeline.automation` is checked by the Dataset feed and storage routes themselves.
    Still the house mechanism — the classpath entry is the switch, never an `if (edition == …)` — and a provider
    that fails to load reads as absent, so the gate refuses (safe direction). Two things can withhold a declared
    feature: a signed entitlement that leaves it out (`Entitlements.featureEntitled`), and, for `investigations`
    only, the Investigations add-on (above).
    Refusals carry `ERR_EDITION_FEATURE` (a finding code; the v1 `errorCode` stays `CONFIG_VALIDATION_FAILED`,
    because the `ErrorCode` enum is a contract catalog). Gate every door, not one: the save gate, the feature's own
    routes, and the run-time arrivals (registry load, boot load, the lanes the registry does not guard). Full
    list: [step catalog → Edition notes](../../module/etl/spec-step-catalog.md).
29. ⚠ **Core, engine, etl and connectors test classpaths are Basic.** A test of the Standard behaviour of
    such a feature pins it with `EditionFeatures.overrideForTest(...)` and restores `null` in teardown; the
    Standard acceptance proper lives in the declaring module, where the REAL provider is discovered.
