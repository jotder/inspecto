package com.gamma.la.core;

import com.gamma.acquire.SecretResolver;
import com.gamma.control.ApiException;
import com.gamma.control.ErrorCodes;
import com.gamma.control.SpiSlot;

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

    private static final SpiSlot<InvestigationStoreProvider> SLOT = new SpiSlot<>(InvestigationStoreProvider.class, true);

    private InvestigationStores() {}

    /** The Investigation store of the Space whose write root is {@code writeRoot}. Cheap: the filesystem one holds no state; a database one is cached by its provider. */
    public static InvestigationStore of(Path writeRoot) {
        String backend = System.getProperty(BACKEND_PROPERTY, "fs").trim().toLowerCase(Locale.ROOT);
        switch (backend) {
            case "fs":
                return new FsInvestigationStore(writeRoot);
            case "db":
                return database(writeRoot);
            default:
                throw unavailable("-D" + BACKEND_PROPERTY + "=" + backend + " is not a backend (fs or db)");
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
            return provider.open(spaceId(writeRoot), new InvestigationStoreProvider.Connection(url, user, password));
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
