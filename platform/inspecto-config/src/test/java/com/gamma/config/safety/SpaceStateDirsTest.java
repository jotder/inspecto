package com.gamma.config.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SE-4 step 1: the state zone's resolver — read {@code state/<dir>} first, fall back to {@code config/<dir>}, write config only. */
class SpaceStateDirsTest {

    @Test
    void aSpaceConfigRootMapsEachZoneDirectoryToItsStateTwin(@TempDir Path tmp) {
        Path config = tmp.resolve("demo").resolve("config");
        Path state = tmp.resolve("demo").resolve("state");
        assertEquals(state.toAbsolutePath().normalize(), SpaceStateDirs.stateRoot(config));
        for (String dir : SpaceStateDirs.DIRS)
            assertEquals(state.resolve(dir).resolve("a.json").toAbsolutePath().normalize(),
                    SpaceStateDirs.twin(config.resolve(dir).resolve("a.json")), dir);
        assertEquals(config.resolve("recon-state").toAbsolutePath().normalize(), SpaceStateDirs.writeDir(config, "recon-state"),
                "this release writes config/ only");
    }

    @Test
    void authoredFilesAndTheTwoUnswitchedDirectoriesHaveNoTwin(@TempDir Path tmp) {
        Path config = tmp.resolve("demo").resolve("config");
        for (String rel : List.of("roles.toon", "registry/dataset/x.toon", "publication-approvals/j.json",
                "audit/snapshots/s.json", "orders/orders_pipeline.toon"))
            assertNull(SpaceStateDirs.twin(config.resolve(rel)), rel);
    }

    @Test
    void theSingleTenantWriteRootReadsConfigOnly(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("assist-root"));   // -Dassist.write.root: no Space folder
        Path f = Files.createDirectories(root.resolve("recon-state")).resolve("r.json");
        Files.writeString(Files.createDirectories(tmp.resolve("state").resolve("recon-state")).resolve("r.json"), "s");
        assertNull(SpaceStateDirs.stateRoot(root));
        assertNull(SpaceStateDirs.twin(f));
        assertEquals(f, SpaceStateDirs.readable(f));
        assertFalse(SpaceStateDirs.shadowed(f));
    }

    @Test
    void aFileIsReadFromStateWhenItIsThereElseFromConfig(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("demo").resolve("config");
        Path inConfig = config.resolve("recon-state").resolve("r.json");
        assertEquals(inConfig, SpaceStateDirs.readable(inConfig), "neither exists: the config path");
        Files.writeString(Files.createDirectories(inConfig.getParent()).resolve("r.json"), "c");
        assertEquals(inConfig, SpaceStateDirs.readable(inConfig), "config only");
        assertFalse(SpaceStateDirs.shadowed(inConfig));
        Path inState = tmp.resolve("demo").resolve("state").resolve("recon-state").resolve("r.json");
        Files.writeString(Files.createDirectories(inState.getParent()).resolve("r.json"), "s");
        assertEquals("s", Files.readString(SpaceStateDirs.readable(inConfig)), "both: state wins");
        assertTrue(SpaceStateDirs.shadowed(inConfig));
        Files.delete(inConfig);
        assertEquals("s", Files.readString(SpaceStateDirs.readable(inConfig)), "state only");
    }

    @Test
    void aListingHoldsBothPlacesStateFirstOneEntryPerName(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config").resolve("pending-changes"));
        Path state = Files.createDirectories(tmp.resolve("demo").resolve("state").resolve("pending-changes"));
        Files.writeString(config.resolve("a.json"), "c");
        Files.writeString(config.resolve("b.json"), "c");
        Files.writeString(state.resolve("b.json"), "s");
        Files.writeString(state.resolve("c.json"), "s");
        List<Path> listed = SpaceStateDirs.list(config);
        assertEquals(3, listed.size(), listed.toString());
        for (Path p : listed)
            assertEquals(p.getFileName().toString().equals("a.json") ? "c" : "s", Files.readString(p), p.toString());
        assertEquals(List.of(), SpaceStateDirs.list(tmp.resolve("other").resolve("config").resolve("recon-state")),
                "an absent directory lists nothing");
    }

    @Test
    void aStateFolderThatIsALinkToOutsideTheSpaceIsNeverRead(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Path outside = Files.createDirectories(tmp.resolve("outside").resolve("recon-state"));
        Files.writeString(outside.resolve("r.json"), "evil");
        link(tmp.resolve("demo").resolve("state"), outside.getParent());
        Path f = config.resolve("recon-state").resolve("r.json");
        assertEquals("evil", Files.readString(tmp.resolve("demo").resolve("state").resolve("recon-state").resolve("r.json")),
                "the link works, so the refusal below is the jail");
        assertEquals(f, SpaceStateDirs.readable(f), "a linked state/ is never read");
        assertFalse(SpaceStateDirs.shadowed(f));
        assertEquals(List.of(), SpaceStateDirs.list(config.resolve("recon-state")));
        assertFalse(SpaceStateDirs.linkFree(tmp.resolve("demo"), tmp.resolve("demo").resolve("state")));
    }

    @Test
    void aZoneFolderThatIsALinkToOutsideTheSpaceIsNeverRead(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("r.json"), "evil");
        Files.createDirectories(tmp.resolve("demo").resolve("state"));
        link(tmp.resolve("demo").resolve("state").resolve("recon-state"), outside);
        Path f = config.resolve("recon-state").resolve("r.json");
        assertEquals(f, SpaceStateDirs.readable(f));
        assertFalse(SpaceStateDirs.shadowed(f));
    }

    @Test
    void aSpaceFolderThatIsItselfALinkStillReadsItsState(@TempDir Path tmp) throws Exception {
        Path real = Files.createDirectories(tmp.resolve("elsewhere").resolve("demo"));
        Files.writeString(Files.createDirectories(real.resolve("state").resolve("recon-state")).resolve("r.json"), "s");
        link(tmp.resolve("spaces").resolve("demo"), real);
        Path f = tmp.resolve("spaces").resolve("demo").resolve("config").resolve("recon-state").resolve("r.json");
        assertEquals("s", Files.readString(SpaceStateDirs.readable(f)), "a Space kept elsewhere (a .ref) is not refused");
    }

    @Test
    void nothingIsCreatedByReading(@TempDir Path tmp) throws Exception {
        Path config = Files.createDirectories(tmp.resolve("demo").resolve("config"));
        SpaceStateDirs.readable(config.resolve("agent").resolve("policy.json"));
        SpaceStateDirs.list(config.resolve("agent"));
        assertFalse(Files.exists(tmp.resolve("demo").resolve("state")), "a reader never makes state/");
    }

    /** {@code link} → {@code target} as a symlink, else (Windows without the privilege) a junction; the test is skipped when neither can be made. */
    static void link(Path link, Path target) throws Exception {
        java.nio.file.Files.createDirectories(link.getParent());
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
            return;
        } catch (Exception noSymlink) {
            // fall through to a junction
        }
        boolean made = false;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            made = p.waitFor() == 0 && java.nio.file.Files.isDirectory(link);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(made, "this OS cannot create a symlink or junction here");
    }
}
