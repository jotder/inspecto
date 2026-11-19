---
type: Capability
title: Alerts & Incidents (INC) — Notifications, decisions and not built
description: Part 4 of 5 of Alerts & Incidents (INC): Decisions; Not built
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
---

# Alerts & Incidents (INC) — Notifications, decisions and not built

Part 4 of 5 of [Alerts & Incidents (INC)](plan-incidents.md) · previous: [Specification — Incidents as operational objects](plan-incidents-operational-objects.md) · next: [Refused, as-built pointers and verification](plan-incidents-refused-verification.md).

## 3. Specification (continued)

### 3.8 Notifications

Shipped 2026-06-29 as an **in-process MVP**, deliberately without a broker: `NotificationService` is an
`EventLog` subscriber handing off to a virtual-thread executor; append-only `EventStore`; feed routes
`/notifications/*` (list / unread-count / read / read-all / delete) and SSE `GET /notifications/stream`;
`NotificationRateLimiter` (rolling per-hour cap on identical notifications — the anti-loop safeguard);
`{{var}}` templates.

- **Channels.** `NotificationChannel` is a `ServiceLoader` SPI; in-app is intrinsic, **email and webhook are
  an edition seam** — `SmtpEmailChannel` (`notify.smtp.{host,from,to,user,pass}`) and `WebhookChannel`
  (`notify.webhook.{url,token}`) live in `inspecto-notify-channels` since EDG-01 cell 1 and **Basic
  registers zero channels**. Persisted destinations are a `channel` `ComponentStore` kind
  (`GET/POST/PUT/DELETE /notifications/channels[/{id}]`, `canAuthorWorkbench`, `kind=EMAIL` with an invalid
  target `422` at CRUD time) read live by dispatch; a `kind` with no discovered transport delivers nothing.
  ⚠ `ChannelConfig` has no `template` field, so a destination cannot override the rule-level template.
- **Rules** (`notification-rule` kind, `/notifications/rules*`, 2026-07-24) override the built-in
  event → notification table; chosen as a `ComponentStore` kind over a boot-scanned TOON, matching the
  channel precedent. `FLOW_CONSERVATION_IMBALANCE` gained a rule (category `ops`, `minLevel=WARN`) because
  the bridge opens an ALERT object for both directions.
- **Digest** is opt-in per destination (`digestMinutes`, 0 = immediate); in-app copies stay per event.
- **Preferences** are **one global set** (`NotificationPreferences`: "per-user preferences arrive with the
  auth module"), category × channel, critical categories locked on. The Notification Center's
  Preferences tab edits that one set.
- **Receipts and suppression.** Inbound delivery-status webhooks (D8, 2026-07-26): `statusAt` is
  `Map<DeliveryStatus, Long>`, first observation wins, verification precedes every write, an unknown
  `deliveryId` is `202`. `DbDeliveryReceiptStore` shipped 2026-09-07 and with it the **suppression
  policy**: complaint ⇒ permanent; hard bounce ⇒ TTL (`-Dnotify.suppression.bounce.ttl`, default `P30D`);
  ⛔ soft bounce **never** suppresses; `-Dnotify.suppression=off`. 🔴 It **arms itself only over a durable
  store** — an evicting cache answering "never bounced" for evidence it forgot must not collapse into
  "deliver". Unsuppress is an **override row** (`DELETE /notifications/suppressions?target=`) forgiving
  history up to its timestamp; a later bounce re-suppresses.
- ✅ **`mail.send` no longer succeeds with nothing sent** (fixed 2026-09-17). `MailSendJob` returns
  `JobResult.skipped("no email channel configured — nothing sent")` — `SKIPPED`, neither green nor a red run
  on every fire. It had returned `ok(...)` and `BACKLOG.md` §6 carried that as working-as-designed with a
  disclosure, which the register's `INC-3` cell rightly called a defect: a scheduled mail job read green
  forever on a bundle with no transport.

### 3.9 Diagnosis

`FailureReactor` (`features/inspecto-agent/src/main/java/com/gamma/agent/diagnose/FailureReactor.java`)
subscribes to the `ConsignmentEventBus` for FAILED consignments and diagnoses them off-thread (bounded
queue + virtual threads) through `HeuristicDiagnoser` and, when a model is configured, `ModelDiagnoser`;
results land in `DiagnosisStore`, a **ring of 256**, and are served read-only by `GET /assist/diagnoses`.
The UI (`/diagnoses`, `DiagnosisDetailDialog`) shows root cause, a heuristic-only flag and a
**`suggestedAlertRuleToon`** the operator can copy. **That is the whole bridge to the chain.** No code in
`com.gamma.agent.diagnose` or `com.gamma.assist` opens an Incident, and no UI affordance launches a
Diagnosis from a failing Run — it is reactive to failure events only. The requirement's "producing an
Incident" is unbuilt (§2, §5).

### 3.10 What Basic loses, and how

`ObjectAccess` (core SPI, `Optional.empty()` on Basic) is provided by `OpsEngineProvider`, which
`inspecto-ops` registers under the **`com.gamma.service.ObjectEngineProvider`** service file — that file, plus
the module's `RouteModule`, `JobTypeProvider` and `MaintenanceTaskProvider` registrations, is the whole switch. Because the objects domain was **constructed by mandatory core**, cell 7
also needed a host-declared `ObjectEngineProvider` handle beside the seam — opening the stores needs
`SpaceRoot` / `OperationalDb` from the module above the engine. With the module absent:
`AbsentModuleRoutes` answers **49 paths `503`** naming the module; `BootstrapRoutes` sets `features.ops` from the
registered features (installed, enabled in the Space and entitled) — derived from what registered, not declared; the SPA drops the
`incidents`, `cases`, `tags` nav ids and the Incidents / Case Manager / Incident-detail panes render an
explained `<inspecto-alert>` ("Operational objects not installed"), never a toast; `DecisionRoutes`
splits its outcome three ways so an absent module is never reported as "Incident already open"
— the defect verification caught. Three ops Job Types leave with the module (§2). ⛔ `/alerts*` stays.

### 3.11 The UI seam

One `ObjectMailComponent` serves `/incidents` and `/cases` by route data — a Gmail-metaphor 3-pane shell
(folders My Incidents — My Cases on `/cases` — / Escalated / Identified / Diagnosing / Resolved / Archived + Tags · list · detail) reading
`GET /workflows/{type}` rather than hard-coding transitions. Triage is **optimistic** (every bulk verb
patches rows to the expected post-state, then reconciles with the server object); merge / split / create are
request → refetch. `/alerts` (`AlertsComponent`) lists fired alerts and Alert Rules with a manual Evaluate
and the `AlertRuleFormDialog`, gated on `LensService.canAuthorAlertRules`. The **Notification Center** has
Channels / Rules / Deliveries / Preferences tabs and the bell reads the SSE stream with polling fallback.
`/diagnoses` is a data table with a detail dialog. ⚠ Neither the Notification Center nor the preferences
pane has an `okf/frontend` feature page (`features/index.md` says "not yet documented"); `alerts.md` and
`diagnoses.md` are stubs under 1 KB.

### 3.12 Public API surface

`@PublicApi(since = "4.0.0")` on: `ObjectType`, `ObjectAccess`, `IncidentAccess`, `AnnotationKinds`,
`FindingsSpec`, `RcaTemplate`, `TagAssignment`, `IdScheme` (core vocabulary, `inspecto-engine`);
`ObjectService`, `ObjectStore`, `DbObjectStore`, `InMemoryObjectStore`, `ObjectQuery`, `OperationalObject`,
`EscalationPolicy` (`inspecto-ops`). **Not** marked: `AlertRule`, `EventAlertBridge`, `OpsEngineProvider`.
⚠ The `@PublicApi` marker records *intent*, not exposure — nothing after 3.x has shipped
(`api-stability.md`). Making the dependent-cascade store methods **abstract** in MNT-14 was a MAJOR widening
of an unreleased surface, and was chosen anyway because a default would orphan rows.

## 4. Decisions

Dated, one line each, with the reason. Only decisions that still bind are listed; where one reversed an
earlier one, both appear.

### The chain and its vocabulary

| Date | Decision | Why |
|---|---|---|
| 2026-06-29 | Notifications ship as an **in-process MVP** — virtual-thread executor + append-only `EventStore`, **no broker** | A broker is a deployment dependency the target user counts do not justify; the idiom is the same one the run-claim seam uses |
| 2026-06-29 | The SaaS-shaped requirement was cut to fit: **one global preference set**, IP + UA not GeoIP, login/MFA events deferred, 401/403 audited as `ACCESS_DENIED` | The core is auth-free; there is no user to prefer per |
| 2026-07-06 (product) | R5 Decision Network: Alert-Rule and Expectation consequences mapped into the **`Consequence`** vocabulary; full kind promotion deferred | One word for "what a decision engine produces" across three rule engines |
| 2026-10-10 (operator) | **Runbook** = a first-class `runbook` component kind, linked guidance with no automation; an Alert Rule links one by `runbook:` (dangling link refused at save) and its Incident/Case page shows it; authored through `/components/runbook` under `canAuthorWorkbench` | Turns the packs' Markdown runbooks into guidance a responder sees on the object, with history, without new routes |
| 2026-07-07 (product) | **Build the Alert-Rule authoring pane**; new capability `canAuthorAlertRules` | Of the three rule engines Alert Rules alone had no UI authoring |
| 2026-07-09 | Alert-rule writes **arm in-process**; the engine never hot-loads `*_alert.toon` | One arming path; a file watcher would be a second one the write route has to agree with |
| 2026-07-12 (product) | `/incidents` + `/cases` become a **mail-like 3-pane UI**; lifecycle renamed **`IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`**; priority `Critical · Major · Minor · Low` | Triage is an inbox; the old `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` encoded assignment as a state |
| 2026-07-12 | `assign` **does not move status**; `/ack` stays alert-only; only `ARCHIVED` is terminal; reopen clears `closedAt` | Ownership and progress are different axes |
| 2026-07-12 | Case membership is **`CONTAINS` links**; `LinkStore` gains `remove()` (an amendment to its append-only contract) | One representation — the graph the UI and glossary already use |
| 2026-07-12 | Merge survivor: the absorbed Case is **`CLOSED` + `mergedInto`**, not a `MERGED` state | A state that means "look elsewhere" is a link wearing a status |
| 2026-07-12 | Open-member gate on Case close is a **soft warning** | Revisit only if the warning proves insufficient |
| 2026-07-19 | **Severity promotion**: `CRITICAL`/`error` rules open a deduped Incident beside the Alert | A high-severity breach must enter triage, not only a feed |
| 2026-07-19 | `create-alert` records a signal **and** opens an object | A consequence that only logs is not actionable |
| 2026-07-22 (product) | **Create contract**: title + **≥ 1 link** mandatory; assignment **direct**, queue routing at creation **deferred** | An unlinked object is not useful; there is no multi-analyst consumer yet |
| 2026-07-24 | **Resolution is hard-gated server-side** (I1): `commit()` rejects `→ RESOLVED` without the four postmortem sections | A UI-only check is bypassed by the API |
| 2026-07-24 | `create-incident` consequence: an Incident at any severity, deduped per rule | Some conditions warrant triage without being alerts |
| 2026-07-24 | Notification **rules** are a `ComponentStore` kind, not a boot-scanned TOON | Matches the `channel` precedent; the kind inherits CRUD, ETags and history for free |
| 2026-07-24 | Digest is **opt-in per destination**; in-app copies stay per event | Batching is a delivery choice, not a fact about the event |
| 2026-07-25 | **Retention is a tier** (D5): `CLOSED → ARCHIVED` and `ARCHIVED → purge` are two windows | "Archived" must not mean "about to be deleted" |
| 2026-07-26 | **D7**: tags become a generic cross-entity concept — *this* system generalized, not a second one; `attributes.tags` becomes a projection | Two tag systems would drift; the old row's claim that nothing wrote `attributes.tags` was simply false |
| 2026-10-06 (operator) | **A Case's Findings Impact field is built in and IS its typed Impact** (`FINDINGS-EDITOR-PER-CASE-TYPE-1`, option (c) of (a) keep separate / (b) mark any Number field / (c) built-in): one locked `impact` section (label renameable) on every Case form, valued as `Impact.confirmed`, written through the impact path and never stored in the blob | A lead's "Confirmed loss" as a free Number field was a second home for the Case's money that analytics never summed; a marked field (b) could be retyped or removed under the money. `confirmed` is the amount a Findings "loss" names; currency stays on the Impact panel (operator, 2026-10-06) |
| 2026-07-26 | **D6 / C3**: Findings sections are a `findings-spec` kind that **fully replaces** the default; values validated `422`; the gate lives in `ObjectRoutes` | Field-level merge makes "remove a section" inexpressible; the engine stays store-agnostic |
| 2026-10-06 | **FINDINGS-PER-CASE-TYPE-1**: per-Case-type Findings spec as component `case.<caseType>`, optional, falls back to the generic spec (operator, 2026-10-06) | A SIM-box form had no home; keying on the existing `caseType` attribute adds no new concept and needs no migration |
| 2026-07-26 | D8 receipts: **first observation wins**; verification precedes every write; unknown id `202` | A provider retries; a later duplicate must not rewrite history |
| 2026-07-27 | **MNT-14 `incident_purge`**: `retention_days` required, derived not stamped, legal hold fail-safe, dependents first through **abstract** methods, `INCIDENT` only, **never scheduled by default** | A shipped default that hard-deletes business records is indefensible; a silent no-op default would orphan rows |
| 2026-07-27 | **G3**: a purge keeps the Incident's ledger trace, including the purge record | The ledger is append-only; that is the compliance claim |
| 2026-08-10 | The promoted Incident is **linked `ESCALATED_FROM`** its Alert | The correlation must be traversable in the graph, not implied by shared attributes |
| 2026-08-10 | An `alerts` Platform Service so `alert.evaluate` holds a real grant; dry-run must report "nothing evaluated" | A decorative grant hides a job that silently did nothing |
| 2026-08-15 | The `AttributeSpec` vocabulary is pinned by a **cross-language contract test** | A type added only in TypeScript made the server `422` a section the renderer could draw |
| 2026-09-06 | **Alert Rules widened to Measures** (BI-5): one record, two shapes; a thresholded Measure is a KPI alert | One rule kind for both senses of "a number crossed a line" |
| 2026-09-06 | Bounce/complaint handling **stays manual until receipts persist**; no auto-disable of a channel | One bad address must not silence a channel |
| 2026-09-07 | Suppression: complaint permanent, hard bounce TTL, **soft bounce never**, armed **only over a durable store**; unsuppress is an override row | An evicting cache cannot distinguish "clean" from "forgot"; pruning receipts would mask a dead address |
| 2026-09-26 (operator) | **Working an Incident or Case is its own capability, `canWorkIncidents`**: `ack` / `resolve` / `transition` / `assign` moved from `canAdminister` (2026-09-15, `ROUTE-UNGATED-DEFAULT-1` step 2b) to it, seeded to `operations`, `support`, `power` and `admin` (`super` holds everything). Merge / split / `PATCH /objects/{id}` / Case-Rule evaluate **stay `canAdminister`**. Those four routes record the authenticated Subject as the actor; a body `actor` counts only without one (Basic). The SPA shows the lifecycle verbs only with `LensService.canWorkIncidents()` (action node `incidents.work`), and Accept self-assigns through `/assign`, not the PATCH | Only Admin/Super held `canAdminister`, so a fraud analyst on `operations` could not close their own Case and `power` could not work an Incident at all. Moving an object's state is the analyst's daily work; reshaping which Incidents a Case holds, or editing its fields wholesale, is not. Pinned by `ControlApiTriageGateTest` (seeded-role Subjects: 401 / 403 `business` / 200 `operations`, and a Case OPEN → investigate → resolve with Disposition CONFIRMED audited under the Subject) |
| 2026-09-26 (operator) | **Narrow postmortem + category routes on `canWorkIncidents`** (`INCIDENT-FINISH-GATE-1`, "narrow route"): `PUT /objects/{id}/postmortem` (body `{postmortem:{…}}` → `attributes.postmortem`, the JSON blob the I1 resolution gate reads) and `PUT /objects/{id}/category` (body `{category:"…"}` → `attributes.category`). Each writes ONLY its one key, refuses any other with 422 (400 for a missing/malformed value), runs behind the scope guard, and is audited as an `OBJECT_ACTIVITY` event (`action` = the key) under `ApiContext.actor`. The postmortem panel and Accept's categorise step call them; priority / severity / assignee / tags / escalate / team / target date stay on the `canAdminister` PATCH | Resolving needs the postmortem and Accept needs a category, so without these an analyst could move an Incident but not finish it. Following the `PUT /objects/{id}/findings` precedent keeps the PATCH from becoming the analyst's catch-all. Pinned by `ControlApiIncidentFinishGateTest` (401 / 403 `business` / 200 `operations`, a foreign key → 422, and IDENTIFIED → DIAGNOSING → RESOLVED with a postmortem by an `operations` Subject) |
| 2026-09-26 | **The SPA shows each disposition control only with the capability its route takes** (`CASE-UI-GATE-LEFTOVERS-1`): Priority, an Incident's Escalate flag, Merge, Split and the Case team / target-date fields render only with `LensService.canAdminister()`. **A comment's `author` is the signed-in Subject** on `POST /objects/{id}/comments` and `POST /notes/{kind}/{id}/comments`; the body's `author` counts only with no Subject (Basic) | A control the server refuses is a dead button; a body field must not re-attribute a Subject's words, as it may not re-attribute a lifecycle move. ⚠ Tagging still rides the PATCH and still shows to everyone — not in this call |
| 2026-09-26 | **Typed impact on Incident and Case, one home** (WS-10, `ASSURE-IMPACT-LEDGER-1`): `attributes.impact` via `PUT /objects/{id}/impact` on `canWorkIncidents`; `outstanding` derived on read; the Findings `impactAmount` section and flat copy retired; a terminal object's impact is closed (409) | A narrow PUT follows the postmortem / category / findings precedent, where extending the PATCH would hand the analyst `canAdminister`'s fields. A stored `outstanding` drifts the first time `recovered` changes without it. Two homes for a Case's money (Findings `impactAmount` + the typed block) would disagree the day one is edited. Freezing a terminal object keeps a closed ledger closed — **amended the same day**: late recoveries (`recovered` / `prevented` only) stay writable on a RESOLVED Incident and a CLOSED Case, because money comes back after the outcome is decided; an ARCHIVED Incident stays fully closed. The PATCH refuses `impact` and `disposition`, and a reopen clears the Disposition |
| 2026-09-26 | **An Incident resolves only with a Disposition** (WS-10): the ladder gains `DUPLICATE` and `ACCEPTED_RISK` (one list, `FindingsSpec.DISPOSITIONS`, shared by Cases' Findings); the value rides the resolve body and is gated in `ObjectService.commit` beside I1 | Resolved-with-no-outcome Incidents make every found / recovered / prevented KPI unreadable. Gated in `commit` so no route or caller can bypass it, as I1 is. Case stays soft (§6.2) — not in scope of the call |
| 2026-09-29 (operator, `IMPORT-RESIDUALS-1` (5)) | **Archiving an undecided Incident stamps `ARCHIVED_UNDECIDED`**: `ObjectService.commit` sets it when an Incident moves to ARCHIVED with no Disposition, and records it as `disposition` on that move's `OBJECT_ACTIVITY` event; one archived WITH a Disposition keeps it; a reopen clears it as any reopen does. It is on `FindingsSpec.DISPOSITIONS` (and the SPA's `incident-disposition.ts`) but never chosen — `CHOOSABLE_DISPOSITIONS` excludes it, so a resolve sending it is 422 and a Case's Findings do not offer it. Archive stays ungated otherwise (still the Trash) | An archived Incident with a blank outcome was unreadable in every found / prevented KPI and indistinguishable from a data gap; stamping keeps archive a one-click dismissal while making "nobody decided" an explicit, countable value. The analytics ledger reads the stored value, so it needed no change. Pinned by `ObjectServiceTest.archivingAnUndecidedIncidentStampsArchivedUndecided` / `archivingAResolvedIncidentKeepsItsDisposition` / `aResolveCannotChooseArchivedUndecided`, `ControlApiIncidentFinishGateTest` (HTTP 422) and `resolve.dialog.spec.ts` |
| 2026-09-28 (session, `ASSURE-IMPACT-LEDGER-RESIDUALS-1`) | **Impact amounts are decimal STRINGS on the wire** — `Impact.toMap()` emits `toPlainString()` (`"1000"`, never `1E+3`), so `GET /objects/{id}` and every read view carry `"confirmed":"123456789012345.123456"`; the `byCurrency` analytics totals stay numbers (read-only, never sent back) | The browser pass found the SPA parsing amounts into a `double` (~15 significant digits; an amount holds up to 21): an edit then rewrote the money, and on a RESOLVED Incident the untouched `confirmed` came back rounded, so the server 409'd every late recovery as a changed field. Breaking changes are free before any production release. Pinned by `ControlApiImpactTest.amountsReadBackAsExactStringsSoALateRecoveryCanResendThem` and `impact-panel.component.spec.ts` |
| 2026-09-28 (session) | **The SPA mirrors the I1 hard gate instead of offering "resolve anyway?"** — the mail view leaves an Incident with an incomplete resolution pattern out of a resolve and names what is missing; the detail page says so before opening the dialog. The detail page takes its actions from `GET /workflows/{type}` (fallback: the server's built-in lifecycles) | The confirm offered a move the server always refuses; the comment was posted, then the resolve failed. The detail page's hand-kept INCIDENT table named states the workflow does not have (OPEN/ASSIGNED/IN_PROGRESS), so it offered no action — Resolve included — on any Incident |
| 2026-09-28 (session) | **A retired flat `impactAmount` is not migrated** (`ASSURE-IMPACT-LEDGER-RESIDUALS-1` (3)) — closed | Nothing after 3.x shipped; no committed seed, fixture or tool writes one (grepped every `*.toon`, `*.json`, `*.py`, `*.mjs`, `*.ps1`, `*.sql` under `spaces/`, `tools/`); a legacy object's money is re-entered through the impact panel |

### Editions

| Date | Decision | Why |
|---|---|---|
| 2026-09-02 | **CP-12 split**: the in-app feed stays in every edition; delivery channels are CP-15 (Standard+) | The feed is the product's nervous system; a transport is a deployment |
| 2026-09-07 (operator) | Gating is **`ServiceLoader` modules**, never `-D` switches | A switch leaves the code in the bundle |
| 2026-09-07 | Cell 1: channels move to `inspecto-notify-channels`; Basic registers **zero** | `SmtpEmailChannel` had reached Basic through the connector sidecar, which is not edition-gated |
| 2026-09-08 (operator) | Cell 7: the **whole `com.gamma.ops` domain** becomes `inspecto-ops`; `OPS-01` and `SP-CTL-02` amended; ⛔ `/alerts*` stays | Operational objects are a multi-operator workflow; Basic is single-user. A gap still raises the *event* everywhere |
| 2026-09-08 | `features.ops` is **derived** from whether `POST /objects` registered | The flag cannot disagree with the build (contrast `authMode`, `SEC` §2) |

## 5. Not built

⛔ **Pointers, never copies.** Each row names its board id; the board is the authority for status and
priority. A row with no id is flagged `UNTRACKED` and needs filing before it can be scheduled.

### Tracked

| Item | Board id | What remains |
|---|---|---|
| D8 notification residuals — soft-bounce retry scheduling, an SES/SNS adapter, GeoIP, auth-gated per-user preferences | `BACKLOG.md` §3 *Notifications (D8 residuals)*; §3 *D8-SUPPRESS-1* | The SNS adapter needs its own review (subscription confirmation + outbound cert fetch from an unauthenticated callback) |
| `findings-spec` authoring UI | `BACKLOG.md` §3 *D6 spec-authoring UI* | Authored as TOON through generic `/components` CRUD today; nothing is broken without it |
| ~~`kpi.completeness` Job Type — signal + deduped Incident on breach~~ | `BACKLOG.md` §3.5 *Completeness KPI* K4 | ✅ Shipped 2026-10-06 (`c16c9eab0`): one Incident per Pipeline, deduped on `pipeline` |
| `TriageRunStore` interface + Postgres implementation; the 256-cap ring blocks AGT-5 embedding recall | `BACKLOG.md` §3 *Postgres multi-user*; §6 | Parked with the multi-user plan |
| MNT-14 residuals — no UI, no shipped Job instance, retention derived not stamped, `INCIDENT` only | `BACKLOG.md` §6 *MNT-14* | Standing; the operator opts in |
| Digest deliveries correlate to the digest, not per notification | `BACKLOG.md` §6 | `deliverWithReceipt` is the escape hatch |
| D7 startup backfill is a full object scan | `BACKLOG.md` §6 | Deliberately unfixed: nothing measures startup |
| ~~`mail.send` succeeds with nothing sent~~ ✅ **FIXED 2026-09-17 — see §above; it returns SKIPPED** | `BACKLOG.md` §6 (was: working-as-designed, disclosed) | ⚠ Kept for the trail: `REQUIREMENTS.md` INC-3 called it a defect while §6 called it by-design, and the disagreement was about its *category*. `ad29e683` settled it by changing the behaviour, so the row is spent, not adjudicated |

### `UNTRACKED` — surfaced by this spec, no board row

> ✅ **Filed 2026-09-09 (Sprint 2).** These findings are no longer untracked. The **cross-cutting** ones
> — those no single area owned, which is why they sat here — are filed as cross-cutting
> `docs/BACKLOG.md` rows. ⚠ The list below is matched **by family, not per item**, so treat it as a
> starting point and read the row before acting on it:
> `SPEC-GLOSSARY-1`.
>
> ⚠ **The remainder stay here deliberately, and that is their correct home.** A finding that is
> area-specific, is *design* rather than a defect, and is recorded in the owning spec's §5 is already filed —
> copying it onto the board would give it two homes and one of them would go stale. The board holds what
> **crosses** areas; a spec holds what belongs to **one**. See
> [`archived-documents/plans-archive/post-consolidation-sprints.md`](../../../../archived-documents/plans-archive/post-consolidation-sprints.md) §Sprint 2.

| Item | Evidence | Why it matters |
|---|---|---|
| **Diagnosis → Incident** — the defining clause of `INC-5` | no `INCIDENT` / `IncidentAccess` reference under `com.gamma.agent.diagnose` or `com.gamma.assist` | A Should recorded shipped; the only bridge is a copyable drafted Alert Rule. Needs a product call: auto-open (deduped per consignment, through `IncidentAccess`) or retitle the requirement |
| ~~**Queue / watcher / escalation-policy UI**~~ ✅ **CLOSED by deletion 2026-09-14** | `RETIRE-HALVES-1` | The gap was a backend with no user half. Retiring the backend closes it — the alternative was building a UI for a capability nobody asked for |
| ~~**`QueueStore` Db parity**~~ ✅ **MOOT 2026-09-14** | `RETIRE-HALVES-1` | There is no `QueueStore` to give Db parity to |
| ~~**An analyst can MOVE an Incident but not always COMPLETE one**~~ ✅ **FIXED 2026-09-26 — see §4, narrow postmortem + category routes** | `INCIDENT → RESOLVED` needs the four postmortem sections (`ObjectService.commit`); the panel and Accept's categorize step used to save through the `canAdminister` PATCH | Kept for the trail: they now ride `PUT /objects/{id}/postmortem` and `PUT /objects/{id}/category` on `canWorkIncidents` (operator, 2026-09-26) |
| **Measure rules in the authoring form** | `AlertRule` has `dataset` + `measure`; `ALERT_RULE_ATTRIBUTES` has neither | The 2026-09-06 widening is TOON-only; the pane cannot express the glossary's own definition |
| **`JOB-01` / `JOB-03` promise Basic three ops jobs** | `OpsJobTypes`, `OpsMaintenanceTasks` in `inspecto-ops` | Corrected in `EDITIONS.md` with this spec (notes on both rows) — recorded here because it is the cell-7 miss class, and the next gating cell will hit it again |
| **`ChannelConfig.template`** — a destination cannot override the rule-level template | `events-metrics.md` "still open"; the plan it rode is archived | The slice's home (`event-signal-backbone-plan` S2) is in the archive and no row survived it |
| **Studio-Dataset binding of `objects.analytics`** | `case-analytics-dataset-plan.md` is DRAFT, no code, uncited | The Parquet exists; nothing reads it as a Dataset |
| **Notification Center / preferences have no `okf/frontend` page**; `alerts.md` and `diagnoses.md` are stubs | `features/index.md` "not yet documented" | The UI half of INC has less concept coverage than any other area so far |
