package com.gamma.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared DuckDB JDBC utilities used by {@link ParquetSummarizer},
 * {@link PartitionSummarizer}, and {@code com.gamma.inspector.CollectorProcessor} (core).
 *
 * <p>Central home for every recurring DuckDB boilerplate pattern:
 * <ul>
 *   <li>Explicit JDBC driver registration ({@link #loadDriver()})</li>
 *   <li>Temp-file database creation ({@link #tempDbFile(String)})</li>
 *   <li>JDBC URL construction ({@link #jdbcUrl(File)})</li>
 *   <li>Two-file cleanup — {@code .db} + {@code .wal} ({@link #deleteTempDb(File)})</li>
 *   <li>{@code COPY … TO} format clauses ({@link #buildCopyOptions(String)})</li>
 * </ul>
 */
public final class DuckDbUtil {

    /** Timestamp pattern shared by all pipeline log messages. */
    public static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DuckDbUtil() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Driver + connection helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Explicitly registers the DuckDB JDBC driver via {@code Class.forName}.
     *
     * <p>Required when the {@code META-INF/services} ServiceLoader entry is stripped
     * or merged incorrectly during fat-JAR shading (observed with maven-shade-plugin).
     * Safe to call multiple times — the driver loader is idempotent.
     *
     * @throws ClassNotFoundException if the DuckDB driver JAR is absent from the classpath
     */
    public static void loadDriver() throws ClassNotFoundException {
        Class.forName("org.duckdb.DuckDBDriver");
    }

    /**
     * Creates and immediately pre-deletes a temporary file for use as a DuckDB database.
     *
     * <p>DuckDB creates a fresh, empty database when given a path that does not yet exist,
     * so pre-deleting the temp file is the correct way to obtain a clean, uniquely-named
     * on-disk database without leaving a zero-byte placeholder.
     *
     * @param prefix temp-file name prefix (e.g. {@code "duckdb_worker_"})
     * @return the (now-deleted) {@link File} whose path DuckDB will use
     * @throws IOException if the temp file cannot be created
     */
    public static File tempDbFile(String prefix) throws IOException {
        File f = File.createTempFile(prefix, ".db");
        f.delete();
        return f;
    }

    /**
     * Like {@link #tempDbFile(String)} but creates the temp database in {@code dir} instead of the
     * JVM's {@code java.io.tmpdir} (typically the system {@code /tmp}).
     *
     * <p>This matters for large inputs: both the on-disk temp database <em>and</em> DuckDB's spill
     * scratch ({@code <dbfile>.tmp}) live next to this file, so pointing it at a roomy data volume
     * (e.g. the pipeline's {@code dirs.temp}) keeps multi-hundred-GB scratch off a small
     * {@code /tmp}. The directory is created if absent.
     *
     * @param prefix temp-file name prefix (e.g. {@code "duckdb_batch_"})
     * @param dir    directory to create the temp database in (must be writable / creatable)
     * @return the (now-deleted) {@link File} whose path DuckDB will use
     * @throws IOException if the directory or temp file cannot be created
     */
    public static File tempDbFile(String prefix, Path dir) throws IOException {
        Files.createDirectories(dir);
        File f = File.createTempFile(prefix, ".db", dir.toFile());
        f.delete();
        return f;
    }

    /**
     * Apply optional DuckDB resource controls to a worker connection via {@code SET} statements.
     * Each argument is applied only when non-null/non-blank; all-null is a no-op leaving DuckDB's
     * own defaults. Kept dependency-free (primitive args) so {@code com.gamma.util} need not depend
     * on the config model.
     *
     * <ul>
     *   <li>{@code temp_directory} — where DuckDB spills; aim at a roomy data volume, not /tmp.</li>
     *   <li>{@code memory_limit} — RAM cap (DuckDB size string, e.g. {@code "16GB"}).</li>
     *   <li>{@code max_temp_directory_size} — spill cap so a runaway query fails fast.</li>
     * </ul>
     *
     * @param conn                 an open DuckDB connection
     * @param memoryLimit          DuckDB {@code memory_limit} value, or {@code null} to leave default
     * @param tempDirectory        DuckDB {@code temp_directory} value, or {@code null} to leave default
     * @param maxTempDirectorySize DuckDB {@code max_temp_directory_size} value, or {@code null} to leave default
     */
    public static void applyDuckDbSettings(Connection conn, String memoryLimit,
                                           String tempDirectory, String maxTempDirectorySize)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (notBlank(tempDirectory))
                st.execute("SET temp_directory='" + sqlLiteral(tempDirectory.replace('\\', '/')) + "'");
            if (notBlank(memoryLimit))
                st.execute("SET memory_limit='" + sqlLiteral(memoryLimit) + "'");
            if (notBlank(maxTempDirectorySize))
                st.execute("SET max_temp_directory_size='" + sqlLiteral(maxTempDirectorySize) + "'");
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Escape single quotes for a single-quoted SQL string literal. */
    private static String sqlLiteral(String s) { return s.replace("'", "''"); }

    /**
     * Opens a DuckDB JDBC connection to {@code dbFile}.
     *
     * <p>Convenience wrapper that combines {@link #jdbcUrl(File)} with
     * {@link DriverManager#getConnection(String)}.  The caller is responsible for
     * closing the returned connection (use try-with-resources).
     *
     * @param dbFile file returned by {@link #tempDbFile(String)}
     * @return an open {@link Connection}
     * @throws SQLException if DuckDB cannot open or create the database
     */
    public static Connection openConnection(File dbFile) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(dbFile));
    }

    /**
     * Cap a worker connection's internal DuckDB parallelism via {@code PRAGMA threads=N}.
     *
     * <p>DuckDB defaults to one thread per core. When several batches run concurrently
     * (each with its own connection), the product of batch-concurrency × per-connection
     * threads can oversubscribe the CPU and add I/O contention. Setting this makes the
     * pipeline's controllable thread count honest.
     *
     * <p>No-op when {@code threads <= 0} (leave DuckDB's default).
     *
     * @param conn    an open DuckDB connection
     * @param threads desired per-connection thread count; {@code <= 0} leaves the default
     */
    public static void applyWorkerThreads(Connection conn, int threads) throws SQLException {
        if (threads <= 0) return;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA threads=" + threads);
        }
    }

    // ── global (JVM-property) fallback caps ──────────────────────────────────────────────────

    /** JVM-property names for the global DuckDB caps — mirror the {@code processing.duckdb.*} config keys. */
    public static final String PROP_MEMORY_LIMIT = "processing.duckdb.memory_limit";
    public static final String PROP_TEMP_DIRECTORY = "processing.duckdb.temp_directory";
    public static final String PROP_MAX_TEMP_DIRECTORY_SIZE = "processing.duckdb.max_temp_directory_size";
    public static final String PROP_THREADS = "processing.duckdb.threads";

    /**
     * The global fallback for a DuckDB setting: the per-config {@code configured} value wins; when it is
     * blank, the {@code -D<property>} value applies; when neither is set, {@code null} ⇒
     * {@link #applyDuckDbSettings} leaves DuckDB's own default. Lets a single {@code -Dprocessing.duckdb.*}
     * flag cap every scratch connection uniformly, with no behaviour change when the flag is unset.
     *
     * <p>⛔ Do NOT use this for a key the settings tier ({@code scheduler.toon}) serves — a served key
     * must not also be read from {@code -D} at use time (split ownership; see {@code SchedulerRoutes}).
     * {@code memory_limit} already moved to {@link #memoryLimit} for exactly that reason; a key added to
     * the settings tier later must move the same way, not stay here.
     */
    public static String globalOr(String configured, String property) {
        if (notBlank(configured)) return configured;
        String p = System.getProperty(property);
        return notBlank(p) ? p : null;
    }

    /** The server-configured {@code memory_limit} (BACKLOG D11), installed from {@code scheduler.toon}
     *  at boot / on a settings PUT; {@code null} until installed. */
    private static volatile String installedMemoryLimit;

    /**
     * Install the server-wide {@code memory_limit} owned by the settings document
     * ({@code scheduler.toon} → {@code GET/PUT /system/scheduler}). {@code null}/blank clears it, so the
     * {@code -D} bootstrap default applies again.
     *
     * <p>⛔ This exists so a served key is <b>not</b> also read from {@code -D} at use time — split
     * ownership of one fact is what the 2026-08-15 operational-db decision forbids
     * ({@code SchedulerRoutes}). {@link #memoryLimit} is the single use-time resolver.
     */
    public static void installMemoryLimit(String value) {
        installedMemoryLimit = notBlank(value) ? value.trim() : null;
    }

    /** The installed server-wide {@code memory_limit}, or {@code null} — for a settings GET's provenance. */
    public static String installedMemoryLimit() {
        return installedMemoryLimit;
    }

    /**
     * Resolve the effective {@code memory_limit} for a connection, in the precedence the settings tier
     * documents: the per-pipeline {@code configured} value wins (narrower scope), else the server
     * configuration's installed value ({@code file}), else {@code -Dprocessing.duckdb.memory_limit}
     * ({@code property}, a bootstrap default consulted only when nothing is installed), else the code
     * default {@link #defaultMemoryLimit()} (GAP-4). Never {@code null}: an unconfigured connection is
     * capped, never left on DuckDB's own ~80%-of-RAM-per-instance default. To restore DuckDB's own
     * behaviour, configure {@code 80%} explicitly at any tier.
     */
    public static String memoryLimit(String configured) {
        if (notBlank(configured)) return configured;
        String installed = installedMemoryLimit;
        if (notBlank(installed)) return installed;
        String p = System.getProperty(PROP_MEMORY_LIMIT);
        return notBlank(p) ? p : defaultMemoryLimit();
    }

    /**
     * How many capped DuckDB instances the {@link #defaultMemoryLimit()} budget is divided across. Mirrors
     * {@code JobService.DEFAULT_MAX_CONCURRENT_RUNS} (4), the other half of BACKLOG D11's pair. It is a
     * FIXED divisor, not the live semaphore: the Run bound is operator-changeable and the batch-ingest
     * path has its own limiter (the 2026-07-25 reason a cap computed from the semaphores was declined).
     */
    public static final int DEFAULT_CONCURRENT_INSTANCES = 4;

    /** The share of host RAM the default budgets for ALL capped instances together — the middle of
     *  {@code editions.md}'s sizing rule ({@code memory_limit ≈ 25–50 % RAM ÷ concurrency}). */
    static final double DEFAULT_RAM_FRACTION = 0.40;

    /** The floor: the 2026-07-27 measurement put the blocking-operator OOM cliff between 512MB and 1GB,
     *  so a default below 1 GiB would turn working jobs into failing ones on a small host. */
    static final long DEFAULT_MEMORY_FLOOR_BYTES = 1L << 30;

    /**
     * The code default for {@code memory_limit} (GAP-4): 40% of the RAM this JVM can see (the container
     * limit under a cgroup) ÷ {@link #DEFAULT_CONCURRENT_INSTANCES}, floored at 1 GiB, as a whole-MiB
     * DuckDB size string. A 32 GiB host ⇒ {@code 3276MiB}; an 8 GiB host ⇒ the {@code 1024MiB} floor.
     */
    public static String defaultMemoryLimit() {
        return defaultMemoryLimit(physicalMemoryBytes());
    }

    /** {@link #defaultMemoryLimit()} for a given RAM size; {@code <= 0} (unknown) ⇒ the floor. */
    static String defaultMemoryLimit(long totalRamBytes) {
        long share = (long) (Math.max(0L, totalRamBytes) * DEFAULT_RAM_FRACTION) / DEFAULT_CONCURRENT_INSTANCES;
        return Math.max(DEFAULT_MEMORY_FLOOR_BYTES, share) / (1024L * 1024L) + "MiB";
    }

    /**
     * Total RAM visible to this JVM (the container limit under a cgroup), or {@code 0} if unknown — which
     * {@link #defaultMemoryLimit(long)} turns into the 1 GiB floor.
     *
     * <p>🔴 {@code com.sun.management} lives in the {@code jdk.management} module, which a jlinked runtime may
     * not carry: the shipped bundles' runtime had only {@code java.management}, so the first ingest threw
     * {@code NoClassDefFoundError} — an {@code Error}, past every {@code catch (Exception)} — and EVERY batch
     * failed (found on the telco demo build, 2026-09-26). A {@link LinkageError} here means "unknown", never
     * a failed ingest; {@code package.ps1} now also ships {@code jdk.management} so the real size is read.
     */
    static long physicalMemoryBytes() {
        try {
            return java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os ? os.getTotalMemorySize() : 0L;
        } catch (LinkageError absentModule) {
            return 0L;
        }
    }

    /**
     * Apply the global {@code -Dprocessing.duckdb.*} caps (memory_limit, spill {@code temp_directory},
     * spill-size cap, and worker {@code threads}) to a scratch connection that has no per-pipeline
     * {@code processing.duckdb} config to read from — the pipeline job ({@code PipelineJobRunner}) and
     * enrichment ({@code EnrichmentEngine}) scratch DBs, which would otherwise open fully uncapped while
     * the batch-ingest path caps its own connections. Every property is opt-in: unset ⇒ no {@code SET}/
     * {@code PRAGMA} is issued ⇒ DuckDB keeps its own defaults, so behaviour is unchanged unless an
     * operator sets the flags (one knob then caps all three paths). ⚠ Except {@code memory_limit}, which
     * is never left unset: {@link #memoryLimit} ends at the GAP-4 code default. Both callers open a
     * file-backed scratch database, so an over-limit query spills to {@code <dbfile>.tmp} beside it —
     * never the CWD (an in-memory {@code jdbc:duckdb:} would spill to {@code ./.tmp}).
     *
     * <p>{@code memory_limit} resolves through {@link #memoryLimit} so the server configuration's
     * installed value (BACKLOG D11) wins over the {@code -D} bootstrap default. ⚠ Preview / dry-run
     * connections deliberately do NOT call this — they run over bounded samples and D11 exempts them.
     */
    public static void applyGlobalDuckDbSettings(Connection conn) throws SQLException {
        applyDuckDbSettings(conn,
                memoryLimit(null),
                System.getProperty(PROP_TEMP_DIRECTORY),
                System.getProperty(PROP_MAX_TEMP_DIRECTORY_SIZE));
        applyWorkerThreads(conn, Integer.getInteger(PROP_THREADS, 0));
    }

    /**
     * Resolve the effective per-connection DuckDB thread count for a batch worker, given the
     * configured value, the batch concurrency, and the machine's core count.
     *
     * <p>This is the anti-oversubscription policy. DuckDB defaults to one thread per core, so when
     * {@code batchConcurrency} batches each open their own connection, the product
     * {@code batchConcurrency × cores} of DuckDB workers fights over {@code cores} CPUs — the
     * kernel-time blowup (futex / TLB / mmap-lock contention) that looks like ~100% sys, ~2% user.
     *
     * <ul>
     *   <li>{@code configured > 0} — honor it exactly (explicit tuning; unchanged behaviour).</li>
     *   <li>{@code configured == 0} (the default) — auto-derive: with more than one concurrent batch,
     *       split the cores evenly ({@code max(1, cores / batchConcurrency)}) so
     *       {@code batchConcurrency × result ≈ cores}; with a single batch, return {@code 0} (let
     *       DuckDB use every core — no oversubscription is possible).</li>
     *   <li>{@code configured < 0} — explicit opt-out: return {@code 0} so DuckDB keeps its own
     *       per-core default even under concurrency (use when you deliberately want one batch to
     *       grab the whole machine).</li>
     * </ul>
     *
     * <p>A {@code 0} result is the "leave DuckDB's default" sentinel understood by
     * {@link #applyWorkerThreads}. Kept dependency-free (primitive args) so {@code com.gamma.util}
     * need not depend on the config model, and pure so it is trivially unit-testable.
     *
     * @param configured       the configured {@code processing.duckdb_threads}
     * @param batchConcurrency  the configured {@code processing.threads} (concurrent batches)
     * @param availableCores    {@link Runtime#availableProcessors()} on this host
     * @return the value to pass to {@link #applyWorkerThreads} ({@code 0} = leave DuckDB default)
     */
    public static int effectiveWorkerThreads(int configured, int batchConcurrency, int availableCores) {
        if (configured > 0) return configured;             // explicit cap — honor exactly
        if (configured < 0) return 0;                      // explicit opt-out — DuckDB per-core default
        if (batchConcurrency <= 1) return 0;               // single batch — all cores, can't oversubscribe
        return Math.max(1, availableCores / batchConcurrency);   // auto: divide cores among batches
    }

    /**
     * Returns the JDBC URL for a DuckDB database file.
     *
     * <p>Forward slashes are used unconditionally: DuckDB's URL parser rejects
     * Windows backslashes in the {@code jdbc:duckdb:} scheme.
     *
     * @param dbFile the database file (may or may not exist yet)
     * @return a {@code jdbc:duckdb:<path>} URL ready to pass to {@link DriverManager}
     */
    public static String jdbcUrl(File dbFile) {
        return "jdbc:duckdb:" + dbFile.getAbsolutePath().replace('\\', '/');
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a DuckDB database file <em>and</em> its companion WAL file
     * ({@code <path>.wal}).
     *
     * <p>Both deletions go through {@link #deleteQuietly(File)} so failures are
     * logged rather than thrown.  Safe to call even when the files no longer exist.
     *
     * @param dbFile the {@code .db} file to remove (the {@code .wal} path is derived
     *               by appending {@code ".wal"} to the absolute path)
     */
    public static void deleteTempDb(File dbFile) {
        deleteQuietly(dbFile);
        deleteQuietly(new File(dbFile.getAbsolutePath() + ".wal"));
    }

    /**
     * Deletes {@code f}, printing a {@code [CLEANUP]} warning if the file exists
     * but the deletion fails.  {@link File#delete()} never throws — failures are
     * surfaced only via the return value, which this method checks.
     *
     * @param f the file to delete (no-op when {@code f} does not exist)
     */
    public static void deleteQuietly(File f) {
        if (!f.delete() && f.exists())
            System.out.printf("[%s] [CLEANUP] WARN: could not delete temp file: %s%n",
                    LocalDateTime.now().format(DT_FMT), f.getAbsolutePath());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COPY-TO format helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the {@code COPY … TO} options clause for the requested format.
     *
     * <ul>
     *   <li>{@code "PARQUET"} → {@code FORMAT PARQUET, COMPRESSION SNAPPY}</li>
     *   <li>{@code "CSV"}     → {@code FORMAT CSV, HEADER true}</li>
     * </ul>
     *
     * @param format {@code "PARQUET"} or {@code "CSV"} (must already be upper-cased)
     * @return the options string to embed in a {@code COPY … TO '<path>' (<options>)} statement
     * @throws IllegalStateException for any unrecognised format string
     */
    public static String buildCopyOptions(String format) {
        return switch (format) {
            case "PARQUET" -> "FORMAT PARQUET, COMPRESSION SNAPPY";
            case "CSV"     -> "FORMAT CSV, HEADER true";
            default        -> throw new IllegalStateException("Unexpected format: " + format);
        };
    }

    /**
     * The line DuckDB's JDBC driver (1.5.x) puts in front of a failure it finds while EXECUTING a plain
     * {@link java.sql.Statement}: {@code Statement.execute(String)} runs a pending query without checking
     * whether preparing it failed, so DuckDB composes this text, a newline, {@code Error: }, then the real
     * error into ONE native message. The {@link java.sql.SQLException} carries no cause, no suppressed and
     * no SQLState to recover it from — {@code Connection.prepareStatement} reports the same SQL cleanly,
     * which is how this was confirmed (TESTRUN-BINDER-ERROR-LEAKS-PREAMBLE-1).
     */
    static final String PENDING_QUERY_PREAMBLE =
            "Invalid Input Error: Attempting to execute an unsuccessful or closed pending query result\nError: ";

    /**
     * {@code message} without DuckDB's pending-query preamble, so an author reads the actionable error
     * ({@code Binder Error: Referenced column … Candidate bindings: …}) first. Only that exact driver
     * text is removed, wherever a wrapper placed it; everything else — including any caller prefix — is
     * kept verbatim. {@code null} stays {@code null}.
     */
    public static String withoutPendingQueryPreamble(String message) {
        return message == null ? null : message.replace(PENDING_QUERY_PREAMBLE, "");
    }
}
