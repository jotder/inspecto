---
type: Feature
title: "Product — admin guide"
description: "Product-wide administration: choosing and packaging an edition, launching the server, serving the UI, configuring the sign-in provider, and where to go for operations."
tags: [product, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Product — admin guide

This is the administrator's entry point. It summarises the decisions you make for a whole installation
and links to the detailed references rather than copying them.

## 1. Pick an edition

Editions are build flavors, never branches: **Basic**, **Standard**, **Advanced**, plus the internal
**Preview** (not customer-facing). The module set, add-ons and deployment posture per edition are in
[`docs/EDITIONS.md`](../../../../EDITIONS.md). In short: Basic runs with no sign-in (`authMode none`);
Standard, Advanced and Preview bundle OIDC sign-in and a Postgres sidecar.

## 2. Build the bundle

`inspecto/package.ps1` produces the deployment bundle. Main parameters (from its `param` block):

| Parameter | Effect |
|---|---|
| `-Edition Basic\|Standard\|Advanced\|Preview` | Edition to stage (default `Basic`). |
| `-Ui gamma\|la-app` | Which SPA goes into `ui/` — the Inspecto console (default) or the Link Analysis app. Not an edition. |
| `-NoUi` / `-NoRuntime` | Skip the UI build / the embedded trimmed Java runtime (the host must then supply Java). |
| `-SkipBootCheck` | Skip the post-package boot smoke against `/health` (local use only, never for a release). |
| `-DemoAuth` | Offline demo build (requires `-Edition Advanced` or `-Edition Preview`); binds to `127.0.0.1`. Never an edition. |
| `-Sign` | Also write a GPG detached signature per artifact; SHA-256 checksums are always written. |

Build recipes and Maven profiles: [build & test](../platform/spec-build-test.md).

## 3. Launch

The bundle's `serve.sh` / `serve.bat` start `ControlApi`. The flags that matter most — `-Dcontrol.port`,
`-Dcontrol.bind`, `-Dspaces.root`, `-Dui.dir`, `-Dcontrol.cors`, `-Dassist.write.root` — are described in
[launch flags](../platform/admin-guide-launch-flags.md); the complete list is in
[`ADVANCED_GUIDE.md`](../../../../ADVANCED_GUIDE.md) §9.

- **UI serving:** `-Dui.dir=./ui` serves the bundled SPA from the same port as the API; omit it to run
  the API without a UI.
- **Exposure:** an unset `-Dcontrol.bind` listens on every interface. On Basic (no sign-in) set
  `-Dcontrol.bind=127.0.0.1` or firewall the port.
- **Write-back:** without `-Dassist.write.root` every configuration change answers `503`.

## 4. Configure sign-in

Sign-in is decided by the classpath (the OIDC jar), not by `-Dauth.mode`, which is only a label.
The identity provider preset is `-Dauth.oidc.provider` = `generic` (default), `wso2` or `keycloak`
(`providers/inspecto-oidc/src/main/java/com/gamma/oidc/OidcConfig.java`). Per key, an explicit
`-Dauth.oidc.<key>` wins over the document at `-Dauth.oidc.discoveryUrl`, which wins over the Keycloak
preset's fixed paths. An unknown provider, an unreachable discovery document, or a mismatched issuer
**refuses to boot**. With `keycloak` and no explicit roles claim, realm and client roles are combined.

Full setup, roles and hardening: [auth & security](../../module/access/admin-guide-auth-security.md);
gateway and IdP blueprints: [`docs/api/deployment/`](../../../../api/deployment/README.md).

## 5. Operate

| Need | Go to |
|---|---|
| Day-to-day operations, health and metrics | [operations](../platform/admin-guide-operations.md) · [reference](../platform/admin-guide-operations-reference.md) |
| Something is broken | [troubleshooting](../platform/admin-guide-troubleshooting.md) · `ADVANCED_GUIDE.md` §11 |
| Known traps | [gotchas](../platform/admin-guide-gotchas.md) |
| HA / DR drill | [HA-DR drill](../platform/admin-guide-ha-dr-drill.md) |
| Spaces on disk | [multi-space](../../module/processor/architecture-multi-space.md) |
| Seed a UAT environment | [UAT seeding](../platform/admin-guide-uat-seeding.md) |
