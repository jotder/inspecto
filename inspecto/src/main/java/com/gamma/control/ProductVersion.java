package com.gamma.control;

/**
 * The running product's version — the string a support call reads aloud (HOME-VERSION-1, 2026-09-15).
 *
 * <p>ONE source: the fat JAR's manifest, stamped by {@code inspecto/pom.xml}'s shade transformer with
 * {@code Implementation-Version = ${project.version}} and read back through
 * {@link Package#getImplementationVersion()}. Nothing else in the tree knows the number: no filtered
 * resource, no hand-typed constant, and {@code inspecto-ui/package.json}'s {@code 21.0.0} is the Angular
 * scaffold's, not ours — which is exactly why the SPA reads this from {@code GET /bootstrap} instead.
 *
 * <p>A {@code target/classes} run (tests, {@code tools/run-backend.ps1}) has no manifest and reports
 * {@link #DEV}; that is deliberately a WORD, so a wrong-looking number can never be mistaken for a release.
 */
public final class ProductVersion {
    private ProductVersion() {}

    /** What a run with no stamped manifest reports. */
    public static final String DEV = "dev";

    /** {@code -Dproduct.version} overrides the manifest — for the launchers of a bundle that repackages the jar. */
    static final String PROPERTY = "product.version";

    private static final String CURRENT = resolve();

    /** The product version, never null or blank. */
    public static String current() { return CURRENT; }

    private static String resolve() {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) return override.trim();
        Package pkg = ProductVersion.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? DEV : v.trim();
    }
}
