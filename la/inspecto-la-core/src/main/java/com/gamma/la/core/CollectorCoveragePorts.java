package com.gamma.la.core;

import com.gamma.spi.auth.SpiSlot;

import java.util.Optional;

/** The active {@link CollectorCoveragePort}, or none (the bridge supplies it). */
public final class CollectorCoveragePorts {

    private static final SpiSlot<CollectorCoveragePort> SLOT = new SpiSlot<>(CollectorCoveragePort.class);

    private CollectorCoveragePorts() {}

    /** The bound port. */
    public static Optional<CollectorCoveragePort> active() {
        return SLOT.active();
    }

    /** Test seam: force the port for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(CollectorCoveragePort port) {
        SLOT.forTest(port);
    }
}
