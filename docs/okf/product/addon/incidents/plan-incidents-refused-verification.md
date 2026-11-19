---
type: Capability
title: Alerts & Incidents (INC) — Refused, as-built pointers and verification
description: Part 5 of 5 of Alerts & Incidents (INC): Refused & superseded; As-built mechanism (pointers only); Verification
tags: [inc, capability, alerts, alert-rules, incidents, cases, queues, escalation, sla, notifications, diagnosis, findings, retention]
timestamp: 2026-09-08T00:00:00Z
---

# Alerts & Incidents (INC) — Refused, as-built pointers and verification

Part 5 of 5 of [Alerts & Incidents (INC)](plan-incidents.md) · previous: [Notifications, decisions and not built](plan-incidents-notifications-decisions.md).

## 6. Refused & superseded

**This section exists because a refused idea with no recorded refusal gets re-proposed.** `BACKLOG.md` §6
does this for *work*; this does it for *design*, per capability. Each row states what was refused and the
reason — the reason is the load-bearing half.

### 6.1 Assignment as a state; membership as a field — REFUSED (2026-07-12)

The old lifecycle carried `ASSIGNED` and `IN_PROGRESS`; the mail redesign removed both. Ownership
(`assignee`) and progress (status) are different axes, and encoding one in the other made "reassign" a
state transition. Likewise Case membership as `attributes.caseId` was refused: it would create a second
representation beside the `CONTAINS` links the glossary, the UI and the correlation graph already use.

### 6.2 Hard gates where a soft warning was chosen — and the one place the reverse happened

Case close with open members, and Case resolve without a Disposition, are **soft warnings** by decision
("revisit only if insufficient"). Incident resolution is the deliberate exception: I1 made the four-section
postmortem a **server-side `422`**, because a UI-only check is bypassed by the API, and WS-10 (2026-09-26)
added the Disposition to the same gate — every money KPI reads a resolved Incident's outcome, so an Incident
with none cannot count as resolved. The Case's Disposition stays soft: it was not part of that call. ⚠ Two current docs
(`objects.md`, the archived `case-management-design.md`) still describe the backend gate as a follow-up;
`PROJECT_NOTES.md` and `ObjectService.commit` are right.

### 6.3 A message broker; email in core — REFUSED (2026-06-29)

Redis / RabbitMQ / Kafka were considered and refused: the in-process virtual-thread executor over an
append-only `EventStore` is the house idiom and the target user counts do not justify a broker. Email is an
**edition seam**, never core — a transport is a deployment concern and Basic ships none.

### 6.4 Auto-disable on bounce; collapsing hard and soft bounce; pruning receipts to unsuppress — REFUSED (2026-09-06/07)

One bad address must not silence a channel, so suppression is **per recipient**. Soft bounce never
suppresses — collapsing it with hard bounce would stop mail to anyone once over quota. "Unsuppress by
deleting the receipts" was rejected twice over: it is audit loss, and a permanent mask over a genuinely dead
address; the override row forgives history up to a timestamp and lets a later bounce re-suppress.

### 6.5 Findings: field-level merge, the `*_workflow.toon` pattern, `ConfigSpecs` reuse, `group`/`secret` — REFUSED (2026-07-26)

A present `findings-spec` **replaces** the default whole because merge makes "remove a section" inexpressible.
Cloning the C6 `*_workflow.toon` pattern was not viable — it is a boot-time scan of CLI path arguments with no
write root, no CRUD and no hot reload, a config surface an operator cannot edit through the product. Mapping
`AttributeSpec` onto backend `ConfigSpecs` / `FieldSpec` was lossy for zero reuse. `group` and `secret` are
frontend-only keys and a `422` on a findings-spec by design — `secret` on a stored-and-returned attribute
would mislead.

### 6.6 `incident_purge` defaults — REFUSED (2026-07-27)

No default `retention_days`; no scheduled instance; no silent `default` on the dependent-cascade store
methods (shipped **abstract**, a MAJOR widening, because a default orphans rows quietly); no per-store hooks
beside `JobService.objects()` (four independently-nullable fields would reintroduce the half-cascade). A
purge is not "all trace removed" (G3).

### 6.7 Receipt design refusals (D8, 2026-07-26)

No provider-minted delivery id (the id is minted locally and embedded outbound, so the `@PublicApi`
signature did not widen); no signature verification against a re-serialised `Map` (key order and whitespace
do not survive); not the Ed25519 SendGrid verifier the plan specified — it is ECDSA P-256 / SHA-256.

### 6.8 Case-management non-goals (2026-07-12)

No true file upload for Case documents (references only — upload touches the air-gap and attack-surface
posture and needs its own design); no auto-merge "similar cases" scoring (Case Rules are the hook); no
moving comments or attachments between Cases on split (history stays where it happened); Cases do not get
postmortems and Incidents do not get Contents, split/merge or a Team.

### 6.9 `-D` switches for edition gating; scheduling `incident_purge` — REFUSED (operator, 2026-09-07 / 2026-07-27)

Recorded here because both are INC consequences: a switch leaves the objects domain in the Basic bundle,
and a default purge deletes business records.

### 6.10 Superseded designs — what replaced them

| Superseded | By | Where recorded |
|---|---|---|
| `OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED` Incident lifecycle; *Issue* | `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED`; *Incident* (2026-07-12) | `GLOSSARY.md` §9, §13 |
| The list-and-detail `objects.component` | the 3-pane `ObjectMailComponent` (the old component was deleted) | `objects.md` |
| `fiveWhys[5]` | variable-length `causeAnalysis[]` + `causeMethod`; legacy migrates on parse (I2) | archived `case-management-design.md` |
| `CASE_DISPOSITIONS` in `mail-model.ts` | the backend default ladder + `findings-spec` | `objects.md` |
| `attributes.tags` as storage | a projection of the central assignment store (D7) | `tags.md` |
| "The engine hot-loads `*_alert.toon`" | in-process arming by the write routes | `events-metrics.md`, the archived authoring plan's own correction |
| Alert Rules as raw files only | `ComponentStore`-backed CRUD (the `alert-rule` kind) | `decision-rules.md` |
| "Scheduler auto-evaluation of Case Rules is a follow-up" | the `caserule.evaluate` Job Type | `jobs.md` |
| "The backend resolution gate is a follow-up" | I1 shipped 2026-07-24 (`ObjectService.commit`) | `PROJECT_NOTES.md` §3 |
| MNT-14 plan §3 G1 / G4 framing | marked wrong in the plan's own header; `jobs.md` is the account | archived `mnt-14-incident-retention-plan.md` |
| D6's two premises ("reuse the C6 workflow/TOON pattern", "the `attribute-spec` renderer" = `ConfigSpecs`) | the `findings-spec` kind and the frontend `AttributeSpec` vocabulary | `objects.md` |
| `DecisionConsequence` | `Consequence` (aliased for back-compat) | archived `decision-network-plan.md` |
| The operations reference's curl walkthrough (`type=ISSUE`, `assign` → `start`, `-Dcontrol.token`, now removed) | rewritten with this spec: `INCIDENT`, `accept` → `resolve`, no token plane | `operations-reference.md` |
| `USER_GUIDE.md`'s "open → in-progress → resolved" and "list-and-detail" | corrected with this spec | `USER_GUIDE.md` |
| `STAKEHOLDER_OVERVIEW.md`'s "Issue & case management … open → assigned → in-progress → resolved → closed", "(cases/issues)", unconditional Basic availability | plan §8 cluster — reconciled at step 6, not here | — |

## 7. As-built mechanism (pointers only)

| Mechanism | Owning file | `resource:` | Read it for |
|---|---|---|---|
| Alert-rule authoring, severity promotion, the `ESCALATED_FROM` edge, notifications end to end (channels, rules, digest, receipts, suppression) | `docs/okf/product/module/event/spec-events-metrics.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | the long-form account §3.2, §3.3 and §3.8 were distilled from |
| `caserule.evaluate`, `objects.analytics`, `mail.send`, **`incident_purge`** (D5 tiers, G3, the abstract cascade) | `docs/okf/product/module/processor/spec-jobs.md` (`Concept`) | `platform/inspecto-engine/src/main/java/com/gamma/job` | the retention account and the Job Types |
| `create-alert` / `create-incident` consequences; Alert Rule `when` | `docs/okf/product/module/processor/spec-decision-rules.md` (`Concept`) | `inspecto/src/main/java/com/gamma/control` | how a Decision Rule opens an object |
| Tags as a cross-entity graph; the Incident/Case adopter; Tag Rules | `docs/okf/product/module/processor/spec-tags.md` (`Concept`) | `features/inspecto-ops/src/main/java/com/gamma/ops/tag` | addressing, the central store, the projection |
| `inspecto_ops_objects`, durable vs in-memory stores, **"queues: none"**, `DbDeliveryReceiptStore` | `docs/okf/product/module/engine/spec-db-layer.md` (`Concept`) | `inspecto-engine` | what persists |
| Edition gating as a mechanism; the cell-7 recipe items 18–27 | `docs/okf/product/layer/platform/architecture-editions.md` (`Concept`) | `pom.xml` | `ObjectAccess` + `ObjectEngineProvider`, the census traps |
| The SLA sweep flags and the lifecycle walkthrough | `docs/okf/product/layer/platform/admin-guide-operations-reference.md` (`Reference`) §Retention · §SLA | — | the operator's curl sequence (rewritten with this spec) |
| The Incidents & Cases UI: mail shell, create contract, optimistic triage, Findings spec, D7 | `docs/okf/product/module/ops/user-guide-objects.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/objects` | the only page titled for the domain |
| Alerts pane · Diagnoses pane · Events pane | `docs/okf/product/addon/incidents/user-guide-alerts.md` · `diagnoses.md` · `events.md` (`Feature`) | `inspecto-ui/src/app/modules/admin/{alerts,diagnoses}` | ⚠ the first two are stubs under 1 KB |
| 🔴 **The operational-objects backend has no concept file.** `ObjectService`, the three workflows, categorization, the I1 gate, the SLA sweep, `EscalationPolicy`, queues and watchers, merge/split, Case Rules, `IncidentAccess`, `EventObjectBridge` are documented only in source, in eight scattered backend pages, and in this spec | *(none — gap; the plan predicted it: "no dedicated backend concept file exists anywhere")* | `features/inspecto-ops/src/main/java/com/gamma/ops` | the code. The `control-plane/index.md` enumeration has no Incident / Case / Objects entry at all |
| 🔴 **Notification Center and preferences have no feature page** | *(none — gap)* | `inspecto-ui/src/app/modules/admin/notification-center` | the code |

---

## 8. Verification

### 8.1 The objects domain — `features/inspecto-ops/src/test/` (39 files; runs only under `-Pedition-standard|enterprise`)

⚠ **The default `mvn -o clean test` does not compile `inspecto-ops`.** A green default build proves nothing
about the rows below; Standard is 31 modules / 4106 tests, Advanced 32 / 4126 at HEAD.

| Class | Proves |
|---|---|
| `ObjectServiceTest` · `ObjectCoreTest` · `WorkflowTest` | the three workflows, `reopen`, `ARCHIVED` terminal, the I1 resolution gate, categorization |
| `ObjectServiceQueueTest` · `ControlApiQueueRoutesTest` | `QueueRouter` routing, assign / watch / watchers routes |
| `ObjectServiceCaseGroupTest` · `ControlApiCaseGroupTest` | Contents, merge (`MERGED_INTO`), split (`SPLIT_FROM`) |
| `ObjectServiceCaseRuleTest` · `ControlApiCaseRuleTest` · `CaseRuleEvalJobTest` | Case Rules: threshold + window, open-or-attach idempotence, the Job Type |
| `EventAlertBridgeTest` (`inspecto-engine`) · `ControlApiAlertFromEventTest` | `SEQUENCE_GAP` / conservation → a stored Alert, listed on `GET /alerts`, no ops module; a gap also opens a linked Incident when the incidents service exists (`aGapAlsoOpensOneLinkedIncident…`) |
| `IncidentAccessTest` | the narrowed seam, dry-run opens nothing |
| `AlertServicePersistenceTest` | fired alerts persisted as objects when the module is present |
| `ControlApiFindingsSpecTest` | `GET /findings/{type}`, the `422` value gate, frontend-only keys refused |
| `ControlApiFindingsImpactFieldTest` | The built-in Case Findings Impact field: 422 on a dropped / re-keyed / retyped / re-tiered / required spec (generic and per-type), a relabel accepted; a Findings save writes `Impact.confirmed` (rest kept, never in the blob, audited) and `objects/analytics` sums it; 403 without `canWorkIncidents` on a change, 200 on an unchanged re-send; 422 with no currency or a negative amount; the PATCH refuses it |
| `ControlApiImpactTest` | `PUT /objects/{id}/impact`: 401 / 403 `business` (before existence-hiding) / 400 / 422 (ten invalid bodies, an Alert) / 409 `ARCHIVED` then writable after `reopen` / 404; `outstanding` derived and never stored; audit `before`/`after` under the Subject; per-currency analytics. The Basic 503 is `NoOperationalObjectsShipInThePersonalBuildTest` |
| `ControlApiObjectsByEntityTest` | `GET /objects/by-entity`: 401, 422 per bad input, empty result, every `matchedOn` path, token-boundary columns, unknown prefixes, status filter, scope hiding, the truncation flag, 429 |
| `FindByAttributeFragmentParityTest` | in-memory and DuckDB stores scan the same newest-first capped window |
| `ControlApiObjectsTest` · `ControlApiObjectsPageTest` · `ControlApiScopedObjectsTest` | the create contract (≥1 link, `400`/`404`), paging, SEC-7d data scopes |
| `ControlApiNoteRoutesTest` · `ControlApiTagRoutesTest` · `TagRuleTest` · `ObjectTagProjectionTest` · `TagAssignmentCoreTest` | Annotations: notes, tags as a projection, Tag Rules |
| `IncidentPurgeTaskTest` · `RetentionSweepSeamTest` | `retention_days` required, dry-run, legal hold, dependents first |
| `ObjectsAnalyticsJobTest` | the tall Parquet artifact |
| `DbObjectStoreTest` · `InMemoryObjectStoreTest` · `DbLinkStoreTest` · `LinkCoreTest` · `NoteCoreTest` | store parity — ⚠ no queue-store counterpart exists to test |
| `ControlApiDecisionRulesTest` · `ControlApiExpectationTest` | `create-alert` / `create-incident`; the Expectation → Incident path |
| `WorkflowConfigLoadTest` | `*_workflow.toon` boot-time override |

### 8.2 Alerts, notifications and diagnosis — the default reactor

| Class | Module | Proves |
|---|---|---|
| `AlertRuleTest` | `inspecto-engine` | both record shapes parse; `when` scoping |
| `ControlApiAlertRuleWriteTest` | `inspecto` | the write routes' gate order, in-process arming, `canAuthorAlertRules`; a `by` column outside the Dataset's Schema refused at both save doors; `/components/alert-rule` refuses every shape `/alerts/rules` refuses |
| `PerEntityAlertTest` | `inspecto-engine` | `by` rules over real DuckDB: 40 keys → ONE Alert (count 40, digests, no entity) and 40 Incidents, re-fire → 0, restart seeding from the digests, a healed key leaves the count and the last heal resolves the Alert, never an Incident, storm cap → one Alert + one storm Incident, two rules over a key → two Alerts, the pre-fold per-key Alerts folded, unknown never heals, no-`by` unchanged |
| `MeasureAlertTest` | `inspecto-engine` | scalar Measure rules: fire once per open breach (no re-fire while still breached), the heal edge (all-clear once, cooldown reset so a relapse fires at once), unknown never heals |
| `ScalarMeasureAlertObjectsTest` | `inspecto-ops` | the real workflow for a no-`by` Measure rule: heal resolves the Alert never the Incident, relapse links / re-opens, restart seeding, retire on a Dataset change |
| `PerEntityAlertObjectsTest` | `inspecto-ops` | the real workflow: a heal never resolves the Incident, even with a complete postmortem; a relapse after an operator's resolve re-opens it, an existing Case Rule groups 40 per-key Incidents into one Case |
| `AlertPurgeStoreTest` · `AlertPurgeTaskTest` · `AlertRetentionPinTest` | `inspecto-engine` | `alert_purge`: old resolved deleted, recent / exact-boundary / no-close-time kept, `OPEN` and `ACKNOWLEDGED` never touched, dry run counts only, window 0/blank refused, DuckDB file reopened shows no resurrected row, the live history drops purged entries, the AUDIT row |
| `NotificationServiceTest` | `inspecto-engine` | subscriber hand-off, rate limiter, preferences gating |
| `ControlApiNotificationsTest` · `ControlApiNotificationChannelsTest` · `ControlApiNotificationRulesTest` · `ControlApiNotificationStreamTest` | `inspecto` | feed routes, channel CRUD (`422` on a bad EMAIL target), rules, the SSE stream |
| `ControlApiCollectorNotifyTest` | `inspecto` | the ACQ push-notify seam that feeds the ledger |
| `RcaTemplateTest` · `CollectorServiceRcaTest` | `inspecto-engine` · `inspecto` | RCA templates and their registry |
| `FailureReactorTest` · `ModelDiagnoserTest` | `inspecto-agent` | failure subscription, off-thread diagnosis, the ring |
| `SmtpEmailChannelTest` · `WebhookChannelTest` | `inspecto-notify-channels` | the transports (edition profile only) |
| `NoChannelShipsInThePersonalBuildTest` | `inspecto` | Basic registers zero `NotificationChannel` providers |

### 8.3 UI specs — `inspecto-ui/src/app/` (vitest)

Fifteen specs sit on this mechanism: `object-mail.component.spec.ts`, `object-detail.component.spec.ts`,
`case-contents.component.spec.ts`, `merge-cases.dialog.spec.ts`, `object-create.dialog.spec.ts`,
`object-link.dialog.spec.ts`, `postmortem-panel.component.spec.ts`, `tag.dialog.spec.ts` (the mail pane,
contents, merge, create contract, linking, postmortem, tagging); `alerts.component.spec.ts`,
`alert-rule-form.dialog.spec.ts`; `notification-center.component.spec.ts`, `channel-form.dialog.spec.ts`,
`rule-form.dialog.spec.ts`, `notifications.component.spec.ts` (the Center and the bell);
`diagnoses.component.spec.ts`, `diagnosis-detail.dialog.spec.ts`.

### 8.4 SPI contracts and Job Type registration

| File | Provider |
|---|---|
| `features/inspecto-ops/src/main/resources/META-INF/services/com.gamma.service.ObjectEngineProvider` | `com.gamma.ops.OpsEngineProvider` — the host-declared handle that makes `ObjectAccess` non-empty |
| `features/inspecto-ops/src/main/resources/META-INF/services/com.gamma.spi.http.RouteModule` | `ObjectRoutes`, `NoteRoutes`, `QueueRoutes`, `TagRoutes` — the 42 paths `AbsentModuleRoutes` stands in for (the 7 Case-management paths moved to the optional `inspecto-case-management` module, MODULE-REORG-P7: `CaseRoutes`, feature `cases`; see the reorg plan "P7 Case Management as built") |
| `features/inspecto-ops/src/main/resources/META-INF/services/com.gamma.job.JobTypeProvider` · `com.gamma.job.MaintenanceTaskProvider` | `OpsJobTypes$CaseRuleEvaluate`, `OpsJobTypes$ObjectsAnalytics` · `OpsMaintenanceTasks` (`incident_purge`) |
| `providers/inspecto-notify-channels/src/main/resources/META-INF/services/com.gamma.notify.NotificationChannel` | `SmtpEmailChannel`, `WebhookChannel` |

### 8.5 Runnable examples

Committed config exercising the chain, all under `spaces/demo/config/` (mirrored in `inspecto-deploy/`):
two Alert Rules as `alert-rule` components, armed at boot — `registry/alert-rules/orders_stream_failures.toon`
(metric shape, `CRITICAL` → Incident) and `registry/alert-rules/orders_low_volume.toon` (measure shape; moved off the
dead pre-2026-07-18 `orders/*_alert.toon` files 2026-09-23) — one RCA
template (`ops/orders_rca.toon`). ⛔ `ops/demo_ops_queue.toon` no longer exists — deleted 2026-09-14 with the capability (`RETIRE-HALVES-1`).
⛔ `ops/sla_escalation.toon` no longer exists either, deleted in the same change. ⚠ **Zero committed Case Rules, Tag Rules, notification rules or channel
destinations**, and — by decision — no scheduled `incident_purge`.

### 8.6 Guards

`render-processor-board --check` used to pin `SP-CTL-02`'s Basic cell 🟡 through the `PARTIAL_ON_PERSONAL`
override (the gap event fired everywhere; the ALERT object did not; the set is EMPTY since 2026-10-08, the bridge is core) — the
generator could not express that until 2026-09-08 and its "just regenerate" advice would have flipped an
operator-approved decision back to ✅. `NoChannelShipsInThePersonalBuildTest` and the cell-7 Basic
baseline (23 modules / 3777 tests, `features.ops = false`) are the edition guards in test clothing.

### 8.7 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **No test exercises Diagnosis → Incident** | there is no such path to test (§3.9) |
| **No test that a Basic bundle answers all 49 object paths `503`** | `AbsentModuleRoutes` is exercised by the cell-7 baseline run, not by a route-by-route assertion |
| ~~**`mail.send` on a transport-less bundle has no failing test**~~ ✅ **STALE 2026-09-17** | ~~its success is the recorded behaviour~~ — it returns **SKIPPED** since `ad29e683`, so the recorded behaviour changed and this line no longer describes the code |
| **No end-to-end SMTP or webhook delivery against a real endpoint** | both transports are tested against in-process fakes; delivery-status webhooks against synthetic signatures |
| **No committed Case Rule or Tag Rule** | `*_case_rule.toon` / `*_tag_rule.toon`: zero files (§8.5) |

**Workflow & SLA beyond Incidents (pure decisions extracted 2026-10-09; no second consumer yet).** `Workflow`, `SlaPolicy` and `EscalationRule` are generic, and the pure SLA/escalation decisions now live in `inspecto-workflow` (`SlaDecisions` over a `GovernedItem` view); `ObjectService.sweepIncidentSla` adapts `OperationalObject` to it and keeps the writes, events and the cap. The policies are still keyed by the closed `ObjectType` enum, and no other module consumes them. Extracting a governed-item contract waits for a second provider (reconciliation breaks or Entity List reviews). The declined second-source design and its preconditions: [module-reorganisation-decisions](../../layer/platform/spec-module-reorg-decisions.md).
