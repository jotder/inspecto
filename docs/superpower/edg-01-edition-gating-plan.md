# EDG-01 — gating the six "not for Personal" features (BUILD PLAN, in flight)

> **Status 2026-09-07: mechanism DECIDED; ✅ cells 1 (`CP-15`) and 2 (`OPS-06`) SHIPPED, four cells remain.** BACKLOG `EDG-01`, ranked **P1** — the
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
