package com.gamma.geolink;

import com.gamma.control.HostContext;
import com.gamma.etl.FileNames;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.la.core.UploadLandingPort;
import com.gamma.spi.http.ApiContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The BRIDGE's {@link UploadLandingPort}: resolves a Collector id (or Pipeline name) over the live Pipelines and answers only
 * for one that opted in with {@code collector.accepts_upload: true} and polls a local directory (the same poll-directory
 * resolution as {@code PipelineInboxRoutes}). It also reads the Collector's own ingest footprint (marker, reject ledger,
 * quarantine folder) so the upload status read can say where a landed file is.
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
            return asTarget(pv.name(), cfg.get());
        }
        return Optional.empty();
    }

    @Override
    public List<Target> targets(ApiContext api) {
        var service = HostContext.of(api).service();
        List<Target> out = new ArrayList<>();
        for (var pv : service.pipelines())
            service.configFor(pv.name()).flatMap(cfg -> asTarget(pv.name(), cfg)).ifPresent(out::add);
        return out;
    }

    private static Optional<Target> asTarget(String pipeline, PipelineConfig cfg) {
        PipelineConfig.Collector c = cfg.collector();
        String poll = cfg.dirs().poll();
        if (!c.acceptsUpload() || !"local".equals(c.connector()) || c.hasDataset() || c.hasConnection()
                || poll == null || poll.isBlank()) return Optional.empty();
        return Optional.of(new Target(c.id(), pipeline, Paths.get(poll).toAbsolutePath().normalize(),
                c.includes(), c.excludes()));
    }

    @Override
    public Ingest ingest(ApiContext api, Target target, String file) {
        Optional<PipelineConfig> found = HostContext.of(api).service().configFor(target.pipeline());
        Path inFile = target.inbox().resolve(file);
        if (found.isEmpty()) return new Ingest(Files.exists(inFile) ? "pending" : "consumed", null, null);
        PipelineConfig cfg = found.get();
        Long rejected = rejectedRows(cfg, file);
        try {
            if (cfg.processing().duplicateCheckEnabled() && cfg.dirs().markers() != null
                    && Files.exists(MarkerManager.getMarkerPath(inFile.toFile(), cfg)))
                return new Ingest("ingested", rejected, null);
        } catch (RuntimeException unreadable) {
            // fall through: the other footprints still answer
        }
        String reason = quarantineReason(cfg, file);
        if (reason != null) return new Ingest("quarantined", rejected, reason);
        return new Ingest(Files.exists(inFile) ? "pending" : "consumed", rejected, null);
    }

    /** Rows in the per-file reject ledger ({@code <errors>/<stem>_errors.csv}, one header line), or null when absent. */
    private static Long rejectedRows(PipelineConfig cfg, String file) {
        try {
            if (cfg.dirs().errors() == null) return null;
            Path ledger = Paths.get(cfg.dirs().errors()).toAbsolutePath().resolve(FileNames.errorsFileName(cfg, file));
            if (!Files.isRegularFile(ledger)) return null;
            try (Stream<String> lines = Files.lines(ledger)) {
                return Math.max(0L, lines.filter(l -> !l.isBlank()).count() - 1);
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The quarantine reason folder holding a file of this name ({@code <quarantine>/<reason>/<name>}), bounded walk. */
    private static String quarantineReason(PipelineConfig cfg, String file) {
        try {
            if (cfg.dirs().quarantine() == null) return null;
            Path q = Paths.get(cfg.dirs().quarantine()).toAbsolutePath().normalize();
            if (!Files.isDirectory(q)) return null;
            try (Stream<Path> walk = Files.walk(q, 4)) {
                return walk.filter(p -> p.getFileName().toString().equals(file) && Files.isRegularFile(p))
                        .map(p -> p.getParent().getFileName().toString()).findFirst().orElse(null);
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
