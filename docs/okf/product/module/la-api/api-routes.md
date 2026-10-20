---
type: Concept
title: HTTP routes — module.la-api
description: Every /api/v1 route the la-api module registers, joined with the OpenAPI contract. Generated.
tags: [api, routes, generated]
---

# HTTP routes — `module.la-api`

> **GENERATED** by `tools/render-api-routes.mjs` from the Java route registrations under this module's
> `src/main` and [`docs/api/openapi-v1.json`](../../../../api/openapi-v1.json) (the HTTP contract, base `/api/v1`).
> Never edit by hand — change the code or the contract and re-run the generator.

90 route(s) registered; 90 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| POST | `/geo/projection` | POST /geo/projection (generated skeleton) | geo | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java` |
| POST | `/geo/routes` | POST /geo/routes (generated skeleton) | geo | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java` |
| POST | `/inv/case-history` | The Incidents and Cases referencing one Entity, for a key that may be a masking alias | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/CaseHistoryRoutes.java` |
| GET | `/inv/datasets/{datasetId}/files` | List the Files recorded towards a Dataset, with each File's index state | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DatasetFilesRoutes.java` |
| POST | `/inv/deep-links/resolve` | Validate an Open in Inspecto LA link's parameters | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationInboundRoutes.java` |
| GET | `/inv/entities/{entityType}/{entityKey}/profile` | Read one Entity's profile by raw value or exploration alias | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| POST | `/inv/entities/{entityType}/resolve` | Resolve a raw value or an alias to the key an Entity profile URL takes | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| GET | `/inv/entity-identities` | GET /inv/entity-identities (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities` | POST /inv/entity-identities (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities/{entityidentityId}/retract` | POST /inv/entity-identities/{entityidentityId}/retract (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| GET | `/inv/entity-identities/group` | GET /inv/entity-identities/group (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities/import` | POST /inv/entity-identities/import (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| GET | `/inv/entity-types` | List the Entity Types in force with their bindings and cached headline | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| GET | `/inv/entity-types/{entityTypeId}/profile` | Read the Entity Type profile: aggregates over every column bound to the type | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| GET | `/inv/graph/algorithms` | The graph-algorithm catalogue and run limits | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| GET | `/inv/graph/runs` | List the caller's graph runs | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/graph/runs` | Start a graph run over an Investigation's Working Set | inv | `canRunLinkGraphAnalysis` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| GET | `/inv/graph/runs/{runId}` | Read one graph run | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/graph/runs/{runId}/cancel` | Cancel a graph run | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/inbound/investigations` | Third-party integration (inbound): open a seeded Investigation for an external record | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationInboundRoutes.java` |
| GET | `/inv/index` | List the edge/node indexes of the caller's viewable Datasets | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| POST | `/inv/index/builds` | Start an edge/node index build over a Dataset | inv | `canBuildLinkIndex` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| GET | `/inv/index/builds/{buildId}` | Read one index build | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| POST | `/inv/index/builds/{buildId}/cancel` | Cancel an index build | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| GET | `/inv/integration` | The Space's third-party integration settings (outbound) | inv | `canAdminister` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| PUT | `/inv/integration` | Replace the Space's third-party integration settings | inv | `canAdminister` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| GET | `/inv/investigation-templates` | GET /inv/investigation-templates (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| GET | `/inv/investigation-templates/{investigationtemplateId}` | GET /inv/investigation-templates/{investigationtemplateId} (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| POST | `/inv/investigation-templates/{investigationtemplateId}/instantiate` | POST /inv/investigation-templates/{investigationtemplateId}/instantiate (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| GET | `/inv/investigations` | GET /inv/investigations (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations` | POST /inv/investigations (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| DELETE | `/inv/investigations/{investigationId}/case` | DELETE /inv/investigations/{investigationId}/case (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| GET | `/inv/investigations/{investigationId}/case` | GET /inv/investigations/{investigationId}/case (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| PUT | `/inv/investigations/{investigationId}/case` | PUT /inv/investigations/{investigationId}/case (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| GET | `/inv/investigations/{investigationId}/compare` | GET /inv/investigations/{investigationId}/compare (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationComparisonRoutes.java` |
| GET | `/inv/investigations/{investigationId}/coverage` | GET /inv/investigations/{investigationId}/coverage (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCoverageRoutes.java` |
| GET | `/inv/investigations/{investigationId}/dossier` | GET /inv/investigations/{investigationId}/dossier (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierRoutes.java` |
| GET | `/inv/investigations/{investigationId}/dossier/bundle` | GET /inv/investigations/{investigationId}/dossier/bundle (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierBundleRoutes.java` |
| POST | `/inv/investigations/{investigationId}/dossier/bundle/verify` | POST /inv/investigations/{investigationId}/dossier/bundle/verify (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierBundleRoutes.java` |
| POST | `/inv/investigations/{investigationId}/dossier/verify` | POST /inv/investigations/{investigationId}/dossier/verify (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts` | GET /inv/investigations/{investigationId}/drafts (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts` | POST /inv/investigations/{investigationId}/drafts (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}` | GET /inv/investigations/{investigationId}/drafts/{draftId} (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/conflicts` | GET /inv/investigations/{investigationId}/drafts/{draftId}/conflicts (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/discard` | POST /inv/investigations/{investigationId}/drafts/{draftId}/discard (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/log` | GET /inv/investigations/{investigationId}/drafts/{draftId}/log (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/ops` | POST /inv/investigations/{investigationId}/drafts/{draftId}/ops (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/promote` | POST /inv/investigations/{investigationId}/drafts/{draftId}/promote (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/rebase` | POST /inv/investigations/{investigationId}/drafts/{draftId}/rebase (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/replay` | GET /inv/investigations/{investigationId}/drafts/{draftId}/replay (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/undo` | POST /inv/investigations/{investigationId}/drafts/{draftId}/undo (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/working-set` | GET /inv/investigations/{investigationId}/drafts/{draftId}/working-set (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/embeds` | List the read-only embed links of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/embeds` | Mint a read-only embed link to an Investigation | inv | `canShareInvestigation` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/embeds/{grantId}/revoke` | Revoke one embed link of an Investigation | inv | `canShareInvestigation` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/handoff` | Hand an Investigation's Dossier bundle and findings off to the external case manager | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/log` | GET /inv/investigations/{investigationId}/log (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/members` | GET /inv/investigations/{investigationId}/members (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/members` | POST /inv/investigations/{investigationId}/members (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/members/revoke` | POST /inv/investigations/{investigationId}/members/revoke (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/ops` | POST /inv/investigations/{investigationId}/ops (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/pending/{pendingId}/approve` | POST /inv/investigations/{investigationId}/pending/{pendingId}/approve (generated skeleton) | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/pending/{pendingId}/deny` | POST /inv/investigations/{investigationId}/pending/{pendingId}/deny (generated skeleton) | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/references` | GET /inv/investigations/{investigationId}/references (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java` |
| POST | `/inv/investigations/{investigationId}/references` | POST /inv/investigations/{investigationId}/references (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java` |
| POST | `/inv/investigations/{investigationId}/reorder` | POST /inv/investigations/{investigationId}/reorder (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/replay` | POST /inv/investigations/{investigationId}/replay (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/reveal` | POST /inv/investigations/{investigationId}/reveal (generated skeleton) | inv | `canRevealLinkEntities` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/template` | POST /inv/investigations/{investigationId}/template (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| POST | `/inv/investigations/{investigationId}/undo` | POST /inv/investigations/{investigationId}/undo (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/working-set` | GET /inv/investigations/{investigationId}/working-set (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/WorkingSetRoutes.java` |
| POST | `/inv/pattern/branching` | POST /inv/pattern/branching (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java` |
| POST | `/inv/pattern/temporal` | POST /inv/pattern/temporal (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java` |
| GET | `/inv/pending` | GET /inv/pending — the four-eyes approver inbox | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/projection` | POST /inv/projection (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/projection/multi` | POST /inv/projection/multi (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/projection/neighbors` | POST /inv/projection/neighbors (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/reference-rows` | Reference Dataset rows for at most 500 entity keys, masked under the Space's maskingMode | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/schema/overlap-profile` | POST /inv/schema/overlap-profile (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/schema/relationships` | GET /inv/schema/relationships (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/snapshots` | GET /inv/snapshots (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/snapshots` | POST /inv/snapshots (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/snapshots/attach` | POST /inv/snapshots/attach (generated skeleton) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/traversal/recursive-paths` | Simple paths from a start node, walked server-side (from the edge index when it can answer exactly) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/uploads` | List the Collectors an analyst may upload to | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| GET | `/inv/uploads/{collector}` | List the caller's uploads to a Collector with their ingest state | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| POST | `/inv/uploads/{collector}` | Land an analyst CSV in an upload-enabled Collector's inbox | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| DELETE | `/inv/uploads/{collector}/{file}` | Withdraw an upload the Collector has not picked up yet | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| GET | `/inv/value-measures` | GET /inv/value-measures (generated skeleton) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/ValueMeasureRoutes.java` |
| GET | `/public/investigations/{token}` | Read-only embedded view of one Investigation (public; the token is the credential) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PublicInvestigationRoutes.java` |

## Drift

None — every route here is in the contract.
