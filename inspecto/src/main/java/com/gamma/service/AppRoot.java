package com.gamma.service;

import java.nio.file.Path;

/**
 * The install root every relative root path in this deployment resolves against ({@code SPACE-RELOCATABLE-ROOTS-1}):
 * {@code -Dapp.root} or {@code APP_ROOT}, else the process working directory.
 *
 * <p><b>Why it is explicit.</b> The working directory was already load-bearing for the {@code ./spaces} default
 * ({@link com.gamma.control.ControlApi}), and the launchers {@code cd} to the install directory before starting the
 * server. Now that {@code spaces.ref}, {@code space.toon data_root} and {@code <id>.ref} may all be written relative,
 * a server started from another directory would silently re-point every Space's data — a wrong-but-booting state,
 * which is worse than a refusal. Naming and logging the root turns that into a one-line check.
 *
 * <p>⛔ This is NOT a jail and grants nothing. It is only the base a relative path resolves against; what a resolved
 * path is then allowed to be is {@link SpaceDataRoot} and {@code PathJail}'s business, identically for a path that
 * was written absolute.
 */
public final class AppRoot {

    /** The JVM flag naming the install root; {@code APP_ROOT} is its environment twin. */
    public static final String PROPERTY = "app.root";

    /** The environment variable naming the install root, for deployments that set env rather than JVM flags. */
    public static final String ENV = "APP_ROOT";

    private AppRoot() {}

    /** The install root: {@code -Dapp.root}, else {@code APP_ROOT}, else the working directory — absolute and normalized. */
    public static Path current() {
        String flag = System.getProperty(PROPERTY);
        if (flag == null || flag.isBlank()) flag = System.getenv(ENV);
        return (flag == null || flag.isBlank() ? Path.of("") : Path.of(flag.trim())).toAbsolutePath().normalize();
    }

    /** {@code path} when {@link #rooted}, else resolved against {@link #current()}; absolute and normalized either way. */
    public static Path resolve(String path) {
        Path p = Path.of(path.trim());
        return (rooted(p) ? p : current().resolve(p)).toAbsolutePath().normalize();
    }

    /**
     * Whether {@code p} names a location in its own right rather than one relative to the app root.
     *
     * <p>⚠ NOT {@link Path#isAbsolute()}. On Windows {@code Path.of("/mnt/bigdisk").isAbsolute()} is {@code false}
     * — the path is rooted but names no drive — so an operator's POSIX path would be taken for a relative one,
     * silently skipping the checks an absolute root gets and landing on the current drive. {@link Path#getRoot()}
     * is non-null for both {@code /mnt/bigdisk} and {@code C:\data}, and null only for a genuinely relative path.
     */
    public static boolean rooted(Path p) {
        return p.getRoot() != null;
    }
}
