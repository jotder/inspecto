---
name: java-backend
description: >
  Senior Backend Architect rules for the inspecto Java engine + control plane (modules
  inspecto/, inspecto-agent/, inspecto-agent-hosted/, inspecto-connectors/). MUST be read and
  applied BEFORE generating or modifying ANY backend artifact — engine code, control API routes,
  SPI implementations, TOON config, edition wiring, or backend tests. Encodes the framework-free
  design (JDK HttpServer, manual DI, ServiceLoader SPI), the edition model (Personal/Standard/
  Enterprise = build flavors, never branches), TOON config + ConfigSafetyValidator rules, the
  DuckDB native-access launch flag, and the authoritative mvn -o test verify loop. Trigger on any
  change under inspecto*/src/main/java or backend config.
---

# Inspecto Backend Architecture (Java engine + control plane)

> Durable backend gotchas & decisions (TOON schema serialization, DuckDB reserved words `day`/`trigger`,
> lowercased `BatchEvent.pipeline()`, sync-bus + `ingestLock` deadlock, `PartitionWriter` partition cols,
> engine seams & perf, auth/edition model): [docs/PROJECT_NOTES.md](../../docs/PROJECT_NOTES.md).

You are acting as a **Senior Backend Architect** for a *deliberately* framework-free Java 24
(build JDK 26, `release=24`) ETL/file-processing platform. The goal is not merely working code but
a lean, air-gappable, single-fat-JAR system with a small attack surface that stays maintainable for
years. Do not reach for a framework; this design is intentional (see [docs/EDITIONS.md](../../docs/EDITIONS.md)).

## Non-negotiables

1. **No framework.** HTTP is `com.sun.net.httpserver.HttpServer`; DI is manual constructors; all
   optional capability is discovered via `java.util.ServiceLoader`. Never add Spring/Quarkus/CDI/
   Guice/Jakarta. Never add a heavy transitive dependency without explicit sign-off — a small SBOM
   is a FedRAMP asset.
2. **Editions are build flavors, NEVER branches or `if (edition==…)` code.** The engine depends on
   SPIs (`Authenticator`, `SecretsProvider`, `SourceConnector`, …); the *build* (Maven profile +
   which `ServiceLoader` modules ship + `-D` flags) decides the implementation. One SemVer version
   spans all editions; artifacts differ by classifier (`-personal` / `-standard`).
3. **Fail-closed at the edge.** Security/secrets are cross-cutting SPIs injected only at the HTTP
   edge (`com.gamma.control`). The engine stays identity-agnostic.
4. **Config safety is mandatory.** All runtime input flows through `ConfigSpec` +
   `ConfigSafetyValidator` (path-jail, bounds, allow-lists). Never bypass it. **No `#` comments in
   any JToon/`ConfigCodec` (`.toon`) file** — the parser rejects them.

## Module map (artifactIds renamed `file-processor-*` → `inspecto-*` on 2026-08-10, `a1da65f5`)

⚠ **`file-processor` now names only the DEPLOYMENT surface, never a reactor artifact.** `package.ps1`
deliberately copies the fat JAR to `file-processor.jar` inside `file-processor-deploy/` (see its
comment at `:153-157`), so bundle filenames and the zip names keep the old word while every
**artifactId and `target/` jar** is `inspecto-*`. A glob like `inspecto/target/file-processor-*.jar`
matches nothing.

Dir == artifactId for every module **except** `inspecto/` → `inspecto-processor`.

| Dir / artifactId | Role |
|---|---|
| `inspecto-api` | `@PublicApi` stability annotations — the reactor's dependency-free leaf. |
| `inspecto-util` | DuckDB access + CSV/file/tar helpers. |
| `inspecto-config` | `ConfigSpec` + `ConfigCodec` + `ConfigSafetyValidator`. |
| `inspecto-sql` | SQL sandbox + oracle + guard. |
| `inspecto-etl` | `PipelineConfig` + batch ingest. |
| `inspecto-event` | Operational-Intelligence event store + metrics. |
| `inspecto-acquire` | File/remote acquisition SPI. |
| `inspecto-engine` | The engine cluster — the strongly-connected component **below** the control plane. |
| `inspecto/` → **`inspecto-processor`** | Lean control plane + the shaded fat JAR. No network/AI deps. |
| `inspecto-agent` | Assist skills on `agent-kernel` (7-skill catalog). |
| `inspecto-agent-hosted` | Hosted model providers (langchain4j). |
| `inspecto-connectors` | **Optional** SFTP/FTP connectors, ServiceLoader-discovered. |
| `inspecto-intelligence` | **Optional** embedded intelligence. |
| `inspecto-security` | **Optional, `-Pedition-standard` / `-Pedition-enterprise` only**: `Authenticator` SPI impl (OIDC/Nimbus JWKS). |
| `inspecto-policy` | **Optional, `-Pedition-enterprise` only**: ABAC policy engine. |

`asn-parser/asn-decoders` is a **separate reactor** aggregated by the root pom only so
`com.gamma.asn:asn-facade` resolves from the build; it inherits nothing from `inspecto-parent`.

⚠ The last two are **profile-gated and invisible to a bare `mvn -o clean test`** — see *Verify loop*.

## Key packages (module ≠ `inspecto/` for most of these)

- **`com.gamma.etl`** (`inspecto-etl`) — `PipelineConfig` (the one config record),
  `BatchProcessor`/`ConsignmentPlanner`, `CsvIngester`, `Compression`, `QuarantineManager`, `MarkerManager`.
- **`com.gamma.inspector`** (`inspecto-engine`) — `CollectorProcessor` (poll-cycle: discover →
  stabilize → dedup → materialize → batch).
- **`com.gamma.acquire`** (`inspecto-acquire`; connector impls in `inspecto-connectors`) — Data
  Acquisition SPI: `CollectorConnector` + `LocalFileSystemConnector`, `RemoteFile`, `StabilityGate`,
  `AcquisitionLedger`, `DuplicatePolicy`, `ConnectionRegistry`/`ConnectionProfile`/`SecretResolver`,
  `CircuitBreaker`, `RateLimiter`, `retry/RetryPolicy`.
- **`com.gamma.service`** (`inspecto/` — the one package that really does live here) —
  `CollectorService` (always-on host), `ControlApi` (~50 JDK-HttpServer routes), `JobService`.
- **`com.gamma.ops`** and **`com.gamma.parse`** (`inspecto-engine`) — `alert/` `metrics/`
  (Prometheus on `/metrics`) `catalog/` `config/` `sql/` (sandboxed SQL) · `ParserPlugin` SPI.

Entry points: `com.gamma.inspector.CollectorProcessor` (one-shot ETL) · `com.gamma.service.CollectorService`
(long-running host, Control API + UI on `:8080`).

## Patterns to follow

- **New optional capability → a `ServiceLoader` SPI** with a no-op/default impl that wins when the
  module is absent (mirrors the optional assist agent and connectors). This is how editions add code.
- **Records + sealed types**, explicit object graphs, no reflection magic. Match the surrounding style.
- **New ControlApi route** → reuse the existing handler/scope pattern; thread `actor` through any
  state-changing action into the event/audit store; keep CORS locked to the UI origin via `-Dcontrol.cors`.
- **Secrets** go through the `SecretsProvider` seam — never hard-code, never log. No plaintext secrets
  in `.toon` or in committed UI config.

## Verify loop (authoritative)

```bash
mvn -o clean test          # default reactor, offline — misses the profile-gated modules
mvn -o clean test -Pedition-enterprise   # + inspecto-security AND inspecto-policy
mvn -o clean package -q    # → inspecto/target/inspecto-processor-*.jar
```
⚠ A **profile-scoped module is invisible** to a bare `mvn -o clean test`. Anything touching the parent
pom, a shared artifactId, a managed dependency, or a shared record must be verified with
`-Pedition-enterprise`. Read the reactor summary for **SKIPPED**, not only FAILURE — "25 modules"
means listed, not built.
Toolchain: JDK `C:\.jdks\openjdk-26.0.1`, Maven `C:\maven\apache-maven-3.9.16\bin\mvn.cmd`. Always
run **offline (`-o`)**. **Every JVM launch needs `--enable-native-access=ALL-UNNAMED`** (DuckDB JNI),
including test invocations. Tests spin up real `SourceService`/`ControlApi` on an ephemeral port and
drive the HTTP surface — extend that pattern (see `inspecto/src/test/java/com/gamma/control/`).

For the build/package/edition recipe see the **build-verify** skill; for committing/branching see the
**release-workflow** skill and [docs/BRANCHING.md](../../docs/BRANCHING.md).
