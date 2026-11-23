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

    // ── join match: asof (LOOKUP-1 P1) ──

    private Map<Integer, Map<String, String>> asofJoin(String txnTimeType, Object... extra) throws SQLException {
        sql("DROP TABLE IF EXISTS arate");
        sql("DROP TABLE IF EXISTS atxn");
        sql("CREATE TABLE arate AS SELECT * FROM (VALUES"
                + " ('USD', DATE '2026-01-01', 1.0), ('USD', DATE '2026-03-01', 1.1), ('EUR', DATE '2026-02-01', 2.0)"
                + ") t(ccy, valid_from, rate)");
        sql("CREATE TABLE atxn AS SELECT id, ccy, CAST(d AS " + txnTimeType + ") AS txn_date FROM (VALUES"
                + " (1, 'USD', '2026-02-15'), (2, 'USD', '2026-03-01'), (3, 'USD', '2025-12-31'),"
                + " (4, 'EUR', '2026-02-01'), (5, 'EUR', '2026-06-01'), (6, 'GBP', '2026-02-15'),"
                + " (7, 'USD', NULL)) t(id, ccy, d)");
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(Map.of("reference", "r", "key", "ccy",
                "match", "asof", "from", "valid_from", "at", "txn_date", "on", List.of("ccy"),
                "columns", List.of("rate")));
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) cfg.remove((String) extra[i]);
            else cfg.put((String) extra[i], extra[i + 1]);
        }
        String id = "a" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "atxn", id,
                (c, name) -> "arate"));
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
    void asofTakesTheLatestReferenceRowOfTheKeyEffectiveAtOrBeforeTheRowsTime() throws Exception {
        var rows = asofJoin("DATE");
        assertEquals(7, rows.size(), "a LEFT join: no transaction dropped or repeated");
        assertEquals("1.0", rows.get(1).get("ccy_rate"), "between two effective dates: the earlier one");
        assertEquals("1.1", rows.get(2).get("ccy_rate"), "a row on its effective date takes that row");
        assertNull(rows.get(3).get("ccy_rate"), "before the key's first effective date: nothing");
        assertEquals("2.0", rows.get(4).get("ccy_rate"));
        assertEquals("2.0", rows.get(5).get("ccy_rate"), "after the last: the last stays in force");
        assertNull(rows.get(6).get("ccy_rate"), "a key the Reference lacks");
        assertNull(rows.get(7).get("ccy_rate"), "a row with no time matches nothing");
    }

    @Test
    void asofReadsATextTimeAsATimeAndAnEmptyFromAsSinceForever() throws Exception {
        var rows = asofJoin("VARCHAR");
        assertEquals("1.1", rows.get(2).get("ccy_rate"), "'2026-03-01' text compares as the date");
        assertEquals("1.0", rows.get(1).get("ccy_rate"));
        sql("DROP TABLE IF EXISTS arate");
        sql("CREATE TABLE arate AS SELECT * FROM (VALUES ('USD', CAST(NULL AS DATE), 0.5),"
                + " ('USD', DATE '2026-03-01', 1.1)) t(ccy, valid_from, rate)");
        sql("DROP TABLE IF EXISTS atxn");
        sql("CREATE TABLE atxn AS SELECT * FROM (VALUES (1, 'USD', DATE '2020-01-01'),"
                + " (2, 'USD', DATE '2026-04-01')) t(id, ccy, txn_date)");
        var cfg = Map.<String, Object>of("reference", "r", "key", "ccy", "match", "asof", "from", "valid_from",
                "at", "txn_date", "on", List.of("ccy"), "columns", List.of("rate"));
        String id = "asofnull";
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "atxn", id,
                (c, name) -> "arate"));
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, ccy_rate FROM \"" + out.table(PipelineRel.DATA) + "\" ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals("0.5", rs.getString(2), "an empty from: reaches back forever");
            assertTrue(rs.next());
            assertEquals("1.1", rs.getString(2));
        }
    }

    @Test
    void asofRefusesTwoReferenceRowsOfOneKeyAndDateAndMissingOrMisplacedSettings() throws Exception {
        asofJoin("DATE");
        sql("DROP TABLE IF EXISTS arate");
        sql("CREATE TABLE arate AS SELECT * FROM (VALUES ('USD', DATE '2026-01-01', 1.0),"
                + " ('USD', DATE '2026-01-01', 1.5)) t(ccy, valid_from, rate)");
        var cfg = Map.<String, Object>of("reference", "r", "key", "ccy", "match", "asof", "from", "valid_from",
                "at", "txn_date", "on", List.of("ccy"), "columns", List.of("rate"));
        var dup = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("asofdup", "transform.join", cfg), "atxn", "asofdup", (c, name) -> "arate"));
        assertTrue(dup.getMessage().contains("USD") && dup.getMessage().contains("2026-01-01"), dup.getMessage());
        var noAt = assertThrows(IllegalArgumentException.class, () -> asofJoin("DATE", "at", null));
        assertTrue(noAt.getMessage().contains("at:"), noAt.getMessage());
        var badAt = assertThrows(IllegalArgumentException.class, () -> asofJoin("DATE", "at", "nope"));
        assertTrue(badAt.getMessage().contains("nope"), badAt.getMessage());
        var to = assertThrows(IllegalArgumentException.class, () -> asofJoin("DATE", "to", "valid_from"));
        assertTrue(to.getMessage().contains("to:"), to.getMessage());
        var stray = assertThrows(IllegalArgumentException.class, () -> normalizedJoin("at", "v"));
        assertTrue(stray.getMessage().contains("asof"), stray.getMessage());
    }

    // ── join normalize: normalized exact (LOOKUP-1 P1) ──

    private Map<Integer, Map<String, String>> normalizedJoin(Object... extra) throws SQLException {
        sql("DROP TABLE IF EXISTS nref");
        sql("DROP TABLE IF EXISTS nin");
        sql("CREATE TABLE nref AS SELECT * FROM (VALUES (' AB1 ', 'first'), ('007', 'bond')) t(code, label)");
        sql("CREATE TABLE nin AS SELECT * FROM (VALUES (1, 'ab1'), (2, '+7'), (3, '7'), (4, '000'), (5, 'zz'))"
                + " t(id, v)");
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(Map.of("reference", "r", "key", "code",
                "match", "exact", "on", List.of("v"), "columns", List.of("label")));
        for (int i = 0; i < extra.length; i += 2) {
            if (extra[i + 1] == null) cfg.remove((String) extra[i]);
            else cfg.put((String) extra[i], extra[i + 1]);
        }
        String id = "n" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), "nin", id,
                (c, name) -> "nref"));
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
    void normalizeMakesBothSidesCompareInOneSpelling() throws Exception {
        var rows = normalizedJoin("normalize", List.of("trim", "upper", "strip_plus", "strip_leading_zeros"));
        assertEquals("first", rows.get(1).get("v_label"), "' AB1 ' and 'ab1' are one key once trimmed and upper-cased");
        assertEquals("bond", rows.get(2).get("v_label"), "'+7' and '007' both reduce to '7'");
        assertEquals("bond", rows.get(3).get("v_label"));
        assertNull(rows.get(4).get("v_label"), "'000' reduces to '0', which the Reference lacks");
        assertNull(rows.get(5).get("v_label"));
    }

    @Test
    void withoutNormalizeSpellingsStayDifferent() throws Exception {
        var rows = normalizedJoin();
        assertNull(rows.get(1).get("v_label"));
        assertNull(rows.get(2).get("v_label"));
    }

    @Test
    void aReferenceThatCollapsesToOneKeyIsRefusedByName() throws Exception {
        sql("CREATE TABLE nref AS SELECT * FROM (VALUES ('A', 'x'), (' a', 'y')) t(code, label)");
        sql("CREATE TABLE nin AS SELECT * FROM (VALUES (1, 'a')) t(id, v)");
        var cfg = Map.<String, Object>of("reference", "r", "key", "code", "match", "exact", "on", List.of("v"),
                "normalize", List.of("trim", "upper"));
        var e = assertThrows(IllegalArgumentException.class, () -> RowShaper.shape(conn,
                PipelineNode.of("nd", "transform.join", cfg), "nin", "nd", (c, name) -> "nref"));
        assertTrue(e.getMessage().contains("A"), e.getMessage());
    }

    @Test
    void anUnknownNormalizerAndNormalizeOnARangeAreRefused() throws Exception {
        var unknown = assertThrows(IllegalArgumentException.class, () -> normalizedJoin("normalize", "soundex"));
        assertTrue(unknown.getMessage().contains("soundex"), unknown.getMessage());
        var range = assertThrows(IllegalArgumentException.class, () -> normalizedJoin("match", "range",
                "key", null, "from", "code", "normalize", "trim"));
        assertTrue(range.getMessage().contains("range"), range.getMessage());
    }

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

    /** Runs a Fields projection of txn(id, currency) against fx(currency, rate); returns id → looked-up rate. */
    private Map<Integer, String> lookupField(Map<String, String> args) throws SQLException {
        Map<String, String> a = new java.util.LinkedHashMap<>(Map.of("reference", "reference/fx", "key", "currency",
                "returns", "rate", "normalize", "none"));
        a.putAll(args);
        String id = "lf" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.sql", Map.of("fields", List.of(
                Map.of("name", "id", "from", "id", "fn", "keep"),
                Map.of("name", "rate", "from", "currency", "fn", "lookup.reference", "args", a)))),
                "txn", id, (c, name) -> "fx"));
        Map<Integer, String> rows = new java.util.TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, rate FROM \"" + out.table(PipelineRel.DATA) + "\"")) {
            while (rs.next()) rows.put(rs.getInt(1), rs.getString(2));
        }
        return rows;
    }

    @Test
    void aLookupFieldReturnsTheReferenceValueOfItsRowsKey() throws Exception {
        sql("CREATE TABLE fx AS SELECT * FROM (VALUES ('USD', '1.0'), ('EUR', '0.9')) t(currency, rate)");
        sql("CREATE TABLE txn AS SELECT * FROM (VALUES (1, 'USD'), (2, 'eur'), (3, 'XXX'), (4, NULL)) t(id, currency)");
        var exact = lookupField(Map.of());
        assertEquals("1.0", exact.get(1));
        assertNull(exact.get(2), "exact: 'eur' is not 'EUR'");
        assertNull(exact.get(3), "no Reference row");
        assertNull(exact.get(4), "no value");
        assertEquals("0.9", lookupField(Map.of("normalize", "upper")).get(2), "normalized: 'eur' matches 'EUR'");
    }

    @Test
    void aLookupFieldOverARepeatedKeyAndAMissingColumnAreRefused() throws Exception {
        sql("CREATE TABLE fx AS SELECT * FROM (VALUES ('USD', '1.0'), ('USD', '1.1')) t(currency, rate)");
        sql("CREATE TABLE txn AS SELECT * FROM (VALUES (1, 'USD')) t(id, currency)");
        assertThrows(SQLException.class, () -> lookupField(Map.of()), "two Reference rows for one key stop the run");
        var e = assertThrows(IllegalArgumentException.class, () -> lookupField(Map.of("returns", "nope")));
        assertTrue(e.getMessage().contains("has no column 'nope'"), e.getMessage());
    }

    /** A walk reads these into the Step's receipt; a null value is neither a hit nor a miss; reading drops them. */
    @Test
    void aKeyedJoinLeavesPerMappingHitAndMissCountsForTheWalkToTake() throws Exception {
        seedDialDigits();
        var rows = keyedJoin(keyedCfg());
        String id = "k" + keyedJoins;
        assertTrue(rows.get(1).keySet().stream().noneMatch(c -> c.startsWith("__lk_")),
                "the counting columns never reach the output");
        var counts = RowShaper.takeLookupStats(conn, id);
        assertEquals(2L, counts.get("lookup.served_msisdn.hit"));
        assertEquals(1L, counts.get("lookup.served_msisdn.miss"), "999 has a value and no Reference row");
        assertEquals(1L, counts.get("lookup.third_party_msisdn.hit"));
        assertEquals(1L, counts.get("lookup.third_party_msisdn.miss"), "33… is not in the table; the NULL is neither");
        assertTrue(RowShaper.takeLookupStats(conn, id).isEmpty(), "taken once, then gone");
        assertTrue(RowShaper.takeLookupStats(conn, "no_such_node").isEmpty());
    }

    /** served_msisdn misses 1 of its 3 values: a miss rate cap above that lets the run on, below it stops it. */
    @Test
    void maxMissRateStopsTheStepWhenTooManyValuesFindNoReferenceRow() throws Exception {
        seedDialDigits();
        assertEquals(3, keyedJoin(keyedCfg("max_miss_rate", 0.5)).size());
        assertEquals(3, keyedJoin(keyedCfg()).size(), "unset: never stops");
        var e = assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("max_miss_rate", 0.2)));
        assertTrue(e.getMessage().contains("1 of 3 values of column served_msisdn"), e.getMessage());
        assertTrue(e.getMessage().contains("stale"), e.getMessage());
        var bad = assertThrows(IllegalArgumentException.class, () -> keyedJoin(keyedCfg("max_miss_rate", 2)));
        assertTrue(bad.getMessage().contains("0 to 1"), bad.getMessage());
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

    // ── join output model: per-input mappings with rename + match details (LOOKUP-1 P1d) ──
    // A synthetic dial-digit-shaped dimension; each number fetches its own columns under a legacy layout's names.

    private void seedLegacyDialDigits() throws SQLException {
        sql("CREATE TABLE ddl AS SELECT * FROM (VALUES"
                + " ('249', 'SD', 'Sudan', 10, 'INT', 'Sudan'),"
                + " ('24991', 'SD', 'Operator A', 11, 'ONNET', 'Sudan'),"
                + " ('44', 'GB', 'United Kingdom', 13, 'INT', 'United Kingdom')"
                + ") t(dial_digit_key, iso_country_code, provider_desc, nop_id_key, event_category_key, country_name)");
        sql("CREATE TABLE cdrl AS SELECT * FROM (VALUES"
                + " (1, '249915555555', '24991', '447700900123'),"
                + " (2, '999', '249112345678', NULL)"
                + ") t(id, SERVED_MSISDN, OTHER_MSISDN, THIRD_PARTY_MSISDN)");
    }

    private static Map<String, Object> legacyLookups() {
        Map<String, Object> served = new java.util.LinkedHashMap<>();
        served.put("on", "SERVED_MSISDN");
        served.put("columns", orderedMap("dial_digit_key", "SERVED_MSISDN_DIAL_DIGIT_KEY",
                "iso_country_code", "SERVED_MSISDN_ISO_CODE", "provider_desc", "SERVED_MSISDN_OPER",
                "nop_id_key", "SERVED_MSISDN_NOP_ID_KEY"));
        served.put("default", Map.of("SERVED_MSISDN_DIAL_DIGIT_KEY", -99));
        Map<String, Object> other = new java.util.LinkedHashMap<>();
        other.put("on", "OTHER_MSISDN");
        other.put("columns", orderedMap("dial_digit_key", "OTHER_MSISDN_DIAL_DIGIT_KEY",
                "event_category_key", "EVENT_CATEGORY_KEY", "country_name", "OTHER_MSISDN_COUNTRY",
                "provider_desc", "OTHER_MSISDN_OPERATOR"));
        other.put("details", true);
        Map<String, Object> third = new java.util.LinkedHashMap<>();
        third.put("on", "THIRD_PARTY_MSISDN");
        third.put("columns", List.of("provider_desc"));   // a list keeps the names, under the {on}_ prefix
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("reference", "r");
        cfg.put("key", "dial_digit_key");
        cfg.put("match", "longest_prefix");
        cfg.put("lookups", List.of(served, other, third));
        return cfg;
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Runs {@code transform.join} {@code cfg} over {@code input} against {@code ref}; rows by id. */
    private Map<Integer, Map<String, String>> joinRows(Map<String, Object> cfg, String input, String ref)
            throws SQLException {
        String id = "p" + (++keyedJoins);
        var out = new RelationByRel(RowShaper.shape(conn, PipelineNode.of(id, "transform.join", cfg), input, id,
                (c, name) -> ref));
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
    void lookupsGiveEachNumberItsOwnColumnsUnderTheLegacyNames() throws Exception {
        seedLegacyDialDigits();
        var rows = joinRows(legacyLookups(), "cdrl", "ddl");
        assertEquals(2, rows.size(), "a LEFT join: no call record dropped or repeated");
        assertEquals(List.of("id", "SERVED_MSISDN", "OTHER_MSISDN", "THIRD_PARTY_MSISDN",
                        "SERVED_MSISDN_DIAL_DIGIT_KEY", "SERVED_MSISDN_ISO_CODE", "SERVED_MSISDN_OPER",
                        "SERVED_MSISDN_NOP_ID_KEY",
                        "OTHER_MSISDN_DIAL_DIGIT_KEY", "EVENT_CATEGORY_KEY", "OTHER_MSISDN_COUNTRY",
                        "OTHER_MSISDN_OPERATOR", "OTHER_MSISDN_matched_key", "OTHER_MSISDN_match_kind",
                        "THIRD_PARTY_MSISDN_provider_desc"),
                List.copyOf(rows.get(1).keySet()));
        var r1 = rows.get(1);
        assertEquals("24991", r1.get("SERVED_MSISDN_DIAL_DIGIT_KEY"));
        assertEquals("Operator A", r1.get("SERVED_MSISDN_OPER"));
        assertEquals("11", r1.get("SERVED_MSISDN_NOP_ID_KEY"));
        assertEquals("ONNET", r1.get("EVENT_CATEGORY_KEY"));
        assertEquals("Sudan", r1.get("OTHER_MSISDN_COUNTRY"));
        assertEquals("United Kingdom", r1.get("THIRD_PARTY_MSISDN_provider_desc"));
        var r2 = rows.get(2);
        assertEquals("-99", r2.get("SERVED_MSISDN_DIAL_DIGIT_KEY"), "the default is keyed by OUTPUT name");
        assertNull(r2.get("SERVED_MSISDN_OPER"), "no default for this output → NULL");
        assertEquals("INT", r2.get("EVENT_CATEGORY_KEY"), "2491… falls back to the country code row");
        assertNull(r2.get("THIRD_PARTY_MSISDN_provider_desc"));
    }

    @Test
    void theColumnsAStepAddsAreExactlyTheColumnsTheRunAdds() throws Exception {
        seedLegacyDialDigits();
        List<String> input = List.of("id", "SERVED_MSISDN", "OTHER_MSISDN", "THIRD_PARTY_MSISDN");
        Map<String, String> refTypes = orderedMap("dial_digit_key", "VARCHAR", "iso_country_code", "VARCHAR",
                "provider_desc", "VARCHAR", "nop_id_key", "INTEGER", "event_category_key", "VARCHAR",
                "country_name", "VARCHAR").entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> e.getValue().toString(), (a, b) -> a, java.util.LinkedHashMap::new));
        var added = RowShaper.joinAddedColumns(legacyLookups(), refTypes, input);
        var ran = new java.util.ArrayList<>(joinRows(legacyLookups(), "cdrl", "ddl").get(1).keySet());
        assertEquals(ran.subList(input.size(), ran.size()), added.stream().map(RowShaper.AddedColumn::name).toList());
        var nop = added.get(3);
        assertEquals(new RowShaper.AddedColumn("SERVED_MSISDN_NOP_ID_KEY", "INTEGER", "nop_id_key", "SERVED_MSISDN",
                "longest_prefix", null), nop);
        var kind = added.stream().filter(a -> a.name().equals("OTHER_MSISDN_match_kind")).findFirst().orElseThrow();
        assertEquals("match_kind", kind.detail());
        assertNull(kind.from());
        assertEquals("OTHER_MSISDN", kind.lookedUpBy());
        // a clash shows before the run, by name
        var e = assertThrows(IllegalArgumentException.class, () -> RowShaper.joinAddedColumns(legacyLookups(),
                refTypes, List.of("id", "SERVED_MSISDN", "OTHER_MSISDN", "THIRD_PARTY_MSISDN", "EVENT_CATEGORY_KEY")));
        assertTrue(e.getMessage().contains("'EVENT_CATEGORY_KEY' clashes"), e.getMessage());
    }

    @Test
    void matchDetailsSayWhichKeyMatchedAndHow() throws Exception {
        seedLegacyDialDigits();
        Map<String, Object> cfg = orderedMap("reference", "r", "key", "dial_digit_key", "match", "longest_prefix",
                "lookups", List.of(orderedMap("on", "OTHER_MSISDN", "columns", List.of("provider_desc"),
                        "details", orderedMap("matched_key", "OM_KEY", "match_kind", "OM_KIND"))));
        var rows = joinRows(cfg, "cdrl", "ddl");
        assertEquals("24991", rows.get(1).get("OM_KEY"));
        assertEquals("exact", rows.get(1).get("OM_KIND"), "the key is the whole value");
        assertEquals("249", rows.get(2).get("OM_KEY"));
        assertEquals("prefix", rows.get(2).get("OM_KIND"));
        // none: nothing matched
        Map<String, Object> served = orderedMap("reference", "r", "key", "dial_digit_key", "match", "longest_prefix",
                "on", "SERVED_MSISDN", "columns", List.of("provider_desc"), "details", true);
        var r2 = joinRows(served, "cdrl", "ddl").get(2);
        assertNull(r2.get("SERVED_MSISDN_matched_key"));
        assertEquals("none", r2.get("SERVED_MSISDN_match_kind"), "the shorthand takes details too");
        // exact match
        Map<String, Object> exact = new java.util.LinkedHashMap<>(served);
        exact.put("match", "exact");
        exact.put("on", "OTHER_MSISDN");
        var ex = joinRows(exact, "cdrl", "ddl");
        assertEquals("exact", ex.get(1).get("OTHER_MSISDN_match_kind"));
        assertEquals("none", ex.get(2).get("OTHER_MSISDN_match_kind"), "exact never falls back to a prefix");
        // range: the matched key is the period
        seedRates();
        var rr = joinRows(orderedMap("reference", "r", "match", "range", "from", "valid_from", "to", "valid_to",
                "filter", "lookup_key = 'SDR'", "lookups", List.of(orderedMap("on", "call_date",
                        "columns", orderedMap("lookup_output", "SDR_RATE"), "details", true))), "calls", "rates");
        assertEquals("612.7", rr.get(2).get("SDR_RATE"));
        assertEquals("2022-02-01..2022-02-28", rr.get(2).get("call_date_matched_key"));
        assertEquals("range", rr.get(2).get("call_date_match_kind"));
        assertEquals("2026-08-01..", rr.get(5).get("call_date_matched_key"), "an open end stays empty");
        assertEquals("none", rr.get(4).get("call_date_match_kind"), "a gap matches nothing");
    }

    @Test
    void lookupsRefuseClashesAndMistakesByName() throws Exception {
        seedLegacyDialDigits();
        java.util.function.Function<Map<String, Object>, String> refusal = cfg ->
                assertThrows(IllegalArgumentException.class, () -> joinRows(cfg, "cdrl", "ddl")).getMessage();
        // an output name equal to an input column
        String m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "lookups", List.of(
                orderedMap("on", "SERVED_MSISDN", "columns", orderedMap("provider_desc", "OTHER_MSISDN")))));
        assertTrue(m.contains("'OTHER_MSISDN' clashes") && m.contains("rename it"), m);
        // two mappings writing one name (case-insensitively)
        m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "lookups", List.of(
                orderedMap("on", "SERVED_MSISDN", "columns", orderedMap("provider_desc", "OPER")),
                orderedMap("on", "OTHER_MSISDN", "columns", orderedMap("provider_desc", "oper")))));
        assertTrue(m.contains("'oper' clashes"), m);
        // a Reference column that does not exist
        m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "lookups", List.of(
                orderedMap("on", "SERVED_MSISDN", "columns", orderedMap("operator_name", "X")))));
        assertTrue(m.contains("no column 'operator_name'"), m);
        // a default keyed by the Reference column, not the output name
        m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "lookups", List.of(
                orderedMap("on", "SERVED_MSISDN", "columns", orderedMap("provider_desc", "SERVED_MSISDN_OPER"),
                        "default", Map.of("provider_desc", "Unknown")))));
        assertTrue(m.contains("default for 'provider_desc'"), m);
        // lookups with the shorthand's on
        m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "on", "SERVED_MSISDN",
                "lookups", List.of(orderedMap("on", "SERVED_MSISDN"))));
        assertTrue(m.contains("both lookups: and 'on'"), m);
        // an entry key that means nothing
        m = refusal.apply(orderedMap("reference", "r", "key", "dial_digit_key", "lookups", List.of(
                orderedMap("on", "SERVED_MSISDN", "rename", Map.of()))));
        assertTrue(m.contains("'rename'"), m);
        // lookups without key: is not the keyed form
        m = refusal.apply(orderedMap("reference", "r", "lookups", List.of(orderedMap("on", "SERVED_MSISDN"))));
        assertTrue(m.contains("'lookups'") && m.contains("needs key:"), m);
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
