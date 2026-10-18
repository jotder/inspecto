package com.gamma.la.core;

import com.gamma.spi.auth.SpiSlot;

import java.util.Optional;

/** The active {@link UploadLandingPort}, or none (the bridge supplies it). */
public final class UploadLandingPorts {

    private static final SpiSlot<UploadLandingPort> SLOT = new SpiSlot<>(UploadLandingPort.class);

    private UploadLandingPorts() {}

    /** The bound port. */
    public static Optional<UploadLandingPort> active() {
        return SLOT.active();
    }

    /** Test seam: force the port for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(UploadLandingPort port) {
        SLOT.forTest(port);
    }
}
