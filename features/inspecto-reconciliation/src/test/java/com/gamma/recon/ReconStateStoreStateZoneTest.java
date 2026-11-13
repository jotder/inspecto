package com.gamma.recon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SE-4 step 1: a Reconciliation's state under {@code state/recon-state} is read before {@code config/}'s; writes stay in config. */
class ReconStateStoreStateZoneTest {

    private static ReconBreaks.Break open(String key) {
        return ReconBreaks.Break.fresh(key, null, "value_break", "amount", 1.0, 2.0, 1.0);
    }

    private static Path inConfig(Path root) {
        return root.resolve("recon-state").resolve("orders.json");
    }

    private static Path inState(Path root) throws IOException {
        return Files.createDirectories(root.resolveSibling("state").resolve("recon-state")).resolve("orders.json");
    }

    @Test
    void aStateUnderStateIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        new ReconStateStore(root).record("orders", List.of(open("EU")), "2026-07-01T00:00:00Z");
        Files.move(inConfig(root), inState(root));
        assertEquals(1, new ReconStateStore(root).read("orders").runs());
    }

    @Test
    void aStateUnderConfigIsRead(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        new ReconStateStore(root).record("orders", List.of(open("EU")), "2026-07-01T00:00:00Z");
        assertFalse(Files.exists(root.resolveSibling("state")), "nothing is written to state/");
        assertEquals(1, new ReconStateStore(root).read("orders").runs());
    }

    @Test
    void withBothTheStateOneWinsAndIsNeverRewrittenHere(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("config"));
        ReconStateStore store = new ReconStateStore(root);
        store.record("orders", List.of(open("EU")), "2026-07-01T00:00:00Z");
        byte[] oneRun = Files.readAllBytes(inConfig(root));
        store.record("orders", List.of(open("EU")), "2026-08-01T00:00:00Z");   // config/: two runs
        Files.write(inState(root), oneRun);                                      // state/: one

        assertEquals(1, store.read("orders").runs(), "state/ wins");
        assertThrows(IOException.class, () -> store.record("orders", List.of(), "2026-09-01T00:00:00Z"));
        assertThrows(IOException.class, () -> store.delete("orders"), "a delete that cannot reach state/ says so");
        assertTrue(Files.isRegularFile(inConfig(root)), "and a refused delete changes nothing");
        assertEquals(new String(oneRun), Files.readString(inState(root)), "state/ untouched");
    }
}
