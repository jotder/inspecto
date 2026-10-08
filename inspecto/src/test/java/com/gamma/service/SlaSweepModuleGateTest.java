package com.gamma.service;

import com.gamma.control.FakeObjectEngineProvider;
import com.gamma.control.ModuleGateTestSeam;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.module.ModuleManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code MODULE-REORG-1 P4f}: the SLA sweep tick of {@code CollectorService} consults the Space's module gate. The
 * engine here is the core-test fake (it only counts sweeps), and the owner table is a stand-in, so the test proves the
 * WIRING: enabled -> the tick reaches the engine (the negative probe that would otherwise succeed), switched off in this
 * Space -> it does not, switched back on -> it does again with nothing re-created.
 */
class SlaSweepModuleGateTest {

    @AfterEach
    void restore() {
        ModuleGateTestSeam.reset();
    }

    @Test
    void theSweepTickSkipsWhileTheOwningModuleIsOffInThisSpaceAndResumes(@TempDir Path dir) throws Exception {
        ModuleManifest ops = new ModuleManifest("zz-ops", "Ops", "implementation", "optional", "boot",
                new ModuleManifest.Provides(List.of("zzOps"), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of("sla-sweep"), List.of()),
                ModuleManifest.Requires.NONE, null);
        ModuleGateTestSeam.own(Map.of("sla-sweep", ops));
        Path space = Files.createDirectories(dir.resolve("acme"));
        Path config = Files.createDirectories(space.resolve("config"));
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        ClassLoader outer = Thread.currentThread().getContextClassLoader();
        CollectorService svc;
        try {
            Thread.currentThread().setContextClassLoader(FakeObjectEngineProvider.fakeObjectEngineClassLoader(outer));
            svc = new CollectorService(List.of(toon), List.of(), List.of(), List.of(), List.of(), 3600, 1, null,
                    SpaceRoot.under(space));
        } finally {
            Thread.currentThread().setContextClassLoader(outer);
        }
        try (svc) {
            int base = FakeObjectEngineProvider.SWEEPS.get();
            svc.slaSweepTick();
            assertEquals(base + 1, FakeObjectEngineProvider.SWEEPS.get(), "module enabled: the tick reaches the engine");

            Files.writeString(config.resolve("modules.toon"), "disabled[1]: zzOps\n");
            svc.slaSweepTick();
            svc.slaSweepTick();
            assertEquals(base + 1, FakeObjectEngineProvider.SWEEPS.get(), "module off in this Space: ticks do nothing");

            Files.writeString(config.resolve("modules.toon"), "disabled[0]:\n");
            svc.slaSweepTick();
            assertEquals(base + 2, FakeObjectEngineProvider.SWEEPS.get(), "switched back on: the next tick sweeps");
        }
    }
}
