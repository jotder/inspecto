# Modules

The backend Maven modules. Directory names were renamed 2026-06-12 and regrouped into `spi/ platform/ features/
la/ providers/` on 2026-10-07 (D-MR2, directories only — the artifactIds were not changed, so dir ≠ artifactId; name a
module to Maven by artifactId, `-pl :inspecto-engine`). The product module `inspecto/` stays at the root. The core
stays lean — network, hosted-AI, and auth dependencies are isolated in their own modules. The classification of every
module (build role · offering role · binding time) and the Offerings that compose them:
[module-taxonomy.md](../module-taxonomy.md). Reactor shape, version management, and the module-extraction playbook:
[reactor.md](reactor.md). Source of truth for the list: root `pom.xml` plus each module's
`META-INF/inspecto/module.toon` — never this page.

# Modules

* [Reactor & modularization](reactor.md) - build order (history), parent `dependencyManagement`, extraction rules (S5 + WS-D, 2026-07-21).
* [Module taxonomy](../module-taxonomy.md) - the manifest, the three axes, Offerings, the Installed/Enabled/Permitted gates.
* [Link Analysis backend architecture](link-analysis.md) - the `la/` modules + entity store: dependency direction, Investigation store port (FS / Postgres), Drafts, masking, link index, Graph Run, `/inv` gate order, host wiring, editions (2026-10-09).
* [Java review coverage](review-coverage.md) - what has been read line-by-line, what has not, and the defect classes that keep recurring (2026-08-18).

## Root

* [Core](engine.md) - `inspecto/` — manifest id `processor`: the composition root (control plane + application packages); ships `inspecto.jar`. The engine was extracted to sibling modules in WS-D (see [reactor.md](reactor.md)).

## `spi/` — contracts (buildRole contract, always present)

* `spi/inspecto-audit-spi/` — the audit contract (`audit-spi`).
* `spi/inspecto-auth-spi/` — the authentication and capability contract (`auth-spi`).
* `spi/inspecto-http-spi/` — the `RouteModule` contract (`http-spi`).

## `platform/` — foundations and platform modules (offeringRole base, always present)

* `platform/inspecto-api/` — dependency-free leaf: the `@PublicApi` annotation (`api`).
* `platform/inspecto-util/` — leaf (w.r.t. `com.gamma`): DuckDB access, CSV/file/tar helpers, TOON helpers, the module-manifest activator (`util`).
* `platform/inspecto-config/` — config spec/codec/safety (`config`); its TOON decode is util's `ToonHelper.decode`.
* `platform/inspecto-sql/` — sandboxed DuckDB SQL: `SqlSandbox`/`SqlOracle`/`SqlGuard`/`SqlViews` (`sql`).
* `platform/inspecto-etl/` — `com.gamma.etl`: ingest/transform/output core, step registry, quarantine, partitioned Parquet (`etl`).
* `platform/inspecto-event/` — `com.gamma.event` + `metrics`: the Operational-Intelligence event store + metric registry; owns `logback.xml` (`event`).
* `platform/inspecto-workflow/` — Workflow and SLA models (`workflow`).
* `platform/inspecto-access/` — access policy: roles, grants, row scope, audit trail (`access`).
* `platform/inspecto-acquire/` — `com.gamma.acquire`: Collectors, connection profiles/registry/workbench, fingerprint ledger, stability gate, retry/circuit-breaker (`acquire`).
* `platform/inspecto-entity-store/` — the entity store (`entity-store`).
* `platform/inspecto-engine/` — the engine cluster: `signal`/`query`/`pipeline`/`inspector`/`ingester`/`ops`/`job`/`enrich`/`alert`/`notify`/`catalog`/`decision` (`engine`).

## `features/` — optional modules (offeringRole optional)

* [Agent](agent.md) - `features/inspecto-agent/` — optional AI assist skills (`agent`).
* `features/inspecto-intelligence/` — AI assist and intelligence (`intelligence`).
* `features/inspecto-backup/` — backup/backup_verify/restore tasks (`backup`).
* `features/inspecto-entity-list/` — Entity Lists + the shared entity fact log (`entity-list`; requires `entity-store`).
* `features/inspecto-exchange/` — cross-Space publication and ingestion contracts (`exchange`; feature `exchange`).
* `features/inspecto-observability/` — the Prometheus exposition and the `/events*` feed (`observability`; feature `events`).
* `features/inspecto-ops/` — operational objects and Incidents; owns the OBJECTS/LINKS/NOTES/TAGS store families (`ops`; feature `ops`).
* `features/inspecto-case-management/` — Case Management (`case-management`; feature `cases`; requires `ops`).
* `features/inspecto-reconciliation/` — Reconciliation (`reconciliation`; feature `reconciliation`).
* `features/inspecto-scoring/` — Scoring (`scoring`; feature `scoring`; requires `entity-list`).
* `features/inspecto-action-requests/` — Action Requests (`action-requests`; feature `actionRequests`; requires `notify-channels`); page: [action-requests](../control-plane/action-requests.md).
* `features/inspecto-regulatory-reporting/` — Regulatory Reporting (`regulatory-reporting`; feature `regulatoryReporting`; requires `ops`); page: [regulatory-reporting](../control-plane/regulatory-reporting.md).
* `features/inspecto-screening/` — Screening (`screening`; feature `screening`; requires `entity-list`); page: [screening](../control-plane/screening.md).
* `features/inspecto-anomaly/` — Anomaly Detection (`anomaly`; no routes yet; config kind `anomaly-model`, Job Type `anomaly.score`); design: [anomaly-detection-design](../../../superpower/anomaly-detection-design.md).

## `la/` — link analysis (optional)

* `la/inspecto-la-graph/` — graph algorithms (`la-graph`). `la/inspecto-la-core/` — core and ports (`la-core`). `la/inspecto-la-storage/` — storage (`la-storage`). `la/inspecto-la-api/` — link-analysis and geo routes (`la-api`; feature `geoLink`). `la/inspecto-geo-link/` — geo and link adapters over platform data (`geo-link`). `la/inspecto-la-store-pg/` — the PostgreSQL store (`la-store-pg`, Enterprise provider).

## `providers/` — implementations chosen by deployment profile (offeringRole provider)

* [Connectors](connectors.md) - `providers/inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* `providers/inspecto-connectors-kafka/` — the premium Kafka stream connector (`connectors-kafka`).
* [Agent (hosted)](agent-hosted.md) - `providers/inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
* [Security](security.md) - `providers/inspecto-oidc/` — OIDC auth (`oidc`); split by D-MR6 into `providers/inspecto-secrets/` (file-keystore secrets) and `providers/inspecto-geo-country/` (MaxMind resolver). Professional and up — see also [auth & security](../editions/auth-security.md).
* `providers/inspecto-policy/` — attribute-based access policy (`policy`, Enterprise).
* `providers/inspecto-notify-channels/` — webhook + SMTP notification transports (`notify-channels`).
* `providers/inspecto-telecom-asn1/` — the telecom ASN.1 decoder (`telecom-asn1`) over `providers/asn-parser/` (nested reactor, moves as a unit).
* `providers/inspecto-demo-auth/` — the demo authenticator; no edition bundles it, only `package.ps1 -DemoAuth`.
