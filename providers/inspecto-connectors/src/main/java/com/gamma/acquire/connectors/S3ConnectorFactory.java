package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectorFactory;
import com.gamma.acquire.ExportConnector;
import com.gamma.acquire.ExportConnectorFactory;
import com.gamma.etl.PipelineConfig;

/**
 * {@link CollectorConnectorFactory} for the {@code s3} scheme (ACQ-4) — AWS S3, MinIO, and any S3-compatible
 * store. Discovered via {@code META-INF/services/com.gamma.acquire.CollectorConnectorFactory} when this module
 * is on the classpath — and, as the outbound {@link ExportConnectorFactory} (EXPORT-1), via
 * {@code META-INF/services/com.gamma.acquire.ExportConnectorFactory}: one {@link S3Connector} serves both
 * directions, so the signing and endpoint rules cannot drift between them.
 */
public final class S3ConnectorFactory implements CollectorConnectorFactory, ExportConnectorFactory {

    @Override
    public String scheme() {
        return "s3";
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg) {
        return create(cfg, null);
    }

    @Override
    public CollectorConnector create(PipelineConfig cfg, ConnectionProfile profile) {
        if (profile == null)
            throw new IllegalArgumentException("s3 source '" + cfg.collector().id()
                    + "' requires collector.connection to reference a *_connection.toon profile (endpoint/credentials)");
        return new S3Connector(profile);
    }

    @Override
    public ConnectionWorkbench workbench(ConnectionProfile profile) {
        return S3Connector.workbench(profile);
    }

    @Override
    public ExportConnector exporter(ConnectionProfile profile) {
        return new S3Connector(profile);
    }
}
