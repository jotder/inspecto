package com.gamma.la.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EmbedGrantStoreContract} over the filesystem implementation, plus what only a directory tree can show. */
class FsEmbedGrantStoreTest extends EmbedGrantStoreContract {

    @TempDir Path root;

    @Override
    InvestigationStore fresh() {
        return new FsInvestigationStore(root);
    }

    /**
     * The grant id of a public token is a STRANGER's input. A path built from it must never leave the grant directory: plant a pointer file
     * and a "grant" file exactly where {@code ../outside} would land if the id were joined unchecked, and look the id up.
     */
    @Test
    void aTraversalIdNeverReachesAFileOutsideTheGrantDirectory() throws Exception {
        InvestigationStore s = fresh();
        assertTrue(s.create("inv-1", "{\"id\":\"inv-1\"}"));
        Path snapshots = root.resolve("audit").resolve("snapshots");
        Files.writeString(snapshots.resolve("outside"), "inv-1", StandardCharsets.UTF_8);                       // embed-grants/../outside
        Files.writeString(snapshots.resolve("investigations").resolve("inv-1").resolve("outside.json"), "{\"stolen\":true}", StandardCharsets.UTF_8);   // embeds/../outside.json
        assertEquals(Optional.empty(), s.embedGrant("../outside"));
        assertEquals(Optional.empty(), s.embedGrant("..%2Foutside"));
        assertEquals(false, s.replaceEmbedGrant("../outside", "{\"overwritten\":true}"));
        assertEquals("{\"stolen\":true}", Files.readString(snapshots.resolve("investigations").resolve("inv-1").resolve("outside.json")), "nothing was written outside");
    }

    @Test
    void theRecordsLiveBesideTheInvestigationAndAPointerNamesItForALookupByGrantId() throws Exception {
        InvestigationStore s = fresh();
        assertTrue(s.create("inv-1", "{\"id\":\"inv-1\"}"));
        assertTrue(s.createEmbedGrant("inv-1", "Abcdefgh12345678", "{\"x\":1}"));
        Path snapshots = root.resolve("audit").resolve("snapshots");
        assertTrue(Files.isRegularFile(snapshots.resolve("investigations/inv-1/embeds/Abcdefgh12345678.json")));
        assertEquals("inv-1", Files.readString(snapshots.resolve("embed-grants/Abcdefgh12345678")));
        assertEquals(Optional.of("{\"x\":1}"), new FsInvestigationStore(root).embedGrant("Abcdefgh12345678"), "a second store on the same Space sees it");
        Files.delete(snapshots.resolve("investigations/inv-1/embeds/Abcdefgh12345678.json"));
        assertEquals(Optional.empty(), s.embedGrant("Abcdefgh12345678"), "a pointer whose record is gone is absent, not an error");
    }
}
