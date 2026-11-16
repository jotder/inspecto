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

104 route(s) registered; 104 in the OpenAPI contract, 0 not.

| Method | Path | Summary | Tag | Capability | Registered in |
|---|---|---|---|---|---|
| POST | `/geo/projection` | Project a Dataset onto map points | geo | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java` |
| POST | `/geo/routes` | Project a Dataset onto map routes | geo | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GeoRoutes.java` |
| POST | `/inv/case-history` | The Incidents and Cases referencing one Entity, for a key that may be a masking alias | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/CaseHistoryRoutes.java` |
| GET | `/inv/datasets/{datasetId}/discover` | What is in one Dataset: entities, busiest, kinds of link, period, Measure totals | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DiscoverRoutes.java` |
| GET | `/inv/datasets/{datasetId}/files` | List the Files recorded towards a Dataset, with each File's index state | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DatasetFilesRoutes.java` |
| POST | `/inv/datasets/{datasetId}/query` | Run one query-builder question over a Dataset | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/QueryRoutes.java` |
| GET | `/inv/datasets/{datasetId}/shapes` | Which Entity Types' value shapes each column of one Dataset fits | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DiscoverRoutes.java` |
| POST | `/inv/deep-links/resolve` | Validate an Open in Inspecto LA link's parameters | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationInboundRoutes.java` |
| GET | `/inv/entities/{entityType}/{entityKey}/profile` | Read one Entity's profile by raw value or exploration alias | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityPageRoutes.java` |
| GET | `/inv/entities/{entityType}/{entityKey}/summary` | One entity's summary over one Dataset's links | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntitySummaryRoutes.java` |
| POST | `/inv/entities/{entityType}/resolve` | Resolve a raw value or an alias to the key an Entity profile URL takes | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| POST | `/inv/entities/{entityType}/suggest` | List the values of an Entity Type that start a graph (the Start box type-ahead) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| GET | `/inv/entity-identities` | Resolved Entity identity groups | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities` | Assert two Entity keys are one identity | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities/{entityidentityId}/retract` | Retract an identity assertion | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| GET | `/inv/entity-identities/group` | Identity group of one Entity key | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| POST | `/inv/entity-identities/import` | Import identity assertions from a mapping Dataset | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityIdentityRoutes.java` |
| GET | `/inv/entity-types` | List the Entity Types in force with their bindings and cached headline | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| DELETE | `/inv/entity-types/{entityTypeId}` | Delete an Entity Type nothing uses | inv | `canAuthorWorkbench` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityProfileRoutes.java` |
| GET | `/inv/entity-types/{entityTypeId}/profile` | Read the Entity Type profile: aggregates over every column bound to the type | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/EntityPageRoutes.java` |
| GET | `/inv/exclusion-lists` | The exclusion lists an Investigation can apply | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/ExclusionListRoutes.java` |
| POST | `/inv/expand/preview` | Unsaved Investigation preview: the Working Set its seed / expand / exclude steps would build (stores nothing) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/graph/algorithms` | The graph-algorithm catalogue and run limits | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| GET | `/inv/graph/runs` | List the caller's graph runs | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/graph/runs` | Start a graph run over an Investigation's Working Set | inv | `canRunLinkGraphAnalysis` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| GET | `/inv/graph/runs/{runId}` | Read one graph run | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/graph/runs/{runId}/cancel` | Cancel a graph run | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/GraphRunRoutes.java` |
| POST | `/inv/inbound/investigations` | Third-party integration (inbound): open a seeded Investigation for an external record | inv | `canOpenInboundInvestigation` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationInboundRoutes.java` |
| GET | `/inv/index` | List the edge/node indexes of the caller's viewable Datasets | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| POST | `/inv/index/builds` | Start an edge/node index build over a Dataset | inv | `canBuildLinkIndex` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| GET | `/inv/index/builds/{buildId}` | Read one index build | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| POST | `/inv/index/builds/{buildId}/cancel` | Cancel an index build | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IndexRoutes.java` |
| GET | `/inv/integration` | The Space's third-party integration settings (outbound) | inv | `canAdminister` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| PUT | `/inv/integration` | Replace the Space's third-party integration settings | inv | `canAdminister` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| GET | `/inv/investigation-templates` | List the caller's Investigation templates | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| GET | `/inv/investigation-templates/{investigationtemplateId}` | One Investigation template | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| POST | `/inv/investigation-templates/{investigationtemplateId}/instantiate` | Instantiate an Investigation template | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| GET | `/inv/investigations` | List readable Investigations | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations` | Create an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| DELETE | `/inv/investigations/{investigationId}` | Delete an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| PATCH | `/inv/investigations/{investigationId}` | PATCH /inv/investigations/{investigationId} | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| DELETE | `/inv/investigations/{investigationId}/case` | Unlink an Investigation from its Case | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| GET | `/inv/investigations/{investigationId}/case` | An Investigation's Case link | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| PUT | `/inv/investigations/{investigationId}/case` | Link an Investigation to a Case | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCaseRoutes.java` |
| GET | `/inv/investigations/{investigationId}/compare` | Compare an Investigation's Working Set across two windows | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationComparisonRoutes.java` |
| GET | `/inv/investigations/{investigationId}/coverage` | Data coverage of an Investigation's window | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationCoverageRoutes.java` |
| GET | `/inv/investigations/{investigationId}/dossier` | An Investigation's Dossier | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierRoutes.java` |
| GET | `/inv/investigations/{investigationId}/dossier/bundle` | Export an Investigation's sealed Dossier bundle | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierBundleRoutes.java` |
| POST | `/inv/investigations/{investigationId}/dossier/bundle/verify` | Verify a Dossier bundle | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierBundleRoutes.java` |
| POST | `/inv/investigations/{investigationId}/dossier/verify` | Verify a Dossier manifest | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DossierRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts` | List Drafts of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts` | Fork a Draft of an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}` | One Draft of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/conflicts` | Conflicts a Draft rebase would meet | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/discard` | Discard a Draft | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/log` | A Draft's own step log | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/ops` | Append a step to a Draft | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/promote` | Promote a Draft into the main log | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/rebase` | Rebase a Draft onto the main head | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/replay` | Replay a Draft's log | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| POST | `/inv/investigations/{investigationId}/drafts/{draftId}/undo` | Undo the latest Draft step | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/drafts/{draftId}/working-set` | A Draft's Working Set | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/DraftRoutes.java` |
| GET | `/inv/investigations/{investigationId}/embeds` | List the read-only embed links of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/embeds` | Mint a read-only embed link to an Investigation | inv | `canShareInvestigation` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/embeds/{grantId}/revoke` | Revoke one embed link of an Investigation | inv | `canShareInvestigation` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationEmbedRoutes.java` |
| POST | `/inv/investigations/{investigationId}/expand/preview` | Grow preview: dry-run an expand and answer what it would add (appends nothing) | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/handoff` | Hand an Investigation's Dossier bundle and findings off to the external case manager | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/IntegrationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/log` | An Investigation's step log | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/members` | Members of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/members` | Grant a role on an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/members/revoke` | Revoke a member of an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationMemberRoutes.java` |
| POST | `/inv/investigations/{investigationId}/ops` | Append a step to an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/pending/{pendingId}/approve` | Approve a pending four-eyes request | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/pending/{pendingId}/deny` | Deny a pending four-eyes request | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/references` | External references of an Investigation | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java` |
| POST | `/inv/investigations/{investigationId}/references` | Add an external reference to an Investigation | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationReferenceRoutes.java` |
| POST | `/inv/investigations/{investigationId}/reorder` | Fork an Investigation with its steps reordered | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/replay` | Replay an Investigation's sealed log | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/reveal` | Reveal the masked entities on screen, with a reason, audited per entity | inv | `canRevealLinkEntities` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| POST | `/inv/investigations/{investigationId}/template` | Save an Investigation as a template | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationTemplateRoutes.java` |
| POST | `/inv/investigations/{investigationId}/undo` | Undo the latest Investigation step | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/investigations/{investigationId}/working-set` | An Investigation's Working Set | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/WorkingSetRoutes.java` |
| POST | `/inv/pattern/branching` | Match a branching pattern over a whole Dataset | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java` |
| POST | `/inv/pattern/temporal` | Scan a Dataset for burst or periodic links | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PatternRoutes.java` |
| GET | `/inv/pending` | GET /inv/pending — the four-eyes approver inbox | inv | `canApproveLinkExpansions` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvestigationRoutes.java` |
| GET | `/inv/presets` | List the preset queries a Dataset can answer | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PresetRoutes.java` |
| POST | `/inv/presets/{presetId}/run` | Run one preset query over a Dataset | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PresetRoutes.java` |
| POST | `/inv/projection` | Fold a Dataset into distinct links (compact edge rows) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/projection/multi` | Project entities across several Datasets | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/projection/neighbors` | One entity's one-hop links (compact edge rows) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/projection/neighbors/preview` | Grow preview for the explorer: what one neighbours step from a set of entities would add | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/reference-rows` | Reference Dataset rows for at most 500 entity keys, masked under the Space's maskingMode | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/schema/overlap-profile` | Profile value overlap between Dataset columns | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/schema/relationships` | Suggest entity relationships across Datasets | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/snapshots` | List sealed Link Analysis snapshots | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/snapshots` | Seal a Link Analysis evidence snapshot | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/snapshots/attach` | Attach a sealed snapshot to a Case | inv | `canManageIncidents` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| POST | `/inv/traversal/recursive-paths` | Simple paths from a start node, walked server-side (from the edge index when it can answer exactly) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/InvRoutes.java` |
| GET | `/inv/uploads` | List the Collectors an analyst may upload to | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| GET | `/inv/uploads/{collector}` | List the caller's uploads to a Collector with their ingest state | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| POST | `/inv/uploads/{collector}` | Land an analyst CSV in an upload-enabled Collector's inbox | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| DELETE | `/inv/uploads/{collector}/{file}` | Withdraw an upload the Collector has not picked up yet | inv | `canUploadData` | `la/inspecto-la-api/src/main/java/com/gamma/la/api/UploadRoutes.java` |
| GET | `/inv/value-measures` | Evaluate a value Measure over a Dataset | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/ValueMeasureRoutes.java` |
| GET | `/public/investigations/{token}` | Read-only embedded view of one Investigation (public; the token is the credential) | inv | — | `la/inspecto-la-api/src/main/java/com/gamma/la/api/PublicInvestigationRoutes.java` |

## Drift

None — every route here is in the contract.
