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
}
