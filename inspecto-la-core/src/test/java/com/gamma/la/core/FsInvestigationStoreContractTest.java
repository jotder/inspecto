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
        Files.createDirectories(invDir("old").resolve("sets"));
        Files.writeString(invDir("old").resolve("header.json"), "{\"id\":\"old\"}", StandardCharsets.UTF_8);
        Files.writeString(invDir("old").resolve("log.jsonl"), "{\"step\":1,\"legacy\":true}\n", StandardCharsets.UTF_8);
        Files.writeString(invDir("old").resolve("sets").resolve("1.json"), "{\"set\":1}", StandardCharsets.UTF_8);
        assertEquals(List.of("fs", "old"), s.ids(), "an Investigation written by an older build is listed and readable");
        assertEquals(List.of("{\"step\":1,\"legacy\":true}"), s.log(InvestigationStore.Scope.main("old")));
        assertEquals(1, s.version(InvestigationStore.Scope.main("old")));
        s.append(InvestigationStore.Scope.main("old"), 1, 2, "{\"step\":2}", "{\"set\":2}");
        assertEquals("{\"step\":1,\"legacy\":true}\n{\"step\":2}\n", Files.readString(invDir("old").resolve("log.jsonl"), StandardCharsets.UTF_8));
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

    /** Stands in for the JVM dying: an Error is not caught by the promote's rollback, exactly as a process death is not. */
    private static final class SimulatedCrash extends Error {
        SimulatedCrash(String why) { super(why); }
    }

    private record Promotable(InvestigationStore s, String draft, List<String> lines, List<String> sets, String mainHash, String ownHash) { }

    /** A 2-step main and a Draft of 3 own steps whose promote would append steps 3..5. */
    private Promotable promotable() throws Exception {
        InvestigationStore s = fresh();
        s.create("x", "{}");
        for (int i = 1; i <= 2; i++) s.append(InvestigationStore.Scope.main("x"), i - 1, i, "{\"step\":" + i + "}", "{\"m\":" + i + "}");
        String d = DraftStore.newId();
        s.createDraft("x", d, "{\"draftId\":\"" + d + "\",\"actor\":\"ann\",\"baseStep\":2}", "ann", 9);
        for (int k = 1; k <= 3; k++) s.append(InvestigationStore.Scope.draft("x", d), k - 1, 2 + k, "{\"step\":" + (2 + k) + ",\"d\":1}", "{\"d\":" + (2 + k) + "}");
        List<String> own = s.log(InvestigationStore.Scope.draft("x", d));
        return new Promotable(s, d, List.of("{\"step\":3,\"p\":1}", "{\"step\":4,\"p\":2}", "{\"step\":5,\"p\":3}"),
                java.util.Arrays.asList(null, "{\"fresh\":4}", null),
                DraftStore.prefixHash(s.log(InvestigationStore.Scope.main("x")), 2), DraftStore.prefixHash(own, own.size()));
    }

    @Test
    void aFailedPromoteLeavesTheMainLogAsItWasAndTheDraftOpen() throws Exception {
        Promotable p = promotable();
        List<String> before = p.s().log(InvestigationStore.Scope.main("x"));
        DraftStore.promoteHook = step -> { if (step == 4) throw new IllegalStateException("injected at step 4"); };
        try {
            assertThrows(IllegalStateException.class, () -> p.s().promoteDraft("x", p.draft(), 2, p.mainHash(), p.ownHash(), p.lines(), p.sets(), "{\"m\":1}"));
        } finally {
            DraftStore.promoteHook = step -> { };
        }
        assertEquals(before, p.s().log(InvestigationStore.Scope.main("x")), "the rollback truncated the steps this attempt wrote");
        assertTrue(p.s().set("x", 3).isEmpty() && p.s().set("x", 4).isEmpty(), "and removed their sets");
        assertEquals(InvestigationStore.DraftState.OPEN, p.s().draftState("x", p.draft()));
        assertFalse(Files.exists(invDir("x").resolve("drafts").resolve(p.draft()).resolve("promoting.json")), "no intent is left behind");
        p.s().promoteDraft("x", p.draft(), 2, p.mainHash(), p.ownHash(), p.lines(), p.sets(), "{\"m\":1}");   // and it can be promoted after all
        assertEquals(5, p.s().version(InvestigationStore.Scope.main("x")));
    }

    @Test
    void aPromoteInterruptedMidWayIsRolledBackByRecovery() throws Exception {
        Promotable p = promotable();
        List<String> before = p.s().log(InvestigationStore.Scope.main("x"));
        DraftStore.promoteHook = step -> { if (step == 4) throw new SimulatedCrash("killed before step 4"); };
        try {
            assertThrows(SimulatedCrash.class, () -> p.s().promoteDraft("x", p.draft(), 2, p.mainHash(), p.ownHash(), p.lines(), p.sets(), "{\"m\":1}"));
        } finally {
            DraftStore.promoteHook = step -> { };
        }
        assertEquals(3, p.s().version(InvestigationStore.Scope.main("x")), "the crash left a partial main step...");
        assertEquals(InvestigationStore.DraftState.OPEN, p.s().draftState("x", p.draft()), "...and an open Draft (the gap recovery closes)");
        p.s().recoverDrafts("x");
        assertEquals(before, p.s().log(InvestigationStore.Scope.main("x")), "recovery put the main log back");
        assertTrue(p.s().set("x", 3).isEmpty(), "and removed the stray set");
        assertEquals(InvestigationStore.DraftState.OPEN, p.s().draftState("x", p.draft()));
        p.s().recoverDrafts("x");   // idempotent
        assertEquals(before, p.s().log(InvestigationStore.Scope.main("x")));
        p.s().promoteDraft("x", p.draft(), 2, p.mainHash(), p.ownHash(), p.lines(), p.sets(), "{\"m\":1}");
        assertEquals(5, p.s().version(InvestigationStore.Scope.main("x")), "the Draft promotes cleanly afterwards");
    }

    @Test
    void aPromoteInterruptedAfterTheLastStepIsCompletedByRecovery() throws Exception {
        Promotable p = promotable();
        FsInvestigationStore.promoteBeforeMarkHook = () -> { throw new SimulatedCrash("killed before the marker"); };
        try {
            assertThrows(SimulatedCrash.class, () -> p.s().promoteDraft("x", p.draft(), 2, p.mainHash(), p.ownHash(), p.lines(), p.sets(), "{\"promotedBy\":\"ann\"}"));
        } finally {
            FsInvestigationStore.promoteBeforeMarkHook = () -> { };
        }
        assertEquals(5, p.s().version(InvestigationStore.Scope.main("x")), "every main step landed");
        assertEquals(InvestigationStore.DraftState.OPEN, p.s().draftState("x", p.draft()), "but the Draft was never marked");
        p.s().recoverDrafts("x");
        assertEquals(InvestigationStore.DraftState.PROMOTED, p.s().draftState("x", p.draft()), "recovery completes the promote");
        assertEquals("{\"promotedBy\":\"ann\"}", p.s().promoteMarker("x", p.draft()).orElseThrow(), "with the marker the promote recorded");
        assertEquals(5, p.s().version(InvestigationStore.Scope.main("x")), "main is untouched");
        assertEquals("{\"d\":3}", p.s().set("x", 3).orElseThrow(), "the shared set survived the Draft's evidence deletion");
        p.s().recoverDrafts("x");   // idempotent
        assertEquals(InvestigationStore.DraftState.PROMOTED, p.s().draftState("x", p.draft()));
    }

    @Test
    void anIdThatWouldEscapeTheStoreIsRefused() {
        InvestigationStore s = fresh();
        assertThrows(IllegalArgumentException.class, () -> s.create("..", "{}"));
        assertThrows(IllegalArgumentException.class, () -> s.createTemplate("../x", "{}"));
    }
}
