package com.gamma.entitystore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * SP5 spike helper (TEST scope only - not a product feature): the DuckDB SQL for the node dictionary. Mirrors the
 * sealed {@code e164} normaliser of {@code EntityTypes.normalise} (a new normaliser would be a new sealed id, D-M9;
 * nothing is added here) and three longest-prefix strategies over a dial-digit prefix table.
 */
final class NodeDictionarySql {

    private NodeDictionarySql() {}

    /** JavaScript {@code \s} as an RE2 class (RE2's own {@code \s} misses \v and NBSP). */
    private static final String WS = "[\\x{9}\\x{A}\\x{B}\\x{C}\\x{D}\\x{20}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}"
            + "\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}]";

    /** Number of digits a BIGINT range key carries (E.164 allows at most 15). */
    static final int KEY_DIGITS = 15;

    static void macros(Statement st) throws SQLException {
        st.execute("CREATE OR REPLACE MACRO ws_trim(s) AS regexp_replace(s, '^" + WS + "+|" + WS + "+$', '', 'g')");
        st.execute("CREATE OR REPLACE MACRO e164_t(t) AS CASE "
                + "WHEN left(t,1)='+' THEN '+' || regexp_replace(t,'[^0-9]','','g') "
                + "WHEN regexp_replace(t,'[^0-9]','','g') LIKE '00%' THEN '+' || substr(regexp_replace(t,'[^0-9]','','g'),3) "
                + "ELSE regexp_replace(t,'[^0-9]','','g') END");
        st.execute("CREATE OR REPLACE MACRO e164(s) AS CASE WHEN e164_t(ws_trim(s))='+' THEN '' ELSE e164_t(ws_trim(s)) END");
    }

    /** A prefix row: dial digits (no '+'), plus its enrichment labels. */
    record Prefix(String prefix, String country, String operator, String range) {}

    /** Deterministic nested prefix table: ~80 two-digit countries, then operators, then ranges, up to {@code n} rows. */
    static List<Prefix> genPrefixes(int n, long seed) {
        Random r = new Random(seed);
        TreeSet<String> all = new TreeSet<>();
        List<String> level = new ArrayList<>();
        for (int c = 20; c <= 99 && all.size() < n; c++) { all.add("" + c); level.add("" + c); }
        int guard = 0;
        while (all.size() < n && guard++ < 50 * n) {
            String base = level.get(r.nextInt(level.size()));
            if (base.length() >= 6) continue;
            String p = base + (char) ('0' + r.nextInt(10))
                    + (r.nextBoolean() && base.length() < 5 ? "" + (char) ('0' + r.nextInt(10)) : "");
            if (all.add(p)) level.add(p);
        }
        List<Prefix> out = new ArrayList<>();
        for (String p : all) {
            String cc = p.substring(0, 2);
            out.add(new Prefix(p, "C" + cc, p.length() >= 4 ? "OP" + p.substring(0, Math.min(4, p.length())) : null,
                    p.length() >= 5 ? "R" + p : null));
        }
        return out;
    }

    static void loadPrefixes(Connection c, List<Prefix> pfx) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE pfx(idx INT, prefix VARCHAR, plen INT, country VARCHAR, operator VARCHAR, rng VARCHAR)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pfx VALUES (?,?,?,?,?,?)")) {
            int i = 0;
            for (Prefix p : pfx) {
                ps.setInt(1, i++); ps.setString(2, p.prefix()); ps.setInt(3, p.prefix().length());
                ps.setString(4, p.country()); ps.setString(5, p.operator()); ps.setString(6, p.range());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** SQL for ids gen(lo,hi): '+' prefix[i % P] then a zero-padded bijective suffix; 11-13 digits in all. */
    static String genIds(long lo, long hi, int pCount) {
        return "SELECT r.i AS i, '+' || p.prefix || lpad(CAST(((r.i // " + pCount + ") * 7919) % "
                + "CAST(pow(10, 11 + (r.i % 3) - p.plen) AS BIGINT) AS VARCHAR), CAST(11 + (r.i % 3) - p.plen AS INT), '0') AS id "
                + "FROM range(" + lo + "," + hi + ") r(i) JOIN pfx p ON p.idx = r.i % " + pCount;
    }

    /** One to three spellings of every id (rep 0..i%3): '+', '00', and a spaced/hyphenated one. */
    static String rawSpellings(String idsTable) {
        return "SELECT CASE rep WHEN 0 THEN id WHEN 1 THEN '00' || substr(id,2) "
                + "ELSE ' +' || substr(id,2,2) || '-' || substr(id,4) || ' ' END AS raw "
                + "FROM " + idsTable + ", range(0,3) s(rep) WHERE rep <= (i % 3)";
    }

    // ------------------------------------------------------------------ longest-prefix strategies

    /** Strategy A: one equi-join per prefix length, UNION ALL, keep the longest match. */
    static String lpmCascade(String nums, List<Integer> lengths) {
        StringBuilder u = new StringBuilder();
        for (int l : lengths) {
            if (u.length() > 0) u.append(" UNION ALL ");
            u.append("SELECT n.id, p.plen, p.country, p.operator, p.rng FROM ").append(nums)
                    .append(" n JOIN pfx p ON p.plen=").append(l).append(" AND substr(n.d,1,").append(l).append(")=p.prefix");
        }
        return best(u.toString());
    }

    /** Strategy C: unnest the candidate prefixes of every number, ONE hash join, keep the longest match. */
    static String lpmCandidates(String nums, List<Integer> lengths) {
        StringBuilder l = new StringBuilder();
        for (int len : lengths) { if (l.length() > 0) l.append(","); l.append("substr(d,1,").append(len).append(")"); }
        return best("SELECT c.id, p.plen, p.country, p.operator, p.rng FROM (SELECT id, unnest([" + l + "]) AS cand FROM "
                + nums + ") c JOIN pfx p ON p.prefix = c.cand");
    }

    private static String best(String matches) {
        return "SELECT id, max(plen) AS plen, arg_max(country, plen) AS country, arg_max(operator, plen) AS operator, "
                + "arg_max(rng, plen) AS rng FROM (" + matches + ") GROUP BY id";
    }

    /**
     * Strategy B: flatten the (nested) prefixes into DISJOINT sorted intervals over a 15-digit zero-padded key once,
     * then one ASOF join. Needs {@code length(d) >= plen} of the matched leaf, so numbers shorter than the longest
     * prefix must go to strategy C (documented limit).
     */
    static void loadIntervals(Connection c, List<Prefix> pfx) throws SQLException {
        record Iv(long lo, long hi, Prefix p) {}
        List<Iv> ivs = new ArrayList<>();
        for (Prefix p : pfx) {
            int len = p.prefix().length();
            long scale = (long) Math.pow(10, KEY_DIGITS - len), base = Long.parseLong(p.prefix()) * scale;
            ivs.add(new Iv(base, base + scale - 1, p));
        }
        ivs.sort((a, b) -> a.lo() != b.lo() ? Long.compare(a.lo(), b.lo()) : Long.compare(b.hi(), a.hi()));
        List<Object[]> out = new ArrayList<>();
        Deque<Iv> stack = new ArrayDeque<>();
        long cursor = 0;
        for (Iv iv : ivs) {
            while (!stack.isEmpty() && stack.peek().hi() < iv.lo()) {
                Iv top = stack.pop();
                if (cursor <= top.hi()) out.add(row(cursor, top.hi(), top.p()));
                cursor = top.hi() + 1;
            }
            if (!stack.isEmpty() && cursor <= iv.lo() - 1) out.add(row(cursor, iv.lo() - 1, stack.peek().p()));
            cursor = iv.lo();
            stack.push(iv);
        }
        while (!stack.isEmpty()) {
            Iv top = stack.pop();
            if (cursor <= top.hi()) out.add(row(cursor, top.hi(), top.p()));
            cursor = top.hi() + 1;
        }
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TABLE pfx_iv(lo BIGINT, hi BIGINT, plen INT, country VARCHAR, operator VARCHAR, rng VARCHAR)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO pfx_iv VALUES (?,?,?,?,?,?)")) {
            for (Object[] o : out) { for (int i = 0; i < 6; i++) ps.setObject(i + 1, o[i]); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    private static Object[] row(long lo, long hi, Prefix p) {
        return new Object[]{lo, hi, p.prefix().length(), p.country(), p.operator(), p.range()};
    }

    static String lpmAsof(String nums) {
        return "SELECT n.id, iv.plen, iv.country, iv.operator, iv.rng FROM (SELECT id, d, "
                + "CAST(rpad(substr(d,1,15),15,'0') AS BIGINT) AS k FROM " + nums + ") n "
                + "ASOF JOIN pfx_iv iv ON n.k >= iv.lo WHERE n.k <= iv.hi AND length(n.d) >= iv.plen";
    }

    static List<Integer> lengths(Connection c) throws SQLException {
        List<Integer> l = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT DISTINCT plen FROM pfx ORDER BY plen DESC")) {
            while (rs.next()) l.add(rs.getInt(1));
        }
        return l;
    }

    /** Digits-only form of an id; only '+'-ids (an international number) are enrichable. */
    static String numsOf(String idsTable) {
        return "SELECT id, substr(id,2) AS d FROM " + idsTable + " WHERE left(id,1)='+'";
    }

    static Map<String, String> fetch(Connection c, String sql) throws SQLException {
        Map<String, String> m = new TreeMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5));
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ the daily upsert

    /** Upsert one day: distinct+normalise raw, find ids absent from {@code dict}, enrich ONLY those, insert. Returns the new count. */
    static long upsertDay(Connection c, String dayRaw, List<Integer> lens) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE day_ids AS SELECT id FROM (SELECT DISTINCT e164(raw) AS id FROM "
                    + "(SELECT DISTINCT raw FROM " + dayRaw + ")) WHERE id <> ''");
            st.execute("CREATE OR REPLACE TEMP TABLE new_ids AS SELECT d.id FROM day_ids d ANTI JOIN dict ON dict.id = d.id");
            st.execute("CREATE OR REPLACE TEMP TABLE new_nums AS " + numsOf("new_ids"));
            st.execute("CREATE OR REPLACE TEMP TABLE new_enr AS " + lpmCandidates("new_nums", lens));
            st.execute("INSERT INTO dict SELECT n.id, e.plen, e.country, e.operator, e.rng FROM new_ids n LEFT JOIN new_enr e ON e.id = n.id");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM new_ids")) { rs.next(); return rs.getLong(1); }
        }
    }
}
