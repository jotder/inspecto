---
type: Capability
title: Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Verification and references
description: Part 6 of 7 of Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan: Verification; References
status: current
tags: [acq, ing, pip, capability, acquisition, connections, collectors, connectors, ledger, ingestion, parsing, consignment, grammar, parser-plugin, quarantine, expectation, pipeline, authoring, step-catalog, execution, scheduler, jobs, plan]
timestamp: 2026-11-18T00:00:00Z
item: module.elt
audience: internal
verified: 2026-11-19 last-edit
code: platform/inspecto-etl
---

# Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan — Verification and references

Part 6 of 7 of [Acquisition, ingestion and Pipelines (ACQ, ING, PIP) — plan](plan-elt.md) · previous: [Refused and superseded](plan-elt-refused-superseded.md) · next: [Parked: parser field tiers interview kit (D13)](plan-elt-parser-field-tiers.md).

## 7. Verification

### ACQ.6 Acquisition — verification

#### ACQ.6.1 Decision-logic and ledger unit tests — `platform/inspecto-acquire/src/test/java/com/gamma/acquire/`

25 test classes (24 here + `retry/RetryPolicyTest`) plus the `testkit/` connector-factory TCK. The table
lists the core ones; not listed: `AcquisitionLedgerPruneTest`, `DayManifestTest`,
`FileSequenceGapsBucketStartGoldenTest`, `StreamLaneTest`. `SecretResolverTest` is listed with them but lives
in `spi/inspecto-auth-spi`. The acquisition core is deliberately built as pure, injectable-clock decision
logic, so almost every invariant in the spec is provable without a network or a sleep.

| Class | Proves |
|---|---|
| `StabilityGateTest` | growing-file hold, release on quiescence, the `size_checks` gate, per-source isolation, gate shared **per space** |
| `DuplicatePolicyTest` | `Decision{NEW,DUPLICATE,CHANGED}` across `Mode{PATH,METADATA,CHECKSUM,ETAG}` incl. the etag→version→metadata fallback; `OnChange` reprocess/alert flags; `configStringsMapToEnums()` pins the config-string→enum mapping and the null/unknown defaults |
| `AcquisitionLedgerTest` | the ledger contract across in-memory **and** DuckDB backends, pre-etag schema migration, and a failed replace rolling back to the prior fingerprint |
| `AcquisitionLedgerWatermarkTest` | `highWatermark` = max(`lastModified`) per source, in-memory + DB, and the safe no-op default |
| `AcquisitionLedgersTest` | ledgers isolated per space (MDC routing) |
| `ChecksumsTest` | known hash vectors, SHA-256 default + change detection, unknown-algorithm failure |
| `IntegrityCheckerTest` | post-fetch size/etag verification, skip-when-no-size, quoted-etag checksum |
| `GapDetectorTest` | timestamp-template series: hourly hole, day rollover, monthly step, invalid-date skip |
| `GapTrackerTest` | the fire-once/refire-on-reopen state machine, per-source isolation, reset |
| `FileSequenceGapsTest` | the two-token windowed variant: interior hole, trailing hours, truncated tail, zero-padding, malformed template |
| `CircuitBreakerTest` | closed→open→half-open, threshold trip, cooldown, per-source isolation |
| `retry/RetryPolicyTest` | `NONE` runs once, retry-until-success, exhaust-and-rethrow, exponential doubling + clamp, linear backoff |
| `RateLimiterTest` | unlimited short-circuit, burst-then-throttle, refill over elapsed time |
| `IntakeGovernorTest` | the T15 contrast: hot policy apply, overrun halves, floor, hysteresis band, per-pipeline override incl. zero-exempt, hard-cap mode |
| `RetrievalPlannerTest` | the plan choice — backup vs. streamable vs. temp-staging |
| `PostActionTest` | `archive_path` date-token resolution |
| `ConnectionProfileTest` | field/tunnel/options load, secret masking in `toMap`, proxy-block parse+mask, **`everyFieldSurvivesAMapRoundTrip`** (the `base_path`/`basePath` data-loss guard) |
| `ConnectionTesterTest` | reachable/unreachable probe, local-profile no-network shortcut, missing host/port failure |
| `SecretResolverTest` (`spi/inspecto-auth-spi`) | all five forms — `recognisesReferences`, `resolvesSystemPropertyScope` (incl. bare-scope fallback), `missingEnvReferenceResolvesToNull`, `literalIsPassedThrough`, **`fileAndKeystoreSchemesAreRefusedNamingTheEditionWhenNoProviderIsBundled`** (the SEC-07 gate), `aBundledProviderServesTheSchemesItSupports` |
| `ConnectionRegistryTest` | register/find/remove by id, null-registration ignored, per-space isolation |
| `LocalConnectionWorkbenchTest` | probe/explore/sample, **path jailing**, CSV vs. raw-line fallback |
| `LocalFileSystemConnectorTest` | discover/open/fetchTo/post-action + ready-marker readiness |

#### ACQ.6.2 Connector, workbench and proxy tests — `providers/inspecto-connectors/src/test/java/com/gamma/acquire/connectors/`

30 test classes — the per-scheme and workbench tests below, a `*FactoryTckTest` per factory (the
`testkit` contract of §ACQ.6.1), `DbExportConnectorFactoryValidateTest`, `CollectorProcessorRemoteCycleTest` and
the export/egress tests (`ObjectStoreEgressTest`, `ObjectStoreExportJobTest`, `PinnedObjectStoreHttpTest`,
`SafetyPolicyEgressTest`) — plus 4 helpers (the 2 relay fixtures, `ObjectStoreEgressFixture`, `OutputRows`).
⚠ The `com/gamma/connect/notify/*` tests (six classes, two helpers) also live in this module but are a
**notification** subject, not ACQ. The Kafka tests (`KafkaConnectorTest`, `KafkaConnectionWorkbenchTest`,
`KafkaConnectorFactoryTckTest`, `KafkaStreamLaneTest`, `KafkaEgressTest`, `KafkaRealBrokerTest`) moved with the
connector to `providers/inspecto-connectors-kafka/src/test/java/com/gamma/acquire/kafka/`.

- Per-scheme: `SftpConnectorTest` (host-key pinning, discover/fetch/resume, dedup-on-rerun, parallel fetch,
  workbench, SOCKS5 + HTTP-CONNECT tunnelling) · `FtpConnectorTest` (+ proxy, unknown-proxy fail-closed) ·
  `FtpsConnectorTest` (TLS mode parsing, asserts `scheme()=="ftps"`) · `FtpTunnelConnectorTest` (bastion +
  passive-port-range parsing) · `S3ConnectorTest` · `AzureBlobConnectorTest` · `GcsConnectorTest` (each:
  discover w/ pagination + etag, depth bound, fetch, move+delete post-actions) · `KafkaConnectorTest`
  (Kafka module; offset-range discover, **ledger-watermark resume**, NDJSON envelope drain, raw-payload mode,
  non-RETAIN post-action rejection, the **in-flight fence** and its self-release, the lag gauge) ·
  `DbExportWatermarkCycleTest` (real poll cycles: frontier advances, **restart between land and commit**,
  fence across a restart) · `DbExportConnectorTest` (query→CSV, `:watermark` placeholder rewrite,
  incremental export **commits then advances**).
- Auth signing, which is what "SDK-free" rests on: `AwsSigV4Test` (vanilla vector, S3 sha256, RFC3986
  encoding) · `AzureSharedKeyTest` (string-to-sign, `x-ms-` headers, canonicalized resource).
- Workbenches: `S3ConnectionWorkbenchTest` · `AzureBlobConnectionWorkbenchTest` ·
  `GcsConnectionWorkbenchTest` (each an in-process JDK `HttpServer` stub per store) ·
  `DbConnectionWorkbenchTest` (schema/table/column explore, bounded sample + truncation flag) ·
  `KafkaConnectionWorkbenchTest` (Kafka module; topic→partition explore; **sample without touching the ledger
  watermark**).
- Proxy: `DbConnectionsProxyTest` — the JDBC half dialled through both relays at a bare listener, asserting
  the relay was asked for the database; unroutable shapes refused.
- Fixtures (not test classes): `MiniSocks5Relay.java`, `MiniHttpConnectRelay.java` — both multi-connection,
  because FTP opens a *second* passive data connection through the same factory; `MiniHttpConnectRelay`
  additionally records the auth header. 🔴 These relays are the only thing that caught the
  `createSocket()`-vs-`connect()` bypass of the HTTP proxy: the happy path still returned the right file.
- Test infrastructure declared in `providers/inspecto-connectors/pom.xml`: Apache MINA `sshd-core` + `sshd-sftp` and
  Apache `ftpserver-core` (all `<scope>test</scope>`) give **real in-process SSH/SFTP and FTP servers**;
  in the Kafka module, kafka-clients' in-jar `MockConsumer` means the suite needs no broker (only
  `KafkaRealBrokerTest` wants one, and skips without it); the ACQ test resource committed here is
  `providers/inspecto-connectors/src/test/resources/ftps-test-keystore.jks`.
- Slice frontier: `RemoteSliceFrontierRekeyTest` (engine), `RemoteSliceFrontierCommitTest` (inspecto: restart
  and fence against the scheduler's split timers), `DbExportWatermarkCycleTest` (connectors).

#### ACQ.6.3 Unpack — `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/`

`UnpackStage`, `UnpackLimits`, `UnpackLedger`, `UnpackOrigins` tests; `CompressionTest.inlineVocabularyIsOwnedByPlugins`
(the one-way drift guard); the zip-slip jail pinned with a **re-packed** archive; the `EMPTY` vs `UNREADABLE`
`entriesFound()` split; `LogicalNamesTest` (the one permitted `ConfigSpecs` mirror of the default
`data_extensions`).

#### ACQ.6.4 Control-plane real-HTTP route tests — `inspecto/src/test/java/com/gamma/control/`

Domain-primary (four):

| Class | Routes / gates |
|---|---|
| `ControlApiCollectorsAndConnectionsTest` | `GET /collectors`; `POST/GET/PUT/DELETE /connections[/{id}]` — create, duplicate id → **409**, delete-while-in-use → **409**, `POST /connections` → **503** when the write root is unavailable |
| `ControlApiCollectorNotifyTest` | `GET /collectors`; `POST /collectors/{id}/notify` → **202** + two 404-unknown cases; and `AuditTrail.classify("POST","/collectors/feed-a/notify")` — **this is the test that pins `collector.notified`** |
| `ControlApiConnectionsTest` | `GET /connections`, `GET/PUT/DELETE /connections/{id}` 404-unknown, `POST /connections/{id}/test` |
| `ControlApiConnectionProbeTest` | `POST /connections/{id}/probe`, `GET …/explore`, `GET …/sample` — **400/422/501/404** across local vs. unsupported-remote schemes |

Incidental (the route string is an example for another concern, but they do exercise the path):
`ControlApiVersionedSurfaceTest` (`/collectors` under `/api/v1`, `/api`, `/api/v2`) ·
`ApiContextV1DerivationTest` (v1 prefix derivation over `/collectors` variants) · `AuditTrailTest`
(`PUT /connections/{id}`, `POST /connections/{id}/test` classification) · `ControlApiBundleNewKindsTest`
(`POST /connections`, `GET /connections/{id}` as bundle-import kinds).

#### ACQ.6.5 SPI contracts (`META-INF/services`)

| File | Providers |
|---|---|
| `providers/inspecto-connectors/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | the **seven**: `SftpConnectorFactory`, `FtpConnectorFactory`, `FtpsConnectorFactory`, `DbExportConnectorFactory`, `S3ConnectorFactory`, `AzureBlobConnectorFactory`, `GcsConnectorFactory` (`KafkaConnectorFactory` is in `providers/inspecto-connectors-kafka/src/main/resources/META-INF/services/...`) |
| `platform/inspecto-engine/src/main/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.inspector.DatasetCollectorConnectorFactory` — the `dataset` scheme, from a different module |
| `providers/inspecto-secrets/src/main/resources/META-INF/services/com.gamma.auth.secrets.SecretsProvider` | `com.gamma.secrets.FileKeystoreSecretsProvider` — the SEC-07 edition seam |
| `inspecto/src/test/resources/META-INF/services/com.gamma.acquire.CollectorConnectorFactory` | `com.gamma.service.FakeRemoteConnectorFactory`, `com.gamma.service.FakeOffsetTailConnectorFactory` (test-only remote schemes) |

`platform/inspecto-engine/src/test/java/com/gamma/inspector/DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable()`
loads the SPI through `ServiceLoader`; so does the factory TCK (`testkit/CollectorConnectorFactoryContract`,
`schemeIsServedByExactlyOneFactory`), which every `*FactoryTckTest` runs.

#### ACQ.6.6 Runnable examples

✅ **One committed example now exercises a non-local connector, and it was DRIVEN live 2026-09-15**
(`COLLECTOR-DATASET-UNPROVEN-1`, closed): `spaces/demo/config/orders/orders_by_region_feed_pipeline.toon` —
`collector: {connector: dataset, dataset: datasets/orders_by_region}` plus the `trigger: {type: event, on: dataset,
from: …}` half, `parsing.frontend: parquet`. On a multi-Space server (`-Dspaces.root=spaces`, Standard modules on
the classpath) `POST /spaces/demo/jobs/orders_by_region_materialize/trigger` → 202 → SUCCESS, 4 rows; the
`DatasetWriteSignal` fired the feed **in the same second** (batch `SUCCESS`, 4 rows, 1 partition, 0 rejected,
180 ms) and the Parquet landed under `spaces/demo/data/orders_by_region_feed/database/`. 🔴 Two things the run
found that no test had: (a) the committed materialize job was **broken** — `measures: "gross,sum"` is not the
measure grammar (`sum(gross)`), so the demo's own materialize had never run; (b) the demo space **does not boot on
the core jar at all** — `ops_analytics_sample_job.toon` declares `type: objects.analytics` (an `inspecto-ops` job
type) and `SpaceManager` SKIPPED the whole space with a WARN (`DEMO-SPACE-PERSONAL-UNBOOTABLE-1`, fixed
2026-09-15: a job of an unregistered type is now skipped at boot, not the Space — see
[jobs](../processor/spec-jobs.md)). ⚠ Before
the Dataset exists the feed's first poll fails loudly (`unknown dataset 'orders_by_region'`) — fail-fast, by
design; the registry component `orders_by_region.toon` is committed so a fresh checkout resolves it at boot.
⚠ Otherwise every runnable committed `*_pipeline.toon` uses `connector: local` (153 occurrences). The two
remote ones are the **`_reference/`** templates, which need external infrastructure and are not runnable
offline: `inspecto/examples/_reference/sftp-collector/orders_pipeline.toon` (`connector: sftp` with
`fetch` + `rate_limit`, `retry`, `stability`, `post_action`) and
`inspecto/examples/_reference/db-export/orders_pipeline.toon` (`connector: db` with `fetch`, `retry`). What is
runnable is the acquisition *semantics* layer over the local connector:

| Example | Exercises |
|---|---|
| `inspecto/examples/05-acquisition/gap-detection/pipeline.toon` | `collector.gap_detection` |
| `inspecto/examples/05-acquisition/dedup-rerun/pipeline.toon` | ⚠ **not** an acquisition key — its dedup is `processing.duplicate_check` |
| `inspecto/examples/05-acquisition/intake-throttle/pipeline.toon` | the per-cycle intake cap, `processing.intake { max_files_per_cycle }` |
| `inspecto/examples/06-serve/sequence-gap/feed_pipeline.toon` | `collector.gap_detection` under the serve runner |
| `inspecto/examples/06-serve/checksum-change/orders_pipeline.toon` | `collector.duplicate { mode: checksum, algorithm: SHA256, on_change: alert }` |
| `inspecto/examples/06-serve/incremental-watermark/feed_pipeline.toon` | `collector.duplicate { mode: metadata }` + `collector.incremental { watermark: last_modified }` |
| `inspecto/examples/07-steps/collect/pipeline.toon` | `connector`, `discovery: poll`, `include[]`, `exclude[]`, `recursive_depth`, `gap_detection` (mirrored at `spaces/default/config/collect_step/collect_step_pipeline.toon`) |
| `spaces/ucc/config/voucher/voucher_pipeline.toon` | the only runnable **`collector.stability`** (the other is in `_reference/sftp-collector`) — `window: 10s`, `size_checks: 2` — and `collector.consignment { max_files, max_bytes }` |

Runners: `inspecto/examples/run-example.ps1` / `.sh` (batch) and `serve-example.ps1` / `.sh` (serve mode —
required for gap/fingerprint/watermark/stability, which need more than one cycle; the `phase2/` two-cycle
protocol and `mtimes.txt`). Self-checking `probes.txt` assertions exist for the three `06-serve/*` examples
above and **not** for any of the three `05-acquisition/*`.

Connection profiles — six committed. Three sit beside the `_reference/` pipelines that bind them
(`_reference/sftp-collector/warehouse_sftp_connection.toon`, `_reference/db-export/orders_db_connection.toon`,
`_reference/webhook-sink/orders_hook_connection.toon`). The other three exercise the Connections API surface
rather than collection (no pipeline references any of them): `spaces/default/config/connections/local_demo_connection.toon`
(`sftp`, `${ENV:DEMO_SFTP_PASSWORD}`; ⚠ `host: 127.0.0.1 port: 8080` reads as a probe fixture aimed at the
control-plane port, not a working SFTP endpoint) · `spaces/demo/config/connections/demo_local_connection.toon`
(`local`, the only one that resolves offline) · `spaces/demo/config/connections/warehouse_sftp_connection.toon`
(`sftp`, `${ENV:WAREHOUSE_SFTP_PASSWORD}`; `spaces/demo/config/README.md` labels it "reference shape").
**No committed `.toon` anywhere contains a `tunnel:` or `proxy:` block** — those shapes live only in
`connectors-runbook.md`, `ConnectionProfileTest`'s inline TOON, and the UI's `connection-form.dialog.ts`.

#### ACQ.6.7 Guards

- `tools/check-secrets.mjs` — the ACQ-relevant guard: it exists so a credential cannot be reintroduced as a
  literal, and it explicitly blesses `${ENV:…}` as *the* sanctioned way not to hold a secret. Two modes,
  because tree-scan alone hands out a false green when a secret is committed and moved one commit later;
  `.githooks/pre-push` runs both. Wired into `ci.yml`.
- `tools/check-vocabulary.mjs` — rule `source-acquisition-entity` enforces Collector over Source. <!-- vocab-allow: names the guard rule by id -->
  ✅ The path-keyed `DOC_ALLOW` waiver for the old `integrations.md` H1 was deleted 2026-09-08 when the
  connector runbook was split out; the guard's self-retirement rule would otherwise have failed the build. The
  guard's comments still record the identical error class fixed once before — a documented `GET /sources`
  that is really `GET /collectors`.
- `tools/check-doc-links.mjs` — wired into `.githooks/pre-push` + `ci.yml`; any ACQ file move must keep the
  link baseline at zero dangling.
- `tools/check-coverage.mjs` — ⚠ the floors are **repo-wide, not per-module** (its header comment), so there is no
  `inspecto-acquire` floor to cite; do not claim one.

#### ACQ.6.8 Named coverage gaps (verified absent, not assumed)

1. 🔴 **No test asserts the registered-scheme set.** The number seven is only the line count of the
   `inspecto-connectors` services file; `FtpsConnectorTest` and
   `DatasetCollectorConnectorFactoryTest.theFactoryIsServiceLoaderDiscoverable` each assert a single scheme,
   and the factory TCK checks that a scheme has *at most* one factory, not that it is registered. Nothing
   counts or lists them together, so a dropped `META-INF/services` line would ship silently — the exact
   failure mode of the palette-fallback incident.
2. 🔴 **No test feeds a raw `ssh-rsa AAAA…` line into `host_key`.** `SftpConnectorTest` covers
   `wrongHostKeyFingerprintRejectsConnect()` (a bogus colon-hex fingerprint) and
   `strictHostKeyWithoutAnyPinRefusesConnect()`. The rejection of a raw key line follows from
   `HostKeyPolicy` handing the string to sshj's fingerprint verifier — it is code-supported by
   construction, but **not covered by a test with that input**, so state it as a design property, never as
   a tested one.
3. The three `05-acquisition/*` examples (`gap-detection`, `dedup-rerun`, `intake-throttle`) have no
   `probes.txt`, so they are demonstrable but not self-checking.
4. Features needing external infra (SFTP, a database, an https receiver, a DuckLake catalog) now ship as
   `inspecto/examples/_reference/` templates (`inspecto/examples/README.md`, *Reference templates*); they are
   reference shapes, not runnable offline, so no committed example *drives* a remote fetch (§ACQ.6.6).
5. `.Z` has no round-trip test; xz / zstd have no codec to test (`BACKLOG.md` §3).

### 7.1 The lane and the commit — `inspecto-engine` · `inspecto-etl`

`ConsignmentIngestorPluginTest` (the end-to-end wrap pin: a toy `StreamingFileIngester` into a partitioned store
and — `unkeyedSegmentWritesAFlatStoreWithLineage` — a flat one, asserting rows, layout and lineage),
`FinalizeSourceConcurrencyTest` (8 Consignments finalising through the shared DB stores; the same-file marker
race with exactly one loser), `ConsignmentAuditWriterTest` (contiguous ledger blocks under concurrency),
`ConsignmentStatusAccessTest`, `PartitionWriterTest` · `PartitionWriterFormatTest`, `PipelineLiftTest`,
`PipelineJobRunnerTest` (the store-layout contract), the `ConsignmentPlanner` and `MarkerManager` tests.
Content refusal: `RefusalQuarantineTest` (incl. `aMappingExpressionCannotReadTheRestrictedStore` and a byte-wise
scan of the whole Space), `FailureTextTest` (six real DuckDB error shapes), the payment pack's
`PaymentFraudTemplateGoldenTest`. Daily feed: `LaDailyIngestAcceptanceTest#aCorrectedSameNameDayReplacesItsPartitionAndAnUnchangedOneIsSkipped`,
`LaDailyFeedTemplateRulesTest`, `IndexAppendTest.aThirtyDayBackfillAnswersEveryReadLikeTheDayByDayRunAcrossThreeCompactions`,
`ControlApiSpaceTemplateSeedGateTest.everyShippedTemplateApplies`.

### 7.2 Parsing lanes and plugins

`DelimitedGrammarTest` · `FixedWidthTest` (`inspecto-etl`; ⚠ both **skip** when their fixture pipeline is
absent — two of the reactor's five standing skips), `ParsersTest` (registry, duplicate-id failure, the
display-flag stub), `XmlRecordIngesterTest` (`previewLabelsAreTheSelectorsThatResolve` — the one-walker
rule), `Asn1ParserPlugin` tests, `RealGrammarsTest` · `ParityCheckTest` (`asn-parser`; **skip** unless
`-Dasn.corpus.tests=true` and the customer corpus is on disk — DATA-GOV-1; the other three standing skips),
`ExcelExtension` tests, `SourceZones` tests, `PipelineConfigParser` tests (`FRONTENDS`, alias precedence,
`records_path`, refusals at load), `ConfigLoaderTest` (`partitions[0]:` round-trips to an empty list).

### 7.3 Casting, quarantine, expectations — `inspecto` · `inspecto-etl`

`DataTransformer` cast-failure tests (`-1` = blank), `QuarantineManager` tests, `ControlApiExpectationTest`
(`inspecto-ops` — the routes, the deduped Incident, `EXPECTATION_FAILED`), `ExpectationEvaluator` tests,
`expectations.component.spec.ts` and `expectation-form.dialog.spec.ts`.

### 7.4 Authoring — what is enforced today

* **Three round-trip tests**, each guarding a different failure: verbatim including unmodelled keys; the
  real codec through a file; and the reachable path from the editor's write route to an engine field
  (`NodeConfigNameContractTest`).
* **A repo-wide Recipe parity gate** — compile of the projection of every committed pipeline fixture must
  equal the original, and it asserts the fixture list is non-empty so an empty sweep cannot pass silently.
* **Committed server-table contracts** for node attributes (`inspecto/contracts/node-attributes.contract.json`,
  `NodeAttributesContractTest` + `node-attributes.spec.ts`) and the processor catalogue
  (`processor-catalog.contract.json`), which the client also reads and which regenerate only deliberately; the
  `step-types` pair was retired 2026-09-24. Also `measure-grammar.contract.json` (`MeasureGrammarContractTest`)
  and `contracts/sql-ast.contract.json` (`SqlAstContractTest`, `sql-ast.spec.ts`).
* **A constant-drift contract** that re-parses the executor's own source text — the strongest guard here.
* **A binding-home contract**: every category offering a picker must have a home on the save path.
* `SqlSavePathGuardTest` (9 tests; mutation-proven: removing the two call sites failed exactly the two
  integration tests), `MapNodeKeyContractTest`, `PipelineEditableTest` (single-slot discard behaviour pinned per
  slot), `RouteArmingTest`, `ControlApiRouteArmingTest`, `RouteIngestEndToEndTest`, `ControlApiStepColumnMetadataTest`,
  `MetadataGraphServiceTest`, `EvidenceMaskerTest`, `PostgresPublishJobTest`, `PipelineDocumentXlsxTest`
  (staged mode, and on Linux in CI since 2026-09-27), `XlsxSheetMergerTest`.
* **Editor specs** pinning the save payload, refusals landing in the dock, the 422-only Apply block,
  authoring affordances hidden in the read-only persona view, and the palette's three host error arms
  (`pipeline-editor.component.spec.ts`, five tests); `pipelines.handler.spec.ts` pins the palette to the enum.

### 7.5 UI specs on the parse surface — vitest (~30)

`pipeline-parse-definition.component.spec.ts`, `grammar-editor.component.spec.ts`, `grammar-editor.dialog.spec.ts`,
`grammar-csv.spec.ts`, `parsing-attributes.spec.ts`, `parsing-sniff.spec.ts`, `parser-tree.component.spec.ts`,
`pipeline-collection-definition.component.spec.ts` (consignment + unpack attributes), `run-detail.component.spec.ts`,
`batch-detail.dialog.spec.ts`, plus the pipeline-editor suite.

### 7.6 Committed artifacts

`inspecto/examples/02-parsing/` — nine format-lane examples (`fixedwidth`, `json-frontend`, `text-regex-frontend`,
`xlsx-frontend`, `xml-plugin-frontend`, `pipe-delimited`, `no-header`, `compressed-gzip`, `asn1-frontend`), each a
`pipeline.toon` (+ `grammar.toon` for fixed-width); `07-steps`; 144 `.toon` files under `inspecto/examples/` in all (counted 2026-11-15).
⚠ **No committed `parquet` example** and no committed `expectation` component.

### 7.7 Guards

`CompressionTest`'s one-way vocabulary guard; `ParsersTest`'s duplicate-id and display-flag pins; the
`node-attributes` contract pair; `check-vocabulary` keeps *Batch* (the entity), *member* (for an Entry) and
*parser config* out of the docs; `SchemaFieldTypes` at load; `tools/check-doc-counts.mjs` derives every marked
count; **`tools/check-doc-citations.mjs`** (committed 2026-09-09, wired into `ci.yml` and `.githooks/pre-push`)
checks backticked symbols and paths against `git ls-files` and the module tree.

### 7.8 Named coverage gaps (verified absent, not assumed)

| Gap | Evidence |
|---|---|
| **Five reactor tests skip by default** — `RealGrammarsTest` ×2, `ParityCheckTest` (corpus-gated), `DelimitedGrammarTest`, `FixedWidthTest` (fixture-gated) | `assumeTrue` in each |
| **No `parquet` frontend example or board row** | §7.6 |
| **The `tier: 'required'` fields ship `required: false`** — the D13 observation is un-run | `BACKLOG.md` §2 |
| **The flat lane's deletion (Row 15) has no test because it is not done** | engine |
| **The Incident hop after a commit has no harness** | [§ACQ.4](plan-elt-not-built.md#acq4-acquisition--not-built) |

### 7.9 Falsify, don't read — five authoring probes

1. **Save a pipeline whose transform SQL reads a file from disk.** A refusal at save (`SQL_STEP_REFUSED`) is
   the 2026-09-09 fix holding; a clean save means it regressed.
2. **Count the type enum's constants** before quoting any node-type number.
3. **Diff the server's authored-key set (`MAP_AUTHORED`) against `RowShaper.MAP_NODE_CONFIG_KEYS`** — there is no
   client mirror any more; a key in one and not the other is the drift returning.
4. **Break the taxonomy route and open the palette.** A silently plainer palette with no message is the
   §5.4 item 6 decision still open.
5. **Add a type to the enum and nothing else, then save a graph using it.** The refusal you get names
   which of the five registration points you missed — and no build or test failed on the way.

### EXE.8 Pipeline execution — verification

**What is actually enforced today**

* **The event-trigger filters** — `PipelineSchedulerEventTriggerTest` (2026-09-09, closing the last of
  `SPEC-NOPROOF-1`): a **non-SUCCESS** commit triggers nothing, a trigger naming itself does not
  re-fire on its own commit (the self-loop guard, whose absence turns one commit into an unbounded run
  loop), a paused pipeline stays paused for EVENT triggers, `from` tolerates case and a `flows/` prefix,
  and a `on: commit` pipeline ignores a Dataset write. Mutation-proven. ➕ **2026-09-15**: the `on: dataset` side now has the same three-way cover — the producing pipeline is suppressed, another pipeline’s write still fires it, and an **unowned** write (a cron/manual job) still fires it. ⛔ The third is not padding: reading “no owning pipeline” as “suppress” would disable materialize-then-trigger entirely, silently. 🔴 **The board row that asked for
  this said the scheduler "has no test class at all" — literally true, materially misleading**:
  `CollectorServiceTriggerTest` already proved its loop semantics end-to-end (DEFAULT_POLL, interval,
  cron, manual, the event trigger, the `on: dataset` fence) under a collaborator's name. ⚠ Grep the
  behaviour, not the identifier. ⛔ `dueThisTick`/`cronDue` stay proven from the outside: they are private
  and `runOne` calls real ELT, so reaching them directly would need reflection or a production change.

* **A lane-parity test that diffs flat against graph writes** — parity is proven, not asserted. The
  strongest control in this area.
* **A stage-two lift test** covering the happy path and the route refusal.
* **A bounded-retry test**, and an orphan-audit test.
* **Ingest lock tests** pinning that the per-pipeline guard is shared as one instance and that the hand-off
  is off-thread.
* **Scheduler-settings tests** pinning configuration-over-flag precedence.
* **A purge test asserting the ledger outlives the record** — including its own purge entry.
* **An operations-zone test written to be falsified on purpose.**

**Falsify, don't read — six probes**

1. **Grep for the class the backlog names as Row 15's blocker.** Zero hits falsifies the row; then grep the
   real runner's `run(` for its production caller.
2. **Open the maintenance job form on a Personal build.** Four offered tasks will be refused and seven
   working ones are missing.
3. **Restart the server, then replay a completed run.** A 404 is the §EXE.2.3 bound, not a bug in the route.
4. **Start with nothing installed and read the effective memory limit.** Nothing is the answer six docs
   deny.
5. **Read the concurrency javadoc against the constant twelve lines below it.** They disagree.
6. **Author a maintenance job by copying the post-sync page's job type id.** The underscore makes it an
   unknown type.

**A capability-pointer check over this file** — it is **`tools/check-doc-citations.mjs`, committed 2026-09-09**
and wired into `ci.yml` and `.githooks/pre-push`. It checks this file's backticked symbols
against `git ls-files` and the module tree — and in this area, that check would have caught item 7 of §EXE.5.3.

## 8. References

### ACQ.7 Acquisition — references

| Mechanism | Owning file |
|---|---|
| The original requirement, the engine as built, the Unpack stage | [spec-acquisition.md](spec-acquisition.md) · [spec-acquisition-unpack.md](spec-acquisition-unpack.md) |
| Poll cycle and phases A–F summary, the Collectors module page, multi-location ingest | [architecture-elt.md](architecture-elt.md) |
| Screens and the Collector configuration surface | [user-guide-elt.md](user-guide-elt.md) |
| Connector SPI, remote schemes, profiles, secrets, workbench, proxy chain | [connectors/spec-connectors.md](../connectors/spec-connectors.md) |
| Operator runbook: profile YAML, host-key table, FTPS, bastion, db-export | [connectors/admin-guide-connectors-runbook.md](../connectors/admin-guide-connectors-runbook.md) |
| Module boundary + dependency confinement | [connectors/architecture-connectors.md](../connectors/architecture-connectors.md) |
| What happens after the inbox | [§1.1](plan-elt-scope-requirements.md#11-ingestion-ing) ingestion, [§1.2](plan-elt-scope-requirements.md#12-pipeline-authoring-pip-1) authoring, [§EXE.1](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7) execution (this file) |

### Ingestion, authoring and execution — references

⚠ **Cite the symbol, not the line** — executor line citations in this area were wrong every time.

| Concern | Where it lives | Note |
|---|---|---|
| The type vocabulary | `BuiltinNodeType` (**49**<!--count:node-types--> constants) | ⚠ Counted 20/28/20 in three docs |
| Published config shapes | `NodeAttributes` + its committed contract | **34**<!--count:node-types-with-attributes--> of the built-ins, by design |
| The authoring round trip | `PipelineEditable.toMap` / `.lower` | [Pipeline spec — model](spec-pipeline-model.md) |
| The read-only projection | `PipelineLift.lift` | ⚠ *internal only, never a file rewrite*; do not cite it as the round trip. D9: its `GET …/graph` response carries `links.roundTrip` and `readOnlyProjection` (`WB-18`) |
| The at-rest projection | `PipelineLift.stageTwo` | engine's |
| Executor dispatch | `RowShaper.shape` in `com.gamma.pipeline.exec` | ⚠ older docs cite the wrong package **and** wrong lines |
| The executable map-node keys | `RowShaper.MAP_NODE_CONFIG_KEYS` | 🔴 contradicted by the config-key reference (§2.4) |
| The authored map keys | `PipelineEditable.MAP_AUTHORED` (3 keys) | no client mirror since 2026-09-24; `MapNodeKeyContractTest` holds it to `RowShaper` |
| Live refusal codes | `PipelineEditable` constants | |
| Graph validation | `PipelineValidator` | 🔴 unknown type is a WARNING (§2.2) |
| The Recipe layer | `RecipeCompiler` (11 verbs) / `RecipeConverter` | the fifth registration point; calls `refuseUnsafeSql` since 2026-09-09 |
| The transform guard | `SqlGuard` | on the save path since 2026-09-09 (`PipelineEditable.refuseUnsafeSql`) |
| The legacy read bridge | `DataTransformer.recordFields` | |
| The function catalogue | `sql-functions.ts` (23 in 7 categories) | |
| The processor catalogue | `processor-catalog.contract.json` (122<!--count:processors--> = 34/18/67) | every statement of this number is derived by `tools/check-doc-counts.mjs` |
| The editor, the panes, the palette, unmodelled config, the transform pane | [user-guide-elt.md](user-guide-elt.md) | |
| Identity & lifecycle | `PipelineSettingsRoutes`, `PipelineRenameRoutes` | [Pipeline identity](../processor/spec-pipeline-identity.md) |
| The backend graph model | [architecture](architecture-elt.md) · [Pipeline spec — model](spec-pipeline-model.md) | |
| The Step catalog | [step-catalog](spec-step-catalog.md) | counts agree with code |
| The config contract | [pipeline-config-keys](../config/spec-pipeline-config-keys.md) | 🔴 the `fields[]` contradiction |
| Pipeline execution (PIP-2..7) | [§EXE.1](plan-elt-scope-requirements.md#exe1-pipeline-execution-pip-2--pip-7) – [§EXE.8](#exe8-pipeline-execution--verification) (this file) | formerly the engine hub's plan |
| The archived active plans | `plans-archive/pipeline-spec.md`, `plans-archive/pipeline-waves-drain-plan.md` (archived 2026-09-10) | D1–D10 are §4.3 here and the [Pipeline spec — model](spec-pipeline-model.md); the plans are provenance |
