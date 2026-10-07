package com.gamma.entitystore;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SP5 MEASUREMENT (not a test): node dictionary, E.164 normalisation, longest-prefix enrichment, daily upsert.
 * Gated: {@code -Dbench.run=true}; sizes {@code -Dbench.n=} (dictionary D, default 1,000,000), {@code -Dbench.prefixes=}
 * (default 1000), {@code -Dbench.newPct=} (new share of the day, default 1). Each figure = 3 runs, min/median in ms.
 * Findings: {@code docs/superpower/link-analysis-roadmap.md} "SP5 findings". File-backed DuckDB (real storage), all
 * cores; the machine is shared, so every number is noisy.
 */
class NodeDictionaryBench {

    private static final StringBuilder OUT = new StringBuilder();

    private static void say(String s) { System.out.println(s); OUT.append(s).append('\n'); }

    private static double[] time3(ThrowingRunnable setup, ThrowingRunnable run) throws Exception {
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) {
            setup.run();
            long t0 = System.nanoTime();
            run.run();
            t[i] = (System.nanoTime() - t0) / 1e6;
        }
        java.util.Arrays.sort(t);
        return t;
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static String fmt(double[] t) { return String.format("min %.0f / median %.0f ms", t[0], t[1]); }

    private static long one(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }

    @Test
    void measure() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"), "pass -Dbench.run=true to run the SP5 measurement");
        long n = Long.getLong("bench.n", 1_000_000L);
        int np = Integer.getInteger("bench.prefixes", 1000);
        double newPct = Double.parseDouble(System.getProperty("bench.newPct", "1"));
        long dayDistinct = n / 5 + 0;                     // U: a day touches a fifth of the dictionary (i % 5 = 0) plus the new ones
        long newCount = Math.max(1, Math.round(dayDistinct * newPct / 100.0));
        Path dir = Files.createTempDirectory("sp5-bench");
        Path db = dir.resolve("sp5.duckdb");
        say("## SP5 run: D=" + n + " prefixes=" + np + " U=" + dayDistinct + " new=" + newCount + " cores="
                + Runtime.getRuntime().availableProcessors() + " load-note=" + System.getProperty("bench.load", "unrecorded"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:" + db)) {
            try (Statement st = c.createStatement()) { NodeDictionarySql.macros(st); }
            var pfx = NodeDictionarySql.genPrefixes(np, 42);
            NodeDictionarySql.loadPrefixes(c, pfx);
            NodeDictionarySql.loadIntervals(c, pfx);
            List<Integer> lens = NodeDictionarySql.lengths(c);
            say("prefix table rows=" + pfx.size() + " distinct lengths=" + lens + " flattened intervals="
                    + one(c, "SELECT count(*) FROM pfx_iv"));

            long ts = System.nanoTime();
            exec(c, "CREATE TABLE ids AS SELECT DISTINCT ON (id) i, id FROM (" + NodeDictionarySql.genIds(0, n, pfx.size()) + ")");
            exec(c, "CREATE TABLE nums AS " + NodeDictionarySql.numsOf("ids"));

            say("    setup: generate ids+nums " + (System.nanoTime() - ts) / 1_000_000 + " ms");
            // (a) normalisation: raw spellings (1-3 per id) -> distinct E.164
            exec(c, "CREATE TABLE raw_all AS " + NodeDictionarySql.rawSpellings("ids"));
            long rawRows = one(c, "SELECT count(*) FROM raw_all");
            long rawDistinct = one(c, "SELECT count(DISTINCT raw) FROM raw_all");
            double[] normDistinctFirst = time3(() -> exec(c, "DROP TABLE IF EXISTS norm"),
                    () -> exec(c, "CREATE TABLE norm AS SELECT DISTINCT e164(raw) AS id FROM (SELECT DISTINCT raw FROM raw_all)"));
            double[] normAll = time3(() -> exec(c, "DROP TABLE IF EXISTS norm2"),
                    () -> exec(c, "CREATE TABLE norm2 AS SELECT DISTINCT e164(raw) AS id FROM raw_all"));
            say("(a) raw rows=" + rawRows + " distinct raw=" + rawDistinct + " -> distinct ids=" + one(c, "SELECT count(*) FROM norm"));
            say("    distinct-raw-first then normalise: " + fmt(normDistinctFirst) + "; normalise every row then distinct: " + fmt(normAll));

            // (b) longest-prefix strategies over all D numbers
            // timed as count(*)+sum(plen) over the query so the figure is the match, not a table write; tables built once after
            double[] a = time3(() -> { }, () -> one(c, "SELECT count(*) + sum(plen) FROM (" + NodeDictionarySql.lpmCascade("nums", lens) + ")"));
            double[] cd = time3(() -> { }, () -> one(c, "SELECT count(*) + sum(plen) FROM (" + NodeDictionarySql.lpmCandidates("nums", lens) + ")"));
            double[] as = time3(() -> { }, () -> one(c, "SELECT count(*) + sum(plen) FROM (" + NodeDictionarySql.lpmAsof("nums") + ")"));
            exec(c, "CREATE TABLE r_a AS " + NodeDictionarySql.lpmCascade("nums", lens));
            exec(c, "CREATE TABLE r_c AS " + NodeDictionarySql.lpmCandidates("nums", lens));
            exec(c, "CREATE TABLE r_b AS " + NodeDictionarySql.lpmAsof("nums"));
            say("(b) LPM of " + n + " numbers vs " + pfx.size() + " prefixes: A cascade-of-equi-joins " + fmt(a)
                    + " | C candidate-list single join " + fmt(cd) + " | B ASOF on flattened intervals " + fmt(as));
            long diff = one(c, "SELECT count(*) FROM r_c c FULL JOIN r_b b USING(id) WHERE c.plen IS DISTINCT FROM b.plen")
                    + one(c, "SELECT count(*) FROM r_c c FULL JOIN r_a a USING(id) WHERE c.plen IS DISTINCT FROM a.plen");
            say("    strategy disagreement rows (must be 0): " + diff + "; matched rows=" + one(c, "SELECT count(*) FROM r_c"));

            // dictionary = ids + enrichment (file-backed), sorted by id? no: insertion order
            exec(c, "CREATE TABLE dict AS SELECT n.id, e.plen, e.country, e.operator, e.rng FROM ids n LEFT JOIN r_c e ON e.id = n.id");
            exec(c, "CHECKPOINT");
            long before = one(c, "SELECT used_blocks * block_size FROM pragma_database_size()");
            exec(c, "CREATE TABLE dict_bak AS SELECT * FROM dict");
            exec(c, "CHECKPOINT");
            say("    dictionary table alone on disk = " + (one(c, "SELECT used_blocks * block_size FROM pragma_database_size()") - before) / (1024 * 1024) + " MB = "
                    + String.format("%.1f", (one(c, "SELECT used_blocks * block_size FROM pragma_database_size()") - before) / (double) one(c, "SELECT count(*) FROM dict")) + " bytes/number (VARCHAR id + plen + 3 label columns)");
            say("    dictionary rows=" + one(c, "SELECT count(*) FROM dict") + ", database file after checkpoint (dict + dict_bak + work tables) = "
                    + Files.size(db) / (1024 * 1024) + " MB");

            // (c) daily upsert: day = (U-new) existing ids at stride 7 + new ids n..n+new, spelled several ways
            exec(c, "CREATE TABLE day_ids0 AS SELECT i, id FROM (" + NodeDictionarySql.genIds(0, n + newCount, pfx.size())
                    + ") WHERE i >= " + n + " OR i % 5 = 0");
            exec(c, "CREATE TABLE day_raw AS " + NodeDictionarySql.rawSpellings("day_ids0")
                    + " UNION ALL SELECT x FROM (VALUES ('N/A'),('+'),('00'),('')) j(x)");
            say("(c) day: distinct ids=" + one(c, "SELECT count(*) FROM day_ids0") + " raw rows=" + one(c, "SELECT count(*) FROM day_raw"));
            long[] added = new long[1];
            double[] upsert = time3(() -> { exec(c, "DROP TABLE dict"); exec(c, "CREATE TABLE dict AS SELECT * FROM dict_bak"); },
                    () -> added[0] = NodeDictionarySql.upsertDay(c, "day_raw", lens));
            say("    DAILY UPSERT (normalise day + anti-join dict + enrich new + insert): " + fmt(upsert) + ", new ids=" + added[0]);
            // upsert broken into steps, once, on a fresh dictionary
            exec(c, "DROP TABLE dict"); exec(c, "CREATE TABLE dict AS SELECT * FROM dict_bak");
            long t0 = System.nanoTime();
            exec(c, "CREATE OR REPLACE TEMP TABLE day_ids AS SELECT id FROM (SELECT DISTINCT e164(raw) AS id FROM (SELECT DISTINCT raw FROM day_raw)) WHERE id <> ''");
            long t1 = System.nanoTime();
            exec(c, "CREATE OR REPLACE TEMP TABLE new_ids AS SELECT d.id FROM day_ids d ANTI JOIN dict ON dict.id = d.id");
            long t2 = System.nanoTime();
            exec(c, "CREATE OR REPLACE TEMP TABLE new_nums AS " + NodeDictionarySql.numsOf("new_ids"));
            exec(c, "CREATE OR REPLACE TEMP TABLE new_enr AS " + NodeDictionarySql.lpmCandidates("new_nums", lens));
            long t3 = System.nanoTime();
            exec(c, "INSERT INTO dict SELECT n.id, e.plen, e.country, e.operator, e.rng FROM new_ids n LEFT JOIN new_enr e ON e.id = n.id");
            long t4 = System.nanoTime();
            say(String.format("    steps (1 run): normalise-day %.0f ms | find-new (anti-join vs dict of %d) %.0f ms | enrich-new %.0f ms | insert %.0f ms",
                    (t1 - t0) / 1e6, n, (t2 - t1) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6));

            // anti-join with a BIGINT key (E.164 digits fit in 8 bytes): physical optimisation of the key
            exec(c, "CREATE OR REPLACE TABLE dict_b AS SELECT CAST(substr(id,2) AS BIGINT) AS k FROM dict WHERE left(id,1)='+'");
            exec(c, "CREATE OR REPLACE TEMP TABLE day_b AS SELECT CAST(substr(id,2) AS BIGINT) AS k FROM day_ids WHERE left(id,1)='+'");
            double[] antiV = time3(() -> { }, () -> exec(c, "CREATE OR REPLACE TEMP TABLE nv AS SELECT d.id FROM day_ids d ANTI JOIN dict ON dict.id = d.id"));
            double[] antiB = time3(() -> { }, () -> exec(c, "CREATE OR REPLACE TEMP TABLE nb AS SELECT d.k FROM day_b d ANTI JOIN dict_b ON dict_b.k = d.k"));
            say("    find-new anti-join: VARCHAR key " + fmt(antiV) + " | BIGINT key " + fmt(antiB));

            // full re-derive: enrich EVERY number in the dictionary (prefix table changed)
            double[] rederive = time3(() -> exec(c, "DROP TABLE IF EXISTS dict2"),
                    () -> exec(c, "CREATE TABLE dict2 AS SELECT n.id, e.plen, e.country, e.operator, e.rng FROM (SELECT id FROM dict) n LEFT JOIN ("
                            + NodeDictionarySql.lpmCandidates("(SELECT id, substr(id,2) AS d FROM dict WHERE left(id,1)='+')", lens) + ") e ON e.id = n.id"));
            say("    FULL RE-DERIVE (LPM over all " + one(c, "SELECT count(*) FROM dict") + " + rebuild): " + fmt(rederive));
            say("    memory now (duckdb_memory): " + one(c, "SELECT coalesce(sum(memory_usage_bytes),0)//1048576 FROM duckdb_memory()") + " MB tracked; JVM heap used "
                    + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024) + " MB");
        } finally {
            String o = System.getProperty("bench.out");
            if (o != null) Files.writeString(Path.of(o), OUT.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            try (var w = Files.walk(dir)) { w.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}
