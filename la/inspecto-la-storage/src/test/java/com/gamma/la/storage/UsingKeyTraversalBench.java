package com.gamma.la.storage;

import com.gamma.la.storage.IndexReader.Edge;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C1 gate (plan D-LQ2): k-hop reachability over the link index, two ways, at
 * {@code inspecto.bench.sizes} edges (default 10^6 and 10^7):
 * <ul>
 *   <li><b>levels</b> - what {@link IndexedTraversal} and {@link SqlGraphEngine} do today: one {@link IndexReader#edges} round
 *       trip per level, an equality statement per frontier key on its bucket. Run here WITHOUT the 20-key cap, to see the cost
 *       the cap avoids; levels wider than {@code inspecto.bench.maxFrontier} keys are not attempted and are counted;</li>
 *   <li><b>using-key</b> - one {@code WITH RECURSIVE ... USING KEY (node)} statement over the index's {@code out} copy (each edge
 *       once, both directions), visited nodes read through {@code recurring}.</li>
 * </ul>
 * Undirected, depths 2, 3 and 5; the reached node sets must be equal wherever both ran. Prints, asserts only parity, and runs
 * only with {@code -Dinspecto.bench.dir=...}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class UsingKeyTraversalBench {

    private static final String DS = "g";
    private static final IndexMapping M = new IndexMapping("s", "t", "k", "ts", null, null, List.of());

    private static long num(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    @Test
    void levelsVersusUsingKey() throws Exception {
        Path root = Path.of(System.getProperty("inspecto.bench.dir")).resolve("using-key");
        int samples = (int) num("samples", 15);
        int maxFrontier = (int) num("maxFrontier", 2_000);
        for (String size : System.getProperty("inspecto.bench.sizes", "1000000,10000000").split(",")) {
            long edges = Long.parseLong(size.trim()), nodes = Math.max(1000, edges / 5);
            Path data = root.resolve("data-" + edges), idx = root.resolve("idx-" + edges);
            List<String> files = IndexScaleBench.plant(data, "e", edges, nodes, 0);
            IndexStore store = new IndexStore(idx, DS, M.hash());
            long t0 = System.nanoTime();
            IndexBuilder.Result built = IndexBuilder.build(new IndexBuilder.Request(DS, M, IndexScaleBench.relation(data, files), store,
                    "fp-" + edges, new IndexBuilder.Options(null, "6GB", null, null, pr -> { }), IndexScaleBench.stamps(data, files),
                    IndexBuilder.Mode.FULL, added -> IndexScaleBench.relation(data, added), List.of(data)));
            System.out.printf("UK N=%d index built in %d s%n", edges, (System.nanoTime() - t0) / 1_000_000_000);
            String glob = built.directory().toAbsolutePath().toString().replace('\\', '/') + "/out/*/*.parquet";
            run(edges, nodes, built, glob, samples, maxFrontier);
        }
    }

    private static void run(long edges, long nodes, IndexBuilder.Result built, String glob, int samples, int maxFrontier) throws Exception {
        try (IndexReader rd = IndexReader.open(built.directory(), built.manifest(), SqlSandboxPolicy.defaultPolicy());
             Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE VIEW e AS SELECT src, dst FROM read_parquet('" + glob + "')"
                        + " UNION ALL SELECT dst AS src, src AS dst FROM read_parquet('" + glob + "')");
                st.executeQuery("SELECT count(*) FROM e").close();              // warm the parquet metadata
            }
            rd.edges(List.of("n1"), List.of(Side.OUT), null, 1000);             // warm the reader
            for (int depth : new int[] {2, 3, 5}) {
                Random rnd = new Random(11);
                List<Long> lv = new ArrayList<>(), uk = new ArrayList<>();
                int tooWide = 0, mismatch = 0, timeouts = 0;
                long reachedSum = 0;
                for (int i = 0; i < samples; i++) {
                    String start = "n" + (long) (Math.pow(rnd.nextDouble(), 0.5) * nodes);  // spread over the skew, not only hubs
                    long t = System.nanoTime();
                    Set<String> byLevels;
                    try {
                        byLevels = levels(rd, start, depth, maxFrontier);
                    } catch (java.sql.SQLTimeoutException timedOut) {   // the index sandbox's own statement timeout
                        byLevels = null;
                        timeouts++;
                    }
                    long levelsNs = System.nanoTime() - t;
                    t = System.nanoTime();
                    Set<String> byKey = usingKey(c, start, depth);
                    uk.add(System.nanoTime() - t);
                    reachedSum += byKey.size();
                    if (byLevels == null) {
                        tooWide++;
                    } else {
                        lv.add(levelsNs);
                        if (!byLevels.equals(byKey)) mismatch++;
                    }
                }
                System.out.printf("UK N=%d depth=%d  levels %s (%d of %d not answered: %d timed out, the rest wider than %d keys)  using-key %s  reached avg %d  mismatches %d%n",
                        edges, depth, pct(lv), tooWide, samples, timeouts, maxFrontier, pct(uk), reachedSum / samples, mismatch);
                if (mismatch > 0) throw new AssertionError("reached sets differ at depth " + depth + ": " + mismatch);
            }
        }
    }

    /** BFS by one {@link IndexReader#edges} round trip per level; null when a level is wider than {@code maxFrontier} keys. */
    private static Set<String> levels(IndexReader rd, String start, int depth, int maxFrontier) throws Exception {
        Set<String> seen = new HashSet<>(List.of(start));
        List<String> frontier = List.of(start);
        for (int d = 0; d < depth && !frontier.isEmpty(); d++) {
            if (frontier.size() > maxFrontier) return null;
            Map<String, List<Edge>> out = rd.edges(frontier, List.of(Side.OUT, Side.IN), null, Integer.MAX_VALUE);
            List<String> next = new ArrayList<>();
            for (List<Edge> es : out.values())
                for (Edge e : es) if (seen.add(e.neighbour())) next.add(e.neighbour());
            frontier = next;
        }
        return seen;
    }

    /** Every node within {@code depth} hops, one recursive statement keyed on the node. */
    private static Set<String> usingKey(Connection c, String start, int depth) throws Exception {
        String sql = "WITH RECURSIVE r(node, depth) USING KEY (node) AS ("
                + " SELECT CAST(? AS VARCHAR), 0"
                + " UNION"
                + " SELECT DISTINCT e.dst, r.depth + 1 FROM r JOIN e ON e.src = r.node"
                + " WHERE r.depth < " + depth + " AND e.dst NOT IN (SELECT node FROM recurring.r))"
                + " SELECT node FROM r";
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, start);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String pct(List<Long> ns) {
        if (ns.isEmpty()) return "n/a";
        long[] s = ns.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(s);
        return String.format("p50 %.0f ms p95 %.0f ms", s[s.length / 2] / 1e6, s[Math.min(s.length - 1, (int) (s.length * 0.95))] / 1e6);
    }
}
