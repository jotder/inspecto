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
    void theReferenceStampChangesWithItsFilesAndIsNullWhenNoneAreDeclared() throws Exception {
        Path csv = dir.resolve("stamp.csv");
        Files.writeString(csv, DIAL);
        String before = PointLookup.referenceStamp("r", resolver(csv));
        assertNotNull(before);
        assertEquals(before, PointLookup.referenceStamp("r", resolver(csv)), "unchanged files, same stamp");
        Files.writeString(csv, DIAL + "33,FR,France,0,10\n");
        assertNotEquals(before, PointLookup.referenceStamp("r", resolver(csv)), "a changed file changes the stamp");
        assertNull(PointLookup.referenceStamp("r", (c, r) -> "x"), "no declared files: nothing to watch");
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

        System.setProperty(PointLookup.PROP_MAX_BYTES, "300");   // two or three rows
        try {
            var cap = assertThrows(IllegalArgumentException.class,
                    () -> PointLookup.load(PipelineNode.of("j", "transform.join", cfg("match", "exact")), resolver(csv)));
            assertTrue(cap.getMessage().contains(PointLookup.PROP_MAX_BYTES), cap.getMessage());
        } finally {
            System.clearProperty(PointLookup.PROP_MAX_BYTES);
        }
    }

    // ── range and asof (LOOKUP-1 P3b): typed bounds, so the Reference is read with its types detected ──────────

    private RowShaper.ReferenceResolver typedResolver(Path csv) {
        return new RowShaper.ReferenceResolver() {
            @Override
            public String resolve(Connection c, String reference) throws SQLException {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE OR REPLACE VIEW tr AS SELECT * FROM read_csv('" + csv.toString().replace('\\', '/')
                            + "', header=true)");
                }
                return "tr";
            }

            @Override public List<Path> readFiles(String reference) { return List.of(csv); }
        };
    }

    /**
     * Runs {@code cfg} over typed input rows ({@code inputSql} creates table {@code tin (id, v, ts)}) through the batch
     * join and the point engine, which gets each row's {@code v} and {@code at} as text, and asserts every added column
     * equal.
     */
    private void assertTypedParity(Map<String, Object> cfg, String csvText, String inputSql) throws Exception {
        Path csv = dir.resolve("t" + System.nanoTime() + ".csv");
        Files.writeString(csv, csvText);
        RowShaper.ReferenceResolver refs = typedResolver(csv);
        PointLookup point = PointLookup.load(PipelineNode.of("j", "transform.join", cfg), refs);
        List<String> added = new ArrayList<>(point.test("x").mappings().get(0).columns().keySet());
        if (conn == null) conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("parity_", dir));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE tin AS " + inputSql);
        }
        String id = "p" + System.nanoTime();
        var rel = RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "tin", id, refs);
        String table = rel.stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
        int n = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                // the added columns as DuckDB's own text, the form the point engine holds (JDBC would print inf as Infinity)
                "SELECT id, " + added.stream().map(c -> "CAST(\"" + c + "\" AS VARCHAR) AS \"" + c + "\"")
                        .collect(java.util.stream.Collectors.joining(", "))
                        + ", CAST(v AS VARCHAR) AS __v, CAST(ts AS VARCHAR) AS __at FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) {
                Map<String, String> want = new LinkedHashMap<>();
                for (String c : added) want.put(c, rs.getString(c));
                Map<String, String> got = point.test(rs.getString("__v"), rs.getString("__at")).mappings().get(0).columns();
                assertEquals(want, got, "row " + rs.getInt("id") + " (v=" + rs.getString("__v") + ", at="
                        + rs.getString("__at") + ") under " + cfg);
                n++;
            }
        }
        assertTrue(n > 0, "the input had rows");
    }

    private static final String BANDS = """
            lo,hi,band,prio
            1,9,small,1
            10,99,mid,1
            100,,big,1
            ,0,negative,1
            """;

    private static final String BAND_INPUT = "SELECT * FROM (VALUES (1, -5, NULL), (2, 0, NULL), (3, 1, NULL),"
            + " (4, 9, NULL), (5, 10, NULL), (6, 50, NULL), (7, 100, NULL), (8, 100000, NULL), (9, NULL, NULL))"
            + " t(id, v, ts)";

    private static Map<String, Object> rangeCfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("reference", "r", "match", "range", "on", "v",
                "from", "lo", "to", "hi", "details", true, "default", List.of("band=NONE")));
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) m.remove((String) kv[i]);
            else m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aNumericRangeAgreesIncludingOpenBoundsAndTheDefault() throws Exception {
        assertTypedParity(rangeCfg(), BANDS, BAND_INPUT);
        // from: only — every two rows overlap, so the band is the one with the highest lower bound at or below v
        assertTypedParity(rangeCfg("to", null, "duplicates", "last", "order_by", "lo"),
                BANDS.replace(",0,negative", "-100,0,negative"), BAND_INPUT);
    }

    /** Found by verification (2026-11-24): a value is never rounded to the bound's type, and inf / huge values compare. */
    @Test
    void aFractionalOrHugeValueOverIntegerBoundsAndInfinityAgree() throws Exception {
        assertTypedParity(rangeCfg(), BANDS, "SELECT * FROM (VALUES (1, 9.50::DECIMAL(10,2), NULL), (2, 9.49::DECIMAL(10,2), NULL),"
                + " (3, 0.40::DECIMAL(10,2), NULL), (4, 99.5::DECIMAL(10,2), NULL), (5, 9.00::DECIMAL(10,2), NULL))"
                + " t(id, v, ts)");
        assertTypedParity(rangeCfg(), BANDS, "SELECT * FROM (VALUES (1, 1e19::DOUBLE, NULL), (2, -1e19::DOUBLE, NULL),"
                + " (3, 'inf'::DOUBLE, NULL), (4, 5.5::DOUBLE, NULL)) t(id, v, ts)");
        String doubles = "lo,hi,band,prio\n-1.5,0.5,low,1\n0.75,inf,pos,1\n";
        assertTypedParity(rangeCfg(), doubles, "SELECT * FROM (VALUES (1, 1e200::DOUBLE, NULL), (2, 'inf'::DOUBLE, NULL),"
                + " (3, 0.6::DOUBLE, NULL), (4, '-inf'::DOUBLE, NULL), (5, 0.0::DOUBLE, NULL)) t(id, v, ts)");
    }

    @Test
    void asofWithANumericFromColumnComparesTheTimeUnrounded() throws Exception {
        String numeric = "code,eff,rate\nA,10,1\nA,20,2\n";
        assertTypedParity(asofCfg(), numeric, "SELECT * FROM (VALUES (1, 'A', 19.6::DOUBLE), (2, 'A', 9.6::DOUBLE),"
                + " (3, 'A', 20.0::DOUBLE), (4, 'A', 10::DOUBLE)) t(id, v, ts)");
    }

    @Test
    void overlappingRangesPickTheSameRowAndRefuseTheSameWay() throws Exception {
        String overlap = BANDS + "5,20,overlap,2\n";
        assertTypedParity(rangeCfg("duplicates", "last", "order_by", "prio"), overlap, BAND_INPUT);
        assertTypedParity(rangeCfg("duplicates", "first", "order_by", "prio"), overlap, BAND_INPUT);
        Path csv = dir.resolve("ov.csv");
        Files.writeString(csv, overlap);
        var e = assertThrows(IllegalArgumentException.class,
                () -> PointLookup.load(PipelineNode.of("j", "transform.join", rangeCfg()), typedResolver(csv)));
        assertTrue(e.getMessage().contains("overlap"), e.getMessage());
    }

    @Test
    void aDateRangeAgrees() throws Exception {
        String periods = """
                start,finish,band,prio
                2026-01-01,2026-01-31,jan,1
                2026-02-01,2026-02-28,feb,1
                2026-03-01,,from-march,1
                """;
        assertTypedParity(rangeCfg("from", "start", "to", "finish"), periods, "SELECT * FROM (VALUES"
                + " (1, DATE '2025-12-31', NULL), (2, DATE '2026-01-01', NULL), (3, DATE '2026-01-31', NULL),"
                + " (4, DATE '2026-02-15', NULL), (5, DATE '2026-03-01', NULL), (6, DATE '2030-01-01', NULL))"
                + " t(id, v, ts)");
    }

    /** Found by verification (2026-11-24): a date on a midnight TIMESTAMP lower bound matches, as DuckDB widens it. */
    @Test
    void aDateOnAMidnightTimestampBoundAgrees() throws Exception {
        String periods = """
                start,finish,band,prio
                2026-01-01 00:00:00,2026-01-31 12:00:00,jan,1
                2026-02-01 00:00:00,,from-feb,1
                """;
        assertTypedParity(rangeCfg("from", "start", "to", "finish"), periods, "SELECT * FROM (VALUES"
                + " (1, DATE '2026-01-01', NULL), (2, DATE '2026-01-31', NULL), (3, DATE '2026-02-01', NULL),"
                + " (4, DATE '2025-12-31', NULL)) t(id, v, ts)");
    }

    private static final String RATES = """
            code,eff,rate
            A,2026-01-01,1
            A,2026-03-01,2
            A,,0
            B,2026-02-01,5
            """;

    private static final String RATE_INPUT = "SELECT * FROM (VALUES (1, 'A', DATE '2025-12-01'), (2, 'A', DATE '2026-01-01'),"
            + " (3, 'A', DATE '2026-02-15'), (4, 'A', DATE '2026-03-01'), (5, 'B', DATE '2026-01-01'),"
            + " (6, 'B', DATE '2026-12-31'), (7, 'B', NULL), (8, 'A', NULL), (9, 'C', DATE '2026-05-05'),"
            + " (10, ' a', DATE '2026-02-15'), (11, NULL, DATE '2026-02-15')) t(id, v, ts)";

    private static Map<String, Object> asofCfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("reference", "r", "match", "asof", "on", "v", "key", "code",
                "from", "eff", "at", "ts", "details", true, "columns", List.of("rate")));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void asofAgreesIncludingTheUndatedRowANullTimeAndNormalize() throws Exception {
        assertTypedParity(asofCfg(), RATES, RATE_INPUT);
        assertTypedParity(asofCfg("normalize", List.of("trim", "upper")), RATES, RATE_INPUT);
    }

    @Test
    void asofRefusesARepeatedKeyAndDateAndPicksTheSameRowWhenAskedTo() throws Exception {
        String dup = RATES + "A,2026-03-01,9\n";
        Path csv = dir.resolve("dup.csv");
        Files.writeString(csv, dup);
        var e = assertThrows(IllegalArgumentException.class,
                () -> PointLookup.load(PipelineNode.of("j", "transform.join", asofCfg()), typedResolver(csv)));
        assertTrue(e.getMessage().contains("code=A"), e.getMessage());
        assertTypedParity(asofCfg("duplicates", "last", "order_by", "rate"), dup, RATE_INPUT);
    }

    // ── fuzzy (LOOKUP-1 P4) ──────────────────────────────────────────────────────────────────────────────────

    private static final String ENTITIES = """
            entity_name,country,sector,rank
            Acme Corporation,US,Tech,1
            Acme Corp,US,Tech,2
            Beta Holdings,GB,Finance,3
            Café du Nord,FR,Food,4
            Globex Industries,US,Manufacturing,5
            """;

    private static Map<String, Object> fuzzyCfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("reference", "r", "match", "fuzzy", "on", "v",
                "key", "entity_name", "details", true, "columns", List.of("sector"), "default", List.of("sector=NONE")));
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] == null) m.remove((String) kv[i]);
            else m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void assertFuzzyParity(Map<String, Object> cfg, List<String> inputs) throws Exception {
        Path csv = dir.resolve("fz" + System.nanoTime() + ".csv");
        Files.writeString(csv, ENTITIES);
        RowShaper.ReferenceResolver refs = resolver(csv);
        PointLookup point = PointLookup.load(PipelineNode.of("j", "transform.join", cfg), refs);
        List<String> added = new ArrayList<>(point.test("x").mappings().get(0).columns().keySet());
        if (conn == null) conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("parity_", dir));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE fzin (id INTEGER, v VARCHAR)");
            for (int i = 0; i < inputs.size(); i++) {
                String val = inputs.get(i);
                st.execute("INSERT INTO fzin VALUES (" + i + ", " + (val == null ? "NULL" : "'" + val.replace("'", "''") + "'") + ")");
            }
        }
        String id = "pfz" + System.nanoTime();
        var rel = RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "fzin", id, refs);
        String table = rel.stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
        Map<Integer, List<Map<String, String>>> want = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id, " + added.stream().map(c -> "CAST(\"" + c + "\" AS VARCHAR) AS \"" + c + "\"")
                        .collect(java.util.stream.Collectors.joining(", "))
                        + " FROM \"" + table + "\" ORDER BY id, "
                        + (cfg.containsKey("details") && cfg.get("details") instanceof Map<?, ?> dm && dm.containsKey("match_score")
                                ? "CAST(\"" + dm.get("match_score") + "\" AS DOUBLE) DESC NULLS LAST, " : "")
                        + "\"" + (added.stream().filter(c -> c.endsWith("matched_key")).findFirst().orElse(added.get(0))) + "\" ASC")) {
            while (rs.next()) {
                Map<String, String> r = new LinkedHashMap<>();
                for (String c : added) r.put(c, rs.getString(c));
                want.computeIfAbsent(rs.getInt("id"), k -> new ArrayList<>()).add(r);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            String v = inputs.get(i);
            List<Map<String, String>> got = point.testTopK(v).stream()
                    .map(a -> a.mappings().get(0).columns()).toList();
            assertEquals(want.get(i), got, "fuzzy value '" + v + "' under " + cfg);
        }
    }

    @Test
    void fuzzyJaroWinklerLevenshteinJaroAndTokenJaccardAgree() throws Exception {
        List<String> inputs = new ArrayList<>(java.util.Arrays.asList(
                "Acme Corp", "acme corporaton", "Beta Holding", "Cafe du Nord", "Globex", "Totally Unknown", "", null));
        assertFuzzyParity(fuzzyCfg("method", "jaro_winkler", "threshold", 0.80,
                "normalize", List.of("trim", "lower")), inputs);
        assertFuzzyParity(fuzzyCfg("method", "jaro", "threshold", 0.78,
                "normalize", List.of("trim", "lower")), inputs);
        assertFuzzyParity(fuzzyCfg("method", "levenshtein", "threshold", 0.75,
                "normalize", List.of("trim", "lower"),
                "details", Map.of("matched_key", "v_matched_key", "match_kind", "v_match_kind", "match_score", "v_score")), inputs);
        assertFuzzyParity(fuzzyCfg("method", "token_jaccard", "threshold", 0.5,
                "normalize", List.of("trim", "lower")), inputs);
    }

    @Test
    void fuzzyWithBlockPrefixAndTopKAgree() throws Exception {
        List<String> inputs = List.of("Acme", "Bcme Corp", "Globex Ind", "Zeta");
        assertFuzzyParity(fuzzyCfg("method", "jaro_winkler", "threshold", 0.70, "block_prefix", 1,
                "normalize", List.of("trim", "lower")), inputs);
        assertFuzzyParity(fuzzyCfg("method", "jaro_winkler", "threshold", 0.75, "top_k", 2,
                "normalize", List.of("trim", "lower")), inputs);
    }

    @Test
    void fuzzyWithBlockColumnAgrees() throws Exception {
        Path csv = dir.resolve("fzb" + System.nanoTime() + ".csv");
        Files.writeString(csv, ENTITIES);
        RowShaper.ReferenceResolver refs = resolver(csv);
        Map<String, Object> cfg = fuzzyCfg("method", "jaro_winkler", "threshold", 0.75, "block", "country",
                "normalize", List.of("trim", "lower"));
        PointLookup point = PointLookup.load(PipelineNode.of("j", "transform.join", cfg), refs);
        assertThrows(IllegalArgumentException.class, () -> point.test("Acme Corp"));
        if (conn == null) conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("parity_", dir));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE fzb (id INTEGER, v VARCHAR, country VARCHAR)");
            st.execute("INSERT INTO fzb VALUES (1, 'Acme Corp', 'US'), (2, 'Acme Corp', 'GB'), (3, 'Beta Holding', 'GB')");
        }
        var rel = RowShaper.shape(conn, PipelineNode.of("pfzb", "transform.join", cfg), "fzb", "pfzb", refs);
        String table = rel.stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) {
                Map<String, String> expected = new LinkedHashMap<>();
                expected.put("v_sector", rs.getString("v_sector"));
                expected.put("v_matched_key", rs.getString("v_matched_key"));
                expected.put("v_match_kind", rs.getString("v_match_kind"));
                Map<String, String> got = point.test(rs.getString("v"), Map.of("country", rs.getString("country")))
                        .mappings().get(0).columns();
                assertEquals(expected, got, "row " + rs.getInt("id"));
            }
        }
    }
}
