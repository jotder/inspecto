---
type: Feature
title: "Control plane and processor host — admin guide"
description: "Booting, securing and checking the control plane host: its JVM flags, health routes and common boot failures."
tags: [processor, admin-guide]
timestamp: 2026-10-19T00:00:00Z
---

# Control plane and processor host — admin guide

The host entry is `ControlApi.main` (`inspecto/src/main/java/com/gamma/control/ControlApi.java`). The full
product flag table is [admin-guide-launch-flags](../../layer/platform/admin-guide-launch-flags.md); day-to-day
operation is [admin-guide-operations](../../layer/platform/admin-guide-operations.md).

## Flags read by the host

| Flag | Default | Effect |
|---|---|---|
| `-Dcontrol.port` | `8080` | HTTP(S) port |
| `-Dcontrol.bind` | every interface | Listen address; an unresolvable value fails the boot. Set `127.0.0.1` on any install without an Authenticator |
| `-Dhttps.keystore` / `-Dhttps.keystore.password` | unset | Serve TLS from this keystore; a wrong password fails the boot naming the property |
| `-Dcontrol.cors` | unset | CORS origin allow-list |
| `-Dui.dir` | unset | Directory of the built UI to serve |
| `-Dassist.write.root` | unset | Enables config write-back; without it mutating routes answer `503` |
| `-Dspaces.root` | unset | Multi-space root; unset means one `default` Space from the CLI config args ([architecture-multi-space](architecture-multi-space.md)) |

## Health

| Route | Auth | Meaning |
|---|---|---|
| `GET /health` | public | `UP`, or `200 DEGRADED` when a Safety Policy file is unreadable or Spaces were skipped (count only) |
| `GET /health/details` | gated | Per-subsystem detail: skipped Spaces and reasons, store families, run lease |
| `GET /ready` | public | `READY` with the Pipeline count. `503` only when Spaces are configured and none booted; zero Spaces is still ready |
| `GET /metrics` | public | Served by the optional observability module; `503` when that module is absent |

Use `/health` for liveness and `/ready` for readiness. `/ready` ignores the shared run lease database on
purpose; a lease outage shows on `/health/details`.

## Common failures

| Symptom | Cause | Action |
|---|---|---|
| Every mutating route answers `503` | `-Dassist.write.root` not set | Set it to the writable config root |
| `/ready` answers `503` | Every configured Space failed to boot | Read `/health/details` (`spaces`) and the log |
| `/health` says `DEGRADED` | Unreadable Safety Policy file or a skipped Space | Fix the named file / Space; the server keeps serving |
| Boot fails on `-Dcontrol.bind` or the keystore | Bad address, or wrong keystore password | Correct the flag |
| `/metrics` answers `503` | Observability module not in this Edition | Expected; use a build that includes it |

Troubleshooting across the platform: [admin-guide-troubleshooting](../../layer/platform/admin-guide-troubleshooting.md).
