package com.gamma.la.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InvestigationStoreContract} over the filesystem implementation, plus what only a directory tree can show: that the
 * files the port writes are byte-for-byte the files {@link SnapshotStore} and {@link DraftStore} wrote before the port existed
 * (so a Space written by an older build reads back unchanged, and the sealed hashes defined over those bytes still hold), and
 * that a Draft's own log behaves as a scope.
 */
class FsInvestigationStoreContractTest extends InvestigationStoreContract {

    @TempDir Path root;

    @Override
    InvestigationStore fresh() {
        return new FsInvestigationStore(root);
    }

    private Path invDir(String id) {
        return root.resolve("audit").resolve("snapshots").resolve("investigations").resolve(id);
    }

    @Test
    void theFilesOnDiskAreExactlyWhatTheLegacyWritersProduced() throws Exception {
        InvestigationStore s = fresh();
        s.create("fs", "{\"id\":\"fs\"}");
        s.append(InvestigationStore.Scope.main("fs"), 0, 1, "{\"step\":1}", "{\"set\":1}");
        s.append(InvestigationStore.Scope.main("fs"), 1, 2, "{\"step\":2}", "{\"set\":2}");
        assertEquals("{\"id\":\"fs\"}", Files.readString(invDir("fs").resolve("header.json"), StandardCharsets.UTF_8));
        assertEquals("{\"step\":1}\n{\"step\":2}\n", Files.readString(invDir("fs").resolve("log.jsonl"), StandardCharsets.UTF_8),
                "one line per step, each ended by exactly one newline");
        assertEquals("{\"set\":2}", Files.readString(invDir("fs").resolve("sets").resolve("2.json"), StandardCharsets.UTF_8));
        // and the reverse: a log written by the legacy writer reads back through the port, so an existing Space is unchanged
        SnapshotStore legacy = new SnapshotStore(root);
        legacy.createInvestigation("old", "{\"id\":\"old\"}");
        legacy.appendStep("old", 1, "{\"step\":1,\"legacy\":true}", "{\"set\":1}");
        assertEquals(List.of("{\"step\":1,\"legacy\":true}"), s.log(InvestigationStore.Scope.main("old")));
        assertEquals(1, s.version(InvestigationStore.Scope.main("old")));
        s.append(InvestigationStore.Scope.main("old"), 1, 2, "{\"step\":2}", "{\"set\":2}");
        assertEquals(2, legacy.readLog("old").size());
    }

    @Test
    void aLineBeingAppendedRightNowIsNotYetPartOfTheLog() throws Exception {
        InvestigationStore s = fresh();
        s.create("p", "{}");
        s.append(InvestigationStore.Scope.main("p"), 0, 1, "{\"step\":1}", "{}");
        Files.writeString(invDir("p").resolve("log.jsonl"), "{\"step\":2,\"half", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);   // a writer is mid-line
        assertEquals(List.of("{\"step\":1}"), s.log(InvestigationStore.Scope.main("p")), "the committed prefix only");
        assertEquals(1, s.version(InvestigationStore.Scope.main("p")));
    }

    @Test
    void aDraftScopeAppendsToItsOwnLogAndRefusesAfterClose() throws Exception {
        InvestigationStore s = fresh();
        s.create("dr", "{}");
        Path inv = invDir("dr");
        String draft = DraftStore.newId();
        assertTrue(DraftStore.create(inv, draft, "{\"draftId\":\"" + draft + "\",\"baseStep\":0}"));
        var scope = InvestigationStore.Scope.draft("dr", draft);
        s.append(scope, 0, 1, "{\"step\":1}", "{\"set\":1}");
        assertThrows(InvestigationVersionConflictException.class, () -> s.append(scope, 0, 2, "{\"step\":9}", "{}"));
        s.append(scope, 1, 2, "{\"step\":2}", "{\"set\":2}");
        assertEquals(List.of("{\"step\":1}", "{\"step\":2}"), s.log(scope));
        assertEquals(List.of(), s.log(InvestigationStore.Scope.main("dr")), "the main log is untouched");
        assertTrue(DraftStore.markDiscarded(DraftStore.draftDir(inv, draft), "{}"));
        assertThrows(InvestigationStore.DraftClosedException.class, () -> s.append(scope, 2, 3, "{\"step\":3}", "{}"));
    }

    @Test
    void anIdThatWouldEscapeTheStoreIsRefused() {
        InvestigationStore s = fresh();
        assertThrows(IllegalArgumentException.class, () -> s.create("..", "{}"));
        assertThrows(IllegalArgumentException.class, () -> s.createTemplate("../x", "{}"));
    }
}
