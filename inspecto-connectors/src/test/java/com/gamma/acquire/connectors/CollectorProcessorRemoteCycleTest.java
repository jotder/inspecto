package com.gamma.acquire.connectors;

import com.gamma.acquire.CircuitBreaker;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PROCESSOR-RELEASE-READINESS-1} gap G6 — the Collector features driven through a REAL poll cycle
 * ({@link CollectorProcessor#run} / {@link CollectorProcessor#acquire}) against real endpoints: an in-process
 * Apache MINA SFTP server and a DuckDB JDBC database. Until now the circuit breaker, the fetch rate limit and the
 * post-action failure path were exercised only as isolated units ({@code CircuitBreakerTest},
 * {@code RateLimiterTest}, {@code SftpConnectorTest#postMove…}), and the two end-to-end runs asserted no more than
 * "some output file exists".
 *
 * <p>Every test uses its own pipeline name (the breaker, stability gate and ledgers key on the collector id) and
 * lands only under {@code @TempDir}; the only sockets are the loopback SFTP server this class starts.
 */
class CollectorProcessorRemoteCycleTest {

    private static final String CONN = "g6-sftp";

    private SshServer sshd;
    private Path serverRoot;
    private int port;
    /** SSH sessions the server accepted — "did this cycle touch the endpoint at all?". */
    private final AtomicInteger sessions = new AtomicInteger();
    /** File names the server refuses to READ (an injected, server-side fetch fault). */
    private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void startServer(@TempDir Path tmp) throws Exception {
        serverRoot = Files.createDirectories(tmp.resolve("sftproot"));
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(KeyPairProvider.wrap(KeyPairGenerator.getInstance("RSA").genKeyPair()));
        sshd.setPasswordAuthenticator((u, p, s) -> "user".equals(u) && "pw".equals(p));
        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.addSftpEventListener(new SftpEventListener() {
            @Override
            public void reading(ServerSession session, String remoteHandle, FileHandle localHandle,
                                long offset, byte[] data, int dataOffset, int dataLen) throws IOException {
                if (unreadable.contains(localHandle.getFile().getFileName().toString()))
                    throw new IOException("injected read fault");
            }
        });
        sshd.setSubsystemFactories(List.of(sftp));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        sshd.addSessionListener(new SessionListener() {
            @Override public void sessionCreated(Session session) { sessions.incrementAndGet(); }
        });
        sshd.start();
        port = sshd.getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        ConnectionRegistry.remove(CONN);
        if (sshd != null) sshd.stop(true);
    }

    private void registerSftp(String password) {
        ConnectionRegistry.register(new ConnectionProfile(CONN, "sftp", "127.0.0.1", port, null, "/",
                "user", password, Map.of(), null));
    }

    // ── (1) circuit breaker ──────────────────────────────────────────────────────────────────

    /**
     * Two consecutive connectivity failures trip the breaker (threshold 2); while it is OPEN the next cycle
     * skips acquisition WITHOUT opening a session — even though the endpoint is healthy again by then — and
     * after the cooldown one half-open trial goes through, succeeds, closes the breaker and ingests the file.
     */
    @Test
    void breakerTripsAfterThresholdSkipsWhileOpenAndRecoversAfterCooldown(@TempDir Path dir) throws Exception {
        Files.writeString(serverRoot.resolve("20200403_cb.csv"), csv("cb", 3));
        String name = "G6_BREAKER";
        PipelineConfig cfg = load(dir, name, sftpCollector("""
                  circuit_breaker:
                    failure_threshold: 2
                    cooldown: 2s
                """));
        String sourceId = cfg.collector().id();
        CircuitBreaker.shared().forget(sourceId);
        try {
            registerSftp("WRONG");   // every connect fails authentication

            assertThrows(IOException.class, () -> CollectorProcessor.run(cfg), "failure 1 surfaces");
            assertEquals(CircuitBreaker.State.CLOSED, CircuitBreaker.shared().state(sourceId),
                    "one failure is under the threshold");
            assertThrows(IOException.class, () -> CollectorProcessor.run(cfg), "failure 2 surfaces");
            assertEquals(CircuitBreaker.State.OPEN, CircuitBreaker.shared().state(sourceId),
                    "the second consecutive failure trips the breaker");
            int sessionsAtTrip = sessions.get();
            assertEquals(2, sessionsAtTrip, "each failing cycle dialled the endpoint once");

            registerSftp("pw");      // the endpoint is healthy again — but the breaker has not cooled down
            assertDoesNotThrow(() -> CollectorProcessor.run(cfg), "an OPEN breaker skips quietly, it does not fail");
            assertEquals(sessionsAtTrip, sessions.get(), "while OPEN the cycle never dials the endpoint");
            assertEquals(0, rows(cfg), "and nothing is acquired");

            Thread.sleep(2_200);     // past the 2s cooldown → one HALF_OPEN trial is allowed
            CollectorProcessor.run(cfg);
            assertEquals(sessionsAtTrip + 1, sessions.get(), "after the cooldown the trial dials once");
            assertEquals(CircuitBreaker.State.CLOSED, CircuitBreaker.shared().state(sourceId),
                    "a successful trial closes the breaker");
            assertEquals(3, rows(cfg), "and the file is acquired and ingested");
        } finally {
            CircuitBreaker.shared().forget(sourceId);
        }
    }

    // ── (2) fetch.rate_limit ─────────────────────────────────────────────────────────────────

    /**
     * {@code fetch.rate_limit: 8KB/s} over four files of ~7 KB each (each under the one-second burst — see the
     * disabled test below for why): the bucket starts full, so the first file is free and each further one
     * waits ≈ 0.7–0.9 s — the acquisition half of the cycle must take ≥ ~2.4 s. Unthrottled, the same fetch over
     * loopback takes a few hundred milliseconds.
     */
    @Test
    void rateLimitThrottlesTheAcquisitionHalfOfTheCycle(@TempDir Path dir) throws Exception {
        long rowsTotal = 0;
        for (int i = 1; i <= 4; i++) {
            String body = csvOfAtLeast("rl" + i, 7_000);
            assertTrue(body.length() < 8 * 1024, "each file stays under the burst capacity");
            rowsTotal += body.lines().count() - 1;
            Files.writeString(serverRoot.resolve("2020040" + i + "_rl.csv"), body);
        }
        registerSftp("pw");
        PipelineConfig cfg = load(dir, "G6_THROTTLE", sftpCollector("""
                  fetch:
                    rate_limit: 8KB/s
                """));
        assertEquals(8 * 1024, cfg.collector().fetch().rateLimitBytesPerSec(), "the key parsed as bytes/s");

        long t0 = System.nanoTime();
        int landed = CollectorProcessor.acquire(cfg);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(4, landed, "all four files are fetched — throttled, not refused");
        assertTrue(elapsedMs >= 1_800,
                "~28 KB at 8 KiB/s with a 1 s burst needs ≥ ~2.4 s of fetch, took " + elapsedMs + " ms");

        CollectorProcessor.ingest(cfg, null);
        assertEquals(rowsTotal, rows(cfg), "the throttled bytes are complete: every row reaches the sink");
    }

    /**
     * 🔴 {@code RATE-LIMIT-OVERSIZE-HANGS-1}: a remote file LARGER than {@code rate_limit} bytes (one second's
     * worth) hangs the acquisition forever. {@code RateLimiter.acquire} waits until the bucket holds
     * {@code bytes} tokens, but {@code refill} caps the bucket at one second of rate, so a request above that
     * cap can never be satisfied — contradicting its own javadoc ("never deadlocks on an over-large request").
     * Found by the first draft of the test above, which hung the surefire fork for ten minutes. Enable this
     * once the limiter is fixed.
     */
    @Test
    @org.junit.jupiter.api.Disabled("RATE-LIMIT-OVERSIZE-HANGS-1 — a file above one second of rate_limit never fetches")
    void aFileLargerThanOneSecondOfRateIsThrottledNotHung(@TempDir Path dir) throws Exception {
        Files.writeString(serverRoot.resolve("20200403_big.csv"), csvOfAtLeast("big", 6 * 1024));
        registerSftp("pw");
        PipelineConfig cfg = load(dir, "G6_THROTTLE_BIG", sftpCollector("""
                  fetch:
                    rate_limit: 4KB/s
                """));
        int landed = assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                () -> CollectorProcessor.acquire(cfg), "a 6 KB file at 4 KiB/s must take ~0.5 s, not forever");
        assertEquals(1, landed);
    }

    // ── (3) post_action: MOVE ────────────────────────────────────────────────────────────────

    /**
     * {@code post_action: MOVE} (land-then-ack): the file whose fetch succeeds is moved into {@code archive_path}
     * on the server; the file whose fetch FAILS (a server-side read fault) is left exactly where it was, is not
     * landed and is not ingested. When the fault clears, the next cycle fetches, moves and ingests it — the
     * failure deferred the file, it did not lose it.
     */
    @Test
    void moveArchivesAFetchedFileAndLeavesAFailedOneInPlace(@TempDir Path dir) throws Exception {
        Files.writeString(serverRoot.resolve("20200403_good.csv"), csv("good", 2));
        Files.writeString(serverRoot.resolve("20200404_bad.csv"), csv("bad", 4));
        unreadable.add("20200404_bad.csv");
        registerSftp("pw");
        // recursive_depth: 1 keeps archive/ out of discovery — see the disabled test below for why it must.
        PipelineConfig cfg = load(dir, "G6_MOVE", sftpCollector("""
                  recursive_depth: 1
                  post_action:
                    on_success: MOVE
                    archive_path: archive
                """));

        CollectorProcessor.run(cfg);

        assertFalse(Files.exists(serverRoot.resolve("20200403_good.csv")), "the fetched file left the root");
        assertTrue(Files.exists(serverRoot.resolve("archive/20200403_good.csv")), "…into archive_path");
        assertTrue(Files.exists(serverRoot.resolve("20200404_bad.csv")), "the failed fetch is NOT moved");
        assertFalse(Files.exists(serverRoot.resolve("archive/20200404_bad.csv")), "…and not archived");
        assertFalse(Files.exists(Path.of(cfg.dirs().poll()).resolve("20200404_bad.csv")),
                "a failed fetch never lands in the inbox");
        assertEquals(List.of("good_1", "good_2"), ids(cfg), "only the fetched file reached the sink");

        unreadable.clear();
        CollectorProcessor.run(cfg);

        assertFalse(Files.exists(serverRoot.resolve("20200404_bad.csv")), "retried next cycle and moved");
        assertTrue(Files.exists(serverRoot.resolve("archive/20200404_bad.csv")));
        assertEquals(List.of("bad_1", "bad_2", "bad_3", "bad_4", "good_1", "good_2"), ids(cfg),
                "the deferred file is ingested once, the first one is not re-ingested");
    }

    /**
     * {@code POST-ACTION-MOVE-RECOLLECTS-ARCHIVE-1}: with the default unbounded {@code recursive_depth}, an
     * {@code archive_path} under the collector's own root used to be DISCOVERED on the next cycle — the archived
     * file has a new relative path ({@code archive/…}), so no marker matched it: it was ingested a SECOND time and
     * MOVEd again to {@code archive/archive/…}. Discovery now drops everything under a MOVE's archive tree.
     */
    @Test
    void anArchivedFileIsNotCollectedAgain(@TempDir Path dir) throws Exception {
        Files.writeString(serverRoot.resolve("20200403_once.csv"), csv("once", 2));
        registerSftp("pw");
        PipelineConfig cfg = load(dir, "G6_MOVE_LOOP", sftpCollector("""
                  post_action:
                    on_success: MOVE
                    archive_path: archive
                """));

        CollectorProcessor.run(cfg);
        CollectorProcessor.run(cfg);

        assertEquals(List.of("once_1", "once_2"), ids(cfg), "the archived file must not be ingested again");
        assertTrue(Files.exists(serverRoot.resolve("archive/20200403_once.csv")), "it stays where MOVE put it");
        assertFalse(Files.exists(serverRoot.resolve("archive/archive/20200403_once.csv")), "not re-archived");
    }

    // ── (4) full Pipeline runs: acquire → parse → sink ───────────────────────────────────────

    /** SFTP collector → CSV parse → Parquet sink: the exact source rows arrive, typed, and a re-run adds none. */
    @Test
    void sftpPipelineLandsTheSourceRowsInTheSink(@TempDir Path dir) throws Exception {
        Files.createDirectories(serverRoot.resolve("sub"));
        Files.writeString(serverRoot.resolve("20200403_a.csv"), csv("a", 2));
        Files.writeString(serverRoot.resolve("sub/20200404_b.csv"), csv("b", 1));
        Files.writeString(serverRoot.resolve("notes.txt"), "not a feed file");
        registerSftp("pw");
        PipelineConfig cfg = load(dir, "G6_SFTP_PIPE", sftpCollector(""));

        CollectorProcessor.run(cfg);

        assertEquals(List.of("a_1", "a_2", "b_1"), ids(cfg), "every CSV row, recursively, and nothing else");
        assertEquals("3.0", scalar(cfg, "SUM(AMT)::VARCHAR"), "AMT parsed as a number");
        assertEquals("2020-04-03", scalar(cfg, "MIN(EVENT_DATE)::VARCHAR"), "EVENT_DATE parsed as a date");

        CollectorProcessor.run(cfg);
        assertEquals(3, rows(cfg), "the re-run fetches and ingests nothing new");
    }

    /** JDBC collector ({@code connector: db}) over DuckDB → CSV parse → Parquet sink: the query's rows arrive. */
    @Test
    void jdbcPipelineLandsTheQueryRowsInTheSink(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("export_src.duckdb");
        String url = "jdbc:duckdb:" + db.toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cdr(ID VARCHAR, AMT DOUBLE, EVENT_DATE DATE)");
            st.execute("INSERT INTO cdr VALUES ('j_1', 1.5, DATE '2020-04-03'), ('j_2', 2.5, DATE '2020-04-04'),"
                    + " ('skip', 9.0, DATE '2020-04-05')");
        }   // closed: DuckDB is single-writer and the connector opens its own connection
        String id = "g6-db";
        ConnectionRegistry.register(new ConnectionProfile(id, "db", null, 0, null, null, null, null,
                Map.of("jdbc_url", url, "query", "SELECT * FROM cdr WHERE ID <> 'skip' ORDER BY ID",
                        "export_name", "cdr_export.csv"), null));
        try {
            PipelineConfig cfg = load(dir, "G6_JDBC_PIPE", "collector:\n  connector: db\n  connection: " + id + "\n");

            CollectorProcessor.run(cfg);

            assertEquals(List.of("j_1", "j_2"), ids(cfg), "exactly the query's rows (the WHERE held)");
            assertEquals("4.0", scalar(cfg, "SUM(AMT)::VARCHAR"));
        } finally {
            ConnectionRegistry.remove(id);
        }
    }

    // ── harness ──────────────────────────────────────────────────────────────────────────────

    /** {@code n} rows {@code <prefix>_1..n}, AMT 1.0 each, dated 2020-04-03, with a header. */
    private static String csv(String prefix, int n) {
        StringBuilder sb = new StringBuilder("ID,AMT,EVENT_DATE\n");
        for (int i = 1; i <= n; i++) sb.append(prefix).append('_').append(i).append(",1.0,2020-04-03\n");
        return sb.toString();
    }

    private static String csvOfAtLeast(String prefix, int bytes) {
        StringBuilder sb = new StringBuilder("ID,AMT,EVENT_DATE\n");
        for (int i = 1; sb.length() < bytes; i++) sb.append(prefix).append('_').append(i).append(",1.0,2020-04-03\n");
        return sb.toString();
    }

    private static String sftpCollector(String extra) {
        return "collector:\n  connector: sftp\n  connection: " + CONN + "\n" + extra;
    }

    private static PipelineConfig load(Path dir, String name, String collectorBlock) throws Exception {
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = "name: " + name + "\nversion: 1\n" + """
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            output:
              format: PARQUET
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/")) + collectorBlock;
        Path p = dir.resolve(name.toLowerCase() + "_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    /** The sink's committed Parquet files (never the {@code .staging} scratch), as a DuckDB list literal. */
    private static String files(PipelineConfig cfg) throws IOException {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.isDirectory(db)) return null;
        try (var w = Files.walk(db)) {
            List<String> f = w.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains(".staging"))
                    .map(p -> "'" + p.toAbsolutePath().toString().replace("\\", "/") + "'").sorted().toList();
            return f.isEmpty() ? null : "[" + String.join(",", f) + "]";
        }
    }

    private static boolean sinkWritten(PipelineConfig cfg) throws IOException {
        return files(cfg) != null;
    }

    private static long rows(PipelineConfig cfg) throws Exception {
        return sinkWritten(cfg) ? Long.parseLong(scalar(cfg, "COUNT(*)::VARCHAR")) : 0L;
    }

    private static String scalar(PipelineConfig cfg, String expr) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + expr + " FROM read_parquet(" + files(cfg) + ")")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> ids(PipelineConfig cfg) throws Exception {
        List<String> out = new ArrayList<>();
        if (!sinkWritten(cfg)) return out;
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT ID FROM read_parquet(" + files(cfg) + ") ORDER BY ID")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
