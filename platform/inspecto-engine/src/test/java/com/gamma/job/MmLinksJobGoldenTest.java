package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-mobile-money} Space Template RUN end to end from a verbatim copy: the three Pipelines ingest the shipped
 * extracts, then the {@code mm_links} {@code sql.template} Job folds them into the link Dataset. Pins the Job's contract:
 * the columns, the six link kinds, one link per transaction, the shared_device / shared_id pairs (one direction, the
 * placeholders excluded) and the event-day column. The planted stories over the same corpus are pinned by
 * {@code LaMobileMoneyGoldenTest} in inspecto-la-graph.
 */
class MmLinksJobGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-mobile-money").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("wallet_txn", "wallet_master", "agent_master");

    @TempDir static Path tmp;
    private static Path data;
    private static List<Map<String, Object>> links;

    @BeforeAll
    static void runThePipelinesAndTheJob() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-mobile-money");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
        }
        data = space.resolve("data");
        JobConfig job = JobConfig.load(space.resolve("config/jobs/mm_links_job.toon").toString());
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess",
                "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform)) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            assertTrue(js.triggerRun("mm_links", null, Map.of()).isPresent());
            JobRun r = awaitRun(js, "mm_links");
            assertNotNull(r, "mm_links never ran");
            assertEquals("SUCCESS", r.status(), "mm_links failed: " + r.message());
        }
        links = rows("SELECT * FROM " + parquet("mm_links"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"), new ArrayList<>(links.get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> l : rows("SELECT kind, count(*) AS n FROM " + parquet("mm_links") + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) l.get("kind"), (Long) l.get("n"));
        assertEquals(List.of("cash_in", "cash_out", "merchant_pay", "p2p", "shared_device", "shared_id"), new ArrayList<>(perKind.keySet()));
    }

    @Test
    void oneLinkPerTransactionAndEveryLinkHasAnEventDay() throws Exception {
        long txns = count("SELECT count(*) FROM " + parquet("wallet_txn/database", "/**/*.parquet"));
        assertEquals(5732, txns, "the landed corpus");
        assertEquals(txns, count("SELECT count(*) FROM " + parquet("mm_links") + " WHERE kind IN ('p2p', 'cash_in', 'cash_out', 'merchant_pay')"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("mm_links") + " WHERE event_date IS NULL OR ts IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("mm_links") + " WHERE kind IN ('p2p', 'cash_in', 'cash_out', 'merchant_pay') AND amount IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("mm_links") + " WHERE kind LIKE 'shared_%' AND amount IS NOT NULL"));
    }

    @Test
    void sharedLinksAreOneDirectionPlaceholdersNeverLinkAndTheFarmIsOneHandset() throws Exception {
        String t = parquet("mm_links");
        assertEquals(0, count("SELECT count(*) FROM " + t + " WHERE kind LIKE 'shared_%' AND src >= dst"), "lower id to higher id");
        assertEquals(0, count("SELECT count(*) FROM " + t + " WHERE kind LIKE 'shared_%' AND (via ILIKE '%UNKNOWN%' OR via ILIKE '%:000000000000000' OR via ILIKE '%PENDING%' OR via IN ('device:', 'id:'))"),
                "a blank or placeholder value is never a link");
        assertEquals(36, count("SELECT count(*) FROM " + t + " WHERE kind = 'shared_device' AND via = 'device:DEV-FARM-01'"), "9 wallets, 36 pairs");
        assertEquals(6, count("SELECT count(*) FROM " + t + " WHERE kind = 'shared_device' AND via = 'device:DEV-FAMILY-01'"));
        assertEquals(3, count("SELECT count(*) FROM " + t + " WHERE kind = 'shared_id'"), "only the household of three");
        assertEquals(0, count("SELECT count(*) FROM " + t + " WHERE kind = 'shared_id' AND (src LIKE 'FARM-%' OR dst LIKE 'FARM-%')"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unused(PlatformServiceRegistry platform, String id, String iface) {
        Class type;
        try {
            type = Class.forName(iface);
        } catch (ClassNotFoundException e) {
            return;
        }
        platform.register(id, type, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return m.invoke(id, a);
                    throw new UnsupportedOperationException(id + " is not wired in this test");
                }));
    }

    private static long count(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static JobRun awaitRun(JobService js, String job) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = js.lastRunOf(job).orElse(null);
            if (r != null && !"SKIPPED".equals(r.status()) && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        return null;
    }

    private static String parquet(String sink) {
        return parquet(sink, "/*.parquet");
    }

    private static String parquet(String sink, String glob) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + glob + "')";
    }

    private static List<Map<String, Object>> rows(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) r.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                out.add(r);
            }
        }
        assertFalse(out.isEmpty(), "no rows: " + sql);
        return out;
    }

    private static boolean hasFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> w = Files.walk(dir)) {
            return w.anyMatch(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."));
        }
    }
}
