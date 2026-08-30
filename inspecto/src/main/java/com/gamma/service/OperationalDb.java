package com.gamma.service;

import com.gamma.acquire.SecretResolver;

import java.util.List;
import java.util.function.Function;

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
 * {@code inspecto-engine/pom.xml}: "runtime stays JDBC-driver-free"); the driver rides the
 * <b>Standard/Enterprise bundle as the {@code postgresql.jar} sidecar</b> (PG-1, decided 2026-08-14),
 * which {@code serve.sh}/{@code serve.bat} auto-detect and add to the classpath — the same mechanism
 * as {@code inspecto-security.jar}, so the fat JAR and its SBOM stay driver-free. On a Personal
 * bundle this is the expected failure and the message says what to drop in rather than leaving an
 * operator to infer it.
 *
 * <p><b>The password is a {@link SecretResolver} reference, never required in the clear</b> (PG-1's
 * second decision, same date): {@code -Dinspecto.db.password} takes {@code ${ENV:PGPASSWORD}},
 * {@code ${KEYSTORE:opsdb}}, {@code ${FILE:/run/secrets/pg}} or a literal, expanded at use — the
 * exact {@code auth.oidc.clientSecret} precedent, so the value need not sit on the process command
 * line and {@code secrets.keystore.*} is supported without a new mechanism.
 */
final class OperationalDb {

    /** The PostgreSQL JDBC driver class, probed by name — never linked against. */
    private static final String PG_DRIVER = "org.postgresql.Driver";

    private OperationalDb() {}

    /**
     * The operational store families — <b>the roster</b>. Every family's property names live here and
     * nowhere else, so the store openers and any diagnostic read of "what is this deployment using"
     * cannot drift apart: naming a family that is not on this list stops compiling.
     *
     * <p>⛔ Introduced 2026-08-15 because they had been ten <b>string literals</b> across
     * {@link ServiceStores} and {@link SpaceBootstrap}, which is the same shape as the mirrored ledger
     * header and the {@code REQUEST_SCOPED_ATTRS} roster — a copy that silently goes stale.
     *
     * <p>⚠ Three irregularities that are REAL and must not be flattened:
     * <ul>
     *   <li><b>Three different "is it on" spellings.</b> The first four accept
     *       {@code duckdb}/{@code postgres}/a raw {@code jdbc:} URL; the Objects family and status accept
     *       {@code db}; each has its own default ({@code none}, {@code duckdb}, {@code memory},
     *       {@code file}).</li>
     *   <li><b>A {@code *.backend} that starts with {@code jdbc:} IS the URL</b> and bypasses this class
     *       entirely — a third source beyond the per-family and shared properties.</li>
     *   <li><b>URL grain ≠ credential grain.</b> The four Objects families each have their own
     *       {@code *.db.url} but share one {@code objects.db.user}/{@code .password}.</li>
     * </ul>
     */
    enum Family {
        JOB_RUNS("Job runs", "jobs.backend", "none", Mode.URL_OR_ENGINE,
                "jobs.db.url", null, null, SpaceRoot::jobRunDbUrl),
        PROVENANCE("Provenance", "provenance.backend", "none", Mode.URL_OR_ENGINE,
                "provenance.db.url", null, null, SpaceRoot::provenanceDbUrl),
        CONSIGNMENT_OUTPUTS("Consignment outputs", "consignment.outputs.backend", "duckdb", Mode.URL_OR_ENGINE,
                "consignment.outputs.db.url", null, null, SpaceRoot::consignmentOutputsDbUrl),
        FILE_STAGES("File stages", "file.stages.backend", "none", Mode.URL_OR_ENGINE,
                "file.stages.db.url", null, null, SpaceRoot::fileStagesDbUrl),
        OBJECTS("Objects", "objects.backend", "memory", Mode.DB_FLAG,
                "objects.db.url", "objects.db.user", "objects.db.password", SpaceRoot::objectsDbUrl),
        LINKS("Links", "objects.backend", "memory", Mode.DB_FLAG,
                "objects.links.db.url", "objects.db.user", "objects.db.password", SpaceRoot::linksDbUrl),
        NOTES("Notes", "objects.backend", "memory", Mode.DB_FLAG,
                "objects.notes.db.url", "objects.db.user", "objects.db.password", SpaceRoot::notesDbUrl),
        TAGS("Tag assignments", "objects.backend", "memory", Mode.DB_FLAG,
                "objects.tags.db.url", "objects.db.user", "objects.db.password", SpaceRoot::tagAssignmentsDbUrl),
        // ⚠ Flipping this to "db" — serving the three ledgers from a database rather than off CSV —
        // was attempted 2026-08-31 and REVERTED. It is a one-word change here, and
        // ServiceStores.openStatusStore is already written for it (single declaration + degrade rather
        // than fail-boot). 🔴 THE BLOCKER IS FRESHNESS, not isolation: CollectorService.syncStatus()
        // projects the on-disk audit into this store exactly ONCE, at boot. Nothing re-projects it when
        // a batch commits, so a DB-backed store serves a snapshot frozen at startup — a run triggered
        // after boot reports no commits at all, which is how ControlApiMultiSpaceTest failed (it read
        // its OWN commit back as empty; there was no cross-space leak — statusDbUrl() is per space).
        // ⛔ Do not move this default until the projection refreshes on commit; BatchEventBus already
        // publishes the event to hang it on. A second, smaller question rides along: the status DuckDB
        // then appears as a BUSINESS store in /db/catalog, where an operational store arguably does not
        // belong.
        STATUS("Status", "status.backend", "file", Mode.DB_FLAG,
                "status.db.url", "status.db.user", "status.db.password", SpaceRoot::statusDbUrl),
        ACQUISITION_LEDGER("Acquisition ledger", "acquire.ledger.backend", "memory", Mode.DB_FLAG,
                "acquire.ledger.db.url", null, null, SpaceRoot::acquisitionLedgerDbUrl);

        /** How a family spells "enabled" on its {@code *.backend} property — they genuinely differ. */
        enum Mode {
            /** {@code duckdb} | {@code postgres} | a raw {@code jdbc:…} URL (which then IS the URL). */
            URL_OR_ENGINE,
            /** {@code db}, against a non-DB default ({@code memory} / {@code file}). */
            DB_FLAG
        }

        final String label;
        final String backendProperty;
        final String backendDefault;
        final Mode mode;
        final String urlProperty;
        /** May be {@code null} — several families open with a URL and no credentials at all. */
        final String userProperty;
        final String passwordProperty;
        private final Function<SpaceRoot, String> spaceDefault;

        Family(String label, String backendProperty, String backendDefault, Mode mode, String urlProperty,
               String userProperty, String passwordProperty, Function<SpaceRoot, String> spaceDefault) {
            this.label = label;
            this.backendProperty = backendProperty;
            this.backendDefault = backendDefault;
            this.mode = mode;
            this.urlProperty = urlProperty;
            this.userProperty = userProperty;
            this.passwordProperty = passwordProperty;
            this.spaceDefault = spaceDefault;
        }

        String spaceDefault(SpaceRoot root) { return spaceDefault.apply(root); }
    }

    /** Where a family's effective URL came from — the question a diagnostic read exists to answer. */
    enum Source {
        /** The family's own {@code *.backend} carried a raw {@code jdbc:} URL, bypassing everything else. */
        BACKEND_PROPERTY,
        /** The family's own {@code -D<family>.db.url}. */
        FAMILY_PROPERTY,
        /** The shared {@code -Dinspecto.db.url}. */
        SHARED_PROPERTY,
        /** No property set — the space's own embedded DuckDB file. */
        SPACE_DEFAULT,
        /** The family's {@code *.backend} leaves it off; no database is opened at all. */
        DISABLED
    }

    /**
     * One family's effective configuration. {@code url} is {@code null} exactly when
     * {@code source == DISABLED}. ⛔ Carries no password, in any form — see {@code SystemRoutes}.
     */
    record Resolved(Family family, Source source, String url, String user) {
        boolean enabled() { return source != Source.DISABLED; }
    }

    /** Every family's effective configuration, in roster order. */
    static List<Resolved> resolveAll(SpaceRoot root) {
        return java.util.Arrays.stream(Family.values()).map(f -> resolve(f, root)).toList();
    }

    /**
     * A family's effective configuration — the same three-way the store openers perform, in one place so
     * a diagnostic read cannot report a URL the store is not actually using.
     */
    static Resolved resolve(Family f, SpaceRoot root) {
        String backend = System.getProperty(f.backendProperty, f.backendDefault).trim();
        if (f.mode == Family.Mode.URL_OR_ENGINE) {
            String lower = backend.toLowerCase();
            if (lower.startsWith("jdbc:"))
                return new Resolved(f, Source.BACKEND_PROPERTY, backend, reportedUser(f));
            if (!"duckdb".equals(lower) && !"postgres".equals(lower) && !"postgresql".equals(lower))
                return new Resolved(f, Source.DISABLED, null, null);
        } else if (!"db".equalsIgnoreCase(backend)) {
            return new Resolved(f, Source.DISABLED, null, null);
        }
        String explicit = System.getProperty(f.urlProperty);
        if (explicit != null && !explicit.isBlank())
            return new Resolved(f, Source.FAMILY_PROPERTY, explicit.trim(), reportedUser(f));
        String shared = url();
        return shared != null
                ? new Resolved(f, Source.SHARED_PROPERTY, shared, reportedUser(f))
                : new Resolved(f, Source.SPACE_DEFAULT, f.spaceDefault(root), reportedUser(f));
    }

    /**
     * The user a family <b>actually connects as</b>, for the report — {@code null} when it sends no
     * credentials at all.
     *
     * <p>⚠ Not {@link #userFor}, deliberately. {@code userFor} falls back to the shared
     * {@code -Dinspecto.db.user} so a credentialed family can inherit it, but the five families with a
     * {@code null} {@code userProperty} open via {@code open(url)} and pass <b>no user and no
     * password</b> ({@code DbJobRunStore}, {@code DbProvenanceStore}, {@code DbConsignmentOutputStore},
     * {@code DbFileStageStore}, {@code AcquisitionLedgers}). Reporting the shared user for them would
     * name a credential the store never sends — the "URL grain ≠ credential grain" trap, and a
     * diagnostic that exists to be trusted must not guess.
     */
    private static String reportedUser(Family f) {
        return f.userProperty == null ? null : userFor(f);
    }

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

    /** The shared password, with a {@code ${…}} reference expanded at use; a literal passes through. */
    static String password() { return SecretResolver.resolve(System.getProperty("inspecto.db.password")); }

    /**
     * Fail closed at boot if {@code postgres} was selected but cannot be honoured — a missing driver or
     * a missing URL. Called once from service bootstrap, before any store opens, so the operator gets
     * one actionable error instead of N scattered WARNs and a quietly reduced service.
     *
     * @throws IllegalStateException naming the property at fault and what to do about it
     */
    /** Whether the PostgreSQL driver is on the classpath — the sidecar question, asked without throwing. */
    static boolean driverAvailable() {
        try {
            Class.forName(PG_DRIVER);
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

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
                            + ") is not on the classpath. The Standard/Enterprise bundle ships it as the"
                            + " postgresql.jar sidecar (auto-detected by serve.sh/serve.bat); the Personal"
                            + " bundle ships DuckDB only — drop postgresql.jar beside inspecto.jar.", missing);
        }
    }

    /**
     * The URL a store should open: an explicit per-family {@code -D<family>.db.url} first (back-compat,
     * and the escape hatch for pointing one store somewhere else), then the shared operational URL,
     * then the space's own DuckDB file.
     *
     * <p>⚠ Takes a {@link Family}, not a property name — that is what keeps the roster load-bearing.
     * The caller still supplies the space default because several openers reach it by a path this class
     * should not know (a legacy root, a {@code jdbc:} backend value that already decided).
     */
    static String urlFor(Family family, String spaceDefault) {
        String explicit = System.getProperty(family.urlProperty);
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String shared = url();
        return shared != null ? shared : spaceDefault;
    }

    /** As {@link #urlFor}, for the credential half — a per-family value first, then the shared one. */
    static String userFor(Family family) {
        if (family.userProperty == null) return user();
        String explicit = System.getProperty(family.userProperty);
        return explicit != null ? explicit : user();
    }

    /** As {@link #userFor}, for the password — a per-family value may also be a {@code ${…}} reference. */
    static String passwordFor(Family family) {
        if (family.passwordProperty == null) return password();
        String explicit = System.getProperty(family.passwordProperty);
        return explicit != null ? SecretResolver.resolve(explicit) : password();
    }
}
