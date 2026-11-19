---
type: Reference
title: Control plane, SPIs and packaging - gotchas
description: Backend traps across routes and authorization, SPIs, optional modules, Postgres stores, packaging and launchers.
tags: [control-api, spi, packaging, editions, gotchas]
timestamp: 2026-11-19T00:00:00Z
item: layer.platform
audience: internal
status: current
verified: 2026-11-19 last-edit
---

# Control plane, SPIs and packaging - gotchas

Moved verbatim from the retired `PROJECT_NOTES.md` (D-IR5, 2026-11-19); the archived copy is `docs/archived-documents/project-notes-2026-11-19.md`. Each entry is cause, then rule.

- 🔴 **A new control-API ROUTE needs an entry in `docs/api/openapi-v1.json`, and your own green tests will
  not tell you** (2026-09-22). `OpenApiPathsContractTest` requires every live route to have an operation.
  Measured: a new per-space settings route passed **its own four real-HTTP tests**,
  `CapabilityManifestTest`, the route-gating guard and all six doc guards — and took the **whole reactor
  red**. ⚠ It fails in `inspecto-processor`, nowhere near the route, and the fail-fast reactor left **13
  modules SKIPPED with 136 STALE surefire reports** on disk; summing those would have read as a
  comfortable green. ⇒ The route checklist is **register → `CapabilityManifest` if gated →
  `openapi-v1.json` → `route-gating-report.mjs` if mutating**. Regenerate, never hand-edit:
  `mvn -o -pl inspecto -am test -Dtest=OpenApiPathsContractTest -Dopenapi.paths.write=true -Dsurefire.failIfNoSpecifiedTests=false -Pedition-standard`.
  Two of this repo's three route contracts fail in a module you did not touch — so **"my targeted tests
  are green" is not a verdict on a route.** Now in `.claude/skills/endpoint/SKILL.md`.

- 🔴 **MOVING A READER IS NOT DONE UNTIL ITS COMMITTED CONFIGS MOVE WITH IT** (2026-09-16). The one-sided
  break happened THREE times in one day, and every time the reader was ours: `BackupTask`, `CleanupTask`,
  then the compactors. The symptoms are quiet — a cleanup sweeping an empty doubled directory and
  reporting success; a verify returning a green "no archive to verify". ⚠ A lane working from a stale base
  **cannot see that it is the one who moved the reader**. ⇒ Land the reader and its configs in one change,
  or sequence them explicitly on the row.

- 🔴 **ROUTE AUTHORIZATION IS OPT-IN — AN UNDECLARED ROUTE IS OPEN** (2026-09-15). `ApiContext.withCapability`
  is a wrapper a route chooses to use; `CapabilityManifest.capabilityFor` documents `null` = "ungated" as a
  legitimate outcome; the router never sees the capability (erased into an opaque `Handler`), and
  `CapabilityManifestTest` sees only registrations that already wrap. Two whole route classes
  (`EventRoutes`, `AssistRoutes`) had no gate and no manifest entry — a *file nobody opened*, not a *route
  nobody listed*. For compliance that is a **mechanism** finding: per-route fixes pass a point-in-time review
  and fail a Type II window. The fix is a fail-closed default (`docs/archived-documents/plans-archive/route-gating-compliance-plan.md`
  step 3), ⛔ landed only after every mutating route is a capability or a categorized exemption — a ratchet
  over an unreviewed list freezes the wrong baseline. ⚠ The audit's "sibling is gated ⇒ this one was
  forgotten" inference failed **six** times; read the handler, the comment and the tests, every time.

- 🔴 **A PER-SPACE FACT READ FROM A JVM-WIDE PROPERTY IS INVISIBLE TO THE WHOLE TEST SUITE.**
  Until 2026-09-15, nine engine call sites read `System.getProperty("assist.write.root")` for the component
  registry while their sibling `dataDir` was per-Space and every control-plane route resolved
  `currentContext().root().config()`. In a multi-Space deployment the two crossed: a run read **one Space's
  registry while writing another Space's data**, failing with `unknown dataset '<an id the route had just
  resolved>'` — *after* a 202 had promised a run. ⛔ **Single-Space deployments make the two paths the same
  directory** — Personal, and every test — so the defect was a no-op everywhere it was ever exercised, and
  only a live multi-Space run exposed it. ⇒ resolve through `com.gamma.pipeline.SpaceConfigRoot`
  (`current()` keys on the space MDC, which both `JobService` submit paths set on the worker thread;
  `forSpace(id)` for a caller holding an id). ⚠ **Move the registry root and the DATA root TOGETHER** — a
  per-Space registry beside a JVM-wide `-Ddata.dir` *is* the defect, so half a migration is worse than none.
  ⚠ Their precedence is deliberately **opposite** and must not be harmonised: `-Ddata.dir` **overrides** a
  Space's data dir (`CollectorService`'s rule), while the config root lets the **Space win** and falls back
  to the property for the default Space only (`ControlApi.writeRoot()`'s rule). A test pins the asymmetry.

- ⚠ **A CAPABILITY-GATED ROUTE NEEDS A `CapabilityManifest` ENTRY TOO**, and the guard that says so fails in
  a **different module** (`inspecto-processor`), nowhere near the route. Because the reactor is fail-fast,
  that one failure left **13 modules SKIPPED — unverified, not passing**, including both edition modules the
  `-Pedition-advanced` profile exists to reach. ⛔ `CapabilityManifestTest` compares the manifest against
  the `withCapability` sites **bidirectionally** — a route in NEITHER is a third case it cannot see, which is
  how 83 ungated mutating routes accumulated (`ROUTE-UNGATED-DEFAULT-1`).

- 🔴 **STAGED is not LOADABLE.** Shipping a file next to the jar only helps if something explicitly loads
  it from there. DuckDB's **autoload** resolves against its own `extension_directory` and ignores
  `-Dduckdb.extension.dir`, which only `DuckDbExtension` reads. Measured four ways 2026-09-14 against
  duckdb_jdbc 1.5.2.1: autoload over an empty directory fails (correct), **over the flat staged directory
  also fails**, over a `<version>/<platform>` tree works, and an explicit `LOAD '<file>'` works. So
  `postgres_scanner` was staged in the morning and still could not load in the afternoon — an air-gapped
  attach reached for a network `INSTALL` anyway. ⛔ **An extension that arrives by AUTOLOAD needs a named
  call site, or its staged file is dead weight** — and there is no `LOAD` in the source to grep for, which
  is exactly what makes it invisible. ⚠ No test caught it because every developer machine already carries
  the extension in `~/.duckdb/extensions`, where autoload does find it.

- 🔴 **"Fails closed on failure" does not cover "succeeds at the wrong thing."** A `catalog_url` with no
  recognised backend prefix does not make DuckLake fail — it reads the value as a **file path** and
  silently creates a private local catalog (measured 2026-09-14: a 1.8 MB DuckDB file in the repo root,
  named after the whole connection string, **password included**). `D10` already made an *unreachable*
  catalog fatal; this one is reached, successfully, privately, with every batch green. ⛔ When a guard
  exists to stop divergence, ask what the WRONG-BUT-SUCCESSFUL path looks like, not just the failing one.
  Now refused by `LakehouseCatalog.requireShared` when partitioned.

- 🔴 **`Paths.get` DISAGREES ACROSS PLATFORMS about a URI, and it fails closed on the box you probe from
  and fails silent on the box you ship to.** Measured 2026-09-14 with `s3://bucket/data`: **Windows**
  throws `InvalidPathException: Illegal char <:> at index 2`; **Linux** (JDK 26, the shipped
  `linux_amd64` target) does **not** throw and returns `/s3:/bucket/data` — a real local directory
  literally named `s3:`, resolved against the working directory. So `dirs.database: s3://…` used to be
  refused here and **silently written to local disk in production**, with `PathJail.contains` giving a
  confident wrong answer about it. ⛔ **Never conclude "that value is rejected" from a Windows probe of
  path code.** Same family as the `catalog_url` note above: the failure mode is success at the wrong
  thing. Now one predicate, `PathJail.isUri`, refused by both the enforcing jail and the 422 write gate;
  ⚠ when object-store paths land (scale-out §5.4 bullets 1 and 6) **dispatch on it, do not delete it** —
  a bucket URI is not containable by `Path` comparison and needs its own rule.

- 🔴 **A prefix match is not a spelling check.** That same guard first accepted `postgres://…` because it
  *starts with* `postgres:` — endorsing the exact spelling its own error message told operators to avoid,
  and which had already been measured failing to attach. ⛔ **When a rule's message enumerates bad values,
  assert every one of them against the rule** (2026-09-14).

- 🔴 **Before adding a call to a shared helper, grep every CONSTRUCTION site of that helper.**
  `PartitionSinkWriter` holds a sink's store and outputs together and looked like the obvious place to
  register DuckLake output — but it serves **two** lanes, and the other already registers through a shared
  tail, so the "tidy" placement would have **double-registered** it (2026-09-14).

- ⚠ **Best-effort staging cannot tell "not wanted" from "not present."** `package.ps1` stages DuckDB
  extensions from a local cache and only WARNS when it finds none — so with no cache on the runner, every
  released bundle shipped an empty `duckdb-extensions/` and `AIRGAP-EXTENSIONS-1` was never actually
  delivered by a release. ⛔ A release path that can silently produce less than intended needs a
  fail-closed switch (`-RequireExtensions`), and **a step that runs only on a tag needs a cheap variant
  that runs on every push** — "a path that executes only at release time" is itself the defect shape
  (2026-09-14, `AIRGAP-EXTENSIONS-CI-1`).

- ⚠ **Operational stores no longer serialise on their own monitor when backed by Postgres.** Since
  `OPS-03` (2026-09-14) each `Db*Store` borrows a connection per operation, so two operations on one
  store can interleave on a pooled source; DuckDB is unchanged. ⛔ New read-then-write code needs ONE
  borrow **and** a DB predicate (`ON CONFLICT`, a fenced `WHERE`) — `synchronized` will not save it.
  Full account, including the two audited exceptions: `okf/product/module/engine/spec-db-layer.md` §2.

- 🔴 **An SPI whose absence removes a safety property must be fail-CLOSED, and two were not.**
  `SpiSlot` routes discovery through `OptionalSpi`, which catches `ServiceConfigurationError` so an
  unloadable **optional** module is an absence rather than a boot failure (PKG-5 — right for the assistant
  sidecar). ⛔ For `Authenticator` and `AccessDecider` absence means *allow*: an empty Authenticator makes
  `ControlApi.dispatch` skip auth on **every** route, and an empty AccessDecider makes both PEPs return
  early, so `PolicyEngine`'s seeded `space-isolation` stops enforcing the **multi-tenant boundary**. Both
  slots now pass `failClosed=true` (2026-09-13). ⚠ The distinction that has to survive any future edit is
  **registered-but-broken vs never-registered** — Personal registers nothing and must still resolve empty.
  ⚠ A new `ATTR_*` constant must also be added to `ControlApi.REQUEST_SCOPED_ATTRS` or it **leaks across
  requests**; `ExchangeAttributeScopeTest` is the guard that catches it.

- 🔴 **Only a REAL provider finds provider-shaped defects.** Standing up WSO2 IS 7.3.0 immediately exposed
  that `OidcAuthenticator` rejected **every RFC 9068 access token** (`typ: at+jwt`; Nimbus allows only
  `JWT`/absent) — and recent Keycloak stamps the same type, so it was never one vendor's quirk. ⚠ The
  offline suite could not have caught it: it mints headers with **no `typ` at all**, which was always
  allowed. Getting a role through needs **three** provider settings (group named for a seeded role · the
  app *requests* the claim · the app lists it in `accessTokenAttributes`), and missing any one gives the
  identical symptom — a valid token whose Subject has **zero capabilities**. ⛔ On WSO2 the claim is
  `groups`; neither the `roles` default nor Keycloak's `realm_access.roles` applies.

- 🔴 **A raw `jdbc:` value in a `*.backend` property is a URL, not a keyword — never lowercase it.**
  Six `ServiceStores` openers read `System.getProperty(…).trim().toLowerCase()` and then passed that same
  string on as the connection URL, so `-Djobs.backend=jdbc:postgresql://db/MyDb?user=Alice&password=Secret`
  silently connected as `mydb`/`alice`/`secret` — Postgres database names, roles **and** passwords are all
  case-sensitive — and on a case-sensitive filesystem `jdbc:duckdb:/srv/Inspecto/x.duckdb` opened a
  different file. Fixed 2026-09-11. ✅ **The rule: compare on a lowercased COPY, pass on the raw value** —
  which `OperationalDb.resolve` already did, twenty lines away. ⚠ The two openers that never lowercased
  (`status`, `events`) were the only correct ones, so "the majority does X" was the wrong signal here.

- 🔴 **Verify a GENERATED launcher by RUNNING it, never by reading the generator — and run the OLD version
  too.** Nothing in CI executes an emitted `serve.sh`/`serve.bat` (`LAUNCHER-GUARD-1`), which is precisely
  why the bug above survived. The technique: extract the `$serve*Content` here-string from
  `inspecto/package.ps1`, write it out, stub the jars, replace the launch line with an `echo`, run it under
  `cmd.exe`/`bash`. Running only the NEW version shows it works; running HEAD's too is what shows the bug
  was real. (`SCR-9` used the same extract-the-here-string technique for its own acceptance.)

- **An `HttpExchange` attribute is per-request by DEFAULT, not by guarantee — derive or clear, never
  trust a stamp.** `sun.net.httpserver.ExchangeImpl` decides *once at class-init* whether each exchange
  gets a private map:
  `static final boolean perExchangeAttributes = !System.getProperty("jdk.httpserver.attributes","").equals("context")`.
  Wherever it falls back — **any pre-26 runtime, or a current one started with
  `-Djdk.httpserver.attributes=context`** — `ControlApi`'s single `createContext("/")` means **one map
  shared by every request in the JVM**. This produced two real defects on 2026-08-11: a stamped `v1` flag
  that latched across requests (`890025e9`, fixed by deriving it from the URI) and, more seriously,
  an authenticated `ATTR_SUBJECT` readable by a later unauthenticated request — set only on success and
  never cleared, so it flowed into `requireCapability`, `actor()` and `authorize()` (`f0d5f131`, fixed by
  clearing the whole `ControlApi.REQUEST_SCOPED_ATTRS` roster as dispatch's first act, with a
  reflection-based completeness guard so a new `ATTR_*` constant missing from the roster is a red build
  rather than a leak). ⚠ **Do not dismiss this as latent on the strength of the local JDK** — the
  `-NoRuntime` bundle runs on any Java 27+ the customer supplies, and `-Djdk.httpserver.attributes=context`
  there brings the shared map back, so the shared-map configuration is a *supported deployment*. Read the JDK source before reasoning about attribute lifetime.

- **A change to a SERVED catalog is not verifiable from the module that computes it**, and
  `mvn -o test -pl <module>` cannot be trusted on its own. Proved 2026-08-10: adding a second
  `transform` entry to `PipelineProjection.RECIPE_VERBS` (inspecto-engine) was verified with
  `-pl :inspecto-engine`, where `StepTypesContractTest` lives — and left `master` RED for four commits,
  because the *same* catalog is asserted again one module up by `ControlApiPipelinesTest` at the route
  level. Route-level contracts live in the module that **serves** them, so a catalog change needs a
  reactor-wide run. Two mechanical traps make module-scoped runs worse than useless here:
  **(a)** `-pl inspecto` alone fails with `NoClassDefFoundError: com.gamma.notify.MailAccess` — it
  resolves siblings from stale installed jars, so it needs `-am`. ⚠ **That trap also has a SILENT form,
  which is the dangerous one** (hit 2026-08-14): when the stale jar is merely *old* rather than
  incompatible, everything compiles and runs, and the module simply tests the sibling's **previous
  behaviour** — a route test asserting a newly-added 422 reported `200` and looked exactly like a broken
  fix. One command settles it before you debug anything:
  `unzip -p ~/.m2/repository/com/gamma/inspector/<artifact>/4.0.0-SNAPSHOT/<artifact>-4.0.0-SNAPSHOT.jar
  com/gamma/<pkg>/<Class>.class | grep -c <NEW_SYMBOL>` — `0` means the run proved nothing. (Note the
  groupId path is `com/gamma/inspector/`; the artifactIds were renamed to `inspecto-*`, the groupId was
  not.) **(b)** with `-am` **and** `-Dtest=`,
  the run dies on the first upstream module with *"No tests matching pattern"* unless you add
  `-Dsurefire.failIfNoSpecifiedTests=false` (`-DfailIfNoTests=false` does **not** cover it — that flag
  is for "no tests at all", not "the filter matched nothing in this module").
  ⚠ **Read a reactor summary for SKIPPED, not just for FAILURE.** A fail-fast abort marks downstream
  modules `SKIPPED`, and "25 modules" in a summary means *listed*, not *built* — a run reported as
  "25 modules, 3017 tests, 0 failures" on 2026-08-10 had in fact failed one module and skipped six,
  `inspecto-oidc` and `inspecto-policy` among them.

- **Hand-assembling a backend classpath will trip the edition model** — which is why nobody assembles one
  by hand anymore: since 2026-08-13 the `inspecto-backend` launch config runs `tools/run-backend.ps1`, which
  derives the classpath at launch and gets the rule below right *by construction*. Read on before editing it.
  `inspecto-connectors` is an *optional* ServiceLoader module carrying `SmtpEmailChannel`, and it
  is deliberately **not** in `inspecto`'s dependency tree — putting its `target/classes` on the classpath makes
  `NotificationService.discoverChannels` find a channel whose `javax.mail` dep is not there, and boot dies
  with `NoClassDefFoundError: javax/mail/Message`. ⚠ **Still true for a hand-built DEV classpath, but no longer
  true of a bundle**: since 2026-09-07 (CONNECTORS-BUNDLE-1) the module ships as the SHADED
  `inspecto-connectors.jar` sidecar, which carries javax.mail and the rest, so a packaged deployment boots and
  the connectors actually work. This note is now about the dev launch path only. Derive the list from
  `mvn -o dependency:build-classpath -pl inspecto -am` (9 modules today) rather than globbing `*/target/classes`,
  and put the module `target/classes` **ahead** of the `.m2` jars so fresh code shadows the stale installed
  `inspecto-*` artifacts (that ordering also silences the duplicate-`logback.xml` warning).

- **`RouteModule.register(api)` runs BEFORE any Space is hosted — never call `api.service()` there.**
  Registration only wires handlers; the Space (and therefore the per-Space service) is resolved *per
  request*. Touching `api.service()` at registration time throws `IllegalState No spaces are hosted` and the
  whole `ControlApi` fails to construct, so it surfaces as **every** `ControlApi*Test` erroring in setup, not
  as one focused failure (26 of them, 2026-07-27). A per-Space migration therefore has to run **lazily on
  first use**, guarded by a `WeakHashMap` keyed on the service (`WidgetTags.backfillOnce`) — the object-CSV
  equivalent gets away with living in `CollectorService` only because that *is* the per-Space object.

- **An optional module that is PRESENT but cannot LINK used to kill the whole boot.** Every optional
  module is found by `ServiceLoader`, and the contract is the absence contract — a missing module 503s
  its routes and nothing else changes. A raw `ServiceLoader.load(X.class).findFirst()` does **not**
  honour that: a jar that is present but unloadable throws, and it throws an `Error`
  (`UnsupportedClassVersionError`, `NoClassDefFoundError`) which `ServiceLoader` propagates rather than
  wrapping in a `ServiceConfigurationError`. Nothing up the stack caught it, and there was **no**
  `LinkageError` handling anywhere in `inspecto`'s main tree. Discovered 2026-09-12 scoping `PKG-5`:
  staging the assistant would have made the server fail to boot on a host whose Java was older than the
  assistant's dependency needs. Fixed by `com.gamma.spi.OptionalSpi`, which all six discovery sites
  now use. ⛔ It deliberately does **not** catch `RuntimeException` from a provider's constructor —
  unloadable is an absence, misbehaving is a defect, and widening the catch turns a bug into a silent
  absence. 🔴 The lesson generalises past this fix: **"optional" is a property of the FAILURE HANDLING,
  not of the packaging.** A module you merely decline to stage is optional; a module whose absence has
  no handled path is mandatory however you ship it.

- **A shaded sidecar must scope the core `provided`, or it ships a second copy of the product.**
  `inspecto-connectors` gets this right; `inspecto-agent` had `inspecto-processor` at `compile` until
  2026-09-12, so its shaded jar carried 8660 entries — 137 duplicate `etl` classes, 69 duplicate
  `service` classes, **98 MB against 3.5 MB of actual module**. Duplicate `ServiceLoader` registrations
  on one classpath are not merely wasteful: they are a second, older copy of the product competing with
  the real one. ⚠ Also exclude anything the core OWNS — the SLF4J binding above all (two bindings make
  backend selection non-deterministic), plus `java.sql.Driver` and `ServletContainerInitializer`.
  Verify on the **staged artifact**: a module's own tests can never fail for a packaging gap, because
  its classes are trivially on its own test classpath (the `CONNECTORS-BUNDLE-1` lesson, re-learned).
