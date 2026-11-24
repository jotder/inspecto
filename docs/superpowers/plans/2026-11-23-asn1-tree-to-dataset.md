# ASN.1 Event Tree to Tabular Dataset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the end-to-end Inspecto ASN.1 workflow: grammarless Raw TLV Dump & lenient grammar editing/validation, Event Roots routing (1 sink/dataset per event), `$header.*` propagation, leaf-only `V`-decoder mapping from the Decode Library, `SET_OF`/`SEQUENCE_OF` Member Fields Table (`group_by` + `sum`/`min`/`max`/`mean`/`count` -> `join` or `fold` delimited/JSON), Sink Table Preview & Table Metadata, and typed DuckDB/Parquet dataset population.

**Architecture:** Pre-SQL streaming decode, event-root routing, `$header.*` capture, leaf `V`-decoder overrides, and intra-record `map`/`group_by`/`fold`/`join` execute in Java (`asn-schema`, `inspecto-etl`, `inspecto-telecom-asn1`). Post-ingest typed staging (`DuckDbRecordSink` aligned with `TypeFlow`), `TRY_CAST` projection (`DataTransformer`), and Parquet/CSV materialization (`PartitionWriter`) run in `inspecto-engine`, orchestrated by `inspecto-ui` (`PipelineParseDefinitionComponent`, `GrammarEditorComponent`, `ParsersService`, `DatasetRegistrationService`).

**Tech Stack:** JDK 27, Maven (`asn-decoders`, `inspecto-telecom-asn1`, `inspecto-etl`, `inspecto-engine`, `inspecto`), JUnit 5, DuckDB, Angular 22 (`inspecto-ui`).

**Spec:** `docs/superpowers/specs/2026-11-23-asn1-tree-to-dataset-design.md`

## Global Constraints

- Pure JDK 27 runtime in `providers/asn-parser/asn-decoders` (no external ASN.1 libraries).
- `V`-decoder selection applies strictly to **leaf/value nodes** (never container or `SET_OF`/`SEQUENCE_OF` nodes).
- `group_by`, `fold`, and intra-record `join` apply strictly to **sub-node / `SET_OF` / `SEQUENCE_OF` containers** (never scalar leaves).
- Preserve `BerReader` (`MAX_DEPTH=200`, `DEFAULT_MAX_VALUE_BYTES=64 MiB`), `DeriveSpec` (`DEFAULT_MAX_LIST_ELEMENTS=10_000`, `CEILING_MAX_LIST_ELEMENTS=1_000_000`, `DEFAULT_MAX_DERIVED_COLUMNS=64`, `CEILING_MAX_DERIVED_COLUMNS=256`), and `ChildSpec` (`DEFAULT_MAX_CHILD_ROWS=10_000`, `CEILING_MAX_CHILD_ROWS=1_000_000`) caps.
- Preview uses `RecoveryPolicy.SKIP_RECORD`; streaming ingest uses `RecoveryPolicy.STOP_FILE`.
- External cross-table `transform.join` is excluded from this parse-to-dataset flow.

## Review Focus

- **Broken / partial ASN.1 grammar with unmatched tags:** `Asn1Parser.parseLenient` + `SchemaCompiler.compileLenient` must return structured `{line, column, severity, message}` diagnostics without throwing, and `SchemaBinder` fallback nodes must surface `matched=false` with tag-path + hex `V`.
- **Empty `SET_OF`/`SEQUENCE_OF` list on a record:** `left` join emits 1 parent row with `NULL` child/group columns; `inner` join emits 0 rows; `fold` emits `""` (delimited) or `"[]"` (JSON).
- **Column name collision on flat table:** Leaf `rename` + `prefix`/`postfix` must be validated by `DeriveSpec.checkClash` before DuckDB table creation.
- **Unparseable typed value in DuckDB sink:** `DataTransformer.selectFor` must wrap typed columns (`BIGINT`, `TIMESTAMP`, etc.) in `TRY_CAST` / `NULLIF` so one bad value becomes `NULL` rather than aborting the batch.
- **Missing or empty file-level `$header.*` block:** Missing header fields (e.g. `batchControlInfo` absent) must resolve to `NULL` without failing record emission.

---

### Task 1: Lenient Diagnostics, Alias Simplification & Decode Library (`asn-schema`)

**Files:**
- Modify: `providers/asn-parser/asn-decoders/asn-schema/src/main/java/com/gamma/asn/schema/Asn1Parser.java`
- Modify: `providers/asn-parser/asn-decoders/asn-schema/src/main/java/com/gamma/asn/schema/SchemaCompiler.java`
- Modify: `providers/asn-parser/asn-decoders/asn-schema/src/main/java/com/gamma/asn/schema/DecoderRegistry.java`
- Test: `providers/asn-parser/asn-decoders/asn-schema/src/test/java/com/gamma/asn/schema/SchemaDiagnosticsAndAliasTest.java`

**Interfaces:**
- Produces:
  - `public record Diagnostic(int line, int column, String severity, String message)` (in `com.gamma.asn.schema`)
  - `Asn1Parser.parseLenientDiagnostics(String text, List<Diagnostic> out): List<ModuleAst>`
  - `SchemaCompiler.compileLenient(List<ModuleAst> modules, String rootTypeName, boolean simplifyAliases, List<Diagnostic> out): CompiledSchema`
  - `public record DecoderMeta(String id, String description, String sqlType)`
  - `DecoderRegistry.listDecoders(): List<DecoderMeta>`

- [ ] **Step 1: Write failing tests for structured diagnostics, alias simplification (`a := b, b := c` -> `a := c`), and `DecoderRegistry.listDecoders()`**

```java
package com.gamma.asn.schema;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SchemaDiagnosticsAndAliasTest {
    @Test
    void parseLenientDiagnosticsCapturesLineAndColumn() {
        String broken = """
            TestMod DEFINITIONS ::= BEGIN
            BadType ::= SEQUENCE {
              a [0] OCTET STRING,
              b [1] UNKNOWN_BROKEN
            """;
        List<Diagnostic> diags = new ArrayList<>();
        Asn1Parser.parseLenientDiagnostics(broken, diags);
        assertFalse(diags.isEmpty());
        assertTrue(diags.stream().anyMatch(d -> d.line() >= 1 && !d.message().isBlank()));
    }

    @Test
    void compileLenientSimplifiesAliasChain() {
        String asn = """
            TapMod DEFINITIONS ::= BEGIN
            CallingNumber ::= NumberString
            NumberString ::= OCTET STRING
            Call ::= SEQUENCE { callingNumber [0] CallingNumber }
            END
            """;
        List<Diagnostic> diags = new ArrayList<>();
        CompiledSchema schema = SchemaCompiler.compileLenient(
            Asn1Parser.parseLenientDiagnostics(asn, diags), "Call", true, diags);
        CompiledType calling = schema.root().components().get(0).type();
        assertEquals(CompiledType.Kind.PRIMITIVE, calling.kind());
    }

    @Test
    void decoderRegistryListsMetadataWithSqlTypes() {
        List<DecoderRegistry.DecoderMeta> metas = DecoderRegistry.withDefaults().listDecoders();
        assertTrue(metas.stream().anyMatch(m -> m.id().equals("tbcd") && m.sqlType().equals("VARCHAR")));
        assertTrue(metas.stream().anyMatch(m -> m.id().equals("ccnTimeStamp") && m.sqlType().equals("TIMESTAMP")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -f providers/asn-parser/asn-decoders/pom.xml -pl asn-schema -Dtest=SchemaDiagnosticsAndAliasTest test`
Expected: FAIL (cannot find symbol `Diagnostic`, `parseLenientDiagnostics`, `listDecoders`)

- [ ] **Step 3: Implement `Diagnostic`, `parseLenientDiagnostics`, `compileLenient(..., simplifyAliases, ...)`, and `DecoderRegistry.listDecoders()`**

```java
// Diagnostic.java in com.gamma.asn.schema
package com.gamma.asn.schema;

public record Diagnostic(int line, int column, String severity, String message) {}
```

In `Asn1Parser.java`, add `parseLenientDiagnostics(String text, List<Diagnostic> out)` delegating to the existing `parseLenient` parser error collector and parsing `(line:col)` prefixes into `Diagnostic(line, col, "ERROR", msg)`.
In `SchemaCompiler.java`, add `compileLenient(List<ModuleAst> modules, String rootTypeName, boolean simplifyAliases, List<Diagnostic> out)` that collapses pass-through `TypeAst.Ref` alias chains (`a -> b -> c` resolves directly to `c`'s `CompiledType`) when `simplifyAliases == true`.
In `DecoderRegistry.java`, add `public record DecoderMeta(String id, String description, String sqlType)` and `public List<DecoderMeta> listDecoders()` returning metadata for all registered universal & telecom decoders (`tbcd`, `addressString`, `ccnTimeStamp`, `timeStamp`, `plmnId`, `imei`, `ipAddress`, `integer`, `unsignedInteger`, `hex`, `latin1`, etc.).

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -o -f providers/asn-parser/asn-decoders/pom.xml -pl asn-schema -Dtest=SchemaDiagnosticsAndAliasTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add providers/asn-parser/asn-decoders/asn-schema/
git commit -m "feat(asn-schema): add structured diagnostics, alias simplification, and decoder catalog"
```

---

### Task 2: `ParseResult.Node` Metadata, Sectioned `grammarSchema()`, and Decoder/Validate Routes (`inspecto-engine`, `inspecto-telecom-asn1`, `inspecto`)

**Files:**
- Modify: `platform/inspecto-engine/src/main/java/com/gamma/parse/ParseResult.java`
- Modify: `platform/inspecto-config/src/main/java/com/gamma/config/spec/FieldSpec.java`
- Modify: `providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1ParserPlugin.java`
- Modify: `inspecto/src/main/java/com/gamma/control/ParserRoutes.java`
- Test: `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/Asn1PluginMetadataAndRoutesTest.java`

**Interfaces:**
- Consumes: `Diagnostic`, `DecoderRegistry.listDecoders()`, `Asn1Parser.parseLenientDiagnostics`, `SchemaCompiler.compileLenient` (Task 1)
- Produces:
  - `ParseResult.Node(String label, String type, String value, List<Node> children, Map<String, Object> meta)` (plus 4-arg backwards-compatible constructor & `leaf`/`container` overloads) where `meta` carries `fieldPath`, `tagPath`, `nodeRole` (`leaf` | `container` | `repeated`), `matched` (`boolean`), `decoderId`
  - `Asn1ParserPlugin.grammarSchema()` with `section` grouping (`"Grammar & Event Roots"`, `"Header & Framing"`, `"Limits"`)
  - `GET /parsers/asn1/decoders` -> `List<DecoderRegistry.DecoderMeta>`
  - `POST /parsers/asn1/validate` -> `{ valid: boolean, diagnostics: List<Diagnostic> }`

- [ ] **Step 1: Write failing test for `ParseResult.Node` metadata, sectioned `grammarSchema()`, and unmatched node highlighting**

```java
package com.gamma.telecom.asn1;

import com.gamma.parse.ParseResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class Asn1PluginMetadataAndRoutesTest {
    @Test
    void previewPopulatesNodeRoleMatchedAndFieldPath() throws Exception {
        Asn1ParserPlugin plugin = new Asn1ParserPlugin();
        assertTrue(plugin.grammarSchema().stream().anyMatch(f -> f.section() != null && !f.section().isBlank()));

        byte[] sample = new byte[] { 0x30, 0x06, (byte) 0x80, 0x01, 0x05, (byte) 0x89, 0x01, 0x0A };
        String grammar = "M DEFINITIONS ::= BEGIN Rec ::= SEQUENCE { a [0] INTEGER } END";
        ParseResult.Tree tree = (ParseResult.Tree) plugin.preview(sample, Map.of("grammar_text", grammar, "root_type", "Rec"));
        ParseResult.Node root = tree.nodes().get(0);
        assertEquals("container", root.meta().get("nodeRole"));
        assertEquals("leaf", root.children().get(0).meta().get("nodeRole"));
        assertEquals(true, root.children().get(0).meta().get("matched"));
        assertEquals(false, root.children().get(1).meta().get("matched"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl providers/inspecto-telecom-asn1 -Dtest=Asn1PluginMetadataAndRoutesTest test`
Expected: FAIL (missing `meta()` / `section()`)

- [ ] **Step 3: Implement `ParseResult.Node` `meta` map, `FieldSpec.section`, `Asn1ParserPlugin` metadata population, and `ParserRoutes` endpoints**

Update `ParseResult.Node` to add `Map<String, Object> meta` with a compact canonical delegating 4-arg constructor `this(label, type, value, children, Map.of())` so existing callers remain untouched. Update `Asn1ParserPlugin.toNode(NamedNode, ...)` and `toNode(Tlv, ...)` to populate `fieldPath`, `tagPath`, `nodeRole` (`leaf` | `container` | `repeated`), `matched` (`n.type() != null`), and `decoderId`. Add `section` to `Asn1ParserPlugin.grammarSchema()`. Register `GET /parsers/asn1/decoders` and `POST /parsers/asn1/validate` in `ParserRoutes.java`.

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -o -pl platform/inspecto-engine,providers/inspecto-telecom-asn1 -Dtest=Asn1PluginMetadataAndRoutesTest,Asn1ParserPluginTest,Asn1ParserPluginTckTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/inspecto-engine/ providers/inspecto-telecom-asn1/ inspecto/
git commit -m "feat(asn1): surface node metadata, sectioned grammarSchema, and decoder/validate routes"
```

---

### Task 3: Header Context (`$header.*`), Leaf `V`-Decoder Overrides & Event-Root Routing (`inspecto-telecom-asn1`, `inspecto-etl`)

**Files:**
- Modify: `providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1RecordIngester.java`
- Modify: `providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1ParserPlugin.java`
- Modify: `platform/inspecto-etl/src/main/java/com/gamma/etl/DecodeProfile.java`
- Test: `providers/inspecto-telecom-asn1/src/test/java/com/gamma/telecom/asn1/EventRouterAndHeaderTest.java`

**Interfaces:**
- Consumes: `DecoderRegistry` (Task 1), `Asn1ParserPlugin` (Task 2)
- Produces:
  - `DecodeProfile` keys: `event_roots` (`Map<String, String>` mapping root path -> segment/sink key), `header_fields` (`List<String>`), `field_decoders` (`Map<String, String>` mapping leaf `fieldPath` -> decoderId)
  - `Asn1RecordIngester.emitRecords(...)` resolves `$header.<field>`, applies `field_decoders` to leaf `V` bytes, and routes each matched `event_root` to its dedicated segment sink.

- [ ] **Step 1: Write failing test for multi-event routing (`moc` vs `gprs`), `$header.*` injection, and leaf `field_decoders` override**

```java
package com.gamma.telecom.asn1;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventRouterAndHeaderTest {
    @Test
    void routesTwoEventsWithHeaderAndLeafVDecoderOverride() throws Exception {
        // Construct minimal grammar with header + CHOICE { moc [0], gprs [1] }
        String asn = """
            TestBatch DEFINITIONS ::= BEGIN
            Batch ::= SEQUENCE {
              header [0] SEQUENCE { sender [0] OCTET STRING },
              events [1] SEQUENCE OF Event
            }
            Event ::= CHOICE {
              moc  [0] SEQUENCE { msisdn [0] OCTET STRING },
              gprs [1] SEQUENCE { imsi   [0] OCTET STRING }
            }
            END
            """;
        // Test via Asn1ParserPlugin.previewRows with event_roots + header_fields + field_decoders
        // Assert two RowSets: "tap_moc_cdr" and "tap_gprs_cdr", each containing hdr_sender and decoded TBCD
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl providers/inspecto-telecom-asn1 -Dtest=EventRouterAndHeaderTest test`
Expected: FAIL

- [ ] **Step 3: Implement `$header.*` extraction, `field_decoders` leaf override, and `event_roots` routing in `DecodeProfile`, `Asn1RecordIngester`, and `Asn1ParserPlugin`**

In `DecodeProfile.java`, validate `event_roots`, `header_fields`, and `field_decoders`. In `Asn1RecordIngester.java`, capture `$header` fields from header nodes, apply `DecoderRegistry` overrides when `fieldPath` is present in `field_decoders`, and route each event root to its configured segment key in `RecordSink`.

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -o -pl platform/inspecto-etl,providers/inspecto-telecom-asn1 -Dtest=EventRouterAndHeaderTest,Asn1RecordIngesterTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/inspecto-etl/ providers/inspecto-telecom-asn1/
git commit -m "feat(asn1): add event-root routing, header context propagation, and leaf V-decoder overrides"
```

---

### Task 4: `SET_OF`/`SEQUENCE_OF` Shaping (`group_by` + Aggs -> `join` or `fold` Delimited/JSON) & Key Rename/Prefix/Postfix (`inspecto-etl`, `inspecto-telecom-asn1`)

**Files:**
- Modify: `platform/inspecto-etl/src/main/java/com/gamma/etl/DeriveSpec.java`
- Modify: `platform/inspecto-etl/src/main/java/com/gamma/etl/ChildSpec.java`
- Modify: `providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1RecordIngester.java`
- Test: `platform/inspecto-etl/src/test/java/com/gamma/etl/SetOfGroupByFoldJoinTest.java`

**Interfaces:**
- Consumes: `Asn1RecordIngester` (Task 3)
- Produces:
  - `DeriveSpec`: supports `mean` (`avg`), `max`, `min`, `sum`, `count`, `first`, leaf `rename` / `prefix` / `postfix`, and `fold` (`delim` with `item_delim` & `field_delim`, or `json`)
  - `ChildSpec`: supports optional `group_by` field + per-member aggregations (`aggs: Map<String, String>`) before emitting grouped sub-records via `join` (`left`/`inner`) or `fold` (`delim`/`json`).

- [ ] **Step 1: Write failing test for GGSN `listOfServiceData[]` `group_by(ratingGroup)` + `sum(downlink)` -> `join` (2 rows) vs `fold` (`delim` and `json`)**

```java
package com.gamma.etl;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SetOfGroupByFoldJoinTest {
    @Test
    void groupByRatingGroupProducesTwoSubRecordsJoinedOrFolded() {
        List<Map<String, Object>> serviceData = List.of(
            Map.of("ratingGroup", "100", "downlink", 8400L),
            Map.of("ratingGroup", "100", "downlink", 1600L),
            Map.of("ratingGroup", "200", "downlink", 2500L)
        );
        // Assert group_by("ratingGroup") + sum("downlink") -> [{ratingGroup:"100", downlink_sum:10000}, {ratingGroup:"200", downlink_sum:2500}]
        // Assert fold("delim", "|", ":") -> "100:10000|200:2500"
        // Assert fold("json") -> "[{\"ratingGroup\":\"100\",\"downlink_sum\":10000},{\"ratingGroup\":\"200\",\"downlink_sum\":2500}]"
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl platform/inspecto-etl -Dtest=SetOfGroupByFoldJoinTest test`
Expected: FAIL

- [ ] **Step 3: Implement `group_by` + per-member aggregations, `fold` (delimited/JSON), `left`/`inner` `join`, and leaf `prefix`/`postfix`/`rename` in `DeriveSpec`, `ChildSpec`, and `Asn1RecordIngester`**

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -o -pl platform/inspecto-etl,providers/inspecto-telecom-asn1 -Dtest=SetOfGroupByFoldJoinTest,Asn1RecordIngesterTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/inspecto-etl/ providers/inspecto-telecom-asn1/
git commit -m "feat(etl): support SET_OF group_by, multi-field aggs, delim/json fold, and prefix/postfix"
```

---

### Task 5: Typed `DuckDbRecordSink` Alignment & Table Metadata (`inspecto-engine`, `inspecto-etl`)

**Files:**
- Modify: `platform/inspecto-engine/src/main/java/com/gamma/inspector/DuckDbRecordSink.java`
- Modify: `platform/inspecto-etl/src/main/java/com/gamma/etl/TypeFlow.java`
- Modify: `providers/inspecto-telecom-asn1/src/main/java/com/gamma/telecom/asn1/Asn1ParserPlugin.java`
- Test: `platform/inspecto-engine/src/test/java/com/gamma/inspector/TypedDuckDbRecordSinkTest.java`

**Interfaces:**
- Consumes: `DeriveSpec` / `DecodeProfile` SQL type hints (Tasks 1, 3, 4)
- Produces:
  - `DuckDbRecordSink.defineTyped(String segmentKey, List<String> columns, Map<String, String> sqlTypes)` (default `define(...)` delegates with `VARCHAR` for backwards compatibility)
  - `ParseResult.RowSet` carries column metadata (`columnTypes`, `sourcePaths`, `decoders`) for the UI **Table Metadata** view.

- [ ] **Step 1: Write failing test verifying `DuckDbRecordSink` creates `BIGINT`/`TIMESTAMP` columns and `DataTransformer` safely `TRY_CAST`s**

```java
package com.gamma.inspector;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedDuckDbRecordSinkTest {
    @Test
    void definesTypedDuckDbStagingColumnsAndHandlesInvalidCast() throws Exception {
        // Verify DESCRIBE raw_<KEY> reports BIGINT / TIMESTAMP when sqlTypes provided
        // Verify non-numeric string becomes NULL via TRY_CAST without failing batch
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o -pl platform/inspecto-engine -Dtest=TypedDuckDbRecordSinkTest test`
Expected: FAIL

- [ ] **Step 3: Implement `defineTyped` in `DuckDbRecordSink` and metadata propagation in `Asn1ParserPlugin.previewRows`**

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn -o -pl platform/inspecto-engine,providers/inspecto-telecom-asn1 -Dtest=TypedDuckDbRecordSinkTest,Asn1ParserPluginTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform/inspecto-engine/ platform/inspecto-etl/ providers/inspecto-telecom-asn1/
git commit -m "feat(engine): align DuckDbRecordSink with typed columns and expose table metadata"
```

---

### Task 6: Interactive Pipeline Step UI & Parse-to-Dataset Workflow (`inspecto-ui`)

**Files:**
- Modify: `inspecto-ui/src/app/inspecto/api/parsers.service.ts`
- Modify: `inspecto-ui/src/app/inspecto/grammar/grammar-editor.component.ts`
- Modify: `inspecto-ui/src/app/modules/admin/pipelines/grammar-editor.dialog.ts`
- Modify: `inspecto-ui/src/app/modules/admin/pipelines/pipeline-parse-definition.component.ts`
- Modify: `inspecto-ui/src/app/inspecto/segments/segments-editor.component.ts`
- Test: `inspecto-ui/src/app/modules/admin/pipelines/pipeline-parse-definition.component.spec.ts`

**Interfaces:**
- Consumes:
  - `GET /parsers/asn1/decoders` (`ParsersService.decoders()`)
  - `POST /parsers/asn1/validate` (`ParsersService.validateGrammar()`)
  - `POST /parsers/asn1/preview` (`ParseResult.Node.meta`, `ParseResult.Rows` metadata)
  - `DatasetRegistrationService.ensure(...)`, `POST /datasets/{id}/materialize`

- [ ] **Step 1: Write failing Angular component/service unit tests for decoder library fetch, `SET_OF` member table state, Table Metadata toggle, and `DefinitionStateService.parsePreview` bridge**

```typescript
describe('PipelineParseDefinitionComponent ASN.1 workflow', () => {
  it('bridges preview.derived RowSet into DefinitionStateService.parsePreview', () => {
    // Mock ParsersService.preview returning kind: 'tree' + derived RowSet[]
    // Assert DefinitionStateService.parsePreview receives columns and sample rows
  });

  it('shows V-decoder picker only on leaf nodes and Member Fields Table on SET_OF nodes', () => {
    // Select leaf node -> assert V-decoder dropdown rendered
    // Select repeated node -> assert Member Fields Table rendered with groupBy radio & agg select
  });
});
```

- [ ] **Step 2: Run Angular tests to verify they fail**

Run: `npx ng test --include="**/pipeline-parse-definition.component.spec.ts" --watch=false` (in `inspecto-ui/`)
Expected: FAIL

- [ ] **Step 3: Implement the UI components**
  - Add `decoders()` and `validateGrammar()` to `ParsersService`.
  - Support sectioned `FieldSpec` rendering and unified serialization in `GrammarEditorComponent`, and enable saving in `GrammarEditorDialog`.
  - In `PipelineParseDefinitionComponent`:
    - Add Raw TLV Dump toggle, Simplify Aliases toggle, Event Roots selector, and `$header.*` pin bar.
    - Render Leaf `V`-Decoder + Rename/Prefix/Postfix inspector on `nodeRole === 'leaf'`, and scrollable **Member Fields Table** (`Use`, `Member Field`, `Type`, `Group By` radio, `Aggregation`, `Join`/`Fold` + delimiter inputs) on `nodeRole === 'repeated'`/`'container'`.
    - Add **Sink Table Preview** vs **Table Metadata** tabs, bridge `preview.derived` into `DefinitionStateService.parsePreview`, and wire **Create & Populate Sink** (`DatasetRegistrationService.ensure` + `POST /datasets/{id}/materialize`).
  - Add sample-value hints & drift detection to `InspectoSegmentsEditorComponent`.

- [ ] **Step 4: Run Angular tests to verify pass**

Run: `npx ng test --include="**/pipeline-parse-definition.component.spec.ts" --watch=false` (in `inspecto-ui/`)
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add inspecto-ui/
git commit -m "feat(ui): add ASN.1 tree-to-dataset studio, member table, and table metadata view"
```
