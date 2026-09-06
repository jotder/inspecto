package com.gamma.etl;

import com.gamma.config.spec.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SchemaMappingDrift}: a mapping input naming a column the schema no longer declares is a cell-level ERROR. */
class SchemaMappingDriftTest {

    private static Map<String, Object> schema(List<String> rawNames, List<Map<String, Object>> fields) {
        return Map.of(
                "raw", Map.of("name", "ev", "fields", rawNames.stream()
                        .map(n -> Map.<String, Object>of("name", n, "type", "VARCHAR")).toList()),
                "mapping", Map.of("canonicalName", "ev", "fields", fields));
    }

    @Test
    void aFromNamingAnUndeclaredColumnIsAnErrorOnThatCell() {
        List<Finding> f = SchemaMappingDrift.check(schema(List.of("ID", "QUANTITY"), List.of(
                Map.of("name", "ID", "from", "ID", "fn", "keep"),
                Map.of("name", "QTY", "from", "QTY", "fn", "keep"))));
        assertEquals(1, f.size(), f.toString());
        assertEquals("mapping.fields[QTY].from", f.get(0).fieldPath());
        assertTrue(f.get(0).message().contains("'QTY'"));
    }

    @Test
    void aColumnArgumentDriftsTooAndACustomRowIsNotAColumnReference() {
        List<Finding> f = SchemaMappingDrift.check(schema(List.of("ID"), List.of(
                Map.of("name", "NOTE", "from", "ID", "fn", "text.join", "args", Map.of("separator", " ", "other", "REMARK")),
                Map.of("name", "X", "fn", "custom", "args", Map.of("expression", "REMARK || 'x'")))));
        assertEquals(1, f.size(), f.toString());
        assertEquals("mapping.fields[NOTE].args.other", f.get(0).fieldPath());
    }

    @Test
    void legacyRulesAreCheckedThroughTheSameConverterTheEngineReads() {
        Map<String, Object> s = Map.of(
                "raw", Map.of("name", "ev", "fields", List.of(
                        Map.of("name", "D", "type", "DATE"), Map.of("name", "T", "type", "VARCHAR"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "D", "sourceExpression", "D", "transformType", "DIRECT"),
                        Map.of("targetColumn", "DT", "sourceExpression", "D|TIME", "transformType", "CONCAT_DT"),
                        Map.of("targetColumn", "E", "sourceExpression", "TRY_CAST(Q AS DOUBLE)", "transformType", "EXPR"))));
        List<Finding> f = SchemaMappingDrift.check(s);
        assertEquals(1, f.size(), f.toString());
        assertTrue(f.get(0).fieldPath().startsWith("mapping.fields[DT]"), f.get(0).fieldPath());
        assertTrue(f.get(0).message().contains("'TIME'"), "the CONCAT_DT time column is the drifted input");
    }

    @Test
    void aConsistentDocumentAndAStructureOnlyDocumentGateNothing() {
        assertTrue(SchemaMappingDrift.check(schema(List.of("ID", "QTY"), List.of(
                Map.of("name", "ID", "from", "ID", "fn", "keep"),
                Map.of("name", "QTY", "from", "QTY", "fn", "num.round", "args", Map.of("decimals", "2"))))).isEmpty());
        assertTrue(SchemaMappingDrift.check(Map.of("raw", Map.of("name", "ev", "fields", List.of(
                Map.of("name", "ID", "type", "VARCHAR"))))).isEmpty(), "no mapping: nothing to drift");
        assertTrue(SchemaMappingDrift.check(Map.of("mapping", Map.of("fields", List.of(
                Map.of("name", "ID", "from", "ID", "fn", "keep"))))).isEmpty(), "no raw.fields: the spec validator's problem");
    }
}
