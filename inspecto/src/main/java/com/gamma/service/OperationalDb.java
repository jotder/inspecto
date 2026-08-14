package com.gamma.service;

/**
 * The <b>operational database</b>: one selection covering every transactional/operational store the
 * service hosts (job runs, provenance, status, Objects/links/notes/tags, consignment outputs, file
 * stages). Business data is never here — it stays Parquet, read through DuckDB as a non-updateable
 * query engine.
 *
 * <p>The edition model this expresses:
 * <ul>
 *   <li><b>Personal</b> — embedded DuckDB for everything. No server to run, no driver to ship.</li>
 *   <li><b>Standard</b> — PostgreSQL for the operational stores; DuckDB stays as the query engine
 *       over Parquet.</li>
 * </ul>
 *
 * <p>Selected by {@code -Dinspecto.db} ({@code duckdb} — the default — or {@code postgres}), with
 * {@code -Dinspecto.db.url} / {@code .user} / {@code .password} carrying the connection when it is
 * {@code postgres}. ⚠ This is a <b>connection</b> selection, never an on/off switch: whether a given
 * store is enabled remains its own {@code *.backend} toggle, with its existing default, so turning on
 * Postgres does not silently enable capabilities an operator never asked for. A per-family
 * {@code *.db.url} still wins over this, which is what keeps every existing deployment working.
 *
 * <p>⛔ <b>Selecting {@code postgres} without the driver fails at boot, loudly.</b> It used to be
 * caught per store and logged at WARN, leaving the store {@code null} — so a deployment pointed at
 * Postgres came up "healthy" with job reporting, provenance and Objects silently switched OFF rather
 * than moved. The shipped {@code inspecto.jar} carries no JDBC driver by design (see
 * {@code inspecto-engine/pom.xml}: "runtime stays JDBC-driver-free"), so this is the expected failure
 * on a Personal bundle and the message says so instead of leaving an operator to infer it.
 */
final class OperationalDb {

    /** The PostgreSQL JDBC driver class, probed by name — never linked against. */
    private static final String PG_DRIVER = "org.postgresql.Driver";

    private OperationalDb() {}

    /** True when the operational stores should speak PostgreSQL rather than embedded DuckDB. */
    static boolean postgres() {
        String v = System.getProperty("inspecto.db", "duckdb").trim().toLowerCase();
        return "postgres".equals(v) || "postgresql".equals(v);
    }

    /**
     * The shared connection URL, or {@code null} when this is a DuckDB (Personal) deployment or no
     * shared URL was given — callers then fall back to their per-space DuckDB file as before.
     */
    static String url() {
        if (!postgres()) return null;
        String url = System.getProperty("inspecto.db.url");
        return url == null || url.isBlank() ? null : url.trim();
    }

    static String user()     { return System.getProperty("inspecto.db.user"); }
    static String password() { return System.getProperty("inspecto.db.password"); }

    /**
     * Fail closed at boot if {@code postgres} was selected but cannot be honoured — a missing driver or
     * a missing URL. Called once from service bootstrap, before any store opens, so the operator gets
     * one actionable error instead of N scattered WARNs and a quietly reduced service.
     *
     * @throws IllegalStateException naming the property at fault and what to do about it
     */
    static void verifySelectable() {
        if (!postgres()) return;
        if (url() == null)
            throw new IllegalStateException(
                    "-Dinspecto.db=postgres needs a connection: set -Dinspecto.db.url=jdbc:postgresql://host:5432/db");
        try {
            Class.forName(PG_DRIVER);
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                    "-Dinspecto.db=postgres was selected but the PostgreSQL JDBC driver (" + PG_DRIVER
                            + ") is not on the classpath. The Personal bundle ships DuckDB only; use the"
                            + " Standard bundle, or add postgresql.jar to the classpath.", missing);
        }
    }

    /**
     * The URL a store should open: an explicit per-family {@code -D<family>.db.url} first (back-compat,
     * and the escape hatch for pointing one store somewhere else), then the shared operational URL,
     * then the space's own DuckDB file.
     */
    static String urlFor(String familyUrlProperty, String spaceDefault) {
        String explicit = System.getProperty(familyUrlProperty);
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String shared = url();
        return shared != null ? shared : spaceDefault;
    }

    /** As {@link #urlFor}, for the credential half — a per-family value first, then the shared one. */
    static String userFor(String familyUserProperty) {
        String explicit = System.getProperty(familyUserProperty);
        return explicit != null ? explicit : user();
    }

    /** As {@link #userFor}, for the password. */
    static String passwordFor(String familyPasswordProperty) {
        String explicit = System.getProperty(familyPasswordProperty);
        return explicit != null ? explicit : password();
    }
}
