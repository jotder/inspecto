package com.gamma.la.core;

import com.gamma.control.ApiContext;
import com.gamma.control.SpiSlot;

import java.util.Optional;

/** The active {@link CasePort}, or none (the bridge supplies it; a bundle without the ops module has no Cases to share with). */
public final class CasePorts {

    private static final SpiSlot<CasePort> SLOT = new SpiSlot<>(CasePort.class);

    private CasePorts() {}

    /** The bound port. */
    public static Optional<CasePort> active() {
        return SLOT.active();
    }

    /** The port, only when it is bound AND the host has Case management installed — otherwise "ops not installed". */
    public static Optional<CasePort> available(ApiContext api) {
        return SLOT.active().filter(p -> p.available(api));
    }

    /** Test seam: force the port for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(CasePort port) {
        SLOT.forTest(port);
    }
}
