package com.gamma.geolink;

import com.gamma.control.HostContext;
import com.gamma.etl.PipelineConfig;
import com.gamma.la.core.UploadLandingPort;
import com.gamma.spi.http.ApiContext;

import java.nio.file.Paths;
import java.util.Optional;

/**
 * The BRIDGE's {@link UploadLandingPort}: resolves a Collector id (or Pipeline name) over the live Pipelines and answers only
 * for one that opted in with {@code collector.accepts_upload: true} and polls a local directory (the same poll-directory
 * resolution as {@code PipelineInboxRoutes}).
 */
public final class HostUploadLandingPort implements UploadLandingPort {

    @Override
    public Optional<Target> target(ApiContext api, String collector) {
        var service = HostContext.of(api).service();
        for (var pv : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(pv.name());
            if (cfg.isEmpty()) continue;
            PipelineConfig.Collector c = cfg.get().collector();
            if (!collector.equals(c.id()) && !collector.equals(pv.name())) continue;
            String poll = cfg.get().dirs().poll();
            if (!c.acceptsUpload() || !"local".equals(c.connector()) || c.hasDataset() || c.hasConnection()
                    || poll == null || poll.isBlank()) return Optional.empty();
            return Optional.of(new Target(c.id(), pv.name(), Paths.get(poll).toAbsolutePath().normalize(),
                    c.includes(), c.excludes()));
        }
        return Optional.empty();
    }
}
