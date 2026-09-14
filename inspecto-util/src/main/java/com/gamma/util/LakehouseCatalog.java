package com.gamma.util;

/**
 * The shared DuckLake catalog a deployment reads through — {@code -Dinspecto.ducklake.catalog} and
 * {@code -Dinspecto.ducklake.data} (scale-out phase C, §5.4 bullet 5, D4/D4a).
 *
 * <p><b>What it is for.</b> The WRITE side already knows its catalog: each pipeline carries
 * {@code output.ducklake.{catalog_url,data_path}}. The READ side does not and cannot — a query is
 * per-request and per-Space, never per-pipeline, so no read path is given a {@code PipelineConfig}.
 * Without a home the read side has no way to name the catalog, which is why one pod could not see
 * another pod's slices: not for want of a mechanism, but for want of the URL.
 *
 * <p><b>Why a system property rather than a config file</b> (operator, 2026-09-14). Under D4 the
 * lakehouse is a property of the DEPLOYMENT — one Postgres catalog that every pod attaches — which is
 * the same shape as {@link Topology} and {@code -Dops.timezone}, and those are the established idiom
 * for a deployment-wide switch. A per-Space file was the alternative and was not taken: DuckLake
 * schemas already separate Spaces inside one catalog, so per-Space catalogs would buy separation the
 * model already has, at the price of a new global settings file with its own fail-closed parse rules.
 * ⚠ If a real per-tenant-catalog need appears, this is the seam to widen — the read paths ask
 * <i>this</i> class, not a property, so the answer can become Space-aware without touching them.
 *
 * <p><b>Read per call, not cached</b>, for the same reason {@link Topology} is: a cached static cannot
 * be set per test, which is how a flag ends up with no negative coverage.
 *
 * <p>⛔ <b>Unset is not an error.</b> Personal and single-node Standard have no shared catalog and must
 * not need one; reads then behave exactly as they did before this class existed. Absence is the default,
 * not a misconfiguration.
 *
 * @since 2026-09-14 (scale-out phase C, §5.4)
 */
public final class LakehouseCatalog {

    private LakehouseCatalog() {}

    /** The catalog URL, e.g. {@code postgres:dbname=lake host=db port=5432 user=U password=P}. */
    public static final String CATALOG_PROPERTY = "inspecto.ducklake.catalog";

    /** Where the Parquet lives — a mounted path today; an {@code s3://} prefix when D4's object store lands. */
    public static final String DATA_PROPERTY = "inspecto.ducklake.data";

    /** A configured shared catalog: the URL as given, plus the data path every attach must name. */
    public record Catalog(String url, String dataPath) {}

    /**
     * The configured shared catalog, or {@code null} when this deployment has none.
     *
     * <p>⛔ <b>The two properties are all-or-nothing.</b> A catalog URL with no data path cannot be
     * attached and a data path with no catalog names nothing, so a half-configured pair is a BOOT-time
     * mistake that would otherwise surface as "reads silently see less than they should" — the failure
     * this whole section exists to prevent. Refusing to guess is the same call {@link Topology#mode()}
     * makes for an unrecognised value.
     *
     * @throws IllegalStateException if exactly one of the two properties is set, or if a set value is
     *                               blank
     */
    public static Catalog configured() {
        String url = trimmedOrNull(System.getProperty(CATALOG_PROPERTY));
        String data = trimmedOrNull(System.getProperty(DATA_PROPERTY));
        if (url == null && data == null) return null;
        if (url == null || data == null)
            throw new IllegalStateException("-D" + CATALOG_PROPERTY + " and -D" + DATA_PROPERTY
                    + " must be set together; got "
                    + (url == null ? "only the data path" : "only the catalog") + ". A catalog with no data"
                    + " path cannot be attached and a data path with no catalog names nothing, so half of"
                    + " this pair would leave reads quietly seeing less than they should rather than"
                    + " failing. Set both, or neither for a deployment with no shared lakehouse.");
        return new Catalog(url, data);
    }

    /** {@code true} when this deployment has a shared catalog to attach. */
    public static boolean isConfigured() {
        return configured() != null;
    }

    private static String trimmedOrNull(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }

    // ── is this catalog actually SHARED? ──────────────────────────────────────────────────────────
    //
    // One definition, two callers: the write side checks the pipeline's output.ducklake.catalog_url and
    // the read side checks -Dinspecto.ducklake.catalog. ⛔ Duplicating the rule is how the two sides come
    // to disagree about what "shared" means, and the disagreement would show up as one of them quietly
    // using a private catalog — the exact defect this rule exists to catch.

    /**
     * The DuckLake catalog backends that are a SHARED SERVER rather than a local file.
     *
     * <p>DuckLake reads whatever follows {@code ducklake:} as a backend spec, and anything without a
     * recognised backend prefix is a <b>file path</b>. That is the whole hazard in {@link #requireShared}.
     */
    private static final java.util.List<String> SERVER_BACKENDS = java.util.List.of("postgres:", "mysql:");

    /**
     * Whether {@code catalogUrl} names a shared server catalog rather than a local file.
     *
     * <p>🔴 <b>A prefix match alone is NOT enough, and the first version of this rule shipped with that
     * hole.</b> {@code postgres://host/db} starts with {@code postgres:} and so passed — while actually
     * failing to attach, because DuckLake wants libpq KEYWORDS after the prefix, not a URL authority. The
     * guard therefore accepted the very spelling its own message told operators to avoid. Caught by
     * {@code LakehouseCatalogTest.fileAndUrlSpellingsAreNotShared}, which is exactly the case a
     * prefix-only rule reads as fine.
     *
     * <p>So the backend prefix must be followed by a connection spec, never by {@code //}: the URL forms
     * are read as paths by DuckLake and must be refused with everything else that is.
     */
    public static boolean isShared(String catalogUrl) {
        if (catalogUrl == null) return false;
        String lower = catalogUrl.trim().toLowerCase();
        return SERVER_BACKENDS.stream().anyMatch(
                b -> lower.startsWith(b) && !lower.startsWith(b + "//"));
    }

    /**
     * ⛔ In a {@code partitioned} topology, refuse a catalog that would be a LOCAL FILE.
     *
     * <p><b>Why this exists when a registration failure is already fatal (D10).</b> D10 covers the catalog
     * that cannot be reached. It does not cover the catalog that is reached <b>successfully and
     * privately</b> — and that is the likelier mistake, because it never raises anything. Measured
     * 2026-09-14 against duckdb_jdbc 1.5.2.1: a catalog value with no recognised backend prefix does not
     * fail, it <b>silently creates a local DuckDB file catalog</b> named after the whole string. On N pods
     * that is N private catalogs, each seeing only its own Parquet, every batch green — precisely the
     * split-brain the partitioned topology exists to prevent, arriving as success rather than as failure.
     * So the shape of the value has to be refused up front; there is no later moment at which it looks wrong.
     *
     * <p>⚠ <b>Single-node behaviour is deliberately unchanged.</b> A file catalog is the correct and
     * documented choice when one process owns the lakehouse, which is every Personal and single-node
     * Standard install. This refuses it only where "one process" is false.
     *
     * <p>⚠ <b>{@code postgresql://…} is refused too, and that is not a mis-diagnosis.</b> It reads like a
     * shared catalog and is what this product documented as THE example, but it carries no recognised
     * prefix, so DuckLake treats it as a path as well — measured failing in the same probe. The message
     * therefore names the spelling measured WORKING rather than only refusing.
     *
     * @param catalogUrl the value to check, as authored
     * @param origin     where it came from, for the message — a config key or a {@code -D} property
     */
    public static void requireShared(String catalogUrl, String origin) {
        if (!Topology.partitioned()) return;
        String url = catalogUrl == null ? "" : catalogUrl.trim();
        if (isShared(url)) return;

        throw new IllegalStateException(origin + "=" + (url.isEmpty() ? "(unset)" : url)
                + " is not a shared catalog, and -D" + Topology.PROPERTY + "=partitioned forbids it. DuckLake"
                + " reads anything without a backend prefix as a FILE PATH, so this would not fail — it would"
                + " quietly give this node its own private catalog, and every node would see only the Parquet"
                + " it wrote itself while every batch reported success. Use a server-backed catalog, e.g."
                + " \"postgres:dbname=lake host=db port=5432 user=U password=P\" (measured working"
                + " 2026-09-14). ⛔ A postgresql:// or postgres:// URL is NOT that spelling and is read as a"
                + " path too. Or run this node with -D" + Topology.PROPERTY + "=single if it genuinely owns"
                + " its lakehouse alone.");
    }
}
