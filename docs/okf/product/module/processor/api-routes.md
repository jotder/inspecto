---
type: Concept
title: HTTP routes — module.processor
description: Every /api/v1 route the processor module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.processor`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

393 route(s) registered; 393 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/access/catalog` | The Access Catalog | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/catalog` | Replace the Access Catalog | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/explain` | Explain an access decision for my session | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/policies` | List access policies | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/policies` | Replace the access policies | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| POST | `/access/policies/preview` | Impact matrix for an unsaved Access Policy draft | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/profiles` | List Access Profiles | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| DELETE | `/access/profiles/{profileId}` | Delete an Access Profile | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/profiles/{profileId}` | Save an Access Profile | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/roles` | The role table | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/roles` | Replace the authored role table | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/roles/{roleId}/effective` | What one role may do | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/agent/actions` | List autonomous agent actions | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/actions/{actionId}` | One autonomous agent action | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/approvals` | List agent approvals | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/approvals/{approvalId}` | One agent approval | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/approvals/{approvalId}/decision` | Approve or decline an agent action | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/feedback` | List Triage Run feedback | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/policy` | The agent autonomy policy | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| PUT | `/agent/policy` | Update the agent autonomy policy | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/policy/kill-switch` | Engage or release the autonomy kill switch | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions` | Open an intelligence agent session | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions/{sessionId}/ask` | Ask an agent session a question | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions/{sessionId}/ask/stream` | Ask an agent session (SSE stream) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/tools/{toolId}` | Run one agent tool | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/tools/{toolId}/derive` | Run an agent tool from a sentence | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs` | List recent Triage Runs | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs/{triageRunId}` | One Triage Run | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/triage-runs/{triageRunId}/feedback` | Rate a Triage Run | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs/{triageRunId}/similar` | Find Triage Runs similar to one | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/alerts` | List recent Alerts | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/{alertId}/ack` | Acknowledge an Alert | alerts | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/{alertId}/resolve` | Resolve an Alert | alerts | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/evaluate` | Evaluate every Alert Rule | alerts | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/alerts/rules` | List armed Alert Rules | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/rules` | Create an Alert Rule | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| DELETE | `/alerts/rules/{ruleId}` | Delete an Alert Rule | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| PUT | `/alerts/rules/{ruleId}` | Replace an Alert Rule | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/alerts/rules/pending` | List deferred Alert Rules | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/analytics/functions` | The Analytics function catalogue: every function the Analytics Kernel offers | analytics | — | `inspecto/src/main/java/com/gamma/control/AnalyticsCatalogueRoutes.java` |
| GET | `/apps` | The Product's Apps, the Apps installed live, and the trusted signers (PRODUCT-ASSEMBLY-1 PA-7d) | settings | — | `inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java` |
| POST | `/apps` | Install a signed App package (canAdminister; PA-7d) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java` |
| DELETE | `/apps/{id}` | Remove an installed App (canAdminister; PA-7d) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java` |
| PUT | `/apps/{id}` | Upgrade an installed App (canAdminister; PA-7d, D-PA25) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AppInstallRoutes.java` |
| POST | `/assist/{assistId}` | Ask an assist skill | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/diagnoses` | List recent failure diagnoses | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/metrics` | Assist agent metrics | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/settings` | The assist model-provider settings | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| POST | `/assist/settings` | Update the assist model-provider settings | assist | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| POST | `/assist/settings/test` | Test the assist model-provider settings | assist | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/skills` | List assist skills | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/audit/anchors` | List signed audit anchors | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/audit/anchors` | Anchor the audit chain now | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/audit/anchors/rebaseline` | Rebaseline a broken audit chain | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/export` | Export the audit trail | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/route-inventory` | The route inventory this server registered | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/search` | Search the audit trail | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/verify` | Verify the audit hash chain | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/auth/exchange` | Redeem an OIDC authorization code (backend-mediated session) | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/auth/logout` | End the session | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/auth/refresh` | Mint a fresh access token from the session cookie | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/bi/query` | Run a headless BI Measure query | bi | — | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| GET | `/bi/templates` | List BI starter templates | bi | — | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| POST | `/bi/templates/{templateId}/apply` | Apply a BI starter template | bi | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| GET | `/bootstrap` | Platform bootstrap — everything the backend owns to start the UI | Platform & Spaces | — | `inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java` |
| POST | `/bundle/export` | Export components as a Metadata Bundle | bundle | — | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| POST | `/bundle/import` | Import a Metadata Bundle | bundle | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| POST | `/bundle/preview` | Read-only fit-check of a Metadata Bundle against this Space | bundle | — | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| GET | `/catalog` | List Catalog tables | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/graph` | Traverse the Catalog graph | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/kpis` | KPI catalog | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/references` | List Catalog References | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/resolve` | Resolve a table or Pipeline to its Catalog node | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/streams` | List Catalog Streams | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/tables/{tableId}` | One Catalog node with its neighbours | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/classifications` | The Space's column classification vocabulary | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ClassificationRoutes.java` |
| GET | `/collectors` | List Collectors | collectors | — | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| POST | `/collectors/{collectorId}/notify` | Tell a Collector a file has landed | collectors | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| GET | `/components/{componentId}` | Registry components of one kind | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/{componentId}` | Create a registry component | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/{type}/{id}` | Delete a registry component | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| GET | `/components/{type}/{id}` | One registry component | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/{type}/{id}` | Replace a registry component | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| GET | `/components/{componentId}/{componentId2}/versions` | Version history of a component | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/{componentId}/{componentId2}/versions/{versionId}/restore` | Restore a component version | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/escalation-rule` | POST /components/escalation-rule | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/escalation-rule/{escalationruleId}` | DELETE /components/escalation-rule/{escalationruleId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/escalation-rule/{escalationruleId}` | PUT /components/escalation-rule/{escalationruleId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/escalation-rule/{escalationruleId}/versions/{versionId}/restore` | POST /components/escalation-rule/{escalationruleId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/findings-spec` | Create the Findings spec component | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/findings-spec/{findingsspecId}` | Delete a Findings spec component | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/findings-spec/{findingsspecId}` | Replace a Findings spec component | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/findings-spec/{findingsspecId}/versions/{versionId}/restore` | Restore a Findings spec version | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/grammar/{grammarId}/test` | Test a grammar component on sample text | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/grammar/preview` | Preview an inline grammar config | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/mapping/validate` | Validate a draft mapping | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sink/{sinkId}/test` | Test a sink component on sample rows | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sink/preview` | Preview an inline sink config | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sla-policy` | POST /components/sla-policy | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/sla-policy/{slapolicyId}` | DELETE /components/sla-policy/{slapolicyId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/sla-policy/{slapolicyId}` | PUT /components/sla-policy/{slapolicyId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sla-policy/{slapolicyId}/versions/{versionId}/restore` | POST /components/sla-policy/{slapolicyId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sql/ast` | Parse tree of author SQL | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/{transformId}/test` | Test a transform component on sample rows | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/describe` | Derive a SQL transform's output columns | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/preview` | Preview an inline transform config | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/workflow` | POST /components/workflow | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/workflow/{workflowId}` | DELETE /components/workflow/{workflowId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/workflow/{workflowId}` | PUT /components/workflow/{workflowId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/workflow/{workflowId}/versions/{versionId}/restore` | POST /components/workflow/{workflowId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/config/{type}/{name}` | Discard a config file (draft delete) | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| GET | `/config/{type}/{name}` | Read a config file back (decoded) | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| GET | `/config/icon-map` | The Space's processor icon map | config | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/config/icon-map` | Replace the Space's processor icon map | config | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| POST | `/config/patch` | Deep-merge a patch into a config file | config | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java` |
| GET | `/config/pipeline/{pipelineId}/impact` | What references a Pipeline, before a delete | config | — | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| POST | `/config/preview/parsing` | Parse a raw sample with a draft's parsing settings | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/preview/schema` | TRY_CAST already-parsed sample rows against a draft schema's typed fields | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/config/schema/{schemaId}/columns/{columnId}/impact` | What reads one mapped column of a Schema (column impact, report-only) | config | — | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| GET | `/config/schema/derived` | Derived output schema of a saved Pipeline | config | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/config/spec/{type}` | Declarative spec for a config type | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/suggest/schema` | Suggest a draft schema from sample rows | config | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/write` | Persist a validated config draft | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java` |
| GET | `/connections` | List Connection profiles | Acquisition | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections` | Create a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| DELETE | `/connections/{id}` | Delete a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{id}` | One Connection profile | Acquisition | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| PUT | `/connections/{id}` | Replace a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{connectionId}/explore` | Browse a Connection's resources | connections | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/{connectionId}/probe` | Graded probe of a saved Connection | connections | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{connectionId}/sample` | Sample a file through a Connection | connections | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/{id}/test` | Test a Connection's reachability | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/test` | Test an unsaved Connection profile | connections | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/consignments/{consignmentId}` | One Consignment across every lane | Execution & Scheduling | — | `inspecto/src/main/java/com/gamma/control/ConsignmentRoutes.java` |
| POST | `/dashboards/{dashboardId}/share` | Create a public share link for a Dashboard | dashboards | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| GET | `/datasets/{datasetId}/arrivals` | Arrivals of an Add data Dataset | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| PUT | `/datasets/{datasetId}/columns/{columnId}` | Change which Entity Type one Dataset column holds | datasets | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/datasets/{datasetId}/compact` | Compact an import Dataset now | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/DatasetCompaction.java` |
| GET | `/datasets/{datasetId}/coverage` | GET /datasets/{datasetId}/coverage | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| DELETE | `/datasets/{datasetId}/feed` | Return an import Dataset to manual uploads | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/DatasetFeed.java` |
| GET | `/datasets/{datasetId}/feed` | Inbox and feed of an import Dataset | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/DatasetFeed.java` |
| PUT | `/datasets/{datasetId}/feed` | Keep an import Dataset up to date | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/DatasetFeed.java` |
| POST | `/datasets/{datasetId}/files` | Add a file to an imported Dataset and run it | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/datasets/{datasetId}/filter` | Row filter of an import Dataset | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/ImportFilterEdit.java` |
| PUT | `/datasets/{datasetId}/filter` | Edit an import Dataset's row filter | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/ImportFilterEdit.java` |
| GET | `/datasets/{datasetId}/freshness` | Data as of for a Dataset | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| POST | `/datasets/{datasetId}/materialize` | Materialize a Dataset into another (async) | datasets | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| PUT | `/datasets/{datasetId}/record-type` | Say what one row of a Dataset is (its record type) | datasets | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/datasets/{datasetId}/rows` | GET /datasets/{datasetId}/rows | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| POST | `/datasets/{datasetId}/run` | Run now: run an imported Dataset's Pipeline over what waits in its inbox | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/datasets/{datasetId}/storage` | Storage state of an import Dataset | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/DatasetCompaction.java` |
| PUT | `/datasets/{datasetId}/storage` | Set an import Dataset's retention | datasets | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DatasetCompaction.java` |
| PUT | `/datasets/{datasetId}/watch` | Watch an import Dataset's inbox | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/InboxWatch.java` |
| GET | `/datasources` | List bundle-exportable Pipeline ids | datasources | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/datasources/{datasourceId}/export` | Export one Pipeline's config bundle | datasources | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/db/catalog` | Table browser catalog | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| POST | `/db/query` | Run read-only SQL against one table | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| GET | `/db/table` | Page one table's rows | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| GET | `/decision-rules` | List Decision Rules | decision-rules | — | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules` | Create a Decision Rule | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| DELETE | `/decision-rules/{decisionruleId}` | Delete a Decision Rule | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| PUT | `/decision-rules/{decisionruleId}` | Replace a Decision Rule | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules/{decisionruleId}/apply` | Apply a Decision Rule's consequences | decision-rules | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules/{decisionruleId}/simulate` | Simulate a Decision Rule | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| GET | `/decision-rules/consequences` | List Decision Rule consequence actions | decision-rules | — | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| GET | `/enrichment` | List enrichment Jobs | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| POST | `/enrichment` | Register an enrichment Job from a config on disk | enrichment | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/lineage` | Output lineage of an enrichment Job | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/report` | Run-audit report of an enrichment Job | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/runs` | Run audit of an enrichment Job | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| POST | `/enrichment/preview` | Preview an enrichment draft on sample rows | enrichment | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/expectations` | List Expectations | expectations | — | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations` | Create an Expectation | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| DELETE | `/expectations/{expectationId}` | Delete an Expectation | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| PUT | `/expectations/{expectationId}` | Replace an Expectation | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/baseline/accept` | Accept a profile into an Expectation's baseline | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/baseline/clear` | Clear an Expectation's baseline | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/evaluate` | Evaluate one Expectation | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| GET | `/expectations/breached-count` | Count breached Expectations | expectations | — | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/evaluate` | Evaluate every Expectation | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| GET | `/export` | Export the whole Space's config | export | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| POST | `/import` | Import a config bundle into this Space | import | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| POST | `/import/preview` | Preview a config bundle import | import | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/integrity` | GET /integrity - the Space lock's report: broken links, duplicates, files changed outside the server | spaces | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SpaceIntegrityRoutes.java` |
| POST | `/integrity/accept` | POST /integrity/accept - accept files changed outside the server so they leave the integrity report | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceIntegrityRoutes.java` |
| GET | `/jobs` | List Jobs | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs` | Create a Job | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| DELETE | `/jobs/{jobId}` | Delete a Job | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}` | One Job | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| PUT | `/jobs/{jobId}` | Replace a Job | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/artifacts/latest` | Latest Run Artifacts of a Job | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/disable` | Disable a Job | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/enable` | Enable a Job | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/reschedule` | Reschedule a Job | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs` | List a Job's Runs | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/artifacts` | List a Run's Artifacts | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/artifacts/{artifactId}/content` | Download a Run Artifact file | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/diff/{diffId}` | Compare two Job Runs | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/log` | One Run's structured log | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/logs` | One Run's log for live tail | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{name}/trigger` | Trigger a job (async) | Execution & Scheduling | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/expressions` | List Job parameter expressions | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/failures` | Job Run failure trend | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/metrics` | Job Run metrics | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/packs` | List Job Packs | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/packs/rescan` | Rescan Job Packs | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/processors` | List consignment processors | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/runs` | List recent Job Runs | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/runs/{runId}` | Poll one run by id | Execution & Scheduling | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/runs/{runId}/replay` | Replay a Job Run (async) | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/types` | List Job Types | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/types/{typeId}` | One Job Type | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/kpis/{kpiId}/value` | Evaluate a KPI definition for the period to date | kpis | — | `inspecto/src/main/java/com/gamma/control/KpiRoutes.java` |
| GET | `/lineage` | Lineage around one output table | lineage | — | `inspecto/src/main/java/com/gamma/control/LineageRoutes.java` |
| GET | `/lookups` | Every keyed Reference lookup with its last run | Execution & Scheduling | — | `inspecto/src/main/java/com/gamma/control/LookupRoutes.java` |
| GET | `/metrics/acquisition` | Acquisition metrics snapshot | metrics | — | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| GET | `/modules` | List modules and their state | modules | — | `inspecto/src/main/java/com/gamma/control/ModulesRoutes.java` |
| GET | `/nav/menus` | The Space's Menu tree | nav | — | `inspecto/src/main/java/com/gamma/control/NavRoutes.java` |
| PUT | `/nav/menus` | Replace the Space's Menu tree | nav | `canCurateMenus` | `inspecto/src/main/java/com/gamma/control/NavRoutes.java` |
| GET | `/notifications` | List my notifications | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/{notificationId}` | Archive a notification | notifications | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/{notificationId}/read` | Mark a notification read | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/{notificationId}/unread` | Mark a notification unread | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/channels` | List notification channels | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/channels` | Create a notification channel | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/channels/{channelId}` | Delete a notification channel | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/channels/{channelId}` | Update a notification channel | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/deliveries` | List notification delivery receipts | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/preferences` | The caller’s effective notification preference grid | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/preferences` | Save the caller’s own preference override (self-service) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/preferences/default` | The deployment-default notification preference grid | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/preferences/default` | Edit the deployment-default grid (canAdminister) | notifications | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/read-all` | Mark all my notifications read | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/rules` | List notification rules | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/rules` | Create a notification rule | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/rules/{ruleId}` | Delete a notification rule | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/rules/{ruleId}` | Replace a notification rule | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/stream` | Stream notifications (SSE) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/suppressions` | Lift a destination's suppression | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/suppressions` | List suppressed delivery destinations | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/unread-count` | Count my unread notifications | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/onboard/files` | Add data: upload a file and get a draft of how to import it | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboard/files/{fileId}/commit` | Add data: one confirm - write the import Pipeline, run it once, register the Dataset | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboard/files/{fileId}/redraft` | Add data: answer a draft again around the column types and roles the person stated | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboard/postgres` | Add data, Choose a table: copy a Postgres table or query into a draft | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/PostgresSnapshot.java` |
| POST | `/onboard/postgres/test` | Add data, Choose a table: test a Postgres connection and list its tables | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/PostgresSnapshot.java` |
| GET | `/onboard/runs/{runId}` | Follow an import run answered with 202 | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboarding/events` | Report a step only the browser sees (LA-ONBOARD-1 OB-13) | — | — | `inspecto/src/main/java/com/gamma/control/OnboardingRoutes.java` |
| GET | `/onboarding/metrics` | What the onboarding counters say (LA-ONBOARD-1 OB-13) | — | `canAdminister` | `inspecto/src/main/java/com/gamma/control/OnboardingRoutes.java` |
| GET | `/origin` | GET /origin - which Space Template (and version) the Space came from, and what changed since | spaces | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SpaceOriginRoutes.java` |
| POST | `/origin/adopt` | POST /origin/adopt - match an existing Space to a Space Template by file hash and record its origin | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceOriginRoutes.java` |
| GET | `/origin/upgrade` | GET /origin/upgrade - what a newer version of the Space's template would change, file by file | spaces | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SpaceOriginRoutes.java` |
| POST | `/origin/upgrade` | POST /origin/upgrade - apply the chosen update and add files of a template upgrade proposal | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceOriginRoutes.java` |
| GET | `/parsers` | List parsers | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| POST | `/parsers/{parserId}/preview` | Preview a sample with a parser | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| GET | `/parsers/asn1/profile` | Read an ASN.1 Decode Profile | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| GET | `/pending-changes` | List the Space's Pending Changes | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pending-changes/{pendingchangeId}` | Read one Pending Change with its content | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/approve` | Approve a Pending Change and apply it | pending-changes | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/decline` | Decline a Pending Change | pending-changes | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pending-changes/{pendingchangeId}/diff` | Line diff of a Pending Change | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/withdraw` | Withdraw your own Pending Change | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pipelines` | List the registered Pipelines, broken ones included | Pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/{pipelineId}/bundle` | Export a Pipeline with its file closure | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java` |
| GET | `/pipelines/{pipelineId}/document` | Pipeline Document for sign-off | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/graph` | Read-only graph of a Pipeline | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| PUT | `/pipelines/{pipelineId}/graph` | Save a Pipeline's edited graph | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/graph/raw` | Editable graph of a Pipeline | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/history` | List a Pipeline's kept config versions, newest first | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| GET | `/pipelines/{pipelineId}/history/{historyId}` | Read one kept config version | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| POST | `/pipelines/{pipelineId}/history/{historyId}/restore` | Restore one kept config version | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| GET | `/pipelines/{pipelineId}/history/diff` | Line diff of two kept versions, or of one against the current config | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| POST | `/pipelines/{pipelineId}/label` | Change a Pipeline's display name | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/{pipelineId}/related` | Everything related to a Pipeline | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineRelatedRoutes.java` |
| POST | `/pipelines/{pipelineId}/rename` | Rename a Pipeline's identity | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java` |
| POST | `/pipelines/{pipelineId}/save-as-template` | Copy a Pipeline into a template | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/{pipelineId}/settings` | Pipeline produces and reference settings | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| POST | `/pipelines/{pipelineId}/settings` | Save Pipeline produces and reference settings | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/authored` | List legacy authored Pipelines | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| DELETE | `/pipelines/authored/{authoredId}` | Delete a legacy authored Pipeline | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/authored/{authoredId}` | One legacy authored Pipeline's graph | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/dry-run` | Dry-run a Pipeline over sample rows | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/authored/{authoredId}/inbox` | List the files waiting in a Pipeline's inbox (dirs.poll) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineInboxRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/inbox` | Upload one file into a Pipeline's inbox (dirs.poll) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineInboxRoutes.java` |
| GET | `/pipelines/authored/{authoredId}/raw` | One legacy authored Pipeline, lossless | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/run` | Run-to-here over real inbox files (scratch only) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/trigger` | Trigger an authored Pipeline run (async) | pipelines | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/combined` | Combined Pipeline and Job topology | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/import` | Import a Pipeline bundle zip | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java` |
| GET | `/pipelines/node-types` | Pipeline node-type catalog | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/processor-catalog` | Full Step Processor taxonomy | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/rename/resume` | Resume an interrupted Pipeline rename | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java` |
| POST | `/promotion/apply` | POST /promotion/apply - apply a previewed change set to this existing Space | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpacePromotionRoutes.java` |
| POST | `/promotion/preview` | POST /promotion/preview - the change set a whole-Space bundle would make in this existing Space | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpacePromotionRoutes.java` |
| GET | `/provenance` | Record counts of one Pipeline Run | provenance | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/provenance/batches` | List recent batches of a Pipeline | provenance | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/public/dashboards/{dashboardId}` | Resolve a shared Dashboard | public | — | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| POST | `/public/dashboards/{dashboardId}/query` | Query the data behind a shared Dashboard | public | — | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| POST | `/public/delivery-status/{deliverystatuId}` | Receive a delivery-status callback | public | — | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| POST | `/queries/{id}/run` | Run a persisted query | Query & Results | — | `inspecto/src/main/java/com/gamma/control/QueryRoutes.java` |
| GET | `/report` | Service-wide Consignment audit report | report | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/requirements` | List Requirements | requirements | — | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements` | Submit a Requirement | requirements | — | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/decision` | Accept or reject a Requirement | requirements | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/deliver` | Mark a Requirement delivered | requirements | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/kpi` | Create a KPI definition from a delivered kpi Requirement | requirements | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/rule-templates/{ruletemplateId}/simulate` | Simulate a Rule Template | rule-templates | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RuleRoutes.java` |
| GET | `/runs` | List registered Pipelines | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs` | Register a Pipeline from a config on disk | runs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/batches` | Consignment ledger of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/commits` | Committed Consignments of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/drain` | Drain a parked Consignment | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/errors` | Rejected rows behind a file's error count | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/files` | Processed files of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/files/stage` | Stage progression of one file | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/lineage` | Lineage rows of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/outputs` | Registered outputs of one Consignment | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/pause` | Pause a Pipeline | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/pending` | Inbox status of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/quarantine` | Quarantined files of a Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/replay-rejects` | Replay one file's rejected records as a new Consignment (EXECUTION-RESIDUALS X4) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/report` | Consignment audit report of one Pipeline | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/reprocess` | Reprocess one Consignment | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/resume` | Resume a paused Pipeline | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/retries` | List one pipeline's pending COMMIT retries (X1 deferrals) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/retries/cancel` | Cancel one file's COMMIT retries: quarantine it now under retry_cancelled (X1 deferrals) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/retries/retry-now` | Retry one file's COMMIT on the next cycle (X1 deferrals) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/trigger` | Trigger a pipeline | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/runs/{runId}` | Poll one manual pipeline run | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/settings/approval` | Read the Space's approval policy | settings | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| PUT | `/settings/approval` | Replace the Space's approval policy | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/settings/approvers` | The Space's approver roster | settings | — | `inspecto/src/main/java/com/gamma/control/ApproverRosterRoutes.java` |
| PUT | `/settings/approvers` | Replace the Space's approver roster | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ApproverRosterRoutes.java` |
| GET | `/settings/branding` | The Space's branding | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/branding` | Replace the Space's branding | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/data` | Read the Space's data settings (data.self_import) | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/data` | Turn Add data on or off for the Space | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/egress` | The Space's egress allowlists | settings | — | `inspecto/src/main/java/com/gamma/control/EgressRoutes.java` |
| PUT | `/settings/egress` | Replace the Space's egress allowlists | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/EgressRoutes.java` |
| GET | `/settings/geo` | The Space's geo settings | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/geo` | Replace the Space's geo settings | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/link-analysis` | The Space's Link Analysis settings | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/link-analysis` | Replace the Space's Link Analysis settings | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/link-analysis/domain-profile` | The Space's Domain Profile: the active Industry pack's terminology and the installed packs | settings | — | `inspecto/src/main/java/com/gamma/control/DomainProfileRoutes.java` |
| PUT | `/settings/link-analysis/domain-profile` | Select the Space's Domain Profile (canAuthorWorkbench) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DomainProfileRoutes.java` |
| GET | `/settings/link-analysis/limits` | The Link Analysis limits catalogue | settings | — | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| PUT | `/settings/link-analysis/limits` | Set the Space's Link Analysis limits | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| GET | `/settings/list-limit` | Read the Space's list limit | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/list-limit` | Set the Space's list limit | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/mail-attachments` | The Space's mail attachment domains | settings | — | `inspecto/src/main/java/com/gamma/control/MailAttachmentRoutes.java` |
| PUT | `/settings/mail-attachments` | Replace the Space's mail attachment domains | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/MailAttachmentRoutes.java` |
| GET | `/settings/modules` | The Space's disabled modules | settings | — | `inspecto/src/main/java/com/gamma/control/ModuleSettingsRoutes.java` |
| PUT | `/settings/modules` | Replace the Space's disabled modules | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ModuleSettingsRoutes.java` |
| GET | `/settings/pipeline-history` | Read the Space's Pipeline config-history retention | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/pipeline-history` | Set the Space's Pipeline config-history retention | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/publication-destinations` | The Space's publication destinations | settings | — | `inspecto/src/main/java/com/gamma/control/PublicationDestinationRoutes.java` |
| PUT | `/settings/publication-destinations` | Replace the Space's publication destinations | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PublicationDestinationRoutes.java` |
| GET | `/settings/safety-policy` | The Space's effective Safety Policy | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SafetyPolicyRoutes.java` |
| GET | `/settings/scheduler` | The Space's scheduler settings | settings | — | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| PUT | `/settings/scheduler` | Update the Space's scheduler settings | settings | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| GET | `/settings/timezone` | Read the Space's default timezone | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/timezone` | Set the Space's default timezone | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/signals` | List Signals | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/count` | Count Signals in a window | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/stream` | Live Signal stream (SSE) | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/tree` | Causation tree of one correlation chain | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| POST | `/space-comparisons` | Compare the storage growth of two or more hosted Spaces | space-comparisons | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceComparisonRoutes.java` |
| GET | `/spaces` | List hosted Spaces | Platform & Spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces` | Create a Space, optionally seeded from a Space Template | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces/_locations/check` | Check a Space folder or data folder | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/spaces/_meta` | Multi-Space capability probe | spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| DELETE | `/spaces/{spaceId}` | Deregister a Space | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| PUT | `/spaces/{spaceId}` | Rename or re-describe a Space | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| PUT | `/spaces/{spaceId}/data-root` | Change the data folder of a Space that holds no data | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces/{spaceId}/detach` | Detach a Space kept outside the Spaces root | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces/attach` | Attach a Space folder kept outside the Spaces root | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces/import` | Create a Space from a bundle zip | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/spaces/templates` | List shipped Space Templates | spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/status` | Status snapshot report | status | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/status/problem-files` | Problem files across all Pipelines | status | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/streams/{streamId}/records` | Push NDJSON or CSV records into a Stream's inbox (ASSURE-PUSH-INGEST-1) | streams | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/StreamPushRoutes.java` |
| PUT | `/system/link-analysis/limits` | Set install-wide Link Analysis limits | system | `canAdminister` | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| GET | `/system/operational-db` | The operational database in use | system | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java` |
| POST | `/system/operational-db/test` | Test an operational database URL | system | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java` |
| GET | `/system/scheduler` | Server-wide scheduler settings | system | — | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| PUT | `/system/scheduler` | Update server-wide scheduler settings | system | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| GET | `/tests` | GET /tests - list the test cases of the bound Space | spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceTestRoutes.java` |
| POST | `/tests/run` | POST /tests/run - run the bound Space's test cases in scratch storage | spaces | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SpaceTestRoutes.java` |
| POST | `/trigger` | Run every Pipeline once | trigger | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/validate` | Validate a config file or draft | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/views` | List recorded view definitions | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |
| GET | `/views/{viewId}` | One view definition | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |
| GET | `/views/{viewId}/data` | Preview a view's rows | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |

## Drift

In the OpenAPI contract, **registered by no module's `api.<method>("…")` call** (may be served by another mechanism):

| Method | Path | Summary |
|---|---|---|
| GET | `/.well-known/oauth-protected-resource` | OAuth protected-resource metadata for the MCP endpoint |
| GET | `/health` | Liveness |
| GET | `/health/details` | Per-subsystem health |
| GET | `/openapi.json` | The OpenAPI contract |
| GET | `/ready` | Readiness probe |
| GET | `/test-discovered/ping` | GET /test-discovered/ping (generated skeleton) |
