---
type: Guide
title: Link analysis and geo routes - admin guide
description: Enable, configure, secure and troubleshoot the Link Analysis HTTP surface.
resource: la/inspecto-la-api/
tags: [module, la-api, link-analysis, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Link analysis and geo routes - admin guide

Deployment walkthroughs for the whole product: [la-core demo admin guide](../la-core/admin-guide-demo.md) and
[real sign-in](../la-core/admin-guide-real-signin.md). This page covers what la-api itself reads.

## Install and enable

1. **Installed.** The jar ships in every edition bundle (`offerings/basic.toon`), together with la-core, la-storage and
   geo-link. Without geo-link, the routes have no data bridge.
2. **Enabled.** Feature `geoLink` per Space in `modules.toon`; disabled answers 404 `MODULE_DISABLED`.
3. **Permitted.** Capabilities from `platform/inspecto-access` `Roles` (for example `canManageIncidents`,
   `canRunLinkGraphAnalysis`, `canBuildLinkIndex`, `canShareInvestigation`); per-route list in [api-routes](api-routes.md).

On Basic the Investigation surface answers 503 "available in Standard and above"; only the explorer is served, without
sign-in, so masking there is display-only.

## Configuration

| Setting | Layer | Edited by | Notes |
|---|---|---|---|
| `link-analysis.toon` (masking mode, `index.enabled`, SPACE limits) | Space write root | `PUT /settings/link-analysis`, `PUT /settings/link-analysis/limits` (`canAuthorWorkbench`) | read per request, cached on file content |
| `la-limits.toon` (INSTALL limits) | server-wide document home (`-Dsystem.config.dir`, else the spaces root, else the sole Space) | `PUT /system/link-analysis/limits` (`canAdminister`) | rate buckets rebuilt on that PUT or a restart |
| `-Dla.embed.enabled=true` | JVM | restart | kill switch for embed links; off by default |
| `-Dinvestigations.backend` and `-Dinvestigations.db.*` | JVM | restart | Investigation store; see [la-store-pg admin guide](../la-store-pg/admin-guide.md) |

Every limit key with its default and range: `GET /settings/link-analysis/limits`, catalogue
`platform/inspecto-entity-store/src/main/java/com/gamma/entitystore/LaLimit.java`, table in
[la-core architecture](../la-core/architecture.md) §9. Values outside a key's range are refused 422 on write and read as
the default if hand-edited.

Most-used SPACE keys: `index.max_age_hours` (24), `index.traversal_depth` (2), `index.frontier_cap` (20),
`graph_run.max_nodes` (50,000), `drafts.max_open` (50), `embed.default_ttl_hours` (72) / `embed.max_ttl_hours` (720).
INSTALL keys: `graph_run.threads` (2) / `queue` (16), `index.threads` (1) / `queue` (4), `rate.link_analysis_*`
(burst 30, 1 per 2 s per subject), `drafts.heavy_jobs`. Older JVM properties (`control.rateLimit.*`,
`inspecto.la.draft.heavy`) are read only when the install document does not state the key.

## Secure

- Embed links are bearer tokens: keep `-Dla.embed.enabled` off unless a portal needs them; the per-Space frame
  allow-list and TTLs bound them ([la-core architecture](../la-core/architecture.md) §6.2).
- Investigation access is membership-based; non-members see 404. The Advanced PDP (`providers/inspecto-policy`) can only
  narrow.
- Audit rows use the `LINK_*` event types; tokens are redacted.

## Troubleshoot

| Symptom | Check |
|---|---|
| Investigation tab missing / 503 | edition is Basic, or `GET /health/details` shows `live.investigations` DOWN |
| Reads slow, response says `source: flat` with a `Reason` | no published index, stale index, or caps exceeded; see [la-storage admin guide](../la-storage/admin-guide.md) |
| 429 on exploration | raise `rate.link_analysis_*` via `PUT /system/link-analysis/limits` |
| Graph Run `REJECTED` / `BUDGET_EXCEEDED` | pool full, or the run exceeds `graph_run.max_*` |
| 409 "Dataset has no data yet" | the Dataset's store is empty: run the Pipeline that fills it |
