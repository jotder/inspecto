package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ImportJournal} rollback — all-or-nothing, but never over a newer write (`IMPORT-RESIDUALS-1` (3)). The
 * "concurrent" write is made between the import's write and its rollback, a deterministic interleaving.
 */
class ImportJournalTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anOrdinaryRollbackRestoresEveryFileAndRemovesWhatItCreated(@TempDir Path root) throws Exception {
        Path existing = root.resolve("a_pipeline.toon");
        Files.writeString(existing, "before");
        Path created = root.resolve("sub/deeper/b_schema.toon");

        ImportJournal j = new ImportJournal();
        j.write(existing, b("imported"), ".t-");
        j.write(created, b("imported"), ".t-");
        j.write(existing, b("imported twice"), ".t-");   // a file written twice restores to its FIRST prior

        assertEquals(List.of(), j.rollback(), "nothing changed concurrently");
        assertEquals("before", Files.readString(existing));
        assertFalse(Files.exists(created));
        assertFalse(Files.exists(root.resolve("sub")), "the directories it created are gone");
        assertTrue(j.isEmpty());
    }

    @Test
    void aConcurrentWriteSurvivesTheRollbackAndIsReported_untouchedFilesStillRollBack(@TempDir Path root)
            throws Exception {
        Path shared = root.resolve("a_pipeline.toon");
        Files.writeString(shared, "before");
        Path untouched = root.resolve("c_pipeline.toon");
        Files.writeString(untouched, "untouched before");
        Path created = root.resolve("sub/b_schema.toon");

        ImportJournal j = new ImportJournal();
        j.write(shared, b("imported"), ".t-");
        j.write(untouched, b("imported"), ".t-");
        j.write(created, b("imported"), ".t-");

        // another request saves `shared` and adds its own file beside `created` before the import rolls back
        Files.writeString(shared, "a legitimate concurrent save");
        Files.writeString(root.resolve("sub/theirs_schema.toon"), "theirs");

        List<Path> left = j.rollback();
        assertEquals(List.of(shared.toAbsolutePath().normalize()), left, "only the concurrently changed file");
        assertEquals("a legitimate concurrent save", Files.readString(shared), "the newer write survives");
        assertEquals("untouched before", Files.readString(untouched), "an untouched file still rolls back");
        assertFalse(Files.exists(created), "a created file nobody else touched is still removed");
        assertEquals("theirs", Files.readString(root.resolve("sub/theirs_schema.toon")),
                "a directory holding someone else's file is left");
    }

    @Test
    void aFileTheImportCreatedAndSomeoneElseRewroteIsLeft(@TempDir Path root) throws Exception {
        Path created = root.resolve("b_pipeline.toon");
        ImportJournal j = new ImportJournal();
        j.write(created, b("imported"), ".t-");
        Files.writeString(created, "theirs");

        assertEquals(List.of(created.toAbsolutePath().normalize()), j.rollback());
        assertEquals("theirs", Files.readString(created), "not deleted out from under the other writer");
    }

    @Test
    void aConcurrentDeleteIsLeftAsADelete(@TempDir Path root) throws Exception {
        Path shared = root.resolve("a_pipeline.toon");
        Files.writeString(shared, "before");
        ImportJournal j = new ImportJournal();
        j.write(shared, b("imported"), ".t-");
        Files.delete(shared);

        assertEquals(List.of(shared.toAbsolutePath().normalize()), j.rollback());
        assertFalse(Files.exists(shared), "the other request's delete is not undone");
    }
}
