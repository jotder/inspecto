# Architecture Map

Inspecto (repo `inspecto`) — Java 26 build / Maven `release=24`, multi-module reactor
(`inspecto-parent`). DuckDB · TOON config (JToon) · OpenCSV · Commons Compress.

---

## Reactor modules (dir ≠ artifactId — dirs renamed 2026-06-12, artifactIds kept)

| Dir | artifactId | Role |
|---|---|---|
| `inspecto/` | `inspecto-processor` | Lean engine + control plane (fat-JAR `inspecto.jar`). No network/AI deps. |
| `inspecto-agent/` | `inspecto-agent` | Assist skills on `agent-kernel` (the 7-skill catalog). |
| `inspecto-agent-hosted/` | `inspecto-agent-hosted` | Hosted model providers (langchain4j Anthropic/OpenAI/Gemini). |
| `inspecto-connectors/` | `inspecto-connectors` | **Optional** remote source connectors (SFTP/sshj, FTP/commons-net). ServiceLoader-discovered. |
| `inspecto-ui/` | — | Angular SPA (Material/Tailwind, ag-Grid, Chart.js, AntV G6); dev serve `:4204`. |

## Key packages (under `inspecto/src/main/java/com/gamma/`)

- **`etl/`** — `PipelineConfig` (the one config record), `BatchProcessor`/`ConsignmentPlanner`, `CsvIngester`,
  `Compression` (.gz/.bz2/.zip), `QuarantineManager`, `MarkerManager`.
- **`inspector/`** — `CollectorProcessor` (poll-cycle entry: discover → stabilize → dedup → materialize → batch).
- **`acquire/`** — Data Acquisition SPI: `CollectorConnector` + `LocalFileSystemConnector`, `RemoteFile`,
  `DiscoveryContext`, `RetrievalPlanner`, `StabilityGate`, `AcquisitionLedger` (+ `shared()`), `DuplicatePolicy`,
  `GapDetector`, `ConnectionRegistry`/`ConnectionProfile`/`SecretResolver`, `IntegrityChecker`,
  `CircuitBreaker`, `RateLimiter`, `retry/RetryPolicy`, `PostAction`.
- **`ops/`** — Operational Intelligence: mutable object store (`ObjectService`, DuckDB), workflow engine,
  `link/` (OBJECT_LINK graph), `note/` (comments/attachments), `rca/` (RCA templates), `EventObjectBridge`.
- **`event/`** (`EventLog`/`EventType`) · **`alert/`** (`AlertRule`/`AlertService`) · **`metrics/`**
  (`MetricRegistry`, Prometheus) · **`catalog/`** (metadata graph) · **`config/`** (Smart Config) ·
  **`sql/`** (sandboxed SQL) · **`service/`** (`CollectorService` host, `ControlApi` REST, `JobService`).

## Key file locations

- **Pipeline config**: `<source>_pipeline.toon` (parsed by `PipelineConfig.load`). Connection profiles:
  `*_connection.toon`. RCA templates: `*_rca.toon`. **No `#` comments** in any JToon/`ConfigCodec` file.
- **Main entry**: `com.gamma.inspector.CollectorProcessor` (ETL) · `com.gamma.service.CollectorService` (always-on
  host: scans `*_pipeline.toon`/`*_enrich.toon`/`*_job.toon`, serves Control API + UI on `:8080`).
- **Tests**: `<module>/src/test/java/...`. Authoritative verify = `mvn -o clean test` (full reactor). Toolchain:
  JDK `C:\.jdks\openjdk-26.0.1`, Maven `C:\maven\apache-maven-3.9.16\bin\mvn.cmd` (run offline `-o`).
- **Docs**: `docs/configuration.md` (config ref) · `docs/operations.md` (runbook) · `docs/integrations.md`
  (remote sources, DuckLake, warehouse) · `docs/consolidated/` (stakeholder snapshot) ·
  `docs/superpowers/specs/` (as-built design specs).

---

**Last Updated**: 2026-06-15
