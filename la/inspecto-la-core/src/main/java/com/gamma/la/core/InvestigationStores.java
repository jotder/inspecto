package com.gamma.la.core;

import com.gamma.auth.secrets.SecretResolver;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.SpiSlot;
import com.gamma.util.StoreHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The one place a caller obtains the {@link InvestigationStore} of a Space; no route names an implementation class.
 *
 * <p><b>Selection (design D-IS8).</b> {@code -Dinvestigations.backend=fs|db}, default {@code fs}: the filesystem under the Space's
 * write root. {@code db} is PostgreSQL through the optional {@code inspecto-la-store-pg} module (an
 * {@link InvestigationStoreProvider} found by {@code ServiceLoader}); its connection is {@code -Dinvestigations.db.url} /
 * {@code .user} / {@code .password}, each falling back to the platform-wide {@code -Dinspecto.db.url} / {@code .user} /
 * {@code .password} (the password may be a {@code ${ENV:NAME}} reference). One backend per Space, no dual write, no import.
 *
 * <p><b>Fail closed.</b> Anything that stops the selected backend serving is {@code 503 CAPABILITY_UNAVAILABLE} naming the cause:
 * an unknown {@code investigations.backend} value, {@code db} with no module in the bundle, {@code db} with no (or a non-PostgreSQL)
 * URL, a database that cannot be reached. It never falls back to the filesystem, because a fall-back on one pod forks the evidence.
 */
public final class InvestigationStores {

    /** {@code -Dinvestigations.backend}: {@code fs} (default) or {@code db}. */
    public static final String BACKEND_PROPERTY = "investigations.backend";
    public static final String URL_PROPERTY = "investigations.db.url";
    public static final String USER_PROPERTY = "investigations.db.user";
    public static final String PASSWORD_PROPERTY = "investigations.db.password";   // secret-allow: the NAME of a property, never its value

    /** The {@code /health/details} family of the selected database store: {@code live.investigations} (UP / DOWN, per Space). */
    public static final String HEALTH_FAMILY = "investigations";

    private static final Logger LOG = LoggerFactory.getLogger(InvestigationStores.class);

    private static final SpiSlot<InvestigationStoreProvider> SLOT = new SpiSlot<>(InvestigationStoreProvider.class, true);

    private InvestigationStores() {}

    /** The Investigation store of the Space whose write root is {@code writeRoot}. Cheap: the filesystem one holds no state; a database one is cached by its provider. */
    public static InvestigationStore of(Path writeRoot) {
        String backend = System.getProperty(BACKEND_PROPERTY, "fs").trim().toLowerCase(Locale.ROOT);
        switch (backend) {
            case "fs":
                return new FsInvestigationStore(writeRoot);
            case "db":
                return reported(writeRoot);
            default:
                throw unavailable("-D" + BACKEND_PROPERTY + "=" + backend + " is not a backend (fs or db)");
        }
    }

    /**
     * The database store of the Space, with the outcome published as {@code live.investigations} on {@code /health/details}
     * (operator decision 2026-10-10: a selected-but-unusable database does NOT stop the boot; it WARNs, shows DOWN, and every request
     * keeps the fail-closed 503). {@link StoreHealth#live} (not {@code record}) because it never throws under a partitioned topology and
     * recovers on the next successful open; the WARN is logged only when the verdict flips, not once per request.
     */
    private static InvestigationStore reported(Path writeRoot) {
        String space = spaceId(writeRoot);
        try {
            InvestigationStore opened = database(writeRoot);
            StoreHealth.Resolved before = StoreHealth.liveOf(space).get(HEALTH_FAMILY);
            StoreHealth.live(space, HEALTH_FAMILY, true, null, "the Investigation database store opened");
            if (before != null && before.status() == StoreHealth.Status.DEGRADED)
                LOG.info("Investigation database store of Space '{}' is usable again", space);
            return opened;
        } catch (ApiException refused) {
            StoreHealth.Resolved before = StoreHealth.liveOf(space).get(HEALTH_FAMILY);
            StoreHealth.live(space, HEALTH_FAMILY, false, null, refused.getMessage()
                    + " - every Investigation request of this Space answers 503 until this is fixed (the connection is the JVM properties -D"
                    + URL_PROPERTY + " / " + USER_PROPERTY + " / " + PASSWORD_PROPERTY + " or -Dinspecto.db.*, which need a restart to change)");
            if (before == null || before.status() != StoreHealth.Status.DEGRADED)
                LOG.warn("Investigation database store of Space '{}' is unusable, serving 503 until fixed: {}", space, refused.getMessage());
            throw refused;
        }
    }

    /**
     * Boot-time check (operator 2026-10-10): when {@code -Dinvestigations.backend=db}, try the store of {@code writeRoot}'s Space once,
     * so an unusable database is WARNed and shown on {@code /health/details} from the start instead of at the first analyst request.
     * Never throws and never stops the boot; a no-op on the filesystem backend.
     */
    public static void probeAtBoot(Path writeRoot) {
        if (writeRoot == null || !"db".equals(System.getProperty(BACKEND_PROPERTY, "fs").trim().toLowerCase(Locale.ROOT))) return;
        try {
            of(writeRoot);
        } catch (RuntimeException refused) {
            // already WARNed and recorded by reported(); the boot goes on
        }
    }

    private static InvestigationStore database(Path writeRoot) {
        java.util.Optional<InvestigationStoreProvider> found;
        try {
            found = SLOT.active();
        } catch (RuntimeException | java.util.ServiceConfigurationError broken) {   // registered but unloadable, or two registered
            throw unavailable("the database Investigation store module is broken: " + broken.getMessage());
        }
        InvestigationStoreProvider provider = found.filter(p -> "db".equals(p.backend())).orElseThrow(() ->
                unavailable("-D" + BACKEND_PROPERTY + "=db but this bundle carries no database Investigation store (inspecto-la-store-pg, Enterprise)"));
        String url = first(System.getProperty(URL_PROPERTY), System.getProperty("inspecto.db.url"));
        if (url == null)
            throw unavailable("-D" + BACKEND_PROPERTY + "=db needs a connection: set -D" + URL_PROPERTY + " (or -Dinspecto.db.url) to jdbc:postgresql://host:5432/db");
        if (!url.regionMatches(true, 0, "jdbc:postgresql:", 0, 16))
            throw unavailable("-D" + BACKEND_PROPERTY + "=db needs a jdbc:postgresql: URL, got " + url.split("\\?", 2)[0]);
        String user = first(System.getProperty(USER_PROPERTY), System.getProperty("inspecto.db.user"));
        String password = SecretResolver.resolve(first(System.getProperty(PASSWORD_PROPERTY), System.getProperty("inspecto.db.password")));
        try {
            return provider.open(spaceId(writeRoot), new InvestigationStoreProvider.Connection(url, user, password),
                    WorkingSetSizeLimit.forRoot(writeRoot));
        } catch (IOException e) {
            throw unavailable("the Investigation database cannot be used: " + e.getMessage());
        }
    }

    /**
     * The Space a write root belongs to. A hosted Space writes into its own {@code spaces/<id>/config}, so the id is the directory above
     * {@code config}; the legacy single-tenant root ({@code -Dassist.write.root}) is the Space {@code default}.
     */
    static String spaceId(Path writeRoot) {
        Path abs = writeRoot.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        if (abs.getFileName() != null && "config".equals(abs.getFileName().toString()) && parent != null && parent.getFileName() != null)
            return parent.getFileName().toString();
        return "default";
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b != null && !b.isBlank() ? b.trim() : null;
    }

    private static ApiException unavailable(String why) {
        return new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, "Investigation store unavailable: " + why);
    }

    /** Test seam: force the provider for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(InvestigationStoreProvider provider) {
        SLOT.forTest(provider);
    }
}
