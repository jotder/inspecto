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
}
