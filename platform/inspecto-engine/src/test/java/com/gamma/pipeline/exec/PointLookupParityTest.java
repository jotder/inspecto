package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LOOKUP-1 P3 — the parity suite: the same golden cases through the batch keyed join ({@link RowShaper}) and the point
 * engine ({@link PointLookup}); for every looked-up value, every column the join adds must read the same.
 */
class PointLookupParityTest {

    @TempDir Path dir;
    private Connection conn;

    @AfterEach
    void close() throws SQLException { if (conn != null) conn.close(); }

    private static final String DIAL = """
            dial_digit_key,iso_country_code,provider_desc,dm_ind,rank
            249,SD,Sudan,0,1
            24991,SD,Operator A,0,2
            2499123,SD,Operator A Toll Free,1,3
            44,GB,United Kingdom,0,4
            0044,GB,UK zero form,0,5
            44,GB,United Kingdom (newer),0,6
            i,TR,dotted i,0,7
            οδοσ,GR,road,0,8
            STRAẞE,DE,street,0,9
            """;

    private static final List<String> VALUES = List.of("249915555555", "2499123", "249912300000", "24991", "999",
            "447700900123", "+447700900123", " 44 ", "0044", "", "4",
            // Unicode, where Java's trim/lower/upper differ from DuckDB's (found by verification, 2026-11-24)
            "44\u00A0", "\u00A044", "44\u3000", "\u0130", "\u039F\u0394\u039F\u03A3", "stra\u00DFe", "STRASSE", "44 \n");

    private RowShaper.ReferenceResolver resolver(Path csv) {
        return new RowShaper.ReferenceResolver() {
            @Override
            public String resolve(Connection c, String reference) throws SQLException {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE OR REPLACE VIEW dd AS SELECT * FROM read_csv('" + csv.toString().replace('\\', '/')
                            + "', header=true, all_varchar=true)");
                }
                return "dd";
            }

            @Override public List<Path> readFiles(String reference) { return List.of(csv); }
        };
    }

    /** Batch: each value as one input row, joined; returns value → (added column → text). */
    private Map<String, Map<String, String>> batch(Map<String, Object> cfg, RowShaper.ReferenceResolver refs,
                                                   List<String> added) throws Exception {
        if (conn == null) conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("parity_", dir));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE vin (id INTEGER, v VARCHAR)");
            for (int i = 0; i < VALUES.size(); i++)
                st.execute("INSERT INTO vin VALUES (" + i + ", '" + VALUES.get(i).replace("'", "''") + "')");
        }
        String id = "p" + System.nanoTime();
        var rel = RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "vin", id, refs);
        String table = rel.stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) {
                Map<String, String> r = new LinkedHashMap<>();
                for (String c : added) r.put(c, rs.getString(c));
                out.put(VALUES.get(rs.getInt("id")), r);
            }
        }
        return out;
    }

    private void assertParity(Map<String, Object> cfg, String csvText) throws Exception {
        Path csv = dir.resolve("ref" + System.nanoTime() + ".csv");
        Files.writeString(csv, csvText);
        RowShaper.ReferenceResolver refs = resolver(csv);
        PointLookup point = PointLookup.load(PipelineNode.of("j", "transform.join", cfg), refs);
        List<String> added = new ArrayList<>(point.test("x").mappings().get(0).columns().keySet());
        Map<String, Map<String, String>> want = batch(cfg, refs, added);
        for (String v : VALUES) {
            Map<String, String> got = point.test(v).mappings().get(0).columns();
            assertEquals(want.get(v), got, "value '" + v + "' under " + cfg);
        }
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("reference", "r", "key", "dial_digit_key", "on", "v",
                "details", true, "duplicates", "last", "order_by", "CAST(rank AS INTEGER)"));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void longestPrefixWithExactWhenAndADefaultAgree() throws Exception {
        assertParity(cfg("match", "longest_prefix", "exact_when", "dm_ind = '1'",
                "columns", List.of("provider_desc", "iso_country_code"), "default", List.of("provider_desc=UNKNOWN")), DIAL);
    }

    @Test
    void exactAgrees() throws Exception {
        assertParity(cfg("match", "exact"), DIAL);
    }

    @Test
    void normalizedExactAndPrefixAgree() throws Exception {
        assertParity(cfg("match", "exact", "normalize", List.of("trim", "strip_plus", "strip_leading_zeros"),
                "duplicates", "first"), DIAL);
        assertParity(cfg("match", "longest_prefix", "normalize", List.of("trim", "strip_plus"),
                "filter", "iso_country_code = 'GB'"), DIAL);
    }

    @Test
    void unicodeTrimLowerAndUpperAgree() throws Exception {
        assertParity(cfg("match", "exact", "normalize", List.of("trim", "lower")), DIAL);
        assertParity(cfg("match", "exact", "normalize", List.of("trim", "upper")), DIAL);
        assertParity(cfg("match", "longest_prefix", "normalize", List.of("trim")), DIAL);
    }

    @Test
    void aBuildLeavesNoScratchBehind() throws Exception {
        Path csv = dir.resolve("s.csv");
        Files.writeString(csv, DIAL);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        java.util.function.Supplier<java.util.Set<Path>> scratch = () -> {
            try (var l = Files.list(tmp)) {
                return l.filter(p -> p.getFileName().toString().startsWith("lookup_"))
                        .collect(java.util.stream.Collectors.toSet());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        var before = scratch.get();
        PointLookup.load(PipelineNode.of("j", "transform.join", cfg("match", "exact")), resolver(csv));
        var after = scratch.get();
        after.removeAll(before);
        assertEquals(java.util.Set.of(), after, "the build's scratch dir is removed");
    }

    @Test
    void aDuplicateKeyIsRefusedAndTheRowCapRefusesTheBuild() throws Exception {
        Path csv = dir.resolve("dup.csv");
        Files.writeString(csv, DIAL);
        Map<String, Object> refuse = new LinkedHashMap<>(cfg("match", "exact"));
        refuse.remove("duplicates");
        refuse.remove("order_by");
        var dup = assertThrows(IllegalArgumentException.class,
                () -> PointLookup.load(PipelineNode.of("j", "transform.join", refuse), resolver(csv)));
        assertTrue(dup.getMessage().contains("dial_digit_key=44"), dup.getMessage());

        System.setProperty(PointLookup.PROP_MAX_ROWS, "2");
        try {
            var cap = assertThrows(IllegalArgumentException.class,
                    () -> PointLookup.load(PipelineNode.of("j", "transform.join", cfg("match", "exact")), resolver(csv)));
            assertTrue(cap.getMessage().contains(PointLookup.PROP_MAX_ROWS), cap.getMessage());
        } finally {
            System.clearProperty(PointLookup.PROP_MAX_ROWS);
        }
    }

    @Test
    void rangeIsNotServedYet() throws Exception {
        Path csv = dir.resolve("r.csv");
        Files.writeString(csv, DIAL);
        var e = assertThrows(IllegalArgumentException.class, () -> PointLookup.load(PipelineNode.of("j", "transform.join",
                Map.of("reference", "r", "from", "rank", "on", "v", "match", "range")), resolver(csv)));
        assertTrue(e.getMessage().contains("range"), e.getMessage());
    }
}
