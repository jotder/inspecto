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
                PipelineNode.of("sm", "transform.summarize", Map.of("measures", List.of("median(amt)"))), "src", "sm"));
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

    @Test
    void fuseFiltersAndProjectionIntoOnePass() throws Exception {
        seedSrc();
        RowShaper.Relation r = RowShaper.fuse(conn, List.of(
                PipelineNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                PipelineNode.of("m", "transform.sql", Map.of("columns",
                        List.of(Map.of("name", "id", "expr", "id"), Map.of("name", "amt", "expr", "amt"))))
        ), "src", "chain");
        assertEquals(PipelineRel.DATA, r.rel());
        assertEquals(List.of(1, 3), ids(r.table(), "id"));                 // filtered
        assertEquals(List.of("amt", "id"), columns(r.table()).stream().sorted().toList());   // projected
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
