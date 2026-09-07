package com.gamma.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the hardened DuckDB connection — the native half of the M6 sandbox (gap G4). The point is the
 * {@code seal()} boundary: before it, the trusted oracle may read real partition files to register
 * views; after it, the untrusted candidate runs with no file access and a frozen configuration it
 * cannot re-open. So a sealed connection must reject both a config change and a file read.
 */
class SqlSandboxTest {

    private static final SqlSandboxPolicy POLICY = SqlSandboxPolicy.withCaps("512MB", 1, 10);

    @Test
    void runsTrustedQueryBeforeSeal() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY); Statement st = sb.statement()) {
            try (ResultSet rs = st.executeQuery("SELECT 21 + 21 AS answer")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("answer"));
            }
        }
    }

    @Test
    void readsRealFileBeforeSealButNotAfter(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("data.csv");
        Files.writeString(csv, "id,amt\n1,10\n2,20\n");
        String glob = csv.toAbsolutePath().toString().replace('\\', '/');

        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            // Trusted phase: external access is on, the oracle can read the legitimate partition file.
            try (Statement st = sb.statement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) AS n FROM read_csv('" + glob + "', header=true)")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("n"), "external access works before seal");
            }

            sb.seal();

            // Sealed: the untrusted candidate cannot reach the filesystem at all.
            try (Statement st = sb.statement()) {
                assertThrows(SQLException.class,
                        () -> st.executeQuery("SELECT * FROM read_csv('" + glob + "', header=true)"),
                        "external access must be denied after seal");
            }
        }
    }

    @Test
    void sealLocksConfiguration() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            sb.seal();
            assertTrue(sb.isSealed());
            try (Statement st = sb.statement()) {
                assertThrows(SQLException.class,
                        () -> st.execute("SET enable_external_access=true"),
                        "configuration is locked — the candidate cannot re-open access");
                assertThrows(SQLException.class,
                        () -> st.execute("SET memory_limit='8GB'"),
                        "configuration is locked — the candidate cannot raise its own caps");
            }
        }
    }

    @Test
    void sealIsIdempotent() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            sb.seal();
            sb.seal(); // must not throw
            assertTrue(sb.isSealed());
        }
    }

    /**
     * AUTHORING-REDESIGN-1 (c) precondition, DISCHARGED 2026-09-07. The row deferred the structured
     * WHERE/JOIN table over a Step's SQL behind one unanswered question: does the {@code json} extension
     * work on a connection that has {@code autoinstall_known_extensions} / {@code autoload_known_extensions}
     * off AND a locked configuration? It does — the extension is statically linked into the DuckDB JDBC
     * artifact, so nothing has to be installed or auto-loaded at all.
     *
     * <p>{@code json_serialize_sql} is the one that matters: it returns the full parsed AST as JSON, which is
     * the route a structured editor reads instead of re-implementing a SQL parser in TypeScript.
     *
     * <p>⚠ This test exists to keep that answer from rotting. It is pinned HERE, next to the seal
     * assertions, because the thing that would break it is a change to the seal — and a version bump that
     * stopped linking {@code json} statically would look exactly like the {@code excel} extension does
     * today (3-layer fail-closed load, nothing statically linked).
     *
     * <p>🔴 The escalation assertions at the end are not decoration: without them a future seal that
     * silently stopped locking anything would make the JSON half pass trivially, and this test would
     * certify a sandbox that no longer sandboxes.
     */
    @Test
    void jsonWorksOnASealedConnection() throws Exception {
        try (SqlSandbox sb = SqlSandbox.open(POLICY)) {
            sb.seal();
            try (Statement st = sb.statement()) {
                try (ResultSet rs = st.executeQuery("SELECT json_extract('{\"a\":1}', '$.a') AS v")) {
                    assertTrue(rs.next());
                    assertEquals("1", rs.getString("v"), "json_extract must work with no extension install");
                }
                try (ResultSet rs = st.executeQuery("SELECT json_structure('{\"a\":1}') AS v")) {
                    assertTrue(rs.next());
                    assertEquals("{\"a\":\"UBIGINT\"}", rs.getString("v"));
                }
                // The AST route itself. A structured editor reads THIS, not a hand-written parser.
                try (ResultSet rs = st.executeQuery(
                        "SELECT json_serialize_sql('SELECT a FROM t WHERE b > 1') AS ast")) {
                    assertTrue(rs.next());
                    String ast = rs.getString("ast");
                    assertTrue(ast.contains("\"error\":false"), "the statement parsed: " + ast);
                    assertTrue(ast.contains("where_clause"), "the WHERE is addressable in the AST: " + ast);
                    assertTrue(ast.contains("COMPARE_GREATERTHAN"), "the comparison survives: " + ast);
                }
            }

            // …and the seal is still a seal. Both of these fail for the RIGHT reason: a locked
            // configuration and a disabled filesystem, not a missing extension.
            try (Statement st = sb.statement()) {
                assertThrows(SQLException.class, () -> st.execute("SET enable_external_access=true"));
                assertThrows(SQLException.class, () -> st.execute("INSTALL excel"));
            }
        }
    }

    @Test
    void closeClosesTheConnection() throws Exception {
        SqlSandbox sb = SqlSandbox.open(POLICY);
        var conn = sb.connection();
        sb.close();
        assertTrue(conn.isClosed(), "close() releases the underlying connection");
    }
}
