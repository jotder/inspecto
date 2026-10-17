package com.gamma.analytics.similarity;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB 1.5.6 parity for the similarity kit (ANALYTICS-CORE-1 S1). What DuckDB's built-ins compute, as found:
 *
 * <ul>
 *   <li>{@code jaro_similarity} / {@code jaro_winkler_similarity} = {@link JaroWinkler#DUCKDB}: UTF-8 <b>bytes</b>,
 *       half-transpositions rounded down, p = 0.1, prefix up to 4, boost above 0.7; empty → 0. Bit for bit.</li>
 *   <li>{@code levenshtein} = {@link Similarity#levenshtein} over {@link TextUnit#UTF8_BYTE} (é ↔ e costs 2).</li>
 *   <li>{@code jaccard(s1, s2)} is <b>not</b> a token Jaccard: it is the Jaccard of the two strings' sets of UTF-8
 *       bytes, case-sensitive, and it RAISES on an empty argument. The kit's token Jaccard is built from list
 *       functions instead ({@link SimilaritySql.Method#TOKEN_JACCARD}).</li>
 *   <li>{@code strip_accents} + {@code lower} = {@link Similarity#normalise} except on code points newer than DuckDB's
 *       Unicode tables.</li>
 * </ul>
 */
class SimilarityDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static final String[] ALPHABET = {"a", "b", "c", "d", "e", "A", "é", "è", "ü", "ß", " ", "\t", "😀", "ж", "x"};

    private static List<String[]> corpus() {
        List<String[]> out = new ArrayList<>();
        String[][] hand = {{"martha", "marhta"}, {"dwayne", "duane"}, {"dixon", "dicksonx"}, {"abcdef", "bcadef"},
                {"abcxyzqq", "abcdefgh"}, {"", ""}, {"", "a"}, {"kitten", "sitting"}, {"é", "e"}, {"José Müller", "Jose Mueller"},
                {"acme trading llc", "acme  trading"}, {"😀ab", "😁ab"}, {"ab", "ba"}, {"a", "a"}};
        for (String[] h : hand) out.add(h);
        Random r = new Random(20261017);
        for (int k = 0; k < 3_000; k++) out.add(new String[] {random(r), random(r)});
        // near-duplicates: one edit apart, so the matching and transposition paths are exercised hard
        for (int k = 0; k < 1_000; k++) {
            String a = random(r);
            int[] cp = a.codePoints().toArray();   // swap whole code points: never split a surrogate pair
            if (cp.length > 1) { int i = r.nextInt(cp.length - 1); int c = cp[i]; cp[i] = cp[i + 1]; cp[i + 1] = c; }
            out.add(new String[] {a, new String(cp, 0, cp.length)});
        }
        return out;
    }

    private static String random(Random r) {
        int n = r.nextInt(14);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(ALPHABET[r.nextInt(ALPHABET.length)]);
        return b.toString();
    }

    @Test
    void everyMethodsSqlEqualsItsJvmTwinBitForBit() throws Exception {
        List<String[]> pairs = corpus();
        load(pairs);
        StringBuilder sql = new StringBuilder("SELECT i, jaro_similarity(a, b), jaro_winkler_similarity(a, b), levenshtein(a, b)");
        for (SimilaritySql.Method m : SimilaritySql.Method.values()) sql.append(", ").append(m.sql("a", "b"));
        sql.append(", ").append(SimilaritySql.Method.JARO_WINKLER.sql(SimilaritySql.normalise("a"), SimilaritySql.normalise("b")));
        sql.append(" FROM pairs ORDER BY i");
        int checked = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                String[] p = pairs.get(rs.getInt(1));
                String tag = "'" + p[0] + "' / '" + p[1] + "'";
                assertEquals(JaroWinkler.DUCKDB.jaro(p[0], p[1]), rs.getDouble(2), 0.0, tag + " jaro_similarity");
                assertEquals(JaroWinkler.DUCKDB.similarity(p[0], p[1]), rs.getDouble(3), 0.0, tag + " jaro_winkler_similarity");
                assertEquals(Similarity.levenshtein(p[0], p[1], TextUnit.UTF8_BYTE), rs.getLong(4), tag + " levenshtein");
                int col = 5;
                for (SimilaritySql.Method m : SimilaritySql.Method.values())
                    assertEquals(m.score(p[0], p[1]), rs.getDouble(col++), 0.0, tag + " " + m);
                assertEquals(SimilaritySql.Method.JARO_WINKLER.score(Similarity.normalise(p[0]), Similarity.normalise(p[1])),
                        rs.getDouble(col), 0.0, tag + " normalised jaro_winkler");
                checked++;
            }
        }
        assertEquals(pairs.size(), checked);
    }

    @Test
    void theStandardDefinitionDiffersFromDuckDbOnlyWhereStated() throws Exception {
        // ASCII and no odd transposition count: STANDARD (code points, h = x/2) and the built-in agree
        assertEquals(scalar("SELECT jaro_winkler_similarity('martha', 'marhta')"), Similarity.jaroWinkler("martha", "marhta"), 0.0);
        // an odd count: the built-in floors h
        assertNotEquals(scalar("SELECT jaro_similarity('abcdef', 'bcadef')"), Similarity.jaro("abcdef", "bcadef"));
        // a non-ASCII letter: the built-in counts bytes
        assertEquals(2.0, scalar("SELECT levenshtein('é', 'e')"));
        assertEquals(1, Similarity.levenshtein("é", "e"));
    }

    @Test
    void duckDbsJaccardIsAByteSetJaccardThatRaisesOnEmpty() throws Exception {
        for (String[] p : corpus()) {
            if (p[0].isEmpty() || p[1].isEmpty()) continue;
            double duck = scalar("SELECT jaccard(" + SqlIdent.sqlStr(p[0]) + ", " + SqlIdent.sqlStr(p[1]) + ")");
            assertEquals(Similarity.jaccard(bytes(p[0]), bytes(p[1])), duck, 0.0, p[0] + " / " + p[1]);
        }
        SQLException e = assertThrows(SQLException.class, () -> scalar("SELECT jaccard('', 'a')"));
        assertTrue(e.getMessage().contains("too short"), e.getMessage());
    }

    @Test
    void normaliseAgreesWithStripAccentsAndLowerExceptOnCodePointsNewerThanDuckDbsTables() throws Exception {
        List<String> differ = new ArrayList<>();
        int compared = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT r::INTEGER, " + SimilaritySql.normalise("chr(r::INTEGER)")
                     + " FROM range(1, 196608) t(r) WHERE r < 55296 OR r > 57343 ORDER BY r")) {
            while (rs.next()) {
                int cp = rs.getInt(1);
                String jvm = Similarity.normalise(new String(Character.toChars(cp)));
                compared++;
                if (!jvm.equals(rs.getString(2))) differ.add(Integer.toHexString(cp));
            }
        }
        assertEquals(196608 - 1 - 2048, compared);
        // every script a name or a free-text field is usually written in agrees exactly: Latin, Greek, Cyrillic,
        // Armenian, Hebrew, Arabic (to U+086F), Latin Extended Additional and Greek Extended
        for (String hex : differ) {
            int cp = Integer.parseInt(hex, 16);
            assertFalse(cp < 0x0870 || (cp >= 0x1E00 && cp <= 0x1FFF), "differs at U+" + hex);
        }
        // the rest are letters and marks added in recent Unicode versions (e.g. U+0897, U+1ACF…U+1ADC, U+A7CB…U+A7DC)
        assertTrue(differ.size() <= 200, differ.size() + " differ: " + differ);
        // a mixed sentence agrees exactly
        String text = "  Crème\tBRÛLÉE  İstanbul ΣΑΣ Йосиф Ångstrom\n";
        assertEquals(Similarity.normalise(text), scalarText("SELECT " + SimilaritySql.normalise(SqlIdent.sqlStr(text))));
    }

    @Test
    void nullOnEitherSideGivesNull() throws Exception {
        for (SimilaritySql.Method m : SimilaritySql.Method.values()) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT " + m.sql("NULL::VARCHAR", "'a'"))) {
                assertTrue(rs.next());
                rs.getDouble(1);
                assertTrue(rs.wasNull(), m + " of NULL");
            }
        }
    }

    private static Set<Integer> bytes(String s) {
        Set<Integer> out = new HashSet<>();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) out.add(b & 0xFF);
        return out;
    }

    private static void load(List<String[]> pairs) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE pairs (i INTEGER, a VARCHAR, b VARCHAR)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pairs VALUES (?, ?, ?)")) {
            for (int i = 0; i < pairs.size(); i++) {
                ps.setInt(1, i);
                ps.setString(2, pairs.get(i)[0]);
                ps.setString(3, pairs.get(i)[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static double scalar(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getDouble(1);
        }
    }

    private static String scalarText(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
