---
type: Overview
title: Inspecto Backend Overview
description: The Java file-processing engine + control plane — tech stack, module map, and design ethos.
resource: inspecto/
tags: [inspecto, backend, overview, java, duckdb, toon]
timestamp: 2026-07-07T00:00:00Z
---

# Overview

**Inspecto** (formerly *UCC File Processor*) is a high-throughput file-processing engine with an embedded
control plane. It ingests files (local or remote), parses them, applies a schema + transforms, and writes
partitioned columnar output — all backed by an embedded **DuckDB**. An operator console
(inspecto-ui — its own [OKF bundle](../frontend/index.md)) drives it over an HTTP control API.

## Tech stack

* **Java 26**, **Maven** multi-module reactor.
* Embedded **DuckDB** (native, via the Appender API) for ingest/transform/output.
* **TOON** configuration (`.toon` files via JToon) — see [TOON config](./config/toon-config.md).
* Framework-free: the JDK's built-in `HttpServer`, manual dependency injection, `ServiceLoader` SPIs, and
  **virtual threads** — no Spring/web framework. See [Architecture](./architecture.md).

## Module map

The directory names were renamed 2026-06-12 and the Maven **artifactIds followed**: 22 of the 23 modules have `dir == artifactId`. The **one** exception is `inspecto/` → `inspecto-processor`. ⚠ The table below lists 5 of 23 Maven modules; the full list is the root `pom.xml` (14 default + 9 profile-scoped), mapped in [architecture-layers.md](./architecture-layers.md).

| Dir | Role | artifactId / jar |
|---|---|---|
| `inspecto/` | engine + control plane (lean core) | `inspecto-processor` / `inspecto.jar` |
| `inspecto-connectors/` | remote connectors (SFTP/FTP/FTPS/DB) — all network deps | `inspecto-connectors` |
| `inspecto-agent/` | optional AI assist skills (vendored kernel layer + eoiagent transport) | `inspecto-agent` |
| `inspecto-agent-hosted/` | hosted model providers (omitted from air-gapped builds) | `inspecto-agent-hosted` |
| `inspecto-security/` | Standard-only OIDC auth (reactor-gated: `edition-standard` profile) | `inspecto-security` |
| `inspecto-ui/` | Angular SPA (served by the engine) | — (npm) |

See [Modules](./modules) for each one.

## Design ethos

* **Keep the core lean** — all network deps live in [connectors](./modules/connectors.md); hosted-AI SDKs in
  [agent-hosted](./modules/agent-hosted.md) (physically absent from air-gapped builds).
* **Editions are build flavors, never git branches** — one auth-free common core, assembled per edition via
  Maven profiles + `ServiceLoader` + `-D` flags. Standard's OIDC auth now exists as the profile-gated
  `inspecto-security` module — the core itself still carries zero auth code. See
  [Editions](./editions/editions-model.md) and [auth & security](./editions/auth-security.md).
* **Mainline** `master` — today the **only** line. `4.x` was deleted 2026-08-17 with its `v4.0.0`/`v4.0.0-RC1` tags, and nothing is in production after `3.x` (newest tag `v3.12.0`); the next `N.x` is cut from `master` at release. See [branch & release policy](./editions/branching-release.md) and `docs/BRANCHING.md` §0-A.
