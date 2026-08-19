package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Loads DuckDB's {@code excel} extension for a connection — the one piece of {@code read_xlsx}
 * infrastructure the driver does not carry: unlike {@code json}/{@code icu}, the extension is
 * <b>not statically linked</b> into duckdb_jdbc (probed on 1.5.2.1), so it must be provisioned.
 *
 * <p>Layered, fail-closed (multiformat-parser-lanes plan P1):
 * <ol>
 *   <li>{@code LOAD excel} — wins when a prior INSTALL cached it (in {@code ~/.duckdb/extensions/…})
 *       or the deployment pre-installed it.</li>
 *   <li>{@code LOAD '<dir>/excel.duckdb_extension'} when {@code -Dduckdb.extension.dir} is set —
 *       the air-gapped deployment ships the platform's extension file beside the jar.</li>
 *   <li>{@code INSTALL excel; LOAD excel} — networked deployments fetch once, then step 1 wins
 *       forever.</li>
 * </ol>
 * If all three fail, the batch fails with a message naming every remedy — never a silent or partial
 * parse. {@code LOAD} is idempotent and cheap, so callers invoke this per ingest connection.
 */
public final class ExcelExtension {

    private static final Logger log = LoggerFactory.getLogger(ExcelExtension.class);

    /** The air-gap escape hatch: a directory holding {@code excel.duckdb_extension} for this platform. */
    public static final String DIR_PROPERTY = "duckdb.extension.dir";

    private ExcelExtension() {}

    /** Load the extension on {@code conn} or throw with every remedy named. */
    public static void ensureLoaded(Connection conn) throws SQLException {
        if (tryLoad(conn)) return;
        throw new SQLException("DuckDB's 'excel' extension is required for frontend 'xlsx' but could not "
                + "be loaded. Remedies: run once with network access (INSTALL caches it under ~/.duckdb), "
                + "or ship this platform's excel.duckdb_extension and point -D" + DIR_PROPERTY
                + " at its directory.");
    }

    /** Best-effort load; {@code false} when unavailable (tests use this to skip, never to pass). */
    public static boolean tryLoad(Connection conn) {
        try (Statement st = conn.createStatement()) {
            try {
                st.execute("LOAD excel");
                return true;
            } catch (SQLException notCached) {
                String dir = System.getProperty(DIR_PROPERTY);
                if (dir != null && !dir.isBlank()) {
                    Path file = Path.of(dir, "excel.duckdb_extension");
                    if (Files.isRegularFile(file)) {
                        st.execute("LOAD '" + file.toString().replace("\\", "/").replace("'", "''") + "'");
                        return true;
                    }
                    log.warn("-D{}={} set but {} does not exist; falling back to INSTALL", DIR_PROPERTY, dir, file);
                }
                st.execute("INSTALL excel");
                st.execute("LOAD excel");
                return true;
            }
        } catch (SQLException e) {
            log.warn("DuckDB excel extension unavailable: {}", e.getMessage());
            return false;
        }
    }
}
