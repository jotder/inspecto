package com.gamma.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ENGINE-INMEMORY-UNSEALED-1}: {@link DuckDbUtil#openInMemory} is sealed by default — no extension
 * autoload, external access only to the declared directories — and {@link DuckDbUtil#openInMemoryWithFileAccess}
 * is the named opt-in that reaches local files but still never the network. Each negative has a twin on a bare
 * connection that MUST succeed, and the network negatives count stub requests (the JDBC error text is generic).
 */
class DuckDbInMemorySealTest {

    /** A loopback CSV stub; {@code hits} counts every request (HEAD + GET). */
    private static HttpServer stub(AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/x.csv", ex -> {
            hits.incrementAndGet();
            byte[] body = ("k,v" + (char) 10 + "remote,1" + (char) 10).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/csv");
            ex.sendResponseHeaders(200, "HEAD".equals(ex.getRequestMethod()) ? -1 : body.length);
            try (var os = ex.getResponseBody()) { if (!"HEAD".equals(ex.getRequestMethod())) os.write(body); }
        });
        server.start();
        return server;
    }

    private static String httpRead(HttpServer s) {
        return "SELECT * FROM read_csv('http://127.0.0.1:" + s.getAddress().getPort() + "/x.csv')";
    }

    private static String sql(Path p) {
        return "'" + p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''") + "'";
    }

    private static boolean runs(Connection c, String q) {
        try (Statement st = c.createStatement()) {
            st.execute(q);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    @Test
    void theDefaultNeverReachesTheNetworkEvenWithAutoloadSwitchedBackOn() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = stub(hits);
        try {
            DuckDbUtil.loadDriver();
            try (Connection bare = DriverManager.getConnection("jdbc:duckdb:")) {
                assertTrue(runs(bare, httpRead(server)), "twin: a bare connection reads the stub");
            }
            assertTrue(hits.get() > 0, "twin: the probe must reach the stub on a bare connection");
            hits.set(0);
            try (Connection c = DuckDbUtil.openInMemory(null)) {
                assertFalse(runs(c, httpRead(server)));
                runs(c, "SET autoload_known_extensions=true");   // not locked — and still no way out
                runs(c, "SET autoinstall_known_extensions=true");
                assertFalse(runs(c, httpRead(server)));
                assertFalse(runs(c, "LOAD httpfs"), "an extension LOAD is refused once sealed");
            }
            assertEquals(0, hits.get(), "the sealed connection reached the network");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDeclaredDirectoryIsReachableAndAnUndeclaredOneIsNot(@TempDir Path tmp) throws Exception {
        Path allowed = Files.createDirectories(tmp.resolve("allowed"));
        Path other = Files.createDirectories(tmp.resolve("allowed-other"));   // shares the prefix, not the dir
        try (Connection c = DuckDbUtil.openInMemory(null, List.of(allowed))) {
            assertTrue(runs(c, "COPY (SELECT 1 AS a) TO " + sql(allowed.resolve("x.parquet")) + " (FORMAT PARQUET)"));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet(" + sql(allowed.resolve("x.parquet")) + ")")) {
                rs.next();
                assertEquals(1, rs.getLong(1));
            }
            assertFalse(runs(c, "COPY (SELECT 1 AS a) TO " + sql(other.resolve("x.parquet")) + " (FORMAT PARQUET)"));
            assertFalse(runs(c, "SET allowed_directories=[" + sql(other) + "]"), "the seal cannot be widened");
            assertFalse(runs(c, "SET enable_external_access=true"), "the seal cannot be lifted");
            assertTrue(runs(c, "SET threads=2"), "other settings stay settable (no lock by default)");
        }
        assertFalse(Files.exists(other.resolve("x.parquet")));
        try (Connection c = DuckDbUtil.openInMemory(null)) {
            assertFalse(runs(c, "SELECT * FROM read_parquet(" + sql(allowed.resolve("x.parquet")) + ")"),
                    "no declared directory ⇒ no file at all");
        }
    }

    @Test
    void theFileAccessOptInReadsAnyLocalFileButNeverTheNetwork(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("anywhere.parquet");
        try (Connection w = DuckDbUtil.openInMemory(null, List.of(tmp))) {
            assertTrue(runs(w, "COPY (SELECT 1 AS a) TO " + sql(f) + " (FORMAT PARQUET)"));
        }
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = stub(hits);
        try (Connection c = DuckDbUtil.openInMemoryWithFileAccess(null, "test")) {
            assertTrue(runs(c, "SELECT * FROM read_parquet(" + sql(f) + ")"), "an undeclared local file is readable");
            assertFalse(runs(c, httpRead(server)));
        } finally {
            server.stop(0);
        }
        assertEquals(0, hits.get(), "the file-access opt-in reached the network");
        assertThrows(IllegalArgumentException.class, () -> DuckDbUtil.openInMemoryWithFileAccess(null, " "));
    }
}
