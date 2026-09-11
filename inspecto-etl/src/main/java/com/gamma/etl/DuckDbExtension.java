package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Provisions a DuckDB extension for one connection, air-gap first — the shared mechanism behind
 * {@link ExcelExtension} ({@code excel}) and {@link DuckLakeRegistrar} ({@code ducklake}).
 *
 * <p>Layered, in this order, and the order is the whole point:
 * <ol>
 *   <li>{@code LOAD <name>} — wins when a prior INSTALL cached it (under {@code ~/.duckdb/extensions/…})
 *       or the deployment pre-installed it. Measured 2026-09-11: this <b>never reaches the network</b>.
 *       DuckDB's {@code autoinstall_known_extensions} governs <em>autoloading</em>, not an explicit
 *       {@code LOAD}, which fails in about a millisecond with <i>"Install it first"</i>. So trying the
 *       cheap path first costs an air-gapped deployment nothing.</li>
 *   <li>{@code LOAD '<dir>/<name>.duckdb_extension'} when {@code -Dduckdb.extension.dir} is set — the
 *       air-gapped deployment ships the platform's extension file beside the jar, and
 *       {@code inspecto/package.ps1} stages exactly these files into {@code duckdb-extensions/<platform>/}
 *       with every launcher pointing the flag at it.</li>
 *   <li>{@code INSTALL <name>; LOAD <name>} — the only step that egresses, and it is the last resort for
 *       networked deployments, which then fetch once and let step 1 win forever.</li>
 * </ol>
 *
 * <p>🔴 <b>Reaching step 3 on an air-gapped install is the bug {@code AIRGAP-EXTENSIONS-1} is about.</b>
 * {@code DuckLakeRegistrar} used to open with an unconditional {@code INSTALL ducklake FROM core} — step 3
 * and nothing else — so "zero egress" was false the moment a pipeline enabled DuckLake, and the failure
 * was swallowed as a non-fatal warning. Any future extension goes through here; ⛔ never write a bare
 * {@code INSTALL} at a call site.
 *
 * @since 5.x
 */
public final class DuckDbExtension {

    private static final Logger log = LoggerFactory.getLogger(DuckDbExtension.class);

    /** The air-gap escape hatch: a directory holding {@code <name>.duckdb_extension} for this platform. */
    public static final String DIR_PROPERTY = "duckdb.extension.dir";

    private DuckDbExtension() {}

    /**
     * Load {@code name} on {@code conn}, or throw naming every remedy.
     *
     * @param purpose what the caller needed it for, quoted back in the failure (e.g. {@code "frontend 'xlsx'"})
     */
    public static void ensureLoaded(Connection conn, String name, String purpose) throws SQLException {
        if (tryLoad(conn, name)) return;
        throw new SQLException("DuckDB's '" + name + "' extension is required for " + purpose
                + " but could not be loaded. Remedies: run once with network access (INSTALL caches it "
                + "under ~/.duckdb), or ship this platform's " + name + ".duckdb_extension and point -D"
                + DIR_PROPERTY + " at its directory.");
    }

    /** Best-effort load; {@code false} when unavailable (tests use this to skip, never to pass). */
    public static boolean tryLoad(Connection conn, String name) {
        try (Statement st = conn.createStatement()) {
            try {
                st.execute("LOAD " + name);
                return true;
            } catch (SQLException notCached) {
                String dir = System.getProperty(DIR_PROPERTY);
                if (dir != null && !dir.isBlank()) {
                    Path file = Path.of(dir, name + ".duckdb_extension");
                    if (Files.isRegularFile(file)) {
                        st.execute("LOAD '" + file.toString().replace('\\', '/').replace("'", "''") + "'");
                        return true;
                    }
                    log.warn("-D{}={} set but {} does not exist; falling back to INSTALL", DIR_PROPERTY, dir, file);
                }
                st.execute("INSTALL " + name);
                st.execute("LOAD " + name);
                return true;
            }
        } catch (SQLException e) {
            log.warn("DuckDB {} extension unavailable: {}", name, e.getMessage());
            return false;
        }
    }
}
