package com.gamma.service;

import com.gamma.config.safety.DiscoveredRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static work distribution — scale-out plan §5.3. Which Spaces a pod hosts, and what it does when the
 * partition map cannot be honoured.
 *
 * <p>🔴 The load-bearing case is the FIRST one: with no {@code partition.toon}, every Space boots exactly
 * as before. Personal and single-node Standard must never need this file.
 */
class SpacePartitionTest {

    @AfterEach
    void clearRoots() {
        DiscoveredRoots.clear();   // process-global — never leak into another test
        System.clearProperty(SpacePartition.ORDINAL_PROPERTY);
    }

    private static void space(Path root, String id) throws Exception {
        Files.createDirectories(root.resolve(id).resolve("config"));
    }

    private static void partition(Path root, String body) throws Exception {
        Files.write(root.resolve(SpacePartition.FILE), body.getBytes(StandardCharsets.UTF_8));
    }

    /** ⛔ The default that must never change: no map ⇒ this pod hosts everything it can see. */
    @Test
    void withNoPartitionFileEverySpaceIsHosted(@TempDir Path root) throws Exception {
        space(root, "orders");
        space(root, "events");
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(2, mgr.size(), "absent a partition.toon, single-node behaviour is unchanged");
            assertTrue(mgr.space(SpaceId.of("orders")).isPresent());
            assertTrue(mgr.space(SpaceId.of("events")).isPresent());
        }
    }

    /** The partition itself: this pod boots its own Spaces and leaves the other pod's alone. */
    @Test
    void onlyThisPodsSpacesAreBooted(@TempDir Path root) throws Exception {
        space(root, "orders");
        space(root, "events");
        partition(root, "spaces:\n  orders: 0\n  events: 1\n");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "0");

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(1, mgr.size(), "pod 0 hosts exactly the spaces mapped to ordinal 0");
            assertTrue(mgr.space(SpaceId.of("orders")).isPresent(), "orders is pod 0's");
            assertTrue(mgr.space(SpaceId.of("events")).isEmpty(),
                    "⛔ events belongs to pod 1 — hosting it here is the double-ownership this prevents");
        }
    }

    /** …and the complement, so the test above cannot pass by simply booting the first directory. */
    @Test
    void theOtherPodBootsTheOtherSpace(@TempDir Path root) throws Exception {
        space(root, "orders");
        space(root, "events");
        partition(root, "spaces:\n  orders: 0\n  events: 1\n");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "1");

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(1, mgr.size());
            assertTrue(mgr.space(SpaceId.of("events")).isPresent(), "events is pod 1's");
            assertTrue(mgr.space(SpaceId.of("orders")).isEmpty());
        }
    }

    /**
     * ⚠ A Space present on disk but in NO map entry is skipped — and boot SUCCEEDS.
     *
     * <p>Deliberately not fatal: zero owners stalls that one Space, whereas refusing to boot would take
     * down every other Space on this pod, so a ConfigMap lagging a new Space directory would turn a small
     * mistake into a pod-wide outage. ⛔ The dangerous violation is TWO owners, never zero.
     */
    @Test
    void anUnassignedSpaceIsSkippedButDoesNotStopTheOthersBooting(@TempDir Path root) throws Exception {
        space(root, "orders");
        space(root, "newcomer");         // added to disk, not yet to the map
        partition(root, "spaces:\n  orders: 0\n");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "0");

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(1, mgr.size(), "the mapped space still boots");
            assertTrue(mgr.space(SpaceId.of("orders")).isPresent());
            assertTrue(mgr.space(SpaceId.of("newcomer")).isEmpty(),
                    "an unowned space is not hosted — but it must not take the pod down with it");
        }
    }

    /**
     * 🔴 A map this pod cannot place itself in is a BOOT FAILURE, not a silent idle.
     *
     * <p>Hosting nothing would leave a pod running and empty; hosting everything would double-host. Neither
     * is discoverable from outside, so it refuses instead — the same call phase A made for
     * {@code -Dinspecto.topology=partitioned}.
     */
    @Test
    void aMapWithNoDeterminablePodOrdinalRefusesToBoot(@TempDir Path root) throws Exception {
        space(root, "orders");
        partition(root, "spaces:\n  orders: 0\n");
        // no -Dinspecto.pod.ordinal, and the test host's HOSTNAME does not end in -N
        org.junit.jupiter.api.Assumptions.assumeTrue(
                SpacePartition.ordinalOfHostname(System.getenv("HOSTNAME")) == null,
                "this assertion needs a host whose name carries no StatefulSet ordinal");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> SpaceManager.discover(root));
        assertTrue(boom.getMessage().contains("ordinal is unknown"), boom.getMessage());
    }

    /**
     * 🔴 The anti-fail-soft guard. Every other global TOON file in this tree defaults on a parse failure;
     * here the default would be "host everything", i.e. exactly the invariant violation the file prevents —
     * and it would show up as duplicate processing, not as a config error.
     *
     * <p>⛔ If someone ever wraps the parse in a catch-and-default, these are the assertions that fail.
     */
    @Test
    void aMalformedMapRefusesToBootRatherThanHostingEverything(@TempDir Path root) throws Exception {
        space(root, "orders");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "0");

        partition(root, "pods:\n  orders: 0\n");          // no `spaces:` section
        IllegalStateException noSection = assertThrows(IllegalStateException.class,
                () -> SpaceManager.discover(root));
        assertTrue(noSection.getMessage().contains("no 'spaces:' section"), noSection.getMessage());

        partition(root, "spaces:\n  orders: primary\n");  // ordinal is not a number
        IllegalStateException notAnOrdinal = assertThrows(IllegalStateException.class,
                () -> SpaceManager.discover(root));
        assertTrue(notAnOrdinal.getMessage().contains("not a pod ordinal"), notAnOrdinal.getMessage());
    }

    /**
     * ⚠ An EMPTY {@code spaces:} section is refused too, rather than read as "host everything".
     * Deleting the file says that deliberately; an empty section is far likelier to be a truncated render.
     */
    @Test
    void anEmptyMapIsRefusedRatherThanTreatedAsNoMap(@TempDir Path root) throws Exception {
        space(root, "orders");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "0");
        partition(root, "spaces:\n");

        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> SpaceManager.discover(root));
        assertTrue(boom.getMessage().contains("EMPTY 'spaces:' section")
                        || boom.getMessage().contains("no 'spaces:' section"),
                boom.getMessage());
    }

    /** The StatefulSet convention, so the ordinary deployment needs no extra flag. */
    @Test
    void thePodOrdinalIsReadFromAStatefulSetHostname() {
        assertEquals(0, SpacePartition.ordinalOfHostname("inspecto-0"));
        assertEquals(2, SpacePartition.ordinalOfHostname("inspecto-2"));
        assertEquals(11, SpacePartition.ordinalOfHostname("inspecto-control-plane-11"));
        assertNull(SpacePartition.ordinalOfHostname("laptop"), "a plain host is not a partitioned deploy");
        assertNull(SpacePartition.ordinalOfHostname("inspecto-"), "a trailing dash yields no ordinal");
        assertNull(SpacePartition.ordinalOfHostname("inspecto-blue"), "a non-numeric suffix is not an ordinal");
        assertNull(SpacePartition.ordinalOfHostname(null));
    }

    /** ⛔ An explicit -D beats HOSTNAME: a deployment that states its ordinal is never second-guessed. */
    @Test
    void theStatedOrdinalWinsOverTheHostname(@TempDir Path root) throws Exception {
        space(root, "events");
        partition(root, "spaces:\n  orders: 0\n  events: 1\n");
        System.setProperty(SpacePartition.ORDINAL_PROPERTY, "1");

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertTrue(mgr.space(SpaceId.of("events")).isPresent(),
                    "-D" + SpacePartition.ORDINAL_PROPERTY + " decides, whatever HOSTNAME says");
        }
    }
}
