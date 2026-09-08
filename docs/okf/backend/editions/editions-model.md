---
type: Concept
title: Editions Model
description: Personal/Standard/Enterprise are build flavors — Maven profiles + ServiceLoader modules + -D flags, never branches.
resource: docs/EDITIONS.md
tags: [editions, build-flavors, maven, serviceloader]
timestamp: 2026-07-07T00:00:00Z
---

# Editions Model

**Editions are BUILD FLAVORS, never git branches.** One source tree (`master`) is the auth-free common core;
an edition is *which Maven modules + flags* are assembled from a given commit. A fix lands once in core and
every edition inherits it at build time — no cross-line cherry-picking. Authoritative doc: `docs/EDITIONS.md`.

Assembly mechanisms:

* **Maven profiles** — `-Pedition-standard` / `-Pedition-enterprise` are **`<modules>` lists and nothing
  else**: no `<build>`, no `<properties>`, no `<dependencies>`, and no child POM declares a profile at
  all. 🔴 **They do NOT vary the fat-JAR's shaded content** (corrected 2026-09-09 — this said
  “which modules + shade includes enter the fat-JAR”): `inspecto/pom.xml` has ONE unconditional shade
  configuration, and the optional modules ship as **separate sidecar jars**, never shaded in. 🔴 **There is no `edition-personal` profile** (corrected 2026-09-07; the
  parent POM declares exactly two — the ids are at `pom.xml:74` and `pom.xml:113`; the `:68,77` this cited until 2026-09-09 are comment lines, and they drifted when the EDG-01 modules were inserted. **Cite the id, not the line**): **Personal is the default build**, no profile at all.
  ⚠ Maven only *warns* on a profile that does not exist and then builds the default, so the old
  `-Pedition-personal` produced a correct Personal jar for an incorrect reason — the kind of instruction
  that survives because it appears to work.
* **An optional Maven module** — the primary mechanism, and as of 2026-09-08 there are **nine**:
  `inspecto-security` (OIDC/Nimbus, role mapping, token relay — see [auth & security](auth-security.md)),
  `inspecto-policy` (Enterprise ABAC), and the **seven** EDG-01 modules (this said “five” before 2026-09-09 while listing seven) `inspecto-notify-channels`,
  `inspecto-backup`, `inspecto-geo-link`, `inspecto-exchange`, `inspecto-metrics`, `inspecto-events`, `inspecto-ops`.
  Each joins the reactor only under `edition-standard`/`edition-enterprise`, so Personal never even
  compiles it.
* **`ServiceLoader`** — an absent module means the no-op impl is the only one discovered (same pattern as the
  optional [assist agent](../agent/assist-agent.md) and [connectors](../modules/connectors.md)).
* **`-D` flags** — e.g. `-Dauth.mode=none` (Personal) vs `-Dauth.mode=oidc` (Standard).
* **`package.ps1 -Edition …`** — emits per-edition bundles from one build (`Personal` | `Standard` |
  `Enterprise`, the last a superset of Standard bundling the ABAC `policy` jar as well; see
  [build & run](../build-run/build-test.md)).

One version spans all editions; artifacts differ by classifier. The matching branch policy is in
[branching & release](branching-release.md).

## Extracting a feature into an edition module — the as-built recipe (EDG-01, 2026-09-07/08)

**EDG-01 is COMPLETE as of 2026-09-08**: all six "not for Personal" cells of the feature × edition matrix
are now true of the build, after having been a stated product decision the code did not apply.

Seven cells were gated out of Personal this way (`9fdb99f8` · `c323f35c` · `91b6c9de`+`1de693a3` ·
`f39b531f` · `d409921a` · cell 6). The shape that worked, and the traps that cost a rebuild each:

1. **Contribute through an SPI, never an `if (edition == …)`.** Routes go through the public
   `com.gamma.control.RouteModule` (made public + `@PublicApi` in `91b6c9de`); maintenance tasks through
   `MaintenanceTaskProvider`; delivery through `NotificationChannel`. `ControlApi` registers its hard-coded
   list first, then appends everything `ServiceLoader` finds.
2. **Absent ⇒ 503 with an explanation, never 404** (`EDITIONS.md` §Assembly model — ⚠ this cited
   “§4” until 2026-09-09; `EDITIONS.md` has **no numbered sections**). The core keeps an `Absent…Routes`
   class that stubs the module's exact surface.
3. 🔴 **Register stubs through `ApiContext.stub`, not `api.get`/`api.post`.** A stub registered as a real
   route counts in `hasRoute`, so a derived feature flag reports the module present. `features.geoLink` shipped
   `true` on Personal for exactly this reason and was caught only by the falsification test.
4. 🔴 **Derive every `/bootstrap` feature flag from `ApiContext.hasRoute`**, never from the edition or a
   proxy. `features.exchange` read `containerRoot() != null`, so a Personal install with `-Dspaces.root`
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
8. ⚠ **Five sites make a capability-gated route work**, `CapabilityManifest` being the one most easily
   missed; route matching is FIRST-MATCH across modules, so a duplicate silently never runs (hence the
   duplicate-registration guard added in `91b6c9de`).
9. ⚠ **`package.ps1` emits four launchers** (`run.sh`/`run.bat`/`serve.sh`/`serve.bat`) — each classpath
   anchor appears twice — and `.gitignore` is per-module for `target/`.
10. ⛔ **Grantable capability vocabulary stays static across editions.** Deriving it from registered routes
    would make a role file authored on Standard fail validation on Personal. Dead vocabulary is not a hole;
    there is no route behind it.

11. 🔴 **Census the CONSUMERS of the surface, not just its providers — and count UI panes, not only Java
    classes.** Cell 6's plan said gating `/events*` costs "the Events screen". It actually reached **four**
    SPA panes (Events, **Audit log**, Dashboard, Incident/Case detail), because they all inject the same
    `EventsService`. One `grep -rln` on the service in the UI would have found them; the plan had only ever
    looked at the backend. Scope a cell from the consumer census, or you promise a product change you have
    not measured.
12. 🔴 **A gated surface can carry a capability another matrix row PROMISES.** `EDITIONS` §Audit gives
    Personal "local append-only logs", and the Audit-log screen read them through the very
    `/events/search` cell 6 was gating — so the packaging change would have silently withdrawn a documented
    compliance capability. The fix is a **narrow core route** (`AuditLogRoutes`: `/audit/search`,
    `/audit/export`) that serves only the audit projection, **fail-closed to a closed type set** so it
    cannot become the feed under a new name. ⚠ Before gating anything, read the OTHER rows of the matrix
    for what they promise the edition you are gating.
13. ⚠ **Gate the landing route too, not just the nav entry.** Hiding a nav item protects a user who
    clicks; it does nothing for `/`. `LENS_HOME.ops` pointed at `events`, so an Ops-lens user on Personal
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
    first-match. The stub array's order has to mirror the module's registration order, and the
    falsification test must probe the specific literal paths — every one of them 503s either way, so a
    status-code-only test cannot tell a correct stub from a catch-all that ate its siblings.
16. 🔴 **A default-false feature flag silently re-points existing specs at the gated path.** Adding
    `SessionService.eventsEnabled` (false by default, the honest Personal state) made three component
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
    Standard/Enterprise build is broken — always run **both** profiles before calling a cell done; and if
    the reactor dies in an earlier module, the new module is merely SKIPPED, which reads like success in a
    summary that only counts failures. Check the module actually **contributed tests**, not just that the
    build passed.

## Extracting a feature that mandatory core CONSTRUCTS (EDG-01 cell 7, CP-11)

The first six cells were leaves — routes, tasks or an exposition. `CP-11` was not: 31 domain files with 27
code dependants, several of which **built** the feature. What that changes:

18. 🔴 **A provider SPI goes where its ARGUMENTS live, not where the seam lives.** `ObjectAccess` belongs
    in `inspecto-engine`, expressed in engine types. Its provider could not: opening the stores needs
    `SpaceRoot` and `OperationalDb` from the **host** module above the engine, and that dependency may not
    be inverted. So `ObjectEngineProvider` is declared in `com.gamma.service` — the `RouteModule`
    precedent, not the `MaintenanceTaskProvider` one. Decide this before writing the interface; discovering
    it afterwards means moving the file and every implementor.
19. ⚠ **A seam is rarely enough on its own — expect to return a HANDLE.** `ObjectAccess`'s nine methods
    covered none of: the boot-time backfill count core logs, the SLA sweep core *schedules*, the browsable
    stores the DB browser lists, or `close()` for four DuckDB connections. Those are lifecycle, not domain
    calls, so they sit on an `ObjectEngine` handle rather than widening the seam for every consumer.
20. 🔴 **Watch for an erasure collision when the domain object implements the seam.** `ObjectService.open`
    returns an `OperationalObject` and `link` an `ObjectLink`, both with the same parameter erasure as the
    SPI's `String open` / `void link` — Java rejects it. Renaming the SPI would push awkward names
    (`openObject`, `linkObjects`) into core to accommodate a module detail. Adapt on the **module's** side
    instead, and let the module's own routes and jobs cast that adapter back down to the concrete engine —
    resolved per-Space through `api.service()`, never a static, or one Space serves another's rows.
21. 🔴 **Check what the OTHER matrix rows promise, and expect to amend some.** `OPS-01` promised Personal
    an `objects` store and `SP-CTL-02` a gap watchdog that raises ALERT objects; both had to change with
    the cell (operator-approved) rather than be left contradicting the build. Cell 6 hit the same wall with
    §Audit and resolved it the other way, by keeping a narrow core read. **Read the neighbours before
    scoping the cell** — this is recipe item 12, and it has now decided the shape of two cells running.
22. ⚠ **A ServiceLoader-contributed Job Type is constructed with NO arguments** and `create` receives only
    a `JobConfig` — neither the engine nor the data root. Defer resolution to run time:
    `JobContext.services()` carries whatever core registered, and `spaceId()` keys a module-side registry.
    ⚠ And a named `switch` case always beats a contributed provider, so a task must be **removed** from the
    switch, not merely also provided.
23. 🔴 **An optional module may only depend on one that ships in every edition where IT ships.**
    `inspecto-ops` is Standard-and-above, `inspecto-policy` is Enterprise-only, so the test edge is legal
    in exactly one direction. ⚠ `-Pedition-standard -DskipTests` **passed** with the wrong direction,
    because this machine's `~/.m2` held a stale jar — a green local build is not evidence here, reactor
    membership per profile is.
24. ⚠ **A three-way outcome must not be squeezed into two.** `DecisionRoutes` reported "Incident already
    open" when the module was merely ABSENT, so a Personal deployment claimed triage was underway for an
    Incident that cannot exist. Absent, present-and-open, present-and-new are three answers. Same class as
    cell 3b's `features.geoLink`, which advertised a button that 404'd.
25. ⚠ **Registration order in a constructor is behaviour.** A Platform Service registered EAGERLY beside a
    neighbour that reads the same field through a SUPPLIER produced 632 NPEs across 40 test classes: the
    field is assigned 40 lines later. When adding to a list of registrations, ask whether the line
    evaluates now or later — not whether it looks like its neighbours.
26. ⚠ **Tests that needed a real engine were only ever testing the Standard shape.** Re-seating
    `AlertServiceTest`/`ReconRunJobTest`/`DryRunServicesTest` onto a core `FakeObjectAccess` is a gain, not
    a compromise: they now assert what core is responsible for (dedupe-then-open, right kind/scope/attrs),
    which is identical on every edition. Move the ones that genuinely assert PERSISTENCE or the object
    GRAPH into the module — a fake asserting its own bookkeeping is a test of the fake.
27. ⚠ **The `V1Body` trap scales.** At one or two moved HTTP tests a local `json()` helper is right (cells
    4, 6). At **seven** (cell 7) one module-local copy in the split package is better: the moved tests stay
    byte-identical and reviewable as *moves*. The drift risk is bounded because core's `ApiContractTest`
    pins the served envelope independently.
