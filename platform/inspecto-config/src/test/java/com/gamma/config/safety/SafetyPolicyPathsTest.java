package com.gamma.config.safety;

import com.gamma.util.CurrentSpace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice S3 of {@code policy-narrowing-design.md}: {@code allow.roots} / {@code deny.roots} reach the path jail,
 * the P0-c twin (an absolute path into a sibling Space), the hand-over of the pin to a worker thread. Every
 * refusal sits beside a twin that must succeed.
 */
class SafetyPolicyPathsTest {

    @TempDir Path tmp;

    private String savedRoots;

    /** The surefire JVM declares the working tree + tmp as operator roots, which would swallow every Space base. */
    @BeforeEach
    void noOperatorRoots() {
        savedRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", "");
    }

    @AfterEach
    void clean() {
        if (savedRoots == null) System.clearProperty("assist.safety.roots");
        else System.setProperty("assist.safety.roots", savedRoots);
        DiscoveredRoots.clear();
    }

    private Path space(String id) throws IOException {
        Path b = Files.createDirectories(tmp.resolve(id));
        Files.createDirectories(b.resolve("config"));
        DiscoveredRoots.register(id, b);
        return b;
    }

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    private static void policy(Path space, String text) throws IOException {
        Files.writeString(space.resolve("config").resolve(SafetyPolicyFiles.FILE), text);
    }

    /** The jail verdict the act sites reach: allow roots + deny roots of one Space's effective policy. */
    private static Path jail(String spaceId, Path candidate) {
        SafetyPolicy p = SafetyPolicy.forSpace(spaceId);
        return PathJail.requireUnderAny(p.allowedRoots(), p.denyRoots(), candidate.toString(), "t");
    }

    @Test
    void p0cTwin_anAbsolutePathIntoASiblingSpaceIsRefusedAndTheSpacesOwnPathPasses() throws IOException {
        Path s1 = space("s1");
        Path s10 = space("s10");          // a string-prefix sibling of s1
        assertDoesNotThrow(() -> jail("s1", s1.resolve("data").resolve("x")));      // twin: own base
        assertThrows(PathJail.Escape.class, () -> jail("s1", s10.resolve("data").resolve("x")));
    }

    @Test
    void aSpaceAllowRootsNarrowsTheJailToASubtree() throws IOException {
        Path s1 = space("s1");
        policy(s1, "allow:\n  roots[1]: " + fwd(s1.resolve("data")) + "\n");
        assertDoesNotThrow(() -> jail("s1", s1.resolve("data").resolve("out").resolve("x")));   // twin
        assertThrows(PathJail.Escape.class, () -> jail("s1", s1.resolve("config").resolve("x")));
        assertThrows(PathJail.Escape.class, () -> jail("s1", s1.resolve("database").resolve("x")));  // prefix, not boundary
    }

    @Test
    void aSpaceCannotAllowARootOutsideItsOwnBase() throws IOException {
        Path s1 = space("s1");
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        policy(s1, "allow:\n  roots[1]: " + fwd(elsewhere) + "\n");
        // The intersection of {s1} and {elsewhere} is empty, and an empty root list refuses everything.
        assertThrows(IllegalArgumentException.class, () -> jail("s1", elsewhere.resolve("x")));
        assertThrows(IllegalArgumentException.class, () -> jail("s1", s1.resolve("data")));
    }

    @Test
    void denyRootsBeatAllowRoots() throws IOException {
        Path s1 = space("s1");
        policy(s1, "deny:\n  roots[1]: " + fwd(s1.resolve("secrets")) + "\n");
        Path refused = s1.resolve("secrets").resolve("k");
        PathJail.Escape e = assertThrows(PathJail.Escape.class, () -> jail("s1", refused));
        assertTrue(e.getMessage().contains("denied root"), e.getMessage());
        assertDoesNotThrow(() -> jail("s1", s1.resolve("data").resolve("k")));                   // twin
        assertDoesNotThrow(() -> jail("s1", s1.resolve("secrets2").resolve("k")));               // boundary, not prefix
    }

    @Test
    void theSameDenyReachesTheThreeArgJailAndTheValidatorThroughTheCurrentSpace() throws IOException {
        Path s1 = space(CurrentSpace.DEFAULT_SPACE_ID);
        policy(s1, "deny:\n  roots[1]: " + fwd(s1.resolve("secrets")) + "\n");
        Path refused = s1.resolve("secrets").resolve("k");
        assertThrows(PathJail.Escape.class,
                () -> PathJail.requireUnderAny(PathJail.allowedRoots(), refused.toString(), "t"));
        assertDoesNotThrow(() -> PathJail.requireUnderAny(PathJail.allowedRoots(), s1.resolve("data").toString(), "t"));
        assertEquals(List.of(s1.resolve("secrets").toAbsolutePath().normalize()), PathJail.deniedRoots());
    }

    @Test
    void aWorkerThreadIsHandedThePinnedPolicyNotARereadOfTheFiles() throws Exception {
        Path s1 = space(CurrentSpace.DEFAULT_SPACE_ID);
        Path file = s1.resolve("config").resolve(SafetyPolicyFiles.FILE);
        policy(s1, "deny:\n  roots[1]: " + fwd(s1.resolve("secrets")) + "\n");
        Path target = s1.resolve("secrets").resolve("k");

        AtomicReference<Boolean> handedRefused = new AtomicReference<>();
        AtomicReference<Boolean> rereadRefused = new AtomicReference<>();
        SafetyPolicy.pinnedForRun(() -> {
            var pin = SafetyPolicy.pinned();
            assertTrue(pin.isPresent());
            try {
                Files.delete(file);                       // loosened mid-run: the next run sees it, this one must not
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Thread handed = Thread.ofVirtual().unstarted(() -> {
                        handedRefused.set(SafetyPolicy.runWithPinned(pin.get(), () -> denied(target)));
            });
            Thread reread = Thread.ofVirtual().unstarted(() -> {
                        rereadRefused.set(denied(target));        // twin: no hand-over, the pin is not visible here
            });
            try {
                handed.start();
                handed.join();
                reread.start();
                reread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        assertEquals(Boolean.TRUE, handedRefused.get(), "the worker handed the pin keeps the run's deny");
        assertEquals(Boolean.FALSE, rereadRefused.get(), "without the hand-over the worker re-reads the loosened files");
    }

    private static boolean denied(Path target) {
        try {
            PathJail.requireUnderAny(PathJail.allowedRoots(), target.toString(), "t");
            return false;
        } catch (PathJail.Escape e) {
            return true;
        }
    }

    @Test
    void baseRootsNeverReadsAPolicyFileSoAnUnreadableOneCannotThrowThere() throws IOException {
        Path s1 = space("s1");
        policy(s1, "alow:\n  roots[1]: x\n");              // unknown key: unreadable
        assertThrows(SafetyPolicyUnreadableException.class, () -> SafetyPolicy.forSpace("s1"));
        assertEquals(List.of(s1.toAbsolutePath().normalize()), SafetyPolicy.baseRoots("s1"));
    }

    @Test
    void aWorkerHandedTheRunWithPinnedSeesBothTheDenyRootsAndTheEgressTier() throws Exception {
        Path s1 = space(CurrentSpace.DEFAULT_SPACE_ID);
        policy(s1, "deny:\n  roots[1]: " + fwd(s1.resolve("secrets")) + "\n  hosts[1]: bad.example\n");
        Path target = s1.resolve("secrets").resolve("k");

        AtomicReference<Boolean> pathRefused = new AtomicReference<>();
        AtomicReference<Boolean> hostRefused = new AtomicReference<>();
        AtomicReference<Boolean> okHostPasses = new AtomicReference<>();
        SafetyPolicy.pinnedForRun(() -> {
            var pin = SafetyPolicy.pinned().orElseThrow();
            try {
                Files.delete(s1.resolve("config").resolve(SafetyPolicyFiles.FILE));     // loosened mid-run
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Thread handed = Thread.ofVirtual().unstarted(() -> SafetyPolicy.runWithPinned(pin, () -> {
                pathRefused.set(denied(target));
                hostRefused.set(refusesHost("bad.example"));
                okHostPasses.set(!refusesHost("good.example"));
                return null;
            }));
            try {
                handed.start();
                handed.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        assertEquals(Boolean.TRUE, pathRefused.get(), "deny roots survive on the worker");
        assertEquals(Boolean.TRUE, hostRefused.get(), "the egress tier survives on the worker");
        assertEquals(Boolean.TRUE, okHostPasses.get(), "twin: an unlisted host still passes");
    }

    private static boolean refusesHost(String host) {
        try {
            EgressGate.current().require(host, 443, "t");
            return false;
        } catch (EgressRefusedException e) {
            return true;
        }
    }
}
