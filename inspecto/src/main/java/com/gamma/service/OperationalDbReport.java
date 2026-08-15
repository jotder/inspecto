package com.gamma.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read-and-validate surface over {@link OperationalDb} (PG-1 Open 2, Stage 1) — what a deployment is
 * <b>actually</b> using, and whether a proposed connection works, without inventing a way to change it.
 *
 * <p><b>Why only read + validate.</b> The UI is served by the process that needs the operational
 * database, so it cannot configure its own dependency and no change could take effect without a restart.
 * Persisting a selection from the UI would also create a <b>second declaration of the same fact</b>
 * alongside {@code -D} — the split-brain this codebase already refused for the enrichment companion.
 * Decided 2026-08-15: the UI reports and validates; the operator applies the flags through their own
 * deployment tooling.
 *
 * <p>⛔ <b>No password leaves this class, in any form</b> — not the value, not a redaction, not a length.
 * The user IS returned: it is not a secret, and "which user am I connecting as" is one of the questions
 * the report exists to answer.
 */
public final class OperationalDbReport {

    private OperationalDbReport() {}

    /** JDBC URL schemes this service will open. ⛔ Anything else is refused rather than dialled. */
    private static final List<String> ALLOWED_SCHEMES = List.of("jdbc:postgresql:", "jdbc:duckdb:");

    /**
     * The effective operational-database configuration for one space: the selected engine, and per
     * family its resolved URL plus <b>where that value came from</b>.
     *
     * <p>⚠ A URL may legally embed credentials ({@code jdbc:postgresql://user:pw@host/db}), so every URL
     * is passed through {@link #stripUserInfo} before it leaves — a report that redacts the password
     * field while echoing it inside a URL has redacted nothing.
     */
    public static Map<String, Object> of(SpaceContext space) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", OperationalDb.postgres() ? "postgres" : "duckdb");
        out.put("engineProperty", "inspecto.db");
        out.put("driverAvailable", OperationalDb.driverAvailable());

        List<Map<String, Object>> families = new java.util.ArrayList<>();
        for (OperationalDb.Resolved r : OperationalDb.resolveAll(space.root())) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("family", r.family().name());
            f.put("label", r.family().label);
            f.put("enabled", r.enabled());
            f.put("source", r.source().name());
            f.put("url", stripUserInfo(r.url()));
            f.put("user", r.user());
            f.put("backendProperty", r.family().backendProperty);
            f.put("urlProperty", r.family().urlProperty);
            // Null for the families that open with a URL and no credentials — rendered as "n/a", not blank.
            f.put("userProperty", r.family().userProperty);
            f.put("passwordProperty", r.family().passwordProperty);
            families.add(f);
        }
        out.put("families", families);
        return out;
    }

    /**
     * Open the supplied JDBC URL for real and run {@code SELECT 1}. A named outcome, never a boolean:
     * "the driver is missing" means <em>drop {@code postgresql.jar} beside {@code inspecto.jar}</em>,
     * which is a completely different action from bad credentials.
     *
     * @param password already resolved from a {@code ${…}} reference by the caller; ⛔ never echoed back
     */
    public static Map<String, Object> test(String url, String user, String password) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", stripUserInfo(url));
        if (url.startsWith("jdbc:postgresql:") && !OperationalDb.driverAvailable()) {
            return outcome(out, "DRIVER_MISSING",
                    "the PostgreSQL JDBC driver is not on the classpath — the Standard/Enterprise bundle"
                            + " ships it as the postgresql.jar sidecar; drop it beside inspecto.jar");
        }
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement s = c.createStatement()) {
            s.execute("SELECT 1");
            return outcome(out, "OK", "connected and answered SELECT 1");
        } catch (Exception e) {
            // The driver's own message is the only thing that distinguishes these, and it is
            // vendor-worded — so the message is passed through rather than parsed into a taxonomy that
            // would silently mis-bucket an unfamiliar driver.
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return outcome(out, looksLikeAuth(msg) ? "AUTH_FAILED" : "UNREACHABLE", stripUserInfo(msg));
        }
    }

    /** Whether a caller-supplied JDBC URL is one this service is willing to dial (see ALLOWED_SCHEMES). */
    public static boolean allowedScheme(String url) {
        return url != null && ALLOWED_SCHEMES.stream().anyMatch(url::startsWith);
    }

    /** The schemes a caller may test, for the 422's message. */
    public static List<String> allowedSchemes() { return ALLOWED_SCHEMES; }

    private static Map<String, Object> outcome(Map<String, Object> out, String code, String detail) {
        out.put("outcome", code);
        out.put("detail", detail);
        return out;
    }

    private static boolean looksLikeAuth(String msg) {
        String m = msg.toLowerCase();
        return m.contains("password") || m.contains("authentication") || m.contains("role \"")
                || m.contains("permission denied");
    }

    /**
     * Remove any {@code user:password@} embedded in a URL's authority. ⛔ Load-bearing for redaction:
     * {@code jdbc:postgresql://user:pw@host:5432/db} is a legal URL an operator may well have set, and
     * without this the report would hand back the very secret it refuses to include as a field.
     */
    static String stripUserInfo(String url) {
        if (url == null) return null;
        int at = url.lastIndexOf('@');
        int slashes = url.indexOf("//");
        if (at < 0 || slashes < 0 || at < slashes) return url;
        return url.substring(0, slashes + 2) + "•••@" + url.substring(at + 1);
    }
}
