# Evidence — route gating (CC6)

**Audience:** an auditor sampling logical-access enforcement, and the deploying organization.
**Control:** SOC 2 **CC6.1** (logical access) · **CC6.3** (role-based authorization) · ISO 27001:2022
**A.5.15** (access control) · **A.5.18** (access rights) · **A.8.3** (information access restriction).
Matrix row: [`../controls-matrix.md`](../controls-matrix.md) → CC6 (logical access).

✅ **Framework — ANSWERED 2026-09-16, and it was already answered in this repo.** The header above follows
the pattern [`release-verification.md`](release-verification.md) already set: name every framework the
evidence serves on one line, and let the **matrix** own the per-control mapping. ⛔ **Do not restate the
Annex A mapping in this document.** `../controls-matrix.md` declares itself the single table the ISO
Statement of Applicability is *exported* from; a second copy here is the drift its own rule 4 exists to
prevent — if these two ever disagree, the matrix wins.

🔴 **The earlier draft of this header cited "ISO 27001 A.9", which no longer exists.** ISO/IEC 27001:**2022**
retired the 2013 Annex A structure; old A.9 (access control) is now spread across A.5.15, A.5.16, A.5.18,
A.8.2 and A.8.3. `../controls-matrix.md` §2 is headed *"ISO 27001:2022 — Annex A"* and uses 2022 numbering
throughout, so the 2013 citation was not merely dated — it contradicted the matrix it points at. ⚠ Only the
criterion references changed; the control, the enforcement points and the table below are untouched.

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

**Scope.** Professional and Enterprise. ⚠ Personal is auth-free by design and enforces no capability at all —
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

⚠ **`POST /spaces` and `POST /spaces/import` are `canAdminister` always, zero Spaces included** (operator 2026-10-03: the
bootstrap admin holds it through `Roles.SEED`). With a `template` that carries a KPI pack `POST /spaces` also needs
`canAuthorWorkbench` and each KPI meets the `/components/kpi` save gate: 403 / 422, and nothing is created
(`ControlApiSpaceTemplateKpiTest`, 2026-09-28).

## The mutating-route inventory

🔴 **This table is GENERATED — never hand-typed.** `tools/route-gating-report.mjs` derives it from the same
source scan CI runs, and in `--check` mode (wired into `ci.yml` and `.githooks/pre-push`) **fails the build
if the committed table differs from the code**. That is the enforced link between this document and the
system: the evidence cannot say something the code does not.

<!--route-gating:begin-->

| Method | Route | Posture | Declared as | Registered at |
|---|---|---|---|---|
| PUT | `/access/catalog` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:70` |
| PUT | `/access/policies` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:56` |
| POST | `/access/policies/preview` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:62` |
| DELETE | `/access/profiles/([^/]+)` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:75` |
| PUT | `/access/profiles/([^/]+)` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:73` |
| PUT | `/access/roles` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java:53` |
| POST | `/action-requests` | gated | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java:63` |
| POST | `/action-requests/([^/]+)/approve` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java:65` |
| POST | `/action-requests/([^/]+)/decline` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java:67` |
| POST | `/action-requests/([^/]+)/mark-failed` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java:71` |
| POST | `/action-requests/([^/]+)/retry` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/ActionRequestRoutes.java:69` |
| POST | `/agent/approvals/(.+)/decision` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:202` |
| PUT | `/agent/policy` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:219` |
| POST | `/agent/policy/kill-switch` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:224` |
| POST | `/agent/sessions` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:45` |
| POST | `/agent/sessions/(.+)/ask` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:62` |
| POST | `/agent/sessions/(.+)/ask/stream` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:74` |
| POST | `/agent/tools/(.+)` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:141` |
| POST | `/agent/tools/(.+)/derive` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:109` |
| POST | `/agent/triage-runs/(.+)/feedback` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java:175` |
| POST | `/alerts/([^/]+)/ack` | gated | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:62` |
| POST | `/alerts/([^/]+)/resolve` | gated | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:64` |
| POST | `/alerts/evaluate` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:55` |
| POST | `/alerts/rules` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:66` |
| DELETE | `/alerts/rules/([^/]+)` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:71` |
| PUT | `/alerts/rules/([^/]+)` | gated | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java:68` |
| POST | `/assist/(.+)` | exempt | self-limiting | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java:65` |
| POST | `/assist/settings` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java:58` |
| POST | `/assist/settings/test` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java:53` |
| POST | `/audit/anchors` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java:57` |
| POST | `/audit/anchors/rebaseline` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java:59` |
| POST | `/auth/exchange` | exempt | identity-flow | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java:41` |
| POST | `/auth/logout` | exempt | identity-flow | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java:43` |
| POST | `/auth/refresh` | exempt | identity-flow | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java:42` |
| POST | `/bi/query` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/BiRoutes.java:50` |
| POST | `/bi/templates/([^/]+)/apply` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BiRoutes.java:54` |
| POST | `/bundle/export` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java:144` |
| POST | `/bundle/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java:147` |
| POST | `/bundle/preview` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java:145` |
| POST | `/cases/from-entities` | gated | `canManageIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:139` |
| POST | `/cases/rules` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:132` |
| DELETE | `/cases/rules/([^/]+)` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:133` |
| POST | `/cases/rules/([^/]+)/evaluate` | gated | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:134` |
| POST | `/collectors/([^/]+)/notify` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java:30` |
| POST | `/components/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:102` |
| DELETE | `/components/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:104` |
| PUT | `/components/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:103` |
| POST | `/components/([^/]+)/([^/]+)/versions/([^/]+)/restore` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:107` |
| POST | `/components/escalation-rule` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:96` |
| DELETE | `/components/escalation-rule/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:98` |
| PUT | `/components/escalation-rule/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:97` |
| POST | `/components/escalation-rule/([^/]+)/versions/([^/]+)/restore` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:99` |
| POST | `/components/findings-spec` | gated | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:78` |
| DELETE | `/components/findings-spec/([^/]+)` | gated | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:80` |
| PUT | `/components/findings-spec/([^/]+)` | gated | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:79` |
| POST | `/components/findings-spec/([^/]+)/versions/([^/]+)/restore` | gated | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:81` |
| POST | `/components/grammar/([^/]+)/test` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:111` |
| POST | `/components/grammar/preview` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:118` |
| POST | `/components/mapping/validate` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:122` |
| POST | `/components/sink/([^/]+)/test` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:112` |
| POST | `/components/sink/preview` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:119` |
| POST | `/components/sla-policy` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:91` |
| DELETE | `/components/sla-policy/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:93` |
| PUT | `/components/sla-policy/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:92` |
| POST | `/components/sla-policy/([^/]+)/versions/([^/]+)/restore` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:94` |
| POST | `/components/sql/ast` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:127` |
| POST | `/components/transform/([^/]+)/test` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:110` |
| POST | `/components/transform/describe` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:124` |
| POST | `/components/transform/preview` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:117` |
| POST | `/components/workflow` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:86` |
| DELETE | `/components/workflow/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:88` |
| PUT | `/components/workflow/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:87` |
| POST | `/components/workflow/([^/]+)/versions/([^/]+)/restore` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java:89` |
| DELETE | `/config/([^/]+)/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java:39` |
| PUT | `/config/icon-map` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:83` |
| POST | `/config/patch` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java:48` |
| POST | `/config/preview/parsing` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:46` |
| POST | `/config/preview/schema` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:49` |
| POST | `/config/suggest/schema` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:50` |
| POST | `/config/write` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java:44` |
| POST | `/connections` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:43` |
| DELETE | `/connections/([^/]+)` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:64` |
| PUT | `/connections/([^/]+)` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:63` |
| POST | `/connections/([^/]+)/probe` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:52` |
| POST | `/connections/([^/]+)/test` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:47` |
| POST | `/connections/test` | gated | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java:60` |
| POST | `/dashboards/([^/]+)/share` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java:55` |
| POST | `/datasets/([^/]+)/materialize` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java:64` |
| POST | `/db/query` | exempt | group-gated | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java:60` |
| POST | `/decision-rules` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:63` |
| DELETE | `/decision-rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:67` |
| PUT | `/decision-rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:65` |
| POST | `/decision-rules/([^/]+)/apply` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:71` |
| POST | `/decision-rules/([^/]+)/simulate` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java:69` |
| POST | `/enrichment` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java:44` |
| POST | `/enrichment/preview` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java:48` |
| POST | `/entity-lists` | gated | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java:96` |
| POST | `/entity-lists/([^/]+)/match` | exempt | read-shaped | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java:102` |
| POST | `/entity-lists/([^/]+)/members` | gated | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java:98` |
| POST | `/entity-lists/([^/]+)/register-dataset` | gated | `canAuthorWorkbench` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java:105` |
| POST | `/entity-lists/([^/]+)/retire` | gated | `canManageIncidents` | `features/inspecto-entity-list/src/main/java/com/gamma/entitylist/EntityListRoutes.java:100` |
| POST | `/events/views` | gated | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java:68` |
| POST | `/events/views/([^/]+)/delete` | gated | `canAuthorWorkbench` | `features/inspecto-observability/src/main/java/com/gamma/eventsapi/EventRoutes.java:70` |
| POST | `/exchange/grants/([^/]+)/(approve|deny|revoke)` | gated | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:85` |
| POST | `/exchange/grants/([^/]+)/expiry` | gated | `canApproveShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:91` |
| POST | `/exchange/grants/([^/]+)/pin` | gated | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:88` |
| POST | `/exchange/offers` | gated | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:72` |
| POST | `/exchange/refresh` | gated | `canOfferDatasets` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:79` |
| POST | `/exchange/requests` | gated | `canRequestShares` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:82` |
| POST | `/exchange/signal-offers` | gated | `canOfferSignals` | `features/inspecto-exchange/src/main/java/com/gamma/exchange/ExchangeRoutes.java:76` |
| POST | `/expectations` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:73` |
| DELETE | `/expectations/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:77` |
| PUT | `/expectations/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:75` |
| POST | `/expectations/([^/]+)/baseline/accept` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:68` |
| POST | `/expectations/([^/]+)/baseline/clear` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:70` |
| POST | `/expectations/([^/]+)/evaluate` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:64` |
| POST | `/expectations/evaluate` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java:63` |
| POST | `/geo/projection` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java:70` |
| POST | `/geo/routes` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java:71` |
| POST | `/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:76` |
| POST | `/import/preview` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java:77` |
| POST | `/inv/entity-identities` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java:88` |
| POST | `/inv/entity-identities/([^/]+)/retract` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java:92` |
| POST | `/inv/entity-identities/import` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java:90` |
| POST | `/inv/graph/runs` | gated | `canRunLinkGraphAnalysis` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java:115` |
| POST | `/inv/graph/runs/([^/]+)/cancel` | exempt | self-service | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java:120` |
| POST | `/inv/index/builds` | gated | `canBuildLinkIndex` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java:116` |
| POST | `/inv/index/builds/([^/]+)/cancel` | exempt | self-service | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java:119` |
| POST | `/inv/investigation-templates/([^/]+)/instantiate` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java:97` |
| POST | `/inv/investigations` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:143` |
| POST | `/inv/investigations/([^/]+)/alert-rules` | gated | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java:73` |
| PUT | `/inv/investigations/([^/]+)/alert-rules/([^/]+)` | gated | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java:79` |
| DELETE | `/inv/investigations/([^/]+)/case` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java:60` |
| PUT | `/inv/investigations/([^/]+)/case` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java:58` |
| POST | `/inv/investigations/([^/]+)/dossier/bundle/verify` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierBundleRoutes.java:64` |
| POST | `/inv/investigations/([^/]+)/dossier/verify` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierRoutes.java:66` |
| POST | `/inv/investigations/([^/]+)/drafts` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:63` |
| POST | `/inv/investigations/([^/]+)/drafts/([^/]+)/discard` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:74` |
| POST | `/inv/investigations/([^/]+)/drafts/([^/]+)/ops` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:70` |
| POST | `/inv/investigations/([^/]+)/drafts/([^/]+)/promote` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:79` |
| POST | `/inv/investigations/([^/]+)/drafts/([^/]+)/rebase` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:77` |
| POST | `/inv/investigations/([^/]+)/drafts/([^/]+)/undo` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java:72` |
| POST | `/inv/investigations/([^/]+)/members` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java:50` |
| POST | `/inv/investigations/([^/]+)/members/revoke` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java:52` |
| POST | `/inv/investigations/([^/]+)/ops` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:146` |
| POST | `/inv/investigations/([^/]+)/pending/([^/]+)/approve` | gated | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:156` |
| POST | `/inv/investigations/([^/]+)/pending/([^/]+)/deny` | gated | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:158` |
| POST | `/inv/investigations/([^/]+)/references` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java:61` |
| POST | `/inv/investigations/([^/]+)/reorder` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:150` |
| POST | `/inv/investigations/([^/]+)/replay` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:152` |
| POST | `/inv/investigations/([^/]+)/reveal` | gated | `canRevealLinkEntities` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:154` |
| POST | `/inv/investigations/([^/]+)/standing-detection` | gated | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java:76` |
| DELETE | `/inv/investigations/([^/]+)/standing-detection/([^/]+)` | gated | `canAuthorAlertRules` | `la/inspecto-geo-link/src/main/java/com/gamma/geolink/InvestigationMeasureRoutes.java:81` |
| POST | `/inv/investigations/([^/]+)/template` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java:93` |
| POST | `/inv/investigations/([^/]+)/undo` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java:148` |
| POST | `/inv/pattern/branching` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java:70` |
| POST | `/inv/pattern/temporal` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java:71` |
| POST | `/inv/projection` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:101` |
| POST | `/inv/projection/multi` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:103` |
| POST | `/inv/projection/neighbors` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:102` |
| POST | `/inv/schema/overlap-profile` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:105` |
| POST | `/inv/snapshots` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:116` |
| POST | `/inv/snapshots/attach` | gated | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:119` |
| POST | `/inv/traversal/recursive-paths` | exempt | read-shaped | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java:106` |
| POST | `/jobs` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:67` |
| DELETE | `/jobs/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:70` |
| PUT | `/jobs/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:68` |
| POST | `/jobs/([^/]+)/disable` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:124` |
| POST | `/jobs/([^/]+)/enable` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:122` |
| POST | `/jobs/([^/]+)/reschedule` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:126` |
| POST | `/jobs/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:143` |
| POST | `/jobs/packs/rescan` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:96` |
| POST | `/jobs/runs/([^/]+)/replay` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java:108` |
| PUT | `/nav/menus` | gated | `canCurateMenus` | `inspecto/src/main/java/com/gamma/control/NavRoutes.java:37` |
| POST | `/notes/([^/]+)/([^/]+)/attachments` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java:58` |
| POST | `/notes/([^/]+)/([^/]+)/comments` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/NoteRoutes.java:56` |
| DELETE | `/notifications/(?!suppressions$)([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:91` |
| POST | `/notifications/([^/]+)/read` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:53` |
| POST | `/notifications/([^/]+)/unread` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:54` |
| POST | `/notifications/channels` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:69` |
| DELETE | `/notifications/channels/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:73` |
| PUT | `/notifications/channels/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:71` |
| PUT | `/notifications/preferences` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:60` |
| PUT | `/notifications/preferences/default` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:62` |
| POST | `/notifications/read-all` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:52` |
| POST | `/notifications/rules` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:78` |
| DELETE | `/notifications/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:82` |
| PUT | `/notifications/rules/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java:80` |
| DELETE | `/notifications/suppressions` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java:78` |
| POST | `/objects` | gated | `canManageIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:64` |
| PATCH | `/objects/([^/]+)` | gated | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:115` |
| POST | `/objects/([^/]+)/ack` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:85` |
| POST | `/objects/([^/]+)/assign` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:88` |
| POST | `/objects/([^/]+)/attachments` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:97` |
| PUT | `/objects/([^/]+)/category` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:110` |
| POST | `/objects/([^/]+)/comments` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:95` |
| PUT | `/objects/([^/]+)/findings` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:104` |
| PUT | `/objects/([^/]+)/impact` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:114` |
| DELETE | `/objects/([^/]+)/links` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:91` |
| POST | `/objects/([^/]+)/links` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:89` |
| POST | `/objects/([^/]+)/merge` | gated | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:92` |
| PUT | `/objects/([^/]+)/postmortem` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:109` |
| POST | `/objects/([^/]+)/rca` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:99` |
| POST | `/objects/([^/]+)/resolve` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:86` |
| POST | `/objects/([^/]+)/split` | gated | `canAdminister` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:93` |
| POST | `/objects/([^/]+)/transition` | gated | `canWorkIncidents` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/ObjectRoutes.java:87` |
| POST | `/parsers/([^/]+)/preview` | exempt | provenance-gated | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java:41` |
| POST | `/pending-changes/([^/]+)/approve` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java:69` |
| POST | `/pending-changes/([^/]+)/decline` | gated | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java:71` |
| POST | `/pending-changes/([^/]+)/withdraw` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java:74` |
| PUT | `/pipelines/([^/]+)/graph` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:83` |
| POST | `/pipelines/([^/]+)/history/([^/]+)/restore` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java:55` |
| POST | `/pipelines/([^/]+)/label` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:41` |
| POST | `/pipelines/([^/]+)/rename` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java:49` |
| POST | `/pipelines/([^/]+)/save-as-template` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:39` |
| POST | `/pipelines/([^/]+)/settings` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java:47` |
| DELETE | `/pipelines/authored/([^/]+)` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java:44` |
| POST | `/pipelines/authored/([^/]+)/dry-run` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:67` |
| POST | `/pipelines/authored/([^/]+)/inbox` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineInboxRoutes.java:42` |
| POST | `/pipelines/authored/([^/]+)/run` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:71` |
| POST | `/pipelines/authored/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java:76` |
| POST | `/pipelines/import` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java:98` |
| POST | `/pipelines/rename/resume` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java:53` |
| POST | `/public/dashboards/([^/]+)/query` | exempt | self-verifying-public | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java:58` |
| POST | `/public/delivery-status/([^/]+)` | exempt | self-verifying-public | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java:68` |
| POST | `/queries/([^/]+)/run` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/QueryRoutes.java:48` |
| POST | `/recon/([^/]+)/breaks/status` | gated | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:80` |
| POST | `/recon/([^/]+)/record` | gated | `canOperateRuns` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:78` |
| POST | `/recon/breaks` | exempt | read-shaped | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:64` |
| POST | `/recon/columns` | exempt | read-shaped | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:62` |
| POST | `/recon/promote` | gated | `canManageIncidents` | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:71` |
| POST | `/recon/rows` | exempt | read-shaped | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:67` |
| POST | `/recon/run` | exempt | stateless-compute | `features/inspecto-reconciliation/src/main/java/com/gamma/recon/ReconRoutes.java:63` |
| POST | `/requirements` | exempt | self-service | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:42` |
| POST | `/requirements/([^/]+)/decision` | gated | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:43` |
| POST | `/requirements/([^/]+)/deliver` | gated | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:45` |
| POST | `/requirements/([^/]+)/kpi` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java:49` |
| POST | `/risk-scores/preview` | gated | `canWorkIncidents` | `features/inspecto-scoring/src/main/java/com/gamma/risk/RiskScoreRoutes.java:60` |
| POST | `/rule-templates/([^/]+)/simulate` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RuleRoutes.java:54` |
| POST | `/runs` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:52` |
| POST | `/runs/([^/]+)/drain` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:123` |
| POST | `/runs/([^/]+)/pause` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:58` |
| POST | `/runs/([^/]+)/replay-rejects` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:108` |
| POST | `/runs/([^/]+)/reprocess` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:96` |
| POST | `/runs/([^/]+)/resume` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:62` |
| POST | `/runs/([^/]+)/retries/cancel` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:117` |
| POST | `/runs/([^/]+)/retries/retry-now` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:115` |
| POST | `/runs/([^/]+)/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:53` |
| PUT | `/settings/approval` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java:64` |
| PUT | `/settings/approvers` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ApproverRosterRoutes.java:39` |
| PUT | `/settings/branding` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:71` |
| PUT | `/settings/egress` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/EgressRoutes.java:52` |
| PUT | `/settings/geo` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:74` |
| PUT | `/settings/link-analysis` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:77` |
| PUT | `/settings/mail-attachments` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/MailAttachmentRoutes.java:37` |
| PUT | `/settings/modules` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ModuleSettingsRoutes.java:44` |
| PUT | `/settings/pipeline-history` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:80` |
| PUT | `/settings/publication-destinations` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PublicationDestinationRoutes.java:37` |
| PUT | `/settings/scheduler` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java:81` |
| PUT | `/settings/timezone` | gated | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java:86` |
| POST | `/space-comparisons` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceComparisonRoutes.java:45` |
| POST | `/spaces` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:72` |
| DELETE | `/spaces/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:79` |
| PUT | `/spaces/([^/]+)` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:76` |
| POST | `/spaces/import` | gated | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java:74` |
| POST | `/streams/([^/]+)/records` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/StreamPushRoutes.java:87` |
| POST | `/system/operational-db/test` | gated | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java:42` |
| PUT | `/system/scheduler` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java:78` |
| POST | `/tags` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:53` |
| DELETE | `/tags/([^/]+)` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:55` |
| POST | `/tags/([^/]+)/rename` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:54` |
| POST | `/tags/assignments/([^/]+)/([^/]+)` | exempt | target-visibility-gated | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:69` |
| DELETE | `/tags/assignments/([^/]+)/([^/]+)/([^/]+)` | exempt | target-visibility-gated | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:71` |
| POST | `/tags/rules` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:57` |
| DELETE | `/tags/rules/([^/]+)` | gated | `canAuthorWorkbench` | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:58` |
| POST | `/tags/rules/([^/]+)/apply` | exempt | collaboration | `features/inspecto-ops/src/main/java/com/gamma/opsapi/TagRoutes.java:60` |
| POST | `/trigger` | gated | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java:145` |
| POST | `/validate` | exempt | read-shaped | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java:43` |

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
