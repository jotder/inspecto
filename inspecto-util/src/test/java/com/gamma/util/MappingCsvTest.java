package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link MappingCsv} — the shared Mapping CSV shape (ELT amendment §3.2, Phase 1). */
class MappingCsvTest {

    @Test
    void roundTripsRulesThroughEncodeAndParse() {
        List<Map<String, String>> rules = List.of(
                Map.of("targetColumn", "ORDER_ID", "sourceExpression", "ORDER_ID", "transformType", "DIRECT"),
                Map.of("targetColumn", "GROSS",
                        "sourceExpression", "ROUND(TRY_CAST(Q AS DOUBLE) * TRY_CAST(P AS DOUBLE), 2)",
                        "transformType", "EXPR"));
        List<Map<String, String>> back = MappingCsv.parse(MappingCsv.encode(rules), "test");
        assertEquals(rules, back, "encode → parse must be lossless, commas and all");
    }

    @Test
    void acceptsThePlanShortHeaderAliases() {
        List<Map<String, String>> rules = MappingCsv.parse("""
                target,source,kind
                REGION,"UPPER(TRIM(REGION))",expr
                """, "test");
        assertEquals("REGION", rules.get(0).get("targetColumn"));
        assertEquals("UPPER(TRIM(REGION))", rules.get(0).get("sourceExpression"));
        assertEquals("expr", rules.get(0).get("transformType"));
    }

    @Test
    void backslashesAreLiteral() {
        // The reason reads go through Csv (RFC4180): Windows paths inside expressions survive.
        List<Map<String, String>> rules = MappingCsv.parse("""
                targetColumn,sourceExpression,transformType
                F,"regexp_extract(name, 'C:\\data')",EXPR
                """, "test");
        assertEquals("regexp_extract(name, 'C:\\data')", rules.get(0).get("sourceExpression"));
    }

    @Test
    void badHeaderAndEmptyBodyFailFast() {
        assertThrows(IllegalArgumentException.class, () -> MappingCsv.parse("a,b\n1,2\n", "f.csv"));
        assertThrows(IllegalArgumentException.class,
                () -> MappingCsv.parse("targetColumn,sourceExpression,transformType\n", "f.csv"));
        assertThrows(IllegalArgumentException.class, () -> MappingCsv.parse("", "f.csv"));
    }

    @Test
    void siblingNaming() {
        assertEquals(Path.of("c", "orders_mapping.csv"),
                MappingCsv.siblingFor(Path.of("c", "orders_schema.toon")));
        assertEquals(Path.of("c", "schema_mapping.csv"),
                MappingCsv.siblingFor(Path.of("c", "schema.toon")));
    }
}
