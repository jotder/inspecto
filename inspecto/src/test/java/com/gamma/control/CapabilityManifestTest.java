package com.gamma.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBAC R4: {@link CapabilityManifest} must match the actual {@code ApiContext.withCapability}
 * registration sites <b>exactly</b>, both directions — the declared table is only an audit surface
 * if drifting from the code fails the build. The scan reads the route sources under
 * {@code src/main/java/com/gamma/control} (surefire's working directory is the module root), using
 * the same shape every registration site follows: pattern literal and capability literal on the
 * registration call.
 */
class CapabilityManifestTest {

    /**
     * The core route sources. ⚠ Since 2026-09-07 (EDG-01 cell 3a) {@link RouteModule} is public and an
     * optional module in a SIBLING reactor module may register gated routes too — so the scan also walks
     * every {@code ../<module>/src/main/java} tree ({@link #routeSourceRoots}). Before that widening the
     * guard silently exempted any gated route that left this directory: a guard whose scope is narrower
     * than the thing it guards is an exemption nobody wrote down.
     */
    private static final Path ROUTES_DIR = Path.of("src", "main", "java", "com", "gamma", "control");

    /**
     * Every reactor module's main source tree, walked recursively — this module's included, so the historic
     * {@link #ROUTES_DIR} is covered as a special case. Surefire's working directory is the module root,
     * hence {@code ..} is the reactor root. There is no {@code target/} under {@code src/main/java}, so nothing
     * needs skipping.
     */
    private static java.util.List<Path> routeSourceRoots() throws IOException {
        java.util.List<Path> roots = new java.util.ArrayList<>();
        Path reactor = Path.of("..").toAbsolutePath().normalize();
        try (Stream<Path> siblings = Files.list(reactor)) {
            for (Path sibling : siblings.filter(Files::isDirectory).sorted().toList()) {
                Path src = sibling.resolve(Path.of("src", "main", "java"));
                if (Files.isDirectory(src)) roots.add(src);
            }
        }
        return roots;
    }

    /** method( "pattern", ApiContext.withCapability( "capability" — whitespace/newline tolerant. */
    private static final Pattern GATE = Pattern.compile(
            "api\\.(get|post|put|patch|delete)\\(\\s*\"([^\"]+)\",\\s*ApiContext\\.withCapability\\(\\s*\"([^\"]+)\"");

    @Test
    void manifestMatchesTheRegistrationSitesExactly() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES) {
            assertTrue(declared.add(key(e.method(), e.pattern(), e.capability())),
                    () -> "duplicate manifest entry: " + e);
        }

        Set<String> registered = scanSources();
        assertFalse(registered.isEmpty(), "source scan found no gated registrations — scan broken?");

        Set<String> missing = new LinkedHashSet<>(registered);
        missing.removeAll(declared);
        Set<String> stale = new LinkedHashSet<>(declared);
        stale.removeAll(registered);
        assertTrue(missing.isEmpty() && stale.isEmpty(), () ->
                "CapabilityManifest and the withCapability registration sites have drifted.\n"
                        + "Gated in code but MISSING from the manifest: " + missing + "\n"
                        + "Declared in the manifest but NOT registered: " + stale);
    }

    @Test
    void vocabularyIsClosedAndFullyGranted() {
        // Every capability a route demands is grantable by the seed table somewhere — the orphan-
        // capability class of bug (five capabilities no role granted, pre-R1) must not recur.
        Set<String> grantedBySeed = new HashSet<>();
        Roles.SEED.values().forEach(def -> grantedBySeed.addAll(def.capabilities()));
        for (String cap : CapabilityManifest.capabilities())
            assertTrue(grantedBySeed.contains(cap),
                    () -> "capability '" + cap + "' is demanded by a route but granted by NO seed role");

        // ...and the seed grants nothing the routes don't know (stale vocabulary).
        assertEquals(Roles.KNOWN_CAPABILITIES, CapabilityManifest.capabilities(),
                "Roles.KNOWN_CAPABILITIES must be exactly the manifest vocabulary");
        for (String cap : grantedBySeed)
            assertTrue(Roles.KNOWN_CAPABILITIES.contains(cap),
                    () -> "seed grants unknown capability '" + cap + "'");
    }

    private static Set<String> scanSources() throws IOException {
        Set<String> found = new LinkedHashSet<>();
        for (Path root : routeSourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = GATE.matcher(Files.readString(f));
                    while (m.find()) found.add(key(m.group(1), m.group(2), m.group(3)));
                }
            }
        }
        return found;
    }

    private static String key(String method, String pattern, String capability) {
        return method.toUpperCase(Locale.ROOT) + " " + pattern + " -> " + capability;
    }
}
