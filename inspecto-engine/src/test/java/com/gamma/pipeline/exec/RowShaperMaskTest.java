package com.gamma.pipeline.exec;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.EditionFeatures;
import com.gamma.pipeline.PipelineNode;
import com.gamma.risk.EvidenceMasker;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.mask} (catalog {@code quality.pii.mask}, SEC-08 Enterprise-only, operator 2026-10-06). Columns are
 * chosen by the registry Dataset's classification (own + lineage, strictest-wins) or by name. The leak probes plant
 * raw values and look for them in every table the run leaves behind and in every refusal.
 */
class RowShaperMaskTest {

    static final String MSISDN = "447700900123";
    static final String EMAIL = "ada@example.org";
    static final String PLAN = "GOLD-ACCT-9921";

    @TempDir Path tmp;
    private File db;
    private Connection conn;
    private String priorRoot;
    private Path root;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsm_");
        conn = DuckDbUtil.openConnection(db);
        priorRoot = System.getProperty("assist.write.root");
        root = Files.createDirectories(tmp.resolve("space").resolve("config"));
        System.setProperty("assist.write.root", root.toString());
        EditionFeatures.overrideForTest(Set.of(EditionFeatures.PII_MASK));
        // subs declares msisdn MSISDN, email PII; a sibling Dataset over the same store declares plan ACCOUNT and
        // email MSISDN — so plan is classified only through LINEAGE, and email carries two classes.
        dataset("subs", "subs_store", List.of(col("msisdn", "MSISDN"), col("email", "PII"), col("id", null)));
        dataset("subs_billing", "subs_store", List.of(col("plan", "ACCOUNT"), col("email", "MSISDN")));
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1,'" + MSISDN + "','" + EMAIL + "','" + PLAN + "'),"
                + "(2,'12',NULL,'P')) t(id,msisdn,email,plan)");
    }

    @AfterEach
    void close() throws Exception {
        EditionFeatures.overrideForTest(null);
        if (priorRoot == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", priorRoot);
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    private static Map<String, Object> col(String name, String cls) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        if (cls != null) c.put("classification", cls);
        return c;
    }

    private void dataset(String id, String store, List<Map<String, Object>> cols) throws Exception {
        Path dir = Files.createDirectories(root.resolve("registry").resolve("datasets"));
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", id);
        ds.put("physicalRef", store);
        ds.put("columns", cols);
        Files.writeString(dir.resolve(id + ".toon"), ConfigCodec.toToon(ds));
    }

    private String run(Map<String, Object> cfg) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("m", "transform.mask", cfg), "src", "m").get(0).table();
    }

    @Test
    void masksByClassificationFullyAndLeavesTheRestAlone() throws Exception {
        String out = run(Map.of("classifications", List.of("MSISDN"), "dataset", "subs"));
        assertEquals("****", value(out, "msisdn", 1));
        assertEquals("****", value(out, "email", 1),
                "email is PII on subs but MSISDN on its sibling — a selected class wins (strictest-wins)");
        assertEquals(PLAN, value(out, "plan", 1), "plan is ACCOUNT, not selected");
        assertNull(value(out, "email", 2), "NULL stays NULL");
        assertEquals("1", value(out, "id", 1));
    }

    /**
     * Strictest-wins with the SELECTED set as the masked predicate: email is PII (own) and MSISDN (sibling). Under the
     * plain restrictiveness order MSISDN would win and a PII-only mask would miss email — selecting PII must mask it.
     */
    @Test
    void aSelectedClassOutranksAStricterUnselectedOne() throws Exception {
        String out = run(Map.of("classifications", List.of("PII"), "dataset", "subs"));
        assertEquals("****", value(out, "email", 1));
        assertEquals(MSISDN, value(out, "msisdn", 1), "msisdn is MSISDN only — not selected");
    }

    @Test
    void lineageClassificationIsHonoured() throws Exception {
        String out = run(Map.of("classifications", List.of("ACCOUNT"), "dataset", "subs", "mode", "partial"));
        assertEquals("**********9921", value(out, "plan", 1), "partial keeps the last 4, masks the rest");
        assertEquals("*", value(out, "plan", 2), "a value no longer than keep_last is masked whole");
        assertEquals(MSISDN, value(out, "msisdn", 1));
    }

    @Test
    void partialByNameWithKeepLastAndHashModeDelegates() throws Exception {
        String partial = run(Map.of("columns", List.of("MSISDN"), "mode", "partial", "keep_last", 3));
        assertEquals("*********123", value(partial, "msisdn", 1));
        assertEquals("**", value(partial, "msisdn", 2));
        sql("DROP TABLE m__data");
        String hashed = run(Map.of("columns", List.of("msisdn"), "mode", "hash"));
        assertEquals(EvidenceMasker.forSpace(root).tokenFor(MSISDN), value(hashed, "msisdn", 1),
                "mode hash IS transform.hash's Space-keyed token");
    }

    /** Leak probe: no raw value of a masked column is in any table the run leaves behind but the input. */
    @Test
    void noRawValueLeaksIntoAnyTable() throws Exception {
        for (String mode : List.of("full", "partial", "hash")) {
            run(Map.of("classifications", List.of("MSISDN", "ACCOUNT"), "dataset", "subs", "mode", mode));
            List<String> tables = tables();
            assertEquals(List.of("m__data", "src"), tables, mode);
            String dump = dump("m__data");
            for (String raw : List.of(MSISDN, EMAIL, PLAN))
                assertFalse(dump.contains(raw), mode + " leaked a raw value: " + dump);
            sql("DROP TABLE m__data");
        }
    }

    /** Leak probe on refusal text: a refusal names columns and classes, never a value. */
    @Test
    void refusalsNeverCarryAValue() throws Exception {
        List<Exception> refusals = new ArrayList<>();
        refusals.add(assertThrows(IllegalArgumentException.class, () -> run(Map.of("columns", List.of("imsi")))));
        refusals.add(assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("classifications", List.of("IMSI"), "dataset", "subs"))));
        refusals.add(assertThrows(IllegalArgumentException.class, () -> run(Map.of("mode", "full"))));
        refusals.add(assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("columns", List.of("msisdn"), "vault", "tokens"))));
        refusals.add(assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("classifications", List.of("PII"), "dataset", "nope"))));
        for (Exception e : refusals)
            for (String raw : List.of(MSISDN, EMAIL, PLAN))
                assertFalse(e.getMessage().contains(raw), e.getMessage());
        assertTrue(refusals.get(1).getMessage().contains("masks nothing"), refusals.get(1).getMessage());
        assertTrue(refusals.get(3).getMessage().contains("'vault'"), refusals.get(3).getMessage());
    }

    /** 🔒 SEC-08: outside Enterprise the Step refuses at run with ERR_EDITION_FEATURE, before touching a value. */
    @Test
    void refusedOutsideEnterprise() throws Exception {
        EditionFeatures.overrideForTest(Set.of());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> run(Map.of("columns", List.of("msisdn"))));
        assertTrue(e.getMessage().startsWith(EditionFeatures.CODE), e.getMessage());
        assertTrue(e.getMessage().contains("Enterprise"), e.getMessage());
        assertEquals(List.of("src"), tables(), "nothing was written");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }

    private String value(String table, String col, int id) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"" + col + "\" FROM \"" + table + "\" WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private List<String> tables() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private String dump(String table) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\"")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) for (int i = 1; i <= n; i++) sb.append(rs.getString(i)).append('|');
        }
        return sb.toString();
    }
}
