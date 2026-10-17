package com.gamma.pipeline.exec;

import com.gamma.analytics.entity.EntityGrouping;
import com.gamma.analytics.entity.EntityRecord;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.entity.link} (ANALYTICS-CORE-1 S2) — {@link RowShaper} over an embedded DuckDB input. Every row is
 * kept with its resolution group, equal to the Analytics Kernel's JVM form; caps and bad keys fail the node by name;
 * the similarity review never changes a group (D-M1).
 */
class RowShaperEntityLinkTest {

    private File db;
    private Connection conn;
    private int runs;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsel_");
        conn = DuckDbUtil.openConnection(db);
        exec("CREATE TABLE src AS SELECT * FROM (VALUES "
                + "(104, 'ANNA@example.com ', '+44 20 7946 0001', 'GB', 'Anna Kowalska'), "
                + "(102, 'anna@example.com', NULL, 'gb', 'Anna Kowalski'), "
                + "(107, NULL, '442079460001', 'GB', 'A. Kowalska'), "
                + "(101, 'bob@example.com', '0000', 'GB', 'Bob Stone'), "
                + "(109, '', '0000', 'FR', 'Bob Stone'), "
                + "(103, '   ', NULL, 'GB', 'Bobby Stone')) t(id, email, phone, country, full_name)");
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private String run(Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("n", "transform.entity.link", cfg), "src", "n_" + (++runs))
                .stream().filter(r -> r.rel().equals(PipelineRel.DATA)).findFirst().orElseThrow().table();
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void everyRowGetsTheKernelsGroupKeepingTheKeysType() throws Exception {
        Map<String, Object> c = cfg("key", "id", "match", List.of("email", "phone"), "digits", List.of("phone"));
        String out = run(c);
        assertEquals(List.of("101|101", "102|102", "103|103", "104|102", "107|102", "109|101"),
                strings("SELECT id || '|' || resolution_group FROM \"" + out + "\" ORDER BY id"));
        assertEquals(List.of("INTEGER"), strings("SELECT DISTINCT typeof(resolution_group) FROM \"" + out + "\""));
        assertEquals(List.of("id", "email", "phone", "country", "full_name", "resolution_group"),
                strings("SELECT column_name FROM (DESCRIBE \"" + out + "\")"));

        // the same groups as the kernel's JVM form over the same text
        List<EntityRecord> recs = new ArrayList<>();
        for (String row : strings("SELECT id || '|' || coalesce(email, '~null~') || '|' || coalesce(phone, '~null~') FROM src")) {
            String[] p = row.split("\\|", -1);
            Map<String, String> v = new HashMap<>();
            v.put("email", p[1].equals("~null~") ? null : p[1]);
            v.put("phone", p[2].equals("~null~") ? null : p[2]);
            recs.add(new EntityRecord(p[0], v));
        }
        Map<String, String> jvm = EntityGrouping.group(EntityLinkStep.parse(c).grouping(), recs).groups();
        for (String row : strings("SELECT id || '|' || resolution_group FROM \"" + out + "\"")) {
            String[] p = row.split("\\|");
            assertEquals(jvm.get(p[0]), p[1], row);
        }
    }

    @Test
    void blockingKeepsMatchesInTheBlockAndTheReviewFlagsWithoutJoining() throws Exception {
        Map<String, Object> base = cfg("key", "id", "match", List.of("email", "phone"), "digits", List.of("phone"),
                "blocking", List.of("country"));
        List<String> groups = strings("SELECT id || '|' || resolution_group FROM \"" + run(base) + "\" ORDER BY id");
        assertEquals(List.of("101|101", "102|102", "103|103", "104|102", "107|102", "109|109"), groups,
                "109 shares 101's phone but not its country");

        Map<String, Object> review = new LinkedHashMap<>(base);
        review.put("candidate_by", "full_name");
        review.put("candidate_threshold", 0.0);   // flag everything: the strongest pull toward merging
        String out = run(review);
        assertEquals(groups, strings("SELECT id || '|' || resolution_group FROM \"" + out + "\" ORDER BY id"),
                "a similarity review never moves a row between groups (D-M1)");
        assertEquals(List.of("101|true", "102|true", "103|true", "104|true", "107|true", "109|false"),
                strings("SELECT id || '|' || candidate_review FROM \"" + out + "\" ORDER BY id"));
        assertEquals(List.of("101"), strings("SELECT candidate_group FROM \"" + out + "\" WHERE id = 103"),
                "Bobby Stone's closest other group is Bob Stone's");
        assertEquals(List.of("true"), strings("SELECT candidate_score IS NULL AND candidate_group IS NULL FROM \""
                + out + "\" WHERE id = 109"), "alone in its block: nothing to review");
    }

    @Test
    void aPlaceholderBeyondTheCapFailsTheNodeAndARepeatedKeyToo() throws Exception {
        exec("INSERT INTO src VALUES (110, 'x@y', '0000', 'GB', 'Z'), (111, 'w@y', '0000', 'GB', 'Y')");
        var e = assertThrows(IllegalArgumentException.class, () -> run(cfg("key", "id", "match", List.of("phone"),
                "max_block_size", 3)));
        assertTrue(e.getMessage().startsWith("node 'n': transform.entity.link: a block holds 4 records, more than the cap of 3"),
                e.getMessage());

        exec("INSERT INTO src VALUES (101, 'again@x', NULL, 'GB', 'Dup')");
        var dup = assertThrows(IllegalArgumentException.class, () -> run(cfg("key", "id", "match", List.of("email"))));
        assertTrue(dup.getMessage().contains("'id' value(s) appear more than once"), dup.getMessage());
    }

    @Test
    void columnsAreCheckedAgainstTheInput() {
        var missing = assertThrows(IllegalArgumentException.class, () -> run(cfg("key", "id", "match", List.of("NOPE"))));
        assertTrue(missing.getMessage().contains("column 'NOPE' is not in the inbound data"), missing.getMessage());
        var collides = assertThrows(IllegalArgumentException.class, () -> run(cfg("key", "id", "match", List.of("email"),
                "group_column", "country")));
        assertTrue(collides.getMessage().contains("collides"), collides.getMessage());
    }

    @Test
    void theGrammarRefusesFuzzyMatchingByName() {
        var fuzzy = assertThrows(IllegalArgumentException.class,
                () -> EntityLinkStep.parse(cfg("key", "id", "match", List.of("full_name"), "threshold", 0.9)));
        assertTrue(fuzzy.getMessage().contains("similarity never joins records (D-M1)"), fuzzy.getMessage());
        var noBlock = assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id",
                "match", List.of("email"), "candidate_by", "full_name", "candidate_threshold", 0.9)));
        assertTrue(noBlock.getMessage().contains("set 'blocking' too"), noBlock.getMessage());
        var noThreshold = assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id",
                "match", List.of("email"), "blocking", "country", "candidate_by", "full_name")));
        assertTrue(noThreshold.getMessage().contains("needs 'candidate_threshold'"), noThreshold.getMessage());
        var unused = assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id",
                "match", List.of("email"), "digits", List.of("phone"))));
        assertTrue(unused.getMessage().contains("no 'blocking' or 'match' entry uses"), unused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id", "match", List.of("a+a"))));
        assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id")));
        assertThrows(IllegalArgumentException.class, () -> EntityLinkStep.parse(cfg("key", "id", "match", "email",
                "max_pairs", 0)));
        assertEquals(List.of("id", "email", "phone", "last_name"), EntityLinkStep.parse(cfg("key", "id",
                "match", List.of("email", "phone+last_name"))).inputColumns());
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
