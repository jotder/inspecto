package com.gamma.inspector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BASIC-ONBOARD-SETTLE-1: a run takes from a scoped folder only the files the caller checked. */
class RunFilesTest {

    @Test
    void aScopeNarrowsOnlyItsOwnFolderAndEndsWhenClosed(@TempDir Path dir) throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Path other = Files.createDirectories(dir.resolve("other"));
        File checked = Files.writeString(inbox.resolve("checked.csv"), "a").toFile();
        File late = Files.writeString(inbox.resolve("late.csv"), "b").toFile();
        File elsewhere = Files.writeString(other.resolve("x.csv"), "c").toFile();
        List<File> all = List.of(checked, late, elsewhere);

        assertEquals(all, RunFiles.narrow(all), "no scope: unchanged");
        try (RunFiles.Scope s = RunFiles.only(inbox, Set.of(checked.toPath()))) {
            assertEquals(List.of(checked, elsewhere), RunFiles.narrow(all), "late.csv left out; another folder untouched");
        }
        assertEquals(all, RunFiles.narrow(all), "closed: unchanged");
    }

    /** ADD-FILE-FEED-1: a file the caller knows is whole passes the stability gate; the gate's own verdict is kept. */
    @Test
    void aCompleteScopeReleasesOnlyItsFilesAndEndsWhenClosed(@TempDir Path dir) throws Exception {
        Path upload = Files.writeString(dir.resolve("add_upload.csv"), "a");
        Path fresh = Files.writeString(dir.resolve("drop_fresh.csv"), "b");
        Path settled = Files.writeString(dir.resolve("drop_settled.csv"), "c");
        List<Path> discovered = List.of(upload, fresh, settled), gated = List.of(settled);

        assertEquals(gated, RunFiles.release(discovered, gated, p -> p), "no scope: the gate decides");
        try (RunFiles.Scope s = RunFiles.complete(Set.of(upload))) {
            assertEquals(List.of(upload, settled), RunFiles.release(discovered, gated, p -> p),
                    "the upload joins, in discovery order; the fresh drop still waits");
        }
        assertEquals(gated, RunFiles.release(discovered, gated, p -> p), "closed: the gate decides again");
    }
}
