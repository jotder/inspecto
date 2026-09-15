# Route Gating for Compliance — Plan

> **Status: DRAFTED 2026-09-15, operator direction *"goal is to pass compliance"*. ✅ Step 1 SHIPPED the
> same shift — all five gates, verified in a clean worktree (4530/0/0/28). ✅ Steps 4a/4b SHIPPED the same
> shift too (4555/0/0/28 on `a831172f`); steps 2, 3 and 4c–4g are sequenced and grounded but not started.** This plan takes the
> route-gating audit ([`route-gating-audit.md`](route-gating-audit.md), all 11 remaining routes grounded
> 2026-09-15) from *a reviewed list* to *a control an auditor can test*. It is the second half of
> `ROUTE-UNGATED-DEFAULT-1` (P1).
>
> ⛔ **The compliance framing decides every choice below, not engineering tidiness.** An auditor testing
> CC6.1 (logical access) and CC6.3 (role-based authorization) wants three things: a **documented model**
> (who may do what), **evidence it is enforced completely** (not "we reviewed 83 routes by hand" but "the
> system cannot ship an undeclared one"), and an **audit trail** of privileged actions and refusals
> (CC7.2). Everything here serves one of those three or is cut.
>
> 🔴 **The finding that reframes the work: the enforcement model is fail-OPEN.** `ApiContext.withCapability`
> is an opt-in wrapper; a route registered without it is simply open, and `CapabilityManifest.capabilityFor`
> documents `null` = *"the route is ungated"* as a legitimate outcome. A route added next month is
> unprotected unless someone remembers. **That is a mechanism finding, and it outranks any individual
> route** — fixing the five open gates without fixing the default would pass a point-in-time review and
> fail a Type II observation window.

---

## 0. What is already true (grounded 2026-09-15 — do not re-derive)

| Seam | Where | State |
|---|---|---|
| Capability check | `ApiContext.requireCapability` (`:168`) · `withCapability` (`:213`) | 403 `PERMISSION_DENIED` **iff** a `Subject` is attached and lacks the capability. **No-op when no Subject** — Personal is auth-free by design; every route stays open there. |
| Router | `ControlApi.register` (`:1138`) · `record Route(method, pattern, handler)` (`:1160`) | The capability is **erased** into an opaque `Handler` before the router sees it. The router knows `(method, pattern)` only. |
| Manifest | `CapabilityManifest.Entry(method, pattern, capability)` · `ENTRIES` | 91 gated routes, grouped by route class. **No exemption record exists** — exemptions are free-text `⛔`/`⚠` comments. |
| Completeness check | `CapabilityManifestTest` | A **regex scan over every sibling module's `src/main/java`** — it sees optional modules' code, but **only registrations that already call `withCapability`**. An ungated mutating registration is invisible to it by construction. |
| Refusal audit | `ControlApi.routeDispatch` `:741`/`:749` → `AuditTrail.accessDenied` (`:73`) → `EventType.ACCESS_DENIED` | Closed under `AUDIT-REFUSAL-GAP-1`. Carries actor · ip · UA · method · path · status. 🔴 **Does NOT carry the capability that was missing** — `requireCapability` puts it only in the exception message. |
| Mutation audit | `ControlApi.dispatch` `:667` → `AuditTrail.record` (`:52`) → `EventType.AUDIT` | Every successful state-changing request, with `action` (dotted) and `action_category` (`authorization` · `authentication` · `configuration` · `destructive` · `data_mutation` · `export`). 🔴 **Nothing marks a write as *privileged*** (i.e. that it passed a capability gate). |
| Actor | `ApiContext.actor(ex)` (`:299`) | `Subject.id()` on Standard/Enterprise → `agent:<session>` → `X-Actor`/`"appUser"` on Personal (the header is **rejected** on Standard/Enterprise as a spoof guard). |
| Storage | `-Devents.backend` = `memory` (Personal) · `parquet` (Standard/Enterprise — launchers set it when `inspecto-security.jar` is present) · `db` (D6) | Read back via `GET /audit/search` · `/audit/export` (`AuditLogRoutes`, **fail-closed to `{AUDIT, ACCESS_DENIED}`**). |
| Retention | `event_prune` maintenance Job, org policy **1 year** (`compliance/evidence/retention-configuration.md`) | ⚠ **Applies to audit events too.** Evidence must be exported inside the window. The 6-month SOC 2 window fits, but the export step is part of the control, not an afterthought. |
| Counters | `inspecto_events_total{level,type}` only | `ACCESS_DENIED` volume is visible in aggregate; **no per-capability or per-route counter**. |
| Compliance docs | `compliance/controls-matrix.md` CC6 row (`:47-49`) → `evidence/access-review.md` | Seven `compliance/evidence/*.md`, **all hand-authored** prose citing `file:line`. No generator exists. |

The **11 grounded routes** and their verdicts are in the audit's *§5 GROUNDED* table: **5 gate · 3 deliberate
exemptions · 3 operator calls**. The 16 Incident/Case triage routes have a decision (*gate the
state-changing ones only*) but no per-route classification yet.

---

## 1. Step 1 — Gate the five — ✅ SHIPPED 2026-09-15

**Compliance purpose:** close the known gaps before the observation window opens. One of them is a live
request-forgery surface, which an auditor would classify as a finding in its own right.

| Route | Capability | Why this one, in one line |
|---|---|---|
| `POST /import` | `canAuthorWorkbench` | Writes `config/` and **hot-registers** connections/pipelines; both true siblings (`/bundle/import`, `/pipelines/import`) carry it. |
| `POST /events/views` | `canAuthorWorkbench` | `SavedView` is **server-wide** (`name, filters, createdAt`, no subject); one store per service. Authoring, not a personal convenience. |
| `POST /events/views/{id}/delete` | `canAuthorWorkbench` | Same store; deletes another caller's view. A POST-shaped DELETE changes nothing about authorization. |
| `POST /assist/settings` | `canAuthorWorkbench` | **Server-wide** provider config. Its javadoc names `scope: assist.write` — **documented and unenforced**, which is an auditor finding on sight. |
| `POST /assist/settings/test` | `canAuthorWorkbench` | Makes a **real outbound call** to whatever `baseUrl` was last saved. |

🔴 **The assist pair is gated in ONE commit.** `/assist/settings` is the injection point; `/assist/settings/test`
is the trigger. Gating only the second leaves an authenticated caller able to point the server at an arbitrary
URL and wait for someone else to press *test*. ⚠ Unlike `/agent/*` (correct-but-unreached: nothing stages
`inspecto-intelligence`), **`inspecto-agent` IS staged** (`package.ps1:340-348`) — this pair is live in every
Standard and Enterprise bundle.

**Mechanics** (the shape every gated route already uses):
- wrap the registration in `ApiContext.withCapability("canAuthorWorkbench", …)`;
- add a `CapabilityManifest.Entry` under the owning route class, with a one-line *why* comment in the house
  style (`POST /requirements` and the `SpaceRoutes` block are the models);
- **tests, one per route, in the existing real-HTTP class** (`ControlApiEventsTest`, `ControlApiAssistTest`;
  `/import` has none and gets one): **401** with no credential · **403** with `"Bearer plain"` (a present
  `Subject` with no capabilities — the case that distinguishes a gate from mere authentication) · the normal
  outcome with the capability. ⚠ For the assist pair the "normal outcome" in a test without `inspecto-agent`
  on the classpath is **503**, not 200 — that still proves the gate *passed* (it is not 403). Say so in the
  test rather than skipping it.
- `CapabilityManifestTest` must stay green in both directions — it is the pin that manifest and code agree.

**Acceptance:** reactor green with `-Pedition-enterprise`; the five appear in `ENTRIES`; each has a 403 test
that goes red when its `withCapability` is removed (mutation-check at least the assist pair).
✅ **MET 2026-09-15.** Mutation: the `withCapability` on `POST /assist/settings` removed in the worktree →
`settingsPairRequiresCanAuthorWorkbench` red (expected 403, got 200), `settingsGateRunsBeforeTheAbsentModule503`
red (expected 403, got 503), and `CapabilityManifestTest` red naming the drift (*"declared in the manifest but
NOT registered: POST /assist/settings"*); the other seven assist tests stayed green; restore verified byte-identical.

---

## 2. Step 2 — Decide the remainder, so that "ungated" becomes a recorded state (OPERATOR + BUILD)

**Compliance purpose:** an auditor asks *"why is this one open?"* about every mutating route. Today the
answer exists for 49 + 34 + 3 routes as prose in the audit and as code comments. **Every route must end this
step in exactly one of two states — a capability, or an exemption with a category and a reason.** No third
state.

**2a. Three operator calls** (from the grounding; each is a real question, not a formality):

| Route | The question | What decides it |
|---|---|---|
| `POST /spaces/import` | Does the `POST /spaces` "additive, not gated — it is the recovery route" decision extend to bundle import? | The comment on the previous line reasons *"creating a Space is additive"*, which fits — but it never names `/spaces/import`, no test covers it, and import writes a whole config tree, which `POST /spaces` does not. |
| `POST /tags/rules/{id}/apply` | Is bulk-applying a Tag Rule an *operate* action (gate `canOperateRuns`) or a collaboration act (open, like assignments)? | Its own comment says operational-and-ungated; the identically shaped `DecisionRoutes` rule-apply **is** gated `canOperateRuns`. One of the two files is wrong. |
| `POST /recon/promote` | Which family does *manually opening an Incident* belong to? | `canAuthorWorkbench` has no precedent for Incident creation. The codebase already disagrees with itself — `DecisionRoutes` opens Incidents under `canOperateRuns`, `ExpectationRoutes` opens them **ungated**. No Incident capability exists. ⚠ Its dedupe lines (`ReconRoutes:192-198`) are the same ones the `(type, key, column)` parity decision rewrites — **do both in one change**. |

**2b. The 16 Incident/Case triage routes** (`inspecto-ops`). Decision already taken: **gate the
state-changing ones only** (`resolve` · `close` · `assign` · `reopen` · `promote` · `transition` · `merge` ·
`split` on `canAdminister`); comment/annotate/attach/link stay open as collaboration. ⇒ a per-route
classification pass over `ObjectRoutes`, each row landing as an `Entry` or an `Exemption`. ⚠ The audit itself
warned this family *"needs a product decision first"*; it now has one, so this is classification, not
decision.

**2c. Transcribe the reviewed buckets into an explicit table.** The audit's §1 identity flow (3), §2
self-verifying public (2), §3 self-service (4), §4 read-shaped POST (34), §7 self-limiting (6), plus the
three grounded exemptions (`/recon/run`, the two `/tags/assignments`), plus `POST /requirements` and
`POST /spaces` — **each becomes an `Exemption(method, pattern, category, reason)`**. The category vocabulary
IS the audit's bucket taxonomy, so nothing is invented:

`identity-flow` · `self-verifying-public` · `self-service` · `read-shaped` · `self-limiting` ·
`recovery-route` · `target-visibility-gated` · `stateless-compute` · `absent-module-stub`

⚠ **One route to classify that the grounding surfaced and the audit's §5 did not name:**
`POST /assist/(.+)` (`AssistRoutes:43`) — the skill-intent catch-all. Establish which bucket it is in
(read-shaped, presumably) before step 3, or the boot check refuses it.

**Acceptance:** zero mutating routes in a third state. The count is derived (step 3's scan), not asserted.

---

## 3. Step 3 — Flip the default: undeclared mutating route ⇒ REFUSED (BUILD, after step 2)

**Compliance purpose:** this is **the control**. It turns "we reviewed the routes" (a point in time) into
"an undeclared mutating route cannot exist in a running server" (a property an auditor can test by trying to
add one). ⛔ **Not before step 2 completes** — a fail-closed default over an unfinished exemption table
either bricks boot on the undecided routes or forces them into the table unreviewed, and the audit's
*"ratchet LAST"* was written against exactly the second outcome.

**3a. Make the capability visible to the router — without touching a single call site.**
`withCapability` today returns a bare lambda. Change it to return a marked handler:

```java
record Gated(String capability, Handler inner) implements Handler { … }   // in ApiContext
static Handler withCapability(String capability, Handler h) { return new Gated(capability, h); }
```

`Route` gains a `capability` (null for open); `ControlApi.register` reads `h instanceof Gated g ? g.capability() : null`.
Every route class across every module already calls `api.post(pattern, ApiContext.withCapability(...))`
— they all get this for free. ⚠ The router now holds the **runtime** inventory, which `CapabilityManifestTest`'s
regex could never give it, and which is exactly what a running server needs to attest its own shape.

**3b. An explicit exemption record beside the manifest.**
`CapabilityManifest` gains `record Exemption(String method, String pattern, String category, String reason)`
and `EXEMPTIONS`, populated from step 2c. Same style as `ENTRIES`: grouped by route class, one-line reason.

**3c. The boot-time refusal.** In `ControlApi.register`, for `POST`/`PUT`/`PATCH`/`DELETE`:
if the handler is not `Gated` **and** `(method, pattern)` matches no `Exemption` ⇒
`throw new IllegalStateException("undeclared mutating route <METHOD pattern> — declare a capability with
ApiContext.withCapability, or an Exemption in CapabilityManifest with a category and a reason")`.
The stub path (`ControlApi:1130`, 503 placeholders for absent optional modules) is exempt by construction and
records itself as `absent-module-stub`. ⛔ **No `-D…=warn` escape hatch.** A control with an off switch is
not a control; CI is where an undeclared route is caught (3d), not production.

**3d. The CI ratchet — the belt to 3c's braces.** Extend `CapabilityManifestTest`'s scan to enumerate
**every** `api.(post|put|patch|delete)(` across all modules (the same generalization the audit used to count
332), and require each to be `withCapability`-wrapped **or** in `EXEMPTIONS`. This catches an optional
module's undeclared route at build time — before it can refuse a boot in a customer's bundle. ⚠ The two
checks are complementary, not redundant: the scan sees **all** modules' code (the test classpath does not
carry optional modules, `ApiContractTest:190-226`); the boot check sees **what is actually deployed**.

**3e. Reads stay open — as a stated decision.** Per the operator (2026-09-15): reads are not
capability-gated on any edition; confidentiality sits at the Space/ABAC layer. The boot check therefore
covers mutating methods only, and the plan says so here so an auditor reads a decision, not an omission.

**Acceptance:** adding `api.post("/x", h)` with no declaration to any route class fails `CapabilityManifestTest`
**and**, if forced past it, fails `ControlApi` construction with the message above (a test does exactly
this). `CapabilityManifestTest` still pins `ENTRIES` ↔ `Gated` registrations in both directions, now via the
runtime inventory for core plus the scan for optional modules.

---

## 4. Step 4 — Audit events and the evidence report (BUILD, alongside 3)

**Compliance purpose:** CC7.2 — the auditor wants to *see* refusals and privileged actions, by actor, over the
window, and wants the inventory the control enforces to be **derived from the code**, not typed by hand.

**4a. Refusal events carry the capability. ✅ SHIPPED 2026-09-15.** `requireCapability` records the missing capability on the
exchange (an `ATTR_DENIED_CAPABILITY` attribute, the existing `attr(ex, …)` idiom) before throwing;
`AuditTrail.accessDenied` reads it into a new `AuditAttrs.CAPABILITY`. Reuse `EventType.ACCESS_DENIED` —
⛔ no new type: `AuditLogRoutes` is fail-closed to `{AUDIT, ACCESS_DENIED}`, and a new type would be
invisible to `/audit/search` until allowlisted.

**4b. Privileged writes are marked. ✅ SHIPPED 2026-09-15 — and NOT the way this paragraph planned it.**
As built, `AuditTrail.record` reads the same exchange attribute 4a sets in `requireCapability`, so it needed
**no router change and did not wait for 3a**. The attribute is stamped only when a `Subject` is attached
(a check actually ran), so on Personal an `AUDIT` row carries none — absence means *not checked*, never
*checked and passed*. ⚠ The first cut omitted `ATTR_CAPABILITY` from `ControlApi.REQUEST_SCOPED_ATTRS` and
`ExchangeAttributeScopeTest` refused it (a cross-request leak on shared-attribute runtimes); fixed before
commit. Original text: `AuditTrail.record` gets the matched `Route`'s capability (the router
now knows it, 3a) and stamps `AuditAttrs.CAPABILITY` on the `AUDIT` event. ⇒ *"every privileged write, by
actor, by capability, in the window"* becomes one `/audit/search` query. Ordinary mutations carry no
capability and are distinguishable by its absence.

**4c. One inventory event at boot.** After registration completes, `ControlApi` emits **one** `AUDIT` event,
`action = "route.inventory.snapshot"`, `action_category = "configuration"`, payload = counts (routes ·
gated · exempt, by category) plus a **digest of the full table**. ⇒ the evidence store holds *what was true
at each boot*, and a changed digest between boots is itself a signal. The full table is served, not embedded:
**4d.**

**4d. A read route for the inventory.** `GET /audit/route-inventory` → the runtime table
`{method, pattern, capability | exemption{category, reason}}` from the router. Read, therefore open by
policy (3e). This is what the report generator and an auditor's own probe both consume.

**4e. The evidence report — derived, and CI-enforced against drift.**
`compliance/evidence/route-gating.md`, following the seven existing evidence files' shape (prose framing the
control, then the table, each row citing `file:line`). 🔴 **The table is GENERATED, never hand-typed**:
`tools/route-gating-report.mjs` runs the same code scan as 3d, emits the table between `<!--route-gating:begin/end-->`
markers with a per-row link to the registration line, and — in guard mode, wired into `ci.yml` and
`.githooks/pre-push` like `check-doc-counts.mjs` — **fails the build if the committed table differs from
the code**. That is the *"evidence with an enforced link"*: the document cannot say something the code does
not. ⚠ This is the repo's first generated evidence file; the seven others are hand-authored. The prose stays
hand-authored — only the table is derived — so the convention is extended, not broken.

**4f. `controls-matrix.md` CC6 row** cites the new evidence file and states the control in one sentence:
*"a mutating route without a declared capability or a categorized exemption fails the build and refuses to
boot."* CC7.2 cites 4a/4b and the export step below.

**4g. The export step is part of the control.** Audit events prune at 1 year. The evidence procedure in
`route-gating.md` includes: `GET /audit/export?type=ACCESS_DENIED&from=…&to=…` and the same for `AUDIT`
with a capability, archived to the compliance store before any prune could reach them.
`audit-log-extraction.md` already describes the mechanism; this plan makes it a scheduled obligation.

**4h. Optional, cheap, useful for CC7.2 monitoring:** a `inspecto_access_denied_total{capability}` counter
beside the existing `inspecto_events_total`. Not required to pass; required to *notice* a probing attacker
before the auditor does.

**Acceptance:** an `ACCESS_DENIED` for a capability refusal carries `capability`; an `AUDIT` for a gated route
carries it too; `GET /audit/route-inventory` returns the table; `tools/route-gating-report.mjs --check`
is green on a clean tree and red after a deliberate one-line edit to the committed table (falsify it in
both directions, as `check-doc-counts` was).

---

## 5. What to AVOID — each one a way to look compliant while not being

- ⛔ **Changing HTTP verbs as a security measure.** Turning read-shaped POSTs into GETs (or anything into
  POST) is a MAJOR — 34 routes and the UI — that no auditor credits. The verb was never the boundary; the
  declaration is. A read-shaped POST gets an `Exemption(read-shaped, …)`, and keeps its body.
- ⛔ **Gating reads inconsistently to look thorough.** Worse than a stated policy of open reads: it invites
  the question *"why these and not those?"* with no answer. Reads are open by decision (3e); write that down.
- ⛔ **Ratcheting before step 2 completes.** It freezes unreviewed exemptions into a baseline — the exact
  failure the audit was written to prevent — or bricks boot on the undecided routes.
- ⛔ **A `warn` mode on the boot check.** A control with an off switch is a suggestion. CI (3d) is the place
  an undeclared route is caught early; production is where it is refused.
- ⛔ **Splitting `canAuthorWorkbench` under deadline.** It guards 52+ routes and an auditor may call it coarse.
  Document the role → capability matrix honestly now; refine when an auditor names a specific concern.
  Halving a capability in a hurry yields two half-capabilities and a migration.
- ⛔ **Opening the SOC 2 observation window before step 3 lands.** The window should observe the control in
  place for its whole duration, not watch it being introduced. (Recorded as *not started* on 2026-09-15.)
- ⛔ **Hand-typing the evidence table.** Six of the seven existing evidence files are prose against code; that
  is fine for narrative. An *inventory* typed by hand drifts the day after it is written — the `SPEC-COUNTS-1`
  class of defect. Derive it, and fail CI when it drifts.
- ⛔ **Letting Personal muddy the claim.** Personal is auth-free by design and enforces no gate. The scope
  statement says: access-control claims apply to Standard and Enterprise. The `EDITIONS.md` legend now says
  ✅ means *present and usable*, not *gated* — keep it that way.
- ⛔ **Treating "outlier among siblings" as evidence.** It failed six times on this audit. Every remaining
  classification in step 2 reads the handler, the comment and the tests, and writes the reason down.

---

## 6. Sequence and dependencies

```
Step 1 (five gates) ─────────────────────────────┐
Step 2a (3 operator calls) ──┐                   │
Step 2b (16 triage classify) ┼─► Step 2c table ──┼─► Step 3 (fail-closed default) ─► Step 4c/4d/4e (inventory, report)
Step 2 assist/(.+) bucket ───┘                   │
Step 4a/4b (capability on events) ───────────────┘  (independent of 3; land with 1 or 3)
```

Step 1 and 4a/4b can ship this shift. Step 2a is blocked on the operator; 2b/2c are classification work.
Step 3 lands the moment 2 completes, and 4c–4g land with it. **Then** open the observation window.

## 7. Open items owed by the operator (also filed in `BACKLOG.md` §1)

- The three route calls in §2a.
- Confirmation that reads-open-by-policy is the compliance position to state (it was decided 2026-09-15 as
  engineering; §3e makes it a compliance claim).
- Which framework(s) the report is written against — SOC 2 Type II is assumed throughout; ISO 27001 A.9
  maps onto the same evidence but the matrix rows differ.
