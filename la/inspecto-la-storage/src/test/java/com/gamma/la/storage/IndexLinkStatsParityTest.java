package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-INDEX-SCALE-MEASURE-1: the per-key distinct-neighbour count behind the manifest's degree statistics is now spelled
 * {@code DISTINCT (key, neighbour)} then {@code count(*)} per key; it used to be {@code count(DISTINCT neighbour)} per key, which
 * ran out of memory at a 2 GB cap over 10^8 edges. The two are the same number. This test keeps the earlier two statements
 * ({@link #REFERENCE_LINKS}, {@link #REFERENCE_DEGREE}) and the earlier reads of them, and checks that over a skewed corpus - parallel
 * links of several kinds, NULL kinds, reverse links, self links, and endpoint keys that fold many ids to one - the manifest of a real
 * FULL build holds exactly what they give.
 */
class IndexLinkStatsParityTest {

    private static final String DS = "parity";
    private static final IndexMapping M = new IndexMapping("s", "t", "k", "ts", null, null, List.of());

    /** The earlier links statement over the written {@code out}: {@code %1$s} / {@code %2$s} the normalised source / target key SQL, {@code %3$s} the table read. */
    private static final String REFERENCE_LINKS = "CREATE TEMP TABLE __ref_links AS SELECT DISTINCT a, b, kind FROM (SELECT %1$s AS a, %2$s AS b, kind FROM %3$s) l"
            + " WHERE a <> '' AND b <> '' AND a <> b";
    private static final String REFERENCE_DEGREE = "CREATE TEMP TABLE __ref_degree AS SELECT x, count(DISTINCT o) AS d FROM (SELECT a AS x, b AS o FROM __ref_links"
            + " UNION ALL SELECT b AS x, a AS o FROM __ref_links) e GROUP BY x";

    /** The earlier statement chain, for the earlier statements' own SQL, used by the memory bench as well. */
    static String referenceLinks(String srcKey, String dstKey, String table) {
        return String.format(REFERENCE_LINKS, srcKey, dstKey, table);
    }

    static String referenceDegree() {
        return REFERENCE_DEGREE;
    }

    private static IndexBuilder.EndpointKey type(String id, UnaryOperator<String> key) {
        return new IndexBuilder.EndpointKey(id, "n", key);
    }

    private static final UnaryOperator<String> IDENTITY = c -> c;
    /** Folds {@code n123} and {@code n129} to {@code n12}: many ids to one key, so parallel links and self links abound. */
    private static final UnaryOperator<String> FOLD = c -> "substr(" + c + ", 1, 3)";

    private static void check(Path tmp, IndexBuilder.EndpointKey src, IndexBuilder.EndpointKey dst) throws Exception {
        Path data = tmp.resolve("data");
        IndexNodesFoldTest.plantFile(data, "a.parquet", 0, 12000, 1500);
        IndexNodesFoldTest.plantFile(data, "b.parquet", 12000, 20000, 1500);
        List<String> files = List.of("a.parquet", "b.parquet");
        IndexBuilder.Result r = IndexBuilder.build(new IndexBuilder.Request(DS, M, IndexNodesFoldTest.relation(data, files), new IndexStore(tmp.resolve("idx"), DS, M.hash()),
                "fp", IndexBuilder.Options.defaults(), IndexNodesFoldTest.stamps(data, files), IndexBuilder.Mode.FULL, added -> IndexNodesFoldTest.relation(data, added),
                List.of(data), IndexPlan.DEFAULT_MAX_DELTAS, src, dst));
        IndexManifest m = r.manifest();

        String out = "read_parquet('" + IndexNodesFoldTest.sql(r.directory()) + "/out/*/*.parquet', hive_partitioning = false)";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute(referenceLinks(src == null ? "src" : src.keySql().apply("src"), dst == null ? "dst" : dst.keySql().apply("dst"), out));
            st.execute(REFERENCE_DEGREE);

            Map<String, Long> kinds = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT kind, count(*) AS n FROM __ref_links WHERE kind IS NOT NULL GROUP BY kind ORDER BY n DESC, kind")) {
                while (rs.next()) kinds.put(rs.getString(1), rs.getLong(2));
            }
            assertFalse(kinds.isEmpty(), "the corpus has kinds");
            assertEquals(kinds, m.kindEdges());
            assertEquals(List.copyOf(kinds.keySet()), List.copyOf(m.kindEdges().keySet()), "most links first");

            try (ResultSet rs = st.executeQuery("SELECT coalesce(quantile_disc(d, 0.5), 0), coalesce(quantile_disc(d, 0.9), 0), coalesce(quantile_disc(d, 0.99), 0),"
                    + " coalesce(max(d), 0), count(*), (SELECT count(DISTINCT a) FROM __ref_links), (SELECT count(DISTINCT b) FROM __ref_links) FROM __ref_degree")) {
                rs.next();
                assertEquals(new IndexManifest.Degree(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), m.degree());
                assertTrue(m.degree().max() > 1, "the corpus has keys with several neighbours");
                Map<String, Long> types = new LinkedHashMap<>();
                if (src != null && dst != null && src.type().equals(dst.type())) types.put(src.type(), rs.getLong(5));
                else {
                    if (src != null) types.put(src.type(), rs.getLong(6));
                    if (dst != null) types.put(dst.type(), rs.getLong(7));
                }
                assertEquals(types, m.typeNodes());
            }

            Map<Long, Long> histogram = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT d, count(*) FROM __ref_degree GROUP BY d ORDER BY d")) {
                while (rs.next()) histogram.put(rs.getLong(1), rs.getLong(2));
            }
            assertEquals(histogram, m.degreeHistogram());
            assertTrue(histogram.size() > 3, "a spread of degrees, not one bucket");
        }
        assertEquals(m, IndexBuilder.verify(r.directory()));
    }

    @Test
    void untypedEndpointsGiveTheEarlierStatementsNumbers(@TempDir Path tmp) throws Exception {
        check(tmp, null, null);
    }

    @Test
    void typedEndpointsOfOneTypeKeyedByTheRawIdGiveTheEarlierStatementsNumbers(@TempDir Path tmp) throws Exception {
        IndexBuilder.EndpointKey t = type("msisdn", IDENTITY);
        check(tmp, t, t);
    }

    @Test
    void endpointsOfOneTypeThatFoldManyIdsToOneKeyGiveTheEarlierStatementsNumbers(@TempDir Path tmp) throws Exception {
        IndexBuilder.EndpointKey t = type("msisdn", FOLD);
        check(tmp, t, t);
    }

    @Test
    void endpointsOfTwoTypesGiveTheEarlierStatementsNumbers(@TempDir Path tmp) throws Exception {
        check(tmp, type("msisdn", FOLD), type("imei", IDENTITY));
    }

    @Test
    void theCorpusHasWhatTheParityNeeds(@TempDir Path tmp) throws Exception {
        // guards the test's own premise: reverse links, self links, parallel links of several kinds and a NULL kind exist in the planted corpus
        Path data = tmp.resolve("data");
        IndexNodesFoldTest.plantFile(data, "a.parquet", 0, 20000, 1500);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            String rel = "read_parquet('" + IndexNodesFoldTest.sql(data) + "/a.parquet')";
            assertTrue(count(st, "SELECT count(*) FROM " + rel + " WHERE s = t") > 0, "self links");
            assertTrue(count(st, "SELECT count(*) FROM (SELECT DISTINCT x.s, x.t FROM " + rel + " x JOIN " + rel + " y ON x.s = y.t AND x.t = y.s WHERE x.s <> x.t)") > 0, "reverse links");
            assertTrue(count(st, "SELECT count(*) FROM (SELECT s, t FROM " + rel + " WHERE k IS NOT NULL GROUP BY s, t HAVING count(DISTINCT k) > 1)") > 0, "one pair, several kinds");
            assertTrue(count(st, "SELECT count(*) FROM " + rel + " WHERE k IS NULL") > 0, "NULL kinds");
        }
        assertTrue(Files.exists(data.resolve("a.parquet")));
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
