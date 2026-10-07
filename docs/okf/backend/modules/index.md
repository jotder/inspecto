# Modules

The backend Maven modules. Directory names were renamed 2026-06-12; the artifactIds were not (dir ≠
artifactId). The core stays lean — network, hosted-AI, and auth dependencies are isolated in their own
modules. Reactor shape, version management, and the module-extraction playbook:
[reactor.md](reactor.md).

# Modules

* [Reactor & modularization](reactor.md) - build order, parent `dependencyManagement`, extraction rules (S5 + WS-D, 2026-07-21).
* [Java review coverage](review-coverage.md) - what has been read line-by-line, what has not, and the defect classes that keep recurring (2026-08-18).
* `platform/inspecto-api/` — dependency-free leaf: the `@PublicApi` annotation (`inspecto-api`).
* `platform/inspecto-util/` — leaf (w.r.t. `com.gamma`): DuckDB access + CSV/file/tar helpers (`inspecto-util`).
* `platform/inspecto-config/` — config spec/codec/safety (`inspecto-config`); depends on fp-api + fp-util (its TOON decode is util's `ToonHelper.decode`).
* `platform/inspecto-sql/` — sandboxed DuckDB SQL: `SqlSandbox`/`SqlOracle`/`SqlGuard`/`SqlViews` (`inspecto-sql`); depends on fp-api/config/util.
* `platform/inspecto-etl/` — `com.gamma.etl`: ingest/transform/output core — `PipelineConfig`, ingesters, batch planning, quarantine, partitioned Parquet (`inspecto-etl`).
* `platform/inspecto-event/` — `com.gamma.event` + `metrics`: the Operational-Intelligence event store + metric registry; owns `logback.xml` (`inspecto-event`).
* `platform/inspecto-acquire/` — `com.gamma.acquire`: connectors, connection profiles/registry/workbench, fingerprint ledger, stability gate, retry/circuit-breaker (`inspecto-acquire`).
* `platform/inspecto-engine/` — the engine cluster: `signal`/`query`/`pipeline`/`inspector`/`ingester`/`ops`/`job`/`enrich`/`alert`/`notify`/`catalog`; holds the fat-jar entry points (`inspecto-engine`).
* [Core](engine.md) - `inspecto/` — the composition root: control plane + application packages, ships `inspecto.jar`. The engine was extracted to sibling modules `inspecto-engine`/`-etl`/`-event`/`-acquire` in WS-D (see [reactor.md](reactor.md)).
* [Connectors](connectors.md) - `providers/inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* [Agent](agent.md) - `features/inspecto-agent/` — optional AI assist skills (vendored kernel layer + eoiagent model transport).
* [Agent (hosted)](agent-hosted.md) - `providers/inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
* [Security](security.md) - `providers/inspecto-oidc/` — Standard-only OIDC auth (`inspecto-oidc`),
  reactor-gated behind the `edition-standard` Maven profile — see also [auth & security](../editions/auth-security.md).
