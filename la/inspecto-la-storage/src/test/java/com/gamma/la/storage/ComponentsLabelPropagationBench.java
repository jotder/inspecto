package com.gamma.la.storage;

import com.gamma.graph.GraphAlgorithms;
import com.gamma.graph.GraphAlgorithms.Edge;
import com.gamma.graph.GraphAlgorithms.Graph;
import com.gamma.graph.GraphAlgorithms.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DUCKDB-2-LOOP-QUERIES-1 C2 gate: connected components of a whole edge set, two ways, at {@code inspecto.bench.sizes} edges
 * (default 10^6 and 10^7):
 * <ul>
 *   <li><b>jvm</b> - every edge read into the JVM and {@link GraphAlgorithms#connectedComponents} (what the in-memory engine
 *       runs, today capped at 100 000 nodes);</li>
 *   <li><b>label propagation</b> - one {@code WITH RECURSIVE ... USING KEY (node)} statement: every node starts labelled with
 *       its own id, and each round a node takes the smallest label among its neighbours when that is smaller than its own;
 *       the fixed point labels every node with the smallest id of its component.</li>
 * </ul>
 * The two partitions must be the same set of node sets. Prints timings and the number of rounds; runs only with
 * {@code -Dinspecto.bench.dir=...}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class ComponentsLabelPropagationBench {

    static final String LABELS = "WITH RECURSIVE lp(node, lbl) USING KEY (node) AS ("
            + " SELECT DISTINCT src, src FROM e"
            + " UNION"
            + " SELECT e.dst, min(w.lbl) FROM lp w JOIN e ON e.src = w.node JOIN recurring.lp cur ON cur.node = e.dst"
            + " GROUP BY e.dst HAVING min(w.lbl) < min(cur.lbl))"
            + " SELECT node, lbl FROM lp";

    @Test
    void jvmVersusLabelPropagation() throws Exception {
        for (String size : System.getProperty("inspecto.bench.sizes", "1000000,10000000").split(",")) {
            long rows = Long.parseLong(size.trim()), nodes = Math.max(1000, rows / 5);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE edges AS SELECT 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2) % 1000000) / 1e6, 3)) AS BIGINT) AS src,"
                        + " 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2 + 1) % 1000000) / 1e6, 2)) AS BIGINT) AS dst FROM range(" + rows + ") r(i)");
                // a long chain, so the propagation needs many rounds: the honest worst case for an iterative method
                st.execute("INSERT INTO edges SELECT 'c' || i, 'c' || (i + 1) FROM range(" + Long.getLong("inspecto.bench.chain", 200) + ") r(i)");
                st.execute("CREATE TABLE e AS SELECT src, dst FROM edges UNION ALL SELECT dst, src FROM edges");

                long t = System.nanoTime();
                Set<Node> ns = new LinkedHashSet<>();
                List<Edge> es = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT src, dst FROM edges")) {
                    while (rs.next()) {
                        String s = rs.getString(1), d = rs.getString(2);
                        ns.add(new Node(s, s));
                        ns.add(new Node(d, d));
                        es.add(new Edge("e" + es.size(), s, d));
                    }
                }
                List<List<String>> jvm = GraphAlgorithms.connectedComponents(new Graph(new ArrayList<>(ns), es));
                long jvmMs = (System.nanoTime() - t) / 1_000_000;

                t = System.nanoTime();
                Map<String, Set<String>> byLabel = new HashMap<>();
                try (ResultSet rs = st.executeQuery(LABELS)) {
                    while (rs.next()) byLabel.computeIfAbsent(rs.getString(2), k -> new HashSet<>()).add(rs.getString(1));
                }
                long lpMs = (System.nanoTime() - t) / 1_000_000;

                Set<Set<String>> a = new HashSet<>(), b = new HashSet<>(byLabel.values());
                for (List<String> comp : jvm) a.add(new HashSet<>(comp));
                System.out.printf("C2 edges=%d nodes=%d components=%d largest=%d  jvm %d ms  label-propagation %d ms  same=%s%n",
                        rows, ns.size(), jvm.size(), jvm.get(0).size(), jvmMs, lpMs, a.equals(b));
                if (!a.equals(b)) throw new AssertionError("partitions differ at " + rows + " edges: " + a.size() + " vs " + b.size());
            }
        }
    }
}
