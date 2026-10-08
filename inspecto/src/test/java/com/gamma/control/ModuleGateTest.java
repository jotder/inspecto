package com.gamma.control;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gamma.module.ModuleManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code MODULE-REORG-1 P4f}: the shared gate for a module's background work and maintenance tasks. */
class ModuleGateTest {

    private static final ModuleManifest OWNER = new ModuleManifest("zz-owner", "Owner", "implementation", "optional", "boot",
            new ModuleManifest.Provides(List.of("zzFeature"), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of("zz-sweep"), List.of("zz_purge")),
            ModuleManifest.Requires.NONE, null);

    @AfterEach
    void restore() {
        ModuleGate.ownersForTest(null, null);
    }

    private static void own() {
        ModuleGate.ownersForTest(Map.of("zz-sweep", OWNER), Map.of("zz_purge", OWNER));
    }

    /** Rewrites modules.toon; the two bodies used differ in size, which is part of the cache stamp. */
    private static void modules(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("modules.toon"), body);
    }

    @Test
    void backgroundWorkIsPausedOnlyWhileItsModuleIsSwitchedOffInThatSpace(@TempDir Path acme, @TempDir Path beta) throws Exception {
        own();
        assertFalse(ModuleGate.paused("zz-sweep", acme), "no modules.toon: everything is on");
        modules(acme, "disabled[1]: zzFeature\n");
        assertTrue(ModuleGate.paused("zz-sweep", acme), "the owning module is off in this Space");
        assertFalse(ModuleGate.paused("zz-sweep", beta), "another Space is unaffected");
        assertFalse(ModuleGate.paused("not-declared", acme), "work no manifest declares belongs to no module");
        modules(acme, "disabled[0]:\n");
        assertFalse(ModuleGate.paused("zz-sweep", acme), "switched back on: resumed with nothing re-created");
    }

    @Test
    void anUnreadableModulesFileDisablesNothing(@TempDir Path dir) throws Exception {
        own();
        modules(dir, "disabled[2]: a\n  \t{{{ not toon\n");
        assertFalse(ModuleGate.paused("zz-sweep", dir), "fail-open, as for the routes");
    }

    @Test
    void theTransitionIsLoggedOncePerChangeAndNeverPerTick(@TempDir Path dir) throws Exception {
        own();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ModuleGate.class);
        logger.addAppender(logs);
        try {
            modules(dir, "disabled[1]: zzFeature\n");
            for (int i = 0; i < 5; i++) assertTrue(ModuleGate.paused("zz-sweep", dir));
            assertEquals(1, logs.list.size(), "five paused ticks log the pause once");
            assertTrue(logs.list.get(0).getFormattedMessage().contains("paused") && logs.list.get(0).getFormattedMessage().contains("zzFeature"));
            modules(dir, "disabled[0]:\n");
            for (int i = 0; i < 5; i++) assertFalse(ModuleGate.paused("zz-sweep", dir));
            assertEquals(2, logs.list.size(), "and the resume once, nothing while running normally");
            assertTrue(logs.list.get(1).getFormattedMessage().contains("resumed"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void aMaintenanceTaskIsTurnedAwayOnlyWhileItsModuleIsOffAndOnlyThatTask() {
        own();
        assertNull(ModuleGate.taskReason("zz_purge", Set.of()));
        String why = ModuleGate.taskReason("zz_purge", Set.of("zzFeature"));
        assertNotNull(why);
        assertTrue(why.contains("zzFeature") && why.contains("zz_purge") && why.contains("switched off in this Space"), why);
        assertNull(ModuleGate.taskReason("cleanup", Set.of("zzFeature")), "a built-in task has no module");
        assertNotNull(ModuleGate.taskReason("ZZ_PURGE", Set.of("zzFeature")), "ids match case-insensitively, as the Job's task: does");
    }

    @Test
    void pausedWorkListsBackgroundAndTasksOfAModuleThatIsOff() {
        assertEquals(List.of(), ModuleGate.pausedWork(OWNER, Set.of()));
        assertEquals(List.of("zz-sweep", "zz_purge"), ModuleGate.pausedWork(OWNER, Set.of("zzFeature")));
    }
}
