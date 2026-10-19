---
type: TestsInventory
title: ETL model and step registry — tests inventory
item: module.etl
description: Generated list of the test classes for module.etl, with how to run them.
tags: [tests, module]
---

# ETL model and step registry — tests inventory (`module.etl`)

> **Generated** by `tools/render-tests-inventory.mjs` from the git-tracked test sources under `platform/inspecto-etl/src/test/` — never edit this table; add or
> rename tests and re-run the tool. Hub: [index](index.md) · Product Map Item
> [`module.etl`](../../../../../product-map/module/etl.toon).

104 test class(es): 104 unit, 0 real-HTTP control-plane, 0 IT.
Kind is inferred: `*IT` → IT; a `ControlApi*` class, or one using both `HttpClient` and `ControlApi` → real-HTTP
control-plane test; else unit.

Run the whole module, or chosen classes (commas between classes, never `+`):

```bash
mvn -o test -pl platform/inspecto-etl
mvn -o test -pl platform/inspecto-etl -Dtest=AcceptedConfigKeysDocContractTest,ChildSpecTest
```

| test class | package | kind | file | summary |
|---|---|---|---|---|
| `AcceptedConfigKeysDocContractTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/AcceptedConfigKeysDocContractTest.java` | The accepted-names doc is <b>generated from the same map the checker enforces</b>, so it cannot |
| `ChildSpecTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ChildSpecTest.java` | Parsing D9 slice 2: the {@code children} block's closed shape and every {@code ASN1-CHILD-*} code. |
| `CollectorResilienceSpecParityTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/CollectorResilienceSpecParityTest.java` | The Collector's retry / circuit-breaker / rate-limit keys are declared in {@code ConfigSpecs.pipeline()} |
| `CommitLogTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/CommitLogTest.java` | Tests for {@link CommitLog} — the durable append-only batch ledger (D2). |
| `CompressionTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/CompressionTest.java` | Phase-E: transparent {@code .gz}/{@code .bz2}/{@code .zip} decompression on the streaming read path. |
| `ConcurrencyControlsTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConcurrencyControlsTest.java` | Tests for the controllable-concurrency knobs added in v1.5.0: |
| `ConfigFromMapTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConfigFromMapTest.java` | Verifies the P2 parse/prepare split: {@code fromMap} is a pure parse (no directory creation), the |
| `ConfigValidatorTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConfigValidatorTest.java` | Unit tests for {@link ConfigValidator}. |
| `ConsignmentAuditWriterTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConsignmentAuditWriterTest.java` |  |
| `ConsignmentEventBusTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConsignmentEventBusTest.java` |  |
| `ConsignmentIdTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConsignmentIdTest.java` | The Consignment identity — {@code CONSIGNMENT-ID-DETERMINISTIC-1}. |
| `ConsignmentPlannerTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConsignmentPlannerTest.java` |  |
| `ConsignmentRecordsTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ConsignmentRecordsTest.java` |  |
| `CsvIngesterSkipLogicTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/CsvIngesterSkipLogicTest.java` | Characterization tests for {@link CsvIngester}'s skip / junk / tail / error |
| `CsvIngesterTargetTableTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/CsvIngesterTargetTableTest.java` |  |
| `DataTransformerCastFailureTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DataTransformerCastFailureTest.java` | {@link DataTransformer#countCastFailures} — the audit hole a failed coercion used to fall through. |
| `DataTransformerCustomPartitionTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DataTransformerCustomPartitionTest.java` |  |
| `DataTransformerMissingRawFieldsTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DataTransformerMissingRawFieldsTest.java` | A schema whose {@code raw} block has no {@code fields[]} — its structure normally comes from a sibling |
| `DataTransformerRowPredicateTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DataTransformerRowPredicateTest.java` | {@code processing.csv_settings.where} — the <b>post-parse</b> row predicate applied by |
| `DataTransformerTransformTypesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DataTransformerTransformTypesTest.java` | Covers {@link DataTransformer}'s less-exercised mapping transform types |
| `DelimitedGrammarTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DelimitedGrammarTest.java` | Tests for the 4.1 delimited-grammar work (docs/delimited-grammar-design.md): |
| `DeriveSpecTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DeriveSpecTest.java` | {@link DeriveSpec}: the closed-vocabulary derive compiler and evaluator, over hand-built decoded maps. |
| `DuckDbCsvIngesterTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckDbCsvIngesterTest.java` | Tests for {@link DuckDbCsvIngester} — the native vectorized CSV path. |
| `DuckDbExtensionTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckDbExtensionTest.java` | {@code AIRGAP-EXTENSIONS-1}: the extension loader must reach {@code INSTALL} — the only step that |
| `DuckLakeEgressGateTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeEgressGateTest.java` | S5 of policy-narrowing-design: the DuckLake catalog host and a remote data path pass the EgressGate. |
| `DuckLakeRegistrarIdempotentTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeRegistrarIdempotentTest.java` | DUCKLAKE-REGISTER-NOT-IDEMPOTENT-1. {@code registerOnce} is plain SQL on a connection where the catalog is |
| `DuckLakeRegistrarPerSinkTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeRegistrarPerSinkTest.java` | A {@code sinks[]} entry's own {@code ducklake} block is HONOURED at registration (operator decision |
| `DuckLakeRegistrarRegisterWiringTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeRegistrarRegisterWiringTest.java` | Pins the wiring the {@code registerOnce}-level tests cannot: {@link DuckLakeRegistrar#register} (per-sink |
| `DuckLakeRegistrarTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeRegistrarTest.java` | {@link DuckLakeRegistrar} had no test at all — one of the six Musts filed as {@code SPEC-NOPROOF-1}. |
| `DuckLakeRegistrationSiteContractTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/DuckLakeRegistrationSiteContractTest.java` | {@code DUCKLAKE-COMMIT-COUNT-1} — the catalog registration happens ONLY in the places listed here, so a |
| `EditionFeaturesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/EditionFeaturesTest.java` | {@link EditionFeatures} (`PROCESSOR-RELEASE-READINESS-1` G9): with no {@link EditionFeatureProvider} on the |
| `FailureTextTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/FailureTextTest.java` | Never store values (2026-10-03): {@link FailureText} strips the value out of REAL DuckDB error texts generically — |
| `FileNamesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/FileNamesTest.java` | Never store values, file-name half: {@link FileNames#safe} keeps a name stable and unique but value-free. |
| `FixedWidthTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/FixedWidthTest.java` | Tests for the fixed-width text frontend (docs/superpowers/specs/2026-06-13-fixedwidth-frontend-design.md): |
| `HiveParquetDailyLandingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/HiveParquetDailyLandingTest.java` | LA-DAILY-INGEST-1 T1 (D-ING5): does Hive-style Parquet in DAILY partition directories land through |
| `IdentifiersTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/IdentifiersTest.java` | Sanity checks for {@link Identifiers} — every name fed to SQL DDL must pass. |
| `IngestProgressTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/IngestProgressTest.java` | {@link IngestProgress}: the live per-pipeline "which file is ingesting now" snapshot. |
| `JsonParsingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/JsonParsingTest.java` | Tests for the {@code frontend: json} parsing frontend (docs/parsing-options-reference.md §5/§6.4): |
| `LineageCollectorDynamicTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/LineageCollectorDynamicTest.java` |  |
| `LineageCollectorTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/LineageCollectorTest.java` |  |
| `ManifestStoreTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ManifestStoreTest.java` |  |
| `MapFieldsIngestTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/MapFieldsIngestTest.java` | The pipeline editor's Record Transformer slot saves {@code processing.map.fields[]}; Stage-1 ingest |
| `MappingCsvDualReadTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/MappingCsvDualReadTest.java` | ELT final amendment Phase 1 slice 1 ({@code docs/archived-documents/plans-archive/elt-final-amendment-plan.md} §8): a |
| `MappingMigrationTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/MappingMigrationTest.java` | The Record Transformer is the ONLY projection spelling: every stored schema under {@code spaces/} must |
| `MarkerManagerTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/MarkerManagerTest.java` | Tests for {@link MarkerManager} — duplicate-detection markers, the |
| `OutputFormatTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/OutputFormatTest.java` | Locks the {@link OutputFormat} enum-strategy to the historical {@code PartitionWriter} |
| `OutputPerMemberConfigTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/OutputPerMemberConfigTest.java` | {@code processing.output_per_member} (INGEST-MULTIFILE-BATCH-REPLAN-DUP-1 stage 2): parse, default, refusals. |
| `ParkedCommitTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ParkedCommitTest.java` | Phase 4 S4c — the parked Consignment's pending commit state survives the JVM that produced it. |
| `ParquetParsingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ParquetParsingTest.java` | The {@code frontend: parquet} parsing frontend (ELT Phase 3 S3c-1): DuckDB {@code read_parquet} |
| `PartitionDefHardFailTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionDefHardFailTest.java` | Regression test for the YAML-list silent-fallthrough bug. |
| `PartitionDefTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionDefTest.java` |  |
| `PartitionWriterFormatTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionWriterFormatTest.java` | Covers {@link PartitionWriter}'s format/compression branches (PARQUET output + |
| `PartitionWriterLayoutSpikeTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionWriterLayoutSpikeTest.java` | SP1 (link-analysis roadmap, M02 / R-02): does the REAL {@link PartitionWriter} give a bucketed, sorted, |
| `PartitionWriterObjectStoreContainmentTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionWriterObjectStoreContainmentTest.java` | {@link PartitionWriter} re-checks object-store containment at the write (SCALEOUT-OBJSTORE-DATABASE-DIR-1), |
| `PartitionWriterObjectStoreTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionWriterObjectStoreTest.java` | {@link PartitionWriter} against a REAL S3-compatible object store — scale-out plan §5.4 bullet 1. |
| `PartitionWriterTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PartitionWriterTest.java` |  |
| `PhaseFConfigTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PhaseFConfigTest.java` | Phase F config: additive {@code source.fetch / retry / circuit_breaker / post_action} parsing + defaults. <!-- vocab-allow: quoted Javadoc --> |
| `PipelineCommitRetryConfigTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineCommitRetryConfigTest.java` | The per-pipeline {@code processing.retry} block (X1 deferral, 2026-09-25) — the bounded COMMIT retry's cap |
| `PipelineConfigBatchTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigBatchTest.java` |  |
| `PipelineConfigCollectorMessageTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigCollectorMessageTest.java` | COLLECTOR-ERRMSG-1 (authoring-residuals R1): the parser's refusal messages must cite |
| `PipelineConfigDraftTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigDraftTest.java` | Schema-less draft parsing (v5.1.0, the stream-onboarding draft lifecycle): a minimal config |
| `PipelineConfigExcelTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigExcelTest.java` | The flat home of the {@code sink.excel} node — the top-level {@code excel:} block (operator 2026-10-06). |
| `PipelineConfigOutputStoreTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigOutputStoreTest.java` | Tests for the {@code output_store:} top-level key (multiplicity plan "A5 RE-SCOPED"): the authored |
| `PipelineConfigPluginValidationTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigPluginValidationTest.java` | Validates that {@link PipelineConfig#load} enforces the plugin-ingester config contract: |
| `PipelineConfigPoolTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigPoolTest.java` | {@code processing.pool} — the named execution pool a Pipeline chooses |
| `PipelineConfigPriorityTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigPriorityTest.java` | {@code processing.priority} — the ConcurrencyBroker share weight (scheduler-system-config plan |
| `PipelineConfigProducesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigProducesTest.java` | Tests for the {@code produces:} top-level key (v5.1.0): absent ⇒ STREAM (the prior behaviour), |
| `PipelineConfigReferenceTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigReferenceTest.java` | Tests for the Reference Phase-2 (P0) config model: the {@code reference:} block |
| `PipelineConfigSinksTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigSinksTest.java` | The plural {@code sinks:} config-format, slice 1 (model + parse). A single {@code output:} synthesises a |
| `PipelineConfigStepsTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigStepsTest.java` | Plan slice A2 — the flat config gains an ordered {@code steps:} chain, so the file can finally hold more |
| `PipelineConfigWebhookTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineConfigWebhookTest.java` | The flat home of the {@code sink.webhook} node — the top-level {@code webhook:} block. The target is a |
| `PipelineIdentityTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineIdentityTest.java` | Splitting a pipeline's <b>identity</b> from its <b>display name</b>. |
| `PipelineIntakeConfigTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineIntakeConfigTest.java` | The per-pipeline {@code processing.intake} admission-control override (T15 follow-up). Every case goes |
| `PipelineKeyCoverageContractTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/PipelineKeyCoverageContractTest.java` | <b>Two authorities read the pipeline config, and they disagree</b> — the sixth contract test |
| `ProgressSpaceIsolationTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ProgressSpaceIsolationTest.java` | {@code SPACE-UNKEYED-STATICS-1}, second half — the live progress snapshots are keyed by |
| `RecordDedupRouteConfigTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/RecordDedupRouteConfigTest.java` | ELT amendment Phase 2 (route/dedup lowering) + Phase 3 S1 (summarize lowering): the flat-config |
| `RecordTransformContractTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/RecordTransformContractTest.java` | The Java Record Transformer catalog vs the committed TS contract |
| `RecordTransformLaneTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/RecordTransformLaneTest.java` | Phase 2 — the {@code fields[]} spelling reaching the shared {@code [{name, expr}]} seam that BOTH |
| `RouteArmingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/RouteArmingTest.java` | The arming rules as a rule set — the form the SAVE path needs. |
| `SchemaDriftTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaDriftTest.java` | {@code quality.schema.drift} (SP-DQ-06): the header a file actually carries vs the {@code raw.fields[]} |
| `SchemaFieldFormatTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaFieldFormatTest.java` | {@code raw.fields[].format} — a date-like field's own strptime pattern, which REPLACES the pipeline's |
| `SchemaFieldTypesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaFieldTypesTest.java` | {@link SchemaFieldTypes} — the honoured {@code raw.fields[].type} vocabulary and the SQL each one |
| `SchemaMappingDriftTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaMappingDriftTest.java` | {@link SchemaMappingDrift}: a mapping input naming a column the schema no longer declares is a cell-level ERROR. |
| `SchemaRefResolutionTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaRefResolutionTest.java` | How a {@code schema_file} / {@code segments} reference turns into the file actually read — unification |
| `SchemaSelectorEntriesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaSelectorEntriesTest.java` | Tests for the read-only {@link SchemaSelector#entries()} catalog accessor (P2). |
| `SchemaSelectorTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaSelectorTest.java` | Tests for {@link SchemaSelector} — the two-pass schema dispatch used by |
| `SchemaTabularRowWidthTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SchemaTabularRowWidthTest.java` | {@code TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1}: an unquoted {@code DECIMAL(18,2)} in a schema's tabular |
| `ShippedDatePartitionsAreDateTypedTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ShippedDatePartitionsAreDateTypedTest.java` | No shipped Pipeline cuts a {@code DATE_*} partition from a field its schema declares non-date |
| `ShippedPipelinesLoadFromAnyWorkingDirectoryTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/ShippedPipelinesLoadFromAnyWorkingDirectoryTest.java` | Every shipped Pipeline loads no matter which directory the process was launched from |
| `SinkDuckLakeSharedTableTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SinkDuckLakeSharedTableTest.java` | Two {@code sinks[]} destinations whose EFFECTIVE ducklake (entry → {@code output.ducklake}) is one |
| `SourceZonesTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/SourceZonesTest.java` | The source-time-zone policy: {@link SourceZones}, the compile sites it reaches, and the <!-- vocab-allow: quoted Javadoc --> |
| `StepDisableArmingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/StepDisableArmingTest.java` | Phase 4 S4 (elt-s4-park-drain-plan): {@code processing.disabled_steps} — the shape parses and |
| `StepProgressTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/StepProgressTest.java` | {@link StepProgress}: the live per-pipeline "which step is the Consignment at" snapshot (G6/S7). |
| `StructureCsvDualReadTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/StructureCsvDualReadTest.java` | STRUCTURE-CSV-1 (ELT final amendment §3.2 first table, built 2026-09-06): a sibling |
| `TestConfigsNameTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/TestConfigsNameTest.java` | TESTCONFIGS-PREFIX-SUFFIX-TRAP-1: the fixture must emit the {@code *_pipeline.toon} name scanners match. |
| `TextRegexTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/TextRegexTest.java` | Tests for the {@code frontend: text_regex} parsing frontend (docs/parsing-options-reference.md |
| `TransformCompilerTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/TransformCompilerTest.java` | Byte-exact characterization of {@link TransformCompiler}. These assertions pin the |
| `TypeFlowTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/TypeFlowTest.java` | {@link TypeFlow} — ELT amendment §3.4 item 4: per-Step output schemas derived by {@code DESCRIBE} over |
| `UnifiedParsingBlockTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/UnifiedParsingBlockTest.java` | Tests for the unified {@code parsing:} block (docs/parsing-options-reference.md §5/§8.5): |
| `XlsxParsingTest` | `com.gamma.etl` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/XlsxParsingTest.java` | The {@code frontend: xlsx} parsing frontend (multiformat-parser-lanes plan X1): DuckDB |
| `ArchiveUnpackTest` | `com.gamma.etl.unpack` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/ArchiveUnpackTest.java` | The 1→N ARCHIVE half of the unpack stage (unpack-stage plan Phase 3 + the archive half of the |
| `LogicalNamesTest` | `com.gamma.etl.unpack` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/LogicalNamesTest.java` | Extension-insensitive filename identity (unpack-stage plan §2.3) and its allow-list, now the |
| `UnpackLedgerTest` | `com.gamma.etl.unpack` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/UnpackLedgerTest.java` | The run-level unpack ledger (unpack-stage plan §2.2, unblocked by the operator's §6 Q1 sign-off): |
| `UnpackStageTest` | `com.gamma.etl.unpack` | unit | `platform/inspecto-etl/src/test/java/com/gamma/etl/unpack/UnpackStageTest.java` | The Collector-level unpack stage (unpack-stage plan Phases 1 + 1b + the stream half of 2): |
