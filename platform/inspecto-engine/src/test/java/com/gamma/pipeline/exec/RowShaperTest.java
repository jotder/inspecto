package com.gamma.pipeline.exec;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T10 — {@link RowShaper}: each row-shaping operator runs as SQL over an embedded-DuckDB input relation and
 * produces the expected named output relations + rows. Covers the predicate split (filter/validate), case
 * vs clone routing, dedup QUALIFY, split UNNEST, projection (map/select/derive), chain-fusion, and merge.
 */
class RowShaperTest {

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rs_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /** src(id INT, grp VARCHAR, amt INT): rows (1,a,150) (2,b,50) (3,a,200). */
    private void seedSrc() throws SQLException {
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1,'a',150),(2,'b',50),(3,'a',200)) t(id,grp,amt)");
    }

    private RelationByRel run(PipelineNode node) throws SQLException {
        return new RelationByRel(RowShaper.shape(conn, node, "src", node.id()));
    }

    @Test
    void filterSplitsKeptAndDropped() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("f", "transform.filter", Map.of("where", "amt >= 100")));
        assertEquals(List.of(1, 3), ids(out.table(PipelineRel.DATA), "id"));
        assertEquals(List.of(2), ids(out.table(PipelineRel.DROPPED), "id"));
    }

    // ── lookup (inline static map, 2026-09-06) ──────────────────────────────────

    @Test
    void lookupTranscodesInPlaceAndPassesUnmatchedThrough() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("l", "transform.lookup",
                Map.of("column", "grp", "mappings", List.of("a=Alpha"))));
        assertEquals(List.of("Alpha", "b", "Alpha"), strings(out.table(PipelineRel.DATA), "grp"),
                "ids 1 and 3 (grp a) are transcoded in place; id 2 (grp b) has no mapping and passes through");
        assertEquals(3, ids(out.table(PipelineRel.DATA), "id").size(), "a lookup never changes row counts");
    }

    @Test
    void lookupWritesATargetColumnAndAppliesTheDefault() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("l", "transform.lookup", Map.of(
                "column", "amt", "mappings", List.of("150=low", "200=high"), "default", "other", "target", "band")));
        assertEquals(List.of("low", "other", "high"), strings(out.table(PipelineRel.DATA), "band"),
                "a numeric column maps by its text; the unmatched row takes the default");
        assertEquals(List.of(150, 50, 200), ints(out.table(PipelineRel.DATA), "amt"), "the source column is untouched");
    }

    @Test
    void lookupLiteralsAreQuotedNotInterpolated() throws Exception {
        seedSrc();
        // a value carrying a quote and SQL: it must land as text, never run
        var out = run(PipelineNode.of("l", "transform.lookup", Map.of(
                "column", "grp", "mappings", List.of("a=O'Brien'); DROP TABLE src; --"), "target", "t")));
        assertEquals(List.of("O'Brien'); DROP TABLE src; --", "b", "O'Brien'); DROP TABLE src; --"),
                strings(out.table(PipelineRel.DATA), "t"));
        assertEquals(3, ids("src", "id").size(), "the source table is still there");
    }

    @Test
    void lookupRefusesAMissingColumnOrMappingAndAMalformedEntry() throws Exception {
        seedSrc();
        assertThrows(IllegalArgumentException.class,
                () -> run(PipelineNode.of("l", "transform.lookup", Map.of("mappings", List.of("a=b")))));
        assertThrows(IllegalArgumentException.class,
                () -> run(PipelineNode.of("l", "transform.lookup", Map.of("column", "grp"))));
        assertThrows(IllegalArgumentException.class,
                () -> run(PipelineNode.of("l", "transform.lookup", Map.of("column", "grp", "mappings", List.of("no-equals")))));
    }

    private List<String> strings(String table, String col) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private List<Integer> ints(String table, String col) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" ORDER BY id")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    @Test
    void validateSplitsValidAndInvalid_nullPredicateGoesInvalid() throws Exception {
        // grp='a' for ids 1,3; NULLIF makes id 2's predicate NULL -> must land in invalid, not data
        seedSrc();
        var out = run(PipelineNode.of("v", "transform.validate",
                Map.of("rule", "CASE WHEN grp='b' THEN NULL ELSE grp='a' END")));
        assertEquals(List.of(1, 3), ids(out.table(PipelineRel.DATA), "id"));
        assertEquals(List.of(2), ids(out.table(PipelineRel.INVALID), "id"));
    }

    @Test
    void routeCaseIsExclusiveWithDefault() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("r", "transform.route", Map.of(
                "mode", "case",
                "branches", List.of(Map.of("key", "agrp", "where", "grp='a'")),
                "default", "other")));
        assertEquals(List.of(1, 3), ids(out.table(PipelineRel.route("agrp")), "id"));
        assertEquals(List.of(2), ids(out.table(PipelineRel.route("other")), "id"));
    }

    @Test
    void routeCloneAllowsOverlap() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("r", "transform.route", Map.of(
                "mode", "clone",
                "branches", List.of(
                        Map.of("key", "big", "where", "amt >= 100"),
                        Map.of("key", "agrp", "where", "grp='a'")))));
        assertEquals(List.of(1, 3), ids(out.table(PipelineRel.route("big")), "id"));
        assertEquals(List.of(1, 3), ids(out.table(PipelineRel.route("agrp")), "id"));   // id1,3 appear on both
    }

    @Test
    void dedupKeepsFirstPerKeyByOrder() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("d", "transform.dedup",
                Map.of("keys", List.of("grp"), "order_by", "amt DESC")));
        // grp a -> id3 (amt200), grp b -> id2; the loser id1 is a duplicate
        assertEquals(List.of(2, 3), ids(out.table(PipelineRel.DATA), "id"));
        assertEquals(List.of(1), ids(out.table(PipelineRel.DUPLICATE), "id"));
    }

    @Test
    void splitUnnestsAListColumn() throws Exception {
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1, ['x','y']), (2, ['z'])) t(id, tags)");
        var out = run(PipelineNode.of("s", "transform.split", Map.of("column", "tags", "as", "tag")));
        assertEquals(3, count(out.table(PipelineRel.DATA)));
        assertEquals(List.of(1, 1, 2), ids(out.table(PipelineRel.DATA), "id"));
    }

    @Test
    void projectMapSelectDerive() throws Exception {
        seedSrc();
        var map = run(PipelineNode.of("m", "transform.sql",
                Map.of("columns", List.of(Map.of("name", "id10", "expr", "id*10")))));
        assertEquals(List.of("id10"), columns(map.table(PipelineRel.DATA)));

        var sel = run(PipelineNode.of("se", "transform.select", Map.of("columns", List.of("id", "amt"))));
        assertEquals(List.of("amt", "id"), columns(sel.table(PipelineRel.DATA)).stream().sorted().toList());

        var der = run(PipelineNode.of("de", "transform.derive",
                Map.of("columns", List.of(Map.of("name", "amt2", "expr", "amt*2")))));
        assertTrue(columns(der.table(PipelineRel.DATA)).containsAll(List.of("id", "grp", "amt", "amt2")));
    }

    // ── SQL transformer (one author SELECT over the typed input, aliased `input`) ────

    @Test
    void sqlExecutesOneSelectOverTheFixedInputAlias() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("x", "transform.sql",
                Map.of("sql", "SELECT id, amt * 2 AS amt2 FROM input WHERE grp = 'a'")));
        String t = out.table(PipelineRel.DATA);
        assertEquals(List.of("amt2", "id"), columns(t).stream().sorted().toList());
        assertEquals(List.of(1, 3), ids(t, "id"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT amt2 FROM \"" + t + "\" ORDER BY id")) {
            assertTrue(rs.next()); assertEquals(300, rs.getInt(1));
            assertTrue(rs.next()); assertEquals(400, rs.getInt(1));
            assertFalse(rs.next());
        }
    }

    @Test
    void sqlRefusesNonSelectAndMultiStatement_namingTheNode() throws Exception {
        seedSrc();
        IllegalArgumentException ddl = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("bad-node", "transform.sql", Map.of("sql", "DROP TABLE src")), "src", "bad"));
        assertTrue(ddl.getMessage().contains("bad-node"), ddl.getMessage());

        IllegalArgumentException multi = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("bad-node", "transform.sql",
                        Map.of("sql", "SELECT * FROM input; DROP TABLE src;")), "src", "bad"));
        assertTrue(multi.getMessage().contains("bad-node"), multi.getMessage());
    }

    /** Mirrors the sandboxed-agent-SQL test elsewhere: file/extension/system surfaces stay blocked. */
    @Test
    void sqlCannotReachOutsideTheInputRelation() throws Exception {
        seedSrc();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("esc", "transform.sql",
                        Map.of("sql", "SELECT * FROM read_csv('/etc/passwd')")), "src", "esc"));
        assertTrue(e.getMessage().contains("esc"), e.getMessage());
    }

    // ── profile (per-column statistics; catalog transform.profiler.inline) ─────

    /** Seeds a table with a NULL and a repeated value, so nulls and distinctness are actually exercised. */
    private void seedProfile() throws SQLException {
        sql("CREATE TABLE prof AS SELECT * FROM (VALUES (1,'a',10),(2,'a',NULL),(3,'b',30)) t(id,grp,amt)");
    }

    @Test
    void profileReportsOneRowPerColumnWithCountsAndBounds() throws Exception {
        seedProfile();
        var out = new RelationByRel(RowShaper.shape(
                conn, PipelineNode.of("pf", "transform.profile", Map.of()), "prof", "pf"));
        String t = out.table(PipelineRel.DATA);

        assertEquals(3, count(t), "one row per inbound column");
        assertEquals(List.of("column_name", "distinct_count", "max_value", "min_value", "null_count", "row_count"),
                columns(t).stream().sorted().toList());

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT row_count, null_count, distinct_count, min_value, max_value "
                     + "FROM \"" + t + "\" WHERE column_name = 'amt'")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1), "row_count counts rows, not non-nulls");
            assertEquals(1, rs.getInt(2), "COUNT(*) - COUNT(col) is the null count");
            assertEquals(2, rs.getInt(3), "COUNT(DISTINCT col) ignores NULL");
            assertEquals("10", rs.getString(4));
            assertEquals("30", rs.getString(5));
        }
    }

    /** ⚠ The repeated value must not be double-counted as distinct. */
    @Test
    void profileCountsDistinctValuesNotRows() throws Exception {
        seedProfile();
        var out = new RelationByRel(RowShaper.shape(
                conn, PipelineNode.of("pf", "transform.profile", Map.of()), "prof", "pf"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT distinct_count FROM \"" + out.table(PipelineRel.DATA)
                     + "\" WHERE column_name = 'grp'")) {
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1), "'a' twice and 'b' once is two distinct values");
        }
    }

    /** {@code columns:} restricts the profile; omitting it profiles everything. */
    @Test
    void profileHonoursAnExplicitColumnList() throws Exception {
        seedProfile();
        var out = new RelationByRel(RowShaper.shape(
                conn, PipelineNode.of("pf", "transform.profile", Map.of("columns", List.of("grp"))), "prof", "pf"));
        assertEquals(1, count(out.table(PipelineRel.DATA)));
    }

    /**
     * ⛔ A typo'd column is REFUSED, not skipped. Silently profiling the columns it recognised would
     * hand back a clean-looking profile of a mistake — the failure mode worth a loud refusal.
     */
    @Test
    void profileRefusesAnUnknownColumn() throws Exception {
        seedProfile();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                RowShaper.shape(conn, PipelineNode.of("pf", "transform.profile",
                        Map.of("columns", List.of("nope"))), "prof", "pf"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    // ── summarize (group-by rollup through MeasureCompiler) ─────────────────────

    @Test
    void summarizeRollsUpByGroupWithMeasures() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("sm", "transform.summarize",
                Map.of("group_by", List.of("grp"), "measures", List.of("count", "sum(amt)"))));
        String t = out.table(PipelineRel.DATA);
        assertEquals(2, count(t));   // two groups: a, b
        // result columns carry MeasureCompiler's stable measure ids (count, sum_amt)
        assertEquals(List.of("count", "grp", "sum_amt"), columns(t).stream().sorted().toList());
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT grp, \"count\", sum_amt FROM \"" + t + "\" ORDER BY grp")) {
            assertTrue(rs.next());
            assertEquals("a", rs.getString(1)); assertEquals(2, rs.getInt(2)); assertEquals(350, rs.getInt(3));
            assertTrue(rs.next());
            assertEquals("b", rs.getString(1)); assertEquals(1, rs.getInt(2)); assertEquals(50, rs.getInt(3));
            assertFalse(rs.next());
        }
    }

    @Test
    void summarizeWithoutGroupByIsAGlobalRollup() throws Exception {
        seedSrc();
        var out = run(PipelineNode.of("sm", "transform.summarize",
                Map.of("measures", List.of("sum(amt)"))));
        String t = out.table(PipelineRel.DATA);
        assertEquals(1, count(t));
        assertEquals(List.of(400), ids(t, "sum_amt"));
    }

    /** The measures grammar is MeasureCompiler's, not a local one — bad input refuses, never mis-parses. */
    @Test
    void summarizeRefusesMissingMalformedAndUnknownMeasures() throws Exception {
        seedSrc();
        // no measures at all
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("sm", "transform.summarize", Map.of("group_by", List.of("grp"))), "src", "sm"));
        // outside the count | agg(field) shorthand
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("sm", "transform.summarize", Map.of("measures", List.of("sum(amt"))), "src", "sm"));
        // well-formed but an aggregation MeasureCompiler does not know
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("sm", "transform.summarize", Map.of("measures", List.of("mode(amt)"))), "src", "sm"));
    }

    // ── join (reference LEFT JOIN through the resolver seam) ────────────────────

    /** ref(grp VARCHAR, label VARCHAR): only grp 'a' is present, so id 2 (grp 'b') must survive with NULL. */
    private RowShaper.ReferenceResolver seedRef() throws SQLException {
        sql("CREATE TABLE refdim AS SELECT * FROM (VALUES ('a','Alpha')) t(grp, label)");
        return (c, name) -> {
            assertEquals("reference/groups", name);   // the node's cfg value reaches the resolver verbatim
            return "refdim";
        };
    }

    @Test
    void joinIsALeftJoin_unmatchedRowsSurviveWithNulls() throws Exception {
        seedSrc();
        var resolver = seedRef();
        var out = new RelationByRel(RowShaper.shape(conn,
                PipelineNode.of("j", "transform.join",
                        Map.of("reference", "reference/groups", "on", "grp")),
                "src", "j", resolver));
        String t = out.table(PipelineRel.DATA);
        assertEquals(3, count(t));   // LEFT JOIN: nothing dropped
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, label FROM \"" + t + "\" ORDER BY id")) {
            assertTrue(rs.next()); assertEquals(1, rs.getInt(1)); assertEquals("Alpha", rs.getString(2));
            assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); assertNull(rs.getString(2));
            assertTrue(rs.next()); assertEquals(3, rs.getInt(1)); assertEquals("Alpha", rs.getString(2));
            assertFalse(rs.next());
        }
    }

    /** The two lowering paths disagree on {@code on}'s shape (scalar vs list) — both must work. */
    @Test
    void joinAcceptsOnAsAListToo() throws Exception {
        seedSrc();
        var out = new RelationByRel(RowShaper.shape(conn,
                PipelineNode.of("j", "transform.join",
                        Map.of("reference", "reference/groups", "on", List.of("grp"))),
                "src", "j", seedRef()));
        assertEquals(3, count(out.table(PipelineRel.DATA)));
    }

    /** Without reference context the 4-arg shape must refuse — never resolve wrongly or no-op. */
    @Test
    void joinWithoutAResolverRefuses() {
        var node = PipelineNode.of("j", "transform.join",
                Map.of("reference", "reference/groups", "on", "grp"));
        var e = assertThrows(IllegalStateException.class, () -> RowShaper.shape(conn, node, "src", "j"));
        assertTrue(e.getMessage().contains("reference/groups"));
    }

    @Test
    void joinRefusesMissingReferenceAndMissingOn() throws Exception {
        seedSrc();
        var resolver = seedRef();
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("j", "transform.join", Map.of("on", "grp")), "src", "j", resolver));
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("j", "transform.join", Map.of("reference", "reference/groups")), "src", "j", resolver));
    }

    // ── join match: longest_prefix (LOOKUP-1 P1) ────────────────────────────────

    /** Runs a prefix join of {@code calls(id, msisdn)} against {@code prefixes} and returns id → operator. */
    private int prefixJoins;

    private Map<Integer, String> prefixJoin(Map<String, Object> cfg) throws SQLException {
        String id = "j" + (++prefixJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg),
                "calls", id, (c, name) -> "prefixes"));
        Map<Integer, String> byId = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, operator FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            while (rs.next()) byId.put(rs.getInt(1), rs.getString(2));
        }
        return byId;
    }

    @Test
    void longestPrefixPicksTheLongestMatchingKeyAndKeepsUnmatchedRows() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'447700900123'),(2,'447911000000'),"
                + "(3,'33612345678'),(4,'9999'),(5,NULL)) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK'),('4477','UK mobile A'),"
                + "('447700','UK test range'),('33','France')) t(msisdn, operator)");
        Map<Integer, String> got = prefixJoin(Map.of("reference", "reference/prefixes", "on", "msisdn",
                "match", "longest_prefix"));
        var expected = new java.util.TreeMap<Integer, String>();
        expected.put(1, "UK test range");   // 447700 beats 4477 and 44
        expected.put(2, "UK");              // only 44 is a prefix of 4479…
        expected.put(3, "France");
        expected.put(4, null);              // no prefix: survives with NULL
        expected.put(5, null);              // NULL key: survives with NULL
        assertEquals(expected, got);
    }

    /** The prefix join keeps the input's key column and adds only the Reference's non-key columns. */
    @Test
    void longestPrefixOutputIsTheInputColumnsThenTheReferenceNonKeyColumns() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'4477')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK')) t(msisdn, operator)");
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of("j", "transform.join",
                Map.of("reference", "r", "on", "msisdn", "match", "longest_prefix")), "calls", "j",
                (c, name) -> "prefixes"));
        List<String> cols = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) cols.add(rs.getMetaData().getColumnName(i));
            assertTrue(rs.next()); assertEquals("4477", rs.getString("msisdn"));
        }
        assertEquals(List.of("id", "msisdn", "operator"), cols);
    }

    /** Earlier on-keys are equal-matched (a tiered key: country, then the number's prefix); a numeric key is read as text. */
    @Test
    void longestPrefixEqualsTheLeadingKeysAndReadsANumericKeyAsText() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'GB',447700123),(2,'FR',447700123)) t(id, country, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('GB',4477,'UK mobile'),('FR',44,'Roamer'))"
                + " t(country, msisdn, operator)");
        Map<Integer, String> got = prefixJoin(Map.of("reference", "r", "on", List.of("country", "msisdn"),
                "match", "longest_prefix"));
        assertEquals("UK mobile", got.get(1));
        assertEquals("Roamer", got.get(2));
    }

    /** An empty key is a catch-all: the default when nothing longer matches. */
    @Test
    void longestPrefixTreatsAnEmptyKeyAsTheCatchAll() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'447'),(2,'999')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('','Other'),('44','UK')) t(msisdn, operator)");
        Map<Integer, String> got = prefixJoin(Map.of("reference", "r", "on", "msisdn", "match", "longest_prefix"));
        assertEquals("UK", got.get(1));
        assertEquals("Other", got.get(2));
    }

    @Test
    void longestPrefixRefusesAReferenceWithTwoRowsForOneKey() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'447')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK'),('44','Also UK')) t(msisdn, operator)");
        var e = assertThrows(IllegalArgumentException.class,
                () -> prefixJoin(Map.of("reference", "r", "on", "msisdn", "match", "longest_prefix")));
        assertTrue(e.getMessage().contains("msisdn=44"), e.getMessage());
    }

    /** NULL keys match nothing, so two of them are not a duplicate — the join runs. */
    @Test
    void longestPrefixAllowsSeveralNullKeysInTheReference() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'447')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK'),(NULL,'x'),(NULL,'y')) t(msisdn, operator)");
        assertEquals("UK", prefixJoin(Map.of("reference", "r", "on", "msisdn", "match", "longest_prefix")).get(1));
    }

    /** match: exact (and an absent match) is the unchanged equi-join: a prefix is NOT a match. */
    @Test
    void exactMatchIsTheDefaultAndDoesNotMatchPrefixes() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'44'),(2,'447')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK')) t(msisdn, operator)");
        for (Map<String, Object> cfg : List.<Map<String, Object>>of(
                Map.of("reference", "r", "on", "msisdn"),
                Map.of("reference", "r", "on", "msisdn", "match", "exact"))) {
            Map<Integer, String> got = prefixJoin(cfg);
            assertEquals("UK", got.get(1));
            assertNull(got.get(2));
        }
    }

    @Test
    void anUnknownMatchIsRefused() throws Exception {
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1,'44')) t(id, msisdn)");
        sql("CREATE TABLE prefixes AS SELECT * FROM (VALUES ('44','UK')) t(msisdn, operator)");
        var e = assertThrows(IllegalArgumentException.class,
                () -> prefixJoin(Map.of("reference", "r", "on", "msisdn", "match", "fuzzy")));
        assertTrue(e.getMessage().contains("fuzzy"), e.getMessage());
    }

    // ── join keyed form: several numbers against one dialling-code table (LOOKUP-1 P1b) ──
    // A synthetic table shaped like a telco dial-digit dimension: key, country, operator, an exact-only flag.

    private void seedDialDigits() throws SQLException {
        sql("CREATE TABLE dd AS SELECT * FROM (VALUES"
                + " ('249', 'SD', 'Sudan', '0', 10),"
                + " ('24991', 'SD', 'Operator A', '0', 11),"
                + " ('2499123', 'SD', 'Operator A Toll Free', '1', 12),"   // exact-only: a whole number
                + " ('44', 'GB', 'United Kingdom', '0', 13)"
                + ") t(dial_digit_key, iso_country_code, provider_desc, dm_ind, nop_id_key)");
        sql("CREATE TABLE cdr AS SELECT * FROM (VALUES"
                + " (1, '249915555555', '2499123', '447700900123'),"
                + " (2, '249112345678', '249912300000', NULL),"
                + " (3, '999', '24991', '33612345678')"
                + ") t(id, served_msisdn, other_msisdn, third_party_msisdn)");
    }

    private Map<String, Object> keyedCfg(Object... extra) {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(Map.of("reference", "r", "key", "dial_digit_key",
                "match", "longest_prefix",
                "on", List.of("served_msisdn", "other_msisdn", "third_party_msisdn"),
                "columns", List.of("dial_digit_key", "provider_desc")));
        for (int i = 0; i < extra.length; i += 2) cfg.put((String) extra[i], extra[i + 1]);
        return cfg;
    }

    private int keyedJoins;

    /** Runs a keyed join of cdr against dd; returns each row as id → (column → value). */
    private Map<Integer, Map<String, String>> keyedJoin(Map<String, Object> cfg) throws SQLException {
        String id = "k" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "cdr", id,
                (c, name) -> "dd"));
        Map<Integer, Map<String, String>> rows = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            while (rs.next()) {
                Map<String, String> r = new java.util.LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                    r.put(rs.getMetaData().getColumnName(i), rs.getString(i));
                rows.put(rs.getInt("id"), r);
            }
        }
        return rows;
    }

    @Test
    void keyedJoinLooksUpEachNumberColumnOnItsOwnAndNamesTheResultsByColumn() throws Exception {
        seedDialDigits();
        var rows = keyedJoin(keyedCfg());
        assertEquals(3, rows.size(), "a LEFT join: no call record dropped or repeated");
        assertEquals(List.of("id", "served_msisdn", "other_msisdn", "third_party_msisdn",
                        "served_msisdn_dial_digit_key", "served_msisdn_provider_desc",
                        "other_msisdn_dial_digit_key", "other_msisdn_provider_desc",
                        "third_party_msisdn_dial_digit_key", "third_party_msisdn_provider_desc"),
                List.copyOf(rows.get(1).keySet()));
        Map<String, String> r1 = rows.get(1);
        assertEquals("24991", r1.get("served_msisdn_dial_digit_key"), "the key column returns the matched prefix");
        assertEquals("Operator A", r1.get("served_msisdn_provider_desc"));
        assertEquals("2499123", r1.get("other_msisdn_dial_digit_key"), "exact value matches an exact-only key too");
        assertEquals("44", r1.get("third_party_msisdn_dial_digit_key"));
        assertEquals("249", rows.get(2).get("served_msisdn_dial_digit_key"), "2491… falls back to the country code");
        assertNull(rows.get(2).get("third_party_msisdn_provider_desc"), "a NULL number matches nothing");
        assertNull(rows.get(3).get("served_msisdn_dial_digit_key"), "no prefix of 999 is in the table");
        assertNull(rows.get(3).get("third_party_msisdn_dial_digit_key"));
    }

    /** Without exact_when the toll-free row is an ordinary prefix; with it, it matches only the whole number. */
    @Test
    void exactWhenRowsMatchOnlyTheWholeNumber() throws Exception {
        seedDialDigits();
        assertEquals("2499123", keyedJoin(keyedCfg()).get(2).get("other_msisdn_dial_digit_key"),
                "without exact_when, 2499123 is the longest prefix of 249912300000");
        var rows = keyedJoin(keyedCfg("exact_when", "dm_ind = '1'"));
        assertEquals("24991", rows.get(2).get("other_msisdn_dial_digit_key"),
                "an exact-only key that is only a prefix is skipped for the next longest");
        assertEquals("2499123", rows.get(1).get("other_msisdn_dial_digit_key"), "…and still matches its whole number");
    }

    @Test
    void defaultFillsUnmatchedNumbersAndAcceptsAListOrAMap() throws Exception {
        seedDialDigits();
        for (Object dflt : List.of(List.of("dial_digit_key=-99", "provider_desc=Unknown"),
                Map.of("dial_digit_key", -99, "provider_desc", "Unknown"))) {
            var r3 = keyedJoin(keyedCfg("default", dflt)).get(3);
            assertEquals("-99", r3.get("served_msisdn_dial_digit_key"));
            assertEquals("Unknown", r3.get("served_msisdn_provider_desc"));
            assertEquals("24991", r3.get("other_msisdn_dial_digit_key"), "a match is never replaced by the default");
        }
    }

    @Test
    void asNamesTheReturnedColumnsAndAClashIsRefused() throws Exception {
        seedDialDigits();
        var r1 = keyedJoin(keyedCfg("as", "{on}_dd_")).get(1);
        assertEquals("Operator A", r1.get("served_msisdn_dd_provider_desc"));
        var e = assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("as", "")));
        assertTrue(e.getMessage().contains("{on}"), e.getMessage());
        sql("ALTER TABLE cdr ADD COLUMN iso_country_code VARCHAR");   // the rows already have a returned name
        var clash = assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg(
                "on", List.of("served_msisdn"), "as", "", "columns", List.of("iso_country_code"))));
        assertTrue(clash.getMessage().contains("'iso_country_code' clashes"), clash.getMessage());
    }

    @Test
    void aRepeatedKeyIsRefusedUnlessDuplicatesSaysWhichRowToKeep() throws Exception {
        seedDialDigits();
        sql("INSERT INTO dd VALUES ('24991', 'SD', 'Operator A (new)', '0', 99)");
        var e = assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg()));
        assertTrue(e.getMessage().contains("dial_digit_key=24991"), e.getMessage());
        assertEquals("Operator A (new)", keyedJoin(keyedCfg("duplicates", "last", "order_by", "nop_id_key"))
                .get(1).get("served_msisdn_provider_desc"));
        assertEquals("Operator A", keyedJoin(keyedCfg("duplicates", "first", "order_by", "nop_id_key"))
                .get(1).get("served_msisdn_provider_desc"));
        assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("duplicates", "last")),
                "keeping one of a repeat needs order_by");
    }

    @Test
    void keyedExactMatchNeedsTheWholeValue() throws Exception {
        seedDialDigits();
        var rows = keyedJoin(keyedCfg("match", "exact"));
        assertEquals("2499123", rows.get(1).get("other_msisdn_dial_digit_key"));
        assertNull(rows.get(1).get("served_msisdn_dial_digit_key"), "exact: a prefix is not a match");
        assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("match", "exact",
                "exact_when", "dm_ind = '1'")), "exact_when is a longest_prefix setting");
    }

    @Test
    void keyedSettingsWithoutKeyOrNamingMissingColumnsAreRefused() throws Exception {
        seedDialDigits();
        Map<String, Object> noKey = keyedCfg();
        noKey.remove("key");
        noKey.put("on", "served_msisdn");
        var e = assertThrows(IllegalArgumentException.class, () -> keyedJoin(noKey));
        assertTrue(e.getMessage().contains("needs key"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("key", "nope")));
        assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("columns", List.of("nope"))));
        assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("on", List.of("nope"))));
    }

    // ── join match: range (LOOKUP-1 P1c) ─────────────────────────────────────────
    // A synthetic rate table shaped like a telco service-range dimension: a kind, a rate, a validity period.

    private void seedRates() throws SQLException {
        sql("CREATE TABLE rates AS SELECT * FROM (VALUES"
                + " ('SDR', '610.5', DATE '2020-01-01', DATE '2022-01-31'),"
                + " ('SDR', '612.7', DATE '2022-02-01', DATE '2022-02-28'),"
                + " ('TAX', '1.41',  DATE '2021-12-20', NULL),"                  // another kind, open-ended
                + " ('SDR', '812.4', DATE '2026-08-01', NULL)"                    // the current rate, open-ended
                + ") t(lookup_key, lookup_output, valid_from, valid_to)");
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES"
                + " (1, DATE '2021-06-15'), (2, DATE '2022-02-01'), (3, DATE '2022-02-28'),"
                + " (4, DATE '2023-05-01'), (5, DATE '2027-01-01'), (6, NULL)"
                + ") t(id, call_date)");
    }

    private Map<Integer, String> rateJoin(Map<String, Object> extra) throws SQLException {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(Map.of("reference", "r", "match", "range",
                "from", "valid_from", "to", "valid_to", "on", "call_date",
                "columns", List.of("lookup_output"), "as", "sdr_"));
        cfg.putAll(extra);
        String id = "r" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "calls", id,
                (c, name) -> "rates"));
        Map<Integer, String> byId = new java.util.TreeMap<>();
        lastRangeRows = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, sdr_lookup_output FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            while (rs.next()) { byId.put(rs.getInt(1), rs.getString(2)); lastRangeRows++; }
        }
        return byId;
    }

    /** Rows the last {@link #rateJoin} wrote — the map above keys by id, so it cannot see a repeated row. */
    private int lastRangeRows;

    @Test
    void aTimestampOnARangesLastDayMatchesAndATextValueMatchesNothing() throws Exception {
        seedRates();
        sql("CREATE OR REPLACE TABLE calls AS SELECT * FROM (VALUES"
                + " (1, TIMESTAMP '2022-02-28 10:30:00'), (2, TIMESTAMP '2022-02-01 00:00:00'),"
                + " (3, TIMESTAMP '2022-03-01 00:00:01')) t(id, call_date)");
        Map<Integer, String> got = rateJoin(Map.of("filter", "lookup_key = 'SDR'"));
        assertEquals("612.7", got.get(1), "10:30 on the last day of a DATE range is inside it");
        assertEquals("612.7", got.get(2));
        assertNull(got.get(3), "the next day is outside");
        sql("CREATE OR REPLACE TABLE calls AS SELECT * FROM (VALUES (1, 'not a date'), (2, '2021-06-15')) t(id, call_date)");
        Map<Integer, String> text = rateJoin(Map.of("filter", "lookup_key = 'SDR'"));
        assertNull(text.get(1), "a value that is no date matches nothing — no raw error");
        assertEquals("610.5", text.get(2), "an ISO date in text still reads as a date");
    }

    @Test
    void rangesThatTouchOnOneDayOverlapBecauseBoundsAreInclusive() throws Exception {
        sql("CREATE TABLE rates AS SELECT * FROM (VALUES ('A', DATE '2022-01-01', DATE '2022-01-31'),"
                + " ('B', DATE '2022-01-31', DATE '2022-02-28')) t(lookup_output, valid_from, valid_to)");
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1, DATE '2022-01-31')) t(id, call_date)");
        var e = assertThrows(IllegalArgumentException.class, () -> rateJoin(Map.of()));
        assertTrue(e.getMessage().contains("overlap") && e.getMessage().contains("2022-01-31"), e.getMessage());
        // the same pair in the other row order exercises the other side of the symmetric check
        sql("CREATE OR REPLACE TABLE rates AS SELECT * FROM (VALUES ('B', DATE '2022-01-31', DATE '2022-02-28'),"
                + " ('A', DATE '2022-01-01', DATE '2022-01-31')) t(lookup_output, valid_from, valid_to)");
        assertThrows(IllegalArgumentException.class, () -> rateJoin(Map.of()), "touching, listed later-first");
        sql("UPDATE rates SET valid_from = DATE '2022-02-01' WHERE lookup_output = 'B'");
        assertEquals("A", rateJoin(Map.of()).get(1), "end and next start on consecutive days do not overlap");
    }

    @Test
    void filterLimitsTheReferenceRowsInExactAndPrefixModesToo() throws Exception {
        sql("CREATE TABLE codes AS SELECT * FROM (VALUES ('44', 'UK', 'old'), ('44', 'United Kingdom', 'new'),"
                + " ('4477', 'UK mobile', 'old')) t(code, label, era)");
        sql("CREATE TABLE nums AS SELECT * FROM (VALUES (1, '44'), (2, '447700')) t(id, num)");
        for (String match : List.of("exact", "longest_prefix")) {
            Map<String, Object> cfg = Map.of("reference", "r", "key", "code", "match", match, "on", "num",
                    "columns", List.of("label"), "as", "c_", "filter", "era = 'new'");
            String id = "f" + (++keyedJoins);
            var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "nums", id,
                    (c, name) -> "codes"));
            Map<Integer, String> got = new java.util.TreeMap<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, c_label FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
                while (rs.next()) got.put(rs.getInt(1), rs.getString(2));
            }
            // without the filter 44 is a repeated key (refused); with it only the 'new' row takes part
            assertEquals("United Kingdom", got.get(1), match);
            assertEquals("longest_prefix".equals(match) ? "United Kingdom" : null, got.get(2),
                    match + ": 4477 is filtered out, so 447700 falls back to 44 (prefix) or matches nothing (exact)");
        }
    }

    @Test
    void rangeMatchesTheRowWhosePeriodHoldsTheValueBoundsInclusiveAndOpenEnds() throws Exception {
        seedRates();
        Map<Integer, String> got = rateJoin(Map.of("filter", "lookup_key = 'SDR'"));
        assertEquals(6, got.size(), "a LEFT join: no call dropped");
        assertEquals(6, lastRangeRows, "…and none repeated");
        assertEquals("610.5", got.get(1));
        assertEquals("612.7", got.get(2), "the start day is inside");
        assertEquals("612.7", got.get(3), "the end day is inside");
        assertNull(got.get(4), "a date in no period (a gap) matches nothing");
        assertEquals("812.4", got.get(5), "an empty end reaches forever");
        assertNull(got.get(6), "a NULL date matches nothing");
    }

    @Test
    void overlappingRangesAreRefusedNamingBothUnlessDuplicatesSaysWhichToKeep() throws Exception {
        seedRates();
        // without the filter the open-ended TAX row overlaps the SDR periods
        var e = assertThrows(IllegalArgumentException.class, () -> rateJoin(Map.of()));
        assertTrue(e.getMessage().contains("overlap") && e.getMessage().contains("2021-12-20"), e.getMessage());
        // keep, per date, the period that started last
        Map<Integer, String> got = rateJoin(Map.of("duplicates", "last", "order_by", "valid_from"));
        assertEquals("1.41", got.get(4), "2023-05-01 lies only in the open-ended TAX period");
        assertEquals("612.7", got.get(2), "2022-02-01: the SDR period started after the TAX one");
    }

    @Test
    void aNumericRangeComparesAsNumbersNotText() throws Exception {
        sql("CREATE TABLE rates AS SELECT * FROM (VALUES (1, 9, 'small'), (10, 99, 'medium'), (100, NULL, 'large'))"
                + " t(lo, hi, band)");
        sql("CREATE TABLE calls AS SELECT * FROM (VALUES (1, 9), (2, 10), (3, 100), (4, 1000)) t(id, call_date)");
        Map<String, Object> cfg = Map.of("reference", "r", "match", "range", "from", "lo", "to", "hi",
                "on", "call_date", "columns", List.of("band"), "as", "b_");
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of("n", "transform.join", cfg), "calls", "n",
                (c, name) -> "rates"));
        Map<Integer, String> band = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, b_band FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            while (rs.next()) band.put(rs.getInt(1), rs.getString(2));
        }
        // as text, '10' < '9' and '1000' < '99' — a text comparison would put 10 and 1000 in the wrong band
        assertEquals(Map.of(1, "small", 2, "medium", 3, "large", 4, "large"), band);
    }

    @Test
    void rangeSettingsThatCannotRunAreRefused() throws Exception {
        seedRates();
        assertThrows(IllegalArgumentException.class, () -> rateJoin(Map.of("key", "lookup_key")), "range takes from/to, not key");
        assertThrows(IllegalArgumentException.class, () -> rateJoin(Map.of("from", "nope")), "a missing bound column");
        Map<String, Object> noBounds = Map.of("reference", "r", "match", "range", "on", "call_date");
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("x", "transform.join", noBounds), "calls", "x", (c, name) -> "rates"));
        Map<String, Object> boundsWithoutRange = Map.of("reference", "r", "match", "exact", "key", "lookup_key",
                "from", "valid_from", "on", "call_date");
        assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("y", "transform.join", boundsWithoutRange), "calls", "y", (c, name) -> "rates"));
    }

    @Test
    void mergeUnionAndJoin() throws Exception {
        sql("CREATE TABLE a AS SELECT * FROM (VALUES (1,'x'),(2,'y')) t(id,v)");
        sql("CREATE TABLE b AS SELECT * FROM (VALUES (3,'z')) t(id,v)");
        var union = RowShaper.merge(conn, PipelineNode.of("u", "transform.merge", Map.of("type", "union")),
                List.of("a", "b"), "u");
        assertEquals(3, count(union.get(0).table()));

        sql("CREATE TABLE l AS SELECT * FROM (VALUES (1,10),(2,20)) t(id,amt)");
        sql("CREATE TABLE rdim AS SELECT * FROM (VALUES (1,'AA')) t(id,code)");
        var join = RowShaper.merge(conn, PipelineNode.of("j", "transform.merge",
                        Map.of("type", "inner", "on", List.of("id"))),
                List.of("l", "rdim"), "j");
        assertEquals(1, count(join.get(0).table()));   // only id=1 matches
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Lookup of produced relations by their PipelineRel for terse assertions. */
    private record RelationByRel(List<RowShaper.Relation> rels) {
        String table(String rel) {
            return rels.stream().filter(r -> r.rel().equals(rel)).map(RowShaper.Relation::table).findFirst()
                    .orElseThrow(() -> new AssertionError("no relation '" + rel + "' in " + rels));
        }
    }

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }

    private int count(String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Integer> ids(String table, String col) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) out.add(md.getColumnName(i));
        }
        return out;
    }
}
