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

353 route(s) registered; 353 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| GET | `/access/catalog` | GET /access/catalog (generated skeleton) | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/catalog` | PUT /access/catalog (generated skeleton) | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/explain` | GET /access/explain (generated skeleton) | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/policies` | GET /access/policies (generated skeleton) | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/policies` | PUT /access/policies (generated skeleton) | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| POST | `/access/policies/preview` | Impact matrix for an unsaved Access Policy draft | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/profiles` | GET /access/profiles (generated skeleton) | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| DELETE | `/access/profiles/{profileId}` | DELETE /access/profiles/{profileId} (generated skeleton) | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/profiles/{profileId}` | PUT /access/profiles/{profileId} (generated skeleton) | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/access/roles` | GET /access/roles (generated skeleton) | access | — | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| PUT | `/access/roles` | PUT /access/roles (generated skeleton) | access | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/AccessRoutes.java` |
| GET | `/agent/actions` | GET /agent/actions (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/actions/{actionId}` | GET /agent/actions/{actionId} (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/approvals` | GET /agent/approvals (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/approvals/{approvalId}` | GET /agent/approvals/{approvalId} (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/approvals/{approvalId}/decision` | POST /agent/approvals/{approvalId}/decision (generated skeleton) | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/feedback` | GET /agent/feedback (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/policy` | GET /agent/policy (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| PUT | `/agent/policy` | PUT /agent/policy (generated skeleton) | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/policy/kill-switch` | POST /agent/policy/kill-switch (generated skeleton) | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions` | POST /agent/sessions (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions/{sessionId}/ask` | POST /agent/sessions/{sessionId}/ask (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/sessions/{sessionId}/ask/stream` | POST /agent/sessions/{sessionId}/ask/stream (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/tools/{toolId}` | POST /agent/tools/{toolId} (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/tools/{toolId}/derive` | POST /agent/tools/{toolId}/derive (generated skeleton) | agent | — | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs` | GET /agent/triage-runs (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs/{triageRunId}` | GET /agent/triage-runs/{triageRunId} (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| POST | `/agent/triage-runs/{triageRunId}/feedback` | POST /agent/triage-runs/{triageRunId}/feedback (generated skeleton) | agent | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/agent/triage-runs/{triageRunId}/similar` | GET /agent/triage-runs/{triageRunId}/similar (generated skeleton) | agent | `canRevealLinkEntities` | `inspecto/src/main/java/com/gamma/control/AgentRoutes.java` |
| GET | `/alerts` | GET /alerts (generated skeleton) | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/{alertId}/ack` | POST /alerts/{alertId}/ack (generated skeleton) | alerts | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/{alertId}/resolve` | POST /alerts/{alertId}/resolve (generated skeleton) | alerts | `canWorkIncidents` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/evaluate` | POST /alerts/evaluate (generated skeleton) | alerts | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/alerts/rules` | GET /alerts/rules (generated skeleton) | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| POST | `/alerts/rules` | POST /alerts/rules (generated skeleton) | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| DELETE | `/alerts/rules/{ruleId}` | DELETE /alerts/rules/{ruleId} (generated skeleton) | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| PUT | `/alerts/rules/{ruleId}` | PUT /alerts/rules/{ruleId} (generated skeleton) | alerts | `canAuthorAlertRules` | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/alerts/rules/pending` | GET /alerts/rules/pending (generated skeleton) | alerts | — | `inspecto/src/main/java/com/gamma/control/AlertRoutes.java` |
| GET | `/analytics/functions` | The Analytics function catalogue: every function the Analytics Kernel offers | analytics | — | `inspecto/src/main/java/com/gamma/control/AnalyticsCatalogueRoutes.java` |
| POST | `/assist/{assistId}` | POST /assist/{assistId} (generated skeleton) | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/diagnoses` | GET /assist/diagnoses (generated skeleton) | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/metrics` | GET /assist/metrics (generated skeleton) | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/settings` | GET /assist/settings (generated skeleton) | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| POST | `/assist/settings` | POST /assist/settings (generated skeleton) | assist | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| POST | `/assist/settings/test` | POST /assist/settings/test (generated skeleton) | assist | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/assist/skills` | GET /assist/skills (generated skeleton) | assist | — | `inspecto/src/main/java/com/gamma/control/AssistRoutes.java` |
| GET | `/audit/anchors` | GET /audit/anchors (generated skeleton) | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/audit/anchors` | POST /audit/anchors (generated skeleton) | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/audit/anchors/rebaseline` | POST /audit/anchors/rebaseline (generated skeleton) | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/export` | GET /audit/export (generated skeleton) | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/route-inventory` | The route inventory this server registered | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/search` | GET /audit/search (generated skeleton) | audit | — | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| GET | `/audit/verify` | GET /audit/verify (generated skeleton) | audit | `canAdminister` | `inspecto/src/main/java/com/gamma/control/AuditLogRoutes.java` |
| POST | `/auth/exchange` | Redeem an OIDC authorization code (backend-mediated session) | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/auth/logout` | End the session | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/auth/refresh` | Mint a fresh access token from the session cookie | Session & Identity | — | `inspecto/src/main/java/com/gamma/control/AuthRoutes.java` |
| POST | `/bi/query` | POST /bi/query (generated skeleton) | bi | — | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| GET | `/bi/templates` | GET /bi/templates (generated skeleton) | bi | — | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| POST | `/bi/templates/{templateId}/apply` | POST /bi/templates/{templateId}/apply (generated skeleton) | bi | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BiRoutes.java` |
| GET | `/bootstrap` | Platform bootstrap — everything the backend owns to start the UI | Platform & Spaces | — | `inspecto/src/main/java/com/gamma/control/BootstrapRoutes.java` |
| POST | `/bundle/export` | POST /bundle/export (generated skeleton) | bundle | — | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| POST | `/bundle/import` | POST /bundle/import (generated skeleton) | bundle | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| POST | `/bundle/preview` | Read-only fit-check of a Metadata Bundle against this Space | bundle | — | `inspecto/src/main/java/com/gamma/control/BundleRoutes.java` |
| GET | `/catalog` | GET /catalog (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/graph` | GET /catalog/graph (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/kpis` | GET /catalog/kpis (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/references` | GET /catalog/references (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/resolve` | GET /catalog/resolve (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/streams` | GET /catalog/streams (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/catalog/tables/{tableId}` | GET /catalog/tables/{tableId} (generated skeleton) | catalog | — | `inspecto/src/main/java/com/gamma/control/CatalogRoutes.java` |
| GET | `/collectors` | GET /collectors (generated skeleton) | collectors | — | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| POST | `/collectors/{collectorId}/notify` | POST /collectors/{collectorId}/notify (generated skeleton) | collectors | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| GET | `/components/{componentId}` | GET /components/{componentId} (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/{componentId}` | POST /components/{componentId} (generated skeleton) | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/{type}/{id}` | DELETE /components/{type}/{id} (generated skeleton) | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| GET | `/components/{type}/{id}` | One registry component | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/{type}/{id}` | Replace a registry component | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| GET | `/components/{componentId}/{componentId2}/versions` | GET /components/{componentId}/{componentId2}/versions (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/{componentId}/{componentId2}/versions/{versionId}/restore` | POST /components/{componentId}/{componentId2}/versions/{versionId}/restore (generated skeleton) | components | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/escalation-rule` | POST /components/escalation-rule | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/escalation-rule/{escalationruleId}` | DELETE /components/escalation-rule/{escalationruleId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/escalation-rule/{escalationruleId}` | PUT /components/escalation-rule/{escalationruleId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/escalation-rule/{escalationruleId}/versions/{versionId}/restore` | POST /components/escalation-rule/{escalationruleId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/findings-spec` | POST /components/findings-spec (generated skeleton) | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/findings-spec/{findingsspecId}` | DELETE /components/findings-spec/{findingsspecId} (generated skeleton) | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/findings-spec/{findingsspecId}` | PUT /components/findings-spec/{findingsspecId} (generated skeleton) | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/findings-spec/{findingsspecId}/versions/{versionId}/restore` | POST /components/findings-spec/{findingsspecId}/versions/{versionId}/restore (generated skeleton) | components | `canManageIncidents` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/grammar/{grammarId}/test` | POST /components/grammar/{grammarId}/test (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/grammar/preview` | POST /components/grammar/preview (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/mapping/validate` | POST /components/mapping/validate (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sink/{sinkId}/test` | POST /components/sink/{sinkId}/test (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sink/preview` | POST /components/sink/preview (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sla-policy` | POST /components/sla-policy | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/sla-policy/{slapolicyId}` | DELETE /components/sla-policy/{slapolicyId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/sla-policy/{slapolicyId}` | PUT /components/sla-policy/{slapolicyId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sla-policy/{slapolicyId}/versions/{versionId}/restore` | POST /components/sla-policy/{slapolicyId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/sql/ast` | POST /components/sql/ast (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/{transformId}/test` | POST /components/transform/{transformId}/test (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/describe` | POST /components/transform/describe (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/transform/preview` | POST /components/transform/preview (generated skeleton) | components | — | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/workflow` | POST /components/workflow | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/components/workflow/{workflowId}` | DELETE /components/workflow/{workflowId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| PUT | `/components/workflow/{workflowId}` | PUT /components/workflow/{workflowId} | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| POST | `/components/workflow/{workflowId}/versions/{versionId}/restore` | POST /components/workflow/{workflowId}/versions/{versionId}/restore | components | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ComponentRoutes.java` |
| DELETE | `/config/{type}/{name}` | Discard a config file (draft delete) | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| GET | `/config/{type}/{name}` | Read a config file back (decoded) | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| GET | `/config/icon-map` | GET /config/icon-map (generated skeleton) | config | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/config/icon-map` | PUT /config/icon-map (generated skeleton) | config | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| POST | `/config/patch` | POST /config/patch (generated skeleton) | config | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java` |
| GET | `/config/pipeline/{pipelineId}/impact` | GET /config/pipeline/{pipelineId}/impact (generated skeleton) | config | — | `inspecto/src/main/java/com/gamma/control/ConfigReadRoutes.java` |
| POST | `/config/preview/parsing` | Parse a raw sample with a draft's parsing settings | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/preview/schema` | TRY_CAST already-parsed sample rows against a draft schema's typed fields | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/config/schema/derived` | GET /config/schema/derived (generated skeleton) | config | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/config/spec/{type}` | Declarative spec for a config type | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/suggest/schema` | POST /config/suggest/schema (generated skeleton) | config | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| POST | `/config/write` | Persist a validated config draft | Metadata & Catalog | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ConfigWriteRoutes.java` |
| GET | `/connections` | List Connection profiles | Acquisition | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections` | Create a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| DELETE | `/connections/{id}` | Delete a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{id}` | One Connection profile | Acquisition | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| PUT | `/connections/{id}` | Replace a Connection profile | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{connectionId}/explore` | GET /connections/{connectionId}/explore (generated skeleton) | connections | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/{connectionId}/probe` | POST /connections/{connectionId}/probe (generated skeleton) | connections | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| GET | `/connections/{connectionId}/sample` | GET /connections/{connectionId}/sample (generated skeleton) | connections | — | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/{id}/test` | Test a Connection's reachability | Acquisition | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/connections/test` | POST /connections/test (generated skeleton) | connections | `canOnboardConnections` | `inspecto/src/main/java/com/gamma/control/ConnectionRoutes.java` |
| POST | `/dashboards/{dashboardId}/share` | POST /dashboards/{dashboardId}/share (generated skeleton) | dashboards | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| PUT | `/datasets/{datasetId}/columns/{columnId}` | Change which Entity Type one Dataset column holds | datasets | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/datasets/{datasetId}/files` | Add a file to an imported Dataset and run it | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/datasets/{datasetId}/freshness` | GET /datasets/{datasetId}/freshness (generated skeleton) | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| POST | `/datasets/{datasetId}/materialize` | POST /datasets/{datasetId}/materialize (generated skeleton) | datasets | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| GET | `/datasets/{datasetId}/rows` | GET /datasets/{datasetId}/rows | datasets | — | `inspecto/src/main/java/com/gamma/control/DatasetRoutes.java` |
| POST | `/datasets/{datasetId}/run` | Run now: run an imported Dataset's Pipeline over what waits in its inbox | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/datasources` | GET /datasources (generated skeleton) | datasources | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/datasources/{datasourceId}/export` | GET /datasources/{datasourceId}/export (generated skeleton) | datasources | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/db/catalog` | GET /db/catalog (generated skeleton) | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| POST | `/db/query` | POST /db/query (generated skeleton) | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| GET | `/db/table` | GET /db/table (generated skeleton) | db | — | `inspecto/src/main/java/com/gamma/control/DbBrowserRoutes.java` |
| GET | `/decision-rules` | GET /decision-rules (generated skeleton) | decision-rules | — | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules` | POST /decision-rules (generated skeleton) | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| DELETE | `/decision-rules/{decisionruleId}` | DELETE /decision-rules/{decisionruleId} (generated skeleton) | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| PUT | `/decision-rules/{decisionruleId}` | PUT /decision-rules/{decisionruleId} (generated skeleton) | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules/{decisionruleId}/apply` | POST /decision-rules/{decisionruleId}/apply (generated skeleton) | decision-rules | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| POST | `/decision-rules/{decisionruleId}/simulate` | POST /decision-rules/{decisionruleId}/simulate (generated skeleton) | decision-rules | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| GET | `/decision-rules/consequences` | GET /decision-rules/consequences (generated skeleton) | decision-rules | — | `inspecto/src/main/java/com/gamma/control/DecisionRoutes.java` |
| GET | `/enrichment` | GET /enrichment (generated skeleton) | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| POST | `/enrichment` | POST /enrichment (generated skeleton) | enrichment | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/lineage` | GET /enrichment/{enrichmentId}/lineage (generated skeleton) | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/report` | GET /enrichment/{enrichmentId}/report (generated skeleton) | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/enrichment/{enrichmentId}/runs` | GET /enrichment/{enrichmentId}/runs (generated skeleton) | enrichment | — | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| POST | `/enrichment/preview` | POST /enrichment/preview (generated skeleton) | enrichment | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/EnrichmentRoutes.java` |
| GET | `/expectations` | GET /expectations (generated skeleton) | expectations | — | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations` | POST /expectations (generated skeleton) | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| DELETE | `/expectations/{expectationId}` | DELETE /expectations/{expectationId} (generated skeleton) | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| PUT | `/expectations/{expectationId}` | PUT /expectations/{expectationId} (generated skeleton) | expectations | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/baseline/accept` | POST /expectations/{expectationId}/baseline/accept (generated skeleton) | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/baseline/clear` | POST /expectations/{expectationId}/baseline/clear (generated skeleton) | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/{expectationId}/evaluate` | POST /expectations/{expectationId}/evaluate (generated skeleton) | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| GET | `/expectations/breached-count` | GET /expectations/breached-count (generated skeleton) | expectations | — | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| POST | `/expectations/evaluate` | POST /expectations/evaluate (generated skeleton) | expectations | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/ExpectationRoutes.java` |
| GET | `/export` | GET /export (generated skeleton) | export | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| POST | `/import` | POST /import (generated skeleton) | import | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| POST | `/import/preview` | POST /import/preview (generated skeleton) | import | — | `inspecto/src/main/java/com/gamma/control/DataSourceRoutes.java` |
| GET | `/jobs` | GET /jobs (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs` | POST /jobs (generated skeleton) | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| DELETE | `/jobs/{jobId}` | DELETE /jobs/{jobId} (generated skeleton) | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}` | GET /jobs/{jobId} (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| PUT | `/jobs/{jobId}` | PUT /jobs/{jobId} (generated skeleton) | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/artifacts/latest` | GET /jobs/{jobId}/artifacts/latest (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/disable` | POST /jobs/{jobId}/disable (generated skeleton) | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/enable` | POST /jobs/{jobId}/enable (generated skeleton) | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{jobId}/reschedule` | POST /jobs/{jobId}/reschedule (generated skeleton) | jobs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs` | GET /jobs/{jobId}/runs (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/artifacts` | GET /jobs/{jobId}/runs/{runId}/artifacts (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/artifacts/{artifactId}/content` | GET /jobs/{jobId}/runs/{runId}/artifacts/{artifactId}/content (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/diff/{diffId}` | GET /jobs/{jobId}/runs/{runId}/diff/{diffId} (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/log` | GET /jobs/{jobId}/runs/{runId}/log (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/{jobId}/runs/{runId}/logs` | GET /jobs/{jobId}/runs/{runId}/logs (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/{name}/trigger` | Trigger a job (async) | Execution & Scheduling | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/expressions` | GET /jobs/expressions (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/failures` | GET /jobs/failures (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/metrics` | GET /jobs/metrics (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/packs` | GET /jobs/packs (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/packs/rescan` | POST /jobs/packs/rescan (generated skeleton) | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/processors` | GET /jobs/processors (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/runs` | GET /jobs/runs (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/runs/{runId}` | Poll one run by id | Execution & Scheduling | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| POST | `/jobs/runs/{runId}/replay` | POST /jobs/runs/{runId}/replay (generated skeleton) | jobs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/types` | GET /jobs/types (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/jobs/types/{typeId}` | GET /jobs/types/{typeId} (generated skeleton) | jobs | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/kpis/{kpiId}/value` | Evaluate a KPI definition for the period to date | kpis | — | `inspecto/src/main/java/com/gamma/control/KpiRoutes.java` |
| GET | `/lineage` | GET /lineage (generated skeleton) | lineage | — | `inspecto/src/main/java/com/gamma/control/LineageRoutes.java` |
| GET | `/metrics/acquisition` | GET /metrics/acquisition (generated skeleton) | metrics | — | `inspecto/src/main/java/com/gamma/control/AcquisitionRoutes.java` |
| GET | `/modules` | GET /modules (generated skeleton) | modules | — | `inspecto/src/main/java/com/gamma/control/ModulesRoutes.java` |
| GET | `/nav/menus` | GET /nav/menus (generated skeleton) | nav | — | `inspecto/src/main/java/com/gamma/control/NavRoutes.java` |
| PUT | `/nav/menus` | PUT /nav/menus (generated skeleton) | nav | `canCurateMenus` | `inspecto/src/main/java/com/gamma/control/NavRoutes.java` |
| GET | `/notifications` | GET /notifications (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/{notificationId}` | DELETE /notifications/{notificationId} (generated skeleton) | notifications | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/{notificationId}/read` | POST /notifications/{notificationId}/read (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/{notificationId}/unread` | POST /notifications/{notificationId}/unread (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/channels` | GET /notifications/channels (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/channels` | POST /notifications/channels (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/channels/{channelId}` | DELETE /notifications/channels/{channelId} (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/channels/{channelId}` | PUT /notifications/channels/{channelId} (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/deliveries` | GET /notifications/deliveries (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/preferences` | The caller’s effective notification preference grid | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/preferences` | Save the caller’s own preference override (self-service) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/preferences/default` | The deployment-default notification preference grid | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/preferences/default` | Edit the deployment-default grid (canAdminister) | notifications | `canAdminister` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/read-all` | POST /notifications/read-all (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/rules` | GET /notifications/rules (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/notifications/rules` | POST /notifications/rules (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/rules/{ruleId}` | DELETE /notifications/rules/{ruleId} (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| PUT | `/notifications/rules/{ruleId}` | PUT /notifications/rules/{ruleId} (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| GET | `/notifications/stream` | GET /notifications/stream (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| DELETE | `/notifications/suppressions` | DELETE /notifications/suppressions (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/suppressions` | GET /notifications/suppressions (generated skeleton) | notifications | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| GET | `/notifications/unread-count` | GET /notifications/unread-count (generated skeleton) | notifications | — | `inspecto/src/main/java/com/gamma/control/NotificationRoutes.java` |
| POST | `/onboard/files` | Add data: upload a file and get a draft of how to import it | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboard/files/{fileId}/commit` | Add data: one confirm - write the import Pipeline, run it once, register the Dataset | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| GET | `/onboard/runs/{runId}` | Follow an import run answered with 202 | datasets | `canUploadData` | `inspecto/src/main/java/com/gamma/control/OnboardRoutes.java` |
| POST | `/onboarding/events` | Report a step only the browser sees (LA-ONBOARD-1 OB-13) | — | — | `inspecto/src/main/java/com/gamma/control/OnboardingRoutes.java` |
| GET | `/onboarding/metrics` | What the onboarding counters say (LA-ONBOARD-1 OB-13) | — | `canAdminister` | `inspecto/src/main/java/com/gamma/control/OnboardingRoutes.java` |
| GET | `/parsers` | GET /parsers (generated skeleton) | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| POST | `/parsers/{parserId}/preview` | POST /parsers/{parserId}/preview (generated skeleton) | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| GET | `/parsers/asn1/profile` | GET /parsers/asn1/profile (generated skeleton) | parsers | — | `inspecto/src/main/java/com/gamma/control/ParserRoutes.java` |
| GET | `/pending-changes` | List the Space's Pending Changes | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pending-changes/{pendingchangeId}` | Read one Pending Change with its content | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/approve` | Approve a Pending Change and apply it | pending-changes | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/decline` | Decline a Pending Change | pending-changes | `canApproveChanges` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pending-changes/{pendingchangeId}/diff` | Line diff of a Pending Change | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| POST | `/pending-changes/{pendingchangeId}/withdraw` | Withdraw your own Pending Change | pending-changes | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/pipelines` | List the registered Pipelines, broken ones included | Pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/{pipelineId}/bundle` | GET /pipelines/{pipelineId}/bundle (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java` |
| GET | `/pipelines/{pipelineId}/document` | GET /pipelines/{pipelineId}/document (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/graph` | GET /pipelines/{pipelineId}/graph (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| PUT | `/pipelines/{pipelineId}/graph` | PUT /pipelines/{pipelineId}/graph (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/graph/raw` | GET /pipelines/{pipelineId}/graph/raw (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/{pipelineId}/history` | List a Pipeline's kept config versions, newest first | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| GET | `/pipelines/{pipelineId}/history/{historyId}` | Read one kept config version | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| POST | `/pipelines/{pipelineId}/history/{historyId}/restore` | Restore one kept config version | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| GET | `/pipelines/{pipelineId}/history/diff` | Line diff of two kept versions, or of one against the current config | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineHistoryRoutes.java` |
| POST | `/pipelines/{pipelineId}/label` | POST /pipelines/{pipelineId}/label (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/{pipelineId}/related` | GET /pipelines/{pipelineId}/related (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineRelatedRoutes.java` |
| POST | `/pipelines/{pipelineId}/rename` | POST /pipelines/{pipelineId}/rename (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java` |
| POST | `/pipelines/{pipelineId}/save-as-template` | POST /pipelines/{pipelineId}/save-as-template (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/{pipelineId}/settings` | GET /pipelines/{pipelineId}/settings (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| POST | `/pipelines/{pipelineId}/settings` | POST /pipelines/{pipelineId}/settings (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineSettingsRoutes.java` |
| GET | `/pipelines/authored` | GET /pipelines/authored (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| DELETE | `/pipelines/authored/{authoredId}` | DELETE /pipelines/authored/{authoredId} (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/authored/{authoredId}` | GET /pipelines/authored/{authoredId} (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/dry-run` | POST /pipelines/authored/{authoredId}/dry-run (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/authored/{authoredId}/inbox` | List the files waiting in a Pipeline's inbox (dirs.poll) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineInboxRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/inbox` | Upload one file into a Pipeline's inbox (dirs.poll) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineInboxRoutes.java` |
| GET | `/pipelines/authored/{authoredId}/raw` | GET /pipelines/authored/{authoredId}/raw (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/run` | POST /pipelines/authored/{authoredId}/run (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| POST | `/pipelines/authored/{authoredId}/trigger` | POST /pipelines/authored/{authoredId}/trigger (generated skeleton) | pipelines | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/PipelineGraphRoutes.java` |
| GET | `/pipelines/combined` | GET /pipelines/combined (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/import` | POST /pipelines/import (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineBundleRoutes.java` |
| GET | `/pipelines/node-types` | GET /pipelines/node-types (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| GET | `/pipelines/processor-catalog` | GET /pipelines/processor-catalog (generated skeleton) | pipelines | — | `inspecto/src/main/java/com/gamma/control/PipelineListRoutes.java` |
| POST | `/pipelines/rename/resume` | POST /pipelines/rename/resume (generated skeleton) | pipelines | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/PipelineRenameRoutes.java` |
| GET | `/provenance` | GET /provenance (generated skeleton) | provenance | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/provenance/batches` | GET /provenance/batches (generated skeleton) | provenance | — | `inspecto/src/main/java/com/gamma/control/JobRoutes.java` |
| GET | `/public/dashboards/{dashboardId}` | GET /public/dashboards/{dashboardId} (generated skeleton) | public | — | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| POST | `/public/dashboards/{dashboardId}/query` | POST /public/dashboards/{dashboardId}/query (generated skeleton) | public | — | `inspecto/src/main/java/com/gamma/control/ShareRoutes.java` |
| POST | `/public/delivery-status/{deliverystatuId}` | POST /public/delivery-status/{deliverystatuId} (generated skeleton) | public | — | `inspecto/src/main/java/com/gamma/control/DeliveryStatusRoutes.java` |
| POST | `/queries/{id}/run` | Run a persisted query | Query & Results | — | `inspecto/src/main/java/com/gamma/control/QueryRoutes.java` |
| GET | `/report` | GET /report (generated skeleton) | report | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/requirements` | GET /requirements (generated skeleton) | requirements | — | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements` | POST /requirements (generated skeleton) | requirements | — | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/decision` | POST /requirements/{requirementId}/decision (generated skeleton) | requirements | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/deliver` | POST /requirements/{requirementId}/deliver (generated skeleton) | requirements | `canTriageRequirements` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/requirements/{requirementId}/kpi` | Create a KPI definition from a delivered kpi Requirement | requirements | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RequirementRoutes.java` |
| POST | `/rule-templates/{ruletemplateId}/simulate` | POST /rule-templates/{ruletemplateId}/simulate (generated skeleton) | rule-templates | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RuleRoutes.java` |
| GET | `/runs` | GET /runs (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs` | POST /runs (generated skeleton) | runs | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/batches` | GET /runs/{runId}/batches (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/commits` | GET /runs/{runId}/commits (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/drain` | POST /runs/{runId}/drain (generated skeleton) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/errors` | GET /runs/{runId}/errors (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/files` | GET /runs/{runId}/files (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/files/stage` | GET /runs/{runId}/files/stage (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/lineage` | GET /runs/{runId}/lineage (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/outputs` | GET /runs/{runId}/outputs (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/pause` | POST /runs/{runId}/pause (generated skeleton) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/pending` | GET /runs/{runId}/pending (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/quarantine` | GET /runs/{runId}/quarantine (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/replay-rejects` | Replay one file's rejected records as a new Consignment (EXECUTION-RESIDUALS X4) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/report` | GET /runs/{runId}/report (generated skeleton) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/reprocess` | POST /runs/{runId}/reprocess (generated skeleton) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/resume` | POST /runs/{runId}/resume (generated skeleton) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/{runId}/retries` | List one pipeline's pending COMMIT retries (X1 deferrals) | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/retries/cancel` | Cancel one file's COMMIT retries: quarantine it now under retry_cancelled (X1 deferrals) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/retries/retry-now` | Retry one file's COMMIT on the next cycle (X1 deferrals) | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/runs/{runId}/trigger` | Trigger a pipeline | runs | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/runs/runs/{runId}` | Poll one manual pipeline run | runs | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/settings/approval` | Read the Space's approval policy | settings | — | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| PUT | `/settings/approval` | Replace the Space's approval policy | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PendingChangeRoutes.java` |
| GET | `/settings/approvers` | GET /settings/approvers (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/ApproverRosterRoutes.java` |
| PUT | `/settings/approvers` | PUT /settings/approvers (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ApproverRosterRoutes.java` |
| GET | `/settings/branding` | GET /settings/branding (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/branding` | PUT /settings/branding (generated skeleton) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/data` | Read the Space's data settings (data.self_import) | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/data` | Turn Add data on or off for the Space | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/egress` | GET /settings/egress (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/EgressRoutes.java` |
| PUT | `/settings/egress` | PUT /settings/egress (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/EgressRoutes.java` |
| GET | `/settings/geo` | GET /settings/geo (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/geo` | PUT /settings/geo (generated skeleton) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/link-analysis` | GET /settings/link-analysis (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/link-analysis` | PUT /settings/link-analysis (generated skeleton) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/link-analysis/domain-profile` | The Space's Domain Profile: the active Industry pack's terminology and the installed packs | settings | — | `inspecto/src/main/java/com/gamma/control/DomainProfileRoutes.java` |
| PUT | `/settings/link-analysis/domain-profile` | Select the Space's Domain Profile (canAuthorWorkbench) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/DomainProfileRoutes.java` |
| GET | `/settings/link-analysis/limits` | GET /settings/link-analysis/limits (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| PUT | `/settings/link-analysis/limits` | PUT /settings/link-analysis/limits (generated skeleton) | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| GET | `/settings/mail-attachments` | GET /settings/mail-attachments (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/MailAttachmentRoutes.java` |
| PUT | `/settings/mail-attachments` | PUT /settings/mail-attachments (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/MailAttachmentRoutes.java` |
| GET | `/settings/modules` | GET /settings/modules (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/ModuleSettingsRoutes.java` |
| PUT | `/settings/modules` | PUT /settings/modules (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/ModuleSettingsRoutes.java` |
| GET | `/settings/pipeline-history` | Read the Space's Pipeline config-history retention | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/pipeline-history` | Set the Space's Pipeline config-history retention | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/settings/publication-destinations` | GET /settings/publication-destinations (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/PublicationDestinationRoutes.java` |
| PUT | `/settings/publication-destinations` | PUT /settings/publication-destinations (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/PublicationDestinationRoutes.java` |
| GET | `/settings/safety-policy` | GET /settings/safety-policy (generated skeleton) | settings | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SafetyPolicyRoutes.java` |
| GET | `/settings/scheduler` | GET /settings/scheduler (generated skeleton) | settings | — | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| PUT | `/settings/scheduler` | PUT /settings/scheduler (generated skeleton) | settings | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| GET | `/settings/timezone` | Read the Space's default timezone | settings | — | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| PUT | `/settings/timezone` | Set the Space's default timezone | settings | `canAuthorWorkbench` | `inspecto/src/main/java/com/gamma/control/SettingsRoutes.java` |
| GET | `/signals` | GET /signals (generated skeleton) | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/count` | GET /signals/count (generated skeleton) | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/stream` | GET /signals/stream (generated skeleton) | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| GET | `/signals/tree` | GET /signals/tree (generated skeleton) | signals | — | `inspecto/src/main/java/com/gamma/control/SignalRoutes.java` |
| POST | `/space-comparisons` | Compare the storage growth of two or more hosted Spaces | space-comparisons | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceComparisonRoutes.java` |
| GET | `/spaces` | List hosted Spaces | Platform & Spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces` | Create a Space, optionally seeded from a Space Template | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/spaces/_meta` | GET /spaces/_meta (generated skeleton) | spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| DELETE | `/spaces/{spaceId}` | DELETE /spaces/{spaceId} (generated skeleton) | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| PUT | `/spaces/{spaceId}` | PUT /spaces/{spaceId} (generated skeleton) | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| POST | `/spaces/import` | POST /spaces/import (generated skeleton) | spaces | `canAdminister` | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/spaces/templates` | GET /spaces/templates (generated skeleton) | spaces | — | `inspecto/src/main/java/com/gamma/control/SpaceRoutes.java` |
| GET | `/status` | GET /status (generated skeleton) | status | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| GET | `/status/problem-files` | GET /status/problem-files (generated skeleton) | status | — | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/streams/{streamId}/records` | Push NDJSON or CSV records into a Stream's inbox (ASSURE-PUSH-INGEST-1) | streams | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/StreamPushRoutes.java` |
| PUT | `/system/link-analysis/limits` | PUT /system/link-analysis/limits (generated skeleton) | system | `canAdminister` | `inspecto/src/main/java/com/gamma/control/LinkAnalysisLimitsRoutes.java` |
| GET | `/system/operational-db` | GET /system/operational-db (generated skeleton) | system | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java` |
| POST | `/system/operational-db/test` | POST /system/operational-db/test (generated skeleton) | system | `canConfigureAccess` | `inspecto/src/main/java/com/gamma/control/SystemRoutes.java` |
| GET | `/system/scheduler` | GET /system/scheduler (generated skeleton) | system | — | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| PUT | `/system/scheduler` | PUT /system/scheduler (generated skeleton) | system | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/SchedulerRoutes.java` |
| POST | `/trigger` | POST /trigger (generated skeleton) | trigger | `canOperateRuns` | `inspecto/src/main/java/com/gamma/control/RunRoutes.java` |
| POST | `/validate` | Validate a config file or draft | Metadata & Catalog | — | `inspecto/src/main/java/com/gamma/control/ConfigPreviewRoutes.java` |
| GET | `/views` | GET /views (generated skeleton) | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |
| GET | `/views/{viewId}` | GET /views/{viewId} (generated skeleton) | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |
| GET | `/views/{viewId}/data` | GET /views/{viewId}/data (generated skeleton) | views | — | `inspecto/src/main/java/com/gamma/control/ViewRoutes.java` |

## Drift

In the OpenAPI contract, **registered by no module's `api.<method>("…")` call** (may be served by another mechanism):

| Method | Path | Summary |
|---|---|---|
| GET | `/health` | Liveness |
| GET | `/health/details` | GET /health/details (generated skeleton) |
| GET | `/openapi.json` | GET /openapi.json (generated skeleton) |
| GET | `/ready` | GET /ready (generated skeleton) |
| GET | `/test-discovered/ping` | GET /test-discovered/ping (generated skeleton) |
