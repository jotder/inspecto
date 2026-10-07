package com.gamma.la.core;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.SpiSlot;

import java.util.Optional;

/**
 * The active {@link DatasetProvider}, or none — the same {@link SpiSlot} discovery the control plane uses for its other
 * optional seams. Absent, a route that needs a Dataset answers {@code 503 CAPABILITY_UNAVAILABLE} naming what is missing;
 * the feature reports itself absent rather than half-working.
 */
public final class DatasetProviders {

    static final String MESSAGE = "Link Analysis cannot read Datasets in this bundle - no Dataset provider is installed "
            + "(it is supplied by the inspecto-geo-link bridge module).";

    private static final SpiSlot<DatasetProvider> SLOT = new SpiSlot<>(DatasetProvider.class);

    private static volatile boolean forcedAbsent;

    private DatasetProviders() {}

    public static Optional<DatasetProvider> active() {
        return forcedAbsent ? Optional.empty() : SLOT.active();
    }

    /** The provider, or a clean {@code 503} {@link ApiException} when none is bound. */
    public static DatasetProvider require() {
        return active().orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE, MESSAGE));
    }

    /** Test seam: force the provider for this JVM; {@code null} re-arms classpath discovery. */
    public static void forTest(DatasetProvider provider) {
        SLOT.forTest(provider);
    }

    /** Test seam: behave as if no provider is bound ({@code true}) - even when the bridge is on the classpath - or restore ({@code false}). */
    public static void forTestAbsent(boolean absent) {
        forcedAbsent = absent;
    }
}
