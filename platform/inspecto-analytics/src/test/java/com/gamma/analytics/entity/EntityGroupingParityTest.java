package com.gamma.analytics.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.similarity.SimilaritySql;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity for entity grouping (ANALYTICS-CORE-1 S2): {@link EntityGrouping#group} and
 * {@link EntityGroupingSql#run} give every record the same group id — and, with a similarity review, the same candidate
 * score, group and flag — on seeded corpora with accents, case and spacing variants, formatted phone numbers, blanks,
 * NULLs and keys outside the Basic Multilingual Plane (so the code-point order of group ids is exercised).
 */
class EntityGroupingParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;
    private static int table;

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    private static final String[] EMAILS = {"anna@example.com", "ANNA@example.com ", " anna@EXAMPLE.com", "bob@example.com",
            "bób@example.com", "carol@x.org", "", "   ", null, null};
    private static final String[] PHONES = {"+44 20 7946 0001", "442079460001", "4420-7946-0001", "0000", "n/a", "33 1 23",
            "33123", null, null};
    private static final String[] COUNTRIES = {"GB", "gb", " GB", "FR", "fr", null};
    private static final String[] NAMES = {"Anna Kowalska", "Anna Kowalski", "anna  kowalska", "José Müller", "Jose Muller",
            "Bob Stone", "Bobby Stone", "", null};
    private static final String[] KEY_PREFIX = {"k", "K", "é", "", "😀", "z", "a"};

    static List<EntityRecord> randomRecords(Random r, int n) {
        List<EntityRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> v = new HashMap<>();
            v.put("email", EMAILS[r.nextInt(EMAILS.length)]);
            v.put("phone", PHONES[r.nextInt(PHONES.length)]);
            v.put("country", COUNTRIES[r.nextInt(COUNTRIES.length)]);
            v.put("name", NAMES[r.nextInt(NAMES.length)]);
            out.add(new EntityRecord(KEY_PREFIX[r.nextInt(KEY_PREFIX.length)] + i, v));
        }
        return out;
    }

    static EntityGrouping.Spec spec(boolean blocking, SimilarityCandidate candidate) {
        List<KeyColumn> block = blocking ? List.of(new KeyColumn("country", Normalization.TEXT)) : List.of();
        return new EntityGrouping.Spec("id", block, List.of(MatchCondition.of(Normalization.TEXT, "email"),
                MatchCondition.of(Normalization.DIGITS, "phone"),
                new MatchCondition(List.of(new KeyColumn("name", Normalization.NONE), new KeyColumn("country", Normalization.NONE)))),
                candidate, 100_000, 100_000_000);
    }

    private static String load(List<EntityRecord> records) throws SQLException {
        String name = "people_" + (++table);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + name + " (id VARCHAR, email VARCHAR, phone VARCHAR, country VARCHAR, name VARCHAR)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + name + " VALUES (?, ?, ?, ?, ?)")) {
            for (EntityRecord rec : records) {
                ps.setString(1, rec.key());
                ps.setString(2, rec.values().get("email"));
                ps.setString(3, rec.values().get("phone"));
                ps.setString(4, rec.values().get("country"));
                ps.setString(5, rec.values().get("name"));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        return name;
    }

    /** key → "group|score|candidateGroup|review" (the last three only with a review). */
    private static Map<String, String> viaSql(EntityGrouping.Spec spec, List<EntityRecord> records) throws SQLException {
        String in = load(records), out = in + "_grouped";
        EntityGroupingSql.run(conn, spec, EntityGroupingSql.Columns.DEFAULT, in, out, in + "_x");
        Map<String, String> got = new HashMap<>();
        boolean review = spec.candidate() != null;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT id, resolution_group"
                + (review ? ", candidate_score, candidate_group, candidate_review" : "") + " FROM " + out)) {
            while (rs.next()) {
                String v = rs.getString(2);
                if (review) {
                    double s = rs.getDouble(3);
                    v += "|" + (rs.wasNull() ? null : s) + "|" + rs.getString(4) + "|" + rs.getBoolean(5);
                }
                assertNull(got.put(rs.getString(1), v), "one row per record");
            }
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM duckdb_tables() WHERE table_name LIKE '" + in + "_x%'")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "scratch tables are dropped");
        }
        return got;
    }

    private static Map<String, String> viaJvm(EntityGrouping.Spec spec, List<EntityRecord> records) {
        EntityGrouping.Result r = EntityGrouping.group(spec, records);
        Map<String, String> out = new HashMap<>();
        for (var e : r.groups().entrySet()) {
            String v = e.getValue();
            if (spec.candidate() != null) {
                EntityGrouping.Candidate c = r.candidates().get(e.getKey());
                v += "|" + c.score() + "|" + c.group() + "|" + c.review();
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    @Test
    void groupsAreTheSameInBothFormsWithAndWithoutBlocking() throws Exception {
        for (long seed : new long[] {1, 2, 20261017}) {
            List<EntityRecord> rs = randomRecords(new Random(seed), 600);
            for (boolean blocking : new boolean[] {false, true}) {
                EntityGrouping.Spec s = spec(blocking, null);
                Map<String, String> jvm = viaJvm(s, rs);
                assertEquals(jvm, viaSql(s, rs), "seed " + seed + ", blocking " + blocking);
                assertTrue(jvm.values().stream().distinct().count() < rs.size(), "the corpus must actually group");
            }
        }
    }

    @Test
    void candidatesAreTheSameInBothFormsForEveryMethod() throws Exception {
        // sparser links than the grouping corpus, so a block keeps several groups to compare across
        Random rnd = new Random(99);
        List<EntityRecord> rs = new ArrayList<>();
        for (EntityRecord rec : randomRecords(rnd, 300)) {
            Map<String, String> v = new HashMap<>(rec.values());
            if (rnd.nextInt(5) > 0) v.put("email", "u" + rs.size() + "@x");
            if (rnd.nextInt(5) > 0) v.put("phone", null);
            rs.add(new EntityRecord(rec.key(), v));
        }
        boolean scoredNotFlagged = false;
        for (SimilaritySql.Method m : SimilaritySql.Method.values())
            for (boolean normalize : new boolean[] {true, false}) {
                EntityGrouping.Spec s = spec(true, new SimilarityCandidate("name", m, normalize, 0.6));
                Map<String, String> jvm = viaJvm(s, rs);
                assertTrue(jvm.values().stream().anyMatch(v -> v.endsWith("|true")), m + " " + normalize + ": some candidate is flagged");
                scoredNotFlagged |= jvm.values().stream().anyMatch(v -> v.endsWith("|false") && !v.contains("|null|"));
                assertEquals(jvm, viaSql(s, rs), m + " normalize " + normalize);
            }
        assertTrue(scoredNotFlagged, "some candidate is scored but not flagged");
    }

    @Test
    void theGroupIdIsTheSmallestKeyByCodePointInBothForms() throws Exception {
        // U+E000 < U+1F600 by code point (and by UTF-8 byte), but its UTF-16 unit is larger than the emoji's high surrogate
        List<EntityRecord> rs = List.of(new EntityRecord("😀-1", Map.of("email", "same@x")),
                new EntityRecord("-2", Map.of("email", "same@x")), new EntityRecord("ﬁ-3", Map.of("email", "same@x")));
        EntityGrouping.Spec s = EntityGrouping.Spec.of("id", List.of(), List.of(MatchCondition.of(Normalization.TEXT, "email")));
        Map<String, String> jvm = viaJvm(s, rs);
        assertEquals("-2", jvm.get("😀-1"));
        assertEquals(jvm, viaSql(s, rs));
    }

    @Test
    void aLongChainSettlesToTheSmallestKey() throws Exception {
        // n000-n001 share an email, n001-n002 a phone, ... : one component whose diameter is the whole chain
        List<EntityRecord> rs = new ArrayList<>();
        for (int i = 299; i >= 0; i--) {
            Map<String, String> v = new HashMap<>();
            v.put("email", "e" + (i / 2) + "@x");
            v.put("phone", String.valueOf(1000 + (i + 1) / 2));
            rs.add(new EntityRecord(String.format("n%03d", i), v));
        }
        EntityGrouping.Spec s = new EntityGrouping.Spec("id", List.of(), List.of(MatchCondition.of(Normalization.TEXT, "email"),
                MatchCondition.of(Normalization.DIGITS, "phone")), null, 10, 1_000);
        Map<String, String> jvm = viaJvm(s, rs);
        assertEquals(1, jvm.values().stream().distinct().count());
        assertEquals("n000", jvm.get("n299"));
        assertEquals(jvm, viaSql(s, rs));
    }

    @Test
    void theSqlFormRefusesWithTheJvmFormsWordsAndWritesNothing() throws Exception {
        List<EntityRecord> rs = new ArrayList<>();
        for (int i = 0; i < 6; i++) rs.add(new EntityRecord("r" + i, Map.of("phone", "0000")));
        EntityGrouping.Spec s = new EntityGrouping.Spec("id", List.of(), List.of(MatchCondition.of(Normalization.DIGITS, "phone")),
                null, 5, 1_000);
        String jvm = assertThrows(IllegalStateException.class, () -> EntityGrouping.group(s, rs)).getMessage();
        String in = load(rs);
        String sql = assertThrows(IllegalStateException.class,
                () -> EntityGroupingSql.run(conn, s, EntityGroupingSql.Columns.DEFAULT, in, in + "_out", in + "_x")).getMessage();
        assertEquals(jvm, sql);
        try (Statement st = conn.createStatement(); ResultSet r = st.executeQuery(
                "SELECT count(*) FROM duckdb_tables() WHERE table_name LIKE '" + in + "_%'")) {
            r.next();
            assertEquals(0, r.getLong(1));
        }

        List<EntityRecord> dup = List.of(new EntityRecord("a", Map.of()), new EntityRecord("a", Map.of()));
        EntityGrouping.Spec ok = EntityGrouping.Spec.of("id", List.of(), List.of(MatchCondition.of(Normalization.TEXT, "email")));
        String in2 = load(dup);
        assertEquals(assertThrows(IllegalStateException.class, () -> EntityGrouping.group(ok, dup)).getMessage(),
                assertThrows(IllegalStateException.class, () -> EntityGroupingSql.run(conn, ok,
                        EntityGroupingSql.Columns.DEFAULT, in2, in2 + "_out", in2 + "_x")).getMessage());
    }

    @Test
    void aNumericKeyKeepsItsTypeAndTheSmallestIsByText() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE nums AS SELECT * FROM (VALUES (10, 'a@x'), (9, 'a@x'), (100, 'b@x')) t(id, email)");
        }
        EntityGrouping.Spec s = EntityGrouping.Spec.of("id", List.of(), List.of(MatchCondition.of(Normalization.TEXT, "email")));
        EntityGroupingSql.run(conn, s, EntityGroupingSql.Columns.DEFAULT, "nums", "nums_out", "nums_x");
        Map<Integer, Integer> got = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id, resolution_group, typeof(resolution_group) FROM nums_out ORDER BY id")) {
            while (rs.next()) {
                assertEquals("INTEGER", rs.getString(3));
                got.put(rs.getInt(1), rs.getInt(2));
            }
        }
        assertEquals(Map.of(9, 10, 10, 10, 100, 100), got, "'10' < '9' as text: the id is the smallest key TEXT");
    }
}
