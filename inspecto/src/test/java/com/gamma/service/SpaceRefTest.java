package com.gamma.service;

import com.gamma.config.safety.DiscoveredRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@code <id>.ref} Space references, booted together with a convention data root (SPACE-DATA-ROOT-1). */
class SpaceRefTest {

    @TempDir Path tmp;

    @AfterEach
    void tearDown() {
        System.clearProperty(SpaceRoot.DATA_ROOT_PROPERTY);
        DiscoveredRoots.clear();
    }

    private Path spacesRoot() throws Exception {
        return Files.createDirectories(tmp.resolve("install").resolve("spaces"));
    }

    private Path elsewhere(String id) throws Exception {
        return Files.createDirectories(tmp.resolve("elsewhere").resolve(id).resolve("config")).getParent();
    }

    @Test
    void aReferencedSpaceBootsFromItsFolderWithDataOnTheMount() throws Exception {
        Path root = spacesRoot();
        Path ext = elsewhere("acme");
        Files.writeString(root.resolve("acme.ref"), ext + "\n");
        Files.createDirectories(root.resolve("local").resolve("config"));
        Path mnt = Files.createDirectories(tmp.resolve("data"));
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());

        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(2, mgr.size(), "the folder Space and the referenced Space both boot");
            SpaceContext acme = mgr.space(SpaceId.of("acme")).orElseThrow();
            assertEquals(ext.toAbsolutePath().normalize(), acme.root().base());
            assertEquals(mnt.resolve("acme"), acme.root().dataRoot());
            assertTrue(Files.isDirectory(mnt.resolve("acme").resolve("duckdb")));
            assertEquals(mnt.resolve("local"), mgr.space(SpaceId.of("local")).orElseThrow().root().dataRoot());
        }
    }

    @Test
    void anUnfollowableReferenceIsSkippedWithAReason() throws Exception {
        Path root = spacesRoot();
        Files.writeString(root.resolve("rel.ref"), "../somewhere/rel");
        Files.writeString(root.resolve("gone.ref"), tmp.resolve("nope").resolve("gone").toString());
        Files.writeString(root.resolve("named.ref"), elsewhere("other-name").toString());
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(0, mgr.size());
            assertEquals(3, mgr.skipped().size());
            assertTrue(mgr.skipped().stream().allMatch(s -> "SPACE_REF_INVALID".equals(s.code())));
        }
    }

    @Test
    void aFolderBesideTheReferenceShadowsIt() throws Exception {
        Path root = spacesRoot();
        Files.writeString(root.resolve("acme.ref"), elsewhere("acme").toString());
        Files.createDirectories(root.resolve("acme").resolve("config"));
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertEquals(root.resolve("acme").toAbsolutePath().normalize(),
                    mgr.space(SpaceId.of("acme")).orElseThrow().root().base(), "the real folder wins");
        }
    }

    @Test
    void overlappingSpacesAreRefusedWhateverTheBootOrder() throws Exception {
        Path root = spacesRoot();
        Path a = elsewhere("a");
        Path b = Files.createDirectories(a.resolve("b").resolve("config")).getParent();   // b nested inside a
        Files.writeString(root.resolve("a.ref"), a.toString());
        Files.writeString(root.resolve("b.ref"), b.toString());
        Path zeta = Files.createDirectories(root.resolve("zeta").resolve("config")).getParent();
        Path demo = elsewhere("demo");   // its data root is inside a Space that boots after it
        Files.writeString(demo.resolve("space.toon"), "data_root: " + zeta.resolve("config").resolve("x").toString().replace('\\', '/') + "\n");
        Files.writeString(root.resolve("demo.ref"), demo.toString());
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertTrue(mgr.space(SpaceId.of("a")).isEmpty());
            assertTrue(mgr.space(SpaceId.of("b")).isEmpty());
            assertTrue(mgr.space(SpaceId.of("demo")).isEmpty());
            assertTrue(mgr.space(SpaceId.of("zeta")).isPresent(), "the Space reached into is not punished");
            assertTrue(mgr.skipped().stream().anyMatch(s -> "SPACE_OVERLAP".equals(s.code())));
        }
    }

    @Test
    void purgeOfAFolderSpaceIgnoresAShadowedReference() throws Exception {
        Path root = spacesRoot();
        Files.writeString(root.resolve("acme.ref"), elsewhere("acme").toString());
        Files.createDirectories(root.resolve("acme").resolve("config"));
        Files.createDirectories(root.resolve("keep").resolve("config"));
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertTrue(mgr.skipped().isEmpty(), "a shadowed reference is not a skipped Space");
            assertTrue(mgr.delete(SpaceId.of("acme"), true));
            assertFalse(Files.exists(root.resolve("acme")), "the folder that booted is purged");
        }
    }

    @Test
    void purgeDropsOnlyTheReferenceAndCreateRespectsIt() throws Exception {
        Path root = spacesRoot();
        Path ext = elsewhere("acme");
        Files.writeString(root.resolve("acme.ref"), ext.toString());
        Files.createDirectories(root.resolve("keep").resolve("config"));
        try (SpaceManager mgr = SpaceManager.discover(root)) {
            assertThrows(IllegalStateException.class, () -> mgr.create(SpaceId.of("acme"), null, null));
            assertTrue(mgr.delete(SpaceId.of("acme"), true));
            assertFalse(Files.exists(root.resolve("acme.ref")));
            assertTrue(Files.isDirectory(ext.resolve("config")), "the referenced folder is never purged");
        }
    }
}
