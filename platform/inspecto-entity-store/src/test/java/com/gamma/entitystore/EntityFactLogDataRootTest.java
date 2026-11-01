package com.gamma.entitystore;

import com.gamma.config.safety.DiscoveredRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPACE-DATA-ROOT-CONFIG-AUDIT-1: the identity fact log lives on the Space's data root once it has one. */
class EntityFactLogDataRootTest {

    @AfterEach
    void tearDown() {
        DiscoveredRoots.clear();
    }

    private static Path fact(Path dir, int seq) {
        return dir.resolve(String.format("%012d.json", seq));
    }

    @Test
    void anExistingLogMovesOntoTheDataRootAndItsChainContinuesThere(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("spaces/demo");
        Path config = Files.createDirectories(base.resolve("config"));
        DiscoveredRoots.register("demo", base);
        EntityFactLog before = new EntityFactLog(config);   // no data root yet: the old place
        EntityFactLog.Log head = before.append(before.read(), "a1", "open", "list.created", "wl",
                Map.of("title", "T", "purpose", "watch", "entityType", "msisdn"));
        assertTrue(Files.isRegularFile(fact(config.resolve("audit/entity-facts"), 1)));

        Path dataRoot = Files.createDirectories(tmp.resolve("mnt/demo"));
        DiscoveredRoots.registerDataRoot("demo", dataRoot);
        EntityFactLog after = new EntityFactLog(config);
        Path moved = dataRoot.resolve("audit/entity-facts");
        assertEquals(moved, after.directory());
        assertEquals(head.headHash(), after.read().headHash(), "the moved fact is read");
        after.append(after.read(), "a1", "add", "list.member.added", "wl", Map.of("keys", List.of("+441")));

        assertTrue(Files.isRegularFile(fact(moved, 1)));
        assertTrue(Files.isRegularFile(fact(moved, 2)), "new writes land under the data root");
        assertFalse(Files.exists(config.resolve("audit/entity-facts")), "nothing left in the config tree");
        assertEquals(2, new EntityFactLog(config).read().headSeq(), "the chain verifies across the move");
    }

    @Test
    void aFactOnlyTheOldLocationHoldsIsStillRead(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("spaces/demo");
        Path config = Files.createDirectories(base.resolve("config"));
        Path dataRoot = Files.createDirectories(tmp.resolve("mnt/demo"));
        DiscoveredRoots.register("demo", base);
        DiscoveredRoots.registerDataRoot("demo", dataRoot);
        EntityFactLog log = new EntityFactLog(config);   // moves nothing: the old tree is empty
        // a fact the move did not relocate (written by an older process after it ran)
        EntityFactLog old = new EntityFactLog(tmp.resolve("scratch"));
        old.append(old.read(), "a1", "open", "list.created", "wl", Map.of("title", "T"));
        Files.createDirectories(config.resolve("audit/entity-facts"));
        Files.copy(fact(old.directory(), 1), fact(config.resolve("audit/entity-facts"), 1));

        assertEquals(1, log.read().headSeq(), "the old-location fact is read");
        log.append(log.read(), "a1", "add", "list.member.added", "wl", Map.of("keys", List.of("+441")));
        assertEquals(2, log.read().headSeq());
        assertTrue(Files.isRegularFile(fact(dataRoot.resolve("audit/entity-facts"), 2)));
    }

    @Test
    void withoutADataRootTheLogStaysInTheConfigTree(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("spaces/demo");
        Path config = Files.createDirectories(base.resolve("config"));
        DiscoveredRoots.register("demo", base);
        DiscoveredRoots.registerDataRoot("demo", base);
        EntityFactLog log = new EntityFactLog(config);
        log.append(log.read(), "a1", "open", "list.created", "wl", Map.of("title", "T"));
        assertEquals(config.resolve("audit/entity-facts"), log.directory());
        assertTrue(Files.isRegularFile(fact(config.resolve("audit/entity-facts"), 1)));
        assertFalse(Files.exists(base.resolve("audit")));
    }
}
