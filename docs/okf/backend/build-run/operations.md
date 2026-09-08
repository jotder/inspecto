---
type: Concept
title: Operations & Launch Flags
description: The key -D launch flags, run modes, and the production-investigation guide.
resource: inspecto/package.ps1
tags: [operations, launch, flags, run, troubleshooting]
timestamp: 2026-06-28T00:00:00Z
---

# Operations & Launch Flags

The server entry is [`ControlApi.main`](../control-plane/control-api.md) (the `com.gamma.inspector.MainApp` CLI is a
separate pre-ETL tool suite — search/copy/extract/backup/prepare-inbox/create-schema/reprocess).

## Key `-D` launch flags

| Flag | Purpose |
|---|---|
| `-Dcontrol.port=<n>` | HTTP port for the control API (default `8080`). |
| `-Dcontrol.bind=<host-or-IP>` | 🔴 **Listen address. Unset = EVERY interface, in every edition** (`ControlApi.java:292-293`) — a deliberate call, not an oversight, because narrowing it would strand existing installs on upgrade. Personal ships **no `Authenticator`**, so a Personal install left on the default binds an **unauthenticated** control plane to every interface. Set `-Dcontrol.bind=127.0.0.1`, or firewall the port, for any single-user install. An unresolvable value **fails the boot** rather than falling back (`:295-296`). |
| `-Dspaces.root=<dir>` | [Multi-space](../control-plane/multi-space.md) root. **No default** (`ControlApi.java:382` reads it with no fallback): unset ⇒ single-tenant, one `default` space built from the CLI config args. The `spaces` you see in a bundle is `serve.sh:8`/`serve.bat:9`'s shell default, not a JVM one. |
| `-Dui.dir=./ui` | Serve the bundled Angular SPA; omit to disable UI serving. |
| `-Dcontrol.cors=<origin>` | CORS origin allow-list for the control plane. |
| `-Dassist.write.root=<dir>` | Enable config/pipeline/connection write-back; absent → mutations return `503` (see [auth & security](../editions/auth-security.md)). |
| `-Dacquire.ledger.backend=db` | Use the durable [DB acquisition ledger](../acquisition/framework.md) instead of in-memory. |
| `-Dauth.mode=none\|oidc` | **Label only** — read at `BootstrapRoutes.java:55,83` to fill `/bootstrap`'s `edition`/`features.authMode`; **nothing branches on it**. The switch is the classpath: `Authenticators.active()` is a `ServiceLoader` lookup, empty on Personal, so dispatch skips auth entirely. `serve.sh`/`serve.bat` set it beside `inspecto-security.jar`, which is what actually decided the edition. |

Plus `--enable-native-access=ALL-UNNAMED` is always required (see [build & test](build-test.md)).

## Investigating production

The living production-investigation guide (process/events/metrics/state/Control API/troubleshooting) is
`docs/ADVANCED_GUIDE.md`. Observability primitives: [events & metrics](../control-plane/events-metrics.md)
(`/metrics` Prometheus text, `/events/search` — both OPTIONAL modules since EDG-01, 503 on Personal; the core audit read is `/audit/search`). Performance tuning: [`performance.md`](performance.md);
the full utilities/batching/output/deployment reference is [`operations-reference.md`](operations-reference.md).
