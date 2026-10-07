package com.gamma.la.core;

import com.gamma.spi.http.ApiContext;
import com.gamma.control.SpiSlot;

import java.util.Optional;

/** The active {@link CasePort}, or none (the bridge supplies it; a bundle without the ops module has no Cases to share with). */
public final class CasePorts {

    private static final SpiSlot<CasePort> SLOT = new SpiSlot<>(CasePort.class);

    private static volatile boolean forcedAbsent;

    private CasePorts() {}

    /** The bound port. */
    public static Optional<CasePort> active() {
        return forcedAbsent ? Optional.empty() : SLOT.active();
    }

    /** The port, only when it is bound AND the host has Case management installed — otherwise "ops not installed". */
    public static Optional<CasePort> available(ApiContext api) {
        return active().filter(p -> p.available(api));
    }

    /** Test seam: force the port for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(CasePort port) {
        SLOT.forTest(port);
    }

    /** Test seam: behave as if no port is bound ({@code true}) - even when the bridge is on the classpath - or restore ({@code false}). */
    public static void forTestAbsent(boolean absent) {
        forcedAbsent = absent;
    }
}
