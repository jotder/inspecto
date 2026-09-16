# Evidence — route gating (CC6)

⚠ **Framework — an ASSUMPTION, not a confirmed instruction.** This document is written against **SOC 2
Type II**, criteria **CC6.1** (logical access) and **CC6.3** (role-based authorization), because that is
what the route-gating compliance plan assumed throughout. **The operator has not yet confirmed which
framework(s) the evidence is written against** — it is the one item of that plan still owed
(`controls-matrix.md` §4a records it as open). ⛔ **No ISO 27001 A.9 mapping is stated here.** The same
underlying evidence maps onto A.9, but the matrix rows differ, and inventing that mapping before the call
is made would put a claim in an auditor's hands that nobody decided. If the answer is ISO — or both — this
section and the criterion references above are what change; the control, the enforcement points and the
table below do not.

**Control:** a mutating HTTP route (`POST` / `PUT` / `PATCH` / `DELETE`) on the control plane must declare
its posture: either it demands a **capability**, or it is a **recorded exemption** carrying a category and a
reason. A route that declares neither **fails the server's boot**.

**Why this is a control and not a review.** "We reviewed the routes" is true at a point in time and decays
from the next commit onward — the audit that opened this work found **83 ungated mutating routes**, and, the
finding that mattered, that only 12 of them could be expressed with the capability vocabulary that existed.
"An undeclared mutating route cannot exist in a running server" is a different kind of claim: it is a
property an auditor can test by trying to add one. That is what is enforced here.

⛔ **There is deliberately no warn-only switch.** A control with an off switch is not a control. CI catches
an undeclared route before it can refuse a boot in a customer's bundle; the boot check is what makes the
guarantee true of the deployed artifact rather than of the repository.

**Scope.** Standard and Enterprise. ⚠ Personal is auth-free by design and enforces no capability at all —
access-control claims do not apply to it, and this document does not make any.

## Reads are open, by policy

Read routes (`GET`) are **not** capability-gated on any edition, and the boot check does not cover them.
This is a stated decision, not an omission: confidentiality is enforced at the **Space / ABAC** layer, where
a caller's data scopes decide what a read can see, rather than at route gating. Affirmed as the compliance
position (operator, 2026-09-16) knowing it becomes a claim an auditor holds us to. ⚠ If reads are ever
gated, this section and the CC6 row in `controls-matrix.md` move with the code.

## How this is enforced, in three places

| Mechanism | What it catches | Where |
|---|---|---|
| Boot refusal | an undeclared mutating route in **what was deployed**, including optional modules discovered from other jars | `inspecto/src/main/java/com/gamma/control/ControlApi.java` (`requireDeclaredPosture`) |
| CI scan | an undeclared mutating route in **any module's source**, before it can refuse anyone's boot | `CapabilityManifestTest`, plus `tools/route-gating-report.mjs --check` |
| Runtime inventory | what a **specific running server** actually registered, with a digest recorded once per boot | `GET /audit/route-inventory`; the `route.inventory.snapshot` audit event |

⚠ The scan and the runtime inventory answer **different questions** and neither replaces the other: the test
classpath does not carry the optional modules, so only the scan sees all source; only the server knows what
it deployed. The evidence cites both deliberately.

## Verifying this control

1. **Try to add one.** Register a mutating route with no declaration — `api.post("/x", h)` — in any route
   class. `CapabilityManifestTest` fails; if forced past it, `ControlApi` construction throws, naming the
   route and both ways to declare it. Pinned by `UndeclaredMutatingRouteTest`.
2. **Ask the server.** `GET /audit/route-inventory` returns every route with its posture, the counts, and a
   digest over the sorted table.
3. **Compare boots.** The `route.inventory.snapshot` audit event records that digest once per boot; a digest
   that changes between boots means the route surface moved.

## The mutating-route inventory

🔴 **This table is GENERATED — never hand-typed.** `tools/route-gating-report.mjs` derives it from the same
source scan CI runs, and in `--check` mode (wired into `ci.yml` and `.githooks/pre-push`) **fails the build
if the committed table differs from the code**. That is the enforced link between this document and the
system: the evidence cannot say something the code does not.

<!--route-gating:begin-->

| Method | Route | Posture | Declared as | Registered at |
|---|---|---|---|---|
| PUT | `/access/catalog` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:58` |
| PUT | `/access/policies` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:50` |
| DELETE | `/access/profiles/([^/]+)` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:63` |
| PUT | `/access/profiles/([^/]+)` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:61` |
| PUT | `/access/roles` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:47` |
| POST | `/agent/approvals/(.+)/decision` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:177` |
| POST | `/agent/cases/(.+)/feedback` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:150` |
| PUT | `/agent/policy` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:194` |
| POST | `/agent/policy/kill-switch` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:199` |
| POST | `/agent/sessions` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AgentRoutes.java:32` |
| POST | `/agent/sessions/(.+)/ask` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AgentRoutes.java:43` |
| POST | `/agent/sessions/(.+)/ask/stream` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AgentRoutes.java:54` |
| POST | `/agent/tools/(.+)` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AgentRoutes.java:118` |
| POST | `/agent/tools/(.+)/derive` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AgentRoutes.java:88` |
| POST | `/alerts/evaluate` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AlertRoutes.java:40` |
| POST | `/alerts/rules` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:44` |
| DELETE | `/alerts/rules/([^/]+)` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:48` |
| PUT | `/alerts/rules/([^/]+)` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:46` |
| POST | `/assist/(.+)` | exempt | self-limiting | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AssistRoutes.java:54` |
| POST | `/assist/settings` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java:47` |
| POST | `/assist/settings/test` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java:45` |
| POST | `/auth/exchange` | exempt | identity-flow | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AuthRoutes.java:38` |
| POST | `/auth/logout` | exempt | identity-flow | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AuthRoutes.java:40` |
| POST | `/auth/refresh` | exempt | identity-flow | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/AuthRoutes.java:39` |
| POST | `/bi/query` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/BiRoutes.java:44` |
| POST | `/bi/templates/([^/]+)/apply` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BiRoutes.java:48` |
| POST | `/bundle/export` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/BundleRoutes.java:115` |
| POST | `/bundle/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java:118` |
| POST | `/bundle/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/BundleRoutes.java:116` |
| POST | `/cases/rules` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:101` |
| DELETE | `/cases/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:102` |
| POST | `/cases/rules/([^/]+)/evaluate` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:103` |
| POST | `/collectors/([^/]+)/notify` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java:27` |
| POST | `/components/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:46` |
| DELETE | `/components/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:48` |
| PUT | `/components/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:47` |
| POST | `/components/([^/]+)/([^/]+)/versions/([^/]+)/restore` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:51` |
| POST | `/components/grammar/([^/]+)/test` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:55` |
| POST | `/components/grammar/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:62` |
| POST | `/components/mapping/validate` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:66` |
| POST | `/components/sink/([^/]+)/test` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:56` |
| POST | `/components/sink/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:63` |
| POST | `/components/transform/([^/]+)/test` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:54` |
| POST | `/components/transform/describe` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:68` |
| POST | `/components/transform/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:61` |
| DELETE | `/config/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java:36` |
| PUT | `/config/icon-map` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:43` |
| POST | `/config/patch` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java:49` |
| POST | `/config/preview/parsing` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:43` |
| POST | `/config/preview/schema` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:46` |
| POST | `/config/suggest/schema` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:47` |
| POST | `/config/write` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java:45` |
| POST | `/connections` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:40` |
| DELETE | `/connections/([^/]+)` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:54` |
| PUT | `/connections/([^/]+)` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:53` |
| POST | `/connections/([^/]+)/probe` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:45` |
| POST | `/connections/([^/]+)/test` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:41` |
| POST | `/connections/test` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:52` |
| POST | `/dashboards/([^/]+)/share` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java:46` |
| POST | `/datasets/([^/]+)/materialize` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java:46` |
| POST | `/db/query` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java:55` |
| POST | `/decision-rules` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:55` |
| DELETE | `/decision-rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:59` |
| PUT | `/decision-rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:57` |
| POST | `/decision-rules/([^/]+)/apply` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:63` |
| POST | `/decision-rules/([^/]+)/simulate` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:61` |
| POST | `/enrichment` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java:40` |
| POST | `/enrichment/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java:43` |
| POST | `/events/views` | gated | `canAuthorWorkbench` | `inspecto-events/src/main/java/com/gamma/eventsapi/EventRoutes.java:59` |
| POST | `/events/views/([^/]+)/delete` | gated | `canAuthorWorkbench` | `inspecto-events/src/main/java/com/gamma/eventsapi/EventRoutes.java:61` |
| POST | `/exchange/grants/([^/]+)/(approve|deny|revoke)` | gated | `canApproveShares` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:81` |
| POST | `/exchange/grants/([^/]+)/expiry` | gated | `canApproveShares` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:85` |
| POST | `/exchange/grants/([^/]+)/pin` | gated | `canRequestShares` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:83` |
| POST | `/exchange/offers` | gated | `canOfferDatasets` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:75` |
| POST | `/exchange/refresh` | gated | `canOfferDatasets` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:77` |
| POST | `/exchange/requests` | gated | `canRequestShares` | `inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:79` |
| POST | `/expectations` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:53` |
| DELETE | `/expectations/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:57` |
| PUT | `/expectations/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:55` |
| POST | `/expectations/([^/]+)/evaluate` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:52` |
| POST | `/expectations/evaluate` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:51` |
| POST | `/geo/projection` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-geo-link/src/main/java/com/gamma/geolink/GeoRoutes.java:60` |
| POST | `/geo/routes` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-geo-link/src/main/java/com/gamma/geolink/GeoRoutes.java:61` |
| POST | `/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:64` |
| POST | `/import/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:65` |
| POST | `/inv/projection` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java:66` |
| POST | `/inv/projection/neighbors` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-geo-link/src/main/java/com/gamma/geolink/InvRoutes.java:67` |
| POST | `/jobs` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:55` |
| DELETE | `/jobs/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:58` |
| PUT | `/jobs/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:56` |
| POST | `/jobs/([^/]+)/disable` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:112` |
| POST | `/jobs/([^/]+)/enable` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:110` |
| POST | `/jobs/([^/]+)/reschedule` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:114` |
| POST | `/jobs/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:131` |
| POST | `/jobs/packs/rescan` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:84` |
| POST | `/jobs/runs/([^/]+)/replay` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:96` |
| PUT | `/nav/menus` | gated | `canCurateMenus` | `inspecto/src/main/java/com/gamma/control/NavRoutes.java:34` |
| POST | `/notes/([^/]+)/([^/]+)/attachments` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java:56` |
| POST | `/notes/([^/]+)/([^/]+)/comments` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java:54` |
| DELETE | `/notifications/(?!suppressions$)([^/]+)` | exempt | self-service | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:71` |
| POST | `/notifications/([^/]+)/read` | exempt | self-service | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:41` |
| POST | `/notifications/channels` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:49` |
| DELETE | `/notifications/channels/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:53` |
| PUT | `/notifications/channels/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:51` |
| PUT | `/notifications/preferences` | exempt | self-service | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:45` |
| POST | `/notifications/read-all` | exempt | self-service | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:40` |
| POST | `/notifications/rules` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:58` |
| DELETE | `/notifications/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:62` |
| PUT | `/notifications/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:60` |
| DELETE | `/notifications/suppressions` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java:68` |
| POST | `/objects` | gated | `canManageIncidents` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:55` |
| PATCH | `/objects/([^/]+)` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:86` |
| POST | `/objects/([^/]+)/ack` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:71` |
| POST | `/objects/([^/]+)/assign` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:74` |
| POST | `/objects/([^/]+)/attachments` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:83` |
| POST | `/objects/([^/]+)/comments` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:81` |
| DELETE | `/objects/([^/]+)/links` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:77` |
| POST | `/objects/([^/]+)/links` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:75` |
| POST | `/objects/([^/]+)/merge` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:78` |
| POST | `/objects/([^/]+)/rca` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:85` |
| POST | `/objects/([^/]+)/resolve` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:72` |
| POST | `/objects/([^/]+)/split` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:79` |
| POST | `/objects/([^/]+)/transition` | gated | `canAdminister` | `inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:73` |
| POST | `/parsers/([^/]+)/preview` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ParserRoutes.java:34` |
| PUT | `/pipelines/([^/]+)/graph` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:80` |
| POST | `/pipelines/([^/]+)/label` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:43` |
| POST | `/pipelines/([^/]+)/rename` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java:52` |
| POST | `/pipelines/([^/]+)/save-as-template` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:41` |
| POST | `/pipelines/([^/]+)/settings` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:49` |
| DELETE | `/pipelines/authored/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java:42` |
| POST | `/pipelines/authored/([^/]+)/dry-run` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:64` |
| POST | `/pipelines/authored/([^/]+)/run` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:68` |
| POST | `/pipelines/authored/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:73` |
| POST | `/pipelines/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java:96` |
| POST | `/pipelines/rename/resume` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java:56` |
| POST | `/public/dashboards/([^/]+)/query` | exempt | self-verifying-public | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ShareRoutes.java:49` |
| POST | `/public/delivery-status/([^/]+)` | exempt | self-verifying-public | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java:58` |
| POST | `/queries/([^/]+)/run` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/QueryRoutes.java:43` |
| POST | `/recon/breaks` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ReconRoutes.java:47` |
| POST | `/recon/columns` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ReconRoutes.java:45` |
| POST | `/recon/promote` | gated | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ReconRoutes.java:54` |
| POST | `/recon/rows` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ReconRoutes.java:50` |
| POST | `/recon/run` | exempt | stateless-compute | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ReconRoutes.java:46` |
| POST | `/requirements` | exempt | self-service | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:38` |
| POST | `/requirements/([^/]+)/decision` | gated | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:39` |
| POST | `/requirements/([^/]+)/deliver` | gated | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:41` |
| POST | `/rule-templates/([^/]+)/simulate` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RuleRoutes.java:51` |
| POST | `/runs` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:49` |
| POST | `/runs/([^/]+)/drain` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:106` |
| POST | `/runs/([^/]+)/pause` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:55` |
| POST | `/runs/([^/]+)/reprocess` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:93` |
| POST | `/runs/([^/]+)/resume` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:59` |
| POST | `/runs/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:50` |
| PUT | `/settings/branding` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:37` |
| PUT | `/settings/geo` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:40` |
| PUT | `/settings/scheduler` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java:76` |
| POST | `/spaces` | exempt | recovery-route | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:72` |
| DELETE | `/spaces/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:79` |
| PUT | `/spaces/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:76` |
| POST | `/spaces/import` | exempt | recovery-route | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:74` |
| POST | `/system/operational-db/test` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java:40` |
| PUT | `/system/scheduler` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java:73` |
| POST | `/tags` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:50` |
| DELETE | `/tags/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:52` |
| POST | `/tags/([^/]+)/rename` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:51` |
| POST | `/tags/assignments/([^/]+)/([^/]+)` | exempt | target-visibility-gated | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:66` |
| DELETE | `/tags/assignments/([^/]+)/([^/]+)/([^/]+)` | exempt | target-visibility-gated | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:68` |
| POST | `/tags/rules` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:54` |
| DELETE | `/tags/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:55` |
| POST | `/tags/rules/([^/]+)/apply` | exempt | collaboration | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:57` |
| POST | `/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:128` |
| POST | `/validate` | exempt | read-shaped | `.claude/worktrees/agent-a1c87e592ba95c08d/inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:40` |

<!--route-gating:end-->

## Exporting the evidence

The control above is enforced at boot, but the *record* that it held — who was refused, and when — lives
in the audit event stream. Two routes serve it, and the type is a **closed set**: `AUDITABLE` is exactly
`{AUDIT, ACCESS_DENIED}` (`AuditLogRoutes.java:40`). A type outside that set is refused, and a **blank**
type is refused too, because on this feed "blank" would mean *every type* and would turn an audit-only
route into the whole event stream. Absent is refused exactly like wrong.

```
GET /audit/export?format=csv&type=ACCESS_DENIED&from=<ISO>&to=<ISO>
GET /audit/export?format=csv&type=AUDIT&from=<ISO>&to=<ISO>
```

`ACCESS_DENIED` is the refusal record — a caller who lacked the capability a route demanded. `AUDIT` is the
mutating-action record. The CSV carries seven base columns plus one per `AuditAttrs` key; both auditable
types get the audit columns. `GET /audit/search` is the same projection for interactive use.

### ⚠ Retention is stated POLICY, not enforced — state it that way to an auditor

The operator's audit-retention window is **one year** (decision of 2026-08-30). ⛔ **Do not tell an auditor
that window is enforced today, because it is not.** The mechanism exists — `event_prune` (COMPLY-3,
controls-matrix gap G5) drops whole `level/year/month/day` Parquet partitions older than `retention_days`,
a partition file-delete and never a SQL `DELETE` — but:

- `retention_days` is **required with no default**, deliberately: "a window that silently defaults is a
  window the code does not apply" (`EventPruneTask`).
- **No committed configuration schedules `event_prune`.** Checked 2026-09-16: the task name appears in no
  `*.toon` in this repository.

⇒ Two consequences, and they pull in opposite directions, so state both. **(1)** No audit evidence is
being aged out today, so an export taken now reaches back as far as the store goes — the "export before
the prune reaches it" urgency does not currently apply. **(2)** The one-year *commitment* is therefore
unmet as a control: it is policy the code would honour if scheduled, not behaviour an auditor can observe.
`EventPruneTask`'s own javadoc carries this instruction — "an auditor must not be told it is enforced".

Closing the gap is a deployment act, not a code change: schedule `event_prune` with an explicit
`retention_days`, then this section can say *enforced* and cite a run. Until then it says *policy*.
