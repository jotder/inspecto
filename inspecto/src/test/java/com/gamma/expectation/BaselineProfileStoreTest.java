package com.gamma.expectation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** DUCKLE-C8 — the durable profile history + accepted-profile store behind the {@code baseline} kind. */
class BaselineProfileStoreTest {

    private static Map<String, Map<String, Double>> rows(double n) {
        return Map.of(BaselineEvaluator.ALL, Map.of("row_count", n));
    }

    private static List<String> ids(List<Map<String, Object>> profiles) {
        return profiles.stream().map(p -> (String) p.get("id")).toList();
    }

    @Test
    void aRecordedProfileIsNotInTheWindowUntilAccepted(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        String p1 = s.record("e", rows(10), "FAILED", 1);
        assertEquals(List.of(), s.acceptedWindow("e", 5), "a refused run records its profile, unaccepted");
        s.acceptByRun("e", p1);
        assertEquals(List.of(p1), ids(s.acceptedWindow("e", 5)));
        assertTrue(Files.isRegularFile(wr.resolve("expectation-baselines").resolve("e.json")), "durable, on disk");
        assertEquals(List.of(p1), ids(new BaselineProfileStore(wr).acceptedWindow("e", 5)), "survives a new instance");
    }

    @Test
    void theWindowIsTheLastNAcceptedOldestFirst(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        for (int i = 0; i < 5; i++) {
            String id = s.record("e", rows(i), "PASSED", i);
            if (i != 2) s.acceptByRun("e", id);
        }
        assertEquals(List.of("p-4", "p-5"), ids(s.acceptedWindow("e", 2)));
        assertEquals(List.of("p-1", "p-2", "p-4", "p-5"), ids(s.acceptedWindow("e", 10)));
    }

    @Test
    void explicitAcceptIsAuditedWithTheWindowItReplaced(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        s.acceptByRun("e", s.record("e", rows(10), "PASSED", 1));
        String refused = s.record("e", rows(99), "FAILED", 2);

        Map<String, Object> op = s.accept("e", null, "alice", 5);   // null = most recent
        assertEquals("accept", op.get("op"));
        assertEquals("alice", op.get("actor"));
        assertEquals(refused, op.get("profileId"));
        assertEquals(Map.of("window", List.of("p-1")), op.get("replaced"));
        assertEquals(List.of("p-1", refused), op.get("window"));

        assertThrows(IllegalStateException.class, () -> s.accept("e", refused, "alice", 5), "already accepted → 409");
        assertThrows(NoSuchElementException.class, () -> s.accept("e", "p-77", "alice", 5), "unknown → 404");
        assertThrows(NoSuchElementException.class, () -> s.accept("fresh", null, "alice", 5), "no history → 404");
    }

    @Test
    void clearUnacceptsEverythingAndIsAuditedWithWhatItReplaced(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        s.acceptByRun("e", s.record("e", rows(1), "PASSED", 1));
        s.acceptByRun("e", s.record("e", rows(2), "PASSED", 2));
        Map<String, Object> op = s.clear("e", "bob");
        assertEquals("clear", op.get("op"));
        assertEquals(Map.of("accepted", List.of("p-1", "p-2")), op.get("replaced"));
        assertEquals(List.of(), s.acceptedWindow("e", 10));
        String json = Files.readString(wr.resolve("expectation-baselines").resolve("e.json"));
        assertTrue(json.contains("\"op\":\"clear\"") && json.contains("\"actor\":\"bob\""), "the op log is durable");
    }

    @Test
    void refusedRunsCanNeverEvictTheBaseline(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        String kept = s.record("e", rows(1), "PASSED", 0);
        s.acceptByRun("e", kept);
        for (int i = 0; i < BaselineProfileStore.MAX_UNACCEPTED + 20; i++) s.record("e", rows(i), "FAILED", i + 1);
        assertEquals(List.of(kept), ids(s.acceptedWindow("e", 10)));
    }

    @Test
    void anUnreadableStoreFailsClosedInsteadOfReadingAsNoHistory(@TempDir Path wr) throws IOException {
        Path dir = Files.createDirectories(wr.resolve("expectation-baselines"));
        Files.writeString(dir.resolve("e.json"), "{ not json");
        BaselineProfileStore s = new BaselineProfileStore(wr);
        IOException x = assertThrows(IOException.class, () -> s.acceptedWindow("e", 5));
        assertTrue(x.getMessage().contains("refusing to treat it as an empty history"), x.getMessage());
    }

    @Test
    void aNameCannotEscapeTheStoreDirectory(@TempDir Path wr) {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        for (String bad : List.of("../x", "..", "a/b", "a\\b", ""))
            assertThrows(IllegalArgumentException.class, () -> s.acceptedWindow(bad, 1), bad);
    }

    @Test
    void deleteDropsTheHistory(@TempDir Path wr) throws IOException {
        BaselineProfileStore s = new BaselineProfileStore(wr);
        s.acceptByRun("e", s.record("e", rows(1), "PASSED", 1));
        s.delete("e");
        assertEquals(List.of(), s.acceptedWindow("e", 5));
    }
}
