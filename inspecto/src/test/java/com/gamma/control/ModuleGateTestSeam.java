package com.gamma.control;

import com.gamma.module.ModuleManifest;

import java.util.Map;

/** Test-side door to {@link ModuleGate}'s package-private owner seam, for tests that live in another package. */
public final class ModuleGateTestSeam {
    private ModuleGateTestSeam() {}

    /** Make {@code background} the owner table of background work ids (no maintenance task is owned). */
    public static void own(Map<String, ModuleManifest> background) {
        ModuleGate.ownersForTest(background, Map.of());
    }

    public static void reset() {
        ModuleGate.ownersForTest(null, null);
    }
}
