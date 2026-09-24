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
 * <p>Two modes, chosen by whether {@code -Dduckdb.extension.dir} is set — and the split is the whole point:
 * <ul>
 *   <li><b>Staged (the bundle).</b> Every generated launcher ({@code serve}/{@code run}/{@code ura}) sets the
 *       flag to {@code duckdb-extensions/<platform>/}, which {@code inspecto/package.ps1} fills with the
 *       platform's binaries. The loader then issues exactly one statement,
 *       {@code LOAD '<dir>/<name>.duckdb_extension'}, and <b>nothing else</b>: not a bare {@code LOAD <name>}
 *       (that would silently prefer whatever some other DuckDB left in {@code ~/.duckdb}, so a bundle would
 *       pass on the build box and fail on the customer's), and never {@code INSTALL}. A missing file FAILS
 *       LOUDLY, naming the exact path. (D-8, 2026-09-24 — before this a missing staged file only warned and
 *       fell through to a network INSTALL, so "the bundle carries it" was checked by nothing.)</li>
 *   <li><b>Unstaged (a dev box, a networked host).</b> {@code LOAD <name>} — wins when a prior INSTALL cached
 *       it; measured 2026-09-11 it <b>never reaches the network</b> ({@code autoinstall_known_extensions}
 *       governs autoloading, not an explicit {@code LOAD}) — then {@code INSTALL <name>; LOAD <name>}, the
 *       only step that egresses.</li>
 * </ul>
 *
 * <p>⚠ This governs the EXPLICIT loads only. DuckDB's autoload ignores the flag and reads its own
 * {@code extension_directory}, which is why every autoloaded extension needs a named call site here too
 * (see {@code package.ps1} step 6d).
 *
 * <p>🔴 <b>Reaching INSTALL on an air-gapped install is the bug {@code AIRGAP-EXTENSIONS-1} is about.</b>
 * {@code DuckLakeRegistrar} used to open with an unconditional {@code INSTALL ducklake FROM core}, so "zero
 * egress" was false the moment a pipeline enabled DuckLake, and the failure was swallowed as a non-fatal
 * warning. Any future extension goes through here; ⛔ never write a bare {@code INSTALL} at a call site.
 *
 * @since 5.x
 */
public final class DuckDbExtension {

    private static final Logger log = LoggerFactory.getLogger(DuckDbExtension.class);

    /** The air-gap switch: a directory holding {@code <name>.duckdb_extension} for this platform. */
    public static final String DIR_PROPERTY = "duckdb.extension.dir";

    private DuckDbExtension() {}

    /**
     * Load {@code name} on {@code conn}, or throw naming every remedy.
     *
     * @param purpose what the caller needed it for, quoted back in the failure (e.g. {@code "frontend 'xlsx'"})
     */
    public static void ensureLoaded(Connection conn, String name, String purpose) throws SQLException {
        try {
            load(conn, name);
        } catch (SQLException e) {
            throw new SQLException("DuckDB's '" + name + "' extension is required for " + purpose
                    + " but could not be loaded: " + e.getMessage(), e);
        }
    }

    /** Best-effort load; {@code false} when unavailable (tests use this to skip, never to pass). */
    public static boolean tryLoad(Connection conn, String name) {
        try {
            load(conn, name);
            return true;
        } catch (SQLException e) {
            log.warn("DuckDB {} extension unavailable: {}", name, e.getMessage());
            return false;
        }
    }

    private static void load(Connection conn, String name) throws SQLException {
        String dir = System.getProperty(DIR_PROPERTY);
        try (Statement st = conn.createStatement()) {
            if (dir != null && !dir.isBlank()) {
                Path file = Path.of(dir, name + ".duckdb_extension").toAbsolutePath();
                if (!Files.isRegularFile(file))
                    throw new SQLException("-D" + DIR_PROPERTY + "=" + dir + " is set, so extensions load ONLY "
                            + "from there, and " + file + " does not exist. Stage this platform's " + name
                            + ".duckdb_extension into it (package.ps1 -RequireExtensions), or unset -D"
                            + DIR_PROPERTY + " on a networked host to allow an INSTALL.");
                st.execute("LOAD '" + file.toString().replace('\\', '/').replace("'", "''") + "'");
                log.debug("DuckDB {} extension loaded from staged file {}", name, file);
                return;
            }
            try {
                st.execute("LOAD " + name);
            } catch (SQLException notCached) {
                try {
                    st.execute("INSTALL " + name);
                    st.execute("LOAD " + name);
                } catch (SQLException noNetwork) {
                    throw new SQLException(noNetwork.getMessage() + " — remedies: run once with network access "
                            + "(INSTALL caches it under ~/.duckdb), or ship this platform's " + name
                            + ".duckdb_extension and point -D" + DIR_PROPERTY + " at its directory.", noNetwork);
                }
            }
        }
    }
}
