# Editions — Personal / Standard / Enterprise

> Editions are **build/packaging flavors over one codebase** — the same `ServiceLoader`-module + `-D`-flag
> mechanism already used to omit the hosted-AI SDKs from air-gapped builds. **Editions are NEVER git
> branches** (see [`BRANCHING.md`](BRANCHING.md)); there is no `personal`/`standard` branch. An edition is
> *which modules are assembled* from a given commit.

## Matrix

| Aspect | **Personal** | **Standard** | **Enterprise (partially shipped)** |
|---|---|---|---|
| Transport | Plain HTTP; binds **every interface** unless you set `-Dcontrol.bind` — see the note below | HTTPS (`HttpsServer` + keystore; FIPS provider for Gov) | HTTPS, TLS at LB/gateway |
| AuthN | **None** | **Delegated to an external IAM** (Keycloak / WSO2 / Okta / Entra) — app is an OIDC/OAuth2 **resource server** | Same, centralized IAM + token introspection |
| AuthZ | None | **RBAC + ABAC** from IAM token claims/groups | RBAC/ABAC + per-tenant boundaries |
| User mgmt / LDAP / SAML | n/a | **IAM's job** (federates AD/LDAP, brokers SAML) | IAM's job |
| Secrets | env / file | `SecretsProvider` (file + OS keystore, or Vault) | Vault / cloud secrets manager |
| At-rest encryption | optional (volume) | Volume encryption + AES-GCM for stored creds | Volume + shared-store encryption |
| Audit | local append-only logs — **recorded in every edition and readable on Personal** via the core `/audit/search` + `/audit/export` routes (EDG-01 cell 6, 2026-09-08). ⛔ Not the same thing as CP-13's events feed, which IS gated: gating the feed alone would have taken this row's promise away, so the audit projection deliberately stayed in core. | **actor-attributed**, tamper-evident compliance log | shared, centralized audit store |
| State | local disk | local disk (or optional Postgres — driver bundled as the `postgresql.jar` sidecar, selected by `-Dinspecto.db`) | **shared backends** (Postgres / object store / Vault) |
| Scheduler | in-JVM | in-JVM | **distributed coordination** (leader election / locks) |
| Compliance scope | none | SOC 2 / ISO 27001 / FedRAMP / HIPAA / PCI | inherits Standard + multi-node controls |
| Packaging | core fat-JAR, `-Dauth.mode=none` | core + `security` + `notify-channels` + `backup` + `geo-link` + `exchange` modules, `-Dauth.mode=oidc`, TLS on | + `policy` module (ABAC; shipped) — later + shared-store modules, coordinator |

> ### 🔴 Listen address — read this before running Personal on a shared network
>
> **The control plane binds every interface by default, in every edition.** `-Dcontrol.bind=<host-or-IP>`
> restricts it (`-Dcontrol.bind=127.0.0.1` for loopback); an unresolvable value fails the boot rather
> than falling back to the wider address.
>
> ⚠ **This matters most on Personal, which ships no authenticator at all.** Left on the default, a
> Personal install serves an unauthenticated control plane — including the config-write routes — to
> every host that can reach the port. Set `-Dcontrol.bind=127.0.0.1`, or firewall the port, for any
> single-user install.
>
> The default is deliberate (2026-08-29): narrowing it to loopback would silently make every deployed
> Standard and Enterprise install unreachable on upgrade, so the flag lets a deployment restrict itself
> rather than changing what an existing one does. ⛔ **This table previously read "bind localhost only"
> for Personal, which the code never enforced** — the claim, not the behaviour, was the defect. Pinned by
> `ControlApiBindTest`.

## Assembly model (how an edition is produced)

| Mechanism | Used for |
|---|---|
| **Separate Maven module** (e.g. `inspecto-security`, `inspecto-notify-channels`, `inspecto-backup`, `inspecto-geo-link`, `inspecto-exchange`, `inspecto-metrics`) | Standard-only code (OIDC resource-server, TLS, RBAC; the webhook + SMTP delivery transports; the backup/restore maintenance tasks; the geo map + link analysis routes; cross-space sharing; the Prometheus `/metrics` exposition). Personal simply doesn't bundle it. |
| **Maven profiles** (`-Pedition-standard` / `-Pedition-enterprise`) | Which modules + shade includes go into the fat-JAR. `edition-enterprise` = `edition-standard` + `inspecto-policy`. 🔴 **Corrected 2026-09-07: there is no `edition-personal` profile** — the parent POM declares exactly two (`pom.xml:68,77`). **Personal is the DEFAULT**, i.e. the reactor with no profile at all, which is what `package.ps1` does (`if ($Edition -ne 'Personal')` guards every Standard+ step). ⚠ The old text named a third flag; Maven only *warns* on a profile that does not exist and then builds the default, so a shift following this table would have got the right bundle for the wrong reason and learnt nothing from the build. |
| **`ServiceLoader`** | Runtime discovery — absent module ⇒ the no-op impl is the only one found (mirrors the optional assist agent). **Since 2026-09-07 this includes whole route groups**: `com.gamma.control.RouteModule` is a public SPI, discovered and registered *after* the built-in list (first-match order is preserved; a colliding `(method, pattern)` fails the boot). |
| **`-D` flags** (`-Dauth.mode`, TLS on/off) | Runtime toggles within an edition. |
| **`package.ps1 -Edition …`** | Emits the per-edition bundles from one build. |

The core engine never contains `if (edition == …)` branches; it depends on SPIs (`Authenticator`,
`SecretsProvider`, …) and the **build** decides which implementation ships. One SemVer version spans all
editions; artifacts differ by classifier (`-personal` / `-standard`).

## Status — core is auth-free (2026-06-16)

The hand-rolled bearer-token control plane that earlier versions baked into `ControlApi`
(per-route `Scope` + `Tokens` + `requireAuth`, `-Dcontrol.token` / `-Dassist.*.token`, the
Angular token-paste `/connect` screen + `authInterceptor` + route guard) has been **removed
from the common core / `master`**. The Personal edition is now genuinely auth-free: every
ControlApi route is open and the SPA boots straight to the dashboard with no login.

Authentication is no longer a core concern — it becomes an **edition** concern. The
Standard/Enterprise editions re-introduce it out-of-band (see below) behind an `Authenticator`
SPI seam, so the engine keeps no auth code and fixes/features land once in common. This realigns
the code with the model already described here: editions add modules; they are never branches.

**Status (2026-07-06, W6):** the `Authenticator` SPI (`com.gamma.control.Authenticator`/`Subject`)
and the AuthN/AuthZ gate in `ControlApi.dispatch` are shipped in the core (edition-neutral — a no-op
when no implementation is on the classpath). The `inspecto-security` module ships the Standard
implementation (`OidcAuthenticator`, Nimbus JOSE+JWT + JWKS) and is reactor-gated behind the
`edition-standard` Maven profile, so it is never built or resolved by a routine Personal build.
`package.ps1 -Edition Standard` builds and bundles it; see `docs/superpower/api-contract-design.md`
§10 W6 for the full slice and `docs/api/deployment/` for WSO2/Keycloak blueprints.

## Security direction (Standard)

Authentication is **delegated to an external IAM** — the app validates IAM-issued JWTs (Nimbus + JWKS:
issuer/audience/expiry) and enforces authorization from claims. The IAM owns user management, AD/LDAP
federation, and SAML brokering, so none of that lives in the Java core. The Angular UI uses OIDC
Authorization Code + PKCE (public client, no shipped secret).

This is **incremental hardening on the existing framework-free core** — *not* a Spring Boot / Quarkus
migration. At 5–15 users with a capabilities-based RFP, a framework buys nothing the IAM + small libraries
don't, and a lean dependency tree is a FedRAMP asset (small SBOM, fewer CVEs to attest). The full
assessment + 7-phase hardening roadmap is maintained alongside this repo's planning notes.

## Enterprise (partially shipped 2026-07-23) — keep the seams open

**Shipped — the ABAC policy engine (rbac-abac-plan §4):** the `edition-enterprise` Maven profile
(= `edition-standard` + `inspecto-policy`). The module registers a `PolicyEngine` on the core's
`com.gamma.control.AccessDecider` ServiceLoader seam; authored per-space Access Policies
(`access-policies.toon`, the shared `Conditions` grammar) then evaluate at the route-level authorize
stage (deny = 403) and the row-level `RowScope` filter (deny = the SEC-7d 404/filtered contract).
Personal and Standard never bundle the module and behave byte-identically. Build/test:
`mvn -o clean test -Pedition-enterprise`.

**Shipped 2026-07-25 — the packaging flavor.** `package.ps1 -Edition Enterprise` now emits a deployable
Enterprise bundle, closing the last gap between "the profile builds" and "an operator can ship it". It is
a **superset of Standard** (mirroring the profile relation), so it builds `inspecto-security` *and*
`inspecto-policy` under `-Pedition-enterprise` and bundles both `inspecto-security.jar` and
`inspecto-policy.jar`. The generated `serve.sh`/`serve.bat` auto-detect edition from the bundle
contents exactly as they already did for Standard: security jar ⇒ `Standard` (+ `-Dauth.mode=oidc`),
plus policy jar ⇒ `Enterprise`. **No new runtime flag exists or is needed** — `inspecto-policy` is found
solely through `META-INF/services/com.gamma.control.AccessDecider`, so the classpath entry *is* the
switch. Personal bundles remain byte-for-byte unchanged.

**Shipped 2026-07-24 — per-tenant space isolation (A4 = SPC-5):** `PolicyEngine.SEED` carries two
engine-resident seeded policies (`space-isolation`, `space-isolation-rows`) denying access outside
the subject's home space; they engage only once a `space` claim is mapped via `roles.toon`
`identity: {attributeClaims}`, exempt `canConfigureAccess` holders, and are tailorable/disableable
by authoring a policy of the same name in `access-policies.toon`.

Already fits: stateless stage-1 engine, **stateless JWT auth** (no server session ⇒ horizontal scale),
pluggable `DbStatusStore` (Postgres) / `ObjectStore` db backend / `ParquetEventStore`, `SecretsProvider`.
Will need later (don't preclude now): distributed scheduler coordination, all state on shared backends
(Postgres + object store for Parquet + shared secrets), work distribution.

## Feature × edition matrix (working board, opened 2026-09-02)

**How to read and work this board.** One row per product feature, one cell per edition. A cell is
addressed as `<row-id>/<P|S|E>` (e.g. `SEC-03/S`) so we can decide, build and deliver **cell-wise**.
Rows are grouped by the FEATURE_INVENTORY §1 areas plus the control-plane / UI / security / compliance
surfaces EDITIONS.md §Matrix already splits. **Editions are build flavors of one codebase** — a core
feature is in every edition by construction, so most Personal/Standard/Enterprise cells agree; the
board exists for the cells that *differ* or are *undecided*.

| Cell | Meaning |
|---|---|
| ✅ | shipped in this edition (the feature is in the bundle and exercised) |
| 🟡 | partial — shipped with a stated gap (see Notes) |
| 🔲 | planned for this edition, not built |
| — | deliberately not in this edition (a decision, not a gap) |
| ❓ | undecided — needs an operator call before anyone builds |

⚠ Keep the row IDs stable once referenced; append new rows at the end of their area. A cell that
changes state carries the date in Notes. Source of truth for *what* a feature is stays
[`FEATURE_INVENTORY.md`](FEATURE_INVENTORY.md) §1; this table only answers *which edition*.

### Core engine — platform capabilities (identical across editions by construction)

> Split 2026-09-02: the per-processor rows (parsers, transforms, sinks, acquisition adapters — the former
> ING-03/04, PRS-*, XFM-01..03, OUT-01..03) moved to the **Step Processors** table below; this table keeps
> the platform capabilities no single processor owns.

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| ING-01 | Multi-pipeline poll cycle (`active:` gate, M..N parallelism, file-pattern glob) | ✅ | ✅ | ✅ | FEATURE_INVENTORY §A |
| ING-02 | Consignment formation (`collector.consignment: max_files / max_bytes / order`) | ✅ | ✅ | ✅ | moved to the Collector 2026-09-02 (CONSIGNMENT-HOME-1); legacy `processing.batch` dual-read |
| ING-05 | Unpack stage (zip/tar/gz/bz2/Z, nested archives) | ✅ | ✅ | ✅ | |
| SCH-01 | Schema registry: field types (all DuckDB scalars, fail-closed), rules (`DIRECT`/`EXPR`/`CONCAT_DT`/`FILENAME_DATE`), multi-format dates | ✅ | ✅ | ✅ | §C |
| SCH-02 | Quarantine / reject routing | ✅ | ✅ | ✅ | |
| SCH-03 | Field classification metadata (PII / INTERNAL) | ✅ | ✅ | ✅ | metadata only — no edition enforces masking on it (see SEC-08) |
| XFM-04 | Decision Rules (space-registry, rule-routed outputs) | ✅ | ✅ | ✅ | keep a pipeline on the flat lane when they route rows |
| OUT-04 | Auto-chunking of huge files, DuckDB scratch/memory tuning | ✅ | ✅ | ✅ | |
| JOB-01 | Job framework (`enrich`, `report`, `maintenance`, `pipeline`, `sql.template`, `consignment.process`, `recon.run`, `caserule.evaluate`, `objects.analytics`, `mail.send`) | ✅ | ✅ | ✅ | §F |
| JOB-02 | Triggers: cron, `on_pipeline`, `on_signal` + `when` guards, `catch_up`, manual | ✅ | ✅ | ✅ | |
| JOB-03 | Maintenance task library (cleanup, ledger/runlog/notification/receipt/dedup/event prune, incident_purge, backup/restore/verify, storage report/trend, compact, materialize, db_maintenance) | ✅ | ✅ | ✅ | `event_prune` added 2026-09-02 (COMPLY-3) |
| JOB-04 | Consignment concurrency broker (priority shares, intake caps) | ✅ | ✅ | ✅ | |

### Step Processors (one row per processor; the board's authoring surface)

Generated from the processor catalog (`ProcessorCatalog`, served on `GET /pipelines/processor-catalog`, mirrored to
`processor-catalog.contract.json`) — edit the catalog, regenerate this table; do not hand-edit rows. **Status**: ✅ delivered
(maps onto an existing node type / engine capability, named in *Maps to*), 🟡 partial (a neighbour capability covers part
of it — the gap is in Notes), 🔲 planned. Every processor is VISIBLE in the editor palettes; 🔲 and 🟡-without-node entries
render inactive. Edition cells default to the same value in all three unless a board decision says otherwise (SEC-08 →
E-only for the two compliance processors; CP-09/CP-11/CP-15/OPS-06 → not for Personal). Row ids are `SP-<family>-<nn>`.

| ID | Processor | Family | P | S | E | Maps to | Notes |
|---|---|---|---|---|---|---|---|
| SP-ACQ-01 | 📁 Local / NFS directory watcher (`acquisition.file.local`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | LocalFileSystemConnector — the default collector |
| SP-ACQ-02 | 🔒 SFTP / FTPS remote ingest (`acquisition.file.sftp`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | inspecto-connectors (sftp, ftps; key auth, bastion tunnel, host-key pinning) |
| SP-ACQ-03 | 🗄️ JDBC / SQL query batch reader (`acquisition.db.jdbc`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | the db-export connector (`connector: db`, watermark column) |
| SP-ACQ-04 | 🧱 Dataset entry (re-ingest a registered Dataset) (`acquisition.dataset`) | Collectors & Ingestion | ✅ | ✅ | ✅ | `acquisition` | UI-S7: `connector: dataset` + `on:dataset` trigger |
| SP-ACQ-05 | 📑 Multi-sheet Excel workbook ingest (`acquisition.file.excel`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `parser.xlsx` | the xlsx PARSER is delivered; per-sheet workbook fan-out as an acquisition is not |
| SP-ACQ-06 | ☁️ AWS S3 object ingest (`acquisition.file.s3`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (s3 connector, SDK-free SigV4; covers MinIO / GCS-interop); no proven end-to-end acquisition-node run |
| SP-ACQ-07 | 🌐 Azure Blob & ADLS Gen2 ingest (`acquisition.file.azure`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (azure blob connector); ADLS Gen2 semantics not proven |
| SP-ACQ-08 | 🪣 Google Cloud Storage ingest (`acquisition.file.gcs`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (gcs connector, native JSON API + service-account OAuth2); no proven end-to-end acquisition-node run |
| SP-ACQ-09 | 📤 Apache Kafka consumer (`acquisition.stream.kafka`) | Collectors & Ingestion | 🟡 | 🟡 | 🟡 | `acquisition` | Connection kind exists (kafka); consumer-group ingest as a Collector not proven |
| SP-ACQ-10 | 📨 Apache Pulsar consumer (`acquisition.stream.pulsar`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-11 | 📬 AWS Kinesis / SQS ingest (`acquisition.stream.kinesis`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-12 | 🐰 RabbitMQ AMQP subscriber (`acquisition.stream.rabbitmq`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-13 | 📡 MQTT IoT telemetry ingest (`acquisition.stream.mqtt`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-14 | 🔄 Change Data Capture (Debezium) (`acquisition.cdc.debezium`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-15 | 💾 Delta Lake table reader (`acquisition.lake.delta`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-16 | 🧊 Apache Iceberg table ingest (`acquisition.lake.iceberg`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-17 | ❄️ Snowflake / BigQuery ingest (`acquisition.db.cloud`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-18 | 🌐 REST API poller & paged ingest (`acquisition.api.rest`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-19 | 🪝 HTTP webhook listener endpoint (`acquisition.api.webhook`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-ACQ-20 | 🔌 gRPC stream receiver (`acquisition.api.grpc`) | Collectors & Ingestion | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-01 | 📄 Delimited / CSV / TSV / PSV parser (`parser.delimited`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.delimited` |  |
| SP-PRS-02 | 📦 Fixed-width column slicer (`parser.fixedwidth`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.fixedwidth` | text (`record: line`) and fixed-length binary (`record: bytes`) |
| SP-PRS-03 | 🧬 JSON object & JSON Lines (NDJSON) parser (`parser.json`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.json` |  |
| SP-PRS-04 | 📑 Excel workbook parser (`parser.excel`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.xlsx` | needs the DuckDB `excel` extension in the bundle (multiformat X1) |
| SP-PRS-05 | 📑 XML / XPath / DOM unpacker (`parser.xml`) | Extraction & Format Parsers | 🟡 | 🟡 | 🟡 | `parser.plugin` | tree→segments bridge ships XML ingests; no XPath selector grammar yet |
| SP-PRS-06 | 📡 ASN.1 BER telecom CDR decoder (`parser.asn1.ber`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.asn1` | asn-parser reactor (decoders, vendor plugins) |
| SP-PRS-07 | 🔎 Named-group regex extractor (`parser.pattern.regex`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.text_regex` |  |
| SP-PRS-08 | 🧩 Custom ingester plugin (segments, multi-event) (`parser.plugin`) | Extraction & Format Parsers | ✅ | ✅ | ✅ | `parser.plugin` | ParserPlugin SPI; `segments: {CALL, SMS}` |
| SP-PRS-09 | 🏷️ Key-value / logfmt parser (`parser.keyvalue`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-10 | 📜 YAML document slicer (`parser.yaml`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-11 | 📜 ASN.1 PER / XER / DER decoder (`parser.asn1.per`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-12 | 📠 Mainframe EBCDIC & COBOL copybook (`parser.mainframe.ebcdic`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-13 | ⚡ Protocol Buffers decoder (`parser.binary.protobuf`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-14 | 🦅 Apache Avro binary decoder (`parser.binary.avro`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-15 | 🌐 PCAP network packet slicer (`parser.binary.pcap`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-16 | 📜 Grok / Logstash expression matcher (`parser.pattern.grok`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-PRS-17 | 🖥️ Syslog RFC 5424 / RFC 3164 parser (`parser.pattern.syslog`) | Extraction & Format Parsers | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-01 | 🛡️ Schema registry & structural rejects (`quality.schema.validator`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `parser` | the declared schema on the Parse Step: typed fields, TRY_CAST at ingest, structural rejects → quarantine. ⛔ Only HALF of the old "Schema validator & type coercion" — coercion as an AUTHORING act folded into `transform.record` (2026-09-04). What cannot fold is the schema CONTRACT: the declared source column + target type are the cast-failure audit's denominator, and `SchemaCompatibility` gates edits to them BACKWARD |
| SP-DQ-02 | ⚠️ Constraint & range checker (Expectations) (`quality.constraint.check`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `expectation` | Expectations evaluated per Dataset (`ExpectationEvaluator`); not a mid-chain step |
| SP-DQ-03 | 🧼 Exact-key deduplicator (within a Consignment) (`quality.dedup.exact`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.dedup` | `scope: consignment` (default) |
| SP-DQ-04 | ⏱️ Sliding time-window deduplicator (`quality.dedup.windowed`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.dedup` | D-9: `scope: window(P4D)` + the durable dedup ledger |
| SP-DQ-05 | 🗂️ File-grain duplicate guard (path / checksum / metadata / marker) (`quality.dedup.file`) | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `acquisition` | Collector `duplicate:` policy + marker dedup — a Guarantee, rides the Collector |
| SP-DQ-06 | 🧬 Schema drift & new-field detector (`quality.schema.drift`) | Data Quality, Validation & Cleansing | 🟡 | 🟡 | 🟡 | `expectation` | multi-schema dispatch refuses unknown shapes; no drift REPORT yet |
| SP-DQ-07 | 🔍 Cluster & edit value normalizer (`quality.cluster.edit`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-08 | 🔍 Fuzzy string (Jaro-Winkler) matcher (`quality.match.fuzzy`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-09 | 🧮 Inline stream profiler & statistics (`quality.profiler.inline`) | Data Quality, Validation & Cleansing | 🟡 | 🟡 | 🟡 | `storage_report` | storage/completeness KPIs exist; no per-column profile step |
| SP-DQ-10 | 📊 Statistical & reservoir sampler (`quality.sample.reservoir`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-11 | 🔤 Character map & code page transcoder (`quality.cleanse.transcode`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-12 | 🔒 PII masking & tokenization (`quality.pii.mask`) | Data Quality, Validation & Cleansing | — | — | 🔲 | — | board SEC-08 — Enterprise only |
| SP-DQ-13 | 🔑 One-way salted cryptographic hasher (`quality.crypto.hash`) | Data Quality, Validation & Cleansing | 🔲 | 🔲 | 🔲 | — |  |
| SP-DQ-14 | 🛡️ GDPR / CCPA field redactor (`quality.compliance.redact`) | Data Quality, Validation & Cleansing | — | — | 🔲 | — | board SEC-08 — Enterprise only |
| SP-XFM-01 | 🧮 Record Transformer (`transform.record`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.sql` | ONE Step that shapes a record: sanitize (trim/pad/replace/case), cast, rename and computed columns — one row per output field over a typed function catalog, generating the SELECT it saves. Folded 2026-09-04 from `transform.expression` + `transform.cast` + `quality.cleanse.trim`, which were three catalog labels over this one grid. `transform.map` was deleted 2026-09-05: a stored `mapping.rules[]` is read as `fields[]` (EXPR → custom, CONCAT_DT → date.concat_parts, FILENAME_DATE → date.from_filename) |
| SP-XFM-02 | 🔽 Row filter (pre-parse regex / post-map predicate) (`transform.filter`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.filter` |  |
| SP-XFM-03 | 🔀 Router — case / clone branches with mid-branch steps (`transform.route`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.route` |  |
| SP-XFM-04 | ∑ Group-by summarizer (measures grammar) (`transform.summarize`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.summarize` |  |
| SP-XFM-05 | 🤝 Reference-store join (versioned references) (`transform.join`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.join` | at rest only — refused mid-branch (no reference resolver on the ingest lane) |
| SP-XFM-06 | 🗺️ Lookup & static map transcoder (`transform.lookup`) | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.lookup` | inline key=value map over one column (2026-09-06); a versioned reference is transform.join |
| SP-XFM-07 | 🔀 Dynamic pivot / transpose (`transform.matrix.pivot`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-08 | 🔄 Unpivot / column flattener (`transform.matrix.unpivot`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-09 | 🏆 Rank & Top-N pruner (`transform.analytics.rank`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-10 | 💥 Array / object exploder & flattener (`transform.explode`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — | the grandfathered `transform.split` node type is the read-only ancestor |
| SP-XFM-11 | 🤝 Presorted stream merge joiner (`transform.join.merge`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — | the grandfathered `transform.merge` node type is the read-only ancestor |
| SP-XFM-12 | 🏛️ Slowly changing dimension (SCD Type 2) (`transform.dim.scd2`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-13 | 🔑 Monotonic surrogate key generator (`transform.key.surrogate`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-14 | 🏷️ DML row-action strategy flagger (`transform.dml.strategy`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-15 | ⚖️ Dataset differ & change compare (`transform.diff.compare`) | Transformers & Dimensional Modeling | 🟡 | 🟡 | 🟡 | `recon` | Reconciliation boards compare Datasets; not a chain step |
| SP-XFM-16 | 🏗️ Hierarchical XML / JSON document builder (`transform.builder.xml`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-17 | 📊 IFRS 15 / IFRS 9 revenue recognition engine (`transform.fintech.ifrs`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-18 | 📱 SIM box & bypass fraud detector (`transform.telecom.simbox`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-19 | 💵 Tariff, rating & usage billing engine (`transform.telecom.rating`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-20 | 🌍 Roaming TAP3 / CIBER surcharger (`transform.telecom.roaming`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-XFM-21 | 🚨 Velocity & impossible-travel anomaly (`transform.fintech.velocity`) | Transformers & Dimensional Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-01 | 🏛️ Level-of-detail (LOD) fixed aggregator (`transform.analytics.lod`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-02 | 🏛️ LOD include / exclude context aggregator (`transform.analytics.lod_context`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-03 | ⏱️ Time-grain resampler & gap imputer (`transform.timeseries.resample`) | Analytics, Time-Series & Semantic Modeling | 🟡 | 🟡 | 🟡 | `measure-grammar` | time grains exist in Studio queries (`QuerySpec.grains`); no resampling step |
| SP-BI-04 | 📈 Period-over-period (YoY / MoM / WoW) shift (`transform.timeseries.shift`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-05 | 📊 Running calculations & moving averages (`transform.analytics.running`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-06 | 🔮 Time-series forecaster (Holt-Winters) (`transform.timeseries.forecast`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-07 | 📐 Semantic KPI calculator & Measure formulas (`transform.semantic.metric`) | Analytics, Time-Series & Semantic Modeling | 🟡 | 🟡 | 🟡 | `measure-grammar` | the Measure grammar (`count | agg(field)`) serves Studio + summarize; no named-KPI layer |
| SP-BI-08 | 🏷️ Template & runtime parameter injector (`transform.param.jinja`) | Analytics, Time-Series & Semantic Modeling | 🟡 | 🟡 | 🟡 | `sql.template` | the `sql.template` job resolves `$name` tokens; no Jinja |
| SP-BI-09 | 📦 Histogram & quantile binner (`transform.data.binning`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-10 | ✂️ Smart custom string splitter (`transform.string.smart_split`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-11 | 🗺️ H3 hexagonal & geohash grid indexer (`transform.geo.h3`) | Analytics, Time-Series & Semantic Modeling | — | 🔲 | 🔲 | — | needs the DuckDB `spatial`/`h3` extension — board CP-09 gate |
| SP-BI-12 | 📍 Spatial polygon & point-in-polygon intersect (`transform.geo.spatial_join`) | Analytics, Time-Series & Semantic Modeling | — | 🔲 | 🔲 | — | needs the DuckDB `spatial` extension — deliberately not loaded (SqlSandbox) |
| SP-BI-13 | 🚨 Outlier detector & IQR boxplot fencer (`transform.stats.outlier`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-BI-14 | 🧮 Pearson & Spearman correlation matrix (`transform.stats.correlation`) | Analytics, Time-Series & Semantic Modeling | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-01 | 📚 Reference-table enrichment (`*_enrich.toon`, Stage-2) (`enrichment.reference`) | Enrichment, Entity Resolution & AI/ML | ✅ | ✅ | ✅ | `enrichment` | the shipped enrichment job + versioned references |
| SP-ENR-02 | 🌍 GeoIP & ISP geolocation enricher (`enrichment.geoip`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-03 | ⚡ Redis / in-memory cache lookup (`enrichment.redis`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-04 | 🌐 Dynamic microservice REST enricher (`enrichment.rest`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-05 | 🗄️ Parameterized stored-procedure caller (`transform.db.procedure`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-06 | 🔗 Master entity resolution & record linkage (`enrichment.entity.link`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-ENR-07 | 🕸️ Graph cluster & connected-component tagger (`enrichment.graph.cluster`) | Enrichment, Entity Resolution & AI/ML | — | 🟡 | 🟡 | `link-analysis` | link-analysis VIEW ships (projection); no tagging step |
| SP-ENR-08 | 🤖 ONNX Runtime embedded inference (`ml.inference.onnx`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — | the optional `inspecto-intelligence` module carries onnxruntime; never bundled |
| SP-ENR-09 | 🏷️ LLM zero-shot classifier & tagging (`ml.llm.classify`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — | assist/intelligence agents are never bundled (CP-14) |
| SP-ENR-10 | 📐 Text embeddings & vector generator (`ml.embedding.vector`) | Enrichment, Entity Resolution & AI/ML | 🔲 | 🔲 | 🔲 | — |  |
| SP-CTL-01 | 🕳️ File sequence & gap integrity analyzer (`control.file.sequence_analyzer`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `gap` | Collector `gap_detection: {sequence}` → the gap node + SEQUENCE_GAP events |
| SP-CTL-02 | 🕳️ Sequence-gap & data-loss watchdog (`control.gap.detector`) | Control, Governance & Sequence Integrity | 🟡 | ✅ | ✅ | `gap` | ⚠ **AMENDED 2026-09-08 (EDG-01 cell 7, operator-approved): Personal is now 🟡, not ✅.** The detector itself is unchanged in every edition and a gap still raises the `SEQUENCE_GAP` **event** everywhere — but PROMOTING it to a managed ALERT object is `EventObjectBridge`, which lives in the optional `inspecto-ops` module. So on Personal a gap is detected and evented, and nothing opens an object for it. ⛔ The gating is of the promotion, never the detection or the event: `CollectorService` asks the seam for a subscriber and registers whatever it gets, so an absent module simply contributes none. Standard/Enterprise: gaps raise ALERT objects as before. |
| SP-CTL-03 | 🏷️ Audit metadata & lineage stamper (`control.audit.stamp`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `sink.persistent` | `filename_column` + the per-file/batch/lineage ledgers + `__batch_id` provenance |
| SP-CTL-04 | ⏳ Throttle & rate limiter (`control.throttle`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `acquisition` | Collector `fetch.rate_limit` + intake caps + the concurrency broker |
| SP-CTL-05 | 🚦 Circuit breaker & fallback switch (`control.circuitbreaker`) | Control, Governance & Sequence Integrity | ✅ | ✅ | ✅ | `acquisition` | Collector `circuit_breaker` + `retry` |
| SP-CTL-06 | 🏁 Transaction & commit controller (`control.transaction.commit`) | Control, Governance & Sequence Integrity | 🟡 | 🟡 | 🟡 | `engine` | the BranchCommitCoordinator ledger + bounded COMMIT retry are engine-internal, not authorable |
| SP-CTL-07 | 🚨 Alert rule dispatcher (`control.alert.dispatch`) | Control, Governance & Sequence Integrity | — | ✅ | ✅ | `alert-rule` | Alert Rules over the ledgers → Alerts → channels; board CP-11/CP-15 |
| SP-CTL-08 | ⏱️ SLA timeout & heartbeat monitor (`control.sla.monitor`) | Control, Governance & Sequence Integrity | 🟡 | 🟡 | 🟡 | `completeness-kpi` | completeness KPI + `heartbeat` maintenance task; no SLA object |
| SP-SNK-01 | 📁 Parquet (snappy / zstd / gzip) partitioned store (`sink.file.parquet`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.persistent` | Hive `year=/month=/day=` partitions |
| SP-SNK-02 | 📄 CSV partitioned store (`sink.file.csv`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.persistent` | `output.format: CSV` |
| SP-SNK-03 | 🦆 DuckLake catalog (PostgreSQL) sink (`sink.ducklake`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.persistent` | `output.ducklake` — needs the postgresql sidecar (Standard+) |
| SP-SNK-04 | 👁️ Derived view (no bytes, registered SQL) (`sink.view`) | Sinks, Storage & Destinations | 🟡 | 🟡 | 🟡 | `sink.view` | grandfathered node type; the Dataset/View surface replaced it |
| SP-SNK-05 | ☣️ Quarantine error-log store (`sink.quarantine`) | Sinks, Storage & Destinations | ✅ | ✅ | ✅ | `sink.quarantine` | structural rejects + `errors/<base>_errors.csv` |
| SP-SNK-06 | 📜 Long-term compliance archive (`sink.archive`) | Sinks, Storage & Destinations | — | ✅ | ✅ | `acquisition` | Collector `post_action: MOVE archive_path` + the `backup` maintenance task |
| SP-SNK-07 | 🗄️ Delta Lake persistent table sink (`sink.lake.delta`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-08 | 🧊 Apache Iceberg append / upsert sink (`sink.lake.iceberg`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-09 | 📊 ClickHouse / StarRocks analytical sink (`sink.db.clickhouse`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-10 | 📑 Excel multi-tab report sink (`sink.file.excel`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-11 | 📤 Apache Kafka topic producer (`sink.stream.kafka`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-12 | 📨 AWS SQS / SNS event publisher (`sink.stream.aws`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |
| SP-SNK-13 | 📧 Email & report dispatcher (`sink.notify.email`) | Sinks, Storage & Destinations | — | 🟡 | 🟡 | `mail.send` | the `mail.send` JOB + mail channels; not a chain sink |
| SP-SNK-14 | 🪝 Outbound webhook dispatcher (`sink.api.webhook`) | Sinks, Storage & Destinations | — | 🟡 | 🟡 | `channel` | webhook notification channel exists; not a chain sink |
| SP-SNK-15 | 🕳️ Dead-letter queue (`sink.dlq`) | Sinks, Storage & Destinations | 🔲 | 🔲 | 🔲 | — |  |

**Count:** 119 processors — 34 delivered, 18 partial, 67 planned.

| ~~SP-DQ-09~~ | ~~🧹 Whitespace & string sanitizer (`quality.cleanse.trim`)~~ | Data Quality, Validation & Cleansing | ✅ | ✅ | ✅ | `transform.sql` | **FOLDED into SP-XFM-01 (Record Transformer) 2026-09-04** — it is the `text.trim` / `text.pad_left` / `text.replace` rows of that grid, no longer a separate catalog entry |
| ~~SP-XFM-02~~ | ~~🔄 Field type cast & renamer matrix (`transform.cast`)~~ | Transformers & Dimensional Modeling | ✅ | ✅ | ✅ | `transform.sql` | **FOLDED into SP-XFM-01 2026-09-04** — cast is the `convert.type` row, rename is the Field-name alias |


### Control plane & authoring

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| CP-01 | Control API v1 (JDK HttpServer, envelope, ETag/If-Match, idempotency keys) | ✅ | ✅ | ✅ | |
| CP-02 | Pipeline authoring: graph editor + Recipe view (insert-between, insert-into-branch, undo/redo, snapshots, save-as-template) | ✅ | ✅ | ✅ | insert-into-branch 2026-09-02 |
| CP-03 | Pipeline lifecycle: validate → save → arm/activate → test-run → run → replay; run-level ledgers | ✅ | ✅ | ✅ | |
| CP-04 | Pipeline bundle export/import (server-side, dependency closure) | ✅ | ✅ | ✅ | selective pipeline export/import for the canonical file is a BACKLOG design item |
| CP-05 | Onboarding wizard (Collection → Parse → Schema → Sink) | ✅ | ✅ | ✅ | |
| CP-06 | Component registry (schemas, grammars, mappings, connections, enrichments, findings-spec, policies…) with `.history/` | ✅ | ✅ | ✅ | |
| CP-07 | Spaces: per-tenant isolation of config, stores, scheduler, event log | ✅ | ✅ | ✅ | isolation is a *layout* in P/S; **enforced** by seeded policies only in E (SEC-06) |
| CP-08 | Studio: Datasets, Queries, Widgets, Dashboards, Viz Library, curated templates | ✅ | ✅ | ✅ | `trend-monitor` template 2026-09-02 |
| CP-09 | Geo map + link analysis views | — | ✅ | ✅ | ✅ **GATED 2026-09-07 (EDG-01 cell 3b) — the P cell is now true of the build.** `GeoRoutes` + `InvRoutes` moved out of the core into `inspecto-geo-link`, a Standard+/Enterprise-only module discovered through the **public `RouteModule` SPI** (cell 3a) and registered AFTER the built-in list. On Personal the five paths answer **503 "not installed"** from the core's `AbsentGeoLinkRoutes` stub — never 404 — and `/bootstrap` reports `features.geoLink=false`, derived from what actually registered (`ApiContext.hasRoute`), on which the SPA **hides** the two nav entries and the two widget offers rather than offering a page that errors. Pinned by `NoGeoLinkShipsInThePersonalBuildTest` over real HTTP in the DEFAULT build. DuckDB `spatial` is still deliberately not loaded (SqlSandbox lockdown). |
| CP-10 | Reconciliation (recon boards, break sets) | ✅ | ✅ | ✅ | explicit non-goals: N>3, non-additive aggs, fuzzy keys |
| CP-11 | Operational objects: Alerts → Incidents → Cases → Tasks, notes/links/tags, findings, RCA, postmortems | — | ✅ | ✅ | ✅ **GATED 2026-09-08 (EDG-01 cell 7) — the P cell is now true of the build, and it was the largest cell by far.** The whole `com.gamma.ops` domain (31 files), the `/objects`, `/notes`, `/queues` and `/tags` route families, the `caserule.evaluate` and `objects.analytics` Job Types and the `incident_purge` maintenance task are the optional `inspecto-ops` module. Personal answers **503 naming the module** on ~50 paths (`AbsentObjectRoutes`, catch-alls ordered last), reports `features.ops=false`, hides the Incidents / Case Manager / Tags nav entries, and explains itself on those three panes plus six cross-entity tag/comment menus. Baselines: Personal 23 modules / **3777** tests, Standard 31 / **4106**, Enterprise 32 / **4126**, all zero failures; `inspecto-ops` contributes 228. 🔴 **Unlike every earlier cell this one was never a leaf**: mandatory core CONSTRUCTED the feature (`ServiceStores` opened four stores, `ServiceBootstrap` loaded six config kinds, `CollectorService` built the `ObjectService` and its event bridge). Core reaches it through `com.gamma.objects.ObjectAccess` plus a host-declared `ObjectEngineProvider` handle — the provider could NOT live beside the seam in the engine, because opening the stores needs `SpaceRoot` and `OperationalDb` from the module above it. ⚠ **It also forced two other rows to change**, which is the durable lesson: `OPS-01` promised Personal an `objects` store and `SP-CTL-02` promised a gap watchdog that raises ALERT objects. Both are amended above (operator-approved 2026-09-08) rather than left to contradict the build. ⛔ **`/alerts*` is NOT in this cell.** The Alerts pane is `AlertsService` over config-authored alert RULES, which every edition serves; an `OperationalObject` of type `ALERT` is a different concept that merely shares the word. The nav spec asserts `alerts` survives the filter, because it is the adjacent id an over-wide one would take. ⛔ Event RECORDING and the audit read stay ungated — see CP-13 and §Audit.
| CP-12 | In-app notifications | ✅ | ✅ | ✅ | split 2026-09-02 — the feed stays in every edition; delivery channels are CP-15 |
| CP-13 | Metrics (`/metrics` Prometheus), events feed, audit CSV export | — | ✅ | ✅ | ✅ **FULLY GATED — cell 5 (2026-09-07) + cell 6 (2026-09-08).** Both halves are now optional modules. **(a) The `/metrics` exposition** is `inspecto-metrics`: on Personal the path answers **503 naming the module** and leaks no exposition. 🔴 This is the half that made EDG-01 P1 — `/metrics` is a `PUBLIC_PATH` and Personal ships no authenticator while binding every interface, so a Personal install served its full operational telemetry to anything that could reach the port while this cell read `—`. ⚠ Only the exposition moved: `MetricRegistry` is called by nine classes across three modules and stays core, so the counters still run — nothing reads them out over HTTP. **(b) The events feed + audit CSV export** is `inspecto-events` (cell 6): the whole `/events*` surface — feed, search, by-id, export, saved views — moved as a unit, and `AbsentEventsRoutes` 503s each path. ⚠ Moved **whole** by operator decision, because the audit CSV is not a route but a `?format=csv&type=AUDIT` branch inside `GET /events/export`; gating it alone would have meant an `if` inside a core route, the one mechanism §Assembly bans. ⚠ **This half is a visible product change**, unlike (a): Personal loses the Events **screen**. The UI hides the nav entry, falls the Ops lens home back to `pipelines` (else `/` landed on a dead pane), and renders an explained `<inspecto-alert>` on the Events, Dashboard and Incident-detail panes. ⛔ **Recording is NOT gated** — `EventStore`/`EventLog` stay core, every edition writes the audit trail, and `NoExchangeShipsInThePersonalBuildTest.eventsAreStillRecordedOnPersonal…` pins exactly that. ⛔ **The audit READ is not gated either**: cell 6 found that gating the feed would remove Personal's Audit-log screen, which §Audit (row above) promises it — so the new core `AuditLogRoutes` keeps `/audit/search` + `/audit/export` in every edition, fail-closed to the AUDIT/ACCESS_DENIED types so it cannot serve as the feed. ⛔ `/metrics/acquisition` is NOT this cell: it is Data-Acquisition telemetry on `AcquisitionRoutes`, a core capability.
| CP-14 | Assist / Intelligence agents (`/assist/*`) | — | — | — | never bundled by design; routes answer 503 in every bundle (build-test.md) |
| CP-15 | Delivery channels (mail, webhooks, delivery-status receipts) | — | 🟡 | 🟡 | ✅ **GATED 2026-09-07 (EDG-01 cell 1) — the P cell is now true of the build.** Both transports moved into `inspecto-notify-channels`, a Standard+/Enterprise-only module: `WebhookChannel` came out of `inspecto-engine` (an unconditional dependency, so it shipped everywhere) and `SmtpEmailChannel` out of `inspecto-connectors` (whose sidecar `package.ps1` copies into EVERY edition by explicit decision). Personal now registers **zero** `NotificationChannel` providers — pinned by `NoChannelShipsInThePersonalBuildTest`, which runs in the DEFAULT build and asserts the SPI is empty. In-app delivery is intrinsic to `NotificationService` and is unaffected. ⚠ Still 🟡 for S/E: soft-bounce retry and the SES/SNS adapter remain deferred (D8) — bounce suppression itself SHIPPED 2026-09-07. ⛔ The inbound `DeliveryStatusAdapter`s stay in `inspecto-connectors`: they are inert without a configured signing key and `DeliveryStatusRoutes` 404s an unconfigured adapter. |

### Security & identity

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| SEC-01 | Transport: HTTPS (keystore; FIPS provider option) | — | ✅ | ✅ | P is plain HTTP; `-Dcontrol.bind` restricts the listen address in every edition |
| SEC-02 | Authentication — OIDC resource server (`inspecto-security`, Nimbus JWKS) | — | ✅ | ✅ | P is auth-free by design |
| SEC-03 | Authorization — RBAC from token claims (`roles.toon` seed) | — | ✅ | ✅ | |
| SEC-04 | Authorization — ABAC policy engine (`inspecto-policy`, `access-policies.toon`, route + row scope) | — | — | ✅ | |
| SEC-05 | Policy authoring UX (matrix/create editor beyond hand-authored TOON) | — | — | 🔲 | seed visibility, "why denied?" explain, read-only Policies tab shipped; editor is BACKLOG |
| SEC-06 | Per-tenant space isolation enforced by seeded policies | — | — | ✅ | engages once a `space` claim is mapped |
| SEC-07 | Secrets: `${ENV}` / `${SYS}` references in every edition; `${FILE}` / `${KEYSTORE}` Standard + Enterprise; Vault / cloud KMS Enterprise-only, gated on a client policy (D4) | ✅ ENV/SYS | ✅ +FILE/KEYSTORE | ✅ +Vault/KMS when a client requires it | **SHIPPED 2026-09-06** — `SecretsProvider` SPI in the core (`inspecto-acquire`); `FileKeystoreSecretsProvider` lives in `inspecto-security` (the Standard/Enterprise profile module) and is found by ServiceLoader; a Personal bundle refuses `${FILE}`/`${KEYSTORE}` with a message naming the edition and pointing at `${ENV}`/`${SYS}` — loud in a connection test, never a silent null |
| SEC-08 | Data masking / row scoping driven by field classification | — | — | 🔲 | **decided 2026-09-02 (operator): Enterprise only.** Classification exists (SCH-03), row scope exists (SEC-04); the join is E-only build work |
| SEC-09 | Actor-attributed, tamper-evident audit log | 🟡 | ✅ | ✅ | P has no actor (auth-free) — events carry `actor=anonymous` |
| SEC-10 | Exchange / sharing grants between spaces | — | ✅ | ✅ | ✅ **GATED 2026-09-07 (EDG-01 cell 4) — the P cell is now true of the build.** The `com.gamma.exchange` domain + `ExchangeRoutes` + `ExchangeRefResolver` moved into `inspecto-exchange`, discovered through the public `RouteModule` SPI. On Personal the 11 paths answer **503 "not installed"**, `features.exchange` is false (it now requires the module to have registered, not merely `-Dspaces.root` — the old check made a Personal install advertise a Share button that 404'd), and both seams stay fail-closed: `SharedRefResolver.NONE` resolves no `shared/` ref, `SharedItemConsumers.NONE` fences no delete. ⛔ The six exchange capabilities REMAIN grantable on every edition by decision — a per-edition validator would break role-file portability, and dead vocabulary has no route behind it. Attributes remain private by default, not by guarantee (SEC-EXCHANGE-ATTRS). |
| SEC-11 | X-Actor header removal (API v1 sunset) | 🔲 | 🔲 | 🔲 | client-migration-gated |
| SEC-12 | OIDC end-session redirect (`bootstrap.auth.endSessionUrl`) | — | 🔲 | 🔲 | nobody has asked; "new capability, not a gap" |

### State, scale & operations

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| OPS-01 | Operational stores on DuckDB (status, jobs, dedup ledger, outputs, provenance) | ✅ | ✅ | ✅ | ⚠ **AMENDED 2026-09-08 (EDG-01 cell 7, operator-approved): `objects` left this list for Personal.** The object/link/note/tag-assignment stores are opened by the optional `inspecto-ops` module now (`ObjectEngineProvider`), so a Personal bundle has none of the four — the row previously promised Personal a store that CP-11 was gating away, which is the contradiction this cell had to resolve one way or the other. Standard and Enterprise are unchanged. ⚠ Each of the four is still its OWN DuckDB file (a file-based DuckDB holds a single-writer lock) and still degrades to in-memory rather than blocking startup. |
| OPS-02 | Operational stores on PostgreSQL (`-Dinspecto.db=postgres`, shared roster) | — | ✅ | ✅ | driver sidecar Standard+ |
| OPS-03 | Multi-user Postgres deployment (shared state, several operators) | — | 🔲 | 🔲 | plan exists (`postgres-multi-user-plan.md`), operator-deferred |
| OPS-04 | Distributed scheduler coordination (leader election / locks) | — | — | 🔲 | EDITIONS §Enterprise "will need later" |
| OPS-05 | Shared object store for Parquet | — | — | 🔲 | EDITIONS §Enterprise "will need later"; **P: decided 2026-09-02, not for Personal** |
| OPS-06 | Backup / restore (zip + sidecar manifest, hash-verified) | — | ✅ | ✅ | ✅ **GATED 2026-09-07 (EDG-01 cell 2) — the P cell is now true of the build.** `BackupTask` moved out of `inspecto-engine` into `inspecto-backup`, a Standard+/Enterprise-only module that contributes `backup`/`backup_verify`/`restore` through the new `MaintenanceTaskProvider` ServiceLoader seam on `MaintenanceJob`. On Personal the three are **unknown maintenance tasks, refused loudly** (`NoBackupTaskShipsInThePersonalBuildTest` pins it in the DEFAULT build) — never a silent SKIPPED, which would read as a successful no-op to a chained job. ⚠ **Consequence for the bundled `spaces/demo`:** its nightly chain `runlog_retention → db_maintenance → config_backup → backup_verify → maintenance_report` stops at `config_backup` on a Personal install with a FAILED run whose message names the cause; the chain's halt-on-failure guard does the rest. That is the edition boundary working, not a defect. |
| OPS-07 | Embedded trimmed JVM runtime in the bundle (jlink) | ✅ | ✅ | ✅ | `-NoRuntime` builds need Java 24+ on the target; the CI release uses `-NoRuntime` |
| OPS-08 | Timezones: `-Dops.timezone` + per-source `parsing.source_timezone` | ✅ | ✅ | ✅ | |

### Compliance & supply chain

| ID | Feature | P | S | E | Notes |
|---|---|---|---|---|---|
| CMP-01 | SBOM per bundle (CycloneDX + SPDX inside the zip) | ✅ | ✅ | ✅ | 2026-09-02 (COMPLY-1) |
| CMP-02 | Signed releases (CI-held key, `.sha256` + `.asc`, fail-closed `-Sign`) | ✅ | ✅ | ✅ | 2026-09-02 (COMPLY-2); workflow unexercised until the first `v*` tag |
| CMP-03 | Dependency-review guard (`tools/dependencies.lock` in CI) | ✅ | ✅ | ✅ | reactor-wide, not per bundle — a review baseline, not an SBOM |
| CMP-04 | Audit retention (`event_prune`, one-year window) | ✅ | ✅ | ✅ | 2026-09-02 (COMPLY-3); operator-scheduled |
| CMP-05 | Control matrix + auditor evidence (`compliance/`) | — | ✅ | ✅ | P has no compliance scope by definition |
| CMP-06 | FIPS mode (G9) | — | 🔲 | 🔲 | matrix gap G9, open |
| CMP-07 | RBAC R5 residual (G8) | — | ✅ | ✅ | 🔴 **Cell corrected 2026-09-07 — it was STALE.** It read `🔲 planned` citing "matrix gap G8, open", but `compliance/controls-matrix.md` records **G8 CLOSED 2026-08-28** → `evidence/access-review.md`, grounded against the shipped R5 view (the Roles tab renders role-level effective grants with the profile-deny overlay). The work was done six weeks before this board was opened; only the cell was behind. ⚠ A cell that cites another file's gap ID inherits that file's state — re-read it before trusting the cell. |
| CMP-08 | Certifications (SOC 2 Type II, ISO 27001, FedRAMP…) | — | — | 🔲 | **decided 2026-09-02 (operator): Enterprise only.** Org-paced (NFR-7); C1 scope statement is org-gated |

**No open ❓ cell** — SEC-07 decided 2026-09-06 (see its row). SEC-08 and CMP-08 were
decided Enterprise-only on 2026-09-02. Everything 🔲 already has a BACKLOG home; a 🔲 cell that gets
scheduled should cite its row here.

### Edition-gating debt (opened by the 2026-09-02 decisions)

| ID | Work | Cells | Notes |
|---|---|---|---|
| EDG-01 | Gate the "not for Personal" features out of the Personal bundle: ✅ **delivery channels (CP-15) DONE 2026-09-07 — cell 1**; ✅ **backup/restore tasks (OPS-06) DONE 2026-09-07 — cell 2**; ✅ **geo map + link analysis (CP-09) DONE 2026-09-07 — cell 3**; ✅ **exchange/sharing (SEC-10) DONE 2026-09-07 — cell 4**; ✅ **the `/metrics` exposition (CP-13) DONE 2026-09-07 — cell 5**; ✅ **the events feed + audit CSV export (CP-13) DONE 2026-09-08 — cell 6**; ✅ **operational objects (CP-11) DONE 2026-09-08 — cell 7**, the largest piece: 31 domain files (not 37 — `AbstractJdbcStore` was shared infrastructure and was relocated to `com.gamma.util` first) and 27 code dependants (the grep census said 23; the compiler found four more, incl. `WidgetTags` calling through type INFERENCE and a whole module, `inspecto-intelligence`, that was never censused). ✅ **EDG-01 IS COMPLETE — all six “not for Personal” cells are now true of the build** | the — cells those rows carry under P | ✅ **MECHANISM DECIDED 2026-09-07 (operator): ServiceLoader modules for all six** — the house mechanism, matching `inspecto-security` / `inspecto-policy` exactly, and the one §Assembly names first. ⛔ NOT `-D` capability switches: a switch leaves the code in the Personal bundle, so it is a policy boundary rather than a packaging one, and Personal ships **no authenticator at all** and binds every interface by default — a mis-set flag there re-exposes an unauthenticated surface. A module Personal never bundles cannot be re-enabled by misconfiguration. ⚠ Accept what that costs: four of the six are core code today (metrics is `MetricRegistry` in the core, objects is `ObjectService` — 37 files, 27 dependants (measured 2026-09-08), exchange and geo are `ControlApi` routes), so each is a real extraction — new module, SPI seam, tests moved — not a gating pass. Deliver cell-wise; the ServiceLoader no-op-when-absent contract is the same one the assist agent already proves. 🔴 Grounded 2026-09-07: `/metrics` is in `ControlApi.PUBLIC_PATHS`, so on Personal it is served **unauthenticated** today despite CP-13 reading `—`. **Plan + ranked census: `archived-documents/plans-archive/edg-01-edition-gating-plan.md`.** 🔴 That census established **CP-13's registry cannot be a module** — `MetricRegistry` has dependants all over core — so cell 5 gated the HTTP **exposition** instead, which is the whole of the exposure. | ⚠ All of these are CORE code today, reachable in every bundle. The house mechanism for an edition difference is a ServiceLoader module or a `-D` flag decided by the BUILD, never `if (edition == …)` (EDITIONS §Assembly). So this is a design slice first: which of the six become optional modules the Personal profile omits, which become a `-D` capability switch `serve.*` sets per edition, and what the UI shows for an absent capability (the 503-panel convention, never a toast). Until it ships, the P cells are a **stated product decision the code does not apply** — say so, do not describe them as absent. |
