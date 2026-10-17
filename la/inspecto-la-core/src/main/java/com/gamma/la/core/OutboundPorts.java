package com.gamma.la.core;

import com.gamma.spi.auth.SpiSlot;

import java.util.Optional;

/** The active {@link OutboundPort}, or none (the bridge supplies it; a bundle without it cannot hand anything off). */
public final class OutboundPorts {

    private static final SpiSlot<OutboundPort> SLOT = new SpiSlot<>(OutboundPort.class);

    private OutboundPorts() {}

    /** The bound port. */
    public static Optional<OutboundPort> active() {
        return SLOT.active();
    }

    /** Test seam: force the port for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(OutboundPort port) {
        SLOT.forTest(port);
    }
}
