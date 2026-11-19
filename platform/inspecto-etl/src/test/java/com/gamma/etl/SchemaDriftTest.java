package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code quality.schema.drift} (SP-DQ-06): the header a file actually carries vs the {@code raw.fields[]}
 * it was declared with. The engine skips the header and reads by position, so before this nothing could
 * tell an operator that a feed grew a column — or, worse, grew one in the MIDDLE and shifted every
 * selector to its right.
 */
class SchemaDriftTest {

    /** Three positional fields — the schema half of every case below. */
    private static Map<String, Object> schema() {
        List<Map<String, Object>> fields = List.of(
                field("A", 0), field("B", 1), field("C", 2));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", fields);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("raw", raw);
        return s;
    }

    private static Map<String, Object> field(String name, int selector) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("selector", selector);
        f.put("type", "VARCHAR");
        return f;
    }

    private static PipelineConfig cfg(Path dir, boolean hasHeader, int skipTailColumns) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path pipeline = dir.resolve("drift_pipeline.toon");
        Files.writeString(pipeline, """
                name: DRIFT
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: PARQUET
                parsing:
                  frontend: delimited
                  delimited:
                    has_header: %s
                    skip_tail_columns: %d
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                """.formatted(d, d, d, d, d, d, d, hasHeader, skipTailColumns), StandardCharsets.UTF_8);
        return PipelineConfig.load(pipeline.toString());
    }

    private static File csv(Path dir, String name, String header) throws Exception {
        Path p = dir.resolve(name);
        // Deliberately a 5-wide data row under a 2..5-wide header: the detector reads the HEADER, and a
        // malformed data row must not hide it (ignore_errors on the sniff) — the real read judges rows.
        Files.writeString(p, header + "\n1,2,3,4,5\n", StandardCharsets.UTF_8);
        return p.toFile();
    }

    private static SchemaDrift.Report detect(Path dir, String header, boolean hasHeader, int tail) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            return SchemaDrift.detect(csv(dir, "f.csv", header), schema(), cfg(dir, hasHeader, tail), conn);
        }
    }

    @Test
    void anIdenticalHeaderIsNotDrift(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "A,B,C", true, 0);
        assertNotNull(r);
        assertFalse(r.drifted(), r.toString());
        assertTrue(r.namesCompared());
        assertEquals(List.of("A", "B", "C"), r.observed());
    }

    @Test
    void aNewTrailingColumnIsReportedByName(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "A,B,C,D", true, 0);
        assertTrue(r.drifted());
        assertEquals(4, r.observedWidth());
        assertEquals(3, r.declaredWidth());
        assertEquals(List.of("D"), r.added());
        assertEquals(List.of(), r.missing());
    }

    /** The dangerous case: every positional selector right of the insert now reads the wrong column. */
    @Test
    void aColumnInsertedMidRowIsCaught(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "A,X,B,C", true, 0);
        assertTrue(r.drifted());
        assertEquals(List.of("X"), r.added());
    }

    @Test
    void aMissingDeclaredColumnIsReported(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "A,C", true, 0);
        assertTrue(r.drifted());
        assertEquals(2, r.observedWidth());
        assertEquals(List.of("B"), r.missing());
    }

    /** No declared name occurs in the header ⇒ a positional lane: only the width may speak. */
    @Test
    void positionalNamesCompareWidthOnly(@TempDir Path dir) throws Exception {
        SchemaDrift.Report same = detect(dir, "x,y,z", true, 0);
        assertFalse(same.namesCompared());
        assertFalse(same.drifted(), "renamed-but-same-width is not drift on a positional lane: " + same);

        SchemaDrift.Report wider = detect(dir, "x,y,z,w", true, 0);
        assertTrue(wider.drifted());
        assertEquals(List.of(), wider.added(), "names are not compared, so nothing is 'added' by name");
    }

    /** skip_tail_columns declares that trailing columns are dropped on purpose — not drift. */
    @Test
    void toleratedTailColumnsAreNotDrift(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "x,y,z,w", true, 1);
        assertEquals(1, r.toleratedExtra());
        assertFalse(r.drifted(), r.toString());
        assertTrue(detect(dir, "x,y,z,w,v", true, 1).drifted(), "one past the tolerance is drift again");
    }

    @Test
    void noHeaderMeansNoReport(@TempDir Path dir) throws Exception {
        assertNull(detect(dir, "A,B,C", false, 0), "a detector that cannot see must say nothing");
    }

    @Test
    void headerNamesAreComparedCaseInsensitively(@TempDir Path dir) throws Exception {
        SchemaDrift.Report r = detect(dir, "a,b,c", true, 0);
        assertTrue(r.namesCompared());
        assertFalse(r.drifted(), r.toString());
    }

    // ── the one comparison (BASIC-ELT-PROFILE-1 B5): diff and settle, pure ──────────────────────────────────

    private static List<SchemaDrift.Declared> abc() {
        return List.of(new SchemaDrift.Declared("0", "a", "BIGINT"), new SchemaDrift.Declared("1", "b", "VARCHAR"),
                new SchemaDrift.Declared("2", "c", "DATE"));
    }

    private static List<SchemaDrift.Observed> seen(String... names) {
        List<SchemaDrift.Observed> out = new java.util.ArrayList<>();
        for (String n : names) out.add(new SchemaDrift.Observed(n, n, "VARCHAR", null));
        return out;
    }

    private static List<String> names(List<SchemaDrift.Observed> cols) {
        return cols.stream().map(SchemaDrift.Observed::name).toList();
    }

    /** By position: a declared name at another position has moved, one nowhere is missing, the rest are new. */
    @Test
    void byPositionAColumnElsewhereHasMovedAndOneNowhereIsMissing() {
        SchemaDrift.Diff d = SchemaDrift.diff(abc(), seen("a", "c", "x"), SchemaDrift.By.POSITION);
        assertEquals(List.of("b"), d.missing().stream().map(SchemaDrift.Declared::name).toList());
        SchemaDrift.Placed c = d.fields().get(2);
        assertTrue(c.moved(), c.toString());
        assertEquals(1, c.at());
        assertFalse(d.fields().get(0).moved());
        assertEquals(List.of("x"), names(d.added()));
        assertEquals(3, d.declaredWidth());
        assertEquals(List.of(), names(d.keepable()), "x sits inside the declared width: it cannot be read on by position");
    }

    /** Only the new columns right after the declared width, in a row, can be read on by position. */
    @Test
    void byPositionTheNewColumnsAfterTheReadOnesAreKeepable() {
        SchemaDrift.Diff d = SchemaDrift.diff(abc(), seen("a", "b", "c", "x", "y"), SchemaDrift.By.POSITION);
        assertTrue(d.missing().isEmpty());
        assertEquals(List.of("x", "y"), names(d.added()));
        assertEquals(List.of("x", "y"), names(d.keepable()));
    }

    /** A positional lane compares the width only: a renamed column is not drift, a short file is. */
    @Test
    void byWidthNamesAreNotCompared() {
        SchemaDrift.Diff same = SchemaDrift.diff(abc(), seen("p", "q", "r"), SchemaDrift.By.WIDTH);
        assertTrue(same.missing().isEmpty() && same.added().isEmpty(), same.toString());
        SchemaDrift.Diff narrow = SchemaDrift.diff(abc(), seen("p", "q"), SchemaDrift.By.WIDTH);
        assertEquals(List.of("c"), narrow.missing().stream().map(SchemaDrift.Declared::name).toList());
        assertEquals(2, narrow.observedWidth());
    }

    /** By name: a selector finds its column wherever it is; a type the sample votes otherwise is a type change. */
    @Test
    void byNameEverySelectorIsANameAndTypesAreCompared() {
        List<SchemaDrift.Declared> declared = List.of(new SchemaDrift.Declared("id", "id", "BIGINT"),
                new SchemaDrift.Declared("x.y", "x_y", "VARCHAR"), new SchemaDrift.Declared("", "gone", null));
        List<SchemaDrift.Observed> observed = List.of(new SchemaDrift.Observed("z", "z", "BOOLEAN", null),
                new SchemaDrift.Observed("x_y", "x.y", "VARCHAR", null), new SchemaDrift.Observed("id", "id", "DOUBLE", null));
        SchemaDrift.Diff d = SchemaDrift.diff(declared, observed, SchemaDrift.By.NAME);
        assertEquals(List.of("gone"), d.missing().stream().map(SchemaDrift.Declared::name).toList());
        assertEquals(List.of("z"), names(d.added()));
        assertEquals(List.of("z"), names(d.keepable()), "by name every new column can be read on");
        assertEquals(1, d.typeChanged().size());
        assertEquals("id", d.typeChanged().get(0).field().name());
    }

    private static SchemaDrift.Observed typed(String type, String format) {
        return new SchemaDrift.Observed("t", "t", type, format);
    }

    /** One type across files: agreement, whole and decimal numbers together, and text for anything else. */
    @Test
    void aColumnSettlesToTheTypeEveryFileAgreesOn() {
        assertEquals("TIME", SchemaDrift.settle(List.of(typed("TIME", null), typed("TIME", null))).type(),
                "a time of day settles to TIME (BASIC-ELT-PROFILE-1 U3)");
        assertEquals("DOUBLE", SchemaDrift.settle(List.of(typed("BIGINT", null), typed("DOUBLE", null))).type());
        SchemaDrift.Observed date = SchemaDrift.settle(List.of(typed("DATE", "%d/%m/%Y"), typed("DATE", "%d/%m/%Y")));
        assertEquals("DATE", date.type());
        assertEquals("%d/%m/%Y", date.format());
        assertEquals("VARCHAR", SchemaDrift.settle(List.of(typed("DATE", "%d/%m/%Y"), typed("DATE", "%m/%d/%Y"))).type(),
                "two formats are no one answer");
        assertEquals("VARCHAR", SchemaDrift.settle(List.of(typed("TIME", null), typed("VARCHAR", null))).type());
        java.util.List<SchemaDrift.Observed> lacking = new java.util.ArrayList<>(List.of(typed("BIGINT", null)));
        lacking.add(null);
        SchemaDrift.Observed text = SchemaDrift.settle(lacking);
        assertEquals("VARCHAR", text.type(), "a file without the column: text");
        assertNull(text.format());
    }
}
