package com.gamma.geolink;

import com.gamma.control.HostContext;
import com.gamma.control.ModuleSettings;
import com.gamma.la.api.EmbedExpirySweep;
import com.gamma.service.SpaceContext;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the periodic sweep of expired embed grants (LA-EMBED-EXPIRY-SWEEP-1). It registers no route: it only starts a daemon timer when
 * the API starts and stops it on close. Each tick sweeps every Space whose {@code geoLink} module is on; {@code -Dla.embed.enabled}
 * off makes the sweep itself do nothing.
 */
public final class EmbedExpirySweepModule implements RouteModule {

    static final String FEATURE = "geoLink";
    private static final long PERIOD_MINUTES = 10;

    @Override
    public void register(ApiContext api) {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embed-expiry-sweep");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                sweepSpaces(HostContext.of(api).spaces().all(), Instant.now());
            } catch (RuntimeException neverOutOfTheScheduler) {
                // the next tick retries
            }
        }, 1, PERIOD_MINUTES, TimeUnit.MINUTES);
        api.onClose(timer::shutdownNow);
    }

    static int sweepSpaces(Collection<SpaceContext> spaces, Instant now) {
        return sweepRoots(spaces.stream().map(ctx -> ctx.root().config()).toList(), now);
    }

    /** One tick over the Spaces' config roots: sweep each whose {@code geoLink} module is not switched off. Never throws. Returns the Investigations swept. */
    static int sweepRoots(Collection<Path> roots, Instant now) {
        int swept = 0;
        for (Path root : roots) {
            try {
                if (root == null || ModuleSettings.disabled(root).contains(FEATURE)) continue;
                swept += EmbedExpirySweep.sweep(root, now);
            } catch (RuntimeException oneSpace) {
                // one Space's failure never stops the others
            }
        }
        return swept;
    }
}
