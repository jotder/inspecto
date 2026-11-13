package com.gamma.expectation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SE-4 step 1: a baseline history under {@code state/expectation-baselines} is read before {@code config/}'s; writes stay in config. */
class BaselineProfileStoreStateZoneTest {

    private static Map<String, Map<String, Double>> rows(double n) {
        return Map.of(BaselineEvaluator.ALL, Map.of("row_count", n));
    }

    private static Path inConfig(Path root) {
        return root.resolve("expectation-baselines").resolve("e.json");
    }

    private static Path inState(Path root) throws IOException {
        return Files.createDirectories(root.resolveSibling("state").resolve("expectation-baselines")).resolve("e.json");
    }

    @Test
    void aHistoryUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        BaselineProfileStore s = new BaselineProfileStore(root);
        s.acceptByRun("e", s.record("e", rows(10), "PASSED", 1));
        Files.move(inConfig(root), inState(root));
        assertEquals(1, new BaselineProfileStore(root).acceptedWindow("e", 5).size());
    }

    @Test
    void aHistoryUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        BaselineProfileStore s = new BaselineProfileStore(root);
        s.acceptByRun("e", s.record("e", rows(10), "PASSED", 1));
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals(1, new BaselineProfileStore(root).acceptedWindow("e", 5).size());
    }

    @Test
    void withBothTheStateHistoryWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        BaselineProfileStore s = new BaselineProfileStore(root);
        s.acceptByRun("e", s.record("e", rows(10), "PASSED", 1));
        byte[] oneAccepted = Files.readAllBytes(inConfig(root));
        s.clear("e", "admin");                                      // config/: no accepted profile
        assertEquals(List.of(), s.acceptedWindow("e", 5));
        Files.write(inState(root), oneAccepted);                    // state/: one

        assertEquals(1, s.acceptedWindow("e", 5).size(), "state/ wins");
        assertThrows(IOException.class, () -> s.record("e", rows(11), "PASSED", 2), "a newer release's history");
        assertThrows(IOException.class, () -> s.delete("e"), "a delete that cannot reach state/ says so");
        assertEquals(new String(oneAccepted), Files.readString(inState(root)), "state/ untouched");
    }
}
