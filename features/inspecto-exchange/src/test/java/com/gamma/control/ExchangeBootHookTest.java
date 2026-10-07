package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.metrics.MetricRegistry;
import com.gamma.query.SharedRefResolver;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Exchange's host-wide installs moved out of {@code ExchangeRoutes.register} into {@code ExchangeBootHook}
 * (MODULE-REORG-P5-TCKS). {@code register} no longer installs anything, so this proves the HOST still does, on a real
 * {@link ControlApi} boot with the module on the class path: the {@link SharedRefResolver}, the cross-Space Signal
 * forwarder (an {@link EventLog} tap) and both fence seams ({@link SignalOfferGrants}, {@link SharedItemConsumers}).
 * The host WITHOUT the module is pinned by the processor's {@code NoExchangeShipsInThePersonalBuildTest}
 * (every seam still {@code NONE}, zero taps). Test-scope split package, like {@code ControlApiExchangeTest}.
 */
class ExchangeBootHookTest {

    @AfterEach
    void reset() {
        SharedRefResolver.install(null);
        SignalOfferGrants.install(null);
        SharedItemConsumers.install(null);
        MetricRegistry.global().reset();
    }

    private static void removeForwarderTap() throws Exception {
        var f = Class.forName("com.gamma.exchange.ExchangeSignalForwarder").getDeclaredField("INSTANCE");
        f.setAccessible(true);
        EventLog.removeTap((java.util.function.BiConsumer<EventLog, com.gamma.audit.Event>) f.get(null));
    }

    @Test
    void aRealBootInstallsAllFourSeams(@TempDir Path root) throws Exception {
        reset();
        removeForwarderTap();
        int tapsBefore = EventLog.tapCount();
        assertSame(SharedRefResolver.NONE, SharedRefResolver.global());
        assertSame(SignalOfferGrants.NONE, SignalOfferGrants.global());
        assertSame(SharedItemConsumers.NONE, SharedItemConsumers.global());

        SpaceManager spaces = SpaceManager.discover(root);
        try (ControlApi api = new ControlApi(spaces, 0)) {
            assertNotSame(SharedRefResolver.NONE, SharedRefResolver.global(), "SharedRefResolver installed at boot");
            assertNotSame(SignalOfferGrants.NONE, SignalOfferGrants.global(), "SignalOfferGrants installed at boot");
            assertNotSame(SharedItemConsumers.NONE, SharedItemConsumers.global(), "SharedItemConsumers installed at boot");
            assertEquals(tapsBefore + 1, EventLog.tapCount(), "the signal forwarder tap installed at boot");
            // the installed fences answer from the real (empty) ledger
            assertFalse(SignalOfferGrants.global().granted("a", "b", "t"));
            assertEquals(List.of(), SharedItemConsumers.global().consumersOf("dataset", "x"));
        } finally {
            spaces.close();
        }
    }
}
