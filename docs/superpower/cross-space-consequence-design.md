# Design — cross-Space consequence (Signal / Decision networks, S8)

**Row:** `BACKLOG.md` §3.5 *Signal / Decision networks — cross-Space consequence* (P2, trigger FIRED
2026-09-15, re-grounded 2026-09-17). **Status:** DESIGN ONLY, written 2026-09-24 — no production code. Every
slice in §6 waits on the decisions in §8. Owners once built: `okf/backend/control-plane/signal-backbone.md`
and `okf/backend/control-plane/decision-rules.md`.

**Scope.** The consequence the trigger names: *a Signal in one Space must cause something in another.*
Connector-direct emission rides with it (§6 slice 5). ⛔ RFC 6902 JSON Patch state deltas for AG-UI do
**not** ride along — they still have no consumer and nothing in this design creates one.

**The answer in one paragraph.** Only a **Signal** crosses the boundary — never a Consequence. The origin
Space *announces*; the target Space *decides*, with its own already-authored `on_signal` Jobs, under a
**standing grant the target Space approved**. The apply path never gains a target-space parameter. The
consent handshake reuses the **Exchange** (Offer / Share Grant) with a new kind `signal`, but only after the
Exchange's own approval check is fixed to authorize each side against **that side's** roles (§2.6 — today it
does not). The triggering caller's identity travels as provenance, never as authority.

---

## 1. The two questions the row asks, answered

**Q-A — Does a consequence need its own authorization, independent of the triggering caller?** **Yes.** A
Subject's capabilities are resolved *per request against the bound Space's* `roles.toon` (§2.5), so a grant
held in Space A says nothing about Space B. Worse, the commonest trigger — an `on_signal` Job — runs with
**no caller at all** (§2.4). A design that authorizes the cross-Space effect with the caller's identity is
therefore both a confused deputy (for `/apply`) and undefined (for automation). The authorization must be a
**standing, target-owned grant**, checked at delivery time.

**Q-B — Under what identity does a target Space accept an externally originated Signal?** Under the
**Share Grant** that a principal *authorized in the target Space* approved. The delivered Signal's `actor` is
a Ref to that grant (`exchange-grant:<id>`); the originating actor, Space and Signal id ride as
framework-stamped provenance attributes. Whatever the delivered Signal then causes is caused by the target's
own Job, authored by someone holding `canAuthorWorkbench` in the target — exactly as for a local Signal.

---

## 2. As-is — grounded 2026-09-24 at `7bb36309c`

### 2.1 One ledger per Space, routed by thread MDC

- `EventLog` keeps a per-Space registry `SPACES` (`inspecto-event/src/main/java/com/gamma/event/EventLog.java:77`);
  `current()` resolves the calling thread's `SPACE_MDC_KEY` against it and falls back to `GLOBAL`
  (`EventLog.java:103-110`). ⚠ An unset or wrong MDC silently writes to the wrong ledger — it never throws.
- Subscribers run **synchronously on the emitting thread**, and every subscriber fault is swallowed
  (`EventLog.java:170-178`). A forwarder built as a subscriber inherits both properties: it runs under the
  *origin's* MDC, and a failure loses the delivery with no trace.
- The HTTP seam binds the MDC for one request only: `/spaces/{id}/…` sets it, `default` sets nothing, and it
  is removed in `finally` (`inspecto/src/main/java/com/gamma/control/ControlApi.java:725-746`).

### 2.2 Each Space is its own runtime — but another Space is one call away

- `SpaceContext` holds one Space's `CollectorService`, "each fully isolated"
  (`inspecto/src/main/java/com/gamma/service/SpaceContext.java:16-19`, `:29`).
- ⚠ **Isolation is by instance, not by access control.** `SpaceManager.space(id)` hands any hosted Space's
  context to any in-process caller (`inspecto/src/main/java/com/gamma/service/SpaceManager.java:519-522`), and
  the Exchange already uses it to reach an owner Space's registry and data (`inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:117-124`).
  "Structurally absent" (the row's wording) is true of the **apply path**, not of the process: the plumbing
  to touch another Space exists, which is exactly why the authorization has to be designed first.

### 2.3 `Signal.space` is descriptive — and Decision Rule signals leave it null

- The record carries `space` and `actor` (`inspecto-engine/src/main/java/com/gamma/signal/Signal.java:27-29`),
  persisted as `ATTR_SPACE` (`Signal.java:47`, `:69`). Nothing reads it to gate anything.
- 🔴 `DecisionRoutes.emitSignal` constructs every Decision Rule Signal with `space = null` **and**
  `actor = null` (`inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:311-316`). Even the
  descriptive field is unset, and the operator who pressed *Apply* is not on the Signal.

### 2.4 The apply path always binds to the request's own Space

- `POST /decision-rules/{name}/apply` is gated `canOperateRuns` (`DecisionRoutes.java:63-64`). Every
  consequence resolves through `api.service()` — the bound Space: `start-job` (`DecisionRoutes.java:216-226`),
  `trigger-pipeline` (`:227-235`), `create-incident` (`:236-259`). There is no target-space parameter.
- `start-job` records the trigger as `decision-rule:<rule>` (`DecisionRoutes.java:220`) — the human actor is
  dropped at the boundary even within one Space.
- `on_signal` dispatch: `JobService.start` subscribes to **this** Space's ledger
  (`inspecto-engine/src/main/java/com/gamma/job/JobService.java:622-628`); `onSignalEvent` fires matching Jobs
  with trigger `signal:<type>` and no Subject (`JobService.java:839-867`). Loop control is a `chainDepth`
  read **from the payload** (`JobService.java:843`, cut at `jobs.signal.maxChainDepth`, default 8,
  `JobService.java:285`, `:852`) plus a self-loop guard keyed on the emitting Job's name (`JobService.java:847`).

### 2.5 Capabilities are per-Space, per-request, and the Subject does not say which Space

- `requireCapability` checks `Subject.capabilities()` and is a **no-op when no Subject is attached**
  (Personal, and every test that attaches none) (`inspecto/src/main/java/com/gamma/control/ApiContext.java:169-186`).
- The OIDC authenticator resolves the role→grant table from the bound Space's `roles.toon`
  (`inspecto-security/src/main/java/com/gamma/security/OidcAuthenticator.java:149-152`, via
  `inspecto/src/main/java/com/gamma/control/Roles.java:197-213`); `ControlApi` stamps that root from
  `writeRoot()` before authenticating (`ControlApi.java:854-855`).
- `Subject` is `(id, capabilities, dataScopes, attributes)` — **no Space id**
  (`inspecto/src/main/java/com/gamma/control/Subject.java:26-27`). A Subject cannot be carried to another
  Space and re-checked; it must be **re-resolved** there, which only an HTTP request bound to that Space does.

### 2.6 The precedent — the Exchange — and the gap it carries

- The Exchange is the one shipped cross-Space mechanism: installation-scope, un-prefixed routes; offer →
  request → owner approves a Share Grant; each mutation gated and signalled
  (`ExchangeRoutes.java:22-44`, routes `:76-95`). It is excluded from the Personal build
  (`NoExchangeShipsInThePersonalBuildTest`).
- 🔴 **As read, owner-side authority is not checked against the owner Space.** The routes are un-prefixed,
  so the capability is resolved against the *default* Space's roles (`ControlApi.java:854` — `writeRoot()`
  with no MDC); `actOnGrant` then approves by grant id with only the actor string
  (`ExchangeRoutes.java:214-237`, `inspecto-exchange/src/main/java/com/gamma/exchange/Exchange.java:171-183`).
  Nothing verifies the approver holds `canApproveShares` **in the owner Space**. Not reproduced by a test in
  this lane — slice 0 starts with that test. ⛔ A signal kind built on this unchanged would let a user who
  can approve anything in `default` open a consequence channel into any Space.

### 2.7 Glossary constraint

`GLOSSARY.md` §Space states *"Activity in one Space is invisible to another."* This feature is a deliberate,
consented exception to it, and the sentence must be amended in the same change that ships slice 3 (D11).

---

## 3. Threat model

| # | Threat | Today | Must hold after |
|---|---|---|---|
| T1 | **Confused deputy** — a caller with `canOperateRuns` in A causes a Job run in B where they hold nothing | impossible (no path) | B's authority is B's grant; the caller's A-capabilities are necessary for the emit, never sufficient for B |
| T2 | **Authoring escalation** — `canAuthorWorkbench` in A authors a Decision Rule whose effect lands in B, later fired unattended | impossible | A can author only *what it announces*; what happens in B is authored in B |
| T3 | **Unattended identity** — an `on_signal` chain has no Subject to authorize anything | n/a in-Space | the grant is the standing authorization; no runtime Subject needed |
| T4 | **Loop / amplification** — A→B→A ping-pong, or a storm | `chainDepth` from payload | depth framework-stamped on delivery (origin +1), never payload-trusted; per-Job coalescer still applies |
| T5 | **Spoofed provenance** — a Signal in B claims to come from A | `space` null, no origin attrs | origin attributes set **only** by the forwarder; payload cannot set them; delivered types are namespaced |
| T6 | **Data egress** — A's payload lands on B's ledger, where reads are open to B | n/a | the offer declares a payload key allowlist; everything else is stripped at the boundary |
| T7 | **Wrong-ledger write** — delivery runs on A's thread with A's MDC | n/a | delivery binds B's MDC explicitly and restores it; a test asserts the Run lands in B |
| T8 | **Silent loss** — subscriber fault swallowed (`EventLog.java:170-178`) | n/a | every attempt records `delivered` / `undeliverable` on A's ledger |
| T9 | **Stale consent** — grant revoked, deliveries continue | n/a | the grant status is read at delivery time, not cached |
| T10 | **Exchange owner-authority gap** (§2.6) | open, as read | fixed first (slice 0), for datasets as well as signals |
| T11 | **Existence oracle** — 404-vs-403 reveals which Spaces exist | Exchange returns 404 for no such Space | same status for "no such Space" and "not permitted" on the signal kind (D12) |

---

## 4. Options

**Option A — Borrowed caller identity.** Add `targetSpace` to consequences; at `/apply` re-resolve the
caller's roles against B's `roles.toon` and require the capability there. ✅ small. ❌ Undefined for
`on_signal` (no caller, T3), so it covers only the human path; ❌ A decides what happens in B (T2); ❌ opens
exactly the target-space parameter the row warns about. **Rejected.**

**Option B — Installation-scope cross-Space controller.** An admin-authored routing table
(`canAdminister`) mapping *(A, type) → (B, action)*. ✅ one place to audit. ❌ B's owners have no say; one
admin grant spans every Space; ❌ still a Consequence crossing, not a Signal. **Rejected as the default**;
kept as the fallback if D2 is answered "admin only".

**Option C — Target-only intake.** B authors "accept type T from A"; A has no say. ✅ B controls effects.
❌ A's data leaves A with no consent from A (T6). **Rejected.**

**Option D — Two-party Signal grant on the Exchange (recommended).** A **offers** signal types (with a
payload allowlist); B **requests**; A **approves** — each act authorized against its own Space's roles once
slice 0 lands. A forwarder on A's ledger copies matching Signals into B's ledger as new, namespaced Signals.
B's existing `on_signal` Jobs are the consequence. ✅ Both sides consent; ✅ no new consequence engine; ✅
reuses the Share Grant ledger, audit and revoke; ✅ the apply path is untouched. ❌ depends on slice 0;
❌ in-process only until the scale-out phase (D8).

---

## 5. Recommendation — Option D, in detail

1. **Only Signals cross.** Decision Rules keep binding to their own Space. `/apply` refuses a consequence
   carrying a `targetSpace` / `space` param with **422** (fail closed, so an author never believes it
   worked).
2. **Offer (A):** `kind: signal`, `item` = a signal type or `prefix.*`, `payloadKeys: [...]`. Gated by a
   signal-specific offer capability (D7), checked **in A**.
3. **Request (B):** consumer B requests the offer; gated `canRequestShares` **in B**.
4. **Approve / deny / revoke (A):** `canApproveShares` **in A**. This is where slice 0 matters.
5. **Forward:** a subscriber on each Space's ledger (installed by the Exchange module, so absent from Personal)
   matches ACTIVE `signal` grants whose owner is this Space. For each match it builds a **new** Signal:
   fresh `signalId`; `type = exchange.<A>.<type>` (D4); `space = B`; `causationId` = origin `signalId`;
   `correlationId` preserved; `actor` = `exchange-grant:<id>`; `payload` = allowlisted keys only, with
   `chainDepth` = origin depth + 1 written by the forwarder; attributes `originSpace`, `originSignalId`,
   `originActor`. It binds B's MDC, emits on **B's** `EventLog`, restores the MDC, and emits
   `exchange.signal.delivered` or `exchange.signal.undeliverable` (target not hosted, grant not ACTIVE) on A.
6. **Consume (B):** a B Job opts in with `on_signal: exchange.<A>.<type>`. Nothing else in `JobService`
   changes; the namespacing is what keeps a B Job on `fraud.*` from firing on A's `fraud.x`.
7. **Stamp in-Space provenance too:** Decision Rule Signals get `space` and `actor` filled (§2.3) — the
   forwarder's `originActor` is meaningless while the origin leaves it null.
8. **Connector-direct emission:** Collector connectors emit **typed Signals** (today acquire code emits raw
   events via `EventLog.current()`), so connector facts become offerable like any other Signal. Only the
   emitters the first consumer needs (D10).

---

## 6. Slices (ordered; each lands with its own unit tests)

| # | Slice | Module(s) | Done when |
|---|---|---|---|
| 0 | **Exchange owner/consumer authority fix** — owner-side acts (`offer`, `approve`, `deny`, `revoke`, `expiry`) re-resolve the Subject against the **owner** Space's roles; consumer-side (`request`, `pin`) against the **consumer's**. Reproducing test first. | `inspecto-exchange`, `inspecto` | N1, N2 red before, green after; dataset grants covered too |
| 1 | Stamp `space` + `actor` on Decision Rule Signals; 422 on a `targetSpace`-carrying consequence | `inspecto` | N7 + a positive test reading `ATTR_SPACE` |
| 2 | Exchange kind `signal`: offer / request / approve with `payloadKeys`; route + `openapi-v1.json` entry + capability manifest | `inspecto-exchange` | offers listable, grants transition; no delivery yet |
| 3 | Forwarder (§5.5) with MDC binding and delivered/undeliverable Signals | `inspecto-exchange`, `inspecto-event` | N3–N6, N8, N10, N11 |
| 4 | B-side consumption — acceptance test for the concrete consequence named in D10 | `inspecto-engine` | N9 + the D10 end-to-end test (a B Job runs in B, not A) |
| 5 | Connector-direct emission for the emitters D10 needs | `inspecto-acquire` | typed Signal on the Collector's own ledger, offerable |
| 6 | Docs: GLOSSARY §Space amendment (D11) and Exchange family kind; OKF `signal-backbone.md` + `decision-rules.md` as-built; EDITIONS cell; archive this plan | docs | vocabulary + link guards green |

⛔ New mutating routes must clear the route-gating boot check, `CapabilityManifestTest` (literal capability
strings) and the `openapi-v1.json` contract — see `okf/backend/editions/auth-security.md`.

---

## 7. Test plan

**Idiom:** real-HTTP control-plane tests (`inspecto/src/test/java/com/gamma/control/ControlApiMultiSpaceTest.java`,
`ControlApiDecisionRulesTest.java` style) plus `JobServiceTest` for dispatch. 🔴 **Every negative authz
test must attach a Subject** through a test `Authenticator` whose capabilities differ per Space: with no
Subject, `requireCapability` is a no-op (§2.5) and a refusal test passes against an ungated route.
🔴 **Each negative test gets a positive twin** that would succeed with the grant, and a **mutation check**
(remove the gate → the negative test goes red, for the right reason — assert the status *and* that B's
ledger is unchanged).

| # | Negative test | Expect |
|---|---|---|
| N1 | Subject holds `canApproveShares` in `default`/A's-sibling but not in owner A; approves a grant owned by A | 403; grant stays REQUESTED |
| N2 | Subject holds `canRequestShares` in A, not in B; requests a `signal` grant with consumer B | 403 |
| N3 | No grant; A emits an offered type | B's ledger unchanged; A has no `delivered` |
| N4 | Grant revoked; A emits | not delivered; `undeliverable` (grant not ACTIVE) on A |
| N5 | Grant for `fraud.alert`; A emits `fraud.other` | not delivered |
| N6 | Offer allowlists `{caseId}`; payload carries `{caseId, msisdn}` | delivered payload has `caseId` only |
| N7 | Decision Rule consequence with `params.targetSpace: b` → `/apply` | 422; nothing executed in either Space |
| N8 | A↔B loop via two grants; origin payload sets `chainDepth: 0` on every hop | chain cut at `maxChainDepth`; payload value ignored |
| N9 | B Job `on_signal: fraud.*`; A's `fraud.x` delivered | B Job does **not** fire; a B Job on `exchange.a.fraud.*` does |
| N10 | A Signal emitted in B with payload `originSpace: a` | not treated as delivered; no origin attributes |
| N11 | Target B deleted after approval; A emits | `undeliverable` on A; emitter unaffected; no exception |
| N12 | Personal build | no forwarder installed (extend `NoExchangeShipsInThePersonalBuildTest`) |
| N13 | Delivery runs; assert the fired Run's `spaceId` and ledger are B's, and A's MDC is restored after | T7 |
| N14 | Request for a non-existent Space vs a forbidden Space | same status and body shape (D12) |

Run per slice at unit level (`-pl <module> -Dtest=A,B`, commas); the full reactor gate only at hand-off or
before a push touching the Exchange/ControlApi seams.

---

## 8. Decisions owed (operator)

1. **D1 — Only Signals cross; the apply path never gains a target-space parameter.** Recommend **yes** (§5.1).
2. **D2 — Consent model.** Two-party on the Exchange (Option D) vs admin-only controller (Option B).
   Recommend **Option D**.
3. **D3 — Fix the Exchange owner-authority gap (§2.6) first, as its own row, independent of this feature.**
   Recommend **yes, filed P1** — it affects dataset grants today if the reading is confirmed by slice 0's test.
4. **D4 — Delivered type namespace** `exchange.<originSpace>.<type>`. Recommend **yes** (fail closed: no
   existing B Job fires until someone opts in).
5. **D5 — Payload crossing:** allowlist on the offer vs whole payload. Recommend **allowlist**, empty by default.
6. **D6 — Delivered Signal identity:** `actor = exchange-grant:<id>`; origin actor as attribute only, never
   authority. Recommend **yes**.
7. **D7 — Capability verbs:** a new `canOfferSignals` for the offer, reusing `canRequestShares` /
   `canApproveShares`; vs reusing `canOfferDatasets` (misnamed for signals). Recommend **new offer verb**.
8. **D8 — Topology:** v1 in-process, single Pod; a target not hosted here is `undeliverable`, never queued.
   Cross-Pod delivery deferred to the scale-out plan's durable shared event store. Recommend **yes**.
9. **D9 — Editions:** ships wherever the Exchange ships (not Personal). Recommend **yes**; confirm the
   EDITIONS cell.
10. **D10 — Name the consequence that was actually asked for:** which origin Space, which signal type, which
    target Space, which Job. Slice 4's acceptance test and slice 5's connector scope depend on it. **Owed —
    no recommendation possible from code.**
11. **D11 — GLOSSARY §Space amendment** to *"invisible to another, except through a consented Exchange grant."*
    Recommend that wording.
12. **D12 — Existence oracle:** return the same status for "no such Space" and "not permitted" on the
    signal kind (differs from the Exchange's current 404). Recommend **yes** for the signal kind; dataset
    kind unchanged unless D3's row takes it.
