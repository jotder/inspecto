# EDG-01 — gating the six "not for Personal" features (BUILD PLAN, in flight)

> **Status 2026-09-07: mechanism DECIDED, first cell not yet built.** BACKLOG `EDG-01`, ranked **P1** — the
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

## 5c. Cell 1 — `CP-15` delivery channels (the pattern-setter)

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
