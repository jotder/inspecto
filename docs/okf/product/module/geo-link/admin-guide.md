---
type: Guide
title: Geo and link adapters — admin guide
description: Installing, switching on, configuring outbound integration and troubleshooting the inspecto-geo-link bridge.
resource: la/inspecto-geo-link/
tags: [module, geo-link, link-analysis, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Geo and link adapters — admin guide

## Install

- Shipped in every Edition as `inspecto-geo-link.jar` (`tools/bundle-modules.mjs`, `from: 'all'`). It needs
  `inspecto-la-core`, `inspecto-la-api` and `inspecto-entity-list` beside it (all staged from Basic up).
- `bindingTime: boot`: present on the classpath at start or not at all.
- Confirm with `GET /modules` — id `geo-link`, `features: geoLink, investigations`.

## Switch on per Space

The module's feature is `geoLink`. Turn modules on or off for a Space with `PUT /settings/modules`
(`inspecto/src/main/java/com/gamma/control/ModulesRoutes.java`); the embed sweep and the routes act only for
Spaces where it is on. In Basic the Investigation surface stays off because the `investigations` edition
feature is declared only by `inspecto-ops` (Standard and up).

## Configure

| Setting | Where | Effect |
|---|---|---|
| `-Dla.embed.enabled=true` | JVM flag (default off) | Kill switch for embed links; off = minting is 503, public requests 404, and the expiry sweep does nothing. |
| Integration settings (`handoff`, `webhooks`, `retry`) | `PUT /inv/integration` (`canAdminister`), stored in the Space config root | Case hand-off endpoint and signed webhooks. See `la/inspecto-la-api/src/main/java/com/gamma/la/api/LaIntegration.java`. |
| `webhooks.connection` / `handoff.connection` | a registered `https` Connection | Endpoint; its token must be a `${…}` reference — a literal token is refused. |
| `webhooks.signingSecret` | a `${…}` secret reference | HMAC key; a literal is refused (422). |
| Egress allowlist | Space setting | Outbound addresses are deny-by-default; an address must be allowlisted or the send is refused and not retried. |
| `collector.accepts_upload: true` | Pipeline Collector config | Lets analysts upload files into that local poll Collector. |
| `input.embed_active_grants` | Space limit | Most live embed links per Investigation. |

Outbound delivery needs the `WebhookSinkTransport` from `inspecto-notify-channels` (Standard and up).

## Secure

- No secret is stored in the integration settings; Connections are onboarded under `canOnboardConnections`.
- Webhook receivers must verify `X-Inspecto-Signature` (HMAC-SHA256 over `X-Inspecto-Timestamp` + "." +
  body) in constant time, reject stale timestamps, and dedupe on `Idempotency-Key`.
- Webhook payloads carry no entity id, so Space masking has nothing to act on; receivers read details through
  the API, where masking applies.

## Troubleshoot

| Symptom | Check |
|---|---|
| Geo map / Link Analysis routes 404 or "not installed" | `GET /modules`: is `geo-link` present and enabled in the Space? |
| Measure or alert-rule route returns 404 | Only the Investigation owner may use it (owner-only gate runs first). |
| Alert Rule on a live Measure never fires | It needs `POST …/standing-detection`; the owner's authority is re-decided each sweep. |
| Data-changes says `assessed:false` | No lineage tying a Pipeline to the Dataset's stores yet. |
| Webhooks not arriving | Ledger events `link.webhook.failed`; check the Connection is `https`, token a reference, the host allowlisted, and the edition has notify-channels. |
| `link-index` Job fails | The module is missing from the classpath (the Job fails closed without it). |

## References

- [architecture](architecture.md) · [spec](spec.md) · [api-routes](api-routes.md)
- [la-core admin-guide-real-signin](../la-core/admin-guide-real-signin.md) · [la-core admin-guide-demo](../la-core/admin-guide-demo.md)
