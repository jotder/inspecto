package com.gamma.acquire;

import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Resolves the {@link CollectorConnector} for a pipeline from its {@code source.connector} scheme.
 *
 * <p>{@code "local"} (and the legacy no-{@code source:}-block case, which defaults to {@code "local"}) is
 * served by the built-in {@link LocalFileSystemConnector}, constructed from {@code dirs.poll}/{@code errors}/
 * {@code quarantine}. Any other scheme is looked up among the {@link CollectorConnectorFactory} services on the
 * classpath — so a connector module contributes new protocols without touching the core. An unknown scheme
 * fails fast with a clear message.
 */
public final class CollectorConnectors {

    private CollectorConnectors() {}

    public static CollectorConnector forConfig(PipelineConfig cfg) {
        if (!isRemote(cfg)) return localForConfig(cfg);
        String scheme = cfg.collector().connector();
        // Resolve the source.connection binding (if any) against the process-wide registry the service
        // publishes into, so the static poll path can hand a remote connector its host/credentials/base path.
        ConnectionProfile profile = cfg.collector().hasConnection()
                ? ConnectionRegistry.find(cfg.collector().connection()).orElse(null)
                : null;
        for (CollectorConnectorFactory f : ServiceLoader.load(CollectorConnectorFactory.class)) {
            if (f.scheme().equalsIgnoreCase(scheme)) return f.create(cfg, profile);
        }
        throw new IllegalArgumentException(
                "No source connector registered for connector '" + scheme + "' (built-in: local). "
                        + "Remote connectors ship in the optional connector module (Data Acquisition roadmap Phase E).");
    }

    /**
     * True when the pipeline acquires over a remote connector (anything but the built-in {@code local} one).
     * A blank/absent {@code source.connector} is the legacy no-{@code source:}-block case ⇒ local.
     */
    public static boolean isRemote(PipelineConfig cfg) {
        String scheme = cfg.collector().connector();
        return scheme != null && !scheme.isBlank() && !scheme.equalsIgnoreCase("local");
    }

    /**
     * The built-in inbox connector for {@code cfg}, regardless of the configured scheme. B3b's ingest path
     * always walks {@code dirs.poll} locally — a remote collector's files have already been fetched and
     * landed there — so it needs a LOCAL connector even when {@code source.connector} names a remote one.
     */
    public static LocalFileSystemConnector localForConfig(PipelineConfig cfg) {
        Path poll = abs(cfg.dirs().poll());
        Path errors = abs(cfg.dirs().errors());
        Path quarantine = abs(cfg.dirs().quarantine());
        // ready_marker (Phase B) makes the local connector answer readiness natively (READY iff the
        // sibling marker exists); null when no source.stability block ⇒ readiness UNKNOWN as before.
        return new LocalFileSystemConnector(poll, errors, quarantine, cfg.collector().stability().readyMarker());
    }

    private static Path abs(String dir) {
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
