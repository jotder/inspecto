package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.mask.EvidenceMasker;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code transform.hash} (catalog {@code quality.crypto.hash}, operator 2026-10-06): a Space-keyed salted one-way
 * token per value. The leak probes plant a raw value, then look for it in every table the run leaves behind and in
 * every refusal — a hash that left its value/token map lying around would be a lookup table for reversing it.
 */
class RowShaperHashTest {

    static final String RAW = "447700900123";
    static final String RAW2 = "447700900999";

    @TempDir Path tmp;
    private File db;
    private Connection conn;
    private String priorRoot;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rsh_");
        conn = DuckDbUtil.openConnection(db);
        priorRoot = System.getProperty("assist.write.root");
        bindSpace("a");
        sql("CREATE TABLE src AS SELECT * FROM (VALUES (1,'" + RAW + "'),(2,'" + RAW2 + "'),(3,'" + RAW
                + "'),(4,NULL)) t(id,msisdn)");
    }

    @AfterEach
    void close() throws Exception {
        if (priorRoot == null) System.clearProperty("assist.write.root");
        else System.setProperty("assist.write.root", priorRoot);
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    /** Binds the default Space's config root to {@code tmp/<name>/config}. */
    private Path bindSpace(String name) throws Exception {
        Path root = Files.createDirectories(tmp.resolve(name).resolve("config"));
        System.setProperty("assist.write.root", root.toString());
        return root;
    }

    private Path root() {
        return Path.of(System.getProperty("assist.write.root"));
    }

    private String run(Map<String, Object> cfg) throws SQLException {
        List<RowShaper.Relation> rels = RowShaper.shape(conn, PipelineNode.of("h", "transform.hash", cfg), "src", "h");
        assertEquals(1, rels.size());
        assertEquals(PipelineRel.DATA, rels.get(0).rel());
        return rels.get(0).table();
    }

    @Test
    void replacesInPlaceDeterministicallyWithTheSpaceToken() throws Exception {
        String out = run(Map.of("columns", List.of("MSISDN")));   // case-insensitive column match
        List<String> v = strings(out, "msisdn");
        assertEquals(EvidenceMasker.forSpace(root()).tokenFor(RAW), v.get(0),
                "the token is the Space's EvidenceMasker token — one token per value per Space");
        assertEquals(v.get(0), v.get(2), "equal values hash equal, so the column can still be joined on");
        assertNotEquals(v.get(0), v.get(1));
        assertNull(v.get(3), "NULL stays NULL — it is not a value to hash");
        assertTrue(v.get(0).matches("masked:[0-9a-f]{16}"), v.get(0));
        assertEquals(List.of(1, 2, 3, 4), ints(out, "id"), "a hash never changes row counts or other columns");
    }

    @Test
    void keepOriginalAddsAHashColumnBeside() throws Exception {
        String out = run(Map.of("columns", List.of("msisdn"), "keep_original", true));
        assertEquals(RAW, strings(out, "msisdn").get(0), "keep_original leaves the source column untouched");
        assertEquals(EvidenceMasker.forSpace(root()).tokenFor(RAW), strings(out, "msisdn_hash").get(0));
    }

    @Test
    void anotherSpaceHashesTheSameValueDifferently() throws Exception {
        String a = strings(run(Map.of("columns", List.of("msisdn"))), "msisdn").get(0);
        sql("DROP TABLE h__data");
        bindSpace("b");
        String b = strings(run(Map.of("columns", List.of("msisdn"))), "msisdn").get(0);
        assertNotEquals(a, b, "the salt is per Space — the same value must not hash alike across Spaces");
    }

    /** Leak probe: no table the run leaves behind (other than the input) holds the raw value or the key. */
    @Test
    void noRawValueOrKeyIsLeftInAnyTable() throws Exception {
        run(Map.of("columns", List.of("msisdn")));
        Path key = EvidenceMasker.keyFile(root());
        assertTrue(Files.isRegularFile(key), "the Space key lives in the Space's secrets, outside config");
        assertFalse(key.startsWith(root()), "the key file must not sit under the config root");
        String keyHex = HexFormat.of().formatHex(Files.readAllBytes(key));
        assertEquals(List.of("h__data", "src"), tables(),
                "the value→token scratch map is dropped — left behind it would be a reversal table");
        String dump = dump("h__data");
        assertFalse(dump.contains(RAW), "a raw value reached the output: " + dump);
        assertFalse(dump.contains(RAW2), dump);
        assertFalse(dump.contains(keyHex.substring(0, 16)), "the key reached the output");
    }

    /** Leak probe on the failure text: a refusal names the column, never a value — and leaves no scratch table. */
    @Test
    void refusesAnUnknownColumnWithoutNamingAValue() throws Exception {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run(Map.of("columns", List.of("msisdn", "imsi"))));
        assertTrue(e.getMessage().contains("'imsi'"), e.getMessage());
        assertFalse(e.getMessage().contains(RAW), "a refusal must never carry a value: " + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> run(Map.of("columns", List.of())));
        assertEquals(List.of("src"), tables());
    }

    @Test
    void refusesWithNoSpaceRatherThanHashingUnkeyed() {
        System.clearProperty("assist.write.root");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> run(Map.of("columns", List.of("msisdn"))));
        assertTrue(e.getMessage().contains("Space"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }

    private List<String> tables() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables ORDER BY 1")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
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

    private String dump(String table) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\"")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) for (int i = 1; i <= n; i++) sb.append(rs.getString(i)).append('|');
        }
        return sb.toString();
    }
}
