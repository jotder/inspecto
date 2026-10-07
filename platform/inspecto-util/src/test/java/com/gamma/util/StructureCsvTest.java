package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StructureCsv} — the Schema structure CSV shape (ELT amendment §3.2 first table, STRUCTURE-CSV-1). */
class StructureCsvTest {

    @Test
    void roundTripsFieldsThroughEncodeAndParse() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("name", "ORDER_ID"); a.put("selector", "0"); a.put("type", "VARCHAR");
        a.put("description", "Order identifier"); a.put("classification", "INTERNAL");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("name", "NOTE"); b.put("selector", "4"); b.put("type", "VARCHAR");
        b.put("description", "Free-text note; blank when the source wrote a null, or \"n/a\"");
        List<Map<String, String>> back = StructureCsv.parse(StructureCsv.encode(List.of(a, b)), "test");
        assertEquals(List.of(a, b), back, "encode → parse must be lossless, commas and quotes included");
    }

    @Test
    void acceptsThePlanHeaderInAnyOrderAndOmitsBlankOptionalCells() {
        List<Map<String, String>> fields = StructureCsv.parse("""
                type,field,unit,selector,description,classification
                INTEGER,QUANTITY,count,4,Units ordered,
                DATE,ORDER_DATE,,1,,
                """, "test");
        assertEquals(2, fields.size());
        assertEquals(Map.of("name", "QUANTITY", "selector", "4", "type", "INTEGER",
                "description", "Units ordered", "unit", "count"), fields.get(0));
        assertEquals(Map.of("name", "ORDER_DATE", "selector", "1", "type", "DATE"), fields.get(1),
                "blank unit/description/classification are omitted, not carried as empty strings");
    }

    @Test
    void badHeaderMissingTypeAndEmptyBodyFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> StructureCsv.parse("field,selector\nA,0\n", "x"), "type column is required");
        assertThrows(IllegalArgumentException.class,
                () -> StructureCsv.parse("field,type\nA,\n", "x"), "a blank type cell is refused");
        assertThrows(IllegalArgumentException.class,
                () -> StructureCsv.parse("field,type\n", "x"), "header without fields");
        assertThrows(IllegalArgumentException.class, () -> StructureCsv.parse("", "x"));
    }

    @Test
    void splittableRefusesFieldsCarryingKeysTheCsvCannotHold() {
        assertTrue(StructureCsv.splittable(List.of(
                Map.of("name", "A", "selector", "0", "type", "VARCHAR"),
                Map.of("name", "B", "type", "DATE", "unit", "", "classification", "PII"))));
        assertFalse(StructureCsv.splittable(List.of(
                Map.of("name", "TS", "type", "TIMESTAMPTZ", "timezone", "Europe/Paris"))),
                "timezone has no column — the list must stay inline");
        assertFalse(StructureCsv.splittable(List.of(Map.of("name", "A"))), "type is required");
        assertFalse(StructureCsv.splittable(List.of()));
    }

    @Test
    void siblingNaming() {
        assertEquals(Path.of("cfg", "orders_structure.csv"),
                StructureCsv.siblingFor(Path.of("cfg", "orders_schema.toon")));
        assertEquals(Path.of("cfg", "ev_structure.csv"), StructureCsv.siblingFor(Path.of("cfg", "ev.toon")));
    }
}
