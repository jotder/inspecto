# EDG-01 — gating the six "not for Personal" features (BUILD PLAN, in flight)

> **Status 2026-09-07: ✅ cells 1 (`CP-15`), 2 (`OPS-06`), 3a (route SPI), 3b (`CP-09`) and 4 (`SEC-10`) SHIPPED; cell 5 (`CP-13` metrics half) BUILT, verification pending. Remaining: CP-13's events-feed half and `CP-11` — both need an operator call, see §19.** BACKLOG `EDG-01`, ranked **P1** — the
> only substantive Personal-scoped work on the board. Parent truth:
> [`../EDITIONS.md`](../EDITIONS.md) §Feature × edition matrix + §Edition-gating debt.

## 1. What is actually wrong today

On 2026-09-02 the operator decided six features are **not for Personal**. The matrix records that as `—` in
the P column of `OPS-06` (backup/restore), `SEC-10` (exchange/sharing), `CP-09` (geo map + link analysis),
`CP-11` (operational objects), `CP-15` (delivery channels) and `CP-13` (metrics / events feed / audit
export). **All six are core code and ship in every Personal bundle.** Those cells are a stated product
decision the code does not apply.

🔴 **It is worse than a cosmetic gap, and this is the reason the row is P1.** Personal ships **no
authenticator at all** and **binds every interface by default** (`EDITIONS.md` §Matrix, pinned by
`ControlApiBindTest`). Verified 2026-09-07: `/metrics` is in `ControlApi.PUBLIC_PATHS`, so a Personal
install serves it **unauthenticated to every host that can reach the port** — while its own matrix cell
says the feature is not in the edition.

## 2. Mechanism — DECIDED 2026-09-07 (operator)

**ServiceLoader modules for all six.** The house mechanism, matching `inspecto-security` and
`inspecto-policy` exactly, and the one `EDITIONS.md` §Assembly names first.

⛔ **Not `-D` capability switches.** A switch leaves the code *in* the Personal bundle, making it a policy
boundary rather than a packaging one — and on an install with no authenticator, a mis-set flag re-exposes
an unauthenticated surface. A module Personal never bundles cannot be re-enabled by misconfiguration.

⚠ **The cost is accepted, not waved away.** Four of the six are woven into core today, so each is a real
extraction — a new module, an SPI seam, tests moved — not a gating pass. Deliver **cell-wise**; a scheduled
cell cites its matrix row.

## 3. 🔴 The hazard that must not be discovered late: route order

`ControlApi` registers `RouteModule`s from a **hard-coded ordered `List.of(...)`**, and route matching is
**first-match in that order** (`ControlApi:432`, `:684`). This is not theoretical — on 2026-09-07
`NotificationRoutes`' archive-by-id catch-all `DELETE /notifications/([^/]+)` swallowed a *literal*
`DELETE /notifications/suppressions` registered by a later module, and answered
*"no notification 'suppressions'"*.

**A literal path cannot outrank a parameterised one registered earlier.** So for every route-based cell:

* moving its `RouteModule` **changes its position** in the order;
* if any *other* module registers a pattern that would shadow one of its paths, moving it **changes which
  handler wins** — silently, with a plausible-looking 404;
* ⛔ therefore **no route-based cell may be extracted without first checking for a shadowing pattern**, and
  the check belongs in the cell's own test, not in a reviewer's head.

⚠ ServiceLoader iteration order is **not** specified. So the seam must be: keep the built-in list
hard-coded and ordered, and **append** discovered modules after it — never replace the list with
discovery. Appending is deterministic relative to the core; the ordering question then reduces to "does
any core module shadow this cell's paths", which is answerable per cell.

## 4. What the UI does for an absent capability

The convention already exists and is not new work: **a `503` latches `unavailable`, disables the
affordance, and explains itself inline** — never a toast, never a hard failure
([`okf/frontend/features/inline-ai-authoring.md`](../okf/frontend/features/inline-ai-authoring.md)
§invariant 2; the assist panel is the shipped precedent). Every other error stays a retryable toast.

⚠ For a *removed module* the route does not 503 — it **404s**, because nothing registered it. So each cell
needs either (a) a core stub that answers 503 for its paths when the module is absent, or (b) a UI that
reads the capability from `/bootstrap` and never calls the route. **(a) is preferred**: it keeps the
"explain, do not hard-fail" contract true for any client, not just our SPA.

## 5. Cells — separability census

*(filled from the 2026-09-07 census; ranked easiest → hardest)*

| # | Cell | Reached via | Seam needed | Rank |
|---|---|---|---|---|
| `CP-15` | Delivery channels | `NotificationChannel` **ServiceLoader SPI** — already the right shape | **None to invent.** Move the impls into an optional module | **1 — easiest** |
| `OPS-06` | Backup / restore | `MaintenanceJob`'s switch, cases `backup`/`backup_verify`/`restore` | A maintenance-task lookup the switch can miss; `BackupTask` is package-private with **0** inbound callers | 2 |
| `CP-09` | Geo map + link analysis | `GeoRoutes` + `InvRoutes` (⚠ `InvRoutes` **is** the link-analysis backend — there is no separate service) | None; stop registering the two modules. **0** inbound callers | 3 |
| `SEC-10` | Exchange / sharing | `ExchangeRoutes` **+** `SharedRefResolver.install(new ExchangeRefResolver(…))` at `ControlApi:250` | None to invent — `SharedRefResolver` is already an installed-singleton SPI with a fail-closed `NONE` default; make the install conditional | 4 |
| `CP-13` | Metrics / events / audit export | 🔴 **Not a `RouteModule`** — `/metrics` is registered inline at `ControlApi:416`, sits in `PUBLIC_PATHS:195`, and is special-cased in `isInfraRoute:1059`. `EventRoutes` is a normal module | Conditional logic **inside `ControlApi` itself** | 5 |
| `CP-11` | Operational objects | `ObjectRoutes` (+ `NoteRoutes`/`QueueRoutes`/`TagRoutes`) **+** `MaintenanceJob`'s `incident_purge` **+** `CollectorService.objects()` | 🔴 **Invent one.** `ObjectService` is a concrete class with **no interface**; unrelated core calls in from `DecisionRoutes:189`, `ExpectationRoutes:164`, `AlertService`, `ServiceBootstrap:78`, and three Job types | **6 — hardest** |

🔴 **`CP-13` cannot be a module, and the decision has to absorb that.** `MetricRegistry` is written to from
~30 call sites deep in core (`CollectorProcessor`, `JobService`, `PipelineScheduler`, `EventLog`, …) — that
instrumentation is load-bearing core plumbing. **Only the HTTP exposition can be gated**, so `CP-13` is a
partial extraction: the registry stays, `/metrics` stops being served. ⚠ That is still the whole security
point (an unauthenticated scrape surface on an auth-free edition), but it is not "a module Personal omits",
and the row must not claim otherwise.

⚠ **Route-order check, done for all four route-based cells** (§3): with no `.*` catch-alls in the table,
collision is only possible on identical literal prefixes, and **no other module registers under
`/exchange/`, `/geo/`, `/inv/`, `/objects`, `/notifications/`**. `SettingsRoutes`' `/settings/geo` and
`AgentRoutes`' `/agent/cases/…` merely look similar; both are anchored full-string literals.

🔴 **The one live ordering hazard is inside CP-15, and it is the guard I added today.**
`NotificationRoutes:71`'s `DELETE /notifications/(?!suppressions$)([^/]+)` is load-bearing **only because
`NotificationRoutes` (position 44) registers before `DeliveryStatusRoutes` (position 45)**. ⛔ If those two
ever move, they move **together and in that relative order** — split or reorder them and the guard either
breaks or silently becomes dead code. *(CP-15 as scoped below moves neither, so this is a warning for
later, not a task now.)*

## 5b. 🔴 Two census claims corrected by grounding

Both were checked against the code rather than taken on report, and both change the work:

1. **The connector sidecar is NOT edition-gated.** `package.ps1` copies `inspecto-connectors.jar` into
   **every** bundle with an explicit comment saying so ("remote acquisition is a core product capability …
   the sidecar is NOT edition-gated"). So `SmtpEmailChannel`, which lives in `inspecto-connectors`, **also
   reaches Personal today** — and moving `WebhookChannel` *into* connectors would gate nothing. CP-15 needs
   its **own** optional module, and **both** channels must move into it.
2. **`WebhookChannel` ships in Personal but is INERT, not active.** `configured()` returns `url != null`
   and the url comes from `-Dnotify.webhook.url`, so nothing is delivered until an operator sets it. ⚠ The
   defect is therefore *reachability*, not live traffic: a Personal install can switch on a delivery
   channel its own matrix says the edition does not have. That is exactly what EDG-01 is about, but the row
   must say the true thing.

⚠ What IS unambiguously false today: `NotificationChannel`'s own Javadoc says *"the lean Personal core
ships **no** channel"*. `inspecto-engine/src/main/resources/META-INF/services/com.gamma.notify.NotificationChannel`
registers `WebhookChannel`, and `inspecto-engine` is an unconditional dependency of `inspecto`. The class
documents a property the build does not have.

## 5c. Cell 1 — `CP-15` delivery channels — ✅ SHIPPED 2026-09-07 (the pattern-setter)

Chosen first because it is the only cell whose **seam already exists and is already correct**:
`NotificationChannel` is a `@PublicApi` ServiceLoader SPI, discovered in `NotificationService:98` and
`MailAccess:60`, with **0** inbound callers into the implementations from anywhere else. Nothing has to be
invented, no route moves, so no shadowing risk — it proves the mechanism end to end with the least that can
go wrong. ⚠ It is also the cell that makes a **currently-false doc claim true**.

**Both channels must move**, per §5b.1:

| Class | Lives in today | Reaches Personal because |
|---|---|---|
| `WebhookChannel` | `inspecto-engine` (+ its `META-INF/services` entry) | `inspecto-engine` is an unconditional dependency of `inspecto` |
| `SmtpEmailChannel` | `inspecto-connectors` | `package.ps1` bundles the connector sidecar in **every** edition, by explicit decision |

**Shape:** a new optional module holding both, built and bundled only for Standard/Enterprise — the
`inspecto-security.jar` pattern `package.ps1` already implements twice.

⚠ **Moving `SmtpEmailChannel` out of `inspecto-connectors` is a change to that sidecar's contents**, which
gained a staged-artifact verification step on 2026-09-07 (`CONNECTORS-BUNDLE-1`). ✅ **Checked 2026-09-07: it asserts on
`META-INF/services/com.gamma.acquire.CollectorConnectorFactory`** — the *acquisition* SPI, not the
notification channel — so moving the mail channel out does not trip it.

⛔ **`NotificationRoutes` and `DeliveryStatusRoutes` do NOT move.** They are core in-app notification CRUD
and config, they work correctly with zero channels registered (by design), and moving them is what would
trip the `(?!suppressions$)` hazard in §5. The cell gates the **transports**, not the surface that manages
them.

**Falsification for this cell** — the test that would fail if the gating were fake: with the module absent,
`ServiceLoader.load(NotificationChannel.class)` finds **zero** providers, and `NotificationService` delivers
in-app only. With it present, both are discovered. ⚠ Asserting only the second half proves nothing.

## 6. Acceptance, per cell

A cell is done when **all** of these hold:

1. The feature's classes live in a module the **default** build does not include (⚠ Personal is the
   default — there is no `-Pedition-personal`; the parent POM declares only `edition-standard` and
   `edition-enterprise`).
2. `package.ps1 -Edition Personal` produces a bundle **without** the module's jar, and the Standard and
   Enterprise bundles keep it. Verified by the packaging step, not by reading the POM.
3. With the module absent, the feature's HTTP paths answer **503 with an explanation**, not 404 and not a
   stack trace.
4. With the module present, every existing test for the feature still passes **unmoved in meaning** — a
   test that had to be weakened to make the extraction work is a signal the seam is wrong.
5. `CapabilityManifest` still agrees with the registration sites (`CapabilityManifestTest` fails both
   directions), and the cell's routes are exercised by a real-HTTP test.
6. The matrix cell in `EDITIONS.md` moves from "a decision the code does not apply" to plain `—`, and this
   plan's row is struck.

## 7. Deliberately out of scope

* ⛔ The **Enterprise-only** pair `SEC-08` (data masking) and `CMP-08` (certifications) are in the same
  BACKLOG row but are a *different* problem: they are unbuilt, not mis-shipped. Gating something that does
  not exist is not work.
* ⛔ No `if (edition == …)` anywhere in core. That is the standing rule EDITIONS §Assembly sets, and the
  whole reason the answer is modules.

## 8. Cell 1 as built — and the three things the plan did not predict

New module **`inspecto-notify-channels`** (parent POM's `edition-standard` AND `edition-enterprise`
profiles), holding both transports in one package `com.gamma.notify.channel`, shaded as a `-sidecar`
artifact, bundled by `package.ps1` for Standard/Enterprise only, on the classpath of all four launchers.

🔴 **1. The connector-sidecar verification DID assert `javax.mail` — I had said it did not.** §5c claimed
the check only looked at `META-INF/services/com.gamma.acquire.CollectorConnectorFactory`. It also asserted
`javax/mail/*` classes were present, with a message about `NotificationService.discoverChannels`. Removing
the now-unused dependency from `inspecto-connectors` would have failed packaging on the very next run. The
assertion moved to the new module's check, with the class that needs it. ⚠ **I read one assertion in that
block and generalised about the block** — grep the claim, not the file the claim names.

🔴 **2. `package.ps1` emits TWO sh launchers and TWO bat launchers**, not one of each — `run.sh`/`run.bat`
and `serve.sh`/`serve.bat` (the parity was itself a 2026-09-07 fix). Every classpath edit is four sites plus
the boot-smoke array. Patching "the launcher" would have left half the bundles unable to see the jar.

🔴 **3. The mail channel had to change package, and its test had to lose a helper.** Leaving
`SmtpEmailChannel` in `com.gamma.connect.notify` would have **split that package across two jars**, because
`DeliveryIds` and both `DeliveryStatusAdapter`s stay in `inspecto-connectors`. It moved to
`com.gamma.notify.channel` (not `@PublicApi`; one doc path was the only reference). Its test used the
package-private `DeliveryIds.fromMessageId`, so those two assertions now match the Message-ID **shape**
directly — the round trip stays pinned on both sides without the module depending on the one it left.

⚠ Also done, because the change made it dead: `javax.mail` is removed from `inspecto-connectors`. That
drops a CDDL/GPLv2+CE artifact out of the sidecar **Personal ships**, which is a small win in the same
direction as the cell itself.

⚠ **Residual, deliberate:** the inbound `DeliveryStatusAdapter`s (`Hmac`, `SendGrid`) stay in
`inspecto-connectors` and therefore still reach Personal. They are inert without a configured signing key,
and `DeliveryStatusRoutes` answers 404 for an unconfigured adapter — so nothing is reachable. Gating them
means splitting the connectors sidecar by edition, which is a different (and larger) decision than this
cell. ⛔ Do not treat CP-15 as "not done" for this; treat it as the next question if the sidecar is ever
edition-split.

## 9. Cell 1 against §6's acceptance — including the two it does NOT meet cleanly

| # | Criterion | Result |
|---|---|---|
| 1 | Module absent from the default build | ✅ `inspecto-notify-channels` does not appear in the `mvn -o clean test` reactor at all |
| 2 | Personal bundle lacks the jar, Standard/Enterprise keep it — **proven by packaging, not by reading the POM** | ✅ `package.ps1 -Edition Personal` → `inspecto.jar`, `inspecto-connectors.jar` only. `-Edition Standard` → adds `inspecto-notify-channels.jar`, and the script's own staged-artifact check prints *"verified: javax.mail classes + both NotificationChannel registrations present"* |
| 3 | Absent module ⇒ the feature's HTTP paths answer **503**, not 404 | ⚠ **N/A for this cell, and that is not a dodge.** CP-15 gates *transports*, and a transport has no HTTP path of its own. `NotificationRoutes` / `DeliveryStatusRoutes` stay in core and are designed to work with zero channels registered. ⛔ The criterion still binds every route-based cell (CP-09, SEC-10, CP-11, CP-13) |
| 4 | Existing tests pass **unmoved in meaning** | ⚠ **All but two assertions.** `SmtpEmailChannelTest` used the package-private `DeliveryIds.fromMessageId`, which stayed in `inspecto-connectors` with the adapters that use it; those two assertions now match the Message-ID **shape** directly. §6 says a weakened test signals a wrong seam — here the fact under test is unchanged (the delivery id round-trips into `Message-ID`), only the route to asserting it. Recording it rather than letting it pass silently |
| 5 | `CapabilityManifest` still agrees; routes exercised by a real-HTTP test | ✅ Vacuous here — no route changed |
| 6 | The matrix cell becomes plain `—` | ✅ `EDITIONS.md` CP-15/P updated, with what changed |

**Verified:** `mvn -o clean test` (Personal) **4036/0/0/16**, reconciling exactly as `4045 − 4 − 7 + 2`;
`mvn -o clean test -Pedition-enterprise` **4101/0/0/16** with the new module SUCCESS; both packaging runs
clean; `check-dependencies` unchanged at 95 artifacts.

⚠ **`NoChannelShipsInThePersonalBuildTest` passes under BOTH profiles**, and that is correct rather than a
weak test: it lives in `inspecto-engine`, whose classpath never contains the channels module because the
dependency points the other way. It answers "what does the CORE see", which is exactly the question.

## 10. Cell 2 as built — `OPS-06` backup / restore, ✅ SHIPPED 2026-09-07

**The seam that did not exist:** `MaintenanceTaskProvider` (+ `MaintenanceTaskContext`), a ServiceLoader SPI
consulted by **the `default` arm** of `MaintenanceJob`'s switch. The switch itself is untouched apart from the
three `case` lines that left, so a built-in can never be shadowed by a provider, and a bundle with no provider
behaves byte-identically to before for every task except the three that were deliberately moved.

**The module:** `inspecto-backup` — THIN like `inspecto-policy` (nothing beyond the core, no shade, no
`-sidecar` classifier), holding `BackupTask` (relocated to `com.gamma.backup`, three methods unchanged) and
`BackupTaskProvider`, registered via `META-INF/services/com.gamma.job.MaintenanceTaskProvider`.

🔴 **Refuse, never skip — and this is the design decision of the cell.** On Personal, `task: backup` is an
*unknown maintenance task* and the run FAILS with a message naming the cause. The tempting alternative, a
SKIPPED result, would read to a chained job downstream as a successful no-op — the `ConservationCheck` shape
again. Pinned by `NoBackupTaskShipsInThePersonalBuildTest` in the default build, which also asserts a built-in
still runs (the switch was cut, not broken) and that NO provider is on that classpath.

⚠ **Product-facing consequence, stated rather than hidden:** the bundled `spaces/demo` has a nightly chain
`runlog_retention → db_maintenance → config_backup → backup_verify → maintenance_report` (cron `15 3 * * *`).
On a Personal install it now stops at `config_backup` with a FAILED run; the chain's halt-on-failure guard
stops the rest. That is the edition boundary doing its job. Recorded in EDITIONS `OPS-06` and the
backup/restore runbook.

🔴 **A task claimed by two providers is refused fail-closed**, mapped to a provider that throws naming both
claimants — never resolved by classpath order, which would be the route-table first-match bug in a new coat.

**Tests moved VERBATIM, in package `com.gamma.job`** — a test-scope split package, on purpose: the five
`MaintenanceLibraryTest` cases and three `JobPathContainmentTest` cases construct `MaintenanceJob`,
`RunContext`, `RunLogStore` and `RunArtifactStore`, all package-private. Keeping the package keeps them
unchanged in meaning (§6 criterion 4 met **fully** this time, unlike cell 1), and because they drive
`MaintenanceJob` end to end, every one of them also proves the SPI discovery.

**Against §6:** (1) ✅ absent from the default reactor · (2) ✅ proven by packaging (below) · (3) ⚠ N/A —
a maintenance task has no HTTP path; the unknown-task refusal is its equivalent, and it is LOUD ·
(4) ✅ eight tests moved unchanged · (5) ✅ vacuous, no route · (6) ✅ EDITIONS `OPS-06` updated.

## 11. Cell 3 grounding — `CP-09` geo map + link analysis, the UI half (2026-09-07)

The first route-based cell, and the UI grounding says it is **not** just "stop registering two RouteModules":

* **Callers.** `GeoService.project()/routes()` → `POST /geo/projection`, `/geo/routes`
  (`inspecto/api/geo.service.ts:68,72`); `InvService.project()/neighbors()` → `POST /inv/projection`,
  `/inv/projection/neighbors` (`inv.service.ts:52,57`). Rendered by `GeoMapComponent` (`/studio/geo-map`) and
  `LinkAnalysisComponent` (`/studio/link-analysis`), and **also as dashboard widgets** (`geo-view-widget`,
  `link-view-widget`, offered in `menu-attach.dialog.ts:26-27`). Nav entries are **static, unconditional
  array literals** (`core/navigation/navigation-data.ts:191-209`).

* 🔴 **Neither feature follows the 503-latch convention today.** Both components wrap `.query()` in a plain
  `try/catch` and set a generic inline `loadError` with **no status-code branching**
  (`geo-map.component.ts:481-483`, `link-analysis.component.ts:481-485`) — a 404 and a 503 render the same
  text, after the user has already navigated in. So plan §4's "the convention exists and is not new work"
  is true of the *assist panel*, not of these two. For CP-09 the UI half IS work.

* **The precedent that fits:** `SessionService.exchangeEnabled` — a plain boolean signal read once from
  `GET /bootstrap`'s `features.exchange` (`session.service.ts:36,67,93`), consumed to hide affordances
  (`datasets.component.ts:64`, `widgets.component.ts:71`, and `link-analysis.component.ts:169` already uses it
  for its *share* button). CP-09 needs the same shape — `features.geo` / `features.linkAnalysis` (or one
  flag) — **not** a `LensService` computed, which is RBAC, not module presence.

* ⚠ **There is no filtered-nav precedent to copy.** Exchange has no nav entry, so nobody has yet hidden a
  `navigation-data.ts` item on a bootstrap flag. CP-09 will be the first; it needs either a filtered array at
  construction (inject `SessionService`) or a per-item `visible` predicate. Decide once, then it is the
  pattern for CP-11 too.

* Vitest specs for both features mock the backend (`HttpTestingController`/spies) and are unaffected by
  the backend module's presence — 8 geo specs, 5 link-analysis specs. They stay in the UI.

* ⚠ Gotcha found on the way: `entity-projection.ts:26`'s comment claims "on any failure … falling back to
  the client sample fold", but `queryOne` (`:194-208`) has no catch — it throws. Do not reason from that
  comment about degrade behaviour.

**Implication for the cell's shape.** Backend: the two `RouteModule`s + their `CapabilityManifest` entries
move to an optional module discovered by an APPENDED `ServiceLoader` pass (§3), and `/bootstrap` gains a
`features` flag the same way `exchange` has one. UI: a `SessionService` flag mirroring `exchangeEnabled`, the
two nav items hidden on it, and the two widget offers gated on it. §4's core 503 stub is *still* wanted for
non-SPA clients and for the widget path, but the honest UI behaviour is "not offered", not "offered and then
explained".

## 12. Cell 3 grounding — `CP-09`, the backend half (2026-09-07) — and the blocker the census missed

The census said "no seam to invent; stop registering the two modules". That is true of the *routes* and
false of the *mechanism*: **a `RouteModule` contributed from another jar cannot exist today.**

* 🔴 **`RouteModule`, `ApiContext` and `Handler` are all package-private** (`RouteModule.java:8`,
  `ApiContext.java:26`, `Handler.java:9`), and so are `ApiException` and `WriteGates`, which `GeoRoutes` /
  `InvRoutes` use directly. All 50 implementors live in `com.gamma.control`. So §3's "append
  ServiceLoader-discovered modules" needs a **public route SPI first** — a `@PublicApi` surface expansion, not
  a gating pass. This is the real first task of cell 3, and it is the seam every later route-based cell
  (SEC-10, CP-11) reuses.
* ✅ Appending after the hard-coded list is otherwise safe: nothing after `ControlApi:443` assumes a complete
  route set (`openApiContract()` is a static file read, no reflective enumeration).
* ✅ `GeoRoutes`/`InvRoutes` have **no** `withCapability` gates and **no** `CapabilityManifest` entries (the
  `/settings/geo` entry at `:135` belongs to `SettingsRoutes` and stays). `docs/api/openapi-v1.json`
  documents **neither** `/geo/*` nor `/inv/*`, and `ApiContractTest` compares the served file to the doc
  byte-for-byte — so the API contract is untouched by their removal.
* 🔴 **Guard-scope exemption, found in passing:** `CapabilityManifestTest` finds `withCapability(` call sites
  by **regex-scanning the directory `src/main/java/com/gamma/control`** (`:28 ROUTES_DIR`). A gated route
  moved to any other module's source tree silently drops out of the guard. Not live for CP-09 (no gates), but
  ⛔ it must be widened before CP-11 (which is gated) is extracted, or the drift test the whole
  five-site rule rests on stops seeing those sites.
* Tests: `ControlApiGeoProjectionTest` and `ControlApiInvProjectionTest` are the only `/geo`/`/inv` HTTP
  tests. They construct `new ControlApi(svc, 0)` (package-private ctor) and use `V1Body`, a package-private
  **test-tree** helper — so either they move with a test-scope split package (the cell-2 technique) or
  `V1Body` is promoted to a shared test-fixtures artifact.
* The 503-when-absent idiom exists and is reusable verbatim: `AssistRoutes:47-49`
  `service().assistAgent().orElseThrow(() -> new ApiException(503, …))` — an `Optional` accessor on the
  service, checked first by every route. §4's core stub for CP-09 is that shape.

**Cell 3 is therefore two commits, not one:** (a) make the route SPI public — `RouteModule`, `ApiContext`,
`Handler`, `ApiException`, `WriteGates` (or an `ApiContext`-exposed subset), plus the ServiceLoader append in
`ControlApi` and a widened `CapabilityManifestTest` scan; (b) move geo + link analysis behind it, with the
`/bootstrap` `features` flag (§11) and the UI hiding. (a) is the pattern-setter for SEC-10 and CP-11.

## 13. Cell 3a as built — the public route SPI (2026-09-07)

The seam every remaining route-based cell (CP-09, SEC-10, CP-11) stands on. Five types went public with
`@PublicApi(since = "4.0.0")` — `RouteModule`, `ApiContext`, `Handler`, `ApiException`, `WriteGates` (the
latter two because `GeoRoutes`/`InvRoutes` call them directly: 17 `ApiException` sites, 3 `WriteGates`) — and
`ControlApi` gained two things:

1. **A `ServiceLoader.load(RouteModule.class)` pass, APPENDED after the hard-coded list** (never interleaved,
   never replacing it — §3). Each discovered module is logged by class name so an operator can see what a
   bundle actually registered.
2. 🔴 **A duplicate-registration guard.** Every registration now goes through one `register(method, pattern,
   handler)`, and a second `(method, pattern)` throws at boot naming the route. First-match dispatch means a
   duplicate would otherwise *never fail* — the later handler simply never runs, with the loser decided by
   module order — which is exactly what swallowed `DELETE /notifications/suppressions` earlier today. **Census
   before adding it: 0 duplicates among the 332 built-in registrations**, so it cannot break an existing boot.

🔴 **The guard-scope exemption in `CapabilityManifestTest` is closed.** It regex-scanned only
`src/main/java/com/gamma/control`; it now walks every reactor module's `src/main/java`, so a gated route in an
optional module cannot fall out of the manifest ↔ registration drift check. ⚠ This surfaces a tension for
CP-11 (the gated cell): its manifest entries live in core `CapabilityManifest` while the routes would live in
a module — the manifest is an audit surface for routes that exist only in some editions. Decide before CP-11.

**Proof over real HTTP, not by reading `ControlApi`:** `TestDiscoveredRoutes` is registered ONLY via
`src/test/resources/META-INF/services/com.gamma.control.RouteModule` and answers `GET /test-discovered/ping`
in `RouteModuleDiscoveryTest`; the same test proves the duplicate guard fires for a repeated `GET` **and
does not fire for a `POST` on the same pattern** — a negative test needs a probe that would otherwise succeed.
⚠ That services file sits on every test classpath in the `inspecto` module, so every test `ControlApi` now
carries that one extra route; the path is one nothing else could register.

**Deliberately NOT done in 3a:** no `/bootstrap` `features` flag, no UI change, no 503 stub — those are
3b's, where the first real module (geo + link analysis) gives them a concrete subject.

## 14. Cell 3b — the UI half, built 2026-09-07 (⛔ not committable alone)

`SessionService.geoLinkEnabled` mirrors `exchangeEnabled` exactly (a boolean signal set in `init()` from
`/bootstrap` `features.geoLink`, absent ⇒ false); `NavigationService._build()` drops the two nav ids when it
is false; `MenuAttachDialog` stops asking the registry for `geo-map-view`/`link-analysis-view`; both
components' `catch` gains a 503 branch that names the edition instead of a generic failure (the belt for a
bookmarked URL — the nav hiding is the braces). Specs: two new files + two cases, all two-directional.

⛔ **This half must land in the same commit as the backend flag.** Today no backend emits `features.geoLink`,
so on a real Standard install these edits would hide geo map and link analysis until the flag exists. The UI
was built first only because its tree is independent of the Maven verifier running on 3a.

🔴 **The first version of the nav filter filtered nothing, and only a two-directional spec caught it.**
`studio-group` is a *child* of `platform-group`; my filter walked top-level groups' direct children and
never reached it. The "hides" assertion would have PASSED on its own — the items were "absent" because the
lookup found no group at all. The "shows" assertion, run against the same lookup, returned `[]` and failed,
which is what exposed it. Two rules, both now in the spec: assert both directions, and assert the fixture
contains the thing you are filtering (`expect(studio).toBeDefined()`) so an empty result cannot pass as a
hit. The filter is recursive now (`dropIds`).

✅ **The ordering question is settled, not assumed.** `SessionService.init()` is an `APP_INITIALIZER` that
awaits `/bootstrap`; `NavigationService.get()` runs from `app.resolvers.ts`, a route resolver, i.e. after
initializers. A one-shot filter reads a settled flag. (`menu-builder.component.ts` re-calls `get()` later —
also settled.)

⚠ Spec gotcha, house idiom: the dialog renders `<inspecto-data-table>`, whose theme service walks up to
`GAMMA_APP_CONFIG` — stub `InspectoGridThemeService` with `{ theme: () => INSPECTO_GRID_DARK }`, as the
config-pane spec does, or every case dies in DI before the assertion.

## 15. Cell 3b as built — the backend half (2026-09-07)

Everything §11/§12 asked for, and the shape it settled into:

* **`ApiContext.hasRoute(method, pattern)`** — one exact-string question, implemented off `ControlApi`'s
  duplicate-guard set. It is what lets two things be **derived from what actually registered** rather than
  guessed from the edition: `/bootstrap`'s `features.geoLink` (`BootstrapRoutes`), and the absent-module stub.
* **`inspecto-geo-link`** — thin like `inspecto-policy`; `GeoRoutes` + `InvRoutes` relocated to
  `com.gamma.geolink` (so `com.gamma.control` is not split across jars), made `public` for `ServiceLoader`'s
  no-arg construction, registered via `META-INF/services/com.gamma.control.RouteModule`. The handlers are
  untouched; two `{@link}`s to core types became `{@code}`.
* **`AbsentGeoLinkRoutes`** (core) — registered **last**, after discovery, through **`ApiContext.stub`**, and
  only for the five `(method, pattern)` pairs `hasRoute` says nobody claimed; each answers 503 naming the
  module and the editions. Registering it earlier would shadow the real module; registering it without the
  `hasRoute` skip would trip the duplicate guard. Both orderings are load-bearing and commented at the site.

  🔴 **The first build got this wrong, and the falsification test caught it.** The stub registered through the
  ordinary `api.post/get`, so `hasRoute` answered true for the stub's own pattern — and `/bootstrap` reported
  `geoLink: true` on a Personal build with no module present. "A route exists" and "the feature is installed"
  are different questions; the flag had been derived from the wrong one. Fix: stubs register through their own
  door (`stub`), occupy the route table (so the path 503s, not 404s) but do **not** count for `hasRoute`; a
  real registration over a stub, or a stub over a real route, is refused. `NoGeoLinkShipsInThePersonalBuildTest`
  asserts the flag is present-and-false — that exact assertion is what failed, which is the point of it.
* **Tests moved with the routes** — `ControlApiGeoProjectionTest`/`ControlApiInvProjectionTest` in package
  `com.gamma.control` inside the module (the `inspecto-policy` precedent), `V1Body.of` → a 4-line local
  `json()` peel, fixtures from the `inspecto-etl` test-jar. **`NoGeoLinkShipsInThePersonalBuildTest`** stays
  in core: over real HTTP on the default build, all five paths **503** (not 404 — that would mean the stub lost
  a path; not 200 — that would mean the module leaked), `features.geoLink` is present-and-false, and the only
  discovered `RouteModule` is the test vehicle.

⚠ **The stub's five pairs are a second copy of the module's surface.** They are kept in step from both
sides: the core test proves each 503s without the module, the module's tests prove each 200s with it. A path
added to one and not the other shows up as a 404 on Personal — in the core test, by design.

**Against §6:** (1) ✅ absent from the default reactor · (2) ✅ proven by packaging (both bundles) ·
(3) ✅ **met for the first time** — absent module ⇒ 503 with an explanation, over real HTTP ·
(4) ✅ the two HTTP test classes moved unchanged apart from the peel helper · (5) ✅ vacuous, no gates ·
(6) ✅ EDITIONS `CP-09` updated.

## 16. Cell 4 grounding — `SEC-10` exchange / sharing, the first GATED cell (2026-09-07)

* **Surface:** 11 routes in `ExchangeRoutes` (`:44-68`), 6 of them gated (`canOfferDatasets` ×2,
  `canRequestShares` ×2, `canApproveShares` ×2) with matching `CapabilityManifest` entries (`:65-71`).
  Everything it uses outside `com.gamma.exchange` is already public and in another module
  (`ComponentRegistry`/`ComponentStore`, `Event`/`EventLog`/`EventType`, `SpaceContext`/`SpaceId`); its helpers
  are private statics. **No package-private core type is touched.**

* ✅ **Q2 resolved — the manifest test survives the move.** `CapabilityManifestTest` walks every reactor
  module's `src/main/java` since 3a, so the six gated sites are found wherever they live; the manifest stays
  in core and the drift check stays exact in both directions. At runtime, `capabilityFor` is consulted only for
  a path that actually dispatched, so phantom entries for unregistered routes are never reached.

* ⚠ **Residual, runtime only, and a decision:** `Roles.KNOWN_CAPABILITIES = Set.copyOf(CapabilityManifest.
  capabilities())` (`Roles:79`) keeps the three exchange capability names grantable on Personal — a role or
  policy may name `canOfferDatasets` and it grants nothing. **Decision: leave the vocabulary static.** Deriving
  it from registered routes would make a role file authored on Standard fail validation on Personal — a
  portability break worse than dead vocabulary, and a per-edition difference in a *validator*. Dead vocabulary
  is not a hole: there is no route behind it. Recorded here so nobody "fixes" it into the worse shape.

* 🔴 **`features.exchange` has the exact defect CP-09 fixed for geo-link.** `BootstrapRoutes:62` computes it
  as `containerRoot() != null` — a guess about the deployment, not a fact about the bundle. A Personal install
  with `-Dspaces.root` set would report `exchange: true` with no module present, and the SPA's `canShare`
  buttons (`datasets`, `widgets`, `link-analysis`; `catalog` branches nav on it) would 404 on click. Fix:
  `containerRoot() != null && api.hasRoute("POST", "/exchange/offers")`. **No UI change is needed** —
  `exchangeEnabled` already gates every affordance; only its source of truth was wrong.

* **Seam:** `SharedRefResolver` (`inspecto-engine`, `:18-44`) is an installed-singleton SPI whose `NONE`
  resolves nothing — fail-closed, and `install()` is a public idempotent static. So `ControlApi:250`'s
  `SharedRefResolver.install(new ExchangeRefResolver(spaces))` is deleted from core and the module's
  `RouteModule.register` performs it; absent module ⇒ `DatasetRelation` sees `NONE` and every `shared/` ref
  fails to resolve, which is the correct answer with zero wiring. `ExchangeRefResolver` moves with the routes.

* **Tests:** four HTTP classes (`ControlApiExchange{,Snapshot,View,Widget}Test`) move with the cell-3b recipe
  (package `com.gamma.control` in the module, local `json()` peel). No test asserts `ENTRIES` size.

* **Shape, then:** `inspecto-exchange` = `com.gamma.exchange.*` + `ExchangeRoutes` + `ExchangeRefResolver`
  (relocated packages, public no-arg ctor), thin like policy; core keeps the manifest entries, gains
  `AbsentExchangeRoutes` (11 pairs → 503, `hasRoute`-skipped, registered last) and the `hasRoute`-derived
  flag; `NoExchangeShipsInThePersonalBuildTest` proves 503 × 11, `features.exchange=false`, and
  `SharedRefResolver.global() == NONE` on the default build.

## 17. Cell 4 as built — `SEC-10` exchange / sharing (2026-09-07)

`inspecto-exchange` takes the whole `com.gamma.exchange` package (7 classes, unmoved — it was already its
own package) plus `ExchangeRoutes` and `ExchangeRefResolver`, both relocated into it from
`com.gamma.control` so no package is split. `ExchangeRoutes.register` now performs the two installs the core
used to do in its constructor. Core keeps the manifest entries, gains `AbsentExchangeRoutes` (11 stubs), and
its `features.exchange` flag stops guessing.

🔴 **The census was wrong about the coupling, and the compiler found it — not review.**
`ComponentRoutes.deleteComponent` reaches into the exchange domain by **fully-qualified name**
(`com.gamma.exchange.Exchange.under(...)`, `ShareGrant.ACTIVE`) and therefore never imports it — so the
`grep "^import com.gamma.exchange"` census that reported "one importer" could not see it. ⚠ **An
import-based census cannot see fully-qualified use.** It is a real fence, not incidental: a component another
Space still holds an ACTIVE grant on must not be deleted out from under its consumer.

**The seam it needed:** `SharedItemConsumers` — the `SharedRefResolver` shape (installed singleton, `NONE`
default), installed by the module. 🔴 Its empty default is **correct, not degraded**, and the class says so:
the fence protects consumers of Exchange grants, and with no exchange module there is no Exchange, so nothing
can have been offered and no consumer can be harmed. The fence has nothing to guard rather than failing to
guard something. ⚠ That distinction is worth stating because "empty ⇒ allow" usually IS the degraded answer;
here the premise of the check cannot hold.

**Two decisions from §16, both held:**
* ⛔ The six `/exchange` capability entries **stay in core's `CapabilityManifest` on every edition** — deriving
  the grantable vocabulary from registered routes would make a role file authored on Standard fail validation
  on Personal. Dead vocabulary is not a hole; a per-edition validator is a portability break. Asserted, so
  nobody "fixes" it the other way.
* ✅ `features.exchange` becomes `containerRoot() != null && hasRoute("POST","/exchange/offers")`. Until now
  it was the `containerRoot` check alone — so a Personal install with `-Dspaces.root` advertised an Exchange
  it did not have and the SPA's Share buttons 404'd on click. **No UI change was needed**: `exchangeEnabled`
  already gated every affordance; only its source of truth was wrong.

**Tests:** seven classes moved (four HTTP in package `com.gamma.control` with the local `json()` peel; three
domain in `com.gamma.exchange`), 23 tests. `NoExchangeShipsInThePersonalBuildTest` (5) asserts the 11 stubs,
both flags, both seams' `NONE`, and the retained vocabulary.

## 18. The last two cells, grounded 2026-09-07 — read this before starting either

Both were checked with the lesson cell 4 taught: **grep the fully-qualified package name, not just the
import line.** It changes the answer for CP-11 substantially.

### `CP-13` — metrics / events / audit export (rank 5). A PARTIAL, by nature.

Three core edits, no module move for the registry: `/metrics` is registered inline at `ControlApi:418`, is
listed in `PUBLIC_PATHS:196`, and is special-cased in `isInfraRoute:1113` (alongside `/metrics/acquisition`,
which `AcquisitionRoutes` owns and is arguably not CP-13). `MetricRegistry` is referenced by **14 files
outside its own package** — load-bearing instrumentation that cannot leave the core. So the cell gates the
**HTTP exposition only**, and the row must keep saying so. ⚠ This is the cell that closes the actual security
point of EDG-01: `/metrics` is a `PUBLIC_PATH`, so an auth-free Personal install serves it unauthenticated to
every interface it binds. `EventRoutes` IS a normal `RouteModule` and could move; note its
`GET /events/([^/]+)` catch-all is registered after its literal siblings, so it must move as a whole.

### `CP-11` — operational objects (rank 6). 🔴 The census under-stated this one.

`com.gamma.ops` is **12 classes in `inspecto-engine`** — a MANDATORY module — and **13 classes in that same
module, outside `ops`, depend on it**: `alert/{AlertAccess,AlertService}`,
`job/{CaseRuleEvalJob,DryRunServices,IncidentPurgeTask,JobService,ObjectsAnalyticsJob,PackTestHarness,
PipelineJobRunner,ReconRunJob}`, `notify/{DbDeliveryReceiptStore,InMemoryNotificationStore,NotificationStore}`.
Several hold **typed fields** (`AlertService:65 private final ObjectService objects`,
`JobService:248 private volatile com.gamma.ops.ObjectService objects`), which is what forces the shape: an
SPI would have to replace the *type* at every field and signature, not just the call sites — and the ones in
`com.gamma.job` reference it fully-qualified, so an import census misses them entirely.

**So CP-11 is a fork, and it is the operator's, not mine:**
* **(a) Full extraction** — invent an `ObjectAccess` SPI (the `SharedItemConsumers`/`SharedRefResolver`
  shape), retype 13 engine classes plus the routes, and move `com.gamma.ops` to an optional module. Faithful
  to "ServiceLoader modules for all six", and by far the largest piece of work in EDG-01.
* **(b) Partial, like CP-13** — gate the *routes* (`ObjectRoutes`, `NoteRoutes`, `QueueRoutes`, `TagRoutes`)
  and `MaintenanceJob`'s `incident_purge`, leaving the domain in the engine where the engine already needs
  it. Personal then carries the object model but exposes none of it — which is what the matrix cell actually
  claims, since `CP-11` is about the operational-objects *surface*.

⚠ **(b) is the honest reading of the cell and a fraction of the cost**; (a) is the letter of the mechanism
decision. Worth putting to the operator before either is started, with this measurement in hand.

## 19. Cell 5 as built — `CP-13`'s metrics half; and the two calls that are the operator's

### Built: `GET /metrics` → `inspecto-metrics`

`MetricsRoutes` is a `RouteModule` in a new thin module; core drops the inline registration at
`ControlApi:418` and gains `AbsentMetricsRoutes` (one stub, via `ApiContext.stub`). `/metrics` stays in
`PUBLIC_PATHS` and `isInfraRoute` so the module's route is reachable unauthenticated at the bare path a
scraper expects, and so the 503 is too — a scraper carries no token, and "this bundle does not expose
metrics" is not worth withholding. Nothing leaks: the registry is never read on that path.

✅ **This closes the security justification EDG-01 was ranked P1 on.** `/metrics` is a `PUBLIC_PATH` on an
edition that ships no authenticator and binds every interface, so a Personal install served its full
operational telemetry to anything that could reach the port, while its own matrix cell said the feature was
not in the edition. ⚠ Only the EXPOSITION is gated — `MetricRegistry` is called by nine classes across three modules
and stays in core, so the counters still run; nothing reads them out over HTTP.

⛔ `/metrics/acquisition` is deliberately untouched: it belongs to `AcquisitionRoutes` and is Data-Acquisition
telemetry, a core capability, not CP-13.

### 🔴 What the first verification caught — four core tests assumed `/metrics` answers 200

⚠ **This cell had a bigger test blast radius than the previous four, and only the reactor found it.** Cells
1–4 moved features nothing else asserted on; `/metrics` is a probe other suites *use*. Four tests in
`inspecto-processor` failed on the first run — none of them about metrics gating, all of them reading the
scrape:

| Test | Was | Now |
|---|---|---|
| `ControlApiTest.metricsEndpointIsOpenAndReflectsARun` | scraped `/metrics`, asserted 200 + exposition | **MOVED** to `inspecto-metrics`' `MetricsExpositionTest` — the assertions are still true, just no longer of the default build |
| `ControlApiMultiSpaceTest.awaitMetric` | polled `GET /metrics` for a per-space label | reads `MetricRegistry.global().scrape()` in-process — the same string `MetricsRoutes` serves |
| `ControlApiVersionedSurfaceTest.noSunsetSignallingRemains` | scraped, asserted the retired metric absent | reads the registry — the claim was never about HTTP |
| `ControlApiVersionedSurfaceTest.infraProbesStayUnversioned` | demanded 200 at the bare `/metrics` | accepts **200 or 503**; a 404 is what would mean "no longer unversioned" |

🔴 **The rule this cell adds: gating a route means finding every test that USES it, not just the tests that
are ABOUT it.** Three of these four are testing something else entirely (space isolation, API versioning,
sunset retirement) and merely reached for the scrape as a convenient read-out.

⚠ Two of the four re-seat onto `MetricRegistry.global().scrape()` — which is **not** a weakening, because
that is the exact string `MetricsRoutes` returns. The one assertion that genuinely could not survive in core
is the raw-`text/plain`-not-an-envelope shape: there is no exposition on Personal to have a shape, so it
moved to the module, where `MetricsExpositionTest` also pins the `version=0.0.4` content type.

⛔ Do not "fix" a future failure here by asserting 404 on `/metrics`. 503-not-404 is the absent-module
contract (EDITIONS §4), and the bare path staying served is what `infraProbesStayUnversioned` exists to prove.

### 🔴 Two calls left, and both are the operator's — not mine to assume

**1. `CP-13`'s events-feed half.** The matrix row also covers the events feed and the audit CSV export.
`EventRoutes` is a normal `RouteModule` and could move whole (note its `GET /events/([^/]+)` catch-all is
registered after its literal siblings, so it must move as a unit). ⚠ But that removes `/events` — the event
log the Events screen is built on — from Personal entirely, which is a visible product change of a different
character from a scrape endpoint nobody browses. The 2026-09-02 decision covers the row; the *size* of this
half deserves confirming before it is built. Options: gate the whole feed, gate only
`/events/export?format=csv&type=AUDIT` (the audit-export half the compliance story names), or leave the feed
in every edition and mark the row partial-by-decision.

**2. `CP-11` operational objects — the fork measured in §18.** `com.gamma.ops` is 12 classes in
`inspecto-engine`, a MANDATORY module, with 13 classes in that same module depending on it, several holding
typed `ObjectService` fields. Full extraction means inventing an `ObjectAccess` SPI and retyping all of them
— the largest single piece of work in EDG-01. Gating just the routes + `incident_purge` is a fraction of the
cost and is arguably what the cell claims, since `CP-11` is about the operational-objects *surface*.
⚠ Recommend putting both to the operator together; §18 has the measurement for CP-11, this section for CP-13.
