package com.gamma.analytics.sample;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.sample.Sampler.Spec;
import com.gamma.util.DuckDbUtil;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVM ↔ DuckDB parity for the sampler: {@link Sampler#sql} keeps exactly the rows {@link Sampler#select} keeps, for fixed
 * sizes and fractions, stratified and not, over keys with accents, CJK, emoji, quotes, empty text, NULL parts and an
 * integer column; and the SQL gives the same rows on a second run and over a shuffled copy of the table.
 */
class SamplerDuckDbParityTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    private static final String[] TEXTS = {"", "Alice", "o'brien", "été", "東京", "emoji 😀",
            "a\"b", "MiXeD", "x:y", "-", "3:abc"};

    private record Row(int id, String grp, String name, Integer num) {}

    private static final List<Row> ROWS = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        Random r = new Random(20261017);
        for (int i = 0; i < 2500; i++) {
            String grp = switch (r.nextInt(5)) { case 0 -> "north"; case 1 -> "south"; case 2 -> "east"; case 3 -> null; default -> "tiny"; };
            if ("tiny".equals(grp) && r.nextInt(20) != 0) grp = "west";
            String name = r.nextInt(12) == 0 ? null : TEXTS[r.nextInt(TEXTS.length)] + (r.nextBoolean() ? "" : " " + i);
            Integer num = r.nextInt(10) == 0 ? null : r.nextInt(1_000_000) - 500_000;
            ROWS.add(new Row(i, grp, name, num));
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER, \"the group\" VARCHAR, name VARCHAR, num INTEGER)");
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?)")) {
            for (Row row : ROWS) {
                ps.setInt(1, row.id());
                if (row.grp() == null) ps.setNull(2, Types.VARCHAR); else ps.setString(2, row.grp());
                if (row.name() == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, row.name());
                if (row.num() == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, row.num());
                ps.addBatch();
            }
            ps.executeBatch();
        }
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE shuffled AS SELECT * FROM t ORDER BY md5(CAST(id AS VARCHAR))");
        }
    }

    @AfterAll
    static void close() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void sqlKeepsExactlyTheJvmRows() throws Exception {
        List<Spec> specs = List.of(Spec.ofSize(42, 7), Spec.ofSize(-1, 1), Spec.ofSize(9, 5000),
                Spec.ofFraction(42, 0.1), Spec.ofFraction(7, 0.333), Spec.ofFraction(0, 1.0));
        List<List<String>> keySets = List.of(List.of("name", "num", "id"), List.of("name", "num"), List.of("id"));
        for (Spec spec : specs)
            for (List<String> key : keySets)
                for (boolean stratified : List.of(true, false)) {
                    List<String> groupBy = stratified ? List.of("the group") : List.of();
                    List<Integer> jvm = jvm(spec, key, stratified);
                    String sql = Sampler.sql(spec, "t", groupBy, key, List.of("id", "the group", "name", "num"), "__s_");
                    String what = spec + " key=" + key + " stratified=" + stratified;
                    assertEquals(jvm, ids(sql), what);
                    assertEquals(jvm, ids(sql), what + " (second run)");
                    assertEquals(jvm, ids(Sampler.sql(spec, "shuffled", groupBy, key,
                            List.of("id", "the group", "name", "num"), "__s_")), what + " (shuffled input)");
                    assertFalse(jvm.isEmpty(), what);
                }
    }

    @Test
    void keyTextSqlEqualsTheJvmKeyText() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, " + Sampler.keyTextSql(List.of("name", "num")) + " FROM t ORDER BY id")) {
            int n = 0;
            while (rs.next()) {
                Row row = ROWS.get(rs.getInt(1));
                assertEquals(Sampler.keyText(Arrays.asList(row.name(), row.num() == null ? null : row.num().toString())),
                        rs.getString(2), "row " + row.id());
                n++;
            }
            assertEquals(ROWS.size(), n);
        }
    }

    @Test
    void scratchColumnsDoNotLeakAndColumnsAreUnchanged() throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(Sampler.sql(Spec.ofSize(1, 2), "t", List.of(), List.of("id"), List.of(), "__s_")
                     + " LIMIT 0")) {
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) cols.add(rs.getMetaData().getColumnName(i));
            assertEquals(List.of("id", "the group", "name", "num"), cols);
        }
    }

    private static List<Integer> jvm(Spec spec, List<String> key, boolean stratified) {
        List<List<String>> groups = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Row row : ROWS) {
            groups.add(stratified ? Arrays.asList(row.grp()) : List.of());
            List<String> parts = new ArrayList<>();
            for (String k : key)
                parts.add(switch (k) {
                    case "name" -> row.name();
                    case "num" -> row.num() == null ? null : row.num().toString();
                    case "id" -> Integer.toString(row.id());
                    default -> throw new IllegalArgumentException(k);
                });
            texts.add(Sampler.keyText(parts));
        }
        return Sampler.select(spec, groups, texts);   // indexes == ids
    }

    private static List<Integer> ids(String sql) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM (" + sql + ") ORDER BY id")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }
}
