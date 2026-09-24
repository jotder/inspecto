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

    /** Every mutating registration, gated or not: method( "pattern" — the same shape the audit counted 332 with. */
    private static final Pattern MUTATING = Pattern.compile(
            "api\\.(post|put|patch|delete)\\(\\s*\"([^\"]+)\"");

    /**
     * Route-gating compliance plan step 2c/3d (2026-09-15): a mutating route ends in exactly ONE of three
     * recorded states — a capability ({@code ENTRIES}), an exemption with a category and a reason
     * ({@code EXEMPTIONS}), or an open operator call ({@code PENDING_OPERATOR_CALLS}). A route in none of them
     * fails the build; so does a route in two. This is the ratchet's BELT: it sees every module's source,
     * which the runtime inventory (step 3a/3c, not yet built) cannot for optional modules absent from a
     * bundle. Reads are open by design and are not scanned (operator, 2026-09-15).
     */
    @Test
    void everyMutatingRouteIsGatedExemptOrPending() throws IOException {
        Set<String> gated = new LinkedHashSet<>();
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES) gated.add(route(e.method(), e.pattern()));
        Set<String> exempt = new LinkedHashSet<>();
        for (CapabilityManifest.Exemption x : CapabilityManifest.EXEMPTIONS) {
            assertTrue(exempt.add(route(x.method(), x.pattern())), () -> "duplicate exemption: " + x);
            assertFalse(x.reason() == null || x.reason().isBlank(), () -> "an exemption needs a reason: " + x);
        }
        Set<String> pending = new LinkedHashSet<>();
        for (CapabilityManifest.Pending p : CapabilityManifest.PENDING_OPERATOR_CALLS)
            assertTrue(pending.add(route(p.method(), p.pattern())), () -> "duplicate pending call: " + p);

        Set<String> registered = new LinkedHashSet<>();
        for (Path root : routeSourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = MUTATING.matcher(Files.readString(f));
                    while (m.find()) registered.add(route(m.group(1), m.group(2)));
                }
            }
        }
        assertFalse(registered.isEmpty(), "source scan found no mutating registrations — scan broken?");

        Set<String> unclassified = new LinkedHashSet<>(registered);
        unclassified.removeAll(gated);
        unclassified.removeAll(exempt);
        unclassified.removeAll(pending);
        assertTrue(unclassified.isEmpty(), () -> "mutating routes in NO recorded state — declare a capability with "
                + "ApiContext.withCapability, or an Exemption with a category and a reason: " + unclassified);

        Set<String> twice = new LinkedHashSet<>(exempt);
        twice.retainAll(gated);
        Set<String> pendingButDecided = new LinkedHashSet<>(pending);
        pendingButDecided.removeIf(r -> !gated.contains(r) && !exempt.contains(r));
        assertTrue(twice.isEmpty() && pendingButDecided.isEmpty(), () ->
                "a route may be in ONE table only. Gated AND exempt: " + twice + "; pending but already decided: " + pendingButDecided);

        Set<String> stale = new LinkedHashSet<>(exempt);
        stale.addAll(pending);
        stale.removeAll(registered);
        assertTrue(stale.isEmpty(), () -> "exempt/pending routes that are no longer registered: " + stale);

        // The third state exists only while the operator has not answered; it can shrink, never grow.
        assertTrue(pending.size() <= 4, () -> "PENDING_OPERATOR_CALLS may only shrink — new routes take a "
                + "capability or an exemption, never a pending row: " + pending);
    }

    /**
     * SEC review F2 (2026-09-24): the notification feed's read/archive state and the preference grid are one
     * shared state per Space — neither store is keyed by a recipient — so these four writes were never "the
     * caller's own", which is what their old {@code self-service} exemption claimed. Pinned so a re-exemption
     * has to delete this test, not just move a line between tables.
     */
    @Test
    void sharedNotificationStateWritesStayAdminGated() {
        Set<String> adminGated = new LinkedHashSet<>();
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES)
            if (Roles.CAN_ADMINISTER.equals(e.capability())) adminGated.add(route(e.method(), e.pattern()));
        for (String r : new String[] {"POST /notifications/read-all", "POST /notifications/([^/]+)/read",
                "PUT /notifications/preferences", "DELETE /notifications/(?!suppressions$)([^/]+)"})
            assertTrue(adminGated.contains(r), () -> r + " writes one global per-Space state and must stay canAdminister");
        for (CapabilityManifest.Exemption x : CapabilityManifest.EXEMPTIONS)
            assertFalse(x.pattern().startsWith("/notifications"),
                    () -> "no /notifications write is caller-scoped, so none may be exempt: " + x);
    }

    private static String route(String method, String pattern) {
        return method.toUpperCase(Locale.ROOT) + " " + pattern;
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
