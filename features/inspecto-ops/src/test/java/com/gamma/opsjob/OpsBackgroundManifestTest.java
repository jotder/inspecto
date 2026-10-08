package com.gamma.opsjob;

import com.gamma.control.ModuleGate;
import com.gamma.job.MaintenanceTaskProvider;
import com.gamma.module.ModuleManifest;
import com.gamma.module.ModuleManifests;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code MODULE-REORG-1 P4f}: what the {@code ops} manifest declares as gateable non-Job work equals what is actually
 * gated. {@code provides.background} == the background ids the host ticks gate ({@link ModuleGate#GATED_BACKGROUND}; ops is
 * the only module with any), and {@code provides.maintenanceTasks} == the tasks its {@link MaintenanceTaskProvider}
 * contributes. A stale list would either leave a disabled module's work running or gate work it no longer owns.
 */
class OpsBackgroundManifestTest {

    private static ModuleManifest ops() {
        return ModuleManifests.load(getClassLoader()).manifests().stream().filter(m -> m.id().equals("ops")).findFirst()
                .orElseThrow(() -> new AssertionError("no 'ops' manifest on the class path"));
    }

    private static ClassLoader getClassLoader() {
        return OpsBackgroundManifestTest.class.getClassLoader();
    }

    @Test
    void declaredBackgroundEqualsTheIdsTheHostGates() {
        assertEquals(new TreeSet<>(ModuleGate.GATED_BACKGROUND), new TreeSet<>(ops().provides().background()));
    }

    @Test
    void declaredMaintenanceTasksEqualTheProvidersTasks() {
        Set<String> contributed = new OpsMaintenanceTasks().tasks();
        assertEquals(new TreeSet<>(contributed), new TreeSet<>(ops().provides().maintenanceTasks()));
    }

    @Test
    void theRealManifestGatesIncidentPurgeAndTheSlaSweepWhenOpsIsOff(@org.junit.jupiter.api.io.TempDir java.nio.file.Path space) throws Exception {
        org.junit.jupiter.api.Assertions.assertNull(ModuleGate.taskReason("incident_purge", Set.of()), "ops on: the purge runs");
        String why = ModuleGate.taskReason("incident_purge", Set.of("ops"));
        org.junit.jupiter.api.Assertions.assertTrue(why != null && why.contains("'ops' module is switched off"), why);
        java.nio.file.Files.writeString(space.resolve("modules.toon"), "disabled[1]: ops\n");
        org.junit.jupiter.api.Assertions.assertTrue(ModuleGate.paused(ModuleGate.SLA_SWEEP, space));
    }
}
