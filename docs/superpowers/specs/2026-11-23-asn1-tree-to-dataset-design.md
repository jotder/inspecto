# ASN.1 Event Tree to Tabular Dataset Design

**Date:** 2026-11-23
**Status:** Draft for Review
**Scope:** End-to-end flow in Inspecto (`providers/asn-parser/asn-decoders`, `providers/inspecto-telecom-asn1`, `platform/inspecto-etl`, `platform/inspecto-engine`, `inspecto-ui`)

---

## 1. Goal & User Workflow

Enable an Inspecto user to go from raw ASN.1 telecom records (for example, TAP 3.12 `TransferBatch` with `batchControlInfo` header + `mobileOriginatedCall`/`mobileTerminatedCall`/`gprsCall` events, MSC `mocCall`, or GGSN `listOfServiceData`) to populated, queryable Inspecto datasets/tables in four connected stages:

1. **Grammarless Raw TLV Dump & Grammar Editing/Testing:** Inspect raw ASN.1 data without any grammar via a structural TLV dump (`RecordReader` / `TlvDump`) for tag-path discovery and analysis, or load/edit an `.asn`/`.asn1` grammar with lenient diagnostics, **alias-chain simplification** (`a := b, b := c` -> `a := c`), and unmatched-node highlighting against sample data.
2. **Event Tree Inspection, Header Capture & Leaf V-Decoder Mapping:** Inspect decoded ASN.1 records as an interactive tree (`ParseResult.Tree` backed by `NamedNode`), capture file/batch **header info** (`$header.*`, e.g., TAP `batchControlInfo`), and select **per-leaf `V` decoders** from a decode library (`DecoderRegistry` / `Decoders` / `FunctionRegistry`).
3. **Multi-Event Routing (Event Roots) & Node-Level Tabular Shaping (`map`, `group_by`, `fold`, `join`):** Define **event roots** from the grammar (e.g., `callEventDetails.mobileOriginatedCall`, `callEventDetails.gprsCall`), route each to its own Sink/Dataset (`1 event root -> 1 sink/dataset`), map leaf nodes with key **rename + prefix/postfix**, and shape `SET_OF`/`SEQUENCE_OF`/sub-node containers via **`group_by`** (`max`, `mean`, `sum`, `min`, `count` grouped by key, e.g. GGSN `ratingGroup` producing grouped sub-records), followed by either **parent `join`** (exploding grouped sub-records with parent columns) or **`fold`** (multi-level delimiter or JSON).
4. **Table Metadata, Dataset Definition & Population:** Inspect both **Sink Table Preview** (sample rows) and **Table Metadata** (column name, source path, `V`-decoder, DuckDB SQL type, partition key) per routed event sink, register the `dataset` components in `ComponentStore`, and materialize rows into DuckDB/Parquet via `StreamingPluginIngestStrategy` -> `DataTransformer.materialize` -> `PartitionWriter.write`.

---

## 2. Architecture Overview

We use a **Hybrid Two-Stage Flattening** architecture paired with a **Unified Parse-to-Dataset UI Workflow**:

- **Stage A (Pre-SQL, Java in `inspecto-telecom-asn1` / `DecodeProfile`):** High-throughput streaming decode (`ByteSource.map` -> `BerReader` -> `SchemaBinder`), **event-root routing**, and **intra-record** tree shaping (`map`, `reduce` + `group_by`, `fold`, intra-record `join`) over `NamedNode` trees.
- **Stage B (Post-ingest, DuckDB SQL in `inspecto-etl` / `DataTransformer`):** Typed sink materialization (`DuckDbRecordSink`), safe `TRY_CAST` projection (`DataTransformer.materialize`), and partitioned Parquet/CSV output (`PartitionWriter`). (External cross-table `transform.join` is intentionally excluded from this parse-to-dataset flow to keep it focused; it remains available as a downstream ETL step.)
- **UI Orchestration (`inspecto-ui`):** `PipelineParseDefinitionComponent` / `AddDataComponent` presents one unified workflow (Raw/Grammar -> Event Roots -> Leaf V-Decoders & Node Flatten -> Sink Table Preview & Table Metadata -> Create & Populate Datasets).

---

## 3. Component Design

### 3.1 Grammarless Raw TLV Dump, Grammar Editing & Live Test (`providers/inspecto-telecom-asn1`, `inspecto-ui`)

Real-world operator ASN.1 data often arrives without a matching grammar, or with grammars containing syntax/dialect quirks, missing `IMPORTS`, or tag/type mismatches against actual CDR bytes.

- **Grammarless Raw TLV Dump & Analysis (`RecordReader` / `TlvDump`):**
  - When no grammar is supplied (or when the user toggles **Raw TLV Dump** mode), `Asn1ParserPlugin.preview` uses `RecordReader` + `Tlv` directly to render the full structural TLV tree (`tagString`, `TagClass`, constructed vs primitive byte length, dotted tag path, hex + ASCII value `V`) for analysis.
  - Users can inspect raw tag paths, full/expanded `V` hex bytes, and record counts without a grammar, and optionally promote/copy discovered tag paths into a starter grammar or direct `DecodeProfile` field mappings.
- **Grammar Validation & Diagnostics (`Asn1Parser.parseLenient` + `SchemaCompiler.compileLenient`):**
  - Expose a validation/compile diagnostic endpoint (or include diagnostics in `POST /parsers/asn1/preview`) returning structured `{line, column, severity, message}` diagnostics for syntax errors, unresolved type references, and tag/dialect warnings without aborting on the first error.
- **Unmatched-Node & Mapping Feedback (`SchemaBinder` -> `ParseResult.Node`):**
  - `SchemaBinder.bind` already falls back to dotted tag-path + uppercase hex for unmatched TLVs (`type == null` on `NamedNode`).
  - Surface an explicit `matched: boolean` (and `tagPath`) flag on `ParseResult.Node` so the UI highlights **unmapped/fallback nodes** and **structural-vs-schema mismatches** directly in the event tree.
- **Inline Grammar Editing & Saving (`GrammarEditorComponent` / `GrammarEditorDialog`):**
  - Enable editing and saving the ASN.1 grammar in-place (either as an inline grammar on the pipeline/parse definition or back to a `PathJail`-validated `.asn`/`.asn1` file) and re-running **Test Grammar** against the loaded sample CDR (`sample_b64` / `sample_text`) in one click.

### 3.2 Event Tree, Alias Simplification, Header Context & Leaf `V`-Mapping (`providers/inspecto-telecom-asn1`, `asn-schema`, `platform/inspecto-engine`)

- **`Asn1ParserPlugin.preview` & `ParseResult.Node`:**
  - `NamedNode` (`com.gamma.asn.schema.NamedNode`) already retains `name`, `path` (tag path), `typeName`, `value`, `children`, and `CompiledType`.
  - Extend `ParseResult.Node` (or its metadata map) to include:
    - `fieldPath`: canonical dotted schema path (e.g., `mobileOriginatedCall.basicCallInformation.callOriginator.callingNumber`), falling back to dotted tag path in structural mode.
    - `nodeRole`: `leaf` (primitive/value node) vs `container` (`SEQUENCE`/`SET`/`CHOICE`) vs `repeated` (`SEQUENCE_OF`/`SET_OF` or sub-node collection).
    - `decoderId`: active `ValueDecoder` / transform ID (populated on `leaf` nodes only).
- **Direct Mapping / Alias Simplification (`a := b, b := c` -> `a := c`):**
  - Complex grammars like TAP 3.12 (`tap 3.12.asn`) have deep type-alias and untagged single-member wrapper chains.
  - Provide a **Simplify Aliases** mode in `SchemaCompiler` / `Asn1ParserPlugin.preview` that collapses pass-through type/wrapper chains (`a := b, b := c` -> `a := c`) when building `fieldPath` and rendering the tree, while preserving the underlying tag resolution.
- **File / Batch Header Context (`$header.*`, e.g., TAP `batchControlInfo` / `accountingInfo`):**
  - `RecordReader` / `Asn1RecordIngester` captures file-level header/trailer TLVs (such as TAP `TransferBatch.batchControlInfo`, `accountingInfo`, `networkInfo`) into a per-file `$header` context map, allowing any routed event sink to include `$header.<fieldPath>` columns on every emitted row.
- **Decode Library & Leaf-Only `V` Mapping (`DecoderRegistry` + `FunctionRegistry` + `DecodeProfile`):**
  - Expose `GET /parsers/asn1/decoders` listing all registered `DecoderRegistry` / `Decoders` and `FunctionRegistry` / `asn-plugin-vendors` functions with name, description, and target SQL type.
  - **Leaf-only constraint:** `V`-decoder selection applies strictly to **leaf/value nodes** (never container/repeated nodes). Overrides are stored in `DecodeProfile` (`field_decoders`) and applied in both `preview`/`previewRows` and `Asn1RecordIngester`.

### 3.3 Multi-Event Routing (Event Roots) & Node-Level Shaping (`map`, `group_by`, `fold`, `join`) (`platform/inspecto-etl`, `providers/inspecto-telecom-asn1`)

- **Event Roots & Multi-Event Router -> One Sink/Dataset per Event:**
  - Users define explicit **Event Roots** against the grammar (e.g., `callEventDetails.mobileOriginatedCall` -> `tap_moc_cdr`, `callEventDetails.gprsCall` -> `tap_gprs_cdr`).
  - `Asn1RecordIngester` + `PipelineConfigParser` emit all matched event types and route each **event root** to its own independent sink/dataset with its own flatten rules and `$header.*` inclusions.
- **`map` (Leaf Extraction, Rename & Prefix/Postfix):**
  - Applies to **leaf nodes** (and `$header.*` fields): extracts the decoded value with an optional target SQL `type` hint (`VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `TIMESTAMP`, `DATE`; default `VARCHAR`), explicit column **rename**, and **prefix / postfix** rules to prevent column name collisions on the flat table.
- **`SET_OF` / `SEQUENCE_OF` Container Configuration (`group_by` (optional) + per-member aggregations -> `join` or `fold`):**
  - Applies **only** to tags with sub-nodes / `SET_OF` / `SEQUENCE_OF` (never scalar leaves).
  - **Step 1 — `group_by` (optional):** Select a specific member field to group by (e.g., `ratingGroup` on GGSN `listOfServiceData[]`), or leave empty to operate across all items.
  - **Step 2 — Member Field Aggregations:** Choose an aggregation (`sum`, `min`, `max`, `mean`, `count`, `first`, or `keep`) for each selected other member field (e.g., `downlinkBytes -> sum`, `uplinkBytes -> max`).
  - **Step 3 — Emit via `join` or `fold`:**
    - **`join`:** Explode each item or grouped sub-record (`left` or `inner` mode) alongside parent columns (`$parent.*`, `$header.*`, with prefix/postfix).
    - **`fold`:** Fold those member fields (or grouped sub-records) into a single column using either **Delimited** (prompting the user for custom item/field delimiters, e.g. `|` and `:`) or **JSON** array/object serialization.
- **Tabular Preview & Table Metadata (`Asn1ParserPlugin.previewRows` + `TypeFlow`):**
  - `previewRows` evaluates routing, `$header.*`, `map`, `group_by`, `fold`, and intra-record `join` on the sample input via `CollectingSink`, returning per-event `ParseResult.Rows` and **Table Metadata** (column name, source path/rule, `V`-decoder, DuckDB SQL type, nullable, partition flag).

### 3.4 Typed Sink Alignment & Dataset Population (`platform/inspecto-etl`, `platform/inspecto-engine`)

- **Typed Sink Alignment (`DuckDbRecordSink` & `TypeFlow`):**
  - Update `DuckDbRecordSink` (`RecordSink`) so `define(...)` creates DuckDB staging columns using the target types declared by `DecodeProfile` / `mapping.fields[]` (defaulting to `VARCHAR` when untyped), matching `TypeFlow.sinkColumns(..., typedSource = true)`.
  - `DataTransformer.selectFor` wraps typed projections in `TRY_CAST` / `NULLIF` / `TRY_STRPTIME` so malformed values become `NULL` rather than failing the batch.
- **Dataset / Table Creation & Population:**
  - `DatasetRegistrationService.ensure` registers one `dataset` component per routed event root in `ComponentStore` (`<writeRoot>/registry`) with the confirmed **Table Metadata** schema, `PartitionDef`, and pipeline reference.
  - `POST /datasets/{id}/materialize` (`JobService.triggerMaterializeRun`) runs `StreamingPluginIngestStrategy` -> `DuckDbRecordSink` -> `DataTransformer.materialize` (`transformed_<KEY>`) -> `PartitionWriter.write` (Parquet/CSV), and `GET /datasets/{id}/rows` serves populated rows.

### 3.5 Interactive UI Workflow & Pipeline Step UI Alignment (`inspecto-ui`, `providers/inspecto-telecom-asn1`)

Align `parser.asn1` with built-in parsers (`delimited`, `fixedwidth`, `json`, `text_regex`, `xlsx`) inside `PipelineParseDefinitionComponent` (and `AddDataComponent` where applicable):

- **Sectioned Form & Unified Serialization:**
  - Add `section` metadata to `Asn1ParserPlugin.grammarSchema()` (*Grammar & Event Roots*, *Header & Framing*, *Limits*) so `GrammarEditorComponent` renders the same collapsible `mat-expansion-panel` layout as built-in parsers.
  - Unify `GrammarEditorComponent.value()` to serialize served/plugin blocks cleanly instead of 3-way manual assembly in `PipelineParseDefinitionComponent.parsingValue()`.
  - Allow saving edited grammars in `GrammarEditorDialog` (inline or `PathJail`-validated `.asn`/`.asn1` file).
- **Downstream Sample Thread & Segment Parity:**
  - Bridge `preview.derived` (`RowSet[]`) into `DefinitionStateService.parsePreview` so downstream Transform/SQL/Dataset steps receive live ASN.1 columns and sample rows just like `kind: 'table'` parsers.
  - Give `InspectoSegmentsEditorComponent` sample-value hints and schema drift detection from `previewTree()` / `previewRows()`, replacing the read-only `asn1.derive` table and hidden `asn1.children` workaround.

4-step guided flow inside the Parse / Add-Data workspace:
1. **Raw Dump / Grammar Edit & Test:** Drop/select a sample CDR (e.g., TAP 3.12, MSC, GGSN) and immediately inspect the **Raw TLV Dump** (no grammar required) — or load/edit an `.asn`/`.asn1` grammar in `GrammarEditorComponent`, toggle **Simplify Aliases** (`a := b, b := c` -> `a := c`), click **Validate & Test** to view line/column diagnostics and unmatched-node highlights, iterate until mapped, and save.
2. **Event Roots, Header Capture & Leaf V-Decoder Mapping:** Define **Event Roots** from the grammar (e.g., `callEventDetails.mobileOriginatedCall`, `callEventDetails.gprsCall`), pin file-level **header fields** (`$header.*`, e.g., TAP `batchControlInfo`), and inspect the interactive `ParserTreeNode` tree. Clicking a **leaf/value node** lets the user pick a decode function for `V` from the **Decode Library** (`GET /parsers/asn1/decoders`) and immediately re-preview.
3. **Flatten Builder (`map`, `group_by`, `fold`, `join`):**
   - On **leaf nodes**: `+ Map` with key **rename** and **prefix/postfix** controls.
   - On **sub-node / `SET_OF` / `SEQUENCE_OF` containers**: render a scrollable **Member Fields Table** (scaling to many tag members) where each member row has `Include`, `Group By` (optional radio), `Aggregation` (`keep`/`sum`/`min`/`max`/`mean`/`count`), and `Alias/Rename`, followed by **`Join`** with parent or **`Fold`** (prompting for custom **delimiters** or **JSON**).
   - Toggle between **Sink Table Preview** (sample flattened rows) and **Table Metadata** (column name, source path/rule, `V`-decoder, DuckDB SQL type, partition key).
4. **Create & Populate Datasets:** Confirm dataset name, column types, and partition key for each routed event sink -> invoke `DatasetRegistrationService.ensure` -> save/activate pipeline -> trigger `POST /datasets/{id}/materialize` and display job progress + `GET /datasets/{id}/rows` preview.

---

## 4. Error Handling & Guardrails

- **Decode & Framing:** `Asn1ParserPlugin.preview` uses `RecoveryPolicy.SKIP_RECORD` to surface malformed records without aborting preview; `Asn1RecordIngester` streaming ingest uses `RecoveryPolicy.STOP_FILE` to prevent silent data loss.
- **Memory & Explosion Caps:** `BerReader` enforces `MAX_DEPTH = 200` and `DEFAULT_MAX_VALUE_BYTES = 64 MiB`; `DeriveSpec` and `ChildSpec` enforce `max_list_elements`, `max_derived_columns`, and `max_child_rows_per_record`.
- **Pre-Flight Validation:** Invalid `fieldPath` references, unknown `reduce` ops, or missing reference datasets in `transform.join` fail validation during preview/config-parse with structured error codes before materialization runs.
- **Cast Safety:** `DataTransformer` uses `TRY_CAST` so a single unparseable value yields `NULL` instead of aborting ingestion.

---

## 5. Testing Strategy

- **`providers/asn-parser/asn-decoders` & `providers/inspecto-telecom-asn1`:**
  - Unit tests for grammarless raw TLV dump analysis, lenient grammar diagnostics (`parseLenient`/`compileLenient` line/col errors), decode-library listing (`GET /parsers/asn1/decoders`) and per-field `V` decoder overrides, `matched` / `fieldPath` / cardinality metadata on `ParseResult.Node`, multi-level `DeriveSpec` (`reduce` + `group_by`), and multi-branch `ChildSpec` (`left` vs `inner` intra-record join).
  - Add synthetic MSC (`mocCall`) and IMS test fixtures (including an intentional tag-mismatch / broken-grammar test case) under `providers/asn-parser/corpus-synthetic/` verified by `SyntheticCorpusTest` and `Asn1ParserPluginTckTest`.
- **`platform/inspecto-etl` & `platform/inspecto-engine`:**
  - Integration tests for typed `DuckDbRecordSink` column creation, `PipelineConfigParser` + `RowShaper` (`transform.join`) chaining, and end-to-end `materialize` -> `GET /datasets/{id}/rows`.
- **`inspecto-ui`:**
  - Component/service unit tests covering tree-node selection -> `DecodeProfile`/`Step` generation -> dataset registration and materialization trigger.
