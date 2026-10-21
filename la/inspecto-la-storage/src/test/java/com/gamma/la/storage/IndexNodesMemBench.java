package com.gamma.la.storage;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-INDEX-SCALE-MEASURE-1, the memory bound of the FULL build's {@code nodes} and link-statistics phases: at a LOWERED
 * {@code buildMemory} the FULL build completes ({@link #theFullBuildCompletesUnderTheCap}), and each statement it replaced dies with
 * DuckDB's {@code Out of Memory Error} on the same corpus under the same connection settings - the one global fold of the whole edge
 * relation that made the node table ({@link #theReplacedNodesStatementDoesNotFitTheSameCap}, kept as
 * {@link IndexNodesFoldTest#referenceNodes}) and the per-key {@code count(DISTINCT)} of the degree statistics
 * ({@link #theReplacedDegreeStatementDoesNotFitTheSameCap}, kept in {@link IndexLinkStatsParityTest}). Never in the default suite
 * (tagged, gated).
 *
 * <p>Needs {@code -Dinspecto.bench.dir=<dir>} (the corpus {@code data-<edges>} is generated there when absent, as
 * {@link IndexScaleBench} does, and reused). Each replaced statement has its own measured point (2026-10-21, Windows laptop, 12 hardware
 * threads, DuckDB 1.5.6), the smallest where the {@code out} and {@code in} tables still fit and the replaced statement does not:
 * <ul>
 *   <li>nodes, and the full build ({@code edges} 3000000, {@code buildMemory} 64MB, {@code threads} 1: the defaults; below about 50 MB
 *       DuckDB's own floor fails both);</li>
 *   <li>degree ({@code degreeEdges} 30000000, {@code degreeMemory} 512MB, {@code degreeThreads} 2: the defaults; at the nodes point the
 *       links table itself does not fit, so the degree test has its own point). The full build also completes at this point (214 s).</li>
 * </ul>
 * The whole class runs at the defaults in a few minutes. Run one test with {@code -Dtest=IndexNodesMemBench#<method>}:
 * {@code mvn -o -pl :inspecto-la-storage test -Dtest=IndexNodesMemBench -Dinspecto.bench.dir=<dir>
 * -Dsurefire.failIfNoSpecifiedTests=false}. Mutation: restore the global statement in {@code IndexBuilder.copyNodes} (or the
 * {@code count(DISTINCT o)} degree statement in {@code linkStats}) and the full build goes red at that point with the
 * {@code Out of Memory Error}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class IndexNodesMemBench {

    private static final String DS = "g";
    private static final IndexMapping M = new IndexMapping("s", "t", "k", "ts", null, null, List.of());

    private final Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath();
    private final long edges = Long.getLong("inspecto.bench.edges", 3_000_000L);
    private final String memory = System.getProperty("inspecto.bench.buildMemory", "64MB");
    private final int threads = Integer.getInteger("inspecto.bench.threads", 1);
    private final long degreeEdges = Long.getLong("inspecto.bench.degreeEdges", 30_000_000L);
    private final String degreeMemory = System.getProperty("inspecto.bench.degreeMemory", "512MB");
    private final int degreeThreads = Integer.getInteger("inspecto.bench.degreeThreads", 2);

    private Path data;
    private List<String> files;

    private void corpus(long n, String cap, int nThreads) throws Exception {
        data = dir.resolve("data-" + n);
        long nodes = Math.max(1000, n / 5);
        if (!Files.isDirectory(data)) IndexScaleBench.plant(data, "base", n, nodes, 0);
        try (var s = Files.list(data)) {
            files = s.map(p -> p.getFileName().toString()).filter(x -> x.startsWith("base")).sorted().toList();
        }
        System.out.printf("NODESMEM edges=%,d files=%d cap=%s threads=%d%n", n, files.size(), cap, nThreads);
    }

    @Test
    void theFullBuildCompletesUnderTheCap() throws Exception {
        corpus(edges, memory, threads);
        Path idx = dir.resolve("idx-nodesmem-" + edges);
        long t0 = System.nanoTime();
        IndexBuilder.Result r = IndexBuilder.build(new IndexBuilder.Request(DS, M, IndexScaleBench.relation(data, files), new IndexStore(idx, DS, M.hash()),
                "fp-" + files.size(), new IndexBuilder.Options(null, memory, threads, null,
                        p -> System.out.printf("NODESMEM progress %s %d/%d at %tT%n", p.phase(), p.step(), p.steps(), new java.util.Date())),
                IndexScaleBench.stamps(data, files), IndexBuilder.Mode.FULL, added -> IndexScaleBench.relation(data, added), List.of(data)));
        System.out.printf("NODESMEM FULL build completed in %.1f s, %d buckets, %,d nodes, %,d edges, phase ms %s%n", (System.nanoTime() - t0) / 1e9,
                r.buckets(), r.nodes(), r.edges(), r.timingsMs());
        assertEquals(r.manifest(), IndexBuilder.verify(r.directory()));
        assertTrue(r.nodes() > 0);
    }

    @Test
    void theReplacedNodesStatementDoesNotFitTheSameCap() throws Exception {
        corpus(edges, memory, threads);
        Path work = Files.createDirectories(dir.resolve("work-nodesmem-" + edges));
        Path nodesOut = work.resolve("nodes");
        deleteTree(nodesOut);
        int buckets = BucketFunction.bucketsFor(edges);
        try (Connection c = DuckDbUtil.openInMemory(work.resolve(".spill"), List.of(data, work)); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");   // the builder's connection: sealed, spill in a real directory, then the caller's limits
            st.execute("SET memory_limit = '" + memory + "'");
            st.execute("SET threads = " + threads);
            st.execute(IndexNodesFoldTest.referenceEdgesView(IndexScaleBench.relation(data, files)));
            long t0 = System.nanoTime();
            SQLException e = assertThrows(SQLException.class, () -> st.execute(IndexNodesFoldTest.referenceNodes(buckets, nodesOut)),
                    "the replaced statement must not fit " + memory + " at " + edges + " edges; raise edges or lower the cap");
            System.out.printf("NODESMEM replaced statement failed after %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, e.getMessage().lines().findFirst().orElse(""));
            assertTrue(e.getMessage().contains("Out of Memory"), e.getMessage());
        } finally {
            deleteTree(work);
        }
    }

    @Test
    void theReplacedDegreeStatementDoesNotFitTheSameCap() throws Exception {
        corpus(degreeEdges, degreeMemory, degreeThreads);
        Path work = Files.createDirectories(dir.resolve("work-degreemem-" + degreeEdges));
        try (Connection c = DuckDbUtil.openInMemory(work.resolve(".spill"), List.of(data, work)); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("SET memory_limit = '" + degreeMemory + "'");
            st.execute("SET threads = " + degreeThreads);
            String table = "(SELECT CAST(s AS VARCHAR) AS src, CAST(t AS VARCHAR) AS dst, CAST(k AS VARCHAR) AS kind FROM (" + IndexScaleBench.relation(data, files)
                    + ") WHERE s IS NOT NULL AND t IS NOT NULL)";
            st.execute(IndexLinkStatsParityTest.referenceLinks("src", "dst", table));   // the links themselves fit: it is the per-key distinct that does not
            long t0 = System.nanoTime();
            SQLException e = assertThrows(SQLException.class, () -> st.execute(IndexLinkStatsParityTest.referenceDegree()),
                    "the replaced degree statement must not fit " + degreeMemory + " at " + degreeEdges + " edges; raise edges or lower the cap");
            System.out.printf("NODESMEM replaced degree statement failed after %.1f s: %s%n", (System.nanoTime() - t0) / 1e9, e.getMessage().lines().findFirst().orElse(""));
            assertTrue(e.getMessage().contains("Out of Memory"), e.getMessage());
        } finally {
            deleteTree(work);
        }
    }

    private static void deleteTree(Path d) throws Exception {
        if (!Files.exists(d)) return;
        try (var w = Files.walk(d)) {
            for (Path x : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(x);
        }
    }
}
